# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

FROM europe-docker.pkg.dev/da-images/public/docker/da-base-image:full-1.0.8@sha256:18dc65a7e20b9289d817cab3c718bd9160a234aae1b56d9128404b2ad4ff51e5

WORKDIR /app

# move tarball to a static name
ADD target/splice-node.tar.gz .

COPY target/storage.conf target/monitoring.conf target/parameters.conf target/entrypoint.sh target/bootstrap-entrypoint.sc target/tools.sh target/logback.xml target/LICENSE /app/

USER root
RUN mkdir -p /app/splice-node/docs/html/cn-release-bundles

RUN ln -s splice-node/bin/splice-node splice-image-bin

RUN chown nonroot:nonroot splice-image-bin
USER nonroot

# point entrypoint to the splice-node executable
ENTRYPOINT ["/usr/bin/tini", "--", "/app/entrypoint.sh"]
