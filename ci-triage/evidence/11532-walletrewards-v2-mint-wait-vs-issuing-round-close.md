# 11532 - WalletRewardsTimeBasedIntegrationTest: bob's V2 minting lands 7.3 s after the 20 s wait; two retries against the issuing round being closed, 8-10 s per ledger round trip (run 44021473992)

Verdict: new (family BC), test timing. Not a duplicate: no family in `known-families.md` and no tracker issue names
this test or clue in 2026. The clue `Bob's balance reflects his own V2 coupon + alice's shared 40%` waits the default
20 s from the moment bob's `CollectRewardsAndMergeAmuletsTrigger` is resumed. Right before, the test advances the sim
clock to 05:29:00.999, which also makes the issuing mining round `00c9f083..e743` due for closing (readyAt
05:28:55.999). sv1's `ExpireIssuingMiningRoundTrigger` submits `DsoRules_MiningRound_Close` at 05:52:55.071; bob's
merge, which uses that round as an input, is submitted at 05:52:56.053. Attempt 1 is rejected
`LOCAL_VERDICT_LOCKED_CONTRACTS` (05:53:05.019), attempt 2 `LOCAL_VERDICT_INACTIVE_CONTRACTS` on the same contract
(05:53:13.671), attempt 3 succeeds at 05:53:22.022. The wait gave up at 05:53:14.674. Each attempt took 8-9 s because
every ledger round trip in the shard took 8-10 s from 05:50:52 to 05:57:04 (about 1 s before and after): the BFT
ordering layer (4 nodes since the previous suite) needed about 0.3 s per block instead of 0.015 s. Why the Canton
process was slow in that window is not visible in the artifacts (section 6).

- Run: https://github.com/canton-network/splice/actions/runs/44021473992, main dbf2ab6c8f ("Pause the reward triggers
  in ScanNewNetworkBackfillingTimeBasedIntegrationTest (#8420)"), schedule, workflow "Integration tests against
  mainnet Daml version", job 133832315070 `build / scala_test_sim_time / simtime (1)`.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620.
- Component: test (wait budget); the slow window is environment, cause open.
- Tuple as given by the user: run 44021473992, simtime (1), ref 11532. The issue body names the same job. The run has
  two more failed jobs (wall-clock-time (3) 133832627487, docker-no-canton (0) 133832631842); they are not part of
  this ref.

## 1. Failing assertion

```
gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/133832315070/logs > log/11532/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/11532/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|contains problems|Run completed' | sed -E 's/^[^Z]*Z //' | sort -u
sed -E 's/\x1b\[[0-9;]*m//g' log/11532/job.log | grep -a -A2 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | head -3
```
```
[info] *** 1 TEST FAILED ***
[info] - list and automatically collect app & validator rewards *** FAILED ***
[info] - should list and automatically collect app & validator rewards *** FAILED ***
[info] Run completed in 26 minutes, 7 seconds.
[info] Tests: succeeded 12, failed 1, canceled 0, ignored 0, pending 0
[info] - should list and automatically collect app & validator rewards *** FAILED ***
[info]   0E-10 was not greater than or equal to 1006.00 delta=0E-10 should be at least V2 coupons (1006.00) (WalletRewardsTimeBasedIntegrationTest.scala:299)
[info]   org.scalatest.exceptions.TestFailedException:
```

The test file is unchanged since 2026-08-06 (#6353) and identical on origin/main (408f7ed9e7).

## 2. Suite and step timeline

```
T=log/11532/logs-simtime-1/canton_network_test.clog.gz
zcat $T | grep -a -E "Starting test suite" | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/",.*//'
```
```
2026-11-17T05:31:46.899Z Starting test suite 'TimeBasedTreasuryIntegrationTestWithoutMerging'...
2026-11-17T05:42:01.258Z Starting test suite 'WalletMintingDelegationTimeBasedIntegrationTest'...
2026-11-17T05:46:04.329Z Starting test suite 'TimeBasedTestNetPreviewIntegrationTest'...
2026-11-17T05:49:58.433Z Starting test suite 'WalletRewardsTimeBasedIntegrationTest'...
2026-11-17T05:54:34.898Z Starting test suite 'WalletTxLogWithRewardsCollectionTimeBasedIntegrationTest'...
```

Test steps from the last time advance on (`[test]` = the suite logger):

```
zcat $T | grep -a 'T05:5[1-4]' | grep -a -E '"logger_name":"(o.l.s.i.t.WalletRewardsTimeBasedIntegrationTest|o.l.s.util.TriggerTestUtil)' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | sed -E 's/\[o.l.s.i.t.WalletRewardsTimeBasedIntegrationTest:WalletRewardsTimeBasedIntegrationTest\]/[test]/' | cut -c1-200 | awk '$1>"2026-11-17T05:52:48.8" && $1<"2026-11-17T05:53:14.69"'
```
```
2026-11-17T05:52:48.860Z Advancing time to 1970-01-01T05:29:00.999Z from 1970-01-01T05:19:11Z for reward automation to run with open time of round 1970-01-01T05:19:01Z for open rounds List(ContractWithState(Contract(\n  contractId = 00a520ba6eb72954da623a4b385
2026-11-17T05:52:48.860Z Running clue: attempting to advance time by PT9M49.999S",[test] DEBUG
2026-11-17T05:52:48.860Z Running clue: wait for the system to quiet down after a round change",[test] DEBUG
2026-11-17T05:52:50.368Z Finished clue: wait for the system to quiet down after a round change",[test] DEBUG
2026-11-17T05:52:50.382Z advancing time by PT9M49.999S to 1970-01-01T05:29:00.999Z",[test] INFO
2026-11-17T05:52:54.233Z Global synchronizer time reached 1970-01-01T05:28:59.999Z on 4 participants after advancing time",[test] INFO
2026-11-17T05:52:54.234Z Finished clue: attempting to advance time by PT9M49.999S",[test] DEBUG
2026-11-17T05:52:54.234Z Running clue: Alice's V2 coupons are shared with correct amounts per beneficiary",[test] DEBUG
2026-11-17T05:52:54.349Z Finished clue: Alice's V2 coupons are shared with correct amounts per beneficiary",[test] DEBUG
2026-11-17T05:52:54.349Z Running clue: Bob has unminted assigned coupon from alice's sharing",[test] DEBUG
2026-11-17T05:52:54.356Z Finished clue: Bob has unminted assigned coupon from alice's sharing",[test] DEBUG
2026-11-17T05:52:54.356Z Running clue: splitwell's external-sharing-mode coupon is neither shared nor collected",[test] DEBUG
2026-11-17T05:52:54.367Z Finished clue: splitwell's external-sharing-mode coupon is neither shared nor collected",[test] DEBUG
2026-11-17T05:52:54.367Z Resuming triggers after block: List(org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger@5f882656)",[o.l.s.util.TriggerTestUtil$] INFO
2026-11-17T05:52:54.367Z Pausing triggers after block: List()",[o.l.s.util.TriggerTestUtil$] INFO
2026-11-17T05:52:54.367Z Running clue: Bob's balance reflects his own V2 coupon + alice's shared 40%",[test] DEBUG
2026-11-17T05:53:14.674Z Failed clue: Bob's balance reflects his own V2 coupon + alice's shared 40%",[test] ERROR
```

The failing clue at the run's sha (default `eventually()`, 20 s):

```
git show dbf2ab6c8f:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/WalletRewardsTimeBasedIntegrationTest.scala | sed -n '293,301p'
```
```
      clue("Bob's balance reflects his own V2 coupon + alice's shared 40%") {
        eventually() {
          val newBobBalance = bobValidatorWalletClient.balance().unlockedQty
          val delta = newBobBalance - prevBobBalance
          // Delta must include at least the V2 coupons; may also include
          // faucet rewards earned after prevBobBalance was captured.
          delta should be >= expectedV2Delta withClue
            s"delta=$delta should be at least V2 coupons ($expectedV2Delta)"
        }
```

## 3. Bob's trigger after the resume: locked, inactive, then success 7.3 s too late

```
zcat $T | grep -a -E 'T05:5(2:5|3:)' | grep -a -E '"logger_name":"[^"]*(TreasuryService|CollectRewardsAndMergeAmuletsTrigger):WalletRewardsTimeBasedIntegrationTest/config=bac37ec7/validator=bobValidator/endUserParty=bob-validator[^"]*"' | grep -a -E 'Resuming trigger|executing batch|Batch failed|failed with a retryable error|Completing operation' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/\\n */ /g; s/WalletRewardsTimeBasedIntegrationTest\/config=bac37ec7\/validator=bobValidator\/endUserParty=bob-validatorbac37ec7-1::12209d7f216e/bobV/' | awk '$1<"2026-11-17T05:53:22.1"' | cut -c1-330
```
```
2026-11-17T05:52:54.367Z Resuming trigger.",[o.l.s.w.a.CollectRewardsAndMergeAmuletsTrigger:bobV] DEBUG
2026-11-17T05:52:56.053Z executing batch AmuletOperationBatch(mergeOperationOpt = AmuletOperation(from = tid:7ff69a9285b5542e285dff06376b2beb, op = CO_MergeTransferInputs(unit()), priority = Low), priority = Low) with inputs Vector(InputValidatorLivenessActivityRecord(ContractId(00bc68eed1c4f8b94c3db5d7a52495a722bacf3d515c
2026-11-17T05:53:05.019Z Batch failed with ABORTED: LOCAL_VERDICT_LOCKED_CONTRACTS(2,b0299ff8): Rejected transaction is referring to locked contracts , failing all operations",[o.l.s.w.t.TreasuryService:bobV] INFO
2026-11-17T05:53:05.020Z The operation 'Collect rewards and merge amulets' failed with a retryable error (full stack trace omitted): category=Some(ContentionOnSharedResources) ErrorInfoDetail(LOCAL_VERDICT_LOCKED_CONTRACTS,HashMap(participant -> bobParticipant, CONTRACT_ID -> List(00c9f083e70c433c65154c0425dbba787b9a557
2026-11-17T05:53:05.763Z executing batch AmuletOperationBatch(mergeOperationOpt = AmuletOperation(from = tid:7ff69a9285b5542e285dff06376b2beb, op = CO_MergeTransferInputs(unit()), priority = Low), priority = Low) with inputs Vector(InputValidatorLivenessActivityRecord(ContractId(00bc68eed1c4f8b94c3db5d7a52495a722bacf3d515c
2026-11-17T05:53:13.671Z Batch failed with NOT_FOUND: LOCAL_VERDICT_INACTIVE_CONTRACTS(11,c7ab4b5a): Rejected transaction is referring to inactive contracts , failing all operations",[o.l.s.w.t.TreasuryService:bobV] INFO
2026-11-17T05:53:13.797Z The operation 'Collect rewards and merge amulets' failed with a retryable error (full stack trace omitted): category=Some(InvalidGivenCurrentSystemStateResourceMissing) ErrorInfoDetail(LOCAL_VERDICT_INACTIVE_CONTRACTS,HashMap(participant -> bobParticipant, CONTRACT_ID -> List(00c9f083e70c433c65154c
2026-11-17T05:53:14.497Z executing batch AmuletOperationBatch(mergeOperationOpt = AmuletOperation(from = tid:7ff69a9285b5542e285dff06376b2beb, op = CO_MergeTransferInputs(unit()), priority = Low), priority = Low) with inputs Vector(InputRewardCouponV2(ContractId(00976666c74a61e48e4ff88516f15252cae2beb39b6680d705c2a0a0ca727
2026-11-17T05:53:22.022Z Completing operation AmuletOperation(from = tid:7ff69a9285b5542e285dff06376b2beb, op = CO_MergeTransferInputs(unit()), priority = Low) with result COO_MergeTransferInputs(Some(ContractId(0017cc7f12b1d329cfce03f1dca7826ea530520536d57c3c0f25e2113e877fd9f8ca12..a407)))",[o.l.s.w.t.TreasuryService:bobV] DEBUG
```

Both rejections name the same contract, `00c9f083e70c...e743` (full error lines: `CONTRACT_ID -> List(00c9f083e70c433c65154c0425dbba787b9a55720dbeb0abe4e6365c78ca0cbe5dca12..e743), reported_by_participant_id -> PAR::sv1::..`).
Success came 27.7 s after the resume, 7.3 s after the clue failed.

## 4. The contended contract is the issuing round that sv1 closes after the same time advance

sv1's `ExpireIssuingMiningRoundTrigger` picks up the round (task ready at sim time 05:28:55.999) right after the
advance:

```
zcat $T | grep -a -E 'T05:52:5[45]' | grep -a -E '00c9f083e70c433c65154c0425dbba787b9a55720dbeb0abe4e6365c78ca0cbe5dca12[0-9a-f]*e743' | grep -a -E '"message":"(Processing|Completing dso delegate task)' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/\\n */ /g; s/"logger_name":"([^":]*).*/[\1]/; s/1220[0-9a-f]{60}/../g' | awk '{print substr($0,1,100) " ... " substr($0,length($0)-55)}'
```
```
2026-11-17T05:52:54.268Z Processing ReadyTask( readyAt = 1970-01-01T05:28:55.999Z, work = AssignedCo ... 3c... ) )",[o.l.s.s.a.d.ExpireIssuingMiningRoundTrigger]
2026-11-17T05:52:54.967Z Completing dso delegate task ReadyTask( readyAt = 1970-01-01T05:28:55.999Z, ... of 467 ms",[o.l.s.s.a.d.ExpireIssuingMiningRoundTrigger]
```

Its submission (trace a23cb589826cd5f3ff1667b0101d09eb) is `DsoRules_MiningRound_Close` on that contract and takes
10.1 s:

```
zcat $T | grep -a -E 'T05:5(2:5|3:0)' | grep -a 'a23cb589826cd5f3ff1667b0101d09eb' > log/11532/sv1-close-trace.jsonl
grep -a -E 'CommandService/SubmitAndWaitForTransaction to 127.0.0.1:15101: (sending request|succeeded)' log/11532/sv1-close-trace.jsonl | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/\\n */ /g' | cut -c1-175
grep -a -E 'T05:52:55.071' log/11532/sv1-close-trace.jsonl | grep -a -o -E 'DsoRules_[A-Za-z_]+|00c9f083e70c433c65154c0425dbba787b9a55720dbeb0abe4e6365c78ca0cbe5dca12[0-9a-f]*e743' | sed -E 's/(00c9f083e70c)[0-9a-f]*(e743)/\1..\2/' | sort | uniq -c
```
```
2026-11-17T05:52:55.071Z Request (tid:a23cb589826cd5f3ff1667b0101d09eb) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:15101: sending request S
2026-11-17T05:53:05.178Z Request (tid:a23cb589826cd5f3ff1667b0101d09eb) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:15101: succeeded(OK)","l
      1 00c9f083e70c..e743
      1 DsoRules_MiningRound_Close
```

So: the helper `advanceTimeForRewardAutomationToRunForCurrentRound` moves the clock to 05:29:00.999 (opensAt of the
newest open round + one tick - 1 ms, TimeTestUtil.scala:340 at dbf2ab6c8f), which is 5 s past the issuing round's
closing time. sv1 submits the close 0.7 s after the advance and it takes 10.1 s to commit. Bob's first merge
(submitted 1 s later) finds the round locked by the close; his second merge still uses the round, now archived; the
third no longer includes it (its inputs start with the two `InputRewardCouponV2`, section 3).

## 5. Every ledger round trip took 8-10 s in that window

Duration of every successful `CommandService/SubmitAndWait*` of all apps in the shard, per minute:

```
zcat $T | grep -a -E 'CommandService/SubmitAndWait[A-Za-z]* to 127.0.0.1:[0-9]+: (sending request|succeeded|failed)' | sed -E 's/\{"@timestamp":"[0-9-]+T([0-9:.]+)Z","message":"Request \(tid:([0-9a-f]+)\) [^ ]+ to 127.0.0.1:([0-9]+): (sending|succeeded|failed).*/\1 \2 \3 \4/' | awk '{split($1,a,":"); t=a[1]*3600+a[2]*60+a[3]; k=$2 $3; if($4=="sending"){s[k]=t; st[k]=$1} else if(k in s){d=t-s[k]; printf "%s %s %.1f %s\n", st[k], $3, d, $4; delete s[k]}}' > log/11532/submit-durations.txt
awk '$4=="succeeded"{m=substr($1,1,5); n[m]++; sum[m]+=$3; if($3>mx[m])mx[m]=$3; if($3>=5)sl[m]++} END{for(m in n) printf "%s n=%d avg=%.1fs max=%.1fs over5s=%d\n", m, n[m], sum[m]/n[m], mx[m], sl[m]}' log/11532/submit-durations.txt | sort | paste - - | column -t
```
```
05:33  n=14  avg=1.5s   max=4.7s   over5s=0   05:34  n=37  avg=1.1s  max=2.1s   over5s=0
05:35  n=35  avg=0.9s   max=1.2s   over5s=0   05:36  n=33  avg=1.0s  max=1.3s   over5s=0
05:37  n=36  avg=1.0s   max=1.3s   over5s=0   05:38  n=43  avg=1.0s  max=2.0s   over5s=0
05:39  n=36  avg=0.9s   max=1.3s   over5s=0   05:40  n=29  avg=0.9s  max=1.2s   over5s=0
05:41  n=33  avg=1.0s   max=1.6s   over5s=0   05:42  n=30  avg=0.9s  max=1.3s   over5s=0
05:43  n=66  avg=1.0s   max=1.7s   over5s=0   05:44  n=88  avg=1.2s  max=2.3s   over5s=0
05:45  n=23  avg=1.1s   max=1.8s   over5s=0   05:46  n=19  avg=2.6s  max=9.0s   over5s=4
05:47  n=21  avg=2.3s   max=16.0s  over5s=3   05:48  n=80  avg=3.6s  max=6.2s   over5s=21
05:49  n=44  avg=5.2s   max=8.8s   over5s=26  05:50  n=46  avg=1.4s  max=8.2s   over5s=2
05:51  n=18  avg=8.9s   max=10.8s  over5s=18  05:52  n=13  avg=8.9s  max=10.7s  over5s=13
05:53  n=5   avg=12.4s  max=23.7s  over5s=5   05:54  n=2   avg=2.5s  max=2.7s   over5s=0
05:55  n=19  avg=8.7s   max=12.0s  over5s=19  05:56  n=6   avg=7.2s  max=7.9s   over5s=6
05:57  n=24  avg=1.2s   max=4.2s   over5s=0
```

Canton side of bob's first attempt (participant trace b0299ff8314b908e11da0806f1c2cca5): no hop is stuck, each of the
three ordering hops takes 1.5-1.7 s and each processing step 0.3-0.8 s.

```
C=log/11532/logs-simtime-1/canton-simtime.clog.gz
zcat $C | grep -a -E '"@timestamp":"2026-11-17T05:5(2:5|3:0)' > log/11532/canton-0552-0553.jsonl
grep -a 'b0299ff8314b908e11da0806f1c2cca5' log/11532/canton-0552-0553.jsonl | grep -a -E 'Phase 1 completed|sends request with id|validated to: Deliver|Phase 3: Validating|Phase 4: Sending|Phase 5: Received|Phase 6: Finalized|Request is finalized' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/\\n */ /g' | awk '{k=substr($0,26,30); c[k]++; if (c[k]<=1) print}' | cut -c1-210
```
```
2026-11-17T05:52:56.614Z Phase 1 completed: Submitting 5 envelopes for Transaction request, submitters bob-validatorbac37ec7-1::12209d7f216e..., command-id fb208684-aa27-45d9-bf52-7c195dca7215",[c.d.c.p.p.Transac
2026-11-17T05:52:56.681Z 'PAR::bobValidator::12209d7f216e...' sends request with id '1c90a373-e363-4b7f-bd04-1d79c7b3d04d' of size 11718 bytes with 5 envelopes to MGR=1, PAR=3.",[c.d.c.s.s.s.GrpcSequencerServic
2026-11-17T05:52:58.294Z At block 4135, the submission request 1c90a373-e363-4b7f-bd04-1d79c7b3d04d at 1970-01-01T05:29:01.024Z validated to: Deliver",[c.d.c.s.b.u.SequencedSubmissionsValidator:sequencer=globalS
2026-11-17T05:52:59.132Z Phase 3: Validating Transaction request=1970-01-01T05:29:01.024Z with 3 envelope(s)",[c.d.c.p.p.TransactionProcessor:participant=bobParticipant/psid=global-domain::12209a65ec3c::36-0] IN
2026-11-17T05:52:59.379Z Phase 4: Sending for request=1970-01-01T05:29:01.024Z with msgId=6f41a4f8-6e53-40e8-83f1-d9790dca8a8f approved=2, rejected=1, abstained=0",[c.d.c.p.p.TransactionProcessor:participant=sv1P
2026-11-17T05:52:59.447Z 'PAR::sv1::1220b56221ad...' sends request with id '6f41a4f8-6e53-40e8-83f1-d9790dca8a8f' of size 1055 bytes with 1 envelopes to MGR=1.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSeq
2026-11-17T05:53:00.918Z At block 4147, the submission request 6f41a4f8-6e53-40e8-83f1-d9790dca8a8f at 1970-01-01T05:29:01.036001Z validated to: Deliver",[c.d.c.s.b.u.SequencedSubmissionsValidator:sequencer=glob
2026-11-17T05:53:01.547Z Phase 5: Received 3 response(s) for request=1970-01-01T05:29:01.024Z.",[c.d.c.s.m.ConfirmationRequestAndResponseProcessor:mediator=globalMediatorSv3/psid=global-domain::12209a65ec3c::36-
2026-11-17T05:53:01.686Z Phase 6: Finalized request=RequestId(1970-01-01T05:29:01.024Z) with verdict ParticipantReject( LocalReject( reason = DecodedCantonError( code = 'LOCAL_VERDICT_LOCKED_CONTRACTS', category =
2026-11-17T05:53:01.739Z 'MED::sv3::122034faedc0...' sends request with id '9c91ab80-eba7-4da6-a40a-6baae098b9f0' of size 1221 bytes with 1 envelopes to PAR=3.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSeq
2026-11-17T05:53:04.402Z Request is finalized as rejected. DecodedCantonError( code = 'LOCAL_VERDICT_LOCKED_CONTRACTS', category = ContentionOnSharedResources, cause = \"Rejected transaction is referring to locke
```

(Trimmed to the first line per message shape; the verdict of MED::sv3 is ordered at block 4157, 05:53:03.4.)

## 6. Ordering layer: 4 nodes since 05:48, consensus per block 0.015 s -> 0.3 s from epoch 129 to 147

The shared sim-time Canton went from 1 to 4 ordering nodes during the previous suite (TimeBasedTestNetPreview) and
stays at 4 for the 1-SV suites after it:

```
zcat $C | grep -a -E '"@timestamp":"2026-11-17T05:(4[5-9]|5[0-7])' | grep -a -E 'sequencer=globalSequencerSv1[/"]' | grep -a -o -E '^\{"@timestamp":"[^"]+","message":"Sending topology of new epoch [0-9]+ to consensus / state transfer \(epochLength= [0-9]+' | sed -E 's/\{"@timestamp":"2026-11-17T([^"]+)Z","message":"/\1 /' | awk '{k=$NF; if(k!=pk) print; pk=k}'
```
```
05:45:03.873 Sending topology of new epoch 93 to consensus / state transfer (epochLength= 20
05:47:45.728 Sending topology of new epoch 110 to consensus / state transfer (epochLength= 60
05:48:06.668 Sending topology of new epoch 112 to consensus / state transfer (epochLength= 80
```

Epoch durations on globalSequencerSv1 (80 blocks per epoch from epoch 112): 7-8 s up to epoch 128, 17.5-33 s for
epochs 129-147 (05:50:52.473 to 05:57:04.064), 7-8 s again from epoch 148.

```
zcat $C | grep -a -E 'sequencer=globalSequencerSv1[/"]' | grep -a -o -E '^\{"@timestamp":"[^"]+","message":"New epoch [0-9]+ has started' | sed -E 's/\{"@timestamp":"2026-11-17T([^"]+)Z","message":"/\1 /' | awk '$1>="05:50:20" {split($1,a,":"); s=a[1]*3600+a[2]*60+a[3]; if(p) printf "%s epoch %s dur=%.1f\n", pt, pe, s-p; p=s; pe=$4; pt=$1}' | paste - - - | column -t
```
```
05:50:22.819  epoch  125  dur=7.2   05:50:30.068  epoch  126  dur=7.0   05:50:37.058  epoch  127  dur=7.6
05:50:44.693  epoch  128  dur=7.8   05:50:52.473  epoch  129  dur=17.5  05:51:09.940  epoch  130  dur=20.6
05:51:30.544  epoch  131  dur=19.4  05:51:49.972  epoch  132  dur=18.0  05:52:08.019  epoch  133  dur=19.9
05:52:27.951  epoch  134  dur=18.2  05:52:46.176  epoch  135  dur=20.1  05:53:06.238  epoch  136  dur=18.9
05:53:25.173  epoch  137  dur=33.0  05:53:58.123  epoch  138  dur=29.8  05:54:27.962  epoch  139  dur=11.2
05:54:39.169  epoch  140  dur=16.2  05:54:55.404  epoch  141  dur=18.3  05:55:13.707  epoch  142  dur=19.8
05:55:33.517  epoch  143  dur=18.2  05:55:51.684  epoch  144  dur=17.6  05:56:09.287  epoch  145  dur=17.8
05:56:27.088  epoch  146  dur=18.3  05:56:45.339  epoch  147  dur=18.7  05:57:04.064  epoch  148  dur=7.8
05:57:11.869  epoch  149  dur=7.3   05:57:19.164  epoch  150  dur=8.7   05:57:27.851  epoch  151  dur=7.6
```

Where the time goes, for the blocks globalSequencerSv2 leads (sampled windows): the empty-block timer is unchanged,
the PBFT round (pre-prepare stored to block ordered: two message exchanges and two DB writes) goes from about 16 ms
to 0.3-0.5 s.

```
zcat $C | grep -a -E '"@timestamp":"2026-11-17T05:(50:[0-9]{2}|51:[0-2]|56:5|57:[0-2])' | grep -a 'sequencer=globalSequencerSv2/' | grep -a -E 'Initiating pull for block|BlockInactivityTimeout: block timeout reached|DB stored pre-prepare|OrderedBlockStored: DB stored block' | sed -E 's/\{"@timestamp":"2026-11-17T([^"]+)Z","message":"/\1 /' | awk '{split($1,a,":"); s=a[2]*60+a[3]; b=""; if(match($0,/blockNumber=[0-9]+/)) b=substr($0,RSTART+12,RLENGTH-12); else if(match($0,/for block [0-9]+/)) b=substr($0,RSTART+10,RLENGTH-10); if($0~/Initiating pull/){pull[b]=s} else if($0~/BlockInactivityTimeout/){lastto=s} else if($0~/pre-prepare/){pp[b]=s; if(b in pull){ own[b]=1; tt[b]=lastto}} else if($0~/OrderedBlockStored/ && (b in own)){m=substr($1,1,7); n[m]++; a1[m]+=tt[b]-pull[b]; a2[m]+=pp[b]-tt[b]; a3[m]+=s-pp[b]}} END{for(m in n) printf "%s0 own_blocks=%d pull_to_timeout=%.3fs timeout_to_preprepare_stored=%.3fs preprepare_to_ordered=%.3fs\n", m, n[m], a1[m]/n[m], a2[m]/n[m], a3[m]/n[m]}' | sort | column -t
```
```
05:50:00  own_blocks=17  pull_to_timeout=0.534s  timeout_to_preprepare_stored=0.004s  preprepare_to_ordered=0.016s
05:50:10  own_blocks=21  pull_to_timeout=0.406s  timeout_to_preprepare_stored=0.045s  preprepare_to_ordered=0.020s
05:50:20  own_blocks=27  pull_to_timeout=0.286s  timeout_to_preprepare_stored=0.060s  preprepare_to_ordered=0.017s
05:50:30  own_blocks=29  pull_to_timeout=0.265s  timeout_to_preprepare_stored=0.069s  preprepare_to_ordered=0.017s
05:50:40  own_blocks=25  pull_to_timeout=0.326s  timeout_to_preprepare_stored=0.047s  preprepare_to_ordered=0.021s
05:50:50  own_blocks=16  pull_to_timeout=0.390s  timeout_to_preprepare_stored=0.067s  preprepare_to_ordered=0.168s
05:51:00  own_blocks=11  pull_to_timeout=0.435s  timeout_to_preprepare_stored=0.106s  preprepare_to_ordered=0.340s
05:51:10  own_blocks=10  pull_to_timeout=0.495s  timeout_to_preprepare_stored=0.101s  preprepare_to_ordered=0.353s
05:51:20  own_blocks=9   pull_to_timeout=0.349s  timeout_to_preprepare_stored=0.228s  preprepare_to_ordered=0.520s
05:56:50  own_blocks=10  pull_to_timeout=0.444s  timeout_to_preprepare_stored=0.105s  preprepare_to_ordered=0.312s
05:57:00  own_blocks=18  pull_to_timeout=0.349s  timeout_to_preprepare_stored=0.066s  preprepare_to_ordered=0.109s
05:57:10  own_blocks=28  pull_to_timeout=0.283s  timeout_to_preprepare_stored=0.058s  preprepare_to_ordered=0.016s
05:57:20  own_blocks=23  pull_to_timeout=0.380s  timeout_to_preprepare_stored=0.035s  preprepare_to_ordered=0.015s
```

With 4 leaders and blocks delivered in block-number order, a submission waits for the other leaders' (mostly empty)
blocks, so 0.3 s more per block is 1.5-1.7 s per ordering hop and 8-10 s per transaction.

Not the BFT rejection family (B / 11255) and not the clock stall (BB):

```
zcat $C | grep -a -E '"@timestamp":"2026-11-17T05:(49:5|5[0-4])' | grep -a -c -E 'P2P connectivity is not ready|currently blacklisted|MAX_SEQUENCING_TIME_EXCEEDED|Task fetching remote time did not complete'
zcat $T | grep -a -E '"@timestamp":"2026-11-17T05:5[0-4]' | grep -a -c -E 'Task fetching remote time did not complete|SUBMISSION_ALREADY_IN_FLIGHT|circuit breaker'
```
```
0
0
```

What made the Canton process slow from 05:50:52 to 05:57:04 is open. The Canton log volume in the window is lower,
not higher (20 s samples: 161479 lines at 05:50:30-49, 65464 at 05:52:10-29, 135484 at 05:57:10-29), no message shape
is more frequent inside the window, `canton-simtime.out` has no GC lines. That matches the host slowdown of 11375
(same symptom: every BFT step slower inside one window), but its confirming check (a second shard of the same run
slow in the same window) could not be run: the only other Canton shard that failed, wall-clock-time (3), has a 310 MB
artifact and `gh run download` answered `HTTP 503: 503 Egress is over the account limit`. Its job log shows an
unrelated checkErrors line at 05:36:56 (`DbStorage.createDatabase failed, retrying`, family AG), nothing about the
05:51-05:57 window.

## Verdict

- New, first hit of this test in the tracker for 2026 (`gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue WalletRewardsTimeBasedIntegrationTest'`
  returns only 6192, 6023, 5967 from 2025-10, other causes; no hit for `"should be at least V2 coupons"`).
  No family signature matches; closest are AR and BA (default 20 s wait around automation that needs more than one
  round trip) and the 11375 one-off (slow window on the host), neither a duplicate parent for this.
- Flake. The test builds the contention itself: the last time advance makes the issuing round due, and the wait for
  bob's minting starts while sv1 is closing that round, so bob's trigger needs up to three submissions. At 1 s per
  round trip that is 3-4 s; at the 8-10 s of this window it is 27.7 s against a 20 s wait.
- Fix (test, one line): `eventually(60.seconds)` for the final balance clue. Worktree `~/Projects/fix-11532`, branch
  `pperek/fix-11532-wallet-rewards-v2-mint-wait`, commit edcac5eb9a off origin/main 408f7ed9e7. Not pushed.
- NOT verified: the suite was not run and the change was not compiled (no Canton locally: Docker is not running,
  `start-canton.sh -p local` does not work on this Mac; nothing was started). The pre-commit scalafmt and copyright
  hooks passed on the commit; the always-run hook `check-daml-return-types` fails in the fresh worktree on Daml
  choices this commit does not touch and was skipped with `SKIP=check-daml-return-types`.
- NOT explained: the cause of the slow window in the Canton process, and whether other shards of the run saw it.
  The longer wait does not address that; a slower window would still fail.
- Whether the lock/inactive pair happens on every run (and normally just costs 2-3 s) was not checked against a
  passing run.
