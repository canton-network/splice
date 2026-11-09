Recurring on main: both post-merge runs today failed in this window (#11287, #11288, closed as duplicates). Since 2026-11-06 that is at least 5 of the 13 post-merge main runs (#11241/#11242, this issue, #11258, #11287, #11288; 3 other failed runs not checked), plus the mainnet-Daml nightly on 11-07 and 11-09. Still unassigned, and canton-network/splice#8356 is not merged.

#11287 (simtime (3), main 9dafafb): the lost-verdict shape. SvTimeBasedRoundMgmtIntegrationTest timed out waiting for sv3Validator, whose installWalletForUser submission stayed in flight. Its confirmation request needs 2 verdicts from the 4 active mediators: sv3 sent, sv4 was refused by its blacklisted sequencer, sv2 subscribed only from 00:00:00.343002Z, and sv1 delayed its send by 3 s on the sim clock and sent only after the test had failed:

```
10:02:03.200 DEBUG mediator=globalMediatorSv1 | Delaying sending of verdict by 3000 ms
10:02:03.566 DEBUG mediator=globalMediatorSv3 | Sent result for request 1970-01-01T00:00:00.257Z
10:02:06.023 ERROR mediator=globalMediatorSv4 | Failed to send result to sequencer for request 1970-01-01T00:00:00.257Z RequestRefused( SendAsyncErrorGrpc( ... ABORTED/SEQUENCER_OVERLOADED(2,800a817a): Mempoo...
10:07:46.991 DEBUG mediator=globalMediatorSv1 | Sent result for request 1970-01-01T00:00:00.257Z
```

#11288 (simtime (4), main bd9953a): the rejected-send shape. At the switch from 1 to 4 sequencers (11:39:58.737) the proposal adding MED::sv4 was rejected for all four SV participants with `P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2)`, the outboxes stayed in that flush cycle, and sv4Validator gave up waiting for `sv4_validator_user` at 11:44:21.

The ordering topology in both runs fits the pattern seen so far: in #11287 sv3 joined alone (1 to 2) and nobody was blacklisted, then sv2 and sv4 joined together (2 to 4) and sv4 was blacklisted for the next three epochs; in #11288 sv2, sv3 and sv4 joined in one step and sv4 was blacklisted for the next three epochs.
