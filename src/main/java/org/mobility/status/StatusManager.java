package org.mobility.status;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.HashMap;

@Component
public class StatusManager {
    public static final String STATUS_VALIDATING = "VALIDATING";
    public static final String STATUS_PREPARING_INPUT = "PREPARING_INPUT";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_PROCESSING_OUTPUT = "PROCESSING_OUTPUT";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "ERROR";

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public StatusManager(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void updateStatus(String requestId, String status, String message) {
        if (requestId == null || status == null) {
            return;
        }

        try {
            jdbcTemplate.update(
                "INSERT INTO status (request_id, status, timestamp, message) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (request_id) DO UPDATE SET status = ?, timestamp = ?, message = ?",
                requestId, status, LocalDateTime.now(), message,
                status, LocalDateTime.now(), message
            );
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", STATUS_FAILED);
            error.put("timestamp", LocalDateTime.now());
            error.put("message", "Failed to update status: " + e.getMessage());
        }
    }

    public Map<String, Object> getStatus(String requestId) {
        if (requestId == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", STATUS_FAILED);
            error.put("timestamp", LocalDateTime.now());
            error.put("message", "Request ID is required");
            return error;
        }

        try {
            return jdbcTemplate.queryForObject(
                "SELECT status, timestamp, message FROM status WHERE request_id = ?",
                new Object[]{requestId},
                (rs, rowNum) -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", rs.getString("status"));
                    result.put("timestamp", rs.getTimestamp("timestamp"));
                    result.put("message", rs.getString("message"));
                    return result;
                }
            );
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", STATUS_FAILED);
            error.put("timestamp", LocalDateTime.now());
            error.put("message", "Status not found for request: " + requestId);
            return error;
        }
    }
}
