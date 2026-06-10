# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

FROM nginxinc/nginx-unprivileged-alpine-slim:1.30.0

USER root

RUN apk add --no-cache tini

USER nginx

COPY default.conf /etc/nginx/conf.d/

ENTRYPOINT ["/usr/bin/tini", "--", "/custom-docker-entrypoint.sh"]
HEALTHCHECK CMD service nginx status || exit 1
