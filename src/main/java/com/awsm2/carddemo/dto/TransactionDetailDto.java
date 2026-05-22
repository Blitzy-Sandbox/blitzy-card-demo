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
 * Single-transaction detail response DTO.
 *
 * <p>Replaces the 3270 transaction-detail screen rendered by the COBOL/CICS
 * program {@code COTRN01C.cbl} (CICS transaction id {@code CT01}).  In the
 * source, {@code COTRN01C} reads a single record from the {@code TRANSACT}
 * VSAM KSDS by transaction ID ({@code TRAN-ID PIC X(16)}) and projects every
 * field of the 350-byte {@code TRAN-RECORD} (defined in
 * {@code app/cpy/CVTRA05Y.cpy}) onto the read-only BMS map {@code COTRN1A}
 * defined in {@code app/bms/COTRN01.bms} (View Transaction screen).  The
 * legacy operator enters a transaction ID in the {@code TRNIDINI PIC X(16)}
 * field and presses {@code ENTER}; on a successful {@code EXEC CICS READ},
 * the program populates {@code TRNIDO}, {@code CARDNUMO}, {@code TTYPCDO},
 * {@code TCATCDO}, {@code TRNSRCO}, {@code TDESCO}, {@code TRNAMTO},
 * {@code TORIGDTO}, {@code TPROCDTO}, {@code MIDO}, {@code MNAMEO},
 * {@code MCITYO}, and {@code MZIPO} on the symbolic map {@code COTRN1AO}
 * (defined in {@code app/cpy-bms/COTRN01.CPY}) and sends the map back to
 * the terminal.
 *
 * <p>In the Java target, this read-only flow is replaced by
 * {@code GET /api/transactions/{id}} on
 * {@code com.awsm2.carddemo.controller.TransactionController}.  The
 * controller delegates to {@code TransactionDetailService} which loads the
 * {@code Transaction} JPA entity from {@code TransactionRepository.findById}
 * and projects it into this DTO.  A {@code 404 Not Found} response is
 * emitted (via {@code RecordNotFoundException} translated by
 * {@code GlobalExceptionHandler}) for any transaction ID that does not
 * resolve to a row in the {@code transaction} table &mdash; preserving the
 * COBOL behavior in {@code COTRN01C} where {@code DFHRESP(NOTFND)} on the
 * {@code READ} causes the program to set {@code WS-MESSAGE} to
 * &quot;Transaction ID NOT found...&quot; and re-render the screen with the
 * input field highlighted in red.
 *
 * <p><b>Read-only view DTO &mdash; no version field.</b>  Per AAP
 * &sect;0.7.3, transactions are immutable once posted to the
 * {@code TRANSACT} VSAM KSDS in the COBOL source, and the {@code COTRN01C}
 * program performs no {@code REWRITE} or {@code DELETE}.  Accordingly this
 * DTO carries no JPA optimistic-lock {@code version} field (in contrast to
 * {@code AccountUpdateDto} / {@code CardUpdateDto} which do).
 *
 * <p><b>Field-by-field mapping &mdash; COTRN1A BMS map / CVTRA05Y
 * TRAN-RECORD / COTRN1AO symbolic map / this DTO:</b>
 * <pre>{@code
 *   BMS field    Symbolic field   CVTRA05Y field            This DTO field
 *   ----------   --------------   -----------------------   ----------------------
 *   TRNID        TRNIDO   X(16)   TRAN-ID         X(16)     transactionId
 *   TTYPCD       TTYPCDO  X(02)   TRAN-TYPE-CD    X(02)     transactionType
 *   TCATCD       TCATCDO  X(04)   TRAN-CAT-CD     9(04)     transactionCategory
 *   TRNSRC       TRNSRCO  X(10)   TRAN-SOURCE     X(10)     source
 *   TDESC        TDESCO   X(60)*  TRAN-DESC       X(100)*   description
 *   TRNAMT       TRNAMTO  X(12)** TRAN-AMT  S9(09)V99       amount
 *   MID          MIDO     X(09)   TRAN-MERCHANT-ID  9(09)   merchantId
 *   MNAME        MNAMEO   X(30)*  TRAN-MERCHANT-NAME X(50)* merchantName
 *   MCITY        MCITYO   X(25)*  TRAN-MERCHANT-CITY X(50)* merchantCity
 *   MZIP         MZIPO    X(10)   TRAN-MERCHANT-ZIP  X(10)  merchantZip
 *   CARDNUM      CARDNUMO X(16)   TRAN-CARD-NUM   X(16)     cardNumber
 *   TORIGDT      TORIGDTO X(10)*  TRAN-ORIG-TS    X(26)*    originationTimestamp
 *   TPROCDT      TPROCDTO X(10)*  TRAN-PROC-TS    X(26)*    processingTimestamp
 *   (none)                        FILLER          X(20)     (not exposed)
 * }</pre>
 *
 * <p>(*) The legacy BMS map projects only a truncated portion of certain
 * source fields (e.g. {@code TDESCO} renders the first 60 of 100 description
 * characters; {@code MNAMEO} renders the first 30 of 50 merchant-name
 * characters; {@code MCITYO} renders the first 25 of 50 merchant-city
 * characters; {@code TORIGDTO} renders the first 10 of 26 timestamp
 * characters &mdash; the date portion).  This DTO carries the <b>full</b>
 * source-field value so REST clients can render whatever projection suits
 * their viewport.  No business calculation is changed; this is informational
 * fidelity enrichment and is consistent with the AAP Minimal Change Clause
 * (&sect;0.7.3) which forbids changes to business logic but does not
 * mandate truncating valid record fields when exposing them on a wider
 * channel than 24x80 terminal real estate permitted.
 *
 * <p>(**) The BMS map {@code TRNAMTO PIC X(12)} renders the amount as a
 * formatted display string (e.g. {@code +99999999.99} per the working
 * storage variable {@code WS-TRAN-AMT PIC +99999999.99} in
 * {@code COTRN01C.cbl}).  The Java target carries the value as a typed
 * {@link BigDecimal} with {@code scale=2}; clients are responsible for
 * locale/format-specific rendering.
 *
 * <p><b>Decimal precision (AAP &sect;0.6.1 / &sect;0.7.1):</b>
 * {@link #amount()} maps {@code TRAN-AMT PIC S9(09)V99} to {@link BigDecimal}
 * with {@code scale=2}.  The producer ({@code TransactionDetailService}) is
 * responsible for setting the scale to {@code 2} with
 * {@code RoundingMode.HALF_EVEN} before populating this field.  Use of
 * {@code float} or {@code double} for any monetary value is forbidden by
 * the AAP and is not used here.
 *
 * <p><b>Timestamp precision (AAP &sect;0.6.3 / &sect;0.5.2):</b>
 * {@link #originationTimestamp()} and {@link #processingTimestamp()} map
 * {@code TRAN-ORIG-TS PIC X(26)} and {@code TRAN-PROC-TS PIC X(26)}
 * respectively from {@code CVTRA05Y.cpy}.  The COBOL 26-character format
 * (e.g. {@code 2026-05-20-14.30.45.123456}) preserves microsecond precision
 * &mdash; the Java target uses {@link LocalDateTime} (which supports
 * microsecond precision via the {@code SSSSSS} {@code DateTimeFormatter}
 * pattern) and replaces the LE {@code CEEDAYS} dependency entirely per AAP
 * &sect;0.5.2.  Both timestamps are serialized to the wire as ISO-8601
 * strings with the pattern {@code yyyy-MM-dd'T'HH:mm:ss.SSSSSS} via
 * {@link JsonFormat}.
 *
 * <p><b>PCI-DSS handling (AAP &sect;0.7.1, &sect;0.6.6):</b>
 * <ul>
 *   <li>{@link #cardNumber()} is the full 16-digit Primary Account Number
 *       (PAN) loaded from the {@code transaction.tran_card_num} column.
 *       The legacy 3270 detail screen also displayed the full PAN to the
 *       authenticated operator &mdash; preserving this projection for
 *       authenticated/authorized REST callers is a deliberate
 *       functional-parity decision (AAP Minimal Change Clause,
 *       &sect;0.7.3).  Endpoint authorization is enforced by Spring
 *       Security on {@code TransactionController} (see {@code SecurityConfig}
 *       &mdash; only authenticated principals with the appropriate role
 *       may invoke {@code GET /api/transactions/{id}}).</li>
 *   <li>The {@link #toString()} method below explicitly masks the PAN to
 *       its last four digits (PCI-DSS Requirement 3.3 / PAN-masking
 *       industry standard) so that any incidental logging of this DTO
 *       (Spring Boot exception logs, debug logs, ad-hoc {@code log.info}
 *       statements, request/response tracing) never emits a full PAN to
 *       CloudWatch Logs.  This is defense-in-depth on top of the
 *       CloudWatch log filters + Macie S3 scanning described in AAP
 *       &sect;0.6.6.</li>
 *   <li>No CVV, CVV2, CVC, expiration date, track data, PIN, or PIN block
 *       is carried on this DTO.  The {@code TRAN-RECORD} layout in
 *       {@code CVTRA05Y.cpy} does not include any of these fields, so the
 *       Java target faithfully omits them.</li>
 *   <li>{@link #merchantName()}, {@link #merchantCity()}, and
 *       {@link #merchantZip()} are not PCI-DSS-sensitive (they describe
 *       the acquiring merchant, not the cardholder) and are emitted
 *       verbatim; they are also excluded from {@link #toString()} purely
 *       to keep log lines narrow.</li>
 * </ul>
 *
 * <p>This record is immutable and response-only; it carries no Jakarta
 * Bean Validation constraints (the request side &mdash; the
 * {@code {id}} path variable on {@code GET /api/transactions/{id}}
 * &mdash; is validated at the controller method-parameter level via
 * {@code @PathVariable @Size(min = 1, max = 16) String id}, not on this
 * DTO).
 *
 * <p><b>COBOL Provenance (AAP &sect;0.7.3 traceability):</b>
 * <ul>
 *   <li>BMS Mapset: {@code app/bms/COTRN01.bms} (mapset {@code COTRN01},
 *       map {@code COTRN1A}, single-record View Transaction screen)</li>
 *   <li>Symbolic Map: {@code app/cpy-bms/COTRN01.CPY}
 *       ({@code COTRN1AI} input layout and {@code COTRN1AO} output layout)</li>
 *   <li>Program: {@code app/cbl/COTRN01C.cbl} (CICS transaction
 *       {@code CT01}, program name {@code COTRN01C}, function "View a
 *       Transaction from TRANSACT file")</li>
 *   <li>Record Layout: {@code app/cpy/CVTRA05Y.cpy}
 *       ({@code TRAN-RECORD}, RECLN=350)</li>
 *   <li>VSAM Cluster: {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}
 *       (RECLN=350, KEYLEN=16; primary key {@code TRAN-ID}) &mdash; replaced
 *       in the Java target by the {@code transaction} table on RDS
 *       PostgreSQL Multi-AZ per AAP &sect;0.6.2.</li>
 * </ul>
 *
 * @param transactionId        the 16-character transaction identifier;
 *                             maps {@code TRAN-ID PIC X(16)} from
 *                             {@code CVTRA05Y.cpy}
 * @param transactionType      the 2-character transaction type code;
 *                             maps {@code TRAN-TYPE-CD PIC X(02)} (joinable
 *                             to {@code TransactionType.typeCode})
 * @param transactionCategory  the 4-digit transaction category code;
 *                             maps {@code TRAN-CAT-CD PIC 9(04)}
 *                             (joinable to
 *                             {@code TransactionCategory.categoryCode})
 * @param source               the 10-character transaction source channel;
 *                             maps {@code TRAN-SOURCE PIC X(10)} (e.g.
 *                             {@code "ONLINE"}, {@code "POS"}, {@code "BATCH"})
 * @param description          the up-to-100-character free-text description;
 *                             maps {@code TRAN-DESC PIC X(100)}
 * @param amount               the transaction amount; maps
 *                             {@code TRAN-AMT PIC S9(09)V99} as a
 *                             {@link BigDecimal} with {@code scale=2}
 * @param merchantId           the 9-digit merchant identifier; maps
 *                             {@code TRAN-MERCHANT-ID PIC 9(09)}
 * @param merchantName         the up-to-50-character merchant name; maps
 *                             {@code TRAN-MERCHANT-NAME PIC X(50)}
 * @param merchantCity         the up-to-50-character merchant city; maps
 *                             {@code TRAN-MERCHANT-CITY PIC X(50)}
 * @param merchantZip          the 10-character merchant ZIP; maps
 *                             {@code TRAN-MERCHANT-ZIP PIC X(10)}
 * @param cardNumber           the 16-digit card number (full PAN, masked
 *                             in {@link #toString()}); maps
 *                             {@code TRAN-CARD-NUM PIC X(16)}
 * @param originationTimestamp the origination timestamp with microsecond
 *                             precision; maps
 *                             {@code TRAN-ORIG-TS PIC X(26)}
 * @param processingTimestamp  the processing timestamp with microsecond
 *                             precision; maps
 *                             {@code TRAN-PROC-TS PIC X(26)}
 *
 * @see com.awsm2.carddemo.dto.TransactionListDto for the paginated
 *      list variant ({@code GET /api/transactions})
 * @see com.awsm2.carddemo.dto.TransactionAddDto for the create-transaction
 *      request DTO ({@code POST /api/transactions})
 */
@Schema(name = "TransactionDetailDto",
        description = "Single-transaction detail response. Replaces the "
                + "3270 View Transaction screen rendered by COBOL program "
                + "COTRN01C / BMS mapset COTRN01 (map COTRN1A). Carries "
                + "every field of the 350-byte TRAN-RECORD layout defined "
                + "in CVTRA05Y.cpy, projected from the TRANSACT VSAM KSDS "
                + "(now the transaction table on RDS PostgreSQL).")
public record TransactionDetailDto(

        @Schema(description = "Transaction identifier. Maps to COBOL "
                + "TRAN-ID PIC X(16) in CVTRA05Y.cpy. Primary key of the "
                + "TRANSACT VSAM KSDS (now the transaction table on RDS "
                + "PostgreSQL). Used as the path variable on "
                + "GET /api/transactions/{id}.",
                example = "T000000000000001",
                maxLength = 16)
        @JsonProperty("transactionId")
        String transactionId,

        @Schema(description = "Transaction type code. Maps to COBOL "
                + "TRAN-TYPE-CD PIC X(02) in CVTRA05Y.cpy. Joinable to the "
                + "TransactionType reference table (primary key tran_type, "
                + "loaded by Flyway V013__seed_transaction_type.sql from "
                + "app/data/ASCII/trantype.txt).",
                example = "01",
                maxLength = 2)
        @JsonProperty("transactionType")
        String transactionType,

        @Schema(description = "Transaction category code. Maps to COBOL "
                + "TRAN-CAT-CD PIC 9(04) in CVTRA05Y.cpy. 4-digit numeric "
                + "joinable to the TransactionCategory reference table "
                + "(loaded by Flyway V014__seed_transaction_category.sql "
                + "from app/data/ASCII/trancatg.txt).",
                example = "5411",
                minimum = "0",
                maximum = "9999")
        @JsonProperty("transactionCategory")
        Integer transactionCategory,

        @Schema(description = "Transaction source channel. Maps to COBOL "
                + "TRAN-SOURCE PIC X(10) in CVTRA05Y.cpy. 10-character code "
                + "identifying the origin of the transaction (e.g. "
                + "\"ONLINE\", \"POS\", \"BATCH\").",
                example = "ONLINE",
                maxLength = 10)
        @JsonProperty("source")
        String source,

        @Schema(description = "Transaction description (full 100-character "
                + "value). Maps to COBOL TRAN-DESC PIC X(100) in "
                + "CVTRA05Y.cpy. The legacy BMS map COTRN1A renders only "
                + "the first 60 characters via TDESCO PIC X(60); the REST "
                + "response carries the full value so callers can render any "
                + "projection.",
                example = "GROCERY STORE PURCHASE",
                maxLength = 100)
        @JsonProperty("description")
        String description,

        /**
         * Transaction amount. Maps COBOL TRAN-AMT PIC S9(09)V99 from
         * CVTRA05Y.cpy to {@link BigDecimal} with {@code scale=2} per AAP
         * &sect;0.6.1 decimal-precision rule. Producer
         * ({@code TransactionDetailService}) must apply
         * {@code setScale(2, RoundingMode.HALF_EVEN)} on every value.
         */
        @Schema(description = "Transaction amount. Maps to COBOL TRAN-AMT "
                + "PIC S9(09)V99 in CVTRA05Y.cpy. Carried as BigDecimal "
                + "with scale=2 per AAP \u00a70.6.1 decimal-precision rule "
                + "(never float/double for monetary values). The producer "
                + "sets the scale to 2 with RoundingMode.HALF_EVEN before "
                + "populating this field. The legacy BMS map renders the "
                + "value through the formatted working-storage variable "
                + "WS-TRAN-AMT PIC +99999999.99 in COTRN01C.cbl; the REST "
                + "response carries the typed numeric value.",
                example = "123.45",
                type = "number",
                format = "double")
        @JsonProperty("amount")
        BigDecimal amount,

        @Schema(description = "Merchant identifier. Maps to COBOL "
                + "TRAN-MERCHANT-ID PIC 9(09) in CVTRA05Y.cpy. 9-digit "
                + "numeric identifier of the acquiring merchant; not "
                + "PCI-DSS-sensitive.",
                example = "100000001",
                minimum = "0",
                maximum = "999999999")
        @JsonProperty("merchantId")
        Long merchantId,

        @Schema(description = "Merchant name (full 50-character value). "
                + "Maps to COBOL TRAN-MERCHANT-NAME PIC X(50) in "
                + "CVTRA05Y.cpy. The legacy BMS map COTRN1A renders only "
                + "the first 30 characters via MNAMEO PIC X(30); the REST "
                + "response carries the full value.",
                example = "ACME GROCERY STORE",
                maxLength = 50)
        @JsonProperty("merchantName")
        String merchantName,

        @Schema(description = "Merchant city (full 50-character value). "
                + "Maps to COBOL TRAN-MERCHANT-CITY PIC X(50) in "
                + "CVTRA05Y.cpy. The legacy BMS map COTRN1A renders only "
                + "the first 25 characters via MCITYO PIC X(25); the REST "
                + "response carries the full value.",
                example = "SEATTLE",
                maxLength = 50)
        @JsonProperty("merchantCity")
        String merchantCity,

        @Schema(description = "Merchant ZIP code. Maps to COBOL "
                + "TRAN-MERCHANT-ZIP PIC X(10) in CVTRA05Y.cpy. Up to "
                + "10 characters (typical US 5- or 9-digit ZIP, or "
                + "international postal code).",
                example = "98109",
                maxLength = 10)
        @JsonProperty("merchantZip")
        String merchantZip,

        @Schema(description = "16-digit card number (PAN). Maps to COBOL "
                + "TRAN-CARD-NUM PIC X(16) in CVTRA05Y.cpy. Returned in "
                + "full to authenticated/authorized REST callers to match "
                + "the legacy 3270 View Transaction screen behavior "
                + "(authorization enforced by Spring Security on "
                + "TransactionController). Masked to last-4 in this DTO's "
                + "toString() override per PCI-DSS Requirement 3.3 to "
                + "prevent accidental logging of the full PAN.",
                example = "4111111111111111",
                maxLength = 16)
        @JsonProperty("cardNumber")
        String cardNumber,

        /**
         * Origination timestamp with microsecond precision. Maps COBOL
         * TRAN-ORIG-TS PIC X(26) from CVTRA05Y.cpy to
         * {@link LocalDateTime} per AAP &sect;0.6.3 / &sect;0.5.2 (native
         * java.time, no LE/CEEDAYS dependency). Serialized as an ISO-8601
         * string {@code yyyy-MM-dd'T'HH:mm:ss.SSSSSS} via
         * {@link JsonFormat}.
         */
        @JsonFormat(shape = JsonFormat.Shape.STRING,
                pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS")
        @Schema(description = "Origination timestamp with microsecond "
                + "precision. Maps to COBOL TRAN-ORIG-TS PIC X(26) in "
                + "CVTRA05Y.cpy. Replaces the LE-managed timestamp string "
                + "with native java.time.LocalDateTime per AAP \u00a70.6.3 / "
                + "\u00a70.5.2. Serialized as an ISO-8601 string with the "
                + "pattern yyyy-MM-dd'T'HH:mm:ss.SSSSSS to preserve the "
                + "microsecond precision of the COBOL X(26) format.",
                example = "2026-05-20T14:30:45.123456",
                format = "date-time",
                type = "string")
        @JsonProperty("originationTimestamp")
        LocalDateTime originationTimestamp,

        /**
         * Processing timestamp with microsecond precision. Maps COBOL
         * TRAN-PROC-TS PIC X(26) from CVTRA05Y.cpy to
         * {@link LocalDateTime} per AAP &sect;0.6.3 / &sect;0.5.2.
         * Serialized as an ISO-8601 string
         * {@code yyyy-MM-dd'T'HH:mm:ss.SSSSSS} via {@link JsonFormat}.
         */
        @JsonFormat(shape = JsonFormat.Shape.STRING,
                pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS")
        @Schema(description = "Processing timestamp with microsecond "
                + "precision. Maps to COBOL TRAN-PROC-TS PIC X(26) in "
                + "CVTRA05Y.cpy. Replaces the LE-managed timestamp string "
                + "with native java.time.LocalDateTime per AAP \u00a70.6.3 / "
                + "\u00a70.5.2. Serialized as an ISO-8601 string with the "
                + "pattern yyyy-MM-dd'T'HH:mm:ss.SSSSSS to preserve the "
                + "microsecond precision of the COBOL X(26) format.",
                example = "2026-05-20T14:30:46.789012",
                format = "date-time",
                type = "string")
        @JsonProperty("processingTimestamp")
        LocalDateTime processingTimestamp
) {

    /**
     * Returns a PCI-DSS-safe string representation of this transaction
     * detail.
     *
     * <p>The default {@link Record#toString()} generated by the compiler
     * would emit every component including the <b>full 16-digit PAN</b>
     * stored in {@link #cardNumber}.  This override deliberately masks
     * the PAN to its last four digits per PCI-DSS Requirement 3.3 and the
     * industry-standard PAN-masking convention
     * ({@code ************NNNN}, twelve asterisks followed by the last
     * four PAN digits), ensuring that any accidental logging of this DTO
     * (Spring Boot exception logs, debug logs, ad-hoc
     * {@code log.info(transaction)} statements, request/response tracing
     * via {@code spring.web.logging}) never emits a full PAN to CloudWatch
     * Logs.  This is defense in depth on top of the CloudWatch log
     * filters and Amazon Macie S3 scanning described in AAP &sect;0.6.6.
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
     *       regardless of total length (16 is the canonical CardDemo PAN
     *       length per {@code TRAN-CARD-NUM PIC X(16)}; the mask length
     *       is fixed at 12 to match the industry-standard
     *       {@code ************NNNN} format and not the input length).</li>
     * </ul>
     *
     * <p>This implementation emits a deliberately narrow set of fields
     * intended for a human investigator's log line &mdash; transaction
     * id, type, category, amount, masked PAN, merchant name, and the
     * processing timestamp.  Merchant city, ZIP, source, origination
     * timestamp, and description are omitted from this projection purely
     * to keep log volume manageable; the complete record is always
     * available by re-querying {@code GET /api/transactions/{id}} or by
     * looking up the transaction in OpenSearch by {@code transactionId}.
     *
     * @return a PCI-safe string of the form
     *         {@code TransactionDetailDto[transactionId=...,
     *         transactionType=..., transactionCategory=..., amount=...,
     *         merchantName=..., cardNumber=************NNNN,
     *         processingTimestamp=...]}
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
        return "TransactionDetailDto[transactionId=" + transactionId
                + ", transactionType=" + transactionType
                + ", transactionCategory=" + transactionCategory
                + ", amount=" + amount
                + ", merchantName=" + merchantName
                + ", cardNumber=" + maskedPan
                + ", processingTimestamp=" + processingTimestamp
                + "]";
    }
}
