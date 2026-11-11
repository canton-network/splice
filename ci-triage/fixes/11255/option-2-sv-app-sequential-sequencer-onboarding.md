# Option 2: the SV app adds sequencers one at a time

Implementation plan for one PR against splice `main`. Background and code facts: `README.md` in this folder. Read
its sections "Cause" and "Facts from the code" first.

This changes the behaviour of an SV automation trigger. The design below must be approved by the user before any
code is written. If you were not told that it is approved, present the section "Design" to the user and STOP.

## Goal

Today every SV proposes all missing sequencers at once, so the DA BFT ordering topology can grow from 1 to 4 nodes
in one step. With 4 nodes a sequencer rejects submissions unless it has an authenticated peer, and right after the
step it has none. After this PR:

- a new sequencer is added to `SequencerSynchronizerState` only when every sequencer added before it has finished
  onboarding, and
- a joining SV reports "finished onboarding" only when its sequencer is connected to all other active sequencers.

Up to 3 nodes a sequencer never rejects for missing peers (dissemination quorum is 1). When the 4th is added, 3
are connected. This replaces splice#8363, which did the same in a test plugin.

## Rules

- Follow the repository's `CLAUDE.md` / `AGENTS.md`. Run every sbt and script command through `direnv exec .`.
- Branch from current `main` in its own worktree: `<user>/fix-11255-sequential-sequencer-onboarding` (`<user>` = `gh api user --jq .login`). Never push.
- Do not edit anything under `canton/` or any Daml code. No DB migration is needed.
- Production code bans `head`, `last`, `min`, `max` on collections; use `headOption`, `sorted.headOption` etc.
- Sign off commits (`git commit -s`), no `Co-Authored-By`.
- If a step says STOP, stop and report what you found instead of improvising.

## Design

### The signal

A joining SV writes its synchronizer node config into its `SvNodeState` twice
(`SynchronizerNodeReconciler.reconcileSynchronizerNodeConfigIfRequired`, called from `JoiningNodeInitializer`):

1. with `SynchronizerNodeState.Onboarding(...)` before its sequencer is onboarded: `sequencerIdentity` is set,
   `sequencerIdentity.availableAfter` is empty;
2. with `SynchronizerNodeState.OnboardedAfterDelay` at the end of its initialization: `availableAfter` is set.

sv1 writes its config with `OnboardedImmediately`, so its `availableAfter` is always set.

Every SV reads the same value from the ledger, so all SVs take the same decision without talking to each other.

### Sponsor side: `SequencerOnboarding.diffDsoRulesWithTopology`

File `apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/automation/singlesv/onboarding/SvOnboardingSequencerTriggers.scala`.

Definitions, all computed from the arguments the method already has:

- `configured`: the `(sequencerId, availableAfter)` pairs from
  `dsoRulesAndState.currentSynchronizerNodeConfigs()` (via `config.sequencerIdentity`).
- `active`: `SequencerSynchronizerState.mapping.active`.
- `stillOnboarding`: sequencers that are in `active` and in `configured` with an empty `availableAfter`.
  Active sequencers without a config entry are ignored (the shared test Canton keeps sequencers of earlier
  suites active).
- `sequencersToAdd`: as today, configured and not active.

New behaviour, only when the new config flag is on:

- If `stillOnboarding` is non-empty: log at info which sequencers are waited for, and add nothing.
- Otherwise add exactly one sequencer: the first of `sequencersToAdd` sorted by `sequencerId.toProtoPrimitive`.

The `else if (thresholdToSet != ...)` branch stays as it is.

All SVs must pick the same sequencer, otherwise no proposal reaches the signature threshold. That is why the order
is by sequencer id and not by anything local.

### Joiner side: wait for P2P before reporting "onboarded"

File `apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/onboarding/joining/JoiningNodeInitializer.scala`.

Before the call `reconcileSynchronizerNodeConfigIfRequired(..., OnboardedAfterDelay)` (the one after
`registerPostUnlimitedTrafficTriggers()`), and only if the local sequencer is a Canton BFT sequencer, wait until:

- the local sequencer's ordering topology (`sequencerAdminConnection.getSequencerOrderingTopology()`) contains all
  sequencers of the current `SequencerSynchronizerState`, and
- every other sequencer in it is authenticated: it appears with `Some(sequencerId)` in
  `sequencerAdminConnection.listCurrentPeerEndpoints()` (this covers authenticated outgoing endpoints and incoming
  connections; see `SequencerBftAdminConnection`).

Use `retryProvider.waitUntil(RetryFor.WaitingOnInitDependency, ...)` like the neighbouring code does, throwing
`Status.FAILED_PRECONDITION` with a message that lists the missing peers while the condition does not hold.

The peer endpoints are added by `SvBftSequencerPeerOnboardingTrigger`, which is already registered at that point,
so this wait only observes.

### Config flag

Add `enableSequentialSequencerOnboarding: Boolean = true` to `SvAppBackendConfig`
(`apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/config/SvAppConfig.scala`). Both changes are behind it.
Name follows the `enableXXX` rule in `AGENTS.md`.

### Known consequences (put them in the PR description)

- A sequencer that is added but whose SV never finishes onboarding blocks later sequencer additions until that SV
  finishes or the flag is turned off on a signing majority of SVs.
- SVs running an older version still propose everything at once. The gate takes full effect when enough SVs to
  reach the signature threshold run the new version.
- Onboarding several SVs at once takes longer, because their sequencer steps run one after another.
- The step 1 to 2 still stalls ordering until the second sequencer is up (strong quorum of 2 is 2). Not changed.

## Steps

### 1. Config flag

Add the field to `SvAppBackendConfig` next to `cantonBftSequencingParameters`, with a one-line comment. Check the
config docs: `git grep -n "cantonBftSequencingParameters\|canton-bft-sequencing-parameters" -- docs cluster`. If
that option is documented or wired there, mirror it for the new flag only where a default of `true` needs no
entry; otherwise leave docs and cluster untouched.

### 2. Sponsor side

1. Give `SequencerOnboarding` a constructor parameter `sequentialOnboarding: Boolean`.
2. In `SvOnboardingSequencerTrigger` add the parameter and pass it through.
3. In `SvDsoAutomationService` (search `new SvOnboardingSequencerTrigger(`) pass
   `config.enableSequentialSequencerOnboarding`. Check that `config` there is the `SvAppBackendConfig`; the
   neighbouring `ReconcileSequencingParametersTrigger` registration uses `config.cantonBftSequencingParameters`.
4. Implement the logic from "Design". Keep the existing parsing of sequencer ids; extend it to keep
   `availableAfter` alongside the id. Extract the decision into a pure function in the companion object so it can
   be unit tested:

   ```scala
   // returns the sequencers to add now
   private[onboarding] def sequencersToAddNow(
       configured: Seq[(SequencerId, Boolean)], // (id, onboarded = availableAfter is set)
       active: Seq[SequencerId],
       sequentialOnboarding: Boolean,
   ): Seq[SequencerId]
   ```

   With `sequentialOnboarding = false` it returns what the code returns today.

### 3. Joiner side

1. Add a method to `LocalSynchronizerNode`
   (`apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/LocalSynchronizerNode.scala`), next to
   `onboardLocalSequencerIfRequired`:

   ```scala
   def waitForBftSequencerPeers(synchronizerId: SynchronizerId)(implicit tc: TraceContext): Future[Unit]
   ```

   It returns `Future.unit` unless `config.sequencer.isCantonBftSequencer`. Otherwise it implements the wait from
   "Design". Read the active sequencers with the admin connection the class already has (look at how
   `HttpSvPublicHandler.waitForNewSequencerObservedByExistingSequencer` reads `SequencerSynchronizerState` and the
   ordering topology; reuse the same calls).
2. Call it in `JoiningNodeInitializer` as described, inside the `if (!config.shouldSkipSynchronizerInitialization)`
   branch, guarded by `config.enableSequentialSequencerOnboarding`.

### 4. Unit test

Add `apps/sv/src/test/scala/org/lfdecentralizedtrust/splice/sv/automation/singlesv/onboarding/SequencerOnboardingSpec.scala`
for `sequencersToAddNow`. Use `apps/sv/src/test/scala/org/lfdecentralizedtrust/splice/sv/onboarding/SequencerBftPeerReconcilerSpec.scala`
as the model for the class shape and for building `SequencerId`s. Cases:

| configured | active | flag | expected |
|---|---|---|---|
| sv1 onboarded; B, C onboarding | sv1 | on | the smaller of B, C by id |
| sv1 onboarded; B, C onboarding | sv1, B | on | nothing |
| sv1, B onboarded; C onboarding | sv1, B | on | C |
| sv1 onboarded | sv1, X (X not configured) | on | nothing to add, X ignored |
| sv1 onboarded; B, C onboarding | sv1 | off | B and C |

Run:

```bash
direnv exec . sbt --batch "apps-sv/testOnly org.lfdecentralizedtrust.splice.sv.automation.singlesv.onboarding.SequencerOnboardingSpec" > /tmp/o2-unit.log 2>&1
grep -E '^\[error\]|Tests:' /tmp/o2-unit.log
direnv exec . sbt --batch updateTestConfigForParallelRuns
```

Commit the changed `test-*.log` files.

### 5. Integration check

Start Canton fresh so that the sequencers are not in the topology yet (this matters: on a used Canton instance the
code path is not exercised):

```bash
./stop-canton.sh || true
rm -rf log/*
direnv exec . ./start-canton.sh -d
direnv exec . sbt --batch "apps-app/testOnly org.lfdecentralizedtrust.splice.integration.tests.SvTimeBasedRoundMgmtIntegrationTest" > /tmp/o2-simtime.log 2>&1
grep -E '^\[error\]|Tests:' /tmp/o2-simtime.log
```

Then show that the ordering topology grew one node at a time:

```bash
grep -a 'New epoch' log/canton-simtime.clog | grep -a 'globalSequencerSv1' | grep -a -o 'size = [0-9]*' | uniq
```

Expected output: `size = 1`, `size = 2`, `size = 3`, `size = 4`, in that order. If a size is skipped, the gate did
not work: STOP and report. Also:

```bash
grep -a -c 'P2P connectivity is not ready' log/canton-simtime.clog    # expected: 0
```

Repeat with a fresh Canton for a wall-clock suite that starts 4 SVs (`SvOnboardingIntegrationTest`, log
`log/canton.clog`). If the tests cannot run in the sandbox, say so and leave them to CI.

Tests that must still pass in CI and may be sensitive to the slower sequencer onboarding: suites with `Onboarding`,
`Lsu`, `RollForward`, `Offboarding`, `ManualStart` in their name. Do not raise timeouts in tests without reporting
which and why.

### 6. Commits

1. `Add sequencersToAddNow and enableSequentialSequencerOnboarding` (pure refactor plus flag, behaviour unchanged
   with the flag off) with the unit test.
2. `Add SV sequencers one at a time and wait for BFT peers before reporting onboarded [ci]`.

PR description: link DACH-NY/cn-test-failures#11255 and splice#8363 (superseded), the design in five lines, the
"Known consequences" list, and the two grep outputs from step 5.

## Done when

- Unit test passes.
- On a fresh Canton the size sequence is 1, 2, 3, 4 and there is no `P2P connectivity is not ready`.
- `direnv exec . sbt --batch lint` passes, and `Test/compile` has no warnings (remove `local.sbt` first if used).
