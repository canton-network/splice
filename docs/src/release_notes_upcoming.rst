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

        - Verdicts returned by the ``/v0/events`` and ``/v0/events/{update_id}`` endpoints now
          include an optional ``round_number`` field.The value of this field may be ``null``,
          or differ among SVs (``null`` value and a valid round), for a brief period till
          this release is adopted by all SVs.

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
          client-controlled ``X-Forwarded-For``/ ``X-Real-Ip`` headers and finally the remote
          address only for requests that did not pass through the ingress. These can be tuned via
          the ``rate-limiting`` config keys.

          .. warning::

             When per-client-IP rate limiting is enabled, SV operators must ensure that the client IP
             used for rate limiting cannot be spoofed. Either configure
             ``rate-limiting.trusted-client-ip-header`` to a trusted, non-spoofable header set by
             your ingress/proxy (e.g. ``x-envoy-external-address`` for Istio deployments), or ensure
             that the ``X-Forwarded-For`` header contains the actual client IP as its first value
             and cannot be spoofed by clients. Otherwise, clients may bypass per-client-IP limits or
             cause other clients to be throttled by forging these headers.

          The fallback to the client-controlled ``X-Forwarded-For``/ ``X-Real-Ip`` headers can be
          disabled by setting ``rate-limiting.enable-client-provided-ip-headers`` to ``false``.
          If no IP can be extracted no per IP rate limit is enforced.

        - Default rate limits have been adjusted:

          - Scan app: the per-operation burst limit has been lowered from 200 to 100 requests per
            second, with a new sustained limit of 50 requests per second. A new global limiter has
            also been added, allowing 400 requests per second burst / 200 sustained across all
            operations combined, with an embedded per-client-IP limiter allowing 100 requests per
            second burst / 50 sustained.
          - SV app: the per-operation burst limit has been lowered from 200 to 20 requests per
            second, with a new sustained limit of 10 requests per second. A new global limiter has
            also been added, allowing 100 requests per second burst / 50 sustained across all
            operations combined, with an embedded per-client-IP limiter allowing 20 requests per
            second burst / 10 sustained.

    - CometBFT

        - Added a watchdog to restart cometbft when we detect that it
          is replaying messages. You must set
          ``watchdog.sequencerMetricsUrl: http://global-domain-SERIAL_ID-sequencer:10013/metrics`` and
          ``watchdog.mediatorMetricsUrl: http://global-domain-SERIAL_ID-mediator:10013/metrics`` in the
          cometbft helm values. If needed, the watchdog can be disabled through ``watchdog.enabled: false``.


        - Bump the default ``deduplicationCacheSize`` to ``1000000``.
