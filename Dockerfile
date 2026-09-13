FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src src
# PostgreSQL integration tests run with ./mvnw verify against Testcontainers outside image builds.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp package

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S walletgroup && adduser -S -G walletgroup walletuser
WORKDIR /app
COPY --from=build --chown=walletuser:walletgroup /workspace/target/wallet-service.jar app.jar
USER walletuser:walletgroup
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=300s --retries=3 \
    CMD wget -q -T 2 -O /dev/null "http://127.0.0.1:${PORT:-8080}/actuator/health" || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=35.0", "-XX:ReservedCodeCacheSize=48m", "-jar", "/app/app.jar"]
