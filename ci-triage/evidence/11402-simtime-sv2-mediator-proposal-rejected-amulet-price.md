# 11402 - simtime (1): MediatorSynchronizerState for MED::sv2 rejected for all four SVs at the 1 -> 4 switch, sv2Validator exits (run 43303565707)

DUPLICATE of 11255 (rejected-send shape). Main f1e385ffac, job simtime (1), Canton 3.7.3. In the setup of
SvTimeBasedAmuletPriceIntegrationTest the global ordering topology goes to 4 nodes at 21:14:06.736; within 0.23 s
sv1's sequencer rejects the pushes of the MediatorSynchronizerState proposal that adds MED::sv2 from all four SV
participants (`P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2)`). sv2's SV app waits
on `Mediator has been granted unlimited traffic` (`No traffic state found for member MED::sv2`), never creates
sv2_validator_user, and the JVM exits at 21:19:41 when sv2Validator gives up `Get user` (7 min of
`PERMISSION_DENIED`), so the job has no ScalaTest report (family H). Not caused by #8425 (ignore pattern only).

- Run: https://github.com/canton-network/splice/actions/runs/43303565707, main f1e385ffac ("Ignore BFT orderer
  wording of the 'Sequencer is unhealthy' WARN in checkErrors (#8425)"), job 131481218295 `ci / scala_test_sim_time /
  simtime (1)`, attempt 1.
- Runtime canton: 3.7.3 (`nix/canton-sources.json` at f1e385ffac).
- Component: Canton BFT ordering under simulated time.

## 1. Job: no report, the last suite is SvTimeBasedAmuletPriceIntegrationTest

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11402/job.txt | grep -a -E 'FAILED \*\*\*|Tests: succeeded|contains problems|Run completed|##\[error\]|still running|Attempt 1' | sed -E 's/^[^Z]*Z //' | tail -4
```
```
[info] *** Test still running after 7 minutes, 46 seconds: suite name: SvTimeBasedAmuletPriceIntegrationTest, test name: amulet price votes and metrics.
Attempt 1 failed with exit code 1, retrying
##[error]Error: failed to run script step (id baae1150-dedb-11f1-b31f-e1a91a9b06ef): Error: step failed with return code 1
##[error]Process completed with exit code 1.
```

## 2. The 1 -> 4 switch and the rejected pushes

Artifact `logs-simtime-1` in `log/11402/logs-simtime-1/`.
```
zcat canton-simtime.clog.gz | grep -a -E "New epoch [0-9]+ .*size = |Rejecting submission request by .*P2P connectivity" | grep -a 'T21:1[3-4]' | <first line per distinct message>
```
```
2026-11-12T21:13:59.968Z New epoch 99 has started with leaders = List(SEQ::sv1::1220cb99fffa...) ... size = 1
2026-11-12T21:14:06.736Z New epoch 100 has started with leaders = List(SEQ::sv1::..., SEQ::sv2::...
2026-11-12T21:14:06.788Z Rejecting submission request by Some(PAR::sv2::1220c7ce3f17...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated...
2026-11-12T21:14:06.795Z Rejecting submission request by Some(PAR::sv3::1220551be7e2...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated...
2026-11-12T21:14:06.837Z Rejecting submission request by Some(PAR::sv1::122097de1beb...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated...
2026-11-12T21:14:06.962Z Rejecting submission request by Some(PAR::sv4::122027431d03...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated...
```
Outbox pushes around the switch and their mappings:
```
zcat canton-simtime.clog.gz | grep -a 'T21:14:06' | grep -a 'QueueBasedSynchronizerOutbox' | grep -a 'Attempting to push' | sed -E '<time participant txhash>'
# per line: zcat canton-simtime.clog.gz | grep -a "participant=${p}Participant" | grep -a "$h" | grep -a -oE 'TopologyTransaction\(\\n *[A-Z][A-Za-z]+\(' | grep -v Signed | sort -u | head -1
zcat canton-simtime.clog.gz | grep -a 'c114b713e7f9' | grep -a -oE 'active = [^)]{0,120}' | sort -u | head -1
```
```
2026-11-12T21:14:06.007Z sv1 14fb8188f7f9 SequencerSynchronizerState
2026-11-12T21:14:06.075Z sv4 6d7eff2f8345 MediatorSynchronizerState
2026-11-12T21:14:06.078Z sv3 6d7eff2f8345 MediatorSynchronizerState
2026-11-12T21:14:06.368Z sv2 14fb8188f7f9 SequencerSynchronizerState
2026-11-12T21:14:06.489Z sv3 14fb8188f7f9 SequencerSynchronizerState
2026-11-12T21:14:06.765Z sv2 c114b713e7f9 MediatorSynchronizerState
2026-11-12T21:14:06.787Z sv3 c114b713e7f9 MediatorSynchronizerState
2026-11-12T21:14:06.817Z sv1 c114b713e7f9 MediatorSynchronizerState
2026-11-12T21:14:06.952Z sv4 c114b713e7f9 MediatorSynchronizerState
active = Seq(MED::sv1::1220693c16fc..., MED::sv3::12201b482883..., MED::sv4::122007876cca..., MED::sv2::1220b5c5a135...
```
All four pushes of c114b713e7f9 (the MED::sv2 add) are sent after the switch at 21:14:06.736 and match the four
rejections.

## 3. sv2 waits on its mediator, sv2_validator_user never created

```
zcat canton-simtime.clog.gz | grep -a -c 'Failed to send result'
zcat canton_network_test.clog.gz | grep -a -E "Got 'user sv[0-9]_validator_user-a9cf3b40"
zcat canton_network_test.clog.gz | awk '/T21:19:(3[0-9]|4[01])/' | grep -a -E 'No traffic state found|Gave up waiting until Mediator|Wait for user failed'
```
```
0
2026-11-12T21:12:50.839Z Got 'user sv1_validator_user-a9cf3b40': ()
2026-11-12T21:14:25.606Z Got 'user sv3_validator_user-a9cf3b40': ()
2026-11-12T21:14:31.442Z Got 'user sv4_validator_user-a9cf3b40': ()
2026-11-12T21:19:30.237Z Retrying on same error kind transient error (request infinite retries) for StatusRuntimeException/NOT_FOUND: No traffic state found for member MED::sv2::1220b5c5a135... | LocalSynchronizerNode:.../SV=sv2
2026-11-12T21:19:41.622Z Gave up waiting until Mediator has been granted unlimited traffic, as we are shutting down | LocalSynchronizerNode:.../SV=sv2
2026-11-12T21:19:41.650Z sv2Validator app initialization: Wait for user failed | ValidatorApp:.../validator=sv2Validator
```
The JVM exit stack is not in the job log (only `Attempt 1 failed with exit code 1`); the shutdown starts at
21:19:41.616, 7 min after sv2Validator's first `Get user` at 21:12:48.

## Verdict

Duplicate of 11255, rejected-send shape: the MED::sv2 proposal pushes of all four SVs are rejected within 0.23 s of
the 1 -> 4 switch, zero `Failed to send result`, sv2 mediator never onboarded.
