package ez.backend.turbo.session;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SseEmitterRegistry {

    private final ConcurrentHashMap<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();

    public void register(UUID id, SseEmitter emitter) {
        emitters.put(id, emitter);
        emitter.onCompletion(() -> emitters.remove(id));
        emitter.onTimeout(() -> emitters.remove(id));
        emitter.onError(e -> emitters.remove(id));
    }

    public SseEmitter get(UUID id) {
        return emitters.get(id);
    }

    public void remove(UUID id) {
        SseEmitter emitter = emitters.remove(id);
        if (emitter != null) {
            emitter.complete();
        }
    }

    public boolean hasActiveConnection(UUID id) {
        return emitters.containsKey(id);
    }

    public List<Map.Entry<UUID, SseEmitter>> getAll() {
        return new ArrayList<>(emitters.entrySet());
    }
}
