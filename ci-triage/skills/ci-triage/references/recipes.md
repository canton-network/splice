# Command recipes (all verified 2026-09)

## 1. Run, jobs, job log
Job id from the issue's `Job:` line. `filter=all` includes earlier attempts of re-run workflows; gh's `--jq`
takes no `--arg`, so pass the name via the environment:
```
RUN=<run id>; ISSUE_JOB='<Job: line from the issue>'
export NAME="${ISSUE_JOB% in *}"
gh api --paginate "repos/canton-network/splice/actions/runs/$RUN/jobs?filter=all&per_page=100" \
  --jq '.jobs[] | select((.name == env.NAME or (.name | endswith(" / " + env.NAME))) and .conclusion == "failure") | "\(.id) attempt=\(.run_attempt) \(.name)"'
```
Exactly one line expected. Then fetch the log (gh >= 2.10x refuses to write logs with ANSI codes without the flag):
```
gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/$JOB/logs > log/$REF/job.log
```
Job logs expire after ~90 days (HTTP 410); artifacts sooner. `gh run view --log` often returns nothing; use the API.

canton-network-internal runs (private): the same steps through `$R` (`ci-triage/CLAUDE.md`, Access), with the jq
filter outside and pages by hand; the log through `ghx` needs no flag, through `gh api` it needs
`--allow-escape-sequences`:
```
C=/repos/DACH-NY/canton-network-internal
for p in 1 2 3; do $R "$C/actions/runs/$RUN/jobs?filter=all&per_page=100&page=$p"; done \
  | jq -r '.jobs[] | select((.name == env.NAME or (.name | endswith(" / " + env.NAME))) and .conclusion == "failure") | "\(.id) attempt=\(.run_attempt) \(.name)"'
ghx "$C/actions/jobs/$JOB/logs" > log/$REF/job.log                                   # R=ghx
gh api --allow-escape-sequences "$C/actions/jobs/$JOB/logs" > log/$REF/job.log         # R='gh api'
```

## 2. Classify the job log
```
sed -E 's/\x1b\[[0-9;]*m//g' log/$REF/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|error\] +org|Run completed|##\[error\]' | sed -E 's/^[^Z]*Z //' | sort -u
```
Flagged lines only (the ones that actually fail checkErrors):
```
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/$REF/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | cut -c1-600
```
Failing assertion and stack head:
```
sed -E 's/\x1b\[[0-9;]*m//g' log/$REF/job.log | grep -a -A12 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | grep -v 'still running'
```
Suites in the shard (the split echo near the top of the log):
```
grep -a -A16 -E '^\S+Z org\.lfdecentralizedtrust\.splice\.integration\.tests\.[A-Za-z0-9]+$' log/$REF/job.log | grep -a -oE 'tests\.[A-Za-z0-9]+$' | sed 's/tests\.//' | sort -u
```

## 3. Artifact
```
export TMPDIR=$PWD/log/ghtmp; mkdir -p $TMPDIR
gh api "repos/canton-network/splice/actions/runs/$RUN/artifacts?per_page=100" --jq '.artifacts[] | "\(.id) \(.size_in_bytes) \(.name) expired=\(.expired)"'
gh run download $RUN --repo canton-network/splice -n logs-<job-slug> -D log/$REF/logs-<job-slug>
```
canton-network-internal runs: list through `$R`, download the zip by artifact id (the redirect to blob storage is
followed by both `ghx` and `gh api`):
```
$R "/repos/DACH-NY/canton-network-internal/actions/runs/$RUN/artifacts?per_page=100" | jq -r '.artifacts[] | "\(.id) \(.size_in_bytes) \(.name) expired=\(.expired)"'
$R "/repos/DACH-NY/canton-network-internal/actions/artifacts/<id>/zip" > log/$REF/<name>.zip && unzip -q -d log/$REF/<name> log/$REF/<name>.zip
```
Contents of splice test artifacts: `canton_network_test.clog.gz` (apps + test framework), `canton.clog.gz` or `canton_before_shutdown.clog.gz`
(all canton nodes; simtime shards: `canton-simtime.clog.gz`), `canton-standalone-*.clog.gz` (per standalone
instance, each with its own ignore file), frontend shards: `browser.<suite>.<user>.N.1.log.gz` (geckodriver, epoch-ms
timestamps) and `npm-<app>-<user>.out.gz` (Vite dev servers), `webpage-*.html.gz` (DOM dump at failure).

## 4. Timelines
```
W='T13:1[5-8]'   # time window around the flagged timestamps, here 13:15-13:18
zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '" | grep -a "$W" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-170
```
Generic line formatter (timestamp, level, logger, message):
```
sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | cut -c1-230
```
Follow one request end to end by trace id: `zcat $C | grep -a <trace-id>`.
Browser logs: epoch ms; `awk -F'\t' '$1>=<from> && $1<=<to>'`; `log.entryAdded` with `"level":"error"` are the
JS console errors; `WebDriver:Navigate` and `browsingContext.domContentLoaded` mark page loads.

## 5. Canton pin and backport state
```
git show <sha>:nix/canton-sources.json | grep -m1 version
git fetch origin <release-line>          # e.g. release-line-0.8.4; the run's headBranch
git merge-base --is-ancestor <fix-sha> origin/<branch> && echo present || echo MISSING
git log --format='%h %ad %s' --date=short origin/<release>..origin/main -- <test file>     # main-only fixes to that file
```

## 6. Canton jar instead of vendored source
```
V=<version>; curl -sSLo canton-$V.tgz https://www.canton.io/releases/canton-open-source-$V.tar.gz
tar -xzf canton-$V.tgz --wildcards '*/lib/canton-open-source-*.jar'
python3 -c "import zipfile;z=zipfile.ZipFile('<jar>');print([n for n in z.namelist() if b'<log string>' in z.read(n)])"
javap -p -c -l -cp <jar> <fully.qualified.Class>   # -l prints the LineNumberTable for exact line numbers
```
Public mirror `digital-asset/canton` is squashed (`[main] Update <date>` commits, no shared shas); compare
sources or jars, not commit ids. With access to DACH-NY/canton (`ci-triage/CLAUDE.md`, Access), read a source file
at a commit or tag with `$R "/repos/DACH-NY/canton/contents/<path>?ref=<ref>" -H "Accept: application/vnd.github.raw"`
to find the code quickly; the jar of the version that ran stays the authority for citations.

## 7. Ignore patterns
`project/ignore-patterns/canton_log.ignore.txt` (canton logs), `canton_network_test_log.ignore.txt` (app/test log),
`canton_log_shutdown_extra.ignore.txt` (after-shutdown half only), `canton-standalone-<instance>.ignore.txt`.
`.github/actions/scripts/check-logs.sh` prints ignored lines with the suffix and real problems without it.

## 8. Fix branch
A separate worktree keeps the triage branch checked out. `-b` refuses to overwrite an existing branch; for a
stacked follow-up, add a worktree for the existing branch instead.
```
git fetch origin main
git worktree add -b <user>/fix-<ref>-<slug> ../fix-<ref> origin/main       # e.g. <user>/fix-10172-multiarch-check-retry
git worktree add -b <user>/backport-<ref>-<pr>-<release-line> ../backport-<ref> origin/<release-line>   # e.g. <user>/backport-10169-7299-release-line-0.8.3
git worktree add ../fix-<ref> <user>/fix-<ref>-<slug>                        # stacked follow-up on an existing fix branch
git -C ../backport-<ref> cherry-pick -x -s <sha>                             # backports
git -C ../fix-<ref> commit -s -m "[ci] <one subject line>"
git worktree remove ../fix-<ref>                                             # when done (the branch stays)
```
The ref in the branch name is the cn-test-failures issue the branch fixes (the parent ref when it closes a family).
Cheap checks: `sbt --batch "apps-app/Test/scalafmtCheck"`, `npx --no-install prettier --check <files>`.

## 9. Export recent issues on the host

Manual-export source (`ci-triage/CLAUDE.md`, Access). Ask the user to run, from the host repo root (set the date to
about two weeks back):
```
direnv exec . ci-triage/scripts/export_issues.py --since <YYYY-MM-DD>
```
Read it from the sandbox as `/run/sandbox/source/ci-triage/export/issues-since-<YYYY-MM-DD>.jsonl`; one JSON
object per line (number, title, state, stateReason, createdAt, closedAt, assignees, labels, body, comments).

## 10. Export inbox on the host

Needed with either source (`ghx` cannot read Projects v2). Ask the user to run, from the host repo root:
```
direnv exec . ci-triage/scripts/export_inbox.py
```
Read it from the sandbox as `/run/sandbox/source/ci-triage/export/inbox.jsonl` (Status = Inbox items of project 48,
with bodies and comments; `commentCount` shows when more than the 100 exported comments exist).

Reading the exports in the sandbox (one issue, or a text search for the duplicate check):
```
X=/run/sandbox/source/ci-triage/export
ls -la $X
jq -r --argjson n $REF 'select(.number == $n) | "#\(.number) [\(.state)] \(.title)\n\(.body)", (.comments[] | "-- \(.author) \(.createdAt)\n\(.body)")' $X/issues-since-*.jsonl
jq -r --arg p '<test name or flagged text>' 'select((.title + " " + .body + " " + ([.comments[].body] | join(" "))) | test($p)) | "#\(.number) \(.state)/\(.stateReason // "-") \(.createdAt[0:10]) \(.title[0:110])"' $X/issues-since-*.jsonl
```

## 11. Private data through ghx or the sandbox token

`R=ghx` or `R='gh api'`, whichever `probe_access.py` reports for the capability (`ci-triage/CLAUDE.md`, Access).
Both print JSON for a `/repos/...` path and exit non-zero on HTTP errors (the router's reason is in the body). Page
by hand with `page=N`; filters go to `jq`, never `--jq`.
```
T=/repos/DACH-NY/cn-test-failures
$R $T/issues/$REF | jq -r '"#\(.number) [\(.state)] \(.title)\n\(.body)"'
$R "$T/issues/$REF/comments?per_page=100" | jq -r '.[] | "-- \(.user.login) \(.created_at)\n\(.body)"'
for p in 1 2 3 4 5 6 7 8 9 10; do $R "$T/issues?state=all&per_page=100&page=$p&since=<YYYY-MM-DD>T00:00:00Z"; done \
  | jq -c '.[] | select(.pull_request | not) | {number, title, state, state_reason, created_at, body}' > log/issues-recent.jsonl
jq -r --arg p '<test name or flagged text>' 'select((.title + " " + (.body // "")) | test($p)) | "#\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:10]) \(.title[0:110])"' log/issues-recent.jsonl
C=/repos/DACH-NY/canton-network-internal
$R "$C/commits/<sha>" | jq -r '.commit.message, (.files[].filename)'
$R "$C/contents/<path>?ref=<sha>" -H "Accept: application/vnd.github.raw"
$R "$C/commits/<sha>/pulls" | jq -r '.[] | "#\(.number) \(.title)\n\(.body)"'
$R "$C/actions/runs/$RUN" | jq -r '"\(.name) \(.head_branch) \(.head_sha[0:10]) \(.created_at) \(.conclusion)"'
```
`since` filters on last update, not creation. The issue list holds no comments; fetch them per candidate. Read file
contents only with the raw media type: the JSON form carries a `download_url` with a short-lived access token, which
must not end up in logs or packets. Jobs, logs and artifacts of canton-network-internal runs: recipes 1 and 3. With
`ghx` after the router's sandbox setup, `git clone https://github.com/DACH-NY/canton-network-internal.git` goes
through the router too (into `log/`, never into the triage branch). Whatever no method reaches: ask the user to save
it to `ci-triage/export/<ref>/` on the host.

## 12. Change an issue's Status on the host

The user runs this; the sandbox has no board access. One line per target Status, any number of refs; an issue is
only changed when its current Status is FROM, so a second run is harmless. `--dry-run` shows what would change.
```
direnv exec . ci-triage/scripts/set_status.py Inbox Triaged 11140 10495
```
