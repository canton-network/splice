# 11340 - SvFrontendIntegrationTest expiring proposal closed by sv2 before the in-flight check (run 42823122392)

DUPLICATE of 11186 (open, unassigned, pawelperek-da pinged 2026-11-05). main 1844b95301 (#8404), job 129853835620
`frontend-wall-clock-time (2)` (attempt 1), Canton 3.7.3-snapshot.20261109.20894.0.ve6e6c252; 31 tests passed, 1 failed:
`Set Dso Rules Configuration proposals can be rejected by other SVs and can expire`. The test pauses
CloseVoteRequestTrigger only on sv1, but every SV runs it (delegateless, `SvTaskBasedTrigger`). The 10 s proposal
(created 13:52:23.307, voteBefore 13:52:33.307) was closed by sv2 (task 13:52:39.543, committed 13:52:42.743, seen by
sv1 13:52:42.643) while the test was still in the UI step that creates the second proposal; the in-flight check
started 13:52:41.623 and failed 13:53:04.505. The fix proposed on 11186 (pause and resume on sv1 to sv4) is not on main.

- Run: https://github.com/canton-network/splice/actions/runs/42823122392, main 1844b95301 ("Remove more cometbft references (#8404)"), job 129853835620 `frontend-wall-clock-time (2)`.
- Runtime canton: 3.7.3-snapshot.20261109.20894.0.ve6e6c252.
- Component: test.

## 1. Failing assertion

```
sed 's/\x1b\[[0-9;]*m//g' log/11340/job.log | grep -a -A3 'can expire \*\*\* FAILED' | head -4 | cut -c30-2000
```
```
[info] - should Set Dso Rules Configuration proposals can be rejected by other SVs and can expire *** FAILED ***
[info]   List("This is a summary of the proposal", "This is a summary of the proposal", "This is a summary of the proposal", "This is a summary of the proposal", "This is a summary of the proposal", "This is a summary of the proposal", "This is a summary of the proposal", "This proposal gets rejected by the other SVs", "This is a summary of the proposal") did not contain element "This proposal expires before anybody votes on it" (SvFrontendIntegrationTest.scala:846)
[info]   org.scalatest.exceptions.TestFailedException:
[info]   at org.scalatest.matchers.MatchersHelper$.indicateFailure(MatchersHelper.scala:392)
```

Line 846 at the run's sha is `inflight should contain(expiringProposalReason)`; only sv1's trigger is paused (lines 805-810):
```
F=apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/SvFrontendIntegrationTest.scala
git show 1844b95301:$F | sed -n '805,810p;842,847p'
```
```
        clue("Pausing vote request expiration automation") {
          sv1Backend.dsoDelegateBasedAutomation
            .trigger[CloseVoteRequestTrigger]
            .pause()
            .futureValue
        }
          clue("sv1 sees both proposals in flight and neither in the vote history") {
            go to s"http://localhost:$sv1UIPort/governance"
            eventually() {
              val inflight = rowDescriptions("inflight-proposals")
              inflight should contain(expiringProposalReason)
              inflight should contain(rejectedProposalReason)
```

## 2. Test case timeline

Artifact `logs-frontend-wall-clock-time-2` (recipe 3) in `log/11340/`.
```
T=log/11340/logs-frontend-wall-clock-time-2/canton_network_test.clog.gz
zcat $T | grep -aE 'Starting test case|Running clue|Finished clue|Failed clue|Test failed' | jq -r '"\(.["@timestamp"][11:23]) \(.message|gsub("\n";" ")|.[0:170])"' | awk '$1>="13:52:23" && $1<="13:53:05"' | grep -v 'Waiting for\|login\|Clear session\|Dumping\|Saving\|Logging'
```
```
13:52:23.249 Starting test case 11
13:52:23.250 Running clue: Pausing vote request expiration automation
13:52:23.253 Finished clue: Pausing vote request expiration automation
13:52:23.254 Running clue: (act) sv1 creates a proposal with a short expiration time
13:52:27.350 Finished clue: (act) sv1 creates a proposal with a short expiration time
13:52:27.350 Running clue: (check) the short-lived proposal exists
13:52:27.371 Finished clue: (check) the short-lived proposal exists
13:52:27.371 Running clue: sv1 creates a proposal via the UI
13:52:30.655 Running clue: (check) can see the governance page
13:52:30.945 Finished clue: (check) can see the governance page
13:52:30.945 Running clue: (act) sv1 can navigate to the create proposal page
13:52:31.012 Running clue: select action and click next
13:52:31.641 Finished clue: select action and click next
13:52:31.641 Finished clue: (act) sv1 can navigate to the create proposal page
13:52:31.642 Running clue: (check) sv1 can see the create proposal form
13:52:32.073 Finished clue: (check) sv1 can see the create proposal form
13:52:32.076 Running clue: (act) sv1 operator can create a new proposal
13:52:33.101 Finished clue: (act) sv1 operator can create a new proposal
13:52:33.101 Running clue: (check) sv1 is redirected to the governance page after successful submission
13:52:41.608 Finished clue: (check) sv1 is redirected to the governance page after successful submission
13:52:41.622 Finished clue: sv1 creates a proposal via the UI
13:52:41.623 Running clue: sv1 sees both proposals in flight and neither in the vote history
13:53:04.505 Failed clue: sv1 sees both proposals in flight and neither in the vote history
13:53:04.786 Test failed: 'SvFrontendIntegrationTest/SV UIs should Set Dso Rules Configuration proposals can be rejected by other SVs and can expire', message: List("This is a summary
```

Both vote request creations took about 4 s on the ledger (the backend one and the UI one):
```
zcat $T | awk '/"@timestamp":"2026-11-10T13:52:(2[3-9]|3[0-9]|4[0-2])/' | grep -aE 'voterequest/create' | grep -aE 'received request|Responding' | jq -r '"\(.["@timestamp"][11:23]) | \(.message|gsub("\n";" ")|.[0:150])"'
```
```
13:52:23.285 | HTTP POST /api/sv/v0/admin/sv/voterequest/create from (127.0.0.1:37112): received request.
13:52:27.345 | HTTP POST /api/sv/v0/admin/sv/voterequest/create from (127.0.0.1:37112): Responding with status code: 200 OK, took 4060 ms
13:52:33.033 | HTTP POST /api/sv/v0/admin/sv/voterequest/create from (127.0.0.1:40526): received request.
13:52:36.985 | HTTP POST /api/sv/v0/admin/sv/voterequest/create from (127.0.0.1:40526): Responding with status code: 200 OK, took 3951 ms
```
All creates in the shard (for scale; 1 to 7.7 s):
```
zcat $T | grep -aE 'voterequest/create from .*Responding' | jq -r '"\(.["@timestamp"][11:23]) \(.message|capture("took (?<t>[0-9]+ ms)").t)"' | tr '\n' ';'
```
```
13:48:00.582 0 ms;13:48:01.740 1157 ms;13:48:21.753 985 ms;13:48:47.193 6063 ms;13:49:16.987 966 ms;13:49:46.653 1656 ms;13:50:19.308 7098 ms;13:50:54.645 4865 ms;13:51:21.070 2345 ms;13:51:42.048 1463 ms;13:52:10.937 7694 ms;13:52:27.345 4060 ms;13:52:36.985 3951 ms;13:53:08.669 3839 ms;13:53:27.693 1741 ms;
```

## 3. sv2 closes the expiring proposal

sv2, sv3 and sv4 all pick up the expired request (sv1 is paused); sv2 commits, sv3 and sv4 lose the race:
```
zcat $T | grep -aF 'CloseVoteRequestTrigger' | awk '/"@timestamp":"2026-11-10T13:52/' | jq -r '"\(.["@timestamp"][11:23]) \(.level) \((.logger_name|split(":")[1] // "")|split("/")|last) | \(.message|gsub("\n";" ")|gsub("  +";" ")|.[0:110])"' | grep -E 'Pausing|Processing|Completing'
```
```
13:52:23.253 DEBUG SV=sv1 | Pausing trigger.
13:52:38.797 INFO SV=sv3 | Processing ReadyTask( readyAt = 2026-11-10T13:52:33.791517Z, work = AssignedContract( contract = Contract( con
13:52:38.803 INFO SV=sv2 | Processing ReadyTask( readyAt = 2026-11-10T13:52:33.798662Z, work = AssignedContract( contract = Contract( con
13:52:39.175 INFO SV=sv4 | Processing ReadyTask( readyAt = 2026-11-10T13:52:34.169734Z, work = AssignedContract( contract = Contract( con
13:52:39.543 INFO SV=sv2 | Completing dso delegate task ReadyTask( readyAt = 2026-11-10T13:52:33.798662Z, work = AssignedContract( contra
13:52:39.737 INFO SV=sv3 | Completing dso delegate task ReadyTask( readyAt = 2026-11-10T13:52:33.791517Z, work = AssignedContract( contra
13:52:40.178 INFO SV=sv4 | Completing dso delegate task ReadyTask( readyAt = 2026-11-10T13:52:34.169734Z, work = AssignedContract( contra
```
The task is the expiring proposal (reason body), created 13:52:23.307162Z, voteBefore 10 s later:
```
zcat $T | grep -aF '"@timestamp":"2026-11-10T13:52:38.803Z"' | grep -aF 'Processing' | jq -r '.message' | grep -aE 'reason|This proposal' | head -2
zcat $T | grep -aF '"@timestamp":"2026-11-10T13:52:38.803Z"' | grep -aF 'Processing' | jq -r '.message' | grep -aoE '(Timestamp|createdAt)[^,)]*' | head -3
```
```
          Record(fields = Seq("https://new-proposal-url.com/", "This proposal expires before anybody votes on it")),
Timestamp(ms = 1794318753307162
Timestamp(ms = 1794318743307162
createdAt = 2026-11-10T13:52:23.307162Z
```
(1794318753307162 us = 2026-11-10T13:52:33.307162Z, the voteBefore; the other is the creation time.)

Outcomes of the three submissions:
```
for t in 74cf241f2e7269801b93c8c913504025 148139e13fd3ed512ed9caade0d6a740 206df8a445007ce3d8400991c3310eac; do zcat $T | grep -aF "tid:$t" | grep -aE 'succeeded|failed' | jq -r '"\(.["@timestamp"][11:23]) \(.message|gsub("\n";" ")|.[0:200])"' | head -1; done
```
```
13:52:42.743 Request (tid:74cf241f2e7269801b93c8c913504025) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5201: succeeded(OK)
13:52:42.883 Request (tid:148139e13fd3ed512ed9caade0d6a740) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5301: failed with ABORTED/LOCAL_VERDICT_LOCKED_CONTRACTS(2,148139e1): Rejected transaction is referring to locked 
13:52:42.889 Request (tid:206df8a445007ce3d8400991c3310eac) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5401: failed with ABORTED/LOCAL_VERDICT_LOCKED_CONTRACTS(2,206df8a4): Rejected transaction is referring to locked 
```
sv1's ingestion received sv2's transaction (same update id) at 13:52:42.643, 1 s after the in-flight check started:
```
zcat $T | grep -aF 'tid:74cf241f2e7269801b93c8c913504025' | grep -aF 'received a message' | jq -r '.message' | grep -aoE '122045cb92137a66[0-9a-f]+' | head -1
zcat $T | grep -aF '"@timestamp":"2026-11-10T13:52:42.643Z"' | grep -aF 'tid:079e60b6e0366676bfcba5fc6fcf6085' | jq -r '.message' | grep -aoE '122045cb92137a66[0-9a-f]+' | head -1
```
```
122045cb92137a66b678cf6d1449abff08a0a03ac376503f9a44c4c1fde7483b2750
122045cb92137a66b678cf6d1449abff08a0a03ac376503f9a44c4c1fde7483b2750
```

## 4. Every SV runs CloseVoteRequestTrigger (delegateless)

```
git show 1844b95301:apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/automation/delegatebased/SvTaskBasedTrigger.scala | sed -n '61,71p'
```
```
    // Pick a random delay in Uniform~(0, upperBound) to avoid all nodes triggering at the same time
    // This formula ensures a uniform random delay across SVs that never exceeds the pollingTriggerInterval
    // and helps to distribute the load between nodes.
    val upperBound =
      Math.min(
        dsoRules.payload.svs
          .size()
          .toLong * svTaskContext.delegatelessAutomationExpectedTaskDuration,
        pollingTriggerInterval,
      )
    val delay = Random.nextLong(upperBound)
```
Other tests pause it on all SVs (or both SVs that matter):
```
git show 1844b95301:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/SvStateManagementIntegrationTest.scala | sed -n '330,332p;693,695p'
```
```
          // Pause so SV3 can be stopped before it gets offboarded
          triggersToPauseAtStart =
            activeSvs.map(_.dsoDelegateBasedAutomation.trigger[CloseVoteRequestTrigger])
    clue("Pausing vote request expiration automation") {
      Seq(sv1Backend, sv2Backend).foreach(
        _.dsoDelegateBasedAutomation.trigger[CloseVoteRequestTrigger].pause().futureValue
```

## 5. Duplicate check and fix state

```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "expires before anybody votes"' --jq '.items[] | "#\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:10]) \(.title[0:110])"'
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "SvFrontendIntegrationTest" created:>=2026-09-23' --jq '.items[] | "#\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:10]) \(.title[0:110])"'
```
```
#11186 open/- 2026-11-04 SvFrontendIntegrationTest: expiring proposal closed by sv2-sv4 before the in-flight check (trigger paused only
#11186 open/- 2026-11-04 SvFrontendIntegrationTest: expiring proposal closed by sv2-sv4 before the in-flight check (trigger paused only
#11141 open/- 2026-11-02 Shard start: app DB pools time out on the first Postgres connection (5 s) while the runner is slow, createData
#10273 closed/duplicate 2026-10-02 castVote times out in SvFrontendIntegrationTest
#10350 closed/completed 2026-10-15 SvFrontendIntegrationTest: "Could not find IdQuery(login-button)" when SST auto-login unmounts the login form
```
11186: same failure (same assertion and mechanism, sv4 closed it there). 11141, 10273, 10350: different failures.
```
gh api repos/DACH-NY/cn-test-failures/issues/11186 --jq '"#\(.number) \(.state) assignees=\([.assignees[].login]|join(",")) comments=\(.comments)"'
gh api repos/DACH-NY/cn-test-failures/issues/11186/comments --jq '.[] | "-- \(.user.login) \(.created_at) \(.body[0:80])"'
git log -1 --format='%h %ci' origin/main
git show origin/main:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/SvFrontendIntegrationTest.scala | grep -n -A4 'Pausing vote request expiration automation'
git blame --line-porcelain -L 805,810 1844b95301 -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/SvFrontendIntegrationTest.scala | grep -E '^(author-mail|summary) ' | sort -u
```
```
#11186 open assignees= comments=2
-- rautenrieth-da 2026-11-05T23:03:12Z Test bug: the test pauses vote request expiry only on sv1, but every SV runs Clo
-- rautenrieth-da 2026-11-05T23:04:09Z @pawelperek-da Could you please have a look?
1844b95301 2026-11-10 14:17:04 +0100
805:        clue("Pausing vote request expiration automation") {
806-          sv1Backend.dsoDelegateBasedAutomation
807-            .trigger[CloseVoteRequestTrigger]
808-            .pause()
809-            .futureValue
author-mail <pawel.perek@digitalasset.com>
summary Remove old governance from the SV UI (#7452)
```
Not in `known-families.md` or `INDEX.tsv` (11186 has only a packet, `evidence/11186-sv-expiring-proposal-closed-by-other-svs.md`):
```
grep -n '11186' ci-triage/evidence/INDEX.tsv ci-triage/known-families.md; ls ci-triage/evidence | grep 11186
```
```
11186-sv-expiring-proposal-closed-by-other-svs.md
```

## Verdict

Duplicate of 11186 (second hit: 2026-11-04, 2026-11-10). Test bug from #7452 (90dc504ae2, author pawel.perek@digitalasset.com, i.e. pawelperek-da): the test pauses
CloseVoteRequestTrigger only on sv1 while every SV runs it, so the expiring proposal survives only until about
voteBefore + 5 s (13:52:38.8 here) plus the close round trip; the test needs the UI proposal flow (14 s here,
13:52:27.4 to 13:52:41.6, with two ledger round trips of about 4 s) to finish before that. Fix in the test, unchanged
from 11186: pause (lines 805-810) and resume (line 856) CloseVoteRequestTrigger on sv1Backend to sv4Backend, as
SvStateManagementIntegrationTest does. Not on main (origin/main 1844b95301 still pauses sv1 only). Not verified: what
the UI list showed between 13:52:41.6 and the archive (no per-poll UI log); not relevant to the cause, since the
request was closed regardless.
