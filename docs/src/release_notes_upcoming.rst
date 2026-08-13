..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

release-notes:: Upcoming

    - Scan app

        - The ``enable-app-activity-record-and-traffic-ingestion`` and
          ``serve-app-activity-records-and-traffic`` configuration options have been removed. App
          activity records and sequencer traffic are now always ingested, and app activity is
          always computed and served on the corresponding HTTP endpoints.

    - SV App

        - The SV app now exposes a ``splice.sv_vote_requests.active`` metric counting the active
          vote requests by their state relative to the SV (``action_needed``, ``in_progress``,
          ``ready_to_close``), allowing SV operators to alert on vote proposals that require
          their vote.

    - Scan & SV App

        - HTTP rate limiting has been extended with a global rate limiter applied across all
          operations, optional per-client-IP rate limiting (enabled by default at the global level),
          and an additional sustained rate limit enforced over a longer window on top of the existing
          per-second burst limit. The client IP is taken from the trusted, non-spoofable
          ``X-Envoy-External-Address`` header set by the Envoy/Istio ingress, falling back to the
          client-controlled ``X-Forwarded-For``/``X-Real-Ip`` headers and finally the remote
          address only for requests that did not pass through the ingress. These can be tuned via
          the following ``rate-limiting`` config keys:

          .. warning::

             When per-client-IP rate limiting is enabled, operators must ensure that the client IP
             used for rate limiting cannot be spoofed. Either configure
             ``rate-limiting.trusted-client-ip-header`` to a trusted, non-spoofable header set by
             your ingress/proxy (e.g. ``x-envoy-external-address`` for Istio deployments), or ensure
             that the ``X-Forwarded-For`` header contains the actual client IP as its first value
             and cannot be spoofed by clients. Otherwise, clients may bypass per-client-IP limits or
             cause other clients to be throttled by forging these headers.


          - ``rate-limiting.trusted-client-ip-header``: name of the trusted proxy header carrying
            the real client IP (default ``x-envoy-external-address``). Override it for non-Istio
            deployments, or set it to an empty string to disable trusting a proxy header and only
            rely on ``X-Forwarded-For``/``X-Real-Ip``/the remote address.
          - ``rate-limiting.default``: overall per-operation limiter used when there is no
            operation-specific override. Its embedded ``per-client-ip`` limiter (disabled by
            default) applies per-client-IP limiting on top of the per-operation limiter.
          - ``rate-limiting.rate-limiters.<operation>``: per-operation overrides of
            ``rate-limiting.default``. Set ``rate-limiting.rate-limiters.<operation>.per-client-ip``
            to enable per-client-IP limiting for a specific operation.
          - ``rate-limiting.global``: overall limiter applied globally across all operations. Its
            embedded ``rate-limiting.global.per-client-ip`` limiter applies per-client-IP limiting
            globally (enabled by default).
          - ``sustained-rate-per-second`` / ``sustained-window-seconds`` on any of the above limiter
            configs: the sustained limit and the window (default 60s) over which it is enforced.

        - Default rate limits have been adjusted:

          - Scan app: ``rate-limiting.default.rate-per-second`` lowered from 200 to 100 with a new
            ``rate-limiting.default.sustained-rate-per-second`` of 50; new
            ``rate-limiting.global`` (400 burst / 200 sustained) with an embedded
            ``rate-limiting.global.per-client-ip`` (100 burst / 50 sustained) limiter.
          - SV app: ``rate-limiting.default.rate-per-second`` lowered from 200 to 20 with a new
            ``rate-limiting.default.sustained-rate-per-second`` of 10; new
            ``rate-limiting.global`` (100 burst / 50 sustained) with an embedded
            ``rate-limiting.global.per-client-ip`` (20 burst / 10 sustained) limiter.
