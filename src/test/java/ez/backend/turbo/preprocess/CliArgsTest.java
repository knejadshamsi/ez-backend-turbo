package ez.backend.turbo.preprocess;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CliArgsTest {

    private static final Set<String> VALUED = Set.of("--input", "--output", "--keep-attr");
    private static final Set<String> FLAGS = Set.of("--no-attr", "--no-polygon");

    @Test
    void parseValuedArgs() {
        CliArgs args = CliArgs.parse(
                new String[]{"--input", "in.xml", "--output", "out.xml"}, VALUED, FLAGS);
        assertEquals("in.xml", args.require("--input"));
        assertEquals("out.xml", args.require("--output"));
    }

    @Test
    void parseFlags() {
        CliArgs args = CliArgs.parse(
                new String[]{"--no-attr", "--no-polygon", "--input", "f"}, VALUED, FLAGS);
        assertTrue(args.hasFlag("--no-attr"));
        assertTrue(args.hasFlag("--no-polygon"));
        assertFalse(args.hasFlag("--input"));
    }

    @Test
    void requireMissingThrows() {
        CliArgs args = CliArgs.parse(new String[]{"--input", "f"}, VALUED, FLAGS);
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> args.require("--output"));
        assertTrue(ex.getMessage().contains("--output"));
    }

    @Test
    void unknownOptionThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> CliArgs.parse(new String[]{"--bogus"}, VALUED, FLAGS));
    }

    @Test
    void missingValueThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> CliArgs.parse(new String[]{"--input"}, VALUED, FLAGS));
    }

    @Test
    void optionalDefault() {
        CliArgs args = CliArgs.parse(new String[]{}, VALUED, FLAGS);
        assertEquals("fallback", args.optional("--missing", "fallback"));
        assertNull(args.optional("--missing", null));
    }
}
