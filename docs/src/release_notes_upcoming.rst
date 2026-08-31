..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

release-notes:: Upcoming

    - Deployment

        - All Splice Helm charts now set ``automountServiceAccountToken: false`` on the pods they
          deploy. Splice components do not use the Kubernetes API, so pods no longer receive an
          API-server credential by default; this reduces the impact of a compromised pod in
          clusters where permissions are bound to the namespace's ``default`` service account.
          If your deployment relies on the mounted token, for example through a custom service
          account set via ``serviceAccountName``, you can restore the previous behavior by setting
          the new ``automountServiceAccountToken`` Helm value to ``true``.

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

    - CometBFT

        - Increased default resources of watchdog and made it only query for metrics it needs.
