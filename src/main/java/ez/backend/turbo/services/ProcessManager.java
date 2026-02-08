package ez.backend.turbo.services;

import ez.backend.turbo.config.StartupValidator;
import ez.backend.turbo.utils.L;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@Service
public class ProcessManager {

    private static final Logger log = LogManager.getLogger(ProcessManager.class);

    private final Map<ProcessType, ProcessConfig> config;
    private final Map<UUID, ProcessInfo> activeProcesses;
    private final Map<ProcessType, Semaphore> semaphores;

    public ProcessManager(StartupValidator validator) {
        this.config = Map.of(
                ProcessType.ADMIN, validator.getAdminProcessConfig(),
                ProcessType.READ, validator.getReadProcessConfig(),
                ProcessType.COMPUTE, validator.getComputeProcessConfig()
        );
        this.activeProcesses = new ConcurrentHashMap<>();
        this.semaphores = new ConcurrentHashMap<>();
        for (ProcessType type : ProcessType.values()) {
            semaphores.put(type, new Semaphore(config.get(type).max(), true));
        }
    }

    public boolean register(UUID processId, ProcessType type, String endpoint) {
        if (activeProcesses.containsKey(processId)) {
            throw new IllegalStateException(L.msg("process.already.registered") + ": " + processId);
        }

        ProcessConfig cfg = config.get(type);
        if (cfg.max() == 0) {
            logRejection(endpoint, type);
            return false;
        }

        Semaphore semaphore = semaphores.get(type);
        if (semaphore.tryAcquire()) {
            try {
                ProcessInfo previous = activeProcesses.putIfAbsent(processId, new ProcessInfo(processId, type));
                if (previous != null) {
                    semaphore.release();
                    throw new IllegalStateException(L.msg("process.already.registered") + ": " + processId);
                }
                return true;
            } catch (Exception e) {
                semaphore.release();
                throw e;
            }
        }
        logRejection(endpoint, type);
        return false;
    }

    private void logRejection(String endpoint, ProcessType type) {
        int active = config.get(type).max() - semaphores.get(type).availablePermits();
        int max = config.get(type).max();
        log.warn("[SYSTEM] {} - {}: {}, {}: {}/{}",
                L.msg("process.rejected"),
                L.msg("process.endpoint"),
                endpoint,
                type,
                active,
                max);
    }

    public void unregister(UUID processId) {
        ProcessInfo removed = activeProcesses.remove(processId);
        if (removed == null) {
            throw new IllegalStateException(L.msg("process.unregister.unknown") + ": " + processId);
        }
        semaphores.get(removed.type()).release();
    }

    public Map<ProcessType, ProcessStats> getStats() {
        return Map.of(
                ProcessType.ADMIN, new ProcessStats(
                        config.get(ProcessType.ADMIN).max() - semaphores.get(ProcessType.ADMIN).availablePermits(),
                        config.get(ProcessType.ADMIN).max()
                ),
                ProcessType.READ, new ProcessStats(
                        config.get(ProcessType.READ).max() - semaphores.get(ProcessType.READ).availablePermits(),
                        config.get(ProcessType.READ).max()
                ),
                ProcessType.COMPUTE, new ProcessStats(
                        config.get(ProcessType.COMPUTE).max() - semaphores.get(ProcessType.COMPUTE).availablePermits(),
                        config.get(ProcessType.COMPUTE).max()
                )
        );
    }

    public boolean isActive(UUID processId) {
        return activeProcesses.containsKey(processId);
    }

    public long getTimeout(ProcessType type) {
        return config.get(type).timeoutMs();
    }

    public record ProcessInfo(UUID processId, ProcessType type) {}

    public record ProcessStats(int active, int max) {}
}
