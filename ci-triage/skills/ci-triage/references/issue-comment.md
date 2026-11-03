# Issue comments

Reader: a senior engineer who knows splice and Canton but never opens the triage branch. The comment must stand on
its own: no packet paths or other triage-branch references.

Shape (Markdown, short paragraphs; a few sentences each):
1. Verdict in one line: what kind of failure, and whether splice needs a change.
2. What failed: run, sha, job or test, step; which issues belong to it and how that is known.
3. Cause, with the decisive log lines verbatim in a fenced code block (two to four lines, timestamps kept,
   secrets and long JSON trimmed with `...`), then one or two sentences interpreting them.
4. What rules out the alternatives (same sha passing, later runs, unrelated commit, family checked).
5. Resolution: the fix in one sentence (file and change) or why none is needed; owner if known. No reference to the
   fix PR or branch: the PR links the issue with `Fixes ...`.
6. Not checked: what was not verified and where the missing evidence lives; when it is worth chasing. If the
   evidence is in a system the user can read (GCP logs: recipe 13), hand them the link before writing this.

References: a bare `#N` in a cn-test-failures comment links to cn-test-failures issue N. Write `#N` only for
cn-test-failures issues; a splice PR or commit is `canton-network/splice#N` or a sha, and only when the reader needs it
(not to name the commit under test, which the issue already shows).

Leave out what the reader can see on the issue itself (links, the job list) and the triage process (which recipes
ran, what was grepped). Duplicate-close comments stay one or two sentences: parent, and the one fact that makes it
the same failure.

Body files: `ci-triage/CLAUDE.md`, Commands for the user. Every comment except a single line of plain text goes
into `<round dir>/comments/<ref>.md` and is posted with `--body-file` (or `close_duplicates.py --comment-file`);
never edit a log line to fit a quoting rule. Print the full body in chat for review: the command, then the file
content in a `~~~` fence (so inner ``` fences render as part of it).

## Example (11152, accepted by the user on 2026-11-03)

```
gh issue comment 11152 -R DACH-NY/cn-test-failures --body-file ci-triage/rounds/2026-11-03T131520Z/comments/11152.md
```

Body (an earlier draft of that file):

~~~
Infra one-off, no splice change needed.

Deploy CIMain run 41610142697 (main 8fd9921238) failed in step Trigger cluster test on attempts 1 to 3 (this issue, #11153, #11154; each attempt logs the issue it filed). The step POSTs to the cluster test invoker (`CLUSTER_TEST_INVOKER_URL`), which answered every request from 09:20:12 to 09:40:29 UTC with:

```
curl: (22) The requested URL returned error: 500
Response body: 500 Internal Server Error: The server encountered an internal error and was unable to complete your request.
```

That is the default Werkzeug error page, so the invoker app raised; it was not a proxy timeout. The curl retry in `cluster_tests.yml` (3 retries within 60 s) cannot bridge a 20 min outage.

Attempt 4 with the same sha succeeded:

```
09:44:14 Triggered deploy_cimain: {"html_url":"https://github.com/DACH-NY/canton-network-internal/actions/runs/41613435215", ...
```

All later Deploy CIMain runs passed on the first attempt, so the commit is not involved.

Not checked: why the invoker raised. Its logs are in GCP project da-cn-ci (2026-11-03 09:20 to 09:40 UTC); worth a look only if it recurs.
~~~
