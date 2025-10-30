package org.mobility.cancel.valid;

import org.mobility.utils.WorkflowResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.UUID;

@Component("cancellationValidationManager")
public class ValidationManager {
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public ValidationManager(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public WorkflowResult validate(String requestId) {
        if (requestId == null || requestId.trim().isEmpty()) {
            return WorkflowResult.error(400, "Request ID is required");
        }

        try {
            UUID.fromString(requestId);
        } catch (IllegalArgumentException e) {
            return WorkflowResult.error(400, "Invalid UUID format for request ID");
        }

        try {
            Map<String, Object> status = jdbcTemplate.queryForMap(
                "SELECT status FROM status WHERE request_id = ?",
                requestId
            );

            if (status == null) {
                return WorkflowResult.error(404, "No simulation found for request ID: " + requestId);
            }

            String currentStatus = (String) status.get("status");
            if ("ERROR".equals(currentStatus) || "COMPLETED".equals(currentStatus)) {
                return WorkflowResult.error(400, "Cannot cancel simulation in " + currentStatus + " state");
            }

            return WorkflowResult.success(Map.of("requestId", requestId));
        } catch (Exception e) {
            return WorkflowResult.error(500, "Validation failed: " + e.getMessage());
        }
    }
}
