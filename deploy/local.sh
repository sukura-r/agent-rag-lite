#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
if [ -f .env ]; then
  set -a
  . ./.env
  set +a
fi
test -f target/agent-rag-lite-0.0.1-SNAPSHOT.jar || {
  echo 'Build first: npm ci, npm run build (frontend), then mvn package (root).' >&2
  exit 1
}
exec java -Xms256m -Xmx1024m -jar target/agent-rag-lite-0.0.1-SNAPSHOT.jar
