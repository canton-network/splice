# Host commands, 2026-10-30 fork a: mainnet-Daml nightly (run 41006358697)

Run on the host (zsh). State-guarded: running it twice is harmless. Evidence: `nightly-a.md`.
Open umbrellas: 10934 (X1 external party PV), 10927 (X2 outbox), 10937 (X3 offline root key), 10930 and 10933 (Y).

```zsh
R=DACH-NY/cn-test-failures
is_open() { [ "$(gh issue view $1 --repo $R --json state -q .state)" = OPEN ]; }
bot_title() { gh issue view $1 --repo $R --json title -q .title | grep -q '^GHA Run'; }
not_commented() { ! gh issue view $1 --repo $R --json comments -q '.comments[] | select(.author.login == "ray-roestenburg-da") | .body' | grep -qF "$2"; }

bot_title 10934 && gh issue edit 10934 --repo $R --title "PV 36 nightly: external party topology generated at PV 37, submit fails PROTO_DESERIALIZATION_FAILURE (Canton 3.7)"
not_commented 10934 "Root cause (mainnet-Daml nightly" && gh issue comment 10934 --repo $R --body "Root cause (mainnet-Daml nightly, PV 36, main dfe31a0ada): every external-party test fails on POST /api/validator/v0/admin/external-party/topology/submit with PROTO_DESERIALIZATION_FAILURE; aliceParticipant logs 'Error while deserializing a TopologyTransaction; expected representative protocol version 36 but found 37'. ValidatorUtil.createTopologyMappings calls generateTransactions for the Authorized store without a protocol version, so Canton 3.7 (#7944) generates the PV 37 form; the participant is on a PV 36 synchronizer. Fix: generate for the synchronizer store or with the synchronizer's PV (as #8125 did for SV1's identity). Not test-only: a 0.10.x validator on a PV 36 network cannot onboard external parties through the validator API. First visible now because the nightly got past SV1 bootstrap (#8065/#8125). Per-job dups: 10924 10926 10928 10929 10931 10932 10936. Same PV mismatch: 10927 (joining SV outbox) and 10937 (offline root key)."
is_open 10924 && gh issue close 10924 --repo $R --reason "not planned" --comment "Duplicate of #10934"
is_open 10926 && gh issue close 10926 --repo $R --reason "not planned" --comment "Duplicate of #10934"
is_open 10928 && gh issue close 10928 --repo $R --reason "not planned" --comment "Duplicate of #10934"
is_open 10929 && gh issue close 10929 --repo $R --reason "not planned" --comment "Duplicate of #10934"
is_open 10931 && gh issue close 10931 --repo $R --reason "not planned" --comment "Duplicate of #10934 (its traffic purchase failure is #10933)"
is_open 10932 && gh issue close 10932 --repo $R --reason "not planned" --comment "Duplicate of #10934"
is_open 10936 && gh issue close 10936 --repo $R --reason "not planned" --comment "Duplicate of #10934"

bot_title 10927 && gh issue edit 10927 --repo $R --title "PV 36 nightly: joining SV nodes' outbox cannot push identity (OTK serial 1, PV 37 form), log check fails"
not_commented 10927 "Root cause (mainnet-Daml nightly" && gh issue comment 10927 --repo $R --body "Root cause (mainnet-Daml nightly, PV 36): tests pass, the log check fails on 'synchronizer outbox flusher failed ... OwnerToKeyMapping(member = MED::sv2SvOffboarding ...), serial = 1' for the joining SVs' mediators on psid ::36-0. Same in 10935 (MED::sv2/sv3) and 10925 (sv1-4 sequencers on the LSU successor ::37-2: the successor store holds the OTK with hash b9bb1c20a3ef, the outbox sends 2987c56f6dcd, same mapping in another PV representation). Same mechanism as 10520/10844 (SV1, fixed by #8065/#8125), now for joining SVs and for LSU across PVs. Dups: 10925 10935."
is_open 10925 && gh issue close 10925 --repo $R --reason "not planned" --comment "Duplicate of #10927"
is_open 10935 && gh issue close 10935 --repo $R --reason "not planned" --comment "Duplicate of #10927"

bot_title 10937 && gh issue edit 10937 --repo $R --title "PV 36 nightly: validator with offline root key cannot connect, TOPOLOGY_CONVERSION_ERROR (Canton 3.7)"
not_commented 10937 "Root cause (mainnet-Daml nightly" && gh issue comment 10937 --repo $R --body "Root cause (mainnet-Daml nightly, PV 36): ValidatorOfflineRootNamespaceKeyIntegrationTest, ReconnectSynchronizer fails with TOPOLOGY_CONVERSION_ERROR 'Signing key 122062945117... for topology transaction NamespaceDelegation' (05:38:52). Connecting to the PV 36 synchronizer makes the participant convert its PV 37 identity transactions, which needs the root key; it is offline by design. aliceValidator exits ('Ensuring decentralized synchronizer registered failed'), the shard hangs until SIGINT at 06:22. Same PV 36/37 mismatch as #10934. Not test-only: an offline-root-key validator on 0.10.x cannot join a PV 36 network."

bot_title 10930 && gh issue edit 10930 --repo $R --title "UnhideAndExpireRewardCouponV2TimeBasedIntegrationTest: no RewardCouponV2 with MainNet packages (amulet 0.1.24), test tag too low"
not_commented 10930 "Mainnet-Daml nightly (amulet 0.1.24)" && gh issue comment 10930 --repo $R --body "Mainnet-Daml nightly (amulet 0.1.24): 'Vector() was empty' at line 206, ProcessRewardsTrigger only ran BatchOfMintingAllowances for rounds 0-4, no coupon batches. The suite is tagged SpliceAmulet_0_1_19, so it is not excluded with the MainNet base packages. Last changed by #7627 (amulet 0.1.25); it likely needs SpliceAmulet_0_1_25. To confirm by the test owner."

bot_title 10933 && gh issue edit 10933 --repo $R --title "Traffic purchase via token standard transfer: UNKNOWN_INFORMEES splice_traffic-manager with MainNet packages, test not version-tagged"
not_commented 10933 "Mainnet-Daml nightly (amulet 0.1.24)" && gh issue comment 10933 --repo $R --body "Mainnet-Daml nightly (amulet 0.1.24): TokenStandardTransferIntegrationTest and WalletTxLogIntegrationTest 'buy traffic through a transfer to the traffic purchase receiver' fail with UNKNOWN_INFORMEES for splice_traffic-manager::1220000...abcd. Added by #8012; the test needs the newer packages and is not tagged for a package version, so it runs against MainNet's."
```
