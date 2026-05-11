# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

ARG canton_version
ARG image_sha256

# TODO(DACH-NY/cn-test-failures#8301) fix upstream instead
FROM ghcr.io/grpc-ecosystem/grpc-health-probe:v0.4.48@sha256:b615f8b80a6796490b91bfe0f7f4d59cf73767d4921968495cb8b4024090e151 AS grpc-health-probe-multi-arch

FROM europe-docker.pkg.dev/da-images/public-all/docker/canton-participant:${canton_version}@${image_sha256}

# TODO(DACH-NY/cn-test-failures#8301) fix upstream instead / find solution that allows fingerprint pinning
COPY --from=grpc-health-probe-multi-arch /ko-app/grpc-health-probe /bin/grpc-health-probe

COPY additional-config.conf /app/
COPY target/logback.xml /app/
COPY R__repeatable-migration.sql /app/repeatable-migrations/
USER root
RUN chown -R nonroot:nonroot /app/additional-config.conf /app/repeatable-migrations/R__repeatable-migration.sql
USER nonroot
