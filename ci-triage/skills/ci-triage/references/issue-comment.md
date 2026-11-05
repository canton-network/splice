# Issue comments

Reader: a senior engineer who knows splice and Canton but never opens the triage branch. The comment must stand on
its own: no packet paths or other triage-branch references.

Shape (Markdown, short paragraphs of one to three sentences; as short as the facts allow):
1. Verdict in one line: what kind of failure, and whether splice needs a change.
2. What failed, by plain job or test name (`docker-compose`, `key-download`, the test name), without run ids, shas,
   shard numbers or which duplicate belongs to which job: the issue and the linked duplicates show those.
3. The decisive log lines verbatim in a fenced code block (two to six lines, timestamps kept, secrets and long JSON
   trimmed with `...`), then one or two sentences interpreting them.
4. Only the facts that settle the verdict, one sentence each (a check that passed, the last good run). No timeline
   of the investigation, no list of runs checked.
5. Resolution only when someone has to act and the action is not obvious from the verdict: the fix in one sentence
   (file and change) and the owner. No reference to the fix PR or branch: the PR links the issue with `Fixes ...`.
No speculation about causes the evidence does not show (who or what did something, why), no @mentions (the
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

## Example (11217, as the user posted it on 2026-11-05)

Draft: `rounds/2026-11-05T094500Z-credentials/comments/11217.md`; posted: `11217-posted.md` next to it. The user cut
the run and duplicate details, the evidence timeline, the guess about the rotation, the resolution, the @mention and
"Not checked".

~~~
CI credentials broken, every run fails: since about 08:55 UTC on 2026-11-05, Auth0, GCP data export and GCP KMS reject the credentials the scala_test jobs get from the `_V2` GitHub secrets.

Failing jobs: docker-compose, the Auth0 frontend login tests, and the GCP bucket and KMS tests in key-download and wall-clock-time.

Three different credentials are rejected:

```
09:04:53 Failed to start secret machine login: failed to fetch access token using client credentials.
         error: oauth2: "access_denied" "Unauthorized".
09:02:23 com.auth0.exception.APIException: Request failed with status code 401: Unauthorized
08:58:12 StorageException: Error getting access token for service account: 400 Bad Request
         {"error":"invalid_grant","error_description":"Invalid JWT Signature."}, iss: splice-data-export-tests@da-cn-splice.iam.gserviceaccount.com
08:55:34 KMS operation `signing with key KmsKeyId(b14aeff8-...)` failed: ... UNAUTHENTICATED: Request had invalid authentication credentials.
```

The secrets are set (the `Check required secrets are set` step added by canton-network/splice#8248 passed), so the values, not the names, are wrong. The same `_V2` secrets worked until this morning. All three credentials broke within a few minutes of each other.
~~~

## Older example (11152, accepted by the user on 2026-11-03, longer than the user now prefers)

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
