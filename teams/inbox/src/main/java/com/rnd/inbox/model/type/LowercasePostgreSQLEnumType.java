package com.rnd.inbox.model.type;

import io.hypersistence.utils.hibernate.type.basic.PostgreSQLEnumType;
import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

public class LowercasePostgreSQLEnumType extends PostgreSQLEnumType {

    @Override
    public void nullSafeSet(PreparedStatement st, Object value, int index,
            SharedSessionContractImplementor session) throws HibernateException, SQLException {
        if (value instanceof Enum) {
            st.setObject(index, ((Enum<?>) value).name().toLowerCase(), Types.OTHER);
        } else {
            st.setObject(index, value, Types.OTHER);
        }
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object nullSafeGet(ResultSet rs, String[] names, SharedSessionContractImplementor session, Object owner)
            throws HibernateException, SQLException {
        String dbValue = rs.getString(names[0]);
        if (dbValue == null) {
            return null;
        }
        return Enum.valueOf((Class<? extends Enum>) returnedClass(), dbValue.toUpperCase());
    }
}
