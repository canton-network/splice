..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _module-splice-api-token-transfereventsv2-53771:

Splice.Api.Token.TransferEventsV2
=================================

Interface to log events for token standard transfers\.

Interfaces
----------

.. _type-splice-api-token-transfereventsv2-eventlog-71599:

**interface** `EventLog <type-splice-api-token-transfereventsv2-eventlog-71599_>`_

  An interface for templates that can be used to report token standard v2 events\.

  The non\-consuming choice of this interface is used by the instrument admin to
  report token transfers\. Make sure to only consider events from the instrument
  admin as token transfer events for a token\.

  **viewtype** `EventLogView <type-splice-api-token-transfereventsv2-eventlogview-52356_>`_

  + **Choice** Archive

    Controller\: Signatories of implementing template

    Returns\: ()

    (no fields)

  + .. _type-splice-api-token-transfereventsv2-eventlogholdingschange-79855:

    **Choice** `EventLog_HoldingsChange <type-splice-api-token-transfereventsv2-eventlogholdingschange-79855_>`_

    Signal a change in the holdings of an account to the observers\.

    Asset admins MUST ensure that these events explain\:

    1. All changes to the holdings of all regular accounts, including holdings that
       were created and archived within a single transaction\.
    2. All incoming and outgoing transfers for all regular accounts\.

    Controller\: admin

    Returns\: `EventLog_HoldingsChangeResult <type-splice-api-token-transfereventsv2-eventlogholdingschangeresult-2898_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - admin
         - `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - Instrument admin reporting the change in holdings\.
       * - account
         - Account
         - The account for which the change in holdings occurred\.
       * - inputHoldingCids
         - \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]
         - The holdings of the account that were consumed\.  They MUST be archived in the same transaction and they MUST be owned by the ``account`` specified in this change\.  Note that these MAY include holdings that were created and archived within the same transaction\. Such holding contract ids will occur in both the input and output list of holdings\.
       * - transferLegSides
         - \[`TransferLegSide <type-splice-api-token-transfereventsv2-transferlegside-2800_>`_\]
         - The transfers that caused the change in holdings\.  Their net balance changes MUST match the change in holdings of the account\. Both sides of a transfer MUST be reported, and their ids must match\. Transfer leg ids MUST be unique for different transfer legs\.  Note that the settlement of transfers whose net balance change is zero MAY result in events that do have empty input and output holdings\.  Note also that merging and splitting of holdings MAY be reported with an empty list of transfer legs, as the merge and split actions may not be related to a (self\-)transfer of tokens\.
       * - outputHoldingCids
         - \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]
         - The newly created holdings of the account\.
       * - observers
         - \[`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]
         - Parties that should be notified about the change in holdings\.  MUST include at least the account parties\.
       * - extraArgs
         - ExtraArgs
         - Arguments storing both metadata and additional context information about the event\.  Use ``splice.lfdecentralizedtrust.org/reason`` to explain the reason for the overall change in holdings\.  The ``ChoiceContext`` is provided to allow storing extra links to contract\-ids, which is not possible using ``Metadata`` alone, as contract\-ids cannot be stored as ``Text``\.

  + **Method eventLog\_holdingsChangeImpl \:** `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `EventLog <type-splice-api-token-transfereventsv2-eventlog-71599_>`_ \-\> `EventLog_HoldingsChange <type-splice-api-token-transfereventsv2-eventlogholdingschange-79855_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `EventLog_HoldingsChangeResult <type-splice-api-token-transfereventsv2-eventlogholdingschangeresult-2898_>`_

Data Types
----------

.. _type-splice-api-token-transfereventsv2-eventlogview-52356:

**data** `EventLogView <type-splice-api-token-transfereventsv2-eventlogview-52356_>`_

  View of an ``EventLog`` contract\.

  Note that ``EventLog`` contracts do not support public fetching, as they are intended to
  be used by the instrument admin or app provider only\.

  .. _constr-splice-api-token-transfereventsv2-eventlogview-70213:

  `EventLogView <constr-splice-api-token-transfereventsv2-eventlogview-70213_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - admin
         - `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - Instrument admin reporting the change in holdings\.
       * - meta
         - Metadata
         - Additional metadata about the event log, used for extensibility\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `EventLogView <type-splice-api-token-transfereventsv2-eventlogview-52356_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `EventLogView <type-splice-api-token-transfereventsv2-eventlogview-52356_>`_

  **instance** `HasFromAnyView <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Internal-Interface-AnyView.html#class-da-internal-interface-anyview-hasfromanyview-30108>`_ `EventLog <type-splice-api-token-transfereventsv2-eventlog-71599_>`_ `EventLogView <type-splice-api-token-transfereventsv2-eventlogview-52356_>`_

  **instance** `HasInterfaceView <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-interface-hasinterfaceview-4492>`_ `EventLog <type-splice-api-token-transfereventsv2-eventlog-71599_>`_ `EventLogView <type-splice-api-token-transfereventsv2-eventlogview-52356_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"admin\" `EventLogView <type-splice-api-token-transfereventsv2-eventlogview-52356_>`_ `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `EventLogView <type-splice-api-token-transfereventsv2-eventlogview-52356_>`_ Metadata

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"admin\" `EventLogView <type-splice-api-token-transfereventsv2-eventlogview-52356_>`_ `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `EventLogView <type-splice-api-token-transfereventsv2-eventlogview-52356_>`_ Metadata

.. _type-splice-api-token-transfereventsv2-eventlogholdingschangeresult-2898:

**data** `EventLog_HoldingsChangeResult <type-splice-api-token-transfereventsv2-eventlogholdingschangeresult-2898_>`_

  Result of logging a change in holdings event\. Provided for extensibility
  in a future where interfaces are upgradeable\.

  .. _constr-splice-api-token-transfereventsv2-eventlogholdingschangeresult-32585:

  `EventLog_HoldingsChangeResult <constr-splice-api-token-transfereventsv2-eventlogholdingschangeresult-32585_>`_

    (no fields)

  **instance** HasMethod `EventLog <type-splice-api-token-transfereventsv2-eventlog-71599_>`_ \"eventLog\_holdingsChangeImpl\" (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `EventLog <type-splice-api-token-transfereventsv2-eventlog-71599_>`_ \-\> `EventLog_HoldingsChange <type-splice-api-token-transfereventsv2-eventlogholdingschange-79855_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `EventLog_HoldingsChangeResult <type-splice-api-token-transfereventsv2-eventlogholdingschangeresult-2898_>`_)

  **instance** `HasExercise <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `EventLog <type-splice-api-token-transfereventsv2-eventlog-71599_>`_ `EventLog_HoldingsChange <type-splice-api-token-transfereventsv2-eventlogholdingschange-79855_>`_ `EventLog_HoldingsChangeResult <type-splice-api-token-transfereventsv2-eventlogholdingschangeresult-2898_>`_

  **instance** `HasExerciseGuarded <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `EventLog <type-splice-api-token-transfereventsv2-eventlog-71599_>`_ `EventLog_HoldingsChange <type-splice-api-token-transfereventsv2-eventlogholdingschange-79855_>`_ `EventLog_HoldingsChangeResult <type-splice-api-token-transfereventsv2-eventlogholdingschangeresult-2898_>`_

  **instance** `HasFromAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `EventLog <type-splice-api-token-transfereventsv2-eventlog-71599_>`_ `EventLog_HoldingsChange <type-splice-api-token-transfereventsv2-eventlogholdingschange-79855_>`_ `EventLog_HoldingsChangeResult <type-splice-api-token-transfereventsv2-eventlogholdingschangeresult-2898_>`_

  **instance** `HasToAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `EventLog <type-splice-api-token-transfereventsv2-eventlog-71599_>`_ `EventLog_HoldingsChange <type-splice-api-token-transfereventsv2-eventlogholdingschange-79855_>`_ `EventLog_HoldingsChangeResult <type-splice-api-token-transfereventsv2-eventlogholdingschangeresult-2898_>`_

.. _type-splice-api-token-transfereventsv2-transferlegside-2800:

**data** `TransferLegSide <type-splice-api-token-transfereventsv2-transferlegside-2800_>`_

  A side of a transfer of holdings between two parties\.

  Used to report transfer events on the affected accounts\.

  .. _constr-splice-api-token-transfereventsv2-transferlegside-86915:

  `TransferLegSide <constr-splice-api-token-transfereventsv2-transferlegside-86915_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - transferLegId
         - `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_
         - An identifier for the transfer leg intended to correlate the sender and receiver sides\.
       * - side
         - `TransferSide <type-splice-api-token-transfereventsv2-transferside-32397_>`_
         - The side of the transfer that this leg refers to\.
       * - otherside
         - Account
         - The account on the other side of the transfer leg; i\.e\., the sender in case of ``side == ReceiverSide``, and the receiver in case of ``side == SenderSide``\.
       * - amount
         - `Decimal <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_
         - The amount to transfer\.
       * - instrumentId
         - `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_
         - The instrument identifier used by the instrument admin\.
       * - meta
         - Metadata
         - Additional metadata about the transfer leg, used for extensibility\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `TransferLegSide <type-splice-api-token-transfereventsv2-transferlegside-2800_>`_

  **instance** `Ord <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-ord-6395>`_ `TransferLegSide <type-splice-api-token-transfereventsv2-transferlegside-2800_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `TransferLegSide <type-splice-api-token-transfereventsv2-transferlegside-2800_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"amount\" `TransferLegSide <type-splice-api-token-transfereventsv2-transferlegside-2800_>`_ `Decimal <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"instrumentId\" `TransferLegSide <type-splice-api-token-transfereventsv2-transferlegside-2800_>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `TransferLegSide <type-splice-api-token-transfereventsv2-transferlegside-2800_>`_ Metadata

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"otherside\" `TransferLegSide <type-splice-api-token-transfereventsv2-transferlegside-2800_>`_ Account

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"side\" `TransferLegSide <type-splice-api-token-transfereventsv2-transferlegside-2800_>`_ `TransferSide <type-splice-api-token-transfereventsv2-transferside-32397_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"transferLegId\" `TransferLegSide <type-splice-api-token-transfereventsv2-transferlegside-2800_>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"transferLegSides\" `EventLog_HoldingsChange <type-splice-api-token-transfereventsv2-eventlogholdingschange-79855_>`_ \[`TransferLegSide <type-splice-api-token-transfereventsv2-transferlegside-2800_>`_\]

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"amount\" `TransferLegSide <type-splice-api-token-transfereventsv2-transferlegside-2800_>`_ `Decimal <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"instrumentId\" `TransferLegSide <type-splice-api-token-transfereventsv2-transferlegside-2800_>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `TransferLegSide <type-splice-api-token-transfereventsv2-transferlegside-2800_>`_ Metadata

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"otherside\" `TransferLegSide <type-splice-api-token-transfereventsv2-transferlegside-2800_>`_ Account

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"side\" `TransferLegSide <type-splice-api-token-transfereventsv2-transferlegside-2800_>`_ `TransferSide <type-splice-api-token-transfereventsv2-transferside-32397_>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"transferLegId\" `TransferLegSide <type-splice-api-token-transfereventsv2-transferlegside-2800_>`_ `Text <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"transferLegSides\" `EventLog_HoldingsChange <type-splice-api-token-transfereventsv2-eventlogholdingschange-79855_>`_ \[`TransferLegSide <type-splice-api-token-transfereventsv2-transferlegside-2800_>`_\]

.. _type-splice-api-token-transfereventsv2-transferside-32397:

**data** `TransferSide <type-splice-api-token-transfereventsv2-transferside-32397_>`_

  A side of a transfer\.

  .. _constr-splice-api-token-transfereventsv2-senderside-1408:

  `SenderSide <constr-splice-api-token-transfereventsv2-senderside-1408_>`_

    The outbound side of a transfer, i\.e\., the sending of assets\.

  .. _constr-splice-api-token-transfereventsv2-receiverside-40022:

  `ReceiverSide <constr-splice-api-token-transfereventsv2-receiverside-40022_>`_

    The inbound direction, i\.e\., the receipt of assets\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `TransferSide <type-splice-api-token-transfereventsv2-transferside-32397_>`_

  **instance** `Ord <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-ord-6395>`_ `TransferSide <type-splice-api-token-transfereventsv2-transferside-32397_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `TransferSide <type-splice-api-token-transfereventsv2-transferside-32397_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"side\" `TransferLegSide <type-splice-api-token-transfereventsv2-transferlegside-2800_>`_ `TransferSide <type-splice-api-token-transfereventsv2-transferside-32397_>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"side\" `TransferLegSide <type-splice-api-token-transfereventsv2-transferlegside-2800_>`_ `TransferSide <type-splice-api-token-transfereventsv2-transferside-32397_>`_

Functions
---------

.. _function-splice-api-token-transfereventsv2-eventlogholdingschangeimpl-8173:

`eventLog_holdingsChangeImpl <function-splice-api-token-transfereventsv2-eventlogholdingschangeimpl-8173_>`_
  \: `EventLog <type-splice-api-token-transfereventsv2-eventlog-71599_>`_ \-\> `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `EventLog <type-splice-api-token-transfereventsv2-eventlog-71599_>`_ \-\> `EventLog_HoldingsChange <type-splice-api-token-transfereventsv2-eventlogholdingschange-79855_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `EventLog_HoldingsChangeResult <type-splice-api-token-transfereventsv2-eventlogholdingschangeresult-2898_>`_
