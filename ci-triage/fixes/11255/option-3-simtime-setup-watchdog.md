# Option 3: advance the sim clock when environment setup is stuck

Implementation plan for one PR against splice `main`. Background and code facts: `README.md` in this folder. Read
its sections "Cause" and "Facts from the code" first.

This is a test-only safety net. It does not prevent the lost message; it lets the system recover from it the way
wall-clock tests already do. Implement it only if the user asked for it; option 2 is the preferred fix.

## Goal

Under simulated time nothing advances the clock while the apps initialize. A submission that is lost during that
time waits for its max sequencing time (20 s for topology broadcasts, about 60 s for transactions) forever, and
app initialization fails after 5 to 8 minutes of wall-clock time.

After this PR a watchdog runs during the setup of every simtime environment. When the ledger has made no progress
for a while, it advances the sim clock by a small step, so that pending sends time out and are retried. It stops
when setup is finished, before any test code runs.

## Rules

- Follow the repository's `CLAUDE.md` / `AGENTS.md`. Run every sbt and script command through `direnv exec .`.
- Branch from current `main` in its own worktree: `<user>/fix-11255-simtime-setup-watchdog` (`<user>` = `gh api user --jq .login`). Never push.
- Test code only (`apps/app/src/test`). Do not edit anything under `canton/` or under `src/main`.
- Test code compiles with fatal warnings: no unused parameters or private members.
- Sign off commits (`git commit -s`), no `Co-Authored-By`.
- If a step says STOP, stop and report what you found instead of improvising.

## Design

### Where it hooks in

`EnvironmentDefinition` (`apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/EnvironmentDefinition.scala`)
has a `setup` function that runs in the "Running setup" step after the apps were started; `withInitializedNodes()`
appends the wait for app initialization to it:

```scala
def withInitializedNodes(): EnvironmentDefinition =
  copy(setup = implicit env => {
    this.setup(env)
    EnvironmentDefinition.waitForNodeInitialization(env)
  })
```

`withSimTime` is applied after the standard setup (`simpleTopology4Svs(testName).withSimTime`). So wrapping the
whole `setup` inside `withSimTime` covers the wait:

```scala
copy(setup = env => {
  val watchdog = SimTimeSetupWatchdog.start(env, logger)
  try this.setup(env)
  finally watchdog.stop()
})
```

`stop()` must block until the watchdog thread has ended, so that no clock change can happen after setup returns.

Environments with `withManualStart` start their apps inside the test; they are not covered. Say so in the PR
description.

### What the watchdog does

A single daemon thread, loop every 5 s of wall-clock time:

1. Read the ledger end of each SV participant that is reachable:
   `sv.participantClientWithAdminToken.ledger_api.state.end()` for `env.svs.local`. Ignore participants whose call
   fails (not started yet). Check the exact console method name in
   `canton/community/app-base/src/main/scala/com/digitalasset/canton/console/commands/LedgerApiAdministration.scala`.
2. If any ledger end changed since the last loop, remember the wall-clock time as "last progress".
3. If there was no progress for `stallThreshold` and fewer than `maxAdvances` advances were made: advance the sim
   clock by `step`, log it at INFO with the sim time before and after, and reset "last progress".

Constants (in the companion object, with a comment each):

| Name | Value | Why |
|---|---|---|
| `pollInterval` | 5 s | cheap |
| `stallThreshold` | 45 s wall clock | longer than a healthy quiet phase; see step 4 for how to check |
| `step` | 25 s sim time | more than the 20 s topology broadcast timeout, less than the 30 s response timeouts and the 60 s ledger time tolerance, so a healthy in-flight transaction is not dropped |
| `maxAdvances` | 6 | total drift at most 150 s, far below the round tick, and enough to pass a 60 s max sequencing time plus a retry |

Advancing the clock: the same calls as `TimeTestUtil.advanceTime`
(`apps/app/src/test/scala/org/lfdecentralizedtrust/splice/util/TimeTestUtil.scala`):

```scala
val now = participant.ledger_api.time.get()
participant.ledger_api.time.set(now, now.plus(step))
```

on the sv1 participant (`env.svs.local` entry named `sv1`, `participantClient`). Do not call `advanceTime` itself:
it waits for round automation and uses test assertions. Catch and log (INFO) every exception inside the loop; a
watchdog failure must never fail the setup.

### Why "no ledger progress" as the trigger

In all three failure shapes the SV participants stop publishing (packets 11255, 11287, 11306 in `../../evidence/`).
A spurious advance while the ledger is quiet is harmless: nothing is in flight. The trigger is a heuristic; the
threshold is validated in step 4.

## Steps

### 1. Add the watchdog class

New file `apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/SimTimeSetupWatchdog.scala`
(copyright header like the neighbouring files). Shape:

```scala
final class SimTimeSetupWatchdog private (env: SpliceTestConsoleEnvironment, logger: TracedLogger) {
  private val stopped = new java.util.concurrent.atomic.AtomicBoolean(false)
  private val thread = new Thread(() => run(), "simtime-setup-watchdog")
  thread.setDaemon(true)
  def stop(): Unit = { stopped.set(true); thread.interrupt(); thread.join(30000) }
  private def run(): Unit = ??? // the loop from "Design"; exit when stopped is set
}
object SimTimeSetupWatchdog {
  def start(env: SpliceTestConsoleEnvironment, logger: TracedLogger): SimTimeSetupWatchdog = ...
}
```

Use the logger type that `EnvironmentDefinition` already uses for `logger.info`. Console commands that fail log
at ERROR in the test log and would fail `checkErrors`; wrap calls that may fail (participant not up yet) the way
other test code probes without logging. Search for an existing pattern first:
`git grep -n "Try(.*ledger_api\|health.is_running\|health.status" -- apps/app/src/test | head`. Prefer checking
`health` before calling the ledger API over catching failures. If you find no way to probe without ERROR logs,
STOP.

### 2. Wire it into `withSimTime`

In `EnvironmentDefinition.withSimTime`, append the `copy(setup = ...)` from "Design" as the last step of the
chain. Check the type of `setup` in the case class and match it (it may take an implicit environment).

### 3. Compile and run one simtime suite

```bash
./stop-canton.sh || true
rm -rf log/*
direnv exec . ./start-canton.sh -d -s
direnv exec . sbt --batch "apps-app/testOnly org.lfdecentralizedtrust.splice.integration.tests.SvTimeBasedRoundMgmtIntegrationTest" > /tmp/o3.log 2>&1
grep -E '^\[error\]|Tests:' /tmp/o3.log
```

### 4. Validate the threshold on healthy runs

The watchdog should not fire in a healthy setup. Count its advances:

```bash
grep -a -c 'simtime-setup-watchdog' log/canton_network_test.clog
grep -a 'advancing sim time' log/canton_network_test.clog | head
```

(use the message text you chose). Run three different simtime suites with 4 SVs on a fresh Canton each (the first
suite on an instance has the longest setup). Pick them from `test-full-class-names-sim-time.log`.

- 0 advances in all three: keep 45 s.
- Advances in healthy runs: report how many and at which setup phase (the app log lines around the advance).
  Raise `stallThreshold` to 90 s and repeat once. If it still fires, STOP and report; do not keep tuning.

If the tests cannot run in the sandbox, say so; the validation then has to happen in CI by reading the same grep
from the uploaded logs.

### 5. Show that it recovers (optional, only if cheap)

There is no deterministic reproducer for 11255. Do not build one. The recovery is checked in CI over time: a hit
then shows an advance in the log followed by a passing setup.

### 6. Commit

One commit, message ending in `[ci]`:

```
Advance the sim clock when the setup of a simtime environment is stuck [ci]
```

PR description: link DACH-NY/cn-test-failures#11255; explain in three sentences what the watchdog does and that it
is a recovery, not a prevention; list the constants; say that manual-start environments are not covered; give the
advance counts from step 4.

## Done when

- The watchdog stops before the first test of the suite runs (its thread is joined in `stop()`).
- Healthy setups show 0 advances, or the report explains the remaining ones.
- `direnv exec . sbt --batch lint` passes and `checkErrors` shows no new lines from the watchdog.

## Risks to report, not to solve

- Tests that assert on the exact sim time or on the initial round state right after setup can break when the
  watchdog advanced the clock. This can only happen in runs where setup was stuck, which would have failed anyway.
- An advance while a transaction is in flight on a participant whose ledger end is not polled (validators) can
  drop that transaction; the participant recovers after its wall-clock timeout (see the comment in
  `TimeTestUtil.advanceTime`).
- The watchdog hides the Canton behaviour from the test results. The INFO log line is the only trace; mention the
  grep in the PR description so that triage can count hits.
