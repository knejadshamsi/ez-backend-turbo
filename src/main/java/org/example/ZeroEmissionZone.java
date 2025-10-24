package org.example;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the configuration and rules for a Zero Emission Zone (ZEZ) in a transportation simulation.
 * 
 * This class handles:
 * - Defining ZEZ network links
 * - Managing vehicle type restrictions
 * - Enforcing temporal and spatial access rules
 * - Validating ZEZ configuration
 * 
 * Key responsibilities include:
 * - Determining vehicle access to specific links
 * - Identifying peak hours and operating times
 * - Classifying vehicle types
 */
public class ZeroEmissionZone {
    private static final Logger logger = LoggerFactory.getLogger(ZeroEmissionZone.class);

    private final ZeroEmissionZoneConfigGroup config;
    private final Network network;
    private final Set<Id<Link>> zoneLinks;
    private final Set<Id<Link>> alternativeRouteLinks;
    private final Set<String> allowedVehicleTypes;
    private final LocalTime startTime;
    private final LocalTime endTime;

    /**
     * Defines vehicle categories based on emission levels and zone access permissions.
     * Provides methods to categorize and validate vehicle types.
     */
    public enum VehicleCategory {
        ELECTRIC("ev_car", true),
        LOW_EMISSION("lev_car", true),
        HEAVY_EMISSION("hev_car", false);

        private final String typePrefix;
        private final boolean zoneAccess;

        /**
         * Constructor for vehicle category.
         * 
         * @param typePrefix Identifier prefix for the vehicle type
         * @param zoneAccess Whether vehicles in this category can access the ZEZ
         */
        VehicleCategory(String typePrefix, boolean zoneAccess) {
            this.typePrefix = typePrefix;
            this.zoneAccess = zoneAccess;
        }

        /**
         * Determines the vehicle category based on the vehicle type string.
         * 
         * @param vehicleType String representing the vehicle type
         * @return Corresponding VehicleCategory, defaulting to LOW_EMISSION if unrecognized
         */
        public static VehicleCategory fromVehicleType(String vehicleType) {
            if (vehicleType == null) return LOW_EMISSION;
            
            for (VehicleCategory category : values()) {
                if (vehicleType.contains(category.typePrefix)) {
                    return category;
                }
            }
            return LOW_EMISSION;
        }

        /**
         * Checks if vehicles in this category have access to the Zero Emission Zone.
         * 
         * @return true if vehicles can enter the zone, false otherwise
         */
        public boolean hasZoneAccess() {
            return zoneAccess;
        }
    }

    /**
     * Constructs a Zero Emission Zone based on network configuration and ZEZ-specific settings.
     * 
     * @param network The transportation network
     * @param config Configuration parameters for the Zero Emission Zone
     * @throws IllegalArgumentException if configuration parsing fails
     * @throws IllegalStateException if network validation fails
     */
    public ZeroEmissionZone(Network network, ZeroEmissionZoneConfigGroup config) {
        this.network = network;
        this.config = config;
        
        // Parse ZEZ and alternative route links from configuration
        this.zoneLinks = parseLinks(config.getZoneLinks());
        this.alternativeRouteLinks = parseLinks(config.getAlternativeRoutes());
        this.allowedVehicleTypes = new HashSet<>(Arrays.asList(config.getAllowedVehicleTypesArray()));
        
        try {
            // Parse and log ZEZ operating times
            this.startTime = LocalTime.parse(config.getStartTime());
            this.endTime = LocalTime.parse(config.getEndTime());
            logger.info("ZEZ operating hours: {} to {}", startTime, endTime);
            logger.info("ZEZ links: {}", zoneLinks);
            logger.info("Alternative routes: {}", alternativeRouteLinks);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid time format in config: " + e.getMessage(), e);
        }
        
        validateConfiguration();
    }

    /**
     * Parses a comma-separated list of link IDs into a set of link identifiers.
     * 
     * @param linkList Comma-separated string of link IDs
     * @return Set of parsed link IDs
     * @throws IllegalArgumentException if link list is invalid
     */
    private Set<Id<Link>> parseLinks(String linkList) {
        if (linkList == null || linkList.trim().isEmpty()) {
            throw new IllegalArgumentException("Link list cannot be empty");
        }

        Set<Id<Link>> links = new HashSet<>();
        for (String linkId : linkList.split(",")) {
            String trimmedId = linkId.trim();
            if (!trimmedId.isEmpty()) {
                links.add(Id.createLinkId(trimmedId));
            }
        }

        if (links.isEmpty()) {
            throw new IllegalArgumentException("No valid links found in: " + linkList);
        }

        return links;
    }

    /**
     * Validates the Zero Emission Zone configuration.
     * Checks:
     * - Existence of ZEZ and bypass route links in the network
     * - No overlap between ZEZ and alternative routes
     * - Valid operating hours
     * 
     * @throws IllegalStateException if configuration is invalid
     */
    private void validateConfiguration() {
        // Validate ZEZ links exist
        for (Id<Link> linkId : zoneLinks) {
            if (network.getLinks().get(linkId) == null) {
                throw new IllegalStateException("ZEZ link " + linkId + " not found in network");
            }
        }
        
        // Validate bypass routes exist
        for (Id<Link> linkId : alternativeRouteLinks) {
            if (network.getLinks().get(linkId) == null) {
                throw new IllegalStateException("Bypass link " + linkId + " not found in network");
            }
        }

        // Check for overlap between ZEZ and alternative routes
        Set<Id<Link>> intersection = new HashSet<>(zoneLinks);
        intersection.retainAll(alternativeRouteLinks);
        if (!intersection.isEmpty()) {
            throw new IllegalStateException("Links " + intersection + " are defined as both ZEZ and bypass routes");
        }

        // Validate operating hours
        if (startTime.equals(endTime)) {
            throw new IllegalStateException("Start time and end time cannot be the same");
        }

        logger.info("ZEZ configuration validated successfully");
    }

    /**
     * Determines if access is prohibited for a specific vehicle on a link during a given time.
     * 
     * @param vehicleType Type of vehicle
     * @param linkId Identifier of the link
     * @param time Time of access attempt
     * @return true if access is prohibited, false otherwise
     */
    public boolean isAccessProhibited(String vehicleType, Id<Link> linkId, LocalTime time) {
        VehicleCategory category = VehicleCategory.fromVehicleType(vehicleType);
        boolean prohibited = isOperatingHours(time) && 
                           isZoneLink(linkId) && 
                           category == VehicleCategory.HEAVY_EMISSION;
        
        if (prohibited) {
            logger.debug("Access prohibited for {} on link {} at {}", vehicleType, linkId, time);
        }
        
        return prohibited;
    }

    /**
     * Checks if the given time falls within peak traffic hours.
     * 
     * @param time Time to check
     * @return true if time is during peak hours, false otherwise
     */
    public boolean isPeakHour(LocalTime time) {
        int hour = time.getHour();
        return (hour >= 7 && hour <= 9) || (hour >= 16 && hour <= 19);
    }

    /**
     * Determines if the given time is within the Zero Emission Zone's operational hours.
     * 
     * @param time Time to check
     * @return true if time is within operational hours, false otherwise
     */
    public boolean isOperatingHours(LocalTime time) {
        if (time == null) {
            logger.warn("Null time provided to isOperatingHours");
            return false;
        }
        return !time.isBefore(startTime) && !time.isAfter(endTime);
    }

    /**
     * Checks if a link is part of the Zero Emission Zone.
     * 
     * @param linkId Link identifier to check
     * @return true if link is in the ZEZ, false otherwise
     */
    public boolean isZoneLink(Id<Link> linkId) {
        if (linkId == null) {
            logger.warn("Null linkId provided to isZoneLink");
            return false;
        }
        return zoneLinks.contains(linkId);
    }

    /**
     * Checks if a link is part of the alternative (bypass) routes.
     * 
     * @param linkId Link identifier to check
     * @return true if link is an alternative route, false otherwise
     */
    public boolean isAlternativeLink(Id<Link> linkId) {
        if (linkId == null) {
            logger.warn("Null linkId provided to isAlternativeLink");
            return false;
        }
        return alternativeRouteLinks.contains(linkId);
    }

    /**
     * Verifies if a specific vehicle type is allowed in the Zero Emission Zone.
     * 
     * @param vehicleType Vehicle type to check
     * @return true if vehicle type is allowed, false otherwise
     */
    public boolean isVehicleAllowed(String vehicleType) {
        if (vehicleType == null) {
            logger.warn("Null vehicleType provided to isVehicleAllowed");
            return false;
        }
        return allowedVehicleTypes.contains(vehicleType);
    }

    // Getters with unmodifiable views to prevent external modification
    public Set<Id<Link>> getZoneLinks() {
        return Collections.unmodifiableSet(zoneLinks);
    }

    public Set<Id<Link>> getAlternativeRouteLinks() {
        return Collections.unmodifiableSet(alternativeRouteLinks);
    }

    public Set<String> getAllowedVehicleTypes() {
        return Collections.unmodifiableSet(allowedVehicleTypes);
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
