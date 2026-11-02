# 10169 - UnsupportedPackageVettingIntegrationTest: sv1's ReceiveSvRewardCouponTrigger fails to downgrade DsoRules after the test votes the package config down (run 35325516312)

DUPLICATE of cn-test-failures 9965, FIXED on main by #7299 "Allow downgrades in unsupported package vetting test"
(d3499d1439, 2026-09-15). The fix is on neither release-line-0.8.3 nor release-line-0.8.x.

- Run: https://github.com/canton-network/splice/actions/runs/35325516312, release-line-0.8.3 5f97fba71b
  ("Copy logback.xml from Canton upstream (#7383)"), job 105537809798 `wall-clock-time (1)`. Canton 3.5.18,
  dso-governance 0.1.29 on this line.
- All 25 tests pass; `checkErrors` fails `canton_network_test.clog` on one WARN + one ERROR from sv1's SV app.

## 1. Flagged lines

```
grep -a -B400 'contains problems' log/10169/job.log | grep -a '@timestamp' | grep -a -v 'ignore this line'
```
```
09:19:40.798Z WARN  ReceiveSvRewardCouponTrigger:UnsupportedPackageVettingIntegrationTest/SV=sv1
  The operation 'processTaskWithRetry' failed with a non-retryable error, not retrying: statusCode=INVALID_ARGUMENT
  INTERPRETATION_UPGRADE_ERROR_TRANSLATION_FAILED(8,3a245230): Translation of contract 00caa6573b3b... to a value of type
  Splice.DsoRules:DsoRules@dfe10251 fails. Reason: Type-checking fails  Found an optional contract field with a value of
  Some at index 13, may not be dropped during downgrading.  Expected type: Splice.DsoRules:DsoRulesConfig
09:19:40.801Z ERROR ReceiveSvRewardCouponTrigger:...SV=sv1  Skipping processing of Task(dsoRulesCid = 00caa6573b3b..., svRewardWeight = 10000, rewardState = ..., round = ... OpenMiningRound ...)
```

## 2. Mechanism

The test "SVs and validators unvet package versions above the configured PackageConfig"
(`UnsupportedPackageVettingIntegrationTest.scala:153-232`) votes the AmuletConfig package config down to
dsoGovernance 0.1.25 and expects the SV and validator apps to unvet the newer versions. Once sv1 has unvetted
0.1.26+, its triggers prepare DsoRules choices against the 0.1.25 package, i.e. Canton must downgrade the
existing DsoRules contract to that version. Field 13 of `DsoRulesConfig` (the SV operations switch-over times
added by #7039 on 2026-09-01) is `Some(...)` on this line, and Daml refuses to drop a populated optional field on
downgrade, so every DsoRules-using submission from sv1 fails with a non-retryable INVALID_ARGUMENT until the
test ends. The reward coupon trigger happened to be the one that logged it at ERROR. The test's own comment
already records the same class of problem for `rewardConfig` ("versions below 0.1.19 lack the rewardConfig
field and fail upgrade translation").

## 3. The fix exists on main only

```
git show d3499d1439 --stat --format='%h %ad %s' --date=short | head -3
git show d3499d1439 -- apps/app/src/test/scala/.../UnsupportedPackageVettingIntegrationTest.scala | grep '^[-+]' | grep -v '^+++\|^---'
for b in release-line-0.8.3 release-line-0.8.x; do git merge-base --is-ancestor d3499d1439 origin/$b && echo "on $b" || echo "NOT on $b"; done
```
```
d3499d1439 2026-09-15 Allow downgrades in unsupported package vetting test (#7299)      (PR body: fixes cn-test-failures 9965)
+        // We deliberately unvet the latest version so make sure downgrades are possible.
+        (_, config) =>
+          ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
+            _.copy(initialSvOperationsSwitchOverTimes = None)
+          )(config),
NOT on release-line-0.8.3
NOT on release-line-0.8.x
```
With `initialSvOperationsSwitchOverTimes = None` the optional field is `None` and the downgrade translates.

## 4. Verdict

Not a new failure: 9965 recurring on the release lines that did not get #7299. Deterministic on any run of this
suite there, not a flake. Backport prepared: branch `ray/backport-7299-release-line-0.8.3` (87d76de621,
`git cherry-pick -x -s d3499d1439` onto origin/release-line-0.8.3, clean); release-line-0.8.x needs the same.
