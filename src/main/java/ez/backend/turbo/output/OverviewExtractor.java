package ez.backend.turbo.output;

import ez.backend.turbo.utils.L;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public class OverviewExtractor {

    public static Map<String, Object> extract(Path policyDir, int personCount,
                                               int networkNodes, int networkLinks,
                                               double simulationAreaKm2,
                                               double sampleFraction) throws IOException {
        int legCount = countLegs(policyDir.resolve("output_legs.csv.gz"));
        double totalKmTraveled = sumTraveledDistance(policyDir.resolve("output_trips.csv.gz"));
        double scaleFactor = 1.0 / sampleFraction;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("personCount", (int) Math.round(personCount * scaleFactor));
        payload.put("legCount", (int) Math.round(legCount * scaleFactor));
        payload.put("simulationAreaKm2", simulationAreaKm2);
        payload.put("networkNodes", networkNodes);
        payload.put("networkLinks", networkLinks);
        payload.put("totalKmTraveled", Math.round(totalKmTraveled * scaleFactor * 100.0) / 100.0);
        payload.put("samplePersonCount", personCount);
        payload.put("sampleLegCount", legCount);
        payload.put("sampleTotalKmTraveled", totalKmTraveled);
        payload.put("samplePercentage", sampleFraction * 100.0);
        return payload;
    }

    private static int countLegs(Path legsFile) throws IOException {
        int count = 0;
        try (BufferedReader reader = gzipReader(legsFile)) {
            reader.readLine();
            while (reader.readLine() != null) {
                count++;
            }
        }
        return count;
    }

    static double extractDistance(Path tripsFile) throws IOException {
        return sumTraveledDistance(tripsFile);
    }

    private static double sumTraveledDistance(Path tripsFile) throws IOException {
        double totalMeters = 0;
        try (BufferedReader reader = gzipReader(tripsFile)) {
            String header = reader.readLine();
            int distIdx = columnIndex(header, "traveled_distance");

            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(";", -1);
                totalMeters += Double.parseDouble(fields[distIdx]);
            }
        }
        return totalMeters / 1000.0;
    }

    static BufferedReader gzipReader(Path file) throws IOException {
        return new BufferedReader(
                new InputStreamReader(new GZIPInputStream(new FileInputStream(file.toFile()))));
    }

    static int columnIndex(String header, String columnName) {
        String[] columns = header.split(";", -1);
        for (int i = 0; i < columns.length; i++) {
            if (columns[i].equals(columnName)) return i;
        }
        throw new IllegalArgumentException(L.msg("output.column.missing") + ": " + columnName);
    }
}
