..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

  .. note::

    This release includes the Token Standard V2 APIs (`CIP-112 <https://github.com/canton-foundation/cips/blob/main/cip-0112/cip-0112.md>`__)
    and their implementation for Amulet.

    **CC holders**: ensure your validator node (or your wallet provider's
    validator node) is running a version of the validator that supports the
    Token Standard V2 APIs, so that you and your counter-parties can use the new
    allocation and transfer workflows when sending or receiving CC.

    **Wallet providers**: consider adding support for the Token Standard V2 APIs in your wallet.
    Adding support for the V2 allocation workflows unlocks valuable new functionality for
    your users. Using the V2 tx history events simplifies presenting the transaction
    history to your users. Furthermore, adding support for accounts in your UIs enables
    interacting with assets with rich account structures, which are commonly used in the TradFi world.

    **Registry providers**: consider implementing support for the Token Standard V2 APIs for your tokens,
    so they can be used by apps and wallets that rely on the V2 token standard.

    **App providers**: review the V2 APIs and consider where their use allows you to improve your app's UX
    and/or reduce its traffic cost. Trading apps in particular can benefit from the improved
    functionality of the V2 allocation APIs.

  - Wallet

    - Support token standard V2 allocations and transfers of Amulet in the Splice Amulet Wallet UI.

      Creating all requested allocations in a single transaction
      `as documented in CIP-112 <https://github.com/canton-foundation/cips/blob/main/cip-0112/cip-0112.md#423-traders-accept-allocation-requests-and-create-allocations>`__
      is not supported by the Splice Amulet Wallet UI, as it only works for Amulet allocations.
      It therefore also does not call ``V2.AllocationRequest_Accept`` on the allocation request,
      to avoid archiving the allocation request while it is still required to create allocations
      of other assets using their registry-specific UIs.

  - Validator

    - Upload and vet the ``splice-util-token-standard-wallet.dar`` by default, so that
      wallets can make use of the new batching functionality (see below) on all validator
      nodes.

    - Fix an issue where calling ``external-party/topology/submit``
      during topology freeze time resulted in retries for the same
      party after freeze time failing with
      ``TOPOLOGY_NO_APPROPRIATE_SIGNING_KEY_IN_STORE``.

  - Daml

    - Release the Token Standard V2 APIs (`CIP-112 <https://github.com/canton-foundation/cips/blob/main/cip-0112/cip-0112.md>`__).
      Their definitions and ``.dar`` files are available from
      the Splice GitHub repository
      (see here for `definitions <https://github.com/canton-network/splice/tree/main/token-standard>`__ and `.dar files <https://github.com/canton-network/splice/tree/main/daml/dars>`__).

      Tokens wanting to support the V2 token standard MUST implement both the Daml interfaces and the OpenAPI
      specifications. They can choose whether to implement both the allocation and transfer APIs, or only one of them.

    - Implement all Token Standard V2 APIs for Amulet, which adds the following new features to Amulet:

      - Add support for committed Amulet allocations that lock their funds until the settlement deadline.

      - Add support for iterated Amulet allocations, which allow executors to specify the actual
        funds transferred post-hoc as part of settlement, provided the allocated funds cover the actual transfer.
        See the `discussion on the mailing list <https://lists.sync.global/g/cip-discuss/message/743>`__ for more information.

      - Support creating Amulet allocations for burn. This prepares for the option of using
        allocations to pay for ANS entries, traffic purchases, or other future use-cases.

      - Allow executing single-step transfers using the V2 transfer factory interface for
        cases where both sender and receiver authorization is available.

      - Greatly simplify the parsing of Amulet transaction history for wallets
        and explorers by reporting all holding changes as
        :ref:`V2.EventLog_HoldingsChange <type-splice-api-token-transfereventsv2-eventlogholdingschange-79855>` events.

        Note that this required adding a ``meta : Optional Metadata`` field to the ``AmuletRules.TransferOutput`` type and
        the ``TransferPreapproval_SendV2`` choice to properly propagate metadata from transfer creation to
        its actual execution and event reporting.

      - Introduce the new ``AmuletConfig.tokenStandardMaxTTL`` config parameter, which restricts
        the maximum lifetime of transfer instructions and amulet allocations,
        with a default value of 90 days. This is a Denial-of-Service protection against filling
        the SVs' active contract store with long-lived allocations and transfer instructions.
        The DSO party can expire both V1 and V2 amulet allocations older than
        ``tokenStandardMaxTTL``, even if the settlement deadline has not yet passed.

      - Properly classify the burn of Amulet for ANS entries in the V2 token standard transaction history.

    - Extend the ``splice-util-token-standard-wallet.dar`` with the
      ``Splice.Util.Token.Wallet.BatchingUtilityV2`` template for batching
      multiple token standard V1 or V2 actions in a single transaction.

    - Switch to Daml SDK 3.5.2 for all non-API packages and the token standard V2 APIs,
      which is the reason for why the version of ```splice-*`` packages unrelated to Token Standard V2
      implementation was bumped as well.

    - These changes require a Daml upgrade to the following versions:

        ================== =======
        name               version
        ================== =======
        amulet             0.1.21
        amuletNameService  0.1.22
        dsoGovernance      0.1.27
        validatorLifecycle 0.1.8
        wallet             0.1.22
        walletPayments     0.1.21
        ================== =======
