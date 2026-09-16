..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _module-splice-api-rewardassignmentv1-30571:

Splice.Api.RewardAssignmentV1
=============================

An API for app providers and other service providers to assign reward
coupons for their activity to their beneficiaries\.

Interfaces
----------

.. _type-splice-api-rewardassignmentv1-rewardcoupon-91850:

**interface** `RewardCoupon <type-splice-api-rewardassignmentv1-rewardcoupon-91850_>`_

  A coupon representing the right to mint a certain amount of rewards\.

  **viewtype** `RewardCouponView <type-splice-api-rewardassignmentv1-rewardcouponview-77665_>`_

  + **Choice** Archive

    Controller\: Signatories of implementing template

    Returns\: ()

    (no fields)

  + .. _type-splice-api-rewardassignmentv1-rewardcouponassignbeneficiaries-91237:

    **Choice** `RewardCoupon_AssignBeneficiaries <type-splice-api-rewardassignmentv1-rewardcouponassignbeneficiaries-91237_>`_

    Assign ultimate beneficiaries to the coupon\. Useful for apps
    where the party that earns the minting right (the provider) is just an
    operational party and the actual beneficiaries are different parties\.

    The coupon MUST NOT already have an assigned beneficiary\.

    Controller\: (DA\.Internal\.Record\.getField @\"provider\" (view this))

    Returns\: `RewardCoupon_AssignBeneficiariesResult <type-splice-api-rewardassignmentv1-rewardcouponassignbeneficiariesresult-6056_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - additionalCoupons
         - \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `RewardCoupon <type-splice-api-rewardassignmentv1-rewardcoupon-91850_>`_\]
         - Additional coupons of the provider to share in the same transaction\.  These MUST NOT already have any assigned beneficiary\.
       * - newBeneficiaries
         - \[`RewardBeneficiary <type-splice-api-rewardassignmentv1-rewardbeneficiary-10184_>`_\]
         - The new beneficiaries to whom to assign part of the minting amount of the coupon\. The percentages MUST be between 0\.0 and 1\.0, add up to 1\.0, and there MUST NOT be duplicate beneficiaries\.  There MUST be at most ``(view this).maxNumNewBeneficiaries`` new beneficiaries in the list\. The purpose of this limit is to ensure that traffic cost of creating coupons guards the overhead of tracking the created coupons for the DSO party\.
       * - extraArgs
         - ExtraArgs
         - Extra arguments for extensibility\. Set to empty, unless needed for specific implementations\.

  + **Method rewardCoupon\_assignBeneficiariesImpl \:** `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `RewardCoupon <type-splice-api-rewardassignmentv1-rewardcoupon-91850_>`_ \-\> `RewardCoupon_AssignBeneficiaries <type-splice-api-rewardassignmentv1-rewardcouponassignbeneficiaries-91237_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `RewardCoupon_AssignBeneficiariesResult <type-splice-api-rewardassignmentv1-rewardcouponassignbeneficiariesresult-6056_>`_

Data Types
----------

.. _type-splice-api-rewardassignmentv1-rewardbeneficiary-10184:

**data** `RewardBeneficiary <type-splice-api-rewardassignmentv1-rewardbeneficiary-10184_>`_

  Specification of a beneficiary of rewards\.

  .. _constr-splice-api-rewardassignmentv1-rewardbeneficiary-42059:

  `RewardBeneficiary <constr-splice-api-rewardassignmentv1-rewardbeneficiary-42059_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - beneficiary
         - `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The party that is granted the right to mint amulet for this activity\.
       * - percentage
         - `Decimal <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_
         - Percentage (specified as a number between 0\.0 and 1\.0) of the reward minting allowance to assign to this beneficiary \.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `RewardBeneficiary <type-splice-api-rewardassignmentv1-rewardbeneficiary-10184_>`_

  **instance** `Ord <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-ord-6395>`_ `RewardBeneficiary <type-splice-api-rewardassignmentv1-rewardbeneficiary-10184_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `RewardBeneficiary <type-splice-api-rewardassignmentv1-rewardbeneficiary-10184_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"beneficiary\" `RewardBeneficiary <type-splice-api-rewardassignmentv1-rewardbeneficiary-10184_>`_ `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"newBeneficiaries\" `RewardCoupon_AssignBeneficiaries <type-splice-api-rewardassignmentv1-rewardcouponassignbeneficiaries-91237_>`_ \[`RewardBeneficiary <type-splice-api-rewardassignmentv1-rewardbeneficiary-10184_>`_\]

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"percentage\" `RewardBeneficiary <type-splice-api-rewardassignmentv1-rewardbeneficiary-10184_>`_ `Decimal <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"beneficiary\" `RewardBeneficiary <type-splice-api-rewardassignmentv1-rewardbeneficiary-10184_>`_ `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"newBeneficiaries\" `RewardCoupon_AssignBeneficiaries <type-splice-api-rewardassignmentv1-rewardcouponassignbeneficiaries-91237_>`_ \[`RewardBeneficiary <type-splice-api-rewardassignmentv1-rewardbeneficiary-10184_>`_\]

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"percentage\" `RewardBeneficiary <type-splice-api-rewardassignmentv1-rewardbeneficiary-10184_>`_ `Decimal <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_

.. _type-splice-api-rewardassignmentv1-rewardcouponview-77665:

**data** `RewardCouponView <type-splice-api-rewardassignmentv1-rewardcouponview-77665_>`_

  View on a coupon representing the right to mint a certain amount of rewards\.

  .. _constr-splice-api-rewardassignmentv1-rewardcouponview-34344:

  `RewardCouponView <constr-splice-api-rewardassignmentv1-rewardcouponview-34344_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - dso
         - `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The DSO party\.
       * - provider
         - `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The party that provided the service for whose activity the minting right was granted\.
       * - beneficiary
         - `Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The beneficiary that can mint the amount specified in the coupon\. If not set assignment via this interface is possible by the provider\.
       * - amount
         - `Decimal <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_
         - Amulet amount that can be minted with this coupon\.
       * - expiresAt
         - `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_
         - Expiration time of the coupon\. The minting right granted by the coupon can only be exercised before this time\.
       * - maxNumNewBeneficiaries
         - `Int <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-int-37261>`_
         - The maximum number of new beneficiaries that can be assigned to the coupon in a single assignment\.
       * - meta
         - Metadata
         - Metadata associated with this coupon\. Provided for extensibility\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `RewardCouponView <type-splice-api-rewardassignmentv1-rewardcouponview-77665_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `RewardCouponView <type-splice-api-rewardassignmentv1-rewardcouponview-77665_>`_

  **instance** `HasFromAnyView <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Internal-Interface-AnyView.html#class-da-internal-interface-anyview-hasfromanyview-30108>`_ `RewardCoupon <type-splice-api-rewardassignmentv1-rewardcoupon-91850_>`_ `RewardCouponView <type-splice-api-rewardassignmentv1-rewardcouponview-77665_>`_

  **instance** `HasInterfaceView <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-interface-hasinterfaceview-4492>`_ `RewardCoupon <type-splice-api-rewardassignmentv1-rewardcoupon-91850_>`_ `RewardCouponView <type-splice-api-rewardassignmentv1-rewardcouponview-77665_>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"amount\" `RewardCouponView <type-splice-api-rewardassignmentv1-rewardcouponview-77665_>`_ `Decimal <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"beneficiary\" `RewardCouponView <type-splice-api-rewardassignmentv1-rewardcouponview-77665_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_)

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"dso\" `RewardCouponView <type-splice-api-rewardassignmentv1-rewardcouponview-77665_>`_ `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"expiresAt\" `RewardCouponView <type-splice-api-rewardassignmentv1-rewardcouponview-77665_>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"maxNumNewBeneficiaries\" `RewardCouponView <type-splice-api-rewardassignmentv1-rewardcouponview-77665_>`_ `Int <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-int-37261>`_

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `RewardCouponView <type-splice-api-rewardassignmentv1-rewardcouponview-77665_>`_ Metadata

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"provider\" `RewardCouponView <type-splice-api-rewardassignmentv1-rewardcouponview-77665_>`_ `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"amount\" `RewardCouponView <type-splice-api-rewardassignmentv1-rewardcouponview-77665_>`_ `Decimal <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"beneficiary\" `RewardCouponView <type-splice-api-rewardassignmentv1-rewardcouponview-77665_>`_ (`Optional <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_)

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"dso\" `RewardCouponView <type-splice-api-rewardassignmentv1-rewardcouponview-77665_>`_ `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"expiresAt\" `RewardCouponView <type-splice-api-rewardassignmentv1-rewardcouponview-77665_>`_ `Time <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"maxNumNewBeneficiaries\" `RewardCouponView <type-splice-api-rewardassignmentv1-rewardcouponview-77665_>`_ `Int <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-ghc-types-int-37261>`_

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `RewardCouponView <type-splice-api-rewardassignmentv1-rewardcouponview-77665_>`_ Metadata

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"provider\" `RewardCouponView <type-splice-api-rewardassignmentv1-rewardcouponview-77665_>`_ `Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

.. _type-splice-api-rewardassignmentv1-rewardcouponassignbeneficiariesresult-6056:

**data** `RewardCoupon_AssignBeneficiariesResult <type-splice-api-rewardassignmentv1-rewardcouponassignbeneficiariesresult-6056_>`_

  .. _constr-splice-api-rewardassignmentv1-rewardcouponassignbeneficiariesresult-3545:

  `RewardCoupon_AssignBeneficiariesResult <constr-splice-api-rewardassignmentv1-rewardcouponassignbeneficiariesresult-3545_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - newBeneficiariesCouponCids
         - \[(`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_, \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `RewardCoupon <type-splice-api-rewardassignmentv1-rewardcoupon-91850_>`_\])\]
         - The coupons created for the newly assigned beneficiaries\.

  **instance** `Eq <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `RewardCoupon_AssignBeneficiariesResult <type-splice-api-rewardassignmentv1-rewardcouponassignbeneficiariesresult-6056_>`_

  **instance** `Show <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `RewardCoupon_AssignBeneficiariesResult <type-splice-api-rewardassignmentv1-rewardcouponassignbeneficiariesresult-6056_>`_

  **instance** HasMethod `RewardCoupon <type-splice-api-rewardassignmentv1-rewardcoupon-91850_>`_ \"rewardCoupon\_assignBeneficiariesImpl\" (`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `RewardCoupon <type-splice-api-rewardassignmentv1-rewardcoupon-91850_>`_ \-\> `RewardCoupon_AssignBeneficiaries <type-splice-api-rewardassignmentv1-rewardcouponassignbeneficiaries-91237_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `RewardCoupon_AssignBeneficiariesResult <type-splice-api-rewardassignmentv1-rewardcouponassignbeneficiariesresult-6056_>`_)

  **instance** `GetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"newBeneficiariesCouponCids\" `RewardCoupon_AssignBeneficiariesResult <type-splice-api-rewardassignmentv1-rewardcouponassignbeneficiariesresult-6056_>`_ \[(`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_, \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `RewardCoupon <type-splice-api-rewardassignmentv1-rewardcoupon-91850_>`_\])\]

  **instance** `SetField <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"newBeneficiariesCouponCids\" `RewardCoupon_AssignBeneficiariesResult <type-splice-api-rewardassignmentv1-rewardcouponassignbeneficiariesresult-6056_>`_ \[(`Party <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_, \[`ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `RewardCoupon <type-splice-api-rewardassignmentv1-rewardcoupon-91850_>`_\])\]

  **instance** `HasExercise <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `RewardCoupon <type-splice-api-rewardassignmentv1-rewardcoupon-91850_>`_ `RewardCoupon_AssignBeneficiaries <type-splice-api-rewardassignmentv1-rewardcouponassignbeneficiaries-91237_>`_ `RewardCoupon_AssignBeneficiariesResult <type-splice-api-rewardassignmentv1-rewardcouponassignbeneficiariesresult-6056_>`_

  **instance** `HasExerciseGuarded <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `RewardCoupon <type-splice-api-rewardassignmentv1-rewardcoupon-91850_>`_ `RewardCoupon_AssignBeneficiaries <type-splice-api-rewardassignmentv1-rewardcouponassignbeneficiaries-91237_>`_ `RewardCoupon_AssignBeneficiariesResult <type-splice-api-rewardassignmentv1-rewardcouponassignbeneficiariesresult-6056_>`_

  **instance** `HasFromAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `RewardCoupon <type-splice-api-rewardassignmentv1-rewardcoupon-91850_>`_ `RewardCoupon_AssignBeneficiaries <type-splice-api-rewardassignmentv1-rewardcouponassignbeneficiaries-91237_>`_ `RewardCoupon_AssignBeneficiariesResult <type-splice-api-rewardassignmentv1-rewardcouponassignbeneficiariesresult-6056_>`_

  **instance** `HasToAnyChoice <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `RewardCoupon <type-splice-api-rewardassignmentv1-rewardcoupon-91850_>`_ `RewardCoupon_AssignBeneficiaries <type-splice-api-rewardassignmentv1-rewardcouponassignbeneficiaries-91237_>`_ `RewardCoupon_AssignBeneficiariesResult <type-splice-api-rewardassignmentv1-rewardcouponassignbeneficiariesresult-6056_>`_

Functions
---------

.. _function-splice-api-rewardassignmentv1-rewardcouponassignbeneficiariesimpl-26627:

`rewardCoupon_assignBeneficiariesImpl <function-splice-api-rewardassignmentv1-rewardcouponassignbeneficiariesimpl-26627_>`_
  \: `RewardCoupon <type-splice-api-rewardassignmentv1-rewardcoupon-91850_>`_ \-\> `ContractId <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `RewardCoupon <type-splice-api-rewardassignmentv1-rewardcoupon-91850_>`_ \-\> `RewardCoupon_AssignBeneficiaries <type-splice-api-rewardassignmentv1-rewardcouponassignbeneficiaries-91237_>`_ \-\> `Update <https://docs.digitalasset.com/build/3.4/reference/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `RewardCoupon_AssignBeneficiariesResult <type-splice-api-rewardassignmentv1-rewardcouponassignbeneficiariesresult-6056_>`_
