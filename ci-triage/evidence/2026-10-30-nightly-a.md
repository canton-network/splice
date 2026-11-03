# 10924-10937: mainnet-Daml nightly, GHA run 41006358697 (main dfe31a0ada, 2026-10-30 05:04)

Workflow `daml_compat_test.yml`: `daml_base_version` = MainNet's 0.9.3 (`INITIAL_PACKAGE_VERSIONS` amulet 0.1.24),
`protocol_version: "36"`. First nightly where SV1 bootstraps at PV 36 since 10-24 (#8065 and #8125 are in
dfe31a0ada), so the tests ran for real for the first time since the Canton 3.7 bump (#7944, 10-23). Earlier
nightlies (10-24..10-29) died in SV1 bootstrap (family N) or on the compile break (#8109) and hid everything below.
Post-merge runs on the same sha (PV 37) do not show any of these.

Verdict: not a workflow/setup error and not #8124 by itself. Two causes:

- **X (new, real): Canton 3.7 nodes produce topology transactions at PV 37 where the PV 36 synchronizer expects PV 36.**
  12 of 14 jobs. Three variants below. Affects any network still at PV 36 running 0.10.x (Canton 3.7) nodes.
- **Y (new, test gap): tests that need newer Daml packages than MainNet's, tagged too low.** 2 tests.

## X1: external party onboarding via the validator API, PROTO_DESERIALIZATION_FAILURE

Every failing external-party test fails on `POST /api/validator/v0/admin/external-party/topology/submit`:

```
HTTP 400 Bad Request ... PROTO_DESERIALIZATION_FAILURE(8,667140a9): Deserialization of protobuf message failed
```
canton.clog (aliceParticipant, wall-clock-time 1, 05:49:42.911):
```
GrpcTopologyManagerWriteService:participant=aliceParticipant ... reason: OtherError(Error while deserializing a
TopologyTransaction; expected representative protocol version 36 but found 37)
```
Cause: `ValidatorUtil.createTopologyMappings` calls `participantAdminConnection.generateTransactions` with
`store = TopologyStoreId.Authorized` and no protocol version; Canton 3.7 generates the Authorized-store form at its
latest PV (37). The participant is connected to a PV 36 synchronizer and rejects it on submit. Fix in splice: generate
for the synchronizer store (or pass the synchronizer's PV), as #8125 did for SV1's identity.

| Issue | Job | Test(s) | protodeser hits |
|---|---|---|---|
| 10924 | logical-sync-upgrade (0) | LsuIntegrationTest "Create external party and transfer 40 amulet to it" | 1 |
| 10926 | key-download (0) | ValidatorReonboardingIntegrationTest re-onboard validator | (external process) |
| 10928 | wall-clock-time (3) | ExternalPartySetupProposalIntegrationTest, 6 tests | 24 |
| 10929 | frontend-wall-clock-time (2) | WalletFrontendIntegrationTest delegations | 4 |
| 10931 | wall-clock-time (8) | WalletTxLogIntegrationTest preapprovals x2 (+ traffic purchase, Y) | 2+ |
| 10932 | simtime (1) | WalletMintingDelegationTimeBasedIntegrationTest, 5 tests | 20 |
| 10934 | wall-clock-time (1) | RecoverExternalPartyIntegrationTest | 1 |
| 10936 | simtime (0) | ExternallySignedTxsTimeBasedIntegrationTest | 4 |

## X2: joining SV nodes' outbox cannot push their identity (WARN, tests pass)

Tests pass (`Tests: succeeded N, failed 0`), the job fails on the log check:
```
Found problems in log/canton-standalone-...: synchronizer outbox flusher failed The synchronizer ... failed the
following topology transactions: List(... OwnerToKeyMapping(member = MED::sv2 ...), serial = 1 ...)
```
- 10927 resource-intensive (0), SvOffboarding: MED::sv2/sv3/sv4SvOffboarding, psid `::36-0`, 3x.
- 10935 wall-clock-time (6), sv123 non-sv1 SVs: MED::sv2, MED::sv3, psid `::36-0`, 2x.
- 10925 roll-forward-lsu (0), DR after LSU to PV 37: sv1-4 sequencers' NamespaceDelegation + OTK serial 1 on the
  successor `::37-2`, 8x. The successor store holds the OTK with hash `b9bb1c20a3ef` (InitialTopologySnapshotValidator,
  05:34:33.740), the outbox sends `2987c56f6dcd`: same mapping, other PV representation, other hash.

Same mechanism as family N (SV1, fixed by #8065/#8125), now for the joining SVs' mediators and for sequencers after
an LSU across PVs. The "joining-SV risk" noted on 10844.

## X3: validator with an offline root namespace key cannot connect (job hangs, cancelled)

10937 wall-clock-time (7), ValidatorOfflineRootNamespaceKeyIntegrationTest, 05:38:52:
```
SynchronizerConnectivityService/ReconnectSynchronizer ... failed with NOT_FOUND/TOPOLOGY_CONVERSION_ERROR(11,48c6102c):
Signing key 122062945117... for topology transaction NamespaceDelegation(namespace = 122062945117... ...)
aliceValidator app initialization: Ensuring decentralized synchronizer registered failed -> exiting
```
Connecting to the PV 36 synchronizer makes the participant convert its identity transactions to PV 36, which needs
the root key; it is offline by design. The validator exits, the shard hangs until SIGINT at 06:22:03 (return code 130).

## Y: tests needing newer packages than amulet 0.1.24

- 10930 simtime (2) UnhideAndExpireRewardCouponV2TimeBasedIntegrationTest:206 `Vector() was empty` ("ProcessRewardsTrigger
  creates Alice's RewardCouponV2 hidden"). ProcessRewardsTrigger only ran `batchType=BatchOfMintingAllowances` for rounds
  0-4, no coupon batches. Tagged `SpliceAmulet_0_1_19`; amulet 0.1.24 is in the base set, so it was not excluded. Last
  changed by #7627 (TBAR v1.1, amulet 0.1.25). Likely needs `SpliceAmulet_0_1_25` (to confirm by the test owner).
- 10933 wall-clock-time (4) TokenStandardTransferIntegrationTest and the third failure in 10931 (WalletTxLogIntegrationTest):
  "buy traffic through a transfer to the traffic purchase receiver" -> `UNKNOWN_INFORMEES ... splice_traffic-manager::1220000...abcd`.
  Added by #8012 (Simon Meier, 10-27); not tagged for a package version.

## Production relevance (to check)

X1/X3 are not test artefacts: a 0.10.x validator (Canton 3.7) on a PV 36 synchronizer cannot onboard external
parties through the validator API, and a validator with an offline root key cannot connect. The workflow comment says
PV 36 stays until "adopted on mainnet". 0.10.0 reaches DevNet 11-02, TestNet 11-09, MainNet 11-16 (SV calendar).

## Owners (suggested)

- X1/X3: whoever owns the Canton 3.7 bump (#7944, Julien Tinguely) with Moritz Kiefer (#8125 did the SV1 part).
- X2: Moritz Kiefer (same mechanism as #8125).
- Y: Simon Meier (#8012; #7627 with Divam).
