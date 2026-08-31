..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

release-notes:: Upcoming

    - SV App

        - The public ``/v0/dso`` endpoint is deprecated and will be removed in 0.9.0
          (see also the release notes for 0.5.5 for the original deprecation notice).
          Use the public ``/v0/dso`` endpoint in the scan app if you need to fetch DSO info
          without SV operator credentials.
          A new ``/v1/dso`` endpoint has been added that returns the same response as ``/v0/dso``
          but requires authorization as SV operator.

        - Joining SVs now fetch DSO info during onboarding from a scan instance
          (typically the sponsor's) instead of the sponsor SV app's deprecated public
          ``/v0/dso`` endpoint. The scan is configured via the new ``.joinWithKeyOnboarding.sponsorScanUrl`` Helm value.
          SVs who set the ``.joinWithKeyOnboarding`` key config must set it before upgrading.
