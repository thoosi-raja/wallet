# Live burst verification — 2026-09-13

The live API passed all five burst stages, including direct PostgreSQL verification. Results below match the saved HTTP responses, database output, summary and public log capture.

- API: https://wallet-xrdr.onrender.com
- Database: Neon, database name `wallet`
- Completed: 2026-09-13 at 15:51:51 UTC (21:21:51 IST)
- Run ID: `burst-02a1a896b6c748bcbcfb`
- Deployed commit: not captured by the runner. Local source was `cfa2a3f`.
- Command: `BASE_URL=https://wallet-xrdr.onrender.com ./scripts/burst_test.sh --db postgres`, with target database credentials supplied through local environment variables.

## HTTP checks

| Probe | Observed result | Client p99 |
| --- | --- | --- |
| 50 simultaneous get-or-create requests | 50 HTTP 200 responses; one distinct zero-balance wallet | 5,000.04 ms |
| 30 simultaneous identical transfers | One HTTP 201 and 29 HTTP 200 responses; all response bodies identical; one debit and credit | 7,048.12 ms |
| Reused key with a changed amount | HTTP 409; no additional balance movement | — |
| Replay through the alternate API route | HTTP 200 with the original body; no additional balance movement | — |
| 200 simultaneous mixed transfers | 150 HTTP 201 successes and 50 HTTP 422 insufficient-funds declines | 54,448.81 ms |
| Replay of a declined transfer | HTTP 200 with the original declined body | — |

All 306 HTTP responses were recorded: 104 HTTP 200, 151 HTTP 201, one expected HTTP 409 and 50 expected HTTP 422. There were no HTTP 5xx responses or recorded transport failures. The runner did not silently retry failed transfer requests.

## Database reconciliation

PostgreSQL reported 201 transfer rows: 151 successful and 50 declined. The retry storm accounts for the successful row in addition to the mixed stage's 150 successful rows.

| Test wallet index | Initial balance, paise | Final balance, paise |
| --- | ---: | ---: |
| 0 | 1,000,000 | 1,001,500 |
| 1 | 1,000,000 | 998,500 |
| 2 | 1,000,000 | 1,002,500 |
| 3 | 1,000,000 | 997,500 |
| Total | 4,000,000 | 4,000,000 |

All final balances were nonnegative. The four persisted balances matched both the HTTP results and balances recomputed from successful transfer rows in `database.txt`.

## Observability and evidence

`/metrics` returned HTTP 200 and exposed request duration buckets/counts plus successful-transfer, insufficient-funds and idempotent-replay counters. The runner checks these series are present; this live check does not assert counter deltas.

The public [logs endpoint](https://wallet-xrdr.onrender.com/logs) returned HTTP 200. The saved 200-event snapshot included transfer creation, debit, credit, success, decline and replay events. Every captured event carried a correlation ID. The bounded feed can subsequently replace these events or reset on restart.

Raw evidence is retained locally under `evidence/runs/burst-02a1a896b6c748bcbcfb/`: `summary.json`, `responses.jsonl`, `database.txt`, `verify.sql`, `metrics.txt`, `public-logs.json` and `state.json`. Raw evidence is gitignored; this report is the repository's reviewable summary.

## Earlier verification

- The local Maven suite passed against PostgreSQL 18.6: 3 unit tests and 29 integration tests. Flyway 11.20.3 migrated and validated the schema without the PostgreSQL version warning. A fresh export of committed source also passed all 32 tests against PostgreSQL 16.
- The updated image passed all five local HTTP burst stages with a 512 MB memory limit and one CPU: 201 transfers, 151 successful and 50 declined, with 4,000,000 paise conserved. Public log checks passed. The report remains at `evidence/local-memory-run/summary.json`; this CPU budget does not reproduce Render's shared allocation.
- A separate local test with a 0.1-CPU quota returned HTTP 503 under contention without a process restart. This did not identify the cause of the earlier Render restarts.
- The first live 50-request provisioning probe returned five HTTP 200 and 45 HTTP 503 responses. All successful responses returned one wallet. Supplied logs identified 36 connection-pool acquisition failures and nine lock failures. A warm repeat passed all 50 requests with one wallet. Both results remain recorded locally.
- Commit `4622f0f` passed the fresh-clone Maven, Docker and HTTP burst checks and [GitHub CI](https://github.com/thoosi-raja/wallet/actions/runs/34757289013). It deployed after correcting a malformed Render environment variable.
- Two earlier live 200-request mixed bursts returned empty HTTP 502 responses while the Render process became unavailable. The second completed its 30-request retry storm first, then returned 92 successes, 35 declines and 73 HTTP 502 responses in the mixed stage. Reconciliation SQL was generated for both runs. These remain failed historical probes.

The later successful run establishes the recorded outcome; it does not establish the earlier restart cause or guarantee every future free-tier burst will succeed.
