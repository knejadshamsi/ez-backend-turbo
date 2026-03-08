package ez.backend.turbo.output;

import ez.backend.turbo.endpoints.SimulationRequest;
import ez.backend.turbo.simulation.PersonMoneyEventCollector;
import ez.backend.turbo.simulation.PersonMoneyEventCollector.MoneyRecord;
import ez.backend.turbo.utils.L;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SectionTwoAnalyzer {

    private static final int MODE_SHIFT = 0;
    private static final int REROUTED = 1;
    private static final int PAID_PENALTY = 2;
    private static final int CANCELLED = 3;
    private static final int NO_CHANGE = 4;
    private static final int CATEGORY_COUNT = 5;

    private static final String[] CATEGORY_KEYS = {
            "modeShift", "rerouted", "paidPenalty", "cancelled", "noChange"
    };

    private static final String[] MAP_CATEGORIES = {
            "modeShift", "rerouted", "paidPenalty", "cancelled"
    };

    private static final String[] MODES = {"car", "bus", "subway", "walk", "bike"};

    public record SectionTwoResult(
            Map<String, Object> paragraph,
            Map<String, Object> sankey,
            Map<String, Object> bar,
            Map<String, Map<String, List<Map<String, Object>>>> mapData
    ) {}

    private record TripRow(String person, int tripNumber, String mainMode,
                           double traveledDistance,
                           double startX, double startY,
                           double endX, double endY) {}

    private record LegRow(String person, String tripId, String mode,
                          String vehicleId, double distance) {}

    public static SectionTwoResult analyze(
            Path baselineDir, Path policyDir,
            PersonMoneyEventCollector moneyCollector,
            double rerouteThresholdMeters, String multiModalPt,
            String targetCrs, SimulationRequest request) {

        Map<String, TripRow> baselineTrips = parseTrips(baselineDir.resolve("output_trips.csv.gz"));
        Map<String, TripRow> policyTrips = parseTrips(policyDir.resolve("output_trips.csv.gz"));
        Map<String, List<LegRow>> baselineLegs = parseLegs(baselineDir.resolve("output_legs.csv.gz"));
        Map<String, List<LegRow>> policyLegs = parseLegs(policyDir.resolve("output_legs.csv.gz"));

        Map<String, List<MoneyRecord>> moneyByKey = indexMoneyEvents(moneyCollector);

        CoordinateTransformation toWgs84 =
                TransformationFactory.getCoordinateTransformation(targetCrs, "EPSG:4326");

        int[] categoryCounts = new int[CATEGORY_COUNT];
        Set<String> affectedPersons = new HashSet<>();

        Map<String, Integer> sankeyFlows = new HashMap<>();

        int[] baselineModeCounts = new int[MODES.length];
        int[] policyModeCounts = new int[MODES.length];

        Map<String, Map<String, List<Map<String, Object>>>> mapData = initMapData();

        for (Map.Entry<String, TripRow> entry : baselineTrips.entrySet()) {
            String tripKey = entry.getKey();
            TripRow baseTrip = entry.getValue();
            TripRow polTrip = policyTrips.get(tripKey);

            String baseMode = resolveMode(baseTrip.mainMode(), tripKey, baselineLegs, multiModalPt);
            incrementModeCount(baselineModeCounts, baseMode);

            if (polTrip == null) {
                categoryCounts[CANCELLED]++;
                affectedPersons.add(baseTrip.person());
                addMapPoints(mapData, baseTrip, CANCELLED, toWgs84);
                continue;
            }

            String polMode = resolveMode(polTrip.mainMode(), tripKey, policyLegs, multiModalPt);
            incrementModeCount(policyModeCounts, polMode);

            addSankeyFlow(sankeyFlows, baseMode, polMode);

            int category = classify(baseMode, polMode, baseTrip, polTrip,
                    tripKey, moneyByKey, rerouteThresholdMeters);

            categoryCounts[category]++;
            if (category != NO_CHANGE) {
                affectedPersons.add(baseTrip.person());
                addMapPoints(mapData, baseTrip, category, toWgs84);
            }
        }

        for (Map.Entry<String, TripRow> entry : policyTrips.entrySet()) {
            if (!baselineTrips.containsKey(entry.getKey())) {
                String polMode = resolveMode(entry.getValue().mainMode(),
                        entry.getKey(), policyLegs, multiModalPt);
                incrementModeCount(policyModeCounts, polMode);
            }
        }

        int totalTrips = baselineTrips.size();
        int affectedTrips = totalTrips - categoryCounts[NO_CHANGE];

        Map<String, Object> paragraph = buildParagraph(
                categoryCounts, totalTrips, affectedTrips,
                affectedPersons.size(), request);
        Map<String, Object> sankey = buildSankey(sankeyFlows);
        Map<String, Object> bar = buildBar(baselineModeCounts, policyModeCounts);

        return new SectionTwoResult(paragraph, sankey, bar, mapData);
    }

    private static String resolveMode(String mainMode, String tripKey,
                                       Map<String, List<LegRow>> legs,
                                       String multiModalPt) {
        if ("ride".equals(mainMode)) return "car";
        if (!"pt".equals(mainMode)) return mainMode;
        return resolvePtMode(tripKey, legs, multiModalPt);
    }

    private static String resolvePtMode(String tripKey,
                                         Map<String, List<LegRow>> legs,
                                         String multiModalPt) {
        List<LegRow> tripLegs = legs.get(tripKey);
        if (tripLegs == null || tripLegs.isEmpty()) return "bus";

        boolean hasBus = false;
        boolean hasSubway = false;
        double longestBusDist = 0;
        double longestSubwayDist = 0;
        String firstTransit = null;

        for (LegRow leg : tripLegs) {
            String vid = leg.vehicleId();
            if (vid == null || vid.isEmpty()) continue;

            if (vid.startsWith("subway_")) {
                hasSubway = true;
                if (leg.distance() > longestSubwayDist) longestSubwayDist = leg.distance();
                if (firstTransit == null) firstTransit = "subway";
            } else if (vid.startsWith("bus_")) {
                hasBus = true;
                if (leg.distance() > longestBusDist) longestBusDist = leg.distance();
                if (firstTransit == null) firstTransit = "bus";
            }
        }

        if (!hasBus && !hasSubway) return "bus";

        return switch (multiModalPt) {
            case "longest" -> longestSubwayDist > longestBusDist ? "subway" : "bus";
            case "first" -> firstTransit != null ? firstTransit : "bus";
            default -> hasSubway ? "subway" : "bus";
        };
    }

    private static int classify(String baseMode, String polMode,
                                TripRow baseTrip, TripRow polTrip,
                                String tripKey,
                                Map<String, List<MoneyRecord>> moneyByKey,
                                double rerouteThreshold) {
        if (!baseMode.equals(polMode)) return MODE_SHIFT;

        if ("car".equals(baseMode)) {
            double distDelta = Math.abs(
                    polTrip.traveledDistance() - baseTrip.traveledDistance());
            if (distDelta > rerouteThreshold) return REROUTED;

            String moneyKey = baseTrip.person() + "_" + (baseTrip.tripNumber() - 1);
            List<MoneyRecord> events = moneyByKey.get(moneyKey);
            if (events != null) {
                for (MoneyRecord mr : events) {
                    if (mr.amount() > -10000) return PAID_PENALTY;
                }
            }
        }

        return NO_CHANGE;
    }

    private static void addSankeyFlow(Map<String, Integer> flows,
                                       String from, String to) {
        String key = from + "→" + to;
        flows.merge(key, 1, Integer::sum);
    }

    private static int modeIndex(String mode) {
        for (int i = 0; i < MODES.length; i++) {
            if (MODES[i].equals(mode)) return i;
        }
        return 0;
    }

    private static void incrementModeCount(int[] counts, String mode) {
        counts[modeIndex(mode)]++;
    }

    private static Map<String, Object> buildParagraph(
            int[] counts, int totalTrips, int affectedTrips,
            int affectedAgents, SimulationRequest request) {

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("totalTrips", totalTrips);
        p.put("affectedTrips", affectedTrips);
        p.put("affectedAgents", affectedAgents);

        p.put("modeShiftCount", counts[MODE_SHIFT]);
        p.put("modeShiftPct", pct(counts[MODE_SHIFT], totalTrips));
        p.put("reroutedCount", counts[REROUTED]);
        p.put("reroutedPct", pct(counts[REROUTED], totalTrips));
        p.put("paidPenaltyCount", counts[PAID_PENALTY]);
        p.put("paidPenaltyPct", pct(counts[PAID_PENALTY], totalTrips));
        p.put("cancelledCount", counts[CANCELLED]);
        p.put("cancelledPct", pct(counts[CANCELLED], totalTrips));
        p.put("noChangeCount", counts[NO_CHANGE]);
        p.put("noChangePct", pct(counts[NO_CHANGE], totalTrips));

        String dominant = "noChange";
        int maxAffected = 0;
        for (int i = 0; i < CATEGORY_COUNT - 1; i++) {
            if (counts[i] > maxAffected) {
                maxAffected = counts[i];
                dominant = CATEGORY_KEYS[i];
            }
        }
        p.put("dominantResponse", maxAffected > 0 ? dominant : "noChange");

        List<Map<String, Object>> penaltyCharges = new ArrayList<>();
        for (SimulationRequest.Zone zone : request.getZones()) {
            for (SimulationRequest.Policy policy : zone.getPolicies()) {
                if (policy.getTier() == 2 && policy.getPenalty() != null) {
                    penaltyCharges.add(Map.of(
                            "zoneName", zone.getId(),
                            "rate", policy.getPenalty()));
                }
            }
        }
        p.put("penaltyCharges", penaltyCharges);

        return p;
    }

    private static Map<String, Object> buildSankey(Map<String, Integer> flows) {
        List<Map<String, Object>> flowList = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : flows.entrySet()) {
            String[] parts = entry.getKey().split("→");
            Map<String, Object> flow = new LinkedHashMap<>();
            flow.put("from", parts[0]);
            flow.put("to", parts[1]);
            flow.put("count", entry.getValue());
            flowList.add(flow);
        }

        Map<String, Object> sankey = new LinkedHashMap<>();
        sankey.put("nodes", List.of(MODES));
        sankey.put("flows", flowList);
        return sankey;
    }

    private static Map<String, Object> buildBar(int[] baselineCounts, int[] policyCounts) {
        int baselineTotal = 0;
        int policyTotal = 0;
        for (int c : baselineCounts) baselineTotal += c;
        for (int c : policyCounts) policyTotal += c;

        List<Double> baselinePcts = new ArrayList<>();
        List<Double> policyPcts = new ArrayList<>();
        for (int i = 0; i < MODES.length; i++) {
            baselinePcts.add(pct(baselineCounts[i], baselineTotal));
            policyPcts.add(pct(policyCounts[i], policyTotal));
        }

        Map<String, Object> bar = new LinkedHashMap<>();
        bar.put("modes", List.of(MODES));
        bar.put("baseline", baselinePcts);
        bar.put("policy", policyPcts);
        return bar;
    }

    private static Map<String, Map<String, List<Map<String, Object>>>> initMapData() {
        Map<String, Map<String, List<Map<String, Object>>>> map = new LinkedHashMap<>();
        for (String dir : new String[]{"origin", "destination"}) {
            Map<String, List<Map<String, Object>>> cats = new LinkedHashMap<>();
            for (String cat : MAP_CATEGORIES) {
                cats.put(cat, new ArrayList<>());
            }
            map.put(dir, cats);
        }
        return map;
    }

    private static void addMapPoints(
            Map<String, Map<String, List<Map<String, Object>>>> mapData,
            TripRow trip, int category, CoordinateTransformation toWgs84) {
        if (category == NO_CHANGE) return;
        String catKey = MAP_CATEGORIES[category];

        Coord originWgs = toWgs84.transform(new Coord(trip.startX(), trip.startY()));
        mapData.get("origin").get(catKey).add(
                Map.of("position", List.of(originWgs.getX(), originWgs.getY()), "weight", 1));

        Coord destWgs = toWgs84.transform(new Coord(trip.endX(), trip.endY()));
        mapData.get("destination").get(catKey).add(
                Map.of("position", List.of(destWgs.getX(), destWgs.getY()), "weight", 1));
    }

    private static Map<String, List<MoneyRecord>> indexMoneyEvents(
            PersonMoneyEventCollector collector) {
        Map<String, List<MoneyRecord>> indexed = new HashMap<>();
        if (collector == null) return indexed;

        for (Map.Entry<Id<Person>, List<MoneyRecord>> entry : collector.getEvents().entrySet()) {
            String personId = entry.getKey().toString();
            for (MoneyRecord mr : entry.getValue()) {
                String key = personId + "_" + mr.reference();
                indexed.computeIfAbsent(key, k -> new ArrayList<>()).add(mr);
            }
        }
        return indexed;
    }

    private static Map<String, TripRow> parseTrips(Path file) {
        Map<String, TripRow> trips = new HashMap<>();
        try (BufferedReader reader = OverviewExtractor.gzipReader(file)) {
            String header = reader.readLine();
            int iPerson = OverviewExtractor.columnIndex(header, "person");
            int iTripNum = OverviewExtractor.columnIndex(header, "trip_number");
            int iMode = OverviewExtractor.columnIndex(header, "main_mode");
            int iDist = OverviewExtractor.columnIndex(header, "traveled_distance");
            int iSx = OverviewExtractor.columnIndex(header, "start_x");
            int iSy = OverviewExtractor.columnIndex(header, "start_y");
            int iEx = OverviewExtractor.columnIndex(header, "end_x");
            int iEy = OverviewExtractor.columnIndex(header, "end_y");

            String line;
            while ((line = reader.readLine()) != null) {
                String[] f = line.split(";", -1);
                String person = f[iPerson];
                int tripNum = Integer.parseInt(f[iTripNum]);
                String key = person + "_" + tripNum;
                trips.put(key, new TripRow(person, tripNum, f[iMode],
                        Double.parseDouble(f[iDist]),
                        Double.parseDouble(f[iSx]), Double.parseDouble(f[iSy]),
                        Double.parseDouble(f[iEx]), Double.parseDouble(f[iEy])));
            }
        } catch (IOException e) {
            throw new RuntimeException(L.msg("output.failed") + ": " + e.getMessage(), e);
        }
        return trips;
    }

    private static Map<String, List<LegRow>> parseLegs(Path file) {
        Map<String, List<LegRow>> legs = new HashMap<>();
        try (BufferedReader reader = OverviewExtractor.gzipReader(file)) {
            String header = reader.readLine();
            int iPerson = OverviewExtractor.columnIndex(header, "person");
            int iTripId = OverviewExtractor.columnIndex(header, "trip_id");
            int iMode = OverviewExtractor.columnIndex(header, "mode");
            int iVehicle = OverviewExtractor.columnIndex(header, "vehicle_id");
            int iDist = OverviewExtractor.columnIndex(header, "distance");

            String line;
            while ((line = reader.readLine()) != null) {
                String[] f = line.split(";", -1);
                LegRow leg = new LegRow(f[iPerson], f[iTripId], f[iMode],
                        f[iVehicle], Double.parseDouble(f[iDist]));
                legs.computeIfAbsent(f[iTripId], k -> new ArrayList<>()).add(leg);
            }
        } catch (IOException e) {
            throw new RuntimeException(L.msg("output.failed") + ": " + e.getMessage(), e);
        }
        return legs;
    }

    private static double pct(int count, int total) {
        if (total == 0) return 0.0;
        return (count / (double) total) * 100.0;
    }
}
