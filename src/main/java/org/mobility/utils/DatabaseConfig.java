package org.mobility.utils;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Configuration
public class DatabaseConfig {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseConfig.class);

    @Value("${POSTGRES_HOST:localhost}")
    private String host;

    @Value("${POSTGRES_PORT:5432}")
    private String port;

    @Value("${POSTGRES_DB:ezdb}")
    private String database;

    @Value("${POSTGRES_USER:postgres}")
    private String username;

    @Value("${POSTGRES_PASSWORD:yourpassword}")
    private String password;

    @Bean
    public DataSource dataSource() {
        HikariConfig config = new HikariConfig();
        String jdbcUrl = String.format("jdbc:postgresql://%s:%s/%s", host, port, database);
        
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("org.postgresql.Driver");
        
        config.setMaximumPoolSize(20);
        config.setMinimumIdle(5);
        config.setConnectionTimeout(10000);
        config.setIdleTimeout(30000);
        config.setMaxLifetime(2000000);
        config.setPoolName("MATSimConnectionPool");
        
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");

        logger.info("Configuring database connection: {}", jdbcUrl);

        return new HikariDataSource(config);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout(10);

        try (Connection connection = dataSource.getConnection()) {
            boolean isValid = connection.isValid(5);
            if (!isValid) {
                logger.error("Database connection is not valid");
                throw new RuntimeException("Database connection test failed");
            }

            template.queryForObject("SELECT 1", Integer.class);
            logger.info("Database connection successfully established");
        } catch (SQLException e) {
            logger.error("Database connection test failed", e);
            throw new RuntimeException("Database connection test failed", e);
        }

        return template;
    }
}
