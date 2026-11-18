# SV rights owners PoC: types and a MainNet example

```mermaid
classDiagram
    direction TB
    class DsoRules {
        <<template>>
        svs : Map Party SvInfo
        svRightOwners : Optional #40;Set Text#41;
        DsoRules_UpdateSvRightOwnerBeneficiaries(name, baseInfo, newInfo, rightOwner)
    }
    class SvRightOwner {
        <<template>>
        dso : Party
        name : Text
        info : SvRightOwnerInfo
    }
    class SvRightsOwnerKey {
        dso : Party
        name : Text
    }
    class SvRightOwnerInfo {
        rightOwnerParty : Party
        description : Text
        operatorAssignments : TextMap OperatorAssignment
    }
    class OperatorAssignment {
        rewardWeight : Int
        escrowedRewardWeight : Int
        beneficiaries : [BeneficiaryInfo]
        defaultBeneficiary : Party
    }
    class BeneficiaryInfo {
        beneficiary : Party
        weight : Int
    }
    class SvRewardCoupon {
        <<template>>
        dso : Party
        sv : Party
        beneficiary : Party
        round : Round
        weight : Int
    }
    class UnclaimedReward {
        <<template>>
        dso : Party
        amount : Decimal
    }
    class SvRightsOwnerRewardState {
        <<template>>
        dso : Party
        svRightsOwnerName : Text
        svOperatorName : Text
        state : RewardState
    }
    class SvRightsOwnerRewardStateKey {
        dso : Party
        svRightsOwnerName : Text
        svOperatorName : Text
    }
    DsoRules ..> SvRightOwner : owner names
    SvRightOwner -- SvRightsOwnerKey : key
    SvRightOwner *-- SvRightOwnerInfo
    SvRightOwnerInfo *-- "1..n" OperatorAssignment : keyed by operator name
    OperatorAssignment *-- "0..20" BeneficiaryInfo : paid in order
    OperatorAssignment ..> DsoRules : operator name is an SV in svs
    OperatorAssignment ..> SvRightsOwnerRewardState : one per owner and operator
    SvRightsOwnerRewardState -- SvRightsOwnerRewardStateKey : key
    OperatorAssignment ..> SvRewardCoupon : each round, one per beneficiary + one to dso for escrowedRewardWeight
    SvRewardCoupon ..> UnclaimedReward : expires, dso never claims
```

Example: a subset of the MainNet [`approved-sv-id-values.yaml`](https://github.com/canton-foundation/configs/blob/f0aa02df12e8ff2332075f796c26f89f828dcaa9/configs/MainNet/approved-sv-id-values.yaml) in the new model. Parties are shortened.

- `Digital-Asset`: one owner with two assignments at two operators.
- `Circle`: hosted by GSF, 70000 to a staking party, the rest to the default beneficiary.
- `Liberty-City-Ventures-1`: runs its own SV node, 70000 to one party, the rest to the owner party.
- `Franklin-Templeton`: one owner with active and escrowed weight; no ghost party, no ghost owner (today `ft` and `FranklinTempleton-ghost-1`).

```jsonc
{
  "DsoRules": {
    "svs": ["Digital-Asset-1", "Digital-Asset-2", "Global-Synchronizer-Foundation", "Liberty-City-Ventures-1"],
    "svRightOwners": ["Circle", "Digital-Asset", "Franklin-Templeton", "Liberty-City-Ventures-1"]
  },
  "SvRightOwner": [
    {
      "name": "Digital-Asset",
      "info": {
        "rightOwnerParty": "<Digital-Asset-1 SV party>",
        "description": "",
        "operatorAssignments": {
          "Digital-Asset-1": {
            "rewardWeight": 159250,
            "escrowedRewardWeight": 0,
            "beneficiaries": [],
            "defaultBeneficiary": "<Digital-Asset-1 SV party>"
          },
          "Digital-Asset-2": {
            "rewardWeight": 159250,
            "escrowedRewardWeight": 0,
            "beneficiaries": [],
            "defaultBeneficiary": "<Digital-Asset-2 SV party>"
          }
        }
      }
    },
    {
      "name": "Circle",
      "info": {
        "rightOwnerParty": "Circle-SvRewardsActive-1::12209d45…",
        "description": "CIP-0041",
        "operatorAssignments": {
          "Global-Synchronizer-Foundation": {
            "rewardWeight": 100000,
            "escrowedRewardWeight": 0,
            "beneficiaries": [
              { "beneficiary": "Circle-SvRewardsStaking-1::12209d45…", "weight": 70000 }
            ],
            "defaultBeneficiary": "Circle-SvRewardsActive-1::12209d45…"
            // coupons: Staking 70000, Active 30000
          }
        }
      }
    },
    {
      "name": "Liberty-City-Ventures-1",
      "info": {
        "rightOwnerParty": "auth0_007c684b…::122078c4…",
        "description": "",
        "operatorAssignments": {
          "Liberty-City-Ventures-1": {
            "rewardWeight": 100000,
            "escrowedRewardWeight": 0,
            "beneficiaries": [
              { "beneficiary": "auth0_007c69ea…::122078c4…", "weight": 70000 }
            ],
            "defaultBeneficiary": "auth0_007c684b…::122078c4…"
            // coupons: auth0_007c69ea… 70000, auth0_007c684b… 30000
          }
        }
      }
    },
    {
      "name": "Franklin-Templeton",
      "info": {
        "rightOwnerParty": "ft::12200b55…",
        "description": "CIP-0118",
        "operatorAssignments": {
          "Global-Synchronizer-Foundation": {
            "rewardWeight": 10000,
            "escrowedRewardWeight": 40000,
            "beneficiaries": [],
            "defaultBeneficiary": "ft::12200b55…"
            // coupons: ft 10000, dso 40000 (expires into UnclaimedReward)
          }
        }
      }
    }
  ],
  // key: (dso, svRightsOwnerName, svOperatorName)
  "SvRightsOwnerRewardState": [
    { "svRightsOwnerName": "Digital-Asset", "svOperatorName": "Digital-Asset-1", "state": "…" },
    {
      "svRightsOwnerName": "Digital-Asset",
      "svOperatorName": "Digital-Asset-2",
      "state": { "lastRoundCollected": 51234, "numRoundsCollected": "…", "numRoundsMissed": "…", "numCouponsIssued": "…" }
      // created at migration; state copied from the operator's SvRewardState "Digital-Asset-2", so no round is minted twice or lost
    },
    { "svRightsOwnerName": "Circle", "svOperatorName": "Global-Synchronizer-Foundation", "state": "…" },
    { "svRightsOwnerName": "Liberty-City-Ventures-1", "svOperatorName": "Liberty-City-Ventures-1", "state": "…" },
    { "svRightsOwnerName": "Franklin-Templeton", "svOperatorName": "Global-Synchronizer-Foundation", "state": "…" }
  ]
}
```

- One `SvRightOwner` per owner, with key `SvRightsOwnerKey`, a `description` and one `OperatorAssignment` per SV node operator.
- Each assignment is keyed by its operator name and has its own weight, ordered beneficiaries with absolute weights, and a default beneficiary for the rest.
- One `SvRightsOwnerRewardState` per owner and operator, with key `(dso, svRightsOwnerName, svOperatorName)`. It is a new template, as the existing `SvRewardState` cannot get a key or rename `svName` in an upgrade; `SvRewardState` stays for minting before the migration, which creates the new states and carries over `lastRoundCollected`.
- `DsoRules.svRightOwners` holds the owner names.
- The owner updates its beneficiaries with `baseInfo` and `newInfo`, like `DsoRules_UpdateSvRightOwnerInfo`.
- `escrowedRewardWeight` replaces the ghost parties, see below.
- Not in this PoC: dropping instructions through the key, the plural rename, the move of the instructions to `Splice.DSO.Instruction`.

## Escrowed weight without ghost parties

Today escrowed weight goes to a ghost party that never mints; its coupons expire into `UnclaimedReward`. Here the escrowed weight sits on the owner's assignment, next to its active weight, and only a vote changes it. The owner's beneficiaries share only `rewardWeight`.

When minting, the operator creates one more `SvRewardCoupon` for the escrowed weight, with `beneficiary = dso`. Nobody claims it, so it expires into `UnclaimedReward` through the existing `DsoRules_ClaimExpiredRewards`, as ghost coupons do today. Releasing escrow stays as today (CIP-0066).

```mermaid
flowchart LR
    A["OperatorAssignment<br/>rewardWeight 10000<br/>escrowedRewardWeight 40000"] -->|"mint, round N"| C1["SvRewardCoupon<br/>beneficiary ft, weight 10000"]
    A -->|"mint, round N"| C2["SvRewardCoupon<br/>beneficiary dso, weight 40000"]
    C1 -->|"ft claims"| CC["Amulet"]
    C2 -->|"round closed, not claimed:<br/>DsoRules_ClaimExpiredRewards"| U["UnclaimedReward"]
    U -->|"milestone met: vote (CIP-0066)"| R["UnclaimedActivityRecord<br/>to the company"]
```

Why a coupon to `dso` and not an `UnclaimedReward` created when minting:

- The amount is not known yet. It is set later, when the round is summarized.
- A round's SV rewards are split by the weight of the coupons created in that round. Escrowed weight without a coupon would not count, and the other SVs would get its part.
- A coupon to `dso` needs no new template and no Scala change, and only a vote can act as `dso`, so nobody can mint it.
