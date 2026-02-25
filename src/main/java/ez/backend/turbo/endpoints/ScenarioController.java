package ez.backend.turbo.endpoints;

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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RestController
public class ScenarioController {

    private static final Logger log = LogManager.getLogger(ScenarioController.class);

    private static final Set<ScenarioStatus> CANCELLABLE = Set.of(
            ScenarioStatus.CREATED, ScenarioStatus.QUEUED, ScenarioStatus.VALIDATING,
            ScenarioStatus.SIMULATING_BASELINE, ScenarioStatus.SIMULATING_POLICY,
            ScenarioStatus.POSTPROCESSING);

    private final ScenarioStateService scenarioStateService;
    private final ProcessManager processManager;
    private final SseMessageSender messageSender;
    private final SseEmitterRegistry emitterRegistry;
    private final ResponseFormatter responseFormatter;

    public ScenarioController(ScenarioStateService scenarioStateService,
                              ProcessManager processManager,
                              SseMessageSender messageSender,
                              SseEmitterRegistry emitterRegistry,
                              ResponseFormatter responseFormatter) {
        this.scenarioStateService = scenarioStateService;
        this.processManager = processManager;
        this.messageSender = messageSender;
        this.emitterRegistry = emitterRegistry;
        this.responseFormatter = responseFormatter;
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
            return ResponseEntity.badRequest()
                    .body(StandardResponse.error(400, L.msg("scenario.cancel.already.completed")));
        }
        if (current == ScenarioStatus.CANCELLED) {
            return ResponseEntity.badRequest()
                    .body(StandardResponse.error(400, L.msg("scenario.cancel.already.cancelled")));
        }
        if (!CANCELLABLE.contains(current)) {
            return ResponseEntity.badRequest()
                    .body(StandardResponse.error(400, L.msg("scenario.cancel.not.running")));
        }

        processManager.requestCancel(requestId);
        log.info("{}: {}", L.msg("scenario.cancel.requested"), requestId);

        if (current == ScenarioStatus.QUEUED || current == ScenarioStatus.CREATED) {
            handleImmediateCancel(requestId);
        }

        Map<String, Object> payload = Map.of("status", "cancelled", "requestId", requestId.toString());
        return ResponseEntity.ok(responseFormatter.success(L.msg("scenario.cancel.confirmed"), payload));
    }

    private void handleImmediateCancel(UUID requestId) {
        scenarioStateService.updateStatus(requestId, ScenarioStatus.CANCELLED);
        SseEmitter emitter = emitterRegistry.get(requestId);
        if (emitter != null) {
            messageSender.sendMessage(emitter, MessageType.CANCELLED_PROCESS,
                    Map.of("requestId", requestId.toString()));
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
