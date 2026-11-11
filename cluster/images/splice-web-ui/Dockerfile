# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# 1.30.6-alpine-slim@sha256:984a21e29bb10d4c0e122374b97b89a2101823a9331e0f3c6c04bc285ccf447f
FROM nginx/nginx-unprivileged@sha256:984a21e29bb10d4c0e122374b97b89a2101823a9331e0f3c6c04bc285ccf447f

USER root

RUN apk add --no-cache tini bash

USER nginx

COPY default.conf /etc/nginx/conf.d/

ENTRYPOINT ["/sbin/tini", "--", "/custom-docker-entrypoint.sh"]
HEALTHCHECK CMD nc -z 127.0.0.1 8080 || exit 1
