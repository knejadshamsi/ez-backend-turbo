package ez.backend.turbo;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class DevDataGenerator {

    static final int COLS = 8;
    static final int ROWS = 10;
    static final int PERSON_COUNT = 100;
    static final double CENTER_LON = -73.571;
    static final double CENTER_LAT = 45.511;
    static final double SPACING_LON = 0.003;
    static final double SPACING_LAT = 0.002;
    static final long SEED = 42;

    // Zone covers rows 2-6, cols 2-5 (center block)
    static final int ZONE_ROW_MIN = 2;
    static final int ZONE_ROW_MAX = 6;
    static final int ZONE_COL_MIN = 2;
    static final int ZONE_COL_MAX = 5;

    static final Path ROOT = Path.of("dev-data/input");
    static final Path NET_DIR = ROOT.resolve("network/2024");
    static final Path POP_DIR = ROOT.resolve("population/2024");
    static final Path PT_DIR = ROOT.resolve("publicTransport/2024");
    static final Path HBEFA_DIR = ROOT.resolve("hbefa");

    static double[][] nodes;
    static int nodeCount;

    public static void main(String[] args) throws IOException {
        buildNodes();
        writeNetwork();
        writePopulation();
        writeTransitSchedule();
        writeTransitVehicles();
        extendHbefa();
        writeDevRequest();
        cleanDatabases();
        System.out.println("Dev data generated: " + PERSON_COUNT + " persons, "
                + nodeCount + " nodes, 3 road types, bus + subway transit");
    }

    static void buildNodes() {
        nodeCount = COLS * ROWS;
        nodes = new double[nodeCount][2];
        double startLon = CENTER_LON - (COLS - 1) * SPACING_LON / 2.0;
        double startLat = CENTER_LAT - (ROWS - 1) * SPACING_LAT / 2.0;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int idx = r * COLS + c;
                nodes[idx][0] = startLon + c * SPACING_LON;
                nodes[idx][1] = startLat + r * SPACING_LAT;
            }
        }
    }

    static int nodeId(int r, int c) {
        return r * COLS + c + 1;
    }

    static String horizontalRoadType(int row) {
        // Row 4: highway INSIDE zone (the penalized fast road)
        // Row 8: highway OUTSIDE zone (the bypass)
        if (row == 4 || row == 8) return "URB/MW-Nat./80";
        // Rows 1, 3, 5, 7: distributors (1,7 outside zone; 3,5 inside)
        if (row == 1 || row == 3 || row == 5 || row == 7) return "URB/Distr/50";
        // Rows 0, 2, 6, 9: local streets
        return "URB/Local/30";
    }

    static String verticalRoadType(int col) {
        // Cols 0, 1, 6, 7: distributor (outside zone — bypass corridors)
        if (col == 0 || col == 1 || col == 6 || col == 7) return "URB/Distr/50";
        // Cols 2-5: local (inside zone)
        return "URB/Local/30";
    }

    static double[] roadParams(String type) {
        return switch (type) {
            case "URB/MW-Nat./80" -> new double[]{22.22, 3600.0, 3.0};
            case "URB/Distr/50" -> new double[]{13.89, 1800.0, 2.0};
            default -> new double[]{8.33, 600.0, 1.0};
        };
    }

    static void writeNetwork() throws IOException {
        Files.createDirectories(NET_DIR);
        List<int[]> links = new ArrayList<>();
        List<String> roadTypes = new ArrayList<>();
        List<double[]> linkParams = new ArrayList<>();

        for (int r = 0; r < ROWS; r++) {
            String type = horizontalRoadType(r);
            double[] params = roadParams(type);
            for (int c = 0; c < COLS - 1; c++) {
                int from = nodeId(r, c);
                int to = nodeId(r, c + 1);
                double length = haversine(nodes[from - 1], nodes[to - 1]);
                links.add(new int[]{from, to});
                roadTypes.add(type);
                linkParams.add(new double[]{length, params[0], params[1], params[2]});
                links.add(new int[]{to, from});
                roadTypes.add(type);
                linkParams.add(new double[]{length, params[0], params[1], params[2]});
            }
        }

        for (int r = 0; r < ROWS - 1; r++) {
            for (int c = 0; c < COLS; c++) {
                String type = verticalRoadType(c);
                double[] params = roadParams(type);
                int from = nodeId(r, c);
                int to = nodeId(r + 1, c);
                double length = haversine(nodes[from - 1], nodes[to - 1]);
                links.add(new int[]{from, to});
                roadTypes.add(type);
                linkParams.add(new double[]{length, params[0], params[1], params[2]});
                links.add(new int[]{to, from});
                roadTypes.add(type);
                linkParams.add(new double[]{length, params[0], params[1], params[2]});
            }
        }

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(NET_DIR.resolve("dev.xml")))) {
            w.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            w.println("<!DOCTYPE network SYSTEM \"http://www.matsim.org/files/dtd/network_v2.dtd\">");
            w.println("<network name=\"dev\">");
            w.println("  <nodes>");
            for (int i = 0; i < nodeCount; i++) {
                w.printf("    <node id=\"%d\" x=\"%.6f\" y=\"%.6f\"/>%n", i + 1, nodes[i][0], nodes[i][1]);
            }
            w.println("  </nodes>");
            w.println("  <links capperiod=\"01:00:00\">");
            for (int i = 0; i < links.size(); i++) {
                int[] l = links.get(i);
                double[] p = linkParams.get(i);
                w.printf("    <link id=\"%d\" from=\"%d\" to=\"%d\" length=\"%.1f\" freespeed=\"%.2f\" capacity=\"%.1f\" permlanes=\"%.1f\" modes=\"car,pt,walk,bike\">%n",
                        i + 1, l[0], l[1], p[0], p[1], p[2], p[3]);
                w.printf("      <attributes>%n");
                w.printf("        <attribute name=\"hbefa_road_type\" class=\"java.lang.String\">%s</attribute>%n", roadTypes.get(i));
                w.printf("      </attributes>%n");
                w.printf("    </link>%n");
            }
            w.println("  </links>");
            w.println("</network>");
        }
    }

    static void writePopulation() throws IOException {
        Files.createDirectories(POP_DIR);
        Random rng = new Random(SEED);

        double minLon = nodes[0][0];
        double maxLon = nodes[nodeCount - 1][0];
        double minLat = nodes[0][1];
        double maxLat = nodes[nodeCount - 1][1];

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(POP_DIR.resolve("dev.xml")))) {
            w.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            w.println("<!DOCTYPE population SYSTEM \"http://www.matsim.org/files/dtd/population_v6.dtd\">");
            w.println("<population>");

            for (int p = 1; p <= PERSON_COUNT; p++) {
                String mode;
                double roll = rng.nextDouble();
                if (roll < 0.65) mode = "car";
                else if (roll < 0.80) mode = "pt";
                else if (roll < 0.90) mode = "bike";
                else mode = "walk";

                int homeCol, homeRow, workCol, workRow;

                if (p <= 30) {
                    // Cross-zone car trips: home inside zone, work outside
                    homeCol = ZONE_COL_MIN + rng.nextInt(ZONE_COL_MAX - ZONE_COL_MIN + 1);
                    homeRow = ZONE_ROW_MIN + rng.nextInt(ZONE_ROW_MAX - ZONE_ROW_MIN + 1);
                    workCol = rng.nextBoolean() ? rng.nextInt(ZONE_COL_MIN) : ZONE_COL_MAX + 1 + rng.nextInt(COLS - ZONE_COL_MAX - 1);
                    workRow = rng.nextInt(ROWS);
                    mode = "car";
                } else if (p <= 45) {
                    // Outside-to-outside trips that pass through zone
                    homeCol = rng.nextBoolean() ? 0 : COLS - 1;
                    homeRow = ZONE_ROW_MIN + rng.nextInt(ZONE_ROW_MAX - ZONE_ROW_MIN + 1);
                    workCol = homeCol == 0 ? COLS - 1 : 0;
                    workRow = ZONE_ROW_MIN + rng.nextInt(ZONE_ROW_MAX - ZONE_ROW_MIN + 1);
                    mode = "car";
                } else if (p <= 55) {
                    // Short trips inside zone (bike/walk candidates)
                    homeCol = ZONE_COL_MIN + rng.nextInt(ZONE_COL_MAX - ZONE_COL_MIN + 1);
                    homeRow = ZONE_ROW_MIN + rng.nextInt(ZONE_ROW_MAX - ZONE_ROW_MIN + 1);
                    workCol = homeCol + (rng.nextBoolean() ? 1 : -1);
                    workCol = Math.max(ZONE_COL_MIN, Math.min(ZONE_COL_MAX, workCol));
                    workRow = homeRow + (rng.nextBoolean() ? 1 : -1);
                    workRow = Math.max(ZONE_ROW_MIN, Math.min(ZONE_ROW_MAX, workRow));
                    if (workCol == homeCol && workRow == homeRow) workCol = Math.min(homeCol + 1, ZONE_COL_MAX);
                    mode = "car";
                } else if (p <= 70) {
                    // Trips near transit (bus row 7 or subway col 3)
                    boolean nearBus = rng.nextBoolean();
                    if (nearBus) {
                        homeRow = 7;
                        homeCol = rng.nextInt(COLS);
                        workRow = 7;
                        workCol = (homeCol + 3 + rng.nextInt(3)) % COLS;
                    } else {
                        homeCol = 3;
                        homeRow = rng.nextInt(ROWS);
                        workCol = 3;
                        workRow = (homeRow + 3 + rng.nextInt(3)) % ROWS;
                    }
                    mode = "car";
                } else if (p <= 80) {
                    // Fully outside zone trips (control group, should see no change)
                    homeCol = rng.nextBoolean() ? 0 : COLS - 1;
                    homeRow = rng.nextBoolean() ? 0 : ROWS - 1;
                    workCol = rng.nextBoolean() ? 0 : COLS - 1;
                    workRow = rng.nextBoolean() ? 0 : ROWS - 1;
                    if (workCol == homeCol && workRow == homeRow) workCol = homeCol == 0 ? COLS - 1 : 0;
                    mode = "car";
                } else {
                    // Remaining: random placement, random mode
                    homeCol = rng.nextInt(COLS);
                    homeRow = rng.nextInt(ROWS);
                    workCol = rng.nextInt(COLS);
                    workRow = rng.nextInt(ROWS);
                    if (workCol == homeCol && workRow == homeRow) {
                        workCol = (homeCol + 1) % COLS;
                    }
                }

                double homeLon = nodes[nodeId(homeRow, homeCol) - 1][0];
                double homeLat = nodes[nodeId(homeRow, homeCol) - 1][1];
                double workLon = nodes[nodeId(workRow, workCol) - 1][0];
                double workLat = nodes[nodeId(workRow, workCol) - 1][1];

                int depHour = 7 + rng.nextInt(2);
                int depMin = rng.nextInt(60);
                int depSec = rng.nextInt(60);
                int retHour = 16 + rng.nextInt(3);
                int retMin = rng.nextInt(60);
                int retSec = rng.nextInt(60);

                w.printf("  <person id=\"%d\">%n", p);
                w.printf("    <plan selected=\"yes\">%n");
                w.printf("      <activity type=\"home\" x=\"%.6f\" y=\"%.6f\" end_time=\"%02d:%02d:%02d\"/>%n",
                        homeLon, homeLat, depHour, depMin, depSec);
                w.printf("      <leg mode=\"%s\"/>%n", mode);
                w.printf("      <activity type=\"work\" x=\"%.6f\" y=\"%.6f\" end_time=\"%02d:%02d:%02d\"/>%n",
                        workLon, workLat, retHour, retMin, retSec);
                w.printf("      <leg mode=\"%s\"/>%n", mode);
                w.printf("      <activity type=\"home\" x=\"%.6f\" y=\"%.6f\"/>%n", homeLon, homeLat);
                w.printf("    </plan>%n");
                w.printf("  </person>%n");
            }

            w.println("</population>");
        }
    }

    static void writeTransitSchedule() throws IOException {
        Files.createDirectories(PT_DIR);

        // Bus on row 7 (outside zone, distributor bypass)
        int busRow = 7;
        // Subway on col 3 (through zone center)
        int subwayCol = 3;

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(PT_DIR.resolve("dev.xml")))) {
            w.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            w.println("<!DOCTYPE transitSchedule SYSTEM \"http://www.matsim.org/files/dtd/transitSchedule_v2.dtd\">");
            w.println("<transitSchedule>");
            w.println("  <transitStops>");

            for (int c = 0; c < COLS; c++) {
                int nid = nodeId(busRow, c);
                int linkId = findHorizontalLinkId(busRow, c);
                w.printf("    <stopFacility id=\"bus_stop_%d\" x=\"%.6f\" y=\"%.6f\" linkRefId=\"%d\" name=\"Bus Stop %d\"/>%n",
                        c + 1, nodes[nid - 1][0], nodes[nid - 1][1], linkId, c + 1);
            }
            for (int r = 0; r < ROWS; r++) {
                int nid = nodeId(r, subwayCol);
                int linkId = findVerticalLinkId(r, subwayCol);
                w.printf("    <stopFacility id=\"subway_stop_%d\" x=\"%.6f\" y=\"%.6f\" linkRefId=\"%d\" name=\"Subway Stop %d\"/>%n",
                        r + 1, nodes[nid - 1][0], nodes[nid - 1][1], linkId, r + 1);
            }

            w.println("  </transitStops>");

            w.println("  <transitLine id=\"bus_line\" name=\"Bus EW\">");
            w.println("    <transitRoute id=\"bus_route_1\">");
            w.println("      <transportMode>bus</transportMode>");
            w.println("      <routeProfile>");
            for (int c = 0; c < COLS; c++) {
                int minutes = c * 3;
                if (c == 0) {
                    w.printf("        <stop refId=\"bus_stop_%d\" departureOffset=\"00:00:00\"/>%n", c + 1);
                } else if (c == COLS - 1) {
                    w.printf("        <stop refId=\"bus_stop_%d\" arrivalOffset=\"00:%02d:00\"/>%n", c + 1, minutes);
                } else {
                    w.printf("        <stop refId=\"bus_stop_%d\" arrivalOffset=\"00:%02d:00\" departureOffset=\"00:%02d:30\"/>%n",
                            c + 1, minutes, minutes);
                }
            }
            w.println("      </routeProfile>");
            w.println("      <route>");
            for (int c = 0; c < COLS - 1; c++) {
                w.printf("        <link refId=\"%d\"/>%n", findHorizontalLinkId(busRow, c));
            }
            w.println("      </route>");
            w.println("      <departures>");
            int busDepId = 1;
            int busVehicleCount = 6;
            for (int h = 6; h <= 9; h++) {
                for (int m = 0; m < 60; m += 10) {
                    w.printf("        <departure id=\"bus_dep_%d\" departureTime=\"%02d:%02d:00\" vehicleRefId=\"bus_%d\"/>%n",
                            busDepId, h, m, ((busDepId - 1) % busVehicleCount) + 1);
                    busDepId++;
                }
            }
            for (int h = 16; h <= 19; h++) {
                for (int m = 0; m < 60; m += 10) {
                    w.printf("        <departure id=\"bus_dep_%d\" departureTime=\"%02d:%02d:00\" vehicleRefId=\"bus_%d\"/>%n",
                            busDepId, h, m, ((busDepId - 1) % busVehicleCount) + 1);
                    busDepId++;
                }
            }
            w.println("      </departures>");
            w.println("    </transitRoute>");
            w.println("  </transitLine>");

            w.println("  <transitLine id=\"subway_line\" name=\"Subway NS\">");
            w.println("    <transitRoute id=\"subway_route_1\">");
            w.println("      <transportMode>subway</transportMode>");
            w.println("      <routeProfile>");
            for (int r = 0; r < ROWS; r++) {
                int minutes = r * 2;
                if (r == 0) {
                    w.printf("        <stop refId=\"subway_stop_%d\" departureOffset=\"00:00:00\"/>%n", r + 1);
                } else if (r == ROWS - 1) {
                    w.printf("        <stop refId=\"subway_stop_%d\" arrivalOffset=\"00:%02d:00\"/>%n", r + 1, minutes);
                } else {
                    w.printf("        <stop refId=\"subway_stop_%d\" arrivalOffset=\"00:%02d:00\" departureOffset=\"00:%02d:30\"/>%n",
                            r + 1, minutes, minutes);
                }
            }
            w.println("      </routeProfile>");
            w.println("      <route>");
            for (int r = 0; r < ROWS - 1; r++) {
                w.printf("        <link refId=\"%d\"/>%n", findVerticalLinkId(r, subwayCol));
            }
            w.println("      </route>");
            w.println("      <departures>");
            int subDepId = 1;
            int subVehicleCount = 6;
            for (int h = 6; h <= 9; h++) {
                for (int m = 0; m < 60; m += 8) {
                    w.printf("        <departure id=\"sub_dep_%d\" departureTime=\"%02d:%02d:00\" vehicleRefId=\"subway_%d\"/>%n",
                            subDepId, h, m, ((subDepId - 1) % subVehicleCount) + 1);
                    subDepId++;
                }
            }
            for (int h = 16; h <= 19; h++) {
                for (int m = 0; m < 60; m += 8) {
                    w.printf("        <departure id=\"sub_dep_%d\" departureTime=\"%02d:%02d:00\" vehicleRefId=\"subway_%d\"/>%n",
                            subDepId, h, m, ((subDepId - 1) % subVehicleCount) + 1);
                    subDepId++;
                }
            }
            w.println("      </departures>");
            w.println("    </transitRoute>");
            w.println("  </transitLine>");

            w.println("</transitSchedule>");
        }
    }

    static void writeTransitVehicles() throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(PT_DIR.resolve("dev-vehicles.xml")))) {
            w.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            w.println("<vehicleDefinitions xmlns=\"http://www.matsim.org/files/dtd\"");
            w.println("                    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"");
            w.println("                    xsi:schemaLocation=\"http://www.matsim.org/files/dtd http://www.matsim.org/files/dtd/vehicleDefinitions_v2.0.xsd\">");

            w.println("  <vehicleType id=\"bus_type\">");
            w.println("    <attributes/>");
            w.println("    <capacity seats=\"40\" standingRoomInPersons=\"20\"/>");
            w.println("    <length meter=\"12.0\"/>");
            w.println("    <width meter=\"2.5\"/>");
            w.println("    <maximumVelocity meterPerSecond=\"16.67\"/>");
            w.println("    <passengerCarEquivalents pce=\"2.5\"/>");
            w.println("  </vehicleType>");

            w.println("  <vehicleType id=\"subway_type\">");
            w.println("    <attributes/>");
            w.println("    <capacity seats=\"200\" standingRoomInPersons=\"400\"/>");
            w.println("    <length meter=\"50.0\"/>");
            w.println("    <width meter=\"3.0\"/>");
            w.println("    <maximumVelocity meterPerSecond=\"22.22\"/>");
            w.println("    <passengerCarEquivalents pce=\"0.0\"/>");
            w.println("  </vehicleType>");

            for (int i = 1; i <= 6; i++) {
                w.printf("  <vehicle id=\"bus_%d\" type=\"bus_type\"/>%n", i);
            }
            for (int i = 1; i <= 6; i++) {
                w.printf("  <vehicle id=\"subway_%d\" type=\"subway_type\"/>%n", i);
            }

            w.println("</vehicleDefinitions>");
        }
    }

    static void extendHbefa() throws IOException {
        Path warmPath = HBEFA_DIR.resolve("average_warm.csv");
        List<String> lines = Files.readAllLines(warmPath);
        boolean hasHighway = lines.stream().anyMatch(l -> l.contains("URB/MW-Nat./80"));
        if (hasHighway) return;

        String[] trafficSits = {"Freeflow", "Heavy", "Satur.", "St+Go"};
        double[] speeds = {65.3, 45.0, 25.5, 12.0};
        String[] components = {"CO", "CO2_TOTAL", "FC", "HC", "NOx", "NO2", "PM", "PM2_5"};
        double[][] carEfa = {
                {0.62, 135.0, 42.7, 0.034, 0.26, 0.045, 0.0038, 0.0025},
                {0.81, 175.5, 55.5, 0.044, 0.338, 0.058, 0.005, 0.0032},
                {1.12, 243.0, 76.8, 0.061, 0.468, 0.081, 0.0069, 0.0045},
                {1.55, 337.5, 106.7, 0.085, 0.65, 0.112, 0.0096, 0.0062}
        };
        double[][] busEfa = {
                {1.5, 620.0, 196.0, 0.15, 5.4, 1.08, 0.10, 0.066},
                {2.1, 790.0, 249.6, 0.21, 7.1, 1.42, 0.133, 0.083},
                {3.2, 1060.0, 335.0, 0.32, 9.5, 1.9, 0.182, 0.116},
                {4.6, 1450.0, 458.2, 0.46, 13.2, 2.64, 0.264, 0.174}
        };

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(warmPath, java.nio.file.StandardOpenOption.APPEND))) {
            for (int t = 0; t < trafficSits.length; t++) {
                for (int c = 0; c < components.length; c++) {
                    w.printf("PASSENGER_CAR;URB/MW-Nat./80/%s;%s;%s;%s%n",
                            trafficSits[t], components[c], carEfa[t][c], speeds[t]);
                }
                for (int c = 0; c < components.length; c++) {
                    w.printf("URBAN_BUS;URB/MW-Nat./80/%s;%s;%s;%s%n",
                            trafficSits[t], components[c], busEfa[t][c], speeds[t]);
                }
            }
        }
    }

    static void writeDevRequest() throws IOException {
        double minLon = nodes[0][0];
        double maxLon = nodes[nodeCount - 1][0];
        double minLat = nodes[0][1];
        double maxLat = nodes[nodeCount - 1][1];

        // Zone covers rows 2-6, cols 2-5 with small padding
        double pad = 0.0005;
        double zMinLon = nodes[nodeId(ZONE_ROW_MIN, ZONE_COL_MIN) - 1][0] - pad;
        double zMaxLon = nodes[nodeId(ZONE_ROW_MIN, ZONE_COL_MAX) - 1][0] + pad;
        double zMinLat = nodes[nodeId(ZONE_ROW_MIN, ZONE_COL_MIN) - 1][1] - pad;
        double zMaxLat = nodes[nodeId(ZONE_ROW_MAX, ZONE_COL_MIN) - 1][1] + pad;

        double sPad = 0.002;
        double sMinLon = minLon - sPad;
        double sMaxLon = maxLon + sPad;
        double sMinLat = minLat - sPad;
        double sMaxLat = maxLat + sPad;

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of("dev-data/dev-request.json")))) {
            w.println("{");
            w.println("  \"zones\": [");
            w.println("    {");
            w.println("      \"id\": \"4ea608bb-1066-4d22-a3b5-15f7505a3f31\",");
            w.println("      \"coords\": [");
            w.println("        [");
            w.printf("          [%.6f, %.6f],%n", zMinLon, zMinLat);
            w.printf("          [%.6f, %.6f],%n", zMaxLon, zMinLat);
            w.printf("          [%.6f, %.6f],%n", zMaxLon, zMaxLat);
            w.printf("          [%.6f, %.6f],%n", zMinLon, zMaxLat);
            w.printf("          [%.6f, %.6f]%n", zMinLon, zMinLat);
            w.println("        ]");
            w.println("      ],");
            w.println("      \"trip\": [\"start\", \"end\", \"pass\"],");
            w.println("      \"policies\": [");
            w.println("        { \"vehicleType\": \"highEmission\", \"tier\": 3, \"period\": [\"07:00\", \"19:00\"] },");
            w.println("        { \"vehicleType\": \"midEmission\", \"tier\": 2, \"period\": [\"07:00\", \"19:00\"], \"penalty\": 5, \"interval\": 10 },");
            w.println("        { \"vehicleType\": \"lowEmission\", \"tier\": 1, \"period\": [\"07:00\", \"19:00\"] },");
            w.println("        { \"vehicleType\": \"nearZeroEmission\", \"tier\": 1, \"period\": [\"07:00\", \"19:00\"] },");
            w.println("        { \"vehicleType\": \"zeroEmission\", \"tier\": 1, \"period\": [\"07:00\", \"19:00\"] }");
            w.println("      ]");
            w.println("    }");
            w.println("  ],");
            w.println("  \"customSimulationAreas\": [");
            w.println("    {");
            w.println("      \"id\": \"b2c3d4e5-6789-4abc-def0-123456789abc\",");
            w.println("      \"coords\": [");
            w.println("        [");
            w.printf("          [%.6f, %.6f],%n", sMinLon, sMinLat);
            w.printf("          [%.6f, %.6f],%n", sMaxLon, sMinLat);
            w.printf("          [%.6f, %.6f],%n", sMaxLon, sMaxLat);
            w.printf("          [%.6f, %.6f],%n", sMinLon, sMaxLat);
            w.printf("          [%.6f, %.6f]%n", sMinLon, sMinLat);
            w.println("        ]");
            w.println("      ]");
            w.println("    }");
            w.println("  ],");
            w.println("  \"scaledSimulationAreas\": [");
            w.println("    {");
            w.println("      \"id\": \"c3d4e5f6-789a-4bcd-ef01-23456789abcd\",");
            w.println("      \"zoneId\": \"4ea608bb-1066-4d22-a3b5-15f7505a3f31\",");
            w.println("      \"coords\": [");
            w.println("        [");
            w.printf("          [%.6f, %.6f],%n", sMinLon, sMinLat);
            w.printf("          [%.6f, %.6f],%n", sMaxLon, sMinLat);
            w.printf("          [%.6f, %.6f],%n", sMaxLon, sMaxLat);
            w.printf("          [%.6f, %.6f],%n", sMinLon, sMaxLat);
            w.printf("          [%.6f, %.6f]%n", sMinLon, sMinLat);
            w.println("        ]");
            w.println("      ]");
            w.println("    }");
            w.println("  ],");
            w.println("  \"sources\": {");
            w.println("    \"network\": { \"year\": 2024, \"name\": \"dev\" },");
            w.println("    \"population\": { \"year\": 2024, \"name\": \"dev\" },");
            w.println("    \"publicTransport\": { \"year\": 2024, \"name\": \"dev\" }");
            w.println("  },");
            w.println("  \"simulationOptions\": { \"iterations\": 10, \"percentage\": 10 },");
            w.println("  \"carDistribution\": {");
            w.println("    \"zeroEmission\": 20,");
            w.println("    \"nearZeroEmission\": 20,");
            w.println("    \"lowEmission\": 20,");
            w.println("    \"midEmission\": 20,");
            w.println("    \"highEmission\": 20");
            w.println("  },");
            w.println("  \"modeUtilities\": {");
            w.println("    \"walk\": 0, \"bike\": 0, \"car\": 0, \"ev\": 0, \"subway\": 0, \"bus\": 0");
            w.println("  }");
            w.println("}");
        }
    }

    static void cleanDatabases() throws IOException {
        deleteIfExists(NET_DIR.resolve("dev.mv.db"));
        deleteIfExists(NET_DIR.resolve("dev.trace.db"));
        deleteIfExists(POP_DIR.resolve("dev.mv.db"));
        deleteIfExists(POP_DIR.resolve("dev.trace.db"));
    }

    static void deleteIfExists(Path p) throws IOException {
        Files.deleteIfExists(p);
    }

    static int findHorizontalLinkId(int row, int col) {
        if (col >= COLS - 1) col = COLS - 2;
        int horizontalLinksPerRow = (COLS - 1) * 2;
        int base = row * horizontalLinksPerRow;
        return base + col * 2 + 1;
    }

    static int findVerticalLinkId(int row, int col) {
        if (row >= ROWS - 1) row = ROWS - 2;
        int totalHorizontalLinks = ROWS * (COLS - 1) * 2;
        int verticalLinksPerRow = COLS * 2;
        int base = totalHorizontalLinks + row * verticalLinksPerRow;
        return base + col * 2 + 1;
    }

    static double haversine(double[] a, double[] b) {
        double dLat = Math.toRadians(b[1] - a[1]);
        double dLon = Math.toRadians(b[0] - a[0]);
        double lat1 = Math.toRadians(a[1]);
        double lat2 = Math.toRadians(b[1]);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371000 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }
}
