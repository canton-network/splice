# 11416 - simtime (3): MED::sv2 MediatorSynchronizerState pushes of sv2 and sv1 rejected after the 1 -> 4 switch, proposal stuck at 2 of 3 signatures; SvExpiredRewardsCollection setup fails, blast radius into TokenStandardMetadata (run 43465999013)

DUPLICATE of 11255 (rejected-send shape, setup-failure blast radius). Main 09f5cc4b78, job simtime (3), Canton
3.7.4-snapshot.20261112.20915.0.v9e248620, the second 11255 hit after the bump from 3.7.3 (#8479, the parent commit of
09f5cc4b78; the first is 11415 on the bump commit itself). 13 tests passed, 2 suites aborted in setup. In the setup of SvExpiredRewardsCollectionTimeBasedIntegrationTest
(fourth suite of the shard) the global ordering topology goes from 1 to 4 nodes at 17:16:23.030. The MediatorSynchronizerState
proposal adding MED::sv2 needs 3 of the 4 DSO namespace owners: sv4 and sv3 pushed it before the switch (both sequenced),
sv2 and sv1 pushed it 1.47 and 1.51 s after the switch and sv1's sequencer rejected both within 16 ms with `P2P
connectivity is not ready (authenticated = 1 < dissemination quorum = 2)`. Their outboxes stay in the open flush cycle
(max sequencing time 11:08:00.998 on a sim clock that stays at 11:07:4x), so the proposal never reaches 3 signatures,
MED::sv2 never gets a traffic state, and sv2's SV app waits on `Mediator has been granted unlimited traffic` until the
5 min init wait fails the setup at 17:21:51.917. No `has been reset` line follows, and the next suite
(TokenStandardMetadataTimeBasedIntegrationTest) fails sv1 init: its DSO PartyToParticipant is `Not fully authorized`
(82 retries) until 17:27:05.763. Shape unchanged against the 3.7.3 hits (11399, 11402: MED::sv2 add at the 1 -> 4 step).

- Run: https://github.com/canton-network/splice/actions/runs/43465999013, main 09f5cc4b78 ("Stop validators in the lsu
  test before restarting everything (#8469)"), job 132025674783 `ci / scala_test_sim_time / simtime (3)`, attempt 1.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at 09f5cc4b78).
- Component: Canton BFT ordering under simulated time (11255).

Artifact `logs-simtime-3` (id 14061478560) in `log/11416/logs-simtime-3/`; the commands below run in that directory
unless they name `log/11416/job.log`. `F` is the generic line formatter used throughout:

```
F='s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/::1220[0-9a-f]+/::../g; s/1220[0-9a-f]{60}/../g'
```

## 1. Job: two suites aborted in setup

```
git show 09f5cc4b78a1ee0f513c127657f21b45a6e65709:nix/canton-sources.json | grep -m1 version
git log --oneline -1 09f5cc4b78a1~1
sed -E 's/\x1b\[[0-9;]*m//g' log/11416/job.log | grep -a -E 'ABORTED|Tests: succeeded|Condition never became true' | sed -E 's/^[^Z]*Z //' | sort -u
grep -a -A16 -E '^\S+Z org\.lfdecentralizedtrust\.splice\.integration\.tests\.[A-Za-z0-9]+$' log/11416/job.log | grep -a -oE 'tests\.[A-Za-z0-9]+$' | sed 's/tests\.//' | sort -u
```
```
  "version": "3.7.4-snapshot.20261112.20915.0.v9e248620",
2c5cd9a752 Upgrade canton to 3.7.4-snapshot.20261112.20915.0.v9e248620 (#8479)
[info]   Cause: java.lang.IllegalStateException: Condition never became true within 5 minutes
[info]  *** ABORTED ***
[info] *** 2 SUITES ABORTED ***
[info] Tests: succeeded 13, failed 0, canceled 0, ignored 0, pending 0
[info] org.lfdecentralizedtrust.splice.integration.tests.SvExpiredRewardsCollectionTimeBasedIntegrationTest *** ABORTED ***
[info] org.lfdecentralizedtrust.splice.integration.tests.TokenStandardMetadataTimeBasedIntegrationTest *** ABORTED ***
SvExpiredRewardsCollectionTimeBasedIntegrationTest
TimeBasedTreasuryIntegrationTestWithoutMerging
TokenStandardMetadataTimeBasedIntegrationTest
ValidatorLicenseMetadataTimeBasedIntegrationTest
WalletTxLogTimeBasedIntegrationTest
```

Both stacks end in `SvAppReference.waitForInitialization` (`EnvironmentDefinition.scala:665`) inside `Running setup`.

## 2. Suite timeline: no reset after the failed setup

```
zcat canton_network_test.clog.gz | grep -a -E "Starting test suite|has been reset" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | tail -4
zcat canton_network_test.clog.gz | grep -a -E '"level":"(WARN|ERROR)"' | awk -F'"@timestamp":"' '{split($2,a,"\""); t=a[1]} t>="2026-11-13T17:14:42" && t<"2026-11-13T17:30"' | grep -a -E 'Timeout while waiting|Failed creating|Aborted' | sed -E "$F" | cut -c1-200
zcat canton_network_test.clog.gz | awk -F'"@timestamp":"' '{split($2,a,"\""); t=a[1]} t>="2026-11-13T17:14:42.9"' | grep -a -c 'has been reset'
```
```
2026-11-13T17:14:42.885Z decentralized namespace has been reset",
2026-11-13T17:14:42.896Z sequencer domain state threshold has been reset",
2026-11-13T17:14:42.923Z Starting test suite 'SvExpiredRewardsCollectionTimeBasedIntegrationTest'...",
2026-11-13T17:21:52.114Z Starting test suite 'TokenStandardMetadataTimeBasedIntegrationTest'...",
2026-11-13T17:21:51.917Z Timeout while waiting for initialization of sv2",[o.l.s.c.SvAppBackendReference:SvExpiredRewardsCollectionTimeBasedIntegrationTest/config=71a6b8da/app=sv2] ERROR
2026-11-13T17:21:51.918Z Failed creating environment: Running setup (EnvironmentSetup.scala:234)",[o.l.s.i.t.SvExpiredRewardsCollectionTimeBasedIntegrationTest:SvExpiredRewardsCollectionTimeBasedIntegrationTest] ERROR
2026-11-13T17:21:52.105Z Aborted test suite 'SvExpiredRewardsCollectionTimeBasedIntegrationTest'.",[c.d.c.LogReporter:reporter=scala-test] WARN
2026-11-13T17:27:05.763Z Timeout while waiting for initialization of sv1",[o.l.s.c.SvAppBackendReference:TokenStandardMetadataTimeBasedIntegrationTest/config=9b7893ef/app=sv1] ERROR
2026-11-13T17:27:05.764Z Failed creating environment: Running setup (EnvironmentSetup.scala:234)",[o.l.s.i.t.TokenStandardMetadataTimeBasedIntegrationTest:TokenStandardMetadataTimeBasedIntegrationTest] ERROR
2026-11-13T17:27:05.806Z Aborted test suite 'TokenStandardMetadataTimeBasedIntegrationTest'.",[c.d.c.LogReporter:reporter=scala-test] WARN
0
```

The first three suites passed and each test ended with the namespace reset; after 17:14:42.9 there is none.

## 3. Ordering topology on globalSequencerSv1: 1 -> 4 in one step, nobody blacklisted

```
zcat canton-simtime.clog.gz | awk -F'"@timestamp":"' '{split($2,a,"\""); t=a[1]} t>="2026-11-13T17:14:42" && t<"2026-11-13T17:22"' | grep -a 'New epoch' | grep -a 'sequencer=globalSequencerSv1' | sed -E 's/\{"@timestamp":"([^"]+)","message":"New epoch ([0-9]+) has started with leaders = List\(([^)]*)\)and blacklisted nodes = List\(([^)]*)\).*size = ([0-9]+).*/\1 epoch \2 leaders=[\3] blacklisted=[\4] size=\5/; s/::1220[0-9a-f]+//g' | cut -c1-200 | awk '{k=$4" "$5" "$6; if (k!=last) print; last=k}' | head -3
```
```
2026-11-13T17:14:43.531Z epoch 122 leaders=[SEQ::sv1] blacklisted=[] size=1
2026-11-13T17:16:23.030Z epoch 131 leaders=[SEQ::sv4, SEQ::sv1, SEQ::sv2, SEQ::sv3] blacklisted=[] size=4
2026-11-13T17:16:45.763Z epoch 132 leaders=[SEQ::sv1, SEQ::sv2, SEQ::sv3, SEQ::sv4] blacklisted=[] size=4
```

All later epochs up to 161 (17:21:59) are size 4 with `blacklisted=[]` (full output of the command without `head`).

## 4. The MED::sv2 proposal needs 3 of 4 owners; sv4 and sv3 push before the switch, sv2 and sv1 after

```
zcat canton_network_test.clog.gz | grep -a -E 'Submitted proposal (DecentralizedNamespaceDefinition|MediatorSynchronizerState\(\\n)' | awk -F'"@timestamp":"' '{split($2,a,"\""); t=a[1]} t>="2026-11-13T17:16:15" && t<"2026-11-13T17:16:25"' | sed -E "$F" | sed -E 's/\\n */ /g; s/synchronizerId = [^,]*, //; s/, waiting until.*(SV=sv[0-9]).*/ [\1]/' | cut -c1-260
zcat canton-simtime.clog.gz | awk -F'"@timestamp":"' '{split($2,a,"\""); t=a[1]} t>="2026-11-13T17:16:22.3" && t<"2026-11-13T17:22"' | grep -a -E 'QueueBasedSynchronizerOutbox' | grep -a -E 'cb93339e5cb3|Another flush' | sed -E "$F" | sed -E 's/c\.d\.c\.t\.[^:]*QueueBasedSynchronizerOutbox:participant=([^/]*)[^]]*/\1/' | cut -c1-200 | awk '{k=$0; sub(/^[^ ]+ /,"",k); if (k!=last) print; last=k}'
```
```
2026-11-13T17:16:15.101Z Submitted proposal DecentralizedNamespaceDefinition(namespace = 12203f6cc4d2..., threshold = 3, owners = Seq(122047e1a0de..., 122051a51851..., 1220f208df0b..., 1220f4c48e3c...)) for Namespace 1220f208df0b... is in owners of Decentraliz
2026-11-13T17:16:22.382Z Submitted proposal MediatorSynchronizerState( group = 0, threshold = 2, active = Seq(MED::sv1::....., MED::sv3::....., MED::sv4::....., MED::sv2::.....) ) for Add mediator MED::sv2::..... [SV=sv4]
2026-11-13T17:16:22.533Z Submitted proposal MediatorSynchronizerState( group = 0, threshold = 2, active = Seq(MED::sv1::....., MED::sv3::....., MED::sv4::....., MED::sv2::.....) ) for Add mediator MED::sv2::..... [SV=sv3]
2026-11-13T17:16:24.496Z Submitted proposal MediatorSynchronizerState( group = 0, threshold = 2, active = Seq(MED::sv1::....., MED::sv3::....., MED::sv4::....., MED::sv2::.....) ) for Add mediator MED::sv2::..... [SV=sv2]
2026-11-13T17:16:24.544Z Submitted proposal MediatorSynchronizerState( group = 0, threshold = 2, active = Seq(MED::sv1::....., MED::sv3::....., MED::sv4::....., MED::sv2::.....) ) for Add mediator MED::sv2::..... [SV=sv1]
2026-11-13T17:16:22.382Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:cb93339e5cb3...))",[sv4Participant] DEBUG
2026-11-13T17:16:22.532Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:cb93339e5cb3...))",[sv3Participant] DEBUG
2026-11-13T17:16:22.632Z Synchronizer 'global' responded the following for the given topology transactions: List((Accepted,TxHash(SHA-256:cb93339e5cb3...)))",[sv4Participant] DEBUG
2026-11-13T17:16:22.953Z Synchronizer 'global' responded the following for the given topology transactions: List((Accepted,TxHash(SHA-256:cb93339e5cb3...)))",[sv3Participant] DEBUG
2026-11-13T17:16:24.496Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:cb93339e5cb3...))",[sv2Participant] DEBUG
2026-11-13T17:16:24.544Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:cb93339e5cb3...))",[sv1Participant] DEBUG
2026-11-13T17:18:22.311Z Another flush cycle is currently ongoing",[sv1Participant] DEBUG
2026-11-13T17:18:25.691Z Another flush cycle is currently ongoing",[sv2Participant] DEBUG
2026-11-13T17:20:20.033Z Another flush cycle is currently ongoing",[sv1Participant] DEBUG
```

The namespace owned by 4 SVs with threshold 3 is the DSO namespace (`global-domain::12203f6cc4d2...`). The pushes of
sv2 and sv1 never get a response; their outboxes stay in the open cycle and the later re-proposals (17:18, 17:20) do
not push.

sv1's participant stores the two sequenced pushes (sequenced at sim time 11:07:41.213 and .214); the later three lines
with `11:07:41.214001Z` are sv1's own local re-proposals (17:16:24.542 is sv1's submit at 17:16:24.544), not sequenced
signatures:

```
zcat canton-simtime.clog.gz | grep -a 'cb93339e5cb3' | grep -a -E 'TopologyStateWriteThroughCache:participant=sv1Participant' | grep -a 'Adding StoredTopologyTransaction' | sed -E 's/\{"@timestamp":"([^"]+)".*sequenced = ([^,]*),.*/\1 sequenced = \2/'
```
```
2026-11-13T17:16:22.636Z sequenced = 1970-01-01T11:07:41.213Z
2026-11-13T17:16:22.956Z sequenced = 1970-01-01T11:07:41.214Z
2026-11-13T17:16:24.542Z sequenced = 1970-01-01T11:07:41.214001Z
2026-11-13T17:18:22.310Z sequenced = 1970-01-01T11:07:41.214001Z
2026-11-13T17:20:20.032Z sequenced = 1970-01-01T11:07:41.214001Z
```

## 5. The sv2 and sv1 pushes are rejected 1.48 s and 1.52 s after the switch; no other P2P rejection

```
zcat canton-simtime.clog.gz | awk -F'"@timestamp":"' '{split($2,a,"\""); t=a[1]} t>="2026-11-13T17:14:42" && t<"2026-11-13T17:22"' | grep -a -E 'Rejecting submission request' | grep -a 'P2P connectivity is not ready' | sed -E "$F" | cut -c1-260
zcat canton-simtime.clog.gz | grep -a -E 'Request to send submission with id (30bbb56b|9ee33df4)' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/::1220[0-9a-f]+/::../g; s/ to Set.*//'
zcat canton-simtime.clog.gz | awk -F'"@timestamp":"' '{split($2,a,"\""); t=a[1]} t>="2026-11-13T17:14:42" && t<"2026-11-13T17:22"' | awk '/currently blacklisted/{b++} /Failed to send result/{f++} /Rejecting submission request by/{r++} END{print "currently_blacklisted="b+0, "failed_to_send_result="f+0, "rejecting_submission_request_by="r+0}'
```
```
2026-11-13T17:16:24.512Z Rejecting submission request (30bbb56b-f39e-4476-b26f-2795d992780f) synchronously: SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",
2026-11-13T17:16:24.512Z Rejecting submission request by Some(PAR::sv2::.....) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequenc
2026-11-13T17:16:24.550Z Rejecting submission request (9ee33df4-a422-4856-becc-a0fe05a82a02) synchronously: SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",
2026-11-13T17:16:24.550Z Rejecting submission request by Some(PAR::sv1::.....) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequenc
2026-11-13T17:16:24.502Z Request to send submission with id 30bbb56b-f39e-4476-b26f-2795d992780f with max sequencing time 1970-01-01T11:08:00.998Z from PAR::sv2::.....
2026-11-13T17:16:24.549Z Request to send submission with id 9ee33df4-a422-4856-becc-a0fe05a82a02 with max sequencing time 1970-01-01T11:08:00.998Z from PAR::sv1::.....
currently_blacklisted=0 failed_to_send_result=0 rejecting_submission_request_by=2
```

The other `Rejecting submission request` lines in the window are `SEQUENCER_AGGREGATE_SUBMISSION_ALREADY_SENT` and
`SEQUENCER_AGGREGATE_SUBMISSION_STUFFING` rejections of MED/SEQ aggregatable sends, the usual duplicates of
already-delivered aggregations, not part of this failure.

Message 30bbb56b is sv2Participant's push of 17:16:24.496 (`Sending message ID 30bbb56b-... to sequencer SEQ::sv1`
at 17:16:24.500, `zcat canton-simtime.clog.gz | grep -a 30bbb56b-f39e-4476-b26f-2795d992780f`), 9ee33df4 sv1Participant's
push of 17:16:24.544 (5 ms later). Zero `currently blacklisted` and `Failed to send result` rule out the lost-verdict
and breaker shapes.

## 6. The sim clock never reaches the max sequencing time, so the cycle never closes

```
zcat canton_network_test.clog.gz | grep -a 'Updated domain time to' | awk -F'"@timestamp":"' '{split($2,a,"\""); t=a[1]} t>="2026-11-13T17:21:40" && t<"2026-11-13T17:21:52"' | tail -1 | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/",.*//'
```
```
2026-11-13T17:21:51.918Z Completed processing PeriodicTask(for = 1970-01-01T11:07:40.998Z) with outcome: Updated domain time to 1970-01-01T11:07:43.456Z
```

Domain time 11:07:43.456 at the end of the 5 min wait against max sequencing time 11:08:00.998.

## 7. MED::sv2 never gets a traffic state; sv2 waits on its unlimited traffic until shutdown

```
zcat canton_network_test.clog.gz | grep -a 'does not yet have a traffic state' | grep -a 'MED::sv2' | grep -a -oE '^\{"@timestamp":"[^"]+' | awk '{t=substr($0,16)} t>="2026-11-13T17:14:42.9" && t<"2026-11-13T17:22" {if(!f) f=t; l=t; n++} END{print n" lines, first "f", last "l}'
zcat canton_network_test.clog.gz | grep -a -E 'SV=sv2' | grep -a -E '(Waiting until|Gave up waiting until) Mediator has been granted unlimited traffic' | awk -F'"@timestamp":"' '{split($2,a,"\""); t=a[1]} t>="2026-11-13T17:14:42.9" && t<"2026-11-13T17:22"' | sed -E "$F" | cut -c1-140
```
```
1246 lines, first 2026-11-13T17:16:19.709Z, last 2026-11-13T17:21:52.040Z
2026-11-13T17:16:27.734Z Waiting until Mediator has been granted unlimited traffic",[o.l.s.s.LocalSynchronizerNode:SvExpiredRewardsCollectio
2026-11-13T17:21:52.045Z Gave up waiting until Mediator has been granted unlimited traffic, as we are shutting down",[o.l.s.s.LocalSynchroni
```

In between, sv2 retries `Check whether Mediator has been granted unlimited traffic` every 2-5 s.

The `does not yet have a traffic state` lines come from sv1's `SvOnboardingUnlimitedTrafficTrigger`.

## 8. Blast radius: next suite's sv1 cannot authorize the DSO party (`Not fully authorized`)

```
zcat canton_network_test.clog.gz | awk -F'"@timestamp":"' '{split($2,a,"\""); t=a[1]} t>="2026-11-13T17:21:52.1" && t<"2026-11-13T17:27:06"' | grep -a -c 'Check whether Party DSO-9b7893ef-9b7893ef.*failed with a retryable error'
zcat canton-simtime.clog.gz | grep -a -E '700fbab52e752c394ea6a99fcd07b576' | grep -a 'Topology transactions failed' | grep -a -oE 'rejectionReason[^)]*\)?[^)]*' | sed -E 's/::1220[0-9a-f]+/::../g; s/\\n */ /g' | cut -c1-120 | head -1
```
```
82
rejectionReason = Not fully authorized )","logger_name":"c.d.c.t.p.TopologyStateProcessorImpl:participant=sv1Participant
```

tid 700fbab52e752c394ea6a99fcd07b576 is sv1's `TopologyManagerWriteService/Authorize` retry at 17:27:01.085 for the
`PartyToParticipant(DSO-9b7893ef-9b7893ef ...)` proposal. Same end state as 11341 and the other setup-step hits: the
failed setup skips the plugins' teardown, the decentralized namespace of the previous environment is not reset.

## Verdict

Duplicate of 11255 (family B, rejected-send shape at the 1 -> 4 step; MED::sv2 add as in 11399 and 11402; setup-failure
blast radius as in 11341). Flake in Canton BFT ordering under simulated time; the fix belongs with the parent (open,
assigned rautenrieth-da). Second hit on Canton 3.7.4-snapshot.20261112.20915.0.v9e248620 (after 11415): shape unchanged, the bump did
not remove it. Rejections came 1.48 to 1.52 s after the switch, outside the 0.3 s window of the original confirming
grep (as noted for 11398). Not verified: why sv2 and sv1 proposed 2 s after sv4 and sv3 (their SV apps reached the
MED::sv2 step later); no Canton source citation made.
