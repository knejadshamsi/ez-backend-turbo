#!/usr/bin/env bash
set -euo pipefail

echo "Compiling DevDataGenerator..."
mvn -q compile test-compile

echo "Running DevDataGenerator..."
mvn -q exec:java -Dexec.mainClass="ez.backend.turbo.DevDataGenerator" -Dexec.classpathScope="test"

echo "Done."
