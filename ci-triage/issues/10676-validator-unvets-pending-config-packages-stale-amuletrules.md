# 10676 - validator unvets the packages of a just-executed config vote because its cached AmuletRules is stale (run 40494870697)

New, production regression from #7922 ("Fix unvetting to not unvet accepted but not effective packages", ef7c41a0b6,
the commit of this run). Main, job `resource-intensive (1)`, canton 3.7.0-snapshot.20261023.20808.0.vf4863aea.
BootstrapPackageConfigIntegrationTest failed 1 of 3: aliceValidator's participant ends up vetting the new package
versions from 11:32:55.782663Z (vote execution time) instead of the scheduled 11:32:49.796870Z, because
`ValidatorPackageVettingTrigger` unvetted them at 11:32:56.6 and re-vetted them at 11:32:58.6.

- Run: https://github.com/canton-network/splice/actions/runs/40494870697, main ef7c41a0b6 (#7922), job 122158220021
  `resource-intensive (1)`.
- Runtime canton: 3.7.0-snapshot.20261023.20808.0.vf4863aea.
- Component: splice validator app (`PackageVettingTrigger` unvetting input, `ValidatorPackageVettingTrigger` reading
  AmuletRules and vote requests from Scan separately).

## 1. Failing assertion

```
gh api repos/canton-network/splice/actions/jobs/122158220021/logs > log/10676/job.log
sed -n 11933,11937p log/10676/job.log | sed -E 's/^[0-9T:.-]+Z //' | cut -c1-200
```
```
[info] - Bootstrap with specific versions and then upgrade to latest *** FAILED ***
[info]   forEvery failed, because: 
[info]     at index 0, List(None, Some(2026-10-27T11:32:49.796870Z), Some(2026-10-27T11:32:49.796870Z)) did not contain element Some(2026-10-27T11:32:55.782663Z) (BootstrapPackageConfigIntegrationTest.scala:494), 
[info]     at index 1, List(None, Some(2026-10-27T11:32:49.796870Z), Some(2026-10-27T11:32:49.796870Z)) did not contain element Some(2026-10-27T11:32:55.782663Z) (BootstrapPackageConfigIntegrationTest.scala:494), 
```
The expected list starts with `None`, which is aliceValidator's own entry in the clue (test lines 309-314).

## 2. First failure of this shard on main is #7922's own commit

```
gh api "repos/canton-network/splice/actions/workflows/<post-merge wf>/runs?per_page=30&branch=main" ... resource-intensive (1) per run
```
```
40494870697 ef7c41a0b6 2026-10-27T11:16:13Z completed ri1=failure
40492423066 696de9b01c 2026-10-27T10:57:02Z completed ri1=success
40361408548 cddc435d40 2026-10-26T16:40:43Z completed ri1=success
... (10 earlier main runs: success)
```

## 3. Alice's vetting proposals: vetted from 49.796, dropped, re-vetted from 55.78

```
unzip -p log/10676/a.zip canton_network_test.clog.gz | gunzip -c > log/10676/test.clog
grep -a -E '"message":"(Submitted proposal VettedPackages|Vetting packages)' log/10676/test.clog | grep -a "validator=aliceValidator" | grep -a -E "T11:3[2-5]"   # timestamps in each message listed
```
```
2026-10-27T11:32:31.921Z Vetting packages | 2026-10-27T11:32:49.796870Z
2026-10-27T11:32:51.868Z Submitted proposal VettedPackages | 2026-10-27T11:32:49.796870Z
2026-10-27T11:32:53.000Z Submitted proposal VettedPackages |
2026-10-27T11:32:56.609Z Vetting packages | 2026-10-27T11:30:44.670020Z
2026-10-27T11:32:56.644Z Submitted proposal VettedPackages |
2026-10-27T11:32:58.591Z Vetting packages | 2026-10-27T11:32:55.782663Z
2026-10-27T11:32:58.614Z Submitted proposal VettedPackages | 2026-10-27T11:32:55.782663Z
```

## 4. The vote closes at 55.78; alice's trigger then sees the vote gone but the old AmuletRules

The vote (expiration 19 s, `Some(scheduledTime)`) is listed as ready by `CloseVoteRequestTrigger` only at
`now - clockSkewAutomationDelay`, i.e. about 5 s after expiry, and executes at 11:32:55.78.
```
grep -a -E "T11:32:5[2-6]" log/10676/test.clog | grep -a "validator=aliceValidator" | grep -a -E "Running package (un)?vetting|Vetting for schedule" | cut -c1-200
```
```
2026-10-27T11:32:56.608Z Running package vetting as the input has changed ...
2026-10-27T11:32:56.609Z Vetting for schedule List((2026-10-27T11:30:44.670020Z,List((splice-wallet-payments,0.1.19), (splice-amulet-name-service,0.1.20), (splice-wallet,0.1.20), (splice-amulet,0.1.19), (splice-validator-lifecycle,0.1.5)))) from amulet rules ...
2026-10-27T11:32:56.619Z Running package unvetting as the input has changed from Set(PackageConfig(0.1.19, ...), PackageConfig(0.1.25, 0.1.26, 0.1.31, 0.1.10, ...)) to ...
```
At 56.6 the vote request is gone (it was the only pending config), and the AmuletRules the trigger reads still has
the old schedule (only the 11:30:44 entry with 0.1.19). The unvetting therefore no longer counts the new versions as
required and removes them. At 58.59 the new AmuletRules arrives (schedule entry 11:32:55.782663) and they are vetted
again from that time.

## 5. Why the two inputs disagree: the validator reads them from Scan, AmuletRules cached

```
git show cn/main:apps/validator/src/main/scala/org/lfdecentralizedtrust/splice/validator/automation/ValidatorPackageVettingTrigger.scala | sed -n 41,55p
git grep -n amuletRulesCacheTimeToLive cn/main -- apps/scan/src/main/scala/org/lfdecentralizedtrust/splice/scan/admin/api/client/BftScanConnection.scala | head -2
```
```
    scanConnection.getAmuletRulesDomain()(tc)
  override def getAmuletRules()(implicit tc: TraceContext) =
    scanConnection.getAmuletRules()
  override def getVoteRequests()(implicit
    scanConnection.getVoteRequests()
  override def getDsoRules()(implicit
    scanConnection.getDsoRules()
BftScanConnection.scala:118:    override protected val amuletRulesCacheTimeToLive: NonNegativeFiniteDuration,
BftScanConnection.scala:234:      cachedAmuletRules: Option[ContractWithState[AmuletRules.ContractId, AmuletRules
```
`getVoteRequests` is a direct `listVoteRequests` call; `getAmuletRules` goes through the AmuletRules cache. Before
#7922 the unvetting input was only the current AmuletRules config, so a vote closing did not trigger unvetting; since
#7922 the pending vote configs are part of the input (PackageVettingTrigger.scala, `pendingPackageConfigs`), so the
vote disappearing before the cache refreshes runs unvetting on an inconsistent view.

## Verdict

New, production regression from #7922 on main (validator only; SVs read from their own DSO store and vetted
correctly). Effect in production: when a config vote executes, a validator can unvet the new package versions for
a few seconds (until its AmuletRules cache refreshes) and re-vet them from a later time; transactions needing those
versions in that window fail. Fix belongs in the app, owner #7922's author: do not drop a pending config from the
unvetting input until the AmuletRules contract id has changed (or read AmuletRules uncached when the vote set
changes, or skip unvetting while the AmuletRules cache is older than the vote list). No test change. Not verified:
the exact cache TTL in the test config, and whether the same happens without the 5 s CloseVoteRequest delay.
