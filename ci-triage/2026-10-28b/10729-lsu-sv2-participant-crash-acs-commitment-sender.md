# 10729 - LsuIntegrationTest: sv2Participant crashes itself during DSO party migration (AcsCommitmentSender close hangs)

Verdict: new family (O), Canton 3.7 bug, one hit. All later main runs have a green logical-sync-upgrade job.
sv2's SV app disconnects sv2Participant from the global synchronizer to migrate the DSO party (normal SV onboarding
path). Closing the new Canton 3.7 `AcsCommitmentSenderImpl` shuts down its kill switch and then waits for the stream's
done future; the stream had just entered a randomized commitment send delay ("Delaying sending commitment by 1267
seconds") that the kill switch does not interrupt. After `shutdownProcessing` (60 s) the close gives up, the disconnect
fails, and Canton crashes the node ("Fatal error occurred, crashing the node to recover from invalid state"). From then
on every Ledger API call to sv2Participant fails UNAVAILABLE, so sv2's ActAsKnownPartyAuthExtractor answers 403 and the
test's vote step fails with CommandFailure.

- Run: https://github.com/canton-network/splice/actions/runs/40667178473 (CI post-merge, main 24bfde02b2, #8017), job
  122745122704 `logical-sync-upgrade (0)`.
- Component: Canton participant, `canton/community/participant/.../commitment/AcsCommitmentSender.scala` (vendored with
  #7944, Canton 3.7). Splice trigger: SV onboarding with DSO party migration (`JoiningNodeInitializer`).
- Production relevance: any SV onboarding with DSO party migration on Canton 3.7 can hit it (the participant restarts
  in prod; in tests the node stays down).

## 1. Test failure

```
gh api repos/canton-network/splice/actions/jobs/122745122704/logs | grep -a -A2 '\*\*\* FAILED'
```
```
[info] - cancel a scheduled logical synchronizer upgrade *** FAILED ***
[info]   The future returned an exception of type: com.digitalasset.canton.console.CommandFailure, with message: Command execution failed.. (SvTestUtil.scala:277)
[info] - upgrade synchronizer to new physical synchronizer without downtime *** FAILED ***
```

## 2. The 403: sv2's auth lookup cannot reach sv2Participant

```
gh api repos/canton-network/splice/actions/artifacts/12809097412/zip > lsu.zip   # logs-logical-sync-upgrade-0
zcat canton_network_test.clog.gz | grep -a 'Authorization Failed for\|4e98c217f5cc025c73e8dba0a66c87e0' | grep -a 'WARN\|ListUserRights\|GetUser'
```
```
10:31:02.848Z UserManagementService/ListUserRights to 127.0.0.1:5201: sending request ListUserRightsRequest(sv2-dc063917, "")
10:31:02.849Z UserManagementService/ListUserRights to 127.0.0.1:5201: failed with UNAVAILABLE/io exception
10:31:02.849Z Authorization Failed for sv2-dc063917 for operation 'listDsoRulesVoteRequests'. Reason: UNAVAILABLE: io exception
```
Every call to 127.0.0.1:5201 (sv2Participant Ledger API) fails from 10:31:02.66 to the end of the run (`grep -a 'to
127.0.0.1:5201: failed with UNAVAILABLE' | cut -c1-19 | uniq -c`: 15 at :02, 18 at :03, then one every ~4 s until 10:36).

## 3. sv2Participant crashes itself

```
zcat canton.clog.gz | awk '/"@timestamp":"2026-10-28T10:31:0[0-3]/' | grep -a 'sv2Participant' | grep -a 'closing\|failed!\|Fatal'
```
```
10:31:01.983Z Task closing pipeline completion future did not complete within 60 seconds. Stack traces: ...
10:31:01.986Z Closing 'AsyncCloseable(name=pipeline completion future)' failed! [AcsCommitmentSenderImpl:participant=sv2Participant]
10:31:01.988Z Closing 'ConnectedSynchronizer(synchronizerId=global-domain::1220749c150e...::37-0, ...)' failed!
10:31:02.001Z Fatal error occurred, crashing the node to recover from invalid state: Failed to disconnect from Synchronizer 'global' due to an exception
```
The 100 thread dumps show every sv2Participant thread idle (WAITING on its executor queue): nothing is working on the
pipeline future, it is never completed.

## 4. Why the future never completes

```
zcat canton.clog.gz | grep -a 'participant=sv2Participant' | grep -a 'AcsCommitmentSender\|Disconnecting\|DSO party migration\|Not stashing' \
  | awk '/T10:(29:[3-5]|30:0)/'
```
```
10:29:40.752Z The DSO party is not authorized to our participant. Starting onboarding with DSO party migration. [JoiningNodeInitializer SV=sv2]
10:29:44.257Z The send-loop terminated due to an orderly shutdown.        <- first disconnect: no delay pending, closes fine
10:29:45.466Z All commitments up to Offset(1) have been sent successfully. Waiting for the next checkpoint signal.
10:30:01.956Z Not stashing notification for subscriber ACS commitment sender, because subscription was closed.
10:30:01.959Z Disconnecting from Synchronizer 'global'
10:30:01.966Z Attempting to close 'SyncCloseable(name=killSwitch shutdown)'... / Successfully closed
10:30:01.966Z Attempting to close 'AsyncCloseable(name=pipeline completion future)'...
10:30:01.969Z Delaying sending commitment by 1267 seconds (interval = 30m, min = 0.0, max = 0.9)
```
`AcsCommitmentSender.onClosed` (vendored at 24bfde02b2, lines ~186-200) runs `ks.shutdown()` and then awaits `doneF`
with `timeouts.shutdownProcessing`. The checkpoint signal that arrived as the subscription closed starts a send with a
randomized delay (here 1267 s, up to 0.9 x the 30 min interval) after the kill switch was already shut down; the
delayed element keeps the stream from completing, so `doneF` cannot complete within 60 s.

## Fix direction (Canton team)

Make the send delay interruptible on close (race the delay against the kill switch / a closing promise, or skip the
delay when `isClosing`), so a disconnect during a pending randomized send does not time out and crash the participant.
Splice-side test workaround is not appropriate: the crash is in the prod disconnect path.
