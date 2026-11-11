# 11361 - AnsFrontendIntegrationTest: search-entry-button stale between clickable check and click (run 43031077112)

DUPLICATE of 11187 (open, assigned stephencompall-DA, fix PR canton-network/splice#8418 open, not merged). Post-merge
main 644b3ba97a (#8424), job `frontend-wall-clock-time (0)`, canton 3.7.3-snapshot.20261109.20894.0.ve6e6c252, 3 tests
passed, 1 failed: `allow requesting an entry with subscription payments and then list it` with
`StaleElementReferenceException` on `search-entry-button` in the clue `requesting an existing name to check the already
taken message`: same test, clue, element and helper frame as 11187 and 11300. The 500 ms debounced name lookup fired
between `eventuallyClickOn`'s clickable check (14:10:39.011) and its click (failed 14:10:39.154); the validator received
the lookup at 14:10:39.152. Third failure of this shard on main since 2026-11-04 (3 of 98 jobs), all three this race.

- Run: https://github.com/canton-network/splice/actions/runs/43031077112, main 644b3ba97a ("Pause faucet coupon
  automation in ScanEventHistoryIntegrationTest (#8424)"), job 130559083969
  `ci / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (0)`, attempt 1.
- Runtime canton: 3.7.3-snapshot.20261109.20894.0.ve6e6c252.
- Component: test (frontend; `eventuallyClickOn` against a button that `DisableConditionally` remounts).

## 0. Issue description (verbatim)

```
$ gh api repos/DACH-NY/cn-test-failures/issues/11361 --jq '"#\(.number) [\(.state)] \(.title)\n\(.body)"'
#11361 [open] GHA Run 43031077112 : job frontend-wall-clock-time (0) in scala_test Failed :fire:

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/43031077112).
Job: frontend-wall-clock-time (0) in scala_test
Branch: [refs/heads/main](https://github.com/canton-network/splice/tree/refs/heads/main)
Workflow: CI post-merge to main or release-line branches
Commit: [644b3ba](https://github.com/canton-network/splice/commit/644b3ba97ab4c3cf188eea599a0e53ef3e2cf6ad) Pause faucet coupon automation in ScanEventHistoryIntegrationTest (#8424)
Actor: rautenrieth-da
```

Job id from the inbox round's job map (`log/inbox-1111b/jobs.tsv`), confirmed:

```
$ gh api repos/canton-network/splice/actions/jobs/130559083969 --jq '"\(.name) attempt=\(.run_attempt) \(.conclusion) \(.started_at) \(.completed_at)"'
ci / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (0) attempt=1 failure 2026-11-11T13:53:08Z 2026-11-11T14:11:41Z
```

## 1. Failing assertion

```
$ L=log/inbox-1111b/130559083969.log
$ sed -E 's/^[^Z]*Z //' $L | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed|##\[error\]' | sort -u | head
##[error]Error: failed to run script step (id 90009df0-ddd8-11f1-8b34-618d61297dc4): Error: step failed with return code 1
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
##[error]Process completed with exit code 1.
[info] *** 1 TEST FAILED ***
[info] - allow requesting an entry with subscription payments and then list it *** FAILED ***
[info] - should allow requesting an entry with subscription payments and then list it *** FAILED ***
[info] Run completed in 6 minutes, 37 seconds.
[info] Tests: succeeded 3, failed 1, canceled 0, ignored 0, pending 0
$ sed -E 's/^[^Z]*Z //' $L | grep -a -A40 'subscription payments and then list it \*\*\* FAILED' | grep -a -E 'FAILED|StaleElementReferenceException|Element: |FrontendIntegrationTest\.scala|AnsFrontendIntegrationTest\.scala' | cut -c1-230 | head -8
[info] - should allow requesting an entry with subscription payments and then list it *** FAILED ***
[info]   org.openqa.selenium.StaleElementReferenceException: The element with the reference fb6b3855-3085-472a-beef-d4a77d8651b4 is stale; either its node document is not the active document, or it is no longer connected to the DO
[info] Element: [[FirefoxDriver: firefox on linux (a466872b-5be7-444f-baee-b347ca377df6)] -> id: search-entry-button]
[info]   at org.lfdecentralizedtrust.splice.integration.tests.FrontendIntegrationTestWithIsolatedEnvironment.clickOn(FrontendIntegrationTest.scala:105)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.FrontendTestCommon.eventuallyClickOn(FrontendIntegrationTest.scala:866)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.FrontendTestCommon.eventuallyClickOn$(FrontendIntegrationTest.scala:854)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.FrontendIntegrationTestWithIsolatedEnvironment.eventuallyClickOn(FrontendIntegrationTest.scala:105)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.AnsFrontendIntegrationTest.$anonfun$new$6(AnsFrontendIntegrationTest.scala:51)
```

## 2. The failing step in the test code at the run sha

AnsFrontendIntegrationTest.scala:51 is the search click of the already-taken clue; FrontendIntegrationTest.scala:866 is
the `clickOn` after a one-shot clickable wait and a scroll (no retry):

```
$ git show 644b3ba97a:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/AnsFrontendIntegrationTest.scala | sed -n '45,53p'
        clue("requesting an existing name to check the already taken message") {
          waitForQuery(id("entry-name-field"))
          eventuallyClickOn(id("entry-name-field"))
          textField("entry-name-field").value = entryNameWithoutSufffix

          waitForCondition(id("search-entry-button")) { ExpectedConditions.elementToBeClickable(_) }
          eventuallyClickOn(id("search-entry-button"))
          waitForQuery(id("unavailable-icon"))
        }
$ git show 644b3ba97a:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/FrontendIntegrationTest.scala | sed -n '852,868p'
  }

  protected def eventuallyClickOn(query: Query)(implicit driver: WebDriver) = {
    clue(s"Waiting for $query to be clickable") {
      waitForCondition(query) {
        ExpectedConditions.elementToBeClickable(_)
      }
    }
    driver
      .asInstanceOf[JavascriptExecutor]
      .executeScript(
        "arguments[0].scrollIntoView({block: 'center'});",
        driver.findElement(query.by),
      )
    clickOn(query)
  }
$ git show 644b3ba97a:nix/canton-sources.json | grep -m1 version
  "version": "3.7.3-snapshot.20261109.20894.0.ve6e6c252",
```

## 3. Timeline: the name lookup started between the clickable check and the click

Artifact `logs-frontend-wall-clock-time-0` (13861499899, created 2026-11-11T14:11:21Z, attempt 1), in `log/11361/art/`.

```
$ A=log/11361/art/canton_network_test.clog.gz
$ zcat $A | jq -r 'select(.logger_name|test("AnsFrontendIntegrationTest:AnsFrontendIntegrationTest$|LogReporter")) | select(.message|test("clue|Caught error|StaleElement")) | [.["@timestamp"][11:23], .message[0:230]]|@tsv' | grep -a -B2 -A3 'already taken' | head -20
14:10:33.939	Finished clue: Waiting for ClassNameQuery(sub-request-accept-button) to be clickable
14:10:38.467	Finished clue: Reserving ans name: mycool_entry.unverified.ans
14:10:38.468	Running clue: requesting an existing name to check the already taken message
14:10:38.487	Running clue: Waiting for IdQuery(entry-name-field) to be clickable
14:10:38.520	Finished clue: Waiting for IdQuery(entry-name-field) to be clickable
14:10:38.744	Running clue: Waiting for IdQuery(search-entry-button) to be clickable
14:10:39.011	Finished clue: Waiting for IdQuery(search-entry-button) to be clickable
14:10:39.154	Failed clue: requesting an existing name to check the already taken message
14:10:39.155	Caught error org.openqa.selenium.StaleElementReferenceException: The element with the reference fb6b3855-3085-472a-beef-d4a77d8651b4 is stale; either its node document is not the active document, or it is no longer connected to th
14:10:39.155	Running clue: Dumping frontend debug info
14:10:39.156	Running clue: Saving screenshot
$ zcat $A | jq -r 'select(.["@timestamp"]>="2026-11-11T14:10:38.95" and .["@timestamp"]<="2026-11-11T14:10:39.2") | select(.message|test("by-name|mycool_entry|Render|RequestAnsEntry|clue";"i")) | [.["@timestamp"][11:23], .logger_name[0:60], .message[0:200]]|@tsv' | head -30
14:10:39.011	o.l.s.i.t.AnsFrontendIntegrationTest:AnsFrontendIntegrationT	Finished clue: Waiting for IdQuery(search-entry-button) to be clickable
14:10:39.145	o.l.s.i.t.AnsFrontendIntegrationTest:AnsFrontendIntegrationT	Render
14:10:39.146	o.l.s.i.t.AnsFrontendIntegrationTest:AnsFrontendIntegrationT	RequestAnsEntry
14:10:39.152	o.l.s.a.a.HttpRequestLogger:AnsFrontendIntegrationTest/confi	HTTP GET /api/validator/v0/scan-proxy/ans-entries/by-name/mycool_entry.unverified.ans from (127.0.0.1:43548): received request.
14:10:39.153	o.l.s.a.a.HttpRequestLogger:AnsFrontendIntegrationTest/confi	HTTP GET /api/validator/v0/scan-proxy/ans-entries/by-name/mycool_entry.unverified.ans from (127.0.0.1:43548): omitting logging of request entity data.
14:10:39.154	o.l.s.v.ValidatorApp:AnsFrontendIntegrationTest/config=9f689	HTTP client (GET /api/scan/v0/ans-entries/by-name/mycool_entry.unverified.ans): omitting logging of request entity data.
14:10:39.154	o.l.s.i.t.AnsFrontendIntegrationTest:AnsFrontendIntegrationT	Failed clue: requesting an existing name to check the already taken message
14:10:39.155	o.l.s.i.t.AnsFrontendIntegrationTest:AnsFrontendIntegrationT	Running clue: Dumping frontend debug info
14:10:39.156	o.l.s.i.t.AnsFrontendIntegrationTest:AnsFrontendIntegrationT	Running clue: Saving screenshot
14:10:39.157	o.l.s.a.a.HttpRequestLogger:AnsFrontendIntegrationTest/confi	HTTP GET /api/scan/v0/ans-entries/by-name/mycool_entry.unverified.ans from (127.0.0.1:52930): received request.
14:10:39.158	o.l.s.a.a.HttpRequestLogger:AnsFrontendIntegrationTest/confi	HTTP GET /api/scan/v0/ans-entries/by-name/mycool_entry.unverified.ans from (127.0.0.1:52930): omitting logging of request entity data.
14:10:39.160	o.l.s.a.a.HttpRequestLogger:AnsFrontendIntegrationTest/confi	HTTP GET /api/scan/v0/ans-entries/by-name/mycool_entry.unverified.ans from (127.0.0.1:52930): Responding with status code: 200 OK, took 2 ms
14:10:39.160	o.l.s.a.a.HttpRequestLogger:AnsFrontendIntegrationTest/confi	HTTP GET /api/scan/v0/ans-entries/by-name/mycool_entry.unverified.ans from (127.0.0.1:52930): Responding with entity data: {"entry":{"contract_id":"0096c70b6b9ac06a1bdc35eb436b39ccaa70c8313d7475b3d133
14:10:39.161	o.l.s.v.ValidatorApp:AnsFrontendIntegrationTest/config=9f689	HTTP client (GET /api/scan/v0/ans-entries/by-name/mycool_entry.unverified.ans): HTTP request took 6 ms to complete. Received response with status code: 200 OK
14:10:39.161	o.l.s.v.ValidatorApp:AnsFrontendIntegrationTest/config=9f689	HTTP client (GET /api/scan/v0/ans-entries/by-name/mycool_entry.unverified.ans): Received response with entity data: {"entry":{"contract_id":"0096c70b6b9ac06a1bdc35eb436b39ccaa70c8313d7475b3d13395f6c4c
14:10:39.162	o.l.s.a.a.HttpRequestLogger:AnsFrontendIntegrationTest/confi	HTTP GET /api/validator/v0/scan-proxy/ans-entries/by-name/mycool_entry.unverified.ans from (127.0.0.1:43548): Responding with status code: 200 OK, took 9 ms
14:10:39.162	o.l.s.a.a.HttpRequestLogger:AnsFrontendIntegrationTest/confi	HTTP GET /api/validator/v0/scan-proxy/ans-entries/by-name/mycool_entry.unverified.ans from (127.0.0.1:43548): omitting logging of response entity data.
14:10:39.189	o.l.s.i.t.AnsFrontendIntegrationTest:AnsFrontendIntegrationT	Render
14:10:39.189	o.l.s.i.t.AnsFrontendIntegrationTest:AnsFrontendIntegrationT	scan-proxy calling GET http://localhost:5503/api/validator/v0/scan-proxy/ans-entries/by-name/mycool_entry.unverified.ans with body: undefined [00-54b0e196c1773f962986f5d11439a68b-b561f3d6070831f0-01]
```

The validator received the browser's lookup at 14:10:39.152, after the clickable check finished (.011) and before the
click failed (.154). The browser console lines (`Render`, `scan-proxy calling GET`) reach this log with a delay (the
`scan-proxy calling GET` console line is logged at .189, after the validator had already answered at .162), so the
validator timestamp is the reliable one for when the lookup started.

## 4. Why the lookup starts then and why it remounts the button

The typed name is applied through a 500 ms debounce; the button sits in `DisableConditionally`, disabled while the
lookup is loading. `DisableConditionally` returns the bare child when enabled and the child wrapped in
`<Stack><Tooltip><span>` when disabled, a different element tree, so React unmounts and remounts the button on each
toggle and Selenium's reference goes stale.

```
$ git show 644b3ba97a:apps/ans/frontend/src/components/RequestAnsEntry.tsx | sed -n '24,36p'
  const ENTRY_NAME_SUFFIX = toEntryNameSuffix(config.spliceInstanceNames.nameServiceNameAcronym);
  const [entryName, setEntryName] = useState<string>('');
  const debounced = useDebouncedCallback(value => {
    setEntryName(value);
    setDisplayValidationResult(true);
  }, 500);

  const [displayValidationResult, setDisplayValidationResult] = useState(false);
  const primaryPartyId = usePrimaryParty();
  const { data: entryLookupResult, isLoading } = useLookupAnsEntryByName(
    toFullEntryName(entryName, ENTRY_NAME_SUFFIX),
    !!primaryPartyId
  );
$ git show 644b3ba97a:apps/ans/frontend/src/components/RequestAnsEntry.tsx | grep -n -E 'debounce|DisableConditionally|search-entry-button|nameLookupStatus === .loading|onChange|flush'
5:  DisableConditionally,
10:import { useDebouncedCallback } from 'use-debounce';
26:  const debounced = useDebouncedCallback(value => {
56:              debounced.flush();
61:          onChange={event => debounced(event.target.value)}
64:        <DisableConditionally
65:          conditions={[{ disabled: nameLookupStatus === 'loading', reason: 'Loading...' }]}
69:            id="search-entry-button"
72:              debounced.flush();
77:        </DisableConditionally>
100:  if (nameLookupStatus === 'loading' || !ansRules) {
$ git show 644b3ba97a:apps/common/frontend/src/components/DisableConditionally.tsx | sed -n '38,62p'
const DisableConditionally: React.FC<DisableConditionallyProps> = props => {
  const condition = props.conditions.find(c => c.disabled);
  if (condition !== undefined) {
    if (!React.isValidElement(props.children)) {
      throw new Error('DisableConditionally: children must be a valid React element');
    }
    const disabledChild = React.cloneElement(props.children, { disabled: true });
    return (
      <Stack>
        {condition.severity && (
          <Alerting alertState={{ severity: condition.severity, message: condition.reason }} />
        )}
        <Tooltip describeChild title={condition.reason}>
          {/*Span is needed to make the tooltip work with disabled elements, see https://mui.com/material-ui/react-tooltip/#disabled-elements*/}
          <span>{disabledChild}</span>
        </Tooltip>
      </Stack>
    );
  } else {
    return props.children;
  }
};

export default DisableConditionally;
```

The race is structural: the test types the name (between the entry-name-field clue finishing at 14:10:38.520 and the
search-button clue starting at .744, section 3) and then immediately waits for the button and clicks it, so the click
lands inside the 500 ms debounce window (about 39.02 to 39.24), when the lookup fires on its own. Inferred: the exact
keystroke time is not logged, only the bounds above.

## 5. The page at failure shows the expected result

```
$ zcat log/11361/art/screenshot-26-11-11-14.10.39.244.png.gz > log/11361/shot/screenshot.png   # viewed
```
The screenshot (14:10:39.244) shows the search field `mycool_entry` `.unverified.ans`, the Search button, and
"mycool_entry.unverified.ans is not available. Please try a different name.": the debounced lookup completed without
the click, so the app behaved correctly and only the test's held element reference was stale.

## 6. Duplicate search

```
$ gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue StaleElementReferenceException'
total 4
11187 open/- 2026-11-04 eventuallyClickOn: element was replaced between check and click
10270 closed/completed 2026-10-01 test transfers that rely on preceding taps don't wait long enough for the tap to complete
6828 closed/completed 2026-01-20 SvFrontendIntegrationTest: "can create valid SRARC_SetConfig" `StaleElementReferenceException`
1411 closed/not_planned 2024-12-17 Validator1PreflightIntegrationTest: StaleElementReferenceException
$ gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "subscription payments and then list it"'
total 1
8105 closed/duplicate 2026-04-17 GHA Run 24576010875 : job frontend-wall-clock-time (1) in scala_test Failed :fire:
$ gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue search-entry-button'
total 2
11187 open/- 2026-11-04 eventuallyClickOn: element was replaced between check and click
11300 closed/duplicate 2026-11-09 GHA Run 42637895044 : job frontend-wall-clock-time (0) in scala_test Failed :fire:
```
(`--jq` per query: `"total \(.total_count)", (.items[] | "\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:10]) \(.title[0:100])")`.)

Per hit:
- 11187: same failure (same test, clue `requesting an existing name to check the already taken message`, element
  `search-entry-button`, `eventuallyClickOn(FrontendIntegrationTest.scala:866)`; packet `11187-ans-search-button-stale-element.md`). Parent.
- 11300: same failure, closed as duplicate of 11187 (packet `11300-ans-search-button-stale-dup-11187.md`).
- 10270, 6828, 1411: other tests (see the 11187 packet, section 3), not this failure.
- 8105: same test, different failure; its comment: `A timeout occurred waiting for a future to complete. Waited 20 seconds.` in `stopWebDrivers`, after `Finished clue: requesting an existing name to check the already taken messag`.

Parent state:
```
$ for n in 11187 11300; do gh api repos/DACH-NY/cn-test-failures/issues/$n --jq '"#\(.number) [\(.state)/\(.state_reason)] assignees=\([.assignees[].login]|join(",")) \(.title)"'; done
#11187 [open/null] assignees=stephencompall-DA eventuallyClickOn: element was replaced between check and click
#11300 [closed/duplicate] assignees= GHA Run 42637895044 : job frontend-wall-clock-time (0) in scala_test Failed :fire:
$ gh api "repos/DACH-NY/cn-test-failures/issues/11187/timeline?per_page=100" --jq '.[] | select(.event=="cross-referenced" or .event=="assigned" or .event=="connected" or .event=="labeled") | "\(.created_at) \(.event) \(.assignee.login // "") \(.source.issue.html_url // "") \(.source.issue.title // "") \(.source.issue.state // "")"'
2026-11-10T21:47:40Z labeled    
2026-11-10T21:47:40Z assigned stephencompall-DA   
```

## 7. Fix PR exists, not merged

```
$ gh api -X GET search/issues -f q='repo:canton-network/splice is:pr 11187' --jq '.items[] | "\(.number) \(.state) \(.created_at[0:10]) \(.pull_request.merged_at // "-") \(.user.login) \(.title)"'
8418 open 2026-11-10 - stephencompall-DA Re-find and re-click the ANS search button until its result shows in AnsFrontendIntegrationTest
8034 closed 2026-10-27 - dependabot[bot] [ci] Bump vitest from 4.1.12 to 5.0.2 in /apps
8045 closed 2026-10-27 2026-10-29T08:28:12Z dependabot[bot] [ci] Bump @vitest/ui from 4.1.12 to 5.0.2 in /apps
$ gh api repos/canton-network/splice/pulls/8418 --jq '"\(.number) \(.state) draft=\(.draft) merged=\(.merged_at) head=\(.head.sha[0:10]) \(.title)\n\(.body)"'
8418 open draft=false merged=null head=191b5da32f Re-find and re-click the ANS search button until its result shows in AnsFrontendIntegrationTest
Re-find and re-click the ANS search button until its result shows, since the button is remounted while the lookup runs.

Fixes DACH-NY/cn-test-failures#11187.
$ gh api repos/canton-network/splice/pulls/8418/reviews --jq '.[] | "\(.user.login) \(.state) \(.submitted_at)"'
$ gh api repos/canton-network/splice/pulls/8418 --jq '"created \(.created_at) updated \(.updated_at) mergeable_state=\(.mergeable_state)"'
created 2026-11-10T21:47:50Z updated 2026-11-10T21:47:51Z mergeable_state=blocked
$ gh api "repos/canton-network/splice/commits/191b5da32f/check-runs?per_page=100" --jq '.check_runs[] | select(.conclusion=="failure") | "\(.name) \(.html_url)"'
ci / final_result https://github.com/canton-network/splice/actions/runs/42908499959/job/130201158716
ci / scala_test_resource_intensive / resource-intensive (0) https://github.com/canton-network/splice/actions/runs/42908499959/job/130153320248
$ gh api "repos/canton-network/splice/commits/191b5da32f/check-runs?per_page=100" --jq '.check_runs[] | select(.name|test("frontend-wall-clock-time")) | "\(.name) \(.conclusion)"'
ci / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (0) success
ci / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (2) success
ci / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (1) success
```
The PR (only `AnsFrontendIntegrationTest.scala`, +15 -10) replaces the clickable wait + click + `waitForQuery(unavailable-icon)`
in all three search steps with `eventually() { eventuallyClickOn(id("search-entry-button")); waitForQuery(id("unavailable-icon"), Some(5.seconds)) }`.
No reviews yet; the PR run failed in `resource-intensive (0)` (not inspected here; the PR does not touch that shard's
tests), so it is blocked. The run sha 644b3ba97a cannot contain it (PR not merged).

## 8. Frequency on main since 11187 was filed

All post-merge runs on main created since 2026-11-04 (96 runs), and their `frontend-wall-clock-time (0)` jobs:

```
$ for p in 1 2 3 4; do gh api "repos/canton-network/splice/actions/workflows/163795441/runs?branch=main&per_page=40&page=$p&created=>=2026-11-04" --jq '.workflow_runs[] | "\(.id) \(.run_attempt) \(.head_sha[0:10]) \(.created_at) \(.status) \(.conclusion)"'; done > log/11361/runs.txt
$ fe0jobs.sh log/11361/runs.txt > log/11361/fe0jobs.txt    # script below
$ awk '{print $1}' log/11361/runs.txt | sort -u | wc -l
96
$ awk '{print $3}' log/11361/fe0jobs.txt | sort | sed -n '1p;$p'
2026-11-04T00:48:27Z
2026-11-11T18:30:00Z
$ awk '{print $NF}' log/11361/fe0jobs.txt | sort | uniq -c
      3 failure
     95 success
$ grep -v success log/11361/fe0jobs.txt
43031077112 644b3ba97a 2026-11-11T13:39:19Z job=130559083969 attempt=1 failure
42637895044 7d3d5f17da 2026-11-09T14:46:05Z job=129231327555 attempt=1 failure
41844793457 83157dad9c 2026-11-04T14:09:41Z job=126627038545 attempt=1 failure
```
`fe0jobs.sh` (run outside the repo):
```
#!/bin/bash
# For each run id in $1, print the frontend-wall-clock-time (0) job(s) with conclusion.
while read -r run att sha created st concl; do
  for p in 1 2 3 4 5; do
    out=""
    for i in 1 2 3 4; do out=$(gh api "repos/canton-network/splice/actions/runs/$run/jobs?filter=all&per_page=40&page=$p" 2>/dev/null) && break; sleep 4; done
    n=$(echo "$out" | jq '.jobs | length')
    echo "$out" | jq -r --arg run "$run" --arg sha "$sha" --arg c "$created" '.jobs[] | select(.name | endswith(" / frontend-wall-clock-time (0)")) | "\($run) \($sha) \($c) job=\(.id) attempt=\(.run_attempt) \(.conclusion)"'
    [ "${n:-0}" -lt 40 ] && break
  done
done < "$1"
```
The three failed jobs are those of 11361 (this packet), 11300 (job 129231327555, its packet section 1) and 11187 (job
126627038545, its packet header): every failure of this shard on main in the week was this race, 3 of 98 jobs.

## Verdict

Duplicate of 11187 (family F, frontend test infrastructure). Test flake, test-only fix: canton-network/splice#8418
(open, by the 11187 assignee stephencompall-DA, blocked on an unrelated `resource-intensive (0)` failure and no review)
retries click plus result check together. Recurrence: 3 of 98 `frontend-wall-clock-time (0)` jobs on main since
2026-11-04, i.e. about once every 2 to 3 days; merging #8418 removes it. Not verified: the exact remount moment
(browser console relay is delayed; inferred from the validator timestamp and the debounce window); why #8418's
resource-intensive (0) failed.
