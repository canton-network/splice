# Tests

See the [design document](https://docs.google.com/document/d/1rvAec6BuKx61TdJ6sY07QRAUA1p_WgedDIisgczFDPI) for details and upcoming changes.

## Ingestion

The performance tests cover three stores. The Tests ingest update data relevant to each store
and measure the time taken for the ingestion process.

| Store | Description | Content |
|---------------------|-------------|-------------------|
| **`SvDsoStore`**    | DSO's internal governance data | ACS contracts |
| **`ScanStore`**     | DSO's public queryable data | ACS contracts |
| **`UpdateHistory`** | DSO's append-only audit log | Updates and associated events (creates/exercises) |

# How to run a test on branch

## CI

Tests are automated while you can also trigger them manually.

### automated

Tests run daily on [GHA workflow](.github/workflows/performance_tests.yml)

### manually

1) Go to [GHA Performance Tests](https://github.com/canton-network/splice/actions/workflows/performance_tests.yml)
2) Click on "Run workflow" and select the branch you want to test.
3) Click on "Run workflow" to start the tests.

## locally

You can also run the tests locally, where you need to configure a Postgres instance and download the test data
beforehand.

```bash
# Start Postgres in a Docker container
./scripts/postgres.sh docker start
# Create the `splice_apps` database
./scripts/postgres.sh docker createdb splice_apps
# Download the test data (if not already done)
gcloud storage cp gs://mainnet-history-dumps/mainnetupdates.json /tmp/mainnetupdates.json
# Run the performance test from the root of the repo
sbt 'apps-app / Test / runMain org.lfdecentralizedtrust.splice.performance.SplicePerf run -t DbSvDsoStore -c ./apps/app/src/test/resources/performance/tests.conf -d /tmp/mainnetupdates.json'
```

# Test data

We download test data from the main-net and upload it to the GCP bucket. This operation is currently manual, but we
automate it in the future.
Test data dumps live in the GCP bucket [
`gs://mainnet-history-dumps`](https://console.cloud.google.com/storage/browser/mainnet-history-dumps).

```bash
# List all files
gcloud storage ls --long --recursive 'gs://mainnet-history-dumps/**'

# Download a file to local /tmp
gcloud storage cp gs://mainnet-history-dumps/mainnetupdates.json /tmp/mainnetupdates.json

# Upload a new test data file
gcloud storage cp ./new_dump.json gs://mainnet-history-dumps/new_dump.json
```

# Performance regression detection

Per-test thresholds are defined in [`.github/store-perf-thresholds.json`](.github/store-perf-thresholds.json).
The GHA workflow creates GH issues when a test exceeds its threshold.
The engineers on the CI monitoring rotation are responsible for triaging these issues and assigning them to the relevant
teams.
Historical performance data is available in
Grafana: https://grafana.splice.network.canton.global/d/splice-perf-ingestion/store-ingestion-performance

# Tuning performance

When a regression is reported (or you're proactively trying to make a component
faster), follow this loop.

### 1. Start from a CI run with metrics

- Pick a recent run of the affected workflow
- Look at the per-test panels in Grafana
- Note which metric actually moved and by how much

### 2. Build a hypothesis

From the metrics, form a statement about why the code-path is slow, e.g:

- Per-item time is up because we now do an extra `SELECT` per row
- CPU/wall ratio is ~0.2, so we're I/O-bound on Postgres, not on the app-side
- Peak heap doubled, so a new code path added more processing in memory.

### 3. Validate the hypothesis

Before changing any code, confirm the hypothesis is consistent with what the
test metrics show. Run at least the tests two times and also cross-check at least two signals
(e.g. ingestion total time and CPU/wall ratio).

### 4. Devise experiments

Translate the hypothesis into the smallest possible change that would prove or
disprove it. Better change one variable per experiment so you can attribute any delta to a single
cause. E.g. changes:

- Batch the query
- Add an index, or remove a redundant one
- Increase / decrease DB `max-connections` or batch size
- Enable / disable extra processing added in app code

### 5. Run the experiment locally

1. On `main` (or the branch before your change), run the relevant
   perf test locally — see [Running locally](#manually) — and record the metrics
2. Apply the change on a branch.
3. Re-run the same test, with the same data file and the same Postgres
   container, Compare the new metrics JSON against the baseline.

A local run give you enough signals if the metric you're chasing moves clearly,
but always verify the changes on CI, which is the baseline infrastructure we use.

### 6. Validate on CI

1. Push the branch
2. Trigger test manually - see [Running manually](#manually)
3. Wait for the run to finish, then compare its metrics data (from Grafana or JSON) against recent `main` runs
4. Merge the change once the CI-run metrics confirm the improvement and show no regression elsewhere

### 7. Finalize

- If the new numbers are meaningfully better, update the relevant threshold in
  [`Thresholds`](.github/store-perf-thresholds.json) so future regressions
  are caught against the new thresholds.
- Mention the hypothesis, the experiment, and the before/after numbers in the
  PR description so the next person tuning this code has some context.
