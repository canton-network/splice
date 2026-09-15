# CI failure triage - 2026-09-15

Evidence packets for cn-test-failures refs. Each packet is reproducible: every command was run
against the run's downloaded artifacts and the pasted output is what it produced (long hashes trimmed
by a sed/cut baked into the command). Artifacts are streamed gzipped with `zcat` (no decompression).

## Overview

| My ref | GH run | Failure (one line) | Resolution / status |
|--------|--------|--------------------|---------------------|
| 10048 | 33911369750 | BFT deadlock: SEQ::sv4 named first leader of epoch 26 before it was initialized; ordering wedges (strongQuorum == size == 3) | Canton-side bug. Evidence packet `10048-bft-deadlock.md`. Hand off to Canton team. |
| 10084 | 34458892258 | checkErrors flags 18 IndexerState reconnect-drain WARNs from sv3Participant; all tests pass | Evidence packet `10084-indexer-reconnect-warn.md`. Log-level threshold artifact (INFO escalates to WARN after 2s under reconnect load), drain succeeds in ~3.8s. Not a functional bug; log-ignore rejected; real fix (canton `retryLogLevel=INFO`, or why disconnect returns before drain) open. |
| 10088 | 34474728903 | (A) roll-forward-lsu: `InvalidStaticSynchronizerParameters` over non-default synchronizerLimits at SV init; (B) LSU: bobValidatorLocal waits 5 min for participant registration on `::36-0` | Evidence packet `10088-lsu-failures.md`. A = canton 3.6 static-param check rejects LSU predecessor params (jobs cancelled); B = LSU registration never observed (job failure). |
| 10091 / 10094 | 34477382133 | globalSequencerSv1 public API (:5108) unresponsive ~120s; co-located mediator's `acknowledge-signed` hits DEADLINE_EXCEEDED; checkErrors flags 2 WARNs. Plus a separate frontend vote-propagation flake. | Evidence packet `10094-sequencer-ack-stall.md`. Raymond's pick to prioritise. Two independent analyses converged. |
| 10121 | 34612379425 | SummarizingMiningRoundTrigger logs ERROR (fails checkErrors) on a retryable "our own Scan has not yet computed the reward accounting totals" that self-resolves on the next poll | FIX committed on branch `ray/fix-summarizing-round-log-noise` (return TaskNoop instead of throwing; INFO not ERROR). Compile not yet verified in-sandbox (disk-limited). |
| 10133 | 34931425682 | docker-compose validator: `ghcr.io` image pull `i/o timeout` bringing up the compose stack | Infra flake (transient registry timeout), no code bug. Re-run; other shard passed. |
| 10312 | cn-internal CircleCI `deploy_upgrade` | preflight `sbt testOnly` killed at the 15m step timeout; a cold Daml+Scala compile ate ~13 of 15 min before the test ran | FIX MERGED into cn-internal: add a `Test/compile` step to `preflight_check` before the timed testOnly (mirrors `preflight_validator_check`). |

## Notes on runtime canton version (differs per run; not what `canton/VERSION` says)

- 10048 (33911369750): canton 3.5.16
- 10088 (34474728903): canton 3.6.0-snapshot.20260909.20251.0.v0a9e6e25
- 10094 (34477382133): canton 3.5.15 (branch release-line-0.7.5)
- 10084 (34458892258): canton 3.6.0-snapshot.20260909.20251.0.v0a9e6e25

## Reproduction prerequisites

The sandbox root filesystem is nearly full; `gh run download` staging ENOSPCs there. Stage on a
mount with several GB free by setting TMPDIR, and keep artifacts gzipped:

```
TMPDIR=<roomy-mount>/ghtmp gh run download <run> --repo canton-network/splice -n <artifact> -D <dir>
cd <dir>
zcat <file>.clog.gz | grep -a '<pattern>'
```
