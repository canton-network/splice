# 10248 - dependencies_scan `sbt bundle`: sbt output check flags vite 8.2+'s `configLoader: 'native'` advisory for `__dirname` in the sv frontend vite config

New, real (build hygiene), introduced by #7522 (6b4c166b71 "Bump vitest to v4", merged 2026-09-29 17:07Z). Job
`dependencies_scan`, step `Executing "sbt bundle"`: the bundle succeeds, then the sbt output check fails on one line,
``[info] Set `VITE_CONFIG_NATIVE_IGNORE_WARNING=true` to suppress this warning.`` Vite 8.2.0 and later print a three-line
advisory when a config uses CommonJS globals that the future native config loader will not provide; the only one in the
repo is `__dirname` at `apps/sv/frontend/vite.config.mjs:32:33`. #7522 regenerated `apps/package-lock.json`, which
replaced the sv frontend's nested vite 8.0.7 with a hoisted vite 8.3.1 (its `package.json` still says `^8.0.7`).

- Failure: `dependencies_scan` / `Executing "sbt bundle"`, pasted by the user (no run id or job log available here):
  `[success] Total time: 145 s ... completed Sep 29, 2026, 5:19:15 PM`, then `Found problems in the sbt output:` with the line above.
- Runtime canton: not involved (frontend build only).
- Component: build (sv frontend vite config). Fix branch `s11/fix-10248-vite-config-import-meta-dirname` (a02660b19c).

## 1. Flagged line

From the user's paste of the step output:

```
[info] > vite build
[info]
[info] (!) Your Vite config uses features that are unsupported by `configLoader: 'native'`, which is planned to become the default in a future major version of Vite:
[info]   - `__dirname` (vite.config.mjs:32:33). Use `import.meta.dirname` instead
[info] Set `VITE_CONFIG_NATIVE_IGNORE_WARNING=true` to suppress this warning.
...
[success] Total time: 145 s (0:02:25.0), completed Sep 29, 2026, 5:19:15 PM
Found problems in the sbt output:
[info] Set `VITE_CONFIG_NATIVE_IGNORE_WARNING=true` to suppress this warning.
Total: 1 lines with problems.
```

## 2. Reproduction with plain vite on origin/main (41e2c83a48)

In a worktree of origin/main: `cd apps && npm ci --no-audit --no-fund`, build the one workspace the config imports
(`cd common/frontend-test-vite-utils && npm run build`), then `cd ../../sv/frontend && npx vite build > log/10248/sv-build-before.log 2>&1`
(the build itself then stops on the unbuilt `@canton-network/splice-common-frontend`, which sbt builds first; the
advisory is printed at config load, before that).

```
sed -E 's/\x1b\[[0-9;]*m//g' log/10248/sv-build-before.log | grep -E '^\(!\)|^  - `__dirname`|VITE_CONFIG_NATIVE|^vite v'
```
```
(!) Your Vite config uses features that are unsupported by `configLoader: 'native'`, which is planned to become the default in a future major version of Vite:
  - `__dirname` (vite.config.mjs:32:33). Use `import.meta.dirname` instead
Set `VITE_CONFIG_NATIVE_IGNORE_WARNING=true` to suppress this warning.
vite v8.3.1 building client environment for production...
```

## 3. Why the check flags it

`check-sbt-output.sh` reports every line matching `warn`/`warning` case-insensitively that no pattern in
`project/ignore-patterns/sbt-output.ignore.txt` removes; only the third line of the advisory contains the word, and no
pattern covers it (the two `vite` hits in the ignore file are the PLUGIN_TIMINGS comment). Fed the three lines prefixed
with `[info] ` as sbt prints them (`log/10248/sbt-like-before.txt`), the script prints exactly the CI output:

```
git show origin/main:.github/actions/scripts/check-sbt-output.sh | grep -n -A2 '^filter_errors'
git show origin/main:project/ignore-patterns/sbt-output.ignore.txt | grep -c -i vite
bash .github/actions/scripts/check-sbt-output.sh log/10248/sbt-like-before.txt; echo exit=$?
```
```
29:filter_errors() {
30-  grep -i -e error -e severe -e warn -e warning -e exception -e critical -e fatal || true
31-}
2
Found problems in the sbt output:
[info] Set `VITE_CONFIG_NATIVE_IGNORE_WARNING=true` to suppress this warning.
Total: 1 lines with problems.

exit=1
```

## 4. Introducing commit: #7522 moved the sv frontend from vite 8.0.7 to 8.3.1

```
for r in 6b4c166b71^ 6b4c166b71; do git show $r:apps/package-lock.json | python3 -c "
import json,sys; d=json.load(sys.stdin)['packages']
print('$r', {k:v.get('version') for k,v in d.items() if k in ('node_modules/vite','sv/frontend/node_modules/vite')}, 'sv devDependencies vite:', d['sv/frontend']['devDependencies']['vite'])"; done
git log -1 --format='%h %ad %s' --date=iso 6b4c166b71
```
```
6b4c166b71^ {'node_modules/vite': '6.4.2', 'sv/frontend/node_modules/vite': '8.0.7'} sv devDependencies vite: ^8.0.7
6b4c166b71 {'node_modules/vite': '8.3.1'} sv devDependencies vite: ^8.0.7
6b4c166b71 2026-09-29 19:07:52 +0200 Bump vitest to v4 (#7522)
```

The failing step completed at 17:19, 12 minutes after #7522 merged.

## 5. Introducing vite release: 8.2.0

Every published vite 8 release, number of `dist` files containing the advisory's env var name:

```
cd log/10248/vitepack && for v in $(npm view vite versions --json | python3 -c 'import json,sys; print(" ".join(v for v in json.load(sys.stdin) if v.startswith("8.") and "-" not in v))'); do
  [ -d $v ] || { npm pack -q vite@$v >/dev/null && mkdir -p $v && tar -xzf vite-$v.tgz -C $v; }
  echo "$v $(grep -rl VITE_CONFIG_NATIVE_IGNORE_WARNING $v/package/dist | wc -l)"; done | tr '\n' ' '; echo
```
```
8.0.0 0 8.0.1 0 8.0.2 0 8.0.3 0 8.0.4 0 8.0.5 0 8.0.6 0 8.0.7 0 8.0.8 0 8.0.9 0 8.0.10 0 8.0.11 0 8.0.12 0 8.0.13 0 8.0.14 0 8.0.15 0 8.0.16 0 8.1.0 0 8.1.1 0 8.1.2 0 8.1.3 0 8.1.4 0 8.1.5 0 8.2.0 1 8.2.1 1 8.2.2 1 8.3.0 1 8.3.1 1 
```

## 6. Fix

`import.meta.dirname` is what the advisory asks for and is resolved by vite's bundling config loader as well (checked
below); it is available in Node >= 20.11 (nix dev shell: v24.15.0).

```
@@ -29,7 +29,7 @@ export default defineConfig(({ mode }) => {
       tsconfigPaths: true,
     },
     test: {
-      globalSetup: path.resolve(__dirname, 'vitest.global-setup.ts'),
+      globalSetup: path.resolve(import.meta.dirname, 'vitest.global-setup.ts'),
       setupFiles: ['./src/__tests__/setup/setup.ts'],
       reporters: [
         'default',
```

## 7. Verification: the same sbt task before and after, uncached

`apps-sv-frontend/bundle` caches on `*.tsx`, `*.ts`, `*.js` and `*.json` under the frontend
(`project/BuildCommon.scala:1290`), not `*.mjs`, so a `vite.config.mjs` edit alone does not rebuild; the cache directory
is removed before each run. Run from the repo root with `FIX_WORKTREE` set to the fix branch worktree (in this sandbox
additionally wrapped in `flock log/build.lock bash -l -c ...`). The direnv banner before sbt's first `[info]` line is
cut, since it is sandbox shell output, not sbt output.

```
W=$FIX_WORKTREE; C=$W/apps/sv/frontend/target/streams/_global/bundle/_global/streams/bundleFrontend; R=$PWD/log/10248
run() { rm -rf $C; (cd $W && direnv exec . sbt -batch 'apps-sv-frontend/bundle') > $R/sbt-bundle-$1.log 2>&1; echo "$1 sbt_exit=$?"
  n=$(grep -n -m1 -E '^\S*\[info\]' $R/sbt-bundle-$1.log | cut -d: -f1); tail -n +$n $R/sbt-bundle-$1.log > $R/sbt-bundle-$1.sbt-only.log
  sed -E 's/\x1b\[[0-9;]*m//g' $R/sbt-bundle-$1.sbt-only.log | grep -E 'vite v8|VITE_CONFIG_NATIVE'
  (cd $W && bash .github/actions/scripts/check-sbt-output.sh $R/sbt-bundle-$1.sbt-only.log; echo "$1 check_exit=$?"); }
git -C $W stash -q && run before; git -C $W stash pop -q && run after
```
```
before sbt_exit=0
[info] Set `VITE_CONFIG_NATIVE_IGNORE_WARNING=true` to suppress this warning.
[info] vite v8.3.1 building client environment for production...
Found problems in the sbt output:
[info] Set `VITE_CONFIG_NATIVE_IGNORE_WARNING=true` to suppress this warning.
Total: 1 lines with problems.

before check_exit=1
after sbt_exit=0
[info] vite v8.3.1 building client environment for production...
No problems found in the sbt output.

after check_exit=0
```

vitest still loads the global setup from the new path: `vitest.global-setup.ts` sets `process.env.TZ = 'Etc/GMT-2'`
(the shell has `TZ=UTC`); a throwaway probe test asserting that value, run with
`npx vitest run src/__tests__/zz-globalsetup-probe.test.ts src/__tests__/utility.test.tsx` in `apps/sv/frontend` and
deleted afterwards (check marks replaced by `OK`):

```
 RUN  v4.1.11 .../fix-10248/apps/sv/frontend
 OK src/__tests__/zz-globalsetup-probe.test.ts (1 test) 3ms
 OK src/__tests__/utility.test.tsx (1 test) 3ms
 Test Files  2 passed (2)
      Tests  2 passed (2)
```

`npx --no-install prettier --check sv/frontend/vite.config.mjs`: `All matched files use Prettier code style!`

## Verdict

- New, not a flake: deterministic on every `sbt bundle` since #7522 (6b4c166b71). Introduced by the lockfile
  regeneration in #7522 (vite 8.0.7 -> 8.3.1 for the sv frontend); the advisory itself is new in vite 8.2.0.
- Fix: `s11/fix-10248-vite-config-import-meta-dirname` (a02660b19c), one line in `apps/sv/frontend/vite.config.mjs`,
  rather than an ignore pattern or `VITE_CONFIG_NATIVE_IGNORE_WARNING`: it removes the only CommonJS global from the vite
  configs, so the line cannot come back from this cause.
- Verified here: the uncached `apps-sv-frontend/bundle` reproduces the CI check failure on origin/main and passes it
  with the fix; vitest global setup still applied; prettier. Not verified: the full root `sbt bundle` of
  dependencies_scan, the full sv frontend vitest suite.
- Side finding, not fixed: the frontend bundle cache ignores `*.mjs` (vite configs), so a local incremental `sbt bundle`
  can hide a config change.
