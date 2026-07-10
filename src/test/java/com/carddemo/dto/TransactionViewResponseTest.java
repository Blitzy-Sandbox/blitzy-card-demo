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
 * Framework-free unit test for {@link TransactionViewResponse}, the immutable
 * transaction-detail-view DTO of the CardDemo COBOL&rarr;Java 25 / Spring Boot
 * 3.5.11 migration.
 *
 * <p>{@code TransactionViewResponse} is the payload of the legacy online
 * <em>Transaction View</em> screen (BMS mapset {@code COTRN01}, program
 * {@code COTRN01C}, online transaction {@code CT01}). Its thirteen components are
 * derived from the transaction record layout {@code TRAN-RECORD} (copybook
 * {@code CVTRA05Y}, {@code RECLN} 350) with maximum widths taken from that
 * <em>record</em> — never the narrower BMS <em>screen</em> widths in
 * {@code COTRN01.CPY}. The COBOL source is referenced read-only at commit SHA
 * {@code 27d6c6f} (full {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}) and is
 * never copied into the target.</p>
 *
 * <p>This suite pins the four migration contracts that make the DTO a faithful,
 * REST-safe stand-in for {@code TRAN-RECORD}. Each is exercised as its own
 * {@link Nested} group:</p>
 * <ol>
 *   <li><strong>JSON round-trip.</strong> A fully-populated instance survives a
 *       serialize/deserialize cycle with record equality intact, the emitted JSON
 *       exposes exactly the thirteen mapped keys, and numeric-but-textual COBOL
 *       identifiers ({@code TRAN-CAT-CD 9(04)}, {@code TRAN-MERCHANT-ID 9(09)})
 *       serialize as JSON <em>strings</em> so their fixed width and leading zeros
 *       survive byte-for-byte.</li>
 *   <li><strong>Monetary precision.</strong> {@code TRAN-AMT S9(09)V99} maps to a
 *       {@link BigDecimal} that serializes as a plain, scale-2 literal
 *       ({@code 100.00} / {@code -50.00}, never {@code 1.0E2}) and is never a
 *       floating-point type (AAP &sect;0.8.2 decimal-fidelity rule — zero
 *       {@code float}/{@code double} in financial fields).</li>
 *   <li><strong>Timestamp contract.</strong> {@code TRAN-ORIG-TS} and
 *       {@code TRAN-PROC-TS} are {@code X(26)} character fields kept verbatim as
 *       26-character {@link String}s and are deliberately not parsed into
 *       {@code java.time} types, so the exact on-file format survives the
 *       round-trip unchanged (external interface contract, Gates 1 and 5).</li>
 *   <li><strong>PAN masking.</strong> {@code TRAN-CARD-NUM X(16)} is masked by the
 *       DTO's canonical constructor so that only the last four digits remain
 *       visible; the full Primary Account Number never appears on the accessor or
 *       in a serialized JSON body.</li>
 * </ol>
 *
 * <p>The test loads no Spring context and uses no database, container, mock, file,
 * or network I/O: it relies solely on the shared {@link DtoTestSupport} helpers
 * (the production-mirroring Jackson mapper and the reflection/security
 * assertions), so it runs fast and deterministically. Design rationale for the
 * masking, string-identifier, and verbatim-timestamp decisions lives in
 * {@code docs/decision-log.md}, not in these comments.</p>
 */
@DisplayName("TransactionViewResponse — CT01 view DTO: JSON contract, money precision, X(26) timestamps, PAN masking")
class TransactionViewResponseTest {

    // ---------------------------------------------------------------------
    // Shared fixture values. Every value is deliberately within the DTO's
    // declared @Size constraints so the fully-populated fixture is also a valid
    // Bean-Validation instance. The synthetic PAN is not a real card number; its
    // last four digits are 3456.
    // ---------------------------------------------------------------------

    /** {@code TRAN-ID X(16)} — sixteen characters wide. */
    private static final String TRANSACTION_ID = "TXN1234567890123";

    /** Synthetic, unmasked 16-digit PAN ({@code TRAN-CARD-NUM X(16)}); last four are 3456. */
    private static final String RAW_CARD_NUMBER = "1234567890123456";

    /** Expected visible last-four digits after masking. */
    private static final String EXPECTED_LAST_4 = "3456";

    /** Masked form the constructor produces for a 16-character PAN: twelve mask chars + last four. */
    private static final String EXPECTED_MASKED_CARD = "************3456";

    /** {@code TRAN-TYPE-CD X(02)}. */
    private static final String TYPE_CODE = "01";

    /** {@code TRAN-CAT-CD 9(04)} — numeric string; leading zero must be preserved. */
    private static final String CATEGORY_CODE = "0005";

    /** {@code TRAN-SOURCE X(10)}. */
    private static final String SOURCE = "POS";

    /** {@code TRAN-DESC X(100)}. */
    private static final String DESCRIPTION = "GROCERY STORE PURCHASE";

    /** Plain, scale-2 monetary literal used for the fully-populated fixture. */
    private static final String SAMPLE_AMOUNT_LITERAL = "100.00";

    /** {@code TRAN-ORIG-TS X(26)} — exactly 26 characters. */
    private static final String ORIGINAL_TS = "2024-01-31 12:34:56.123456";

    /** {@code TRAN-PROC-TS X(26)} — exactly 26 characters. */
    private static final String PROCESSED_TS = "2024-02-01 08:00:00.000000";

    /** {@code TRAN-MERCHANT-ID 9(09)} — numeric string; leading zeros must be preserved. */
    private static final String MERCHANT_ID = "000000123";

    /** {@code TRAN-MERCHANT-NAME X(50)}. */
    private static final String MERCHANT_NAME = "ACME CORPORATION";

    /** {@code TRAN-MERCHANT-CITY X(50)}. */
    private static final String MERCHANT_CITY = "SEATTLE";

    /** {@code TRAN-MERCHANT-ZIP X(10)}. */
    private static final String MERCHANT_ZIP = "98101";

    /** Fixed-width character length of the two timestamp fields ({@code X(26)}). */
    private static final int TIMESTAMP_LENGTH = 26;

    /**
     * The thirteen JSON keys the DTO must expose, one per mapped
     * {@code TRAN-RECORD} field. Order here is irrelevant to the assertions, which
     * compare membership regardless of order.
     */
    private static final String[] EXPECTED_JSON_KEYS = {
        "transactionId", "cardNumber", "typeCode", "categoryCode", "source",
        "description", "amount", "originalTimestamp", "processedTimestamp",
        "merchantId", "merchantName", "merchantCity", "merchantZip"
    };

    // ---------------------------------------------------------------------
    // Fixture factories and reflection helper.
    // ---------------------------------------------------------------------

    /**
     * Builds a fully-populated view with a caller-supplied {@code amount}, reusing
     * the shared field constants for every other component. The <em>raw</em>
     * (unmasked) card number is passed so the canonical constructor's masking
     * behaviour is exercised end-to-end.
     *
     * @param amount the monetary amount to place in the view
     * @return a populated {@link TransactionViewResponse}
     */
    private static TransactionViewResponse sample(BigDecimal amount) {
        return new TransactionViewResponse(
                TRANSACTION_ID,
                RAW_CARD_NUMBER,
                TYPE_CODE,
                CATEGORY_CODE,
                SOURCE,
                DESCRIPTION,
                amount,
                ORIGINAL_TS,
                PROCESSED_TS,
                MERCHANT_ID,
                MERCHANT_NAME,
                MERCHANT_CITY,
                MERCHANT_ZIP);
    }

    /**
     * A fully-populated view whose amount is the canonical scale-2 sample
     * {@code 100.00}.
     *
     * @return a populated {@link TransactionViewResponse}
     */
    private static TransactionViewResponse fullyPopulated() {
        return sample(new BigDecimal(SAMPLE_AMOUNT_LITERAL));
    }

    /**
     * Locates the record component of {@link TransactionViewResponse} with the
     * given name, for type-level (reflection) assertions.
     *
     * @param name the record component name to find
     * @return the matching {@link RecordComponent}
     */
    private static RecordComponent componentNamed(String name) {
        for (RecordComponent component : TransactionViewResponse.class.getRecordComponents()) {
            if (component.getName().equals(name)) {
                return component;
            }
        }
        throw new AssertionError(
                "TransactionViewResponse has no record component named '" + name + "'");
    }

    // =====================================================================
    // Phase 1 — JSON round-trip, exact key set, and String-typed identifiers
    // =====================================================================

    @Nested
    @DisplayName("JSON round-trip")
    class RoundTripJson {

        @Test
        @DisplayName("a fully-populated view round-trips with every field preserved (PAN already masked)")
        void roundTripsWithoutLoss() {
            TransactionViewResponse original = fullyPopulated();

            TransactionViewResponse restored = roundTrip(original, TransactionViewResponse.class);

            // A record derives equals() from every component. The constructor masks
            // cardNumber on the way in, and masking is idempotent, so the stored value
            // is already "************3456"; the serialize/deserialize cycle therefore
            // reproduces an equal instance with no field lost.
            assertThat(restored).isEqualTo(original);
        }

        @Test
        @DisplayName("serialized JSON exposes exactly the thirteen mapped TRAN-RECORD keys")
        void exposesExactlyThirteenKeys() {
            String json = toJson(fullyPopulated());

            Map<String, Object> fields = fromJson(json, new TypeReference<Map<String, Object>>() { });

            assertThat(fields.keySet()).containsExactlyInAnyOrder(EXPECTED_JSON_KEYS);
        }

        @Test
        @DisplayName("numeric COBOL identifiers serialize as JSON strings, preserving width and leading zeros")
        void numericIdentifiersSerializeAsStrings() {
            String json = toJson(fullyPopulated());

            Map<String, Object> fields = fromJson(json, new TypeReference<Map<String, Object>>() { });

            // TRAN-CAT-CD 9(04) and TRAN-MERCHANT-ID 9(09) are numeric in COBOL but are
            // represented as String so their fixed width and leading zeros survive; a
            // JSON number would deserialize to Integer/Long and silently drop the zeros.
            assertThat(fields.get("categoryCode"))
                    .as("categoryCode must stay a String to preserve the leading zero")
                    .isInstanceOf(String.class)
                    .isEqualTo(CATEGORY_CODE);
            assertThat(fields.get("merchantId"))
                    .as("merchantId must stay a String to preserve leading zeros")
                    .isInstanceOf(String.class)
                    .isEqualTo(MERCHANT_ID);
            assertThat(fields.get("transactionId"))
                    .as("transactionId must stay a String")
                    .isInstanceOf(String.class)
                    .isEqualTo(TRANSACTION_ID);
        }
    }

    // =====================================================================
    // Phase 2 — Monetary amount: PLAIN notation and scale 2 (CRITICAL)
    // =====================================================================

    @Nested
    @DisplayName("Monetary amount: PLAIN notation and scale 2")
    class MoneyPrecision {

        @Test
        @DisplayName("a positive amount serializes as a plain scale-2 literal (100.00, never 1.0E2)")
        void serializesPositiveMoneyAsPlainScaleTwo() {
            TransactionViewResponse dto = fullyPopulated();

            String json = toJson(dto);

            assertJsonNumberIsPlain(json, "amount", "100.00");
            assertScale(dto.amount(), 2);
        }

        @Test
        @DisplayName("a negative amount serializes as a plain scale-2 literal (-50.00)")
        void serializesNegativeMoneyAsPlainScaleTwo() {
            TransactionViewResponse dto = sample(new BigDecimal("-50.00"));

            String json = toJson(dto);

            assertJsonNumberIsPlain(json, "amount", "-50.00");
            assertScale(dto.amount(), 2);
        }

        @Test
        @DisplayName("the canonical constructor normalizes amount to the V99 scale of 2")
        void normalizesScaleToTwo() {
            TransactionViewResponse dto = sample(new BigDecimal("5"));

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

    // =====================================================================
    // Phase 3 — X(26) timestamps preserved verbatim as Strings (CRITICAL)
    // =====================================================================

    @Nested
    @DisplayName("X(26) timestamps preserved verbatim as Strings")
    class TimestampContract {

        @Test
        @DisplayName("both 26-character timestamps serialize as JSON strings and round-trip unchanged")
        void timestampsRoundTripAsStrings() throws JsonProcessingException {
            TransactionViewResponse dto = fullyPopulated();

            String json = toJson(dto);
            JsonNode tree = objectMapper().readTree(json);

            // Serialized shape: each timestamp is a JSON string (isTextual), not a
            // numeric timestamp array or an object.
            assertThat(tree.get("originalTimestamp").isTextual())
                    .as("originalTimestamp must serialize as a JSON string")
                    .isTrue();
            assertThat(tree.get("processedTimestamp").isTextual())
                    .as("processedTimestamp must serialize as a JSON string")
                    .isTrue();

            // Exact 26-character on-file format is preserved byte-for-byte.
            assertThat(tree.get("originalTimestamp").asText())
                    .isEqualTo(ORIGINAL_TS)
                    .hasSize(TIMESTAMP_LENGTH);
            assertThat(tree.get("processedTimestamp").asText())
                    .isEqualTo(PROCESSED_TS)
                    .hasSize(TIMESTAMP_LENGTH);

            // The values survive a full serialize/deserialize cycle unchanged.
            TransactionViewResponse restored = roundTrip(dto, TransactionViewResponse.class);

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
            // Explicitly reject the java.time types the field must NOT become, so a
            // future "helpful" refactor to LocalDateTime is caught here.
            assertThat(originalType).isNotIn(LocalDate.class, LocalDateTime.class);
            assertThat(processedType).isNotIn(LocalDate.class, LocalDateTime.class);
        }
    }

    // =====================================================================
    // Phase 4 — PAN masking at the REST boundary
    // =====================================================================

    @Nested
    @DisplayName("PAN masking at the REST boundary")
    class PanMasking {

        @Test
        @DisplayName("cardNumber is masked to its last four digits by the canonical constructor")
        void masksAllButLastFour() {
            TransactionViewResponse dto = fullyPopulated();

            // Only the last four digits are visible; the masked prefix holds no digit.
            assertPanMaskedLast4(dto.cardNumber(), EXPECTED_LAST_4);
            assertThat(dto.cardNumber())
                    .as("a 16-character PAN masks to twelve mask characters plus the last four")
                    .isEqualTo(EXPECTED_MASKED_CARD)
                    .doesNotContain(RAW_CARD_NUMBER)
                    .endsWith(EXPECTED_LAST_4);
        }

        @Test
        @DisplayName("serialized JSON never contains the full 16-digit PAN, only the masked form")
        void jsonNeverContainsFullPan() {
            String json = toJson(fullyPopulated());

            assertThat(json)
                    .as("the transaction view must not leak the full sixteen-digit PAN")
                    .doesNotContain(RAW_CARD_NUMBER)
                    .contains(EXPECTED_MASKED_CARD);

            // Structural confirmation: the cardNumber key carries exactly the masked value.
            Map<String, Object> fields = fromJson(json, new TypeReference<Map<String, Object>>() { });
            assertThat(fields.get("cardNumber")).isEqualTo(EXPECTED_MASKED_CARD);
        }

        @Test
        @DisplayName("masking is idempotent — an already-masked value survives a round-trip unchanged")
        void maskingIsIdempotentAcrossRoundTrip() {
            // Re-constructing from the masked value (as deserialization does) must not
            // re-mask the visible last four or alter the value in any way.
            TransactionViewResponse restored = roundTrip(fullyPopulated(), TransactionViewResponse.class);

            assertThat(restored.cardNumber()).isEqualTo(EXPECTED_MASKED_CARD);
        }
    }

    // =====================================================================
    // Bean Validation — the fully-populated fixture satisfies every @Size rule
    // =====================================================================

    @Nested
    @DisplayName("Bean Validation constraints")
    class BeanValidation {

        @Test
        @DisplayName("a fully-populated view satisfies every declared @Size constraint")
        void fullyPopulatedViewIsValid() {
            assertThat(validate(fullyPopulated())).isEmpty();
        }
    }
}
