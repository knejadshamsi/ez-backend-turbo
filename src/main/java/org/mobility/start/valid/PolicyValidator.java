package org.mobility.start.valid;

import org.mobility.utils.WorkflowResult;
import org.springframework.stereotype.Component;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.w3c.dom.Element;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Component
public class PolicyValidator {
    public WorkflowResult validate(List<Map<String, Object>> policies) {
        if (policies == null || policies.isEmpty()) {
            return WorkflowResult.error(400, "At least one policy is required");
        }

        try {
            Set<String> validVehicleTypes = loadValidVehicleTypes();
            Map<String, Map<String, List<TimeRange>>> vehicleTypePolicies = new HashMap<>();

            for (Map<String, Object> policy : policies) {
                String vehicleType = (String) policy.get("vehicleType");
                if (vehicleType == null || vehicleType.trim().isEmpty()) {
                    return WorkflowResult.error(400, "Vehicle type is required for all policies");
                }

                if (!validVehicleTypes.contains(vehicleType)) {
                    return WorkflowResult.error(400, "Invalid vehicle type: " + vehicleType);
                }

                List<String> operatingHours = (List<String>) policy.get("operatingHours");
                if (operatingHours == null || operatingHours.size() != 2) {
                    return WorkflowResult.error(400, "Operating hours must contain start and end times");
                }

                try {
                    LocalTime start = LocalTime.parse(operatingHours.get(0), DateTimeFormatter.ofPattern("HH:mm"));
                    LocalTime end = LocalTime.parse(operatingHours.get(1), DateTimeFormatter.ofPattern("HH:mm"));

                    if (start.equals(end)) {
                        return WorkflowResult.error(400, "Start and end times cannot be the same");
                    }

                    Object policyValues = policy.get("policyValues");
                    String policyKey = policyValues instanceof String ? 
                        (String) policyValues : 
                        String.join(",", ((List<?>) policyValues).stream().map(String::valueOf).toArray(String[]::new));

                    vehicleTypePolicies
                        .computeIfAbsent(vehicleType, k -> new HashMap<>())
                        .computeIfAbsent(policyKey, k -> new ArrayList<>())
                        .add(new TimeRange(start, end));

                } catch (DateTimeParseException e) {
                    return WorkflowResult.error(400, "Invalid time format. Use HH:mm format");
                }
            }

            for (Map<String, List<TimeRange>> typePolicies : vehicleTypePolicies.values()) {
                for (List<TimeRange> timeRanges : typePolicies.values()) {
                    timeRanges.sort(Comparator.comparing(tr -> tr.start));
                    for (int i = 0; i < timeRanges.size() - 1; i++) {
                        if (timeRanges.get(i).overlaps(timeRanges.get(i + 1))) {
                            return WorkflowResult.error(400, "Overlapping operating hours detected for same policy type");
                        }
                    }
                }
            }

            List<Map<String, Object>> mergedPolicies = new ArrayList<>();
            for (Map.Entry<String, Map<String, List<TimeRange>>> vehicleEntry : vehicleTypePolicies.entrySet()) {
                for (Map.Entry<String, List<TimeRange>> policyEntry : vehicleEntry.getValue().entrySet()) {
                    List<TimeRange> timeRanges = policyEntry.getValue();
                    for (TimeRange range : timeRanges) {
                        Map<String, Object> mergedPolicy = new HashMap<>();
                        mergedPolicy.put("vehicleType", vehicleEntry.getKey());
                        mergedPolicy.put("policyValues", policyEntry.getKey().contains(",") ? 
                            Arrays.asList(policyEntry.getKey().split(",")) : policyEntry.getKey());
                        mergedPolicy.put("operatingHours", Arrays.asList(
                            range.start.format(DateTimeFormatter.ofPattern("HH:mm")),
                            range.end.format(DateTimeFormatter.ofPattern("HH:mm"))
                        ));
                        mergedPolicies.add(mergedPolicy);
                    }
                }
            }

            return WorkflowResult.success(Map.of("policies", mergedPolicies));
        } catch (Exception e) {
            return WorkflowResult.error(500, "Failed to validate policies: " + e.getMessage());
        }
    }

    private Set<String> loadValidVehicleTypes() throws Exception {
        Path basePath = Paths.get(System.getProperty("user.dir"));
        Path vehicleTypesPath = basePath.resolve("src/main/resources/matsim-input/vehicle_types.xml");
        
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document doc = dBuilder.parse(new File(vehicleTypesPath.toString()));
        
        doc.getDocumentElement().normalize();
        
        NodeList vehicleTypeNodes = doc.getElementsByTagName("vehicleType");
        Set<String> types = new HashSet<>();
        
        for (int i = 0; i < vehicleTypeNodes.getLength(); i++) {
            Element vehicleType = (Element) vehicleTypeNodes.item(i);
            types.add(vehicleType.getAttribute("id"));
        }
        
        return types;
    }

    private static class TimeRange {
        final LocalTime start;
        final LocalTime end;

        TimeRange(LocalTime start, LocalTime end) {
            this.start = start;
            this.end = end;
        }

        boolean overlaps(TimeRange other) {
            if (start.equals(end) || other.start.equals(other.end)) {
                return true;
            }
            
            boolean thisWraps = start.isAfter(end);
            boolean otherWraps = other.start.isAfter(other.end);

            if (!thisWraps && !otherWraps) {
                return !end.isBefore(other.start) && !other.end.isBefore(start);
            }

            if (thisWraps && otherWraps) {
                return true;
            }

            if (thisWraps) {
                return !other.end.isBefore(start) || !other.start.isAfter(end);
            }

            return !end.isBefore(other.start) || !start.isAfter(other.end);
        }
    }
}
