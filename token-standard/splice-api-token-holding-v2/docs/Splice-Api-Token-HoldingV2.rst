..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _module-splice-api-token-holdingv2-15657:

Splice.Api.Token.HoldingV2
==========================

Types and interfaces for retrieving an investor's holdings\.

Interfaces
----------

.. _type-splice-api-token-holdingv2-holding-65433:

**interface** `Holding <type-splice-api-token-holdingv2-holding-65433_>`_

  Holding interface\.

  **viewtype** `HoldingView <type-splice-api-token-holdingv2-holdingview-66978_>`_

  + **Choice** Archive

    Controller\: Signatories of implementing template

    Returns\: ()

    (no fields)


Data Types
----------

.. _type-splice-api-token-holdingv2-account-52093:

**data** `Account <type-splice-api-token-holdingv2-account-52093_>`_

  A data type to represent an on\-chain managed account,

  For example a traditional accounting structure, or simply a delegation to
  a provider to perform some services\.

  .. _constr-splice-api-token-holdingv2-account-85680:

  `Account <constr-splice-api-token-holdingv2-account-85680_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - owner
         - `Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The party that owns the account, which MUST be set for regular accounts\.  ``None`` is reserved for special accounts managed by the instrument admin\. Special accounts are for example used to represent the source account for mints and the target account for burns\.
       * - provider
         - `Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The provider of the account\.  Account providers MUST have visibility on all asset movements and holdings\. Asset implementations are free to determine how authorization for asset movements is split between providers and owners\. For example, providers MAY have to authorize all movements requested by the owner\.
       * - id
         - `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_
         - Account number or similar\. Use the empty string (\"\") as the default account identifier\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `Account <type-splice-api-token-holdingv2-account-52093_>`_

  **instance** `Ord <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-ord-6395>`_ `Account <type-splice-api-token-holdingv2-account-52093_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `Account <type-splice-api-token-holdingv2-account-52093_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"account\" `HoldingView <type-splice-api-token-holdingv2-holdingview-66978_>`_ `Account <type-splice-api-token-holdingv2-account-52093_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"id\" `Account <type-splice-api-token-holdingv2-account-52093_>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"owner\" `Account <type-splice-api-token-holdingv2-account-52093_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_)

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"provider\" `Account <type-splice-api-token-holdingv2-account-52093_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_)

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"account\" `HoldingView <type-splice-api-token-holdingv2-holdingview-66978_>`_ `Account <type-splice-api-token-holdingv2-account-52093_>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"id\" `Account <type-splice-api-token-holdingv2-account-52093_>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"owner\" `Account <type-splice-api-token-holdingv2-account-52093_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_)

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"provider\" `Account <type-splice-api-token-holdingv2-account-52093_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_)

.. _type-splice-api-token-holdingv2-holdingview-66978:

**data** `HoldingView <type-splice-api-token-holdingv2-holdingview-66978_>`_

  View for ``Holding``\.

  .. _constr-splice-api-token-holdingv2-holdingview-87327:

  `HoldingView <constr-splice-api-token-holdingv2-holdingview-87327_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - account
         - `Account <type-splice-api-token-holdingv2-account-52093_>`_
         - Account in which the holding is held\.
       * - instrumentId
         - `InstrumentId <type-splice-api-token-holdingv2-instrumentid-91751_>`_
         - Instrument being held\.
       * - amount
         - `Decimal <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_
         - Size of the holding\.
       * - lock
         - `Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Lock <type-splice-api-token-holdingv2-lock-18966_>`_
         - Lock on the holding\.  Registries SHOULD allow holdings with expired locks as inputs to transfers to enable a combined unlocking \+ use choice\.
       * - meta
         - Metadata
         - Metadata\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `HoldingView <type-splice-api-token-holdingv2-holdingview-66978_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `HoldingView <type-splice-api-token-holdingv2-holdingview-66978_>`_

  **instance** `HasFromAnyView <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Internal-Interface-AnyView.html#class-da-internal-interface-anyview-hasfromanyview-30108>`_ `Holding <type-splice-api-token-holdingv2-holding-65433_>`_ `HoldingView <type-splice-api-token-holdingv2-holdingview-66978_>`_

  **instance** `HasInterfaceView <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-interface-hasinterfaceview-4492>`_ `Holding <type-splice-api-token-holdingv2-holding-65433_>`_ `HoldingView <type-splice-api-token-holdingv2-holdingview-66978_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"account\" `HoldingView <type-splice-api-token-holdingv2-holdingview-66978_>`_ `Account <type-splice-api-token-holdingv2-account-52093_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"amount\" `HoldingView <type-splice-api-token-holdingv2-holdingview-66978_>`_ `Decimal <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"instrumentId\" `HoldingView <type-splice-api-token-holdingv2-holdingview-66978_>`_ `InstrumentId <type-splice-api-token-holdingv2-instrumentid-91751_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"lock\" `HoldingView <type-splice-api-token-holdingv2-holdingview-66978_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Lock <type-splice-api-token-holdingv2-lock-18966_>`_)

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `HoldingView <type-splice-api-token-holdingv2-holdingview-66978_>`_ Metadata

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"account\" `HoldingView <type-splice-api-token-holdingv2-holdingview-66978_>`_ `Account <type-splice-api-token-holdingv2-account-52093_>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"amount\" `HoldingView <type-splice-api-token-holdingv2-holdingview-66978_>`_ `Decimal <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"instrumentId\" `HoldingView <type-splice-api-token-holdingv2-holdingview-66978_>`_ `InstrumentId <type-splice-api-token-holdingv2-instrumentid-91751_>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"lock\" `HoldingView <type-splice-api-token-holdingv2-holdingview-66978_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Lock <type-splice-api-token-holdingv2-lock-18966_>`_)

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `HoldingView <type-splice-api-token-holdingv2-holdingview-66978_>`_ Metadata

.. _type-splice-api-token-holdingv2-instrumentid-91751:

**data** `InstrumentId <type-splice-api-token-holdingv2-instrumentid-91751_>`_

  A globally unique identifier for instruments\.

  .. _constr-splice-api-token-holdingv2-instrumentid-6896:

  `InstrumentId <constr-splice-api-token-holdingv2-instrumentid-6896_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - admin
         - `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The party representing the registry app that administers the instrument\.
       * - id
         - `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_
         - The identifier used for the instrument by the instrument admin\.  This identifier MUST be unique and unambiguous per instrument admin\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `InstrumentId <type-splice-api-token-holdingv2-instrumentid-91751_>`_

  **instance** `Ord <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-ord-6395>`_ `InstrumentId <type-splice-api-token-holdingv2-instrumentid-91751_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `InstrumentId <type-splice-api-token-holdingv2-instrumentid-91751_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"admin\" `InstrumentId <type-splice-api-token-holdingv2-instrumentid-91751_>`_ `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"id\" `InstrumentId <type-splice-api-token-holdingv2-instrumentid-91751_>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"instrumentId\" `HoldingView <type-splice-api-token-holdingv2-holdingview-66978_>`_ `InstrumentId <type-splice-api-token-holdingv2-instrumentid-91751_>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"admin\" `InstrumentId <type-splice-api-token-holdingv2-instrumentid-91751_>`_ `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"id\" `InstrumentId <type-splice-api-token-holdingv2-instrumentid-91751_>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"instrumentId\" `HoldingView <type-splice-api-token-holdingv2-holdingview-66978_>`_ `InstrumentId <type-splice-api-token-holdingv2-instrumentid-91751_>`_

.. _type-splice-api-token-holdingv2-lock-18966:

**data** `Lock <type-splice-api-token-holdingv2-lock-18966_>`_

  Details of a lock\.

  .. _constr-splice-api-token-holdingv2-lock-95297:

  `Lock <constr-splice-api-token-holdingv2-lock-95297_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - holders
         - \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]
         - Unique list of parties which are locking the contract\. (Represented as a list, as that has the better JSON encoding\.)
       * - expiresAt
         - `Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_
         - Absolute, inclusive deadline as of which the lock expires\.
       * - expiresAfter
         - `Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `RelTime <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Time.html#type-da-time-types-reltime-23082>`_
         - Duration after which the created lock expires\. Measured relative to the ledger time that the locked holding contract was created\.  If both ``expiresAt`` and ``expiresAfter`` are set, the lock expires at the earlier of the two times\.
       * - context
         - `Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_
         - Short, human\-readable description of the context of the lock\. Used by wallets to enable users to understand the reason for the lock\.  Note that the visibility of the content in this field might be wider than the visibility of the contracts in the context\. You should thus carefully decide what information is safe to put in the lock context\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `Lock <type-splice-api-token-holdingv2-lock-18966_>`_

  **instance** `Ord <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-ord-6395>`_ `Lock <type-splice-api-token-holdingv2-lock-18966_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `Lock <type-splice-api-token-holdingv2-lock-18966_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"context\" `Lock <type-splice-api-token-holdingv2-lock-18966_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_)

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"expiresAfter\" `Lock <type-splice-api-token-holdingv2-lock-18966_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `RelTime <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Time.html#type-da-time-types-reltime-23082>`_)

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"expiresAt\" `Lock <type-splice-api-token-holdingv2-lock-18966_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_)

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"holders\" `Lock <type-splice-api-token-holdingv2-lock-18966_>`_ \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"lock\" `HoldingView <type-splice-api-token-holdingv2-holdingview-66978_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Lock <type-splice-api-token-holdingv2-lock-18966_>`_)

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"context\" `Lock <type-splice-api-token-holdingv2-lock-18966_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_)

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"expiresAfter\" `Lock <type-splice-api-token-holdingv2-lock-18966_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `RelTime <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Time.html#type-da-time-types-reltime-23082>`_)

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"expiresAt\" `Lock <type-splice-api-token-holdingv2-lock-18966_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_)

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"holders\" `Lock <type-splice-api-token-holdingv2-lock-18966_>`_ \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"lock\" `HoldingView <type-splice-api-token-holdingv2-holdingview-66978_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Lock <type-splice-api-token-holdingv2-lock-18966_>`_)
