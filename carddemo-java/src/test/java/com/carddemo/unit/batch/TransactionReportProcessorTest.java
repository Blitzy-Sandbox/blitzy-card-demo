package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.carddemo.batch.processors.TransactionReportProcessor;
import com.carddemo.batch.processors.TransactionReportProcessor.ReportLine;
import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.model.entity.Transaction;
import com.carddemo.model.entity.TransactionCategory;
import com.carddemo.model.entity.TransactionType;
import com.carddemo.model.key.TransactionCategoryId;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionCategoryRepository;
import com.carddemo.repository.TransactionTypeRepository;
import com.carddemo.service.shared.DateValidationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link TransactionReportProcessor}, the per-record processor for the
 * transaction-report stage (lineage: COBOL {@code CBTRN03C}, source commit {@code 27d6c6f};
 * REFERENCE ONLY). The tests pin the source behaviour exactly: the inclusive
 * {@code [startDate, endDate]} date-window filter (COBOL alphanumeric {@code >=}/{@code <=}
 * comparison on the {@code PIC X(10)} processing date), the ordered
 * {@code 1500-A}/{@code 1500-B}/{@code 1500-C} enrichment lookups (blank descriptions and a
 * {@code null} account id on a lookup miss), and the {@code carddemo.batch.records.processed}
 * /{@code carddemo.batch.records.rejected} metrics with their low-cardinality
 * {@code reason} tags. A real {@link SimpleMeterRegistry} verifies the counter values.
 */
@ExtendWith(MockitoExtension.class)
class TransactionReportProcessorTest {

    private static final String METRIC_PROCESSED = "carddemo.batch.records.processed";
    private static final String METRIC_REJECTED = "carddemo.batch.records.rejected";
    private static final String TAG_REASON = "reason";
    private static final String REASON_OUT_OF_WINDOW = "out_of_window";
    private static final String REASON_MALFORMED_DATE = "malformed_date";

    private static final String START_DATE = "2026-08-01";
    private static final String END_DATE = "2026-08-31";
    private static final String CARD_NUM = "1234567890123456";
    private static final String TYPE_CD = "01";
    private static final int CAT_CD = 5;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Mock
    private TransactionTypeRepository transactionTypeRepository;

    @Mock
    private TransactionCategoryRepository transactionCategoryRepository;

    @Mock
    private DateValidationService dateValidationService;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    /** Builds a processor bound to the supplied inclusive window bounds. */
    private TransactionReportProcessor processor(String startDate, String endDate) {
        return new TransactionReportProcessor(
                cardCrossReferenceRepository,
                transactionTypeRepository,
                transactionCategoryRepository,
                dateValidationService,
                meterRegistry,
                startDate,
                endDate);
    }

    /** Builds a posted transaction carrying the given processing timestamp. */
    private Transaction transaction(String tranProcTs) {
        Transaction tx = new Transaction();
        tx.setTranId("0000000000000001");
        tx.setTranCardNum(CARD_NUM);
        tx.setTranTypeCd(TYPE_CD);
        tx.setTranCatCd(CAT_CD);
        tx.setTranSource("POS");
        tx.setTranAmt(new BigDecimal("12.34"));
        tx.setTranProcTs(tranProcTs);
        return tx;
    }

    private double rejected(String reason) {
        return meterRegistry.counter(METRIC_REJECTED, TAG_REASON, reason).count();
    }

    private double processed() {
        return meterRegistry.counter(METRIC_PROCESSED).count();
    }

    @Test
    void nullTransactionThrows() {
        assertThatThrownBy(() -> processor(START_DATE, END_DATE).process(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void nullProcessingTimestampFilteredAsMalformed() {
        // tranProcTs == null short-circuits before the date validator is consulted.
        ReportLine result = processor(START_DATE, END_DATE).process(transaction(null));

        assertThat(result).isNull();
        assertThat(rejected(REASON_MALFORMED_DATE)).isEqualTo(1.0d);
        assertThat(processed()).isEqualTo(0.0d);
    }

    @Test
    void shortProcessingTimestampFilteredAsMalformed() {
        // Fewer than 10 chars cannot hold yyyy-MM-dd; dropped without calling the validator.
        ReportLine result = processor(START_DATE, END_DATE).process(transaction("2026-08"));

        assertThat(result).isNull();
        assertThat(rejected(REASON_MALFORMED_DATE)).isEqualTo(1.0d);
    }

    @Test
    void invalidCalendarDateFilteredAsMalformed() {
        when(dateValidationService.isValidDate(anyString(), any())).thenReturn(false);

        ReportLine result =
                processor(START_DATE, END_DATE).process(transaction("2026-13-99-00.00.00.000000"));

        assertThat(result).isNull();
        assertThat(rejected(REASON_MALFORMED_DATE)).isEqualTo(1.0d);
        assertThat(processed()).isEqualTo(0.0d);
    }

    @Test
    void dateBeforeStartFilteredAsOutOfWindow() {
        when(dateValidationService.isValidDate(anyString(), any())).thenReturn(true);

        // 2026-07-31 < startDate 2026-08-01 -> out of window, no enrichment lookups.
        ReportLine result =
                processor(START_DATE, END_DATE).process(transaction("2026-07-31-23.59.59.000000"));

        assertThat(result).isNull();
        assertThat(rejected(REASON_OUT_OF_WINDOW)).isEqualTo(1.0d);
        assertThat(processed()).isEqualTo(0.0d);
    }

    @Test
    void dateAfterEndFilteredAsOutOfWindow() {
        when(dateValidationService.isValidDate(anyString(), any())).thenReturn(true);

        // 2026-09-01 > endDate 2026-08-31 -> out of window.
        ReportLine result =
                processor(START_DATE, END_DATE).process(transaction("2026-09-01-00.00.00.000000"));

        assertThat(result).isNull();
        assertThat(rejected(REASON_OUT_OF_WINDOW)).isEqualTo(1.0d);
    }

    @Test
    void inWindowRecordEnrichedFromAllLookups() {
        when(dateValidationService.isValidDate(anyString(), any())).thenReturn(true);

        CardCrossReference xref = new CardCrossReference();
        xref.setXrefCardNum(CARD_NUM);
        xref.setXrefAcctId(999L);
        when(cardCrossReferenceRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref));

        TransactionType type = new TransactionType();
        type.setTranType(TYPE_CD);
        type.setTranTypeDesc("Purchase");
        when(transactionTypeRepository.findById(TYPE_CD)).thenReturn(Optional.of(type));

        TransactionCategory category = new TransactionCategory();
        category.setId(new TransactionCategoryId(TYPE_CD, CAT_CD));
        category.setTranCatTypeDesc("Retail");
        when(transactionCategoryRepository.findById(any(TransactionCategoryId.class)))
                .thenReturn(Optional.of(category));

        ReportLine result =
                processor(START_DATE, END_DATE).process(transaction("2026-08-15-12.00.00.000000"));

        assertThat(result).isNotNull();
        assertThat(result.tranId()).isEqualTo("0000000000000001");
        assertThat(result.cardNumber()).isEqualTo(CARD_NUM);
        assertThat(result.accountId()).isEqualTo(999L);
        assertThat(result.typeCode()).isEqualTo(TYPE_CD);
        assertThat(result.typeDescription()).isEqualTo("Purchase");
        assertThat(result.categoryCode()).isEqualTo(CAT_CD);
        assertThat(result.categoryDescription()).isEqualTo("Retail");
        assertThat(result.source()).isEqualTo("POS");
        // Amount is carried through with its scale untouched (no rounding/rescale).
        assertThat(result.amount()).isEqualByComparingTo("12.34");
        assertThat(result.amount().scale()).isEqualTo(2);

        assertThat(processed()).isEqualTo(1.0d);
        assertThat(rejected(REASON_OUT_OF_WINDOW)).isEqualTo(0.0d);
        assertThat(rejected(REASON_MALFORMED_DATE)).isEqualTo(0.0d);
    }

    @Test
    void inWindowRecordWithMissingLookupsYieldsBlanksAndNullAccount() {
        when(dateValidationService.isValidDate(anyString(), any())).thenReturn(true);
        when(cardCrossReferenceRepository.findById(CARD_NUM)).thenReturn(Optional.empty());
        when(transactionTypeRepository.findById(TYPE_CD)).thenReturn(Optional.empty());
        when(transactionCategoryRepository.findById(any(TransactionCategoryId.class)))
                .thenReturn(Optional.empty());

        ReportLine result =
                processor(START_DATE, END_DATE).process(transaction("2026-08-15-12.00.00.000000"));

        assertThat(result).isNotNull();
        // Lookup misses leave the COBOL fields spaces -> account id null, descriptions blank.
        assertThat(result.accountId()).isNull();
        assertThat(result.typeDescription()).isEmpty();
        assertThat(result.categoryDescription()).isEmpty();
        assertThat(processed()).isEqualTo(1.0d);
    }

    @Test
    void openWindowBoundsAcceptAnyValidDate() {
        when(dateValidationService.isValidDate(anyString(), any())).thenReturn(true);

        CardCrossReference xref = new CardCrossReference();
        xref.setXrefCardNum(CARD_NUM);
        xref.setXrefAcctId(1L);
        when(cardCrossReferenceRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref));
        when(transactionTypeRepository.findById(TYPE_CD)).thenReturn(Optional.empty());
        when(transactionCategoryRepository.findById(any(TransactionCategoryId.class)))
                .thenReturn(Optional.empty());

        // Blank job parameters normalize to null bounds -> open window accepts a far-future date.
        ReportLine result = processor("  ", "").process(transaction("2099-01-01-00.00.00.000000"));

        assertThat(result).isNotNull();
        assertThat(processed()).isEqualTo(1.0d);
        assertThat(rejected(REASON_OUT_OF_WINDOW)).isEqualTo(0.0d);
    }
}
