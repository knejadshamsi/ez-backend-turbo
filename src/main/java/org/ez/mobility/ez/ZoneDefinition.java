package org.ez.mobility.ez;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Defines properties of emission zones, including boundaries and restrictions.
 * This class manages the spatial definition and access rules of zero emission zones.
 */
public class ZoneDefinition {
    private static final Logger logger = LoggerFactory.getLogger(ZoneDefinition.class);

    private final String requestId;
    private final Set<Id<Link>> zoneLinks;
    private final Set<String> allowedVehicleTypes;
    private final Network network;

    /**
     * Creates a new Zone Definition instance.
     *
     * @param requestId The simulation request ID
     * @param network The MATSim network containing the zone
     * @param config Configuration for the zero emission zone
     */
    public ZoneDefinition(String requestId, Network network, ZeroEmissionZoneConfigGroup config) {
        if (requestId == null || requestId.trim().isEmpty()) {
            throw new IllegalArgumentException("Request ID cannot be null or empty");
        }
        if (network == null) {
            throw new IllegalArgumentException("Network cannot be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("Configuration cannot be null");
        }

        this.requestId = requestId;
        this.network = network;
        this.zoneLinks = initializeZoneLinks(config);
        this.allowedVehicleTypes = initializeAllowedVehicleTypes(config);
        
        validateZoneConfiguration();
        logZoneConfiguration();
    }

    private Set<Id<Link>> initializeZoneLinks(ZeroEmissionZoneConfigGroup config) {
        String zoneLinkIds = config.getZoneLinkIds();
        if (zoneLinkIds == null || zoneLinkIds.trim().isEmpty()) {
            throw new IllegalArgumentException("Zone link IDs cannot be null or empty");
        }

        Set<Id<Link>> links = Stream.of(zoneLinkIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(linkId -> Id.create(linkId, Link.class))
                .collect(Collectors.toCollection(HashSet::new));

        // Validate that all configured links exist in the network
        links.forEach(linkId -> {
            if (!network.getLinks().containsKey(linkId)) {
                throw new IllegalArgumentException("Link " + linkId + " specified in ZEZ config does not exist in network");
            }
        });

        return Collections.unmodifiableSet(links);
    }

    private Set<String> initializeAllowedVehicleTypes(ZeroEmissionZoneConfigGroup config) {
        String allowedTypes = config.getAllowedVehicleTypes();
        if (allowedTypes == null || allowedTypes.trim().isEmpty()) {
            throw new IllegalArgumentException("Allowed vehicle types cannot be null or empty");
        }

        Set<String> types = Stream.of(allowedTypes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(HashSet::new));

        return Collections.unmodifiableSet(types);
    }

    private void validateZoneConfiguration() {
        if (zoneLinks.isEmpty()) {
            throw new IllegalStateException("Zero Emission Zone must contain at least one link");
        }
        if (allowedVehicleTypes.isEmpty()) {
            throw new IllegalStateException("Zero Emission Zone must allow at least one vehicle type");
        }
    }

    private void logZoneConfiguration() {
        logger.info("Initialized Zero Emission Zone for request {} with {} links", requestId, zoneLinks.size());
        logger.info("Allowed vehicle types for request {}: {}", requestId, String.join(", ", allowedVehicleTypes));
    }

    /**
     * Gets the request ID associated with this zone definition.
     *
     * @return The request ID
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * Gets the set of links that are part of the zero emission zone.
     *
     * @return Unmodifiable set of link IDs in the zone
     */
    public Set<Id<Link>> getZoneLinks() {
        return zoneLinks;
    }

    /**
     * Gets the set of vehicle types allowed in the zero emission zone.
     *
     * @return Unmodifiable set of allowed vehicle type strings
     */
    public Set<String> getAllowedVehicleTypes() {
        return allowedVehicleTypes;
    }

    /**
     * Checks if a link is part of the zero emission zone.
     *
     * @param linkId The ID of the link to check
     * @return true if the link is in the zone, false otherwise
     */
    public boolean containsLink(Id<Link> linkId) {
        return linkId != null && zoneLinks.contains(linkId);
    }

    /**
     * Checks if a vehicle type is allowed in the zero emission zone.
     *
     * @param vehicleType The vehicle type to check
     * @return true if the vehicle type is allowed, false otherwise
     */
    public boolean isVehicleTypeAllowed(String vehicleType) {
        return vehicleType != null && allowedVehicleTypes.contains(vehicleType);
    }

    /**
     * Gets the network this zone is part of.
     *
     * @return The MATSim network
     */
    public Network getNetwork() {
        return network;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ZoneDefinition that = (ZoneDefinition) o;
        return Objects.equals(requestId, that.requestId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(requestId);
    }

    @Override
    public String toString() {
        return String.format("ZoneDefinition[requestId=%s, links=%d, allowedTypes=%d]",
                requestId, zoneLinks.size(), allowedVehicleTypes.size());
    }
}
