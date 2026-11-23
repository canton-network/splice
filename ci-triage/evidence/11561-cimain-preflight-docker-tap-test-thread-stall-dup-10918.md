# 11561 - cimain preflight_check_docker: tapAmulets test thread silent 115.7 s inside one `eventually` attempt, stale read fails

DUPLICATE of 10918 (family AT), confirmed by the family's geckodriver gap check. Deploy cimain (splice e74e9bf844,
Canton 3.7.4-snapshot.20261112.20915.0.v9e248620), job Preflight check (docker),
DockerComposeValidatorPreflightIntegrationTest, 0 passed, 1 failed. `tapAmulets(123.4)` failed its `forAtLeast(1)`
check (WalletFrontendTestUtil.scala:67 and :70): the only tap it had read was one of 20.004 USD dated
2026-11-18T10:51:37Z, below the expected 123.39. The tap itself worked: the page dumped at the failure shows the
123.4 USD Tap row of 10:51:44Z. The 20.004 USD tap of 10:51:37Z is the validator's own tap for its first traffic
purchase. The attempt that started at 10:51:45.3 found two rows (the 10:51:37Z tap and the traffic purchase), read
the first row's amulet amount at 10:51:45.7, and then the test thread did nothing for 115.7 s, until 10:53:41.176,
when it issued the first console HTTP command of the JVM (`GetSpliceInstanceNames`, for `amuletNameAcronym`). The
2 min budget of the `eventually` (from 10:51:23.0) ended during the stall, so `BaseTest.eventually` rethrew this
attempt's stale observation without a second attempt. Same shape, same code line and same resume point as 10920
(the closed duplicate of 10918). Not a wallet, validator or cluster problem.

New in this packet (section 3): the stall sits exactly where the JVM builds its console HTTP command runner for the
first time, which loads the package signatures of all DAR resources (lazy, CPU-only, not logged). The same load takes
30 to 61 s in the apps on CI shards; in a preflight / compose / LocalNet JVM without local apps it runs on the first
console command, which in `tapAmulets` is inside the 2 min `eventually`. This is a lead for the stall origin that the
parent packet left open; it is not proven (no thread dump, no timing of the load in this JVM), and it does not
explain 10918 itself (stall after an empty read, no console command).

- Splice: e74e9bf844 ("Disable base64 intrisics (#8563)").
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at e74e9bf844).
- Component: test framework (single over-long `eventually` attempt), flake.
- Parent: 10918, open, not closed as a duplicate, the ref family AT is filed under.
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. The test and what its job artifacts showed (2026-11-18, times UTC)

The test (`runbook/DockerComposeValidatorPreflightIntegrationTest.scala` at e74e9bf844) starts the compose validator,
logs in as administrator and calls `tapAmulets(123.4)`. In `WalletFrontendTestUtil.tapAmulets` the check is one
`eventually(timeUntilSuccess = 2.minute)` around `assertTapResultIsVisible()`, which reads all `.tx-row` elements,
their dates, then `txs.flatMap(readTapFromRow)`. `readTapFromRow` parses the amulet amount with
`unit = amuletNameAcronym`; with no local SV that is a `getSpliceInstanceNames()` call on the remote scan
(`CommonAppInstanceReferences.scala:363-396`).

- The page at the failure has the test's tap. The page dump and the screenshot taken at 10:53:44.9 show three rows:
  a Tap of 10:51:44Z (+123.40000000000343 USD), an Extra Traffic Purchase of 10:51:37Z (-20.00399999999973 USD) and
  a Tap of 10:51:37Z (+20.00399999999973 USD). 123.40000000000343 USD is inside the asserted range 123.39 to 123.41,
  so a second attempt would have passed.
- Clue timeline: the tap clue finished at 10:51:23.003. 168 attempts in 22 s read no rows (the last at
  10:51:45.126). The 169th, at 10:51:45.492, is the first that saw any row: the validator's own two rows of
  10:51:37Z; the test's tap (10:51:44Z) was not rendered yet. Its second log line, the list of new taps with only
  the 20.004 USD tap, came at 10:53:44.721, 119.2 s later, and the clue failed at 10:53:44.739.
- Family AT confirming check (the 10918 packet's search for gaps over 5 s between a geckodriver response and the
  next command): apart from the compose start before the browser is used and the teardown, there is one gap, of
  118.4 s, starting at 10:51:45 right after the text of `.tx-amount-amulet` of the first Tap row came back, the same
  point as in 10920 (208.4 s there).
- Only the test thread is silent. Between 10:51:45.492 and 10:53:41.176 the test log has 2616 lines, all from the
  BiDi console relay thread, none from the test thread. The test thread resumes at 10:53:41.176 with the
  `GetSpliceInstanceNames` console command against the remote scan, which is answered with 200 in about 1 s (and a
  second time in 0.4 s).

Test thread silent from the last geckodriver answer (10:51:45.7) to 10:53:41.176 = 115.7 s. The `eventually` started
at 10:51:23.0 with 2 min, so its deadline (10:53:23) passed inside the attempt. Signature and confirm of family AT
both hold: stale read reported, clue far beyond its budget, page at failure has the row, one round of WebDriver
commands then minutes of nothing, only the test thread silent.

## 2. Source: what runs before the first console HTTP command

The two `GetSpliceInstanceNames` calls are the only console commands of the whole run, so the one at 10:53:41.176 is
the first of the JVM. `ConsoleHttpCommandRunner.runCommand` logs `Running on ...` as its first statement, so the
time went before it, in the test thread, without a WebDriver call. What runs there on the first command (splice at
e74e9bf844, same on origin/main): `SpliceConsoleEnvironment.httpCommandRunner` is a `lazy val` that needs the
`lazy val templateDecoder`, which needs the companion's `lazy val packageSignatures`:

```
git show e74e9bf844:apps/app/src/main/scala/org/lfdecentralizedtrust/splice/environment/SpliceConsoleEnvironment.scala | sed -n '43,46p;60,63p;358,366p'
```
```
  private lazy val templateDecoder = new ResourceTemplateDecoder(
    SpliceConsoleEnvironment.packageSignatures,
    environment.loggerFactory,
  )
  lazy val httpCommandRunner: ConsoleHttpCommandRunner = new ConsoleHttpCommandRunner(
    environment.config.parameters.timeouts.console,
    environment.loggerFactory,
  )(this.tracer, templateDecoder, httpClient, environment.executionContext, actorSystem)
  private lazy val packageSignatures: Map[PackageId, PackageSignature] =
    ResourceTemplateDecoder.loadPackageSignaturesFromResources(
      DarResources.TokenStandard.allPackageResources.flatMap(_.all) ++
        DarResources.splitwell.all ++
        DarResources.validatorLifecycle.all ++
        DarResources.wallet.all ++
        DarResources.amulet.all ++
        DarResources.dsoGovernance.all
    )
```

## 3. Lead for the stall origin: the package signature load on the first console command

The same `loadPackageSignaturesFromResources` is what the apps log as `Loading package signatures from resources`
(`NodeBase.scala:109-114`); it took 30.9 s on a normal CI shard and 61.1 s on a slow one (11558 packet, section 3).
The console-side load is not logged, but in the artifacts of eight other refs the thread that issues the first
`Running on` line of the JVM is silent for 22 to 50 s before it:

```
for f in $(find log -name 'canton_network_test.clog.gz' | head -12); do echo "== $f"; zcat $f | awk -F'"' '{ m=match($0,/"thread_name":"[^"]*"/); th=substr($0,RSTART,RLENGTH); if ($0 ~ /"message":"Running on /) { print "prev same-thread line " last[th] " -> first Running on " $4; exit } last[th]=$4 }'; done
```
```
== log/11553/logs-wall-clock-time-7/canton_network_test.clog.gz
prev same-thread line 2026-11-17T19:33:38.053Z -> first Running on 2026-11-17T19:34:04.149Z
== log/11554/logs-simtime-0/canton_network_test.clog.gz
prev same-thread line 2026-11-17T20:12:38.384Z -> first Running on 2026-11-17T20:13:00.810Z
== log/11543/logs-simtime-3/canton_network_test.clog.gz
prev same-thread line 2026-11-17T16:15:50.848Z -> first Running on 2026-11-17T16:16:15.014Z
== log/11559/logs-simtime-4/canton_network_test.clog.gz
prev same-thread line 2026-11-18T09:17:03.281Z -> first Running on 2026-11-18T09:17:33.664Z
== log/11550/logs-frontend-wall-clock-time-2/canton_network_test.clog.gz
prev same-thread line 2026-11-17T18:57:46.184Z -> first Running on 2026-11-17T18:58:11.278Z
== log/11560/logs-simtime-0/canton_network_test.clog.gz
prev same-thread line 2026-11-18T09:17:32.087Z -> first Running on 2026-11-18T09:17:57.909Z
== log/11558/logs-frontend-wall-clock-time-2/canton_network_test.clog.gz
prev same-thread line 2026-11-18T05:22:54.821Z -> first Running on 2026-11-18T05:23:44.390Z
== log/11540/logs-simtime-0/canton_network_test.clog.gz
prev same-thread line 2026-11-17T15:34:00.532Z -> first Running on 2026-11-17T15:34:25.266Z
```

In those suites the first console command is part of the environment setup, outside any `eventually`. In the
preflight, compose and LocalNet frontend suites there are no local apps and no setup commands, so the first console
command is `amuletNameAcronym` inside the `tapAmulets` attempt, on a runner that is also running the compose
validator and participant and Firefox. It only costs the test when the first attempt that sees a Tap row sees only
the validator's own top-up tap: when the test's tap is already in that read, the long attempt still passes. Here
the top-up tap (10:51:37Z) was rendered one poll before the test's tap (10:51:44Z).

Not verified: that the 115.7 s were spent in this load (4x the app-side baseline; no thread dump or timing), and the
eight gaps above may contain other setup work. 10918 does not fit this lead: its stalled attempt read zero rows and
issued no console command. 10920 fits (stall between the amulet amount text and `GetSpliceInstanceNames`).

## Verdict

Duplicate of 10918 (family AT), third occurrence, first on a cluster preflight job (the first two were GHA
docker-compose and docker-no-canton shards of one splice run). Flake; the deployed cluster and the compose validator
worked. Fix location: test code. No fix written (duplicate). If the lead in section 3 is right, the smallest
test-side change is to resolve `amuletNameAcronym` once before the `eventually` in `tapAmulets` (and in the callers
of `readTapFromRow` / `readTransactionFromRow`), so the one-off load is outside the retry budget; the family's
general hardening (one more attempt in `FrontendTestCommon.eventually` when the deadline passed during an attempt)
would also have turned this run green. Not verified: where the test thread blocked.

Suggested action (not run): close 11561 as a duplicate of 10918.
