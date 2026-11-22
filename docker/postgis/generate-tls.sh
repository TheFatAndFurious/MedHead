#!/bin/sh
set -eu

# Generates, once, a local CA and the PostgreSQL server certificate in /tls.
# Only the public CA certificate is published in /client-ca for the clients.

mkdir -p /tls
mkdir -p /client-ca

publish_client_ca() {
  cp /tls/ca.crt /client-ca/ca.crt
  chmod 644 /client-ca/ca.crt
  chmod 755 /client-ca
}

if [ -s /tls/ca.crt ] && [ -s /tls/server.crt ] && [ -s /tls/server.key ]; then
  openssl verify -CAfile /tls/ca.crt /tls/server.crt
  openssl x509 -in /tls/server.crt -checkend 0 -noout
  publish_client_ca
  echo "Existing PostgreSQL TLS certificates are valid."
  exit 0
fi

if [ -e /tls/ca.crt ] || [ -e /tls/server.crt ] || [ -e /tls/server.key ]; then
  echo "Incomplete PostgreSQL TLS certificates; recreate the postgis-tls volume." >&2
  exit 1
fi

umask 077
certificate_workdir="$(mktemp -d)"
trap 'rm -rf "$certificate_workdir"' EXIT

openssl genrsa -out "$certificate_workdir/ca.key" 2048
openssl req -x509 -new -sha256 -days 3650 \
  -key "$certificate_workdir/ca.key" \
  -out "$certificate_workdir/ca.crt" \
  -subj "/CN=MedHead POC PostgreSQL CA" \
  -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,keyCertSign,cRLSign"

openssl genrsa -out "$certificate_workdir/server.key" 2048
openssl req -new -sha256 \
  -key "$certificate_workdir/server.key" \
  -out "$certificate_workdir/server.csr" \
  -subj "/CN=database"

# verify-full clients check that the host name they connect to is listed here.
cat > "$certificate_workdir/server.ext" <<'EOF'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:database,DNS:localhost,IP:127.0.0.1
EOF

openssl x509 -req -sha256 -days 365 \
  -in "$certificate_workdir/server.csr" \
  -CA "$certificate_workdir/ca.crt" \
  -CAkey "$certificate_workdir/ca.key" \
  -CAcreateserial \
  -out "$certificate_workdir/server.crt" \
  -extfile "$certificate_workdir/server.ext"

cp "$certificate_workdir/ca.crt" /tls/ca.crt
cp "$certificate_workdir/server.crt" /tls/server.crt
cp "$certificate_workdir/server.key" /tls/server.key
# PostgreSQL refuses a key readable by anyone other than its owner.
chown postgres:postgres /tls/server.key
chmod 600 /tls/server.key
chmod 644 /tls/ca.crt /tls/server.crt
chmod 755 /tls
publish_client_ca

echo "PostgreSQL TLS certificates generated (server certificate valid for one year)."
