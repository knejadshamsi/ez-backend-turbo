package org.mobility.start.valid;

import org.mobility.utils.WorkflowResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ZoneLinksValidator {
    @Value("${matsim.input-directory}")
    private String inputDirectory;

    public WorkflowResult validate(List<String> zoneLinks) {
        if (zoneLinks == null) {
            return WorkflowResult.error(400, "Zone links cannot be null");
        }

        try {
            Path basePath = Paths.get(System.getProperty("user.dir"));
            Path validLinksPath = basePath.resolve("src/main/resources/indexes/valid_links.idx");
            
            if (!Files.exists(validLinksPath)) {
                return WorkflowResult.error(500, "Valid links index file not found");
            }

            Set<String> validLinks = Files.readAllLines(validLinksPath)
                .stream()
                .map(String::trim)
                .collect(Collectors.toSet());

            if (validLinks.isEmpty()) {
                return WorkflowResult.error(500, "Valid links index file is empty");
            }

            if (!zoneLinks.isEmpty()) {
                List<String> normalizedZoneLinks = zoneLinks.stream()
                    .map(Object::toString)
                    .map(String::trim)
                    .collect(Collectors.toList());

                List<String> invalidLinks = normalizedZoneLinks.stream()
                    .filter(link -> !validLinks.contains(link))
                    .collect(Collectors.toList());

                if (!invalidLinks.isEmpty()) {
                    return WorkflowResult.error(400, "Invalid zone links: " + String.join(", ", invalidLinks));
                }
            }

            return WorkflowResult.success(Map.of("zoneLinks", zoneLinks));
        } catch (Exception e) {
            return WorkflowResult.error(500, "Failed to validate zone links: " + e.getMessage());
        }
    }
}
