package org.ez.mobility.core.service;

import org.ez.mobility.config.SimulationProperties;
import org.ez.mobility.core.SimulationStatusRepository;
import org.ez.mobility.output.OutputEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.stream.Stream;

@Service
public class CleanupService {
    private static final Logger logger = LoggerFactory.getLogger(CleanupService.class);

    private final SimulationProperties properties;
    private final SimulationStatusRepository statusRepository;
    private final OutputEventRepository eventRepository;

    public CleanupService(
            SimulationProperties properties,
            SimulationStatusRepository statusRepository,
            OutputEventRepository eventRepository) {
        this.properties = properties;
        this.statusRepository = statusRepository;
        this.eventRepository = eventRepository;
    }

    @Scheduled(cron = "0 0 * * * *") // Run every hour
    @Transactional
    public void cleanupOldSimulations() {
        if (!properties.getCleanup().isEnabled()) {
            logger.debug("Cleanup is disabled");
            return;
        }

        LocalDateTime cutoffTime = LocalDateTime.now()
            .minusHours(properties.getCleanup().getAgeHours());

        try {
            cleanupSimulationFiles(cutoffTime);
            cleanupDatabaseRecords(cutoffTime);
        } catch (Exception e) {
            logger.error("Error during cleanup", e);
        }
    }

    private void cleanupSimulationFiles(LocalDateTime cutoffTime) {
        Path simulationsDir = Paths.get("simulations");
        if (!Files.exists(simulationsDir)) {
            return;
        }

        try (Stream<Path> paths = Files.list(simulationsDir)) {
            paths.filter(Files::isDirectory)
                .forEach(dir -> {
                    try {
                        if (isDirectoryOld(dir, cutoffTime)) {
                            deleteDirectory(dir);
                            logger.info("Deleted old simulation directory: {}", dir);
                        }
                    } catch (IOException e) {
                        logger.error("Error processing directory: " + dir, e);
                    }
                });
        } catch (IOException e) {
            logger.error("Error listing simulation directories", e);
        }
    }

    private boolean isDirectoryOld(Path dir, LocalDateTime cutoffTime) {
        try {
            return Files.getLastModifiedTime(dir).toInstant()
                .isBefore(cutoffTime.toInstant(java.time.ZoneOffset.UTC));
        } catch (IOException e) {
            logger.error("Error checking directory age: " + dir, e);
            return false;
        }
    }

    private void deleteDirectory(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted((a, b) -> b.compareTo(a)) // Reverse order to delete files before directories
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        logger.error("Error deleting path: " + path, e);
                    }
                });
        }
    }

    @Transactional
    protected void cleanupDatabaseRecords(LocalDateTime cutoffTime) {
        try {
            // Delete old events first to maintain referential integrity
            int deletedEvents = eventRepository.deleteByRequestIdIn(
                statusRepository.findOldRequestIds(cutoffTime));
            logger.info("Deleted {} old event records", deletedEvents);

            // Delete old status records
            int deletedStatuses = statusRepository.deleteByCreatedBefore(cutoffTime);
            logger.info("Deleted {} old status records", deletedStatuses);

        } catch (Exception e) {
            logger.error("Error cleaning up database records", e);
            throw e;
        }
    }
}
