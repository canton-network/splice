# 10157 - ui_tests: two wallet vitest cases time out at the describe-level 7500 ms (run 35122937351)

Two independent timeouts in `apps/wallet/frontend/src/__tests__/wallet.test.tsx`, both under the
`describe('Wallet user can', ...)` block whose closing `}, 7500);` (line 1140) caps every test at 7.5 s,
overriding the shared vitest `testTimeout: 15000` (`apps/common/frontend-test-vite-utils/src/index.ts:7`).

- Run: https://github.com/canton-network/splice/actions/runs/35122937351, release-line-0.8.x b741fda663
  ("Bump Canton to 3.5.18-snapshot.20260916..."), job 104884928585 `ui_tests`.
- `apps-common-frontend / npmTest` -> `npm run test:sbt --workspaces` -> wallet workspace `vitest --run` exit 1:
  `Test Files 1 failed | 4 passed (5)`, `Tests 2 failed | 73 passed (75)`.

## 1. The two failures (job console)

```
sed -E 's/\x1b\[[0-9;]*m//g' log/10157/job.log | grep -a -E ' x | FAIL |Test timed out|Test Files|Tests  '
```
```
 > src/__tests__/wallet.test.tsx (32 tests | 2 failed) 111235ms
   x Wallet user can > Token Standard > Allocations > see allocation requests v2, and accept them 7512ms
     -> Test timed out in 7500ms.
   x Wallet user can > Regular transfer offer > transfer offer is used when receiver has a transfer preapproval but checkbox is unchecked 7726ms
     -> Test timed out in 7500ms.
 FAIL  ... see allocation requests v2, and accept them        -> src/__tests__/wallet.test.tsx:349:7
 FAIL  ... transfer offer is used when receiver has a transfer preapproval but checkbox is unchecked
        -> transferTests src/__tests__/wallet.test.tsx:1221:3  (called from :671:5, the "Regular transfer offer" describe)
 Test Files  1 failed | 4 passed (5)
       Tests  2 failed | 73 passed (75)
```

## 2. Failure A: "see allocation requests v2, and accept them" - DUPLICATE of cn-test-failures 10120, fixed on main by #7304

```
git log origin/release-line-0.8.x..origin/main --format='%h %ad %s' --date=short -- apps/wallet/frontend/src/__tests__/wallet.test.tsx
git diff origin/release-line-0.8.x origin/main -- apps/wallet/frontend/src/__tests__/wallet.test.tsx | grep '^[-+]'
```
```
ffe110031a 2026-09-15 Fix wallet allocation ui test flake (#7304)      ("Fixes https://github.com/DACH-NY/cn-test-failures/issues/10120")
@@ -349,6 +349,11 @@
+        // Build the contracts once so the contract id is stable across polls; a fresh id on
+        // every poll remounts the row (keyed by contract id) and the Accept button we click.
+        const allocationRequestContracts = allocationRequests.map(payload => ({
+          contract: mkContract(AllocationRequestV2, payload),
+        }));
-              allocation_requests: allocationRequests.map(contract => {
-                return { contract: mkContract(AllocationRequestV2, contract) };
-              }),
+              allocation_requests: allocationRequestContracts,
```
The only diff between the two branches in this file is that fix; release-line-0.8.x still mints a new
contract id per MSW poll, so the row (and the Accept button) can remount under the click and the
`waitFor` never completes. Backport #7304.

## 3. Failure B: "Regular transfer offer > ... checkbox is unchecked" - near-limit test on a slow runner

Same-block siblings in this run: `transfer offer is used when receiver has no transfer preapproval` 6049 ms,
`transfer preapproval is used ...` 7202 ms (passed), this one 7726 ms (failed at 7500), `deduplication id
is passed` 14487 ms (passes because `transferTests` gives it its own `}, 15000)` at line 1323). The Token
Standard copies of the same tests took 3.4-6.3 s. The whole file took 111 s for 32 tests.

The test body (`wallet.test.tsx:1221-1256` at b741fda663) renders `<App/>`, navigates to Transfers,
types a receiver, waits for Send (2 s waitFor), toggles token standard, unticks the preapproval checkbox,
types a description and asserts the mocked send was called. Nothing waits on a specific slow thing; the
run is just ~10 % over budget on this runner. No fix on main for this case (`git log` above shows
only #7304), and #7252 ("Match UI tests timeouts with vitest's global", 2026-09-11, main only) touched only
SV frontend tests, but the same idea applies: drop the `7500` override at `wallet.test.tsx:1140` so the block
inherits the global 15000, or give `transferTests` per-test timeouts like `deduplication id is passed` has.

## 4. Verdict

A: duplicate of 10120, missing backport of #7304 to release-line-0.8.x. B: runner-speed flake caused by the
describe-level 7500 ms override; raise/remove it (same class as #7252). No product bug.
