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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Common context DTO mirroring the CICS {@code CARDDEMO-COMMAREA}.
 *
 * <p>In the source mainframe application, the central COMMAREA (defined in
 * {@code app/cpy/COCOM01Y.cpy} as the 01-level {@code CARDDEMO-COMMAREA}
 * structure) was passed between every CICS program via
 * {@code EXEC CICS RETURN TRANSID(...) COMMAREA(...)} to maintain
 * <i>pseudo-conversational</i> state across screen interactions.  Because CICS
 * pseudo-conversational programs are stateless between user interactions, the
 * COMMAREA was the sole vehicle for propagating "who you are", "where you
 * came from", and "what you were looking at" from one screen to the next.
 *
 * <p><b>COBOL source layout</b> (preserved verbatim from
 * {@code app/cpy/COCOM01Y.cpy} lines 19&ndash;44):</p>
 * <pre>{@code
 *   01 CARDDEMO-COMMAREA.
 *      05 CDEMO-GENERAL-INFO.
 *         10 CDEMO-FROM-TRANID             PIC X(04).
 *         10 CDEMO-FROM-PROGRAM            PIC X(08).
 *         10 CDEMO-TO-TRANID               PIC X(04).
 *         10 CDEMO-TO-PROGRAM              PIC X(08).
 *         10 CDEMO-USER-ID                 PIC X(08).
 *         10 CDEMO-USER-TYPE               PIC X(01).
 *            88 CDEMO-USRTYP-ADMIN         VALUE 'A'.
 *            88 CDEMO-USRTYP-USER          VALUE 'U'.
 *         10 CDEMO-PGM-CONTEXT             PIC 9(01).
 *            88 CDEMO-PGM-ENTER            VALUE 0.
 *            88 CDEMO-PGM-REENTER          VALUE 1.
 *      05 CDEMO-CUSTOMER-INFO.
 *         10 CDEMO-CUST-ID                 PIC 9(09).
 *         10 CDEMO-CUST-FNAME              PIC X(25).
 *         10 CDEMO-CUST-MNAME              PIC X(25).
 *         10 CDEMO-CUST-LNAME              PIC X(25).
 *      05 CDEMO-ACCOUNT-INFO.
 *         10 CDEMO-ACCT-ID                 PIC 9(11).
 *         10 CDEMO-ACCT-STATUS             PIC X(01).
 *      05 CDEMO-CARD-INFO.
 *         10 CDEMO-CARD-NUM                PIC 9(16).
 *      05 CDEMO-MORE-INFO.
 *         10 CDEMO-LAST-MAP                PIC X(7).
 *         10 CDEMO-LAST-MAPSET             PIC X(7).
 * }</pre>
 *
 * <p><b>Migration strategy (AAP &sect;0.3.4):</b> In the Java/Spring Boot
 * target, the original COMMAREA fields are split between several alternative
 * state-passing mechanisms, and most flows do not need this DTO at all:
 * <ul>
 *   <li><b>JWT claims</b> &mdash; {@code CDEMO-USER-ID} and
 *       {@code CDEMO-USER-TYPE} are encoded in the JWT issued by
 *       {@code AuthController} ({@code sub} subject claim and the custom
 *       {@code userType} claim respectively).  They are supplied on every
 *       request via the {@code Authorization: Bearer <token>} header and
 *       are extracted by
 *       {@code com.awsm2.carddemo.security.JwtAuthenticationFilter} into
 *       the Spring Security {@code Authentication} principal.</li>
 *   <li><b>URL path variables</b> &mdash; {@code CDEMO-ACCT-ID} and
 *       {@code CDEMO-CARD-NUM} appear as {@code @PathVariable} segments
 *       in REST URLs ({@code /api/accounts/{id}}, {@code /api/cards/{n}}),
 *       so they need no server-side carrying between requests.</li>
 *   <li><b>Request DTOs</b> &mdash; customer-level data
 *       ({@code CDEMO-CUST-ID}, names) is supplied per-request in
 *       endpoint-specific DTOs such as {@code AccountUpdateDto} or
 *       {@code AccountViewDto}, and no cross-request carrying is needed.</li>
 *   <li><b>This DTO</b> &mdash; present-but-rarely-used for explicit
 *       per-request context passthrough when a service needs to remember
 *       the originating program or last-map (e.g., for transaction-add
 *       confirmation flows that depend on prior screen state).  ALB
 *       sticky sessions or a Spring Session store are the recommended
 *       alternatives (AAP &sect;0.3.4 &mdash; "JWT claims plus per-request
 *       DTOs" is the preferred pattern; sticky sessions are the fallback
 *       for stateful flows).</li>
 * </ul>
 *
 * <p><b>Field omissions vs. source COMMAREA:</b> The on-wire DTO carries
 * a strict subset of {@code CARDDEMO-COMMAREA} on the following rationale:
 * <ul>
 *   <li>{@code CDEMO-TO-TRANID} and {@code CDEMO-TO-PROGRAM} are dropped
 *       &mdash; CICS XCTL routing is replaced by REST endpoint URLs
 *       returned in {@code ApiResponse} envelopes (the client navigates
 *       directly), so the "next program" is implicit in the response.</li>
 *   <li>{@code CDEMO-PGM-CONTEXT} (ENTER / REENTER) is dropped &mdash;
 *       the REST verb (GET, POST, PUT) plus the absence/presence of a
 *       resource ID conveys the same semantic, and no pseudo-conversation
 *       state needs to flow across requests.</li>
 *   <li>{@code CDEMO-CUST-MNAME} (middle name) is dropped &mdash; the
 *       Customer entity already carries the full name set and the
 *       passthrough COMMAREA only needs first/last for header rendering.</li>
 * </ul>
 *
 * <p><b>PCI-DSS handling (AAP &sect;0.6.6):</b> The {@link #cardNumber()}
 * component holds the full 16-digit primary account number (PAN) when
 * present, mirroring the original {@code CDEMO-CARD-NUM PIC 9(16)} field.
 * To prevent inadvertent PAN leakage to log files, CloudWatch, OpenSearch
 * indexes, or stack-trace surfaces, the {@link #toString()} override
 * masks the PAN by retaining only the last four digits and prefixing
 * twelve asterisks.  The full PAN remains accessible only via the
 * accessor {@link #cardNumber()} for code that explicitly needs it (e.g.,
 * the XREF lookup in {@code TransactionPostingService}).  Card verification
 * values (CVV) and Social Security Numbers (SSN) are not part of the
 * source COMMAREA and therefore do not appear in this DTO.
 *
 * <p><b>Wire-format stability:</b> The {@code @JsonProperty} bindings on
 * each component freeze the on-wire JSON contract independently of any
 * future record component renames.  {@code @JsonInclude(NON_NULL)} at the
 * record level suppresses fields whose runtime value is {@code null};
 * because most passthrough COMMAREA fields are optional (a request that
 * does not yet reference a card or account does not populate those
 * fields), this annotation keeps the serialized payloads compact.
 *
 * <p><b>Immutability and thread safety:</b> Java records are implicitly
 * {@code final} with {@code final} components, so instances are immutable
 * and may be freely shared across threads.  No defensive copying of
 * components is required because all components are immutable types
 * ({@link String}, {@link Long}).
 *
 * <p><b>Validation policy:</b> This DTO is a passthrough context payload,
 * not a request input from an external client, so it carries no Jakarta
 * Bean Validation ({@code @NotNull}, {@code @Size}, etc.) annotations.
 * Field-level constraints are documented via the OpenAPI
 * {@link Schema#maxLength() maxLength} and
 * {@link Schema#allowableValues() allowableValues} attributes for
 * documentation purposes; runtime enforcement is the responsibility of
 * the caller that populates the DTO.
 *
 * <p><b>COBOL Provenance:</b>
 * <ul>
 *   <li>Copybook: {@code app/cpy/COCOM01Y.cpy} (01-level
 *       {@code CARDDEMO-COMMAREA})</li>
 *   <li>Used by: every CICS program in {@code app/cbl/CO*.cbl} (18 online
 *       programs &mdash; {@code COSGN00C}, {@code COMEN01C},
 *       {@code COADM01C}, {@code COACTVWC}, {@code COACTUPC},
 *       {@code COCRDLIC}, {@code COCRDSLC}, {@code COCRDUPC},
 *       {@code COTRN00C}, {@code COTRN01C}, {@code COTRN02C},
 *       {@code COBIL00C}, {@code CORPT00C}, {@code COUSR00C},
 *       {@code COUSR01C}, {@code COUSR02C}, {@code COUSR03C}).</li>
 *   <li>Replaced operationally by: JWT claims (user identity), URL path
 *       variables (resource IDs), per-request DTOs (form data), and ALB
 *       sticky sessions or Spring Session (stateful flows).</li>
 * </ul>
 *
 * @param tranId             4-character CICS transaction ID identifying
 *                           the originating transaction; maps to
 *                           {@code CDEMO-GENERAL-INFO.CDEMO-FROM-TRANID
 *                           PIC X(04)}
 * @param programName        8-character originating COBOL program name;
 *                           maps to
 *                           {@code CDEMO-GENERAL-INFO.CDEMO-FROM-PROGRAM
 *                           PIC X(08)}.  Preserved verbatim for
 *                           traceability and parallel-run validation
 * @param userId             8-character authenticated user ID; maps to
 *                           {@code CDEMO-GENERAL-INFO.CDEMO-USER-ID PIC
 *                           X(08)}.  In the Java target, also available
 *                           as the {@code sub} JWT claim
 * @param userType           single-character user type code; maps to
 *                           {@code CDEMO-GENERAL-INFO.CDEMO-USER-TYPE
 *                           PIC X(01)} with the COBOL 88-level condition
 *                           names {@code CDEMO-USRTYP-ADMIN} ('A') and
 *                           {@code CDEMO-USRTYP-USER} ('U').  In the
 *                           Java target, also available as the
 *                           {@code userType} JWT claim and the
 *                           {@code ROLE_ADMIN} / {@code ROLE_USER} Spring
 *                           Security granted authority
 * @param customerId         9-digit customer identifier; maps to
 *                           {@code CDEMO-CUSTOMER-INFO.CDEMO-CUST-ID
 *                           PIC 9(09)}.  Stored as {@code Long} to
 *                           preserve the full numeric range
 * @param customerFirstName  customer first name; maps to
 *                           {@code CDEMO-CUSTOMER-INFO.CDEMO-CUST-FNAME
 *                           PIC X(25)}
 * @param customerLastName   customer last name; maps to
 *                           {@code CDEMO-CUSTOMER-INFO.CDEMO-CUST-LNAME
 *                           PIC X(25)}.  The middle name field
 *                           {@code CDEMO-CUST-MNAME} from the source
 *                           COMMAREA is intentionally not propagated
 *                           here (see "Field omissions" above)
 * @param accountId          11-digit account identifier; maps to
 *                           {@code CDEMO-ACCOUNT-INFO.CDEMO-ACCT-ID PIC
 *                           9(11)}.  Stored as {@code Long} to preserve
 *                           the full numeric range (max 99,999,999,999
 *                           fits within {@code long}'s 19-digit range)
 * @param accountStatus      single-character account status flag; maps
 *                           to {@code CDEMO-ACCOUNT-INFO.CDEMO-ACCT-STATUS
 *                           PIC X(01)}.  Typically 'Y' (active) or
 *                           'N' (closed/inactive)
 * @param cardNumber         16-digit card primary account number (PAN);
 *                           maps to {@code CDEMO-CARD-INFO.CDEMO-CARD-NUM
 *                           PIC 9(16)}.  Stored as {@code String} (not
 *                           {@code long}) to preserve leading zeros and
 *                           to avoid accidental arithmetic.  <b>Masked
 *                           in {@link #toString()}</b> per PCI-DSS
 *                           guidance &mdash; only the last four digits
 *                           are exposed in log surfaces
 * @param lastMap            7-character BMS map name last sent to the
 *                           terminal; maps to
 *                           {@code CDEMO-MORE-INFO.CDEMO-LAST-MAP PIC
 *                           X(7)}.  Preserved for parallel-run
 *                           validation; not used by the REST clients
 * @param lastMapset         7-character BMS mapset name last sent to the
 *                           terminal; maps to
 *                           {@code CDEMO-MORE-INFO.CDEMO-LAST-MAPSET
 *                           PIC X(7)}.  Preserved for parallel-run
 *                           validation; not used by the REST clients
 *
 * @see <a href=
 *      "https://github.com/aws-samples/aws-mainframe-modernization-carddemo">
 *      AWS CardDemo (source COBOL)</a>
 */
@Schema(name = "CommonContextDto",
        description = "Common context payload mirroring the CICS "
                + "CARDDEMO-COMMAREA (app/cpy/COCOM01Y.cpy).  Carries optional "
                + "passthrough context (originating program / last-map / "
                + "user / customer / account / card) for flows that need to "
                + "preserve prior-screen state.  In most REST flows, identity "
                + "is carried via JWT claims and resource IDs via path "
                + "variables; this DTO is provided primarily for traceability "
                + "and parity with the original COBOL COMMAREA contract.  "
                + "PCI-DSS: card number is masked in toString() output.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommonContextDto(

        // ===== General info (CDEMO-GENERAL-INFO) =====

        @Schema(description = "Originating CICS transaction ID.  Sourced from "
                + "the COBOL CDEMO-GENERAL-INFO.CDEMO-FROM-TRANID PIC X(04) "
                + "field.  4-character upper-case alphanumeric identifier "
                + "(e.g., 'CC00', 'CM00', 'CT00').",
                example = "CC00",
                maxLength = 4)
        @JsonProperty("tranId")
        String tranId,

        @Schema(description = "Originating COBOL program name.  Sourced from "
                + "the COBOL CDEMO-GENERAL-INFO.CDEMO-FROM-PROGRAM PIC X(08) "
                + "field.  8-character upper-case identifier referencing the "
                + "original COBOL program (e.g., 'COMEN01C', 'COACTVWC', "
                + "'COTRN02C').  Preserved verbatim for traceability and "
                + "parallel-run validation against the original mainframe "
                + "behavior.",
                example = "COMEN01C",
                maxLength = 8)
        @JsonProperty("programName")
        String programName,

        @Schema(description = "Authenticated user ID.  Sourced from the COBOL "
                + "CDEMO-GENERAL-INFO.CDEMO-USER-ID PIC X(08) field.  In the "
                + "Java target, this value is also available as the 'sub' "
                + "claim on the JWT issued by /api/auth/signin and is "
                + "extracted into the Spring Security Authentication "
                + "principal by JwtAuthenticationFilter.",
                example = "USER0001",
                maxLength = 8)
        @JsonProperty("userId")
        String userId,

        @Schema(description = "User-type code.  Sourced from the COBOL "
                + "CDEMO-GENERAL-INFO.CDEMO-USER-TYPE PIC X(01) field with "
                + "the 88-level condition names CDEMO-USRTYP-ADMIN ('A') and "
                + "CDEMO-USRTYP-USER ('U').  In the Java target, this value "
                + "also drives the Spring Security ROLE_ADMIN / ROLE_USER "
                + "granted authority assigned by JwtAuthenticationFilter.",
                example = "U",
                allowableValues = {"A", "U"})
        @JsonProperty("userType")
        String userType,

        // ===== Customer info (CDEMO-CUSTOMER-INFO) =====

        @Schema(description = "Customer identifier.  Sourced from the COBOL "
                + "CDEMO-CUSTOMER-INFO.CDEMO-CUST-ID PIC 9(09) field.  Stored "
                + "as java.lang.Long to preserve the full 9-digit numeric "
                + "range without arithmetic risk.",
                example = "100000001")
        @JsonProperty("customerId")
        Long customerId,

        @Schema(description = "Customer first name.  Sourced from the COBOL "
                + "CDEMO-CUSTOMER-INFO.CDEMO-CUST-FNAME PIC X(25) field.",
                example = "John",
                maxLength = 25)
        @JsonProperty("customerFirstName")
        String customerFirstName,

        @Schema(description = "Customer last name.  Sourced from the COBOL "
                + "CDEMO-CUSTOMER-INFO.CDEMO-CUST-LNAME PIC X(25) field.  "
                + "The CDEMO-CUST-MNAME (middle name) field from the source "
                + "COMMAREA is intentionally not propagated through this "
                + "DTO; the Customer entity carries the full name set.",
                example = "Doe",
                maxLength = 25)
        @JsonProperty("customerLastName")
        String customerLastName,

        // ===== Account info (CDEMO-ACCOUNT-INFO) =====

        @Schema(description = "Account identifier.  Sourced from the COBOL "
                + "CDEMO-ACCOUNT-INFO.CDEMO-ACCT-ID PIC 9(11) field.  Stored "
                + "as java.lang.Long to preserve the full 11-digit numeric "
                + "range (max 99,999,999,999 fits within long's 19-digit "
                + "capacity).",
                example = "10000000001")
        @JsonProperty("accountId")
        Long accountId,

        @Schema(description = "Account status flag.  Sourced from the COBOL "
                + "CDEMO-ACCOUNT-INFO.CDEMO-ACCT-STATUS PIC X(01) field.  "
                + "Typically 'Y' (active) or 'N' (closed/inactive).",
                example = "Y",
                maxLength = 1)
        @JsonProperty("accountStatus")
        String accountStatus,

        // ===== Card info (CDEMO-CARD-INFO) =====

        @Schema(description = "Card primary account number (PAN).  Sourced "
                + "from the COBOL CDEMO-CARD-INFO.CDEMO-CARD-NUM PIC 9(16) "
                + "field.  Stored as java.lang.String (not long) to preserve "
                + "leading zeros and to avoid accidental arithmetic on a "
                + "PAN.  PCI-DSS: this field is masked in toString() output "
                + "(only last four digits exposed, prefixed by twelve "
                + "asterisks) to prevent inadvertent PAN leakage to log "
                + "files, CloudWatch, OpenSearch indexes, or stack-trace "
                + "surfaces.",
                example = "4111111111111111",
                maxLength = 16)
        @JsonProperty("cardNumber")
        String cardNumber,

        // ===== More info (CDEMO-MORE-INFO) =====

        @Schema(description = "Last BMS map name sent to the terminal.  "
                + "Sourced from the COBOL CDEMO-MORE-INFO.CDEMO-LAST-MAP "
                + "PIC X(7) field.  Preserved for parallel-run validation "
                + "against the original mainframe behavior; not used by "
                + "REST clients.",
                example = "COMEN1A",
                maxLength = 7)
        @JsonProperty("lastMap")
        String lastMap,

        @Schema(description = "Last BMS mapset name sent to the terminal.  "
                + "Sourced from the COBOL CDEMO-MORE-INFO.CDEMO-LAST-MAPSET "
                + "PIC X(7) field.  Preserved for parallel-run validation "
                + "against the original mainframe behavior; not used by "
                + "REST clients.",
                example = "COMEN01",
                maxLength = 7)
        @JsonProperty("lastMapset")
        String lastMapset
) {

    /**
     * Returns a string representation of this context, with the
     * {@link #cardNumber()} primary account number (PAN) masked per
     * PCI-DSS guidance (AAP &sect;0.6.6).
     *
     * <p>The mask retains only the last four digits of the PAN and
     * prefixes them with twelve asterisks (e.g., a card number
     * {@code 4111111111111111} renders as {@code ************1111}).
     * If the PAN is {@code null} or shorter than four characters, the
     * masked value is rendered as {@code null} &mdash; we never emit a
     * partial-PAN substring from a malformed input because that would
     * itself leak information.
     *
     * <p>The default {@link Record#toString() Record.toString()} would
     * include the full PAN, so this override is required to keep any
     * log statement, stack trace, exception message, or audit-trail
     * snapshot that incorporates the DTO from leaking the PAN into log
     * sinks (CloudWatch Logs, OpenSearch indexes, console output, etc.).
     *
     * <p>The other components &mdash; {@link #tranId()},
     * {@link #programName()}, {@link #userId()}, {@link #userType()},
     * {@link #customerId()}, {@link #accountId()}, {@link #accountStatus()},
     * {@link #lastMap()}, {@link #lastMapset()} &mdash; are included
     * unredacted because they do not carry cardholder data.  Customer
     * names ({@link #customerFirstName()}, {@link #customerLastName()})
     * are deliberately omitted from this output to reduce the surface
     * area of personally-identifiable information appearing in log
     * sinks; the full payload remains available via the record
     * accessors for code that explicitly needs it.
     *
     * <p><b>NOTE on record-generated {@code equals()}/{@code hashCode()}
     * (accepted risk per AAP &sect;0.6.6).</b>  Java records auto-generate
     * {@link Object#equals(Object)} and {@link Object#hashCode()} over
     * every component &mdash; including the unmasked PAN
     * {@link #cardNumber()} &mdash; and the record contract forbids
     * overriding these methods to exclude components without converting
     * the type to a regular class (a scope expansion that would violate
     * the AAP-mandated Minimal Change Clause).  The risk that the
     * unmasked PAN participates in equality/hash operations is
     * <b>accepted</b> because:
     * <ul>
     *   <li>This DTO is the COMMAREA replacement; its PAN lifetime is
     *       the duration of a single REST handler / JWT-authenticated
     *       request flow and is not persisted as a long-lived
     *       structure.</li>
     *   <li>{@link Object#equals(Object)} and {@link Object#hashCode()}
     *       on this DTO are not invoked by any audit, logging, caching,
     *       or persistence path &mdash; only {@code toString()} (which
     *       is PCI-DSS-masked) reaches CloudWatch / OpenSearch.</li>
     *   <li>Debugger inspection and heap-dump analysis are governed by
     *       the platform's PCI-DSS access controls (AAP &sect;0.6.6)
     *       and are out of scope for DTO-level mitigation.</li>
     * </ul>
     *
     * @return a PCI-DSS-safe string representation of this context
     */
    @Override
    public String toString() {
        final String maskedPan = (cardNumber == null || cardNumber.length() < 4)
                ? null
                : "************" + cardNumber.substring(cardNumber.length() - 4);
        return "CommonContextDto[tranId=" + tranId
             + ", programName=" + programName
             + ", userId=" + userId
             + ", userType=" + userType
             + ", customerId=" + customerId
             + ", accountId=" + accountId
             + ", accountStatus=" + accountStatus
             + ", cardNumber=" + maskedPan
             + ", lastMap=" + lastMap
             + ", lastMapset=" + lastMapset
             + "]";
    }
}
