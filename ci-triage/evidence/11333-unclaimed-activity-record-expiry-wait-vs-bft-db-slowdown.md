# 11333 - UnclaimedActivityRecordIntegrationTest "An UnclaimedActivityRecord gets expired": record still listed after the 20 s wait (run 42790576154)

New (first hit of this shape since 9729 in August, on release-line-0.7.0). Test timing, not a product bug. The
expiry trigger did its job: it picked the record up at 10:00:17.380, as soon as `expiresAt` (10:00:12.064) plus the
5 s `clockSkewAutomationDelay` had passed, and submitted `DsoRules_ExpireUnclaimedActivityRecord` at 10:00:17.725.
That one transaction then took 18 s (SubmitAndWait answered at 10:00:35.880; sv1Participant stored it at 10:00:32.970)
because all four DA BFT sequencers of the shared Canton slowed down together from about 10:00:05 to 10:02:40: the
orderer's block store (sv1) went from 3-5 ms to 100-280 ms per block on average, block output fell from about 1000 to 150-400
per minute, and every sequencing hop (request, confirmation response, result) took 2-4 s. The test's check
`eventually()` (default 20 s, 10:00:08.077 to 10:00:28.406) leaves only about 11 s for that one round trip, because
the record cannot be archived before `expiresAt + 5 s`. The sibling test case in the same suite needed 13.7 s of its
20 s on a normal round trip of 1.06 s. The cause of the 2.5-minute slowdown (host or Postgres contention) is not in
the artifacts.

- Run: https://github.com/canton-network/splice/actions/runs/42790576154, main e59889cad0 ("SV UI: Add support for
  new vote creation to update featured app config (#8061)"), job 129743155909 `wall-clock-time (6)`, attempt 1,
  runner self-hosted-k8s-large-runner-jd7mz.
- Runtime canton: 3.7.3-snapshot.20261109.20894.0.ve6e6c252 (`git show e59889cad0:nix/canton-sources.json`).
- Component: test (wait budget); trigger: transient slowdown of the Canton BFT orderer DB writes on the runner.

## 1. Failing assertion

Job log (`gh api repos/canton-network/splice/actions/jobs/129743155909/logs > log/11333/job.log`):
```
sed 's/\x1b\[[0-9;]*m//g' log/11333/job.log | grep -a -E 'Tests: succeeded|Total number of tests run|An UnclaimedActivityRecord gets expired \*\*\* FAILED' | cut -c1-200 | head -4
```
```
2026-11-10T10:01:56.6290396Z [info] - An UnclaimedActivityRecord gets expired *** FAILED ***
2026-11-10T10:15:27.5732222Z [info] Total number of tests run: 22
2026-11-10T10:15:27.5733222Z [info] Tests: succeeded 21, failed 1, canceled 0, ignored 0, pending 0
2026-11-10T10:15:27.5735179Z [info] - An UnclaimedActivityRecord gets expired *** FAILED ***
```
Stack head (`grep -a -A8 'An UnclaimedActivityRecord gets expired \*\*\* FAILED'`, trimmed):
```
[info]   List(org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.UnclaimedActivityRecord.Contract(ContractId(00bdef53dd5f48ab315b04dd8a0e98fec951af97a9d498ec386ed0ed96c3196da3ca12122014f407b1b35206af5a5129a95d918eba88856f3dd609f687b052a6e316b09920), ...
[info]   org.scalatest.exceptions.TestFailedException:
[info]   at org.scalatest.matchers.MatchersHelper$.indicateFailure(MatchersHelper.scala:392)
[info]   at org.scalatest.matchers.should.Matchers$AnyShouldWrapper.shouldBe(Matchers.scala:7770)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.UnclaimedActivityRecordIntegrationTest.$anonfun$new$22(UnclaimedActivityRecordIntegrationTest.scala:196)
```

## 2. The test and its timeline

Test source at the run's sha (lines 149-199): the record's expiry is `Instant.now().plus(10, ChronoUnit.SECONDS)` at
the act, the expiry triggers are paused during the act and check, then the archive is checked with a default
`eventually()`:
```
git show e59889cad0:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/UnclaimedActivityRecordIntegrationTest.scala | cat -n | sed -n '149,199p' | grep -E 'setTriggersWithin|triggersToPause|plus\(|eventually|shouldBe empty|clue\('
```
```
   149	    setTriggersWithin(
   150	      triggersToPauseAtStart = Seq(mergeAmuletsTrigger(aliceValidatorBackend, aliceUserName))
   152	      setTriggersWithin(
   153	        triggersToPauseAtStart = expiredUnallocatedTriggers ++ expiredUnclaimedTriggers
   163	                  Instant.now().plus(10, ChronoUnit.SECONDS),
   186	              ) shouldBe empty withClue "UnclaimedReward"
   191	      clue("UnclaimedActivityRecord gets archived") {
   192	        eventually() {
   196	            ) shouldBe empty withClue "UnclaimedActivityRecord"
```
History of those lines: `+10 s` since 8a83eb63b5 (#7425, 2026-09-21; the later 01b3d029eb changed only the other test
case from 5 s to 10 s), `eventually()` since 2025-07-04:
```
git blame -L 163,163 -L 192,192 e59889cad0 -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/UnclaimedActivityRecordIntegrationTest.scala
```
```
8a83eb63b54 (Raymond Roestenburg 2026-09-21 12:11:07 +0200 163)                   Instant.now().plus(10, ChronoUnit.SECONDS),
9521489b397 (Jose Velasco - IEU  2025-07-04 16:16:07 +0200 192)         eventually() {
```
Clue timeline (artifact `logs-wall-clock-time-6`, `gh run download 42790576154 --repo canton-network/splice -n
logs-wall-clock-time-6 -D log/11333/logs-wall-clock-time-6`):
```
A=log/11333/logs-wall-clock-time-6
zcat $A/canton_network_test.clog.gz | grep -a 'UnclaimedActivityRecordIntegrationTest' | grep -aE '"logger_name":"(o.l.s.i.t.UnclaimedActivityRecordIntegrationTest|c.d.c.LogReporter)' | grep -aE 'clue|Starting .UnclaimedActivityRecordIntegrationTest/|Test (succeeded|failed)' | jq -rR 'fromjson? | "\(."@timestamp") \(.level) \(.message[0:120])"'
```
```
2026-11-10T09:58:52.367Z DEBUG Running clue: UnallocatedUnclaimedActivityRecord gets archived
2026-11-10T09:59:10.963Z DEBUG Finished clue: UnallocatedUnclaimedActivityRecord gets archived
2026-11-10T09:59:26.731Z INFO Starting 'UnclaimedActivityRecordIntegrationTest/An UnclaimedActivityRecord gets expired'...
...
2026-11-10T10:00:02.063Z DEBUG Running clue: (act) Creating vote request
2026-11-10T10:00:03.311Z DEBUG Finished clue: (act) Creating vote request
2026-11-10T10:00:03.311Z DEBUG Running clue: (check) UnclaimedActivityRecord has been created
2026-11-10T10:00:08.076Z DEBUG Finished clue: (check) UnclaimedActivityRecord has been created
2026-11-10T10:00:08.077Z DEBUG Running clue: UnclaimedActivityRecord gets archived
2026-11-10T10:00:28.406Z ERROR Failed clue: UnclaimedActivityRecord gets archived
2026-11-10T10:01:56.195Z ERROR Test failed: 'UnclaimedActivityRecordIntegrationTest/An UnclaimedActivityRecord gets expired', message: List(org.lfdecen
```
The archive wait ran 20.3 s (10:00:08.077 to 10:00:28.406), the default `eventually()` budget.

## 3. The expiry trigger: resumed, picked the record up when due, submission slow

```
zcat $A/canton_network_test.clog.gz | awk '/"@timestamp":"2026-11-10T09:58:4/,/"@timestamp":"2026-11-10T10:00:36/' | grep -aE '(ExpiredUnclaimedActivityRecordTrigger|ExpiredUnallocatedUnclaimedActivityRecordTrigger|AllocateUnallocatedUnclaimedActivityRecordTrigger):.*"level":"INFO"|TriggerTestUtil|tid:c07ac0fa157ba28e571d01e8a59f02fe' | grep -av 'TrafficControl' | jq -rR 'fromjson? | "\(."@timestamp") \(.logger_name|sub(":.*";"")|sub(".*\\.";"")) | \(.message|gsub("\n";" ")|gsub("  +";" ")|.[0:150])"'
```
Sibling test case (normal round trip, 1.06 s):
```
2026-11-10T09:58:52.366Z TriggerTestUtil$ | Resuming triggers after block: List(org.lfdecentralizedtrust.splice.sv.automation.delegatebased.ExpiredUnallocatedUnclaimedActivityRecordTrigger@79be3
2026-11-10T09:59:05.003Z ExpiredUnallocatedUnclaimedActivityRecordTrigger | Processing ReadyTask( readyAt = 2026-11-10T09:59:00.000290Z, work = AssignedContract( contract = Contract( contractId = 00114d664773381b9448eea20e99df
2026-11-10T09:59:05.020Z ExpiredUnallocatedUnclaimedActivityRecordTrigger | Completing dso delegate task ReadyTask( readyAt = 2026-11-10T09:59:00.000290Z, work = AssignedContract( contract = Contract( contractId = 00114d664773
2026-11-10T09:59:06.062Z ExpiredUnallocatedUnclaimedActivityRecordTrigger | Completed processing with outcome: archived expired unallocated unclaimed activity record
```
Failing test case:
```
2026-11-10T10:00:02.062Z TriggerTestUtil$ | Pausing triggers for block: List(org.lfdecentralizedtrust.splice.sv.automation.delegatebased.ExpiredUnallocatedUnclaimedActivityRecordTrigger@262edbc0
2026-11-10T10:00:05.967Z AllocateUnallocatedUnclaimedActivityRecordTrigger | Completed processing with outcome: allocated unallocated unclaimed activity record
2026-11-10T10:00:08.076Z TriggerTestUtil$ | Resuming triggers after block: List(org.lfdecentralizedtrust.splice.sv.automation.delegatebased.ExpiredUnallocatedUnclaimedActivityRecordTrigger@262ed
2026-11-10T10:00:17.380Z ExpiredUnclaimedActivityRecordTrigger | Processing ReadyTask( readyAt = 2026-11-10T10:00:12.372002Z, work = AssignedContract( contract = Contract( contractId = 00bdef53dd5f48ab315b04dd8a0e98
2026-11-10T10:00:17.705Z ExpiredUnclaimedActivityRecordTrigger | Completing dso delegate task ReadyTask( readyAt = 2026-11-10T10:00:12.372002Z, work = AssignedContract( contract = Contract( contractId = 00bdef53dd5f
2026-11-10T10:00:17.725Z ApiClientRequestLogger | Request (tid:c07ac0fa157ba28e571d01e8a59f02fe) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:5101: sending request SubmitAndWaitReq
2026-11-10T10:00:28.406Z UnclaimedActivityRecordIntegrationTest | Failed clue: UnclaimedActivityRecord gets archived
2026-11-10T10:00:35.880Z ApiClientRequestLogger | Request (tid:c07ac0fa157ba28e571d01e8a59f02fe) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:5101: received a message SubmitAndWait
```
The record's expiry and the trigger's 5 s lag. `readyAt` is `clock.now - clockSkewAutomationDelay`, default 5 s:
```
zcat $A/canton_network_test.clog.gz | awk '/"@timestamp":"2026-11-10T10:00:17.3/,/"@timestamp":"2026-11-10T10:00:17.4/' | grep -a 'ExpiredUnclaimedActivityRecordTrigger' | grep -a Processing | jq -rR 'fromjson? | .message' | grep -E 'Timestamp\(|createdAt'
git grep -n 'clockSkewAutomationDelay' e59889cad0 -- apps/common/src/main | head -3
```
```
          Timestamp(ms = 1794304812064005, str = 2026-11-10T10:00:12.064005Z)
      createdAt = 2026-11-10T10:00:05.264614Z
e59889cad0:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/automation/ScheduledTaskTrigger.scala:30:    val now = context.clock.now.minus(context.config.clockSkewAutomationDelay.asJava)
e59889cad0:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/config/AutomationConfig.scala:18:    clockSkewAutomationDelay: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(5),
```
So the record was archivable from 10:00:17.064 at the earliest; the trigger's first poll after that was 10:00:17.380.
Budget left for the archive transaction: 10:00:28.406 - 10:00:17.380 = 11.0 s. It took 18.1 s.

## 4. Where the 18 s went: three slow sequencing hops

Command id `53c1875f-b36f-455d-b7c2-e22396a4017a`, request (BFT time) 10:00:19.459196Z, in the Canton log:
```
zcat $A/canton.clog.gz | awk '/"@timestamp":"2026-11-10T10:00:17/,/"@timestamp":"2026-11-10T10:00:36/' | grep -aE '53c1875f-b36f-455d-b7c2-e22396a4017a|10:00:19.459196Z' | grep -aE 'Registering handle|Phase [1-7]|now in-flight|Successfully submitted|Delivering block|Sent result|Got result|SubmitAndWait.*sending response' | jq -rR 'fromjson? | "\(."@timestamp") \(.logger_name|sub("/psid=.*";"")|sub("^.*:";"")) | \(.message|gsub("\n";" ")|gsub("  +";" ")|.[0:120])"'
```
(Phase 2 and Phase 5 detail lines of the other mediators omitted.)
```
2026-11-10T10:00:17.737Z participant=sv1Participant | Registering handle for (53c1875f-b36f-455d-b7c2-e22396a4017a,sv1-56c72c81,Set(digital-asset-2-56c72c81::1220fe4740385039
2026-11-10T10:00:18.574Z participant=sv1Participant | Phase 1 completed: Submitting 4 envelopes for Transaction request, submitters digital-asset-2-56c72c81::1220fe474038...,
2026-11-10T10:00:21.356Z sequencer=globalSequencerSv1 | Delivering block 7981 to post-ordering (epoch = 121, block's BFT time = 2026-11-10T10:00:19.459196Z, block size = 1, ori
2026-11-10T10:00:22.363Z mediator=globalMediatorSv4 | Phase 2: Registered request=2026-11-10T10:00:19.459196Z from submittingParticipant=PAR::sv1::1220fe474038... with 2 view
2026-11-10T10:00:22.656Z participant=sv1Participant | Phase 3: Validating Transaction request=2026-11-10T10:00:19.459196Z with 2 envelope(s)
2026-11-10T10:00:23.494Z participant=sv1Participant | Phase 4: Sending for request=2026-11-10T10:00:19.459196Z with msgId=3481f60b-551a-41bb-b1c4-5b72c4d427ad approved=2, rej
2026-11-10T10:00:27.806Z mediator=globalMediatorSv4 | Phase 5: Received 2 response(s) for request=2026-11-10T10:00:19.459196Z.
2026-11-10T10:00:28.157Z mediator=globalMediatorSv4 | Phase 6: Finalized request=RequestId(2026-11-10T10:00:19.459196Z) with verdict Approve at 2026-11-10T10:01:19.459196Z
2026-11-10T10:00:31.002Z mediator=globalMediatorSv3 | Sent result for request 2026-11-10T10:00:19.459196Z
2026-11-10T10:00:32.178Z participant=sv1Participant | Got result for Transaction request at RequestId(2026-11-10T10:00:19.459196Z): OpenEnvelope( SignedProtocolMessage( Confi
2026-11-10T10:00:32.970Z participant=sv1Participant | Phase 7: Storing at offset=1169 SequencedTransactionAccepted( recordTime = 2026-11-10T10:00:19.459196Z, updateId = 12203
```
Request submitted 18.574, delivered 21.356 (2.8 s); confirmation sent 23.494, at the mediators 27.806 (4.3 s); verdict
28.157, result at the participant 32.178 (4.0 s). The archive reached sv1Participant's ledger (the ACS the test reads)
at 10:00:32.970, 4.6 s after the check gave up. No rejection, no retry, no WARN: only latency. Confirmation that
nothing errored in the window:
```
zcat $A/canton.clog.gz | awk '/"@timestamp":"2026-11-10T09:59:5/,/"@timestamp":"2026-11-10T10:00:5/' > log/11333/win.clog
grep -a -c '"level":"WARN"' log/11333/win.clog
```
```
0
```

## 5. The BFT ordering slowed for all leaders at once from 10:00:05

Script `log/11333/scripts/bftlag.py` (per 5 s: blocks delivered on globalSequencerSv1, log time minus BFT time):
```python
import sys, json, re
from datetime import datetime
pat = re.compile(r"Delivering block (\d+) to post-ordering \(epoch = (\d+), block's BFT time = ([0-9T:.\-]+)Z, block size = (\d+)")
def ts(s): return datetime.fromisoformat(s.replace('Z','+00:00')).timestamp()
b = {}
for line in sys.stdin:
    if 'globalSequencerSv1' not in line or 'Delivering block' not in line: continue
    try: j = json.loads(line)
    except Exception: continue
    m = pat.search(j['message'])
    if not m: continue
    t = ts(j['@timestamp']); lag = t - ts(m.group(3)+'Z')
    k = j['@timestamp'][11:18] + ('0' if int(j['@timestamp'][18]) < 5 else '5')
    c = b.setdefault(k, [0, 0.0, 0.0, 0])
    c[0] += 1; c[1] = max(c[1], lag); c[2] += lag; c[3] += int(m.group(4))
for k in sorted(b):
    n, mx, s, sz = b[k]
    print(f"{k} blocks={n:3d} reqs={sz:3d} lag_avg={s/n:5.2f}s lag_max={mx:5.2f}s")
```
```
grep -a 'globalSequencerSv1' log/11333/win.clog | grep -a 'Delivering block' | python3 -I log/11333/scripts/bftlag.py
```
```
09:59:50 blocks= 95 reqs= 41 lag_avg= 0.22s lag_max= 0.58s
09:59:55 blocks= 88 reqs= 20 lag_avg= 0.25s lag_max= 0.39s
10:00:00 blocks=100 reqs= 29 lag_avg= 0.21s lag_max= 0.33s
10:00:05 blocks= 51 reqs= 12 lag_avg= 0.42s lag_max= 1.27s
10:00:10 blocks= 23 reqs= 14 lag_avg= 1.44s lag_max= 2.50s
10:00:15 blocks= 10 reqs=  8 lag_avg= 1.82s lag_max= 2.82s
10:00:20 blocks= 18 reqs=  7 lag_avg= 2.24s lag_max= 3.25s
10:00:25 blocks= 18 reqs=  8 lag_avg= 1.77s lag_max= 2.09s
10:00:30 blocks= 12 reqs=  9 lag_avg= 1.69s lag_max= 2.33s
10:00:35 blocks= 16 reqs= 10 lag_avg= 2.54s lag_max= 3.48s
10:00:40 blocks= 12 reqs=  8 lag_avg= 3.07s lag_max= 4.28s
10:00:45 blocks= 11 reqs=  7 lag_avg= 2.63s lag_max= 3.12s
```
Load did not rise (requests per 5 s fell with the block rate). Per epoch and original leader
(`log/11333/scripts/bftleader.py`, same parsing plus the `original leader SEQ::svN` field, window 09:59:55 to 10:00:40)
the lag is uniform, so no single slow or blacklisted node and no view change; all four leaders kept their segments:
```
grep -a 'Delivering block' log/11333/win.clog | python3 -I log/11333/scripts/bftleader.py 09:59:55 10:00:40
```
```
epoch 119 leader sv1 blocks= 20 lag_avg= 0.21s lag_max= 0.31s
epoch 119 leader sv2 blocks= 20 lag_avg= 0.21s lag_max= 0.30s
epoch 119 leader sv3 blocks= 20 lag_avg= 0.21s lag_max= 0.29s
epoch 119 leader sv4 blocks= 20 lag_avg= 0.21s lag_max= 0.30s
epoch 120 leader sv1 blocks= 20 lag_avg= 0.73s lag_max= 1.94s
epoch 120 leader sv2 blocks= 20 lag_avg= 0.77s lag_max= 2.50s
epoch 120 leader sv3 blocks= 20 lag_avg= 0.80s lag_max= 2.07s
epoch 120 leader sv4 blocks= 20 lag_avg= 0.77s lag_max= 1.85s
epoch 121 leader sv1 blocks= 17 lag_avg= 2.16s lag_max= 3.48s
epoch 121 leader sv2 blocks= 17 lag_avg= 2.07s lag_max= 3.25s
epoch 121 leader sv3 blocks= 17 lag_avg= 2.08s lag_max= 3.22s
epoch 121 leader sv4 blocks= 17 lag_avg= 2.18s lag_max= 3.47s
```
(Epochs 117 and 118 omitted, same as 119.)

No view change and no blacklisting in the window: every block the four sequencers stored was ordered in view 0, and
every epoch started with an empty blacklist:
```
grep -a 'globalSequencer' log/11333/win.clog | grep -a -o 'DB stored block w/ (epochNumber=[0-9]*, blockNumber=[0-9]*), view number [0-9]*' | awk '{print $NF}' | uniq -c
grep -a 'globalSequencerSv1' log/11333/win.clog | jq -rR 'fromjson? | select(.message|test("New epoch [0-9]+ has started")) | "\(."@timestamp") \(.message|gsub("\n";" ")|.[0:80]) ... \(.message|capture("blacklisted nodes = (?<b>List\\([^)]*\\))").b)"'
```
```
   1818 0
2026-11-10T09:59:51.924Z New epoch 117 has started with leaders = List(SEQ::sv2::12207b244dfeacf40fba1c5e ... List()
2026-11-10T09:59:56.591Z New epoch 118 has started with leaders = List(SEQ::sv3::1220046346dbfaf118d16dc0 ... List()
2026-11-10T10:00:01.007Z New epoch 119 has started with leaders = List(SEQ::sv4::12200a822927e3d52a9d310a ... List()
2026-11-10T10:00:04.852Z New epoch 120 has started with leaders = List(SEQ::sv1::12202eb272fcfbf0e00227ae ... List()
2026-11-10T10:00:16.307Z New epoch 121 has started with leaders = List(SEQ::sv2::12207b244dfeacf40fba1c5e ... List()
2026-11-10T10:00:45.322Z New epoch 122 has started with leaders = List(SEQ::sv3::1220046346dbfaf118d16dc0 ... List()
```

## 6. The orderer's DB writes slowed 40x for about 2.5 minutes

Script `log/11333/scripts/bftdb.py`: on globalSequencerSv1, time from `CompletedBlock commitCertificate ...
blockNumber=N` to `DB stored block w/ (epochNumber=E, blockNumber=N)`, per minute with an argument:
```python
import sys, json, re
from datetime import datetime
def ts(s): return datetime.fromisoformat(s.replace('Z','+00:00')).timestamp()
cc = re.compile(r"CompletedBlock commitCertificate.*blockNumber=(\d+)\)")
st = re.compile(r"DB stored block w/ \(epochNumber=\d+, blockNumber=(\d+)\)")
start = {}; b = {}
for line in sys.stdin:
    if 'globalSequencerSv1/' not in line: continue
    if 'CompletedBlock commitCertificate' not in line and 'DB stored block w/' not in line: continue
    j = json.loads(line); m = j['message']
    a = cc.search(m)
    if a: start[a.group(1)] = ts(j['@timestamp']); continue
    s = st.search(m)
    if s and s.group(1) in start:
        k = j['@timestamp'][11:16] if len(sys.argv) > 1 else j['@timestamp'][11:18] + ('0' if int(j['@timestamp'][18]) < 5 else '5')
        d = ts(j['@timestamp']) - start.pop(s.group(1))
        c = b.setdefault(k, [0, 0.0, 0.0]); c[0] += 1; c[1] = max(c[1], d); c[2] += d
for k in sorted(b):
    n, mx, s = b[k]; print(f"{k} n={n:3d} store_avg={s/n*1000:6.0f}ms store_max={mx*1000:6.0f}ms")
```
```
zcat $A/canton.clog.gz | grep -a 'globalSequencerSv1/' | grep -a 'CompletedBlock commitCertificate\|DB stored block w/' | python3 -I log/11333/scripts/bftdb.py minute | awk '$1>="09:50" && $1<="10:12"'
```
```
09:50 n=116 store_avg=     2ms store_max=    13ms
09:51 n=126 store_avg=    12ms store_max=   572ms
09:52 n=446 store_avg=     8ms store_max=   320ms
09:53 n=979 store_avg=     3ms store_max=    92ms
09:54 n=999 store_avg=     6ms store_max=   139ms
09:55 n=1076 store_avg=     4ms store_max=    92ms
09:56 n=1030 store_avg=     4ms store_max=    82ms
09:57 n=1016 store_avg=     4ms store_max=    44ms
09:58 n=994 store_avg=     4ms store_max=    59ms
09:59 n=1008 store_avg=     5ms store_max=    76ms
10:00 n=297 store_avg=   104ms store_max=   950ms
10:01 n=150 store_avg=   184ms store_max=   800ms
10:02 n=393 store_avg=    81ms store_max=   496ms
10:03 n=956 store_avg=     8ms store_max=   244ms
10:04 n=936 store_avg=     4ms store_max=    56ms
10:05 n=953 store_avg=     4ms store_max=    50ms
10:06 n=948 store_avg=     4ms store_max=   109ms
10:07 n=1103 store_avg=     4ms store_max=   119ms
10:08 n=1005 store_avg=     7ms store_max=    97ms
10:09 n=1175 store_avg=     4ms store_max=    54ms
10:10 n=1101 store_avg=     3ms store_max=    40ms
10:11 n=1083 store_avg=     4ms store_max=    70ms
10:12 n=1038 store_avg=     6ms store_max=    73ms
```
Per 5 s around the failure (`python3 -I log/11333/scripts/bftdb.py` without the argument, on `win.clog`): 3-5 ms up to
10:00:05, then 145-281 ms averages from 10:00:10 to 10:00:45. The episode is 10:00:05 to about 10:02:40, exactly
the window of the failing check; the next test case ("Amulet gets minted", 10:03) ran normally. The Canton log
rate per second fell from 8355-9270 (10:00:00 to 10:00:05) to 746-4792 (10:00:06 to 10:00:23) (`awk -F'"@timestamp":"' 'NF>1{c[substr($2,12,8)]++} END{for(k in c) print k, c[k]}' log/11333/win.clog`).

What slowed the DB is not in the artifacts: the runner artifact `logs-wall-clock-time-6-runner` holds only step
outputs, no CPU or I/O metrics (`grep -i -n 'load average\|vmstat\|iostat\|cpu\|meminfo' log/11333/runner/*.out` matches only nix
`evaluating file` lines). Postgres container logs of the runner pod are in GCP (recipe 13); query
for the user, not run:
```
ci-triage/scripts/gcp_logs.py --project da-cn-splice --start 2026-11-10T09:55:00Z --end 2026-11-10T10:08:00Z --filter 'resource.type="k8s_container"' --filter 'resource.labels.pod_name="self-hosted-k8s-large-runner-jd7mz-workflow"' --filter 'resource.labels.container_name="postgres"'
```
```
gcloud logging read 'timestamp>="2026-11-10T09:55:00Z" AND timestamp<="2026-11-10T10:08:00Z" AND (resource.type="k8s_container") AND (resource.labels.pod_name="self-hosted-k8s-large-runner-jd7mz-workflow") AND (resource.labels.container_name="postgres")' --project da-cn-splice --order asc --limit 200 --format 'value(timestamp,severity,resource.labels.service_name,resource.labels.function_name,httpRequest.status,textPayload,jsonPayload.message)'
```

## 7. Duplicate check

Tracker search for the suite:
```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "UnclaimedActivityRecordIntegrationTest"' --jq '.items[] | "#\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:10]) \(.title[0:110])"'
```
```
#11168 closed/completed 2026-11-03 UnclaimedActivityRecordIntegrationTest: unallocated record expires (5 s) before its vote request is executed
#11165 closed/completed 2026-11-03 SequencerPruningIntegrationTest disables the shared alice, bob and splitwell participants at the global sequen
#9729 closed/completed 2026-08-18 UnclaimedActivityRecordIntegrationTest : UnclaimedActivityRecord not expired after reeenabling trigger
#10175 closed/completed 2026-09-18 UnclaimedActivityRecordIntegrationTest "An UnclaimedActivityRecord gets expired": pausing alice's merge trigge
#9458 closed/not_planned 2026-08-04 UnclaimedActivityRecordIntegrationTest UnclaimedActivityRecord has been created for Creating vote request List
#7458 closed/completed 2026-03-09 UnclaimedActivityRecordIntegrationTest Failed w/ timeout
#7864 closed/completed 2026-04-01 UnclaimedActivityRecordIntegrationTest UnclaimedActivityRecord has been created for Creating vote request List
#8146 closed/duplicate 2026-04-22 GHA Run 24780724791 : job wall-clock-time (7) in scala_test Failed :fire:
#6661 closed/not_planned 2025-12-11 timeout pausing a `CollectRewardsAndMergeAmuletsTrigger`
#6349 closed/completed 2025-11-03 UpdateHistorySanityCheckPlugin - UnclaimedActivityRecordIntegrationTest - ERROR:global:Unexpected transfer inp
#5946 closed/completed 2025-10-15 UpdateHistorySanityCheckPlugin "contains errors"
#6281 closed/completed 2025-10-30 new acs exports: SYNC_SERVICE_ALARM - Request RequestId with failed activeness check is approved.
```
Per hit:
- 9729: same test case and same assertion (`UnclaimedActivityRecord ... shouldBe empty` after re-enabling the trigger),
  release-line-0.7.0, closed 2026-09-01 after no recurrence, no root cause recorded. Same symptom; its cause is not
  known, so not a parent.
- 10175: same test case, different failure (`pause().futureValue` on alice's merge trigger timed out; family H3,
  closed completed; 8a83eb63b5 (#7425) keeps alice's merge trigger paused across both blocks).
- 11168: other test case (unallocated record expired before the vote executed), fixed by 01b3d029eb.
- 7864, 9458: the "has been created" check, not the archive check.
- 7458, 8146, 6661, 6349, 5946, 6281, 11165: other failures (timeout pausing a trigger, sanity plugin, other suites).
`known-families.md` (`grep -n 'UnclaimedActivityRecord' ci-triage/known-families.md`) lists only 10175 (H3) and a
SequencerPruning cascade; no family for a slow archive.

Same-shard cross-check: the slowdown episode is DA BFT on a single runner; 11313 (2026-11-09, BootstrapPackageConfig
close vote after view changes and low Canton log rate) and 11330 (this run's sibling `frontend-wall-clock-time (2)`,
`BFT orderer can't connect to database`) are other symptoms of slow BFT/DB on CI runners, not this mechanism.

## Verdict

New, test timing flake triggered by a 2.5-minute slowdown of the DA BFT orderer's database writes on the runner
(10:00:05 to 10:02:40). Not a duplicate. Not a splice product bug: the trigger fired on time and the transaction
succeeded; the check's 20 s `eventually()` covers the 10 s expiry, the 5 s `clockSkewAutomationDelay` and only about
one 11 s round trip.

Fix (test, small): in `UnclaimedActivityRecordIntegrationTest.scala` give both archive checks (lines 117 and 192 at
e59889cad0) an explicit timeout that covers expiry + `clockSkewAutomationDelay` + a slow round trip, e.g.
`eventually(60.seconds)`; the sibling case needed 13.7 s of its 20 s even on a normal 1 s round trip. No owner from
blame (the `eventually()` is from 2025-07, the 10 s expiry from #7425); unassigned, for the fix agent.

Not verified: what slowed the orderer's DB writes (Postgres or node contention); GCP query above. No sbt run.
