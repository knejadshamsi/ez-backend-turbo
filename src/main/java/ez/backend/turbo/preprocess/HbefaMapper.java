package ez.backend.turbo.preprocess;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

final class HbefaMapper {

    private final Map<String, String> mapping;

    private HbefaMapper(Map<String, String> mapping) {
        this.mapping = mapping;
    }

    static HbefaMapper withDefaults() {
        Map<String, String> m = new HashMap<>();
        m.put("motorway", "URB/MW-Nat./80");
        m.put("motorway_link", "URB/MW-Nat./60");
        m.put("trunk", "URB/Trunk-Nat./70");
        m.put("trunk_link", "URB/Trunk-Nat./50");
        m.put("primary", "URB/Trunk-City/60");
        m.put("primary_link", "URB/Trunk-City/50");
        m.put("secondary", "URB/Distr/50");
        m.put("secondary_link", "URB/Distr/30");
        m.put("tertiary", "URB/Local/50");
        m.put("tertiary_link", "URB/Local/30");
        m.put("residential", "URB/Access/30");
        m.put("living_street", "URB/Access/30");
        m.put("unclassified", "URB/Local/50");
        m.put("service", "URB/Access/30");
        return new HbefaMapper(m);
    }

    static HbefaMapper fromCsv(Path csvFile) {
        Map<String, String> m = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(csvFile)) {
            String line = reader.readLine();
            if (line == null) {
                throw new IllegalArgumentException(
                        "Empty HBEFA mapping file | Fichier de mappage HBEFA vide");
            }
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",", 2);
                if (parts.length != 2) {
                    throw new IllegalArgumentException(
                            "Invalid HBEFA mapping line: " + line
                                    + " | Ligne de mappage invalide : " + line);
                }
                m.put(parts[0].trim(), parts[1].trim());
            }
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException(
                    "Cannot read HBEFA mapping file: " + csvFile
                            + " | Impossible de lire le fichier de mappage : " + csvFile, e);
        }
        return new HbefaMapper(m);
    }

    static HbefaMapper withDefaultsAndOverrides(Path csvFile) {
        HbefaMapper base = withDefaults();
        HbefaMapper overrides = fromCsv(csvFile);
        base.mapping.putAll(overrides.mapping);
        return base;
    }

    String map(String osmHighway) {
        return mapping.get(osmHighway);
    }
}
