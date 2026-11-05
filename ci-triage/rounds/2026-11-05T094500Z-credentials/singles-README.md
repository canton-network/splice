# Group singles: 11188, 11182 (fork)

| Ref | Verdict | Cause | Rank |
|---|---|---|---|
| 11188 | duplicate of 9440 (reopen) | Canton: sv1 participant reconnect hangs after the sequencer connection swap (after `Ensured ... persistent state`); teardown plugin sys.exit (family H) hides the report | 3, rare: 4 hits since July (9151, 9440, 10235, 11188), app-upgrade runs on every main push |
| 11182 | root cause, new | scan BulkStorageCommitFromStaging: `Source.repeat` + `mapAsync(1)` starts one extra BFT check after success; it races with a peer deleting its staging copy and logs a false ERROR | 3, rare: first hit; production bug, false ERROR on clusters too |

Packets: `evidence/11188-app-upgrade-sv1-reconnect-hang.md`, `evidence/11182-scan-bulk-bft-recheck-false-error.md`.
Comments: `comments/9440.md`, `comments/11188-dup.md`, `comments/11182.md`. Lesson: none for both (Canton bug; production code).
