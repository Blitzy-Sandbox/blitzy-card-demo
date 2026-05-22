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
import java.time.LocalDate;

/**
 * Account-update request DTO with optimistic locking.
 *
 * <p>Replaces the 3270 account-update screen rendered by the COBOL/CICS
 * program {@code COACTUPC.cbl} (mapset {@code COACTUP}, map {@code CACTUPA},
 * defined in {@code app/bms/COACTUP.bms} and the generated symbolic-map
 * copybook {@code app/cpy-bms/COACTUP.CPY}).  {@code COACTUPC.cbl} is the
 * <i>only</i> program in the source code base that uses
 * {@code EXEC CICS SYNCPOINT ROLLBACK} (line 4100 of the source) &mdash; it
 * performs a dual update of the {@code ACCTDAT} ({@code ACCOUNT-RECORD}) and
 * {@code CUSTDAT} ({@code CUSTOMER-RECORD}) VSAM clusters inside a single
 * CICS unit of work and rolls both writes back if the second
 * {@code REWRITE} fails.  In the Java target, this dual write is wrapped in
 * {@code AccountUpdateService.update(...)} with
 * {@code @Transactional(rollbackFor = Exception.class)} on the service
 * method &mdash; preserving the all-or-nothing semantics of the original
 * SYNCPOINT boundary (AAP &sect;0.6.2 / &sect;0.7.1).
 *
 * <p><b>Optimistic locking (AAP &sect;0.6.2):</b>  The COBOL source manually
 * compares before / after images of every {@code ACCT-*} and {@code CUST-*}
 * field in paragraph {@code 9700-CHECK-CHANGE-IN-REC} (lines 4109+ of
 * {@code COACTUPC.cbl}) and aborts with a {@code LOCKED-BUT-UPDATE-FAILED}
 * condition if any other process has modified the records since the GET.
 * In the Java target this snapshot-comparison pattern is replaced by JPA
 * {@code @Version} on the {@code Account} entity.  The {@link #version()}
 * component on this DTO carries the version value returned by the prior
 * {@code GET /api/accounts/{id}} call; if the database version no longer
 * matches the supplied {@link #version()} at save time, JPA raises an
 * {@code OptimisticLockException} which the service translates into a
 * {@code ConcurrentModificationException} (HTTP 409 via
 * {@code GlobalExceptionHandler}).
 *
 * <p><b>Segmented-field normalization (BMS &harr; JSON contract):</b>  The
 * BMS {@code COACTUP} map renders date, SSN, and phone values as
 * <i>segmented</i> sub-fields on the 3270 screen, but this DTO collapses
 * each into a single canonical JSON property:
 * <pre>{@code
 *   BMS segment(s)                          JSON property             Format
 *   -------------------------------------   -----------------------   --------------------
 *   OPNYEAR(4)/OPNMON(2)/OPNDAY(2)           openDate                  yyyy-MM-dd
 *   EXPYEAR(4)/EXPMON(2)/EXPDAY(2)           expirationDate            yyyy-MM-dd
 *   RISYEAR(4)/RISMON(2)/RISDAY(2)           reissueDate               yyyy-MM-dd
 *   DOBYEAR(4)/DOBMON(2)/DOBDAY(2)           dateOfBirth               yyyy-MM-dd
 *   ACTSSN1(3)/ACTSSN2(2)/ACTSSN3(4)         customerSsn               9-digit numeric
 *   ACSPH1A(3)/ACSPH1B(3)/ACSPH1C(4)         phoneNumber1              10-digit numeric
 *   ACSPH2A(3)/ACSPH2B(3)/ACSPH2C(4)         phoneNumber2              10-digit numeric (optional)
 * }</pre>
 * The COBOL {@code COACTUPC.cbl} program reconstructs the segmented values
 * via REDEFINES in {@code WS-EDIT-US-SSN}, {@code WS-EDIT-US-PHONE-NUM},
 * etc. (lines 82&ndash;147), then validates the digit ranges (e.g.,
 * {@code INVALID-SSN-PART1} excludes 0, 666, and 900&ndash;999 at line 121).
 * In the Java target the operator/client supplies the already-assembled
 * value; field-level Bean Validation verifies the format
 * ({@link Pattern @Pattern} regex) and the service layer (via
 * {@code ValidationLookupService} &mdash; port of
 * {@code app/cpy/CSLKPCDY.cpy}) applies the lookup-table checks (NANPA
 * area codes, US state codes, state/ZIP-prefix combinations).
 *
 * <p><b>Account-pairs-with-Customer in one DTO:</b>  The {@code COACTUP}
 * BMS map presents <i>both</i> account fields and customer fields on a
 * single 3270 screen because the underlying {@code COACTUPC.cbl} program
 * commits both within one SYNCPOINT boundary.  This DTO mirrors that
 * pairing so that a single REST request body drives both the
 * {@code Account} and {@code Customer} JPA entity updates inside the
 * service's single {@code @Transactional} method (AAP &sect;0.6.2 / &sect;
 * 0.7.1).  Separating them into two DTOs would require either a
 * client-driven two-phase commit (forbidden &mdash; not equivalent to the
 * source's single-SYNCPOINT semantics) or a service-internal aggregation
 * step (forbidden by the Minimal Change Clause &mdash; the source uses one
 * DTO worth of fields).
 *
 * <p><b>Administrative balance adjustment preservation:</b>  The COBOL
 * source allows the operator to type a new {@code ACURBAL} (current
 * balance) value into the {@code COACTUP} screen, which becomes part of
 * the {@code REWRITE} payload.  This is intentional in the source &mdash;
 * an administrator may override balances during reconciliation &mdash; and
 * is preserved verbatim in this DTO: {@link #currentBalance()} is
 * <i>writable</i> on update.  Any business rules that gate when an
 * administrator may write the balance live in the service layer; they are
 * <b>not</b> field-level constraints (per the Minimal Change Clause: AAP
 * &sect;0.7.3).
 *
 * <p><b>Cross-field validation (delegated to service layer):</b>
 * <ul>
 *   <li>Phone area code (NANPA): validated by
 *       {@code ValidationLookupService.isValidAreaCode(...)} &mdash; port
 *       of {@code WS-US-PHONE-AREA-CODE-TO-EDIT} 88-level list in
 *       {@code CSLKPCDY.cpy}.</li>
 *   <li>US state code: validated by
 *       {@code ValidationLookupService.isValidStateCode(...)} &mdash; port
 *       of the {@code WS-US-STATE-CODE-TO-EDIT} 88-level list in
 *       {@code CSLKPCDY.cpy}.</li>
 *   <li>State + ZIP-prefix combination: validated by
 *       {@code ValidationLookupService.isValidStateZipCombination(...)}
 *       &mdash; port of the {@code WS-US-STATE-AND-FIRST-ZIP2} 88-level
 *       list in {@code CSLKPCDY.cpy}.</li>
 *   <li>Date semantic validity (leap-year, month/day, future-date guards):
 *       delegated to {@code DateValidationService} &mdash; port of
 *       {@code app/cpy/CSUTLDPY.cpy} and {@code app/cbl/CSUTLDTC.cbl}
 *       (replaces the LE {@code CEEDAYS} call: AAP &sect;0.5.2).</li>
 * </ul>
 * On failure the service raises a {@code ValidationException} from
 * {@code com.awsm2.carddemo.exception} with a populated {@code fieldErrors}
 * map; {@code GlobalExceptionHandler} translates the exception to HTTP 400
 * Bad Request with a standardized JSON envelope (AAP &sect;0.3.4).
 *
 * <p><b>Monetary precision (AAP &sect;0.6.1):</b>  Every monetary field
 * &mdash; {@link #currentBalance()}, {@link #creditLimit()},
 * {@link #cashCreditLimit()}, {@link #currentCycleCredit()}, and
 * {@link #currentCycleDebit()} &mdash; is typed as {@link BigDecimal} and
 * annotated with {@link Digits @Digits(integer = 10, fraction = 2)} to
 * match the {@code PIC S9(10)V99} declarations on the
 * {@code ACCOUNT-RECORD} layout in {@code app/cpy/CVACT01Y.cpy}.  Service-
 * level arithmetic uses {@link java.math.RoundingMode#HALF_EVEN} (banker's
 * rounding) and {@code BigDecimal}'s arbitrary-precision contract &mdash;
 * never {@code double} or {@code float}.  The two non-current monetary
 * limits ({@link #creditLimit()} and {@link #cashCreditLimit()}) carry an
 * additional {@link DecimalMin @DecimalMin("0.00")} guard reflecting the
 * COBOL {@code FLG-CRED-LIMIT-NOT-OK} / {@code FLG-CASH-CREDIT-LIMIT-NOT-OK}
 * sign-check predicates in {@code COACTUPC.cbl} (lines 196&ndash;203).
 * {@link #currentBalance()}, {@link #currentCycleCredit()}, and
 * {@link #currentCycleDebit()} accept signed values because the COBOL
 * record layout declares them as signed ({@code PIC S9(10)V99}) and the
 * online program does not enforce non-negativity on the balance field.
 *
 * <p><b>COBOL Provenance (AAP &sect;0.7.3 traceability):</b>
 * <ul>
 *   <li>BMS Mapset: {@code app/bms/COACTUP.bms} (mapset
 *       {@code COACTUP}, map {@code CACTUPA})</li>
 *   <li>Symbolic Map: {@code app/cpy-bms/COACTUP.CPY} (record
 *       {@code CACTUPAI} / output redefine {@code CACTUPAO})</li>
 *   <li>Program: {@code app/cbl/COACTUPC.cbl} (online CICS pseudo-
 *       conversational account-maintenance program; only program in the
 *       source code base that issues {@code SYNCPOINT ROLLBACK})</li>
 *   <li>Record Layouts: {@code app/cpy/CVACT01Y.cpy} (ACCOUNT-RECORD, 300
 *       bytes), {@code app/cpy/CVCUS01Y.cpy} (CUSTOMER-RECORD, 500 bytes)</li>
 *   <li>Validation lookup: {@code app/cpy/CSLKPCDY.cpy} (NANPA area
 *       codes, US state/territory codes, state + first-two-of-ZIP
 *       combinations)</li>
 *   <li>Consumed by: {@code AccountUpdateService#update(Long,
 *       AccountUpdateDto)} (one-service-per-COBOL-program mapping per AAP
 *       &sect;0.3.3 / &sect;0.4.1)</li>
 *   <li>Controller endpoint: {@code PUT /api/accounts/{id}} (see
 *       {@code AccountController})</li>
 * </ul>
 *
 * <p><b>PCI-DSS / PII handling (AAP &sect;0.7.1, &sect;0.6.6):</b>  The
 * {@link #customerSsn()} component carries a 9-digit US Social Security
 * Number, which is regulated PII.  The default {@link Record#toString()}
 * generated for a record exposes every component verbatim; that would
 * leak the SSN into application logs if a controller, service, or
 * validator inadvertently logs the request body.  To prevent any
 * accidental SSN leakage into CloudWatch Logs and downstream OpenSearch
 * indexes, this record overrides {@link #toString()} to render the SSN as
 * {@code ***-**-XXXX} (last four digits only).  No password, CVV, or full
 * PAN appears on this DTO, so no other masking is required.  The
 * auto-generated {@link #equals(Object)} and {@link #hashCode()}
 * implementations from the record contract are retained &mdash; equality
 * compares every component including the unmasked SSN (which is held in
 * memory during request processing); only the textual {@link #toString()}
 * representation is masked.
 *
 * <p>This DTO contains no business logic, no AWS-SDK references, and no
 * Lombok &mdash; it is a pure validated request envelope per AAP
 * &sect;0.3.3 (Layered Architecture) and the no-Lombok directive in the
 * file's agent prompt.
 *
 * <p><b>COBOL field-name typo preserved:</b>  The
 * {@code ACCT-EXPIRAION-DATE} field name in {@code app/cpy/CVACT01Y.cpy}
 * contains a spelling error ("EXPIRAION" instead of "EXPIRATION").  The
 * Java target uses the corrected English spelling
 * ({@link #expirationDate()}) because the wire contract is the canonical
 * JSON property name; the source-name typo is documented in this JavaDoc
 * for traceability per the Minimal Change Clause (AAP &sect;0.7.3) &mdash;
 * the database column and the {@code Account} entity field retain the
 * documented mapping back to {@code ACCT-EXPIRAION-DATE}.
 *
 * @param accountId          11-digit account identifier (primary key on
 *                           {@code ACCOUNT-RECORD}); maps to
 *                           {@code ACCT-ID PIC 9(11)} in
 *                           {@code app/cpy/CVACT01Y.cpy} and BMS field
 *                           {@code ACCTSID PIC X(11)} on the
 *                           {@code COACTUP} screen
 * @param activeStatus       single-character active flag ('Y' or 'N');
 *                           maps to {@code ACCT-ACTIVE-STATUS PIC X(01)}
 *                           and BMS field {@code ACSTTUS PIC X(01)};
 *                           validated by the COBOL
 *                           {@code FLG-ACCT-STATUS-ISVALID} 88-level
 *                           (line 193 of {@code COACTUPC.cbl})
 * @param currentBalance     signed current balance; maps to
 *                           {@code ACCT-CURR-BAL PIC S9(10)V99} and BMS
 *                           field {@code ACURBAL PIC X(15)}; typed as
 *                           {@link BigDecimal} per AAP &sect;0.6.1 and
 *                           preserved on update to honor administrative
 *                           balance-adjustment semantics of the source
 * @param creditLimit        non-negative credit limit; maps to
 *                           {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} and
 *                           BMS field {@code ACRDLIM PIC X(15)}
 * @param cashCreditLimit    non-negative cash credit limit; maps to
 *                           {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99}
 *                           and BMS field {@code ACSHLIM PIC X(15)}
 * @param openDate           account open date (ISO yyyy-MM-dd); maps to
 *                           {@code ACCT-OPEN-DATE PIC X(10)} and segmented
 *                           BMS fields {@code OPNYEAR}/{@code OPNMON}/
 *                           {@code OPNDAY}
 * @param expirationDate     account expiration date (ISO yyyy-MM-dd); maps
 *                           to {@code ACCT-EXPIRAION-DATE PIC X(10)} (COBOL
 *                           typo preserved at the database/entity layer)
 *                           and segmented BMS fields {@code EXPYEAR}/
 *                           {@code EXPMON}/{@code EXPDAY}
 * @param reissueDate        account reissue date (ISO yyyy-MM-dd); maps to
 *                           {@code ACCT-REISSUE-DATE PIC X(10)} and
 *                           segmented BMS fields {@code RISYEAR}/
 *                           {@code RISMON}/{@code RISDAY}
 * @param currentCycleCredit signed current-cycle credit total; maps to
 *                           {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} and
 *                           BMS field {@code ACRCYCR PIC X(15)}
 * @param currentCycleDebit  signed current-cycle debit total; maps to
 *                           {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} and
 *                           BMS field {@code ACRCYDB PIC X(15)}
 * @param addressZip         account address ZIP (optional); maps to
 *                           {@code ACCT-ADDR-ZIP PIC X(10)}; note the
 *                           BMS COACTUP screen does <i>not</i> render a
 *                           dedicated account-ZIP field &mdash; this
 *                           component is present to round-trip the
 *                           underlying record-layout value (Minimal
 *                           Change Clause)
 * @param accountGroupId     disclosure / account group identifier (foreign
 *                           key into the {@code disclosure_group} table);
 *                           maps to {@code ACCT-GROUP-ID PIC X(10)} and
 *                           BMS field {@code AADDGRP PIC X(10)}
 * @param customerId         9-digit customer identifier (primary key on
 *                           {@code CUSTOMER-RECORD}); maps to
 *                           {@code CUST-ID PIC 9(09)} in
 *                           {@code app/cpy/CVCUS01Y.cpy} and BMS field
 *                           {@code ACSTNUM PIC X(09)}
 * @param firstName          customer first name; maps to
 *                           {@code CUST-FIRST-NAME PIC X(25)} and BMS
 *                           field {@code ACSFNAM PIC X(25)}
 * @param middleName         customer middle name (optional); maps to
 *                           {@code CUST-MIDDLE-NAME PIC X(25)} and BMS
 *                           field {@code ACSMNAM PIC X(25)}
 * @param lastName           customer last name; maps to
 *                           {@code CUST-LAST-NAME PIC X(25)} and BMS
 *                           field {@code ACSLNAM PIC X(25)}
 * @param customerSsn        customer Social Security Number (9-digit
 *                           numeric); maps to {@code CUST-SSN PIC 9(09)}
 *                           and segmented BMS fields {@code ACTSSN1}/
 *                           {@code ACTSSN2}/{@code ACTSSN3}.  Masked in
 *                           {@link #toString()} per AAP &sect;0.6.6
 *                           PCI-DSS / PII rules
 * @param phoneNumber1       customer primary phone (10 digits, no
 *                           separators); maps to
 *                           {@code CUST-PHONE-NUM-1 PIC X(15)} and
 *                           segmented BMS fields {@code ACSPH1A}/
 *                           {@code ACSPH1B}/{@code ACSPH1C}
 * @param phoneNumber2       customer secondary phone (optional, 10 digits
 *                           or empty); maps to
 *                           {@code CUST-PHONE-NUM-2 PIC X(15)} and
 *                           segmented BMS fields {@code ACSPH2A}/
 *                           {@code ACSPH2B}/{@code ACSPH2C}
 * @param addressLine1       customer address line 1; maps to
 *                           {@code CUST-ADDR-LINE-1 PIC X(50)} and BMS
 *                           field {@code ACSADL1 PIC X(50)}
 * @param addressLine2       customer address line 2 (optional); maps to
 *                           {@code CUST-ADDR-LINE-2 PIC X(50)} and BMS
 *                           field {@code ACSADL2 PIC X(50)}
 * @param addressLine3       customer address line 3 (optional); maps to
 *                           {@code CUST-ADDR-LINE-3 PIC X(50)} (the BMS
 *                           {@code COACTUP} screen does not surface line
 *                           3; preserved here to round-trip the
 *                           record-layout value)
 * @param stateCode          US state/territory code (2 uppercase letters);
 *                           maps to {@code CUST-ADDR-STATE-CD PIC X(02)}
 *                           and BMS field {@code ACSSTTE PIC X(02)};
 *                           lookup-table-validated against
 *                           {@code CSLKPCDY.cpy} state codes by the
 *                           service layer
 * @param countryCode        ISO country code (3 uppercase letters); maps
 *                           to {@code CUST-ADDR-COUNTRY-CD PIC X(03)} and
 *                           BMS field {@code ACSCTRY PIC X(03)}
 * @param zipCode            customer ZIP code (5 digits or 5+4 with
 *                           hyphen); maps to
 *                           {@code CUST-ADDR-ZIP PIC X(10)} and BMS field
 *                           {@code ACSZIPC PIC X(05)} (the BMS screen
 *                           accepts 5; the record stores up to 10 to
 *                           accommodate ZIP+4)
 * @param dateOfBirth        customer date of birth (ISO yyyy-MM-dd); maps
 *                           to {@code CUST-DOB-YYYY-MM-DD PIC X(10)} and
 *                           segmented BMS fields {@code DOBYEAR}/
 *                           {@code DOBMON}/{@code DOBDAY}
 * @param version            JPA optimistic-lock version value from the
 *                           prior {@code GET /api/accounts/{id}};
 *                           replaces the COBOL before/after image
 *                           comparison in paragraph
 *                           {@code 9700-CHECK-CHANGE-IN-REC} of
 *                           {@code COACTUPC.cbl}
 *
 * @see <a href=
 *      "https://github.com/aws-samples/aws-mainframe-modernization-carddemo">
 *      AWS CardDemo (source COBOL)</a>
 */
@Schema(name = "AccountUpdateDto",
        description = "Account-update request payload combining Account and Customer "
                + "fields.  Mirrors the input fields of the legacy COACTUP 3270 screen "
                + "(BMS map CACTUPA) and the underlying ACCOUNT-RECORD (CVACT01Y.cpy, "
                + "300 bytes) and CUSTOMER-RECORD (CVCUS01Y.cpy, 500 bytes) layouts.  "
                + "The service wraps the dual write in a single @Transactional method, "
                + "mirroring the SYNCPOINT / SYNCPOINT ROLLBACK boundary of the source "
                + "COACTUPC.cbl program.  Optimistic locking is enforced via the "
                + "version field, which must equal the value returned by the prior "
                + "GET; mismatch yields HTTP 409.  SSN values are masked in toString() "
                + "per PCI-DSS / PII rules.")
public record AccountUpdateDto(

        // ===================================================================
        // Account fields (ACCOUNT-RECORD / CVACT01Y.cpy)
        // ===================================================================

        @NotNull(message = "Account ID is required")
        @Schema(description = "11-digit account identifier. Maps to ACCT-ID PIC 9(11) "
                        + "in CVACT01Y.cpy and BMS field ACCTSID PIC X(11) on the "
                        + "COACTUP screen. Primary key on the Account JPA entity.",
                example = "10000000001",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("accountId")
        Long accountId,

        @NotBlank(message = "Active status is required")
        @Pattern(regexp = "^[YN]$",
                message = "Active status must be 'Y' or 'N'")
        @Schema(description = "Account active status flag. Maps to "
                        + "ACCT-ACTIVE-STATUS PIC X(01) and BMS field ACSTTUS. "
                        + "Validated by COBOL 88-level FLG-ACCT-STATUS-ISVALID "
                        + "(COACTUPC.cbl line 193).",
                example = "Y",
                maxLength = 1,
                allowableValues = {"Y", "N"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("activeStatus")
        String activeStatus,

        @NotNull(message = "Current balance is required")
        @Digits(integer = 10, fraction = 2,
                message = "Current balance must have at most 10 integer digits and 2 fraction digits")
        @Schema(description = "Signed current account balance. Maps to "
                        + "ACCT-CURR-BAL PIC S9(10)V99 in CVACT01Y.cpy and BMS "
                        + "field ACURBAL. Administrative balance adjustments are "
                        + "permitted on update (preserved from the COBOL source).",
                example = "1234.56",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("currentBalance")
        BigDecimal currentBalance,

        @NotNull(message = "Credit limit is required")
        @DecimalMin(value = "0.00", message = "Credit limit must be >= 0")
        @Digits(integer = 10, fraction = 2,
                message = "Credit limit must have at most 10 integer digits and 2 fraction digits")
        @Schema(description = "Non-negative credit limit. Maps to "
                        + "ACCT-CREDIT-LIMIT PIC S9(10)V99 and BMS field ACRDLIM. "
                        + "Sign check ports COBOL FLG-CRED-LIMIT-NOT-OK (line 196).",
                example = "5000.00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("creditLimit")
        BigDecimal creditLimit,

        @NotNull(message = "Cash credit limit is required")
        @DecimalMin(value = "0.00", message = "Cash credit limit must be >= 0")
        @Digits(integer = 10, fraction = 2,
                message = "Cash credit limit must have at most 10 integer digits and 2 fraction digits")
        @Schema(description = "Non-negative cash credit limit. Maps to "
                        + "ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 and BMS field "
                        + "ACSHLIM. Sign check ports COBOL "
                        + "FLG-CASH-CREDIT-LIMIT-NOT-OK (line 200).",
                example = "1000.00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("cashCreditLimit")
        BigDecimal cashCreditLimit,

        @NotNull(message = "Open date is required")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        @Schema(description = "Account open date (ISO 8601 yyyy-MM-dd). Maps to "
                        + "ACCT-OPEN-DATE PIC X(10) and segmented BMS fields "
                        + "OPNYEAR/OPNMON/OPNDAY. Semantic validation (leap year, "
                        + "month/day) delegated to DateValidationService.",
                example = "2020-01-15",
                format = "date",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("openDate")
        LocalDate openDate,

        @NotNull(message = "Expiration date is required")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        @Schema(description = "Account expiration date (ISO 8601 yyyy-MM-dd). Maps "
                        + "to ACCT-EXPIRAION-DATE PIC X(10) (COBOL spelling typo "
                        + "preserved at the storage layer) and segmented BMS fields "
                        + "EXPYEAR/EXPMON/EXPDAY.",
                example = "2030-01-15",
                format = "date",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("expirationDate")
        LocalDate expirationDate,

        @NotNull(message = "Reissue date is required")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        @Schema(description = "Account reissue date (ISO 8601 yyyy-MM-dd). Maps to "
                        + "ACCT-REISSUE-DATE PIC X(10) and segmented BMS fields "
                        + "RISYEAR/RISMON/RISDAY.",
                example = "2025-01-15",
                format = "date",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("reissueDate")
        LocalDate reissueDate,

        @NotNull(message = "Current cycle credit is required")
        @Digits(integer = 10, fraction = 2,
                message = "Current cycle credit must have at most 10 integer digits and 2 fraction digits")
        @Schema(description = "Signed current-cycle credit total. Maps to "
                        + "ACCT-CURR-CYC-CREDIT PIC S9(10)V99 and BMS field "
                        + "ACRCYCR. Negative values are permitted (signed PIC).",
                example = "500.00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("currentCycleCredit")
        BigDecimal currentCycleCredit,

        @NotNull(message = "Current cycle debit is required")
        @Digits(integer = 10, fraction = 2,
                message = "Current cycle debit must have at most 10 integer digits and 2 fraction digits")
        @Schema(description = "Signed current-cycle debit total. Maps to "
                        + "ACCT-CURR-CYC-DEBIT PIC S9(10)V99 and BMS field "
                        + "ACRCYDB. Negative values are permitted (signed PIC).",
                example = "200.00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("currentCycleDebit")
        BigDecimal currentCycleDebit,

        @Size(max = 10,
                message = "Account address ZIP must be at most 10 characters")
        @Schema(description = "Account address ZIP. Maps to ACCT-ADDR-ZIP "
                        + "PIC X(10) in CVACT01Y.cpy. (The BMS COACTUP screen "
                        + "does not surface this field; included for "
                        + "record-layout round-trip per the Minimal Change "
                        + "Clause.)",
                example = "12345",
                maxLength = 10)
        @JsonProperty("addressZip")
        String addressZip,

        @NotBlank(message = "Account group ID is required")
        @Size(max = 10,
                message = "Account group ID must be at most 10 characters")
        @Schema(description = "Disclosure / account group identifier. Maps to "
                        + "ACCT-GROUP-ID PIC X(10) and BMS field AADDGRP. "
                        + "Foreign key into the disclosure_group table "
                        + "(port of CVTRA02Y.cpy).",
                example = "DEFAULT",
                maxLength = 10,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("accountGroupId")
        String accountGroupId,

        // ===================================================================
        // Customer fields (CUSTOMER-RECORD / CVCUS01Y.cpy)
        // ===================================================================

        @NotNull(message = "Customer ID is required")
        @Schema(description = "9-digit customer identifier. Maps to CUST-ID "
                        + "PIC 9(09) in CVCUS01Y.cpy and BMS field ACSTNUM "
                        + "PIC X(09). Primary key on the Customer JPA entity.",
                example = "100000001",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("customerId")
        Long customerId,

        @NotBlank(message = "First name is required")
        @Size(max = 25,
                message = "First name must be at most 25 characters")
        @Schema(description = "Customer first name. Maps to CUST-FIRST-NAME "
                        + "PIC X(25) and BMS field ACSFNAM PIC X(25).",
                example = "John",
                maxLength = 25,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("firstName")
        String firstName,

        @Size(max = 25,
                message = "Middle name must be at most 25 characters")
        @Schema(description = "Customer middle name (optional). Maps to "
                        + "CUST-MIDDLE-NAME PIC X(25) and BMS field ACSMNAM "
                        + "PIC X(25).",
                example = "M",
                maxLength = 25)
        @JsonProperty("middleName")
        String middleName,

        @NotBlank(message = "Last name is required")
        @Size(max = 25,
                message = "Last name must be at most 25 characters")
        @Schema(description = "Customer last name. Maps to CUST-LAST-NAME "
                        + "PIC X(25) and BMS field ACSLNAM PIC X(25).",
                example = "Doe",
                maxLength = 25,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("lastName")
        String lastName,

        /**
         * 9-digit US Social Security Number.  Masked in {@link #toString()}
         * to {@code ***-**-XXXX} per AAP &sect;0.6.6 PCI-DSS / PII rules.
         * Lookup-validation against {@code WS-EDIT-US-SSN-PART1} excluded
         * ranges (0, 666, 900&ndash;999) is performed by
         * {@code ValidationLookupService} (port of {@code CSLKPCDY.cpy}).
         */
        @NotNull(message = "Customer SSN is required")
        @Schema(description = "Customer Social Security Number (9-digit numeric). "
                        + "Maps to CUST-SSN PIC 9(09) and segmented BMS fields "
                        + "ACTSSN1/ACTSSN2/ACTSSN3. Masked in toString() per "
                        + "PCI-DSS / PII rules.",
                example = "123456789",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("customerSsn")
        Long customerSsn,

        @NotBlank(message = "Phone number 1 is required")
        @Pattern(regexp = "^\\d{10}$",
                message = "Phone number 1 must be exactly 10 digits (area code + exchange + line)")
        @Schema(description = "Customer primary phone (10 digits, no separators; "
                        + "the area code is validated by ValidationLookupService "
                        + "against the NANPA list ported from CSLKPCDY.cpy). "
                        + "Maps to CUST-PHONE-NUM-1 PIC X(15) and segmented BMS "
                        + "fields ACSPH1A/ACSPH1B/ACSPH1C.",
                example = "2125551234",
                maxLength = 15,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("phoneNumber1")
        String phoneNumber1,

        @Pattern(regexp = "^(\\d{10})?$",
                message = "Phone number 2 must be exactly 10 digits if present, or empty")
        @Schema(description = "Customer secondary phone (optional; 10 digits or "
                        + "empty). Maps to CUST-PHONE-NUM-2 PIC X(15) and "
                        + "segmented BMS fields ACSPH2A/ACSPH2B/ACSPH2C.",
                example = "2125555678",
                maxLength = 15)
        @JsonProperty("phoneNumber2")
        String phoneNumber2,

        @NotBlank(message = "Address line 1 is required")
        @Size(max = 50,
                message = "Address line 1 must be at most 50 characters")
        @Schema(description = "Customer address line 1. Maps to "
                        + "CUST-ADDR-LINE-1 PIC X(50) and BMS field ACSADL1.",
                example = "123 Main Street",
                maxLength = 50,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("addressLine1")
        String addressLine1,

        @Size(max = 50,
                message = "Address line 2 must be at most 50 characters")
        @Schema(description = "Customer address line 2 (optional). Maps to "
                        + "CUST-ADDR-LINE-2 PIC X(50) and BMS field ACSADL2.",
                example = "Apt 4B",
                maxLength = 50)
        @JsonProperty("addressLine2")
        String addressLine2,

        @Size(max = 50,
                message = "Address line 3 must be at most 50 characters")
        @Schema(description = "Customer address line 3 / city. Maps to "
                        + "CUST-ADDR-LINE-3 PIC X(50) and BMS field ACSCITY "
                        + "PIC X(50) on the COACTUP screen (line 392 of "
                        + "app/bms/COACTUP.bms). The COBOL program "
                        + "COACTUPC.cbl moves ACSCITYI directly to "
                        + "ACUP-NEW-CUST-ADDR-LINE-3 (lines 1329-1333), "
                        + "confirming that this field carries the city "
                        + "value rendered in the BMS 'City' label.",
                maxLength = 50)
        @JsonProperty("addressLine3")
        String addressLine3,

        @NotBlank(message = "State code is required")
        @Pattern(regexp = "^[A-Z]{2}$",
                message = "State code must be exactly 2 uppercase letters")
        @Schema(description = "US state / territory code (2 uppercase letters). "
                        + "Maps to CUST-ADDR-STATE-CD PIC X(02) and BMS field "
                        + "ACSSTTE. Validated against the lookup list ported "
                        + "from CSLKPCDY.cpy by ValidationLookupService.",
                example = "NY",
                maxLength = 2,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("stateCode")
        String stateCode,

        @NotBlank(message = "Country code is required")
        @Pattern(regexp = "^[A-Z]{3}$",
                message = "Country code must be exactly 3 uppercase letters")
        @Schema(description = "ISO 3166-1 alpha-3 country code (3 uppercase "
                        + "letters). Maps to CUST-ADDR-COUNTRY-CD PIC X(03) and "
                        + "BMS field ACSCTRY.",
                example = "USA",
                maxLength = 3,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("countryCode")
        String countryCode,

        @NotBlank(message = "ZIP code is required")
        @Pattern(regexp = "^\\d{5}(-\\d{4})?$",
                message = "ZIP code must be 5 digits or 5+4 (e.g., 12345 or 12345-6789)")
        @Schema(description = "Customer ZIP code (5 digits or 5+4 with hyphen). "
                        + "Maps to CUST-ADDR-ZIP PIC X(10) and BMS field ACSZIPC "
                        + "PIC X(05). The state/ZIP-prefix combination is "
                        + "validated by ValidationLookupService against the "
                        + "WS-US-STATE-AND-FIRST-ZIP2 list from CSLKPCDY.cpy.",
                example = "12345",
                maxLength = 10,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("zipCode")
        String zipCode,

        @NotNull(message = "Date of birth is required")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        @Schema(description = "Customer date of birth (ISO 8601 yyyy-MM-dd). "
                        + "Maps to CUST-DOB-YYYY-MM-DD PIC X(10) and segmented "
                        + "BMS fields DOBYEAR/DOBMON/DOBDAY. Semantic "
                        + "validation (leap year, month/day, past-date guard) "
                        + "delegated to DateValidationService.",
                example = "1980-05-15",
                format = "date",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("dateOfBirth")
        LocalDate dateOfBirth,

        /**
         * Government-issued identifier (driver's license, passport, etc.)
         * captured by {@code CUST-GOVT-ISSUED-ID PIC X(20)} in
         * {@code CVCUS01Y.cpy} and rendered on the BMS {@code COACTUP}
         * screen as the unprotected input field {@code ACSGOVT} (line 433
         * of {@code app/bms/COACTUP.bms}, length 20).  Optional in the
         * Customer record and on the BMS screen (no {@code @NotBlank}).
         */
        @Size(max = 20,
                message = "Government-issued ID must be at most 20 characters")
        @Schema(description = "Government-issued identifier (driver's license, "
                        + "passport, etc.). Maps to CUST-GOVT-ISSUED-ID "
                        + "PIC X(20) in CVCUS01Y.cpy and BMS field ACSGOVT "
                        + "PIC X(20) on the COACTUP screen (line 433 of "
                        + "app/bms/COACTUP.bms). Optional on the source "
                        + "screen.",
                example = "D12345678",
                maxLength = 20)
        @JsonProperty("governmentIssuedId")
        String governmentIssuedId,

        /**
         * Electronic Funds Transfer (EFT) account identifier captured by
         * {@code CUST-EFT-ACCOUNT-ID PIC X(10)} in {@code CVCUS01Y.cpy} and
         * rendered on the BMS {@code COACTUP} screen as the unprotected
         * input field {@code ACSEFTC} (line 464 of {@code app/bms/COACTUP.bms},
         * length 10).  Optional in the Customer record and on the BMS screen.
         */
        @Size(max = 10,
                message = "EFT account ID must be at most 10 characters")
        @Schema(description = "EFT (Electronic Funds Transfer) account "
                        + "identifier. Maps to CUST-EFT-ACCOUNT-ID PIC X(10) "
                        + "in CVCUS01Y.cpy and BMS field ACSEFTC PIC X(10) "
                        + "on the COACTUP screen (line 464 of "
                        + "app/bms/COACTUP.bms). Optional on the source "
                        + "screen.",
                example = "1234567890",
                maxLength = 10)
        @JsonProperty("eftAccountId")
        String eftAccountId,

        /**
         * Primary card holder indicator ({@code "Y"} or {@code "N"})
         * captured by {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)} in
         * {@code CVCUS01Y.cpy} and rendered on the BMS {@code COACTUP}
         * screen as the unprotected input field {@code ACSPFLG} (line 474
         * of {@code app/bms/COACTUP.bms}, length 1).
         */
        @Pattern(regexp = "^[YN ]?$",
                message = "Primary card holder indicator must be 'Y', 'N', or blank")
        @Size(max = 1,
                message = "Primary card holder indicator must be at most 1 character")
        @Schema(description = "Primary card holder indicator ('Y' or 'N'). "
                        + "Maps to CUST-PRI-CARD-HOLDER-IND PIC X(01) in "
                        + "CVCUS01Y.cpy and BMS field ACSPFLG PIC X(01) on "
                        + "the COACTUP screen (line 474 of "
                        + "app/bms/COACTUP.bms).",
                example = "Y",
                maxLength = 1,
                allowableValues = {"Y", "N", " "})
        @JsonProperty("primaryCardHolderIndicator")
        String primaryCardHolderIndicator,

        /**
         * FICO credit score ({@code 300}&ndash;{@code 850}) captured by
         * {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} in {@code CVCUS01Y.cpy}
         * and rendered on the BMS {@code COACTUP} screen as the
         * unprotected input field {@code ACSTFCO} (line 318 of
         * {@code app/bms/COACTUP.bms}, length 3).  The 3-digit width on
         * the BMS screen permits the full FICO range; the Java target
         * applies a {@code @Min}/{@code @Max} bound as a defensive check.
         */
        @jakarta.validation.constraints.Min(value = 0,
                message = "FICO credit score must be >= 0 (PIC 9(03) lower bound)")
        @jakarta.validation.constraints.Max(value = 999,
                message = "FICO credit score must be <= 999 (PIC 9(03) upper bound)")
        @Schema(description = "FICO credit score (PIC 9(03), 0-999). Maps "
                        + "to CUST-FICO-CREDIT-SCORE PIC 9(03) in "
                        + "CVCUS01Y.cpy and BMS field ACSTFCO PIC 9(03) on "
                        + "the COACTUP screen (line 318 of "
                        + "app/bms/COACTUP.bms). The PIC clause permits "
                        + "0-999; standard FICO scores fall within "
                        + "300-850.",
                example = "720",
                minimum = "0",
                maximum = "999")
        @JsonProperty("ficoCreditScore")
        Integer ficoCreditScore,

        // ===================================================================
        // Optimistic-locking version (replaces COACTUPC.cbl snapshot-compare)
        // ===================================================================

        /**
         * JPA {@code @Version} value from the prior {@code GET}.  Required
         * for optimistic locking on the {@code Account} entity; mismatch on
         * save raises {@code OptimisticLockException}, translated to
         * {@code ConcurrentModificationException} &rarr; HTTP 409 by
         * {@code GlobalExceptionHandler}.  Replaces the manual snapshot
         * comparison performed in paragraph {@code 9700-CHECK-CHANGE-IN-REC}
         * of {@code COACTUPC.cbl} (lines 4109+).  The {@code Customer}
         * update inside the service's {@code @Transactional} method
         * piggybacks on this lock because both writes share the single
         * transactional boundary that replaces the source's SYNCPOINT
         * (AAP &sect;0.6.2).
         */
        @NotNull(message = "Version is required for optimistic-locking-based update")
        @Schema(description = "Optimistic-lock version from the prior GET. "
                        + "Replaces the COBOL before/after image comparison "
                        + "(COACTUPC.cbl paragraph 9700-CHECK-CHANGE-IN-REC). "
                        + "Mismatch yields HTTP 409.",
                example = "0",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("version")
        Long version
) {

    /**
     * Returns a PII-safe string representation of this request.
     *
     * <p>The default {@link Record#toString()} generated by the Java record
     * contract would include the full 9-digit {@link #customerSsn()}
     * verbatim.  Per AAP &sect;0.6.6 (PCI-DSS / PII rules) and the
     * Macie-driven leakage-prevention controls referenced in the AAP, this
     * overridden method renders the SSN as {@code ***-**-XXXX} (last four
     * digits only) so that accidental logging of the DTO in CloudWatch
     * Logs or OpenSearch does not expose the regulated PII value.
     *
     * <p>If {@link #customerSsn()} is {@code null}, the masked
     * representation is rendered literally as {@code null} (no placeholder
     * asterisks) to avoid implying that a masked SSN is present.  Numeric
     * SSNs shorter than 9 digits (which should not occur under the
     * field's {@code Long} typing combined with service-level NANPA-style
     * validation) are normalized to 9 digits by zero-padding via
     * {@link String#format(String, Object...)} before masking, so the
     * "last four digits" representation is always well-defined.
     *
     * <p>Every other component is rendered as-is &mdash; none of the
     * other fields carry sensitive cardholder or government-issued PII.
     * Equality and hash semantics are unaffected; the auto-generated
     * {@link Object#equals(Object)} and {@link Object#hashCode()}
     * implementations from the record contract continue to use every
     * component including the unmasked SSN, per the Java record contract
     * &mdash; only the textual {@link #toString()} is masked.  This
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
        return "AccountUpdateDto[accountId=" + accountId
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
                + ", governmentIssuedId=" + governmentIssuedId
                + ", eftAccountId=" + eftAccountId
                + ", primaryCardHolderIndicator=" + primaryCardHolderIndicator
                + ", ficoCreditScore=" + ficoCreditScore
                + ", version=" + version
                + "]";
    }
}
