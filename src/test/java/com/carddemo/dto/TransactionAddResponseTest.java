package com.carddemo.dto;

import static com.carddemo.dto.DtoTestSupport.assertJsonNumberIsPlain;
import static com.carddemo.dto.DtoTestSupport.assertScale;
import static com.carddemo.dto.DtoTestSupport.fromJson;
import static com.carddemo.dto.DtoTestSupport.roundTrip;
import static com.carddemo.dto.DtoTestSupport.toJson;
import static com.carddemo.dto.DtoTestSupport.validate;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pure, framework-free unit tests for {@link TransactionAddResponse}, the
 * transaction-add (CICS transaction {@code CT02}) confirmation DTO returned once
 * a new transaction has been persisted. The type is the Java 25 / Spring Boot
 * translation of the confirmation the legacy program {@code COTRN02C} rendered on
 * the {@code COTRN02} BMS map; its shape is driven by the read-only COBOL sources
 * (referenced by frozen source SHA {@code 27d6c6f}, never copied into the
 * target): the {@code COTRN02} symbolic map ({@code app/cpy-bms/COTRN02.CPY}) and
 * the transaction record {@code TRAN-RECORD} ({@code app/cpy/CVTRA05Y.cpy}).
 *
 * <p>These assertions lock down the migration contract of the add-transaction
 * confirmation, one concern per {@link Nested} group:</p>
 * <ol>
 *   <li><strong>JSON round-trip</strong> - a fully-populated response survives a
 *       serialize/deserialize cycle unchanged and exposes exactly the four mapped
 *       fields {@code transactionId}, {@code accountId}, {@code amount} and
 *       {@code message}; {@code transactionId} stays a {@link String} that
 *       preserves the fixed 16-character {@code TRAN-ID} ({@code PIC X(16)})
 *       width rather than collapsing into a number.</li>
 *   <li><strong>Monetary precision</strong> - {@code TRAN-AMT}
 *       ({@code PIC S9(09)V99}, copybook {@code CVTRA05Y}) maps to a scale-2
 *       {@link BigDecimal} that always serializes as a <em>plain</em> decimal
 *       literal (for example {@code 123.45}, never {@code 1.2345E2}) and is never
 *       a floating-point type (AAP decimal-fidelity rule).</li>
 *   <li><strong>Identifier fidelity</strong> - {@code accountId} stays a
 *       {@link String} preserving the 11-character {@code ACTIDIN}
 *       ({@code PIC X(11)}) width, and the human-readable confirmation
 *       {@code message} round-trips unchanged.</li>
 *   <li><strong>Bean Validation</strong> - the declared {@code @Size} constraints
 *       enforce the {@code X(16)} / {@code X(11)} widths on the wire.</li>
 * </ol>
 *
 * <p>The suite loads no Spring context and touches no database, file, network, or
 * AWS resource; every check is an in-memory serialization or reflection assertion
 * built on the shared {@link DtoTestSupport} helpers (which mirror the production
 * serialization contract), so it is fast and deterministic. The rationale for the
 * money-as-plain, scale-2 decision lives in {@code docs/decision-log.md}, not in
 * these comments.</p>
 */
@DisplayName("TransactionAddResponse — CT02 add-transaction confirmation DTO")
class TransactionAddResponseTest {

    // ---------------------------------------------------------------------
    // Shared fixture values. Each id is exactly its source picture width so the
    // width-preservation assertions are meaningful, and every value keeps the
    // fully-populated fixture within the DTO's declared @Size constraints.
    // ---------------------------------------------------------------------

    /** A representative 16-character {@code TRAN-ID} ({@code PIC X(16)}). */
    private static final String TRANSACTION_ID = "TXN0000000000123";

    /** Fixed character width of {@code TRAN-ID} ({@code PIC X(16)}). */
    private static final int TRANSACTION_ID_LENGTH = 16;

    /** A representative 11-character {@code ACTIDIN} ({@code PIC X(11)}). */
    private static final String ACCOUNT_ID = "00000000123";

    /** Fixed character width of {@code ACTIDIN} ({@code PIC X(11)}). */
    private static final int ACCOUNT_ID_LENGTH = 11;

    /** Plain, scale-2 monetary literal used for the fully-populated fixture. */
    private static final String SAMPLE_AMOUNT_LITERAL = "100.00";

    /**
     * The standard confirmation message produced by
     * {@link TransactionAddResponse#withConfirmation(String, String, BigDecimal)}
     * for {@link #TRANSACTION_ID}.
     */
    private static final String CONFIRMATION_MESSAGE =
            "Transaction " + TRANSACTION_ID + " added successfully";

    /** The exact set of JSON keys the DTO must expose, one per record component. */
    private static final String[] EXPECTED_JSON_KEYS = {
        "transactionId", "accountId", "amount", "message"
    };

    // ---------------------------------------------------------------------
    // Fixture factories and reflection helper.
    // ---------------------------------------------------------------------

    /**
     * Builds a fully-populated, valid confirmation whose amount is the canonical
     * scale-2 sample {@code 100.00}.
     *
     * @return a populated {@link TransactionAddResponse}
     */
    private static TransactionAddResponse sample() {
        return sampleWithAmount(new BigDecimal(SAMPLE_AMOUNT_LITERAL));
    }

    /**
     * Builds a confirmation with a caller-supplied {@code amount}, reusing the
     * shared id and message constants for every other component.
     *
     * @param amount the monetary amount to place in the response (may be
     *               {@code null} to exercise the null-preserving constructor)
     * @return a populated {@link TransactionAddResponse}
     */
    private static TransactionAddResponse sampleWithAmount(BigDecimal amount) {
        return new TransactionAddResponse(TRANSACTION_ID, ACCOUNT_ID, amount, CONFIRMATION_MESSAGE);
    }

    /**
     * Locates the record component of {@link TransactionAddResponse} with the
     * given name, for type-level assertions.
     *
     * @param name the record component name to find
     * @return the matching {@link RecordComponent}
     */
    private static RecordComponent componentNamed(String name) {
        for (RecordComponent component : TransactionAddResponse.class.getRecordComponents()) {
            if (component.getName().equals(name)) {
                return component;
            }
        }
        throw new AssertionError(
                "TransactionAddResponse has no record component named '" + name + "'");
    }

    // =====================================================================
    // Phase 1 — JSON round-trip and wire contract
    // =====================================================================

    @Nested
    @DisplayName("JSON round-trip")
    class RoundTripJson {

        @Test
        @DisplayName("a fully-populated response round-trips with every component preserved")
        void roundTripsPreservingEveryComponent() {
            TransactionAddResponse original = sample();

            TransactionAddResponse restored = roundTrip(original, TransactionAddResponse.class);

            // Record value-equality is scale-sensitive for BigDecimal, so equality
            // here also proves the money scale (2) survives the round-trip.
            assertThat(restored).isEqualTo(original);
            assertThat(restored.transactionId()).isEqualTo(TRANSACTION_ID);
            assertThat(restored.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(restored.amount()).isEqualByComparingTo(original.amount());
            assertThat(restored.message()).isEqualTo(CONFIRMATION_MESSAGE);
            assertScale(restored.amount(), 2);
        }

        @Test
        @DisplayName("serializes exactly the four expected JSON keys")
        void serializesExactlyTheFourExpectedJsonKeys() {
            String json = toJson(sample());

            Map<String, Object> tree = fromJson(json, new TypeReference<Map<String, Object>>() { });

            assertThat(tree.keySet()).containsExactlyInAnyOrder(EXPECTED_JSON_KEYS);
        }

        @Test
        @DisplayName("transactionId is auto-generated, echoed back, and stays a 16-char String")
        void transactionIdStaysStringAtSixteenChars() {
            // Type-level: the component is String, never a numeric type, so the
            // auto-generated 16-character id is never re-parsed as a number.
            assertThat(componentNamed("transactionId").getType()).isEqualTo(String.class);

            TransactionAddResponse restored = roundTrip(sample(), TransactionAddResponse.class);

            assertThat(restored.transactionId())
                    .isEqualTo(TRANSACTION_ID)
                    .hasSize(TRANSACTION_ID_LENGTH);
        }
    }

    // =====================================================================
    // Phase 2 — Monetary amount: PLAIN notation and scale 2 (CRITICAL)
    // =====================================================================

    @Nested
    @DisplayName("Monetary amount: PLAIN notation and scale 2")
    class MoneyPrecision {

        @Test
        @DisplayName("amount serializes as a plain scale-2 literal (123.45, never 1.2345E2)")
        void serializesMoneyAsPlainScaleTwo() {
            TransactionAddResponse dto = sampleWithAmount(new BigDecimal("123.45"));

            String json = toJson(dto);

            assertJsonNumberIsPlain(json, "amount", "123.45");
            assertScale(dto.amount(), 2);
        }

        @Test
        @DisplayName("the canonical constructor normalizes amount to the V99 scale of 2 using HALF_UP")
        void normalizesScaleToTwoHalfUp() {
            // A scale-0 input is padded out to two fraction digits.
            assertThat(sampleWithAmount(new BigDecimal("5")).amount().toPlainString())
                    .isEqualTo("5.00");
            assertScale(sampleWithAmount(new BigDecimal("5")).amount(), 2);

            // A scale-3 input is rounded half-up to two fraction digits: the exact
            // decimal 2.345 rounds up to 2.35, reproducing COBOL rounding on assignment.
            assertThat(sampleWithAmount(new BigDecimal("2.345")).amount().toPlainString())
                    .isEqualTo("2.35");
            assertScale(sampleWithAmount(new BigDecimal("2.345")).amount(), 2);
        }

        @Test
        @DisplayName("the amount component is BigDecimal, never a floating-point type")
        void amountComponentIsBigDecimal() {
            Class<?> amountType = componentNamed("amount").getType();

            assertThat(amountType).isEqualTo(BigDecimal.class);
            assertThat(amountType).isNotIn(double.class, float.class, Double.class, Float.class);
        }

        @Test
        @DisplayName("a null amount is preserved as null by the canonical constructor")
        void nullAmountIsPreserved() {
            assertThat(sampleWithAmount(null).amount()).isNull();
        }
    }

    // =====================================================================
    // Phase 3 — Identifier fidelity and confirmation message
    // =====================================================================

    @Nested
    @DisplayName("Identifier fidelity and confirmation message")
    class IdentifierFidelity {

        @Test
        @DisplayName("accountId stays a String preserving the 11-character width")
        void accountIdStaysStringAtElevenChars() {
            assertThat(componentNamed("accountId").getType()).isEqualTo(String.class);

            TransactionAddResponse restored = roundTrip(sample(), TransactionAddResponse.class);

            assertThat(restored.accountId())
                    .isEqualTo(ACCOUNT_ID)
                    .hasSize(ACCOUNT_ID_LENGTH);
        }

        @Test
        @DisplayName("the confirmation message is a String that round-trips unchanged")
        void messageRoundTripsUnchanged() {
            assertThat(componentNamed("message").getType()).isEqualTo(String.class);

            TransactionAddResponse restored = roundTrip(sample(), TransactionAddResponse.class);

            assertThat(restored.message()).isEqualTo(CONFIRMATION_MESSAGE);
        }

        @Test
        @DisplayName("withConfirmation builds the standard confirmation message and normalizes money")
        void withConfirmationBuildsStandardMessage() {
            TransactionAddResponse dto = TransactionAddResponse.withConfirmation(
                    TRANSACTION_ID, ACCOUNT_ID, new BigDecimal(SAMPLE_AMOUNT_LITERAL));

            assertThat(dto.transactionId()).isEqualTo(TRANSACTION_ID);
            assertThat(dto.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(dto.message()).isEqualTo(CONFIRMATION_MESSAGE);
            assertScale(dto.amount(), 2);

            // The confirmation exposes no sensitive internals (no PAN / card number).
            assertThat(dto.message()).doesNotContainIgnoringCase("card");
        }
    }

    // =====================================================================
    // Bean Validation — the @Size constraints enforce the fixed field widths
    // =====================================================================

    @Nested
    @DisplayName("Bean Validation constraints")
    class BeanValidation {

        @Test
        @DisplayName("a fully-populated response satisfies every declared constraint")
        void fullyPopulatedResponseIsValid() {
            assertThat(validate(sample())).isEmpty();
        }

        @Test
        @DisplayName("a transactionId wider than 16 characters violates @Size on 'transactionId'")
        void oversizeTransactionIdViolatesSize() {
            TransactionAddResponse dto = new TransactionAddResponse(
                    "TXN00000000001234", ACCOUNT_ID,
                    new BigDecimal(SAMPLE_AMOUNT_LITERAL), CONFIRMATION_MESSAGE);

            var violations = validate(dto);

            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("transactionId"));
        }

        @Test
        @DisplayName("an accountId wider than 11 characters violates @Size on 'accountId'")
        void oversizeAccountIdViolatesSize() {
            TransactionAddResponse dto = new TransactionAddResponse(
                    TRANSACTION_ID, "000000001234",
                    new BigDecimal(SAMPLE_AMOUNT_LITERAL), CONFIRMATION_MESSAGE);

            var violations = validate(dto);

            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("accountId"));
        }
    }
}
