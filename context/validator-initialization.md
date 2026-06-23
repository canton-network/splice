# Validator app: initialization and package vetting

How the Validator app (`apps/validator/`, sbt project `apps-validator`) comes up, onboards, and vets
Daml packages. Base lifecycle classes live in `apps/common/` (`apps-common`); runtime wiring is in
`apps/app/` (`apps-app`). All paths are relative to the repo root. Line numbers are approximate
anchors - grep the named symbols if they have drifted.

## 1. Bootstrap and where the validator fits in SpliceApp

The validator is one node type managed by the Splice environment, parallel to SV / Scan / Splitwell.

- `SpliceEnvironment` (`apps/app/src/main/scala/org/lfdecentralizedtrust/splice/environment/SpliceEnvironment.scala`)
  defines `createValidator(name, validatorConfig)`, which calls `ValidatorAppBootstrap(...)`, and exposes
  `validators = new ValidatorApps(createValidator, ...)`. `allSplices` lists `List(svs, scans, validators, splitwells)`,
  matching `startupOrderPrecedence` - SVs/Scans come up before validators.
- `ValidatorApps` (`.../environment/ValidatorApps.scala`) is a thin `ManagedNodes[...]`.
- `ValidatorAppBootstrap` (`apps/validator/src/main/scala/org/lfdecentralizedtrust/splice/validator/ValidatorAppBootstrap.scala`)
  extends `NodeBootstrapBase` ("modelled after Canton's ParticipantNodeBootstrap"). `apply` creates the
  DB storage factory with an exclusive DB lock (`SpliceDbLockCounters.VALIDATOR_WRITE`, gated by
  `instanceLockEnabled`). `initialize(adminRoutes)` calls `startInstanceUnlessClosing { new ValidatorApp(...) }`
  - constructing `ValidatorApp` is what kicks off initialization.
- `ValidatorApp` (`.../validator/ValidatorApp.scala`) extends `Node[ValidatorApp.State, Option[CantonTimestamp]]`.
  The `PreInitializeState` is `Option[CantonTimestamp]` (the initial synchronizer time, consumed later by
  the sequencer-reconnect trigger).

## 2. The init state machine (base lifecycle)

Driver: `NodeBase` (`apps/common/.../environment/NodeBase.scala`), `initializeF` runs once:

1. `preInitializeBeforeLedgerConnection()`
2. create ledger client (`createLedgerClient`) - auth token source + `SpliceLedgerClient`
3. `waitForUser(...)` - waits for `serviceUser` (`ledgerApiUser`) to exist, retrying on `PERMISSION_DENIED`
4. `initializeNode(client)`
5. on success `isInitializedVar = true`; on failure (not closing) it logs and `sys.exit(1)`

`initializeNode` is overridden in `Node` (`apps/common/.../environment/Node.scala`). The validator sets
`waitForPartyBeforePreinitialize = false` (`ValidatorApp.scala`), so the order is:

`preInitializeBeforeLedgerConnection` -> `preInitializeAfterLedgerConnection` -> `waitForParty` ->
`waitForPackages(requiredPackageIds)` -> `initialize(ledgerClient, serviceParty, preInitializeState)`.

The validator deliberately runs preinit before waiting for its party because it "doesn't want to mess
around with things like sequencer connections until the SV app finishes init" (comment in `ValidatorApp.scala`).
`waitForParty` retries `getPrimaryParty(serviceUser)` (also retrying `PERMISSION_DENIED`, since the SV app
may not have allocated the user yet).

## 3. Validator-specific init phases

### Phase A - `preInitializeBeforeLedgerConnection` (participant identity)
Opens a `ParticipantAdminConnection` and runs `ParticipantInitializer`
(`org.lfdecentralizedtrust.splice.setup.ParticipantInitializer`) with the resolved participant id
(`ValidatorCantonIdentifierConfig.resolvedNodeIdentifierConfig`):
- `config.svValidator` -> `waitForNodeInitialized()` (the SV app owns participant init), else
  `ensureInitializedWithExpectedId()`.

### Phase B - `preInitializeAfterLedgerConnection` (domain connection, vetting, party allocation)
Returns `Option[CantonTimestamp]` (`initialSynchronizerTime`). Inside `withParticipantAdminConnection`:
1. `getParticipantId()`, build `ValidatorInternalStore` keyed by the validator party. The validator party
   is derived from `config.validatorPartyHint` (or sanitized `ledgerApiUser`) via `ParticipantPartyMigrator.toPartyId`.
2. `ValidatorConfigProvider` over the internal store (persists the Scan URL list).
3. `BftScanConnection(...)` using `config.scanClient`, with persisted scan-list hooks from `ValidatorScanConnection`.
4. `resolveDomainMigrationId(scanConnection)` - highest known local migration id, else fetch from Scan with retries.
5. Build `SynchronizerConnector`.
6. `isSynchronizerRegistered(config.domains.global.alias)`; `initialSynchronizerTime = Option.when(!alreadyRegistered)(clock.now)`
   - only set when the domain is brand new, so the reconcile trigger doesn't travel back in time.
7. Register and connect the global decentralized synchronizer
   (`ensureDecentralizedSynchronizerRegisteredAndConnectedWithCurrentConfig`, or a wait variant for SV
   validators with BFT sequencer disabled). Sequencer connections come from a static `domains.global.url`
   or are fetched from Scan (`waitForSequencerConnectionsFromScan`).
8. `ensureExtraDomainsRegistered()`.
9. **Vet packages eagerly** "before the automation kicks in": get `AmuletRules` from Scan, resolve the
   global synchronizer id + extra connected synchronizers, then `PackageVetting.vetCurrentPackages(...)`
   for each. See section 5.
10. Party allocation/migration: if `migrateValidatorParty` + `participantBootstrappingDump` ->
    `ParticipantPartyMigrator.migrate(...)` from a Scan ACS snapshot. Otherwise (non-SV validator)
    `ensureUserPrimaryPartyIsAllocated(...)`, enforcing the hint format
    `^[a-zA-Z0-9_]+-[a-zA-Z0-9_]+-[0-9]+$`. No-op for SV validators (the SV app already created the user).
11. `ensurePruningSchedule(config.participantPruningSchedule)`.

### Phase C - `initialize` (stores, wallet, automation, onboarding, HTTP routes)
Receives `(ledgerClient, validatorParty, initialSynchronizerTime)`:
1. Read-only connection + `ParticipantAdminConnection` + `NodeIdentitiesStore`; rebuild
   `ValidatorInternalStore` + `ValidatorConfigProvider`; BFT Scan connection; `resolveDomainMigrationId`.
2. **Traffic balance service** (`newTrafficBalanceService`) registered on the ledger client. After this,
   init-time commands from the validator party must use `CommandPriority.High` so they aren't blocked
   while the first traffic top-up is pending.
3. `getDsoPartyIdWithRetries()` from Scan -> `ValidatorStore.Key(validatorParty, dsoParty)` -> `ValidatorStore`.
4. `DomainTimeAutomationService`, `ValidatorTopupConfig`, dedup duration, `getAmuletRulesDomain()` -> synchronizer id.
5. `ParticipantInitializer.ensureInitializedWithRotatedOTK(...)` - participant identity with a rotated
   one-time key on the resolved synchronizer.
6. Wallet managers if `config.enableWallet`: `ExternalPartyWalletManager` + `UserWalletManager`.
7. `ValidatorAutomationService` (section 6).
8. `setupAppInstance` per `config.appInstances`: upload its DARs, allocate the service party with
   `CanReadAs` the validator party, onboard its wallet user via `ValidatorUtil.onboard`.
9. Onboard validator wallet users (`config.validatorWalletUsers`, or `ledgerApiUser` if empty).
10. **`ensureValidatorIsOnboarded(store, validatorParty, config.onboarding)`** (section 4).
11. Build auth `verifier` (HS256/RS256) and all HTTP handlers (`HttpValidatorHandler`,
    `HttpValidatorAdminHandler`, scan-proxy + token-standard proxy, wallet/ans, `HttpValidatorPublicHandler`),
    assemble the Pekko HTTP route with CORS + per-realm auth, register via `adminRoutes.updateRoute(route)`.
12. Return `ValidatorApp.State(...)`.

## 4. Onboarding handshake (secret -> request -> ValidatorLicense)

`ensureValidatorIsOnboarded` (`ValidatorApp.scala`):
- `store.lookupValidatorLicenseWithOffset()`; if a `ValidatorLicense` exists -> already onboarded.
- Else branch on `config.onboarding: Option[ValidatorOnboardingConfig]`:
  - `Some(oc)`: `requestOnboarding(oc.svClient.adminApi, validatorParty, oc.secret)` then `waitForValidatorLicense(store)`.
  - `None`: just `waitForValidatorLicense(store)` (SV validators / pre-provisioned).
- `requestOnboarding` retries `ValidatorSvConnection.onboardValidator(validatorParty, secret, contactPoint)`
  (`apps/validator/.../ValidatorSvConnection.scala`), which POSTs `OnboardValidatorRequest(partyId, secret, version, contactPoint)`.
- `waitForValidatorLicense` polls `lookupValidatorLicenseWithOffset` until the contract appears (throws
  `NOT_FOUND` to drive retries).

SV side (`apps/sv/.../admin/http/HttpSvPublicHandler.scala`):
- `onboardValidator` handler decodes the secret (`Secrets.decodeValidatorOnboardingSecret`), checks it was
  issued by this SV (`sponsoringSv == svParty`), looks it up via `svStore.lookupValidatorOnboardingBySecret`,
  and is idempotent for already-used secrets / existing licenses.
- For a fresh valid secret it submits `DsoRules_OnboardValidator` (creates the on-ledger `ValidatorLicense`)
  on the DSO and `ValidatorOnboarding_Match` (archives the one-time `ValidatorOnboarding` contract). That
  `ValidatorLicense` ingested into the validator's `ValidatorStore` releases `waitForValidatorLicense`.

The secret is generated by the sponsoring SV (`ValidatorOnboardingConfig.secret`; devnet self-service path
`devNetOnboardValidatorPrepare` -> `prepareValidatorOnboarding`).

## 5. Package vetting

"Vetting" = Canton/Daml package vetting: a participant must add DAR packages to its `VettedPackages`
topology state on a synchronizer before they can be used in transactions; unvetting removes them. Two
entry points, both validator-side:

**(a) Startup one-shot** (`ValidatorApp.scala`, Phase B): builds
`new PackageVetting(ValidatorPackageVettingTrigger.packages, clock, participantAdminConnection, ...)` and
calls `vetCurrentPackages(synchronizerId, amuletRules, config.additionalPackagesToUnvet)` for the global +
extra synchronizers, so required packages are present before automation starts.

**(b) Continuous trigger** `ValidatorPackageVettingTrigger`
(`apps/validator/.../automation/ValidatorPackageVettingTrigger.scala`), extending the shared
`PackageVettingTrigger` (`apps/common/.../automation/PackageVettingTrigger.scala`), registered in
`ValidatorAutomationService`. Constructed with `enableUnvetting = false` ("Currently only supported by SVs"
- the SV runs `SvPackageVettingTrigger`). Package set = `PackageIdResolver.validatorPackages`. It is a
`PollingTrigger`; `performWorkIfAvailable` is guarded by `runIfInputChanged` (keyed on synchronizer id,
AmuletRules/DsoRules contract ids, the `VoteRequest` set, and `additionalPackagesToUnvet`) to avoid
re-submitting topology transactions every poll.

### Which packages get vetted
Decided by on-ledger config: `AmuletRules.configSchedule[...].packageConfig` (`splice.amuletconfig.PackageConfig`).
`PackageIdResolver.readPackageVersion(packageConfig, pkg)` reads the required version per
`PackageIdResolver.Package` (amulet / wallet / wallet-payments / name-service / validator-lifecycle).
`DarResourcesUtil.getRequiredPackageVersions` expands a required version to the concrete DAR set: by default
every available version from the minimum-initialization version up to and including the required version (so
older transactions stay processable); with `latestPackagesOnly` (LocalNet only) just the exact version.

AmuletRules / DsoRules / VoteRequests come from **Scan** via `BftScanConnection` (the trigger overrides
`getSynchronizerId`/`getAmuletRules`/`getVoteRequests`/`getDsoRules`), not the DSO directly.

The six `SpliceApiTokenStandard*` interface packages plus `SpliceUtilBatchedMarkers` are **always** vetted
regardless of `packageConfig` (`PackageVetting.vetCurrentPackages`): stores include interface ids in
`GetUpdates` requests that fail if the package is absent, and interfaces are non-upgradeable so there's no
benefit to coordinating them via package config.

### Upload + vet
`PackageVetting.vetPackages` -> `uploadDarsAndVet` (`apps/common/.../util/PackageVetting.scala`):
1. `participantAdminConnection.uploadDarFiles(..., RetryFor.Automation)` - upload DAR bytes.
2. `participantAdminConnection.vetDars(domainId, resources, fromDate = validFrom, maxVettingDelay)` - issue
   the vetting topology change.
`vetDars`/`unvetDars` live in `ParticipantAdminDarsConnection` (`apps/common/.../environment/ParticipantAdminDarsConnection.scala`):
they read the current `VettedPackages` authorized state and `ensureTopologyMapping[VettedPackages]` (or
`ensureInitialMapping` if none), each package becoming `VettedPackage(packageId, validFromInclusive, validUntil = None)`.
The check is no-op if all DARs are already present (validFrom is not re-checked: "once it's part of the
vetting state it can no longer be updated"). Submitted via `proposeMapping` with serial increment + retry.

### Versioning / upgrades and `validFrom` (the key mechanism)
Vetting is scheduled by **effective time** so a new package version is vetted *ahead of* when an
AmuletConfig change makes it required - enabling coordinated network upgrades.
`vetPackages` builds an `AmuletConfigSchedule` and calls `associatePackageVersionsByEarliestVettingDate`,
merging three sources of future config:
- accepted-but-not-yet-effective vote requests (`futureAmuletConfigFromVoteRequests`),
- the AmuletRules config schedule's `futureConfigs`,
- the current `initialConfig`.
For each `(effectiveTime, config)` it computes required versions and reduces to the **earliest** time each
`(pkg, version)` is needed, then vets sequentially earliest-first (so dependencies are available early and
topology updates don't race).

Accepted-effective votes come from `AmuletConfigSchedule.getAcceptedEffectiveVoteRequests`
(`apps/common/.../util/AmuletConfigSchedule.scala`): `ARC_AmuletRules / CRARC_SetConfig` vote requests that
have a `targetEffectiveAt` and have reached `Thresholds.requiredNumVotes(dsoRules)`. So once a config-change
vote passes but before it is effective, new package versions are vetted with `validFrom = effectiveAt`.

Canton `validFrom`/`validUntil` bounds: `validFrom` is used (set to the earliest-needed effective time),
`validUntil` is left `None`. If a dependency was already vetted with a *later* future `validFrom`, it is
re-added with the earlier one. `PackageVersionSupport` is the consumer side (which version to use in
transactions / feature gating) and is threaded into the automation service, but the vetting trigger drives
off AmuletConfig + votes.

### Time-based submission spreading - `maxVettingDelay`
`maxVettingDelay` (default 24h) is passed as `maxSubmissionDelay` into the topology layer
(`TopologyAdminConnection`), which picks a random delay in `[0, maxVettingDelay)` and `clock.scheduleAt(...)`
before submitting - so ~86k validators don't all submit the same vetting transaction at once and overload
the network. `adjustMaxVettingDelay` caps this so the random delay never pushes submission past the
package's `validFrom` (for imminent effective times the window shortens so the transaction still lands in time).

### Unvetting (SV-only)
The unvetting branch (`PackageVetting.unvetPackages` -> `filterUnsupportedPackageVersions`) removes
unsupported versions. It is disabled on validators (`enableUnvetting = false`); only `SvPackageVettingTrigger`
in the SV app runs it.

## 6. Automation/triggers brought up at init

`ValidatorAutomationService` (`apps/validator/.../automation/ValidatorAutomationService.scala`) registers:
- `TopologyMetricsTrigger` (if `topologyMetricsPollingInterval` set).
- Wallet-gated (only when wallet manager defined): `WalletAppInstallTrigger`, `ValidatorRightTrigger`,
  `OffboardUserPartyTrigger`, `AcceptTransferPreapprovalProposalTrigger`, `RenewTransferPreapprovalTrigger`,
  `ReceiveFaucetCouponTrigger` (if `enableAutomaticRewardsCollectionAndAmuletMerging`),
  `TopupMemberTrafficTrigger` (skipped for SV validators or target throughput <= 0), `TransferCommandSendTrigger`.
- `PeriodicParticipantIdentitiesBackupTrigger` (if `participantIdentitiesBackup` configured).
- `ReconcileSequencerConnectionsTrigger` (when `sequencerConnectionFromScan`; gets `initialSynchronizerTime`
  from Phase B) - keeps participant sequencer connections in sync with Scan.
- `ValidatorPackageVettingTrigger` (non-SV validators only; section 5).
- `ValidatorLicenseMetadataTrigger`, `ValidatorLicenseActivityTrigger` - keep the on-ledger `ValidatorLicense`
  metadata/activity current.
- `RollForwardLsuTrigger` (non-SV validators only; logical synchronizer upgrade roll-forward).
- `SqlIndexInitializationTrigger`.
The base `SpliceAppAutomationService` also drives ACS/TxLog ingestion into `ValidatorStore`.

## 7. Key configuration (`apps/validator/.../config/ValidatorAppConfig.scala`)

`ValidatorAppBackendConfig`, init-relevant fields:
- `ledgerApiUser` - service/wallet user.
- `validatorPartyHint: Option[String]` - `None` for SV validators, `Some` otherwise; drives the validator
  party id and (by default) the participant id.
- `validatorWalletUsers`, `appInstances`.
- `participantClient`, `scanClient: BftScanClientConfig`.
- `domains: ValidatorSynchronizerConfig` - `global` (optional `url`, `buyExtraTraffic`, `reservedTraffic`,
  `trafficBalanceCacheTimeToLive`, optional `trustedSynchronizerConfig`) and `extra`.
- `onboarding: Option[ValidatorOnboardingConfig]` = `{ svClient, secret }`.
- `svValidator: Boolean`, `disableSvValidatorBftSequencerConnection` - gate participant-init wait, party
  allocation, the sequencer-connection trigger, etc.
- `enableWallet`, `participantBootstrappingDump`, `participantIdentitiesBackup`, `migrateValidatorParty`.
- `cantonIdentifierConfig` (`resolvedNodeIdentifierConfig` defaults participant id to the party hint or
  `"unnamedValidator"`).
- `contactPoint`, `participantPruningSchedule`, `maxVettingDelay` (default 24h), `latestPackagesOnly`
  (LocalNet only), `instanceLockEnabled`.

Test topology: `apps/app/src/test/resources/simple-topology.conf` and the validator includes under
`include/validators/` (`_validator.conf` -> `_regular_validator.conf` -> e.g. `alice-validator.conf`, which
sets `ledger-api-user`, `validator-party-hint = "alice-validator-1"`, an `onboarding` block pointing at
sv1 with `secret = "alicesecret"`, etc.).

## Execution order (summary)

1. `SpliceEnvironment` -> `ValidatorApps` -> `createValidator` -> `ValidatorAppBootstrap.apply` (DB + lock)
   -> `initialize` -> `new ValidatorApp`.
2. `NodeBase.initializeF`: `preInitializeBeforeLedgerConnection` -> create ledger client -> `waitForUser`
   -> `initializeNode`.
3. `Node.initializeNode` (validator): preinit-before (participant init) -> preinit-after (Scan conn,
   migration id, register+connect global synchronizer, extra domains, **vet packages**, allocate/migrate
   primary party, pruning) -> `waitForParty` -> `waitForPackages` -> `initialize`.
4. `ValidatorApp.initialize`: stores + Scan + DSO party -> traffic balance service ->
   `ensureInitializedWithRotatedOTK` -> wallet managers -> `ValidatorAutomationService` -> setup app
   instances -> onboard wallet users -> `ensureValidatorIsOnboarded` (secret -> SV request -> wait for
   `ValidatorLicense`) -> HTTP routes -> `State`.
5. SV exercises `DsoRules_OnboardValidator` (creates `ValidatorLicense`) + `ValidatorOnboarding_Match`; the
   ingested license releases the wait and `isInitializedVar` flips to true.
