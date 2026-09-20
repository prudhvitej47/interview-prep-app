# syntax=docker/dockerfile:1

# Packages the jar that has already been built and tested. It does not build it: CI compiles and
# tests once on the runner, and repeating that inside the image added about two minutes to every
# merge for an identical result.
#
# Building by hand needs one command first:
#
#   cd backend && ./mvnw package && cd .. && docker build .
#
# Always linux/amd64 in CI: the VM is x86_64 while the development Macs are arm64.

FROM eclipse-temurin:25-jre-noble AS layers
WORKDIR /layers
COPY backend/target/*.jar app.jar
# Splits the jar by how often each part changes. Dependencies are 58 MB and change when the pom
# does; the application itself is about 320 KB and changes every merge. As separate image layers,
# a normal merge pushes the small one and the registry already has the rest.
# The destination has to be empty, and app.jar is sitting in /layers.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:25-jre-noble AS runtime
WORKDIR /app

RUN groupadd --system --gid 1001 app \
    && useradd --system --uid 1001 --gid app --no-create-home app

# Ordered least- to most-frequently changed, so the cheap layer is the one that keeps moving.
COPY --from=layers --chown=app:app /layers/extracted/dependencies/ ./
COPY --from=layers --chown=app:app /layers/extracted/spring-boot-loader/ ./
COPY --from=layers --chown=app:app /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers --chown=app:app /layers/extracted/application/ ./

USER app
EXPOSE 8080

# The container memory limit is the budget; let the JVM size its heap from that rather than
# hard-coding a number that has to change twice whenever the limit moves.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -cp . org.springframework.boot.loader.launch.JarLauncher"]
