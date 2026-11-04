# 11161 - SplitwellFrontendIntegrationTest: wallet login helper misses the "already logged in" case on a slow confirm-payment load (run 41633306529)

New (family AE, provisional letter). main be675a11dd ("[ci] Bump react-router from 7.18.4 to 8.4.0 in /apps
(#8040)"), job `frontend-wall-clock-time (1)`, canton 3.7.1. 22 tests, 1 failed: `SplitwellFrontendIntegrationTest
/ A splitwell UI should settle debts with a single party`, `[frontend=bobSplitwell step=enter-payment-accept-verify]
Could not find IdQuery(user-id-field)`. Bob was still logged in to his wallet (localhost:3001) from test case 0. After
the splitwell transfer link redirects to `/confirm-payment/<cid>`, the wallet page took about 3.8 s to reach
DOMContentLoaded. `FrontendLoginUtil.loginOnceConfirmedToBeAtUrl` checks for `#logout-button` once at the start of each
attempt, before React has rendered, so it never logs out, and then waits for a login form that a logged-in wallet
never shows. Its 5 s budget allows only two such attempts. Test-side timing race in the shared login helper, not
caused by #8040: the same shard passed on the next two main runs, which contain #8040.

- Run: https://github.com/canton-network/splice/actions/runs/41633306529, main be675a11dd ("[ci] Bump react-router
  from 7.18.4 to 8.4.0 in /apps (#8040)"), job 125908678411 `ci / scala_test_frontend_wall_clock_time /
  frontend-wall-clock-time (1)`, attempt 1 (only attempt).
- Runtime canton: 3.7.1 (`nix/canton-sources.json` at be675a11dd). Vite v8.3.5 (`npm-wallet-bob.out`).
- Component: test (`apps/app/src/test/scala/org/lfdecentralizedtrust/splice/util/FrontendLoginUtil.scala`).

## 1. Failing assertion

The job log has one failing test; the failure is in `waitForQuery` during the login helper.
```
sed -E 's/\x1b\[[0-9;]*m//g' log/11161/job.log | grep -a -A2 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | head -3
sed -E 's/\x1b\[[0-9;]*m//g' log/11161/job.log | grep -a 'Tests: succeeded' | sed -E 's/^[^Z]*Z //'
```
```
[info] - should settle debts with a single party *** FAILED ***
[info]   [frontend=bobSplitwell step=enter-payment-accept-verify] Could not find IdQuery(user-id-field) (FrontendIntegrationTest.scala:427)
[info]   org.scalatest.exceptions.TestFailedException:
[info] Tests: succeeded 21, failed 1, canceled 0, ignored 0, pending 0
```
The step at be675a11dd (`SplitwellFrontendIntegrationTest.scala:239-244`): `enterSplitwellPayment(...)`, then
"Bob is redirected to wallet ..", `loginOnCurrentPage(bobWalletUIPort, bobDamlUser)`. The helper
(`FrontendLoginUtil.scala:39-63` at be675a11dd):
```
git show be675a11dd:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/util/FrontendLoginUtil.scala | sed -n 39,52p
```
```
  protected def loginOnceConfirmedToBeAtUrl(
      ledgerApiUser: String
  )(implicit webDriver: WebDriver) = {
    eventually(timeUntilSuccess = 5.seconds) {
      if (find(id("logout-button")).isDefined) {
        eventuallyClickOn(id("logout-button"))
      }
      silentClue("Waiting for login page to be ready") {
        waitForCondition(id("user-id-field"), timeUntilSuccess = Some(3.seconds)) {
          ExpectedConditions.elementToBeClickable(_)
        }
      }
      textField("user-id-field").value = ledgerApiUser
```

## 2. Test-side timeline

Test case 0 already logged bob in to the wallet (its own redirect at 12:26:07-12:26:11 needed one retry). In test case
1, the login wait starts at 12:26:43.925; two 3 s attempts fail and the 5 s budget is spent.
```
zcat log/11161/art/canton_network_test.clog.gz | grep -a -E 'Starting test case|Starting .SplitwellFrontend|login page to be ready|Caught error|Test failed' | grep -a Splitwell | sed -E 's/^\{"@timestamp":"2026-11-03T([^"]*)","message":"([^"]{0,140}).*/\1 \2/' | head -24
```
```
12:25:14.858Z Starting test case 0
12:25:14.858Z Starting 'SplitwellFrontendIntegrationTest/A splitwell UI should settle debts with multiple parties'...
12:25:25.429Z Running clue: Waiting for login page to be ready
12:25:25.464Z Finished clue: Waiting for login page to be ready
12:25:32.986Z Running clue: Waiting for login page to be ready
12:25:33.022Z Finished clue: Waiting for login page to be ready
12:25:48.502Z Running clue: Waiting for login page to be ready
12:25:48.540Z Finished clue: Waiting for login page to be ready
12:26:07.348Z Running clue: Waiting for login page to be ready
12:26:11.782Z Failed clue: Waiting for login page to be ready
12:26:11.835Z Running clue: Waiting for login page to be ready
12:26:11.909Z Finished clue: Waiting for login page to be ready
12:26:15.468Z Starting test case 1
12:26:15.469Z Starting 'SplitwellFrontendIntegrationTest/A splitwell UI should settle debts with a single party'...
12:26:22.699Z Running clue: Waiting for login page to be ready
12:26:22.740Z Finished clue: Waiting for login page to be ready
12:26:26.595Z Running clue: Waiting for login page to be ready
12:26:26.634Z Finished clue: Waiting for login page to be ready
12:26:43.925Z Running clue: Waiting for login page to be ready
12:26:47.026Z Failed clue: Waiting for login page to be ready
12:26:47.115Z Running clue: Waiting for login page to be ready
12:26:50.118Z Failed clue: Waiting for login page to be ready
12:26:50.119Z Caught error org.scalatest.exceptions.TestFailedException: Could not find IdQuery(user-id-field), dumping all frontend debug info before ret
12:26:50.311Z Test failed: 'SplitwellFrontendIntegrationTest/A splitwell UI should settle debts with a single party', message: [frontend=bobSplitwell step
```

## 3. Browser side: both logout checks run before the page has rendered

Bob's geckodriver log (epoch ms, converted to UTC by the awk). The transfer-link click is at 12:26:43.269. The
`#logout-button` lookup at 12:26:43.883 finds nothing, and so does the second one at 12:26:47.030, 8 ms after
DOMContentLoaded of `/confirm-payment/<cid>`. FindElement #931 blocks from 12:26:44.629 until the document loads.
```
B=log/11161/art/browser.org.lfdecentralizedtrust.splice.integration.tests.SplitwellFrontendIntegrationTest.bobSplitwell.3.1.log.gz
zcat $B | grep -a -E '\[[01],(907|919|920|931|932|933),|browsingContext.domContentLoaded' | awk -F'\t' '$1>=1793708803000 && $1<=1793708810000' | sed -E 's/"stacktrace":.*//; s/"params":\{"context":"[^"]*",[^}]*"url":"([^"]{0,45}).*/\1/; s/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/<id>/g' | awk -F'\t' '{printf "%s.%03d %s\n", strftime("%H:%M:%S", int($1/1000), 1), $1%1000, $NF}' | cut -c1-170
```
```
12:26:43.269 0 -> [0,907,"WebDriver:ElementClick",{"id":"<id>"}]
12:26:43.352 0 <- [1,907,null,{"value":null}]
12:26:43.883 0 -> [0,919,"WebDriver:FindElement",{"using":"css selector","value":"#logout\\-button"}]
12:26:43.923 0 <- [1,919,{"error":"no such element","message":"Unable to locate element: #logout\\-button",
12:26:43.926 0 -> [0,920,"WebDriver:FindElement",{"using":"css selector","value":"#user\\-id\\-field"}]
12:26:43.989 0 <- [1,920,{"error":"no such element","message":"Unable to locate element: #user\\-id\\-field",
12:26:44.629 0 -> [0,931,"WebDriver:FindElement",{"using":"css selector","value":"#user\\-id\\-field"}]
12:26:47.022 WebDriverBiDiConnection <id> <- {"type":"event","method":"browsingContext.domContentLoaded",http://localhost:3001/confirm-payment/00eb967
12:26:47.023 0 <- [1,931,{"error":"no such element","message":"Unable to locate element: #user\\-id\\-field",
12:26:47.030 0 -> [0,932,"WebDriver:FindElement",{"using":"css selector","value":"#logout\\-button"}]
12:26:47.113 0 <- [1,932,{"error":"no such element","message":"Unable to locate element: #logout\\-button",
12:26:47.119 0 -> [0,933,"WebDriver:FindElement",{"using":"css selector","value":"#user\\-id\\-field"}]
12:26:47.139 0 <- [1,933,{"error":"no such element","message":"Unable to locate element: #user\\-id\\-field",
```
From 12:26:47.139 to the end of the budget, every
`#user-id-field` lookup returns `no such element`, at a 100 ms poll interval.

Page loads of bob's browser: in this run the wallet confirm-payment page took 3.8 s after the click in test case 1
and more than 4.4 s after the wait start in test case 0. No console errors and no `disallowed MIME type` (so not the
family F vite deps race).
```
zcat $B | grep -a -oE '"method":"browsingContext.domContentLoaded".*"url":"[^"]{0,45}' | sed -E 's/.*"timestamp":([0-9]+).*"url":"(.*)/\1 \2/' | awk '{printf "%s.%03d %s\n", strftime("%H:%M:%S", int($1/1000), 1), $1%1000, $2}'
zcat $B | grep -a -c 'disallowed MIME'; zcat $B | grep -a -c '"level":"error"'
```
```
12:25:32.835 http://localhost:3401/
12:26:11.775 http://localhost:3001/confirm-payment/00a9848
12:26:14.223 http://localhost:3401/
12:26:26.530 http://localhost:3401/
12:26:47.022 http://localhost:3001/confirm-payment/00eb967
12:27:05.781 http://localhost:3401/
0
0
```

## 4. The wallet was logged in when the test gave up

The failure screenshot (`screenshot-26-11-03-12.26.50.154.png` in artifact `logs-frontend-wall-clock-time-1`, taken
at 12:26:50.15) shows the Amulet Wallet header with `bob__wallet__user-...`, `Logout` and "Total Available Balance:
39 AMT / 0.195 USD", with a loading spinner in the page body. So the page rendered as logged in, which is the branch
the helper only takes when its single `#logout-button` check happens to run after rendering.
```
gh api "/repos/canton-network/splice/actions/runs/41633306529/artifacts?per_page=100" --jq '.artifacts[] | "\(.id) \(.name)"' | grep 'frontend-wall-clock-time-1$'
unzip -l log/11161/art/a.zip | grep -a screenshot
```
```
13231725870 logs-frontend-wall-clock-time-1
    14263  2026-11-03 12:26   screenshot-26-11-03-12.26.50.154.png.gz
```

## 5. Not caused by #8040 (react-router 8)

The next two main runs, both descendants of be675a11dd, passed the same shard. SplitwellFrontendIntegrationTest is in
shard (1) of both, as in the failing run.
```
for h in 6a32b00e92 5c53b74294; do gh api "/repos/canton-network/splice/compare/be675a11dd6394f2ca6b1fdf4fed3fc39673e2f6...$h" --jq "\"$h: \(.status) ahead_by=\(.ahead_by)\""; done
for run in 41637223316 41637827972; do gh api "/repos/canton-network/splice/actions/runs/$run/jobs?per_page=100&filter=latest" --jq '.jobs[] | select(.name|endswith("frontend-wall-clock-time (1)")) | "\(.id) \(.conclusion)"'; done
for j in 125922009978 125924179240; do sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11161/later-$j.log | grep -a -E 'Tests: succeeded'; sed -E 's/\x1b\[[0-9;]*m//g' log/11161/later-$j.log | grep -a -c 'SplitwellFrontendIntegrationTest'; done
```
```
6a32b00e92: ahead ahead_by=1
5c53b74294: ahead ahead_by=2
125922009978 success
125924179240 success
[info] Tests: succeeded 22, failed 0, canceled 0, ignored 0, pending 0
800
[info] Tests: succeeded 22, failed 0, canceled 0, ignored 0, pending 0
800
```

## 6. Duplicate check

There is no earlier issue with this signature since 2026-10-20 (export `issues-since-2026-10-20.jsonl`), and no family
entry. 10156 / 9704 (family F) failed on the same `user-id-field` symptom, but their cause was blocked Vite modules.
That is ruled out here (section 3). 10893 (0.9.x, unknown-party error) is a different test. Precedent: #7810 (fb8ff44ded,
2026-10-16) moved the fill and click into the same retry loop for the SvFrontendIntegrationTest auto-login race,
but kept the one-shot logout check.
```
X=/run/sandbox/source/ci-triage/export
python3 -I -c "import json,sys
for l in open(sys.argv[1]):
    r=json.loads(l); t=r['title']+' '+r['body']+' '.join(c['body'] for c in r['comments'])
    for p in ['user-id-field','enter-payment-accept-verify','loginOnCurrentPage']:
        if p in t: print(r['number'], p)" $X/issues-since-2026-10-20.jsonl | wc -l
grep -c -i 'user-id-field\|enter-payment' ci-triage/known-families.md
```
```
0
0
```

## Verdict

New family AE (provisional letter). Test-side flake in the shared login helper `loginOnceConfirmedToBeAtUrl`: if the
user is already logged in and the page renders more than about 2 s after the helper starts, the one-shot
`#logout-button` check per attempt misses it, and the 5 s budget runs out waiting for a login form that never
comes. The trigger here was a 3.8 s confirm-payment page load from the Vite dev server. Not a product bug, and not
caused by #8040.

Fix (test-side, described, not written), in `apps/app/src/test/scala/org/lfdecentralizedtrust/splice/util/FrontendLoginUtil.scala`,
`loginOnceConfirmedToBeAtUrl` (lines 39-63 at be675a11dd): before the `eventually(timeUntilSuccess = 5.seconds)`
block, wait with the default `eventually()` until the page shows either state:
`eventually() { (find(id("logout-button")).isDefined || find(id("user-id-field")).isDefined) shouldBe true }`.
Then keep the existing loop. This makes the logout decision after rendering, and it also covers the slow first load
of test case 0. Alternative: in the loop, wait up to 3 s for either element, and click logout if it is the logout
button.

Not verified: whether react-router 8 or the Vite dev server makes the load slower than before. Passing runs upload no
browser logs, so there is no baseline. The fix is not compiled or run.

## 5. Wallet page loads are slow in general (added 2026-11-04)

Same suite, run 41721296163 (11176, artifact logs-frontend-wall-clock-time-1): time from each `WebDriver:Navigate`
to the next `browsingContext.domContentLoaded`, all browser logs of the shard.
```
python3 -I -c '<pair WebDriver:Navigate with the next domContentLoaded per browser log; group by port>'
```
```
http://localhost:3000        n= 20 median=3.11s p90=3.60s max=4.71s
http://localhost:3001        n=  3 median=2.98s p90=3.30s max=3.30s
http://localhost:3100        n=  1 median=4.14s p90=4.14s max=4.14s
http://localhost:3400        n=  3 median=0.79s p90=1.21s max=1.21s
http://localhost:3401        n=  3 median=0.48s p90=1.21s max=1.21s
http://localhost:3402        n=  2 median=1.42s p90=1.42s max=1.42s
```
The frontends run `vite --force` (dev server, unbundled ES modules, React development build: about 81000
`console.timeStamp` profiling entries in bob's browser log). A 3 to 4 s wallet load is the norm, so the helper's 5 s
budget is only just above it. Not measured: module counts, network timings (not in the browser log), runner CPU.
