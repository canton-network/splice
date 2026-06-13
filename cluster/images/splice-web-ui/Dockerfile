# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

FROM nginxinc/nginx-unprivileged:1.31.1-alpine-slim@sha256:3123b0a1300f3d7c641855d07db6492a5157694fd8291e84fc486403b1e80dae

USER root

RUN apk add --no-cache tini bash

USER nginx

COPY default.conf /etc/nginx/conf.d/

ENTRYPOINT ["/sbin/tini", "--", "/custom-docker-entrypoint.sh"]
HEALTHCHECK CMD nc -z 127.0.0.1 8080 || exit 1
