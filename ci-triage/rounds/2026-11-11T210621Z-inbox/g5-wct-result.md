# g5-wct result (11364 11375 11376)

Access: cn-test-failures and splice via `gh api` (sandbox token). Job logs from `log/inbox-1111b/`, artifacts in
`log/11364/`, `log/11375/`, `log/11376/` (gitignored). Job ids confirmed with recipe 1 (exactly one failed match each,
attempt 1).

Packets:
- `evidence/11364-getconnectedsynchronizers-client-echo-dup-9445.md`
- `evidence/11375-11376-bft-slowdown-two-shards-same-window.md`

## 11364 - duplicate of 9445 (Canton GetConnectedSynchronizers INTERNAL mid-reconnect), new client-side shape

Verdict: duplicate of 9445 (open, assigned carlpulley-da). All 27 tests passed; checkErrors flagged three sv2Scan
lines at 14:32:20.926-.930 (ApiClientRequestLogger ERROR `GetConnectedSynchronizers ... failed with INTERNAL/An error
occurred`, DomainIngestionService WARN non-retryable, ERROR `Skipping processing`). sv4 sequencer appeared in the sv2
validator SynchronizerConnector at 14:32:20.865; ReconcileSequencerConnectionsTrigger modified the connection and
disconnected/reconnected sv2Participant (DisconnectSynchronizer 14:32:20.920-.940); the scan poll failed at .921 on the
participant with `Failed retrieving SynchronizerTopologyClient` (the 9445 race). The participant line is ignored since
splice#7334 (canton_log.ignore.txt:219) and printed as ignored; the client echo in the app log is not covered.
Second client-side hit after 6016 (2025-10-21). Rank: rare (2 client-side hits in about 13 months), Canton fix owned.

INDEX row:
```
11364	2026-11-11	43031077112	main 644b3ba97a (#8424)	130559764394 wall-clock-time (5)	3.7.3-snapshot.20261109.20894.0.ve6e6c252	SvOnboardingAddlIntegrationTest checkErrors: sv2Scan GetConnectedSynchronizers INTERNAL (masked) + DomainIngestionService WARN/ERROR at 14:32:20.926-.930; sv2Validator ReconcileSequencerConnectionsTrigger reconnects sv2Participant after sv4 sequencer appears (14:32:20.865), participant fails with Failed retrieving SynchronizerTopologyClient at .921; participant line ignored since #7334, client echo not	9445 (G)	canton	close dup 9445; optional note on 9445 (client echo not ignored)	evidence/11364-getconnectedsynchronizers-client-echo-dup-9445.md
```

known-families.md, family G (append to the section; index row G: add 9445 to the refs):
```
- 9445 GetConnectedSynchronizers INTERNAL while a participant disconnects a synchronizer (Canton race between
  `removeAndClose` and re-adding the topology client; open, carlpulley-da). Participant line
  `failed with INTERNAL/Failed retrieving SynchronizerTopologyClient` ignored since splice#7334
  (canton_log.ignore.txt:219). Client echo NOT ignored: app-log ApiClientRequestLogger ERROR
  `GetConnectedSynchronizers to <port>: failed with INTERNAL/An error occurred. Please contact the operator` plus
  DomainIngestionService WARN `processTaskWithRetry failed with a non-retryable error` and ERROR `Skipping processing of
  PeriodicTask` (6016 aliceValidator 2025-10; 11364 sv2Scan 2026-11-11, SvOnboardingAddlIntegrationTest, reconnect by
  ReconcileSequencerConnectionsTrigger after sv4 sequencer appears). Confirm: the participant line with the same trace
  id 1 ms earlier, and `DisconnectSynchronizer ... received` on that participant just before it. Close as dup of 9445.
```

Lesson: none (Canton bug; the ignore gap is specific to this workaround).

Action (host):
```
direnv exec . ci-triage/scripts/close_duplicates.py 9445 11364 --comment 'Same race on the client side: sv2Participant was reconnecting after a sequencer connection update and the sv2 scan poll of GetConnectedSynchronizers got INTERNAL; the existing ignore covers only the participant log line, not this client echo.'
```
Optional, only if the user wants the 9445 owner to see the new shape (9445 has an assignee, so by the skill rule no
recurrence comment by default):
```
gh issue comment 9445 -R DACH-NY/cn-test-failures --body-file ci-triage/rounds/2026-11-11T210621Z-inbox/comments/9445.md
```
Assignee: none to add (9445 already assigned to carlpulley-da).

## 11375 - infra: 4 min synchronizer slowdown on two shards at once (WalletExpirationsIntegrationTest)

Verdict: new, infra flake; no splice change. From 18:45:22 to 18:49:18 UTC the two failing shards of run 43073677994
(separate runner pods r82nt and 8vqj4, each with its own Canton) slowed down in lockstep: BFT epochs 15-36 s instead
of 3.5-6 s, sv1 local pre-prepare DB write 100-370 ms instead of about 5 ms. `auto-expire payment requests` fails in
its first step: aliceValidator `onboardUser` hits the 38 s HTTP client timeout at 18:48:50.982 (request completed
after 63.8 s; installWalletForUser 20.1 s and createValidatorRight 29.5 s submissions, each ordering hop 3-6 s).
The other 19 onboardUser calls in the shard took less than 10 s each.
Canton bump (#8441): not implicated. Both pods slowed and recovered within 2 s of each other; the next post-merge runs
on the same Canton (915616a, d356bcf) passed their wall-clock shards; the previous wall-clock-time (5) (run
43031077112, old Canton) has slow epochs only at startup. Not verified: what both pods shared (node, storage).
Rank: one-off (first hit), infra.

INDEX row:
```
11375	2026-11-11	43073677994	main ee8772cc0b (#8441)	130708763686 wall-clock-time (5)	3.7.3-snapshot.20261111.20904.0.vbe797d9d	WalletExpirationsIntegrationTest auto-expire payment requests: onboardUser HTTP 38 s timeout 18:48:50.982 (request done after 63.8 s, two submissions 20.1 s / 29.5 s); BFT epochs 15-36 s 18:45:22-18:49:18 on this shard and wall-clock-time (9) at the same time (separate pods), sv1 pre-prepare DB write 100-370 ms; not the Canton bump (next runs green)	new	infra	comment, Triaged; parent of 11376	evidence/11375-11376-bft-slowdown-two-shards-same-window.md
```

known-families.md, family J (infra) entry:
```
- Run-wide slowdown of the test host (11375, dup 11376; run 43073677994, main ee8772c, 2026-11-11): wall-clock-time
  (5) and (9), separate runner pods with their own Canton, slow down in lockstep 18:45:22-18:49:18 UTC: BFT epochs on
  globalSequencerSv1 15-36 s instead of 3.5-6 s, sv1 `PrePrepare received` -> `DB stored pre-prepare` 100-370 ms
  instead of about 5 ms; every ledger round trip needs 3 hops of 3-6 s, so 38 s HTTP calls (onboardUser) and 20 s
  `eventually()` around one transaction fail. Confirm: the epoch-duration awk in the 11375 packet on two shards of
  the same run shows the slow epochs starting and ending within seconds of each other. Not the Canton version (next
  runs on it green). Cause of the host slowdown not visible in artifacts (GCP: node of the runner pods).
```
(Not family M: M is steady per-hop latency vs a too-short deadline; here every budget fails only inside the window.)

Lesson: none (infra one-off).

Action (host):
```
gh issue comment 11375 -R DACH-NY/cn-test-failures --body-file ci-triage/rounds/2026-11-11T210621Z-inbox/comments/11375.md
gh issue edit 11375 -R DACH-NY/cn-test-failures --title 'wall-clock-time: 4 min synchronizer slowdown on two shards at once (WalletExpirations, SvMergeSvRewardState)'
direnv exec . ci-triage/scripts/set_status.py Inbox Triaged 11375
```
Assignee: none (infra; no evidence pointing at a person).
Optional follow-up for the user, only if it recurs: GCP Logs for the two runner pods 18:40-18:55 UTC (packet Verdict
has the `gcp_logs.py` command; the node name is in the entry labels).

## 11376 - duplicate of 11375 (same run, same window)

Verdict: duplicate of 11375. `Multiple SvRewardStates for the same SV get merged` fails its 20 s `eventually()` at
SvMergeSvRewardStateIntegrationTest.scala:75: the clue starts 18:47:41.825, the MergeSvRewardStateContractsTrigger
submits at 18:47:42.454 and the transaction returns at 18:48:01.955 (19.5 s; ordering hops 3.7 s, 5.0 s, 4.3 s), clue
fails at 18:48:02.054. Inside the same 18:45:22-18:49:18 slowdown as 11375 (shard 9 epochs 304-313, 19-35 s).

INDEX row:
```
11376	2026-11-11	43073677994	main ee8772cc0b (#8441)	130708763759 wall-clock-time (9)	3.7.3-snapshot.20261111.20904.0.vbe797d9d	SvMergeSvRewardStateIntegrationTest: merge transaction 18:47:42.454-18:48:01.955 (19.5 s) misses the 20 s eventually by 130 ms; same 18:45:22-18:49:18 BFT slowdown as 11375 (shard 9 epochs 19-35 s)	11375 (J)	infra	close dup 11375	evidence/11375-11376-bft-slowdown-two-shards-same-window.md
```

known-families.md: covered by the 11375 entry above.

Lesson: none.

Action (host):
```
direnv exec . ci-triage/scripts/close_duplicates.py 11375 11376 --comment 'Same run and same four minute synchronizer slowdown: the merge transaction took 19.5 s and returned 130 ms after the 20 s eventually expired.'
```
Assignee: none.
