package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.carddemo.entity.CardXref;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryType;
import com.carddemo.entity.TransactionCategoryTypeId;
import com.carddemo.entity.TransactionType;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionCategoryTypeRepository;
import com.carddemo.repository.TransactionTypeRepository;

/**
 * Pure, fast unit test for {@link TransactionReportProcessor}, the enriching
 * item-processor of the transaction-detail report job migrated from the COBOL
 * batch program {@code CBTRN03C} plus the DFSORT {@code INCLUDE COND} window
 * filter ({@code app/cbl/CBTRN03C.cbl} and the report JCL/proc, frozen reference
 * SHA {@code 27d6c6f} &mdash; read-only, not copied into this repository).
 *
 * <p>All three reference repositories are Mockito mocks; the reporting window is
 * supplied through the public constructor as ISO date strings, so the suite loads
 * no Spring context and touches no database, Testcontainers, Docker, or live
 * AWS.</p>
 *
 * <p>Every filter and enrichment branch of
 * {@link TransactionReportProcessor#process(Transaction)} is exercised: the
 * unparseable/short/{@code null} processing-timestamp filter, the inclusive
 * date-window bounds ({@code GE start AND LE end}), the {@code null}-card and
 * missing-cross-reference filters, and the enrichment of type/category
 * descriptions (present and blank-default). The inverted-window guard on the
 * constructor is asserted too.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionReportProcessor — COBOL CBTRN03C report enrichment (SHA 27d6c6f)")
class TransactionReportProcessorTest {

    private static final String START = "2024-01-01";
    private static final String END = "2024-12-31";
    private static final String CARD_NUM = "4111111111111111";
    private static final long ACCT_ID = 777L;

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private TransactionTypeRepository transactionTypeRepository;

    @Mock
    private TransactionCategoryTypeRepository transactionCategoryTypeRepository;

    private TransactionReportProcessor newProcessor() {
        return new TransactionReportProcessor(
                cardXrefRepository, transactionTypeRepository,
                transactionCategoryTypeRepository, START, END);
    }

    private static Transaction tx(String procTs, String cardNum) {
        Transaction t = new Transaction();
        t.setTranId("0000000000000001");
        t.setTranProcTs(procTs);
        t.setTranCardNum(cardNum);
        t.setTranTypeCd("01");
        t.setTranCatCd(5);
        t.setTranSource("POS");
        t.setTranAmt(new BigDecimal("12.34"));
        return t;
    }

    private static CardXref xref() {
        CardXref x = new CardXref();
        x.setXrefCardNum(CARD_NUM);
        x.setXrefAcctId(ACCT_ID);
        x.setXrefCustId(42L);
        return x;
    }

    @Nested
    @DisplayName("Date-window filter (INCLUDE COND) — filtered items yield null")
    class DateWindow {

        @Test
        @DisplayName("null processing timestamp → null")
        void nullProcTs() {
            assertThat(newProcessor().process(tx(null, CARD_NUM))).isNull();
        }

        @Test
        @DisplayName("processing timestamp shorter than 10 chars → null")
        void shortProcTs() {
            assertThat(newProcessor().process(tx("2024", CARD_NUM))).isNull();
        }

        @Test
        @DisplayName("unparseable processing date → null")
        void unparseableProcTs() {
            assertThat(newProcessor().process(tx("20X4-01-15-12.00", CARD_NUM))).isNull();
        }

        @Test
        @DisplayName("date before window start → null")
        void beforeWindow() {
            assertThat(newProcessor().process(tx("2023-12-31-00.00.00", CARD_NUM))).isNull();
        }

        @Test
        @DisplayName("date after window end → null")
        void afterWindow() {
            assertThat(newProcessor().process(tx("2025-01-01-00.00.00", CARD_NUM))).isNull();
        }
    }

    @Nested
    @DisplayName("Enrichment filters — missing card / cross-reference yield null")
    class EnrichmentFilters {

        @Test
        @DisplayName("null card number → null")
        void nullCardNumber() {
            assertThat(newProcessor().process(tx("2024-06-15-00.00.00", null))).isNull();
        }

        @Test
        @DisplayName("no card cross-reference → null")
        void missingCrossReference() {
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());
            assertThat(newProcessor().process(tx("2024-06-15-00.00.00", CARD_NUM))).isNull();
        }
    }

    @Nested
    @DisplayName("Enriched detail line (in-window, resolvable)")
    class Enriched {

        @Test
        @DisplayName("resolves account id and both descriptions")
        void withDescriptions() {
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref()));
            TransactionType type = new TransactionType();
            type.setTranTypeDesc("PURCHASE");
            when(transactionTypeRepository.findById("01")).thenReturn(Optional.of(type));
            TransactionCategoryType cat = new TransactionCategoryType();
            cat.setTranCatTypeDesc("RETAIL");
            when(transactionCategoryTypeRepository.findById(any(TransactionCategoryTypeId.class)))
                    .thenReturn(Optional.of(cat));

            var line = newProcessor().process(tx("2024-06-15-00.00.00", CARD_NUM));

            assertThat(line).isNotNull();
            assertThat(line.transactionId()).isEqualTo("0000000000000001");
            assertThat(line.accountId()).isEqualTo(ACCT_ID);
            assertThat(line.typeCode()).isEqualTo("01");
            assertThat(line.typeDescription()).isEqualTo("PURCHASE");
            assertThat(line.categoryCode()).isEqualTo(5);
            assertThat(line.categoryDescription()).isEqualTo("RETAIL");
            assertThat(line.source()).isEqualTo("POS");
            assertThat(line.amount()).isEqualByComparingTo("12.34");
        }

        @Test
        @DisplayName("missing reference rows → blank descriptions (INITIALIZE default)")
        void blankDescriptionsWhenReferencesAbsent() {
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref()));
            when(transactionTypeRepository.findById("01")).thenReturn(Optional.empty());
            when(transactionCategoryTypeRepository.findById(any(TransactionCategoryTypeId.class)))
                    .thenReturn(Optional.empty());

            var line = newProcessor().process(tx("2024-06-15-00.00.00", CARD_NUM));

            assertThat(line).isNotNull();
            assertThat(line.typeDescription()).isEmpty();
            assertThat(line.categoryDescription()).isEmpty();
        }
    }

    @Test
    @DisplayName("inverted date window → IllegalArgumentException on construction")
    void invertedWindowRejected() {
        assertThatThrownBy(() -> new TransactionReportProcessor(
                cardXrefRepository, transactionTypeRepository,
                transactionCategoryTypeRepository, "2024-12-31", "2024-01-01"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
