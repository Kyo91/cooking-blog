# Build the staged sbt-native-packager distribution; Railway detects this file automatically.
FROM sbtscala/scala-sbt:eclipse-temurin-21.0.11_10_1.12.14_3.8.4 AS build

WORKDIR /workspace

COPY build.sbt .scalafmt.conf ./
COPY project ./project
RUN sbt -batch -Dsbt.supershell=false update

COPY src ./src
RUN sbt -batch -Dsbt.supershell=false clean stage

FROM eclipse-temurin:21-jre-jammy

RUN groupadd --gid 1001 cooking-blog && \
    useradd --uid 1001 --gid cooking-blog --create-home --shell /usr/sbin/nologin cooking-blog

WORKDIR /opt/cooking-blog
COPY --from=build --chown=cooking-blog:cooking-blog /workspace/target/universal/stage ./

USER cooking-blog

ENV HTTP_HOST=0.0.0.0

EXPOSE 8080

CMD ["/opt/cooking-blog/bin/cooking-blog"]
