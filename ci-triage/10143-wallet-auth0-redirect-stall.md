# 10143 - WalletAuth0FrontendIntegrationTest: Auth0 /authorize navigation never commits (run 35072334729)

Post-merge CI on main, sha 0c43730f70, 2026-09-16T08:10Z. Canton runtime 3.6.0-snapshot.20260910.20260.0.v90621933.
Ref mapping is inferred: the cn-test-failures issue 10143 was given as a URL only; of the 2026-09-16 morning main
runs, this frontend job is the only failed job not otherwise accounted for. The run has two more failed jobs
(section 1) that this packet does not cover.

Categorization:
- test failed: WalletAuth0FrontendIntegrationTest "redirect to the previous page after login" (1 of 50 in the shard)
- failure type: ScalaTest assertion after a 20s eventually() - the Auth0 login form never appeared after clicking
  "Log In with OAuth2"
- component: frontend integration test / wallet UI OIDC redirect / Auth0 external IdP (canton-network-test.us.auth0.com)
- flake vs real: flake. The same click-to-Auth0 step succeeded twice in the same suite 16s and 56s earlier. The
  browser initiated the navigation to Auth0's /authorize but the new document never committed within 25s. Not a
  wallet backend or Canton problem (zero WARN/ERROR in either log during the window).

## Setup

```
TMPDIR=<roomy>/ghtmp gh run download 35072334729 --repo canton-network/splice -n logs-frontend-wall-clock-time-2 -D dl
cd dl
# canton_network_test.clog.gz = test harness + splice apps; canton.clog.gz = canton nodes
# browser.*WalletAuth0FrontendIntegrationTest.randomUser.3.1.log.gz = geckodriver log of the failing test's browser
# job log: gh api repos/canton-network/splice/actions/jobs/104716753202/logs
```

## 1. Failed jobs

```
gh run view 35072334729 --repo canton-network/splice --json jobs \
  --jq '.jobs[] | select(.conclusion=="failure") | "\(.databaseId)  \(.name)"'
```
```
104716522199  ci / ui_tests / ui_tests
104716731789  ci / scala_test_sim_time / simtime (3)
104716753202  ci / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (2)
```
This packet covers frontend-wall-clock-time (2), job 104716753202. ui_tests and simtime (3) are separate failures.

```
grep -n '##\[error\]\|TEST FAILED\|Failed tests:' job-104716753202.log | cut -c1-120
```
```
11192:2026-09-16T08:41:41.4077750Z [info] *** 1 TEST FAILED ***
11300:2026-09-16T08:41:41.4171137Z [error] Failed tests:
11306:2026-09-16T08:41:42.4155734Z ##[error]Error: failed to run script step (id 267d0ba0-b1a7-11f1-ba65-6d320b644586): Error: step failed with return code 1
11307:2026-09-16T08:41:42.4211206Z ##[error]Process completed with exit code 1.
```
```
sed -n '11188,11192p' job-104716753202.log | cut -c29-
```
```
[info] Total number of tests run: 50
[info] Suites: completed 10, aborted 0
[info] Tests: succeeded 49, failed 1, canceled 0, ignored 2, pending 0
[info] *** 1 TEST FAILED ***
```
No checkErrors verdict in this job: it fails on the ScalaTest failure alone.

## 2. Failing test, assertion, stack trace, source line

```
grep -n 'redirect to the previous page after login' job-104716753202.log | head -3 | cut -c29-200
```
```
11079:[info] *** Test still running after 1 minute, 13 seconds: suite name: WalletAuth0FrontendIntegrationTest, test name: A wallet UI with a backend configured to accept auth0 tokens should redirect to the previous page after login.
11080:[info] *** Test still running after 1 minute, 43 seconds: suite name: WalletAuth0FrontendIntegrationTest, test name: A wallet UI with a backend configured to accept auth0 tokens should redirect to the previous page after login.
11081:[info] - should redirect to the previous page after login *** FAILED ***
```
```
sed -n '11082,11100p' job-104716753202.log | cut -c29- | grep -E 'None was|Exception|splice' | grep -v FrontendIntegrationTestWithIsolatedEnvironment
```
```
[info]   None was equal to None (FrontendIntegrationTest.scala:565)
[info]   org.scalatest.exceptions.TestFailedException:
[info]   at org.scalatest.matchers.dsl.ResultOfNotWordForAny.be(ResultOfNotWordForAny.scala:85)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.FrontendTestCommon.assertAuth0LoginFormVisible(FrontendIntegrationTest.scala:565)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.FrontendTestCommon.$anonfun$loginViaAuth0InCurrentPage$2(FrontendIntegrationTest.scala:670)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.SpliceTests$TestCommon.$anonfun$silentActAndCheck$2(SpliceTests.scala:406)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.FrontendTestCommon.$anonfun$eventually$1(FrontendIntegrationTest.scala:399)
[info]   at com.digitalasset.canton.BaseTest$.eventually(BaseTest.scala:637)
```
```
sed -n '11100,11185p' job-104716753202.log | cut -c29- | grep -E 'WalletAuth0FrontendIntegrationTest.scala|FrontendLoginUtil.scala|loginViaAuth0InCurrentPage\(' 
```
```
[info]   at org.lfdecentralizedtrust.splice.integration.tests.FrontendTestCommon.loginViaAuth0InCurrentPage(FrontendIntegrationTest.scala:670)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.WalletAuth0FrontendIntegrationTest.$anonfun$new$15(WalletAuth0FrontendIntegrationTest.scala:106)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.WalletAuth0FrontendIntegrationTest.$anonfun$new$10(WalletAuth0FrontendIntegrationTest.scala:104)
[info]   at org.lfdecentralizedtrust.splice.util.FrontendLoginUtil.$anonfun$withAuth0LoginCheck$2(FrontendLoginUtil.scala:144)
[info]   at org.lfdecentralizedtrust.splice.util.FrontendLoginUtil.withAuth0LoginCheck(FrontendLoginUtil.scala:107)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.WalletAuth0FrontendIntegrationTest.$anonfun$new$9(WalletAuth0FrontendIntegrationTest.scala:75)
```

The assertion at sha 0c43730f70 (the sha that ran):

```
git show 0c43730f70:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/FrontendIntegrationTest.scala \
  | awk 'NR>=562 && NR<=568 {print NR": "$0}'
```
```
562:   protected def assertAuth0LoginFormVisible()(implicit
563:       webDriver: WebDriverType
564:   ) = {
565:     find(tagName("h1")) should not be None
566:     find(tagName("h1")).value.text shouldBe "Welcome"
567:     find(cssSelector("div.password")) should not be empty withClue "password div"
568:   }
```
"None was equal to None" is ScalaTest's rendering of `find(tagName("h1")) should not be None` when `find` returned
`None`: the current page has no `<h1>` at all. Auth0's universal login page has `<h1>Welcome</h1>`; the wallet login
page has none (section 5). The check is polled by `eventually()` (default 20s) inside `silentActAndCheck`:

```
git show 0c43730f70:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/FrontendIntegrationTest.scala \
  | awk 'NR>=658 && NR<=674 {print NR": "$0}'
```
```
658:   protected def loginViaAuth0InCurrentPage(
659:       username: String,
660:       password: String,
661:       assertCompleted: () => org.scalatest.Assertion,
662:   )(implicit
663:       webDriver: WebDriverType
664:   ) = {
665:     silentActAndCheck(
666:       "Auth0 login: Click the login button",
667:       eventuallyClickOn(id("oidc-login-button")),
668:     )(
669:       "Auth0 login: Login form is visible",
670:       _ => assertAuth0LoginFormVisible(),
671:     )
672: 
673:     submitAuth0LoginForm(username, password, assertCompleted)
674:   }
```
The call site is the last step of the test:

```
git show 0c43730f70:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/WalletAuth0FrontendIntegrationTest.scala \
  | awk 'NR>=104 && NR<=111 {print NR": "$0}'
```
```
104:           clue("User has to login again") {
105:             go to s"http://localhost:3000/confirm-payment/${paymentRequestContractId.contractId}"
106:             loginViaAuth0InCurrentPage(
107:               auth0User.email,
108:               auth0User.password,
109:               () => find(id("confirm-payment")) should not be None,
110:             )
111:           }
```

## 3. Canton runtime version

```
zcat canton.clog.gz | grep -aoE 'Canton version [0-9][^" ]*' | head -1
```
```
Canton version 3.6.0-snapshot.20260910.20260.0.v90621933
```

## 4. Harness timeline: every step passed until the second click on "Log In with OAuth2"

```
zcat canton_network_test.clog.gz | grep -aE '"@timestamp":"2026-09-16T08:(29:(2[7-9]|[3-5][0-9])|30:(0[0-9]|1[0-2]))' \
  | grep -aE '"message":"(Running clue|Finished clue|Failed clue|Test (failed|succeeded)|Starting creating environment for)' \
  | grep -avE 'SvFrontendIntegrationTest|Waiting for IdQuery|Getting state|Tapping|Making sure|Create a payment' \
  | grep -aoE '"@timestamp":"[^"]*"|"message":"[^"]{0,120}' | paste - - | sed -E 's/"@timestamp"://; s/\t"message":/  /'
```
```
"2026-09-16T08:29:03.287Z"  "Starting creating environment for WalletAuth0FrontendIntegrationTest, test 'A wallet UI with a backend configured to accept auth0 tokens should redirect to the
"2026-09-16T08:29:27.644Z"  "Running clue: The user logs in with OAauth2 and completes all Auth0 login prompts
"2026-09-16T08:29:27.644Z"  "Running clue: (act) Auth0 login: Open target web page
"2026-09-16T08:29:28.145Z"  "Finished clue: (act) Auth0 login: Open target web page
"2026-09-16T08:29:28.145Z"  "Running clue: (check) Auth0 login: Log in or log out buttons are visible
"2026-09-16T08:29:28.163Z"  "Finished clue: (check) Auth0 login: Log in or log out buttons are visible
"2026-09-16T08:29:28.165Z"  "Running clue: (act) Auth0 login: Click the login button
"2026-09-16T08:29:28.261Z"  "Finished clue: (act) Auth0 login: Click the login button
"2026-09-16T08:29:28.261Z"  "Running clue: (check) Auth0 login: Login form is visible
"2026-09-16T08:29:30.712Z"  "Finished clue: (check) Auth0 login: Login form is visible
"2026-09-16T08:29:30.712Z"  "Running clue: (act) Auth0 login: Fill out and submit login form
"2026-09-16T08:29:35.879Z"  "Finished clue: (act) Auth0 login: Fill out and submit login form
"2026-09-16T08:29:35.879Z"  "Running clue: (check) Auth0 login: Target page or authorization form is visible
"2026-09-16T08:29:40.090Z"  "Finished clue: (check) Auth0 login: Target page or authorization form is visible
"2026-09-16T08:29:40.090Z"  "Finished clue: The user logs in with OAauth2 and completes all Auth0 login prompts
"2026-09-16T08:29:40.090Z"  "Running clue: (act) onboard user
"2026-09-16T08:29:40.185Z"  "Finished clue: (act) onboard user
"2026-09-16T08:29:40.185Z"  "Running clue: (check) user is onboarded
"2026-09-16T08:29:42.757Z"  "Finished clue: (check) user is onboarded
"2026-09-16T08:29:42.757Z"  "Running clue: The user taps 100 amulets
"2026-09-16T08:29:42.939Z"  "Finished clue: The user taps 100 amulets
"2026-09-16T08:29:42.939Z"  "Running clue: (act) The user logs out
"2026-09-16T08:29:43.381Z"  "Finished clue: (act) The user logs out
"2026-09-16T08:29:43.381Z"  "Running clue: (check) The user sees the login screen again
"2026-09-16T08:29:43.391Z"  "Finished clue: (check) The user sees the login screen again
"2026-09-16T08:29:43.392Z"  "Running clue: A payment is created
"2026-09-16T08:29:43.746Z"  "Finished clue: A payment is created
"2026-09-16T08:29:43.746Z"  "Running clue: User has to login again
"2026-09-16T08:29:44.159Z"  "Running clue: (act) Auth0 login: Click the login button
"2026-09-16T08:29:49.356Z"  "Finished clue: (act) Auth0 login: Click the login button
"2026-09-16T08:29:49.356Z"  "Running clue: (check) Auth0 login: Login form is visible
"2026-09-16T08:30:09.359Z"  "Running clue: Dumping frontend debug info
"2026-09-16T08:30:09.476Z"  "Finished clue: Dumping frontend debug info
"2026-09-16T08:30:09.358Z"  "Failed clue: User has to login again
"2026-09-16T08:30:10.964Z"  "Test failed: 'WalletAuth0FrontendIntegrationTest/A wallet UI with a backend configured to accept auth0 tokens should redirect to the previous page after login',
```
Three things stand out: (1) the first Auth0 login of this test (08:29:28 to 08:29:40) went through, so Auth0
credentials, tenant and wallet backend all worked seconds earlier; (2) the second "Click the login button" act took
5.2s (08:29:44.159 to 08:29:49.356) versus 0.1s for the first one; (3) the "Login form is visible" check then polled
for exactly 20s (08:29:49.356 to 08:30:09.359, the `eventually()` default) and never saw an `h1`.

## 5. Browser at failure: still on the wallet login page, never reached Auth0

Page source dumped at 08:30:09.439:

```
zcat webpage-26-09-16-8.30.9.439.html.gz | grep -o '<div id="root">.*' | sed -E 's/class="[^"]*"//g' | cut -c1-330
```
```
<div id="root"><div><div ><div ><h5 >Amulet Wallet</h5><button  tabindex="0" type="button" id="oidc-login-button">Log In with OAuth2</button><div  role="separator" aria-orientation="horizontal"><span >OR</span></div><div ><div ><span  aria-hidden="true"></span><svg  focusable="false" color="white" aria-hidden="true" viewBox="0 0
```
The wallet's `Login` component (`h5` "Amulet Wallet", the `oidc-login-button`, the "OR" divider and the test-login
username field). No `h1` anywhere, hence `find(tagName("h1")) == None`.

Screenshot `screenshot-26-09-16-8.30.9.435.png` (1366x447, dark theme): the heading "AMULET WALLET", a light-blue
pill button "Log In with OAuth2", an "OR" divider, an empty "Username for test login" field outlined in red, and a
greyed-out "Log In" button. No Auth0 branding, no "Welcome" form, no error banner.

```
zcat canton_network_test.clog.gz | grep -aE '"@timestamp":"2026-09-16T08:30:09\.47' \
  | grep -aoE '"message":"(localStorage|sessionStorage) = [^"]*' | sed -E 's/[0-9a-f]{16,}/<HASH>/g'
```
```
"message":"localStorage = [oidc.<HASH>]
"message":"sessionStorage = []
```
`oidc.<state>` in localStorage is oidc-client-ts's signin state written just before it redirects. The redirect was
prepared; the browser never arrived at Auth0.

The document's resource timings (dumped by "Logging network requests") show a page loaded once, one fetch of the OIDC
discovery document, and nothing after it: no /authorize, no callback with `code=`, no token exchange.

```
zcat canton_network_test.clog.gz | grep -aE '"@timestamp":"2026-09-16T08:30:09\.4[6-7]' | python3 -c '
import sys,json
rows=[]
for l in sys.stdin:
    try: d=json.loads(l); m=d["message"]
    except Exception: continue
    if m.startswith("{"):
        e=json.loads(m); rows.append((e["startTime"],e["duration"],e["responseStatus"],e["initiatorType"],e["name"]))
print("entries:",len(rows))
for r in sorted(rows)[-3:]: print(r)
for r in rows:
    if "auth0" in r[4] or "code=" in r[4]: print("AUTH0:",r)
'
```
```
entries: 195
(156, 3, 200, 'script', 'http://localhost:3000/src/hooks/scan-proxy/useLookupAnsEntryByName.ts')
(544, 2, 0, 'other', 'https://www.hyperledger.org/hubfs/hyperledgerfavicon.png')
(557, 11, 200, 'fetch', 'https://canton-network-test.us.auth0.com/.well-known/openid-configuration')
AUTH0: (557, 11, 200, 'fetch', 'https://canton-network-test.us.auth0.com/.well-known/openid-configuration')
```

## 6. Browser console: oidc-client-ts reached "navigate: begin" and then nothing happened for 25s

The geckodriver log carries the page's console via WebDriver BiDi `log.entryAdded`. Timestamps below are the log's
epoch-ms column converted to UTC. Failing attempt (browser `randomUser.3.1`):

```
zcat browser.org.lfdecentralizedtrust.splice.integration.tests.WalletAuth0FrontendIntegrationTest.randomUser.3.1.log.gz \
  | awk -F'\t' '$1>=1789547383700 && $1<=1789547410000' \
  | grep -aE 'webdriver::server.*-> POST .*(click|/url)|browsingContext.domContentLoaded|signinRedirect: begin|navigate: begin|authorization endpoint|getJson: (url|HTTP)|screenshot' \
  | python3 -c '...epoch->UTC, strip session ids, hashes...'
```
```
08:29:43.746 WD  -> POST /session/S/url {
08:29:44.157 NAV browsingContext.domContentLoaded http://localhost:3000/confirm-payment/<HASH>
08:29:44.294 WD  -> POST /session/S/element/40ed5fec-936f-4756-9210-10a6f10e7114/click {
08:29:44.298 CON debug [oidc-client-ts]  [UserManager] signinRedirect: begin
08:29:44.304 CON debug [oidc-client-ts]  [JsonService] getJson: url: https://canton-network-test.us.auth0.com/.well-known/openid-configuration
08:29:44.316 CON debug [oidc-client-ts]  [JsonService] getJson: HTTP response received, status 200
08:29:44.317 CON debug [oidc-client-ts]  [OidcClient] createSigninRequest: Received authorization endpoint https://canton-network-test.us.auth0.com/authorize
08:29:44.318 CON debug [oidc-client-ts]  [WebStorageStateStore] set('<HASH>'): begin
08:29:44.318 CON debug [oidc-client-ts]  [UserManager] _signinStart: got signin request
08:29:44.318 CON debug [oidc-client-ts]  [RedirectNavigator] navigate: begin
08:30:09.362 WD  -> GET /session/S/element/6ac4552c-e018-432c-91bb-b4f2bf3e0da9/screenshot
```
`[RedirectNavigator] navigate: begin` is oidc-client-ts assigning `window.location` to the /authorize URL. After
it: no `domContentLoaded` for any Auth0 URL, no console warning or error, no new document, for 25s until the
failure dump. Console warn/error entries in that window:

```
awk '$1>="08:29:44.000" && $1<="08:30:10.000"' browser31.txt | grep -cE 'CON (warn|error)'
```
```
0
```

The same click 16s earlier in the same browser, for comparison:

```
awk '$1>="08:29:28.180" && $1<="08:29:31.000"' browser31.txt | grep -E 'click|navigate: begin|domContentLoaded|/value'
```
```
08:29:28.186 WD  -> POST /session/S/element/c23c6b42-9a1d-4c21-b2a2-5748c1db3714/click {
08:29:28.388 CON debug [oidc-client-ts]  [RedirectNavigator] navigate: begin
08:29:30.734 WD  -> POST /session/S/element/44152e7f-cbc3-4f2a-b7b6-e43ef2cdcce1/value {
08:29:30.757 WD  -> POST /session/S/element/756bea10-28ae-4749-8007-dcc40113650a/click {
08:29:30.767 NAV browsingContext.domContentLoaded https://canton-network-test.us.auth0.com/u/login?state=<X>
```
And in the first test's browser (`randomUser.2.1`), 56s earlier:

```
zcat browser.*WalletAuth0FrontendIntegrationTest.randomUser.2.1.log.gz | python3 -c '...ElementClick round trips, navigate: begin, domContentLoaded...'
```
```
08:28:48.563 ElementClick returned 08:28:48.635 took 0.072s
08:28:48.745 CON [RedirectNavigator] navigate: begin
08:28:49.297 NAV domContentLoaded https://canton-network-test.us.auth0.com/u/login?state=<X>
08:28:50.304 NAV domContentLoaded http://localhost:3000/?code=<X>&state=<X>
```
Normal: Auth0's login page is loaded 0.5s to 2.3s after `navigate: begin`. Failing: never.

## 7. The 5.06s click: Firefox saw `beforeunload` (navigation started) but never `pagehide` (new document never committed)

Marionette round trips for every ElementClick in the failing browser:

```
zcat browser.*WalletAuth0FrontendIntegrationTest.randomUser.3.1.log.gz | python3 -c '...pair "WebDriver:ElementClick" request ids with their responses...'
```
```
08:29:28.187 ElementClick -> 08:29:28.261 took 0.074s
08:29:30.757 ElementClick -> 08:29:35.879 took 5.122s
08:29:40.102 ElementClick -> 08:29:40.184 took 0.082s
08:29:42.774 ElementClick -> 08:29:42.830 took 0.056s
08:29:42.878 ElementClick -> 08:29:42.937 took 0.059s
08:29:42.954 ElementClick -> 08:29:43.379 took 0.425s
08:29:44.294 ElementClick -> 08:29:49.356 took 5.062s
```
Raw geckodriver lines between the click request and its response: nothing but console entries, then `h1` lookups
fail from the first poll onwards.

```
zcat browser.*WalletAuth0FrontendIntegrationTest.randomUser.3.1.log.gz | awk -F'\t' '$1>=1789547384290 && $1<=1789547389400' \
  | grep -av log.entryAdded | cut -f1,2,4 | sed -E 's#/session/[0-9a-f-]+#/session/S#; s/[0-9a-f-]{36}/<EL>/g' | cut -c1-150 | head -8
```
```
1789547384294	webdriver::server	-> POST /session/S/element/<EL>/click {
1789547384294	Marionette	0 -> [0,501,"WebDriver:ElementClick",{"id":"<EL>"}]
1789547389356	Marionette	0 <- [1,501,null,{"value":null}]
1789547389356	webdriver::server	<- 200 OK {"value":null}
1789547389357	webdriver::server	-> POST /session/S/element {
1789547389357	Marionette	0 -> [0,502,"WebDriver:FindElement",{"using":"tag name","value":"h1"}]
1789547389358	Marionette	0 <- [1,502,{"error":"no such element","message":"Unable to locate element: h1","stacktrace":"RemoteError@chrome://remote/content/shared/RemoteError.sys.mjs:8:8\nWebDriverError@chrome://remote/content/shared/webdriver/Er
```
Why 5.06s: Marionette's ElementClick waits for a possible navigation. Verified against the Firefox build that ran
(capabilities line in the same log: `"browserVersion":"152.0.1"`; the identical build is in this sandbox's nix store):

```
zcat browser.*randomUser.3.1.log.gz | grep -aoE '"browserVersion":"[^"]*"' | sort -u
unzip -p /nix/store/7k0cl16qw5g384l8kpb6nd64k3rkqy78-firefox-152.0.1/lib/firefox/omni.ja \
  chrome/remote/content/marionette/navigate.sys.mjs | awk 'NR==25||NR==26||(NR>=306&&NR<=322)||(NR>=337&&NR<=344)'
```
```
"browserVersion":"152.0.1"
// Timeout used to wait for the page to be unloaded.
const TIMEOUT_UNLOAD_EVENT = 5000;
    if (seenBeforeUnload) {
      seenBeforeUnload = false;
      unloadTimer.initWithCallback(
        onTimer,
        TIMEOUT_UNLOAD_EVENT,
        Ci.nsITimer.TYPE_ONE_SHOT
      );

      // If no page unload has been detected, ensure to properly stop
      // the load listener, and return from the currently active command.
    } else if (!seenUnload) {
      lazy.logger.trace(
        "Canceled page load listener because no navigation " +
          "has been detected"
      );
      checkDone({ finished: true });
    }
    switch (data.type) {
      case "beforeunload":
        seenBeforeUnload = true;
        break;

      case "pagehide":
        seenUnload = true;
        break;
```
After a click Marionette arms a short timer; if a `beforeunload` was seen in that window it re-arms for
`TIMEOUT_UNLOAD_EVENT` = 5000ms waiting for `pagehide`, and returns when that expires. A click that returns in
~70ms means no navigation started within the short window (the two good clicks: their `navigate: begin` came 110ms
to 200ms after the click because the discovery fetch took 178ms). A click that returns in 5.06s means the browser
DID fire `beforeunload` (the `window.location` assignment to Auth0's /authorize started a navigation) and then did
NOT fire `pagehide` within 5s: the response for the new document never arrived. It still had not 20s later.

## 8. Not the wallet backend, not Canton, not the dev server

```
zcat canton_network_test.clog.gz | grep -aE '"@timestamp":"2026-09-16T08:(29:(4[3-9]|5[0-9])|30:(0[0-9]|10))' \
  | grep -aE '"level":"(WARN|ERROR)"' | grep -av 'Trying to re-register template' \
  | grep -aoE '"@timestamp":"[^"]*"|"message":"[^"]{0,90}' | paste - -
zcat canton.clog.gz | grep -aE '"@timestamp":"2026-09-16T08:(29:(4[3-9]|5[0-9])|30:(0[0-9]|10))' | grep -acE '"level":"(WARN|ERROR)"'
```
```
"@timestamp":"2026-09-16T08:30:09.358Z"	"message":"Failed clue: User has to login again
"@timestamp":"2026-09-16T08:30:10.964Z"	"message":"Test failed: 'WalletAuth0FrontendIntegrationTest/A wallet UI with a backend configured to accept auth0 tokens
0
```
The only WARN/ERROR lines in the window are the test's own failure lines (the 174 other WARNs are the browser's
"Trying to re-register template" console spam mirrored into the log; present in passing tests too). Zero in the
canton node log. The wallet dev server (vite on :3000) started at 08:19:31 and logged nothing further:

```
zcat npm-wallet-alice.out.gz | grep -E 'VITE|Local|vite'
```
```
8:19:31 AM [vite] (client) Forced re-optimization of dependencies
  VITE v8.0.7  ready in 187 ms
  ->  Local:   http://localhost:3000/   (vite arrow glyph replaced by ->)
8:19:32 AM [vite] (client) [optimizer] bundling dependencies...
```
The page was served fine (195 resources, all 200 except the favicon 404), and the page is logged out (no
/api/validator calls), as expected after the logout step.

The wallet's login handler is a plain `signinRedirect` with `prompt: 'login'`, so an Auth0 SSO session cannot
short-circuit the form, and the wallet's own logout does not touch Auth0:

```
git show 0c43730f70:apps/common/frontend/src/contexts/UserContext.tsx | awk 'NR>=91 && NR<=99 || NR>=103 && NR<=108 {print NR": "$0}'
```
```
91:   const loginWithOidc = () => {
92:     if (auth) {
93:       // see AuthProvider.tsx's extractTargetFromUser
94:       const state = { redirectTo: window.location.href.replace(window.location.origin, '') };
95:       // We store the user id in localStorage. If it really was cleared
96:       // users should get a chance to login as a different user.
97:       auth.signinRedirect({ prompt: 'login', state });
98:     }
99:   };
103:   const signoutFromIdp = useCallback(() => {
104:     if (auth === undefined || !auth.isAuthenticated) return;
105:     auth.removeUser().finally(() => {
106:       window.location.href = window.location.origin;
107:     });
108:   }, [auth]);
```

Auth0 was slower than usual during this test even where it worked: the "Fill out and submit login form" click (the
Continue button, which posts to Auth0) took 5.122s in this test versus 1.024s in the previous test (section 7 table
and the 2.1 baseline in section 6), and "Target page ... visible" took 4.2s.

## 9. The test retries a stuck initial login for 1 minute, but not the re-login under test

```
git show 0c43730f70:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/FrontendIntegrationTest.scala \
  | awk 'NR>=585 && NR<=592 || NR==615 || NR>=636 && NR<=640 {print NR": "$0}'
```
```
585:     // Sometimes the whole auth0 login workflow gets stuck for unknown reasons.
586:     // Therefore we retry the whole workflow and take screenshots on each failed attempt.
587:     eventually(1.minutes) {
588:       try {
589:         dumpDebugInfoOnFailure {
590:           silentActAndCheck(
591:             "Auth0 login: Open target web page",
592:             go to url,
615:           loginViaAuth0InCurrentPage(username, password, assertCompleted)
636:           // Finally, navigate away from the current page to clear any JavaScript state
637:           go to "about:blank"
638:           throw e
639:       }
640:     }
```
`completeAuth0LoginWithAuthorization` (used by `withAuth0LoginCheck` for the first login) wraps the whole flow in a
1-minute retry that clears Auth0 cookies and `oidc.*` storage and reloads, precisely because "the whole auth0 login
workflow gets stuck for unknown reasons" (comment dates from the initial import, b6086ad603). The
"User has to login again" step calls `loginViaAuth0InCurrentPage` directly (section 2, line 106), so the same stall
there is fatal on the first occurrence. This packet is a captured instance of the "unknown reasons": the top-level
navigation to /authorize does not complete.

## Root cause / hypothesis

Proven from the artifacts:
- The test failed at its last step: after `go to /confirm-payment/<cid>` and clicking "Log In with OAuth2",
  Auth0's login form (`h1` "Welcome") never appeared within the 20s `eventually()`; the browser was still on the
  wallet login page at the dump (page source, screenshot, resource timings, localStorage).
- oidc-client-ts got as far as `[RedirectNavigator] navigate: begin` at 08:29:44.318 (discovery document fetched,
  signin state stored, /authorize URL built) and no document ever replaced the wallet page.
- Marionette's ElementClick took 5.062s, which with Firefox 152.0.1's `navigate.sys.mjs` means `beforeunload` fired
  (the navigation to /authorize was started by the browser) but `pagehide` did not within 5000ms: the /authorize
  response never committed. The two earlier, successful clicks in the same suite reached Auth0 in 0.5s to 2.3s.
- No console error, no WARN/ERROR in the wallet/validator/canton logs, dev server healthy; the same Auth0 tenant
  had just completed a login for this same user 9s earlier.

Inferred:
- The top-level GET https://canton-network-test.us.auth0.com/authorize?... hung (no response headers for >25s):
  either on Auth0's side (the tenant was measurably slower during this test, 5.1s vs 1.0s for the form post) or in
  the runner's egress path to Auth0. The geckodriver log has no BiDi network events (count 0) and the resource
  timings of the old document do not record a top-level navigation, so the artifacts cannot distinguish the two.
- Nothing in Splice code changed the outcome: no wallet, common-frontend auth or test-helper change touched this
  flow recently (latest relevant commits: test file 454a93e1a5, common auth ab5dbaa163 "Remove offline_access scope",
  FrontendIntegrationTest a4f33265f1/8f90278f93 for WG/Portfolio preflight).

## Duplicates / related

- No prior ci-triage packet mentions WalletAuth0FrontendIntegrationTest or "redirect to the previous page"
  (`grep -rl 'WalletAuth0\|redirect to the previous page\|Auth0' ci-triage/` -> no matches).
- Failed-job lists of the 17 most recent main failures (gh run list, 2026-09-07 to 2026-09-11) show no other
  frontend-wall-clock-time (2) failure; run 34477382133 in packet 10094 section 7 failed the same job on
  SvFrontendIntegrationTest, a different test. No confirmed recurrence of this test in that sample.
- Same family as the "auth0 login workflow gets stuck" flake the initial-login retry in
  `completeAuth0LoginWithAuthorization` already guards against.
- Other callers of the unguarded `loginViaAuth0InCurrentPage`: only this test (line 106) and the guarded call at
  FrontendIntegrationTest.scala:615.

## Suggested next step / owner

- Test-side (wallet frontend test owners): give the "User has to login again" step the same resilience as the
  first login: wrap `go to .../confirm-payment/<cid>` + `loginViaAuth0InCurrentPage` in the `eventually(1.minute)`
  retry that clears Auth0 cookies and `oidc.*` storage (extract the retry from `completeAuth0LoginWithAuthorization`
  so it takes the pre-login navigation as a parameter). The redirect-back-to-previous-page assertion is unaffected
  because each attempt re-navigates to the confirm-payment URL first.
- Observability: subscribe the BiDi `network` module (or log `browsingContext.navigationStarted`/`navigationFailed`)
  in the frontend test driver so a stalled top-level navigation shows up as a request with no response rather than
  as 25s of silence; that would settle Auth0-vs-egress next time.
- No action for wallet backend, validator or Canton.

## Summary

frontend-wall-clock-time (2), job 104716753202, canton 3.6.0-snapshot.20260910.20260.0.v90621933. 49 of 50 tests
passed; WalletAuth0FrontendIntegrationTest "redirect to the previous page after login" failed at its final step with
"None was equal to None" = `find(tagName("h1")) should not be None` (FrontendIntegrationTest.scala:565): Auth0's
login form never appeared after clicking "Log In with OAuth2" on /confirm-payment. The browser log shows
oidc-client-ts reached `navigate: begin` (redirect to /authorize issued) and Marionette's 5.062s click proves
`beforeunload` fired but no new document ever committed; 25s later the page was still the wallet login page. Same
click succeeded twice earlier in the suite; Auth0 was measurably slower during this test. External-IdP navigation
stall, a flake; the re-login step lacks the 1-minute stuck-login retry that the initial login already has.
