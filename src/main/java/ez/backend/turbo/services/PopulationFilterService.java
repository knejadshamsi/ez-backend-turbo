package ez.backend.turbo.services;

import ez.backend.turbo.config.StartupValidator;
import ez.backend.turbo.endpoints.SimulationRequest;
import ez.backend.turbo.simulation.ZoneLinkResolver.ZoneLinkSet;
import ez.backend.turbo.utils.L;
import ez.backend.turbo.utils.SpatialUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class PopulationFilterService {

    private static final Logger log = LogManager.getLogger(PopulationFilterService.class);
    private static final int BATCH_SIZE = 500;
    private static final String WGS84 = "EPSG:4326";

    private final SourceRegistry sourceRegistry;
    private final String targetCrs;

    public PopulationFilterService(SourceRegistry sourceRegistry, StartupValidator startupValidator) {
        this.sourceRegistry = sourceRegistry;
        this.targetCrs = startupValidator.getTargetCrs();
    }

    public Set<String> filter(SimulationRequest request, List<ZoneLinkSet> zoneLinkSets) {
        int popYear = request.getSources().getPopulation().getYear();
        String popName = request.getSources().getPopulation().getName();
        int netYear = request.getSources().getNetwork().getYear();
        String netName = request.getSources().getNetwork().getName();
        JdbcTemplate popDb = sourceRegistry.getPopulationDb(popYear, popName);
        JdbcTemplate netDb = sourceRegistry.getNetworkDb(netYear, netName);
        CoordinateTransformation transform =
                TransformationFactory.getCoordinateTransformation(WGS84, targetCrs);

        Set<String> matched = new HashSet<>();

        List<SimulationRequest.Zone> zones = request.getZones();
        for (int i = 0; i < zones.size(); i++) {
            SimulationRequest.Zone zone = zones.get(i);
            ZoneLinkSet linkSet = zoneLinkSets.get(i);
            Set<String> linkIds = toStringIds(linkSet.allLinks());

            for (String tripType : zone.getTrip()) {
                switch (tripType) {
                    case "start" -> matched.addAll(findPersonsByStartLinks(popDb, linkIds));
                    case "end" -> matched.addAll(findPersonsByEndLinks(popDb, linkIds));
                    case "pass" -> matched.addAll(findPersonsByPassLinks(popDb, linkIds));
                }
            }

            String zoneWkt = SpatialUtils.toPolygonWkt(
                    SpatialUtils.projectRings(zone.getCoords(), transform));
            matched.addAll(findRoutelessPersonsByActivity(popDb, zoneWkt));
        }

        List<SimulationRequest.CustomSimulationArea> customAreas = request.getCustomSimulationAreas();
        if (customAreas != null) {
            for (SimulationRequest.CustomSimulationArea area : customAreas) {
                String wkt = SpatialUtils.toPolygonWkt(
                        SpatialUtils.projectRings(area.getCoords(), transform));
                Set<String> areaLinkIds = queryIntersectingLinks(netDb, wkt);
                if (!areaLinkIds.isEmpty()) {
                    matched.addAll(findPersonsByPassLinks(popDb, areaLinkIds));
                }
                matched.addAll(findRoutelessPersonsByActivity(popDb, wkt));
            }
        }

        List<SimulationRequest.ScaledSimulationArea> scaledAreas = request.getScaledSimulationAreas();
        if (scaledAreas != null) {
            for (SimulationRequest.ScaledSimulationArea area : scaledAreas) {
                String wkt = SpatialUtils.toPolygonWkt(
                        SpatialUtils.projectRings(area.getCoords(), transform));
                Set<String> areaLinkIds = queryIntersectingLinks(netDb, wkt);
                if (!areaLinkIds.isEmpty()) {
                    matched.addAll(findPersonsByPassLinks(popDb, areaLinkIds));
                }
                matched.addAll(findRoutelessPersonsByActivity(popDb, wkt));
            }
        }

        if (matched.isEmpty()) {
            throw new IllegalStateException(L.msg("population.filter.empty"));
        }

        log.info(L.msg("population.filter.result"), matched.size());
        return matched;
    }

    private Set<String> findPersonsByStartLinks(JdbcTemplate popDb, Set<String> linkIds) {
        Set<String> result = new HashSet<>();
        for (List<String> batch : partition(linkIds)) {
            String placeholders = placeholders(batch.size());
            String sql = "SELECT DISTINCT person_id FROM route_links WHERE link_order = 0 AND link_id IN ("
                    + placeholders + ")";
            result.addAll(popDb.queryForList(sql, String.class, batch.toArray()));
        }
        return result;
    }

    private Set<String> findPersonsByEndLinks(JdbcTemplate popDb, Set<String> linkIds) {
        Set<String> result = new HashSet<>();
        for (List<String> batch : partition(linkIds)) {
            String placeholders = placeholders(batch.size());
            String sql = "SELECT DISTINCT rl.person_id FROM route_links rl "
                    + "WHERE rl.link_id IN (" + placeholders + ") "
                    + "AND rl.link_order = ("
                    + "SELECT MAX(rl2.link_order) FROM route_links rl2 "
                    + "WHERE rl2.person_id = rl.person_id AND rl2.leg_seq = rl.leg_seq)";
            result.addAll(popDb.queryForList(sql, String.class, batch.toArray()));
        }
        return result;
    }

    private Set<String> findPersonsByPassLinks(JdbcTemplate popDb, Set<String> linkIds) {
        Set<String> result = new HashSet<>();
        for (List<String> batch : partition(linkIds)) {
            String placeholders = placeholders(batch.size());
            String sql = "SELECT DISTINCT person_id FROM route_links WHERE link_id IN ("
                    + placeholders + ")";
            result.addAll(popDb.queryForList(sql, String.class, batch.toArray()));
        }
        return result;
    }

    private Set<String> findRoutelessPersonsByActivity(JdbcTemplate popDb, String polygonWkt) {
        Set<String> result = new HashSet<>();
        popDb.query(
                "SELECT DISTINCT a.person_id FROM activities a "
                        + "WHERE ST_Intersects(a.geom, ST_GeomFromText(?)) "
                        + "AND NOT EXISTS ("
                        + "SELECT 1 FROM route_links rl WHERE rl.person_id = a.person_id)",
                rs -> { result.add(rs.getString("person_id")); },
                polygonWkt);
        return result;
    }

    private Set<String> queryIntersectingLinks(JdbcTemplate netDb, String polygonWkt) {
        Set<String> linkIds = new HashSet<>();
        netDb.query("SELECT link_id FROM links WHERE ST_Intersects(geom, ST_GeomFromText(?))",
                rs -> { linkIds.add(rs.getString("link_id")); },
                polygonWkt);
        return linkIds;
    }

    private Set<String> toStringIds(Set<Id<Link>> linkIds) {
        Set<String> ids = new HashSet<>(linkIds.size());
        for (Id<Link> id : linkIds) {
            ids.add(id.toString());
        }
        return ids;
    }

    private List<List<String>> partition(Set<String> items) {
        List<String> list = new ArrayList<>(items);
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < list.size(); i += BATCH_SIZE) {
            batches.add(list.subList(i, Math.min(i + BATCH_SIZE, list.size())));
        }
        return batches;
    }

    private String placeholders(int count) {
        StringBuilder sb = new StringBuilder(count * 2);
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        return sb.toString();
    }
}
