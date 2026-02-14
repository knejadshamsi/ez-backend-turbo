#!/usr/bin/env bash
set -euo pipefail

mvn -q clean package -DskipTests
java -jar target/*.jar --dev &
SERVER_PID=$!
trap "kill $SERVER_PID 2>/dev/null" EXIT

until curl -sf http://localhost:8080/health > /dev/null 2>&1; do sleep 1; done

curl -X POST http://localhost:8080/simulate \
  -H "Content-Type: application/json" \
  -d @dev-data/dev-request.json \
  --no-buffer
