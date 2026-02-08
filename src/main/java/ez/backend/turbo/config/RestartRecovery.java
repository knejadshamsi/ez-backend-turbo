package ez.backend.turbo.config;

import ez.backend.turbo.database.ScenarioRepository;
import ez.backend.turbo.services.ProcessManager;
import ez.backend.turbo.utils.ScenarioStatus;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class RestartRecovery {

    private final ScenarioRepository repository;
    private final ProcessManager processManager;

    public RestartRecovery(ScenarioRepository repository, ProcessManager processManager) {
        this.repository = repository;
        this.processManager = processManager;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOrphanedScenarios() {
        List<UUID> incomplete = repository.findIncompleteScenarios();

        for (UUID requestId : incomplete) {
            if (!processManager.isActive(requestId)) {
                repository.updateStatus(requestId, ScenarioStatus.CANCELLED, Instant.now());
            }
        }
    }
}
