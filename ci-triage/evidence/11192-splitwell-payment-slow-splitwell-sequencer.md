# 11192 - SplitwellFrontendIntegrationTest settle debts: payment accept took 21.5 s (run 41896689021)

new. main 8e9e279feae2 (contains #8227, family AE fix), job 126808631728 `frontend-wall-clock-time (1)` (attempt 1).
Slow splitwell synchronizer: the payment needs its AppPaymentRequest unassigned from splitwell to global first; slow
Postgres writes on the splitwell sequencer made the unassignment take 13 s, and the accept 21.5 s, longer than the
20 s the test waits for the redirect back to Splitwell.

- Run: https://github.com/canton-network/splice/actions/runs/41896689021, main 8e9e279feae2, job 126808631728.
- Component: test timeout vs. Canton reference sequencer on the splitwell synchronizer.

## 1. Failing assertion
```
[info] - should settle debts with multiple parties *** FAILED ***
[info]   [frontend=bobSplitwell step=settle-debts-and-verify] The partial function passed as the second parameter to inside was not defined at the value passed as the first parameter to inside, which was: List() (SplitwellFrontendIntegrationTest.scala:131)
```
Line 131: after `payment-accept`, the balances rows on Splitwell. Screenshot 19:42:03.956: bob still on the wallet
confirm-payment page, "Processing payment...", button disabled.

## 2. Accept request (canton_network_test.clog.gz)
```
19:41:43.583 HttpRequestLogger:validator=bobValidator | HTTP POST /api/validator/v0/wallet/app-payment-requests/008f5fed3f14.../accept: received
19:41:43.658 bobValidator | SubmitAndWaitForTransaction sending request (command fca86e87-e91c-4967-a8a4-570a4bfca821)
19:42:05.153 HttpRequestLogger:validator=bobValidator | HTTP POST .../accept: Responding
```

## 3. Where the 21.5 s went (canton.clog.gz, bobParticipant, command fca86e87)
```
19:41:43.705 CantonSyncService | Received submit-transaction fca86e87-...
19:41:43.712 ConnectedSynchronizer psid=splitwell | Submitting unassignment of List(ContractId(008f5fed3f14...
19:41:56.902 UnassignmentProcessor psid=splitwell | About to wrap up request RequestId(2026-11-04T19:41:43.727772Z)
19:42:01.576 AssignmentProcessor psid=global-domain | About to wrap up request RequestId(2026-11-04T19:41:57.043035Z)
19:42:01.617 TransactionProcessor | Phase 1 completed: Submitting 6 envelopes for Transaction request, ... command-id fca86e87-...
19:42:04.335 TransactionProcessor | About to wrap up request RequestId(2026-11-04T19:42:01.626366Z)
```
Unassignment 13.2 s, assignment 4.5 s, transaction 2.7 s.

## 4. Unassignment: each splitwell sequencer hop delivered 4-5 s late
```
19:41:45.635 UnassignmentProcessor participant=bobParticipant | Phase 4: Sending ... approved=1
19:41:46.081 splitwellSequencer BlockSequencerStateManager | Processing block 1533 with 3 block events. First timestamp in block: 2026-11-04T19:41:45.639584Z
19:41:46.419 splitwellSequencer DbReferenceBlockOrderingStore | Storing an ordered request with UUID: aa195f1e-...
19:41:49.804 splitwellSequencer ReferenceSequencerDriver | Stored batch of requests
19:41:50.855 splitwellSequencer DbSequencerStore | Reading events for member MED::splitwellValidator::12208d1ef9a8... from timestamp (exclusive) Some(2026-11-04T19:41:43.727772Z)
19:41:50.923 splitwellMediator | Phase 5: Received 1 response(s) for request=2026-11-04T19:41:43.727772Z.
19:41:51.668 splitwellMediator | Phase 6: Finalized request=RequestId(2026-11-04T19:41:43.727772Z) with verdict Approve
19:41:55.887 splitwellMediator | Received a message from the sequencer.
19:41:55.892 splitwellMediator DefaultVerdictSender | Sent result for request 2026-11-04T19:41:43.727772Z
```
A 3.4 s reference-driver insert (46.419 to 49.804) and a 4 s gap before the verdict was read back. Not family L
(`ref_seq_contention.py` on canton.clog.gz: 0 failed inserts; it covers the global sequencers only).

## Verdict
New, rare (first hit of this mechanism; earlier Splitwell settle-debts failures 10156 and 11161/11172 failed at login).
Fix options (test only): wait longer than 20 s for the redirect after `payment-accept` (the payment can need a
reassignment across two synchronizers). Not verified: why the splitwell sequencer's Postgres writes took seconds
(runner load or the shared Postgres; no DB metrics in the artifact).
