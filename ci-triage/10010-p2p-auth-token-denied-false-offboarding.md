# 10010 - sv3's P2P auth token fetch denied by sv4 for 12 s: sv4 was stuck on a stale topology after falsely concluding it was off-boarded (run 33785420105)

Same run has three failed wall-clock shards: (8) and (9) are the sv2 onboarding wedge already in the
2026-09-10 off-boarding sweep (`ray/ci-triage-svonboarding-bft`, 4b7534bbee; 10048 family). This packet is
the third one, wall-clock-time (1), job 100749318272, which the tracker triaged as "Log flake ... expected when
a topology change is in-flight, we can suppress the log". The artifact says otherwise: it is the 10048
off-boarding bug on sv4, seen from sv3's side.

- Run: https://github.com/canton-network/splice/actions/runs/33785420105, main 3063ad675b ("upgrade canton to
  3.5.16 (#7110)"), 2026-09-03. Runtime canton 3.5.16.
- All 35 tests pass; `checkErrors` flags one WARN in `canton_before_shutdown.clog`:
```
17:48:37.834Z WARN P2PAddAuthTokenHeaderGrpcServerInterceptor$P2PAuthenticatorServerCall:sequencer=globalSequencerSv3
  Failed to fetch P2P server authentication token to P2PUrl("http://localhost:5410"): Status{code=PERMISSION_DENIED,
  description=Member SEQ::sv3::1220c57ffffe... access is disabled}
```
Port 5410 is globalSequencerSv4's P2P endpoint; sv4 rejected sv3's authentication challenges 53 times between
17:48:27.683 and 17:48:39.561 (`CLIENT_AUTHENTICATION_REJECTED`, `MemberAccessDisabled(SEQ::sv3)`).

## 1. Test running: SvStateManagementIntegrationTest initDso (17:46:28 start; first test passes 17:48:55)

## 2. sv3 was an active sequencer 21 s before sv4 started rejecting it

SequencerSynchronizerState as sequenced (globalSequencerSv1 view):
```
zcat log/10010/logs-wall-clock-time-1/canton_before_shutdown.clog.gz | grep -a 'sequencer=globalSequencerSv1' | grep -a 'Persisted topology transactions' | grep -a SequencerSynchronizerState | grep -a -oE 'EffectiveTime\([^)]+\)|serial = [0-9]+|active = Seq\([^)]*\)'
```
```
serial 2  effective 17:48:05.276  active = [sv1, sv4]
serial 3  effective 17:48:06.376  active = [sv1, sv4, sv3]
serial 4  effective 17:48:10.702  active = [sv1, sv4, sv3, sv2]
```
So from 17:48:06.4 sv3 is active for everyone who has processed that transaction. The rejections start at
17:48:27.7, i.e. no topology change for sv3 was "in flight" any more.

## 3. sv4 did not have serial 3 because it had stopped processing blocks: the 10048 off-boarding conclusion

```
zcat ...clog.gz | grep -a 'sequencer=globalSequencerSv4' | grep -a -v DEBUG | grep -a -E "Initialized the block sequencer|isn't part of it|Switching to catch-up|Completed state transfer" ; \
zcat ...clog.gz | grep -a 'sequencer=globalSequencerSv4' | grep -a 'Persisted topology transactions' | grep -a SequencerSynchronizerState | grep -a -oE '"@timestamp":"[^"]+"|serial = [0-9]+' | paste - -
```
```
17:48:19.366Z Initialized the block sequencer with head block BlockInfo(250, 2026-09-03T17:48:05.836431Z, ...)    (snapshot predates serial 3)
17:48:19.309Z persisted serial = 2 (from the onboarding snapshot)
17:48:20.476Z Starting Onboarding state transfer from epoch 12
17:48:24.628Z Received topology for epoch 13, but this node isn't part of it (i.e., it has been off-boarded): not starting consensus as this node is going to be shut down and decommissioned
17:48:27.683 - 17:48:39.561  53 x CLIENT_AUTHENTICATION_REJECTED for SEQ::sv3 (sv4 still at block 250: sv3 not in its SequencerSynchronizerState)
17:48:39.807Z Switching to catch-up state transfer (up to at least Some(14)) while in epoch 13; latestCompletedEpoch is 12 and message epoch is 15
17:48:39.988Z persisted serial = 3 (sv3 added)                                                                   (first time sv4 learns of sv3)
17:48:40.292Z Completed state transfer, new epoch is 14, completing init
```
sv4's onboarding snapshot (head block 250 at 17:48:05.84) did not yet contain serial 3 (17:48:06.4). Catching up
would normally take a second; instead sv4 hit the state-transfer bug from 10048 at 17:48:24.6 (empty response for
epoch 13 read as "I was off-boarded"), stopped consensus, and sat on block 250 for 15 s. During that window it
judged every sv3 challenge against a topology in which sv3 did not exist yet. sv1's retransmission requests for
epoch 14 (17:48:34-38) finally pushed it into catch-up state transfer at 17:48:39.8, it persisted serial 3 at
17:48:39.99, and sv3 authenticated. The same run's shards (8) and (9) hit the same bug on sv2 and never recovered.

## 4. Verdict

Not a benign "topology change in flight" and not a candidate for a log-ignore: the WARN is the P2P-side symptom
of a node that wrongly stopped processing after the false off-boarding conclusion (10048 family). Fixed by
DACH-NY/canton#35600 (3.5.17; main since the 20260910 snapshot, see 10048 packet section "Canton response and
version check"); release-line-0.8.0 is no longer supported (Raymond, 2026-09-18), so every supported line (main, 0.8.x on 3.5.18,
0.8.1 on 3.5.17) has the fix. Close 10010 (and its duplicates 10020, 10055, and splice #6986 if that is the same
line) against the #35600 bump rather than adding an ignore;
an ignore would have hidden the only WARN that surfaced the bug on shards where the wedge did not happen.
