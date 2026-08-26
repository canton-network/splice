..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

release-notes:: Upcoming

    - Scan & SV App

        - The client IP used for per-client-IP HTTP rate limiting is now extracted based on a
          configurable, ordered list of headers, ``rate-limiting.client-ip-headers``, which defaults
          to ``["x-forwarded-for", "x-real-ip"]``. The first configured header that is present and
          whose value parses as an IP literal is used; for comma separated values (as in
          ``X-Forwarded-For``) the first entry is taken. Configuring an empty list disables the
          extraction, in which case no per-client-IP rate limit is enforced.

          This replaces the ``rate-limiting.trusted-client-ip-header`` and
          ``rate-limiting.enable-client-provided-ip-headers`` options, which have been removed.

    - Docker

        - Updated Docker base image to 1.0.13, which updates gRPC health probe to v0.4.55.
