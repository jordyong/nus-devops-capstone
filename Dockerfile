# syntax=docker/dockerfile:1

# ---- Build stage: compile and package with Maven (tests run in the CI Test stage) ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Resolve dependencies first so this layer is cached until pom.xml changes
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/transaction-validator.jar extract --layers --launcher --destination target/extracted

# ---- Runtime stage: minimal JRE, non-root user ----
FROM eclipse-temurin:21-jre-alpine

ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="transaction-validator" \
      org.opencontainers.image.description="FinSecure Pay transaction validation API" \
      org.opencontainers.image.source="https://github.com/jordyong/nus-devops-capstone" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"

# Pull in Alpine security fixes released after the base image was published (the image scan gate fails on fixable CVEs)
RUN apk upgrade --no-cache \
 && addgroup -S app && adduser -S -G app -u 10001 app
WORKDIR /app

# Spring Boot layers, least to most frequently changed, for better layer caching
COPY --from=build --chown=app:app /workspace/target/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/application/ ./

USER 10001
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:8080/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
