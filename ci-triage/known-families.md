# Known flake families (2026-09 state). Check these before any deep analysis.

Format: signature to grep | confirming check | mechanism | parent ref and duplicates | fix state.

## A. ACS_COMMITMENT_MISMATCH sv1Participant vs aliceValidator after a multi-host step
- Signature (canton log WARN): `ReceivedAcsCommitmentMatcher:participant=sv1Participant ... ACS_COMMITMENT_MISMATCH ... sender = aliceValidator`.
- Confirm: `zcat canton_network_test.clog.gz | grep -a -E "Starting test suite|Multi-host alice"`; the mismatched
  period's `fromExclusive` is 1-16 s after a `Multi-host alice on sv1Participant` clue (ExpiryWithMinimalVettedPackages
  base suites: AmuletExpiryV1Fallback, ExpiryWithIgnoredAmuletVersion, ExpiryWithNoVettedAmuletVersion; and
  AutoIgnoreUnresponsiveParties*). The WARN lands 0-27 min later in an unrelated suite (random send delay).
- Mechanism: the tests add sv1Participant as a host of alice's wallet party after she owns contracts, with no
  ACS import; the two hosts genuinely disagree. Test issue, not product. Detected since 2026-09-10 only; the old
  processor also resolved hosting at tick time and the 3.6 pipeline ran unnoticed from 08-21, so the detection change is
  a Canton-side unknown (10146 packet, correction 2026-09-21). Fixed by PR 7435 (merged 2026-09-21).
- Parent 10111 (run 34523566111); dups 10129, 10146, 10155, 10158, 10162, 10164, 10167, 10178 (PG14 nightly), 10182.
- Fix: `ray/fix-multihost-acs-mismatch` (allocate alice's party hosted on both participants before onboarding).
  Do not widen `canton_log.ignore.txt:145`.

## B. BFT 1 -> N sequencer onboarding step with unauthenticated newcomers (quorum loss, blacklisting)
- Signatures: (1) `acknowledge-signed ... DEADLINE_EXCEEDED after 119.99s` + `Failed to acknowledge clean timestamp`
  (participant or mediator client, WARN); (2) SV app WARN `POST /api/sv/v0/onboard/sv/sequencer ... timeout after 38 seconds`.
- Confirm on globalSequencerSv1: `New epoch N has started with leaders = [sv1, sv2, ...]` with `size` stepping 1 -> 3/4,
  preceded within a second by `Authenticated P2P nodes count ... 1 is currently below weak quorum size 2`, then
  `blacklisted nodes = List(SEQ::sv1...)` (or the newcomer) for ~3 epochs; the ack `received a message` on the
  sequencer, `MempoolModule: P2P connectivity is not ready`, `cancelled` then `sending response` ~120 s later.
- Mechanism: Canton activates the new ordering topology at the epoch boundary regardless of P2P authentication.
  Not fixed in digital-asset/canton main as of the 2026-09-15.22 mirror. Splice mitigations: serialise SV
  sequencer onboardings, or gate on P2P authentication; for (2) a non-blocking onboard handler or a
  `custom-timeouts` entry for `onboardSvSequencer`, or extend the `onboard/validator` timeout ignore.
- Umbrella 10165 (10094 and 10153 closed as dups; 10161 same). Occurs during any initDso with 3-4 SVs
  (ValidatorIntegrationTest, SvOnboardingIntegrationTest, SvDsoPartyManagementIntegrationTest,
  DistributedDomainIntegrationTest, ValidatorSequencerConnectionIntegrationTest).
- 10212 (run 35873276062, wall-clock-time (1), canton 3.6.0-snapshot.20260916.20284): signature (1) on
  sv1Participant during ValidatorSequencerConnectionIntegrationTest setup. sv2-sv4 call `onboard/sv/start`
  within 0.4 s, so the topology steps 1 -> 4 in one change (epoch 90); sv1 blacklisted epochs 91-93. The stuck
  ack's clean timestamp is 1 us before the new topology's activationTime.
- 10225 (run 36148619987, wall-clock-time (3), canton 3.6.0-snapshot.20260916.20284): signature (1) from
  ValidatorSequencerConnectionIntegrationTest's 4-SV initDso. The mempool rejected the mediator's ack within
  1 ms (`P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting`), but the
  AcknowledgeSigned call stayed open until the 120 s client deadline (`cancelled`, then `sending response`
  114 ms later): the 120 s timeout comes from the rejection not completing the call, not from a 120 s outage
  (rejections lasted 15:42:14.206-15:42:18.110).
- Signature (3): a participant's topology broadcast is refused by the blacklisted sequencer and the party never
  reaches that synchronizer, surfacing as `INVALID_PRESCRIBED_SYNCHRONIZER_ID(9,...): Not all informees are on the
  specified synchronizer: <target>, but on Set(<other synchronizer>)` on a wallet/app-install command. 10227
  (run 36160174141, wall-clock-time (9), canton 3.6.0-snapshot.20260925.20321). First hard test failure in this
  family; the earlier three symptoms are checkErrors WARNs.
- Second Canton gap found via 10227, worth reporting separately: the topology-broadcast path
  (`SequencerBasedRegisterTopologyTransactionHandle` / `StoreBasedSynchronizerOutbox`) does NOT fail over to
  another sequencer connection on a refused send, although `RichSequencerClientImpl` retries an ordinary send on a
  new connection in the same millisecond (`Retry has not been configured for GrpcRequestRefusedByServer, giving
  up.`). `SequencerConnectionPoolImpl` keeps returning the blacklisted node, so each 10 s outbox flush can draw it
  again and a transient blacklist becomes a ~30 s stall of all topology dispatch for that participant.
  Confirming grep: pair `returning Set(sequencer-connection-SEQ::svN` with the next `was refused by SEQ::svN ...
  because it is overloaded` and check whether a second `returning Set(...)` for the SAME message id follows
  (ordinary send) or a `Failed broadcasting topology transactions` does (broadcast).

## C. False off-boarding conclusion during onboarding state transfer (10048 family) - FIXED
- Signatures: `Received topology for epoch N, but this node isn't part of it (i.e., it has been off-boarded)` on a
  newly onboarded sequencer; downstream: initDso timeouts (10137), `Failed to fetch P2P server authentication token
  ... Member SEQ::svN access is disabled` (10010: the frozen node judges peers against its stale topology).
- Fixed by DACH-NY/canton#35600: present in 3.5.17+, and 3.6 snapshots from 20260910 on (markers
  `Might already have all necessary blocks for new epoch`, `Detected need for catch-up state transfer (to `).
  Absent in 3.5.16 and 20260909.20244. No supported line is exposed (0.8.0 unsupported). Do not add ignores.
- Beware: `isn't part of it` also appears legitimately when a test really off-boards a sequencer
  (canton-standalone-sv123-non-sv1-svs has its own ignore file).

## D. Simulated time jump with a command in flight (round-opening waits)
- Signature: `Check waiting for open round automation (should create OpenMiningRound N) ... (a, b, c) was not equal to (a+1, b+1, c+1)`
  (TimeTestUtil.scala) in simtime shards; also `Domain time delay is currently 10m ... waiting until delay is below 2 minutes` (sv1).
- Confirm: `Advancing sim clock to <T>` followed within 20 ms by `MAX_SEQUENCING_TIME_EXCEEDED` on the sequencer,
  `Task scheduler waits for tick of sc=...` on sv1Participant, and a `Received TimeProof(<pre-jump time>)`; then no
  `Validating event` on sv1Participant until ~30 s later.
- Mechanism: the sequencer drops the in-flight message, the participant blocks until its 30 s wall-clock timeout,
  domain time stays behind, SV automation pauses. #5779 fixed `advanceTimeAndWaitForRoundAutomation` (90 s);
  `advanceTimeAndWaitForRoundOpening` was left at 20 s.
- Parent 9740 (2026-08-19), dup 10170. Fix: `ray/fix-round-opening-wait-budget`.
- Sibling: `advanceTimeAndWaitForRoundAutomation` failing with rounds one too far ((7, 7, 8, 9) vs (6, 6, 7, 8)) right
  after a multi-hour `advanceTime`: the round automation is still catching up a backlog when the next helper snapshots.
  UnhideAndExpireRewardCouponV2TimeBasedIntegrationTest (37 h coupon TTL jump) = splice #7206 / 10173; fix
  `ray/fix-10173-unhide-expire-coupon-ttl` (short TTL + advanceRoundsUntil). Grep: `advancing time by PT[0-9]+H` followed
  by `successfully advanced the rounds` lines during the next `(act) advancing time` clue.
- Sibling: `advanceTime(PT25H)` then `LOCAL_VERDICT_INACTIVE_CONTRACTS` on transfer-preapproval send in
  WalletMintingDelegationTimeBasedIntegrationTest = 10060 / splice #7223, fixed on main by #7261; release lines
  need the backport (10154, 10166, 10171; `ray/backport-7261-release-line-0.8.3`).
  9929 is the same failure on main before #7261 (79e56f457f, 2026-09-02). Confirming grep: decode the rejection's
  `grpc-status-details-bin` for the CONTRACT_ID; it is an IssuingMiningRound that `ExpireIssuingMiningRoundTrigger`
  closes within 2 s of the PT25H jump.

## E. Missing backports to release lines (check first for any release-line-* failure)
- #7261 (minting delegation time jump), #7305 (per-port Vite deps cache, 10156 / 9704), #7304 (wallet allocation
  UI test, 10157 / 10120), #7299 (package downgrade in UnsupportedPackageVettingIntegrationTest, 10169 / 9965).
- Check: `git merge-base --is-ancestor <sha> origin/<line>`; `git log origin/<line>..origin/main -- <test file>`.

## F. Frontend test infrastructure
- Vite dev servers for alice/bob/charlie splitwell run `vite --force` from one directory; without #7305 they race
  on `node_modules/.vite/deps` (`ENOENT ... rename ... deps_temp` in `npm-splitwell-*.out`, Firefox
  `disallowed MIME type ("")` for every module, empty `<div id="root">`). 9704 / 10156.
- testing-library 1 s `findBy` budgets versus the 15 s vitest budget: `navigateToLegacyGovernancePage` (10145),
  un-awaited `waitFor` in set-amulet-rules-form.test.tsx (10141). Fix: `ray/fix-sv-ui-test-timeouts`.
- Describe-level `}, 7500)` in wallet.test.tsx makes near-limit tests fail on slow runners (10157 B).
- Auth0 login stall on the second login in WalletAuth0FrontendIntegrationTest (10143): only the first login had the
  retry wrapper. Fix: `ray/fix-auth0-relogin-retry`.

## G. "All tests pass, one log line fails checkErrors" items still without a fix
- 10084 IndexerState reconnect-drain WARN (Canton `retryLogLevel`; ignore rejected).
- 10140 `SERVER_OVERLOADED` on DownloadTopologyStateForInit: test-only limit 3 in `sequencers.conf`; fix
  `ray/fix-topology-init-limit` (3 -> 7).
- 10144 GetPreferredPackages INTERNAL while a DAR upload merges into the package metadata view (Canton race,
  cn-test-failures 9136).
- 10147 `[UNEXPECTED] State transition ... Connecting (unchanged)` WARN at standalone shutdown (Canton log level).
- 10121 / 10142 SummarizingMiningRoundTrigger ERROR on a retryable "totals not yet computed"
  (`ray/fix-summarizing-round-log-noise`).

## H. Evidence loss
- `ResetTopologyStatePlugin` `sys.exit(1)` in teardown (10137, 10139): no report, checkErrors skipped, later
  suites lost. Fix: `ray/fix-reset-topology-plugin-no-exit`.
  Third hit (run 35611022158 wall-clock-time (2), ref 10183): the fourth owner's reset proposal arrived after
  three signatures had already authorized owners = {sv1}; `TOPOLOGY_NO_APPROPRIATE_SIGNING_KEY_IN_STORE`, then 15
  zero-delay restarts inside the 250 ms effective delay all hit `TOPOLOGY_MAPPING_ALREADY_EXISTS`. Confirming grep:
  `zcat canton_network_test.clog.gz | grep -a -c 'Restarting decentralized namespace reset'` = 16 within 100 ms.
  Fix: `ray/fix-10183-reset-namespace-late-proposer` (tolerate the late proposer; wait loop decides).
- `NodeBase` `sys.exit(1)` on init failure in the shared sbt JVM: silent 60-minute hang recorded as cancelled
  (10088-A, #7289; branch `ray/fix-fail-fast-init`). Second hit 10180 (LSU shard, bobValidatorLocal init). Signature:
  GH conclusion cancelled, `Received SIGINT` at start+60 min, no ScalaTest summary, test log ends on
  `app initialization: Initialization failed`, canton logs continue. Confirming grep: `zcat canton_network_test.clog.gz | tail -1`.
- 10139 cause: reference block sequencer `insert block` SQLSTATE 40001 retry storm with four sequencers on one
  Postgres until the 8-connection pools are exhausted (Canton / topology size).
- Teardown leak cascade (10176; July run 28921009132): an exception from any plugin's `beforeEnvironmentDestroyed`
  skips `environment.close()` (vendored `EnvironmentSetup.manualDestroyEnvironment` runs the hook outside its `try`;
  canton main unchanged 2026-09-15.22). Signature: one teardown failure, then every later test fails at `Creating
  fixture` with `Could not create Prometheus HTTP server` / `Address already in use` (:25000) and shared-environment
  suites abort with zero tests. Confirming grep: the old environment's `config=<id>` keeps logging after the failure.
  10235 (wall-clock-time (9), run 36544106177): trigger was `UpdateHistorySanityCheckPlugin` calling `.automation` on
  a scan that was started but never initialized; its `is_initialized` filter only checks that the node object exists.
  Fix `s11/fix-10235-sanity-check-skip-uninitialized-scans` (filter on `Try(scan.appState).isSuccess`).

## H2. LSU: validator init against a non-active psid
- 10088-B (5-min hang, infinite retry) -> fixed by #7311 (WARN + skip). 10174: the WARN fails checkErrors because #7311's
  ignore regex `... active (status: .*), ...` has unescaped parentheses (ripgrep group), fix `ray/fix-10174-lsu-source-ignore-regex`.
  Mechanism: a validator restarted after the LSU uses the participant's registered (stale) psid; Canton rejects the
  modify; on retry the participant reports LSU_SOURCE. Rare on main (1 of 40 runs). Design note: prefer the active psid.
- 10180 (variant C): same restart, the OTK rotation check `listAllTransactions(Synchronizer(logical id))` in
  `NodeInitializer.rotateOwnerToKeyMappingNotSignedByKeys` is not wrapped in `retryProvider.retry`; between the LSU
  target registration (36-0 deactivated) and 36-2 reaching Ok (~470 ms) the participant answers
  `TOPOLOGY_STORE_NOT_FOUND: No active synchronizer found` and the validator exits (family H). App fix: retry it like
  `findOwnerToKeyMappingThatUsesNamespaceSigningKey` does, NOT_FOUND is already in `retryableStatusCodes`.
- Checking an ignore pattern: `LINE=$(zcat ... | grep -a -m1 '<text>'); echo "$LINE" | rg -c -e '<pattern>'`.

## H3. Trigger pause timeout: `Waited 5 seconds. (TriggerTestUtil.scala:93)`
- `setTriggersWithin` pauses with `pause().futureValue` (5 s). `PollingTrigger.pause()` waits for the running task, so a
  task that is retrying a "retryable" ledger error (deadline-exceeded, CONTRACT_NOT_FOUND) blocks the pause. Confirming
  grep: the trigger's `failed with a retryable error` / `Retrying after a number of N failures` lines in the 5 s window.
- 10175 (7864/#5176 lineage): UnclaimedActivityRecordIntegrationTest resumed alice's merge trigger for 1 ms between two
  blocks after the record's 10 s expiry had passed. Fix: pause across both blocks (outer setTriggersWithin), never a
  bigger expiry margin. Also check `actAndCheck`'s 5 s max poll interval when a block "takes too long".
- 10176: same timeout from `UpdateHistorySanityCheckPlugin.beforeEnvironmentDestroyed` pausing a scan's AcsSnapshotTrigger
  whose `UpdateIncrementalSnapshotTask` transaction took 10.67 s to complete after 1 ms of SQL (Postgres commit
  latency outlier on the runner; p99 350 ms in that shard vs 10-30 ms elsewhere). No retry lines: the confirming
  grep is the gap between the store's `Updated incremental snapshot` and the trigger's `Completed processing` for
  the scan named first in `Checking update histories for List(...)`. Fix `ray/fix-10176-sanity-check-pause-timeout`
  (1 min pause budget in the plugin); the cascade itself is family H.

## I. Test races (venue and splitwell)
- 8784: settlement venue submits `OTCTrade_Settle` before its participant ingested the AmuletAllocation contracts
  (`CONTRACT_NOT_FOUND`). PR #6013's wait compared two unrelated codegen ContractId classes and could never
  match. Fix: `ray/fix-venue-allocation-wait` (compare `.contractId` strings).
- 10149: BulkStorageCommitFromStagingTest re-stubs Mockito mocks while the flow calls them
  (`ClassCastException` Promise -> Uri, then `expectNext(20 s)` timeout). Fix: `ray/fix-bulk-storage-test-stubbing-race`.
- 10179: MemberTrafficIntegrationTest compares the participant admin API's traffic view (lags until the receipt of
  the last submission is processed, ~80 ms) with Scan's sequencer admin view taken 27 ms later; alice's validator
  automation submits 2-3 times a second during the suite. Confirming grep: `TrafficConsumedManager ... Consumed N for
  PAR::aliceValidator` with a sequencing timestamp between the two `TrafficControlState` responses. Fix
  `ray/fix-10179-member-traffic-status-consistent-read` (compare inside `eventually()`).


## K. Sim time: mediator pruning scheduler backoff after a jump of at least the retention period
- Signature (canton-simtime log WARN): `MediatorPruningScheduler ... Backing off 1s or until next window after error: Requested
  pruning timestamp [T] is later than the earliest available pruning timestamp [T - 30 s]`, all tests pass.
- Mechanism (10184 (simtime (2))): the SV app installs a 10-minute cron with 30 d retention on its mediator; the
  scheduler prunes up to `clock.now - retention`; a sim-time jump >= 30 d puts that at the pre-jump clean timestamp, and
  `Mediator.prune` refuses anything above `clean - confirmationResponseTimeout` (30 s) until the mediator sees a
  post-jump event. Confirming grep: `advancing time by PT720H` (or longer) in the test log within a second before the WARN,
  and the other mediators logging `Pruned up to <same T>` a few seconds later.
- Fix: sim-time-only ignore in `canton_log_simtime_extra.ignore.txt` (`ray/fix-10184-mediator-pruning-backoff-ignore`);
  do not put it in `canton_log.ignore.txt`, in production the line means the mediator lags the clock by > retention.

## L. Reference sequencer `insert block` SQLSTATE 40001 retry storm (slow ordering, not only teardown exits)
- Signature: a ledger command or confirmation takes 10-25 s; participants log `timeout-result has not completed after
  N milliseconds` / `succeed successfully but slow`; on the sequencer, `enqueued reference sequencer store request` is
  followed by `Created batch reference-driver-requests-batch` many seconds later, and `DbStorageSingle` logs
  `The operation 'insert block' has failed ... Retrying after` with growing backoff and `SQL state: 40001`.
- Confirm: pair first `enqueued ... tag send` with the next `Created batch` on globalSequencerSv1 and list waits over
  5 s; grep the `insert block` retry chain in that window. Block production (`Processing block`) keeps running.
- Cause: several reference sequencers (4 global + 2 splitwell) store serializable block inserts in one Postgres; the
  test configs run the reference driver in both wall-clock and sim-time shards (`sequencers.conf:33`).
- Occurrences: 10139 (wall-clock nightly, 27 s gap, then ResetTopologyStatePlugin exit), 10197 (simtime, 19.6 s wait,
  20 s test budget). 10256 (simtime (1), canton 3.6.0-snapshot.20260929, 17.8 s without a global block, backoff
  to 5.583 s): surfaces as `INVALID_PRESCRIBED_SYNCHRONIZER_ID ... but on Set(splitwell...)` on wallet onboarding, like 10227
  (family B); tell them apart by the global synchronizer's driver (reference `insert block` retries vs BFT blacklisting). 3.5.17/3.5.18 sim-time runs show the same storm with backoff under 0.4 s; 10197 reached 8.6 s.
- Fixes: per-test budgets where a check depends on one ordering round trip (`ray/fix-10197-bft-read-confirmation-wait`);
  the contention itself is Canton / test infra (fewer writers per DB, non-serializable insert, capped backoff).

## J. Infra, no code change
- 10133 ghcr.io pull i/o timeout.
- Multi-arch image check (deployment_test, `scripts/check-multiarch-images.py`): one skopeo answer decides. 10150 = Docker Hub
  502 (`unable to inspect ... 502` on stderr); 10172 = no stderr line, response without a `manifests` list for a digest
  that is an index. Confirm with the registry probe in the 10172 packet. Fix: `ray/fix-multiarch-check-retry`.
- 10185-10193, 10195 (run 35613990408, release-line-0.8.x, 2026-09-21 14:52-14:57 UTC): eleven shards die in nix flake
  setup, `unable to download https://github.com/nix-systems/default/archive/<rev>.tar.gz: HTTP error 504` after
  cache misses in the runner binary cache and cache.nixos.org. Signature: no `Tests:` line, failure within 2-6 min of
  job start, every shard of the run at once. Fix: rerun failed jobs; ask runner owners to cache the flake inputs.
- 10204 (run 35740669383, main 57ed1c31b3, resource-intensive (0), GH conclusion cancelled): one degraded runner pod.
  Signature: `Timeout: Canton instance(s) failed to start within 300 seconds` in "Wait for Canton to be ready", `Run tests`
  skipped, run has zero artifacts. Confirm: compare step durations with a sibling shard of the same run
  (`##[end-action ...;duration_ms=`); here Set up SBT 1868 s vs 51 s, Restore precompiled classes 1071 s vs 23 s.
  Not family H: there tests run and SIGINT lands at +60 min. Logs lost because `upload_logs` "Sanitize filenames"
  failed in the container hook and the default `success()` skipped the uploads; fix
  `s11/fix-10204-upload-logs-after-sanitize-failure`. Resolution: rerun.
- 10249 (CircleCI deploy_basic build 507404, cimain, splice 6b4c166b71): one GKE node CPU-saturated after a scale-up
  (three validator-apps, splitwell-app, sv-da-1 sequencer and mediator); init on that node 25-35x slower. Signature:
  Helm `validator1/validator-validator1 ... context deadline exceeded` on every attempt, validator-app log
  `Getting BFT scan connection started` then 13-21 min silence, then `Timeout while waiting for initialization`.
  Confirm: per-node `kubernetes.io/node/cpu/allocatable_utilization` and the `Scheduled` events for validator-app pods
  (queries in the packet). Resolution: rerun; bootstrap.sc 5 min DAR-upload wait turns slow init into a restart loop.
- 10248 (not infra, listed here as a build-output check item): `Found problems in the sbt output:` with only
  ``[info] Set `VITE_CONFIG_NATIVE_IGNORE_WARNING=true` to suppress this warning.``; vite >= 8.2.0 advisory for CommonJS
  globals in a vite config. Fix the config (`import.meta.dirname`), do not ignore the line. Fix `s11/fix-10248-vite-config-import-meta-dirname`.

## M. State leaking between suites of one shard through a shared Postgres table
- Signature: an assertion that a store is empty (or has an exact size) fails with an entity whose embedded test
  config id is NOT the failing suite's. Splice test parties carry the config id: `alice__wallet__user-<config>`.
  Compare it against the failing suite's own `config=` in the logger name before anything else.
- Confirm: `zcat canton_network_test.clog.gz | grep -aoE '"logger_name":"[^"]*IntegrationTest/config=[0-9a-f]{8}' |
  sed -E 's/.*:([A-Za-z0-9]+IntegrationTest)\/config=([0-9a-f]{8})/\2 \1/' | sort -u` maps config ids to suites;
  then check that no write in the failing suite's own window ever names that entity. One Flyway migration line for
  the table in the whole log means one database per shard, shared by every suite in it.
- 10214 (run 35876878745, wall-clock-time (7)): `dso_unavailable_parties`.
  `DbUnavailablePartiesStore.listPartiesAt` has no `store_id` predicate although `store_id` is written on insert
  and used by `removePartiesUpToStoreId`; `removeParties` deletes `where party = any(...)`, unscoped in the other
  direction. ExpiryWithNoVettedAmuletVersionIntegrationTest leaves its party with the default 10 min ignore
  duration, and AutoIgnoreUnresponsivePartiesWithPersistenceIntegrationTest runs entirely inside that window.
- Deterministic, not a flake, whenever the shard split co-locates the two suites: check the `cmd:` line of the job
  log for the shard's suite order before calling it timing-dependent.
- Do not fix by weakening the assertion or cleaning the table in the test; that hides an unscoped production query.
- Expect the objection "but `cleanDb()` truncates every table between tests". It does, and `resetAllAppTables` in
  `SpliceDbTest` does list this table, but `cleanDb` is a hook on canton's `DbTest` trait that only the store UNIT
  tests mix in. The integration bases (`IntegrationTest`, `IntegrationTestWithIsolatedEnvironment` in
  `SpliceTests.scala`) do not. Settle it empirically rather than by reading the class hierarchy:
  `zcat canton_network_test.clog.gz | grep -ac 'Resetting all Splice app database tables'` was 0 for a shard of
  nine suites, and the table's Flyway migration line occurs exactly once. Integration suites isolate by building a
  new environment (new config id, new DSO party), not by truncating app tables.

## N. Per-table ACS snapshots (#6515, cc4539a9ac, from 2026-09-29): scan regressions
- Snapshot visible before its indexes exist: tests run with `perAcsSnapshotTablesEnabled`; the snapshot-timestamp
  endpoints (`getDateOfMostRecentSnapshotBefore`, `getDateOfFirstSnapshotAfter`) return 404 for a snapshot whose
  `indexes_created` is still false (no fallback to an older indexed one) until `AcsSnapshotIndexTrigger` indexes it,
  0.3-1 s after "Saved incremental snapshot". 10236 (+ 10237, 10241, 10242): ScanTimeBasedIntegrationTest "snapshotting",
  `.value` on None at line 254, docker-canton-simtime (0); 4 of 5 runs since #6515, 0 of 7 before. Confirming grep:
  `Saved incremental snapshot at <T>` followed within 1 s by a snapshot-timestamp `No snapshots found` 404 and then
  `Successfully indexed tables of snapshot <T>`. Fix `s11/fix-10236-scan-snapshot-before-skips-unindexed` (the
  snapshot-before endpoints filter `indexes_created` and serve the latest indexed snapshot).
- Table name collision within one millisecond: `AcsSnapshotStore` names per-snapshot tables and indexes with
  `targetRecordTime.toEpochMilli`, so two forced snapshots in the same ms fail with `relation
  "acs_snapshot_creates_v1_<historyId>_<ms>" already exists` (SQLSTATE 42P07) and `/api/scan/v0/state/acs/force` returns
  HTTP 500. 10238 (TokenStandardMetadataTimeBasedIntegrationTest, simtime (2)); dups 10247 (run 36584417962, main 1293c69b23), 10257 (run 36710644051, main 33b55cb609). Confirming grep: the two `Forcing ACS
  snapshot at <t>` lines share the same epoch ms. Fix: scan app (names from micros or the snapshot id), owner #6515.
  Test-side fix for TokenStandardMetadataTimeBasedIntegrationTest: `s11/fix-10238-single-forced-acs-snapshot` (forces
  once and reuses that snapshot). Wall-clock waits and bare `advanceTime` don't help: in sim time the record time
  only leaves the millisecond when a new update lands.

## O. Teardown: scan serves a request after its DbStorage closed (sbt output check)
- Signature: all tests pass; `Found problems in the sbt output:` `[delay-util-0] [org.apache.pekko.dispatch.Dispatcher]
  executeTask was rejected twice!` + `RejectedExecutionException` (4 lines), at the end of
  TrafficBasedRewardsSvAppTimeBasedIntegrationTest.
- Mechanism: `NodeBootstrapBase.onClosed` closes the node (stores, DbStorage) before the HTTP binding; an SV's
  `ProcessRewardsDryRunTrigger` calls `/api/scan/v0/internal/reward-accounting-process/rounds/N/batches/<hash>` during
  teardown; the lookup retries forever on the closed Slick executor; the binding waits 4.5 s; the retry's DelayUtil
  timer fires after the environment executor is gone.
- Confirming grep: `lookupBatchByHash` `transient error (request infinite retries)` on a scan AFTER its `'db-storage' is
  now in state Failed(Component is closed)`, plus `Task closing http binding admin service still not completed after
  4500 milliseconds` for that scan.
- Occurrences: 10233 (sv1Scan), 10234 (sv4Scan), canton 3.6.0-snapshot.20260925. Fix: close httpAdminService first (app,
  described). Do not ignore the Pekko line.

## P. Participant reconnect never completes after a sequencer-alias-only config change (Canton 3.6.0-snapshot.20260928)
- Signature: `ParticipantAdminConnection` "reconnect to the synchronizer ... for new sequencer configuration to take
  effect", then on the participant `Ensured <psid> persistent state` is the last connect-path line; every later `Trying
  to connect` has no `About to connect`; `connect to Synchronizer 'global' has not completed after ~10 s` every 2 min.
- Downstream: the sponsor SV returns NOT_CONNECTED_TO_SYNCHRONIZER to joining SVs; later environments time out waiting
  for sv1 init; a teardown-plugin failure then leaks the environment (family H).
- Occurrence: 10235 (sv1Validator init in Ans4SvsIntegrationTest, DefaultSequencer -> SEQ::sv1, same endpoint). Canton-side,
  open; what the stuck connect waits on needs a thread dump.

## Q. Cluster deploy: Pulumi program exception from a private config missing a newly required key
- Signature (CircleCI `Apply Pulumi configuration to cluster`, 6 of 6 attempts): `[organization/sv-canton/...] - error -
  error: TypeError: Cannot read properties of undefined (reading '<field>')` at program evaluation, followed about 10 s
  later by `Aborting: ... Aborting because of caught exception`, then `^C received; cancelling` and Helm `context
  canceled` / StatefulSet `Resource operation was cancelled` on every other stack. Only the first TypeError matters.
- Confirm: `grep -a -n "Aborting after the wait time"` names the stack that failed first; its stack trace names the
  file and line. Check whether the key is read from `getPathToPrivateConfigFile` (CI clusters are `IS_DEVNET=1`, so
  `configs/DevNet/`; the splice stub has only `TestNet/`).
- 10264 (parent), 10265, 10266, 10267, 10268 (2026-09-30, cimain deploy_basic x3, ciperiodic sv-reonboard, ciperiodic
  upgrade): #7546 (5375bb71c3) added `messages.confirmationResponse` in `getSequencerRateLimitConfig`
  (`cluster/pulumi/sv-canton/src/decentralizedSynchronizerNode.ts:55`). Packet [10264-10268-sv-canton-pulumi-confirmation-response-rate-limit-missing-in-devnet-private-config.md](10264-10268-sv-canton-pulumi-confirmation-response-rate-limit-missing-in-devnet-private-config.md).
- Fix state: open, owner #7546. Private config key (configs-private#3673) or tolerate absent message types.
- Not family J: family J deploy failures (10249) are Helm readiness timeouts after the full 600 s wait with no program
  exception; family Q aborts within minutes, well before the 600 s Helm wait.
