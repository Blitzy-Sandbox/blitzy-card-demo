package com.carddemo.dto;

import static com.carddemo.dto.DtoTestSupport.assertJsonNumberIsPlain;
import static com.carddemo.dto.DtoTestSupport.assertScale;
import static com.carddemo.dto.DtoTestSupport.componentNames;
import static com.carddemo.dto.DtoTestSupport.fromJson;
import static com.carddemo.dto.DtoTestSupport.roundTrip;
import static com.carddemo.dto.DtoTestSupport.toJson;
import static com.carddemo.dto.DtoTestSupport.validate;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;

import jakarta.validation.ConstraintViolation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Framework-free unit test for {@link BillPaymentResponse}, the immutable
 * bill-payment (transaction {@code CB00}) response DTO of the CardDemo
 * COBOL&rarr;Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.5.11 migration.
 *
 * <p>{@code BillPaymentResponse} is the headless-REST replacement for the legacy
 * BMS screen {@code COBIL00} (map source {@code app/cpy-bms/COBIL00.CPY}, program
 * {@code COBIL00C}); its monetary components are backed by the {@code ACCOUNT-RECORD}
 * layout in {@code app/cpy/CVACT01Y.cpy}, whose {@code ACCT-CURR-BAL} field is
 * {@code PIC S9(10)V99} — a signed, scale-2 packed picture (frozen COBOL source
 * referenced read-only by SHA {@code 27d6c6f}). The record echoes the account
 * identifier that was paid ({@code ACTIDIN} / {@code ACCT-ID}), the balance owed
 * before the payment, the amount paid, the resulting balance, a confirmation of the
 * generated payment transaction, and a human-readable message.</p>
 *
 * <p>This suite pins the four migration contracts that make the DTO a faithful,
 * REST-safe stand-in for the {@code COBIL00} screen. Each is exercised as its own
 * {@link Nested} group:</p>
 * <ol>
 *   <li><strong>JSON round-trip and key contract.</strong> A fully-populated
 *       response survives a serialize/deserialize cycle with full record equality,
 *       the emitted JSON exposes exactly the six mapped keys, and the record projects
 *       exactly the six intended components (no leaked internals).</li>
 *   <li><strong>Decimal fidelity (CRITICAL).</strong> All <em>three</em> money
 *       components ({@code currentBalance}, {@code paymentAmount}, {@code newBalance})
 *       are {@link BigDecimal} — never a floating-point type — and serialize as plain,
 *       scale-2 literals ({@code 1500.00} / {@code 0.00}, never {@code 1.5E3}). The
 *       canonical constructor normalizes each amount to scale&nbsp;2 with
 *       {@code HALF_UP} rounding, reproducing COBOL fixed-point behaviour, and the
 *       CB00 pay-full-balance relationship
 *       ({@code newBalance == currentBalance - paymentAmount}) is carried faithfully
 *       (AAP &sect;0.8.2 decimal-precision rule).</li>
 *   <li><strong>Identifier contract.</strong> {@code accountId} remains a
 *       {@link String} at the eleven-character width of {@code ACTIDIN X(11)} /
 *       {@code ACCT-ID 9(11)}, so leading zeros survive serialization byte-for-byte
 *       (a numeric encoding would silently drop them); {@code confirmationNumber} and
 *       {@code message} round-trip unchanged as strings.</li>
 *   <li><strong>Bean Validation.</strong> The declared {@code @Size} constraints
 *       ({@code accountId} &le; 11, {@code confirmationNumber} &le; 16) accept a valid
 *       response and reject over-width values on the expected property path.</li>
 * </ol>
 *
 * <p>The test loads no Spring context and uses no database, container, mock, file, or
 * network I/O: it relies solely on the shared {@link DtoTestSupport} helpers (the
 * production-mirroring Jackson mapper and the scale/plain-number assertions), so it
 * runs fast and deterministically. Design rationale for the money and
 * string-identifier decisions lives in {@code docs/decision-log.md}, not in these
 * comments.</p>
 */
@DisplayName("BillPaymentResponse — CB00 response DTO: JSON contract, decimal fidelity, identifiers")
class BillPaymentResponseTest {

    // ---------------------------------------------------------------------
    // Shared fixture values. The three money literals are the AAP-mandated
    // values that document the CB00 pay-full-balance path: paying the entire
    // current balance drives the new balance to zero. Every value is within the
    // DTO's declared @Size constraints so the fully-populated fixture is also a
    // valid Bean-Validation instance.
    // ---------------------------------------------------------------------

    /**
     * Account identifier fixture with a deliberate leading zero and the full
     * eleven-character width of {@code ACTIDIN X(11)} / {@code ACCT-ID 9(11)}; used
     * to prove the value is preserved as text rather than collapsed to a number.
     */
    private static final String ACCOUNT_ID = "00000000012";

    /** Balance owed before the payment ({@code ACCT-CURR-BAL}), scale-2 literal. */
    private static final String CURRENT_BALANCE = "1500.00";

    /** Amount paid — the full current balance — scale-2 literal. */
    private static final String PAYMENT_AMOUNT = "1500.00";

    /** Balance after a full-balance payment: zero, scale-2 literal. */
    private static final String NEW_BALANCE = "0.00";

    /** Confirmation identifier of the generated payment transaction (&le; 16 chars). */
    private static final String CONFIRMATION_NUMBER = "PAYCONF000000001";

    /** Human-readable confirmation text returned to the caller. */
    private static final String MESSAGE = "Payment posted successfully.";

    /** Fixed character width of {@code accountId} ({@code ACTIDIN X(11)}). */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** Mandated scale of every monetary component ({@code PIC S9(10)V99}). */
    private static final int MONEY_SCALE = 2;

    /**
     * The six JSON keys the DTO must expose, one per record component. Order is
     * irrelevant to the assertions, which compare membership regardless of order.
     */
    private static final String[] EXPECTED_JSON_KEYS = {
        "accountId", "currentBalance", "paymentAmount", "newBalance",
        "confirmationNumber", "message"
    };

    /** The three monetary component names that must all be scale-2 {@link BigDecimal}. */
    private static final String[] MONEY_COMPONENTS = {
        "currentBalance", "paymentAmount", "newBalance"
    };

    // ---------------------------------------------------------------------
    // Fixture factory and reflection helper.
    // ---------------------------------------------------------------------

    /**
     * Builds the canonical sample response shared by the tests: the AAP-mandated
     * full-balance-payment values ({@code 1500.00} owed, {@code 1500.00} paid,
     * {@code 0.00} remaining) plus a valid account id, confirmation number, and
     * message.
     *
     * @return a fully-populated {@link BillPaymentResponse} sample
     */
    private static BillPaymentResponse sample() {
        return new BillPaymentResponse(
                ACCOUNT_ID,
                new BigDecimal(CURRENT_BALANCE),
                new BigDecimal(PAYMENT_AMOUNT),
                new BigDecimal(NEW_BALANCE),
                CONFIRMATION_NUMBER,
                MESSAGE);
    }

    /**
     * Locates the record component of {@link BillPaymentResponse} with the given
     * name, for type-level assertions. {@code getRecordComponents()} is the
     * authoritative source of a record's declared components and their types.
     *
     * @param name the record component name to find
     * @return the matching {@link RecordComponent}
     */
    private static RecordComponent componentNamed(String name) {
        for (RecordComponent component : BillPaymentResponse.class.getRecordComponents()) {
            if (component.getName().equals(name)) {
                return component;
            }
        }
        throw new AssertionError(
                "BillPaymentResponse has no record component named '" + name + "'");
    }

    @Nested
    @DisplayName("JSON round-trip and key contract")
    class RoundTripJson {

        @Test
        @DisplayName("a fully-populated response round-trips with every component preserved")
        void roundTripPreservesAllComponents() {
            BillPaymentResponse original = sample();

            BillPaymentResponse restored = roundTrip(original, BillPaymentResponse.class);

            // A record derives equals() from every component, so equality proves each
            // field (including all three scale-2 money values) survived the
            // serialize/deserialize cycle unchanged.
            assertThat(restored).isEqualTo(original);
        }

        @Test
        @DisplayName("serialized JSON exposes exactly the six mapped keys")
        void jsonExposesExactlyExpectedKeys() {
            String json = toJson(sample());

            Map<String, Object> tree = fromJson(json, new TypeReference<Map<String, Object>>() { });

            assertThat(tree.keySet()).containsExactlyInAnyOrder(EXPECTED_JSON_KEYS);
        }

        @Test
        @DisplayName("the record projects exactly the six intended components — no leaked internals")
        void recordExposesExactlyExpectedComponents() {
            // componentNames() lower-cases each record component name; asserting the
            // exact set reconfirms the minimal projection expected by the REST contract.
            assertThat(componentNames(BillPaymentResponse.class))
                    .containsExactlyInAnyOrder(
                            "accountid", "currentbalance", "paymentamount",
                            "newbalance", "confirmationnumber", "message");
        }
    }

    @Nested
    @DisplayName("Decimal fidelity: all three money fields PLAIN + scale 2 (CRITICAL)")
    class MoneyPrecision {

        @Test
        @DisplayName("currentBalance, paymentAmount and newBalance all serialize as plain scale-2 literals")
        void allThreeMoneyFieldsSerializeAsPlainScaleTwo() {
            BillPaymentResponse dto = sample();

            String json = toJson(dto);

            // PLAIN (non-scientific), fixed scale-2 literals in the serialized JSON.
            assertJsonNumberIsPlain(json, "currentBalance", "1500.00");
            assertJsonNumberIsPlain(json, "paymentAmount", "1500.00");
            assertJsonNumberIsPlain(json, "newBalance", "0.00");

            // The in-memory BigDecimal values are held at exactly scale 2.
            assertScale(dto.currentBalance(), MONEY_SCALE);
            assertScale(dto.paymentAmount(), MONEY_SCALE);
            assertScale(dto.newBalance(), MONEY_SCALE);
        }

        @Test
        @DisplayName("all three money components are BigDecimal, never a floating-point type")
        void allThreeMoneyComponentsAreBigDecimalNeverFloatingPoint() {
            for (String field : MONEY_COMPONENTS) {
                Class<?> type = componentNamed(field).getType();

                assertThat(type)
                        .as("money component '%s' must be java.math.BigDecimal", field)
                        .isEqualTo(BigDecimal.class);
                assertThat(type)
                        .as("money component '%s' must never be a floating-point type", field)
                        .isNotIn(double.class, float.class, Double.class, Float.class);
            }
        }

        @Test
        @DisplayName("the canonical constructor normalizes each amount to scale 2 with HALF_UP rounding")
        void canonicalConstructorNormalizesToScaleTwo() {
            // Unscaled and over-scaled inputs are coerced to the V99 scale of 2:
            //   1500  -> 1500.00 (zero-padding),
            //   0.005 -> 0.01    (HALF_UP rounds the trailing 5 up),
            //   12.5  -> 12.50   (zero-padding).
            BillPaymentResponse dto = new BillPaymentResponse(
                    ACCOUNT_ID,
                    new BigDecimal("1500"),
                    new BigDecimal("0.005"),
                    new BigDecimal("12.5"),
                    CONFIRMATION_NUMBER,
                    MESSAGE);

            assertScale(dto.currentBalance(), MONEY_SCALE);
            assertScale(dto.paymentAmount(), MONEY_SCALE);
            assertScale(dto.newBalance(), MONEY_SCALE);

            assertThat(dto.currentBalance().toPlainString()).isEqualTo("1500.00");
            assertThat(dto.paymentAmount().toPlainString()).isEqualTo("0.01");
            assertThat(dto.newBalance().toPlainString()).isEqualTo("12.50");
        }

        @Test
        @DisplayName("full-balance payment drives newBalance to currentBalance - paymentAmount (0.00)")
        void newBalanceEqualsCurrentBalanceMinusPayment() {
            BillPaymentResponse dto = sample();

            BigDecimal expectedNewBalance = dto.currentBalance().subtract(dto.paymentAmount());

            // The CB00 pay-full-balance semantics: paying the entire current balance
            // leaves a zero balance. This is a DTO test, so we assert the values we set
            // are carried faithfully and remain internally consistent.
            assertThat(dto.newBalance())
                    .as("newBalance must equal currentBalance - paymentAmount")
                    .isEqualByComparingTo(expectedNewBalance)
                    .isEqualByComparingTo(new BigDecimal(NEW_BALANCE));
            assertThat(dto.newBalance().toPlainString()).isEqualTo("0.00");
        }

        @Test
        @DisplayName("a null monetary value is preserved as null (no NullPointerException on normalization)")
        void nullMonetaryValueIsPreserved() {
            BillPaymentResponse dto = new BillPaymentResponse(
                    ACCOUNT_ID, null, null, null, CONFIRMATION_NUMBER, MESSAGE);

            assertThat(dto.currentBalance()).isNull();
            assertThat(dto.paymentAmount()).isNull();
            assertThat(dto.newBalance()).isNull();
        }
    }

    @Nested
    @DisplayName("Identifiers and confirmation round-trip")
    class IdentifiersAndConfirmation {

        @Test
        @DisplayName("accountId stays an eleven-character String, preserving width and leading zeros")
        void accountIdIsElevenDigitStringPreservingLeadingZeros() {
            BillPaymentResponse dto = sample();

            // Structural: the component type is java.lang.String (not a numeric type).
            assertThat(componentNamed("accountId").getType()).isEqualTo(String.class);

            // Width: exactly the eleven characters of ACTIDIN X(11) / ACCT-ID 9(11).
            assertThat(dto.accountId()).hasSize(ACCOUNT_ID_WIDTH);

            String json = toJson(dto);

            // Textual proof: the value is quoted in the JSON payload (a bare number
            // would be unquoted and would lose the significant leading zeros).
            assertThat(json).contains("\"accountId\":\"" + ACCOUNT_ID + "\"");

            // Structural proof: Jackson maps a JSON string back to java.lang.String,
            // whereas a JSON number would deserialize to Integer/Long and drop zeros.
            Map<String, Object> fields = fromJson(json, new TypeReference<Map<String, Object>>() { });
            assertThat(fields.get("accountId"))
                    .isInstanceOf(String.class)
                    .isEqualTo(ACCOUNT_ID);
        }

        @Test
        @DisplayName("confirmationNumber and message round-trip unchanged as Strings")
        void confirmationNumberAndMessageRoundTrip() {
            BillPaymentResponse restored = roundTrip(sample(), BillPaymentResponse.class);

            assertThat(restored.confirmationNumber()).isEqualTo(CONFIRMATION_NUMBER);
            assertThat(restored.message()).isEqualTo(MESSAGE);

            assertThat(componentNamed("confirmationNumber").getType()).isEqualTo(String.class);
            assertThat(componentNamed("message").getType()).isEqualTo(String.class);
        }
    }

    @Nested
    @DisplayName("Bean Validation constraints")
    class BeanValidation {

        @Test
        @DisplayName("a fully-populated response satisfies every declared constraint")
        void fullyPopulatedResponseIsValid() {
            assertThat(validate(sample())).isEmpty();
        }

        @Test
        @DisplayName("an accountId longer than eleven characters is rejected on the accountId path")
        void accountIdLongerThanElevenIsRejected() {
            // Twelve characters — one over the @Size(max = 11) bound on ACTIDIN X(11).
            BillPaymentResponse dto = new BillPaymentResponse(
                    "123456789012",
                    new BigDecimal(CURRENT_BALANCE),
                    new BigDecimal(PAYMENT_AMOUNT),
                    new BigDecimal(NEW_BALANCE),
                    CONFIRMATION_NUMBER,
                    MESSAGE);

            Set<ConstraintViolation<BillPaymentResponse>> violations = validate(dto);

            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .as("an over-width accountId must be flagged on the accountId property")
                    .anySatisfy(violation ->
                            assertThat(violation.getPropertyPath().toString()).isEqualTo("accountId"));
        }

        @Test
        @DisplayName("a confirmationNumber longer than sixteen characters is rejected on that path")
        void confirmationNumberLongerThanSixteenIsRejected() {
            // Seventeen characters — one over the @Size(max = 16) bound.
            BillPaymentResponse dto = new BillPaymentResponse(
                    ACCOUNT_ID,
                    new BigDecimal(CURRENT_BALANCE),
                    new BigDecimal(PAYMENT_AMOUNT),
                    new BigDecimal(NEW_BALANCE),
                    "C".repeat(17),
                    MESSAGE);

            Set<ConstraintViolation<BillPaymentResponse>> violations = validate(dto);

            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .as("an over-width confirmationNumber must be flagged on the confirmationNumber property")
                    .anySatisfy(violation ->
                            assertThat(violation.getPropertyPath().toString()).isEqualTo("confirmationNumber"));
        }
    }
}
