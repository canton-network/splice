Paste-ready comment for cn-test-failures 10010 (run 33785420105, job 100749318272). ASCII only.

---

The "expected when a topology change is in flight" reading does not match the artifact for this job, so I would
not suppress the line. The WARN is the symptom of a node that had stopped processing, not of a change still
propagating.

1. No topology change concerning sv3 was in flight when the rejections happened. The SequencerSynchronizerState
   transaction that adds SEQ::sv3 (serial 3) was sequenced with effective time 17:48:06.376. sv4 started rejecting
   sv3's challenges at 17:48:27.683, 21 s later, and kept rejecting them 53 times until 17:48:39.561. Serial 4
   (sv2) was effective at 17:48:10.702. From 17:48:11 onwards the sequencer topology was stable.

2. sv4 rejected sv3 because sv4 itself was frozen on a stale topology. sv4 initialized from its onboarding
   snapshot at 17:48:19.366 with head block 250 (block time 17:48:05.836), which predates serial 3, so its
   store held only serial 2 (sv1, sv4). It then had to catch up a few blocks to learn about sv3. Instead, at
   17:48:24.628 it logged "Received topology for epoch 13, but this node isn't part of it (i.e., it has been
   off-boarded): not starting consensus as this node is going to be shut down and decommissioned" and stopped
   consuming blocks. That is the false off-boarding conclusion of #10048 (state transfer empty response mistaken
   for a next-epoch topology), fixed in canton#35600 (3.5.17). sv4 only persisted serial 3 at 17:48:39.988,
   after sv1's retransmission requests for epoch 14 pushed it into catch-up state transfer at 17:48:39.807, and
   the rejections stopped at that instant.

3. The other two failed shards of the same run, wall-clock-time (8) and (9), hit the same false off-boarding on
   sv2 and wedged (they are in the 2026-09-10 sweep of 27 occurrences). Shard (1) is the same bug with a milder
   outcome because sv1's retransmissions happened to pull sv4 back into catch-up.

4. Consequence for the proposed fix: an ignore pattern on "Failed to fetch P2P server authentication token ...
   access is disabled" would hide the only WARN that surfaces this bug on shards where the wedge does not
   happen. Since canton 3.5.17 contains #35600, main (since the 20260910 snapshot), release-line-0.8.x and 0.8.1
   should no longer produce it; release-line-0.8.0 still pins 3.5.16 and can. Close this against the canton
   bump and re-check any post-3.5.17 occurrence individually rather than adding the ignore.

Evidence packet: ci-triage/10010-p2p-auth-token-denied-false-offboarding.md (all timestamps from
logs-wall-clock-time-1/canton_before_shutdown.clog of the run).
