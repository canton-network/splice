# 10173 - UnhideAndExpireRewardCouponV2TimeBasedIntegrationTest: a 37-hour time jump leaves a round backlog, the next round-opening wait sees one round too many (run 35349868387)

Same defect as the open public issue splice #7206 ("Fix flake caused by UnhideAndExpireRewardCouponV2TimeBasedIntegrationTest
advancing time", 2026-09-09, no fix yet), whose cn-test-failures parent is not readable from here. Sibling of the
#7261 / 10060 family (WalletMintingDelegation: time advanced too far), fixed there by advancing one round at a time.

- Run: https://github.com/canton-network/splice/actions/runs/35349868387, release-line-0.8.3 9adbf80cd2 ("Backport PR
  #7398 to release-line-0.8.3 (#7410)"), job 105615504911 `simtime (2)`, canton 3.5.18. 11 tests, 1 failed.

## 1. Failing assertion (job console)

```
sed -E 's/\x1b\[[0-9;]*m//g' log/10173/job.log | grep -a -A3 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | grep -v 'still running'
```
```
- Unhide and expire of RewardCouponV2 *** FAILED ***
  Check waiting for open and issuing round automation (should create OpenMiningRound 8, should advance IssuingMiningRounds List()
  for advancing time (7, 7, 8, 9) was not equal to (6, 6, 7, 8) (TimeTestUtil.scala:232)
```
`advanceTimeAndWaitForRoundAutomation` expects exactly one round more than its snapshot; the rounds moved by two.

## 2. Timeline: the backlog created by `advanceTime(37 h)`

```
zcat log/10173/logs-simtime-2/canton_network_test.clog.gz | grep -a UnhideAndExpireRewardCouponV2 | grep -a -E 'advancing time by|Running clue: \(act\)|Failed clue|successfully advanced the rounds' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /'
```
```
13:42:26.480Z advancing time by PT10M10S to 1970-01-01T05:36:31Z
13:42:27.972Z Completed processing with outcome: successfully advanced the rounds and archived round 4
13:42:31.655Z Running clue: (act) Advance past the coupon TTL while Alice is unvetted
13:42:33.156Z advancing time by PT37H to 1970-01-02T18:36:31Z
13:42:34.477Z Running clue: (act) Generate new Alice+Bob activity while Alice is still unvetted
13:42:35.180Z Running clue: (act) advancing time                                   (advanceRoundsToNextRoundOpening snapshots the rounds here)
13:42:35.294Z Completed processing with outcome: successfully advanced the rounds and archived round 5   (backlog catch-up, 0.1 s after the snapshot)
13:42:36.681Z advancing time by PT10M10S to 1970-01-02T18:46:41Z
13:42:38.763Z Completed processing with outcome: successfully advanced the rounds and archived round 6
13:44:06.706Z Failed clue: (check) waiting for open and issuing round automation (should create OpenMiningRound 8, ...)   (90 s budget)
```
After the 37 h jump the AdvanceOpenMiningRoundTrigger has about 220 rounds to catch up on and keeps advancing
while the test's next `advanceRoundsToNextRoundOpening` takes its snapshot (rounds 5..7), advances another 10 min
and then waits for exactly (6, 6, 7, 8). The automation had already produced (7, 7, 8, 9), and never comes back, so
the 90 s check fails. This is the mechanism described in #7206 verbatim.

## 3. Where the 37 h comes from

```
grep -n 'rewardCouponTimeToLiveMicros' apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/config/SvAppConfig.scala
grep -n 'rewardCouponTimeToLive' daml/splice-amulet/daml/Splice/AmuletConfig.daml daml/splice-amulet/daml/Splice/AmuletRules.daml | head -3
```
```
SvAppConfig.scala:263:    rewardCouponTimeToLiveMicros: Long = 36L * 60 * 60 * 1000000, // 36 hours
AmuletConfig.daml:85:    rewardCouponTimeToLive : RelTime -- ^ Time to live for reward coupons, default 36h ...
AmuletRules.daml:547:    rewardCouponTimeToLive = rewardConfig.rewardCouponTimeToLive      (coupons expire at roundClosedAt + TTL)
```
The test jumps 37 h twice (lines 202 and 429) to pass the default 36 h coupon TTL. #7261 solved the same shape in
WalletMintingDelegationTimeBasedIntegrationTest by shortening the delay to 45 min and advancing with
`advanceRoundsUntil(target)`; #7206's own suggestion is the same for this test.

## 4. Fix

Branch `ray/fix-10173-unhide-expire-coupon-ttl` (77c50439a9, off main): the test sets
`initialRewardConfig.rewardCouponTimeToLiveMicros` to 2 h through `ConfigTransforms.updateAllSvAppFoundDsoConfigs_`
(keeping the other reward config defaults), and both TTL steps become
`advanceRoundsUntil(getLedgerTime.toInstant.plus(rewardCouponTtl.plusMinutes(20)))`, i.e. about 14 round
openings with the round automation caught up after each, so no backlog exists when the next
`advanceRoundsToNextRoundOpening` snapshots. Coupons are created 2-6 rounds before each TTL step, well inside 2 h,
and the expiry semantics (roundClosedAt + TTL) are unchanged. scalafmt clean; not compiled or run here.
`advanceRoundsUntil` exists on main (#7261) and on release-line-0.8.3 since the #7401 backport (07d9b37f01), so the
same commit applies there. Public issue #7206 can be closed by this.
