# g1-simtime-init result: 11358 11362 11368 11374

Packet: `evidence/11358-11362-11368-11374-simtime-1to4-mediator-add-lost-poisons-shard.md`.

## Verdict per ref

- 11358: duplicate of 11255 (family B, rejected-send / batch-never-ordered shape). SvExpiredRewardsCollectionTimeBased setup, MED::sv3 add lost at the 1 -> 4 step 12:00:47.
- 11362: duplicate of 11255, same shape. NonZeroRoundBootstrapBftTimeBased setup, MED::sv2 add lost at 14:05:30.
- 11368: duplicate of 11255, same shape. TimeBasedTestNetPreview setup, MED::sv3 add lost at 16:33:16.
- 11374: duplicate of 11255, same shape. TrafficBasedRewardsSvAppTimeBased setup, MED::sv3 add lost at 18:28:42.

Answers to the brief:
- Which node did not initialize: the joining SV app (sv3, sv2, sv3, sv3). It waited on `Mediator has been granted
  unlimited traffic` (96 to 320 s) because the MediatorSynchronizerState proposal adding its mediator never got enough
  signatures. Within 0.07 to 1.2 s after the global ordering topology went from 1 to 4 sequencers, the SV participants'
  signatures were rejected (`P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2)`) or sat in a
  batch that was never ordered (11358, sv3). The participants treat those sends as possibly sequenced until the max
  sequencing time on the stopped sim clock, so the outboxes stay blocked (`Another flush cycle is currently ongoing`).
- Not AM: the time did not go into DAR uploads. In 11374 (the shortest wait) all DAR uploads ended 18 s before the switch.
  Unlike 11347, the init is not slow: it is wedged.
- One common cause: yes, the same Canton BFT 1 -> 4 window under sim time. No 2026-11-11 commit is involved: the four
  shas are unrelated test or perf changes, #8356 is in all four, and two Canton snapshots ran (11374 on 20261111).
  #8356 works: there is no blacklisting in any epoch and zero lost verdicts. It never targeted this shape (as noted on
  11255 on 2026-11-09 and 2026-11-10).
- Runner slowness: not the cause. Once the signatures are lost, nothing advances sim time during setup.
- Shard damage: the failure happens in `Running setup`, so the reset plugins never run (zero `has been reset` after
  it). Every later suite fails sv1's DSO PartyToParticipant with `TOPOLOGY_UNAUTHORIZED_TRANSACTION` (86 to 252
  times per run), and 11368 then hits the family H `Address already in use` cascade. ExternallySignedTxs and
  SvTimeBasedRewardCoupon are victims that happen to run later in these shards.
- Frequency (runs, attempt 1, post-merge main): since #8356 (2026-11-10 23:29 UTC), 4 of 16 runs with simtime jobs
  failed a simtime shard, and those are exactly these four. Before #8356: 7 of 12 runs (11-09) and 6 of 15 (11-10),
  which mixed the lost-verdict and rejected-send shapes. 11-06: 3 of 11.
- First sha: the shape is not new. The first simtime hits were 11241/11242 on c218905f75 (2026-11-06), the first
  main run after splice#8234 (DA BFT in all tests). The #8234 merge run and the run before it passed.
- 11368 and 11374 come after #8432 (3b5b82822d). The failure has nothing to do with #8432: the 11358 and 11362 shas
  predate it and fail the same way.
- Related groups, not checked: 11363 and 11373 run on wall clock, where a rejected send costs at most its max
  sequencing time and does not wedge. This cause explains them only if their logs show the same rejections at the
  ordering step. 11371 and 11372 (roll-forward-lsu) are family AK, the same ordering-topology growth window.

## INDEX.tsv rows

```
11358	2026-11-11	43012738610	main 2d54198a47 (#8427)	130496550275 `simtime (3)`, attempt 1 (attempt 2 passed)	3.7.3-snapshot.20261109.20894.0.ve6e6c252	SvExpiredRewardsCollectionTimeBasedIntegrationTest setup: ordering 1 -> 4 at epoch 117 12:00:47.172 (no blacklisting); MED::sv3 add (MediatorSynchronizerState serial 4): sv1/sv2 signatures accepted, sv3 batch (ref epoch 116) never ordered, sv4 send rejected 12:00:47.828 P2P connectivity is not ready; sv3 waits for mediator traffic until 12:04:16.7; no reset, 3 later suites fail sv1 DSO PartyToParticipant TOPOLOGY_UNAUTHORIZED_TRANSACTION	11255 (B, rejected-send)	Canton BFT 1->4 step, sim time	close dup 11255	evidence/11358-11362-11368-11374-simtime-1to4-mediator-add-lost-poisons-shard.md
11362	2026-11-11	43030349815	main 2fc780a883 (#8417)	130555862019 `simtime (2)`	3.7.3-snapshot.20261109.20894.0.ve6e6c252	NonZeroRoundBootstrapBftTimeBasedIntegrationTest setup: ordering 1 -> 4 at epoch 117 14:05:29.962; MED::sv2 add signatures of sv2/sv3/sv1 participants rejected 14:05:30.149-31.011 (P2P connectivity is not ready); sv2 waits for mediator traffic 14:05:33.9 to 14:10:54.2; no reset, 2 later suites fail sv1/sv3 init (TOPOLOGY_UNAUTHORIZED_TRANSACTION)	11255 (B, rejected-send)	Canton BFT 1->4 step, sim time	close dup 11255	evidence/11358-11362-11368-11374-simtime-1to4-mediator-add-lost-poisons-shard.md
11368	2026-11-11	43055359204	main 1ee8c88066 (#8434)	130644484709 `simtime (5)`	3.7.3-snapshot.20261109.20894.0.ve6e6c252	TimeBasedTestNetPreviewIntegrationTest setup: ordering 1 -> 4 at epoch 61 16:33:16.633; MED::sv3 add signatures of all four SV participants rejected 16:33:16.700-17.162; sv3 waits 16:33:20.9 to 16:36:50.3; no reset, ScanWithGradualStarts fails (sv1Scan no app state), last 2 suites Address already in use (H)	11255 (B, rejected-send)	Canton BFT 1->4 step, sim time	close dup 11255	evidence/11358-11362-11368-11374-simtime-1to4-mediator-add-lost-poisons-shard.md
11374	2026-11-11	43073759004	main 915616a77d (#8413)	130709771107 `simtime (0)`	3.7.3-snapshot.20261111.20904.0.vbe797d9d	TrafficBasedRewardsSvAppTimeBasedIntegrationTest setup (first suite): ordering 1 -> 4 at epoch 15 18:28:41.723; MED::sv3 add signatures of sv1/sv4/sv3/sv2 rejected 18:28:42.026-.937; sv3 waits 18:28:45.9 to 18:30:21.6; no reset in the shard, 3 later suites fail init (sv1, sv1Scan), 2 abort	11255 (B, rejected-send)	Canton BFT 1->4 step, sim time	close dup 11255	evidence/11358-11362-11368-11374-simtime-1to4-mediator-add-lost-poisons-shard.md
```

## known-families.md text (family B, append under the 11255 bullets)

```
- 11255 after splice#8356 (blacklisting off in simtime), 2026-11-11, rejected-send / batch-never-ordered shape only:
  11358 11362 11368 11374 (simtime (3)/(2)/(5)/(0), main 2d54198a47, 2fc780a883, 1ee8c88066, 915616a77d; Canton
  20261109 and 20261111 snapshots), 4 of 16 post-merge runs since #8356, all four closed as dups. A 4-SV setup grows
  the ordering topology 1 -> 4; the SV participants' signatures on the MediatorSynchronizerState add of the joining
  SV's mediator are rejected (`P2P connectivity is not ready`) or batched and never ordered within 1.2 s of the
  switch; the joining SV waits on `Mediator has been granted unlimited traffic` until the 5 min init wait. All four
  are setup-step hits, so the blast radius applies (no reset, sv1 DSO PartyToParticipant
  TOPOLOGY_UNAUTHORIZED_TRANSACTION in every later suite). Confirm: `blacklisted nodes = List()` on the size-4 epoch,
  zero `currently blacklisted` and `Failed to send result`, `Rejecting submission request by Some(PAR::sv` within
  1.2 s after it, and the stuck SV's last line `Waiting until Mediator has been granted unlimited traffic`.
  Not AM: no DAR uploads in the wait.
```

## Lesson

Lesson: none new. This adds a second item to the 11347 candidate (teardown must tolerate a partially started
environment; cleanup must run on every exit path). Here the shared-state reset lives in `beforeEnvironmentDestroyed`,
and the setup-failure path skips it, so one failed setup breaks the rest of the shard. Suggested wording if merged:
"Undo shared-state changes on every exit path, including a failed setup".

## Suggested action

Close as duplicates, one call per ref, no parent comment. The parent's owner discussion already says this shape is
not covered by #8356, and the blast radius is described on 11332. The parent is assigned to rautenrieth-da.

```
direnv exec . ci-triage/scripts/close_duplicates.py 11255 11358 --comment 'Setup of SvExpiredRewardsCollectionTimeBasedIntegrationTest: right after the ordering topology grew from 1 to 4 sequencers, the signatures adding the sv3 mediator were rejected (P2P connectivity is not ready) or never ordered, sv3 waited for mediator traffic until its init timed out, and the skipped topology reset failed the later suites.'
direnv exec . ci-triage/scripts/close_duplicates.py 11255 11362 --comment 'Setup of NonZeroRoundBootstrapBftTimeBasedIntegrationTest: right after the ordering topology grew from 1 to 4 sequencers, the signatures adding the sv2 mediator were rejected (P2P connectivity is not ready), sv2 waited for mediator traffic until its init timed out, and the skipped topology reset failed the later suites.'
direnv exec . ci-triage/scripts/close_duplicates.py 11255 11368 --comment 'Setup of TimeBasedTestNetPreviewIntegrationTest: right after the ordering topology grew from 1 to 4 sequencers, all four signatures adding the sv3 mediator were rejected (P2P connectivity is not ready), sv3 waited for mediator traffic until its init timed out, and the skipped topology reset failed the later suites.'
direnv exec . ci-triage/scripts/close_duplicates.py 11255 11374 --comment 'Setup of TrafficBasedRewardsSvAppTimeBasedIntegrationTest: right after the ordering topology grew from 1 to 4 sequencers, all four signatures adding the sv3 mediator were rejected (P2P connectivity is not ready), sv3 waited for mediator traffic until its init timed out, and the skipped topology reset failed the later suites.'
```

No Status change: closing moves them to Closed. Assignee: none new (the parent is already rautenrieth-da).
Priority note for the parent: this is a frequent flake (4 of 16 post-merge runs since #8356, about one in four), and
each hit takes out the rest of its simtime shard. The two open fixes are the 1 -> 4 step (onboard the SV sequencers
one at a time, proposed on 11255) and running the reset plugins on the `Running setup` failure path (proposed on
11332). Neither has a PR on main yet.
