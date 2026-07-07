..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _module-splice-api-token-transferinstructionv2-57543:

Splice.Api.Token.TransferInstructionV2
======================================

V2 API to instruct transfers of holdings between accounts\.

Interfaces
----------

.. _type-splice-api-token-transferinstructionv2-transferfactory-72147:

**interface** `TransferFactory <type-splice-api-token-transferinstructionv2-transferfactory-72147_>`_

  A factory contract to instruct transfers of holdings between parties\.

  **viewtype** `TransferFactoryView <type-splice-api-token-transferinstructionv2-transferfactoryview-8084_>`_

  + **Choice** Archive

    Controller\: Signatories of implementing template

    Returns\: ()

    (no fields)

  + .. _type-splice-api-token-transferinstructionv2-transferfactorypublicfetch-11963:

    **Choice** `TransferFactory_PublicFetch <type-splice-api-token-transferinstructionv2-transferfactorypublicfetch-11963_>`_

    Fetch the view of the factory contract\.

    Controller\: actors

    Returns\: `TransferFactoryView <type-splice-api-token-transferinstructionv2-transferfactoryview-8084_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - actors
         - \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]
         - Set of parties executing the fetch\.

  + .. _type-splice-api-token-transferinstructionv2-transferfactorytransfer-54000:

    **Choice** `TransferFactory_Transfer <type-splice-api-token-transferinstructionv2-transferfactorytransfer-54000_>`_

    Instruct the registry to execute a transfer\.
    Implementations MUST ensure that this choice fails if ``transfer.executeBefore`` is in the past\.

    Controller\: actors

    Returns\: `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - transfer
         - `Transfer <type-splice-api-token-transferinstructionv2-transfer-64952_>`_
         - The transfer to execute\.  Implementations MUST validate that the admin of the transferred instrument matches the admin of the factory\.
       * - actors
         - \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]
         - Set of parties executing the transfer\.  Implementations MUST check these parties to avoid unauthorized transfer instructions\.  Implementations SHOULD  * complete the transfer in a single\-step if the actors include all   parties required to authorize the transfer * allow all the sender's account parties to call this choice, so they   can all initiate a transfer on their own
       * - extraArgs
         - ExtraArgs
         - The extra arguments to pass to the transfer implementation\.

  + **Method transferFactory\_publicFetchImpl \:** `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferFactory <type-splice-api-token-transferinstructionv2-transferfactory-72147_>`_ \-\> `TransferFactory_PublicFetch <type-splice-api-token-transferinstructionv2-transferfactorypublicfetch-11963_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferFactoryView <type-splice-api-token-transferinstructionv2-transferfactoryview-8084_>`_

  + **Method transferFactory\_transferExtraObservers \:** `TransferFactory_Transfer <type-splice-api-token-transferinstructionv2-transferfactorytransfer-54000_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

  + **Method transferFactory\_transferImpl \:** `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferFactory <type-splice-api-token-transferinstructionv2-transferfactory-72147_>`_ \-\> `TransferFactory_Transfer <type-splice-api-token-transferinstructionv2-transferfactorytransfer-54000_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

.. _type-splice-api-token-transferinstructionv2-transferinstruction-61129:

**interface** `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_

  An interface for tracking the status of a transfer instruction,
  i\.e\., a request to a registry app to execute a transfer\.

  **viewtype** `TransferInstructionView <type-splice-api-token-transferinstructionv2-transferinstructionview-64402_>`_

  + **Choice** Archive

    Controller\: Signatories of implementing template

    Returns\: ()

    (no fields)

  + .. _type-splice-api-token-transferinstructionv2-transferinstructionaccept-24017:

    **Choice** `TransferInstruction_Accept <type-splice-api-token-transferinstructionv2-transferinstructionaccept-24017_>`_

    Accept the transfer instruction as someone that needs to authorize it, either on the
    sending or receiving side\.

    Implementations MUST ensure that the transfer instruction is archived upon acceptance\.
    They SHOULD do so by calling ``TransferInstructionV1.TransferInstruction_Accept``
    where possible to maximize compatibility with v1 transaction parsers\.

    Note that while implementations will typically return ``TransferInstructionResult_Completed``,
    this is not guaranteed\. The result of the choice is implementation\-specific and MAY
    be any of the three possible results\.

    Controller\: actors

    Returns\: `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - actors
         - \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]
         - Set of parties executing the acceptance\.  Implementations MUST check these parties to avoid unauthorized acceptance\.  Implementations SHOULD allow the owner and the provider of the recipient account to call this choice\.
       * - extraArgs
         - ExtraArgs
         - Additional context required in order to exercise the choice\.

  + .. _type-splice-api-token-transferinstructionv2-transferinstructionreject-12876:

    **Choice** `TransferInstruction_Reject <type-splice-api-token-transferinstructionv2-transferinstructionreject-12876_>`_

    Reject the transfer offer represented by the transfer instruction\.

    Implementations MUST ensure that the transfer instruction is archived upon rejection\.
    They SHOULD do so by calling ``TransferInstructionV1.TransferInstruction_Reject``
    where possible to maximize compatibility with v1 transaction parsers\.

    Controller\: actors

    Returns\: `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - actors
         - \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]
         - Set of parties executing the rejection\.  Implementations MUST check these parties to avoid unauthorized rejection\.  Implementations SHOULD allow the owner and the provider of the recipient account to call this choice\.
       * - extraArgs
         - ExtraArgs
         - Additional context required in order to exercise the choice\.

  + .. _type-splice-api-token-transferinstructionv2-transferinstructionwithdraw-30201:

    **Choice** `TransferInstruction_Withdraw <type-splice-api-token-transferinstructionv2-transferinstructionwithdraw-30201_>`_

    Withdraw the transfer offer represented by the transfer instruction\.

    Implementations MUST ensure that the transfer instruction is archived upon withdrawal\.
    They SHOULD do so by calling ``TransferInstructionV1.TransferInstruction_Withdraw``
    where possible to maximize compatibility with v1 transaction parsers\.

    Controller\: actors

    Returns\: `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - actors
         - \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]
         - Set of parties executing the withdrawal\.  Implementations MUST check these parties to avoid unauthorized withdrawal\.  Implementations SHOULD allow the owner and the provider of the sender account to call this choice\.
       * - extraArgs
         - ExtraArgs
         - Additional context required in order to exercise the choice\.

  + **Method transferInstruction\_acceptExtraObservers \:** `TransferInstruction_Accept <type-splice-api-token-transferinstructionv2-transferinstructionaccept-24017_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

  + **Method transferInstruction\_acceptImpl \:** `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ \-\> `TransferInstruction_Accept <type-splice-api-token-transferinstructionv2-transferinstructionaccept-24017_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

  + **Method transferInstruction\_rejectExtraObservers \:** `TransferInstruction_Reject <type-splice-api-token-transferinstructionv2-transferinstructionreject-12876_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

  + **Method transferInstruction\_rejectImpl \:** `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ \-\> `TransferInstruction_Reject <type-splice-api-token-transferinstructionv2-transferinstructionreject-12876_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

  + **Method transferInstruction\_withdrawExtraObservers \:** `TransferInstruction_Withdraw <type-splice-api-token-transferinstructionv2-transferinstructionwithdraw-30201_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

  + **Method transferInstruction\_withdrawImpl \:** `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ \-\> `TransferInstruction_Withdraw <type-splice-api-token-transferinstructionv2-transferinstructionwithdraw-30201_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

Data Types
----------

.. _type-splice-api-token-transferinstructionv2-transfer-64952:

**data** `Transfer <type-splice-api-token-transferinstructionv2-transfer-64952_>`_

  A specification of a transfer of holdings between two accounts\.

  .. _constr-splice-api-token-transferinstructionv2-transfer-30735:

  `Transfer <constr-splice-api-token-transferinstructionv2-transfer-30735_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
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
         - InstrumentId
         - The instrument identifier\.
       * - requestedAt
         - `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_
         - Wallet provided timestamp when the transfer was requested\. MUST be in the past when instructing the transfer\.
       * - executeBefore
         - `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_
         - Until when (exclusive) the transfer may be executed\. MUST be in the future when instructing the transfer\.  Registries SHOULD NOT execute the transfer instruction after this time, so that senders can retry creating a new transfer instruction after this time\.  Registries MAY limit the duration between ``requestedAt`` and ``executeBefore`` to limit the resources spent on tracking active transfer instructions\.
       * - inputHoldingCids
         - \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]
         - The holding contracts that should be used to fund the transfer\.  The holdings MUST be owned by the sender account, and match the instrument specified by the transfer\.  MAY be empty if the registry supports automatic selection of holdings for transfers or does not represent holdings on\-ledger\.  If specified, then the transfer MUST archive all of these holdings, so that the execution of the transfer conflicts with any other transfers using these holdings\. Thereby allowing that the sender can use deliberate contention on holdings to prevent duplicate transfers\.
       * - meta
         - Metadata
         - Metadata\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `Transfer <type-splice-api-token-transferinstructionv2-transfer-64952_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `Transfer <type-splice-api-token-transferinstructionv2-transfer-64952_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"amount\" `Transfer <type-splice-api-token-transferinstructionv2-transfer-64952_>`_ `Decimal <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"executeBefore\" `Transfer <type-splice-api-token-transferinstructionv2-transfer-64952_>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"inputHoldingCids\" `Transfer <type-splice-api-token-transferinstructionv2-transfer-64952_>`_ \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"instrumentId\" `Transfer <type-splice-api-token-transferinstructionv2-transfer-64952_>`_ InstrumentId

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `Transfer <type-splice-api-token-transferinstructionv2-transfer-64952_>`_ Metadata

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"receiver\" `Transfer <type-splice-api-token-transferinstructionv2-transfer-64952_>`_ Account

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"requestedAt\" `Transfer <type-splice-api-token-transferinstructionv2-transfer-64952_>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"sender\" `Transfer <type-splice-api-token-transferinstructionv2-transfer-64952_>`_ Account

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"transfer\" `TransferFactory_Transfer <type-splice-api-token-transferinstructionv2-transferfactorytransfer-54000_>`_ `Transfer <type-splice-api-token-transferinstructionv2-transfer-64952_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"transfer\" `TransferInstructionView <type-splice-api-token-transferinstructionv2-transferinstructionview-64402_>`_ `Transfer <type-splice-api-token-transferinstructionv2-transfer-64952_>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"amount\" `Transfer <type-splice-api-token-transferinstructionv2-transfer-64952_>`_ `Decimal <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"executeBefore\" `Transfer <type-splice-api-token-transferinstructionv2-transfer-64952_>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"inputHoldingCids\" `Transfer <type-splice-api-token-transferinstructionv2-transfer-64952_>`_ \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"instrumentId\" `Transfer <type-splice-api-token-transferinstructionv2-transfer-64952_>`_ InstrumentId

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `Transfer <type-splice-api-token-transferinstructionv2-transfer-64952_>`_ Metadata

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"receiver\" `Transfer <type-splice-api-token-transferinstructionv2-transfer-64952_>`_ Account

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"requestedAt\" `Transfer <type-splice-api-token-transferinstructionv2-transfer-64952_>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"sender\" `Transfer <type-splice-api-token-transferinstructionv2-transfer-64952_>`_ Account

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"transfer\" `TransferFactory_Transfer <type-splice-api-token-transferinstructionv2-transferfactorytransfer-54000_>`_ `Transfer <type-splice-api-token-transferinstructionv2-transfer-64952_>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"transfer\" `TransferInstructionView <type-splice-api-token-transferinstructionv2-transferinstructionview-64402_>`_ `Transfer <type-splice-api-token-transferinstructionv2-transfer-64952_>`_

.. _type-splice-api-token-transferinstructionv2-transferfactoryview-8084:

**data** `TransferFactoryView <type-splice-api-token-transferinstructionv2-transferfactoryview-8084_>`_

  View for ``TransferFactory``\.

  .. _constr-splice-api-token-transferinstructionv2-transferfactoryview-61185:

  `TransferFactoryView <constr-splice-api-token-transferinstructionv2-transferfactoryview-61185_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - admin
         - `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The party representing the registry app that administers the instruments for which this transfer factory can be used\.
       * - meta
         - Metadata
         - Additional metadata specific to the transfer factory, used for extensibility\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `TransferFactoryView <type-splice-api-token-transferinstructionv2-transferfactoryview-8084_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `TransferFactoryView <type-splice-api-token-transferinstructionv2-transferfactoryview-8084_>`_

  **instance** HasMethod `TransferFactory <type-splice-api-token-transferinstructionv2-transferfactory-72147_>`_ \"transferFactory\_publicFetchImpl\" (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferFactory <type-splice-api-token-transferinstructionv2-transferfactory-72147_>`_ \-\> `TransferFactory_PublicFetch <type-splice-api-token-transferinstructionv2-transferfactorypublicfetch-11963_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferFactoryView <type-splice-api-token-transferinstructionv2-transferfactoryview-8084_>`_)

  **instance** `HasFromAnyView <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Internal-Interface-AnyView.html#class-da-internal-interface-anyview-hasfromanyview-30108>`_ `TransferFactory <type-splice-api-token-transferinstructionv2-transferfactory-72147_>`_ `TransferFactoryView <type-splice-api-token-transferinstructionv2-transferfactoryview-8084_>`_

  **instance** `HasInterfaceView <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-interface-hasinterfaceview-4492>`_ `TransferFactory <type-splice-api-token-transferinstructionv2-transferfactory-72147_>`_ `TransferFactoryView <type-splice-api-token-transferinstructionv2-transferfactoryview-8084_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"admin\" `TransferFactoryView <type-splice-api-token-transferinstructionv2-transferfactoryview-8084_>`_ `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `TransferFactoryView <type-splice-api-token-transferinstructionv2-transferfactoryview-8084_>`_ Metadata

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"admin\" `TransferFactoryView <type-splice-api-token-transferinstructionv2-transferfactoryview-8084_>`_ `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `TransferFactoryView <type-splice-api-token-transferinstructionv2-transferfactoryview-8084_>`_ Metadata

  **instance** `HasExercise <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `TransferFactory <type-splice-api-token-transferinstructionv2-transferfactory-72147_>`_ `TransferFactory_PublicFetch <type-splice-api-token-transferinstructionv2-transferfactorypublicfetch-11963_>`_ `TransferFactoryView <type-splice-api-token-transferinstructionv2-transferfactoryview-8084_>`_

  **instance** `HasExerciseGuarded <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `TransferFactory <type-splice-api-token-transferinstructionv2-transferfactory-72147_>`_ `TransferFactory_PublicFetch <type-splice-api-token-transferinstructionv2-transferfactorypublicfetch-11963_>`_ `TransferFactoryView <type-splice-api-token-transferinstructionv2-transferfactoryview-8084_>`_

  **instance** `HasFromAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `TransferFactory <type-splice-api-token-transferinstructionv2-transferfactory-72147_>`_ `TransferFactory_PublicFetch <type-splice-api-token-transferinstructionv2-transferfactorypublicfetch-11963_>`_ `TransferFactoryView <type-splice-api-token-transferinstructionv2-transferfactoryview-8084_>`_

  **instance** `HasToAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `TransferFactory <type-splice-api-token-transferinstructionv2-transferfactory-72147_>`_ `TransferFactory_PublicFetch <type-splice-api-token-transferinstructionv2-transferfactorypublicfetch-11963_>`_ `TransferFactoryView <type-splice-api-token-transferinstructionv2-transferfactoryview-8084_>`_

.. _type-splice-api-token-transferinstructionv2-transferinstructionaction-41433:

**data** `TransferInstructionAction <type-splice-api-token-transferinstructionv2-transferinstructionaction-41433_>`_

  Actions to advance the state of a transfer instruction\.

  .. _constr-splice-api-token-transferinstructionv2-tiaaccept-61933:

  `TIA_Accept <constr-splice-api-token-transferinstructionv2-tiaaccept-61933_>`_


  .. _constr-splice-api-token-transferinstructionv2-tiareject-58512:

  `TIA_Reject <constr-splice-api-token-transferinstructionv2-tiareject-58512_>`_


  .. _constr-splice-api-token-transferinstructionv2-tiawithdraw-34893:

  `TIA_Withdraw <constr-splice-api-token-transferinstructionv2-tiawithdraw-34893_>`_


  .. _constr-splice-api-token-transferinstructionv2-tiacustom-41440:

  `TIA_Custom <constr-splice-api-token-transferinstructionv2-tiacustom-41440_>`_

    Used to represent registry\-specific actions that need to happen
    for the transfer instruction to advance\.

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - id
         - `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_
         - Identifier of the action\. Namespaced analogously to metadata keys\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `TransferInstructionAction <type-splice-api-token-transferinstructionv2-transferinstructionaction-41433_>`_

  **instance** `Ord <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-ord-6395>`_ `TransferInstructionAction <type-splice-api-token-transferinstructionv2-transferinstructionaction-41433_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `TransferInstructionAction <type-splice-api-token-transferinstructionv2-transferinstructionaction-41433_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"availableActions\" `TransferInstructionView <type-splice-api-token-transferinstructionv2-transferinstructionview-64402_>`_ (`Map <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-map-90052>`_ `TransferInstructionAction <type-splice-api-token-transferinstructionv2-transferinstructionaction-41433_>`_ \[\[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]\])

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"id\" `TransferInstructionAction <type-splice-api-token-transferinstructionv2-transferinstructionaction-41433_>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"availableActions\" `TransferInstructionView <type-splice-api-token-transferinstructionv2-transferinstructionview-64402_>`_ (`Map <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-map-90052>`_ `TransferInstructionAction <type-splice-api-token-transferinstructionv2-transferinstructionaction-41433_>`_ \[\[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]\])

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"id\" `TransferInstructionAction <type-splice-api-token-transferinstructionv2-transferinstructionaction-41433_>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

.. _type-splice-api-token-transferinstructionv2-transferinstructionresult-97952:

**data** `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

  The result of instructing a transfer or advancing the state of a transfer instruction\.

  .. _constr-splice-api-token-transferinstructionv2-transferinstructionresult-40597:

  `TransferInstructionResult <constr-splice-api-token-transferinstructionv2-transferinstructionresult-40597_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - output
         - `TransferInstructionResult_Output <type-splice-api-token-transferinstructionv2-transferinstructionresultoutput-2217_>`_
         - The output of the step\.
       * - senderChangeCids
         - \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]
         - New holdings owned by the sender created to return \"change\"\. Can be used by actors to batch creating or updating multiple transfer instructions in a single Daml transaction\.
       * - meta
         - Metadata
         - Additional metadata specific to the transfer instruction, used for extensibility; e\.g\., fees charged\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

  **instance** HasMethod `TransferFactory <type-splice-api-token-transferinstructionv2-transferfactory-72147_>`_ \"transferFactory\_transferImpl\" (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferFactory <type-splice-api-token-transferinstructionv2-transferfactory-72147_>`_ \-\> `TransferFactory_Transfer <type-splice-api-token-transferinstructionv2-transferfactorytransfer-54000_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_)

  **instance** HasMethod `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ \"transferInstruction\_acceptImpl\" (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ \-\> `TransferInstruction_Accept <type-splice-api-token-transferinstructionv2-transferinstructionaccept-24017_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_)

  **instance** HasMethod `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ \"transferInstruction\_rejectImpl\" (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ \-\> `TransferInstruction_Reject <type-splice-api-token-transferinstructionv2-transferinstructionreject-12876_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_)

  **instance** HasMethod `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ \"transferInstruction\_withdrawImpl\" (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ \-\> `TransferInstruction_Withdraw <type-splice-api-token-transferinstructionv2-transferinstructionwithdraw-30201_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_)

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_ Metadata

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"output\" `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_ `TransferInstructionResult_Output <type-splice-api-token-transferinstructionv2-transferinstructionresultoutput-2217_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"senderChangeCids\" `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_ \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_ Metadata

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"output\" `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_ `TransferInstructionResult_Output <type-splice-api-token-transferinstructionv2-transferinstructionresultoutput-2217_>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"senderChangeCids\" `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_ \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `HasExercise <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `TransferFactory <type-splice-api-token-transferinstructionv2-transferfactory-72147_>`_ `TransferFactory_Transfer <type-splice-api-token-transferinstructionv2-transferfactorytransfer-54000_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

  **instance** `HasExercise <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ `TransferInstruction_Accept <type-splice-api-token-transferinstructionv2-transferinstructionaccept-24017_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

  **instance** `HasExercise <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ `TransferInstruction_Reject <type-splice-api-token-transferinstructionv2-transferinstructionreject-12876_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

  **instance** `HasExercise <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ `TransferInstruction_Withdraw <type-splice-api-token-transferinstructionv2-transferinstructionwithdraw-30201_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

  **instance** `HasExerciseGuarded <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `TransferFactory <type-splice-api-token-transferinstructionv2-transferfactory-72147_>`_ `TransferFactory_Transfer <type-splice-api-token-transferinstructionv2-transferfactorytransfer-54000_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

  **instance** `HasExerciseGuarded <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ `TransferInstruction_Accept <type-splice-api-token-transferinstructionv2-transferinstructionaccept-24017_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

  **instance** `HasExerciseGuarded <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ `TransferInstruction_Reject <type-splice-api-token-transferinstructionv2-transferinstructionreject-12876_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

  **instance** `HasExerciseGuarded <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ `TransferInstruction_Withdraw <type-splice-api-token-transferinstructionv2-transferinstructionwithdraw-30201_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

  **instance** `HasFromAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `TransferFactory <type-splice-api-token-transferinstructionv2-transferfactory-72147_>`_ `TransferFactory_Transfer <type-splice-api-token-transferinstructionv2-transferfactorytransfer-54000_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

  **instance** `HasFromAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ `TransferInstruction_Accept <type-splice-api-token-transferinstructionv2-transferinstructionaccept-24017_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

  **instance** `HasFromAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ `TransferInstruction_Reject <type-splice-api-token-transferinstructionv2-transferinstructionreject-12876_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

  **instance** `HasFromAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ `TransferInstruction_Withdraw <type-splice-api-token-transferinstructionv2-transferinstructionwithdraw-30201_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

  **instance** `HasToAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `TransferFactory <type-splice-api-token-transferinstructionv2-transferfactory-72147_>`_ `TransferFactory_Transfer <type-splice-api-token-transferinstructionv2-transferfactorytransfer-54000_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

  **instance** `HasToAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ `TransferInstruction_Accept <type-splice-api-token-transferinstructionv2-transferinstructionaccept-24017_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

  **instance** `HasToAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ `TransferInstruction_Reject <type-splice-api-token-transferinstructionv2-transferinstructionreject-12876_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

  **instance** `HasToAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ `TransferInstruction_Withdraw <type-splice-api-token-transferinstructionv2-transferinstructionwithdraw-30201_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

.. _type-splice-api-token-transferinstructionv2-transferinstructionresultoutput-2217:

**data** `TransferInstructionResult_Output <type-splice-api-token-transferinstructionv2-transferinstructionresultoutput-2217_>`_

  The output of instructing a transfer or advancing the state of a transfer instruction\.

  .. _constr-splice-api-token-transferinstructionv2-transferinstructionresultpending-89401:

  `TransferInstructionResult_Pending <constr-splice-api-token-transferinstructionv2-transferinstructionresultpending-89401_>`_

    Use this result to communicate that the transfer is pending further steps\.

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - transferInstructionCid
         - `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_
         - Contract id of the transfer instruction representing the pending state\.

  .. _constr-splice-api-token-transferinstructionv2-transferinstructionresultcompleted-30685:

  `TransferInstructionResult_Completed <constr-splice-api-token-transferinstructionv2-transferinstructionresultcompleted-30685_>`_

    Use this result to communicate that the transfer succeeded and the receiver
    has received their holdings\.

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - receiverHoldingCids
         - \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]
         - The newly created holdings owned by the receiver as part of successfully completing the transfer\.  MAY be empty if the registry does not represent holdings on\-ledger\.

  .. _constr-splice-api-token-transferinstructionv2-transferinstructionresultfailed-73018:

  `TransferInstructionResult_Failed <constr-splice-api-token-transferinstructionv2-transferinstructionresultfailed-73018_>`_

    Use this result to communicate that the transfer did not succeed and all holdings (minus fees)
    have been returned to the sender\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `TransferInstructionResult_Output <type-splice-api-token-transferinstructionv2-transferinstructionresultoutput-2217_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `TransferInstructionResult_Output <type-splice-api-token-transferinstructionv2-transferinstructionresultoutput-2217_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"output\" `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_ `TransferInstructionResult_Output <type-splice-api-token-transferinstructionv2-transferinstructionresultoutput-2217_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"receiverHoldingCids\" `TransferInstructionResult_Output <type-splice-api-token-transferinstructionv2-transferinstructionresultoutput-2217_>`_ \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"transferInstructionCid\" `TransferInstructionResult_Output <type-splice-api-token-transferinstructionv2-transferinstructionresultoutput-2217_>`_ (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_)

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"output\" `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_ `TransferInstructionResult_Output <type-splice-api-token-transferinstructionv2-transferinstructionresultoutput-2217_>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"receiverHoldingCids\" `TransferInstructionResult_Output <type-splice-api-token-transferinstructionv2-transferinstructionresultoutput-2217_>`_ \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"transferInstructionCid\" `TransferInstructionResult_Output <type-splice-api-token-transferinstructionv2-transferinstructionresultoutput-2217_>`_ (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_)

.. _type-splice-api-token-transferinstructionv2-transferinstructionview-64402:

**data** `TransferInstructionView <type-splice-api-token-transferinstructionv2-transferinstructionview-64402_>`_

  View for ``TransferInstruction``\.

  .. _constr-splice-api-token-transferinstructionv2-transferinstructionview-16095:

  `TransferInstructionView <constr-splice-api-token-transferinstructionv2-transferinstructionview-16095_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - originalInstructionCid
         - `Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_)
         - The contract id of the original transfer instruction contract, which is ``None`` for the original transfer instruction itself\.  This SHOULD be used by wallets to correlate the same transfer instruction across updates to its state\.
       * - transfer
         - `Transfer <type-splice-api-token-transferinstructionv2-transfer-64952_>`_
         - The transfer specified by the transfer instruction\.
       * - expiresAt
         - `Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_
         - The time at which the transfer instruction expires if inactive\.  Registries MAY expire the transfer instruction after this time\. Thereby recovering storage resources and protecting themselves from denial\-of\-service attacks\.  Registries SHOULD avoid unnecessary expiries by  * making the expiry time as close to the transfer's executeBefore as possible * bumping expiry on every action on the transfer instruction
       * - availableActions
         - `Map <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-map-90052>`_ `TransferInstructionAction <type-splice-api-token-transferinstructionv2-transferinstructionaction-41433_>`_ \[\[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]\]
         - What actions are available to which groups of parties\. The list of lists is interpreted as a set of sets and represents a disjunction of conjunctions of parties, i\.e\., each inner list represents a group of parties that can act jointly to execute the action\.  This field can be used to inform wallet users whether they can take an action or not; and which other parties they might be waiting on to take their action\.  Supports multiple parties for actions that require joint authorization\. Executing them will require appropriate, registry\-specific delegation contracts to be in place\.
       * - meta
         - Metadata
         - Additional metadata specific to the transfer instruction, used for extensibility; e\.g\., more detailed status information\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `TransferInstructionView <type-splice-api-token-transferinstructionv2-transferinstructionview-64402_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `TransferInstructionView <type-splice-api-token-transferinstructionv2-transferinstructionview-64402_>`_

  **instance** `HasFromAnyView <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Internal-Interface-AnyView.html#class-da-internal-interface-anyview-hasfromanyview-30108>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ `TransferInstructionView <type-splice-api-token-transferinstructionv2-transferinstructionview-64402_>`_

  **instance** `HasInterfaceView <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-interface-hasinterfaceview-4492>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ `TransferInstructionView <type-splice-api-token-transferinstructionv2-transferinstructionview-64402_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"availableActions\" `TransferInstructionView <type-splice-api-token-transferinstructionv2-transferinstructionview-64402_>`_ (`Map <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-map-90052>`_ `TransferInstructionAction <type-splice-api-token-transferinstructionv2-transferinstructionaction-41433_>`_ \[\[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]\])

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"expiresAt\" `TransferInstructionView <type-splice-api-token-transferinstructionv2-transferinstructionview-64402_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_)

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `TransferInstructionView <type-splice-api-token-transferinstructionv2-transferinstructionview-64402_>`_ Metadata

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"originalInstructionCid\" `TransferInstructionView <type-splice-api-token-transferinstructionv2-transferinstructionview-64402_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_))

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"transfer\" `TransferInstructionView <type-splice-api-token-transferinstructionv2-transferinstructionview-64402_>`_ `Transfer <type-splice-api-token-transferinstructionv2-transfer-64952_>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"availableActions\" `TransferInstructionView <type-splice-api-token-transferinstructionv2-transferinstructionview-64402_>`_ (`Map <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-map-90052>`_ `TransferInstructionAction <type-splice-api-token-transferinstructionv2-transferinstructionaction-41433_>`_ \[\[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]\])

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"expiresAt\" `TransferInstructionView <type-splice-api-token-transferinstructionv2-transferinstructionview-64402_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_)

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `TransferInstructionView <type-splice-api-token-transferinstructionv2-transferinstructionview-64402_>`_ Metadata

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"originalInstructionCid\" `TransferInstructionView <type-splice-api-token-transferinstructionv2-transferinstructionview-64402_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_))

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"transfer\" `TransferInstructionView <type-splice-api-token-transferinstructionv2-transferinstructionview-64402_>`_ `Transfer <type-splice-api-token-transferinstructionv2-transfer-64952_>`_

Functions
---------

.. _function-splice-api-token-transferinstructionv2-transferinstructionacceptimpl-46747:

`transferInstruction_acceptImpl <function-splice-api-token-transferinstructionv2-transferinstructionacceptimpl-46747_>`_
  \: `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ \-\> `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ \-\> `TransferInstruction_Accept <type-splice-api-token-transferinstructionv2-transferinstructionaccept-24017_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

.. _function-splice-api-token-transferinstructionv2-transferinstructionrejectimpl-68290:

`transferInstruction_rejectImpl <function-splice-api-token-transferinstructionv2-transferinstructionrejectimpl-68290_>`_
  \: `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ \-\> `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ \-\> `TransferInstruction_Reject <type-splice-api-token-transferinstructionv2-transferinstructionreject-12876_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

.. _function-splice-api-token-transferinstructionv2-transferinstructionwithdrawimpl-38143:

`transferInstruction_withdrawImpl <function-splice-api-token-transferinstructionv2-transferinstructionwithdrawimpl-38143_>`_
  \: `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ \-\> `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ \-\> `TransferInstruction_Withdraw <type-splice-api-token-transferinstructionv2-transferinstructionwithdraw-30201_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

.. _function-splice-api-token-transferinstructionv2-transferinstructionacceptextraobservers-992:

`transferInstruction_acceptExtraObservers <function-splice-api-token-transferinstructionv2-transferinstructionacceptextraobservers-992_>`_
  \: `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ \-\> `TransferInstruction_Accept <type-splice-api-token-transferinstructionv2-transferinstructionaccept-24017_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

.. _function-splice-api-token-transferinstructionv2-transferinstructionrejectextraobservers-63697:

`transferInstruction_rejectExtraObservers <function-splice-api-token-transferinstructionv2-transferinstructionrejectextraobservers-63697_>`_
  \: `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ \-\> `TransferInstruction_Reject <type-splice-api-token-transferinstructionv2-transferinstructionreject-12876_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

.. _function-splice-api-token-transferinstructionv2-transferinstructionwithdrawextraobservers-12020:

`transferInstruction_withdrawExtraObservers <function-splice-api-token-transferinstructionv2-transferinstructionwithdrawextraobservers-12020_>`_
  \: `TransferInstruction <type-splice-api-token-transferinstructionv2-transferinstruction-61129_>`_ \-\> `TransferInstruction_Withdraw <type-splice-api-token-transferinstructionv2-transferinstructionwithdraw-30201_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

.. _function-splice-api-token-transferinstructionv2-transferfactorytransferextraobservers-52409:

`transferFactory_transferExtraObservers <function-splice-api-token-transferinstructionv2-transferfactorytransferextraobservers-52409_>`_
  \: `TransferFactory <type-splice-api-token-transferinstructionv2-transferfactory-72147_>`_ \-\> `TransferFactory_Transfer <type-splice-api-token-transferinstructionv2-transferfactorytransfer-54000_>`_ \-\> \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

.. _function-splice-api-token-transferinstructionv2-transferfactorytransferimpl-94762:

`transferFactory_transferImpl <function-splice-api-token-transferinstructionv2-transferfactorytransferimpl-94762_>`_
  \: `TransferFactory <type-splice-api-token-transferinstructionv2-transferfactory-72147_>`_ \-\> `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferFactory <type-splice-api-token-transferinstructionv2-transferfactory-72147_>`_ \-\> `TransferFactory_Transfer <type-splice-api-token-transferinstructionv2-transferfactorytransfer-54000_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv2-transferinstructionresult-97952_>`_

.. _function-splice-api-token-transferinstructionv2-transferfactorypublicfetchimpl-31481:

`transferFactory_publicFetchImpl <function-splice-api-token-transferinstructionv2-transferfactorypublicfetchimpl-31481_>`_
  \: `TransferFactory <type-splice-api-token-transferinstructionv2-transferfactory-72147_>`_ \-\> `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferFactory <type-splice-api-token-transferinstructionv2-transferfactory-72147_>`_ \-\> `TransferFactory_PublicFetch <type-splice-api-token-transferinstructionv2-transferfactorypublicfetch-11963_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferFactoryView <type-splice-api-token-transferinstructionv2-transferfactoryview-8084_>`_
