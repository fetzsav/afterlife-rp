#!/usr/bin/env bash
# Wings API helper for the AfterLife dev server (user-approved automation).
# Usage: AFTERLIFE_SERVER_UUID=<uuid> wings.sh <start|stop|restart|kill|status>
set -euo pipefail

UUID="${AFTERLIFE_SERVER_UUID:?set AFTERLIFE_SERVER_UUID to the Pterodactyl server UUID}"
# Wings serves HTTPS with a cert for the node hostname; -k for loopback access.
API="https://127.0.0.1:8080/api/servers/$UUID"
CURL="curl -sk"
TOKEN=$(awk '/^token:/ {print $2}' /etc/pterodactyl/config.yml)

case "${1:?usage: wings.sh <start|stop|restart|kill|status>}" in
    start|stop|restart|kill)
        code=$($CURL -o /tmp/wings-response.txt -w "%{http_code}" -X POST "$API/power" \
            -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
            -d "{\"action\":\"$1\"}")
        echo "power $1 -> HTTP $code"
        [ "$code" = "204" ] || cat /tmp/wings-response.txt
        ;;
    status)
        $CURL "$API" -H "Authorization: Bearer $TOKEN" | head -c 400
        echo
        ;;
    *)
        echo "unknown action: $1" >&2
        exit 1
        ;;
esac
