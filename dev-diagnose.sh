#!/usr/bin/env bash
set -euo pipefail

JAR="target/turbo-1.0.0-SNAPSHOT.jar"

if [ ! -f "$JAR" ]; then
  echo "JAR not found at $JAR — run 'mvn package -DskipTests' first"
  exit 1
fi

echo "=== 1. Check application.properties in JAR ==="
unzip -l "$JAR" 2>/dev/null | grep "application.*properties" || echo "NONE FOUND"

echo ""
echo "=== 2. Dump application.properties from JAR ==="
unzip -p "$JAR" "BOOT-INF/classes/application.properties" 2>/dev/null | head -10 || echo "COULD NOT EXTRACT"

echo ""
echo "=== 3. Start app with --debug to see property sources ==="
timeout 20 java -jar "$JAR" --dev --debug 2>&1 \
  | grep -iE "(PropertySource|application\.properties|config.location|ez\.log\.locale|ez\.data\.root)" \
  || echo "No matching debug output found"

echo ""
echo "=== Done ==="
