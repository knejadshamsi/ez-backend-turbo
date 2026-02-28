package ez.backend.turbo.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import ez.backend.turbo.config.StartupValidator;
import ez.backend.turbo.database.TripLegRepository;
import ez.backend.turbo.sse.SseMessageSender;
import ez.backend.turbo.utils.L;
import ez.backend.turbo.utils.MessageType;
import ez.backend.turbo.utils.ScenarioStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ScenarioReplayService {

    private static final Logger log = LogManager.getLogger(ScenarioReplayService.class);

    private final SseMessageSender messageSender;
    private final ObjectMapper objectMapper;
    private final TripLegRepository tripLegRepository;
    private final ScenarioStateService scenarioStateService;
    private final Path dataRoot;

    public ScenarioReplayService(SseMessageSender messageSender,
                                 ObjectMapper objectMapper,
                                 TripLegRepository tripLegRepository,
                                 ScenarioStateService scenarioStateService,
                                 StartupValidator startupValidator) {
        this.messageSender = messageSender;
        this.objectMapper = objectMapper;
        this.tripLegRepository = tripLegRepository;
        this.scenarioStateService = scenarioStateService;
        this.dataRoot = startupValidator.getDataRoot();
    }

    @SuppressWarnings("unchecked")
    public void replay(UUID requestId, SseEmitter emitter, ScenarioStatus status) {
        sendPreamble(requestId, emitter, status);

        if (status != ScenarioStatus.COMPLETED) {
            messageSender.complete(emitter);
            return;
        }

        replayOutput(requestId, emitter);
    }

    private void sendPreamble(UUID requestId, SseEmitter emitter, ScenarioStatus status) {
        messageSender.sendMessage(emitter, MessageType.SCENARIO_STATUS,
                Map.of("status", status.name(), "requestId", requestId.toString()));

        var scenario = scenarioStateService.getScenario(requestId);
        if (scenario.isEmpty()) return;

        String inputJson = (String) scenario.get().get("inputData");
        if (inputJson != null) {
            try {
                Object inputData = objectMapper.readValue(inputJson, Object.class);
                messageSender.sendMessage(emitter, MessageType.SCENARIO_INPUT, inputData);
            } catch (Exception e) {
                log.warn("{}: {}", L.msg("output.replay.failed"), e.getMessage());
                messageSender.sendMessage(emitter, MessageType.SCENARIO_INPUT, Map.of());
            }
        } else {
            messageSender.sendMessage(emitter, MessageType.SCENARIO_INPUT, Map.of());
        }

        String sessionJson = (String) scenario.get().get("sessionData");
        if (sessionJson != null) {
            try {
                Object sessionData = objectMapper.readValue(sessionJson, Object.class);
                messageSender.sendMessage(emitter, MessageType.SCENARIO_SESSION, sessionData);
            } catch (Exception e) {
                log.warn("{}: {}", L.msg("output.replay.failed"), e.getMessage());
                messageSender.sendMessage(emitter, MessageType.SCENARIO_SESSION, Map.of());
            }
        } else {
            messageSender.sendMessage(emitter, MessageType.SCENARIO_SESSION, Map.of());
        }
    }

    @SuppressWarnings("unchecked")
    private void replayOutput(UUID requestId, SseEmitter emitter) {
        Path outputDir = dataRoot.resolve("output").resolve(requestId.toString());

        Path overviewFile = outputDir.resolve("overview.json");
        Path emissionsFile = outputDir.resolve("emissions.json");
        Path peopleResponseFile = outputDir.resolve("people-response.json");

        if (!Files.isRegularFile(overviewFile) || !Files.isRegularFile(emissionsFile)
                || !Files.isRegularFile(peopleResponseFile)) {
            messageSender.sendError(emitter, MessageType.ERROR_GLOBAL,
                    "DATA_CORRUPT", L.msg("output.replay.corrupt"));
            messageSender.complete(emitter);
            return;
        }

        try {
            Object overview = objectMapper.readValue(overviewFile.toFile(), Object.class);
            messageSender.sendMessage(emitter, MessageType.DATA_TEXT_OVERVIEW, overview);

            Map<String, Object> emissions = objectMapper.readValue(emissionsFile.toFile(), Map.class);
            messageSender.sendMessage(emitter, MessageType.DATA_TEXT_PARAGRAPH1_EMISSIONS, emissions.get("paragraph1"));
            messageSender.sendMessage(emitter, MessageType.DATA_TEXT_PARAGRAPH2_EMISSIONS, emissions.get("paragraph2"));
            messageSender.sendMessage(emitter, MessageType.DATA_CHART_BAR_EMISSIONS, emissions.get("barChart"));
            messageSender.sendMessage(emitter, MessageType.DATA_CHART_PIE_EMISSIONS, emissions.get("pieChart"));

            if (Files.isRegularFile(outputDir.resolve("map-emissions.json"))) {
                messageSender.sendLifecycle(emitter, MessageType.SUCCESS_MAP_EMISSIONS);
            } else {
                messageSender.sendError(emitter, MessageType.ERROR_MAP_EMISSIONS,
                        "MAP_MISSING", L.msg("output.map.file.missing"));
            }

            Map<String, Object> peopleResponse = objectMapper.readValue(peopleResponseFile.toFile(), Map.class);
            messageSender.sendMessage(emitter, MessageType.DATA_TEXT_PARAGRAPH1_PEOPLE_RESPONSE, peopleResponse.get("paragraph1"));
            messageSender.sendMessage(emitter, MessageType.DATA_TEXT_PARAGRAPH2_PEOPLE_RESPONSE, peopleResponse.get("paragraph2"));
            messageSender.sendMessage(emitter, MessageType.DATA_CHART_BREAKDOWN_PEOPLE_RESPONSE, peopleResponse.get("breakdownChart"));
            messageSender.sendMessage(emitter, MessageType.DATA_CHART_TIME_IMPACT_PEOPLE_RESPONSE, peopleResponse.get("timeImpactChart"));

            if (Files.isRegularFile(outputDir.resolve("map-people-response.json"))) {
                messageSender.sendLifecycle(emitter, MessageType.SUCCESS_MAP_PEOPLE_RESPONSE);
            } else {
                messageSender.sendError(emitter, MessageType.ERROR_MAP_PEOPLE_RESPONSE,
                        "MAP_MISSING", L.msg("output.map.file.missing"));
            }

            int pageSize = 50;
            List<Map<String, Object>> records = tripLegRepository.findByRequestId(requestId, 1, pageSize);
            int totalRecords = tripLegRepository.countByRequestId(requestId);
            messageSender.sendMessage(emitter, MessageType.DATA_TABLE_TRIP_LEGS,
                    Map.of("records", records, "totalRecords", totalRecords, "pageSize", pageSize));

            if (Files.isRegularFile(outputDir.resolve("map-trip-legs.json"))) {
                messageSender.sendLifecycle(emitter, MessageType.SUCCESS_MAP_TRIP_LEGS);
            } else {
                messageSender.sendError(emitter, MessageType.ERROR_MAP_TRIP_LEGS,
                        "MAP_MISSING", L.msg("output.map.file.missing"));
            }

            messageSender.sendLifecycle(emitter, MessageType.SUCCESS_PROCESS);
            messageSender.complete(emitter);

        } catch (Exception e) {
            log.error("{}: {}", L.msg("output.replay.failed"), e.getMessage(), e);
            messageSender.sendError(emitter, MessageType.ERROR_GLOBAL,
                    "REPLAY_ERROR", L.msg("output.replay.failed"));
            messageSender.complete(emitter);
        }
    }
}
