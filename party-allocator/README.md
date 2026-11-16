# party allocator

Allocate up to maxParties on a single node. Intended for scale testing.

## Usage

```
export 'EXTERNAL_CONFIG={"token": "<token>", "jsonLedgerApiUrl": "http://localhost:6501", "scanApiUrl": "http://localhost:5012", "keyDirectory": "keys", "maxParties": 100, "validatorApiUrl": "http://localhost:5503"}'
 ```

 For local testing, you can use it in combination with `./scripts/start-backends-for-local-frontend-testing.sh`.

 Make sure to first tap as the validator operator and potentially adjust your traffic configuration.

## Party Allocation Steps

Each party that is allocated is setup through the following steps:

1. Create party topology through generate/allocate.
2. Tap some coin through prepare/execute.
3. Create a `TransferPreapprovalProposal` through prepare/execute.
4. Wait for the validator automation to accept the proposal and the preapproval to be created.

A failed setup is retried up to 3 times. Setups that have not finished are marked by an `<index>_pending`
file in the key directory and are resumed after a restart. A retried setup reuses the key of the party
and skips the steps that have already completed.

The number of parties set up in parallel adapts to errors up to the configured `parallelism`.

## Tests

```
npm test
```

## Cluster Deployment

To enable the party allocator to run against the validator runbook enable it through the following setting in `config.yaml`.

```
validators:
  validator-runbook:
    partyAllocator:
      enable: true
```
