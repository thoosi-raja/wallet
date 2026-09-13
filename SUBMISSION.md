# Wallet & P2P transfer submission

- Live API: https://wallet-xrdr.onrender.com
- Public repository: https://github.com/thoosi-raja/wallet
- Reproduce locally: `docker compose up --build -d --wait && ./scripts/burst_test.sh`
- Reproduce remotely: `BASE_URL=https://wallet-xrdr.onrender.com ./scripts/burst_test.sh --db postgres` with target database credentials supplied through PG environment variables. A credential-free SQL-editor workflow is documented in README.md.
- Public logs: https://wallet-xrdr.onrender.com/logs — debit, credit, decline and replay events verified during the successful live burst on 2026-09-13. See the [verification report](docs/verification/2026-09-13-live-burst.md).

## Design and reasoning

`wallets` stores one wallet per unique user with a nonnegative BIGINT balance. `transfers` stores source, destination, positive BIGINT amount, status, decline reason, timestamp, request hash and a unique idempotency key. All money uses integer paise. JSON validation rejects fractional numbers, numeric strings and duplicate keys. Database constraints provide a second enforcement layer. Flyway applies versioned SQL; Hibernate validates the schema.

Each transfer uses a single PostgreSQL READ_COMMITTED transaction. Both wallet rows are locked with pessimistic write locks in lexical ID order, then the source balance is checked and both balances updated. A-to-B and B-to-A acquire locks in the same order. The transfer record and both updates commit together. Insufficient funds commits a declined record with no balance changes. Credit arithmetic checks 64-bit overflow before changing balances.

The idempotency key is unique in PostgreSQL and commits in the same transaction as the money movement. A matching request returns its original result; a different payload with the same key returns 409. The key is checked before and after locking. If concurrent inserts still race, the loser rolls back completely before reading the winner in a fresh transaction. Request correlation is in the response header so retry bodies stay identical.

Sorted row locks are simple for a two-wallet operation. A conditional debit can also work, but its destination update still locks another row and opposing transfers still require lock ordering or deadlock recovery. Serializable isolation adds retries and contention costs; process-local locks do not protect multiple instances. We choose consistency over availability: bounded pool, lock and transaction waits can return 503, and callers must retry with the same key. We do not claim uninterrupted availability on free hosting.

The API supports both original paths and `/api/v1`, with one `success`, `data`, `error` envelope. Bearer tokens identify the source wallet owner. The image uses a multi-stage build, non-root runtime and health check. JSON completion events and domain counters are emitted after commit. Request histograms support aggregate p99 queries. A process crash can lose a post-commit log or metric; database rows remain the durable record.

AI disclosure: I supplied the exercise requirements and directed the response envelopes, versioned routes, package layout, metrics changes and deployment. AI proposed the sorted-lock and idempotency-recovery design, which I accepted, and generated the implementation, tests and container configuration. I ran the live verification and used AI to investigate failures.

Cost target: ₹0 with Render Free and Neon Free. Cold starts and shared compute affect latency.

## Verification status

- Live PASS on 2026-09-13 at 15:51:51 UTC against Neon `wallet`: 50 concurrent provisioning requests, 30 identical transfers, conflicts/route aliases and 200 mixed transfers. SQL verified 201 rows (151 successful, 50 declined), exact per-wallet accounting and conservation of 4,000,000 paise. All 306 HTTP responses had expected statuses; mixed-stage client p99 was 54.45 seconds. See the [run report](docs/verification/2026-09-13-live-burst.md). The runner did not capture the deployed commit SHA.
- Local Maven verification passed with 3 unit tests and 29 PostgreSQL integration tests from a fresh export of committed source. Local Docker and HTTP burst checks also passed; their CPU budget differs from Render's shared allocation.
- Earlier live probes failed with HTTP 503 and 502 responses. The [verification history](docs/verification/2026-09-13-live-burst.md#earlier-verification) retains those results. The later pass does not establish the earlier restart cause.
