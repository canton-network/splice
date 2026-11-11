# 11255: the three shapes explained

For readers who know little of the DA BFT internals. Code references and what was verified are in `README.md`.

All three shapes happen in the same few seconds: right after the ordering layer switches from 1 sequencer to 4,
while the three new sequencers are not yet connected. They differ in which stage of the BFT pipeline loses the
message.

## Background

**The pipeline.** A submission to a DA BFT sequencer passes through four stages:

1. **Mempool:** accepts or rejects the submission from the client (participant or mediator).
2. **Availability:** groups submissions into a batch, sends the batch to the other sequencers, and collects their
   acknowledgements. Only a batch with enough acks may be proposed for ordering.
3. **Consensus:** orders batches into blocks, using PBFT.
4. **Output:** turns ordered blocks into the sequenced events that participants and mediators read.

**Epochs, segments, leaders.** Time is divided into epochs. Within an epoch each sequencer is the leader of one
segment, a fixed set of block slots that only it proposes for. The epoch ends only when every segment is complete.
If a leader does not fill its slots, the others must run a view change (after a timeout) to take over, and a leader
that needed one is blacklisted for the following epochs. A blacklisted sequencer leads no segment and its mempool
rejects client submissions.

**Membership changes only at epoch boundaries.** When a topology transaction adds sequencers, the next epoch starts
with the new set. Nothing checks whether the new nodes are running or connected.

**Quorums** depend on the number of nodes in the topology, not on how many are up:

| Nodes | Acks needed for a batch | Nodes needed for consensus |
|---|---|---|
| 1 | 1 | 1 |
| 2 | 1 | 2 |
| 3 | 1 | 3 |
| 4 | 2 | 3 |

A node's own ack counts, so up to 3 nodes a sequencer can make its own batches available alone. At 4 it needs one
peer.

**Why the new nodes are late.** A new sequencer can only be initialized from a snapshot taken after it is in the
topology. So the order is always: the epoch with 4 nodes starts, then sv2 to sv4 download their onboarding state,
start, connect and catch up. In between, sv1 is alone in a 4-node topology. It cannot get the second ack for a
batch and cannot reach the 3 nodes consensus needs.

## Shape 1: rejected send (mempool)

- Since sv1 knows it has no connected peer, its mempool refuses new submissions:
  `P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2)`. This is intended; the client is
  supposed to retry elsewhere.
- The retry does not happen. The error reaches the client as "unavailable", which the sequencer client treats as
  "maybe it was sequenced anyway" and swallows. The sender then waits for the message's max sequencing time before
  giving up.
- In 11255 the affected message was the topology broadcast adding sv4's mediator. Each SV participant's topology
  outbox handles one broadcast at a time, so all four outboxes blocked, sv4 never finished onboarding, and its
  validator gave up.

## Shape 2: batch never ordered (availability)

- Here the submission arrived a few hundred milliseconds before the switch, so the mempool accepted it and put it
  into a batch.
- Before that batch was ordered, the epoch ended. Under the new topology the batch needs 2 acks. sv1 sends it once
  to the three new peers, which are not connected, and by default never re-sends it.
- In one variant (11326) the batch had already been proposed, and the proposal was dropped because its epoch had
  closed (`received message after closing`).
- The client got a success response, so it also waits for the max sequencing time.
- Why the batch is not picked up once the peers connect is not verified; the packet for 11255 lists it as open.

## Shape 3: lost verdict (blacklisting plus mediator delay)

- This one happens slightly later, when the new nodes are up but one of them was slow. Its segment needed a view
  change, so it is blacklisted for the next epochs. In the logs it is usually sv4.
- A transaction needs verdicts from 2 mediators. Each mediator sends through its own SV's sequencer. sv4's mediator
  sends its verdict to sv4's sequencer, which refuses it because it is blacklisted.
- To avoid every mediator sending every verdict, Canton lets only some send immediately; the others wait 3 s as
  backup. That wait runs on the node clock.
- Result: one verdict arrives, one is refused, the backup is waiting. The transaction never completes, and the
  participants involved stop making progress.
- splice#8356 removed the blacklisting in simtime tests, so this shape should be gone there. Post-merge runs were
  not checked.

## Why this is fatal only under simulated time

Every one of these waits ends at a deadline: max sequencing time for shapes 1 and 2, the 3 s delay and the decision
time for shape 3. Canton measures those deadlines against sequenced time or the node clock.

- **Wall clock:** the deadline passes after 20 to 60 seconds, the sender retries, and by then the peers are
  connected. The result is a slow setup, a circuit-breaker warning or a client timeout.
- **Simulated time:** the clock only moves when a test advances it, and no test code runs during environment setup.
  The deadline never arrives, nothing is retried, and app initialization fails after several minutes.
