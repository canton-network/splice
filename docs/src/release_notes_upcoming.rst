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

    - CantonBft

         - Increase the default segment length by 4x to reduce performance impact from epoch switches.

    - Validator App

        - Added a sharing-automation option to each party's reward-sharing config.
          When set to external, an off-node automation owns reward-coupon beneficiary assignment:
          the validator neither mints unassigned reward coupons nor runs built-in sharing for that party.
          Defaults to built-in, preserving existing behavior.
