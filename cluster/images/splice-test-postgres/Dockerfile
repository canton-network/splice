# DEPRECATED: This image is no longer used in CI. Wallclock tests now use the
# official postgres:17 image directly. Kept until splice-postgres chart EOL (2026-11-12).
# See https://github.com/canton-network/splice/issues/1129
FROM postgres:14.18@sha256:c0aab7962b283cf24a0defa5d0d59777f5045a7be59905f21ba81a20b1a110c9
LABEL org.opencontainers.image.base.name="postgres:14.18"

COPY cmd.sh /usr/local/bin/
COPY target/LICENSE .

CMD ["cmd.sh"]
