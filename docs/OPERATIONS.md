# Operating and demonstrating the wallet service

Business endpoints accept `Authorization: Bearer <user_id>`. Resource fields are under `data` in the common `success`, `data`, `error` envelope. Both original and `/api/v1` routes are supported. An initial successful transfer returns 201; a replay returns 200 with the identical body and `Idempotent-Replay: true`. A persisted insufficient-funds decline initially returns 422, and its replay returns 200 with that same declined body.

## Live verification

Use `./scripts/burst_test.sh --base-url https://wallet-xrdr.onrender.com --db postgres` with the target database's PG environment variables. Only four newly created test wallets are funded. Keep `summary.json`, `responses.jsonl`, `database.txt` and `metrics.txt` from the run as evidence. Record the deployed commit SHA alongside the results. A nonzero exit or `failure.json` is a failed probe, even if subsequent retries succeed; preserve failed evidence when investigating.

Without database credentials, use `--prepare`, execute the generated `seed.sql` in Neon's SQL editor, then `--db manual --resume <directory>`. Independently execute the generated `verify.sql` afterwards; expected transfer counts are 201 total, 151 successful and 50 declined, and the four wallets total 4,000,000 paise. The result intentionally does not claim database verification until those SQL results are checked.

For strict evaluator tooling, point it at either `/wallets` and `/transfers` or `/api/v1/wallets` and `/api/v1/transfers`, and extract `.data.id`, `.data.balance_paise`, and `.data.status`.

## Render environment naming

The code defaults to a 60,000 ms connection-pool wait. An override is normally unnecessary. If needed, use `SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT=60000`, not `SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT`. The latter is interpreted as a nested `connection.timeout` property and was reproduced starting Hikari before binding finished, producing a "pool is sealed" startup failure. Delete the malformed variable rather than keeping both spellings.

Other valid overrides are `SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE` and `SPRING_DATASOURCE_HIKARI_MINIMUMIDLE`. Spring Boot's environment conversion replaces dots with underscores and removes hyphens; see the [official binding rules](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.relaxed-binding.environment-variables).

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

## Public domain logs

`https://wallet-xrdr.onrender.com/logs` exposes the most recent 200 selected domain events as `data` in the shared response envelope. It is a public, non-cached snapshot for this demonstration, not a durable audit store. Only event time, name, correlation ID, transfer/wallet ID, integer amount and outcome are included. Request bodies, tokens, idempotency keys, request hashes, exception details and arbitrary log messages are omitted. The bounded buffer resets on restart and may omit older events during a burst. Full structured stdout logging remains available in Render.

To follow the feed in a terminal:

```bash
while true; do curl -fsS https://wallet-xrdr.onrender.com/logs; printf '\n'; sleep 2; done
```

The burst runner captures this feed and verifies correlated decline and replay events. Publish its URL as the public logs link after checking the deployed endpoint. Generated captures remain gitignored; a longer demonstration recording is optional.

## Log evidence

1. Open the deployed Render service's Logs view and start a screen recording that shows the service identity and timestamps.
2. Run the live burst command. Filter the logs by the `burst-...` run ID found in its `state.json`; every request correlation ID starts with that ID.
3. Show `transfer_created`, `wallet_debited`, `wallet_credited`, `transfer_declined_insufficient_funds`, and `idempotent_replay_hit`. Match a declined response's `correlation_id` in `responses.jsonl` to its server log event.
4. Show the final test result and database verification. Publish the recording at a reviewer-accessible URL and put that link in `SUBMISSION.md`.

The Render dashboard itself is account-restricted. Use the public `/logs` feed or a shared recording for reviewers; do not present a private dashboard URL as a public logs link. A captured local Compose run proves only local behavior; label it accordingly. Never include credentials or the Environment settings screen in a recording.

For local JSON log export:

```bash
docker compose logs --no-log-prefix --no-color wallet-service > /tmp/wallet-domain.jsonl
```

`transfer_initiated` is an attempt. Creation/debit/credit/completion events describe committed work. A process crash between commit and emission can still lose telemetry; use PostgreSQL transfer records for durable reconciliation.

## Reasoning to explain during review

Both directions acquire the same two wallet locks in lexical ID order. `READ_COMMITTED` alone is insufficient: the explicit write locks protect the balance check and update. Idempotency uniqueness is committed with both balance changes. A unique-key race loser rolls back before reading the winner in a fresh transaction.

A conditional debit is also viable, but the destination credit still locks a second row, so opposing transfers still need a consistent lock order or deliberate deadlock recovery. Serializable isolation adds serialization failures and retry handling that this two-row transfer does not require. JVM locks do not coordinate multiple app instances. Bounded database failures return 503 so callers can retry the same key without accepting unsafe writes.

The reversal feature is not part of the current R2 API. A later implementation needs an original-transfer link, at most one successful reversal per original enforced in the database, its own idempotency key, and an atomic debit of the original recipient. An insufficient recipient balance must decline without changing either wallet.

## Free-instance resource budget

The Docker JVM uses at most 35% of container memory for the Java heap and caps its code cache at 48 MB, leaving room for class metadata, thread stacks and native allocations. Compose limits the app to 512 MB and one CPU for repeatable local tests. This does not promise the same CPU throughput as shared free hosting. The health-check startup grace is five minutes; once healthy, checks run every 15 seconds.

A separate local test with a hard 0.1-CPU quota showed clean 503 responses under severe contention, without a process restart. A Render burst did restart the process, so the underlying platform restart reason must not be assumed solely from that local test. Limits protect accounting correctness; only live measurements establish whether a particular free-instance burst completes successfully.

The free deployment admits eight transfer requests at a time, using a fair in-process queue with a 120-second bound. The Hikari pool has six connections, so a spike cannot create hundreds of connection waiters. JSON stdout logging is asynchronous with a 2,048-event queue so log-drain speed does not occupy request workers. This is an availability control for one app instance; PostgreSQL constraints, idempotency and locks remain the correctness controls across instances.
