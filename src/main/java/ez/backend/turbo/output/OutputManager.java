package ez.backend.turbo.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import ez.backend.turbo.config.StartupValidator;
import ez.backend.turbo.database.TripLegRepository;
import ez.backend.turbo.database.TripLegRepository.TripLegRecord;
import ez.backend.turbo.endpoints.SimulationRequest;
import ez.backend.turbo.output.EmissionsAggregator.EmissionsResult;
import ez.backend.turbo.output.ResponseAnalyzer.ResponseConfig;
import ez.backend.turbo.output.ResponseAnalyzer.ResponseResult;
import ez.backend.turbo.services.ScenarioStateService;
import ez.backend.turbo.services.SourceRegistry;
import ez.backend.turbo.simulation.MatsimRunner.SimulationResult;
import ez.backend.turbo.sse.SseMessageSender;
import ez.backend.turbo.utils.L;
import ez.backend.turbo.utils.MessageType;
import ez.backend.turbo.utils.ScenarioStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Network;
import org.matsim.vehicles.Vehicles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class OutputManager {

    private static final Logger log = LogManager.getLogger(OutputManager.class);

    private final SseMessageSender messageSender;
    private final ScenarioStateService scenarioStateService;
    private final ObjectMapper objectMapper;
    private final SourceRegistry sourceRegistry;
    private final TripLegRepository tripLegRepository;
    private final Path dataRoot;
    private final String targetCrs;
    private final String fleetMetric;
    private final double mixingHeightMeters;
    private final boolean excludeNonCarAgents;
    private final double rerouteThresholdMeters;
    private final String multiModalPt;
    private final double subwayFactorGpkm;
    private final String tripLegsScope;

    public OutputManager(SseMessageSender messageSender,
                         ScenarioStateService scenarioStateService,
                         ObjectMapper objectMapper,
                         StartupValidator startupValidator,
                         SourceRegistry sourceRegistry,
                         TripLegRepository tripLegRepository,
                         @Value("${ez.target.crs}") String targetCrs,
                         @Value("${ez.output.fleet-metric}") String fleetMetric,
                         @Value("${ez.emissions.mixing-height-meters}") double mixingHeightMeters,
                         @Value("${ez.response.exclude-non-car-agents}") boolean excludeNonCarAgents,
                         @Value("${ez.response.reroute-threshold-meters}") double rerouteThresholdMeters,
                         @Value("${ez.response.multi-modal-pt}") String multiModalPt,
                         @Value("${ez.emissions.subway-factor-gpkm}") double subwayFactorGpkm,
                         @Value("${ez.output.trip-legs-scope}") String tripLegsScope) {
        this.messageSender = messageSender;
        this.scenarioStateService = scenarioStateService;
        this.objectMapper = objectMapper;
        this.sourceRegistry = sourceRegistry;
        this.tripLegRepository = tripLegRepository;
        this.dataRoot = startupValidator.getDataRoot();
        this.targetCrs = targetCrs;
        this.fleetMetric = fleetMetric;
        this.mixingHeightMeters = mixingHeightMeters;
        this.excludeNonCarAgents = excludeNonCarAgents;
        this.rerouteThresholdMeters = rerouteThresholdMeters;
        this.multiModalPt = multiModalPt;
        this.subwayFactorGpkm = subwayFactorGpkm;
        this.tripLegsScope = tripLegsScope;
    }

    public void processOutput(UUID requestId, SseEmitter emitter,
                               SimulationRequest request, Vehicles vehicles,
                               int personCount, int networkNodes, int networkLinks,
                               double simulationAreaKm2,
                               SimulationResult baselineResult,
                               SimulationResult policyResult) {
        Path outputDir = dataRoot.resolve("output").resolve(requestId.toString());
        Path baselineDir = baselineResult.outputDir();
        Path policyDir = policyResult.outputDir();
        boolean anySucceeded = false;

        // Step 1: Overview
        anySucceeded |= processOverview(emitter, outputDir, policyDir,
                personCount, networkNodes, networkLinks, simulationAreaKm2);

        // Step 2: Emissions
        anySucceeded |= processEmissions(emitter, outputDir, baselineDir, policyDir,
                request, vehicles, simulationAreaKm2);

        // Step 3: Response analysis + trip legs
        anySucceeded |= processResponse(requestId, emitter, outputDir, baselineDir, policyDir,
                request, baselineResult, policyResult);

        // Step 4: Completion
        if (anySucceeded) {
            scenarioStateService.updateStatus(requestId, ScenarioStatus.COMPLETED);
            messageSender.sendLifecycle(emitter, MessageType.SUCCESS_PROCESS);
        } else {
            scenarioStateService.updateStatus(requestId, ScenarioStatus.FAILED);
            messageSender.sendError(emitter, MessageType.ERROR_GLOBAL,
                    "ALL_COMPONENTS_FAILED", L.msg("output.all.failed"));
        }
        messageSender.complete(emitter);
    }

    private boolean processOverview(SseEmitter emitter, Path outputDir, Path policyDir,
                                     int personCount, int networkNodes, int networkLinks,
                                     double simulationAreaKm2) {
        try {
            log.info(L.msg("output.stage.overview"));
            Map<String, Object> overview = OverviewExtractor.extract(
                    policyDir, personCount, networkNodes, networkLinks, simulationAreaKm2);
            writeJson(outputDir.resolve("overview.json"), overview);
            messageSender.sendMessage(emitter, MessageType.DATA_TEXT_OVERVIEW, overview);
            log.info(L.msg("output.overview.written"),
                    overview.get("personCount"), overview.get("legCount"), overview.get("totalKmTraveled"));
            return true;
        } catch (Exception e) {
            log.error("{}: {}", L.msg("output.component.failed"), e.getMessage(), e);
            messageSender.sendError(emitter, MessageType.ERROR_GLOBAL,
                    "OVERVIEW_ERROR", e.getMessage());
            return false;
        }
    }

    private boolean processEmissions(SseEmitter emitter, Path outputDir,
                                      Path baselineDir, Path policyDir,
                                      SimulationRequest request, Vehicles vehicles,
                                      double simulationAreaKm2) {
        try {
            log.info(L.msg("output.stage.modescan"));
            double modeShiftPercentage = computeModeShift(baselineDir, policyDir);

            log.info(L.msg("output.stage.emissions"));
            int netYear = request.getSources().getNetwork().getYear();
            String netName = request.getSources().getNetwork().getName();
            Network network = sourceRegistry.getNetwork(netYear, netName);

            EmissionsResult emissions = EmissionsAggregator.aggregate(
                    baselineDir, policyDir, vehicles, network,
                    request.getCarDistribution(), modeShiftPercentage,
                    simulationAreaKm2, targetCrs, fleetMetric, mixingHeightMeters);

            Map<String, Object> emissionsJson = new LinkedHashMap<>();
            emissionsJson.put("paragraph1", emissions.paragraph1());
            emissionsJson.put("paragraph2", emissions.paragraph2());
            emissionsJson.put("barChart", emissions.barChart());
            emissionsJson.put("pieChart", emissions.pieChart());
            writeJson(outputDir.resolve("emissions.json"), emissionsJson);

            messageSender.sendMessage(emitter, MessageType.DATA_TEXT_PARAGRAPH1_EMISSIONS, emissions.paragraph1());
            messageSender.sendMessage(emitter, MessageType.DATA_TEXT_PARAGRAPH2_EMISSIONS, emissions.paragraph2());
            messageSender.sendMessage(emitter, MessageType.DATA_CHART_BAR_EMISSIONS, emissions.barChart());
            messageSender.sendMessage(emitter, MessageType.DATA_CHART_PIE_EMISSIONS, emissions.pieChart());

            try {
                writeJson(outputDir.resolve("map-emissions.json"), emissions.mapData());
                messageSender.sendLifecycle(emitter, MessageType.SUCCESS_MAP_EMISSIONS);
            } catch (Exception mapEx) {
                log.warn("{}: {}", L.msg("output.map.failed"), mapEx.getMessage());
                messageSender.sendError(emitter, MessageType.ERROR_MAP_EMISSIONS, "MAP_ERROR", mapEx.getMessage());
            }

            log.info(L.msg("output.emissions.written"),
                    emissions.paragraph1().get("co2Baseline"),
                    emissions.paragraph1().get("co2PostPolicy"));
            return true;
        } catch (Exception e) {
            log.error("{}: {}", L.msg("output.component.failed"), e.getMessage(), e);
            messageSender.sendError(emitter, MessageType.ERROR_GLOBAL,
                    "EMISSIONS_ERROR", e.getMessage());
            return false;
        }
    }

    private boolean processResponse(UUID requestId, SseEmitter emitter, Path outputDir,
                                     Path baselineDir, Path policyDir,
                                     SimulationRequest request,
                                     SimulationResult baselineResult,
                                     SimulationResult policyResult) {
        ResponseResult response;
        boolean chartsSucceeded = false;

        try {
            log.info(L.msg("output.stage.response"));
            ResponseConfig responseConfig = new ResponseConfig(
                    excludeNonCarAgents, rerouteThresholdMeters, multiModalPt,
                    subwayFactorGpkm, tripLegsScope, targetCrs);

            response = ResponseAnalyzer.analyze(
                    baselineDir, policyDir, request,
                    baselineResult.legTracker(), policyResult.legTracker(),
                    policyResult.moneyCollector(), responseConfig);

            Map<String, Object> prJson = new LinkedHashMap<>();
            prJson.put("paragraph1", response.paragraph1());
            prJson.put("paragraph2", response.paragraph2());
            prJson.put("breakdownChart", response.breakdownChart());
            prJson.put("timeImpactChart", response.timeImpactChart());
            writeJson(outputDir.resolve("people-response.json"), prJson);

            messageSender.sendMessage(emitter, MessageType.DATA_TEXT_PARAGRAPH1_PEOPLE_RESPONSE, response.paragraph1());
            messageSender.sendMessage(emitter, MessageType.DATA_TEXT_PARAGRAPH2_PEOPLE_RESPONSE, response.paragraph2());
            messageSender.sendMessage(emitter, MessageType.DATA_CHART_BREAKDOWN_PEOPLE_RESPONSE, response.breakdownChart());
            messageSender.sendMessage(emitter, MessageType.DATA_CHART_TIME_IMPACT_PEOPLE_RESPONSE, response.timeImpactChart());
            chartsSucceeded = true;
        } catch (Exception e) {
            log.error("{}: {}", L.msg("output.component.failed"), e.getMessage(), e);
            messageSender.sendError(emitter, MessageType.ERROR_GLOBAL,
                    "RESPONSE_ERROR", e.getMessage());
            return false;
        }

        try {
            writeJson(outputDir.resolve("map-people-response.json"), response.peopleResponseMap());
            messageSender.sendLifecycle(emitter, MessageType.SUCCESS_MAP_PEOPLE_RESPONSE);
        } catch (Exception e) {
            log.warn("{}: {}", L.msg("output.map.failed"), e.getMessage());
            messageSender.sendError(emitter, MessageType.ERROR_MAP_PEOPLE_RESPONSE, "MAP_ERROR", e.getMessage());
        }

        try {
            tripLegRepository.batchInsert(requestId, response.tripLegRecords());
            int totalRecords = response.tripLegRecords().size();
            int pageSize = 50;
            List<Map<String, Object>> firstPage = response.tripLegRecords().stream()
                    .limit(pageSize)
                    .map(ResponseAnalyzer::toSseRecord)
                    .toList();
            messageSender.sendMessage(emitter, MessageType.DATA_TABLE_TRIP_LEGS,
                    Map.of("records", firstPage, "totalRecords", totalRecords, "pageSize", pageSize));
            log.info(L.msg("output.response.written"), totalRecords);
        } catch (Exception e) {
            log.error("{}: {}", L.msg("output.component.failed"), e.getMessage(), e);
            messageSender.sendError(emitter, MessageType.ERROR_GLOBAL,
                    "TRIP_LEGS_ERROR", e.getMessage());
        }

        try {
            writeJson(outputDir.resolve("map-trip-legs.json"), response.tripLegsMap());
            messageSender.sendLifecycle(emitter, MessageType.SUCCESS_MAP_TRIP_LEGS);
        } catch (Exception e) {
            log.warn("{}: {}", L.msg("output.map.failed"), e.getMessage());
            messageSender.sendError(emitter, MessageType.ERROR_MAP_TRIP_LEGS, "MAP_ERROR", e.getMessage());
        }

        return chartsSucceeded;
    }

    private double computeModeShift(Path baselineDir, Path policyDir) {
        Map<String, String> baselineModes = parseTripModes(baselineDir.resolve("output_trips.csv.gz"));
        Map<String, String> policyModes = parseTripModes(policyDir.resolve("output_trips.csv.gz"));

        int baselineCarTrips = 0;
        int carTripsLost = 0;
        for (Map.Entry<String, String> entry : baselineModes.entrySet()) {
            if ("car".equals(entry.getValue())) {
                baselineCarTrips++;
                String policyMode = policyModes.get(entry.getKey());
                if (policyMode != null && !"car".equals(policyMode)) {
                    carTripsLost++;
                }
            }
        }

        if (baselineCarTrips == 0) return 0.0;
        return (carTripsLost / (double) baselineCarTrips) * 100.0;
    }

    private Map<String, String> parseTripModes(Path tripsFile) {
        Map<String, String> modes = new HashMap<>();
        try (BufferedReader reader = OverviewExtractor.gzipReader(tripsFile)) {
            String header = reader.readLine();
            int personIdx = OverviewExtractor.columnIndex(header, "person");
            int tripNumIdx = OverviewExtractor.columnIndex(header, "trip_number");
            int modeIdx = OverviewExtractor.columnIndex(header, "main_mode");

            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(";", -1);
                String key = fields[personIdx] + "_" + fields[tripNumIdx];
                modes.put(key, fields[modeIdx]);
            }
        } catch (IOException e) {
            throw new RuntimeException(L.msg("output.failed") + ": " + e.getMessage(), e);
        }
        return modes;
    }

    private void writeJson(Path file, Object data) {
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writeValue(file.toFile(), data);
        } catch (IOException e) {
            throw new RuntimeException(L.msg("output.failed") + ": " + e.getMessage(), e);
        }
    }
}
