..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _module-splice-api-token-allocationrequestv2-73967:

Splice.Api.Token.AllocationRequestV2
====================================

V2 of the AllocationRequest interface, which is used by applications
to inform their users that they should create allocations for a specific settlement\.

Users may view and react to these requests using for example a token standard
wallet or custom automation that uses the Ledger API directly\.

Interfaces
----------

.. _type-splice-api-token-allocationrequestv2-allocationrequest-94785:

**interface** `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_

  A request by an app for allocations to be created to enable the execution of a settlement\.

  Apps MAY use one or more requests per settlement, depending on their needs
  and confidentiality requirements\.

  Apps SHOULD have as contract observers both the owners and providers of all
  authorizer accounts that need to create requested allocations\.

  **viewtype** `AllocationRequestView <type-splice-api-token-allocationrequestv2-allocationrequestview-55010_>`_

  + .. _type-splice-api-token-allocationrequestv2-allocationrequestaccept-64401:

    **Choice** `AllocationRequest_Accept <type-splice-api-token-allocationrequestv2-allocationrequestaccept-64401_>`_

    Signal to settlement\.executors that the requested allocations were or will be created\.

    Wallets MAY call this choice in the same transaction as the creation
    of the requested allocations to protect from creating the same
    allocations multiple times\.

    Apps MUST cleanup allocation requests independently of whether this choice is
    called, as there is no guarantee that the choice will be called\.

    IMPORTANT\: implementations MUST ensure that the allocation request is
    consumed by the body of this choice\.

    Controller\: actors

    Returns\: `AllocationRequest_AcceptResult <type-splice-api-token-allocationrequestv2-allocationrequestacceptresult-50636_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - actors
         - \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]
         - Set of parties executing the acceptance\.  Implementations MUST check these parties to avoid unauthorized acceptance\.  Implementations SHOULD allow calling this choice for all parties that can create an allocation matching this request\.
       * - extraArgs
         - ExtraArgs
         - Additional context required in order to exercise the choice\.

  + .. _type-splice-api-token-allocationrequestv2-allocationrequestreject-6716:

    **Choice** `AllocationRequest_Reject <type-splice-api-token-allocationrequestv2-allocationrequestreject-6716_>`_

    Reject an allocation request to signal that no allocation will be created for it\.

    The choice is nonconsuming to support alternative consumption patterns,
    e\.g\., by calling the consuming V1\.AllocationRequest\_Reject choice for
    transaction parsing compatibility\.

    IMPORTANT\: implementations MUST ensure that the allocation request is
    consumed by the body of this choice\.

    Controller\: actors

    Returns\: `AllocationRequest_RejectResult <type-splice-api-token-allocationrequestv2-allocationrequestrejectresult-42801_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - actors
         - \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]
         - Set of parties executing the rejection\.  Implementations MUST check these parties to avoid unauthorized rejection\.  Implementations SHOULD allow calling this choice for all parties that can create an allocation matching this request\.
       * - extraArgs
         - ExtraArgs
         - Additional context required in order to exercise the choice\.

  + .. _type-splice-api-token-allocationrequestv2-allocationrequestwithdraw-15869:

    **Choice** `AllocationRequest_Withdraw <type-splice-api-token-allocationrequestv2-allocationrequestwithdraw-15869_>`_

    Withdraw an allocation request as the executors\.

    The choice is nonconsuming to support alternative consumption patterns,
    e\.g\., by calling the consuming V1\.AllocationRequest\_Withdraw choice for
    transaction parsing compatibility\.

    IMPORTANT\: implementations MUST ensure that the allocation request is
    consumed by the body of this choice\.

    Controller\: actors

    Returns\: `AllocationRequest_WithdrawResult <type-splice-api-token-allocationrequestv2-allocationrequestwithdrawresult-84796_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - actors
         - \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]
         - Set of parties executing the withdrawal\.  Implementations MUST check these parties to avoid unauthorized withdrawal\.  Implementations SHOULD allow the ``settlement.executors`` to jointly call this choice\.
       * - extraArgs
         - ExtraArgs
         - Additional context required in order to exercise the choice\.

  + **Choice** Archive

    Controller\: Signatories of implementing template

    Returns\: ()

    (no fields)

  + **Method allocationRequest\_acceptExtraObservers \:** `AllocationRequest_Accept <type-splice-api-token-allocationrequestv2-allocationrequestaccept-64401_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

  + **Method allocationRequest\_acceptImpl \:** `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ \-\> `AllocationRequest_Accept <type-splice-api-token-allocationrequestv2-allocationrequestaccept-64401_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationRequest_AcceptResult <type-splice-api-token-allocationrequestv2-allocationrequestacceptresult-50636_>`_

  + **Method allocationRequest\_rejectExtraObservers \:** `AllocationRequest_Reject <type-splice-api-token-allocationrequestv2-allocationrequestreject-6716_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

  + **Method allocationRequest\_rejectImpl \:** `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ \-\> `AllocationRequest_Reject <type-splice-api-token-allocationrequestv2-allocationrequestreject-6716_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationRequest_RejectResult <type-splice-api-token-allocationrequestv2-allocationrequestrejectresult-42801_>`_

  + **Method allocationRequest\_withdrawExtraObservers \:** `AllocationRequest_Withdraw <type-splice-api-token-allocationrequestv2-allocationrequestwithdraw-15869_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

  + **Method allocationRequest\_withdrawImpl \:** `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ \-\> `AllocationRequest_Withdraw <type-splice-api-token-allocationrequestv2-allocationrequestwithdraw-15869_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationRequest_WithdrawResult <type-splice-api-token-allocationrequestv2-allocationrequestwithdrawresult-84796_>`_

Data Types
----------

.. _type-splice-api-token-allocationrequestv2-allocationrequestaction-94189:

**data** `AllocationRequestAction <type-splice-api-token-allocationrequestv2-allocationrequestaction-94189_>`_

  Actions available on an allocation request\.

  .. _constr-splice-api-token-allocationrequestv2-araaccept-48821:

  `ARA_Accept <constr-splice-api-token-allocationrequestv2-araaccept-48821_>`_


  .. _constr-splice-api-token-allocationrequestv2-arareject-23432:

  `ARA_Reject <constr-splice-api-token-allocationrequestv2-arareject-23432_>`_


  .. _constr-splice-api-token-allocationrequestv2-aracustom-78856:

  `ARA_Custom <constr-splice-api-token-allocationrequestv2-aracustom-78856_>`_

    Used to represent app\-specific actions on allocation requests\.

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - id
         - `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_
         - Identifier of the action\. Namespaced analogously to metadata keys\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `AllocationRequestAction <type-splice-api-token-allocationrequestv2-allocationrequestaction-94189_>`_

  **instance** `Ord <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-ord-6395>`_ `AllocationRequestAction <type-splice-api-token-allocationrequestv2-allocationrequestaction-94189_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `AllocationRequestAction <type-splice-api-token-allocationrequestv2-allocationrequestaction-94189_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"availableActions\" `AllocationRequestView <type-splice-api-token-allocationrequestv2-allocationrequestview-55010_>`_ (`Map <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-map-90052>`_ `AllocationRequestAction <type-splice-api-token-allocationrequestv2-allocationrequestaction-94189_>`_ \[\[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]\])

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"id\" `AllocationRequestAction <type-splice-api-token-allocationrequestv2-allocationrequestaction-94189_>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"availableActions\" `AllocationRequestView <type-splice-api-token-allocationrequestv2-allocationrequestview-55010_>`_ (`Map <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-map-90052>`_ `AllocationRequestAction <type-splice-api-token-allocationrequestv2-allocationrequestaction-94189_>`_ \[\[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]\])

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"id\" `AllocationRequestAction <type-splice-api-token-allocationrequestv2-allocationrequestaction-94189_>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

.. _type-splice-api-token-allocationrequestv2-allocationrequestview-55010:

**data** `AllocationRequestView <type-splice-api-token-allocationrequestv2-allocationrequestview-55010_>`_

  View of an ``AllocationRequest``\.

  .. _constr-splice-api-token-allocationrequestv2-allocationrequestview-93959:

  `AllocationRequestView <constr-splice-api-token-allocationrequestv2-allocationrequestview-93959_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - originalRequestCid
         - `Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_)
         - The contract id of the original allocation request contract, which is ``None`` for the original allocation request itself\.  This SHOULD be used by wallets to correlate the same allocation request across updates to its state\. It should not be used to correlate different allocation requests for the same settlement\. That can be done using the ``settlement`` field\.
       * - settlement
         - SettlementInfo
         - Settlement for which allocations are requested to be created\.
       * - allocations
         - \[AllocationSpecification\]
         - The allocations that are requested to be authorized for execution as part of the settlement\.  Wallets SHOULD check their ``authorizer`` to see whether this request requires action from their users or merely serves informational purposes\.
       * - requestedAt
         - `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_
         - Timestamp at which the request was created\.
       * - settleAt
         - `Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_
         - Timestamp at which the settlement is expected to be executed\. The authorizer SHOULD create their allocations before this time\.  For iterated settlements, this is the expected time of the first iteration\.
       * - availableActions
         - `Map <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-map-90052>`_ `AllocationRequestAction <type-splice-api-token-allocationrequestv2-allocationrequestaction-94189_>`_ \[\[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]\]
         - What actions are available to which groups of parties\. The list of lists is interpreted as a set of sets and represents a disjunction of conjunctions of parties, i\.e\., each inner list represents a group of parties that can act jointly to execute the action\.  This field can be used to inform wallet users whether they can take an action or not; and which other parties they might be waiting on to take their action\.  Supports multiple parties for actions that require joint authorization\. All possible combinations of action actors that could call a choice SHOULD be included\.
       * - meta
         - Metadata
         - Additional metadata specific to the allocation request, used for extensibility\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `AllocationRequestView <type-splice-api-token-allocationrequestv2-allocationrequestview-55010_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `AllocationRequestView <type-splice-api-token-allocationrequestv2-allocationrequestview-55010_>`_

  **instance** `HasFromAnyView <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Internal-Interface-AnyView.html#class-da-internal-interface-anyview-hasfromanyview-30108>`_ `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ `AllocationRequestView <type-splice-api-token-allocationrequestv2-allocationrequestview-55010_>`_

  **instance** `HasInterfaceView <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-interface-hasinterfaceview-4492>`_ `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ `AllocationRequestView <type-splice-api-token-allocationrequestv2-allocationrequestview-55010_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"allocations\" `AllocationRequestView <type-splice-api-token-allocationrequestv2-allocationrequestview-55010_>`_ \[AllocationSpecification\]

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"availableActions\" `AllocationRequestView <type-splice-api-token-allocationrequestv2-allocationrequestview-55010_>`_ (`Map <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-map-90052>`_ `AllocationRequestAction <type-splice-api-token-allocationrequestv2-allocationrequestaction-94189_>`_ \[\[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]\])

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `AllocationRequestView <type-splice-api-token-allocationrequestv2-allocationrequestview-55010_>`_ Metadata

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"originalRequestCid\" `AllocationRequestView <type-splice-api-token-allocationrequestv2-allocationrequestview-55010_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_))

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"requestedAt\" `AllocationRequestView <type-splice-api-token-allocationrequestv2-allocationrequestview-55010_>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"settleAt\" `AllocationRequestView <type-splice-api-token-allocationrequestv2-allocationrequestview-55010_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_)

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"settlement\" `AllocationRequestView <type-splice-api-token-allocationrequestv2-allocationrequestview-55010_>`_ SettlementInfo

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"allocations\" `AllocationRequestView <type-splice-api-token-allocationrequestv2-allocationrequestview-55010_>`_ \[AllocationSpecification\]

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"availableActions\" `AllocationRequestView <type-splice-api-token-allocationrequestv2-allocationrequestview-55010_>`_ (`Map <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-map-90052>`_ `AllocationRequestAction <type-splice-api-token-allocationrequestv2-allocationrequestaction-94189_>`_ \[\[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]\])

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `AllocationRequestView <type-splice-api-token-allocationrequestv2-allocationrequestview-55010_>`_ Metadata

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"originalRequestCid\" `AllocationRequestView <type-splice-api-token-allocationrequestv2-allocationrequestview-55010_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_))

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"requestedAt\" `AllocationRequestView <type-splice-api-token-allocationrequestv2-allocationrequestview-55010_>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"settleAt\" `AllocationRequestView <type-splice-api-token-allocationrequestv2-allocationrequestview-55010_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_)

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"settlement\" `AllocationRequestView <type-splice-api-token-allocationrequestv2-allocationrequestview-55010_>`_ SettlementInfo

.. _type-splice-api-token-allocationrequestv2-allocationrequestacceptresult-50636:

**data** `AllocationRequest_AcceptResult <type-splice-api-token-allocationrequestv2-allocationrequestacceptresult-50636_>`_

  The result of the ``AllocationRequest_Accept`` choice\.

  .. _constr-splice-api-token-allocationrequestv2-allocationrequestacceptresult-68411:

  `AllocationRequest_AcceptResult <constr-splice-api-token-allocationrequestv2-allocationrequestacceptresult-68411_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - meta
         - Metadata
         - Additional metadata specific to the result of accepting the allocation request, used for extensibility\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `AllocationRequest_AcceptResult <type-splice-api-token-allocationrequestv2-allocationrequestacceptresult-50636_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `AllocationRequest_AcceptResult <type-splice-api-token-allocationrequestv2-allocationrequestacceptresult-50636_>`_

  **instance** HasMethod `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ \"allocationRequest\_acceptImpl\" (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ \-\> `AllocationRequest_Accept <type-splice-api-token-allocationrequestv2-allocationrequestaccept-64401_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationRequest_AcceptResult <type-splice-api-token-allocationrequestv2-allocationrequestacceptresult-50636_>`_)

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `AllocationRequest_AcceptResult <type-splice-api-token-allocationrequestv2-allocationrequestacceptresult-50636_>`_ Metadata

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `AllocationRequest_AcceptResult <type-splice-api-token-allocationrequestv2-allocationrequestacceptresult-50636_>`_ Metadata

  **instance** `HasExercise <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ `AllocationRequest_Accept <type-splice-api-token-allocationrequestv2-allocationrequestaccept-64401_>`_ `AllocationRequest_AcceptResult <type-splice-api-token-allocationrequestv2-allocationrequestacceptresult-50636_>`_

  **instance** `HasExerciseGuarded <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ `AllocationRequest_Accept <type-splice-api-token-allocationrequestv2-allocationrequestaccept-64401_>`_ `AllocationRequest_AcceptResult <type-splice-api-token-allocationrequestv2-allocationrequestacceptresult-50636_>`_

  **instance** `HasFromAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ `AllocationRequest_Accept <type-splice-api-token-allocationrequestv2-allocationrequestaccept-64401_>`_ `AllocationRequest_AcceptResult <type-splice-api-token-allocationrequestv2-allocationrequestacceptresult-50636_>`_

  **instance** `HasToAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ `AllocationRequest_Accept <type-splice-api-token-allocationrequestv2-allocationrequestaccept-64401_>`_ `AllocationRequest_AcceptResult <type-splice-api-token-allocationrequestv2-allocationrequestacceptresult-50636_>`_

.. _type-splice-api-token-allocationrequestv2-allocationrequestrejectresult-42801:

**data** `AllocationRequest_RejectResult <type-splice-api-token-allocationrequestv2-allocationrequestrejectresult-42801_>`_

  The result of the ``AllocationRequest_Reject`` choice\.

  .. _constr-splice-api-token-allocationrequestv2-allocationrequestrejectresult-65686:

  `AllocationRequest_RejectResult <constr-splice-api-token-allocationrequestv2-allocationrequestrejectresult-65686_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - meta
         - Metadata
         - Additional metadata specific to the result of rejecting the allocation request, used for extensibility\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `AllocationRequest_RejectResult <type-splice-api-token-allocationrequestv2-allocationrequestrejectresult-42801_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `AllocationRequest_RejectResult <type-splice-api-token-allocationrequestv2-allocationrequestrejectresult-42801_>`_

  **instance** HasMethod `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ \"allocationRequest\_rejectImpl\" (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ \-\> `AllocationRequest_Reject <type-splice-api-token-allocationrequestv2-allocationrequestreject-6716_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationRequest_RejectResult <type-splice-api-token-allocationrequestv2-allocationrequestrejectresult-42801_>`_)

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `AllocationRequest_RejectResult <type-splice-api-token-allocationrequestv2-allocationrequestrejectresult-42801_>`_ Metadata

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `AllocationRequest_RejectResult <type-splice-api-token-allocationrequestv2-allocationrequestrejectresult-42801_>`_ Metadata

  **instance** `HasExercise <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ `AllocationRequest_Reject <type-splice-api-token-allocationrequestv2-allocationrequestreject-6716_>`_ `AllocationRequest_RejectResult <type-splice-api-token-allocationrequestv2-allocationrequestrejectresult-42801_>`_

  **instance** `HasExerciseGuarded <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ `AllocationRequest_Reject <type-splice-api-token-allocationrequestv2-allocationrequestreject-6716_>`_ `AllocationRequest_RejectResult <type-splice-api-token-allocationrequestv2-allocationrequestrejectresult-42801_>`_

  **instance** `HasFromAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ `AllocationRequest_Reject <type-splice-api-token-allocationrequestv2-allocationrequestreject-6716_>`_ `AllocationRequest_RejectResult <type-splice-api-token-allocationrequestv2-allocationrequestrejectresult-42801_>`_

  **instance** `HasToAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ `AllocationRequest_Reject <type-splice-api-token-allocationrequestv2-allocationrequestreject-6716_>`_ `AllocationRequest_RejectResult <type-splice-api-token-allocationrequestv2-allocationrequestrejectresult-42801_>`_

.. _type-splice-api-token-allocationrequestv2-allocationrequestwithdrawresult-84796:

**data** `AllocationRequest_WithdrawResult <type-splice-api-token-allocationrequestv2-allocationrequestwithdrawresult-84796_>`_

  The result of the ``AllocationRequest_Withdraw`` choice\.

  .. _constr-splice-api-token-allocationrequestv2-allocationrequestwithdrawresult-5639:

  `AllocationRequest_WithdrawResult <constr-splice-api-token-allocationrequestv2-allocationrequestwithdrawresult-5639_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - meta
         - Metadata
         - Additional metadata specific to the result of withdrawing the allocation request, used for extensibility\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `AllocationRequest_WithdrawResult <type-splice-api-token-allocationrequestv2-allocationrequestwithdrawresult-84796_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `AllocationRequest_WithdrawResult <type-splice-api-token-allocationrequestv2-allocationrequestwithdrawresult-84796_>`_

  **instance** HasMethod `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ \"allocationRequest\_withdrawImpl\" (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ \-\> `AllocationRequest_Withdraw <type-splice-api-token-allocationrequestv2-allocationrequestwithdraw-15869_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationRequest_WithdrawResult <type-splice-api-token-allocationrequestv2-allocationrequestwithdrawresult-84796_>`_)

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `AllocationRequest_WithdrawResult <type-splice-api-token-allocationrequestv2-allocationrequestwithdrawresult-84796_>`_ Metadata

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `AllocationRequest_WithdrawResult <type-splice-api-token-allocationrequestv2-allocationrequestwithdrawresult-84796_>`_ Metadata

  **instance** `HasExercise <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ `AllocationRequest_Withdraw <type-splice-api-token-allocationrequestv2-allocationrequestwithdraw-15869_>`_ `AllocationRequest_WithdrawResult <type-splice-api-token-allocationrequestv2-allocationrequestwithdrawresult-84796_>`_

  **instance** `HasExerciseGuarded <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ `AllocationRequest_Withdraw <type-splice-api-token-allocationrequestv2-allocationrequestwithdraw-15869_>`_ `AllocationRequest_WithdrawResult <type-splice-api-token-allocationrequestv2-allocationrequestwithdrawresult-84796_>`_

  **instance** `HasFromAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ `AllocationRequest_Withdraw <type-splice-api-token-allocationrequestv2-allocationrequestwithdraw-15869_>`_ `AllocationRequest_WithdrawResult <type-splice-api-token-allocationrequestv2-allocationrequestwithdrawresult-84796_>`_

  **instance** `HasToAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ `AllocationRequest_Withdraw <type-splice-api-token-allocationrequestv2-allocationrequestwithdraw-15869_>`_ `AllocationRequest_WithdrawResult <type-splice-api-token-allocationrequestv2-allocationrequestwithdrawresult-84796_>`_

Functions
---------

.. _function-splice-api-token-allocationrequestv2-allocationrequestacceptimpl-23055:

`allocationRequest_acceptImpl <function-splice-api-token-allocationrequestv2-allocationrequestacceptimpl-23055_>`_
  \: `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ \-\> `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ \-\> `AllocationRequest_Accept <type-splice-api-token-allocationrequestv2-allocationrequestaccept-64401_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationRequest_AcceptResult <type-splice-api-token-allocationrequestv2-allocationrequestacceptresult-50636_>`_

.. _function-splice-api-token-allocationrequestv2-allocationrequestrejectimpl-606:

`allocationRequest_rejectImpl <function-splice-api-token-allocationrequestv2-allocationrequestrejectimpl-606_>`_
  \: `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ \-\> `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ \-\> `AllocationRequest_Reject <type-splice-api-token-allocationrequestv2-allocationrequestreject-6716_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationRequest_RejectResult <type-splice-api-token-allocationrequestv2-allocationrequestrejectresult-42801_>`_

.. _function-splice-api-token-allocationrequestv2-allocationrequestwithdrawimpl-24655:

`allocationRequest_withdrawImpl <function-splice-api-token-allocationrequestv2-allocationrequestwithdrawimpl-24655_>`_
  \: `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ \-\> `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ \-\> `AllocationRequest_Withdraw <type-splice-api-token-allocationrequestv2-allocationrequestwithdraw-15869_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationRequest_WithdrawResult <type-splice-api-token-allocationrequestv2-allocationrequestwithdrawresult-84796_>`_

.. _function-splice-api-token-allocationrequestv2-allocationrequestacceptextraobservers-53780:

`allocationRequest_acceptExtraObservers <function-splice-api-token-allocationrequestv2-allocationrequestacceptextraobservers-53780_>`_
  \: `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ \-\> `AllocationRequest_Accept <type-splice-api-token-allocationrequestv2-allocationrequestaccept-64401_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

.. _function-splice-api-token-allocationrequestv2-allocationrequestrejectextraobservers-15805:

`allocationRequest_rejectExtraObservers <function-splice-api-token-allocationrequestv2-allocationrequestrejectextraobservers-15805_>`_
  \: `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ \-\> `AllocationRequest_Reject <type-splice-api-token-allocationrequestv2-allocationrequestreject-6716_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

.. _function-splice-api-token-allocationrequestv2-allocationrequestwithdrawextraobservers-97788:

`allocationRequest_withdrawExtraObservers <function-splice-api-token-allocationrequestv2-allocationrequestwithdrawextraobservers-97788_>`_
  \: `AllocationRequest <type-splice-api-token-allocationrequestv2-allocationrequest-94785_>`_ \-\> `AllocationRequest_Withdraw <type-splice-api-token-allocationrequestv2-allocationrequestwithdraw-15869_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]
