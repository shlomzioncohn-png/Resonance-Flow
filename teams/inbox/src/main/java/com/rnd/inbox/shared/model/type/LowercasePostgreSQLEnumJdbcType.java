package com.rnd.inbox.shared.model.type;

import org.hibernate.dialect.PostgreSQLEnumJdbcType;
import org.hibernate.type.descriptor.ValueBinder;
import org.hibernate.type.descriptor.ValueExtractor;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.descriptor.jdbc.BasicBinder;
import org.hibernate.type.descriptor.jdbc.BasicExtractor;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Locale;

/**
 * {@link PostgreSQLEnumJdbcType} binds/extracts the Java enum's {@code name()} verbatim, but the
 * Postgres enum labels declared in the Flyway migrations are lowercase (e.g. {@code 'pending'})
 * while the Java enum constants are UPPER_SNAKE_CASE (e.g. {@code PENDING}). This type lowercases
 * on write and uppercases on read so the two sides line up.
 */
public class LowercasePostgreSQLEnumJdbcType extends PostgreSQLEnumJdbcType {

    @Override
    public <X> ValueBinder<X> getBinder(JavaType<X> javaType) {
        return new BasicBinder<>(javaType, this) {
            @Override
            protected void doBindNull(PreparedStatement st, int index, WrapperOptions options) throws SQLException {
                st.setNull(index, Types.OTHER);
            }

            @Override
            protected void doBindNull(CallableStatement st, String name, WrapperOptions options) throws SQLException {
                st.setNull(name, Types.OTHER);
            }

            @Override
            protected void doBind(PreparedStatement st, X value, int index, WrapperOptions options)
                    throws SQLException {
                st.setObject(index, toLowercaseName(value), Types.OTHER);
            }

            @Override
            protected void doBind(CallableStatement st, X value, String name, WrapperOptions options)
                    throws SQLException {
                st.setObject(name, toLowercaseName(value), Types.OTHER);
            }
        };
    }

    @Override
    public <X> ValueExtractor<X> getExtractor(JavaType<X> javaType) {
        return new BasicExtractor<>(javaType, this) {
            @Override
            protected X doExtract(ResultSet rs, int paramIndex, WrapperOptions options) throws SQLException {
                return getJavaType().wrap(toUppercase(rs.getObject(paramIndex)), options);
            }

            @Override
            protected X doExtract(CallableStatement statement, int index, WrapperOptions options)
                    throws SQLException {
                return getJavaType().wrap(toUppercase(statement.getObject(index)), options);
            }

            @Override
            protected X doExtract(CallableStatement statement, String name, WrapperOptions options)
                    throws SQLException {
                return getJavaType().wrap(toUppercase(statement.getObject(name)), options);
            }
        };
    }

    private static String toLowercaseName(Object value) {
        return ((Enum<?>) value).name().toLowerCase(Locale.ROOT);
    }

    private static Object toUppercase(Object value) {
        return value instanceof String ? ((String) value).toUpperCase(Locale.ROOT) : value;
    }
}
