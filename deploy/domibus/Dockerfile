# Domibus 5.2.1.4 (Tomcat) without the self-provisioning logic of the official image.
#
# The official domibus-tomcat10 image generates domains, keystores and properties from environment
# variables on first boot and overwrites whatever is mounted. Our configuration is version controlled,
# so the official image only supplies files (Tomcat incl. JDBC drivers, Domibus distribution). Nothing
# is executed in it: it is amd64 only and its glibc requires x86-64-v3, which emulators such as
# Rosetta do not provide. All other stages are multi-arch, so Domibus runs natively on arm64 hosts.

ARG DOMIBUS_IMAGE=code.europa.eu:4567/edelivery/docker/domibus-tomcat10:5.2.1.4-JEE10

FROM --platform=linux/amd64 ${DOMIBUS_IMAGE} AS official-amd64

FROM busybox:1.36.1 AS build
COPY --from=official-amd64 /data/tomcat /opt/domibus
COPY --from=official-amd64 /data/domInstall/domibus /dist
# Only the WS plugin is installed (other plugins would start schedulers without configuration and log
# errors constantly). The domain templates are skipped because every directory under domains/ is
# started as a domain; our domains are bind mounted.
RUN set -ex; \
    conf=/opt/domibus/conf/domibus; \
    mkdir -p "$conf/plugins/lib" "$conf/plugins/config"; \
    unzip -q /dist/*-tomcat-configuration.zip -d "$conf" -x 'domains/default/*' 'domains/domain_name/*' 'webapps/*'; \
    unzip -q -p /dist/*-tomcat-war.zip > /opt/domibus/webapps/domibus.war; \
    unzip -q -j /dist/*-default-ws-plugin.zip 'conf/domibus/plugins/lib/*.jar' -d "$conf/plugins/lib"; \
    unzip -q -j /dist/*-default-ws-plugin.zip 'conf/domibus/plugins/config/tomcat/ws-plugin.properties' -d "$conf/plugins/config"; \
    mkdir -p /opt/domibus/logs /opt/domibus/temp /opt/domibus/work

# The Ubuntu release is pinned too: "cp --update=none" in docker-compose.yml needs coreutils 9.3 or later.
FROM eclipse-temurin:21.0.12.1_1-jre-resolute
ENV CATALINA_HOME=/opt/domibus
ENV PATH=${CATALINA_HOME}/bin:${PATH}
ENV JAVA_OPTS="-Xms256m -Xmx1024m -Ddomibus.config.location=${CATALINA_HOME}/conf/domibus"
COPY --from=build /opt/domibus ${CATALINA_HOME}
WORKDIR ${CATALINA_HOME}
EXPOSE 8080
CMD ["catalina.sh", "run"]
