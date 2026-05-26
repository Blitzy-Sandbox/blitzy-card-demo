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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.OptBoolean;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Transaction creation (add) request DTO.
 *
 * <p>Replaces the 3270 transaction-add screen rendered by the COBOL/CICS
 * program {@code COTRN02C.cbl} (CICS transaction id {@code CT02}).  In the
 * source, {@code COTRN02C} accepts operator-keyed input through the BMS map
 * {@code COTRN2A} (in {@code app/bms/COTRN02.bms}), validates each field,
 * performs a "browse-to-end" pattern via {@code EXEC CICS STARTBR}/{@code
 * READPREV} on the {@code TRANSACT} VSAM KSDS to determine the next
 * transaction ID (the highest existing key plus one), then issues
 * {@code EXEC CICS WRITE} on {@code TRANSACT} and performs the
 * account-balance and category-balance updates (the latter handled by the
 * CICS/online-batch bridge in the original mainframe pipeline).
 *
 * <p>In the Java target, {@code TransactionAddService.add(...)} executes the
 * following sequence (see AAP &sect;0.4.1):
 * <ol>
 *   <li>Validates input via Bean Validation (annotations on this record) +
 *       {@code ValidationLookupService} (NANPA area-code / US state-territory
 *       / state-ZIP combination checks where applicable &mdash; port of
 *       {@code app/cpy/CSLKPCDY.cpy}).</li>
 *   <li>Resolves the missing identifier (account &harr; card) via
 *       {@code CardCrossReferenceRepository.findById(cardNumber)} or
 *       {@code CardCrossReferenceRepository.findByXrefAcctId(accountId)}
 *       (replaces the {@code CXACAIX} alternate index over the
 *       {@code CARDXREF} VSAM cluster &mdash; see AAP &sect;0.6.2).</li>
 *   <li>Generates a transaction identifier via a JPA
 *       {@code @SequenceGenerator} (replaces the COBOL "browse-to-end"
 *       pattern, which carries an inherent race condition under concurrent
 *       online activity &mdash; the sequence eliminates the race entirely).</li>
 *   <li>Persists the {@code Transaction} entity and updates the
 *       {@code Account} (and {@code TransactionCategoryBalance}) inside a
 *       single {@code @Transactional(rollbackFor = Exception.class)}
 *       boundary &mdash; replaces the implicit CICS {@code SYNCPOINT} that
 *       wraps the original conversational add flow (AAP &sect;0.6.2).</li>
 *   <li>Publishes a {@code transaction.posted} Kafka event to MSK,
 *       partitioned by the resolved 11-digit account identifier so that
 *       all events for the same account land on the same partition and
 *       remain strictly ordered for any single consumer (AAP &sect;0.6.5).</li>
 * </ol>
 *
 * <p><b>Bean Validation note &mdash; cross-field "accountId or cardNumber"
 * rule (AAP &sect;0.7.2 functional-preservation):</b> The BMS screen requires
 * the operator to enter <i>either</i> the 11-digit account number
 * ({@code ACTIDIN}) <i>or</i> the 16-digit card number ({@code CARDNIN}).
 * Both fields are therefore marked optional at the field level (no
 * {@code @NotBlank}), and the "at least one must be present" rule plus the
 * "if both are present, the card must cross-reference to the supplied
 * account" rule are enforced in the SERVICE layer (which has access to the
 * cross-reference repository).  Field-level Bean Validation runs first
 * (triggered by the {@code @Valid} annotation on the controller method
 * parameter); service-level cross-field validation runs second.  On
 * mismatch the service raises a {@code ValidationException} from
 * {@code com.awsm2.carddemo.exception}.
 *
 * <p><b>BMS / record-layout field mapping (AAP &sect;0.4.1 traceability):</b>
 * <pre>{@code
 *   BMS field   Length   CVTRA05Y field                Java component
 *   ---------   ------   ---------------------------   --------------------
 *   ACTIDIN     X(11)    (resolved via CARDXREF)       accountId
 *   CARDNIN     X(16)    TRAN-CARD-NUM        X(16)    cardNumber
 *   TTYPCD      X(02)    TRAN-TYPE-CD         X(02)    transactionType
 *   TCATCD      X(04)    TRAN-CAT-CD          9(04)    transactionCategory
 *   TRNSRC      X(10)    TRAN-SOURCE          X(10)    source
 *   TDESC       X(60)    TRAN-DESC            X(100)*  description
 *   TRNAMT      X(12)    TRAN-AMT          S9(09)V99   amount
 *   TORIGDT     X(10)    TRAN-ORIG-TS         X(26)**  originationTimestamp
 *   MID         X(09)    TRAN-MERCHANT-ID     9(09)    merchantId
 *   MNAME       X(30)*** TRAN-MERCHANT-NAME   X(50)*** merchantName
 *   MCITY       X(25)*** TRAN-MERCHANT-CITY   X(50)*** merchantCity
 *   MZIP        X(10)    TRAN-MERCHANT-ZIP    X(10)    merchantZip
 *   CONFIRM     X(01)    (transient &mdash; not on record) confirm
 * }</pre>
 *
 * <p>(*) The BMS screen surface limits the description to 60 characters
 * because the BMS field {@code TDESC} is 60 bytes wide, but the
 * {@code TRAN-DESC} field on the {@code TRANSACT} record (declared in
 * {@code app/cpy/CVTRA05Y.cpy}) is 100 bytes.  Per AAP minimal-change
 * discipline and to avoid silently truncating data submitted by API clients
 * (non-3270 callers), this DTO permits up to 100 characters &mdash; matching
 * the underlying storage capacity.
 *
 * <p>(**) The COBOL screen accepts a 10-character {@code YYYY-MM-DD} date
 * for the origination timestamp; the underlying record stores a 26-byte
 * timestamp string.  In the Java target this becomes a native
 * {@link LocalDateTime} (AAP &sect;0.6.3 &mdash; no {@code CEEDAYS}
 * dependency), serialized via Jackson's ISO-8601 pattern.  The service
 * captures the processing timestamp (TRAN-PROC-TS) internally; callers
 * supply only the origination timestamp.
 *
 * <p>(***) The BMS display lengths for {@code MNAME} (30) and {@code MCITY}
 * (25) are narrower than the underlying record-layout capacities of 50 each.
 * This DTO follows the record-layout capacity (50/50) to avoid silently
 * truncating data submitted by API clients; the service may still apply
 * lookup-table validation (state / ZIP combinations) via
 * {@code ValidationLookupService}.
 *
 * <p><b>COBOL Provenance (AAP &sect;0.7.3):</b>
 * <ul>
 *   <li>BMS Mapset: {@code app/bms/COTRN02.bms} (mapset {@code COTRN02},
 *       map {@code COTRN2A})</li>
 *   <li>Symbolic Map: {@code app/cpy-bms/COTRN02.CPY} (record
 *       {@code COTRN2AI})</li>
 *   <li>Program: {@code app/cbl/COTRN02C.cbl}</li>
 *   <li>Record Layout: {@code app/cpy/CVTRA05Y.cpy} (TRAN-RECORD, 350
 *       bytes)</li>
 *   <li>Consumed by: {@code TransactionAddService#add(TransactionAddDto)}
 *       (one-service-per-COBOL-program mapping per AAP &sect;0.3.3)</li>
 *   <li>Controller endpoint: {@code POST /api/transactions} (see
 *       {@code TransactionController})</li>
 * </ul>
 *
 * <p><b>PCI-DSS handling (AAP &sect;0.7.1, &sect;0.6.6):</b>
 * <ul>
 *   <li>The {@link #cardNumber()} field is a full 16-digit Primary Account
 *       Number (PAN) on the wire (the BMS screen also accepts the full PAN
 *       on input).  The default {@link Record#toString()} generated for a
 *       record exposes every component verbatim; that would leak the PAN
 *       into application logs if a controller, service, or validator
 *       inadvertently logs the request body.</li>
 *   <li>To prevent any accidental PAN leakage into CloudWatch Logs and
 *       downstream OpenSearch indexes, this record overrides
 *       {@link #toString()} to mask the PAN with twelve asterisks followed
 *       by the last four digits ({@code "************nnnn"}) per PCI-DSS
 *       Requirement 3.4 (truncation/masking of the full PAN in audit logs
 *       and on display).</li>
 *   <li>The {@link #amount()} field is typed as {@link BigDecimal} per AAP
 *       &sect;0.6.1 &mdash; never {@code double} or {@code float} &mdash;
 *       to preserve the exact COBOL fixed-point decimal-arithmetic
 *       semantics of {@code TRAN-AMT PIC S9(09)V99}.  The service layer
 *       performs all arithmetic with
 *       {@link java.math.RoundingMode#HALF_EVEN} (banker's rounding)
 *       matching the COBOL {@code PIC 9} default rounding behavior.</li>
 * </ul>
 *
 * <p>The auto-generated {@link #equals(Object)} and {@link #hashCode()}
 * methods from the Java record contract are retained.  Equality compares
 * every component (including the unmasked PAN, which is held in memory
 * during request processing); only the textual {@link #toString()}
 * representation is masked.
 *
 * <p>This DTO contains no business logic, no AWS-SDK references, and no
 * Lombok &mdash; it is a pure validated request envelope per AAP
 * &sect;0.3.3 (Layered Architecture).
 *
 * @param accountId            11-digit account identifier (optional if
 *                             {@code cardNumber} is supplied); maps to BMS
 *                             {@code ACTIDIN PIC X(11)} and resolves through
 *                             the {@code CARDXREF} cross-reference index
 * @param cardNumber           16-digit card number / PAN (optional if
 *                             {@code accountId} is supplied); maps to BMS
 *                             {@code CARDNIN PIC X(11)} and to
 *                             {@code TRAN-CARD-NUM PIC X(16)} on the record
 * @param transactionType      2-digit transaction-type code; maps to BMS
 *                             {@code TTYPCD PIC X(02)} and to
 *                             {@code TRAN-TYPE-CD PIC X(02)}; foreign key
 *                             into the {@code transaction_type} table
 *                             (port of {@code app/cpy/CVTRA03Y.cpy})
 * @param transactionCategory  4-digit transaction-category code; maps to
 *                             BMS {@code TCATCD PIC X(04)} and to
 *                             {@code TRAN-CAT-CD PIC 9(04)}; foreign key
 *                             into the {@code transaction_category} table
 *                             (port of {@code app/cpy/CVTRA04Y.cpy})
 * @param source               transaction source string; maps to BMS
 *                             {@code TRNSRC PIC X(10)} and to
 *                             {@code TRAN-SOURCE PIC X(10)}
 * @param description          free-text transaction description; maps to
 *                             BMS {@code TDESC PIC X(60)} and to
 *                             {@code TRAN-DESC PIC X(100)} on the record
 *                             (the wider record-layout capacity is honored
 *                             here for non-3270 callers)
 * @param amount               signed monetary amount; maps to BMS
 *                             {@code TRNAMT PIC X(12)} and to
 *                             {@code TRAN-AMT PIC S9(09)V99}; typed as
 *                             {@link BigDecimal} per AAP &sect;0.6.1
 * @param originationTimestamp origination timestamp supplied by the
 *                             operator/client; maps to BMS
 *                             {@code TORIGDT PIC X(10)} on the screen and
 *                             to {@code TRAN-ORIG-TS PIC X(26)} on the
 *                             record (the service captures the processing
 *                             timestamp itself; the operator/client supplies
 *                             only the origination timestamp)
 * @param merchantId           9-digit merchant identifier; maps to BMS
 *                             {@code MID PIC X(09)} and to
 *                             {@code TRAN-MERCHANT-ID PIC 9(09)}
 * @param merchantName         merchant name; maps to BMS
 *                             {@code MNAME PIC X(30)} and to
 *                             {@code TRAN-MERCHANT-NAME PIC X(50)} on the
 *                             record (record-layout capacity honored)
 * @param merchantCity         merchant city; maps to BMS
 *                             {@code MCITY PIC X(25)} and to
 *                             {@code TRAN-MERCHANT-CITY PIC X(50)} on the
 *                             record (record-layout capacity honored)
 * @param merchantZip          merchant ZIP code; maps to BMS
 *                             {@code MZIP PIC X(10)} and to
 *                             {@code TRAN-MERCHANT-ZIP PIC X(10)}; the
 *                             service may further validate state/ZIP
 *                             combinations via {@code ValidationLookupService}
 * @param confirm              transient confirmation flag ('Y' to post, 'N'
 *                             to cancel); maps to BMS
 *                             {@code CONFIRM PIC X(01)}; not persisted on
 *                             the {@code TRAN-RECORD}, used by the service
 *                             to gate the final write
 *
 * @see <a href=
 *      "https://github.com/aws-samples/aws-mainframe-modernization-carddemo">
 *      AWS CardDemo (source COBOL)</a>
 */
@Schema(name = "TransactionAddDto",
        description = "Transaction creation request payload. Mirrors the input fields of "
                + "the legacy COTRN02 3270 screen (BMS map COTRN2A) and the underlying "
                + "TRAN-RECORD record layout (CVTRA05Y.cpy, 350 bytes). "
                + "Either accountId or cardNumber must be present; if both are present, "
                + "the cardNumber's cross-reference must resolve to the supplied accountId. "
                + "PAN values are masked in toString() per PCI-DSS Requirement 3.4.")
public record TransactionAddDto(

        // COBOL: COTRN02C line 199 "Account ID must be Numeric..." (verbatim,
        // Issue CP4-#2). The COBOL test is `IS NUMERIC` which fires when a
        // value is present but non-numeric — translated to the @Pattern
        // numeric check below. The cross-field "must be entered" rule
        // (COBOL line 226) is enforced server-side in the service validate().
        @Pattern(regexp = "^\\d{11}$",
                message = "Account ID must be Numeric...")
        @Schema(description = "11-digit account identifier (optional if cardNumber is "
                        + "supplied; the service resolves the missing identifier via "
                        + "the CARDXREF cross-reference). Maps to BMS ACTIDIN PIC X(11).",
                example = "10000000001",
                maxLength = 11)
        @JsonProperty("accountId")
        String accountId,

        // COBOL: COTRN02C line 213 "Card Number must be Numeric..." (verbatim,
        // Issue CP4-#2).
        @Pattern(regexp = "^\\d{16}$",
                message = "Card Number must be Numeric...")
        @Schema(description = "16-digit card number / Primary Account Number (PAN), "
                        + "optional if accountId is supplied. Maps to BMS CARDNIN PIC X(16) "
                        + "and to TRAN-CARD-NUM PIC X(16) on the record. The toString() "
                        + "implementation on this DTO masks this value per PCI-DSS "
                        + "Requirement 3.4.",
                example = "4111111111111111",
                maxLength = 16)
        @JsonProperty("cardNumber")
        String cardNumber,

        // COBOL: COTRN02C line 254 "Type CD can NOT be empty..." and line 325
        // "Type CD must be Numeric..." (verbatim, Issue CP4-#2).
        @NotBlank(message = "Type CD can NOT be empty...")
        @Pattern(regexp = "^\\d{2}$",
                message = "Type CD must be Numeric...")
        @Schema(description = "Transaction type code (2 digits; foreign key into the "
                        + "transaction_type table). Maps to BMS TTYPCD PIC X(02) and to "
                        + "TRAN-TYPE-CD PIC X(02) on the record.",
                example = "01",
                maxLength = 2,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("transactionType")
        String transactionType,

        // COBOL: COTRN02C line 260 "Category CD can NOT be empty..." (verbatim,
        // Issue CP4-#2). The line 331 "Category CD must be Numeric..." check
        // is structurally enforced by the Integer Java type — JSON parsing
        // rejects non-numeric values at Jackson layer before validation.
        @NotNull(message = "Category CD can NOT be empty...")
        @Schema(description = "Transaction category code (4 digits; foreign key into the "
                        + "transaction_category table). Maps to BMS TCATCD PIC X(04) and to "
                        + "TRAN-CAT-CD PIC 9(04) on the record.",
                example = "5411",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("transactionCategory")
        Integer transactionCategory,

        // COBOL: COTRN02C line 266 "Source can NOT be empty..." (verbatim,
        // Issue CP4-#2). The 10-char cap is enforced by @Size for the JSON
        // contract; no separate COBOL message exists for over-length.
        @NotBlank(message = "Source can NOT be empty...")
        @Size(max = 10,
                message = "Source can NOT be empty...")
        @Schema(description = "Free-text transaction source (e.g., \"ONLINE\", \"BATCH\", "
                        + "\"MOBILE\"). Maps to BMS TRNSRC PIC X(10) and to "
                        + "TRAN-SOURCE PIC X(10) on the record.",
                example = "ONLINE",
                maxLength = 10,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("source")
        String source,

        // COBOL: COTRN02C line 272 "Description can NOT be empty..." (verbatim,
        // Issue CP4-#2).
        @NotBlank(message = "Description can NOT be empty...")
        @Size(max = 100,
                message = "Description can NOT be empty...")
        @Schema(description = "Free-text transaction description. Maps to BMS "
                        + "TDESC PIC X(60) on the screen, but the underlying "
                        + "TRAN-DESC PIC X(100) record field permits up to 100 "
                        + "characters; the wider capacity is honored here for "
                        + "non-3270 callers.",
                example = "GROCERY STORE PURCHASE",
                maxLength = 100,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("description")
        String description,

        // COBOL: COTRN02C line 278 "Amount can NOT be empty..." and line 344
        // "Amount should be in format -99999999.99" (verbatim, Issue CP4-#2).
        @NotNull(message = "Amount can NOT be empty...")
        @Digits(integer = 9,
                fraction = 2,
                message = "Amount should be in format -99999999.99")
        @DecimalMin(value = "-999999999.99",
                message = "Amount should be in format -99999999.99")
        @Schema(description = "Signed monetary amount. Maps to BMS TRNAMT PIC X(12) "
                        + "(displayed as -99999999.99 with sign and decimal point) and to "
                        + "TRAN-AMT PIC S9(09)V99 on the record. Positive values represent "
                        + "debits (purchases/withdrawals); negative values represent credits "
                        + "(payments/refunds). Typed as BigDecimal per AAP \u00a70.6.1 -- "
                        + "never float/double -- to preserve exact COBOL decimal-arithmetic "
                        + "semantics; the service performs all arithmetic with "
                        + "RoundingMode.HALF_EVEN (banker's rounding).",
                example = "123.45",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("amount")
        BigDecimal amount,

        // COBOL: COTRN02C line 284 "Orig Date can NOT be empty..." (verbatim,
        // Issue CP4-#2). The "should be in format YYYY-MM-DD" (line 356) and
        // "Not a valid date" (line 401) error strings are emitted by the
        // service-level validate() method after the @NotNull check fires.
        @NotNull(message = "Orig Date can NOT be empty...")
        // QA Final-CP6 C1 (date coercion fix): lenient = OptBoolean.FALSE
        // forces Jackson's contextual LocalDateTimeDeserializer to resolve
        // the supplied timestamp under ResolverStyle.STRICT. The previous
        // (LENIENT) resolver style silently coerced impossible day-of-
        // month values (e.g. "2024-02-30T10:00:00" -> 2024-02-29T10:00:00),
        // regressing the COBOL CSUTLDTC calendar-validity contract that
        // rejects such dates. With lenient=FALSE, Jackson throws
        // InvalidFormatException -> HttpMessageNotReadableException ->
        // HTTP 400 from GlobalExceptionHandler.handleMessageNotReadable.
        @JsonFormat(shape = JsonFormat.Shape.STRING,
                pattern = "uuuu-MM-dd'T'HH:mm:ss",
                lenient = OptBoolean.FALSE)
        @Schema(description = "Origination timestamp supplied by the operator/client "
                        + "(ISO-8601, yyyy-MM-dd'T'HH:mm:ss). Maps to BMS TORIGDT PIC X(10) "
                        + "(date portion only, displayed as YYYY-MM-DD) and to "
                        + "TRAN-ORIG-TS PIC X(26) on the record. Java native "
                        + "LocalDateTime replaces the LE CEEDAYS dependency per AAP "
                        + "\u00a70.6.3.",
                example = "2026-05-20T14:30:45",
                format = "date-time",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("originationTimestamp")
        LocalDateTime originationTimestamp,

        /**
         * Processing timestamp supplied by the operator/client. Maps to
         * {@code TPROCDT PIC X(10)} on the BMS map (line 200 of
         * {@code app/bms/COTRN02.bms}) and to {@code TPROCDTI PIC X(10)}
         * in the symbolic-map copybook ({@code app/cpy-bms/COTRN02.CPY}),
         * which the COBOL {@code COTRN02C} program validates as
         * {@code YYYY-MM-DD} (lines 288-298 emptiness check, 369-377 format
         * check, 425-432 calendar-validity check via {@code CSUTLDTC}) and
         * then moves directly to {@code TRAN-PROC-TS} on the
         * {@code TRAN-RECORD} (line 465).  In the Java target the field is
         * carried as {@link LocalDateTime} so a single ISO-8601 timestamp
         * can capture both the calendar date and the time-of-day portion
         * persisted to {@code TRAN-PROC-TS PIC X(26)}.  This preserves the
         * COBOL contract that the processing date is operator-supplied
         * (required) and not service-generated.  Calendar-validity checks
         * are delegated to {@code DateValidationService} (port of
         * {@code CSUTLDPY.cpy} and {@code CSUTLDTC.cbl}) per AAP
         * &sect;0.6.3.
         */
        // COBOL: COTRN02C line 290 "Proc Date can NOT be empty..." (verbatim,
        // Issue CP4-#2). The "should be in format YYYY-MM-DD" (line 369) and
        // "Not a valid date" (line 421) error strings are emitted by the
        // service-level validate() method after the @NotNull check fires.
        @NotNull(message = "Proc Date can NOT be empty...")
        // QA Final-CP6 C1 (date coercion fix): see originationTimestamp.
        @JsonFormat(shape = JsonFormat.Shape.STRING,
                pattern = "uuuu-MM-dd'T'HH:mm:ss",
                lenient = OptBoolean.FALSE)
        @Schema(description = "Processing timestamp supplied by the operator/client "
                        + "(ISO-8601, yyyy-MM-dd'T'HH:mm:ss). Maps to BMS TPROCDT "
                        + "PIC X(10) (line 200 of app/bms/COTRN02.bms; date portion "
                        + "only, displayed as YYYY-MM-DD) and to TRAN-PROC-TS "
                        + "PIC X(26) on the TRAN-RECORD. The COBOL COTRN02C "
                        + "program requires this field (lines 288-298 emptiness "
                        + "check, 369-377 format check, 425-432 calendar-validity "
                        + "check) and moves it directly to TRAN-PROC-TS at line "
                        + "465. Calendar-validity validation is delegated to "
                        + "DateValidationService per AAP \u00a70.6.3.",
                example = "2026-05-20T14:30:45",
                format = "date-time",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("processingTimestamp")
        LocalDateTime processingTimestamp,

        // COBOL: COTRN02C line 296 "Merchant ID can NOT be empty..." and line
        // 432 "Merchant ID must be Numeric..." (verbatim, Issue CP4-#2). The
        // numeric check is structurally enforced by Long Java type — Jackson
        // rejects non-numeric values before validation fires.
        @NotNull(message = "Merchant ID can NOT be empty...")
        @Schema(description = "9-digit merchant identifier. Maps to BMS MID PIC X(09) and "
                        + "to TRAN-MERCHANT-ID PIC 9(09) on the record.",
                example = "100000001",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("merchantId")
        Long merchantId,

        // COBOL: COTRN02C line 302 "Merchant Name can NOT be empty..."
        // (verbatim, Issue CP4-#2).
        @NotBlank(message = "Merchant Name can NOT be empty...")
        @Size(max = 50,
                message = "Merchant Name can NOT be empty...")
        @Schema(description = "Merchant name. Maps to BMS MNAME PIC X(30) on the screen, "
                        + "but the underlying TRAN-MERCHANT-NAME PIC X(50) record field "
                        + "permits up to 50 characters; the wider capacity is honored here "
                        + "for non-3270 callers.",
                example = "ACME GROCERY",
                maxLength = 50,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("merchantName")
        String merchantName,

        // COBOL: COTRN02C line 308 "Merchant City can NOT be empty..."
        // (verbatim, Issue CP4-#2).
        @NotBlank(message = "Merchant City can NOT be empty...")
        @Size(max = 50,
                message = "Merchant City can NOT be empty...")
        @Schema(description = "Merchant city. Maps to BMS MCITY PIC X(25) on the screen, "
                        + "but the underlying TRAN-MERCHANT-CITY PIC X(50) record field "
                        + "permits up to 50 characters; the wider capacity is honored here "
                        + "for non-3270 callers. The service may further validate state/ZIP "
                        + "combinations via ValidationLookupService (port of CSLKPCDY.cpy).",
                example = "SEATTLE",
                maxLength = 50,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("merchantCity")
        String merchantCity,

        // COBOL: COTRN02C line 314 "Merchant Zip can NOT be empty..." (verbatim,
        // Issue CP4-#2).
        @NotBlank(message = "Merchant Zip can NOT be empty...")
        @Size(max = 10,
                message = "Merchant Zip can NOT be empty...")
        @Schema(description = "Merchant ZIP code (US 5 or 9 digit; either '98101' or "
                        + "'98101-1234' form is accepted). Maps to BMS MZIP PIC X(10) "
                        + "and to TRAN-MERCHANT-ZIP PIC X(10) on the record.",
                example = "98101",
                maxLength = 10,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("merchantZip")
        String merchantZip,

        // COBOL: COTRN02C line 184 "Invalid value. Valid values are (Y/N)..."
        // (verbatim, Issue CP4-#2). The blank/empty case (line 178 "Confirm
        // to add this transaction...") is handled in the service-level
        // validate() since it is conceptually a prompt, not a rejection.
        @Pattern(regexp = "^[YN]$",
                message = "Invalid value. Valid values are (Y/N)...")
        @Schema(description = "Confirmation flag: 'Y' to post the transaction, 'N' to "
                        + "cancel without posting. Maps to BMS CONFIRM PIC X(01). This "
                        + "field is transient and is not persisted on the TRAN-RECORD; the "
                        + "service uses it to gate the final write and to emit a typed "
                        + "validation response when the operator cancels.",
                example = "Y",
                maxLength = 1,
                allowableValues = {"Y", "N"})
        @JsonProperty("confirm")
        String confirm,

        /**
         * Server-generated transaction identifier returned on the
         * {@code POST /api/transactions} response so the caller can
         * reference, audit, and follow up on the created transaction
         * without re-querying.
         *
         * <p>QA Final-CP6 Finding M2 (MAJOR): the generated MAX-TRAN-ID+1
         * value was previously persisted but never surfaced to the
         * caller. The {@code BillPaymentDto.transactionId} field
         * (returned by {@code POST /api/billing/pay}) already used this
         * pattern; this brings the {@code POST /api/transactions}
         * endpoint into parity.
         *
         * <p>The field is request-side optional (defaults to {@code null})
         * and response-side populated. The service ignores any
         * client-supplied {@code transactionId} on inbound requests and
         * uses its own MAX(TRAN-ID)+1 generator
         * ({@link com.awsm2.carddemo.service.TransactionAddService});
         * see the service implementation for the authoritative
         * server-side override behavior. The OpenAPI {@code @Schema}
         * still documents the field as response-only via
         * {@code accessMode = READ_ONLY} so API consumers reading the
         * generated OpenAPI/Swagger contract continue to see it as a
         * read-only response field.
         *
         * <p><b>QA Final-CP7 Finding F-CRITICAL-01 (CRITICAL) fix:</b>
         * The previous declaration used
         * {@code @JsonProperty(value = "transactionId",
         * access = JsonProperty.Access.READ_ONLY)}, which suppressed the
         * property name for the deserialization-path property-based
         * Creator. Because the 15-arg compact canonical constructor is
         * annotated {@link JsonCreator}, Jackson 2.x must build a
         * property-based Creator that names every constructor parameter
         * &mdash; including this one. With {@code READ_ONLY} suppressing
         * the {@code transactionId} parameter name, the factory walk in
         * {@code BasicDeserializerFactory._validateNamedPropertyParameter}
         * raised {@code InvalidDefinitionException}: <i>"Argument #14 of
         * constructor has no property name (and is not Injectable): can
         * not use as property-based Creator"</i>. The
         * {@code transaction.posted} Kafka topic consumer
         * ({@code KafkaEventConsumer#onTransactionPosted}) therefore
         * routed 100% of inbound records to {@code transaction.posted.DLT}
         * via {@code DeadLetterPublishingRecoverer}, silently breaking
         * the PCI-DSS audit trail for transaction events (AAP
         * &sect;0.6.6).
         *
         * <p>Removing {@code access = JsonProperty.Access.READ_ONLY}
         * restores Jackson's ability to bind {@code transactionId} from
         * inbound JSON, preserving the Kafka consumer's
         * deserialization path while leaving the
         * server-ignores-client-supplied-value contract intact at the
         * service layer (the only code that reads {@code transactionId}
         * on the request path is the response builder, never the
         * persistence layer). The {@code @Schema(accessMode =
         * Schema.AccessMode.READ_ONLY)} annotation continues to surface
         * the response-only intent in the OpenAPI contract.
         *
         * <p>COBOL: maps to {@code TRAN-ID PIC X(16)} on
         * {@code CVTRA05Y.cpy} line 7. The COBOL paragraph
         * {@code WRITE-TRANSACT-FILE} computed
         * {@code TRAN-ID = MAX(TRAN-ID-IN-FILE) + 1} and never returned
         * the value to the operator (the BMS COTRN2A map did not display
         * it). The Java target preserves the same generator semantics
         * but, per modern REST conventions, returns the value to the
         * caller so the operator can correlate the response with
         * downstream audit / OpenSearch / CloudTrail entries.
         */
        @Schema(description = "Server-generated 16-digit transaction "
                        + "identifier (TRAN-ID PIC X(16)). Populated on "
                        + "responses to POST /api/transactions; ignored "
                        + "on requests &mdash; the service computes the "
                        + "value as MAX(TRAN-ID)+1 against the "
                        + "transactions table.",
                example = "0000000000000023",
                maxLength = 16,
                accessMode = Schema.AccessMode.READ_ONLY)
        // QA Final-CP7 F-CRITICAL-01: access = JsonProperty.Access.READ_ONLY
        // was removed so Jackson's record-property-based Creator can resolve
        // a name for this constructor parameter. Without a usable property
        // name on every parameter, Jackson 2.x refuses to build the
        // property-based Creator and the consumer pipeline routes every
        // transaction.posted record to the DLT. The OpenAPI @Schema above
        // still surfaces the response-only contract to API consumers.
        @JsonProperty("transactionId")
        String transactionId
) {

    /**
     * Compact canonical constructor annotated with
     * {@link JsonCreator} so Jackson unambiguously picks the
     * 15-component record constructor when deserializing inbound
     * JSON (even though a sibling non-canonical 14-arg constructor
     * exists below). The compact form runs the record's implicit
     * field-assignment after this body returns &mdash; we do not
     * perform any validation here (Bean Validation is applied via
     * the per-field {@code @NotNull}/{@code @Pattern}/{@code @Digits}
     * constraints when the controller invokes {@code @Valid}).
     *
     * <p>Without the explicit {@code @JsonCreator}, Jackson 2.x sees
     * two candidate constructors on this record (the 15-arg
     * canonical and the 14-arg backward-compatible delegate) and
     * fails with &ldquo;no Creators, like default constructor,
     * exist&rdquo;. Annotating the canonical form is the
     * minimal-change resolution prescribed by Jackson's record
     * support semantics introduced in Jackson 2.12.</p>
     *
     * <p>For this creator to function, every component declared in
     * the record signature above must expose a property name to
     * Jackson's deserialization path. The {@code transactionId}
     * component is annotated with {@code @JsonProperty("transactionId")}
     * (no {@code Access.READ_ONLY}) for exactly that reason &mdash;
     * see QA Final-CP7 Finding F-CRITICAL-01 (CRITICAL) for the
     * forensic trace of the previous regression that suppressed the
     * property name on that one parameter and routed 100% of
     * {@code transaction.posted} records to the DLT.</p>
     *
     * <p>QA Final-CP6 Finding M2 introduced this annotation; QA
     * Final-CP7 Finding F-CRITICAL-01 hardened the property-name
     * exposure on every parameter.</p>
     *
     * @param accountId            optional 11-digit account ID (BMS
     *                             {@code ACTIDIN}); paired with
     *                             {@code cardNumber} so callers may
     *                             supply either identifier
     * @param cardNumber           optional 16-digit card number /
     *                             PAN (BMS {@code CARDNIN}); masked in
     *                             {@link #toString()} per PCI-DSS 3.4
     * @param transactionType      2-digit transaction type code (BMS
     *                             {@code TTYPCD}); foreign key into
     *                             {@code transaction_type}
     * @param transactionCategory  4-digit transaction category code (BMS
     *                             {@code TCATCD}); foreign key into
     *                             {@code transaction_category}
     * @param source               2-character transaction source code
     *                             (BMS {@code TRNSRC}); free text
     *                             describing the origin channel
     * @param description          26-character free-text description
     *                             (BMS {@code TDESC}); preserved
     *                             verbatim on the transaction record
     * @param amount               transaction amount as a
     *                             {@link BigDecimal} (BMS
     *                             {@code TRNAMT}, COBOL
     *                             {@code PIC S9(09)V99}); positive for
     *                             debits, negative for credits per
     *                             COBOL convention
     * @param originationTimestamp the original transaction origination
     *                             timestamp (BMS {@code TORIGDT}); ISO-8601
     * @param processingTimestamp  the host processing timestamp (BMS
     *                             {@code TPROCDT}); ISO-8601
     * @param merchantId           merchant identifier (BMS
     *                             {@code MID}); 9-digit numeric
     * @param merchantName         merchant name (BMS
     *                             {@code MNAME}); preserved verbatim
     * @param merchantCity         merchant city (BMS
     *                             {@code MCITY})
     * @param merchantZip          merchant ZIP code (BMS
     *                             {@code MZIP})
     * @param confirm              {@code "Y"}/{@code "N"} confirmation
     *                             flag from the COTRN02 confirm prompt
     * @param transactionId        server-generated 16-digit transaction
     *                             identifier (response-only); ignored
     *                             on inbound JSON, computed by
     *                             {@code TransactionAddService} as
     *                             {@code MAX(TRAN-ID)+1}
     */
    @JsonCreator
    public TransactionAddDto {
        // No-op compact body: record-generated field-assignment is
        // applied automatically; per-field bean-validation
        // constraints fire during @Valid binding at the controller
        // layer.
    }

    /**
     * Backward-compatible 14-arg constructor for inbound request
     * deserialization &mdash; callers (tests, services) that do
     * not supply a {@code transactionId} hit this form and the
     * delegating call passes {@code null} for the trailing
     * component.
     *
     * <p>QA Final-CP6 Finding M2: added so the addition of the new
     * {@code transactionId} response-only field does not break the
     * ~46 existing call-sites that construct this DTO with 14
     * positional arguments (request shape).</p>
     *
     * @param accountId            optional 11-digit account ID; see the
     *                             canonical constructor for full semantics
     * @param cardNumber           optional 16-digit card number / PAN
     * @param transactionType      2-digit transaction type code
     * @param transactionCategory  4-digit transaction category code
     * @param source               2-character transaction source code
     * @param description          26-character description
     * @param amount               transaction amount (BigDecimal)
     * @param originationTimestamp original transaction origination timestamp
     * @param processingTimestamp  host processing timestamp
     * @param merchantId           merchant identifier
     * @param merchantName         merchant name
     * @param merchantCity         merchant city
     * @param merchantZip          merchant ZIP code
     * @param confirm              confirmation flag ({@code "Y"} /
     *                             {@code "N"})
     */
    public TransactionAddDto(
            String accountId,
            String cardNumber,
            String transactionType,
            Integer transactionCategory,
            String source,
            String description,
            BigDecimal amount,
            LocalDateTime originationTimestamp,
            LocalDateTime processingTimestamp,
            Long merchantId,
            String merchantName,
            String merchantCity,
            String merchantZip,
            String confirm) {
        this(accountId, cardNumber, transactionType, transactionCategory,
                source, description, amount, originationTimestamp,
                processingTimestamp, merchantId, merchantName,
                merchantCity, merchantZip, confirm,
                /* transactionId = */ null);
    }

    /**
     * Returns a PCI-DSS-safe string representation of this request.
     *
     * <p>The default {@link Record#toString()} generated by the Java record
     * contract would include the full 16-digit {@link #cardNumber()} (PAN)
     * verbatim.  PCI-DSS Requirement 3.4 mandates that the full PAN must
     * not be retained in audit logs and on display unless a defined
     * business need exists; this overridden method emits only the last four
     * digits of the PAN preceded by twelve asterisks
     * ({@code "************nnnn"}).
     *
     * <p>If the {@link #cardNumber()} is {@code null} or shorter than four
     * digits, the masked representation is rendered as {@code null} (no
     * placeholder asterisks) to avoid implying that a masked PAN is
     * present.
     *
     * <p>Every other component is rendered as-is &mdash; none of the other
     * fields carry sensitive cardholder data.
     *
     * <p><b>NOTE on record-generated {@code equals()}/{@code hashCode()}
     * (accepted risk per AAP &sect;0.6.6).</b>  Java records auto-generate
     * {@link Object#equals(Object)} and {@link Object#hashCode()} over
     * every component &mdash; including the unmasked PAN
     * {@link #cardNumber()} &mdash; and the Java record contract forbids
     * overriding these methods to exclude components without converting
     * the type to a regular class (a significant scope expansion that
     * would also break the AAP-mandated Minimal Change Clause).  The
     * risk that the unmasked PAN participates in equality/hash
     * operations is <b>accepted</b> because:
     * <ul>
     *   <li>The PAN value, when present, lives in JVM memory for at
     *       most the duration of a single
     *       {@code POST /api/transactions} request (the service
     *       persists a tokenized/masked value via JPA and discards the
     *       DTO reference at the end of the controller invocation).</li>
     *   <li>{@link Object#equals(Object)} and {@link Object#hashCode()}
     *       on this DTO are not invoked by any audit, logging, caching,
     *       or persistence path &mdash; only {@code toString()} (which
     *       is PCI-DSS-masked) reaches CloudWatch / OpenSearch.</li>
     *   <li>Debugger inspection and heap-dump analysis are governed by
     *       the platform's PCI-DSS access controls (AAP &sect;0.6.6)
     *       and are out of scope for DTO-level mitigation.</li>
     * </ul>
     *
     * @return a string representation safe for logging and audit emission
     */
    @Override
    public String toString() {
        final String maskedPan = (cardNumber == null || cardNumber.length() < 4)
                ? null
                : "************" + cardNumber.substring(cardNumber.length() - 4);
        return "TransactionAddDto[accountId=" + accountId
                + ", cardNumber=" + maskedPan
                + ", transactionType=" + transactionType
                + ", transactionCategory=" + transactionCategory
                + ", source=" + source
                + ", description=" + description
                + ", amount=" + amount
                + ", originationTimestamp=" + originationTimestamp
                + ", processingTimestamp=" + processingTimestamp
                + ", merchantId=" + merchantId
                + ", merchantName=" + merchantName
                + ", merchantCity=" + merchantCity
                + ", merchantZip=" + merchantZip
                + ", confirm=" + confirm
                + ", transactionId=" + transactionId
                + "]";
    }
}
