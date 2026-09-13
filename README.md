# Wallet service

Java 21, Spring Boot 3.5.16, PostgreSQL 16/18, Spring MVC, JPA, Flyway 11.20.3 and Micrometer. All balances and amounts are signed 64-bit integer paise. HTTP request handling uses virtual threads.

[Submission](SUBMISSION.md) · [Live API](https://wallet-xrdr.onrender.com) · [Public logs](https://wallet-xrdr.onrender.com/logs) · [Metrics](https://wallet-xrdr.onrender.com/metrics) · [Live verification](docs/verification/2026-09-13-live-burst.md)

## Run

```bash
docker compose up --build
```

The service is available at `http://localhost:8080`. PostgreSQL uses a persistent Docker volume and is reachable only on the Compose network. Compose supplies a local development password; set `DB_PASSWORD` in your environment or a gitignored `.env` to override it. Set `WALLET_PORT` to change the host port. The application itself requires `DB_PASSWORD` and has no embedded password default.

```bash
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8080/actuator/prometheus
curl -fsS http://localhost:8080/metrics
./scripts/burst_test.sh
```

The burst script requires Python 3 and curl; local funding and SQL verification additionally use Docker Compose. It creates four fresh wallets, funds each with 1,000,000 paise, and checks 50 simultaneous get-or-create requests, 30 identical transfer retries, conflicting keys, cross-route idempotency, and 200 simultaneous mixed transfers including 50 overdrafts. It verifies exact final balances, 201 transfer rows (151 successful and 50 declined), and exported metrics. No failed transfer is silently retried. Each run saves response bodies, correlation IDs, HTTP status counts and observed p99 latency under `evidence/runs/`.

For the deployed service, use the same runner with the target database credentials in your local environment (not committed). Native `psql` is used if installed; otherwise Docker supplies the PostgreSQL client:

```bash
BASE_URL=https://wallet-xrdr.onrender.com ./scripts/burst_test.sh --db postgres
```

Set `PGHOST` to Neon's direct hostname, `PGDATABASE=wallet`, `PGUSER=neondb_owner`, `PGSSLMODE=require`, `PGCHANNELBINDING=require`, and supply the password through `PGPASSWORD` or, for native psql, a local password file. The runner passes credentials through environment variables and does not record them. Use the same database as the deployed app. Remote API URLs are rejected in Compose database mode to prevent funding the wrong database.

If you prefer Neon's SQL editor, no password needs to leave Neon:

```bash
./scripts/burst_test.sh --base-url https://wallet-xrdr.onrender.com --prepare --output evidence/runs/live
# Execute the generated seed.sql in Neon; it must report exactly four funded wallets.
./scripts/burst_test.sh --db manual --resume evidence/runs/live
```

Manual mode verifies HTTP invariants and generates `verify.sql` for independent row-count checks. Its report explicitly marks database verification as pending. Once a run has started transfers, it cannot be resumed: use new wallets and keys for another attempt. Funding SQL updates only the run's four fresh zero-balance wallets, atomically. Use `--api-prefix ''` to test the original unversioned routes. Warmup allows up to 120 seconds for a sleeping free instance; bursts use a 120-second request deadline.

For a local JVM, use Java 21 and an existing PostgreSQL 16 database:

```bash
export DB_URL=jdbc:postgresql://localhost:5432/wallet
export DB_USERNAME=wallet
export DB_PASSWORD=your-local-database-password
./mvnw spring-boot:run
```

Flyway creates the schema on first start; Hibernate validates it without modifying it. The Docker build compiles and runs unit tests on Java 21. The runtime uses the unprivileged `walletuser:walletgroup`, a read-only filesystem with writable `/tmp`, and an HTTP health check. Compose caps the app at 512 MB and one CPU; the JVM reserves at most 35% of container memory for its heap and 48 MB for code cache.

## API

Business routes are centralized in `constants/ApiRoutes.java`. Both `/wallets` and `/transfers` and their `/api/v1` equivalents are supported by the same controllers. They share authentication, response envelopes and idempotency keys. Read resource fields from `data`; `Location` headers use the canonical `/api/v1` paths. Monitoring endpoints remain `/metrics` and `/actuator/*` and retain their standard formats.

All business responses use the reusable `ApiResponse<T>` record:

```json
{"success":true,"data":{"id":"wallet-id","balance_paise":0},"error":null}
```

The `data` object above is abbreviated; full wallet fields are listed below. Failures have the same three top-level fields:

```json
{"success":false,"data":null,"error":{"code":"WALLET_NOT_FOUND","message":"Wallet not found"}}
```

An insufficient-funds result has `success: false`, the durable transfer in `data`, and `error.code: DECLINED_INSUFFICIENT_FUNDS`. Its initial response is `422`; replay and GET return `200` with the same envelope describing the original outcome. HTTP status describes handling of the current request, while `success` describes the business outcome. Request-specific correlation IDs stay in `X-Correlation-ID`; the envelope omits request timestamps so an idempotent retry returns an identical body. Validation, authorization, framework and unexpected errors also use this envelope while retaining their HTTP statuses and relevant headers.

All JSON field names use snake case. Wallet IDs are case-sensitive opaque strings; generated IDs are UUIDs. Transfer wallet IDs accept 1–64 ASCII letters, digits, underscores or hyphens. `user_id` is a nonblank string of at most 64 ASCII letters, digits, underscores or hyphens and is stored exactly as supplied.

Every wallet and transfer API call requires `Authorization: Bearer <user_id>`. This exercise token is intentionally simple: the token value is the authenticated user id. `POST /api/v1/wallets` only creates or returns the wallet for that same `user_id`; transfer creation requires the bearer user to own the source wallet. `GET /api/v1/transfers/{id}` is visible to either participant.

| Endpoint | Request | Result |
| --- | --- | --- |
| `POST /api/v1/wallets` | `{"user_id":"alice"}` | `200`, existing or newly created zero-balance wallet |
| `GET /api/v1/wallets/{id}` | — | `200`, current wallet; `404` if missing |
| `POST /api/v1/transfers` | `{"from":"wallet-id-a","to":"wallet-id-b","amount_paise":1000,"idempotency_key":"retry-key"}` | `201` success, `422` insufficient funds, or replay/conflict below |
| `GET /api/v1/transfers/{id}` | — | `200`, persisted transfer; `404` if missing |

Create a wallet:

```bash
curl -sS http://localhost:8080/api/v1/wallets \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer alice' \
  -d '{"user_id":"alice"}'
```

Wallet response `data` contains `id`, `user_id`, `balance_paise`, `created_at`, and `updated_at`. New wallets always start at zero. Funding is outside this transfer service; the burst script uses SQL exclusively for its isolated test wallets.

Transfer response `data` contains `id`, `idempotency_key`, `source_wallet_id`, `destination_wallet_id`, `amount_paise`, `status`, `decline_reason`, and `created_at`. A newly recorded transfer includes a `Location` header for its read endpoint. `decline_reason` is null for `SUCCESS` and `Insufficient funds` for `DECLINED_INSUFFICIENT_FUNDS`.

`idempotency_key` accepts 1–128 printable ASCII characters without whitespace. Its scope is the entire service/database. Use a fresh UUID for each intended transfer and retain that key for retries. The service also accepts the same key through the `Idempotency-Key` header for compatibility with standard retry clients; if both are supplied, they must match.

- Same key and same `SHA-256(from + "|" + to + "|" + amount_paise)`: `200` with `Idempotent-Replay: true` and the exact original response body. This also applies to an original `422` decline, even if the wallet has subsequently received funds.
- Same key and a different transfer: `409`, `Idempotency key reused with different body`.
- Insufficient funds: `422`, with a durable declined transfer and no balance changes.
- Invalid body/key, nonpositive amounts, fractional numbers, numeric strings, duplicate JSON keys, unknown fields, or same-wallet transfers: `400`.
- Missing source/destination: `404`.
- A destination credit exceeding `Long.MAX_VALUE`: `422`, `BALANCE_LIMIT_EXCEEDED`, with no changes and no transfer row.
- Database lock/pool timeout or temporary unavailability: `503` with `Retry-After: 1`. Retry with the same key, including when a response was lost and the commit outcome is unknown.

Only successful and insufficient-funds outcomes consume a key. Input validation runs before lookup. Keys are retained with transfer records indefinitely; deleting them would remove the retry guarantee. Error responses use the same envelope with `success: false`, `data: null`, and an `error` object containing `code` and `message`, without internal SQL or exception details. Correlation is available in the `X-Correlation-ID` response header. A transfer resource describes its original outcome, so its response excludes mutable wallet balances.

## Transaction and concurrency model

`TransferService` owns a `REQUIRES_NEW`, `READ_COMMITTED` transaction through `TransactionTemplate`. It checks idempotency, acquires the two wallet write locks in lexical ID order, then checks idempotency again after any lock wait. Wallets are first loaded through `PESSIMISTIC_WRITE` queries so the persistence context cannot supply a stale pre-lock balance. Both balance changes and the transfer row commit atomically. Insufficient funds is returned as a result, allowing the declined row to commit.

The second lookup handles identical retries sharing the same wallet pair efficiently. Concurrent requests reusing a key across independent pairs can still race at insertion. Only a violation of `uq_transfers_idempotency_key` triggers recovery: the losing transaction rolls back completely before a fresh transaction loads the winner. A mismatched hash then produces `409`. Other integrity errors propagate and roll back. PostgreSQL's uniqueness check waits for the competing transaction's outcome, so the winner is committed before recovery reads it.

Credit arithmetic uses `Math.addExact` before either balance is changed. Debits occur only when the source can cover a positive amount. PostgreSQL's `chk_balance_non_negative` also rejects direct negative writes. Additional database checks enforce positive transfers, distinct wallets, valid statuses and hashes, and consistent decline reasons. Unique constraints supply the requested indexes on `user_id` and `idempotency_key`; extra indexes cover the transfer foreign keys.

`WalletService` uses `INSERT ... ON CONFLICT (user_id) DO NOTHING` followed by a separate read in the same `READ_COMMITTED` transaction. That read sees any competing upsert winner after it commits.

DTOs and service results are immutable Java records. Wallet mutations require row locks, and transfer entities are immutable. Multiple service instances coordinate through PostgreSQL. Service write methods own their transactions; invoke them without holding an outer database transaction or wallet locks.

HikariCP allows six connections and waits at most 60 seconds for a connection. PostgreSQL lock timeout is 15 seconds, statement timeout is 20 seconds, transaction timeout is 30 seconds, and open-in-view is disabled. Contention exceeding these bounds produces a retryable failure rather than an unbounded wait. Conservation applies to the transfer engine; any separate funding or administration path must obey the same database locking and accounting rules.

Flyway manages the PostgreSQL schema through versioned SQL migrations; Hibernate validates it at startup. Keep applied migrations unchanged and add future schema changes as `V2__...sql`, `V3__...sql`, and so on.

## Observability

Standard output is JSON encoded by `LogstashEncoder`. Events include `wallet_provisioned`, `transfer_initiated`, `transfer_created`, `wallet_debited`, `wallet_credited`, `transfer_success`, `transfer_declined_insufficient_funds`, and `idempotent_replay_hit`. Every creation/debit/credit/completion event is emitted only after commit, with the transfer ID and request correlation ID. Replays emit no new debit or credit event. Request bodies and idempotency keys are not included in domain event logs. `X-Correlation-ID` is returned and put in MDC; missing or unsafe values are replaced with a UUID, and MDC is cleared in a `finally` block. Accepted supplied IDs contain 1–128 letters, digits, dots, underscores, colons or hyphens.

The public `/logs` endpoint returns the latest 200 selected domain events in the shared JSON envelope. Its bounded buffer omits request bodies, tokens, idempotency keys, hashes and exception details, and resets on restart.

On a small instance, at most eight transfer requests enter the service at once. Additional requests wait fairly for up to 120 seconds, then receive a retryable `503 TRANSFER_QUEUE_FULL` response. This limits transfer work reaching the database while preserving idempotent retry behavior; the semaphore does not cap the number of waiting HTTP requests. Wallet provisioning and read endpoints do not use this gate. JSON stdout logging uses a 2,048-event asynchronous buffer; if it fills, logging callers block until space becomes available, which can delay transfer responses even after commit.

`/metrics` and `/actuator/prometheus` export request rate, latency and error-rate inputs through `http_server_requests_seconds` with status/outcome tags and histogram buckets for p99 queries. They also export:

- `wallet_transfers_successful_total`
- `wallet_transfers_declined_insufficient_funds_total`
- `wallet_transfers_idempotent_replays_total`

Counters and completion logs are recorded after commit, so rollbacks do not count as completed transfers. They are process-level telemetry, reset on restart, and can miss a commit if the process stops before emitting the metric. PostgreSQL transfer rows are the durable accounting record. Health is exposed at `/actuator/health`, with liveness and database-aware readiness groups under `/actuator/health/liveness` and `/actuator/health/readiness`.

`/metrics` and `/actuator/prometheus` scrape the same Micrometer registry. The integration suite checks the exported counter names and HTTP histogram output; [OPERATIONS.md](docs/OPERATIONS.md#metrics) contains PromQL queries.

## Verification

```bash
./mvnw test       # Unit tests; Docker is not required
./mvnw verify     # Unit and PostgreSQL 16 Testcontainers integration tests; Docker is required
```

Integration tests send concurrent HTTP requests through the actual MVC server. They verify provisioning races, identical replays after exhausting the source, conflicting payloads, 50 simultaneous debits, 200 bidirectional transfers, durable declined replays, forced unique-index races and rollback recovery, integer overflow and maximum-long precision, malformed input, the direct database overdraft constraint, unrelated integrity failures, correlation headers, virtual request threads, metrics, health, and read endpoints. Use `./mvnw verify -Dtest.postgres.image=postgres:18.6-alpine` to run against the deployed Neon database version. Flyway is pinned to 11.20.3 for PostgreSQL 18 support. Tests require real PostgreSQL and fail if Docker is unavailable; no H2 approximation or silent test skipping is used.

## Deployment and evidence

API: https://wallet-xrdr.onrender.com. Repository: https://github.com/thoosi-raja/wallet.
Use `/actuator/health/readiness` for the host health check and `/metrics` for Prometheus. The Docker health check follows `PORT` (8080 by default). Live readiness does not establish live concurrency correctness; current evidence and any remaining checks are recorded in [SUBMISSION.md](SUBMISSION.md).

GitHub Actions checks a fresh checkout with Maven/PostgreSQL tests, Docker Compose, and the same burst command. CI stores its test reports, burst responses and container logs as artifacts. The [operations guide](docs/OPERATIONS.md) contains PromQL and the live log capture procedure.
