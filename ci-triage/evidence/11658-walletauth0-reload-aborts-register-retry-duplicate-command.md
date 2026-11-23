# 11658 - WalletAuth0FrontendIntegrationTest: page reload aborts the in-flight register call, the wallet UI retries it, createValidatorRight ends in DUPLICATE_COMMAND WARN (run 44524109030)

Verdict: new (family BM), test race against the wallet UI; not a duplicate. All 22 tests passed, checkErrors flagged
one WARN of aliceValidator in `canton_network_test.clog`. The test "allow login via auth0 and persist user name on
refresh" clicks "Onboard yourself" and reloads the page as soon as `#logged-in-user` is shown. The UI shows that
element when the 1 s `user-status` poll reports the WalletAppInstall, but the `POST /v0/register` behind the button
only returns after the validator has also created the ValidatorRight (a second transaction, 3.3 s here). The reload
(12:43:34.645) aborted the pending request; the onboarding mutation of the wallet UI has `retry: 3`, and the old
document, still alive until the reloaded page committed 4 s later, sent the register again exactly 1.0 s after the
abort (12:43:35.649). The second register ran concurrently with the first: its createValidatorRight got
`SUBMISSION_ALREADY_IN_FLIGHT` (retried after 100 ms), then `DUPLICATE_COMMAND`, which is not retryable and is
logged at WARN (12:43:36.199, HTTP 409). The first register finished with 200 at 12:43:35.985.

- Run: https://github.com/canton-network/splice/actions/runs/44524109030, main 5eff61ca40 ("Revert "Revert
  "implement participant admin API auth support in clusters (#8216)"" (#8577)"), push, workflow "CI post-merge to
  main or release-line branches", job 135560458811 `ci / scala_test_frontend_wall_clock_time /
  frontend-wall-clock-time (1)`.
- Runtime canton: 3.7.4.
- Component: test (FrontendLoginUtil.withAuth0LoginCheck); the non-idempotent concurrent register is a splice
  validator app weakness that the test exposes (see Verdict).
- Tuple as given: run 44524109030, frontend-wall-clock-time (1), ref 11658. The run has exactly one failed job and
  the issue body names it.

## 1. Flagged line

checkErrors failure, one line, logger HttpValidatorHandler of aliceValidator in WalletAuth0FrontendIntegrationTest.

```
gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/135560458811/logs > log/11658/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/11658/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed' | sed -E 's/^[^Z]*Z //' | sort -u
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11658/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | sed -E 's/1220[0-9a-f]{60}/../g; s/"stack_trace":"[^"]*",//; s/\\n/ | /g' | cut -c1-1300
```
```
[error] (checkErrors) log/canton_network_test.clog contains problems.
[error] java.lang.RuntimeException: log/canton_network_test.clog contains problems.
[info] All tests passed.
[info] Run completed in 16 minutes, 8 seconds.
[info] Tests: succeeded 22, failed 0, canceled 0, ignored 0, pending 0
Found problems in log/canton_network_test.clog:
***"@timestamp":"2026-11-19T12:43:36.199Z","message":"The operation 'createValidatorRight' failed with a non-retryable error, not retrying: | category=Some(InvalidGivenCurrentSystemStateResourceExists) | statusCode=ALREADY_EXISTS | description=DUPLICATE_COMMAND(10,7f19c2a4): Command submission already exists. | ErrorInfoDetail(DUPLICATE_COMMAND,HashMap(participant -> aliceParticipant, changeId -> ChangeId(alice_validator_user-2b50f7e2,org.lfdecentralizedtrust.splice.validator.createValidatorRight_ce324fbd1f2670c6b26f9fd606b141162c63b5755966fa6dc61b58bdd0f45fc6,Set(alice-validator2b50f7e2-1::..aa2c, auth0_007c6afeeee89f27acfb8bcb8c2d::..aa2c)), completion_offset -> 148, accepted -> true, psid -> global-domain::12208c1e1073...::37-0, category -> 10, tid -> 7f19c2a4e0f7bbb6858ab04d4c12b5cb, definite_answer -> true, existingSubmissionId -> Some(483514f4-5607-4902-b333-55ee31428f5b))) | RequestInfoDetail(7f19c2a4e0f7bbb6858ab04d4c12b5cb)","logger_name":"o.l.s.v.a.h.HttpValidatorHandler:WalletAuth0FrontendIntegrationTest/config=2b50f7e2/validator=aliceValidator","thread_name":"WalletAuth0FrontendIntegrationTest-2b50f7e2-env-ec-416","level":"WARN","span-id":"495ccb73987d3fa6","span-parent-id":"db96177401df6cdb","trace-id":"05212728c7bf740c9e6ee1f795d95dd3","span-name":"createValidatorRi
Total: 1 lines with problems.
```

## 2. Which test was running

The WARN (12:43:36.199) falls into the first test of the suite, which ended 12:43:49.286.

```
D=log/11658/logs-frontend-wall-clock-time-1; T=$D/canton_network_test.clog.gz
zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '" | grep -a 'T12:4[2-5]' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200
```
```
2026-11-19T12:43:49.286Z Test succeeded: 'WalletAuth0FrontendIntegrationTest/A wallet UI with a backend configured to accept auth0 tokens should allow login via auth0 and persist user name on refresh'
2026-11-19T12:45:04.098Z Test succeeded: 'WalletAuth0FrontendIntegrationTest/A wallet UI with a backend configured to accept auth0 tokens should redirect to the previous page after login'",
2026-11-19T12:45:04.200Z Starting test suite 'TokenStandardWalletTransfersFrontendIntegrationTest'...",
2026-11-19T12:45:37.283Z Starting 'TokenStandardWalletTransfersFrontendIntegrationTest/A wallet UI should create a p2p transfer'...",
```

## 3. Validator side: two register requests for the same user, 5.4 s apart, overlapping

The first request (trace b915fcc0) submits installWalletForUser 12:43:31.762 and createValidatorRight 12:43:32.641
and answers 200 at 12:43:35.985. The second (trace 05212728) arrives 12:43:35.751, skips the install, finds no
ValidatorRight in the store yet and submits the same command id twice: in flight, then duplicate.

```
zcat $T | grep -a 'config=2b50f7e2/validator=aliceValidator' | grep -a 'T12:43:3[0-6]' | grep -a -E 'POST /api/validator/v0/register.*(received request|Responding)|already exists, skipping|SubmitAndWait to .*sending request.*(createValidatorRight|installWalletForUser)|createValidatorRight. (failed|has failed)|Now retrying' | sed -E 's/"logger_name":"[^"]*:WalletAuth0FrontendIntegrationTest[^"]*".*"trace-id":"([0-9a-f]{8})[^"]*".*/ [trace \1]/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/\\n/ | /g; s/(sending request SubmitAndWaitRequest).*validator\.(createValidatorRight|installWalletForUser)[^ ]*.*( \[trace)/\1(... \2 ...)\3/; s/(ErrorInfoDetail\((SUBMISSION_ALREADY_IN_FLIGHT|DUPLICATE_COMMAND)).*( \[trace)/\1 ...)\3/; s/",? \[trace/ [trace/' | cut -c1-300
```
```
2026-11-19T12:43:30.377Z HTTP POST /api/validator/v0/register from (127.0.0.1:53072): received request. [trace b915fcc0]
2026-11-19T12:43:30.639Z Now retrying operation 'Check whether Party auth0_007c6afeeee89f27acfb8bcb8c2d::1220e11f790a... is observed on ledger API'.  [trace b915fcc0]
2026-11-19T12:43:30.920Z Now retrying operation 'Check whether Party auth0_007c6afeeee89f27acfb8bcb8c2d::1220e11f790a... is observed on ledger API'.  [trace b915fcc0]
2026-11-19T12:43:31.682Z Now retrying operation 'Check whether Party auth0_007c6afeeee89f27acfb8bcb8c2d::1220e11f790a... is observed on ledger API'.  [trace b915fcc0]
2026-11-19T12:43:31.762Z Request (tid:66967a4e078e4cbf7dd8da0578c7589c) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:5501: sending request SubmitAndWaitRequest(... installWalletForUser ...) [trace b915fcc0]
2026-11-19T12:43:32.641Z Request (tid:2f0ce4d4333bfec39f4f792daab9ba83) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:5501: sending request SubmitAndWaitRequest(... createValidatorRight ...) [trace b915fcc0]
2026-11-19T12:43:34.364Z Now retrying operation 'update ingestion service'.  [trace e60b4e59]
2026-11-19T12:43:35.751Z HTTP POST /api/validator/v0/register from (127.0.0.1:53116): received request. [trace 05212728]
2026-11-19T12:43:35.762Z WalletAppInstall for auth0|6afeeee89f27acfb8bcb8c2d already exists, skipping [trace 05212728]
2026-11-19T12:43:35.775Z Request (tid:57f53358f813a8619db81a5bde1bf4ed) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:5501: sending request SubmitAndWaitRequest(... createValidatorRight ...) [trace 05212728]
2026-11-19T12:43:35.836Z The operation 'createValidatorRight' failed with a retryable error (full stack trace omitted): | category=Some(ContentionOnSharedResources) | ErrorInfoDetail(SUBMISSION_ALREADY_IN_FLIGHT ...) [trace 05212728]
2026-11-19T12:43:35.836Z The operation 'createValidatorRight' has failed with an exception. New kind of error: transient error (request infinite retries). Retrying after a number of 0 failures, and after 100 milliseconds.  [trace 05212728]
2026-11-19T12:43:35.945Z Now retrying operation 'createValidatorRight'.  [trace 05212728]
2026-11-19T12:43:35.950Z Request (tid:7f19c2a4e0f7bbb6858ab04d4c12b5cb) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:5501: sending request SubmitAndWaitRequest(... createValidatorRight ...) [trace 05212728]
2026-11-19T12:43:35.985Z HTTP POST /api/validator/v0/register from (127.0.0.1:53072): Responding with status code: 200 OK, took 5607 ms [trace b915fcc0]
2026-11-19T12:43:36.199Z The operation 'createValidatorRight' failed with a non-retryable error, not retrying: | category=Some(InvalidGivenCurrentSystemStateResourceExists) | statusCode=ALREADY_EXISTS | description=DUPLICATE_COMMAND(10,7f19c2a4): Command submission already exists. | ErrorInfoDetail(
2026-11-19T12:43:36.200Z The operation 'createValidatorRight' has failed with an exception. Maximum number of retries 0 exceeded. Giving up.  [trace 05212728]
2026-11-19T12:43:36.204Z HTTP POST /api/validator/v0/register from (127.0.0.1:53116): Responding with status code: 409 Conflict, took 453 ms [trace 05212728]
2026-11-19T12:43:36.573Z Now retrying operation 'update ingestion service'.  [trace f3040002]
```

The ValidatorRight transaction of the first request reached the validator's update streams at 12:43:35.963, 13 ms
after the retry of the second request had read the store (no ValidatorRight) and resubmitted (12:43:35.950):

```
zcat $T | grep -a 'config=2b50f7e2/validator=aliceValidator' | grep -a -E 'T12:43:3[2-6]' | grep -a -E 'GetUpdates.*received a message' | grep -a -E 'createValidatorRight|WalletAppInstall' | sed -E 's/\{"@timestamp":"([^"]+)","message":"Request \(tid:([0-9a-f]{8})[^)]*\) ([^ ]+) .*(org\.lfdecentralizedtrust\.splice\.[a-zA-Z.]*(createValidatorRight|[iI]nstall[A-Za-z]*))_.*/\1 tid \2 \3 received Transaction \4/' | cut -c1-260 | head -8
```
```
2026-11-19T12:43:32.623Z tid 89011415 com.daml.ledger.api.v2.UpdateService/GetUpdates received Transaction org.lfdecentralizedtrust.splice.validator.installWalletForUser
2026-11-19T12:43:32.623Z tid 2c816f7b com.daml.ledger.api.v2.UpdateService/GetUpdates received Transaction org.lfdecentralizedtrust.splice.validator.installWalletForUser
2026-11-19T12:43:35.963Z tid 9c28c47d com.daml.ledger.api.v2.UpdateService/GetUpdates received Transaction org.lfdecentralizedtrust.splice.validator.createValidatorRight
2026-11-19T12:43:35.963Z tid 89011415 com.daml.ledger.api.v2.UpdateService/GetUpdates received Transaction org.lfdecentralizedtrust.splice.validator.createValidatorRight
2026-11-19T12:43:35.963Z tid 2a58225c com.daml.ledger.api.v2.UpdateService/GetUpdates received Transaction org.lfdecentralizedtrust.splice.validator.createValidatorRight
```

## 4. Browser side: one click, then the test's reload, then a second register from the old document

Epoch ms 1795092210347 = 12:43:30.347. One `ElementClick` on the onboard button, register sent 25 ms later. The
test reads `#logged-in-user` (`data-selenium-text`) at 12:43:34.638 and navigates at 12:43:34.645. The second
register is logged 1004 ms later from the same realm (bbc97cf0, the old document); the reloaded page only reaches
domContentLoaded at 12:43:38.613 (new realm d6957a05). The first register never logs a response in the browser.

```
B=$D/browser.org.lfdecentralizedtrust.splice.integration.tests.WalletAuth0FrontendIntegrationTest.randomUser.0.1.log.gz
zcat $B | awk -F'\t' '$1>=1795092210340 && $1<=1795092222000' | grep -a -E 'WebDriver:(ElementClick|Navigate)|"data-selenium-text"|validator (calling|got response)|"method":"browsingContext.domContentLoaded"|user_onboarded' | grep -a -v '\\"user_onboarded\\":false' | sed -E 's/^([0-9]+)\t[A-Za-z]+\t[A-Z]+\t/\1 /; s/WebDriverBiDiConnection [0-9a-f-]+ //; s/.*"text":"([^"]{0,200}).*"realm":"([0-9a-f]{8}).*/\1 realm \2/; s/^([0-9]+) .*"source":\{"realm":"([0-9a-f]{8})[^}]*\}.*"text":"([^"]{0,150}).*/\1 realm \2: \3/; s/"script":.*//; s/1220[0-9a-f]{60}/../g; s/^([0-9]+) .*domContentLoaded.*"url":"([^"]*)".*/\1 browsingContext.domContentLoaded \2/' | cut -c1-260
```
```
1795092210347 0 -> [0,103,"WebDriver:ElementClick",{"id":"11a549f0-8525-486a-9c0c-1de5326852f2"}]
1795092210372 realm bbc97cf0: validator calling POST http://localhost:5503/api/validator/v0/register with body: undefined [00-b915fcc0f3c57e90158a06b9c12c0e5b-401cf90924099b7a-01]
1795092211188 realm bbc97cf0: wallet got response with status code 200 and body: \
1795092212201 realm bbc97cf0: wallet got response with status code 200 and body: \
1795092213218 realm bbc97cf0: wallet got response with status code 200 and body: \
1795092213301 realm bbc97cf0: wallet got response with status code 200 and body: \
1795092214315 realm bbc97cf0: wallet got response with status code 200 and body: \
1795092214638 0 -> [0,120,"WebDriver:ExecuteScript",{"args":[{"element-6066-11e4-a52e-4f735466cecf":"d2c3d490-03bd-4b70-b1a7-b2bdbf1c34d7"},"data-selenium-text"],
1795092214645 0 -> [0,121,"WebDriver:Navigate",{"url":"http://localhost:3000"}]
1795092215330 realm bbc97cf0: wallet got response with status code 200 and body: \
1795092215649 realm bbc97cf0: validator calling POST http://localhost:5503/api/validator/v0/register with body: undefined [00-05212728c7bf740c9e6ee1f795d95dd3-db96177401df6cdb-01]
1795092218613 browsingContext.domContentLoaded http://localhost:3000/
1795092218711 realm d6957a05: wallet got response with status code 200 and body: \
1795092218801 realm d6957a05: wallet got response with status code 200 and body: \
1795092218815 0 -> [0,130,"WebDriver:ExecuteScript",{"args":[{"element-6066-11e4-a52e-4f735466cecf":"2b419832-9914-495a-8fa0-500a562bfbe0"},"data-selenium-text"],
1795092218893 0 -> [0,141,"WebDriver:ElementClick",{"id":"608fe09f-b3fa-4df5-a4c5-45dc08256c15"}]
1795092221499 browsingContext.domContentLoaded http://localhost:3000/
```

(The `wallet got response` lines are the `user-status` polls with `user_onboarded: true`.) Register responses seen
by the browser, first test against second test (which does not navigate after onboarding):

```
zcat $B | grep -a -c 'validator got response'; zcat $D/browser.*WalletAuth0*randomUser.1.1.log.gz | grep -a -c 'validator got response'
```
```
0
1
```

## 5. Source at the run's sha (5eff61ca40)

- Test: `apps/app/src/test/scala/org/lfdecentralizedtrust/splice/util/FrontendLoginUtil.scala:134-146`,
  `actAndCheck("onboard user", eventuallyClickOn(id("onboard-button")))("user is onboarded", ...)` only waits for a
  non-empty `#logged-in-user`; `WalletAuth0FrontendIntegrationTest.scala:49-52` then runs `go to
  s"http://localhost:3000"`. AnsAuth0FrontendIntegrationTest uses the same helper and navigates to the ANS UI right
  after, so it has the same window.
- Wallet UI: `apps/wallet/frontend/src/routes/authCheck.tsx` renders the wallet instead of `Onboarding` as soon as
  `useIsOnboarded` (from the polled `user-status`) is true. `apps/wallet/frontend/src/components/Onboarding.tsx`:
  `useMutation({ mutationFn: registerUser, onSuccess: () => navigate('/transactions'), retry: 3 })`. react-query's
  default retry delay starts at 1000 ms, which matches the 1004 ms between the navigation and the second call. That
  the aborted fetch is what triggered the retry is an inference from this timing, the missing response and the
  realm; the browser log has no line for the failed fetch itself.
- Validator: `ValidatorUtil.onboard` runs `installWalletForUser` and then `SpliceUtil.createValidatorRight`
  (`apps/common/src/main/scala/org/lfdecentralizedtrust/splice/util/SpliceUtil.scala:111-169`), which looks up the
  ValidatorRight in the store and, if absent, submits with a command id derived from the user and
  `deduplicationOffset` = the store offset. Two overlapping calls therefore end in `DUPLICATE_COMMAND` for the
  second one whenever the first commits before the store of the second lookup has ingested it.

## 6. Duplicate check

```
grep -n -i -E 'WalletAuth0|DUPLICATE_COMMAND|v0/register' ci-triage/known-families.md | cut -c1-120
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue DUPLICATE_COMMAND' --jq '.items[]|"\(.number) \(.state) \(.state_reason) \(.title)"' | cut -c1-150
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue WalletAuth0' --jq '.items[]|"\(.number) \(.state) \(.state_reason) \(.title)"' | cut -c1-150
```
```
352:- Auth0 login stall on the second login in WalletAuth0FrontendIntegrationTest (10143): only the first login had the
9448 closed duplicate (cluster deploy failure, unrelated)
9449 closed duplicate GHA Run 30785460595 : job Ingestion Tests in ingestion_performance_tests Failed :fire:
9435 closed completed WalletTransactionHistoryFrontendIntegrationTest fails on show all types of transactions
7385 closed duplicate GHA Run 22449248626 : job frontend-wall-clock-time (2) in scala_test Failed :fire:
3536 closed not_planned Tap times out / DEADLINE_EXCEEDED then SUBMISSION_ALREADY_IN_FLIGHT
168 closed not_planned SV runbook failed to join DSO due to failed to request onboarding from sv1
10143 closed completed WalletAuth0FrontendIntegrationTest: Auth0 /authorize navigation never commits (run 35072334729)
```

None matches: 10143 is the Auth0 navigation stall, 3536 and its family (9207) are a single tap retried by the wallet
backend after a 10 s deadline, with no second HTTP request. Here the retry comes from the browser, after a
navigation issued by the test. No open issue to point to.

## Verdict

New, family BM. Flake (first occurrence found), caused by the test: it navigates away while the register call it
triggered is still pending. The window exists on every run (register returns one transaction later than the UI
switch); it was hit here because createValidatorRight took 3.3 s (12:43:32.641 to 12:43:35.963).

Proposed test fix, not committed (one line in `FrontendLoginUtil.withAuth0LoginCheck`, covers both Auth0 suites that
use it): the onboarding mutation navigates to `/transactions` in `onSuccess`, the page is at `/` before, so the URL
tells the test that the register call has returned.

```
--- a/apps/app/src/test/scala/org/lfdecentralizedtrust/splice/util/FrontendLoginUtil.scala
+++ b/apps/app/src/test/scala/org/lfdecentralizedtrust/splice/util/FrontendLoginUtil.scala
@@ -141,6 +141,7 @@ trait FrontendLoginUtil extends WithAuth0Support { self: FrontendTestCommon =>
             _ => {
               val userId = seleniumText(find(id("logged-in-user")))
               userId should not be empty
+              currentUrl should endWith("/transactions")
               userId
             },
           )._2
```

Not verified: `direnv exec . sbt -batch "apps-app/Test/compile"` in a fresh worktree off origin/main (5eff61ca40)
failed on the local machine for lack of disk space (4.5 GB free at the start), before reaching the Scala sources:

```
[error] ghc-pkg: .daml/package-database/2.3/package.conf.d/package.cache43260-0.tmp: renameFile:renamePath:rename: resource exhausted (No space left on device)
```

So the change is neither compiled nor run, the worktree and branch were removed again, and two assumptions are
open: that the URL is not already `/transactions` before the register call returns (login lands on `/`, section 4
shows `http://localhost:3000/?code=..`), and that `onSuccess` and its `navigate` still run after `Onboarding` has
been unmounted by the `user-status` poll (expected: mutation-level callbacks of react-query run regardless of the
observer, and the URL is the only thing checked).

Product side, for the owners of the validator app and wallet UI, independent of the test fix: a user who reloads
during onboarding gets the same WARN and a 409 for the retried call. Options: treat `DUPLICATE_COMMAND` in
`SpliceUtil.createValidatorRight` as retryable (the next attempt finds the ValidatorRight and skips), or drop
`retry: 3` from the onboarding mutation. No ignore pattern: the WARN is the only signal of a non-idempotent register.
