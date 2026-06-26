package com.carddemo.unit.dto;

import java.math.BigDecimal;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.carddemo.dto.AccountDto;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure JUnit 5 unit test for {@link com.carddemo.dto.AccountDto}, the REST field-contract
 * carrier for the account view and update flows.
 *
 * <p>Behavioral-parity references (read-only @ SHA {@code 27d6c6f}): the {@code COACTVW} view
 * map ({@code app/bms/COACTVW.bms}, program {@code app/cbl/COACTVWC.cbl}) presents the account
 * dates, SSN and phone numbers as single, un-segmented fields, whereas the {@code COACTUP}
 * update map ({@code app/bms/COACTUP.bms}, program {@code app/cbl/COACTUPC.cbl}) presents the
 * same values as discrete segments — {@code OPNMON}/{@code OPNDAY}, {@code ACTSSN1}/{@code
 * ACTSSN2}/{@code ACTSSN3}, {@code ACSPH1A}/{@code ACSPH1B}/{@code ACSPH1C}, etc. The two
 * record shapes intentionally differ so that each map's external field contract is preserved
 * verbatim.</p>
 *
 * <p>These tests exercise {@link AccountDto.ViewResponse} and {@link AccountDto.UpdateRequest}
 * with no Spring context, no Mockito, no Testcontainers and no I/O — only a standalone Jakarta
 * Bean Validation {@link Validator}. The non-negotiable guarantees verified here are:</p>
 * <ul>
 *   <li>{@code ViewResponse} has EXACTLY 30 record components, in the documented order;</li>
 *   <li>both records expose EXACTLY five {@link BigDecimal} {@code @Digits(integer=10, fraction=2)}
 *       monetary fields ({@code creditLimit}, {@code cashCreditLimit}, {@code currentBalance},
 *       {@code currentCycleCredit}, {@code currentCycleDebit}) and contain NO floating-point
 *       component (decimal exactness, AAP 0.6.1);</li>
 *   <li>{@code UpdateRequest} is segmented — dates as year/month/day, the SSN as 3-2-4 parts and
 *       each phone as 3-3-4 parts — with NO merged {@code openDate}/{@code expirationDate}/
 *       {@code reissueDate}/{@code dateOfBirth}/{@code ssn}/{@code phone1}/{@code phone2}
 *       equivalents;</li>
 *   <li>{@code accountStatus} is a {@code String} of width 1 (NOT an enum);</li>
 *   <li>both records carry a {@code version} component — the JPA {@code @Version} optimistic-locking
 *       token surfaced so a stateless client can echo it and a stale cross-request update is rejected
 *       (HTTP 409), reproducing {@code 9300-CHECK-CHANGE-IN-REC};</li>
 *   <li>the declared {@code @Size}/{@code @Pattern}/{@code @Digits}/{@code @NotBlank} constraints
 *       behave at their documented boundaries.</li>
 * </ul>
 */
class AccountDtoTest {

    /** A monetary value that exactly fills the {@code @Digits(integer = 10, fraction = 2)} envelope. */
    private static final BigDecimal VALID_MONEY = new BigDecimal("1234567890.99");

    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    /** Ordered record-component names of the supplied record class. */
    private static List<String> names(Class<?> recordClass) {
        return Arrays.stream(recordClass.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /** Ordered names of the {@link BigDecimal}-typed components of the supplied record class. */
    private static List<String> bigDecimalComponentNames(Class<?> recordClass) {
        return Arrays.stream(recordClass.getRecordComponents())
                .filter(component -> component.getType() == BigDecimal.class)
                .map(RecordComponent::getName)
                .toList();
    }

    /** Asserts no component of the supplied record class is a floating-point type. */
    private static void assertNoFloatingPointComponents(Class<?> recordClass) {
        for (RecordComponent component : recordClass.getRecordComponents()) {
            assertThat(component.getType())
                    .as("component '%s' must not be floating-point (decimal exactness, AAP 0.6.1)",
                            component.getName())
                    .isNotIn(double.class, float.class, Double.class, Float.class);
        }
    }

    /**
     * Builds a {@link AccountDto.ViewResponse} that is fully valid except for the four
     * parameterized fields, so a single field-under-test can be driven to its boundary while
     * every other component stays within contract.
     */
    private static AccountDto.ViewResponse viewWith(String accountId, String state,
            BigDecimal creditLimit, BigDecimal currentBalance) {
        return new AccountDto.ViewResponse(
                accountId,
                "Y",
                "2024-01-01",
                creditLimit,
                "2025-01-01",
                VALID_MONEY,
                "2024-06-01",
                currentBalance,
                VALID_MONEY,
                "GROUP00001",
                VALID_MONEY,
                "123456789",
                "123-45-6789",
                "1990-01-01",
                "750",
                "John",
                "Q",
                "Public",
                "123 Main Street",
                state,
                "Apt 4",
                "90210",
                "Beverly Hills",
                "USA",
                "(123)456-7890",
                "GOVTID0001",
                "(123)456-7891",
                "EFT0000001",
                "Y",
                0L);
    }

    /**
     * Builds a {@link AccountDto.UpdateRequest} that is fully valid except for the eight
     * parameterized segments, so a single segment-under-test can be driven to its boundary while
     * every other component stays within contract.
     */
    private static AccountDto.UpdateRequest updateWith(String accountId, String openYear,
            String openMonth, String customerId, String ssnPart1, String ssnPart3,
            String phone1Area, String phone1Line) {
        return new AccountDto.UpdateRequest(
                accountId,
                "Y",
                openYear,
                openMonth,
                "01",
                "2025",
                "01",
                "01",
                "2024",
                "06",
                "01",
                "1990",
                "01",
                "01",
                VALID_MONEY,
                VALID_MONEY,
                VALID_MONEY,
                VALID_MONEY,
                "GROUP00001",
                VALID_MONEY,
                customerId,
                ssnPart1,
                "45",
                ssnPart3,
                "750",
                "John",
                "Q",
                "Public",
                "123 Main Street",
                "CA",
                "Apt 4",
                "90210",
                "Beverly Hills",
                "USA",
                phone1Area,
                "456",
                phone1Line,
                "123",
                "456",
                "7890",
                "GOVTID0001",
                "EFT0000001",
                "Y",
                0L);
    }

    /** A fully contract-valid view response. */
    private static AccountDto.ViewResponse validViewResponse() {
        return viewWith("12345678901", "CA", VALID_MONEY, VALID_MONEY);
    }

    /** A fully contract-valid update request. */
    private static AccountDto.UpdateRequest validUpdateRequest() {
        return updateWith("12345678901", "2024", "01", "123456789", "123", "6789", "123", "7890");
    }

    // ---------------------------------------------------------------------
    // Phase 2 — ViewResponse shape (the headline 29-field contract)
    // ---------------------------------------------------------------------

    @Test
    void viewResponseHasExactlyThirtyComponents() {
        assertThat(AccountDto.ViewResponse.class.getRecordComponents()).hasSize(30);
    }

    @Test
    void viewResponseComponentsInOrder() {
        assertThat(names(AccountDto.ViewResponse.class)).containsExactly(
                "accountId", "accountStatus", "openDate", "creditLimit", "expirationDate",
                "cashCreditLimit", "reissueDate", "currentBalance", "currentCycleCredit", "accountGroupId",
                "currentCycleDebit", "customerId", "ssn", "dateOfBirth", "ficoScore",
                "firstName", "middleName", "lastName", "addressLine1", "state",
                "addressLine2", "zipCode", "city", "country", "phone1",
                "governmentId", "phone2", "eftAccountId", "primaryCardHolder", "version");
    }

    @Test
    void viewResponseHasFiveBigDecimalMoneyFields() {
        assertThat(bigDecimalComponentNames(AccountDto.ViewResponse.class)).containsExactly(
                "creditLimit", "cashCreditLimit", "currentBalance", "currentCycleCredit", "currentCycleDebit");
    }

    @Test
    void accountStatusIsStringNotEnum() {
        RecordComponent accountStatus = Arrays.stream(AccountDto.ViewResponse.class.getRecordComponents())
                .filter(component -> "accountStatus".equals(component.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(accountStatus.getType()).isEqualTo(String.class);
        assertThat(accountStatus.getType().isEnum()).isFalse();
    }

    // ---------------------------------------------------------------------
    // Phase 3 — UpdateRequest segmentation (fidelity proof)
    // ---------------------------------------------------------------------

    @Test
    void updateRequestHasSegmentedDates() {
        List<String> componentNames = names(AccountDto.UpdateRequest.class);
        assertThat(componentNames).contains(
                "openYear", "openMonth", "openDay",
                "expiryYear", "expiryMonth", "expiryDay",
                "reissueYear", "reissueMonth", "reissueDay",
                "dobYear", "dobMonth", "dobDay");
        assertThat(componentNames).doesNotContain(
                "openDate", "expirationDate", "reissueDate", "dateOfBirth");
    }

    @Test
    void updateRequestHasSsnSplitThreeTwoFour() {
        List<String> componentNames = names(AccountDto.UpdateRequest.class);
        assertThat(componentNames).contains("ssnPart1", "ssnPart2", "ssnPart3");
        assertThat(componentNames).doesNotContain("ssn");
    }

    @Test
    void updateRequestHasPhoneSplitThreeThreeFour() {
        List<String> componentNames = names(AccountDto.UpdateRequest.class);
        assertThat(componentNames).contains(
                "phone1Area", "phone1Prefix", "phone1Line",
                "phone2Area", "phone2Prefix", "phone2Line");
        assertThat(componentNames).doesNotContain("phone1", "phone2");
    }

    @Test
    void bothRecordsHaveVersionField() {
        assertThat(names(AccountDto.ViewResponse.class)).contains("version");
        assertThat(names(AccountDto.UpdateRequest.class)).contains("version");
    }

    @Test
    void updateRequestHasFiveBigDecimalMoneyFields() {
        assertThat(bigDecimalComponentNames(AccountDto.UpdateRequest.class)).containsExactly(
                "creditLimit", "cashCreditLimit", "currentBalance", "currentCycleCredit", "currentCycleDebit");
    }

    // ---------------------------------------------------------------------
    // Phase 4 — No floating-point components on either record
    // ---------------------------------------------------------------------

    @Test
    void noFloatingPointComponents() {
        assertNoFloatingPointComponents(AccountDto.ViewResponse.class);
        assertNoFloatingPointComponents(AccountDto.UpdateRequest.class);
    }

    // ---------------------------------------------------------------------
    // Phase 5 — Bean validation: valid instances pass; boundary violations
    // report the exact offending property path
    // ---------------------------------------------------------------------

    @Test
    void viewResponseValidInstancePasses() {
        Set<ConstraintViolation<AccountDto.ViewResponse>> violations = validator.validate(validViewResponse());
        assertThat(violations).isEmpty();
    }

    @Test
    void updateRequestValidInstancePasses() {
        Set<ConstraintViolation<AccountDto.UpdateRequest>> violations = validator.validate(validUpdateRequest());
        assertThat(violations).isEmpty();
    }

    @Test
    void creditLimitOverScaleFailsDigits() {
        Set<ConstraintViolation<AccountDto.ViewResponse>> tooManyFractionDigits =
                validator.validate(viewWith("12345678901", "CA", new BigDecimal("1.234"), VALID_MONEY));
        assertThat(tooManyFractionDigits)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsOnly("creditLimit");

        Set<ConstraintViolation<AccountDto.ViewResponse>> withinScale =
                validator.validate(viewWith("12345678901", "CA", new BigDecimal("1234567890.99"), VALID_MONEY));
        assertThat(withinScale).isEmpty();
    }

    @Test
    void currentBalanceElevenIntegerDigitsFailsDigits() {
        Set<ConstraintViolation<AccountDto.ViewResponse>> tooManyIntegerDigits =
                validator.validate(viewWith("12345678901", "CA", VALID_MONEY, new BigDecimal("12345678901.12")));
        assertThat(tooManyIntegerDigits)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsOnly("currentBalance");
    }

    @Test
    void updateRequestBlankAccountIdFails() {
        Set<ConstraintViolation<AccountDto.UpdateRequest>> violations =
                validator.validate(updateWith("", "2024", "01", "123456789", "123", "6789", "123", "7890"));
        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsOnly("accountId");
    }

    @Test
    void ssnPart1ThreeDigitBoundary() {
        Set<ConstraintViolation<AccountDto.UpdateRequest>> tooLong =
                validator.validate(updateWith("12345678901", "2024", "01", "123456789", "1234", "6789", "123", "7890"));
        assertThat(tooLong)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsOnly("ssnPart1");

        Set<ConstraintViolation<AccountDto.UpdateRequest>> withinWidth =
                validator.validate(updateWith("12345678901", "2024", "01", "123456789", "123", "6789", "123", "7890"));
        assertThat(withinWidth).isEmpty();
    }

    @Test
    void ssnPart3FourDigitBoundary() {
        Set<ConstraintViolation<AccountDto.UpdateRequest>> tooLong =
                validator.validate(updateWith("12345678901", "2024", "01", "123456789", "123", "12345", "123", "7890"));
        assertThat(tooLong)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsOnly("ssnPart3");

        Set<ConstraintViolation<AccountDto.UpdateRequest>> withinWidth =
                validator.validate(updateWith("12345678901", "2024", "01", "123456789", "123", "6789", "123", "7890"));
        assertThat(withinWidth).isEmpty();
    }

    @Test
    void phone1AreaThreeDigitBoundary() {
        Set<ConstraintViolation<AccountDto.UpdateRequest>> tooLong =
                validator.validate(updateWith("12345678901", "2024", "01", "123456789", "123", "6789", "1234", "7890"));
        assertThat(tooLong)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsOnly("phone1Area");

        Set<ConstraintViolation<AccountDto.UpdateRequest>> withinWidth =
                validator.validate(updateWith("12345678901", "2024", "01", "123456789", "123", "6789", "123", "7890"));
        assertThat(withinWidth).isEmpty();
    }

    @Test
    void phone1LineFourDigitBoundary() {
        Set<ConstraintViolation<AccountDto.UpdateRequest>> tooLong =
                validator.validate(updateWith("12345678901", "2024", "01", "123456789", "123", "6789", "123", "12345"));
        assertThat(tooLong)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsOnly("phone1Line");

        Set<ConstraintViolation<AccountDto.UpdateRequest>> withinWidth =
                validator.validate(updateWith("12345678901", "2024", "01", "123456789", "123", "6789", "123", "7890"));
        assertThat(withinWidth).isEmpty();
    }

    @Test
    void openYearFiveDigitsFailsSize() {
        Set<ConstraintViolation<AccountDto.UpdateRequest>> tooLong =
                validator.validate(updateWith("12345678901", "20245", "01", "123456789", "123", "6789", "123", "7890"));
        assertThat(tooLong)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsOnly("openYear");

        Set<ConstraintViolation<AccountDto.UpdateRequest>> withinWidth =
                validator.validate(updateWith("12345678901", "2024", "01", "123456789", "123", "6789", "123", "7890"));
        assertThat(withinWidth).isEmpty();
    }

    @Test
    void openMonthThreeDigitsFailsSize() {
        Set<ConstraintViolation<AccountDto.UpdateRequest>> tooLong =
                validator.validate(updateWith("12345678901", "2024", "123", "123456789", "123", "6789", "123", "7890"));
        assertThat(tooLong)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsOnly("openMonth");

        Set<ConstraintViolation<AccountDto.UpdateRequest>> withinWidth =
                validator.validate(updateWith("12345678901", "2024", "12", "123456789", "123", "6789", "123", "7890"));
        assertThat(withinWidth).isEmpty();
    }

    @Test
    void customerIdNonDigitFailsPattern() {
        Set<ConstraintViolation<AccountDto.UpdateRequest>> nonNumeric =
                validator.validate(updateWith("12345678901", "2024", "01", "12A", "123", "6789", "123", "7890"));
        assertThat(nonNumeric)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsOnly("customerId");

        Set<ConstraintViolation<AccountDto.UpdateRequest>> numeric =
                validator.validate(updateWith("12345678901", "2024", "01", "123456789", "123", "6789", "123", "7890"));
        assertThat(numeric).isEmpty();
    }

    @Test
    void stateWidthTwo() {
        Set<ConstraintViolation<AccountDto.ViewResponse>> tooLong =
                validator.validate(viewWith("12345678901", "ABC", VALID_MONEY, VALID_MONEY));
        assertThat(tooLong)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsOnly("state");

        Set<ConstraintViolation<AccountDto.ViewResponse>> withinWidth =
                validator.validate(viewWith("12345678901", "CA", VALID_MONEY, VALID_MONEY));
        assertThat(withinWidth).isEmpty();
    }
}
