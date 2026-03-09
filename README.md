# EZ Backend Turbo

Spring Boot backend for zero-emission zone policy analysis on the Island of Montreal. Integrates MATSim 2025.0 for agent-based traffic simulation, comparing a baseline scenario against user-defined zone policies to measure impacts on emissions, modal shift, and trip performance.

Serves a React frontend ([ez-frontend-react](https://github.com/knejadshamsi/ez-frontend-react)) over SSE and REST.

## Requirements

- Java 21
- Maven 3.9+

## Quick Start (Dev Mode)

```bash
mvn clean package -DskipTests
java -jar target/turbo-1.0.0-SNAPSHOT.jar --dev
```

Dev mode uses `./dev-data` as the data root. The server starts on port 8080 and expects the frontend at `http://localhost:3000`.

Alternatively, `bash dev-run.sh` builds, starts the server, and fires a sample simulation request in one step.

## Production Mode

Provide a configuration file that overrides `application.properties` defaults:

```bash
java -jar target/turbo-1.0.0-SNAPSHOT.jar config.yml
```

The config file is loaded via Spring's `--spring.config.additional-location`. All `ez.*` properties from `application.properties` can be overridden.

## Preprocessing CLI

The JAR doubles as a preprocessing tool for converting raw geospatial data into MATSim-ready inputs:

```bash
java -jar target/turbo-1.0.0-SNAPSHOT.jar preprocess network   --geojson roads.geojson --output network.xml --crs EPSG:32188
java -jar target/turbo-1.0.0-SNAPSHOT.jar preprocess population --geojson od.geojson    --output plans.xml   --crs EPSG:32188
java -jar target/turbo-1.0.0-SNAPSHOT.jar preprocess transit    --gtfs gtfs/            --output-schedule schedule.xml --output-vehicles vehicles.xml
```

## Testing

```bash
mvn clean verify
```

Packages the JAR, starts the server as a subprocess, runs a full simulation end-to-end, validates all SSE messages and REST endpoints, then tears down. Integration tests use Maven Failsafe (`*IT.java`).

After a dev simulation completes, `bash dev-verify.sh` validates zone enforcement correctness against the MATSim output and generates a report at `dev-data/verification-report.md`.

## How It Works

A simulation request defines **zones** (polygons on the map with tiered vehicle policies), **simulation areas** (the geographic scope), **data sources** (network, population, transit by year), and **parameters** (iterations, sample percentage, vehicle fleet mix, mode utilities).

The pipeline:

1. **Validate** the request (geometry, sources, options, policies)
2. **Filter** the population to agents whose trips intersect the simulation areas
3. **Sample** to the requested percentage and assign vehicles by fleet distribution
4. **Prepare** transit (GTFS-derived schedules, vehicle ID prefixing for bus/subway distinction)
5. **Run baseline** MATSim simulation (no policies)
6. **Run policy** MATSim simulation (zone enforcement active) in parallel once baseline initializes
7. **Post-process** results into three output sections, streamed over SSE as they complete

### Zone Policy Tiers

| Tier | Effect | MATSim Mechanism |
|------|--------|------------------|
| 1 | Exempt | No enforcement, vehicle allowed |
| 2 | Congestion pricing | `PersonMoneyEvent` charges penalty per interval while on zone links |
| 3 | Ban | Infinite travel disutility on zone links + forced innovation each iteration |

Forced innovation cycles ban-violating agents through mode change, reroute, and time shift strategies to push them off banned links.

### Output Sections

**Section 1 — Emissions:** CO2, NOx, PM2.5, PM10 totals and deltas (private scaled by sample fraction, transit at 100%). Time-binned line chart, stacked bar by vehicle type, warm/cold split, emission intensity per km, and per-link heatmap data.

**Section 2 — People's Response:** Classifies each trip as mode shift, rerouted, paid penalty, cancelled, or no change. Sankey diagram of mode flows, modal split bar chart, and origin/destination scatter maps by response category.

**Section 3 — Trip Performance:** Per-trip CO2 and travel time deltas between baseline and policy. Quadrant analysis (win-win, lose-lose, environment vs. personal cost). Paginated trip leg table stored in H2 for REST retrieval. Arc-based map visualization per trip.

## API

All REST responses use the standard envelope:

```json
{ "statusCode": 200, "message": "...", "payload": { }, "timestamp": "..." }
```

SSE endpoints stream raw JSON messages (no envelope) with `messageType`, `payload`, and `timestamp` fields.

| Method | Path | Type | Purpose |
|--------|------|------|---------|
| GET | `/health` | REST | Health check |
| POST | `/simulate` | SSE | Submit simulation, stream progress and results |
| GET | `/scenario/{id}` | SSE | Reconnect to scenario (replay if completed) |
| POST | `/scenario/{id}/cancel` | REST | Cancel running simulation |
| POST | `/scenario/{id}/retry` | REST | Re-serve a specific output component |
| DELETE | `/scenario/{id}` | REST | Delete scenario and clean up data |
| POST | `/scenario/{id}/session-data` | REST | Store frontend session state |
| GET | `/scenario/{id}/status` | REST | Poll scenario status and progress |
| GET | `/scenario/{id}/maps/emissions` | REST | Emission heatmap GeoJSON |
| GET | `/scenario/{id}/maps/people-response` | REST | Response category scatter GeoJSON |
| GET | `/scenario/{id}/maps/trip-legs` | REST | Trip arc GeoJSON |
| GET | `/scenario/{id}/trip-legs` | REST | Paginated trip leg table |
| POST | `/draft` | REST | Save draft |
| GET | `/draft/{id}` | REST | Load draft |
| DELETE | `/draft/{id}` | REST | Delete draft |

## Project Structure

```
src/main/java/ez/backend/turbo/
  config/        Cross-cutting: CORS, rate limiting, logging, startup validation, exception handling
  endpoints/     REST controllers and request DTOs
  database/      H2 repositories, H2GIS spatial database management
  services/      Business logic: simulation orchestration, process management, queueing, source registry
  simulation/    MATSim integration: config builder, runner, zone enforcement, emission tracking
  output/        Post-processing: emission aggregation, response classification, trip analysis
  preprocess/    CLI: network, population, transit preprocessing from raw data
  validation/    Request validation with typed error enums
  sse/           SSE message sending and heartbeat scheduling
  session/       SSE connection tracking
  utils/         Shared types, locale loader, response formatting
```

## Configuration

All configuration options with defaults and inline documentation are in [`src/main/resources/application.properties`](src/main/resources/application.properties). This is the single reference for every `ez.*` property — server tuning, scoring, strategy weights, emission parameters, process limits, and more.

In production, provide a config file that overrides any subset of these defaults. In dev mode (`--dev`), only `ez.data.root` is overridden to `./dev-data`.

## Data Directory Layout

```
<ez.data.root>/
  db/                          Auto-created on startup
  input/
    hbefa/                     Emission factor CSVs and vehicle type definitions
    network/<year>/<name>.xml  MATSim network files
    population/<year>/<name>.xml  MATSim population plans
    publicTransport/<year>/<name>.xml  Transit schedules and vehicles
  output/<request-id>/         Simulation results (per scenario)
  scenarios.mv.db              H2 database for scenario tracking
```

## Tech Stack

- **Spring Boot 3.4.13** (Web, JDBC, Log4j2)
- **MATSim 2025.0** (core + emissions contrib + pt2matsim)
- **H2** embedded database with **H2GIS** spatial extension
- **Java 21**
