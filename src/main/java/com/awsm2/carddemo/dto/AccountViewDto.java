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
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Account-inquiry response DTO returned by {@code GET /api/accounts/{id}}.
 *
 * <p>Replaces the 3270 account-view screen rendered by the COBOL/CICS
 * program {@code COACTVWC.cbl} (mapset {@code COACTVW}, map
 * {@code CACTVWA}, defined in {@code app/bms/COACTVW.bms} and the
 * generated symbolic-map copybook {@code app/cpy-bms/COACTVW.CPY}).  The
 * COBOL program joins three VSAM clusters &mdash; {@code ACCTDAT}
 * ({@code ACCOUNT-RECORD} from {@code app/cpy/CVACT01Y.cpy}),
 * {@code CUSTDAT} ({@code CUSTOMER-RECORD} from
 * {@code app/cpy/CVCUS01Y.cpy}), and {@code CXACAIX} (the
 * {@code CARDXREF} alternate index) &mdash; to build the data shown on a
 * single screen.  In the Java target, {@code AccountViewService.view(...)}
 * fetches the {@code Account} entity, resolves the customer via
 * {@code CardCrossReferenceRepository.findByXrefAcctId(...)}, joins with
 * {@code Customer}, and returns this DTO from the
 * {@code AccountController#getAccount(Long)} endpoint (AAP &sect;0.3.4 /
 * &sect;0.4.1).
 *
 * <p><b>Read-only response DTO:</b>  Unlike the request-side
 * {@code AccountUpdateDto}, this DTO is consumed by the REST client only.
 * The database is the source of truth for every monetary, date, and string
 * field, so this DTO carries <i>no</i> per-field Bean Validation
 * constraints beyond the single {@link Pattern @Pattern} on
 * {@link #activeStatus()} (which documents the COBOL
 * {@code ACCT-ACTIVE-STATUS PIC X(01)} Y/N invariant in the OpenAPI
 * schema).  Service-level validation occurs on the request side; emitted
 * responses are trusted &mdash; the {@link Pattern @Pattern} marker simply
 * serves as inline documentation for downstream consumers.
 *
 * <p><b>Segmented-field normalization (BMS &harr; JSON contract):</b>  The
 * BMS {@code COACTVW} map renders each date as a 10-character formatted
 * string (e.g., {@code mm/dd/yyyy}) and the SSN as a 12-character display
 * string with embedded separators (e.g., {@code 123-45-6789}); the COBOL
 * record layouts under {@code app/cpy/CVACT01Y.cpy} and
 * {@code app/cpy/CVCUS01Y.cpy} store each date as
 * {@code PIC X(10)} (e.g., {@code ACCT-OPEN-DATE},
 * {@code CUST-DOB-YYYY-MM-DD}) and the SSN as {@code CUST-SSN PIC 9(09)}.
 * This DTO normalizes the wire contract to:
 * <pre>{@code
 *   COBOL field                              JSON property             Format
 *   ---------------------------------------- ------------------------- --------------------
 *   ACCT-OPEN-DATE       PIC X(10)           openDate                  yyyy-MM-dd
 *   ACCT-EXPIRAION-DATE  PIC X(10)           expirationDate            yyyy-MM-dd
 *   ACCT-REISSUE-DATE    PIC X(10)           reissueDate               yyyy-MM-dd
 *   CUST-DOB-YYYY-MM-DD  PIC X(10)           dateOfBirth               yyyy-MM-dd
 *   CUST-SSN             PIC 9(09)           customerSsn               9-digit numeric Long
 * }</pre>
 * The ISO-8601 {@code yyyy-MM-dd} pattern is enforced by
 * {@link JsonFormat @JsonFormat(pattern = "yyyy-MM-dd")} on every
 * {@link LocalDate} component; Jackson serializes / deserializes against
 * the {@link java.time.format.DateTimeFormatter#ISO_LOCAL_DATE} formatter
 * automatically when the pattern matches.
 *
 * <p><b>COBOL Provenance (AAP &sect;0.7.3 traceability):</b>
 * <ul>
 *   <li>BMS Mapset: {@code app/bms/COACTVW.bms} (mapset {@code COACTVW},
 *       map {@code CACTVWA})</li>
 *   <li>Symbolic Map: {@code app/cpy-bms/COACTVW.CPY} (record
 *       {@code CACTVWAI} / output redefine {@code CACTVWAO})</li>
 *   <li>Program: {@code app/cbl/COACTVWC.cbl} (online CICS pseudo-
 *       conversational account-inquiry program; reads
 *       {@code ACCT-ID PIC 9(11)} from the screen, fetches the
 *       {@code ACCOUNT-RECORD}, resolves the customer via the
 *       {@code CXACAIX} alternate index, and renders the joined record)</li>
 *   <li>Record Layouts: {@code app/cpy/CVACT01Y.cpy} (ACCOUNT-RECORD, 300
 *       bytes), {@code app/cpy/CVCUS01Y.cpy} (CUSTOMER-RECORD, 500 bytes)</li>
 *   <li>Cross-reference: {@code app/cpy/CVACT03Y.cpy} (CARD-XREF-RECORD,
 *       50 bytes) &mdash; consumed by
 *       {@code CardCrossReferenceRepository.findByXrefAcctId(...)} in the
 *       Java target</li>
 *   <li>Produced by: {@code AccountViewService#view(Long)} (one-service-
 *       per-COBOL-program mapping per AAP &sect;0.3.3 / &sect;0.4.1)</li>
 *   <li>Controller endpoint: {@code GET /api/accounts/{id}} (see
 *       {@code AccountController})</li>
 * </ul>
 *
 * <p><b>Monetary precision (AAP &sect;0.6.1):</b>  Every monetary field
 * &mdash; {@link #currentBalance()}, {@link #creditLimit()},
 * {@link #cashCreditLimit()}, {@link #currentCycleCredit()}, and
 * {@link #currentCycleDebit()} &mdash; is typed as {@link BigDecimal} to
 * match the {@code PIC S9(10)V99} declarations on the
 * {@code ACCOUNT-RECORD} layout in {@code app/cpy/CVACT01Y.cpy}.  The
 * underlying JPA {@code Account} entity carries the
 * {@code @Column(precision = 12, scale = 2)} mapping (digits_total + 1 for
 * sign, scale = 2 for V99) and Hibernate serializes the column as
 * PostgreSQL {@code NUMERIC(12, 2)}; this DTO carries the value verbatim
 * &mdash; no {@code float}, no {@code double}, no {@code long}-cents
 * approximation.  Service-level arithmetic uses
 * {@link java.math.RoundingMode#HALF_EVEN} (banker's rounding) per AAP
 * &sect;0.6.1.
 *
 * <p><b>COBOL field-name typo preserved at source layer only:</b>  The
 * {@code ACCT-EXPIRAION-DATE} field name in {@code app/cpy/CVACT01Y.cpy}
 * (line 11) contains a spelling error ("EXPIRAION" instead of
 * "EXPIRATION").  The Java target uses the corrected English spelling
 * ({@link #expirationDate()}) because the wire contract is the canonical
 * JSON property name; the source-name typo is documented in this JavaDoc
 * for traceability per the Minimal Change Clause (AAP &sect;0.7.3) and the
 * {@code Account} entity field retains the documented mapping back to
 * {@code ACCT-EXPIRAION-DATE}.  This DTO never reuses the COBOL
 * misspelling.
 *
 * <p><b>PCI-DSS / PII handling (AAP &sect;0.6.6, &sect;0.7.1):</b>  The
 * {@link #customerSsn()} component carries a 9-digit US Social Security
 * Number, which is regulated PII.  The default {@link Record#toString()}
 * generated for a Java record exposes every component verbatim; that
 * would leak the SSN into application logs if a controller, service, or
 * validator inadvertently logs the response payload.  To prevent any
 * accidental SSN leakage into CloudWatch Logs and downstream OpenSearch
 * indexes, this record overrides {@link #toString()} to render the SSN as
 * {@code ***-**-XXXX} (last four digits only).  Amazon Macie
 * continuously scans S3 outputs for PII/financial-data exposure (AAP
 * &sect;0.6.6); application logs must never carry plain SSN.  Note that
 * the JSON serialization sent to authenticated REST clients is
 * <i>not</i> masked &mdash; the unmasked SSN is delivered over TLS 1.2+
 * to the authorized caller; only the textual {@link #toString()}
 * representation used in logs/audit is masked.
 *
 * <p>The auto-generated {@link #equals(Object)} and {@link #hashCode()}
 * implementations from the record contract are retained &mdash; equality
 * compares every component including the unmasked SSN (which is held in
 * memory during request processing); only the textual {@link #toString()}
 * representation is masked.
 *
 * <p>This DTO contains no business logic, no AWS-SDK references, and no
 * Lombok &mdash; it is a pure read-only response envelope per AAP
 * &sect;0.3.3 (Layered Architecture) and the no-Lombok directive in the
 * file's agent prompt.
 *
 * @param accountId          11-digit account identifier; maps to
 *                           {@code ACCT-ID PIC 9(11)} in
 *                           {@code app/cpy/CVACT01Y.cpy} (line 5) and BMS
 *                           field {@code ACCTSID PIC X(11)} on the
 *                           {@code COACTVW} screen
 * @param activeStatus       single-character active flag ('Y' or 'N');
 *                           maps to {@code ACCT-ACTIVE-STATUS PIC X(01)}
 *                           (line 6 of {@code CVACT01Y.cpy}) and BMS
 *                           field {@code ACSTTUS PIC X(01)}
 * @param currentBalance     signed current balance; maps to
 *                           {@code ACCT-CURR-BAL PIC S9(10)V99} (line 7
 *                           of {@code CVACT01Y.cpy}) and BMS field
 *                           {@code ACURBAL PICOUT '+ZZZ,ZZZ,ZZZ.99'};
 *                           typed as {@link BigDecimal} per AAP
 *                           &sect;0.6.1
 * @param creditLimit        signed credit limit; maps to
 *                           {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} (line
 *                           8) and BMS field {@code ACRDLIM}
 * @param cashCreditLimit    signed cash credit limit; maps to
 *                           {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99}
 *                           (line 9) and BMS field {@code ACSHLIM}
 * @param openDate           account open date (ISO yyyy-MM-dd); maps to
 *                           {@code ACCT-OPEN-DATE PIC X(10)} (line 10)
 *                           and BMS field {@code ADTOPEN PIC X(10)}
 * @param expirationDate     account expiration date (ISO yyyy-MM-dd);
 *                           maps to {@code ACCT-EXPIRAION-DATE PIC X(10)}
 *                           (line 11; COBOL typo preserved at the
 *                           database/entity layer) and BMS field
 *                           {@code AEXPDT PIC X(10)}
 * @param reissueDate        account reissue date (ISO yyyy-MM-dd); maps
 *                           to {@code ACCT-REISSUE-DATE PIC X(10)} (line
 *                           12) and BMS field {@code AREISDT PIC X(10)}
 * @param currentCycleCredit signed current-cycle credit total; maps to
 *                           {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99}
 *                           (line 13) and BMS field {@code ACRCYCR}
 * @param currentCycleDebit  signed current-cycle debit total; maps to
 *                           {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99}
 *                           (line 14) and BMS field {@code ACRCYDB}
 * @param addressZip         account address ZIP (1&ndash;10 chars); maps
 *                           to {@code ACCT-ADDR-ZIP PIC X(10)} (line 15
 *                           of {@code CVACT01Y.cpy})
 * @param accountGroupId     disclosure-group identifier used for interest-
 *                           rate lookup; maps to {@code ACCT-GROUP-ID PIC
 *                           X(10)} (line 16 of {@code CVACT01Y.cpy}) and
 *                           BMS field {@code AADDGRP}; references the
 *                           {@code DisclosureGroup} entity (AAP &sect;
 *                           0.4.1)
 * @param customerId         9-digit customer identifier; maps to
 *                           {@code CUST-ID PIC 9(09)} (line 5 of
 *                           {@code CVCUS01Y.cpy}) and BMS field
 *                           {@code ACSTNUM PIC X(9)}
 * @param firstName          customer first name (up to 25 chars); maps
 *                           to {@code CUST-FIRST-NAME PIC X(25)} (line 6
 *                           of {@code CVCUS01Y.cpy}) and BMS field
 *                           {@code ACSFNAM}
 * @param middleName         customer middle name (up to 25 chars); maps
 *                           to {@code CUST-MIDDLE-NAME PIC X(25)} (line
 *                           7) and BMS field {@code ACSMNAM}
 * @param lastName           customer last name (up to 25 chars); maps to
 *                           {@code CUST-LAST-NAME PIC X(25)} (line 8) and
 *                           BMS field {@code ACSLNAM}
 * @param customerSsn        9-digit SSN; maps to {@code CUST-SSN PIC
 *                           9(09)} (line 17 of {@code CVCUS01Y.cpy}) and
 *                           BMS field {@code ACSTSSN PIC X(12)}
 *                           (displayed segmented as 999-99-9999); masked
 *                           in {@link #toString()} per AAP &sect;0.6.6
 * @param phoneNumber1       customer primary phone (up to 15 chars);
 *                           maps to {@code CUST-PHONE-NUM-1 PIC X(15)}
 *                           (line 15 of {@code CVCUS01Y.cpy}) and BMS
 *                           field {@code ACSPHN1}
 * @param phoneNumber2       customer secondary phone (up to 15 chars);
 *                           maps to {@code CUST-PHONE-NUM-2 PIC X(15)}
 *                           (line 16) and BMS field {@code ACSPHN2}
 * @param addressLine1       customer address line 1 (up to 50 chars);
 *                           maps to {@code CUST-ADDR-LINE-1 PIC X(50)}
 *                           (line 9 of {@code CVCUS01Y.cpy}) and BMS
 *                           field {@code ACSADL1}
 * @param addressLine2       customer address line 2 (up to 50 chars);
 *                           maps to {@code CUST-ADDR-LINE-2 PIC X(50)}
 *                           (line 10) and BMS field {@code ACSADL2}
 * @param addressLine3       customer address line 3 / city (up to 50
 *                           chars); maps to {@code CUST-ADDR-LINE-3 PIC
 *                           X(50)} (line 11) and BMS field
 *                           {@code ACSCITY}
 * @param stateCode          2-letter US state code; maps to
 *                           {@code CUST-ADDR-STATE-CD PIC X(02)} (line
 *                           12) and BMS field {@code ACSSTTE PIC X(2)}
 * @param countryCode        3-letter country code; maps to
 *                           {@code CUST-ADDR-COUNTRY-CD PIC X(03)} (line
 *                           13) and BMS field {@code ACSCTRY PIC X(3)}
 * @param zipCode            customer ZIP code (up to 10 chars); maps to
 *                           {@code CUST-ADDR-ZIP PIC X(10)} (line 14 of
 *                           {@code CVCUS01Y.cpy}) and BMS field
 *                           {@code ACSZIPC PIC X(5)}
 * @param dateOfBirth        customer date of birth (ISO yyyy-MM-dd); maps
 *                           to {@code CUST-DOB-YYYY-MM-DD PIC X(10)}
 *                           (line 19 of {@code CVCUS01Y.cpy}) and BMS
 *                           field {@code ACSTDOB PIC X(10)}
 */
@Schema(name = "AccountViewDto",
        description = "Account inquiry response — joins Account (CVACT01Y) and Customer "
                + "(CVCUS01Y) data sourced via the CARDXREF alternate index (CVACT03Y); "
                + "returned by GET /api/accounts/{id}. Monetary fields use arbitrary-"
                + "precision decimal (BigDecimal) and date fields use ISO-8601 (yyyy-MM-dd) "
                + "per AAP §0.6.1. SSN is delivered to authorized callers over TLS but is "
                + "masked in server-side logs via the overridden toString().")
public record AccountViewDto(

        // ===== Account fields — sourced from app/cpy/CVACT01Y.cpy =====

        @Schema(description = "11-digit account identifier (ACCT-ID PIC 9(11)).",
                example = "10000000001",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("accountId")
        Long accountId,

        @Pattern(regexp = "^[YN]$",
                message = "activeStatus must be 'Y' or 'N' per ACCT-ACTIVE-STATUS contract")
        @Schema(description = "Account active status: 'Y' (active) or 'N' (inactive). "
                + "Maps to ACCT-ACTIVE-STATUS PIC X(01) in CVACT01Y.cpy.",
                example = "Y",
                allowableValues = {"Y", "N"},
                maxLength = 1,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("activeStatus")
        String activeStatus,

        @Schema(description = "Current balance (ACCT-CURR-BAL PIC S9(10)V99); "
                + "BigDecimal with scale=2 per AAP §0.6.1.",
                example = "1234.56",
                format = "decimal",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("currentBalance")
        BigDecimal currentBalance,

        @Schema(description = "Credit limit (ACCT-CREDIT-LIMIT PIC S9(10)V99); "
                + "BigDecimal with scale=2.",
                example = "5000.00",
                format = "decimal",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("creditLimit")
        BigDecimal creditLimit,

        @Schema(description = "Cash credit limit (ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99); "
                + "BigDecimal with scale=2.",
                example = "1000.00",
                format = "decimal",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("cashCreditLimit")
        BigDecimal cashCreditLimit,

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        @Schema(description = "Account open date (ACCT-OPEN-DATE PIC X(10), ISO yyyy-MM-dd).",
                example = "2020-01-15",
                format = "date",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("openDate")
        LocalDate openDate,

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        @Schema(description = "Account expiration date "
                + "(ACCT-EXPIRAION-DATE PIC X(10), ISO yyyy-MM-dd; "
                + "COBOL field-name typo preserved at the source layer only).",
                example = "2030-01-15",
                format = "date",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("expirationDate")
        LocalDate expirationDate,

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        @Schema(description = "Account reissue date "
                + "(ACCT-REISSUE-DATE PIC X(10), ISO yyyy-MM-dd).",
                example = "2025-01-15",
                format = "date")
        @JsonProperty("reissueDate")
        LocalDate reissueDate,

        @Schema(description = "Current cycle credit total "
                + "(ACCT-CURR-CYC-CREDIT PIC S9(10)V99); BigDecimal with scale=2.",
                example = "500.00",
                format = "decimal",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("currentCycleCredit")
        BigDecimal currentCycleCredit,

        @Schema(description = "Current cycle debit total "
                + "(ACCT-CURR-CYC-DEBIT PIC S9(10)V99); BigDecimal with scale=2.",
                example = "200.00",
                format = "decimal",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("currentCycleDebit")
        BigDecimal currentCycleDebit,

        @Schema(description = "Account address ZIP "
                + "(ACCT-ADDR-ZIP PIC X(10) in CVACT01Y.cpy).",
                example = "12345",
                maxLength = 10)
        @JsonProperty("addressZip")
        String addressZip,

        @Schema(description = "Disclosure group identifier "
                + "(ACCT-GROUP-ID PIC X(10) in CVACT01Y.cpy) used for "
                + "interest-rate lookup via the DisclosureGroup entity.",
                example = "DEFAULT",
                maxLength = 10)
        @JsonProperty("accountGroupId")
        String accountGroupId,

        // ===== Customer fields — sourced from app/cpy/CVCUS01Y.cpy =====

        @Schema(description = "9-digit customer identifier "
                + "(CUST-ID PIC 9(09) in CVCUS01Y.cpy).",
                example = "100000001",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("customerId")
        Long customerId,

        @Schema(description = "Customer first name "
                + "(CUST-FIRST-NAME PIC X(25) in CVCUS01Y.cpy).",
                example = "John",
                maxLength = 25)
        @JsonProperty("firstName")
        String firstName,

        @Schema(description = "Customer middle name "
                + "(CUST-MIDDLE-NAME PIC X(25) in CVCUS01Y.cpy).",
                example = "M",
                maxLength = 25)
        @JsonProperty("middleName")
        String middleName,

        @Schema(description = "Customer last name "
                + "(CUST-LAST-NAME PIC X(25) in CVCUS01Y.cpy).",
                example = "Doe",
                maxLength = 25)
        @JsonProperty("lastName")
        String lastName,

        @Schema(description = "Customer SSN (CUST-SSN PIC 9(09) in CVCUS01Y.cpy). "
                + "Masked as ***-**-XXXX in toString() output for log/audit safety "
                + "per AAP §0.6.6 PCI-DSS handling; unmasked over TLS 1.2+ in the "
                + "JSON response to authorized callers.",
                example = "123456789",
                accessMode = Schema.AccessMode.READ_ONLY)
        @JsonProperty("customerSsn")
        Long customerSsn,

        @Schema(description = "Customer primary phone "
                + "(CUST-PHONE-NUM-1 PIC X(15) in CVCUS01Y.cpy).",
                example = "5551234567",
                maxLength = 15)
        @JsonProperty("phoneNumber1")
        String phoneNumber1,

        @Schema(description = "Customer secondary phone "
                + "(CUST-PHONE-NUM-2 PIC X(15) in CVCUS01Y.cpy).",
                example = "5557654321",
                maxLength = 15)
        @JsonProperty("phoneNumber2")
        String phoneNumber2,

        @Schema(description = "Customer address line 1 "
                + "(CUST-ADDR-LINE-1 PIC X(50) in CVCUS01Y.cpy).",
                example = "123 Main St",
                maxLength = 50)
        @JsonProperty("addressLine1")
        String addressLine1,

        @Schema(description = "Customer address line 2 "
                + "(CUST-ADDR-LINE-2 PIC X(50) in CVCUS01Y.cpy).",
                example = "Apt 4B",
                maxLength = 50)
        @JsonProperty("addressLine2")
        String addressLine2,

        @Schema(description = "Customer address line 3 / city "
                + "(CUST-ADDR-LINE-3 PIC X(50) in CVCUS01Y.cpy).",
                example = "New York",
                maxLength = 50)
        @JsonProperty("addressLine3")
        String addressLine3,

        @Schema(description = "Customer state code, 2-letter US abbreviation "
                + "(CUST-ADDR-STATE-CD PIC X(02) in CVCUS01Y.cpy).",
                example = "NY",
                maxLength = 2)
        @JsonProperty("stateCode")
        String stateCode,

        @Schema(description = "Customer country code, 3-letter ISO 3166-1 alpha-3 "
                + "(CUST-ADDR-COUNTRY-CD PIC X(03) in CVCUS01Y.cpy).",
                example = "USA",
                maxLength = 3)
        @JsonProperty("countryCode")
        String countryCode,

        @Schema(description = "Customer ZIP code "
                + "(CUST-ADDR-ZIP PIC X(10) in CVCUS01Y.cpy).",
                example = "10001",
                maxLength = 10)
        @JsonProperty("zipCode")
        String zipCode,

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        @Schema(description = "Customer date of birth "
                + "(CUST-DOB-YYYY-MM-DD PIC X(10), ISO yyyy-MM-dd).",
                example = "1980-05-15",
                format = "date")
        @JsonProperty("dateOfBirth")
        LocalDate dateOfBirth

) {

    /**
     * Returns a string representation of this DTO with the SSN masked as
     * {@code ***-**-XXXX} (last four digits only) for log/audit safety per
     * AAP &sect;0.6.6 PCI-DSS handling.
     *
     * <p>This override prevents accidental SSN leakage into CloudWatch
     * Logs and downstream OpenSearch indexes when a controller, service,
     * filter, or interceptor logs the response payload via the default
     * {@code Object#toString()} path.  The unmasked SSN remains in memory
     * (and on the JSON wire to the authorized caller) &mdash; only the
     * textual representation produced by this method is masked.
     *
     * <p>The masking algorithm:
     * <ol>
     *   <li>If {@link #customerSsn()} is {@code null}, render the literal
     *       {@code "null"} for that field.</li>
     *   <li>Otherwise, zero-pad the value to 9 digits using
     *       {@code String.format("%09d", customerSsn)}, then concatenate
     *       the literal prefix {@code "***-**-"} with the last four
     *       digits ({@code substring(5)}).</li>
     * </ol>
     * Zero-padding ensures the {@code substring(5)} slice always yields
     * a well-defined last-four-digit segment regardless of the actual
     * decimal width of the boxed {@link Long} value (e.g., a leading-zero
     * SSN such as {@code 023456789} which has 9 digits but a boxed
     * decimal width of 8).
     *
     * <p>The full address, phone, name, and date fields are included in
     * the output for diagnostic completeness; only the SSN is masked.
     * Operators auditing logs may search by the trailing-four-digit
     * suffix to correlate events without exposing the full SSN.  This
     * mirrors the COBOL behavior where the unmasked SSN is held in
     * working storage during request processing but never written to a
     * permanent audit log.
     *
     * @return a string representation safe for logging and audit emission
     */
    @Override
    public String toString() {
        final String maskedSsn;
        if (customerSsn == null) {
            maskedSsn = "null";
        } else {
            // Zero-pad to 9 digits so the substring(5,9) slice always
            // yields a well-defined last-four-digit segment regardless of
            // the actual decimal width of the boxed Long value.
            final String ssnStr = String.format("%09d", customerSsn);
            maskedSsn = "***-**-" + ssnStr.substring(5);
        }
        return "AccountViewDto[accountId=" + accountId
                + ", activeStatus=" + activeStatus
                + ", currentBalance=" + currentBalance
                + ", creditLimit=" + creditLimit
                + ", cashCreditLimit=" + cashCreditLimit
                + ", openDate=" + openDate
                + ", expirationDate=" + expirationDate
                + ", reissueDate=" + reissueDate
                + ", currentCycleCredit=" + currentCycleCredit
                + ", currentCycleDebit=" + currentCycleDebit
                + ", addressZip=" + addressZip
                + ", accountGroupId=" + accountGroupId
                + ", customerId=" + customerId
                + ", firstName=" + firstName
                + ", middleName=" + middleName
                + ", lastName=" + lastName
                + ", customerSsn=" + maskedSsn
                + ", phoneNumber1=" + phoneNumber1
                + ", phoneNumber2=" + phoneNumber2
                + ", addressLine1=" + addressLine1
                + ", addressLine2=" + addressLine2
                + ", addressLine3=" + addressLine3
                + ", stateCode=" + stateCode
                + ", countryCode=" + countryCode
                + ", zipCode=" + zipCode
                + ", dateOfBirth=" + dateOfBirth
                + "]";
    }
}
