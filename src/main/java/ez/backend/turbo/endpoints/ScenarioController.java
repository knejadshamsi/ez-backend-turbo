package ez.backend.turbo.endpoints;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ez.backend.turbo.config.StartupValidator;
import ez.backend.turbo.services.ProcessManager;
import ez.backend.turbo.services.ScenarioStateService;
import ez.backend.turbo.session.SseEmitterRegistry;
import ez.backend.turbo.sse.SseMessageSender;
import ez.backend.turbo.utils.L;
import ez.backend.turbo.utils.MessageType;
import ez.backend.turbo.utils.ResponseFormatter;
import ez.backend.turbo.utils.ScenarioStatus;
import ez.backend.turbo.utils.StandardResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RestController
public class ScenarioController {

    private static final Logger log = LogManager.getLogger(ScenarioController.class);

    private static final Set<ScenarioStatus> CANCELLABLE = Set.of(
            ScenarioStatus.CREATED, ScenarioStatus.QUEUED, ScenarioStatus.VALIDATING,
            ScenarioStatus.SIMULATING_BASELINE, ScenarioStatus.SIMULATING_POLICY,
            ScenarioStatus.POSTPROCESSING);

    private static final Set<ScenarioStatus> IMMEDIATE_CANCEL = Set.of(
            ScenarioStatus.CREATED, ScenarioStatus.QUEUED);

    private static final Set<ScenarioStatus> NOT_RETRYABLE = Set.of(
            ScenarioStatus.CANCELLED, ScenarioStatus.DELETED, ScenarioStatus.FAILED);

    private static final Set<ScenarioStatus> STILL_RUNNING = Set.of(
            ScenarioStatus.CREATED, ScenarioStatus.QUEUED, ScenarioStatus.VALIDATING,
            ScenarioStatus.SIMULATING_BASELINE, ScenarioStatus.SIMULATING_POLICY,
            ScenarioStatus.POSTPROCESSING);

    private record RetryMapping(String filename, String jsonKey) {}

    private static final Map<String, RetryMapping> RETRY_MAPPINGS = Map.of(
            "text_overview", new RetryMapping("overview.json", null),
            "text_paragraph1_emissions", new RetryMapping("emissions.json", "paragraph1"),
            "text_paragraph2_emissions", new RetryMapping("emissions.json", "paragraph2"),
            "chart_bar_emissions", new RetryMapping("emissions.json", "barChart"),
            "chart_pie_emissions", new RetryMapping("emissions.json", "pieChart"),
            "text_paragraph1_people_response", new RetryMapping("people-response.json", "paragraph1"),
            "text_paragraph2_people_response", new RetryMapping("people-response.json", "paragraph2"),
            "chart_breakdown_people_response", new RetryMapping("people-response.json", "breakdownChart"),
            "chart_time_impact_people_response", new RetryMapping("people-response.json", "timeImpactChart")
    );

    private final ScenarioStateService scenarioStateService;
    private final ProcessManager processManager;
    private final SseMessageSender messageSender;
    private final SseEmitterRegistry emitterRegistry;
    private final ResponseFormatter responseFormatter;
    private final ObjectMapper objectMapper;
    private final Path dataRoot;

    public ScenarioController(ScenarioStateService scenarioStateService,
                              ProcessManager processManager,
                              SseMessageSender messageSender,
                              SseEmitterRegistry emitterRegistry,
                              ResponseFormatter responseFormatter,
                              ObjectMapper objectMapper,
                              StartupValidator startupValidator) {
        this.scenarioStateService = scenarioStateService;
        this.processManager = processManager;
        this.messageSender = messageSender;
        this.emitterRegistry = emitterRegistry;
        this.responseFormatter = responseFormatter;
        this.objectMapper = objectMapper;
        this.dataRoot = startupValidator.getDataRoot();
    }

    @PostMapping("/scenario/{id}/cancel")
    public ResponseEntity<StandardResponse<?>> cancel(@PathVariable String id) {
        UUID requestId = parseUuid(id);

        Optional<ScenarioStatus> status = scenarioStateService.getStatus(requestId);
        if (status.isEmpty()) {
            return ResponseEntity.status(404)
                    .body(StandardResponse.error(404, L.msg("scenario.not.found")));
        }

        ScenarioStatus current = status.get();
        if (current == ScenarioStatus.COMPLETED) {
            return ResponseEntity.status(409)
                    .body(StandardResponse.error(409, L.msg("scenario.cancel.already.completed")));
        }
        if (current == ScenarioStatus.CANCELLED) {
            return ResponseEntity.status(409)
                    .body(StandardResponse.error(409, L.msg("scenario.cancel.already.cancelled")));
        }
        if (current == ScenarioStatus.DELETED) {
            return ResponseEntity.status(409)
                    .body(StandardResponse.error(409, L.msg("scenario.cancel.deleted")));
        }
        if (current == ScenarioStatus.FAILED) {
            return ResponseEntity.status(409)
                    .body(StandardResponse.error(409, L.msg("scenario.cancel.failed")));
        }
        if (!CANCELLABLE.contains(current)) {
            return ResponseEntity.status(409)
                    .body(StandardResponse.error(409, L.msg("scenario.cancel.not.running")));
        }

        log.info("{}: {}", L.msg("scenario.cancel.requested"), requestId);

        if (IMMEDIATE_CANCEL.contains(current)) {
            handleImmediateCancel(requestId);
            return ResponseEntity.ok(responseFormatter.success(
                    L.msg("scenario.cancel.confirmed"),
                    Map.of("requestId", requestId.toString())));
        }

        processManager.requestCancel(requestId);
        CountDownLatch latch = processManager.getCancellationLatch(requestId);

        if (latch == null) {
            ScenarioStatus recheck = scenarioStateService.getStatus(requestId).orElse(null);
            if (recheck == ScenarioStatus.CANCELLED) {
                return ResponseEntity.ok(responseFormatter.success(
                        L.msg("scenario.cancel.confirmed"),
                        Map.of("requestId", requestId.toString())));
            }
            return ResponseEntity.status(409)
                    .body(StandardResponse.error(409, L.msg("scenario.cancel.not.running")));
        }

        try {
            boolean completed = latch.await(processManager.getCancelTimeoutMs(), TimeUnit.MILLISECONDS);
            if (completed) {
                log.info("{}: {}", L.msg("scenario.cancel.confirmed"), requestId);
                return ResponseEntity.ok(responseFormatter.success(
                        L.msg("scenario.cancel.confirmed"),
                        Map.of("requestId", requestId.toString())));
            } else {
                log.warn("{}: {}", L.msg("scenario.cancel.timeout"), requestId);
                return ResponseEntity.status(408)
                        .body(StandardResponse.error(408, L.msg("scenario.cancel.timeout")));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(408)
                    .body(StandardResponse.error(408, L.msg("scenario.cancel.timeout")));
        }
    }

    @PostMapping("/scenario/{id}/retry")
    public ResponseEntity<StandardResponse<?>> retry(@PathVariable String id,
                                                      @RequestBody Map<String, String> body) {
        UUID requestId = parseUuid(id);

        Optional<ScenarioStatus> status = scenarioStateService.getStatus(requestId);
        if (status.isEmpty()) {
            return ResponseEntity.status(404)
                    .body(StandardResponse.error(404, L.msg("scenario.not.found")));
        }

        ScenarioStatus current = status.get();
        if (NOT_RETRYABLE.contains(current)) {
            return ResponseEntity.badRequest()
                    .body(StandardResponse.error(400,
                            L.msg("scenario.retry.not.allowed") + ": " + current.name()));
        }
        if (STILL_RUNNING.contains(current)) {
            return ResponseEntity.badRequest()
                    .body(StandardResponse.error(400, L.msg("scenario.retry.still.running")));
        }

        String messageType = body.get("messageType");
        if (messageType == null || !RETRY_MAPPINGS.containsKey(messageType)) {
            return ResponseEntity.badRequest()
                    .body(StandardResponse.error(400, L.msg("scenario.retry.invalid.type")));
        }

        RetryMapping mapping = RETRY_MAPPINGS.get(messageType);
        Path outputDir = dataRoot.resolve("output").resolve(requestId.toString());
        Path file = outputDir.resolve(mapping.filename());

        if (!Files.isRegularFile(file)) {
            return ResponseEntity.status(500)
                    .body(StandardResponse.error(500, L.msg("scenario.retry.data.missing")));
        }

        try {
            JsonNode root = objectMapper.readTree(file.toFile());
            if (root == null || root.isEmpty()) {
                Files.deleteIfExists(file);
                return ResponseEntity.status(500)
                        .body(StandardResponse.error(500, L.msg("scenario.retry.data.corrupt")));
            }

            Object payload;
            if (mapping.jsonKey() == null) {
                payload = objectMapper.treeToValue(root, Object.class);
            } else {
                JsonNode section = root.get(mapping.jsonKey());
                if (section == null || section.isMissingNode()) {
                    return ResponseEntity.status(500)
                            .body(StandardResponse.error(500, L.msg("scenario.retry.data.corrupt")));
                }
                payload = objectMapper.treeToValue(section, Object.class);
            }

            String sseMessageType = "data_" + messageType;
            Map<String, Object> sseEnvelope = new LinkedHashMap<>();
            sseEnvelope.put("messageType", sseMessageType);
            sseEnvelope.put("payload", payload);
            sseEnvelope.put("timestamp", Instant.now().toString());

            log.info("{}: {} {}", L.msg("scenario.retry.served"), requestId, messageType);
            return ResponseEntity.ok(responseFormatter.success(sseEnvelope));
        } catch (IOException e) {
            try { Files.deleteIfExists(file); } catch (IOException ignored) {}
            return ResponseEntity.status(500)
                    .body(StandardResponse.error(500, L.msg("scenario.retry.data.corrupt")));
        }
    }

    @DeleteMapping("/scenario/{id}")
    public ResponseEntity<StandardResponse<?>> delete(@PathVariable String id) {
        UUID requestId = parseUuid(id);

        Optional<ScenarioStatus> status = scenarioStateService.getStatus(requestId);
        if (status.isEmpty()) {
            return ResponseEntity.status(404)
                    .body(StandardResponse.error(404, L.msg("scenario.not.found")));
        }

        ScenarioStatus current = status.get();
        if (current == ScenarioStatus.DELETED) {
            return ResponseEntity.badRequest()
                    .body(StandardResponse.error(400, L.msg("scenario.delete.already.deleted")));
        }

        if (STILL_RUNNING.contains(current)) {
            if (IMMEDIATE_CANCEL.contains(current)) {
                handleImmediateCancel(requestId);
            } else {
                processManager.requestCancel(requestId);
                CountDownLatch latch = processManager.getCancellationLatch(requestId);
                if (latch != null) {
                    try {
                        latch.await(processManager.getCancelTimeoutMs(), TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            log.info("{}: {}", L.msg("scenario.delete.auto.cancelled"), requestId);
        }

        scenarioStateService.cleanupOutputData(requestId);
        scenarioStateService.updateStatus(requestId, ScenarioStatus.DELETED);
        log.info("{}: {}", L.msg("scenario.delete.completed"), requestId);

        return ResponseEntity.ok(responseFormatter.success(L.msg("scenario.delete.completed"), Map.of()));
    }

    @PostMapping("/scenario/{id}/session-data")
    public ResponseEntity<StandardResponse<?>> storeSessionData(@PathVariable String id,
                                                                 @RequestBody String body) {
        UUID requestId = parseUuid(id);

        Optional<ScenarioStatus> status = scenarioStateService.getStatus(requestId);
        if (status.isEmpty()) {
            return ResponseEntity.status(404)
                    .body(StandardResponse.error(404, L.msg("scenario.not.found")));
        }

        scenarioStateService.storeSessionData(requestId, body);
        return ResponseEntity.ok(responseFormatter.success(L.msg("scenario.session.stored"), Map.of()));
    }

    @GetMapping("/scenario/{id}/status")
    public ResponseEntity<StandardResponse<?>> status(@PathVariable String id) {
        UUID requestId = parseUuid(id);

        Optional<ScenarioStatus> status = scenarioStateService.getStatus(requestId);
        if (status.isEmpty()) {
            return ResponseEntity.status(404)
                    .body(StandardResponse.error(404, L.msg("scenario.not.found")));
        }

        ScenarioStatus current = status.get();
        if (STILL_RUNNING.contains(current) && !processManager.isActive(requestId)) {
            log.warn("{}: {}", L.msg("scenario.status.stale"), requestId);
            scenarioStateService.updateStatus(requestId, ScenarioStatus.FAILED);
            current = ScenarioStatus.FAILED;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", current.name());
        String progress = processManager.getProgress(requestId);
        payload.put("progress", progress);

        return ResponseEntity.ok(responseFormatter.success(payload));
    }

    private void handleImmediateCancel(UUID requestId) {
        scenarioStateService.updateStatus(requestId, ScenarioStatus.CANCELLED);
        SseEmitter emitter = emitterRegistry.get(requestId);
        if (emitter != null) {
            messageSender.sendMessage(emitter, MessageType.PA_CANCELLED_PROCESS,
                    Map.of("status", "CANCELLED", "reason", "user_cancelled"));
            messageSender.complete(emitter);
        }
        scenarioStateService.cleanupOutputData(requestId);
        log.info("{}: {}", L.msg("scenario.cancel.queued"), requestId);
    }

    private UUID parseUuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(L.msg("scenario.not.found") + ": " + id);
        }
    }
}
