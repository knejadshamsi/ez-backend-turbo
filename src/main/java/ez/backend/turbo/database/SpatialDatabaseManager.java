package ez.backend.turbo.database;

import ez.backend.turbo.utils.L;
import jakarta.annotation.PreDestroy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.h2gis.functions.factory.H2GISFunctions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SpatialDatabaseManager {

    private static final Logger log = LogManager.getLogger(SpatialDatabaseManager.class);

    private final Map<String, ManagedDatabase> databases = new ConcurrentHashMap<>();

    public record ManagedDatabase(DriverManagerDataSource dataSource, JdbcTemplate jdbcTemplate) {}

    public JdbcTemplate openOrCreate(String key, Path xmlPath) {
        return databases.computeIfAbsent(key, k -> initDatabase(k, xmlPath)).jdbcTemplate();
    }

    public boolean databaseExists(Path xmlPath) {
        Path dbPath = deriveDbPath(xmlPath);
        return Files.exists(dbPath.resolveSibling(dbPath.getFileName().toString() + ".mv.db"));
    }

    public JdbcTemplate get(String key) {
        ManagedDatabase db = databases.get(key);
        if (db == null) {
            throw new IllegalStateException(L.msg("source.spatial.db.not.open") + ": " + key);
        }
        return db.jdbcTemplate();
    }

    public void closeAndDelete(String key, Path xmlPath) {
        ManagedDatabase db = databases.remove(key);
        if (db != null) {
            try { db.jdbcTemplate().execute("SHUTDOWN"); } catch (Exception ignored) {}
        }
        Path dbPath = deriveDbPath(xmlPath);
        try {
            Files.deleteIfExists(dbPath.resolveSibling(dbPath.getFileName().toString() + ".mv.db"));
        } catch (IOException ignored) {}
    }

    @PreDestroy
    void shutdown() {
        for (Map.Entry<String, ManagedDatabase> entry : databases.entrySet()) {
            try {
                entry.getValue().jdbcTemplate().execute("SHUTDOWN");
            } catch (Exception e) {
                log.warn(L.msg("source.spatial.shutdown.failed"), entry.getKey());
                continue;
            }
            try {
                log.info(L.msg("source.spatial.db.closed"), entry.getKey());
            } catch (Exception ignored) {}
        }
        databases.clear();
    }

    private ManagedDatabase initDatabase(String key, Path xmlPath) {
        Path dbPath = deriveDbPath(xmlPath);
        String url = "jdbc:h2:file:" + dbPath.toString().replace('\\', '/') + ";DB_CLOSE_DELAY=-1";

        DriverManagerDataSource dataSource = new DriverManagerDataSource(url);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        try (Connection conn = dataSource.getConnection()) {
            H2GISFunctions.load(conn);
        } catch (Exception e) {
            throw new IllegalStateException(L.msg("source.spatial.init.failed") + ": " + key, e);
        }

        log.info(L.msg("source.spatial.init"), key);
        return new ManagedDatabase(dataSource, jdbcTemplate);
    }

    private Path deriveDbPath(Path xmlPath) {
        String filename = xmlPath.getFileName().toString();
        String base = filename.substring(0, filename.lastIndexOf('.'));
        return xmlPath.resolveSibling(base);
    }
}
