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

## Verdict
New, rare Selenium race in a shared helper. Possible fix (test only): retry the click in `eventuallyClickOn` on
StaleElementReferenceException. Not verified: what re-rendered the button (no browser trace of the DOM).
