# Issue comments

Reader: a senior engineer who knows splice and Canton but never opens the triage branch. The comment must stand on
its own: no packet paths or other triage-branch references.

Shape (Markdown, short paragraphs of one to three sentences; as short as the facts allow):
1. Verdict in one line: what kind of failure, and whether splice needs a change. State it as certain only when
   every link from the trigger to the failure is shown in the log; otherwise say "likely" and name the unshown
   link in one sentence. When the code has a guard against exactly this failure (a cleanup, a pause, a retry), the
   verdict must say why the guard did not work; a cause that skips the guard is incomplete (11195: blamed the suite
   that left vetted packages behind, missed that `withNoVettedPackages` skipped the disconnected participant).
2. What failed, by plain job or test name (`docker-compose`, `key-download`, the test name), without run ids, shas,
   shard numbers or which duplicate belongs to which job: the issue and the linked duplicates show those.
3. The decisive log lines verbatim in a fenced code block (two to six lines, timestamps kept, secrets and long JSON
   trimmed with `...`), then one or two sentences interpreting them.
4. Only the facts that settle the verdict, one sentence each (a check that passed, the last good run). No timeline
   of the investigation, no list of runs checked.
5. Resolution only when someone has to act and the action is not obvious from the verdict: the fix in one sentence
   (file and change) and the owner. No reference to the fix PR or branch: the PR links the issue with `Fixes ...`.
Do not claim evidence is absent ("not in the artifacts", "the logs do not show") unless a stated search settled it; an unexplained step is left out or named as open ("not found" is a statement about our search, not the logs). No triage-branch vocabulary: no family letters ("family L") or packet names; describe the mechanism instead. No speculation about causes the evidence does not show (who or what did something, why), no @mentions (the
assignee is set with the command), and no "Not checked" paragraph unless the missing evidence decides whether
someone has to act; then one sentence, and if the evidence is in a system the user can read (GCP logs: recipe 13),
hand them the link first.

References: a bare `#N` in a cn-test-failures comment links to cn-test-failures issue N. Write `#N` only for
cn-test-failures issues; a splice PR or commit is `canton-network/splice#N` or a sha, and only when the reader needs it
(not to name the commit under test, which the issue already shows).

Leave out what the reader can see on the issue itself (links, the job list) and the triage process (which recipes
ran, what was grepped). Duplicate-close comments are one sentence of plain text, the one fact that makes it the same failure, passed inline
with `close_duplicates.py --comment '...'` (no body file; no backticks or quotes, so it fits the shell rules). They do not
name the parent (no "Duplicate of #N"): `close_duplicates.py` closes with `--duplicate-of`, which links the parent
already.

Body files: `ci-triage/CLAUDE.md`, Commands for the user. Every comment except a single line of plain text goes
into `<round dir>/comments/<ref>.md` and is posted with `--body-file` (or `close_duplicates.py --comment-file`);
never edit a log line to fit a quoting rule. Print the full body in chat for review: the command, then the file
content in a `~~~` fence (so inner ``` fences render as part of it).

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
gh issue comment 11152 -R DACH-NY/cn-test-failures --body-file ci-triage/rounds/2026-11-03T131520Z/comments/11152.md
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
