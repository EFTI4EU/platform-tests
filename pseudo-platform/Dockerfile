# Build context: the repository root (see the pseudo-platform service in deploy/docker-compose.yml)
FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /build
# The reactor needs the poms of all modules, but only the sources of the modules built
COPY pom.xml .
COPY runner/pom.xml runner/
COPY core/pom.xml core/
COPY core/src core/src
COPY domibus-client/pom.xml domibus-client/
COPY domibus-client/src domibus-client/src
COPY pseudo-platform/pom.xml pseudo-platform/
COPY pseudo-platform/src pseudo-platform/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl pseudo-platform -am package -DskipTests

# The health check in docker-compose.yml uses the curl of this image.
FROM eclipse-temurin:21.0.12.1_1-jre-resolute
WORKDIR /app
COPY --from=build /build/pseudo-platform/target/pseudo-platform.jar app.jar
COPY formats formats
ENV PLATFORM_FORMAT_DIR=/app/formats/ri-v0.5
USER nobody
EXPOSE 18090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
