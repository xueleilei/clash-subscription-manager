# syntax=docker/dockerfile:1
FROM --platform=$BUILDPLATFORM node:24-bookworm-slim@sha256:c86ecead9e55a5210ac1727de38a4ca840bf03dcaf34dc9749addbf383b1920c AS frontend
WORKDIR /build/frontend
RUN npm install --global pnpm@11.25.0
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
RUN --mount=type=cache,target=/root/.local/share/pnpm/store pnpm install --frozen-lockfile
COPY frontend/ ./
RUN pnpm build

FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS backend
ARG TARGETARCH
WORKDIR /build
COPY backend/ backend/
COPY config/node-regions.yaml config/node-regions.yaml
COPY --from=frontend /build/frontend/dist/ frontend/dist/
COPY tools/mihomo/ tools/mihomo/
COPY deploy/HealthCheck.java deploy/HealthCheck.java
RUN --mount=type=cache,target=/root/.m2 --mount=type=secret,id=maven_settings,target=/tmp/maven-settings.xml \
    if [ -f /tmp/maven-settings.xml ]; then mvn -B -ntp -s /tmp/maven-settings.xml -f backend/pom.xml -DskipTests package; else mvn -B -ntp -f backend/pom.xml -DskipTests package; fi
RUN ./tools/mihomo/fetch.sh "linux-$TARGETARCH" && javac -d /build/health deploy/HealthCheck.java

FROM eclipse-temurin:21-jre-noble@sha256:0eda5830ed7b669abc246a8dc520ea8a23cab06f9a8d4b6964109e2f2394e46f
LABEL org.opencontainers.image.licenses="GPL-3.0-only"
ARG TARGETARCH
RUN groupadd --gid 10001 clash && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin clash
WORKDIR /opt/app
COPY LICENSE /opt/app/LICENSE
COPY --from=backend /build/backend/target/clash-subscription-manager-0.1.0-SNAPSHOT.jar /opt/app/app.jar
COPY --from=backend /build/health/ /opt/app/health/
COPY --from=backend /build/.cache/mihomo/linux-${TARGETARCH}/mihomo /opt/mihomo/mihomo
COPY --from=backend /build/.cache/mihomo/resources/ /opt/mihomo/resources/
COPY --chmod=755 deploy/entrypoint.sh /opt/app/entrypoint.sh
USER 10001:10001
ENV PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=20s --timeout=15s --start-period=60s --retries=5 CMD ["java", "-XX:MaxRAMPercentage=10", "-cp", "/opt/app/health", "HealthCheck"]
ENTRYPOINT ["/opt/app/entrypoint.sh"]
