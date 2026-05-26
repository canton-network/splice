..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - Scan app

        - The following deprecated endpoints have been removed from the public API:

          - ``/v0/top-validators-by-validator-faucets``
          - ``/v0/top-providers-by-app-rewards``
          - ``/v0/top-validators-by-validator-rewards``
          - ``/v0/top-validators-by-purchased-traffic``
          - ``/v0/rewards-collected``
          - ``v0/round-party-totals``
          - ``v0/round-totals``
          - ``v0/aggregated-rounds``


    - Deployment

        - Switch docker base images to https://github.com/canton-network/canton-base-images to reduce attack surface.
