package com.carddemo.unit.dto;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.carddemo.dto.CardDto;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure JUnit 5 unit tests for {@link CardDto} and its four nested records.
 *
 * <p>These tests pin the REST data-transfer contract for the card list, detail,
 * and update flows. The expected shape is derived byte-accurately from the AWS
 * CardDemo BMS symbolic maps {@code COCRDLI}/{@code COCRDSL}/{@code COCRDUP} and
 * online programs {@code COCRDLIC}/{@code COCRDSLC}/{@code COCRDUPC} at source
 * commit {@code 27d6c6f}. The migration contract guarantees four invariants that
 * these tests defend regardless of any future field renaming:</p>
 *
 * <ul>
 *   <li>{@code ListResponse} carries the seven-row page of {@code COCRDLI}
 *       ({@code ACCTNO1-7}/{@code CRDNUM1-7}/{@code CRDSTS1-7}).</li>
 *   <li>Card expiry is stored as discrete <em>segments</em> ({@code EXPMON},
 *       {@code EXPYEAR}, {@code EXPDAY}) on both {@code Detail} and
 *       {@code UpdateRequest}, never as a single merged date value; {@code Detail}
 *       surfaces {@code expiryDay} from the persisted date so a stateless client
 *       can perform a read-modify-write.</li>
 *   <li>{@code cardStatus} is a one-character {@link String} ({@code CRDSTS}/
 *       {@code CRDSTCD}), not a status enum.</li>
 *   <li>{@code UpdateRequest} requires a non-blank {@code accountId} and
 *       {@code cardNumber} ({@code @NotBlank}).</li>
 *   <li>{@code Detail} and {@code UpdateRequest} carry a {@code version} component
 *       (the JPA {@code @Version} token); {@code UpdateRequest.version} is
 *       {@code @NotNull} so a stale cross-request update is rejected (HTTP 409),
 *       reproducing {@code 9300-CHECK-CHANGE-IN-REC}.</li>
 * </ul>
 *
 * <p>The suite uses only the standalone jakarta Bean Validation
 * {@link Validator}; it deliberately avoids Spring, Testcontainers, and Mockito.
 * No floating-point types appear anywhere, consistent with the decimal-exactness
 * requirement of the migration (AAP §0.6.1).</p>
 */
class CardDtoTest {

    // Re-used field values that satisfy every constraint on the records under test.
    private static final String VALID_ACCOUNT_ID = "12345678901";   // 11 digits
    private static final String VALID_CARD_NUMBER = "1234567890123456"; // 16 digits
    private static final String VALID_CARD_STATUS = "Y";            // width 1
    private static final String VALID_NAME = "JOHN Q PUBLIC";       // <= 50
    private static final String VALID_EXP_MONTH = "12";             // 2 digits
    private static final String VALID_EXP_YEAR = "2024";            // 4 digits
    private static final String VALID_EXP_DAY = "31";               // 2 digits
    private static final String VALID_PAGE_NUMBER = "1";            // <= 3 digits

    // The factory is held for the class lifetime so the obtained validator stays
    // fully functional across every test; the short-lived test JVM reclaims it.
    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    /**
     * Returns the named record component of {@code recordType}, failing the test
     * if no such component exists.
     */
    private static RecordComponent component(Class<?> recordType, String name) {
        for (RecordComponent rc : recordType.getRecordComponents()) {
            if (rc.getName().equals(name)) {
                return rc;
            }
        }
        throw new AssertionError(
                "record component '" + name + "' not found on " + recordType.getName());
    }

    /**
     * Asserts that {@code violations} is non-empty and that every violation maps
     * to the same {@code expectedPath}. This is robust to a field that trips two
     * constraints at once (for example {@code @Size} + {@code @Pattern}, or
     * {@code @NotBlank} + {@code @Pattern}), which yields multiple violations on
     * one property path.
     */
    private static void assertViolationsOnlyOn(
            Set<? extends ConstraintViolation<?>> violations, String expectedPath) {
        assertThat(violations)
                .isNotEmpty()
                .allSatisfy(cv ->
                        assertThat(cv.getPropertyPath().toString()).isEqualTo(expectedPath));
    }

    // ---------------------------------------------------------------------
    // Phase 2 — record shape (reflection on the four nested records)
    // ---------------------------------------------------------------------

    @Test
    void cardSummaryComponentsInOrder() {
        assertThat(CardDto.CardSummary.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("accountId", "cardNumber", "cardStatus");
    }

    @Test
    void listResponseComponentsInOrder() {
        assertThat(CardDto.ListResponse.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("pageNumber", "accountIdFilter", "cardNumberFilter", "cards");
        // The page is a list of card rows (the seven-row COCRDLI page).
        assertThat(component(CardDto.ListResponse.class, "cards").getType())
                .isEqualTo(List.class);
    }

    @Test
    void detailComponentsInOrder() {
        assertThat(CardDto.Detail.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly(
                        "accountId", "cardNumber", "cardholderName",
                        "cardStatus", "expiryMonth", "expiryYear", "expiryDay", "version");
    }

    @Test
    void updateRequestHasSegmentedExpiry() {
        // Expiry MUST stay segmented (month/year/day); a merged 'expiryDate'
        // component would break external-contract fidelity with COCRDUP.
        assertThat(CardDto.UpdateRequest.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly(
                        "accountId", "cardNumber", "cardholderName", "cardStatus",
                        "expiryMonth", "expiryYear", "expiryDay", "version")
                .contains("expiryMonth", "expiryYear", "expiryDay")
                .doesNotContain("expiryDate");
    }

    @Test
    void cardStatusIsStringNotEnum() {
        // A one-character String across all three card views proves there is no
        // status enum (CRDSTS/CRDSTCD width 1 in the BMS maps).
        assertThat(component(CardDto.CardSummary.class, "cardStatus").getType())
                .isEqualTo(String.class);
        assertThat(component(CardDto.Detail.class, "cardStatus").getType())
                .isEqualTo(String.class);
        assertThat(component(CardDto.UpdateRequest.class, "cardStatus").getType())
                .isEqualTo(String.class);
    }

    // ---------------------------------------------------------------------
    // Phase 3 — no floating-point components (decimal exactness, AAP §0.6.1)
    // ---------------------------------------------------------------------

    @Test
    void noFloatingPointComponents() {
        Class<?>[] records = {
                CardDto.CardSummary.class,
                CardDto.ListResponse.class,
                CardDto.Detail.class,
                CardDto.UpdateRequest.class
        };
        for (Class<?> recordType : records) {
            for (RecordComponent rc : recordType.getRecordComponents()) {
                assertThat(rc.getType())
                        .as("%s.%s must not be floating-point",
                                recordType.getSimpleName(), rc.getName())
                        .isNotIn(double.class, float.class, Double.class, Float.class);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Phase 4 — the seven-row page contract validates cleanly
    // ---------------------------------------------------------------------

    @Test
    void listResponseSevenRowPageValidates() {
        // A full page is exactly the seven COCRDLI rows; build seven valid
        // summaries and confirm the cascaded @Valid validation reports nothing.
        List<CardDto.CardSummary> cards = List.of(
                new CardDto.CardSummary("11111111111", "1111111111111111", "Y"),
                new CardDto.CardSummary("22222222222", "2222222222222222", "N"),
                new CardDto.CardSummary("33333333333", "3333333333333333", "Y"),
                new CardDto.CardSummary("44444444444", "4444444444444444", "Y"),
                new CardDto.CardSummary("55555555555", "5555555555555555", "N"),
                new CardDto.CardSummary("66666666666", "6666666666666666", "Y"),
                new CardDto.CardSummary("77777777777", "7777777777777777", "Y"));
        assertThat(cards).hasSize(7);

        CardDto.ListResponse response = new CardDto.ListResponse(
                VALID_PAGE_NUMBER, VALID_ACCOUNT_ID, VALID_CARD_NUMBER, cards);

        Set<ConstraintViolation<CardDto.ListResponse>> violations = validator.validate(response);
        assertThat(violations).isEmpty();
    }

    // ---------------------------------------------------------------------
    // Phase 5 — bean validation: both violating and passing instances, each
    // violating case pinned to its exact property path.
    // ---------------------------------------------------------------------

    @Test
    void cardSummaryCardNumberTooLongFailsSize() {
        // 17 digits exceeds @Size(max=16) (and the \d{0,16} pattern).
        CardDto.CardSummary tooLong =
                new CardDto.CardSummary(VALID_ACCOUNT_ID, "12345678901234567", VALID_CARD_STATUS);
        Set<ConstraintViolation<CardDto.CardSummary>> violations = validator.validate(tooLong);
        assertViolationsOnlyOn(violations, "cardNumber");

        // 16 digits is the boundary and passes.
        CardDto.CardSummary ok =
                new CardDto.CardSummary(VALID_ACCOUNT_ID, VALID_CARD_NUMBER, VALID_CARD_STATUS);
        assertThat(validator.validate(ok)).isEmpty();
    }

    @Test
    void cardSummaryAccountIdNonDigitFailsPattern() {
        // 11 chars (within @Size) but the trailing 'A' breaks the \d{0,11} pattern.
        CardDto.CardSummary nonDigit =
                new CardDto.CardSummary("1234567890A", VALID_CARD_NUMBER, VALID_CARD_STATUS);
        Set<ConstraintViolation<CardDto.CardSummary>> violations = validator.validate(nonDigit);
        assertViolationsOnlyOn(violations, "accountId");
    }

    @Test
    void cardStatusWidthOne() {
        // Two characters exceed @Size(max=1) on the single-character status code.
        CardDto.CardSummary tooWide =
                new CardDto.CardSummary(VALID_ACCOUNT_ID, VALID_CARD_NUMBER, "AB");
        Set<ConstraintViolation<CardDto.CardSummary>> violations = validator.validate(tooWide);
        assertViolationsOnlyOn(violations, "cardStatus");

        // A single character passes.
        CardDto.CardSummary ok =
                new CardDto.CardSummary(VALID_ACCOUNT_ID, VALID_CARD_NUMBER, "Y");
        assertThat(validator.validate(ok)).isEmpty();
    }

    @Test
    void detailExpiryMonthThreeDigitsFailsSize() {
        // Three digits exceed @Size(max=2) (and the \d{0,2} pattern) on EXPMON.
        CardDto.Detail tooLong = new CardDto.Detail(
                VALID_ACCOUNT_ID, VALID_CARD_NUMBER, VALID_NAME,
                VALID_CARD_STATUS, "123", VALID_EXP_YEAR, VALID_EXP_DAY, 0L);
        Set<ConstraintViolation<CardDto.Detail>> violations = validator.validate(tooLong);
        assertViolationsOnlyOn(violations, "expiryMonth");

        // Two digits pass.
        CardDto.Detail ok = new CardDto.Detail(
                VALID_ACCOUNT_ID, VALID_CARD_NUMBER, VALID_NAME,
                VALID_CARD_STATUS, VALID_EXP_MONTH, VALID_EXP_YEAR, VALID_EXP_DAY, 0L);
        assertThat(validator.validate(ok)).isEmpty();
    }

    @Test
    void detailExpiryYearFiveDigitsFailsSize() {
        // Five digits exceed @Size(max=4) (and the \d{0,4} pattern) on EXPYEAR.
        CardDto.Detail tooLong = new CardDto.Detail(
                VALID_ACCOUNT_ID, VALID_CARD_NUMBER, VALID_NAME,
                VALID_CARD_STATUS, VALID_EXP_MONTH, "20245", VALID_EXP_DAY, 0L);
        Set<ConstraintViolation<CardDto.Detail>> violations = validator.validate(tooLong);
        assertViolationsOnlyOn(violations, "expiryYear");

        // Four digits pass.
        CardDto.Detail ok = new CardDto.Detail(
                VALID_ACCOUNT_ID, VALID_CARD_NUMBER, VALID_NAME,
                VALID_CARD_STATUS, VALID_EXP_MONTH, VALID_EXP_YEAR, VALID_EXP_DAY, 0L);
        assertThat(validator.validate(ok)).isEmpty();
    }

    @Test
    void updateRequestBlankAccountIdFails() {
        // A blank accountId trips @NotBlank (and the \d{1,11} pattern).
        CardDto.UpdateRequest blank = new CardDto.UpdateRequest(
                "", VALID_CARD_NUMBER, VALID_NAME, VALID_CARD_STATUS,
                VALID_EXP_MONTH, VALID_EXP_YEAR, VALID_EXP_DAY, 0L);
        Set<ConstraintViolation<CardDto.UpdateRequest>> violations = validator.validate(blank);
        assertViolationsOnlyOn(violations, "accountId");
    }

    @Test
    void updateRequestBlankCardNumberFails() {
        // A blank cardNumber trips @NotBlank (and the \d{1,16} pattern).
        CardDto.UpdateRequest blank = new CardDto.UpdateRequest(
                VALID_ACCOUNT_ID, "", VALID_NAME, VALID_CARD_STATUS,
                VALID_EXP_MONTH, VALID_EXP_YEAR, VALID_EXP_DAY, 0L);
        Set<ConstraintViolation<CardDto.UpdateRequest>> violations = validator.validate(blank);
        assertViolationsOnlyOn(violations, "cardNumber");
    }

    @Test
    void updateRequestExpiryDayTwoDigitBoundary() {
        // Three digits exceed @Size(max=2) (and the \d{0,2} pattern) on EXPDAY.
        CardDto.UpdateRequest tooLong = new CardDto.UpdateRequest(
                VALID_ACCOUNT_ID, VALID_CARD_NUMBER, VALID_NAME, VALID_CARD_STATUS,
                VALID_EXP_MONTH, VALID_EXP_YEAR, "123", 0L);
        Set<ConstraintViolation<CardDto.UpdateRequest>> violations = validator.validate(tooLong);
        assertViolationsOnlyOn(violations, "expiryDay");

        // The two-digit boundary value passes.
        CardDto.UpdateRequest ok = new CardDto.UpdateRequest(
                VALID_ACCOUNT_ID, VALID_CARD_NUMBER, VALID_NAME, VALID_CARD_STATUS,
                VALID_EXP_MONTH, VALID_EXP_YEAR, VALID_EXP_DAY, 0L);
        assertThat(validator.validate(ok)).isEmpty();
    }

    @Test
    void updateRequestValidInstancePasses() {
        CardDto.UpdateRequest ok = new CardDto.UpdateRequest(
                VALID_ACCOUNT_ID, VALID_CARD_NUMBER, VALID_NAME, VALID_CARD_STATUS,
                VALID_EXP_MONTH, VALID_EXP_YEAR, VALID_EXP_DAY, 0L);
        Set<ConstraintViolation<CardDto.UpdateRequest>> violations = validator.validate(ok);
        assertThat(violations).isEmpty();
    }

    @Test
    void updateRequestNullVersionPassesDtoLayer() {
        // The optimistic-locking token carries NO bean-validation constraint (D-062): a null or
        // omitted version is NOT rejected at the DTO layer. The service's verifyVersion treats a
        // null token exactly like a stale one and raises the byte-exact 409 (D-059), so the
        // 9300-CHECK-CHANGE-IN-REC guard is enforced in the service layer rather than shadowed by
        // a controller-boundary @NotNull -> generic 400. With every other field valid, a null
        // version therefore yields no DTO-layer violation.
        CardDto.UpdateRequest missingVersion = new CardDto.UpdateRequest(
                VALID_ACCOUNT_ID, VALID_CARD_NUMBER, VALID_NAME, VALID_CARD_STATUS,
                VALID_EXP_MONTH, VALID_EXP_YEAR, VALID_EXP_DAY, null);
        Set<ConstraintViolation<CardDto.UpdateRequest>> violations = validator.validate(missingVersion);
        assertThat(violations).isEmpty();
    }
}
