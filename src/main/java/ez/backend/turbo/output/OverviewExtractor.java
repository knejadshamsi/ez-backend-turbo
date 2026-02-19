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
                                               double simulationAreaKm2) throws IOException {
        int legCount = countLegs(policyDir.resolve("output_legs.csv.gz"));
        double totalKmTraveled = sumTraveledDistance(policyDir.resolve("output_trips.csv.gz"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("personCount", personCount);
        payload.put("legCount", legCount);
        payload.put("simulationAreaKm2", simulationAreaKm2);
        payload.put("networkNodes", networkNodes);
        payload.put("networkLinks", networkLinks);
        payload.put("totalKmTraveled", totalKmTraveled);
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
