package ez.backend.turbo.output;

import ez.backend.turbo.endpoints.SimulationRequest;
import ez.backend.turbo.utils.L;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.emissions.events.EmissionEventsReader;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.Vehicles;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class EmissionsAggregator {

    private static final String[] CATEGORY_ORDER = {
            "zeroEmission", "nearZeroEmission", "lowEmission", "midEmission", "highEmission"
    };

    public record EmissionsResult(
            Map<String, Object> paragraph1,
            Map<String, Object> paragraph2,
            Map<String, Object> barChart,
            Map<String, Object> pieChart,
            Map<String, List<Map<String, Object>>> mapData
    ) {}

    public static EmissionsResult aggregate(
            Path baselineDir, Path policyDir,
            Vehicles vehicles, Network network,
            SimulationRequest.CarDistribution carDistribution,
            double modeShiftPercentage, double simulationAreaKm2,
            String targetCrs, String fleetMetric, double mixingHeightMeters) {

        EmissionSumHandler baselineHandler = replayEvents(baselineDir);
        EmissionSumHandler policyHandler = replayEvents(policyDir);

        Map<String, Object> paragraph1 = buildParagraph1(
                baselineHandler, policyHandler, modeShiftPercentage);

        double[] policyShares = computeFleetShares(policyHandler, vehicles, fleetMetric);

        Map<String, Object> paragraph2 = buildParagraph2(
                policyHandler, carDistribution, policyShares,
                simulationAreaKm2, mixingHeightMeters);

        Map<String, Object> barChart = buildBarChart(baselineHandler, policyHandler);

        Map<String, Object> pieChart = buildPieChart(carDistribution, policyShares);

        Map<String, List<Map<String, Object>>> mapData = buildMapData(
                baselineHandler, policyHandler, network, targetCrs);

        return new EmissionsResult(paragraph1, paragraph2, barChart, pieChart, mapData);
    }

    private static EmissionSumHandler replayEvents(Path outputDir) {
        EventsManager manager = EventsUtils.createEventsManager();
        EmissionSumHandler handler = new EmissionSumHandler();
        manager.addHandler(handler);
        new EmissionEventsReader(manager)
                .readFile(outputDir.resolve("output_events.xml.gz").toString());
        return handler;
    }

    private static Map<String, Object> buildParagraph1(
            EmissionSumHandler baseline, EmissionSumHandler policy,
            double modeShiftPercentage) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("co2Baseline", baseline.getTotalCo2());
        p.put("co2PostPolicy", policy.getTotalCo2());
        p.put("pm25Baseline", baseline.getTotalPm25());
        p.put("pm25PostPolicy", policy.getTotalPm25());
        p.put("noxBaseline", baseline.getTotalNox());
        p.put("noxPostPolicy", policy.getTotalNox());
        p.put("pm10Baseline", baseline.getTotalPm10());
        p.put("pm10PostPolicy", policy.getTotalPm10());
        p.put("modeShiftPercentage", modeShiftPercentage);
        return p;
    }

    private static Map<String, Object> buildParagraph2(
            EmissionSumHandler policy,
            SimulationRequest.CarDistribution dist,
            double[] policyShares,
            double simulationAreaKm2, double mixingHeightMeters) {
        double evBaseline = dist.getZeroEmission() + dist.getNearZeroEmission();
        double standardBaseline = dist.getLowEmission() + dist.getMidEmission();
        double heavyBaseline = dist.getHighEmission();

        double evPolicy = policyShares[0] + policyShares[1];
        double standardPolicy = policyShares[2] + policyShares[3];
        double heavyPolicy = policyShares[4];

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("pm25PostPolicy", policy.getTotalPm25());
        p.put("zoneArea", simulationAreaKm2);
        p.put("mixingHeight", mixingHeightMeters);
        p.put("evShareBaseline", evBaseline);
        p.put("evSharePostPolicy", evPolicy);
        p.put("standardShareBaseline", standardBaseline);
        p.put("standardSharePostPolicy", standardPolicy);
        p.put("heavyShareBaseline", heavyBaseline);
        p.put("heavySharePostPolicy", heavyPolicy);
        return p;
    }

    private static Map<String, Object> buildBarChart(
            EmissionSumHandler baseline, EmissionSumHandler policy) {
        Map<String, Object> bar = new LinkedHashMap<>();
        bar.put("baselineData", List.of(
                baseline.getTotalCo2(), baseline.getTotalNox(),
                baseline.getTotalPm25(), baseline.getTotalPm10()));
        bar.put("postPolicyData", List.of(
                policy.getTotalCo2(), policy.getTotalNox(),
                policy.getTotalPm25(), policy.getTotalPm10()));
        return bar;
    }

    private static Map<String, Object> buildPieChart(
            SimulationRequest.CarDistribution dist, double[] policyShares) {
        Map<String, Object> pie = new LinkedHashMap<>();
        pie.put("vehicleBaselineData", List.of(
                dist.getZeroEmission(), dist.getNearZeroEmission(),
                dist.getLowEmission(), dist.getMidEmission(),
                dist.getHighEmission()));
        pie.put("vehiclePostPolicyData", List.of(
                policyShares[0], policyShares[1],
                policyShares[2], policyShares[3],
                policyShares[4]));
        return pie;
    }

    private static double[] computeFleetShares(
            EmissionSumHandler handler, Vehicles vehicles, String fleetMetric) {
        Map<String, Double> counts = new HashMap<>();
        for (String cat : CATEGORY_ORDER) {
            counts.put(cat, 0.0);
        }

        double total = 0;

        if ("trip-count".equals(fleetMetric)) {
            for (Map.Entry<Id<Vehicle>, Integer> entry : handler.getVehicleTripCounts().entrySet()) {
                Vehicle v = vehicles.getVehicles().get(entry.getKey());
                if (v == null) continue;
                String category = v.getType().getId().toString();
                if (counts.containsKey(category)) {
                    counts.merge(category, (double) entry.getValue(), Double::sum);
                    total += entry.getValue();
                }
            }
        } else {
            for (Id<Vehicle> vehicleId : handler.getUniqueVehicles()) {
                Vehicle v = vehicles.getVehicles().get(vehicleId);
                if (v == null) continue;
                String category = v.getType().getId().toString();
                if (counts.containsKey(category)) {
                    counts.merge(category, 1.0, Double::sum);
                    total += 1;
                }
            }
        }

        double[] shares = new double[5];
        if (total > 0) {
            for (int i = 0; i < CATEGORY_ORDER.length; i++) {
                shares[i] = (counts.get(CATEGORY_ORDER[i]) / total) * 100.0;
            }
        }
        return shares;
    }

    private static Map<String, List<Map<String, Object>>> buildMapData(
            EmissionSumHandler baseline, EmissionSumHandler policy,
            Network network, String targetCrs) {
        CoordinateTransformation toWgs84 =
                TransformationFactory.getCoordinateTransformation(targetCrs, "EPSG:4326");

        Map<Id<Link>, double[]> baselineLinks = baseline.getLinkTotals();
        Map<Id<Link>, double[]> policyLinks = policy.getLinkTotals();

        Set<Id<Link>> allLinkIds = new HashSet<>(baselineLinks.keySet());
        allLinkIds.addAll(policyLinks.keySet());

        double[] zero = {0, 0, 0, 0};

        List<Map<String, Object>> co2Points = new ArrayList<>();
        List<Map<String, Object>> noxPoints = new ArrayList<>();
        List<Map<String, Object>> pm25Points = new ArrayList<>();
        List<Map<String, Object>> pm10Points = new ArrayList<>();

        for (Id<Link> linkId : allLinkIds) {
            Link link = network.getLinks().get(linkId);
            if (link == null) continue;

            double[] base = baselineLinks.getOrDefault(linkId, zero);
            double[] pol = policyLinks.getOrDefault(linkId, zero);

            Coord from = link.getFromNode().getCoord();
            Coord to = link.getToNode().getCoord();
            Coord mid = new Coord(
                    (from.getX() + to.getX()) / 2.0,
                    (from.getY() + to.getY()) / 2.0);
            Coord wgs = toWgs84.transform(mid);
            List<Double> position = List.of(wgs.getX(), wgs.getY());

            addPoint(co2Points, position, base[EmissionSumHandler.CO2] - pol[EmissionSumHandler.CO2]);
            addPoint(noxPoints, position, base[EmissionSumHandler.NOX] - pol[EmissionSumHandler.NOX]);
            addPoint(pm25Points, position, base[EmissionSumHandler.PM25] - pol[EmissionSumHandler.PM25]);
            addPoint(pm10Points, position, base[EmissionSumHandler.PM10] - pol[EmissionSumHandler.PM10]);
        }

        Map<String, List<Map<String, Object>>> mapData = new LinkedHashMap<>();
        mapData.put("CO2", co2Points);
        mapData.put("NOx", noxPoints);
        mapData.put("PM2.5", pm25Points);
        mapData.put("PM10", pm10Points);
        return mapData;
    }

    private static void addPoint(List<Map<String, Object>> points,
                                  List<Double> position, double weight) {
        if (weight == 0.0) return;
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("position", position);
        point.put("weight", weight);
        points.add(point);
    }
}
