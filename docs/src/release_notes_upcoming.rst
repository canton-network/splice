..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - SV App

        - The SV app now reconciles the ``setBalanceRequestSubmissionWindowSize`` traffic control parameter of the global synchronizer against a new SV app config value ``set-balance-request-submission-window-size``, which defaults to Canton's current default of 2 minutes.
          This parameter defines the time window used to compute the max sequencing time of traffic purchase (top-up) requests.
          Canton lowered its default from 4 minutes to 2 minutes (see the `Canton 3.5.1 release notes <https://docs.canton.network/global-synchronizer/release-notes/canton-releases/3-5-1#minor-breaking-changes>`_).
          Networks bootstrapped on an older version (DevNet, TestNet, MainNet) still use the old value for this parameter.
          By upgrading to this version, SVs agree to change this parameter to 2 minutes (unless they override the new SV app config value).
          The change takes effect once a sufficient number of SVs have upgraded.

