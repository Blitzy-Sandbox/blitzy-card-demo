/*
 * UserTypeConverter.java — JPA AttributeConverter for UserType enum
 *
 * Converts between the UserType Java enum and its single-character database
 * representation. The COBOL source (CSUSR01Y.cpy) defines SEC-USR-TYPE as
 * PIC X(01) with values 'A' (admin) and 'U' (regular user). The PostgreSQL
 * DDL (V1__create_schema.sql) defines usr_type as CHAR(1) with a CHECK
 * constraint: CHECK (usr_type IN ('A', 'U')).
 *
 * This converter delegates to the existing UserType.getCode() and
 * UserType.fromCode() methods, which encapsulate the character-to-enum
 * mapping logic. Using an AttributeConverter instead of @Enumerated(EnumType.STRING)
 * ensures that the single-character code ('A'/'U') is persisted rather than
 * the full enum constant name ("ADMIN"/"USER"), which would violate the
 * CHAR(1) column constraint.
 *
 * The converter is annotated with @Converter(autoApply = false) — it is
 * explicitly referenced via @Convert on the UserSecurity.secUsrType field.
 * This avoids unexpected side effects on other entities that might use
 * UserType in the future.
 *
 * @see com.cardemo.model.enums.UserType
 * @see com.cardemo.model.entity.UserSecurity
 */
package com.cardemo.model.converter;

import com.cardemo.model.enums.UserType;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA {@link AttributeConverter} that maps {@link UserType} enum values to their
 * single-character database representation and vice versa.
 *
 * <table>
 *   <caption>Enum-to-Database Mapping</caption>
 *   <tr><th>Java Enum</th><th>Database Value</th></tr>
 *   <tr><td>{@link UserType#ADMIN}</td><td>{@code 'A'}</td></tr>
 *   <tr><td>{@link UserType#USER}</td><td>{@code 'U'}</td></tr>
 *   <tr><td>{@code null}</td><td>{@code null}</td></tr>
 * </table>
 *
 * <p>Decision rationale (DECISION_LOG.md): {@code @Enumerated(EnumType.STRING)} would
 * persist "ADMIN" (5 chars) or "USER" (4 chars), exceeding the CHAR(1) DDL constraint
 * and triggering a {@code DataTruncation} or CHECK constraint violation. The converter
 * pattern provides exact control over the persisted representation.</p>
 */
@Converter
public class UserTypeConverter implements AttributeConverter<UserType, String> {

    /**
     * Converts a {@link UserType} enum value to its single-character database column value.
     *
     * <p>Mapping: {@code ADMIN → "A"}, {@code USER → "U"}, {@code null → null}.</p>
     *
     * @param attribute the UserType enum value to convert; may be {@code null}
     * @return the single-character code ("A" or "U"), or {@code null} if the input is {@code null}
     */
    @Override
    public String convertToDatabaseColumn(UserType attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.getCode();
    }

    /**
     * Converts a single-character database column value to its {@link UserType} enum equivalent.
     *
     * <p>Mapping: {@code "A" → ADMIN}, {@code "U" → USER}, {@code null → null}.</p>
     *
     * <p>If the database value does not match any known code, {@link UserType#fromCode(String)}
     * will throw an {@link IllegalArgumentException}. This should never occur if the DDL
     * CHECK constraint is enforced, but provides a defensive safety net for data integrity.</p>
     *
     * @param dbData the database column value; expected to be "A", "U", or {@code null}
     * @return the corresponding UserType enum value, or {@code null} if the input is {@code null}
     * @throws IllegalArgumentException if dbData is not a recognized user type code
     */
    @Override
    public UserType convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        return UserType.fromCode(dbData.trim());
    }
}
