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
Contents: `canton_network_test.clog.gz` (apps + test framework), `canton.clog.gz` or `canton_before_shutdown.clog.gz`
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
sources or jars, not commit ids.

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

Manual-export source (SKILL.md, Sources for private data). Ask the user to run this from the host repo root
(fish shell; set `SINCE` to about two weeks back). Read it from the sandbox as
`/run/sandbox/source/ci-triage/export/issues-since-$SINCE.jsonl`; one JSON object per line.
```
set SINCE <YYYY-MM-DD>
mkdir -p ci-triage/export
gh issue list -R DACH-NY/cn-test-failures --state all --search "created:>=$SINCE" --limit 2000 \
        --json number,title,state,stateReason,createdAt,closedAt,assignees,labels,body,comments \
        --jq '.[] | {number, title, state, stateReason, createdAt, closedAt,
                assignees: [.assignees[].login], labels: [.labels[].name], body,
                comments: [.comments[] | {author: .author.login, createdAt, body}]}' \
        >ci-triage/export/issues-since-$SINCE.jsonl
and wc -l ci-triage/export/issues-since-$SINCE.jsonl
```

## 10. Export inbox on the host

Needed with either source (`ghx` cannot read Projects v2). Ask the user to run this from the host repo root
(fish shell) to get the Status = Inbox items of project 48, with bodies and comments. Read it from the sandbox as
`/run/sandbox/source/ci-triage/export/inbox.jsonl`.
```
gh api graphql --paginate -f query='
query($endCursor: String) {
  organization(login: "DACH-NY") {
    projectV2(number: 48) {
      items(first: 50, after: $endCursor) {
        pageInfo { hasNextPage endCursor }
        nodes {
          status: fieldValueByName(name: "Status") { ... on ProjectV2ItemFieldSingleSelectValue { name } }
          content {
            ... on Issue {
              number title url state createdAt
              repository { nameWithOwner }
              author { login }
              assignees(first: 10) { nodes { login } }
              labels(first: 20) { nodes { name } }
              body
              comments(first: 100) { totalCount nodes { author { login } createdAt body } }
            }
          }
        }
      }
    }
  }
}' --jq '.data.organization.projectV2.items.nodes[]
  | select(.status.name == "Inbox" and .content.repository.nameWithOwner == "DACH-NY/cn-test-failures")
  | .content
  | {number, title, url, state, createdAt, author: .author.login,
      assignees: [.assignees.nodes[].login], labels: [.labels.nodes[].name], body,
      commentCount: .comments.totalCount,
      comments: [.comments.nodes[] | {author: .author.login, createdAt, body}]}' >ci-triage/export/inbox.jsonl
and echo (wc -l <ci-triage/export/inbox.jsonl | string trim) "Inbox issues -> ci-triage/export/inbox.jsonl"
```

Reading the exports in the sandbox (one issue, or a text search for the duplicate check):
```
X=/run/sandbox/source/ci-triage/export
ls -la $X
jq -r --argjson n $REF 'select(.number == $n) | "#\(.number) [\(.state)] \(.title)\n\(.body)", (.comments[] | "-- \(.author) \(.createdAt)\n\(.body)")' $X/issues-since-*.jsonl
jq -r --arg p '<test name or flagged text>' 'select((.title + " " + .body + " " + ([.comments[].body] | join(" "))) | test($p)) | "#\(.number) \(.state)/\(.stateReason // "-") \(.createdAt[0:10]) \(.title[0:110])"' $X/issues-since-*.jsonl
```

## 11. Private data through ghx

`ghx` source (SKILL.md, Sources for private data). `ghx <path> [curl options]`; JSON out, curl exits non-zero on
HTTP errors (`--fail-with-body`, the router's reason is in the body). Page by hand with `page=N`.
```
T=/repos/DACH-NY/cn-test-failures
ghx $T/issues/$REF | jq -r '"#\(.number) [\(.state)] \(.title)\n\(.body)"'
ghx "$T/issues/$REF/comments?per_page=100" | jq -r '.[] | "-- \(.user.login) \(.created_at)\n\(.body)"'
for p in 1 2 3 4 5 6 7 8 9 10; do ghx "$T/issues?state=all&per_page=100&page=$p&since=<YYYY-MM-DD>T00:00:00Z"; done \
  | jq -c '.[] | select(.pull_request | not) | {number, title, state, state_reason, created_at, body}' > log/issues-recent.jsonl
jq -r --arg p '<test name or flagged text>' 'select((.title + " " + (.body // "")) | test($p)) | "#\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:10]) \(.title[0:110])"' log/issues-recent.jsonl
C=/repos/DACH-NY/canton-network-internal
ghx "$C/commits/<sha>" | jq -r '.commit.message, (.files[].filename)'
ghx "$C/contents/<path>?ref=<sha>" -H "Accept: application/vnd.github.raw"
```
`since` filters on last update, not creation. The issue list holds no comments; fetch them per candidate. Read file
contents only with the raw media type: the JSON form carries a `download_url` with a short-lived access token, which
must not end up in logs or packets. After the router's sandbox setup, `git clone https://github.com/DACH-NY/canton-network-internal.git`
goes through the router too (into `log/`, never into the triage branch). canton-network-internal Actions logs are
refused: ask the user to save them to `ci-triage/export/<ref>/` on the host.

Inbox without the user (heuristic, see SKILL.md): lists open issues whose board Status was only ever changed by
the automation bot, flagged `T` when the number already appears in `ci-triage/**.md`. Only the first 100 timeline
events per issue are read. Old bulk-filed items match too; drop them with `--min-number` (the oldest open item of
the last round).
```
python3 -I ci-triage/skills/ci-triage/scripts/inbox_fetch.py --min-number <N> --triage-dir ci-triage --json log/inbox-heuristic.json
```

## 12. Change an issue's Status on the host

The user runs this; the sandbox has no board access. Define the fish function once per host shell (or save it as
`~/.config/fish/functions/p48_status.fish`), then hand out one `p48_status <ref> <from> <to>` line per issue, e.g.
`p48_status 11140 Inbox Triaged`. It changes nothing unless the current Status is `<from>`, so it is safe to run
twice. Needs a host `gh` token with the `project` scope, SSO-authorized for DACH-NY.
```
function p48_status --argument-names ref from to
    set -l q 'query($n: Int!) { repository(owner: "DACH-NY", name: "cn-test-failures") { issue(number: $n) {
        projectItems(first: 20) { nodes { id
          project { id number field(name: "Status") { ... on ProjectV2SingleSelectField { id options { id name } } } }
          status: fieldValueByName(name: "Status") { ... on ProjectV2ItemFieldSingleSelectValue { name } } } } } } }'
    set -l row (gh api graphql -F n=$ref -f query=$q --jq ".data.repository.issue.projectItems.nodes[] | select(.project.number == 48) | [.project.id, .id, (.status.name // \"<none>\"), .project.field.id, (first(.project.field.options[] | select(.name == \"$to\") | .id) // \"\")] | @tsv")
    set -l f (string split \t -- $row)
    if test (count $f) -ne 5; or test -z "$f[5]"
        echo "#$ref: not on board 48, or Status has no option '$to'"
        return 1
    end
    if test "$f[3]" != "$from"
        echo "#$ref: Status is '$f[3]', not '$from'; left as is"
        return 1
    end
    gh api graphql -f p=$f[1] -f i=$f[2] -f fid=$f[4] -f o=$f[5] -f query='mutation($p: ID!, $i: ID!, $fid: ID!, $o: String!) {
        updateProjectV2ItemFieldValue(input: {projectId: $p, itemId: $i, fieldId: $fid, value: {singleSelectOptionId: $o}}) { projectV2Item { id } } }' >/dev/null
    and echo "#$ref: $from -> $to"
end
```
