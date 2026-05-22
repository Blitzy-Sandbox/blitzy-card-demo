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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Sign-on (authentication) request DTO carrying the credentials submitted by
 * the operator on the {@code POST /api/auth/signin} REST endpoint.
 *
 * <p><b>COBOL Provenance</b> &mdash; this DTO is the modern target counterpart
 * to the three CardDemo source artifacts that together implement the 3270
 * sign-on screen:
 * <ul>
 *   <li><b>BMS Mapset:</b> {@code app/bms/COSGN00.bms} (mapset {@code COSGN00},
 *       map {@code COSGN0A}) &mdash; the physical 3270 screen definition. The
 *       map declares two operator-editable input fields:
 *       <ul>
 *         <li>{@code USERID DFHMDF ATTRB=(FSET,IC,NORM,UNPROT), LENGTH=8,
 *             POS=(19,43)} (lines 156-160) &mdash; cursor home, 8 characters,
 *             unprotected so the operator may type, FSET so the field is
 *             always returned on {@code RECEIVE MAP}.</li>
 *         <li>{@code PASSWD DFHMDF ATTRB=(DRK,FSET,UNPROT), LENGTH=8,
 *             POS=(20,43)} (lines 175-180) &mdash; identical length and FSET
 *             semantics, plus {@code DRK} (darkened) so the password is not
 *             echoed to the screen during entry.</li>
 *       </ul>
 *   </li>
 *   <li><b>Symbolic Map:</b> {@code app/cpy-bms/COSGN00.CPY} (lines 67-78)
 *       &mdash; the BMS-generated COBOL copybook that exposes the screen
 *       buffer to the application program. The input variants the
 *       authentication path consumes are:
 *       <ul>
 *         <li>{@code 02 USERIDI PIC X(8)} (line 72) &mdash; the user-typed
 *             user identifier as received from the terminal.</li>
 *         <li>{@code 02 PASSWDI PIC X(8)} (line 78) &mdash; the user-typed
 *             password as received from the terminal.</li>
 *       </ul>
 *   </li>
 *   <li><b>Program:</b> {@code app/cbl/COSGN00C.cbl} &mdash; the CICS
 *       pseudo-conversational program that drives the screen. The paragraph
 *       {@code PROCESS-ENTER-KEY} (lines 108-140) consumes
 *       {@code USERIDI OF COSGN0AI} and {@code PASSWDI OF COSGN0AI}, runs the
 *       non-empty validation rules
 *       ({@code WHEN USERIDI = SPACES OR LOW-VALUES} at line 118 and
 *       {@code WHEN PASSWDI = SPACES OR LOW-VALUES} at line 123),
 *       upper-cases both fields ({@code MOVE FUNCTION UPPER-CASE(...)} at
 *       lines 132-136) and then calls {@code READ-USER-SEC-FILE} (lines
 *       209-219) against the {@code USRSEC} VSAM KSDS for credential
 *       verification.</li>
 * </ul>
 *
 * <p><b>Migration Mapping</b> &mdash; the original CICS pseudo-conversational
 * round trip ({@code SEND MAP COSGN0A} &rarr; operator types &rarr;
 * {@code RECEIVE MAP COSGN0A} &rarr; {@code RETURN TRANSID('CC00')
 * COMMAREA(...)}) is replaced by a single stateless HTTP exchange:
 * <ol>
 *   <li>The client (web UI, mobile app, or downstream system) submits an
 *       HTTP {@code POST} to {@code /api/auth/signin} with this DTO as the
 *       JSON request body.</li>
 *   <li>The Spring MVC dispatcher binds the JSON onto an instance of this
 *       record and invokes Jakarta Bean Validation (because the controller
 *       method parameter is annotated {@code @Valid}). The
 *       {@link NotBlank}, {@link Size}, and {@link Pattern} constraints
 *       below replicate the COBOL non-empty and value-domain checks
 *       declaratively at the controller boundary; invalid input is rejected
 *       with HTTP {@code 400 Bad Request} via
 *       {@code GlobalExceptionHandler} mapping
 *       {@code MethodArgumentNotValidException}, so the service is never
 *       invoked when input is malformed (mirrors the COBOL early-return
 *       from {@code PROCESS-ENTER-KEY} when {@code WS-ERR-FLG = 'Y'}).</li>
 *   <li>{@code AuthController} forwards the validated record to
 *       {@code SignonService.authenticate(dto)} &mdash; the one-to-one Java
 *       counterpart to {@code COSGN00C}'s {@code READ-USER-SEC-FILE}
 *       paragraph (AAP &sect;0.7.1: one {@code @Service} per COBOL program).</li>
 *   <li>The service uppercases the {@link #userId()}, looks up the matching
 *       {@code UserSecurity} JPA entity through
 *       {@code UserSecurityRepository.findById(...)} (replaces VSAM
 *       {@code EXEC CICS READ DATASET('USRSEC')}), and uses the configured
 *       BCrypt {@code PasswordEncoder} to verify the supplied
 *       {@link #password()} against the stored hash &mdash; an explicit
 *       security upgrade over the source's plaintext password field
 *       {@code SEC-USR-PWD PIC X(08)} defined in {@code CSUSR01Y.cpy} (AAP
 *       &sect;0.1.1 &mdash; deliberate PCI-DSS-aligned improvement).</li>
 *   <li>On success the service issues a JWT carrying {@code userId} and
 *       {@code userType} claims (replacing the
 *       {@code CDEMO-USER-ID}/{@code CDEMO-USER-TYPE} fields the COBOL
 *       program writes to {@code CARDDEMO-COMMAREA} at lines 224-228 before
 *       the {@code EXEC CICS XCTL} to {@code COADM01C} or {@code COMEN01C}).
 *       The JWT is returned in {@code SignonResponseDto} and bears the
 *       role-routing information the CICS world carried in the COMMAREA.</li>
 * </ol>
 *
 * <p><b>Validation Contract</b> &mdash; the field-level constraints below
 * preserve the BMS field semantics exactly:
 * <table>
 *   <caption>BMS &rarr; Jakarta validation mapping</caption>
 *   <tr><th>BMS field</th><th>Symbolic map field</th><th>BMS length</th>
 *       <th>BMS attributes</th><th>Java component</th><th>Constraints</th></tr>
 *   <tr><td>{@code USERID}</td><td>{@code USERIDI}</td><td>8</td>
 *       <td>{@code (FSET,IC,NORM,UNPROT)}</td><td>{@link #userId()}</td>
 *       <td>{@link NotBlank}, {@link Size}{@code (max=8)},
 *           {@link Pattern}{@code (^[A-Za-z0-9]+$)}</td></tr>
 *   <tr><td>{@code PASSWD}</td><td>{@code PASSWDI}</td><td>8</td>
 *       <td>{@code (DRK,FSET,UNPROT)} &mdash; DRK = darkened/hidden</td>
 *       <td>{@link #password()}</td>
 *       <td>{@link NotBlank}, {@link Size}{@code (max=8)}</td></tr>
 * </table>
 *
 * <p><b>PCI-DSS / PII Handling</b> &mdash; the password component carries
 * unencrypted credential material in transit between the controller boundary
 * and {@code SignonService}; it must <i>never</i> appear in application logs,
 * audit events, OpenSearch indexes, or any toString-derived diagnostic
 * output. Two deliberate safeguards in this class:
 * <ul>
 *   <li>{@link #toString()} is overridden to redact the password to
 *       {@code ********} so accidental log emission (e.g.,
 *       {@code log.info("Received request: {}", dto)}) cannot leak the
 *       credential (replaces the Java {@code record} auto-generated
 *       {@code toString} which would include every component verbatim).</li>
 *   <li>The OpenAPI {@link Schema#accessMode()} on the password field is
 *       {@link Schema.AccessMode#WRITE_ONLY} so springdoc-openapi documents
 *       the field as a request-only property &mdash; response-shaped schemas
 *       generated from this DTO omit it. This aligns with the BMS {@code DRK}
 *       attribute that hid the password from the 3270 screen.</li>
 * </ul>
 *
 * <p>Transport-layer protection is provided by ALB-terminated TLS 1.2+ (AAP
 * &sect;0.6.6) so the JSON body is never on the wire in plaintext, and at
 * rest the credential lives only as a BCrypt hash in the
 * {@code USER_SECURITY} table (AAP &sect;0.1.1).
 *
 * <p><b>Immutability</b> &mdash; declared as a Java {@code record} so all
 * components are {@code final} and the value is structurally immutable.
 * This is intentional: request DTOs are read-only contracts between the
 * Spring MVC dispatcher and the {@code @Service} layer, and immutability
 * eliminates a class of bugs where downstream code might inadvertently
 * mutate the bound request (e.g., upper-casing the user id) and confuse
 * later logging or audit.
 *
 * @see com.awsm2.carddemo.dto.SignonResponseDto sibling response DTO
 *      carrying the issued JWT and routing claims.
 */
@Schema(
        name = "SignonRequest",
        description = "Authentication request body for POST /api/auth/signin. "
                + "Replaces the BMS COSGN00 / COSGN0A 3270 sign-on screen "
                + "(program COSGN00C.cbl)."
)
public record SignonRequestDto(

        /**
         * Operator-supplied user identifier. Replicates the
         * {@code USERIDI PIC X(8)} input variant from the BMS symbolic map
         * {@code app/cpy-bms/COSGN00.CPY} (line 72), backed by the
         * {@code USERID DFHMDF ATTRB=(FSET,IC,NORM,UNPROT), LENGTH=8}
         * physical field at lines 156-160 of {@code app/bms/COSGN00.bms}.
         *
         * <p>Validation rules:
         * <ul>
         *   <li>{@link NotBlank} replicates the COBOL guard
         *       {@code WHEN USERIDI OF COSGN0AI = SPACES OR LOW-VALUES} at
         *       {@code app/cbl/COSGN00C.cbl} line 118 (paragraph
         *       {@code PROCESS-ENTER-KEY}) which sets the error message
         *       {@code "Please enter User ID ..."} and re-displays the
         *       screen.</li>
         *   <li>{@link Size}{@code (max = 8)} caps the input at the BMS
         *       field length so an oversized payload does not silently
         *       truncate. The original BMS field could not have received
         *       more than 8 characters from a 3270 device; we enforce the
         *       same physical contract on the REST boundary.</li>
         *   <li>{@link Pattern}{@code (^[A-Za-z0-9]+$)} restricts the
         *       identifier to alphanumeric characters. The COBOL program
         *       uppercases the input via
         *       {@code MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI) TO
         *       WS-USER-ID} (line 132) before keying the
         *       {@code USRSEC} VSAM read; we tolerate mixed case at the
         *       boundary and let the service uppercase before lookup so
         *       legacy client behaviour is preserved.</li>
         * </ul>
         *
         * <p>The {@link JsonProperty} binding pins the JSON wire name to
         * {@code userId} so refactoring the Java identifier (e.g., to
         * {@code username}) cannot accidentally break the public REST
         * contract documented in OpenAPI.
         */
        @NotBlank(message = "User ID is required")
        @Size(max = 8, message = "User ID must be at most 8 characters")
        @Pattern(regexp = "^[A-Za-z0-9]+$",
                message = "User ID must be alphanumeric")
        @Schema(
                description = "Operator user identifier. 1-8 alphanumeric "
                        + "characters; case-insensitive at the service layer "
                        + "(the service uppercases before keying the USRSEC "
                        + "lookup, mirroring COBOL MOVE FUNCTION UPPER-CASE). "
                        + "Maps to BMS field USERID (LENGTH=8) and symbolic "
                        + "map USERIDI PIC X(8).",
                example = "ADMIN001",
                maxLength = 8,
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @JsonProperty("userId")
        String userId,

        /**
         * Operator-supplied password (plaintext at the controller
         * boundary). Replicates the {@code PASSWDI PIC X(8)} input variant
         * from {@code app/cpy-bms/COSGN00.CPY} (line 78), backed by the
         * {@code PASSWD DFHMDF ATTRB=(DRK,FSET,UNPROT), LENGTH=8} field at
         * lines 175-180 of {@code app/bms/COSGN00.bms}. The {@code DRK}
         * (darkened) attribute caused the 3270 device to suppress echo;
         * the REST equivalent is the OpenAPI
         * {@link Schema.AccessMode#WRITE_ONLY} mode below, which marks the
         * field as a request-only property in generated documentation, and
         * the overridden {@link #toString()} which redacts the value.
         *
         * <p>Validation rules:
         * <ul>
         *   <li>{@link NotBlank} replicates the COBOL guard
         *       {@code WHEN PASSWDI OF COSGN0AI = SPACES OR LOW-VALUES} at
         *       {@code app/cbl/COSGN00C.cbl} line 123 (paragraph
         *       {@code PROCESS-ENTER-KEY}) which sets the error message
         *       {@code "Please enter Password ..."} and re-displays the
         *       screen.</li>
         *   <li>{@link Size}{@code (max = 8)} caps the input at the BMS
         *       field length so the REST surface accepts no more than the
         *       physical contract did.</li>
         * </ul>
         *
         * <p>No character-class {@link Pattern} is applied; the COBOL
         * program does not constrain password character class either &mdash;
         * it simply uppercases and compares
         * ({@code IF SEC-USR-PWD = WS-USER-PWD} at line 223). In the Java
         * target, comparison happens against a BCrypt hash via
         * {@code PasswordEncoder.matches(rawPassword, encodedPassword)},
         * which itself imposes no character restrictions.
         *
         * <p><b>Security note:</b> this field is never logged, never echoed
         * to a response body, and never indexed in OpenSearch or
         * CloudWatch Logs (enforced by the overridden {@link #toString()}
         * below and by the structured logger configuration in
         * {@code logback-spring.xml}).
         */
        @NotBlank(message = "Password is required")
        @Size(max = 8, message = "Password must be at most 8 characters")
        @Schema(
                description = "Operator password (1-8 characters). Hidden on "
                        + "the 3270 screen via the DRK (darkened) attribute "
                        + "in the BMS source; in the REST world the field is "
                        + "marked WRITE_ONLY in OpenAPI documentation so it "
                        + "never appears in response schemas. Maps to BMS "
                        + "field PASSWD (LENGTH=8) and symbolic map PASSWDI "
                        + "PIC X(8).",
                example = "Pa55w0rd",
                maxLength = 8,
                requiredMode = Schema.RequiredMode.REQUIRED,
                accessMode = Schema.AccessMode.WRITE_ONLY
        )
        @JsonProperty("password")
        String password
) {

    /**
     * Sentinel used by {@link #toString()} to redact the password field so
     * that accidental string interpolation of this DTO into log records,
     * audit events, OpenSearch documents, or CloudTrail events cannot leak
     * the operator-supplied credential.
     *
     * <p>This value intentionally does not reveal the length, character
     * class, or any other metadata of the original password.
     */
    private static final String REDACTED_PASSWORD = "********";

    /**
     * Returns a diagnostic string representation that intentionally
     * <i>redacts</i> the password.
     *
     * <p>Java {@code record} types auto-generate a {@code toString()} of
     * the form {@code "SignonRequestDto[userId=..., password=...]"} which
     * would emit the password verbatim into any log line that interpolates
     * this DTO. Overriding {@code toString()} here is a deliberate
     * defence-in-depth measure: even if a downstream developer writes
     * {@code log.info("Signon request: {}", dto)} (a common mistake), the
     * password never reaches CloudWatch Logs, OpenSearch, or any other
     * sink. This complements the structured-logging redaction filters
     * declared in {@code logback-spring.xml} and the Macie S3 scanning
     * documented in AAP &sect;0.6.6.
     *
     * <p>The user id is <i>not</i> redacted because it is non-secret and
     * is required for correlation across audit and diagnostic logs.
     *
     * @return a human-readable representation of this DTO with the
     *         password component replaced by {@value #REDACTED_PASSWORD}.
     */
    @Override
    public String toString() {
        return "SignonRequestDto[userId=" + userId
                + ", password=" + REDACTED_PASSWORD + "]";
    }
}
