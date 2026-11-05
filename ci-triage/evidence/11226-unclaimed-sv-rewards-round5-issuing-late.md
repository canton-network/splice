# 11226 - UnclaimedSvRewardsScriptIntegrationTest: round 5 SV reward coupon not claimed within the 20 s check (release-line-0.10.0)

- Run 42020381491, release-line-0.10.0 8458061, job 127221957544 `wall-clock-time (0)`, attempt 1.
- Artifact logs-wall-clock-time-0 (13413784354) in `log/11226/art/`; suite lines extracted to `log/11226/suite.jsonl`
  (`zcat canton_network_test.clog.gz | grep -a UnclaimedSvRewardsScriptIntegrationTest`).
- Component: test timing (test code identical on release-line-0.10.0 and main).

## 1. The failure

```
sed -E 's/^[0-9T:.-]+Z //; s/\x1b\[[0-9;]*m//g' log/run-42020381491/127221957544.log | grep -a -A10 'unclaimed_sv_rewards.py summarizes.*\*\*\* FAILED' | head -11
```
```
[info] - unclaimed_sv_rewards.py summarizes claimed, expired, and unclaimed minting rewards for a given beneficiary *** FAILED ***
[info]   Check Coupons for rounds 3, 4 and 5 get claimed for Advance rounds to allow claiming the remaining SV reward coupons Vector(Contract(
[info]     contractId = 00ef6808b6af639b4cc5d77fd8a668459286b8419a3f188bd5363adae5ae3a071fca12122012e8b850a305231dfbdfac4b1dffa979d332d9d7144d496a0f394e89bd902bdd,
...
[info]   )) had size 1 instead of expected size 0 SvRewardCoupons (UnclaimedSvRewardsScriptIntegrationTest.scala:140)
```
Tests: succeeded 64, failed 1. The remaining coupon 00ef6808b6af is the round 5 coupon (payload `Record(fields = 5), 10000`
in its create event; 00ea1abae704 is round 3, 00ca7ed91e7a round 4).

## 2. Timeline (suite.jsonl)

```
jq -r 'select(.message|test("Running clue|Finished clue|Failed clue"))|...' log/11226/suite.jsonl
jq -r 'select(.message|test("StartIssuing|root hash|StartProcessingRewardsV2"))|...' log/11226/suite.jsonl
```
```
12:17:39.323 Running clue: (act) Advance rounds to allow claiming the remaining SV reward coupons
12:17:43.212 ExecuteConfirmedActionTrigger  Completed processing with outcome: executed action ARC_AmuletRules( ... CRARC_MiningRound_StartIssuing ( ... ContractId(00dd0814a60d4e52260e   [round 3]
12:17:43.521 TreasuryService (sv1 wallet)  executing batch ... with inputs Vector(InputSvRewardCoupon(ContractId(00ea1abae704...   [round 3 claimed]
12:17:52.321 ExecuteConfirmedActionTrigger  ... CRARC_MiningRound_StartIssuing ... ContractId(0078192cfdc4d8cc930e   [round 4]
12:17:52.973 TreasuryService (sv1 wallet)  executing batch ... InputSvRewardCoupon(ContractId(00ca7ed91e7a...   [round 4 claimed]
12:18:03.034 Finished clue: (act) Advance rounds to allow claiming the remaining SV reward coupons
12:18:03.034 Running clue: (check) Coupons for rounds 3, 4 and 5 get claimed
12:18:03.171 SummarizingMiningRoundTrigger  Processing Task( summarizingRound = ... 00ef4232def7...   [round 5 summarizing]
12:18:03.185 CalculateRewardsTrigger  The operation 'processTaskWithRetry' failed with a retryable error (full stack trace omitted): FAILED_PRECONDITION: For round 5: our own Scan has not yet computed the root hash.
12:18:23.046 Failed clue: (check) Coupons for rounds 3, 4 and 5 get claimed
12:18:23.673 ExecuteConfirmedActionTrigger  Acquired lock after PT11.777597S
12:18:23.673 ExecuteConfirmedActionTrigger  Completed processing with outcome: executed action ARC_AmuletRules( ... CRARC_StartProcessingRewardsV2 ...
12:18:30.600 ExecuteConfirmedActionTrigger  Completed processing with outcome: executed action ARC_AmuletRules( ... CRARC_MiningRound_StartIssuing ... ContractId(00ef4232def7dbdba7fe   [round 5]
```
The round 5 IssuingMiningRound (0047da54cd03, opensAt 12:18:25.191622Z) is first ingested at 12:18:30.600. The coupon
for round 5 can only be claimed against it, so the check (actAndCheck default `timeUntilSuccess = 20.seconds`,
SpliceTests.scala) ran out 7 s before the round existed.

Per round, summarizing to issuing took: round 3 12:17:40.8 -> 12:17:43.2, round 4 12:17:49.9 -> 12:17:52.3,
round 5 12:18:03.2 -> 12:18:30.6 (27 s). For round 5 the rewards V2 step (CalculateRewardsTrigger waiting on scan's
root hash, then StartProcessingRewardsV2) finished at 12:18:23.7 after the ExecuteConfirmedActionTrigger waited 11.8 s
for its lock; the other rounds acquired it within about 1 s. Why the lock was held that long is not established.
The summarizing round 5 only appears with the third tick at the end of the act, so the whole chain for round 5 lands
inside the 20 s check window, unlike rounds 3 and 4.

Not a cause: the `update ingestion service` DEADLINE_EXCEEDED retries at 12:18:13-12:18:20 are the 120 s stream
deadline, INFO level, all stores reconnected within 0.2 s.

## 3. Duplicate and backport check

- `known-families.md`, `evidence/INDEX.tsv`: no entry for the suite.
- `gh search issues -R DACH-NY/cn-test-failures UnclaimedSvRewardsScriptIntegrationTest`: 9793 (2026-08-31, main,
  "had size 4 instead of expected size 3 sv1 SvRewardCoupons" at line 116, fixed by splice#7288 adding a round check
  and longer wait in the expire step) and 5018 (2025-07). Neither covers the claim check at line 140.
- Test file identical on release-line-0.10.0 and main (`gh api .../contents/...?ref=` both, `diff`: same), and main
  has CalculateRewardsTrigger, so no backport exists and main can hit it too.

## Verdict

New, first hit: test timing. The round 5 coupon needs IssuingMiningRound 5, which is created by three DSO steps
(summarize, rewards V2 processing, StartIssuing) after the last tick of the act; here they took 27 s against a 20 s
check. Fix (test only): give the claim check a longer `timeUntilSuccess` (the expire check above it already uses
80 s). Rare; no assignee evidence (git blame of the check not decisive; 9793's fix by ray-roestenburg-da touched
the expire step).
