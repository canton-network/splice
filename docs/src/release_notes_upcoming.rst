..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

release-notes:: Upcoming

    - SV App

        - The deprecated (in 0.8.0) public ``/v0/dso`` endpoint has been removed.
          Use the public ``/v0/dso`` endpoint in the scan app if you need to fetch DSO info without SV operator credentials.

        - Joining SVs now fetch DSO info during onboarding from a scan instance
          (typically the sponsor's) instead of the sponsor SV app's deprecated public
          ``/v0/dso`` endpoint. The scan is configured via the new ``.joinWithKeyOnboarding.sponsorScanUrl`` Helm value.
          SVs who set the ``.joinWithKeyOnboarding`` key config must set it before upgrading.

    - Helm

        - The deprecated `splice-domain` Helm chart has been removed.

    - Scan App

        - Added a new public ``/v0/events/latest-record-time`` endpoint that returns the latest
          record time for which ``/v0/events`` will be able to return events.

    - SV UI

        - The ``AmuletRules_SetConfig`` proposal form can now set ``amuletSwitchOverTimes``.

        - The ``DsoRulesConfig`` proposal form can now set ``svOperationsSwitchOverTimes``.
