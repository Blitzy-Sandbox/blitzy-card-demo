package com.carddemo.unit.dto;

import com.carddemo.dto.StatementDto;
import com.carddemo.enums.TransactionTypeCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure JUnit 5 unit tests for {@link StatementDto}.
 *
 * <p>This is the fastest test tier in the AWS CardDemo COBOL&#8594;Java
 * migration: it exercises a single in-memory {@code record} plus the standalone
 * Jakarta Bean Validation {@link Validator}. There is <em>no</em>
 * {@code @SpringBootTest}, Spring context, Testcontainers, or Mockito, and
 * nothing is read from disk at runtime &mdash; every expected literal is derived
 * from the frozen parity sources read at authoring time (commit SHA
 * {@code 27d6c6f}).
 *
 * <p>Parity source (REFERENCE only, never copied into the codebase):
 * {@code app/cpy/COSTM01.CPY} &mdash; the {@code 01 TRNX-RECORD} reporting
 * layout. That group contains thirteen data fields ({@code TRNX-CARD-NUM}
 * &hellip; {@code TRNX-PROC-TS}) plus a trailing {@code FILLER PIC X(20)}; the
 * filler carries no business meaning and is intentionally excluded, so the
 * record under test has exactly thirteen components.
 *
 * <p><strong>Contract deviations bound to the real source.</strong> The
 * authoritative agent contract instructs that assertions bind to the real
 * component names, order, types, and annotations of the dependency. Two fields
 * in the real {@link StatementDto} differ from the documented expectation, and
 * each is flagged with an inline {@code // NOTE} at its assertion:
 * <ul>
 *   <li>{@code transactionType} is modelled as a validated two-character
 *       {@code String} ({@code @Size(max = 2) @Pattern("\\d{2}")}) rather than
 *       a {@link TransactionTypeCode} enum. The legacy two-character
 *       {@code TRNX-TYPE-CD} contract is preserved by the size/pattern
 *       constraints, and this test ties the string value back to
 *       {@link TransactionTypeCode#fromCode(String)}.</li>
 *   <li>{@code amount} carries {@code @Digits(integer = 10, fraction = 2)};
 *       the COBOL {@code TRNX-AMT PIC S9(09)V99} is nine integer digits, so the
 *       boundary assertions cover both the nine-digit COBOL width and the
 *       real ten-digit bound.</li>
 * </ul>
 */
@DisplayName("StatementDto record - COSTM01 TRNX-RECORD (13 components, FILLER excluded)")
class StatementDtoTest {

    /**
     * The thirteen record components in their canonical declaration order, used
     * by the record-shape assertions. The trailing COBOL {@code FILLER X(20)}
     * is deliberately absent.
     */
    private static final String[] EXPECTED_COMPONENTS = {
        "cardNumber", "transactionId", "transactionType", "categoryCode",
        "source", "description", "amount", "merchantId", "merchantName",
        "merchantCity", "merchantZip", "originTimestamp", "processTimestamp"
    };

    // Fully-valid field values: each sits within its real Bean Validation bounds
    // so that validDto() produces zero constraint violations.
    private static final String VALID_CARD_NUMBER = "1234567890123456";   // 16 digits, @Size(max=16) + \d{0,16}
    private static final String VALID_TRANSACTION_ID = "0000000000000001"; // 16 chars, @Size(max=16)
    private static final String VALID_TRANSACTION_TYPE = "01";             // 2 digits, @Size(max=2) + \d{2}
    private static final String VALID_CATEGORY_CODE = "0001";              // 1-4 digits, @Size(max=4) + \d{1,4}
    private static final String VALID_SOURCE = "POS";                      // <=10, @Size(max=10)
    private static final String VALID_DESCRIPTION = "Test purchase transaction"; // <=100, @Size(max=100)
    private static final BigDecimal VALID_AMOUNT = new BigDecimal("123456789.99"); // 9 int + 2 frac
    private static final String VALID_MERCHANT_ID = "123456789";           // 9 digits, @Size(max=9) + \d{1,9}
    private static final String VALID_MERCHANT_NAME = "Acme Store";        // <=50, @Size(max=50)
    private static final String VALID_MERCHANT_CITY = "Seattle";           // <=50, @Size(max=50)
    private static final String VALID_MERCHANT_ZIP = "98101";              // <=10, @Size(max=10)
    private static final String VALID_TIMESTAMP = "2024-01-15 12:30:45.123456"; // 26 chars, @Size(max=26)

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    /**
     * Builds the standalone Jakarta Bean Validation engine once for the class.
     * The factory is retained in a static field (and closed in
     * {@link #closeValidator()}) so the engine is reused across tests without a
     * dangling {@link AutoCloseable}.
     */
    @BeforeAll
    static void initValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    /**
     * Releases the validation engine and its underlying expression-language
     * resources after all tests have run.
     */
    @AfterAll
    static void closeValidator() {
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    /**
     * Builds a {@link StatementDto} whose every field is within its real
     * validation bounds, producing zero constraint violations.
     *
     * @return a fully-valid statement DTO
     */
    private static StatementDto validDto() {
        return new StatementDto(
                VALID_CARD_NUMBER, VALID_TRANSACTION_ID, VALID_TRANSACTION_TYPE,
                VALID_CATEGORY_CODE, VALID_SOURCE, VALID_DESCRIPTION, VALID_AMOUNT,
                VALID_MERCHANT_ID, VALID_MERCHANT_NAME, VALID_MERCHANT_CITY,
                VALID_MERCHANT_ZIP, VALID_TIMESTAMP, VALID_TIMESTAMP);
    }

    /** Returns a valid DTO with only {@code amount} overridden. */
    private static StatementDto withAmount(BigDecimal amount) {
        return new StatementDto(
                VALID_CARD_NUMBER, VALID_TRANSACTION_ID, VALID_TRANSACTION_TYPE,
                VALID_CATEGORY_CODE, VALID_SOURCE, VALID_DESCRIPTION, amount,
                VALID_MERCHANT_ID, VALID_MERCHANT_NAME, VALID_MERCHANT_CITY,
                VALID_MERCHANT_ZIP, VALID_TIMESTAMP, VALID_TIMESTAMP);
    }

    /** Returns a valid DTO with only {@code cardNumber} overridden. */
    private static StatementDto withCardNumber(String cardNumber) {
        return new StatementDto(
                cardNumber, VALID_TRANSACTION_ID, VALID_TRANSACTION_TYPE,
                VALID_CATEGORY_CODE, VALID_SOURCE, VALID_DESCRIPTION, VALID_AMOUNT,
                VALID_MERCHANT_ID, VALID_MERCHANT_NAME, VALID_MERCHANT_CITY,
                VALID_MERCHANT_ZIP, VALID_TIMESTAMP, VALID_TIMESTAMP);
    }

    /** Returns a valid DTO with only {@code description} overridden. */
    private static StatementDto withDescription(String description) {
        return new StatementDto(
                VALID_CARD_NUMBER, VALID_TRANSACTION_ID, VALID_TRANSACTION_TYPE,
                VALID_CATEGORY_CODE, VALID_SOURCE, description, VALID_AMOUNT,
                VALID_MERCHANT_ID, VALID_MERCHANT_NAME, VALID_MERCHANT_CITY,
                VALID_MERCHANT_ZIP, VALID_TIMESTAMP, VALID_TIMESTAMP);
    }

    /** Returns a valid DTO with only {@code categoryCode} overridden. */
    private static StatementDto withCategoryCode(String categoryCode) {
        return new StatementDto(
                VALID_CARD_NUMBER, VALID_TRANSACTION_ID, VALID_TRANSACTION_TYPE,
                categoryCode, VALID_SOURCE, VALID_DESCRIPTION, VALID_AMOUNT,
                VALID_MERCHANT_ID, VALID_MERCHANT_NAME, VALID_MERCHANT_CITY,
                VALID_MERCHANT_ZIP, VALID_TIMESTAMP, VALID_TIMESTAMP);
    }

    /** Returns a valid DTO with only {@code merchantId} overridden. */
    private static StatementDto withMerchantId(String merchantId) {
        return new StatementDto(
                VALID_CARD_NUMBER, VALID_TRANSACTION_ID, VALID_TRANSACTION_TYPE,
                VALID_CATEGORY_CODE, VALID_SOURCE, VALID_DESCRIPTION, VALID_AMOUNT,
                merchantId, VALID_MERCHANT_NAME, VALID_MERCHANT_CITY,
                VALID_MERCHANT_ZIP, VALID_TIMESTAMP, VALID_TIMESTAMP);
    }

    /** Returns a valid DTO with only {@code transactionType} overridden. */
    private static StatementDto withTransactionType(String transactionType) {
        return new StatementDto(
                VALID_CARD_NUMBER, VALID_TRANSACTION_ID, transactionType,
                VALID_CATEGORY_CODE, VALID_SOURCE, VALID_DESCRIPTION, VALID_AMOUNT,
                VALID_MERCHANT_ID, VALID_MERCHANT_NAME, VALID_MERCHANT_CITY,
                VALID_MERCHANT_ZIP, VALID_TIMESTAMP, VALID_TIMESTAMP);
    }

    /**
     * Resolves the declared {@link Class} of a record component by name.
     *
     * @param name the record component name
     * @return the component's declared type
     */
    private static Class<?> componentType(String name) {
        for (RecordComponent component : StatementDto.class.getRecordComponents()) {
            if (component.getName().equals(name)) {
                return component.getType();
            }
        }
        throw new AssertionError("StatementDto has no record component named '" + name + "'");
    }

    /**
     * Returns whether the supplied violation set contains a violation reported
     * against the given property path.
     *
     * @param violations   the constraint violations produced by the validator
     * @param propertyPath the property path to look for (for example {@code "amount"})
     * @return {@code true} if at least one violation targets {@code propertyPath}
     */
    private static boolean hasViolationOn(
            Set<ConstraintViolation<StatementDto>> violations, String propertyPath) {
        return violations.stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals(propertyPath));
    }

    // ------------------------------------------------------------------
    // Record-shape assertions (reflection)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("declares exactly thirteen record components (FILLER X(20) excluded)")
    void hasExactlyThirteenComponents() {
        assertThat(StatementDto.class.getRecordComponents()).hasSize(13);
    }

    @Test
    @DisplayName("components appear in the canonical COSTM01 declaration order")
    void componentsAreInDeclaredOrder() {
        assertThat(StatementDto.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly(EXPECTED_COMPONENTS);
    }

    @Test
    @DisplayName("trailing COBOL FILLER is not a component")
    void fillerIsExcluded() {
        assertThat(StatementDto.class.getRecordComponents())
                .as("the trailing FILLER PIC X(20) must not surface as a record component")
                .noneMatch(component -> component.getName().toLowerCase(Locale.ROOT).contains("filler"))
                .hasSize(13);
    }

    @Test
    @DisplayName("amount is BigDecimal and transactionType is the real declared type")
    void amountIsBigDecimalAndTransactionTypeIsString() {
        // amount preserves COMP-3 / PIC S9(09)V99 precision as BigDecimal (AAP 0.6.1):
        // no double/float substitution is permitted.
        assertThat(componentType("amount")).isEqualTo(BigDecimal.class);
        // NOTE: the documented contract expected transactionType to be the
        // TransactionTypeCode enum, but the real StatementDto models it as a
        // validated two-character String (@Size(max=2) + @Pattern("\\d{2}")).
        // The assertion binds to the real type; the two-character TRNX-TYPE-CD
        // contract is still enforced (see the pattern test below) and the value
        // is tied back to TransactionTypeCode in transactionTypeResolves...().
        assertThat(componentType("transactionType")).isEqualTo(String.class);
    }

    // ------------------------------------------------------------------
    // Decimal-exactness guard (AAP 0.6.1): no floating-point components
    // ------------------------------------------------------------------

    @Test
    @DisplayName("no record component is double/float/Double/Float")
    void noFloatingPointComponents() {
        for (RecordComponent component : StatementDto.class.getRecordComponents()) {
            assertThat(component.getType())
                    .as("component '%s' must not be floating-point (decimal exactness, AAP 0.6.1)",
                            component.getName())
                    .isNotIn(double.class, float.class, Double.class, Float.class);
        }
    }

    // ------------------------------------------------------------------
    // Bean-validation boundary assertions (both violating and passing cases)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a fully-valid instance produces zero constraint violations")
    void validInstancePassesValidation() {
        assertThat(validator.validate(validDto())).isEmpty();
    }

    @Test
    @DisplayName("amount with more than two fraction digits fails @Digits")
    void amountFractionScaleOverflowFailsDigits() {
        // "1.234" has three fraction digits; @Digits(fraction = 2) is exceeded.
        Set<ConstraintViolation<StatementDto>> violations =
                validator.validate(withAmount(new BigDecimal("1.234")));
        assertThat(hasViolationOn(violations, "amount"))
                .as("three fraction digits must violate @Digits(fraction = 2)")
                .isTrue();
    }

    @Test
    @DisplayName("amount exceeding the integer-digit bound fails @Digits")
    void amountIntegerPartOverflowFailsDigits() {
        // NOTE: the real @Digits bound is integer = 10 (the COBOL TRNX-AMT is
        // PIC S9(09)V99 = nine integer digits). Eleven integer digits exceeds
        // the real bound and must produce a violation; "1234567890.12"
        // (ten integer digits) is valid and is covered by the boundary test.
        Set<ConstraintViolation<StatementDto>> violations =
                validator.validate(withAmount(new BigDecimal("12345678901.12")));
        assertThat(hasViolationOn(violations, "amount"))
                .as("eleven integer digits must violate @Digits(integer = 10)")
                .isTrue();
    }

    @Test
    @DisplayName("amount within the integer/fraction bounds passes @Digits")
    void amountWithinDigitsBoundsPasses() {
        // Nine integer digits matches the COBOL TRNX-AMT PIC S9(09)V99 width.
        assertThat(hasViolationOn(
                validator.validate(withAmount(new BigDecimal("123456789.99"))), "amount"))
                .as("nine integer digits (COBOL width) must satisfy @Digits")
                .isFalse();
        // Ten integer digits is the real DTO boundary and must also pass.
        assertThat(hasViolationOn(
                validator.validate(withAmount(new BigDecimal("9999999999.99"))), "amount"))
                .as("ten integer digits (real @Digits bound) must satisfy @Digits")
                .isFalse();
    }

    @Test
    @DisplayName("cardNumber longer than 16 characters is rejected; 16 is accepted")
    void cardNumberExceedingSizeIsRejected() {
        // Seventeen characters exceeds @Size(max = 16) (and the \d{0,16} pattern).
        assertThat(hasViolationOn(
                validator.validate(withCardNumber("12345678901234567")), "cardNumber"))
                .as("a 17-character cardNumber must be rejected")
                .isTrue();
        // Sixteen digits is exactly at the boundary and must be accepted.
        assertThat(hasViolationOn(
                validator.validate(withCardNumber("1234567890123456")), "cardNumber"))
                .as("a 16-character cardNumber must be accepted")
                .isFalse();
    }

    @Test
    @DisplayName("description longer than 100 characters fails @Size; 100 passes")
    void descriptionExceedingSizeIsRejected() {
        // description carries only @Size(max = 100) (no pattern), so it is the
        // clean demonstrator of the @Size upper boundary.
        assertThat(hasViolationOn(
                validator.validate(withDescription("a".repeat(101))), "description"))
                .as("a 101-character description must violate @Size(max = 100)")
                .isTrue();
        assertThat(hasViolationOn(
                validator.validate(withDescription("a".repeat(100))), "description"))
                .as("a 100-character description must satisfy @Size(max = 100)")
                .isFalse();
    }

    @Test
    @DisplayName("non-digit cardNumber fails @Pattern(\\d{0,16})")
    void cardNumberNonDigitFailsPattern() {
        // Sixteen characters (within @Size) but containing letters violates the
        // digits-only pattern; the all-digit valid value passes (see validDto()).
        assertThat(hasViolationOn(
                validator.validate(withCardNumber("12345678901234AB")), "cardNumber"))
                .as("a non-digit cardNumber must violate the \\d{0,16} pattern")
                .isTrue();
    }

    @Test
    @DisplayName("non-digit categoryCode fails @Pattern(\\d{1,4})")
    void categoryCodeNonDigitFailsPattern() {
        assertThat(hasViolationOn(
                validator.validate(withCategoryCode("A1")), "categoryCode"))
                .as("a non-digit categoryCode must violate the \\d{1,4} pattern")
                .isTrue();
        assertThat(hasViolationOn(
                validator.validate(withCategoryCode("0001")), "categoryCode"))
                .as("a four-digit categoryCode must satisfy the \\d{1,4} pattern")
                .isFalse();
    }

    @Test
    @DisplayName("non-digit merchantId fails @Pattern(\\d{1,9})")
    void merchantIdNonDigitFailsPattern() {
        assertThat(hasViolationOn(
                validator.validate(withMerchantId("1234567AB")), "merchantId"))
                .as("a non-digit merchantId must violate the \\d{1,9} pattern")
                .isTrue();
        assertThat(hasViolationOn(
                validator.validate(withMerchantId("123456789")), "merchantId"))
                .as("a nine-digit merchantId must satisfy the \\d{1,9} pattern")
                .isFalse();
    }

    @Test
    @DisplayName("non-numeric transactionType fails @Pattern(\\d{2}) preserving TRNX-TYPE-CD")
    void transactionTypeNonNumericFailsPattern() {
        // The legacy TRNX-TYPE-CD PIC X(02) contract is enforced as a
        // two-digit string pattern; a non-numeric value is rejected.
        assertThat(hasViolationOn(
                validator.validate(withTransactionType("AB")), "transactionType"))
                .as("a non-numeric transactionType must violate the \\d{2} pattern")
                .isTrue();
        assertThat(hasViolationOn(
                validator.validate(withTransactionType("01")), "transactionType"))
                .as("a two-digit transactionType must satisfy the \\d{2} pattern")
                .isFalse();
    }

    // ------------------------------------------------------------------
    // Serialization round-trip and TransactionTypeCode linkage
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the record survives a Jackson serialize/deserialize round-trip")
    void transactionTypeRoundTripsThroughJackson() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        StatementDto original = validDto();

        String json = objectMapper.writeValueAsString(original);
        StatementDto roundTripped = objectMapper.readValue(json, StatementDto.class);

        // transactionType is a plain two-character String; its token is "01"
        // regardless of any enum JSON representation, so the round-trip is the
        // tolerant contract rather than an exact-token assertion.
        assertThat(roundTripped.transactionType()).isEqualTo("01");
        assertThat(roundTripped.cardNumber()).isEqualTo(original.cardNumber());
        // BigDecimal is compared by value to remain scale-tolerant across JSON.
        assertThat(roundTripped.amount()).isEqualByComparingTo(original.amount());
    }

    @Test
    @DisplayName("transactionType code resolves through TransactionTypeCode and rejects unknown codes")
    void transactionTypeResolvesViaTransactionTypeCode() {
        StatementDto dto = validDto();

        // The DTO's two-character transactionType string must correspond to a
        // legacy TRAN-TYPE code, tying StatementDto to TransactionTypeCode.
        assertThat(TransactionTypeCode.fromCode(dto.transactionType()))
                .isEqualTo(TransactionTypeCode.PURCHASE);
        assertThat(TransactionTypeCode.fromCode("01").getCode()).isEqualTo("01");
        assertThatThrownBy(() -> TransactionTypeCode.fromCode("99"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

