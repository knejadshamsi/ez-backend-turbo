package org.mobility.start.valid;

import org.mobility.utils.WorkflowResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

@Component("startValidationManager")
public class ValidationManager {
    private final RequestIdValidator requestIdValidator;
    private final ZoneLinksValidator zoneLinksValidator;
    private final PolicyValidator policyValidator;
    private final PolicyValuesValidator policyValuesValidator;
    private final SettingsValidator settingsValidator;

    @Autowired
    public ValidationManager(
        RequestIdValidator requestIdValidator,
        ZoneLinksValidator zoneLinksValidator,
        PolicyValidator policyValidator,
        PolicyValuesValidator policyValuesValidator,
        SettingsValidator settingsValidator
    ) {
        this.requestIdValidator = requestIdValidator;
        this.zoneLinksValidator = zoneLinksValidator;
        this.policyValidator = policyValidator;
        this.policyValuesValidator = policyValuesValidator;
        this.settingsValidator = settingsValidator;
    }

    public WorkflowResult validate(Map<String, Object> request) {
        try {
            String requestId = (String) request.get("requestId");
            WorkflowResult requestIdResult = requestIdValidator.validate(requestId);
            if (!requestIdResult.isSuccess()) {
                return requestIdResult;
            }

            List<String> errors = new ArrayList<>();

            Object zoneLinksObj = request.get("zoneLinks");
            if (zoneLinksObj instanceof List) {
                List<?> zoneLinks = (List<?>) zoneLinksObj;
                List<String> zoneLinksStr = zoneLinks.stream()
                    .map(Object::toString)
                    .collect(Collectors.toList());
                
                WorkflowResult zoneLinksResult = zoneLinksValidator.validate(zoneLinksStr);
                if (!zoneLinksResult.isSuccess()) {
                    errors.add(zoneLinksResult.getMessage());
                }
            } else {
                errors.add("zoneLinks must be a list");
            }

            List<Map<String, Object>> policies = (List<Map<String, Object>>) request.get("policy");
            WorkflowResult policyResult = policyValidator.validate(policies);
            if (!policyResult.isSuccess()) {
                errors.add(policyResult.getMessage());
            }

            if (policies != null) {
                for (Map<String, Object> policy : policies) {
                    WorkflowResult policyValuesResult = policyValuesValidator.validate(policy.get("policyValues"));
                    if (!policyValuesResult.isSuccess()) {
                        errors.add(policyValuesResult.getMessage());
                    }
                }
            }

            Map<String, Object> settings = (Map<String, Object>) request.get("settings");
            WorkflowResult settingsResult = settingsValidator.validate(settings);
            if (!settingsResult.isSuccess()) {
                errors.add(settingsResult.getMessage());
            }

            if (!errors.isEmpty()) {
                return WorkflowResult.error(400, String.join("; ", errors));
            }

            return WorkflowResult.success(request);
        } catch (Exception e) {
            return WorkflowResult.error(400, "Invalid request format: " + e.getMessage());
        }
    }
}
