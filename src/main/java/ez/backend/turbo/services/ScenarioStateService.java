package ez.backend.turbo.services;

import ez.backend.turbo.database.ScenarioRepository;
import ez.backend.turbo.database.TripLegRepository;
import ez.backend.turbo.utils.IdGenerator;
import ez.backend.turbo.utils.L;
import ez.backend.turbo.utils.ScenarioStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

// Manages scenario lifecycle, state transitions, and data persistence
@Service
public class ScenarioStateService {

    private static final Logger log = LogManager.getLogger(ScenarioStateService.class);

    private final ScenarioRepository scenarioRepository;
    private final TripLegRepository tripLegRepository;
    private final IdGenerator idGenerator;
    private final Path dataRoot;

    public ScenarioStateService(ScenarioRepository scenarioRepository,
                                TripLegRepository tripLegRepository,
                                IdGenerator idGenerator,
                                @Value("${ez.data.root}") String dataRoot) {
        this.scenarioRepository = scenarioRepository;
        this.tripLegRepository = tripLegRepository;
        this.idGenerator = idGenerator;
        this.dataRoot = Path.of(dataRoot);
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

    public void cleanupOutputData(UUID requestId) {
        tripLegRepository.deleteByRequestId(requestId);
        Path outputDir = dataRoot.resolve("output").resolve(requestId.toString());
        if (Files.isDirectory(outputDir)) {
            try (Stream<Path> walk = Files.walk(outputDir)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                        });
            } catch (IOException e) {
                log.warn("{}: {}", L.msg("scenario.cancel.cleanup"), e.getMessage());
            }
        }
        log.info("{}: {}", L.msg("scenario.cancel.cleanup"), requestId);
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
