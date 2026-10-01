ARG RUNNER_VERSION=2.337.0
ARG RUNNER_DIGEST=sha256:e5496277be5d09bc968b3d64911b74e219ac4a3f2edce956a3ecf9271bea1ef4

# Note that we don't currently support arm64 runners, so we build this only for amd64
FROM --platform=$BUILDPLATFORM ghcr.io/actions/actions-runner:${RUNNER_VERSION}@${RUNNER_DIGEST}

# required to get the org.opencontainers.image.base.name label to render correctly
ARG RUNNER_VERSION

LABEL org.opencontainers.image.base.name="ghcr.io/actions/actions-runner:${RUNNER_VERSION}"

COPY target/index.js /home/runner/k8s/index.js
COPY target/LICENSE .
