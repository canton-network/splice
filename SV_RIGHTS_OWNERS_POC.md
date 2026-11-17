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
        operatorName : Text
        rewardWeight : Int
        beneficiaries : [BeneficiaryInfo]
        defaultBeneficiary : Party
    }
    class BeneficiaryInfo {
        beneficiary : Party
        weight : Int
    }
    class SvRewardState {
        <<template>>
        dso : Party
        svRightsOwnerName : Text
        svOperatorName : Text
    }
    DsoRules ..> SvRightOwner : owner names
    SvRightOwner -- SvRightsOwnerKey : key
    SvRightOwner *-- SvRightOwnerInfo
    SvRightOwnerInfo *-- "1..n" OperatorAssignment
    OperatorAssignment *-- "0..20" BeneficiaryInfo : paid in order
    OperatorAssignment ..> DsoRules : operatorName is an SV in svs
    OperatorAssignment ..> SvRewardState : one per owner and operator
```

Example: a subset of the MainNet [`approved-sv-id-values.yaml`](https://github.com/canton-foundation/configs/blob/f0aa02df12e8ff2332075f796c26f89f828dcaa9/configs/MainNet/approved-sv-id-values.yaml) in the new model. Parties are shortened.

- `Digital-Asset`: one owner with two assignments at two operators.
- `Circle`: hosted by GSF, 70000 to a staking party, the rest to the default beneficiary.
- `Liberty-City-Ventures-1`: runs its own SV node, 70000 to one party, the rest to the owner party.
- `Franklin-Templeton` and `FranklinTempleton-ghost-1`: the active party and the escrow party are two separate owners.

```jsonc
{
  "DsoRules": {
    "svs": ["Digital-Asset-1", "Digital-Asset-2", "Global-Synchronizer-Foundation", "Liberty-City-Ventures-1"],
    "svRightOwners": ["Circle", "Digital-Asset", "Franklin-Templeton", "FranklinTempleton-ghost-1", "Liberty-City-Ventures-1"]
  },
  "SvRightOwner": [
    {
      "name": "Digital-Asset",
      "info": {
        "rightOwnerParty": "<Digital-Asset-1 SV party>",
        "description": "",
        "operatorAssignments": {
          "Digital-Asset-1": {
            "operatorName": "Digital-Asset-1",
            "rewardWeight": 159250,
            "beneficiaries": [],
            "defaultBeneficiary": "<Digital-Asset-1 SV party>"
          },
          "Digital-Asset-2": {
            "operatorName": "Digital-Asset-2",
            "rewardWeight": 159250,
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
          "Circle": {
            "operatorName": "Global-Synchronizer-Foundation",
            "rewardWeight": 100000,
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
            "operatorName": "Liberty-City-Ventures-1",
            "rewardWeight": 100000,
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
          "Franklin-Templeton": {
            "operatorName": "Global-Synchronizer-Foundation",
            "rewardWeight": 10000,
            "beneficiaries": [],
            "defaultBeneficiary": "ft::12200b55…"
          }
        }
      }
    },
    {
      "name": "FranklinTempleton-ghost-1",
      "info": {
        "rightOwnerParty": "FranklinTempleton-ghost-1::1220f5cf…",
        "description": "CIP-0118, escrow",
        "operatorAssignments": {
          "FranklinTempleton-ghost-1": {
            "operatorName": "Global-Synchronizer-Foundation",
            "rewardWeight": 40000,
            "beneficiaries": [],
            "defaultBeneficiary": "FranklinTempleton-ghost-1::1220f5cf…"
          }
        }
      }
    }
  ],
  // key: (dso, svRightsOwnerName, svOperatorName)
  "SvRewardState": [
    { "svRightsOwnerName": "Digital-Asset", "svOperatorName": "Digital-Asset-1" },
    { "svRightsOwnerName": "Digital-Asset", "svOperatorName": "Digital-Asset-2" },
    { "svRightsOwnerName": "Circle", "svOperatorName": "Global-Synchronizer-Foundation" },
    { "svRightsOwnerName": "Liberty-City-Ventures-1", "svOperatorName": "Liberty-City-Ventures-1" },
    { "svRightsOwnerName": "Franklin-Templeton", "svOperatorName": "Global-Synchronizer-Foundation" },
    { "svRightsOwnerName": "FranklinTempleton-ghost-1", "svOperatorName": "Global-Synchronizer-Foundation" }
  ]
}
```

- One `SvRightOwner` per owner, with key `SvRightsOwnerKey`, a `description` and one `OperatorAssignment` per SV node operator.
- Each assignment has its own weight, operator, ordered beneficiaries with absolute weights, and a default beneficiary for the rest.
- One `SvRewardState` per owner and operator, with key `(dso, svRightsOwnerName, svOperatorName)`.
- `DsoRules.svRightOwners` holds the owner names.
- The owner updates its beneficiaries with `baseInfo` and `newInfo`, like `DsoRules_UpdateSvRightOwnerInfo`.
- Not in this PoC: the `SvRewardState` key above (the code still has today's `svName`), `escrowedRewardWeight`, dropping instructions through the key, the plural rename, the move of the instructions to `Splice.DSO.Instruction`.
