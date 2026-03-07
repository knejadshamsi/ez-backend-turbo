package ez.backend.turbo.simulation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.emissions.EmissionModule;
import org.matsim.core.config.Config;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.vehicles.Vehicles;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import ez.backend.turbo.services.ProcessManager;
import ez.backend.turbo.utils.L;

@Component
public class MatsimRunner {

    private static final Logger log = LogManager.getLogger(MatsimRunner.class);

    public record SimulationResult(
            Path outputDir,
            LegEmissionTracker legTracker,
            @Nullable PersonMoneyEventCollector moneyCollector) {}

    public SimulationResult runSimulation(
            Config config, Network network,
            TransitSchedule transitSchedule, Vehicles transitVehicles,
            Population population, Vehicles vehicles,
            String runType, UUID requestId, ProcessManager processManager,
            @Nullable CountDownLatch initGate,
            AbstractModule... additionalModules) {

        MutableScenario scenario = (MutableScenario) ScenarioUtils.createScenario(config);
        scenario.setNetwork(network);
        scenario.setPopulation(population);

        vehicles.getVehicleTypes().values().forEach(scenario.getVehicles()::addVehicleType);
        vehicles.getVehicles().values().forEach(scenario.getVehicles()::addVehicle);

        scenario.setTransitSchedule(transitSchedule);
        scenario.setTransitVehicles(transitVehicles);

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

        controler.addControlerListener((IterationStartsListener) event -> {
            if (event.getIteration() == 0 && initGate != null) {
                initGate.countDown();
            }
            if (processManager.isCancelled(requestId)) {
                throw new RuntimeException(L.msg("scenario.cancel.confirmed"));
            }
        });

        controler.run();

        Path outputDir = Path.of(config.controller().getOutputDirectory());
        String msgKey = isPolicy ? "simulation.policy.complete" : "simulation.baseline.complete";
        log.info(L.msg(msgKey), outputDir);
        return new SimulationResult(outputDir, legTracker, moneyCollector);
    }

    public static void normalizeModesInPopulation(Population population) {
        for (Person person : population.getPersons().values()) {
            for (Plan plan : person.getPlans()) {
                List<PlanElement> elements = plan.getPlanElements();
                for (int i = 0; i < elements.size(); i++) {
                    if (elements.get(i) instanceof Leg leg) {
                        if ("car_passenger".equals(leg.getMode())) {
                            leg.setMode("ride");
                        }
                    }
                }
                List<Leg> currentTrip = new ArrayList<>();
                for (PlanElement element : elements) {
                    if (element instanceof Leg leg) {
                        currentTrip.add(leg);
                    } else {
                        if (!currentTrip.isEmpty()) {
                            setTripRoutingMode(currentTrip);
                            currentTrip.clear();
                        }
                    }
                }
                if (!currentTrip.isEmpty()) {
                    setTripRoutingMode(currentTrip);
                }
            }
        }
    }

    private static void setTripRoutingMode(List<Leg> trip) {
        String mainMode = trip.size() == 1
                ? trip.get(0).getMode()
                : identifyMainMode(trip);
        for (Leg leg : trip) {
            leg.setRoutingMode(mainMode);
        }
    }

    private static String identifyMainMode(List<Leg> legs) {
        for (Leg leg : legs) {
            String mode = leg.getMode();
            if ("car".equals(mode)) return "car";
            if ("pt".equals(mode)) return "pt";
            if ("bike".equals(mode)) return "bike";
            if ("ride".equals(mode)) return "ride";
        }
        return legs.get(0).getMode();
    }
}
