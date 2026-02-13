package ez.backend.turbo.simulation;

import ez.backend.turbo.config.StartupValidator;
import ez.backend.turbo.endpoints.SimulationRequest;
import ez.backend.turbo.services.SourceRegistry;
import ez.backend.turbo.utils.SpatialUtils;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ZoneLinkResolver {

    private static final String WGS84 = "EPSG:4326";

    private final SourceRegistry sourceRegistry;
    private final String targetCrs;
    private final boolean overlapFirstWins;
    private final GeometryFactory geometryFactory = new GeometryFactory();

    public ZoneLinkResolver(StartupValidator startupValidator, SourceRegistry sourceRegistry) {
        this.sourceRegistry = sourceRegistry;
        this.targetCrs = startupValidator.getTargetCrs();
        this.overlapFirstWins = startupValidator.isOverlapFirstWins();
    }

    public record ZoneLinkSet(
            String zoneId,
            Set<Id<Link>> allLinks,
            Set<Id<Link>> entryGateways,
            Set<Id<Link>> exitGateways,
            Set<Id<Link>> interiorLinks
    ) {}

    public List<ZoneLinkSet> resolve(List<SimulationRequest.Zone> zones,
                                     int networkYear, String networkName) {
        CoordinateTransformation transform =
                TransformationFactory.getCoordinateTransformation(WGS84, targetCrs);
        JdbcTemplate networkDb = sourceRegistry.getNetworkDb(networkYear, networkName);
        Network network = sourceRegistry.getNetwork(networkYear, networkName);

        List<ProjectedZone> projectedZones = new ArrayList<>(zones.size());
        Map<String, Set<String>> rawLinkSets = new LinkedHashMap<>();

        for (SimulationRequest.Zone zone : zones) {
            List<double[][]> rings = SpatialUtils.projectRings(zone.getCoords(), transform);
            String wkt = SpatialUtils.toPolygonWkt(rings);
            Polygon polygon = toJtsPolygon(rings);
            Set<String> linkIds = queryIntersectingLinks(networkDb, wkt);
            rawLinkSets.put(zone.getId(), linkIds);
            projectedZones.add(new ProjectedZone(zone.getId(), polygon));
        }

        resolveOverlaps(rawLinkSets);

        List<ZoneLinkSet> results = new ArrayList<>(zones.size());
        for (ProjectedZone pz : projectedZones) {
            results.add(classifyLinks(pz, rawLinkSets.get(pz.zoneId), network));
        }
        return results;
    }

    private record ProjectedZone(String zoneId, Polygon polygon) {}

    private Polygon toJtsPolygon(List<double[][]> rings) {
        LinearRing shell = toLinearRing(rings.get(0));
        LinearRing[] holes = new LinearRing[rings.size() - 1];
        for (int i = 1; i < rings.size(); i++) {
            holes[i - 1] = toLinearRing(rings.get(i));
        }
        return geometryFactory.createPolygon(shell, holes);
    }

    private LinearRing toLinearRing(double[][] ring) {
        Coordinate[] coords = new Coordinate[ring.length];
        for (int i = 0; i < ring.length; i++) {
            coords[i] = new Coordinate(ring[i][0], ring[i][1]);
        }
        return geometryFactory.createLinearRing(coords);
    }

    private Set<String> queryIntersectingLinks(JdbcTemplate db, String polygonWkt) {
        Set<String> linkIds = new HashSet<>();
        db.query("SELECT link_id FROM links WHERE ST_Intersects(geom, ST_GeomFromText(?))",
                rs -> { linkIds.add(rs.getString("link_id")); },
                polygonWkt);
        return linkIds;
    }

    private void resolveOverlaps(Map<String, Set<String>> rawLinkSets) {
        Set<String> claimed = new HashSet<>();
        List<String> order = new ArrayList<>(rawLinkSets.keySet());
        if (!overlapFirstWins) {
            Collections.reverse(order);
        }
        for (String zoneId : order) {
            Set<String> links = rawLinkSets.get(zoneId);
            links.removeAll(claimed);
            claimed.addAll(links);
        }
    }

    private ZoneLinkSet classifyLinks(ProjectedZone pz, Set<String> linkIdStrings, Network network) {
        Set<Id<Link>> allLinks = new HashSet<>();
        Set<Id<Link>> entryGateways = new HashSet<>();
        Set<Id<Link>> exitGateways = new HashSet<>();
        Set<Id<Link>> interiorLinks = new HashSet<>();

        for (String idStr : linkIdStrings) {
            Id<Link> linkId = Id.createLinkId(idStr);
            Link link = network.getLinks().get(linkId);
            if (link == null) continue;

            allLinks.add(linkId);
            boolean fromInside = isInside(link.getFromNode(), pz.polygon);
            boolean toInside = isInside(link.getToNode(), pz.polygon);

            if (!fromInside && toInside) {
                entryGateways.add(linkId);
            } else if (fromInside && !toInside) {
                exitGateways.add(linkId);
            } else if (fromInside) {
                interiorLinks.add(linkId);
            } else {
                // Both endpoints outside but link crosses zone
                entryGateways.add(linkId);
                exitGateways.add(linkId);
            }
        }

        return new ZoneLinkSet(pz.zoneId, allLinks, entryGateways, exitGateways, interiorLinks);
    }

    private boolean isInside(Node node, Polygon polygon) {
        Coord c = node.getCoord();
        return polygon.contains(
                geometryFactory.createPoint(new Coordinate(c.getX(), c.getY())));
    }
}
