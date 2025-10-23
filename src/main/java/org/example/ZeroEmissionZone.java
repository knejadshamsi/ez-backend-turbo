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
    private static final LocalTime PEAK_START = LocalTime.of(7, 0);
    private static final LocalTime PEAK_END = LocalTime.of(9, 0);
    private static final LocalTime EVENING_PEAK_START = LocalTime.of(16, 0);
    private static final LocalTime EVENING_PEAK_END = LocalTime.of(18, 0);

    private final ZeroEmissionZoneConfigGroup config;
    private final Network network;
    private final Set<Id<Link>> zoneLinks;
    private final Set<Id<Link>> alternativeRouteLinks;
    private final LocalTime startTime;
    private final LocalTime endTime;

    public enum VehicleCategory {
        ELECTRIC("ev", true, 50.0),
        LOW_EMISSION("lev", true, -100.0),
        HEAVY_EMISSION("hev", false, -500.0);

        private final String typePrefix;
        private final boolean zoneAccess;
        private final double defaultPenalty;

        VehicleCategory(String typePrefix, boolean zoneAccess, double defaultPenalty) {
            this.typePrefix = typePrefix;
            this.zoneAccess = zoneAccess;
            this.defaultPenalty = defaultPenalty;
        }

        public static VehicleCategory fromVehicleType(String vehicleType) {
            for (VehicleCategory category : values()) {
                if (vehicleType.startsWith(category.typePrefix)) {
                    return category;
                }
            }
            return LOW_EMISSION;
        }
    }

    public ZeroEmissionZone(Network network, ZeroEmissionZoneConfigGroup config) {
        this.network = network;
        this.config = config;
        this.zoneLinks = parseLinks(config.getZoneLinks());
        this.alternativeRouteLinks = parseLinks(config.getAlternativeRoutes());
        
        try {
            this.startTime = LocalTime.parse(config.getStartTime());
            this.endTime = LocalTime.parse(config.getEndTime());
        } catch (DateTimeParseException e) {
            logger.error("Invalid time format in configuration");
            throw new IllegalArgumentException("Invalid time format", e);
        }
        
        validateConfiguration();
    }

    private Set<Id<Link>> parseLinks(String linkString) {
        Set<Id<Link>> links = new HashSet<>();
        for (String linkId : linkString.split(",")) {
            links.add(Id.createLinkId(linkId.trim()));
        }
        return links;
    }

    private void validateConfiguration() {
        if (zoneLinks.isEmpty()) {
            logger.error("No zone links defined in configuration");
            throw new IllegalArgumentException("Zone links must be defined");
        }
        if (alternativeRouteLinks.isEmpty()) {
            logger.error("No alternative route links defined in configuration");
            throw new IllegalArgumentException("Alternative route links must be defined");
        }
    }

    public double calculateScore(Leg leg, String vehicleId, LocalTime currentTime) {
        if (!(leg.getRoute() instanceof NetworkRoute)) {
            return 0.0;
        }

        VehicleCategory category = VehicleCategory.fromVehicleType(vehicleId);
        NetworkRoute route = (NetworkRoute) leg.getRoute();
        
        return calculateRouteScore(route, category, currentTime);
    }

    private double calculateRouteScore(NetworkRoute route, VehicleCategory category, LocalTime currentTime) {
        double score = 0.0;
        boolean usesZone = false;
        boolean usesAlternative = false;

        // Check if route uses zone or alternative links
        for (Id<Link> linkId : route.getLinkIds()) {
            if (zoneLinks.contains(linkId)) {
                usesZone = true;
            }
            if (alternativeRouteLinks.contains(linkId)) {
                usesAlternative = true;
            }
        }

        // Calculate time-based multiplier
        double timeMultiplier = calculateTimeMultiplier(currentTime);

        // Calculate score based on vehicle category and route choice
        if (isOperatingHours(currentTime)) {
            switch (category) {
                case ELECTRIC:
                    if (usesZone) {
                        // EVs get consistent rewards, slightly higher during peak hours
                        score += config.getEvRewardValue() * timeMultiplier;
                    }
                    break;
                    
                case LOW_EMISSION:
                    if (usesZone) {
                        // LEVs get higher penalties during peak hours
                        score += config.getLevPenaltyValue() * timeMultiplier;
                    }
                    if (usesAlternative) {
                        // Increased reward for using alternative routes during peak hours
                        score += config.getLevAlternativeRewardValue() * timeMultiplier * 1.5;
                    }
                    break;
                    
                case HEAVY_EMISSION:
                    if (usesZone) {
                        // Severe penalties for HEVs during operating hours
                        score += config.getHevViolationPenaltyValue() * timeMultiplier * 2.0;
                        logger.warn("HEV violation detected during operating hours: {}", currentTime);
                    }
                    if (usesAlternative) {
                        // Strong incentive for using alternative routes
                        score += config.getLevAlternativeRewardValue() * timeMultiplier * 2.0;
                    }
                    break;
            }
        } else {
            // Outside operating hours, more lenient scoring
            if (usesZone) {
                switch (category) {
                    case ELECTRIC:
                        score += config.getEvRewardValue() * 0.5; // Reduced reward outside operating hours
                        break;
                    case LOW_EMISSION:
                        score += config.getLevPenaltyValue() * 0.3; // Minimal penalty outside operating hours
                        break;
                    case HEAVY_EMISSION:
                        score += config.getHevViolationPenaltyValue() * 0.5; // Reduced penalty outside operating hours
                        break;
                }
            }
        }

        return score;
    }

    private double calculateTimeMultiplier(LocalTime currentTime) {
        // Higher multiplier during peak hours
        if ((currentTime.isAfter(PEAK_START) && currentTime.isBefore(PEAK_END)) ||
            (currentTime.isAfter(EVENING_PEAK_START) && currentTime.isBefore(EVENING_PEAK_END))) {
            return 1.5; // Peak hour multiplier
        }
        // Regular operating hours
        else if (isOperatingHours(currentTime)) {
            return 1.0; // Standard multiplier
        }
        // Outside operating hours
        else {
            return 0.5; // Reduced multiplier
        }
    }

    public boolean isOperatingHours(LocalTime currentTime) {
        return !currentTime.isBefore(startTime) && !currentTime.isAfter(endTime);
    }

    public boolean isZoneLink(Id<Link> linkId) {
        return zoneLinks.contains(linkId);
    }

    public boolean isAlternativeLink(Id<Link> linkId) {
        return alternativeRouteLinks.contains(linkId);
    }

    public boolean isAccessAllowed(String vehicleId, LocalTime currentTime) {
        VehicleCategory category = VehicleCategory.fromVehicleType(vehicleId);

        // HEVs are strictly forbidden during operating hours
        if (category == VehicleCategory.HEAVY_EMISSION && isOperatingHours(currentTime)) {
            return false;
        }

        // Outside operating hours or other vehicle categories
        return !isOperatingHours(currentTime) || category.zoneAccess;
    }

    public Set<Id<Link>> getZoneLinks() {
        return Collections.unmodifiableSet(zoneLinks);
    }

    public Set<Id<Link>> getAlternativeRouteLinks() {
        return Collections.unmodifiableSet(alternativeRouteLinks);
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public ZeroEmissionZoneConfigGroup getConfig() {
        return config;
    }
}
