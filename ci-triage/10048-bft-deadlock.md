# 10048 - BFT synchronizer deadlock (run 33911369750)

All commands verified against the downloaded artifacts.

## Setup

```
gh run download 33911369750 --repo canton-network/splice -n logs-wall-clock-time-0 -D wct0
gunzip -k wct0/canton.clog.gz wct0/canton_network_test.clog.gz    # or zcat, if disk is tight
cd wct0
# canton.clog = node-side log (sequencers/mediators); canton_network_test.clog = test harness
```

## 1. Which jobs failed

```
gh run view 33911369750 --repo canton-network/splice --json jobs \
  --jq '.jobs[] | select(.conclusion=="failure") | "\(.databaseId)  \(.name)"'
```
```
101149437720  ci / scala_test_wall_clock_time / wall-clock-time (0)
101149437818  ci / scala_test_wall_clock_time / wall-clock-time (8)
```
Two wall-clock shards failed; the BFT deadlock is shard (0), job 101149437720 (artifact
logs-wall-clock-time-0). Shard 8 is a separate unrelated failure.

## 2. Failing suite in shard 0

```
grep -aoE 'integration\.tests\.[A-Za-z0-9]+IntegrationTest' canton_network_test.clog | sort | uniq -c
```
```
      8 integration.tests.ValidatorIntegrationTest
```
Shard (0) runs ValidatorIntegrationTest, a 4-SV wall-clock topology (sv1..sv4).

## 3. Canton runtime version

```
grep -ao 'WAITING_FOR_EXTERNAL_INPUT_INITIALIZATION, [0-9][^)]*' canton.clog | sort -u
```
```
WAITING_FOR_EXTERNAL_INPUT_INITIALIZATION, 3.5.16
```
The sequencer that actually ran is canton 3.5.16 (not whatever nix/canton-sources.json pins now).

## 4. Epoch 26 starts with sv4 as first leader; strongQuorum == size == 3

```
grep -a 'New epoch 26 has started' canton.clog | grep -a globalSequencerSv1 | head -1 | grep -aoE '"@timestamp":"[^"]+"'
```
```
"@timestamp":"2026-09-04T19:43:32.508Z"
```
```
grep -a 'New epoch 26 has started' canton.clog | grep -a globalSequencerSv1 | head -1 \
  | grep -aoE 'leaders = List\(SEQ::sv4[^,]*, SEQ::sv1[^,]*, SEQ::sv3[^,]*\)' \
  | sed -E 's/(SEQ::sv[0-9])::[0-9a-f]+/\1::.../g'
```
```
leaders = List(SEQ::sv4::..., SEQ::sv1::..., SEQ::sv3::...)and blacklisted nodes = List()
```
```
grep -a 'New epoch 26 has started' canton.clog | grep -a globalSequencerSv1 | head -1 \
  | grep -aoE 'size = [0-9]+|weakQuorum = [0-9]+|strongQuorum = [0-9]+'
```
```
size = 3
weakQuorum = 1
strongQuorum = 3
```
At 19:43:32.508 epoch 26 begins with leaders [sv4, sv1, sv3] and strongQuorum = size = 3, so all three
sequencers must be live for any block to commit; block 820 belongs to sv4.

## 5. sv1 and sv3 immediately see only 2 of 3 and warn ordering may halt

```
grep -a 'below strong quorum size 3' canton.clog \
  | grep -aoE '"@timestamp":"[^"]+","message":"[^"]*below strong quorum size 3[^"]*"' | head -2
```
```
"@timestamp":"2026-09-04T19:43:32.506Z","message":"Authenticated P2P nodes count (including this node) 2 is currently below strong quorum size 3, ordering may not be able to proceed until more nodes are authenticated"
"@timestamp":"2026-09-04T19:43:32.507Z","message":"Authenticated P2P nodes count (including this node) 2 is currently below strong quorum size 3, ordering may not be able to proceed until more nodes are authenticated"
```
Exactly as epoch 26 starts, sv3 and sv1 each see only 2 of 3 sequencers authenticated (sv4 not
connected) and both log that ordering may not proceed.

## 6. Contrast: epoch 25 ran fine (2 nodes, quorum 2)

```
grep -a 'New epoch 25 has started' canton.clog | grep -a globalSequencerSv1 | head -1 \
  | grep -aoE '"@timestamp":"[^"]+"|size = [0-9]+|strongQuorum = [0-9]+'
```
```
"@timestamp":"2026-09-04T19:43:25.003Z"
size = 2
strongQuorum = 2
```
Epoch 25 had 2 leaders (sv3, sv1), both initialized, strongQuorum 2 -> it ordered blocks normally.

## 7. Last block ever ordered is epoch 25 / block 819; epoch 26 orders nothing

```
grep -a 'OrderedBlockStored: DB stored block' canton.clog \
  | grep -aoE 'epochNumber=[0-9]+, blockNumber=[0-9]+' \
  | awk -F'blockNumber=' '{if($2>m){m=$2;line=$0}} END{print line}'
```
```
epochNumber=25, blockNumber=819
```
```
grep -a 'OrderedBlockStored: DB stored block' canton.clog | grep -a 'epochNumber=25, blockNumber=819' \
  | grep -a globalSequencerSv1 | head -1 | grep -aoE '"@timestamp":"[^"]+"'
```
```
"@timestamp":"2026-09-04T19:43:32.470Z"
```
The highest block ever ordered by live consensus is epoch 25 / block 819 at 19:43:32.470; no epoch-26
block (820+) is ordered by anyone.

## 8. sv4 was not initialized when it was named leader

```
grep -a globalSequencerSv4 canton.clog | grep -a '19:43:29.778' \
  | grep -ao 'WAITING_FOR_EXTERNAL_INPUT_INITIALIZATION, 3.5.16' | head -1
```
```
WAITING_FOR_EXTERNAL_INPUT_INITIALIZATION, 3.5.16
```
```
grep -a globalSequencerSv4 canton.clog \
  | grep -aE 'Creating BFT sequencer at block height|Starting Onboarding state transfer from epoch|Completed epoch 24' \
  | grep -aoE '"@timestamp":"[^"]+","message":"(Creating BFT sequencer at block height [^"]*|Starting Onboarding state transfer from epoch [0-9]+|Completed epoch 24[^"]*)"' | head
```
```
"@timestamp":"2026-09-04T19:43:35.202Z","message":"Creating BFT sequencer at block height Some(741)"
"@timestamp":"2026-09-04T19:43:36.187Z","message":"Starting Onboarding state transfer from epoch 24"
"@timestamp":"2026-09-04T19:43:40.260Z","message":"Completed epoch 24 that could alter sequencing topology: last block mode = StateTransfer; ..."
```
sv4 was still NotInitialized at 19:43:29.778 (2.7s before being named leader at 19:43:32.508); it only
creates its BFT sequencer at 19:43:35.2 and starts onboarding state transfer from epoch 24 at
19:43:36.187 - i.e. it begins initializing AFTER it was already made leader.

## 9. sv4 state-transfers only through epoch 25, never enters epoch 26

```
grep -a globalSequencerSv4 canton.clog \
  | grep -aoE 'block transfer response for block Some..epochNumber=[0-9]+' \
  | grep -aoE 'epochNumber=[0-9]+' | sort | uniq -c
```
```
     80 epochNumber=25
```
```
grep -a globalSequencerSv4 canton.clog | grep -a 'no commit certificates for it' | head -1 \
  | grep -aoE '"@timestamp":"[^"]+".*epoch 26 but there are no commit certificates for it' \
  | sed -E 's/SEQ::sv[0-9]::[0-9a-f]+/SEQ::sv3::.../'
```
```
"@timestamp":"2026-09-04T19:43:41.568Z","message":"Got a retransmission request from SEQ::sv3::... for epoch 26 but there are no commit certificates for it
```
```
grep -a globalSequencerSv4 canton.clog | grep -c '"message":"New epoch'
```
```
0
```
```
grep -a globalSequencerSv4 canton.clog | grep -a 'Processing 0 new epoch topology messages' | tail -1 | grep -aoE '"@timestamp":"[^"]+"'
```
```
"@timestamp":"2026-09-04T19:46:56.579Z"
```
sv4's 80 block-transfer responses are all for epoch 25 (up to block 819); it completes epoch 24 and
catches up through epoch 25, but receives zero epoch-26 blocks. sv1/sv3 and sv4 exchange epoch-26 state
requests but no epoch-26 commit certificate exists anywhere, so the transfer yields nothing and sv4 can
never leave onboarding to lead. sv4 never starts any epoch (0 "New epoch" lines) and is still idle at
19:46:56 near end of log - the wedge is permanent.

## 10. Downstream symptoms (consequences, not independent bugs)

```
grep -a 'No traffic state found for member' canton_network_test.clog \
  | grep -aoE 'member (MED|SEQ|PAR)::sv[0-9]' | sort | uniq -c
```
```
     12 member MED::sv3
     88 member MED::sv4
```
The persistent post-wedge "No traffic state found" is for MED::sv4 (88x) - the mediator of the same SV
whose sequencer wedged (the sv3 hits are pre-wedge onboarding, benign).

```
grep -a 'Gave up waiting until Sequencer is added' canton_network_test.clog | head -1 \
  | grep -aoE '"@timestamp":"[^"]+","message":"Gave up waiting[^"]*"' \
  | sed -E 's/(::[0-9a-f]{6,}|(sv[0-9])::[0-9a-f]+)/\2::.../g'
```
```
"@timestamp":"2026-09-04T19:45:26.556Z","message":"Gave up waiting until Sequencer is added to the topology state for MediatorToOnboard(synchronizerId = global-domain::..., mediatorId = MED::sv2::..., sequencerId = SEQ::sv2::...)"
```
MED::sv2 onboarding fails waiting for SEQ::sv2 to appear in topology, because the topology transaction
that would add it can no longer be sequenced (ordering wedged) - downstream effect.

## Summary for Canton team

In ValidatorIntegrationTest, shard wall-clock-time(0) (job 101149437720, canton 3.5.16), BFT epoch 26
began 2026-09-04T19:43:32.508 with leaders [SEQ::sv4, sv1, sv3] and strongQuorum = size = 3, assigning
the epoch's first block (820) to SEQ::sv4. But SEQ::sv4 was still NotInitialized/WAITING_FOR_EXTERNAL_INPUT
at that instant; it only created its BFT sequencer at 19:43:35.2 and started onboarding state transfer at
19:43:36.187, catching up through epoch 25 only (last live block 819 at 19:43:32.470). sv4 never enters
epoch 26 (0 "New epoch" lines, still idle 19:46:56); retransmissions for epoch 26 find "no commit
certificates for it". Because epoch 26 needs all 3 of 3 and its leader never comes online, sv1/sv3 log
they are below strong quorum and ordering halts permanently. Downstream: MED::sv4 "No traffic state found"
(88x), MED::sv2 onboarding "Gave up waiting until Sequencer [SEQ::sv2] is added" (19:45:26). Root cause to
investigate: epoch leader selection names a newly-added sequencer (sv4, active in topology from epoch 24)
as first leader before it has initialized from its onboarding snapshot and joined consensus - unrecoverable
when strongQuorum equals full membership.
