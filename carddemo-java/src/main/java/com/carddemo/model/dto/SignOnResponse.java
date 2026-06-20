package com.carddemo.model.dto;

/**
 * Immutable response payload for sign-on / authentication.
 *
 * <p>Serialized to JSON and returned by {@code AuthController} from
 * {@code POST /api/auth/signin} once {@code AuthenticationService} (migrated from
 * the {@code COSGN00C} online program) completes successfully. It carries the
 * issued JWT, the authenticated user's identity and type, the derived role, and
 * the token expiry. Authentication <em>failures</em> are not represented by this
 * record: they surface as the shared error envelope produced by
 * {@code GlobalExceptionHandler} (HTTP {@code 401 AUTHENTICATION_FAILED}), so this
 * success contract intentionally carries no error field. This is a plain,
 * immutable, JSON-serializable value carrier with no behavior, no persistence
 * concerns, and no presentation chrome.</p>
 *
 * <p>Contract: see {@code docs/api-contracts.md} §5.1. The published response
 * fields are {@code token}, {@code userId}, {@code userType}, {@code role}, and
 * {@code expiresAt}.</p>
 *
 * <p>Field lineage (origin &rarr; record component): the issued JWT &rarr;
 * {@link #token()} &mdash; a new field with no {@code COSGN00} screen equivalent,
 * the stateless replacement for the CICS conversational state once held in
 * {@code CARDDEMO-COMMAREA}; {@code COSGN00} {@code USERID} ({@code PIC X(8)})
 * &rarr; {@link #userId()}; {@code SEC-USR-TYPE} / {@code CDEMO-USER-TYPE}
 * ({@code PIC X(01)}, {@code 'A'} or {@code 'U'}) &rarr; {@link #userType()};
 * the derived authorization role (see §3.2) &rarr; {@link #role()}; the JWT
 * {@code exp} instant &rarr; {@link #expiresAt()}. The password and every other
 * credential field of the sign-on map are intentionally excluded from this
 * contract.</p>
 *
 * <p>Source lineage (reference only, COBOL not copied): AWS CardDemo commit
 * {@code 27d6c6f}.</p>
 *
 * @param token     the issued JWT bearer token (new stateless-session field, no
 *                  COBOL equivalent)
 * @param userId    the echoed authenticated user identifier
 *                  (mirrors {@code COSGN00} {@code USERID PIC X(8)})
 * @param userType  the authenticated user's authorization type as a single
 *                  character &mdash; {@code "A"} (admin) or {@code "U"} (user),
 *                  echoing {@code CDEMO-USER-TYPE} ({@code PIC X(01)})
 * @param role      the derived authorization role &mdash; {@code "ADMIN"} or
 *                  {@code "USER"} (see {@code docs/api-contracts.md} §3.2)
 * @param expiresAt the token expiry timestamp in ISO-8601 form (the JWT
 *                  {@code exp} claim rendered as an instant)
 */
public record SignOnResponse(
        String token,
        String userId,
        String userType,
        String role,
        String expiresAt) {
}
