# 10154 - WalletMintingDelegationTimeBasedIntegrationTest fails on LOCAL_VERDICT_INACTIVE_CONTRACTS after advancing 25 h (run 35115826905)

DUPLICATE of cn-test-failures 10060 (public splice issue #7223), FIXED on main by #7261 (0c43730f70,
2026-09-16 08:10Z). The fix is NOT on release-line-0.8.x, where this run happened.

- Run: https://github.com/canton-network/splice/actions/runs/35115826905, release-line-0.8.x ba405bcbf6
  ("Backport PR #7341 to release-line-0.8.x (#7344)"), job 104861329019 `simtime (0)`.
  The same run's `frontend-wall-clock-time (2)` failure is ref 10156 (separate packet).
- Runtime canton: 3.5.17 (`nix/canton-sources.json` at ba405bcbf6).
- 17 tests in 7 suites, 1 failed: `WalletMintingDelegationTimeBasedIntegrationTest / MintingDelegationCollectRewardsTrigger
  should collect rewards for all coupons owned by the beneficiary` with `CommandFailure: Command execution failed.`

## 1. The failing command

```
zcat log/10154/logs-simtime-0/canton_network_test.clog.gz | grep -a 'T15:52:0[2-4]' | grep -a -E 'clue|ERROR'
```
```
15:52:02.555Z DEBUG Finished clue: attempting to advance time by PT25H
15:52:02.555Z DEBUG Finished clue: (act) Advance past the delayed coupon's mintAfter
15:52:04.070Z DEBUG Finished clue: (check) The delayed development fund coupon is collected
15:52:04.071Z DEBUG Running clue: Test that amulets get merge at 2x limit
15:52:04.476Z ERROR HttpCommandException: HTTP 404 Not Found POST at '/api/validator/v0/wallet/transfer-preapproval/send' on 127.0.0.1:5503.
              Command failed, message: LOCAL_VERDICT_INACTIVE_CONTRACTS(11,52a7501b): Rejected transaction is referring to inactive contracts
15:52:04.477Z ERROR Failed clue: Test that amulets get merge at 2x limit
15:52:04.482Z ERROR Test failed: '...should collect rewards for all coupons owned by the beneficiary', message: Command execution failed.
```

Both sv1Participant and aliceParticipant emit the same `LOCAL_VERDICT_INACTIVE_CONTRACTS(11,224b85d6)` at
15:52:03.632 in `canton-simtime.clog`.

## 2. Same symptom as issue #7223

Issue #7223 (public copy of cn-test-failures 10060): the test does `advanceTime(mintDelay.plus(1 hour))` with
`mintDelay = 24 h`, which queues a backlog of round advances; the following `transferPreapprovalSend` then
references an IssuingMiningRound that gets archived under it.

## 3. The fix exists on main only

```
git log origin/main --oneline | grep 7261
git log origin/release-line-0.8.x --oneline | grep -E '7261|WalletMintingDelegation'
git show origin/release-line-0.8.x:apps/app/src/test/scala/.../WalletMintingDelegationTimeBasedIntegrationTest.scala | grep -n -E 'mintDelay|advanceTime'
```
```
0c43730f70 Fix flake in WalletMintingDelegationTimeBasedIntegrationTest caused by time advancing too far (#7261)
(nothing on release-line-0.8.x)
475:      val mintDelay = Duration.ofHours(24)
653:        advanceTime(mintDelay.plus(Duration.ofHours(1))),
```

#7261 changes `mintDelay` to 45 min and replaces the single 25 h jump by `advanceRoundsUntil(mintAfter)`
(one round at a time, new helper in `TimeTestUtil.scala`).

## 4. Verdict

Not a new failure. Backport #7261 to release-line-0.8.x (and 0.8.0/0.8.1 if those lines are still built).
