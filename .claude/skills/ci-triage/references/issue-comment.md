# Issue comments

Reader: a senior engineer who knows splice and Canton but never opens the triage dataset. The comment must stand on
its own: no packet paths or other triage-dataset references.

Shape (Markdown, titled sections in this order, short paragraphs of one to three sentences; as short as the facts
allow). The same sections are used in the chat presentation and in the comment. Leave a section out only when it has
nothing to say.
1. `## Summary`: exactly one line (one sentence, under about 200 characters, no line break): what broke and how bad
   (the failing test or job, how often, whether it blocks main or a release line, whether splice needs a change).
   Details belong in the sections below, not here. State it as certain only when every link from the trigger to
   the failure is shown in the log; otherwise say "likely" and name the unshown link in the sections below. When
   the code has a guard against exactly this failure (a cleanup, a pause, a retry), the sections must say why the
   guard did not work; a cause that skips the guard is incomplete (11195: blamed the suite that left vetted
   packages behind, missed that `withNoVettedPackages` skipped the disconnected participant).
2. `## Test Purpose`: what the test, job or workflow step is for, in plain words (for a deploy or a workflow: what
   it applies or checks). Plain job and test names, no run ids, shas, shard numbers or which duplicate belongs to
   which job.
3. `## Test Environment`: the architecture the failing job runs in, in two or three sentences and a network diagram:
   which components (apps, nodes, sequencers, runner, cluster, stores) take part and how they are connected.
4. `## Expected Behavior`: what should have happened at the point of failure, in one or two sentences.
5. `## Actual Behavior`: what happened instead, with the decisive log lines verbatim in a fenced code block (two to
   six lines, timestamps kept, secrets and long JSON trimmed with `...`) and a timeline diagram when the order of
   events matters. Name the log file each block comes from, in the sentence before the block or in a comment line
   above it, so that a reader can verify it by hand: the file as it is named in the job's artifact
   (`canton_network_test.clog.gz`, `canton-simtime.clog.gz`, `canton.clog.gz`) or, for the console log, the file name in the run's log archive (`gh api repos/<repo>/actions/runs/<run>/logs`,
   `unzip -l`; e.g. `0_trigger_cimain _ trigger_cluster_test.txt`) with the job id and the step. A block that mixes files is split per file. Never quote a
   line without saying where it is. Make the source the first line inside the code block, as a comment line
   (`# log file: canton_network_test.clog.gz` or, for a console log, `# log file: <file name in the run's log archive> (job <id>, step "<step>")`), so that it stays with the
   lines when someone copies them. Before handing over the comment, check that every code block with log lines
   starts with such a line.
6. `## Reproduction Steps`: the commands that reproduce it or force it, with their real output (a small local
   setup is fine), and one sentence on the repro rate: hits and runs of the job, first occurrence or not, what
   triggers it. When it cannot be forced, the section is called `## Repro Rate` and has only the rate and what
   would force it.
7. `## Root Cause`: the mechanism, with only the facts that settle it, one sentence each (a check that passed, the
   last good run); no timeline of the investigation, no list of runs checked.
8. `## Fix`: the fix in one or two sentences (file and change; for a backport the PR and the release line), or why
   none is needed. No reference to the fix PR or branch: the PR links the issue with `Fixes ...`.
9. `## Owner`: the person(s) to assign, each with the reason (the PR or commit that introduced the failure, the
   history of the touched files, the related functional change, a recent fix; whether the cause is the original
   change or a later one, authorship alone is not ownership; for an external contributor the internal reviewer or
   merger instead; ci-triage-inbox, Assignee). Left out when no owner was found.
Length: super short and crisp. Keep only what a reader needs to act: what failed, the proof, the cause, the fix, the
owner. Aim for about 300 words for a root cause and less for a duplicate. Per section: Summary one line; Test Purpose
and Expected Behavior one or two sentences; Test Environment two or three sentences and one small diagram; Actual
Behavior three to five sentences, one timeline, one log block of two to four lines (a second block only when a
different file proves a different step); Repro Rate one line; Root Cause two to four sentences; Fix one to three
lines; Owner one line with the reason. Leave out: investigation steps, timing tables and breakdowns (they stay in the
packet), ruled-out alternatives unless a reader would suspect them, related issues that are not the parent, caveats
about what was not checked unless they change the action, and repeated facts. When in doubt, cut it; the packet keeps
the detail. (11780: the first version had 1266 words; the posted one 344.)
Style: write for a quick read. Simple, direct sentences: one idea per sentence, about 20 words at most, active voice,
plain words, the subject first. No chains of clauses joined by commas, semicolons or "which"; split them. A fix or a
list of steps is a short numbered list or a small code block, not one long sentence. Keep the facts and the names
(files, steps, tests, PRs): shortening must not lose context. Example, not "In `cluster_tests.yml`, step "Trigger
cluster test": keep only the successful response in `result`, for example write the body to a file with `curl -o` and
use the file (or take `tail -n 1` of `$result`) before the `echo`", but: "File: `cluster_tests.yml`, step `Trigger
cluster test`. Keep only the successful response. Write the body to a file with `curl -o`. Read `result` from that
file." The same style applies to the chat presentation.

Diagrams (default: yes): a diagram helps a reader grasp the failure faster in almost every case, even a simple one,
so draw one unless the failure is plainly a one-liner with nothing to connect (a missing file, a typo). Keep it
small: three to eight boxes. There is no fixed recipe; pick the form that makes the failure clearest:
- Under `Test Environment`: a network picture (who talks to whom, which part of the setup the config or code
  belongs to), with the failing part or link marked.
- Under `Actual Behavior`: a timeline when the order of events matters (a race, a close during a call, a cancel after
  an error), or a state change or before and after, when that explains it better. One picture may serve both if it is
  clear.
- How: Mermaid (GitHub renders it in the comment), placed before the log block. Short labels (at most about 30
  characters per line, `<br/>` for line breaks) so nothing is cut off at comment width; the failing step stands
  out (red link: `linkStyle <n> stroke:#d00,stroke-width:3px,color:#d00`); times as offsets with the date and the
  log file in a note. Only steps and names that the quoted lines or the code show. Check the syntax before handing
  over the comment (`mermaid.parse` under jsdom accepts or rejects the source); the rendering is only seen on the
  issue.

Do not claim evidence is absent ("not in the artifacts", "the logs do not show") unless a stated search settled it; an unexplained step is left out or named as open ("not found" is a statement about our search, not the logs). No triage-dataset vocabulary: no family letters ("family L") or packet names; describe the mechanism instead. No speculation about causes the evidence does not show (who or what did something, why), no @mentions (the
assignee is set with the command), and no "Not checked" paragraph unless the missing evidence decides whether
someone has to act; then one sentence, and if the evidence is in a system the user can read (GCP logs: recipe 13),
hand them the link first.

References: a bare `#N` in a cn-test-failures comment links to cn-test-failures issue N. Write `#N` only for
cn-test-failures issues; a splice PR or commit is `canton-network/splice#N` or a sha, and only when the reader needs it
(not to name the commit under test, which the issue already shows).

Leave out what the reader can see on the issue itself (links, the job list) and the triage process (which recipes
ran, what was grepped). A duplicate gets the full triage comment (the nine sections with this occurrence's evidence, written like any other
comment; the Summary starts with "Same failure as #N") and nothing is posted on its parent. The close comment is one sentence of plain text, the one fact that makes it the same failure, passed inline
with `close_duplicates.py --comment '...'` (no body file; no backticks or quotes, so it fits the shell rules). They do not
name the parent (no "Duplicate of #N"): `close_duplicates.py` closes with `--duplicate-of`, which links the parent
already.

Every body file ends with a hidden marker line `<!-- ci-triage:<ref> -->`. The hand-over script finds its own earlier
comment by that marker and then posts nothing, so a re-run never makes a second copy. (Searching for a phrase of the
text breaks as soon as the text changes: 11775 got two copies that way.)

A posted comment is never overwritten: people have read it and may have answered it. A correction or a new finding
after posting is a new follow-up comment, body file `comments/<ref>-update-<n>.md` with marker
`<!-- ci-triage:<ref>-update-<n> -->`, posted once by its own marker. It starts with `## Update: <what changed>` and
says in its first sentence what in the earlier comment was wrong or is new (11784: the read regression was blamed on
the DB, but most of it is outside the DB timers). The body file of the original stays as posted. Before writing the
correction, check whether the original is posted (search the issue's comments for its marker): if it is not posted
yet, fix the original file instead.

Body files (shell rules for the commands: `<D>/CLAUDE.md`, Commands for the user). A comment that is a single line
of plain text (no Markdown, backticks, quotes, backslashes or `$`) is passed inline with `--body` or `--comment`;
the one-sentence close comment is always written that way (the full duplicate comment is a body file). Every other comment, and always one with a code block or a
log line, goes into `<round dir>/comments/<ref>.md`, is handed over (ci-triage skill, Dataset) and is posted from
the user's host clone with `--body-file <checkout>/ci-triage/rounds/<round>/comments/<ref>.md` (or
`close_duplicates.py --comment-file` with the same path, which accepts only such files). Never edit a log line to
fit a quoting rule. Print the full body in chat for review: the command, then the file content in a `~~~` fence (so
inner ``` fences render as part of it).

## What the user changes when posting (drafts vs posted comments, 2026-11-05: 11164, 11170, 11172, 11175, 11186, 11219, 11226, 11228, 11232)

- Drops the verdict label ("Rare:", "Test flake, likely on the client side:", "Product bug in ...:"); the first
  sentence states the mechanism. Keep "Likely" when a link is unproven.
- Drops run context the issue already shows (branch, sha, shard) and references to unrelated PRs that were only ruled
  out (#8227, #6577 in 11172).
- Several alternatives or guards go into a short bullet list ("Two possible causes:", "Two guards did not apply:"),
  not one long sentence; an unconfirmed explanation is one of the bullets, not the verdict.
- When an owner is assigned, the fix line is often shortened to a hint ("The expire check above already has a longer
  timeout (80 s).", "We should ...") or left out for the owner to decide; keep a full fix line only when it is
  trivial and certain.
- The assignee is pinged in a separate comment: `@<login> Could you please have a look?`. Hand that out as its own
  `gh issue comment` command next to `--add-assignee` (the main comment still has no @mentions).
- External incidents: the failing log lines and one sentence ("Random GitHub flake during incident."), then close.

## Example (shape of 11217 as the user posted it on 2026-11-05; names, times and log lines are made up)

Draft: `rounds/2026-11-05T094500Z-credentials/comments/11217.md`; posted: `11217-posted.md` next to it. The user cut
the run and duplicate details, the evidence timeline, the guess about the rotation, the resolution, the @mention and
"Not checked".

~~~
CI credentials broken, every run fails: since about 10:05 UTC on <date>, the identity provider, the storage service and the signing service reject the credentials the scala_test jobs get from the `<NAME>_V2` GitHub secrets.

Failing jobs: docker-compose, the frontend login tests, and the storage and signing tests in key-download and wall-clock-time.

Three different credentials are rejected:

```
10:14:02 Failed to start machine login: failed to fetch access token: oauth2: "access_denied" "Unauthorized".
10:12:47 APIException: Request failed with status code 401: Unauthorized
10:09:31 Error getting access token for service account: 400 Bad Request
         {"error":"invalid_grant", ...}, iss: <test service account>
10:05:10 Signing with key <key id> failed: ... UNAUTHENTICATED: Request had invalid authentication credentials.
```

The secrets are set (the `Check required secrets are set` step added by canton-network/splice#<N> passed), so the values, not the names, are wrong. The same secrets worked until this morning. All three credentials broke within a few minutes of each other.
~~~

## Older example (shape of 11152, accepted by the user on 2026-11-03, longer than the user now prefers; details made up)

```
gh issue comment 11152 -R DACH-NY/cn-test-failures --body-file <checkout>/ci-triage/rounds/2026-11-03T131520Z/comments/11152.md
```

Body (an earlier draft of that file):

~~~
Infra one-off, no splice change needed.

Deploy run <run id> (main <sha>) failed in step Trigger cluster test on attempts 1 to 3 (this issue, #<N>, #<N>; each attempt logs the issue it filed). The step POSTs to the cluster test invoker (`CLUSTER_TEST_INVOKER_URL`), which answered every request from 14:20:12 to 14:40:29 UTC with:

```
curl: (22) The requested URL returned error: 500
Response body: 500 Internal Server Error: The server encountered an internal error and was unable to complete your request.
```

That is the default Werkzeug error page, so the invoker app raised; it was not a proxy timeout. The curl retry in `cluster_tests.yml` (3 retries within 60 s) cannot bridge a 20 min outage.

Attempt 4 with the same sha succeeded:

```
14:44:14 Triggered <deploy workflow>: {"html_url":"https://github.com/<org>/<repo>/actions/runs/<run id>", ...
```

All later deploy runs passed on the first attempt, so the commit is not involved.

Not checked: why the invoker raised. Its logs are in GCP project <project> (14:20 to 14:40 UTC); worth a look only if it recurs.
~~~
