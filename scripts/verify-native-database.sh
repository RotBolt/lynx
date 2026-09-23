#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 <lynx-binary> <android|ios> <device-or-simulator> <application-id> <database-path> <output-directory>" >&2
  exit 2
}

[[ $# -eq 6 ]] || usage
LYNX="$1"
PLATFORM="$2"
TARGET="$3"
APP_ID="$4"
DATABASE="$5"
OUTPUT_DIR="$6"
QUERY='SELECT id,transport,method,url,status,response_body,error,started_at,completed_at FROM network_events ORDER BY id;'

[[ -x "$LYNX" ]] || { echo "Lynx executable is not runnable: $LYNX" >&2; exit 2; }
command -v sqlite3 >/dev/null || { echo "sqlite3 is required" >&2; exit 2; }
mkdir -p "$OUTPUT_DIR"
safe_name="$(printf '%s' "$DATABASE" | tr '/ ' '__')"
SOURCE_DB="$OUTPUT_DIR/source-${PLATFORM}-${safe_name}"

case "$PLATFORM" in
  android)
    command -v adb >/dev/null || { echo "adb is required" >&2; exit 2; }
    adb -s "$TARGET" exec-out run-as "$APP_ID" cat "$DATABASE" > "$SOURCE_DB"
    "$LYNX" db list --platform android --device "$TARGET" --package "$APP_ID" > "$OUTPUT_DIR/db-list.txt"
    SNAPSHOT_RESULT="$("$LYNX" db snapshot "$DATABASE" --platform android --device "$TARGET" --package "$APP_ID")"
    ;;
  ios)
    command -v xcrun >/dev/null || { echo "xcrun is required" >&2; exit 2; }
    CONTAINER="$(xcrun simctl get_app_container "$TARGET" "$APP_ID" data)"
    cp "$CONTAINER/$DATABASE" "$SOURCE_DB"
    "$LYNX" db list --platform ios --simulator "$TARGET" --bundle-id "$APP_ID" > "$OUTPUT_DIR/db-list.txt"
    SNAPSHOT_RESULT="$("$LYNX" db snapshot "$DATABASE" --platform ios --simulator "$TARGET" --bundle-id "$APP_ID")"
    ;;
  *) usage ;;
esac

printf '%s\n' "$SNAPSHOT_RESULT" > "$OUTPUT_DIR/db-snapshot.txt"
SNAPSHOT_DB="$(sed -n 's/.* path=\([^ ]*\).*/\1/p' "$OUTPUT_DIR/db-snapshot.txt" | tail -n 1)"
[[ -n "$SNAPSHOT_DB" && -f "$SNAPSHOT_DB" ]] || { echo "Lynx did not return a readable snapshot path" >&2; exit 1; }
"$LYNX" db tables "$SNAPSHOT_DB" > "$OUTPUT_DIR/db-tables.txt"
"$LYNX" db query "$SNAPSHOT_DB" "$QUERY" > "$OUTPUT_DIR/db-query.txt"
sqlite3 -readonly -json "$SOURCE_DB" "$QUERY" > "$OUTPUT_DIR/source-rows.json"
sqlite3 -readonly -json "$SNAPSHOT_DB" "$QUERY" > "$OUTPUT_DIR/snapshot-rows.json"

if ! cmp -s "$OUTPUT_DIR/source-rows.json" "$OUTPUT_DIR/snapshot-rows.json"; then
  echo "Database source and snapshot rows differ. Record this as a WAL/coherence baseline failure; do not hide it with a fingerprint." >&2
  exit 1
fi
echo "DB_BASELINE_OK platform=$PLATFORM database=$DATABASE rows=$(jq 'length' "$OUTPUT_DIR/snapshot-rows.json")"
