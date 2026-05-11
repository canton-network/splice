# Tests

The performance tests cover three stores.
## Ingestion
| Store               | Summary                               | Content                                                                           |
|---------------------|---------------------------------------|-----------------------------------------------------------------------------------|
| **`SvDsoStore`**    | DSO's **internal governance data* | one ACS row per active contract                                                   |
| **`ScanStore`**     | DSO's **public, queryable data**  | one ACS row per active contract
| **`UpdateHistory`** | DSO's **append-only audit log**   | one row per update (reassignment/transaction) and one per event (create/exercise)

# How to run a test on branch
## CI
### Automated
Tests run daily on GHA workflows: [`.github/workflows/store-performance-tests.yml`](.github/workflows/store-performance-tests.yml)

### Manually
1) Go to GitHub -> Actions -> Store performance tests.
2) Click on "Run workflow" and select the branch you want to test.
3) Click on "Run workflow" to start the tests.

## locally
```bash
export POSTGRES_HOST=localhost
export POSTGRES_USER=<user>
export POSTGRES_PASSWORD=<pwd>
export POSTGRES_PORT=5432

# Run Postgres in a Docker container
docker run --name perf-pg -e POSTGRES_USER=$POSTGRES_USER -e POSTGRES_PASSWORD=$POSTGRES_PASSWORD -p $POSTGRES_PORT:$POSTGRES_PORT -d postgres:14
# Create the database
PGPASSWORD=$POSTGRES_PASSWORD psql -h localhost -U canton -c 'CREATE DATABASE splice_apps;'
# Download the test data (if not already done)
gcloud storage cp gs://mainnet-history-dumps/mainnet_big_update.json /tmp/mainnet_big_update.json
# Run the performance test
sbt 'apps-app / Test / runMain org.lfdecentralizedtrust.splice.performance.SplicePerf run -t UpdateHistoryRead -c ./apps/app/src/test/resources/performance/tests.conf -d /tmp/mainnet_big_update.json'
```
# Test data

Test data dumps live in the GCP bucket [`gs://mainnet-history-dumps`](https://console.cloud.google.com/storage/browser/mainnet-history-dumps).
Authenticate once with `gcloud auth login` (or `gcloud auth application-default login`).

```bash
# List all files
gcloud storage ls --long --recursive 'gs://mainnet-history-dumps/**'

# Download a file to local /tmp (used by the read perf test)
gcloud storage cp gs://mainnet-history-dumps/mainnet_big_update.json /tmp/mainnet_big_update.json

# Upload a new test data file
gcloud storage cp ./my_new_dump.json gs://mainnet-history-dumps/my_new_dump.json
```

# Performance regression detection
Per-test thresholds are defined in [`.github/store-perf-thresholds.json`](.github/store-perf-thresholds.json).
The GHA CI workflow creates GH issues when a test exceeds its threshold.

# Tuning performance
