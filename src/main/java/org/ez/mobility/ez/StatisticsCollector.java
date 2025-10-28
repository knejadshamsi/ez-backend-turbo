package org.ez.mobility.ez;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gathers and processes statistics related to zero emission zones.
 * Tracks various metrics about vehicle movements, charges, and policy impacts.
 */
@Component
public class StatisticsCollector {
    private static final Logger logger = LoggerFactory.getLogger(StatisticsCollector.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @PersistenceContext
    private EntityManager entityManager;

    // Request-specific statistics storage
    private final Map<String, RequestStatistics> requestStats = new ConcurrentHashMap<>();

    private static class RequestStatistics {
        final Map<String, Map<String, AtomicLong>> vehicleEntriesByMode = new ConcurrentHashMap<>();
        final Map<String, Map<String, Double>> chargesByMode = new ConcurrentHashMap<>();
        final Map<String, AtomicLong> lengthRestrictionViolations = new ConcurrentHashMap<>();
        final Map<String, AtomicLong> operatingHourViolations = new ConcurrentHashMap<>();
        final Map<String, AtomicLong> bannedVehicleAttempts = new ConcurrentHashMap<>();
        final Map<String, Map<Integer, Double>> hourlyChargeDistribution = new ConcurrentHashMap<>();
    }

    private RequestStatistics getOrCreateStats(String requestId) {
        return requestStats.computeIfAbsent(requestId, k -> new RequestStatistics());
    }

    /**
     * Records a vehicle entry into the zero emission zone.
     */
    public void recordVehicleEntry(String requestId, String vehicleId, String policyMode, double charge, LocalDateTime entryTime) {
        RequestStatistics stats = getOrCreateStats(requestId);

        // Record entry count by mode
        stats.vehicleEntriesByMode
            .computeIfAbsent(policyMode, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(vehicleId, k -> new AtomicLong(0))
            .incrementAndGet();

        // Record charges by mode
        stats.chargesByMode
            .computeIfAbsent(policyMode, k -> new ConcurrentHashMap<>())
            .merge(vehicleId, charge, Double::sum);

        // For hourly charges, track distribution
        if ("hourly".equals(policyMode)) {
            stats.hourlyChargeDistribution
                .computeIfAbsent(vehicleId, k -> new ConcurrentHashMap<>())
                .merge(entryTime.getHour(), charge, Double::sum);
        }
        
        logger.debug("Vehicle entry recorded for request {}: id={}, mode={}, charge={}", 
                    requestId, vehicleId, policyMode, charge);
    }

    /**
     * Records a length restriction violation.
     */
    public void recordLengthRestrictionViolation(String requestId, String vehicleId, double actualLength, int maxAllowedLength) {
        RequestStatistics stats = getOrCreateStats(requestId);
        stats.lengthRestrictionViolations.computeIfAbsent(vehicleId, k -> new AtomicLong(0)).incrementAndGet();
        
        logger.warn("Length restriction violation for request {}: id={}, actual length={}, max allowed={}", 
                    requestId, vehicleId, actualLength, maxAllowedLength);
    }

    /**
     * Records an operating hour violation.
     */
    public void recordOperatingHourViolation(String requestId, String vehicleId, LocalDateTime entryTime) {
        RequestStatistics stats = getOrCreateStats(requestId);
        stats.operatingHourViolations.computeIfAbsent(vehicleId, k -> new AtomicLong(0)).incrementAndGet();
        
        logger.warn("Operating hour violation for request {}: id={}, entry time={}", 
                    requestId, vehicleId, entryTime);
    }

    /**
     * Records an attempt by a banned vehicle to enter the zone.
     */
    public void recordBannedVehicleAttempt(String requestId, String vehicleId, LocalDateTime attemptTime) {
        RequestStatistics stats = getOrCreateStats(requestId);
        stats.bannedVehicleAttempts.computeIfAbsent(vehicleId, k -> new AtomicLong(0)).incrementAndGet();
        
        logger.warn("Banned vehicle attempted entry for request {}: id={}, time={}", 
                    requestId, vehicleId, attemptTime);
    }

    /**
     * Generates a comprehensive report of zone statistics for a specific request.
     */
    public Map<String, Object> generateReport(String requestId) {
        RequestStatistics stats = requestStats.get(requestId);
        if (stats == null) {
            logger.warn("No statistics found for request: {}", requestId);
            return new HashMap<>();
        }

        Map<String, Object> report = new HashMap<>();
        
        // Entries and charges by policy mode
        report.put("entriesByMode", stats.vehicleEntriesByMode);
        report.put("chargesByMode", stats.chargesByMode);
        
        // Violations and attempts
        report.put("lengthRestrictionViolations", stats.lengthRestrictionViolations);
        report.put("operatingHourViolations", stats.operatingHourViolations);
        report.put("bannedVehicleAttempts", stats.bannedVehicleAttempts);
        
        // Hourly statistics
        report.put("hourlyChargeDistribution", stats.hourlyChargeDistribution);
        
        // Summary statistics
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalEntries", calculateTotalEntries(stats));
        summary.put("totalCharges", calculateTotalCharges(stats));
        summary.put("totalViolations", calculateTotalViolations(stats));
        report.put("summary", summary);
        
        logger.info("Generated comprehensive zone statistics report for request: {}", requestId);
        return report;
    }

    private long calculateTotalEntries(RequestStatistics stats) {
        return stats.vehicleEntriesByMode.values().stream()
            .flatMap(m -> m.values().stream())
            .mapToLong(AtomicLong::get)
            .sum();
    }

    private double calculateTotalCharges(RequestStatistics stats) {
        return stats.chargesByMode.values().stream()
            .flatMap(m -> m.values().stream())
            .mapToDouble(Double::doubleValue)
            .sum();
    }

    private long calculateTotalViolations(RequestStatistics stats) {
        return stats.lengthRestrictionViolations.values().stream().mapToLong(AtomicLong::get).sum()
             + stats.operatingHourViolations.values().stream().mapToLong(AtomicLong::get).sum()
             + stats.bannedVehicleAttempts.values().stream().mapToLong(AtomicLong::get).sum();
    }

    /**
     * Stores the statistics for a request in the database and cleans up memory.
     */
    @Transactional
    public void persistAndCleanup(String requestId) {
        try {
            RequestStatistics stats = requestStats.get(requestId);
            if (stats != null) {
                // Convert statistics to JSON
                String statisticsJson = objectMapper.writeValueAsString(generateReport(requestId));
                
                // Store in database using native query for better performance
                entityManager.createNativeQuery(
                    "INSERT INTO simulation_statistics (request_id, statistics_data) VALUES (?1, ?2::jsonb)")
                    .setParameter(1, requestId)
                    .setParameter(2, statisticsJson)
                    .executeUpdate();
                
                // Clean up memory
                requestStats.remove(requestId);
                logger.info("Statistics persisted and cleaned up for request: {}", requestId);
            }
        } catch (Exception e) {
            logger.error("Failed to persist statistics for request: " + requestId, e);
        }
    }
}
