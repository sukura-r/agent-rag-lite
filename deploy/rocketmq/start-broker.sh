#!/bin/sh
set -eu
case "$BROKER_ADVERTISED_HOST" in ''|*[!a-zA-Z0-9.:-]*) echo "Invalid broker host" >&2; exit 1;; esac
case "$BROKER_PORT" in ''|*[!0-9]*) echo "Invalid broker port" >&2; exit 1;; esac
cat /home/rocketmq/conf/base-broker.conf > /tmp/agent-broker.conf
printf '\nbrokerIP1=%s\nlistenPort=%s\nnamesrvAddr=rocketmq-namesrv:9876\n' \
  "$BROKER_ADVERTISED_HOST" "$BROKER_PORT" >> /tmp/agent-broker.conf
exec sh mqbroker -c /tmp/agent-broker.conf
