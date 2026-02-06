package ez.backend.turbo.endpoints;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

// Health check endpoint
@RestController
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("statusCode", 200);
        response.put("message", "Backend is healthy");
        response.put("payload", new HashMap<>());  
        response.put("timestamp", ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT));

        return ResponseEntity.ok(response);
    }
}
