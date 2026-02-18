package ez.backend.turbo.simulation;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.events.VehicleAbortsEvent;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.PersonLeavesVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleAbortsEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleEntersTrafficEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleLeavesTrafficEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.emissions.Pollutant;
import org.matsim.contrib.emissions.events.ColdEmissionEvent;
import org.matsim.contrib.emissions.events.ColdEmissionEventHandler;
import org.matsim.contrib.emissions.events.WarmEmissionEvent;
import org.matsim.contrib.emissions.events.WarmEmissionEventHandler;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.Vehicles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class LegEmissionTracker implements
        VehicleEntersTrafficEventHandler,
        VehicleLeavesTrafficEventHandler,
        VehicleAbortsEventHandler,
        PersonEntersVehicleEventHandler,
        PersonLeavesVehicleEventHandler,
        WarmEmissionEventHandler,
        ColdEmissionEventHandler {

    private final Vehicles vehicles;

    private final ConcurrentHashMap<Id<Vehicle>, Id<Person>> vehicleDriverMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Id<Vehicle>, LegAccumulator> activeLegs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Id<Vehicle>, ConcurrentHashMap<Id<Person>, PassengerAccumulator>> transitPassengers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Id<Person>, List<LegEmission>> personLegs = new ConcurrentHashMap<>();

    public record LegEmission(double startTime, double endTime, double co2, double pm25, double nox, double pm10) {}

    @Inject
    LegEmissionTracker(Scenario scenario) {
        this.vehicles = scenario.getVehicles();
    }

    @Override
    public void reset(int iteration) {
        vehicleDriverMap.clear();
        activeLegs.clear();
        transitPassengers.clear();
        personLegs.clear();
    }

    @Override
    public void handleEvent(VehicleEntersTrafficEvent event) {
        Id<Vehicle> vehicleId = event.getVehicleId();
        Id<Person> personId = event.getPersonId();
        vehicleDriverMap.put(vehicleId, personId);
        activeLegs.put(vehicleId, new LegAccumulator(event.getTime()));
    }

    @Override
    public void handleEvent(VehicleLeavesTrafficEvent event) {
        Id<Vehicle> vehicleId = event.getVehicleId();
        LegAccumulator acc = activeLegs.remove(vehicleId);
        if (acc == null) return;

        Id<Person> personId = vehicleDriverMap.remove(vehicleId);
        if (personId == null) return;

        LegEmission leg = acc.toLegEmission(event.getTime());
        personLegs.computeIfAbsent(personId, k -> Collections.synchronizedList(new ArrayList<>())).add(leg);
    }

    @Override
    public void handleEvent(VehicleAbortsEvent event) {
        Id<Vehicle> vehicleId = event.getVehicleId();
        activeLegs.remove(vehicleId);
        vehicleDriverMap.remove(vehicleId);
    }

    @Override
    public void handleEvent(PersonEntersVehicleEvent event) {
        Id<Vehicle> vehicleId = event.getVehicleId();
        if (vehicleDriverMap.containsKey(vehicleId)) return;

        transitPassengers
                .computeIfAbsent(vehicleId, k -> new ConcurrentHashMap<>())
                .put(event.getPersonId(), new PassengerAccumulator(event.getTime()));
    }

    @Override
    public void handleEvent(PersonLeavesVehicleEvent event) {
        Id<Vehicle> vehicleId = event.getVehicleId();
        ConcurrentHashMap<Id<Person>, PassengerAccumulator> passengers = transitPassengers.get(vehicleId);
        if (passengers == null) return;

        Id<Person> personId = event.getPersonId();
        PassengerAccumulator acc = passengers.remove(personId);
        if (acc == null) return;

        LegEmission leg = new LegEmission(
                acc.boardTime, event.getTime(),
                acc.co2, acc.pm25, acc.nox, acc.pm10);
        personLegs.computeIfAbsent(personId, k -> Collections.synchronizedList(new ArrayList<>())).add(leg);
    }

    @Override
    public void handleEvent(WarmEmissionEvent event) {
        attributeEmissions(event.getVehicleId(), event.getWarmEmissions());
    }

    @Override
    public void handleEvent(ColdEmissionEvent event) {
        attributeEmissions(event.getVehicleId(), event.getColdEmissions());
    }

    private void attributeEmissions(Id<Vehicle> vehicleId, Map<Pollutant, Double> emissions) {
        double co2 = emissions.getOrDefault(Pollutant.CO2_TOTAL, 0.0);
        double pm25 = emissions.getOrDefault(Pollutant.PM2_5, 0.0)
                + emissions.getOrDefault(Pollutant.PM2_5_non_exhaust, 0.0);
        double nox = emissions.getOrDefault(Pollutant.NOx, 0.0);
        double pm10 = emissions.getOrDefault(Pollutant.PM, 0.0)
                + emissions.getOrDefault(Pollutant.PM_non_exhaust, 0.0);

        // Private vehicle — full emissions to driver
        LegAccumulator driverAcc = activeLegs.get(vehicleId);
        if (driverAcc != null && vehicleDriverMap.containsKey(vehicleId)) {
            driverAcc.addEmissions(co2, pm25, nox, pm10);
            return;
        }

        // Transit vehicle — split across passengers currently aboard
        ConcurrentHashMap<Id<Person>, PassengerAccumulator> passengers = transitPassengers.get(vehicleId);
        if (passengers == null || passengers.isEmpty()) return;

        int count = passengers.size();
        double shareCo2 = co2 / count;
        double sharePm25 = pm25 / count;
        double shareNox = nox / count;
        double sharePm10 = pm10 / count;

        for (PassengerAccumulator pAcc : passengers.values()) {
            pAcc.addEmissions(shareCo2, sharePm25, shareNox, sharePm10);
        }
    }

    public Map<Id<Person>, List<LegEmission>> getPersonLegs() {
        return new HashMap<>(personLegs);
    }

    private static class LegAccumulator {
        final double startTime;
        double co2;
        double pm25;
        double nox;
        double pm10;

        LegAccumulator(double startTime) {
            this.startTime = startTime;
        }

        void addEmissions(double co2, double pm25, double nox, double pm10) {
            this.co2 += co2;
            this.pm25 += pm25;
            this.nox += nox;
            this.pm10 += pm10;
        }

        LegEmission toLegEmission(double endTime) {
            return new LegEmission(startTime, endTime, co2, pm25, nox, pm10);
        }
    }

    private static class PassengerAccumulator {
        final double boardTime;
        double co2;
        double pm25;
        double nox;
        double pm10;

        PassengerAccumulator(double boardTime) {
            this.boardTime = boardTime;
        }

        void addEmissions(double co2, double pm25, double nox, double pm10) {
            this.co2 += co2;
            this.pm25 += pm25;
            this.nox += nox;
            this.pm10 += pm10;
        }
    }
}
