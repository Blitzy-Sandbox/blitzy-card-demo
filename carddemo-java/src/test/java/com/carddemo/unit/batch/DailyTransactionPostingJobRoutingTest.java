package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.support.ClassifierCompositeItemWriter;
import org.springframework.batch.test.MetaDataInstanceFactory;

import com.carddemo.batch.jobs.DailyTransactionPostingJob;
import com.carddemo.batch.jobs.DailyTransactionPostingJob.RejectRoutingWriter;
import com.carddemo.batch.processors.TransactionPostingProcessor;
import com.carddemo.batch.processors.TransactionPostingProcessor.PostingResult;
import com.carddemo.batch.readers.DailyTransactionReader;
import com.carddemo.batch.writers.RejectWriter;
import com.carddemo.batch.writers.TransactionWriter;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.DailyTransaction;
import com.carddemo.model.entity.Transaction;
import com.carddemo.model.entity.TransactionCategoryBalance;
import com.carddemo.model.enums.RejectCode;

/**
 * Pure-JVM unit tests for the {@link ClassifierCompositeItemWriter} routing assembled by
 * {@link DailyTransactionPostingJob}, the Spring Batch re-host of the mainframe daily-transaction
 * posting job {@code app/jcl/POSTTRAN.jcl} (single step {@code EXEC PGM=CBTRN02C}) and COBOL
 * program {@code app/cbl/CBTRN02C.cbl} (source commit {@code 27d6c6f}; REFERENCE ONLY &mdash; no
 * COBOL/JCL is copied).
 *
 * <p>The COBOL main loop validated each {@code DALYTRAN} record ({@code 1500-VALIDATE-TRAN}) and
 * then either posted it ({@code 2000-POST-TRANSACTION}) or wrote a reject record
 * ({@code 2500-WRITE-REJECT-REC}). The job models that fork as a
 * {@link ClassifierCompositeItemWriter} keyed on {@link PostingResult#rejected()}. These tests
 * exercise only that composite writer's {@code write(...)} (and the reject-counting
 * {@link RejectRoutingWriter} listener's {@code afterStep(...)}); they construct the
 * {@code @Configuration} class directly with mocked collaborators and never start a real job,
 * a Spring context, Testcontainers, or LocalStack.</p>
 *
 * <p>The binding assertions are:</p>
 * <ul>
 *   <li>a posted result ({@code rejected()==false}) is routed to the {@link TransactionWriter},
 *       which itself consumes the {@link PostingResult} (there is no intermediate wrapper);</li>
 *   <li>a rejected result ({@code rejected()==true}) is routed through the
 *       {@link RejectRoutingWriter} adapter, which re-serializes the original
 *       {@link DailyTransaction} to its 350-byte {@code CVTRA06Y} image and forwards a
 *       {@link RejectWriter.RejectedTransaction} carrying the numeric reject code and the
 *       fixed-width 76-character reason description to the {@link RejectWriter};</li>
 *   <li>the step exit status becomes {@link DailyTransactionPostingJob#COMPLETED_WITH_REJECTS}
 *       when at least one record was rejected (the COBOL {@code IF WS-REJECT-COUNT > 0 MOVE 4 TO
 *       RETURN-CODE} warning), and {@link ExitStatus#COMPLETED} otherwise.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DailyTransactionPostingJob - ClassifierCompositeItemWriter routing")
class DailyTransactionPostingJobRoutingTest {

    /** Account identifier carried by the posted result and asserted on the routed item. */
    private static final long ACCOUNT_ID = 12345L;

    /** Deterministic transaction id for the posted fixture (COBOL {@code TRAN-ID PIC X(16)}). */
    private static final String TRAN_ID = "DT00000000000001";

    /** Total fixed length of a {@code CVTRA06Y} daily-transaction record, in bytes. */
    private static final int CVTRA06Y_RECORD_LENGTH = 350;

    /** Width of the COBOL {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} reason description. */
    private static final int REASON_DESC_WIDTH = 76;

    @Mock
    private DailyTransactionReader dailyTransactionReader;

    @Mock
    private TransactionPostingProcessor transactionPostingProcessor;

    @Mock
    private TransactionWriter transactionWriter;

    @Mock
    private RejectWriter rejectWriter;

    /** The composite writer under test, obtained from the production bean factory method. */
    private ClassifierCompositeItemWriter<PostingResult> writer;

    /** The shared reject routing writer; also the reject-counting step listener under test. */
    private RejectRoutingWriter rejectRoutingWriter;

    private Transaction tx;
    private Account acct;
    private DailyTransaction daily;
    private PostingResult posted;
    private PostingResult rejected;

    @BeforeEach
    void setUp() {
        DailyTransactionPostingJob job = new DailyTransactionPostingJob(
                dailyTransactionReader, transactionPostingProcessor, transactionWriter, rejectWriter);
        // The reject delegate the classifier routes to is the same instance registered as the
        // step's reject-counting listener, exactly as the production wiring shares it.
        rejectRoutingWriter = job.dailyTransactionRejectRoutingWriter();
        writer = job.dailyTransactionPostingResultWriter(rejectRoutingWriter);

        tx = transaction(TRAN_ID, new BigDecimal("100.00"));
        acct = account(ACCOUNT_ID);
        TransactionCategoryBalance categoryBalance = categoryBalance();
        posted = PostingResult.posted(tx, acct, categoryBalance);

        daily = dailyTransaction("9999999999999999", new BigDecimal("250.50"));
        rejected = PostingResult.rejected(RejectCode.INVALID_CARD_NUMBER, daily);
    }

    @Test
    @DisplayName("posted -> TransactionWriter and rejected -> RejectWriter, no cross-routing")
    void routesPostedToTransactionWriter_andRejectedToRejectWriter() throws Exception {
        writer.write(Chunk.of(posted, rejected));

        // Posted branch: the classifier forwards the raw PostingResult to the TransactionWriter
        // (the writer itself consumes PostingResult; there is no intermediate adapter type).
        ArgumentCaptor<Chunk<PostingResult>> txCaptor = ArgumentCaptor.captor();
        verify(transactionWriter).write(txCaptor.capture());
        Chunk<PostingResult> txChunk = txCaptor.getValue();
        assertThat(txChunk.getItems()).hasSize(1);
        PostingResult routedPosted = txChunk.getItems().get(0);
        assertThat(routedPosted).isSameAs(posted);
        assertThat(routedPosted.rejected()).isFalse();
        assertThat(routedPosted.postedTransaction()).isSameAs(tx);
        assertThat(routedPosted.updatedAccount().getAcctId()).isEqualTo(ACCOUNT_ID);

        // Rejected branch: the RejectRoutingWriter adapter maps the result onto a
        // RejectedTransaction (350-byte record + numeric code + 76-char description) and
        // delegates the chunk to the RejectWriter.
        ArgumentCaptor<Chunk<RejectWriter.RejectedTransaction>> rjCaptor = ArgumentCaptor.captor();
        verify(rejectWriter).write(rjCaptor.capture());
        Chunk<RejectWriter.RejectedTransaction> rjChunk = rjCaptor.getValue();
        assertThat(rjChunk.getItems()).hasSize(1);
        RejectWriter.RejectedTransaction rj = rjChunk.getItems().get(0);
        assertThat(rj.reasonCode()).isEqualTo(100);
        assertThat(rj.reasonDescription())
                .isEqualTo(rejected.rejectReasonDescription())
                .startsWith(RejectCode.INVALID_CARD_NUMBER.getDescription())
                .hasSize(REASON_DESC_WIDTH);
        assertThat(rj.dailyTransactionRecord()).isNotNull().hasSize(CVTRA06Y_RECORD_LENGTH);

        // Each writer received exactly one chunk; posted never reached the RejectWriter and the
        // rejected record never reached the TransactionWriter.
        verifyNoMoreInteractions(transactionWriter, rejectWriter);
    }

    @Test
    @DisplayName("all posted -> only TransactionWriter is invoked")
    void allPosted_onlyTransactionWriterInvoked() throws Exception {
        writer.write(Chunk.of(posted));

        verify(transactionWriter).write(any());
        verify(rejectWriter, never()).write(any());
    }

    @Test
    @DisplayName("all rejected -> only RejectWriter is invoked")
    void allRejected_onlyRejectWriterInvoked() throws Exception {
        writer.write(Chunk.of(rejected));

        verify(rejectWriter).write(any());
        verify(transactionWriter, never()).write(any());
    }

    @Test
    @DisplayName("afterStep with rejects -> exit status COMPLETED_WITH_REJECTS (RC=4 parity)")
    void afterStep_withRejects_returnsCompletedWithRejects() throws Exception {
        StepExecution stepExecution = MetaDataInstanceFactory.createStepExecution();
        rejectRoutingWriter.beforeStep(stepExecution);

        // A single rejected chunk increments the listener's reject tally (the COBOL
        // WS-REJECT-COUNT equivalent); the delegate write to the mocked RejectWriter is a no-op.
        rejectRoutingWriter.write(Chunk.of(rejected));

        assertThat(rejectRoutingWriter.afterStep(stepExecution).getExitCode())
                .isEqualTo(DailyTransactionPostingJob.COMPLETED_WITH_REJECTS);
    }

    @Test
    @DisplayName("afterStep with zero rejects -> exit status COMPLETED")
    void afterStep_withoutRejects_returnsCompleted() {
        StepExecution stepExecution = MetaDataInstanceFactory.createStepExecution();
        rejectRoutingWriter.beforeStep(stepExecution);

        assertThat(rejectRoutingWriter.afterStep(stepExecution).getExitCode())
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private static Transaction transaction(String id, BigDecimal amount) {
        Transaction t = new Transaction();
        t.setTranId(id);
        t.setTranTypeCd("02");
        t.setTranCatCd(5411);
        t.setTranSource("POS");
        t.setTranDesc("UNIT TEST TRANSACTION");
        t.setTranAmt(amount);
        t.setTranMerchantId(123456789L);
        t.setTranMerchantName("ACME STORE");
        t.setTranMerchantCity("ANYTOWN");
        t.setTranMerchantZip("00000");
        t.setTranCardNum("1234567890123456");
        t.setTranOrigTs("2024-01-15-12.00.00.000000");
        t.setTranProcTs("2024-01-16-08.30.00.000000");
        return t;
    }

    private static Account account(long acctId) {
        Account a = new Account();
        a.setAcctId(acctId);
        a.setAcctCurrBal(new BigDecimal("1000.00"));
        a.setAcctCreditLimit(new BigDecimal("5000.00"));
        a.setVersion(0L);
        return a;
    }

    private static TransactionCategoryBalance categoryBalance() {
        // The posted result's category balance is carried through to the (mocked) TransactionWriter
        // but is not inspected by these routing assertions, so a bare instance is sufficient.
        TransactionCategoryBalance b = new TransactionCategoryBalance();
        b.setTranCatBal(new BigDecimal("100.00"));
        return b;
    }

    private static DailyTransaction dailyTransaction(String cardNum, BigDecimal amount) {
        DailyTransaction d = new DailyTransaction();
        d.setDalytranId("DT00000000000099");
        d.setDalytranTypeCd("02");
        d.setDalytranCatCd(5411);
        d.setDalytranSource("POS");
        d.setDalytranDesc("REJECTED UNIT TEST");
        d.setDalytranAmt(amount);
        d.setDalytranMerchantId(123456789L);
        d.setDalytranMerchantName("ACME STORE");
        d.setDalytranMerchantCity("ANYTOWN");
        d.setDalytranMerchantZip("00000");
        d.setDalytranCardNum(cardNum);
        d.setDalytranOrigTs("2024-01-15-12.00.00.000000");
        d.setDalytranProcTs("2024-01-16-08.30.00.000000");
        return d;
    }
}
