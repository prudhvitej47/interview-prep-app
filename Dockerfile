# Built by CI from the repository root, because the build needs both backend/ and frontend/.
#
# Always built for linux/amd64: the Lightsail VM is x86_64 while the development Macs are arm64,
# so an image built natively on a Mac will not start on the server.

FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src

# Dependencies first, so a code-only change reuses the cached layer.
COPY backend/pom.xml backend/pom.xml
RUN mvn -B -f backend/pom.xml dependency:go-offline -DskipTests

COPY frontend/package.json frontend/package-lock.json frontend/
COPY backend backend
COPY frontend frontend

# Tests run in CI before this image is built; repeating them here would only slow the push.
RUN mvn -B -f backend/pom.xml clean package -DskipTests \
    && mv backend/target/interview-prep-backend-*.jar /src/app.jar

FROM eclipse-temurin:25-jre-noble AS runtime
WORKDIR /app

RUN groupadd --system --gid 1001 app \
    && useradd --system --uid 1001 --gid app --no-create-home app

COPY --from=build --chown=app:app /src/app.jar /app/app.jar

USER app
EXPOSE 8080

# The container memory limit is the budget; let the JVM size its heap from that rather than
# hard-coding a number that has to be changed twice whenever the limit moves.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD ["java", "-version"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
