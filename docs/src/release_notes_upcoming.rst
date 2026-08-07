..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - Wallet app

        - Duplicate wallet operations submitted with the same command id (e.g. tap, transfer,
          token standard transfers) now return the original result idempotently instead of HTTP 409.
          This aligns with standard idempotency-key semantics: a second request with a previously
          accepted command id receives a 200 response with the same result as the first.
          Concurrent duplicates, where no submission has completed yet, are still rejected.

        - ``TransferPreapprovalProposal`` s are now accepted if there is an existing one but it has expired.

    - CantonBft

         - Increase the default segment length by 4x to reduce performance impact from epoch switches.

    - Validator App

        - Added a ``type`` parameter to validator config's ``reward-sharing-config-by-party`` option.

          When this is set to ``external``, it indicates that the assignment of reward coupons to beneficiaries is being managed by a process external to the validator app, and thus the validator app's automation does not assign or mint the unassigned coupons.

          The ``type`` defaults to ``built-in`` preserving the existing behavior where the validator app will either mint the unassigned rewards coupons, or assign them to beneficiaries if configured.

          See the reward-sharing documentation for details:
          https://docs.canton.network/global-synchronizer/splice-fundamentals/reward-sharing#reward-sharing

          Example enabling external sharing automation for a party::

              canton.validator-apps.<validator>.reward-sharing-config-by-party = {
                "<party-id>" = {
                  type = "external"
                  # Optionally batch-size may be specified to configure the maximum number of coupons to mint in a single transaction
                  batch-size = 80
                }
              }
