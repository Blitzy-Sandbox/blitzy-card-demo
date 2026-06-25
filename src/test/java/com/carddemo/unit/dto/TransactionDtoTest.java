package com.carddemo.unit.dto;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.carddemo.dto.TransactionDto;
import com.carddemo.enums.TransactionTypeCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure JUnit 5 unit tests for {@link TransactionDto} and its four nested
 * records ({@code TransactionSummary}, {@code ListResponse}, {@code Detail},
 * {@code AddRequest}).
 *
 * <p>This is the fastest test tier in the AWS CardDemo COBOL&#8594;Java
 * migration: it exercises in-memory records and a standalone Jakarta Bean
 * Validation {@link Validator} with <em>no</em> {@code @SpringBootTest},
 * Spring context, Mockito, Testcontainers, or disk/file I/O. Every expected
 * field width, pattern, and ordering is hard-coded here from the frozen parity
 * sources read at authoring time (commit SHA {@code 27d6c6f}).
 *
 * <p>Parity sources (REFERENCE only, never copied into the codebase):
 * <ul>
 *   <li>{@code app/bms/COTRN00.bms} (CT00 / {@code COTRN00C}) &mdash;
 *       {@code PAGENUM X(8)}, {@code TRNIDIN X(16)} and the ten repeated rows
 *       {@code TRNIDnn X(16)} / {@code TDATEnn X(8)} / {@code TDESCnn X(26)} /
 *       {@code TAMTnnn X(12)}, confirming the list page contract.</li>
 *   <li>{@code app/cbl/COTRN01C.cbl} (CT01) &mdash; the transaction detail
 *       display fields.</li>
 *   <li>{@code app/cbl/COTRN02C.cbl} (CT02) &mdash; the transaction add fields,
 *       including {@code CONFIRM X(1)}.</li>
 * </ul>
 *
 * <p>The assertions below bind to the <strong>real</strong> source contract of
 * {@link TransactionDto} as it exists in the repository. Two points differ from
 * an earlier anticipated shape and are flagged inline with {@code // NOTE:}
 * markers so the divergence is explicit:
 * <ol>
 *   <li>{@code Detail.transactionType} is a validated {@code String}
 *       ({@code @Size(max = 2) @Pattern("\\d{2}")}), <em>not</em> the
 *       {@link TransactionTypeCode} enum. {@code AddRequest.typeCode} is
 *       likewise a {@code \d{2}} {@code String}, and {@code source} is always a
 *       {@code String}.</li>
 *   <li>Monetary {@code amount} fields are annotated
 *       {@code @Digits(integer = 10, fraction = 2)}; the chosen golden value
 *       {@code 123456789.99} (nine integer digits, two fraction digits)
 *       satisfies that contract while {@code 1.234} violates the two-digit
 *       fraction bound.</li>
 * </ol>
 */
class TransactionDtoTest {

    /** Golden, fully-valid monetary amount: nine integer digits, two fraction digits. */
    private static final BigDecimal VALID_AMOUNT = new BigDecimal("123456789.99");

    /**
     * The factory is retained for the lifetime of the test class so the derived
     * {@link Validator} stays usable across every {@code @Test}; closing it
     * eagerly in {@link #setUpValidator()} would release the underlying
     * constraint infrastructure.
     */
    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    // ------------------------------------------------------------------
    // Test data builders (all fields valid unless explicitly overridden)
    // ------------------------------------------------------------------

    private static TransactionDto.TransactionSummary summary(BigDecimal amount) {
        return new TransactionDto.TransactionSummary(
                "TX0000000000001",
                "20230101",
                "Grocery purchase",
                amount);
    }

    private static TransactionDto.TransactionSummary validSummary() {
        return summary(VALID_AMOUNT);
    }

    private static TransactionDto.Detail detail(String transactionType,
            String description, String merchantId) {
        return new TransactionDto.Detail(
                "TX0000000000001",
                "1234567890123456",
                transactionType,
                "0001",
                "POS",
                description,
                VALID_AMOUNT,
                "2023-01-01",
                "2023-01-02",
                merchantId,
                "Acme Stores",
                "Springfield",
                "12345-6789");
    }

    private static TransactionDto.Detail validDetail() {
        // transactionType is the legacy two-character code "01" (PURCHASE).
        return detail(TransactionTypeCode.PURCHASE.getCode(), "Grocery purchase", "123456789");
    }

    private static TransactionDto.AddRequest addRequest(String accountId,
            String typeCode, String confirm) {
        return new TransactionDto.AddRequest(
                accountId,
                "1234567890123456",
                typeCode,
                "0001",
                "POS",
                "Grocery purchase",
                VALID_AMOUNT,
                "2023-01-01",
                "2023-01-02",
                "123456789",
                "Acme Stores",
                "Springfield",
                "12345-6789",
                confirm);
    }

    private static TransactionDto.AddRequest validAddRequest() {
        return addRequest("12345678901", "01", "Y");
    }

    /**
     * Returns the single violated property path, asserting first that exactly
     * one violation was raised.
     */
    private static <T> String onlyViolationPath(Set<ConstraintViolation<T>> violations) {
        assertThat(violations).hasSize(1);
        return violations.iterator().next().getPropertyPath().toString();
    }

    // ------------------------------------------------------------------
    // Phase 2 - Record shape (reflection over the four nested records)
    // ------------------------------------------------------------------

    @Test
    void transactionSummaryComponentsInOrder() {
        RecordComponent[] components = TransactionDto.TransactionSummary.class.getRecordComponents();
        assertThat(components).extracting(RecordComponent::getName)
                .containsExactly("transactionId", "date", "description", "amount");
        assertThat(components[3].getType()).isEqualTo(BigDecimal.class);
    }

    @Test
    void listResponseComponentsInOrder() {
        RecordComponent[] components = TransactionDto.ListResponse.class.getRecordComponents();
        assertThat(components).extracting(RecordComponent::getName)
                .containsExactly("pageNumber", "transactionIdFilter", "transactions");
        assertThat(components[2].getType()).isEqualTo(List.class);
    }

    @Test
    void detailHasThirteenComponentsInOrder() {
        RecordComponent[] components = TransactionDto.Detail.class.getRecordComponents();
        assertThat(components).hasSize(13);
        assertThat(components).extracting(RecordComponent::getName)
                .containsExactly(
                        "transactionId", "cardNumber", "transactionType", "categoryCode",
                        "source", "description", "amount", "originDate", "processDate",
                        "merchantId", "merchantName", "merchantCity", "merchantZip");
        // NOTE: Detail.transactionType is a validated String (@Size(max=2) @Pattern "\\d{2}")
        // in the real source, not the TransactionTypeCode enum.
        assertThat(components[2].getType()).isEqualTo(String.class);
        assertThat(components[4].getType()).isEqualTo(String.class);
        assertThat(components[6].getType()).isEqualTo(BigDecimal.class);
    }

    @Test
    void addRequestHasFourteenComponentsInOrder() {
        RecordComponent[] components = TransactionDto.AddRequest.class.getRecordComponents();
        assertThat(components).hasSize(14);
        assertThat(components).extracting(RecordComponent::getName)
                .containsExactly(
                        "accountId", "cardNumber", "typeCode", "categoryCode", "source",
                        "description", "amount", "originDate", "processDate", "merchantId",
                        "merchantName", "merchantCity", "merchantZip", "confirm");
        // typeCode is a \d{2} String on the add path (never the enum).
        assertThat(components[2].getType()).isEqualTo(String.class);
        assertThat(components[6].getType()).isEqualTo(BigDecimal.class);
    }

    // ------------------------------------------------------------------
    // Phase 3 - No floating-point components (decimal exactness, AAP 0.6.1)
    // ------------------------------------------------------------------

    @Test
    void noFloatingPointComponents() {
        List<Class<?>> records = List.of(
                TransactionDto.TransactionSummary.class,
                TransactionDto.ListResponse.class,
                TransactionDto.Detail.class,
                TransactionDto.AddRequest.class);
        for (Class<?> recordType : records) {
            for (RecordComponent component : recordType.getRecordComponents()) {
                assertThat(component.getType())
                        .as("%s.%s must not be a floating-point type",
                                recordType.getSimpleName(), component.getName())
                        .isNotIn(double.class, float.class, Double.class, Float.class);
            }
        }
    }

    // ------------------------------------------------------------------
    // Phase 4 - 10-row page contract
    // ------------------------------------------------------------------

    @Test
    void listResponseTenRowPageValidates() {
        List<TransactionDto.TransactionSummary> transactions = List.of(
                validSummary(), validSummary(), validSummary(), validSummary(), validSummary(),
                validSummary(), validSummary(), validSummary(), validSummary(), validSummary());
        TransactionDto.ListResponse response =
                new TransactionDto.ListResponse("1", "TX00000000000001", transactions);

        Set<ConstraintViolation<TransactionDto.ListResponse>> violations =
                validator.validate(response);

        assertThat(transactions).hasSize(10);
        assertThat(violations).isEmpty();
    }

    // ------------------------------------------------------------------
    // Phase 5 - Bean validation (violating and passing, asserting path)
    // ------------------------------------------------------------------

    @Test
    void summaryAmountOverScaleFailsDigits() {
        // Three fraction digits exceeds @Digits(..., fraction = 2).
        Set<ConstraintViolation<TransactionDto.TransactionSummary>> violations =
                validator.validate(summary(new BigDecimal("1.234")));
        assertThat(onlyViolationPath(violations)).isEqualTo("amount");

        // NOTE: amount is @Digits(integer = 10, fraction = 2) in the real source; the
        // golden value 123456789.99 (nine integer + two fraction digits) is well-formed.
        assertThat(validator.validate(summary(VALID_AMOUNT))).isEmpty();
    }

    @Test
    void detailDescriptionTooLongFailsSize() {
        Set<ConstraintViolation<TransactionDto.Detail>> violations =
                validator.validate(detail("01", "a".repeat(61), "123456789"));
        assertThat(onlyViolationPath(violations)).isEqualTo("description");

        assertThat(validator.validate(detail("01", "a".repeat(60), "123456789"))).isEmpty();
    }

    @Test
    void detailMerchantIdNonDigitFailsPattern() {
        Set<ConstraintViolation<TransactionDto.Detail>> violations =
                validator.validate(detail("01", "Grocery purchase", "12A"));
        assertThat(onlyViolationPath(violations)).isEqualTo("merchantId");
    }

    @Test
    void addRequestBlankAccountIdFails() {
        // A blank accountId trips both @NotBlank and @Pattern("\\d{1,11}"); every
        // resulting violation is reported against the "accountId" path.
        Set<ConstraintViolation<TransactionDto.AddRequest>> violations =
                validator.validate(addRequest("", "01", "Y"));
        assertThat(violations).isNotEmpty();
        assertThat(violations).allSatisfy(violation ->
                assertThat(violation.getPropertyPath().toString()).isEqualTo("accountId"));
    }

    @Test
    void addRequestTypeCodeMustBeTwoDigits() {
        // A single digit does not satisfy the exact two-digit pattern \d{2}.
        assertThat(onlyViolationPath(validator.validate(addRequest("12345678901", "1", "Y"))))
                .isEqualTo("typeCode");
        // Two non-digit characters fail the same pattern.
        assertThat(onlyViolationPath(validator.validate(addRequest("12345678901", "ab", "Y"))))
                .isEqualTo("typeCode");
        // Exactly two digits is accepted.
        assertThat(validator.validate(addRequest("12345678901", "01", "Y"))).isEmpty();
    }

    @Test
    void addRequestConfirmInvalidCharFailsPattern() {
        Set<ConstraintViolation<TransactionDto.AddRequest>> violations =
                validator.validate(addRequest("12345678901", "01", "Q"));
        assertThat(onlyViolationPath(violations)).isEqualTo("confirm");

        // The pattern "[YyNn]?" accepts Y/y/N/n and the empty string.
        assertThat(validator.validate(addRequest("12345678901", "01", "Y"))).isEmpty();
        assertThat(validator.validate(addRequest("12345678901", "01", ""))).isEmpty();
    }

    @Test
    void addRequestValidInstancePasses() {
        assertThat(validator.validate(validAddRequest())).isEmpty();
    }

    // ------------------------------------------------------------------
    // Phase 6 - Enum (de)serialization (tolerant round-trip)
    // ------------------------------------------------------------------

    @Test
    void detailTransactionTypeJacksonRoundTrip() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TransactionDto.Detail original = validDetail();

        String json = objectMapper.writeValueAsString(original);
        TransactionDto.Detail roundTripped =
                objectMapper.readValue(json, TransactionDto.Detail.class);

        // NOTE: Detail.transactionType is a String, so the round-trip preserves the
        // legacy two-character code "01" rather than an enum constant; that code
        // resolves through TransactionTypeCode.fromCode(...) to PURCHASE.
        assertThat(roundTripped.transactionType()).isEqualTo(TransactionTypeCode.PURCHASE.getCode());
        assertThat(TransactionTypeCode.fromCode(roundTripped.transactionType()))
                .isEqualTo(TransactionTypeCode.PURCHASE);
    }

    @Test
    void fromCodeResolvesAndRejects() {
        assertThat(TransactionTypeCode.fromCode("02")).isEqualTo(TransactionTypeCode.PAYMENT);
        assertThatThrownBy(() -> TransactionTypeCode.fromCode("zz"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
