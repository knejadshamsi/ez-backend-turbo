package ez.backend.turbo.endpoints;

import com.fasterxml.jackson.databind.ObjectMapper;
import ez.backend.turbo.services.DraftService;
import ez.backend.turbo.utils.L;
import ez.backend.turbo.utils.ResponseFormatter;
import ez.backend.turbo.utils.StandardResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
public class DraftController {

    private static final String PREFIX = "d_";

    private final DraftService draftService;
    private final ResponseFormatter responseFormatter;
    private final ObjectMapper objectMapper;

    public DraftController(DraftService draftService,
                           ResponseFormatter responseFormatter,
                           ObjectMapper objectMapper) {
        this.draftService = draftService;
        this.responseFormatter = responseFormatter;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/draft")
    public ResponseEntity<StandardResponse<?>> create(@RequestBody(required = false) String body) {
        String inputData = null;
        String sessionData = null;

        if (body != null && !body.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
                Object input = parsed.get("inputData");
                Object session = parsed.get("sessionData");
                if (input != null) {
                    inputData = objectMapper.writeValueAsString(input);
                }
                if (session != null) {
                    sessionData = objectMapper.writeValueAsString(session);
                }
            } catch (Exception e) {
                throw new IllegalArgumentException(L.msg("draft.invalid.body"));
            }
        }

        UUID draftId = draftService.createDraft(inputData, sessionData);
        return ResponseEntity.ok(responseFormatter.success(
                L.msg("draft.created"),
                Map.of("draftId", prefixDraftId(draftId))));
    }

    @SuppressWarnings("unchecked")
    @GetMapping("/draft/{id}")
    public ResponseEntity<StandardResponse<?>> get(@PathVariable String id) {
        UUID draftId = parseDraftId(id);

        Optional<Map<String, Object>> draft = draftService.getDraft(draftId);
        if (draft.isEmpty()) {
            return ResponseEntity.status(404)
                    .body(StandardResponse.error(404, L.msg("draft.not.found")));
        }

        Map<String, Object> data = draft.get();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("draftId", prefixDraftId(draftId));

        String inputJson = (String) data.get("inputData");
        if (inputJson != null) {
            try {
                payload.put("inputData", objectMapper.readValue(inputJson, Object.class));
            } catch (Exception e) {
                payload.put("inputData", null);
            }
        } else {
            payload.put("inputData", null);
        }

        String sessionJson = (String) data.get("sessionData");
        if (sessionJson != null) {
            try {
                payload.put("sessionData", objectMapper.readValue(sessionJson, Object.class));
            } catch (Exception e) {
                payload.put("sessionData", null);
            }
        } else {
            payload.put("sessionData", null);
        }

        payload.put("createdAt", data.get("createdAt").toString());

        return ResponseEntity.ok(responseFormatter.success(payload));
    }

    @PutMapping("/draft/{id}")
    public ResponseEntity<StandardResponse<?>> update(@PathVariable String id,
                                                       @RequestBody String body) {
        UUID draftId = parseDraftId(id);

        Optional<Map<String, Object>> existing = draftService.getDraft(draftId);
        if (existing.isEmpty()) {
            return ResponseEntity.status(404)
                    .body(StandardResponse.error(404, L.msg("draft.not.found")));
        }

        String inputData = null;
        String sessionData = null;

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            Object input = parsed.get("inputData");
            Object session = parsed.get("sessionData");
            if (input != null) {
                inputData = objectMapper.writeValueAsString(input);
            }
            if (session != null) {
                sessionData = objectMapper.writeValueAsString(session);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(L.msg("draft.invalid.body"));
        }

        draftService.updateDraft(draftId, inputData, sessionData);
        return ResponseEntity.ok(responseFormatter.success(L.msg("draft.updated"), Map.of()));
    }

    @DeleteMapping("/draft/{id}")
    public ResponseEntity<StandardResponse<?>> delete(@PathVariable String id) {
        UUID draftId = parseDraftId(id);

        Optional<Map<String, Object>> existing = draftService.getDraft(draftId);
        if (existing.isEmpty()) {
            return ResponseEntity.status(404)
                    .body(StandardResponse.error(404, L.msg("draft.not.found")));
        }

        draftService.deleteDraft(draftId);
        return ResponseEntity.ok(responseFormatter.success(L.msg("draft.deleted"), Map.of()));
    }

    private UUID parseDraftId(String id) {
        if (!id.startsWith(PREFIX)) {
            throw new IllegalArgumentException(L.msg("draft.invalid.id"));
        }
        try {
            return UUID.fromString(id.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(L.msg("draft.invalid.id"));
        }
    }

    private String prefixDraftId(UUID id) {
        return PREFIX + id.toString();
    }
}
