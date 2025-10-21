package org.example;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

@Singleton
public class StatisticsCollector {

    private static final Logger logger = LoggerFactory.getLogger(StatisticsCollector.class);
    private final Map<String, Integer> linkEntryStats = new HashMap<>();
    private final Map<String, Integer> vehicleEntryStats = new HashMap<>();

    @Inject
    public StatisticsCollector() {
        logger.info("Initializing StatisticsCollector");
    }

    /**
     * Records when a vehicle enters a zero-emission zone link.
     */
    public void recordEntry(LinkEnterEvent event, boolean isZeroEmission) {
        String entryType = isZeroEmission ? "ZeroEmission" : "NonZeroEmission";
        linkEntryStats.merge(entryType, 1, Integer::sum);
        logger.info("Recorded {} entry for vehicle {} on link {}", 
                   entryType, event.getVehicleId(), event.getLinkId());
    }

    /**
     * Tracks when a person enters a vehicle, recording the agent ID and vehicle ID.
     */
    public void recordPersonVehicleEntry(PersonEntersVehicleEvent event) {
        vehicleEntryStats.merge(event.getVehicleId().toString(), 1, Integer::sum);
        logger.info("Person {} entered vehicle {}", 
                   event.getPersonId(), event.getVehicleId());
    }

    /**
     * Saves iteration statistics.
     */
    public void saveIterationStats(int iteration, Map<?, ?> violations) {
        logger.info("Saving statistics for iteration {}", iteration);
        // Implement saving logic if necessary
    }

    /**
     * Generates a final report after the simulation completes.
     */
    public void generateFinalReport(String outputPath) {
        logger.info("Generating final report at {}", outputPath);
        // Implement report generation logic
    }
}