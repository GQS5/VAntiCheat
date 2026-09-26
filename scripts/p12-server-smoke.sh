#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 3 ] || [ "$#" -gt 4 ]; then
    printf 'Usage: %s <paper|folia> <server-directory> <artifact-jar> [duration-seconds]\n' "$0" >&2
    exit 64
fi

platform="$1"
server_dir="$2"
artifact="$3"
duration="${4:-45}"

case "$platform" in
    paper) server_jar="$server_dir/paper-1.21.11.jar"; default_port=25571 ;;
    folia) server_jar="$server_dir/folia-1.21.11.jar"; default_port=25570 ;;
    *) printf 'Unsupported platform: %s\n' "$platform" >&2; exit 64 ;;
esac

[ -d "$server_dir" ] || { printf 'Server directory not found: %s\n' "$server_dir" >&2; exit 66; }
[ -f "$server_jar" ] || { printf 'Server artifact not found: %s\n' "$server_jar" >&2; exit 66; }
[ -f "$artifact" ] || { printf 'VAntiCheat artifact not found: %s\n' "$artifact" >&2; exit 66; }

port="${P12_PORT:-$default_port}"
if command -v ss >/dev/null 2>&1 && ss -ltn | rg -q ":${port}[[:space:]]"; then
    printf 'Port %s is already in use; refusing to disturb a running server.\n' "$port" >&2
    exit 75
fi

plugin="$server_dir/plugins/VAntiCheat.jar"
backup="$(mktemp)"
log_dir="${P12_LOG_DIR:-${TMPDIR:-/tmp}/vanticheat-p12}"
mkdir -p "$log_dir"
log_file="$log_dir/${platform}-$(date +%Y%m%d-%H%M%S).log"

restore() {
    if [ -s "$backup" ]; then
        cp "$backup" "$plugin"
    else
        rm -f "$plugin"
    fi
    rm -f "$backup"
}
trap restore EXIT INT TERM

if [ -f "$plugin" ]; then cp "$plugin" "$backup"; fi
cp "$artifact" "$plugin"

printf 'Starting %s smoke validation; log=%s\n' "$platform" "$log_file"
(
    cd "$server_dir"
    timeout --signal=TERM "${duration}s" java -Xms1G -Xmx2G -jar "$(basename "$server_jar")" --nogui
) >"$log_file" 2>&1 || status=$?
status="${status:-0}"

if rg -q '\[VAntiCheat\] VAntiCheat enabled;.*platform=' "$log_file" \
        && rg -q '\[VAntiCheat\] VAntiCheat disabled' "$log_file"; then
    printf 'VAntiCheat startup/shutdown smoke: PASS\n'
else
    printf 'VAntiCheat startup/shutdown smoke: FAIL\n'
    printf 'Inspect: %s\n' "$log_file"
    exit 1
fi

if [ "$status" -ne 0 ] && [ "$status" -ne 124 ] && [ "$status" -ne 143 ]; then
    printf 'Server exited unexpectedly with status %s.\n' "$status" >&2
    exit 1
fi

printf 'No player validation was performed by this script.\n'
