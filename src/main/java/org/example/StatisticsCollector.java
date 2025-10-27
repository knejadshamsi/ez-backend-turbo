package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collects and processes statistics for the Zero Emission Zone simulation.
 * Tracks vehicle movements, emissions, and zone violations.
 */
public class StatisticsCollector {
    private static final Logger logger = LoggerFactory.getLogger(StatisticsCollector.class);
    private final ZeroEmissionZone zeroEmissionZone;
    private final Map<Id<Link>, Double> linkTrafficCounts;
    private final Map<String, Double> vehicleEmissions;
    private final Map<String, Integer> zoneViolations;
    private final ObjectMapper objectMapper;

    public StatisticsCollector(ZeroEmissionZone zeroEmissionZone) {
        this.zeroEmissionZone = zeroEmissionZone;
        this.linkTrafficCounts = new ConcurrentHashMap<>();
        this.vehicleEmissions = new ConcurrentHashMap<>();
        this.zoneViolations = new ConcurrentHashMap<>();
        this.objectMapper = new ObjectMapper();
    }

    public void recordLinkTraffic(Id<Link> linkId) {
        linkTrafficCounts.merge(linkId, 1.0, Double::sum);
    }

    public void recordEmission(String vehicleId, double emission) {
        vehicleEmissions.merge(vehicleId, emission, Double::sum);
    }

    public void recordZoneViolation(String vehicleId) {
        zoneViolations.merge(vehicleId, 1, Integer::sum);
    }

    public Map<String, Object> generateSummaryStatistics() {
        Map<String, Object> summary = new HashMap<>();
        
        // Traffic statistics
        Map<String, Double> trafficStats = new HashMap<>();
        linkTrafficCounts.forEach((linkId, count) -> 
            trafficStats.put(linkId.toString(), count));
        summary.put("trafficFlow", trafficStats);
        
        // Emission statistics
        Map<String, Object> emissionStats = new HashMap<>();
        emissionStats.put("totalEmissions", vehicleEmissions.values().stream().mapToDouble(Double::doubleValue).sum());
        emissionStats.put("vehicleEmissions", vehicleEmissions);
        summary.put("emissions", emissionStats);
        
        // Zone violation statistics
        Map<String, Object> violationStats = new HashMap<>();
        violationStats.put("totalViolations", zoneViolations.values().stream().mapToInt(Integer::intValue).sum());
        violationStats.put("vehicleViolations", zoneViolations);
        summary.put("violations", violationStats);

        // Zone statistics
        Map<String, Object> zoneStats = new HashMap<>();
        zoneStats.put("zoneLinks", zeroEmissionZone.getZoneLinks().size());
        summary.put("zoneStatistics", zoneStats);

        // Add timestamp
        summary.put("timestamp", LocalDateTime.now().toString());

        // Write results to file
        try {
            File outputDir = new File("output/zero-emission-test");
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            objectMapper.writeValue(
                new File(outputDir, "output_stats.json"),
                summary
            );
        } catch (IOException e) {
            logger.error("Failed to write statistics to file", e);
        }

        return summary;
    }

    public Map<Id<Link>, Double> getLinkTrafficCounts() {
        return Collections.unmodifiableMap(linkTrafficCounts);
    }

    public Map<String, Double> getVehicleEmissions() {
        return Collections.unmodifiableMap(vehicleEmissions);
    }

    public Map<String, Integer> getZoneViolations() {
        return Collections.unmodifiableMap(zoneViolations);
    }
}
