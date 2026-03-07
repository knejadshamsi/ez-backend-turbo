package ez.backend.turbo.simulation;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.matsim.contrib.emissions.utils.EmissionsConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.PlansConfigGroup;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.pt.config.TransitConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import ez.backend.turbo.config.StartupValidator;
import ez.backend.turbo.endpoints.SimulationRequest;
import ez.backend.turbo.services.ScaleFactorConfig;
import ez.backend.turbo.services.ScoringConfig;
import ez.backend.turbo.services.SourceRegistry;
import ez.backend.turbo.services.StrategyConfig;
import ez.backend.turbo.services.VehicleTypeRegistry;

@Component
public class MatsimConfigBuilder {

    private static final long RANDOM_SEED = 4711L;
    private static final double STORAGE_CAP_FLOOR = 0.03;
    private static final double QSIM_END_TIME = 30 * 3600.0;
    private static final double HOME_DURATION = 12 * 3600.0;
    private static final double WORK_DURATION = 8 * 3600.0;
    private static final double WORK_OPEN = 6 * 3600.0;
    private static final double WORK_CLOSE = 20 * 3600.0;
    private static final double EDUCATION_DURATION = 6 * 3600.0;
    private static final double EDUCATION_OPEN = 7 * 3600.0;
    private static final double EDUCATION_CLOSE = 18 * 3600.0;
    private static final double EDUCATION_LATEST_START = 9 * 3600.0;
    private static final double SHOP_DURATION = 1 * 3600.0;
    private static final double SHOP_OPEN = 8 * 3600.0;
    private static final double SHOP_CLOSE = 21 * 3600.0;
    private static final double SHOP_LATEST_START = 20 * 3600.0;
    private static final double LEISURE_DURATION = 2 * 3600.0;
    private static final double LEISURE_OPEN = 6 * 3600.0;
    private static final double LEISURE_CLOSE = 24 * 3600.0;
    private static final double LEISURE_LATEST_START = 23 * 3600.0;
    private static final double OTHER_DURATION = 1.5 * 3600.0;
    private static final double OTHER_CLOSE = 24 * 3600.0;
    private static final double OTHER_LATEST_START = 24 * 3600.0;

    private final Path dataRoot;
    private final String targetCrs;
    private final ScoringConfig scoringConfig;
    private final ScaleFactorConfig scaleFactorConfig;
    private final StrategyConfig strategyConfig;
    private final SourceRegistry sourceRegistry;
    private final VehicleTypeRegistry vehicleTypeRegistry;

    public MatsimConfigBuilder(StartupValidator startupValidator,
                               SourceRegistry sourceRegistry,
                               VehicleTypeRegistry vehicleTypeRegistry) {
        this.dataRoot = startupValidator.getDataRoot();
        this.targetCrs = startupValidator.getTargetCrs();
        this.scoringConfig = startupValidator.getScoringConfig();
        this.scaleFactorConfig = startupValidator.getScaleFactorConfig();
        this.strategyConfig = startupValidator.getStrategyConfig();
        this.sourceRegistry = sourceRegistry;
        this.vehicleTypeRegistry = vehicleTypeRegistry;
    }

    public Config build(SimulationRequest request, UUID requestId, String runType,
                        @Nullable Path plansFile, @Nullable Path vehiclesFile) {
        Config config = ConfigUtils.createConfig();
        configureGlobal(config);
        configureController(config, request, requestId, runType);
        configureQSim(config, request);
        configureNetwork(config, request);
        configurePlans(config, plansFile);
        configureVehicles(config, vehiclesFile);
        configureTransit(config, request);
        configureScoring(config, request);
        configureReplanning(config);
        configureEmissions(config);
        return config;
    }

    private void configureGlobal(Config config) {
        config.global().setCoordinateSystem(targetCrs);
        config.global().setNumberOfThreads(strategyConfig.globalThreads());
        config.global().setRandomSeed(RANDOM_SEED);
    }

    private void configureController(Config config, SimulationRequest request, UUID requestId, String runType) {
        int lastIteration = request.getSimulationOptions().getIterations() - 1;
        Path outputDir = dataRoot.resolve("output").resolve(requestId.toString()).resolve(runType);
        config.controller().setLastIteration(lastIteration);
        config.controller().setOutputDirectory(outputDir.toString());
        config.controller().setOverwriteFileSetting(
                OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setWriteEventsInterval(Math.max(lastIteration, 1));
        config.controller().setWritePlansInterval(Math.max(lastIteration, 1));
    }

    private void configureQSim(Config config, SimulationRequest request) {
        double percentage = request.getSimulationOptions().getPercentage() / 100.0;
        config.qsim().setFlowCapFactor(percentage);
        config.qsim().setStorageCapFactor(Math.max(percentage, STORAGE_CAP_FLOOR));
        config.qsim().setEndTime(QSIM_END_TIME);
        config.qsim().setMainModes(List.of("car"));
        config.qsim().setVehiclesSource(QSimConfigGroup.VehiclesSource.fromVehiclesData);
        config.qsim().setLinkDynamics(QSimConfigGroup.LinkDynamics.PassingQ);
        config.qsim().setTrafficDynamics(QSimConfigGroup.TrafficDynamics.kinematicWaves);
        config.qsim().setNumberOfThreads(strategyConfig.qsimThreads());
    }

    private void configureNetwork(Config config, SimulationRequest request) {
        int year = request.getSources().getNetwork().getYear();
        String name = request.getSources().getNetwork().getName();
        Path networkPath = sourceRegistry.resolve("network", year, name);
        config.network().setInputFile(networkPath.toString());
    }

    private void configurePlans(Config config, @Nullable Path plansFile) {
        if (plansFile != null) {
            config.plans().setInputFile(plansFile.toString());
        }
        config.plans().setHandlingOfPlansWithoutRoutingMode(
                PlansConfigGroup.HandlingOfPlansWithoutRoutingMode.useMainModeIdentifier);
    }

    private void configureVehicles(Config config, @Nullable Path vehiclesFile) {
        if (vehiclesFile != null) {
            config.vehicles().setVehiclesFile(vehiclesFile.toString());
        }
    }

    private void configureTransit(Config config, SimulationRequest request) {
        int year = request.getSources().getPublicTransport().getYear();
        String name = request.getSources().getPublicTransport().getName();
        SourceRegistry.TransitPaths transit = sourceRegistry.resolveTransit(year, name);
        config.transit().setUseTransit(true);
        config.transit().setTransitScheduleFile(transit.schedule().toString());
        config.transit().setVehiclesFile(transit.vehicles().toString());
        config.transit().setRoutingAlgorithmType(
                TransitConfigGroup.TransitRoutingAlgorithmType.SwissRailRaptor);
    }

    private void configureScoring(Config config, SimulationRequest request) {
        config.scoring().setPerforming_utils_hr(scoringConfig.performingUtilsPerHr());
        config.scoring().setMarginalUtilityOfMoney(scoringConfig.marginalUtilityOfMoney());
        config.scoring().setBrainExpBeta(scoringConfig.brainExpBeta());
        config.scoring().setLearningRate(scoringConfig.learningRate());

        ScoringConfigGroup.ActivityParams home = new ScoringConfigGroup.ActivityParams("home");
        home.setTypicalDuration(HOME_DURATION);
        config.scoring().addActivityParams(home);

        ScoringConfigGroup.ActivityParams work = new ScoringConfigGroup.ActivityParams("work");
        work.setTypicalDuration(WORK_DURATION);
        work.setOpeningTime(WORK_OPEN);
        work.setClosingTime(WORK_CLOSE);
        config.scoring().addActivityParams(work);

        ScoringConfigGroup.ActivityParams education = new ScoringConfigGroup.ActivityParams("education");
        education.setTypicalDuration(EDUCATION_DURATION);
        education.setOpeningTime(EDUCATION_OPEN);
        education.setClosingTime(EDUCATION_CLOSE);
        education.setLatestStartTime(EDUCATION_LATEST_START);
        config.scoring().addActivityParams(education);

        ScoringConfigGroup.ActivityParams shop = new ScoringConfigGroup.ActivityParams("shop");
        shop.setTypicalDuration(SHOP_DURATION);
        shop.setOpeningTime(SHOP_OPEN);
        shop.setClosingTime(SHOP_CLOSE);
        shop.setLatestStartTime(SHOP_LATEST_START);
        config.scoring().addActivityParams(shop);

        ScoringConfigGroup.ActivityParams leisure = new ScoringConfigGroup.ActivityParams("leisure");
        leisure.setTypicalDuration(LEISURE_DURATION);
        leisure.setOpeningTime(LEISURE_OPEN);
        leisure.setClosingTime(LEISURE_CLOSE);
        leisure.setLatestStartTime(LEISURE_LATEST_START);
        config.scoring().addActivityParams(leisure);

        ScoringConfigGroup.ActivityParams other = new ScoringConfigGroup.ActivityParams("other");
        other.setTypicalDuration(OTHER_DURATION);
        other.setClosingTime(OTHER_CLOSE);
        other.setLatestStartTime(OTHER_LATEST_START);
        config.scoring().addActivityParams(other);

        addInteractionActivity(config, "car interaction");
        addInteractionActivity(config, "pt interaction");
        addInteractionActivity(config, "bike interaction");
        addInteractionActivity(config, "walk interaction");
        addInteractionActivity(config, "ride interaction");

        SimulationRequest.ModeUtilities mu = request.getModeUtilities();

        ScoringConfigGroup.ModeParams carParams = config.scoring().getOrCreateModeParams("car");
        carParams.setConstant(mu.getCar() * scaleFactorConfig.car());
        carParams.setMarginalUtilityOfTraveling(scoringConfig.carMarginalUtilityOfTraveling());
        carParams.setMonetaryDistanceRate(scoringConfig.carMonetaryDistanceRate());

        ScoringConfigGroup.ModeParams walkParams = config.scoring().getOrCreateModeParams("walk");
        walkParams.setConstant(mu.getWalk() * scaleFactorConfig.walk());
        walkParams.setMarginalUtilityOfTraveling(scoringConfig.walkMarginalUtilityOfTraveling());
        walkParams.setMarginalUtilityOfDistance(scoringConfig.walkMarginalUtilityOfDistance());

        ScoringConfigGroup.ModeParams bikeParams = config.scoring().getOrCreateModeParams("bike");
        bikeParams.setConstant(mu.getBike() * scaleFactorConfig.bike());
        bikeParams.setMarginalUtilityOfTraveling(scoringConfig.bikeMarginalUtilityOfTraveling());

        double ptConstant = (mu.getSubway() * scaleFactorConfig.subway()
                + mu.getBus() * scaleFactorConfig.bus()) / 2.0;
        ScoringConfigGroup.ModeParams ptParams = config.scoring().getOrCreateModeParams("pt");
        ptParams.setConstant(ptConstant);
        ptParams.setMarginalUtilityOfTraveling(scoringConfig.ptMarginalUtilityOfTraveling());
    }

    private void configureReplanning(Config config) {
        config.replanning().setMaxAgentPlanMemorySize(strategyConfig.maxAgentPlanMemorySize());
        config.replanning().setFractionOfIterationsToDisableInnovation(0.8);

        addStrategy(config, "ChangeExpBeta", strategyConfig.changeExpBetaWeight());
        addStrategy(config, "ReRoute", strategyConfig.reRouteWeight());
        addStrategy(config, "SubtourModeChoice", strategyConfig.subtourModeChoiceWeight());
        addStrategy(config, "TimeAllocationMutator", strategyConfig.timeAllocationMutatorWeight());

        config.subtourModeChoice().setModes(strategyConfig.subtourModes());
        config.subtourModeChoice().setChainBasedModes(strategyConfig.chainBasedModes());
        config.timeAllocationMutator().setMutationRange(strategyConfig.mutationRange());
    }

    private void addInteractionActivity(Config config, String type) {
        ScoringConfigGroup.ActivityParams params = new ScoringConfigGroup.ActivityParams(type);
        params.setScoringThisActivityAtAll(false);
        config.scoring().addActivityParams(params);
    }

    private void addStrategy(Config config, String name, double weight) {
        ReplanningConfigGroup.StrategySettings settings = new ReplanningConfigGroup.StrategySettings();
        settings.setStrategyName(name);
        settings.setWeight(weight);
        config.replanning().addStrategySettings(settings);
    }

    private void configureEmissions(Config config) {
        EmissionsConfigGroup emissions = ConfigUtils.addOrGetModule(config, EmissionsConfigGroup.class);
        emissions.setAverageWarmEmissionFactorsFile(vehicleTypeRegistry.getHbefaWarmFile().toString());
        emissions.setAverageColdEmissionFactorsFile(vehicleTypeRegistry.getHbefaColdFile().toString());

        Path hbefaDir = vehicleTypeRegistry.getHbefaWarmFile().getParent();
        Path detailedWarm = hbefaDir.resolve("detailed_warm.csv");
        Path detailedCold = hbefaDir.resolve("detailed_cold.csv");

        if (detailedWarm.toFile().isFile() && detailedCold.toFile().isFile()) {
            emissions.setDetailedWarmEmissionFactorsFile(detailedWarm.toString());
            emissions.setDetailedColdEmissionFactorsFile(detailedCold.toString());
            emissions.setDetailedVsAverageLookupBehavior(
                    EmissionsConfigGroup.DetailedVsAverageLookupBehavior.tryDetailedThenTechnologyAverageThenAverageTable);
        } else {
            emissions.setDetailedVsAverageLookupBehavior(
                    EmissionsConfigGroup.DetailedVsAverageLookupBehavior.directlyTryAverageTable);
        }

        emissions.setNonScenarioVehicles(EmissionsConfigGroup.NonScenarioVehicles.ignore);
        emissions.setHbefaTableConsistencyCheckingLevel(
                EmissionsConfigGroup.HbefaTableConsistencyCheckingLevel.none);
    }
}
