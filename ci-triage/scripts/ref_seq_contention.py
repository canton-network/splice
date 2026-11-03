#!/usr/bin/env python3
"""Reference sequencer `insert block` contention in one Canton log (family L).

Runs in the sandbox on a downloaded artifact. Usage, from the repo root:
  ci-triage/scripts/ref_seq_contention.py log/<ref>/art/canton-simtime.clog.gz [--sequencers globalSequencerSv]
Prints, for the sequencers whose name starts with the prefix: stored batches, failed inserts, retry chains by
number of failures before the next stored batch and by duration, retry delays over 1 s, and per minute the
enqueued requests, stored batches and failed inserts.
"""
import argparse
import collections
import datetime
import gzip
import json
import re

SEQ = re.compile(r"sequencer=(\w+)")
DELAY = re.compile(r"Retrying after ([0-9.]+)s")


def ts(s: str) -> datetime.datetime:
    return datetime.datetime.fromisoformat(s.replace("Z", "+00:00"))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("clog", help="Canton log (.clog.gz), JSON lines")
    ap.add_argument("--sequencers", default="globalSequencerSv", help="sequencer name prefix")
    args = ap.parse_args()

    stored: collections.Counter[str] = collections.Counter()
    failed: collections.Counter[str] = collections.Counter()
    enqueued: collections.Counter[str] = collections.Counter()
    losses: dict[str, int] = {}
    chain_start: dict[str, str] = {}
    by_losses: collections.Counter[int] = collections.Counter()
    durations: list[tuple[float, str, str]] = []
    long_delays: list[tuple[str, str, float]] = []

    with gzip.open(args.clog, "rt", errors="replace") as f:
        for line in f:
            if "insert block" not in line and "Stored batch of requests" not in line and "enqueued reference" not in line:
                continue
            try:
                j = json.loads(line)
            except json.JSONDecodeError:
                continue
            m = SEQ.search(j.get("logger_name", ""))
            if not m or not m.group(1).startswith(args.sequencers):
                continue
            seq, t, msg = m.group(1), j["@timestamp"], j.get("message", "")
            minute = t[:16]
            if msg.startswith("Stored batch of requests"):
                stored[minute] += 1
                if seq in losses:
                    by_losses[losses.pop(seq)] += 1
                    start = chain_start.pop(seq)
                    durations.append(((ts(t) - ts(start)).total_seconds(), start, seq))
            elif "'insert block' has failed" in msg:
                failed[minute] += 1
                losses[seq] = losses.get(seq, 0) + 1
                chain_start.setdefault(seq, t)
                d = DELAY.search(msg)
                if d and float(d.group(1)) > 1.0:
                    long_delays.append((t, seq, float(d.group(1))))
            elif msg.startswith("enqueued reference sequencer store request"):
                enqueued[minute] += 1

    n_stored, n_failed = sum(stored.values()), sum(failed.values())
    print(f"{args.clog}: sequencers {args.sequencers}*")
    print(f"stored batches {n_stored}, failed inserts {n_failed} ({n_failed / max(1, n_stored):.2f} per stored batch), "
          f"retry chains {len(durations)}, unfinished chains {len(losses)}")
    print("failures before success: " + ", ".join(f"{k}: {v}" for k, v in sorted(by_losses.items())))
    buckets = [(0.0, 0.5), (0.5, 2.0), (2.0, 5.0), (5.0, float("inf"))]
    print("chain duration (s): " + ", ".join(
        f"{lo}-{hi}: {sum(1 for d, _, _ in durations if lo <= d < hi)}" for lo, hi in buckets))
    for d, start, seq in sorted(durations)[-3:]:
        print(f"  longest: {d:.1f} s from {start} {seq}")
    print(f"retry delays over 1 s: {len(long_delays)}")
    for t, seq, v in long_delays:
        print(f"  {t} {seq} {v}s")
    print("minute            enqueued  stored  failed  failed/stored")
    for minute in sorted(set(stored) | set(failed) | set(enqueued)):
        print(f"{minute}  {enqueued[minute]:8d} {stored[minute]:7d} {failed[minute]:7d}  {failed[minute] / max(1, stored[minute]):.2f}")


if __name__ == "__main__":
    main()
