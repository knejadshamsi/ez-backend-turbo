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

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class StatisticsCollector implements LinkEnterEventHandler, PersonEntersVehicleEventHandler {
    private static final Logger logger = LoggerFactory.getLogger(StatisticsCollector.class);
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final ZeroEmissionZone zeroEmissionZone;
    private final Map<Integer, Map<Id<Person>, Integer>> iterationViolations = new ConcurrentHashMap<>();
    private final Map<Id<Person>, List<ZeroEmissionEntry>> individualEntries = new ConcurrentHashMap<>();
    private final Map<String, Integer> zoneEntryStatistics = new ConcurrentHashMap<>();
    private final Map<String, Integer> linkEntryStats = new ConcurrentHashMap<>();
    private final Map<String, Integer> vehicleEntryStats = new ConcurrentHashMap<>();
    private final Map<Id<Person>, Integer> reroutingStats = new ConcurrentHashMap<>();
    private final AtomicInteger totalReroutedPlans = new AtomicInteger(0);

    private static class ZeroEmissionEntry {
        final double timestamp;
        final Id<Link> linkId;
        final boolean isZeroEmission;

        ZeroEmissionEntry(double timestamp, Id<Link> linkId, boolean isZeroEmission) {
            this.timestamp = timestamp;
            this.linkId = linkId;
            this.isZeroEmission = isZeroEmission;
        }
    }

    public StatisticsCollector(ZeroEmissionZone zeroEmissionZone) {
        this.zeroEmissionZone = zeroEmissionZone;
        logger.info("Initializing StatisticsCollector");
    }

    @Override
    public void reset(int iteration) {
        iterationViolations.clear();
        individualEntries.clear();
        zoneEntryStatistics.clear();
        linkEntryStats.clear();
        vehicleEntryStats.clear();
        reroutingStats.clear();
        totalReroutedPlans.set(0);
        zeroEmissionZone.resetViolations();
    }

    @Override
    public void handleEvent(LinkEnterEvent event) {
        if (event == null) return;
        recordLinkEntry(event);
    }

    @Override
    public void handleEvent(PersonEntersVehicleEvent event) {
        if (event == null) return;
        recordPersonVehicleEntry(event);
    }

    public void recordReroutingEvent(Id<Person> personId) {
        reroutingStats.merge(personId, 1, Integer::sum);
        totalReroutedPlans.incrementAndGet();
        logger.info("Recorded rerouting for person {}", personId);
    }

    private void recordLinkEntry(LinkEnterEvent event) {
        boolean isZeroEmission = !zeroEmissionZone.isInZeroEmissionZone(event.getLinkId());
        recordEntry(event, isZeroEmission);
    }

    private void recordEntry(LinkEnterEvent event, boolean isZeroEmission) {
        String entryType = isZeroEmission ? "ZeroEmission" : "NonZeroEmission";
        linkEntryStats.merge(entryType, 1, Integer::sum);

        Id<Person> personId = extractPersonId(event.getVehicleId().toString());

        ZeroEmissionEntry entry = new ZeroEmissionEntry(
            event.getTime(), 
            event.getLinkId(), 
            isZeroEmission
        );

        individualEntries.compute(personId, (key, existingEntries) -> {
            if (existingEntries == null) {
                existingEntries = new ArrayList<>();
            }
            existingEntries.add(entry);
            return existingEntries;
        });

        zoneEntryStatistics.merge(
            isZeroEmission ? "zero_emission_entries" : "non_zero_emission_entries", 
            1, 
            Integer::sum
        );

        logger.info("Recorded {} entry for vehicle {} on link {}", 
                   entryType, event.getVehicleId(), event.getLinkId());
    }

    private void recordPersonVehicleEntry(PersonEntersVehicleEvent event) {
        vehicleEntryStats.merge(event.getVehicleId().toString(), 1, Integer::sum);
        logger.info("Person {} entered vehicle {}", 
                   event.getPersonId(), event.getVehicleId());
    }

    private Id<Person> extractPersonId(String vehicleId) {
        String[] parts = vehicleId.split("_");
        return Id.createPersonId(parts[0]);
    }

    public Map<String, Integer> getZoneEntryStatistics() {
        return new HashMap<>(zoneEntryStatistics);
    }

    public Map<String, Integer> getLinkEntryStats() {
        return new HashMap<>(linkEntryStats);
    }

    public Map<String, Integer> getVehicleEntryStats() {
        return new HashMap<>(vehicleEntryStats);
    }

    public Map<Id<Person>, List<ZeroEmissionEntry>> getIndividualEntries() {
        return new HashMap<>(individualEntries);
    }

    public Map<Id<Person>, Integer> getReroutingStats() {
        return new HashMap<>(reroutingStats);
    }

    public int getTotalReroutedPlans() {
        return totalReroutedPlans.get();
    }

    public double getAverageReroutesPerPerson() {
        if (reroutingStats.isEmpty()) return 0.0;
        return reroutingStats.values().stream()
            .mapToInt(Integer::intValue)
            .average()
            .orElse(0.0);
    }

    public Map<String, Object> generateSummaryStatistics() {
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalReroutedPlans", getTotalReroutedPlans());
        summary.put("averageReroutesPerPerson", getAverageReroutesPerPerson());
        summary.put("zoneEntryStats", getZoneEntryStatistics());
        summary.put("uniqueVehicles", vehicleEntryStats.size());
        summary.put("totalZoneEntries", zoneEntryStatistics.values().stream()
            .mapToInt(Integer::intValue)
            .sum());
        return summary;
    }
}
