# 10227 - wallet onboarding fails with INVALID_PRESCRIBED_SYNCHRONIZER_ID because a blacklisted BFT sequencer refuses the party's topology broadcast (run 36160174141)

DUPLICATE of the 10165 BFT-onboarding umbrella (family B), new symptom. DistributedDomainIntegrationTest
"SV onboarding on distributed domain" onboards sv2-sv4 onto the distributed global domain; the BFT ordering
topology steps 1 -> 3 -> 4 and the newcomer SEQ::sv3 is blacklisted for epochs 84-86 (16:47:22 to 16:47:53,
31.3 s) because it was activated at the epoch boundary before it had authenticated with its peers. 21 s later
the test onboards alice's wallet user. The party allocation is authorized locally and reaches the splitwell
synchronizer, but the `global` broadcast is handed to SEQ::sv3 by the connection pool, refused with
SEQUENCER_OVERLOADED ("this node is currently blacklisted"), and - unlike an ordinary send, which fails over to
another sequencer in the same millisecond - the topology broadcast path does not retry on another connection.
The outbox therefore re-tries only on its 10 s cycle and picks sv3 again each time. With the party on splitwell
but not on global-domain, `installWalletForUser` submits the WalletAppInstall prescribed to global-domain and is
rejected with INVALID_PRESCRIBED_SYNCHRONIZER_ID. 67 tests pass, 1 fails. The transaction was accepted at
16:48:03.355, 1.74 s after the test gave up at 16:48:01.322.

- Run: https://github.com/canton-network/splice/actions/runs/36160174141, `main` ccc8e26641
  ("Upgrade Canton to 3.6.0-snapshot.20260925.20321.0.vaecbf95c (#7476)"), job 108154882163
  `ci / scala_test_wall_clock_time / wall-clock-time (9)`.
- Runtime canton: 3.6.0-snapshot.20260925.20321.0.vaecbf95c (previous pin on the parent commit:
  3.6.0-snapshot.20260916.20284.0.vf27c4824).
- Component: Canton (BFT ordering blacklisting + sequencer client topology-broadcast path); splice validator
  app contributes the short retry budget. Not a test bug.

All commands below are run from the repo root with the artifact in
`log/10227/logs-wall-clock-time-9/`; long hashes are trimmed by a sed baked into each command.

## 1. Failing assertion

```
sed -E 's/\x1b\[[0-9;]*m//g' log/10227/job.log \
  | grep -a -E 'FAILED \*\*\*|Tests: succeeded|Run completed in' | sed -E 's/^[^Z]*Z //' | sort -u
```
```
[info] *** 1 TEST FAILED ***
[info] - SV onboarding on distributed domain *** FAILED ***
[info] Run completed in 18 minutes, 31 seconds.
[info] Tests: succeeded 67, failed 1, canceled 0, ignored 4, pending 0
```

```
sed -E 's/\x1b\[[0-9;]*m//g' log/10227/job.log | grep -a -A16 'FAILED \*\*\*' \
  | sed -E 's/^[^Z]*Z //' | grep -v 'still running' | head -16
```
```
[info] - SV onboarding on distributed domain *** FAILED ***
[info]   org.scalatest.exceptions.TestFailedException was thrown. (BaseTest.scala:485)
[info]   org.scalatest.exceptions.TestFailedException:
[info]   at org.scalatest.Assertions.fail(Assertions.scala:965)
[info]   at com.digitalasset.canton.BaseTest.$anonfun$eventuallySucceeds$1(BaseTest.scala:485)
[info]   at com.digitalasset.canton.BaseTest$.eventually(BaseTest.scala:637)
[info]   at com.digitalasset.canton.BaseTest.eventuallySucceeds(BaseTest.scala:481)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.DistributedDomainIntegrationTest.$anonfun$new$12(DistributedDomainIntegrationTest.scala:120)
[info]   at com.digitalasset.canton.BaseTest.clue(BaseTest.scala:399)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.DistributedDomainIntegrationTest.$anonfun$new$1(DistributedDomainIntegrationTest.scala:119)
```

The failing statement, at the run's sha:

```
git show ccc8e26641:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/DistributedDomainIntegrationTest.scala \
  | sed -n '116,122p'
```
```
    aliceValidatorBackend.startSync()

    // Check that things work for external validators
    clue("Alice can tap") {
      eventuallySucceeds()(onboardWalletUser(aliceWalletClient, aliceValidatorBackend))
      aliceWalletClient.tap(1000)
    }
```

## 2. Which test was running

```
zcat log/10227/logs-wall-clock-time-9/canton_network_test.clog.gz \
  | grep -a -E 'T16:4[5-8]:' | grep -aE '"(Running|Finished|Failed) clue' \
  | grep -a 'DistributedDomainIntegrationTest' \
  | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-120
```
```
2026-09-25T16:47:27.624Z Running clue: Sequencers are initialized",
2026-09-25T16:47:27.671Z Finished clue: Sequencers are initialized",
2026-09-25T16:47:27.671Z Running clue: SV participants are connected to all sequencers",
2026-09-25T16:47:28.336Z Finished clue: SV participants are connected to all sequencers",
2026-09-25T16:47:28.369Z Running clue: DSO party is bootstrapped as a decentralized namespace with SVs as owners",
2026-09-25T16:47:28.390Z Finished clue: DSO party is bootstrapped as a decentralized namespace with SVs as owners",
2026-09-25T16:47:33.020Z Running clue: Alice can tap",
2026-09-25T16:47:33.020Z Running clue: Onboard alice_wallet_user-50cb7fe2 on aliceValidator",
2026-09-25T16:47:40.692Z Running clue: Onboard alice_wallet_user-50cb7fe2 on aliceValidator",
2026-09-25T16:47:47.903Z Running clue: Onboard alice_wallet_user-50cb7fe2 on aliceValidator",
2026-09-25T16:47:54.635Z Running clue: Onboard alice_wallet_user-50cb7fe2 on aliceValidator",
2026-09-25T16:48:01.322Z Failed clue: Alice can tap",
```

Suite boundaries: started 16:45:56.099, failed 16:48:15.813. The test's own "Sequencers are initialized" and
"SV participants are connected to all sequencers" checks pass at 16:47:27-28, i.e. 5 s AFTER sv3 was already
blacklisted in the ordering layer (section 5). Those checks look at node and connection state, not at the BFT
ordering topology, so the test has no signal that the newcomer cannot accept submissions.

## 3. What the validator returned: INVALID_PRESCRIBED_SYNCHRONIZER_ID, four identical attempts

```
zcat log/10227/logs-wall-clock-time-9/canton_network_test.clog.gz | grep -a -E 'T16:4[78]:' \
  | grep -a 'admin/users' | grep -aE 'Responding with status|Responding with entity' \
  | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | cut -c1-260
```
```
2026-09-25T16:47:40.678Z HTTP POST /api/validator/v0/admin/users from (127.0.0.1:50460): Responding with status code: 400 Bad Request
2026-09-25T16:47:40.678Z HTTP POST /api/validator/v0/admin/users from (127.0.0.1:50460): Responding with entity data: {\n  \"error\" : \"INVALID_PRESCRIBED_SYNCHRONIZER_ID(9,f6ae8377): Not all informees are on the specified synchronizer: global-domain::1220dbe028f8...::36-0, but on Set(splitwell::12204dc041cf...::35-0)\"\n}
2026-09-25T16:47:47.882Z HTTP POST /api/validator/v0/admin/users from (127.0.0.1:45000): Responding with status code: 400 Bad Request
2026-09-25T16:47:47.882Z HTTP POST /api/validator/v0/admin/users from (127.0.0.1:45000): Responding with entity data: {\n  \"error\" : \"INVALID_PRESCRIBED_SYNCHRONIZER_ID(9,a3a24de7): Not all informees are on the specified synchronizer: global-domain::1220dbe028f8...::36-0, but on Set(splitwell::12204dc041cf...::35-0)\"\n}
2026-09-25T16:47:54.634Z HTTP POST /api/validator/v0/admin/users from (127.0.0.1:36104): Responding with status code: 400 Bad Request
2026-09-25T16:48:01.320Z HTTP POST /api/validator/v0/admin/users from (127.0.0.1:36118): Responding with status code: 400 Bad Request
```

The same four attempts succeed for a fresh environment 43 s later in SplitwellIntegrationTest
(`config=dff4ab18`, 16:48:44.284, `200 OK`), so nothing is permanently broken.

## 4. The validator's own retry budget: 12 retries, ~7 s, then 400

Following the first attempt end to end by trace id:

```
zcat log/10227/logs-wall-clock-time-9/canton_network_test.clog.gz \
  | grep -a '7959cf30a0b6fb70d1c0c0261b155607' | grep -avE '"level":"(DEBUG|TRACE)"' \
  | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/ [\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' \
  | cut -c1-200
```
```
2026-09-25T16:47:33.025Z Ensuring that Party alice__wallet__user-50cb7fe2::1220fbe26003... is allocated on PAR::aliceValidator::1220fbe26003...
2026-09-25T16:47:33.038Z Success: Party alice__wallet__user-50cb7fe2::1220fbe26003... is allocated on PAR::aliceValidator::1220fbe26003..., result is ()
2026-09-25T16:47:33.038Z Waiting until Party alice__wallet__user-50cb7fe2::1220fbe26003... is observed on ledger API
2026-09-25T16:47:33.575Z Success: Party alice__wallet__user-50cb7fe2::1220fbe26003... is observed on ledger API
2026-09-25T16:47:33.584Z Success: Grant user rights for user alice_validator_user-50cb7fe2 to act as alice__wallet__user-50cb7fe2::1220fbe26003..., result is ()
2026-09-25T16:47:33.595Z Request (tid:2404646c26ab64b3663b937629d3f511) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:5501: failed with FAILED_PRECONDITION/INVALID_PRESCRIBED_SYNCHRONIZER_ID(9,2404646c): Not all informees are on the specified synchronizer
2026-09-25T16:47:33.595Z The operation 'installWalletForUser' failed with a retryable error (full stack trace omitted):
2026-09-25T16:47:40.673Z The operation 'installWalletForUser' has failed with an exception. Total maximum number of retries 12 exceeded. Giving up.
2026-09-25T16:47:40.674Z Request to http://127.0.0.1:5503/api/validator/v0/admin/users resulted in a gRPC StatusRuntimeException: FAILED_PRECONDITION: INVALID_PRESCRIBED_SYNCHRONIZER_ID(9,f6ae8377): Not all informees are on the specified synchronizer: global-domain::1220dbe028f8...::36-0, but on Set(splitwell::12204dc041cf...::35-0)
```

Two things to note. `Waiting until Party ... is observed on ledger API` returns success at 16:47:33.575: the
party IS visible, because it reached the splitwell synchronizer. That readiness check does not establish that
the party exists on the synchronizer the next command prescribes. And the retry budget (12 retries, 7.1 s)
is an order of magnitude shorter than the 31 s blacklist window.

## 5. Why global-domain did not have the party: SEQ::sv3 blacklisted for three epochs

```
zcat log/10227/logs-wall-clock-time-9/canton.clog.gz | grep -a -E 'New epoch (8[2-8]) has started' \
  | grep -a 'sequencer=globalSequencerSv1' | python3 -c "
import sys,json,re
for l in sys.stdin:
    d=json.loads(l); m=d['message']
    e=re.search(r'New epoch (\d+)',m).group(1)
    b=re.search(r'blacklisted nodes = List\(([^)]*)\)',m).group(1)
    b=re.sub(r'::1220[0-9a-f]{56}([0-9a-f]{4})',r'::..\1',b)
    print(d['@timestamp'], 'epoch',e,'blacklisted=['+b+']')
"
```
```
2026-09-25T16:47:05.036Z epoch 82 blacklisted=[]
2026-09-25T16:47:12.479Z epoch 83 blacklisted=[]
2026-09-25T16:47:22.061Z epoch 84 blacklisted=[SEQ::sv3::..c21b7305]
2026-09-25T16:47:32.424Z epoch 85 blacklisted=[SEQ::sv3::..c21b7305]
2026-09-25T16:47:42.873Z epoch 86 blacklisted=[SEQ::sv3::..c21b7305]
2026-09-25T16:47:53.330Z epoch 87 blacklisted=[]
2026-09-25T16:48:01.636Z epoch 88 blacklisted=[]
```

The family B shape, with the ordering topology stepping 1 -> 3 -> 4 and the newcomer unauthenticated at the
moment it is activated as a leader:

```
zcat log/10227/logs-wall-clock-time-9/canton.clog.gz | grep -a -E 'T16:4[67]:' \
  | grep -aE 'below strong quorum|is now again above strong quorum|New epoch 8[13] has started' \
  | sed -E 's/"logger_name":"([^"]*)".*/ [\1]/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' \
  | cut -c1-190 | head -8
```
```
2026-09-25T16:46:56.586Z Authenticated P2P nodes count (including this node) 1 is currently below strong quorum size 3, ordering may not be able to proceed until more nodes are authenticated [globalSequencerSv1]
2026-09-25T16:46:56.594Z New epoch 81 has started with leaders = List(SEQ::sv1..., SEQ::sv2..., SEQ::sv4...)and blacklisted nodes = List(); ordering topology = OrderingTopology(size = 3, weakQuorum = 1, strongQuorum = 3
2026-09-25T16:47:12.478Z New epoch 83 has started with leaders = List(SEQ::sv4..., SEQ::sv1..., SEQ::sv2..., SEQ::sv3...)and blacklisted nodes = List(); ordering topology = OrderingTopology(size = 4, weakQuorum = 2, strongQuorum = 3
2026-09-25T16:47:16.628Z Authenticated P2P nodes count (including this node) 1 is currently below strong quorum size 3, ordering may not be able to proceed until more nodes are authenticated [globalSequencerSv3]
2026-09-25T16:47:16.769Z Authenticated P2P nodes count (including this node) 3 is now again above strong quorum size 3 [globalSequencerSv3]
```

sv3 is made a leader of epoch 83 at 16:47:12.478 while it still has one authenticated P2P peer at 16:47:16.628;
it fails its segment and is blacklisted from epoch 84 (16:47:22.061) to the end of epoch 86 (16:47:53.330).
This is the 10165 / 10094 / 10153 / 10161 mechanism: Canton activates the new ordering topology at the epoch
boundary regardless of P2P authentication.

## 6. The blacklisted sequencer refuses the topology broadcast, and the broadcast does not fail over

The outbox pushes the same transaction to both synchronizers; splitwell accepts, global is handed to sv3:

```
zcat log/10227/logs-wall-clock-time-9/canton.clog.gz | grep -a -E 'T16:47:33\.0[3-5]' \
  | grep -a 'aliceParticipant' | grep -aiE 'global|OVERLOADED|refused|send' \
  | python3 -c "
import sys,json,re
for l in sys.stdin:
    d=json.loads(l); ln=d.get('logger_name','')
    if 'psid=splitwell' in ln: continue
    m=re.sub(r'1220[0-9a-f]{56}([0-9a-f]{4})',r'..\1',d['message'])
    print(d['@timestamp'],'['+d.get('level','')+']',ln[:52],'|',m[:210].replace('\n',' '))
"
```
```
2026-09-25T16:47:33.037Z [DEBUG] c.d.c.t.StoreBasedSynchronizerOutbox:participant=aliceParti | Attempting to push 1 topology transactions to Synchronizer 'global': List(TxHash(SHA-256:82d3a9706527...))
2026-09-25T16:47:33.038Z [DEBUG] c.d.c.s.c.p.SequencerConnectionPoolImpl:participant=alicePa | [264e4f1f-413c-4d67-adda-e331b46265d4] requesting 1 connection(s) excluding Set() allowing only Set(sv1, sv4, sv2, sv3)
2026-09-25T16:47:33.038Z [DEBUG] c.d.c.s.c.p.SequencerConnectionPoolImpl:participant=alicePa | [264e4f1f-413c-4d67-adda-e331b46265d4] returning Set(sequencer-connection-SEQ::sv3::..c21b7305-0)
2026-09-25T16:47:33.041Z [INFO] c.d.c.s.c.p.GrpcConnection:participant=aliceParticipant/psid | Request failed for server-SEQ::sv3::..c21b7305-0.   GrpcRequestRefusedByServer: ABORTED/SEQUENCER_OVERLOADED(2,4eb8dfef): Mempool received client request but this node is currently blacklisted, rejecting
2026-09-25T16:47:33.041Z [DEBUG] c.d.c.s.c.p.GrpcConnection:participant=aliceParticipant/psid | Retry has not been configured for GrpcRequestRefusedByServer, giving up.
2026-09-25T16:47:33.042Z [DEBUG] c.d.c.s.c.RichSequencerClientImpl:participant=aliceParticipa | Cancelling the pending send as submission failed synchronously with refused error: SEQUENCER_OVERLOADED(2,0): Mempool received client request but this node is currently blacklisted, rejecting
2026-09-25T16:47:33.042Z [INFO] c.d.c.c.s.SequencerBasedRegisterTopologyTransactionHandle:par | Failed broadcasting topology transactions: RequestRefused(SendAsyncErrorGrpc(Request failed for server-SEQ::sv3::..c21b7305-0.
2026-09-25T16:47:33.042Z [DEBUG] c.d.c.t.StoreBasedSynchronizerOutbox:participant=aliceParti | Synchronizer 'global' responded the following for the given topology transactions: List(Failed)
2026-09-25T16:47:33.043Z [WARN] c.d.c.t.StoreBasedSynchronizerOutbox:participant=aliceParti | synchronizer outbox flusher failed The synchronizer Synchronizer 'global' failed the following topology transactions: List(SignedTopologyTransaction(
```

This is the decisive contrast. An ordinary send refused by the same sequencer three seconds earlier is
immediately re-issued on another connection; the topology broadcast is not:

```
zcat log/10227/logs-wall-clock-time-9/canton.clog.gz | grep -a -E 'T16:4[78]:' | grep -a 'aliceParticipant' \
  | grep -aE "Attempting to push 1 topology transactions to Synchronizer 'global'|returning Set\(sequencer-connection|because it is overloaded|Failed broadcasting topology|responded the following" \
  | python3 -c "
import sys,json,re
for l in sys.stdin:
    d=json.loads(l)
    if 'psid=splitwell' in d.get('logger_name',''): continue
    m=re.sub(r'1220[0-9a-f]{56}([0-9a-f]{4})',r'..\1',d['message'])
    print(d['@timestamp'],'|',m[:120].replace('\n',' '))
"
```
```
2026-09-25T16:47:30.039Z | [56050266-d7fc-44d9-9e9e-359687f9d1ec] returning Set(sequencer-connection-SEQ::sv3::..c21b7305-0)
2026-09-25T16:47:30.045Z | Send request with message id 56050266-d7fc-44d9-9e9e-359687f9d1ec was refused by SEQ::sv3::1220c2b0344f... because it is overloaded
2026-09-25T16:47:30.045Z | [56050266-d7fc-44d9-9e9e-359687f9d1ec] returning Set(sequencer-connection-SEQ::sv4::..1f01b4fc-0)
2026-09-25T16:47:33.037Z | Attempting to push 1 topology transactions to Synchronizer 'global': List(TxHash(SHA-256:82d3a9706527...))
2026-09-25T16:47:33.038Z | [264e4f1f-413c-4d67-adda-e331b46265d4] returning Set(sequencer-connection-SEQ::sv3::..c21b7305-0)
2026-09-25T16:47:33.041Z | Send request with message id 264e4f1f-413c-4d67-adda-e331b46265d4 was refused by SEQ::sv3::1220c2b0344f... because it is overloaded
2026-09-25T16:47:33.042Z | Failed broadcasting topology transactions: RequestRefused(   SendAsyncErrorGrpc(     Request failed for server-SEQ::sv3::..c21b730
2026-09-25T16:47:33.042Z | Synchronizer 'global' responded the following for the given topology transactions: List(Failed)
2026-09-25T16:47:33.162Z | Synchronizer 'splitwell' responded the following for the given topology transactions: List(Accepted)
```

Line 3 is the failover on the ordinary send (sv3 refused at .045, sv4 returned at .045). The broadcast at
.038 has no equivalent: it reports `Failed` and waits for the next outbox flush.

## 7. The outbox retries on a 10 s cycle and draws sv3 every time

```
zcat log/10227/logs-wall-clock-time-9/canton.clog.gz | grep -a -E 'T16:47:[3-5][0-9]|T16:48:0[0-9]' \
  | grep -a 'aliceParticipant' \
  | grep -aE "Attempting to push 1 topology transactions to Synchronizer 'global'|Failed broadcasting topology|responded the following" \
  | python3 -c "
import sys,json,re
for l in sys.stdin:
    d=json.loads(l)
    if 'psid=splitwell' in d.get('logger_name',''): continue
    m=re.sub(r'1220[0-9a-f]{56}([0-9a-f]{4})',r'..\1',d['message'])
    print(d['@timestamp'],'|',m[:110].replace('\n',' '))
"
```
```
2026-09-25T16:47:33.037Z | Attempting to push 1 topology transactions to Synchronizer 'global': List(TxHash(SHA-256:82d3a9706527...))
2026-09-25T16:47:33.042Z | Synchronizer 'global' responded the following for the given topology transactions: List(Failed)
2026-09-25T16:47:43.045Z | Attempting to push 1 topology transactions to Synchronizer 'global': List(TxHash(SHA-256:82d3a9706527...))
2026-09-25T16:47:43.049Z | Failed broadcasting topology transactions: RequestRefused(   SendAsyncErrorGrpc(     Request failed for server-SEQ::sv3::..c21b730
2026-09-25T16:47:53.052Z | Attempting to push 1 topology transactions to Synchronizer 'global': List(TxHash(SHA-256:82d3a9706527...))
2026-09-25T16:47:53.056Z | Failed broadcasting topology transactions: RequestRefused(   SendAsyncErrorGrpc(     Request failed for server-SEQ::sv3::..c21b730
2026-09-25T16:48:03.063Z | Attempting to push 1 topology transactions to Synchronizer 'global': List(TxHash(SHA-256:82d3a9706527...))
2026-09-25T16:48:03.355Z | Synchronizer 'global' responded the following for the given topology transactions: List(Accepted)
```

Three refused flushes (16:47:33.037, :43.045, :53.052), each drawing sv3 from the pool. The blacklist is
cleared at the epoch 87 boundary (16:47:53.330), 274 ms after the third flush had already been refused, so
the first flush that can succeed is the fourth, at 16:48:03.063. It is accepted at 16:48:03.355.

The test gave up at 16:48:01.322. The margin is 1.74 s; a fourth outbox cycle would have made the run green.

## 8. Timeline

```
16:45:56.099  DistributedDomainIntegrationTest / "SV onboarding on distributed domain" starts
16:46:56.594  epoch 81: ordering topology 1 -> 3 (sv1, sv2, sv4)
16:47:12.478  epoch 83: ordering topology -> 4, SEQ::sv3 becomes a leader
16:47:16.628  globalSequencerSv3: authenticated P2P peers 1, below strong quorum 3
16:47:22.061  epoch 84: SEQ::sv3 blacklisted
16:47:27.624  test asserts "Sequencers are initialized" (passes; looks at node state, not ordering state)
16:47:33.020  clue "Alice can tap"; party alice__wallet__user-50cb7fe2 authorized locally
16:47:33.042  global broadcast refused by the blacklisted SEQ::sv3; no failover
16:47:33.162  splitwell accepts the PartyToParticipant transaction
16:47:33.595  installWalletForUser -> INVALID_PRESCRIBED_SYNCHRONIZER_ID (12 retries, 7.1 s, then HTTP 400)
16:47:43.049  outbox retry 2 refused (sv3)
16:47:53.056  outbox retry 3 refused (sv3)
16:47:53.330  epoch 87: blacklist cleared
16:48:01.322  test gives up: "Failed clue: Alice can tap"
16:48:03.355  outbox retry 4 accepted on global-domain
16:48:15.813  Test failed
```

## Verdict

Duplicate of the 10165 BFT-onboarding umbrella (family B), fourth distinct symptom after 10094/10153 (ack
DEADLINE_EXCEEDED), 10161 (mediator ack) and 10165 (SV onboard HTTP timeout). Confirming greps of section 5
match the family: ordering topology stepping to 4, the newcomer unauthenticated when activated, and the
newcomer blacklisted for three epochs. The wording differs from the catalogue entry ("below strong quorum
size 3" here, "below weak quorum size 2" in the 10094/10165 occurrences), which is the 4-node rather than
the 3-node shape; the mechanism is the same.

Flake, in that the test lost a race by 1.74 s and the system self-healed. It exposes two real Canton gaps:

1. The known family B gap: a newly onboarded BFT sequencer is activated into the ordering topology at the
   epoch boundary before it has authenticated with its peers, fails its leader segment, and is blacklisted
   for three epochs (31.3 s here). Already filed under 10165; not fixed as of this snapshot.
2. New, and the reason this surfaced as a test failure rather than a WARN: the topology broadcast path
   (`SequencerBasedRegisterTopologyTransactionHandle` / `StoreBasedSynchronizerOutbox`) does not fail over to
   another sequencer connection when the chosen one refuses the send, although `RichSequencerClientImpl` does
   exactly that for ordinary sends (section 6). Because `SequencerConnectionPoolImpl` keeps returning the
   blacklisted node - blacklisting is ordering-layer state, not connection health - every 10 s flush can draw
   it again. Worth raising with Canton separately from the blacklisting itself, since it converts a transient
   single-node condition into a multi-tens-of-seconds stall of all topology dispatch for that participant.

Splice-side contributors, described and NOT written (production code, owner decision):

- `onboardUser` treats "party observed on ledger API" as sufficient before submitting a command prescribed to
  global-domain. The check is satisfied by the splitwell synchronizer. Waiting for the party on the synchronizer
  that the next command prescribes would close the window regardless of the Canton behaviour.
- `installWalletForUser`'s retry budget (12 retries, 7.1 s) is shorter than a single outbox flush cycle (10 s),
  so it cannot survive even one refused broadcast. INVALID_PRESCRIBED_SYNCHRONIZER_ID is already classified
  retryable; only the budget is too small.

No fix branch. A wider `eventuallySucceeds` budget in DistributedDomainIntegrationTest would hide a genuine
31 s topology-dispatch stall, and the conventions rule out treating the timeout as the cause.

NOT verified:

- Whether the missing failover in the topology-broadcast path is new in 3.6.0-snapshot.20260925.20321 or
  predates it. No jar comparison was run against the previous pin
  (3.6.0-snapshot.20260916.20284.0.vf27c4824); this packet's claims all rest on runtime log lines.
- Why sv3 failed its leader segment in epoch 83 beyond the P2P authentication lag shown in section 5; the
  blacklisting criterion itself was not read out of the jar.
- Whether other shards or runs on this snapshot hit the same signature. Only this job was examined.
- Nothing was compiled or run; no Canton node was started.
