# 10204 - resource-intensive (0): Canton never ready on a slow runner pod, then log upload skipped (run 35740669383)

New, infra. main 57ed1c31b3, job 106790129333 `resource-intensive (0)`, attempt 1, GH conclusion cancelled (the
only non-success job of 58 in the run). No test ran: `wait-for-canton.sh -w` timed out at 15:32:58 although Canton
had been launched detached at 14:55:24, 37.5 min earlier. Every setup step on this pod ran 9-47x slower than on the
sibling shard `resource-intensive (1)` (same runner pool, started the same second, passed). Why Canton did not come
up cannot be determined: the artifact upload was skipped because the best-effort `Sanitize filenames` step of
`upload_logs` failed inside the runner's container hook, so the run has zero artifacts. Evidence loss.

- Run: https://github.com/canton-network/splice/actions/runs/35740669383, main 57ed1c31b3 ("add release notes for minting delegation bug fix (#7453)"), job 106790129333 `ci / scala_test_resource_intensive / resource-intensive (0)`.
- Runtime canton: 3.6.0-snapshot.20260916.20284.0.vf27c4824.
- Component: infra (runner pod); secondary: CI log upload (`.github/actions/sbt/upload_logs/action.yml`).
- Fetch: `gh api repos/canton-network/splice/actions/jobs/106790129333/logs > log/10204/job.log`, then
  `sed -E 's/\x1b\[[0-9;]*m//g' log/10204/job.log > log/10204/job.clean.log` (same for sibling job 106790127605
  into `log/10204/sibling-106790127605.clean.log`). Trailing whitespace is stripped from the outputs below.

## 1. Mapping: the only non-success job of the run, cancelled

```
gh api 'repos/canton-network/splice/actions/runs/35740669383/jobs?per_page=100' --jq '[.jobs[] | .conclusion] | group_by(.) | map({(.[0] // "null"): length}) | add'; gh api repos/canton-network/splice/actions/jobs/106790129333 --jq '{started_at,completed_at,conclusion}'
```

```
{"cancelled":1,"skipped":4,"success":53}
{"completed_at":"2026-09-22T15:37:25Z","conclusion":"cancelled","started_at":"2026-09-22T14:33:33Z"}
```

## 2. Failing line: Canton not ready within 300 s, tests skipped

```
grep -a -n -E 'Timeout: Canton|##\[error\]|display=Run tests' log/10204/job.clean.log | sed -E 's/^([0-9]+):\S*T([0-9:]{8})\.[0-9]*Z/\1 \2/' | cut -c1-160 | head -6
```

```
10206 15:32:58 Timeout: Canton instance(s) failed to start within 300 seconds
10207 15:32:58 ##[error]Error: failed to run script step (id 30233ab0-b69a-11f1-b3f7-4393d53315fa): Error: step failed with return code 1
10208 15:32:59 ##[error]Process completed with exit code 1.
10209 15:32:59 ##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
10211 15:32:59 ##[start-action display=Run tests;id=__self.__self_7]
10527 15:33:54 ##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
```

## 3. Canton was started detached 37.5 min before the wait (start-canton.sh -D, then sbt setup, then wait-for-canton.sh)

```
git show 57ed1c31b3:.github/actions/tests/scala_test/action.yml | grep -n -E 'start-canton.sh|wait-for-canton.sh'; grep -a -n -E 'Creating database splice_apps|-D specified|waiting only for canton|299 seconds left' log/10204/job.clean.log | sed -E 's/^([0-9]+):\S*T([0-9:]{8})\.[0-9]*Z/\1 \2/'
```

```
9:    description: "Options for start-canton.sh"
132:          ./start-canton.sh -p external -D ${{ inputs.start_canton_options }}
235:          ./wait-for-canton.sh -w
237:          ./wait-for-canton.sh -s
8863 14:55:19 Creating database splice_apps
8864 14:55:24 -D specified, not waiting for canton to start
9967 15:27:59 waiting only for canton with wall clock time
9968 15:27:59 Waiting for Canton instance(s) to start (299 seconds left)
```

Of the wait loop's one-second ticks, 53 of 237 took 2-4 s of wall clock, a first sign of a starved pod:

```
grep -a -E 'seconds left\)' log/10204/job.clean.log | sed -E 's/^\S*T([0-9:]{8})\.[0-9]*Z/\1/' | awk '{split($1,t,":"); s=t[1]*3600+t[2]*60+t[3]; if (p) d[s-p]++; p=s} END {for (k in d) print k " s between ticks: " d[k]}' | sort -n
```

```
0 s between ticks: 3
1 s between ticks: 181
2 s between ticks: 43
3 s between ticks: 9
4 s between ticks: 1
```

## 4. Same runner pool, same second, 9-47x slower setup than the passing sibling shard

```
for f in log/10204/job.clean.log log/10204/sibling-106790127605.clean.log; do grep -a -m1 'Runner name' $f | sed -E 's/^\S*Z //'; grep -a -E '##\[start-action display=' $f | sed -E 's/^\S*Z ##\[start-action display=(.*);id=([^]]*)\]/\2 = \1/' > log/10204/names.tmp; grep -a -E '##\[end-action' $f | sed -E 's/^\S*Z ##\[end-action id=([^;]*);outcome=([a-z]*);conclusion=[a-z]*;duration_ms=([0-9]*)\]/\1 \2 \3/' | awk 'NR==FNR{split($0,a," = "); n[a[1]]=a[2]; next} {print "  " n[$1] ": " $2 " " int($3/1000) " s"}' log/10204/names.tmp - | grep -E '^  (Set up Nix \(Self hosted\)|Start Canton|Restore precompiled classes|Set up SBT|Wait for Canton to be ready|Run tests):' | sort -u; done; rm -f log/10204/names.tmp
```

```
Runner name: 'self-hosted-k8s-x-large-qmch4-runner-bn8kb'
  Restore precompiled classes: success 1071 s
  Restore precompiled classes: success 835 s
  Run tests: skipped 0 s
  Set up Nix (Self hosted): success 1065 s
  Set up SBT: skipped 0 s
  Set up SBT: success 1868 s
  Start Canton: success 67 s
  Wait for Canton to be ready: failure 306 s
Runner name: 'self-hosted-k8s-x-large-qmch4-runner-98p66'
  Restore precompiled classes: success 15 s
  Restore precompiled classes: success 23 s
  Run tests: success 624 s
  Set up Nix (Self hosted): success 119 s
  Set up SBT: skipped 0 s
  Set up SBT: success 51 s
  Start Canton: success 3 s
  Wait for Canton to be ready: success 1 s
```

The sibling's Canton was ready 1 s after its wait began; on this pod it was not ready after 37.5 min. With no
Canton log there is no way to tell a crashed Canton from one still booting on a starved node.

## 5. Why there are no Canton logs: Sanitize filenames failed in the container hook, the three uploads were skipped

```
gh api 'repos/canton-network/splice/actions/runs/35740669383/artifacts?per_page=100' --jq .total_count; grep -a -E 'id=__self\.__self_10' log/10204/job.clean.log | sed -E 's/^\S*T([0-9:]{8})\.[0-9]*Z /\1 /; s/;conclusion=[a-z]*//' | cut -c1-150; grep -a -n 'custom container implementation failed' log/10204/job.clean.log | sed -E 's/^([0-9]+):\S*T([0-9:]{8})\.[0-9]*Z/\1 \2/'
```

```
0
15:33:20 ##[start-action display=Upload logs;id=__self.__self_10]
15:33:24 ##[start-action display=Pack logfiles;id=__self.__self_10.__run]
15:33:37 ##[end-action id=__self.__self_10.__run;outcome=success;duration_ms=13037]
15:33:37 ##[start-action display=Move potential additional debugging artifacts to log directory;id=__self.__self_10.__run_2]
15:33:50 ##[end-action id=__self.__self_10.__run_2;outcome=success;duration_ms=12327]
15:33:50 ##[start-action display=Sanitize filenames;id=__self.__self_10.__self]
15:33:50 ##[start-action display=Run bash command;id=__self.__self_10.__self.__run]
15:33:54 ##[end-action id=__self.__self_10.__self.__run;outcome=failure;duration_ms=3858]
15:33:54 ##[end-action id=__self.__self_10.__self;outcome=failure;duration_ms=4073]
15:33:54 ##[start-action display=Upload logs;id=__self.__self_10.__actions_upload-artifact]
15:33:54 ##[end-action id=__self.__self_10.__actions_upload-artifact;outcome=skipped;duration_ms=0]
15:33:54 ##[start-action display=Upload runner logs;id=__self.__self_10.__actions_upload-artifact_2]
15:33:54 ##[end-action id=__self.__self_10.__actions_upload-artifact_2;outcome=skipped;duration_ms=0]
15:33:54 ##[start-action display=Upload runner temp directory;id=__self.__self_10.__actions_upload-artifact_3]
15:33:54 ##[end-action id=__self.__self_10.__actions_upload-artifact_3;outcome=skipped;duration_ms=0]
15:33:54 ##[end-action id=__self.__self_10;outcome=failure;duration_ms=33562]
10209 15:32:59 ##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
10527 15:33:54 ##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
```

The step is best effort by intent (`|| true`), but its nix wrapper failing makes the default `success()` condition skip every later step of the composite action:

```
git show 57ed1c31b3:.github/actions/sbt/upload_logs/action.yml | sed -n '38,56p'
```

```
    # Certain characters are disallowed in artifact filenames in GHA, so we need to sanitize them
    - name: Sanitize filenames
      # Runs in nix to have access to `rename`
      uses: ./.github/actions/nix/run_bash_command_in_nix
      with:
        cmd: |
          rename -a : . ${{ inputs.splice_root }}/log/* || true

    - name: Upload logs
      uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4.6.2
      with:
        name: ${{ inputs.name }}
        path: '${{ inputs.splice_root }}/log'

    - name: Upload runner logs
      uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4.6.2
      with:
        name: ${{ inputs.name }}-runner
        path: /logs
```

## Verdict

New occurrence for family J (infra, no code change): one degraded runner pod
(`self-hosted-k8s-x-large-qmch4-runner-bn8kb`), Canton never ready, no test ran. Not family H (`NodeBase sys.exit`):
there tests run and SIGINT arrives at +60 min; here the wait step fails and `Run tests` is skipped. Flake. Resolution:
rerun; if it recurs, runner owners should look at node pressure for that pool.

Secondary, fixable: evidence loss by `upload_logs`. Fix branch `s11/fix-10204-upload-logs-after-sanitize-failure`
(a16e747a69, off origin/main 7b2505473c): `continue-on-error: true` on `Sanitize filenames`, so a failure there no
longer skips the uploads. Verified: `actionlint` clean before and after (no new findings), trailing-whitespace
check. Not verified: behaviour in a real run whose sanitize step fails. No local sbt or start-canton run was done:
the fix is a GitHub Actions composite step that no local build exercises, and a local Canton start says nothing
about one slow CI pod. Not determined: why Canton did not start on this pod, and why the container hook failed at
15:33:54 (no runner-side logs; `Upload runner logs` was skipped too).
