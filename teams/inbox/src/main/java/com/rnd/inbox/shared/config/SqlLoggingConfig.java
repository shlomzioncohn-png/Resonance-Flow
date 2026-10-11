package com.rnd.inbox.shared.config;

import com.rnd.inbox.shared.logging.SqlLogRequestInterceptor;
import com.rnd.inbox.shared.logging.SqlQueryLoggingListener;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.sql.DataSource;

/**
 * Wraps the application's DataSource so every JDBC statement - whichever layer issued it -
 * passes through {@link SqlQueryLoggingListener}.
 */
@Configuration
@EnableConfigurationProperties(SqlLoggingProperties.class)
@ConditionalOnProperty(prefix = "inbox.sql-logging", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class SqlLoggingConfig {

    @Bean
    WebMvcConfigurer sqlLogRequestContext() {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                // First in line, so the queries of every later interceptor carry the request id too.
                registry.addInterceptor(new SqlLogRequestInterceptor()).order(Ordered.HIGHEST_PRECEDENCE);
            }
        };
    }

    // static: a BeanPostProcessor must exist before the regular beans it post-processes.
    @Bean
    static BeanPostProcessor sqlLoggingDataSourceWrapper(ObjectProvider<SqlLoggingProperties> properties) {
        return new DataSourceWrapper(properties);
    }

    private static final class DataSourceWrapper implements BeanPostProcessor {

        private final ObjectProvider<SqlLoggingProperties> properties;

        private DataSourceWrapper(ObjectProvider<SqlLoggingProperties> properties) {
            this.properties = properties;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (!(bean instanceof DataSource dataSource)) {
                return bean;
            }
            return ProxyDataSourceBuilder.create(dataSource)
                    .name(beanName)
                    .listener(new SqlQueryLoggingListener(properties.getObject()))
                    .build();
        }
    }
}
