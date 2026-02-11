package ez.backend.turbo.config;

import ez.backend.turbo.services.ProcessConfig;
import ez.backend.turbo.utils.L;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class StartupValidator implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(StartupValidator.class);

    private static final List<String> REQUIRED_TABLES = List.of("scenarios");
    private static final Set<String> VALID_LOCALES = Set.of("en", "fr");
    private static final String SEPARATOR = "========================================";

    private final JdbcTemplate jdbcTemplate;
    private final String locale;
    private final boolean consoleEnabled;
    private final boolean fileEnabled;
    private final boolean autoCreateTables;
    private final String allowedOrigin;
    private final int rateLimitMaxRequests;
    private final int rateLimitBanDuration;
    private final ProcessConfig adminProcessConfig;
    private final ProcessConfig readProcessConfig;
    private final ProcessConfig computeProcessConfig;
    private final Path dataRoot;
    private final boolean computeQueueEnabled;
    private final long computeQueueTimeout;
    private final int computeQueueMaxSize;

    public StartupValidator(Environment environment, JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;

        this.dataRoot = validateDataRoot(environment.getProperty("ez.data.root"));
        this.locale = validateLocale(environment.getProperty("ez.log.locale"));
        this.consoleEnabled = validateBoolean("ez.log.console",
                environment.getProperty("ez.log.console"));
        this.fileEnabled = validateBoolean("ez.log.file",
                environment.getProperty("ez.log.file"));
        this.autoCreateTables = validateBoolean("ez.startup.auto-create-tables",
                environment.getProperty("ez.startup.auto-create-tables"));
        this.allowedOrigin = validateAllowedOrigin(
                environment.getProperty("ez.cors.allowed-origin"));

        this.rateLimitMaxRequests = validatePositiveInteger("ez.ratelimit.max-requests-per-second",
                environment.getProperty("ez.ratelimit.max-requests-per-second"));
        this.rateLimitBanDuration = validatePositiveInteger("ez.ratelimit.ban-duration-seconds",
                environment.getProperty("ez.ratelimit.ban-duration-seconds"));

        this.adminProcessConfig = validateProcessConfig("admin", environment);
        this.readProcessConfig = validateProcessConfig("read", environment);
        this.computeProcessConfig = validateProcessConfig("compute", environment);

        this.computeQueueEnabled = validateBoolean("ez.processes.compute.queue.enabled",
                environment.getProperty("ez.processes.compute.queue.enabled"));
        if (computeQueueEnabled) {
            if (computeProcessConfig.max() < 1) {
                throw new IllegalStateException("ez.processes.compute.max must be >= 1 when queue is enabled | "
                        + "ez.processes.compute.max doit être >= 1 lorsque la file d'attente est activée");
            }
            this.computeQueueTimeout = validatePositiveLong("ez.processes.compute.queue.timeout",
                    environment.getProperty("ez.processes.compute.queue.timeout"));
            this.computeQueueMaxSize = validatePositiveInteger("ez.processes.compute.queue.max-size",
                    environment.getProperty("ez.processes.compute.queue.max-size"));
        } else {
            this.computeQueueTimeout = 0;
            this.computeQueueMaxSize = 0;
        }

        if (adminProcessConfig.max() == 0 && readProcessConfig.max() == 0 && computeProcessConfig.max() == 0) {
            throw new IllegalStateException("At least one of {admin, read, compute} must have max > 0 | "
                    + "Au moins un de {admin, read, compute} doit avoir max > 0");
        }
    }

    public static boolean checkConfigFile(String path) {
        File file = new File(path);
        if (!file.exists() || !file.canRead()) {
            printErrors(List.of(
                    "File not found or not readable: " + path
                            + " | Fichier introuvable ou illisible : " + path
            ));
            return false;
        }
        return true;
    }

    public String getLocale() {
        return locale;
    }

    public boolean isConsoleEnabled() {
        return consoleEnabled;
    }

    public boolean isFileEnabled() {
        return fileEnabled;
    }

    public boolean isAutoCreateTables() {
        return autoCreateTables;
    }

    public String getAllowedOrigin() {
        return allowedOrigin;
    }

    public int getRateLimitMaxRequests() {
        return rateLimitMaxRequests;
    }

    public int getRateLimitBanDuration() {
        return rateLimitBanDuration;
    }

    public ProcessConfig getAdminProcessConfig() {
        return adminProcessConfig;
    }

    public ProcessConfig getReadProcessConfig() {
        return readProcessConfig;
    }

    public ProcessConfig getComputeProcessConfig() {
        return computeProcessConfig;
    }

    public boolean isComputeQueueEnabled() {
        return computeQueueEnabled;
    }

    public long getComputeQueueTimeout() {
        return computeQueueTimeout;
    }

    public int getComputeQueueMaxSize() {
        return computeQueueMaxSize;
    }

    public Path getDataRoot() {
        return dataRoot;
    }

    @Override
    public void run(ApplicationArguments args) {
        verifyConnectivity();
        checkRequiredTables();
        log.info(L.msg("db.tables.verified"));
    }

    private Path validateDataRoot(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("ez.data.root is missing | ez.data.root est manquant");
        }
        Path resolved = Path.of(value).toAbsolutePath().normalize();
        try {
            Files.createDirectories(resolved.resolve("db"));
            Files.createDirectories(resolved.resolve("input/population"));
            Files.createDirectories(resolved.resolve("input/network"));
            Files.createDirectories(resolved.resolve("input/publicTransport"));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create data directory: " + resolved
                    + " | Impossible de créer le répertoire de données : " + resolved, e);
        }
        return resolved;
    }

    private String validateLocale(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("ez.log.locale is missing | ez.log.locale est manquant");
        }
        if (!VALID_LOCALES.contains(value)) {
            throw new IllegalStateException("ez.log.locale must be 'en' or 'fr', got '" + value
                    + "' | ez.log.locale doit être 'en' ou 'fr', reçu '" + value + "'");
        }
        return value;
    }

    private boolean validateBoolean(String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is missing | " + key + " est manquant");
        }
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalStateException(key + " must be 'true' or 'false', got '" + value
                    + "' | " + key + " doit être 'true' ou 'false', reçu '" + value + "'");
        }
        return "true".equals(value);
    }

    private String validateAllowedOrigin(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("ez.cors.allowed-origin is missing | ez.cors.allowed-origin est manquant");
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equals(scheme) && !"https".equals(scheme))) {
                throw new IllegalStateException("ez.cors.allowed-origin must use http or https"
                        + " | ez.cors.allowed-origin doit utiliser http ou https");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalStateException("ez.cors.allowed-origin must include a host"
                        + " | ez.cors.allowed-origin doit inclure un hôte");
            }
            if (uri.getPort() == -1) {
                throw new IllegalStateException("ez.cors.allowed-origin must include a port (e.g. http://localhost:3000)"
                        + " | ez.cors.allowed-origin doit inclure un port (ex. http://localhost:3000)");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("ez.cors.allowed-origin is not a valid URL: " + value
                    + " | ez.cors.allowed-origin n'est pas une URL valide : " + value, e);
        }
        return value;
    }

    private ProcessConfig validateProcessConfig(String typeName, Environment env) {
        String maxKey = "ez.processes." + typeName + ".max";
        String timeoutKey = "ez.processes." + typeName + ".timeout";

        int max = validateNonNegativeInteger(maxKey, env.getProperty(maxKey));
        long timeout = validatePositiveLong(timeoutKey, env.getProperty(timeoutKey));

        return new ProcessConfig(max, timeout);
    }

    private int validatePositiveInteger(String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing value: " + key + " | Valeur manquante : " + key);
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) {
                throw new IllegalStateException("Value must be >= 1: " + key + " (got: " + value + ") | "
                        + "La valeur doit être >= 1 : " + key + " (reçu : " + value + ")");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid number format: " + key + " (got: " + value + ") | "
                    + "Format numérique invalide : " + key + " (reçu : " + value + ")", e);
        }
    }

    private int validateNonNegativeInteger(String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing value: " + key + " | Valeur manquante : " + key);
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new IllegalStateException("Value must be >= 0: " + key + " (got: " + value + ") | "
                        + "La valeur doit être >= 0 : " + key + " (reçu : " + value + ")");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid number format: " + key + " (got: " + value + ") | "
                    + "Format numérique invalide : " + key + " (reçu : " + value + ")", e);
        }
    }

    private long validatePositiveLong(String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing value: " + key + " | Valeur manquante : " + key);
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1) {
                throw new IllegalStateException("Value must be >= 1: " + key + " (got: " + value + ") | "
                        + "La valeur doit être >= 1 : " + key + " (reçu : " + value + ")");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid number format: " + key + " (got: " + value + ") | "
                    + "Format numérique invalide : " + key + " (reçu : " + value + ")", e);
        }
    }

    public static void printErrors(List<String> errors) {
        System.err.println(SEPARATOR);
        System.err.println("Configuration error | Erreur de configuration");
        System.err.println(SEPARATOR);
        for (String error : errors) {
            System.err.println("- " + error);
        }
        System.err.println(SEPARATOR);
        System.exit(1);
    }

    private void verifyConnectivity() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            log.info(L.msg("db.connected"));
        } catch (Exception e) {
            log.error(L.msg("db.connect.failed"), e.getMessage());
            throw new IllegalStateException(L.msg("db.connect.exception"), e);
        }
    }

    private void checkRequiredTables() {
        List<String> missing = new ArrayList<>();
        for (String table : REQUIRED_TABLES) {
            if (!tableExists(table)) {
                missing.add(table);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        if (!autoCreateTables) {
            for (String table : missing) {
                log.error(L.msg("db.table.missing"), table);
            }
            throw new IllegalStateException(L.msg("db.tables.missing.exception") + ": " + missing);
        }
        executeSchemaSql();
        for (String table : missing) {
            log.info(L.msg("db.table.created"), table);
        }
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?",
                Integer.class,
                tableName.toUpperCase()
        );
        return count != null && count > 0;
    }

    private void executeSchemaSql() {
        try {
            var resource = new ClassPathResource("schema.sql");
            String sql = resource.getContentAsString(StandardCharsets.UTF_8);
            jdbcTemplate.execute(sql);
        } catch (Exception e) {
            log.error(L.msg("db.schema.failed"), e.getMessage());
            throw new IllegalStateException(L.msg("db.schema.exception"), e);
        }
    }
}
