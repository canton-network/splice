# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

FROM nginxinc/nginx-unprivileged:1.31.1-alpine-slim@sha256:6616de6eaa82bc2ee3541fa287a8fca7dc7271e6374e9402014dbd13f4a980ae

USER root

RUN apk add --no-cache tini bash

USER nginx

COPY default.conf /etc/nginx/conf.d/

ENTRYPOINT ["/sbin/tini", "--", "/custom-docker-entrypoint.sh"]
HEALTHCHECK CMD nc -z 127.0.0.1 8080 || exit 1
