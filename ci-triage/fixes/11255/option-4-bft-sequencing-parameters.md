# Option 4: DA BFT sequencing parameters in tests

Implementation plan for one PR against splice `main`. Background and code facts: `README.md` in this folder. Read
its sections "Cause" and "Facts from the code" first.

## Goal

Make the DA BFT layer in integration tests recover faster after an ordering topology change, and stop it from
blacklisting nodes, by changing the sequencing parameters the SV apps set in tests:

- shorter PBFT view-change timeout,
- shorter segments (shorter epochs),
- no blacklisting in wall-clock tests too (splice#8356 did this for simtime only).

This helps the wall-clock shapes of DACH-NY/cn-test-failures#11255 (epoch stalls of 20 to 30 s, circuit breaker
trips after a node was blacklisted). It does not fix the rejected-send shape: no sequencing parameter relaxes the
`P2P connectivity is not ready` rejection. Do not claim that it does.

## Rules

- Follow the repository's `CLAUDE.md` / `AGENTS.md`. Run every sbt and script command through `direnv exec .`.
- Branch from current `main` in its own worktree: `<user>/fix-11255-bft-test-parameters` (`<user>` = `gh api user --jq .login`). Never push.
- Do not change `BftSequencingParameters.default` (`SvAppConfig.scala`): that is the production default.
- Do not edit anything under `canton/`.
- Sign off commits (`git commit -s`), no `Co-Authored-By`.
- If a step says STOP, stop and report what you found instead of improvising.

## How the parameters reach Canton

- Each SV app has `cantonBftSequencingParameters: Option[BftSequencingParameters]` in `SvAppBackendConfig`
  (`apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/config/SvAppConfig.scala`). Production default:
  view-change timeout 5 s, segment length 20, exponential blacklisting.
- `ReconcileSequencingParametersTrigger` of every SV writes its configured value into the
  `SequencingParametersState` topology mapping whenever the on-ledger value differs. So all SVs of an environment
  must have the same value, or their triggers overwrite each other forever.
- Canton reads the mapping at an epoch boundary. The mapping stays on the shared Canton instance across suites.
- splice#8356 added `ConfigTransforms.disableBftBlacklisting()`
  (`apps/app/src/main/scala/org/lfdecentralizedtrust/splice/config/ConfigTransforms.scala`), applied at the end of
  `EnvironmentDefinition.withSimTime`.

## Values

| Parameter | Production | Tests after this PR | Canton's own test (for comparison) |
|---|---|---|---|
| `pbftViewChangeTimeout` | 5 s | 1 s | 250 ms |
| `segmentLength` | 20 | 4 | 2 |
| blacklisting | exponential | none | none |

The values are less aggressive than Canton's test on purpose: CI runners are loaded, and a timeout that is too
short causes view changes without any fault. Do not go below these values without the user's approval.

## Steps

### 1. Replace the transform

In `ConfigTransforms.scala`, replace `disableBftBlacklisting()` by a transform that sets all three values. Keep
using `updateAllSvAppConfigs_` so that every SV gets the same value:

```scala
// Production values are tuned for throughput on a stable network. In tests SV nodes join and leave all the time:
// a short view-change timeout and short segments let ordering recover quickly after a topology change, and without
// blacklisting a node that was slow during its onboarding does not reject client submissions afterwards.
def withBftSequencingParametersForTests(): ConfigTransform =
  updateAllSvAppConfigs_(c =>
    c.copy(cantonBftSequencingParameters =
      c.cantonBftSequencingParameters.map(
        _.copy(
          pbftViewChangeTimeout = PositiveFiniteDuration.ofSeconds(1),
          segmentLength = PositiveLong.tryCreate(4L),
          blacklistLeaderSelectionPolicyConfig =
            SequencingParameters.NoBlacklistingLeaderSelectionPolicyConfig,
        )
      )
    )
  )
```

Match the field types of `BftSequencingParameters` exactly (check which `PositiveFiniteDuration` and which
positive-number type the case class uses and how `BftSequencingParameters.default` builds them; copy that style).

### 2. Apply it to all tests

- Remove the `.addConfigTransform((_, conf) => ConfigTransforms.disableBftBlacklisting()(conf))` line from
  `EnvironmentDefinition.withSimTime`.
- Add `withBftSequencingParametersForTests()` to `ConfigTransforms.defaults(...)` (the `Seq` that starts with
  `makeAllTimeoutsBounded`). That list applies to every test environment, wall clock and simtime.
- `git grep -n "disableBftBlacklisting"` must return nothing afterwards.

### 3. Fix the test that asserts on the defaults

`apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/ReconcileBftSequencingParametersIntegrationTest.scala`
starts sv1 and asserts `pbftViewChangeTimeout` is 5 s and blacklisting is `Exponential`, then restarts with an
`sv1Local` config that uses `Linear` and asserts the change.

Keep what the test proves (the trigger applies a change of config). Change the first assertions to the new test
values (1 s, and `howLongToBlacklist` / `howManyCanWeBlacklist` as in
`SequencingParameters.NoBlacklistingLeaderSelectionPolicyConfig`). The `sv1Local` config is built from
`c.svApps("sv1")` with explicit values, so the second half should not need changes; check whether the transform
from step 2 runs before or after this suite's own `addConfigTransforms` and adjust only if the assertion fails.

Then search for other tests that depend on the old values:

```bash
git grep -n "cantonBftSequencingParameters\|pbftViewChangeTimeout\|blacklist" -- apps/app/src/test | grep -v ReconcileBftSequencingParameters
```

Suites that test blacklisting or view changes on purpose must keep the production values: add an opt-out for them
by overriding the config in their `environmentDefinition` with `BftSequencingParameters.default`. The reviewer of
#8356 asked to keep blacklisting on for the LSU and DR tests; apply the opt-out to the suites whose name contains
`Lsu` or `DisasterRecovery` and list them in the PR description so the user can decide.

Also check the ignore patterns: `git grep -n -i "blacklist\|view change" -- project/ignore-patterns`. Do not
remove entries.

### 4. Run

```bash
./stop-canton.sh || true
rm -rf log/*
direnv exec . ./start-canton.sh -d
direnv exec . sbt --batch "apps-app/testOnly org.lfdecentralizedtrust.splice.integration.tests.ReconcileBftSequencingParametersIntegrationTest" > /tmp/o4-reconcile.log 2>&1
grep -E '^\[error\]|Tests:' /tmp/o4-reconcile.log
direnv exec . sbt --batch "apps-app/testOnly org.lfdecentralizedtrust.splice.integration.tests.SvOnboardingIntegrationTest" > /tmp/o4-wall.log 2>&1
grep -E '^\[error\]|Tests:' /tmp/o4-wall.log
```

Look up the fully qualified class names with `git grep -l "class <Name>"` if they differ. If the tests cannot run
in the sandbox, say so and leave them to CI.

### 5. Check the effect in the Canton log

After the wall-clock 4-SV suite:

```bash
# parameters were applied: epochs with 4 nodes are 16 blocks long (4 * 4)
grep -a 'New epoch' log/canton.clog | grep -a 'globalSequencerSv1' | tail -3
# no node is blacklisted
grep -a 'New epoch' log/canton.clog | grep -a -v 'blacklisted nodes = List()' | wc -l     # expected: 0
# view changes: count them, for the PR description
grep -a -c -i 'view change' log/canton.clog
direnv exec . sbt --batch checkErrors
```

Compare the view-change count with a run on `main` (same suite, fresh Canton). If the count on the branch is more
than about three times the count on `main`, the timeout is too short for this machine: raise
`pbftViewChangeTimeout` to 2 s, repeat once, and report both counts. If `checkErrors` reports new WARN lines from
the BFT layer, quote them and STOP; do not add ignore patterns on your own.

### 6. Commit

One commit, message ending in `[ci]`:

```
Use a short BFT view-change timeout, short segments and no blacklisting in all integration tests [ci]
```

PR description: link DACH-NY/cn-test-failures#11255; the values table; which shapes this helps and that the
rejected-send shape is not addressed; the opt-out suites; the view-change counts from step 5.

## Done when

- `ReconcileBftSequencingParametersIntegrationTest` passes.
- The Canton log of a wall-clock 4-SV suite shows no blacklisted node.
- `direnv exec . sbt --batch lint` passes.

## Risks to report, not to solve

- Spurious view changes on slow runners (step 5 measures this locally; CI is the real check).
- Shorter epochs mean more epoch changes and more topology queries per second of sequencing. If setup time of the
  first suite gets noticeably longer than on `main`, report the two durations.
- The mapping stays on the shared Canton instance. A suite that opts out changes it for the suites after it until
  their sv1 reconciles it back; the reconciliation is automatic.
