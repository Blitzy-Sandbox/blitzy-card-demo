package com.cardemo.model.enums;

/**
 * Type-safe representation of the CardDemo user-type (role) code.
 *
 * <p>This enum is the Java 25 replacement for the single-character COBOL
 * user-type field that drives sign-on and role routing in the legacy AWS
 * CardDemo mainframe application. Two source artifacts define the authoritative
 * value set:</p>
 *
 * <ul>
 *   <li>{@code app/cpy/CSUSR01Y.cpy} &mdash; {@code 05 SEC-USR-TYPE PIC X(01)},
 *       the 1-byte user-type field stored on every {@code USRSEC} security
 *       record.</li>
 *   <li>{@code app/cpy/COCOM01Y.cpy} &mdash; {@code 10 CDEMO-USER-TYPE PIC X(01)}
 *       with the two condition names that enumerate every legal value:
 *       {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} and
 *       {@code 88 CDEMO-USRTYP-USER VALUE 'U'}.</li>
 * </ul>
 *
 * <p>Per the migration's Minimal Change Clause, the COBOL value set is
 * reproduced <strong>exactly</strong>: there are precisely two user types and
 * no others, and the single-character codes {@code 'A'} and {@code 'U'} are
 * preserved verbatim so that round-trips to the external interface contract
 * (the 1-byte {@code SEC-USR-TYPE} / {@code CDEMO-USER-TYPE} field) remain
 * byte-faithful.</p>
 *
 * <p>The enum is a pure, dependency-free constant: it holds no mutable state,
 * performs no I/O, and depends on nothing beyond {@code java.lang}. It is a
 * foundational leaf consumed by the {@code UserSecurity} entity, the
 * authentication DTOs (for example {@code CommArea} and {@code SignOnResponse}),
 * the authentication service, and admin/main-menu role routing.</p>
 *
 * <p><strong>Traceability:</strong> derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference and is
 * never copied into this repository.</p>
 */
public enum UserType {

    /**
     * Administrative user. Maps to the COBOL condition name
     * {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} and the stored code {@code 'A'}.
     * In the legacy program flow administrators are routed to the admin menu.
     */
    ADMIN('A'),

    /**
     * Regular (non-administrative) user. Maps to the COBOL condition name
     * {@code 88 CDEMO-USRTYP-USER VALUE 'U'} and the stored code {@code 'U'}.
     * In the legacy program flow regular users are routed to the main menu.
     */
    USER('U');

    /** Single-character storage code, preserving 1-byte fidelity with the COBOL field. */
    // COBOL substitution: the 1-byte PIC X(01) SEC-USR-TYPE / CDEMO-USER-TYPE
    // field (legal values 'A' and 'U') is represented as a single char to
    // preserve exact 1-byte fidelity with the external interface contract.
    private final char code;

    /**
     * Binds each constant to its byte-faithful COBOL storage code. Enum
     * constructors are implicitly private.
     *
     * @param code the single-character code persisted in the COBOL
     *             {@code SEC-USR-TYPE} / {@code CDEMO-USER-TYPE} field
     */
    UserType(final char code) {
        this.code = code;
    }

    /**
     * Returns the single-character storage code for this user type.
     *
     * @return {@code 'A'} for {@link #ADMIN} or {@code 'U'} for {@link #USER}
     */
    public char getCode() {
        return code;
    }

    /**
     * Resolves a {@code UserType} from its single-character COBOL code.
     *
     * <p>Matching is case-sensitive, mirroring the byte-exact COBOL 88-level
     * comparison: only the uppercase codes {@code 'A'} and {@code 'U'} are
     * accepted. The lookup is derived solely from the codes declared on the
     * enum constants, so those literals are defined in exactly one place. An
     * unrecognized code indicates a data-integrity error, because the COBOL
     * {@code SEC-USR-TYPE} field only ever stores {@code 'A'} or {@code 'U'}.</p>
     *
     * @param code the single-character user-type code
     * @return the matching {@code UserType}
     * @throws IllegalArgumentException if {@code code} matches no known user type
     */
    public static UserType fromCode(final char code) {
        for (final UserType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown user type code: " + code);
    }

    /**
     * Resolves a {@code UserType} from a string carrying the COBOL
     * {@code PIC X(01)} code, as read from a fixed-width record or DTO field.
     *
     * <p>The input is trimmed and must contain exactly one character after
     * trimming; resolution then delegates to {@link #fromCode(char)} and is
     * therefore case-sensitive. A {@code null}, empty/blank, or multi-character
     * value is a data-integrity error, because the COBOL {@code SEC-USR-TYPE}
     * field only ever stores the single character {@code 'A'} or {@code 'U'}.</p>
     *
     * @param code the string form of the single-character user-type code
     * @return the matching {@code UserType}
     * @throws IllegalArgumentException if {@code code} is {@code null}, does not
     *         contain exactly one character after trimming, or matches no known
     *         user type
     */
    public static UserType fromCode(final String code) {
        if (code == null) {
            throw new IllegalArgumentException("Unknown user type code: null");
        }
        final String trimmed = code.trim();
        if (trimmed.length() != 1) {
            throw new IllegalArgumentException("Unknown user type code: " + code);
        }
        return fromCode(trimmed.charAt(0));
    }

    /**
     * Indicates whether this user type is the administrative role.
     *
     * <p>Mirrors the COBOL {@code 88 CDEMO-USRTYP-ADMIN} condition name used for
     * admin-menu role routing in the legacy program flow.</p>
     *
     * @return {@code true} if this is {@link #ADMIN}; {@code false} otherwise
     */
    public boolean isAdmin() {
        return this == ADMIN;
    }
}
