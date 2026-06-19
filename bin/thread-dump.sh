#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

usage() {
    cat <<EOF
Usage: $(basename "$0") [<node-name> | --all] [options]

Captures a thread dump (including virtual threads) from the JVM running
inside the given container and saves it to the node's log directory.

Arguments:
  <node-name>           Single container (e.g., ispn-5)
  --all                 All running ispn-* containers

Options:
  --format <text|json>  Output format (default: json)
  --repeat N            Take N dumps with a 5-second interval

Examples:
  $(basename "$0") ispn-5
  $(basename "$0") --all
  $(basename "$0") ispn-5 --format text
  $(basename "$0") ispn-5 --repeat 3
  $(basename "$0") --all --repeat 3
EOF
    exit 1
}

if [ $# -lt 1 ]; then
    usage
fi

ALL=false
CONTAINERS=()
FORMAT="json"
REPEAT=1

while [ $# -gt 0 ]; do
    case "$1" in
        --all) ALL=true; shift ;;
        --format) FORMAT="$2"; shift 2 ;;
        --repeat) REPEAT="$2"; shift 2 ;;
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

    local filename="thread-dump-${timestamp}.${FORMAT}"
    local remote_file="${REMOTE_LOG}/${filename}"

    docker exec "$container" \
        sh -c "\$JAVA_HOME/bin/jcmd $pid Thread.dump_to_file -format=$FORMAT $remote_file"

    echo "  Saved: $log_dir/$filename"
}

for container in "${CONTAINERS[@]}"; do
    for i in $(seq 1 "$REPEAT"); do
        echo "[$container] Taking thread dump ($i/$REPEAT)..."
        take_dump "$container"

        if [ "$i" -lt "$REPEAT" ]; then
            echo "  Waiting 5 seconds..."
            sleep 5
        fi
    done
done
