#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

usage() {
    cat <<EOF
Usage: $(basename "$0") [<node-name> | --all] [options]

Captures a heap dump from the JVM running inside the given container
and saves it to the node's log directory.

Arguments:
  <node-name>           Single container (e.g., ispn-5)
  --all                 All running ispn-* containers

Options:
  --live                Only dump live objects (triggers a full GC first)

Examples:
  $(basename "$0") ispn-5
  $(basename "$0") --all
  $(basename "$0") ispn-5 --live
  $(basename "$0") --all --live
EOF
    exit 1
}

if [ $# -lt 1 ]; then
    usage
fi

ALL=false
CONTAINERS=()
LIVE=""

while [ $# -gt 0 ]; do
    case "$1" in
        --all) ALL=true; shift ;;
        --live) LIVE="-all=false"; shift ;;
        --help|-h) usage ;;
        -*) echo "Error: unknown option '$1'"; usage ;;
        *) CONTAINERS+=("$1"); shift ;;
    esac
done

if [ "$ALL" = true ]; then
    mapfile -t CONTAINERS < <(docker ps --filter "name=ispn-" --format '{{.Names}}' | sort)
    if [ ${#CONTAINERS[@]} -eq 0 ]; then
        echo "Error: no running ispn-* containers found"
        exit 1
    fi
fi

if [ ${#CONTAINERS[@]} -eq 0 ]; then
    usage
fi

for container in "${CONTAINERS[@]}"; do
    if ! docker ps --format '{{.Names}}' | grep -qx "$container"; then
        echo "Error: container '$container' is not running"
        echo "Running containers: $(docker ps --filter 'name=ispn-' --format '{{.Names}}' | sort | tr '\n' ' ')"
        exit 1
    fi
done

REMOTE_LOG="/opt/infinispan/server/log"

find_java_pid() {
    docker exec "$1" sh -c '
        for d in /proc/[0-9]*/cmdline; do
            if grep -ql java "$d" 2>/dev/null; then
                echo "${d}" | sed "s|/proc/||;s|/cmdline||"
                exit
            fi
        done
    '
}

take_dump() {
    local container="$1"
    local log_dir="$PROJECT_DIR/logs/$container"
    local timestamp
    timestamp=$(date +%Y%m%d-%H%M%S)

    local pid
    pid=$(find_java_pid "$container")
    if [ -z "$pid" ]; then
        echo "  ERROR: no Java process found in $container"
        return 1
    fi

    local filename="heap-dump-${timestamp}.hprof"
    local remote_file="${REMOTE_LOG}/${filename}"

    docker exec "$container" \
        sh -c "\$JAVA_HOME/bin/jcmd $pid GC.heap_dump $LIVE $remote_file"

    echo "  Saved: $log_dir/$filename"
}

for container in "${CONTAINERS[@]}"; do
    echo "[$container] Taking heap dump..."
    take_dump "$container"
done
