# 11140 - BftScanConnectionIntegrationTest: aliceValidator's 60 s scan refresh WARNs about the stopped sv2Scan after the suppression ended (run 41441330497)

New (no match in `known-families.md`, `history/README-2026-09-08_2026-11-02.md` or the cn-test-failures export
since 2026-10-19). Test flake, test-side fix. main 1938e4170b, job wall-clock-time (9), Canton 3.7.1, 22 tests passed,
0 failed; checkErrors flagged one line: `Failed to connect to scan of Digital-Asset-Eng-2 (http://localhost:5112).`
at 12:15:04.072 from aliceValidator in `BftScanConnectionIntegrationTest` "init fast enough even if there are
unavailable scans". The test stops sv2Scan on purpose and suppresses this WARN only while aliceValidator starts and
onboards its user. aliceValidator's scan list refresh runs every 60 s (`scansRefreshInterval = 60s` in the test
config); its first refresh was at 12:14:04.066, the test body finished at 12:14:50, the teardown plugins took until
12:15:04.085, and the next refresh at 12:15:04.067 tried the still-stopped sv2Scan again, 18 ms before aliceValidator
was stopped.

- Run: https://github.com/canton-network/splice/actions/runs/41441330497, main 1938e4170b ("updating gcs proxy image
  (#8014)"), job 125264181558 `ci / scala_test_wall_clock_time / wall-clock-time (9)`, attempt 1,
  2026-11-02 12:02:59-12:44:31 UTC.
- Runtime canton: 3.7.1 (`nix/canton-sources.json` at 1938e4170b).
- Component: test (`apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/BftScanConnectionIntegrationTest.scala`).

## 1. Flagged lines

Tests passed; checkErrors flagged exactly one line in `canton_network_test.clog`.

```
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11140/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | grep -v 'ignore this line' | cut -c1-330
```
```
Found problems in log/canton_network_test.clog:
***"@timestamp":"2026-11-02T12:15:04.072Z","message":"Failed to connect to scan of Digital-Asset-Eng-2 (http://localhost:5112).","logger_name":"o.l.s.s.a.a.c.BftScanConnection$AllDsoScansBft:BftScanConnectionIntegrationTest/config=d62bc221/validator=aliceValidator","thread_name":"BftScanConnectionIntegrationTest-d62bc221-env-ec-
Total: 1 lines with problems.
```

## 2. Which test was running

The WARN is 1 s before the end of "init fast enough even if there are unavailable scans" (12:11:01 - 12:15:05).
The suite uses `IntegrationTestWithIsolatedEnvironment`, so the test's end includes its environment teardown.

```
L=log/11140/logs-wall-clock-time-9
zcat $L/canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '" | grep -a BftScanConnectionIntegrationTest | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-170
```
```
2026-11-02T12:11:01.292Z Starting test suite 'BftScanConnectionIntegrationTest'...",
2026-11-02T12:11:01.319Z Starting 'BftScanConnectionIntegrationTest/init fast enough even if there are unavailable scans'...",
2026-11-02T12:15:05.019Z Test succeeded: 'BftScanConnectionIntegrationTest/init fast enough even if there are unavailable scans'",
2026-11-02T12:15:05.021Z Starting 'BftScanConnectionIntegrationTest/agree on failed HttpCommandException'...",
2026-11-02T12:18:00.523Z Test succeeded: 'BftScanConnectionIntegrationTest/agree on failed HttpCommandException'",
2026-11-02T12:18:00.523Z Starting 'BftScanConnectionIntegrationTest/validator onboarding and recovery succeed with internal config turned on'...",
2026-11-02T12:19:16.861Z Test succeeded: 'BftScanConnectionIntegrationTest/validator onboarding and recovery succeed with internal config turned on'",
2026-11-02T12:19:16.861Z Starting 'BftScanConnectionIntegrationTest/validator reboots when initial bootstrap scan is offline'...",
2026-11-02T12:20:30.362Z Test succeeded: 'BftScanConnectionIntegrationTest/validator reboots when initial bootstrap scan is offline'",
```

## 3. The test stops sv2Scan and suppresses the WARN only around startup and onboarding

`:5112` is sv2Scan (`getSvName(2)` = Digital-Asset-Eng-2). The suppression block ends after `onboardUser`; the
`eventuallySucceeds` and the environment teardown run unsuppressed. aliceValidator refreshes its scan list every
60 s.

```
git show 1938e4170b:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/BftScanConnectionIntegrationTest.scala | sed -n '46,50p;80,100p'
```
```
          case (name, c) if name == "aliceValidator" =>
            val dbEnabledConfig = BftScanClientConfig.Bft(
              seedUrls = NonEmptyList.of(
                Uri("http://127.0.0.1:5012")
              ),
    // Alice's validator will see the two scans, but SV2's won't connect
    sv2ScanBackend.stop()
    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
      {
        aliceValidatorBackend.startSync()
        aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
      },
      forAll(_)(
        _.message should (include(
          s"Failed to connect to scan of ${getSvName(2)} (http://localhost:5112)."
        ) or
          include("Encountered 4 consecutive transient failures") or include(
            "Failed to connect to scan of FAILED Seed URL #0 (http://localhost:5112)."
          ))
      ),
    )

    eventuallySucceeds() {
      aliceAnsExternalClient.listAnsEntries()
    }
  }
```
(Line 51 of the same file, not shown by the sed: `scansRefreshInterval = NonNegativeFiniteDuration.ofSeconds(60),`.)

## 4. Refresh timeline: two suppressed failures, then the 60 s refresh after the block

The refresh at 12:14:04.066 (startup) and the bootstrap refresh at 12:14:40.050 were suppressed. The next periodic
refresh, 60.001 s after the first, logged the WARN unsuppressed.

```
zcat $L/canton_network_test.clog.gz | grep -a 'config=d62bc221/validator=aliceValidator' | grep -a -E 'Failed to connect to scan of|Started refreshing scan list' | sed -E 's/\{"@timestamp":"([^"]+)","message":"([^"]*)".*"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/\1 \4 [\3] \2/; s/BftScanConnectionIntegrationTest\/config=d62bc221\/?//' | cut -c1-150
```
```
2026-11-02T12:14:04.066Z INFO [o.l.s.s.a.a.c.BftScanConnection$AllDsoScansBft:validator=aliceValidator] Started refreshing scan list from BftState(Map
2026-11-02T12:14:04.129Z INFO [o.l.s.s.a.a.c.BftScanConnection$AllDsoScansBft:validator=aliceValidator] Suppressed WARN: Failed to connect to scan of 
2026-11-02T12:14:40.050Z INFO [o.l.s.s.a.a.c.BftScanConnection$AllDsoScansBft:validator=aliceValidator] Started refreshing scan list from BftState(Map
2026-11-02T12:14:40.053Z INFO [o.l.s.s.a.a.c.BftScanConnection$AllDsoScansBft:validator=aliceValidator] Suppressed WARN: Failed to connect to scan of 
2026-11-02T12:15:04.067Z INFO [o.l.s.s.a.a.c.BftScanConnection$AllDsoScansBft:validator=aliceValidator] Started refreshing scan list from BftState(Map
2026-11-02T12:15:04.072Z WARN [o.l.s.s.a.a.c.BftScanConnection$AllDsoScansBft:validator=aliceValidator] Failed to connect to scan of Digital-Asset-Eng
```

## 5. The test body ended at 12:14:50, teardown plugins ran 13.5 s, aliceValidator stopped 18 ms after the WARN

aliceValidator finished initialization at 12:14:44.564; the (successful) `listAnsEntries` ran at 12:14:50.421.
The environment teardown then ran the TokenStandardCliSanityCheckPlugin from 12:14:50.552 to past 12:15:04,
and stopped aliceValidator at 12:15:04.085/.087, after the refresh at 12:15:04.067.

```
zcat $L/canton_network_test.clog.gz | grep -a -E 'aliceValidator app initialization: Initialization complete|Running on aliceAns command ListAnsEntries|Stopping node aliceValidator|Stopping aliceValidator node' | grep -a -E 'T12:1[45]' | sed -E 's/\{"@timestamp":"([^"]+)","message":"([^"]*)".*"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/\1 \4 [\3] \2/; s/BftScanConnectionIntegrationTest\/config=d62bc221\/?//' | cut -c1-170
```
```
2026-11-02T12:14:44.564Z INFO [o.l.s.v.ValidatorApp:validator=aliceValidator] aliceValidator app initialization: Initialization complete, running on version 0.10.1-ci-sna
2026-11-02T12:14:50.421Z DEBUG [o.l.s.c.ConsoleHttpCommandRunner:] Running on aliceAns command ListAnsEntries() against NetworkAppClientConfig(http://127.0.0.1:5503,None,
2026-11-02T12:15:04.085Z INFO [o.l.s.e.ValidatorApps:] Stopping node aliceValidator
2026-11-02T12:15:04.087Z INFO [o.l.s.v.ValidatorApp:validator=aliceValidator] Stopping aliceValidator node
2026-11-02T12:15:04.903Z INFO [o.l.s.e.ValidatorApps:] Stopping node aliceValidator
```

Teardown plugins between the test body and the node stops (first line per plugin):

```
zcat $L/canton_network_test.clog.gz | grep -a -E 'T12:(14:5|15:0[0-4])' | grep -a -E '"logger_name":"o\.l\.s\.i\.p\.' | sed -E 's/\{"@timestamp":"([^"]+)","message":"([^"]*)".*"logger_name":"([^":]*)[^"]*".*/\1 [\3] \2/' | awk '{if ($2!=last) print; last=$2}' | cut -c1-150
```
```
2026-11-02T12:14:50.552Z [o.l.s.i.p.TokenStandardCliSanityCheckPlugin] Token-standard CLI not running for bob_validator_wallet_user-d62bc221 because e
2026-11-02T12:15:04.241Z [o.l.s.i.p.ResetDecentralizedNamespace] Resetting decentralized namespace to contain only sv1
2026-11-02T12:15:04.940Z [o.l.s.i.p.ResetSequencerSynchronizerStateThreshold] Resetting sequencer domain state threshold to 1
```

## 6. No ignore pattern covers this suite; nothing changed recently

Seven other suites that stop scans on purpose have a suite-scoped ignore for this WARN; BftScanConnectionIntegrationTest
relies on its in-test suppression. The test file was last changed on 2026-08-05 (#6677); no ignore-pattern change since
then touches this line, and main has not changed either file since 1938e4170b.

```
grep -n 'Failed to connect to scan of' project/ignore-patterns/canton_network_test_log.ignore.txt
git log --format='%h %ad %s' --date=short -1 1938e4170b -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/BftScanConnectionIntegrationTest.scala
```
```
111:Failed to connect to scan of Digital-Asset-Eng-3.*AppUpgradeIntegrationTest
114:Failed to connect to scan of .*DisasterRecoveryIntegrationTest
115:Failed to connect to scan of .*AppUpgradeIntegrationTest
116:Failed to connect to scan of .*ConfigurationProvidedBftScanConnectionIntegrationTest
119:Failed to connect to scan of .*SvDevNetReonboardingIntegrationTest
156:Failed to connect to scan of FAILED Seed URL.*RollForwardLsu.*IntegrationTest
186:Failed to connect to scan of.*NonZeroRoundBootstrapBftTimeBasedIntegrationTest
00b619ac58 2026-08-05 Demote `Failed to read bft sequencers list from scan` to INFO (#6677)
```

## Verdict

New, family AB. Test flake (timing race), fix belongs in the test. The WARN is the expected consequence of the test's
own `sv2ScanBackend.stop()`; it escapes because the suppression scope ends at `onboardUser`, while aliceValidator
keeps retrying sv2Scan every 60 s until the environment is torn down. It fails checkErrors whenever startup of
aliceValidator + `onboardUser` + `listAnsEntries` + teardown plugins (here 46 s + 13.5 s) exceed one refresh
interval; this run missed by 18 ms, so most runs pass.

Fix candidates (test-side, not written, not compiled):
- Preferred: keep sv2Scan's absence inside a suppression until aliceValidator stops, e.g. end the test with
  `loggerFactory.suppressWarningsAndErrors(aliceValidatorBackend.stop())` after the `eventuallySucceeds`, and move
  the `eventuallySucceeds` into the `assertEventuallyLogsSeq` block. Alternative: restart sv2Scan
  (`sv2ScanBackend.startSync()`) at the end of the test, inside the suppression block.
- Not recommended: a suite-scoped ignore `Failed to connect to scan of .*BftScanConnectionIntegrationTest` (like the
  seven siblings), since the suite's other tests would then hide real connection failures.

Not verified: how often this hits (only this occurrence found in the tracker export since 2026-10-19 and in the
2026-09/10 triage notes); whether stopping aliceValidator inside the suppression logs other WARNs.
