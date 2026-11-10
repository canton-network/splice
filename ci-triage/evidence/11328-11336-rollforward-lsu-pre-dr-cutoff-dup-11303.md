# 11328 11336 - RollForwardLsuDR: pre-DR SV onboarding misses the 300 s sequencing cutoff again (runs 42790498517, 42799831067)

DUPLICATE of 11303 (family AK). Both runs are post-merge main on 2026-11-10, Canton
3.7.3-snapshot.20261109.20894.0.ve6e6c252, job `roll-forward-lsu (0)`, tests succeeded 0, failed 1:
`RollForwardLsuDRIntegrationTest` fails in its first `startAllSync` (clue "Start nodes before DR", line 225) because
one joining SV never finishes initialization before the pre-DR synchronizer stops sequencing at suite start plus 300 s
(`lsu-repair.global-max-sequencing-time-exclusive`, test line 89). Each run shows one of the two delays already seen
under 11303:
- 11336 (sv3), the 11303 shape: the ordering topology goes from 1 to 4 sequencers at 10:45:17.498; all four first
  broadcasts of the MediatorSynchronizerState change adding `MED::sv3` are rejected with `P2P connectivity is not
  ready` at 10:45:18; sv1 and sv3 re-send at 10:45:49 (accepted, 2 signatures), the queued copies of sv2 and sv4 are
  dropped when their apps switch the participants to their own sequencers (10:45:36, 10:45:37); the SV apps
  re-propose at 10:47:12, after the bound 10:47:05.905, and the re-pushes are refused with
  `SEQUENCING_TIME_NOT_ADMISSIBLE`.
- 11328 (sv4), the 11312 shape: everything before the sequencer step runs about 60 s slower than in 11303; the
  ordering topology goes 1 to 3 at 09:42:57.936 and 3 to 4 (sv4) at 09:43:21.058, 4.6 s before the bound
  09:43:25.681; sv2 and sv3 complete 11 and 15 s before it, sv4 sends its last join step
  (`DsoRules_SetSynchronizerNodeConfig`) at 09:43:31.267 and it is refused with `SEQUENCING_TIME_NOT_ADMISSIBLE`.
Hit rate: 4 of 31 completed post-merge main runs since #8234 (11303 and 11312 on 2026-11-09, these two on
2026-11-10); 2 of 18 before the Canton bump #8381, 2 of 13 since, so the bump does not visibly change it.

- Run (11328): https://github.com/canton-network/splice/actions/runs/42790498517, main 6c0fee0505 ("add back unused
  cometbft config (#8390)"), job 129742803244 `ci / scala_test_roll_forward_lsu / roll-forward-lsu (0)`, attempt 1.
- Run (11336): https://github.com/canton-network/splice/actions/runs/42799831067, main f78a3ebdeb ("deploy_upgrade via
  gha (#8395)"), job 129774572737 `ci / scala_test_roll_forward_lsu / roll-forward-lsu (0)`, attempt 1.
- Runtime canton: 3.7.3-snapshot.20261109.20894.0.ve6e6c252 at both shas (`nix/canton-sources.json`).
- Component: test time budget (pre-DR sequencing cutoff); Canton BFT sequencer onboarding window (11255) as the delay.

Artifacts: `log/<ref>/job.log`, `log/<ref>/logs-roll-forward-lsu-0/` (artifact `logs-roll-forward-lsu-0`, created
2026-11-10T09:48:56Z for 11328 and 2026-11-10T10:51:43Z for 11336).
`L=log/<ref>/logs-roll-forward-lsu-0`, `S=$L/canton-standalone-roll-forward-lsu-before-dr.clog.gz`, `C=$L/canton.clog.gz`,
`F='s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/RollForwardLsuDRIntegrationTest\/config=[0-9a-f]+\///; s/\\n */ /g'`.

## 1. Job, failing assertion, Canton pin

Job resolution (recipe 1; this sandbox's gh 2.46 has no `--allow-escape-sequences`, the logs were fetched without it):

```
for n in 11328 11336; do RUN=$(jq -r .body log/inbox-1110/$n.json | grep -o 'actions/runs/[0-9]*' | head -1 | cut -d/ -f3); export NAME=$(jq -r .body log/inbox-1110/$n.json | sed -n 's/^Job: \(.*\) in .*/\1/p' | tr -d '\r'); echo "$n run=$RUN name=$NAME: $(gh api --paginate "repos/canton-network/splice/actions/runs/$RUN/jobs?filter=all&per_page=100" --jq '.jobs[] | select((.name == env.NAME or (.name | endswith(" / " + env.NAME))) and .conclusion == "failure") | "\(.id) attempt=\(.run_attempt) \(.name)"' | tr '\n' ';')"; done
for s in 6c0fee0505 f78a3ebdeb; do git show $s:nix/canton-sources.json | grep -m1 version; done
```
```
11328 run=42790498517 name=roll-forward-lsu (0): 129742803244 attempt=1 ci / scala_test_roll_forward_lsu / roll-forward-lsu (0);
11336 run=42799831067 name=roll-forward-lsu (0): 129774572737 attempt=1 ci / scala_test_roll_forward_lsu / roll-forward-lsu (0);
  "version": "3.7.3-snapshot.20261109.20894.0.ve6e6c252",
  "version": "3.7.3-snapshot.20261109.20894.0.ve6e6c252",
```

```
for REF in 11328 11336; do echo "== $REF"; sed -E 's/\x1b\[[0-9;]*m//g' log/$REF/job.log | grep -a -A14 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | grep -a -E 'FAILED|IllegalStateException|SvAppReference.waitForInitialization|RollForwardLsuDRIntegrationTest.scala:225' | head -4; done
```
```
== 11328
[info] - roll forward LSU DR *** FAILED ***
[info]   java.lang.IllegalStateException: Condition never became true within 5 minutes
[info]   at org.lfdecentralizedtrust.splice.console.SvAppReference.waitForInitialization(SvAppReference.scala:51)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.RollForwardLsuDRIntegrationTest.$anonfun$new$3(RollForwardLsuDRIntegrationTest.scala:225)
== 11336
[info] - roll forward LSU DR *** FAILED ***
[info]   java.lang.IllegalStateException: Condition never became true within 5 minutes
[info]   at org.lfdecentralizedtrust.splice.console.SvAppReference.waitForInitialization(SvAppReference.scala:51)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.RollForwardLsuDRIntegrationTest.$anonfun$new$3(RollForwardLsuDRIntegrationTest.scala:225)
```

The test is unchanged since 11303 (92705421fd): the bound is still suite start plus 300 s and line 225 is the first
`startAllSync` inside "Start nodes before DR":

```
T=apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/RollForwardLsuDRIntegrationTest.scala
for s in 6c0fee0505 f78a3ebdeb; do echo "== $s"; git show $s:$T | sed -n '89p;224,226p'; done
git log --format='%h %ad %s' --date=short 92705421fd..origin/main -- $T
```
```
== 6c0fee0505
  val maxSequencingTime = CantonTimestamp.now().plusSeconds(300)
      clue("Start nodes before DR") {
        startAllSync((aliceValidatorBackend +: allNodes)*)
      }
== f78a3ebdeb
  val maxSequencingTime = CantonTimestamp.now().plusSeconds(300)
      clue("Start nodes before DR") {
        startAllSync((aliceValidatorBackend +: allNodes)*)
      }
```
(no commits to the test after 92705421fd)

## 2. Cutoff, which SV did not initialize, refusals (family AK confirm, part 1)

```
for REF in 11328 11336; do L=log/$REF/logs-roll-forward-lsu-0; S=$L/canton-standalone-roll-forward-lsu-before-dr.clog.gz; echo "===== $REF"; zcat $L/canton_network_test.clog.gz | grep -a -m1 'Running clue: Starting external Canton process roll-forward-lsu-before-dr' | grep -a -oE 'sv1StandaloneSequencer.parameters.lsu-repair.global-max-sequencing-time-exclusive=[^,)]*'; zcat $L/canton_network_test.clog.gz | grep -a -E "Starting test suite|Running clue: Start nodes before DR|Timeout while waiting for initialization|Test failed: " | sed -E "$F" | cut -c1-200; zcat $L/canton_network_test.clog.gz | grep -a -E '"message":"(sv[1-4]) app initialization: (Initialization complete|JoiningNodeInitializer joining Dso with key started)' | sed -E "$F" | cut -c1-110; printf 'SEQUENCING_TIME_NOT_ADMISSIBLE: '; zcat $S | grep -a -c 'SEQUENCING_TIME_NOT_ADMISSIBLE'; zcat $S | grep -a -m1 'SEQUENCING_TIME_NOT_ADMISSIBLE' | sed -E 's/\{"@timestamp":"([^"]+)","message":"(.{190}).*/\1 \2.../'; printf 'P2P connectivity is not ready: '; zcat $S | grep -a -c 'P2P connectivity is not ready'; printf 'Not requeuing topology transactions: '; zcat $L/canton.clog.gz | grep -a -c 'Not requeuing topology transactions'; done
```
```
===== 11328
sv1StandaloneSequencer.parameters.lsu-repair.global-max-sequencing-time-exclusive=2026-11-10T09:43:25.680997Z
2026-11-10T09:38:25.711Z Starting test suite 'RollForwardLsuDRIntegrationTest'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-10T09:38:34.597Z Running clue: Start nodes before DR",[o.l.s.i.t.RollForwardLsuDRIntegrationTest:RollForwardLsuDRIntegrationTest] DEBUG
2026-11-10T09:48:15.130Z Timeout while waiting for initialization of sv4",[o.l.s.c.SvAppBackendReference:app=sv4] ERROR
2026-11-10T09:48:16.006Z Test failed: 'RollForwardLsuDRIntegrationTest/roll forward LSU DR', message: Condition never became true within 5 minutes, location: SeeStackDepthException",[c.d.c.LogReporter
2026-11-10T09:39:19.444Z sv4 app initialization: JoiningNodeInitializer joining Dso with key started",[o.l.s.s
2026-11-10T09:39:19.444Z sv3 app initialization: JoiningNodeInitializer joining Dso with key started",[o.l.s.s
2026-11-10T09:39:19.444Z sv2 app initialization: JoiningNodeInitializer joining Dso with key started",[o.l.s.s
2026-11-10T09:40:22.174Z sv1 app initialization: Initialization complete, running on version 0.10.2-ci-snapsho
2026-11-10T09:43:10.421Z sv3 app initialization: Initialization complete, running on version 0.10.2-ci-snapsho
2026-11-10T09:43:14.841Z sv2 app initialization: Initialization complete, running on version 0.10.2-ci-snapsho
SEQUENCING_TIME_NOT_ADMISSIBLE: 1560
2026-11-10T09:43:27.846Z SEQUENCING_TIME_NOT_ADMISSIBLE(9,4f4431ec): The sequencing time [2026-11-10T09:43:27.645422Z] is not admissible (sequencing time is at or after the upper bound (2026-11-10T09:43:25.680997Z))...
P2P connectivity is not ready: 0
Not requeuing topology transactions: 3
===== 11336
sv1StandaloneSequencer.parameters.lsu-repair.global-max-sequencing-time-exclusive=2026-11-10T10:47:05.905121Z
2026-11-10T10:42:05.936Z Starting test suite 'RollForwardLsuDRIntegrationTest'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-10T10:42:13.359Z Running clue: Start nodes before DR",[o.l.s.i.t.RollForwardLsuDRIntegrationTest:RollForwardLsuDRIntegrationTest] DEBUG
2026-11-10T10:50:39.675Z Timeout while waiting for initialization of sv3",[o.l.s.c.SvAppBackendReference:app=sv3] ERROR
2026-11-10T10:50:45.421Z Test failed: 'RollForwardLsuDRIntegrationTest/roll forward LSU DR', message: Condition never became true within 5 minutes, location: SeeStackDepthException",[c.d.c.LogReporter
2026-11-10T10:42:45.045Z sv3 app initialization: JoiningNodeInitializer joining Dso with key started",[o.l.s.s
2026-11-10T10:42:45.045Z sv4 app initialization: JoiningNodeInitializer joining Dso with key started",[o.l.s.s
2026-11-10T10:42:45.045Z sv2 app initialization: JoiningNodeInitializer joining Dso with key started",[o.l.s.s
2026-11-10T10:43:43.341Z sv1 app initialization: Initialization complete, running on version 0.10.2-ci-snapsho
2026-11-10T10:45:37.488Z sv2 app initialization: Initialization complete, running on version 0.10.2-ci-snapsho
2026-11-10T10:45:38.086Z sv4 app initialization: Initialization complete, running on version 0.10.2-ci-snapsho
SEQUENCING_TIME_NOT_ADMISSIBLE: 1096
2026-11-10T10:47:06.267Z SEQUENCING_TIME_NOT_ADMISSIBLE(9,d41159d9): The sequencing time [2026-11-10T10:47:06.114664Z] is not admissible (sequencing time is at or after the upper bound (2026-11-10T10:47:05.905121Z))...
P2P connectivity is not ready: 24
Not requeuing topology transactions: 3
```

In both runs the bound is suite start plus 300 s (09:38:25.711 to 09:43:25.681, 10:42:05.936 to 10:47:05.905), the
first refusal follows it within 2 s, and exactly one joining SV never completes: sv4 in 11328, sv3 in 11336.

## 3. Ordering topology steps on the pre-DR synchronizer (sv1's epochs)

Epochs whose ordering topology size or blacklist changes:

```
for REF in 11328 11336; do S=log/$REF/logs-roll-forward-lsu-0/canton-standalone-roll-forward-lsu-before-dr.clog.gz; echo "== $REF"; zcat $S | grep -a 'New epoch' | grep -a 'sequencer=sv1Standalone' | awk '{ts=substr($0,16,24); match($0,/New epoch [0-9]+/); ep=substr($0,RSTART+10,RLENGTH-10); match($0,/size = [0-9]+/); sz=substr($0,RSTART+7,RLENGTH-7); match($0,/blacklisted nodes = List\([^)]*\)/); bl=substr($0,RSTART+20,RLENGTH-20); gsub(/::1220[0-9a-f]+/,"",bl); match($0,/activationTime = [^,]*/); act=substr($0,RSTART+17,RLENGTH-17); if(sz!=p || bl!="List()" || pbl!="List()") print ts, "epoch", ep, "size", sz, "act", act, "bl", bl; p=sz; pbl=bl}' | head -20; done
```
```
== 11328
2026-11-10T09:39:37.566Z epoch 0 size 1 act 0001-01-01T00:00:00.250002Z bl List()
2026-11-10T09:42:58.340Z epoch 33 size 3 act 2026-11-10T09:42:57.936410Z bl List()
2026-11-10T09:43:21.413Z epoch 37 size 4 act 2026-11-10T09:43:21.057933Z bl List()
2026-11-10T09:43:26.781Z epoch 38 size 4 act 2026-11-10T09:43:21.057933Z bl List(SEQ::sv4)
2026-11-10T09:43:33.790Z epoch 39 size 4 act 2026-11-10T09:43:21.057933Z bl List(SEQ::sv4)
2026-11-10T09:43:40.920Z epoch 40 size 4 act 2026-11-10T09:43:21.057933Z bl List(SEQ::sv4)
2026-11-10T09:43:47.970Z epoch 41 size 4 act 2026-11-10T09:43:21.057933Z bl List()
== 11336
2026-11-10T10:43:07.012Z epoch 0 size 1 act 0001-01-01T00:00:00.250002Z bl List()
2026-11-10T10:45:17.941Z epoch 32 size 4 act 2026-11-10T10:45:17.498156Z bl List()
2026-11-10T10:45:34.489Z epoch 33 size 4 act 2026-11-10T10:45:17.498156Z bl List(SEQ::sv4)
2026-11-10T10:45:40.853Z epoch 34 size 4 act 2026-11-10T10:45:17.498156Z bl List(SEQ::sv4)
2026-11-10T10:45:47.984Z epoch 35 size 4 act 2026-11-10T10:45:17.498156Z bl List(SEQ::sv4)
2026-11-10T10:45:55.861Z epoch 36 size 4 act 2026-11-10T10:45:17.498156Z bl List()
```

11328: 1 to 3 at 09:42:57.936 (4 min 32 s after suite start), then sv4 alone at 09:43:21.058, 4.6 s before the
bound. 11336: 1 to 4 in one step at 10:45:17.498 (3 min 12 s after suite start), 108 s before the bound.

## 4. Time to the sequencer step, compared with 11303 and 11312

```
for d in log/11303/logs-roll-forward-lsu-1 log/11312/logs-roll-forward-lsu-0 log/11328/logs-roll-forward-lsu-0 log/11336/logs-roll-forward-lsu-0; do [ -d $d ] || continue; echo "== $d"; zcat $d/canton_network_test.clog.gz | grep -a -E "Starting test suite 'RollForwardLsuDR|sv1 app initialization: Initialization complete|JoiningNodeInitializer joining Dso with key started|Onboarded sequencer SEQ::sv[2-4]|sv[2-4] app initialization: Initialization complete" | sed -E 's/\{"@timestamp":"[0-9-]+T([^"]+)","message":"([^"]{0,70}).*/\1 \2/' | awk '!seen[substr($0,14)]++'; done
```
```
== log/11303/logs-roll-forward-lsu-1
15:18:15.648Z Starting test suite 'RollForwardLsuDRIntegrationTest'...
15:19:05.781Z sv3 app initialization: JoiningNodeInitializer joining Dso with key st
15:19:05.781Z sv4 app initialization: JoiningNodeInitializer joining Dso with key st
15:19:05.781Z sv2 app initialization: JoiningNodeInitializer joining Dso with key st
15:20:09.818Z sv1 app initialization: Initialization complete, running on version 0.
15:21:49.162Z Onboarded sequencer SEQ::sv2::1220538104bf...
15:21:49.481Z Onboarded sequencer SEQ::sv3::12208a62e011...
15:21:49.965Z Onboarded sequencer SEQ::sv4::122041d5446a...
15:22:08.773Z sv4 app initialization: Initialization complete, running on version 0.
15:22:09.436Z sv3 app initialization: Initialization complete, running on version 0.
== log/11312/logs-roll-forward-lsu-0
17:00:30.767Z Starting test suite 'RollForwardLsuDRIntegrationTest'...
17:01:19.856Z sv2 app initialization: JoiningNodeInitializer joining Dso with key st
17:01:19.856Z sv3 app initialization: JoiningNodeInitializer joining Dso with key st
17:01:19.856Z sv4 app initialization: JoiningNodeInitializer joining Dso with key st
17:02:26.570Z sv1 app initialization: Initialization complete, running on version 0.
17:04:50.668Z Onboarded sequencer SEQ::sv4::12204b722fc4...
17:05:12.054Z Onboarded sequencer SEQ::sv3::12209285f20f...
17:05:15.711Z Onboarded sequencer SEQ::sv2::122011f16c9d...
== log/11328/logs-roll-forward-lsu-0
09:38:25.711Z Starting test suite 'RollForwardLsuDRIntegrationTest'...
09:39:19.444Z sv4 app initialization: JoiningNodeInitializer joining Dso with key st
09:39:19.444Z sv3 app initialization: JoiningNodeInitializer joining Dso with key st
09:39:19.444Z sv2 app initialization: JoiningNodeInitializer joining Dso with key st
09:40:22.174Z sv1 app initialization: Initialization complete, running on version 0.
09:42:59.431Z Onboarded sequencer SEQ::sv2::122065afb276...
09:42:59.942Z Onboarded sequencer SEQ::sv3::1220894ddc45...
09:43:10.421Z sv3 app initialization: Initialization complete, running on version 0.
09:43:14.841Z sv2 app initialization: Initialization complete, running on version 0.
09:43:21.802Z Onboarded sequencer SEQ::sv4::122009ac24cd...
== log/11336/logs-roll-forward-lsu-0
10:42:05.936Z Starting test suite 'RollForwardLsuDRIntegrationTest'...
10:42:45.045Z sv3 app initialization: JoiningNodeInitializer joining Dso with key st
10:42:45.045Z sv4 app initialization: JoiningNodeInitializer joining Dso with key st
10:42:45.045Z sv2 app initialization: JoiningNodeInitializer joining Dso with key st
10:43:43.341Z sv1 app initialization: Initialization complete, running on version 0.
10:45:20.477Z Onboarded sequencer SEQ::sv2::122043507cc7...
10:45:20.716Z Onboarded sequencer SEQ::sv3::122083b7258f...
10:45:21.828Z Onboarded sequencer SEQ::sv4::1220bb58c75a...
10:45:37.488Z sv2 app initialization: Initialization complete, running on version 0.
10:45:38.086Z sv4 app initialization: Initialization complete, running on version 0.
```

Suite start to joiners' sequencers onboarded: 11303 3 min 34 s, 11312 4 min 20 s to 4 min 45 s, 11328 4 min 34 s to
4 min 56 s, 11336 3 min 15 s. In 11328 the extra time is spread over the init steps before the sequencer step, not one
hang (sv3's init steps with a gap over 8 s, 11303 vs 11328):

```
for d in log/11303/logs-roll-forward-lsu-1 log/11328/logs-roll-forward-lsu-0; do echo "== $d"; zcat $d/canton_network_test.clog.gz | grep -a 'SV=sv3"' | grep -a -E '"message":"(Waiting until|Success:|Attempting to get|Got )' | sed -E 's/\{"@timestamp":"[0-9-]+T([^"]+)","message":"([^"]{0,85}).*/\1 \2/' | awk '{split($1,t,":"); s=t[1]*3600+t[2]*60+t[3]; if(NR>1 && s-p>8) printf "  gap %.1fs before: %s\n", s-p, $0; p=s}' | head -12; done
```
```
== log/11303/logs-roll-forward-lsu-1
  gap 31.3s before: 15:19:05.720Z Attempting to get 'Participant ID'
  gap 20.8s before: 15:19:26.662Z Success: participant registered Synchronizer 'global', result is RegisteredSynchroniz
  gap 42.9s before: 15:20:09.587Z Got 'DSO party ID from sponsoring SV': DSO-8d8ae105-8d8ae105::12202de5a37d...
  gap 62.2s before: 15:21:14.033Z Success: dars List(5838793a7f11fbc16159065094823fb662120184ede48922dff092958dae6857, 
  gap 11.2s before: 15:21:38.150Z Waiting until the DsoRules and AmuletRules are visible
== log/11328/logs-roll-forward-lsu-0
  gap 37.4s before: 09:39:19.389Z Attempting to get 'Participant ID'
  gap 20.6s before: 09:39:40.096Z Success: participant registered Synchronizer 'global', result is RegisteredSynchroniz
  gap 41.9s before: 09:40:22.044Z Got 'DSO party ID from sponsoring SV': DSO-86924879-86924879::12206c937d4c...
  gap 10.4s before: 09:40:32.444Z Success: Party digital-asset-eng-3-86924879::12202869ca66... is allocated on PAR::sv3
  gap 74.7s before: 09:41:47.104Z Success: dars List(5838793a7f11fbc16159065094823fb662120184ede48922dff092958dae6857, 
  gap 8.5s before: 09:42:07.399Z Attempting to get 'SvOnboardingConfirmed contract for digital-asset-eng-3-86924879::1
  gap 20.6s before: 09:42:29.123Z Waiting until the DsoRules and AmuletRules are visible
  gap 13.8s before: 09:42:54.744Z Waiting until Sequencer is added to the topology state for MediatorToOnboard(synchron
  gap 8.1s before: 09:43:29.321Z Success: Extra traffic limit for MED::sv4::12205ac887ac... set to 9223372036854775807
```

## 5. 11328: sv4's last join step is sent 5.6 s after the bound and refused

sv4's init steps after its mediator is onboarded:

```
L=log/11328/logs-roll-forward-lsu-0; zcat $L/canton_network_test.clog.gz | grep -a 'SV=sv4"' | grep -a -E 'app initialization: .*(started|finished|failed)|Waiting until|Success:' | sed -E "$F" | cut -c1-170 | tail -12
```
```
2026-11-10T09:43:24.736Z Waiting until Mediator has been granted unlimited traffic",[o.l.s.s.LocalSynchronizerNode:SV=sv4] INFO
2026-11-10T09:43:30.403Z Success: Mediator has been granted unlimited traffic",[o.l.s.s.LocalSynchronizerNode:SV=sv4] INFO
2026-11-10T09:43:30.409Z Waiting until Mediator onboard topology transaction is active",[o.l.s.s.LocalSynchronizerNode:SV=sv4] INFO
2026-11-10T09:43:30.409Z Success: Mediator onboard topology transaction is active",[o.l.s.s.LocalSynchronizerNode:SV=sv4] INFO
2026-11-10T09:43:31.223Z Waiting until mediator observes itself as onboarded",[o.l.s.s.LocalSynchronizerNode:SV=sv4] INFO
2026-11-10T09:43:31.230Z Success: mediator observes itself as onboarded",[o.l.s.s.LocalSynchronizerNode:SV=sv4] INFO
2026-11-10T09:43:31.282Z Success: dars List(866ea53bea2afc099c560ad4c386a1736859841b17c422836f133d605ee51616, 2b680f34f11be43d65a2dde723f4c9595a0983f63a0bb3455f03bb16c5d9
2026-11-10T09:43:31.304Z Success: dars List() are removed from global-domain::12206c937d4c...",[o.l.s.e.ParticipantAdminConnection:SV=sv4] INFO
2026-11-10T09:48:41.083Z sv4 app initialization: JoiningNodeInitializer joining Dso with key failed",[o.l.s.s.SvApp:SV=sv4] INFO
2026-11-10T09:48:41.084Z sv4 app initialization: Initialize node failed",[o.l.s.s.SvApp:SV=sv4] INFO
2026-11-10T09:48:41.084Z sv4 app initialization: Initialize app failed",[o.l.s.s.SvApp:SV=sv4] INFO
2026-11-10T09:48:41.085Z sv4 app initialization: Ignoring initialization failure, we are actually shutting down. Message was: sv4 app initialization: Initialize app faile
```

The ledger submissions sv4 sends after 09:43:20 (choice per submission) and the retry of the config step:

```
zcat $L/canton_network_test.clog.gz | grep -a 'SV=sv4"' | grep -a 'CommandService/SubmitAndWait' | grep -a 'sending request' | awk -F'"' '$4>="2026-11-10T09:43:20"' | grep -a -oE '^\{"@timestamp":"[^"]+|DsoRules_[A-Za-z]+' | paste -sd' ' | sed 's/{"@timestamp":"/\n/g'
zcat $L/canton_network_test.clog.gz | grep -a 'SV=sv4"' | grep -a -E "The operation 'setting domain config for" | sed -E "$F" | cut -c1-200 | head -1
```
```

2026-11-10T09:43:31.265Z DsoRules_SubmitStatusReport 
2026-11-10T09:43:31.267Z DsoRules_SetSynchronizerNodeConfig 
2026-11-10T09:45:31.475Z DsoRules_SetSynchronizerNodeConfig 
2026-11-10T09:47:31.816Z DsoRules_SetSynchronizerNodeConfig
2026-11-10T09:45:31.267Z The operation 'setting domain config for digital-asset-eng-4-86924879::1220aab855cf...' failed with a retryable error (full stack trace omitted): DEADLINE_EXCEEDED: CallOption
```

The 09:43:31.267 submission (tid 79d4c2d7...) reaches the pre-DR sequencer as a send that is refused:

```
S=$L/canton-standalone-roll-forward-lsu-before-dr.clog.gz
zcat $S | grep -a 'SEQUENCING_TIME_NOT_ADMISSIBLE' | grep -a 'PAR::sv4' | awk -F'"' '$4>="2026-11-10T09:43:31" && $4<"2026-11-10T09:43:34"' | head -1 | sed -E 's/1220[0-9a-f]{60}/../g; s/\{"@timestamp":"([^"]+)","message":"(.{330}).*/\1 \2.../'
```
```
2026-11-10T09:43:31.452Z SEQUENCING_TIME_NOT_ADMISSIBLE(9,79d4c2d7): The sequencing time [2026-11-10T09:43:31.382049Z] is not admissible (sequencing time is at or after the upper bound (2026-11-10T09:43:25.680997Z)) for Send(2026-11-10T09:43:31.382049Z,SignedContent(SubmissionRequest(PAR::sv4::1220aab855cf...,989517ba-b724-45b6-b4b6-c4ed35c05a4d,Batch(\...
```

(The error id `79d4c2d7` is the trace id of the submission request `tid:79d4c2d71acf6bc57b7ae436679c3ea1` sent at
09:43:31.267.) This is the same last step as in 11312 (`DsoRules_SetSynchronizerNodeConfig`, family AK confirm).

## 6. 11336: the MED::sv3 change is lost at the 1 to 4 step (the 11303 shape)

sv3 waits for its mediator's unlimited traffic from 10:45:23 to the end; the grant needs `MED::sv3` to be an active
mediator:

```
L=log/11336/logs-roll-forward-lsu-0; zcat $L/canton_network_test.clog.gz | grep -a 'SV=sv3"' | grep -a "No traffic state found for member MED::sv3" | awk -F'"' 'NR==1{f=$4} {l=$4; n++} END{print n" retries, first "f", last "l}'
```
```
180 retries, first 2026-11-10T10:45:23.735Z, last 2026-11-10T10:50:43.030Z
```

All four SV apps propose the MediatorSynchronizerState change adding `MED::sv3` at 10:45:18, 0.6 to 1.5 s after the
1 to 4 activation (10:45:17.498156); each first send is rejected by the pre-DR sv1 sequencer:

```
zcat $L/canton_network_test.clog.gz | grep -a 'Submitted proposal MediatorSynchronizerState' | grep -a 'for Add mediator MED::sv3' | sed -E "$F" | sed -E 's/(threshold = [0-9]), active = .*\[([^]]*SV=sv[1-4])\].*/\1 ... \2/' | cut -c1-200 | head
S=$L/canton-standalone-roll-forward-lsu-before-dr.clog.gz
zcat $S | grep -a -m1 'Rejecting submission request by Some(PAR::sv' | sed -E 's/1220[0-9a-f]{60}/../g; s/\{"@timestamp":"([^"]+)","message":"(.{260}).*sequencer=([a-zA-Z0-9]+).*/\1 \2... [\3]/'
zcat $S | grep -a 'Rejecting submission request by Some(PAR::sv' | sed -E "$F" | cut -c1-200 | head -5 | awk '{print substr($0,1,24), substr($0,25,80)}'
```
```
2026-11-10T10:45:18.141Z Submitted proposal MediatorSynchronizerState( synchronizerId = global-domain::1220690314e5..., group = 0, threshold = 2 ... o.l.s.e.ParticipantAdminConnection:SV=sv4
2026-11-10T10:45:18.713Z Submitted proposal MediatorSynchronizerState( synchronizerId = global-domain::1220690314e5..., group = 0, threshold = 2 ... o.l.s.e.ParticipantAdminConnection:SV=sv1
2026-11-10T10:45:18.876Z Submitted proposal MediatorSynchronizerState( synchronizerId = global-domain::1220690314e5..., group = 0, threshold = 2 ... o.l.s.e.ParticipantAdminConnection:SV=sv3
2026-11-10T10:45:18.972Z Submitted proposal MediatorSynchronizerState( synchronizerId = global-domain::1220690314e5..., group = 0, threshold = 2 ... o.l.s.e.ParticipantAdminConnection:SV=sv2
2026-11-10T10:47:12.825Z Submitted proposal MediatorSynchronizerState( synchronizerId = global-domain::1220690314e5..., group = 0, threshold = 2 ... o.l.s.e.ParticipantAdminConnection:SV=sv2
2026-11-10T10:47:14.381Z Submitted proposal MediatorSynchronizerState( synchronizerId = global-domain::1220690314e5..., group = 0, threshold = 2 ... o.l.s.e.ParticipantAdminConnection:SV=sv4
2026-11-10T10:49:12.239Z Submitted proposal MediatorSynchronizerState( synchronizerId = global-domain::1220690314e5..., group = 0, threshold = 2 ... o.l.s.e.ParticipantAdminConnection:SV=sv2
2026-11-10T10:49:19.453Z Submitted proposal MediatorSynchronizerState( synchronizerId = global-domain::1220690314e5..., group = 0, threshold = 2 ... o.l.s.e.ParticipantAdminConnection:SV=sv4
2026-11-10T10:51:17.689Z Submitted proposal MediatorSynchronizerState( synchronizerId = global-domain::1220690314e5..., group = 0, threshold = 2 ... o.l.s.e.ParticipantAdminConnection:SV=sv4
2026-11-10T10:45:18.161Z Rejecting submission request by Some(PAR::sv4::12202f7b44a9...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)","logger_name":"c.d.c.s.s.s.GrpcSequence... [sv1StandaloneSequencer]
2026-11-10T10:45:18.161Z  Rejecting submission request by Some(PAR::sv4::12202f7b44a9...) with SequencerD
2026-11-10T10:45:18.724Z  Rejecting submission request by Some(PAR::sv1::12206c835f39...) with SequencerD
2026-11-10T10:45:18.887Z  Rejecting submission request by Some(PAR::sv3::1220431f45b0...) with SequencerD
2026-11-10T10:45:18.985Z  Rejecting submission request by Some(PAR::sv2::12204f07e587...) with SequencerD
2026-11-10T10:45:19.067Z  Rejecting submission request by Some(PAR::sv2::12204f07e587...) with SequencerD
```

Per participant (tx d16d16b13cca): the four first pushes fail; sv1 and sv3 re-push at 10:45:49 and are accepted (two
signatures, one short of the threshold of 3 of 4 owners, as in 11303); the queued copies of sv2 and sv4 are dropped
when their participants close the connection to switch to their own sequencers; nobody pushes again before the bound:

```
C=$L/canton.clog.gz
G='s/1220[0-9a-f]{60}/../g; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name":"[^"]*participant=(sv[1-4]Participant)[^"]*".*"level":"([A-Z]+)".*/",[\1] \2/'
zcat $C | grep -a -E 'participant=sv[1-4]Participant' | grep -a -E 'Attempting to push 1 topology transactions|Synchronizer .global. responded the following for the given topology transa|Not requeuing topology transactions|Requeuing and stopping' | awk -F'"' '$4>="2026-11-10T10:45:17" && $4<"2026-11-10T10:47:20"' | sed -E "$G" | cut -c1-220
zcat $L/canton_network_test.clog.gz | grep -a -E 'SV=sv[24]"' | grep -a 'Updating to new synchronizer connection config' | awk -F'"' '$4>="2026-11-10T10:45:00" && $4<"2026-11-10T10:46"' | sed -E 's/\{"@timestamp":"([^"]+)","message":"(.{80}).*SV=(sv[24])".*/\1 [\3] \2.../'
```
```
2026-11-10T10:45:18.140Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:d16d16b13cca...))",[sv4Participant] DEBUG
2026-11-10T10:45:18.712Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:d16d16b13cca...))",[sv1Participant] DEBUG
2026-11-10T10:45:18.876Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:d16d16b13cca...))",[sv3Participant] DEBUG
2026-11-10T10:45:18.972Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:d16d16b13cca...))",[sv2Participant] DEBUG
2026-11-10T10:45:36.860Z Requeuing and stopping due to closing/synchronizer-disconnect",[sv2Participant] INFO
2026-11-10T10:45:36.860Z Not requeuing topology transactions due to an ongoing shutdown.",[sv2Participant] INFO
2026-11-10T10:45:37.586Z Requeuing and stopping due to closing/synchronizer-disconnect",[sv4Participant] INFO
2026-11-10T10:45:37.586Z Not requeuing topology transactions due to an ongoing shutdown.",[sv4Participant] INFO
2026-11-10T10:45:39.111Z Synchronizer 'global' responded the following for the given topology transactions: List((Failed,TxHash(SHA-256:d16d16b13cca...)))",[sv1Participant] DEBUG
2026-11-10T10:45:39.442Z Synchronizer 'global' responded the following for the given topology transactions: List((Failed,TxHash(SHA-256:d16d16b13cca...)))",[sv3Participant] DEBUG
2026-11-10T10:45:49.115Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:d16d16b13cca...))",[sv1Participant] DEBUG
2026-11-10T10:45:49.445Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:d16d16b13cca...))",[sv3Participant] DEBUG
2026-11-10T10:45:49.951Z Synchronizer 'global' responded the following for the given topology transactions: List((Accepted,TxHash(SHA-256:d16d16b13cca...)))",[sv1Participant] DEBUG
2026-11-10T10:45:50.142Z Synchronizer 'global' responded the following for the given topology transactions: List((Accepted,TxHash(SHA-256:d16d16b13cca...)))",[sv3Participant] DEBUG
2026-11-10T10:47:12.824Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:d16d16b13cca...))",[sv2Participant] DEBUG
2026-11-10T10:47:14.381Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:d16d16b13cca...))",[sv4Participant] DEBUG
2026-11-10T10:45:36.685Z [sv2] Updating to new synchronizer connection config for synchronizer Synchronizer 'gl...
2026-11-10T10:45:37.492Z [sv4] Updating to new synchronizer connection config for synchronizer Synchronizer 'gl...
```

The SV apps re-propose after their `check established` wait gives up, after the bound; the re-pushes of sv2 and sv4
go to their own sequencers and are refused:

```
zcat $L/canton_network_test.clog.gz | grep -a -E "The operation 'Add mediator MED::sv3::[0-9a-f]+\.\.\.' failed with a retryable error" | sed -E "$F" | sed -E 's/(failed with a retryable error).*\[/\1 ... [/' | head -3
zcat $C | grep -a 'participant=sv[24]Participant' | grep -a -E 'Sending message ID .* to sequencer' | awk -F'"' '$4>="2026-11-10T10:47:12" && $4<"2026-11-10T10:47:15"' | sed -E 's/1220[0-9a-f]{60}/../g; s/\{"@timestamp":"([^"]+)","message":"([^"]*)".*participant=(sv[24])Participant.*/\1 [\3] \2/'
for id in 7060640c-ad4f-46e5-b41f-7191ce39a9af 1d9933ab-0b1e-49c1-b872-eeedbd3c6bfc; do zcat $S | grep -a "$id" | grep -a -m1 'SEQUENCING_TIME_NOT_ADMISSIBLE' | sed -E 's/1220[0-9a-f]{60}/../g; s/\{"@timestamp":"([^"]+)","message":"(.{200}).*/\1 \2.../'; done
```
```
2026-11-10T10:47:12.581Z The operation 'Add mediator MED::sv3::122000b8b132...' failed with a retryable error ... [o.l.s.e.ParticipantAdminConnection:SV=sv2] INFO
2026-11-10T10:47:14.140Z The operation 'Add mediator MED::sv3::122000b8b132...' failed with a retryable error ... [o.l.s.e.ParticipantAdminConnection:SV=sv4] INFO
2026-11-10T10:47:18.391Z The operation 'Add mediator MED::sv3::122000b8b132...' failed with a retryable error ... [o.l.s.e.ParticipantAdminConnection:SV=sv3] INFO
2026-11-10T10:47:12.827Z [sv2] Sending message ID 7060640c-ad4f-46e5-b41f-7191ce39a9af to sequencer SEQ::sv2::122043507cc7...
2026-11-10T10:47:14.384Z [sv4] Sending message ID 1d9933ab-0b1e-49c1-b872-eeedbd3c6bfc to sequencer SEQ::sv4::1220bb58c75a...
2026-11-10T10:47:13.105Z SEQUENCING_TIME_NOT_ADMISSIBLE(9,ae6bb3bc): The sequencing time [2026-11-10T10:47:13.014518Z] is not admissible (sequencing time is at or after the upper bound (2026-11-10T10:47:05.905121Z)) for Send(...
2026-11-10T10:47:14.496Z SEQUENCING_TIME_NOT_ADMISSIBLE(9,67fbf80e): The sequencing time [2026-11-10T10:47:14.347888Z] is not admissible (sequencing time is at or after the upper bound (2026-11-10T10:47:05.905121Z)) for Send(...
```

## 7. Hit rate on main

`roll-forward-lsu` job conclusions of every post-merge main run created since 2026-11-06 (latest attempt), then the
runs since #8234 (b77153c658, 2026-11-06 13:34:52 UTC) and since the Canton bump #8381 (885cd84668, run 42687934161
created 2026-11-09T19:55:11Z):

```
mkdir -p log/g5; gh api --paginate "repos/canton-network/splice/actions/runs?branch=main&event=push&created=>=2026-11-06T00:00:00Z&per_page=100" --jq '.workflow_runs[] | select(.name|test("post-merge")) | "\(.id) \(.head_sha[0:10]) \(.conclusion) \(.created_at)"' > log/g5/runs.txt
while read id sha concl ts; do gh api --paginate "repos/canton-network/splice/actions/runs/$id/jobs?filter=latest&per_page=100" --jq '.jobs[] | select(.name|test("roll-forward-lsu")) | "\(.name|sub(".*/ ";"")) \(.conclusion)"' | while read j; do echo "$ts $id $sha $j"; done; done < log/g5/runs.txt > log/g5/rflsu.txt
git log -1 --format='%h %ci' b77153c658
grep -v ' success$' log/g5/rflsu.txt
awk '$1>="2026-11-06T13:34:52Z" && $NF!="null"{r[$2]=1; if($NF=="failure") f[$2]=1} END{n=0;m=0; for(k in r){n++; if(k in f) m++}; print m" of "n" completed post-merge main runs since #8234 with a roll-forward-lsu failure"}' log/g5/rflsu.txt
awk '$1>="2026-11-09T19:55:11Z" && $NF!="null"{r[$2]=1; if($NF=="failure") f[$2]=1} END{n=0;m=0; for(k in r){n++; if(k in f) m++}; print m" of "n" since the Canton bump #8381 (885cd84668, run 42687934161)"}' log/g5/rflsu.txt
```
```
b77153c658 2026-11-06 14:34:52 +0100
2026-11-10T13:17:08Z 42823122392 1844b95301 roll-forward-lsu (1) null
2026-11-10T13:17:08Z 42823122392 1844b95301 roll-forward-lsu (0) null
2026-11-10T10:31:13Z 42799831067 f78a3ebdeb roll-forward-lsu (0) failure
2026-11-10T09:24:50Z 42790498517 6c0fee0505 roll-forward-lsu (0) failure
2026-11-09T16:47:50Z 42658360509 91715b25c0 roll-forward-lsu (0) failure
2026-11-09T14:53:49Z 42639148146 92705421fd roll-forward-lsu (1) failure
4 of 31 completed post-merge main runs since #8234 with a roll-forward-lsu failure
2 of 13 since the Canton bump #8381 (885cd84668, run 42687934161)
```

The four failures are 11303, 11312, 11328 and 11336 (run ids above match the issues). Before the Canton bump: 2 of
18; since: 2 of 13. Too few runs to tell a rate change; the bump is not needed to explain either failure.

## 8. Duplicate check

```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue roll-forward-lsu created:>=2026-11-08' --jq '.items[] | "#\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:16]) \(.title[0:100])"'
gh api repos/DACH-NY/cn-test-failures/issues/11303 --jq '"assignees=\([.assignees[].login]|join(",")) state=\(.state) comments=\(.comments)"'
```
```
#11336 open/- 2026-11-10T10:51 GHA Run 42799831067 : job roll-forward-lsu (0) in scala_test Failed :fire:
#11328 open/- 2026-11-10T09:49 GHA Run 42790498517 : job roll-forward-lsu (0) in scala_test Failed :fire:
#11303 open/- 2026-11-09T15:28 RollForwardLsuDRIntegrationTest: pre-DR SV onboarding misses the 300 s sequencing cutoff since DA BF
#11312 closed/duplicate 2026-11-09T17:11 GHA Run 42658360509 : job roll-forward-lsu (0) in scala_test Failed :fire:
```
```
assignees= state=open comments=1
```

11303 is the parent (open, unassigned, one comment: the 11303 analysis); 11312 its closed duplicate. Older
`roll-forward-lsu` issues were other failures (11303 packet section 9). Family AK confirm holds for both refs:
`SEQUENCING_TIME_NOT_ADMISSIBLE ... upper bound (<suite start + 300 s>)` refusals (section 2) and the joining SV's last
init submission sent after that bound (11328: `DsoRules_SetSynchronizerNodeConfig` 09:43:31.267, section 5; 11336:
MediatorSynchronizerState for `MED::sv3` re-pushed 10:47:12 and 10:47:14, section 6).

## Verdict

Duplicate of 11303 (family AK), both refs. Test budget, not a new Canton or splice bug: the pre-DR synchronizer of
`RollForwardLsuDRIntegrationTest` stops sequencing at suite start plus 300 s, and since #8234 the joiners' DA BFT
onboarding (with its 11255 delays: lost mediator broadcasts in 11336, slow init plus a separate 3 to 4 step in 11328)
must finish inside it. Fix in the test, as stated on 11303: more headroom than 300 s on line 89 (the test sleeps
until `maxSequencingTime` + 5 s, so the cost is the same extra test time), or start the joining SVs one at a time
before DR. Unassigned. Not verified: the 11336 batches of the first sends of sv1/sv3 vs the 11303 batch-loss detail
(not needed: they were rejected outright, section 6); the reason the 11328 init phases were about 60 s slower (spread
over all steps, section 4).
