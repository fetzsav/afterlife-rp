#!/usr/bin/env bash
# AfterLifeRP database backup with rotation. Reads credentials from the repo
# .env (never committed; rule 12). Runs mysqldump inside the MariaDB container
# so no client is needed on the host.
#
# Usage:
#   backup.sh                 # write a timestamped dump, prune old ones
#   backup.sh restore <file>  # restore a dump (DESTRUCTIVE, prompts first)
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$REPO_DIR/.env"
CONTAINER="afterlife-mariadb"
BACKUP_DIR="${AFTERLIFE_BACKUP_DIR:-$REPO_DIR/backups}"
KEEP=${AFTERLIFE_BACKUP_KEEP:-14}

[ -f "$ENV_FILE" ] || { echo "missing $ENV_FILE" >&2; exit 1; }
# shellcheck disable=SC1090
set -a; . "$ENV_FILE"; set +a
DB="${MARIADB_DATABASE:-afterlife}"

dump() {
    mkdir -p "$BACKUP_DIR"
    local stamp file
    stamp="$(date -u +%Y%m%d-%H%M%S)"
    file="$BACKUP_DIR/afterlife-$stamp.sql.gz"
    docker exec "$CONTAINER" sh -c \
        "exec mariadb-dump --single-transaction --routines --triggers \
            -u root -p'$MARIADB_ROOT_PASSWORD' '$DB'" \
        | gzip > "$file"
    echo "wrote $file ($(du -h "$file" | cut -f1))"
    # Rotation: keep the newest $KEEP dumps.
    ls -1t "$BACKUP_DIR"/afterlife-*.sql.gz 2>/dev/null | tail -n +$((KEEP + 1)) \
        | xargs -r rm -f
}

restore() {
    local file="$1"
    [ -f "$file" ] || { echo "no such file: $file" >&2; exit 1; }
    echo "This will OVERWRITE database '$DB'. Type the DB name to confirm:"
    read -r confirm
    [ "$confirm" = "$DB" ] || { echo "aborted"; exit 1; }
    gunzip -c "$file" | docker exec -i "$CONTAINER" sh -c \
        "exec mariadb -u root -p'$MARIADB_ROOT_PASSWORD' '$DB'"
    echo "restored $DB from $file"
}

case "${1:-dump}" in
    dump)    dump ;;
    restore) restore "${2:?usage: backup.sh restore <file>}" ;;
    *) echo "usage: backup.sh [dump|restore <file>]" >&2; exit 1 ;;
esac
