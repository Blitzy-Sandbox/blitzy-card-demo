package com.carddemo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Framework-free unit test for {@link CardListItem}, the immutable card-list-row
 * DTO of the CardDemo COBOL&rarr;Java 25 / Spring Boot 3.5.11 migration.
 *
 * <p>{@code CardListItem} models one repeated row of the legacy <em>Card List</em>
 * screen (BMS mapset {@code COCRDLI}, online transaction {@code CCLI}); its three
 * components are sourced from the {@code CARD-RECORD} layout (copybook
 * {@code CVACT02Y}, referenced read-only by source SHA {@code 27d6c6f}):
 * {@code accountId} &larr; {@code CARD-ACCT-ID} 9(11), {@code cardNumber} &larr;
 * {@code CARD-NUM} X(16), and {@code activeStatus} &larr;
 * {@code CARD-ACTIVE-STATUS} X(01). This suite pins the three behaviours that guard
 * the interface contract and the card-data security posture of the DTO:</p>
 * <ol>
 *   <li><strong>JSON round-trip.</strong> A serialize/deserialize cycle preserves
 *       record equality, the emitted JSON exposes exactly the keys
 *       {@code accountId}/{@code cardNumber}/{@code activeStatus}, and
 *       {@code accountId} serializes as a JSON <em>string</em> so its eleven-digit
 *       width and any leading zeros survive byte-for-byte (a numeric encoding would
 *       silently drop them).</li>
 *   <li><strong>PAN masking.</strong> {@code cardNumber} carries only a masked
 *       Primary Account Number (last four digits visible); neither the value nor its
 *       serialized JSON ever contains the full sixteen-digit PAN.</li>
 *   <li><strong>Sensitive-data safety.</strong> The record declares no CVV
 *       component, so {@code CARD-CVV-CD} 9(03) — present in the source record —
 *       can never surface on this REST-facing DTO.</li>
 * </ol>
 *
 * <p>The test loads no Spring context and uses no database, container, mock, file,
 * or network I/O: it relies solely on the shared {@link DtoTestSupport} helpers (the
 * production-mirroring Jackson mapper and the reflection/security assertions), so it
 * runs fast and deterministically. Design rationale for the masking and
 * string-identifier decisions lives in {@code docs/decision-log.md}, not in these
 * comments.</p>
 */
@DisplayName("CardListItem — card-list-row DTO: JSON contract, PAN masking, no-CVV safety")
class CardListItemTest {

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
     * that must <em>never</em> appear in the DTO or its serialized JSON; it is used
     * exclusively as a negative-assertion needle.
     */
    private static final String FULL_PAN = "4111111111113456";

    /**
     * The masked card number actually carried by the DTO: twelve mask characters
     * followed by {@link #LAST4}, exactly sixteen characters wide to match
     * {@code CARD-NUM} X(16) while exposing only the last four digits.
     */
    private static final String MASKED_PAN = "*".repeat(FULL_PAN.length() - LAST4.length()) + LAST4;

    /** Single-character active-status flag ({@code Y} = active). */
    private static final String ACTIVE_STATUS = "Y";

    /**
     * Builds the canonical sample row shared by the tests: a valid account id, a
     * correctly masked PAN, and an active status.
     *
     * @return a fully populated {@link CardListItem} sample
     */
    private static CardListItem sampleItem() {
        return new CardListItem(ACCOUNT_ID, MASKED_PAN, ACTIVE_STATUS);
    }

    // ---------------------------------------------------------------------
    // Phase 1 — JSON round-trip, key set, and String-typed account identifier
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("JSON round-trip preserves full record equality")
    void roundTripPreservesEquality() {
        CardListItem item = sampleItem();

        CardListItem roundTripped = DtoTestSupport.roundTrip(item, CardListItem.class);

        // A record derives equals() from every component, so equality proves each
        // field survived the serialize/deserialize cycle unchanged.
        assertThat(roundTripped).isEqualTo(item);
    }

    @Test
    @DisplayName("serialized JSON exposes exactly accountId, cardNumber, activeStatus")
    void jsonHasExactlyExpectedKeys() {
        String json = DtoTestSupport.toJson(sampleItem());

        Map<String, Object> fields = DtoTestSupport.fromJson(json, new TypeReference<Map<String, Object>>() { });

        assertThat(fields).containsOnlyKeys("accountId", "cardNumber", "activeStatus");
        assertThat(fields)
                .containsEntry("accountId", ACCOUNT_ID)
                .containsEntry("cardNumber", MASKED_PAN)
                .containsEntry("activeStatus", ACTIVE_STATUS);
    }

    @Test
    @DisplayName("accountId serializes as a JSON string, preserving width and leading zeros")
    void accountIdSerializesAsStringPreservingLeadingZeros() {
        String json = DtoTestSupport.toJson(sampleItem());

        // Textual proof: the value is quoted in the JSON payload (a bare number
        // would be unquoted and would lose the significant leading zero).
        assertThat(json).contains("\"accountId\":\"" + ACCOUNT_ID + "\"");

        // Structural proof: Jackson maps a JSON string to java.lang.String, whereas
        // a JSON number would deserialize to Integer/Long and drop the leading zero.
        Map<String, Object> fields = DtoTestSupport.fromJson(json, new TypeReference<Map<String, Object>>() { });
        assertThat(fields.get("accountId"))
                .isInstanceOf(String.class)
                .isEqualTo(ACCOUNT_ID);
    }

    // ---------------------------------------------------------------------
    // Phase 2 — PAN masking (only the last four digits may be visible)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("cardNumber is a PAN masked to its last four digits")
    void cardNumberIsMaskedToLast4() {
        CardListItem item = sampleItem();

        // Only the last four digits are visible; the masked prefix holds no digit.
        DtoTestSupport.assertPanMaskedLast4(item.cardNumber(), LAST4);

        assertThat(item.cardNumber())
                .as("masked card number must reveal the last four but never the full PAN")
                .doesNotContain(FULL_PAN)
                .endsWith(LAST4);
    }

    @Test
    @DisplayName("serialized JSON never contains the full unmasked PAN")
    void jsonDoesNotContainFullUnmaskedPan() {
        String json = DtoTestSupport.toJson(sampleItem());

        assertThat(json)
                .as("serialized card-list row must not leak the full sixteen-digit PAN")
                .doesNotContain(FULL_PAN);
    }

    // ---------------------------------------------------------------------
    // Phase 3 — Sensitive-data safety (CARD-CVV-CD must never surface)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("record exposes no CVV component")
    void recordExposesNoCvvComponent() {
        // CARD-CVV-CD 9(03) exists in CVACT02Y but must never reach a REST DTO.
        DtoTestSupport.assertNoComponentNamed(CardListItem.class, "cvv");
    }

    @Test
    @DisplayName("record exposes exactly the three card-list-row components")
    void recordExposesExactlyExpectedComponents() {
        // componentNames() lower-cases each record component name. Asserting the
        // exact set reconfirms the minimal projection: no cvv, no embossed name,
        // no expiry date — only the three fields the card-list row renders.
        assertThat(DtoTestSupport.componentNames(CardListItem.class))
                .containsExactlyInAnyOrder("accountid", "cardnumber", "activestatus");
    }
}
