package com.carddemo.model.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

/**
 * User authorization type for the CardDemo application.
 *
 * <p>Java equivalent of the COBOL user-type code {@code SEC-USR-TYPE} (the persisted
 * {@code PIC X(01)} field in {@code app/cpy/CSUSR01Y.cpy}) and the {@code CDEMO-USER-TYPE}
 * field in the communication area {@code app/cpy/COCOM01Y.cpy}, whose 88-level condition
 * names {@code CDEMO-USRTYP-ADMIN} (value {@code 'A'}) and {@code CDEMO-USRTYP-USER}
 * (value {@code 'U'}) this enum represents. Lineage is preserved against source commit
 * {@code 27d6c6f}.</p>
 *
 * <p>The single-character code is retained as a {@link String} to mirror the COBOL
 * {@code PIC X(01)} (a one-byte alphanumeric) and to provide a uniform, null-comparable
 * accessor contract. Drives admin-versus-user menu routing and authorization across the
 * online and batch layers.</p>
 */
public enum UserType {

    /** Administrator user. COBOL {@code CDEMO-USRTYP-ADMIN}, code {@code 'A'}. */
    ADMIN("A"),

    /** Standard (regular) user. COBOL {@code CDEMO-USRTYP-USER}, code {@code 'U'}. */
    USER("U");

    /** The persisted single-character user-type code ({@code "A"} or {@code "U"}). */
    private final String code;

    UserType(String code) {
        this.code = code;
    }

    /**
     * Returns the persisted single-character user-type code.
     *
     * @return the code ({@code "A"} for {@link #ADMIN}, {@code "U"} for {@link #USER})
     */
    public String getCode() {
        return code;
    }

    /**
     * Resolves a single-character COBOL user-type code to its enum constant.
     *
     * <p>The lookup is null-safe: a {@code null} argument yields no match rather than a
     * {@link NullPointerException}, because the comparison is anchored on the constant's
     * own non-null code.</p>
     *
     * @param code the one-character code ({@code "A"} or {@code "U"}); may be {@code null}
     * @return the matching {@link UserType}, or {@code null} if no constant matches or
     *         {@code code} is {@code null}
     */
    public static UserType fromCode(String code) {
        for (UserType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Jackson factory that deserializes an inbound JSON string into a {@link UserType},
     * accepting <em>both</em> the COBOL-native single-character codes ({@code "A"} /
     * {@code "U"} &mdash; the external contract documented in {@code api-contracts.md §2.8}
     * and the {@code PIC X(01)} {@code SEC-USR-TYPE} field of {@code app/cpy/CSUSR01Y.cpy})
     * and the enum constant names ({@code "ADMIN"} / {@code "USER"}, retained for backward
     * compatibility with existing REST clients and with this API's own response
     * serialization). This restores COBOL-native input parity (§0.8.1 external-interface
     * preservation) for {@code POST}/{@code PUT /api/admin/users} without expanding the
     * accepted domain beyond the two valid authorization types.
     *
     * <p>The value is trimmed and upper-cased before matching, so surrounding whitespace and
     * lower-case variants resolve identically. A {@code null} token is passed through as
     * {@code null} (an explicit JSON {@code null} is handled by Jackson without invoking this
     * factory; the guard keeps the method null-safe when called directly), leaving any
     * required/optional enforcement to bean validation on the enclosing request DTO &mdash;
     * preserving the pre-existing null-handling behavior. Any other value raises
     * {@link IllegalArgumentException}, which Jackson surfaces as a deserialization failure
     * and the global exception handler maps to HTTP 400.</p>
     *
     * <p><strong>Serialization is intentionally unchanged:</strong> no {@code @JsonValue}
     * counterpart is declared, so responses continue to emit the enum constant name
     * ({@code "ADMIN"} / {@code "USER"}), keeping the sign-on, user list/detail, and menu
     * response contracts byte-for-byte identical.</p>
     *
     * @param value the inbound JSON string &mdash; {@code "A"}/{@code "U"} or
     *              {@code "ADMIN"}/{@code "USER"}, case-insensitive and trimmed; may be
     *              {@code null}
     * @return the matching {@link UserType}, or {@code null} when {@code value} is
     *         {@code null}
     * @throws IllegalArgumentException if {@code value} is non-null and matches no known
     *         code or constant name
     */
    @JsonCreator
    public static UserType fromJson(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "A", "ADMIN" -> ADMIN;
            case "U", "USER" -> USER;
            default -> throw new IllegalArgumentException(
                    "Invalid userType '" + value + "'; expected one of: A, U, ADMIN, USER");
        };
    }
}
