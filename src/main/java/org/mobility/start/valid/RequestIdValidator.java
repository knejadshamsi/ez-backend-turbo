package org.mobility.start.valid;

import org.mobility.utils.WorkflowResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.UUID;

@Component
public class RequestIdValidator {
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public RequestIdValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public WorkflowResult validate(String requestId) {
        if (requestId == null || requestId.trim().isEmpty()) {
            return WorkflowResult.error(400, "Request ID is required");
        }

        UUID uuid;
        try {
            uuid = UUID.fromString(requestId);
            if (!uuid.toString().equals(requestId)) {
                return WorkflowResult.error(400, "Invalid UUID format for request ID");
            }
        } catch (IllegalArgumentException e) {
            return WorkflowResult.error(400, "Invalid UUID format for request ID");
        }

        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (" +
                "  SELECT request_id FROM status WHERE request_id = ? " +
                "  UNION " +
                "  SELECT request_id FROM agents WHERE request_id = ?" +
                ") AS existing_requests",
                Integer.class,
                requestId,
                requestId
            );

            if (count != null && count > 0) {
                return WorkflowResult.error(409, "Request ID already exists");
            }

            return WorkflowResult.success(Map.of("requestId", requestId));
        } catch (Exception e) {
            return WorkflowResult.error(500, "Database validation failed: " + e.getMessage());
        }
    }
}
