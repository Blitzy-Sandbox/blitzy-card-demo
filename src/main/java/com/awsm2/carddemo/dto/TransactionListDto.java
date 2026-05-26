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

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Paged transaction-list response DTO.
 *
 * <p>Replaces the 3270 transaction-list screen rendered by the COBOL/CICS
 * program {@code COTRN00C.cbl} (CICS transaction id {@code CT00}).  In the
 * source, {@code COTRN00C} uses {@code EXEC CICS STARTBR}/{@code READNEXT} on
 * the {@code TRANSACT} VSAM KSDS to browse 10 transaction records at a time
 * (the BMS map {@code COTRN0A} in {@code app/bms/COTRN00.bms} defines a
 * 10-row table {@code TRNID01..TRNID10}, {@code TDATE01..TDATE10},
 * {@code TDESC01..TDESC10}, {@code TAMT001..TAMT010}), with paging driven by
 * the {@code PF7} (page backward) and {@code PF8} (page forward) keys.  The
 * COBOL working storage carries the browse-state field
 * {@code CDEMO-CT00-PAGE-NUM PIC 9(08)} and the end-of-page sentinels
 * {@code CDEMO-CT00-TRNID-FIRST}/{@code CDEMO-CT00-TRNID-LAST}, exposed for
 * inter-program navigation through the CICS COMMAREA defined in
 * {@code COCOM01Y.cpy}.
 *
 * <p>In the Java target, this is replaced by Spring Data {@code Pageable}
 * on {@code TransactionRepository.findAll(Pageable)} with {@code size=10} to
 * match the BMS 10-row table.  The optional {@link #idFilter()} narrows the
 * result set by transaction-ID prefix or exact match, mirroring the COBOL
 * positioning read where the program copies the
 * {@code TRNIDINI PIC X(16)} field from the screen into {@code TRAN-ID}
 * before issuing {@code STARTBR}.
 *
 * <p>Controller endpoint:
 * {@code GET /api/transactions?id={filter}&page={n}&size=10} &mdash; see
 * {@code TransactionController} (AAP &sect;0.3.4 REST endpoint inventory).
 *
 * <p><b>BMS field origin &mdash; per-row mapping (COTRN0A map, 10 occurrences
 * &mdash; values shown for row {@code nn = 01..10}):</b>
 * <pre>{@code
 *   BMS field    Length   CVTRA05Y field            TransactionRow component
 *   ----------   ------   -----------------------   ------------------------
 *   TRNIDnnI     X(16)    TRAN-ID         X(16)     transactionId
 *   TDATEnnI     X(08)    TRAN-PROC-TS    X(26) *   processingTimestamp **
 *   TDESCnnI     X(26)    TRAN-DESC       X(100)*** description ***
 *   TAMTnnnI     X(12)    TRAN-AMT  S9(09)V99       amount
 *   (none)                TRAN-CARD-NUM   X(16)     cardNumber  (masked)
 *   (none)                TRAN-TYPE-CD    X(02)     transactionType
 *   (none)                TRAN-CAT-CD     9(04)     transactionCategory
 *   (none)                TRAN-SOURCE     X(10)     source
 * }</pre>
 *
 * <p>(*) The BMS screen renders only the date portion of the 26-byte
 * {@code TRAN-PROC-TS} timestamp via the formatted working-storage variable
 * {@code WS-TRAN-DATE PIC X(08)} (default {@code '00/00/00'}); the REST
 * response carries the full {@link LocalDateTime} so callers can render any
 * format they need.
 *
 * <p>(**) Native {@code java.time.LocalDateTime} replaces the LE-managed
 * {@code TRAN-PROC-TS PIC X(26)} string per AAP &sect;0.6.3 (no
 * {@code CEEDAYS} dependency).  Serialized as an ISO-8601 string using
 * {@link JsonFormat} for stable client contracts.
 *
 * <p>(***) The COBOL screen truncates the description to 26 characters in
 * {@code TDESCnnI}; the REST response carries the full 100-character
 * {@code TRAN-DESC} so clients can render the complete description (and may
 * truncate for narrow-format views if they wish).  Document for clients that
 * the field is up to 100 chars per the source layout.
 *
 * <p>Additional fields exposed in this DTO that are present on the
 * {@code TRAN-RECORD} but were not visible on the legacy 3270 row
 * ({@code cardNumber}, {@code transactionType}, {@code transactionCategory},
 * {@code source}) are surfaced here for downstream callers (mobile clients,
 * audit search, reporting); this is information enrichment via additional
 * record fields, not a change to any preserved business calculation, and is
 * consistent with the AAP Minimal Change Clause (AAP &sect;0.7.3) since no
 * existing behavior is altered.
 *
 * <p><b>COBOL Provenance (AAP &sect;0.7.3 traceability):</b>
 * <ul>
 *   <li>BMS Mapset: {@code app/bms/COTRN00.bms} (mapset {@code COTRN00},
 *       map {@code COTRN0A}, 10-row transaction table)</li>
 *   <li>Symbolic Map: {@code app/cpy-bms/COTRN00.CPY}</li>
 *   <li>Program: {@code app/cbl/COTRN00C.cbl} (CICS transaction
 *       {@code CT00}, program name {@code COTRN00C})</li>
 *   <li>Record Layout: {@code app/cpy/CVTRA05Y.cpy}
 *       ({@code TRAN-RECORD}, RECLN=350)</li>
 *   <li>VSAM Cluster: {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}
 *       (RECLN=350, KEYLEN=16; primary key {@code TRAN-ID}; alternate
 *       index on ({@code TRAN-CARD-NUM}, {@code TRAN-PROC-TS}) replaced
 *       in the Java target by a composite database index per AAP
 *       &sect;0.6.2)</li>
 *   <li>COMMAREA fields driving paging:
 *       {@code CDEMO-CT00-TRNID-FIRST PIC X(16)},
 *       {@code CDEMO-CT00-TRNID-LAST PIC X(16)},
 *       {@code CDEMO-CT00-PAGE-NUM PIC 9(08)},
 *       {@code CDEMO-CT00-NEXT-PAGE-FLG PIC X(01)}
 *       &mdash; replaced by Spring Data {@code Pageable} state
 *       returned in this DTO ({@code page}, {@code totalPages},
 *       {@code first}, {@code last}).</li>
 * </ul>
 *
 * <p><b>Decimal precision (AAP &sect;0.6.1 / &sect;0.7.1):</b>
 * {@link TransactionRow#amount()} maps {@code TRAN-AMT PIC S9(09)V99} to
 * {@link BigDecimal} with {@code scale=2}.  The producer
 * ({@code TransactionListService}) is responsible for setting the scale to
 * {@code 2} with {@code RoundingMode.HALF_EVEN} on every value before it
 * appears in this DTO; this DTO carries the value verbatim and never invokes
 * arithmetic.  Use of {@code float}/{@code double} for any monetary value is
 * forbidden by the AAP and is not used here.
 *
 * <p><b>PCI-DSS handling (AAP &sect;0.7.1, &sect;0.6.6):</b>
 * <ul>
 *   <li>{@link TransactionRow#cardNumber()} is <b>pre-masked at the
 *       producer</b> (only the last 4 digits are retained, the leading 12
 *       digits replaced by {@code '*'} characters per industry-standard PAN
 *       masking).  This DTO holds the masked string verbatim and never
 *       performs un-masking.  Callers must NEVER attempt to reverse the
 *       masking.</li>
 *   <li>No CVV, CVV2, CVC, full PAN, expiration date, track data, PIN, or
 *       PIN block is carried on this DTO.</li>
 *   <li>The default {@link Record#toString()} generated for the outer
 *       {@code TransactionListDto} record is safe to log because it
 *       delegates to {@link TransactionRow#toString()} for each row, which
 *       is explicitly overridden below to emit only PCI-safe fields (the
 *       already-masked card number plus non-sensitive identifiers).</li>
 *   <li>Merchant ID, name, city, and ZIP from the {@code TRAN-RECORD} are
 *       NOT carried on this list-view DTO (they appear only on the detail
 *       DTO) to keep the payload small and reduce incidental PII surface
 *       area.</li>
 * </ul>
 *
 * <p>This record is immutable and response-only; it carries no Jakarta
 * Bean Validation constraints (the request side &mdash; query parameters
 * {@code id}, {@code page}, {@code size} &mdash; is validated at the
 * controller method-parameter level, not on this DTO).
 *
 * @param rows           the list of transaction rows on the current page;
 *                       up to 10 entries to match the 10-row BMS table in
 *                       {@code COTRN00.bms}
 * @param page           the current page number (0-indexed, as produced by
 *                       Spring Data {@code Page#getNumber()}; the COBOL
 *                       source maintained {@code CDEMO-CT00-PAGE-NUM}
 *                       1-indexed and the Java target normalizes to
 *                       0-indexed per Spring Data convention)
 * @param size           the page size; fixed at 10 to match the legacy BMS
 *                       table capacity of {@code COTRN00.bms}
 * @param totalElements  the total number of matching transactions across
 *                       all pages, reflecting the {@link #idFilter()} when
 *                       provided
 * @param totalPages     the total number of pages available given
 *                       {@code totalElements} and {@code size}
 * @param first          {@code true} if this is the first page
 *                       (corresponds to the COBOL guard for "top of the
 *                       page" on the {@code PF7} handler)
 * @param last           {@code true} if this is the last page
 *                       (corresponds to the COBOL guard for "bottom of the
 *                       page" on the {@code PF8} handler and to the
 *                       {@code CDEMO-CT00-NEXT-PAGE-FLG} flag)
 * @param idFilter       the optional transaction-ID filter echoed from
 *                       the request; matches the legacy BMS field
 *                       {@code TRNIDIN PIC X(16)} which the COBOL program
 *                       copied to {@code TRAN-ID} before issuing
 *                       {@code STARTBR}; may be {@code null} or empty
 *                       when listing all transactions
 *
 * @see TransactionListDto.TransactionRow
 */
@Schema(name = "TransactionListDto",
        description = "Paged transaction-list response. Replaces the 3270 "
                + "transaction-list screen rendered by COBOL program "
                + "COTRN00C / BMS mapset COTRN00. Page size is fixed at 10 "
                + "to match the 10-row BMS table COTRN0A.")
public record TransactionListDto(

        @Schema(description = "Transaction rows on this page. Up to 10 "
                + "entries to match the legacy BMS 10-row table "
                + "(COTRN00.bms map COTRN0A, rows TRNID01..TRNID10).")
        @JsonProperty("rows")
        List<TransactionRow> rows,

        @Schema(description = "Current page number (0-indexed), as produced "
                + "by Spring Data Page#getNumber(). The legacy COBOL program "
                + "(COTRN00C.cbl) maintained CDEMO-CT00-PAGE-NUM "
                + "(1-indexed); the Java target normalizes to 0-indexed "
                + "paging per Spring Data convention.",
                example = "0")
        @JsonProperty("page")
        int page,

        @Schema(description = "Page size. Fixed at 10 to match the 10-row "
                + "BMS table in COTRN00.bms. Any request with a different "
                + "size is coerced to 10 by the controller for fidelity "
                + "with the legacy screen.",
                example = "10")
        @JsonProperty("size")
        int size,

        @Schema(description = "Total number of matching transactions across "
                + "all pages, as produced by Spring Data "
                + "Page#getTotalElements(). Reflects the idFilter when "
                + "provided.",
                example = "1500")
        @JsonProperty("totalElements")
        long totalElements,

        @Schema(description = "Total number of pages available given "
                + "totalElements and size, as produced by Spring Data "
                + "Page#getTotalPages().",
                example = "150")
        @JsonProperty("totalPages")
        int totalPages,

        @Schema(description = "First-page indicator. True when this is "
                + "page 0. Corresponds to the COBOL guard in COTRN00C.cbl "
                + "that suppresses page-backward navigation when "
                + "CDEMO-CT00-PAGE-NUM is already at the first page (PF7 "
                + "handler).",
                example = "true")
        @JsonProperty("first")
        boolean first,

        @Schema(description = "Last-page indicator. True when this is the "
                + "final page. Corresponds to the COBOL guard in "
                + "COTRN00C.cbl that suppresses page-forward navigation "
                + "when CDEMO-CT00-NEXT-PAGE-FLG indicates no next page "
                + "(PF8 handler).",
                example = "false")
        @JsonProperty("last")
        boolean last,

        @Schema(description = "Transaction-ID filter echoed from the "
                + "request. The legacy COBOL field TRNIDIN (PIC X(16)) was "
                + "copied to TRAN-ID before the STARTBR positioning read; "
                + "the Java target preserves the same semantics by passing "
                + "this filter to TransactionRepository as a prefix or "
                + "exact match. Null or empty means list all transactions.",
                example = "T000000000000123",
                nullable = true,
                maxLength = 16)
        @JsonProperty("idFilter")
        String idFilter
) {

    /**
     * Defensively masks a card-number-shaped string for safe logging.
     *
     * <p>The masking algorithm preserves only the last 4 characters and
     * prefixes them with 12 asterisks, producing the PCI-DSS-compliant
     * {@code "************nnnn"} form.  {@code null} inputs render as
     * literal {@code null}; inputs shorter than 4 characters are rendered
     * as {@code "****"} (fully masked) to avoid leaking partial digit
     * information.
     *
     * <p>The masking is <b>idempotent</b>: a string already in the
     * {@code "************nnnn"} form passes through unchanged.
     *
     * <p>This helper is used by {@link TransactionRow#toString()} as a
     * defense-in-depth layer over the producer's pre-masking convention.
     *
     * @param value the card-number-shaped value to mask (may be {@code null})
     * @return the masked representation, or {@code null} if the input is
     *         {@code null}; otherwise the masked string
     */
    static String maskPan(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() < 4) {
            return "****";
        }
        return "************" + value.substring(value.length() - 4);
    }

    /**
     * One row of the paged transaction list.
     *
     * <p>Mirrors a single occurrence of the 10-row BMS table {@code COTRN0A}
     * defined in {@code app/bms/COTRN00.bms} and a single {@code TRAN-RECORD}
     * defined in {@code app/cpy/CVTRA05Y.cpy} (RECLN=350).  The BMS row
     * displays four visible fields ({@code TRNIDnnI}, {@code TDATEnnI},
     * {@code TDESCnnI}, {@code TAMTnnnI}); this Java record surfaces those
     * plus four additional fields from the underlying record
     * ({@code cardNumber} &mdash; <b>pre-masked</b>, {@code transactionType},
     * {@code transactionCategory}, {@code source}) so REST callers can render
     * the list without round-tripping for detail.
     *
     * <p><b>Per-component COBOL provenance (CVTRA05Y.cpy):</b>
     * <ul>
     *   <li>{@link #transactionId()} &harr; {@code TRAN-ID PIC X(16)}</li>
     *   <li>{@link #cardNumber()} &harr; {@code TRAN-CARD-NUM PIC X(16)}
     *       (held here in masked form: 12 leading {@code '*'} characters
     *       plus the trailing 4 digits of the original PAN)</li>
     *   <li>{@link #processingTimestamp()} &harr;
     *       {@code TRAN-PROC-TS PIC X(26)} (LE-managed timestamp string,
     *       replaced in Java by {@code java.time.LocalDateTime})</li>
     *   <li>{@link #transactionType()} &harr;
     *       {@code TRAN-TYPE-CD PIC X(02)}</li>
     *   <li>{@link #transactionCategory()} &harr;
     *       {@code TRAN-CAT-CD PIC 9(04)} (4-digit unsigned, max 9999)</li>
     *   <li>{@link #source()} &harr; {@code TRAN-SOURCE PIC X(10)}</li>
     *   <li>{@link #description()} &harr; {@code TRAN-DESC PIC X(100)}</li>
     *   <li>{@link #amount()} &harr; {@code TRAN-AMT PIC S9(09)V99}
     *       (signed, 11 total digits, scale 2 &mdash; BigDecimal with
     *       precision 11, scale 2)</li>
     * </ul>
     *
     * <p>The COBOL screen truncates the description to 26 characters in
     * {@code TDESCnnI} via the BMS field length; the REST response carries
     * the full 100 characters so clients can render the complete text.
     * Clients targeting narrow viewports may truncate to 30 characters on
     * their own.
     *
     * <p><b>PCI-DSS:</b> {@link #cardNumber()} is delivered pre-masked.
     * The {@link #toString()} method below is explicitly overridden so log
     * statements that print the row never emit the merchant or category
     * sensitive fields beyond what is already safe.  Even though the card
     * number is pre-masked, the explicit {@code toString()} provides defense
     * in depth and a single audited place to update if the masking format
     * ever changes.
     *
     * @param transactionId       the transaction's unique identifier
     *                            (primary key of the {@code TRANSACT} VSAM
     *                            KSDS); 16-character string sourced from
     *                            {@code TRAN-ID}
     * @param cardNumber          the card number associated with the
     *                            transaction, <b>pre-masked at the producer</b>
     *                            (only the last 4 digits retained); sourced
     *                            from {@code TRAN-CARD-NUM} after masking
     * @param processingTimestamp the timestamp at which the transaction was
     *                            processed; sourced from
     *                            {@code TRAN-PROC-TS} (LE 26-character
     *                            string parsed into {@code LocalDateTime})
     * @param transactionType     the transaction type code; sourced from
     *                            {@code TRAN-TYPE-CD} (2-character code,
     *                            FK to {@code TRANTYPE} reference data)
     * @param transactionCategory the transaction category code; sourced
     *                            from {@code TRAN-CAT-CD} (4-digit integer,
     *                            FK to {@code TRANCATG} reference data)
     * @param source              the transaction source channel; sourced
     *                            from {@code TRAN-SOURCE} (10-character
     *                            code, e.g. {@code "ONLINE"},
     *                            {@code "POS"})
     * @param description         the transaction description; sourced from
     *                            {@code TRAN-DESC} (full 100 characters;
     *                            clients may truncate to 30 for narrow
     *                            screen rendering matching the legacy BMS
     *                            view, but the wire format is always the
     *                            full string)
     * @param amount              the transaction amount as a
     *                            {@code BigDecimal} with scale 2 (banker's
     *                            rounding, {@code HALF_EVEN}); sourced from
     *                            {@code TRAN-AMT PIC S9(09)V99}; never
     *                            {@code double}/{@code float}
     */
    @Schema(name = "TransactionListDto.TransactionRow",
            description = "Single transaction row in the paged list. "
                    + "Mirrors one occurrence of the 10-row BMS table "
                    + "COTRN0A and one TRAN-RECORD in CVTRA05Y.cpy. Card "
                    + "numbers are pre-masked at the producer (PCI-DSS).")
    public record TransactionRow(

            @Schema(description = "Transaction ID. Maps to COBOL TRAN-ID "
                    + "PIC X(16) in CVTRA05Y.cpy. The primary key of the "
                    + "TRANSACT VSAM KSDS and the JPA primary key of the "
                    + "Transaction entity.",
                    example = "T000000000000123",
                    maxLength = 16)
            @JsonProperty("transactionId")
            String transactionId,

            @Schema(description = "Masked card number. Maps to COBOL "
                    + "TRAN-CARD-NUM PIC X(16) in CVTRA05Y.cpy, masked at "
                    + "the producer so only the last 4 digits are retained. "
                    + "The leading 12 characters are '*' per PCI-DSS PAN "
                    + "masking guidance. Never carries the full PAN.",
                    example = "************1234",
                    maxLength = 16)
            @JsonProperty("cardNumber")
            String cardNumber,

            @JsonFormat(shape = JsonFormat.Shape.STRING,
                    pattern = "yyyy-MM-dd'T'HH:mm:ss")
            @Schema(description = "Processing timestamp. Replaces COBOL "
                    + "TRAN-PROC-TS PIC X(26) (LE-managed 26-character "
                    + "string) with native java.time.LocalDateTime. "
                    + "Serialized on the wire as an ISO-8601 string "
                    + "(yyyy-MM-dd'T'HH:mm:ss). The legacy BMS screen "
                    + "showed only the date portion via WS-TRAN-DATE PIC "
                    + "X(08); the REST response carries the full local "
                    + "datetime.",
                    example = "2026-05-20T14:30:45",
                    format = "date-time")
            @JsonProperty("processingTimestamp")
            LocalDateTime processingTimestamp,

            @Schema(description = "Transaction type code. Maps to COBOL "
                    + "TRAN-TYPE-CD PIC X(02) in CVTRA05Y.cpy. Foreign key "
                    + "to the TRANTYPE reference data (7 rows seeded by "
                    + "V013__seed_transaction_type.sql).",
                    example = "01",
                    maxLength = 2)
            @JsonProperty("transactionType")
            String transactionType,

            @Schema(description = "Transaction category code. Maps to "
                    + "COBOL TRAN-CAT-CD PIC 9(04) in CVTRA05Y.cpy. "
                    + "4-digit unsigned integer (range 0..9999). Foreign "
                    + "key to the TRANCATG reference data (18 rows seeded "
                    + "by V014__seed_transaction_category.sql).",
                    example = "5411",
                    minimum = "0",
                    maximum = "9999")
            @JsonProperty("transactionCategory")
            Integer transactionCategory,

            @Schema(description = "Transaction source channel. Maps to "
                    + "COBOL TRAN-SOURCE PIC X(10) in CVTRA05Y.cpy. "
                    + "10-character code identifying the origin of the "
                    + "transaction (e.g. \"ONLINE\", \"POS\", \"BATCH\").",
                    example = "ONLINE",
                    maxLength = 10)
            @JsonProperty("source")
            String source,

            @Schema(description = "Transaction description. Maps to COBOL "
                    + "TRAN-DESC PIC X(100) in CVTRA05Y.cpy. The full "
                    + "100-character description is carried on the wire; "
                    + "clients targeting narrow viewports (matching the "
                    + "legacy BMS TDESCnnI 26-character field) may "
                    + "truncate for display.",
                    example = "GROCERY STORE PURCHASE",
                    maxLength = 100)
            @JsonProperty("description")
            String description,

            @Schema(description = "Transaction amount. Maps to COBOL "
                    + "TRAN-AMT PIC S9(09)V99 in CVTRA05Y.cpy. Carried as "
                    + "BigDecimal with scale=2 per AAP \u00a70.6.1 "
                    + "decimal-precision rule (never float/double for "
                    + "monetary values). The producer sets the scale to 2 "
                    + "with RoundingMode.HALF_EVEN before populating this "
                    + "field. The OpenAPI format is `decimal` (NOT "
                    + "`double`) to advertise exact decimal precision to "
                    + "generated clients per AAP \u00a70.6.1 (binary "
                    + "floating-point semantics are forbidden for "
                    + "monetary values).",
                    example = "123.45",
                    type = "number",
                    format = "decimal")
            @JsonProperty("amount")
            BigDecimal amount
    ) {

        /**
         * Returns a PCI-DSS-safe string representation of this row.
         *
         * <p>The default {@link Record#toString()} generated by the
         * compiler emits every component including {@code transactionType},
         * {@code transactionCategory}, {@code source}, and the full
         * description.  This override is deliberately narrower &mdash; it
         * emits only the fields a human investigator typically needs from a
         * log line:
         * <ul>
         *   <li>{@code transactionId} &mdash; non-sensitive primary key
         *       used to look up the full record in OpenSearch or RDS</li>
         *   <li>{@code cardNumber} &mdash; <b>defensively masked</b> via
         *       {@link TransactionListDto#maskPan(String)} regardless of
         *       whether the producer pre-masked the value (defense in
         *       depth)</li>
         *   <li>{@code processingTimestamp} &mdash; non-sensitive temporal
         *       context</li>
         *   <li>{@code amount} &mdash; non-sensitive monetary context</li>
         *   <li>{@code description} &mdash; non-sensitive free-text
         *       description</li>
         * </ul>
         *
         * <p>This intentionally omits the merchant identifiers and
         * category/type codes from the log line to keep log volume small
         * and reduce incidental PII surface area; full record details
         * remain available via {@code TransactionDetailDto} and OpenSearch
         * lookup by {@code transactionId}.
         *
         * <p>Although the producer ({@code TransactionListService}) is
         * expected to pre-mask the card number before constructing the
         * row, {@link TransactionListDto#maskPan(String)} is applied here
         * as <b>defense in depth</b> so any direct construction path
         * (test fixtures, mappers, integration scenarios) that passes a
         * raw 16-digit PAN cannot leak that PAN into CloudWatch Logs,
         * OpenSearch indices, or any framework/interceptor that logs the
         * row.  The {@code maskPan} helper is idempotent, so values that
         * are already in the canonical {@code "************nnnn"} form
         * pass through unchanged.
         *
         * <p><b>NOTE on record-generated {@code equals()}/{@code hashCode()}
         * (accepted risk per AAP &sect;0.6.6).</b>  Java records auto-generate
         * {@link Object#equals(Object)} and {@link Object#hashCode()} over
         * every component &mdash; including {@link #cardNumber()} &mdash;
         * and the record contract forbids overriding these methods to
         * exclude components without converting the type to a regular
         * class (a scope expansion that would violate the AAP-mandated
         * Minimal Change Clause).  The risk that the (typically already
         * masked) PAN participates in equality/hash operations is
         * <b>accepted</b> because:
         * <ul>
         *   <li>The producer ({@code TransactionListService}) pre-masks
         *       the PAN at row construction time, so the canonical
         *       in-memory value is the masked form
         *       ({@code ************XXXX}).</li>
         *   <li>{@link Object#equals(Object)} and {@link Object#hashCode()}
         *       on rows are not invoked by any audit, logging, caching,
         *       or persistence path &mdash; only {@code toString()}
         *       (defensively masked above) reaches CloudWatch /
         *       OpenSearch.</li>
         *   <li>Debugger inspection and heap-dump analysis are governed
         *       by the platform's PCI-DSS access controls (AAP
         *       &sect;0.6.6) and are out of scope for DTO-level
         *       mitigation.</li>
         * </ul>
         *
         * @return a string of the form
         *         {@code TransactionRow[transactionId=..., cardNumber=...,
         *         processingTimestamp=..., amount=..., description=...]}
         *         with the card number defensively masked
         */
        @Override
        public String toString() {
            // Defensive masking — the producer (TransactionListService)
            // is expected to pre-mask the PAN per PCI-DSS Requirement
            // 3.4, but maskPan() is applied here as defense in depth so
            // a direct construction path with a raw PAN cannot leak it
            // through toString(). The maskPan() helper is idempotent.
            return "TransactionRow[transactionId=" + transactionId
                    + ", cardNumber=" + maskPan(cardNumber)
                    + ", processingTimestamp=" + processingTimestamp
                    + ", amount=" + amount
                    + ", description=" + description
                    + "]";
        }
    }
}
