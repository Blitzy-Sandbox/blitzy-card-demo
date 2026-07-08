package com.carddemo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure, framework-free unit test for {@link TransactionListResponse}, the paged
 * payload of the legacy CardDemo Transaction List screen (BMS map
 * {@code COTRN00}, CICS transaction {@code CT00}, program {@code COTRN00C}).
 *
 * <p>These assertions lock down the COBOL&rarr;Java migration contract for the
 * transaction-list envelope (source referenced by SHA {@code 27d6c6f}, not
 * copied into the target). The type under test is the composite record
 * {@code TransactionListResponse(String transactionIdFilter,
 * PageResponse<TransactionListItem> page)}, so the tests verify the wrapper and
 * its interaction with the nested {@link PageResponse} and
 * {@link TransactionListItem}:</p>
 * <ul>
 *   <li><strong>Composite JSON round-trip</strong> &mdash; a populated response
 *       survives a serialize/deserialize cycle unchanged, the wire contract
 *       exposes exactly the two components {@code transactionIdFilter} and
 *       {@code page}, and {@code page.content} is a JSON array.</li>
 *   <li><strong>Page size 10</strong> &mdash; the {@code COTRN00} map renders a
 *       fixed ten rows per page (repeated {@code TRNIDnn}/{@code TDATEnn}/
 *       {@code TDESCnn}/{@code TAMTnnn} group, rows {@code 01}&ndash;{@code 10}),
 *       so the enclosed page carries {@code pageSize == 10}.</li>
 *   <li><strong>Money fidelity survives nesting</strong> &mdash; each
 *       {@link TransactionListItem#amount()} maps {@code TRAN-AMT}
 *       ({@code PIC S9(09)V99}, copybook {@code CVTRA05Y}) to a scale-2
 *       {@link BigDecimal} that serializes as a <em>plain</em> decimal literal
 *       (for example {@code 100.00}, never {@code 1.0E2}) even when nested two
 *       levels deep inside {@code page.content[i].amount}.</li>
 *   <li><strong>Filter passthrough</strong> &mdash; the echoed
 *       {@code transactionIdFilter} ({@code TRNIDIN PIC X(16)}) round-trips when
 *       present and is preserved as an explicit {@code null} when unfiltered,
 *       matching the production Jackson contract.</li>
 * </ul>
 *
 * <p>The test loads no Spring context and touches no database, file, network, or
 * AWS resource; every check is an in-memory serialization, reflection, or
 * bean-validation assertion built on the shared {@link DtoTestSupport} helpers,
 * so the suite is fast and deterministic. The rationale for the money-as-plain,
 * scale-2 and one-based paging decisions lives in {@code docs/decision-log.md},
 * not in these comments.</p>
 */
@DisplayName("TransactionListResponse — CT00 paged transaction-list envelope")
class TransactionListResponseTest {

    /**
     * The fixed number of transaction rows rendered per page by the legacy
     * {@code COTRN00} Transaction List screen (selectors {@code SEL0001I}..
     * {@code SEL0010I}); the producing {@code TransactionListService} builds the
     * enclosed {@link PageResponse} with this page size.
     */
    private static final int PAGE_SIZE = 10;

    /** A representative 16-character transaction-id filter ({@code TRNIDIN PIC X(16)}). */
    private static final String FILTER = "0000000000000042";

    // ---------------------------------------------------------------------
    // Phase 1 — composite JSON round-trip and wire contract
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("round-trips through JSON preserving equality and exposing page.content as an array")
    void compositeRoundTripPreservesEqualityAndExposesContentArray() {
        List<TransactionListItem> items = sampleItems();
        TransactionListResponse response =
                new TransactionListResponse(FILTER, samplePage(items, 1, items.size()));

        TransactionListResponse restored =
                DtoTestSupport.roundTrip(response, TransactionListResponse.class);

        // Record value-equality is deep (and BigDecimal-scale-sensitive), so this
        // single assertion proves the whole nested graph survived the wire.
        assertThat(restored).isEqualTo(response);
        assertThat(restored.transactionIdFilter()).isEqualTo(FILTER);
        assertThat(restored.page().content())
                .as("nested transaction rows survive the round-trip in order")
                .containsExactlyElementsOf(items);
        assertThat(restored.page().content()).hasSize(items.size());

        String json = DtoTestSupport.toJson(response);
        // The record's components are the single source of truth for the wire keys.
        Map<String, Object> topLevel =
                DtoTestSupport.fromJson(json, new TypeReference<Map<String, Object>>() { });
        assertThat(topLevel).containsOnlyKeys("transactionIdFilter", "page");
        // page.content is serialized as a JSON array (compact mapper -> "content":[ ).
        assertThat(json)
                .as("page.content must serialize as a JSON array")
                .contains("\"content\":[");
    }

    // ---------------------------------------------------------------------
    // Phase 2 — page size 10 (CRITICAL)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("enclosed page reports the fixed COTRN00 page size of 10 rows/page")
    void pageSizeIsTenRowsPerPage() {
        List<TransactionListItem> items = sampleItems();
        // Build the page via the production factory with the legacy page size 10,
        // on a middle page (2 of 3) so the derived metadata is non-trivial.
        PageResponse<TransactionListItem> page = PageResponse.of(items, 2, 10, 25L);

        assertThat(page.pageSize()).as("COTRN00 fixed page size").isEqualTo(PAGE_SIZE);
        assertThat(page.pageNumber()).as("one-based page number").isEqualTo(2);
        assertThat(page.totalElements()).as("total elements").isEqualTo(25L);
        assertThat(page.totalPages()).as("ceil(25 / 10)").isEqualTo(3);
        assertThat(page.hasPrevious()).as("PF7 available on a middle page").isTrue();
        assertThat(page.hasNext()).as("PF8 available on a middle page").isTrue();
        assertThat(page.first()).as("middle page is not first").isFalse();
        assertThat(page.last()).as("middle page is not last").isFalse();

        TransactionListResponse response = new TransactionListResponse(FILTER, page);
        assertThat(response.page().pageSize())
                .as("the response envelope preserves the size-10 page")
                .isEqualTo(PAGE_SIZE);

        // The size-10 page size survives the JSON round-trip unchanged.
        TransactionListResponse restored =
                DtoTestSupport.roundTrip(response, TransactionListResponse.class);
        assertThat(restored).isEqualTo(response);
        assertThat(restored.page().pageSize()).isEqualTo(PAGE_SIZE);
    }

    // ---------------------------------------------------------------------
    // Phase 3 — money fidelity survives nesting (PLAIN, scale 2)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("nested amounts serialize PLAIN with scale 2 (no scientific notation) and survive round-trip")
    void nestedMoneySerializesPlainWithScaleTwo() {
        List<TransactionListItem> items = sampleItems();
        TransactionListResponse response =
                new TransactionListResponse(FILTER, samplePage(items, 1, items.size()));

        // Every source row already carries scale-2 money (the producing service's
        // setScale(2, HALF_UP) responsibility is asserted at the row level here).
        for (TransactionListItem item : items) {
            DtoTestSupport.assertScale(item.amount(), 2);
        }

        String json = DtoTestSupport.toJson(response);

        // Navigate into page.content[i].amount: 'amount' occurs only inside the
        // nested content rows, so the verbatim numeric literals collected from the
        // token stream are exactly those nested amounts, in content order. Reading
        // via a JsonNode tree would be wrong (a DecimalNode strips trailing zeros,
        // reporting 100.00 as 1E+2), so the raw stream literal is the only faithful
        // representation of what the serializer emitted.
        List<String> nestedAmounts = nestedAmountLiterals(json);
        assertThat(nestedAmounts)
                .as("exactly one nested amount literal per content row")
                .hasSize(items.size());
        assertThat(nestedAmounts).containsExactly("100.00", "1234.56", "-42.00");
        for (String literal : nestedAmounts) {
            assertThat(literal)
                    .as("nested money '%s' must be plain, fixed scale-2 with no scientific notation", literal)
                    .matches("-?\\d+\\.\\d{2}")
                    .doesNotContainIgnoringCase("e");
        }

        // The nested amounts also survive the round-trip with value and scale intact.
        TransactionListResponse restored =
                DtoTestSupport.roundTrip(response, TransactionListResponse.class);
        List<TransactionListItem> restoredRows = restored.page().content();
        assertThat(restoredRows).hasSize(items.size());
        for (int i = 0; i < items.size(); i++) {
            BigDecimal restoredAmount = restoredRows.get(i).amount();
            assertThat(restoredAmount).isEqualByComparingTo(items.get(i).amount());
            DtoTestSupport.assertScale(restoredAmount, 2);
        }
    }

    // ---------------------------------------------------------------------
    // Phase 4 — transactionIdFilter passthrough (present and null)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("transactionIdFilter round-trips verbatim when a search filter is supplied")
    void transactionIdFilterRoundTripsWhenPresent() {
        TransactionListResponse response = sampleResponse(FILTER);

        TransactionListResponse restored =
                DtoTestSupport.roundTrip(response, TransactionListResponse.class);
        assertThat(restored.transactionIdFilter()).isEqualTo(FILTER);
        assertThat(restored).isEqualTo(response);

        String json = DtoTestSupport.toJson(response);
        Map<String, Object> topLevel =
                DtoTestSupport.fromJson(json, new TypeReference<Map<String, Object>>() { });
        assertThat(topLevel).containsEntry("transactionIdFilter", FILTER);
    }

    @Test
    @DisplayName("transactionIdFilter is preserved as an explicit null when unfiltered")
    void transactionIdFilterIsNullWhenUnfiltered() {
        TransactionListResponse response = sampleResponse(null);

        TransactionListResponse restored =
                DtoTestSupport.roundTrip(response, TransactionListResponse.class);
        assertThat(restored.transactionIdFilter())
                .as("an absent filter round-trips as null")
                .isNull();
        assertThat(restored).isEqualTo(response);

        // Production uses Jackson's default inclusion (ALWAYS), so the key is emitted
        // with an explicit JSON null rather than being omitted from the document.
        String json = DtoTestSupport.toJson(response);
        Map<String, Object> topLevel =
                DtoTestSupport.fromJson(json, new TypeReference<Map<String, Object>>() { });
        assertThat(topLevel).containsKey("transactionIdFilter");
        assertThat(topLevel.get("transactionIdFilter"))
                .as("unfiltered transactionIdFilter is serialized as explicit null")
                .isNull();
    }

    @Test
    @DisplayName("@Size(max=16) is enforced on transactionIdFilter; null is permitted")
    void transactionIdFilterSizeConstraintIsEnforced() {
        // A 16-character filter and an absent (null) filter are both valid; @Size
        // never rejects null, matching the "unfiltered" semantics of the screen.
        assertThat(DtoTestSupport.validate(sampleResponse(FILTER)))
                .as("a 16-character filter is within the PIC X(16) width").isEmpty();
        assertThat(DtoTestSupport.validate(sampleResponse(null)))
                .as("a null (unfiltered) filter is valid").isEmpty();

        // Seventeen characters exceeds TRNIDIN PIC X(16) -> a single violation on
        // the transactionIdFilter component (there is no @Valid cascade into page).
        String tooLong = "0".repeat(17);
        var violations = DtoTestSupport.validate(sampleResponse(tooLong));
        assertThat(violations).hasSize(1);
        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getPropertyPath().toString()).isEqualTo("transactionIdFilter"));
    }

    // ---------------------------------------------------------------------
    // Test fixtures and helpers
    // ---------------------------------------------------------------------

    /**
     * Builds a small, deterministic page of transaction rows exercising three
     * distinct scale-2 money shapes: a trailing-zero value ({@code 100.00}), a
     * fractional value ({@code 1234.56}), and a negative value ({@code -42.00})
     * (the signed {@code S9(09)V99} case). Widths respect the {@code COTRN00}
     * row field sizes ({@code TRAN-ID} 16, {@code TDATE} 8, {@code TDESC} 26).
     *
     * @return an ordered, fixed list of three {@link TransactionListItem} rows
     */
    private static List<TransactionListItem> sampleItems() {
        return List.of(
                new TransactionListItem(
                        "0000000000000001", "24/01/15", "GROCERY STORE PURCHASE", new BigDecimal("100.00")),
                new TransactionListItem(
                        "0000000000000002", "24/01/16", "ONLINE SUBSCRIPTION", new BigDecimal("1234.56")),
                new TransactionListItem(
                        "0000000000000003", "24/01/17", "REFUND - RETURNED ITEM", new BigDecimal("-42.00")));
    }

    /**
     * Wraps the supplied rows in a {@link PageResponse} using the production
     * factory and the legacy {@code COTRN00} page size of {@value #PAGE_SIZE}.
     *
     * @param items         the transaction rows on this page
     * @param pageNumber    the one-based page index
     * @param totalElements the total number of transactions across all pages
     * @return an immutable {@link PageResponse} of {@link TransactionListItem}
     */
    private static PageResponse<TransactionListItem> samplePage(
            List<TransactionListItem> items, int pageNumber, long totalElements) {
        return PageResponse.of(items, pageNumber, PAGE_SIZE, totalElements);
    }

    /**
     * Builds a single-page {@link TransactionListResponse} with the given filter.
     *
     * @param filter the echoed transaction-id filter, or {@code null} when
     *               unfiltered
     * @return a populated {@link TransactionListResponse}
     */
    private static TransactionListResponse sampleResponse(String filter) {
        List<TransactionListItem> items = sampleItems();
        return new TransactionListResponse(filter, samplePage(items, 1, items.size()));
    }

    /**
     * Collects the verbatim numeric literals of every {@code amount} field in the
     * document, in document order, by walking the JSON with a streaming
     * {@link JsonParser}. Because {@code amount} appears only inside the nested
     * {@code page.content[]} rows, the returned literals are exactly the nested
     * transaction amounts. Each value token must be a JSON number, and
     * {@link JsonParser#getText()} returns the exact characters the serializer
     * emitted (for example {@code 100.00}) rather than a re-normalized form.
     *
     * @param json the response JSON to inspect
     * @return the raw {@code amount} literals in content order
     */
    private static List<String> nestedAmountLiterals(String json) {
        List<String> literals = new ArrayList<>();
        try (JsonParser parser = DtoTestSupport.objectMapper().getFactory().createParser(json)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() == JsonToken.FIELD_NAME
                        && "amount".equals(parser.currentName())) {
                    JsonToken valueToken = parser.nextToken();
                    assertThat(valueToken)
                            .as("nested 'amount' must serialize as a JSON number")
                            .isIn(JsonToken.VALUE_NUMBER_FLOAT, JsonToken.VALUE_NUMBER_INT);
                    literals.add(parser.getText());
                }
            }
        } catch (IOException e) {
            throw new AssertionError(
                    "Failed to parse response JSON while reading nested amounts: " + json, e);
        }
        return literals;
    }
}
