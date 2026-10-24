# pg-partition-bq-test

This is a throwaway CloudSQL → Datastream → BigQuery pipeline whose source is a
range-partitioned Postgres table. It exists to validate the
[#6312](https://github.com/canton-network/splice/issues/6312) cutover of the scan
`update_history_*` tables to partitioned parents before the migration ships, in
particular the `publish_via_partition_root = true` publication setting from
[#6730](https://github.com/canton-network/splice/issues/6730).

The stack mirrors both scan streams in `common-sv/src/bigQuery.ts`, each with
its own publication, replication slot and BigQuery dataset:

- the **stag-prod** stream: `appendOnly`, `backfillNone`, and HOUR
  ingestion-time partitioning. Most checks below are on its dataset.
- the **legacy** stream: merge mode and `backfillAll`. It is here to check what
  reaches BigQuery from the renamed `*_legacy` table, also with backfill
  (sections 2d to 2f). Both streams list their tables by name in `includeObjects`.

The publications and replication slots are created by the real
`cluster/pulumi/canton-network/bigquery-cloudsql.sh`.

The stack is applied manually only. It is not part of any deploy flow or of `make update-expected`.

## What gets deployed

| Resource | Name |
|---|---|
| CloudSQL instance (private IP, `cloudsql.logical_decoding=on`) | `<cluster>-pgpart-db-<suffix>` |
| Database and schema | `partition_test` |
| Users | `cnadmin` (owns the tables), `bqdatastream` (Datastream) |
| Tables | `sim_scan_verdict_store` (partitioned from the start), `sim_update_history_c_x` (plain, swapped in section 2) |
| Publications and slots | `pgpart_stag_prod_pub` and `pgpart_legacy_pub` (`publish_via_partition_root = true`), `pgpart_stag_prod_r_slot` and `pgpart_legacy_r_slot` |
| NAT VM, Datastream private connection, firewall | `<cluster>-pgpart-*`; peering /29 is `GCP_MASTER_IPV4_CIDR` + 24 |
| BigQuery datasets | `pgpart_<cluster>` (stag-prod), `pgpart_<cluster>_legacy` |
| Datastream streams | `<cluster>-pgpart-stag-prod`, `<cluster>-pgpart-legacy` |

Datastream names the BigQuery tables `<schema>_<table>`, so the two tables
expected in each dataset are `partition_test_sim_scan_verdict_store` and
`partition_test_sim_update_history_c_x`.

The stack config `waitForMigration` sets the migration that
`bigquery-cloudsql.sh` waits for, for both publications. It is unset until
section 2e.

## Deploy

You need the same GCP permissions as for deploying scan's BigQuery pipeline:
Cloud SQL Editor, permission to create GCS buckets, and Datastream and BigQuery
admin. The Datastream API must be enabled in the project; check with
`gcloud services list --enabled | grep datastream`.

```bash
cd cluster/deployment/scratchnetd
cncluster pulumi pg-partition-bq-test up
cncluster pulumi pg-partition-bq-test stack output --show-secrets
```

The first `up` creates
`cluster/stacks/scratchnets/pg-partition-bq-test/Pulumi.pg-partition-bq-test.scratchd.yaml`.
Commit it alongside this project.

Set these variables for the commands below, filled from the stack outputs:

```bash
PRIVATE_IP=...          # privateIp
PGPASSWORD=...          # cnadminPassword
BQ_DATASET=...          # bigQueryDataset, e.g. da-cn-scratchnet.pgpart_scratchd
BQ_DATASET_LEGACY=...   # bigQueryDatasetLegacy
STREAM=...              # datastreamStreamId
STREAM_LEGACY=...       # datastreamStreamIdLegacy
```

### psql

The instance has only a private IP. The scratchnet's GKE cluster is in the same
VPC, so run a client pod there. Every node pool carries a `NoSchedule` taint
(`cn_apps`, or `cloud.google.com/compute-class` on clusters with compute
classes; see `cluster/pulumi/cluster/src/nodePools.ts`), so the pod needs a
toleration, or it stays `Pending` until kubectl gives up with `timed out
waiting for the condition`:

```bash
kubectl run pgpart-psql --rm -it --restart=Never --image=postgres:18 \
  --labels=testing=pgpart-pr7737 \
  --pod-running-timeout=5m \
  --overrides='{"apiVersion": "v1", "spec": {"tolerations": [
    {"key": "cn_apps", "operator": "Exists", "effect": "NoSchedule"},
    {"key": "cloud.google.com/compute-class", "operator": "Exists", "effect": "NoSchedule"}]}}' \
  --env=PGPASSWORD="$PGPASSWORD" \
  -- psql -h "$PRIVATE_IP" -U cnadmin -d partition_test
```

If it still doesn't start, `kubectl describe pod pgpart-psql` from a second
terminal shows why.

As a fallback, `gcloud compute ssh <natVmName> --zone <natVmZone>`, then
`sudo apt-get install -y postgresql-client` and run the same `psql` command.
The VM uses OS Login, which has two common failure modes:

- `Permission denied (publickey)`: your account lacks an OS Login role.
  `apt-get` needs sudo, so that's `roles/compute.osAdminLogin` on the project
  or the VM. `gcloud compute ssh <natVmName> --zone <natVmZone> --troubleshoot`
  checks this, among other things.
- A prompt for the passphrase of `~/.ssh/google_compute_engine` means that key
  is passphrase-protected; with a passphrase-free key ssh never asks.
  `ssh-keygen -y -f ~/.ssh/google_compute_engine` asks too in that case. Enter
  its passphrase, or move the key pair aside and let `gcloud compute ssh`
  generate a new one (just press enter at `Enter passphrase (empty for no
  passphrase)`).

Start every psql session with:

```sql
SET search_path TO partition_test;
SET TIME ZONE 'UTC';
```

### BigQuery

```bash
bq query --use_legacy_sql=false --label=testing:pgpart-pr7737 "<query>"
```

The BigQuery samples below are written for that double-quoted argument: their
backticks are escaped, and the shell expands `$BQ_DATASET`.

The **table list** below shows the tables in both datasets. Several sections
check it; it must never show a table named after a partition, such as
`…_sim_scan_verdict_store_p20261012`, or after the renamed
`…_sim_update_history_c_x_legacy`:

```sql
SELECT table_schema, table_name FROM \`$BQ_DATASET\`.INFORMATION_SCHEMA.TABLES
UNION ALL
SELECT table_schema, table_name FROM \`$BQ_DATASET_LEGACY\`.INFORMATION_SCHEMA.TABLES
ORDER BY 1, 2;
```

Datastream writes with a delay of a few minutes, even with `dataFreshness: 0s`.
Re-run BigQuery checks until the numbers stop changing before you conclude anything.
To check the stream status and per-object errors:

```bash
for s in "$STREAM" "$STREAM_LEGACY"; do
  gcloud datastream streams describe "$s" --location=us-central1 --format='value(state,errors)'
  gcloud datastream objects list --stream="$s" --location=us-central1
done
```

Each stream should list exactly two objects, `partition_test.sim_scan_verdict_store`
and `partition_test.sim_update_history_c_x`, throughout the test.


## 0. Publication sanity

```sql
SELECT pubname, pubviaroot FROM pg_publication ORDER BY 1;
-- pgpart_legacy_pub, t and pgpart_stag_prod_pub, t

SELECT * FROM pg_publication_tables ORDER BY 1, 3;
-- sim_scan_verdict_store and sim_update_history_c_x only, for each publication,
-- with no partitions such as sim_scan_verdict_store_import
```

## 1. Partitions and CDC

Create daily partitions for yesterday, today and tomorrow, in the same shape the
#6312 migration uses (`record_time` in microseconds since the epoch):

```sql
DO $$
DECLARE d date;
BEGIN
  FOR d IN SELECT generate_series(current_date - 1, current_date + 1, interval '1 day')::date LOOP
    EXECUTE format(
      'CREATE TABLE IF NOT EXISTS %I PARTITION OF sim_scan_verdict_store FOR VALUES FROM (%s) TO (%s)',
      'sim_scan_verdict_store_p' || to_char(d, 'YYYYMMDD'),
      (extract(epoch FROM d::timestamp AT TIME ZONE 'UTC') * 1000000)::bigint,
      (extract(epoch FROM (d + 1)::timestamp AT TIME ZONE 'UTC') * 1000000)::bigint);
  END LOOP;
END $$;
-- DO
```

Confirm the partitions exist:

```sql
\d+ sim_scan_verdict_store
-- 'Partitions:' should show 4: the _import created in index.ts,
-- and 3 dated ones with different ranges

SELECT inhrelid::regclass AS partition, pg_get_expr(c.relpartbound, c.oid) AS bound
  FROM pg_inherits JOIN pg_class c ON c.oid = inhrelid
  WHERE inhparent = 'sim_scan_verdict_store'::regclass ORDER BY 1;
-- lower-level version of the above
```

Insert rows into every partition, including the import partition, and count them per partition:

```sql
INSERT INTO sim_scan_verdict_store (record_time, payload)
  SELECT (extract(epoch FROM t) * 1000000)::bigint, 'phase1 ' || t
  FROM generate_series(current_date - 1 + interval '1 hour',
                       current_date + 1 + interval '23 hours', interval '6 hours') AS t;
-- INSERT 0 12
INSERT INTO sim_scan_verdict_store (record_time, payload) VALUES (-62135596800000000, 'phase1 import');
-- INSERT 0 1

SELECT tableoid::regclass, count(*) FROM sim_scan_verdict_store GROUP BY 1 ORDER BY 1;
-- 1 in import, 4 in each other partition

SELECT count(*), min(row_id), max(row_id) FROM sim_scan_verdict_store;
-- 13, 1, 13
```

Check BigQuery (assuming shell command from above, double-quoted argument):

```sql
-- the table list
-- expect partition_test_sim_scan_verdict_store in each dataset; the legacy
-- dataset may also have an empty partition_test_sim_update_history_c_x
-- already, from the initial backfill

SELECT count(*), min(row_id), max(row_id)
  FROM \`$BQ_DATASET.partition_test_sim_scan_verdict_store\`;
-- expect 13, 1, 13 (matching above pg version)

SELECT count(*), min(row_id), max(row_id)
  FROM \`$BQ_DATASET_LEGACY.partition_test_sim_scan_verdict_store\`;
-- expect 13, 1, 13 too

SELECT row_id, count(*) FROM \`$BQ_DATASET.partition_test_sim_scan_verdict_store\`
  GROUP BY row_id HAVING count(*) > 1;
-- expect no rows, empty output (meaning no duplicates)

SELECT datastream_metadata.change_type, count(*)
  FROM \`$BQ_DATASET.partition_test_sim_scan_verdict_store\` GROUP BY 1;
-- expect INSERT, 13 (no other change types)
```

- **Expected:** the dataset has exactly one table per parent and nothing named
  after a partition, such as `…_sim_scan_verdict_store_p20261012` or `…_import`.
- **Failure signal:** the stream lists only the parents in `includeObjects`, so a
  publication without `pubviaroot` would most likely **silently drop** the rows
  rather than create per-partition tables. That is why this step compares counts
  and not only table names.

Then add a partition for the day after tomorrow, after the stream is already
running, and insert a row into it:

```sql
DO $$
DECLARE d date := current_date + 2;
BEGIN
  EXECUTE format(
    'CREATE TABLE %I PARTITION OF sim_scan_verdict_store FOR VALUES FROM (%s) TO (%s)',
    'sim_scan_verdict_store_p' || to_char(d, 'YYYYMMDD'),
    (extract(epoch FROM d::timestamp AT TIME ZONE 'UTC') * 1000000)::bigint,
    (extract(epoch FROM (d + 1)::timestamp AT TIME ZONE 'UTC') * 1000000)::bigint);
END $$;
-- DO

INSERT INTO sim_scan_verdict_store (record_time, payload)
  VALUES ((extract(epoch FROM current_date + 2 + interval '12 hours') * 1000000)::bigint,
          'phase1 new partition');
-- INSERT 0 1

SELECT tableoid::regclass, row_id, payload FROM sim_scan_verdict_store
  WHERE payload = 'phase1 new partition';
-- sim_scan_verdict_store_p<day after tomorrow>, 14, phase1 new partition
```

Check that the row arrives in the same BigQuery table:

```sql
-- the table list
-- expect no change

SELECT row_id, payload FROM \`$BQ_DATASET.partition_test_sim_scan_verdict_store\`
  WHERE payload = 'phase1 new partition';
-- expect 14, phase1 new partition

SELECT count(*), min(row_id), max(row_id)
  FROM \`$BQ_DATASET.partition_test_sim_scan_verdict_store\`;
-- expect 14, 1, 14
```

## 2. Cutover without a publication step

The #6312 migration in [PR 7770](https://github.com/canton-network/splice/pull/7770),
`V078__partition_update_history.sql`, renames the `update_history_*` tables to
`*_legacy` and creates partitioned parents under the old names. It does not touch
publications. Publications track tables by OID, so after V078 they publish the
`*_legacy` tables, which nothing writes to any more, and not the new parents.

The only thing that fixes the publications is `bigquery-cloudsql.sh`
re-running its `ALTER PUBLICATION … SET TABLE <names>`, which resolves the names
to the new parents. The Pulumi operator re-runs it only when the script's
arguments change, for example when the BigQuery config bumps the migration it
waits for to V078. This section follows the order where the release with V078
lands first and that config change lands in a later commit, so updates are
silently not replicated in between.

Here `sim_update_history_c_x` stands for `update_history_creates` and
`update_history_exercises`, the replicated tables V078 renames, and
`sim_scan_verdict_store` for the replicated tables it leaves alone.

Seed the plain table and confirm the rows reach BigQuery:

```sql
INSERT INTO sim_update_history_c_x (record_time, payload)
  SELECT (extract(epoch FROM t) * 1000000)::bigint, 'pre-cutover ' || t
  FROM generate_series(now() - interval '2 hours', now(), interval '30 minutes') AS t;
-- INSERT 0 5

SELECT count(*), max(row_id) FROM sim_update_history_c_x;
-- 5, 5
```

```sql
SELECT count(*), max(row_id) FROM \`$BQ_DATASET.partition_test_sim_update_history_c_x\`;
-- expect 5, 5 (matching above pg version)

SELECT count(*), max(row_id) FROM \`$BQ_DATASET_LEGACY.partition_test_sim_update_history_c_x\`;
-- expect 5, 5 too
```

### 2a. Rename alone: what the publication does by default

```sql
BEGIN;
ALTER TABLE sim_update_history_c_x RENAME TO sim_update_history_c_x_legacy;
SELECT * FROM pg_publication_tables ORDER BY 1, 3;
-- sim_scan_verdict_store and sim_update_history_c_x_legacy, for each publication
ROLLBACK;
```

### 2b. The release: a V078-shaped migration

The same steps V078 takes for each table, in one transaction, with no
publication step. `payload_interned` stands for V078's new `*_interned`
columns. The last insert is what Flyway records along with the migration, and
what `bigquery-cloudsql.sh` waits for.

```sql
BEGIN;

ALTER TABLE sim_update_history_c_x RENAME TO sim_update_history_c_x_legacy;
ALTER SEQUENCE sim_update_history_c_x_row_id_seq RENAME TO sim_update_history_c_x_legacy_row_id_seq;
ALTER TABLE sim_update_history_c_x_legacy
  RENAME CONSTRAINT sim_update_history_c_x_pkey TO sim_update_history_c_x_legacy_pkey;

CREATE TABLE sim_update_history_c_x (
  row_id bigint GENERATED BY DEFAULT AS IDENTITY,
  ingested_at timestamptz NOT NULL DEFAULT now(),
  record_time bigint NOT NULL,
  payload text,
  payload_interned bigint NOT NULL,
  PRIMARY KEY (row_id, record_time)
) PARTITION BY RANGE (record_time);
SELECT setval(pg_get_serial_sequence('sim_update_history_c_x', 'row_id'), last_value, is_called)
  FROM sim_update_history_c_x_legacy_row_id_seq;
-- 5

INSERT INTO flyway_schema_history VALUES ('V078__partition_update_history.sql', true);

COMMIT;
-- COMMIT
```

### 2c. Scan startup: partitions

V078 creates no partitions; scan creates them at startup, before it ingests
anything (`UpdateHistory.createPartitions`):

```sql
DO $$
DECLARE d date;
BEGIN
  FOR d IN SELECT generate_series(current_date - 1, current_date + 1, interval '1 day')::date LOOP
    EXECUTE format(
      'CREATE TABLE IF NOT EXISTS %I PARTITION OF sim_update_history_c_x FOR VALUES FROM (%s) TO (%s)',
      'sim_update_history_c_x_p' || to_char(d, 'YYYYMMDD'),
      (extract(epoch FROM d::timestamp AT TIME ZONE 'UTC') * 1000000)::bigint,
      (extract(epoch FROM (d + 1)::timestamp AT TIME ZONE 'UTC') * 1000000)::bigint);
  END LOOP;
END $$;
-- DO
```

### 2d. The silent gap

Ingest into both tables, as scan would after the release:

```sql
INSERT INTO sim_update_history_c_x (record_time, payload, payload_interned)
  SELECT (extract(epoch FROM t) * 1000000)::bigint, 'gap ' || t, extract(hour FROM t)
  FROM generate_series(current_date - 1 + interval '3 hours',
                       current_date + 1 + interval '21 hours', interval '6 hours') AS t;
-- INSERT 0 12
INSERT INTO sim_scan_verdict_store (record_time, payload)
  SELECT (extract(epoch FROM t) * 1000000)::bigint, 'control ' || t
  FROM generate_series(current_date + interval '10 hours',
                       current_date + interval '11 hours', interval '30 minutes') AS t;
-- INSERT 0 3

SELECT min(row_id), max(row_id) FROM sim_update_history_c_x;
-- 6, 17 (the new parent continues the legacy row_id sequence)

SELECT * FROM pg_publication_tables ORDER BY 1, 3;
-- sim_scan_verdict_store and sim_update_history_c_x_legacy, for each publication;
-- the new parent is not published

SELECT slot_name, active, pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn) AS lag_bytes
  FROM pg_replication_slots ORDER BY 1;
-- pgpart_legacy_r_slot and pgpart_stag_prod_r_slot, both t, and a lag that stays
-- small when re-run after the BigQuery checks: sim_scan_verdict_store keeps the
-- slots advancing
```

Nothing writes to the `*_legacy` tables after V078: V078 only renames them, and
scan only reads them. But the publications still publish them, so write to the
legacy table here to see where its changes go. A distinct `row_id` and payload
make any change that lands in BigQuery stand out:

```sql
INSERT INTO sim_update_history_c_x_legacy (row_id, record_time, payload)
  VALUES (1000, (extract(epoch FROM now()) * 1000000)::bigint, 'legacy-insert');
-- INSERT 0 1
UPDATE sim_update_history_c_x_legacy SET payload = 'legacy-update' WHERE row_id = 1;
-- UPDATE 1
```

Wait 20–30 minutes, then check BigQuery:

```sql
-- the table list
-- expect partition_test_sim_scan_verdict_store and partition_test_sim_update_history_c_x
-- in each dataset, and no …_sim_update_history_c_x_legacy

SELECT count(*), max(row_id) FROM \`$BQ_DATASET.partition_test_sim_scan_verdict_store\`;
-- expect 17, 17: the control rows arrive, so the stream is alive

SELECT count(*), max(row_id) FROM \`$BQ_DATASET.partition_test_sim_update_history_c_x\`;
-- expect 5, 5, unchanged: none of the gap rows arrive

SELECT count(*), max(row_id) FROM \`$BQ_DATASET_LEGACY.partition_test_sim_scan_verdict_store\`;
-- expect 17, 17

SELECT count(*), max(row_id) FROM \`$BQ_DATASET_LEGACY.partition_test_sim_update_history_c_x\`;
-- expect 5, 5, unchanged

SELECT 'stag-prod' AS ds, row_id, payload
  FROM \`$BQ_DATASET.partition_test_sim_update_history_c_x\` WHERE payload LIKE 'legacy-%'
UNION ALL
SELECT 'legacy', row_id, payload
  FROM \`$BQ_DATASET_LEGACY.partition_test_sim_update_history_c_x\` WHERE payload LIKE 'legacy-%';
-- seen in a real run: 1000, legacy-insert and 1, legacy-update, in each dataset

SELECT row_id, payload, datastream_metadata.change_type
  FROM \`$BQ_DATASET.partition_test_sim_update_history_c_x\` WHERE row_id IN (1, 1000)
  ORDER BY row_id, datastream_metadata.source_timestamp;
-- seen in a real run: 1, pre-cutover …, INSERT; 1, legacy-update, UPDATE;
-- 1000, legacy-insert, INSERT
```

- **Seen in a real run:** no table named after the legacy table appears, but
  both streams apply the legacy table's changes to the BigQuery table of its
  **old name**. Datastream apparently keeps the relation it resolved at stream
  start rather than following the name. In the merge-mode legacy dataset, the
  update overwrote the pre-cutover row 1. So between the release and the
  config change, any DML on a `*_legacy` table reaches BigQuery; a `DELETE`
  would remove rows from the legacy dataset. After 2e the legacy table is out
  of the publications, and its changes no longer reach the slots.
- The probe rows stay in BigQuery for the rest of the test; the expectations
  below include them.

- **Expected:** both streams stay `RUNNING`, and nothing flags the gap: the
  console's stream details Objects tab lists `partition_test.sim_update_history_c_x`
  as normal (seen in a real run, for the stag-prod stream). Only the row counts
  above reveal it.
- **Record:** whether either stream reports an error for the writes to the
  legacy table, in `streams describe` or `objects list`.

### 2e. The config change: the operator re-runs `bigquery-cloudsql.sh`

Bump the migration the script waits for, then run a plain `up`, as the operator
would:

```bash
cncluster pulumi pg-partition-bq-test config set waitForMigration V078__partition_update_history.sql
cncluster pulumi pg-partition-bq-test preview --diff
cncluster pulumi pg-partition-bq-test up
```

- **Expected preview:** a `~ update` of each of `<cluster>-pgpart-pub-replicate-slot`
  and `<cluster>-pgpart-pub-replicate-slot-legacy`, whose `create` and `delete`
  change only in `--flyway-migration-to-wait-for`. Nothing is replaced. A
  replace would run `delete-pub-rep-slot` and drop the slot.
- **Expected `up`:** the script finds V078 at once and runs `SET TABLE`, for
  each publication.

```sql
SELECT * FROM pg_publication_tables ORDER BY 1, 3;
-- sim_scan_verdict_store and sim_update_history_c_x, for each publication;
-- the _legacy table is gone

SELECT pubname, pubviaroot FROM pg_publication ORDER BY 1;
-- pgpart_legacy_pub, t and pgpart_stag_prod_pub, t (SET TABLE leaves them alone)
```

Ingest again:

```sql
INSERT INTO sim_update_history_c_x (record_time, payload, payload_interned)
  SELECT (extract(epoch FROM t) * 1000000)::bigint, 'post-fix ' || t, extract(hour FROM t)
  FROM generate_series(current_date + interval '1 hour',
                       current_date + interval '19 hours', interval '6 hours') AS t;
-- INSERT 0 4

SELECT split_part(payload, ' ', 1) AS phase, count(*), min(row_id), max(row_id)
  FROM sim_update_history_c_x GROUP BY 1 ORDER BY 1;
-- gap, 12, 6, 17
-- post-fix, 4, 18, 21
```

Check BigQuery:

```sql
-- the table list
-- expect no change: no …_sim_update_history_c_x_legacy and no per-partition table

SELECT SPLIT(payload, ' ')[OFFSET(0)] AS phase, count(*), min(row_id), max(row_id)
  FROM \`$BQ_DATASET.partition_test_sim_update_history_c_x\` GROUP BY 1 ORDER BY 1;
-- expect legacy-insert, 1, 1000, 1000 and legacy-update, 1, 1, 1 (the 2d probe),
-- post-fix, 4, 18, 21 and pre-cutover, 5, 1, 5; no gap rows

SELECT column_name, data_type FROM \`$BQ_DATASET\`.INFORMATION_SCHEMA.COLUMNS
  WHERE table_name = 'partition_test_sim_update_history_c_x' ORDER BY ordinal_position;
-- expect payload_interned, INT64, added by Datastream for the new parent

SELECT SPLIT(payload, ' ')[OFFSET(0)] AS phase, count(*), min(row_id), max(row_id)
  FROM \`$BQ_DATASET_LEGACY.partition_test_sim_update_history_c_x\` GROUP BY 1 ORDER BY 1;
-- expect legacy-insert, 1, 1000, 1000 and legacy-update, 1, 1, 1 (the 2d probe),
-- post-fix, 4, 18, 21 and pre-cutover, 4, 2, 5 (row 1 was overwritten in 2d),
-- and, if the legacy stream backfilled the new parent by itself, gap, 12, 6, 17

SELECT column_name FROM \`$BQ_DATASET_LEGACY\`.INFORMATION_SCHEMA.KEY_COLUMN_USAGE
  WHERE table_name = 'partition_test_sim_update_history_c_x' ORDER BY ordinal_position;
-- the merge key of the legacy-shaped table
```

- **Expected:** both streams stay `RUNNING`.
- **Seen in a real run:** the BigQuery table is still clustered on `row_id`
  only, although the new parent's primary key is `(row_id, record_time)`.
- **Record:**
  - whether the legacy stream backfills `partition_test.sim_update_history_c_x`
    by itself after `SET TABLE`: `objects list --stream="$STREAM_LEGACY"` shows
    the object's backfill job, and the gap rows appear in the query above;
  - the merge key above, and whether the legacy stream reports an error for the
    object now that the source's primary key is `(row_id, record_time)`.

### 2f. Fill the gap

**Legacy-shaped dataset: Datastream backfill.** Backfill the object on the
legacy stream. This reads the new parent by its name, and is the second check
that the `*_legacy` table never reaches BigQuery. Skip `start-backfill` if 2e
recorded that the stream already backfilled the object by itself.

```bash
OBJECT=$(gcloud datastream objects lookup --stream="$STREAM_LEGACY" --location=us-central1 \
  --postgresql-schema=partition_test --postgresql-table=sim_update_history_c_x \
  --format='value(name.basename())')
gcloud datastream objects start-backfill "$OBJECT" --stream="$STREAM_LEGACY" --location=us-central1
gcloud datastream objects describe "$OBJECT" --stream="$STREAM_LEGACY" --location=us-central1 \
  --format='value(backfillJob.state)'
# re-run until COMPLETED

gcloud datastream objects lookup --stream="$STREAM_LEGACY" --location=us-central1 \
  --postgresql-schema=partition_test --postgresql-table=sim_update_history_c_x_legacy
# expect NOT_FOUND: the stream has no object for the legacy table, so nothing
# can backfill it
```

Wait a few minutes, then:

```sql
-- the table list
-- expect no change: no …_sim_update_history_c_x_legacy

SELECT SPLIT(payload, ' ')[OFFSET(0)] AS phase, count(*), min(row_id), max(row_id)
  FROM \`$BQ_DATASET_LEGACY.partition_test_sim_update_history_c_x\` GROUP BY 1 ORDER BY 1;
-- expect gap, 12, 6, 17, legacy-insert, 1, 1000, 1000, legacy-update, 1, 1, 1,
-- post-fix, 4, 18, 21 and pre-cutover, 4, 2, 5: the gap is filled, and the
-- rows only the legacy table has are untouched

-- re-run the legacy-insert/legacy-update query from 2d
-- expect the same 4 rows as in 2d: the backfill reads only the new parent
```

- **Record:** whether the backfill duplicates or changes any row in the
  legacy-shaped table, and whether this would do for the legacy dataset in
  runbook step 5 instead of the #7622 procedure.

**Stag-prod dataset: the #7622 procedure.** This follows the BigQuery backfill procedure in
[canton-network-internal#7622](https://github.com/DACH-NY/canton-network-internal/pull/7622)
(`BIGQUERY.md`). Find the gap:

```sql
WITH base_ids AS (
  SELECT row_id, LEAD(row_id) OVER (ORDER BY row_id) AS next_row_id
  FROM \`$BQ_DATASET.partition_test_sim_update_history_c_x\`
  WHERE ingested_at > TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 1 DAY)
    AND payload NOT LIKE 'legacy-%'  -- the 2d probe; not part of #7622
)
SELECT row_id + 1 AS gap_start_id, next_row_id - 1 AS gap_end_id,
       next_row_id - row_id - 1 AS missing_count
  FROM base_ids WHERE next_row_id > row_id + 1 ORDER BY 1;
-- expect 6, 17, 12
```

Extract the missing rows through a relay pod. Unlike the psql pod above, this
needs `psql` on your machine. In one terminal:

```bash
kubectl apply -f - <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: pgpart-db-relay
  labels:
    testing: pgpart-pr7737
spec:
  restartPolicy: Never
  tolerations:
    - {key: cn_apps, operator: Exists, effect: NoSchedule}
    - {key: cloud.google.com/compute-class, operator: Exists, effect: NoSchedule}
  containers:
    - name: relay
      image: alpine/socat
      args: ["tcp-listen:5432,fork,reuseaddr", "tcp-connect:$PRIVATE_IP:5432"]
      ports:
        - containerPort: 5432
EOF
kubectl wait --for=condition=Ready pod/pgpart-db-relay --timeout=5m
kubectl port-forward pod/pgpart-db-relay 5433:5432
```

In another:

```bash
FROM=6; TO=18
PGPASSWORD="$PGPASSWORD" psql "host=127.0.0.1 port=5433 user=cnadmin dbname=partition_test" -qtA \
  -c "SELECT row_to_json(t) FROM (SELECT * FROM partition_test.sim_update_history_c_x WHERE row_id >= $FROM AND row_id < $TO) t;" \
  > cx-$FROM-$TO.json
wc -l < cx-$FROM-$TO.json
# 12
```

Load them into a temporary table with the target's schema, then copy the rows
that are not in the target yet:

```bash
BQ_TARGET="${BQ_DATASET/./:}.partition_test_sim_update_history_c_x"
BQ_TEMP="${BQ_DATASET/./:}.cx_backfill_temp"
bq show --schema --format=prettyjson "$BQ_TARGET" > cx_schema.json
bq load --source_format=NEWLINE_DELIMITED_JSON "$BQ_TEMP" cx-$FROM-$TO.json cx_schema.json
```

```sql
DELETE FROM \`$BQ_DATASET.cx_backfill_temp\`
  WHERE row_id IN (SELECT row_id FROM \`$BQ_DATASET.partition_test_sim_update_history_c_x\`);
-- expect 0 rows removed

INSERT INTO \`$BQ_DATASET.partition_test_sim_update_history_c_x\`
    (row_id, ingested_at, record_time, payload, payload_interned, datastream_metadata)
  SELECT row_id, ingested_at, record_time, payload, payload_interned, datastream_metadata
  FROM \`$BQ_DATASET.cx_backfill_temp\`;
-- expect 12 rows inserted
```

- The column list is required: BigQuery rejects `INSERT … SELECT *` into an
  ingestion-time partitioned table, as Datastream's appendOnly tables are.
- **Seen in a real run:** `bq load` accepts `ingested_at` as `row_to_json`
  writes it (`2026-10-21T10:00:00.123456+00:00`), and accepts the rows without
  `datastream_metadata`. All of its fields, `uuid` included, are null in the
  loaded rows.

Verify, and clean up:

```sql
-- re-run the gap query above
-- expect no rows

SELECT datastream_metadata IS NULL AS backfilled, count(*), min(row_id), max(row_id)
  FROM \`$BQ_DATASET.partition_test_sim_update_history_c_x\` GROUP BY 1 ORDER BY 1;
-- expect false, 11, 1, 1000 (including the 2 probe rows from 2d) and true, 12, 6, 17

SELECT row_id, count(*) FROM \`$BQ_DATASET.partition_test_sim_update_history_c_x\`
  GROUP BY row_id HAVING count(*) > 1;
-- expect only 1, 2: the original row 1 and the 2d probe's update of it
```

```bash
bq rm -f -t "$BQ_TEMP"
kubectl delete pod pgpart-db-relay --now
```

## 3. Pruning by DDL

Dropping a partition, or the legacy table, is DDL. Logical replication emits
nothing for it, so the append-only BigQuery tables must not change.

```sql
DROP TABLE sim_scan_verdict_store_p<yesterday>;   -- e.g. sim_scan_verdict_store_p20261011
-- DROP TABLE
DROP TABLE sim_update_history_c_x_p<yesterday>;
-- DROP TABLE
DROP TABLE sim_update_history_c_x_legacy;         -- out of the publication since 2e
-- DROP TABLE

SELECT count(*) FROM sim_scan_verdict_store;
-- 13 (17 minus yesterday's 4)

SELECT count(*) FROM sim_update_history_c_x;
-- 12 (16 minus yesterday's 4 gap rows; the 5 pre-cutover rows went with the legacy table)
```

Wait a few minutes, then:

```sql
-- the table list
-- expect no change

SELECT count(*) FROM \`$BQ_DATASET.partition_test_sim_scan_verdict_store\`;
-- expect 17, unchanged from section 2

SELECT count(*) FROM \`$BQ_DATASET.partition_test_sim_update_history_c_x\`;
-- expect 23, unchanged from section 2 (21 and the 2 probe rows from 2d)

SELECT datastream_metadata.change_type, count(*)
  FROM \`$BQ_DATASET.partition_test_sim_scan_verdict_store\` GROUP BY 1;
-- expect INSERT, 17 (no DELETE)

SELECT datastream_metadata.change_type, count(*)
  FROM \`$BQ_DATASET.partition_test_sim_update_history_c_x\` GROUP BY 1 ORDER BY 1;
-- expect NULL, 12 (the rows filled in 2f), INSERT, 10 (9 and the probe's insert)
-- and UPDATE, 1 (the probe's update); no DELETE

SELECT count(*) FROM \`$BQ_DATASET_LEGACY.partition_test_sim_scan_verdict_store\`;
-- expect 17, unchanged from section 2

SELECT count(*) FROM \`$BQ_DATASET_LEGACY.partition_test_sim_update_history_c_x\`;
-- expect 22, unchanged from section 2 (rows 1 to 21 and the probe's 1000)
```

- **Expected:** both streams are still `RUNNING`.

### `DELETE` on the new parent while it is published

Scan deletes from the new parents by name, for example to clean up after a
domain migration. Logical replication rejects an `UPDATE` or `DELETE` on a
published table without a replica identity, so check that the partitions
have one, through the parent's primary key:

```sql
SELECT c.relname, c.relreplident, i.indexrelid::regclass AS pk
  FROM pg_class c LEFT JOIN pg_index i ON i.indrelid = c.oid AND i.indisprimary
  WHERE c.relname LIKE 'sim_update_history_c_x%' AND c.relkind IN ('p', 'r') ORDER BY 1;
-- every row has d (default: the primary key) and a primary key index
```

Delete a gap row, which reached BigQuery through 2f, and a post-fix row, which
reached it through the stream:

```sql
DELETE FROM sim_update_history_c_x WHERE row_id IN (10, 18)
  RETURNING tableoid::regclass, row_id;
-- sim_update_history_c_x_p<today>, 10 and sim_update_history_c_x_p<today>, 18
-- DELETE 2
```

Wait a few minutes, then:

```sql
SELECT row_id, payload, datastream_metadata.change_type
  FROM \`$BQ_DATASET.partition_test_sim_update_history_c_x\`
  WHERE datastream_metadata.change_type = 'DELETE' ORDER BY 1;
-- expect 10 and 18, DELETE: appended under the parent's name, not the partition's

SELECT count(*) FROM \`$BQ_DATASET.partition_test_sim_update_history_c_x\`;
-- expect 25 (23 and the 2 DELETE rows)

SELECT row_id FROM \`$BQ_DATASET_LEGACY.partition_test_sim_update_history_c_x\`
  WHERE row_id IN (10, 18);
-- expect no rows: the merge removed both

SELECT count(*) FROM \`$BQ_DATASET_LEGACY.partition_test_sim_update_history_c_x\`;
-- expect 20 (22 minus 2)

-- the table list
-- expect no change: no per-partition table
```

- **Expected:** both streams are still `RUNNING`, with no error for the object.
- **Record:**
  - what the stag-prod `DELETE` rows carry besides the key, `(row_id, record_time)`;
    with the default replica identity, Postgres sends only the key columns;
  - whether the legacy-shaped dataset removed the rows, given the merge key
    recorded in 2e.

## 4. Test status

The above was run and confirmed on a real scratchnet cluster at `a0d914e224434db747f006522747fc6f5737d2da` on 2026-10-21,
except the legacy-shaped stream and the writes to and backfill around the
`*_legacy` table, which were added after that run. Those were run through 2d
on 2026-10-24, with the results recorded there.

The earlier procedure, whose migration altered the
publication itself, was verified in a real test as of
[`ef62712d6d`](https://github.com/canton-network/splice/blob/ef62712d6d58bc9063b8bd32137181f12176cc8b/cluster/pulumi/pg-partition-bq-test/README.md)
on 2026-10-19.

## 5. Read-only check for real clusters

Do **not** alter publications on real clusters outside a release; the
Datastreams run on them. Check only:

```sql
SELECT pubname, pubviaroot FROM pg_publication;
-- both update_history_datastream_pub and update_history_datastream_stag_prod_pub should have t
```

`bigquery-cloudsql.sh` sets `publish_via_partition_root` only when it **creates**
a publication. Its `ALTER PUBLICATION … SET TABLE` branch leaves the existing
setting alone. In production both publications have `t`; the legacy one since it
was deleted and recreated. A publication with `f` can't be fixed in place:
Datastream does not support changing that setting on a publication a stream
already uses, and the stream fails permanently. Such a cluster needs a new
publication, slot and stream before the cutover.

## 6. Runbook for the release that introduces V078

> **Reminder:** copy this into the runbook of the release that introduces
> V078, adjusted by what section 2 shows on GCP.

1. **Code, in the release itself:** add an optional key to
   `ScanBigQueryConfigSchema` for the migration `bigquery-cloudsql.sh` waits
   for, defaulting to today's `V068__app_activity_record_meta.sql`. The schema is
   `.strict()`, so a config that sets the key before the code knows it fails
   every stack that parses the SV config.
2. **Deploy the release.** From then on, `update_history_creates` and
   `update_history_exercises` are not replicated by either Datastream, while the
   verdict tables keep flowing. Nothing fails or alerts.
3. **Then, in a separate commit,** set the key to exactly
   `V078__partition_update_history.sql`. An already-applied migration makes
   `SET TABLE` run before the rename, as a no-op, and the publications stay
   broken; a misspelled one fails the `canton-network` stack on every retry.
   The operator re-runs `bigquery-cloudsql.sh` for both publications. Both
   imports start in the same `up`, and Cloud SQL may reject the second while the
   first runs; the operator's retry handles that.
4. **Check** that `pg_publication_tables` lists the new parents and no
   `*_legacy` table, for both publications.
5. **Fill the gap** between steps 2 and 3, for both tables in both datasets,
   with the canton-network-internal#7622 procedure, as in section 2f. Name the
   columns in its `INSERT … SELECT`: the stag-prod tables are ingestion-time
   partitioned, and BigQuery rejects `SELECT *` without a column list. For
   stag-prod, fill `<dataset>_prod` directly (with `record_date`), not only
   `<dataset>_staging`: the hourly `sp_append_*` job copies staging to prod above a
   per-table `record_time` watermark, which the first rows after step 3 move past
   the whole gap. The legacy
   stream uses `backfillAll`: watch whether it starts backfilling the new
   relations after step 3, and see #7622 on switching it to `--backfill-none`
   first. Sections 2e and 2f show whether it does, and whether a manual
   backfill of the new parents fills the legacy dataset instead.
6. **Don't** write to the `*_legacy` tables between steps 2 and 3, not even to
   prune them by `DELETE`: they are still in both publications, and Datastream
   applies their changes to the BigQuery tables of the old names (section 2d).
   As of PR 7770 nothing does: V078 only renames them, and scan only reads them.
   Pruning them with `DROP TABLE` is DDL, which logical replication doesn't emit.
7. **Don't** change anything that would replace the pub/slot commands: no
   `triggers`, no renamed resources, no `--replace`. A replace runs
   `delete-pub-rep-slot`, which drops the slot.

## Teardown

```bash
cncluster pulumi pg-partition-bq-test config rm waitForMigration
cncluster pulumi pg-partition-bq-test down
```

Remove `waitForMigration` first: a fresh `up` that waits for V078 times out,
because only section 2b records it.

Deleting the stack drops the replication slots and publications first, then the
instance, and deletes the BigQuery datasets along with their contents.

Everything the stack and this procedure create carries `pgpart-pr7737`: as the
`testing` label where the resource takes labels, as the description of the
firewall rule, and as a network tag on the NAT VM. To look for leftovers (Cloud
Asset Inventory lags by a few minutes, and needs the Cloud Asset API):

```bash
gcloud asset search-all-resources --scope="projects/$CLOUDSDK_CORE_PROJECT" --query='pgpart-pr7737'
kubectl get pods -A -l testing=pgpart-pr7737
```

The temporary GCS bucket that `bigquery-cloudsql.sh` uses to create the
publication is not labelled, because the stack runs that script unchanged.
