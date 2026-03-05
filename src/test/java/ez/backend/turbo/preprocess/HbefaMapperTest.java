package ez.backend.turbo.preprocess;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class HbefaMapperTest {

    @Test
    void defaultMappings() {
        HbefaMapper mapper = HbefaMapper.withDefaults();
        assertEquals("URB/Access/30", mapper.map("residential"));
        assertEquals("URB/MW-Nat./80", mapper.map("motorway"));
        assertEquals("URB/Distr/50", mapper.map("secondary"));
        assertEquals("URB/Local/50", mapper.map("tertiary"));
    }

    @Test
    void unmappedReturnsNull() {
        HbefaMapper mapper = HbefaMapper.withDefaults();
        assertNull(mapper.map("cycleway"));
        assertNull(mapper.map("footway"));
    }

    @Test
    void csvOverride(@TempDir Path tempDir) throws IOException {
        Path csv = tempDir.resolve("mapping.csv");
        Files.writeString(csv, "osm_highway,hbefa_road_type\ncycleway,URB/Cycle/20\n");
        HbefaMapper mapper = HbefaMapper.fromCsv(csv);
        assertEquals("URB/Cycle/20", mapper.map("cycleway"));
        assertNull(mapper.map("residential"));
    }

    @Test
    void withDefaultsAndOverrides(@TempDir Path tempDir) throws IOException {
        Path csv = tempDir.resolve("override.csv");
        Files.writeString(csv, "osm_highway,hbefa_road_type\nresidential,URB/Residential/40\ncycleway,URB/Cycle/20\n");
        HbefaMapper mapper = HbefaMapper.withDefaultsAndOverrides(csv);
        assertEquals("URB/Residential/40", mapper.map("residential"));
        assertEquals("URB/Cycle/20", mapper.map("cycleway"));
        assertEquals("URB/MW-Nat./80", mapper.map("motorway"));
    }
}
