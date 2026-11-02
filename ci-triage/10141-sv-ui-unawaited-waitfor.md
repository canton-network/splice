# 10141 - SV UI vitest run fails on an unawaited waitFor (run 35072334729)

Post-merge CI on main, sha 0c43730f70 ("Fix flake in WalletMintingDelegationTimeBasedIntegrationTest..."),
2026-09-16T08:10Z. Job 104716522199 `ci / ui_tests / ui_tests`, step "Run UI tests". Commands verified
against the artifacts in log/10141/.

## Categorization

- Test(s) failed: none. All 6 frontend vitest runs report every test passed (269/269 in the SV frontend).
  The SV run exits 1 on `Errors 1 error`: an unhandled promise rejection from
  `apps/sv/frontend/src/__tests__/governance/forms/set-amulet-rules-form.test.tsx:71-85`.
- Failure type: assertion inside a `waitFor` that is never awaited (dangling promise, 1000ms timeout).
- Component: apps/sv/frontend (SV UI unit tests). Other frontends (common, ans, scan, splitwell, wallet) passed.
- Flake vs real: flake in the test (latent bug since 2025-08-19, #1945). Surfaces when the runner is slow:
  this SV suite took 114.92s vs 62.67s in the previous passing main run. No product change involved.

## Setup

```
gh run download 35072334729 --repo canton-network/splice -n vite-report -D dl     # report.html.gz + test-*.log
gh api repos/canton-network/splice/actions/jobs/104716522199/logs > job.log         # 58822 lines
gh api repos/canton-network/splice/actions/jobs/104700596144/logs > prev-job.log    # ui_tests of previous main run (passed)
L=job.log; P=prev-job.log
# A strips the GHA timestamp and folds vitest's non-ASCII glyphs: check mark -> [ok], pointer -> >, rule -> ----
A() { sed -E 's/^[0-9T:.Z-]+ //; s/\xe2\x9c\x93/[ok]/g; s/\xe2\x9d\xaf/>/g; s/(\xe2\x8e\xaf)+/----/g; s/\xe2\x80\xa6/.../g; s/\xe2\x80\xa2/*/g' | LC_ALL=C tr -d '\200-\377'; }
```
vite-report is the xunit-viewer HTML over TEST-{common,ans,scan,splitwell,sv,wallet}.xml; its own summary is
"442 passed" and it lists zero failed tests, which is consistent with what follows: no test failed.

## 1. Failed job

```
gh run view 35072334729 --repo canton-network/splice --json jobs \
  --jq '.jobs[] | select(.conclusion=="failure") | "\(.databaseId)  \(.name)"'
```
```
104716522199  ci / ui_tests / ui_tests
104716731789  ci / scala_test_sim_time / simtime (3)
104716753202  ci / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (2)
```
Three independent failed jobs. This packet covers ui_tests (job 104716522199) only.

## 2. What failed: the SV frontend vitest process exited 1 with all tests passing

The sbt task runs `npm run test:sbt --workspaces --if-present`; the workspace that returned non-zero is
apps/sv/frontend:

```
grep -aE '\(apps-common-frontend / npmTest\)|npm error command (failed|sh)|npm error (path|workspace)' $L | A | cut -c1-200
```
```
[error] npm error path /__w/splice/splice/apps/sv/frontend
[error] npm error workspace @canton-network/splice-sv-frontend@0.1.0
[error] npm error command failed
[error] npm error command sh -c PORT=0 vitest --run
[error] (apps-common-frontend / npmTest) java.lang.IllegalStateException: Running command `npm run test:sbt --workspaces --if-present` in `/__w/splice/splice/apps/common/frontend/../..` returned non-z
```

Per-frontend vitest summaries (order: common, ans, scan, splitwell, sv, wallet):

```
grep -aE 'Test Files|      Tests |     Errors|   Start at' $L | A
```
```
[error]  Test Files  14 passed (14)
[error]       Tests  86 passed (86)
[error]    Start at  08:17:09
[error]  Test Files  1 passed (1)
[error]       Tests  1 passed (1)
[error]    Start at  08:17:31
[error]  Test Files  1 passed (1)
[error]       Tests  7 passed (7)
[error]    Start at  08:17:38
[error]  Test Files  1 passed (1)
[error]       Tests  6 passed (6)
[error]    Start at  08:17:46
[error]  Test Files  32 passed (32)
[error]       Tests  269 passed (269)
[error]      Errors  1 error
[error]    Start at  08:17:57
[error]  Test Files  5 passed (5)
[error]       Tests  75 passed (75)
[error]    Start at  08:19:52
```
The SV run (Start at 08:17:57) has 269/269 passed plus `Errors 1 error`. Vitest exits non-zero on an
unhandled error even when every test passed; that is the whole job failure.

The unhandled error, its stack (trimmed) and vitest's attribution:

```
grep -aE 'Vitest caught|Unhandled Rejection|^\S+ \[error\] AssertionError' $L | A
```
```
[error] Vitest caught 1 unhandled error during the test run.
[error] ---- Unhandled Rejection ----
[error] AssertionError: expected 20 to be greater than 65
```
```
grep -aE -A9 'timeout src/__tests__/governance' $L | A | cut -c1-140
```
```
[error]  > timeout src/__tests__/governance/forms/set-amulet-rules-form.test.tsx:74:37
[error]      72|       () => {
[error]      73|         const configLabels = screen.getAllByTestId('config-label', { e...
[error]      74|         expect(configLabels.length).toBeGreaterThan(65);
[error]        |                                     ^
[error]      75| 
[error]      76|         const configFields = screen.getAllByTestId('config-field', { e...
[error]  > runWithExpensiveErrorDiagnosticsDisabled ../../node_modules/@testing-library/dom/dist/config.js:47:12
[error]  > checkCallback ../../node_modules/@testing-library/dom/dist/wait-for.js:124:77
[error]  > MutationObserver.checkRealTimersCallback ../../node_modules/@testing-library/dom/dist/wait-for.js:118:16
```
```
grep -aE 'This error originated|The latest test that might' $L | A | cut -c1-200
```
```
[error] This error originated in "src/__tests__/governance/forms/set-amulet-rules-form.test.tsx" test file. It doesn't mean the error was thrown inside the file itself, but while it was running.
[error] The latest test that might've caused the error is "should render errors when submit button is clicked on new form". It might mean one of the following:
```
The frame `timeout ... wait-for.js` is testing-library's waitFor giving up after its timeout and rethrowing
the last callback error. Between the AssertionError and the stack the log carries a ~130-line DOM dump
(`<title>Supervalidator Operations</title>`, a rendered `set-amulet-config-rules-form`), which is
testing-library's standard prettyDOM on a failed waitFor.

Both tests named above PASSED in this run; the error is not attributed to a failing test:

```
grep -aE 'set-amulet-rules-form.test.tsx \(|Set Amulet Config Rules Form > should render (all|errors)' $L | A
```
```
[error]  [ok] src/__tests__/governance/forms/set-amulet-rules-form.test.tsx (14 tests) 57095ms
[error]    [ok] Set Amulet Config Rules Form > should render all Set Amulet Config Rules Form components  981ms
[error]    [ok] Set Amulet Config Rules Form > should render errors when submit button is clicked on new form  9045ms
```

## 3. The noisy lines are steady-state test output, not the failure

Counts in the failing job log, and the identical counts in the previous main run's PASSING ui_tests job:

```
for F in $L $P; do echo "$F: InvalidURL $(grep -ac 'Invalid URL: ' $F)  MSW $(grep -ac '\[MSW\] Error: intercepted' $F)  ENOTFOUND $(grep -ac 'getaddrinfo ENOTFOUND' $F)  503 $(grep -ac 'Failed to submit proposal Error: HTTP-Code: 503' $F)  500 $(grep -ac 'wallet got response with status code 500' $F)"; done
```
```
job.log: InvalidURL 681  MSW 67  ENOTFOUND 10  503 6  500 4
prev-job.log: InvalidURL 657  MSW 67  ENOTFOUND 10  503 6  500 4
```
Which frontend section each kind falls in (sections delimited by the `test:sbt` banner line numbers):

```
grep -naE 'Invalid URL: |\[MSW\] Error: intercepted|getaddrinfo ENOTFOUND|Failed to submit proposal Error: HTTP-Code: 503|wallet got response with status code 500' $L \
  | awk -F: '{ n=$1; if (n<10507) s="common"; else if (n<10665) s="ans"; else if (n<11037) s="scan"; else if (n<11722) s="splitwell"; else if (n<48583) s="sv"; else s="wallet"; k=(index($0,"Invalid URL")?"InvalidURL":index($0,"[MSW]")?"MSW":index($0,"ENOTFOUND")?"ENOTFOUND":index($0,"503")?"503":"500"); c[k" "s]++ } END { for (x in c) print c[x], x }' | sort -k2
```
```
4 500 wallet
6 503 sv
10 ENOTFOUND scan
681 InvalidURL sv
2 MSW splitwell
65 MSW wallet
```

- `Invalid URL: h TypeError: Invalid URL` (681, SV): `console.debug` in
  `apps/sv/frontend/src/utils/validations.tsx:20` (sha 0c43730f70) inside `isValidUrl`, fired on every
  keystroke while tests `user.type()` a URL into the proposal URL fields (`h`, `ht`, `htt`, ...,
  `https://`). Expected noise from the validator, present in the passing run too.
  ```
  grep -aE 'Invalid URL: ' $L | sed -n '1,3p;$p' | A | cut -c1-120
  ```
  ```
  [error] Invalid URL: h TypeError: Invalid URL
  [error] Invalid URL: ht TypeError: Invalid URL
  [error] Invalid URL: htt TypeError: Invalid URL
  [error] Invalid URL: https:// TypeError: Invalid URL
  ```
- `[MSW] Error: intercepted a request without a matching request handler` (67: 65 wallet, 2 splitwell):
  ```
  grep -aE -A2 '\[MSW\] Error: intercepted' $L | grep -aoE '(GET|POST|OPTIONS) (http[^ ]+|/api[^ ]+)' | sed -E 's#[0-9a-f]{20,}#<HASH>#g' | sort | uniq -c | sort -rn
  ```
  ```
       39 GET http://localhost:5003/api/validator/v0/feature-support
       23 GET http://localhost:5003/api/validator/v0/wallet/token-standard/transfers
        2 POST /api/json-api/v2/commands/submit-and-wait-for-transaction
        2 GET http://localhost:5003/api/validator/v0/wallet/token-standard/allocation-requests
        1 GET http://localhost:5003/api/validator/v0/allocations
  ```
  Wallet/splitwell background queries with no MSW handler (`onUnhandledRequest: 'error'` in
  `apps/wallet/frontend/src/__tests__/setup/setup.ts:42`); the wallet run passed 75/75 and the count
  is 67 in the passing run as well. Pre-existing test-hygiene debt, not this failure.
- `getaddrinfo ENOTFOUND scan.sv-2.target_hostname` (10, scan): the scan test config lists a second
  BFT scan at `https://scan.sv-2.TARGET_HOSTNAME` (`apps/scan/frontend/src/__tests__/setup/config.ts:11`)
  that has no MSW handler, so the request passes through to a real DNS lookup that fails. Scan run passed 7/7, same count in the
  passing run.
- `Failed to submit proposal Error: HTTP-Code: 503` (6, SV) and `wallet got response with status code 500`
  (4, wallet): deliberate failure-path tests. The 503 is returned by the tests' own MSW handlers, e.g.
  `set-amulet-rules-form.test.tsx:336` ("should show error on form if submission fails"); the 500 is from
  `wallet.test.tsx:1285` / `:1318` (`throw new Error('Request failed')` in a mock). All of those tests passed.

## 4. Causal chain: the waitFor at test line 71 is not awaited

Source at sha 0c43730f70:

```
T=apps/sv/frontend/src/__tests__/governance/forms/set-amulet-rules-form.test.tsx
git show 0c43730f70:$T | awk 'NR==43||NR==44||(NR>=70&&NR<=92)||NR==94{printf "%4d  %s\n", NR, $0}' | cut -c1-110
```
```
  43  describe('Set Amulet Config Rules Form', () => {
  44    test('should render all Set Amulet Config Rules Form components', () => {
  70      // Amulet Rules has a lot of fields to process so this can get flakey if not given enough time
  71      waitFor(
  72        () => {
  73          const configLabels = screen.getAllByTestId('config-label', { exact: false });
  74          expect(configLabels.length).toBeGreaterThan(65);
  75  
  76          const configFields = screen.getAllByTestId('config-field', { exact: false });
  77          expect(configFields.length).toBeGreaterThan(65);
  78  
  79          // no changes have been made so we should not see any current values
  80          expect(() => screen.getAllByTestId('config-current-value', { exact: false })).toThrowError(
  81            /Unable to find an element/
  82          );
  83        },
  84        { timeout: 1000 }
  85      );
  86  
  87      const jsonDiffsToggle = screen.getByTestId('json-diff-toggle');
  88      expect(screen.getByText('JSON')).toBeInTheDocument();
  89      expect(jsonDiffsToggle).toHaveTextContent('Show JSON');
  90      expect(jsonDiffsToggle).toHaveAttribute('aria-expanded', 'false');
  91      expect(screen.getByTestId('json-diffs-details')).not.toBeVisible();
  92    });
  94    test('should render errors when submit button is clicked on new form', async () => {
```
Line 44: the test callback is synchronous (no `async`). Line 71: `waitFor(...)` returns a promise that is
neither awaited nor returned. Line 84: it polls for at most 1000ms. The test body runs to line 91 and
finishes immediately; vitest marks it passed (981ms, section 2) and moves on.

It is the only such call in the repo's frontend tests:

```
git grep -n 'waitFor(' 0c43730f70 -- apps | grep -E '__tests__' | grep -vE 'await waitFor|return waitFor|=> waitFor|import|\bawait\b' | cut -c1-120
```
```
0c43730f70:apps/sv/frontend/src/__tests__/governance/forms/set-amulet-rules-form.test.tsx:71:    waitFor(
```

### 4a. What the dangling promise sees when its timer fires

After the test returns, the SV setup unmounts the tree and the next test renders a fresh form:

```
git show 0c43730f70:apps/sv/frontend/src/__tests__/setup/setup.ts | awk 'NR>=55&&NR<=59{printf "%4d  %s\n", NR, $0}'
```
```
  55  // Reset handlers & react renderers after each test `important for test isolation`
  56  afterEach(() => {
  57    server.resetHandlers();
  58    cleanup();
  59    vi.useRealTimers();
```
The next test (`should render errors when submit button is clicked on new form`, line 94) renders another
`<SetAmuletConfigRulesForm />`. The form builds its field list from the `/v1/dso` query result, and with no
data yet it builds the list from a `null` config:

```
git show 0c43730f70:apps/sv/frontend/src/components/forms/SetAmuletConfigRulesForm.tsx | awk 'NR==66||(NR>=177&&NR<=180)||NR==267||NR==277{printf "%4d  %s\n", NR, $0}' | cut -c1-120
git show 0c43730f70:apps/sv/frontend/src/components/form-components/ConfigField.tsx | awk 'NR>=106&&NR<=108{printf "%4d  %s\n", NR, $0}'
```
```
  66    const dsoInfoQuery = useDsoInfos();
 177    const maybeConfig = dsoInfoQuery.data?.amuletRules.payload.configSchedule.initialValue;
 178    const amuletConfig = maybeConfig ? maybeConfig : null;
 179    // passing the config twice here because we initially have no changes
 180    const allAmuletConfigChanges = buildAmuletConfigChanges(amuletConfig, amuletConfig, true);
 267              {allAmuletConfigChanges.map(change => (
 277                      <field.ConfigField
 106            <Typography
 107              component="p"
 108              data-testid={`config-label-${configChange.fieldName}`}
```
So `config-label-*` nodes appear in stages: a partial set on the first render(s), the full set (>65) once the
mocked DSO info has arrived and React has committed all `ConfigField`s. When the 1000ms timer of the
dangling waitFor fired, the DOM it observed (the DOM dump in the log shows a rendered
`set-amulet-config-rules-form`) contained 20 labels, hence `expected 20 to be greater than 65`. The
promise rejects with nobody awaiting it, vitest records an unhandled rejection, and exits 1.

### 4b. Why this run and not the previous one: a ~2x slower SV suite

```
grep -aE 'set-amulet-rules-form.test.tsx \(|should render all Set Amulet Config Rules Form components' $L | A
grep -aE '   Duration' $L | sed -n 5p | A | cut -c1-60
```
```
[error]  [ok] src/__tests__/governance/forms/set-amulet-rules-form.test.tsx (14 tests) 57095ms
[error]    [ok] Set Amulet Config Rules Form > should render all Set Amulet Config Rules Form components  981ms
[error]    Duration  114.92s (transform 6.66s, setup 15.19s,
```
```
grep -aE 'set-amulet-rules-form.test.tsx \(|should render all Set Amulet Config Rules Form components' $P | A
grep -aE '   Duration' $P | sed -n 5p | A | cut -c1-60
grep -ac 'Vitest caught' $P
```
```
[info]  [ok] src/__tests__/governance/forms/set-amulet-rules-form.test.tsx (14 tests) 26440ms
[info]    [ok] Set Amulet Config Rules Form > should render all Set Amulet Config Rules Form components  418ms
[info]    Duration  62.67s (transform 2.71s, setup 8.96s, co
0
```
Previous main run (35067363744, job 104700596144, passed): same file 26.4s, same test 418ms, SV suite
62.67s, zero unhandled errors. This run: 57.1s, 981ms, 114.92s. On the fast runner the full field set is
committed well inside the 1000ms window and the dangling promise resolves silently; on the slow runner it
does not, and the rejection surfaces. The 1000ms budget is hardcoded at line 84 and independent of the
vitest global timeout.

### 4c. Not a recent regression

```
git blame -L 71,85 0c43730f70 -- $T | awk '{print $1, $2, $3}' | sort -u
git log -1 --format='%h %ad %s' --date=short 5571abc60d8
git log --oneline 0c43730f70 -5 -- $T
git log --oneline 0c43730f70 -4 -- apps/sv/frontend/
```
```
5571abc60d8 (fayi-da 2025-08-19
5571abc60d 2025-08-19 Implement Amulet Rules Proposal Form in new SV UI (#1945)
cffa699fb4 Match UI tests timeouts with vitest's global (#7252)
ce85b79622 SV app: deprecate `/v0/dso` in favor of authenticated `/v1/dso` (#6957)
b959ffbb39 Final Implementation Initiate Proposal Flow (#6410)
2984f04e5b SV UI: More descriptive names for Reward Config (#6578)
4a9b1ae7e7 UI tests janitorization (#6451)
4ce91a87a4 Error handling in validator onboarding page (#7307)
0e13bb5b01 Gate react-query-devtools in integration tests (#6577)
cffa699fb4 Match UI tests timeouts with vitest's global (#7252)
b9abff1a25 [ci] Report the rate limiter in the access log and return JSON on 429 (#7207)
```
Lines 71-85 are unchanged since #1945 (2025-08-19). The most recent touch of this file, #7252 (2026-09-11),
only re-indented the neighbouring test and dropped its per-test `{ timeout: 10000 }`; it did not touch lines
70-85 (`git show cffa699fb4 -- $T` hunks start at lines 91 and 334). Nothing in apps/sv/frontend since then touches
the form or ConfigField. #7322 (previous main sha) is Grafana alerting, not frontend.

## 5. Neighbouring main runs

```
for r in 35073028349 35067363744 35056297878 35002354672 34992614501; do echo -n "$r "; gh run view $r --repo canton-network/splice --json jobs --jq '[.jobs[] | select(.name|test("ui_tests")) | .conclusion] | join(",")'; done
```
```
35073028349 success
35067363744 success
35056297878 success
35002354672 success
34992614501 success
```
ui_tests passed in the run before (35067363744) and the run after (35073028349) and in the three earlier
main runs; this is a single-run flake on a slow runner.

## Root cause / hypothesis

Proven (from the log and the source at 0c43730f70):
- No test failed; vitest exits 1 because of one unhandled promise rejection
  (`Errors 1 error`, `Vitest caught 1 unhandled error`).
- The rejection is testing-library `waitFor` timing out at
  `set-amulet-rules-form.test.tsx:74` (`expected 20 to be greater than 65`).
- That `waitFor` (lines 71-85) is called without `await` inside a synchronous test (line 44), so its
  outcome is never observed by the test; the test itself passes.
- The noisy lines (Invalid URL x681, MSW x67, ENOTFOUND x10, 503 x6, 500 x4) are present with the same
  counts in the previous passing run and come from expected validator/mocks paths.
- The SV suite ran ~1.8x slower than in the previous passing run (114.92s vs 62.67s; the file 57.1s vs
  26.4s).

Inferred:
- On the slow runner the full set of >65 `config-label-*` nodes was not committed within the 1000ms
  window; the observed DOM (20 labels) is the partial first render of the form rendered by the following
  test. The exact count 20 was not reproduced locally.

## Duplicates / related

- Same run, other failed jobs: simtime (3) (104716731789) and frontend-wall-clock-time (2) (104716753202);
  independent Scala integration test shards, not covered here.
- No other recent main ui_tests failure found (section 5). No other unawaited `waitFor` in the repo's
  frontend tests (section 4).
- Pre-existing test-hygiene noise worth a separate small cleanup: 65 unhandled MSW requests in the wallet
  tests (feature-support, token-standard/transfers) and the unmocked `scan.sv-2.TARGET_HOSTNAME` in the scan
  test config (section 3).

## Suggested next step / owner

One-line fix in `apps/sv/frontend/src/__tests__/governance/forms/set-amulet-rules-form.test.tsx`: make the
test at line 44 `async` and `await` the `waitFor` at line 71 (and drop or raise the 1000ms override so the
vitest default timeout applies, matching #7252's intent). Awaiting it turns a slow runner into a properly
attributed test failure or a pass instead of a process-level unhandled rejection. Owner: SV UI frontend
(test introduced in #1945 by fayi-da; #7252 by Pawel Perek touched the same file last).
