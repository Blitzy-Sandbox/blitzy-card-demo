/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Sign-on (authentication) response DTO returned by the
 * {@code POST /api/auth/signin} REST endpoint after a successful credential
 * check. It is the modern target counterpart to the CICS pseudo-conversational
 * sign-on success path implemented by {@code app/cbl/COSGN00C.cbl}, and
 * carries the role-routing information the COBOL program previously deposited
 * in the {@code CARDDEMO-COMMAREA} before transferring control to the next
 * program via {@code EXEC CICS XCTL}.
 *
 * <p><b>COBOL Provenance</b> &mdash; this DTO surfaces the four user-identity
 * attributes that {@code COSGN00C} reads from the {@code USRSEC} VSAM KSDS,
 * adding a JWT bearer token and an expiry hint as deliberate, scope-bound
 * additions required by the migration target (stateless REST replacing
 * stateful CICS pseudo-conversation):
 * <ul>
 *   <li><b>Program:</b> {@code app/cbl/COSGN00C.cbl} &mdash; the
 *       {@code READ-USER-SEC-FILE} paragraph (lines 209-219) reads the
 *       {@code USRSEC} dataset keyed by {@code WS-USER-ID} into
 *       {@code SEC-USER-DATA}; on success it copies the user-identity fields
 *       into the COMMAREA at lines 224-228
 *       ({@code MOVE WS-USER-ID TO CDEMO-USER-ID},
 *       {@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE}) and then issues
 *       {@code EXEC CICS XCTL PROGRAM('COADM01C')} or
 *       {@code EXEC CICS XCTL PROGRAM('COMEN01C')} depending on
 *       {@code CDEMO-USRTYP-ADMIN} (lines 230-240).</li>
 *   <li><b>User Layout:</b> {@code app/cpy/CSUSR01Y.cpy} declares the
 *       {@code SEC-USER-DATA} record (80 bytes total) at lines 17-23:
 *       <ul>
 *         <li>{@code 05 SEC-USR-ID    PIC X(08)} (line 18) &rarr; {@link #userId()}</li>
 *         <li>{@code 05 SEC-USR-FNAME PIC X(20)} (line 19) &rarr; {@link #firstName()}</li>
 *         <li>{@code 05 SEC-USR-LNAME PIC X(20)} (line 20) &rarr; {@link #lastName()}</li>
 *         <li>{@code 05 SEC-USR-PWD   PIC X(08)} (line 21) &mdash;
 *             <b>deliberately omitted</b> from the response per PCI-DSS;
 *             the COBOL field's plaintext password value is replaced by a
 *             BCrypt hash in the {@code USER_SECURITY} table and never
 *             leaves the service layer (AAP &sect;0.1.1 deliberate security
 *             upgrade).</li>
 *         <li>{@code 05 SEC-USR-TYPE  PIC X(01)} (line 22) &rarr; {@link #userType()}</li>
 *         <li>{@code 05 SEC-USR-FILLER PIC X(23)} (line 23) &mdash;
 *             reserved 23-byte filler with no business semantics; not
 *             exposed.</li>
 *       </ul>
 *   </li>
 *   <li><b>BMS Mapset:</b> {@code app/bms/COSGN00.bms} &mdash; the physical
 *       3270 screen carried no per-user identity fields on output (it
 *       displays only the {@code TITLE01/TITLE02} application title and the
 *       {@code ERRMSG} feedback line); the legacy program signalled
 *       authentication success by switching transactions via {@code XCTL}
 *       rather than by populating a response screen. The REST equivalent
 *       must materialise that success state as data in the response body,
 *       which is exactly what this DTO carries.</li>
 *   <li><b>Symbolic Map:</b> {@code app/cpy-bms/COSGN00.CPY} &mdash; the
 *       output variants {@code USERIDO} (line 140) and {@code ERRMSGO}
 *       (line 152) are the only relevant outbound fields for the COBOL
 *       sign-on; user-identity attributes (first name, last name, user
 *       type) were never echoed to the terminal, so the JSON response
 *       shape is necessarily wider than the BMS output buffer.</li>
 * </ul>
 *
 * <p><b>Migration Mapping</b> &mdash; the original
 * {@code EXEC CICS XCTL PROGRAM('COADM01C')} /
 * {@code EXEC CICS XCTL PROGRAM('COMEN01C')} dispatch (lines 231-239 of
 * {@code COSGN00C.cbl}) is replaced by <i>client-side</i> routing keyed off
 * the {@link #userType()} field returned in this DTO. The contract:
 * <ol>
 *   <li>The client POSTs credentials to {@code /api/auth/signin} carrying a
 *       {@link SignonRequestDto} body.</li>
 *   <li>On success the server returns this DTO with a JWT
 *       ({@link #token()}) and the user-identity attributes. The
 *       {@code Authorization: Bearer <token>} header populated from
 *       {@link #token()} replaces the
 *       {@code CARDDEMO-COMMAREA} that CICS automatically chained across
 *       pseudo-conversations (AAP &sect;0.1.1: stateless REST + JWT claim
 *       set replaces CICS COMMAREA).</li>
 *   <li>The client inspects {@link #userType()} (a verbatim copy of the
 *       {@code SEC-USR-TYPE} value from {@code CSUSR01Y.cpy}) and routes to
 *       the admin menu when the value is {@code 'A'} or to the main menu
 *       when the value is {@code 'U'} &mdash; the same selector the COBOL
 *       program applies via the {@code CDEMO-USRTYP-ADMIN} 88-level on
 *       line 230 of {@code COSGN00C.cbl}.</li>
 *   <li>{@link #expiresAt()} reports the JWT's {@code exp} claim as a
 *       Unix epoch (seconds since 1970-01-01 UTC). Clients use this value
 *       to refresh the token proactively rather than waiting for an HTTP
 *       {@code 401 Unauthorized} response (which has no analogue in the
 *       CICS world, where the session was kept alive by the operator's
 *       continued interaction with the same {@code WS-TRANID = 'CC00'}
 *       defined on line 37 of {@code COSGN00C.cbl}).</li>
 * </ol>
 *
 * <p><b>Field-by-Field Contract</b> &mdash; the response shape preserves the
 * BMS / symbolic-map field widths verbatim so downstream consumers (web UI,
 * mobile app, audit pipeline) can rely on the same byte-level guarantees the
 * legacy CICS world provided:
 * <table>
 *   <caption>CSUSR01Y / JWT &rarr; JSON property mapping</caption>
 *   <tr><th>JSON property</th><th>Source</th><th>Width</th><th>Notes</th></tr>
 *   <tr><td>{@code token}</td><td>JWT (newly minted)</td><td>variable</td>
 *       <td>HS256-signed, signing key from AWS Secrets Manager (AAP
 *           &sect;0.6.4); never logged, never persisted, redacted in
 *           {@link #toString()}.</td></tr>
 *   <tr><td>{@code userId}</td><td>{@code SEC-USR-ID PIC X(08)}</td>
 *       <td>8 characters</td>
 *       <td>JWT {@code sub} claim; equal to the uppercased input
 *           {@code USERID} from {@code COSGN0AI} after
 *           {@code FUNCTION UPPER-CASE} (line 132 of
 *           {@code COSGN00C.cbl}).</td></tr>
 *   <tr><td>{@code firstName}</td><td>{@code SEC-USR-FNAME PIC X(20)}</td>
 *       <td>20 characters</td>
 *       <td>Returned trimmed of trailing spaces &mdash; the COBOL field is
 *           space-padded but JSON consumers expect human-readable values.</td></tr>
 *   <tr><td>{@code lastName}</td><td>{@code SEC-USR-LNAME PIC X(20)}</td>
 *       <td>20 characters</td>
 *       <td>Returned trimmed of trailing spaces.</td></tr>
 *   <tr><td>{@code userType}</td><td>{@code SEC-USR-TYPE PIC X(01)}</td>
 *       <td>1 character</td>
 *       <td>Verbatim copy of {@code SEC-USR-TYPE}: {@code 'A'} for admin,
 *           {@code 'U'} for regular user. The values match the COBOL
 *           88-level {@code CDEMO-USRTYP-ADMIN} expectation
 *           ({@code COCOM01Y.cpy}).</td></tr>
 *   <tr><td>{@code expiresAt}</td><td>JWT {@code exp} claim</td>
 *       <td>{@code long} (epoch seconds)</td>
 *       <td>Has no COBOL analogue; required by the stateless REST model
 *           because there is no equivalent of the CICS pseudo-conversational
 *           {@code RETURN TRANSID(...) COMMAREA(...)} that implicitly kept a
 *           session alive (line 98-102 of {@code COSGN00C.cbl}).</td></tr>
 * </table>
 *
 * <p><b>PCI-DSS / PII Handling</b> &mdash; the JWT bearer token in
 * {@link #token()} is a credential equivalent: anyone in possession of the
 * raw token can impersonate the authenticated user until the token expires.
 * Three deliberate safeguards protect this DTO:
 * <ul>
 *   <li>{@link #toString()} is overridden to redact the {@link #token()}
 *       component to {@code ********} so accidental log emission (e.g.,
 *       {@code log.info("Issued sign-on: {}", dto)}) cannot leak the bearer
 *       credential into CloudWatch Logs or OpenSearch indexes (AAP
 *       &sect;0.6.6). The auto-generated record {@code toString()} would
 *       include the full JWT verbatim, which is unacceptable for a bearer
 *       credential.</li>
 *   <li>The COBOL {@code SEC-USR-PWD PIC X(08)} field (line 21 of
 *       {@code CSUSR01Y.cpy}) is <i>never</i> projected onto this response;
 *       the BCrypt-hashed equivalent in the {@code USER_SECURITY} table is
 *       likewise withheld. The class deliberately exposes no
 *       {@code password} component to make accidental leakage a compile
 *       error.</li>
 *   <li>Transport-layer protection is provided by ALB-terminated TLS 1.2+
 *       (AAP &sect;0.6.6) so the JSON body is never on the wire in
 *       plaintext; CloudFront / ALB access logs do not capture response
 *       bodies, only headers and metadata.</li>
 * </ul>
 *
 * <p><b>Immutability</b> &mdash; declared as a Java {@code record} so all
 * components are {@code final} and the value is structurally immutable.
 * This is intentional: response DTOs are produced once by the service
 * layer and then serialised by the Spring MVC dispatcher; downstream code
 * must not mutate the issued token, user identity, or expiry &mdash;
 * doing so would produce drift between the audit log entry written by
 * {@code AuditLogService} (AAP &sect;0.6.6) and the value actually returned
 * to the operator. The record contract eliminates that risk at compile
 * time.
 *
 * <p><b>OpenAPI Surface</b> &mdash; the {@link Schema} annotations on the
 * record itself and on every component are consumed by
 * springdoc-openapi 2.x (AAP &sect;0.5.1) to publish the public REST
 * contract at {@code /v3/api-docs} and {@code /swagger-ui.html}, preserving
 * the COBOL field semantics in the human-readable documentation that
 * downstream consumers (front-end, mobile, batch reconciliation) rely on.
 *
 * @see com.awsm2.carddemo.dto.SignonRequestDto sibling request DTO
 *      carrying the operator-supplied credentials.
 */
@Schema(
        name = "SignonResponse",
        description = "Authentication response body for POST /api/auth/signin. "
                + "Carries the JWT bearer token plus the user-identity "
                + "attributes (USER-ID, FIRST-NAME, LAST-NAME, USER-TYPE) "
                + "drawn from the USRSEC VSAM record defined in "
                + "app/cpy/CSUSR01Y.cpy. The userType field replaces the "
                + "CICS XCTL routing previously performed by COSGN00C.cbl "
                + "(EXEC CICS XCTL PROGRAM 'COADM01C' / 'COMEN01C')."
)
public record SignonResponseDto(

        /**
         * JWT bearer token issued by {@code SignonService} on successful
         * authentication. The token is signed with HS256 using the key
         * fetched from AWS Secrets Manager (AAP &sect;0.6.4 &mdash;
         * dynamic rotation without Spring Boot restart via
         * {@code @RefreshScope} beans), and carries the {@code sub}
         * (user id), {@code role} (user type), and {@code exp}
         * (expiry) claims.
         *
         * <p>Clients send the token back on every subsequent API call as
         * the {@code Authorization: Bearer <token>} HTTP header. The
         * server-side {@code JwtAuthenticationFilter} validates the
         * signature, extracts the claims, and populates the Spring
         * Security {@code SecurityContext}; this replaces the implicit
         * session state CICS carried in the chained
         * {@code CARDDEMO-COMMAREA} between pseudo-conversational
         * transactions (line 100 of {@code COSGN00C.cbl}).
         *
         * <p><b>Security note:</b> the token is a bearer credential
         * &mdash; possession is sufficient for impersonation. The
         * overridden {@link #toString()} below redacts this component
         * so the value cannot leak into log records, audit events,
         * OpenSearch indexes, or CloudWatch Logs.
         */
        @Schema(
                description = "JWT bearer token to be sent on subsequent "
                        + "requests as the value of the "
                        + "'Authorization: Bearer <token>' header. Signed "
                        + "with HS256 using a signing key from AWS Secrets "
                        + "Manager (rotating). Carries 'sub' (user id), "
                        + "'role' (user type), and 'exp' (expiry epoch) "
                        + "claims.",
                example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJBRE1JTjAwMSJ9.signature",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @JsonProperty("token")
        String token,

        /**
         * Authenticated user identifier &mdash; verbatim copy of the
         * {@code SEC-USR-ID PIC X(08)} field declared at line 18 of
         * {@code app/cpy/CSUSR01Y.cpy} and read into {@code SEC-USER-DATA}
         * by the {@code READ-USER-SEC-FILE} paragraph (lines 209-219 of
         * {@code app/cbl/COSGN00C.cbl}).
         *
         * <p>The value is the uppercased identifier the operator typed on
         * the BMS {@code USERID} field (after the
         * {@code MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI) TO
         * WS-USER-ID} transformation at line 132 of
         * {@code COSGN00C.cbl}); it is also embedded as the {@code sub}
         * claim of the JWT in {@link #token()} so the server can
         * correlate subsequent requests with this user without a
         * database round-trip.
         *
         * <p>Width: 8 characters, alphanumeric. The COBOL field is
         * space-padded; this DTO returns the trimmed value so JSON
         * consumers receive a clean identifier.
         */
        @Schema(
                description = "Authenticated user identifier. Maps to "
                        + "SEC-USR-ID PIC X(08) from CSUSR01Y.cpy (line 18). "
                        + "Same value as the JWT 'sub' claim. Trimmed of "
                        + "trailing spaces.",
                example = "ADMIN001",
                maxLength = 8,
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @JsonProperty("userId")
        String userId,

        /**
         * User first name &mdash; verbatim copy of the
         * {@code SEC-USR-FNAME PIC X(20)} field declared at line 19 of
         * {@code app/cpy/CSUSR01Y.cpy}.
         *
         * <p>Width: 20 characters. The COBOL field is space-padded; this
         * DTO returns the trimmed value so JSON consumers receive a
         * human-readable name without trailing whitespace.
         *
         * <p>The COBOL sign-on program does not echo this field to the
         * 3270 screen (it is consumed only as part of the role-routing
         * decision and is otherwise carried implicitly in the user's
         * COMMAREA-bound session), so the JSON response is necessarily
         * wider than the BMS output contract. This is expected and
         * required for downstream consumers (e.g., the main menu UI
         * displays "Welcome, FirstName LastName").
         */
        @Schema(
                description = "User first name. Maps to "
                        + "SEC-USR-FNAME PIC X(20) from CSUSR01Y.cpy "
                        + "(line 19). Trimmed of trailing spaces.",
                example = "Administrator",
                maxLength = 20,
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @JsonProperty("firstName")
        String firstName,

        /**
         * User last name &mdash; verbatim copy of the
         * {@code SEC-USR-LNAME PIC X(20)} field declared at line 20 of
         * {@code app/cpy/CSUSR01Y.cpy}.
         *
         * <p>Width: 20 characters. The COBOL field is space-padded; this
         * DTO returns the trimmed value.
         */
        @Schema(
                description = "User last name. Maps to "
                        + "SEC-USR-LNAME PIC X(20) from CSUSR01Y.cpy "
                        + "(line 20). Trimmed of trailing spaces.",
                example = "User",
                maxLength = 20,
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @JsonProperty("lastName")
        String lastName,

        /**
         * User type / role &mdash; verbatim copy of the
         * {@code SEC-USR-TYPE PIC X(01)} field declared at line 22 of
         * {@code app/cpy/CSUSR01Y.cpy}. The value is the selector the
         * COBOL program uses to dispatch the next CICS transaction at
         * lines 230-240 of {@code COSGN00C.cbl} via the
         * {@code CDEMO-USRTYP-ADMIN} 88-level (defined in
         * {@code COCOM01Y.cpy}):
         * <ul>
         *   <li>{@code 'A'} &mdash; Admin user. The COBOL program issues
         *       {@code EXEC CICS XCTL PROGRAM('COADM01C')} (line 232) to
         *       hand off to the admin menu. In the REST world the client
         *       reads this value and routes to the admin menu UI
         *       (calling {@code GET /api/menu/admin}).</li>
         *   <li>{@code 'U'} &mdash; Regular user. The COBOL program
         *       issues {@code EXEC CICS XCTL PROGRAM('COMEN01C')} (line
         *       237) to hand off to the main menu. In the REST world the
         *       client routes to the main menu UI (calling
         *       {@code GET /api/menu/main}).</li>
         * </ul>
         *
         * <p>The {@code allowableValues} attribute on the OpenAPI schema
         * constrains the public contract to those two values, matching
         * the 88-level definition the COBOL world relied on
         * (AAP &sect;0.7.3 &mdash; document all COBOL-to-Java translations
         * with inline references to the original paragraph/section).
         *
         * <p>Width: 1 character.
         */
        @Schema(
                description = "User role selector. Maps to "
                        + "SEC-USR-TYPE PIC X(01) from CSUSR01Y.cpy "
                        + "(line 22). 'A' = admin (legacy program "
                        + "COADM01C); 'U' = regular user (legacy program "
                        + "COMEN01C). Client routes to the appropriate "
                        + "menu UI based on this value.",
                example = "A",
                maxLength = 1,
                allowableValues = {"A", "U"},
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @JsonProperty("userType")
        String userType,

        /**
         * JWT expiration timestamp as a Unix epoch (seconds since
         * 1970-01-01 UTC). Equals the {@code exp} claim embedded in
         * {@link #token()}.
         *
         * <p>Clients should refresh the token <i>before</i> this instant
         * to avoid receiving HTTP {@code 401 Unauthorized} on subsequent
         * requests. The recommended client behaviour is to refresh when
         * the remaining lifetime drops below a small safety margin (for
         * example, 60 seconds before expiry).
         *
         * <p>This component has no COBOL analogue: the CICS
         * pseudo-conversational model kept the session alive implicitly
         * by chaining {@code CARDDEMO-COMMAREA} across
         * {@code EXEC CICS RETURN TRANSID('CC00')} calls (lines 98-102
         * of {@code COSGN00C.cbl}). The REST model requires an explicit
         * lifetime so the client and server agree on when re-authentication
         * is necessary &mdash; this is a structural side-effect of the
         * stateless migration target, not a behavioural change to the
         * sign-on business logic.
         */
        @Schema(
                description = "JWT expiration timestamp (seconds since "
                        + "1970-01-01 UTC). Mirrors the JWT 'exp' claim. "
                        + "Clients should refresh the token before this "
                        + "instant to avoid 401 Unauthorized responses.",
                example = "1716212100",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @JsonProperty("expiresAt")
        Long expiresAt
) {

    /**
     * Sentinel used by {@link #toString()} to redact the JWT bearer
     * token component so that accidental string interpolation of this
     * DTO into log records, audit events, OpenSearch documents, or
     * CloudTrail events cannot leak the credential.
     *
     * <p>This value intentionally does not reveal the length, encoding,
     * or any other metadata of the original token.
     */
    private static final String REDACTED_TOKEN = "********";

    /**
     * Returns a diagnostic string representation that intentionally
     * <i>redacts</i> the JWT bearer token.
     *
     * <p>Java {@code record} types auto-generate a {@code toString()} of
     * the form {@code "SignonResponseDto[token=..., userId=..., ...]"}
     * which would emit the bearer token verbatim into any log line that
     * interpolates this DTO. Overriding {@code toString()} here is a
     * deliberate defence-in-depth measure: even if a downstream
     * developer writes
     * {@code log.info("Issued sign-on: {}", dto)} (a common mistake),
     * the token never reaches CloudWatch Logs, OpenSearch, or any other
     * sink. This complements the structured-logging redaction filters
     * declared in {@code logback-spring.xml} and the Macie S3 scanning
     * documented in AAP &sect;0.6.6.
     *
     * <p>The user identity fields ({@code userId}, {@code firstName},
     * {@code lastName}, {@code userType}) and the expiration timestamp
     * are <i>not</i> redacted because they are non-secret and are
     * required for correlation across audit and diagnostic logs &mdash;
     * mirroring the COBOL audit trail which records
     * {@code CDEMO-USER-ID} and {@code CDEMO-USER-TYPE} on every
     * transaction (AAP &sect;0.7.2 &mdash; "audit trail content must be
     * preserved exactly").
     *
     * <p><b>NOTE on record-generated {@code equals()}/{@code hashCode()}
     * (accepted risk per AAP &sect;0.6.6).</b>  Java records auto-generate
     * {@link Object#equals(Object)} and {@link Object#hashCode()} over
     * every component &mdash; including the {@code token} component
     * &mdash; and the Java record contract forbids overriding these
     * methods to exclude components without converting the type to a
     * regular class (a significant scope expansion that would also
     * break the AAP-mandated Minimal Change Clause).  The risk that
     * the JWT bearer token participates in equality/hash operations is
     * <b>accepted</b> because:
     * <ul>
     *   <li>{@link Object#equals(Object)} and {@link Object#hashCode()}
     *       on this response DTO are not invoked by any audit, logging,
     *       caching, or persistence path &mdash; only {@code toString()}
     *       (which is redacted) reaches CloudWatch / OpenSearch.</li>
     *   <li>The DTO instance is constructed by the controller, written
     *       to the HTTP response body, and discarded; no reference is
     *       retained beyond the response cycle.</li>
     *   <li>Debugger inspection and heap-dump analysis are governed by
     *       the platform's PCI-DSS access controls (AAP &sect;0.6.6)
     *       and are out of scope for DTO-level mitigation.</li>
     * </ul>
     *
     * @return a human-readable representation of this DTO with the
     *         token component replaced by {@value #REDACTED_TOKEN}.
     */
    @Override
    public String toString() {
        return "SignonResponseDto[userId=" + userId
                + ", firstName=" + firstName
                + ", lastName=" + lastName
                + ", userType=" + userType
                + ", expiresAt=" + expiresAt
                + ", token=" + REDACTED_TOKEN + "]";
    }
}
