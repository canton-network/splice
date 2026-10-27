..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

  - Health checks of Canton nodes

    - **Behavior change** The health checks of all Canton nodes (participant, sequencer and mediator) now use Canton's HTTP health endpoints
      (`/health/live` and `/health/ready`) instead of the gRPC health service. This affects:

      - Helm charts: the liveness and readiness probes of `splice-participant` and `splice-global-domain`.
        The charts enable the HTTP health endpoints on port 5071 through the `ADDITIONAL_CONFIG_HTTP_HEALTH_SERVER` environment variable.
        Set `httpHealthProbes: false` to restore the previous gRPC probes.
      - Docker images: the `HEALTHCHECK` of `canton-participant` and `canton-domain`.
        The `canton-participant` healthcheck expects the HTTP health endpoints on port 5071, which the Docker Compose deployments enable.
        If you run the `canton-participant` image with your own deployment, set
        `ADDITIONAL_CONFIG_HTTP_HEALTH_SERVER=canton.participants.participant.monitoring.http-health-server.port=5071`.
        The `canton-domain` image serves the HTTP health endpoints on ports 5071 (mediator) and 5072 (sequencer).
        The gRPC health service on port 5061 (5061 and 5062 for `canton-domain`) remains available.
      - Docker Compose deployments: the participant in the validator and SV deployments, the synchronizer node in the SV deployment,
        and the `canton` container in LocalNet.

      Liveness is unchanged. Readiness, however, now only reports ready once the node has completed its database migrations and initialized its storage.
      Previously, nodes reported ready while database migrations were still running, even though they could not serve any requests yet.
      After an upgrade with long-running database migrations, nodes therefore stay not ready (Kubernetes) or unhealthy (Docker) for the duration of the migrations.
      If you use tooling that waits for readiness, you may need to increase its timeouts, for example:

      - `helm install` and `helm upgrade` with `--wait` (default `--timeout` is 5 minutes)
      - the `progressDeadlineSeconds` of Kubernetes Deployments (default is 10 minutes), as observed by `kubectl rollout status` or GitOps tools
      - `docker compose up --wait` (including the `-w` flag of the `start.sh` scripts of the Docker Compose deployments),
        and services that depend on Canton containers with `condition: service_healthy`.
        The Docker images use a `HEALTHCHECK` start period of 10 minutes, so containers report unhealthy if migrations take longer,
        and report healthy again once the migrations are done.

