#!/bin/sh
set -eu

: "${REDIS_PASSWORD:?REDIS_PASSWORD must be set}"

# Extra arguments (Compose `command`) override redis.conf, e.g. for load test variants.
# The official entrypoint switches to the redis user and owns /data correctly.
exec /usr/local/bin/docker-entrypoint.sh redis-server \
  /usr/local/etc/redis/redis.conf \
  --requirepass "$REDIS_PASSWORD" \
  --maxmemory "${REDIS_MAXMEMORY:-256mb}" \
  "$@"
