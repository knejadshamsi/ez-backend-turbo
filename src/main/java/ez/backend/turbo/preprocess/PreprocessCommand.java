package ez.backend.turbo.preprocess;

import java.util.Arrays;

public final class PreprocessCommand {

    private PreprocessCommand() {}

    public static void run(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        String subcommand = args[0];
        String[] remaining = Arrays.copyOfRange(args, 1, args.length);

        switch (subcommand) {
            case "network" -> System.exit(new NetworkPreprocessor().execute(remaining));
            case "population" -> System.exit(new PopulationPreprocessor().execute(remaining));
            case "transit" -> System.exit(new TransitPreprocessor().execute(remaining));
            default -> {
                System.err.println("Unknown subcommand: " + subcommand
                        + " | Sous-commande inconnue : " + subcommand);
                printUsage();
                System.exit(1);
            }
        }
    }

    private static void printUsage() {
        System.err.println();
        System.err.println("Usage | Utilisation :");
        System.err.println("  java -jar app.jar preprocess network    --input <file> --output <file> [options]");
        System.err.println("  java -jar app.jar preprocess population --input <file> --output <file> [options]");
        System.err.println("  java -jar app.jar preprocess transit    --gtfs <folder> --output-schedule <file> [options]");
        System.err.println();
        System.err.println("Network options | Options reseau :");
        System.err.println("  --polygon <geojson>    Geofence polygon (default: Montreal)");
        System.err.println("  --no-polygon           Skip geofencing");
        System.err.println("  --hbefa-map <csv>      Custom OSM highway -> HBEFA mapping");
        System.err.println("  --no-attr              Strip all non-essential attributes");
        System.err.println("  --keep-attr <a,b,c>    Keep only named attributes");
        System.err.println("  --source-crs <epsg>    Source CRS (default: EPSG:32188)");
        System.err.println("  --target-crs <epsg>    Reproject to target CRS");
        System.err.println();
        System.err.println("Population options | Options population :");
        System.err.println("  --polygon <geojson>    Geofence polygon (default: Montreal)");
        System.err.println("  --no-polygon           Skip geofencing");
        System.err.println("  --no-attr              Strip all person attributes");
        System.err.println("  --keep-attr <a,b,c>    Keep only named person attributes");
        System.err.println("  --no-routes            Strip route link chains from legs");
        System.err.println("  --keep-selected-only   Keep only selected plan per person");
        System.err.println("  --no-planless          Remove persons with no plans");
        System.err.println("  --sample <percent>     Downsample population");
        System.err.println("  --source-crs <epsg>    Source CRS (default: EPSG:32188)");
        System.err.println("  --target-crs <epsg>    Reproject to target CRS");
        System.err.println();
        System.err.println("Transit options | Options transit :");
        System.err.println("  --gtfs <folder>                      GTFS data folder (required)");
        System.err.println("  --output-schedule <file>             Output schedule XML (required)");
        System.err.println("  --output-vehicles <file>             Output vehicles XML");
        System.err.println("  --network <file>                     Network for route mapping");
        System.err.println("  --output-network <file>              Output network with PT links");
        System.err.println("  --crs <epsg>                         Output CRS (default: EPSG:32188)");
        System.err.println("  --sample-day <param>                 Sample day: date (yyyymmdd), dayWithMostTrips, all");
        System.err.println("  --skip-mapping                       Output unmapped schedule only");
        System.err.println("  --max-travel-cost-factor <n>         Artificial link threshold (default: 5.0)");
        System.err.println("  --max-link-candidate-distance <m>    Stop-to-link search radius in meters (default: 200)");
        System.err.println("  --n-link-threshold <n>               Link candidates per stop (default: 6)");
        System.err.println("  --candidate-distance-multiplier <n>  Extra candidate radius multiplier (default: 1.6)");
        System.err.println("  --threads <n>                        Mapping threads (default: 4)");
        System.err.println("  --mode-assignment <spec>             Mode mapping: bus=car,bus,pt;subway=metro,rail");
        System.err.println();
    }
}
