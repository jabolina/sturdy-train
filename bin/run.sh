#!/bin/bash

# --- Setup Variables ---
DIR=$(dirname "$0")
# Using $(...) for command substitution is generally preferred over backticks (`)
TARGET_DIR="$DIR/../target"
LIBS="$TARGET_DIR/libs"
# The colon is used to separate elements in the classpath
CP="$TARGET_DIR/classes:$TARGET_DIR/libs/*"

# Initialize LOG and AGENT_PATH
LOG=""
AGENT_PATH=""
# Arguments to be passed to the Java application
JAVA_ARGS=()

# --- Logging Configuration ---
if [ -f "$HOME/log4j.properties" ]; then
    LOG="-Dlog4j.configuration=file:$HOME/log4j.properties"
fi

if [ -f "$HOME/log4j2.xml" ]; then
    LOG="$LOG -Dlog4j.configurationFile=$HOME/log4j2.xml"
else
    LOG="$LOG -Dlog4j.configurationFile=log4j2.xml"
fi

if [ -f "$HOME/logging.properties" ]; then
    LOG="$LOG -Djava.util.logging.config.file=$HOME/logging.properties"
fi

# --- Argument Parsing and Profiling Check ---
# Loop through all provided arguments ($@) to check for --profiling
while [[ $# -gt 0 ]]; do
    key="$1"
    case $key in
        # Check for the --profiling option
        --profiling)
            # The next argument ($2) should be the path to the agent
            if [ -n "$2" ]; then
                AGENT_PATH="-agentpath:$2"
                # Keep the --profiling flag to pass to the Java application
                JAVA_ARGS+=("$key")
                # Shift twice: once for the --profiling key, once for the path value
                shift 2
            else
                echo "Error: --profiling requires an argument (path to agent library)."
                exit 1
            fi
            ;;
        --jfr)
            DRIVER_LOGS="$DIR/../logs/driver"
            JFR_FILE="$DRIVER_LOGS/recording-$(date +%Y-%m-%d-%H:%M).jfr"
            JFR_OPTS="-XX:StartFlightRecording=filename=$JFR_FILE,settings=default,+org.infinispan.resiliency.CacheOperation#enabled=true"
            shift
            ;;
        # For all other arguments, add them to the JAVA_ARGS array
        *)
            JAVA_ARGS+=("$1")
            shift
            ;;
    esac
done

# --- Java Execution ---
FLAGS="$JAVA_OPTS -XX:+HeapDumpOnOutOfMemoryError -XX:+ExitOnOutOfMemoryError -XX:-RestrictContended --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED -Xmx4g -Dorg.infinispan.threads.virtual=true"
# DEBUG="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=*:8787"
# Note: $DEBUG is commented out in your original script, but included for completeness.

# Run the Java application, only including $AGENT_PATH if it was set
java $AGENT_PATH -cp "$CP" $LOG $DEBUG $FLAGS $JFR_OPTS org.infinispan.Main "${JAVA_ARGS[@]}"

# Generate the JFR report if recording was enabled
if [ -n "$JFR_FILE" ] && [ -f "$JFR_FILE" ]; then
    ln -sf "$(basename "$JFR_FILE")" "$DRIVER_LOGS/latest.jfr"
    REPORT_FILE="$DRIVER_LOGS/report-$(date +%Y-%m-%d-%H:%M).html"
    java -cp "$CP" org.infinispan.jfr.JfrReport "$JFR_FILE" "$REPORT_FILE"
fi
