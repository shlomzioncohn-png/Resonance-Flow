package com.rnd.inbox.shared.config;

import ch.qos.logback.classic.Level;
import com.rnd.inbox.shared.logging.SqlLogCapture;
import com.rnd.inbox.shared.logging.SqlLogEvent;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqlLoggingConfigTest {

    private static final String PARAMETERIZED_SQL = "select * from tasks where status = ?";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FakeDataSourceConfig.class, SqlLoggingConfig.class);

    private SqlLogCapture capture;

    @BeforeEach
    void setUp() {
        capture = new SqlLogCapture();
        capture.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        capture.close();
    }

    // ---- wrapping on/off ----

    @Test
    void dataSource_whenEnabledPropertyMissing_isWrapped() {
        contextRunner.run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);

            assertThat(dataSource).isInstanceOf(ProxyDataSource.class);
            assertThat(((ProxyDataSource) dataSource).getDataSource())
                    .isSameAs(context.getBean(FakeDataSourceConfig.class).target);
        });
    }

    @Test
    void dataSource_whenEnabledTrue_isWrapped() {
        contextRunner.withPropertyValues("inbox.sql-logging.enabled=true").run(context ->
                assertThat(context.getBean(DataSource.class)).isInstanceOf(ProxyDataSource.class));
    }

    @Test
    void dataSource_whenEnabledFalse_isLeftUntouched() {
        contextRunner.withPropertyValues("inbox.sql-logging.enabled=false").run(context -> {
            assertThat(context.getBean(DataSource.class))
                    .isSameAs(context.getBean(FakeDataSourceConfig.class).target);
            assertThat(context).doesNotHaveBean(SqlLoggingProperties.class);
            assertThat(context).doesNotHaveBean("sqlLoggingDataSourceWrapper");
            assertThat(context).doesNotHaveBean("sqlLogRequestContext");
        });
    }

    @Test
    void requestInterceptorRegistration_whenEnabled_isPresent() {
        contextRunner.run(context -> assertThat(context).hasBean("sqlLogRequestContext"));
    }

    @Test
    void wrappedDataSource_isNamedAfterItsBean() {
        contextRunner.run(context ->
                assertThat(((ProxyDataSource) context.getBean(DataSource.class)).getDataSourceName())
                        .isEqualTo("dataSource"));
    }

    @Test
    void otherBeans_areNotReplaced() {
        contextRunner.run(context ->
                assertThat(context.getBean("unrelatedBean")).isSameAs(FakeDataSourceConfig.UNRELATED));
    }

    // ---- properties binding ----

    @Test
    void properties_whenNothingConfigured_haveTheirDefaults() {
        contextRunner.run(context -> {
            SqlLoggingProperties properties = context.getBean(SqlLoggingProperties.class);

            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.isIncludeParameters()).isFalse();
            assertThat(properties.getSlowQueryThresholdMs()).isEqualTo(200);
        });
    }

    @Test
    void properties_whenConfigured_areBoundFromTheKebabCaseKeys() {
        contextRunner.withPropertyValues(
                "inbox.sql-logging.include-parameters=true",
                "inbox.sql-logging.slow-query-threshold-ms=42").run(context -> {
            SqlLoggingProperties properties = context.getBean(SqlLoggingProperties.class);

            assertThat(properties.isIncludeParameters()).isTrue();
            assertThat(properties.getSlowQueryThresholdMs()).isEqualTo(42);
        });
    }

    // ---- a statement run through the wrapped DataSource reaches the log ----

    @Test
    void preparedStatement_whenIncludeParametersTrue_isLoggedWithInlinedParameter() {
        contextRunner.withPropertyValues("inbox.sql-logging.include-parameters=true").run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(PARAMETERIZED_SQL)) {
                statement.setString(1, "OPEN");
                statement.executeQuery();
            }

            assertThat(capture.messages()).containsExactly("select * from tasks where status = 'OPEN'");
        });
    }

    @Test
    void preparedStatement_whenIncludeParametersNotConfigured_isLoggedWithPlaceholder() {
        contextRunner.run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(PARAMETERIZED_SQL)) {
                statement.setString(1, "OPEN");
                statement.executeQuery();
            }

            assertThat(capture.messages()).containsExactly(PARAMETERIZED_SQL);
        });
    }

    @Test
    void plainStatement_runThroughWrappedDataSource_isLoggedAsIs() {
        contextRunner.run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("select 1");
            }

            assertThat(capture.messages()).containsExactly("select 1");
        });
    }

    @Test
    void failingStatement_isLoggedAsFailedWithoutItsParametersAndTheExceptionStillPropagates() {
        contextRunner.run(context -> {
            FakeDataSourceConfig fake = context.getBean(FakeDataSourceConfig.class);
            when(fake.preparedStatement.executeQuery()).thenThrow(new SQLException("boom"));
            DataSource dataSource = context.getBean(DataSource.class);

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(PARAMETERIZED_SQL)) {
                statement.setString(1, "OPEN");
                assertThatThrownBy(statement::executeQuery)
                        .isInstanceOf(SQLException.class).hasMessage("boom");
            }

            assertThat(capture.events()).singleElement().satisfies(event -> {
                assertThat(SqlLogCapture.eventOf(event)).isEqualTo(SqlLogEvent.QUERY_FAILED);
                assertThat(event.getFormattedMessage()).isEqualTo(PARAMETERIZED_SQL);
            });
        });
    }

    @Test
    void statement_whenLoggingDisabled_isNotLogged() {
        contextRunner.withPropertyValues("inbox.sql-logging.enabled=false").run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(PARAMETERIZED_SQL)) {
                statement.setString(1, "OPEN");
                statement.executeQuery();
            }

            assertThat(capture.events()).isEmpty();
        });
    }

    /** A DataSource backed by Mockito mocks, so no database is needed. */
    @Configuration
    static class FakeDataSourceConfig {

        static final Object UNRELATED = new Object();

        final DataSource target = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final PreparedStatement preparedStatement = mock(PreparedStatement.class);
        final Statement statement = mock(Statement.class);

        @Bean
        DataSource dataSource() throws SQLException {
            when(target.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(PARAMETERIZED_SQL)).thenReturn(preparedStatement);
            when(connection.createStatement()).thenReturn(statement);
            return target;
        }

        @Bean
        Object unrelatedBean() {
            return UNRELATED;
        }
    }
}
