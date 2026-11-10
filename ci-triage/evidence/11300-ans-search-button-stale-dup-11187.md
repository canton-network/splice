# 11300 - AnsFrontendIntegrationTest: search-entry-button stale between clickable check and click (run 42637895044)

DUPLICATE of 11187 (open, unassigned, no fix PR linked). Post-merge main 7d3d5f17da (#8365), job
`frontend-wall-clock-time (0)`, canton 3.7.2, 3 tests passed, 1 failed: `allow requesting an entry with subscription
payments and then list it` with `StaleElementReferenceException` on `search-entry-button` in the clue `requesting an
existing name to check the already taken message`, the same clue, element and sequence as 11187.

- Run: https://github.com/canton-network/splice/actions/runs/42637895044, main 7d3d5f17da ("Handle duplicate sequencer
  ids in scan (#8365)"), job 129231327555 `ci / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (0)`, attempt 1.
- Runtime canton: 3.7.2.
- Component: test (frontend, `eventuallyClickOn`).

## 1. Failing assertion

```
$ sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11300/job.log | grep -a -A40 'should allow requesting an entry with subscription payments and then list it \*\*\* FAILED' | grep -a -E 'StaleElementReferenceException|Element: |AnsFrontendIntegrationTest\.scala' | cut -c1-230 | head -5
[info]   org.openqa.selenium.StaleElementReferenceException: The element with the reference 824d4f7f-fd5d-4094-9638-4fa3b7e56de8 is stale; either its node document is not the active document, or it is no longer connected to the DO
[info] Element: [[FirefoxDriver: firefox on linux (cda5530a-ea3d-4bdb-b097-642263129b8a)] -> id: search-entry-button]
[info]   at org.lfdecentralizedtrust.splice.integration.tests.AnsFrontendIntegrationTest.$anonfun$new$6(AnsFrontendIntegrationTest.scala:51)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.AnsFrontendIntegrationTest.$anonfun$new$4(AnsFrontendIntegrationTest.scala:45)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.AnsFrontendIntegrationTest.$anonfun$new$4$adapted(AnsFrontendIntegrationTest.scala:32)
```

## 2. The failing line is the search click of the already-taken clue

```
$ git show 7d3d5f17da:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/AnsFrontendIntegrationTest.scala | sed -n '45,53p'
        clue("requesting an existing name to check the already taken message") {
          waitForQuery(id("entry-name-field"))
          eventuallyClickOn(id("entry-name-field"))
          textField("entry-name-field").value = entryNameWithoutSufffix

          waitForCondition(id("search-entry-button")) { ExpectedConditions.elementToBeClickable(_) }
          eventuallyClickOn(id("search-entry-button"))
          waitForQuery(id("unavailable-icon"))
        }
```

## 3. The name lookup started between the clickable check and the click

Same order as in 11187: clickable check finished, the debounced `ans-entries/by-name` lookup for the typed name
started 70 ms later, and the clue failed 3 ms after that with a stale element.

```
$ zcat log/11300/logs-frontend-wall-clock-time-0/canton_network_test.clog.gz | awk -F'"@timestamp":"' '{t=substr($2,1,23)} t>="2026-11-09T15:07:01" && t<="2026-11-09T15:07:02"' | grep -a -E 'already taken message|IdQuery\(search-entry-button\)|scan-proxy calling GET .*ans-entries/by-name|Caught error' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name.*//; s/ with body: undefined \[[^]]*\]//' | cut -c1-200
2026-11-09T15:07:01.086Z scan-proxy calling GET http://localhost:5503/api/validator/v0/scan-proxy/ans-entries/by-name/.unverified.ans
2026-11-09T15:07:01.212Z Running clue: requesting an existing name to check the already taken message
2026-11-09T15:07:01.466Z Running clue: Waiting for IdQuery(search-entry-button) to be clickable
2026-11-09T15:07:01.809Z Finished clue: Waiting for IdQuery(search-entry-button) to be clickable
2026-11-09T15:07:01.879Z scan-proxy calling GET http://localhost:5503/api/validator/v0/scan-proxy/ans-entries/by-name/mycool_entry.unverified.ans
2026-11-09T15:07:01.882Z Failed clue: requesting an existing name to check the already taken message
2026-11-09T15:07:01.883Z Caught error org.openqa.selenium.StaleElementReferenceException: The element with the reference 824d4f7f-fd5d-4094-9638-4fa3b7e56de8 is stale; either its node document is not 
```

## 4. Parent state

```
$ gh api repos/DACH-NY/cn-test-failures/issues/11187 --jq '"#\(.number) \(.state) assignees=\([.assignees[].login]|join(",")) \(.title)"'
#11187 open assignees= eventuallyClickOn: element was replaced between check and click
```

## Verdict

Duplicate of 11187 (Selenium race in `eventuallyClickOn`: the debounced name lookup disables `search-entry-button`,
`DisableConditionally` remounts it, the click hits the old node). Test flake, fix in the test or `eventuallyClickOn` as
described on 11187. Not verified: the DOM remount itself (no browser-side evidence checked here; the order of the
three log lines matches 11187).
