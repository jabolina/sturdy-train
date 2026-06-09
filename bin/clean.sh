#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
LOGS_DIR="$PROJECT_DIR/logs"

usage() {
    cat <<EOF
Usage: $(basename "$0") [action]

Actions:
  logs      Delete server log files (default)
  reports   Delete HTML reports and async-profiler output
  jfr       Delete JFR recordings
  all       Delete everything: logs, reports, JFR, heap dumps

Examples:
  $(basename "$0")
  $(basename "$0") all
  $(basename "$0") jfr
EOF
    exit 1
}

ACTION="${1:-logs}"

clean_logs() {
    echo "Cleaning log files..."
    find "$LOGS_DIR" -type f \( -name '*.log' -o -name '*.log.*' \) -print -delete
}

clean_reports() {
    echo "Cleaning reports..."
    find "$LOGS_DIR" -type f \( -name '*.html' \) -print -delete
}

clean_jfr() {
    echo "Cleaning JFR recordings..."
    find "$LOGS_DIR" -type f \( -name '*.jfr' \) -print -delete
    find "$LOGS_DIR" -type l -name '*.jfr' -print -delete
}

clean_heap() {
    echo "Cleaning heap dumps..."
    find "$LOGS_DIR" -type f \( -name '*.hprof' \) -print -delete
}

case "$ACTION" in
    logs)    clean_logs ;;
    reports) clean_reports ;;
    jfr)     clean_jfr ;;
    all)     clean_logs; clean_reports; clean_jfr; clean_heap ;;
    -h|--help) usage ;;
    *)       echo "Error: unknown action '$ACTION'"; usage ;;
esac

echo "Done."
