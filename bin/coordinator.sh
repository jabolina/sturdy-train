#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

usage() {
    cat <<EOF
Usage: $(basename "$0") <find|stop>

Actions:
  find    Show the current cluster view and identify the coordinator
  stop    Send SIGINT to the coordinator

The coordinator is determined from the latest ISPN000094 view log entry.

Examples:
  $(basename "$0") find
  $(basename "$0") stop
EOF
    exit 1
}

if [ $# -lt 1 ]; then
    usage
fi

ACTION="$1"; shift

parse_view() {
    local view_line
    view_line=$(grep -h 'ISPN000094' "$PROJECT_DIR"/logs/ispn-*/server.log* \
        | sort -t',' -k2 \
        | tail -1 \
        || true)

    if [ -z "$view_line" ]; then
        echo "Could not determine cluster view from logs."
        exit 1
    fi

    local members
    members=$(echo "$view_line" | grep -oP '\(\d+\) \[\K[^]]+')

    COORDINATOR=$(echo "$members" | cut -d',' -f1 | sed 's/(.*//' | xargs)
    MEMBERS="$members"
    MEMBER_COUNT=$(echo "$members" | tr ',' '\n' | wc -l | xargs)
}

do_find() {
    parse_view
    echo "Cluster size: $MEMBER_COUNT"
    echo "Coordinator:  $COORDINATOR"
    echo "Members:      $MEMBERS"
}

do_stop() {
    parse_view
    echo "Coordinator: $COORDINATOR"
    echo "Sending SIGINT to $COORDINATOR..."
    docker kill --signal=SIGINT "$COORDINATOR"
    echo "Done."
}

case "$ACTION" in
    find) do_find ;;
    stop) do_stop ;;
    *) usage ;;
esac
