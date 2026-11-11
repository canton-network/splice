# g3-init-wct result (11363 11373)

## Verdicts

- 11363 (key-download (0), main 644b3ba, run 43031077112, job 130558753553): NEW, not a duplicate of 11347. Canton BFT
  P2P bug, same symptom as 10011 (closed fixed in Canton 3.6, DACH-NY/canton#35711). The failing wait is the second
  startAllSync (sv4Reonboard), not sv1. DAR uploads 87 (22 repeats) took only 30 s. sv4Reonboard waited from
  14:19:05.970 on `Mediator has been granted unlimited traffic` (read from its own new sequencer); that sequencer
  never left onboarding state transfer from epoch 81 (55 `Retrying block transfer request`, 53 `Not enough
  authenticated and whitelisted nodes`). Peers sv1-3 keep `localhost:27410` bound to the old sv4 sequencer id: the
  old sv4 reconnected 0.5 s after its endpoint was removed (14:17:21.395) and before its Canton stopped
  (14:17:27.286); afterwards each authentication of the new node is flipped back to the old id ~100 ms later (868
  flips each way on sv1, 14:19:07.150-14:22:47.834), and messages to the new node are dropped (`Dropping network
  message to unknown SEQ::sv4ReonboardSvReonboarding`). Teardown clean, no H cascade. 2 failures in 46 key-download
  (0) jobs since 11-09 (11347 and this, different causes).
- 11373 (frontend-wall-clock-time (2), main 08774bc, run 43072152132, job 130703023512): NEW occurrence of the AM shape
  (SV init slower than a fixed wait) on a slow runner, plus the family B setup-failure blast radius. Setup
  `waitForNodeInitialization` is sequential, 5 min per node; sv3 was checked first and got 5 min from 6.6 s after its
  start; it was still onboarding its sequencer/mediator at 18:11:23.123 (no hang). Versus the same passing setup on
  11-10 (11340 run): signature loading 57 s vs 30 s, sv1 2m48s vs 1m36s, sv2 5m30s vs 4m12s, sv4 4m57s vs 3m40s, sv3
  needed 3m48s then. Same 87 DARs per SV (22 repeats), no Daml/DAR/Canton change since 2ef0423. Later 5 suites: zero
  `has been reset`, sv1 DSO party `Not fully authorized`. NO_TEMPLATES_FOR_PACKAGE_NAME_AND_QUALIFIED_NAME: 0 in job log
  and artifacts. First setup timeout on this shard in 46 main jobs since 11-09.

Common cause with g1 (simtime 11358 11362 11368 11374): no. g1 found a wedge (mediator-add signatures lost at the
BFT 1 -> 4 step under sim time, joining SV stuck on `Mediator has been granted unlimited traffic`); 11373 is not
wedged: sv3 was past the DSO party migration and still advancing at the timeout.
Shared with g1 only: the setup-failure blast radius (no `has been reset`, later suites fail sv1 DSO party
allocation). No commit on 2026-11-11 adds DARs or changes the Canton pin up to 08774bc (`git log 2ef0423979..08774bc437
-- daml '*DarResources*.scala' apps/common/src/main/resources/dar` is empty; pin 3.7.3-snapshot.20261109). In 11373
the slowdown is uniform and shows in the CPU-only package signature loading (57 s vs 30 s in the 11340 baseline, 32 s
in 11347, 25 s in 11363 on an x-large runner), which points at the runner, not a commit. 11363 is a third mechanism
(stale BFT P2P endpoint association), not slowness.

## INDEX.tsv rows

```
11363	2026-11-11	43031077112	main 644b3ba97a (#8424)	130558753553 key-download (0)	3.7.3-snapshot.20261109.20894.0.ve6e6c252	SvReonboardingIntegrationTest second startAllSync: sv4Reonboard waits on mediator unlimited traffic 14:19:05.970-14:22:54.923 (own new BFT sequencer stuck in state transfer from epoch 81); peers sv1-3 keep localhost:27410 bound to the old sv4 sequencer id (old sv4 reconnected 14:17:21.9 after endpoint removal 14:17:21.395), 868 re-association flips on sv1 14:19:07.150-14:22:47.834, messages to the new node dropped; DAR uploads 30 s	new (AV, same symptom as 10011)	Canton BFT P2P	comment, retitle, Triaged, assignee suggestion martinflorian-da	evidence/11363-sv-reonboarding-bft-peers-keep-old-sv4-sequencer-id.md
11373	2026-11-11	43072152132	main 08774bc437 (#8442)	130703023512 frontend-wall-clock-time (2)	3.7.3-snapshot.20261109.20894.0.ve6e6c252	SvFrontendIntegrationTest setup: sequential waitForNodeInitialization checked sv3 first, 5 min from 18:06:23 to 18:11:23.123 while sv3 still onboarding (no hang); all init phases 1.3-1.8x slower than the passing 11-10 setup (signature loading 57 s vs 30 s, sv2 5m30s vs 4m12s); 87 DARs/SV, 22 repeats; 5 later suites fail sv1 DSO party Not fully authorized (no namespace reset)	new (AM shape; B setup-failure blast radius)	test budget on slow runner	comment, retitle, Triaged	evidence/11373-frontend-setup-sv3-init-over-5min-slow-runner.md
```

## known-families.md text

Index rows (AV is a proposal; renumber if another group takes it):

```
| AV | re-onboarded SV sequencer with a new id on the same P2P endpoint: peers keep the old id, newcomer never leaves state transfer (Canton BFT) | 11363 (same symptom as 10011, closed fixed in 3.6) | open, Canton |
```

AM index row, parent/main refs: `11299, 11347, 11373`.

New section:

```
## AV. Re-onboarded SV sequencer on the same P2P endpoint: peers keep the old sequencer id (Canton BFT)
- Signature: SvReonboardingIntegrationTest `Timeout while waiting for initialization of sv4Reonboard` (second
  startAllSync, test line 446); sv4Reonboard log ends in `Waiting until Mediator has been granted unlimited traffic`
  with `No traffic state found for member MED::sv4ReonboardSvReonboarding`.
- Confirm on canton-standalone-sv123-reonboarding.clog.gz: sv1-3 sequencers log `Associating P2P endpoint
  P2PUrl("http://localhost:27410") ... was previously associated with SEQ::sv4ReonboardSvReonboarding..., changing to
  SEQ::sv4SvReonboarding...` repeatedly and `Dropping network message to unknown SEQ::sv4ReonboardSvReonboarding`; on
  canton-standalone-sv4-reonboarding-new.clog.gz `Retrying block transfer request for epoch N` without `Completed
  state transfer`.
- Mechanism: after the offboard vote the SVs remove the old sv4 P2P endpoint while the old sv4 sequencer still runs;
  it reconnects inbound and is authenticated again under its old id, leaving a connection actor for
  NodeId(old id, localhost:27410). The new sv4 Canton reuses port 27410; each authentication of the new id is undone
  by the old actor's next send about 100 ms later. Same symptom as 10011 (ciperiodic sv re-onboard, closed as fixed
  in Canton 3.6 by DACH-NY/canton#35711, splice#7264); whether that fix is in 3.7.3 not verified.
- 11363 (2026-11-11, key-download (0), main 644b3ba, Canton 3.7.3-snapshot.20261109): first hit, 868 flips on sv1.
  Fix in Canton; test mitigation: stop the old sv4 nodes before the offboard takes effect, or a different P2P port
  for the re-onboarded sequencer.
```

AM occurrence line:

```
- 11373 (2026-11-11, frontend-wall-clock-time (2), main 08774bc437): SvFrontendIntegrationTest setup; the sequential
  `waitForNodeInitialization` (5 min per node) checked sv3 first, sv3 still onboarding its sequencer/mediator at the
  timeout. All init phases 1.3x-1.8x slower than the passing setup of 2026-11-10 (CPU-only package signature loading
  57 s vs 30 s), same 87 DARs/22 repeats, no Daml/DAR/Canton change: slow runner. Later 5 suites: B setup-failure
  blast radius. Confirm: `Timeout while waiting for initialization of sv<N>` in `Running setup` with the node's
  `app initialization` phases still advancing; compare `Finished loading package signatures` minus `Loading` against
  about 30 s.
```

## Lesson candidate

Lesson candidate (from 11373, once a fix lands): "A per-item timeout in a sequential wait over things that started in
parallel is not a budget". `nodes.foreach(_.waitFor(5.minutes))` gives the first node checked only 5 min from its
own start and later nodes up to N x 5 min; the effective budget depends on iteration order. Do: one overall deadline
for the group (`val deadline = now + budget; nodes.foreach(n => n.waitFor(deadline - now))`), sized from slow-runner
measurements. (11363: Lesson: none, Canton bug.)

## Suggested actions (user runs on the host, repo root)

11363 (Triaged, Canton, assignee suggestion martinflorian-da: assignee of 10011, the same mechanism):

```
gh issue comment 11363 -R DACH-NY/cn-test-failures --body-file ci-triage/rounds/2026-11-11T210621Z-inbox/comments/11363.md
gh issue edit 11363 -R DACH-NY/cn-test-failures --title 'SvReonboardingIntegrationTest: BFT peers keep the old sv4 sequencer id on the reused P2P endpoint, re-onboarded sv4 never initializes'
gh issue edit 11363 -R DACH-NY/cn-test-failures --add-assignee martinflorian-da
gh issue comment 11363 -R DACH-NY/cn-test-failures --body '@martinflorian-da Could you please have a look?'
```

11373 (Triaged, no assignee: the AM fix 11299 has none; alternative: if g1 establishes a common parent for the
setup-timeout-on-slow-runner shape, close 11373 as its duplicate with
`direnv exec . ci-triage/scripts/close_duplicates.py <parent> 11373 --comment 'Same 5 min setup wait for SV init on a slow runner, sv3 still onboarding at the timeout, later suites fail without the namespace reset'`):

```
gh issue comment 11373 -R DACH-NY/cn-test-failures --body-file ci-triage/rounds/2026-11-11T210621Z-inbox/comments/11373.md
gh issue edit 11373 -R DACH-NY/cn-test-failures --title 'SvFrontendIntegrationTest setup: sv3 init outlasts the 5 min wait on a slow runner, later suites fail without namespace reset'
```

Both:

```
direnv exec . ci-triage/scripts/set_status.py Inbox Triaged 11363 11373
```

Rank: 11363 rare (1 of 46 jobs), Canton; 11373 rare on this shard (1 of 46) but the same slow-setup theme hit 4
simtime shards today (g1), so rank it with g1.
