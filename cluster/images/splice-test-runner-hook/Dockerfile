ARG RUNNER_VERSION=2.335.1
ARG RUNNER_DIGEST=sha256:08c30b0a7105f64bddfc485d2487a22aa03932a791402393352fdf674bda2c29

# Note that we don't currently support arm64 runners, so we build this only for amd64
FROM --platform=$BUILDPLATFORM ghcr.io/actions/actions-runner:${RUNNER_VERSION}@${RUNNER_DIGEST}

# required to get the org.opencontainers.image.base.name label to render correctly
ARG RUNNER_VERSION

LABEL org.opencontainers.image.base.name="ghcr.io/actions/actions-runner:${RUNNER_VERSION}"

COPY target/index.js /home/runner/k8s/index.js
COPY target/LICENSE .
