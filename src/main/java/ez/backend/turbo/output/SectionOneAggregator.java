package ez.backend.turbo.output;

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

import static ez.backend.turbo.output.SectionOneHandler.*;

public class SectionOneAggregator {

    private static final String[] PRIVATE_CATEGORIES = {
            "zeroEmission", "nearZeroEmission", "lowEmission", "midEmission", "highEmission"
    };

    public record SectionOneResult(
            Map<String, Object> paragraph1,
            Map<String, Object> lineChart,
            Map<String, Object> stackedBar,
            Map<String, Object> paragraph2,
            Map<String, Object> warmColdIntensity,
            Map<String, Map<String, List<Map<String, Object>>>> mapData
    ) {}

    public static SectionOneHandler replayEvents(Path outputDir, double simulationEndTime) {
        EventsManager manager = EventsUtils.createEventsManager();
        SectionOneHandler handler = new SectionOneHandler(simulationEndTime);
        manager.addHandler(handler);
        new EmissionEventsReader(manager)
                .readFile(outputDir.resolve("output_events.xml.gz").toString());
        return handler;
    }

    public static SectionOneResult aggregate(
            SectionOneHandler baseline, SectionOneHandler policy,
            Vehicles vehicles, Network network,
            double baselineDistanceMeters, double policyDistanceMeters,
            double simulationAreaKm2, String targetCrs,
            double mixingHeightMeters, double mapPointIntervalMeters,
            double sampleFraction) {

        double scaleFactor = 1.0 / sampleFraction;

        Map<String, Object> paragraph1 = buildParagraph1(baseline, policy, vehicles, scaleFactor);
        Map<String, Object> lineChart = buildLineChart(baseline, policy, scaleFactor);
        Map<String, Object> stackedBar = buildStackedBar(baseline, policy, vehicles, scaleFactor);
        Map<String, Object> paragraph2 = buildParagraph2(
                baseline, policy, vehicles, simulationAreaKm2, mixingHeightMeters, scaleFactor);
        Map<String, Object> warmColdIntensity = buildWarmColdIntensity(
                baseline, policy, vehicles,
                baselineDistanceMeters, policyDistanceMeters, scaleFactor);
        Map<String, Map<String, List<Map<String, Object>>>> mapData = buildMapData(
                baseline, policy, network, targetCrs, mapPointIntervalMeters);

        return new SectionOneResult(
                paragraph1, lineChart, stackedBar,
                paragraph2, warmColdIntensity, mapData);
    }

    private static Map<String, Object> buildParagraph1(
            SectionOneHandler baseline, SectionOneHandler policy,
            Vehicles vehicles, double scaleFactor) {

        double[] basePriv = scaledPrivate(baseline, vehicles, scaleFactor);
        double[] polPriv = scaledPrivate(policy, vehicles, scaleFactor);
        double[] baseTransit = sumByFleet(baseline.getVehicleEmissions(), vehicles, false);
        double[] polTransit = sumByFleet(policy.getVehicleEmissions(), vehicles, false);

        Map<String, Object> p = new LinkedHashMap<>();

        p.put("co2Baseline", basePriv[CO2] + baseTransit[CO2]);
        p.put("co2Policy", polPriv[CO2] + polTransit[CO2]);
        p.put("co2DeltaPercent", deltaPercent(basePriv[CO2] + baseTransit[CO2],
                polPriv[CO2] + polTransit[CO2]));

        p.put("privateCo2Baseline", basePriv[CO2]);
        p.put("privateCo2Policy", polPriv[CO2]);
        p.put("privateCo2DeltaPercent", deltaPercent(basePriv[CO2], polPriv[CO2]));

        p.put("transitCo2Baseline", baseTransit[CO2]);
        p.put("transitCo2Policy", polTransit[CO2]);

        p.put("noxBaseline", basePriv[NOX] + baseTransit[NOX]);
        p.put("noxPolicy", polPriv[NOX] + polTransit[NOX]);
        p.put("noxDeltaPercent", deltaPercent(basePriv[NOX] + baseTransit[NOX],
                polPriv[NOX] + polTransit[NOX]));

        p.put("privateNoxBaseline", basePriv[NOX]);
        p.put("privateNoxPolicy", polPriv[NOX]);
        p.put("privateNoxDeltaPercent", deltaPercent(basePriv[NOX], polPriv[NOX]));

        p.put("pm25Baseline", basePriv[PM25] + baseTransit[PM25]);
        p.put("pm25Policy", polPriv[PM25] + polTransit[PM25]);
        p.put("pm25DeltaPercent", deltaPercent(basePriv[PM25] + baseTransit[PM25],
                polPriv[PM25] + polTransit[PM25]));

        p.put("privatePm25Baseline", basePriv[PM25]);
        p.put("privatePm25Policy", polPriv[PM25]);
        p.put("privatePm25DeltaPercent", deltaPercent(basePriv[PM25], polPriv[PM25]));

        p.put("transitPm25Baseline", baseTransit[PM25]);
        p.put("transitPm25Policy", polTransit[PM25]);

        p.put("pm10Baseline", basePriv[PM10] + baseTransit[PM10]);
        p.put("pm10Policy", polPriv[PM10] + polTransit[PM10]);
        p.put("pm10DeltaPercent", deltaPercent(basePriv[PM10] + baseTransit[PM10],
                polPriv[PM10] + polTransit[PM10]));

        p.put("privatePm10Baseline", basePriv[PM10]);
        p.put("privatePm10Policy", polPriv[PM10]);
        p.put("privatePm10DeltaPercent", deltaPercent(basePriv[PM10], polPriv[PM10]));

        p.put("transitPm10Baseline", baseTransit[PM10]);
        p.put("transitPm10Policy", polTransit[PM10]);

        p.put("transitNoxBaseline", baseTransit[NOX]);
        p.put("transitNoxPolicy", polTransit[NOX]);

        return p;
    }

    private static double[] scaledPrivate(SectionOneHandler handler,
                                           Vehicles vehicles, double scaleFactor) {
        double[] raw = sumByFleet(handler.getVehicleEmissions(), vehicles, true);
        double[] scaled = new double[POLLUTANT_COUNT];
        for (int i = 0; i < POLLUTANT_COUNT; i++) scaled[i] = raw[i] * scaleFactor;
        return scaled;
    }

    private static double[] sumByFleet(Map<Id<Vehicle>, double[]> vehicleEmissions,
                                        Vehicles vehicles, boolean wantPrivate) {
        double[] result = new double[POLLUTANT_COUNT];
        Set<String> privateSet = new HashSet<>(List.of(PRIVATE_CATEGORIES));
        for (Map.Entry<Id<Vehicle>, double[]> entry : vehicleEmissions.entrySet()) {
            String vid = entry.getKey().toString();
            boolean isTransit = vid.startsWith("bus_") || vid.startsWith("subway_");

            if (wantPrivate && isTransit) continue;
            if (!wantPrivate && !isTransit) continue;

            if (wantPrivate) {
                Vehicle v = vehicles.getVehicles().get(entry.getKey());
                if (v == null) continue;
                if (!privateSet.contains(v.getType().getId().toString())) continue;
            }

            double[] emissions = entry.getValue();
            for (int i = 0; i < POLLUTANT_COUNT; i++) {
                result[i] += emissions[i];
            }
        }
        return result;
    }

    private static Map<String, Object> buildLineChart(
            SectionOneHandler baseline, SectionOneHandler policy,
            double scaleFactor) {

        double[][] basePriv = baseline.getPrivateTimeBins();
        double[][] polPriv = policy.getPrivateTimeBins();
        double binWidth = baseline.getBinWidth();

        List<String> labels = new ArrayList<>();
        for (int i = 0; i < TIME_BIN_COUNT; i++) {
            double start = i * binWidth;
            double end = (i + 1) * binWidth;
            labels.add(formatTime(start) + "-" + formatTime(end));
        }

        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("timeBins", labels);
        chart.put("co2Baseline", privateOnlyBins(basePriv, CO2, scaleFactor));
        chart.put("co2Policy", privateOnlyBins(polPriv, CO2, scaleFactor));
        chart.put("noxBaseline", privateOnlyBins(basePriv, NOX, scaleFactor));
        chart.put("noxPolicy", privateOnlyBins(polPriv, NOX, scaleFactor));
        chart.put("pm25Baseline", privateOnlyBins(basePriv, PM25, scaleFactor));
        chart.put("pm25Policy", privateOnlyBins(polPriv, PM25, scaleFactor));
        chart.put("pm10Baseline", privateOnlyBins(basePriv, PM10, scaleFactor));
        chart.put("pm10Policy", privateOnlyBins(polPriv, PM10, scaleFactor));
        return chart;
    }

    private static List<Double> privateOnlyBins(double[][] privateBins,
                                                 int pollutantIndex, double scaleFactor) {
        List<Double> values = new ArrayList<>(TIME_BIN_COUNT);
        for (int i = 0; i < TIME_BIN_COUNT; i++) {
            values.add(privateBins[i][pollutantIndex] * scaleFactor);
        }
        return values;
    }

    private static Map<String, Object> buildStackedBar(
            SectionOneHandler baseline, SectionOneHandler policy,
            Vehicles vehicles, double scaleFactor) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("baseline", groupByVehicleType(baseline.getVehicleEmissions(), vehicles, scaleFactor));
        result.put("policy", groupByVehicleType(policy.getVehicleEmissions(), vehicles, scaleFactor));
        return result;
    }

    private static Map<String, Object> groupByVehicleType(
            Map<Id<Vehicle>, double[]> vehicleEmissions, Vehicles vehicles,
            double scaleFactor) {

        Map<String, double[]> privateByType = new LinkedHashMap<>();
        for (String cat : PRIVATE_CATEGORIES) {
            privateByType.put(cat, new double[POLLUTANT_COUNT]);
        }
        double[] transitTotals = new double[POLLUTANT_COUNT];

        Set<String> privateSet = new HashSet<>(List.of(PRIVATE_CATEGORIES));

        for (Map.Entry<Id<Vehicle>, double[]> entry : vehicleEmissions.entrySet()) {
            String vehicleIdStr = entry.getKey().toString();
            double[] emissions = entry.getValue();

            if (vehicleIdStr.startsWith("bus_") || vehicleIdStr.startsWith("subway_")) {
                for (int i = 0; i < POLLUTANT_COUNT; i++) {
                    transitTotals[i] += emissions[i];
                }
                continue;
            }

            Vehicle v = vehicles.getVehicles().get(entry.getKey());
            if (v == null) continue;
            String category = v.getType().getId().toString();
            if (!privateSet.contains(category)) continue;

            double[] cat = privateByType.get(category);
            for (int i = 0; i < POLLUTANT_COUNT; i++) {
                cat[i] += emissions[i];
            }
        }

        for (double[] vals : privateByType.values()) {
            for (int i = 0; i < POLLUTANT_COUNT; i++) {
                vals[i] *= scaleFactor;
            }
        }

        Map<String, Object> privateResult = new LinkedHashMap<>();
        privateResult.put("co2ByType", extractByType(privateByType, CO2));
        privateResult.put("noxByType", extractByType(privateByType, NOX));
        privateResult.put("pm25ByType", extractByType(privateByType, PM25));
        privateResult.put("pm10ByType", extractByType(privateByType, PM10));

        Map<String, Object> transitResult = new LinkedHashMap<>();
        transitResult.put("co2", transitTotals[CO2]);
        transitResult.put("nox", transitTotals[NOX]);
        transitResult.put("pm25", transitTotals[PM25]);
        transitResult.put("pm10", transitTotals[PM10]);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("private", privateResult);
        result.put("transit", transitResult);
        return result;
    }

    private static Map<String, Double> extractByType(Map<String, double[]> byType, int pollutantIndex) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> entry : byType.entrySet()) {
            result.put(entry.getKey(), entry.getValue()[pollutantIndex]);
        }
        return result;
    }

    private static Map<String, Object> buildParagraph2(
            SectionOneHandler baseline, SectionOneHandler policy,
            Vehicles vehicles, double simulationAreaKm2,
            double mixingHeightMeters, double scaleFactor) {

        double[] basePriv = scaledPrivate(baseline, vehicles, scaleFactor);
        double[] polPriv = scaledPrivate(policy, vehicles, scaleFactor);

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("pm25PerKm2Baseline", simulationAreaKm2 > 0 ? basePriv[PM25] / simulationAreaKm2 : 0.0);
        p.put("pm25PerKm2Policy", simulationAreaKm2 > 0 ? polPriv[PM25] / simulationAreaKm2 : 0.0);
        p.put("zoneAreaKm2", simulationAreaKm2);
        p.put("mixingHeightMeters", mixingHeightMeters);
        return p;
    }

    private static Map<String, Object> buildWarmColdIntensity(
            SectionOneHandler baseline, SectionOneHandler policy,
            Vehicles vehicles,
            double baselineDistanceMeters, double policyDistanceMeters,
            double scaleFactor) {

        Map<String, Object> warmCold = new LinkedHashMap<>();
        warmCold.put("warmBaseline", baseline.getPrivateWarmTotal() * scaleFactor);
        warmCold.put("coldBaseline", baseline.getPrivateColdTotal() * scaleFactor);
        warmCold.put("warmPolicy", policy.getPrivateWarmTotal() * scaleFactor);
        warmCold.put("coldPolicy", policy.getPrivateColdTotal() * scaleFactor);

        double[] basePriv = sumByFleet(baseline.getVehicleEmissions(), vehicles, true);
        double[] polPriv = sumByFleet(policy.getVehicleEmissions(), vehicles, true);

        double baseCo2Private = basePriv[CO2] * scaleFactor;
        double polCo2Private = polPriv[CO2] * scaleFactor;
        double baseDistScaled = baselineDistanceMeters * scaleFactor;
        double polDistScaled = policyDistanceMeters * scaleFactor;

        Map<String, Object> intensity = new LinkedHashMap<>();
        intensity.put("co2Baseline", baseCo2Private);
        intensity.put("co2Policy", polCo2Private);
        intensity.put("distanceBaseline", baseDistScaled);
        intensity.put("distancePolicy", polDistScaled);
        intensity.put("co2PerMeterBaseline",
                baselineDistanceMeters > 0 ? basePriv[CO2] / baselineDistanceMeters : 0.0);
        intensity.put("co2PerMeterPolicy",
                policyDistanceMeters > 0 ? polPriv[CO2] / policyDistanceMeters : 0.0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("warmCold", warmCold);
        result.put("intensity", intensity);
        return result;
    }

    private static Map<String, Map<String, List<Map<String, Object>>>> buildMapData(
            SectionOneHandler baseline, SectionOneHandler policy,
            Network network, String targetCrs, double intervalMeters) {

        CoordinateTransformation toWgs84 =
                TransformationFactory.getCoordinateTransformation(targetCrs, "EPSG:4326");

        String[] pollutantKeys = {"CO2", "NOx", "PM2.5", "PM10"};

        Map<String, Map<String, List<Map<String, Object>>>> result = new LinkedHashMap<>();
        result.put("baseline", buildLinkPoints(baseline.getLinkTotals(), network, toWgs84, intervalMeters, pollutantKeys));
        result.put("policy", buildLinkPoints(policy.getLinkTotals(), network, toWgs84, intervalMeters, pollutantKeys));
        result.put("privateBaseline", buildLinkPoints(baseline.getPrivateLinkTotals(), network, toWgs84, intervalMeters, pollutantKeys));
        result.put("privatePolicy", buildLinkPoints(policy.getPrivateLinkTotals(), network, toWgs84, intervalMeters, pollutantKeys));
        return result;
    }

    private static Map<String, List<Map<String, Object>>> buildLinkPoints(
            Map<Id<Link>, double[]> linkEmissions, Network network,
            CoordinateTransformation toWgs84, double intervalMeters,
            String[] pollutantKeys) {

        Map<String, List<Map<String, Object>>> map = new LinkedHashMap<>();
        for (String key : pollutantKeys) {
            map.put(key, new ArrayList<>());
        }

        double[] zero = new double[POLLUTANT_COUNT];

        for (Map.Entry<Id<Link>, double[]> entry : linkEmissions.entrySet()) {
            Link link = network.getLinks().get(entry.getKey());
            if (link == null) continue;

            double[] vals = entry.getValue();

            Coord from = link.getFromNode().getCoord();
            Coord to = link.getToNode().getCoord();
            double dx = to.getX() - from.getX();
            double dy = to.getY() - from.getY();
            double linkLength = Math.sqrt(dx * dx + dy * dy);

            int pointCount = Math.max(1, (int) (linkLength / intervalMeters));
            double stepX = dx / pointCount;
            double stepY = dy / pointCount;

            for (int p = 0; p < pointCount; p++) {
                double x = from.getX() + stepX * (p + 0.5);
                double y = from.getY() + stepY * (p + 0.5);
                Coord wgs = toWgs84.transform(new Coord(x, y));
                List<Double> position = List.of(wgs.getX(), wgs.getY());

                for (int i = 0; i < POLLUTANT_COUNT; i++) {
                    double weight = vals[i] / pointCount;
                    if (weight > 0) {
                        map.get(pollutantKeys[i]).add(
                                Map.of("position", position, "weight", weight));
                    }
                }
            }
        }

        return map;
    }

    private static double deltaPercent(double baseline, double policy) {
        if (baseline == 0) return 0.0;
        return ((policy - baseline) / baseline) * 100.0;
    }

    private static String formatTime(double seconds) {
        int totalMinutes = (int) (seconds / 60);
        int hours = totalMinutes / 60;
        int minutes = totalMinutes % 60;
        return String.format("%d:%02d", hours, minutes);
    }
}
