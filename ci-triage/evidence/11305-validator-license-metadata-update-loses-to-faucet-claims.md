# 11305 - ValidatorLicenseMetadataTimeBasedIntegrationTest: metadata update loses three license races to faucet claims and lands 11 s after the 20 s check (run 42639073931)

new (test timing). main fbc7ad2481 (#8100), job simtime (4), Canton 3.7.2; tests succeeded 10, failed 1.
`contact point gets updated` advances sim time by 2 h after swapping aliceValidator for aliceValidatorLocal and
expects the ValidatorLicense metadata to show the new contact point within the default `actAndCheck` window of 20 s.
After the jump three triggers of aliceValidatorLocal exercise the same ValidatorLicense: ReceiveFaucetCouponTrigger
(rounds 0, 1, 2, one after the other), ValidatorLicenseActivityTrigger and ValidatorLicenseMetadataTrigger. The
metadata update was rejected with LOCAL_VERDICT_LOCKED_CONTRACTS three times, each time behind a faucet claim, and
succeeded on its fourth attempt at 15:26:43.976, 11 s after the check gave up (15:26:32.887). Each transaction took
6 to 9 s instead of about 1 s because BFT ordering on the shared simtime Canton was slow from about 15:21 to 15:26
(median 1.5 to 2.7 s per sequencing hop at sv1, 0.29 s from 15:27), uniformly on all four BFT nodes, with no
ordering-topology growth, view change or blacklisting. What slowed ordering in that window was not found. Not a
duplicate; not a regression of #8100 (the suite passed on the next two main runs, which contain it).

- Run: https://github.com/canton-network/splice/actions/runs/42639073931, main fbc7ad2481 ("Make ACS stores track a single synchronizer (#8100)"), job 129235481382 `ci / scala_test_sim_time / simtime (4)`, attempt 1.
- Runtime canton: 3.7.2.
- Component: test (check window too short for serialized license contention); slow BFT ordering as the trigger, cause not found.

## 1. Failing assertion

The metadata still had the old contact point and lastUpdatedAt 2 h behind the ledger time.

```
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11305/job.log | grep -a -m1 'Check metadata gets updated' | cut -c1-900
```
```
[info]   Check metadata gets updated for Advance time past minMetadataUpdateInterval org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.ValidatorLicenseMetadata(1970-01-01T04:18:40Z, 0.10.2-ci-snapshot.20261109.1.0.vfbc7ad24-dirty, alice@example.com) was not equal to org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.ValidatorLicenseMetadata(1970-01-01T06:18:40Z, 0.10.2-ci-snapshot.20261109.1.0.vfbc7ad24-dirty, aliceLocal@example.com) (ValidatorLicenseMetadataTimeBasedIntegrationTest.scala:74)
```

Job mapping (one failed job, attempt 1):

```
RUN=42639073931; export NAME='simtime (4)'
gh api --paginate "repos/canton-network/splice/actions/runs/$RUN/jobs?filter=all&per_page=100" --jq '.jobs[] | select((.name == env.NAME or (.name | endswith(" / " + env.NAME))) and .conclusion == "failure") | "\(.id) attempt=\(.run_attempt) \(.name)"'
```
```
129235481382 attempt=1 ci / scala_test_sim_time / simtime (4)
```

## 2. Which test was running

Artifact `logs-simtime-4` (created 2026-11-09T15:33:11Z), downloaded to `log/11305/logs-simtime-4/`.

```
T=log/11305/logs-simtime-4/canton_network_test.clog.gz
zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '" | grep -a 'ValidatorLicenseMetadata' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200
```
```
2026-11-09T15:25:14.800Z Starting test suite 'ValidatorLicenseMetadataTimeBasedIntegrationTest'...",
2026-11-09T15:25:14.801Z Starting 'ValidatorLicenseMetadataTimeBasedIntegrationTest/contact point gets updated'...",
2026-11-09T15:26:56.580Z Test failed: 'ValidatorLicenseMetadataTimeBasedIntegrationTest/contact point gets updated', message: Check metadata gets updated for Advance time past minMetadataUpdateInterva
2026-11-09T15:26:56.589Z Starting 'ValidatorLicenseMetadataTimeBasedIntegrationTest/lastActiveAt gets updated'...",
2026-11-09T15:27:23.245Z Test succeeded: 'ValidatorLicenseMetadataTimeBasedIntegrationTest/lastActiveAt gets updated'",
2026-11-09T15:27:23.245Z Starting 'ValidatorLicenseMetadataTimeBasedIntegrationTest/unclaimed ValidatorLivenessActivityRecord contracts should be expired'...",
2026-11-09T15:28:44.227Z Test succeeded: 'ValidatorLicenseMetadataTimeBasedIntegrationTest/unclaimed ValidatorLivenessActivityRecord contracts should be expired'",
```

## 3. Test and trigger code at the run's sha

The test leaves the faucet and activity triggers running (the second test case's comment says so on purpose) and
checks with the default `actAndCheck` window.

```
S=fbc7ad24815305dc936e5d8aebefbd5c75f67882
git show $S:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/ValidatorLicenseMetadataTimeBasedIntegrationTest.scala | cat -n | sed -n '62,80p;89,91p'
git show $S:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/SpliceTests.scala | cat -n | sed -n '365,367p'
```
```
    62	    aliceValidatorBackend.stop()
    63	    aliceValidatorLocalBackend.start()
    64	    actAndCheck(
    65	      "Advance time past minMetadataUpdateInterval",
    66	      advanceTime(java.time.Duration.ofHours(2)),
    67	    )(
    68	      "metadata gets updated",
    69	      _ => {
    70	        val license =
    71	          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs.awaitJava(
    72	            ValidatorLicense.COMPANION
    73	          )(aliceValidatorBackend.getValidatorPartyId())
    74	        license.data.metadata.toScala.value shouldBe new ValidatorLicenseMetadata(
    75	          getLedgerTime.toInstant,
    76	          BuildInfo.compiledVersion,
    77	          "aliceLocal@example.com",
    78	        )
    79	      },
    80	    )
    89	    // Collect the initial faucet coupons to make sure they don't run after the first license query.
    90	    // We don't disable the trigger globally since the first test should also pass
    91	    // with this trigger enabled.
   365	    def actAndCheck[T, U](
   366	        timeUntilSuccess: FiniteDuration = 20.seconds,
   367	        maxPollInterval: FiniteDuration = 5.seconds,
```

All three triggers exercise a consuming choice on the same ValidatorLicense:

```
S=fbc7ad24815305dc936e5d8aebefbd5c75f67882; D=apps/validator/src/main/scala/org/lfdecentralizedtrust/splice/validator/automation
git show $S:$D/ValidatorLicenseMetadataTrigger.scala | grep -n -E 'exercise'
git show $S:$D/ReceiveFaucetCouponTrigger.scala | grep -n -E 'exercise|Received faucet coupon'
git show $S:$D/ValidatorLicenseActivityTrigger.scala | grep -n -E 'exercise'
```
```
71:        update = task.work.existingLicense.exercise(
72:          _.exerciseValidatorLicense_UpdateMetadata(
129:          license.exercise(
130:            _.exerciseValidatorLicense_RecordValidatorLivenessActivity(
140:          TaskSuccess(s"Received faucet coupon for Round ${unclaimedRound.payload.round.number}")
67:        update = task.work.existingLicense.exercise(
68:          _.exerciseValidatorLicense_ReportActive(
```

## 4. The metadata update lost three races and landed after the check

The check started at 15:26:12.778 and failed at 15:26:32.887. The metadata trigger was rejected with
LOCAL_VERDICT_LOCKED_CONTRACTS at 15:26:21, 15:26:29 and 15:26:36, each time just as a faucet claim completed; after
the first loss the faucet trigger had already started its next claim (15:26:21.353, 15:26:29.576) before the metadata
trigger re-read the new license (15:26:21.566, 15:26:29.682). The update succeeded at 15:26:43.976.

```
T=log/11305/logs-simtime-4/canton_network_test.clog.gz
zcat $T | awk '/2026-11-09T15:26:(0[89]|[1-4][0-9])/' | grep -a -E '"logger_name":"o.l.s.(v.a.(ReceiveFaucetCouponTrigger|ValidatorLicenseActivityTrigger|ValidatorLicenseMetadataTrigger):[^"]*aliceValidatorLocal|i.t.ValidatorLicenseMetadataTimeBasedIntegrationTest:)' | grep -a -E '"message":"(Processing|Completed processing|skipped, as|Task that failed|advancing time|Running clue: \(check\)|Failed clue|Global synchronizer time)' | sed -E 's/"logger_name":"o\.l\.s\.[a-z.]*\.([A-Za-z]+):.*/[\1]/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/\\n.*(\[[A-Za-z]+\])$/ ... \1/; s/ValidatorLicenseMetadataTimeBasedIntegrationTest/VLMT/' | cut -c1-250
```
```
2026-11-09T15:26:09.444Z advancing time by PT2H to 1970-01-01T06:18:40Z",[VLMT]
2026-11-09T15:26:12.761Z Processing ... [ValidatorLicenseActivityTrigger]
2026-11-09T15:26:12.763Z Processing ... [ValidatorLicenseMetadataTrigger]
2026-11-09T15:26:12.778Z Global synchronizer time reached 1970-01-01T06:18:39Z on 2 participants after advancing time",[VLMT]
2026-11-09T15:26:12.778Z Running clue: (check) metadata gets updated",[VLMT]
2026-11-09T15:26:12.899Z Processing ... [ReceiveFaucetCouponTrigger]
2026-11-09T15:26:21.280Z Task that failed with following exception is not stale: ABORTED: LOCAL_VERDICT_LOCKED_CONTRACTS(2,2af2e37b): Rejected transaction is referring to locked contracts  ",[ValidatorLicenseMetadataTrigger]
2026-11-09T15:26:21.298Z Completed processing with outcome: Received faucet coupon for Round 0",[ReceiveFaucetCouponTrigger]
2026-11-09T15:26:21.353Z Processing ... [ReceiveFaucetCouponTrigger]
2026-11-09T15:26:21.370Z skipped, as the task has become stale",[ValidatorLicenseActivityTrigger]
2026-11-09T15:26:21.532Z skipped, as the task has become stale",[ValidatorLicenseMetadataTrigger]
2026-11-09T15:26:21.566Z Processing ... [ValidatorLicenseMetadataTrigger]
2026-11-09T15:26:29.395Z Task that failed with following exception is not stale: ABORTED: LOCAL_VERDICT_LOCKED_CONTRACTS(2,23311e16): Rejected transaction is referring to locked contracts  ",[ValidatorLicenseMetadataTrigger]
2026-11-09T15:26:29.500Z Completed processing with outcome: Received faucet coupon for Round 1",[ReceiveFaucetCouponTrigger]
2026-11-09T15:26:29.576Z Processing ... [ReceiveFaucetCouponTrigger]
2026-11-09T15:26:29.650Z skipped, as the task has become stale",[ValidatorLicenseMetadataTrigger]
2026-11-09T15:26:29.682Z Processing ... [ValidatorLicenseMetadataTrigger]
2026-11-09T15:26:32.887Z Failed clue: (check) metadata gets updated",[VLMT]
2026-11-09T15:26:36.463Z Task that failed with following exception is not stale: ABORTED: LOCAL_VERDICT_LOCKED_CONTRACTS(2,ed73c532): Rejected transaction is referring to locked contracts  ",[ValidatorLicenseMetadataTrigger]
2026-11-09T15:26:36.475Z Completed processing with outcome: Received faucet coupon for Round 2",[ReceiveFaucetCouponTrigger]
2026-11-09T15:26:36.730Z skipped, as the task has become stale",[ValidatorLicenseMetadataTrigger]
2026-11-09T15:26:36.774Z Processing ... [ValidatorLicenseMetadataTrigger]
2026-11-09T15:26:43.976Z Completed processing with outcome: Updated ValidatorLicense to version 0.10.2-ci-snapshot.20261109.1.0.vfbc7ad24-dirty and contact point aliceLocal@example.com",[ValidatorLicenseMetadataTrigger]
```

In the first race (all three submitted within 140 ms) the three requests were ordered in the same block 6113, the
faucet claim first (trace d28ec62f, the ValidatorLicense_RecordValidatorLivenessActivity submission):

```
C=log/11305/logs-simtime-4/canton-simtime.clog.gz
zcat $C | grep -a 'd28ec62fb93d3730b322dd23c326f323' | grep -a -E 'Submitted commands are|Block 6113 being output' | grep -a -E 'Submitted|globalSequencerSv1' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-300
```
```
2026-11-09T15:26:12.953Z Submitted commands are: 'exercise @Splice.ValidatorLicense:ValidatorLicense ValidatorLicense_RecordValidatorLivenessActivity'",[c.d.c.p.a.s.c.CommandSubmissionServiceImpl:participant=aliceParticipant] DEBUG
2026-11-09T15:26:14.608Z Block 6113 being output contains requests with the following trace IDs: [d28ec62fb93d3730b322dd23c326f323,6b0b01662f0caf438482299e82be5262,34cf04d248df3adf1ea6c8f4cd306ec0,2af2e37b9ca927a8dfd5323d82a66bb4,ce8d152477830f7c99a239fb73da842b,c018606023a7235fe968d2c2910c4e7f,c01a
```

## 5. Every transaction of the first test case took 5 to 11 s, the later test cases about 1 s

With about 1.2 s per transaction the four serialized license transactions fit easily in 20 s; at about 8 s they
need about 31 s.

```
T=log/11305/logs-simtime-4/canton_network_test.clog.gz
zcat $T | grep -a 'ValidatorLicenseMetadataTimeBasedIntegrationTest' | grep -a 'CommandService/SubmitAndWait to' | grep -a -E 'sending request|succeeded\(OK\)' | sed -E 's/\{"@timestamp":"([^"]+)","message":"Request \(tid:([0-9a-f]+)\) [^:]*:[0-9]+: (sending request|succeeded).*/\1 \2 \3/' | awk '{split($1,a,"T"); split(a[2],b,":"); sub("Z","",b[3]); t=b[1]*3600+b[2]*60+b[3]; if ($3=="sending") s[$2]=t; else if ($2 in s) {d=t-s[$2]; k=($1<"2026-11-09T15:26:56.580Z")?"test 1 (contact point gets updated)":"tests 2 and 3"; n[k]++; sum[k]+=d; if(!(k in mn)||d<mn[k])mn[k]=d; if(d>mx[k])mx[k]=d; delete s[$2]}} END {for (k in n) printf "%s: %d successful SubmitAndWait, min %.1fs avg %.1fs max %.1fs\n", k, n[k], mn[k], sum[k]/n[k], mx[k]}' | sort
```
```
test 1 (contact point gets updated): 20 successful SubmitAndWait, min 5.4s avg 8.1s max 11.2s
tests 2 and 3: 61 successful SubmitAndWait, min 0.9s avg 1.2s max 2.0s
```

The faucet claim's hops at sv1: request ordered 1.4 s after send (13.183 to 14.608), sv1's confirmation response
ordered 1.9 s after send (15.221 to 17.082), sv1's verdict ordered 1.8 s after send (17.743 to 19.583). Participant and
mediator processing between the hops took well under 0.2 s each (Phase 3 15:26:15.037, Phase 4 15:26:15.179).

```
C=log/11305/logs-simtime-4/canton-simtime.clog.gz
zcat $C | grep -a 'd28ec62fb93d3730b322dd23c326f323' | grep -a -E "sends request with id '(2229dc16|8852c26e|8efcc2d1)|Block (6113|6125|6134) being output|Phase (3|4): .*participant=sv1Participant" | grep -a -E 'sends request|Phase|globalSequencerSv1' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | cut -c1-200
```
```
2026-11-09T15:26:13.183Z 'PAR::aliceValidator::12208f1b76f0...' sends request with id '2229dc16-19ba-4696-8a5a-7b7c0ff71227' of size 4055 bytes with 3 envelopes to MGR=1, PAR=2.",[c.d.c.s.s.s.GrpcSequ
2026-11-09T15:26:14.608Z Block 6113 being output contains requests with the following trace IDs: [d28ec62fb93d3730b322dd23c326f323,6b0b01662f0caf438482299e82be5262,34cf04d248df3adf1ea6c8f4cd306ec0,2af
2026-11-09T15:26:15.037Z Phase 3: Validating Transaction request=1970-01-01T06:18:40.019Z with 1 envelope(s)",[c.d.c.p.p.TransactionProcessor:participant=sv1Participant/psid=global-domain::12207122019
2026-11-09T15:26:15.179Z Phase 4: Sending for request=1970-01-01T06:18:40.019Z with msgId=8852c26e-3439-4379-975b-e68652b619d1 approved=1, rejected=0, abstained=0",[c.d.c.p.p.TransactionProcessor:part
2026-11-09T15:26:15.221Z 'PAR::sv1::1220ab49e461...' sends request with id '8852c26e-3439-4379-975b-e68652b619d1' of size 721 bytes with 1 envelopes to MGR=1.",[c.d.c.s.s.s.GrpcSequencerService:sequen
2026-11-09T15:26:17.082Z Block 6125 being output contains requests with the following trace IDs: [d28ec62fb93d3730b322dd23c326f323,d28ec62fb93d3730b322dd23c326f323,2af2e37b9ca927a8dfd5323d82a66bb4,2af
2026-11-09T15:26:17.743Z 'MED::sv1::1220fd8faf31...' sends request with id '8efcc2d1-6fcf-463f-8124-0d3356a19f58' of size 768 bytes with 1 envelopes to PAR=2.",[c.d.c.s.s.s.GrpcSequencerService:sequen
2026-11-09T15:26:19.583Z Block 6134 being output contains requests with the following trace IDs: [d28ec62fb93d3730b322dd23c326f323,6b0b01662f0caf438482299e82be5262,ce8d152477830f7c99a239fb73da842b,c01
```

## 6. BFT ordering was slow from about 15:21 to 15:26 on all four nodes, without topology change

Ordering latency at sv1 (send accepted by the sequencer to `BFT ordering request ... in output block`), per minute:

```
cat > log/11305/ordlat.py <<'EOF'
import sys, re, collections
send = re.compile(r'"@timestamp":"([^"]+)","message":"\'[^\']+\' sends request with id \'([0-9a-f-]+)\'')
out = re.compile(r'"@timestamp":"([^"]+)","message":"BFT ordering request at index \d+ in output block .* with message ID ([0-9a-f-]+) ')
def secs(ts):
    h, m, s = ts[11:-1].split(':'); return int(h)*3600+int(m)*60+float(s)
start = {}; agg = collections.defaultdict(list)
for line in sys.stdin:
    if 'sequencer=globalSequencerSv1' not in line: continue
    m = send.search(line)
    if m: start[m.group(2)] = secs(m.group(1)); continue
    m = out.search(line)
    if m and m.group(2) in start:
        agg[m.group(1)[11:16]].append(secs(m.group(1)) - start.pop(m.group(2)))
for k in sorted(agg):
    v = sorted(agg[k]); print(f"{k} n={len(v)} median={v[len(v)//2]:.2f}s p90={v[int(len(v)*0.9)]:.2f}s max={v[-1]:.2f}s")
EOF
zcat log/11305/logs-simtime-4/canton-simtime.clog.gz | python3 -I log/11305/ordlat.py | awk '$1>="15:15" && $1<="15:31"'
```
```
15:15 n=81 median=0.30s p90=0.41s max=1.90s
15:16 n=120 median=0.29s p90=0.54s max=0.75s
15:17 n=103 median=0.29s p90=0.45s max=0.57s
15:18 n=27 median=0.31s p90=0.61s max=0.67s
15:19 n=52 median=0.43s p90=6.04s max=7.73s
15:20 n=225 median=0.59s p90=0.88s max=2.90s
15:21 n=354 median=1.75s p90=3.58s max=4.86s
15:22 n=184 median=1.82s p90=2.56s max=3.07s
15:23 n=252 median=2.68s p90=3.44s max=4.28s
15:24 n=91 median=0.57s p90=1.42s max=2.90s
15:25 n=31 median=1.85s p90=2.52s max=2.61s
15:26 n=85 median=1.47s p90=1.88s max=2.61s
15:27 n=149 median=0.29s p90=0.60s max=0.70s
15:28 n=123 median=0.29s p90=0.42s max=0.68s
15:29 n=207 median=0.28s p90=0.55s max=0.80s
15:30 n=143 median=0.29s p90=0.51s max=0.73s
15:31 n=100 median=0.29s p90=0.43s max=0.65s
```

Interval between consecutive blocks of the same leader, as output at sv1: all four leaders slowed alike.

```
cat > log/11305/leaders.py <<'EOF'
import sys, re, collections
pat = re.compile(r'"@timestamp":"([^"]+)","message":"Full data obtained for block (\d+) in epoch (\d+); original leader SEQ::(sv\d)::')
def secs(ts):
    h, m, s = ts[11:-1].split(':'); return int(h)*3600+int(m)*60+float(s)
last = {}; gaps = collections.defaultdict(list)
for line in sys.stdin:
    if 'sequencer=globalSequencerSv1/' not in line or 'Full data obtained' not in line: continue
    m = pat.search(line)
    if not m: continue
    t = secs(m.group(1)); ldr = m.group(4); minute = m.group(1)[11:16]
    if ldr in last: gaps[(minute, ldr)].append(t - last[ldr])
    last[ldr] = t
for minute in sorted({k[0] for k in gaps}):
    if not ('15:19' <= minute <= '15:29'): continue
    row = []
    for l in ('sv1','sv2','sv3','sv4'):
        v = sorted(gaps.get((minute,l), []))
        row.append(f"{l} n={len(v)} med={v[len(v)//2]:.2f}s" if v else f"{l} n=0")
    print(minute, ' | '.join(row))
EOF
zcat log/11305/logs-simtime-4/canton-simtime.clog.gz | python3 -I log/11305/leaders.py
```
```
15:19 sv1 n=58 med=0.55s | sv2 n=58 med=0.55s | sv3 n=58 med=0.55s | sv4 n=35 med=0.38s
15:20 sv1 n=99 med=0.47s | sv2 n=99 med=0.45s | sv3 n=99 med=0.47s | sv4 n=42 med=0.47s
15:21 sv1 n=62 med=0.89s | sv2 n=62 med=0.89s | sv3 n=62 med=0.93s | sv4 n=62 med=0.94s
15:22 sv1 n=69 med=0.81s | sv2 n=69 med=0.81s | sv3 n=69 med=0.85s | sv4 n=69 med=0.86s
15:23 sv1 n=47 med=1.24s | sv2 n=46 med=1.25s | sv3 n=46 med=1.26s | sv4 n=45 med=1.27s
15:24 sv1 n=71 med=0.69s | sv2 n=72 med=0.71s | sv3 n=73 med=0.73s | sv4 n=73 med=0.72s
15:25 sv1 n=65 med=0.88s | sv2 n=64 med=0.90s | sv3 n=63 med=0.92s | sv4 n=64 med=0.93s
15:26 sv1 n=77 med=0.79s | sv2 n=78 med=0.81s | sv3 n=78 med=0.77s | sv4 n=78 med=0.81s
15:27 sv1 n=151 med=0.34s | sv2 n=150 med=0.35s | sv3 n=150 med=0.35s | sv4 n=150 med=0.35s
15:28 sv1 n=141 med=0.36s | sv2 n=142 med=0.35s | sv3 n=142 med=0.36s | sv4 n=142 med=0.36s
15:29 sv1 n=138 med=0.37s | sv2 n=138 med=0.37s | sv3 n=138 med=0.37s | sv4 n=138 med=0.37s
```

The ordering topology stayed at 4 nodes with no blacklisted nodes; no view change in the window (so not the 11255
shapes, which all involve the ordering topology growing or a node being blacklisted or rejected):

```
C=log/11305/logs-simtime-4/canton-simtime.clog.gz
zcat $C | awk '/"@timestamp":"2026-11-09T15:2[1-6]:/' | grep -a -c 'view change in progress = true'
zcat $C | awk '/"@timestamp":"2026-11-09T15:2[1-6]:/' | grep -a 'New epoch' | grep -a -c -v 'blacklisted nodes = List()'
zcat $C | awk '/"@timestamp":"2026-11-09T15:2[4-7]:/' | grep -a 'New epoch' | grep -a 'globalSequencerSv1' | sed -E 's/\{"@timestamp":"([^"]+)","message":"New epoch ([0-9]+) has started.*activationTime = ([^,]+),.*size = ([0-9]+).*/\1 epoch=\2 topoActivation=\3 size=\4/' | cut -c1-200
```
```
0
0
2026-11-09T15:24:01.098Z epoch=82 topoActivation=1970-01-01T03:48:10.911001Z size=4
2026-11-09T15:24:09.382Z epoch=83 topoActivation=1970-01-01T03:48:10.911001Z size=4
2026-11-09T15:24:22.666Z epoch=84 topoActivation=1970-01-01T03:48:10.911001Z size=4
2026-11-09T15:24:45.347Z epoch=85 topoActivation=1970-01-01T03:48:10.911001Z size=4
2026-11-09T15:25:09.101Z epoch=86 topoActivation=1970-01-01T03:48:10.911001Z size=4
2026-11-09T15:25:27.833Z epoch=87 topoActivation=1970-01-01T04:18:40.571001Z size=4
2026-11-09T15:25:46.946Z epoch=88 topoActivation=1970-01-01T04:18:40.571001Z size=4
2026-11-09T15:26:04.329Z epoch=89 topoActivation=1970-01-01T04:18:40.571001Z size=4
2026-11-09T15:26:22.062Z epoch=90 topoActivation=1970-01-01T04:18:40.571001Z size=4
2026-11-09T15:26:38.517Z epoch=91 topoActivation=1970-01-01T04:18:40.571001Z size=4
2026-11-09T15:26:53.677Z epoch=92 topoActivation=1970-01-01T04:18:40.571001Z size=4
2026-11-09T15:27:02.715Z epoch=93 topoActivation=1970-01-01T04:18:40.571001Z size=4
2026-11-09T15:27:10.389Z epoch=94 topoActivation=1970-01-01T04:18:40.571001Z size=4
2026-11-09T15:27:17.824Z epoch=95 topoActivation=1970-01-01T04:18:40.571001Z size=4
2026-11-09T15:27:26.358Z epoch=96 topoActivation=1970-01-01T04:18:40.571001Z size=4
2026-11-09T15:27:34.745Z epoch=97 topoActivation=1970-01-01T04:18:40.571001Z size=4
2026-11-09T15:27:42.604Z epoch=98 topoActivation=1970-01-01T04:18:40.571001Z size=4
2026-11-09T15:27:50.457Z epoch=99 topoActivation=1970-01-01T04:18:40.571001Z size=4
2026-11-09T15:27:58.596Z epoch=100 topoActivation=1970-01-01T04:18:40.571001Z size=4
```

The canton WARNs in 15:21 to 15:26 belong to the previous suite (TimeBasedTestNetPreviewIntegrationTest, until 15:25:01)
and say only that processing was late; they do not name a cause:

```
C=log/11305/logs-simtime-4/canton-simtime.clog.gz
zcat $C | awk '/"@timestamp":"2026-11-09T15:2[1-6]:/' | grep -a -E '"level":"(WARN|ERROR)"' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200 | awk '{print substr($0,1,16), substr($0,26,90)}' | sort | uniq -c | sort -rn | head -10
```
```
     17 2026-11-09T15:21 Your detailed API event logging is turned on but you are doing quite a few concurrent requ
      9 2026-11-09T15:23 Your detailed API event logging is turned on but you are doing quite a few concurrent requ
      7 2026-11-09T15:21 Detected late processing (or clock skew) of batch with timestamp = 1970-01-01T03:58:20.208
      5 2026-11-09T15:23 Detected late processing (or clock skew) of batch with timestamp = 1970-01-01T04:08:30.314
      4 2026-11-09T15:21 Response message for request [RequestId(1970-01-01T03:58:20.204001Z)] timed out at 1970-01
      1 2026-11-09T15:23 Detected late processing (or clock skew) of batch with timestamp = 1970-01-01T04:08:30.317
      1 2026-11-09T15:23 Detected late processing (or clock skew) of batch with timestamp = 1970-01-01T04:08:30.315
      1 2026-11-09T15:23 Detected late processing (or clock skew) of batch with timestamp = 1970-01-01T04:08:30.313
      1 2026-11-09T15:22 Your detailed API event logging is turned on but you are doing quite a few concurrent requ
      1 2026-11-09T15:21 Detected late processing (or clock skew) of batch with timestamp = 1970-01-01T03:58:20.207
```

The runner artifact `logs-simtime-4-runner` holds only step output (nix setup, caches, uploads), no CPU or memory metrics.

## 7. Not a regression of #8100: the suite passed on the next two main runs

```
mkdir -p log/11305/later
for RUN in 42646231899 42658360509; do for J in $(gh api --paginate "repos/canton-network/splice/actions/runs/$RUN/jobs?per_page=100" --jq '.jobs[] | select(.name|test("/ simtime \\(")) | select(.status=="completed") | "\(.id)"'); do gh api repos/canton-network/splice/actions/jobs/$J/logs > log/11305/later/$J.log; done; done
for f in log/11305/later/*.log; do if grep -a -A16 -E '^\S+Z org\.lfdecentralizedtrust\.splice\.integration\.tests\.[A-Za-z0-9]+$' $f | grep -a -q 'tests.ValidatorLicenseMetadataTimeBasedIntegrationTest$'; then J=$(basename $f .log); echo "job $J: $(gh api repos/canton-network/splice/actions/jobs/$J --jq '"\(.run_id) \(.name) \(.conclusion) \(.head_sha[0:10])"')"; fi; done
git merge-base --is-ancestor fbc7ad2481 5dc875a64f && echo "5dc875a64f contains #8100"
```
```
job 129261073792: 42646231899 ci / scala_test_sim_time / simtime (1) success 5dc875a64f
job 129303339359: 42658360509 ci / scala_test_sim_time / simtime (3) success 91715b25c0
5dc875a64f contains #8100
```

## 8. Duplicate check

No family, INDEX row or recent issue matches; the two older tracker hits are other failures of the same suite.

```
grep -n -i -E 'ValidatorLicenseMetadata|LOCAL_VERDICT_LOCKED' ci-triage/known-families.md
awk -F'\t' 'tolower($0) ~ /validatorlicensemetadata|local_verdict_locked/ {print $1" | "$7}' ci-triage/evidence/INDEX.tsv
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "ValidatorLicenseMetadataTimeBasedIntegrationTest"' --jq '.total_count, (.items[] | "#\(.number) \(.state) \(.title[0:140])")'
```
```
2
#8423 closed ValidatorLicenseMetadataTimeBasedIntegrationTest  unclaimed ValidatorLivenessActivityRecord contracts should be expired  
#6377 closed Found problems in log: Trigger ran before round closing time but task is stil available, will not rerun (ReceiveFaucetCouponTrigger:Validato
```

- 8423 (2026-05-13): the third test case of the suite, not `contact point gets updated`.
- 6377 (2025-11-07): a checkErrors log line of the faucet trigger, no failed assertion.

## 9. Test history

```
git log --format='%h %ad %s' --date=short -3 fbc7ad2481 -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/ValidatorLicenseMetadataTimeBasedIntegrationTest.scala
```
```
d4a21721d0 2026-02-26 SV/validator integration test clues for list size assertions (#4151)
7a0b452c00 2026-02-17 Rename IntegrationTest to IntegrationTestWithIsolatedEnvironment (#3985)
a10cc0ce3a 2025-11-07 [ci] Fix sim time manual trigger run (#3130)
```

## Verdict

New, first hit; test flake with a slow-ordering trigger. The 20 s check window cannot absorb the license contention
the test deliberately leaves on: after the 2 h jump the faucet trigger claims three rounds one after the other on
the same ValidatorLicense, and the metadata update only lands after them. At about 1.2 s per transaction that is
about 5 s; with ordering at 1.5 to 2.7 s per hop (about 8 s per transaction) it took 31 s.

Fix (test): in `ValidatorLicenseMetadataTimeBasedIntegrationTest` `contact point gets updated`, give the check room
for the serialized faucet claims, e.g. `actAndCheck(timeUntilSuccess = 60.seconds)(...)`; alternatively claim the
faucet coupons first as the second test case does (`advanceTimeForRewardAutomationToRunForCurrentRound` before the
license read) so only the metadata update competes. No assignee: no PR introduced this; the test file was last changed
by #4151 (2026-02-26).

Not verified: why BFT ordering was slow from about 15:21 to 15:26 (all four nodes alike, no topology change, view
change or blacklisting; the runner artifact has no resource metrics). The slow window started in the previous suite,
so it is not caused by this test.
