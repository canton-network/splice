..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - Scan UI

      - Bring back the governance page that was removed in release 0.5.18.

    - Token Standard V2 (CIP-112)

      - Notable callouts for Amulet changes:
          - add a ``meta : Optional Metadata`` field to the ``AmuletRules.TransferOutput`` type and
            the ``TransferPreapproval_SendV2`` choice
          - properly classify the burn of ANS in the V2 token standard transaction history

      - Add preview of the V2 token standard APIs and implement them for Amulet

      - Add support for creating Allocations V2 of Amulet in the Splice Amulet Wallet UI.
        This is meant for users that create the allocations for an allocation request
        using the registry specific UIs for each asset. The Amulet Wallet UI
        therefore does not archive the V2 AllocationRequest when creating the
        Amulet Allocation for it, so that the allocation request is visible in the other
        registry UIs as well.

        For creating all allocations in a single transaction `as documented in CIP-112 <https://github.com/canton-foundation/cips/blob/main/cip-0112/cip-0112.md#423-traders-accept-allocation-requests-and-create-allocations>`__, we recommend using
        a token standard v2 wallet UI that uniformly supports all V1 and V2 assets.

      .. TODO(#4707): add callouts for wallets, explorers, SVs, validator operators, app operators as needed
      .. TODO(#4707): add Daml versions of token standard to release notes
        - The ``app_activity_record_store`` table has been modified to avoid unexpected DB performance issues.
          This required clearing the existing data in this table which has been ingested since the ``0.5.18`` release.
          This impacts the data being served via the experimental field ``app_activity_records`` on the ``/v0/events`` and ``/v0/events/{update_id}`` endpoints.
          Specifically the ``app_activity_records`` field will not contain the
          data which has been provided for the events which happened between the ``0.5.18`` and this release.
          Note that the ``app_activity_records`` data already provided for events during this period is correct
          and the network explorers who have ingested this data should keep a copy of it.

    .. important::

      **Action recommended for validator operators:** upgrade to this release
      before the SVs start testing traffic-based app rewards in dry-run mode
      (see `SV Longterm Operations Schedule <https://docs.google.com/document/d/1QhLL5bL0u8temBL86y957VbWDtZJhH9udH-_C7nBlvc/edit?tab=t.0#heading=h.ripdn5ydglli>`__ for dates for the different networks).
      Otherwise, CC transfers and reward collection will stop working for parties on your node until you upgrade.

      **Action recommended for app devs:** app's with Daml code that statically depends on ``splice-amulet``
      should recompile their Daml code
      to link against the new version of ``splice-amulet`` listed below. Otherwise, code involving CC transfers
      will stop working as both ``OpenMiningRound`` and ``AmuletRules`` include newly introduced config fields.

      Apps that build against the :ref:`token_standard` API are not required to change except for upgrading
      their validator node.

    - Daml

      - Add ``RewardCouponV2`` to represent rewards available from traffic-based app rewards that are computed
        by the SV apps off-ledger as described in `CIP 104 <https://github.com/canton-foundation/cips/blob/main/cip-0104/cip-0104.md>`__.
        They are created in an efficient batched fashion once per-round for every party that is eligible for traffic-based app rewards.

        In contrast to the existing reward coupons, these new coupons are using time based expiry,
        and can be minted by default up to 36h after their creation. Thereby allowing their beneficiaries
        to batch the minting to save traffic costs.

        They can be minted like all other coupon types using one of the following methods:

          1. Automated minting via the Splice Wallet backend that is part of the validator app,
             which works for onboarded internal parties and for external parties with a :ref:`minting delegation <minting-delegations>`.
          2. Direct minting by constructing calls to ``AmuletRules_Transfer`` that uses them as
             an transfer input. These calls can be made directly against the Ledger API, or indirectly
             via custom Daml code deployed to the validator node.

      - Add a new field ``rewardConfig`` to the ``AmuletConfig`` for configuring whether rounds should use
        traffic-based app rewards or on-ledger reward accounting, and whether traffic-based app reward coupon creation
        should be simulated in a dry-run mode. See the
        :ref:`RewardConfig <type-splice-amuletconfig-rewardconfig-87101>`
        data type definition for the list reward configuration fields and their semantics.

      - Store the current ``rewardConfig`` and ``trafficPrice`` on every ``OpenMiningRound`` contract when creating it.
        This information serves to synchronize the SV apps on the parameters to use for processing traffic-based app rewards.

      - Add ``CalculateRewardsV2`` and ``ProcessRewardsV2`` templates together with supporting code
        to implement the creation of the new reward coupons based on the reward
        values computed off-ledger by the SV apps.

      - Adjust the CC transfer implementation such that it stops creating featured app activity markers
        when it runs against a round (or external party configuration state) where traffic-based app rewards
        are enabled.
        Due to the propagation delay of updating the external party configuration state in the ``splice-amulet`` code,
        there will be a transition phase where token standard CC transfers still create featured app markers.
        These will be automatically archived as soon as traffic-based app rewards are enabled.
        Thus no double-issuance of rewards will occur.

