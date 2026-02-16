#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# Simulation Correctness Verification Pipeline
#
# Purpose: Validates that zone enforcement policies produced correct outcomes
#          in the MATSim simulation output. Run this AFTER dev-run.sh completes.
#
# What it does:
#   - Parses MATSim output (gzipped XML events, CSV scores, trip logs)
#   - Runs 7 groups of tests (A-G) covering enforcement, immunity, adaptation
#   - Classifies results as PASS (hard requirement met), FAIL (violation), or
#     INFO (observation — no pass/fail, behavior depends on agent decisions)
#   - Generates a markdown evidence report at dev-data/verification-report.md
#
# How it works:
#   - Reads vehicles.xml to map each person to their vehicle emission type
#   - Reads gzipped event files to find personMoney events (bans, fines)
#   - Reads gzipped trip CSVs to compare agent mode/route across iterations
#   - Reads gzipped person CSVs to compare scores between baseline and policy
#   - MATSim only writes event files for iteration 0 and the final iteration,
#     so behavioral comparison is always between those two
#
# Exit codes:
#   0 — all hard tests passed
#   1 — at least one hard test failed, or required files are missing
# =============================================================================

OUTPUT_ROOT="./dev-data/output"
REPORT_FILE="./dev-data/verification-report.md"
PASS=0
FAIL=0
INFO=0

pass() { echo "  PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL: $1"; FAIL=$((FAIL + 1)); }
info() { echo "  INFO: $1"; INFO=$((INFO + 1)); }

# =============================================================================
# SETUP
#
# Locates the simulation output directory (named by request UUID), resolves
# paths to all required files, discovers which iteration event/trip files
# exist, and builds the person-to-vehicle-type mapping from vehicles.xml.
#
# The vehicle type map is critical — it's how every subsequent test knows
# which tier (1/2/3) each agent belongs to, which determines whether they
# should be banned, fined, or exempt.
# =============================================================================

UUID_DIR=$(find "$OUTPUT_ROOT" -mindepth 1 -maxdepth 1 -type d | head -1)
if [ -z "$UUID_DIR" ]; then
  echo "No output directory found under $OUTPUT_ROOT"
  exit 1
fi
UUID=$(basename "$UUID_DIR")

BASELINE_EVENTS="$UUID_DIR/baseline/output_events.xml.gz"
POLICY_EVENTS="$UUID_DIR/policy/output_events.xml.gz"
VEHICLES_FILE="$UUID_DIR/vehicles.xml"
BASELINE_PERSONS="$UUID_DIR/baseline/output_persons.csv.gz"
POLICY_PERSONS="$UUID_DIR/policy/output_persons.csv.gz"

for f in "$BASELINE_EVENTS" "$POLICY_EVENTS" "$VEHICLES_FILE" "$BASELINE_PERSONS" "$POLICY_PERSONS"; do
  if [ ! -f "$f" ]; then
    echo "Missing required file: $f"
    exit 1
  fi
done

# Discover first and last iteration directories under policy/ITERS/
# MATSim writes events for iteration 0 (initial) and the final iteration
ITERS_DIR="$UUID_DIR/policy/ITERS"
FIRST_IT_EVENTS=""
LAST_IT_EVENTS=""
FIRST_IT=""
LAST_IT=""
if [ -d "$ITERS_DIR" ]; then
  FIRST_IT=$(ls "$ITERS_DIR" | sort -t. -k2 -n | head -1 | sed 's/it\.//')
  LAST_IT=$(ls "$ITERS_DIR" | sort -t. -k2 -n | tail -1 | sed 's/it\.//')
  FIRST_IT_EVENTS="$ITERS_DIR/it.${FIRST_IT}/${FIRST_IT}.events.xml.gz"
  LAST_IT_EVENTS="$ITERS_DIR/it.${LAST_IT}/${LAST_IT}.events.xml.gz"
fi

FIRST_IT_TRIPS="$ITERS_DIR/it.${FIRST_IT}/${FIRST_IT}.trips.csv.gz"
LAST_IT_TRIPS="$ITERS_DIR/it.${LAST_IT}/${LAST_IT}.trips.csv.gz"

# Build vehicle type map by parsing vehicles.xml
# Each <vehicle> element has id="<personId>_car" and type="<emissionType>"
# We strip the "_car" suffix to get the person ID as the map key
declare -A PERSON_VTYPE
while IFS= read -r line; do
  vid=$(echo "$line" | sed -n 's/.*id="\([^"]*\)".*/\1/p')
  vtype=$(echo "$line" | sed -n 's/.*type="\([^"]*\)".*/\1/p')
  if [ -n "$vid" ] && [ -n "$vtype" ]; then
    person="${vid%%_*}"
    PERSON_VTYPE["$person"]="$vtype"
  fi
done < <(grep '<vehicle ' "$VEHICLES_FILE" | grep -v 'vehicleType')

# Enforcement time window in seconds (matches dev-request.json policy periods)
# Update these if the request's period values change
BAN_START=25200   # 07:00
BAN_END=68400     # 19:00

# Helpers to extract personMoney events from gzipped XML
# "numbered" variant includes line numbers for the evidence report
extract_money_events() {
  gunzip -c "$1" | grep 'type="personMoney"' || true
}

extract_money_events_numbered() {
  gunzip -c "$1" | grep -n 'type="personMoney"' || true
}

echo "=== Simulation Verification Pipeline ==="
echo "Output: $UUID"
echo "Iterations: it.${FIRST_IT} to it.${LAST_IT}"
echo ""

# Initialize the markdown evidence report with header and vehicle type table
cat > "$REPORT_FILE" <<HEADER
# Verification Report

**Output ID**: \`$UUID\`
**Iterations**: it.${FIRST_IT} to it.${LAST_IT}
**Generated**: $(date -u +"%Y-%m-%dT%H:%M:%SZ")

## Vehicle Type Map

| Person | Vehicle | Type | Tier |
|--------|---------|------|------|
HEADER

for p in $(echo "${!PERSON_VTYPE[@]}" | tr ' ' '\n' | sort -n); do
  vt="${PERSON_VTYPE[$p]}"
  tier="?"
  case "$vt" in
    highEmission) tier="3 (BAN)" ;;
    midEmission) tier="2 (CONGESTION)" ;;
    *) tier="1 (EXEMPT)" ;;
  esac
  echo "| $p | ${p}_car | $vt | $tier |" >> "$REPORT_FILE"
done
echo "" >> "$REPORT_FILE"

# =============================================================================
# GROUP A — BASELINE INTEGRITY
#
# What: Confirms the baseline simulation (no zone policies) produced zero
#       personMoney events.
# Why:  The baseline run has no enforcement module installed. If personMoney
#       events appear, it means penalties leaked into the baseline, which would
#       contaminate the before/after comparison.
# How:  Greps baseline/output_events.xml.gz for type="personMoney".
#
# Hard test — any personMoney event in baseline is a FAIL.
# =============================================================================
echo "--- Group A: Baseline Integrity ---"
echo "## Group A: Baseline Integrity" >> "$REPORT_FILE"
echo "" >> "$REPORT_FILE"

BASELINE_MONEY=$(extract_money_events_numbered "$BASELINE_EVENTS")
if [ -z "$BASELINE_MONEY" ]; then
  pass "A1: Zero personMoney events in baseline"
  echo "**A1 PASS**: Zero personMoney events in \`baseline/output_events.xml.gz\`" >> "$REPORT_FILE"
else
  BASELINE_MONEY_COUNT=$(echo "$BASELINE_MONEY" | wc -l)
  fail "A1: Baseline has $BASELINE_MONEY_COUNT personMoney events (expected 0)"
  echo "**A1 FAIL**: Found $BASELINE_MONEY_COUNT personMoney events:" >> "$REPORT_FILE"
  echo '```' >> "$REPORT_FILE"
  echo "$BASELINE_MONEY" | head -5 >> "$REPORT_FILE"
  echo '```' >> "$REPORT_FILE"
fi
echo "" >> "$REPORT_FILE"
echo ""

# =============================================================================
# GROUP B — BAN ENFORCEMENT (TIER 3)
#
# What: Validates that Tier 3 (highEmission) vehicles received zone_ban
#       penalties, that bans targeted only the correct vehicle types, that the
#       penalty amount matches the code constant, and that bans only fired
#       within the configured time window.
# Why:  This is the core enforcement mechanism. If bans don't fire, target the
#       wrong vehicles, use the wrong amount, or fire outside the time window,
#       the entire policy simulation is broken.
# How:  Searches personMoney events with purpose="zone_ban". With multi-
#       iteration runs, agents may reroute by the final iteration (no bans in
#       final output), so we also check iteration 0 where bans first fire.
#       Cross-references each banned person against vehicles.xml to verify
#       their vehicle type. Parses the event timestamp and checks it falls
#       within [BAN_START, BAN_END).
#
# B1-B4 are all hard tests — any violation is a FAIL.
# =============================================================================
echo "--- Group B: Ban Enforcement (Tier 3) ---"
echo "## Group B: Ban Enforcement (Tier 3)" >> "$REPORT_FILE"
echo "" >> "$REPORT_FILE"

# First check the final iteration output, then fall back to iteration 0
# If bans exist in it.0 but not in the final, the agent rerouted (adapted)
FINAL_BAN_EVENTS=$(extract_money_events "$POLICY_EVENTS" | grep 'purpose="zone_ban"' || true)
FIRST_IT_BAN_EVENTS=""
BAN_SOURCE=""
BAN_EVENTS=""
REROUTED=false

if [ -n "$FINAL_BAN_EVENTS" ]; then
  BAN_EVENTS="$FINAL_BAN_EVENTS"
  BAN_SOURCE="policy/output_events.xml.gz (final)"
elif [ -f "$FIRST_IT_EVENTS" ]; then
  FIRST_IT_BAN_EVENTS=$(extract_money_events "$FIRST_IT_EVENTS" | grep 'purpose="zone_ban"' || true)
  if [ -n "$FIRST_IT_BAN_EVENTS" ]; then
    BAN_EVENTS="$FIRST_IT_BAN_EVENTS"
    BAN_SOURCE="policy/ITERS/it.${FIRST_IT}/${FIRST_IT}.events.xml.gz"
    REROUTED=true
  fi
fi

# B1: At least one ban event must exist somewhere across available iterations
if [ -n "$BAN_EVENTS" ]; then
  BAN_COUNT=$(echo "$BAN_EVENTS" | wc -l)
  if [ "$REROUTED" = true ]; then
    pass "B1: $BAN_COUNT ban event(s) in it.${FIRST_IT}, zero in final (agent adapted)"
    echo "**B1 PASS**: $BAN_COUNT ban event(s) found in it.${FIRST_IT}, zero in final iteration (agent adapted)" >> "$REPORT_FILE"
  else
    pass "B1: $BAN_COUNT ban event(s) in final iteration"
    echo "**B1 PASS**: $BAN_COUNT ban event(s) in final iteration" >> "$REPORT_FILE"
  fi
  echo "" >> "$REPORT_FILE"
  echo "Source: \`$BAN_SOURCE\`" >> "$REPORT_FILE"

  # Include the raw XML with line numbers as evidence
  if [ "$REROUTED" = true ]; then
    BAN_EVIDENCE=$(extract_money_events_numbered "$FIRST_IT_EVENTS" | grep 'purpose="zone_ban"' || true)
  else
    BAN_EVIDENCE=$(extract_money_events_numbered "$POLICY_EVENTS" | grep 'purpose="zone_ban"' || true)
  fi
  echo '```xml' >> "$REPORT_FILE"
  echo "$BAN_EVIDENCE" >> "$REPORT_FILE"
  echo '```' >> "$REPORT_FILE"
else
  fail "B1: Zero zone_ban events in any iteration"
  echo "**B1 FAIL**: Zero zone_ban events found in any available iteration" >> "$REPORT_FILE"
fi
echo "" >> "$REPORT_FILE"

# B2: Every ban event must target a highEmission (Tier 3) vehicle
# Cross-references the person ID in each ban event against the vehicle type map
if [ -n "$BAN_EVENTS" ]; then
  B2_OK=true
  while IFS= read -r event; do
    person=$(echo "$event" | sed -n 's/.*person="\([^"]*\)".*/\1/p')
    vtype="${PERSON_VTYPE[$person]:-unknown}"
    if [ "$vtype" = "highEmission" ]; then
      pass "B2: Ban on person $person (type: $vtype)"
      echo "**B2 PASS**: Ban on person $person — vehicle type \`$vtype\` (Tier 3)" >> "$REPORT_FILE"
    else
      fail "B2: Ban on person $person (type: $vtype) — expected highEmission"
      echo "**B2 FAIL**: Ban on person $person — vehicle type \`$vtype\` (NOT Tier 3)" >> "$REPORT_FILE"
      B2_OK=false
    fi
  done <<< "$BAN_EVENTS"
else
  info "B2: Skipped (no ban events)"
  echo "**B2 SKIP**: No ban events to validate" >> "$REPORT_FILE"
fi
echo "" >> "$REPORT_FILE"

# B3: The penalty amount must be exactly -10000.0
# This matches the BAN_PENALTY constant in ZoneEnforcementHandler.java:24
if [ -n "$BAN_EVENTS" ]; then
  while IFS= read -r event; do
    amount=$(echo "$event" | sed -n 's/.*amount="\([^"]*\)".*/\1/p')
    person=$(echo "$event" | sed -n 's/.*person="\([^"]*\)".*/\1/p')
    if [ "$amount" = "-10000.0" ]; then
      pass "B3: Person $person ban amount: $amount"
      echo "**B3 PASS**: Person $person penalty amount = \`$amount\` (matches BAN_PENALTY constant)" >> "$REPORT_FILE"
    else
      fail "B3: Person $person ban amount: $amount (expected -10000.0)"
      echo "**B3 FAIL**: Person $person penalty amount = \`$amount\` (expected \`-10000.0\`)" >> "$REPORT_FILE"
    fi
  done <<< "$BAN_EVENTS"
else
  info "B3: Skipped (no ban events)"
  echo "**B3 SKIP**: No ban events to validate" >> "$REPORT_FILE"
fi
echo "" >> "$REPORT_FILE"

# B4: Ban events must only occur within the configured enforcement window
# Parses the time attribute (in seconds since midnight), converts to HH:MM:SS,
# and checks it falls within [BAN_START, BAN_END)
if [ -n "$BAN_EVENTS" ]; then
  while IFS= read -r event; do
    time_val=$(echo "$event" | sed -n 's/.*time="\([^"]*\)".*/\1/p')
    person=$(echo "$event" | sed -n 's/.*person="\([^"]*\)".*/\1/p')
    time_int=${time_val%.*}
    hours=$((time_int / 3600))
    mins=$(( (time_int % 3600) / 60 ))
    secs=$((time_int % 60))
    timestamp=$(printf "%02d:%02d:%02d" "$hours" "$mins" "$secs")
    in_window=$(awk "BEGIN { print ($time_int >= $BAN_START && $time_int < $BAN_END) ? 1 : 0 }")
    if [ "$in_window" = "1" ]; then
      pass "B4: Person $person ban at ${timestamp} (${time_val}s) within 07:00-19:00"
      echo "**B4 PASS**: Person $person ban at \`${timestamp}\` (${time_val}s) — within window [07:00, 19:00)" >> "$REPORT_FILE"
    else
      fail "B4: Person $person ban at ${timestamp} (${time_val}s) OUTSIDE 07:00-19:00"
      echo "**B4 FAIL**: Person $person ban at \`${timestamp}\` (${time_val}s) — OUTSIDE window" >> "$REPORT_FILE"
    fi
  done <<< "$BAN_EVENTS"
else
  info "B4: Skipped (no ban events)"
  echo "**B4 SKIP**: No ban events to validate" >> "$REPORT_FILE"
fi
echo "" >> "$REPORT_FILE"
echo ""

# =============================================================================
# GROUP C — TIER 1 IMMUNITY
#
# What: Confirms that Tier 1 vehicles (lowEmission, nearZeroEmission,
#       zeroEmission) never received any personMoney events in any iteration.
# Why:  Tier 1 vehicles are exempt from all enforcement. If they receive any
#       penalty (ban or congestion charge), the enforcement handler has a bug
#       in its vehicle type filtering.
# How:  For each available iteration event file, greps for personMoney events
#       matching any Tier 1 person ID. Checks both first and last iteration.
#
# Hard test — any penalty on a Tier 1 vehicle is a FAIL.
# =============================================================================
echo "--- Group C: Tier 1 Immunity ---"
echo "## Group C: Tier 1 Immunity" >> "$REPORT_FILE"
echo "" >> "$REPORT_FILE"

TIER1_PERSONS=()
for p in "${!PERSON_VTYPE[@]}"; do
  vt="${PERSON_VTYPE[$p]}"
  if [ "$vt" = "lowEmission" ] || [ "$vt" = "nearZeroEmission" ] || [ "$vt" = "zeroEmission" ]; then
    TIER1_PERSONS+=("$p")
  fi
done

for it_file in "$FIRST_IT_EVENTS" "$LAST_IT_EVENTS"; do
  if [ ! -f "$it_file" ]; then continue; fi
  it_name=$(basename "$(dirname "$it_file")")
  it_money=$(extract_money_events "$it_file")
  for person in "${TIER1_PERSONS[@]}"; do
    vtype="${PERSON_VTYPE[$person]}"
    person_money=$(echo "$it_money" | grep "person=\"$person\"" || true)
    if [ -z "$person_money" ]; then
      pass "C1: Person $person ($vtype) has no penalties in $it_name"
      echo "**C1 PASS**: Person $person (\`$vtype\`) — zero penalties in \`$it_name\`" >> "$REPORT_FILE"
    else
      fail "C1: Person $person ($vtype) penalized in $it_name"
      echo "**C1 FAIL**: Person $person (\`$vtype\`) penalized in \`$it_name\`:" >> "$REPORT_FILE"
      echo '```xml' >> "$REPORT_FILE"
      echo "$person_money" >> "$REPORT_FILE"
      echo '```' >> "$REPORT_FILE"
    fi
  done
done
echo "" >> "$REPORT_FILE"
echo ""

# =============================================================================
# GROUP D — CONGESTION PRICING (TIER 2)
#
# What: Checks whether Tier 2 (midEmission) vehicles received congestion
#       pricing charges (purpose="zone_penalty"), and if so, validates they
#       only target Tier 2 vehicles.
# Why:  Congestion pricing is interval-based — the agent must enter through an
#       entry gateway AND exit through an exit gateway, spending enough time
#       inside the zone to accumulate charge intervals. If the agent's route
#       doesn't cross both gateways, or their trip is too short, no charge
#       fires. This is valid behavior, not a bug.
# How:  Searches personMoney events with purpose="zone_penalty" across all
#       available iterations. If found, validates vehicle types. If not found,
#       reports the Tier 2 agent's trip departure times to explain why (e.g.,
#       trip outside enforcement window, or route didn't cross gateways).
#
# D1-D2 are soft tests (INFO if no events, PASS/FAIL if events exist).
# D3 is purely informational — explains absence of congestion charges.
# =============================================================================
echo "--- Group D: Congestion Pricing (Tier 2) ---"
echo "## Group D: Congestion Pricing (Tier 2)" >> "$REPORT_FILE"
echo "" >> "$REPORT_FILE"

# Collect personMoney events from all available iteration files
ALL_POLICY_MONEY=""
for it_file in "$FIRST_IT_EVENTS" "$LAST_IT_EVENTS"; do
  if [ -f "$it_file" ]; then
    it_money=$(extract_money_events "$it_file")
    ALL_POLICY_MONEY=$(printf "%s\n%s" "$ALL_POLICY_MONEY" "$it_money")
  fi
done

PENALTY_EVENTS=$(echo "$ALL_POLICY_MONEY" | grep 'purpose="zone_penalty"' || true)

if [ -n "$PENALTY_EVENTS" ]; then
  PENALTY_COUNT=$(echo "$PENALTY_EVENTS" | wc -l)
  pass "D1: Found $PENALTY_COUNT zone_penalty event(s)"
  echo "**D1 PASS**: Found $PENALTY_COUNT zone_penalty event(s)" >> "$REPORT_FILE"

  # D2: Verify charges only hit Tier 2 vehicles
  D2_OK=true
  while IFS= read -r event; do
    person=$(echo "$event" | sed -n 's/.*person="\([^"]*\)".*/\1/p')
    amount=$(echo "$event" | sed -n 's/.*amount="\([^"]*\)".*/\1/p')
    vtype="${PERSON_VTYPE[$person]:-unknown}"
    if [ "$vtype" = "midEmission" ]; then
      pass "D2: Congestion charge on person $person ($vtype): $amount"
      echo "**D2 PASS**: Person $person (\`$vtype\`) charged \`$amount\`" >> "$REPORT_FILE"
    else
      fail "D2: Congestion charge on person $person ($vtype) — expected midEmission"
      echo "**D2 FAIL**: Person $person (\`$vtype\`) charged — expected midEmission only" >> "$REPORT_FILE"
      D2_OK=false
    fi
  done <<< "$PENALTY_EVENTS"
else
  info "D1: Zero zone_penalty events across available iterations"
  echo "**D1 INFO**: Zero zone_penalty events found" >> "$REPORT_FILE"
  echo "" >> "$REPORT_FILE"

  # D3: Explain why no congestion charges fired by showing trip timing
  # If the Tier 2 agent's trips depart outside the enforcement window, or
  # the route is too short to trigger an interval, no charge is expected
  echo "**D3**: Tier 2 trip timing analysis:" >> "$REPORT_FILE"
  for person in "${!PERSON_VTYPE[@]}"; do
    if [ "${PERSON_VTYPE[$person]}" = "midEmission" ]; then
      if [ -f "$FIRST_IT_TRIPS" ]; then
        echo "" >> "$REPORT_FILE"
        echo "Person $person (midEmission) trips in it.${FIRST_IT}:" >> "$REPORT_FILE"
        echo '```' >> "$REPORT_FILE"
        trips=$(gunzip -c "$FIRST_IT_TRIPS" | grep "^${person};")
        echo "$trips" >> "$REPORT_FILE"
        echo '```' >> "$REPORT_FILE"

        while IFS= read -r trip; do
          dep_time=$(echo "$trip" | cut -d';' -f4)
          dep_secs=$(echo "$dep_time" | awk -F: '{print $1*3600 + $2*60 + $3}')
          in_window=$(awk "BEGIN { print ($dep_secs >= $BAN_START && $dep_secs < $BAN_END) ? \"WITHIN\" : \"OUTSIDE\" }")
          info "D3: Person $person trip departs $dep_time ($dep_secs s) — $in_window enforcement window"
          echo "  - Departs \`$dep_time\` (${dep_secs}s) — **$in_window** enforcement window [07:00, 19:00)" >> "$REPORT_FILE"
        done <<< "$trips"
      fi
    fi
  done
fi
echo "" >> "$REPORT_FILE"
echo ""

# =============================================================================
# GROUP E — BEHAVIORAL ADAPTATION
#
# What: Compares each agent's travel mode (car, bike, walk, pt) and trip
#       distance between the first and last iteration to detect behavioral
#       changes caused by enforcement pressure.
# Why:  MATSim agents learn over iterations. A banned agent should eventually
#       switch mode (car → bike) or reroute (longer distance to avoid zone).
#       This test proves enforcement actually changed agent behavior, not just
#       added a penalty to the score.
# How:  Parses trips.csv.gz from it.0 and the final iteration. Extracts the
#       main_mode (column 9) and traveled_distance (column 7) for each agent.
#       Compares across iterations: same mode + same distance = unchanged,
#       different mode = MODE SWITCH, same mode + different distance = REROUTE.
#       E3 specifically checks that the banned (highEmission) agent adapted.
#
# Soft tests — reports observations. E3 is a PASS if the banned agent changed.
# =============================================================================
echo "--- Group E: Behavioral Adaptation ---"
echo "## Group E: Behavioral Adaptation" >> "$REPORT_FILE"
echo "" >> "$REPORT_FILE"

if [ -f "$FIRST_IT_TRIPS" ] && [ -f "$LAST_IT_TRIPS" ] && [ "$FIRST_IT" != "$LAST_IT" ]; then
  echo "| Person | Type | It.${FIRST_IT} Mode | It.${FIRST_IT} Dist | It.${LAST_IT} Mode | It.${LAST_IT} Dist | Changed? |" >> "$REPORT_FILE"
  echo "|--------|------|----------|----------|----------|----------|----------|" >> "$REPORT_FILE"

  # E1/E2: Compare every agent's mode and distance across iterations
  for person in $(echo "${!PERSON_VTYPE[@]}" | tr ' ' '\n' | sort -n); do
    vtype="${PERSON_VTYPE[$person]}"
    first_trips=$(gunzip -c "$FIRST_IT_TRIPS" | grep "^${person};" || true)
    last_trips=$(gunzip -c "$LAST_IT_TRIPS" | grep "^${person};" || true)

    if [ -z "$first_trips" ] || [ -z "$last_trips" ]; then
      info "E1: Person $person — missing trip data"
      continue
    fi

    first_modes=$(echo "$first_trips" | cut -d';' -f9 | sort -u | tr '\n' ',' | sed 's/,$//')
    last_modes=$(echo "$last_trips" | cut -d';' -f9 | sort -u | tr '\n' ',' | sed 's/,$//')
    first_dists=$(echo "$first_trips" | cut -d';' -f7 | tr '\n' ',' | sed 's/,$//')
    last_dists=$(echo "$last_trips" | cut -d';' -f7 | tr '\n' ',' | sed 's/,$//')

    changed="No"
    if [ "$first_modes" != "$last_modes" ]; then
      changed="MODE SWITCH"
    elif [ "$first_dists" != "$last_dists" ]; then
      changed="REROUTE"
    fi

    echo "  Person $person ($vtype): it.${FIRST_IT}=$first_modes it.${LAST_IT}=$last_modes — $changed"
    echo "| $person | $vtype | $first_modes | $first_dists | $last_modes | $last_dists | $changed |" >> "$REPORT_FILE"

    if [ "$changed" = "MODE SWITCH" ]; then
      info "E1: Person $person ($vtype) switched mode: $first_modes → $last_modes"
    elif [ "$changed" = "REROUTE" ]; then
      info "E2: Person $person ($vtype) rerouted: distance $first_dists → $last_dists"
    else
      info "E1: Person $person ($vtype) unchanged"
    fi
  done

  echo "" >> "$REPORT_FILE"

  # E3: Specifically check that the banned (highEmission) agent adapted
  # This is the most important behavioral test — the agent who got hit with
  # -10000 in it.0 should have changed their behavior by the final iteration
  echo "### E3: Banned Agent Adaptation" >> "$REPORT_FILE"
  echo "" >> "$REPORT_FILE"
  for person in "${!PERSON_VTYPE[@]}"; do
    if [ "${PERSON_VTYPE[$person]}" = "highEmission" ]; then
      first_modes=$(gunzip -c "$FIRST_IT_TRIPS" | grep "^${person};" | cut -d';' -f9 | sort -u | tr '\n' ',' | sed 's/,$//')
      last_modes=$(gunzip -c "$LAST_IT_TRIPS" | grep "^${person};" | cut -d';' -f9 | sort -u | tr '\n' ',' | sed 's/,$//')
      if [ "$first_modes" != "$last_modes" ]; then
        pass "E3: Person $person (highEmission) adapted: $first_modes → $last_modes"
        echo "**E3 PASS**: Person $person adapted after ban — mode \`$first_modes\` → \`$last_modes\`" >> "$REPORT_FILE"
      else
        first_dists=$(gunzip -c "$FIRST_IT_TRIPS" | grep "^${person};" | cut -d';' -f7 | tr '\n' ',' | sed 's/,$//')
        last_dists=$(gunzip -c "$LAST_IT_TRIPS" | grep "^${person};" | cut -d';' -f7 | tr '\n' ',' | sed 's/,$//')
        if [ "$first_dists" != "$last_dists" ]; then
          pass "E3: Person $person (highEmission) rerouted: $first_dists → $last_dists"
          echo "**E3 PASS**: Person $person rerouted after ban — distances \`$first_dists\` → \`$last_dists\`" >> "$REPORT_FILE"
        else
          info "E3: Person $person (highEmission) did not change behavior"
          echo "**E3 INFO**: Person $person did not change behavior between iterations" >> "$REPORT_FILE"
        fi
      fi
    fi
  done
else
  info "E: Single iteration — no behavioral comparison possible"
  echo "**E INFO**: Single iteration run — no behavioral comparison possible" >> "$REPORT_FILE"
fi
echo "" >> "$REPORT_FILE"
echo ""

# =============================================================================
# GROUP F — TIME WINDOW ENFORCEMENT
#
# What: Validates that enforcement only fires within the configured time window
#       and that trips occurring outside the window are left untouched.
# Why:  The zone policies specify a period (e.g., 07:00-19:00). Enforcement
#       must respect this boundary. Penalizing agents outside the window would
#       be incorrect — a vehicle driving through the zone at 06:00 should not
#       be fined or banned.
# How:  F1 scans ALL personMoney events across available iterations and flags
#       any with a timestamp outside [BAN_START, BAN_END). This is a hard test.
#       F2 finds trips that depart outside the window and confirms they received
#       no penalties — this is informational, proving the boundary works.
#
# F1 is a hard test. F2 is informational.
# =============================================================================
echo "--- Group F: Time Window Enforcement ---"
echo "## Group F: Time Window Enforcement" >> "$REPORT_FILE"
echo "" >> "$REPORT_FILE"

# F1: Scan every personMoney event and flag any outside the time window
F1_VIOLATIONS=0
for it_file in "$FIRST_IT_EVENTS" "$LAST_IT_EVENTS"; do
  if [ ! -f "$it_file" ]; then continue; fi
  it_name=$(basename "$(dirname "$it_file")")
  it_money=$(extract_money_events "$it_file")
  if [ -z "$it_money" ]; then continue; fi

  while IFS= read -r event; do
    time_val=$(echo "$event" | sed -n 's/.*time="\([^"]*\)".*/\1/p')
    time_int=${time_val%.*}
    outside=$(awk "BEGIN { print ($time_int < $BAN_START || $time_int >= $BAN_END) ? 1 : 0 }")
    if [ "$outside" = "1" ]; then
      person=$(echo "$event" | sed -n 's/.*person="\([^"]*\)".*/\1/p')
      purpose=$(echo "$event" | sed -n 's/.*purpose="\([^"]*\)".*/\1/p')
      hours=$((time_int / 3600))
      mins=$(( (time_int % 3600) / 60 ))
      timestamp=$(printf "%02d:%02d" "$hours" "$mins")
      fail "F1: Person $person $purpose at $timestamp — OUTSIDE window ($it_name)"
      echo "**F1 FAIL**: Person $person \`$purpose\` at \`$timestamp\` (${time_val}s) — outside [07:00, 19:00) in \`$it_name\`" >> "$REPORT_FILE"
      F1_VIOLATIONS=$((F1_VIOLATIONS + 1))
    fi
  done <<< "$it_money"
done

if [ "$F1_VIOLATIONS" -eq 0 ]; then
  pass "F1: All enforcement events are within the time window"
  echo "**F1 PASS**: All personMoney events occur within [07:00, 19:00)" >> "$REPORT_FILE"
fi
echo "" >> "$REPORT_FILE"

# F2: Find trips departing outside the enforcement window and confirm they
# were not penalized — this proves the time boundary is correctly enforced
echo "### F2: Trips Outside Window" >> "$REPORT_FILE"
echo "" >> "$REPORT_FILE"
if [ -f "$FIRST_IT_TRIPS" ]; then
  for person in $(echo "${!PERSON_VTYPE[@]}" | tr ' ' '\n' | sort -n); do
    trips=$(gunzip -c "$FIRST_IT_TRIPS" | grep "^${person};" || true)
    if [ -z "$trips" ]; then continue; fi
    while IFS= read -r trip; do
      dep_time=$(echo "$trip" | cut -d';' -f4)
      dep_secs=$(echo "$dep_time" | awk -F: '{print $1*3600 + $2*60 + $3}')
      outside=$(awk "BEGIN { print ($dep_secs < $BAN_START || $dep_secs >= $BAN_END) ? 1 : 0 }")
      if [ "$outside" = "1" ]; then
        mode=$(echo "$trip" | cut -d';' -f9)
        it_money=$(extract_money_events "$FIRST_IT_EVENTS")
        person_money_near=""
        if [ -n "$it_money" ]; then
          person_money_near=$(echo "$it_money" | grep "person=\"$person\"" || true)
        fi
        if [ -z "$person_money_near" ]; then
          info "F2: Person $person departs $dep_time (outside window) via $mode — no penalty (correct)"
          echo "- Person $person departs \`$dep_time\` (outside window) via \`$mode\` — no penalty applied (correct)" >> "$REPORT_FILE"
        fi
      fi
    done <<< "$trips"
  done
fi
echo "" >> "$REPORT_FILE"
echo ""

# =============================================================================
# GROUP G — SCORE ANALYSIS
#
# What: Compares each agent's executed_score between the baseline and policy
#       simulation runs.
# Why:  Scores quantify the overall impact of zone policies on each agent.
#       A banned agent who adapted should have a score close to baseline (they
#       found an alternative). Tier 1 agents should be unaffected (delta ≈ 0).
#       Large unexpected deltas may indicate a bug.
# How:  Parses output_persons.csv.gz (semicolon-delimited, score in column 2)
#       from both baseline/ and policy/ directories. Computes the delta and
#       classifies each agent's outcome based on their tier:
#       - highEmission: if rerouted, score should be close to baseline;
#         if not rerouted, score should have dropped from the ban penalty
#       - Tier 1: delta should be near zero (no enforcement applied)
#       - midEmission: delta reported as-is (depends on congestion charges)
#
# Soft tests — all results are INFO, no FAIL possible.
# =============================================================================
echo "--- Group G: Score Analysis ---"
echo "## Group G: Score Analysis" >> "$REPORT_FILE"
echo "" >> "$REPORT_FILE"

echo "| Person | Type | Baseline Score | Policy Score | Delta | Assessment |" >> "$REPORT_FILE"
echo "|--------|------|---------------|-------------|-------|------------|" >> "$REPORT_FILE"

for person in $(echo "${!PERSON_VTYPE[@]}" | tr ' ' '\n' | sort -n); do
  vtype="${PERSON_VTYPE[$person]}"
  baseline_score=$(gunzip -c "$BASELINE_PERSONS" | grep "^$person;" | cut -d';' -f2)
  policy_score=$(gunzip -c "$POLICY_PERSONS" | grep "^$person;" | cut -d';' -f2)

  if [ -z "$baseline_score" ] || [ -z "$policy_score" ]; then
    info "G1: Person $person — missing score data"
    continue
  fi

  delta=$(awk "BEGIN { printf \"%.2f\", $policy_score - $baseline_score }")
  abs_delta=$(awk "BEGIN { d = $policy_score - $baseline_score; printf \"%.2f\", (d < 0) ? -d : d }")

  assessment=""
  case "$vtype" in
    highEmission)
      # If the agent rerouted, their score should be comparable to baseline
      # (they avoided the penalty by changing behavior). If they didn't reroute,
      # their score should have dropped by roughly the ban penalty amount.
      if [ "$REROUTED" = true ]; then
        is_similar=$(awk "BEGIN { print ($abs_delta < 10) ? 1 : 0 }")
        if [ "$is_similar" = "1" ]; then
          assessment="Adapted (score comparable to baseline)"
          info "G1: Person $person ($vtype) score delta: $delta (adapted successfully)"
        else
          assessment="Large score change: $delta"
          info "G1: Person $person ($vtype) score delta: $delta"
        fi
      else
        is_lower=$(awk "BEGIN { print ($policy_score < $baseline_score) ? 1 : 0 }")
        if [ "$is_lower" = "1" ]; then
          assessment="Penalized (score dropped $delta)"
          info "G1: Person $person ($vtype) penalized, score dropped $delta"
        else
          assessment="Unexpected: score did not drop"
          info "G1: Person $person ($vtype) score did not drop: $delta"
        fi
      fi
      ;;
    lowEmission|nearZeroEmission|zeroEmission)
      # Tier 1 agents have no enforcement — their scores should be nearly
      # identical between baseline and policy (small variance from stochastic
      # replanning is acceptable, but large deltas suggest a problem)
      is_similar=$(awk "BEGIN { print ($abs_delta < 10) ? 1 : 0 }")
      if [ "$is_similar" = "1" ]; then
        assessment="Stable (Tier 1, expected)"
        info "G2: Person $person ($vtype) score stable, delta: $delta"
      else
        assessment="Unexpected large delta: $delta"
        info "G2: Person $person ($vtype) unexpected score delta: $delta"
      fi
      ;;
    midEmission)
      # Tier 2 scores depend on whether congestion charges fired
      assessment="Delta: $delta"
      info "G1: Person $person ($vtype) score delta: $delta"
      ;;
  esac

  echo "  Person $person ($vtype): baseline=$baseline_score policy=$policy_score delta=$delta"
  echo "| $person | $vtype | $baseline_score | $policy_score | $delta | $assessment |" >> "$REPORT_FILE"
done
echo "" >> "$REPORT_FILE"
echo ""

# =============================================================================
# SUMMARY
# =============================================================================
echo "==========================================="
echo "Results: $PASS passed, $FAIL failed, $INFO info"
echo "" >> "$REPORT_FILE"
echo "---" >> "$REPORT_FILE"
echo "" >> "$REPORT_FILE"
echo "## Summary" >> "$REPORT_FILE"
echo "" >> "$REPORT_FILE"
echo "**$PASS passed, $FAIL failed, $INFO info**" >> "$REPORT_FILE"

if [ "$FAIL" -gt 0 ]; then
  echo "VERIFICATION FAILED"
  echo "" >> "$REPORT_FILE"
  echo "**VERIFICATION FAILED**" >> "$REPORT_FILE"
  exit 1
else
  echo "VERIFICATION PASSED"
  echo "" >> "$REPORT_FILE"
  echo "**VERIFICATION PASSED**" >> "$REPORT_FILE"
fi

echo ""
echo "Evidence report: $REPORT_FILE"
