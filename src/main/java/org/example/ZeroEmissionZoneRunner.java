package org.example;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.ScoringFunctionFactory;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.scoring.functions.CharyparNagelScoringFunctionFactory;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.listener.StartupListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZeroEmissionZoneRunner {
    private static final Logger logger = LoggerFactory.getLogger(ZeroEmissionZoneRunner.class);
    private static final double SCORE_THRESHOLD = -100.0;

    private final Network network;
    private final Scenario scenario;
    private final ZeroEmissionZone zeroEmissionZone;
    private final ReroutingStrategy reroutingStrategy;
    private final StatisticsCollector statisticsCollector;
    private final Config config;
    private final ZeroEmissionZoneConfigGroup zezConfig;

    public ZeroEmissionZoneRunner(String configFilePath) {
        // Load configuration and register custom config group
        this.config = ConfigUtils.loadConfig(configFilePath);
        this.zezConfig = new ZeroEmissionZoneConfigGroup();
        this.config.addModule(zezConfig);

        // Load scenario
        this.scenario = ScenarioUtils.loadScenario(config);
        this.network = scenario.getNetwork();
        
        // Initialize components with config
        this.zeroEmissionZone = new ZeroEmissionZone(network, zezConfig);
        this.reroutingStrategy = new ReroutingStrategy(network, SCORE_THRESHOLD);
        this.statisticsCollector = new StatisticsCollector(zeroEmissionZone);
        
        validateConfiguration();
    }

    private void validateConfiguration() {
        if (network.getNodes().isEmpty() || network.getLinks().isEmpty()) {
            throw new IllegalStateException("Network is empty or invalid");
        }

        if (zeroEmissionZone.getZeroEmissionZoneLinkIds().isEmpty()) {
            throw new IllegalStateException("No zero emission zone links found in the network");
        }
    }

    public void runSimulation() {
        try {
            logger.info("Starting Zero Emission Zone Simulation");
            logger.info("Network configuration: {} nodes, {} links", 
                network.getNodes().size(), 
                network.getLinks().size());
            logger.info("Zero emission zone contains {} links", 
                zeroEmissionZone.getZeroEmissionZoneLinkIds().size());

            // Create and configure the controller
            Controler controler = new Controler(scenario);
            
            // Configure output directory
            controler.getConfig().controler().setOverwriteFileSetting(
                OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);

            // Set up scoring function factory without dependency injection
            controler.setScoringFunctionFactory(new SimpleScoringFunctionFactory(
                scenario, zeroEmissionZone));

            // Add startup listener to initialize components
            controler.addControlerListener((StartupListener) event -> {
                event.getServices().getEvents().addHandler(statisticsCollector);
            });

            // Run the simulation
            controler.run();

            // Log final statistics
            logFinalStatistics();

        } catch (Exception e) {
            logger.error("Simulation failed", e);
            throw new RuntimeException("Simulation execution failed", e);
        }
    }

    private void logFinalStatistics() {
        logger.info("Simulation completed successfully");
        logger.info("Total rerouted plans: {}", reroutingStrategy.getReroutedPlansCount());
        
        var zoneStats = statisticsCollector.getZoneEntryStatistics();
        logger.info("Zero emission zone entries: {}", 
            zoneStats.getOrDefault("zero_emission_entries", 0));
        logger.info("Non-zero emission zone entries: {}", 
            zoneStats.getOrDefault("non_zero_emission_entries", 0));
    }

    private static class SimpleScoringFunctionFactory implements ScoringFunctionFactory {
        private final CharyparNagelScoringFunctionFactory defaultFactory;
        private final ZeroEmissionZone zeroEmissionZone;

        public SimpleScoringFunctionFactory(Scenario scenario, ZeroEmissionZone zez) {
            this.defaultFactory = new CharyparNagelScoringFunctionFactory(scenario);
            this.zeroEmissionZone = zez;
        }

        @Override
        public ScoringFunction createNewScoringFunction(Person person) {
            return new SimpleScoringFunction(
                defaultFactory.createNewScoringFunction(person),
                zeroEmissionZone
            );
        }
    }

    private static class SimpleScoringFunction implements ScoringFunction {
        private final ScoringFunction delegate;
        private final ZeroEmissionZone zeroEmissionZone;
        private double additionalScore = 0.0;

        public SimpleScoringFunction(ScoringFunction delegate, ZeroEmissionZone zez) {
            this.delegate = delegate;
            this.zeroEmissionZone = zez;
        }

        @Override
        public void handleActivity(org.matsim.api.core.v01.population.Activity activity) {
            delegate.handleActivity(activity);
        }

        @Override
        public void handleLeg(org.matsim.api.core.v01.population.Leg leg) {
            delegate.handleLeg(leg);
            additionalScore += zeroEmissionZone.calculatePenalty(leg);
        }

        @Override
        public void agentStuck(double time) {
            delegate.agentStuck(time);
        }

        @Override
        public void addMoney(double amount) {
            delegate.addMoney(amount);
        }

        @Override
        public void addScore(double amount) {
            additionalScore += amount;
        }

        @Override
        public void handleEvent(Event event) {
            delegate.handleEvent(event);
        }

        @Override
        public void finish() {
            delegate.finish();
        }

        @Override
        public double getScore() {
            return delegate.getScore() + additionalScore;
        }
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            throw new IllegalArgumentException("Config file path must be provided as argument");
        }
        
        String configFilePath = args[0];
        try {
            ZeroEmissionZoneRunner runner = new ZeroEmissionZoneRunner(configFilePath);
            runner.runSimulation();
        } catch (Exception e) {
            logger.error("Failed to run simulation", e);
            System.exit(1);
        }
    }

    // Getters for testing and monitoring
    public Network getNetwork() {
        return network;
    }

    public ZeroEmissionZone getZeroEmissionZone() {
        return zeroEmissionZone;
    }

    public ReroutingStrategy getReroutingStrategy() {
        return reroutingStrategy;
    }

    public StatisticsCollector getStatisticsCollector() {
        return statisticsCollector;
    }

    public ZeroEmissionZoneConfigGroup getZeroEmissionZoneConfig() {
        return zezConfig;
    }
}
