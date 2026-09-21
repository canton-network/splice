# Known flake families (2026-09 state). Check these before any deep analysis.

Format: signature to grep | confirming check | mechanism | parent ref and duplicates | fix state.

## A. ACS_COMMITMENT_MISMATCH sv1Participant vs aliceValidator after a multi-host step
- Signature (canton log WARN): `ReceivedAcsCommitmentMatcher:participant=sv1Participant ... ACS_COMMITMENT_MISMATCH ... sender = aliceValidator`.
- Confirm: `zcat canton_network_test.clog.gz | grep -a -E "Starting test suite|Multi-host alice"`; the mismatched
  period's `fromExclusive` is 1-16 s after a `Multi-host alice on sv1Participant` clue (ExpiryWithMinimalVettedPackages
  base suites: AmuletExpiryV1Fallback, ExpiryWithIgnoredAmuletVersion, ExpiryWithNoVettedAmuletVersion; and
  AutoIgnoreUnresponsiveParties*). The WARN lands 0-27 min later in an unrelated suite (random send delay).
- Mechanism: the tests add sv1Participant as a host of alice's wallet party after she owns contracts, with no
  ACS import; the two hosts genuinely disagree. Test issue, not product. Canton 3.6 detects it every time.
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
  (ValidatorIntegrationTest, SvOnboardingIntegrationTest, SvDsoPartyManagementIntegrationTest).

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
  Third hit (run 35611022158 wall-clock-time (2), ref 10183 or 10184): the fourth owner's reset proposal arrived after
  three signatures had already authorized owners = {sv1}; `TOPOLOGY_NO_APPROPRIATE_SIGNING_KEY_IN_STORE`, then 15
  zero-delay restarts inside the 250 ms effective delay all hit `TOPOLOGY_MAPPING_ALREADY_EXISTS`. Confirming grep:
  `zcat canton_network_test.clog.gz | grep -a -c 'Restarting decentralized namespace reset'` = 16 within 100 ms.
  Fix: `ray/fix-10183-10184-wct-2-reset-namespace-late-proposer` (tolerate the late proposer; wait loop decides).
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
- Mechanism (10183/10184 simtime (2)): the SV app installs a 10-minute cron with 30 d retention on its mediator; the
  scheduler prunes up to `clock.now - retention`; a sim-time jump >= 30 d puts that at the pre-jump clean timestamp, and
  `Mediator.prune` refuses anything above `clean - confirmationResponseTimeout` (30 s) until the mediator sees a
  post-jump event. Confirming grep: `advancing time by PT720H` (or longer) in the test log within a second before the WARN,
  and the other mediators logging `Pruned up to <same T>` a few seconds later.
- Fix: sim-time-only ignore in `canton_log_simtime_extra.ignore.txt` (`ray/fix-10183-10184-simtime-2-mediator-pruning-backoff-ignore`);
  do not put it in `canton_log.ignore.txt`, in production the line means the mediator lags the clock by > retention.

## J. Infra, no code change
- 10133 ghcr.io pull i/o timeout.
- Multi-arch image check (deployment_test, `scripts/check-multiarch-images.py`): one skopeo answer decides. 10150 = Docker Hub
  502 (`unable to inspect ... 502` on stderr); 10172 = no stderr line, response without a `manifests` list for a digest
  that is an index. Confirm with the registry probe in the 10172 packet. Fix: `ray/fix-multiarch-check-retry`.
