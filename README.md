# Wallet service

Java 21, Spring Boot 3.5.16, PostgreSQL 16, Spring MVC, JPA, Flyway and Micrometer. All balances and amounts are signed 64-bit integer paise. HTTP request handling uses virtual threads.

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

The burst script requires Bash, curl, jq and Docker Compose. It creates two fresh test wallets, seeds each with 1,000,000 paise using SQL inside the local PostgreSQL container, and verifies all four requested concurrency scenarios. It leaves those wallets and their 51 transfer records available for inspection. Repeated runs use new users and keys. Use `BASE_URL` for a different service URL backed by the same Compose database; `WALLET_PORT` is also respected.

For a local JVM, use Java 21 and an existing PostgreSQL 16 database:

```bash
export DB_URL=jdbc:postgresql://localhost:5432/wallet
export DB_USERNAME=wallet
export DB_PASSWORD=your-local-database-password
./mvnw spring-boot:run
```

Flyway creates the schema on first start; Hibernate validates it without modifying it. The Docker build compiles and runs unit tests on Java 21. The runtime uses the unprivileged `walletuser:walletgroup`, a read-only filesystem with writable `/tmp`, and an HTTP health check.

## API

Business routes are centralized in `constants/ApiRoutes.java` under `/api/v1`. This is a breaking change from the unversioned `/wallets` and `/transfers` paths; callers must use the versioned paths and read resource fields from `data`. `Location` headers also use the versioned paths. Monitoring endpoints remain `/metrics` and `/actuator/*` and retain their standard formats.

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

Wallet response `data` contains `id`, `user_id`, `balance_paise`, `created_at`, and `updated_at`. New wallets always start at zero. Funding is outside this transfer service; the burst script uses SQL exclusively for isolated local test data.

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

DTOs and service results are immutable Java records. Table entities (`Wallet`, `Transfer`) and their status enum live in the `models` package. JPA entities use the classes and protected no-argument constructors required by JPA; wallet mutation is confined to locked transactions and transfers are marked immutable. The application contains no JVM monitor blocks or process-local balance locks. Multiple service instances coordinate through PostgreSQL. Service write methods own their transactions, so callers should invoke them without holding an outer database transaction or wallet locks.

HikariCP allows 20 connections and waits at most 5 seconds for a connection. PostgreSQL lock timeout is 4 seconds, statement timeout is 10 seconds, transaction timeout is 15 seconds, and open-in-view is disabled. Contention exceeding these bounds produces a retryable failure rather than an unbounded wait. Conservation applies to the transfer engine; any separate funding or administration path must obey the same database locking and accounting rules.

## Why Flyway rather than Liquibase?

This service targets one database engine and currently has one small, PostgreSQL-specific SQL migration. Flyway's ordered SQL files and checksum history are sufficient and keep constraints and indexes easy to review. Hibernate only validates the result. Applied migrations must remain unchanged; future schema changes belong in new `V2__...sql`, `V3__...sql` files. Moving Java entities to `models` does not change table names or require a database migration. See [Flyway versioned migrations](https://documentation.red-gate.com/fd/versioned-migrations-273973333.html).

Liquibase is also a valid choice, especially if the team needs structured changesets, explicit database [preconditions](https://www.liquibase.com/technical-glossary/preconditions), or a changeset [rollback workflow](https://docs.liquibase.com/community/reference-guide-5-0/init-update-and-rollback-commands/rollback). This project does not currently need those features, so Flyway remains in place. Neither tool supplies runtime transfer atomicity; PostgreSQL transactions, locks and constraints do that. A schema rollback also cannot undo already committed business transfers safely.

## Observability

Standard output is JSON encoded by `LogstashEncoder`. Events include `wallet_provisioned`, `transfer_initiated`, `transfer_success`, `transfer_declined_insufficient_funds`, and `idempotent_replay_hit`. Request bodies and idempotency keys are not included in domain event logs. `X-Correlation-ID` is returned and put in MDC; missing or unsafe values are replaced with a UUID, and MDC is cleared in a `finally` block. Accepted supplied IDs contain 1–128 letters, digits, dots, underscores, colons or hyphens.

`/metrics` and `/actuator/prometheus` export request rate, latency and error-rate inputs through `http_server_requests_seconds` with status/outcome tags and histogram buckets for p99 queries. They also export:

- `wallet_transfers_successful_total`
- `wallet_transfers_declined_insufficient_funds_total`
- `wallet_transfers_idempotent_replays_total`

Counters and completion logs are recorded after commit, so rollbacks do not count as completed transfers. They are process-level telemetry, reset on restart, and can miss a commit if the process stops before emitting the metric. PostgreSQL transfer rows are the durable accounting record. Health is exposed at `/actuator/health`, with liveness and database-aware readiness groups under `/actuator/health/liveness` and `/actuator/health/readiness`.

The service uses the supported `micrometer-registry-prometheus` dependency and Spring Boot's auto-configured registry and `/actuator/prometheus` endpoint. `/metrics` scrapes that same registry. The successful-transfer counter was renamed from `wallet_transfers_created_total` to `wallet_transfers_successful_total`: the newer client reserves the `_created` suffix, so retaining the old name would silently change its exported series. Update any existing scrape queries or dashboards to the new name. See the [Micrometer migration guide](https://github.com/micrometer-metrics/micrometer/wiki/1.13-Migration-Guide) and [Boot endpoint documentation](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html). The integration suite checks the actual scrape names and HTTP histogram output.

## Verification

```bash
./mvnw test       # Unit tests; Docker is not required
./mvnw verify     # Unit and PostgreSQL 16 Testcontainers integration tests; Docker is required
```

Integration tests send concurrent HTTP requests through the actual MVC server. They verify provisioning races, identical replays after exhausting the source, conflicting payloads, 50 simultaneous debits, 50 bidirectional transfers, durable declined replays, forced unique-index races and rollback recovery, integer overflow and maximum-long precision, malformed input, the direct database overdraft constraint, unrelated integrity failures, correlation headers, virtual request threads, metrics, health, and read endpoints. They require real PostgreSQL and fail if Docker is unavailable; no H2 approximation or silent test skipping is used.

## Deployment boundary

This implements the specified wallet engine with a deliberately simple bearer-token boundary for the exercise. Payment funding/settlement and a general ledger are outside this API. Public deployment still needs TLS, managed database credentials, restricted database privileges, backups and recovery. Compose binds the HTTP port to loopback and uses a local development database account; use deployment-specific credentials and network policy for a shared environment.

Implementation references: [Spring Boot 3.5 requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [virtual threads](https://docs.spring.io/spring-boot/3.5/reference/features/spring-application.html#features.spring-application.virtual-threads), [PostgreSQL 16 row locking and consistent lock order](https://www.postgresql.org/docs/16/explicit-locking.html), and [PostgreSQL transaction isolation](https://www.postgresql.org/docs/16/transaction-iso.html).
