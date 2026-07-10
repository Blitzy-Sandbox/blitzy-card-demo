package com.carddemo.dto;

import static com.carddemo.dto.DtoTestSupport.assertJsonNumberIsPlain;
import static com.carddemo.dto.DtoTestSupport.assertPanMaskedLast4;
import static com.carddemo.dto.DtoTestSupport.assertScale;
import static com.carddemo.dto.DtoTestSupport.fromJson;
import static com.carddemo.dto.DtoTestSupport.objectMapper;
import static com.carddemo.dto.DtoTestSupport.roundTrip;
import static com.carddemo.dto.DtoTestSupport.toJson;
import static com.carddemo.dto.DtoTestSupport.validate;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StatementTransactionDto}, the flattened statement /
 * report transaction line migrated from the legacy COBOL copybook
 * {@code COSTM01.CPY} record {@code TRNX-RECORD} (frozen COBOL source at SHA
 * {@code 27d6c6f}).
 *
 * <p>These tests pin the four migration contracts that make the DTO a faithful,
 * REST-safe stand-in for {@code TRNX-RECORD}. Each is exercised as its own
 * {@link Nested} group:</p>
 * <ol>
 *   <li><strong>JSON round-trip</strong> - a fully-populated line survives a
 *       serialize/deserialize cycle unchanged and exposes exactly the thirteen
 *       mapped {@code TRNX-RECORD} fields (the trailing {@code FILLER X(20)} is
 *       intentionally unmapped).</li>
 *   <li><strong>Monetary precision</strong> - {@code TRNX-AMT S9(09)V99} maps to
 *       a {@link BigDecimal} that serializes as a plain, scale-2 literal
 *       ({@code 100.00}, never {@code 1.0E2}) and is never a floating-point type
 *       (AAP decimal-fidelity rule).</li>
 *   <li><strong>Timestamp contract</strong> - {@code TRNX-ORIG-TS} and
 *       {@code TRNX-PROC-TS} are {@code X(26)} character fields kept verbatim as
 *       26-character {@link String}s and are deliberately not parsed into
 *       {@code java.time} types (external interface contract, Gates 1 and 5).</li>
 *   <li><strong>PAN masking</strong> - the full Primary Account Number never
 *       leaves the REST boundary; {@link StatementTransactionDto#toMaskedView()}
 *       and {@link StatementTransactionDto#maskedCardNumber()} expose only the
 *       last four digits.</li>
 * </ol>
 *
 * <p>The suite is framework-free: it uses JUnit 5 and AssertJ with the shared
 * {@link DtoTestSupport} helpers (which mirror the production serialization
 * contract) and loads no Spring context.</p>
 */
class StatementTransactionDtoTest {

    // ---------------------------------------------------------------------
    // Shared fixture values. Every value is deliberately within the DTO's
    // declared @Size / @Pattern constraints so the fully-populated fixture is
    // also a valid Bean-Validation instance. The synthetic PAN is not a real
    // card number; its last four digits are 3456.
    // ---------------------------------------------------------------------

    /** Synthetic 16-digit PAN ({@code TRNX-CARD-NUM X(16)}); last four are 3456. */
    private static final String CARD_NUMBER = "1234567890123456";

    /** Expected visible last-four digits after masking. */
    private static final String EXPECTED_LAST_4 = "3456";

    /** Masked form produced for a 16-character PAN: twelve mask chars + last four. */
    private static final String EXPECTED_MASKED_CARD = "************3456";

    /** {@code TRNX-ID X(16)}. */
    private static final String TRANSACTION_ID = "TXN1234567890123";

    /** {@code TRNX-TYPE-CD X(02)}. */
    private static final String TYPE_CODE = "01";

    /** {@code TRNX-CAT-CD 9(04)} - numeric string, width preserved. */
    private static final String CATEGORY_CODE = "0005";

    /** {@code TRNX-SOURCE X(10)}. */
    private static final String SOURCE = "POS";

    /** {@code TRNX-DESC X(100)}. */
    private static final String DESCRIPTION = "GROCERY STORE PURCHASE";

    /** Plain, scale-2 monetary literal used for the fully-populated fixture. */
    private static final String SAMPLE_AMOUNT_LITERAL = "100.00";

    /** {@code TRNX-MERCHANT-ID 9(09)} - numeric string, width preserved. */
    private static final String MERCHANT_ID = "000000123";

    /** {@code TRNX-MERCHANT-NAME X(50)}. */
    private static final String MERCHANT_NAME = "ACME CORPORATION";

    /** {@code TRNX-MERCHANT-CITY X(50)}. */
    private static final String MERCHANT_CITY = "SEATTLE";

    /** {@code TRNX-MERCHANT-ZIP X(10)}. */
    private static final String MERCHANT_ZIP = "98101";

    /** {@code TRNX-ORIG-TS X(26)} - exactly 26 characters. */
    private static final String ORIGINAL_TS = "2024-01-31 12:34:56.123456";

    /** {@code TRNX-PROC-TS X(26)} - exactly 26 characters. */
    private static final String PROCESSED_TS = "2024-02-01 08:00:00.000000";

    /** Fixed-width character length of the two timestamp fields ({@code X(26)}). */
    private static final int TIMESTAMP_LENGTH = 26;

    /**
     * The thirteen JSON keys the DTO must expose, one per mapped
     * {@code TRNX-RECORD} field. Order here is irrelevant to the assertions,
     * which compare membership regardless of order.
     */
    private static final String[] EXPECTED_JSON_KEYS = {
        "cardNumber", "transactionId", "typeCode", "categoryCode", "source",
        "description", "amount", "merchantId", "merchantName", "merchantCity",
        "merchantZip", "originalTimestamp", "processedTimestamp"
    };

    // ---------------------------------------------------------------------
    // Fixture factories and reflection helper.
    // ---------------------------------------------------------------------

    /**
     * Builds a fully-populated line with a caller-supplied {@code amount},
     * reusing the shared field constants for every other component.
     *
     * @param amount the monetary amount to place in the line
     * @return a populated {@link StatementTransactionDto}
     */
    private static StatementTransactionDto sample(BigDecimal amount) {
        return new StatementTransactionDto(
                CARD_NUMBER,
                TRANSACTION_ID,
                TYPE_CODE,
                CATEGORY_CODE,
                SOURCE,
                DESCRIPTION,
                amount,
                MERCHANT_ID,
                MERCHANT_NAME,
                MERCHANT_CITY,
                MERCHANT_ZIP,
                ORIGINAL_TS,
                PROCESSED_TS);
    }

    /**
     * A fully-populated, valid statement line whose amount is the canonical
     * scale-2 sample {@code 100.00}.
     *
     * @return a populated {@link StatementTransactionDto}
     */
    private static StatementTransactionDto fullyPopulated() {
        return sample(new BigDecimal(SAMPLE_AMOUNT_LITERAL));
    }

    /**
     * Locates the record component of {@link StatementTransactionDto} with the
     * given name, for type-level assertions.
     *
     * @param name the record component name to find
     * @return the matching {@link RecordComponent}
     */
    private static RecordComponent componentNamed(String name) {
        for (RecordComponent component : StatementTransactionDto.class.getRecordComponents()) {
            if (component.getName().equals(name)) {
                return component;
            }
        }
        throw new AssertionError(
                "StatementTransactionDto has no record component named '" + name + "'");
    }

    @Nested
    @DisplayName("JSON round-trip")
    class RoundTripJson {

        @Test
        @DisplayName("a fully-populated line round-trips with every mapped field preserved, "
                + "the PAN deliberately masked on serialization")
        void roundTripsWithoutLoss() {
            StatementTransactionDto original = fullyPopulated();

            StatementTransactionDto restored = roundTrip(original, StatementTransactionDto.class);

            // The cardNumber component is always masked on serialization (see
            // StatementTransactionDto.MaskedCardNumberSerializer), so a JSON round-trip
            // yields the masked view: every other TRNX-RECORD field survives unchanged
            // while the full PAN is deliberately reduced to its last four digits.
            assertThat(restored).isEqualTo(original.toMaskedView());
        }

        @Test
        @DisplayName("all thirteen COSTM01 fields are present as JSON keys")
        void exposesExactlyThirteenKeys() {
            String json = toJson(fullyPopulated());

            Map<String, Object> tree = fromJson(json, new TypeReference<Map<String, Object>>() { });

            assertThat(tree.keySet()).containsExactlyInAnyOrder(EXPECTED_JSON_KEYS);
        }
    }

    @Nested
    @DisplayName("Monetary amount: PLAIN notation and scale 2")
    class MoneyPrecision {

        @Test
        @DisplayName("amount serializes as a plain scale-2 literal (100.00, never 1.0E2)")
        void serializesMoneyAsPlainScaleTwo() {
            StatementTransactionDto dto = fullyPopulated();

            String json = toJson(dto);

            assertJsonNumberIsPlain(json, "amount", "100.00");
            assertScale(dto.amount(), 2);
        }

        @Test
        @DisplayName("the canonical constructor normalizes amount to the V99 scale of 2")
        void normalizesScaleToTwo() {
            StatementTransactionDto dto = sample(new BigDecimal("5"));

            assertScale(dto.amount(), 2);
            assertThat(dto.amount().toPlainString()).isEqualTo("5.00");
        }

        @Test
        @DisplayName("the amount component is BigDecimal, never a floating-point type")
        void amountComponentIsBigDecimal() {
            Class<?> amountType = componentNamed("amount").getType();

            assertThat(amountType).isEqualTo(BigDecimal.class);
            assertThat(amountType).isNotIn(double.class, float.class, Double.class, Float.class);
        }
    }

    @Nested
    @DisplayName("X(26) timestamps preserved verbatim as Strings")
    class TimestampContract {

        @Test
        @DisplayName("both 26-character timestamps round-trip as JSON strings, unchanged")
        void timestampsRoundTripAsStrings() throws JsonProcessingException {
            StatementTransactionDto dto = fullyPopulated();

            String json = toJson(dto);
            JsonNode tree = objectMapper().readTree(json);

            assertThat(tree.get("originalTimestamp").isTextual())
                    .as("originalTimestamp must serialize as a JSON string")
                    .isTrue();
            assertThat(tree.get("processedTimestamp").isTextual())
                    .as("processedTimestamp must serialize as a JSON string")
                    .isTrue();
            assertThat(tree.get("originalTimestamp").asText())
                    .isEqualTo(ORIGINAL_TS)
                    .hasSize(TIMESTAMP_LENGTH);
            assertThat(tree.get("processedTimestamp").asText())
                    .isEqualTo(PROCESSED_TS)
                    .hasSize(TIMESTAMP_LENGTH);

            StatementTransactionDto restored = roundTrip(dto, StatementTransactionDto.class);

            assertThat(restored.originalTimestamp())
                    .isEqualTo(ORIGINAL_TS)
                    .hasSize(TIMESTAMP_LENGTH);
            assertThat(restored.processedTimestamp())
                    .isEqualTo(PROCESSED_TS)
                    .hasSize(TIMESTAMP_LENGTH);
        }

        @Test
        @DisplayName("both timestamp components are java.lang.String, never a date/time type")
        void timestampComponentsAreStrings() {
            Class<?> originalType = componentNamed("originalTimestamp").getType();
            Class<?> processedType = componentNamed("processedTimestamp").getType();

            assertThat(originalType).isEqualTo(String.class);
            assertThat(processedType).isEqualTo(String.class);
            assertThat(originalType).isNotIn(LocalDate.class, LocalDateTime.class);
            assertThat(processedType).isNotIn(LocalDate.class, LocalDateTime.class);
        }
    }

    @Nested
    @DisplayName("PAN masking at the REST boundary")
    class PanMasking {

        @Test
        @DisplayName("maskedCardNumber() leaves only the last four digits visible")
        void masksAllButLastFour() {
            StatementTransactionDto dto = fullyPopulated();

            assertPanMaskedLast4(dto.maskedCardNumber(), EXPECTED_LAST_4);
            assertThat(dto.toMaskedView().cardNumber()).isEqualTo(EXPECTED_MASKED_CARD);
        }

        @Test
        @DisplayName("the masked REST view never serializes the full PAN")
        void maskedViewJsonHidesFullPan() {
            StatementTransactionDto masked = fullyPopulated().toMaskedView();

            String json = toJson(masked);

            assertThat(json).doesNotContain(CARD_NUMBER);
            Map<String, Object> tree = fromJson(json, new TypeReference<Map<String, Object>>() { });
            assertThat(tree.keySet()).containsExactlyInAnyOrder(EXPECTED_JSON_KEYS);
            assertThat(tree.get("cardNumber")).isEqualTo(EXPECTED_MASKED_CARD);
        }

        @Test
        @DisplayName("even the natural (unmasked-view) serialization never emits the full PAN")
        void naturalViewNeverSerializesFullPan() {
            // Defense in depth: the cardNumber component itself is annotated with
            // MaskedCardNumberSerializer, so masking applies even when the raw record
            // (rather than toMaskedView()) is serialized. The full PAN is available only
            // via the in-memory cardNumber() accessor, never in a JSON body.
            String json = toJson(fullyPopulated());

            assertThat(json).doesNotContain(CARD_NUMBER);
            assertThat(json).contains(EXPECTED_MASKED_CARD);
        }
    }

    @Nested
    @DisplayName("Bean Validation constraints")
    class BeanValidation {

        @Test
        @DisplayName("a fully-populated line satisfies every declared constraint")
        void fullyPopulatedLineIsValid() {
            assertThat(validate(fullyPopulated())).isEmpty();
        }
    }
}
