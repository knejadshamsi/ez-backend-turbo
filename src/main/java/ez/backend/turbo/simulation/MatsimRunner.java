package ez.backend.turbo.simulation;

import java.nio.file.Path;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.emissions.EmissionModule;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.vehicles.EngineInformation;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import ez.backend.turbo.endpoints.SimulationRequest;
import ez.backend.turbo.services.SourceRegistry;
import ez.backend.turbo.utils.L;

@Component
public class MatsimRunner {

    private static final Logger log = LogManager.getLogger(MatsimRunner.class);

    private final MatsimConfigBuilder configBuilder;
    private final SourceRegistry sourceRegistry;

    public record SimulationResult(
            Path outputDir,
            LegEmissionTracker legTracker,
            @Nullable PersonMoneyEventCollector moneyCollector) {}

    public MatsimRunner(MatsimConfigBuilder configBuilder, SourceRegistry sourceRegistry) {
        this.configBuilder = configBuilder;
        this.sourceRegistry = sourceRegistry;
    }

    public SimulationResult runSimulation(SimulationRequest request, UUID requestId,
                              Population population, Vehicles vehicles,
                              Path plansFile, Path vehiclesFile, String runType,
                              AbstractModule... additionalModules) {
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
        prefixTransitVehicleIds(transit.schedule(), transit.vehicles());
        tagBusVehicleTypes(transit.vehicles());
        scenario.setTransitSchedule(transit.schedule());
        scenario.setTransitVehicles(transit.vehicles());

        log.info(L.msg("simulation.matsim.scenario.ready"));

        LegEmissionTracker legTracker = new LegEmissionTracker(scenario);
        PersonMoneyEventCollector moneyCollector = null;

        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new AbstractModule() {
            @Override
            public void install() {
                bind(EmissionModule.class).asEagerSingleton();
            }
        });
        controler.addOverridingModule(new LegEmissionModule(legTracker));

        boolean isPolicy = "policy".equals(runType);
        if (isPolicy) {
            moneyCollector = new PersonMoneyEventCollector();
            controler.addOverridingModule(new PersonMoneyEventModule(moneyCollector));
        }

        for (AbstractModule module : additionalModules) {
            controler.addOverridingModule(module);
        }
        controler.run();

        Path outputDir = Path.of(config.controller().getOutputDirectory());
        String msgKey = isPolicy ? "simulation.policy.complete" : "simulation.baseline.complete";
        log.info(L.msg(msgKey), outputDir);
        return new SimulationResult(outputDir, legTracker, moneyCollector);
    }

    private void prefixTransitVehicleIds(
            org.matsim.pt.transitSchedule.api.TransitSchedule schedule, Vehicles transitVehicles) {
        java.util.Set<String> busRouteVehicleIds = new java.util.HashSet<>();
        java.util.Set<String> subwayRouteVehicleIds = new java.util.HashSet<>();

        for (TransitLine line : schedule.getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                String mode = route.getTransportMode();
                for (var departure : route.getDepartures().values()) {
                    String vid = departure.getVehicleId().toString();
                    if ("subway".equals(mode) || "rail".equals(mode) || "metro".equals(mode)) {
                        subwayRouteVehicleIds.add(vid);
                    } else {
                        busRouteVehicleIds.add(vid);
                    }
                }
            }
        }

        java.util.Map<Id<Vehicle>, String> renames = new java.util.HashMap<>();
        for (Vehicle v : transitVehicles.getVehicles().values()) {
            String id = v.getId().toString();
            if (subwayRouteVehicleIds.contains(id)) {
                renames.put(v.getId(), "subway_" + id);
            } else if (busRouteVehicleIds.contains(id)) {
                renames.put(v.getId(), "bus_" + id);
            }
        }

        for (var entry : renames.entrySet()) {
            Vehicle old = transitVehicles.getVehicles().get(entry.getKey());
            transitVehicles.removeVehicle(entry.getKey());
            Vehicle renamed = transitVehicles.getFactory()
                    .createVehicle(Id.createVehicleId(entry.getValue()), old.getType());
            transitVehicles.addVehicle(renamed);
        }

        for (TransitLine line : schedule.getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                for (var departure : route.getDepartures().values()) {
                    String oldId = departure.getVehicleId().toString();
                    String newId = renames.get(Id.createVehicleId(oldId));
                    if (newId != null) {
                        departure.setVehicleId(Id.createVehicleId(newId));
                    }
                }
            }
        }
    }

    private void tagBusVehicleTypes(Vehicles transitVehicles) {
        for (VehicleType vt : transitVehicles.getVehicleTypes().values()) {
            EngineInformation ei = vt.getEngineInformation();
            if (VehicleUtils.getHbefaVehicleCategory(ei) == null) {
                vt.setNetworkMode("car");
                VehicleUtils.setHbefaVehicleCategory(ei, "URBAN_BUS");
                VehicleUtils.setHbefaTechnology(ei, "diesel");
                VehicleUtils.setHbefaSizeClass(ei, "not specified");
                VehicleUtils.setHbefaEmissionsConcept(ei, "UBus-d-EU3");
            }
        }
    }
}
