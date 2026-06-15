package com.carddemo.model.enums;

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
}
