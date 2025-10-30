package org.mobility.cancel;

import org.mobility.cancel.valid.ValidationManager;
import org.mobility.manager.StateManager;
import org.mobility.status.StatusManager;
import org.mobility.utils.WorkflowResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class CancellationManager {
    private final ValidationManager validationManager;
    private final StateManager stateManager;
    private final StatusManager statusManager;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public CancellationManager(
        @Qualifier("cancellationValidationManager") ValidationManager validationManager,
        StateManager stateManager,
        StatusManager statusManager,
        JdbcTemplate jdbcTemplate
    ) {
        this.validationManager = validationManager;
        this.stateManager = stateManager;
        this.statusManager = statusManager;
        this.jdbcTemplate = jdbcTemplate;
    }

    public WorkflowResult cancelSimulation(Map<String, Object> request) {
        String requestId = (String) request.get("requestId");
        
        WorkflowResult validationResult = validationManager.validate(requestId);
        if (!validationResult.isSuccess()) {
            return validationResult;
        }

        try {
            stateManager.requestCancellation(requestId);
            statusManager.updateStatus(requestId, StatusManager.STATUS_FAILED, "Simulation cancelled by user request");
            
            jdbcTemplate.update("DELETE FROM routes WHERE request_id = ?", requestId);
            jdbcTemplate.update("DELETE FROM legs WHERE request_id = ?", requestId);
            jdbcTemplate.update("DELETE FROM activities WHERE request_id = ?", requestId);
            jdbcTemplate.update("DELETE FROM agents WHERE request_id = ?", requestId);
            
            stateManager.removeState(requestId);
            
            return WorkflowResult.success(Map.of(
                "requestId", requestId,
                "status", "cancelled"
            ));
        } catch (Exception e) {
            return WorkflowResult.error(500, "Failed to cancel simulation: " + e.getMessage());
        }
    }
}
