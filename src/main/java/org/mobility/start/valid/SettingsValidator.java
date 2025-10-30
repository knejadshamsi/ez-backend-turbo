package org.mobility.start.valid;

import org.mobility.utils.WorkflowResult;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class SettingsValidator {
    public WorkflowResult validate(Map<String, Object> settings) {
        if (settings == null) {
            return WorkflowResult.error(400, "Settings are required");
        }

        try {
            if (!settings.containsKey("simulateAllAgents")) {
                return WorkflowResult.error(400, "simulateAllAgents setting is required");
            }

            if (!(settings.get("simulateAllAgents") instanceof Boolean)) {
                return WorkflowResult.error(400, "simulateAllAgents must be a boolean");
            }

            if (!settings.containsKey("simulationIterations")) {
                return WorkflowResult.error(400, "simulationIterations setting is required");
            }

            Object iterationsObj = settings.get("simulationIterations");
            if (!(iterationsObj instanceof Number)) {
                return WorkflowResult.error(400, "simulationIterations must be a number");
            }

            int iterations;
            if (iterationsObj instanceof Integer) {
                iterations = (Integer) iterationsObj;
            } else {
                iterations = ((Number) iterationsObj).intValue();
            }

            if (iterations < 1 || iterations > 10) {
                return WorkflowResult.error(400, "simulationIterations must be between 1 and 10");
            }

            if (!settings.containsKey("modeRestriction")) {
                return WorkflowResult.error(400, "modeRestriction setting is required");
            }

            Object restrictionsObj = settings.get("modeRestriction");
            if (!(restrictionsObj instanceof Map)) {
                return WorkflowResult.error(400, "modeRestriction must be an object");
            }

            Map<String, List<Integer>> restrictions = (Map<String, List<Integer>>) restrictionsObj;
            if (!restrictions.containsKey("walkRestrictions") || !restrictions.containsKey("bikeRestrictions")) {
                return WorkflowResult.error(400, "Both walkRestrictions and bikeRestrictions are required");
            }

            List<Integer> walkRestrictions = restrictions.get("walkRestrictions");
            List<Integer> bikeRestrictions = restrictions.get("bikeRestrictions");

            if (walkRestrictions == null || walkRestrictions.size() != 4) {
                return WorkflowResult.error(400, "walkRestrictions must contain exactly 4 values");
            }

            if (bikeRestrictions == null || bikeRestrictions.size() != 4) {
                return WorkflowResult.error(400, "bikeRestrictions must contain exactly 4 values");
            }

            for (Integer value : walkRestrictions) {
                if (value == null || value < 0) {
                    return WorkflowResult.error(400, "walkRestrictions values must be non-negative integers");
                }
            }

            for (Integer value : bikeRestrictions) {
                if (value == null || value < 0) {
                    return WorkflowResult.error(400, "bikeRestrictions values must be non-negative integers");
                }
            }

            return WorkflowResult.success(Map.of("settings", settings));
        } catch (ClassCastException e) {
            return WorkflowResult.error(400, "Invalid settings format");
        } catch (Exception e) {
            return WorkflowResult.error(500, "Failed to validate settings: " + e.getMessage());
        }
    }
}
