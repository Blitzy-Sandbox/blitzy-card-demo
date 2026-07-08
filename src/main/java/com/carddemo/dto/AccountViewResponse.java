package com.carddemo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Immutable response payload for the <strong>Account View</strong> use case
 * (CICS transaction {@code CAVW}), served by {@code AccountController} and
 * produced by {@code AccountViewService}.
 *
 * <p>This DTO flattens the legacy {@code ACCOUNT-RECORD} and
 * {@code CUSTOMER-RECORD} into the single, denormalized shape presented by the
 * 3270 BMS screen {@code COACTVW}, so the REST response mirrors exactly what the
 * mainframe operator saw on the "Account View" panel. There is deliberately
 * <em>no</em> nested customer object &mdash; the customer attributes are
 * flattened alongside the account attributes to preserve the screen contract.</p>
 *
 * <h2>Source of truth (COBOL, referenced by commit SHA {@code 27d6c6f})</h2>
 * <ul>
 *   <li>Screen layout: {@code app/cpy-bms/COACTVW.CPY}</li>
 *   <li>Account record: {@code app/cpy/CVACT01Y.cpy} (ACCOUNT-RECORD, RECLN 300)</li>
 *   <li>Customer record: {@code app/cpy/CVCUS01Y.cpy} (CUSTOMER-RECORD, RECLN 500)</li>
 * </ul>
 *
 * <h2>Fidelity rules honored</h2>
 * <ul>
 *   <li><strong>Decimal precision (AAP &sect;0.8.2):</strong> every
 *       {@code PIC S9(10)V99} monetary field maps to {@link BigDecimal} with a
 *       fixed <em>scale of 2</em>. The canonical constructor normalizes each
 *       money value via {@code setScale(2, HALF_UP)}, so the invariant holds
 *       regardless of how the value was produced. {@code float}/{@code double}
 *       are never used for money.</li>
 *   <li><strong>Dates:</strong> the {@code X(10)} date fields map to
 *       {@link LocalDate} and always serialize as ISO {@code yyyy-MM-dd}.</li>
 *   <li><strong>Identifiers:</strong> numeric COBOL keys (account id, customer
 *       id) remain {@link String} to preserve fixed width and leading zeros.</li>
 *   <li><strong>Optimistic concurrency (AAP &sect;0.8.4):</strong>
 *       {@link #version()} echoes the JPA {@code @Version} of the underlying
 *       account so the client can send it back on a subsequent update, matching
 *       the COACTUPC read-then-rewrite pattern.</li>
 *   <li><strong>Field widths:</strong> {@code @Size(max = ...)} documents the
 *       authoritative <em>record</em> widths from CVACT01Y/CVCUS01Y. Where the
 *       COACTVW screen width differs, it is noted inline (SSN, ZIP, phone).</li>
 * </ul>
 *
 * <h2>PII / SSN handling (decision-log item)</h2>
 * <p>{@code CUST-SSN} is sensitive PII. To preserve the legacy screen contract
 * (validation Gates 1 and 5), the raw value is carried by default; however,
 * masking to the last four digits in REST responses is recommended. This class
 * therefore offers {@link #maskSsn(String)} and {@link #withMaskedSsn()}; the
 * <em>producing service</em> decides whether to mask. Masking preserves the
 * {@link String} type and the 9-character record width. No password or other
 * credential is ever carried by this DTO.</p>
 *
 * <p>Instances are immutable, thread-safe, stateless holders (Java record).</p>
 *
 * @param accountId                  ACCT-ID {@code PIC 9(11)} / screen ACCTSID.
 * @param activeStatus               ACCT-ACTIVE-STATUS {@code PIC X(01)} / screen ACSTTUS ({@code Y}/{@code N}).
 * @param currentBalance             ACCT-CURR-BAL {@code PIC S9(10)V99} (scale 2).
 * @param creditLimit                ACCT-CREDIT-LIMIT {@code PIC S9(10)V99} (scale 2).
 * @param cashCreditLimit            ACCT-CASH-CREDIT-LIMIT {@code PIC S9(10)V99} (scale 2).
 * @param currentCycleCredit         ACCT-CURR-CYC-CREDIT {@code PIC S9(10)V99} (scale 2).
 * @param currentCycleDebit          ACCT-CURR-CYC-DEBIT {@code PIC S9(10)V99} (scale 2).
 * @param openDate                   ACCT-OPEN-DATE {@code PIC X(10)} (ISO yyyy-MM-dd).
 * @param expirationDate             ACCT-EXPIRAION-DATE {@code PIC X(10)} (legacy misspelling retained in copybook).
 * @param reissueDate                ACCT-REISSUE-DATE {@code PIC X(10)}.
 * @param accountGroupId             ACCT-GROUP-ID {@code PIC X(10)} / screen AADDGRP.
 * @param version                    JPA {@code @Version} echo for optimistic locking (AAP &sect;0.8.4).
 * @param customerId                 CUST-ID {@code PIC 9(09)} / screen ACSTNUM.
 * @param firstName                  CUST-FIRST-NAME {@code PIC X(25)}.
 * @param middleName                 CUST-MIDDLE-NAME {@code PIC X(25)}.
 * @param lastName                   CUST-LAST-NAME {@code PIC X(25)}.
 * @param addressLine1               CUST-ADDR-LINE-1 {@code PIC X(50)}.
 * @param addressLine2               CUST-ADDR-LINE-2 {@code PIC X(50)}.
 * @param city                       CUST-ADDR-LINE-3 {@code PIC X(50)} (screen ACSCITY: record line-3 is the screen city).
 * @param stateCode                  CUST-ADDR-STATE-CD {@code PIC X(02)}.
 * @param countryCode                CUST-ADDR-COUNTRY-CD {@code PIC X(03)}.
 * @param zipCode                    CUST-ADDR-ZIP {@code PIC X(10)} (screen ACSZIPC is X(5)).
 * @param phoneNumber1               CUST-PHONE-NUM-1 {@code PIC X(15)} (screen ACSPHN1 is X(13), formatted).
 * @param phoneNumber2               CUST-PHONE-NUM-2 {@code PIC X(15)}.
 * @param ssn                        CUST-SSN {@code PIC 9(09)} (screen ACSTSSN is X(12)); PII &mdash; see {@link #maskSsn(String)}.
 * @param govtIssuedId               CUST-GOVT-ISSUED-ID {@code PIC X(20)}.
 * @param dateOfBirth                CUST-DOB-YYYY-MM-DD {@code PIC X(10)} (ISO yyyy-MM-dd).
 * @param eftAccountId               CUST-EFT-ACCOUNT-ID {@code PIC X(10)}.
 * @param primaryCardHolderIndicator CUST-PRI-CARD-HOLDER-IND {@code PIC X(01)} ({@code Y}/{@code N}).
 * @param ficoScore                  CUST-FICO-CREDIT-SCORE {@code PIC 9(03)} (300&ndash;850).
 */
public record AccountViewResponse(

        // ----- Account attributes (app/cpy/CVACT01Y.cpy — ACCOUNT-RECORD, RECLN 300) -----

        // ACCT-ID PIC 9(11): String preserves fixed width + leading zeros.
        @Size(max = 11) String accountId,
        // ACCT-ACTIVE-STATUS PIC X(01): Y/N flag.
        @Size(max = 1) String activeStatus,
        // ACCT-CURR-BAL PIC S9(10)V99: scale-2 money.
        BigDecimal currentBalance,
        // ACCT-CREDIT-LIMIT PIC S9(10)V99: scale-2 money.
        BigDecimal creditLimit,
        // ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99: scale-2 money.
        BigDecimal cashCreditLimit,
        // ACCT-CURR-CYC-CREDIT PIC S9(10)V99: scale-2 money.
        BigDecimal currentCycleCredit,
        // ACCT-CURR-CYC-DEBIT PIC S9(10)V99: scale-2 money.
        BigDecimal currentCycleDebit,
        // ACCT-OPEN-DATE PIC X(10): ISO date.
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate openDate,
        // ACCT-EXPIRAION-DATE PIC X(10): ISO date (legacy misspelling retained in copybook).
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate expirationDate,
        // ACCT-REISSUE-DATE PIC X(10): ISO date.
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate reissueDate,
        // ACCT-GROUP-ID PIC X(10).
        @Size(max = 10) String accountGroupId,
        // JPA @Version echo (optimistic lock token for update round-trip).
        Long version,

        // ----- Customer attributes (app/cpy/CVCUS01Y.cpy — CUSTOMER-RECORD, RECLN 500) -----

        // CUST-ID PIC 9(09): String preserves fixed width + leading zeros.
        @Size(max = 9) String customerId,
        // CUST-FIRST-NAME PIC X(25).
        @Size(max = 25) String firstName,
        // CUST-MIDDLE-NAME PIC X(25).
        @Size(max = 25) String middleName,
        // CUST-LAST-NAME PIC X(25).
        @Size(max = 25) String lastName,
        // CUST-ADDR-LINE-1 PIC X(50).
        @Size(max = 50) String addressLine1,
        // CUST-ADDR-LINE-2 PIC X(50).
        @Size(max = 50) String addressLine2,
        // CUST-ADDR-LINE-3 PIC X(50): the screen "city" (ACSCITY) is record address line 3.
        @Size(max = 50) String city,
        // CUST-ADDR-STATE-CD PIC X(02).
        @Size(max = 2) String stateCode,
        // CUST-ADDR-COUNTRY-CD PIC X(03).
        @Size(max = 3) String countryCode,
        // CUST-ADDR-ZIP PIC X(10) (screen ACSZIPC is X(5)).
        @Size(max = 10) String zipCode,
        // CUST-PHONE-NUM-1 PIC X(15) (screen ACSPHN1 is X(13), formatted).
        @Size(max = 15) String phoneNumber1,
        // CUST-PHONE-NUM-2 PIC X(15).
        @Size(max = 15) String phoneNumber2,
        // CUST-SSN PIC 9(09) (screen ACSTSSN is X(12)); PII — see maskSsn/withMaskedSsn.
        @Size(max = 9) String ssn,
        // CUST-GOVT-ISSUED-ID PIC X(20).
        @Size(max = 20) String govtIssuedId,
        // CUST-DOB-YYYY-MM-DD PIC X(10): ISO date.
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate dateOfBirth,
        // CUST-EFT-ACCOUNT-ID PIC X(10).
        @Size(max = 10) String eftAccountId,
        // CUST-PRI-CARD-HOLDER-IND PIC X(01): Y/N flag.
        @Size(max = 1) String primaryCardHolderIndicator,
        // CUST-FICO-CREDIT-SCORE PIC 9(03): 300–850.
        Integer ficoScore

) {

    /** Fixed decimal scale for every monetary field, matching COBOL {@code V99}. */
    private static final int MONEY_SCALE = 2;

    /** Number of trailing SSN digits left visible when masking. */
    private static final int SSN_VISIBLE_DIGITS = 4;

    /**
     * Canonical constructor that enforces the scale-2 invariant for every
     * monetary field. Applying {@link BigDecimal#setScale(int, RoundingMode)}
     * with {@link RoundingMode#HALF_UP} here guarantees that the DTO always
     * carries {@code PIC S9(10)V99}-equivalent precision (AAP &sect;0.8.2),
     * regardless of the scale of the values supplied by the mapping layer.
     * {@code null} money values are preserved as {@code null}.
     */
    public AccountViewResponse {
        currentBalance = normalizeMoney(currentBalance);
        creditLimit = normalizeMoney(creditLimit);
        cashCreditLimit = normalizeMoney(cashCreditLimit);
        currentCycleCredit = normalizeMoney(currentCycleCredit);
        currentCycleDebit = normalizeMoney(currentCycleDebit);
    }

    /**
     * Normalizes a monetary amount to the fixed scale of 2 using
     * {@link RoundingMode#HALF_UP}, reproducing COBOL rounding at assignment.
     *
     * @param value the raw amount (may be {@code null})
     * @return the amount at scale 2, or {@code null} if {@code value} is {@code null}
     */
    private static BigDecimal normalizeMoney(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Masks a Social Security Number so that only the final four digits remain
     * visible, replacing every earlier digit with {@code '*'}. Non-digit
     * characters are stripped before masking, and the result preserves the
     * 9-character record width for a standard {@code CUST-SSN PIC 9(09)} value
     * (for example, {@code "123456789"} becomes {@code "*****6789"}).
     *
     * <p>Values that are {@code null} are returned unchanged; values with four
     * or fewer digits are returned unchanged because masking them would not
     * conceal any additional information.</p>
     *
     * @param ssn the raw SSN (may be {@code null})
     * @return the masked SSN, or the original value when there is nothing to mask
     */
    public static String maskSsn(String ssn) {
        if (ssn == null) {
            return null;
        }
        String digits = ssn.replaceAll("\\D", "");
        if (digits.length() <= SSN_VISIBLE_DIGITS) {
            return ssn;
        }
        String visible = digits.substring(digits.length() - SSN_VISIBLE_DIGITS);
        return "*".repeat(digits.length() - SSN_VISIBLE_DIGITS) + visible;
    }

    /**
     * Returns a copy of this response with the {@link #ssn()} masked to its
     * last four digits (see {@link #maskSsn(String)}). All other fields are
     * carried over unchanged. The producing service uses this when it elects to
     * redact PII in REST responses; the raw value is retained otherwise so the
     * legacy screen contract (Gates 1 and 5) is preserved by default.
     *
     * @return a new, immutable {@code AccountViewResponse} with a masked SSN
     */
    public AccountViewResponse withMaskedSsn() {
        return new AccountViewResponse(
                accountId, activeStatus, currentBalance, creditLimit, cashCreditLimit,
                currentCycleCredit, currentCycleDebit, openDate, expirationDate, reissueDate,
                accountGroupId, version,
                customerId, firstName, middleName, lastName, addressLine1, addressLine2,
                city, stateCode, countryCode, zipCode, phoneNumber1, phoneNumber2,
                maskSsn(ssn), govtIssuedId, dateOfBirth, eftAccountId,
                primaryCardHolderIndicator, ficoScore);
    }
}
