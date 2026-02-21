package ez.backend.turbo.output;

import ez.backend.turbo.database.TripLegRepository.TripLegRecord;
import ez.backend.turbo.endpoints.SimulationRequest;
import ez.backend.turbo.simulation.LegEmissionTracker;
import ez.backend.turbo.simulation.LegEmissionTracker.LegEmission;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ResponseAnalyzer {

    public record ResponseConfig(
            boolean excludeNonCarAgents,
            double rerouteThresholdMeters,
            String multiModalPt,
            double subwayFactorGpkm,
            String tripLegsScope,
            String targetCrs) {}

    public record ResponseResult(
            Map<String, Object> paragraph1,
            Map<String, Object> paragraph2,
            Map<String, Object> breakdownChart,
            Map<String, Object> timeImpactChart,
            Map<String, Map<String, List<Map<String, Object>>>> peopleResponseMap,
            List<TripLegRecord> tripLegRecords,
            List<Map<String, Object>> tripLegsMap) {}

    private static final int PAID_PENALTY = 0;
    private static final int REROUTED = 1;
    private static final int BUS = 2;
    private static final int SUBWAY = 3;
    private static final int WALK = 4;
    private static final int BIKE = 5;
    private static final int SWITCHED_TO_CAR = 6;
    private static final int CANCELLED = 7;
    private static final int CATEGORY_COUNT = 8;

    private static final String[] IMPACT_LABELS = {
            "Paid Penalty", "Rerouted", "Car \u2192 Bus", "Car \u2192 Subway",
            "Car \u2192 Walking", "Car \u2192 Biking", "Switched to Car", "Cancelled Trip"
    };

    private static final String[] MAP_KEYS = {
            "paidPenalty", "rerouted", "switchedToBus", "switchedToSubway",
            "switchedToWalking", "switchedToBiking", "switchedToCar", "cancelledTrip"
    };

    private record TripRow(String person, int tripNumber, String mainMode,
                           double traveledDistance, double travTimeMinutes,
                           double startX, double startY, double endX, double endY,
                           String startActivityType, String endActivityType) {}

    private record LegRow(String person, String tripId, String mode, String vehicleId,
                          double distance, double travTimeMinutes,
                          double startX, double startY, double endX, double endY) {}

    public static ResponseResult analyze(
            Path baselineDir, Path policyDir,
            SimulationRequest request,
            LegEmissionTracker baselineTracker, LegEmissionTracker policyTracker,
            PersonMoneyEventCollector moneyCollector,
            ResponseConfig config) {

        Map<String, TripRow> baselineTrips = parseTrips(baselineDir.resolve("output_trips.csv.gz"));
        Map<String, TripRow> policyTrips = parseTrips(policyDir.resolve("output_trips.csv.gz"));
        Map<String, List<LegRow>> policyLegs = parseLegs(policyDir.resolve("output_legs.csv.gz"));

        Set<String> eligiblePersons = null;
        if (config.excludeNonCarAgents()) {
            eligiblePersons = new HashSet<>();
            for (TripRow trip : baselineTrips.values()) {
                if ("car".equals(trip.mainMode())) {
                    eligiblePersons.add(trip.person());
                }
            }
        }

        Map<String, List<MoneyRecord>> moneyByKey = indexMoneyEvents(moneyCollector);

        int[] categoryCounts = new int[CATEGORY_COUNT];
        double[] timeSums = new double[CATEGORY_COUNT];
        int[] timeCounts = new int[CATEGORY_COUNT];
        int totalAffectedTrips = 0;

        CoordinateTransformation toWgs84 =
                TransformationFactory.getCoordinateTransformation(config.targetCrs(), "EPSG:4326");

        Map<String, Map<String, List<Map<String, Object>>>> prMap = initPeopleResponseMap();
        Map<String, Integer> tripClassifications = new HashMap<>();
        Set<String> affectedPersons = new HashSet<>();

        for (Map.Entry<String, TripRow> entry : baselineTrips.entrySet()) {
            String tripKey = entry.getKey();
            TripRow baseTrip = entry.getValue();

            if (eligiblePersons != null && !eligiblePersons.contains(baseTrip.person())) {
                continue;
            }

            TripRow polTrip = policyTrips.get(tripKey);
            int category = classify(baseTrip, polTrip, tripKey, policyLegs,
                    moneyByKey, config);

            if (category < 0) continue;

            tripClassifications.put(tripKey, category);
            affectedPersons.add(baseTrip.person());
            categoryCounts[category]++;
            totalAffectedTrips++;

            if (category != CANCELLED && polTrip != null) {
                double timeDelta = polTrip.travTimeMinutes() - baseTrip.travTimeMinutes();
                timeSums[category] += timeDelta;
                timeCounts[category]++;
            }

            addMapPoint(prMap, "origin", MAP_KEYS[category],
                    baseTrip.startX(), baseTrip.startY(), toWgs84);
            addMapPoint(prMap, "destination", MAP_KEYS[category],
                    baseTrip.endX(), baseTrip.endY(), toWgs84);

            // Multi-modal PT: count both bus and subway when config is "all"
            if ("all".equals(config.multiModalPt()) && (category == BUS || category == SUBWAY)) {
                int secondary = hasSecondaryTransitMode(tripKey, policyLegs, category);
                if (secondary >= 0) {
                    categoryCounts[secondary]++;
                    addMapPoint(prMap, "origin", MAP_KEYS[secondary],
                            baseTrip.startX(), baseTrip.startY(), toWgs84);
                    addMapPoint(prMap, "destination", MAP_KEYS[secondary],
                            baseTrip.endX(), baseTrip.endY(), toWgs84);
                }
            }
        }

        Map<String, Object> paragraph1 = buildParagraph1(
                categoryCounts, totalAffectedTrips, request);
        Map<String, Object> paragraph2 = buildParagraph2(timeSums, timeCounts);
        Map<String, Object> breakdownChart = buildBreakdownChart(
                categoryCounts, totalAffectedTrips);
        Map<String, Object> timeImpactChart = buildTimeImpactChart(timeSums, timeCounts);

        List<TripLegRecord> tripLegRecords = buildTripLegRecords(
                baselineTrips, policyTrips, policyLegs,
                baselineTracker, policyTracker,
                tripClassifications, affectedPersons, config);

        Map<String, List<LegRow>> legsByPerson = buildLegsByPerson(policyLegs);
        List<Map<String, Object>> tripLegsMapData = buildTripLegsMap(
                tripLegRecords, legsByPerson, toWgs84);

        return new ResponseResult(paragraph1, paragraph2, breakdownChart,
                timeImpactChart, prMap, tripLegRecords, tripLegsMapData);
    }

    private static int classify(TripRow baseTrip, TripRow polTrip, String tripKey,
                                Map<String, List<LegRow>> policyLegs,
                                Map<String, List<MoneyRecord>> moneyByKey,
                                ResponseConfig config) {
        if (polTrip == null) return CANCELLED;

        String baseMode = baseTrip.mainMode();
        String polMode = polTrip.mainMode();

        if ("car".equals(baseMode) && !"car".equals(polMode)) {
            if ("pt".equals(polMode)) {
                return classifyPtSwitch(tripKey, policyLegs, config);
            }
            if ("walk".equals(polMode)) return WALK;
            if ("bike".equals(polMode)) return BIKE;
            return WALK;
        }

        if (!"car".equals(baseMode) && "car".equals(polMode)) {
            return SWITCHED_TO_CAR;
        }

        if ("car".equals(baseMode) && "car".equals(polMode)) {
            double distDelta = Math.abs(polTrip.traveledDistance() - baseTrip.traveledDistance());
            if (distDelta > config.rerouteThresholdMeters()) return REROUTED;

            String moneyKey = baseTrip.person() + "_" + (baseTrip.tripNumber() - 1);
            List<MoneyRecord> events = moneyByKey.get(moneyKey);
            if (events != null) {
                for (MoneyRecord mr : events) {
                    if (mr.amount() > -10000) return PAID_PENALTY;
                }
            }
        }

        return -1;
    }

    private static int classifyPtSwitch(String tripKey,
                                        Map<String, List<LegRow>> policyLegs,
                                        ResponseConfig config) {
        List<LegRow> legs = policyLegs.get(tripKey);
        if (legs == null || legs.isEmpty()) return BUS;

        boolean hasBus = false;
        boolean hasSubway = false;
        double longestBusDist = 0;
        double longestSubwayDist = 0;
        int firstTransit = -1;

        for (int i = 0; i < legs.size(); i++) {
            LegRow leg = legs.get(i);
            String vid = leg.vehicleId();
            if (vid == null || vid.isEmpty()) continue;

            if (vid.startsWith("subway_")) {
                hasSubway = true;
                if (leg.distance() > longestSubwayDist) longestSubwayDist = leg.distance();
                if (firstTransit < 0) firstTransit = SUBWAY;
            } else if (vid.startsWith("bus_")) {
                hasBus = true;
                if (leg.distance() > longestBusDist) longestBusDist = leg.distance();
                if (firstTransit < 0) firstTransit = BUS;
            }
        }

        if (!hasBus && !hasSubway) return BUS;

        return switch (config.multiModalPt()) {
            case "longest" -> longestSubwayDist > longestBusDist ? SUBWAY : BUS;
            case "first" -> firstTransit >= 0 ? firstTransit : BUS;
            default -> hasSubway ? SUBWAY : BUS;
        };
    }

    private static int hasSecondaryTransitMode(String tripKey,
                                                  Map<String, List<LegRow>> policyLegs,
                                                  int primaryCategory) {
        List<LegRow> legs = policyLegs.get(tripKey);
        if (legs == null) return -1;

        for (LegRow leg : legs) {
            String vid = leg.vehicleId();
            if (vid == null || vid.isEmpty()) continue;
            if (primaryCategory == BUS && vid.startsWith("subway_")) return SUBWAY;
            if (primaryCategory == SUBWAY && vid.startsWith("bus_")) return BUS;
        }
        return -1;
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

    private static Map<String, Object> buildParagraph1(
            int[] counts, int total, SimulationRequest request) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("paidPenaltyPct", pct(counts[PAID_PENALTY], total));
        p.put("reroutedPct", pct(counts[REROUTED], total));
        p.put("busPct", pct(counts[BUS], total));
        p.put("subwayPct", pct(counts[SUBWAY], total));
        p.put("walkPct", pct(counts[WALK], total));
        p.put("bikePct", pct(counts[BIKE], total));
        p.put("carPct", pct(counts[SWITCHED_TO_CAR], total));
        p.put("cancelledPct", pct(counts[CANCELLED], total));

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
        p.put("totalAffectedTrips", total);
        return p;
    }

    private static Map<String, Object> buildParagraph2(double[] timeSums, int[] timeCounts) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("avgPenaltyTime", avg(timeSums[PAID_PENALTY], timeCounts[PAID_PENALTY]));
        p.put("avgRerouteTime", avg(timeSums[REROUTED], timeCounts[REROUTED]));
        p.put("avgBusTime", avg(timeSums[BUS], timeCounts[BUS]));
        p.put("avgSubwayTime", avg(timeSums[SUBWAY], timeCounts[SUBWAY]));
        p.put("avgWalkTime", avg(timeSums[WALK], timeCounts[WALK]));
        p.put("avgBikeTime", avg(timeSums[BIKE], timeCounts[BIKE]));
        p.put("avgCarSwitchTime", avg(timeSums[SWITCHED_TO_CAR], timeCounts[SWITCHED_TO_CAR]));
        return p;
    }

    private static Map<String, Object> buildBreakdownChart(int[] counts, int total) {
        return Map.of("data", List.of(
                pct(counts[PAID_PENALTY], total), pct(counts[REROUTED], total),
                pct(counts[BUS], total), pct(counts[SUBWAY], total),
                pct(counts[WALK], total), pct(counts[BIKE], total),
                pct(counts[SWITCHED_TO_CAR], total), pct(counts[CANCELLED], total)));
    }

    private static Map<String, Object> buildTimeImpactChart(double[] timeSums, int[] timeCounts) {
        return Map.of("data", List.of(
                avg(timeSums[PAID_PENALTY], timeCounts[PAID_PENALTY]),
                avg(timeSums[REROUTED], timeCounts[REROUTED]),
                avg(timeSums[BUS], timeCounts[BUS]),
                avg(timeSums[SUBWAY], timeCounts[SUBWAY]),
                avg(timeSums[WALK], timeCounts[WALK]),
                avg(timeSums[BIKE], timeCounts[BIKE]),
                avg(timeSums[SWITCHED_TO_CAR], timeCounts[SWITCHED_TO_CAR])));
    }

    private static List<TripLegRecord> buildTripLegRecords(
            Map<String, TripRow> baselineTrips, Map<String, TripRow> policyTrips,
            Map<String, List<LegRow>> policyLegs,
            LegEmissionTracker baselineTracker, LegEmissionTracker policyTracker,
            Map<String, Integer> tripClassifications, Set<String> affectedPersons,
            ResponseConfig config) {

        Map<Id<Person>, List<LegEmission>> baselineEmissions = baselineTracker.getPersonLegs();
        Map<Id<Person>, List<LegEmission>> policyEmissions = policyTracker.getPersonLegs();

        Map<String, List<LegRow>> legsByPerson = buildLegsByPerson(policyLegs);

        List<TripLegRecord> records = new ArrayList<>();
        Set<String> processedPersons = new HashSet<>();

        for (Map.Entry<String, Integer> classEntry : tripClassifications.entrySet()) {
            String tripKey = classEntry.getKey();
            TripRow baseTrip = baselineTrips.get(tripKey);
            String personId = baseTrip.person();

            if (processedPersons.contains(personId)) continue;
            processedPersons.add(personId);

            boolean personAffected = affectedPersons.contains(personId);
            if ("affected".equals(config.tripLegsScope()) && !personAffected) continue;

            Id<Person> pid = Id.createPersonId(personId);
            List<LegEmission> baseLegList = baselineEmissions.getOrDefault(pid, List.of());
            List<LegEmission> polLegList = policyEmissions.getOrDefault(pid, List.of());
            List<LegRow> personLegRows = legsByPerson.getOrDefault(personId, List.of());

            int legCount = Math.min(baseLegList.size(), polLegList.size());
            if ("all".equals(config.tripLegsScope())) {
                legCount = Math.max(baseLegList.size(), polLegList.size());
            }

            for (int i = 0; i < legCount; i++) {
                LegEmission baseLeg = i < baseLegList.size() ? baseLegList.get(i) : null;
                LegEmission polLeg = i < polLegList.size() ? polLegList.get(i) : null;
                LegRow legRow = i < personLegRows.size() ? personLegRows.get(i) : null;

                double baseCo2 = baseLeg != null ? baseLeg.co2() : 0;
                double polCo2 = polLeg != null ? polLeg.co2() : 0;

                if (legRow != null && legRow.vehicleId() != null
                        && legRow.vehicleId().startsWith("subway_")) {
                    polCo2 = config.subwayFactorGpkm() * (legRow.distance() / 1000.0);
                }

                double baseTime = baseLeg != null ? (baseLeg.endTime() - baseLeg.startTime()) / 60.0 : 0;
                double polTime = polLeg != null ? (polLeg.endTime() - polLeg.startTime()) / 60.0 : 0;

                double co2Delta = polCo2 - baseCo2;
                double timeDelta = polTime - baseTime;

                if ("changed".equals(config.tripLegsScope()) && co2Delta == 0 && timeDelta == 0) {
                    continue;
                }

                String tripId = legRow != null ? legRow.tripId() : null;
                String impact = findImpactForLeg(tripId, tripClassifications);
                TripRow parentTrip = tripId != null ? policyTrips.get(tripId) : null;
                String originActivity = parentTrip != null ? parentTrip.startActivityType() : "unknown";
                String destActivity = parentTrip != null ? parentTrip.endActivityType() : "unknown";

                records.add(new TripLegRecord(
                        personId + "_" + i,
                        personId,
                        originActivity,
                        destActivity,
                        BigDecimal.valueOf(co2Delta).setScale(2, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(timeDelta).setScale(2, RoundingMode.HALF_UP),
                        impact));
            }
        }

        return records;
    }

    private static String findImpactForLeg(String tripId,
                                           Map<String, Integer> tripClassifications) {
        if (tripId == null) return "No Change";
        Integer category = tripClassifications.get(tripId);
        return category != null ? IMPACT_LABELS[category] : "No Change";
    }

    private static Map<String, List<LegRow>> buildLegsByPerson(
            Map<String, List<LegRow>> policyLegs) {
        Map<String, List<LegRow>> byPerson = new HashMap<>();
        for (List<LegRow> legs : policyLegs.values()) {
            for (LegRow leg : legs) {
                byPerson.computeIfAbsent(leg.person(), k -> new ArrayList<>()).add(leg);
            }
        }
        return byPerson;
    }

    private static List<Map<String, Object>> buildTripLegsMap(
            List<TripLegRecord> records,
            Map<String, List<LegRow>> legsByPerson,
            CoordinateTransformation toWgs84) {

        List<Map<String, Object>> mapEntries = new ArrayList<>();
        for (TripLegRecord rec : records) {
            String personId = rec.personId();
            String legId = rec.legId();
            int legIndex = Integer.parseInt(legId.substring(legId.lastIndexOf('_') + 1));

            List<LegRow> personLegList = legsByPerson.get(personId);
            if (personLegList == null || legIndex >= personLegList.size()) continue;

            LegRow leg = personLegList.get(legIndex);
            Coord start = toWgs84.transform(new Coord(leg.startX(), leg.startY()));
            Coord end = toWgs84.transform(new Coord(leg.endX(), leg.endY()));

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", legId);
            entry.put("path", List.of(
                    List.of(start.getX(), start.getY()),
                    List.of(end.getX(), end.getY())));
            entry.put("co2Delta", rec.co2DeltaGrams().doubleValue());
            entry.put("timeDelta", rec.timeDeltaMinutes().doubleValue());
            entry.put("impact", rec.impact());
            mapEntries.add(entry);
        }
        return mapEntries;
    }

    public static Map<String, Object> toSseRecord(TripLegRecord rec) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("legId", rec.legId());
        m.put("personId", rec.personId());
        m.put("originActivity", rec.originActivityType());
        m.put("destinationActivity", rec.destinationActivityType());
        m.put("co2DeltaGrams", rec.co2DeltaGrams());
        m.put("timeDeltaMinutes", rec.timeDeltaMinutes());
        m.put("impact", rec.impact());
        return m;
    }

    private static Map<String, Map<String, List<Map<String, Object>>>> initPeopleResponseMap() {
        Map<String, Map<String, List<Map<String, Object>>>> map = new LinkedHashMap<>();
        for (String dir : new String[]{"origin", "destination"}) {
            Map<String, List<Map<String, Object>>> cats = new LinkedHashMap<>();
            for (String key : MAP_KEYS) {
                cats.put(key, new ArrayList<>());
            }
            map.put(dir, cats);
        }
        return map;
    }

    private static void addMapPoint(
            Map<String, Map<String, List<Map<String, Object>>>> prMap,
            String direction, String category,
            double x, double y, CoordinateTransformation toWgs84) {
        Coord wgs = toWgs84.transform(new Coord(x, y));
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("position", List.of(wgs.getX(), wgs.getY()));
        point.put("weight", 1);
        prMap.get(direction).get(category).add(point);
    }

    private static Map<String, TripRow> parseTrips(Path file) {
        Map<String, TripRow> trips = new HashMap<>();
        try (BufferedReader reader = OverviewExtractor.gzipReader(file)) {
            String header = reader.readLine();
            int iPerson = OverviewExtractor.columnIndex(header, "person");
            int iTripNum = OverviewExtractor.columnIndex(header, "trip_number");
            int iMode = OverviewExtractor.columnIndex(header, "main_mode");
            int iDist = OverviewExtractor.columnIndex(header, "traveled_distance");
            int iTime = OverviewExtractor.columnIndex(header, "trav_time");
            int iSx = OverviewExtractor.columnIndex(header, "start_x");
            int iSy = OverviewExtractor.columnIndex(header, "start_y");
            int iEx = OverviewExtractor.columnIndex(header, "end_x");
            int iEy = OverviewExtractor.columnIndex(header, "end_y");
            int iSact = OverviewExtractor.columnIndex(header, "start_activity_type");
            int iEact = OverviewExtractor.columnIndex(header, "end_activity_type");

            String line;
            while ((line = reader.readLine()) != null) {
                String[] f = line.split(";", -1);
                String person = f[iPerson];
                int tripNum = Integer.parseInt(f[iTripNum]);
                String key = person + "_" + tripNum;
                trips.put(key, new TripRow(person, tripNum, f[iMode],
                        Double.parseDouble(f[iDist]), parseTravTime(f[iTime]),
                        Double.parseDouble(f[iSx]), Double.parseDouble(f[iSy]),
                        Double.parseDouble(f[iEx]), Double.parseDouble(f[iEy]),
                        f[iSact], f[iEact]));
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
            int iTime = OverviewExtractor.columnIndex(header, "trav_time");
            int iSx = OverviewExtractor.columnIndex(header, "start_x");
            int iSy = OverviewExtractor.columnIndex(header, "start_y");
            int iEx = OverviewExtractor.columnIndex(header, "end_x");
            int iEy = OverviewExtractor.columnIndex(header, "end_y");

            String line;
            while ((line = reader.readLine()) != null) {
                String[] f = line.split(";", -1);
                LegRow leg = new LegRow(f[iPerson], f[iTripId], f[iMode], f[iVehicle],
                        Double.parseDouble(f[iDist]), parseTravTime(f[iTime]),
                        Double.parseDouble(f[iSx]), Double.parseDouble(f[iSy]),
                        Double.parseDouble(f[iEx]), Double.parseDouble(f[iEy]));
                legs.computeIfAbsent(f[iTripId], k -> new ArrayList<>()).add(leg);
            }
        } catch (IOException e) {
            throw new RuntimeException(L.msg("output.failed") + ": " + e.getMessage(), e);
        }
        return legs;
    }

    private static double parseTravTime(String travTime) {
        String[] parts = travTime.split(":");
        return Integer.parseInt(parts[0]) * 60
                + Integer.parseInt(parts[1])
                + Integer.parseInt(parts[2]) / 60.0;
    }

    private static double pct(int count, int total) {
        if (total == 0) return 0.0;
        return (count / (double) total) * 100.0;
    }

    private static double avg(double sum, int count) {
        if (count == 0) return 0.0;
        return sum / count;
    }
}
