package com.carddemo.model.enums;

/**
 * User authorization type. Java equivalent of the COBOL user-type code
 * ({@code SEC-USR-TYPE} in {@code CSUSR01Y}) and the {@code CDEMO-USER-TYPE}
 * 88-levels in {@code COCOM01Y} ({@code CDEMO-USRTYP-ADMIN} {@code 'A'} /
 * {@code CDEMO-USRTYP-USER} {@code 'U'}) from source commit {@code 27d6c6f}.
 *
 * <p>The single-character code is held as a {@link String} mirroring the COBOL
 * {@code PIC X(01)} 1-byte alphanumeric field.</p>
 *
 * <p>Drives admin-vs-user menu routing and authorization across the migrated
 * application.</p>
 */
public enum UserType {

    /** Administrator user. COBOL {@code CDEMO-USRTYP-ADMIN VALUE 'A'}. */
    ADMIN("A"),

    /** Standard user. COBOL {@code CDEMO-USRTYP-USER VALUE 'U'}. */
    USER("U");

    /** Persisted single-character user-type code ({@code "A"} or {@code "U"}). */
    private final String code;

    /**
     * Binds the persisted single-character code to the enum constant.
     *
     * @param code the single-character user-type code ({@code "A"} or {@code "U"})
     */
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
     * @param code the 1-char code ({@code "A"} or {@code "U"}); may be {@code null}
     * @return the matching {@link UserType}, or {@code null} if none matches or
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
