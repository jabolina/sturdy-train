#!/usr/bin/env bash
set -euo pipefail

DEFAULT_CPUS="2.0"

usage() {
    cat <<EOF
Usage: $(basename "$0") <inject|restore> [args...]

Actions:
  inject <cpus> <node> [node...]   Throttle CPU on one or more nodes
  restore [node]                   Restore CPU to default ($DEFAULT_CPUS).
                                   If no node specified, restores all nodes.

Examples:
  $(basename "$0") inject 0.05 ispn-3
  $(basename "$0") inject 0.2 ispn-1 ispn-2
  $(basename "$0") restore ispn-3
  $(basename "$0") restore
EOF
    exit 1
}

if [ $# -lt 1 ]; then
    usage
fi

ACTION="$1"; shift

all_nodes() {
    docker ps --filter "name=^ispn-" --format '{{.Names}}'
}

do_inject() {
    if [ $# -lt 2 ]; then
        echo "Error: inject requires <cpus> and at least one <node>."
        usage
    fi

    local cpus="$1"; shift

    for node in "$@"; do
        echo "Throttling $node to $cpus CPUs..."
        docker update --cpus "$cpus" "$node"
    done
}

do_restore() {
    local nodes
    if [ $# -gt 0 ]; then
        nodes="$*"
    else
        nodes=$(all_nodes)
    fi

    for node in $nodes; do
        echo "Restoring $node to $DEFAULT_CPUS CPUs..."
        docker update --cpus "$DEFAULT_CPUS" "$node"
    done
}

case "$ACTION" in
    inject) do_inject "$@" ;;
    restore) do_restore "$@" ;;
    *) usage ;;
esac
