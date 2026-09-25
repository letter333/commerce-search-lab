#!/bin/sh
set -eu

# Desktop file-backed secrets may appear as mode 777. Keep the source read-only
# and give Elasticsearch a private, memory-backed copy with the required mode.
: "${ELASTIC_PASSWORD_FILE:?ELASTIC_PASSWORD_FILE is required}"
test -d /run/local-secrets
umask 077
cat "$ELASTIC_PASSWORD_FILE" > /run/local-secrets/elastic_password
chmod 400 /run/local-secrets/elastic_password
export ELASTIC_PASSWORD_FILE=/run/local-secrets/elastic_password

exec /usr/local/bin/docker-entrypoint.sh "$@"
