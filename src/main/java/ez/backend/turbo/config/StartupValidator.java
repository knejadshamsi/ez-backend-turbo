package ez.backend.turbo.config;

import ez.backend.turbo.services.ProcessConfig;
import ez.backend.turbo.services.ScaleFactorConfig;
import ez.backend.turbo.services.ScoringConfig;
import ez.backend.turbo.services.StrategyConfig;
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
import java.util.Arrays;
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
    private final String sourceCrs;
    private final String targetCrs;
    private final boolean computeQueueEnabled;
    private final long computeQueueTimeout;
    private final int computeQueueMaxSize;
    private final Path hbefaWarmFile;
    private final Path hbefaColdFile;
    private final Path vehicleTypesFile;
    private final ScaleFactorConfig scaleFactorConfig;
    private final ScoringConfig scoringConfig;
    private final StrategyConfig strategyConfig;

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

        this.sourceCrs = validateCrs("ez.source.crs",
                environment.getProperty("ez.source.crs"));
        this.targetCrs = validateCrs("ez.target.crs",
                environment.getProperty("ez.target.crs"));

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

        Path hbefaDir = dataRoot.resolve("input/hbefa");
        this.hbefaWarmFile = validateFilePath("ez.hbefa.warm-file",
                environment.getProperty("ez.hbefa.warm-file"), hbefaDir, ".csv");
        this.hbefaColdFile = validateFilePath("ez.hbefa.cold-file",
                environment.getProperty("ez.hbefa.cold-file"), hbefaDir, ".csv");
        this.vehicleTypesFile = validateFilePath("ez.hbefa.vehicle-types-file",
                environment.getProperty("ez.hbefa.vehicle-types-file"), hbefaDir, ".yml");

        this.scaleFactorConfig = validateScaleFactorConfig(environment);
        this.scoringConfig = validateScoringConfig(environment);
        this.strategyConfig = validateStrategyConfig(environment);

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

    public String getSourceCrs() {
        return sourceCrs;
    }

    public String getTargetCrs() {
        return targetCrs;
    }

    public Path getHbefaWarmFile() {
        return hbefaWarmFile;
    }

    public Path getHbefaColdFile() {
        return hbefaColdFile;
    }

    public Path getVehicleTypesFile() {
        return vehicleTypesFile;
    }

    public ScaleFactorConfig getScaleFactorConfig() {
        return scaleFactorConfig;
    }

    public ScoringConfig getScoringConfig() {
        return scoringConfig;
    }

    public StrategyConfig getStrategyConfig() {
        return strategyConfig;
    }

    @Override
    public void run(ApplicationArguments args) {
        verifyConnectivity();
        checkRequiredTables();
        log.info(L.msg("db.tables.verified"));
        log.info(L.msg("config.scoring.loaded"),
                scoringConfig.performingUtilsPerHr(),
                scoringConfig.brainExpBeta(),
                scoringConfig.learningRate());
        log.info(L.msg("config.scale.factors.loaded"),
                scaleFactorConfig.walk(), scaleFactorConfig.bike(), scaleFactorConfig.car(),
                scaleFactorConfig.ev(), scaleFactorConfig.subway(), scaleFactorConfig.bus());
        log.info(L.msg("config.strategy.loaded"),
                4,
                Arrays.toString(strategyConfig.subtourModes()),
                strategyConfig.globalThreads(),
                strategyConfig.qsimThreads());
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
            Files.createDirectories(resolved.resolve("input/hbefa"));
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

    private String validateCrs(String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is missing | " + key + " est manquant");
        }
        if (!value.matches("EPSG:\\d+")) {
            throw new IllegalStateException(key + " must match EPSG:<number>, got '" + value
                    + "' | " + key + " doit correspondre a EPSG:<nombre>, recu '" + value + "'");
        }
        return value;
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

    private Path validateFilePath(String key, String value, Path baseDir, String extension) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is missing | " + key + " est manquant");
        }
        if (!value.endsWith(extension)) {
            throw new IllegalStateException(key + " must end with " + extension + ", got '" + value
                    + "' | " + key + " doit se terminer par " + extension + ", reçu '" + value + "'");
        }
        Path resolved = baseDir.resolve(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalStateException(key + " file not found: " + resolved
                    + " | " + key + " fichier introuvable : " + resolved);
        }
        if (!Files.isReadable(resolved)) {
            throw new IllegalStateException(key + " file not readable: " + resolved
                    + " | " + key + " fichier illisible : " + resolved);
        }
        return resolved;
    }

    private double validateDouble(String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing value: " + key + " | Valeur manquante : " + key);
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid number format: " + key + " (got: " + value + ") | "
                    + "Format numérique invalide : " + key + " (reçu : " + value + ")", e);
        }
    }

    private double validatePositiveDouble(String key, String value) {
        double parsed = validateDouble(key, value);
        if (parsed <= 0) {
            throw new IllegalStateException("Value must be > 0: " + key + " (got: " + value + ") | "
                    + "La valeur doit être > 0 : " + key + " (reçu : " + value + ")");
        }
        return parsed;
    }

    private double validateNonNegativeDouble(String key, String value) {
        double parsed = validateDouble(key, value);
        if (parsed < 0) {
            throw new IllegalStateException("Value must be >= 0: " + key + " (got: " + value + ") | "
                    + "La valeur doit être >= 0 : " + key + " (reçu : " + value + ")");
        }
        return parsed;
    }

    private String validateNonEmptyString(String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " must not be empty | " + key + " ne doit pas être vide");
        }
        return value.trim();
    }

    private ScaleFactorConfig validateScaleFactorConfig(Environment env) {
        return new ScaleFactorConfig(
                validateDouble("ez.scoring.scale-factor.walk",
                        env.getProperty("ez.scoring.scale-factor.walk")),
                validateDouble("ez.scoring.scale-factor.bike",
                        env.getProperty("ez.scoring.scale-factor.bike")),
                validateDouble("ez.scoring.scale-factor.car",
                        env.getProperty("ez.scoring.scale-factor.car")),
                validateDouble("ez.scoring.scale-factor.ev",
                        env.getProperty("ez.scoring.scale-factor.ev")),
                validateDouble("ez.scoring.scale-factor.subway",
                        env.getProperty("ez.scoring.scale-factor.subway")),
                validateDouble("ez.scoring.scale-factor.bus",
                        env.getProperty("ez.scoring.scale-factor.bus"))
        );
    }

    private ScoringConfig validateScoringConfig(Environment env) {
        return new ScoringConfig(
                validatePositiveDouble("ez.scoring.performing-utils-per-hr",
                        env.getProperty("ez.scoring.performing-utils-per-hr")),
                validatePositiveDouble("ez.scoring.marginal-utility-of-money",
                        env.getProperty("ez.scoring.marginal-utility-of-money")),
                validatePositiveDouble("ez.scoring.brain-exp-beta",
                        env.getProperty("ez.scoring.brain-exp-beta")),
                validatePositiveDouble("ez.scoring.learning-rate",
                        env.getProperty("ez.scoring.learning-rate")),
                validateDouble("ez.scoring.car.marginal-utility-of-traveling",
                        env.getProperty("ez.scoring.car.marginal-utility-of-traveling")),
                validateDouble("ez.scoring.car.monetary-distance-rate",
                        env.getProperty("ez.scoring.car.monetary-distance-rate")),
                validateDouble("ez.scoring.pt.marginal-utility-of-traveling",
                        env.getProperty("ez.scoring.pt.marginal-utility-of-traveling")),
                validateDouble("ez.scoring.walk.marginal-utility-of-traveling",
                        env.getProperty("ez.scoring.walk.marginal-utility-of-traveling")),
                validateDouble("ez.scoring.bike.marginal-utility-of-traveling",
                        env.getProperty("ez.scoring.bike.marginal-utility-of-traveling"))
        );
    }

    private StrategyConfig validateStrategyConfig(Environment env) {
        String subtourModesRaw = validateNonEmptyString("ez.strategy.subtour-modes",
                env.getProperty("ez.strategy.subtour-modes"));
        String chainBasedModesRaw = validateNonEmptyString("ez.strategy.chain-based-modes",
                env.getProperty("ez.strategy.chain-based-modes"));

        String[] subtourModes = Arrays.stream(subtourModesRaw.split(","))
                .map(String::trim).toArray(String[]::new);
        String[] chainBasedModes = Arrays.stream(chainBasedModesRaw.split(","))
                .map(String::trim).toArray(String[]::new);

        return new StrategyConfig(
                validateNonNegativeDouble("ez.strategy.change-exp-beta-weight",
                        env.getProperty("ez.strategy.change-exp-beta-weight")),
                validateNonNegativeDouble("ez.strategy.reroute-weight",
                        env.getProperty("ez.strategy.reroute-weight")),
                validateNonNegativeDouble("ez.strategy.subtour-mode-choice-weight",
                        env.getProperty("ez.strategy.subtour-mode-choice-weight")),
                validateNonNegativeDouble("ez.strategy.time-allocation-mutator-weight",
                        env.getProperty("ez.strategy.time-allocation-mutator-weight")),
                subtourModes,
                chainBasedModes,
                validatePositiveDouble("ez.strategy.mutation-range",
                        env.getProperty("ez.strategy.mutation-range")),
                validatePositiveInteger("ez.strategy.max-agent-plan-memory-size",
                        env.getProperty("ez.strategy.max-agent-plan-memory-size")),
                validatePositiveInteger("ez.strategy.global-threads",
                        env.getProperty("ez.strategy.global-threads")),
                validatePositiveInteger("ez.strategy.qsim-threads",
                        env.getProperty("ez.strategy.qsim-threads"))
        );
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
