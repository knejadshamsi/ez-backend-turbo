package ez.backend.turbo.endpoints;

import ez.backend.turbo.utils.ResponseFormatter;
import ez.backend.turbo.utils.StandardResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    private final ResponseFormatter responseFormatter;

    public HealthController(ResponseFormatter responseFormatter) {
        this.responseFormatter = responseFormatter;
    }

    @GetMapping("/health")
    public ResponseEntity<StandardResponse<Map<String, Object>>> health() {
        return ResponseEntity.ok(responseFormatter.success("Backend is healthy", Map.of()));
    }
}
