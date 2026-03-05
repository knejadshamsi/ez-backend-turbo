package ez.backend.turbo.preprocess;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.NetworkCleaner;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;


import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class NetworkPreprocessor {

    private static final String OSM_HIGHWAY_ATTR = "osm:way:highway";
    private static final String HBEFA_ATTR = "hbefa_road_type";
    private static final String HBEFA_FALLBACK = "URB/Access/30";
    private static final Set<String> MANDATORY_LINK_ATTRS = Set.of(HBEFA_ATTR);

    private static final Set<String> VALUED_KEYS = Set.of(
            "--input", "--output", "--polygon", "--hbefa-map",
            "--keep-attr", "--source-crs", "--target-crs");
    private static final Set<String> FLAG_KEYS = Set.of("--no-polygon", "--no-attr");

    int execute(String[] args) {
        try {
            return run(args);
        } catch (Exception e) {
            System.err.println("Error | Erreur : " + e.getMessage());
            return 1;
        }
    }

    private int run(String[] args) {
        CliArgs cli = CliArgs.parse(args, VALUED_KEYS, FLAG_KEYS);

        String inputPath = cli.require("--input");
        String outputPath = cli.require("--output");
        String sourceCrs = cli.optional("--source-crs", "EPSG:32188");
        String targetCrs = cli.optional("--target-crs", null);

        Network network;
        if (targetCrs != null && !targetCrs.equals(sourceCrs)) {
            CoordinateTransformation transform =
                    TransformationFactory.getCoordinateTransformation(sourceCrs, targetCrs);
            network = NetworkUtils.readNetwork(inputPath,
                    ConfigUtils.createConfig().network(), transform);
        } else {
            network = NetworkUtils.readNetwork(inputPath);
        }
        System.out.println("Read network: " + network.getNodes().size() + " nodes, "
                + network.getLinks().size() + " links"
                + " | Reseau lu : " + network.getNodes().size() + " noeuds, "
                + network.getLinks().size() + " liens");

        if (!cli.hasFlag("--no-polygon")) {
            String networkCrs = (targetCrs != null) ? targetCrs : sourceCrs;
            GeometryFactory gf = new GeometryFactory();
            Polygon polygon;
            String polygonPath = cli.optional("--polygon", null);
            if (polygonPath != null) {
                polygon = GeoJsonPolygonReader.read(Path.of(polygonPath), gf);
            } else {
                polygon = MontrealBoundary.toPolygon(gf);
                System.out.println("Using built-in Montreal boundary"
                        + " | Utilisation de la frontiere de Montreal integree");
            }
            geofence(network, polygon, gf, networkCrs);
        }

        HbefaMapper mapper;
        String hbefaMapPath = cli.optional("--hbefa-map", null);
        if (hbefaMapPath != null) {
            mapper = HbefaMapper.withDefaultsAndOverrides(Path.of(hbefaMapPath));
        } else {
            mapper = HbefaMapper.withDefaults();
        }
        applyHbefaMapping(network, mapper);

        if (cli.hasFlag("--no-attr")) {
            stripAttributes(network, Set.of());
        } else {
            String keepAttrRaw = cli.optional("--keep-attr", null);
            if (keepAttrRaw != null) {
                stripAttributes(network, new HashSet<>(Arrays.asList(keepAttrRaw.split(","))));
            }
        }

        new NetworkCleaner().run(network);
        System.out.println("After cleaning: " + network.getNodes().size() + " nodes, "
                + network.getLinks().size() + " links"
                + " | Apres nettoyage : " + network.getNodes().size() + " noeuds, "
                + network.getLinks().size() + " liens");

        new NetworkWriter(network).write(outputPath);
        System.out.println("Wrote: " + outputPath + " | Ecrit : " + outputPath);
        return 0;
    }

    private void geofence(Network network, Polygon polygon,
                          GeometryFactory gf, String networkCrs) {
        Polygon projected = projectPolygon(polygon, "EPSG:4326", networkCrs, gf);

        Set<Id<Node>> outsideNodes = new HashSet<>();
        for (Node node : network.getNodes().values()) {
            Point point = gf.createPoint(
                    new Coordinate(node.getCoord().getX(), node.getCoord().getY()));
            if (!projected.contains(point)) {
                outsideNodes.add(node.getId());
            }
        }

        Set<Id<Link>> linksToRemove = new HashSet<>();
        for (Link link : network.getLinks().values()) {
            if (outsideNodes.contains(link.getFromNode().getId())
                    && outsideNodes.contains(link.getToNode().getId())) {
                linksToRemove.add(link.getId());
            }
        }
        for (Id<Link> id : linksToRemove) {
            network.removeLink(id);
        }

        Set<Id<Node>> nodesToRemove = new HashSet<>();
        for (Node node : network.getNodes().values()) {
            if (node.getInLinks().isEmpty() && node.getOutLinks().isEmpty()) {
                nodesToRemove.add(node.getId());
            }
        }
        for (Id<Node> id : nodesToRemove) {
            network.removeNode(id);
        }

        System.out.println("After geofence: " + network.getNodes().size() + " nodes, "
                + network.getLinks().size() + " links"
                + " | Apres geocloture : " + network.getNodes().size() + " noeuds, "
                + network.getLinks().size() + " liens");
    }

    private void applyHbefaMapping(Network network, HbefaMapper mapper) {
        List<String> unmapped = new ArrayList<>();
        int mapped = 0;
        int fallback = 0;

        for (Link link : network.getLinks().values()) {
            if (link.getAttributes().getAttribute(HBEFA_ATTR) != null) continue;

            Object osmHighway = link.getAttributes().getAttribute(OSM_HIGHWAY_ATTR);
            if (osmHighway == null) {
                link.getAttributes().putAttribute(HBEFA_ATTR, HBEFA_FALLBACK);
                fallback++;
                continue;
            }
            String hbefaType = mapper.map(osmHighway.toString());
            if (hbefaType == null) {
                unmapped.add(link.getId().toString());
                continue;
            }
            link.getAttributes().putAttribute(HBEFA_ATTR, hbefaType);
            mapped++;
        }

        System.out.println("HBEFA mapped: " + mapped + " links, " + fallback + " fallback"
                + " | HBEFA mappe : " + mapped + " liens, " + fallback + " par defaut");
        if (!unmapped.isEmpty()) {
            System.err.println("WARNING: " + unmapped.size()
                    + " links without HBEFA mapping"
                    + " | AVERTISSEMENT : " + unmapped.size()
                    + " liens sans mappage HBEFA");
            int show = Math.min(unmapped.size(), 20);
            System.err.println("  " + String.join(", ", unmapped.subList(0, show))
                    + (unmapped.size() > 20 ? " ..." : ""));
        }
    }

    private void stripAttributes(Network network, Set<String> keepSet) {
        for (Link link : network.getLinks().values()) {
            Set<String> toRemove = new HashSet<>(link.getAttributes().getAsMap().keySet());
            toRemove.removeAll(MANDATORY_LINK_ATTRS);
            toRemove.removeAll(keepSet);
            for (String attr : toRemove) {
                link.getAttributes().removeAttribute(attr);
            }
        }
        for (Node node : network.getNodes().values()) {
            Set<String> toRemove = new HashSet<>(node.getAttributes().getAsMap().keySet());
            toRemove.removeAll(keepSet);
            for (String attr : toRemove) {
                node.getAttributes().removeAttribute(attr);
            }
        }
    }

    private Polygon projectPolygon(Polygon polygon, String fromCrs, String toCrs,
                                   GeometryFactory gf) {
        CoordinateTransformation transform =
                TransformationFactory.getCoordinateTransformation(fromCrs, toCrs);
        Coordinate[] coords = polygon.getExteriorRing().getCoordinates();
        Coordinate[] projected = new Coordinate[coords.length];
        for (int i = 0; i < coords.length; i++) {
            Coord p = transform.transform(new Coord(coords[i].x, coords[i].y));
            projected[i] = new Coordinate(p.getX(), p.getY());
        }
        return gf.createPolygon(projected);
    }
}
