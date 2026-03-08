package ez.backend.turbo.endpoints;

import com.fasterxml.jackson.databind.ObjectMapper;
import ez.backend.turbo.config.StartupValidator;
import ez.backend.turbo.database.TripLegRepository;
import ez.backend.turbo.services.ScenarioStateService;
import ez.backend.turbo.utils.L;
import ez.backend.turbo.utils.ResponseFormatter;
import ez.backend.turbo.utils.ScenarioStatus;
import ez.backend.turbo.utils.StandardResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
public class OutputController {

    private final ResponseFormatter responseFormatter;
    private final ScenarioStateService scenarioStateService;
    private final TripLegRepository tripLegRepository;
    private final ObjectMapper objectMapper;
    private final Path dataRoot;

    public OutputController(ResponseFormatter responseFormatter,
                            ScenarioStateService scenarioStateService,
                            TripLegRepository tripLegRepository,
                            ObjectMapper objectMapper,
                            StartupValidator startupValidator) {
        this.responseFormatter = responseFormatter;
        this.scenarioStateService = scenarioStateService;
        this.tripLegRepository = tripLegRepository;
        this.objectMapper = objectMapper;
        this.dataRoot = startupValidator.getDataRoot();
    }

    @GetMapping("/scenario/{id}/maps/emissions")
    public ResponseEntity<StandardResponse<?>> emissionsMap(@PathVariable String id) {
        UUID requestId = parseUuid(id);
        ResponseEntity<StandardResponse<?>> validation = validateCompleted(requestId);
        if (validation != null) return validation;
        return serveMapFile(requestId, "map-emissions.json");
    }

    @GetMapping("/scenario/{id}/maps/people-response")
    public ResponseEntity<StandardResponse<?>> peopleResponseMap(@PathVariable String id) {
        UUID requestId = parseUuid(id);
        ResponseEntity<StandardResponse<?>> validation = validateCompleted(requestId);
        if (validation != null) return validation;
        return serveMapFile(requestId, "map-people-response.json");
    }

    @GetMapping("/scenario/{id}/maps/trip-legs")
    public ResponseEntity<StandardResponse<?>> tripLegsMap(@PathVariable String id) {
        UUID requestId = parseUuid(id);
        ResponseEntity<StandardResponse<?>> validation = validateCompleted(requestId);
        if (validation != null) return validation;
        return serveMapFile(requestId, "map-trip-legs.json");
    }

    @GetMapping("/scenario/{id}/trip-legs")
    public ResponseEntity<StandardResponse<?>> tripLegs(@PathVariable String id,
                                                        @RequestParam int page,
                                                        @RequestParam int pageSize,
                                                        @RequestParam(defaultValue = "true") boolean excludeNC) {
        UUID requestId = parseUuid(id);
        ResponseEntity<StandardResponse<?>> validation = validateCompleted(requestId);
        if (validation != null) return validation;

        List<Map<String, Object>> records = tripLegRepository.findByRequestId(
                requestId, page, pageSize, excludeNC);
        int totalRecords = excludeNC
                ? tripLegRepository.countByRequestIdExcludeNC(requestId)
                : tripLegRepository.countByRequestId(requestId);
        int totalAllRecords = tripLegRepository.countByRequestId(requestId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("records", records);
        payload.put("page", page);
        payload.put("pageSize", pageSize);
        payload.put("totalRecords", totalRecords);
        payload.put("totalAllRecords", totalAllRecords);

        return ResponseEntity.ok(responseFormatter.success(payload));
    }

    private UUID parseUuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(L.msg("output.scenario.not.found") + ": " + id);
        }
    }

    private ResponseEntity<StandardResponse<?>> validateCompleted(UUID requestId) {
        Optional<ScenarioStatus> status = scenarioStateService.getStatus(requestId);
        if (status.isEmpty()) {
            return ResponseEntity.status(404)
                    .body(StandardResponse.error(404, L.msg("output.scenario.not.found")));
        }
        if (status.get() != ScenarioStatus.COMPLETED) {
            return ResponseEntity.status(409)
                    .body(StandardResponse.error(409, L.msg("output.scenario.not.completed")));
        }
        return null;
    }

    private ResponseEntity<StandardResponse<?>> serveMapFile(UUID requestId, String filename) {
        Path file = dataRoot.resolve("output").resolve(requestId.toString()).resolve(filename);
        if (!Files.isRegularFile(file)) {
            return ResponseEntity.status(404)
                    .body(StandardResponse.error(404, L.msg("output.map.file.missing")));
        }
        try {
            Object data = objectMapper.readValue(file.toFile(), Object.class);
            return ResponseEntity.ok(responseFormatter.success(data));
        } catch (IOException e) {
            throw new RuntimeException(L.msg("output.map.file.missing") + ": " + e.getMessage(), e);
        }
    }
}
