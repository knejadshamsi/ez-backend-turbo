package org.example;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Collects and manages statistics for the Zero Emission Zone (ZEZ) simulation.
 * 
 * This class tracks and analyzes various metrics related to:
 * - Vehicle violations of ZEZ restrictions
 * - Rerouting events
 * - Link usage
 * - Hourly vehicle type counts
 * - Compliance and effectiveness of ZEZ policies
 * 
 * Key responsibilities:
 * - Record and aggregate vehicle-related events
 * - Generate comprehensive summary statistics
 * - Provide insights into ZEZ policy performance
 */
public class StatisticsCollector {
    private static final Logger logger = LoggerFactory.getLogger(StatisticsCollector.class);

    // Core simulation components
    private final ZeroEmissionZone zez;

    // Concurrent data structures for thread-safe statistics tracking
    private final Map<Id<Person>, AtomicInteger> violations;
    private final Map<Id<Person>, AtomicInteger> reroutings;
    private final Map<Id<Link>, AtomicInteger> linkUsage;
    private final Map<Id<Person>, List<VehicleEvent>> vehicleEvents;
    private final Map<LocalTime, Map<String, AtomicInteger>> hourlyVehicleTypeCount;

    // Vehicle types tracked in the simulation
    private static final Set<String> VEHICLE_TYPES = Set.of("ev_car", "lev_car", "hev_car");

    /**
     * Represents a specific event related to a vehicle in the ZEZ simulation.
     */
    public static class VehicleEvent {
        private final LocalTime time;
        private final Id<Link> linkId;
        private final String eventType; // "violation" or "rerouting"

        /**
         * Constructs a new vehicle event.
         * 
         * @param time Time of the event
         * @param linkId Link where the event occurred
         * @param eventType Type of event (violation or rerouting)
         */
        public VehicleEvent(LocalTime time, Id<Link> linkId, String eventType) {
            this.time = time;
            this.linkId = linkId;
            this.eventType = eventType;
        }

        // Getter methods for event details
        public LocalTime getTime() { return time; }
        public Id<Link> getLinkId() { return linkId; }
        public String getEventType() { return eventType; }
    }

    /**
     * Constructs a StatisticsCollector for the given Zero Emission Zone.
     * 
     * @param zez Zero Emission Zone configuration
     */
    public StatisticsCollector(ZeroEmissionZone zez) {
        this.zez = zez;
        this.violations = new ConcurrentHashMap<>();
        this.reroutings = new ConcurrentHashMap<>();
        this.linkUsage = new ConcurrentHashMap<>();
        this.vehicleEvents = new ConcurrentHashMap<>();
        this.hourlyVehicleTypeCount = new ConcurrentHashMap<>();
        
        // Initialize hourly counts for each vehicle type
        initializeHourlyVehicleTypeCounts();
    }

    /**
     * Initializes hourly vehicle type counts for comprehensive tracking.
     */
    private void initializeHourlyVehicleTypeCounts() {
        for (int hour = 0; hour < 24; hour++) {
            LocalTime timeKey = LocalTime.of(hour, 0);
            Map<String, AtomicInteger> typeCount = new HashMap<>();
            VEHICLE_TYPES.forEach(type -> typeCount.put(type, new AtomicInteger(0)));
            hourlyVehicleTypeCount.put(timeKey, typeCount);
        }
    }

    /**
     * Records a ZEZ violation for a specific vehicle.
     * 
     * @param personId Identifier of the person/vehicle
     * @param linkId Link where the violation occurred
     */
    public void recordViolation(Id<Person> personId, Id<Link> linkId) {
        LocalTime currentTime = getCurrentTime();
        
        // Increment violation counts and track events
        violations.computeIfAbsent(personId, k -> new AtomicInteger(0)).incrementAndGet();
        linkUsage.computeIfAbsent(linkId, k -> new AtomicInteger(0)).incrementAndGet();
        vehicleEvents.computeIfAbsent(personId, k -> new ArrayList<>())
            .add(new VehicleEvent(currentTime, linkId, "violation"));
        
        // Update hourly vehicle type count
        String vehicleType = getVehicleTypeFromId(personId.toString());
        LocalTime hourKey = LocalTime.of(currentTime.getHour(), 0);
        hourlyVehicleTypeCount.get(hourKey).get(vehicleType).incrementAndGet();
        
        logger.warn("Vehicle {} violated ZEZ restrictions at link {} at time {}", 
            personId, linkId, currentTime);
    }

    /**
     * Records a rerouting event for a specific vehicle.
     * 
     * @param personId Identifier of the person/vehicle
     * @param linkId Link where rerouting occurred
     */
    public void recordRerouting(Id<Person> personId, Id<Link> linkId) {
        LocalTime currentTime = getCurrentTime();
        
        // Increment rerouting counts and track events
        reroutings.computeIfAbsent(personId, k -> new AtomicInteger(0)).incrementAndGet();
        vehicleEvents.computeIfAbsent(personId, k -> new ArrayList<>())
            .add(new VehicleEvent(currentTime, linkId, "rerouting"));
        
        // Update hourly vehicle type count
        String vehicleType = getVehicleTypeFromId(personId.toString());
        LocalTime hourKey = LocalTime.of(currentTime.getHour(), 0);
        hourlyVehicleTypeCount.get(hourKey).get(vehicleType).incrementAndGet();
        
        logger.info("Vehicle {} rerouted near ZEZ at link {} at time {}", 
            personId, linkId, currentTime);
    }

    /**
     * Generates a comprehensive summary of ZEZ simulation statistics.
     * 
     * @return Map of various statistical metrics
     */
    public Map<String, Object> generateSummaryStatistics() {
        Map<String, Object> summary = new HashMap<>();
        
        // Basic event counts
        summary.put("totalViolations", getTotalViolations());
        summary.put("totalReroutings", getTotalReroutings());
        
        // Vehicle type statistics
        summary.put("violationsByVehicleType", getViolationsByVehicleType());
        summary.put("reroutingsByVehicleType", getReroutingsByVehicleType());
        
        // Link usage statistics
        summary.put("linkUsage", getLinkUsageStats());
        
        // Time-based statistics
        summary.put("hourlyVehicleCounts", getHourlyVehicleCounts());
        summary.put("peakHourViolations", getPeakHourViolations());
        
        // ZEZ effectiveness metrics
        summary.put("zezComplianceRate", calculateComplianceRate());
        summary.put("reroutingEffectiveness", calculateReroutingEffectiveness());
        
        return summary;
    }

    /**
     * Calculates the total number of ZEZ violations.
     * 
     * @return Total number of violations
     */
    private int getTotalViolations() {
        return violations.values().stream()
            .mapToInt(AtomicInteger::get)
            .sum();
    }

    /**
     * Calculates the total number of rerouting events.
     * 
     * @return Total number of reroutings
     */
    private int getTotalReroutings() {
        return reroutings.values().stream()
            .mapToInt(AtomicInteger::get)
            .sum();
    }

    /**
     * Generates a breakdown of violations by vehicle type.
     * 
     * @return Map of violations for each vehicle type
     */
    private Map<String, Integer> getViolationsByVehicleType() {
        Map<String, Integer> stats = new HashMap<>();
        for (String vehicleType : VEHICLE_TYPES) {
            stats.put(vehicleType, 0);
        }
        
        violations.forEach((personId, count) -> {
            String vehicleType = getVehicleTypeFromId(personId.toString());
            stats.merge(vehicleType, count.get(), Integer::sum);
        });
        
        return stats;
    }

    /**
     * Generates a breakdown of reroutings by vehicle type.
     * 
     * @return Map of reroutings for each vehicle type
     */
    private Map<String, Integer> getReroutingsByVehicleType() {
        Map<String, Integer> stats = new HashMap<>();
        for (String vehicleType : VEHICLE_TYPES) {
            stats.put(vehicleType, 0);
        }
        
        reroutings.forEach((personId, count) -> {
            String vehicleType = getVehicleTypeFromId(personId.toString());
            stats.merge(vehicleType, count.get(), Integer::sum);
        });
        
        return stats;
    }

    /**
     * Retrieves link usage statistics.
     * 
     * @return Map of link IDs and their usage counts
     */
    private Map<String, Integer> getLinkUsageStats() {
        return linkUsage.entrySet().stream()
            .collect(Collectors.toMap(
                e -> e.getKey().toString(),
                e -> e.getValue().get()
            ));
    }

    /**
     * Generates hourly vehicle type counts.
     * 
     * @return Nested map of hourly vehicle type counts
     */
    private Map<Integer, Map<String, Integer>> getHourlyVehicleCounts() {
        Map<Integer, Map<String, Integer>> hourlyStats = new HashMap<>();
        
        hourlyVehicleTypeCount.forEach((time, typeCount) -> {
            Map<String, Integer> hourCount = new HashMap<>();
            typeCount.forEach((type, count) -> 
                hourCount.put(type, count.get()));
            hourlyStats.put(time.getHour(), hourCount);
        });
        
        return hourlyStats;
    }

    /**
     * Calculates violations during peak hours for each vehicle type.
     * 
     * @return Map of peak hour violations by vehicle type
     */
    private Map<String, Integer> getPeakHourViolations() {
        Map<String, Integer> peakHourStats = new HashMap<>();
        
        vehicleEvents.forEach((personId, events) -> {
            events.stream()
                .filter(e -> e.eventType.equals("violation"))
                .filter(e -> isPeakHour(e.time))
                .forEach(e -> {
                    String vehicleType = getVehicleTypeFromId(personId.toString());
                    peakHourStats.merge(vehicleType, 1, Integer::sum);
                });
        });
        
        return peakHourStats;
    }

    /**
     * Calculates the ZEZ compliance rate.
     * 
     * @return Percentage of events that were successfully rerouted
     */
    private double calculateComplianceRate() {
        int totalEvents = getTotalViolations() + getTotalReroutings();
        if (totalEvents == 0) return 100.0;
        
        return (double) getTotalReroutings() / totalEvents * 100.0;
    }

    /**
     * Calculates the effectiveness of rerouting for hybrid vehicles.
     * 
     * @return Percentage of hybrid vehicle events successfully rerouted
     */
    private double calculateReroutingEffectiveness() {
        Map<String, Integer> reroutingsByType = getReroutingsByVehicleType();
        int hevReroutings = reroutingsByType.getOrDefault("hev_car", 0);
        int totalHevEvents = hevReroutings + 
            getViolationsByVehicleType().getOrDefault("hev_car", 0);
        
        if (totalHevEvents == 0) return 100.0;
        
        return (double) hevReroutings / totalHevEvents * 100.0;
    }

    /**
     * Determines the vehicle type based on the person's ID.
     * 
     * @param personId ID of the person/vehicle
     * @return Vehicle type string
     */
    private String getVehicleTypeFromId(String personId) {
        if (personId.contains("ev")) {
            return "ev_car";
        } else if (personId.contains("lev")) {
            return "lev_car";
        } else if (personId.contains("hev")) {
            return "hev_car";
        }
        return "unknown";
    }

    /**
     * Gets the current simulation time.
     * 
     * @return Current local time (to be replaced with actual simulation time)
     */
    private LocalTime getCurrentTime() {
        // This should be replaced with actual simulation time when available
        return LocalTime.now();
    }

    /**
     * Checks if a given time is during peak hours.
     * 
     * @param time Time to check
     * @return true if time is during peak hours, false otherwise
     */
    private boolean isPeakHour(LocalTime time) {
        int hour = time.getHour();
        return (hour >= 7 && hour <= 9) || (hour >= 16 && hour <= 19);
    }

    // Getter methods for testing and verification
    public Map<Id<Person>, AtomicInteger> getViolations() {
        return Collections.unmodifiableMap(violations);
    }

    public Map<Id<Person>, AtomicInteger> getReroutings() {
        return Collections.unmodifiableMap(reroutings);
    }

    public Map<Id<Link>, AtomicInteger> getLinkUsage() {
        return Collections.unmodifiableMap(linkUsage);
    }

    public Map<Id<Person>, List<VehicleEvent>> getVehicleEvents() {
        return Collections.unmodifiableMap(vehicleEvents);
    }

    public Map<LocalTime, Map<String, AtomicInteger>> getHourlyVehicleTypeCount() {
        return Collections.unmodifiableMap(hourlyVehicleTypeCount);
    }
}
