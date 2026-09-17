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

## Tests

`npm test` runs the config parsing unit tests with the node test runner, `npm run check` type
checks everything.
