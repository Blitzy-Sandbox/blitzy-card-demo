package com.carddemo.model.dto;

import com.carddemo.model.enums.UserType;

/**
 * Response payload for {@code POST /api/auth/signin}.
 *
 * <p>Returned by {@code controller.AuthController} after
 * {@code service.auth.AuthenticationService} (migrated from the COBOL online
 * program {@code COSGN00C}) validates the supplied credentials against the
 * {@code USRSEC} security store. On success the body carries the issued JWT and
 * the authenticated user's authorization type; on failure it carries an error
 * message and leaves the credential-bearing fields {@code null}.
 *
 * <p>{@code token} is a {@code new} field with no {@code COSGN00} screen
 * equivalent: the original CICS program kept conversational state in the
 * {@code CARDDEMO-COMMAREA} and routed to a menu transaction, whereas the
 * migrated design is stateless and returns a JWT instead. {@code userType}
 * mirrors {@code SEC-USR-TYPE} / {@code CDEMO-USER-TYPE} (ADMIN/USER), and
 * {@code errorMessage} mirrors the {@code COSGN00} {@code ERRMSG} field
 * ({@code PIC X(78)}). Lineage: source commit {@code 27d6c6f} (COBOL not copied).
 *
 * @param token        issued JSON Web Token authenticating subsequent requests
 *                     (stateless replacement for CICS conversational state); may
 *                     be {@code null} when authentication failed
 * @param userId       echoed authenticated user id ({@code COSGN00} {@code USERID},
 *                     {@code PIC X(8)})
 * @param userType     authenticated user's authorization type ({@code SEC-USR-TYPE}
 *                     / {@code CDEMO-USER-TYPE}); may be {@code null} when
 *                     authentication failed
 * @param errorMessage error text describing why sign-on failed ({@code COSGN00}'s
 *                     {@code ERRMSG}, {@code PIC X(78)}); {@code null} when
 *                     authentication succeeded
 */
public record SignOnResponse(
        String token,
        String userId,
        UserType userType,
        String errorMessage) {
}
