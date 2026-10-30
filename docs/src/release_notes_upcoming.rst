..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

  - LocalNet

    - **Behavior change** All host ports published by LocalNet (Postgres, the participant Ledger, Admin and JSON APIs,
      the validator admin APIs and the Swagger UI) are now bound to `127.0.0.1` instead of all interfaces.
      These services use default credentials or no authentication, so they must not be reachable from the network.
      To expose them on another interface, set `HOST_BIND_IP` (as already supported for the web UIs), for example `HOST_BIND_IP=0.0.0.0`.

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


  - Docker

    - Upgrade base image to 1.0.18

    - The nginx containers of the Docker Compose deployments (validator, sv and localnet) now use the ``nginx/nginx-unprivileged`` image.
      The nginx process runs as a non-root user and listens on container port 8080 instead of 80.
      The published host ports are unchanged. If you use a custom ``nginx.conf``, update its ``listen`` ports to 8080
      and set ``pid /tmp/nginx.pid;`` so the unprivileged user can write the pid file.

  - Participant

    - The `splice-participant` Helm chart has two new values to harden the participant: `enableKeyDownload` and `enableRepairCommands`.
      Both default to `true`, so the behavior is unchanged. Splice 0.10.0 will change both defaults to `false`.

      - `enableKeyDownload: false` disallows downloading the participant's private keys via its admin API.
        For participants that do not use KMS, this breaks participant identities dumps, including periodic backups
        (`participantIdentitiesDumpPeriodicBackup`), the validator's `/v0/admin/participant/identities` endpoint, and SV identities dumps.
        Participants that use KMS are not affected, as their identities dumps do not include private keys.
      - `enableRepairCommands: false` disables the participant's repair service (`canton.features.enable-repair-commands`).
        **SVs must keep it enabled**, as Scan's `/v0/acs/{party}` endpoint, which validators use to restore from an identities dump, exports the ACS through it.
        Validators only need it while restoring from an identities dump (`migrateValidatorParty`) or while running repair commands such as `participant.repair.import_acs`.

  - Deployment

    - Added the `splice-topology-key-monitor` Helm chart, which logs a warning for every topology transaction that is signed by any key configured in the chart.

  - Validator

    - **Behavior change** When auth is disabled, setting `allowedOrigins` in the `splice-validator` Helm chart is mandatory.
      The Docker Compose validator deployment sets it automatically.
