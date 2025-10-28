package org.ez.mobility.ez;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.vehicles.Vehicle;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies policy rules for the zero emission zone and transit system.
 */
@Component
public class PolicyEngine {
    private static final Logger logger = LoggerFactory.getLogger(PolicyEngine.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final StatisticsCollector statisticsCollector;
    private final Map<String, RequestPolicy> requestPolicies = new ConcurrentHashMap<>();

    @Autowired
    public PolicyEngine(StatisticsCollector statisticsCollector) {
        this.statisticsCollector = statisticsCollector;
    }

    private static class RequestPolicy {
        final Map<String, PolicyDetails> vehiclePolicies = new HashMap<>();
        final Map<String, Integer> lengthRestrictions = new HashMap<>();
        LocalTime operatingStart;
        LocalTime operatingEnd;
        final TransitConfigGroup transitConfig = new TransitConfigGroup();
    }

    public static class PolicyDetails {
        public String mode;  // "fixed", "hourly", "vehicle", "banned", "transit_stop", or "transit_line"
        public double price;
        public double interval;  // for hourly mode or transit frequency
        public double penalty;   // for banned mode
        public String vehicleMode;  // for vehicle-specific mode or transit mode (bus/metro)
        public double[] coordinates;  // for transit stops
        public String[] stopSequence;  // for transit lines
    }

    /**
     * Configures the policy engine with the specified zone configuration for a request.
     */
    public void configure(String requestId, ZeroEmissionZoneConfigGroup config) {
        try {
            RequestPolicy policy = new RequestPolicy();
            
            // Set operating hours
            policy.operatingStart = config.getOperatingStartTime();
            policy.operatingEnd = config.getOperatingEndTime();

            // Set length restrictions
            policy.lengthRestrictions.putAll(config.getLengthRestrictions());

            // Parse policy configuration
            parsePolicyConfiguration(requestId, policy, config.getPolicyConfiguration());

            // Store the policy configuration
            requestPolicies.put(requestId, policy);

            logger.info("Policy engine configured for request {} with {} vehicle policies and {} length restrictions",
                       requestId, policy.vehiclePolicies.size(), policy.lengthRestrictions.size());
                       
        } catch (Exception e) {
            logger.error("Failed to configure policy engine for request " + requestId, e);
            throw new PolicyConfigurationException("Failed to configure policy engine: " + e.getMessage(), e);
        }
    }

    private void parsePolicyConfiguration(String requestId, RequestPolicy policy, String policyConfig) {
        if (policyConfig == null || policyConfig.isEmpty()) {
            return;
        }

        try {
            String[] entries = policyConfig.split(";");
            for (String entry : entries) {
                String[] parts = entry.split(":");
                if (parts.length >= 3) {
                    String id = parts[0];
                    String mode = parts[1];
                    Map<String, String> options = parseOptions(parts[2]);
                    
                    PolicyDetails policyDetails = new PolicyDetails();
                    policyDetails.mode = mode;
                    
                    configurePolicyDetails(requestId, id, mode, options, policyDetails, policy);
                    policy.vehiclePolicies.put(id, policyDetails);
                }
            }
        } catch (Exception e) {
            throw new PolicyConfigurationException("Failed to parse policy configuration: " + e.getMessage(), e);
        }
    }

    private void configurePolicyDetails(String requestId, String id, String mode, Map<String, String> options, 
                                      PolicyDetails policy, RequestPolicy requestPolicy) {
        try {
            switch (mode) {
                case "fixed":
                    policy.price = parseDoubleOption(options, "price", 0.0);
                    break;
                case "hourly":
                    policy.price = parseDoubleOption(options, "price", 0.0);
                    policy.interval = parseDoubleOption(options, "interval", 1.0);
                    validateHourlyInterval(policy.interval);
                    break;
                case "vehicle":
                    policy.vehicleMode = options.getOrDefault("mode", "fixed");
                    policy.price = parseDoubleOption(options, "price", 0.0);
                    break;
                case "banned":
                    policy.penalty = parseDoubleOption(options, "penalty", 100.0);
                    break;
                case "transit_stop":
                    configureTransitStop(id, options, policy, requestPolicy);
                    break;
                case "transit_line":
                    configureTransitLine(id, options, policy, requestPolicy);
                    break;
                default:
                    throw new PolicyConfigurationException("Unknown policy mode: " + mode);
            }
        } catch (Exception e) {
            throw new PolicyConfigurationException("Failed to configure policy details for " + mode + ": " + e.getMessage(), e);
        }
    }

    private void configureTransitStop(String id, Map<String, String> options, PolicyDetails policy, RequestPolicy requestPolicy) {
        policy.vehicleMode = options.getOrDefault("mode", "bus");
        String[] coords = id.split(",");
        if (coords.length != 2) {
            throw new PolicyConfigurationException("Invalid transit stop coordinates: " + id);
        }
        
        policy.coordinates = new double[]{
            Double.parseDouble(coords[0]),
            Double.parseDouble(coords[1])
        };
        
        requestPolicy.transitConfig.addTransitStop(new TransitConfigGroup.TransitStop(
            "stop_" + id,
            policy.coordinates[0],
            policy.coordinates[1],
            policy.vehicleMode
        ));
    }

    private void configureTransitLine(String id, Map<String, String> options, PolicyDetails policy, RequestPolicy requestPolicy) {
        policy.vehicleMode = options.getOrDefault("mode", "bus");
        policy.interval = parseDoubleOption(options, "interval", 30.0);
        policy.stopSequence = id.split(",");
        
        if (policy.stopSequence.length < 2) {
            throw new PolicyConfigurationException("Transit line must have at least 2 stops: " + id);
        }
        
        requestPolicy.transitConfig.addTransitLine(new TransitConfigGroup.TransitLine(
            id,
            policy.vehicleMode,
            requestPolicy.operatingStart,
            requestPolicy.operatingEnd,
            policy.interval,
            policy.vehicleMode.equals("metro") ? 50.0 : 30.0,
            policy.stopSequence
        ));
    }

    private double parseDoubleOption(Map<String, String> options, String key, double defaultValue) {
        String value = options.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new PolicyConfigurationException("Invalid number format for " + key + ": " + value);
        }
    }

    private void validateHourlyInterval(double interval) {
        if (interval < 0.5 || interval > 23.0) {
            throw new PolicyConfigurationException("Hourly interval must be between 0.5 and 23.0: " + interval);
        }
    }

    private Map<String, String> parseOptions(String optionsStr) {
        Map<String, String> options = new HashMap<>();
        String[] pairs = optionsStr.split(",");
        for (String pair : pairs) {
            String[] keyValue = pair.split("=");
            if (keyValue.length == 2) {
                options.put(keyValue[0], keyValue[1]);
            }
        }
        return options;
    }

    /**
     * Calculates the charge for a vehicle entering the zone.
     */
    public double calculateCharge(String requestId, Id<Vehicle> vehicleId, String vehicleType, LocalTime entryTime) {
        RequestPolicy policy = requestPolicies.get(requestId);
        if (policy == null) {
            logger.warn("No policy found for request: {}", requestId);
            return 0.0;
        }

        if (!isOperatingHour(requestId, entryTime)) {
            statisticsCollector.recordOperatingHourViolation(requestId, vehicleId.toString(), 
                LocalDateTime.now().with(entryTime));
            return 0.0;
        }

        PolicyDetails vehiclePolicy = policy.vehiclePolicies.get(vehicleType);
        if (vehiclePolicy == null) {
            return 0.0;  // Tier 1 treatment - free entry
        }

        double charge = 0.0;
        switch (vehiclePolicy.mode) {
            case "fixed":
                charge = vehiclePolicy.price;
                break;
            case "hourly":
                double hours = Math.ceil(vehiclePolicy.interval);
                charge = vehiclePolicy.price * hours;
                break;
            case "vehicle":
                charge = vehiclePolicy.price;
                break;
            case "banned":
                charge = vehiclePolicy.penalty;
                statisticsCollector.recordBannedVehicleAttempt(requestId, vehicleId.toString(), 
                    LocalDateTime.now().with(entryTime));
                break;
        }

        statisticsCollector.recordVehicleEntry(requestId, vehicleId.toString(), vehiclePolicy.mode, 
            charge, LocalDateTime.now().with(entryTime));
        return charge;
    }

    /**
     * Checks if a vehicle is allowed in the zone based on its length restriction
     * and whether it's banned.
     */
    public boolean isVehicleAllowed(String requestId, String vehicleType, double length) {
        RequestPolicy policy = requestPolicies.get(requestId);
        if (policy == null) {
            logger.warn("No policy found for request: {}", requestId);
            return true;
        }

        // Check if vehicle is banned
        PolicyDetails vehiclePolicy = policy.vehiclePolicies.get(vehicleType);
        if (vehiclePolicy != null && "banned".equals(vehiclePolicy.mode)) {
            logger.debug("Vehicle type {} is banned from the zone in request {}", vehicleType, requestId);
            return false;
        }

        // Check length restrictions
        Integer maxLength = policy.lengthRestrictions.get(vehicleType);
        if (maxLength == null) {
            return true;  // No restriction for this vehicle type
        }

        boolean allowed = length <= maxLength;
        if (!allowed) {
            statisticsCollector.recordLengthRestrictionViolation(requestId, vehicleType, length, maxLength);
        }
        
        logger.debug("Vehicle type {} with length {} is {} based on length restrictions in request {}",
                    vehicleType, length, allowed ? "allowed" : "not allowed", requestId);
        return allowed;
    }

    /**
     * Checks if the given time is within operating hours.
     */
    public boolean isOperatingHour(String requestId, LocalTime time) {
        RequestPolicy policy = requestPolicies.get(requestId);
        if (policy == null) {
            logger.warn("No policy found for request: {}", requestId);
            return true;
        }

        if (policy.operatingStart.isBefore(policy.operatingEnd)) {
            return !time.isBefore(policy.operatingStart) && !time.isAfter(policy.operatingEnd);
        } else {
            // Handles overnight operation (e.g., 22:00 - 06:00)
            return !time.isBefore(policy.operatingStart) || !time.isAfter(policy.operatingEnd);
        }
    }

    /**
     * Cleans up policy data for a completed request.
     */
    public void cleanup(String requestId) {
        requestPolicies.remove(requestId);
        logger.info("Cleaned up policy data for request: {}", requestId);
    }

    public static class PolicyConfigurationException extends RuntimeException {
        public PolicyConfigurationException(String message) {
            super(message);
        }

        public PolicyConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
