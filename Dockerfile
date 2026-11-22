FROM maven:3.9.16-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml ./
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline

COPY src ./src

# Version of the JAR, for example 0.1.0 for a release; left empty, pom.xml decides.
ARG VERSION
RUN if [ -n "$VERSION" ]; then \
      mvn --batch-mode --no-transfer-progress -DgenerateBackupPoms=false versions:set -DnewVersion="$VERSION"; \
    fi \
    && mvn --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S spring && adduser -S spring -G spring

WORKDIR /app

COPY --from=build --chown=spring:spring /workspace/target/*.jar app.jar

USER spring

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
  CMD wget -q -O - http://localhost:8080/actuator/health/readiness | grep -q '"status":"UP"' || exit 1

# Traceability (OCI annotations): the CI passes the commit, the version and the build date.
# The build context excludes .git, so docker inspect is the way to find the commit of an image.
ARG VCS_REF=unknown
ARG VERSION=unknown
ARG BUILD_DATE=unknown
LABEL org.opencontainers.image.title="medhead-api" \
      org.opencontainers.image.source="https://github.com/TheFatAndFurious/MedHead" \
      org.opencontainers.image.revision="$VCS_REF" \
      org.opencontainers.image.version="$VERSION" \
      org.opencontainers.image.created="$BUILD_DATE"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
