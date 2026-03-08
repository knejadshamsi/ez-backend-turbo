package ez.backend.turbo.output;

import ez.backend.turbo.database.TripLegRepository.TripLegRecord;
import ez.backend.turbo.simulation.LegEmissionTracker;
import ez.backend.turbo.simulation.LegEmissionTracker.LegEmission;
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

public class SectionThreeAnalyzer {

    private static final String NC = "NC";

    public record SectionThreeResult(
            Map<String, Object> paragraph,
            List<TripLegRecord> tripRecords,
            Map<String, Object> mapData
    ) {}

    private record TripRow(String person, int tripNumber, String mainMode,
                           double traveledDistance, double travTimeMinutes,
                           double startX, double startY, double endX, double endY,
                           String startActivityType, String endActivityType) {}

    private record LegRow(String person, String tripId, String mode,
                          String vehicleId, double distance, double travTimeMinutes,
                          double startX, double startY, double endX, double endY) {}

    public static SectionThreeResult analyze(
            Path baselineDir, Path policyDir,
            LegEmissionTracker baselineTracker, LegEmissionTracker policyTracker,
            double subwayFactorGpkm, String multiModalPt, String targetCrs) {

        Map<String, TripRow> baselineTrips = parseTrips(baselineDir.resolve("output_trips.csv.gz"));
        Map<String, TripRow> policyTrips = parseTrips(policyDir.resolve("output_trips.csv.gz"));
        Map<String, List<LegRow>> baselineLegs = parseLegs(baselineDir.resolve("output_legs.csv.gz"));
        Map<String, List<LegRow>> policyLegs = parseLegs(policyDir.resolve("output_legs.csv.gz"));

        Map<Id<Person>, List<LegEmission>> baselineEmissions = baselineTracker.getPersonLegs();
        Map<Id<Person>, List<LegEmission>> policyEmissions = policyTracker.getPersonLegs();

        Map<String, int[]> baselineLegRanges = buildLegRanges(baselineLegs);
        Map<String, int[]> policyLegRanges = buildLegRanges(policyLegs);

        CoordinateTransformation toWgs84 =
                TransformationFactory.getCoordinateTransformation(targetCrs, "EPSG:4326");

        List<TripLegRecord> records = new ArrayList<>();
        Map<String, Object> mapEntries = new LinkedHashMap<>();

        Set<String> allTripKeys = new HashSet<>(baselineTrips.keySet());
        allTripKeys.addAll(policyTrips.keySet());

        for (String tripKey : allTripKeys) {
            TripRow baseTrip = baselineTrips.get(tripKey);
            TripRow polTrip = policyTrips.get(tripKey);

            String baseMode = baseTrip != null
                    ? resolveMode(baseTrip.mainMode(), tripKey, baselineLegs, multiModalPt) : null;
            String polMode = polTrip != null
                    ? resolveMode(polTrip.mainMode(), tripKey, policyLegs, multiModalPt) : null;

            double baseCo2 = baseTrip != null
                    ? sumTripCo2(baseTrip.person(), tripKey, baselineLegRanges,
                        baselineEmissions, baselineLegs, subwayFactorGpkm) : 0;
            double polCo2 = polTrip != null
                    ? sumTripCo2(polTrip.person(), tripKey, policyLegRanges,
                        policyEmissions, policyLegs, subwayFactorGpkm) : 0;

            double baseTime = baseTrip != null ? baseTrip.travTimeMinutes() : 0;
            double polTime = polTrip != null ? polTrip.travTimeMinutes() : 0;

            double co2Delta = polCo2 - baseCo2;
            double timeDelta = polTime - baseTime;

            String impact;
            if (baseTrip == null) {
                impact = "\u2192 " + polMode;
            } else if (polTrip == null) {
                impact = baseMode + " \u2192";
            } else if (baseMode.equals(polMode) && Math.abs(co2Delta) < 0.01 && Math.abs(timeDelta) < 0.01) {
                impact = NC;
            } else if (!baseMode.equals(polMode)) {
                impact = baseMode + " \u2192 " + polMode;
            } else {
                impact = baseMode + " \u2192 " + polMode;
            }

            TripRow refTrip = polTrip != null ? polTrip : baseTrip;
            String personId = refTrip.person();

            records.add(new TripLegRecord(
                    tripKey,
                    personId,
                    refTrip.startActivityType(),
                    refTrip.endActivityType(),
                    BigDecimal.valueOf(co2Delta).setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(timeDelta).setScale(2, RoundingMode.HALF_UP),
                    impact));

            Map<String, Object> tripMapEntry = new LinkedHashMap<>();
            if (baseTrip != null) {
                tripMapEntry.put("baseline", buildLegArcs(tripKey, baselineLegs, toWgs84));
            }
            if (polTrip != null) {
                tripMapEntry.put("policy", buildLegArcs(tripKey, policyLegs, toWgs84));
            }
            mapEntries.put(tripKey, tripMapEntry);
        }

        Map<String, Object> paragraph = buildParagraph(records);
        return new SectionThreeResult(paragraph, records, mapEntries);
    }

    private static Map<String, int[]> buildLegRanges(Map<String, List<LegRow>> legsByTrip) {
        Map<String, List<String>> tripsByPerson = new LinkedHashMap<>();
        for (Map.Entry<String, List<LegRow>> entry : legsByTrip.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            String person = entry.getValue().get(0).person();
            tripsByPerson.computeIfAbsent(person, k -> new ArrayList<>()).add(entry.getKey());
        }

        Map<String, int[]> ranges = new HashMap<>();
        for (Map.Entry<String, List<String>> personEntry : tripsByPerson.entrySet()) {
            List<String> tripIds = personEntry.getValue();
            int offset = 0;
            for (String tripId : tripIds) {
                int legCount = legsByTrip.get(tripId).size();
                ranges.put(tripId, new int[]{offset, offset + legCount});
                offset += legCount;
            }
        }
        return ranges;
    }

    private static double sumTripCo2(String personId, String tripKey,
                                      Map<String, int[]> legRanges,
                                      Map<Id<Person>, List<LegEmission>> emissions,
                                      Map<String, List<LegRow>> legsByTrip,
                                      double subwayFactorGpkm) {
        int[] range = legRanges.get(tripKey);
        if (range == null) return 0;

        Id<Person> pid = Id.createPersonId(personId);
        List<LegEmission> personLegs = emissions.getOrDefault(pid, List.of());
        List<LegRow> tripLegRows = legsByTrip.getOrDefault(tripKey, List.of());

        double total = 0;
        for (int i = range[0]; i < range[1]; i++) {
            int legIdx = i - range[0];
            LegRow legRow = legIdx < tripLegRows.size() ? tripLegRows.get(legIdx) : null;

            if (legRow != null && legRow.vehicleId() != null
                    && legRow.vehicleId().startsWith("subway_")) {
                total += subwayFactorGpkm * (legRow.distance() / 1000.0);
                continue;
            }

            if (i < personLegs.size()) {
                total += personLegs.get(i).co2();
            }
        }
        return total;
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

    private static List<Map<String, Object>> buildLegArcs(
            String tripKey, Map<String, List<LegRow>> legsByTrip,
            CoordinateTransformation toWgs84) {
        List<LegRow> legs = legsByTrip.get(tripKey);
        if (legs == null || legs.isEmpty()) return List.of();

        List<Map<String, Object>> arcs = new ArrayList<>();
        for (LegRow leg : legs) {
            Coord from = toWgs84.transform(new Coord(leg.startX(), leg.startY()));
            Coord to = toWgs84.transform(new Coord(leg.endX(), leg.endY()));
            Map<String, Object> arc = new LinkedHashMap<>();
            arc.put("from", List.of(from.getX(), from.getY()));
            arc.put("to", List.of(to.getX(), to.getY()));
            arc.put("mode", leg.mode());
            arcs.add(arc);
        }
        return arcs;
    }

    private static Map<String, Object> buildParagraph(List<TripLegRecord> records) {
        int totalTrips = records.size();
        int changedTrips = 0;
        int unchangedTrips = 0;
        int cancelledTrips = 0;
        int newTrips = 0;
        int modeShiftTrips = 0;

        double netCo2 = 0;
        double netTime = 0;
        double changedCo2Sum = 0;
        double changedTimeSum = 0;

        int improvedCo2 = 0;
        int worsenedCo2 = 0;
        int improvedTime = 0;
        int worsenedTime = 0;
        int winWin = 0;
        int loseLose = 0;
        int envWinPersonalCost = 0;
        int personalWinEnvCost = 0;

        for (TripLegRecord rec : records) {
            double co2 = rec.co2DeltaGrams().doubleValue();
            double time = rec.timeDeltaMinutes().doubleValue();
            netCo2 += co2;
            netTime += time;

            if (NC.equals(rec.impact())) {
                unchangedTrips++;
                continue;
            }

            changedTrips++;
            changedCo2Sum += co2;
            changedTimeSum += time;

            String impact = rec.impact();
            if (impact.startsWith("\u2192 ")) {
                newTrips++;
            } else if (impact.endsWith(" \u2192")) {
                cancelledTrips++;
            } else {
                modeShiftTrips++;
            }

            if (co2 < 0) improvedCo2++;
            if (co2 > 0) worsenedCo2++;
            if (time < 0) improvedTime++;
            if (time > 0) worsenedTime++;

            if (co2 < 0 && time < 0) winWin++;
            else if (co2 > 0 && time > 0) loseLose++;
            else if (co2 < 0 && time > 0) envWinPersonalCost++;
            else if (co2 > 0 && time < 0) personalWinEnvCost++;
        }

        String dominant = "none";
        int maxQuadrant = 0;
        if (winWin > maxQuadrant) { maxQuadrant = winWin; dominant = "winWin"; }
        if (loseLose > maxQuadrant) { maxQuadrant = loseLose; dominant = "loseLose"; }
        if (envWinPersonalCost > maxQuadrant) { maxQuadrant = envWinPersonalCost; dominant = "envWinPersonalCost"; }
        if (personalWinEnvCost > maxQuadrant) { maxQuadrant = personalWinEnvCost; dominant = "personalWinEnvCost"; }

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("totalTrips", totalTrips);
        p.put("changedTrips", changedTrips);
        p.put("unchangedTrips", unchangedTrips);
        p.put("cancelledTrips", cancelledTrips);
        p.put("newTrips", newTrips);
        p.put("modeShiftTrips", modeShiftTrips);
        p.put("netCo2DeltaGrams", round(netCo2));
        p.put("netTimeDeltaMinutes", round(netTime));
        p.put("avgCo2DeltaGrams", changedTrips > 0 ? round(changedCo2Sum / changedTrips) : 0.0);
        p.put("avgTimeDeltaMinutes", changedTrips > 0 ? round(changedTimeSum / changedTrips) : 0.0);
        p.put("improvedCo2Count", improvedCo2);
        p.put("worsenedCo2Count", worsenedCo2);
        p.put("improvedTimeCount", improvedTime);
        p.put("worsenedTimeCount", worsenedTime);
        p.put("winWinCount", winWin);
        p.put("loseLoseCount", loseLose);
        p.put("envWinPersonalCostCount", envWinPersonalCost);
        p.put("personalWinEnvCostCount", personalWinEnvCost);
        p.put("dominantOutcome", dominant);
        return p;
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

    private static double round(double val) {
        return BigDecimal.valueOf(val).setScale(2, RoundingMode.HALF_UP).doubleValue();
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
        Map<String, List<LegRow>> legs = new LinkedHashMap<>();
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
}
