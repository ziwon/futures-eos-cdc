FROM curlimages/curl:8.7.1 AS fetch
ARG DBZ_VERSION=3.0.0.Final
ARG DBZ_URL=https://repo1.maven.org/maven2/io/debezium/debezium-connector-postgres/${DBZ_VERSION}/debezium-connector-postgres-${DBZ_VERSION}-plugin.tar.gz
RUN mkdir -p /tmp/artifact && \
    curl -fsSL -o /tmp/artifact/debezium.tgz "$DBZ_URL" && \
    mkdir -p /tmp/artifact/debezium && \
    tar -xzf /tmp/artifact/debezium.tgz -C /tmp/artifact/debezium

FROM quay.io/strimzi/kafka:0.47.0-kafka-3.9.0
USER root
COPY --from=fetch /tmp/artifact/debezium /opt/kafka/plugins/debezium-postgres
RUN chown -R 1001:0 /opt/kafka/plugins && chmod -R g+rwX /opt/kafka/plugins
USER 1001
