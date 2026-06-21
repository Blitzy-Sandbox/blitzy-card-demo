package com.carddemo.unit.service.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.service.shared.PaginationSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link PaginationSupport#clampPageToMaxOffset(int, int)}, the guard that prevents
 * the offset-overflow {@code InvalidDataAccessApiUsageException} (HTTP 500) on absurd page numbers.
 * Pure JVM test (no Spring context).
 */
class PaginationSupportTest {

    private static final int CARD_PAGE_SIZE = 7;
    private static final int LIST_PAGE_SIZE = 10;

    @Test
    @DisplayName("an in-range page index is returned unchanged")
    void inRangePageReturnedUnchanged() {
        assertThat(PaginationSupport.clampPageToMaxOffset(0, CARD_PAGE_SIZE)).isZero();
        assertThat(PaginationSupport.clampPageToMaxOffset(5, CARD_PAGE_SIZE)).isEqualTo(5);
        assertThat(PaginationSupport.clampPageToMaxOffset(100, LIST_PAGE_SIZE)).isEqualTo(100);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, -5, -100, Integer.MIN_VALUE})
    @DisplayName("a negative page index is floored to the first page (0)")
    void negativePageFlooredToZero(int negativePage) {
        assertThat(PaginationSupport.clampPageToMaxOffset(negativePage, CARD_PAGE_SIZE)).isZero();
        assertThat(PaginationSupport.clampPageToMaxOffset(negativePage, LIST_PAGE_SIZE)).isZero();
    }

    @Test
    @DisplayName("the cap equals Integer.MAX_VALUE / pageSize for the application page sizes")
    void capEqualsMaxValueDividedByPageSize() {
        assertThat(PaginationSupport.clampPageToMaxOffset(Integer.MAX_VALUE, CARD_PAGE_SIZE))
                .isEqualTo(Integer.MAX_VALUE / CARD_PAGE_SIZE)
                .isEqualTo(306_783_378);
        assertThat(PaginationSupport.clampPageToMaxOffset(Integer.MAX_VALUE, LIST_PAGE_SIZE))
                .isEqualTo(Integer.MAX_VALUE / LIST_PAGE_SIZE)
                .isEqualTo(214_748_364);
    }

    @Test
    @DisplayName("a page exactly at the cap is preserved; one above the cap is clamped to the cap")
    void boundaryAtAndAboveCap() {
        int capSeven = Integer.MAX_VALUE / CARD_PAGE_SIZE;
        assertThat(PaginationSupport.clampPageToMaxOffset(capSeven, CARD_PAGE_SIZE)).isEqualTo(capSeven);
        assertThat(PaginationSupport.clampPageToMaxOffset(capSeven + 1, CARD_PAGE_SIZE)).isEqualTo(capSeven);

        int capTen = Integer.MAX_VALUE / LIST_PAGE_SIZE;
        assertThat(PaginationSupport.clampPageToMaxOffset(capTen, LIST_PAGE_SIZE)).isEqualTo(capTen);
        assertThat(PaginationSupport.clampPageToMaxOffset(capTen + 1, LIST_PAGE_SIZE)).isEqualTo(capTen);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 7, 10, 25, 100})
    @DisplayName("the clamped offset (page * pageSize) never exceeds Integer.MAX_VALUE for any huge request")
    void clampedOffsetNeverOverflows(int pageSize) {
        int clamped = PaginationSupport.clampPageToMaxOffset(Integer.MAX_VALUE, pageSize);
        long offset = (long) clamped * pageSize;
        assertThat(offset).isLessThanOrEqualTo(Integer.MAX_VALUE);
        // Allowing one more page would have overflowed, confirming the cap is maximal.
        // (Cast to long before incrementing so the +1 itself cannot overflow int.)
        assertThat(((long) clamped + 1) * pageSize).isGreaterThan(Integer.MAX_VALUE);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    @DisplayName("a pageSize below one is rejected")
    void pageSizeBelowOneRejected(int badPageSize) {
        assertThatThrownBy(() -> PaginationSupport.clampPageToMaxOffset(0, badPageSize))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageSize must be >= 1");
    }
}
