# rate-limit-tester

It checks that the rate limits declared in a cluster config are actually enforced using k6.

## Running

`src/main.ts` takes the domain under test and a config file in the shape of `scan.example.yaml`
(e.g. `cluster/deployment/<cluster>/config.resolved.yaml`). Only the _global_ limits
(`externalRateLimits.globalLimits` and `externalRateLimits.globalPerIpLimits`) are exercised for
now: per endpoint buckets (`externalRateLimits.rateLimits`) are ignored, and the load is driven
against a probe path that has no bucket of its own, `/api/scan/version` by default:

```bash
k6 run src/main.ts -e DOMAIN=scan.sv-2.example.com -e CONFIG=./scan.example.yaml
```

Use `-e PROBE_PATH=/some/other/path` to charge the global buckets through another endpoint.

Scenarios are scheduled sequentially, because the token buckets of a service are shared and
overlapping scenarios would be charged each other's requests.

## Checks and scenarios

Each check is sized from the global buckets: the binding rate is the higher of the global and the
global per-IP sustained rate, and bursts run at `BURST_FACTOR` times it (default 2x, capped by
`MAX_BURST_RPS`), long enough to drain the bucket.

| Check                | Scenario  | Traffic                                   | Expectation                        |
| -------------------- | --------- | ----------------------------------------- | ---------------------------------- |
| `per-ip-rate-limit`  | `below`   | half the per-IP rate, 20s                 | nothing is rejected                |
| `per-ip-rate-limit`  | `above`   | a burst                                   | 429s, from Envoy and not the app   |
| `client-ip-spoofing` | `spoofed` | a burst, each request forging a client IP | 429s, i.e. the headers are ignored |

A run fails if a burst is never rejected, if the 429s come from the splice app, if traffic below
the limit is rejected, or if too many responses are neither 200 nor 429. VUs stop sending once
the burst has produced `REJECTIONS_TO_PROVE` rejections (default 50).

## Tests

`npm test` runs the config parsing unit tests with the node test runner, `npm run check` type
checks everything.
