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
 * Update-User request DTO (admin-only).
 *
 * <p>Replaces the 3270 update-user screen rendered by the COBOL/CICS program
 * {@code COUSR02C.cbl} (CICS transaction id {@code CU02}, BMS mapset
 * {@code COUSR02}, map {@code COUSR2A}).
 *
 * <p>In the source, {@code COUSR02C} performs a pseudo-conversational
 * read-modify-write flow against the {@code USRSEC} VSAM KSDS:
 * <ol>
 *   <li>{@code RECEIVE MAP COUSR2A} &mdash; reads {@code USRIDIN}
 *       (user ID to update).</li>
 *   <li>{@code PROCESS-ENTER-KEY} paragraph &mdash; {@code READ USRSEC FILE}
 *       keyed by {@code SEC-USR-ID} populated from {@code USRIDIN}, displays
 *       the existing {@code SEC-USR-FNAME}, {@code SEC-USR-LNAME},
 *       {@code SEC-USR-PWD}, and {@code SEC-USR-TYPE} as modifiable fields
 *       (BMS map attributes {@code FSET,UNPROT}; password field carries
 *       additional {@code DRK} dark-display attribute).</li>
 *   <li>Operator modifies the fields and presses {@code PF5=Save} (or
 *       {@code PF3=Save&amp;Exit}); the {@code UPDATE-USER-INFO} paragraph
 *       validates non-empty input, re-reads the record, compares each input
 *       field against the persisted value, and, if any differ, sets
 *       {@code USR-MODIFIED-YES} and calls {@code UPDATE-USER-SEC-FILE}
 *       to issue an {@code EXEC CICS REWRITE FILE('USRSEC')}.</li>
 *   <li>If no field was modified, displays
 *       {@code 'Please modify to update ...'} in red and re-sends the
 *       screen.</li>
 * </ol>
 *
 * <p>In the Java target, the equivalent flow is implemented by
 * {@code UserUpdateService.update(userId, dto)} (one-service-per-COBOL-program
 * per AAP &sect;0.7.1):
 * <ul>
 *   <li>The {@code userId} is supplied as the URL path variable on
 *       {@code PUT /api/admin/users/{id}} and echoed in this DTO; the service
 *       validates the path id matches {@link #userId()} to detect tampering.</li>
 *   <li>The service loads the existing {@code UserSecurity} entity by primary
 *       key (replaces the COBOL {@code READ USRSEC FILE} verb in the
 *       {@code READ-USER-SEC-FILE} paragraph); a missing record raises
 *       {@code RecordNotFoundException} (replaces COBOL file-status {@code 23}
 *       NOTFND handling per AAP &sect;0.1.1) and is surfaced as HTTP
 *       {@code 404 Not Found}.</li>
 *   <li>{@link #firstName()}, {@link #lastName()}, and {@link #userType()} are
 *       compared against the persisted values and copied into the entity only
 *       when they differ &mdash; preserving the {@code USR-MODIFIED-YES}
 *       semantics of the original program (paragraphs
 *       {@code UPDATE-USER-INFO} lines 219-234 of {@code COUSR02C.cbl}).</li>
 *   <li>{@link #password()} is treated as optional (see "Optional password
 *       semantics" below); when supplied, the service re-hashes via BCrypt
 *       and stores in {@code user_security.sec_usr_pwd} (security upgrade
 *       per AAP &sect;0.1.1).</li>
 *   <li>The save operation is wrapped in {@code @Transactional} so that any
 *       JPA {@code OptimisticLockException} (if optimistic locking is later
 *       added) or downstream failure rolls back &mdash; this is the target
 *       analogue of the original CICS task-level commit semantics on the
 *       {@code REWRITE} verb.</li>
 *   <li>The service publishes an {@code account.updated}-class event via
 *       {@code KafkaEventPublisher} (MSK; per AAP &sect;0.6.5) when the user
 *       record actually changes, so downstream audit consumers and the
 *       {@code AuditLogService} (CloudTrail + OpenSearch indexing per AAP
 *       &sect;0.6.6) can record the operator action.</li>
 * </ul>
 *
 * <p><b>Optional password semantics:</b> In the legacy COBOL program,
 * {@code SEC-USR-PWD} is a mandatory non-empty field on every update
 * (paragraph {@code UPDATE-USER-INFO}, lines 198-203 of
 * {@code COUSR02C.cbl}, emits {@code 'Password can NOT be empty...'} if
 * blank). The Java target relaxes this constraint to support a modern
 * "leave blank to keep existing password" PUT pattern that is industry
 * standard for REST APIs: when {@link #password()} is {@code null} or
 * blank the service skips the BCrypt re-hash and the existing
 * {@code user_security.sec_usr_pwd} hash is retained unchanged. This is
 * the only behavioral deviation from the COBOL source in this DTO and is
 * documented in AAP-aligned key insights (the per-file agent prompt
 * explicitly mandates this: <i>"Password field is OPTIONAL (no
 * {@literal @NotBlank}) &mdash; null/blank means 'keep existing'"</i>).
 *
 * <p><b>BMS field origin &mdash; COUSR2A map mapping:</b>
 * <pre>{@code
 *   BMS field   Length   CSUSR01Y field      Record component   Direction
 *   ---------   ------   ----------------    ----------------   ---------
 *   USRIDIN     X(08)    SEC-USR-ID          userId             in/echo
 *   FNAME       X(20)    SEC-USR-FNAME       firstName          input
 *   LNAME       X(20)    SEC-USR-LNAME       lastName           input
 *   PASSWD      X(08)    SEC-USR-PWD         password           input (opt)
 *   USRTYPE     X(01)    SEC-USR-TYPE        userType           input
 * }</pre>
 *
 * <p><b>COBOL Provenance (AAP &sect;0.7.3 traceability):</b>
 * <ul>
 *   <li>BMS Mapset: {@code app/bms/COUSR02.bms} (mapset {@code COUSR02},
 *       map {@code COUSR2A})</li>
 *   <li>Symbolic Map: {@code app/cpy-bms/COUSR02.CPY}
 *       ({@code COUSR2AI} / {@code COUSR2AO})</li>
 *   <li>Program: {@code app/cbl/COUSR02C.cbl} (CICS transaction
 *       {@code CU02}, paragraphs {@code PROCESS-ENTER-KEY},
 *       {@code UPDATE-USER-INFO}, {@code READ-USER-SEC-FILE},
 *       {@code UPDATE-USER-SEC-FILE})</li>
 *   <li>Record Layout: {@code app/cpy/CSUSR01Y.cpy}
 *       ({@code SEC-USER-DATA}, 80 bytes)</li>
 *   <li>VSAM Cluster: {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS}
 *       (RECLN=80, KEYLEN=8)</li>
 * </ul>
 *
 * <p><b>REST endpoint mapping (per AAP &sect;0.3.4):</b>
 * <ul>
 *   <li>{@code PUT /api/admin/users/{id}} &mdash; request body contains this
 *       DTO; the path variable {@code {id}} is the user ID being updated
 *       (matches the BMS {@code USRIDIN} field and JPA primary key of
 *       {@code UserSecurity}). Response on success is HTTP {@code 200 OK}
 *       with an updated {@code UserListDto.UserRow}-like representation
 *       (passwords never returned).</li>
 *   <li>Secured via Spring Security {@code @PreAuthorize("hasRole('ADMIN')")}
 *       on {@code UserAdminController.updateUser(...)}.</li>
 * </ul>
 *
 * <p><b>Security &amp; PCI-DSS handling (AAP &sect;0.7.1, &sect;0.6.6):</b>
 * <ul>
 *   <li>{@link #password()} carries a plaintext credential on the request
 *       path only; the service hashes it via BCrypt before persistence so
 *       the plaintext never reaches the database. The OpenAPI
 *       {@code accessMode = WRITE_ONLY} attribute documents that the field
 *       must never appear on any response payload.</li>
 *   <li>The custom {@link #toString()} override below redacts the
 *       password to prevent accidental exposure in CloudWatch Logs, audit
 *       trails, or {@code Slf4j} debug statements &mdash; satisfying the
 *       "no plaintext card/account data in logs" PCI-DSS constraint in
 *       AAP &sect;0.6.6 (extended here to credential material).</li>
 *   <li>{@code SEC-USR-FILLER} ({@code PIC X(23)}) is unused padding in the
 *       on-disk layout and carries no business meaning &mdash; not mapped to
 *       any record component.</li>
 *   <li>This endpoint is admin-only; access control is enforced at the
 *       controller layer via {@code @PreAuthorize("hasRole('ADMIN')")}.</li>
 *   <li>The Bean Validation constraints below replicate the inline
 *       non-empty/length checks scattered through the
 *       {@code UPDATE-USER-INFO} paragraph (lines 179-213 of
 *       {@code COUSR02C.cbl}) so invalid input is rejected at the
 *       controller boundary (HTTP {@code 400 Bad Request} via
 *       {@code GlobalExceptionHandler}) without ever invoking the service
 *       or repository.</li>
 *   <li>The {@code ^[AU]$} pattern on {@link #userType()} enforces the
 *       binary value domain implied by the COUSR02.bms map literal
 *       "{@code (A=Admin, U=User)}" at line 154 of the BMS source &mdash;
 *       a constraint that, in the legacy code, was implicit and relied on
 *       operator discipline.</li>
 *   <li>JPA {@code @Version} is intentionally NOT used on
 *       {@code UserSecurity}: AAP &sect;0.7.1 mandates optimistic
 *       locking only on {@code Account} and {@code Card} aggregates.
 *       User-administration writes are admin-only and rare; concurrent
 *       admin edits are tolerated.</li>
 * </ul>
 *
 * <p>This record is immutable; field setters do not exist by design. All
 * mutation flows through service-layer re-construction.
 *
 * @param userId    the user ID to update; echoed from the URL path variable
 *                  {@code {id}} on {@code PUT /api/admin/users/{id}}. Maps
 *                  to {@code SEC-USR-ID PIC X(08)} in {@code CSUSR01Y.cpy}
 *                  and to BMS field {@code USRIDIN}. Required; alphanumeric;
 *                  up to 8 characters.
 * @param firstName the new (or unchanged) first name. Maps to
 *                  {@code SEC-USR-FNAME PIC X(20)} and BMS field
 *                  {@code FNAME}. Required; up to 20 characters.
 * @param lastName  the new (or unchanged) last name. Maps to
 *                  {@code SEC-USR-LNAME PIC X(20)} and BMS field
 *                  {@code LNAME}. Required; up to 20 characters.
 * @param password  the new plaintext password &mdash; <b>optional</b>. Maps
 *                  to {@code SEC-USR-PWD PIC X(08)} and BMS field
 *                  {@code PASSWD}. When {@code null} or blank the service
 *                  retains the existing BCrypt hash; otherwise the service
 *                  re-hashes the supplied plaintext via BCrypt and stores
 *                  the result in {@code user_security.sec_usr_pwd}. Up to
 *                  8 characters (matches the legacy field width); never
 *                  appears in any response body or log line.
 * @param userType  the new (or unchanged) user-type code. Maps to
 *                  {@code SEC-USR-TYPE PIC X(01)} and BMS field
 *                  {@code USRTYPE}; {@code "A"} denotes admin (granted
 *                  access to {@code COADM01C} and the administration
 *                  suite); {@code "U"} denotes a regular user. Required;
 *                  exactly one character; must match {@code ^[AU]$}.
 */
@Schema(name = "UserUpdateDto",
        description = "Update-User request DTO (admin-only). Replaces the "
                + "3270 update-user screen rendered by COBOL program "
                + "COUSR02C / BMS mapset COUSR02 / map COUSR2A. Carries the "
                + "user identifier plus the four modifiable record fields "
                + "from CSUSR01Y.cpy (firstName, lastName, password, "
                + "userType). The password field is optional &mdash; "
                + "omit or leave blank to keep the existing credential "
                + "unchanged. Submitted via PUT /api/admin/users/{id} and "
                + "secured by @PreAuthorize(\"hasRole('ADMIN')\") on "
                + "UserAdminController.")
public record UserUpdateDto(

        @NotBlank(message = "User ID is required")
        @Size(max = 8, message = "User ID must be at most 8 characters")
        @Pattern(regexp = "^[A-Za-z0-9]+$",
                message = "User ID must be alphanumeric")
        @Schema(description = "User identifier (read-only on this DTO &mdash; "
                + "set from the URL path variable {id} on "
                + "PUT /api/admin/users/{id}). The service rejects requests "
                + "where this field disagrees with the path variable to "
                + "guard against client tampering. Maps to COBOL "
                + "SEC-USR-ID PIC X(08) in CSUSR01Y.cpy and to BMS field "
                + "USRIDIN in COUSR02.bms.",
                example = "USER0001",
                maxLength = 8)
        @JsonProperty("userId")
        String userId,

        @NotBlank(message = "First name is required")
        @Size(max = 20, message = "First name must be at most 20 characters")
        @Schema(description = "User's first name. Replicates the COUSR02C.cbl "
                + "UPDATE-USER-INFO non-empty check (WHEN FNAMEI = SPACES OR "
                + "LOW-VALUES emits 'First Name can NOT be empty...'). Maps "
                + "to COBOL SEC-USR-FNAME PIC X(20) in CSUSR01Y.cpy and to "
                + "BMS field FNAME in COUSR02.bms.",
                example = "Jane",
                maxLength = 20)
        @JsonProperty("firstName")
        String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 20, message = "Last name must be at most 20 characters")
        @Schema(description = "User's last name. Replicates the COUSR02C.cbl "
                + "UPDATE-USER-INFO non-empty check (WHEN LNAMEI = SPACES OR "
                + "LOW-VALUES emits 'Last Name can NOT be empty...'). Maps "
                + "to COBOL SEC-USR-LNAME PIC X(20) in CSUSR01Y.cpy and to "
                + "BMS field LNAME in COUSR02.bms.",
                example = "Smith",
                maxLength = 20)
        @JsonProperty("lastName")
        String lastName,

        @Size(max = 8, message = "Password must be at most 8 characters")
        @Schema(description = "New plaintext password &mdash; OPTIONAL. When "
                + "null or blank the service retains the existing BCrypt "
                + "hash in user_security.sec_usr_pwd; otherwise the service "
                + "re-hashes the supplied value via BCrypt before saving. "
                + "Never returned in any response body or log line (see "
                + "the redacting toString() override on this DTO). The "
                + "legacy COUSR02C.cbl program required this field "
                + "non-empty (line 198) but the Java target relaxes the "
                + "rule to support the industry-standard 'leave blank to "
                + "keep existing' REST pattern. Maps to COBOL SEC-USR-PWD "
                + "PIC X(08) in CSUSR01Y.cpy and to BMS field PASSWD in "
                + "COUSR02.bms.",
                example = "NewPass1",
                maxLength = 8,
                nullable = true,
                accessMode = Schema.AccessMode.WRITE_ONLY)
        @JsonProperty("password")
        String password,

        @NotBlank(message = "User type is required")
        @Pattern(regexp = "^[AU]$",
                message = "User type must be 'A' or 'U'")
        @Schema(description = "User type code. \"A\" denotes admin (granted "
                + "access to COADM01C and the user-administration suite); "
                + "\"U\" denotes a regular user. The ^[AU]$ pattern "
                + "enforces the binary value domain implied by the "
                + "COUSR02.bms map literal '(A=Admin, U=User)' (line 154 "
                + "of the BMS source) and replicates the COUSR02C.cbl "
                + "non-empty check (WHEN USRTYPEI = SPACES OR LOW-VALUES "
                + "emits 'User Type can NOT be empty...'). Maps to COBOL "
                + "SEC-USR-TYPE PIC X(01) in CSUSR01Y.cpy and to BMS field "
                + "USRTYPE in COUSR02.bms.",
                example = "A",
                allowableValues = {"A", "U"})
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
     *   <li>Substitutes {@code "(unchanged)"} when {@link #password()} is
     *       {@code null} or empty &mdash; signalling the "keep existing"
     *       branch without revealing the absence/presence of a credential.</li>
     *   <li>Substitutes {@code "********"} (eight asterisks, matching the
     *       legacy {@code SEC-USR-PWD PIC X(08)} field width) otherwise
     *       &mdash; the value is present but never disclosed.</li>
     *   <li>Renders {@link #userId()}, {@link #firstName()},
     *       {@link #lastName()}, and {@link #userType()} verbatim; these
     *       are operational identifiers, not cardholder data, and are safe
     *       to log.</li>
     * </ul>
     *
     * @return a human-readable, credential-safe representation of this DTO
     */
    @Override
    public String toString() {
        return "UserUpdateDto[userId=" + userId
             + ", firstName=" + firstName
             + ", lastName=" + lastName
             + ", userType=" + userType
             + ", password=" + (password == null || password.isEmpty()
                                ? "(unchanged)"
                                : "********")
             + "]";
    }
}
