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

/**
 * Flattened reporting / statement transaction DTO mirroring the
 * {@code TRNX-RECORD} layout defined in {@code app/cpy/COSTM01.CPY}.
 *
 * <p>{@code COSTM01.CPY} (titled
 * &quot;CardDemo - Transaction altered Layout for use in reporting&quot;
 * in its banner comment) is a <b>denormalized reporting view</b> of a
 * transaction.  In contrast to {@code CVTRA05Y.cpy} (the
 * {@code TRAN-RECORD} layout used by the {@code TRANSACT} VSAM KSDS and
 * mirrored by the {@code Transaction} JPA entity in
 * {@code com.awsm2.carddemo.domain}), the {@code TRNX-RECORD} layout
 * pre-joins merchant details (name, city, ZIP) inline alongside the core
 * transaction fields so that statement and report writers can emit each
 * printable line with a single sequential read &mdash; no run-time
 * cross-reference lookup is required during batch output generation.
 * This DTO carries that denormalized projection on the Java side.
 *
 * <p><b>Used by:</b>
 * <ul>
 *   <li>{@code com.awsm2.carddemo.service.StatementGenerationService}
 *       (port of {@code app/cbl/CBSTM03A.CBL} text-statement generator and
 *       {@code app/cbl/CBSTM03B.CBL} HTML-statement variant) &mdash; one
 *       instance of this DTO per posted transaction line on a customer
 *       statement.</li>
 *   <li>{@code com.awsm2.carddemo.service.TransactionReportService}
 *       (port of {@code app/cbl/CBTRN03C.cbl} transaction-report writer)
 *       &mdash; one instance per transaction matching the report's
 *       date-window and account-filter criteria.</li>
 *   <li>{@code com.awsm2.carddemo.batch.StatementGenerationJob} and
 *       {@code com.awsm2.carddemo.batch.TransactionReportJob} (Spring
 *       Batch jobs that wrap the two services above) &mdash; the Spring
 *       Batch {@code ItemProcessor} stage produces this DTO and the
 *       {@code ItemWriter} stage serializes it (via the
 *       {@code S3OutputService} adapter) to the configured S3 output
 *       location for the statement or report run.</li>
 * </ul>
 *
 * <p><b>COBOL TRNX-RECORD layout (from {@code app/cpy/COSTM01.CPY}):</b>
 * <pre>{@code
 *   01  TRNX-RECORD.
 *       05  TRNX-KEY.
 *           10  TRNX-CARD-NUM            PIC X(16).
 *           10  TRNX-ID                  PIC X(16).
 *       05  TRNX-REST.
 *           10  TRNX-TYPE-CD             PIC X(02).
 *           10  TRNX-CAT-CD              PIC 9(04).
 *           10  TRNX-SOURCE              PIC X(10).
 *           10  TRNX-DESC                PIC X(100).
 *           10  TRNX-AMT                 PIC S9(09)V99.
 *           10  TRNX-MERCHANT-ID         PIC 9(09).      (not exposed)
 *           10  TRNX-MERCHANT-NAME       PIC X(50).
 *           10  TRNX-MERCHANT-CITY       PIC X(50).
 *           10  TRNX-MERCHANT-ZIP        PIC X(10).
 *           10  TRNX-ORIG-TS             PIC X(26).
 *           10  TRNX-PROC-TS             PIC X(26).
 *           10  FILLER                   PIC X(20).      (not exposed)
 * }</pre>
 *
 * <p><b>Field-by-field mapping &mdash; COBOL {@code COSTM01.CPY}
 * TRNX-RECORD / this DTO:</b>
 * <pre>{@code
 *   COBOL field              COBOL PIC         This DTO field
 *   -----------------------  ----------------  -----------------------
 *   TRNX-CARD-NUM            X(16)             cardNumber
 *   TRNX-ID                  X(16)             transactionId
 *   TRNX-TYPE-CD             X(02)             transactionType
 *   TRNX-CAT-CD              9(04)             transactionCategory
 *   TRNX-SOURCE              X(10)             source
 *   TRNX-DESC                X(100)            description
 *   TRNX-AMT                 S9(09)V99         amount
 *   TRNX-MERCHANT-ID         9(09)             (not exposed)
 *   TRNX-MERCHANT-NAME       X(50)             merchantName
 *   TRNX-MERCHANT-CITY       X(50)             merchantCity
 *   TRNX-MERCHANT-ZIP        X(10)             merchantZip
 *   TRNX-ORIG-TS             X(26)             originationTimestamp
 *   TRNX-PROC-TS             X(26)             processingTimestamp
 *   FILLER                   X(20)             (not exposed)
 * }</pre>
 *
 * <p>{@code TRNX-MERCHANT-ID} is intentionally <b>not exposed</b> on this
 * DTO &mdash; the consumer endpoints (statement and transaction-report
 * output) only render merchant <em>name / city / ZIP</em> as printable
 * fields, and the numeric merchant identifier is a system-internal key.
 * The trailing {@code FILLER PIC X(20)} is reserved space at the end of
 * the COBOL record with no associated meaning; it is not carried into the
 * Java target.
 *
 * <p><b>Decimal precision (AAP &sect;0.6.1 / &sect;0.7.1):</b>
 * {@link #amount()} maps {@code TRNX-AMT PIC S9(09)V99} to
 * {@link BigDecimal} with {@code scale=2}.  The producer service
 * ({@code StatementGenerationService} or {@code TransactionReportService})
 * is responsible for setting the scale to {@code 2} with
 * {@code RoundingMode.HALF_EVEN} (banker's rounding) before populating
 * this field, matching the COBOL {@code PIC 9} fixed-point arithmetic
 * semantics exactly.  Use of {@code float} or {@code double} for any
 * monetary value is forbidden by the AAP and is not used here.
 *
 * <p><b>Timestamp precision (AAP &sect;0.6.3 / &sect;0.5.2):</b>
 * {@link #originationTimestamp()} and {@link #processingTimestamp()} map
 * {@code TRNX-ORIG-TS PIC X(26)} and {@code TRNX-PROC-TS PIC X(26)}
 * respectively.  The 26-character COBOL timestamp layout
 * ({@code YYYY-MM-DD HH:MM:SS.NNNNNN} &mdash; 10 chars date + 1 space + 8
 * chars time + 1 dot + 6 chars microseconds + null = 26 bytes) preserves
 * microsecond precision.  The Java target uses {@link LocalDateTime}
 * (which supports microsecond precision via the {@code SSSSSS}
 * {@link java.time.format.DateTimeFormatter} pattern) and replaces the
 * mainframe LE {@code CEEDAYS} / {@code CEELOCT} dependency entirely per
 * AAP &sect;0.5.2.  Both timestamps are serialized to the wire as ISO-8601
 * strings with the pattern {@code yyyy-MM-dd'T'HH:mm:ss.SSSSSS} via the
 * {@link JsonFormat} annotation on each field; readers should round-trip
 * the same pattern to preserve sub-second precision.
 *
 * <p><b>PCI-DSS handling (AAP &sect;0.7.1, &sect;0.6.6):</b>
 * <ul>
 *   <li>{@link #cardNumber()} carries the 16-digit Primary Account Number
 *       (PAN).  The legacy COBOL batch writers ({@code CBSTM03A.CBL} for
 *       statements, {@code CBTRN03C.cbl} for transaction reports) emit
 *       the masked PAN on the printable output line; the producer service
 *       on the Java side is therefore responsible for masking the PAN to
 *       last-4 (or a regulator-acceptable equivalent) <em>before</em>
 *       populating this field whenever the DTO will be emitted to an
 *       external statement, report, or S3 object.  Authenticated /
 *       authorized internal callers (e.g. fraud investigators querying
 *       OpenSearch via the same DTO) may receive the full PAN at the
 *       service layer's discretion; endpoint-level authorization is
 *       enforced by Spring Security in
 *       {@code com.awsm2.carddemo.config.SecurityConfig}.</li>
 *   <li>The {@link #toString()} method below <b>always</b> masks the PAN
 *       to its last four digits regardless of the value stored in
 *       {@link #cardNumber}, per PCI-DSS Requirement 3.3 and the
 *       industry-standard PAN-masking convention
 *       ({@code ************NNNN}).  This ensures that any incidental
 *       logging of this DTO (Spring Boot exception logs, debug logs,
 *       ad-hoc {@code log.info(dto)} statements, request/response
 *       tracing) never emits a full PAN to CloudWatch Logs.  This is
 *       defense in depth on top of the CloudWatch log filters and Amazon
 *       Macie S3 scanning described in AAP &sect;0.6.6.</li>
 *   <li>No CVV, CVV2, CVC, expiration date, track data, PIN, PIN block,
 *       or SSN is carried on this DTO.  The {@code TRNX-RECORD} layout in
 *       {@code COSTM01.CPY} does not include any of these fields, so the
 *       Java target faithfully omits them.</li>
 * </ul>
 *
 * <p>This record is immutable, response-only, and used by both REST API
 * consumers (e.g. an internal statement-preview endpoint) and Spring Batch
 * {@code ItemWriter} pipelines.  It carries no Jakarta Bean Validation
 * constraints &mdash; validation of the underlying transaction data is
 * performed at the source ({@code Transaction} JPA entity column
 * constraints + posting-time validators in
 * {@code TransactionPostingService}) before this DTO is ever produced.
 *
 * <p><b>COBOL Provenance (AAP &sect;0.7.3 traceability):</b>
 * <ul>
 *   <li>Copybook: {@code app/cpy/COSTM01.CPY} ({@code TRNX-RECORD})</li>
 *   <li>Used by:
 *     <ul>
 *       <li>{@code app/cbl/CBSTM03A.CBL} (text statement generator)</li>
 *       <li>{@code app/cbl/CBSTM03B.CBL} (HTML statement variant)</li>
 *       <li>{@code app/cbl/CBTRN03C.cbl} (transaction report writer)</li>
 *     </ul>
 *   </li>
 *   <li>Source-of-truth normalized record: {@code app/cpy/CVTRA05Y.cpy}
 *       ({@code TRAN-RECORD}, RECLN=350) &mdash; the normalized form
 *       carried by the {@code Transaction} JPA entity.  This DTO is the
 *       denormalized projection of {@code TRAN-RECORD} pre-joined with
 *       merchant master data for reporting output.</li>
 * </ul>
 *
 * @param cardNumber           the 16-digit card number (masked in
 *                             {@link #toString()}); maps
 *                             {@code TRNX-CARD-NUM PIC X(16)} from
 *                             {@code COSTM01.CPY}
 * @param transactionId        the 16-character transaction identifier;
 *                             maps {@code TRNX-ID PIC X(16)}
 * @param transactionType      the 2-character transaction type code;
 *                             maps {@code TRNX-TYPE-CD PIC X(02)}
 *                             (joinable to
 *                             {@code TransactionType.typeCode})
 * @param transactionCategory  the 4-digit transaction category code;
 *                             maps {@code TRNX-CAT-CD PIC 9(04)}
 *                             (joinable to
 *                             {@code TransactionCategory.categoryCode})
 * @param source               the 10-character transaction source channel;
 *                             maps {@code TRNX-SOURCE PIC X(10)} (e.g.
 *                             {@code "ONLINE"}, {@code "POS"},
 *                             {@code "BATCH"})
 * @param description          the up-to-100-character free-text
 *                             description; maps
 *                             {@code TRNX-DESC PIC X(100)}
 * @param amount               the transaction amount; maps
 *                             {@code TRNX-AMT PIC S9(09)V99} as a
 *                             {@link BigDecimal} with {@code scale=2}
 * @param merchantName         the up-to-50-character merchant name; maps
 *                             {@code TRNX-MERCHANT-NAME PIC X(50)}
 * @param merchantCity         the up-to-50-character merchant city; maps
 *                             {@code TRNX-MERCHANT-CITY PIC X(50)}
 * @param merchantZip          the 10-character merchant ZIP; maps
 *                             {@code TRNX-MERCHANT-ZIP PIC X(10)}
 * @param originationTimestamp the origination timestamp with microsecond
 *                             precision; maps
 *                             {@code TRNX-ORIG-TS PIC X(26)}
 * @param processingTimestamp  the processing timestamp with microsecond
 *                             precision; maps
 *                             {@code TRNX-PROC-TS PIC X(26)}
 *
 * @see com.awsm2.carddemo.dto.TransactionDetailDto for the normalized
 *      single-transaction view used by {@code GET /api/transactions/{id}}
 * @see com.awsm2.carddemo.dto.TransactionListDto for the paginated list
 *      view used by {@code GET /api/transactions}
 */
@Schema(name = "StatementTransactionDto",
        description = "Flattened reporting / statement transaction DTO. "
                + "Mirrors the denormalized TRNX-RECORD layout from "
                + "app/cpy/COSTM01.CPY, used by COBOL programs CBSTM03A "
                + "(text statements), CBSTM03B (HTML statements), and "
                + "CBTRN03C (transaction reports). Carries the core "
                + "transaction fields plus pre-joined merchant name / "
                + "city / ZIP so a single sequential write produces one "
                + "printable output line. The full PAN field is masked "
                + "to last-4 in toString() per PCI-DSS Requirement 3.3.")
public record StatementTransactionDto(

        @Schema(description = "Card number (Primary Account Number). Maps "
                + "to COBOL TRNX-CARD-NUM PIC X(16) in COSTM01.CPY. The "
                + "producer service is responsible for masking the PAN "
                + "to last-4 before populating this field for external "
                + "(statement / report / S3 object) emission per "
                + "PCI-DSS Requirement 3.3; this DTO's toString() "
                + "additionally masks the PAN unconditionally as defense "
                + "in depth.",
                example = "************0001",
                maxLength = 16)
        @JsonProperty("cardNumber")
        String cardNumber,

        @Schema(description = "Transaction identifier. Maps to COBOL "
                + "TRNX-ID PIC X(16) in COSTM01.CPY. Primary key of the "
                + "TRANSACT VSAM KSDS (now the transaction table on RDS "
                + "PostgreSQL).",
                example = "T000000000000001",
                maxLength = 16)
        @JsonProperty("transactionId")
        String transactionId,

        @Schema(description = "Transaction type code. Maps to COBOL "
                + "TRNX-TYPE-CD PIC X(02) in COSTM01.CPY. Joinable to "
                + "the TransactionType reference table (primary key "
                + "tran_type, seeded by Flyway "
                + "V013__seed_transaction_type.sql from "
                + "app/data/ASCII/trantype.txt).",
                example = "01",
                maxLength = 2)
        @JsonProperty("transactionType")
        String transactionType,

        @Schema(description = "Transaction category code. Maps to COBOL "
                + "TRNX-CAT-CD PIC 9(04) in COSTM01.CPY. Joinable to "
                + "the TransactionCategory reference table (primary key "
                + "tran_cat_cd, seeded by Flyway "
                + "V014__seed_transaction_category.sql from "
                + "app/data/ASCII/trancatg.txt).",
                example = "5411")
        @JsonProperty("transactionCategory")
        Integer transactionCategory,

        @Schema(description = "Transaction source channel. Maps to COBOL "
                + "TRNX-SOURCE PIC X(10) in COSTM01.CPY. Indicates the "
                + "origination channel of the transaction (e.g. "
                + "'ONLINE' from CICS-equivalent REST endpoints, "
                + "'POS' from point-of-sale, 'BATCH' from end-of-day "
                + "posting).",
                example = "ONLINE",
                maxLength = 10)
        @JsonProperty("source")
        String source,

        @Schema(description = "Transaction free-text description. Maps "
                + "to COBOL TRNX-DESC PIC X(100) in COSTM01.CPY. The "
                + "100-character field is intended to be human-readable "
                + "and is emitted verbatim onto statement and report "
                + "lines.",
                maxLength = 100)
        @JsonProperty("description")
        String description,

        @Schema(description = "Transaction amount. Maps to COBOL "
                + "TRNX-AMT PIC S9(09)V99 in COSTM01.CPY. Carried as a "
                + "BigDecimal with scale=2; the producer service is "
                + "responsible for setting the scale to 2 with "
                + "RoundingMode.HALF_EVEN (banker's rounding) per AAP "
                + "&sect;0.6.1. Use of float or double for monetary "
                + "values is forbidden by the AAP.",
                example = "123.45")
        @JsonProperty("amount")
        BigDecimal amount,

        @Schema(description = "Merchant name. Maps to COBOL "
                + "TRNX-MERCHANT-NAME PIC X(50) in COSTM01.CPY. "
                + "Pre-joined from the merchant master record into the "
                + "denormalized reporting view to avoid per-line "
                + "cross-reference lookups during batch output "
                + "generation.",
                example = "ACME COFFEE SHOP",
                maxLength = 50)
        @JsonProperty("merchantName")
        String merchantName,

        @Schema(description = "Merchant city. Maps to COBOL "
                + "TRNX-MERCHANT-CITY PIC X(50) in COSTM01.CPY. "
                + "Pre-joined from the merchant master record.",
                example = "SEATTLE",
                maxLength = 50)
        @JsonProperty("merchantCity")
        String merchantCity,

        @Schema(description = "Merchant ZIP code. Maps to COBOL "
                + "TRNX-MERCHANT-ZIP PIC X(10) in COSTM01.CPY. The "
                + "10-character width accommodates the US ZIP+4 format "
                + "('99999-9999') as well as international postal codes "
                + "padded to 10 characters.",
                example = "98109",
                maxLength = 10)
        @JsonProperty("merchantZip")
        String merchantZip,

        @JsonFormat(shape = JsonFormat.Shape.STRING,
                pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS")
        @Schema(description = "Transaction origination timestamp with "
                + "microsecond precision. Maps to COBOL "
                + "TRNX-ORIG-TS PIC X(26) in COSTM01.CPY. The 26-char "
                + "COBOL layout ('YYYY-MM-DD HH:MM:SS.NNNNNN') preserves "
                + "microsecond precision; the Java target uses "
                + "LocalDateTime (which supports microsecond precision "
                + "via the SSSSSS DateTimeFormatter pattern). Serialized "
                + "as an ISO-8601 string with pattern "
                + "yyyy-MM-dd'T'HH:mm:ss.SSSSSS.",
                example = "2026-05-20T14:30:45.123456",
                format = "date-time")
        @JsonProperty("originationTimestamp")
        LocalDateTime originationTimestamp,

        @JsonFormat(shape = JsonFormat.Shape.STRING,
                pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS")
        @Schema(description = "Transaction processing timestamp with "
                + "microsecond precision. Maps to COBOL "
                + "TRNX-PROC-TS PIC X(26) in COSTM01.CPY. The 26-char "
                + "COBOL layout ('YYYY-MM-DD HH:MM:SS.NNNNNN') preserves "
                + "microsecond precision; the Java target uses "
                + "LocalDateTime. Serialized as an ISO-8601 string with "
                + "pattern yyyy-MM-dd'T'HH:mm:ss.SSSSSS.",
                example = "2026-05-20T14:30:46.789012",
                format = "date-time")
        @JsonProperty("processingTimestamp")
        LocalDateTime processingTimestamp
) {

    /**
     * Returns a PCI-DSS-safe string representation of this statement /
     * report transaction.
     *
     * <p>The default {@link Record#toString()} implementation generated
     * by the Java compiler would emit every component of this record,
     * including the <b>full 16-digit PAN</b> stored in
     * {@link #cardNumber}.  This override deliberately masks the PAN to
     * its last four digits per PCI-DSS Requirement 3.3 and the
     * industry-standard PAN-masking convention
     * ({@code ************NNNN}: twelve asterisks followed by the last
     * four PAN digits), ensuring that any accidental logging of this DTO
     * (Spring Boot exception logs, debug logs, ad-hoc
     * {@code log.info(dto)} statements, request/response tracing via
     * {@code spring.web.logging}, Spring Batch step-execution-listener
     * audit logs) never emits a full PAN to CloudWatch Logs.  This is
     * defense in depth on top of the CloudWatch log filters and Amazon
     * Macie S3 scanning described in AAP &sect;0.6.6.
     *
     * <p>Masking semantics for edge cases:
     * <ul>
     *   <li>{@code null} card number &rarr; emitted as {@code "****"} (no
     *       leading mask, no exception thrown).</li>
     *   <li>Card number with fewer than 4 characters &rarr; emitted as
     *       {@code "****"} (no last-4 to display; emitting only the mask
     *       avoids any partial leakage of short strings).</li>
     *   <li>Card number with 4 or more characters &rarr; emitted as
     *       twelve asterisks followed by the last four characters,
     *       regardless of total length (16 is the canonical CardDemo
     *       PAN length per {@code TRNX-CARD-NUM PIC X(16)}; the mask
     *       length is fixed at 12 to match the industry-standard
     *       {@code ************NNNN} format and not the input length).</li>
     * </ul>
     *
     * <p>This implementation emits a deliberately narrow set of fields
     * intended for a human investigator's log line &mdash; transaction
     * id, type, category, amount, masked PAN, merchant name, and the
     * processing timestamp.  The remaining fields (source, description,
     * merchant city, merchant ZIP, origination timestamp) are omitted
     * from the projection purely to keep log lines narrow; the complete
     * record remains available by re-querying the producing service or
     * by looking up the underlying {@link com.awsm2.carddemo.domain
     * Transaction} entity in OpenSearch by {@code transactionId}.
     *
     * @return a PCI-safe string of the form
     *         {@code StatementTransactionDto[cardNumber=************NNNN,
     *         transactionId=..., transactionType=...,
     *         transactionCategory=..., amount=...,
     *         merchantName=..., processingTimestamp=...]}
     */
    @Override
    public String toString() {
        final String maskedPan;
        if (cardNumber == null || cardNumber.length() < 4) {
            maskedPan = "****";
        } else {
            maskedPan = "************"
                    + cardNumber.substring(cardNumber.length() - 4);
        }
        return "StatementTransactionDto[cardNumber=" + maskedPan
                + ", transactionId=" + transactionId
                + ", transactionType=" + transactionType
                + ", transactionCategory=" + transactionCategory
                + ", amount=" + amount
                + ", merchantName=" + merchantName
                + ", processingTimestamp=" + processingTimestamp
                + "]";
    }
}
