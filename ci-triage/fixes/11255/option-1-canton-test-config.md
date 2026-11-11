# Option 1: Canton test config

Implementation plan for one PR against splice `main`. Background and code facts: `README.md` in this folder. Read
its sections "Cause" and "Facts from the code" first.

## Goal

Change the Canton configuration used by the integration tests so that two of the three failure shapes of
DACH-NY/cn-test-failures#11255 cannot happen:

- Lost verdict: under simulated time a mediator delays its verdict by 3 s on a clock that does not advance.
- Batch never ordered: a batch sent to peers that are not connected yet is never sent again.

This PR does not fix the third shape (rejected send). Do not claim that it does.

## Rules

- Follow the repository's `CLAUDE.md` / `AGENTS.md`. Run every sbt and script command through `direnv exec .`.
- Branch from current `main` in its own worktree: `<user>/fix-11255-canton-test-config` (`<user>` = `gh api user --jq .login`). Never push.
- Change only the files named below. Do not edit anything under `canton/`.
- Sign off commits (`git commit -s`), no `Co-Authored-By`.
- If a step says STOP, stop and report what you found instead of improvising.

## Files

- `apps/app/src/test/resources/simple-topology-canton-simtime.conf` (simtime Canton instance; includes
  `simple-topology-canton.conf` and overrides parts of it)
- `apps/app/src/test/resources/include/sequencers.conf` (template `_sequencer_bft_template`)
- `apps/app/src/test/resources/include/mediators.conf` (read only; it sets `delayed-verdict-sender.liveness-margin = 1`)

## Steps

### 1. Disable the delayed verdict sender under simtime

In `simple-topology-canton-simtime.conf`, after the existing line
`_sequencer_template.sequencer.block.circuit-breaker.enabled = false`, add:

```hocon
# The delay is scheduled on the node clock. Under simulated time nothing advances it during environment setup,
# so a delayed verdict is never sent and a request whose other verdicts are lost never completes.
_mediator_template.parameters.delayed-verdict-sender.enabled = false
```

Check that the key is right: `DelayedVerdictSenderConfig` in
`canton/community/synchronizer/src/main/scala/com/digitalasset/canton/synchronizer/mediator/MediatorNode.scala`
must have a field `enabled`. If it does not, STOP.

Do not change `mediators.conf`: wall-clock tests keep the delay.

### 2. Enable batch re-send on the BFT sequencers

First find out which clock drives the patience. In
`canton/community/synchronizer/src/main/scala/com/digitalasset/canton/synchronizer/sequencer/block/bftordering/core/modules/availability/AvailabilityModule.scala`,
find the uses of `disseminationPatienceO` and how the re-send is triggered (look for `PatienceAndCurrentTime` and
where `now` comes from). Write the answer into the PR description in one sentence.

In `include/sequencers.conf`, inside `_sequencer_bft_template.config`, next to
`consensus-empty-block-creation-timeout = 250ms`, add:

```hocon
# Re-send a batch to peers that have not acknowledged it. Off by default; without it a batch sent while the
# peers of a grown ordering topology are not connected yet is never ordered.
availability-dissemination-patience = 1s
```

Check that the key is right: `BftBlockOrdererConfig` (`.../bftordering/core/BftBlockOrdererConfig.scala`) must have
`availabilityDisseminationPatience: Option[FiniteDuration]`. If it does not, STOP.

### 3. Lower the P2P reconnect cap

In `BftBlockOrdererConfig.scala`, find the connection management config class (fields `initialConnectionMaxDelay`,
`maxConnectionRetryDelay`, ...) and the field name under which `P2PNetworkConfig` holds it. In
`include/sequencers.conf`, inside `initial-network { ... }`, add the kebab-case equivalent, expected to be:

```hocon
# Default is 2 minutes, which can outlast the onboarding of a peer.
connection-management-config.max-connection-retry-delay = 5s
```

If you cannot find the field, skip this step and say so in the PR description.

### 4. Verify that Canton accepts the config

Canton rejects unknown config keys at startup, so starting it is the check.

```bash
direnv allow
./stop-canton.sh || true
rm -rf log/*
direnv exec . ./start-canton.sh -d
```

Expected: the script returns after `wait-for-canton.sh` succeeds and `canton.tokens` and `canton-simtime.tokens`
exist. If Canton does not come up, read `log/canton.out` and `log/canton-simtime.out` for the config error, fix the
key, and retry. If the pinned Canton binary does not know a key that exists in `canton/` sources, remove that key
and say so in the PR description.

### 5. Run one simtime and one wall-clock suite with 4 SVs

```bash
direnv exec . sbt --batch "apps-app/testOnly org.lfdecentralizedtrust.splice.integration.tests.SvTimeBasedRoundMgmtIntegrationTest" > /tmp/o1-simtime.log 2>&1
grep -E '^\[error\]|Tests:' /tmp/o1-simtime.log
direnv exec . sbt --batch "apps-app/testOnly org.lfdecentralizedtrust.splice.integration.tests.SvOnboardingIntegrationTest" > /tmp/o1-wall.log 2>&1
grep -E '^\[error\]|Tests:' /tmp/o1-wall.log
```

Look up the fully qualified class names with `git grep -l "class SvTimeBasedRoundMgmtIntegrationTest"` if they
differ. If the tests cannot run in the sandbox (out of memory, timeouts unrelated to the change), say so and leave
them to CI; do not keep retrying.

Then check the logs:

```bash
grep -c 'Delaying sending of verdict' log/canton-simtime.clog    # expected: 0
direnv exec . sbt --batch checkErrors
```

`checkErrors` must not report new WARN or ERROR lines from the sequencers caused by the re-send. If it does, quote
them and STOP.

### 6. Commit

One commit, message ending in `[ci]`:

```
Canton test config: no delayed verdicts under simtime, re-send BFT batches [ci]
```

PR description: link DACH-NY/cn-test-failures#11255, say which two shapes this addresses and that the rejected-send
shape is not addressed, and give the answer from step 2.

## Done when

- Canton starts with the new config for both instances.
- `Delaying sending of verdict` no longer appears in `log/canton-simtime.clog`.
- The two suites pass locally, or the report says why they could not be run.
- `direnv exec . sbt --batch lint` passes.

## Risks to report, not to solve

- `liveness-margin = 1` in `mediators.conf` exists so that not all mediators send a verdict in tests. With the
  delay disabled under simtime all active mediators send at once. If a simtime test depends on the delay it will
  fail; name the test.
- Re-sending batches adds P2P traffic and log lines. If a test's log assertions break, name the test.
