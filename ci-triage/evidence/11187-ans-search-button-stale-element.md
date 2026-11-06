# 11187 - AnsFrontendIntegrationTest StaleElementReferenceException on search-entry-button (run 41844793457)

new. main 83157dad9c7d, job 126627038545 `frontend-wall-clock-time (0)` (attempt 1). Selenium race: the search button
re-rendered between `eventuallyClickOn`'s clickable wait and its click.

- Run: https://github.com/canton-network/splice/actions/runs/41844793457, main 83157dad9c7d, job 126627038545.
- Component: test (FrontendTestCommon.eventuallyClickOn).

## 1. Failing assertion
```
sed -E 's/^[0-9T:.-]+Z //; s/\x1b\[[0-9;]*m//g' log/11187/job.log | grep -a -A8 'subscription payments and then list it \*\*\* FAILED'
```
```
[info] - should allow requesting an entry with subscription payments and then list it *** FAILED ***
[info]   org.openqa.selenium.StaleElementReferenceException: The element with the reference b5131b1e-... is stale; either its node document is not the active document, or it is no longer connected to the DOM
[info] Element: [[FirefoxDriver: firefox on linux (...)] -> id: search-entry-button]
  at ...FrontendTestCommon.eventuallyClickOn(FrontendIntegrationTest.scala:866)
  at ...AnsFrontendIntegrationTest.$anonfun$new$6(AnsFrontendIntegrationTest.scala:51)
```

## 2. Mechanism
`eventuallyClickOn` (FrontendIntegrationTest.scala:854-866 at 83157dad9c7d) waits once for `elementToBeClickable`,
then scrolls and clicks without a retry. AnsFrontendIntegrationTest.scala:49-51 types the entry name and clicks
`search-entry-button`; a React re-render of the button between the wait and the click leaves a stale reference.

## 3. Frequency
```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue StaleElementReferenceException'
```
```
total 3: 10270 (2026-10-01), 6828 (2026-01-20), 1411 (2024-12-17)
```
Per hit (`gh issue view <n> --json title,body,comments`, grep StaleElementReferenceException), checked 2026-11-06:
- 10270: not the same failure; only a quoted code comment ("robust against `StaleElementReferenceException` errors").
- 6828 (SvFrontendIntegrationTest, 2026-01-20): same exception, `The element with the reference 064b2797-... is stale`.
- 1411 (Validator1PreflightIntegrationTest, 2024-12-17): same exception, `The element with the reference 873a0e3c-... is stale`.
So two earlier hits of the exception in other tests, none since January.

## Verdict
New, rare Selenium race in a shared helper. Possible fix (test only): retry the click in `eventuallyClickOn` on
StaleElementReferenceException. Not verified: what re-rendered the button (no browser trace of the DOM).

## 4. Test log (added 2026-11-06, artifact logs-frontend-wall-clock-time-0, 13336017364)

```
zcat log/11187/art/canton_network_test.clog.gz | jq -r 'select(.["@timestamp"]>="2026-11-04T14:55:07" and .["@timestamp"]<="2026-11-04T14:55:22") | select(.logger_name|test("AnsFrontendIntegrationTest:AnsFrontendIntegrationTest$|LogReporter")) | [.["@timestamp"][11:23], .message]|@tsv'
zcat log/11187/art/canton_network_test.clog.gz | jq -r 'select(.["@timestamp"]>="2026-11-04T14:55:07.5" and .["@timestamp"]<="2026-11-04T14:55:07.86") | select(.logger_name|test("web-frontend")) | [.["@timestamp"][11:23], .message]|@tsv'
```
```
14:55:07.130 Running clue: requesting an existing name to check the already taken message
14:55:07.541 Running clue: Waiting for IdQuery(search-entry-button) to be clickable
14:55:07.793 Finished clue: Waiting for IdQuery(search-entry-button) to be clickable
14:55:07.795 web-frontend=alice | Render
14:55:07.796 web-frontend=alice | RequestAnsEntry
14:55:07.854 Failed clue: requesting an existing name to check the already taken message
14:55:07.855 Caught error org.openqa.selenium.StaleElementReferenceException: The element with the reference b5131b1e-... is stale; ...
```
The failing step is the second search (after "Reserving ans name: mycool_entry.unverified.ans", 14:55:07.130), not the
first. The `Render` / `RequestAnsEntry` console lines are React render-trace output of alice's page; they show a render of
the component holding the button between the clickable check and the click, not which state change caused it.

## 5. What replaced the button (added 2026-11-06)

```
zcat log/11187/art/canton_network_test.clog.gz | jq -r 'select(.["@timestamp"]>="2026-11-04T14:55:07.17" and .["@timestamp"]<="2026-11-04T14:55:08.2") | select(.message|test("by-name|ans-entries|mycool_entry";"i")) | [.["@timestamp"][11:23], .logger_name, .message]|@tsv'
git show FETCH_HEAD:apps/ans/frontend/src/components/RequestAnsEntry.tsx | sed -n 50,77p
git show FETCH_HEAD:apps/common/frontend/src/components/DisableConditionally.tsx | sed -n 38,60p
```
```
14:55:07.801 web-frontend=alice | scan-proxy calling GET http://localhost:5503/api/validator/v0/scan-proxy/ans-entries/by-name/mycool_entry.unverified.ans with body: undefined ...
14:55:07.811 validator=aliceValidator | HTTP GET /api/validator/v0/scan-proxy/ans-entries/by-name/mycool_entry.unverified.ans from (127.0.0.1:40760): Responding with status code: 200 OK
```
RequestAnsEntry.tsx: `<DisableConditionally conditions={[{ disabled: nameLookupStatus === 'loading', reason: 'Loading...' }]}>`
around `<Button id="search-entry-button">`; the Searchbar's `onChange` calls `debounced(...)`, `onKeyDown` Enter calls
`setDisplayValidationResult(true); debounced.flush()` (same as the button's onClick).
DisableConditionally.tsx: disabled -> `<Stack>... <Tooltip ...><span>{cloneElement(child, {disabled: true})}</span></Tooltip></Stack>`,
enabled -> `props.children`. Different element trees, so React unmounts and remounts the Button on each toggle.

Mechanism: the debounced lookup started at 14:55:07.801, 8 ms after the clickable check (.793), switching the button to
the disabled tree; the click inside `clickOn` (FrontendIntegrationTest.scala:866) at ~.854 hit the old node.
Inferred, not shown: the exact remount moments (the render-trace lines `MuiStackRoot`/`ForwardRef(Tooltip)` at .797 fit
the disabled tree but other components also render those).

PR 3604 (6828) re-found the element inside `eventually` by calling `checkNewVoteRequestInProgressTab` again; the query
was unchanged. `eventuallyClickOn` already finds by query at click time, so the window here is inside `clickOn`.
A retry of `clickOn` alone can land on the disabled button (Selenium clicks it without error, onClick does not fire).
Fix options: DisableConditionally renders the same tree in both states; or the test presses Enter in entry-name-field.
