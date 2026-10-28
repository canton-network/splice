# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# 1.30.6-alpine-slim@sha256:5d8bb9437d74a600ec5dbf90c035451c724dc33eac8d3a71078f6f8612c345f6
FROM nginxinc/nginx-unprivileged@sha256:5d8bb9437d74a600ec5dbf90c035451c724dc33eac8d3a71078f6f8612c345f6

USER root

RUN apk add --no-cache tini bash

USER nginx

COPY default.conf /etc/nginx/conf.d/

ENTRYPOINT ["/sbin/tini", "--", "/custom-docker-entrypoint.sh"]
HEALTHCHECK CMD nc -z 127.0.0.1 8080 || exit 1
