package org.example;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.core.population.routes.NetworkRoute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.*;

public class ZeroEmissionZone {
    private static final Logger logger = LoggerFactory.getLogger(ZeroEmissionZone.class);
    private static final double ZERO_EMISSION_ZONE_PENALTY = 50.0;
    private static final double SEVERE_PENALTY_MULTIPLIER = 2.0;
    private static final double CONSECUTIVE_VIOLATION_MULTIPLIER = 1.5;

    // Configuration parameters from config group
    private final ZeroEmissionZoneConfigGroup config;
    private final Network network;
    private final Set<Id<Link>> zeroEmissionZoneLinkIds;
    private final Map<String, Integer> violationCounts = new HashMap<>();
    private final Map<String, List<Id<Link>>> consecutiveViolations = new HashMap<>();
    private final Map<String, Double> emissionPenalties = new HashMap<>();

    public ZeroEmissionZone(Network network, ZeroEmissionZoneConfigGroup config) {
        this.network = network;
        this.config = config;
        this.zeroEmissionZoneLinkIds = findZeroEmissionZoneLinks();
        validateConfiguration();
    }

    private void validateConfiguration() {
        try {
            LocalTime.parse(config.getStartTime());
            LocalTime.parse(config.getEndTime());
        } catch (DateTimeParseException e) {
            logger.error("Invalid time format. Use HH:mm:ss format.");
            throw new IllegalArgumentException("Invalid time format for Zero Emission Zone", e);
        }

        if (config.getPenaltyScore() >= 0) {
            logger.warn("Penalty score should be negative. Current value: {}", config.getPenaltyScore());
        }

        if (config.getMaxViolationsBeforeSeverePenalty() <= 0) {
            logger.error("Max violations must be a positive number");
            throw new IllegalArgumentException("Max violations must be positive");
        }
    }

    private Set<Id<Link>> findZeroEmissionZoneLinks() {
        Set<Id<Link>> zezLinks = new HashSet<>();
        for (Link link : network.getLinks().values()) {
            Object type = link.getAttributes().getAttribute("type");
            if (type != null && "zero_emission_zone".equals(type.toString())) {
                zezLinks.add(link.getId());
            }
        }
        logger.info("Identified {} zero emission zone links.", zezLinks.size());
        return zezLinks;
    }

    public double calculatePenalty(Leg leg) {
        if (!(leg.getRoute() instanceof NetworkRoute)) {
            return 0.0;
        }

        NetworkRoute route = (NetworkRoute) leg.getRoute();
        double penalty = 0.0;
        String vehicleId = leg.getAttributes().getAttribute("vehicleId").toString();
        List<Id<Link>> currentViolations = new ArrayList<>();

        // Check start link
        penalty += checkLinkPenalty(route.getStartLinkId(), vehicleId, currentViolations);
        
        // Check intermediate links
        for (Id<Link> linkId : route.getLinkIds()) {
            penalty += checkLinkPenalty(linkId, vehicleId, currentViolations);
        }
        
        // Check end link
        penalty += checkLinkPenalty(route.getEndLinkId(), vehicleId, currentViolations);

        // Update consecutive violations
        if (!currentViolations.isEmpty()) {
            consecutiveViolations.put(vehicleId, currentViolations);
        } else {
            consecutiveViolations.remove(vehicleId);
        }

        return penalty;
    }

    private double checkLinkPenalty(Id<Link> linkId, String vehicleId, List<Id<Link>> currentViolations) {
        if (zeroEmissionZoneLinkIds.contains(linkId)) {
            currentViolations.add(linkId);
            int violations = violationCounts.getOrDefault(vehicleId, 0) + 1;
            violationCounts.put(vehicleId, violations);

            double penalty = ZERO_EMISSION_ZONE_PENALTY;

            // Apply severe penalty if over maximum violations
            if (violations > config.getMaxViolationsBeforeSeverePenalty()) {
                penalty *= SEVERE_PENALTY_MULTIPLIER;
            }

            // Apply consecutive violation multiplier
            List<Id<Link>> previousViolations = consecutiveViolations.get(vehicleId);
            if (previousViolations != null && !previousViolations.isEmpty()) {
                penalty *= CONSECUTIVE_VIOLATION_MULTIPLIER;
            }

            return penalty;
        }
        return 0.0;
    }

    public double calculateRouteCost(NetworkRoute route) {
        double cost = 0.0;
        
        // Check start link
        if (isInZeroEmissionZone(route.getStartLinkId())) {
            cost += ZERO_EMISSION_ZONE_PENALTY;
        }
        
        // Check intermediate links
        for (Id<Link> linkId : route.getLinkIds()) {
            if (isInZeroEmissionZone(linkId)) {
                cost += ZERO_EMISSION_ZONE_PENALTY;
            }
        }
        
        // Check end link
        if (isInZeroEmissionZone(route.getEndLinkId())) {
            cost += ZERO_EMISSION_ZONE_PENALTY;
        }
        
        return cost;
    }

    public boolean isInZeroEmissionZone(Id<Link> linkId) {
        return zeroEmissionZoneLinkIds.contains(linkId);
    }

    public void resetViolations() {
        violationCounts.clear();
        consecutiveViolations.clear();
    }

    public void addEmissionPenalty(String emissionType, double penalty) {
        emissionPenalties.put(emissionType, penalty);
    }
    
    public Map<String, Double> getEmissionPenalties() {
        return new HashMap<>(emissionPenalties);
    }

    public Set<Id<Link>> getZeroEmissionZoneLinkIds() {
        return new HashSet<>(zeroEmissionZoneLinkIds);
    }

    public Map<String, Integer> getViolationCounts() {
        return new HashMap<>(violationCounts);
    }

    public ZeroEmissionZoneConfigGroup getConfig() {
        return config;
    }
}
