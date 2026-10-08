package com.rnd.inbox.shared.model.type;

import com.rnd.inbox.tenant.model.TenantStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.hibernate.type.descriptor.ValueBinder;
import org.hibernate.type.descriptor.ValueExtractor;
import org.hibernate.type.descriptor.java.EnumJavaType;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LowercasePostgreSQLEnumJdbcTypeTest {

    private static final EnumJavaType<TenantStatus> JAVA_TYPE = new EnumJavaType<>(TenantStatus.class);

    private final LowercasePostgreSQLEnumJdbcType jdbcType = new LowercasePostgreSQLEnumJdbcType();

    @Mock
    private PreparedStatement preparedStatement;

    @Mock
    private ResultSet resultSet;

    @Test
    void writesTheEnumNameInLowercase() throws Exception {
        ValueBinder<TenantStatus> binder = jdbcType.getBinder(JAVA_TYPE);

        binder.bind(preparedStatement, TenantStatus.ACTIVE, 1, null);

        verify(preparedStatement).setObject(1, "active", Types.OTHER);
    }

    @Test
    void writesNullAsANullOfTheEnumType() throws Exception {
        ValueBinder<TenantStatus> binder = jdbcType.getBinder(JAVA_TYPE);

        binder.bind(preparedStatement, null, 1, null);

        verify(preparedStatement).setNull(1, Types.OTHER);
    }

    @Test
    void readsALowercaseDbValueBackAsTheMatchingUppercaseEnumConstant() throws Exception {
        when(resultSet.getObject(1)).thenReturn("suspended");
        ValueExtractor<TenantStatus> extractor = jdbcType.getExtractor(JAVA_TYPE);

        TenantStatus result = extractor.extract(resultSet, 1, null);

        assertThat(result).isEqualTo(TenantStatus.SUSPENDED);
    }

    @Test
    void readsANullDbValueAsNull() throws Exception {
        when(resultSet.getObject(1)).thenReturn(null);
        ValueExtractor<TenantStatus> extractor = jdbcType.getExtractor(JAVA_TYPE);

        TenantStatus result = extractor.extract(resultSet, 1, null);

        assertThat(result).isNull();
    }
}
