# Converting `approved-sv-id-values.yaml` into on-ledger SV rights owners

**Scratch directory: remove it before merging.** It shows how the DevNet config from
[canton-foundation/configs#413](https://github.com/canton-foundation/configs/pull/413) maps to the
`SvRightOwnerInfo` types in [`SV_RIGHTS_OWNERS_POC.md`](../../SV_RIGHTS_OWNERS_POC.md),
i.e., to the argument of `DsoRules_MigrateToOnLedgerSvRightOwners`.

## Files

- `input/approved-sv-id-values.DevNet.yaml`: the config from configs#413
  (`pedrodneves/configs@f119edc7adbf63db0a669818732d54eea6f80569`), which adds `svRightsOwnerName`.
- `comments_to_fields.py`: moves the `# <label> [CIP-…] [# <role>]` comments on beneficiaries into
  `label`, `cips`, `role` (`active`, `escrow` or `unassigned`) and `note` fields, by plain line edits.
  Output: `output/approved-sv-id-values.DevNet.fields.yaml`.
- `convert.py`: converts that file into a `TextMap SvRightOwnerInfo`, keyed by owner name, and checks the result.
  Output: `output/sv-right-owners.DevNet.json`.

```sh
python3 comments_to_fields.py input/approved-sv-id-values.DevNet.yaml > output/approved-sv-id-values.DevNet.fields.yaml
uv run --script convert.py output/approved-sv-id-values.DevNet.fields.yaml > output/sv-right-owners.DevNet.json
```

## Conversion rules

- **Operators stay as they are.** For each SV node operator, its own `svRightsOwnerName` gets an assignment with the
  SV's `rewardWeightBps` minus the weight of the beneficiaries that belong to other owners.
- **Every beneficiary stays at its current operator.** An owner's beneficiaries at an operator become one assignment,
  keyed by that operator's name, with their summed weight.
- **Escrow:** entries with role `escrow` or `unassigned` become `escrowedRewardWeight`. No coupons are created for it.
- **`rightOwnerParty`:** the SV party for owners that run an SV node. For a hosted owner, its only non-escrow party.
  `null` for a hosted owner without a non-escrow party, i.e., the 18 owners whose weight is fully escrowed.
- **`defaultBeneficiary`:** the operator's SV party for an SV's own assignments, otherwise `rightOwnerParty`.
  It is `null` only where the beneficiaries receive all of the non-escrowed weight.
  A trailing beneficiary that pays the default beneficiary is dropped, as it receives the remainder anyway.
- **`description`:** the labels, CIPs and URLs of the owner's entries, without duplicates.
- `<X SV party>` stands for the party of SV node operator `X`, which is not in the config.

## Checks done by `convert.py`

- The set of operators is unchanged (15), and every owner has at most one assignment per operator (52 owners, 56 assignments).
- Every config entry keeps its operator, owner and weight, with escrow entries summed into `escrowedRewardWeight`.
- For every operator, the coupons per party equal today's `SvUtil.weightDistributionForSv`,
  except that escrow parties no longer receive coupons.

## Open points

- 18 hosted owners have only escrowed weight and thus no owner party in the config, e.g. DTCC, Nasdaq, Visa.
  They get one by a DSO vote with `DsoRules_UpdateSvRightOwnerInfo`, e.g., when their first milestone is reached.
- For Taurus, Talos, BitGo, Fireblocks, Chainlink and Tharimmune, the only non-escrow party is in the namespace
  of a ghost validator (GhostSV-validator-1 `1220a9cc…` or MPCH-GHOSTvalidator-1 `1220a816…`).
  So whoever operates that validator acts as their `rightOwnerParty` and can redirect their non-escrowed weight;
  it already receives their coupons today.
- Owners that run an SV node get no `description`, as the config has no comments on the SV entries.
- The SV parties `<X SV party>` of the SV node operators must be filled in from the on-ledger `DsoRules.svs`,
  and the total weight must match the on-ledger `svRewardWeight`s, which this does not check.
