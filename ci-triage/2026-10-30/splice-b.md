# 2026-10-30 fork B: splice main scala_test singles (10912-10923)

Job logs in `log/b30/<ref>.log` (`gh api repos/canton-network/splice/actions/jobs/<job>/logs`), artifacts in
`log/b30/<ref>-art/`.

| Ref | Run / job id | Job | main sha |
|---|---|---|---|
| 10912 | 40896774918 / 123528652632 | wall-clock-time (8) | a48d720d0c |
| 10913 | 40909781995 / 123575157346 | wall-clock-time (6) | cf0a3a954c |
| 10916 | 40928477567 / 123641070043 | simtime (2) | d3be3a4304 |
| 10917 | 40936000355 / 123667073972 | docker-compose (1) | 5c67137b80 |
| 10918 | 40969391292 / 123782957691 | docker-compose (1) | 6d404e421b |
| 10919 | 40969391292 / 123783381549 | wall-clock-time (4) | 6d404e421b |
| 10920 | 40969391292 / 123783162222 | docker-no-canton (0) | 6d404e421b |
| 10921 | 40985878612 / 123837493953 | wall-clock-time (5) | dfe31a0ada |
| 10923 | 41006003116 / 123901672911 | wall-clock-time (4), PG14 nightly | dfe31a0ada |

## 1. 10921, 10923: PeriodicTopologySnapshot broken by the pekko-connectors-google 1.3.0 bump (#8017) - REAL

```
sed -E 's/\x1b\[[0-9;]*m//g' log/b30/10923.log | grep -a -A3 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | cut -c1-200
[info] - should produces a topology snapshot in the background *** FAILED ***
[info]   forAtLeast(1) failed, because no element satisfied the assertion block:
[info]     at index 0, "Starting node sv1" did not include substring "Took a new topology snapshot on 2026-10-30", and ...
```

The trigger never gets to the bucket:

```
zcat log/b30/10923-art/canton_network_test.clog.gz | grep -a 'PeriodicTopologySnapshotTrigger' | grep -a -E '"level":"(WARN|ERROR)"' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-330
2026-10-30T05:29:21.723Z Stream onboarding state to GCP bucket failed with an unknown exception, not retrying","logger_name":"o.l.s.s.a.PeriodicTopologySnapshotTrigger:...","level":"WARN","stack_trace":"com.typesafe.config.ConfigException$Missing: hardcoded value: No configuration setting found for key 'scopes'
2026-10-30T05:29:21.726Z Skipping processing of \nPeriodicTask(for = 2026-10-30T05:29:21.092341Z)\ndue to unexpected failure", ... "level":"ERROR"
```

Stack (filtered): `SimpleConfig.getStringList` <- `SequencerAdminConnection.streamOnboardingState(SequencerAdminConnection.scala:249)`
<- `PeriodicTopologySnapshotTrigger.takeTopologySnapshot(PeriodicTopologySnapshotTrigger.scala:108)`.

`SequencerAdminConnection.scala:249` is `Credentials.apply(ConfigFactory.parseMap(...))` for a
`GcpCredentialsConfig.ServiceAccount`; the map puts `"scopes"` **inside** `"service-account"`:

```
git show dfe31a0ada:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/SequencerAdminConnection.scala | sed -n 242,265p
            case cred @ GcpCredentialsConfig.ServiceAccount(_) =>
              ...
              Credentials.apply(
                ConfigFactory.parseMap(
                  Map[String, AnyRef](
                    "provider" -> "service-account",
                    "service-account" ->
                      Map[String, AnyRef](
                        "private-key" -> ...,
                        "client-email" -> x.getClientEmail,
                        "project-id" -> bucketConfig.projectId,
                        "scopes" -> scopes,
```

pekko-connectors-google-common changed where it reads `scopes` (javap of both jars from the coursier cache):

```
javap -p -c -constants 'org/apache/pekko/stream/connectors/google/auth/Credentials$.class'   # 1.3.0
  private ... parseServiceAccount(com.typesafe.config.Config, ...);
         4: ldc   // String scopes
         6: invokeinterface  Config.getStringList          <- top level of the passed config
        27: ldc   // String service-account
        29: invokeinterface  Config.getConfig
        36: invokevirtual    ServiceAccountCredentials$.apply(Config, Set, ...)
# 1.0.2: parseServiceAccount = getConfig("service-account") -> ServiceAccountCredentials$.apply(Config, ...),
#        and ServiceAccountCredentials$ reads "scopes" from that inner config (ldc String scopes at 237)
```

The version change:

```
git log --format='%h %ad %an %s' --date=iso -G 'pekko-connectors-google-cloud-storage' cn/main -- project/Dependencies.scala | head -1
24bfde02b2 2026-10-28 10:48:49 +0100 Raymond Roestenburg [ci] Bump auth0, commons-compress, pekko-connectors-google-cloud-storage, scala-parallel-collections and s3mock (#8017)
git show cn/release-line-0.9.x:project/Dependencies.scala | grep -n pekko-connectors-google
37:    "org.apache.pekko" %% "pekko-connectors-google-cloud-storage" % "1.3.0"      # backport #8073, 10-28
```

Why it is not red on every run: the trigger first checks whether today's snapshot already exists in the shared test
bucket and then succeeds without streaming ("Today's topology snapshot already exists."). The two failures are the
first runs of 2026-10-30 (01:26, 05:00 UTC); run 41025774246 (08:20) passed the suite. Inferred, not verified: a run
of a branch without the bump (a release line before #8073) wrote today's snapshot in between, so later main runs see
it and pass. Earlier days were masked by the GCS key rotation (family T, 10-29).

Impact outside CI: every SV with a `ServiceAccount` bucket config on a release containing #8017 (0.10.0, cut
2026-10-29, DevNet 2026-11-02; next 0.9.x patch via #8073) stops writing periodic topology snapshots.

Fix: put `"scopes" -> scopes` at the top level of the parsed map (keep it inside `service-account` too, harmless).
Verdict: real regression, parent 10921; 10923 dup. Owner: Raymond (#8017). Check other `Credentials.apply` callers:
`git grep -n '"service-account"' cn/main -- apps` finds only this one.

## 2. 10913: `Killed SBT after timeout 40m` after a classes cache miss (family 10358)

```
sed -E 's/\x1b\[[0-9;]*m//g' log/b30/10913.log | grep -a -E 'classes-10 branch:main|Killed SBT|Attempt 1 failed' | sed -E 's/^[^Z]*Z //'
  key: classes-10 branch:main dependencies:8fdc1e342e... rev:cf0a3a954c...
Killed SBT after timeout 40m
Attempt 1 failed with exit code 124, retrying
sed -E 's/\x1b\[[0-9;]*m//g' log/b30/10913.log | grep -a -E 'compiling [0-9]{3,} Scala' | cut -c1-110 | head -3
2026-10-29T16:23:08Z [info] compiling 264 Scala sources to /__w/splice/splice/canton/community/admin-api/target/
2026-10-29T16:24:09Z [info] compiling 1081 Scala sources and 1 Java source to /__w/splice/splice/canton/communit
```

From-scratch compile of canton + apps on the first run after #8102 (Maven dependencies, merged 15:58), last suite
still running at the kill. Dup of 10358.

## 3. 10916: scan `/state/acs/force` 500 on the ACS snapshot advisory lock (dup 10740)

```
zcat log/b30/10916-art/canton_network_test.clog.gz | grep -a 'TokenStandardMetadataTimeBased' | grep -a -E '"level":"ERROR"' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-230 | head -3
2026-10-29T18:41:12.164Z Request to http://127.0.0.1:5012/api/scan/v0/state/acs/force resulted in an unexpected exception: Failed to acquire session-scoped advisory lock 1936747523.
2026-10-29T18:41:12.177Z ... HTTP 500 Internal Server Error POST at '/api/scan/v0/state/acs/force' ...
2026-10-29T18:41:12.178Z Failed clue: (check) rounds are defined and include tapped amulet
```

Same signature as 10740 (TokenStandardV2TransferIntegrationTest, open, inbox). Dup of 10740.

## 4. 10912: ScanEventHistoryIntegrationTest, events after the pre-tap cursor (NEW, test race)

```
sed -E 's/\x1b\[[0-9;]*m//g' log/b30/10912.log | grep -a -A400 'should provide new events with verdicts \*\*\* FAILED' | grep -a 'ScanEventHistoryIntegrationTest.scala' | head -1 | sed -E 's/^[^Z]*Z //'
[info]   at org.lfdecentralizedtrust.splice.integration.tests.ScanEventHistoryIntegrationTest.$anonfun$new$3(ScanEventHistoryIntegrationTest.scala:65)
git show a48d720d0c:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/ScanEventHistoryIntegrationTest.scala | sed -n 59,65p
    val eventHistoryAfterLastCursor = sv1ScanBackend.getEventHistory(
      count = pageLimit,
      after = Some(cursorBeforeTap),
      encoding = CompactJson,
    )
    eventHistoryAfterLastCursor shouldBe empty withClue "EventHistory after pre-tap cursor"
```

The non-empty list contains a verdict plus created events with `licenseCid`, `couponCid`, `openRoundCid` for
splitwell-validator: background validator-reward/licence automation committed a transaction between reading the
cursor and listing after it. The "should be empty" check assumes a quiet ledger. Test-side fix: drop the emptiness
check, or pause the reward automation for the step. Passed in later runs of the same suite (e.g. 10919's shard,
23:18). New, open. Owner suggestion: Robert (verdict ingestion test).

## 5. 10919: ExpiryWithIgnoredAmuletVersionIntegrationTest, startup of all nodes > 2 min on a held DB lock (NEW)

```
zcat log/b30/10919-art/canton_network_test.clog.gz | grep -a 'DbLockNotAcquired' | grep -a -o 'IntegrationTest/config=[0-9a-f]*/[a-z]*=[A-Za-z0-9]*' | sort | uniq -c | sort -rn | head -3
    944 IntegrationTest/config=2a0abecd/validator=splitwellValidator
     90 IntegrationTest/config=bf6436/scan=sv1Scan
     90 IntegrationTest/config=f8a23a44/validator=splitwellValidator
zcat ... | grep -a 'config=2a0abecd' | grep -a -E 'did not complete|db-migration still' | ...
2026-10-29T23:24:01.261Z Task db-migration still not completed after 40 seconds. ... splitwell=providerSplitwellBackend / validator=splitwellValidator
2026-10-29T23:26:29.654Z CONSOLE_COMMAND_INTERNAL_ERROR ... TimeoutException: Task startup-of-all-nodes did not complete within 2 minutes.
```

The suite started 23:23:16.269, 0.3 s after AutoIgnoreUnresponsivePartiesWithPersistenceIntegrationTest finished;
that suite's splitwellValidator (config c483676d) still ran scheduled actions until 23:24:01, and the startup thread
dump also shows a ScanEventHistoryIntegrationTest providerSplitwellBackend thread. splitwellValidator and
providerSplitwellBackend of the new suite retried the DB lock for the whole 2 min (944 retries vs 15-90 normally).
Not chased further: which process held the lock until 23:26. New, open; needs an owner who knows the
"WithPersistence" teardown.

## 6. 10917, 10918, 10920: compose / LocalNet wallet frontends (NEW, intermittent)

- 10917 (5c67137b80): all tests pass, checkErrors flags one browser ERROR from the portfolio app:
  `Object(1) (SDK)/Failed to list contracts of interface #splice-api-token-transfer-instruction-v1:...:TransferInstruction`
  (`browser.*.DockerComposeValidatorFrontendIntegrationTest.frontend.0.1.log.gz`). Portfolio is a third-party image;
  #8130 bumped it to 1.14.0 at 02:26 on 10-30.
- 10918 (6d404e421b): `taps should have length 1` got `List()` (DockerComposeValidatorFrontendIntegrationTest.scala:263,
  "after restoring from backup"); the page dump at failure shows a `Balance Change (Tap)` row with the expected classes.
- 10920 (6d404e421b): LocalNetFrontendIntegrationTest, `forAtLeast(1)` over an empty tx list (WalletFrontendTestUtil.scala:67).

Not a deterministic regression: the next four post-merge runs after 5c67137b80 (40937960422, 40939540775,
40950738173, 40956318047) and the 10-30 runs (41025774246, 41033718573) are green. #8096 (compose nginx images) is
not merged. Root cause not found. 10917 open (portfolio, check after #8130); 10918 open as parent for 10920.
