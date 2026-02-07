package ez.backend.turbo;

import jakarta.annotation.PostConstruct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.springframework.stereotype.Component;

@Component
public class LogConfig {

    private final boolean consoleEnabled;
    private final boolean fileEnabled;

    public LogConfig(StartupValidator startupValidator) {
        this.consoleEnabled = startupValidator.isConsoleEnabled();
        this.fileEnabled = startupValidator.isFileEnabled();
    }

    @PostConstruct
    void init() {
        if (consoleEnabled && fileEnabled) {
            return;
        }
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        if (!consoleEnabled) {
            removeAppender(config, "Console");
        }
        if (!fileEnabled) {
            removeAppender(config, "File");
        }
        ctx.updateLoggers();
    }

    private void removeAppender(Configuration config, String name) {
        if (config.getAppender(name) == null) {
            throw new IllegalStateException("Appender not found in log4j2 configuration: " + name
                    + " | Appender introuvable dans la configuration log4j2 : " + name);
        }
        config.getRootLogger().removeAppender(name);
        for (LoggerConfig logger : config.getLoggers().values()) {
            logger.removeAppender(name);
        }
    }
}
