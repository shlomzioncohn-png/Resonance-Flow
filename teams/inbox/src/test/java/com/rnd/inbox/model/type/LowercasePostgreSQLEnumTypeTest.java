package com.rnd.inbox.model.type;

import com.rnd.inbox.model.enums.OrganizationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LowercasePostgreSQLEnumTypeTest {

    private final TestableLowercasePostgreSQLEnumType type = new TestableLowercasePostgreSQLEnumType();

    @Mock
    private PreparedStatement preparedStatement;

    @Mock
    private ResultSet resultSet;

    @Test
    void writesTheEnumNameInLowercase() throws Exception {
        type.nullSafeSet(preparedStatement, OrganizationStatus.ACTIVE, 1, null);

        verify(preparedStatement).setObject(1, "active", Types.OTHER);
    }

    @Test
    void writesNullAsIs() throws Exception {
        type.nullSafeSet(preparedStatement, null, 1, null);

        verify(preparedStatement).setObject(1, null, Types.OTHER);
    }

    @Test
    void readsALowercaseDbValueBackAsTheMatchingUppercaseEnumConstant() throws Exception {
        when(resultSet.getString("status")).thenReturn("suspended");

        Object result = type.nullSafeGet(resultSet, new String[] {"status"}, null, null);

        assertThat(result).isEqualTo(OrganizationStatus.SUSPENDED);
    }

    @Test
    void readsANullDbValueAsNull() throws Exception {
        when(resultSet.getString("status")).thenReturn(null);

        Object result = type.nullSafeGet(resultSet, new String[] {"status"}, null, null);

        assertThat(result).isNull();
    }

    private static class TestableLowercasePostgreSQLEnumType extends LowercasePostgreSQLEnumType {
        @Override
        @SuppressWarnings("rawtypes")
        public Class returnedClass() {
            return OrganizationStatus.class;
        }
    }
}
