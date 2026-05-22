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
import jakarta.validation.constraints.Pattern;

/**
 * Delete-User confirmation DTO (admin-only).
 *
 * <p>Replaces the 3270 delete-user confirmation screen rendered by the
 * COBOL/CICS program {@code COUSR03C.cbl} (CICS transaction id {@code CU03},
 * BMS mapset {@code COUSR03}, map {@code COUSR3A}).
 *
 * <p>In the source, {@code COUSR03C} performs the following pseudo-conversational
 * flow:
 * <ol>
 *   <li>{@code RECEIVE MAP COUSR3A} &mdash; reads {@code USRIDIN} (user ID
 *       to delete).</li>
 *   <li>{@code READ USRSEC FILE} keyed by {@code SEC-USR-ID} populated from
 *       {@code USRIDIN}.</li>
 *   <li>Displays {@code SEC-USR-FNAME}, {@code SEC-USR-LNAME}, and
 *       {@code SEC-USR-TYPE} as read-only confirmation fields (BMS map
 *       attributes {@code ASKIP,FSET,NORM}).</li>
 *   <li>When the operator presses {@code PF5=Delete}, the
 *       {@code DELETE-USER-INFO} paragraph re-reads the record and issues an
 *       {@code EXEC CICS DELETE FILE('USRSEC')} against the
 *       {@code USRSEC VSAM KSDS}.</li>
 * </ol>
 *
 * <p>In the Java target, this DTO carries both the read-only display fields
 * (echoed from the persisted {@code UserSecurity} entity) and the
 * {@link #confirm()} input flag that signals the operator's intent. The
 * actual delete is performed by the service layer via
 * {@code UserDeleteService.delete(userId, dto.confirm())} which in turn calls
 * {@code userSecurityRepository.deleteById(userId)} on the Spring Data JPA
 * repository (replacing the COBOL {@code EXEC CICS DELETE FILE} verb).
 * {@code RecordNotFoundException} is thrown if the user record is absent
 * (replaces COBOL file-status {@code 23} NOTFND handling per AAP &sect;0.1.1).
 *
 * <p><b>REST endpoint mapping</b> (per AAP &sect;0.3.4):
 * <ul>
 *   <li>{@code DELETE /api/admin/users/{id}} &mdash; request body contains
 *       this DTO with {@code confirm="Y"}; the path variable
 *       {@code {id}} is the user ID being deleted (matches the BMS
 *       {@code USRIDIN} field). Response on success is HTTP {@code 204
 *       No Content}; the read-only fields ({@link #firstName()},
 *       {@link #lastName()}, {@link #userType()}) may be optionally returned
 *       in a preview/echo payload before the confirmed delete.</li>
 * </ul>
 *
 * <p><b>BMS field origin &mdash; COUSR3A map mapping:</b>
 * <pre>{@code
 *   BMS field   Length   CSUSR01Y field      Record component   Direction
 *   ---------   ------   ----------------    ----------------   ----------
 *   USRIDIN     X(08)    SEC-USR-ID          userId             display
 *   FNAME       X(20)    SEC-USR-FNAME       firstName          display
 *   LNAME       X(20)    SEC-USR-LNAME       lastName           display
 *   USRTYPE     X(01)    SEC-USR-TYPE        userType           display
 *   (PF5 key)   --       --                  confirm            input
 * }</pre>
 *
 * <p><b>COBOL Provenance (AAP &sect;0.7.3 traceability):</b>
 * <ul>
 *   <li>BMS Mapset: {@code app/bms/COUSR03.bms} (mapset {@code COUSR03},
 *       map {@code COUSR3A})</li>
 *   <li>Symbolic Map: {@code app/cpy-bms/COUSR03.CPY}</li>
 *   <li>Program: {@code app/cbl/COUSR03C.cbl} (CICS transaction
 *       {@code CU03}, paragraph {@code DELETE-USER-INFO})</li>
 *   <li>Record Layout: {@code app/cpy/CSUSR01Y.cpy}
 *       ({@code SEC-USER-DATA}, 80 bytes)</li>
 *   <li>VSAM Cluster: {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS}
 *       (RECLN=80, KEYLEN=8)</li>
 * </ul>
 *
 * <p><b>Security &amp; PCI-DSS handling (AAP &sect;0.7.1, &sect;0.6.6):</b>
 * <ul>
 *   <li>{@code SEC-USR-PWD} ({@code PIC X(08)}) is <b>intentionally excluded</b>
 *       from this DTO. Even on a delete-confirmation flow there is no
 *       requirement to display credentials, and surfacing them on a response
 *       body or request payload would violate AAP &sect;0.6.6's PCI-DSS
 *       posture and the "no plaintext card/account data in logs" constraint.
 *       Passwords are stored as BCrypt hashes in the {@code UserSecurity}
 *       JPA entity (security upgrade vs. the original plaintext
 *       {@code USRSEC} file) and never leave the persistence layer.</li>
 *   <li>{@code SEC-USR-FILLER} ({@code PIC X(23)}) is unused padding in the
 *       on-disk layout and carries no business meaning &mdash; not mapped.</li>
 *   <li>This endpoint is admin-only; access control is enforced at the
 *       controller layer via {@code @PreAuthorize("hasRole('ADMIN')")} in
 *       {@code UserAdminController}.</li>
 *   <li>The Bean Validation constraint on {@link #confirm()} (regex
 *       {@code ^[YN]$}) replicates the binary {@code PF5=Delete} /
 *       {@code F4=Clear} branch of the COBOL
 *       {@code EVALUATE EIBAID} dispatcher in
 *       {@code COUSR03C.cbl} MAIN-PARA, ensuring no out-of-domain value
 *       can drive the delete branch.</li>
 *   <li>The default {@code toString()} generated by the {@code record}
 *       contract is safe to log because no PCI/PII/credential field appears
 *       on this DTO (user names are operational identifiers, not
 *       cardholder data).</li>
 * </ul>
 *
 * <p><b>Input vs. output semantics:</b> Of the five record components, only
 * {@link #confirm()} is operator input; the other four
 * ({@link #userId()}, {@link #firstName()}, {@link #lastName()},
 * {@link #userType()}) are read-only display fields populated by the service
 * layer from the persisted {@code UserSecurity} record. The OpenAPI
 * {@code accessMode = READ_ONLY} attribute documents this contract for
 * springdoc-openapi schema generation so that client generators
 * (TypeScript, OpenAPI Generator targets, etc.) correctly model the field
 * direction.
 *
 * <p>This record is immutable; it carries no monetary fields, no card
 * primary account numbers, and no credential material.
 *
 * @param userId    the user ID to delete (display-only on response; echoed
 *                  from the URL path variable {@code {id}}). Maps to
 *                  {@code SEC-USR-ID PIC X(08)} in {@code CSUSR01Y.cpy} and
 *                  to BMS field {@code USRIDIN}.
 * @param firstName the user's first name (display-only on response;
 *                  populated by the service from the persisted entity to
 *                  confirm the correct record is being deleted). Maps to
 *                  {@code SEC-USR-FNAME PIC X(20)} and BMS field
 *                  {@code FNAME}.
 * @param lastName  the user's last name (display-only on response). Maps to
 *                  {@code SEC-USR-LNAME PIC X(20)} and BMS field
 *                  {@code LNAME}.
 * @param userType  the user-type code (display-only on response). Maps to
 *                  {@code SEC-USR-TYPE PIC X(01)} and BMS field
 *                  {@code USRTYPE}; {@code "A"} denotes admin,
 *                  {@code "U"} denotes a regular user (per BMS map literal
 *                  "(A=Admin, U=User)" at line 139 of {@code COUSR03.bms}).
 * @param confirm   the confirmation flag &mdash; the only operator-input
 *                  field. {@code "Y"} confirms deletion (replaces COBOL
 *                  {@code WHEN DFHPF5 PERFORM DELETE-USER-INFO} at line 121
 *                  of {@code COUSR03C.cbl}); {@code "N"} cancels (replaces
 *                  {@code WHEN DFHPF4 PERFORM CLEAR-CURRENT-SCREEN}). Any
 *                  other value is rejected by the {@code @Pattern}
 *                  constraint and surfaced as an HTTP {@code 400 Bad
 *                  Request} by {@code GlobalExceptionHandler}.
 */
@Schema(name = "UserDeleteDto",
        description = "Delete-User confirmation DTO (admin-only). Replaces the "
                + "3270 delete-user screen rendered by COBOL program COUSR03C "
                + "/ BMS mapset COUSR03 / map COUSR3A. Carries four read-only "
                + "display fields (userId, firstName, lastName, userType) "
                + "echoed from the USRSEC record (CSUSR01Y.cpy) plus the "
                + "single operator-input flag 'confirm' that drives the "
                + "delete branch (PF5=Delete in the source).")
public record UserDeleteDto(

        @Schema(description = "User identifier being deleted (display-only). "
                + "Maps to COBOL SEC-USR-ID PIC X(08) in CSUSR01Y.cpy and to "
                + "BMS field USRIDIN in COUSR03.bms. Echoed from the URL "
                + "path variable {id} on DELETE /api/admin/users/{id}.",
                example = "USER0001",
                maxLength = 8,
                accessMode = Schema.AccessMode.READ_ONLY)
        @JsonProperty("userId")
        String userId,

        @Schema(description = "User's first name (display-only confirmation). "
                + "Maps to COBOL SEC-USR-FNAME PIC X(20) in CSUSR01Y.cpy and "
                + "to BMS field FNAME in COUSR03.bms. Populated by the "
                + "service from the persisted UserSecurity entity so the "
                + "operator can confirm the correct record is being deleted.",
                example = "John",
                maxLength = 20,
                accessMode = Schema.AccessMode.READ_ONLY)
        @JsonProperty("firstName")
        String firstName,

        @Schema(description = "User's last name (display-only confirmation). "
                + "Maps to COBOL SEC-USR-LNAME PIC X(20) in CSUSR01Y.cpy and "
                + "to BMS field LNAME in COUSR03.bms.",
                example = "Doe",
                maxLength = 20,
                accessMode = Schema.AccessMode.READ_ONLY)
        @JsonProperty("lastName")
        String lastName,

        @Schema(description = "User type code (display-only confirmation). "
                + "Maps to COBOL SEC-USR-TYPE PIC X(01) in CSUSR01Y.cpy and "
                + "to BMS field USRTYPE in COUSR03.bms. \"A\" denotes an "
                + "admin user (granted access to COADM01C and the user "
                + "administration suite); \"U\" denotes a regular user.",
                example = "U",
                allowableValues = {"A", "U"},
                accessMode = Schema.AccessMode.READ_ONLY)
        @JsonProperty("userType")
        String userType,

        @Schema(description = "Confirmation flag &mdash; the only operator-"
                + "input field on this DTO. \"Y\" confirms the deletion "
                + "(replaces the COBOL WHEN DFHPF5 PERFORM "
                + "DELETE-USER-INFO branch in COUSR03C.cbl MAIN-PARA); "
                + "\"N\" cancels (replaces the WHEN DFHPF4 PERFORM "
                + "CLEAR-CURRENT-SCREEN branch). Any other value is "
                + "rejected by Jakarta Bean Validation and surfaced as "
                + "HTTP 400 Bad Request by GlobalExceptionHandler.",
                example = "Y",
                allowableValues = {"Y", "N"})
        @Pattern(regexp = "^[YN]$",
                message = "Confirmation must be 'Y' or 'N'")
        @JsonProperty("confirm")
        String confirm
) {
}
