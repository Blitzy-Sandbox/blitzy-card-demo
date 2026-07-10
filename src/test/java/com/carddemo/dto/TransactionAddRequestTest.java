package com.carddemo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link TransactionAddRequest}, the REST request body that adds a new
 * financial transaction (online transaction {@code CT02}, legacy program
 * {@code COTRN02C}).
 *
 * <p>The DTO is the Spring MVC / Jakarta Validation translation of the BMS input map
 * {@code COTRN02} ({@code app/cpy-bms/COTRN02.CPY}) backed by the {@code TRAN-RECORD}
 * layout ({@code app/cpy/CVTRA05Y.cpy}, record length&nbsp;350). Legacy source
 * constructs are referenced by commit SHA {@code 27d6c6f}; no COBOL source is
 * reproduced here. These tests pin four migration-critical properties of the request:</p>
 * <ol>
 *   <li><strong>Decimal fidelity</strong> — {@code amount} maps the COBOL
 *       {@code TRAN-AMT PIC S9(09)V99} field to a scale-2 {@link BigDecimal}. It is
 *       mandatory ({@code @NotNull}) and constrained to at most 9 integer and 2 fraction
 *       digits ({@code @Digits(integer = 9, fraction = 2)}), so three-or-more-decimal or
 *       ten-integer-digit amounts are rejected while {@code 999999999.99} (the largest
 *       {@code S9(09)V99} value) is accepted. {@code float}/{@code double} are never used
 *       for money.</li>
 *   <li><strong>No client-supplied transaction id</strong> — {@code CT02} auto-generates
 *       the transaction id at commit time, so the request exposes <em>no</em>
 *       {@code transactionId}/{@code tranId} component (the generated id is returned on
 *       {@code TransactionAddResponse} instead). Its absence is proven by reflection.</li>
 *   <li><strong>Field edits</strong> — the mandatory-field and fixed-width edits performed
 *       by {@code CICS RECEIVE MAP} are reproduced as {@code @NotBlank}/{@code @Size}/
 *       {@code @Pattern} constraints; violations are asserted by their property path and
 *       offending constraint annotation, so an accidental annotation change breaks the
 *       build.</li>
 *   <li><strong>JSON binding</strong> — the request deserializes from the on-the-wire
 *       contract, binding {@code amount} as a scale-2 {@link BigDecimal} and {@code confirm}
 *       (the pseudo-conversational commit flag) as a boolean, and round-trips without
 *       loss.</li>
 * </ol>
 *
 * <p>The tests are deliberately pure and framework-free: they use only the shared
 * programmatic {@link jakarta.validation.Validator} and {@code ObjectMapper} exposed by
 * {@link DtoTestSupport} — no Spring context, no Testcontainers, and no mocks — so they run
 * in milliseconds and contribute fast line coverage toward the Gate&nbsp;8
 * (&ge;80%) JaCoCo threshold. All literal values below are obvious, non-secret test
 * fixtures.</p>
 */
@DisplayName("TransactionAddRequest — amount fidelity, no client transaction id, field edits, JSON binding")
class TransactionAddRequestTest {

    /** A valid account id: 11 digits at the {@code X(11)} boundary. */
    private static final String VALID_ACCOUNT_ID = "12345678901";

    /** A valid card number: 16 digits at the {@code X(16)} boundary. */
    private static final String VALID_CARD_NUMBER = "4111111111111111";

    /** A valid transaction type code ({@code X(02)}). */
    private static final String VALID_TYPE_CODE = "01";

    /** A valid transaction category code ({@code 9(04)}). */
    private static final String VALID_CATEGORY_CODE = "0005";

    /** A valid transaction source ({@code X(10)}). */
    private static final String VALID_SOURCE = "POS";

    /** A valid transaction description ({@code X(100)}). */
    private static final String VALID_DESCRIPTION = "Grocery purchase";

    /** A canonical, in-range monetary amount ({@code S9(09)V99}, scale 2). */
    private static final BigDecimal VALID_AMOUNT = new BigDecimal("123.45");

    /**
     * The maximum in-range amount: 9 integer digits + 2 fraction digits, i.e. the largest
     * value a COBOL {@code PIC S9(09)V99} field can hold. Must satisfy {@code @Digits(9, 2)}.
     */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999.99");

    /** A valid original timestamp at the {@code X(26)} boundary (26 characters). */
    private static final String VALID_ORIGINAL_TS = "2024-01-31 12:00:00.000000";

    /** A valid processed timestamp at the {@code X(26)} boundary (26 characters). */
    private static final String VALID_PROCESSED_TS = "2024-01-31 12:00:05.000000";

    /** A valid merchant id ({@code 9(09)}). */
    private static final String VALID_MERCHANT_ID = "123456789";

    /** A valid merchant name ({@code X(50)}). */
    private static final String VALID_MERCHANT_NAME = "Test Merchant Inc";

    /** A valid merchant city ({@code X(50)}). */
    private static final String VALID_MERCHANT_CITY = "Seattle";

    /** A valid merchant ZIP ({@code X(10)}). */
    private static final String VALID_MERCHANT_ZIP = "98101";

    /** The two-step confirmation flag; {@code true} commits, {@code null}/{@code false} previews. */
    private static final Boolean VALID_CONFIRM = Boolean.TRUE;

    // ------------------------------------------------------------------
    // Factory helpers — each returns a fully valid request except for the
    // single component under test, keeping every assertion isolated to one
    // constraint and one property path.
    // ------------------------------------------------------------------

    /**
     * Builds a fully valid request whose {@code amount} is the supplied value.
     *
     * @param amount the monetary amount to place on the request (may be {@code null} to
     *               exercise {@code @NotNull})
     * @return a request valid in every component except as dictated by {@code amount}
     */
    private static TransactionAddRequest validRequestWithAmount(BigDecimal amount) {
        return new TransactionAddRequest(
                VALID_ACCOUNT_ID, VALID_CARD_NUMBER, VALID_TYPE_CODE, VALID_CATEGORY_CODE,
                VALID_SOURCE, VALID_DESCRIPTION, amount, VALID_ORIGINAL_TS, VALID_PROCESSED_TS,
                VALID_MERCHANT_ID, VALID_MERCHANT_NAME, VALID_MERCHANT_CITY, VALID_MERCHANT_ZIP,
                VALID_CONFIRM);
    }

    /**
     * Builds a canonical, fully valid request (amount {@code 123.45}).
     *
     * @return a request that produces zero constraint violations
     */
    private static TransactionAddRequest validRequest() {
        return validRequestWithAmount(VALID_AMOUNT);
    }

    /**
     * Builds a valid request whose {@code accountId} is the supplied value.
     *
     * @param accountId the account id to place on the request
     * @return a request valid in every component except as dictated by {@code accountId}
     */
    private static TransactionAddRequest requestWithAccountId(String accountId) {
        return new TransactionAddRequest(
                accountId, VALID_CARD_NUMBER, VALID_TYPE_CODE, VALID_CATEGORY_CODE,
                VALID_SOURCE, VALID_DESCRIPTION, VALID_AMOUNT, VALID_ORIGINAL_TS, VALID_PROCESSED_TS,
                VALID_MERCHANT_ID, VALID_MERCHANT_NAME, VALID_MERCHANT_CITY, VALID_MERCHANT_ZIP,
                VALID_CONFIRM);
    }

    /**
     * Builds a valid request whose {@code typeCode} is the supplied value.
     *
     * @param typeCode the transaction type code to place on the request
     * @return a request valid in every component except as dictated by {@code typeCode}
     */
    private static TransactionAddRequest requestWithTypeCode(String typeCode) {
        return new TransactionAddRequest(
                VALID_ACCOUNT_ID, VALID_CARD_NUMBER, typeCode, VALID_CATEGORY_CODE,
                VALID_SOURCE, VALID_DESCRIPTION, VALID_AMOUNT, VALID_ORIGINAL_TS, VALID_PROCESSED_TS,
                VALID_MERCHANT_ID, VALID_MERCHANT_NAME, VALID_MERCHANT_CITY, VALID_MERCHANT_ZIP,
                VALID_CONFIRM);
    }

    /**
     * Builds a valid request whose {@code description} is the supplied value.
     *
     * @param description the transaction description to place on the request
     * @return a request valid in every component except as dictated by {@code description}
     */
    private static TransactionAddRequest requestWithDescription(String description) {
        return new TransactionAddRequest(
                VALID_ACCOUNT_ID, VALID_CARD_NUMBER, VALID_TYPE_CODE, VALID_CATEGORY_CODE,
                VALID_SOURCE, description, VALID_AMOUNT, VALID_ORIGINAL_TS, VALID_PROCESSED_TS,
                VALID_MERCHANT_ID, VALID_MERCHANT_NAME, VALID_MERCHANT_CITY, VALID_MERCHANT_ZIP,
                VALID_CONFIRM);
    }

    // ==================================================================
    // Phase 1 — a fully valid request has no violations.
    // ==================================================================

    @Nested
    @DisplayName("Phase 1 — a fully valid request")
    class ValidInstance {

        @Test
        @DisplayName("A fully valid request (amount 123.45) produces zero violations")
        void fullyValidRequestHasNoViolations() {
            TransactionAddRequest request = validRequest();

            Set<ConstraintViolation<TransactionAddRequest>> violations = DtoTestSupport.validate(request);

            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("Optional components may be null while the mandatory fields remain valid")
        void optionalComponentsMayBeNull() {
            // categoryCode, source, description, timestamps, merchant fields and confirm are
            // optional (no @NotNull/@NotBlank); @Size and @Pattern skip null values, so a
            // request carrying only the mandatory accountId, cardNumber, typeCode and amount
            // is still fully valid.
            TransactionAddRequest request = new TransactionAddRequest(
                    VALID_ACCOUNT_ID, VALID_CARD_NUMBER, VALID_TYPE_CODE, null,
                    null, null, VALID_AMOUNT, null, null,
                    null, null, null, null, null);

            Set<ConstraintViolation<TransactionAddRequest>> violations = DtoTestSupport.validate(request);

            assertThat(violations).isEmpty();
        }
    }

    // ==================================================================
    // Phase 2 (CRITICAL) — amount @NotNull / @Digits (S9(09)V99 fidelity).
    // ==================================================================

    @Nested
    @DisplayName("Phase 2 — amount @NotNull / @Digits (S9(09)V99 decimal fidelity)")
    class AmountValidation {

        @Test
        @DisplayName("A null amount violates @NotNull on 'amount'")
        void nullAmountViolatesNotNull() {
            // @Digits skips null values, so only @NotNull fires for a missing amount.
            Set<ConstraintViolation<TransactionAddRequest>> violations =
                    DtoTestSupport.validate(validRequestWithAmount(null));

            assertThat(violations).singleElement().satisfies(violation -> {
                assertThat(violation.getPropertyPath().toString()).isEqualTo("amount");
                assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(NotNull.class);
            });
        }

        @Test
        @DisplayName("An amount with three fraction digits (100.123) violates @Digits on 'amount'")
        void tooManyFractionDigitsViolatesDigits() {
            Set<ConstraintViolation<TransactionAddRequest>> violations =
                    DtoTestSupport.validate(validRequestWithAmount(new BigDecimal("100.123")));

            assertThat(violations).singleElement().satisfies(violation -> {
                assertThat(violation.getPropertyPath().toString()).isEqualTo("amount");
                assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Digits.class);
            });
        }

        @Test
        @DisplayName("An amount with ten integer digits violates @Digits on 'amount'")
        void tooManyIntegerDigitsViolatesDigits() {
            // 1234567890.00 has ten integer digits, exceeding the S9(09)V99 nine-digit integer part.
            Set<ConstraintViolation<TransactionAddRequest>> violations =
                    DtoTestSupport.validate(validRequestWithAmount(new BigDecimal("1234567890.00")));

            assertThat(violations).singleElement().satisfies(violation -> {
                assertThat(violation.getPropertyPath().toString()).isEqualTo("amount");
                assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Digits.class);
            });
        }

        @Test
        @DisplayName("The maximum in-range amount 999999999.99 (9 integer + 2 fraction) is accepted")
        void maximumInRangeAmountIsAccepted() {
            Set<ConstraintViolation<TransactionAddRequest>> violations =
                    DtoTestSupport.validate(validRequestWithAmount(MAX_AMOUNT));

            assertThat(violations)
                    .as("999999999.99 is the largest S9(09)V99 value and must satisfy @Digits(9, 2)")
                    .isEmpty();
        }
    }

    // ==================================================================
    // Phase 3 (CRITICAL) — the request carries no transaction id.
    // CT02 (COTRN02C) auto-generates the transaction id at commit time;
    // the client must never supply it. The generated id is returned on
    // TransactionAddResponse, never accepted on the request.
    // ==================================================================

    @Nested
    @DisplayName("Phase 3 — no client-supplied transaction id")
    class NoTransactionId {

        @Test
        @DisplayName("No component is named transactionId or tranId")
        void hasNoClientSuppliedTransactionId() {
            DtoTestSupport.assertNoComponentNamed(TransactionAddRequest.class, "transactionId", "tranId");
        }

        @Test
        @DisplayName("The component set exposes the CT02 input fields and excludes any transaction id")
        void componentSetExcludesTransactionId() {
            // componentNames(...) returns the record component names lower-cased.
            Set<String> components = DtoTestSupport.componentNames(TransactionAddRequest.class);

            assertThat(components)
                    .as("the CT02 request must expose its transaction input components")
                    .contains("accountid", "cardnumber", "typecode", "categorycode", "amount", "confirm");
            assertThat(components)
                    .as("the transaction id is auto-generated server-side and must not be a request component")
                    .doesNotContain("transactionid", "tranid");
        }
    }

    // ==================================================================
    // Phase 4 — string-field edits (@NotBlank / @Pattern / @Size) and the
    // property paths they report. Each failing case is paired with a
    // passing counterpart, and each blank/illegal value is chosen so that
    // exactly one constraint fires.
    // ==================================================================

    @Nested
    @DisplayName("Phase 4 — string-field constraints and violated property paths")
    class StringFieldConstraints {

        // ---- @NotBlank on typeCode (mandatory RECEIVE MAP field) ----

        @ParameterizedTest(name = "blank typeCode [{0}] violates @NotBlank")
        @NullSource
        @ValueSource(strings = {"", "  "})
        @DisplayName("A null, empty, or whitespace-only typeCode violates @NotBlank on 'typeCode'")
        void blankTypeCodeViolatesNotBlank(String blankTypeCode) {
            // Blank values are kept to at most 2 characters so @Size(max = 2) stays satisfied
            // and only @NotBlank fires, isolating the assertion to a single violation.
            Set<ConstraintViolation<TransactionAddRequest>> violations =
                    DtoTestSupport.validate(requestWithTypeCode(blankTypeCode));

            assertThat(violations).singleElement().satisfies(violation -> {
                assertThat(violation.getPropertyPath().toString()).isEqualTo("typeCode");
                assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(NotBlank.class);
            });
        }

        @Test
        @DisplayName("A present, in-width typeCode ('01') passes validation")
        void validTypeCodePasses() {
            Set<ConstraintViolation<TransactionAddRequest>> violations =
                    DtoTestSupport.validate(requestWithTypeCode("01"));

            assertThat(violations).isEmpty();
        }

        // ---- @Pattern on accountId (numeric only, 1..11 digits) ----

        @Test
        @DisplayName("A non-numeric accountId of legal width violates @Pattern on 'accountId'")
        void nonNumericAccountIdViolatesPattern() {
            // Eleven non-digit characters satisfy @Size(max = 11), so only @Pattern fires.
            Set<ConstraintViolation<TransactionAddRequest>> violations =
                    DtoTestSupport.validate(requestWithAccountId("ABCDEFGHIJK"));

            assertThat(violations).singleElement().satisfies(violation -> {
                assertThat(violation.getPropertyPath().toString()).isEqualTo("accountId");
                assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Pattern.class);
            });
        }

        @Test
        @DisplayName("A numeric accountId passes validation")
        void numericAccountIdPasses() {
            Set<ConstraintViolation<TransactionAddRequest>> violations =
                    DtoTestSupport.validate(requestWithAccountId("999"));

            assertThat(violations).isEmpty();
        }

        // ---- @Size on description (X(100)) ----

        @Test
        @DisplayName("A description longer than 100 characters violates @Size on 'description'")
        void tooLongDescriptionViolatesSize() {
            Set<ConstraintViolation<TransactionAddRequest>> violations =
                    DtoTestSupport.validate(requestWithDescription("x".repeat(101)));

            assertThat(violations).singleElement().satisfies(violation -> {
                assertThat(violation.getPropertyPath().toString()).isEqualTo("description");
                assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Size.class);
            });
        }

        @Test
        @DisplayName("A description at the 100-character boundary passes validation")
        void boundaryDescriptionPasses() {
            Set<ConstraintViolation<TransactionAddRequest>> violations =
                    DtoTestSupport.validate(requestWithDescription("x".repeat(100)));

            assertThat(violations).isEmpty();
        }
    }

    // ==================================================================
    // Phase 5 — JSON binding and round-trip. amount binds as a scale-2
    // BigDecimal (money-as-plain contract) and confirm as a boolean.
    // ==================================================================

    @Nested
    @DisplayName("Phase 5 — JSON binding and round-trip")
    class JsonBinding {

        @Test
        @DisplayName("A valid JSON body binds amount as a scale-2 BigDecimal and confirm as boolean true")
        void deserializesValidBody() {
            String json = """
                    {
                      "accountId": "12345678901",
                      "cardNumber": "4111111111111111",
                      "typeCode": "01",
                      "categoryCode": "0005",
                      "source": "POS",
                      "description": "Grocery purchase",
                      "amount": 123.45,
                      "originalTimestamp": "2024-01-31 12:00:00.000000",
                      "processedTimestamp": "2024-01-31 12:00:05.000000",
                      "merchantId": "123456789",
                      "merchantName": "Test Merchant Inc",
                      "merchantCity": "Seattle",
                      "merchantZip": "98101",
                      "confirm": true
                    }
                    """;

            TransactionAddRequest request = DtoTestSupport.fromJson(json, TransactionAddRequest.class);

            // amount binds as a BigDecimal that preserves the source S9(09)V99 scale of 2.
            DtoTestSupport.assertScale(request.amount(), 2);
            assertThat(request.amount()).isEqualTo(new BigDecimal("123.45"));
            // confirm binds as a boolean (the two-step commit flag).
            assertThat(request.confirm()).isTrue();
            assertThat(request.accountId()).isEqualTo("12345678901");
            assertThat(request.typeCode()).isEqualTo("01");
        }

        @Test
        @DisplayName("A JSON round-trip preserves every component and the amount's scale")
        void jsonRoundTripPreservesAllComponents() {
            TransactionAddRequest original = validRequest();

            TransactionAddRequest restored = DtoTestSupport.roundTrip(original, TransactionAddRequest.class);

            // The record's generated equals() compares all 14 components (scale-sensitive for
            // the BigDecimal amount), so equality proves a lossless round-trip.
            assertThat(restored).isEqualTo(original);
            DtoTestSupport.assertScale(restored.amount(), 2);
            assertThat(restored.confirm()).isEqualTo(original.confirm());
        }

        @Test
        @DisplayName("A false confirm flag binds as boolean false (preview rather than commit)")
        void confirmFalseBindsAsBoolean() {
            String json = """
                    {
                      "accountId": "12345678901",
                      "cardNumber": "4111111111111111",
                      "typeCode": "01",
                      "amount": 50.00,
                      "confirm": false
                    }
                    """;

            TransactionAddRequest request = DtoTestSupport.fromJson(json, TransactionAddRequest.class);

            assertThat(request.confirm()).isFalse();
            DtoTestSupport.assertScale(request.amount(), 2);
            assertThat(request.amount()).isEqualTo(new BigDecimal("50.00"));
        }
    }
}
