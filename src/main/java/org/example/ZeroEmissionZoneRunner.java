package org.example;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.ScenarioUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZeroEmissionZoneRunner {
    private static final Logger logger = LoggerFactory.getLogger(ZeroEmissionZoneRunner.class);

    public static void main(String[] args) {
        logger.info("Starting Zero Emission Zone simulation...");

        if (args.length == 0) {
            throw new RuntimeException("No config file specified. Please provide a config file path as an argument.");
        }

        try {
            // Load MATSim configuration from config.xml
            Config config = ConfigUtils.loadConfig(args[0]);

            // Create and load scenario
            Scenario scenario = ScenarioUtils.loadScenario(config);
            logger.info("Scenario loaded with configuration from {}", args[0]);

            // Initialize statistics collector
            StatisticsCollector statsCollector = new StatisticsCollector();

            // Create a MATSim controller
            Controler controler = new Controler(scenario);

            // Initialize ZeroEmissionZoneScoring and add as event handler
            ZeroEmissionZoneScoring scoringHandler = new ZeroEmissionZoneScoring(scenario, statsCollector);
            controler.getEvents().addHandler(scoringHandler);

            // Set scoring function factory
            controler.setScoringFunctionFactory(scoringHandler);

            logger.info("Zero Emission Zone setup completed.");

            // Run the simulation
            try {
                logger.info("Running the MATSim simulation...");
                controler.run();
                logger.info("Simulation completed successfully.");
            } catch (Exception e) {
                logger.error("Error during simulation execution", e);
                throw new RuntimeException("Simulation failed", e);
            }

            // Generate final statistics report
            try {
                String outputPath = controler.getControlerIO().getOutputPath();
                statsCollector.generateFinalReport(outputPath);
                logger.info("Final statistics report generated at {}", outputPath);
            } catch (Exception e) {
                logger.error("Error generating final report", e);
            }

        } catch (Exception e) {
            logger.error("Fatal error during simulation setup", e);
            throw new RuntimeException("Failed to initialize simulation", e);
        }
    }
}
