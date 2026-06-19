#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ASPROF_PATH=""
CONTAINERS=$(docker ps --filter "name=ispn-" --format '{{.Names}}' 2>/dev/null | sort)
PROFILER_DIR="/opt/infinispan/async-profiler"

usage() {
    cat <<EOF
Usage: $(basename "$0") <start|stop|status> [options]

Actions:
  start   --path <async-profiler-dir> [-- asprof start options]
  stop    [--format <html|jfr|collapsed>] [-- asprof stop options]
  status

The --path flag is required on 'start' and points to the local async-profiler directory.
Options after -- are forwarded to asprof for the respective action.

Start defaults: -e cpu
Stop defaults:  --format html

Examples:
  $(basename "$0") start --path /home/user/async-profiler
  $(basename "$0") start --path /home/user/async-profiler -- -e wall --all
  $(basename "$0") stop
  $(basename "$0") stop --format jfr
EOF
    exit 1
}

if [ $# -lt 1 ]; then
    usage
fi

ACTION="$1"; shift

install_profiler() {
    local container="$1"
    if docker exec "$container" test -f "$PROFILER_DIR/bin/asprof" 2>/dev/null; then
        return
    fi
    echo "  Installing async-profiler in $container..."
    docker exec "$container" mkdir -p "$PROFILER_DIR/bin" "$PROFILER_DIR/lib"
    docker cp "$ASPROF_PATH/bin/asprof" "$container:$PROFILER_DIR/bin/asprof"
    docker cp "$ASPROF_PATH/lib/libasyncProfiler.so" "$container:$PROFILER_DIR/lib/libasyncProfiler.so"
}

find_java_pid() {
    local container="$1"
    docker exec "$container" pgrep -x java || docker exec "$container" pgrep -f "org.infinispan" || echo ""
}

do_start() {
    while [ $# -gt 0 ]; do
        case "$1" in
            --path) ASPROF_PATH="$2"; shift 2 ;;
            --) shift; break ;;
            *) echo "Error: unknown option '$1'. Use -- to separate asprof options."; usage ;;
        esac
    done

    if [ -z "$ASPROF_PATH" ]; then
        echo "Error: --path is required for start"
        usage
    fi

    if [ ! -f "$ASPROF_PATH/bin/asprof" ]; then
        echo "Error: asprof binary not found at $ASPROF_PATH/bin/asprof"
        exit 1
    fi

    local extra_args=("$@")
    local has_event=false
    for arg in "${extra_args[@]}"; do
        if [ "$arg" = "-e" ]; then
            has_event=true
        fi
    done

    for container in $CONTAINERS; do
        echo "[$container]"
        install_profiler "$container"

        local pid
        pid=$(find_java_pid "$container")
        if [ -z "$pid" ]; then
            echo "  WARNING: No Java process found, skipping"
            continue
        fi

        local args=()
        if [ "$has_event" = false ]; then
            args+=(-e cpu)
        fi
        args+=("${extra_args[@]}")

        echo "  Starting profiler on PID $pid..."
        docker exec "$container" "$PROFILER_DIR/bin/asprof" start "${args[@]}" "$pid"
    done

    echo ""
    echo "Profiling started on all nodes. Run '$(basename "$0") stop' when done."
}

do_stop() {
    local ext="html"
    local extra_args=()
    while [ $# -gt 0 ]; do
        case "$1" in
            --format) ext="$2"; shift 2 ;;
            --) shift; extra_args=("$@"); break ;;
            *) echo "Error: unknown option '$1'."; usage ;;
        esac
    done

    for container in $CONTAINERS; do
        echo "[$container]"

        if ! docker exec "$container" test -f "$PROFILER_DIR/bin/asprof" 2>/dev/null; then
            echo "  Profiler not installed, skipping"
            continue
        fi

        local pid
        pid=$(find_java_pid "$container")
        if [ -z "$pid" ]; then
            echo "  WARNING: No Java process found, skipping"
            continue
        fi

        local output_file="/opt/infinispan/server/log/profile.${ext}"
        local args=(-f "$output_file")
        args+=("${extra_args[@]}")

        echo "  Stopping profiler on PID $pid..."
        docker exec "$container" "$PROFILER_DIR/bin/asprof" stop "${args[@]}" "$pid"

        local log_dir="$PROJECT_DIR/logs/$container"
        echo "  Output: $log_dir/profile.${ext}"
    done
}

do_status() {
    for container in $CONTAINERS; do
        echo "[$container]"

        if ! docker exec "$container" test -f "$PROFILER_DIR/bin/asprof" 2>/dev/null; then
            echo "  Profiler not installed"
            continue
        fi

        local pid
        pid=$(find_java_pid "$container")
        if [ -z "$pid" ]; then
            echo "  No Java process found"
            continue
        fi

        docker exec "$container" "$PROFILER_DIR/bin/asprof" status "$pid"
    done
}

case "$ACTION" in
    start)  do_start "$@" ;;
    stop)   do_stop "$@" ;;
    status) do_status ;;
    *)      usage ;;
esac
