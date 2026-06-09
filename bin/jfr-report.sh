#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
DRIVER_LOGS="$PROJECT_DIR/logs/driver"
CP="$PROJECT_DIR/target/classes:$PROJECT_DIR/target/libs/*"

JFR_FILE="${1:-$DRIVER_LOGS/latest.jfr}"

if [ ! -f "$JFR_FILE" ]; then
    echo "JFR file not found: $JFR_FILE"
    exit 1
fi

REPORT_FILE="$DRIVER_LOGS/report-$(date +%Y-%m-%d-%H:%M).html"

java -cp "$CP" org.infinispan.jfr.JfrReport "$JFR_FILE" "$REPORT_FILE"
