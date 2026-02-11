package ez.backend.turbo.services;

import ez.backend.turbo.config.StartupValidator;
import ez.backend.turbo.utils.L;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.vehicles.MatsimVehicleReader;
import org.matsim.vehicles.Vehicles;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Component
public class SourceRegistry {

    private static final Logger log = LogManager.getLogger(SourceRegistry.class);
    private static final Set<String> SOURCE_TYPES = Set.of("population", "network", "publicTransport");

    private final Path inputRoot;
    private final Map<String, Map<Integer, Set<String>>> catalog = new HashMap<>();
    private final Map<String, Network> networks = new HashMap<>();
    private final Map<String, TransitData> transitCache = new HashMap<>();

    public record TransitPaths(Path schedule, Path vehicles) {}
    public record TransitData(TransitSchedule schedule, Vehicles vehicles) {}

    public SourceRegistry(StartupValidator startupValidator, L locale) {
        this.inputRoot = startupValidator.getDataRoot().resolve("input");
        log.info(L.msg("source.scan.started"), inputRoot);
        scanSources();
    }

    public Path resolve(String type, int year, String name) {
        if (!SOURCE_TYPES.contains(type)) {
            throw new IllegalArgumentException(String.format(L.msg("source.resolve.type"), type));
        }
        Map<Integer, Set<String>> years = catalog.getOrDefault(type, Map.of());
        Set<String> names = years.get(year);
        if (names == null) {
            throw new IllegalArgumentException(String.format(
                    L.msg("source.resolve.year"), year, type, years.keySet()));
        }
        if (!names.contains(name)) {
            throw new IllegalArgumentException(String.format(
                    L.msg("source.resolve.name"), name, type, year, names));
        }
        return inputRoot.resolve(type).resolve(String.valueOf(year)).resolve(name + ".xml");
    }

    public TransitPaths resolveTransit(int year, String name) {
        resolve("publicTransport", year, name);
        Path yearDir = inputRoot.resolve("publicTransport").resolve(String.valueOf(year));
        return new TransitPaths(yearDir.resolve(name + ".xml"), yearDir.resolve(name + "-vehicles.xml"));
    }

    public Network getNetwork(int year, String name) {
        String key = year + "/" + name;
        Network network = networks.get(key);
        if (network == null) {
            throw new IllegalArgumentException(String.format(
                    L.msg("source.resolve.name"), name, "network", year, catalog.getOrDefault("network", Map.of()).getOrDefault(year, Set.of())));
        }
        return network;
    }

    public TransitData getTransit(int year, String name) {
        String key = year + "/" + name;
        TransitData data = transitCache.get(key);
        if (data == null) {
            throw new IllegalArgumentException(String.format(
                    L.msg("source.resolve.name"), name, "publicTransport", year, catalog.getOrDefault("publicTransport", Map.of()).getOrDefault(year, Set.of())));
        }
        return data;
    }

    private void scanSources() {
        int total = 0;
        total += scanType("network");
        total += scanType("publicTransport");
        total += scanType("population");
        log.info(L.msg("source.scan.complete"), total);
    }

    private int scanType(String type) {
        Path typeDir = inputRoot.resolve(type);
        int count = 0;
        if (!Files.isDirectory(typeDir)) {
            log.warn(L.msg("source.scan.type.none"), type);
            return 0;
        }
        try (DirectoryStream<Path> years = Files.newDirectoryStream(typeDir)) {
            for (Path yearDir : years) {
                if (!Files.isDirectory(yearDir)) continue;
                int year;
                try {
                    year = Integer.parseInt(yearDir.getFileName().toString());
                } catch (NumberFormatException e) {
                    log.warn(L.msg("source.scan.skipped.dir"), yearDir.getFileName());
                    continue;
                }
                count += switch (type) {
                    case "network" -> scanNetworkYear(yearDir, year);
                    case "publicTransport" -> scanTransitYear(yearDir, year);
                    case "population" -> scanPopulationYear(yearDir, year);
                    default -> 0;
                };
            }
        } catch (Exception e) {
            log.warn(L.msg("source.scan.type.none"), type);
            return 0;
        }
        if (count > 0) {
            log.info(L.msg("source.scan.type.found"), count, type);
        } else {
            log.warn(L.msg("source.scan.type.none"), type);
        }
        return count;
    }

    private int scanNetworkYear(Path yearDir, int year) {
        int count = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(yearDir, "*.xml")) {
            for (Path file : files) {
                String name = stripExtension(file.getFileName().toString());
                try {
                    Network network = NetworkUtils.readNetwork(file.toString());
                    register("network", year, name);
                    networks.put(year + "/" + name, network);
                    log.info(L.msg("source.network.loaded"), name,
                            network.getNodes().size(), network.getLinks().size());
                    count++;
                } catch (Exception e) {
                    log.warn(L.msg("source.scan.skipped.file"), file.getFileName());
                }
            }
        } catch (Exception e) {
            log.warn(L.msg("source.scan.skipped.dir"), yearDir.getFileName());
        }
        return count;
    }

    private int scanTransitYear(Path yearDir, int year) {
        int count = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(yearDir, "*.xml")) {
            for (Path file : files) {
                String filename = file.getFileName().toString();
                if (filename.endsWith("-vehicles.xml")) continue;
                String name = stripExtension(filename);
                Path vehiclesFile = yearDir.resolve(name + "-vehicles.xml");
                if (!Files.exists(vehiclesFile)) {
                    log.warn(L.msg("source.transit.incomplete"), name, year);
                    continue;
                }
                try {
                    Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
                    new TransitScheduleReader(scenario).readFile(file.toString());
                    new MatsimVehicleReader(scenario.getTransitVehicles()).readFile(vehiclesFile.toString());
                    TransitSchedule schedule = scenario.getTransitSchedule();
                    Vehicles vehicles = scenario.getTransitVehicles();
                    register("publicTransport", year, name);
                    transitCache.put(year + "/" + name, new TransitData(schedule, vehicles));
                    log.info(L.msg("source.transit.loaded"), name,
                            schedule.getFacilities().size(), schedule.getTransitLines().size());
                    count++;
                } catch (Exception e) {
                    log.warn(L.msg("source.scan.skipped.file"), file.getFileName());
                }
            }
        } catch (Exception e) {
            log.warn(L.msg("source.scan.skipped.dir"), yearDir.getFileName());
        }
        return count;
    }

    private int scanPopulationYear(Path yearDir, int year) {
        int count = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(yearDir, "*.xml")) {
            for (Path file : files) {
                String name = stripExtension(file.getFileName().toString());
                try (InputStream in = new FileInputStream(file.toFile())) {
                    XMLStreamReader reader = XMLInputFactory.newInstance().createXMLStreamReader(in);
                    while (reader.hasNext()) {
                        if (reader.next() == XMLStreamReader.START_ELEMENT) {
                            String root = reader.getLocalName();
                            if ("population".equals(root) || "plans".equals(root)) {
                                register("population", year, name);
                                count++;
                            } else {
                                log.warn(L.msg("source.population.invalid"), file.getFileName());
                            }
                            break;
                        }
                    }
                    reader.close();
                } catch (Exception e) {
                    log.warn(L.msg("source.scan.skipped.file"), file.getFileName());
                }
            }
        } catch (Exception e) {
            log.warn(L.msg("source.scan.skipped.dir"), yearDir.getFileName());
        }
        return count;
    }

    private void register(String type, int year, String name) {
        catalog.computeIfAbsent(type, k -> new HashMap<>())
                .computeIfAbsent(year, k -> new TreeSet<>())
                .add(name);
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
