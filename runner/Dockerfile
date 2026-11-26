# Build context: the repository root (see the runner service in deploy/docker-compose.yml)
FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /build
# The reactor needs the poms of all modules, but only the sources of the modules built
COPY pom.xml .
COPY pseudo-platform/pom.xml pseudo-platform/
COPY core/pom.xml core/
COPY core/src core/src
COPY domibus-client/pom.xml domibus-client/
COPY domibus-client/src domibus-client/src
COPY runner/pom.xml runner/
COPY runner/src runner/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl runner -am package -DskipTests

FROM eclipse-temurin:21.0.12.1_1-jre-resolute
# Same layout as the repository, so paths printed by the runner (e.g. the operator script) are recognisable
WORKDIR /app
COPY --from=build /build/runner/target/runner.jar runner.jar
COPY formats formats
COPY testdata testdata
ENTRYPOINT ["java", "-jar", "/app/runner.jar"]
