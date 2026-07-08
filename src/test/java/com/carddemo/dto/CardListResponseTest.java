package com.carddemo.dto;

import static com.carddemo.dto.DtoTestSupport.assertPanMaskedLast4;
import static com.carddemo.dto.DtoTestSupport.componentNames;
import static com.carddemo.dto.DtoTestSupport.roundTrip;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CardListResponse}, the aggregate response for the Card
 * List transaction (CCLI) implemented by COBOL {@code COCRDLIC} (source commit
 * {@code 27d6c6f}). The legacy symbolic map renders up to seven card rows per
 * page, and each row exposes a masked Primary Account Number; this DTO carries
 * the two optional filter values (account id, card number) plus a
 * {@link PageResponse} of {@link CardListItem} rows.
 *
 * <p>These tests pin the {@code PAGE_SIZE = 7} pagination-parity constant, the
 * structural contract, and the PAN-masking security invariant that flows
 * through from {@link CardListItem}. They contribute to the CP2 test-coverage
 * gate (Gate 8, JaCoCo &ge; 80%).</p>
 */
@DisplayName("CardListResponse — card-list aggregate response DTO")
class CardListResponseTest {

    /** Account-id filter fixture ({@code ACCT-ID} PIC 9(11)). */
    private static final String ACCOUNT_FILTER = "00000000001";

    /** A full 16-digit PAN whose visible last four are {@code 1234}. */
    private static final String FULL_PAN_1 = "4111111111111234";

    /** A second full 16-digit PAN whose visible last four are {@code 5678}. */
    private static final String FULL_PAN_2 = "4222222222225678";

    private static PageResponse<CardListItem> onePage() {
        List<CardListItem> rows = List.of(
                new CardListItem("00000000001", FULL_PAN_1, "Y"),
                new CardListItem("00000000002", FULL_PAN_2, "N"));
        return PageResponse.of(rows, 1, CardListResponse.PAGE_SIZE, 2);
    }

    @Nested
    @DisplayName("Legacy pagination parity")
    class Pagination {

        @Test
        @DisplayName("PAGE_SIZE is 7, matching the COCRDLIC seven-rows-per-page map")
        void pageSizeIsSeven() {
            assertThat(CardListResponse.PAGE_SIZE).isEqualTo(7);
        }

        @Test
        @DisplayName("PageResponse.of derives the navigation flags for the first page")
        void pageMetadata() {
            PageResponse<CardListItem> page = onePage();

            assertThat(page.pageNumber()).isEqualTo(1);
            assertThat(page.pageSize()).isEqualTo(7);
            assertThat(page.totalElements()).isEqualTo(2);
            assertThat(page.hasPrevious()).isFalse();
            assertThat(page.first()).isTrue();
        }
    }

    @Nested
    @DisplayName("Construction and accessors")
    class Construction {

        @Test
        @DisplayName("carries both filters and the page verbatim")
        void carriesFields() {
            PageResponse<CardListItem> page = onePage();

            CardListResponse response = new CardListResponse(ACCOUNT_FILTER, "4111", page);

            assertThat(response.accountIdFilter()).isEqualTo(ACCOUNT_FILTER);
            assertThat(response.cardNumberFilter()).isEqualTo("4111");
            assertThat(response.page()).isSameAs(page);
        }

        @Test
        @DisplayName("null filters are permitted (unfiltered listing)")
        void nullFiltersAllowed() {
            CardListResponse response = new CardListResponse(null, null, onePage());

            assertThat(response.accountIdFilter()).isNull();
            assertThat(response.cardNumberFilter()).isNull();
            assertThat(response.page().content()).hasSize(2);
        }

        @Test
        @DisplayName("declares exactly accountIdFilter, cardNumberFilter, and page")
        void declaresComponents() {
            // componentNames() lower-cases each record component name; assert the
            // normalized (lower-cased) names accordingly.
            assertThat(componentNames(CardListResponse.class))
                    .containsExactlyInAnyOrder("accountidfilter", "cardnumberfilter", "page");
        }
    }

    @Nested
    @DisplayName("PAN masking security invariant")
    class Masking {

        @Test
        @DisplayName("every listed card number is masked to its last four digits")
        void cardNumbersMasked() {
            CardListResponse response = new CardListResponse(ACCOUNT_FILTER, null, onePage());

            assertPanMaskedLast4(response.page().content().get(0).cardNumber(), "1234");
            assertPanMaskedLast4(response.page().content().get(1).cardNumber(), "5678");
        }
    }

    @Nested
    @DisplayName("Serialization")
    class Serialization {

        @Test
        @DisplayName("round-trips through JSON preserving masked rows and pagination")
        void jsonRoundTrip() {
            CardListResponse original = new CardListResponse(ACCOUNT_FILTER, null, onePage());

            CardListResponse restored = roundTrip(original, CardListResponse.class);

            assertThat(restored).isEqualTo(original);
            assertPanMaskedLast4(restored.page().content().get(0).cardNumber(), "1234");
            assertThat(restored.page().totalElements()).isEqualTo(2);
        }
    }
}
