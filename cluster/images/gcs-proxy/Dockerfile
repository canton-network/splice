# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

FROM --platform=$BUILDPLATFORM debian:trixie-slim@sha256:62bd423aeee912639ca8ee681c2b762cb3e25137e0a92b20be455898a9f034ad

ARG base_version
LABEL org.opencontainers.image.base.name="debian:trixie-slim"

WORKDIR /tmp
ENV GCSPROXY_VERSION=0.3.1

RUN apt-get update \
    && apt-get install --no-install-suggests --no-install-recommends --yes ca-certificates wget \
    && wget https://github.com/daichirata/gcsproxy/releases/download/v${GCSPROXY_VERSION}/gcsproxy-${GCSPROXY_VERSION}-linux-amd64.tar.gz \
    && tar zxf gcsproxy-${GCSPROXY_VERSION}-linux-amd64.tar.gz \
    && cp ./gcsproxy-${GCSPROXY_VERSION}-linux-amd64/gcsproxy /

ENV GOOGLE_CLOUD_PROJECT=da-cn-shared

COPY target/LICENSE .

CMD ["/gcsproxy", "-v", "-b", "0.0.0.0:8080" ]
