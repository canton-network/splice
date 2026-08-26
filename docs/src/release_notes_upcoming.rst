..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

release-notes:: Upcoming

    - Scan app

        - The member traffic-status response now includes ``base_traffic_remainder`` in
          ``traffic_status.actual``, exposing the member's current replenishing base
          traffic alongside its extra-traffic counters.

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

    - SV app

        - The SV app OpenAPI specification now annotates endpoints
          (``x-jvm-package: sv_public``) with an ``x-external-audience`` extension, which is one of
          ``validators`` (endpoints that validator operators need to reach), ``svs``
          (endpoints that only other SVs need to reach) or ``none`` (endpoints that do not
          need to be reachable from outside of the SV node's own deployment, e.g. the CometBFT
          endpoints). SV operators can use this
          annotation to restrict the external exposure of their SV app: only the endpoints of a
          given audience need to be reachable from the corresponding networks, and endpoints with
          an audience of ``none``, as well as endpoints without an ``x-external-audience``, do not
          need to be exposed to external traffic at all.
          Note that endpoints currently marked for exposure to validators will be phased out in the foreseeable future,
          and replaced by a new limited number of endpoints which should be available only on DevNet.
