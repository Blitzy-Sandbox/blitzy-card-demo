package com.carddemo.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request payload for the Account Update use case (legacy transaction
 * <strong>CAUP</strong>, COBOL program {@code COACTUPC}).
 *
 * <p>This DTO is the modern, headless replacement for the {@code COACTUP} BMS
 * screen ({@code app/cpy-bms/COACTUP.CPY}). It carries the editable account and
 * customer attributes submitted by a client and is validated with Jakarta Bean
 * Validation constraints that reproduce the {@code CICS RECEIVE MAP} field
 * edits. Its fields are backed by the legacy {@code ACCOUNT-RECORD}
 * ({@code CVACT01Y}, record length 300) and {@code CUSTOMER-RECORD}
 * ({@code CVCUS01Y}, record length 500) copybooks.</p>
 *
 * <h2>Modernization decisions</h2>
 * <ul>
 *   <li><strong>Composed domain types.</strong> The legacy BMS map split dates
 *       into YEAR/MON/DAY, the SSN into three parts, and each phone number into
 *       area/prefix/line sub-fields. This DTO composes them into
 *       {@link java.time.LocalDate}, a single nine-digit {@code ssn} string, and
 *       single {@code phoneNumber} strings; the split sub-fields are
 *       intentionally not carried.</li>
 *   <li><strong>Exact decimal fidelity.</strong> Every {@code PIC S9(10)V99}
 *       monetary field maps to {@link java.math.BigDecimal} constrained to at
 *       most ten integer digits and two fraction digits (scale 2). Binary
 *       floating-point ({@code float}/{@code double}) is prohibited for
 *       monetary values.</li>
 *   <li><strong>Optimistic concurrency.</strong> The {@code version} component
 *       carries the JPA {@code @Version} token read when the account was viewed,
 *       preserving the read-then-rewrite compare semantics of {@code COACTUPC};
 *       a stale token is surfaced as an optimistic-lock conflict (HTTP 409).</li>
 *   <li><strong>Stateless.</strong> No CICS {@code COMMAREA} /
 *       pseudo-conversational state is retained; the request is fully
 *       self-describing.</li>
 * </ul>
 *
 * <p>Optional components are validated for shape only when present: per the Bean
 * Validation specification, {@code @Size}, {@code @Pattern}, {@code @Digits},
 * {@code @Past}, {@code @PastOrPresent}, {@code @Min} and {@code @Max} all treat
 * a {@code null} value as valid. Only {@code accountId}, {@code firstName} and
 * {@code lastName} are strictly required. Full state-code and ZIP lookups
 * (legacy {@code CSLKPCDY}) and date-of-birth range checks (legacy
 * {@code CSUTLDPY}) are enforced by the service layer; this DTO enforces the
 * structural shape.</p>
 *
 * @param accountId account identifier; backs {@code ACCT-ID PIC 9(11)}; required, up to eleven digits
 * @param activeStatus account active flag; backs {@code ACCT-ACTIVE-STATUS PIC X(01)}; {@code "Y"} or {@code "N"}
 * @param currentBalance current account balance; backs {@code ACCT-CURR-BAL PIC S9(10)V99} (scale 2)
 * @param creditLimit total credit limit; backs {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} (scale 2)
 * @param cashCreditLimit cash credit limit; backs {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99} (scale 2)
 * @param currentCycleCredit current cycle credit total; backs {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} (scale 2)
 * @param currentCycleDebit current cycle debit total; backs {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} (scale 2)
 * @param openDate account open date; backs {@code ACCT-OPEN-DATE PIC X(10)}; must not be in the future
 * @param expirationDate account expiration date; backs {@code ACCT-EXPIRAION-DATE PIC X(10)}
 * @param reissueDate account reissue date; backs {@code ACCT-REISSUE-DATE PIC X(10)}
 * @param accountGroupId account group / disclosure code; backs {@code ACCT-GROUP-ID PIC X(10)}
 * @param version optimistic-lock version token read during account view; drives 409 conflict detection
 * @param customerId customer identifier; backs {@code CUST-ID PIC 9(09)}; up to nine digits
 * @param firstName customer first name; backs {@code CUST-FIRST-NAME PIC X(25)}; required
 * @param middleName customer middle name; backs {@code CUST-MIDDLE-NAME PIC X(25)}
 * @param lastName customer last name; backs {@code CUST-LAST-NAME PIC X(25)}; required
 * @param addressLine1 first address line; backs {@code CUST-ADDR-LINE-1 PIC X(50)}
 * @param addressLine2 second address line; backs {@code CUST-ADDR-LINE-2 PIC X(50)}
 * @param city city (third address line); backs {@code CUST-ADDR-LINE-3 PIC X(50)}
 * @param stateCode two-letter US state code; backs {@code CUST-ADDR-STATE-CD PIC X(02)}
 * @param countryCode country code; backs {@code CUST-ADDR-COUNTRY-CD PIC X(03)}
 * @param zipCode postal ZIP code; backs {@code CUST-ADDR-ZIP PIC X(10)}; up to ten digits
 * @param phoneNumber1 primary phone number; backs {@code CUST-PHONE-NUM-1 PIC X(15)}
 * @param phoneNumber2 secondary phone number; backs {@code CUST-PHONE-NUM-2 PIC X(15)}
 * @param ssn Social Security Number; backs {@code CUST-SSN PIC 9(09)}; exactly nine digits
 * @param govtIssuedId government-issued identifier; backs {@code CUST-GOVT-ISSUED-ID PIC X(20)}
 * @param dateOfBirth customer date of birth; backs {@code CUST-DOB-YYYY-MM-DD PIC X(10)}; must be strictly in the past
 * @param eftAccountId EFT account identifier; backs {@code CUST-EFT-ACCOUNT-ID PIC X(10)}
 * @param primaryCardHolderIndicator primary card-holder flag; backs {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}; {@code "Y"} or {@code "N"}
 * @param ficoScore FICO credit score; backs {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}; range 300&ndash;850
 */
public record AccountUpdateRequest(

        @NotBlank
        @Size(max = 11)
        @Pattern(regexp = "\\d{1,11}")
        String accountId,

        @Size(max = 1)
        @Pattern(regexp = "[YN]")
        String activeStatus,

        @Digits(integer = 10, fraction = 2)
        BigDecimal currentBalance,

        @Digits(integer = 10, fraction = 2)
        BigDecimal creditLimit,

        @Digits(integer = 10, fraction = 2)
        BigDecimal cashCreditLimit,

        @Digits(integer = 10, fraction = 2)
        BigDecimal currentCycleCredit,

        @Digits(integer = 10, fraction = 2)
        BigDecimal currentCycleDebit,

        @PastOrPresent
        LocalDate openDate,

        LocalDate expirationDate,

        LocalDate reissueDate,

        @Size(max = 10)
        String accountGroupId,

        Long version,

        @Size(max = 9)
        @Pattern(regexp = "\\d{1,9}")
        String customerId,

        @NotBlank
        @Size(max = 25)
        String firstName,

        @Size(max = 25)
        String middleName,

        @NotBlank
        @Size(max = 25)
        String lastName,

        @Size(max = 50)
        String addressLine1,

        @Size(max = 50)
        String addressLine2,

        @Size(max = 50)
        String city,

        @Size(max = 2)
        String stateCode,

        @Size(max = 3)
        String countryCode,

        @Size(max = 10)
        @Pattern(regexp = "\\d{0,10}")
        String zipCode,

        @Size(max = 15)
        String phoneNumber1,

        @Size(max = 15)
        String phoneNumber2,

        @Size(max = 9)
        @Pattern(regexp = "\\d{9}")
        String ssn,

        @Size(max = 20)
        String govtIssuedId,

        @Past
        LocalDate dateOfBirth,

        @Size(max = 10)
        String eftAccountId,

        @Size(max = 1)
        @Pattern(regexp = "[YN]")
        String primaryCardHolderIndicator,

        @Min(300)
        @Max(850)
        Integer ficoScore

) {
}
