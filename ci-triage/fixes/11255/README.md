# 11255: fix options

Findings and implementation plans for DACH-NY/cn-test-failures#11255 (submissions lost while the DA BFT ordering
topology grows from 1 to 4 sequencers; fatal under simulated time).

Written 2026-11-11 from reading splice main at `d356bcf573` (Canton `3.7.3-snapshot.20261111`), the Canton sources
under `canton/`, and the packets in `../../evidence/`. Nothing here was run or tested. Line numbers refer to
`d356bcf573` and will drift; the plans name symbols as well.

| File | Content |
|---|---|
| `README.md` | this file: cause, facts from the code, options, order |
| `option-1-canton-test-config.md` | plan: Canton test config (mediator verdict delay, batch re-send) |
| `option-2-sv-app-sequential-sequencer-onboarding.md` | plan: SV app adds sequencers one at a time |
| `option-3-simtime-setup-watchdog.md` | plan: advance the sim clock when setup is stuck |
| `option-4-bft-sequencing-parameters.md` | plan: DA BFT sequencing parameters in tests |

Options 5 and 6 have no plan file; see below.

## Cause

Three shapes, three causes. splice#8356 (merged, blacklisting off, simtime only) touches one.

| Shape | Cause in Canton | Covered by #8356 |
|---|---|---|
| Rejected send | With 4 sequencers the mempool rejects unless 2 nodes (itself included) are authenticated. The rejection is `SEQUENCER_UNAVAILABLE`, which the sequencer client treats as "possibly sequenced", so the send stays pending until its max sequencing time. | no |
| Batch never ordered | After the switch a batch needs 2 acks. Re-sending a batch to peers is off by default. | no |
| Lost verdict | One mediator's sequencer is blacklisted and refuses the result; the next mediator's verdict is delayed 3 s on the node clock, which is the stopped sim clock. | blacklisting only |

Two facts frame every option:

- Nothing times out on a stopped sim clock. Send timeouts, the topology outbox, in-flight submissions and the
  mediator delay are driven by sequenced time or by the node clock. No test advances the clock during environment
  setup.
- The window is structural. A new sequencer starts its P2P server only after it is in the ordering topology and
  has been initialized from the onboarding state. Nothing in Canton gates activation of the new ordering topology
  on connectivity.

It happens once per Canton instance: sequencers stay in `SequencerSynchronizerState` across suites (only the
threshold is reset by `ResetSequencerSynchronizerStateThreshold`), so it hits the first multi-SV suite of a shard.

## Facts from the code

Paths under `canton/community/synchronizer/src/main/scala/com/digitalasset/canton/synchronizer/sequencer/block/bftordering/`
are abbreviated `B/`; `base/` is `canton/community/base/src/main/scala/com/digitalasset/canton/`.

### Rejected send

- `B/core/modules/mempool/MempoolModule.scala:70-78`: `if (!canDisseminate)` rejects with `P2PNotReady`.
  `canDisseminate` is `authenticatedCount >= weakQuorum` (`MempoolState.scala`). The comment says the rejection is
  deliberate, "shortens the client retry cycle and leverages sequencer client amplification".
- The count includes the node itself and incoming connections (`B/core/modules/p2p/P2PNetworkOutModule.scala:912-931`).
- Quorums (`B/framework/data/topology/OrderingTopology.scala:158-181`):

  | n | weak (dissemination) | strong |
  |---|---|---|
  | 1 | 1 | 1 |
  | 2 | 1 | 2 |
  | 3 | 1 | 3 |
  | 4 | 2 | 3 |

  So the rejection can only happen once 4 sequencers are in the topology.
- No config flag or sequencing parameter disables the check (`MempoolModuleConfig`, `SequencingParameters`,
  `BftBlockOrdererConfig` have none).
- `B/bindings/canton/sequencing/BftBlockOrderer.scala:974-983`: `P2PNotReady` becomes
  `SequencerErrors.Unavailable`; every other mempool rejection, the blacklist one included, becomes `Overloaded`.
- `base/sequencing/client/pool/GrpcSequencerConnection.scala:189-198`: `GrpcServiceUnavailable` is logged as
  "returned an error however may still be possibly sequenced so we are ignoring the error" and returned as success.
  `Overloaded` is a definitive refusal. So the P2P rejection is the only mempool rejection the client does not
  treat as final.
- Topology broadcasts are sent with `amplify = false`, so amplification does not retry them elsewhere.
- The topology outbox waits for the send result without a wall-clock bound. Max sequencing time is
  `clock.now + topology-transaction-registration-timeout` (20 s). Read from Canton 3.7.0 sources in git history
  (`01436df75f^`), because `community-common` is a binary dependency since #8257; the 3.7.3 code was not read.

### Batch never ordered

- `B/core/BftBlockOrdererConfig.scala:116-118,343`: `availabilityDisseminationPatience`, "If `None`, re-sending is
  disabled", default `None`.
- A batch complete with 1 ack under n=1 goes back to in-progress under n=4 and is re-sent to the new members once
  (`AvailabilityModule.scala:851-897`); they are not connected yet.
- Not verified: which clock drives the patience, and whether a re-send alone gets the batch ordered in the cases
  logged as `received message after closing` (`B/core/modules/consensus/iss/EpochState.scala:258-262`).

### Lost verdict

- `canton/community/synchronizer/src/main/scala/com/digitalasset/canton/synchronizer/mediator/VerdictSender.scala:252-270`:
  the delay is scheduled with `clock.scheduleAfterCancelledOnShutdown`, i.e. on the node clock.
- Config `DelayedVerdictSenderConfig` (`MediatorNode.scala:98-133`): `enabled = true`, `liveness-margin = 3`,
  `initial-delay = 3s`, `delay = 1s`. Tests set `liveness-margin = 1` in
  `apps/app/src/test/resources/include/mediators.conf` so that not all mediators send.

### Topology activation and P2P

- The new ordering topology becomes active at the first epoch boundary after the topology change is effective
  (`B/core/modules/output/OutputModule.scala:1235-1260`). No connectivity or catch-up check.
- Epoch length is `segmentLength * number of sequencers`. Splice sets `segmentLength = 20`
  (`BftSequencingParameters.default` in `SvAppConfig.scala`), Canton's default is 10.
- An uninitialized sequencer runs no P2P server (`SequencerNode.scala:338-341,798-813`). A peer can authenticate
  only when it is an active sequencer in the topology. So static `peer-endpoints` cannot pre-connect newcomers.
- P2P reconnect backoff doubles up to `max-connection-retry-delay`, default 2 min
  (`BftBlockOrdererConfig.scala:489-510`).
- Admin API to observe it: `bft.get_peer_network_status`, `bft.get_write_readiness`, `bft.get_ordering_topology`.

### Splice SV app

- `SequencerOnboarding.diffDsoRulesWithTopology` (`SvOnboardingSequencerTriggers.scala`) proposes every sequencer
  from the DsoRules node configs that is not active yet, all at once. That is how 1 to 4 happens in one step.
- Existing sequencers learn a newcomer's P2P endpoint from the newcomer's scan (`SequencerBftPeerReconciler`,
  `listSvBftSequencers`), which lists a sequencer only once it is initialized and caches "not initialized" for
  10 s (`HttpScanHandler.uninitializedBftSequencersCooldown`).
- A joining SV writes its synchronizer node config twice: first with `SynchronizerNodeState.Onboarding`
  (`sequencerIdentity.availableAfter` empty), and with `OnboardedAfterDelay` (`availableAfter` set) at the end of
  `JoiningNodeInitializer`. This is a ledger-visible signal every SV sees identically; option 2 uses it.
- The global synchronizer is bootstrapped by the sv1 app (`SV1Initializer.bootstrapDomain`), not by
  `bootstrap-canton.sc`.

### Test setup

- Order in `EnvironmentSetup.manualCreateEnvironment` (`canton/community/integration-testing/`): plugins
  `afterEnvironmentCreated`, "Starting all nodes", "Running setup". When setup fails only the fixture is closed;
  the plugins' `beforeEnvironmentDestroyed` does not run, so `ResetDecentralizedNamespace` is skipped and the
  following suites of the shard fail (11332, 11324).
- `EnvironmentDefinition.withInitializedNodes` adds the wait for app initialization to `setup`.

## Options

1. **Canton test config.** Disable the mediator verdict delay under simtime, enable batch re-send, lower the P2P
   reconnect cap. Small, no code. Does not fix the rejected-send shape.
2. **SV app adds sequencers one at a time.** Replaces splice#8363 (same idea as a test plugin). By the quorum table,
   steps 1 to 2 and 2 to 3 never reject, and at 3 to 4 three nodes are already authenticated. Also protects
   production when two SVs join close together. Changes trigger behaviour: needs early review.
3. **Advance the sim clock when setup is stuck.** Test-only safety net that gives simtime the recovery path
   wall-clock runs have. Covers all three shapes, but hides the Canton behaviour.
4. **DA BFT sequencing parameters.** No parameter relaxes the P2P rejection. Shorter view-change timeout and
   segment length, and no blacklisting, help the wall-clock shapes (11257, 11173, breaker trips).
5. **Onboard the sequencers when the shared Canton starts.** Not practical in `bootstrap-canton.sc`, because the
   sv1 app bootstraps the synchronizer and the joiners' apps initialize their sequencers; the script would
   duplicate that logic. A warm-up run of the real apps before the suites is possible (CI step, `start-canton.sh`
   flag), but it is splice#8363 moved out of the test framework, costs minutes on every shard, and still needs the
   sequential start. Not planned.
6. **Upstream, DA BFT team.** Return the P2P-not-ready rejection as a definitive refusal like the blacklist
   rejection (the outbox then retries after `broadcast-retry-delay`, which runs on wall clock); enable batch
   re-send by default; do not activate an ordering topology whose new members are not connected. To be filed by
   the user.

Independent of the options: run the topology reset plugins when "Running setup" fails (suggested in 11332). The
code is in `canton/`, which splice avoids editing; not planned here.

## Suggested order

1. Option 1.
2. Option 2, after the user has reviewed the interface change.
3. Option 3 only if option 2 stalls.
4. Option 4 as its own PR.
5. Option 6 filed upstream.

Options 1, 2 and 4 are independent PRs. Option 3 conflicts with none of them.
