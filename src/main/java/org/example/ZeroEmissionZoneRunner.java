package org.example;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Provider;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

/**
 * Main runner for Zero Emission Zone simulation.
 * Configures and executes a MATSim simulation with zero emission zone policies.
 */
public class ZeroEmissionZoneRunner {
    private static final Logger logger = LoggerFactory.getLogger(ZeroEmissionZoneRunner.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Network network;
    private final Scenario scenario;
    private final ZeroEmissionZone zeroEmissionZone;
    private final ZeroEmissionZoneConfigGroup zezConfig;
    private final Config config;
    private final StatisticsCollector statistics;
    private Controler controler;

    /**
     * Constructor to initialize simulation configuration.
     * Loads config, sets up simulation parameters, and prepares scenario.
     * 
     * @param configFilePath Path to the configuration XML file
     */
    public ZeroEmissionZoneRunner(String configFilePath) {
        try {
            // Initialize configuration with custom Zero Emission Zone config group
            this.config = ConfigUtils.loadConfig(configFilePath, new ZeroEmissionZoneConfigGroup());
            this.zezConfig = (ZeroEmissionZoneConfigGroup) config.getModules().get(ZeroEmissionZoneConfigGroup.GROUP_NAME);
            
            // Configure simulation output and data management
            configureOutputSettings();
            
            // Configure simulation runtime parameters
            configureSimulationParameters();

            // Create and load scenario with configured parameters
            this.scenario = ScenarioUtils.createScenario(config);
            ScenarioUtils.loadScenario(scenario);

            this.network = scenario.getNetwork();
            this.zeroEmissionZone = new ZeroEmissionZone(network, zezConfig);
            this.statistics = new StatisticsCollector(zeroEmissionZone);

            validateConfiguration();
        } catch (Exception e) {
            logger.error("Configuration initialization failed", e);
            throw new IllegalStateException("Failed to initialize simulation configuration", e);
        }
    }

    /**
     * Configure output settings for simulation results and logging.
     */
    private void configureOutputSettings() {
        config.controler().setCreateGraphs(false);
        config.controler().setWriteEventsInterval(1);  // Write events every iteration
        config.controler().setWritePlansInterval(1);   // Write plans every iteration
        config.controler().setDumpDataAtEnd(true);
        config.controler().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controler().setOutputDirectory("./output/zero-emission-test");
    }

    /**
     * Configure detailed simulation parameters for traffic dynamics and performance.
     */
    private void configureSimulationParameters() {
        // Global simulation settings
        config.global().setNumberOfThreads(1);
        config.global().setRandomSeed(4711L);

        // Detailed queue simulation configuration
        QSimConfigGroup qsimConfig = config.qsim();
        qsimConfig.setTrafficDynamics(QSimConfigGroup.TrafficDynamics.kinematicWaves);
        qsimConfig.setLinkDynamics(QSimConfigGroup.LinkDynamics.PassingQ);
        qsimConfig.setVehiclesSource(QSimConfigGroup.VehiclesSource.modeVehicleTypesFromVehiclesData);
        qsimConfig.setNumberOfThreads(1);
        qsimConfig.setRemoveStuckVehicles(false);
        qsimConfig.setInsertingWaitingVehiclesBeforeDrivingVehicles(true);
        qsimConfig.setSnapshotStyle(QSimConfigGroup.SnapshotStyle.queue);

        // Travel time calculation configuration
        TravelTimeCalculatorConfigGroup ttcConfig = config.travelTimeCalculator();
        ttcConfig.setTraveltimeBinSize(900); // 15-minute time bins
        ttcConfig.setMaxTime(36 * 3600); // Maximum simulation time: 36 hours
        ttcConfig.setCalculateLinkToLinkTravelTimes(false);
        ttcConfig.setFilterModes(true);
        
        // Specify modes to analyze for travel times
        Set<String> analyzedModes = new HashSet<>(Arrays.asList("car", "ev_car", "lev_car", "hev_car"));
        ttcConfig.setAnalyzedModes(analyzedModes);

        // Disable link statistics generation
        config.linkStats().setWriteLinkStatsInterval(0);
    }

    /**
     * Validate the configuration to ensure critical components are properly initialized.
     * Throws an exception if network or zero emission zone is improperly configured.
     */
    private void validateConfiguration() {
        if (network.getNodes().isEmpty() || network.getLinks().isEmpty()) {
            throw new IllegalStateException("Network is empty or invalid");
        }

        if (zeroEmissionZone.getZoneLinks().isEmpty()) {
            throw new IllegalStateException("No zero emission zone links defined");
        }
    }

    /**
     * Execute the simulation with Zero Emission Zone policies.
     * Sets up the controller, configures modules, and runs the simulation.
     */
    public void runSimulation() {
        try {
            logger.info("Starting Zero Emission Zone Simulation");
            logger.info("Network configuration: {} nodes, {} links", 
                network.getNodes().size(), 
                network.getLinks().size());

            controler = new Controler(scenario);

            // Create fallback travel time calculator using free speed
            final FreeSpeedTravelTime freeSpeedTravelTime = new FreeSpeedTravelTime();

            // Create travel time calculator for the network
            final TravelTimeCalculator travelTimeCalculator = new TravelTimeCalculator(
                network,
                config.travelTimeCalculator()
            );

            // Create Zero Emission Zone policy implementation
            ZeroEmissionZonePolicy zezPolicy = new ZeroEmissionZonePolicy(
                zeroEmissionZone,
                network,
                statistics,
                freeSpeedTravelTime
            );

            // Configure simulation modules and bindings
            controler.addOverridingModule(new AbstractModule() {
                @Override
                public void install() {
                    // Bind core Zero Emission Zone components
                    bind(ZeroEmissionZone.class).toInstance(zeroEmissionZone);
                    bind(StatisticsCollector.class).toInstance(statistics);
                    
                    // Bind travel time and disutility components
                    addEventHandlerBinding().toInstance(travelTimeCalculator);
                    bind(TravelTime.class).toProvider(new Provider<TravelTime>() {
                        @Override
                        public TravelTime get() {
                            return freeSpeedTravelTime;
                        }
                    });

                    // Bind Zero Emission Zone policy for car travel
                    addTravelDisutilityFactoryBinding("car").toInstance(zezPolicy);

                    // Add mobility simulation listener for dynamic replanning
                    addMobsimListenerBinding().toInstance(zezPolicy);
                }
            });

            // Execute the simulation
            controler.run();

            // Log final simulation statistics
            Map<String, Object> summary = statistics.generateSummaryStatistics();
            logger.info("Simulation completed at {} with statistics: {}", 
                TIME_FORMATTER.format(LocalTime.now()),
                summary);

        } catch (Exception e) {
            logger.error("Failed to run simulation", e);
            throw new RuntimeException("Simulation execution failed", e);
        }
    }

    /**
     * Main entry point for running the Zero Emission Zone simulation.
     * Requires a configuration file path as a command-line argument.
     * 
     * @param args Command-line arguments (expects config file path)
     */
    public static void main(String[] args) {
        if (args.length != 1) {
            logger.error("Usage: ZeroEmissionZoneRunner <config-file>");
            System.exit(1);
        }

        String configFilePath = args[0];
        new ZeroEmissionZoneRunner(configFilePath).runSimulation();
    }
}
