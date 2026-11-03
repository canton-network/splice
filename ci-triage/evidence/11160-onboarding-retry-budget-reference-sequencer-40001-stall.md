# 11160 - TokenStandardCliTestData: alice onboarding gives up while global ordering stalls on insert block 40001 (run 41631747535)

New occurrence of family L (reference sequencer `insert block` SQLSTATE 40001 retry storm), new symptom: wallet
onboarding exhausts its retry budget. Release-line run, release-line-0.10.0 0ad3ce0394 ("Backport PR #8191 to
release-line-0.10.0 (#8192)", a Grafana dashboard change only), job simtime (2), Canton 3.7.1. 19 tests pass, 1
fails: in TokenStandardCliTestDataTimeBasedIntegrationTest "expire amulet allocations handling both locked and
already unlocked amulets", the first step `onboardWalletUser(aliceWalletClient, aliceValidatorBackend)` fails with
HTTP 400 INVALID_PRESCRIBED_SYNCHRONIZER_ID. alice's new party became effective on splitwell at 12:29:15.85 but on
the global synchronizer only at 12:29:27.6, because globalSequencerSv1 did not process a block from 12:29:10.33 to
12:29:27.59 while the reference driver's `insert block` kept failing with SQLSTATE 40001 (backoff 0.05 s up to
6.373 s). `installWalletForUser` (ValidatorUtil.scala) prescribes the global synchronizer and gave up after 12
retries at 12:29:23.5, 4 s too early. Not a missing backport (no main-only change to the onboarding code or the test).

- Run: https://github.com/canton-network/splice/actions/runs/41631747535, release-line-0.10.0 0ad3ce0394 ("Backport PR #8191 to release-line-0.10.0 (#8192)"), job 125903399617 `ci / scala_test_sim_time / simtime (2)`, attempt 1.
- Runtime canton: 3.7.1 (`nix/canton-sources.json` at 0ad3ce0394).
- Component: Canton test infra (reference sequencer store contention, family L); splice validator onboarding retry budget makes it fatal.

## 1. Issue and job

Issue body (export 2026-11-03 13:14), verbatim:
```
[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/41631747535).
Job: simtime (2) in scala_test
Branch: [refs/heads/release-line-0.10.0](https://github.com/canton-network/splice/tree/refs/heads/release-line-0.10.0)
Workflow: CI post-merge to main or release-line branches
Commit: [0ad3ce0](https://github.com/canton-network/splice/commit/0ad3ce0394cb10d269bccfa61e34382faa83f41d) Backport PR #8191 to release-line-0.10.0 (#8192)
Actor: moritzkiefer-da
```
The run has one attempt; exactly one failing job matches:
```
gh api "/repos/canton-network/splice/actions/runs/41631747535/attempts/1/jobs?per_page=100" --paginate --jq '.jobs[] | select(.name | endswith("simtime (2)")) | "\(.id) \(.name) \(.conclusion) \(.completed_at)"'
```
```
125903399617 ci / scala_test_sim_time / simtime (2) failure 2026-11-03T12:33:49Z
```

## 2. Failing assertion

```
gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/125903399617/logs > log/11160/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/11160/job.log | grep -a -A6 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | head -7
```
```
[info] - expire amulet allocations handling both locked and already unlocked amulets *** FAILED ***
[info]   com.digitalasset.canton.console.CommandFailure: Command execution failed.
[info] Run completed in 21 minutes, 15 seconds.
[info] Total number of tests run: 20
[info] Suites: completed 9, aborted 0
[info] Tests: succeeded 19, failed 1, canceled 0, ignored 1, pending 0
[info] *** 1 TEST FAILED ***
```
The artifact `logs-simtime-2` (id 13230477586) has the cause:
```
gh api /repos/canton-network/splice/actions/artifacts/13230477586/zip > log/11160/logs-simtime-2.zip
zcat canton_network_test.clog.gz | grep -a -n 'expire amulet allocations handling\|Failed clue\|HttpCommandException' | cut -c1-330
```
```
1177380:{"@timestamp":"2026-11-03T12:29:14.368Z","message":"Starting 'TokenStandardCliTestDataTimeBasedIntegrationTest/expire amulet allocations handling both locked and already unlocked amulets'...",...
1185517:{"@timestamp":"2026-11-03T12:29:23.611Z","message":"org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommandException: HTTP 400 Bad Request POST at '/api/validator/v0/admin/users' on 127.0.0.1:5503. Command failed, message: INVALID_PRESCRIBED_SYNCHRONIZER_ID(9,7a61b839): Not all informees are on the specified synchronizer: global-domain::122088c38f7c...::37-0, but on Set(splitwell::12205850d0f6...::36-0)
1185518:{"@timestamp":"2026-11-03T12:29:23.611Z","message":"Failed clue: Onboard alice_wallet_user-94aea4bd_tc1 on aliceValidator",...
```
The failing step is the first line of the test case (`onboardWalletUser(aliceWalletClient, aliceValidatorBackend)`,
TokenStandardCliTestDataTimeBasedIntegrationTest.scala:967 at 0ad3ce0394).

## 3. installWalletForUser retries 12 times from 12:29:16.40 to 12:29:23.53

```
zcat canton_network_test.clog.gz | sed -n '1177380,1185521p' | grep -a 'installWalletForUser' | cut -c1-200
```
```
{"@timestamp":"2026-11-03T12:29:16.405Z","message":"The operation 'installWalletForUser' failed with a retryable error (full stack trace omitted):\ncategory=Some(InvalidGivenCurrentSystemStateOther)\nErrorInfoDetail(INVALID_PRESCRIBED_SYNCHRONIZER_ID,HashMap(participant -> aliceParticipant, physicalSynchronizerId -> global-domain::122088c38f7c...::37-0, synchronizersOfAllInformees -> Set(splitwell::12205850d0f6...::3
... (same at 16.521, 16.689, 17.000, 17.477, 18.011, 18.863, 19.729, 20.562, 21.398, 22.119, 22.750)
{"@timestamp":"2026-11-03T12:29:23.526Z","message":"The operation 'installWalletForUser' has failed with an exception. Total maximum number of retries 12 exceeded. Giving up. ",...
```
`ValidatorUtil.installWalletForUser` (0ad3ce0394) submits the WalletAppInstall with `.withSynchronizerId(synchronizerId)`
(the AmuletRules synchronizer) inside `retryProvider.retry(retryFor, ...)`, `retryFor = RetryFor.ClientCalls`. The party
was created by `createPartyAndUser`, which waits only on `ensurePartyAllocated(TopologyStoreId.Authorized, ...)`, i.e.
the participant's own store, not on the PartyToParticipant being effective on the global synchronizer.

## 4. alice's party: effective on splitwell after 1.5 s, on global after 13.2 s

```
zcat canton-simtime.clog.gz | grep -a 'alice__wallet__user-94aea4bd__tc1' | grep -a -E '"message":"Persisted topology' | (python: timestamp, logger)
```
```
2026-11-03T12:29:14.914Z Persisted topology tra SequencedTime(1970-03-06T03:01:14.000002Z), ... participant=aliceParticipant/store=Authorized
2026-11-03T12:29:15.851Z Persisted topology tra SequencedTime(1970-03-06T03:01:14.000837Z), ... participant=aliceParticipant/psid=splitwell::12205850d0f6::36-0
2026-11-03T12:29:27.618Z Persisted topology tra SequencedTime(1970-03-06T03:01:14.002287Z), ... participant=bobParticipant/psid=global-domain::122088c38f7c::37-0
... (all global-domain members 12:29:27.618 to 12:29:27.636, aliceParticipant at 12:29:27.636)
```
The broadcast to global (message id 624b7da8) was accepted by globalSequencerSv1 at 12:29:14.922 and ordered in block
6840 at 12:29:27.587:
```
zcat canton-simtime.clog.gz | grep -a '624b7da8-22a0-4d4d-858b-76327b7fa692' | (python: time, logger tail, message)
```
```
12:29:14.922 INFO c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1 'PAR::aliceValidator::12206a7bc4d1...' sends request with id '624b7da8-22a0-4d4d-858b-76327b7fa692' of size 680 bytes with 1 envelopes to ALL=1.
12:29:14.923 DEBUG ...sequencer=globalSequencerSv1/psid=global-domain::122088c38f7c::37-0 Estimated sequencing time 1970-03-06T03:01:14.002286Z for submission with id 624b7da8-22a0-4d4d-858b-76327b7fa692
12:29:27.587 DEBUG ...sequencer=globalSequencerSv1/psid=global-domain::122088c38f7c::37-0 At block 6840, the submission request 624b7da8-22a0-4d4d-858b-76327b7fa692 at 1970-03-06T03:01:14.002287Z validated to: Deliver
12:29:27.618 DEBUG ...participant=aliceParticipant/psid=global-domain::122088c38f7c::37-0 Received the receipt for a previously sent batch: Deliver(   previous timestamp = Some(1970-03-06T03:01:14.001354Z),   timestamp = 1970-03-06T03:01:14.002287Z, ...
```

## 5. Family L confirm: globalSequencerSv1 store stalls 18.2 s on insert block 40001

The global synchronizer runs the reference driver (`sequencers.conf:33` `type = reference` at 0ad3ce0394). Family L
confirming check, first `enqueued ... tag send` paired with the next `Created batch`, waits over 1 s:
```
zcat canton-simtime.clog.gz | awk '/"@timestamp":"2026-11-03T12:29:(0[5-9]|1[0-9]|2[0-8])/' | grep -a 'globalSequencerSv1' | grep -a -E 'enqueued reference sequencer store request|Created batch reference-driver-requests-batch' | (python pairing)
```
```
first enqueued 12:29:07.259 -> Created batch 12:29:08.581 wait 1.3 s
first enqueued 12:29:09.478 -> Created batch 12:29:27.680 wait 18.2 s
```
The `insert block` chain on globalSequencerSv1 in that window (INFO, from DbStorageSingle):
```
29:11.392 INFO c.d.c.r.DbStorageSingle Detected an SQLException. SQL state: 40001, error code: 0
29:11.392 INFO c.d.c.r.DbStorageSingle The operation 'insert block' has failed with an exception. New kind of error: transient error (request infinite retries). Retrying after 0.05s.
29:12.071 INFO c.d.c.r.DbStorageSingle Detected an SQLException. SQL state: 40001, error code: 0
29:12.936 INFO c.d.c.r.DbStorageSingle Detected an SQLException. SQL state: 40001, error code: 0
29:14.117 INFO c.d.c.r.DbStorageSingle Detected an SQLException. SQL state: 40001, error code: 0
29:14.914 INFO c.d.c.r.DbStorageSingle The operation 'insert block' has failed with an exception. Retrying after 0.084s.
29:15.000 INFO c.d.c.r.DbStorageSingle The operation 'insert block' has failed with an exception. Retrying after 0.1s.
29:15.846 INFO c.d.c.r.DbStorageSingle The operation 'insert block' has failed with an exception. Retrying after 0.224s.
29:16.344 INFO c.d.c.r.DbStorageSingle The operation 'insert block' has failed with an exception. Retrying after 0.581s.
29:16.974 INFO c.d.c.r.DbStorageSingle The operation 'insert block' has failed with an exception. Retrying after 1.31s.
29:18.827 INFO c.d.c.r.DbStorageSingle The operation 'insert block' has failed with an exception. Retrying after 2.169s.
29:21.166 INFO c.d.c.r.DbStorageSingle The operation 'insert block' has failed with an exception. Retrying after 6.373s.
```
globalSequencerSv2, Sv3 and Sv4 log the same `insert block` failures from 12:29:09.193 on. The last block processed
by globalSequencerSv1 before the gap:
```
29:10.329 INFO c.d.c.s.b.u.BlockChunkProcessor Processing block 6740, data chunk 0. Last chunk ts=1970-03-06T03:01:14.001354Z, ...
```
and the next one is 6840 at 12:29:27.587 (section 4). The window starts right after a sim-time advance
(`12:29:05.004 SimClock Advancing sim clock to 1970-03-06T03:01:14Z`), followed by a burst of
MAX_SEQUENCING_TIME_EXCEEDED rejections and resubmissions from all nodes.

## 6. Not a missing backport (family E)

```
git log --format='%h %ad %s' --date=short $(git merge-base 0ad3ce0394 https-origin/main)..https-origin/main -- apps/validator/src/main/scala/org/lfdecentralizedtrust/splice/validator/util/ValidatorUtil.scala apps/validator/src/main/scala/org/lfdecentralizedtrust/splice/validator/admin/http/HttpValidatorAdminHandler.scala apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/TokenStandardCliTestDataTimeBasedIntegrationTest.scala
git show --stat --format='%h %s' 0ad3ce0394 | head -4
```
```
(no output)
0ad3ce0394 Backport PR #8191 to release-line-0.10.0 (#8192)

 ...ashboards-jvm.kubernetes_core_v1_ConfigMap.json |  2 +-
 .../observability/grafana-dashboards/jvm/jvm.json  | 78 +++++++++++++++++-----
```
main (5c53b74294) has no change to the onboarding code or the test since the release line branched; the run's commit
only touches a Grafana dashboard. Recent issues export (2026-10-20 on): no other issue mentions
INVALID_PRESCRIBED_SYNCHRONIZER_ID, "Not all informees" or TokenStandardCliTestData.

## Verdict

Family L (reference sequencer `insert block` SQLSTATE 40001 retry storm), new occurrence on release-line-0.10.0 with a
new symptom: onboarding a wallet user right after allocating its party fails when global ordering stalls for more
than about 7 s, because `installWalletForUser` prescribes the global synchronizer, the party is only known on
splitwell at that point, and the ClientCalls retry budget (12 retries) runs out. Flake; root contention is Canton /
test infra (shared Postgres for the reference sequencers, serializable `insert block`, uncapped backoff).

Fix options (not written):
- Splice validator app (owner decision): in `ValidatorUtil.onboard`, after `createPartyAndUser` and before
  `installWalletForUser`, wait until the party's PartyToParticipant is effective on the AmuletRules synchronizer
  (`synchronizerId` from `getAmuletRulesDomain()`), e.g. with `participantAdminConnection` listing
  PartyToParticipant for the party on that synchronizer's store, under `RetryFor.ClientCalls`. This also removes
  the race in production when global ordering is slow.
- Family L itself: fewer writers per database or a non-serializable `insert block` in the test sequencer setup.
No test-side fix: the step is the generic `onboardWalletUser` helper used by every suite.

Not verified: why the 40001 storm started at 12:29:09 (beyond the sim-time advance burst); whether 10139 and 10197
(family L parents, older than the export) are still open.
