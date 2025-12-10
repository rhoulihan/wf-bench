#!/bin/bash
# WF Benchmark Tool launcher script

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR_FILE="$SCRIPT_DIR/target/wf-bench-1.0.0-SNAPSHOT.jar"

# Check if JAR exists
if [ ! -f "$JAR_FILE" ]; then
    echo "Error: JAR file not found at $JAR_FILE"
    echo "Please run 'mvn package' first"
    exit 1
fi

# Run with enable-preview flag for Java 23 features
java --enable-preview -jar "$JAR_FILE" "$@"
