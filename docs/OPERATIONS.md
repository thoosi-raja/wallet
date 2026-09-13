# Operating and demonstrating the wallet service

Business endpoints accept `Authorization: Bearer <user_id>`. Resource fields are under `data` in the common `success`, `data`, `error` envelope. Both original and `/api/v1` routes are supported. An initial successful transfer returns 201; a replay returns 200 with the identical body and `Idempotent-Replay: true`. A persisted insufficient-funds decline initially returns 422, and its replay returns 200 with that same declined body.

## Live verification

Use `./scripts/burst_test.sh --base-url https://wallet-xrdr.onrender.com --db postgres` with the target database's PG environment variables. Only four newly created test wallets are funded. Keep `summary.json`, `responses.jsonl`, `database.txt` and `metrics.txt` from the run as evidence. Record the deployed commit SHA alongside the results. A nonzero exit or `failure.json` is a failed probe, even if subsequent retries succeed; preserve failed evidence when investigating.

Without database credentials, use `--prepare`, execute the generated `seed.sql` in Neon's SQL editor, then `--db manual --resume <directory>`. Independently execute the generated `verify.sql` afterwards; expected transfer counts are 201 total, 151 successful and 50 declined, and the four wallets total 4,000,000 paise. The result intentionally does not claim database verification until those SQL results are checked.

For strict evaluator tooling, point it at either `/wallets` and `/transfers` or `/api/v1/wallets` and `/api/v1/transfers`, and extract `.data.id`, `.data.balance_paise`, and `.data.status`.

## Metrics

Scrape `https://wallet-xrdr.onrender.com/metrics` at a regular interval. Example queries (the URI regular expression covers both API route forms):

```promql
# Requests per second
sum(rate(http_server_requests_seconds_count{uri=~"(/api/v1)?/(wallets|transfers).*"}[5m]))

# Aggregate p99 in seconds, computed from histogram buckets
histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{uri=~"(/api/v1)?/(wallets|transfers).*"}[5m])))

# 5xx response fraction; intended 409 conflicts and 422 declines are not server errors
sum(rate(http_server_requests_seconds_count{uri=~"(/api/v1)?/(wallets|transfers).*",status=~"5.."}[5m]))
/
clamp_min(sum(rate(http_server_requests_seconds_count{uri=~"(/api/v1)?/(wallets|transfers).*"}[5m])), 0.000001)

increase(wallet_transfers_successful_total[5m])
increase(wallet_transfers_declined_insufficient_funds_total[5m])
increase(wallet_transfers_idempotent_replays_total[5m])
```

Counters reset on app restart and are telemetry, not an accounting ledger. The burst runner's p99 is measured end to end at the client and differs from server processing latency.

## Log evidence

1. Open the deployed Render service's Logs view and start a screen recording that shows the service identity and timestamps.
2. Run the live burst command. Filter the logs by the `burst-...` run ID found in its `state.json`; every request correlation ID starts with that ID.
3. Show `transfer_created`, `wallet_debited`, `wallet_credited`, `transfer_declined_insufficient_funds`, and `idempotent_replay_hit`. Match a declined response's `correlation_id` in `responses.jsonl` to its server log event.
4. Show the final test result and database verification. Publish the recording at a reviewer-accessible URL and put that link in `SUBMISSION.md`.

The Render dashboard itself is account-restricted. Do not present its private URL as a public logs link. A captured local Compose run proves only local behavior; label it accordingly. Never include credentials or the Environment settings screen in a recording.

For local JSON log export:

```bash
docker compose logs --no-log-prefix --no-color wallet-service > /tmp/wallet-domain.jsonl
```

`transfer_initiated` is an attempt. Creation/debit/credit/completion events describe committed work. A process crash between commit and emission can still lose telemetry; use PostgreSQL transfer records for durable reconciliation.

## Reasoning to explain during review

Both directions acquire the same two wallet locks in lexical ID order. `READ_COMMITTED` alone is insufficient: the explicit write locks protect the balance check and update. Idempotency uniqueness is committed with both balance changes. A unique-key race loser rolls back before reading the winner in a fresh transaction.

A conditional debit is also viable, but the destination credit still locks a second row, so opposing transfers still need a consistent lock order or deliberate deadlock recovery. Serializable isolation adds serialization failures and retry handling that this two-row transfer does not require. JVM locks do not coordinate multiple app instances. Bounded database failures return 503 so callers can retry the same key without accepting unsafe writes.

The reversal feature is not part of the current R2 API. A later implementation needs an original-transfer link, at most one successful reversal per original enforced in the database, its own idempotency key, and an atomic debit of the original recipient. An insufficient recipient balance must decline without changing either wallet.
