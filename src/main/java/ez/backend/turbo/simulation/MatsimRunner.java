package ez.backend.turbo.simulation;

import java.nio.file.Path;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.emissions.EmissionModule;
import org.matsim.core.config.Config;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.vehicles.EngineInformation;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;
import org.springframework.stereotype.Component;

import ez.backend.turbo.endpoints.SimulationRequest;
import ez.backend.turbo.services.SourceRegistry;
import ez.backend.turbo.utils.L;

@Component
public class MatsimRunner {

    private static final Logger log = LogManager.getLogger(MatsimRunner.class);

    private final MatsimConfigBuilder configBuilder;
    private final SourceRegistry sourceRegistry;

    public MatsimRunner(MatsimConfigBuilder configBuilder, SourceRegistry sourceRegistry) {
        this.configBuilder = configBuilder;
        this.sourceRegistry = sourceRegistry;
    }

    public Path runSimulation(SimulationRequest request, UUID requestId,
                              Population population, Vehicles vehicles,
                              Path plansFile, Path vehiclesFile, String runType) {
        Config config = configBuilder.build(request, requestId, runType, plansFile, vehiclesFile);
        MutableScenario scenario = (MutableScenario) ScenarioUtils.createScenario(config);

        int netYear = request.getSources().getNetwork().getYear();
        String netName = request.getSources().getNetwork().getName();
        scenario.setNetwork(sourceRegistry.getNetwork(netYear, netName));

        scenario.setPopulation(population);

        vehicles.getVehicleTypes().values().forEach(scenario.getVehicles()::addVehicleType);
        vehicles.getVehicles().values().forEach(scenario.getVehicles()::addVehicle);

        int transitYear = request.getSources().getPublicTransport().getYear();
        String transitName = request.getSources().getPublicTransport().getName();
        SourceRegistry.TransitData transit = sourceRegistry.getTransit(transitYear, transitName);
        scenario.setTransitSchedule(transit.schedule());
        tagTransitVehicleTypes(transit.vehicles());
        scenario.setTransitVehicles(transit.vehicles());

        log.info(L.msg("simulation.matsim.scenario.ready"));

        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new AbstractModule() {
            @Override
            public void install() {
                bind(EmissionModule.class).asEagerSingleton();
            }
        });
        controler.run();

        Path outputDir = Path.of(config.controller().getOutputDirectory());
        log.info(L.msg("simulation.baseline.complete"), outputDir);
        return outputDir;
    }

    private void tagTransitVehicleTypes(Vehicles transitVehicles) {
        for (VehicleType vt : transitVehicles.getVehicleTypes().values()) {
            if (VehicleUtils.getHbefaVehicleCategory(vt.getEngineInformation()) == null) {
                vt.setNetworkMode("car");
                EngineInformation ei = vt.getEngineInformation();
                VehicleUtils.setHbefaVehicleCategory(ei, "PASSENGER_CAR");
                VehicleUtils.setHbefaTechnology(ei, "diesel");
                VehicleUtils.setHbefaSizeClass(ei, "not specified");
                VehicleUtils.setHbefaEmissionsConcept(ei, "PC-D-Euro-3");
            }
        }
    }
}
