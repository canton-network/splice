# 11535 - simtime (3): MED::sv3 MediatorSynchronizerState pushes of sv3, sv1 and sv4 rejected after the 1 -> 4 switch, sv2 outbox stuck; SvExpiredRewardsCollection setup fails, blast radius into two more suites (run 44043765534)

DUPLICATE of 11255 (family B, rejected-send shape at the 1 -> 4 step, setup-failure blast radius). Main 54f83a5ddd, job
simtime (3), Canton 3.7.4-snapshot.20261112.20915.0.v9e248620. 9 tests passed, 3 suites aborted in `Running setup`,
checkErrors skipped. In the setup of SvExpiredRewardsCollectionTimeBasedIntegrationTest (fourth suite of the shard) the
global ordering topology goes from 1 to 4 nodes at 08:36:02.390. The MediatorSynchronizerState proposal adding MED::sv3
is pushed by sv3 and sv1 21 ms after the switch and by sv4 189 ms after it; sv1's sequencer rejects all three within
20 ms with `P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2)`. sv2 does not push it at all:
its outbox is still in the flush cycle of its previous push, batched by sv1 175 ms before the switch and never ordered
(no `Deliver`). All four outboxes stay in an open flush cycle (max sequencing time 14:01:51.998 on a sim clock that
stays at 14:01:33), MED::sv3 never gets a traffic state, and sv3's SV app waits on `Mediator has been granted unlimited
traffic` until the 5 min init wait fails the setup at 08:39:44.005. No `has been reset` line follows, and the next two
suites (SvTimeBasedBootstrappingRound, WalletAmuletPriceTimeBased) fail sv1 init on a DSO PartyToParticipant that is
`Not fully authorized`. Same shape as 11415 (same suite, MED::sv3) and 11416; the other failed shard of this run,
simtime (1), is 11534, also 11255.

- Run: https://github.com/canton-network/splice/actions/runs/44043765534, main 54f83a5ddd ("Remove stale TODOs
  (#8506)"), job 133905191530 `ci / scala_test_sim_time / simtime (3)`.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at 54f83a5ddd).
- Component: Canton BFT ordering under simulated time (11255).

Artifact `logs-simtime-3` (id 14312754116) in `log/11535/logs-simtime-3/`; the commands below run in that directory
unless they name `log/11535/job.log`. On macOS `zcat` is the nix gzip one (or use `gzcat`). `F` is the generic line
formatter, `../win-canton.log` the Canton log of the failing setup:

```
F='s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/::1220[0-9a-f]+/::../g; s/1220[0-9a-f]{60}/../g'
zcat canton-simtime.clog.gz | awk -F'"@timestamp":"' '{split($2,a,"\""); t=a[1]} t>="2026-11-17T08:34:07" && t<"2026-11-17T08:39:45"' > ../win-canton.log
```

## 1. Job: three suites aborted in setup

```
git show 54f83a5ddd:nix/canton-sources.json | grep -m1 version
sed -E 's/\x1b\[[0-9;]*m//g' log/11535/job.log | grep -a -E 'ABORTED|Tests: succeeded|Condition never became true|\[error\] 	org' | sed -E 's/^[^Z]*Z //' | sort -u
grep -a -A16 -E '^\S+Z org\.lfdecentralizedtrust\.splice\.integration\.tests\.[A-Za-z0-9]+$' log/11535/job.log | grep -a -oE 'tests\.[A-Za-z0-9]+$' | sed 's/tests\.//' | sort -u
```
```
  "version": "3.7.4-snapshot.20261112.20915.0.v9e248620",
[error] 	org.lfdecentralizedtrust.splice.integration.tests.SvExpiredRewardsCollectionTimeBasedIntegrationTest
[error] 	org.lfdecentralizedtrust.splice.integration.tests.SvTimeBasedBootstrappingRoundIntegrationTest
[error] 	org.lfdecentralizedtrust.splice.integration.tests.WalletAmuletPriceTimeBasedIntegrationTest
[info]   Cause: java.lang.IllegalStateException: Condition never became true within 5 minutes
[info]  *** ABORTED ***
[info] *** 3 SUITES ABORTED ***
[info] Tests: succeeded 9, failed 0, canceled 0, ignored 0, pending 0
[info] org.lfdecentralizedtrust.splice.integration.tests.SvExpiredRewardsCollectionTimeBasedIntegrationTest *** ABORTED ***
[info] org.lfdecentralizedtrust.splice.integration.tests.SvTimeBasedBootstrappingRoundIntegrationTest *** ABORTED ***
[info] org.lfdecentralizedtrust.splice.integration.tests.WalletAmuletPriceTimeBasedIntegrationTest *** ABORTED ***
SvExpiredRewardsCollectionTimeBasedIntegrationTest
SvTimeBasedBootstrappingRoundIntegrationTest
UnhideAndExpireRewardCouponV2TimeBasedIntegrationTest
ValidatorLicenseMetadataTimeBasedIntegrationTest
WalletAmuletPriceTimeBasedIntegrationTest
WalletTxLogTimeBasedIntegrationTest
```

All three stacks end in `SvAppReference.waitForInitialization` (`EnvironmentDefinition.scala:665`) inside
`Creating environment failed at step Running setup (EnvironmentSetup.scala:234)`.

## 2. Suite timeline: sv3 times out first, no reset afterwards

```
zcat canton_network_test.clog.gz | grep -a -E "Starting test suite|Aborted test suite|Timeout while waiting for init|Completed test suite" | sed -E "$F" | cut -c1-200
zcat canton_network_test.clog.gz | grep -a 'has been reset' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | tail -2
zcat canton_network_test.clog.gz | awk -F'"@timestamp":"' '{split($2,a,"\""); t=a[1]} t>="2026-11-17T08:39:44.2"' | grep -a -c 'has been reset'
```
```
2026-11-17T08:17:06.775Z Starting test suite 'UnhideAndExpireRewardCouponV2TimeBasedIntegrationTest'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-17T08:26:35.380Z Completed test suite 'UnhideAndExpireRewardCouponV2TimeBasedIntegrationTest'.",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-17T08:26:35.437Z Starting test suite 'WalletTxLogTimeBasedIntegrationTest'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-17T08:31:59.607Z Completed test suite 'WalletTxLogTimeBasedIntegrationTest'.",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-17T08:31:59.620Z Starting test suite 'ValidatorLicenseMetadataTimeBasedIntegrationTest'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-17T08:34:07.183Z Completed test suite 'ValidatorLicenseMetadataTimeBasedIntegrationTest'.",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-17T08:34:07.192Z Starting test suite 'SvExpiredRewardsCollectionTimeBasedIntegrationTest'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-17T08:39:44.005Z Timeout while waiting for initialization of sv3",[o.l.s.c.SvAppBackendReference:SvExpiredRewardsCollectionTimeBasedIntegrationTest/config=95bc7ecd/app=sv3] ERROR
2026-11-17T08:39:44.214Z Aborted test suite 'SvExpiredRewardsCollectionTimeBasedIntegrationTest'.",[c.d.c.LogReporter:reporter=scala-test] WARN
2026-11-17T08:39:44.228Z Starting test suite 'SvTimeBasedBootstrappingRoundIntegrationTest'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-17T08:44:58.691Z Timeout while waiting for initialization of sv1",[o.l.s.c.SvAppBackendReference:SvTimeBasedBootstrappingRoundIntegrationTest/config=f20eaa3d/app=sv1] ERROR
2026-11-17T08:44:58.734Z Aborted test suite 'SvTimeBasedBootstrappingRoundIntegrationTest'.",[c.d.c.LogReporter:reporter=scala-test] WARN
2026-11-17T08:44:58.744Z Starting test suite 'WalletAmuletPriceTimeBasedIntegrationTest'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-17T08:50:15.786Z Timeout while waiting for initialization of sv1",[o.l.s.c.SvAppBackendReference:WalletAmuletPriceTimeBasedIntegrationTest/config=fb493b5c/app=sv1] ERROR
2026-11-17T08:50:15.825Z Aborted test suite 'WalletAmuletPriceTimeBasedIntegrationTest'.",[c.d.c.LogReporter:reporter=scala-test] WARN
2026-11-17T08:34:07.143Z decentralized namespace has been reset",
2026-11-17T08:34:07.155Z sequencer domain state threshold has been reset",
0
```

## 3. Ordering topology on globalSequencerSv1: 1 -> 4 in one step, nobody blacklisted

```
grep -a 'New epoch' ../win-canton.log | grep -a 'sequencer=globalSequencerSv1' | sed -E 's/\{"@timestamp":"([^"]+)","message":"New epoch ([0-9]+) has started with leaders = List\(([^)]*)\)and blacklisted nodes = List\(([^)]*)\).*size = ([0-9]+).*/\1 epoch \2 leaders=[\3] blacklisted=[\4] size=\5/; s/::1220[0-9a-f]+//g' | cut -c1-200 | awk '{k=$4" "$5" "$6; if (k!=last) print; last=k}' | head -3
```
```
2026-11-17T08:34:16.081Z epoch 127 leaders=[SEQ::sv1] blacklisted=[] size=1
2026-11-17T08:36:02.390Z epoch 136 leaders=[SEQ::sv1, SEQ::sv2, SEQ::sv3, SEQ::sv4] blacklisted=[] size=4
2026-11-17T08:36:17.862Z epoch 137 leaders=[SEQ::sv2, SEQ::sv3, SEQ::sv4, SEQ::sv1] blacklisted=[] size=4
```

## 4. Confirming grep of the family: P2P rejections of SV participant sends right after the switch, no blacklisting, no lost verdict

```
grep -a -E 'Rejecting submission request by' ../win-canton.log | grep -a 'P2P connectivity is not ready' | sed -E "$F" | cut -c1-230
grep -a -E 'Request to send submission with id (e3ce6896|70da089d|077cec28)' ../win-canton.log | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/::1220[0-9a-f]+/::../g; s/ to Set.*//'
awk '/currently blacklisted/{b++} /Failed to send result/{f++} /Rejecting submission request by/{r++} END{print "currently_blacklisted="b+0, "failed_to_send_result="f+0, "rejecting_submission_request_by="r+0}' ../win-canton.log
grep -a 'Rejecting submission request by' ../win-canton.log | grep -av 'P2P connectivity' | sed -E "$F" | cut -c1-200
```
```
2026-11-17T08:36:02.431Z Rejecting submission request by Some(PAR::sv3::.....) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s
2026-11-17T08:36:02.437Z Rejecting submission request by Some(PAR::sv1::.....) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s
2026-11-17T08:36:02.587Z Rejecting submission request by Some(PAR::sv4::.....) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s
2026-11-17T08:36:02.416Z Request to send submission with id 70da089d-805b-4fad-aedf-ff5d75dd699c with max sequencing time 1970-01-01T14:01:51.998Z from PAR::sv1::.....
2026-11-17T08:36:02.417Z Request to send submission with id e3ce6896-996f-4d24-8483-dca95db96824 with max sequencing time 1970-01-01T14:01:51.998Z from PAR::sv3::.....
2026-11-17T08:36:02.585Z Request to send submission with id 077cec28-e3a1-43d1-9704-f14b89f7b53c with max sequencing time 1970-01-01T14:01:51.998Z from PAR::sv4::.....
currently_blacklisted=0 failed_to_send_result=0 rejecting_submission_request_by=4
2026-11-17T08:36:15.298Z Rejecting submission request by Some(MED::sv2::.....) with SequencerDeliverError(code = SEQUENCER_AGGREGATE_SUBMISSION_ALREADY_SENT, message = Not accepting submission as aggregation ID SHA-256:2
```

Rejections 41, 47 and 197 ms after the size-4 epoch (inside the 0.3 s window of the catalogue's grep). The fourth
`Rejecting submission request by` is the usual duplicate of an already delivered aggregation, not part of the failure.

## 5. The rejected sends are the MED::sv3 MediatorSynchronizerState pushes; sv2's outbox is stuck on its previous push

```
zcat canton_network_test.clog.gz | grep -a -E 'Submitted proposal MediatorSynchronizerState\(\\n' | awk -F'"@timestamp":"' '{split($2,a,"\""); t=a[1]} t>="2026-11-17T08:35:55" && t<"2026-11-17T08:36:10"' | sed -E "$F" | sed -E 's/\\n */ /g; s/synchronizerId = [^,]*, //; s/, waiting until.*(SV=sv[0-9]).*/ [\1]/' | cut -c1-230
grep -a -E 'QueueBasedSynchronizerOutbox' ../win-canton.log | awk -F'"@timestamp":"' '{split($2,a,"\""); t=a[1]} t>="2026-11-17T08:36:01.9"' | grep -a -E 'Attempting to push|responded the following|Another flush' | grep -a -E 'participant=sv' | sed -E "$F" | sed -E 's/c\.d\.c\.t\.[^:]*QueueBasedSynchronizerOutbox:participant=([^/]*)[^]]*/\1/' | cut -c1-200
```
```
2026-11-17T08:36:02.411Z Submitted proposal MediatorSynchronizerState( group = 0, threshold = 2, active = Seq(MED::sv1::....., MED::sv2::....., MED::sv4::....., MED::sv3::.....) ) for Add mediator MED::sv3::..... [SV=sv3]
2026-11-17T08:36:02.411Z Submitted proposal MediatorSynchronizerState( group = 0, threshold = 2, active = Seq(MED::sv1::....., MED::sv2::....., MED::sv4::....., MED::sv3::.....) ) for Add mediator MED::sv3::..... [SV=sv1]
2026-11-17T08:36:02.579Z Submitted proposal MediatorSynchronizerState( group = 0, threshold = 2, active = Seq(MED::sv1::....., MED::sv2::....., MED::sv4::....., MED::sv3::.....) ) for Add mediator MED::sv3::..... [SV=sv4]
2026-11-17T08:36:02.797Z Submitted proposal MediatorSynchronizerState( group = 0, threshold = 2, active = Seq(MED::sv1::....., MED::sv2::....., MED::sv4::....., MED::sv3::.....) ) for Add mediator MED::sv3::..... [SV=sv2]
2026-11-17T08:36:01.932Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:4489692eb77a...))",[sv1Participant] DEBUG
2026-11-17T08:36:02.134Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:4489692eb77a...))",[sv4Participant] DEBUG
2026-11-17T08:36:02.185Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:4489692eb77a...))",[sv2Participant] DEBUG
2026-11-17T08:36:02.282Z Synchronizer 'global' responded the following for the given topology transactions: List((Accepted,TxHash(SHA-256:4489692eb77a...)))",[sv1Participant] DEBUG
2026-11-17T08:36:02.322Z Synchronizer 'global' responded the following for the given topology transactions: List((Accepted,TxHash(SHA-256:4489692eb77a...)))",[sv4Participant] DEBUG
2026-11-17T08:36:02.411Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:f803668da7ba...))",[sv1Participant] DEBUG
2026-11-17T08:36:02.411Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:f803668da7ba...))",[sv3Participant] DEBUG
2026-11-17T08:36:02.579Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:f803668da7ba...))",[sv4Participant] DEBUG
2026-11-17T08:36:02.795Z Another flush cycle is currently ongoing",[sv2Participant] DEBUG
2026-11-17T08:37:50.684Z Another flush cycle is currently ongoing",[sv4Participant] DEBUG
2026-11-17T08:38:00.689Z Another flush cycle is currently ongoing",[sv3Participant] DEBUG
2026-11-17T08:38:04.215Z Another flush cycle is currently ongoing",[sv1Participant] DEBUG
2026-11-17T08:38:05.126Z Another flush cycle is currently ongoing",[sv2Participant] DEBUG
2026-11-17T08:39:42.740Z Another flush cycle is currently ongoing",[sv4Participant] DEBUG
```

The three pushes of f803668da7ba (the MED::sv3 add) at 08:36:02.411/.411/.579 are the three rejected sends of section 4
(5-6 ms before each `Request to send submission`); none gets a response. sv2 never pushes f803668da7ba: its push of
the previous transaction 4489692eb77a (08:36:02.185, message 6acec5f5) is batched by sv1 175 ms before the switch and
never delivered:

```
grep -a '6acec5f5-11d2-476d-9e00-38c06ef6bad6' ../win-canton.log | grep -a 'LocalBatchCreated' | sed -E "$F" | cut -c1-150
grep -a -c '6acec5f5-11d2-476d-9e00-38c06ef6bad6' ../win-canton.log; grep -a '6acec5f5-11d2-476d-9e00-38c06ef6bad6' ../win-canton.log | grep -a -c 'Deliver'
```
```
2026-11-17T08:36:02.215Z Availability.LocalDissemination.LocalBatchCreated: created batch ID BatchId(..dab5) with reference epoch 135 for batch received from
12
0
```

Mixed hit as 11409: rejected sends plus one batch created just before the size-4 epoch and never ordered.

## 6. The sim clock never reaches the max sequencing time; MED::sv3 never gets a traffic state

```
zcat canton_network_test.clog.gz | grep -a 'Updated domain time to' | awk -F'"@timestamp":"' '{split($2,a,"\""); t=a[1]} t>="2026-11-17T08:39:30" && t<"2026-11-17T08:39:44.1"' | tail -1 | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/",.*//'
zcat canton_network_test.clog.gz | grep -a 'does not yet have a traffic state' | grep -a 'MED::sv3' | grep -a -oE '^\{"@timestamp":"[^"]+' | awk '{t=substr($0,16)} t>="2026-11-17T08:34:07" && t<"2026-11-17T08:39:45" {if(!f) f=t; l=t; n++} END{print n" lines, first "f", last "l}'
zcat canton_network_test.clog.gz | grep -a -E 'SV=sv3' | grep -a -E '(Waiting until|Gave up waiting until) Mediator has been granted unlimited traffic' | awk -F'"@timestamp":"' '{split($2,a,"\""); t=a[1]} t>="2026-11-17T08:34:07" && t<"2026-11-17T08:39:45"' | sed -E "$F" | cut -c1-140
```
```
2026-11-17T08:39:44.050Z Completed processing PeriodicTask(for = 1970-01-01T14:01:31.998Z) with outcome: Updated domain time to 1970-01-01T14:01:33.521Z
849 lines, first 2026-11-17T08:36:01.783Z, last 2026-11-17T08:39:43.997Z
2026-11-17T08:36:06.937Z Waiting until Mediator has been granted unlimited traffic",[o.l.s.s.LocalSynchronizerNode:SvExpiredRewardsCollectionTimeBasedIntegratio
2026-11-17T08:39:44.156Z Gave up waiting until Mediator has been granted unlimited traffic, as we are shutting down",[o.l.s.s.LocalSynchronizerNode:SvExpiredRew
```

Domain time 14:01:33.521 at the end of the 5 min wait against max sequencing time 14:01:51.998: the rejected sends
stay "possibly sequenced" and the flush cycles never close.

## 7. Blast radius: the next two suites' sv1 cannot authorize the DSO party

```
zcat canton_network_test.clog.gz | awk -F'"@timestamp":"' '{split($2,a,"\""); t=a[1]} t>="2026-11-17T08:39:44.2"' | grep -a -E 'Check whether Party DSO-[0-9a-f-]*.*failed with a retryable error' | grep -a -oE 'Party DSO-[0-9a-f]+' | sort | uniq -c
zcat canton-simtime.clog.gz | awk -F'"@timestamp":"' '{split($2,a,"\""); t=a[1]} t>="2026-11-17T08:39:44.2"' | grep -a 'Topology transactions failed' | grep -a -oE 'rejectionReason = [A-Za-z ]+' | sort | uniq -c
```
```
     84 Party DSO-f20eaa3d
     81 Party DSO-fb493b5c
    165 rejectionReason = Not fully authorized
```

f20eaa3d and fb493b5c are the configs of SvTimeBasedBootstrappingRound and WalletAmuletPriceTimeBased (section 2).
The failed setup skips the plugins' teardown, so the decentralized namespace of the SvExpiredRewardsCollection
environment (4 owners) is never reset.

## Verdict

Duplicate of 11255 "Timeouts due to changing bft ordering topology" (open, assigned rautenrieth-da; family B,
rejected-send shape at the 1 -> 4 step with one never-ordered batch, MED::sv3 add as in 11415, setup-failure blast
radius as in 11341). Flake in Canton BFT ordering under simulated time; the mitigation is splice#8363 ("Onboard SV
sequencers one at a time in tests"), still open on 2026-11-17. No fix written here and nothing changed outside this
packet. Both failed shards of run 44043765534 are this family (simtime (1) is 11534). Not verified: no Canton source
citation; which topology transaction 4489692eb77a is (sv1, sv3 and sv4 got it accepted before the switch, so only
sv2's outbox depends on it); no local run.
