# syntax=docker/dockerfile:1
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /workspace
COPY --chown=gradle:gradle server/ server/
COPY --chown=gradle:gradle desktop/src/main/kotlin/ desktop/src/main/kotlin/
COPY --chown=gradle:gradle desktop/src/main/resources/icon.png desktop/src/main/resources/icon.png
COPY --chown=gradle:gradle desktop/src/test/kotlin/ desktop/src/test/kotlin/
USER gradle
RUN gradle -p server --no-daemon -Dorg.gradle.jvmargs=-Xmx2g test installDist

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 1000 yunx && useradd --uid 1000 --gid yunx --create-home yunx \
    && mkdir /data /downloads && chown yunx:yunx /data /downloads
COPY --from=build --chown=yunx:yunx /workspace/server/build/install/yunx-server /opt/yunx
ENV YUNX_DESKTOP_DATA_DIR=/data YUNX_DOWNLOAD_DIR=/downloads YUNX_PORT=8080
USER yunx
WORKDIR /opt/yunx
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/health || exit 1
ENTRYPOINT ["/opt/yunx/bin/yunx-server"]
