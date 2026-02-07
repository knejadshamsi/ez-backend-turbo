package ez.backend.turbo.endpoints;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "statusCode", 200,
                "message", "Backend is healthy",
                "payload", Map.of(),
                "timestamp", Instant.now().toString()
        ));
    }
}
