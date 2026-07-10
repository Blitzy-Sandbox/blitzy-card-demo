package com.carddemo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Framework-free unit test for {@link CardListResponse}, the composite payload of
 * the CardDemo <em>Card List</em> screen (online transaction {@code CCLI}) in the
 * COBOL&rarr;Java 25 / Spring Boot 3.5.11 migration.
 *
 * <p>{@code CardListResponse} is the idiomatic Spring translation of the legacy
 * 3270 BMS symbolic map {@code COCRDLI} ({@code app/cpy-bms/COCRDLI.CPY}, referenced
 * read-only by source SHA {@code 27d6c6f}) driven by program {@code COCRDLIC}. The
 * mainframe screen renders two search-criteria fields at the top &mdash;
 * {@code ACCTSID} {@code PIC X(11)} and {@code CARDSID} {@code PIC X(16)} &mdash;
 * plus a fixed block of up to seven repeated card rows
 * ({@code ACCTNOn}/{@code CRDNUMn}/{@code CRDSTSn}, {@code n = 1..7}) and the
 * {@code PAGENO} page indicator with PF7/PF8 scroll keys. The record therefore
 * bundles the two echoed filters with a {@link PageResponse} of {@link CardListItem}
 * rows sourced from the {@code CARD-RECORD} layout (copybook {@code CVACT02Y}).</p>
 *
 * <p>This suite pins the four behaviours that guard the interface contract and the
 * card-data security posture of the composite:</p>
 * <ol>
 *   <li><strong>Composite JSON round-trip.</strong> A serialize/deserialize cycle
 *       preserves full record equality (including the nested generic
 *       {@code PageResponse<CardListItem>}), the emitted JSON exposes exactly the
 *       three top-level keys {@code accountIdFilter}/{@code cardNumberFilter}/
 *       {@code page}, and {@code page} nests the nine {@link PageResponse}
 *       properties over a {@code content} array of card items.</li>
 *   <li><strong>Fixed page size &mdash; seven rows.</strong> The legacy card-list
 *       map shows seven rows per page, published as the invariant
 *       {@link CardListResponse#PAGE_SIZE}; the page carried by the response is
 *       built with that size and it survives serialization.</li>
 *   <li><strong>PAN masking survives nesting.</strong> Every nested
 *       {@link CardListItem#cardNumber()} is masked to its last four digits, and no
 *       full sixteen-digit Primary Account Number appears anywhere in the serialized
 *       document.</li>
 *   <li><strong>Filter passthrough.</strong> Present filters are echoed verbatim and
 *       round-trip unchanged; absent (unfiltered) filters round-trip as {@code null}
 *       and serialize exactly as production does.</li>
 * </ol>
 *
 * <p>The test loads no Spring context and uses no database, container, mock, file,
 * or network I/O: it relies solely on the shared {@link DtoTestSupport} helpers (the
 * production-mirroring Jackson mapper and the PAN-masking assertions), so it runs
 * fast and deterministically and contributes to the Gate&nbsp;8 (&ge;80%) JaCoCo
 * line-coverage target. Design rationale for the masking, fixed-page-size, and
 * filter-echo decisions lives in {@code docs/decision-log.md}, not in these
 * comments; no COBOL source is reproduced here.</p>
 */
@DisplayName("CardListResponse — card-list (CCLI) composite: JSON contract, fixed page size 7, PAN masking, filter passthrough")
class CardListResponseTest {

    /** Number of trailing digits a masked PAN leaves visible (matches production). */
    private static final int VISIBLE_PAN_DIGITS = 4;

    /**
     * Account-id fixtures with deliberate leading zeros and the full eleven-digit
     * width of {@code CARD-ACCT-ID} 9(11); prove values survive as text.
     */
    private static final String ACCOUNT_ID_1 = "00000000012";
    private static final String ACCOUNT_ID_2 = "00000000034";
    private static final String ACCOUNT_ID_3 = "00000000056";

    /**
     * Full, unmasked sixteen-digit PANs ({@code CARD-NUM} X(16)). These are the
     * values that must <em>never</em> appear in the response or its serialized JSON;
     * they are supplied to the {@link CardListItem} constructor purely to prove that
     * its masking reduces them to their last four digits.
     */
    private static final String FULL_PAN_1 = "4111111111113456";
    private static final String FULL_PAN_2 = "5500000000005678";
    private static final String FULL_PAN_3 = "6011000000009012";

    /** Single-character active-status flags ({@code Y} = active, {@code N} = inactive). */
    private static final String ACTIVE = "Y";
    private static final String INACTIVE = "N";

    /** Echoed account-id search filter fixture ({@code ACCTSID} {@code PIC X(11)}). */
    private static final String ACCOUNT_ID_FILTER = "00000000012";

    /**
     * Echoed card-number search filter fixture ({@code CARDSID} {@code PIC X(16)}).
     * A short, caller-typed search fragment &mdash; never a full PAN.
     */
    private static final String CARD_NUMBER_FILTER = "4111";

    /**
     * Computes the masked form of a full PAN the way {@link CardListItem} does: every
     * character before the final four replaced with {@code '*'}.
     *
     * @param fullPan the full, unmasked PAN
     * @return the masked PAN exposing only its last four characters
     */
    private static String maskedOf(String fullPan) {
        String last4 = fullPan.substring(fullPan.length() - VISIBLE_PAN_DIGITS);
        return "*".repeat(fullPan.length() - VISIBLE_PAN_DIGITS) + last4;
    }

    /**
     * Builds a few card-list rows from full PANs. The {@link CardListItem} canonical
     * constructor masks each PAN on construction, so the returned rows already carry
     * masked card numbers.
     *
     * @return an immutable list of three sample {@link CardListItem} rows
     */
    private static List<CardListItem> sampleItems() {
        return List.of(
                new CardListItem(ACCOUNT_ID_1, FULL_PAN_1, ACTIVE),
                new CardListItem(ACCOUNT_ID_2, FULL_PAN_2, INACTIVE),
                new CardListItem(ACCOUNT_ID_3, FULL_PAN_3, ACTIVE));
    }

    /**
     * Builds the sample page exactly as {@code CardListService} does: seven rows per
     * page ({@link CardListResponse#PAGE_SIZE}) on page 2 of a three-page result, so
     * the pagination metadata (previous/next present) is exercised.
     *
     * @return a fixed-size-7 {@link PageResponse} of sample card rows
     */
    private static PageResponse<CardListItem> samplePage() {
        return PageResponse.of(sampleItems(), 2, CardListResponse.PAGE_SIZE, 21L);
    }

    /**
     * Builds the canonical sample response shared by the tests: both filters present
     * and a fixed-size-7 page of masked card rows.
     *
     * @return a fully populated {@link CardListResponse} sample
     */
    private static CardListResponse sampleResponse() {
        return new CardListResponse(ACCOUNT_ID_FILTER, CARD_NUMBER_FILTER, samplePage());
    }

    // ---------------------------------------------------------------------
    // Phase 1 — composite JSON round-trip and the on-the-wire key contract
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("composite round-trips losslessly through JSON, preserving the nested PageResponse<CardListItem>")
    void compositeRoundTripPreservesEquality() {
        CardListResponse original = sampleResponse();

        // CardListResponse is a concrete (non-generic) type, so roundTrip(..., Class)
        // is sufficient; a record derives equals() from every component, so equality
        // proves the filters and the entire nested page survived the cycle unchanged.
        CardListResponse restored = DtoTestSupport.roundTrip(original, CardListResponse.class);

        assertThat(restored)
                .as("JSON round-trip must preserve full composite record equality")
                .isEqualTo(original);

        // The nested generic must deserialize back into real CardListItem instances
        // (not raw maps), element-for-element in order.
        assertThat(restored.page().content())
                .as("restored nested content must equal the original card rows in order")
                .containsExactlyElementsOf(original.page().content());
    }

    @Test
    @DisplayName("serialized JSON exposes exactly the top-level keys accountIdFilter, cardNumberFilter, page")
    void jsonExposesExactlyTopLevelKeys() {
        String json = DtoTestSupport.toJson(sampleResponse());

        Map<String, Object> top = DtoTestSupport.fromJson(json, new TypeReference<Map<String, Object>>() { });

        assertThat(top).containsOnlyKeys("accountIdFilter", "cardNumberFilter", "page");
    }

    @Test
    @DisplayName("nested page carries the nine PageResponse properties over a content array of card items")
    void pageNestsNinePropertiesAndContentArray() {
        CardListResponse response = sampleResponse();

        // The nested page serializes identically whether standalone or embedded, so
        // parsing the page's own JSON to a Map proves the nested key contract without
        // an unchecked cast of a Map value (which would trip -Xlint:all).
        String pageJson = DtoTestSupport.toJson(response.page());
        Map<String, Object> pageMap = DtoTestSupport.fromJson(pageJson, new TypeReference<Map<String, Object>>() { });
        assertThat(pageMap).containsOnlyKeys(
                "content",
                "pageNumber",
                "pageSize",
                "totalElements",
                "totalPages",
                "hasNext",
                "hasPrevious",
                "first",
                "last");
        assertThat(pageMap.get("content")).as("content must be a JSON array").isInstanceOf(List.class);

        // A TypeReference preserves the full generic type, so the content array
        // deserializes into typed CardListItem rows with the three card-row fields.
        PageResponse<CardListItem> parsedPage =
                DtoTestSupport.fromJson(pageJson, new TypeReference<PageResponse<CardListItem>>() { });
        assertThat(parsedPage.content())
                .as("content array of card items")
                .hasSize(3)
                .allSatisfy(item -> {
                    assertThat(item.accountId()).as("row accountId").isNotBlank();
                    assertThat(item.cardNumber()).as("row cardNumber").isNotBlank();
                    assertThat(item.activeStatus()).as("row activeStatus").isNotBlank();
                });

        // And prove the embedding: the full document nests the page object under "page".
        assertThat(DtoTestSupport.toJson(response))
                .as("full document must nest the page object under the \"page\" key")
                .contains("\"page\":{");
    }

    // ---------------------------------------------------------------------
    // Phase 2 — fixed page size of seven rows (CRITICAL: COCRDLI paging)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("page size is the fixed 7 rows/page and equals the published CardListResponse.PAGE_SIZE constant")
    void pageSizeIsSevenRowsPerCardListPage() {
        // Build the page exactly as the producing service does.
        PageResponse<CardListItem> page = PageResponse.of(sampleItems(), 1, CardListResponse.PAGE_SIZE, 3L);
        CardListResponse response = new CardListResponse(null, null, page);

        assertThat(CardListResponse.PAGE_SIZE)
                .as("the published card-list page-size constant must be 7 (COCRDLI ACCTNO1..7)")
                .isEqualTo(7);
        assertThat(response.page().pageSize())
                .as("page size carried on the CardListResponse must be the fixed 7 rows/page")
                .isEqualTo(7)
                .isEqualTo(CardListResponse.PAGE_SIZE);
    }

    @Test
    @DisplayName("the fixed page size 7 survives the JSON round-trip and is emitted as pageSize:7")
    void pageSizeSevenSurvivesRoundTrip() {
        CardListResponse response = sampleResponse();

        CardListResponse restored = DtoTestSupport.roundTrip(response, CardListResponse.class);
        assertThat(restored.page().pageSize())
                .as("round-tripped page size")
                .isEqualTo(CardListResponse.PAGE_SIZE)
                .isEqualTo(7);

        assertThat(DtoTestSupport.toJson(response))
                .as("serialized page must carry pageSize 7")
                .contains("\"pageSize\":7");
    }

    // ---------------------------------------------------------------------
    // Phase 3 — PAN masking survives nesting inside the composite page
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("every nested card item exposes only a PAN masked to its last four digits")
    void eachContentCardNumberMaskedToLast4() {
        CardListResponse response = sampleResponse();

        List<CardListItem> items = response.page().content();
        assertThat(items).as("sample page content").isNotEmpty();

        // General shape: a run of mask characters followed by exactly four digits,
        // with no cleartext digit leaking into the masked prefix.
        for (CardListItem item : items) {
            DtoTestSupport.assertPanMaskedLast4(item.cardNumber());
        }

        // Exact masked forms against the source full PANs, and only the last four
        // digits of each PAN remain visible.
        assertThat(items.get(0).cardNumber()).isEqualTo(maskedOf(FULL_PAN_1)).endsWith("3456");
        assertThat(items.get(1).cardNumber()).isEqualTo(maskedOf(FULL_PAN_2)).endsWith("5678");
        assertThat(items.get(2).cardNumber()).isEqualTo(maskedOf(FULL_PAN_3)).endsWith("9012");
    }

    @Test
    @DisplayName("serialized composite never contains a full unmasked 16-digit PAN anywhere")
    void serializedJsonNeverContainsAnyFullPan() {
        String json = DtoTestSupport.toJson(sampleResponse());

        // Direct needles: none of the specific full PANs may appear.
        assertThat(json)
                .as("serialized card-list response must not leak any full sixteen-digit PAN")
                .doesNotContain(FULL_PAN_1)
                .doesNotContain(FULL_PAN_2)
                .doesNotContain(FULL_PAN_3);

        // Provenance-agnostic string scan: no run of sixteen-or-more consecutive
        // digits may appear anywhere in the document (account ids are 11 digits and
        // masked PANs expose only 4, so a 16+ digit run can only be a leaked PAN).
        assertThat(json)
                .as("no 16-digit-or-longer PAN-like digit run may appear in the JSON")
                .doesNotContainPattern("\\d{16,}");
    }

    // ---------------------------------------------------------------------
    // Phase 4 — echoed search-filter passthrough (present, null, empty)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("present filters are echoed back verbatim and survive the JSON round-trip")
    void filtersEchoedVerbatimWhenPresent() {
        CardListResponse response = sampleResponse();
        assertThat(response.accountIdFilter()).as("accountIdFilter accessor").isEqualTo(ACCOUNT_ID_FILTER);
        assertThat(response.cardNumberFilter()).as("cardNumberFilter accessor").isEqualTo(CARD_NUMBER_FILTER);

        CardListResponse restored = DtoTestSupport.roundTrip(response, CardListResponse.class);
        assertThat(restored.accountIdFilter()).as("echoed accountIdFilter").isEqualTo(ACCOUNT_ID_FILTER);
        assertThat(restored.cardNumberFilter()).as("echoed cardNumberFilter").isEqualTo(CARD_NUMBER_FILTER);

        assertThat(DtoTestSupport.toJson(response))
                .as("present filters must serialize as their verbatim string values")
                .contains("\"accountIdFilter\":\"" + ACCOUNT_ID_FILTER + "\"")
                .contains("\"cardNumberFilter\":\"" + CARD_NUMBER_FILTER + "\"");
    }

    @Test
    @DisplayName("null (unfiltered) filters round-trip as null and serialize as explicit JSON null keys")
    void nullFiltersRoundTripAndSerializeAsJsonNull() {
        CardListResponse response = new CardListResponse(null, null, samplePage());

        CardListResponse restored = DtoTestSupport.roundTrip(response, CardListResponse.class);
        assertThat(restored.accountIdFilter()).as("null accountIdFilter round-trips as null").isNull();
        assertThat(restored.cardNumberFilter()).as("null cardNumberFilter round-trips as null").isNull();

        // Production declares no @JsonInclude(NON_NULL) on CardListResponse, so the
        // filter keys are present with an explicit JSON null rather than omitted.
        String json = DtoTestSupport.toJson(response);
        Map<String, Object> top = DtoTestSupport.fromJson(json, new TypeReference<Map<String, Object>>() { });
        assertThat(top)
                .as("null filters must be present keys (not omitted) with null values")
                .containsKey("accountIdFilter")
                .containsKey("cardNumberFilter");
        assertThat(top.get("accountIdFilter")).as("serialized accountIdFilter value").isNull();
        assertThat(top.get("cardNumberFilter")).as("serialized cardNumberFilter value").isNull();
    }

    @Test
    @DisplayName("empty-string filters round-trip as empty strings, distinct from null")
    void emptyStringFiltersRoundTrip() {
        CardListResponse response = new CardListResponse("", "", samplePage());

        CardListResponse restored = DtoTestSupport.roundTrip(response, CardListResponse.class);
        assertThat(restored.accountIdFilter()).as("empty accountIdFilter").isEqualTo("");
        assertThat(restored.cardNumberFilter()).as("empty cardNumberFilter").isEqualTo("");
    }
}
