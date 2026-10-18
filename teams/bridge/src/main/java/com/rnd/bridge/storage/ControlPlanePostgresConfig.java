package com.rnd.bridge.storage;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A dedicated DataSource/JdbcTemplate for the control-plane Postgres table - this app has no
 * other DataSource (no JPA/Hibernate), so @Primary just makes this the one Spring Boot's own
 * SQL-init (spring.sql.init.mode: always, schema.sql) runs against to create
 * control_plane_state on startup. No default for any of these three properties: a missing env
 * var must fail startup loudly, same convention as HUBSPOT_TOKEN / CRM_ORGANIZATION_ID (see
 * teams/bridge/CLAUDE.md §7).
 * initializationFailTimeout(-1) keeps pool creation from blocking/throwing if Postgres isn't
 * reachable yet at bean-creation time (connections are then opened lazily, on first real use) -
 * paired with spring.sql.init.continue-on-error so BridgeApplicationTests' full-context load
 * doesn't require a live Postgres, the same tolerance already given to an absent Kafka broker
 * (see teams/bridge/CLAUDE.md §7).
 */
@Configuration
public class ControlPlanePostgresConfig {

    @Bean
    @Primary
    public DataSource controlPlaneDataSource(
            @Value("${bridge.postgres.url}") String url,
            @Value("${bridge.postgres.user}") String user,
            @Value("${bridge.postgres.password}") String password) {
        HikariDataSource dataSource = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName("org.postgresql.Driver")
                .url(url)
                .username(user)
                .password(password)
                .build();
        dataSource.setInitializationFailTimeout(-1);
        return dataSource;
    }

    @Bean
    public JdbcTemplate controlPlaneJdbcTemplate(DataSource controlPlaneDataSource) {
        return new JdbcTemplate(controlPlaneDataSource);
    }
}
