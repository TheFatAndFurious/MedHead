FROM postgres:17-bookworm

RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        openssl \
        postgresql-17-postgis-3 \
        postgresql-17-postgis-3-scripts \
    && rm -rf /var/lib/apt/lists/*

COPY docker/postgis/init-postgis.sql /docker-entrypoint-initdb.d/10-init-postgis.sql
COPY docker/postgis/pg_hba.conf /etc/postgresql/pg_hba.conf
COPY docker/postgis/generate-tls.sh /usr/local/bin/generate-tls.sh

RUN chmod 644 /etc/postgresql/pg_hba.conf \
    && chmod 755 /usr/local/bin/generate-tls.sh

# The certificate and key come from the /tls volume filled by generate-tls.sh.
# pg_hba.conf rejects any network connection that does not use TLS.
CMD ["postgres", \
     "-c", "ssl=on", \
     "-c", "ssl_cert_file=/tls/server.crt", \
     "-c", "ssl_key_file=/tls/server.key", \
     "-c", "ssl_min_protocol_version=TLSv1.2", \
     "-c", "hba_file=/etc/postgresql/pg_hba.conf"]
