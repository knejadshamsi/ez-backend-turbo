package org.mobility.start.constructor;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.scenario.ScenarioUtils;
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
public class PopulationConstructor {
    @Value("${matsim.input-directory}")
    private String inputDirectory;

    public Population constructPopulation(Map<String, Object> request) {
        Path basePath = Paths.get(System.getProperty("user.dir"));
        Path populationFilePath = basePath.resolve(inputDirectory).resolve("population.xml").normalize();
        
        if (!Files.exists(populationFilePath)) {
            throw new RuntimeException("Population file not found: " + populationFilePath);
        }

        Config config = ConfigUtils.createConfig();
        Scenario scenario = ScenarioUtils.createScenario(config);
        
        try {
            new PopulationReader(scenario).readFile(populationFilePath.toString());
        } catch (Exception e) {
            throw new RuntimeException("Failed to read population file: " + e.getMessage());
        }

        Population population = scenario.getPopulation();
        if (population.getPersons().isEmpty()) {
            throw new RuntimeException("Population file contains no persons");
        }

        Boolean simulateAllAgents = (Boolean) request.get("simulateAllAgents");
        if (simulateAllAgents != null && !simulateAllAgents && request.containsKey("zoneLinks")) {
            List<String> zoneLinks = (List<String>) request.get("zoneLinks");
            if (!zoneLinks.isEmpty()) {
                Path linkAgentsIndexPath = basePath.resolve("src/main/resources/indexes/link_agents.idx").normalize();
                if (!Files.exists(linkAgentsIndexPath)) {
                    throw new RuntimeException("Link agents index file not found: " + linkAgentsIndexPath);
                }

                Set<String> agentsInZoneLinks = readAgentsForLinks(linkAgentsIndexPath, zoneLinks);
                if (agentsInZoneLinks.isEmpty()) {
                    throw new RuntimeException("No agents found for specified zone links");
                }

                Set<Id<Person>> personsToRemove = population.getPersons().values().stream()
                    .filter(person -> !agentsInZoneLinks.contains(person.getId().toString()))
                    .map(Person::getId)
                    .collect(Collectors.toSet());

                personsToRemove.forEach(population.getPersons()::remove);

                if (population.getPersons().isEmpty()) {
                    throw new RuntimeException("No agents remain after filtering by zone links");
                }
            }
        }
        
        return population;
    }

    private Set<String> readAgentsForLinks(Path linkAgentsIndexPath, List<String> zoneLinks) {
        try {
            Set<String> zoneLinksSet = new HashSet<>(zoneLinks);
            return Files.lines(linkAgentsIndexPath)
                .filter(line -> {
                    String[] parts = line.split(":");
                    return parts.length == 2 && zoneLinksSet.contains(parts[0].trim());
                })
                .flatMap(line -> {
                    String[] parts = line.split(":");
                    String[] agents = parts[1].trim().split(",");
                    return java.util.Arrays.stream(agents).map(String::trim).filter(s -> !s.isEmpty());
                })
                .collect(Collectors.toSet());
        } catch (Exception e) {
            throw new RuntimeException("Failed to read link agents index: " + e.getMessage());
        }
    }
}
