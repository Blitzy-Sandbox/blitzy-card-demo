package com.carddemo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Framework-free unit test for {@link CardViewResponse}, the immutable Card Detail
 * View payload of the CardDemo COBOL&rarr;Java 25 / Spring Boot 3.5.11 migration.
 *
 * <p>{@code CardViewResponse} is the idiomatic Spring translation of the legacy 3270
 * BMS symbolic map {@code COCRDSL} (mapset {@code app/cpy-bms/COCRDSL.CPY}, online
 * transaction {@code CCDL}), which the program {@code COCRDSLC} populated from the
 * VSAM {@code CARD-RECORD} layout (copybook {@code app/cpy/CVACT02Y.cpy}, referenced
 * read-only by source SHA {@code 27d6c6f}). Its six components map to the record and
 * screen fields as follows: {@code accountId} &larr; {@code CARD-ACCT-ID} 9(11)
 * (screen {@code ACCTSID}), {@code cardNumber} &larr; {@code CARD-NUM} X(16) (screen
 * {@code CARDSID}, masked), {@code embossedName} &larr; {@code CARD-EMBOSSED-NAME}
 * X(50) (screen {@code CRDNAME}), {@code activeStatus} &larr;
 * {@code CARD-ACTIVE-STATUS} X(01) (screen {@code CRDSTCD}), {@code expirationDate}
 * &larr; {@code CARD-EXPIRAION-DATE} X(10) (screen {@code EXPMON}/{@code EXPYEAR}),
 * plus the JPA optimistic-lock {@code version} echoed back for the Card Update
 * round-trip (AAP &sect;0.8.4).</p>
 *
 * <p>This suite pins the five behaviours that guard the interface contract and the
 * card-data security posture of the DTO:</p>
 * <ol>
 *   <li><strong>JSON round-trip.</strong> A serialize/deserialize cycle preserves
 *       full record equality and the emitted JSON exposes exactly the keys
 *       {@code accountId}/{@code cardNumber}/{@code embossedName}/
 *       {@code activeStatus}/{@code expirationDate}/{@code version}.</li>
 *   <li><strong>No CVV.</strong> The record declares no CVV component, so
 *       {@code CARD-CVV-CD} 9(03) &mdash; present in the source record and a secret
 *       &mdash; can never surface on this REST-facing DTO, neither as a component nor
 *       as a JSON key.</li>
 *   <li><strong>PAN masking.</strong> {@code cardNumber} carries only a masked
 *       Primary Account Number (last four digits visible); neither the value nor its
 *       serialized JSON ever contains the full sixteen-digit PAN.</li>
 *   <li><strong>ISO date.</strong> {@code expirationDate} is a {@link LocalDate} and
 *       serializes as an ISO-8601 string ({@code "2027-12-31"}), never as an epoch
 *       number or a {@code [year, month, day]} array.</li>
 *   <li><strong>Version passthrough.</strong> The {@link Long} {@code version}
 *       survives a round-trip unchanged, whether populated or {@code null}.</li>
 * </ol>
 *
 * <p>The test loads no Spring context and uses no database, container, mock, file, or
 * network I/O: it relies solely on the shared {@link DtoTestSupport} helpers (the
 * production-mirroring Jackson mapper and the reflection/security assertions), so it
 * runs fast and deterministically. Design rationale for the masking and no-CVV
 * decisions lives in {@code docs/decision-log.md}, not in these comments.</p>
 */
@DisplayName("CardViewResponse — card-view DTO: JSON contract, no-CVV safety, PAN masking, ISO expiry, version echo")
class CardViewResponseTest {

    /**
     * Account identifier fixture with a deliberate leading zero and the full
     * eleven-digit width of {@code CARD-ACCT-ID} 9(11); used to prove the value is
     * preserved as text rather than collapsed to a number.
     */
    private static final String ACCOUNT_ID = "00000000012";

    /** The only portion of a card number a masked PAN is allowed to reveal. */
    private static final String LAST4 = "3456";

    /**
     * A full, unmasked sixteen-digit PAN ending in {@link #LAST4}. It is the value
     * that must <em>never</em> appear in the DTO or its serialized JSON; it is passed
     * to the canonical constructor (which masks it) and reused as a negative-assertion
     * needle.
     */
    private static final String FULL_PAN = "4111111111113456";

    /**
     * The masked card number the canonical constructor must produce from
     * {@link #FULL_PAN}: twelve mask characters followed by {@link #LAST4}, exactly
     * sixteen characters wide to match {@code CARD-NUM} X(16) while exposing only the
     * last four digits.
     */
    private static final String MASKED_PAN = "*".repeat(FULL_PAN.length() - LAST4.length()) + LAST4;

    /** Embossed card-holder name fixture ({@code CARD-EMBOSSED-NAME} X(50)). */
    private static final String EMBOSSED_NAME = "JOHN Q CARDHOLDER";

    /** Single-character active-status flag ({@code CARD-ACTIVE-STATUS} X(01); {@code Y} = active). */
    private static final String ACTIVE_STATUS = "Y";

    /** Card expiration date fixture ({@code CARD-EXPIRAION-DATE} X(10)). */
    private static final LocalDate EXPIRATION_DATE = LocalDate.of(2027, 12, 31);

    /** The ISO-8601 textual form {@link #EXPIRATION_DATE} must serialize to. */
    private static final String EXPIRATION_ISO = "2027-12-31";

    /** Optimistic-lock version fixture echoed back on the Card Update round-trip. */
    private static final Long VERSION = 7L;

    /**
     * Builds the canonical sample response shared by the tests. The full PAN is
     * handed to the canonical constructor, which masks it to {@link #MASKED_PAN}, so
     * {@code sampleResponse().cardNumber()} is always the masked value.
     *
     * @return a fully populated {@link CardViewResponse} sample
     */
    private static CardViewResponse sampleResponse() {
        return new CardViewResponse(
                ACCOUNT_ID, FULL_PAN, EMBOSSED_NAME, ACTIVE_STATUS, EXPIRATION_DATE, VERSION);
    }

    /**
     * Deserializes JSON into a generic field map without an unchecked cast, so key
     * and value assertions can inspect the raw serialized shape.
     *
     * @param json the JSON document to parse
     * @return an insertion-ordered map of top-level field names to their values
     */
    private static Map<String, Object> parseFields(String json) {
        return DtoTestSupport.fromJson(json, new TypeReference<Map<String, Object>>() { });
    }

    // ---------------------------------------------------------------------
    // Phase 1 — JSON round-trip and exact key set
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Phase 1: JSON round-trip preserves full record equality")
    void jsonRoundTripPreservesEquality() {
        CardViewResponse original = sampleResponse();

        CardViewResponse roundTripped = DtoTestSupport.roundTrip(original, CardViewResponse.class);

        // A record derives equals() from every component, so equality proves each
        // field survived the serialize/deserialize cycle unchanged. PAN masking is
        // idempotent, so the already-masked cardNumber is stable across the trip.
        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    @DisplayName("Phase 1: serialized JSON exposes exactly the six card-view keys")
    void serializedJsonExposesExactlyExpectedKeys() {
        String json = DtoTestSupport.toJson(sampleResponse());

        Map<String, Object> fields = parseFields(json);

        assertThat(fields).containsOnlyKeys(
                "accountId", "cardNumber", "embossedName", "activeStatus", "expirationDate", "version");
        // String-valued fields carry their exact text; version is verified via the
        // round-trip in Phase 5 (a small JSON integer deserializes to Integer here).
        assertThat(fields)
                .containsEntry("accountId", ACCOUNT_ID)
                .containsEntry("cardNumber", MASKED_PAN)
                .containsEntry("embossedName", EMBOSSED_NAME)
                .containsEntry("activeStatus", ACTIVE_STATUS)
                .containsEntry("expirationDate", EXPIRATION_ISO);
    }

    // ---------------------------------------------------------------------
    // Phase 2 — Sensitive-data safety: CARD-CVV-CD must never surface
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Phase 2: record declares no CVV component")
    void recordHasNoCvvComponent() {
        // CARD-CVV-CD 9(03) exists in CVACT02Y but must never reach a REST DTO.
        DtoTestSupport.assertNoComponentNamed(CardViewResponse.class, "cvv");
    }

    @Test
    @DisplayName("Phase 2: serialized JSON contains no CVV key in any form")
    void serializedJsonContainsNoCvvKey() {
        String json = DtoTestSupport.toJson(sampleResponse());

        // String scan across the whole payload catches cvv, cardCvv, and cvvCode
        // (each contains the token "cvv") regardless of casing.
        assertThat(json)
                .as("card-view JSON must never leak a CVV field")
                .doesNotContainIgnoringCase("cvv")
                .doesNotContainIgnoringCase("cardCvv")
                .doesNotContainIgnoringCase("cvvCode");
    }

    // ---------------------------------------------------------------------
    // Phase 3 — PAN masking (only the last four digits may be visible)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Phase 3: cardNumber is a PAN masked to its last four digits")
    void cardNumberIsMaskedToLast4() {
        CardViewResponse dto = sampleResponse();

        // Only the last four digits are visible; the masked prefix holds no digit.
        DtoTestSupport.assertPanMaskedLast4(dto.cardNumber(), LAST4);

        assertThat(dto.cardNumber())
                .as("masked card number must reveal the last four but never the full PAN")
                .isEqualTo(MASKED_PAN)
                .doesNotContain(FULL_PAN)
                .endsWith(LAST4);
    }

    @Test
    @DisplayName("Phase 3: serialized JSON never contains the full unmasked PAN")
    void jsonDoesNotContainFullUnmaskedPan() {
        String json = DtoTestSupport.toJson(sampleResponse());

        assertThat(json)
                .as("serialized card view must not leak the full sixteen-digit PAN")
                .doesNotContain(FULL_PAN)
                .contains(MASKED_PAN);
    }

    // ---------------------------------------------------------------------
    // Phase 4 — expirationDate is an ISO-8601 LocalDate
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Phase 4: expirationDate serializes as an ISO-8601 string, not epoch/array")
    void expirationDateSerializesAsIsoString() {
        String json = DtoTestSupport.toJson(sampleResponse());

        // Structural proof: an ISO string maps to java.lang.String, whereas an epoch
        // day would deserialize to a Number and a WRITE_DATES_AS_TIMESTAMPS array to
        // a List. Asserting a String equal to the ISO form rules both out.
        Map<String, Object> fields = parseFields(json);
        assertThat(fields.get("expirationDate"))
                .isInstanceOf(String.class)
                .isEqualTo(EXPIRATION_ISO);

        // Textual proof: the value is emitted quoted, in ISO-8601 form.
        assertThat(json).contains("\"expirationDate\":\"" + EXPIRATION_ISO + "\"");
    }

    @Test
    @DisplayName("Phase 4: expirationDate record component is typed LocalDate")
    void expirationDateComponentTypeIsLocalDate() {
        RecordComponent expirationDate = null;
        for (RecordComponent component : CardViewResponse.class.getRecordComponents()) {
            if ("expirationDate".equals(component.getName())) {
                expirationDate = component;
            }
        }

        assertThat(expirationDate).as("expirationDate record component").isNotNull();
        assertThat(expirationDate.getType())
                .as("expirationDate must be modelled as java.time.LocalDate")
                .isEqualTo(LocalDate.class);
    }

    // ---------------------------------------------------------------------
    // Phase 5 — version (Long) passthrough for the optimistic-lock echo
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Phase 5: non-null version round-trips unchanged")
    void versionRoundTrips() {
        CardViewResponse roundTripped =
                DtoTestSupport.roundTrip(sampleResponse(), CardViewResponse.class);

        assertThat(roundTripped.version())
                .as("optimistic-lock version must survive the JSON round-trip")
                .isEqualTo(VERSION);
    }

    @Test
    @DisplayName("Phase 5: null version round-trips as null")
    void nullVersionRoundTrips() {
        CardViewResponse dto = new CardViewResponse(
                ACCOUNT_ID, FULL_PAN, EMBOSSED_NAME, ACTIVE_STATUS, EXPIRATION_DATE, null);

        CardViewResponse roundTripped = DtoTestSupport.roundTrip(dto, CardViewResponse.class);

        assertThat(roundTripped.version())
                .as("a read carrying no version must round-trip as null")
                .isNull();
        assertThat(roundTripped).isEqualTo(dto);
    }
}
