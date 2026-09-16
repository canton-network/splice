# rate-limit-tester

It checks that the Envoy rate limits declared in a cluster config are actually enforced using k6.

## Running

`src/main.ts` takes the domain under test and a config file in the shape of `scan.example.yaml`
(e.g. `cluster/deployment/<cluster>/config.resolved.yaml`). Every entry under
`externalRateLimits.rateLimits` flagged with `test: true` is exercised by all checks in
`src/checks/` that apply to it:

```bash
k6 run src/main.ts -e DOMAIN=scan.sv-2.example.com -e CONFIG=./scan.example.yaml
```

Scenarios are scheduled sequentially, because the token buckets of a service are shared and
overlapping scenarios would be charged each other's requests.

## Tests

`npm test` runs the config parsing unit tests with the node test runner, `npm run check` type
checks everything.
