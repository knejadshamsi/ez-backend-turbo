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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class SectionOneHandler implements
        WarmEmissionEventHandler,
        ColdEmissionEventHandler,
        VehicleEntersTrafficEventHandler {

    static final int CO2 = 0;
    static final int NOX = 1;
    static final int PM25 = 2;
    static final int PM10 = 3;
    static final int POLLUTANT_COUNT = 4;
    static final int TIME_BIN_COUNT = 12;

    private final double simulationEndTime;
    private final double binWidth;
    private final double[] totals = new double[POLLUTANT_COUNT];
    private final Map<Id<Link>, double[]> linkTotals = new HashMap<>();
    private final Map<Id<Link>, double[]> privateLinkTotals = new HashMap<>();
    private final double[][] timeBins = new double[TIME_BIN_COUNT][POLLUTANT_COUNT];
    private final double[][] privateTimeBins = new double[TIME_BIN_COUNT][POLLUTANT_COUNT];
    private final double[][] transitTimeBins = new double[TIME_BIN_COUNT][POLLUTANT_COUNT];
    private final Map<Id<Vehicle>, double[]> vehicleEmissions = new HashMap<>();
    private double warmTotal;
    private double coldTotal;
    private double privateWarmTotal;
    private double privateColdTotal;

    public SectionOneHandler(double simulationEndTime) {
        this.simulationEndTime = simulationEndTime;
        this.binWidth = simulationEndTime / TIME_BIN_COUNT;
    }

    @Override
    public void reset(int iteration) {
        Arrays.fill(totals, 0);
        linkTotals.clear();
        privateLinkTotals.clear();
        for (double[] bin : timeBins) Arrays.fill(bin, 0);
        for (double[] bin : privateTimeBins) Arrays.fill(bin, 0);
        for (double[] bin : transitTimeBins) Arrays.fill(bin, 0);
        vehicleEmissions.clear();
        warmTotal = 0;
        coldTotal = 0;
        privateWarmTotal = 0;
        privateColdTotal = 0;
    }

    @Override
    public void handleEvent(WarmEmissionEvent event) {
        double[] values = extractPollutants(event.getWarmEmissions());
        boolean transit = isTransit(event.getVehicleId());
        accumulate(event.getLinkId(), event.getVehicleId(), event.getTime(), values, transit);
        warmTotal += values[CO2];
        if (!transit) privateWarmTotal += values[CO2];
    }

    @Override
    public void handleEvent(ColdEmissionEvent event) {
        double[] values = extractPollutants(event.getColdEmissions());
        boolean transit = isTransit(event.getVehicleId());
        accumulate(event.getLinkId(), event.getVehicleId(), event.getTime(), values, transit);
        coldTotal += values[CO2];
        if (!transit) privateColdTotal += values[CO2];
    }

    @Override
    public void handleEvent(VehicleEntersTrafficEvent event) {
    }

    private static boolean isTransit(Id<Vehicle> vehicleId) {
        String id = vehicleId.toString();
        return id.startsWith("bus_") || id.startsWith("subway_");
    }

    private void accumulate(Id<Link> linkId, Id<Vehicle> vehicleId,
                            double time, double[] values, boolean transit) {
        for (int i = 0; i < POLLUTANT_COUNT; i++) {
            totals[i] += values[i];
        }

        double[] link = linkTotals.computeIfAbsent(linkId, k -> new double[POLLUTANT_COUNT]);
        for (int i = 0; i < POLLUTANT_COUNT; i++) {
            link[i] += values[i];
        }

        if (!transit) {
            double[] privLink = privateLinkTotals.computeIfAbsent(linkId, k -> new double[POLLUTANT_COUNT]);
            for (int i = 0; i < POLLUTANT_COUNT; i++) {
                privLink[i] += values[i];
            }
        }

        int bin = timeBinIndex(time);
        for (int i = 0; i < POLLUTANT_COUNT; i++) {
            timeBins[bin][i] += values[i];
        }

        double[][] targetBins = transit ? transitTimeBins : privateTimeBins;
        for (int i = 0; i < POLLUTANT_COUNT; i++) {
            targetBins[bin][i] += values[i];
        }

        double[] veh = vehicleEmissions.computeIfAbsent(vehicleId, k -> new double[POLLUTANT_COUNT]);
        for (int i = 0; i < POLLUTANT_COUNT; i++) {
            veh[i] += values[i];
        }
    }

    private int timeBinIndex(double time) {
        if (binWidth <= 0) return 0;
        int bin = (int) (time / binWidth);
        return Math.min(bin, TIME_BIN_COUNT - 1);
    }

    private double[] extractPollutants(Map<Pollutant, Double> emissions) {
        double co2 = emissions.getOrDefault(Pollutant.CO2_TOTAL, 0.0);
        double nox = emissions.getOrDefault(Pollutant.NOx, 0.0);
        double pm25 = emissions.getOrDefault(Pollutant.PM2_5, 0.0)
                + emissions.getOrDefault(Pollutant.PM2_5_non_exhaust, 0.0);
        double pm10 = emissions.getOrDefault(Pollutant.PM, 0.0)
                + emissions.getOrDefault(Pollutant.PM_non_exhaust, 0.0);
        return new double[]{co2, nox, pm25, pm10};
    }

    public double[] getTotals() { return totals.clone(); }
    public Map<Id<Link>, double[]> getLinkTotals() { return new HashMap<>(linkTotals); }
    public Map<Id<Link>, double[]> getPrivateLinkTotals() { return new HashMap<>(privateLinkTotals); }
    public double[][] getTimeBins() { return timeBins.clone(); }
    public double[][] getPrivateTimeBins() { return deepCopy(privateTimeBins); }
    public double[][] getTransitTimeBins() { return deepCopy(transitTimeBins); }
    public Map<Id<Vehicle>, double[]> getVehicleEmissions() { return new HashMap<>(vehicleEmissions); }
    public double getWarmTotal() { return warmTotal; }
    public double getColdTotal() { return coldTotal; }
    public double getPrivateWarmTotal() { return privateWarmTotal; }
    public double getPrivateColdTotal() { return privateColdTotal; }
    public double getSimulationEndTime() { return simulationEndTime; }
    public double getBinWidth() { return binWidth; }

    private static double[][] deepCopy(double[][] src) {
        double[][] copy = new double[src.length][];
        for (int i = 0; i < src.length; i++) copy[i] = src[i].clone();
        return copy;
    }
}
