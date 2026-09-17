# 10144 - run 35076492327 ui_tests: SV UI config-diffs test times out finding "Vote Requests"

Post-merge CI on main, sha f1ee318e39 ("Don't wait forever on a non-active psid in
`ensureSynchronizerRegisteredAndConnected` (#7311)"), 2026-09-16T08:55Z. Job 104730030877
`ci / ui_tests / ui_tests`, step "Run UI tests". Raymond's refs for this run are 10144 and 10145; the run has
two failed jobs (this one and resource-intensive (1)) and which ref maps to which job is not known.
Commands verified against the artifacts in log/35076492327-ui_tests/.

## Categorization

- Test(s) failed: `apps/sv/frontend/src/__tests__/config-diffs.test.tsx > SV can see AmuletRules config
  diffs > in the rejected section.` (1 of 269 SV tests; 268 passed, the other 5 frontends all passed).
  Note the file lives at `src/__tests__/config-diffs.test.tsx`, not under `governance/forms/`.
- Failure type: testing-library `findByText('Vote Requests')` gave up after its 1000ms default in the shared
  helper `navigateToLegacyGovernancePage` (`src/__tests__/helpers.tsx:112`). Properly attributed test
  failure (awaited), no unhandled rejection this time.
- Component: apps/sv/frontend (SV UI unit tests), legacy `/governance-old` route (`Voting` ->
  `ListVoteRequests`).
- Flake vs real: flake. Same helper passed 3 times earlier in the same file in this run and in 17 call sites
  in two other files; the test passed in the previous 3 main runs including the 1.8x slower run of 10141.
  No frontend code changed between the 10141 sha and this sha. No product change involved.

## Setup

```
gh run download 35076492327 --repo canton-network/splice -n vite-report -D dl     # report.html.gz + test-*.log
gh api repos/canton-network/splice/actions/jobs/104730030877/logs > job.log         # 57481 lines
gh api repos/canton-network/splice/actions/jobs/104718784844/logs > p1.log          # ui_tests of 35073028349 (passed)
gh api repos/canton-network/splice/actions/jobs/104700596144/logs > p2.log          # ui_tests of 35067363744 (passed)
# ui_tests of 35072334729 (10141, failed on the unawaited waitFor) is log/10141/job-104716522199.log = p3.log
L=job.log; P1=p1.log; P2=p2.log; P3=p3.log
# A strips the GHA timestamp and folds vitest's non-ASCII glyphs: check mark -> [ok], pointer -> >, rule -> ----
A() { sed -E 's/^[0-9T:.Z-]+ //; s/\xe2\x9c\x93/[ok]/g; s/\xe2\x9d\xaf/>/g; s/(\xe2\x8e\xaf)+/----/g; s/\xe2\x80\xa6/.../g; s/\xe2\x80\xa2/*/g; s/\xc3\x97/x/g' | LC_ALL=C tr -d '\200-\377'; }
```
vite-report (xunit-viewer over TEST-{common,ans,scan,splitwell,sv,wallet}.xml) summarises "441 passed"; its
failed-test list carries the one failure below.

## 1. Failed jobs of the run

```
gh run view 35076492327 --repo canton-network/splice --json jobs,headSha,createdAt \
  --jq '.headSha, .createdAt, (.jobs[] | select(.conclusion=="failure") | "\(.databaseId)  \(.name)")'
```
```
f1ee318e39a2e4572a02264e19a061c6ba5d78da
2026-09-16T08:55:37Z
104730030877  ci / ui_tests / ui_tests
104730519175  ci / scala_test_resource_intensive / resource-intensive (1)
```
Two independent failed jobs. This packet covers ui_tests (job 104730030877) only.

## 2. What failed: one SV frontend test, properly attributed

Per-frontend vitest summaries (order: common, ans, scan, splitwell, sv, wallet):

```
grep -aE 'Test Files|      Tests |     Errors|   Start at' $L | A
```
```
[error]  Test Files  14 passed (14)
[error]       Tests  86 passed (86)
[error]    Start at  09:01:32
[error]  Test Files  1 passed (1)
[error]       Tests  1 passed (1)
[error]    Start at  09:01:41
[error]  Test Files  1 passed (1)
[error]       Tests  7 passed (7)
[error]    Start at  09:01:46
[error]  Test Files  1 passed (1)
[error]       Tests  6 passed (6)
[error]    Start at  09:01:53
[error]  Test Files  1 failed | 31 passed (32)
[error]       Tests  1 failed | 268 passed (269)
[error]    Start at  09:02:00
[error]  Test Files  5 passed (5)
[error]       Tests  75 passed (75)
[error]    Start at  09:03:08
```
The SV run (Start at 09:02:00) has 1 failed test and no `Errors` line: unlike 10141 there is no unhandled
rejection, and the 10141 site is silent this time:

```
grep -ac 'Vitest caught' $L; grep -ac 'set-amulet-rules-form.test.tsx:74' $L
```
```
0
0
```

The failing test, its per-test timing next to its siblings, and the file total:

```
grep -aE 'config-diffs.test.tsx \(|SV can see .* config diffs >' $L | A | cut -c1-120
```
```
[error]  > src/__tests__/config-diffs.test.tsx (10 tests | 1 failed) 15779ms
[error]    [ok] SV can see AmuletRules config diffs > while creating a vote request.  1608ms
[error]    [ok] SV can see AmuletRules config diffs > in the action needed section.  1679ms
[error]    [ok] SV can see AmuletRules config diffs > in the executed section.  1599ms
[error]    x SV can see AmuletRules config diffs > in the rejected section. 2612ms
[error]    [ok] SV can see DsoRules config diffs > while creating a vote request.  1779ms
[error]    [ok] SV can see DsoRules config diffs > in the action needed section.  1744ms
[error]    [ok] SV can see DsoRules config diffs > of a SetConfig vote result in the executed section.  1475ms
[error]    [ok] SV can see DsoRules config diffs > of a SetConfig vote result in the executed section 2.  1545ms
[error]    [ok] SV can see DsoRules config diffs > in the rejected section.  1476ms
[error]  FAIL  src/__tests__/config-diffs.test.tsx > SV can see AmuletRules config diffs > in the rejected section.
```
(The 10th test is `SV user can > login and see the SV party ID`, which passed.)

Assertion and stack (the ~320 lines between them are testing-library's prettyDOM dump, see section 4):

```
N=$(grep -an 'FAIL  src/__tests__/config-diffs' $L | tail -1 | cut -d: -f1); sed -n "$N,$((N+1))p" $L | A | cut -c1-200
sed -n "$N,$((N+330))p" $L | A | grep -aE '^\[error\]  > |^\[error\]     1[01][0-9]\||^\[error\]        \|' | cut -c1-120
```
```
[error]  FAIL  src/__tests__/config-diffs.test.tsx > SV can see AmuletRules config diffs > in the rejected section.
[error] TestingLibraryElementError: Unable to find an element with the text: Vote Requests. This could be because the text is broken up by multiple elements. In this case, you can provide a function f
```
```
[error]  > waitForWrapper ../../node_modules/@testing-library/dom/dist/wait-for.js:163:27
[error]  > ../../node_modules/@testing-library/dom/dist/query-helpers.js:86:33
[error]  > navigateToLegacyGovernancePage src/__tests__/helpers.tsx:112:23
[error]     110|   window.history.pushState({}, '', '/governance-old');
[error]     111|   window.dispatchEvent(new PopStateEvent('popstate'));
[error]     112|   expect(await screen.findByText('Vote Requests')).toBeDefined();
[error]        |                       ^
[error]     113| }
[error]     114| 
[error]  > goToGovernanceTabAndClickOnAction src/__tests__/config-diffs.test.tsx:192:9
[error]  > src/__tests__/config-diffs.test.tsx:96:11
```
`waitForWrapper` at `wait-for.js:163` is testing-library's `findBy*` giving up after its timeout and
rethrowing the query error. The test itself took 2612ms, far below vitest's 15s `testTimeout`; the budget
that ran out is the library's own.

## 3. The waited-on budget is testing-library's 1000ms default, not vitest's 15s

Source at sha f1ee318e39:

```
T=apps/sv/frontend/src/__tests__/config-diffs.test.tsx; H=apps/sv/frontend/src/__tests__/helpers.tsx
git show f1ee318e39:$T | awk '(NR>=92&&NR<=98)||(NR>=186&&NR<=194){printf "%4d  %s\n", NR, $0}' | cut -c1-110
git show f1ee318e39:$H | awk 'NR>=109&&NR<=113{printf "%4d  %s\n", NR, $0}'
```
```
  92    test('in the rejected section.', async () => {
  93      const user = userEvent.setup();
  94      render(<AppWithConfig />);
  95  
  96      await goToGovernanceTabAndClickOnAction('Rejected', deprecatedAction, user);
  97  
  98      await screen.findByTestId('stringify-display');
 186  async function goToGovernanceTabAndClickOnAction(
 187    tableType: string,
 188    action: string,
 189    user: ReturnType<typeof userEvent.setup>,
 190    index: number = 0
 191  ): Promise<void> {
 192    await navigateToLegacyGovernancePage();
 193  
 194    const button = await screen.findByText(tableType);
 109  export async function navigateToLegacyGovernancePage(): Promise<void> {
 110    window.history.pushState({}, '', '/governance-old');
 111    window.dispatchEvent(new PopStateEvent('popstate'));
 112    expect(await screen.findByText('Vote Requests')).toBeDefined();
 113  }
```
`findByText` has no `{ timeout }` argument, and nothing in the frontends raises testing-library's
`asyncUtilTimeout` (default 1000ms in @testing-library/dom 10.4.0); vitest's global is 15s:

```
git grep -n "asyncUtilTimeout\|configure({" f1ee318e39 -- 'apps/*/frontend*' 'apps/common/frontend*' | wc -l
grep -n '"node_modules/@testing-library/dom": {' -A1 apps/package-lock.json | grep version
git show f1ee318e39:apps/common/frontend-test-vite-utils/src/index.ts | awk 'NR>=5&&NR<=10{printf "%4d  %s\n", NR, $0}'
```
```
0
5513-      "version": "10.4.0",
   5    test: {
   6      disableConsoleIntercept: true,
   7      environment: 'happy-dom',
   8      exclude: ['../lib/**'],
   9      silent: false,
  10      testTimeout: 15000,
```
So every `findBy*` / `waitFor` without an explicit timeout in the SV suite has a 1000ms budget, independent of
the 15s per-test budget that #7252 ("Match UI tests timeouts with vitest's global") aligned the explicit
per-test timeouts to. The shared conf sets no `pool`/`fileParallelism`, so vitest's default of running test
files in parallel workers applies.

### 3a. What has to happen inside that 1000ms

`/governance-old` renders `Voting`, which shows `Loading` until two queries resolve, then `VoteRequest` ->
`SvListVoteRequests` -> `ListVoteRequests`, which shows `Loading` until three more queries resolve (one of
them dependent on the first), and only then renders the `Vote Requests` heading:

```
git show f1ee318e39:apps/sv/frontend/src/App.tsx | awk 'NR==100{printf "%4d  %s\n", NR, $0}'
git show f1ee318e39:apps/sv/frontend/src/routes/voting.tsx | awk 'NR>=12&&NR<=16||NR==25{printf "%4d  %s\n", NR, $0}'
git show f1ee318e39:apps/common/frontend/src/components/votes/ListVoteRequests.tsx | awk 'NR==116||NR==121||NR==122||NR==160||NR==161||NR==288{printf "%4d  %s\n", NR, $0}'
```
```
 100          <Route path="governance-old" element={<Voting />} />
  12    const dsoInfosQuery = useDsoInfos();
  13    const featureSupport = useFeatureSupport();
  14    if (dsoInfosQuery.isLoading || featureSupport.isLoading) {
  15      return <Loading />;
  16    }
  25    return <Box sx={{ p: 4 }}>{<VoteRequest />}</Box>;
 116    const listVoteRequestsQuery = votesHooks.useListDsoRulesVoteRequests();
 121    const votesQuery = votesHooks.useListVotes(voteRequestIds);
 122    const dsoInfosQuery = votesHooks.useDsoInfos();
 160    if (listVoteRequestsQuery.isLoading || dsoInfosQuery.isLoading || votesQuery.isLoading) {
 161      return <Loading />;
 288        <Typography variant="h4">Vote Requests</Typography>
```
Each `render(<AppWithConfig />)` creates a fresh `QueryClient` (`App.tsx:49`), so every test pays for the
whole chain through MSW again: at least two sequential round trips (dsoInfos/featureSupport, then
listVoteRequests, then listVotes over the returned ids) plus the React commits in between. The same chain
completed inside 1000ms in the three earlier tests of this file in this run (section 2) and in every other
call site.

## 4. The DOM dump shows the shell rendered and the vote-request list query resolved

The prettyDOM dump is cut at testing-library's print limit while still inside the top nav, so the page body
is not visible:

```
sed -n "$N,$((N+330))p" $L | A | grep -aoE 'data-testid="[^"]+"|data-page="[^"]+"|aria-label="[^"]+"|Loading|Vote Requests' | sort -u | tr '\n' ' '; echo
sed -n "$((N+300)),$((N+330))p" $L | A | grep -aE '\.\.\.$' | head -1
```
```
Vote Requests aria-label="4 pending" data-page="Governance" data-testid="app-title" data-testid="logout-button" data-testid="navlink-amulet-price" data-testid="navlink-dso" data-testid="navlink-governance" data-testid="navlink-validator-onboarding" data-testid="network-instance-name" data-testid="sv-top-nav" data-testid="sv-top-nav-links" data-testid="sv-top-nav-spacer-end" data-testid="sv-top-nav-spacer-start"
[error]                      ...
```
("Vote Requests" above is the text of the error message itself, not a DOM node.) The `4 pending` badge on
the Governance nav link is derived from the vote-request list query in the layout:

```
git show f1ee318e39:apps/sv/frontend/src/components/Layout.tsx | awk 'NR==51||NR==53||NR==67{printf "%4d  %s\n", NR, $0}' | cut -c1-100
```
```
  51    const listVoteRequestsQuery = votesHooks.useListDsoRulesVoteRequests();
  53    const actionsPending = listVoteRequestsQuery.data?.filter(
  67        badgeCount: actionsPending?.length,
```
So at the 1000ms mark the app shell was mounted and `listVoteRequests` had resolved (shared cache with
`ListVoteRequests.tsx:116`); what had not yet rendered is the body below the nav. Whether it was still
`Loading` on `featureSupport`, on the dependent `votesQuery`, or one React commit away cannot be read from
the truncated dump.

## 5. Not a slow runner: the SV suite ran at baseline speed, and the test passed in the slow 10141 run

Same four numbers across this run, the 10141 run (slow, this test passed), and the two passing runs:

```
for F in $L $P3 $P1 $P2; do echo "=== $F"; grep -aE '   Duration' $F | sed -n 5p | A | cut -c1-40; \
  grep -aE 'config-diffs.test.tsx \(|AmuletRules config diffs > in the rejected section|sv.test.tsx \(|synchroniser-upgrade.test.tsx \(|set-amulet-rules-form.test.tsx \(' $F | A | cut -c1-110; done
```
```
=== job.log
[error]    Duration  67.37s (transform 2.87s,
[error]  [ok] src/__tests__/governance/forms/set-amulet-rules-form.test.tsx (14 tests) 25960ms
[error]  [ok] src/__tests__/sv.test.tsx (14 tests) 34898ms
[error]  [ok] src/__tests__/synchroniser-upgrade.test.tsx (8 tests) 35846ms
[error]  > src/__tests__/config-diffs.test.tsx (10 tests | 1 failed) 15779ms
[error]    x SV can see AmuletRules config diffs > in the rejected section. 2612ms
=== p3.log (35072334729, 10141)
[error]    Duration  114.92s (transform 6.66s,
[error]  [ok] src/__tests__/governance/forms/set-amulet-rules-form.test.tsx (14 tests) 57095ms
[error]  [ok] src/__tests__/sv.test.tsx (14 tests) 55401ms
[error]  [ok] src/__tests__/synchroniser-upgrade.test.tsx (8 tests) 52610ms
[error]  [ok] src/__tests__/config-diffs.test.tsx (10 tests) 18870ms
[error]    [ok] SV can see AmuletRules config diffs > in the rejected section.  1881ms
=== p1.log (35073028349)
[info]    Duration  91.24s (transform 3.58s,
[info]  [ok] src/__tests__/governance/forms/set-amulet-rules-form.test.tsx (14 tests) 37516ms
[info]  [ok] src/__tests__/sv.test.tsx (14 tests) 45367ms
[info]  [ok] src/__tests__/synchroniser-upgrade.test.tsx (8 tests) 44509ms
[info]  [ok] src/__tests__/config-diffs.test.tsx (10 tests) 18627ms
[info]    [ok] SV can see AmuletRules config diffs > in the rejected section.  2018ms
=== p2.log (35067363744)
[info]    Duration  62.67s (transform 2.71s,
[info]  [ok] src/__tests__/governance/forms/set-amulet-rules-form.test.tsx (14 tests) 26440ms
[info]  [ok] src/__tests__/sv.test.tsx (14 tests) 32666ms
[info]  [ok] src/__tests__/synchroniser-upgrade.test.tsx (8 tests) 30916ms
[info]  [ok] src/__tests__/config-diffs.test.tsx (10 tests) 14124ms
[info]    [ok] SV can see AmuletRules config diffs > in the rejected section.  1410ms
```
SV suite: 67.37s here vs 62.67s on the fastest passing run, 91.24s and 114.92s on the two slower runs. The
failing test's whole body (navigate, wait for the heading, click tab, find action, click row, wait for the
diff, count accordions) normally completes in 1.4-2.0s; here the first `findByText` alone did not finish in
1000ms. The 2612ms on the failed test is 1000ms of waiting plus the failure/dump. This is a localized stall
in one test on a suite that was otherwise at baseline speed, not the suite-wide 1.8x slowdown of 10141.

`sv.test.tsx` and `synchroniser-upgrade.test.tsx` also go through `navigateToLegacyGovernancePage` and passed:

```
git grep -c "navigateToLegacyGovernancePage" f1ee318e39 -- apps/sv/frontend/src/__tests__ | sed 's/^[^:]*://'
```
```
apps/sv/frontend/src/__tests__/config-diffs.test.tsx:4
apps/sv/frontend/src/__tests__/helpers.tsx:1
apps/sv/frontend/src/__tests__/sv.test.tsx:9
apps/sv/frontend/src/__tests__/synchroniser-upgrade.test.tsx:8
```

## 6. The noisy lines are steady-state test output, identical to passing runs

```
for F in $L $P3 $P1 $P2; do echo "$F: InvalidURL $(grep -ac 'Invalid URL: ' $F) MSW $(grep -ac '\[MSW\] Error: intercepted' $F) ENOTFOUND $(grep -ac 'getaddrinfo ENOTFOUND' $F) 503 $(grep -ac 'HTTP-Code: 503' $F) 500 $(grep -ac 'status code 500' $F) FAIL $(grep -ac ' FAIL  ' $F) unhandled $(grep -ac 'Vitest caught' $F)"; done
```
```
job.log: InvalidURL 669 MSW 67 ENOTFOUND 9 503 12 500 4 FAIL 1 unhandled 0
p3.log: InvalidURL 681 MSW 67 ENOTFOUND 10 503 12 500 4 FAIL 0 unhandled 1
p1.log: InvalidURL 669 MSW 67 ENOTFOUND 10 503 12 500 4 FAIL 0 unhandled 0
p2.log: InvalidURL 657 MSW 67 ENOTFOUND 10 503 12 500 4 FAIL 0 unhandled 0
```
Same kinds and counts as in the passing runs; see 10141 section 3 for what each one is (validator
`console.debug` on partial URLs, unmocked wallet/splitwell background queries, unmocked second scan host,
deliberate failure-path tests). None is part of this failure.

## 7. Not a recent regression

```
git log --format='%h %ad %s' --date=short 0c43730f70..f1ee318e39 | cut -c1-110
git log --format='%h %s' 0c43730f70..f1ee318e39 -- 'apps/*/frontend*' 'apps/common/frontend*' | wc -l
git blame -L 109,113 f1ee318e39 -- $H | awk '{print $1, $2, $3, $4}' | sort -u
git log --format='%h %ad %s' --date=short f1ee318e39 -3 -- $H
git log --format='%h %ad %s' --date=short f1ee318e39 -2 -- $T
git log --format='%h %ad %s' --date=short f1ee318e39 -2 -- apps/sv/frontend/src/routes/voting.tsx apps/common/frontend/src/components/votes/ListVoteRequests.tsx
```
```
f1ee318e39 2026-09-16 Don't wait forever on a non-active psid in `ensureSynchronizerRegisteredAndConnected` (#7311)
75c22422b7 2026-09-16 Fix inverted ACS commitment component health condition (#7327)
de254044f1 2026-09-16 Fix commitment pv 36 dashboard variables (#7326)
0
c2f0f564ca1 (Pawel Perek 2026-02-18
9f5a147cc5 2026-06-03 move all npm packages to @canton-network (#5750)
583289253e 2026-05-13 Fix inconsistent timings in date pickers (#5530)
c2f0f564ca 2026-02-18 Default to the new Governance implementation (#3931)
9f5a147cc5 2026-06-03 move all npm packages to @canton-network (#5750)
c2f0f564ca 2026-02-18 Default to the new Governance implementation (#3931)
9f5a147cc5 2026-06-03 move all npm packages to @canton-network (#5750)
f3a8de6efd 2026-02-24 Reconcile beta and normal theme (#4079)
```
Three commits between the 10141 sha and this one, none touching any frontend. The helper (lines 109-113)
dates from #3931 (2026-02-18, when the legacy governance page was moved to `/governance-old`); the test file
and the components under test were last touched by the package rename in June. The last frontend commits
before this sha are #7307, #6577, #7252 (all in the previous passing runs as well).

## 8. Neighbouring main runs: two ui_tests failures in 24 runs, different tests

```
gh api "repos/canton-network/splice/actions/workflows/163795441/runs?branch=main&per_page=40&created=2026-09-12..2026-09-16" \
  --jq '.workflow_runs[] | "\(.id) \(.head_sha[0:10]) \(.created_at) \(.conclusion)"' > runs.txt; wc -l < runs.txt
for r in $(cut -d' ' -f1 runs.txt); do echo "$(grep "^$r " runs.txt) ui_tests=$(gh run view $r --repo canton-network/splice --json jobs --jq '[.jobs[] | select(.name|test("ui_tests")) | "\(.databaseId):\(.conclusion)"] | join(",")')"; done | grep -v 'ui_tests=[0-9]*:success'
```
```
24
35076492327 f1ee318e39 2026-09-16T08:55:37Z failure ui_tests=104730030877:failure
35072334729 0c43730f70 2026-09-16T08:10:23Z failure ui_tests=104716522199:failure
```
Of 24 post-merge main runs since 2026-09-12, ui_tests failed twice: 10141 (unawaited waitFor in
set-amulet-rules-form.test.tsx) and this one. The only other recent ui_tests failure on main (run
34612379425, 2026-09-11, job 103305806471) was in the wallet frontend:

```
gh api repos/canton-network/splice/actions/jobs/103305806471/logs | grep -aE ' FAIL  ' | A | cut -c1-140
```
```
[error]  FAIL  src/__tests__/wallet.test.tsx > Wallet user can > Token Standard > Allocations > see allocation requests v2, and accept them
```
`config-diffs.test.tsx` has no other failure on main in this window; the next run (35077158925) passed
ui_tests.

## Root cause / hypothesis

Proven (from the log and the source at f1ee318e39):
- One SV test failed: `config-diffs.test.tsx > SV can see AmuletRules config diffs > in the rejected
  section.`; 268 other SV tests and all other frontends passed; no unhandled rejection.
- The failure is `screen.findByText('Vote Requests')` in `helpers.tsx:112` (`navigateToLegacyGovernancePage`)
  timing out. No timeout is passed and nothing configures `asyncUtilTimeout`, so the budget is
  @testing-library/dom's 1000ms default; vitest's 15s `testTimeout` was not involved (test took 2612ms).
- Reaching the `Vote Requests` heading requires the `Voting` and `ListVoteRequests` loading gates to clear:
  at least two sequential mocked round trips plus React commits per fresh `QueryClient`.
- The SV suite ran at baseline speed (67.37s vs 62.67s); the same test passed in 1410-2018ms in the three
  previous main runs, including the 114.92s run of 10141; the same helper passed in 20 other call sites in
  this run. No frontend code changed since the previous passing run.
- Noise lines are identical in kind and count to the passing runs.

Inferred:
- A transient stall of more than ~1s hit this one test's initial render/query chain (vitest runs the 32 SV
  test files in parallel worker processes on a `self-hosted-k8s-small` runner; CPU contention, a GC pause,
  or a slow MSW response for one of the dependent queries are all consistent with a single test exceeding
  the 1000ms `findBy` budget while the rest of the suite runs at normal speed). The truncated DOM dump
  confirms the shell was mounted and the list query resolved, but not which gate was still open. The exact
  cause of the stall is not recoverable from the log.

## Duplicates / related

- Same run, other failed job: resource-intensive (1) (104730519175), an independent Scala shard, not covered
  here. Which of refs 10144/10145 is this job is unknown.
- 10141 (run 35072334729, `ci-triage/10141-sv-ui-unawaited-waitfor.md`): same job type and same SV frontend
  suite, but a DIFFERENT root cause. 10141 was a test bug (a `waitFor` never awaited, surfacing as an
  unhandled rejection) exposed by a suite-wide 1.8x slowdown. Here the wait is awaited and attributed, the
  suite was at baseline speed, and this very test passed in the 1.8x slow run. What the two share is the
  family: a 1000ms testing-library budget that is far tighter than the 15s vitest budget #7252 aligned the
  explicit per-test timeouts to. The 10141 site (`set-amulet-rules-form.test.tsx:71-85`) passed silently in
  this run (25960ms for the file, no `Errors`, no `:74` frame in the log).
- No other `config-diffs.test.tsx` failure in the 24 main runs since 2026-09-12; one unrelated wallet
  ui_tests failure on 2026-09-11 (section 8).
- Pre-existing test-hygiene noise (65 unmocked wallet MSW requests, unmocked `scan.sv-2.TARGET_HOSTNAME`)
  is the same as documented in 10141 section 3.

## Suggested next step / owner

FIX WRITTEN 2026-09-17: branch `ray/fix-sv-ui-test-timeouts` (26ad84f42d) passes the 15 s vitest budget to the
`findByText` in `navigateToLegacyGovernancePage` (`apps/sv/frontend/src/__tests__/helpers.tsx`), together with the
10141 await fix; prettier clean, vitest not run in the sandbox.

Give the SV suite's implicit waits the same budget #7252 gave its explicit ones: `configure({
asyncUtilTimeout: <n> })` from `@testing-library/dom` in `apps/sv/frontend/src/__tests__/setup/setup.ts`
(or, narrower, pass `{ timeout }` to the `findByText` in `navigateToLegacyGovernancePage` at
`helpers.tsx:112`, which is the shared entry point of 21 legacy-governance tests). Either turns a one-second
hiccup on a shared runner into a pass instead of a red main. Low priority, no product change. Owner: SV UI
frontend (helper by Pawel Perek in #3931; #7252 by the same author is the natural follow-up).
