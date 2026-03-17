/*
 * UserType.java — User Role Enumeration for CardDemo Application
 *
 * Migrated from COBOL source artifacts:
 *   - app/cpy/CSUSR01Y.cpy (SEC-USR-TYPE PIC X(01), line 22)
 *   - app/cpy/COCOM01Y.cpy (CDEMO-USER-TYPE with 88-level conditions, lines 26-28)
 *
 * This enum codifies the user role/type values from the CardDemo COBOL security
 * model. The single-character code originates from PIC X(01) fields in both the
 * user security record (SEC-USR-TYPE) and the COMMAREA (CDEMO-USER-TYPE).
 *
 * COBOL 88-level condition mapping:
 *   88 CDEMO-USRTYP-ADMIN VALUE 'A'  →  UserType.ADMIN
 *   88 CDEMO-USRTYP-USER  VALUE 'U'  →  UserType.USER
 *
 * Usage across COBOL programs:
 *   - COSGN00C.cbl: Routes to admin menu (COADM01C) vs regular menu (COMEN01C)
 *   - COADM01C.cbl: Verifies CDEMO-USRTYP-ADMIN for admin access control
 *   - COMEN01C.cbl: Routes based on user type for feature access
 *   - COUSR01C.cbl: Accepts user type during user creation
 *   - All CICS online programs: Check CDEMO-USER-TYPE in COMMAREA for role routing
 *
 * Consumed by: UserSecurity entity, CommArea DTO, SignOnResponse DTO,
 *              SecurityConfig (role-based access), AuthenticationService
 */
package com.cardemo.model.enums;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Enumerates the user role types in the CardDemo application.
 *
 * <p>Each constant carries the single-character COBOL type code defined in
 * {@code COCOM01Y.cpy} as 88-level condition values on the
 * {@code CDEMO-USER-TYPE PIC X(01)} field.</p>
 *
 * <p>The two valid codes are:
 * <ul>
 *   <li>{@code "A"} — Administrator (admin menu access, user management)</li>
 *   <li>{@code "U"} — Regular user (main menu access, account/card/transaction features)</li>
 * </ul>
 * </p>
 */
public enum UserType {

    /**
     * Administrator user type.
     * <p>Maps from COBOL: {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} (COCOM01Y.cpy line 27).</p>
     * <p>Grants access to the admin menu (COADM01C) and user management features
     * (COUSR00C–COUSR03C).</p>
     */
    ADMIN("A"),

    /**
     * Regular (non-admin) user type.
     * <p>Maps from COBOL: {@code 88 CDEMO-USRTYP-USER VALUE 'U'} (COCOM01Y.cpy line 28).</p>
     * <p>Grants access to the main menu (COMEN01C) with account, card, transaction,
     * billing, and report features.</p>
     */
    USER("U");

    /**
     * Static lookup map keyed by the single-character COBOL code for O(1) constant resolution.
     * Built once at class-load time from {@link #values()}.
     */
    private static final Map<String, UserType> CODE_MAP =
            Arrays.stream(values())
                    .collect(Collectors.toMap(UserType::getCode, Function.identity()));

    /**
     * The single-character COBOL type code (PIC X(01)).
     * Stored exactly as defined in the 88-level VALUE clause — "A" or "U".
     */
    private final String code;

    /**
     * Constructs a {@code UserType} enum constant with the given COBOL type code.
     *
     * @param code the single-character code matching the COBOL 88-level VALUE
     */
    UserType(String code) {
        this.code = code;
    }

    /**
     * Resolves a {@code UserType} constant from its single-character COBOL code.
     *
     * <p>The input string is trimmed before lookup to handle any trailing spaces
     * that may originate from fixed-width COBOL fields (PIC X(01) padded in
     * larger record buffers).</p>
     *
     * @param code the COBOL type code (e.g., "A" or "U"); may include leading/trailing whitespace
     * @return the matching {@code UserType} constant
     * @throws IllegalArgumentException if {@code code} is {@code null} or does not match
     *                                  any known user type code
     */
    public static UserType fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Unknown user type code: null");
        }
        String trimmed = code.trim();
        UserType result = CODE_MAP.get(trimmed);
        if (result == null) {
            throw new IllegalArgumentException("Unknown user type code: " + trimmed);
        }
        return result;
    }

    /**
     * Returns the single-character COBOL type code for this user type.
     *
     * @return "A" for {@link #ADMIN}, "U" for {@link #USER}
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the single-character COBOL-compatible code for file and comparison operations.
     *
     * <p>This preserves the exact COBOL comparison semantics used in
     * {@code EVALUATE SEC-USR-TYPE} constructs across the online programs.</p>
     *
     * @return the single-character code ("A" or "U")
     */
    @Override
    public String toString() {
        return code;
    }
}
