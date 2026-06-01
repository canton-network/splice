..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

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

      - Add a new interface package ``splice-api-reward-assignment-v1``.
        Apps whose ultimate beneficiaries are different from the app provider party (e.g., decentralized apps) can use the
        this package to assign the rewards to their ultimate beneficiaries.

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

      - The Daml changes require an upgrade to these versions:

          ================== =======
          name               version
          ================== =======
          amulet             0.1.19
          amuletNameService  0.1.20
          dsoGovernance      0.1.25
          wallet             0.1.20
          walletPayments     0.1.19
          ================== =======

    - Validator app

      - ``TransferPreapprovalProposal``s are now only accepted for parties hosted on your node.
        To recover the prior behavior you can enable ``canton.validator-apps.validator_backend.transfer-preapproval.accept-non-hosted-preapproval-proposals = true``.

        Thanks to `Rhaydden <https://github.com/Rhaydden>`_ for reporting.

    - Deployment

      - Remove leftover code from Hard Synchronizer Migrations. In particular the ``migration`` flags are gone from SV and validator helm charts as well as the ``-M`` option in docker compose.
        The ``legacyId`` flag is also removed from the SV chart.

      - Changed the default JVM args from ``-Dscala.concurrent.context.numThreads=8 -XX:ActiveProcessorCount=8`` to  ``-Dscala.concurrent.context.numThreads=12 -XX:ActiveProcessorCount=12``
        for the participant, mediator, sequencer, sv app and scan app deployments.

    - Observability

      - The automation background services no longer affect the ``/readyz`` endpoint
        of Splice apps and instead were replaced with a ``splice_automation_background_service_health`` gauge that reports
        the health of each background service registered with an automation service. The gauge is ``0`` when the service
        is healthy and ``1`` when it is unhealthy, and is labeled with
        ``automation_service`` and ``service``.

    - Scan

      - The following deprecated endpoints have been removed from the public API:

          - ``/v0/rewards-collected``
          - ``/v0/round-party-totals``
          - ``/v0/round-totals``
          - ``/v0/aggregated-rounds``
          - ``/v0/round-of-latest-data``

    - SV app

      - Amulet-based expiry triggers now skip batches whose preferred amulet package version
        is listed in the ``ignored-amulet-versions`` configuration and prior to the minimal supported version.
        Parties from skipped batches are added to an in-memory ignore list. This allows sv-1 to better handle
        participants that have not yet vetted the latest amulet package and avoid repeated failures.
