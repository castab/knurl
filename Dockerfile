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

# ffprobe is the only FFmpeg program Knurl runs (video width/height). It is compiled here as a
# fully static binary from a pinned, checksum-verified release instead of installing the
# distribution's ffmpeg package: that package pulls well over a hundred dependencies from mirrors
# that have repeatedly stalled or failed image builds. Static linking is required because this
# stage is musl-based while the runtime image is glibc-based. The build is LGPL-only - never add
# --enable-gpl or --enable-nonfree, which would restrict or forbid redistributing the image - and
# enables only what probing Instagram video needs: the MP4/MOV demuxer and common video codecs.
FROM alpine:3.24 AS ffprobe
ARG FFMPEG_VERSION=8.1.3
ARG FFMPEG_SHA256=7138d28c96d9d3e3af4ee3d8cad72741f8ffb40da90c1112235dea3ecd3178a3
RUN apk add --no-cache build-base curl nasm xz
WORKDIR /src
RUN curl --fail --silent --show-error --location --retry 5 --retry-all-errors \
        --output ffmpeg.tar.xz "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz" \
    && echo "${FFMPEG_SHA256}  ffmpeg.tar.xz" | sha256sum -c - \
    && tar -xJf ffmpeg.tar.xz --strip-components=1 \
    && ./configure \
        --disable-everything --disable-autodetect --disable-doc --disable-debug --disable-network \
        --disable-ffmpeg --disable-ffplay --enable-ffprobe \
        --disable-avdevice --disable-avfilter --disable-swscale --disable-swresample \
        --enable-protocol=pipe,file \
        --enable-demuxer=mov \
        --enable-parser=h264,hevc,vp8,vp9,av1,mpeg4video \
        --enable-decoder=h264,hevc,vp8,vp9,av1,mpeg4 \
        --enable-static --disable-shared --extra-ldflags=-static \
    && make -j"$(nproc)" ffprobe \
    && strip ffprobe \
    && ./ffprobe -version

FROM eclipse-temurin:25-jre AS runtime
# No package installation: the runtime depends only on its base image and the two build stages.
RUN useradd --system --create-home --home-dir /app --shell /usr/sbin/nologin knurl
COPY --from=ffprobe /src/ffprobe /usr/local/bin/ffprobe
WORKDIR /app
COPY --from=build --chown=knurl:knurl /workspace/knurl-app/build/install/knurl-app ./
USER knurl
ENV JAVA_TOOL_OPTIONS="-Xmx512m"
EXPOSE 8080
# bash's built-in /dev/tcp keeps the probe free of curl/wget, neither of which the base image guarantees.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${PORT:-8080} && printf 'GET /health HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3 && head -n 1 <&3 | grep -q ' 200 '"]
ENTRYPOINT ["./bin/knurl-app"]
