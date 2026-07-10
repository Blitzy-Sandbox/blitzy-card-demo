package com.carddemo.dto;

import jakarta.validation.constraints.Size;

/**
 * Immutable response payload describing a single application user.
 *
 * <p>This is the shared read / confirmation view returned by the user
 * management endpoints of {@code UserController}. It backs the detail
 * operations behind CICS transactions <strong>CU00</strong> (list users),
 * <strong>CU01</strong> (add user), <strong>CU02</strong> (update user) and
 * <strong>CU03</strong> (view / delete user), and is also returned as the
 * confirmation body for create, update and delete requests.</p>
 *
 * <p>The field layout is translated from the legacy BMS symbolic map
 * {@code COUSR03} ({@code app/cpy-bms/COUSR03.CPY}), whose display fields are in
 * turn backed by the {@code SEC-USER-DATA} record
 * ({@code app/cpy/CSUSR01Y.cpy}). Fixed-width COBOL picture clauses are
 * preserved as maximum lengths via Jakarta Bean Validation {@link Size}
 * constraints so the REST contract stays width-compatible with the mainframe
 * record layout.</p>
 *
 * <p><strong>Security constraint:</strong> the underlying {@code SEC-USER-DATA}
 * record carries a {@code SEC-USR-PWD} password field ({@code PIC X(08)}). That
 * field — and any password hash derived from it — is deliberately
 * <em>never</em> exposed on this DTO. The response surface is intentionally
 * limited to non-sensitive profile attributes only.</p>
 *
 * <p>The type is a {@code record}, making every instance immutable, thread-safe
 * and stateless, which mirrors the pseudo-conversational, per-request nature of
 * the original CICS screen data.</p>
 *
 * @param userId    the unique user identifier; maps to {@code SEC-USR-ID} /
 *                  {@code USRIDIN}, {@code PIC X(08)} (maximum 8 characters)
 * @param firstName the user's given name; maps to {@code SEC-USR-FNAME} /
 *                  {@code FNAME}, {@code PIC X(20)} (maximum 20 characters)
 * @param lastName  the user's family name; maps to {@code SEC-USR-LNAME} /
 *                  {@code LNAME}, {@code PIC X(20)} (maximum 20 characters)
 * @param userType  the user role code; maps to {@code SEC-USR-TYPE} /
 *                  {@code USRTYPE}, {@code PIC X(01)} — {@code "A"} for an
 *                  administrator or {@code "U"} for a regular user (maximum 1
 *                  character)
 * @param message   optional human-readable confirmation or status text returned
 *                  for create / update / delete operations; {@code null} for a
 *                  plain read
 */
public record UserResponse(

        @Size(max = 8)
        String userId,

        @Size(max = 20)
        String firstName,

        @Size(max = 20)
        String lastName,

        @Size(max = 1)
        String userType,

        String message
) {
}
