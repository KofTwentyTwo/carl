#
# Copyright (C) 2026 KofTwentyTwo
#

# Package the native distribution that passed application conformance and release checks.
# Supply a reviewed immutable digest through RUNTIME_IMAGE when qualifying a release.
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre@sha256:d7051a45dd955e4d5d1db4d3f4269fe13d1c6dff8cc6b7ef89fc8577b96c1982
FROM ${RUNTIME_IMAGE}
# CVE-2026-84782: the pinned Temurin base predates Ubuntu's OpenSSL fix.
# Upgrade only the affected packages and fail closed below the reviewed fixed version.
RUN apt-get update \
    && apt-get install -y --no-install-recommends --only-upgrade \
       libssl3t64 openssl openssl-provider-legacy \
    && for package in libssl3t64 openssl openssl-provider-legacy; do \
         dpkg --compare-versions "$(dpkg-query -W -f='${Version}' "$package")" \
           ge '3.5.5-1ubuntu3.6' || exit 1; \
       done \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --chown=10001:10001 target/agent/ /app/
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
USER 10001:10001
EXPOSE 8090
ENTRYPOINT ["sh", "/app/bin/agent"]
