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
 * Add-User request DTO (admin-only).
 *
 * <p>Replaces the 3270 add-user screen rendered by the COBOL/CICS program
 * {@code COUSR01C.cbl} (CICS transaction id {@code CU01}, BMS mapset
 * {@code COUSR01}, map {@code COUSR1A}).
 *
 * <p>In the source, {@code COUSR01C} performs the following pseudo-conversational
 * flow on a first-time invocation (paragraphs {@code MAIN-PARA},
 * {@code PROCESS-ENTER-KEY}, {@code WRITE-USER-SEC-FILE}):
 * <ol>
 *   <li>{@code SEND MAP COUSR1A} &mdash; renders a blank entry form with
 *       five operator-editable fields ({@code FNAME}, {@code LNAME},
 *       {@code USERID}, {@code PASSWD}, {@code USRTYPE}) and the literal
 *       hint {@code (A=Admin, U=User)} at line 150 of the BMS source.</li>
 *   <li>{@code RECEIVE MAP COUSR1A} &mdash; reads the operator-supplied
 *       values into {@code COUSR1AI}.</li>
 *   <li>{@code PROCESS-ENTER-KEY} paragraph (lines 115-160 of
 *       {@code COUSR01C.cbl}) evaluates each of the five input fields in
 *       turn:
 *       <ul>
 *         <li>{@code WHEN FNAMEI = SPACES OR LOW-VALUES} &rarr; emit
 *             {@code 'First Name can NOT be empty...'}.</li>
 *         <li>{@code WHEN LNAMEI = SPACES OR LOW-VALUES} &rarr; emit
 *             {@code 'Last Name can NOT be empty...'}.</li>
 *         <li>{@code WHEN USERIDI = SPACES OR LOW-VALUES} &rarr; emit
 *             {@code 'User ID can NOT be empty...'}.</li>
 *         <li>{@code WHEN PASSWDI = SPACES OR LOW-VALUES} &rarr; emit
 *             {@code 'Password can NOT be empty...'}.</li>
 *         <li>{@code WHEN USRTYPEI = SPACES OR LOW-VALUES} &rarr; emit
 *             {@code 'User Type can NOT be empty...'}.</li>
 *       </ul>
 *       Any failure resets {@code WS-ERR-FLG} to {@code 'Y'} and re-sends
 *       the map with the error message in red.</li>
 *   <li>When all five fields pass validation, the values are moved into
 *       the {@code SEC-USER-DATA} record (defined in
 *       {@code CSUSR01Y.cpy}) and {@code WRITE-USER-SEC-FILE} issues
 *       {@code EXEC CICS WRITE FILE('USRSEC') FROM(SEC-USER-DATA)
 *       RIDFLD(SEC-USR-ID)} against the {@code USRSEC} VSAM KSDS
 *       (CISZ=8192, RECLN=80, KEYLEN=8, starting at offset 1).</li>
 *   <li>A duplicate-key condition (CICS {@code DFHRESP(DUPREC)} / VSAM
 *       {@code FILE STATUS '22'}) raises an in-program error and the
 *       message {@code 'User ID already exists...'} is shown.</li>
 * </ol>
 *
 * <p>In the Java target, the equivalent flow is implemented by
 * {@code UserAddService.add(dto)} (one-service-per-COBOL-program per
 * AAP &sect;0.7.1):
 * <ol>
 *   <li>The Jakarta Bean Validation constraints below ({@link NotBlank},
 *       {@link Size}, {@link Pattern}) replicate the COBOL non-empty and
 *       value-domain checks at the controller boundary &mdash; invalid
 *       input is rejected with HTTP {@code 400 Bad Request} via
 *       {@code GlobalExceptionHandler} (mapping {@code MethodArgumentNotValidException}),
 *       so the service is never invoked when input is malformed.</li>
 *   <li>The service BCrypt-hashes {@link #password()} before persisting
 *       to {@code user_security.sec_usr_pwd} (security upgrade per AAP
 *       &sect;0.1.1 &mdash; the legacy {@code USRSEC} file stored
 *       plaintext credentials, which is incompatible with PCI-DSS
 *       requirements per AAP &sect;0.6.6).</li>
 *   <li>The service issues
 *       {@code userSecurityRepository.save(new UserSecurity(...))}
 *       which Spring Data JPA translates to an {@code INSERT} into
 *       {@code user_security} (the table created by Flyway migration
 *       {@code V010__create_user_security.sql} per AAP &sect;0.4.1).
 *       The repository call replaces the COBOL {@code EXEC CICS WRITE}
 *       verb.</li>
 *   <li>A {@code DataIntegrityViolationException} thrown by the JDBC
 *       driver on primary-key collision (PostgreSQL unique constraint
 *       violation) is caught by the service and rethrown as
 *       {@code DuplicateRecordException} which {@code GlobalExceptionHandler}
 *       surfaces as HTTP {@code 409 Conflict} &mdash; preserving the
 *       semantics of the COBOL {@code 'User ID already exists...'} message
 *       (replaces COBOL file-status {@code 22} DUPKEY / CICS
 *       {@code DFHRESP(DUPREC)} handling per AAP &sect;0.1.1).</li>
 *   <li>On success the service returns the persisted entity as a
 *       {@code UserListDto.UserRow}-shaped response (passwords never
 *       returned) and HTTP {@code 201 Created} with a {@code Location}
 *       header pointing at {@code /api/admin/users/{userId}} is emitted by
 *       {@code UserAdminController.addUser(...)}.</li>
 * </ol>
 *
 * <p><b>BMS field origin &mdash; COUSR1A map mapping:</b>
 * <pre>{@code
 *   BMS field   Length   CSUSR01Y field      Record component   Direction
 *   ---------   ------   ----------------    ----------------   ---------
 *   USERID      X(08)    SEC-USR-ID          userId             input
 *   FNAME       X(20)    SEC-USR-FNAME       firstName          input
 *   LNAME       X(20)    SEC-USR-LNAME       lastName           input
 *   PASSWD      X(08)    SEC-USR-PWD         password           input (dark)
 *   USRTYPE     X(01)    SEC-USR-TYPE        userType           input
 * }</pre>
 *
 * <p>The {@code PASSWD} BMS field carries the {@code DRK} (dark-display)
 * attribute on line 126 of {@code app/bms/COUSR01.bms} &mdash; the
 * operator typed characters were rendered invisibly on the 3270 terminal.
 * The Java target preserves this confidentiality posture via the
 * {@code accessMode = WRITE_ONLY} OpenAPI declaration below and the
 * redacting {@link #toString()} override.
 *
 * <p><b>COBOL Provenance (AAP &sect;0.7.3 traceability):</b>
 * <ul>
 *   <li>BMS Mapset: {@code app/bms/COUSR01.bms} (mapset {@code COUSR01},
 *       map {@code COUSR1A})</li>
 *   <li>Symbolic Map: {@code app/cpy-bms/COUSR01.CPY}
 *       ({@code COUSR1AI} / {@code COUSR1AO})</li>
 *   <li>Program: {@code app/cbl/COUSR01C.cbl} (CICS transaction
 *       {@code CU01}, paragraphs {@code MAIN-PARA},
 *       {@code PROCESS-ENTER-KEY}, {@code SEND-USRADD-SCREEN},
 *       {@code RECEIVE-USRADD-SCREEN}, {@code WRITE-USER-SEC-FILE})</li>
 *   <li>Record Layout: {@code app/cpy/CSUSR01Y.cpy}
 *       ({@code SEC-USER-DATA}, 80 bytes)</li>
 *   <li>VSAM Cluster: {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS}
 *       (RECLN=80, KEYLEN=8)</li>
 *   <li>Migration Script: {@code src/main/resources/db/migration/V010__create_user_security.sql}</li>
 * </ul>
 *
 * <p><b>REST endpoint mapping</b> (per AAP &sect;0.3.4):
 * <ul>
 *   <li>{@code POST /api/admin/users} &mdash; request body contains this
 *       DTO. Response on success is HTTP {@code 201 Created} with a
 *       {@code Location} header set to {@code /api/admin/users/{userId}}
 *       and a body shaped like {@code UserListDto.UserRow} (without the
 *       password). On duplicate user-id, HTTP {@code 409 Conflict} with a
 *       {@code DuplicateRecordException} error payload. On validation
 *       failure, HTTP {@code 400 Bad Request} with field-level errors
 *       enumerated by {@code GlobalExceptionHandler}.</li>
 *   <li>Secured via Spring Security {@code @PreAuthorize("hasRole('ADMIN')")}
 *       on {@code UserAdminController.addUser(...)} &mdash; admin role is
 *       indicated by the persisted {@code user_security.sec_usr_type = 'A'}
 *       per AAP &sect;0.3.4 and the {@code SecurityConfig} JWT role
 *       mapping.</li>
 * </ul>
 *
 * <p><b>Security &amp; PCI-DSS handling (AAP &sect;0.7.1, &sect;0.6.6):</b>
 * <ul>
 *   <li>{@link #password()} carries a plaintext credential on the request
 *       path only; the service hashes it via BCrypt (per Spring Security 6
 *       {@code BCryptPasswordEncoder}) before persistence so the plaintext
 *       never reaches the database. The OpenAPI
 *       {@code accessMode = WRITE_ONLY} attribute documents that the field
 *       must never appear on any response payload.</li>
 *   <li>The legacy COBOL program stored the password in plaintext in the
 *       {@code SEC-USR-PWD PIC X(08)} field of the {@code USRSEC} VSAM
 *       KSDS &mdash; an acknowledged migration-time security upgrade per
 *       AAP &sect;0.1.1 ("Plaintext password storage in the source
 *       USRSEC file must be upgraded to BCrypt hashing in the Java target
 *       &mdash; a deliberate security improvement within the scope of
 *       PCI-DSS compliance"). The corresponding
 *       {@code user_security.sec_usr_pwd} column is sized 60 characters
 *       (BCrypt output length).</li>
 *   <li>The custom {@link #toString()} override below redacts the password
 *       to prevent accidental exposure in CloudWatch Logs, audit trails,
 *       or {@code Slf4j} debug statements &mdash; satisfying the "no
 *       plaintext card/account data in logs" PCI-DSS constraint in AAP
 *       &sect;0.6.6 (extended here to credential material).</li>
 *   <li>{@code SEC-USR-FILLER} ({@code PIC X(23)}) is unused padding in
 *       the on-disk layout (lines 23 of {@code CSUSR01Y.cpy}) and carries
 *       no business meaning &mdash; not mapped to any record component.</li>
 *   <li>This endpoint is admin-only; access control is enforced at the
 *       controller layer via {@code @PreAuthorize("hasRole('ADMIN')")}.</li>
 *   <li>The Bean Validation constraints below replicate the inline
 *       non-empty checks scattered through the {@code PROCESS-ENTER-KEY}
 *       paragraph (lines 117-151 of {@code COUSR01C.cbl}) so invalid
 *       input is rejected at the controller boundary without ever
 *       invoking the service or repository.</li>
 *   <li>The {@code ^[AU]$} pattern on {@link #userType()} enforces the
 *       binary value domain implied by the COUSR01.bms map literal
 *       "{@code (A=Admin, U=User)}" at line 150 of the BMS source
 *       &mdash; a constraint that, in the legacy code, was implicit and
 *       relied on operator discipline.</li>
 *   <li>The {@code ^[A-Za-z0-9]+$} pattern on {@link #userId()} excludes
 *       whitespace and control characters that would corrupt the
 *       primary-key index in PostgreSQL; the legacy program tolerated
 *       embedded spaces because COBOL {@code MOVE} left-justifies and
 *       pads with spaces, but the relational target requires a stricter
 *       contract.</li>
 * </ul>
 *
 * <p>This record is immutable; field setters do not exist by design. All
 * mutation flows through service-layer re-construction.
 *
 * @param userId    the new user's unique identifier. Maps to
 *                  {@code SEC-USR-ID PIC X(08)} in {@code CSUSR01Y.cpy}
 *                  and to BMS field {@code USERID} in
 *                  {@code COUSR01.bms}. Required; alphanumeric; up to 8
 *                  characters. The primary key of the
 *                  {@code user_security} JPA entity.
 * @param firstName the new user's first name. Maps to
 *                  {@code SEC-USR-FNAME PIC X(20)} and BMS field
 *                  {@code FNAME}. Required; up to 20 characters.
 * @param lastName  the new user's last name. Maps to
 *                  {@code SEC-USR-LNAME PIC X(20)} and BMS field
 *                  {@code LNAME}. Required; up to 20 characters.
 * @param password  the new user's plaintext password. Maps to
 *                  {@code SEC-USR-PWD PIC X(08)} and BMS field
 *                  {@code PASSWD} (dark-display attribute on the 3270
 *                  terminal). Required; up to 8 characters; hashed via
 *                  BCrypt by the service before persistence; never
 *                  appears in any response body or log line.
 * @param userType  the new user's type code. Maps to
 *                  {@code SEC-USR-TYPE PIC X(01)} and BMS field
 *                  {@code USRTYPE}. Required; exactly one character;
 *                  must match {@code ^[AU]$} &mdash; {@code "A"} grants
 *                  admin privileges (access to {@code COADM01C} and the
 *                  user-administration suite), {@code "U"} denotes a
 *                  regular user.
 */
@Schema(name = "UserAddDto",
        description = "Add-User request DTO (admin-only). Replaces the "
                + "3270 add-user screen rendered by COBOL program "
                + "COUSR01C / BMS mapset COUSR01 / map COUSR1A. Carries the "
                + "five operator-supplied fields from CSUSR01Y.cpy "
                + "(userId, firstName, lastName, password, userType). "
                + "Submitted via POST /api/admin/users and secured by "
                + "@PreAuthorize(\"hasRole('ADMIN')\") on UserAdminController. "
                + "The plaintext password is BCrypt-hashed by the service "
                + "before persistence (security upgrade per AAP §0.1.1).")
public record UserAddDto(

        @NotBlank(message = "User ID is required")
        @Size(max = 8, message = "User ID must be at most 8 characters")
        @Pattern(regexp = "^[A-Za-z0-9]+$",
                message = "User ID must be alphanumeric")
        @Schema(description = "Unique identifier for the new user. "
                + "Replicates the COUSR01C.cbl PROCESS-ENTER-KEY non-empty "
                + "check (WHEN USERIDI = SPACES OR LOW-VALUES emits "
                + "'User ID can NOT be empty...') and the implicit "
                + "8-character primary-key length from CSUSR01Y.cpy. "
                + "The alphanumeric pattern excludes whitespace and "
                + "control characters that would corrupt the PostgreSQL "
                + "primary-key index. Maps to COBOL SEC-USR-ID PIC X(08) "
                + "in CSUSR01Y.cpy and to BMS field USERID in "
                + "COUSR01.bms.",
                example = "USER0001",
                maxLength = 8,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("userId")
        String userId,

        @NotBlank(message = "First name is required")
        @Size(max = 20, message = "First name must be at most 20 characters")
        @Schema(description = "New user's first name. Replicates the "
                + "COUSR01C.cbl PROCESS-ENTER-KEY non-empty check "
                + "(WHEN FNAMEI = SPACES OR LOW-VALUES emits 'First Name "
                + "can NOT be empty...'). Maps to COBOL SEC-USR-FNAME "
                + "PIC X(20) in CSUSR01Y.cpy and to BMS field FNAME in "
                + "COUSR01.bms.",
                example = "John",
                maxLength = 20,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("firstName")
        String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 20, message = "Last name must be at most 20 characters")
        @Schema(description = "New user's last name. Replicates the "
                + "COUSR01C.cbl PROCESS-ENTER-KEY non-empty check "
                + "(WHEN LNAMEI = SPACES OR LOW-VALUES emits 'Last Name "
                + "can NOT be empty...'). Maps to COBOL SEC-USR-LNAME "
                + "PIC X(20) in CSUSR01Y.cpy and to BMS field LNAME in "
                + "COUSR01.bms.",
                example = "Doe",
                maxLength = 20,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("lastName")
        String lastName,

        @NotBlank(message = "Password is required")
        @Size(max = 8, message = "Password must be at most 8 characters")
        @Schema(description = "Plaintext password — hashed (BCrypt) by "
                + "the service before persistence; never returned in any "
                + "response body or log line (see the redacting "
                + "toString() override on this DTO). Replicates the "
                + "COUSR01C.cbl PROCESS-ENTER-KEY non-empty check "
                + "(WHEN PASSWDI = SPACES OR LOW-VALUES emits 'Password "
                + "can NOT be empty...'). The legacy USRSEC file stored "
                + "this value as plaintext in SEC-USR-PWD PIC X(08); the "
                + "Java target stores the BCrypt hash in "
                + "user_security.sec_usr_pwd (60 chars) per AAP §0.1.1 "
                + "security upgrade. Maps to COBOL SEC-USR-PWD PIC X(08) "
                + "in CSUSR01Y.cpy and to BMS field PASSWD (DRK "
                + "dark-display attribute) in COUSR01.bms.",
                example = "Pa55w0rd",
                maxLength = 8,
                requiredMode = Schema.RequiredMode.REQUIRED,
                accessMode = Schema.AccessMode.WRITE_ONLY)
        @JsonProperty("password")
        String password,

        @NotBlank(message = "User type is required")
        @Pattern(regexp = "^[AU]$",
                message = "User type must be 'A' (admin) or 'U' (regular user)")
        @Schema(description = "User type code. \"A\" denotes admin "
                + "(granted access to COADM01C and the user-administration "
                + "suite); \"U\" denotes a regular user. The ^[AU]$ "
                + "pattern enforces the binary value domain implied by "
                + "the COUSR01.bms map literal '(A=Admin, U=User)' "
                + "(line 150 of the BMS source) and replicates the "
                + "COUSR01C.cbl non-empty check (WHEN USRTYPEI = SPACES "
                + "OR LOW-VALUES emits 'User Type can NOT be empty...'). "
                + "Maps to COBOL SEC-USR-TYPE PIC X(01) in CSUSR01Y.cpy "
                + "and to BMS field USRTYPE in COUSR01.bms.",
                example = "U",
                allowableValues = {"A", "U"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("userType")
        String userType
) {

    /**
     * Returns a redacted string representation suitable for safe logging.
     *
     * <p>The default {@code toString()} generated by the Java {@code record}
     * contract would include the raw {@link #password()} value in any log
     * statement that captures the DTO &mdash; a direct violation of the
     * PCI-DSS-aligned posture in AAP &sect;0.6.6 ("no plaintext card/account
     * data in logs"; extended here to all credential material). This
     * override:
     * <ul>
     *   <li>Substitutes {@code "********"} (eight asterisks, matching the
     *       legacy {@code SEC-USR-PWD PIC X(08)} field width) for the
     *       {@link #password()} value regardless of whether it is set
     *       &mdash; the value is never disclosed in logs, audit trails,
     *       or debug output.</li>
     *   <li>Renders {@link #userId()}, {@link #firstName()},
     *       {@link #lastName()}, and {@link #userType()} verbatim; these
     *       are operational identifiers, not cardholder data, and are safe
     *       to log.</li>
     * </ul>
     *
     * <p>This override is invoked anywhere the JVM stringifies the DTO,
     * including {@code Logger.debug(..., dto)}, {@code String.format("%s", dto)},
     * collection {@code toString()}, and exception messages that interpolate
     * the request body. Spring Boot's default {@code MethodArgumentNotValidException}
     * handler does <em>not</em> stringify the request body, so this override
     * is a defense-in-depth measure rather than a load-bearing control &mdash;
     * but the PCI-DSS scope of CardDemo (per AAP &sect;0.6.6) requires
     * defense-in-depth on credential material.
     *
     * @return a human-readable, credential-safe representation of this DTO
     */
    @Override
    public String toString() {
        return "UserAddDto[userId=" + userId
             + ", firstName=" + firstName
             + ", lastName=" + lastName
             + ", userType=" + userType
             + ", password=********]";
    }
}
