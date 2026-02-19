package ez.backend.turbo.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import ez.backend.turbo.config.StartupValidator;
import ez.backend.turbo.endpoints.SimulationRequest;
import ez.backend.turbo.services.ScenarioStateService;
import ez.backend.turbo.simulation.MatsimRunner.SimulationResult;
import ez.backend.turbo.sse.SseMessageSender;
import ez.backend.turbo.utils.L;
import ez.backend.turbo.utils.MessageType;
import ez.backend.turbo.utils.ScenarioStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.vehicles.Vehicles;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class OutputManager {

    private static final Logger log = LogManager.getLogger(OutputManager.class);

    private final SseMessageSender messageSender;
    private final ScenarioStateService scenarioStateService;
    private final ObjectMapper objectMapper;
    private final Path dataRoot;

    public OutputManager(SseMessageSender messageSender,
                         ScenarioStateService scenarioStateService,
                         ObjectMapper objectMapper,
                         StartupValidator startupValidator) {
        this.messageSender = messageSender;
        this.scenarioStateService = scenarioStateService;
        this.objectMapper = objectMapper;
        this.dataRoot = startupValidator.getDataRoot();
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

        // Step 1: Overview
        log.info(L.msg("output.stage.overview"));
        Map<String, Object> overview;
        try {
            overview = OverviewExtractor.extract(
                    policyDir, personCount, networkNodes, networkLinks, simulationAreaKm2);
        } catch (IOException e) {
            throw new RuntimeException(L.msg("output.failed") + ": " + e.getMessage(), e);
        }
        writeJson(outputDir.resolve("overview.json"), overview);
        messageSender.sendMessage(emitter, MessageType.DATA_TEXT_OVERVIEW, overview);
        log.info(L.msg("output.overview.written"),
                overview.get("personCount"), overview.get("legCount"), overview.get("totalKmTraveled"));

        // Step 1.5: Mode shift pre-scan
        log.info(L.msg("output.stage.modescan"));
        double modeShiftPercentage = computeModeShift(baselineDir, policyDir);

        // Step 2: Emissions (commit 2)

        // Step 3: Response analysis (commit 3)

        // Step 4: Completion
        scenarioStateService.updateStatus(requestId, ScenarioStatus.COMPLETED);
        messageSender.sendLifecycle(emitter, MessageType.SUCCESS_PROCESS);
        messageSender.complete(emitter);
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
