package ez.backend.turbo.preprocess;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TransitPreprocessorTest {

    @TempDir
    Path tempDir;

    @Test
    void missingGtfsErrors() {
        int code = new TransitPreprocessor().execute(new String[]{
                "--output-schedule", tempDir.resolve("out.xml").toString(),
                "--skip-mapping"});
        assertEquals(1, code);
    }

    @Test
    void mappingRequiresNetwork() {
        int code = new TransitPreprocessor().execute(new String[]{
                "--gtfs", tempDir.resolve("nonexistent").toString(),
                "--output-schedule", tempDir.resolve("out.xml").toString(),
                "--output-network", tempDir.resolve("net.xml").toString()});
        assertEquals(1, code);
    }

    @Test
    void mappingRequiresOutputNetwork() {
        int code = new TransitPreprocessor().execute(new String[]{
                "--gtfs", tempDir.resolve("nonexistent").toString(),
                "--network", tempDir.resolve("net.xml").toString(),
                "--output-schedule", tempDir.resolve("out.xml").toString()});
        assertEquals(1, code);
    }
}
