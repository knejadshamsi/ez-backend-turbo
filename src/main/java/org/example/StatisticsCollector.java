package org.example;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class StatisticsCollector implements LinkEnterEventHandler, PersonEntersVehicleEventHandler {
    private static final Logger logger = LoggerFactory.getLogger(StatisticsCollector.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ZeroEmissionZone zeroEmissionZone;
    private final Map<String, Map<Id<Person>, List<ZoneEntry>>> categoryEntries = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> zoneEntryStats = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> alternativeRouteStats = new ConcurrentHashMap<>();
    private final Map<Id<Person>, String> personVehicleCategory = new ConcurrentHashMap<>();
    private final AtomicInteger totalReroutedPlans = new AtomicInteger(0);

    private static class ZoneEntry {
        final double timestamp;
        final Id<Link> linkId;
        final boolean isZoneLink;
        final boolean isAlternativeRoute;
        final LocalTime entryTime;

        ZoneEntry(double timestamp, Id<Link> linkId, boolean isZoneLink, boolean isAlternativeRoute, LocalTime entryTime) {
            this.timestamp = timestamp;
            this.linkId = linkId;
            this.isZoneLink = isZoneLink;
            this.isAlternativeRoute = isAlternativeRoute;
            this.entryTime = entryTime;
        }
    }

    public StatisticsCollector(ZeroEmissionZone zeroEmissionZone) {
        this.zeroEmissionZone = zeroEmissionZone;
        initializeStats();
        logger.info("Initialized StatisticsCollector with three-tier vehicle classification");
    }

    private void initializeStats() {
        // Initialize counters for each vehicle category
        for (ZeroEmissionZone.VehicleCategory category : ZeroEmissionZone.VehicleCategory.values()) {
            categoryEntries.put(category.name(), new ConcurrentHashMap<>());
            zoneEntryStats.put(category.name() + "_entries", new AtomicInteger(0));
            alternativeRouteStats.put(category.name() + "_alternative_usage", new AtomicInteger(0));
        }
    }

    @Override
    public void reset(int iteration) {
        categoryEntries.clear();
        zoneEntryStats.clear();
        alternativeRouteStats.clear();
        personVehicleCategory.clear();
        totalReroutedPlans.set(0);
        initializeStats();
    }

    @Override
    public void handleEvent(LinkEnterEvent event) {
        if (event == null) return;
        
        String vehicleId = event.getVehicleId().toString();
        Id<Person> personId = Id.createPersonId(vehicleId.split("_")[0]);
        String category = personVehicleCategory.get(personId);
        
        if (category == null) {
            logger.warn("No vehicle category found for person {}", personId);
            return;
        }

        // Convert event time to LocalTime
        double timeInSeconds = event.getTime();
        int hours = (int) (timeInSeconds / 3600) % 24;
        int minutes = (int) ((timeInSeconds % 3600) / 60);
        LocalTime entryTime = LocalTime.of(hours, minutes);

        boolean isZoneLink = zeroEmissionZone.isZoneLink(event.getLinkId());
        boolean isAlternativeLink = zeroEmissionZone.isAlternativeLink(event.getLinkId());

        // Record entry
        ZoneEntry entry = new ZoneEntry(
            event.getTime(),
            event.getLinkId(),
            isZoneLink,
            isAlternativeLink,
            entryTime
        );

        // Update category-specific statistics
        categoryEntries.get(category).computeIfAbsent(personId, k -> new ArrayList<>()).add(entry);

        if (isZoneLink) {
            zoneEntryStats.get(category + "_entries").incrementAndGet();
            logger.info("{} entered zone link {} at {}", category, event.getLinkId(), entryTime);
        }

        if (isAlternativeLink) {
            alternativeRouteStats.get(category + "_alternative_usage").incrementAndGet();
            logger.info("{} used alternative route {} at {}", category, event.getLinkId(), entryTime);
        }
    }

    @Override
    public void handleEvent(PersonEntersVehicleEvent event) {
        if (event == null) return;
        
        String vehicleId = event.getVehicleId().toString();
        String vehicleType = vehicleId.split("_")[0]; // Assuming format: type_number
        
        // Map vehicle type to category
        String category = null;
        if (vehicleType.startsWith("ev")) {
            category = ZeroEmissionZone.VehicleCategory.ELECTRIC.name();
        } else if (vehicleType.startsWith("lev")) {
            category = ZeroEmissionZone.VehicleCategory.LOW_EMISSION.name();
        } else if (vehicleType.startsWith("hev")) {
            category = ZeroEmissionZone.VehicleCategory.HEAVY_EMISSION.name();
        }

        if (category != null) {
            personVehicleCategory.put(event.getPersonId(), category);
            logger.info("Recorded vehicle category {} for person {}", category, event.getPersonId());
        }
    }

    public void recordReroutingEvent(Id<Person> personId) {
        totalReroutedPlans.incrementAndGet();
        logger.info("Recorded rerouting for person {}", personId);
    }

    public Map<String, Integer> getZoneEntryStatistics() {
        Map<String, Integer> stats = new HashMap<>();
        zoneEntryStats.forEach((key, value) -> stats.put(key, value.get()));
        alternativeRouteStats.forEach((key, value) -> stats.put(key, value.get()));
        return stats;
    }

    public Map<String, List<ZoneEntry>> getCategoryEntries(String category) {
        Map<Id<Person>, List<ZoneEntry>> entries = categoryEntries.get(category);
        if (entries == null) {
            return Collections.emptyMap();
        }
        return entries.entrySet().stream()
            .collect(HashMap::new,
                    (m, e) -> m.put(e.getKey().toString(), e.getValue()),
                    HashMap::putAll);
    }

    public int getTotalReroutedPlans() {
        return totalReroutedPlans.get();
    }

    public Map<String, Object> generateSummaryStatistics() {
        Map<String, Object> summary = new HashMap<>();
        
        // Zone entry statistics by vehicle category
        summary.put("zoneEntries", getZoneEntryStatistics());
        
        // Alternative route usage
        Map<String, Integer> alternativeUsage = new HashMap<>();
        alternativeRouteStats.forEach((key, value) -> alternativeUsage.put(key, value.get()));
        summary.put("alternativeRouteUsage", alternativeUsage);
        
        // Total statistics
        summary.put("totalReroutedPlans", getTotalReroutedPlans());
        summary.put("uniqueVehicles", personVehicleCategory.size());
        
        // Category-specific statistics
        Map<String, Integer> categoryStats = new HashMap<>();
        categoryEntries.forEach((category, entries) -> 
            categoryStats.put(category, entries.size()));
        summary.put("vehiclesByCategory", categoryStats);

        return summary;
    }
}
