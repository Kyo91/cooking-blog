FROM eclipse-temurin:21-jdk-noble AS build

RUN apt-get update \
    && apt-get install -y --no-install-recommends clang libssl-dev libutf8proc-dev zlib1g-dev curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build
COPY project/build.properties project/build.properties
RUN version=$(cut -d= -f2 project/build.properties) \
    && curl -fsSL "https://repo.maven.apache.org/maven2/org/scala-sbt/sbt-launch/${version}/sbt-launch-${version}.jar" -o /tmp/sbt-launch.jar \
    && printf '[repositories]\nlocal\nmaven-central: https://repo.maven.apache.org/maven2/\n' > /tmp/sbt-repositories

COPY project/Dependencies.scala project/plugins.sbt project/
COPY build.sbt ./
COPY src/shared src/shared
COPY native-spike/src native-spike/src
RUN java -Xmx3g -Dsbt.override.build.repos=true -Dsbt.repository.config=/tmp/sbt-repositories \
      -jar /tmp/sbt-launch.jar 'nativeSpike/nativeLink' \
    && install native-spike/target/scala-*/cooking-blog-native-spike /native-spike

FROM ubuntu:24.04
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl libssl3t64 libutf8proc3 zlib1g \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /native-spike /usr/local/bin/native-spike
COPY native-spike/smoke.sh /usr/local/bin/smoke.sh
USER 65534:65534
