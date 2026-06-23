FROM maven:3.9.11-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre-jammy

ARG GOALMAKER_SOURCE_FINGERPRINT=unknown
LABEL com.example.goalmaker.source="$GOALMAKER_SOURCE_FINGERPRINT"

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
       ca-certificates iptables util-linux \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /workspace/target/goalmaker.jar /app/goalmaker.jar
COPY docker/fetch-worker/entrypoint.sh /usr/local/bin/goalmaker-fetch-worker
RUN chmod 0555 /usr/local/bin/goalmaker-fetch-worker \
    && chmod 0444 /app/goalmaker.jar

ENTRYPOINT ["/usr/local/bin/goalmaker-fetch-worker"]
