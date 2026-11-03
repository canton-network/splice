# 11163 - SvTimeBasedRewardCouponIntegrationTest: sv2 ACS import refused, last event is a topology offset; SV app exits the JVM (run 41637827972)

DUPLICATE of 10907 (family U), third hit after 10907 (sv4, SvTimeBasedOnboardingIntegrationTest) and 11107. Main
5c53b74294, job simtime (1). While SvTimeBasedRewardCouponIntegrationTest started its environment, sv2 joined the DSO:
it submitted the DSO PartyToParticipant proposal, disconnected its participant, fetched the ACS snapshot from the
sponsor and called ImportPartyAcs, which sv2Participant refused with IMPORT_ACS_ERROR "Cannot apply a repair command as
the last event is a topology offset" (13:11:32.295). The error is non-retryable, SV app init failed and NodeBase
exited the shared sbt JVM 2 ms later (family H evidence loss): no ScalaTest summary, no test report for the running
suite, the remaining suite of the shard (SvTimeBasedBootstrappingRoundIntegrationTest) never ran, checkErrors skipped.
Not caused by #8202 (offboarding trigger only). The same join path succeeded for sv2 at 13:06:41 in
TimeBasedTestNetPreviewIntegrationTest, so it is a timing race, not a deterministic break.

- Run: https://github.com/canton-network/splice/actions/runs/41637827972, main 5c53b74294 ("Don't offboard DSO hosting
  participants that are still used by an active SV (#8202)"), job 125924096050 `ci / scala_test_sim_time / simtime (1)`.
- Runtime canton: 3.7.1 (nix/canton-sources.json at 5c53b74294).
- Component: splice SV app join flow (JoiningNodeDsoPartyHosting) against a Canton 3.7 repair precondition.

## 1. Failing step output
The Run Tests step ends with the SV app init failure and the JVM exit; no ScalaTest summary.
```
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11163/job.log | grep -a -E 'Test still running|app initialization|IMPORT_ACS_ERROR|Run completed|TEST FAILED|All tests passed|##\[error\]' | awk '!s[$0]++' | cut -c1-200 | tail -8
```
```
sv2 app initialization: Initialization failed, so exiting; check the application logs for details
java.lang.RuntimeException: sv2 app initialization: Initialize app failed
Caused by: java.lang.RuntimeException: sv2 app initialization: Initialize node failed
Caused by: java.lang.RuntimeException: sv2 app initialization: JoiningNodeInitializer joining Dso with key failed
Caused by: io.grpc.StatusRuntimeException: INVALID_ARGUMENT: IMPORT_ACS_ERROR(8,751432a7): Cannot apply a repair command as the last event is a topology offset. Please reconnect to the synchronizer
##[error]Error: failed to run script step (id baec76b0-d784-11f1-a978-cf598328a665): Error: step failed with return code 1
##[error]Process completed with exit code 1.
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
```

## 2. Which suite was running
Last suites started in the test log (artifact logs-simtime-1).
```
zcat log/11163/art/canton_network_test.clog.gz | grep -a -oE '"@timestamp":"[^"]*","message":"Starting test suite [^"]*' | sed -E 's/"@timestamp":"([^"]*)","message":/\1 /' | tail -4
```
```
2026-11-03T13:05:04.355Z "Starting test suite 'TimeBasedTestNetPreviewIntegrationTest'...
2026-11-03T13:08:31.670Z "Starting test suite 'ValidatorLicenseMetadataTimeBasedIntegrationTest'...
2026-11-03T13:09:58.876Z "Starting test suite 'ExternallySignedTxsTimeBasedIntegrationTest'...
2026-11-03T13:10:49.729Z "Starting test suite 'SvTimeBasedRewardCouponIntegrationTest'...
```

## 3. sv2 join steps (test log, SV=sv2)
Proposal, disconnect, snapshot, import refused, init failed; all within 1 s.
```
zcat log/11163/art/canton_network_test.clog.gz | grep -a 'SV=sv2' | grep -aE 'Submitted proposal PartyToParticipant|Disconnecting from all domains|disconnected from global domain|Received Acs snapshot|Imports the acs in the participant. failed|Initialization failed' | python3 -I -c "
import sys,json
for l in sys.stdin:
    r=json.loads(l); print(r['@timestamp'][11:23], r['level'], r['logger_name'].split(':')[0], r['message'][:150].replace(chr(10),' '))
" | sed -E 's/1220[0-9a-f]{60}/../g; s/(1220[0-9a-f]{8})[0-9a-f.]*/\1../g' | grep '^13:11'
```
```
13:11:31.442 INFO o.l.s.e.ParticipantAdminConnection Submitted proposal PartyToParticipant(partyId = DSO-f8fad444-f8fad444::1220bbdbeab6.., participants = Map(PAR::sv1::12202e944d81.. -> Submission, PA
13:11:31.679 INFO o.l.s.s.o.j.JoiningNodeDsoPartyHosting Disconnecting from all domains
13:11:31.735 INFO o.l.s.s.o.j.JoiningNodeDsoPartyHosting candidate SV participant disconnected from global domain
13:11:32.129 INFO o.l.s.s.o.j.JoiningNodeDsoPartyHosting Received Acs snapshot from sponsor, importing into candidate participant
13:11:32.385 WARN o.l.s.e.ParticipantAdminConnection The operation 'Imports the acs in the participant' failed with a non-retryable error, not retrying: category=Some(InvalidIndependentOfSystemState) sta
13:11:32.387 ERROR o.l.s.s.SvApp sv2 app initialization: Initialization failed
```
The same command without the final grep shows the successful join of sv2 at 13:06:40-13:06:41 in
TimeBasedTestNetPreviewIntegrationTest (same flow, import passed).

## 4. Canton side: last indexed event before the import is a topology event
sv2Participant (canton-simtime.clog): the last offset indexed before the disconnect is 856, a
TopologyTransactionEffective (effective 1970-01-02T12:29:06.019802Z, 18 ms before the disconnect at 13:11:31.735 wall
clock); the repair check at RepairServiceHelpers.scala:223 then refuses the import.
```
zcat log/11163/art/canton-simtime.clog.gz | grep -a 'participant=sv2' | grep -a '"2026-11-03T13:11:3[12]' | grep -aE 'Storing at offset=85[3-9]|RepairServiceContractsImporter' | python3 -I -c "
import sys,json
for l in sys.stdin:
    r=json.loads(l); print(r['@timestamp'][11:23], r['logger_name'].split(':')[0].split('.')[-1], r['message'][:120].replace(chr(10),' '))
" | sed -E 's/1220[0-9a-f]{8,}[.]*/1220../g'
```
```
13:11:31.627 ParallelIndexerSubscription Storing at offset=853 SequencerIndexMoved(synchronizerId = global-domain::1220.., sequencerTimestamp = 1970-01-
13:11:31.632 ParallelIndexerSubscription Storing at offset=854 SequencerIndexMoved(synchronizerId = global-domain::1220.., sequencerTimestamp = 1970-01-
13:11:31.697 ParallelIndexerSubscription Storing at offset=855 SequencerIndexMoved(synchronizerId = global-domain::1220.., sequencerTimestamp = 1970-01-
13:11:31.697 ParallelIndexerSubscription Storing at offset=856 TopologyTransactionEffective(   effectiveTime = 1970-01-02T12:29:06.019802Z,   synchronizerId = gl
13:11:32.134 RepairServiceContractsImporter Adding contracts to synchronizer global-domain::1220.. with parameters: Map(workflowIdPrefix -> Some(canton-net
13:11:32.295 RepairServiceContractsImporter IMPORT_ACS_ERROR(8,751432a7): Cannot apply a repair command as the last event is a topology offset. Please reconnect to 
```

## 5. Evidence loss
The test log ends 3 ms after the failure; test reports exist only for the six suites that finished before.
```
zcat log/11163/art/canton_network_test.clog.gz | tail -1 | grep -aoE '"@timestamp":"[^"]*"'
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11163/job.log | grep -aoE 'TEST-org\.lfdecentralizedtrust\.splice\.integration\.tests\.[A-Za-z]+\.xml' | sort -u | sed 's/.*tests\.//'
```
```
"@timestamp":"2026-11-03T13:11:32.390Z"
ExternallySignedTxsTimeBasedIntegrationTest.xml
TimeBasedTestNetPreviewIntegrationTest.xml
TimeBasedTreasuryIntegrationTestWithoutMerging.xml
TrafficBasedRewardsDryRunTimeBasedIntegrationTest.xml
ValidatorLicenseMetadataTimeBasedIntegrationTest.xml
WalletTxLogTimeBasedIntegrationTest.xml
```
Shard suites (split echo): ExternallySignedTxsTimeBased, SvTimeBasedBootstrappingRound, SvTimeBasedRewardCoupon,
TimeBasedTestNetPreview, TimeBasedTreasuryWithoutMerging, TrafficBasedRewardsDryRunTimeBased,
ValidatorLicenseMetadataTimeBased, WalletTxLogTimeBased; SvTimeBasedRewardCoupon and SvTimeBasedBootstrappingRound
have no report.

## Verdict
Duplicate of 10907, family U (third hit: 10907 sv4 2026-10-29, 11107, 11163 sv2 2026-11-03), flake (timing race
in the join flow), plus family H evidence loss through the NodeBase exit. #8202 not implicated (it only changes
SvOffboardingPartyToParticipantTrigger).

Fix location (not written, owner needed):
- splice, `apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/onboarding/joining/JoiningNodeDsoPartyHosting.scala:152`
  (`importPartyAcs` after `disconnectFromAllSynchronizers`): when ImportPartyAcs fails with IMPORT_ACS_ERROR
  "last event is a topology offset", follow the error's own advice: reconnect the synchronizer, wait until the ledger
  end moves past the topology offset, disconnect, and retry the import (the ACS snapshot can be reused). Today
  `ParticipantAdminConnection.importPartyAcs` (apps/common/.../ParticipantAdminConnection.scala:253) gives up on it
  as non-retryable.
- Canton alternative: the repair commands have `force_repair_when_topology_transaction_at_ledger_end`
  (participant_repair_service.proto), but `ImportPartyAcsRequest` (party_management_service.proto:455) does not;
  whether skipping the check is safe for the party-onboarding import is a Canton question.

Not verified: that offset 856 is the DSO PartyToParticipant transaction of sv2 itself (effective time and timing fit:
proposal submitted 13:11:31.442, observed 13:11:31.679); why the successful 13:06 join had a later non-topology event.
