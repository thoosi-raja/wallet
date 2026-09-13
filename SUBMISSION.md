# Wallet & P2P transfer submission

- Live API: https://wallet-xrdr.onrender.com
- Public repository: https://github.com/thoosi-raja/wallet
- Reproduce locally: `docker compose up --build -d --wait && ./scripts/burst_test.sh`
- Reproduce remotely: `BASE_URL=https://wallet-xrdr.onrender.com ./scripts/burst_test.sh --db postgres` with target database credentials supplied through PG environment variables. A credential-free SQL-editor workflow is documented in README.md.
- Logs/evidence: see [evidence](evidence/) and the [operations guide](docs/OPERATIONS.md). A public recording of the complete deployed burst remains outstanding. The included live failure logs are actual supplied Render output, not a success demonstration.

## Design and reasoning

`wallets` stores one wallet per unique user with a nonnegative BIGINT balance. `transfers` stores source, destination, positive BIGINT amount, status, decline reason, timestamp, request hash and a unique idempotency key. All money uses integer paise. JSON validation rejects fractional numbers, numeric strings and duplicate keys. Database constraints provide a second enforcement layer. Flyway applies versioned SQL; Hibernate validates the schema.

Each transfer uses a single PostgreSQL READ_COMMITTED transaction. Both wallet rows are locked with pessimistic write locks in lexical ID order, then the source balance is checked and both balances updated. A-to-B and B-to-A acquire locks in the same order. The transfer record and both updates commit together. Insufficient funds commits a declined record with no balance changes. Credit arithmetic checks 64-bit overflow before changing balances.

The idempotency key is unique in PostgreSQL and commits in the same transaction as the money movement. A matching request returns its original result; a different payload with the same key returns 409. The key is checked before and after locking. If concurrent inserts still race, the loser rolls back completely before reading the winner in a fresh transaction. Request correlation is in the response header so retry bodies stay identical.

Sorted row locks are simple for a two-wallet operation. A conditional debit can also work, but its destination update still locks another row and opposing transfers still require lock ordering or deadlock recovery. Serializable isolation adds retries and contention costs; process-local locks do not protect multiple instances. We choose consistency over availability: bounded pool, lock and transaction waits can return 503, and callers must retry with the same key. We do not claim uninterrupted availability on free hosting.

The API supports both original paths and `/api/v1`, with one `success`, `data`, `error` envelope. Bearer tokens identify the source wallet owner. The image uses a multi-stage build, non-root runtime and health check. JSON completion events and domain counters are emitted after commit. Request histograms support aggregate p99 queries. A process crash can lose a post-commit log or metric; database rows remain the durable record.

AI disclosure: the candidate supplied the exercise and requested response envelopes, versioned routes, the models package, Prometheus modernization and deployment. AI proposed and implemented the sorted-lock transaction design and unique-key race recovery, generated the code/tests/container setup, and helped diagnose live failures. Implementation was initially committed as one batch; subsequent review changes are recorded as new work. These notes do not claim unaided implementation or a fabricated incremental history.

Cost target: ₹0, using Render Free and Neon Free within their limits. Free-tier sleep, compute limits and verification requirements apply; no production availability guarantee is claimed.

## Verification status

- The expanded local Maven suite passed: 2 unit tests and 28 PostgreSQL integration tests.
- The local HTTP burst passed all five stages with exact SQL counts: 201 transfers, 151 successful and 50 declined; 4,000,000 paise conserved across four wallets. See [local report](evidence/local-summary.json).
- Live readiness, metrics and the versioned API were checked on Render.
- The first live 50-request provisioning probe failed: 5 HTTP 200 and 45 HTTP 503. All successful responses returned one wallet. Logs identified 36 connection-pool acquisition failures and 9 lock failures; no duplicate wallet was observed. See [failed probe](evidence/live-provisioning-failed.json) and [actual failure logs](evidence/live-failure-logs.jsonl).
- A warm repeat passed all 50 requests with one wallet. See [warm probe](evidence/live-provisioning-warm.json).
- Longer bounded connection/lock waits are being deployed in response to the measured failure. Cold-start revalidation, live funded transfer storms, independent Neon row counts and a full public log recording remain pending. A warm provisioning pass is not a full live correctness pass.
