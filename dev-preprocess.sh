#!/usr/bin/env bash
set -euo pipefail

JAR="target/turbo-1.0.0-SNAPSHOT.jar"

if [ ! -f "$JAR" ]; then
    echo "=== Building | Construction ==="
    mvn -q clean package -DskipTests
fi

java -Xmx16g -jar "$JAR" preprocess "$@"
