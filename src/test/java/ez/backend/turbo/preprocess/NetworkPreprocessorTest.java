package ez.backend.turbo.preprocess;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NetworkPreprocessorTest {

    @TempDir
    static Path tempDir;

    static Path networkFile;
    static Path polygonFile;

    // Inside polygon (EPSG:32188 coords within Montreal)
    // n1(296k,5046k) n2(300k,5046k) n3(304k,5046k)
    // n4(296k,5050k) n5(300k,5050k) n6(304k,5050k)
    static final Set<String> INSIDE_NODES = Set.of("n1", "n2", "n3", "n4", "n5", "n6");

    // Outside polygon (far south-west)
    // n7(250k,5000k) n8(260k,5000k) n9(250k,5010k) n10(260k,5010k)
    static final Set<String> OUTSIDE_NODES = Set.of("n7", "n8", "n9", "n10");

    // Disconnected (inside polygon coordinates but no links)
    static final Set<String> DISCONNECTED_NODES = Set.of("n11", "n12");

    // Links between inside nodes only (bidi pairs)
    // L1/L2: n1↔n2 residential, L3/L4: n2↔n3 secondary, L5/L6: n4↔n5 motorway
    // L7/L8: n5↔n6 residential, L9/L10: n1↔n4 tertiary, L11/L12: n2↔n5 residential
    // L13/L14: n3↔n6 residential
    static final Set<String> INSIDE_LINKS = Set.of(
            "L1", "L2", "L3", "L4", "L5", "L6", "L7", "L8",
            "L9", "L10", "L11", "L12", "L13", "L14");

    // Links between outside nodes only (both endpoints outside)
    // L15/L16: n7↔n8, L17/L18: n9↔n10, L19/L20: n7↔n9
    static final Set<String> OUTSIDE_LINKS = Set.of("L15", "L16", "L17", "L18", "L19", "L20");

    // Cross-boundary links (one endpoint inside, one outside)
    // L21/L22: n1↔n7
    static final Set<String> CROSS_LINKS = Set.of("L21", "L22");

    // Expected HBEFA mappings per osm:way:highway
    static final Map<String, String> EXPECTED_HBEFA = Map.of(
            "residential", "URB/Access/30",
            "secondary", "URB/Distr/50",
            "motorway", "URB/MW-Nat./80",
            "tertiary", "URB/Local/50");

    @BeforeAll
    static void buildFixtures() throws IOException {
        Network network = NetworkUtils.createNetwork();
        NetworkFactory nf = network.getFactory();

        double[][] inside = {
                {296000, 5046000}, {300000, 5046000}, {304000, 5046000},
                {296000, 5050000}, {300000, 5050000}, {304000, 5050000}
        };
        for (int i = 0; i < inside.length; i++) {
            network.addNode(nf.createNode(Id.createNodeId("n" + (i + 1)),
                    new Coord(inside[i][0], inside[i][1])));
        }

        double[][] outside = {
                {250000, 5000000}, {260000, 5000000},
                {250000, 5010000}, {260000, 5010000}
        };
        for (int i = 0; i < outside.length; i++) {
            network.addNode(nf.createNode(Id.createNodeId("n" + (i + 7)),
                    new Coord(outside[i][0], outside[i][1])));
        }

        network.addNode(nf.createNode(Id.createNodeId("n11"), new Coord(298000, 5048000)));
        network.addNode(nf.createNode(Id.createNodeId("n12"), new Coord(299000, 5048000)));

        int id = 1;
        id = addBidiLink(network, nf, id, "n1", "n2", "residential", "Rue A");
        id = addBidiLink(network, nf, id, "n2", "n3", "secondary", "Boul B");
        id = addBidiLink(network, nf, id, "n4", "n5", "motorway", "A-40");
        id = addBidiLink(network, nf, id, "n5", "n6", "residential", "Rue C");
        id = addBidiLink(network, nf, id, "n1", "n4", "tertiary", "Rue D");
        id = addBidiLink(network, nf, id, "n2", "n5", "residential", "Rue E");
        id = addBidiLink(network, nf, id, "n3", "n6", "residential", "Rue F");
        id = addBidiLink(network, nf, id, "n7", "n8", "residential", "Far St");
        id = addBidiLink(network, nf, id, "n9", "n10", "residential", "Far Ave");
        id = addBidiLink(network, nf, id, "n7", "n9", "residential", "Far Rd");
        addBidiLink(network, nf, id, "n1", "n7", "secondary", "Bridge Rd");

        networkFile = tempDir.resolve("test-network.xml");
        new NetworkWriter(network).write(networkFile.toString());

        // WGS84 polygon covering Montreal but not the far south-west outside nodes
        polygonFile = tempDir.resolve("polygon.geojson");
        Files.writeString(polygonFile, """
                {"type":"Polygon","coordinates":[[[-74.1,45.3],[-73.3,45.3],[-73.3,45.8],[-74.1,45.8],[-74.1,45.3]]]}""");
    }

    private static int addBidiLink(Network network, NetworkFactory nf, int id,
                                   String fromId, String toId, String highway, String name) {
        Node from = network.getNodes().get(Id.createNodeId(fromId));
        Node to = network.getNodes().get(Id.createNodeId(toId));
        double length = NetworkUtils.getEuclideanDistance(from.getCoord(), to.getCoord());

        for (int dir = 0; dir < 2; dir++) {
            Node a = dir == 0 ? from : to;
            Node b = dir == 0 ? to : from;
            Link link = nf.createLink(Id.createLinkId("L" + (id + dir)), a, b);
            link.setLength(length);
            link.setFreespeed(13.89);
            link.setCapacity(1000);
            link.setNumberOfLanes(1);
            link.setAllowedModes(Set.of("car"));
            link.getAttributes().putAttribute("osm:way:highway", highway);
            link.getAttributes().putAttribute("osm:way:name", name);
            link.getAttributes().putAttribute("sidewalk", 1.0);
            network.addLink(link);
        }
        return id + 2;
    }

    @Test
    void basicReadWritePreservesAllNodesAndLinks() {
        Path output = tempDir.resolve("out-basic.xml");
        int code = new NetworkPreprocessor().execute(new String[]{
                "--input", networkFile.toString(),
                "--output", output.toString(),
                "--no-polygon"});
        assertEquals(0, code);
        Network result = NetworkUtils.readNetwork(output.toString());
        int expectedNodes = INSIDE_NODES.size() + OUTSIDE_NODES.size();
        assertEquals(expectedNodes, result.getNodes().size(),
                "NetworkCleaner removes disconnected n11,n12 but keeps all connected nodes");
        int expectedLinks = INSIDE_LINKS.size() + OUTSIDE_LINKS.size() + CROSS_LINKS.size();
        assertEquals(expectedLinks, result.getLinks().size());
    }

    @Test
    void geofenceRemovesCorrectNodesAndLinks() {
        Path output = tempDir.resolve("out-geofence.xml");
        int code = new NetworkPreprocessor().execute(new String[]{
                "--input", networkFile.toString(),
                "--output", output.toString(),
                "--polygon", polygonFile.toString()});
        assertEquals(0, code);
        Network result = NetworkUtils.readNetwork(output.toString());

        // All 6 inside nodes must survive
        for (String nodeId : INSIDE_NODES) {
            assertNotNull(result.getNodes().get(Id.createNodeId(nodeId)),
                    "Inside node " + nodeId + " should survive geofence");
        }

        // n8, n9, n10 have no link to any inside node after geofence removes
        // outside-only links → they become orphaned → removed
        for (String nodeId : Set.of("n8", "n9", "n10")) {
            assertNull(result.getNodes().get(Id.createNodeId(nodeId)),
                    "Outside node " + nodeId + " should be removed (orphaned after geofence)");
        }

        // Disconnected nodes n11, n12 removed (no links at all)
        for (String nodeId : DISCONNECTED_NODES) {
            assertNull(result.getNodes().get(Id.createNodeId(nodeId)),
                    "Disconnected node " + nodeId + " should be removed");
        }

        // All outside-only links must be removed (both endpoints outside polygon)
        for (String linkId : OUTSIDE_LINKS) {
            assertNull(result.getLinks().get(Id.createLinkId(linkId)),
                    "Outside link " + linkId + " should be removed by geofence");
        }

        // All inside-only links must survive
        for (String linkId : INSIDE_LINKS) {
            assertNotNull(result.getLinks().get(Id.createLinkId(linkId)),
                    "Inside link " + linkId + " should survive geofence");
        }
    }

    @Test
    void noPolygonPreservesAllConnectedNodes() {
        Path output = tempDir.resolve("out-nopoly.xml");
        int code = new NetworkPreprocessor().execute(new String[]{
                "--input", networkFile.toString(),
                "--output", output.toString(),
                "--no-polygon"});
        assertEquals(0, code);
        Network result = NetworkUtils.readNetwork(output.toString());

        for (String nodeId : INSIDE_NODES) {
            assertNotNull(result.getNodes().get(Id.createNodeId(nodeId)));
        }
        for (String nodeId : OUTSIDE_NODES) {
            assertNotNull(result.getNodes().get(Id.createNodeId(nodeId)));
        }
        // Disconnected nodes removed by NetworkCleaner
        for (String nodeId : DISCONNECTED_NODES) {
            assertNull(result.getNodes().get(Id.createNodeId(nodeId)),
                    "Disconnected " + nodeId + " removed by NetworkCleaner");
        }
    }

    @Test
    void hbefaMappingProducesCorrectTypes() {
        Path output = tempDir.resolve("out-hbefa.xml");
        int code = new NetworkPreprocessor().execute(new String[]{
                "--input", networkFile.toString(),
                "--output", output.toString(),
                "--no-polygon"});
        assertEquals(0, code);
        Network result = NetworkUtils.readNetwork(output.toString());

        for (Link link : result.getLinks().values()) {
            String osmHighway = (String) link.getAttributes().getAttribute("osm:way:highway");
            String hbefa = (String) link.getAttributes().getAttribute("hbefa_road_type");
            assertNotNull(hbefa, "Link " + link.getId() + " missing hbefa_road_type");
            String expected = EXPECTED_HBEFA.get(osmHighway);
            assertEquals(expected, hbefa,
                    "Link " + link.getId() + " with highway=" + osmHighway
                            + " should map to " + expected + " but got " + hbefa);
        }
    }

    @Test
    void noAttrStripsAllExceptMandatory() {
        Path output = tempDir.resolve("out-noattr.xml");
        int code = new NetworkPreprocessor().execute(new String[]{
                "--input", networkFile.toString(),
                "--output", output.toString(),
                "--no-polygon", "--no-attr"});
        assertEquals(0, code);
        Network result = NetworkUtils.readNetwork(output.toString());

        for (Link link : result.getLinks().values()) {
            assertNotNull(link.getAttributes().getAttribute("hbefa_road_type"),
                    "Mandatory hbefa_road_type must be preserved on " + link.getId());
            assertNull(link.getAttributes().getAttribute("osm:way:highway"),
                    "osm:way:highway should be stripped from " + link.getId());
            assertNull(link.getAttributes().getAttribute("osm:way:name"),
                    "osm:way:name should be stripped from " + link.getId());
            assertNull(link.getAttributes().getAttribute("sidewalk"),
                    "sidewalk should be stripped from " + link.getId());
        }

        for (Node node : result.getNodes().values()) {
            assertTrue(node.getAttributes().getAsMap().isEmpty(),
                    "Node " + node.getId() + " should have no attributes after --no-attr");
        }
    }

    @Test
    void keepAttrPreservesOnlyNamedAndMandatory() {
        Path output = tempDir.resolve("out-keepattr.xml");
        int code = new NetworkPreprocessor().execute(new String[]{
                "--input", networkFile.toString(),
                "--output", output.toString(),
                "--no-polygon", "--keep-attr", "osm:way:name"});
        assertEquals(0, code);
        Network result = NetworkUtils.readNetwork(output.toString());

        for (Link link : result.getLinks().values()) {
            assertNotNull(link.getAttributes().getAttribute("hbefa_road_type"),
                    "Mandatory hbefa_road_type must survive on " + link.getId());
            assertNotNull(link.getAttributes().getAttribute("osm:way:name"),
                    "Kept attribute osm:way:name must survive on " + link.getId());
            assertNull(link.getAttributes().getAttribute("osm:way:highway"),
                    "Non-kept osm:way:highway should be stripped from " + link.getId());
            assertNull(link.getAttributes().getAttribute("sidewalk"),
                    "Non-kept sidewalk should be stripped from " + link.getId());
        }
    }

    @Test
    void linkPropertiesPreservedAfterProcessing() {
        Path output = tempDir.resolve("out-props.xml");
        int code = new NetworkPreprocessor().execute(new String[]{
                "--input", networkFile.toString(),
                "--output", output.toString(),
                "--no-polygon"});
        assertEquals(0, code);
        Network result = NetworkUtils.readNetwork(output.toString());

        for (Link link : result.getLinks().values()) {
            assertTrue(link.getLength() > 0, "Link " + link.getId() + " length must be positive");
            assertEquals(13.89, link.getFreespeed(), 0.01,
                    "Link " + link.getId() + " freespeed must be preserved");
            assertEquals(1000.0, link.getCapacity(), 0.01,
                    "Link " + link.getId() + " capacity must be preserved");
            assertEquals(1.0, link.getNumberOfLanes(), 0.01,
                    "Link " + link.getId() + " lanes must be preserved");
            assertTrue(link.getAllowedModes().contains("car"),
                    "Link " + link.getId() + " must allow car mode");
        }
    }

    @Test
    void customHbefaCsvOverridesDefaults(@TempDir Path csvDir) throws IOException {
        Path csv = csvDir.resolve("custom.csv");
        Files.writeString(csv, "osm_highway,hbefa_road_type\nresidential,URB/Residential/40\n");

        Path output = tempDir.resolve("out-csv-hbefa.xml");
        int code = new NetworkPreprocessor().execute(new String[]{
                "--input", networkFile.toString(),
                "--output", output.toString(),
                "--no-polygon", "--hbefa-map", csv.toString()});
        assertEquals(0, code);
        Network result = NetworkUtils.readNetwork(output.toString());

        for (Link link : result.getLinks().values()) {
            String osmHighway = (String) link.getAttributes().getAttribute("osm:way:highway");
            String hbefa = (String) link.getAttributes().getAttribute("hbefa_road_type");
            if ("residential".equals(osmHighway)) {
                assertEquals("URB/Residential/40", hbefa,
                        "CSV override should change residential mapping");
            } else if ("motorway".equals(osmHighway)) {
                assertEquals("URB/MW-Nat./80", hbefa,
                        "Non-overridden motorway should use built-in default");
            }
        }
    }

    @Test
    void missingInputReturnsError() {
        int code = new NetworkPreprocessor().execute(new String[]{
                "--output", tempDir.resolve("missing.xml").toString()});
        assertEquals(1, code);
    }

    @Test
    void missingOutputReturnsError() {
        int code = new NetworkPreprocessor().execute(new String[]{
                "--input", networkFile.toString()});
        assertEquals(1, code);
    }
}
