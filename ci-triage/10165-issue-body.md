Paste-ready body for cn-test-failures 10165 (now the surviving issue for this family; 10094 and 10153 were
closed as duplicates of it, 10161 is the same mechanism). ASCII only.

---

Title: BFT sequencer onboarding step activates the new ordering topology before the newcomers are
P2P-authenticated; incumbents lose quorum for 5-25 s and get blacklisted, surfacing as checkErrors WARNs

Seen on main (canton 3.6.0-snapshot.20260910 and .20260916) and release-line-0.7.5/0.8.0 (canton 3.5.15/3.5.16).
All tests pass in every occurrence; the job fails on checkErrors.

Mechanism (same in all runs): initDso onboards sv2..sv4 at once. At the next epoch boundary sv1's sequencer
activates the N-node ordering topology ("New epoch E has started with leaders = [sv1, sv2, sv3, (sv4)]") while
only sv1 is P2P-authenticated ("Authenticated P2P nodes count (including this node) 1 is currently below weak
quorum size 2"). The newcomers authenticate 4-25 s later. Until then the mempool rejects every submission
("P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting"), sv1 cannot make
progress in that epoch and is blacklisted for the next 3 epochs (~40 s). When the last newcomer joins in a
later step (sv4 in 10165), it is the newcomer that gets blacklisted.

Two symptoms, both flagged by checkErrors:

1. Sequencer-client ack WARN (10094 canton 3.5.15, 10153 and 10161 canton 3.6 20260910): a participant's or
   the mediator's periodic `acknowledge-signed` that reaches sv1's public API inside the quorum gap is
   accepted by `BlockSequencer`, rejected by the mempool, and only answered after the client's 120 s deadline
   ("AcknowledgeSigned ... cancelled" then "sending response AcknowledgeSignedResponse()" 150-190 ms later).
   Client side: `GrpcClientGaveUp: DEADLINE_EXCEEDED ... Request: acknowledge-signed/<ts>` and
   `Failed to acknowledge clean timestamp (usually because sequencer is down)`, both WARN.
2. SV app HTTP timeout WARN (10165, canton 3.6 20260916): `POST /api/sv/v0/onboard/sv/sequencer` on sv1 times out
   after the 38 s `pekko.http.server.request-timeout`, because `HttpSvPublicHandler.getSequencerOnboardingState`
   blocks on the new sequencer appearing in `SequencerSynchronizerState` and in the ordering topology, and the
   sequencer-add topology transaction (proposed identically by all SVs within 0.5 s) cannot be ordered during
   the gap (epoch 104 lasted 38 s instead of 7-13 s; the tx was sequenced 50 s after the proposals). The joining
   SV's trigger retries and succeeds ~20 s later.

Runs / jobs: 34477382133 wall-clock (10094), 35113435367 wall-clock-time (0) (10153), 35206251191
wall-clock-time (6) (10161), 35223275137 wall-clock-time (9) (10165). Evidence packets:
ci-triage/10094-sequencer-ack-stall.md, 10153-sequencer-ack-stall-bft-1-to-4.md,
10161-mediator-ack-stall-bft-1-to-4.md, 10165-onboard-sv-sequencer-http-timeout-bft-1-to-3-stall.md.

Canton side (owner): activate a new ordering topology only once a quorum of its members is authenticated,
or do not accept acks the mempool cannot disseminate (answer them once ordering resumes). Not present in
digital-asset/canton main as of the 2026-09-15.22 mirror state (checked 2026-09-17).

Splice side (mitigations, any of): serialise SV sequencer onboardings in the test topologies or gate the
next onboarding on P2P authentication of the previous one; make the onboard/sv/sequencer handler
non-blocking or give it a longer `custom-timeouts` entry in tests; extend the existing
`api/sv/v0/onboard/validator \(POST\) resulted in a timeout` ignore to the sequencer endpoint (same
"callers retry" justification).
