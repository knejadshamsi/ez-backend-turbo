package ez.backend.turbo.database;

import ez.backend.turbo.config.StartupValidator;
import ez.backend.turbo.utils.L;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

@Component
public class NetworkSpatialLoader {

    private static final Logger log = LogManager.getLogger(NetworkSpatialLoader.class);
    private static final int BATCH_SIZE = 5000;
    private static final String HBEFA_ATTR = "hbefa_road_type";

    private final SpatialDatabaseManager dbManager;
    private final CoordinateTransformation transformation;

    public NetworkSpatialLoader(SpatialDatabaseManager dbManager, StartupValidator config) {
        this.dbManager = dbManager;
        this.transformation = TransformationFactory.getCoordinateTransformation(
                config.getSourceCrs(), config.getTargetCrs());
    }

    public Network loadFromXml(Path xmlPath, String dbKey) {
        Network network = NetworkUtils.readNetwork(xmlPath.toString(),
                ConfigUtils.createConfig().network(), transformation);
        JdbcTemplate jdbc = dbManager.openOrCreate(dbKey, xmlPath);

        try {
            createTables(jdbc);
            validateHbefaTypes(network);
            insertNodes(jdbc, network);
            insertLinks(jdbc, network);
        } catch (Exception e) {
            dbManager.closeAndDelete(dbKey, xmlPath);
            throw e;
        }

        log.info(L.msg("source.network.loaded"), stripName(xmlPath),
                network.getNodes().size(), network.getLinks().size());
        log.info(L.msg("source.spatial.db.created"), dbKey);
        return network;
    }

    public Network loadFromDatabase(String dbKey, Path xmlPath) {
        JdbcTemplate jdbc = dbManager.openOrCreate(dbKey, xmlPath);
        Network network = NetworkUtils.createNetwork();
        NetworkFactory factory = network.getFactory();

        jdbc.query("SELECT node_id, x, y FROM nodes", rs -> {
            network.addNode(factory.createNode(
                    Id.createNodeId(rs.getString("node_id")),
                    new Coord(rs.getDouble("x"), rs.getDouble("y"))));
        });

        jdbc.query(
                "SELECT link_id, from_node, to_node, length, freespeed, capacity, " +
                "lanes, allowed_modes, hbefa_road_type FROM links", rs -> {
            Node from = network.getNodes().get(Id.createNodeId(rs.getString("from_node")));
            Node to = network.getNodes().get(Id.createNodeId(rs.getString("to_node")));
            Link link = factory.createLink(Id.createLinkId(rs.getString("link_id")), from, to);
            link.setLength(rs.getDouble("length"));
            link.setFreespeed(rs.getDouble("freespeed"));
            link.setCapacity(rs.getDouble("capacity"));
            link.setNumberOfLanes(rs.getDouble("lanes"));
            link.setAllowedModes(new HashSet<>(Arrays.asList(rs.getString("allowed_modes").split(","))));
            String hbefa = rs.getString("hbefa_road_type");
            if (hbefa != null) link.getAttributes().putAttribute(HBEFA_ATTR, hbefa);
            network.addLink(link);
        });

        log.info(L.msg("source.network.restored"), stripName(xmlPath),
                network.getNodes().size(), network.getLinks().size());
        return network;
    }

    private void createTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS nodes (
                    node_id VARCHAR(64) PRIMARY KEY,
                    x DOUBLE NOT NULL, y DOUBLE NOT NULL,
                    geom GEOMETRY(POINT))""");
        jdbc.execute("CREATE SPATIAL INDEX IF NOT EXISTS idx_nodes_geom ON nodes(geom)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS links (
                    link_id VARCHAR(64) PRIMARY KEY,
                    from_node VARCHAR(64) NOT NULL, to_node VARCHAR(64) NOT NULL,
                    length DOUBLE NOT NULL, freespeed DOUBLE NOT NULL,
                    capacity DOUBLE NOT NULL, lanes DOUBLE NOT NULL,
                    allowed_modes VARCHAR(255) NOT NULL,
                    hbefa_road_type VARCHAR(64),
                    geom GEOMETRY(LINESTRING))""");
        jdbc.execute("CREATE SPATIAL INDEX IF NOT EXISTS idx_links_geom ON links(geom)");
    }

    private void validateHbefaTypes(Network network) {
        List<String> missing = new ArrayList<>();
        for (Link link : network.getLinks().values()) {
            if (link.getAttributes().getAttribute(HBEFA_ATTR) == null) {
                missing.add(link.getId().toString());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    L.msg("source.network.hbefa.missing") + ": " + String.join(", ", missing));
        }
        log.info(L.msg("source.network.hbefa"), network.getLinks().size(), network.getLinks().size());
    }

    private void insertNodes(JdbcTemplate jdbc, Network network) {
        List<Node> nodes = new ArrayList<>(network.getNodes().values());
        jdbc.batchUpdate(
                "INSERT INTO nodes (node_id, x, y, geom) VALUES (?, ?, ?, ST_GeomFromText(?))",
                nodes, BATCH_SIZE,
                (ps, node) -> {
                    Coord c = node.getCoord();
                    ps.setString(1, node.getId().toString());
                    ps.setDouble(2, c.getX());
                    ps.setDouble(3, c.getY());
                    ps.setString(4, String.format("POINT(%.10f %.10f)", c.getX(), c.getY()));
                });
    }

    private void insertLinks(JdbcTemplate jdbc, Network network) {
        List<Link> links = new ArrayList<>(network.getLinks().values());
        jdbc.batchUpdate(
                "INSERT INTO links (link_id, from_node, to_node, length, freespeed, " +
                "capacity, lanes, allowed_modes, hbefa_road_type, geom) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ST_GeomFromText(?))",
                links, BATCH_SIZE,
                (ps, link) -> {
                    Coord from = link.getFromNode().getCoord();
                    Coord to = link.getToNode().getCoord();
                    ps.setString(1, link.getId().toString());
                    ps.setString(2, link.getFromNode().getId().toString());
                    ps.setString(3, link.getToNode().getId().toString());
                    ps.setDouble(4, link.getLength());
                    ps.setDouble(5, link.getFreespeed());
                    ps.setDouble(6, link.getCapacity());
                    ps.setDouble(7, link.getNumberOfLanes());
                    ps.setString(8, String.join(",", link.getAllowedModes()));
                    Object hbefa = link.getAttributes().getAttribute(HBEFA_ATTR);
                    ps.setString(9, hbefa != null ? hbefa.toString() : null);
                    ps.setString(10, String.format("LINESTRING(%.10f %.10f, %.10f %.10f)",
                            from.getX(), from.getY(), to.getX(), to.getY()));
                });
    }

    private String stripName(Path xmlPath) {
        String filename = xmlPath.getFileName().toString();
        return filename.substring(0, filename.lastIndexOf('.'));
    }
}
