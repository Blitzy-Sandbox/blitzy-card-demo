package com.carddemo.dto;

import static com.carddemo.dto.DtoTestSupport.componentNames;
import static com.carddemo.dto.DtoTestSupport.roundTrip;
import static com.carddemo.dto.DtoTestSupport.sampleMoney;
import static com.carddemo.dto.DtoTestSupport.toJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TransactionListResponse}, the aggregate response for the
 * Transaction List transaction (CT00) implemented by COBOL {@code COTRN00C}
 * (source commit {@code 27d6c6f}). The DTO carries an optional transaction-id
 * filter plus a {@link PageResponse} of {@link TransactionListItem} rows.
 *
 * <p>These tests pin the structural contract and, critically, the decimal
 * fidelity of the row amounts: each {@code TRAN-AMT PIC S9(09)V99} value must
 * serialize as a plain scale-2 number (never scientific notation) and must
 * round-trip unchanged (AAP &sect;0.8.2). They contribute to the CP2
 * test-coverage gate (Gate 8, JaCoCo &ge; 80%).</p>
 */
@DisplayName("TransactionListResponse — transaction-list aggregate response DTO")
class TransactionListResponseTest {

    /** Transaction-id filter fixture ({@code TRAN-ID} PIC X(16)). */
    private static final String TRAN_ID_FILTER = "0000000000000001";

    private static PageResponse<TransactionListItem> onePage() {
        List<TransactionListItem> rows = List.of(
                new TransactionListItem("0000000000000001", "20240115", "GROCERY STORE", sampleMoney()),
                new TransactionListItem("0000000000000002", "20240116", "FUEL PURCHASE", new BigDecimal("42.50")));
        return PageResponse.of(rows, 1, 10, 2);
    }

    @Nested
    @DisplayName("Construction and accessors")
    class Construction {

        @Test
        @DisplayName("carries the transaction-id filter and page verbatim")
        void carriesFields() {
            PageResponse<TransactionListItem> page = onePage();

            TransactionListResponse response = new TransactionListResponse(TRAN_ID_FILTER, page);

            assertThat(response.transactionIdFilter()).isEqualTo(TRAN_ID_FILTER);
            assertThat(response.page()).isSameAs(page);
        }

        @Test
        @DisplayName("null filter is permitted (unfiltered listing)")
        void nullFilterAllowed() {
            TransactionListResponse response = new TransactionListResponse(null, onePage());

            assertThat(response.transactionIdFilter()).isNull();
            assertThat(response.page().content()).hasSize(2);
        }

        @Test
        @DisplayName("declares exactly transactionIdFilter and page")
        void declaresComponents() {
            // componentNames() lower-cases each record component name; assert the
            // normalized (lower-cased) names accordingly.
            assertThat(componentNames(TransactionListResponse.class))
                    .containsExactlyInAnyOrder("transactionidfilter", "page");
        }
    }

    @Nested
    @DisplayName("Monetary fidelity of row amounts")
    class MoneyFidelity {

        @Test
        @DisplayName("row amounts serialize as plain scale-2 numbers (no scientific notation)")
        void amountsSerializePlain() {
            TransactionListResponse response = new TransactionListResponse(null, onePage());

            String json = toJson(response);

            // Assert against the raw serialized string (the on-the-wire form): the
            // amount must be the plain scale-2 literal 100.00. Re-parsing through an
            // untyped JsonNode would bind the number to a double and drop the trailing
            // zero (100.00 -> 100.0), so the raw string is the authoritative check.
            assertThat(json)
                    .as("row amount must serialize as the plain scale-2 literal 100.00")
                    .containsPattern("\"amount\"\\s*:\\s*100\\.00")
                    .doesNotContainPattern("(?i)\"amount\"\\s*:\\s*[-0-9.]+e");
        }

        @Test
        @DisplayName("row amounts round-trip unchanged with scale 2 preserved")
        void amountRoundTrips() {
            TransactionListResponse original = new TransactionListResponse(null, onePage());

            TransactionListResponse restored = roundTrip(original, TransactionListResponse.class);

            assertThat(restored).isEqualTo(original);
            BigDecimal restoredAmount = restored.page().content().get(0).amount();
            assertThat(restoredAmount).isEqualByComparingTo("100.00");
            assertThat(restoredAmount.scale()).isEqualTo(2);
        }
    }
}
