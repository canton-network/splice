ARG RUNNER_VERSION=2.336.0
ARG RUNNER_DIGEST=sha256:0cfdcc701ce933c6d243c6b0b2da767366dc9f2e99961d4c3754b0b78084cdda

# Note that we don't currently support arm64 runners, so we build this only for amd64
FROM --platform=$BUILDPLATFORM ghcr.io/actions/actions-runner:${RUNNER_VERSION}@${RUNNER_DIGEST}

# required to get the org.opencontainers.image.base.name label to render correctly
ARG RUNNER_VERSION

LABEL org.opencontainers.image.base.name="ghcr.io/actions/actions-runner:${RUNNER_VERSION}"

COPY target/index.js /home/runner/k8s/index.js
COPY target/LICENSE .
