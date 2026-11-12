#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""
Load a periodic topology snapshot (written by the SV app's PeriodicTopologySnapshotTrigger)
into BigQuery, replacing the tables' previous contents:

1. Take the snapshot from a local file, or download the latest complete one from the snapshot
   bucket.
2. Decode it into JSONL + BigQuery schemas with scripts/topology-snapshot-to-jsonl.sc.
3. Upload the JSONL files to a GCS bucket.
4. Create the BigQuery dataset if it doesn't exist, and load both tables, replacing their
   contents.
5. Check that each load wrote as many rows as its JSONL file has.

Needs `canton` on the PATH and the google-cloud-storage and google-cloud-bigquery Python
packages, all of which the repo's nix dev shell provides. Authenticates with Application
Default Credentials (locally: `gcloud auth application-default login`). Example:

  direnv exec . scripts/topology-snapshot-to-bigquery.py \\
    --snapshot-location gs://<snapshot-bucket>/<prefix> \\
    --project-id <project-id> --dataset <dataset> --location <location> \\
    --export-bucket <bucket>
"""

import argparse
import json
import logging
import os
import re
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

# the ignore is for storage, which ships without type information (no py.typed)
from google.cloud import bigquery, storage  # type: ignore[attr-defined]

logger = logging.getLogger("topology-snapshot-to-bigquery")

SNAPSHOT_FOLDER_RE = re.compile(r"(^|/)topology_snapshot_(\d{4}-\d{2}-\d{2})/$")
# Files PeriodicTopologySnapshotTrigger writes; `metadata` is written last.
SNAPSHOT_FILES = ("onboarding-state.zst", "authorized", "metadata")
DECODER_SCRIPT = Path(__file__).resolve().parent / "topology-snapshot-to-jsonl.sc"
# Canton refuses to start without at least one node; the decoder never connects to this one.
CANTON_DUMMY_NODE = (
    "canton.remote-participants.unused.admin-api.port=1,"
    "canton.remote-participants.unused.ledger-api.port=2"
)
# The transactions file is several GB; give uploads plenty of time.
UPLOAD_TIMEOUT_SECONDS = 3600


@dataclass(frozen=True)
class Table:
    name: str
    jsonl: Path
    schema: Path


def split_gs_uri(uri: str) -> tuple[str, str]:
    """gs://bucket/some/path -> ("bucket", "some/path")"""
    if not uri.startswith("gs://"):
        raise ValueError(f"Not a gs:// URI: {uri}")
    bucket, _, path = uri.removeprefix("gs://").partition("/")
    return bucket, path


def latest_complete_snapshot(client: storage.Client, location: str) -> str:
    """Returns the gs:// folder of the most recent snapshot that has all its files."""
    bucket, prefix = split_gs_uri(location.rstrip("/") + "/")
    if prefix == "/":
        prefix = ""
    listing = client.list_blobs(bucket, prefix=prefix, delimiter="/")
    # the folder "prefixes" are only populated once the pages are consumed
    for _ in listing:
        pass
    folders = sorted(
        (m.group(2), folder)
        for folder in listing.prefixes
        if (m := SNAPSHOT_FOLDER_RE.search(folder))
    )
    for date, folder in reversed(folders):
        names = {
            blob.name.rsplit("/", 1)[-1]
            for blob in client.list_blobs(bucket, prefix=folder)
        }
        if all(f in names for f in SNAPSHOT_FILES):
            logger.info(
                "Latest complete snapshot is from %s: gs://%s/%s", date, bucket, folder
            )
            return f"gs://{bucket}/{folder}"
        logger.info(
            "Skipping incomplete snapshot gs://%s/%s (has %s)", bucket, folder, names
        )
    sys.exit(f"No complete topology snapshot found under {location}")


def fetch_snapshot(client: storage.Client, location: str, work_dir: Path) -> Path:
    if not location.startswith("gs://"):
        return Path(location)
    folder = location.rstrip("/") + "/"
    if not SNAPSHOT_FOLDER_RE.search(folder):
        folder = latest_complete_snapshot(client, location)
    bucket_name, path = split_gs_uri(folder)
    bucket = client.bucket(bucket_name)
    local = work_dir / "onboarding-state.zst"
    logger.info("Downloading %sonboarding-state.zst", folder)
    bucket.blob(path + "onboarding-state.zst").download_to_filename(str(local))
    metadata = bucket.blob(path + "metadata").download_as_text()
    logger.info("Snapshot metadata: %s", json.dumps(json.loads(metadata)))
    return local


def decode(
    snapshot: Path,
    transactions: Table,
    members: Table,
    canton_log: Path,
    canton: str,
    java_opts: str,
) -> None:
    env = {
        **os.environ,
        "SNAPSHOT_IN": str(snapshot),
        "SNAPSHOT_OUT": str(transactions.jsonl),
        "SNAPSHOT_SCHEMA_OUT": str(transactions.schema),
        "SNAPSHOT_MEMBERS_OUT": str(members.jsonl),
        "SNAPSHOT_MEMBERS_SCHEMA_OUT": str(members.schema),
        "JAVA_OPTS": java_opts,
    }
    cmd = [
        canton,
        "run",
        str(DECODER_SCRIPT),
        "--no-tty",
        "-C",
        CANTON_DUMMY_NODE,
        "--log-file-name",
        str(canton_log),
    ]
    logger.info("Decoding %s with %s", snapshot, DECODER_SCRIPT.name)
    subprocess.run(cmd, check=True, env=env)
    for table in (transactions, members):
        for f in (table.jsonl, table.schema):
            if not f.is_file():
                sys.exit(f"Decoding did not produce {f}; see {canton_log}")


def count_lines(path: Path) -> int:
    with path.open("rb") as f:
        return sum(1 for _ in f)


def upload(client: storage.Client, local: Path, uri: str) -> None:
    bucket, path = split_gs_uri(uri)
    logger.info("Uploading %s to %s", local, uri)
    client.bucket(bucket).blob(path).upload_from_filename(
        str(local), timeout=UPLOAD_TIMEOUT_SECONDS
    )


def load_table(client: bigquery.Client, dataset: str, table: Table, uri: str) -> int:
    """Replaces the table's contents with the JSONL file at uri; returns the rows written."""
    job_config = bigquery.LoadJobConfig(
        source_format=bigquery.SourceFormat.NEWLINE_DELIMITED_JSON,
        write_disposition=bigquery.WriteDisposition.WRITE_TRUNCATE,
        schema=client.schema_from_json(str(table.schema)),
    )
    destination = f"{client.project}.{dataset}.{table.name}"
    logger.info("Loading %s into %s", uri, destination)
    job = client.load_table_from_uri(uri, destination, job_config=job_config)
    job.result()  # raises if the load failed
    return int(job.output_rows or 0)


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "--snapshot-location",
        required=True,
        help="Local onboarding-state.zst, a gs:// topology_snapshot_<date> folder, or the gs://"
        " location (bucket + prefix) holding those folders, in which case the latest complete"
        " snapshot is used",
    )
    parser.add_argument(
        "--project-id",
        required=True,
        help="GCP project ID (not name) for BigQuery and the export bucket",
    )
    parser.add_argument("--dataset", required=True, help="BigQuery dataset")
    parser.add_argument(
        "--location",
        required=True,
        help="BigQuery location, used if the dataset has to be created (e.g. EU, europe-west6)",
    )
    parser.add_argument(
        "--export-bucket", required=True, help="GCS bucket for the JSONL files"
    )
    parser.add_argument("--export-prefix", default="topology-export")
    parser.add_argument("--transactions-table", default="topology_transactions")
    parser.add_argument("--members-table", default="sequencer_members")
    parser.add_argument(
        "--work-dir", help="Directory for intermediate files (default: a temp dir)"
    )
    parser.add_argument(
        "--keep-files",
        action="store_true",
        help="Keep the intermediate files at the end",
    )
    parser.add_argument("--canton", default="canton", help="Canton binary")
    parser.add_argument("--java-opts", default="-Xmx512m")
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s"
    )

    if shutil.which(args.canton) is None:
        sys.exit(
            f"{args.canton} not found on the PATH; run this from the repo's nix dev shell"
        )

    project_id = args.project_id
    storage_client = storage.Client(project=project_id)
    bq_client = bigquery.Client(project=project_id, location=args.location)

    work_dir = Path(args.work_dir or tempfile.mkdtemp(prefix="topology-snapshot-"))
    work_dir.mkdir(parents=True, exist_ok=True)
    logger.info("Working in %s", work_dir)
    transactions = Table(
        args.transactions_table,
        work_dir / "transactions.jsonl",
        work_dir / "transactions-schema.json",
    )
    members = Table(
        args.members_table, work_dir / "members.jsonl", work_dir / "members-schema.json"
    )
    canton_log = work_dir / "canton.log"
    # only these are removed at the end, so a --work-dir with other files in it is safe
    created = [
        transactions.jsonl,
        transactions.schema,
        members.jsonl,
        members.schema,
        canton_log,
    ]
    try:
        snapshot = fetch_snapshot(storage_client, args.snapshot_location, work_dir)
        if args.snapshot_location.startswith("gs://"):
            created.append(snapshot)  # downloaded, not the caller's file
        decode(snapshot, transactions, members, canton_log, args.canton, args.java_opts)

        dataset = bigquery.Dataset(f"{project_id}.{args.dataset}")
        dataset.location = args.location
        bq_client.create_dataset(dataset, exists_ok=True)

        prefix = f"gs://{args.export_bucket}/{args.export_prefix.strip('/')}"
        for table in (transactions, members):
            uri = f"{prefix}/{table.jsonl.name}"
            upload(storage_client, table.jsonl, uri)
            written = load_table(bq_client, args.dataset, table, uri)
            expected = count_lines(table.jsonl)
            if written != expected:
                sys.exit(
                    f"{table.name}: load wrote {written} rows, expected {expected}"
                )
            logger.info("%s.%s: %d rows", args.dataset, table.name, written)
    finally:
        if args.keep_files:
            logger.info("Keeping files in %s", work_dir)
        elif args.work_dir:
            for f in created:
                f.unlink(missing_ok=True)
        else:
            shutil.rmtree(work_dir)  # our own temp dir


if __name__ == "__main__":
    main()
