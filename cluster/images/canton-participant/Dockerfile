# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

ARG canton_version
ARG image_sha256

FROM europe-docker.pkg.dev/da-images/public-all/docker/canton-participant:${canton_version}@${image_sha256}

EXPOSE 5071

COPY additional-config.conf /app/
COPY target/logback.xml /app/
COPY R__repeatable-migration.sql /app/repeatable-migrations/
USER root
RUN chown -R nonroot:nonroot /app/additional-config.conf /app/repeatable-migrations/R__repeatable-migration.sql
USER nonroot

# Overrides the grpc-health-probe based HEALTHCHECK of the base image.
# Requires the HTTP health server on port 5071, which deployments enable via ADDITIONAL_CONFIG_HTTP_HEALTH_SERVER.
# -Y off: busybox wget ignores no_proxy, so a proxy injected via the environment would break the probe.
HEALTHCHECK --start-period=10m \
  CMD wget -Y off -q -T 5 -O /dev/null http://localhost:5071/health/ready || exit 1
