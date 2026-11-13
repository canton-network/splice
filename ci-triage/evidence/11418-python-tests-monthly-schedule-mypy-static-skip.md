# 11418, 11420 - python_tests: mypy errors in scripts/monthly-schedule.py on main (runs 43479138494, 43491683136)

New, hard broken on main since 1ffd6415e3 (#7359, merged 2026-11-13 18:05 UTC). `scripts/check-python-types.sh`
reports two `return-value` errors in `choice_value` of `scripts/monthly-schedule.py`; every post-merge run since
fails python_tests (11418 on the merge commit itself, 11420 on the next one, #8485). The errors came in with two
commits of the PR ("bool", "checked"). They passed PR CI because every PR commit but one was tagged `[static]`,
and `build.python_tests.yml` gates the type check and the unit tests on `skip_on_static`, so python_tests
reported success with all its steps skipped; the one untagged commit (9a1007286f) had its python_tests run
cancelled by the next push. Fix: widen the return annotation (fix PR splice#8486,
040590bcec). The CI gap (python checks skipped under `[static]`) is for the workflow owners.

- Runs: https://github.com/canton-network/splice/actions/runs/43479138494 (main 1ffd6415e3, "Include dev events in
  monday.com cadence (#7359)", job 132070746320 `python_tests`) and
  https://github.com/canton-network/splice/actions/runs/43491683136 (main 6a996a302c, "BQ: add config flag for which
  dataset to use for functions (#8485)", job 132114210139 `python_tests`).
- Runtime canton: not involved.
- Component: dev script (`scripts/monthly-schedule.py`) plus CI workflow (`.github/workflows/build.python_tests.yml`).

## 1. Failing step

Both job logs fail the same way in `Type check Python`.
```
grep -nE 'monthly-schedule|Found [0-9]+ error' log/11418/job.log log/11420/job.log | cut -c1-230
```
```
log/11418/job.log:9763:2026-11-13T18:09:55.7088096Z scripts/monthly-schedule.py:405: error: Incompatible return value type (got "str | bool", expected "dict[str, Any] | str")  [return-value]
log/11418/job.log:9764:2026-11-13T18:09:55.7088969Z scripts/monthly-schedule.py:408: error: Incompatible return value type (got "dict[str, Any] | None", expected "dict[str, Any] | str")  [return-value]
log/11418/job.log:9765:2026-11-13T18:09:56.7176843Z Found 2 errors in 1 file (checked 16 source files)
log/11420/job.log:9754:2026-11-13T19:27:19.5364312Z scripts/monthly-schedule.py:405: error: Incompatible return value type (got "str | bool", expected "dict[str, Any] | str")  [return-value]
log/11420/job.log:9755:2026-11-13T19:27:19.5365282Z scripts/monthly-schedule.py:408: error: Incompatible return value type (got "dict[str, Any] | None", expected "dict[str, Any] | str")  [return-value]
log/11420/job.log:9756:2026-11-13T19:27:20.5419432Z Found 2 errors in 1 file (checked 16 source files)
```

The code on main:
```
git show origin/main:scripts/monthly-schedule.py | sed -n '395,409p'
```
```
def choice_value(column: dict[str, Any], label: str | bool) -> dict[str, Any] | str:
    column_type = str(column.get("type", "")).lower()

    if column_type in {"status", "color"}:
        return {"label": label}

    if column_type == "dropdown":
        return {"labels": [label]}

    if column_type in {"text", "long_text"}:
        return label

    if column_type == "checkbox":
        return {"checked": "true"} if label else None
```

## 2. Every post-merge run since #7359 fails, no fix on main yet
```
gh run list -R canton-network/splice --branch main --event push --created '>=2026-11-13T17:00' --limit 30 --json databaseId,headSha,conclusion,createdAt,workflowName --jq '.[] | select(.workflowName|test("post-merge")) | "\(.databaseId) \(.headSha[0:10]) \(.createdAt) \(.conclusion)"'
git log --oneline 6a996a302c..origin/main -- scripts/monthly-schedule.py    # origin/main 6a996a302c, fetched 2026-11-13 ~21:00 UTC
```
```
43491683136 6a996a302c 2026-11-13T19:23:03Z failure
43479138494 1ffd6415e3 2026-11-13T18:05:11Z failure
```
(second command: no output)

## 3. Introduced by two commits of #7359
```
git log -L395,409:scripts/monthly-schedule.py --format='%h %s' -s ca8c53e2aa | head -5
gh api repos/canton-network/splice/commits/04740678dd --jq '.files[]|.patch' | head -30
```
```
04740678dd [static] checked
ad0e564371 [static] bool
cb3b5996f3 [static] add dev events to monthly schedule
758fd48a00 type-check and unittest Python in CI (#8098)
48d6bf5d14 script to auto-generate monday.com events for versions (#6775)
```
```
@@ -371,7 +371,7 @@ def choice_value(column: dict[str, Any], label: str | bool) -> dict[str, Any] |
         return label
 
     if column_type == "checkbox":
-        return label
+        return {"checked": "true"} if label else None
 
     raise RuntimeError(f"Unsupported choice column type {column_type!r} for {column['title']!r}")
```
The PR head is identical to the merge commit in this file and in the type-check script; only the Canton pin differs:
```
git diff --stat ca8c53e2aa 1ffd6415e3 -- scripts/ '*.toml' '*.ini' '*mypy*' 'scripts/check-python-types.sh' nix/ | tail -15
```
```
 nix/canton-sources.json | 12 ++++++------
 1 file changed, 6 insertions(+), 6 deletions(-)
```

## 4. Why PR CI passed: python_tests skips everything under `[static]`
The PR commits (all but one tagged `[static]`), and #8098 (which added the type check) was already in the branch:
```
gh api repos/canton-network/splice/pulls/7359/commits --paginate --jq '.[]|"\(.sha[0:10]) \(.commit.committer.date) \(.commit.message|split("\n")[0])"'
gh api repos/canton-network/splice/compare/758fd48a00...04740678dd --jq '"status=\(.status) behind_by=\(.behind_by)"'
```
```
cb3b5996f3 2026-11-10T13:27:24Z [static] add dev events to monthly schedule
9fdea89ca3 2026-11-10T13:27:24Z [static] accept negative week numbers
ad0e564371 2026-11-10T13:28:29Z [static] bool
04740678dd 2026-11-10T13:28:32Z [static] checked
a4bf836d62 2026-11-10T13:29:03Z [static] hit the refresh URL after creating events
fe7d5139cf 2026-11-10T13:29:06Z [static] hit the refresh URL after creating events
6056f4f855 2026-11-10T13:32:21Z [static] read the secret from URL
4dd903da22 2026-11-10T13:47:53Z [static] whoops
47364df98b 2026-11-10T14:23:57Z [static] Move release-line and Canton release cuts per review
109f1b832f 2026-11-10T14:38:42Z [static] Split event date and time into separate monday.com columns
9fa7964ac0 2026-11-10T14:44:18Z [static] Use the "9:00 am" label for the Time US Eastern column
70ddac8fe1 2026-11-10T14:48:45Z [static] Print a calendar view of the schedule in dry-run mode
6ede294050 2026-11-10T14:56:34Z [static] Add CILR LSU dev event and make dev event titles unique
30e136a8fc 2026-11-10T15:08:48Z [static] Add CILR Daml votes dev event
e1b9a6954b 2026-11-10T15:21:54Z [static] Add a dev-only mode to the monthly schedule
2a801ff1dd 2026-11-10T16:39:54Z [static] Cut Canton & WG releases for every Splice patch release
2f0ad0412d 2026-11-10T16:42:55Z [static] Drop "(if applicable)" from dev event titles
9a1007286f 2026-11-13T17:46:23Z move daml models to Monday
ca8c53e2aa 2026-11-13T17:48:22Z [static]
status=ahead behind_by=0
```
python_tests on the PR head (ca8c53e2aa) and on 2f0ad0412d: success, with type check and unit tests skipped; on the
only untagged commit, cancelled:
```
for s in ca8c53e2aa4cc5e559e70f855aaa04fc2e7b34b9 9a1007286f 2f0ad0412d; do full=$(gh api repos/canton-network/splice/commits/$s --jq .sha); echo "== $s"; gh api repos/canton-network/splice/commits/$full/check-runs --paginate --jq '.check_runs[]|select(.name|test("python_tests|final_result"))|"\(.name) \(.conclusion) \(.id)"'; done
gh api repos/canton-network/splice/actions/jobs/132061364811 --jq '.steps[]|"\(.number) \(.name) \(.conclusion)"'
```
```
== ca8c53e2aa4cc5e559e70f855aaa04fc2e7b34b9
ci / final_result success
ci / python_tests / python_tests success
== 9a1007286f
ci / final_result failure 132060500957
ci / python_tests / python_tests cancelled 132060259456
== 2f0ad0412d
ci / final_result success 129986241532
ci / python_tests / python_tests success 129977985003
```
```
1 Set up job success
2 Initialize containers success
3 Check out repository code success
4 Check if static only success
5 Check out repository code skipped
6 Setup skipped
7 Type check Python skipped
8 Run Python unit tests skipped
9 Report Failures on Slack & Github skipped
17 Post Check out repository code success
18 Stop containers success
19 Complete job success
```
(job 129977985003 on 2f0ad0412d: the same step list.) The gate:
```
git show origin/main:.github/workflows/build.python_tests.yml | sed -n '26,53p'
```
```
      - name: Check if static only
        uses: ./.github/actions/tests/skip_on_static
        id: skip
        with:
          gh_token: ${{ secrets.GITHUB_TOKEN }}

      - name: Check out repository code
        uses: actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd # v6.0.2
        if: inputs.commit_sha != ''
        with:
          persist-credentials: false
          ref: ${{ inputs.commit_sha }}
          submodules: "recursive"
          token: ${{ secrets.GITHUB_TOKEN }}

      - name: Setup
        id: setup
        if: steps.skip.outputs.skip != 'true'
        uses: ./.github/actions/tests/common_test_setup
        with:
          cache_version: 10
          test_name: python_tests

      - name: Type check Python
        if: steps.skip.outputs.skip != 'true'
        uses: ./.github/actions/nix/run
        with:
          cmd: ./scripts/check-python-types.sh
```
`skip_on_static` sets `skip=true` on a pull_request event when the last commit message contains `[static]` or the PR
has the `static` label. A type check is a static check, so a `[static]` PR that only touches Python never runs it.

## 5. Fix and verification
Fix PR splice#8486 (branch `s11/ctf-11418-monthly-schedule-types` off main 6a996a302c), one commit
040590bcec: return annotation of `choice_value` widened to `dict[str, Any] | str | bool | None` (annotation only;
runtime behaviour unchanged). Tagged `[ci]`, not `[static]`, so that python_tests actually runs on the PR.

Unfixed file reproduces the CI errors in the dev shell, the fixed one passes:
```
git show origin/main:scripts/monthly-schedule.py > log/monthly-schedule.py && direnv exec . env MYPYPATH=log mypy --config-file= --python-version 3.14 --strict --explicit-package-bases log/monthly-schedule.py 2>&1 | tail -3
direnv exec . env MYPYPATH=scripts mypy --config-file= --python-version 3.14 --strict --explicit-package-bases scripts/monthly-schedule.py 2>&1 | tail -1
```
```
log/monthly-schedule.py:405: error: Incompatible return value type (got "str | bool", expected "dict[str, Any] | str")  [return-value]
log/monthly-schedule.py:408: error: Incompatible return value type (got "dict[str, Any] | None", expected "dict[str, Any] | str")  [return-value]
Found 2 errors in 1 file (checked 1 source file)
```
```
Success: no issues found in 1 source file
```
Both CI steps of python_tests on the fix branch:
```
direnv exec . scripts/check-python-types.sh > log/mypy.out 2>&1; echo exit=$?; grep -nE '^mypy: scripts$' -A1 log/mypy.out
direnv exec . scripts/run-python-tests.sh > log/pytest.out 2>&1; echo exit=$?; tail -3 log/pytest.out
```
```
exit=0
35:mypy: scripts
36-Success: no issues found in 16 source files
```
```
exit=0
Ran 60 tests in 2.896s

OK
```

## Verdict
New, real, hard broken on main. Fix location: the script (PR above, verified with both python_tests steps);
the underlying gap is `build.python_tests.yml` skipping the type check under `[static]` (owner: CI workflow, not
changed here). Not verified: the monday.com behaviour of the script (annotation-only change), the PR's CI run
(not checked here).
