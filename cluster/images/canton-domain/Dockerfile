# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

ARG base_version
FROM canton:${base_version}

ARG base_version
LABEL org.opencontainers.image.base.name="canton:${base_version}"

EXPOSE 5007
EXPOSE 5008
EXPOSE 5009
EXPOSE 5061
EXPOSE 5062
EXPOSE 5071
EXPOSE 5072
EXPOSE 10013

COPY app.conf bootstrap.sc /app/

HEALTHCHECK --start-period=10m \
  CMD wget -Y off -q -T 5 -O /dev/null http://localhost:5071/health/ready \
   && wget -Y off -q -T 5 -O /dev/null http://localhost:5072/health/ready || exit 1
