package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.math.BigDecimal;

import com.carddemo.batch.jobs.DailyTransactionPostingJob;
import com.carddemo.batch.processors.TransactionPostingProcessor;
import com.carddemo.batch.processors.TransactionPostingProcessor.PostingResult;
import com.carddemo.batch.readers.DailyTransactionReader;
import com.carddemo.batch.writers.RejectWriter;
import com.carddemo.batch.writers.TransactionWriter;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.DailyTransaction;
import com.carddemo.model.entity.Transaction;
import com.carddemo.model.enums.RejectCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.support.ClassifierCompositeItemWriter;
import org.springframework.batch.test.MetaDataInstanceFactory;

/**
 * Pure-JVM unit tests for the {@link ClassifierCompositeItemWriter} routing assembled by
 * {@link DailyTransactionPostingJob}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL/JCL not copied; source commit {@code 27d6c6f}): the
 * mainframe JCL job {@code app/jcl/POSTTRAN.jcl} (single step {@code EXEC PGM=CBTRN02C}) and the
 * COBOL daily-transaction posting program {@code app/cbl/CBTRN02C.cbl}. The COBOL per-record main
 * loop validates each {@code CVTRA06Y} daily transaction ({@code app/cpy/CVTRA06Y.cpy}) and then
 * either {@code PERFORM 2000-POST-TRANSACTION} for a valid record or
 * {@code ADD 1 TO WS-REJECT-COUNT} + {@code PERFORM 2500-WRITE-REJECT-REC} for an invalid one.
 * After the loop, {@code IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE} raises the warning return
 * code. This test proves the Java equivalents:</p>
 * <ul>
 *   <li>a posted {@link PostingResult} ({@link PostingResult#rejected()} {@code == false}) is routed
 *       to the {@link TransactionWriter}, adapted into a {@link TransactionWriter.PostedTransaction}
 *       that carries the posted transaction and its owning account id
 *       ({@code updatedAccount().getAcctId()});</li>
 *   <li>a rejected {@link PostingResult} is routed to the {@link RejectWriter}, adapted into a
 *       {@link RejectWriter.RejectedTransaction} that carries the re-serialized 350-byte daily image,
 *       the numeric reject code ({@code rejectCode()}), and the reason description
 *       ({@code rejectReasonDescription()});</li>
 *   <li>neither item crosses to the other writer (each delegate receives exactly its own items);</li>
 *   <li>the step listener maps a non-zero reject count onto the
 *       {@link DailyTransactionPostingJob#COMPLETED_WITH_REJECTS} exit status (COBOL RC=4) while a
 *       zero-reject successful step keeps the {@link ExitStatus#COMPLETED} status.</li>
 * </ul>
 *
 * <p>The system under test is exercised in isolation: the composite writer obtained from the
 * configuration's bean method is driven directly through {@link ClassifierCompositeItemWriter#write}
 * with the two delegate writers replaced by Mockito mocks, and the reject-count step listener's
 * {@link StepExecutionListener#afterStep(StepExecution)} is invoked directly. No Spring context,
 * Spring Batch {@code JobLauncher}, Testcontainers, or LocalStack is started.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DailyTransactionPostingJob - ClassifierCompositeItemWriter routing (POSTTRAN / CBTRN02C)")
class DailyTransactionPostingJobRoutingTest {

    /** Owning account id carried by the posted adapter ({@code updatedAccount().getAcctId()}). */
    private static final Long ACCOUNT_ID = 12345L;

    /** Deterministic transaction id for the posted fixture ({@code TRAN-ID}, {@code X(16)}). */
    private static final String POSTED_TRAN_ID = "DT00000000000001";

    /** Reject reason for the rejected fixture (COBOL reason {@code 100}, {@code 1500-A-LOOKUP-XREF}). */
    private static final RejectCode REJECT_REASON = RejectCode.INVALID_CARD_NUMBER;

    /** Length of the re-serialized {@code CVTRA06Y} daily-transaction image (RECLN = 350). */
    private static final int DALYTRAN_IMAGE_LENGTH = 350;

    /** Step-scoped reader; required by the constructor (not exercised by the writer routing). */
    @Mock
    private DailyTransactionReader dailyTransactionReader;

    /** Validation processor; required by the constructor (not exercised by the writer routing). */
    @Mock
    private TransactionPostingProcessor transactionPostingProcessor;

    /** Posted-result delegate; the routing target verified for posted results. */
    @Mock
    private TransactionWriter transactionWriter;

    /** Rejected-result delegate; the routing target verified for rejected results. */
    @Mock
    private RejectWriter rejectWriter;

    /** The configuration under test, reassembled with fresh mocks before each test. */
    private DailyTransactionPostingJob job;

    /** The classifier composite writer obtained from the configuration's bean method. */
    private ClassifierCompositeItemWriter<PostingResult> writer;

    /** The transaction instance carried by {@link #posted}; asserted by reference identity. */
    private Transaction postedTransaction;

    /** A posted (non-rejected) result built for the postable account. */
    private PostingResult posted;

    /** A rejected result built for reject reason {@link #REJECT_REASON}. */
    private PostingResult rejected;

    /**
     * Builds the configuration with all four constructor collaborators mocked and obtains the
     * classifier composite writer from its bean method, then prepares one posted and one rejected
     * {@link PostingResult}. The writer's classifier and adapters are real production code; only the
     * two terminal delegate writers are mocks so their inbound chunks can be captured.
     */
    @BeforeEach
    void setUp() {
        job = new DailyTransactionPostingJob(
                dailyTransactionReader, transactionPostingProcessor, transactionWriter, rejectWriter);
        // Pass-through: the bean method consumes the package-private reject-count listener returned by
        // the sibling bean method; the listener is never named here.
        writer = job.dailyTransactionPostingWriter(job.dailyTransactionPostingRejectListener());

        postedTransaction = new Transaction();
        postedTransaction.setTranId(POSTED_TRAN_ID);

        Account postableAccount = new Account();
        postableAccount.setAcctId(ACCOUNT_ID);

        posted = PostingResult.posted(postedTransaction, postableAccount);
        rejected = PostingResult.rejected(REJECT_REASON, dailyTransaction());
    }

    /**
     * Builds a populated daily transaction so the rejected adapter re-serializes a realistic
     * {@code CVTRA06Y} image. Every field is null-safe in the production encoder, but realistic
     * values make the 350-byte image assertion meaningful.
     *
     * @return a populated {@link DailyTransaction}
     */
    private static DailyTransaction dailyTransaction() {
        DailyTransaction tran = new DailyTransaction();
        tran.setDalytranId(POSTED_TRAN_ID);
        tran.setDalytranTypeCd("PR");
        tran.setDalytranCatCd(5);
        tran.setDalytranSource("POS");
        tran.setDalytranDesc("GROCERY PURCHASE");
        tran.setDalytranAmt(new BigDecimal("123.45"));
        tran.setDalytranMerchantId(999L);
        tran.setDalytranMerchantName("ACME STORE");
        tran.setDalytranMerchantCity("NEW YORK");
        tran.setDalytranMerchantZip("10001");
        tran.setDalytranCardNum("4111111111111111");
        tran.setDalytranOrigTs("2024-01-15-10.30.00.000000");
        tran.setDalytranProcTs("2024-01-16-02.00.00.000000");
        return tran;
    }

    @Test
    @DisplayName("posted routes to TransactionWriter and rejected routes to RejectWriter with correct adapters")
    void routesPostedToTransactionWriter_andRejectedToRejectWriter() throws Exception {
        writer.write(Chunk.of(posted, rejected));

        // Posted -> TransactionWriter, adapted to PostedTransaction(transaction, accountId).
        ArgumentCaptor<Chunk<TransactionWriter.PostedTransaction>> txCaptor = ArgumentCaptor.captor();
        verify(transactionWriter).write(txCaptor.capture());
        Chunk<TransactionWriter.PostedTransaction> txChunk = txCaptor.getValue();
        assertThat(txChunk.getItems()).hasSize(1);
        TransactionWriter.PostedTransaction postedItem = txChunk.getItems().get(0);
        assertThat(postedItem.transaction()).isSameAs(postedTransaction);
        assertThat(postedItem.accountId()).isEqualTo(ACCOUNT_ID);

        // Rejected -> RejectWriter, adapted to RejectedTransaction(image, reasonCode, reasonDescription).
        ArgumentCaptor<Chunk<RejectWriter.RejectedTransaction>> rjCaptor = ArgumentCaptor.captor();
        verify(rejectWriter).write(rjCaptor.capture());
        Chunk<RejectWriter.RejectedTransaction> rjChunk = rjCaptor.getValue();
        assertThat(rjChunk.getItems()).hasSize(1);
        RejectWriter.RejectedTransaction rejectedItem = rjChunk.getItems().get(0);
        assertThat(rejectedItem.reasonCode()).isEqualTo(REJECT_REASON.getCode());
        // rejectReasonDescription() is the space-padded X(76) form; assert against the source value
        // the adapter passes through, and confirm the trimmed text matches the reject reason literal.
        assertThat(rejectedItem.reasonDescription()).isEqualTo(rejected.rejectReasonDescription());
        assertThat(rejectedItem.reasonDescription().strip()).isEqualTo(REJECT_REASON.getDescription());
        assertThat(rejectedItem.dailyTransactionRecord()).hasSize(DALYTRAN_IMAGE_LENGTH);

        // No cross-routing: each delegate received exactly one chunk and nothing else.
        verifyNoMoreInteractions(transactionWriter, rejectWriter);
    }

    @Test
    @DisplayName("an all-posted chunk reaches only the TransactionWriter")
    void allPosted_onlyTransactionWriterInvoked() throws Exception {
        writer.write(Chunk.of(posted));

        verify(transactionWriter).write(any());
        verify(rejectWriter, never()).write(any());
    }

    @Test
    @DisplayName("an all-rejected chunk reaches only the RejectWriter")
    void allRejected_onlyRejectWriterInvoked() throws Exception {
        writer.write(Chunk.of(rejected));

        verify(rejectWriter).write(any());
        verify(transactionWriter, never()).write(any());
    }

    @Test
    @DisplayName("afterStep: a reject routed during the step yields COMPLETED_WITH_REJECTS (COBOL RC=4)")
    void afterStep_withRejects_returnsCompletedWithRejects() throws Exception {
        // The same listener instance backs the writer (which advances it per routed reject) and the
        // step exit-status mapping; var keeps the package-private listener type unnamed.
        var listener = job.dailyTransactionPostingRejectListener();
        var rejectCountingWriter = job.dailyTransactionPostingWriter(listener);
        rejectCountingWriter.write(Chunk.of(rejected));

        StepExecutionListener stepListener = listener;
        StepExecution stepExecution = MetaDataInstanceFactory.createStepExecution();
        stepExecution.setStatus(BatchStatus.COMPLETED);
        stepExecution.setExitStatus(ExitStatus.COMPLETED);

        assertThat(stepListener.afterStep(stepExecution).getExitCode())
                .isEqualTo(DailyTransactionPostingJob.COMPLETED_WITH_REJECTS);
    }

    @Test
    @DisplayName("afterStep: a successful step with zero rejects keeps the COMPLETED exit status")
    void afterStep_withoutRejects_returnsCompleted() throws Exception {
        var listener = job.dailyTransactionPostingRejectListener();
        var rejectCountingWriter = job.dailyTransactionPostingWriter(listener);
        // Routing only posted results must not advance the reject counter.
        rejectCountingWriter.write(Chunk.of(posted));

        StepExecutionListener stepListener = listener;
        StepExecution stepExecution = MetaDataInstanceFactory.createStepExecution();
        stepExecution.setStatus(BatchStatus.COMPLETED);
        stepExecution.setExitStatus(ExitStatus.COMPLETED);

        assertThat(stepListener.afterStep(stepExecution).getExitCode())
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
    }
}
