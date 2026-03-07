package ez.backend.turbo.preprocess;

import org.matsim.api.core.v01.network.Network;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt2matsim.config.PublicTransitMappingConfigGroup;
import org.matsim.pt2matsim.config.TransportModeParameterSet;
import org.matsim.pt2matsim.gtfs.GtfsConverter;
import org.matsim.pt2matsim.gtfs.GtfsFeedImpl;
import org.matsim.pt2matsim.mapping.PTMapper;
import org.matsim.pt2matsim.tools.NetworkTools;
import org.matsim.pt2matsim.tools.ScheduleTools;
import org.matsim.vehicles.Vehicles;

import java.util.Set;

final class TransitPreprocessor {

    private static final Set<String> VALUED_KEYS = Set.of(
            "--gtfs", "--network", "--output-schedule", "--output-vehicles",
            "--output-network", "--crs", "--sample-day",
            "--max-travel-cost-factor", "--max-link-candidate-distance",
            "--n-link-threshold", "--candidate-distance-multiplier",
            "--threads", "--mode-assignment");
    private static final Set<String> FLAG_KEYS = Set.of("--skip-mapping");

    int execute(String[] args) {
        try {
            return run(args);
        } catch (Exception e) {
            System.err.println("Error | Erreur : " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private int run(String[] args) throws Exception {
        CliArgs cli = CliArgs.parse(args, VALUED_KEYS, FLAG_KEYS);

        String gtfsFolder = cli.require("--gtfs");
        String crs = cli.optional("--crs", "EPSG:32188");
        String sampleDay = cli.optional("--sample-day", "dayWithMostTrips");
        String outputSchedule = cli.require("--output-schedule");
        String outputVehicles = cli.optional("--output-vehicles", null);

        System.out.println("Converting GTFS to MATSim transit schedule..."
                + " | Conversion GTFS vers horaire MATSim...");

        GtfsConverter converter = new GtfsConverter(new GtfsFeedImpl(gtfsFolder));
        converter.convert(sampleDay, crs);
        TransitSchedule schedule = converter.getSchedule();
        Vehicles vehicles = converter.getVehicles();

        System.out.println("GTFS converted: " + schedule.getTransitLines().size() + " lines, "
                + schedule.getFacilities().size() + " stops"
                + " | GTFS converti : " + schedule.getTransitLines().size() + " lignes, "
                + schedule.getFacilities().size() + " arrets");

        if (cli.hasFlag("--skip-mapping")) {
            ScheduleTools.writeTransitSchedule(schedule, outputSchedule);
            if (outputVehicles != null) {
                ScheduleTools.writeVehicles(vehicles, outputVehicles);
            }
            System.out.println("Wrote unmapped schedule (--skip-mapping)"
                    + " | Horaire non-mappe ecrit (--skip-mapping)");
            return 0;
        }

        String networkFile = cli.optional("--network", null);
        if (networkFile == null) {
            System.err.println("--network is required for mapping (use --skip-mapping to skip)"
                    + " | --network est requis pour le mappage (utilisez --skip-mapping pour ignorer)");
            return 1;
        }
        String outputNetwork = cli.optional("--output-network", null);
        if (outputNetwork == null) {
            System.err.println("--output-network is required for mapping"
                    + " | --output-network est requis pour le mappage");
            return 1;
        }

        Network network = NetworkTools.readNetwork(networkFile);

        PublicTransitMappingConfigGroup mapConfig = buildMappingConfig(cli);

        System.out.println("Mapping transit to network..."
                + " | Mappage du transit sur le reseau...");
        PTMapper.mapScheduleToNetwork(schedule, network, mapConfig);

        int artificialCount = countArtificialLinks(network);
        System.out.println("Mapping complete: " + artificialCount + " artificial links created"
                + " | Mappage termine : " + artificialCount + " liens artificiels crees");

        ScheduleTools.writeTransitSchedule(schedule, outputSchedule);
        if (outputVehicles != null) {
            ScheduleTools.writeVehicles(vehicles, outputVehicles);
        }
        NetworkTools.writeNetwork(network, outputNetwork);

        System.out.println("Wrote: " + outputSchedule
                + (outputVehicles != null ? ", " + outputVehicles : "")
                + ", " + outputNetwork
                + " | Ecrit : " + outputSchedule
                + (outputVehicles != null ? ", " + outputVehicles : "")
                + ", " + outputNetwork);
        return 0;
    }

    private PublicTransitMappingConfigGroup buildMappingConfig(CliArgs cli) {
        PublicTransitMappingConfigGroup config = new PublicTransitMappingConfigGroup();

        config.setMaxTravelCostFactor(Double.parseDouble(
                cli.optional("--max-travel-cost-factor", "5.0")));
        config.setMaxLinkCandidateDistance(Double.parseDouble(
                cli.optional("--max-link-candidate-distance", "200.0")));
        config.setNLinkThreshold(Integer.parseInt(
                cli.optional("--n-link-threshold", "6")));
        config.setCandidateDistanceMultiplier(Double.parseDouble(
                cli.optional("--candidate-distance-multiplier", "1.6")));
        config.setNumOfThreads(Integer.parseInt(
                cli.optional("--threads", "4")));
        config.setTravelCostType(PublicTransitMappingConfigGroup.TravelCostType.travelTime);
        config.setRoutingWithCandidateDistance(true);
        config.setRemoveNotUsedStopFacilities(true);
        config.getModesToKeepOnCleanUp().add("car");

        String modeAssignment = cli.optional("--mode-assignment", null);
        if (modeAssignment != null) {
            parseModeAssignments(config, modeAssignment);
        } else {
            addDefaultModeAssignments(config);
        }

        return config;
    }

    private void addDefaultModeAssignments(PublicTransitMappingConfigGroup config) {
        TransportModeParameterSet bus = new TransportModeParameterSet("bus");
        bus.setNetworkModesStr("car,bus,pt");
        config.addParameterSet(bus);

        TransportModeParameterSet subway = new TransportModeParameterSet("subway");
        subway.setNetworkModesStr("metro,rail,pt,subway");
        config.addParameterSet(subway);

        TransportModeParameterSet rail = new TransportModeParameterSet("rail");
        rail.setNetworkModesStr("rail,pt");
        config.addParameterSet(rail);
    }

    private void parseModeAssignments(PublicTransitMappingConfigGroup config, String raw) {
        for (String pair : raw.split(";")) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException(
                        "Invalid mode assignment: " + pair
                                + " (expected format: bus=car,bus,pt;subway=metro,rail)"
                                + " | Assignation de mode invalide : " + pair);
            }
            TransportModeParameterSet param = new TransportModeParameterSet(parts[0].trim());
            param.setNetworkModesStr(parts[1].trim());
            config.addParameterSet(param);
        }
    }

    private int countArtificialLinks(Network network) {
        int count = 0;
        for (var link : network.getLinks().values()) {
            if (link.getAllowedModes().contains("artificial")) {
                count++;
            }
        }
        return count;
    }
}
