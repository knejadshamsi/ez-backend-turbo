#!/usr/bin/env bash
set -euo pipefail

DATA_ROOT="./dev-data"
JAR="target/turbo-1.0.0-SNAPSHOT.jar"
PORT=8080
HEALTH_TIMEOUT=30
LOG_FILE="dev-test-output.log"

echo "=== Killing existing Java processes ==="
taskkill //F //IM java.exe 2>/dev/null || true
sleep 1

echo "=== Cleaning dev artifacts ==="
rm -f "$DATA_ROOT/scenarios.mv.db" "$DATA_ROOT/scenarios.trace.db"
rm -rf "$DATA_ROOT/output/"
find "$DATA_ROOT/input" -name "*.mv.db" -delete 2>/dev/null || true

echo "=== Building ==="
mvn -q clean package -DskipTests

echo "=== Starting server ==="
java -jar "$JAR" --dev > "$LOG_FILE" 2>&1 &
SERVER_PID=$!
trap "kill $SERVER_PID 2>/dev/null; wait $SERVER_PID 2>/dev/null" EXIT

echo "=== Waiting for health (max ${HEALTH_TIMEOUT}s) ==="
for i in $(seq 1 "$HEALTH_TIMEOUT"); do
  if curl -sf "http://localhost:${PORT}/health" > /dev/null 2>&1; then
    echo "Server ready after ${i}s"
    break
  fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "ERROR: Server process died. Check $LOG_FILE"
    tail -20 "$LOG_FILE"
    exit 1
  fi
  sleep 1
done

if ! curl -sf "http://localhost:${PORT}/health" > /dev/null 2>&1; then
  echo "ERROR: Server did not become healthy within ${HEALTH_TIMEOUT}s"
  tail -30 "$LOG_FILE"
  exit 1
fi

echo "=== Sending simulation request ==="
curl -X POST "http://localhost:${PORT}/simulate" \
  -H "Content-Type: application/json" \
  -d @dev-data/dev-request.json \
  --no-buffer -s --max-time 120

echo ""
echo "=== Done. Log at $LOG_FILE ==="
