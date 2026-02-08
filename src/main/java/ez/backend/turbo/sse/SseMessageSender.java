package ez.backend.turbo.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import ez.backend.turbo.utils.L;
import ez.backend.turbo.utils.MessageType;

import java.io.IOException;

import java.time.Instant;
import java.util.Map;

@Component
public class SseMessageSender {

    private static final Logger log = LogManager.getLogger(SseMessageSender.class);

    private final ObjectMapper objectMapper;

    public SseMessageSender(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void sendMessage(SseEmitter emitter, MessageType type, Object payload) {
        Map<String, Object> envelope = Map.of(
                "messageType", type.getValue(),
                "payload", payload,
                "timestamp", Instant.now().toString()
        );
        doSend(emitter, envelope);
    }

    public void sendLifecycle(SseEmitter emitter, MessageType type) {
        sendMessage(emitter, type, Map.of());
    }

    public void sendError(SseEmitter emitter, MessageType type, String code, String message) {
        sendMessage(emitter, type, Map.of("code", code, "message", message));
    }

    public void complete(SseEmitter emitter) {
        emitter.complete();
    }

    private void doSend(SseEmitter emitter, Map<String, Object> envelope) {
        try {
            String json = objectMapper.writeValueAsString(envelope);
            emitter.send(SseEmitter.event().data(json));
        } catch (IOException e) {
            log.warn(L.msg("sse.send.failed"), e);
            emitter.completeWithError(e);
        }
    }
}
