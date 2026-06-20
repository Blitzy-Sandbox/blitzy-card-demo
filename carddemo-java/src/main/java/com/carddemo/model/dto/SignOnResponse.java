package com.carddemo.model.dto;

import com.carddemo.model.enums.UserType;

/**
 * Immutable response payload for sign-on / authentication.
 *
 * <p>Serialized to JSON and returned by {@code AuthController} from
 * {@code POST /api/auth/signin} once {@code AuthenticationService} (migrated from
 * the {@code COSGN00C} online program) completes. On success it carries the
 * issued JWT and the authenticated user's type; on failure it carries only the
 * error message. This is a plain, immutable, JSON-serializable value carrier with
 * no behavior, no persistence concerns, and no presentation chrome.</p>
 *
 * <p>Field lineage (origin &rarr; record component): the issued JWT &rarr;
 * {@link #token()} &mdash; a new field with no {@code COSGN00} screen equivalent,
 * the stateless replacement for the CICS conversational state once held in
 * {@code CARDDEMO-COMMAREA}; {@code COSGN00} {@code USERID} ({@code PIC X(8)})
 * &rarr; {@link #userId()}; {@code SEC-USR-TYPE} / {@code CDEMO-USER-TYPE} &rarr;
 * {@link #userType()}; {@code COSGN00} {@code ERRMSG} ({@code PIC X(78)}) &rarr;
 * {@link #errorMessage()}. The password and every other credential field of the
 * sign-on map are intentionally excluded from this contract.</p>
 *
 * <p>Source lineage (reference only, COBOL not copied): AWS CardDemo commit
 * {@code 27d6c6f}.</p>
 *
 * @param token        the issued JWT bearer token; {@code null} on authentication
 *                     failure (new stateless-session field, no COBOL equivalent)
 * @param userId       the echoed authenticated user identifier
 *                     (mirrors {@code COSGN00} {@code USERID PIC X(8)})
 * @param userType     the authenticated user's authorization type
 *                     ({@link UserType#ADMIN} or {@link UserType#USER}, from
 *                     {@code SEC-USR-TYPE}); {@code null} on authentication failure
 * @param errorMessage the sign-on error message text (mirrors {@code COSGN00}
 *                     {@code ERRMSG PIC X(78)}); populated on failure, {@code null}
 *                     on success
 */
public record SignOnResponse(
        String token,
        String userId,
        UserType userType,
        String errorMessage) {
}
