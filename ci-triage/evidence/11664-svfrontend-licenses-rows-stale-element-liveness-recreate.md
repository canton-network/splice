# 11664 - SvFrontendIntegrationTest "can prepare an onboarding secret for new validator": StaleElementReferenceException while reading the validator license rows outside any retry (run 44557022463)

Verdict: new (family BN), test race against the 1 s poll of the SV UI; not a duplicate. 30 tests passed, 1 failed.
The test reads the rows of the "Validator Licenses" table once, outside `eventually`
(`SvFrontendIntegrationTest.scala:155`, `val licenseRows = getLicensesTableRows`). scalatest-selenium `findAll`
is not atomic: one `FindElements` call, then two to three WebDriver round trips per row to build the typed element
(about 95 ms per row here, 7 rows). In that window the SV UI polled `GET /v0/admin/validator/licenses` (the UI polls
every second) and got a new contract for the license of the sv2 validator: sv2Validator's ReceiveFaucetCouponTrigger
had exercised `ValidatorLicense_RecordValidatorLivenessActivity` at 16:00:39.035, which archives the license and
creates a new one (ingested by sv1 at 16:00:40.205). The table rows are keyed by contract id
(`apps/common/frontend/src/components/ValidatorLicenses.tsx`, `key={license.contractId}`), so React unmounted the
row of the archived contract; it was the fifth of the seven references the test still held, and the next attribute
read on it failed (16:00:40.896). No retry wraps the read, so the exception failed the test.

- Run: https://github.com/canton-network/splice/actions/runs/44557022463, main c34a2a5b91 ("Use the new ledger api
  json in bulk storage (#8566)"), workflow "CI post-merge to main or release-line branches", job 135676253752
  `ci / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (2)`.
- Runtime canton: 3.7.4.
- Component: test (SvFrontendIntegrationTest, ValidatorLicensesFrontendTestUtil).
- Tuple as given: run 44557022463, frontend-wall-clock-time (2), ref 11664. The run has exactly one failed job and
  the issue body names it.

## 1. Failing assertion

One failed test; the job-level "custom container implementation failed" annotation is only the wrapper of the sbt
exit code.

```
gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/135676253752/logs > log/11664/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/11664/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed' | sed -E 's/^[^Z]*Z //' | sort -u
```
```
[info] *** 1 TEST FAILED ***
[info] - can prepare an onboarding secret for new validator *** FAILED ***
[info] - should can prepare an onboarding secret for new validator *** FAILED ***
[info] Run completed in 24 minutes, 43 seconds.
[info] Tests: succeeded 30, failed 1, canceled 0, ignored 0, pending 0
```

The exception and the splice frames: the element is a `validator-licenses-table-row`, the failing command is the
`type` attribute read that scalatest-selenium does per element inside `findAll`, called from line 155 of the test.

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11664/job.log | grep -a -A60 'should can prepare an onboarding secret for new validator \*\*\* FAILED' | sed -E 's/^[^Z]*Z //' | grep -a -E 'StaleElement|Command:|Element:|WebBrowser|ValidatorLicensesFrontendTestUtil|SvFrontendIntegrationTest\.\$anonfun' | cut -c1-260
```
```
[info]   org.openqa.selenium.StaleElementReferenceException: The element with the reference ced381df-b6c9-49de-83e4-199cae351d50 is stale; either its node document is not the active document, or it is no longer connected to the DOM
[info] Command: [9e51c7d2-ebec-400f-b1c4-6aee89b18341, getElementAttribute ***name=type, id=ced381df-b6c9-49de-83e4-199cae351d50***]
[info] Element: [[FirefoxDriver: firefox on linux (9e51c7d2-ebec-400f-b1c4-6aee89b18341)] -> class name: validator-licenses-table-row]
[info]   at org.scalatestplus.selenium.WebBrowser$TagMeta.typeValue$lzycompute(WebBrowser.scala:1802)
[info]   at org.scalatestplus.selenium.WebBrowser$TagMeta.typeValue(WebBrowser.scala:1801)
[info]   at org.scalatestplus.selenium.WebBrowser.isInputField(WebBrowser.scala:1809)
[info]   at org.scalatestplus.selenium.WebBrowser.org$scalatestplus$selenium$WebBrowser$$isTextField(WebBrowser.scala:1816)
[info]   at org.scalatestplus.selenium.WebBrowser.org$scalatestplus$selenium$WebBrowser$$createTypedElement(WebBrowser.scala:3253)
[info]   at org.scalatestplus.selenium.WebBrowser$Query.$anonfun$findAllElements$1(WebBrowser.scala:2967)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.ValidatorLicensesFrontendTestUtil.getLicensesTableRows(ValidatorLicensesFrontendTestUtil.scala:9)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.ValidatorLicensesFrontendTestUtil.getLicensesTableRows$(ValidatorLicensesFrontendTestUtil.scala:8)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.SvFrontendIntegrationTest.$anonfun$new$15(SvFrontendIntegrationTest.scala:155)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.SvFrontendIntegrationTest.$anonfun$new$14(SvFrontendIntegrationTest.scala:105)
```

The read at line 155 sits between two `actAndCheck` blocks, with no retry around it (same text on origin/main
6c71d08233):

```
git show c34a2a5b91:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/SvFrontendIntegrationTest.scala | sed -n 153,158p
git show c34a2a5b91:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/ValidatorLicensesFrontendTestUtil.scala | sed -n 8,10p
```
```
        )

        val licenseRows = getLicensesTableRows
        val newValidatorParty = allocateRandomSvParty("splice-client", Some(2))

        actAndCheck(
  def getLicensesTableRows(implicit webDriver: WebDriverType): Seq[Element] = {
    findAll(className("validator-licenses-table-row")).toList
  }
```

## 2. Which test was running

The test started 16:00:32.317; the secret check finished 16:00:40.504 and the exception came 395 ms later.

```
T=log/11664/logs-frontend-wall-clock-time-2/canton_network_test.clog.gz
zcat $T | grep -a -E "Starting '|Test (succeeded|failed): |clue: \((act|check)\)|Caught error|clue: (Dumping|Saving)" | grep -a -E '"@timestamp":"2026-11-19T16:00:(3[2-9]|4[01])' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200 | sed -n 5,21p
```
```
2026-11-19T16:00:32.317Z Starting 'SvFrontendIntegrationTest/SV UIs should can prepare an onboarding secret for new validator'...",
2026-11-19T16:00:32.318Z Running clue: (act) sv1 operator can login and browse to the validator onboarding tab",
2026-11-19T16:00:35.514Z Finished clue: (act) sv1 operator can login and browse to the validator onboarding tab",
2026-11-19T16:00:35.514Z Running clue: (check) We see a button for creating onboarding secret",
2026-11-19T16:00:35.702Z Finished clue: (check) We see a button for creating onboarding secret",
2026-11-19T16:00:35.704Z Running clue: (act) fill the party hint field and click on the button to create an onboarding secret",
2026-11-19T16:00:36.092Z Finished clue: (act) fill the party hint field and click on the button to create an onboarding secret",
2026-11-19T16:00:36.092Z Running clue: (check) a new secret row is added",
2026-11-19T16:00:40.504Z Finished clue: (check) a new secret row is added",
2026-11-19T16:00:40.899Z Caught error org.openqa.selenium.StaleElementReferenceException: The element with the reference ced381df-b6c9-49de-83e4-199cae351d50 is stale; either its node document is not 
2026-11-19T16:00:40.899Z Running clue: Dumping frontend debug info",
2026-11-19T16:00:40.899Z Running clue: Saving screenshot",
2026-11-19T16:00:40.959Z Finished clue: Saving screenshot",
2026-11-19T16:00:40.959Z Running clue: Saving page source",
2026-11-19T16:00:41.050Z Finished clue: Saving page source",
2026-11-19T16:00:41.101Z Finished clue: Dumping frontend debug info",
2026-11-19T16:00:41.153Z Test failed: 'SvFrontendIntegrationTest/SV UIs should can prepare an onboarding secret for new validator', message: The element with the reference ced381df-b6c9-49de-83e4-199c
```

## 3. Browser: the license poll lands between FindElements and the per-row reads

Epoch ms 1795104040504 = 16:00:40.504. `FindElements` returns seven rows at .554; the UI sends its license poll at
.749 and gets the answer at .813; the read on the fifth reference (`ced381df`) at .895 is stale.

```
B=log/11664/logs-frontend-wall-clock-time-2/browser.org.lfdecentralizedtrust.splice.integration.tests.SvFrontendIntegrationTest.sv1.0.1.log.gz
zcat $B | awk -F'\t' '$1>=1795104040500 && $1<=1795104040910' | grep -a -E 'Marionette.*(FindElements|\[1,198,|ced381df)|sv (calling GET [^ ]*validator/licenses|got response[^[]*validator_licenses)' | sed -E 's/"script":"[^"]*("|$)/"script":".."/; s/RemoteAgent.*"text":"/console /; s/\{"element-6066-11e4-a52e-4f735466cecf":"([0-9a-f]{8})[0-9a-f-]*"\}/\1/g' | cut -c1-230
```
```
1795104040507	Marionette	DEBUG	0 -> [0,198,"WebDriver:FindElements",{"using":"css selector","value":".validator\\-licenses\\-table\\-row"}]
1795104040554	Marionette	DEBUG	0 <- [1,198,null,[0c41169a,3c45a983,8e9178cf,763ba80c,ced381df,31467c60,593ce1b2]]
1795104040749	console sv calling GET http://localhost:5114/api/sv/v0/admin/validator/licenses?limit=10 with body: undefined [00-58a29219abc15c1333963e1cd816e3f8-1ea7379ca4297b9d-01]","timestamp":1795104040748,"stackTrace":{"callFr
1795104040813	console sv got response with status code 200 and body: \"{\\\"validator_licenses\\\":[{\\\"template_id\\\":\\\"c5203dee918350373a993328e34587211 ... 35c1ca12122092f18f200adfbb0081a65bc618ce7bb39b376f77790a7874addb2ce
1795104040895	Marionette	DEBUG	0 -> [0,211,"WebDriver:ExecuteScript",{"args":[ced381df,"type"],"script":".."}]
1795104040896	Marionette	DEBUG	0 <- [1,211,{"error":"stale element reference","message":"The element with the reference ced381df-b6c9-49de-83e4-199cae351d50 is stale; either its node document is not the active document, or it is n
```

The license query polls every second during the whole test (request lines of `validator/licenses` in the browser
log, seconds since 16:00:00):

```
zcat $B | awk -F'\t' '$1>=1795104032317 && $1<=1795104040910' | grep -a 'sv calling GET [^ ]*validator/licenses' | awk -F'\t' '{printf "%.3f ", ($1-1795104000000)/1000} END {print ""}'
```
```
35.478 36.563 37.588 38.618 39.660 40.749 
```

## 4. The poll at 16:00:40.749 returned a different license contract

The first contract id of the response changes between the poll before and the poll during the row read.

```
zcat $T | grep -a 'T16:00:\(39\|40\)\.' | grep -a 'got response.*validator_licenses' | python3 -I -c '
import sys,re
for l in sys.stdin:
    print(re.search(r"@timestamp\":\"([^\"]+)",l).group(1), re.findall(r"contract_id[\\\"]+:[\\\"]+([0-9a-f]{10})",l))'
```
```
2026-11-19T16:00:39.746Z ['00423669bb']
2026-11-19T16:00:40.840Z ['003b934ffa']
2026-11-19T16:00:40.990Z []
```

`003b934ffa..` is the ValidatorLicense that sv2Validator's liveness activity record created: the trigger submits at
16:00:39.035 on the old license `0030a4d07a..`, the command completes at 16:00:40.203, sv1's DSO store ingests the
new contract at 16:00:40.205, 544 ms before the UI poll.

```
zcat $T | grep -a 'T16:00:\(39\|40\)\.' | grep -a -E 'Validator never received faucet|tid:96a23378ebf670b5275ddfb3ee78b426\) .*(sending request|succeeded)|Ingested transaction batch.*ValidatorLicense.*SV=sv1/store=dsoParty' | sed -E 's/\\n +/ /g; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | python3 -I -c '
import sys,re
for l in sys.stdin:
    m=re.search(r"Identifier\(#splice-amulet, Splice.ValidatorLicense, ValidatorLicense\), ([0-9a-f]{10})[^,]*, (\w+)",l)
    c=re.search(r"contractId = ([0-9a-f]{10})",l)
    lg=re.search(r"logger_name\":\"([^\"]+)",l).group(1)
    print(l[:112].rstrip(), "...", (m.group(1)+".. "+m.group(2)) if m else "", ("created "+c.group(1)+"..") if c else "", "["+lg+"]")'
```
```
2026-11-19T16:00:39.034Z Validator never received faucet coupons, it will start now at round 0.","logger_name":" ...   [o.l.s.v.a.ReceiveFaucetCouponTrigger:SvFrontendIntegrationTest/config=7a31861a/validator=sv2Validator]
2026-11-19T16:00:39.035Z Request (tid:96a23378ebf670b5275ddfb3ee78b426) com.daml.ledger.api.v2.CommandService/Su ... 0030a4d07a.. ValidatorLicense_RecordValidatorLivenessActivity  [o.l.s.a.a.c.ApiClientRequestLogger:SvFrontendIntegrationTest/config=7a31861a/validator=sv2Validator]
2026-11-19T16:00:40.203Z Request (tid:96a23378ebf670b5275ddfb3ee78b426) com.daml.ledger.api.v2.CommandService/Su ...   [o.l.s.a.a.c.ApiClientRequestLogger:SvFrontendIntegrationTest/config=7a31861a/validator=sv2Validator]
2026-11-19T16:00:40.205Z Ingested transaction batch of 1 elements: ( offset = 435, acsSizeDiff = 1, synchronizer ...  created 003b934ffa.. [o.l.s.s.d.DbMultiDomainAcsStore:SvFrontendIntegrationTest/config=7a31861a/SV=sv1/store=dsoParty]
2026-11-19T16:00:42.449Z Ingested transaction batch of 1 elements: ( offset = 438, acsSizeDiff = 1, synchronizer ...  created 00cd379372.. [o.l.s.s.d.DbMultiDomainAcsStore:SvFrontendIntegrationTest/config=7a31861a/SV=sv1/store=dsoParty]
```

(The 16:00:42.449 line matches the time filter through a payload timestamp; it is the next license recreate, after
the failure.)

The rows are keyed by contract id, so a recreated license is a new row and the old row leaves the DOM:

```
git show c34a2a5b91:apps/common/frontend/src/components/ValidatorLicenses.tsx | grep -n -B2 -A4 'key={license.contractId}'
git show c34a2a5b91:apps/sv/frontend/src/App.tsx | sed -n 45,52p
```
```
80-              return (
81-                <LicenseRow
82:                  key={license.contractId}
83-                  validator={license.payload.validator}
84-                  createdAt={new Date(license.createdAt)}
85-                />
86-              );
  const refetchInterval = useConfigPollInterval();
  const navigate = useNavigate();

  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        refetchInterval,
        structuralSharing: replaceEqualDeep,
```

## 5. Duplicate check

No family in `known-families.md` has this signature (the only `StaleElementReferenceException` entry is the ANS
`search-entry-button` one in section F, 11187, a click on a remounted button). The tracker has no open or recent
issue with the row class or the helper:

```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "validator-licenses-table-row"' --jq '.items[]|"\(.number) \(.state) \(.created_at[0:10]) \(.title)"'
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "StaleElementReferenceException" SvFrontendIntegrationTest' --jq '.items[]|"\(.number) \(.state) \(.created_at[0:10]) \(.title)"'
```
```
6375 closed 2025-11-06 ScanFrontendTimeBasedIntegrationTest: [validator-licenses-table-row] had size 5 instead of expected size 1
6828 closed 2026-01-20 SvFrontendIntegrationTest: "can create valid SRARC_SetConfig" `StaleElementReferenceException`
```

Both are other tests and other mechanisms (a row count, a governance form), closed long ago. The test file and the
helper are unchanged between the run's sha and origin/main:

```
git log --oneline c34a2a5b91..origin/main -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/SvFrontendIntegrationTest.scala apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/ValidatorLicensesFrontendTestUtil.scala
```
```
```

## Verdict

New, family BN. Test flake: an un-retried multi-round-trip Selenium read of a list that the UI re-renders on its
1 s poll, hit when a validator's liveness activity record (once per validator and round; here sv2Validator's first
one, "Validator never received faucet coupons, it will start now at round 0") recreates a ValidatorLicense during
the roughly 350 ms the read takes. Rare: it needs the recreate, the sv1 ingest and a UI poll inside that window.

Fix location: test. Branch `pperek/fix-11664-licenses-rows-stale-element` (worktree `~/Projects/fix-11664`, off
origin/main 6c71d08233), commit 6451f8f582, local only, not pushed: the read becomes
`val licenseRows = eventually()(getLicensesTableRows)`. The frontend `eventually`
(`FrontendIntegrationTest.scala`) retries every `WebDriverException`, so a stale row leads to a fresh `findAll`.
Only the size of the result is used afterwards.

Verified on the fix branch (macOS, 2026-11-20, Canton 3.7.4 wall clock against a local Postgres 18.4 with
`./start-canton.sh -d -w -p external`, `./start-frontends.sh -d -s`):

```
sbt -batch "apps-app/Test/compile"
sbt -batch "apps-app/testOnly org.lfdecentralizedtrust.splice.integration.tests.SvFrontendIntegrationTest"
```
```
[success] Total time: 407 s (0:06:47.0), completed 20 Nov 2026, 09:49:17
[info] Run completed in 10 minutes, 59 seconds.
[info] Tests: succeeded 13, failed 0, canceled 0, ignored 0, pending 0
[info] All tests passed.
[success] Total time: 674 s (0:11:14.0), completed 20 Nov 2026, 10:03:29
```

The local run shows that the suite passes with the change; it did not hit the race (no
`Retrying StaleElementReferenceException` with `getLicensesTableRows` in its log), so the retry path itself was not
exercised. `sbt checkErrors` was not run locally.

Not verified: that no other row than the sv2 validator's changed in the 16:00:40.813 response (the logged
response body is truncated; only the first contract id is visible). The remaining `getLicensesTableRows` call
(`checkValidatorLicenseRow`) already runs inside the retried check of `actAndCheck`.
