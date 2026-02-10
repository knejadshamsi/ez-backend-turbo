package ez.backend.turbo.services;

import ez.backend.turbo.config.StartupValidator;
import ez.backend.turbo.session.SseEmitterRegistry;
import ez.backend.turbo.sse.SseMessageSender;
import ez.backend.turbo.utils.L;
import ez.backend.turbo.utils.MessageType;
import ez.backend.turbo.utils.ScenarioStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnProperty(name = "ez.processes.compute.queue.enabled", havingValue = "true")
public class SimulationQueueManager {

    private static final Logger log = LogManager.getLogger(SimulationQueueManager.class);

    private final ArrayBlockingQueue<QueuedSimulation> queue;
    private final ExecutorService workerPool;
    private final long queueTimeoutMs;
    private final int computeMax;
    private final AtomicInteger activeWorkers = new AtomicInteger(0);

    private final ProcessManager processManager;
    private final SimulationService simulationService;
    private final ScenarioStateService scenarioStateService;
    private final SseMessageSender messageSender;
    private final SseEmitterRegistry emitterRegistry;

    public SimulationQueueManager(StartupValidator validator,
                                  ProcessManager processManager,
                                  @Lazy SimulationService simulationService,
                                  ScenarioStateService scenarioStateService,
                                  SseMessageSender messageSender,
                                  SseEmitterRegistry emitterRegistry) {
        this.queue = new ArrayBlockingQueue<>(validator.getComputeQueueMaxSize());
        this.queueTimeoutMs = validator.getComputeQueueTimeout();
        this.computeMax = validator.getComputeProcessConfig().max();
        this.workerPool = Executors.newFixedThreadPool(computeMax);
        this.processManager = processManager;
        this.simulationService = simulationService;
        this.scenarioStateService = scenarioStateService;
        this.messageSender = messageSender;
        this.emitterRegistry = emitterRegistry;
    }

    @PostConstruct
    void startWorkers() {
        for (int i = 0; i < computeMax; i++) {
            workerPool.submit(this::workerLoop);
        }
    }

    @PreDestroy
    void shutdown() {
        workerPool.shutdownNow();
    }

    public boolean submit(UUID requestId, SseEmitter emitter, boolean queued) {
        return queue.offer(new QueuedSimulation(requestId, emitter, System.currentTimeMillis(), queued));
    }

    public boolean hasIdleWorker() {
        return activeWorkers.get() < computeMax;
    }

    public long getQueueTimeout() {
        return queueTimeoutMs;
    }

    private void workerLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                QueuedSimulation sim = queue.take();
                ThreadContext.put("ctx", sim.requestId().toString());
                try {
                    processQueuedSimulation(sim);
                } catch (Exception e) {
                    log.error("{}: {}", L.msg("simulation.failed"), e.getMessage(), e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } finally {
                ThreadContext.remove("ctx");
            }
        }
    }

    private void processQueuedSimulation(QueuedSimulation sim) {
        if (sim.submittedAt() + queueTimeoutMs < System.currentTimeMillis()) {
            handleExpired(sim);
            return;
        }

        if (!emitterRegistry.hasActiveConnection(sim.requestId())) {
            handleDisconnected(sim);
            return;
        }

        activeWorkers.incrementAndGet();
        processManager.register(sim.requestId(), ProcessType.COMPUTE, "/simulate");
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("requestId", sim.requestId().toString());
            if (sim.queued()) {
                payload.put("queued", true);
            }
            messageSender.sendMessage(sim.emitter(), MessageType.PA_REQUEST_ACCEPTED, payload);
            simulationService.executePipeline(sim.requestId(), sim.emitter());
        } finally {
            processManager.unregister(sim.requestId());
            activeWorkers.decrementAndGet();
        }
    }

    private void handleExpired(QueuedSimulation sim) {
        log.warn(L.msg("simulation.queue.timeout"));
        scenarioStateService.updateStatus(sim.requestId(), ScenarioStatus.FAILED);
        messageSender.sendError(sim.emitter(), MessageType.ERROR_GLOBAL,
                "QUEUE_TIMEOUT", L.msg("simulation.queue.timeout"));
        messageSender.complete(sim.emitter());
    }

    private void handleDisconnected(QueuedSimulation sim) {
        log.warn(L.msg("simulation.queue.disconnected"));
        scenarioStateService.updateStatus(sim.requestId(), ScenarioStatus.CANCELLED);
    }

    record QueuedSimulation(UUID requestId, SseEmitter emitter, long submittedAt, boolean queued) {}
}
