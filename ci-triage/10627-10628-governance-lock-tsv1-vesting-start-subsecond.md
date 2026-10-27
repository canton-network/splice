# 10627 / 10628 - GovernanceLockTimeBasedIntegrationTest TSv1 vesting start compared with sub-second precision (runs 40359027430, 40358978037)

FIXED on main by #7978 (cddc435d40). Both failing runs are post-merge CI on main at shas that contain #7961 but not
#7978. The test compared the vesting lock's start (end time minus vesting period) with
`getLedgerTime.plusSeconds(1)`, which keeps the ledger time's sub-second part; the lock stores the start in whole
seconds, so the test fails whenever the ledger time is not on a second boundary. #7978 changed the test to
`truncateInstant(getLedgerTime.plusSeconds(1).toInstant)`. The first post-merge main run with #7978 passed the shard.

- Runs: https://github.com/canton-network/splice/actions/runs/40359027430 main 1edbbc4e6d (#7835), job 121699912134
  `simtime (0)` (10627); https://github.com/canton-network/splice/actions/runs/40358978037 main 093005d8db (#7963),
  job 121699722984 `simtime (0)` (10628).
- Runtime canton: 3.7.0-snapshot.20261023.20808.0.vf4863aea.
- Component: test (GovernanceLockTimeBasedIntegrationTest).

## 1. Failing assertion (identical in both jobs)

```
gh api repos/canton-network/splice/actions/jobs/121699912134/logs > log/10627/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/10627/job.log | grep -a -A2 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | head -3
```
```
[info] - SV GovernanceLock created via TSv1 compatibility interface can be unlocked into a vesting GovernanceLock *** FAILED ***
[info]   1970-01-01T09:35:20Z was not equal to 1970-01-01T09:35:20.999Z (GovernanceLockTimeBasedIntegrationTest.scala:153)
[info]   org.scalatest.exceptions.TestFailedException:
```

## 2. The assertion at the failing sha keeps the sub-second part

```
git show 1edbbc4e6d:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/GovernanceLockTimeBasedIntegrationTest.scala | grep -n "vestingStartTime = \|shouldBe$" | head -3
```
```
130:      val vestingStartTime = getLedgerTime.plusSeconds(1)
138:        vestingStartTime = vestingStartTime,
153:      vestingSpec.endTime.minus(vestingSpec.vestingPeriod.microseconds, ChronoUnit.MICROS) shouldBe
```

## 3. #7978 truncates it; the failing shas do not contain #7978

```
git show cn/main:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/GovernanceLockTimeBasedIntegrationTest.scala | grep -n "val vestingStartTime" | head -1
git grep -n "def truncateInstant" cn/main -- apps
git merge-base --is-ancestor cddc435d40 1edbbc4e6d && echo in || echo "7978 NOT in 1edbbc4e6d"
git merge-base --is-ancestor cddc435d40 093005d8db && echo in || echo "7978 NOT in 093005d8db"
```
```
143:      val vestingStartTime = truncateInstant(getLedgerTime.plusSeconds(1).toInstant)
cn/main:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/GovernanceLockTest.scala:43:  protected def truncateInstant(i: Instant): Instant = i.truncatedTo(ChronoUnit.SECONDS)
7978 NOT in 1edbbc4e6d
7978 NOT in 093005d8db
```

## 4. Post-merge main runs: green before (ledger time happened to be on a second), red at the two shas, green with #7978

```
gh api "repos/canton-network/splice/actions/workflows/<post-merge workflow id>/runs?branch=main&per_page=12" --jq '.workflow_runs[]|"\(.id) \(.created_at) \(.head_sha[:10]) \(.conclusion)"'
```
```
40361408548 2026-10-26T16:40:43Z cddc435d40 success
40359027430 2026-10-26T16:25:46Z 1edbbc4e6d failure
40358978037 2026-10-26T16:25:28Z 093005d8db failure
40334163187 2026-10-26T13:50:40Z 50916e27ce success
40315450134 2026-10-26T11:29:28Z 9947261884 success
40313835584 2026-10-26T11:16:27Z bb5b8554a9 failure
40300362525 2026-10-26T09:32:34Z f7526abc4c success
40297500799 2026-10-26T09:10:18Z 29f49d797f success
40283011619 2026-10-26T06:59:01Z 698b908407 success
40085999177 2026-10-24T10:27:42Z 8483372383 success
```
In 40361408548 job 121708210504 `simtime (0)` ran GovernanceLockTimeBasedIntegrationTest
(`TEST-...GovernanceLockTimeBasedIntegrationTest.xml` copied) and reported `Tests: succeeded 20, failed 0`.

## Verdict

Real test bug introduced with #7961, fixed by #7978; not a flake family. Close 10628 as duplicate of 10627 and 10627
as completed (fixed by #7978). Not verified: that no other TSv1 test still compares untruncated times (one green
run only).
