package ez.backend.turbo.endpoints;

import ez.backend.turbo.services.ProcessManager;
import ez.backend.turbo.services.ProcessType;
import ez.backend.turbo.services.ScenarioReplayService;
import ez.backend.turbo.services.ScenarioStateService;
import ez.backend.turbo.services.SimulationService;
import ez.backend.turbo.session.SseEmitterRegistry;
import ez.backend.turbo.sse.SseMessageSender;
import ez.backend.turbo.utils.L;
import ez.backend.turbo.utils.MessageType;
import ez.backend.turbo.utils.ScenarioStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
public class SimulationController {

    private static final Set<ScenarioStatus> REPLAYABLE = Set.of(
            ScenarioStatus.COMPLETED, ScenarioStatus.CANCELLED,
            ScenarioStatus.FAILED, ScenarioStatus.DELETED);

    private final SimulationService simulationService;
    private final ScenarioStateService scenarioStateService;
    private final ScenarioReplayService scenarioReplayService;
    private final ProcessManager processManager;
    private final SseEmitterRegistry emitterRegistry;
    private final SseMessageSender messageSender;

    public SimulationController(SimulationService simulationService,
                                ScenarioStateService scenarioStateService,
                                ScenarioReplayService scenarioReplayService,
                                ProcessManager processManager,
                                SseEmitterRegistry emitterRegistry,
                                SseMessageSender messageSender) {
        this.simulationService = simulationService;
        this.scenarioStateService = scenarioStateService;
        this.scenarioReplayService = scenarioReplayService;
        this.processManager = processManager;
        this.emitterRegistry = emitterRegistry;
        this.messageSender = messageSender;
    }

    @PostMapping("/simulate")
    public SseEmitter simulate(@RequestBody SimulationRequest request) {
        return simulationService.startSimulation(request);
    }

    @GetMapping("/scenario/{id}")
    public SseEmitter loadScenario(@PathVariable String id) {
        UUID requestId;
        try {
            requestId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return errorEmitter(MessageType.ERROR_GLOBAL, "INVALID_ID", L.msg("output.scenario.not.found"));
        }

        Optional<ScenarioStatus> status = scenarioStateService.getStatus(requestId);
        if (status.isEmpty()) {
            return errorEmitter(MessageType.ERROR_GLOBAL, "NOT_FOUND", L.msg("output.scenario.not.found"));
        }

        ScenarioStatus current = status.get();
        if (!REPLAYABLE.contains(current)) {
            return errorEmitter(MessageType.ERROR_GLOBAL, "NOT_COMPLETED", L.msg("output.scenario.not.completed"));
        }

        boolean registered = processManager.register(requestId, ProcessType.READ, "/scenario/{id}");
        if (!registered) {
            return errorEmitter(MessageType.ERROR_GLOBAL, "CAPACITY", L.msg("output.replay.failed"));
        }

        long timeout = processManager.getTimeout(ProcessType.READ);
        SseEmitter emitter = new SseEmitter(timeout);
        emitterRegistry.register(requestId, emitter);

        CompletableFuture.runAsync(() -> {
            try {
                scenarioReplayService.replay(requestId, emitter, current);
            } finally {
                processManager.unregister(requestId);
            }
        });

        return emitter;
    }

    private SseEmitter errorEmitter(MessageType type, String code, String message) {
        SseEmitter emitter = new SseEmitter(5000L);
        messageSender.sendError(emitter, type, code, message);
        messageSender.complete(emitter);
        return emitter;
    }
}
