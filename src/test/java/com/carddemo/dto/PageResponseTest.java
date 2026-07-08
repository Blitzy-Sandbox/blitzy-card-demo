package com.carddemo.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link PageResponse}, the generic pagination envelope that
 * replaces the legacy 3270 paged-list navigation of the mainframe BMS maps.
 *
 * <p>The heart of these tests is the static factory {@link PageResponse#of}
 * arithmetic. In the legacy system the page-size was screen-specific — the card
 * list {@code COCRDLI} shows <strong>7</strong> rows per page (selectors
 * {@code CRDSEL1I}..{@code CRDSEL7I}) while the transaction list {@code COTRN00}
 * and the user list show <strong>10</strong> rows per page (selectors
 * {@code SEL0001I}..{@code SEL0010I}); the current-page indicators were
 * {@code PAGENO} / {@code PAGENUM} and PF7/PF8 drove backward/forward scrolling.
 * The migration carries {@code pageSize} explicitly and derives {@code hasNext}
 * (PF8 / "MORE") and {@code hasPrevious} (PF7) from it. The row counts above are
 * asserted through the {@link #CARD_ROWS_PER_PAGE} and {@link #LIST_ROWS_PER_PAGE}
 * constants so the tests stay tied to that COBOL provenance.</p>
 *
 * <p><strong>Page numbering is one-based</strong> in the production record (the
 * first page is {@code pageNumber == 1}, mirroring the legacy {@code PAGENO}
 * display), so every assertion below uses one-based page indices. The factory
 * computes {@code totalPages} with overflow-safe ceiling division in
 * {@code long} space and <strong>guards against a division by zero</strong> when
 * {@code pageSize} is not positive (yielding {@code totalPages == 0}); that guard
 * is exercised explicitly per the migration's acceptance criteria.</p>
 *
 * <p>The tests are deliberately pure and dependency-free (no Spring context, no
 * Testcontainers, no mocks): they exercise the factory math directly and use the
 * shared {@link DtoTestSupport} Jackson mapper for the JSON contract, so they run
 * in milliseconds and are a primary line-coverage driver toward the Gate&nbsp;8
 * (&ge;80%) JaCoCo threshold. Rationale for the design lives in
 * {@code docs/decision-log.md}, not in verbose comments; no COBOL source is
 * reproduced here.</p>
 */
@DisplayName("PageResponse — of(...) pagination math, JSON contract, and record semantics")
class PageResponseTest {

    /** Rows per page on the card-list screen {@code COCRDLI} (CRDSEL1..CRDSEL7). */
    private static final int CARD_ROWS_PER_PAGE = 7;

    /** Rows per page on the transaction/user-list screens {@code COTRN00}/{@code COUSR00}. */
    private static final int LIST_ROWS_PER_PAGE = 10;

    // ------------------------------------------------------------------
    // Phase 1 — of(...) factory math. This is the core of the test: the
    // ceiling division, the one-based first/last/middle boolean derivation,
    // the empty-result shape, and the divide-by-zero guard.
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "totalElements={0}, pageSize={1} -> totalPages={2}")
    @CsvSource({
            "21, 7, 3",   // exact multiple: 21 / 7 = 3 with no remainder
            "22, 7, 4",   // remainder rounds up (ceil): 22 / 7 -> 4
            "10, 10, 1",  // exact single page
            "11, 10, 2",  // one over a page boundary rounds up
            "7, 7, 1",    // exactly one full page
            "1, 7, 1",    // a single element still occupies one page
            "0, 7, 0"     // no elements -> zero pages
    })
    @DisplayName("of(...) computes totalPages as the ceiling of totalElements / pageSize")
    void ceilDivisionComputesTotalPages(long totalElements, int pageSize, int expectedTotalPages) {
        PageResponse<Object> response = PageResponse.of(List.of(), 1, pageSize, totalElements);
        assertThat(response.totalPages())
                .as("ceil(totalElements=%d / pageSize=%d)", totalElements, pageSize)
                .isEqualTo(expectedTotalPages);
    }

    @Test
    @DisplayName("First page (pageNumber=1) is first, has no previous, and has a next page")
    void firstPageHasNoPrevious() {
        // 21 elements over 7-per-page = 3 pages; page 1 of 3.
        PageResponse<String> response = PageResponse.of(List.of("row"), 1, CARD_ROWS_PER_PAGE, 21);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.first()).as("first").isTrue();
        assertThat(response.hasPrevious()).as("hasPrevious").isFalse();
        assertThat(response.hasNext()).as("hasNext").isTrue();
        assertThat(response.last()).as("last").isFalse();
    }

    @Test
    @DisplayName("Last page is last, has no next, and has a previous page")
    void lastPageHasNoNext() {
        // 21 elements over 7-per-page = 3 pages; page 3 of 3 is the final page.
        PageResponse<String> response = PageResponse.of(List.of("row"), 3, CARD_ROWS_PER_PAGE, 21);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.last()).as("last").isTrue();
        assertThat(response.hasNext()).as("hasNext").isFalse();
        assertThat(response.hasPrevious()).as("hasPrevious").isTrue();
        assertThat(response.first()).as("first").isFalse();
    }

    @Test
    @DisplayName("Middle page has both a next and a previous page and is neither first nor last")
    void middlePageHasBothNeighbours() {
        // 21 elements over 7-per-page = 3 pages; page 2 of 3 is the middle page.
        PageResponse<String> response = PageResponse.of(List.of("row"), 2, CARD_ROWS_PER_PAGE, 21);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.hasNext()).as("hasNext").isTrue();
        assertThat(response.hasPrevious()).as("hasPrevious").isTrue();
        assertThat(response.first()).as("first").isFalse();
        assertThat(response.last()).as("last").isFalse();
    }

    @Test
    @DisplayName("Empty result yields zero pages that are simultaneously first and last with no neighbours")
    void emptyResultIsFirstAndLastWithNoNeighbours() {
        PageResponse<String> response = PageResponse.of(List.<String>of(), 1, LIST_ROWS_PER_PAGE, 0);
        assertThat(response.content()).as("content").isEmpty();
        assertThat(response.totalPages()).as("totalPages").isZero();
        assertThat(response.hasNext()).as("hasNext").isFalse();
        assertThat(response.hasPrevious()).as("hasPrevious").isFalse();
        assertThat(response.first()).as("first").isTrue();
        assertThat(response.last()).as("last").isTrue();
    }

    @Test
    @DisplayName("pageSize == 0 does not divide by zero and yields totalPages = 0 (guard)")
    void pageSizeZeroDoesNotThrowAndYieldsZeroPages() {
        // Explicit AAP requirement: the factory must guard the ceiling division so
        // that a non-positive pageSize can never raise an ArithmeticException.
        assertThatCode(() -> PageResponse.of(List.of(), 0, 0, 0))
                .as("of(...) with pageSize=0 and no elements must not throw")
                .doesNotThrowAnyException();
        assertThatCode(() -> PageResponse.of(List.of(), 0, 0, 100))
                .as("of(...) with pageSize=0 and a non-zero total must not throw")
                .doesNotThrowAnyException();

        PageResponse<Object> emptyGuard = PageResponse.of(List.of(), 0, 0, 0);
        assertThat(emptyGuard.totalPages()).as("guarded totalPages (empty)").isZero();
        assertThat(emptyGuard.hasNext()).as("hasNext (empty guard)").isFalse();
        assertThat(emptyGuard.hasPrevious()).as("hasPrevious (empty guard)").isFalse();
        assertThat(emptyGuard.first()).as("first (empty guard)").isTrue();
        assertThat(emptyGuard.last()).as("last (empty guard)").isTrue();

        PageResponse<Object> populatedGuard = PageResponse.of(List.of(), 0, 0, 100);
        assertThat(populatedGuard.totalPages()).as("guarded totalPages (non-zero total)").isZero();
        assertThat(populatedGuard.hasNext()).as("hasNext (populated guard)").isFalse();
        assertThat(populatedGuard.hasPrevious()).as("hasPrevious (populated guard)").isFalse();
        assertThat(populatedGuard.first()).as("first (populated guard)").isTrue();
        assertThat(populatedGuard.last()).as("last (populated guard)").isTrue();
    }

    @Test
    @DisplayName("of(...) echoes content, pageNumber, pageSize, and totalElements through unchanged")
    void factoryEchoesRawInputsUnchanged() {
        List<String> page = List.of("alpha", "bravo");
        PageResponse<String> response = PageResponse.of(page, 2, CARD_ROWS_PER_PAGE, 21);
        assertThat(response.content()).as("content").containsExactly("alpha", "bravo");
        assertThat(response.pageNumber()).as("pageNumber").isEqualTo(2);
        assertThat(response.pageSize()).as("pageSize").isEqualTo(CARD_ROWS_PER_PAGE);
        assertThat(response.totalElements()).as("totalElements").isEqualTo(21L);
    }

    @Test
    @DisplayName("Null content is normalised to an empty, non-null list")
    void nullContentIsNormalisedToEmptyList() {
        PageResponse<String> response = PageResponse.<String>of(null, 1, CARD_ROWS_PER_PAGE, 0);
        assertThat(response.content()).as("normalised content").isNotNull().isEmpty();
    }

    @Test
    @DisplayName("The content list is immutable (defensive copy)")
    void contentListIsImmutable() {
        PageResponse<String> response = PageResponse.of(List.of("alpha"), 1, CARD_ROWS_PER_PAGE, 1);
        assertThatThrownBy(() -> response.content().add("bravo"))
                .as("content() must expose an unmodifiable list")
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ------------------------------------------------------------------
    // Phase 2 — JSON contract. The record must round-trip losslessly and
    // expose exactly its nine documented properties on the wire.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A PageResponse<String> round-trips losslessly through JSON via a TypeReference")
    void jsonRoundTripPreservesEquality() {
        PageResponse<String> original =
                PageResponse.of(List.of("alpha", "bravo", "charlie"), 2, CARD_ROWS_PER_PAGE, 21);
        String json = DtoTestSupport.toJson(original);
        PageResponse<String> restored =
                DtoTestSupport.fromJson(json, new TypeReference<PageResponse<String>>() { });
        assertThat(restored)
                .as("JSON round-trip must preserve record equality")
                .isEqualTo(original);
    }

    @Test
    @DisplayName("Serialized JSON exposes exactly the nine documented properties")
    void jsonContainsAllNineProperties() {
        PageResponse<String> response =
                PageResponse.of(List.of("alpha", "bravo", "charlie"), 2, CARD_ROWS_PER_PAGE, 21);
        String json = DtoTestSupport.toJson(response);
        Map<String, Object> asMap =
                DtoTestSupport.fromJson(json, new TypeReference<Map<String, Object>>() { });
        assertThat(asMap).containsOnlyKeys(
                "content",
                "pageNumber",
                "pageSize",
                "totalElements",
                "totalPages",
                "hasNext",
                "hasPrevious",
                "first",
                "last");
    }

    // ------------------------------------------------------------------
    // Phase 3 — record equality / hashCode semantics.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Two PageResponses built from equal arguments are equal and share a hashCode")
    void equalArgumentsProduceEqualRecords() {
        PageResponse<String> left = PageResponse.of(List.of("x", "y"), 1, CARD_ROWS_PER_PAGE, 2);
        PageResponse<String> right = PageResponse.of(List.of("x", "y"), 1, CARD_ROWS_PER_PAGE, 2);
        assertThat(left).isEqualTo(right);
        assertThat(left).hasSameHashCodeAs(right);
    }

    @Test
    @DisplayName("A differing component breaks record equality")
    void differingComponentBreaksEquality() {
        PageResponse<String> base = PageResponse.of(List.of("x", "y"), 1, CARD_ROWS_PER_PAGE, 2);
        // Same page and content but a different total -> different totalPages/hasNext/last.
        PageResponse<String> differentTotals =
                PageResponse.of(List.of("x", "y"), 1, CARD_ROWS_PER_PAGE, 9);
        assertThat(base).isNotEqualTo(differentTotals);
    }
}
