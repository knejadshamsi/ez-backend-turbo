package ez.backend.turbo.database;

import ez.backend.turbo.config.StartupValidator;
import ez.backend.turbo.utils.L;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class PopulationSpatialLoader {

    private static final Logger log = LogManager.getLogger(PopulationSpatialLoader.class);
    private static final int BATCH_SIZE = 1000;

    private final SpatialDatabaseManager dbManager;
    private final CoordinateTransformation transformation;

    public PopulationSpatialLoader(SpatialDatabaseManager dbManager, StartupValidator config) {
        this.dbManager = dbManager;
        this.transformation = TransformationFactory.getCoordinateTransformation(
                config.getSourceCrs(), config.getTargetCrs());
    }

    public int loadFromXml(Path xmlPath, String dbKey) {
        JdbcTemplate jdbc = dbManager.openOrCreate(dbKey, xmlPath);
        int personCount;
        try {
            createTables(jdbc);
            personCount = streamPopulation(jdbc, xmlPath);
            jdbc.execute("CREATE SPATIAL INDEX IF NOT EXISTS idx_activities_geom ON activities(geom)");
        } catch (Exception e) {
            dbManager.closeAndDelete(dbKey, xmlPath);
            throw new IllegalStateException(L.msg("source.population.load.failed") + ": " + xmlPath, e);
        }

        log.info(L.msg("source.population.loaded"), stripName(xmlPath), personCount);
        log.info(L.msg("source.spatial.db.created"), dbKey);
        return personCount;
    }

    public void openExisting(String dbKey, Path xmlPath) {
        dbManager.openOrCreate(dbKey, xmlPath);
        Integer count = dbManager.get(dbKey).queryForObject(
                "SELECT COUNT(*) FROM persons", Integer.class);
        log.info(L.msg("source.population.restored"), stripName(xmlPath), count);
    }

    private int streamPopulation(JdbcTemplate jdbc, Path xmlPath) throws Exception {
        int personCount = 0;
        List<PersonRecord> batch = new ArrayList<>(BATCH_SIZE);

        try (InputStream in = new BufferedInputStream(Files.newInputStream(xmlPath))) {
            XMLStreamReader reader = XMLInputFactory.newInstance().createXMLStreamReader(in);
            PersonRecord current = null;
            boolean inSelectedPlan = false;
            int seq = 0;

            while (reader.hasNext()) {
                int event = reader.next();

                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "person" -> {
                            current = new PersonRecord(reader.getAttributeValue(null, "id"));
                            inSelectedPlan = false;
                            seq = 0;
                        }
                        case "plan" -> {
                            String selected = reader.getAttributeValue(null, "selected");
                            inSelectedPlan = "yes".equals(selected);
                        }
                        case "activity" -> {
                            if (current != null && inSelectedPlan) {
                                double x = Double.parseDouble(reader.getAttributeValue(null, "x"));
                                double y = Double.parseDouble(reader.getAttributeValue(null, "y"));
                                Coord projected = transformation.transform(new Coord(x, y));
                                String endTimeStr = reader.getAttributeValue(null, "end_time");
                                Double endTime = endTimeStr != null ? parseTime(endTimeStr) : null;
                                current.activities.add(new ActivityRecord(
                                        seq++, reader.getAttributeValue(null, "type"),
                                        projected.getX(), projected.getY(), endTime));
                            }
                        }
                        case "leg" -> {
                            if (current != null && inSelectedPlan) {
                                current.legs.add(new LegRecord(seq++,
                                        reader.getAttributeValue(null, "mode")));
                            }
                        }
                        case "route" -> {
                            if (current != null && inSelectedPlan) {
                                String routeType = reader.getAttributeValue(null, "type");
                                if ("links".equals(routeType)) {
                                    String text = reader.getElementText().trim();
                                    if (!text.isEmpty()) {
                                        String[] linkIds = text.split("\\s+");
                                        int legSeq = current.legs.isEmpty() ? 0
                                                : current.legs.get(current.legs.size() - 1).seq;
                                        for (int i = 0; i < linkIds.length; i++) {
                                            current.routeLinks.add(new RouteLinkRecord(
                                                    legSeq, i, linkIds[i]));
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT
                        && "person".equals(reader.getLocalName()) && current != null) {
                    batch.add(current);
                    personCount++;
                    if (batch.size() == BATCH_SIZE) {
                        flushBatch(jdbc, batch);
                        batch.clear();
                    }
                    current = null;
                }
            }
            reader.close();
        }

        if (!batch.isEmpty()) flushBatch(jdbc, batch);
        return personCount;
    }

    private void createTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS persons (
                    person_id VARCHAR(128) PRIMARY KEY)""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS activities (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    person_id VARCHAR(128) NOT NULL,
                    seq INT NOT NULL,
                    activity_type VARCHAR(64) NOT NULL,
                    x DOUBLE NOT NULL, y DOUBLE NOT NULL,
                    end_time DOUBLE,
                    geom GEOMETRY(POINT))""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_activities_person ON activities(person_id)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS legs (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    person_id VARCHAR(128) NOT NULL,
                    seq INT NOT NULL,
                    mode VARCHAR(32) NOT NULL)""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_legs_person ON legs(person_id)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS route_links (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    person_id VARCHAR(128) NOT NULL,
                    leg_seq INT NOT NULL,
                    link_order INT NOT NULL,
                    link_id VARCHAR(64) NOT NULL)""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_route_links_person ON route_links(person_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_route_links_link ON route_links(link_id)");
    }

    private void flushBatch(JdbcTemplate jdbc, List<PersonRecord> persons) {
        jdbc.batchUpdate("INSERT INTO persons (person_id) VALUES (?)",
                persons, persons.size(),
                (ps, p) -> ps.setString(1, p.id));

        List<Object[]> activities = new ArrayList<>();
        List<Object[]> legs = new ArrayList<>();
        List<Object[]> routeLinks = new ArrayList<>();

        for (PersonRecord p : persons) {
            for (ActivityRecord a : p.activities) {
                activities.add(new Object[]{
                        p.id, a.seq, a.type, a.x, a.y, a.endTime,
                        String.format("POINT(%.10f %.10f)", a.x, a.y)});
            }
            for (LegRecord l : p.legs) {
                legs.add(new Object[]{p.id, l.seq, l.mode});
            }
            for (RouteLinkRecord r : p.routeLinks) {
                routeLinks.add(new Object[]{p.id, r.legSeq, r.linkOrder, r.linkId});
            }
        }

        if (!activities.isEmpty()) {
            jdbc.batchUpdate(
                    "INSERT INTO activities (person_id, seq, activity_type, x, y, end_time, geom) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ST_GeomFromText(?))", activities);
        }
        if (!legs.isEmpty()) {
            jdbc.batchUpdate(
                    "INSERT INTO legs (person_id, seq, mode) VALUES (?, ?, ?)", legs);
        }
        if (!routeLinks.isEmpty()) {
            jdbc.batchUpdate(
                    "INSERT INTO route_links (person_id, leg_seq, link_order, link_id) " +
                    "VALUES (?, ?, ?, ?)", routeLinks);
        }
    }

    private Double parseTime(String timeStr) {
        if (timeStr.contains(":")) {
            String[] parts = timeStr.split(":");
            return Double.parseDouble(parts[0]) * 3600
                    + Double.parseDouble(parts[1]) * 60
                    + Double.parseDouble(parts[2]);
        }
        return Double.parseDouble(timeStr);
    }

    private String stripName(Path xmlPath) {
        String filename = xmlPath.getFileName().toString();
        return filename.substring(0, filename.lastIndexOf('.'));
    }

    private record PersonRecord(String id, List<ActivityRecord> activities,
                                List<LegRecord> legs, List<RouteLinkRecord> routeLinks) {
        PersonRecord(String id) {
            this(id, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }

    private record ActivityRecord(int seq, String type, double x, double y, Double endTime) {}
    private record LegRecord(int seq, String mode) {}
    private record RouteLinkRecord(int legSeq, int linkOrder, String linkId) {}
}
