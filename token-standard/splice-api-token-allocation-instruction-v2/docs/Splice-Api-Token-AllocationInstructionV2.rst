..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _module-splice-api-token-allocationinstructionv2-9958:

Splice.Api.Token.AllocationInstructionV2
========================================

V2 interfaces to enable wallets to instruct the registry to create allocations\.

Interfaces
----------

.. _type-splice-api-token-allocationinstructionv2-allocationfactory-48435:

**interface** `AllocationFactory <type-splice-api-token-allocationinstructionv2-allocationfactory-48435_>`_

  Contracts implementing ``AllocationFactory`` are retrieved from the registry app and are
  used by the wallet to create allocation instructions (or allocations directly)\.

  **viewtype** `AllocationFactoryView <type-splice-api-token-allocationinstructionv2-allocationfactoryview-38300_>`_

  + .. _type-splice-api-token-allocationinstructionv2-allocationfactoryallocate-94480:

    **Choice** `AllocationFactory_Allocate <type-splice-api-token-allocationinstructionv2-allocationfactoryallocate-94480_>`_

    Request the creation of an allocation for a particular settlement\.

    It depends on the registry whether this results in the allocation being
    created directly or in an allocation instruction being created instead\.

    Controller\: actors

    Returns\: `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - settlement
         - SettlementInfo
         - The settlement for which this allocation is made\.
       * - allocation
         - AllocationSpecification
         - The allocation which should be created\.  Implementations MUST validate that the allocation's admin party matches the admin of the factory\.
       * - requestedAt
         - `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_
         - The time at which the allocation was requested\.
       * - inputHoldingCids
         - \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]
         - The holdings to use to fund the allocation\.  Implementations MUST return change and all input holdings not used for funding in the ``authorizerChangeCids`` field of the ``AllocationInstructionResult``\.
       * - extraArgs
         - ExtraArgs
         - Additional choice arguments\.
       * - actors
         - \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]
         - Set of parties executing the allocation\.  Implementations MUST check their concrete values to avoid unauthorized allocation creation\.  Implementations SHOULD allow the initiation of the allocation if the ``actors`` is equal to the ``authorizer`` of the allocation\. Likewise, for accounts where the account provider has the right to allocate funds, the registry SHOULD allow the initiation of the allocation if the ``actors`` are equal to the account provider\.

  + .. _type-splice-api-token-allocationinstructionv2-allocationfactorypublicfetch-46919:

    **Choice** `AllocationFactory_PublicFetch <type-splice-api-token-allocationinstructionv2-allocationfactorypublicfetch-46919_>`_

    Controller\: actors

    Returns\: `AllocationFactoryView <type-splice-api-token-allocationinstructionv2-allocationfactoryview-38300_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - actors
         - \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]
         - Set of parties executing the fetch\.

  + **Choice** Archive

    Controller\: Signatories of implementing template

    Returns\: ()

    (no fields)

  + **Method allocationFactory\_allocateExtraObservers \:** `AllocationFactory_Allocate <type-splice-api-token-allocationinstructionv2-allocationfactoryallocate-94480_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

  + **Method allocationFactory\_allocateImpl \:** `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv2-allocationfactory-48435_>`_ \-\> `AllocationFactory_Allocate <type-splice-api-token-allocationinstructionv2-allocationfactoryallocate-94480_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_

  + **Method allocationFactory\_publicFetchImpl \:** `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv2-allocationfactory-48435_>`_ \-\> `AllocationFactory_PublicFetch <type-splice-api-token-allocationinstructionv2-allocationfactorypublicfetch-46919_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationFactoryView <type-splice-api-token-allocationinstructionv2-allocationfactoryview-38300_>`_

.. _type-splice-api-token-allocationinstructionv2-allocationinstruction-46357:

**interface** `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_

  An interface for tracking the status of an allocation instruction,
  i\.e\., a request to a registry app to create an allocation\.

  Registries MAY evolve the allocation instruction in multiple steps\. They SHOULD
  do so using only the choices on this interface, so that wallets can reliably
  parse the transaction history and determine whether the creation of the allocation ultimately
  succeeded or failed\.

  **viewtype** `AllocationInstructionView <type-splice-api-token-allocationinstructionv2-allocationinstructionview-35894_>`_

  + .. _type-splice-api-token-allocationinstructionv2-allocationinstructionaccept-19413:

    **Choice** `AllocationInstruction_Accept <type-splice-api-token-allocationinstructionv2-allocationinstructionaccept-19413_>`_

    Accept the allocation instruction as someone that needs to authorize it\.

    Controller\: actors

    Returns\: `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - actors
         - \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]
         - Set of parties executing the acceptance\.  Registries are free to decide on the required authorization for acceptance\. They MUST however report the parties that can accept unilaterally via the ``AllocationInstructionView.availableActions`` field, so that wallets can show the accept option to the user\.
       * - extraArgs
         - ExtraArgs
         - Additional context required in order to exercise the choice\.

  + .. _type-splice-api-token-allocationinstructionv2-allocationinstructionwithdraw-61449:

    **Choice** `AllocationInstruction_Withdraw <type-splice-api-token-allocationinstructionv2-allocationinstructionwithdraw-61449_>`_

    Withdraw the allocation instruction\.

    Controller\: actors

    Returns\: `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - actors
         - \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]
         - Set of parties executing the withdrawal\.  Implementations MUST check these parties to avoid unauthorized withdrawal\.  Registries are free to decide on the required authorization for withdrawal\. They MUST however report the parties that can withdraw unilaterally via the ``AllocationInstructionView.availableActions`` field, so that wallets can show the withdrawal option to the user\.
       * - extraArgs
         - ExtraArgs
         - Additional context required in order to exercise the choice\.

  + **Choice** Archive

    Controller\: Signatories of implementing template

    Returns\: ()

    (no fields)

  + **Method allocationInstruction\_acceptExtraObservers \:** `AllocationInstruction_Accept <type-splice-api-token-allocationinstructionv2-allocationinstructionaccept-19413_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

  + **Method allocationInstruction\_acceptImpl \:** `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_ \-\> `AllocationInstruction_Accept <type-splice-api-token-allocationinstructionv2-allocationinstructionaccept-19413_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_

  + **Method allocationInstruction\_withdrawExtraObservers \:** `AllocationInstruction_Withdraw <type-splice-api-token-allocationinstructionv2-allocationinstructionwithdraw-61449_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

  + **Method allocationInstruction\_withdrawImpl \:** `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_ \-\> `AllocationInstruction_Withdraw <type-splice-api-token-allocationinstructionv2-allocationinstructionwithdraw-61449_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_

Data Types
----------

.. _type-splice-api-token-allocationinstructionv2-allocationfactoryview-38300:

**data** `AllocationFactoryView <type-splice-api-token-allocationinstructionv2-allocationfactoryview-38300_>`_

  View for ``AllocationFactory``\.

  .. _constr-splice-api-token-allocationinstructionv2-allocationfactoryview-7777:

  `AllocationFactoryView <constr-splice-api-token-allocationinstructionv2-allocationfactoryview-7777_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - admin
         - `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The party representing the registry app that administers the instruments for which this allocation factory can be used\.
       * - meta
         - Metadata
         - Additional metadata specific to the allocation factory, used for extensibility\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `AllocationFactoryView <type-splice-api-token-allocationinstructionv2-allocationfactoryview-38300_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `AllocationFactoryView <type-splice-api-token-allocationinstructionv2-allocationfactoryview-38300_>`_

  **instance** HasMethod `AllocationFactory <type-splice-api-token-allocationinstructionv2-allocationfactory-48435_>`_ \"allocationFactory\_publicFetchImpl\" (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv2-allocationfactory-48435_>`_ \-\> `AllocationFactory_PublicFetch <type-splice-api-token-allocationinstructionv2-allocationfactorypublicfetch-46919_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationFactoryView <type-splice-api-token-allocationinstructionv2-allocationfactoryview-38300_>`_)

  **instance** `HasFromAnyView <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Internal-Interface-AnyView.html#class-da-internal-interface-anyview-hasfromanyview-30108>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv2-allocationfactory-48435_>`_ `AllocationFactoryView <type-splice-api-token-allocationinstructionv2-allocationfactoryview-38300_>`_

  **instance** `HasInterfaceView <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-interface-hasinterfaceview-4492>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv2-allocationfactory-48435_>`_ `AllocationFactoryView <type-splice-api-token-allocationinstructionv2-allocationfactoryview-38300_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"admin\" `AllocationFactoryView <type-splice-api-token-allocationinstructionv2-allocationfactoryview-38300_>`_ `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `AllocationFactoryView <type-splice-api-token-allocationinstructionv2-allocationfactoryview-38300_>`_ Metadata

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"admin\" `AllocationFactoryView <type-splice-api-token-allocationinstructionv2-allocationfactoryview-38300_>`_ `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `AllocationFactoryView <type-splice-api-token-allocationinstructionv2-allocationfactoryview-38300_>`_ Metadata

  **instance** `HasExercise <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv2-allocationfactory-48435_>`_ `AllocationFactory_PublicFetch <type-splice-api-token-allocationinstructionv2-allocationfactorypublicfetch-46919_>`_ `AllocationFactoryView <type-splice-api-token-allocationinstructionv2-allocationfactoryview-38300_>`_

  **instance** `HasExerciseGuarded <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv2-allocationfactory-48435_>`_ `AllocationFactory_PublicFetch <type-splice-api-token-allocationinstructionv2-allocationfactorypublicfetch-46919_>`_ `AllocationFactoryView <type-splice-api-token-allocationinstructionv2-allocationfactoryview-38300_>`_

  **instance** `HasFromAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv2-allocationfactory-48435_>`_ `AllocationFactory_PublicFetch <type-splice-api-token-allocationinstructionv2-allocationfactorypublicfetch-46919_>`_ `AllocationFactoryView <type-splice-api-token-allocationinstructionv2-allocationfactoryview-38300_>`_

  **instance** `HasToAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv2-allocationfactory-48435_>`_ `AllocationFactory_PublicFetch <type-splice-api-token-allocationinstructionv2-allocationfactorypublicfetch-46919_>`_ `AllocationFactoryView <type-splice-api-token-allocationinstructionv2-allocationfactoryview-38300_>`_

.. _type-splice-api-token-allocationinstructionv2-allocationinstructionaction-91665:

**data** `AllocationInstructionAction <type-splice-api-token-allocationinstructionv2-allocationinstructionaction-91665_>`_

  Actions to advance the state of an allocation instruction\.

  .. _constr-splice-api-token-allocationinstructionv2-aiawithdraw-46739:

  `AIA_Withdraw <constr-splice-api-token-allocationinstructionv2-aiawithdraw-46739_>`_


  .. _constr-splice-api-token-allocationinstructionv2-aiaaccept-52851:

  `AIA_Accept <constr-splice-api-token-allocationinstructionv2-aiaaccept-52851_>`_


  .. _constr-splice-api-token-allocationinstructionv2-aiacustom-67386:

  `AIA_Custom <constr-splice-api-token-allocationinstructionv2-aiacustom-67386_>`_

    Used to represent registry\-specific actions that need to happen
    for the allocation to be created\.

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - id
         - `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_
         - Identifier of the action\. Namespaced analogously to metadata keys\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `AllocationInstructionAction <type-splice-api-token-allocationinstructionv2-allocationinstructionaction-91665_>`_

  **instance** `Ord <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-ord-6395>`_ `AllocationInstructionAction <type-splice-api-token-allocationinstructionv2-allocationinstructionaction-91665_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `AllocationInstructionAction <type-splice-api-token-allocationinstructionv2-allocationinstructionaction-91665_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"availableActions\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv2-allocationinstructionview-35894_>`_ (`Map <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-map-90052>`_ `AllocationInstructionAction <type-splice-api-token-allocationinstructionv2-allocationinstructionaction-91665_>`_ \[\[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]\])

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"id\" `AllocationInstructionAction <type-splice-api-token-allocationinstructionv2-allocationinstructionaction-91665_>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"availableActions\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv2-allocationinstructionview-35894_>`_ (`Map <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-map-90052>`_ `AllocationInstructionAction <type-splice-api-token-allocationinstructionv2-allocationinstructionaction-91665_>`_ \[\[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]\])

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"id\" `AllocationInstructionAction <type-splice-api-token-allocationinstructionv2-allocationinstructionaction-91665_>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

.. _type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488:

**data** `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_

  The result of instructing an allocation or advancing the state of an allocation instruction\.

  .. _constr-splice-api-token-allocationinstructionv2-allocationinstructionresult-61349:

  `AllocationInstructionResult <constr-splice-api-token-allocationinstructionv2-allocationinstructionresult-61349_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - output
         - `AllocationInstructionResult_Output <type-splice-api-token-allocationinstructionv2-allocationinstructionresultoutput-56921_>`_
         - The output of the step\.
       * - authorizerChangeCids
         - `TextMap <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-textmap-11691>`_ \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]
         - Holdings owned by the authorizer that were not used to fund the allocation or that were created to return \"change\", keyed by ``instrumentId.id``\. Can be used by callers to batch creating or updating multiple allocation instructions in a single Daml transaction\.
       * - meta
         - Metadata
         - Additional metadata specific to the allocation instruction, used for extensibility; e\.g\., fees charged\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_

  **instance** HasMethod `AllocationFactory <type-splice-api-token-allocationinstructionv2-allocationfactory-48435_>`_ \"allocationFactory\_allocateImpl\" (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv2-allocationfactory-48435_>`_ \-\> `AllocationFactory_Allocate <type-splice-api-token-allocationinstructionv2-allocationfactoryallocate-94480_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_)

  **instance** HasMethod `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_ \"allocationInstruction\_acceptImpl\" (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_ \-\> `AllocationInstruction_Accept <type-splice-api-token-allocationinstructionv2-allocationinstructionaccept-19413_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_)

  **instance** HasMethod `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_ \"allocationInstruction\_withdrawImpl\" (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_ \-\> `AllocationInstruction_Withdraw <type-splice-api-token-allocationinstructionv2-allocationinstructionwithdraw-61449_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_)

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"authorizerChangeCids\" `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_ (`TextMap <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-textmap-11691>`_ \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\])

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_ Metadata

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"output\" `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_ `AllocationInstructionResult_Output <type-splice-api-token-allocationinstructionv2-allocationinstructionresultoutput-56921_>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"authorizerChangeCids\" `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_ (`TextMap <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-textmap-11691>`_ \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\])

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_ Metadata

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"output\" `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_ `AllocationInstructionResult_Output <type-splice-api-token-allocationinstructionv2-allocationinstructionresultoutput-56921_>`_

  **instance** `HasExercise <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv2-allocationfactory-48435_>`_ `AllocationFactory_Allocate <type-splice-api-token-allocationinstructionv2-allocationfactoryallocate-94480_>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_

  **instance** `HasExercise <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_ `AllocationInstruction_Accept <type-splice-api-token-allocationinstructionv2-allocationinstructionaccept-19413_>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_

  **instance** `HasExercise <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_ `AllocationInstruction_Withdraw <type-splice-api-token-allocationinstructionv2-allocationinstructionwithdraw-61449_>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_

  **instance** `HasExerciseGuarded <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv2-allocationfactory-48435_>`_ `AllocationFactory_Allocate <type-splice-api-token-allocationinstructionv2-allocationfactoryallocate-94480_>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_

  **instance** `HasExerciseGuarded <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_ `AllocationInstruction_Accept <type-splice-api-token-allocationinstructionv2-allocationinstructionaccept-19413_>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_

  **instance** `HasExerciseGuarded <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_ `AllocationInstruction_Withdraw <type-splice-api-token-allocationinstructionv2-allocationinstructionwithdraw-61449_>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_

  **instance** `HasFromAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv2-allocationfactory-48435_>`_ `AllocationFactory_Allocate <type-splice-api-token-allocationinstructionv2-allocationfactoryallocate-94480_>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_

  **instance** `HasFromAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_ `AllocationInstruction_Accept <type-splice-api-token-allocationinstructionv2-allocationinstructionaccept-19413_>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_

  **instance** `HasFromAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_ `AllocationInstruction_Withdraw <type-splice-api-token-allocationinstructionv2-allocationinstructionwithdraw-61449_>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_

  **instance** `HasToAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv2-allocationfactory-48435_>`_ `AllocationFactory_Allocate <type-splice-api-token-allocationinstructionv2-allocationfactoryallocate-94480_>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_

  **instance** `HasToAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_ `AllocationInstruction_Accept <type-splice-api-token-allocationinstructionv2-allocationinstructionaccept-19413_>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_

  **instance** `HasToAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_ `AllocationInstruction_Withdraw <type-splice-api-token-allocationinstructionv2-allocationinstructionwithdraw-61449_>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_

.. _type-splice-api-token-allocationinstructionv2-allocationinstructionresultoutput-56921:

**data** `AllocationInstructionResult_Output <type-splice-api-token-allocationinstructionv2-allocationinstructionresultoutput-56921_>`_

  The output of instructing an allocation or advancing the state of an allocation instruction\.

  .. _constr-splice-api-token-allocationinstructionv2-allocationinstructionresultpending-31785:

  `AllocationInstructionResult_Pending <constr-splice-api-token-allocationinstructionv2-allocationinstructionresultpending-31785_>`_

    Use this result to communicate that the creation of the allocation is pending further steps\.

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - allocationInstructionCid
         - `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_
         - Contract id of the allocation instruction representing the pending state\.

  .. _constr-splice-api-token-allocationinstructionv2-allocationinstructionresultcompleted-32281:

  `AllocationInstructionResult_Completed <constr-splice-api-token-allocationinstructionv2-allocationinstructionresultcompleted-32281_>`_

    Use this result to communicate that the allocation was created\.

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - allocationCid
         - `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Allocation
         - The newly created allocation\.

  .. _constr-splice-api-token-allocationinstructionv2-allocationinstructionresultfailed-58062:

  `AllocationInstructionResult_Failed <constr-splice-api-token-allocationinstructionv2-allocationinstructionresultfailed-58062_>`_

    Use this result to communicate that the creation of the allocation did not succeed and
    all holdings reserved for funding the allocation have been released\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `AllocationInstructionResult_Output <type-splice-api-token-allocationinstructionv2-allocationinstructionresultoutput-56921_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `AllocationInstructionResult_Output <type-splice-api-token-allocationinstructionv2-allocationinstructionresultoutput-56921_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"allocationCid\" `AllocationInstructionResult_Output <type-splice-api-token-allocationinstructionv2-allocationinstructionresultoutput-56921_>`_ (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Allocation)

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"allocationInstructionCid\" `AllocationInstructionResult_Output <type-splice-api-token-allocationinstructionv2-allocationinstructionresultoutput-56921_>`_ (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_)

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"output\" `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_ `AllocationInstructionResult_Output <type-splice-api-token-allocationinstructionv2-allocationinstructionresultoutput-56921_>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"allocationCid\" `AllocationInstructionResult_Output <type-splice-api-token-allocationinstructionv2-allocationinstructionresultoutput-56921_>`_ (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Allocation)

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"allocationInstructionCid\" `AllocationInstructionResult_Output <type-splice-api-token-allocationinstructionv2-allocationinstructionresultoutput-56921_>`_ (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_)

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"output\" `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_ `AllocationInstructionResult_Output <type-splice-api-token-allocationinstructionv2-allocationinstructionresultoutput-56921_>`_

.. _type-splice-api-token-allocationinstructionv2-allocationinstructionview-35894:

**data** `AllocationInstructionView <type-splice-api-token-allocationinstructionv2-allocationinstructionview-35894_>`_

  View for ``AllocationInstruction``\.

  .. _constr-splice-api-token-allocationinstructionv2-allocationinstructionview-86539:

  `AllocationInstructionView <constr-splice-api-token-allocationinstructionv2-allocationinstructionview-86539_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - originalInstructionCid
         - `Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_)
         - The contract id of the first allocation instruction contract of this allocation instruction workflow, if this is not the first step of the workflow\.  This SHOULD be used by wallets to correlate the same allocation instruction across updates to its state\. It should not be used to correlate different allocation instructions for the same settlement\. That can be done using the ``allocation.settlement`` field\.
       * - settlement
         - SettlementInfo
         - The settlement for which this allocation is made\.
       * - allocation
         - AllocationSpecification
         - The allocation that this instruction should create\.
       * - requestedAt
         - `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_
         - The time at which the allocation was originally requested\.
       * - inputHoldingCids
         - \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]
         - The holdings to be used to fund the allocation\.  Empty for allocations that do not require funding\.  MAY be empty for registries that do not represent their holdings on\-ledger\.
       * - expiresAt
         - `Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_
         - The time at which the allocation instruction expires if inactive\.  Registries MAY expire the allocation instruction after this time\. Thereby recovering storage resources and protecting themselves from denial\-of\-service attacks\.  Registries SHOULD avoid unnecessary expiries by  * making the expiry time as close to the allocation's settlement deadline as possible * bumping expiry on every action on the allocation instruction
       * - availableActions
         - `Map <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-map-90052>`_ `AllocationInstructionAction <type-splice-api-token-allocationinstructionv2-allocationinstructionaction-91665_>`_ \[\[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]\]
         - What actions are available to which groups of parties\. The list of lists is interpreted as a set of sets and represents a disjunction of conjunctions of parties, i\.e\., each inner list represents a group of parties that can act jointly to execute the action\.  This field can be used to inform wallet users whether they can take an action or not; and which other parties they might be waiting on to take their action\.  Supports multiple parties for actions that require joint authorization\. Executing them will require appropriate, registry\-specific delegation contracts to be in place\.
       * - meta
         - Metadata
         - Additional metadata specific to the allocation instruction, used for extensibility; e\.g\., more detailed status information\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `AllocationInstructionView <type-splice-api-token-allocationinstructionv2-allocationinstructionview-35894_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `AllocationInstructionView <type-splice-api-token-allocationinstructionv2-allocationinstructionview-35894_>`_

  **instance** `HasFromAnyView <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Internal-Interface-AnyView.html#class-da-internal-interface-anyview-hasfromanyview-30108>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_ `AllocationInstructionView <type-splice-api-token-allocationinstructionv2-allocationinstructionview-35894_>`_

  **instance** `HasInterfaceView <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-interface-hasinterfaceview-4492>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_ `AllocationInstructionView <type-splice-api-token-allocationinstructionv2-allocationinstructionview-35894_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"allocation\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv2-allocationinstructionview-35894_>`_ AllocationSpecification

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"availableActions\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv2-allocationinstructionview-35894_>`_ (`Map <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-map-90052>`_ `AllocationInstructionAction <type-splice-api-token-allocationinstructionv2-allocationinstructionaction-91665_>`_ \[\[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]\])

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"expiresAt\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv2-allocationinstructionview-35894_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_)

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"inputHoldingCids\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv2-allocationinstructionview-35894_>`_ \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv2-allocationinstructionview-35894_>`_ Metadata

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"originalInstructionCid\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv2-allocationinstructionview-35894_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_))

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"requestedAt\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv2-allocationinstructionview-35894_>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"settlement\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv2-allocationinstructionview-35894_>`_ SettlementInfo

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"allocation\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv2-allocationinstructionview-35894_>`_ AllocationSpecification

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"availableActions\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv2-allocationinstructionview-35894_>`_ (`Map <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-map-90052>`_ `AllocationInstructionAction <type-splice-api-token-allocationinstructionv2-allocationinstructionaction-91665_>`_ \[\[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]\])

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"expiresAt\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv2-allocationinstructionview-35894_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_)

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"inputHoldingCids\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv2-allocationinstructionview-35894_>`_ \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv2-allocationinstructionview-35894_>`_ Metadata

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"originalInstructionCid\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv2-allocationinstructionview-35894_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_))

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"requestedAt\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv2-allocationinstructionview-35894_>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"settlement\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv2-allocationinstructionview-35894_>`_ SettlementInfo

Functions
---------

.. _function-splice-api-token-allocationinstructionv2-allocationinstructionwithdrawimpl-81083:

`allocationInstruction_withdrawImpl <function-splice-api-token-allocationinstructionv2-allocationinstructionwithdrawimpl-81083_>`_
  \: `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_ \-\> `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_ \-\> `AllocationInstruction_Withdraw <type-splice-api-token-allocationinstructionv2-allocationinstructionwithdraw-61449_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_

.. _function-splice-api-token-allocationinstructionv2-allocationinstructionwithdrawextraobservers-63168:

`allocationInstruction_withdrawExtraObservers <function-splice-api-token-allocationinstructionv2-allocationinstructionwithdrawextraobservers-63168_>`_
  \: `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_ \-\> `AllocationInstruction_Withdraw <type-splice-api-token-allocationinstructionv2-allocationinstructionwithdraw-61449_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

.. _function-splice-api-token-allocationinstructionv2-allocationinstructionacceptimpl-63643:

`allocationInstruction_acceptImpl <function-splice-api-token-allocationinstructionv2-allocationinstructionacceptimpl-63643_>`_
  \: `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_ \-\> `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_ \-\> `AllocationInstruction_Accept <type-splice-api-token-allocationinstructionv2-allocationinstructionaccept-19413_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_

.. _function-splice-api-token-allocationinstructionv2-allocationinstructionacceptextraobservers-43840:

`allocationInstruction_acceptExtraObservers <function-splice-api-token-allocationinstructionv2-allocationinstructionacceptextraobservers-43840_>`_
  \: `AllocationInstruction <type-splice-api-token-allocationinstructionv2-allocationinstruction-46357_>`_ \-\> `AllocationInstruction_Accept <type-splice-api-token-allocationinstructionv2-allocationinstructionaccept-19413_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

.. _function-splice-api-token-allocationinstructionv2-allocationfactoryallocateextraobservers-14645:

`allocationFactory_allocateExtraObservers <function-splice-api-token-allocationinstructionv2-allocationfactoryallocateextraobservers-14645_>`_
  \: `AllocationFactory <type-splice-api-token-allocationinstructionv2-allocationfactory-48435_>`_ \-\> `AllocationFactory_Allocate <type-splice-api-token-allocationinstructionv2-allocationfactoryallocate-94480_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

.. _function-splice-api-token-allocationinstructionv2-allocationfactoryallocateimpl-12622:

`allocationFactory_allocateImpl <function-splice-api-token-allocationinstructionv2-allocationfactoryallocateimpl-12622_>`_
  \: `AllocationFactory <type-splice-api-token-allocationinstructionv2-allocationfactory-48435_>`_ \-\> `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv2-allocationfactory-48435_>`_ \-\> `AllocationFactory_Allocate <type-splice-api-token-allocationinstructionv2-allocationfactoryallocate-94480_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv2-allocationinstructionresult-99488_>`_

.. _function-splice-api-token-allocationinstructionv2-allocationfactorypublicfetchimpl-80729:

`allocationFactory_publicFetchImpl <function-splice-api-token-allocationinstructionv2-allocationfactorypublicfetchimpl-80729_>`_
  \: `AllocationFactory <type-splice-api-token-allocationinstructionv2-allocationfactory-48435_>`_ \-\> `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv2-allocationfactory-48435_>`_ \-\> `AllocationFactory_PublicFetch <type-splice-api-token-allocationinstructionv2-allocationfactorypublicfetch-46919_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationFactoryView <type-splice-api-token-allocationinstructionv2-allocationfactoryview-38300_>`_
