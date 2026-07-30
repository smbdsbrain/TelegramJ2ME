FROM debian:bookworm-slim AS build

ARG MTPROXY_COMMIT=cafc3380a81671579ce366d0594b9a8e450827e9
RUN apt-get update \
 && apt-get install -y --no-install-recommends git build-essential libssl-dev zlib1g-dev ca-certificates \
 && git clone https://github.com/TelegramMessenger/MTProxy.git /src \
 && cd /src \
 && git checkout "$MTPROXY_COMMIT" \
 && make -j2

FROM debian:bookworm-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends libssl3 zlib1g curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/objs/bin/mtproto-proxy /usr/local/bin/mtproto-proxy
COPY tools/mtproxy/entrypoint.sh /entrypoint.sh
RUN chmod 0755 /entrypoint.sh
EXPOSE 443 8888
ENTRYPOINT ["/entrypoint.sh"]
