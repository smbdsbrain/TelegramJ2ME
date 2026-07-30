#!/bin/sh
set -eu

case "${MTPROXY_SECRET:-}" in
  ????????????????????????????????) ;;
  *) echo "MTPROXY_SECRET must be exactly 32 hex characters" >&2; exit 2 ;;
esac

mkdir -p /run/mtproxy
curl --fail --silent --show-error https://core.telegram.org/getProxySecret \
  -o /run/mtproxy/proxy-secret
curl --fail --silent --show-error https://core.telegram.org/getProxyConfig \
  -o /run/mtproxy/proxy-multi.conf

set --
case "${MTPROXY_VERBOSITY:-0}" in
  0) ;;
  *) set -- -v -v -v ;;
esac

if [ -n "${MTPROXY_PUBLIC_IP:-}" ]; then
  private_ip="$(hostname -i | awk '{ print $1 }')"
  set -- "$@" --nat-info "${private_ip}:${MTPROXY_PUBLIC_IP}"
fi

exec /usr/local/bin/mtproto-proxy \
  "$@" -u nobody -p 8888 -H 443 -S "$MTPROXY_SECRET" \
  --aes-pwd /run/mtproxy/proxy-secret /run/mtproxy/proxy-multi.conf -M 1
