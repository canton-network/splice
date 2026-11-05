# 11186 - SvFrontendIntegrationTest expiring proposal closed by sv2-sv4 before the in-flight check (run 41840258688)

new. main cf81b9067bc9, job 126610553201 `frontend-wall-clock-time (2)` (attempt 1). Test bug: the test pauses
CloseVoteRequestTrigger only on sv1, but every SV runs it; when the UI step takes longer than the 10 s expiry, another
SV closes the expiring request before the in-flight check.

- Run: https://github.com/canton-network/splice/actions/runs/41840258688, main cf81b9067bc9, job 126610553201.
- Component: test.

## 1. Failing assertion
```
sed -E 's/^[0-9T:.-]+Z //; s/\x1b\[[0-9;]*m//g' log/11186/job.log | grep -a -m1 -oE 'did not contain element "This proposal expires before anybody votes on it" \([A-Za-z]+\.scala:[0-9]+\)'
```
```
did not contain element "This proposal expires before anybody votes on it" (SvFrontendIntegrationTest.scala:804)
```
Line 804 is `inflight should contain(expiringProposalReason)` in clue "sv1 sees both proposals in flight".

## 2. Test timeline (artifact logs-frontend-wall-clock-time-2, canton_network_test.clog.gz)
```
14:29:03.226 CloseVoteRequestTrigger:SV=sv1 | Pausing trigger.
14:29:03.228 Running clue: (act) sv1 creates a proposal with a short expiration time
14:29:07.230 Running clue: sv1 creates a proposal via the UI
14:29:18.050 Finished clue: sv1 creates a proposal via the UI
14:29:18.051 Running clue: sv1 sees both proposals in flight and neither in the vote history
14:29:42.304 Failed clue: sv1 sees both proposals in flight and neither in the vote history
```
The request is created with `RelTime(10 s)`, so it expires about 14:29:13.

## 3. Other SVs close it
```
zcat canton_network_test.clog.gz | awk -F'"@timestamp":"' '{t=substr($2,12,12)} t>="14:29:03" && t<="14:29:43"' | grep -aE 'CloseVoteRequest' | grep -av ApiClientRequestLogger
```
```
14:29:18.492 CloseVoteRequestTrigger:SV=sv4 | Processing ReadyTask(readyAt = 2026-11-04T14:29:13.487142Z, ...
14:29:21.005 CloseVoteRequestTrigger:SV=sv4 | Completed processing with outcome: closing VoteRequest (voteRequestCid: ContractId(00b7dcf14f36...
14:29:21.522 CloseVoteRequestTrigger:SV=sv3 | skipped, as the task has become stale
14:29:21.527 CloseVoteRequestTrigger:SV=sv2 | skipped, as the task has become stale
```
The check could only pass between 14:29:18.05 and 14:29:21.0.

## Verdict
New, test bug, introduced with the test in canton-network/splice#7452 (pawelperek-da, 2026-09-23; git blame of the
pause and resume lines). Fix (test only): pause and resume CloseVoteRequestTrigger on sv1 to sv4, not only on sv1.
Rare: first hit (no earlier issue for the test name). Not verified: why the UI step took 10.8 s this time.
