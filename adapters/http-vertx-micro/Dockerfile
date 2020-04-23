FROM oracle/graalvm-ce:20.0.0-java11 as graalvm
RUN gu install native-image

COPY . /home/app/hono-adapter-http-vertx-micro
WORKDIR /home/app/hono-adapter-http-vertx-micro

RUN native-image --no-server -cp target/hono-adapter-http-vertx-micro-*.jar

FROM frolvlad/alpine-glibc
RUN apk update && apk add libstdc++
EXPOSE 8080
EXPOSE 8443
EXPOSE 8088
COPY --from=graalvm /home/app/hono-adapter-http-vertx-micro/hono-adapter-http-vertx-micro /app/hono-adapter-http-vertx-micro
ENTRYPOINT ["/app/hono-adapter-http-vertx-micro"]