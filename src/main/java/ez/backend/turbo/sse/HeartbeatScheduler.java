package ez.backend.turbo.sse;

import ez.backend.turbo.utils.L;
import ez.backend.turbo.session.SseEmitterRegistry;
import ez.backend.turbo.utils.MessageType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

@Component
public class HeartbeatScheduler {

    private static final Logger log = LogManager.getLogger(HeartbeatScheduler.class);

    private final SseEmitterRegistry registry;
    private final SseMessageSender sender;

    public HeartbeatScheduler(SseEmitterRegistry registry, SseMessageSender sender) {
        this.registry = registry;
        this.sender = sender;
    }

    @Scheduled(fixedRate = 30000)
    void sendHeartbeats() {
        for (Map.Entry<UUID, SseEmitter> entry : registry.getAll()) {
            try {
                sender.sendLifecycle(entry.getValue(), MessageType.HEARTBEAT);
            } catch (Exception e) {
                log.warn(L.msg("sse.heartbeat.failed"), e);
                registry.remove(entry.getKey());
            }
        }
    }
}
