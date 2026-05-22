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

        @Pattern(regexp = "^\\d{11}$",
                message = "Account ID must be exactly 11 digits")
        @Schema(description = "11-digit account identifier (optional if cardNumber is "
                        + "supplied; the service resolves the missing identifier via "
                        + "the CARDXREF cross-reference). Maps to BMS ACTIDIN PIC X(11).",
                example = "10000000001",
                maxLength = 11)
        @JsonProperty("accountId")
        String accountId,

        @Pattern(regexp = "^\\d{16}$",
                message = "Card number must be exactly 16 digits")
        @Schema(description = "16-digit card number / Primary Account Number (PAN), "
                        + "optional if accountId is supplied. Maps to BMS CARDNIN PIC X(16) "
                        + "and to TRAN-CARD-NUM PIC X(16) on the record. The toString() "
                        + "implementation on this DTO masks this value per PCI-DSS "
                        + "Requirement 3.4.",
                example = "4111111111111111",
                maxLength = 16)
        @JsonProperty("cardNumber")
        String cardNumber,

        @NotBlank(message = "Transaction type is required")
        @Pattern(regexp = "^\\d{2}$",
                message = "Transaction type must be 2 digits")
        @Schema(description = "Transaction type code (2 digits; foreign key into the "
                        + "transaction_type table). Maps to BMS TTYPCD PIC X(02) and to "
                        + "TRAN-TYPE-CD PIC X(02) on the record.",
                example = "01",
                maxLength = 2,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("transactionType")
        String transactionType,

        @NotNull(message = "Transaction category is required")
        @Schema(description = "Transaction category code (4 digits; foreign key into the "
                        + "transaction_category table). Maps to BMS TCATCD PIC X(04) and to "
                        + "TRAN-CAT-CD PIC 9(04) on the record.",
                example = "5411",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("transactionCategory")
        Integer transactionCategory,

        @NotBlank(message = "Transaction source is required")
        @Size(max = 10,
                message = "Transaction source must be at most 10 characters")
        @Schema(description = "Free-text transaction source (e.g., \"ONLINE\", \"BATCH\", "
                        + "\"MOBILE\"). Maps to BMS TRNSRC PIC X(10) and to "
                        + "TRAN-SOURCE PIC X(10) on the record.",
                example = "ONLINE",
                maxLength = 10,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("source")
        String source,

        @NotBlank(message = "Description is required")
        @Size(max = 100,
                message = "Description must be at most 100 characters")
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

        @NotNull(message = "Amount is required")
        @Digits(integer = 9,
                fraction = 2,
                message = "Amount must have at most 9 integer digits and 2 fraction digits "
                        + "(S9(09)V99)")
        @DecimalMin(value = "-999999999.99",
                message = "Amount must be at least -999999999.99 (S9(09)V99 lower bound)")
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

        @NotNull(message = "Origination date is required")
        @JsonFormat(shape = JsonFormat.Shape.STRING,
                pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Origination timestamp supplied by the operator/client "
                        + "(ISO-8601, yyyy-MM-dd'T'HH:mm:ss). Maps to BMS TORIGDT PIC X(10) "
                        + "(date portion only, displayed as YYYY-MM-DD) and to "
                        + "TRAN-ORIG-TS PIC X(26) on the record. The service captures the "
                        + "processing timestamp (TRAN-PROC-TS) internally; the client "
                        + "supplies only the origination timestamp. Java native "
                        + "LocalDateTime replaces the LE CEEDAYS dependency per AAP "
                        + "\u00a70.6.3.",
                example = "2026-05-20T14:30:45",
                format = "date-time",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("originationTimestamp")
        LocalDateTime originationTimestamp,

        @NotNull(message = "Merchant ID is required")
        @Schema(description = "9-digit merchant identifier. Maps to BMS MID PIC X(09) and "
                        + "to TRAN-MERCHANT-ID PIC 9(09) on the record.",
                example = "100000001",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("merchantId")
        Long merchantId,

        @NotBlank(message = "Merchant name is required")
        @Size(max = 50,
                message = "Merchant name must be at most 50 characters")
        @Schema(description = "Merchant name. Maps to BMS MNAME PIC X(30) on the screen, "
                        + "but the underlying TRAN-MERCHANT-NAME PIC X(50) record field "
                        + "permits up to 50 characters; the wider capacity is honored here "
                        + "for non-3270 callers.",
                example = "ACME GROCERY",
                maxLength = 50,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("merchantName")
        String merchantName,

        @NotBlank(message = "Merchant city is required")
        @Size(max = 50,
                message = "Merchant city must be at most 50 characters")
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

        @NotBlank(message = "Merchant ZIP is required")
        @Size(max = 10,
                message = "Merchant ZIP must be at most 10 characters")
        @Schema(description = "Merchant ZIP code (US 5 or 9 digit; either '98101' or "
                        + "'98101-1234' form is accepted). Maps to BMS MZIP PIC X(10) "
                        + "and to TRAN-MERCHANT-ZIP PIC X(10) on the record.",
                example = "98101",
                maxLength = 10,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("merchantZip")
        String merchantZip,

        @Pattern(regexp = "^[YN]$",
                message = "Confirmation must be 'Y' or 'N'")
        @Schema(description = "Confirmation flag: 'Y' to post the transaction, 'N' to "
                        + "cancel without posting. Maps to BMS CONFIRM PIC X(01). This "
                        + "field is transient and is not persisted on the TRAN-RECORD; the "
                        + "service uses it to gate the final write and to emit a typed "
                        + "validation response when the operator cancels.",
                example = "Y",
                maxLength = 1,
                allowableValues = {"Y", "N"})
        @JsonProperty("confirm")
        String confirm
) {

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
     * fields carry sensitive cardholder data.  Equality and hash semantics
     * are unaffected; {@link Object#equals(Object)} and
     * {@link Object#hashCode()} continue to use every component including
     * the unmasked PAN, per the Java record contract.
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
                + ", merchantId=" + merchantId
                + ", merchantName=" + merchantName
                + ", merchantCity=" + merchantCity
                + ", merchantZip=" + merchantZip
                + ", confirm=" + confirm
                + "]";
    }
}
