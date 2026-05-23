# ── Stage 1: Build ──────────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /build

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn package -DskipTests -B

RUN --mount=type=cache,target=/root/.m2 \
    mvn package -DskipTests -B --no-transfer-progress

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: run as non-root
RUN addgroup -S gateway && adduser -S gateway -G gateway
USER gateway

WORKDIR /app

# Copy JAR from builder
COPY --from=builder /build/target/inference-token-gateway-*.jar app.jar

# JVM tuning for containerised deployment:
# - UseContainerSupport: respects cgroup memory limits
# - MaxRAMPercentage:    use up to 75% of container RAM for heap
# - G1GC:               balanced GC for latency-sensitive workloads
# - ExitOnOutOfMemoryError: fail fast instead of thrashing
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+UseG1GC \
               -XX:+ExitOnOutOfMemoryError \
               -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
