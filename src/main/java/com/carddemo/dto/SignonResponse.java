package com.carddemo.dto;

import jakarta.validation.constraints.Size;
import java.util.Locale;

/**
 * Immutable sign-on response returned to a client after a successful authentication
 * against the CardDemo user-security store (originally the VSAM {@code USRSEC} file,
 * whose {@code SEC-USER-DATA} record is described by copybook {@code CSUSR01Y}).
 *
 * <p>This DTO is the stateless replacement for the CICS pseudo-conversational
 * {@code COMMAREA} user context carried by {@code COCOM01Y} in the legacy sign-on
 * transaction (program {@code COSGN00C}, transaction {@code CC00}). Rather than a
 * server-retained conversational session, the entire session context travels inside
 * the issued JSON Web Token ({@link #token()}); the remaining fields simply echo the
 * authenticated user's identity and mapped role for client convenience.</p>
 *
 * <h2>Security invariants</h2>
 * <ul>
 *   <li>The user's password (legacy {@code SEC-USR-PWD}) and any derived password hash
 *       are <strong>never</strong> exposed by this response.</li>
 *   <li>No server-side or conversational session state is embedded — the token is the
 *       whole session context (see AAP &sect;0.8.4, COMMAREA &rarr; JWT).</li>
 * </ul>
 *
 * <h2>Field provenance ({@code SEC-USER-DATA} &mdash; {@code CSUSR01Y})</h2>
 * <ul>
 *   <li>{@code userId}    &larr; {@code SEC-USR-ID}    {@code PIC X(08)}</li>
 *   <li>{@code firstName} &larr; {@code SEC-USR-FNAME} {@code PIC X(20)}</li>
 *   <li>{@code lastName}  &larr; {@code SEC-USR-LNAME} {@code PIC X(20)}</li>
 *   <li>{@code role}      &larr; {@code SEC-USR-TYPE}  {@code PIC X(01)}, mapped to a role name</li>
 * </ul>
 *
 * @param token     the issued JWT bearer token; opaque to the client
 * @param tokenType the token scheme; conventionally {@value #BEARER}
 * @param userId    the authenticated user id (echo of {@code SEC-USR-ID}; at most 8 characters)
 * @param firstName the authenticated user's first name ({@code SEC-USR-FNAME})
 * @param lastName  the authenticated user's last name ({@code SEC-USR-LNAME})
 * @param role      the mapped role name — {@value #ROLE_ADMIN} or {@value #ROLE_USER}
 */
public record SignonResponse(
        String token,
        String tokenType,
        @Size(max = 8) String userId,
        String firstName,
        String lastName,
        String role) {

    /** Conventional bearer-token scheme name used for {@link #tokenType()}. */
    public static final String BEARER = "Bearer";

    /** Mapped role name for the administrator user type ({@code SEC-USR-TYPE = 'A'}). */
    public static final String ROLE_ADMIN = "ADMIN";

    /** Mapped role name for the regular user type ({@code SEC-USR-TYPE = 'U'}). */
    public static final String ROLE_USER = "USER";

    /**
     * Legacy {@code SEC-USR-TYPE} code identifying an administrator, mirroring the
     * COCOM01Y 88-level {@code CDEMO-USRTYP-ADMIN VALUE 'A'}.
     */
    private static final String USER_TYPE_ADMIN = "A";

    /**
     * Assembles a bearer-token sign-on response, mapping the raw single-character
     * {@code SEC-USR-TYPE} code to a role name and defaulting {@link #tokenType()}
     * to {@value #BEARER}. This is the preferred way for the sign-on service to build
     * a response, keeping the {@code SEC-USR-TYPE} &rarr; role translation in one place.
     *
     * @param token      the issued JWT bearer token
     * @param userId     the authenticated user id ({@code SEC-USR-ID})
     * @param firstName  the user's first name ({@code SEC-USR-FNAME})
     * @param lastName   the user's last name ({@code SEC-USR-LNAME})
     * @param secUsrType the raw {@code SEC-USR-TYPE} code (typically {@code 'A'} or {@code 'U'})
     * @return a fully populated, immutable {@code SignonResponse}
     */
    public static SignonResponse of(String token,
                                    String userId,
                                    String firstName,
                                    String lastName,
                                    String secUsrType) {
        return new SignonResponse(token, BEARER, userId, firstName, lastName,
                mapRole(secUsrType));
    }

    /**
     * Maps a legacy {@code SEC-USR-TYPE} code to a role name, preserving the two-role
     * model and the exact routing semantics of {@code COSGN00C}: the administrator code
     * {@code 'A'} ({@code CDEMO-USRTYP-ADMIN}) yields {@value #ROLE_ADMIN}; every other
     * value — including the regular-user code {@code 'U'} ({@code CDEMO-USRTYP-USER}) —
     * yields {@value #ROLE_USER}. This mirrors the COBOL
     * {@code IF CDEMO-USRTYP-ADMIN ... ELSE ...} branch that routes every non-admin to
     * the regular menu ({@code COMEN01C}).
     *
     * <p>The input is trimmed and upper-cased before comparison so that the fixed-width,
     * space-padded value read from the {@code SEC-USER-DATA} record maps correctly; a
     * {@code null} or blank code is treated as a regular user.</p>
     *
     * @param secUsrType the raw {@code SEC-USR-TYPE} code
     * @return {@value #ROLE_ADMIN} when the code is {@code 'A'}; otherwise {@value #ROLE_USER}
     */
    public static String mapRole(String secUsrType) {
        String normalized = secUsrType == null
                ? ""
                : secUsrType.trim().toUpperCase(Locale.ROOT);
        return USER_TYPE_ADMIN.equals(normalized) ? ROLE_ADMIN : ROLE_USER;
    }
}
