package ez.backend.turbo.preprocess;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GeoJsonPolygonReaderTest {

    private static final GeometryFactory GF = new GeometryFactory();

    private static final String POLYGON_JSON = """
            {"type":"Polygon","coordinates":[[[-73.6,45.5],[-73.5,45.5],[-73.5,45.6],[-73.6,45.6],[-73.6,45.5]]]}""";

    private static final String FEATURE_JSON = """
            {"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[[-73.6,45.5],[-73.5,45.5],[-73.5,45.6],[-73.6,45.6],[-73.6,45.5]]]}}""";

    private static final String FEATURE_COLLECTION_JSON = """
            {"type":"FeatureCollection","features":[{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[[-73.6,45.5],[-73.5,45.5],[-73.5,45.6],[-73.6,45.6],[-73.6,45.5]]]}}]}""";

    @Test
    void readPolygonGeometry(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("polygon.geojson");
        Files.writeString(file, POLYGON_JSON);
        Polygon polygon = GeoJsonPolygonReader.read(file, GF);
        assertNotNull(polygon);
        assertEquals(5, polygon.getExteriorRing().getNumPoints());
    }

    @Test
    void readFeature(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("feature.geojson");
        Files.writeString(file, FEATURE_JSON);
        Polygon polygon = GeoJsonPolygonReader.read(file, GF);
        assertNotNull(polygon);
        assertEquals(5, polygon.getExteriorRing().getNumPoints());
    }

    @Test
    void readFeatureCollection(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("fc.geojson");
        Files.writeString(file, FEATURE_COLLECTION_JSON);
        Polygon polygon = GeoJsonPolygonReader.read(file, GF);
        assertNotNull(polygon);
        assertEquals(5, polygon.getExteriorRing().getNumPoints());
    }
}
