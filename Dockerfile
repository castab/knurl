# Build context is the repository root. No development configuration is copied into the image.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY shared-domain/build.gradle.kts shared-domain/build.gradle.kts
COPY knurl-app/build.gradle.kts knurl-app/build.gradle.kts
COPY shared-domain/src shared-domain/src
COPY knurl-app/src knurl-app/src
RUN --mount=type=cache,target=/root/.gradle sed -i 's/\r$//' gradlew && sh gradlew --no-daemon :knurl-app:installDist -x test

FROM eclipse-temurin:25-jre AS runtime
RUN apt-get update \
    && apt-get install --no-install-recommends --yes curl ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --home-dir /app --shell /usr/sbin/nologin knurl
WORKDIR /app
COPY --from=build --chown=knurl:knurl /workspace/knurl-app/build/install/knurl-app ./
USER knurl
ENV JAVA_TOOL_OPTIONS="-Xmx512m"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl --fail --silent --show-error --output /dev/null "http://127.0.0.1:${PORT:-8080}/health" || exit 1
ENTRYPOINT ["./bin/knurl-app"]
