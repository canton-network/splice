# 11554 - simtime (0): TrafficBasedRewardsSvAppTimeBased setup, splitwell provider party push rejected at the 1 -> 4 BFT step (run 44165252335)

DUPLICATE of 11255 (family B, rejected-send shape). Post-merge run on main facc9443d3, job `simtime (0)`, Canton
3.7.4-snapshot.20261112.20915.0.v9e248620. Tests: succeeded 0, failed 3, 2 suites aborted. The first suite of the
shard (TrafficBasedRewardsSvAppTimeBasedIntegrationTest) never finished its environment setup: the global BFT
ordering topology went from 1 to 4 sequencers at 20:15:00.758 with one authenticated P2P node, and 181 ms later
sv1's sequencer rejected splitwellParticipant's topology broadcast of the PartyToParticipant mapping for the
splitwell provider party (`P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2)`). The
participant does not retry the send, the provider party is never hosted on the global synchronizer,
splitwellValidator stays in `Set up app instance splitwell` until the 5 min init wait expires. The other four
failures are the setup-failure blast radius of the same family (no topology reset, leaked environment).

- Run: https://github.com/canton-network/splice/actions/runs/44165252335, main facc9443d3 ("Don't let TODO comments
  in Flyway (#8538)"), job 134329952552 `ci / scala_test_sim_time / simtime (0)`.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620.
- Component: Canton BFT ordering (topology activation regardless of P2P authentication); test-side mitigation is
  splice#8363 (open).

Paths below are relative to `log/11554/` (job log `job.log`, artifact `logs-simtime-0/`).

## 1. Failing assertions

Three failed tests and two aborted suites; the first one is an init timeout in the environment setup.

```
sed -E 's/\x1b\[[0-9;]*m//g' job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|Run completed' | sed -E 's/^[^Z]*Z //' | sort -u | cut -c1-400
sed -E 's/\x1b\[[0-9;]*m//g' job.log | grep -a -A5 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | grep -v 'still running' | cut -c1-300 | head -6
```
```
[info] *** 3 TESTS FAILED ***
[info] - Enable, disable of dryRunVersion/mintingVersion take effect at round closure *** FAILED ***
[info] - Externally signed transactions can tolerate a preparation/submission skew larger than ledgerTimeRecordTimeTolerance *** FAILED ***
[info] - initialize a scan app that joins late *** FAILED ***
[info] Run completed in 18 minutes, 56 seconds.
[info] Tests: succeeded 0, failed 3, canceled 0, ignored 0, pending 0

[info] - Enable, disable of dryRunVersion/mintingVersion take effect at round closure *** FAILED ***
[info]   com.digitalasset.canton.integration.EnvironmentSetup$EnvironmentSetupException: Creating environment failed at step Running setup (EnvironmentSetup.scala:235)
[info]   Cause: java.lang.IllegalStateException: Condition never became true within 5 minutes
[info]   at com.digitalasset.canton.console.ConsoleMacros$utils$.retry_until_true(ConsoleMacros.scala:151)
[info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization(SpliceInstanceReference.scala:197)
[info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization$(SpliceInstanceReference.scala:192)
```

## 2. Suite timeline

The first suite fails in `Running setup`; every later suite fails or aborts in setup, teardown or fixture creation.

```
zcat logs-simtime-0/canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '|Finished test suite|Running setup|Creating environment failed|Aborted test suite" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200
```
```
2026-11-17T20:11:52.985Z Starting test suite 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest'...",
2026-11-17T20:11:53.013Z Starting 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest/Enable, disable of dryRunVersion/mintingVersion take effect at round closure'...",
2026-11-17T20:12:38.195Z Starting creating environment: Running setup",
2026-11-17T20:20:30.900Z Failed creating environment: Running setup (EnvironmentSetup.scala:235)",
2026-11-17T20:20:31.200Z Test failed: 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest/Enable, disable of dryRunVersion/mintingVersion take effect at round closure', message: Creating environment fai
2026-11-17T20:20:31.274Z Starting test suite 'WalletTimeBasedIntegrationTest'...",
2026-11-17T20:20:45.081Z Starting creating environment: Running setup",
2026-11-17T20:25:45.156Z Failed creating environment: Running setup (EnvironmentSetup.scala:235)",
2026-11-17T20:25:45.200Z Aborted test suite 'WalletTimeBasedIntegrationTest'.",
2026-11-17T20:25:45.207Z Starting test suite 'ScanWithGradualStartsTimeBasedIntegrationTest'...",
2026-11-17T20:25:45.208Z Starting 'ScanWithGradualStartsTimeBasedIntegrationTest/initialize a scan app that joins late'...",
2026-11-17T20:25:45.906Z Starting creating environment: Running setup",
2026-11-17T20:25:46.012Z Finished creating environment: Running setup",
2026-11-17T20:30:47.837Z Test failed: 'ScanWithGradualStartsTimeBasedIntegrationTest/initialize a scan app that joins late', message: The app state of sv1Scan is currently not accessible. Node doesn't
2026-11-17T20:30:47.845Z Starting test suite 'ExternallySignedTxsTimeBasedIntegrationTest'...",
2026-11-17T20:30:47.846Z Starting 'ExternallySignedTxsTimeBasedIntegrationTest/Externally signed transactions can tolerate a preparation/submission skew larger than ledgerTimeRecordTimeTolerance'...",
2026-11-17T20:30:48.063Z Test failed: 'ExternallySignedTxsTimeBasedIntegrationTest/Externally signed transactions can tolerate a preparation/submission skew larger than ledgerTimeRecordTimeTolerance',
2026-11-17T20:30:48.071Z Starting test suite 'SvTimeBasedBootstrappingRoundIntegrationTest'...",
2026-11-17T20:30:48.284Z Aborted test suite 'SvTimeBasedBootstrappingRoundIntegrationTest'.",
```

## 3. Which node did not initialize

In the first environment (config=80019a54) 14 of 16 apps completed by 20:15:30; splitwellValidator stays in `Set up
app instance splitwell` from 20:14:55.020 and providerSplitwellBackend in `Wait for preferred domain connection`,
both until the setup gives up at 20:20:30.

```
zcat logs-simtime-0/canton_network_test.clog.gz | grep -a 'config=80019a54' | grep -a -E 'app initialization: ' | sed -E 's/\{"@timestamp":"([^"]+)","message":"([^"]{0,200}).*/\1 \2/' > init-80019a54.txt
grep -c 'Initialization complete' init-80019a54.txt
grep -E 'splitwellValidator|providerSplitwell' init-80019a54.txt | grep -E 'Set up app instance|Wait for preferred domain connection|Initialize node failed' | cut -c1-200
```
```
14
2026-11-17T20:14:55.020Z splitwellValidator app initialization: Set up app instance splitwell started
2026-11-17T20:15:01.483Z providerSplitwellBackend app initialization: Wait for preferred domain connection started
2026-11-17T20:20:30.918Z providerSplitwellBackend app initialization: Wait for preferred domain connection failed
2026-11-17T20:20:30.918Z providerSplitwellBackend app initialization: Initialize node failed
2026-11-17T20:20:30.981Z splitwellValidator app initialization: Set up app instance splitwell failed
2026-11-17T20:20:30.981Z splitwellValidator app initialization: Initialize node failed
```

What the validator retries until the timeout: `installWalletForUser`, 62 times, refused because the provider party is
hosted on the splitwell synchronizers only, not on global.

```
zcat logs-simtime-0/canton_network_test.clog.gz | grep -a 'config=80019a54' | grep -a 'T20:1[6-9]' | grep -a -E 'splitwellValidator' | grep -a -o -E "The operation '[^']{0,80}' has failed with an exception" | sort | uniq -c | sort -rn | head -5
zcat logs-simtime-0/canton_network_test.clog.gz | grep -a 'config=80019a54' | grep -a 'validator=splitwellValidator' | grep -a "The operation 'installWalletForUser' failed with" | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/"logger_name".*//' | cut -c1-420 | sed -n '2p;$p'
```
```
     62 The operation 'installWalletForUser' has failed with an exception
      2 The operation 'update ingestion service' has failed with an exception

2026-11-17T20:15:02.719Z The operation 'installWalletForUser' failed with a retryable error (full stack trace omitted):\ncategory=Some(InvalidGivenCurrentSystemStateOther)\nErrorInfoDetail(INVALID_PRESCRIBED_SYNCHRONIZER_ID,HashMap(participant -> splitwellParticipant, physicalSynchronizerId -> global-domain::12200904ba49...::37-0, synchronizersOfAllInformees -> Set(splitwell::122086605556...::36-0, splitwellUpgrade::
2026-11-17T20:20:27.225Z The operation 'installWalletForUser' failed with a retryable error (full stack trace omitted):\ncategory=Some(InvalidGivenCurrentSystemStateOther)\nErrorInfoDetail(INVALID_PRESCRIBED_SYNCHRONIZER_ID,HashMap(participant -> splitwellParticipant, physicalSynchronizerId -> global-domain::12200904ba49...::37-0, synchronizersOfAllInformees -> Set(splitwell::122086605556...::36-0, splitwellUpgrade::
```

## 4. Family B confirming grep: the 1 -> 4 step and the rejected send

globalSequencerSv1 starts the first epoch with size 4 at 20:15:00.758 while only itself is authenticated; the only
`Rejecting submission request by` of the run comes 181 ms later, for splitwellValidator's participant.

```
C=logs-simtime-0/canton-simtime.clog.gz; F='s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g'
zcat $C | grep -a -E 'New epoch [0-9]+ has started with leaders' | grep -a 'sequencer=globalSequencerSv1' | grep -a -v 'size = 1,' | sed -E "$F" | sed -E 's/(New epoch [0-9]+ has started).*(size = [0-9]+).*(strongQuorum = [0-9]+).*/\1 \2 \3/' | cut -c1-120 | head -2
zcat $C | grep -a 'sequencer=globalSequencerSv1' | grep -a -E 'is currently below (weak|strong) quorum' | sed -E "$F" | cut -c1-200 | head -3
zcat $C | grep -a 'Rejecting submission request by' | grep -a 'P2P connectivity' | sed -E "$F" | sed -E 's/ with SequencerDeliverError.*"trace-id":"([0-9a-f]+)".*/ tid=\1/' | cut -c1-160
```
```
2026-11-17T20:15:00.758Z New epoch 12 has started size = 4 strongQuorum = 3
2026-11-17T20:15:22.399Z New epoch 13 has started size = 4 strongQuorum = 3

2026-11-17T20:15:00.743Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, dissemination and ordering may not be able to proceed until more nodes are authent
2026-11-17T20:15:04.777Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, dissemination and ordering may not be able to proceed until more nodes are authent
2026-11-17T20:15:04.806Z Authenticated P2P nodes count (including this node) 2 is currently below strong quorum size 3, ordering may not be able to proceed until more nodes are authenticated","logger_

2026-11-17T20:15:00.939Z Rejecting submission request by Some(PAR::splitwellValidator::122013817314...) tid=6c8f65b2c1f31b55869906d7b5000365
```

(Epochs 0-11 on globalSequencerSv1, 20:13:08.295 to 20:14:53.343, all have `size = 1`.)

## 5. What was rejected, and that it is not retried

The trace id is the participant's topology outbox pushing the PartyToParticipant mapping of the splitwell provider
party to global; the gRPC connection gives up without a retry, and neither the message id nor the transaction hash
appears again in the Canton log.

```
F='s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g'
zcat $C | grep -a 6c8f65b2c1f31b55869906d7b5000365 | sed -E "$F" | cut -c1-330 | grep -E 'Attempting to push|sends request|max sequencing time|MempoolModule|Rejecting submission request by|Retry has not been configured'
zcat $C | grep -a 'participant=splitwellParticipant' | grep -a 'c9df8c3ec704' | grep -a 'Adding StoredTopologyTransaction' | sed -E "$F" | cut -c1-300
zcat $C | grep -a -E '74011338-21ad-48a5-89c1-370f6e99aec6|c9df8c3ec704' | awk -F'"' '$4>"2026-11-17T20:15:01"' | wc -l
```
```
2026-11-17T20:15:00.923Z Attempting to push 1 topology transactions to Synchronizer 'global': List(TxHash(SHA-256:c9df8c3ec704...))",[c.d.c.t.StoreBasedSynchronizerOutbox:participant=splitwellParticipant] DEBUG
2026-11-17T20:15:00.929Z 'PAR::splitwellValidator::122013817314...' sends request with id '74011338-21ad-48a5-89c1-370f6e99aec6' of size 677 bytes with 1 envelopes to ALL=1.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-17T20:15:00.930Z Request to send submission with id 74011338-21ad-48a5-89c1-370f6e99aec6 with max sequencing time 1970-01-01T00:00:20Z from PAR::splitwellValidator::122013817314... to Set(All). ",[c.d.c.s.s.b.BlockSequencer:sequencer=globalSequencerSv1/psid=global-domain::12200904ba49::37-0] DEBUG
2026-11-17T20:15:00.934Z P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting",[c.d.c.s.s.b.b.c.m.m.MempoolModule:sequencer=globalSequencerSv1/psid=global-domain::12200904ba49::37-0] INFO
2026-11-17T20:15:00.939Z Rejecting submission request by Some(PAR::splitwellValidator::122013817314...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-17T20:15:00.944Z Retry has not been configured for GrpcServiceUnavailable, giving up.",[c.d.c.s.c.p.GrpcConnection:participant=splitwellParticipant/synchronizerAlias=Synchronizer 'global'/pool=main/connection=DefaultSequencer-0] DEBUG

2026-11-17T20:15:00.916Z Adding StoredTopologyTransaction(\n  SignedTopologyTransaction(\n    TopologyTransaction(\n      PartyToParticipant(partyId = splitwell__provider-80019a54::122013817314..., participants = PAR::splitwellValidator::122013817314... -> Submission),\n      serial = 1,\n      oper

0
```

The send carried max sequencing time 00:00:20 on the sim clock, which stood at about 00:00:00.18 (estimated
sequencing time in the same trace), so the participant's timeout for it does not fire in wall-clock terms within the
5 min init wait.

## 6. Blast radius (suites 2-5)

No decentralized namespace reset ran after the setup failure (the plugins' beforeEnvironmentDestroyed is skipped
when `Running setup` fails), and the first environment keeps logging after its failure.

```
zcat logs-simtime-0/canton_network_test.clog.gz | grep -a -c 'has been reset'
zcat logs-simtime-0/canton_network_test.clog.gz | grep -a 'config=80019a54' | awk -F'"' '$4>"2026-11-17T20:21"' | wc -l
for c in a374345a 394f556a; do zcat logs-simtime-0/canton_network_test.clog.gz | grep -a "config=$c" | grep -a -E '"message":"sv1 app initialization: ' | sed -E 's/\{"@timestamp":"([^"]+)","message":"([^"]{0,160}).*/\1 \2/' | grep 'bootstrapping Dso'; done
```
```
0
7
2026-11-17T20:20:49.255Z sv1 app initialization: SV1Initializer bootstrapping Dso started
2026-11-17T20:25:45.177Z sv1 app initialization: SV1Initializer bootstrapping Dso failed
2026-11-17T20:25:46.905Z sv1 app initialization: SV1Initializer bootstrapping Dso started
```

- WalletTimeBasedIntegrationTest (config=a374345a): sv1 never leaves `SV1Initializer bootstrapping Dso` (from 20:20:49.255), 5 min
  init wait, suite aborted 20:25:45.
- ScanWithGradualStartsTimeBasedIntegrationTest (config=394f556a): sv1 again open in `SV1Initializer bootstrapping
  Dso` from 20:25:46.905; the test fails and the teardown throws `The app state of sv1Scan is currently not
  accessible. Node doesn't have any app state.` from UpdateHistorySanityCheckPlugin (the 11347 throw of family H),
  which skips `environment.close()`.
- ExternallySignedTxsTimeBasedIntegrationTest and SvTimeBasedBootstrappingRoundIntegrationTest: `Creating fixture`
  fails with `Could not create Prometheus HTTP server` / `java.net.BindException: Address already in use` (leaked
  environment).

Not verified: the exact rejection that keeps sv1 in `bootstrapping Dso` in suites 2 and 3 (the catalogue's
`Not fully authorized` text was not searched for at the Canton level).

## Verdict

Duplicate of 11255 (family B, rejected send at the 1 -> 4 step; same suite, node and end state as 11398 and 11412,
here with the topology push of the splitwell provider party instead of a transaction). 11255 is open, assigned to
rautenrieth-da, and not closed as a duplicate. Flake in the sense of a race, real Canton behaviour: the new ordering
topology is activated at the epoch boundary before the newcomers are P2P-authenticated, and a rejected send is not
retried. Fix location: Canton BFT; test-side mitigation splice#8363 ("Onboard SV sequencers one at a time in tests")
is still open on 2026-11-17 and is not in facc9443d3; splice#8356 (blacklisting off, merged 2026-11-10) does not
cover this shape. No fix branch. Fifth failed simtime shard of this family on 2026-11-17 (11534, 11535, 11540,
11543 before it).

Proposed action (not run): close 11554 as a duplicate of 11255.

```
direnv exec . .claude/skills/ci-triage/scripts/close_duplicates.py 11255 11554 --comment 'Duplicate of 11255: simtime (0), TrafficBasedRewardsSvAppTimeBased setup on main facc9443d3 (Canton 3.7.4-snapshot.20261112). The global BFT ordering topology went 1 to 4 at 20:15:00.758 and 181 ms later the sv1 sequencer rejected the splitwellValidator participant topology broadcast of the splitwell provider party (P2P connectivity is not ready, authenticated = 1 below dissemination quorum = 2). The send is not retried, the validator stays in Set up app instance splitwell until the 5 min init wait ends, and the four later suites fail from the missing topology reset and the leaked environment.'
```
