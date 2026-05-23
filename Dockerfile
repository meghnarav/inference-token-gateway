# ── Stage 1: Build ──────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Cache Maven dependencies before copying source
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B --no-transfer-progress 2>/dev/null || true

COPY src ./src

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
