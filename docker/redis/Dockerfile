FROM redis:8.2.10-alpine

RUN apk add --no-cache openssl

COPY docker/redis/redis.conf /usr/local/etc/redis/redis.conf
COPY docker/redis/generate-tls.sh /usr/local/bin/generate-tls.sh
COPY docker/redis/start-redis.sh /usr/local/bin/start-redis.sh

RUN chmod 755 /usr/local/bin/generate-tls.sh /usr/local/bin/start-redis.sh

ENTRYPOINT ["/usr/local/bin/start-redis.sh"]
