package ez.backend.turbo.preprocess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;

import java.nio.file.Path;

final class GeoJsonPolygonReader {

    private GeoJsonPolygonReader() {}

    static Polygon read(Path geojsonFile, GeometryFactory gf) {
        try {
            JsonNode root = new ObjectMapper().readTree(geojsonFile.toFile());
            JsonNode geometry = extractGeometry(root);

            String type = geometry.get("type").asText();
            if (!"Polygon".equals(type)) {
                throw new IllegalArgumentException(
                        "Expected Polygon geometry, got: " + type
                                + " | Type Polygon attendu, recu : " + type);
            }

            JsonNode coordinates = geometry.get("coordinates");
            LinearRing shell = parseRing(coordinates.get(0), gf);
            LinearRing[] holes = new LinearRing[coordinates.size() - 1];
            for (int i = 1; i < coordinates.size(); i++) {
                holes[i - 1] = parseRing(coordinates.get(i), gf);
            }
            return gf.createPolygon(shell, holes);

        } catch (java.io.IOException e) {
            throw new IllegalArgumentException(
                    "Cannot read GeoJSON file: " + geojsonFile
                            + " | Impossible de lire le fichier GeoJSON : " + geojsonFile, e);
        }
    }

    private static JsonNode extractGeometry(JsonNode root) {
        String type = root.get("type").asText();
        if ("Polygon".equals(type) && root.has("coordinates")) {
            return root;
        }
        if ("Feature".equals(type)) {
            return root.get("geometry");
        }
        if ("FeatureCollection".equals(type)) {
            JsonNode features = root.get("features");
            if (features == null || features.isEmpty()) {
                throw new IllegalArgumentException(
                        "FeatureCollection has no features"
                                + " | La FeatureCollection est vide");
            }
            return features.get(0).get("geometry");
        }
        throw new IllegalArgumentException(
                "Unsupported GeoJSON type: " + type
                        + " | Type GeoJSON non supporte : " + type);
    }

    private static LinearRing parseRing(JsonNode ringNode, GeometryFactory gf) {
        Coordinate[] coords = new Coordinate[ringNode.size()];
        for (int i = 0; i < ringNode.size(); i++) {
            JsonNode point = ringNode.get(i);
            coords[i] = new Coordinate(point.get(0).asDouble(), point.get(1).asDouble());
        }
        return gf.createLinearRing(coords);
    }
}
