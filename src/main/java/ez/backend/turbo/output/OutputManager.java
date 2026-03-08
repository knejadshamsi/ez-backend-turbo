package ez.backend.turbo.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import ez.backend.turbo.config.StartupValidator;
import ez.backend.turbo.database.TripLegRepository;
import ez.backend.turbo.endpoints.SimulationRequest;
import ez.backend.turbo.output.SectionOneAggregator.SectionOneResult;
import ez.backend.turbo.output.SectionThreeAnalyzer.SectionThreeResult;
import ez.backend.turbo.output.SectionTwoAnalyzer.SectionTwoResult;
import ez.backend.turbo.services.ProcessManager;
import ez.backend.turbo.services.ScenarioStateService;
import ez.backend.turbo.services.SourceRegistry;
import ez.backend.turbo.simulation.MatsimRunner.SimulationResult;
import ez.backend.turbo.utils.CancellationException;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class OutputManager {

    private static final Logger log = LogManager.getLogger(OutputManager.class);

    private final SseMessageSender messageSender;
    private final ScenarioStateService scenarioStateService;
    private final ProcessManager processManager;
    private final ObjectMapper objectMapper;
    private final SourceRegistry sourceRegistry;
    private final TripLegRepository tripLegRepository;
    private final Path dataRoot;
    private final String targetCrs;
    private final String fleetMetric;
    private final double mixingHeightMeters;
    private final double rerouteThresholdMeters;
    private final String multiModalPt;
    private final double subwayFactorGpkm;
    private final double mapPointIntervalMeters;

    public OutputManager(SseMessageSender messageSender,
                         ScenarioStateService scenarioStateService,
                         ProcessManager processManager,
                         ObjectMapper objectMapper,
                         StartupValidator startupValidator,
                         SourceRegistry sourceRegistry,
                         TripLegRepository tripLegRepository,
                         @Value("${ez.target.crs}") String targetCrs,
                         @Value("${ez.output.fleet-metric}") String fleetMetric,
                         @Value("${ez.emissions.mixing-height-meters}") double mixingHeightMeters,
                         @Value("${ez.response.reroute-threshold-meters}") double rerouteThresholdMeters,
                         @Value("${ez.response.multi-modal-pt}") String multiModalPt,
                         @Value("${ez.emissions.subway-factor-gpkm}") double subwayFactorGpkm,
                         @Value("${ez.emissions.map-point-interval-meters}") double mapPointIntervalMeters) {
        this.messageSender = messageSender;
        this.scenarioStateService = scenarioStateService;
        this.processManager = processManager;
        this.objectMapper = objectMapper;
        this.sourceRegistry = sourceRegistry;
        this.tripLegRepository = tripLegRepository;
        this.dataRoot = startupValidator.getDataRoot();
        this.targetCrs = targetCrs;
        this.fleetMetric = fleetMetric;
        this.mixingHeightMeters = mixingHeightMeters;
        this.rerouteThresholdMeters = rerouteThresholdMeters;
        this.multiModalPt = multiModalPt;
        this.subwayFactorGpkm = subwayFactorGpkm;
        this.mapPointIntervalMeters = mapPointIntervalMeters;
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

        double sampleFraction = request.getSimulationOptions().getPercentage() / 100.0;

        messageSender.sendLifecycle(emitter, MessageType.PA_POSTPROCESSING_OVERVIEW_STARTED);
        anySucceeded |= processOverview(emitter, outputDir, policyDir,
                personCount, networkNodes, networkLinks, simulationAreaKm2, sampleFraction);
        messageSender.sendLifecycle(emitter, MessageType.PA_POSTPROCESSING_OVERVIEW_COMPLETE);
        checkCancelled(requestId);

        messageSender.sendLifecycle(emitter, MessageType.PA_POSTPROCESSING_EMISSIONS_STARTED);
        anySucceeded |= processEmissions(emitter, outputDir, baselineDir, policyDir,
                request, vehicles, simulationAreaKm2);
        messageSender.sendLifecycle(emitter, MessageType.PA_POSTPROCESSING_EMISSIONS_COMPLETE);
        checkCancelled(requestId);

        messageSender.sendLifecycle(emitter, MessageType.PA_POSTPROCESSING_PEOPLE_RESPONSE_STARTED);
        anySucceeded |= processPeopleResponse(emitter, outputDir, baselineDir, policyDir,
                request, policyResult);
        messageSender.sendLifecycle(emitter, MessageType.PA_POSTPROCESSING_PEOPLE_RESPONSE_COMPLETE);
        checkCancelled(requestId);

        messageSender.sendLifecycle(emitter, MessageType.PA_POSTPROCESSING_TRIP_LEGS_STARTED);
        anySucceeded |= processTripLegs(requestId, emitter, outputDir, baselineDir, policyDir,
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
                                     double simulationAreaKm2, double sampleFraction) {
        try {
            log.info(L.msg("output.stage.overview"));
            Map<String, Object> overview = OverviewExtractor.extract(
                    policyDir, personCount, networkNodes, networkLinks, simulationAreaKm2,
                    sampleFraction);
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
            log.info(L.msg("output.stage.emissions"));
            int netYear = request.getSources().getNetwork().getYear();
            String netName = request.getSources().getNetwork().getName();
            Network network = sourceRegistry.getNetwork(netYear, netName);

            double simEndTime = request.getSimulationOptions().getIterations() > 0
                    ? 30 * 3600.0 : 30 * 3600.0;

            SectionOneHandler baselineHandler = SectionOneAggregator.replayEvents(baselineDir, simEndTime);
            SectionOneHandler policyHandler = SectionOneAggregator.replayEvents(policyDir, simEndTime);

            double baselineDistanceKm = OverviewExtractor.extractDistance(
                    baselineDir.resolve("output_trips.csv.gz"));
            double policyDistanceKm = OverviewExtractor.extractDistance(
                    policyDir.resolve("output_trips.csv.gz"));
            double baselineDistanceMeters = baselineDistanceKm * 1000.0;
            double policyDistanceMeters = policyDistanceKm * 1000.0;

            double sampleFraction = request.getSimulationOptions().getPercentage() / 100.0;
            SectionOneResult result = SectionOneAggregator.aggregate(
                    baselineHandler, policyHandler, vehicles, network,
                    baselineDistanceMeters, policyDistanceMeters,
                    simulationAreaKm2, targetCrs, mixingHeightMeters, mapPointIntervalMeters,
                    sampleFraction);

            Map<String, Object> emissionsJson = new LinkedHashMap<>();
            emissionsJson.put("paragraph1", result.paragraph1());
            emissionsJson.put("lineChart", result.lineChart());
            emissionsJson.put("stackedBar", result.stackedBar());
            emissionsJson.put("paragraph2", result.paragraph2());
            emissionsJson.put("warmColdIntensity", result.warmColdIntensity());
            writeJson(outputDir.resolve("emissions.json"), emissionsJson);

            messageSender.sendMessage(emitter, MessageType.DATA_TEXT_PARAGRAPH1_EMISSIONS, result.paragraph1());
            messageSender.sendMessage(emitter, MessageType.DATA_CHART_BAR_EMISSIONS, result.paragraph1());
            messageSender.sendMessage(emitter, MessageType.DATA_CHART_LINE_EMISSIONS, result.lineChart());
            messageSender.sendMessage(emitter, MessageType.DATA_CHART_STACKED_BAR_EMISSIONS, result.stackedBar());
            messageSender.sendMessage(emitter, MessageType.DATA_TEXT_PARAGRAPH2_EMISSIONS, result.paragraph2());
            messageSender.sendMessage(emitter, MessageType.DATA_WARM_COLD_INTENSITY_EMISSIONS, result.warmColdIntensity());

            try {
                writeJson(outputDir.resolve("map-emissions.json"), result.mapData());
                messageSender.sendLifecycle(emitter, MessageType.SUCCESS_MAP_EMISSIONS);
            } catch (Exception mapEx) {
                log.warn("{}: {}", L.msg("output.map.failed"), mapEx.getMessage());
                messageSender.sendError(emitter, MessageType.ERROR_MAP_EMISSIONS, "MAP_ERROR", mapEx.getMessage());
            }

            log.info(L.msg("output.emissions.written"),
                    result.paragraph1().get("co2Baseline"),
                    result.paragraph1().get("co2Policy"));
            return true;
        } catch (Exception e) {
            log.error("{}: {}", L.msg("output.component.failed"), e.getMessage(), e);
            messageSender.sendError(emitter, MessageType.ERROR_GLOBAL,
                    "EMISSIONS_ERROR", e.getMessage());
            return false;
        }
    }

    private boolean processPeopleResponse(SseEmitter emitter, Path outputDir,
                                          Path baselineDir, Path policyDir,
                                          SimulationRequest request,
                                          SimulationResult policyResult) {
        try {
            log.info(L.msg("output.stage.response"));
            SectionTwoResult result = SectionTwoAnalyzer.analyze(
                    baselineDir, policyDir, policyResult.moneyCollector(),
                    rerouteThresholdMeters, multiModalPt, targetCrs, request);

            Map<String, Object> prJson = new LinkedHashMap<>();
            prJson.put("paragraph", result.paragraph());
            prJson.put("sankey", result.sankey());
            prJson.put("bar", result.bar());
            writeJson(outputDir.resolve("people-response.json"), prJson);

            messageSender.sendMessage(emitter, MessageType.DATA_TEXT_PARAGRAPH1_PEOPLE_RESPONSE, result.paragraph());
            messageSender.sendMessage(emitter, MessageType.DATA_CHART_SANKEY_PEOPLE_RESPONSE, result.sankey());
            messageSender.sendMessage(emitter, MessageType.DATA_CHART_BAR_PEOPLE_RESPONSE, result.bar());

            try {
                writeJson(outputDir.resolve("map-people-response.json"), result.mapData());
                messageSender.sendLifecycle(emitter, MessageType.SUCCESS_MAP_PEOPLE_RESPONSE);
            } catch (Exception mapEx) {
                log.warn("{}: {}", L.msg("output.map.failed"), mapEx.getMessage());
                messageSender.sendError(emitter, MessageType.ERROR_MAP_PEOPLE_RESPONSE, "MAP_ERROR", mapEx.getMessage());
            }

            log.info(L.msg("output.response.written"), result.paragraph().get("affectedTrips"));
            return true;
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            log.error("{}: {}", L.msg("output.component.failed"), e.getMessage(), e);
            messageSender.sendError(emitter, MessageType.ERROR_GLOBAL,
                    "RESPONSE_ERROR", e.getMessage());
            return false;
        }
    }

    private boolean processTripLegs(UUID requestId, SseEmitter emitter, Path outputDir,
                                     Path baselineDir, Path policyDir,
                                     SimulationRequest request,
                                     SimulationResult baselineResult,
                                     SimulationResult policyResult) {
        try {
            log.info(L.msg("output.stage.tripLegs"));
            SectionThreeResult result = SectionThreeAnalyzer.analyze(
                    baselineDir, policyDir,
                    baselineResult.legTracker(), policyResult.legTracker(),
                    subwayFactorGpkm, multiModalPt, targetCrs);

            Map<String, Object> perfJson = new LinkedHashMap<>();
            perfJson.put("paragraph", result.paragraph());
            writeJson(outputDir.resolve("trip-performance.json"), perfJson);

            messageSender.sendMessage(emitter, MessageType.DATA_TEXT_PARAGRAPH1_TRIP_LEGS, result.paragraph());

            tripLegRepository.batchInsert(requestId, result.tripRecords());
            int totalAllRecords = result.tripRecords().size();
            int pageSize = 10;
            List<Map<String, Object>> firstPage = result.tripRecords().stream()
                    .filter(r -> !"NC".equals(r.impact()))
                    .limit(pageSize)
                    .map(SectionThreeAnalyzer::toSseRecord)
                    .toList();
            int totalNonNC = (int) result.tripRecords().stream()
                    .filter(r -> !"NC".equals(r.impact()))
                    .count();
            Map<String, Object> tablePayload = new LinkedHashMap<>();
            tablePayload.put("records", firstPage);
            tablePayload.put("totalRecords", totalNonNC);
            tablePayload.put("totalAllRecords", totalAllRecords);
            tablePayload.put("pageSize", pageSize);
            messageSender.sendMessage(emitter, MessageType.DATA_TABLE_TRIP_LEGS, tablePayload);
            log.info(L.msg("output.tripLegs.written"), totalAllRecords);

            try {
                writeJson(outputDir.resolve("map-trip-legs.json"), result.mapData());
                messageSender.sendLifecycle(emitter, MessageType.SUCCESS_MAP_TRIP_LEGS);
            } catch (Exception mapEx) {
                log.warn("{}: {}", L.msg("output.map.failed"), mapEx.getMessage());
                messageSender.sendError(emitter, MessageType.ERROR_MAP_TRIP_LEGS, "MAP_ERROR", mapEx.getMessage());
            }

            messageSender.sendLifecycle(emitter, MessageType.PA_POSTPROCESSING_TRIP_LEGS_COMPLETE);
            return true;
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            log.error("{}: {}", L.msg("output.component.failed"), e.getMessage(), e);
            messageSender.sendError(emitter, MessageType.ERROR_GLOBAL,
                    "TRIP_LEGS_ERROR", e.getMessage());
            messageSender.sendLifecycle(emitter, MessageType.PA_POSTPROCESSING_TRIP_LEGS_COMPLETE);
            return false;
        }
    }

    private void checkCancelled(UUID requestId) {
        if (processManager.isCancelled(requestId)) {
            throw new CancellationException(requestId);
        }
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
