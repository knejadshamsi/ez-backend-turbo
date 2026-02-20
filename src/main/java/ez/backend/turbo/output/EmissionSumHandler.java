package ez.backend.turbo.output;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.events.handler.VehicleEntersTrafficEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.emissions.Pollutant;
import org.matsim.contrib.emissions.events.ColdEmissionEvent;
import org.matsim.contrib.emissions.events.ColdEmissionEventHandler;
import org.matsim.contrib.emissions.events.WarmEmissionEvent;
import org.matsim.contrib.emissions.events.WarmEmissionEventHandler;
import org.matsim.vehicles.Vehicle;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class EmissionSumHandler implements
        WarmEmissionEventHandler,
        ColdEmissionEventHandler,
        VehicleEntersTrafficEventHandler {

    static final int CO2 = 0;
    static final int NOX = 1;
    static final int PM25 = 2;
    static final int PM10 = 3;

    private double totalCo2;
    private double totalNox;
    private double totalPm25;
    private double totalPm10;
    private final Map<Id<Link>, double[]> linkTotals = new HashMap<>();
    private final Set<Id<Vehicle>> uniqueVehicles = new HashSet<>();
    private final Map<Id<Vehicle>, Integer> vehicleTripCounts = new HashMap<>();

    @Override
    public void reset(int iteration) {
        totalCo2 = 0;
        totalNox = 0;
        totalPm25 = 0;
        totalPm10 = 0;
        linkTotals.clear();
        uniqueVehicles.clear();
        vehicleTripCounts.clear();
    }

    @Override
    public void handleEvent(WarmEmissionEvent event) {
        accumulate(event.getLinkId(), event.getWarmEmissions());
    }

    @Override
    public void handleEvent(ColdEmissionEvent event) {
        accumulate(event.getLinkId(), event.getColdEmissions());
    }

    @Override
    public void handleEvent(VehicleEntersTrafficEvent event) {
        Id<Vehicle> vehicleId = event.getVehicleId();
        uniqueVehicles.add(vehicleId);
        vehicleTripCounts.merge(vehicleId, 1, Integer::sum);
    }

    private void accumulate(Id<Link> linkId, Map<Pollutant, Double> emissions) {
        double co2 = emissions.getOrDefault(Pollutant.CO2_TOTAL, 0.0);
        double pm25 = emissions.getOrDefault(Pollutant.PM2_5, 0.0)
                + emissions.getOrDefault(Pollutant.PM2_5_non_exhaust, 0.0);
        double nox = emissions.getOrDefault(Pollutant.NOx, 0.0);
        double pm10 = emissions.getOrDefault(Pollutant.PM, 0.0)
                + emissions.getOrDefault(Pollutant.PM_non_exhaust, 0.0);

        totalCo2 += co2;
        totalNox += nox;
        totalPm25 += pm25;
        totalPm10 += pm10;

        double[] link = linkTotals.computeIfAbsent(linkId, k -> new double[4]);
        link[CO2] += co2;
        link[NOX] += nox;
        link[PM25] += pm25;
        link[PM10] += pm10;
    }

    public double getTotalCo2() { return totalCo2; }
    public double getTotalNox() { return totalNox; }
    public double getTotalPm25() { return totalPm25; }
    public double getTotalPm10() { return totalPm10; }

    public Map<Id<Link>, double[]> getLinkTotals() {
        return new HashMap<>(linkTotals);
    }

    public Set<Id<Vehicle>> getUniqueVehicles() {
        return new HashSet<>(uniqueVehicles);
    }

    public Map<Id<Vehicle>, Integer> getVehicleTripCounts() {
        return new HashMap<>(vehicleTripCounts);
    }
}
