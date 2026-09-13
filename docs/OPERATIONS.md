# Operations

See [README.md](../README.md#api) for authentication, routes and response formats.

## Live verification

Use `./scripts/burst_test.sh --base-url https://wallet-xrdr.onrender.com --db postgres` with the target database's PG environment variables. Only four newly created test wallets are funded. Keep `summary.json`, `responses.jsonl`, `database.txt` and `metrics.txt` from the run as evidence. Record the deployed commit SHA alongside the results. A nonzero exit or `failure.json` is a failed probe, even if subsequent retries succeed; preserve failed evidence when investigating.

The [2026-09-13 live verification report](verification/2026-09-13-live-burst.md) records the passing run, SQL reconciliation, latency and public log checks.

Without database credentials, use `--prepare`, execute the generated `seed.sql` in Neon's SQL editor, then `--db manual --resume <directory>`. Independently execute the generated `verify.sql` afterwards; expected transfer counts are 201 total, 151 successful and 50 declined, and the four wallets total 4,000,000 paise. The result intentionally does not claim database verification until those SQL results are checked.

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

The burst runner captures this feed in `public-logs.json` and checks decline, replay and correlation fields. Generated captures remain gitignored.

To trace a request in Render logs, filter on its `correlation_id` from `responses.jsonl`. All burst correlation IDs begin with the run ID in `state.json`. Completion events include `transfer_created`, `wallet_debited`, `wallet_credited`, `transfer_declined_insufficient_funds` and `idempotent_replay_hit`.

For local JSON log export:

```bash
docker compose logs --no-log-prefix --no-color wallet-service > /tmp/wallet-domain.jsonl
```

`transfer_initiated` is an attempt. Creation/debit/credit/completion events describe committed work. A process crash between commit and emission can still lose telemetry; use PostgreSQL transfer records for durable reconciliation.

## Free-instance resource budget

The Docker JVM uses at most 35% of container memory for the Java heap and caps its code cache at 48 MB, leaving room for class metadata, thread stacks and native allocations. Compose limits the app to 512 MB and one CPU for repeatable local tests. This does not promise the same CPU throughput as shared free hosting. The health-check startup grace is five minutes; once healthy, checks run every 15 seconds.

The free deployment admits eight transfer requests at a time, using a fair semaphore with a 120-second acquisition timeout. The semaphore does not limit the number of waiting HTTP requests. The Hikari pool has six connections; transfer admission limits callers reaching that pool, while wallet provisioning and reads bypass admission. JSON stdout logging uses a 2,048-event asynchronous buffer with blocking behavior when full, so slow log drains can still delay request completion. These settings control resource use for one app instance; PostgreSQL constraints, idempotency and locks remain the correctness controls across instances.
