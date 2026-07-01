..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _module-splice-api-token-allocationv2-67287:

Splice.Api.Token.AllocationV2
=============================

This module defines V2 of the ``Allocation`` interface and supporting types\.

In contrast to V1, this interface supports\:

* authorizing multiple transfers in a single allocation
* only allocating the net amount of funds transferred to the allocation
* allow the executors to specify the actual transfers after creation of the allocation
* use the same allocation for multiple settlement iterations
* create committed allocations whose funds are locked until the settlement deadline
* specifying accounts directly in the transfer legs instead of using metadata
* flexible actors for extensibility
* implementation\-defined choice observers for view compression where
  confidentiality requirements allow for it

It also removes the need to specify the ``expectedAdmin`` party, as its value
is already specified in other choice arguments or not relevant (for ``_PublicFetch`` choices)\.

Interfaces
----------

.. _type-splice-api-token-allocationv2-allocation-27217:

**interface** `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_

  A contract representing the approval of the authorizer to send or receive
  the net amount of assets of the transfer legs as part of a settlement where
  the executors and instrument admin check that every transfer leg has a matching
  authorization from the otherside\.

  **viewtype** `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_

  + .. _type-splice-api-token-allocationv2-allocationcancel-98587:

    **Choice** `Allocation_Cancel <type-splice-api-token-allocationv2-allocationcancel-98587_>`_

    Cancel the allocation to release the funds and invalidate the approval
    to execute the settlement\.

    Typically called by

    * the executors once it is clear that the settlement will not be executed
    * the admin to cancel an expired allocation

    The choice is nonconsuming to support alternative consumption patterns,
    e\.g\., by calling the consuming V1\.Allocation\_Cancel choice for
    transaction parsing compatibility\.

    IMPORTANT\: implementations MUST ensure that the allocation is consumed by
    the body of this choice\.

    Controller\: actors

    Returns\: `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - actors
         - \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]
         - Set of parties executing the cancellation\.  Implementations MUST check these parties to avoid unauthorized cancellation\. By default, they SHOULD require them to be equal to the allocation ``executors``\.
       * - extraArgs
         - ExtraArgs
         - Additional context required in order to exercise the choice\.

  + .. _type-splice-api-token-allocationv2-allocationsettle-31244:

    **Choice** `Allocation_Settle <type-splice-api-token-allocationv2-allocationsettle-31244_>`_

    Settle the allocation by moving the net amount of the transfer legs\.

    If a settlement deadline is set, implementations MUST NOT allow calling
    this choice after the settlement deadline has passed\.

    The choice is nonconsuming to support alternative consumption patterns,
    e\.g\., by calling the consuming V1\.Allocation\_ExecuteTransfer choice for
    transaction parsing compatibility\.

    IMPORTANT\: implementations MUST ensure that the allocation is consumed by
    the body of this choice\.

    Controller\: actors

    Returns\: `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - actors
         - \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]
         - Set of parties executing the settlement\.  Implementations MUST check these parties to avoid unauthorized settlement execution\. By default, they SHOULD require them to be equal to the allocation ``admin`` and the ``executors``, so that they can jointly guarantee atomic settlement\.  This authorization is typically provided as part of the ``SettlementFactory_SettleBatch`` choice, which should be used by the ``executors`` to settle V2 allocations\.
       * - extraTransferLegSides
         - \[`TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_\]
         - Extra transfer leg sides to settle as part of settlement\.  They MUST NOT be set unless iterated settlement was enabled by the allocation's authorizer\.
       * - nextIterationFunding
         - `Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`TextMap <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-textmap-11691>`_ `Decimal <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_)
         - The funds to reserve for the next settlement iteration, if there is any\.  This MUST NOT be set unless iterated settlement was enabled by the allocation's authorizer\.  Setting this to ``None`` indicates that no further settlement iterations will be executed after this one\.
       * - extraArgs
         - ExtraArgs
         - Additional context required in order to exercise the choice\.

  + .. _type-splice-api-token-allocationv2-allocationwithdraw-18681:

    **Choice** `Allocation_Withdraw <type-splice-api-token-allocationv2-allocationwithdraw-18681_>`_

    Allow the authorizer to withdraw the allocation to release the funds
    and invalidate the approval to execute the settlement\. This can for
    example be used by the authorizer to undo a mistakenly created
    allocation\.

    For committed allocations (i\.e\., ``committed`` set to ``True``), this
    choice can only be exercised once the settlement deadline has passed\.

    The choice is nonconsuming to support alternative consumption patterns,
    e\.g\., by calling the consuming V1\.Allocation\_Withdraw choice for
    transaction parsing compatibility\.

    IMPORTANT\: implementations MUST ensure that the allocation is consumed by
    the body of this choice\.

    Controller\: actors

    Returns\: `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - actors
         - \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]
         - Set of parties executing the withdrawal\.  Implementations MUST check these parties to avoid unauthorized withdrawal\. By default they SHOULD allow the account parties of the authorizer to withdraw the allocation\.
       * - extraArgs
         - ExtraArgs
         - Additional context required in order to exercise the choice\.

  + **Choice** Archive

    Controller\: Signatories of implementing template

    Returns\: ()

    (no fields)

  + **Method allocation\_cancelExtraObservers \:** `Allocation_Cancel <type-splice-api-token-allocationv2-allocationcancel-98587_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

  + **Method allocation\_cancelImpl \:** `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ \-\> `Allocation_Cancel <type-splice-api-token-allocationv2-allocationcancel-98587_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_

  + **Method allocation\_settleExtraObservers \:** `Allocation_Settle <type-splice-api-token-allocationv2-allocationsettle-31244_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

  + **Method allocation\_settleImpl \:** `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ \-\> `Allocation_Settle <type-splice-api-token-allocationv2-allocationsettle-31244_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_

  + **Method allocation\_withdrawExtraObservers \:** `Allocation_Withdraw <type-splice-api-token-allocationv2-allocationwithdraw-18681_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

  + **Method allocation\_withdrawImpl \:** `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ \-\> `Allocation_Withdraw <type-splice-api-token-allocationv2-allocationwithdraw-18681_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_

.. _type-splice-api-token-allocationv2-settlementfactory-64321:

**interface** `SettlementFactory <type-splice-api-token-allocationv2-settlementfactory-64321_>`_

  A settlement factory enables the net settlement of a batch of allocations
  for the same instrument admin\.

  **viewtype** `SettlementFactoryView <type-splice-api-token-allocationv2-settlementfactoryview-93858_>`_

  + **Choice** Archive

    Controller\: Signatories of implementing template

    Returns\: ()

    (no fields)

  + .. _type-splice-api-token-allocationv2-settlementfactorypublicfetch-69193:

    **Choice** `SettlementFactory_PublicFetch <type-splice-api-token-allocationv2-settlementfactorypublicfetch-69193_>`_

    Controller\: actors

    Returns\: `SettlementFactoryView <type-splice-api-token-allocationv2-settlementfactoryview-93858_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - actors
         - \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]
         - Set of parties executing the fetch\.

  + .. _type-splice-api-token-allocationv2-settlementfactorysettlebatch-65399:

    **Choice** `SettlementFactory_SettleBatch <type-splice-api-token-allocationv2-settlementfactorysettlebatch-65399_>`_

    Settle a batch of allocations for instruments with the same instrument admin\.

    The choice is structured in this form for efficiency and privacy\. It
    enables the instrument admin to only perform net debits and credits for
    each account across all transfers being settled; and restrict visibility of
    each credit or debit to executors, admin, and affected account parties only\.

    Controller\: actors

    Returns\: `SettlementFactory_SettleBatchResult <type-splice-api-token-allocationv2-settlementfactorysettlebatchresult-32414_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - settlement
         - `SettlementInfo <type-splice-api-token-allocationv2-settlementinfo-44234_>`_
         - The settlement for which the allocations are settled\.
       * - transferLegs
         - \[`TransferLeg <type-splice-api-token-allocationv2-transferleg-34001_>`_\]
         - The transfers that are to be executed as part of the settlement\.  There MUST be at least one transfer leg\. All transfer legs MUST have the same instrument admin as the one of the factory\.
       * - allocations
         - \[`FinalizedAllocation <type-splice-api-token-allocationv2-finalizedallocation-95818_>`_\]
         - Allocations to settle\.  They serve as proof that all transfers executed as part of settlement were authorized by both sender and receiver\.  The implementation of this choice determines the validation performed by the instrument admin when settling an allocation created by an instrument holder\.
       * - actors
         - \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]
         - Allows setting non\-default controllers for settling the batch, which can be used for implementation specific authorization patterns\. Set of parties executing the settlement\.  Implementations MUST check this value to avoid unauthorized settlement execution\. By default they SHOULD check that they are equal to ``settlement.executors`` to provide maximal compatibility with apps\.
       * - extraArgs
         - ExtraArgs
         - Additional choice arguments\.

  + **Method settlementFactory\_publicFetchImpl \:** `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `SettlementFactory <type-splice-api-token-allocationv2-settlementfactory-64321_>`_ \-\> `SettlementFactory_PublicFetch <type-splice-api-token-allocationv2-settlementfactorypublicfetch-69193_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `SettlementFactoryView <type-splice-api-token-allocationv2-settlementfactoryview-93858_>`_

  + **Method settlementFactory\_settleBatchExtraObservers \:** `SettlementFactory_SettleBatch <type-splice-api-token-allocationv2-settlementfactorysettlebatch-65399_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

  + **Method settlementFactory\_settleBatchImpl \:** `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `SettlementFactory <type-splice-api-token-allocationv2-settlementfactory-64321_>`_ \-\> `SettlementFactory_SettleBatch <type-splice-api-token-allocationv2-settlementfactorysettlebatch-65399_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `SettlementFactory_SettleBatchResult <type-splice-api-token-allocationv2-settlementfactorysettlebatchresult-32414_>`_

Data Types
----------

.. _type-splice-api-token-allocationv2-allocationaction-86361:

**data** `AllocationAction <type-splice-api-token-allocationv2-allocationaction-86361_>`_

  Actions available on an allocation\.

  .. _constr-splice-api-token-allocationv2-aasettle-5287:

  `AA_Settle <constr-splice-api-token-allocationv2-aasettle-5287_>`_


  .. _constr-splice-api-token-allocationv2-aacancel-35556:

  `AA_Cancel <constr-splice-api-token-allocationv2-aacancel-35556_>`_


  .. _constr-splice-api-token-allocationv2-aawithdraw-9366:

  `AA_Withdraw <constr-splice-api-token-allocationv2-aawithdraw-9366_>`_


  .. _constr-splice-api-token-allocationv2-aacustom-71963:

  `AA_Custom <constr-splice-api-token-allocationv2-aacustom-71963_>`_

    Used to represent registry\-specific actions on allocations\.

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - id
         - `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_
         - Identifier of the action\. Namespaced analogously to metadata keys\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `AllocationAction <type-splice-api-token-allocationv2-allocationaction-86361_>`_

  **instance** `Ord <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-ord-6395>`_ `AllocationAction <type-splice-api-token-allocationv2-allocationaction-86361_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `AllocationAction <type-splice-api-token-allocationv2-allocationaction-86361_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"availableActions\" `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_ (`Map <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-map-90052>`_ `AllocationAction <type-splice-api-token-allocationv2-allocationaction-86361_>`_ \[\[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]\])

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"id\" `AllocationAction <type-splice-api-token-allocationv2-allocationaction-86361_>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"availableActions\" `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_ (`Map <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-map-90052>`_ `AllocationAction <type-splice-api-token-allocationv2-allocationaction-86361_>`_ \[\[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]\])

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"id\" `AllocationAction <type-splice-api-token-allocationv2-allocationaction-86361_>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

.. _type-splice-api-token-allocationv2-allocationresult-19648:

**data** `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_

  The result of a choice on an allocation\.

  .. _constr-splice-api-token-allocationv2-allocationresult-77853:

  `AllocationResult <constr-splice-api-token-allocationv2-allocationresult-77853_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - output
         - `AllocationResult_Output <type-splice-api-token-allocationv2-allocationresultoutput-24769_>`_
         - The output of the action\.
       * - authorizerHoldingCids
         - `TextMap <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-textmap-11691>`_ \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]
         - New holdings created for the authorizer as part of the settlement keyed by their ``instrumentId.id``\.
       * - meta
         - Metadata
         - Additional metadata specific to the settlement, used for extensibility\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_

  **instance** HasMethod `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ \"allocation\_cancelImpl\" (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ \-\> `Allocation_Cancel <type-splice-api-token-allocationv2-allocationcancel-98587_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_)

  **instance** HasMethod `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ \"allocation\_settleImpl\" (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ \-\> `Allocation_Settle <type-splice-api-token-allocationv2-allocationsettle-31244_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_)

  **instance** HasMethod `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ \"allocation\_withdrawImpl\" (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ \-\> `Allocation_Withdraw <type-splice-api-token-allocationv2-allocationwithdraw-18681_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_)

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"allocationSettleResults\" `SettlementFactory_SettleBatchResult <type-splice-api-token-allocationv2-settlementfactorysettlebatchresult-32414_>`_ \[`AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_\]

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"authorizerHoldingCids\" `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_ (`TextMap <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-textmap-11691>`_ \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\])

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_ Metadata

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"output\" `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_ `AllocationResult_Output <type-splice-api-token-allocationv2-allocationresultoutput-24769_>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"allocationSettleResults\" `SettlementFactory_SettleBatchResult <type-splice-api-token-allocationv2-settlementfactorysettlebatchresult-32414_>`_ \[`AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_\]

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"authorizerHoldingCids\" `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_ (`TextMap <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-textmap-11691>`_ \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\])

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_ Metadata

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"output\" `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_ `AllocationResult_Output <type-splice-api-token-allocationv2-allocationresultoutput-24769_>`_

  **instance** `HasExercise <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ `Allocation_Cancel <type-splice-api-token-allocationv2-allocationcancel-98587_>`_ `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_

  **instance** `HasExercise <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ `Allocation_Settle <type-splice-api-token-allocationv2-allocationsettle-31244_>`_ `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_

  **instance** `HasExercise <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ `Allocation_Withdraw <type-splice-api-token-allocationv2-allocationwithdraw-18681_>`_ `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_

  **instance** `HasExerciseGuarded <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ `Allocation_Cancel <type-splice-api-token-allocationv2-allocationcancel-98587_>`_ `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_

  **instance** `HasExerciseGuarded <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ `Allocation_Settle <type-splice-api-token-allocationv2-allocationsettle-31244_>`_ `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_

  **instance** `HasExerciseGuarded <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ `Allocation_Withdraw <type-splice-api-token-allocationv2-allocationwithdraw-18681_>`_ `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_

  **instance** `HasFromAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ `Allocation_Cancel <type-splice-api-token-allocationv2-allocationcancel-98587_>`_ `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_

  **instance** `HasFromAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ `Allocation_Settle <type-splice-api-token-allocationv2-allocationsettle-31244_>`_ `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_

  **instance** `HasFromAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ `Allocation_Withdraw <type-splice-api-token-allocationv2-allocationwithdraw-18681_>`_ `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_

  **instance** `HasToAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ `Allocation_Cancel <type-splice-api-token-allocationv2-allocationcancel-98587_>`_ `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_

  **instance** `HasToAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ `Allocation_Settle <type-splice-api-token-allocationv2-allocationsettle-31244_>`_ `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_

  **instance** `HasToAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ `Allocation_Withdraw <type-splice-api-token-allocationv2-allocationwithdraw-18681_>`_ `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_

.. _type-splice-api-token-allocationv2-allocationresultoutput-24769:

**data** `AllocationResult_Output <type-splice-api-token-allocationv2-allocationresultoutput-24769_>`_

  The output of changing the state of an allocation\.

  .. _constr-splice-api-token-allocationv2-allocationresultpending-27625:

  `AllocationResult_Pending <constr-splice-api-token-allocationv2-allocationresultpending-27625_>`_

    Use this result to communicate that the allocation is pending further steps\.

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - allocationCid
         - `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_
         - Contract id of the allocation representing the pending state\.

  .. _constr-splice-api-token-allocationv2-allocationresultsettled-8699:

  `AllocationResult_Settled <constr-splice-api-token-allocationv2-allocationresultsettled-8699_>`_

    The result of settling an allocation by exercising the ``Allocation_Settle`` choice\.

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - nextIterationAllocationCid
         - `Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_)
         - The new allocation created for the next settlement iteration, if any\.

  .. _constr-splice-api-token-allocationv2-allocationresultcancelled-57867:

  `AllocationResult_Cancelled <constr-splice-api-token-allocationv2-allocationresultcancelled-57867_>`_

    The result of the ``Allocation_Cancel`` choice when fully authorized\.

  .. _constr-splice-api-token-allocationv2-allocationresultwithdrawn-92018:

  `AllocationResult_Withdrawn <constr-splice-api-token-allocationv2-allocationresultwithdrawn-92018_>`_

    The result of the ``Allocation_Withdraw`` choice when fully authorized\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `AllocationResult_Output <type-splice-api-token-allocationv2-allocationresultoutput-24769_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `AllocationResult_Output <type-splice-api-token-allocationv2-allocationresultoutput-24769_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"allocationCid\" `AllocationResult_Output <type-splice-api-token-allocationv2-allocationresultoutput-24769_>`_ (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_)

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"nextIterationAllocationCid\" `AllocationResult_Output <type-splice-api-token-allocationv2-allocationresultoutput-24769_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_))

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"output\" `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_ `AllocationResult_Output <type-splice-api-token-allocationv2-allocationresultoutput-24769_>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"allocationCid\" `AllocationResult_Output <type-splice-api-token-allocationv2-allocationresultoutput-24769_>`_ (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_)

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"nextIterationAllocationCid\" `AllocationResult_Output <type-splice-api-token-allocationv2-allocationresultoutput-24769_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_))

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"output\" `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_ `AllocationResult_Output <type-splice-api-token-allocationv2-allocationresultoutput-24769_>`_

.. _type-splice-api-token-allocationv2-allocationspecification-21899:

**data** `AllocationSpecification <type-splice-api-token-allocationv2-allocationspecification-21899_>`_

  An approval by the authorizer to receive or send assets as part of
  settlement\.

  .. _constr-splice-api-token-allocationv2-allocationspecification-98616:

  `AllocationSpecification <constr-splice-api-token-allocationv2-allocationspecification-98616_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - admin
         - `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The asset admin of the instruments that are transferred as part of the settlement\.
       * - authorizer
         - Account
         - The account authorizing the transfers as part of the settlement\.
       * - transferLegSides
         - \[`TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_\]
         - The sides of transfer legs authorized by this allocation\.
       * - settlementDeadline
         - `Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_
         - The executors' and authorizer's agreed time\-to\-live for the allocation\.  After this time, if set, the allocation can no longer be settled, and the authorizer can withdraw the allocation to release the funds\.
       * - nextIterationFunding
         - `Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`TextMap <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-textmap-11691>`_ `Decimal <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_)
         - Amounts reserved for funding the next settlement iteration\.  Amounts are keyed by instrument id and MUST be positive\.  Setting this to ``None`` indicates that iterated settlement is disabled, and the allocation can only be settled once with exactly its specified transfer legs\. Setting this to an empty map indicates that iterated settlement is enabled, but that no funding for the next iteration is reserved by the authorizer\. This can be used when the authorizer expects incoming transfers in the next iteration, and thus does not need to reserve any funding\.
       * - committed
         - `Bool <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-bool-66265>`_
         - Whether the authorizer commits to the allocation until either  * the executors settle allocation, * the executors cancel the allocation, * the settlement deadline passed, or * the admin expires the allocation\.  If set to ``True``, then the authorizer cannot withdraw the allocation until the settlement deadline\. Use committed allocations for cases where the executors need a guarantee that the allocation will be available until settlement\.
       * - meta
         - Metadata
         - Additional metadata for the allocation specification, which can be used to store information about an allocation used in iterated settlement\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `AllocationSpecification <type-splice-api-token-allocationv2-allocationspecification-21899_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `AllocationSpecification <type-splice-api-token-allocationv2-allocationspecification-21899_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"admin\" `AllocationSpecification <type-splice-api-token-allocationv2-allocationspecification-21899_>`_ `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"allocation\" `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_ `AllocationSpecification <type-splice-api-token-allocationv2-allocationspecification-21899_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"authorizer\" `AllocationSpecification <type-splice-api-token-allocationv2-allocationspecification-21899_>`_ Account

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"committed\" `AllocationSpecification <type-splice-api-token-allocationv2-allocationspecification-21899_>`_ `Bool <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-bool-66265>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `AllocationSpecification <type-splice-api-token-allocationv2-allocationspecification-21899_>`_ Metadata

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"nextIterationFunding\" `AllocationSpecification <type-splice-api-token-allocationv2-allocationspecification-21899_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`TextMap <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-textmap-11691>`_ `Decimal <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_))

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"settlementDeadline\" `AllocationSpecification <type-splice-api-token-allocationv2-allocationspecification-21899_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_)

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"transferLegSides\" `AllocationSpecification <type-splice-api-token-allocationv2-allocationspecification-21899_>`_ \[`TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_\]

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"admin\" `AllocationSpecification <type-splice-api-token-allocationv2-allocationspecification-21899_>`_ `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"allocation\" `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_ `AllocationSpecification <type-splice-api-token-allocationv2-allocationspecification-21899_>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"authorizer\" `AllocationSpecification <type-splice-api-token-allocationv2-allocationspecification-21899_>`_ Account

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"committed\" `AllocationSpecification <type-splice-api-token-allocationv2-allocationspecification-21899_>`_ `Bool <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-bool-66265>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `AllocationSpecification <type-splice-api-token-allocationv2-allocationspecification-21899_>`_ Metadata

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"nextIterationFunding\" `AllocationSpecification <type-splice-api-token-allocationv2-allocationspecification-21899_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`TextMap <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-textmap-11691>`_ `Decimal <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_))

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"settlementDeadline\" `AllocationSpecification <type-splice-api-token-allocationv2-allocationspecification-21899_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_)

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"transferLegSides\" `AllocationSpecification <type-splice-api-token-allocationv2-allocationspecification-21899_>`_ \[`TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_\]

.. _type-splice-api-token-allocationv2-allocationview-63318:

**data** `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_

  View of a contract representing a ready\-to\-settle allocation\.

  .. _constr-splice-api-token-allocationv2-allocationview-85195:

  `AllocationView <constr-splice-api-token-allocationv2-allocationview-85195_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - originalAllocationCid
         - `Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_)
         - The contract id of the original allocation contract, which is ``None`` for the original allocation contract itself\.  This SHOULD be used by wallets to correlate the same allocation across updates to its state\. It should not be used to correlate different allocations for the same settlement\. That can be done using the ``allocation.settlement`` field\.
       * - settlement
         - `SettlementInfo <type-splice-api-token-allocationv2-settlementinfo-44234_>`_
         - The settlement for which this allocation is made\.
       * - allocation
         - `AllocationSpecification <type-splice-api-token-allocationv2-allocationspecification-21899_>`_
         - The specification of the allocation\.
       * - holdingCids
         - \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]
         - The holdings that are backing this allocation\.  Provided so that wallets can correlate the allocation with the holdings\.  MAY be empty for registries that do not represent their holdings on\-ledger\.
       * - createdAt
         - `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_
         - The time when the allocation was originally created\.
       * - numIterations
         - `Int <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-int-37261>`_
         - The number of settlement iterations that have been executed for this allocation so far\.
       * - expiresAt
         - `Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_
         - The time at which the allocation expires if inactive\.  Registries MAY expire the allocation and return the locked funds to the authorizer after this time\. Thereby recovering storage resources and protecting themselves from denial\-of\-service attacks\.  Registries SHOULD avoid unnecessary refreshes by  * making the expiry time as close to the settlement deadline as possible * bumping expiry on every settlement iteration\.
       * - availableActions
         - `Map <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-map-90052>`_ `AllocationAction <type-splice-api-token-allocationv2-allocationaction-86361_>`_ \[\[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]\]
         - What actions are available to which groups of parties\. The list of lists is interpreted as a set of sets and represents a disjunction of conjunctions of parties, i\.e\., each inner list represents a group of parties that can act jointly to execute the action\.  This field can be used to inform wallet users whether they can take an action or not; and which other parties they might be waiting on to take their action\.  Supports multiple parties for actions that require joint authorization\. Executing them will require appropriate, registry\-specific delegation contracts to be in place\.
       * - meta
         - Metadata
         - Additional metadata specific to the allocation, used for extensibility\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_

  **instance** `HasFromAnyView <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Internal-Interface-AnyView.html#class-da-internal-interface-anyview-hasfromanyview-30108>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_

  **instance** `HasInterfaceView <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-interface-hasinterfaceview-4492>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"allocation\" `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_ `AllocationSpecification <type-splice-api-token-allocationv2-allocationspecification-21899_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"availableActions\" `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_ (`Map <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-map-90052>`_ `AllocationAction <type-splice-api-token-allocationv2-allocationaction-86361_>`_ \[\[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]\])

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"createdAt\" `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"expiresAt\" `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_)

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"holdingCids\" `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_ \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_ Metadata

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"numIterations\" `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_ `Int <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-int-37261>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"originalAllocationCid\" `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_))

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"settlement\" `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_ `SettlementInfo <type-splice-api-token-allocationv2-settlementinfo-44234_>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"allocation\" `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_ `AllocationSpecification <type-splice-api-token-allocationv2-allocationspecification-21899_>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"availableActions\" `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_ (`Map <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-map-90052>`_ `AllocationAction <type-splice-api-token-allocationv2-allocationaction-86361_>`_ \[\[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]\])

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"createdAt\" `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"expiresAt\" `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_)

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"holdingCids\" `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_ \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_ Metadata

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"numIterations\" `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_ `Int <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-int-37261>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"originalAllocationCid\" `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_))

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"settlement\" `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_ `SettlementInfo <type-splice-api-token-allocationv2-settlementinfo-44234_>`_

.. _type-splice-api-token-allocationv2-finalizedallocation-95818:

**data** `FinalizedAllocation <type-splice-api-token-allocationv2-finalizedallocation-95818_>`_

  An allocation finalized by the executors for settlement\.

  .. _constr-splice-api-token-allocationv2-finalizedallocation-93793:

  `FinalizedAllocation <constr-splice-api-token-allocationv2-finalizedallocation-93793_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - allocationCid
         - `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_
         - The allocation to settle\.
       * - extraTransferLegSides
         - \[`TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_\]
         - The extra transfer leg sides to authorize as part of this allocation in this settlement iteration\.  They MUST be empty unless iterated settlement was enabled by the allocation's authorizer\.
       * - nextIterationFunding
         - `Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`TextMap <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-textmap-11691>`_ `Decimal <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_)
         - The funding to reserve for the next settlement iteration\.  This MUST NOT be set unless iterated settlement was enabled by the allocation's authorizer\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `FinalizedAllocation <type-splice-api-token-allocationv2-finalizedallocation-95818_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `FinalizedAllocation <type-splice-api-token-allocationv2-finalizedallocation-95818_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"allocationCid\" `FinalizedAllocation <type-splice-api-token-allocationv2-finalizedallocation-95818_>`_ (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_)

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"allocations\" `SettlementFactory_SettleBatch <type-splice-api-token-allocationv2-settlementfactorysettlebatch-65399_>`_ \[`FinalizedAllocation <type-splice-api-token-allocationv2-finalizedallocation-95818_>`_\]

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"extraTransferLegSides\" `FinalizedAllocation <type-splice-api-token-allocationv2-finalizedallocation-95818_>`_ \[`TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_\]

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"nextIterationFunding\" `FinalizedAllocation <type-splice-api-token-allocationv2-finalizedallocation-95818_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`TextMap <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-textmap-11691>`_ `Decimal <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_))

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"allocationCid\" `FinalizedAllocation <type-splice-api-token-allocationv2-finalizedallocation-95818_>`_ (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_)

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"allocations\" `SettlementFactory_SettleBatch <type-splice-api-token-allocationv2-settlementfactorysettlebatch-65399_>`_ \[`FinalizedAllocation <type-splice-api-token-allocationv2-finalizedallocation-95818_>`_\]

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"extraTransferLegSides\" `FinalizedAllocation <type-splice-api-token-allocationv2-finalizedallocation-95818_>`_ \[`TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_\]

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"nextIterationFunding\" `FinalizedAllocation <type-splice-api-token-allocationv2-finalizedallocation-95818_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`TextMap <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-textmap-11691>`_ `Decimal <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_))

.. _type-splice-api-token-allocationv2-settlementfactoryview-93858:

**data** `SettlementFactoryView <type-splice-api-token-allocationv2-settlementfactoryview-93858_>`_

  View for ``SettlementFactory``\.

  .. _constr-splice-api-token-allocationv2-settlementfactoryview-47925:

  `SettlementFactoryView <constr-splice-api-token-allocationv2-settlementfactoryview-47925_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - admin
         - `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The party representing the registry app that administers the instruments for which this settlement factory can be used\.
       * - meta
         - Metadata
         - Additional metadata specific to the settlement factory, used for extensibility\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `SettlementFactoryView <type-splice-api-token-allocationv2-settlementfactoryview-93858_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `SettlementFactoryView <type-splice-api-token-allocationv2-settlementfactoryview-93858_>`_

  **instance** HasMethod `SettlementFactory <type-splice-api-token-allocationv2-settlementfactory-64321_>`_ \"settlementFactory\_publicFetchImpl\" (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `SettlementFactory <type-splice-api-token-allocationv2-settlementfactory-64321_>`_ \-\> `SettlementFactory_PublicFetch <type-splice-api-token-allocationv2-settlementfactorypublicfetch-69193_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `SettlementFactoryView <type-splice-api-token-allocationv2-settlementfactoryview-93858_>`_)

  **instance** `HasFromAnyView <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Internal-Interface-AnyView.html#class-da-internal-interface-anyview-hasfromanyview-30108>`_ `SettlementFactory <type-splice-api-token-allocationv2-settlementfactory-64321_>`_ `SettlementFactoryView <type-splice-api-token-allocationv2-settlementfactoryview-93858_>`_

  **instance** `HasInterfaceView <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-interface-hasinterfaceview-4492>`_ `SettlementFactory <type-splice-api-token-allocationv2-settlementfactory-64321_>`_ `SettlementFactoryView <type-splice-api-token-allocationv2-settlementfactoryview-93858_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"admin\" `SettlementFactoryView <type-splice-api-token-allocationv2-settlementfactoryview-93858_>`_ `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `SettlementFactoryView <type-splice-api-token-allocationv2-settlementfactoryview-93858_>`_ Metadata

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"admin\" `SettlementFactoryView <type-splice-api-token-allocationv2-settlementfactoryview-93858_>`_ `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `SettlementFactoryView <type-splice-api-token-allocationv2-settlementfactoryview-93858_>`_ Metadata

  **instance** `HasExercise <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `SettlementFactory <type-splice-api-token-allocationv2-settlementfactory-64321_>`_ `SettlementFactory_PublicFetch <type-splice-api-token-allocationv2-settlementfactorypublicfetch-69193_>`_ `SettlementFactoryView <type-splice-api-token-allocationv2-settlementfactoryview-93858_>`_

  **instance** `HasExerciseGuarded <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `SettlementFactory <type-splice-api-token-allocationv2-settlementfactory-64321_>`_ `SettlementFactory_PublicFetch <type-splice-api-token-allocationv2-settlementfactorypublicfetch-69193_>`_ `SettlementFactoryView <type-splice-api-token-allocationv2-settlementfactoryview-93858_>`_

  **instance** `HasFromAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `SettlementFactory <type-splice-api-token-allocationv2-settlementfactory-64321_>`_ `SettlementFactory_PublicFetch <type-splice-api-token-allocationv2-settlementfactorypublicfetch-69193_>`_ `SettlementFactoryView <type-splice-api-token-allocationv2-settlementfactoryview-93858_>`_

  **instance** `HasToAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `SettlementFactory <type-splice-api-token-allocationv2-settlementfactory-64321_>`_ `SettlementFactory_PublicFetch <type-splice-api-token-allocationv2-settlementfactorypublicfetch-69193_>`_ `SettlementFactoryView <type-splice-api-token-allocationv2-settlementfactoryview-93858_>`_

.. _type-splice-api-token-allocationv2-settlementfactorysettlebatchresult-32414:

**data** `SettlementFactory_SettleBatchResult <type-splice-api-token-allocationv2-settlementfactorysettlebatchresult-32414_>`_

  Result of settling a batch of allocations\.

  .. _constr-splice-api-token-allocationv2-settlementfactorysettlebatchresult-16337:

  `SettlementFactory_SettleBatchResult <constr-splice-api-token-allocationv2-settlementfactorysettlebatchresult-16337_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - allocationSettleResults
         - \[`AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_\]
         - The result of settling each allocation in the batch\. In the same order as the ``allocations`` in the choice arguments\.
       * - meta
         - Metadata
         - Additional metadata specific to the batch settlement, used for extensibility\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `SettlementFactory_SettleBatchResult <type-splice-api-token-allocationv2-settlementfactorysettlebatchresult-32414_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `SettlementFactory_SettleBatchResult <type-splice-api-token-allocationv2-settlementfactorysettlebatchresult-32414_>`_

  **instance** HasMethod `SettlementFactory <type-splice-api-token-allocationv2-settlementfactory-64321_>`_ \"settlementFactory\_settleBatchImpl\" (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `SettlementFactory <type-splice-api-token-allocationv2-settlementfactory-64321_>`_ \-\> `SettlementFactory_SettleBatch <type-splice-api-token-allocationv2-settlementfactorysettlebatch-65399_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `SettlementFactory_SettleBatchResult <type-splice-api-token-allocationv2-settlementfactorysettlebatchresult-32414_>`_)

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"allocationSettleResults\" `SettlementFactory_SettleBatchResult <type-splice-api-token-allocationv2-settlementfactorysettlebatchresult-32414_>`_ \[`AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_\]

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `SettlementFactory_SettleBatchResult <type-splice-api-token-allocationv2-settlementfactorysettlebatchresult-32414_>`_ Metadata

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"allocationSettleResults\" `SettlementFactory_SettleBatchResult <type-splice-api-token-allocationv2-settlementfactorysettlebatchresult-32414_>`_ \[`AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_\]

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `SettlementFactory_SettleBatchResult <type-splice-api-token-allocationv2-settlementfactorysettlebatchresult-32414_>`_ Metadata

  **instance** `HasExercise <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `SettlementFactory <type-splice-api-token-allocationv2-settlementfactory-64321_>`_ `SettlementFactory_SettleBatch <type-splice-api-token-allocationv2-settlementfactorysettlebatch-65399_>`_ `SettlementFactory_SettleBatchResult <type-splice-api-token-allocationv2-settlementfactorysettlebatchresult-32414_>`_

  **instance** `HasExerciseGuarded <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `SettlementFactory <type-splice-api-token-allocationv2-settlementfactory-64321_>`_ `SettlementFactory_SettleBatch <type-splice-api-token-allocationv2-settlementfactorysettlebatch-65399_>`_ `SettlementFactory_SettleBatchResult <type-splice-api-token-allocationv2-settlementfactorysettlebatchresult-32414_>`_

  **instance** `HasFromAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `SettlementFactory <type-splice-api-token-allocationv2-settlementfactory-64321_>`_ `SettlementFactory_SettleBatch <type-splice-api-token-allocationv2-settlementfactorysettlebatch-65399_>`_ `SettlementFactory_SettleBatchResult <type-splice-api-token-allocationv2-settlementfactorysettlebatchresult-32414_>`_

  **instance** `HasToAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `SettlementFactory <type-splice-api-token-allocationv2-settlementfactory-64321_>`_ `SettlementFactory_SettleBatch <type-splice-api-token-allocationv2-settlementfactorysettlebatch-65399_>`_ `SettlementFactory_SettleBatchResult <type-splice-api-token-allocationv2-settlementfactorysettlebatchresult-32414_>`_

.. _type-splice-api-token-allocationv2-settlementinfo-44234:

**data** `SettlementInfo <type-splice-api-token-allocationv2-settlementinfo-44234_>`_

  An unambiguous reference to a settlement, which can be used by wallets to
  correlate allocations to the same settlement\.

  The ``executors`` MUST ensure that the triple of ``(id, cid, meta)`` is unique
  across settlements\.

  .. _constr-splice-api-token-allocationv2-settlementinfo-44883:

  `SettlementInfo <constr-splice-api-token-allocationv2-settlementinfo-44883_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - executors
         - \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]
         - The parties that are responsible for executing the settlement and guarantee atomic settlement across asset admins\.
       * - id
         - `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_
         - Textual identifier of the settlement\.
       * - cid
         - `Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ AnyContractId
         - Optional contract\-id based identifier of the settlement\.  This field is there for technical reasons, as contract\-ids cannot be converted to text from within Daml, which is due to their full textual representation being only known after transactions have been prepared\.
       * - meta
         - Metadata
         - Additional metadata to identify the settlement, used for extensibility\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `SettlementInfo <type-splice-api-token-allocationv2-settlementinfo-44234_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `SettlementInfo <type-splice-api-token-allocationv2-settlementinfo-44234_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"cid\" `SettlementInfo <type-splice-api-token-allocationv2-settlementinfo-44234_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ AnyContractId)

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"executors\" `SettlementInfo <type-splice-api-token-allocationv2-settlementinfo-44234_>`_ \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"id\" `SettlementInfo <type-splice-api-token-allocationv2-settlementinfo-44234_>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `SettlementInfo <type-splice-api-token-allocationv2-settlementinfo-44234_>`_ Metadata

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"settlement\" `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_ `SettlementInfo <type-splice-api-token-allocationv2-settlementinfo-44234_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"settlement\" `SettlementFactory_SettleBatch <type-splice-api-token-allocationv2-settlementfactorysettlebatch-65399_>`_ `SettlementInfo <type-splice-api-token-allocationv2-settlementinfo-44234_>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"cid\" `SettlementInfo <type-splice-api-token-allocationv2-settlementinfo-44234_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ AnyContractId)

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"executors\" `SettlementInfo <type-splice-api-token-allocationv2-settlementinfo-44234_>`_ \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"id\" `SettlementInfo <type-splice-api-token-allocationv2-settlementinfo-44234_>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `SettlementInfo <type-splice-api-token-allocationv2-settlementinfo-44234_>`_ Metadata

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"settlement\" `AllocationView <type-splice-api-token-allocationv2-allocationview-63318_>`_ `SettlementInfo <type-splice-api-token-allocationv2-settlementinfo-44234_>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"settlement\" `SettlementFactory_SettleBatch <type-splice-api-token-allocationv2-settlementfactorysettlebatch-65399_>`_ `SettlementInfo <type-splice-api-token-allocationv2-settlementinfo-44234_>`_

.. _type-splice-api-token-allocationv2-transferleg-34001:

**data** `TransferLeg <type-splice-api-token-allocationv2-transferleg-34001_>`_

  A specification of a transfer of holdings between two parties for the
  purpose of a settlement, which often requires the atomic execution of multiple legs\.

  .. _constr-splice-api-token-allocationv2-transferleg-77514:

  `TransferLeg <constr-splice-api-token-allocationv2-transferleg-77514_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - transferLegId
         - `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_
         - An identifier for the transfer leg\.
       * - sender
         - Account
         - The sender of the transfer\.
       * - receiver
         - Account
         - The receiver of the transfer\.
       * - amount
         - `Decimal <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_
         - The amount to transfer\.
       * - instrumentId
         - `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_
         - The instrument identifier used by the instrument admin\.
       * - meta
         - Metadata
         - Additional metadata about the transfer leg, used for extensibility\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `TransferLeg <type-splice-api-token-allocationv2-transferleg-34001_>`_

  **instance** `Ord <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-ord-6395>`_ `TransferLeg <type-splice-api-token-allocationv2-transferleg-34001_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `TransferLeg <type-splice-api-token-allocationv2-transferleg-34001_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"amount\" `TransferLeg <type-splice-api-token-allocationv2-transferleg-34001_>`_ `Decimal <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"instrumentId\" `TransferLeg <type-splice-api-token-allocationv2-transferleg-34001_>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `TransferLeg <type-splice-api-token-allocationv2-transferleg-34001_>`_ Metadata

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"receiver\" `TransferLeg <type-splice-api-token-allocationv2-transferleg-34001_>`_ Account

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"sender\" `TransferLeg <type-splice-api-token-allocationv2-transferleg-34001_>`_ Account

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"transferLegId\" `TransferLeg <type-splice-api-token-allocationv2-transferleg-34001_>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"transferLegs\" `SettlementFactory_SettleBatch <type-splice-api-token-allocationv2-settlementfactorysettlebatch-65399_>`_ \[`TransferLeg <type-splice-api-token-allocationv2-transferleg-34001_>`_\]

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"amount\" `TransferLeg <type-splice-api-token-allocationv2-transferleg-34001_>`_ `Decimal <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"instrumentId\" `TransferLeg <type-splice-api-token-allocationv2-transferleg-34001_>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `TransferLeg <type-splice-api-token-allocationv2-transferleg-34001_>`_ Metadata

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"receiver\" `TransferLeg <type-splice-api-token-allocationv2-transferleg-34001_>`_ Account

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"sender\" `TransferLeg <type-splice-api-token-allocationv2-transferleg-34001_>`_ Account

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"transferLegId\" `TransferLeg <type-splice-api-token-allocationv2-transferleg-34001_>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"transferLegs\" `SettlementFactory_SettleBatch <type-splice-api-token-allocationv2-settlementfactorysettlebatch-65399_>`_ \[`TransferLeg <type-splice-api-token-allocationv2-transferleg-34001_>`_\]

.. _type-splice-api-token-allocationv2-transferlegside-31020:

**data** `TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_

  A side of a transfer leg, which is what allocations authorize\.

  .. _constr-splice-api-token-allocationv2-transferlegside-92643:

  `TransferLegSide <constr-splice-api-token-allocationv2-transferlegside-92643_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - transferLegId
         - `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_
         - An identifier for the transfer leg\.
       * - side
         - `TransferSide <type-splice-api-token-allocationv2-transferside-67513_>`_
         - The side of the transfer that this leg refers to\.
       * - otherside
         - Account
         - The account on the other side of the transfer leg; i\.e\., the sender in case of ``side == ReceiverSide``, and the receiver in case of ``side == SenderSide``\.
       * - amount
         - `Decimal <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_
         - The amount being transferred\.
       * - instrumentId
         - `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_
         - The instrument identifier used by the instrument admin\.
       * - meta
         - Metadata
         - Additional metadata about the transfer leg, used for extensibility\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_

  **instance** `Ord <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-ord-6395>`_ `TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"amount\" `TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_ `Decimal <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"extraTransferLegSides\" `Allocation_Settle <type-splice-api-token-allocationv2-allocationsettle-31244_>`_ \[`TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_\]

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"extraTransferLegSides\" `FinalizedAllocation <type-splice-api-token-allocationv2-finalizedallocation-95818_>`_ \[`TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_\]

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"instrumentId\" `TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_ Metadata

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"otherside\" `TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_ Account

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"side\" `TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_ `TransferSide <type-splice-api-token-allocationv2-transferside-67513_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"transferLegId\" `TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"transferLegSides\" `AllocationSpecification <type-splice-api-token-allocationv2-allocationspecification-21899_>`_ \[`TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_\]

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"amount\" `TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_ `Decimal <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"extraTransferLegSides\" `Allocation_Settle <type-splice-api-token-allocationv2-allocationsettle-31244_>`_ \[`TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_\]

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"extraTransferLegSides\" `FinalizedAllocation <type-splice-api-token-allocationv2-finalizedallocation-95818_>`_ \[`TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_\]

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"instrumentId\" `TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_ Metadata

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"otherside\" `TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_ Account

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"side\" `TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_ `TransferSide <type-splice-api-token-allocationv2-transferside-67513_>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"transferLegId\" `TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"transferLegSides\" `AllocationSpecification <type-splice-api-token-allocationv2-allocationspecification-21899_>`_ \[`TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_\]

.. _type-splice-api-token-allocationv2-transferside-67513:

**data** `TransferSide <type-splice-api-token-allocationv2-transferside-67513_>`_

  A side of a transfer\.

  .. _constr-splice-api-token-allocationv2-senderside-82048:

  `SenderSide <constr-splice-api-token-allocationv2-senderside-82048_>`_

    The outbound side of a transfer, i\.e\., the sending of assets\.

  .. _constr-splice-api-token-allocationv2-receiverside-26774:

  `ReceiverSide <constr-splice-api-token-allocationv2-receiverside-26774_>`_

    The inbound direction, i\.e\., the receipt of assets\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `TransferSide <type-splice-api-token-allocationv2-transferside-67513_>`_

  **instance** `Ord <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-ord-6395>`_ `TransferSide <type-splice-api-token-allocationv2-transferside-67513_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `TransferSide <type-splice-api-token-allocationv2-transferside-67513_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"side\" `TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_ `TransferSide <type-splice-api-token-allocationv2-transferside-67513_>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"side\" `TransferLegSide <type-splice-api-token-allocationv2-transferlegside-31020_>`_ `TransferSide <type-splice-api-token-allocationv2-transferside-67513_>`_

Functions
---------

.. _function-splice-api-token-allocationv2-allocationsettleimpl-86098:

`allocation_settleImpl <function-splice-api-token-allocationv2-allocationsettleimpl-86098_>`_
  \: `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ \-\> `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ \-\> `Allocation_Settle <type-splice-api-token-allocationv2-allocationsettle-31244_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_

.. _function-splice-api-token-allocationv2-allocationcancelimpl-4149:

`allocation_cancelImpl <function-splice-api-token-allocationv2-allocationcancelimpl-4149_>`_
  \: `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ \-\> `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ \-\> `Allocation_Cancel <type-splice-api-token-allocationv2-allocationcancel-98587_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_

.. _function-splice-api-token-allocationv2-allocationwithdrawimpl-22975:

`allocation_withdrawImpl <function-splice-api-token-allocationv2-allocationwithdrawimpl-22975_>`_
  \: `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ \-\> `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ \-\> `Allocation_Withdraw <type-splice-api-token-allocationv2-allocationwithdraw-18681_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationResult <type-splice-api-token-allocationv2-allocationresult-19648_>`_

.. _function-splice-api-token-allocationv2-allocationsettleextraobservers-63249:

`allocation_settleExtraObservers <function-splice-api-token-allocationv2-allocationsettleextraobservers-63249_>`_
  \: `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ \-\> `Allocation_Settle <type-splice-api-token-allocationv2-allocationsettle-31244_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

.. _function-splice-api-token-allocationv2-allocationcancelextraobservers-5502:

`allocation_cancelExtraObservers <function-splice-api-token-allocationv2-allocationcancelextraobservers-5502_>`_
  \: `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ \-\> `Allocation_Cancel <type-splice-api-token-allocationv2-allocationcancel-98587_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

.. _function-splice-api-token-allocationv2-allocationwithdrawextraobservers-84364:

`allocation_withdrawExtraObservers <function-splice-api-token-allocationv2-allocationwithdrawextraobservers-84364_>`_
  \: `Allocation <type-splice-api-token-allocationv2-allocation-27217_>`_ \-\> `Allocation_Withdraw <type-splice-api-token-allocationv2-allocationwithdraw-18681_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

.. _function-splice-api-token-allocationv2-settlementfactorypublicfetchimpl-1547:

`settlementFactory_publicFetchImpl <function-splice-api-token-allocationv2-settlementfactorypublicfetchimpl-1547_>`_
  \: `SettlementFactory <type-splice-api-token-allocationv2-settlementfactory-64321_>`_ \-\> `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `SettlementFactory <type-splice-api-token-allocationv2-settlementfactory-64321_>`_ \-\> `SettlementFactory_PublicFetch <type-splice-api-token-allocationv2-settlementfactorypublicfetch-69193_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `SettlementFactoryView <type-splice-api-token-allocationv2-settlementfactoryview-93858_>`_

.. _function-splice-api-token-allocationv2-settlementfactorysettlebatchimpl-63577:

`settlementFactory_settleBatchImpl <function-splice-api-token-allocationv2-settlementfactorysettlebatchimpl-63577_>`_
  \: `SettlementFactory <type-splice-api-token-allocationv2-settlementfactory-64321_>`_ \-\> `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `SettlementFactory <type-splice-api-token-allocationv2-settlementfactory-64321_>`_ \-\> `SettlementFactory_SettleBatch <type-splice-api-token-allocationv2-settlementfactorysettlebatch-65399_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `SettlementFactory_SettleBatchResult <type-splice-api-token-allocationv2-settlementfactorysettlebatchresult-32414_>`_

.. _function-splice-api-token-allocationv2-settlementfactorysettlebatchextraobservers-38682:

`settlementFactory_settleBatchExtraObservers <function-splice-api-token-allocationv2-settlementfactorysettlebatchextraobservers-38682_>`_
  \: `SettlementFactory <type-splice-api-token-allocationv2-settlementfactory-64321_>`_ \-\> `SettlementFactory_SettleBatch <type-splice-api-token-allocationv2-settlementfactorysettlebatch-65399_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]
