# Build context must be the repo root (monorepo: this service depends on :shared-domain, and
# settings.gradle.kts includes all three modules so Gradle's configuration phase needs every
# module's build.gradle.kts present even though only this one's sources are compiled here).
#   docker build -f ingestion-service/Dockerfile -t knurl-ingestion-service .

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
COPY ingestion-service ingestion-service

# gradlew is committed CRLF/non-executable (repo is edited on Windows): normalize line endings
# and invoke it via `sh` rather than relying on chmod.
RUN sed -i 's/\r$//' gradlew && sh gradlew --no-daemon :ingestion-service:installDist -x test

FROM eclipse-temurin:25-jre AS runtime
RUN useradd --system --create-home --home-dir /app --shell /usr/sbin/nologin knurl
WORKDIR /app
COPY --from=build --chown=knurl:knurl /workspace/ingestion-service/build/install/ingestion-service ./
USER knurl

# No HTTP listener - see Main.kt's architectural-boundary note. Configuration comes entirely from
# env vars (DATABASE_URL, S3_*, INSTAGRAM_*, ADMIN_TOKEN, ...); see README.md's env var reference.
ENTRYPOINT ["./bin/ingestion-service"]
