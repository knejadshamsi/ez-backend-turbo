package ez.backend.turbo.services;

import ez.backend.turbo.L;
import ez.backend.turbo.database.ScenarioRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// Manages scenario lifecycle, state transitions, and data persistence
@Service
public class ScenarioStateService {

    private static final Logger log = LogManager.getLogger(ScenarioStateService.class);

    private final ScenarioRepository scenarioRepository;
    private final IdGenerator idGenerator;

    public ScenarioStateService(ScenarioRepository scenarioRepository, IdGenerator idGenerator) {
        this.scenarioRepository = scenarioRepository;
        this.idGenerator = idGenerator;
    }

    public UUID createScenario() {
        UUID requestId = idGenerator.generate();
        Instant now = Instant.now();
        scenarioRepository.create(requestId, ScenarioStatus.CREATED, now);
        logWithContext(requestId, () -> log.info(L.msg("scenario.created")));
        return requestId;
    }

    public void updateStatus(UUID requestId, ScenarioStatus newStatus) {
        int updated = scenarioRepository.updateStatus(requestId, newStatus, Instant.now());
        if (updated == 0) {
            throw new IllegalArgumentException(L.msg("scenario.not.found") + ": " + requestId);
        }
        logWithContext(requestId, () -> log.info(L.msg("scenario.status.changed"), newStatus));
    }

    public Optional<Map<String, Object>> getScenario(UUID requestId) {
        return scenarioRepository.findById(requestId);
    }

    public Optional<ScenarioStatus> getStatus(UUID requestId) {
        return scenarioRepository.findStatusById(requestId);
    }

    public void storeInputData(UUID requestId, String inputDataJson) {
        int updated = scenarioRepository.updateInputData(requestId, inputDataJson, Instant.now());
        if (updated == 0) {
            throw new IllegalArgumentException(L.msg("scenario.not.found") + ": " + requestId);
        }
        logWithContext(requestId, () -> log.info(L.msg("scenario.input.stored")));
    }

    public void storeSessionData(UUID requestId, String sessionDataJson) {
        int updated = scenarioRepository.updateSessionData(requestId, sessionDataJson, Instant.now());
        if (updated == 0) {
            throw new IllegalArgumentException(L.msg("scenario.not.found") + ": " + requestId);
        }
        logWithContext(requestId, () -> log.info(L.msg("scenario.session.stored")));
    }

    private void logWithContext(UUID requestId, Runnable logAction) {
        try {
            ThreadContext.put("ctx", requestId.toString());
            logAction.run();
        } finally {
            ThreadContext.remove("ctx");
        }
    }
}
