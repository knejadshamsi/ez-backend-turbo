package org.example;

import org.matsim.api.core.v01.Id;
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
import org.matsim.api.core.v01.population.Leg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class ZeroEmissionZoneRunner {
    private static final Logger logger = LoggerFactory.getLogger(ZeroEmissionZoneRunner.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Network network;
    private final Scenario scenario;
    private final ZeroEmissionZone zeroEmissionZone;
    private final ReroutingStrategy reroutingStrategy;
    private final StatisticsCollector statisticsCollector;
    private final Config config;
    private final ZeroEmissionZoneConfigGroup zezConfig;

    public ZeroEmissionZoneRunner(String configFilePath) {
        // Load configuration and register custom config group
        this.config = ConfigUtils.loadConfig(configFilePath, new ZeroEmissionZoneConfigGroup());
        this.zezConfig = (ZeroEmissionZoneConfigGroup) config.getModules().get(ZeroEmissionZoneConfigGroup.GROUP_NAME);

        // Create scenario using ScenarioUtils with the config
        this.scenario = ScenarioUtils.createScenario(config);
        ScenarioUtils.loadScenario(scenario);

        this.network = scenario.getNetwork();
        
        this.zeroEmissionZone = new ZeroEmissionZone(network, zezConfig);
        this.reroutingStrategy = new ReroutingStrategy(network, zezConfig);
        this.statisticsCollector = new StatisticsCollector(zeroEmissionZone);
        
        validateConfiguration();
    }

    private void validateConfiguration() {
        if (network.getNodes().isEmpty() || network.getLinks().isEmpty()) {
            throw new IllegalStateException("Network is empty or invalid");
        }

        if (zeroEmissionZone.getZoneLinks().isEmpty()) {
            throw new IllegalStateException("No zero emission zone links defined");
        }

        if (zeroEmissionZone.getAlternativeRouteLinks().isEmpty()) {
            throw new IllegalStateException("No alternative route links defined");
        }
    }

    public void runSimulation() {
        try {
            logger.info("Starting Zero Emission Zone Simulation with Three-Tier Vehicle Classification");
            logger.info("Network configuration: {} nodes, {} links", 
                network.getNodes().size(), 
                network.getLinks().size());

            // Initialize Controler with the scenario
            Controler controler = new Controler(scenario);
            controler.addControlerListener((StartupListener) event -> 
                logger.info("Simulation startup at {}", TIME_FORMATTER.format(LocalTime.now()))
            );

            // Create scoring function factory using scenario
            ScoringFunctionFactory scoringFunctionFactory = new CharyparNagelScoringFunctionFactory(scenario);
            controler.setScoringFunctionFactory(person -> 
                new CategoryBasedScoringFunction(scoringFunctionFactory.createNewScoringFunction(person))
            );

            controler.getConfig().controler().setOverwriteFileSetting(
                OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists
            );
            controler.run();
        } catch (Exception e) {
            logger.error("Failed to run simulation", e);
            throw new RuntimeException("Simulation execution failed", e);
        }
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            logger.error("Usage: ZeroEmissionZoneRunner <config-file>");
            return;
        }

        String configFilePath = args[0];
        ZeroEmissionZoneRunner runner = new ZeroEmissionZoneRunner(configFilePath);
        runner.runSimulation();
    }

    private class CategoryBasedScoringFunction implements ScoringFunction {
        private final ScoringFunction delegate;
        private double additionalScore = 0.0;

        public CategoryBasedScoringFunction(ScoringFunction delegate) {
            this.delegate = delegate;
        }

        @Override
        public void handleActivity(org.matsim.api.core.v01.population.Activity activity) {
            delegate.handleActivity(activity);
        }

        @Override
        public void handleLeg(Leg leg) {
            delegate.handleLeg(leg);
            
            Object vehicleIdAttribute = leg.getAttributes().getAttribute("vehicleId");
            String vehicleId = vehicleIdAttribute != null ? vehicleIdAttribute.toString() : null;
            
            if (vehicleId != null) {
                double departureTime = leg.getDepartureTime().seconds();
                int hours = (int) (departureTime / 3600) % 24;
                int minutes = (int) ((departureTime % 3600) / 60);
                LocalTime time = LocalTime.of(hours, minutes);
                
                additionalScore += zeroEmissionZone.calculateScore(leg, vehicleId, time);
            }
        }

        @Override
        public void addMoney(double amount) {
            delegate.addMoney(amount);
        }

        @Override
        public void agentStuck(double time) {
            delegate.agentStuck(time);
        }

        @Override
        public void finish() {
            delegate.finish();
            delegate.addMoney(additionalScore);
        }

        @Override
        public double getScore() {
            return delegate.getScore() + additionalScore;
        }

        @Override
        public void addScore(double amount) {
            additionalScore += amount;
        }

        @Override
        public void handleEvent(Event event) {
            // Optional event handling logic
        }
    }
}
