# Build context must be the repo root (monorepo: this service depends on :shared-domain, and
# settings.gradle.kts includes all three modules so Gradle's configuration phase needs every
# module's build.gradle.kts present even though only this one's sources are compiled here).
#   docker build -f presentation-service/Dockerfile -t knurl-presentation-service .

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Wrapper + build scripts first so dependency resolution is cached across source-only changes.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY shared-domain/build.gradle.kts shared-domain/build.gradle.kts
COPY ingestion-service/build.gradle.kts ingestion-service/build.gradle.kts
COPY presentation-service/build.gradle.kts presentation-service/build.gradle.kts

COPY shared-domain shared-domain
COPY presentation-service presentation-service

# gradlew is committed CRLF/non-executable (repo is edited on Windows): normalize line endings
# and invoke it via `sh` rather than relying on chmod.
RUN sed -i 's/\r$//' gradlew && sh gradlew --no-daemon :presentation-service:installDist -x test

FROM eclipse-temurin:25-jre AS runtime
RUN useradd --system --create-home --home-dir /app --shell /usr/sbin/nologin knurl
WORKDIR /app
COPY --from=build --chown=knurl:knurl /workspace/presentation-service/build/install/presentation-service ./
USER knurl

# Default from PresentationConfig; override with PORT if the platform assigns one.
EXPOSE 8080
ENTRYPOINT ["./bin/presentation-service"]
