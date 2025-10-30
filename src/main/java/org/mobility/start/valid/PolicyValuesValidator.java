package org.mobility.start.valid;

import org.mobility.utils.WorkflowResult;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class PolicyValuesValidator {
    public WorkflowResult validate(Object policyValues) {
        if (policyValues == null) {
            return WorkflowResult.error(400, "Policy values are required");
        }

        try {
            if (policyValues instanceof String) {
                String value = (String) policyValues;
                if (!value.equals("banned") && !value.equals("free")) {
                    return WorkflowResult.error(400, "String policy value must be 'banned' or 'free'");
                }
            } else if (policyValues instanceof List) {
                List<?> values = (List<?>) policyValues;
                
                if (values.isEmpty() || values.size() > 3) {
                    return WorkflowResult.error(400, "Policy values list must contain 1-3 numbers");
                }

                for (Object value : values) {
                    if (!(value instanceof Number)) {
                        return WorkflowResult.error(400, "Policy values must be numbers");
                    }

                    double numValue = ((Number) value).doubleValue();
                    if (numValue < 0 && numValue != Double.NEGATIVE_INFINITY) {
                        return WorkflowResult.error(400, "Policy values must be positive numbers or negative infinity");
                    }
                }

                if (values.size() == 3) {
                    double interval = ((Number) values.get(2)).doubleValue();
                    if (interval <= 0) {
                        return WorkflowResult.error(400, "Interval value must be positive");
                    }
                }
            } else {
                return WorkflowResult.error(400, "Policy values must be either a string or a list of numbers");
            }

            return WorkflowResult.success(Map.of("policyValues", policyValues));
        } catch (Exception e) {
            return WorkflowResult.error(500, "Failed to validate policy values: " + e.getMessage());
        }
    }
}
