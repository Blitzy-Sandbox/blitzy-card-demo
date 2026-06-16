package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.batch.processors.TransactionReportProcessor;
import com.carddemo.batch.processors.TransactionReportProcessor.ReportLine;
import com.carddemo.exception.RecordNotFoundException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TransactionReportProcessor} focused on the CP3 COBOL-parity
 * finding: the three reference lookups must be <strong>fail-fast</strong>, matching the
 * {@code app/cbl/CBTRN03C.cbl} {@code INVALID KEY} paths ({@code 1500-A-LOOKUP-XREF},
 * {@code 1500-B-LOOKUP-TRANTYPE}, {@code 1500-C-LOOKUP-TRANCATG}), each of which performs
 * {@code 9999-ABEND-PROGRAM} (a {@code CEE3ABD} abend) rather than emitting an incomplete
 * report row. The Java equivalent throws {@link RecordNotFoundException} (COBOL
 * {@code FILE STATUS '23'}).
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}). The
 * tests assert that (1) a fully resolved record renders a complete {@link ReportLine};
 * (2) each missing lookup is fatal; (3) the cross-reference diagnostic masks the card
 * number to its last four digits and never leaks the full PAN; and (4) the
 * date-window/malformed-date filters remain non-fatal record skips (returning
 * {@code null}), because the COBOL main loop simply skips out-of-window records rather
 * than abending. The collaborators are mocked because the assertions concern the lookup
 * control flow; {@link DateValidationService} is dependency-free so a real instance is
 * used, and the {@code MeterRegistry} is a real {@link SimpleMeterRegistry}.</p>
 */
@DisplayName("TransactionReportProcessor - fail-fast reference lookups with masked card diagnostics")
class TransactionReportProcessorTest {

    private static final String START_DATE = "2024-01-01";
    private static final String END_DATE = "2024-12-31";
    private static final String IN_WINDOW_PROC_TS = "2024-06-15-12.34.56.789012";
    private static final String FULL_PAN = "1111222233334444";
    private static final String MASKED_PAN = "****4444";
    private static final String TYPE_CODE = "01";
    private static final Integer CATEGORY_CODE = 5;

    private static final String RECORDS_PROCESSED_METRIC = "carddemo.batch.records.processed";
    private static final String RECORDS_REJECTED_METRIC = "carddemo.batch.records.rejected";
    private static final String REASON_TAG = "reason";

    private CardCrossReferenceRepository cardCrossReferenceRepository;
    private TransactionTypeRepository transactionTypeRepository;
    private TransactionCategoryRepository transactionCategoryRepository;
    private SimpleMeterRegistry meterRegistry;
    private TransactionReportProcessor processor;

    @BeforeEach
    void setUp() {
        cardCrossReferenceRepository = mock(CardCrossReferenceRepository.class);
        transactionTypeRepository = mock(TransactionTypeRepository.class);
        transactionCategoryRepository = mock(TransactionCategoryRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        processor = new TransactionReportProcessor(
                cardCrossReferenceRepository,
                transactionTypeRepository,
                transactionCategoryRepository,
                new DateValidationService(),
                meterRegistry,
                START_DATE,
                END_DATE);
    }

    @Test
    @DisplayName("All lookups resolve: emits a complete report line and counts it as processed")
    void allLookupsResolve_emitsCompleteReportLine() {
        Transaction txn = inWindowTransaction();
        when(cardCrossReferenceRepository.findById(FULL_PAN))
                .thenReturn(Optional.of(xref(FULL_PAN, 100L)));
        when(transactionTypeRepository.findById(TYPE_CODE))
                .thenReturn(Optional.of(type(TYPE_CODE, "PURCHASE")));
        when(transactionCategoryRepository.findById(new TransactionCategoryId(TYPE_CODE, CATEGORY_CODE)))
                .thenReturn(Optional.of(category(TYPE_CODE, CATEGORY_CODE, "RETAIL")));

        ReportLine line = processor.process(txn);

        assertThat(line).isNotNull();
        assertThat(line.tranId()).isEqualTo("0000000000000001");
        assertThat(line.cardNumber()).isEqualTo(FULL_PAN);
        assertThat(line.accountId()).isEqualTo(100L);
        assertThat(line.typeCode()).isEqualTo(TYPE_CODE);
        assertThat(line.typeDescription()).isEqualTo("PURCHASE");
        assertThat(line.categoryCode()).isEqualTo(CATEGORY_CODE);
        assertThat(line.categoryDescription()).isEqualTo("RETAIL");
        assertThat(line.amount()).isEqualByComparingTo(new BigDecimal("123.45"));
        assertThat(meterRegistry.counter(RECORDS_PROCESSED_METRIC).count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Missing cross-reference is fatal: throws RecordNotFoundException, masks the card, leaks no full PAN")
    void missingCrossReference_isFatal_andMasksCardNumber() {
        Transaction txn = inWindowTransaction();
        when(cardCrossReferenceRepository.findById(FULL_PAN)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> processor.process(txn));

        assertThat(thrown).isInstanceOf(RecordNotFoundException.class);
        assertThat(thrown.getMessage())
                .contains("INVALID CARD NUMBER")
                .contains(MASKED_PAN)
                .doesNotContain(FULL_PAN);
        // No line was emitted, so nothing is counted as processed.
        assertThat(meterRegistry.counter(RECORDS_PROCESSED_METRIC).count()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Missing transaction type is fatal: throws RecordNotFoundException with the visible type code")
    void missingTransactionType_isFatal() {
        Transaction txn = inWindowTransaction();
        when(cardCrossReferenceRepository.findById(FULL_PAN))
                .thenReturn(Optional.of(xref(FULL_PAN, 100L)));
        when(transactionTypeRepository.findById(TYPE_CODE)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> processor.process(txn));

        assertThat(thrown).isInstanceOf(RecordNotFoundException.class);
        assertThat(thrown.getMessage())
                .contains("INVALID TRANSACTION TYPE")
                .contains(TYPE_CODE)
                .doesNotContain(FULL_PAN);
    }

    @Test
    @DisplayName("Missing transaction category is fatal: throws RecordNotFoundException with the visible type/category key")
    void missingTransactionCategory_isFatal() {
        Transaction txn = inWindowTransaction();
        when(cardCrossReferenceRepository.findById(FULL_PAN))
                .thenReturn(Optional.of(xref(FULL_PAN, 100L)));
        when(transactionTypeRepository.findById(TYPE_CODE))
                .thenReturn(Optional.of(type(TYPE_CODE, "PURCHASE")));
        when(transactionCategoryRepository.findById(new TransactionCategoryId(TYPE_CODE, CATEGORY_CODE)))
                .thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> processor.process(txn));

        assertThat(thrown).isInstanceOf(RecordNotFoundException.class);
        assertThat(thrown.getMessage())
                .contains("INVALID TRAN CATG KEY")
                .contains(TYPE_CODE + "/" + CATEGORY_CODE)
                .doesNotContain(FULL_PAN);
    }

    @Test
    @DisplayName("Out-of-window record is filtered (null), not fatal, and is counted as rejected")
    void outOfWindowRecord_isFilteredNotFatal() {
        Transaction txn = inWindowTransaction();
        txn.setTranProcTs("2023-12-31-23.59.59.999999");

        ReportLine line = processor.process(txn);

        assertThat(line).isNull();
        assertThat(meterRegistry.counter(RECORDS_REJECTED_METRIC, REASON_TAG, "out_of_window").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter(RECORDS_PROCESSED_METRIC).count()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Malformed processing date is filtered (null), not fatal, and is counted as rejected")
    void malformedProcessingDate_isFilteredNotFatal() {
        Transaction txn = inWindowTransaction();
        txn.setTranProcTs("2024");

        ReportLine line = processor.process(txn);

        assertThat(line).isNull();
        assertThat(meterRegistry.counter(RECORDS_REJECTED_METRIC, REASON_TAG, "malformed_proc_date").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter(RECORDS_PROCESSED_METRIC).count()).isEqualTo(0.0);
    }

    private Transaction inWindowTransaction() {
        Transaction txn = new Transaction();
        txn.setTranId("0000000000000001");
        txn.setTranCardNum(FULL_PAN);
        txn.setTranTypeCd(TYPE_CODE);
        txn.setTranCatCd(CATEGORY_CODE);
        txn.setTranSource("POS");
        txn.setTranAmt(new BigDecimal("123.45"));
        txn.setTranProcTs(IN_WINDOW_PROC_TS);
        return txn;
    }

    private CardCrossReference xref(String cardNumber, Long accountId) {
        CardCrossReference xref = new CardCrossReference();
        xref.setXrefCardNum(cardNumber);
        xref.setXrefAcctId(accountId);
        return xref;
    }

    private TransactionType type(String typeCode, String description) {
        TransactionType type = new TransactionType();
        type.setTranType(typeCode);
        type.setTranTypeDesc(description);
        return type;
    }

    private TransactionCategory category(String typeCode, Integer categoryCode, String description) {
        TransactionCategory category = new TransactionCategory();
        category.setId(new TransactionCategoryId(typeCode, categoryCode));
        category.setTranCatTypeDesc(description);
        return category;
    }
}
