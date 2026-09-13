# Wallet & P2P transfer submission

- Live API: https://wallet-xrdr.onrender.com
- Public repository: https://github.com/thoosi-raja/wallet
- Reproduce locally: `docker compose up --build -d --wait && ./scripts/burst_test.sh`
- Reproduce remotely: `BASE_URL=https://wallet-xrdr.onrender.com ./scripts/burst_test.sh --db postgres` with target database credentials supplied through PG environment variables. A credential-free SQL-editor workflow is documented in README.md.
- Public logs endpoint: `/logs` serves a bounded snapshot of correlated domain events; deployed verification is pending. Generated reports and captured logs stay in the gitignored `evidence/` directory. GitHub CI uploads its results as workflow artifacts; see the [operations guide](docs/OPERATIONS.md).

## Design and reasoning

`wallets` stores one wallet per unique user with a nonnegative BIGINT balance. `transfers` stores source, destination, positive BIGINT amount, status, decline reason, timestamp, request hash and a unique idempotency key. All money uses integer paise. JSON validation rejects fractional numbers, numeric strings and duplicate keys. Database constraints provide a second enforcement layer. Flyway applies versioned SQL; Hibernate validates the schema.

Each transfer uses a single PostgreSQL READ_COMMITTED transaction. Both wallet rows are locked with pessimistic write locks in lexical ID order, then the source balance is checked and both balances updated. A-to-B and B-to-A acquire locks in the same order. The transfer record and both updates commit together. Insufficient funds commits a declined record with no balance changes. Credit arithmetic checks 64-bit overflow before changing balances.

The idempotency key is unique in PostgreSQL and commits in the same transaction as the money movement. A matching request returns its original result; a different payload with the same key returns 409. The key is checked before and after locking. If concurrent inserts still race, the loser rolls back completely before reading the winner in a fresh transaction. Request correlation is in the response header so retry bodies stay identical.

Sorted row locks are simple for a two-wallet operation. A conditional debit can also work, but its destination update still locks another row and opposing transfers still require lock ordering or deadlock recovery. Serializable isolation adds retries and contention costs; process-local locks do not protect multiple instances. We choose consistency over availability: bounded pool, lock and transaction waits can return 503, and callers must retry with the same key. We do not claim uninterrupted availability on free hosting.

The API supports both original paths and `/api/v1`, with one `success`, `data`, `error` envelope. Bearer tokens identify the source wallet owner. The image uses a multi-stage build, non-root runtime and health check. JSON completion events and domain counters are emitted after commit. Request histograms support aggregate p99 queries. A process crash can lose a post-commit log or metric; database rows remain the durable record.

AI disclosure: the candidate supplied the exercise and requested response envelopes, versioned routes, the models package, Prometheus modernization and deployment. AI proposed and implemented the sorted-lock transaction design and unique-key race recovery, generated the code/tests/container setup, and helped diagnose live failures. Implementation was initially committed as one batch; subsequent review changes are recorded as new work. These notes do not claim unaided implementation or a fabricated incremental history.

Cost target: ₹0, using Render Free and Neon Free within their limits. Free-tier sleep, compute limits and verification requirements apply; no production availability guarantee is claimed.

## Verification status

- The expanded local Maven suite passed against PostgreSQL 18.6: 3 unit tests and 29 integration tests. Flyway 11.20.3 migrated and validated the schema without the PostgreSQL version warning.
- The updated image passed all five local HTTP burst stages with a 512 MB memory limit and one CPU: 201 transfers, 151 successful and 50 declined; 4,000,000 paise conserved across four wallets. The public log feed checks passed. The report is retained under `evidence/local-memory-run/summary.json`; this CPU quota does not reproduce Render's shared CPU allocation.
- Live readiness, metrics and the versioned API were checked on Render.
- The first live 50-request provisioning probe failed: 5 HTTP 200 and 45 HTTP 503. All successful responses returned one wallet. Logs identified 36 connection-pool acquisition failures and 9 lock failures; no duplicate wallet was observed. The failed probe and supplied Render logs are retained locally.
- A warm repeat passed all 50 requests with one wallet. The report is retained locally.
- Commit `4622f0f` passed the fresh-clone Maven, Docker and HTTP burst checks and [GitHub CI](https://github.com/thoosi-raja/wallet/actions/runs/34757289013). It deployed after correcting a malformed Render environment variable.
- On that deployment, the live 30-request retry storm passed. The 200-request mixed burst returned 81 successes, 25 declines and 94 empty HTTP 502 responses; metrics confirmed the process restarted during the run. User-supplied Neon SQL results showed 107 committed transfers (82 successes including the original retry-storm transfer, 25 declines), exactly 4,000,000 paise and exact per-wallet reconciliation. This establishes conservation for that interrupted run, not a passing full live load probe. The updated image reserves more memory for JVM overhead; the platform restart cause and full live revalidation remain outstanding.
