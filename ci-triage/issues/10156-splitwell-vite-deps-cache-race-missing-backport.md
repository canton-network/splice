# 10156 - SplitwellFrontendIntegrationTest: alice's splitwell UI never renders because three Vite dev servers race on one node_modules/.vite/deps (run 35115826905)

DUPLICATE of cn-test-failures 9704, FIXED on main by #7305 "Per-port vite deps caching" (4f0c04b8ed,
2026-09-15). The fix is NOT on release-line-0.8.x, where this run happened.

- Run: https://github.com/canton-network/splice/actions/runs/35115826905, release-line-0.8.x ba405bcbf6,
  job 104863007896 `frontend-wall-clock-time (2)`. The same run's `simtime (0)` failure is ref 10154.
- Runtime canton: 3.5.17. Firefox 152.0.1 / geckodriver 0.36.0 / selenium 4.44.0; Vite 8.0.7.
- 44 tests in 9 suites, 1 failed: `SplitwellFrontendIntegrationTest / A splitwell UI should settle debts with
  multiple parties`: `[frontend=aliceSplitwell step=create-invite] Could not find IdQuery(user-id-field)`
  (`FrontendIntegrationTest.scala:425`, via `FrontendLoginUtil.loginOnceConfirmedToBeAtUrl`, `FrontendLoginUtil.scala:47`).

## 1. Test-side timeline (canton_network_test.clog)

```
zcat log/10156/logs-frontend-wall-clock-time-2/canton_network_test.clog.gz | grep -a SplitwellFrontendIntegrationTest | grep -a -E "Starting|Waiting for login|Could not find|screenshot|Test failed"
```
```
16:00:12.298Z Starting 'SplitwellFrontendIntegrationTest/A splitwell UI should settle debts with multiple parties'
16:00:17.829Z Running clue: Waiting for login page to be ready     (3 attempts of 3 s, all fail)
16:00:26.865Z Caught error TestFailedException: Could not find IdQuery(user-id-field), dumping all frontend debug info
16:00:26.879Z WARN Failed to take screenshot   (WebDriverException: Unable to capture screenshot, elementScreenshot)
16:00:26.945Z Test failed: ... [frontend=aliceSplitwell step=create-invite] Could not find IdQuery(user-id-field)
```
This is the first frontend step of the first test in the suite: `login(aliceSplitwellUIPort, aliceDamlUser)` at
`SplitwellFrontendIntegrationTest.scala:58`. The login helper gives the page 5 s (+ one 3 s attempt) to show
`#user-id-field`.

## 2. Browser side: page loaded, every module script blocked

```
B=log/10156/logs-frontend-wall-clock-time-2/browser.org.lfdecentralizedtrust.splice.integration.tests.SplitwellFrontendIntegrationTest.aliceSplitwell.2.1.log.gz
zcat $B | awk -F'\t' '$1>=1789574410000 && $1<=1789574430000' | grep -a -E 'WebDriver:Navigate|domContentLoaded|"level":"error"'
```
```
1789574417633 (16:00:17.633Z)  WebDriver:Navigate {"url":"http://localhost:3400"}
1789574417752  log.entryAdded level=error  Loading module from "http://localhost:3400/node_modules/.vite/deps/@canton-network_splice-common-frontend.js?v=fb59cab6" was blocked because of a disallowed MIME type ("").
1789574417753  log.entryAdded level=error  ... /node_modules/.vite/deps/react.js?v=7d355db5 ... disallowed MIME type ("")
1789574417758  log.entryAdded level=error  ... /node_modules/.vite/deps/react-dom_client.js?v=02f0f719 ... disallowed MIME type ("")
1789574417762  log.entryAdded level=error  ... /node_modules/.vite/deps/react_jsx-dev-runtime.js?v=96d17b20 ... disallowed MIME type ("")
1789574417806  browsingContext.domContentLoaded url=http://localhost:3400/
```
No further navigation or reload until the test gave up at 16:00:26.9. The page dump taken at failure
(`webpage-26-09-16-16.0.26.880.html.gz`) is the bare index: `<div id="root"></div>` with
`<script type="module" src="/src/index.tsx">` - React never mounted, so there is no login form.

## 3. Server side: alice's Vite optimizer lost the rename race 13 minutes earlier

```
zcat log/10156/logs-frontend-wall-clock-time-2/npm-splitwell-alice.out.gz
```
```
> @canton-network/splice-splitwell-frontend@0.1.0 start
> vite --force
3:46:51 PM [vite] (client) Forced re-optimization of dependencies
  VITE v8.0.7  ready in 1082 ms   ->  Local: http://localhost:3400/
3:46:53 PM [vite] (client) [optimizer] bundling dependencies...
3:46:58 PM [vite] (client) error while updating dependencies:
Error: ENOENT: no such file or directory, rename '/__w/splice/splice/apps/splitwell/frontend/node_modules/.vite/deps' -> '/__w/splice/splice/apps/splitwell/frontend/node_modules/.vite/deps_temp_8f0a99be'
    at Object.renameSync (node:fs:1013:11)
    at Object.commit (.../vite/dist/node/chunks/node.js:31426:9)
    at commitProcessing (.../node.js:23256:28)
    at runOptimizer (.../node.js:23278:11)
    at onCrawlEnd (.../node.js:23398:5)
4:00:18 PM [vite] (client) [optimizer] bundling dependencies...
4:00:19 PM [vite] (client) new dependencies optimized: @canton-network/splice-common-frontend-utils, @canton-network/splice-common-frontend, ...
```
bob (3401) and charlie (3402) show `bundling dependencies...` at 3:46:53 PM with no error; the wallet (3
instances), sv (2) and other frontends show none either. Only alice's optimizer failed to commit, so on the
first request at 16:00:17 its `node_modules/.vite/deps/*` files did not exist and Vite answered the module
requests without a JavaScript MIME type. The re-optimization it then triggered finished at 16:00:19 but the
page was not reloaded within the test's budget.

## 4. Why three servers share one cache directory

`start-frontends.sh:207-209` starts `splitwell` for alice/bob/charlie from the same
`apps/splitwell/frontend` directory (`start_frontend`, `start-frontends.sh:25-84`, `cd frontend_dir; npm start`),
and every frontend's `npm start` is `vite --force` (`apps/splitwell/frontend/package.json:73`), i.e. all three
re-bundle into the shared default `node_modules/.vite`. Vite commits the bundle by renaming
`.vite/deps` aside and the fresh temp dir into place; three concurrent commits race on those renames.

## 5. The fix exists on main only

```
git show 4f0c04b8ed --stat --format='%h %ad %s' --date=short; git show 4f0c04b8ed --format= | grep '^[-+]' | grep -v '^+++\|^---'
git merge-base --is-ancestor 4f0c04b8ed origin/release-line-0.8.x || echo NOT on release-line-0.8.x
```
```
4f0c04b8ed 2026-09-15 Per-port vite deps caching (#7305)   apps/common/frontend-test-vite-utils/src/index.ts | 3 +++
+const cacheDir = process.env.PORT ? `node_modules/.vite-${process.env.PORT}` : 'node_modules/.vite';
+  cacheDir,
NOT on release-line-0.8.x
```
#7305 body: "Fixes https://github.com/DACH-NY/cn-test-failures/issues/9704".

## 6. Verdict

Not a new failure and not a Splitwell bug: it is the shared Vite dep-cache race that #7305 already fixed on
main (cacheDir per PORT). Backport #7305 to release-line-0.8.x (and 0.8.0/0.8.1 if still built). Any
frontend suite whose first login hits a server that lost the race fails the same way, so the earlier
frontend-shard "login form never appeared" flakes on release lines should be checked against
`npm-<app>-<user>.out` for `error while updating dependencies` before being triaged as UI bugs.
