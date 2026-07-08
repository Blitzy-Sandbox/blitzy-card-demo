package com.carddemo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link AccountUpdateRequest}, the REST request body for the
 * Account Update use case (legacy CICS transaction <strong>CAUP</strong>, COBOL
 * program {@code COACTUPC}). The DTO is the headless replacement for the
 * {@code COACTUP} BMS screen ({@code app/cpy-bms/COACTUP.CPY}) and carries the
 * editable account and customer attributes backed by the {@code ACCOUNT-RECORD}
 * ({@code CVACT01Y}) and {@code CUSTOMER-RECORD} ({@code CVCUS01Y}) copybooks.
 *
 * <p>This is a <strong>validation-heavy</strong> DTO, so the tests concentrate on
 * proving that the Jakarta Bean-Validation constraints reproduce the field edits
 * the legacy {@code CICS RECEIVE MAP} performed, exactly as production declares
 * them. The mappings under test derive from the read-only legacy copybooks
 * (referenced by source SHA {@code 27d6c6f}; no COBOL source is reproduced):</p>
 * <ul>
 *   <li><strong>Money</strong> — the five {@code PIC S9(10)V99} account amounts
 *       ({@code ACCT-CURR-BAL} and friends) map to {@link BigDecimal} fields
 *       constrained by {@code @Digits(integer = 10, fraction = 2)}; binary
 *       floating point is prohibited for money.</li>
 *   <li><strong>FICO score</strong> — {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}
 *       maps to an {@link Integer} bounded by {@code @Min(300)} and
 *       {@code @Max(850)}.</li>
 *   <li><strong>SSN</strong> — {@code CUST-SSN PIC 9(09)} maps to a nine-digit
 *       {@link String} constrained by {@code @Pattern("\\d{9}")} (and
 *       {@code @Size(max = 9)}).</li>
 *   <li><strong>Dates</strong> — {@code ACCT-OPEN-DATE PIC X(10)} maps to an
 *       {@code openDate} constrained {@code @PastOrPresent}, while
 *       {@code CUST-DOB-YYYY-MM-DD PIC X(10)} maps to a {@code dateOfBirth}
 *       constrained strictly {@code @Past}. The other two account dates
 *       ({@code expirationDate}, {@code reissueDate}) intentionally carry no
 *       temporal constraint, so they are not asserted here.</li>
 * </ul>
 *
 * <p>Per the Bean Validation specification, {@code @Digits}, {@code @Min},
 * {@code @Max}, {@code @Pattern}, {@code @Past} and {@code @PastOrPresent} all
 * treat a {@code null} value as valid, so every optional component may be omitted;
 * this is proven by {@link #minimalRequestWithNullOptionalFieldsHasNoViolations()}.</p>
 *
 * <p>The tests are deliberately pure and framework-free: they use the shared
 * programmatic {@link jakarta.validation.Validator} exposed by
 * {@link DtoTestSupport} — no Spring context, no Testcontainers, and no mocks — so
 * they run in milliseconds and contribute fast line coverage toward the Gate&nbsp;8
 * (&ge;80%) JaCoCo threshold. Each invalid case asserts the offending
 * {@link ConstraintViolation#getPropertyPath() property path}, the offending
 * constraint annotation, and (where deterministic) the violation count, so an
 * accidental annotation change breaks the build. The credential-shaped literals
 * used below (SSN, account and customer ids) are obvious, non-secret test
 * fixtures. Rationale is documented in {@code docs/decision-log.md}.</p>
 */
@DisplayName("AccountUpdateRequest — Jakarta validation (money, FICO, SSN, dates) and SSN masking")
class AccountUpdateRequestTest {

    // ------------------------------------------------------------------
    // Valid fixture values. Each satisfies its production constraint so a
    // request built from them (see valid()) has zero violations; individual
    // tests substitute exactly one component to isolate a single constraint.
    // ------------------------------------------------------------------

    /** {@code ACCT-ID PIC 9(11)} at its eleven-digit width. */
    private static final String VALID_ACCOUNT_ID = "12345678901";

    /** {@code ACCT-ACTIVE-STATUS PIC X(01)} — active. */
    private static final String VALID_ACTIVE_STATUS = "Y";

    /** A canonical scale-2 money value ({@code 100.00}) within {@code @Digits(10, 2)}. */
    private static final BigDecimal VALID_MONEY = new BigDecimal("100.00");

    /** {@code ACCT-EXPIRAION-DATE} — unconstrained, so any value is acceptable. */
    private static final LocalDate VALID_EXPIRATION_DATE = LocalDate.of(2030, 12, 31);

    /** {@code ACCT-REISSUE-DATE} — unconstrained, so any value is acceptable. */
    private static final LocalDate VALID_REISSUE_DATE = LocalDate.of(2025, 6, 1);

    /** {@code ACCT-GROUP-ID PIC X(10)} within its width. */
    private static final String VALID_GROUP_ID = "GRP001";

    /** Optimistic-lock token read at account view; unconstrained. */
    private static final Long VALID_VERSION = 0L;

    /**
     * {@code CUST-ID PIC 9(09)} — intentionally distinct from {@link #VALID_SSN}
     * so the SSN-masking assertions cannot be satisfied by a colliding id.
     */
    private static final String VALID_CUSTOMER_ID = "987654321";

    /** {@code CUST-FIRST-NAME PIC X(25)}; required (@NotBlank). */
    private static final String VALID_FIRST_NAME = "John";

    /** {@code CUST-MIDDLE-NAME PIC X(25)}. */
    private static final String VALID_MIDDLE_NAME = "Quincy";

    /** {@code CUST-LAST-NAME PIC X(25)}; required (@NotBlank). */
    private static final String VALID_LAST_NAME = "Doe";

    /** {@code CUST-ADDR-LINE-1 PIC X(50)}. */
    private static final String VALID_ADDRESS_LINE_1 = "123 Main Street";

    /** {@code CUST-ADDR-LINE-2 PIC X(50)}. */
    private static final String VALID_ADDRESS_LINE_2 = "Suite 100";

    /** {@code CUST-ADDR-LINE-3 PIC X(50)} (city). */
    private static final String VALID_CITY = "Springfield";

    /** {@code CUST-ADDR-STATE-CD PIC X(02)}. */
    private static final String VALID_STATE_CODE = "IL";

    /** {@code CUST-ADDR-COUNTRY-CD PIC X(03)}. */
    private static final String VALID_COUNTRY_CODE = "USA";

    /** {@code CUST-ADDR-ZIP PIC X(10)} — digits only, within width. */
    private static final String VALID_ZIP_CODE = "62704";

    /** {@code CUST-PHONE-NUM-1 PIC X(15)}. */
    private static final String VALID_PHONE_NUMBER_1 = "5551234567";

    /** {@code CUST-PHONE-NUM-2 PIC X(15)}. */
    private static final String VALID_PHONE_NUMBER_2 = "5559876543";

    /** {@code CUST-SSN PIC 9(09)} — exactly nine digits. */
    private static final String VALID_SSN = "123456789";

    /** {@code CUST-GOVT-ISSUED-ID PIC X(20)}. */
    private static final String VALID_GOVT_ISSUED_ID = "DL1234567890";

    /** {@code CUST-DOB-YYYY-MM-DD PIC X(10)} — strictly in the past (@Past). */
    private static final LocalDate VALID_DATE_OF_BIRTH = LocalDate.of(1980, 6, 15);

    /** {@code CUST-EFT-ACCOUNT-ID PIC X(10)} at its ten-character width. */
    private static final String VALID_EFT_ACCOUNT_ID = "EFT0000001";

    /** {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}. */
    private static final String VALID_PRI_CARD_HOLDER_IND = "Y";

    /** {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} — mid-range, within 300&ndash;850. */
    private static final int VALID_FICO = 700;

    /** {@code ACCT-OPEN-DATE PIC X(10)} — in the past (@PastOrPresent). */
    private static final LocalDate VALID_OPEN_DATE = LocalDate.of(2000, 1, 15);

    // ------------------------------------------------------------------
    // Factory helpers. build(...) writes the 30-component canonical
    // constructor exactly once with all-valid defaults, taking the small set
    // of components the tests actually vary; the thin wrappers substitute a
    // single component so every non-empty result isolates one constraint.
    // ------------------------------------------------------------------

    /**
     * Builds an {@link AccountUpdateRequest} that is fully valid except for the
     * components supplied as arguments (which the caller varies to exercise a
     * single constraint at a time). Every other component uses a valid fixture,
     * guaranteeing that any resulting violation is attributable to an argument.
     *
     * @param currentBalance the {@code currentBalance} money component
     * @param ficoScore      the {@code ficoScore} component
     * @param ssn            the {@code ssn} component
     * @param openDate       the {@code openDate} component
     * @param dateOfBirth    the {@code dateOfBirth} component
     * @return a request populated with the given components and valid defaults
     */
    private static AccountUpdateRequest build(
            BigDecimal currentBalance,
            Integer ficoScore,
            String ssn,
            LocalDate openDate,
            LocalDate dateOfBirth) {
        return new AccountUpdateRequest(
                VALID_ACCOUNT_ID,          // accountId
                VALID_ACTIVE_STATUS,       // activeStatus
                currentBalance,            // currentBalance (under test)
                VALID_MONEY,               // creditLimit
                VALID_MONEY,               // cashCreditLimit
                VALID_MONEY,               // currentCycleCredit
                VALID_MONEY,               // currentCycleDebit
                openDate,                  // openDate (under test)
                VALID_EXPIRATION_DATE,     // expirationDate
                VALID_REISSUE_DATE,        // reissueDate
                VALID_GROUP_ID,            // accountGroupId
                VALID_VERSION,             // version
                VALID_CUSTOMER_ID,         // customerId
                VALID_FIRST_NAME,          // firstName
                VALID_MIDDLE_NAME,         // middleName
                VALID_LAST_NAME,           // lastName
                VALID_ADDRESS_LINE_1,      // addressLine1
                VALID_ADDRESS_LINE_2,      // addressLine2
                VALID_CITY,                // city
                VALID_STATE_CODE,          // stateCode
                VALID_COUNTRY_CODE,        // countryCode
                VALID_ZIP_CODE,            // zipCode
                VALID_PHONE_NUMBER_1,      // phoneNumber1
                VALID_PHONE_NUMBER_2,      // phoneNumber2
                ssn,                       // ssn (under test)
                VALID_GOVT_ISSUED_ID,      // govtIssuedId
                dateOfBirth,               // dateOfBirth (under test)
                VALID_EFT_ACCOUNT_ID,      // eftAccountId
                VALID_PRI_CARD_HOLDER_IND, // primaryCardHolderIndicator
                ficoScore);                // ficoScore (under test)
    }

    /** A fully valid request (every component within its constraint). */
    private static AccountUpdateRequest valid() {
        return build(VALID_MONEY, VALID_FICO, VALID_SSN, VALID_OPEN_DATE, VALID_DATE_OF_BIRTH);
    }

    /** A valid request with {@code currentBalance} substituted. */
    private static AccountUpdateRequest withCurrentBalance(BigDecimal currentBalance) {
        return build(currentBalance, VALID_FICO, VALID_SSN, VALID_OPEN_DATE, VALID_DATE_OF_BIRTH);
    }

    /** A valid request with {@code ficoScore} substituted. */
    private static AccountUpdateRequest withFicoScore(Integer ficoScore) {
        return build(VALID_MONEY, ficoScore, VALID_SSN, VALID_OPEN_DATE, VALID_DATE_OF_BIRTH);
    }

    /** A valid request with {@code ssn} substituted. */
    private static AccountUpdateRequest withSsn(String ssn) {
        return build(VALID_MONEY, VALID_FICO, ssn, VALID_OPEN_DATE, VALID_DATE_OF_BIRTH);
    }

    /** A valid request with {@code openDate} substituted. */
    private static AccountUpdateRequest withOpenDate(LocalDate openDate) {
        return build(VALID_MONEY, VALID_FICO, VALID_SSN, openDate, VALID_DATE_OF_BIRTH);
    }

    /** A valid request with {@code dateOfBirth} substituted. */
    private static AccountUpdateRequest withDateOfBirth(LocalDate dateOfBirth) {
        return build(VALID_MONEY, VALID_FICO, VALID_SSN, VALID_OPEN_DATE, dateOfBirth);
    }

    /**
     * Builds a request that sets only the three strictly-required components
     * ({@code accountId}, {@code firstName}, {@code lastName}) and leaves every
     * optional component {@code null}, proving the optional-when-null contract.
     *
     * @return a minimal, valid request
     */
    private static AccountUpdateRequest minimalValid() {
        return new AccountUpdateRequest(
                VALID_ACCOUNT_ID, null, null, null, null, null, null, null, null, null,
                null, null, null, VALID_FIRST_NAME, null, VALID_LAST_NAME, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    // ------------------------------------------------------------------
    // Phase 1 — a fully valid instance (and the minimal null-optional case).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A fully-populated, well-formed request has zero violations")
    void fullyValidRequestHasNoViolations() {
        Set<ConstraintViolation<AccountUpdateRequest>> violations = DtoTestSupport.validate(valid());

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("A minimal request (only required id/names set, all optional fields null) has zero violations")
    void minimalRequestWithNullOptionalFieldsHasNoViolations() {
        Set<ConstraintViolation<AccountUpdateRequest>> violations = DtoTestSupport.validate(minimalValid());

        assertThat(violations).isEmpty();
    }

    // ------------------------------------------------------------------
    // Phase 2 — FICO score bounds: @Min(300) / @Max(850) on 'ficoScore'.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ficoScore below the @Min(300) floor (299) violates @Min on 'ficoScore'")
    void ficoScoreBelowMinimumViolatesMin() {
        Set<ConstraintViolation<AccountUpdateRequest>> violations = DtoTestSupport.validate(withFicoScore(299));

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo("ficoScore");
            assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Min.class);
        });
    }

    @Test
    @DisplayName("ficoScore above the @Max(850) ceiling (851) violates @Max on 'ficoScore'")
    void ficoScoreAboveMaximumViolatesMax() {
        Set<ConstraintViolation<AccountUpdateRequest>> violations = DtoTestSupport.validate(withFicoScore(851));

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo("ficoScore");
            assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Max.class);
        });
    }

    @ParameterizedTest(name = "ficoScore = {0} (inclusive boundary) is accepted")
    @ValueSource(ints = {300, 850})
    @DisplayName("ficoScore at the inclusive @Min/@Max boundaries (300 and 850) yields no violation")
    void ficoScoreAtBoundariesHasNoViolation(int boundaryScore) {
        Set<ConstraintViolation<AccountUpdateRequest>> violations = DtoTestSupport.validate(withFicoScore(boundaryScore));

        assertThat(violations).isEmpty();
    }

    // ------------------------------------------------------------------
    // Phase 3 — SSN pattern: @Pattern("\\d{9}") (with @Size(max = 9)) on 'ssn'.
    // An eight-digit or non-digit value fails @Pattern only; a ten-character
    // value fails BOTH @Size and @Pattern (two violations, both on 'ssn').
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "ssn = \"{0}\" (not exactly nine digits) violates @Pattern on 'ssn'")
    @ValueSource(strings = {"12345678", "12345678X"})
    @DisplayName("An SSN of 8 digits, or 9 characters with a non-digit, violates @Pattern only (single violation on 'ssn')")
    void ssnWithWrongShapeViolatesPatternOnly(String malformedSsn) {
        Set<ConstraintViolation<AccountUpdateRequest>> violations = DtoTestSupport.validate(withSsn(malformedSsn));

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo("ssn");
            assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Pattern.class);
        });
    }

    @Test
    @DisplayName("A ten-digit SSN exceeds @Size(max = 9) AND fails @Pattern — exactly two violations, both on 'ssn'")
    void ssnTooLongViolatesSizeAndPattern() {
        Set<ConstraintViolation<AccountUpdateRequest>> violations = DtoTestSupport.validate(withSsn("1234567890"));

        // Deterministic: exactly two violations, both on 'ssn', one @Size and one
        // @Pattern. Asserted with single-argument all/any-Satisfy consumers (rather
        // than extracting to a Class<? extends Annotation> collection) to stay clear
        // of an unchecked generic-array-creation warning under -Xlint:all.
        assertThat(violations).hasSize(2);
        assertThat(violations).allSatisfy(violation ->
                assertThat(violation.getPropertyPath().toString()).isEqualTo("ssn"));
        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Size.class));
        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Pattern.class));
    }

    @Test
    @DisplayName("A valid nine-digit SSN yields no violation")
    void validNineDigitSsnHasNoViolation() {
        Set<ConstraintViolation<AccountUpdateRequest>> violations = DtoTestSupport.validate(withSsn("123456789"));

        assertThat(violations).isEmpty();
    }

    // ------------------------------------------------------------------
    // Phase 4 — money precision: @Digits(integer = 10, fraction = 2) on the
    // five S9(10)V99 amounts; 'currentBalance' is exercised as representative.
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "currentBalance = {0} violates @Digits(integer = 10, fraction = 2)")
    @ValueSource(strings = {"100.123", "12345678901.00", "12345678901.999"})
    @DisplayName("Money exceeding 10 integer or 2 fraction digits violates @Digits on 'currentBalance'")
    void moneyExceedingDigitsBoundsViolatesDigits(String invalidMoney) {
        Set<ConstraintViolation<AccountUpdateRequest>> violations =
                DtoTestSupport.validate(withCurrentBalance(new BigDecimal(invalidMoney)));

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo("currentBalance");
            assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Digits.class);
        });
    }

    @ParameterizedTest(name = "currentBalance = {0} is within @Digits(integer = 10, fraction = 2)")
    @ValueSource(strings = {"0.00", "100.00", "9999999999.99"})
    @DisplayName("Money within 10 integer and 2 fraction digits (including the boundary) yields no violation")
    void moneyWithinDigitsBoundsHasNoViolation(String validMoney) {
        Set<ConstraintViolation<AccountUpdateRequest>> violations =
                DtoTestSupport.validate(withCurrentBalance(new BigDecimal(validMoney)));

        assertThat(violations).isEmpty();
    }

    // ------------------------------------------------------------------
    // Phase 5 — date constraints. 'openDate' is @PastOrPresent (today is
    // accepted); 'dateOfBirth' is strictly @Past (today is rejected). Only
    // these two date components carry a temporal constraint in production.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A future openDate violates @PastOrPresent on 'openDate'")
    void futureOpenDateViolatesPastOrPresent() {
        Set<ConstraintViolation<AccountUpdateRequest>> violations =
                DtoTestSupport.validate(withOpenDate(LocalDate.now().plusDays(1)));

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo("openDate");
            assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(PastOrPresent.class);
        });
    }

    @Test
    @DisplayName("Today's date is accepted for openDate (@PastOrPresent allows the present)")
    void presentOpenDateHasNoViolation() {
        Set<ConstraintViolation<AccountUpdateRequest>> violations =
                DtoTestSupport.validate(withOpenDate(LocalDate.now()));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("A future dateOfBirth violates @Past on 'dateOfBirth'")
    void futureDateOfBirthViolatesPast() {
        Set<ConstraintViolation<AccountUpdateRequest>> violations =
                DtoTestSupport.validate(withDateOfBirth(LocalDate.now().plusDays(1)));

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo("dateOfBirth");
            assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Past.class);
        });
    }

    @Test
    @DisplayName("Today's date violates @Past on 'dateOfBirth' (strictly-past: the present is rejected)")
    void presentDateOfBirthViolatesPast() {
        Set<ConstraintViolation<AccountUpdateRequest>> violations =
                DtoTestSupport.validate(withDateOfBirth(LocalDate.now()));

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo("dateOfBirth");
            assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Past.class);
        });
    }

    // ------------------------------------------------------------------
    // SSN masking (sensitive-data safety) and record-accessor fidelity. The
    // record's toString() must never render the full CUST-SSN value; the
    // canonical accessors must return the exact constructor arguments.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("toString() masks the SSN to its last four digits and never leaks the full value")
    void toStringMasksSsn() {
        String rendered = withSsn("444556666").toString();

        assertThat(rendered)
                .as("toString() must not leak the full nine-digit SSN")
                .doesNotContain("444556666");
        assertThat(rendered)
                .as("toString() must mask all but the trailing four SSN digits")
                .doesNotContain("44455");
        assertThat(rendered)
                .as("toString() should retain the trailing four SSN digits")
                .contains("6666");
        assertThat(rendered)
                .as("toString() should still expose non-sensitive components such as the account id")
                .contains(VALID_ACCOUNT_ID);
    }

    @Test
    @DisplayName("Record accessors return the exact constructor arguments (masking is presentation-only)")
    void accessorsReturnConstructorValues() {
        AccountUpdateRequest request = valid();

        assertThat(request.accountId()).isEqualTo(VALID_ACCOUNT_ID);
        assertThat(request.ssn()).isEqualTo(VALID_SSN);
        assertThat(request.ficoScore()).isEqualTo(VALID_FICO);
        assertThat(request.currentBalance()).isEqualByComparingTo(VALID_MONEY);
        assertThat(request.openDate()).isEqualTo(VALID_OPEN_DATE);
        assertThat(request.dateOfBirth()).isEqualTo(VALID_DATE_OF_BIRTH);
    }
}
