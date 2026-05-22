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
 * Card work-areas DTO mirroring the COBOL {@code CC-WORK-AREAS} 01-level
 * structure defined in {@code app/cpy/CVCRD01Y.cpy}.
 *
 * <p><b>COBOL source layout</b> (preserved verbatim from
 * {@code app/cpy/CVCRD01Y.cpy} lines 1&ndash;42):</p>
 * <pre>{@code
 *   01  CC-WORK-AREAS.
 *       05 CC-WORK-AREA.
 *          10 CCARD-AID                 PIC X(5).
 *             88  CCARD-AID-ENTER       VALUE 'ENTER'.
 *             88  CCARD-AID-CLEAR       VALUE 'CLEAR'.
 *             88  CCARD-AID-PA1         VALUE 'PA1  '.
 *             88  CCARD-AID-PA2         VALUE 'PA2  '.
 *             88  CCARD-AID-PFK01       VALUE 'PFK01'.
 *             ...
 *             88  CCARD-AID-PFK12       VALUE 'PFK12'.
 *          10 CCARD-NEXT-PROG           PIC X(8).
 *          10 CCARD-NEXT-MAPSET         PIC X(7).
 *          10 CCARD-NEXT-MAP            PIC X(7).
 *          10 CCARD-ERROR-MSG           PIC X(75).
 *          10 CCARD-RETURN-MSG          PIC X(75).
 *             88  CCARD-RETURN-MSG-OFF  VALUE LOW-VALUES.
 *          10 CC-ACCT-ID                PIC X(11) VALUE SPACES.
 *          10 CC-ACCT-ID-N REDEFINES CC-ACCT-ID  PIC 9(11).
 *          10 CC-CARD-NUM               PIC X(16) VALUE SPACES.
 *          10 CC-CARD-NUM-N REDEFINES CC-CARD-NUM PIC 9(16).
 *          10 CC-CUST-ID                PIC X(09) VALUE SPACES.
 *          10 CC-CUST-ID-N REDEFINES CC-CUST-ID  PIC 9(9).
 * }</pre>
 *
 * <p>The original COBOL working storage carried five categories of fields,
 * each of which has a clear analogue (or deliberate retirement) in the
 * Java/Spring Boot/AWS target:</p>
 * <ul>
 *   <li><b>AID keys</b> ({@code CCARD-AID} &mdash; ENTER, CLEAR, PA1, PA2,
 *       PFK01..PFK12 with corresponding 88-level boolean condition names
 *       in the source) &mdash; the 3270 terminal attention-identifier
 *       byte conveyed by CICS in the {@code EIBAID} field. In the REST
 *       target, the user's intent is expressed by the HTTP verb (GET,
 *       POST, PUT, DELETE) and any business action selectors carried in
 *       request bodies; this DTO preserves the field for informational
 *       traceability only, per AAP &sect;0.4.1.</li>
 *   <li><b>Routing fields</b> ({@code CCARD-NEXT-PROG} PIC X(8),
 *       {@code CCARD-NEXT-MAPSET} PIC X(7), {@code CCARD-NEXT-MAP} PIC
 *       X(7)) &mdash; the CICS pseudo-conversational "next transaction"
 *       parameters that {@code EXEC CICS RETURN TRANSID(...) COMMAREA(...)}
 *       used to chain screens. In the REST target, navigation between
 *       endpoints is the responsibility of the client (typically a SPA
 *       or thick client following hyperlinks returned in
 *       {@code ApiResponse} envelopes); the server no longer dictates
 *       next-screen routing, so these fields are informational only.</li>
 *   <li><b>Messaging fields</b> ({@code CCARD-ERROR-MSG} PIC X(75) and
 *       {@code CCARD-RETURN-MSG} PIC X(75)) &mdash; the human-readable
 *       error and informational messages that COBOL programs assigned to
 *       BMS error-line fields before returning to the terminal. In the
 *       REST target these flow through the {@code message} component of
 *       {@code ApiResponse} and the {@code errors} list inside the
 *       same envelope; this DTO captures them for use in lookup-style
 *       services that need to surface a structured contextual message
 *       distinct from validation errors.</li>
 *   <li><b>Identifier fields</b> ({@code CC-ACCT-ID} PIC X(11),
 *       {@code CC-CARD-NUM} PIC X(16), {@code CC-CUST-ID} PIC X(9)) with
 *       their {@code REDEFINES} numeric variants ({@code CC-ACCT-ID-N}
 *       PIC 9(11), {@code CC-CARD-NUM-N} PIC 9(16), {@code CC-CUST-ID-N}
 *       PIC 9(9)) &mdash; the three primary entity identifiers that
 *       COBOL programs carried in working storage to thread the user's
 *       chosen account/card/customer across multiple pseudo-conversational
 *       turns. In the Java target the {@code REDEFINES} numeric variants
 *       collapse into the single string field, with downstream consumers
 *       parsing as needed; the leading {@code VALUE SPACES} initial
 *       value collapses to {@code null} on the Java side.</li>
 *   <li><b>Commented-out fields</b> ({@code CCARD-LAST-PROG},
 *       {@code CCARD-RETURN-TO-PROG}, {@code CCARD-RETURN-FLAG},
 *       {@code CCARD-FUNCTION}) &mdash; lines 20, 22, 25&ndash;27, and
 *       31&ndash;33 of {@code CVCRD01Y.cpy} carry leading {@code *}
 *       comment markers and are not part of the active record layout.
 *       Accordingly, no Java field is generated for them.</li>
 * </ul>
 *
 * <p><b>Migration disposition (AAP &sect;0.4.1):</b> This DTO is kept
 * primarily for documentation/parity and to provide a structured way for
 * services that need to surface a contextual message bundle (error +
 * return messages with the relevant entity identifiers) to clients. It
 * SHOULD NOT be used for routing decisions in new code &mdash; routing
 * happens on the client. New code that needs to track the active
 * account/card/customer across a logical user session should prefer
 * carrying those values in JWT claims (per the
 * {@code CommonContextDto}/COMMAREA disposition in AAP &sect;0.4.1) or
 * as explicit method parameters; this DTO is a payload-shaped mirror,
 * not the canonical state holder.</p>
 *
 * <p><b>PCI-DSS / production sanitization (AAP &sect;0.6.6):</b> The
 * {@link #cardNumber()} field carries a 16-digit Primary Account Number
 * (PAN). Per AAP &sect;0.6.6 ("CloudWatch log filters detect plaintext
 * card or account data in application logs") and the project-wide PAN
 * masking convention used by {@code CardListDto.CardRow}, this record
 * implements a custom {@link #toString()} that masks all but the last
 * four digits of the PAN with twelve leading asterisks. Services and
 * loggers that emit this DTO via the default {@code toString()} are
 * therefore PAN-safe by default; any caller that needs the raw PAN
 * must access {@link #cardNumber()} directly, accepting responsibility
 * for proper handling.</p>
 *
 * <p><b>Wire-format stability:</b> The {@link JsonProperty} bindings on
 * each component freeze the on-wire JSON contract independently of any
 * future record component renames. {@link JsonInclude#NON_NULL} at the
 * record level suppresses fields whose runtime value is {@code null}
 * &mdash; the work-area payload is sparse by design (most flows
 * populate only a handful of the nine fields), so omitting nulls keeps
 * JSON payloads compact and stable for OpenAPI consumers.</p>
 *
 * <p><b>Overlap with other DTOs:</b> The identifier fields
 * ({@link #accountId()}, {@link #cardNumber()}, {@link #customerId()})
 * overlap with the analogous fields in {@code CommonContextDto} (which
 * mirrors {@code app/cpy/COCOM01Y.cpy} / the CICS COMMAREA). To avoid
 * duplication and ambiguity in service flows, callers should pick
 * exactly one of the two DTOs to thread context through a given
 * request/response pair: {@code CommonContextDto} when the data
 * represents authenticated session state carried across multiple
 * requests, and {@code CardWorkAreasDto} when the data is a transient,
 * single-response context bundle (typically accompanying a card-related
 * lookup or update operation).</p>
 *
 * <p><b>COBOL Provenance:</b></p>
 * <ul>
 *   <li>Copybook: {@code app/cpy/CVCRD01Y.cpy} (structure
 *       {@code CC-WORK-AREAS})</li>
 *   <li>Source population statements: each COBOL program that
 *       {@code COPY CVCRD01Y} populated {@code CC-WORK-AREAS} fields
 *       across its PROCEDURE DIVISION paragraphs &mdash; assigning the
 *       AID byte from {@code EIBAID}, the next-program/mapset/map names
 *       from string literals or COMMAREA-derived values, the error and
 *       return messages from string literals or response-code lookups,
 *       and the identifier fields from BMS map input or
 *       {@code DFHCOMMAREA} input.</li>
 *   <li>Used by: every CICS online program that performs lookup-style
 *       flows (e.g., {@code app/cbl/COCRDLIC.cbl},
 *       {@code app/cbl/COCRDSLC.cbl}, {@code app/cbl/COCRDUPC.cbl}) via
 *       {@code COPY CVCRD01Y.} in WORKING-STORAGE.</li>
 *   <li>Retires: the CICS pseudo-conversational
 *       {@code RETURN TRANSID COMMAREA} routing model (AAP &sect;0.1.1
 *       &mdash; "translates to stateless REST endpoints") &mdash; the
 *       routing fields ({@link #nextProgram()}, {@link #nextMapset()},
 *       {@link #nextMap()}) are kept as informational metadata only.</li>
 * </ul>
 *
 * <p><b>Distinction from {@code COCOM01Y.cpy} / {@code CommonContextDto}:</b>
 * {@code CVCRD01Y.cpy} (this DTO) is the <em>per-card-flow</em>
 * work-area structure (AID + routing + messages + identifiers); the
 * broader CICS COMMAREA defined in {@code app/cpy/COCOM01Y.cpy} is the
 * <em>session-wide</em> context (user ID, user type, current screen
 * stack, etc.) and maps to a separate {@code CommonContextDto} per AAP
 * &sect;0.4.1. Both carry overlapping identifier fields by design in
 * the COBOL source; the Java target preserves this duality for
 * traceability but expects callers to choose exactly one in any given
 * service flow.</p>
 *
 * @param aidKey        last AID key pressed &mdash; mirrors COBOL
 *                      {@code CCARD-AID PIC X(5)}. Original COBOL had
 *                      specific 5-character constants (ENTER, CLEAR,
 *                      PA1, PA2, PFK01..PFK12) via 88-level boolean
 *                      condition names. In the REST target this is
 *                      informational only since the HTTP verb conveys
 *                      the user's intent.  May be {@code null}.
 * @param nextProgram   next program name &mdash; mirrors COBOL
 *                      {@code CCARD-NEXT-PROG PIC X(8)}. In CICS this
 *                      was the 8-character program-id of the next
 *                      pseudo-conversational target; in REST, routing
 *                      is client-side so this is informational only.
 *                      May be {@code null}.
 * @param nextMapset    next mapset name &mdash; mirrors COBOL
 *                      {@code CCARD-NEXT-MAPSET PIC X(7)}. In CICS this
 *                      was the 7-character BMS mapset name (e.g.,
 *                      {@code COCRDSL}); informational only in REST.
 *                      May be {@code null}.
 * @param nextMap       next map name &mdash; mirrors COBOL
 *                      {@code CCARD-NEXT-MAP PIC X(7)}. In CICS this
 *                      was the 7-character BMS map name within a
 *                      mapset (e.g., {@code COCRDSLA}); informational
 *                      only in REST. May be {@code null}.
 * @param errorMessage  error message text &mdash; mirrors COBOL
 *                      {@code CCARD-ERROR-MSG PIC X(75)}. Surfaced to
 *                      the client as part of {@code ApiResponse.message}
 *                      when accompanying an error response. May be
 *                      empty on success or {@code null} when no error
 *                      condition is present.
 * @param returnMessage return/informational message text &mdash;
 *                      mirrors COBOL {@code CCARD-RETURN-MSG PIC X(75)}
 *                      (with its {@code CCARD-RETURN-MSG-OFF} 88-level
 *                      condition name meaning the field is empty,
 *                      represented in Java as {@code null}). Carries
 *                      non-error contextual messages (e.g., "RECORD
 *                      UPDATED SUCCESSFULLY"). May be {@code null}.
 * @param accountId     11-digit account identifier &mdash; mirrors
 *                      COBOL {@code CC-ACCT-ID PIC X(11)} with
 *                      {@code REDEFINES CC-ACCT-ID-N PIC 9(11)}
 *                      numeric variant. Held as a {@code String} on
 *                      the Java side; the original {@code VALUE
 *                      SPACES} initial value collapses to {@code null}.
 *                      May be {@code null}.
 * @param cardNumber    16-digit card number (Primary Account Number,
 *                      PAN) &mdash; mirrors COBOL {@code CC-CARD-NUM
 *                      PIC X(16)} with {@code REDEFINES CC-CARD-NUM-N
 *                      PIC 9(16)} numeric variant. Held as a
 *                      {@code String} on the Java side. <b>Masked in
 *                      {@link #toString()}</b> per PCI-DSS PAN masking
 *                      convention (12 leading asterisks + last 4
 *                      digits). May be {@code null}.
 * @param customerId    9-digit customer identifier &mdash; mirrors
 *                      COBOL {@code CC-CUST-ID PIC X(9)} with
 *                      {@code REDEFINES CC-CUST-ID-N PIC 9(9)} numeric
 *                      variant. Held as a {@code String} on the Java
 *                      side. May be {@code null}.
 *
 * @see com.fasterxml.jackson.annotation.JsonInclude
 * @see com.fasterxml.jackson.annotation.JsonProperty
 * @see io.swagger.v3.oas.annotations.media.Schema
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "CardWorkAreas",
        description = "Card work-areas DTO carrying the last AID key, next "
                + "program/mapset/map names (informational only in REST), "
                + "error and return message text, and the active account, "
                + "card, and customer identifiers. Mirrors COBOL "
                + "CC-WORK-AREAS from app/cpy/CVCRD01Y.cpy. Populated by "
                + "services that perform card-related lookups and surface "
                + "structured contextual messages to clients. The PAN "
                + "(cardNumber) is masked in toString() per PCI-DSS "
                + "guidance (AAP \u00a70.6.6); routing fields are kept for "
                + "traceability but should not influence client navigation.")
public record CardWorkAreasDto(

        /**
         * Last AID key pressed &mdash; mirrors COBOL
         * {@code CCARD-AID PIC X(5)}.
         *
         * <p>In the original COBOL the AID byte came from {@code EIBAID}
         * after every {@code EXEC CICS RECEIVE MAP} call and was
         * normalized into one of the 5-character mnemonics (ENTER,
         * CLEAR, PA1, PA2, PFK01..PFK12) via the boolean 88-level
         * condition names {@code CCARD-AID-ENTER},
         * {@code CCARD-AID-CLEAR}, {@code CCARD-AID-PA1},
         * {@code CCARD-AID-PA2}, and {@code CCARD-AID-PFK01} through
         * {@code CCARD-AID-PFK12}. In the REST/JSON target the HTTP
         * verb (GET / POST / PUT / DELETE) carries the user's intent
         * directly, so this field is preserved for traceability only
         * and SHOULD NOT influence server-side routing or business
         * decisions in new code.
         */
        @Schema(description = "Last AID key (ENTER, CLEAR, PA1, PA2, "
                + "PFK01..PFK12) — informational only in REST since the "
                + "HTTP verb conveys user intent. Mirrors COBOL CCARD-AID "
                + "PIC X(5) from app/cpy/CVCRD01Y.cpy.",
                example = "ENTER",
                maxLength = 5,
                nullable = true)
        @JsonProperty("aidKey")
        String aidKey,

        /**
         * Next program name &mdash; mirrors COBOL
         * {@code CCARD-NEXT-PROG PIC X(8)}.
         *
         * <p>In CICS this was the 8-character program-id passed to
         * {@code EXEC CICS RETURN TRANSID(...) COMMAREA(...)} to chain
         * the next pseudo-conversational turn (e.g., {@code "COCRDSLC"}
         * to invoke the card-detail program). The REST target replaces
         * server-driven navigation with client-driven URL routing, so
         * this field is informational only and should not be used to
         * make routing decisions on the server.
         */
        @Schema(description = "Next program name (informational; routing is "
                + "client-side in REST). Mirrors COBOL CCARD-NEXT-PROG PIC "
                + "X(8) from app/cpy/CVCRD01Y.cpy.",
                example = "COCRDSLC",
                maxLength = 8,
                nullable = true)
        @JsonProperty("nextProgram")
        String nextProgram,

        /**
         * Next mapset name &mdash; mirrors COBOL
         * {@code CCARD-NEXT-MAPSET PIC X(7)}.
         *
         * <p>In CICS this was the 7-character BMS mapset name (e.g.,
         * {@code "COCRDSL"}) used by {@code EXEC CICS SEND MAP MAPSET()}
         * to render the next 3270 screen. With BMS retired in favor of
         * JSON REST endpoints (AAP &sect;0.3.4), this field carries no
         * functional weight in the Java target and is preserved for
         * documentation parity only.
         */
        @Schema(description = "Next mapset name (informational; BMS is "
                + "retired in favor of REST endpoints per AAP \u00a70.3.4). "
                + "Mirrors COBOL CCARD-NEXT-MAPSET PIC X(7) from "
                + "app/cpy/CVCRD01Y.cpy.",
                example = "COCRDSL",
                maxLength = 7,
                nullable = true)
        @JsonProperty("nextMapset")
        String nextMapset,

        /**
         * Next map name &mdash; mirrors COBOL
         * {@code CCARD-NEXT-MAP PIC X(7)}.
         *
         * <p>In CICS this was the 7-character BMS map name within a
         * mapset (e.g., {@code "COCRDSLA"}). Same disposition as
         * {@link #nextMapset()} &mdash; informational only, kept for
         * documentation parity.
         */
        @Schema(description = "Next map name (informational; BMS is retired "
                + "in favor of REST endpoints per AAP \u00a70.3.4). Mirrors "
                + "COBOL CCARD-NEXT-MAP PIC X(7) from app/cpy/CVCRD01Y.cpy.",
                example = "COCRDSLA",
                maxLength = 7,
                nullable = true)
        @JsonProperty("nextMap")
        String nextMap,

        /**
         * Error message text &mdash; mirrors COBOL
         * {@code CCARD-ERROR-MSG PIC X(75)}.
         *
         * <p>In the original COBOL this was assigned to the BMS
         * error-line field {@code ERRMSGO} (highlighted in red) before
         * returning to the 3270 terminal. In the Java/REST target the
         * value flows through the {@code message} or {@code errors}
         * components of an {@code ApiResponse} envelope when emitted by
         * controllers, surfacing the same human-readable diagnostic to
         * the client. Maximum length of 75 characters is preserved
         * exactly from the COBOL declaration; longer messages must be
         * truncated by the producer.
         */
        @Schema(description = "Error message text (max 75 chars) carrying a "
                + "human-readable diagnostic. Mirrors COBOL CCARD-ERROR-MSG "
                + "PIC X(75) from app/cpy/CVCRD01Y.cpy. Surfaced via the "
                + "ApiResponse envelope in the Java target.",
                example = "ACCOUNT NUMBER MUST BE NUMERIC",
                maxLength = 75,
                nullable = true)
        @JsonProperty("errorMessage")
        String errorMessage,

        /**
         * Return / informational message text &mdash; mirrors COBOL
         * {@code CCARD-RETURN-MSG PIC X(75)} (with the 88-level
         * condition name {@code CCARD-RETURN-MSG-OFF} meaning the
         * field carries {@code LOW-VALUES} / is empty, which collapses
         * to {@code null} on the Java side).
         *
         * <p>Used for non-error contextual messages such as
         * {@code "RECORD UPDATED SUCCESSFULLY"} or
         * {@code "NO MORE RECORDS TO DISPLAY"}. In the Java target the
         * value flows through the {@code message} component of an
         * {@code ApiResponse} envelope. Maximum length of 75 characters
         * is preserved exactly from the COBOL declaration.
         */
        @Schema(description = "Return/informational message text (max 75 "
                + "chars) carrying a non-error contextual message. Mirrors "
                + "COBOL CCARD-RETURN-MSG PIC X(75) from "
                + "app/cpy/CVCRD01Y.cpy.",
                example = "RECORD UPDATED SUCCESSFULLY",
                maxLength = 75,
                nullable = true)
        @JsonProperty("returnMessage")
        String returnMessage,

        /**
         * 11-digit account identifier &mdash; mirrors COBOL
         * {@code CC-ACCT-ID PIC X(11)} with the {@code REDEFINES
         * CC-ACCT-ID-N PIC 9(11)} numeric variant.
         *
         * <p>In the COBOL source this was carried as alphanumeric
         * ({@code PIC X(11)}) for storage and as a numeric REDEFINES
         * ({@code PIC 9(11)}) for arithmetic comparisons; the Java
         * target collapses both forms into a single string field with
         * downstream consumers parsing as needed. The original
         * {@code VALUE SPACES} initial value (line 35 of the copybook)
         * collapses to {@code null} on the Java side, consistent with
         * the {@link JsonInclude#NON_NULL} suppression at the record
         * level.
         */
        @Schema(description = "11-digit account identifier. Mirrors COBOL "
                + "CC-ACCT-ID PIC X(11) (with REDEFINES CC-ACCT-ID-N PIC "
                + "9(11) numeric variant) from app/cpy/CVCRD01Y.cpy.",
                example = "10000000001",
                maxLength = 11,
                nullable = true)
        @JsonProperty("accountId")
        String accountId,

        /**
         * 16-digit card number (Primary Account Number, PAN) &mdash;
         * mirrors COBOL {@code CC-CARD-NUM PIC X(16)} with the
         * {@code REDEFINES CC-CARD-NUM-N PIC 9(16)} numeric variant.
         *
         * <p>In the COBOL source this was carried as alphanumeric
         * ({@code PIC X(16)}) and as a numeric REDEFINES
         * ({@code PIC 9(16)}). The Java target collapses both forms
         * into a single string field; downstream consumers may parse
         * to {@code long} when arithmetic comparison is required.
         *
         * <p><b>PCI-DSS:</b> per AAP &sect;0.6.6, this field is masked
         * in {@link #toString()} (12 leading asterisks + last 4 digits
         * retained) so that the default {@code toString()}-driven log
         * output never exposes a plaintext PAN. The raw 16-digit value
         * remains accessible via the {@link #cardNumber()} accessor
         * for callers that legitimately require it (e.g., a card
         * lookup); those callers accept responsibility for proper
         * handling. CloudWatch log filters and Amazon Macie scans
         * provide a second line of defense (AAP &sect;0.6.6).
         */
        @Schema(description = "16-digit card number / Primary Account Number "
                + "(PAN). Masked in toString() per PCI-DSS PAN masking "
                + "convention (12 leading asterisks + last 4 digits). "
                + "Mirrors COBOL CC-CARD-NUM PIC X(16) (with REDEFINES "
                + "CC-CARD-NUM-N PIC 9(16) numeric variant) from "
                + "app/cpy/CVCRD01Y.cpy.",
                example = "4111111111111111",
                maxLength = 16,
                nullable = true)
        @JsonProperty("cardNumber")
        String cardNumber,

        /**
         * 9-digit customer identifier &mdash; mirrors COBOL
         * {@code CC-CUST-ID PIC X(9)} with the {@code REDEFINES
         * CC-CUST-ID-N PIC 9(9)} numeric variant.
         *
         * <p>Same disposition as {@link #accountId()}: alphanumeric
         * and numeric REDEFINES variants collapse into a single string
         * field; {@code VALUE SPACES} collapses to {@code null}.
         */
        @Schema(description = "9-digit customer identifier. Mirrors COBOL "
                + "CC-CUST-ID PIC X(9) (with REDEFINES CC-CUST-ID-N PIC 9(9) "
                + "numeric variant) from app/cpy/CVCRD01Y.cpy.",
                example = "100000001",
                maxLength = 9,
                nullable = true)
        @JsonProperty("customerId")
        String customerId
) {

    /**
     * PAN-safe string representation of this work-areas record.
     *
     * <p>The {@link #cardNumber()} component is masked using the
     * project-wide PCI-DSS PAN masking convention (12 leading
     * asterisks followed by the last four digits of the PAN) so that
     * any logger, exception, or debugging call site that invokes
     * {@code toString()} on a {@code CardWorkAreasDto} can never leak
     * a plaintext PAN. The masking convention matches
     * {@code CardListDto.CardRow#toString()} for repository-wide
     * consistency.
     *
     * <p>The masking algorithm is deliberately conservative: any
     * {@code cardNumber} value shorter than 4 characters (e.g., the
     * empty string or {@code null}) is reported as {@code null} in
     * the output rather than a partial mask, so the function can
     * never accidentally surface fewer-than-four-digit residual data.
     *
     * <p>Only the fields most useful for debugging are included in
     * the output &mdash; routing fields ({@link #nextProgram()},
     * {@link #nextMapset()}, {@link #nextMap()}) and the return
     * message are intentionally omitted to keep log lines concise and
     * focused on the data most often needed during incident response.
     * Callers who need the full payload should rely on the
     * Jackson-serialized JSON form (which includes every non-null
     * field with field-name binding via {@link JsonProperty}) instead
     * of {@code toString()}.
     *
     * @return a PAN-safe string with the structure
     *         {@code CardWorkAreasDto[aidKey=..., accountId=...,
     *         cardNumber=************LAST4, customerId=...,
     *         errorMessage=...]}.
     */
    @Override
    public String toString() {
        // PAN masking: 12 leading asterisks + last 4 digits (per AAP
        // §0.6.6 PCI-DSS guidance and the repository-wide convention
        // established in CardListDto.CardRow#toString()).  A null or
        // sub-4-character cardNumber is reported as null rather than a
        // partial mask, ensuring this method can never surface residual
        // plaintext PAN data.
        final String maskedPan = (cardNumber == null || cardNumber.length() < 4)
                ? null
                : "************" + cardNumber.substring(cardNumber.length() - 4);
        return "CardWorkAreasDto[aidKey=" + aidKey
                + ", accountId=" + accountId
                + ", cardNumber=" + maskedPan
                + ", customerId=" + customerId
                + ", errorMessage=" + errorMessage
                + "]";
    }
}
