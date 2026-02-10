package ez.backend.turbo.config;

import ez.backend.turbo.utils.L;
import ez.backend.turbo.utils.StandardResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LogManager.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<StandardResponse<?>> handleBadRequest(IllegalArgumentException e) {
        log.warn("{}: {}", L.msg("exception.caught"), e.getMessage());
        return ResponseEntity.badRequest().body(StandardResponse.error(400, e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<StandardResponse<?>> handleServiceUnavailable(IllegalStateException e) {
        log.warn("{}: {}", L.msg("exception.caught"), e.getMessage());
        return ResponseEntity.status(503).body(StandardResponse.error(503, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<StandardResponse<?>> handleGeneric(Exception e) {
        log.error("{}: {}", L.msg("exception.caught"), e.getMessage(), e);
        return ResponseEntity.status(500).body(StandardResponse.error(500, L.msg("exception.internal")));
    }
}
