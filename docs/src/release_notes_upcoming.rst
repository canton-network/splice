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

        - The governance UI no longer stops an SV from casting or changing its vote once a
          proposal's target effective time has passed. Votes are now accepted for as long as the
          vote request is open, matching what the ledger allows. This makes it possible to reject a
          proposal whose action fails to execute and which would otherwise remain in flight
          indefinitely.
          
        - Network banner now always shows. The network name is derived from the scan node's public URL:
          MainNet/TestNet/DevNet/ScratchNet from the cluster subdomain, LocalNet for localhost, and a
          capitalized fallback otherwise (Unknown Network when no scan URL is available).
