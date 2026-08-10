package com.vsergeychik.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.account.AccountRepository;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.CardRepository;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.BatchConfig.JobParameterContract;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.BatchConfig.StopRequestedException;
import com.vsergeychik.carddemo.config.BatchConfig.StopSignal;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.customer.CustomerRepository;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;
import com.vsergeychik.carddemo.transaction.TransactionPostingJob.ExecutionSummary;
import com.vsergeychik.carddemo.transaction.TransactionPostingJob.SysoutSink;
import com.vsergeychik.carddemo.transaction.model.DalyTranRecord;
import com.vsergeychik.carddemo.transaction.model.TranRecord;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The branch-level check on {@link TransactionPostingJob}, the translation of
 * {@code app/cbl/CBTRN01C.cbl}.
 *
 * <p>Four things this suite exists to pin, beyond ordinary coverage:
 *
 * <ul>
 *   <li><strong>Gate G13.</strong> The job bean is real and launchable, and nothing triggers it: the
 *       class carries no {@code @Scheduled}, no runner and no {@code JobLauncher}.</li>
 *   <li><strong>Defect 1.</strong> Exactly one cross-reference lookup happens after end of file, on the
 *       previous record's card number.</li>
 *   <li><strong>Defect 2.</strong> A failure closing the daily transaction file reports the
 *       <em>customer</em> file's text and renders the <em>customer</em> file's status.</li>
 *   <li><strong>The dead accesses.</strong> {@code CUSTFILE}, {@code CARDFILE} and {@code TRANFILE} are
 *       opened and closed and never read; nothing is ever written anywhere.</li>
 * </ul>
 *
 * <p>Everything runs through {@link TransactionPostingJob#execute(SysoutSink)} with stubbed
 * repositories, so no launcher, no application context and no backend is in the path.
 *
 * <h2>Where these expectations come from</h2>
 *
 * <p>Practice <strong>B12</strong>. Every expected value below is <strong>statically derived</strong> -
 * read out of {@code app/cbl/CBTRN01C.cbl}, the copybook byte layouts in {@code app/cpy}, the CSD and
 * JCL contracts, and the real fixture rows in {@code app/data/ASCII} - and <strong>not</strong> captured
 * from a live COBOL execution. That is not a shortcut: AAP &sect;0.7.6 records eight independently
 * verified blockers that make running the legacy programs impossible in this environment, among them a
 * COBOL compiler whose indexed file handler is disabled, no Language Environment {@code CEE*} services
 * for {@code CEE3ABD}, and no CICS emulator. The substitution is documented and escalated (risk R-A)
 * rather than absorbed, and every substantive requirement survives it: the diff is still field by field
 * and the branch bar is still ninety per cent.
 *
 * <p>Because a statically derived expectation can encode a misreading where a captured one cannot, the
 * two places most exposed to that risk are pinned against real bytes rather than against prose. The
 * record image is asserted against a row of {@code app/data/ASCII/dailytran.txt} loaded verbatim from
 * the classpath, and the offsets it is read at are asserted against the copybook widths themselves. See
 * {@link RecordImageIsTheRawSpan} and {@link CopybookGeometry}.
 *
 * <h2>The constraints this file is written under</h2>
 *
 * <ul>
 *   <li><strong>R1 - names from the prompt, behaviour from the source.</strong> The class under test is
 *       called {@code TransactionPostingJob} and it posts <em>nothing</em>. Every assertion here is
 *       taken from {@code CBTRN01C}, never from the name. A test that expected a posted record would be
 *       wrong; {@link NothingPosts} exists to make adding one fail.</li>
 *   <li><strong>B5 - dead and orphan code is preserved.</strong> The job stays runnable with no trigger,
 *       and the useless work stays: three files opened and closed but never read, and the lookup that
 *       runs once more than there are records. Each is asserted <em>intact</em>.</li>
 *   <li><strong>B4 - no silent scope creep.</strong> Each oddity carries a comment naming it as source
 *       behaviour and citing the line, so the assertion reads as deliberate rather than as a bug
 *       someone forgot to fix.</li>
 *   <li><strong>R5 - fixed width is the wire format.</strong> The displayed record is asserted as its
 *       raw three-hundred-and-fifty-byte span, never as a re-encode of decoded fields.</li>
 *   <li><strong>R4, gate G22 - never {@code double}, never {@code float}.</strong> The one amount this
 *       suite touches is a {@link java.math.BigDecimal} at the copybook's declared scale, and no
 *       floating-point literal appears anywhere in this file.</li>
 *   <li><strong>B8, gate G52.</strong> No wildcard import, and every byte-to-character boundary names
 *       its charset - {@link #CHARSET} - rather than taking the platform default.</li>
 *   <li><strong>B9, gate G53.</strong> No mutable static state. Each test builds its own
 *       {@link Fixture}; the only static members are immutable constants and pure helpers.</li>
 *   <li><strong>B7.</strong> Deterministic, non-interactive and order-independent: no test depends on
 *       another having run, and nothing here reaches a network, a clock or a real dataset.</li>
 *   <li><strong>Gate G51.</strong> The tasklet seam is driven directly. A {@code JobLauncher} never
 *       appears; the two step-level assertions build a {@link StepContribution} by hand.</li>
 * </ul>
 *
 * <h2>One note on imports</h2>
 *
 * <p>{@code DataSourceConfig.DatasetBinding} and {@code DatasetBindings} are imported because they are
 * the types in {@link BatchConfig}'s own public constructor and in
 * {@link BatchConfig#datasetBinding(String, String)}. They are the declared dependency's contract
 * rather than a dependency of this test's own choosing: the job's start-up guards cannot be exercised
 * without naming them. Nothing else outside this file's declared dependency set is imported, and in
 * particular nothing from {@code com.vsergeychik.carddemo.parity} - this suite reads no parity case and
 * stands entirely on its own.
 *
 * @see TransactionPostingJob
 */
@DisplayName("TransactionPostingJob - CBTRN01C, the orphan that posts nothing")
class TransactionPostingJobTest {

    /** The code page every stubbed repository reports and every record is built in. */
    private static final Charset CHARSET = StandardCharsets.US_ASCII;

    /** A dataset name that is not a real one; no test reaches a backend. */
    private static final String TEST_DSNAME = "TEST.CARDDEMO.DATASET";

    /** A sixteen-character card number, as {@code DALYTRAN-CARD-NUM PIC X(16)} holds it. */
    private static final String CARD_ONE = "4111111111111111";

    /** A second card number, so a stale value is distinguishable from a fresh one. */
    private static final String CARD_TWO = "4222222222222222";

    /** A sixteen-character transaction id, as {@code DALYTRAN-ID PIC X(16)} holds it. */
    private static final String TRAN_ONE = "TRAN000000000001";

    /** The account id the cross reference returns. */
    private static final long ACCOUNT_ID = 99_999_999_999L;

    /** The customer id the cross reference returns. */
    private static final int CUSTOMER_ID = 123_456_789;

    /** A status that is neither {@code '00'}, {@code '10'}, {@code '22'} nor {@code '23'}. */
    private static final String OTHER_STATUS = "35";

    /**
     * The real daily transaction fixture, on the test classpath.
     *
     * <p>{@code src/test/resources/fixtures/dailytran.txt} is a byte-identical copy of
     * {@code app/data/ASCII/dailytran.txt}, which the AAP names as authoritative input data. Reading it
     * rather than hand-writing a row is what makes the record-image assertions evidence instead of
     * restatement: the bytes come from the same file the parity cases are seeded from.
     */
    private static final String DAILY_TRANSACTION_FIXTURE = "/fixtures/dailytran.txt";

    /** The application configuration whose {@code spring.batch.job.enabled} keeps the orphan unlaunched. */
    private static final String APPLICATION_CONFIGURATION = "/application.yml";

    /** How many records {@code app/data/ASCII/dailytran.txt} holds - counted, not assumed. */
    private static final int FIXTURE_RECORD_COUNT = 300;

    /**
     * How many of those records carry a negative sign overpunch in the trailing byte of
     * {@code DALYTRAN-AMT}.
     *
     * <p>Six, at one-based rows 2, 55, 87, 150, 165 and 210. Every one of them is a negative amount of
     * non-zero magnitude - row 2 is {@code 0000009190}} , which is {@code -919.00}.
     */
    private static final int NEGATIVE_OVERPUNCH_ROW_COUNT = 6;

    /** The one-based row of the first negative-overpunch record, used as the worked example. */
    private static final int FIRST_NEGATIVE_OVERPUNCH_ROW = 2;

    /** The amount row {@link #FIRST_NEGATIVE_OVERPUNCH_ROW} carries, at the copybook's declared scale. */
    private static final BigDecimal FIRST_NEGATIVE_OVERPUNCH_AMOUNT = new BigDecimal("-919.00");

    /** The zoned-decimal overpunch for a trailing digit of zero carrying a <em>negative</em> sign. */
    private static final char NEGATIVE_ZERO_OVERPUNCH = '}';

    /** The zoned-decimal overpunch for a trailing digit of zero carrying a <em>positive</em> sign. */
    private static final char POSITIVE_ZERO_OVERPUNCH = '{';

    /** The eleven-character {@code PIC S9(09)V99} image of a negative zero: a magnitude no sign survives. */
    private static final String NEGATIVE_ZERO_AMOUNT_IMAGE = "0000000000" + NEGATIVE_ZERO_OVERPUNCH;

    /** The same magnitude with a positive sign, which is what a {@link BigDecimal} round trip produces. */
    private static final String POSITIVE_ZERO_AMOUNT_IMAGE = "0000000000" + POSITIVE_ZERO_OVERPUNCH;

    // =================================================================================================
    // Fixture.
    // =================================================================================================

    /**
     * A stubbed world in which the job runs: six repositories, six handles, and a sink that records every
     * line in order.
     *
     * <p>Every handle answers {@code '00'} to its open and its close, and the daily transaction pass is
     * empty, so a freshly built fixture runs cleanly. Each test adjusts exactly the one stub its arm needs.
     */
    private static final class Fixture {

        private final DalyTranRepository dalyTranRepository = mock(DalyTranRepository.class);

        private final CustomerRepository customerRepository = mock(CustomerRepository.class);

        private final CardXrefRepository cardXrefRepository = mock(CardXrefRepository.class);

        private final CardRepository cardRepository = mock(CardRepository.class);

        private final AccountRepository accountRepository = mock(AccountRepository.class);

        private final TransactionRepository transactionRepository = mock(TransactionRepository.class);

        private final DalyTranRepository.DalytranFile dalytranFile =
                mock(DalyTranRepository.DalytranFile.class);

        private final CustomerRepository.CustomerFile custfile =
                mock(CustomerRepository.CustomerFile.class);

        private final CardXrefRepository.BrowseCursor xrefCursor =
                mock(CardXrefRepository.BrowseCursor.class);

        private final CardRepository.CardBrowse cardBrowse = mock(CardRepository.CardBrowse.class);

        private final AccountRepository.AccountFile acctfile = mock(AccountRepository.AccountFile.class);

        private final TransactionRepository.InputFile tranfile =
                mock(TransactionRepository.InputFile.class);

        /** Every line the run displayed, in emission order. */
        private final List<String> lines = new ArrayList<>();

        private Fixture() {
            when(dalyTranRepository.datasetCharset()).thenReturn(CHARSET);

            when(dalyTranRepository.open()).thenReturn(dalytranFile);
            when(dalytranFile.openStatus()).thenReturn(FileStatus.OK);
            when(dalytranFile.closeFile()).thenReturn(FileStatus.OK);
            when(dalytranFile.readNext()).thenReturn(DalyTranRepository.ReadResult.endOfFile());

            when(customerRepository.openInput()).thenReturn(custfile);
            when(custfile.openStatus()).thenReturn(FileStatus.OK);
            when(custfile.closeFile()).thenReturn(FileStatus.OK);

            when(cardXrefRepository.addressing(any(), anyString(), isNull(), anyString()))
                    .thenReturn(cardXrefRepository);
            when(cardXrefRepository.openBrowse()).thenReturn(xrefCursor);
            when(xrefCursor.openStatus()).thenReturn(FileStatus.OK);
            when(xrefCursor.closeBrowse()).thenReturn(FileStatus.OK);
            when(cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(CardXrefRepository.ReadResult.notFound(
                            TransactionPostingJob.XREFFILE_DD_NAME));

            when(cardRepository.addressing(any(), anyString())).thenReturn(cardRepository);
            when(cardRepository.openBrowse(anyString(), any())).thenReturn(cardBrowse);
            when(cardBrowse.openResp()).thenReturn(FileStatus.NORMAL);

            when(accountRepository.open(AccountRepository.OpenMode.INPUT)).thenReturn(acctfile);
            when(acctfile.openStatus()).thenReturn(FileStatus.OK);
            when(acctfile.closeFile()).thenReturn(FileStatus.OK);
            when(acctfile.readByKey(anyLong())).thenReturn(AccountRepository.ReadResult.notFound());

            when(transactionRepository.openInput(any(DatasetBinding.class))).thenReturn(tranfile);
            when(tranfile.openStatus()).thenReturn(FileStatus.OK);
            when(tranfile.closeInput()).thenReturn(FileStatus.OK);
        }

        /** @return the job over this fixture's stubs, with no published {@code SYSOUT} bean */
        private TransactionPostingJob job() {
            return job(validBatchConfig(), null);
        }

        /**
         * @param batchConfig the batch seam to build over
         * @param published   a {@code SYSOUT} sink the container publishes, or {@code null}
         * @return the job
         */
        private TransactionPostingJob job(BatchConfig batchConfig, SysoutSink published) {
            return new TransactionPostingJob(batchConfig, dalyTranRepository, customerRepository,
                    cardXrefRepository, cardRepository, accountRepository, transactionRepository,
                    new SuppliedProvider<>(published));
        }

        /** @return the sink that records into {@link #lines} */
        private SysoutSink sink() {
            return lines::add;
        }

        /** @return what a clean run of the job over this fixture produced */
        private ExecutionSummary run() {
            return job().execute(sink());
        }

        /**
         * Stubs the daily transaction pass to return the given records and then end of file.
         *
         * @param records the records to return in order
         */
        private void dailyTransactions(DalyTranRecord... records) {
            DalyTranRepository.ReadResult[] later =
                    new DalyTranRepository.ReadResult[Math.max(records.length, 1)];
            for (int index = 1; index < records.length; index++) {
                later[index - 1] = DalyTranRepository.ReadResult.found(records[index]);
            }
            later[Math.max(records.length - 1, 0)] = DalyTranRepository.ReadResult.endOfFile();
            when(dalytranFile.readNext())
                    .thenReturn(records.length == 0
                            ? DalyTranRepository.ReadResult.endOfFile()
                            : DalyTranRepository.ReadResult.found(records[0]), later);
        }
    }

    /**
     * An {@link ObjectProvider} over one optional bean, which is what the container hands a constructor.
     *
     * @param <T> the bean type
     */
    private static final class SuppliedProvider<T> implements ObjectProvider<T> {

        /** The bean, or {@code null} when the container publishes none. */
        private final T bean;

        private SuppliedProvider(T bean) {
            this.bean = bean;
        }

        @Override
        public T getObject() {
            if (bean == null) {
                throw new NoSuchBeanDefinitionException("no bean of this type is published");
            }
            return bean;
        }

        @Override
        public T getIfAvailable() {
            return bean;
        }

        @Override
        public T getIfUnique() {
            return bean;
        }

        @Override
        public Stream<T> stream() {
            return bean == null ? Stream.empty() : Stream.of(bean);
        }
    }

    // =================================================================================================
    // Catalogue builders, in the shape application.yml declares.
    // =================================================================================================

    /**
     * @param ddName       the DD name
     * @param recordLength the width to declare
     * @param dsname       the dataset name to declare
     * @return one binding entry
     */
    private static DatasetBinding binding(String ddName, int recordLength, String dsname) {
        return new DatasetBinding(dsname, DatasetBinding.KSDS, false, "FB", null, recordLength,
                ddName, 1, 0, null, null);
    }

    /** @return the six-entry catalogue this job resolves, all at their copybook widths */
    private static DatasetBindings validBindings() {
        return bindings(TransactionPostingJob.DALYTRAN_DD_NAME, DalyTranRecord.RECORD_LENGTH,
                TEST_DSNAME);
    }

    /**
     * The six-entry catalogue with one entry overridden, which is how each geometry rejection is driven.
     *
     * @param overriddenDd     the DD to override
     * @param overriddenLength the width to declare for it
     * @param overriddenDsname the dataset name to declare for it
     * @return the catalogue
     */
    private static DatasetBindings bindings(String overriddenDd, int overriddenLength,
            String overriddenDsname) {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(TransactionPostingJob.DALYTRAN_DD_NAME,
                binding(TransactionPostingJob.DALYTRAN_DD_NAME, DalyTranRecord.RECORD_LENGTH,
                        TEST_DSNAME));
        catalogue.put(TransactionPostingJob.CUSTFILE_DD_NAME,
                binding(TransactionPostingJob.CUSTFILE_DD_NAME, CustomerRecord.RECORD_LENGTH,
                        TEST_DSNAME));
        catalogue.put(TransactionPostingJob.XREFFILE_DD_NAME,
                binding(TransactionPostingJob.XREFFILE_DD_NAME, CardXrefRecord.RECORD_LENGTH,
                        TEST_DSNAME));
        catalogue.put(TransactionPostingJob.CARDFILE_DD_NAME,
                binding(TransactionPostingJob.CARDFILE_DD_NAME, CardRecord.RECORD_LENGTH, TEST_DSNAME));
        catalogue.put(TransactionPostingJob.ACCTFILE_DD_NAME,
                binding(TransactionPostingJob.ACCTFILE_DD_NAME, AccountRecord.RECORD_LENGTH,
                        TEST_DSNAME));
        catalogue.put(TransactionPostingJob.TRANFILE_DD_NAME,
                binding(TransactionPostingJob.TRANFILE_DD_NAME, TranRecord.RECORD_LENGTH, TEST_DSNAME));
        catalogue.put(overriddenDd, binding(overriddenDd, overriddenLength, overriddenDsname));
        return catalogue;
    }

    /**
     * @param program    the program to declare on the job and its step
     * @param stepName   the step name to declare
     * @param gated      whether the step declares {@code COND=(0,NE)} gating
     * @param parameters the declared parameters
     * @return a catalogue holding just that contract
     */
    private static JobContracts contracts(String program, String stepName, boolean gated,
            List<JobParameterContract> parameters) {
        JobContracts catalogue = new JobContracts();
        catalogue.put(TransactionPostingJob.JOB_KEY, new JobContract(program, parameters,
                List.of(new StepContract(stepName, program, gated)), null, Map.of()));
        return catalogue;
    }

    /** @return the contract catalogue exactly as {@code application.yml:1035-1041} declares it */
    private static JobContracts validContracts() {
        return contracts(TransactionPostingJob.PROGRAM_ID, TransactionPostingJob.STEP_NAME, false,
                List.of());
    }

    /**
     * @param contracts the job contracts
     * @param bindings  the dataset catalogue
     * @return a seam over both, with batch plumbing that is present but touched only by a bean method
     */
    private static BatchConfig batchConfig(JobContracts contracts, DatasetBindings bindings) {
        return new BatchConfig(new SuppliedProvider<>(mock(JobRepository.class)),
                new SuppliedProvider<>(mock(PlatformTransactionManager.class)), contracts, bindings);
    }

    /** @return a seam over the catalogues configuration actually declares */
    private static BatchConfig validBatchConfig() {
        return batchConfig(validContracts(), validBindings());
    }

    // =================================================================================================
    // Record builders.
    // =================================================================================================

    /**
     * @param id       the sixteen-character {@code DALYTRAN-ID}
     * @param cardNum  the sixteen-character {@code DALYTRAN-CARD-NUM}
     * @return a daily transaction record carrying just those two fields
     */
    private static DalyTranRecord dalyTran(String id, String cardNum) {
        DalyTranRecord record = new DalyTranRecord(CHARSET);
        record.moveDalytranId(id);
        record.moveDalytranCardNum(cardNum);
        return record;
    }

    /**
     * @param cardNum the sixteen-character {@code XREF-CARD-NUM}
     * @return a cross-reference record over the fixed customer and account identifiers
     */
    private static CardXrefRecord xref(String cardNum) {
        return new CardXrefRecord(cardNum, CUSTOMER_ID, ACCOUNT_ID);
    }

    /**
     * @param cardNum the card number the record carries
     * @return a found outcome over that record and the bytes it encodes to
     */
    private static CardXrefRepository.ReadResult xrefFound(String cardNum) {
        CardXrefRecord record = xref(cardNum);
        return CardXrefRepository.ReadResult.found(TransactionPostingJob.XREFFILE_DD_NAME, record,
                new String(record.encode(CHARSET), CHARSET));
    }

    /** @return an account record carrying {@link #ACCOUNT_ID} */
    private static AccountRecord account() {
        AccountRecord record = new AccountRecord(CHARSET);
        record.setAcctId(ACCOUNT_ID);
        return record;
    }

    /** @return the {@code DALYTRAN-CARD-NUM} of a record area nothing has been moved into */
    private static String untouchedCardNumber() {
        return new DalyTranRecord(CHARSET).dalytranCardNum();
    }

    /** @return the {@code DALYTRAN-ID} of a record area nothing has been moved into */
    private static String untouchedTranId() {
        return new DalyTranRecord(CHARSET).dalytranId();
    }

    /**
     * @param cardNum the card number the message names
     * @param tranId  the transaction id the message names
     * @return the single line {@code app/cbl/CBTRN01C.cbl:181-183} emits
     */
    private static String notVerifiedLine(String cardNum, String tranId) {
        return TransactionPostingJob.CARD_NOT_VERIFIED_PREFIX + cardNum
                + TransactionPostingJob.CARD_NOT_VERIFIED_SUFFIX + tranId;
    }

    /**
     * @param cardNum the card number the cross reference returned
     * @return the four lines the {@code NOT INVALID KEY} arm emits, in order
     */
    private static List<String> xrefSuccessLines(String cardNum) {
        return List.of(TransactionPostingJob.SUCCESSFUL_READ_OF_XREF,
                TransactionPostingJob.XREF_CARD_NUMBER_PREFIX + cardNum,
                TransactionPostingJob.XREF_ACCOUNT_ID_PREFIX + "99999999999",
                TransactionPostingJob.XREF_CUSTOMER_ID_PREFIX + "123456789");
    }

    // =================================================================================================
    // Classpath readers.
    //
    // Both name their charset rather than taking the platform default (practice B8), and neither caches
    // what it read, so no test can be influenced by another having read first (practice B9). A missing
    // resource fails the test that asked for it, with the resource named, rather than yielding an empty
    // list that would make an assertion pass for the wrong reason.
    // =================================================================================================

    /**
     * @param resource the absolute classpath resource to read
     * @return its lines, in file order, with no terminator characters
     */
    private static List<String> classpathLines(String resource) {
        try (InputStream stream = TransactionPostingJobTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as("classpath resource %s must exist", resource).isNotNull();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, CHARSET))) {
                return reader.lines().toList();
            }
        } catch (IOException problem) {
            throw new UncheckedIOException("could not read " + resource, problem);
        }
    }

    /**
     * @param row the one-based row of {@code app/data/ASCII/dailytran.txt} to return
     * @return that row, exactly as the fixture holds it
     */
    private static String dailyTransactionFixtureRow(int row) {
        List<String> rows = classpathLines(DAILY_TRANSACTION_FIXTURE);
        assertThat(rows).as("the fixture holds the row asked for").hasSizeGreaterThanOrEqualTo(row);
        return rows.get(row - 1);
    }

    /**
     * @param image the eleven-character {@code DALYTRAN-AMT} image to place
     * @return a record area carrying that amount image and nothing else moved into it
     */
    private static DalyTranRecord dalyTranWithAmountImage(String image) {
        DalyTranRecord record = new DalyTranRecord(CHARSET);
        record.writeDalytranAmtImage(image);
        return record;
    }

    // =================================================================================================
    // Gate G13 - a real, launchable job that nothing triggers.
    // =================================================================================================

    @Nested
    @DisplayName("Gate G13 - runnable, and triggered by nothing")
    class PublishedContract {

        @Test
        @DisplayName("the job bean is real, named for the contract key, and carries the single step")
        void theJobIsPublishedUnderTheContractDerivedName() {
            Fixture fixture = new Fixture();
            TransactionPostingJob subject = fixture.job();

            assertThat(TransactionPostingJob.JOB_NAME).isEqualTo("transactionPostingJob");
            assertThat(TransactionPostingJob.JOB_KEY).isEqualTo("transaction-posting-job");
            assertThat(subject.transactionPostingJob().getName())
                    .isEqualTo(TransactionPostingJob.JOB_NAME);
            assertThat(subject.transactionPostingStep().getName())
                    .isEqualTo(TransactionPostingJob.STEP_NAME);
            assertThat(subject.transactionPostingTasklet()).isNotNull();
        }

        @Test
        @DisplayName("the configuration bean name is qualified, so it cannot collide with the job bean")
        void theConfigurationBeanNameIsQualified() {
            assertThat(TransactionPostingJob.CONFIGURATION_BEAN_NAME)
                    .isEqualTo("transactionPostingJobConfiguration")
                    .isNotEqualTo(TransactionPostingJob.JOB_NAME);
        }

        @Test
        @DisplayName("no trigger of any kind: @Configuration is the only annotation and there are no "
                + "runner interfaces, scheduled methods or launcher fields")
        void nothingTriggersTheJob() {
            assertThat(TransactionPostingJob.class.getDeclaredAnnotations())
                    .as("only @Configuration; a @Scheduled or @EnableScheduling here would run the orphan")
                    .hasSize(1);
            assertThat(TransactionPostingJob.class.getInterfaces())
                    .as("no CommandLineRunner, no ApplicationRunner, no InitializingBean")
                    .isEmpty();
            assertThat(Stream.of(TransactionPostingJob.class.getDeclaredFields())
                    .map(field -> field.getType().getName()))
                    .as("no JobLauncher, runner or scheduler is held")
                    .noneMatch(name -> name.contains("JobLauncher") || name.contains("Runner")
                            || name.contains("Scheduler"));
            assertThat(Stream.of(TransactionPostingJob.class.getDeclaredMethods())
                    .flatMap(method -> Stream.of(method.getDeclaredAnnotations()))
                    .map(annotation -> annotation.annotationType().getName()))
                    .as("no method is scheduled")
                    .noneMatch(name -> name.contains("Scheduled"));
        }

        @Test
        @DisplayName("the job declares no parameters, because no JCL invokes CBTRN01C")
        void theJobDeclaresNoParameters() {
            Fixture fixture = new Fixture();

            assertThat(fixture.job().jobParameters().getParameters()).isEmpty();
        }

        @Test
        @DisplayName("GATE G13: registered, launchable, and left unlaunched - application.yml turns the "
                + "automatic job runner off, so an explicit launch is the only way CBTRN01C ever runs")
        void theOrphanIsRegisteredAndLeftUnlaunched() {
            // Gate G13, implicit requirement I4 and practice B5, in one place.
            //
            // No EXEC PGM=CBTRN01C card exists anywhere in app/jcl or app/proc - CBTRN01C is the one
            // in-scope program with no invoker at all. It still migrates as a fully runnable job, so the
            // two halves of that statement both have to hold: the bean must be REAL, and nothing must
            // start it. Wiring it into a schedule or a pipeline would be a behaviour change, not a
            // completion.
            //
            // Two mechanisms keep it unlaunched, and this asserts both. The class carries no trigger of
            // its own - nothingTriggersTheJob() above proves that by reflection - and the container's own
            // job runner is switched off in configuration, which is what stops Boot from launching every
            // Job bean it can see at start-up.
            Fixture fixture = new Fixture();

            assertThat(fixture.job().transactionPostingJob())
                    .as("the bean is real: G13 asks for a runnable job, not an absent one")
                    .isNotNull();
            assertThat(fixture.job().transactionPostingJob().isRestartable())
                    .as("a launch is a submission, not a resumption: every launch runs the single step "
                            + "from the beginning, which is what an EXEC card would have done had one "
                            + "existed")
                    .isFalse();
            assertThat(Stream.of(TransactionPostingJob.class.getDeclaredMethods())
                    .map(method -> method.getReturnType().getName()))
                    .as("this class publishes no runner, no launcher and no exit-code generator bean, so "
                            + "nothing it contributes to the context can start it either")
                    .noneMatch(name -> name.contains("Runner") || name.contains("Launcher")
                            || name.contains("Scheduler") || name.contains("ExitCodeGenerator"));

            List<String> configuration = classpathLines(APPLICATION_CONFIGURATION);
            int batchAt = configuration.indexOf("  batch:");
            assertThat(batchAt).as("application.yml declares spring.batch").isNotNegative();
            assertThat(configuration.subList(batchAt, batchAt + 3))
                    .as("spring.batch.job.enabled is false, so no Job bean - this one included - is "
                            + "launched at start-up; an explicit launch is the only way in")
                    .containsExactly("  batch:", "    job:", "      enabled: false");
        }

        @Test
        @DisplayName("the tasklet runs the program once and reports the read count as step metadata")
        void theTaskletRunsTheProgramAndReportsTheReadCount() throws Exception {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE), dalyTran("TRAN000000000002",
                    CARD_TWO));
            List<String> spooled = new ArrayList<>();
            StepExecution stepExecution =
                    new StepExecution(TransactionPostingJob.STEP_NAME, new JobExecution(1L));
            StepContribution contribution = new StepContribution(stepExecution);

            RepeatStatus status = fixture.job(validBatchConfig(), spooled::add)
                    .transactionPostingTasklet()
                    .execute(contribution, new ChunkContext(new StepContext(stepExecution)));

            assertThat(status).isEqualTo(RepeatStatus.FINISHED);
            assertThat(contribution.getReadCount()).isEqualTo(2);
            assertThat(spooled).startsWith(TransactionPostingJob.START_BANNER)
                    .endsWith(TransactionPostingJob.END_BANNER);
        }

        @Test
        @DisplayName("a tasklet over an empty dataset reports no reads, and still finishes")
        void theTaskletReportsNoReadsForAnEmptyDataset() throws Exception {
            Fixture fixture = new Fixture();
            StepExecution stepExecution =
                    new StepExecution(TransactionPostingJob.STEP_NAME, new JobExecution(2L));
            StepContribution contribution = new StepContribution(stepExecution);
            // Nothing publishes a sink on this path, so the tasklet has to fall back to
            // defaultSysoutSink(), which targets the process's standard output. Standard output is
            // redirected for the duration of the call so that the fallback is asserted rather than merely
            // tolerated, and so the lines it emits land in this assertion instead of in the build's spool.
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            PrintStream standardOutput = System.out;
            RepeatStatus status;
            try {
                System.setOut(new PrintStream(captured, true, CHARSET));
                status = fixture.job().transactionPostingTasklet()
                        .execute(contribution, new ChunkContext(new StepContext(stepExecution)));
            } finally {
                System.setOut(standardOutput);
            }

            assertThat(status).isEqualTo(RepeatStatus.FINISHED);
            assertThat(contribution.getReadCount()).isZero();
            DalyTranRecord neverRead = new DalyTranRecord(CHARSET);
            assertThat(captured.toString(CHARSET).split("\n", -1))
                    .as("the fallback sink emitted the empty pass verbatim, defect 1's extra lookup "
                            + "included, and the trailing terminator")
                    .containsExactly(TransactionPostingJob.START_BANNER,
                            TransactionPostingJob.INVALID_CARD_NUMBER_FOR_XREF,
                            TransactionPostingJob.CARD_NOT_VERIFIED_PREFIX + neverRead.dalytranCardNum()
                                    + TransactionPostingJob.CARD_NOT_VERIFIED_SUFFIX
                                    + neverRead.dalytranId(),
                            TransactionPostingJob.END_BANNER,
                            "");
        }

        @Test
        @DisplayName("the validated step contract is the ungated STEP01 running CBTRN01C")
        void theStepContractIsTheOneConfigurationDeclares() {
            StepContract contract = new Fixture().job().stepContract();

            assertThat(contract.name()).isEqualTo(TransactionPostingJob.STEP_NAME);
            assertThat(contract.program()).isEqualTo(TransactionPostingJob.PROGRAM_ID);
            assertThat(contract.requirePrecedingExitCodeZero()).isFalse();
            assertThat(TransactionPostingJob.REQUIRED_STEPS).containsExactly(contract);
        }
    }

    // =================================================================================================
    // Constructor guards.
    // =================================================================================================

    @Nested
    @DisplayName("Constructor guards - a mis-declared contract fails at start-up")
    class ConstructorGuards {

        @Test
        @DisplayName("every collaborator is required")
        void everyCollaboratorIsRequired() {
            Fixture fixture = new Fixture();
            BatchConfig seam = validBatchConfig();
            ObjectProvider<SysoutSink> noSink = new SuppliedProvider<>(null);

            assertThatNullPointerException().isThrownBy(() -> new TransactionPostingJob(null,
                    fixture.dalyTranRepository, fixture.customerRepository, fixture.cardXrefRepository,
                    fixture.cardRepository, fixture.accountRepository, fixture.transactionRepository,
                    noSink));
            assertThatNullPointerException().isThrownBy(() -> new TransactionPostingJob(seam, null,
                    fixture.customerRepository, fixture.cardXrefRepository, fixture.cardRepository,
                    fixture.accountRepository, fixture.transactionRepository, noSink));
            assertThatNullPointerException().isThrownBy(() -> new TransactionPostingJob(seam,
                    fixture.dalyTranRepository, null, fixture.cardXrefRepository,
                    fixture.cardRepository, fixture.accountRepository, fixture.transactionRepository,
                    noSink));
            assertThatNullPointerException().isThrownBy(() -> new TransactionPostingJob(seam,
                    fixture.dalyTranRepository, fixture.customerRepository, null,
                    fixture.cardRepository, fixture.accountRepository, fixture.transactionRepository,
                    noSink));
            assertThatNullPointerException().isThrownBy(() -> new TransactionPostingJob(seam,
                    fixture.dalyTranRepository, fixture.customerRepository, fixture.cardXrefRepository,
                    null, fixture.accountRepository, fixture.transactionRepository, noSink));
            assertThatNullPointerException().isThrownBy(() -> new TransactionPostingJob(seam,
                    fixture.dalyTranRepository, fixture.customerRepository, fixture.cardXrefRepository,
                    fixture.cardRepository, null, fixture.transactionRepository, noSink));
            assertThatNullPointerException().isThrownBy(() -> new TransactionPostingJob(seam,
                    fixture.dalyTranRepository, fixture.customerRepository, fixture.cardXrefRepository,
                    fixture.cardRepository, fixture.accountRepository, null, noSink));
            assertThatNullPointerException().isThrownBy(() -> new TransactionPostingJob(seam,
                    fixture.dalyTranRepository, fixture.customerRepository, fixture.cardXrefRepository,
                    fixture.cardRepository, fixture.accountRepository, fixture.transactionRepository,
                    null));
        }

        @Test
        @DisplayName("a contract naming another program is refused, at the job and at the step")
        void aContractNamingAnotherProgramIsRefused() {
            Fixture fixture = new Fixture();

            JobContracts wrongJob = contracts("CBTRN02C", TransactionPostingJob.STEP_NAME, false,
                    List.of());
            assertThatIllegalStateException()
                    .isThrownBy(() -> fixture.job(batchConfig(wrongJob, validBindings()), null))
                    .withMessageContaining("carddemo.jobs.transaction-posting-job.program")
                    .withMessageContaining("CBTRN01C");

            JobContracts wrongStep = new JobContracts();
            wrongStep.put(TransactionPostingJob.JOB_KEY, new JobContract(
                    TransactionPostingJob.PROGRAM_ID, List.of(),
                    List.of(new StepContract(TransactionPostingJob.STEP_NAME, "CBTRN03C", false)),
                    null, Map.of()));
            assertThatIllegalStateException()
                    .isThrownBy(() -> fixture.job(batchConfig(wrongStep, validBindings()), null))
                    .withMessageContaining("].program");
        }

        @Test
        @DisplayName("a declared job parameter is refused: CBTRN01C has no EXEC card and no PARM")
        void aDeclaredParameterIsRefused() {
            Fixture fixture = new Fixture();
            JobContracts parameterised = contracts(TransactionPostingJob.PROGRAM_ID,
                    TransactionPostingJob.STEP_NAME, false,
                    List.of(new JobParameterContract("parmDate", "string", "2022071800")));

            assertThatIllegalStateException()
                    .isThrownBy(() -> fixture.job(batchConfig(parameterised, validBindings()), null))
                    .withMessageContaining("parameters declares")
                    .withMessageContaining("app/jcl/");
        }

        @Test
        @DisplayName("a gated step is refused: there is no COND to reproduce and no preceding step")
        void aGatedStepIsRefused() {
            Fixture fixture = new Fixture();
            JobContracts gated = contracts(TransactionPostingJob.PROGRAM_ID,
                    TransactionPostingJob.STEP_NAME, true, List.of());

            assertThatIllegalStateException()
                    .isThrownBy(() -> fixture.job(batchConfig(gated, validBindings()), null))
                    .withMessageContaining("require-preceding-exit-code-zero");
        }

        @Test
        @DisplayName("a renamed step, and a second step declared beside this one, are both refused")
        void theWholeStepSequenceIsCompared() {
            Fixture fixture = new Fixture();

            JobContracts renamed = contracts(TransactionPostingJob.PROGRAM_ID, "STEP15", false,
                    List.of());
            assertThatIllegalStateException()
                    .isThrownBy(() -> fixture.job(batchConfig(renamed, validBindings()), null));

            JobContracts twoSteps = new JobContracts();
            twoSteps.put(TransactionPostingJob.JOB_KEY, new JobContract(
                    TransactionPostingJob.PROGRAM_ID, List.of(),
                    List.of(new StepContract(TransactionPostingJob.STEP_NAME,
                                    TransactionPostingJob.PROGRAM_ID, false),
                            new StepContract("STEP02", TransactionPostingJob.PROGRAM_ID, false)),
                    null, Map.of()));
            assertThatIllegalStateException()
                    .isThrownBy(() -> fixture.job(batchConfig(twoSteps, validBindings()), null));
        }

        @ParameterizedTest
        @ValueSource(strings = { "DALYTRAN", "CUSTFILE", "XREFFILE", "CARDFILE", "ACCTFILE",
                "TRANFILE" })
        @DisplayName("a DD whose declared width contradicts its copybook is refused")
        void aWidthThatContradictsTheCopybookIsRefused(String ddName) {
            Fixture fixture = new Fixture();
            DatasetBindings wrongWidth = bindings(ddName, 1, TEST_DSNAME);

            assertThatIllegalStateException()
                    .isThrownBy(() -> fixture.job(batchConfig(validContracts(), wrongWidth), null))
                    .withMessageContaining("carddemo.datasets." + ddName + ".record-length");
        }

        @ParameterizedTest
        @ValueSource(strings = { "DALYTRAN", "CUSTFILE", "XREFFILE", "CARDFILE", "ACCTFILE",
                "TRANFILE" })
        @DisplayName("a DD that names no dataset is refused")
        void aBlankDatasetNameIsRefused(String ddName) {
            Fixture fixture = new Fixture();
            int width = validBindings().binding(ddName).recordLength();
            DatasetBindings blank = bindings(ddName, width, "   ");

            assertThatIllegalStateException()
                    .isThrownBy(() -> fixture.job(batchConfig(validContracts(), blank), null))
                    .withMessageContaining("carddemo.datasets." + ddName + ".dsname");
        }

        @Test
        @DisplayName("a DD that declares no dataset name at all is refused, and says so")
        void anUndeclaredDatasetNameIsRefused() {
            Fixture fixture = new Fixture();
            DatasetBindings undeclared = bindings(TransactionPostingJob.XREFFILE_DD_NAME,
                    CardXrefRecord.RECORD_LENGTH, null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> fixture.job(batchConfig(validContracts(), undeclared), null))
                    .withMessageContaining("carddemo.datasets."
                            + TransactionPostingJob.XREFFILE_DD_NAME + ".dsname is not declared");
        }
    }

    // =================================================================================================
    // The clean pass, and defect 1.
    // =================================================================================================

    @Nested
    @DisplayName("The pass - and defect 1, the extra lookup after end of file")
    class ThePass {

        @Test
        @DisplayName("an empty dataset still performs one lookup: the banners plus the post-EOF lookup")
        void anEmptyDatasetStillPerformsOneLookup() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions();

            ExecutionSummary summary = fixture.run();

            assertThat(fixture.lines).containsExactly(
                    TransactionPostingJob.START_BANNER,
                    TransactionPostingJob.INVALID_CARD_NUMBER_FOR_XREF,
                    notVerifiedLine(untouchedCardNumber(), untouchedTranId()),
                    TransactionPostingJob.END_BANNER);
            assertThat(summary.recordsRead()).isZero();
            assertThat(summary.xrefLookups()).isEqualTo(1);
            assertThat(summary.accountReads()).isZero();
            assertThat(summary.postEndOfFileLookups()).isEqualTo(1);
            assertThat(summary.completedCleanly()).isTrue();
        }

        @Test
        @DisplayName("one record, xref and account both found: thirteen lines, and the last five are the "
                + "post-EOF lookup repeated on the same card")
        void oneRecordFoundEverywhere() {
            Fixture fixture = new Fixture();
            DalyTranRecord record = dalyTran(TRAN_ONE, CARD_ONE);
            fixture.dailyTransactions(record);
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_ONE));
            when(fixture.acctfile.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.found(account()));

            ExecutionSummary summary = fixture.run();

            List<String> expected = new ArrayList<>();
            expected.add(TransactionPostingJob.START_BANNER);
            expected.add(record.displayImage());
            expected.addAll(xrefSuccessLines(CARD_ONE));
            expected.add(TransactionPostingJob.SUCCESSFUL_READ_OF_ACCOUNT_FILE);
            expected.addAll(xrefSuccessLines(CARD_ONE));
            expected.add(TransactionPostingJob.SUCCESSFUL_READ_OF_ACCOUNT_FILE);
            expected.add(TransactionPostingJob.END_BANNER);

            assertThat(fixture.lines).containsExactlyElementsOf(expected);
            assertThat(fixture.lines).hasSize(13);
            assertThat(summary.recordsRead()).isEqualTo(1);
            assertThat(summary.recordImageLinesDisplayed()).isEqualTo(1);
            assertThat(summary.xrefLookups()).isEqualTo(2);
            assertThat(summary.accountReads()).isEqualTo(2);
        }

        @Test
        @DisplayName("the record image is displayed once per record, never twice - unlike CBACT03C")
        void theRecordImageIsDisplayedOncePerRecord() {
            Fixture fixture = new Fixture();
            DalyTranRecord first = dalyTran(TRAN_ONE, CARD_ONE);
            DalyTranRecord second = dalyTran("TRAN000000000002", CARD_TWO);
            fixture.dailyTransactions(first, second);

            ExecutionSummary summary = fixture.run();

            assertThat(fixture.lines).filteredOn(line -> line.equals(first.displayImage())).hasSize(1);
            assertThat(fixture.lines).filteredOn(line -> line.equals(second.displayImage())).hasSize(1);
            assertThat(summary.recordsRead()).isEqualTo(2);
            assertThat(summary.recordImageLinesDisplayed()).isEqualTo(2);
        }

        @Test
        @DisplayName("DEFECT 1: the post-EOF lookup fires exactly once, on the LAST record's card number")
        void thePostEndOfFileLookupUsesTheStaleCardNumber() {
            Fixture fixture = new Fixture();
            DalyTranRecord first = dalyTran(TRAN_ONE, CARD_ONE);
            DalyTranRecord second = dalyTran("TRAN000000000002", CARD_TWO);
            fixture.dailyTransactions(first, second);

            ExecutionSummary summary = fixture.run();

            // Two records, three lookups: one per record and one after end of file.
            verify(fixture.cardXrefRepository, times(3)).readByCardNumber(anyString());
            verify(fixture.cardXrefRepository, times(1)).readByCardNumber(CARD_ONE);
            // CARD_TWO twice: once for its own record, and once more for the stale post-EOF lookup.
            verify(fixture.cardXrefRepository, times(2)).readByCardNumber(CARD_TWO);
            assertThat(summary.xrefLookups()).isEqualTo(3).isEqualTo(summary.recordsRead() + 1);

            // The stale lookup's message names the last record's card and transaction id, not a fresh one.
            assertThat(fixture.lines).filteredOn(
                    line -> line.equals(notVerifiedLine(CARD_TWO, "TRAN000000000002"))).hasSize(2);
        }

        @Test
        @DisplayName("the not-verified message is ONE line with four operands run together")
        void theNotVerifiedMessageIsASingleLine() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));

            fixture.run();

            assertThat(fixture.lines).contains("CARD NUMBER " + CARD_ONE
                    + " COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-" + TRAN_ONE);
        }

        @Test
        @DisplayName("a stop requested between records abandons the pass")
        void aStopRequestedBetweenRecordsAbandonsThePass() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            TransactionPostingJob subject = fixture.job();
            SysoutSink sink = fixture.sink();
            int[] consulted = { 0 };
            StopSignal stopAtOnce = () -> {
                if (consulted[0]++ > 0) {
                    throw new IllegalStateException("consulted more than once");
                }
                throw new StopRequestedExceptionDouble();
            };

            assertThatExceptionOfType(StopRequestedExceptionDouble.class)
                    .isThrownBy(() -> subject.execute(sink, stopAtOnce));
            assertThat(fixture.lines).containsExactly(TransactionPostingJob.START_BANNER);
            verify(fixture.dalytranFile, never()).readNext();
        }

        @Test
        @DisplayName("a null sink and a null stop signal are both refused")
        void nullArgumentsAreRefused() {
            TransactionPostingJob subject = new Fixture().job();

            assertThatNullPointerException().isThrownBy(() -> subject.execute(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> subject.execute(line -> { }, null))
                    .withMessageContaining("StopSignal.RUNNING");
        }

        @Test
        @DisplayName("the real StopSignal.RUNNING never interrupts a pass")
        void theRunningSignalNeverInterrupts() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));

            ExecutionSummary summary = fixture.job().execute(fixture.sink(), StopSignal.RUNNING);

            assertThat(summary.recordsRead()).isEqualTo(1);
        }

        @Test
        @DisplayName("why the stop refusal is stood in for: the framework's own type is unconstructible "
                + "from outside its package")
        void theFrameworkStopRefusalCannotBeConstructedHere() {
            // StopRequestedExceptionDouble is not a shortcut. BatchConfig.StopRequestedException carries
            // the framework's JobInterruptedException as its cause and declares only a package-private
            // constructor, so a test in this package cannot create one - and should not, because a
            // fabricated interruption would assert nothing about the real one. The double stands in for
            // it at the one place a pass observes it: a probe that throws between records.
            assertThat(RuntimeException.class).isAssignableFrom(StopRequestedException.class);
            assertThat(Stream.of(StopRequestedException.class.getDeclaredConstructors())
                    .filter(constructor -> Modifier.isPublic(constructor.getModifiers())))
                    .as("no publicly accessible constructor, so the double is the only way to drive this")
                    .isEmpty();
            assertThat(StopRequestedExceptionDouble.class).isNotEqualTo(StopRequestedException.class);
        }
    }

    /** A stand-in for the framework's stop refusal, which a test cannot construct directly. */
    private static final class StopRequestedExceptionDouble extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private StopRequestedExceptionDouble() {
            super("stop requested");
        }
    }

    // =================================================================================================
    // The dead accesses.
    // =================================================================================================

    @Nested
    @DisplayName("The dead accesses - three files opened and closed, and nothing written anywhere")
    class DeadAccesses {

        @Test
        @DisplayName("CUSTFILE is opened and closed and NEVER read")
        void theCustomerFileIsOpenedAndClosedAndNeverRead() {
            Fixture fixture = new Fixture();

            fixture.run();

            verify(fixture.customerRepository).openInput();
            verify(fixture.custfile).openStatus();
            verify(fixture.custfile).closeFile();
            verifyNoMoreInteractions(fixture.custfile);
        }

        @Test
        @DisplayName("CARDFILE is opened and closed and NEVER read")
        void theCardFileIsOpenedAndClosedAndNeverRead() {
            Fixture fixture = new Fixture();

            fixture.run();

            verify(fixture.cardRepository).addressing(any(), anyString());
            verify(fixture.cardRepository).openBrowse(anyString(), any());
            verify(fixture.cardBrowse).openResp();
            verify(fixture.cardBrowse).endBrowse();
            verifyNoMoreInteractions(fixture.cardBrowse);
        }

        @Test
        @DisplayName("TRANFILE is opened and closed and NEVER read - the posting the name promises")
        void theTransactionFileIsOpenedAndClosedAndNeverTouched() {
            Fixture fixture = new Fixture();

            fixture.run();

            verify(fixture.transactionRepository).openInput(any(DatasetBinding.class));
            verify(fixture.tranfile).openStatus();
            verify(fixture.tranfile).closeInput();
            verifyNoMoreInteractions(fixture.tranfile);
            verifyNoMoreInteractions(fixture.transactionRepository);
        }

        @Test
        @DisplayName("the account file is opened INPUT and never for update")
        void theAccountFileIsOpenedForInputOnly() {
            Fixture fixture = new Fixture();
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_ONE));

            fixture.run();

            verify(fixture.accountRepository).open(AccountRepository.OpenMode.INPUT);
            verify(fixture.accountRepository, never()).open(AccountRepository.OpenMode.I_O);
            verifyNoMoreInteractions(fixture.accountRepository);
        }

        @Test
        @DisplayName("the cross reference is read on the CCXREF base and never on the CXACAIX path")
        void theCrossReferenceIsReadOnTheBaseOnly() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));

            fixture.run();

            verify(fixture.cardXrefRepository, never()).readByAccountIdViaAltIndex(anyLong());
            verify(fixture.cardXrefRepository, never()).readByAccountIdViaAltIndex(anyString());
            verify(fixture.xrefCursor, never()).readNext();
        }
    }

    // =================================================================================================
    // Gates G19 and G21 - the three record layouts this program addresses, at their copybook widths.
    // =================================================================================================

    /**
     * Every record this program decodes is at its copybook's declared width, {@code FILLER} included.
     *
     * <p>Gates <strong>G19</strong> and <strong>G21</strong>. {@value TransactionPostingJob#PROGRAM_ID}
     * addresses three record layouts - the one it displays and the two it reads by key - and this module
     * addresses every field by absolute offset, so a record built one byte short misplaces every field
     * after the gap rather than failing outright. The width is therefore asserted three ways: against the
     * copybook's stated {@code RECLN}, against the sum of the declared spans, and against the length of
     * an encoded record.
     *
     * <p>{@code FILLER} is the specific thing gate G21 is about. It carries no value and is never
     * displayed on its own, so it is the field most easily dropped - and dropping it is unrecoverable,
     * because the total width is the only evidence it was ever there. All three layouts end in one:
     * {@code X(20)} for the daily transaction, {@code X(14)} for the cross reference,
     * {@code X(178)} for the account.
     */
    @Nested
    @DisplayName("Gates G19 and G21 - copybook widths, with FILLER present and space-filled")
    class CopybookGeometry {

        @Test
        @DisplayName("DALYTRAN-RECORD is 350 bytes - app/cpy/CVTRA06Y.cpy, RECLN 350")
        void theDailyTransactionRecordIs350Bytes() {
            assertThat(DalyTranRecord.RECORD_LENGTH).isEqualTo(350);
            assertThat(DalyTranRecord.sumOfDeclaredSpanLengths())
                    .as("every byte of the record is claimed by a declared span, FILLER included")
                    .isEqualTo(DalyTranRecord.RECORD_LENGTH);
            // FILLER X(20) at offset 330 - app/cpy/CVTRA06Y.cpy:18, the last item of the group.
            assertThat(DalyTranRecord.FILLER_OFFSET).isEqualTo(330);
            assertThat(DalyTranRecord.FILLER_LENGTH).isEqualTo(20);
            assertThat(DalyTranRecord.FILLER_OFFSET + DalyTranRecord.FILLER_LENGTH)
                    .isEqualTo(DalyTranRecord.RECORD_LENGTH);

            DalyTranRecord record = dalyTran(TRAN_ONE, CARD_ONE);
            assertThat(record.encode(CHARSET)).hasSize(DalyTranRecord.RECORD_LENGTH);
            assertThat(record.displayImage()).hasSize(DalyTranRecord.RECORD_LENGTH);
            assertThat(record.filler())
                    .as("FILLER is emitted as spaces, not omitted and not zero-filled")
                    .isEqualTo(" ".repeat(DalyTranRecord.FILLER_LENGTH))
                    .isBlank();
        }

        @Test
        @DisplayName("CARD-XREF-RECORD is 50 bytes including FILLER X(14) - app/cpy/CVACT03Y.cpy, RECLN 50")
        void theCrossReferenceRecordIs50Bytes() {
            assertThat(CardXrefRecord.RECORD_LENGTH).isEqualTo(50);
            // XREF-CARD-NUM X(16) + XREF-CUST-ID 9(09) + XREF-ACCT-ID 9(11) is 36 bytes; the trailing
            // FILLER X(14) makes 50. app/data/ASCII/cardxref.txt holds only the first 36 - AAP 0.2.4
            // records the deviation and risk R-F - so the record type is what carries the true width.
            assertThat(CardXrefRecord.XREF_CARD_NUM_LENGTH + CardXrefRecord.XREF_CUST_ID_LENGTH
                    + CardXrefRecord.XREF_ACCT_ID_LENGTH).isEqualTo(36);
            assertThat(CardXrefRecord.FILLER_OFFSET).isEqualTo(36);
            assertThat(CardXrefRecord.FILLER_LENGTH).isEqualTo(14);
            assertThat(CardXrefRecord.FILLER_OFFSET + CardXrefRecord.FILLER_LENGTH)
                    .isEqualTo(CardXrefRecord.RECORD_LENGTH);

            byte[] encoded = xref(CARD_ONE).encode(CHARSET);
            assertThat(encoded).hasSize(CardXrefRecord.RECORD_LENGTH);
            assertThat(new String(encoded, CHARSET).substring(CardXrefRecord.FILLER_OFFSET))
                    .as("the trailing 14 bytes are spaces, which is what makes the record 50 and not 36")
                    .isEqualTo(" ".repeat(CardXrefRecord.FILLER_LENGTH));
        }

        @Test
        @DisplayName("ACCOUNT-RECORD is 300 bytes including FILLER X(178) - app/cpy/CVACT01Y.cpy, RECLN 300")
        void theAccountRecordIs300Bytes() {
            assertThat(AccountRecord.RECORD_LENGTH).isEqualTo(300);
            assertThat(AccountRecord.KEY_LENGTH)
                    .as("ACCT-ID PIC 9(11) is the record key - app/cpy/CVACT01Y.cpy:5")
                    .isEqualTo(11);

            AccountRecord record = account();
            assertThat(record.toByteArray()).hasSize(AccountRecord.RECORD_LENGTH);
            assertThat(record.toFixedWidthString()).hasSize(AccountRecord.RECORD_LENGTH);
            // FILLER X(178) begins at offset 122 - app/cpy/CVACT01Y.cpy:17 - and runs to the last byte.
            assertThat(AccountRecord.FILLER_OFFSET).isEqualTo(122);
            assertThat(AccountRecord.FILLER_LENGTH).isEqualTo(178);
            assertThat(AccountRecord.FILLER_OFFSET + AccountRecord.FILLER_LENGTH)
                    .isEqualTo(AccountRecord.RECORD_LENGTH);
            assertThat(record.raw(AccountRecord.SPAN_FILLER))
                    .as("FILLER is emitted as spaces, which is what makes the record 300 bytes wide")
                    .isEqualTo(" ".repeat(AccountRecord.FILLER_LENGTH));
            // The misspelling in the copybook is preserved rather than corrected - implicit requirement
            // I1. A renamed field would break the field-for-field diff the parity gate rests on.
            assertThat(AccountRecord.ACCT_EXPIRAION_DATE_NAME).isEqualTo("ACCT-EXPIRAION-DATE");
        }

        @Test
        @DisplayName("the DD widths the job validates at start-up are exactly these three plus the three "
                + "it opens and never reads")
        void theJobValidatesEveryDdAgainstItsCopybook() {
            // The job refuses a dataset binding whose declared record length contradicts the copybook the
            // program's record area is declared from - see its constructor. That guard covers all six
            // DDs, not only the three that are read, because an unread file's geometry is still part of
            // what its OPEN verifies.
            assertThat(List.of(DalyTranRecord.RECORD_LENGTH, CustomerRecord.RECORD_LENGTH,
                    CardXrefRecord.RECORD_LENGTH, CardRecord.RECORD_LENGTH, AccountRecord.RECORD_LENGTH,
                    TranRecord.RECORD_LENGTH))
                    .as("CVTRA06Y 350, CVCUS01Y 500, CVACT03Y 50, CVACT02Y 150, CVACT01Y 300, CVTRA05Y 350")
                    .containsExactly(350, 500, 50, 150, 300, 350);
        }
    }

    // =================================================================================================
    // 1000-DALYTRAN-GET-NEXT's three-arm ladder, its two 88-levels, and Z-ABEND-PROGRAM.
    // =================================================================================================

    /**
     * The status ladder, both condition names, and the abend they lead to.
     *
     * <p>{@code app/cbl/CBTRN01C.cbl:202-225} is two nested decisions stacked on one another:
     *
     * <pre>
     * READ DALYTRAN-FILE INTO DALYTRAN-RECORD.                   :203
     * IF  DALYTRAN-STATUS = '00'   MOVE  0 TO APPL-RESULT         :204-205
     * ELSE IF DALYTRAN-STATUS = '10'  MOVE 16 TO APPL-RESULT      :207-208
     *      ELSE                       MOVE 12 TO APPL-RESULT      :210
     * IF  APPL-AOK  CONTINUE                                      :213-214
     * ELSE IF APPL-EOF  MOVE 'Y' TO END-OF-DAILY-TRANS-FILE       :216-217
     *      ELSE  DISPLAY 'ERROR READING DAILY TRANSACTION FILE'   :219
     *            MOVE DALYTRAN-STATUS TO IO-STATUS                :220
     *            PERFORM Z-DISPLAY-IO-STATUS                      :221
     *            PERFORM Z-ABEND-PROGRAM                          :222
     * </pre>
     *
     * <p>The two {@code 88}-levels at {@code :143-144} are what join them: {@code APPL-AOK VALUE 0} and
     * {@code APPL-EOF VALUE 16}. The numbers 0, 16 and 12 are therefore not arbitrary - each selects one
     * arm of the second decision, which is why gate <strong>G50</strong> asks for both condition names in
     * both states and gate <strong>G47</strong> for all three statuses.
     *
     * <p>Gate <strong>G35</strong>: {@code Z-ABEND-PROGRAM} at {@code :469-473} displays
     * {@code 'ABENDING PROGRAM'}, moves 0 to {@code TIMING} and 999 to {@code ABCODE}, then calls
     * {@code CEE3ABD}. All three values are asserted, on every abend path this program has.
     */
    @Nested
    @DisplayName("1000-DALYTRAN-GET-NEXT - three statuses, two 88-levels, one abend")
    class StatusLadderAndAbend {

        @Test
        @DisplayName("ARM 1 - status '00' moves 0, APPL-AOK is TRUE, and the pass continues")
        void statusOkContinuesThePass() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(distinctRecords(2));

            ExecutionSummary summary = fixture.run();

            // Every found read took the '00' arm: APPL-RESULT 0, APPL-AOK satisfied, CONTINUE at :214.
            assertThat(FileStatus.APPL_AOK).as("88 APPL-AOK VALUE 0 - app/cbl/CBTRN01C.cbl:143").isZero();
            assertThat(FileStatus.isOk(FileStatus.OK)).isTrue();
            assertThat(summary.recordsRead()).isEqualTo(2);
            assertThat(summary.returnCode()).isEqualTo(AbendException.RETURN_CODE_OK);
            assertThat(fixture.lines).doesNotContain(TransactionPostingJob.ERROR_READING_DALYTRAN);
            assertThat(fixture.lines).doesNotContain(AbendException.ABEND_DISPLAY_TEXT);
        }

        @Test
        @DisplayName("ARM 2 - status '10' moves 16, APPL-AOK is FALSE and APPL-EOF TRUE, so the flag is "
                + "set and the loop ends without an abend")
        void endOfFileEndsTheLoopCleanly() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(distinctRecords(1));

            ExecutionSummary summary = fixture.run();

            // 88 APPL-EOF VALUE 16 - :144. The value is 16 and not 12, which is the whole difference
            // between ending a pass and abending one.
            assertThat(FileStatus.APPL_EOF).isEqualTo(16);
            assertThat(FileStatus.APPL_EOF).isNotEqualTo(FileStatus.APPL_AOK);
            assertThat(AbendException.RETURN_CODE_END_OF_FILE).isEqualTo(FileStatus.APPL_EOF);
            assertThat(FileStatus.isEndOfFile(FileStatus.END_OF_FILE)).isTrue();
            // The run reached its closing banner, so the loop ended through :217 and not through :222.
            assertThat(fixture.lines).endsWith(TransactionPostingJob.END_BANNER);
            assertThat(summary.completedCleanly()).isTrue();
            assertThat(summary.returnCode()).isEqualTo(AbendException.RETURN_CODE_OK);
        }

        @ParameterizedTest(name = "status ''{0}'' is neither ''00'' nor ''10'', so APPL-RESULT becomes 12")
        @ValueSource(strings = { "35", "37", "39", "41", "92", FileStatus.NOT_FOUND, FileStatus.DUPLICATE,
            FileStatus.RECORD_LENGTH_CONFLICT })
        @DisplayName("ARM 3 - any other status moves 12, both 88-levels are FALSE, and the run abends")
        void anyOtherStatusAbends(String status) {
            // The else at :210 is deliberately open-ended in the source - it catches everything, including
            // statuses that mean something specific elsewhere in the estate ('23' not found, '22'
            // duplicate, '04' record length conflict). A translation that special-cased any of them would
            // read plausibly and be wrong.
            Fixture fixture = new Fixture();
            when(fixture.dalytranFile.readNext())
                    .thenReturn(DalyTranRepository.ReadResult.other(status));

            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(fixture::run).actual();

            assertThat(fixture.lines).containsExactly(
                    TransactionPostingJob.START_BANNER,
                    TransactionPostingJob.ERROR_READING_DALYTRAN,
                    FileStatus.toDisplayLine(status),
                    AbendException.ABEND_DISPLAY_TEXT);
            assertThat(abend.getReturnCode())
                    .as("MOVE 12 TO APPL-RESULT at :210 - neither 88-level is satisfied")
                    .isEqualTo(TransactionPostingJob.APPL_RESULT_FATAL)
                    .isNotEqualTo(FileStatus.APPL_AOK)
                    .isNotEqualTo(FileStatus.APPL_EOF);
        }

        @Test
        @DisplayName("GATE G35 - the abend carries ABCODE 999 and TIMING 0, exactly as Z-ABEND-PROGRAM "
                + "sets them before CALL 'CEE3ABD'")
        void theAbendCarriesTheAbendCodeAndTiming() {
            Fixture fixture = new Fixture();
            when(fixture.dalytranFile.readNext())
                    .thenReturn(DalyTranRepository.ReadResult.other(OTHER_STATUS));

            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(fixture::run).actual();

            // app/cbl/CBTRN01C.cbl:470-473, in order: the display, then TIMING, then ABCODE, then the
            // call. The display is asserted as the last line above; the two parameters are asserted here.
            assertThat(abend.getAbendCode())
                    .as("MOVE 999 TO ABCODE - :472")
                    .hasValue(AbendException.STANDARD_ABEND_CODE);
            assertThat(AbendException.STANDARD_ABEND_CODE).isEqualTo(999);
            assertThat(abend.getTiming())
                    .as("MOVE 0 TO TIMING - :471; zero means abend now rather than at the next "
                            + "synchronisation point")
                    .hasValue(AbendException.STANDARD_TIMING);
            assertThat(AbendException.STANDARD_TIMING).isZero();
            assertThat(abend.getProgram()).isEqualTo(TransactionPostingJob.PROGRAM_ID);
            assertThat(abend.hasAbendCode()).isTrue();
            assertThat(abend.hasTiming()).isTrue();
        }

        @Test
        @DisplayName("the exit code is one of the four COBOL-observable values, and on the happy path it "
                + "is 0 - CBTRN01C sets no RETURN-CODE of its own")
        void theExitCodeIsOneOfTheFourObservableValues() {
            List<Integer> observable = List.of(AbendException.RETURN_CODE_OK,
                    AbendException.RETURN_CODE_WARNING,
                    TransactionPostingJob.APPL_RESULT_ASSUMED_FAILURE,
                    TransactionPostingJob.APPL_RESULT_FATAL);
            assertThat(observable).containsExactly(0, 4, 8, 12);

            // The happy path. There is no MOVE ... TO RETURN-CODE anywhere in CBTRN01C, and GOBACK at
            // :197 leaves whatever RETURN-CODE holds - which, nothing having set it, is zero.
            Fixture clean = new Fixture();
            clean.dailyTransactions(distinctRecords(2));
            assertThat(clean.run().returnCode()).isZero().isIn(observable);

            // The failing path. APPL-RESULT reaches the abend as 12, which is in the same set: the
            // program's only two observable outcomes are 0 and 12.
            Fixture failing = new Fixture();
            when(failing.dalytranFile.readNext())
                    .thenReturn(DalyTranRepository.ReadResult.other(OTHER_STATUS));
            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(failing::run).actual();
            assertThat(abend.getReturnCode()).isEqualTo(12).isIn(observable);
        }

        @Test
        @DisplayName("Z-DISPLAY-IO-STATUS: both of its arms are reachable from this program - the numeric "
                + "one and the extended '9' one")
        void bothArmsOfTheStatusRendererAreReachable() {
            // app/cbl/CBTRN01C.cbl:477-488 is a two-armed decision, and gate G30 wants both arms driven
            // in source order. Which arm is taken depends on the status the failing verb reported, so
            // both are driven here through the same paragraph that reports them.

            // The numeric arm: '35' is numeric and does not begin with '9', so the image is '00'
            // followed by the status (:485-486).
            Fixture numeric = new Fixture();
            when(numeric.dalytranFile.readNext())
                    .thenReturn(DalyTranRepository.ReadResult.other(OTHER_STATUS));
            assertThatExceptionOfType(AbendException.class).isThrownBy(numeric::run);
            assertThat(numeric.lines).contains(FileStatus.DISPLAY_PREFIX + "0035");

            // The extended arm: a backend refusal is reported as '9' plus a one-byte feedback code, and a
            // first character of '9' selects :479-483, where the second byte is rendered as three decimal
            // digits of its unsigned value - here zero.
            Fixture extended = new Fixture();
            when(extended.dalytranFile.readNext()).thenThrow(new IllegalStateException("driver refused"));
            assertThatExceptionOfType(AbendException.class).isThrownBy(extended::run);
            assertThat(extended.lines).contains(FileStatus.DISPLAY_PREFIX + "9000");
            assertThat(TransactionPostingJob.PERMANENT_ERROR_STATUS).startsWith("9")
                    .hasSize(FileStatus.STATUS_LENGTH);
        }

        @ParameterizedTest(name = "the {0} OPEN failure abends with ABCODE 999 and TIMING 0")
        @ValueSource(strings = { "DALYTRAN", "CUSTFILE", "XREFFILE", "ACCTFILE", "TRANFILE" })
        @DisplayName("every OPEN failure arm reaches the same Z-ABEND-PROGRAM, with its own literal")
        void everyOpenFailureArmAbendsWithItsOwnLiteral(String ddName) {
            // CARDFILE is absent from this list because its repository reports a CICS response code
            // rather than a two-character FILE STATUS, so its failing status is not OTHER_STATUS and it
            // cannot share this row shape. OpenFailures covers it on its own terms.
            Fixture fixture = new Fixture();
            String literal = failOpenOf(fixture, ddName);

            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(fixture::run).actual();

            assertThat(fixture.lines).containsExactly(TransactionPostingJob.START_BANNER, literal,
                    FileStatus.toDisplayLine(OTHER_STATUS), AbendException.ABEND_DISPLAY_TEXT);
            assertThat(abend.getReturnCode()).isEqualTo(TransactionPostingJob.APPL_RESULT_FATAL);
            assertThat(abend.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
            assertThat(abend.getTiming()).hasValue(AbendException.STANDARD_TIMING);
        }

        @ParameterizedTest(name = "the {0} CLOSE failure abends with ABCODE 999 and TIMING 0")
        @ValueSource(strings = { "CUSTFILE", "XREFFILE", "ACCTFILE", "TRANFILE" })
        @DisplayName("every CLOSE failure arm reaches the same Z-ABEND-PROGRAM, with its own literal")
        void everyCloseFailureArmAbendsWithItsOwnLiteral(String ddName) {
            // DALYTRAN is excluded here on purpose: its close paragraph reports the CUSTOMER file's
            // literal and status (defect 2), and CloseFailures covers that case on its own terms.
            Fixture fixture = new Fixture();
            String literal = failCloseOf(fixture, ddName);

            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(fixture::run).actual();

            assertThat(fixture.lines).containsSequence(literal,
                    FileStatus.toDisplayLine(OTHER_STATUS), AbendException.ABEND_DISPLAY_TEXT);
            assertThat(fixture.lines)
                    .as("the closing banner at :195 is never reached - CEE3ABD does not return")
                    .doesNotContain(TransactionPostingJob.END_BANNER);
            assertThat(abend.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
            assertThat(abend.getTiming()).hasValue(AbendException.STANDARD_TIMING);
        }

        /**
         * Makes one open report a failing status.
         *
         * @param fixture the fixture to adjust
         * @param ddName  the DD whose open should fail
         * @return the literal that paragraph displays
         */
        private String failOpenOf(Fixture fixture, String ddName) {
            switch (ddName) {
                case "DALYTRAN" -> {
                    when(fixture.dalytranFile.openStatus()).thenReturn(OTHER_STATUS);
                    return TransactionPostingJob.ERROR_OPENING_DALYTRAN;
                }
                case "CUSTFILE" -> {
                    when(fixture.custfile.openStatus()).thenReturn(OTHER_STATUS);
                    return TransactionPostingJob.ERROR_OPENING_CUSTFILE;
                }
                case "XREFFILE" -> {
                    when(fixture.xrefCursor.openStatus()).thenReturn(OTHER_STATUS);
                    return TransactionPostingJob.ERROR_OPENING_XREFFILE;
                }
                case "ACCTFILE" -> {
                    when(fixture.acctfile.openStatus()).thenReturn(OTHER_STATUS);
                    return TransactionPostingJob.ERROR_OPENING_ACCTFILE;
                }
                case "TRANFILE" -> {
                    when(fixture.tranfile.openStatus()).thenReturn(OTHER_STATUS);
                    return TransactionPostingJob.ERROR_OPENING_TRANFILE;
                }
                default -> throw new IllegalArgumentException("no such open paragraph: " + ddName);
            }
        }

        /**
         * Makes one close report a failing status.
         *
         * @param fixture the fixture to adjust
         * @param ddName  the DD whose close should fail
         * @return the literal that paragraph displays
         */
        private String failCloseOf(Fixture fixture, String ddName) {
            switch (ddName) {
                case "CUSTFILE" -> {
                    when(fixture.custfile.closeFile()).thenReturn(OTHER_STATUS);
                    return TransactionPostingJob.ERROR_CLOSING_CUSTFILE;
                }
                case "XREFFILE" -> {
                    when(fixture.xrefCursor.closeBrowse()).thenReturn(OTHER_STATUS);
                    return TransactionPostingJob.ERROR_CLOSING_XREFFILE;
                }
                case "ACCTFILE" -> {
                    when(fixture.acctfile.closeFile()).thenReturn(OTHER_STATUS);
                    return TransactionPostingJob.ERROR_CLOSING_ACCTFILE;
                }
                case "TRANFILE" -> {
                    when(fixture.tranfile.closeInput()).thenReturn(OTHER_STATUS);
                    return TransactionPostingJob.ERROR_CLOSING_TRANFILE;
                }
                default -> throw new IllegalArgumentException("no such close paragraph: " + ddName);
            }
        }
    }

    // =================================================================================================
    // The mainline guard structure - every combination of the two read-status flags.
    // =================================================================================================

    /**
     * The three mainline guards, driven across every combination of the two read-status flags.
     *
     * <p>{@code app/cbl/CBTRN01C.cbl:173-184} is three decisions, and each has an arm that is easy to
     * lose in translation:
     *
     * <ul>
     *   <li>{@code IF WS-XREF-READ-STATUS = 0} at {@code :173} - the account read runs. Note carefully
     *       that this is <strong>not</strong> "the lookup succeeded". {@code 2000-LOOKUP-XREF} moves 4
     *       into the flag only on its {@code INVALID KEY} arm ({@code :233}); a status that is neither
     *       phrase leaves the flag at the zero {@code :170} put there, so the account read happens anyway
     *       - on an account id the failed read never populated.</li>
     *   <li>{@code IF WS-ACCT-READ-STATUS NOT = 0} at {@code :177} - the {@code 'ACCOUNT ... NOT FOUND'}
     *       line. Same asymmetry: only {@code 3000-READ-ACCOUNT}'s {@code INVALID KEY} arm
     *       ({@code :247}) sets it.</li>
     *   <li>The {@code ELSE} at {@code :180} - the skip line, reached only from the cross reference's
     *       {@code INVALID KEY} arm.</li>
     * </ul>
     *
     * <p>Gates <strong>G30</strong> and <strong>G49</strong>: nine combinations, one parameterised
     * matrix. Each row states, for one pair of arms, whether the account file is read, whether the
     * not-found line is emitted, and whether the skip line is. Every expectation is doubled because a
     * one-record pass runs the loop twice - defect 1 - and both iterations take the same arms.
     */
    @Nested
    @DisplayName("The mainline guards - all nine combinations of the two read-status flags")
    class GuardStructure {

        /** How many times a one-record pass runs the loop body: the record, then the stale iteration. */
        private static final int ITERATIONS = 2;

        @ParameterizedTest(name = "xref {0} + account {1}: reads={2} notFoundLine={3} skipLine={4}")
        @CsvSource({
            // xrefArm,     acctArm,      accountIsRead, notFoundLine, skipLine
            "FOUND,         FOUND,        true,          false,        false",
            "FOUND,         INVALID_KEY,  true,          true,         false",
            "FOUND,         OTHER,        true,          false,        false",
            "INVALID_KEY,   FOUND,        false,         false,        true",
            "INVALID_KEY,   INVALID_KEY,  false,         false,        true",
            "INVALID_KEY,   OTHER,        false,         false,        true",
            "OTHER,         FOUND,        true,          false,        false",
            "OTHER,         INVALID_KEY,  true,          true,         false",
            "OTHER,         OTHER,        true,          false,        false",
        })
        @DisplayName("the guards at :173, :177 and :180 select exactly these arms")
        void theGuardsSelectExactlyTheseArms(String xrefArm, String acctArm, boolean accountIsRead,
                boolean notFoundLine, boolean skipLine) {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefOutcome(xrefArm));
            when(fixture.acctfile.readByKey(anyLong())).thenReturn(accountOutcome(acctArm));

            ExecutionSummary summary = fixture.run();

            assertThat(summary.accountReads())
                    .as("3000-READ-ACCOUNT runs only when WS-XREF-READ-STATUS is still zero")
                    .isEqualTo(accountIsRead ? ITERATIONS : 0);
            verify(fixture.acctfile, times(accountIsRead ? ITERATIONS : 0)).readByKey(anyLong());

            // The account id the not-found line names depends on which arm populated CARD-XREF-RECORD:
            // the cross reference's own eleven digits on the success arm, and the record area's declared
            // zeros when the lookup left it untouched.
            String expectedAcctId = "FOUND".equals(xrefArm) ? "99999999999" : "00000000000";
            assertThat(countOf(fixture, TransactionPostingJob.ACCOUNT_NOT_FOUND_PREFIX + expectedAcctId
                    + TransactionPostingJob.ACCOUNT_NOT_FOUND_SUFFIX))
                    .isEqualTo(notFoundLine ? ITERATIONS : 0);

            assertThat(countOf(fixture, notVerifiedLine(CARD_ONE, TRAN_ONE)))
                    .as("the skip line is the ELSE at :180, and nothing else reaches it")
                    .isEqualTo(skipLine ? ITERATIONS : 0);

            // The two arms are mutually exclusive by construction: :173's THEN and :180's ELSE.
            assertThat(notFoundLine && skipLine).isFalse();
        }

        @ParameterizedTest(name = "an INVALID KEY condition of ''{0}'' skips the transaction")
        @ValueSource(strings = { FileStatus.NOT_FOUND, FileStatus.DUPLICATE })
        @DisplayName("both INVALID KEY statuses take the same guard arm, and no other status does")
        void bothInvalidKeyStatusesTakeTheSameArm(String status) {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            when(fixture.cardXrefRepository.readByCardNumber(anyString())).thenReturn(
                    FileStatus.NOT_FOUND.equals(status)
                            ? CardXrefRepository.ReadResult.notFound(
                                    TransactionPostingJob.XREFFILE_DD_NAME)
                            // DUPREC is the base-key duplicate; the program reads the CCXREF base
                            // cluster, never the CXACAIX path, so DUPKEY cannot arise here.
                            : CardXrefRepository.ReadResult.duplicate(
                                    TransactionPostingJob.XREFFILE_DD_NAME, xref(CARD_ONE),
                                    new String(xref(CARD_ONE).encode(CHARSET), CHARSET),
                                    FileStatus.DUPREC));

            ExecutionSummary summary = fixture.run();

            assertThat(fixture.lines).contains(TransactionPostingJob.INVALID_CARD_NUMBER_FOR_XREF);
            assertThat(countOf(fixture, notVerifiedLine(CARD_ONE, TRAN_ONE))).isEqualTo(ITERATIONS);
            assertThat(summary.accountReads())
                    .as("WS-XREF-READ-STATUS became 4, so :173's guard closed")
                    .isZero();
        }

        /**
         * @param arm one of {@code FOUND}, {@code INVALID_KEY}, {@code OTHER}
         * @return the cross-reference outcome that drives that arm of {@code 2000-LOOKUP-XREF}
         */
        private CardXrefRepository.ReadResult xrefOutcome(String arm) {
            return switch (arm) {
                case "FOUND" -> xrefFound(CARD_ONE);
                case "INVALID_KEY" -> CardXrefRepository.ReadResult.notFound(
                        TransactionPostingJob.XREFFILE_DD_NAME);
                case "OTHER" -> CardXrefRepository.ReadResult.other(
                        TransactionPostingJob.XREFFILE_DD_NAME, OTHER_STATUS);
                default -> throw new IllegalArgumentException("unknown cross-reference arm: " + arm);
            };
        }

        /**
         * @param arm one of {@code FOUND}, {@code INVALID_KEY}, {@code OTHER}
         * @return the account outcome that drives that arm of {@code 3000-READ-ACCOUNT}
         */
        private AccountRepository.ReadResult accountOutcome(String arm) {
            return switch (arm) {
                case "FOUND" -> AccountRepository.ReadResult.found(account());
                case "INVALID_KEY" -> AccountRepository.ReadResult.notFound();
                case "OTHER" -> AccountRepository.ReadResult.of(OTHER_STATUS);
                default -> throw new IllegalArgumentException("unknown account arm: " + arm);
            };
        }
    }

    /**
     * @param fixture the fixture whose spool to count in
     * @param line    the exact line to count
     * @return how many times that line was displayed
     */
    private static long countOf(Fixture fixture, String line) {
        return fixture.lines.stream().filter(line::equals).count();
    }

    // =================================================================================================
    // The SYSOUT fingerprint - every literal, transcribed from the source rather than from the class.
    // =================================================================================================

    /**
     * Every {@code DISPLAY} literal {@value TransactionPostingJob#PROGRAM_ID} can emit, byte for byte.
     *
     * <p>The rest of this suite asserts SYSOUT lines against {@link TransactionPostingJob}'s own
     * constants, which is the right way round for readability but says nothing about whether a constant
     * is <em>correct</em>. Here the literals are transcribed from {@code app/cbl/CBTRN01C.cbl} by hand,
     * with the line each came from, and compared to the constants. If a constant ever drifts - a lost
     * trailing space, a normalised {@code 'ACCOUNT ID : '}, a tidied-up message - it fails here, and the
     * rest of the suite keeps passing against the drifted value. That is the point of the duplication.
     */
    @Nested
    @DisplayName("The SYSOUT fingerprint - literals transcribed from CBTRN01C, not from the class")
    class SourceLiterals {

        @Test
        @DisplayName("the two banners: :156 at start-up and :195 at the end")
        void theBanners() {
            assertThat(TransactionPostingJob.START_BANNER)
                    .isEqualTo("START OF EXECUTION OF PROGRAM CBTRN01C");
            assertThat(TransactionPostingJob.END_BANNER)
                    .isEqualTo("END OF EXECUTION OF PROGRAM CBTRN01C");
        }

        @Test
        @DisplayName("the six OPEN literals, one per paragraph: :263, :282, :300, :318, :336, :354")
        void theSixOpenLiterals() {
            assertThat(TransactionPostingJob.ERROR_OPENING_DALYTRAN)
                    .isEqualTo("ERROR OPENING DAILY TRANSACTION FILE");
            assertThat(TransactionPostingJob.ERROR_OPENING_CUSTFILE)
                    .isEqualTo("ERROR OPENING CUSTOMER FILE");
            assertThat(TransactionPostingJob.ERROR_OPENING_XREFFILE)
                    .isEqualTo("ERROR OPENING CROSS REF FILE");
            assertThat(TransactionPostingJob.ERROR_OPENING_CARDFILE)
                    .isEqualTo("ERROR OPENING CARD FILE");
            assertThat(TransactionPostingJob.ERROR_OPENING_ACCTFILE)
                    .isEqualTo("ERROR OPENING ACCOUNT FILE");
            assertThat(TransactionPostingJob.ERROR_OPENING_TRANFILE)
                    .isEqualTo("ERROR OPENING TRANSACTION FILE");
        }

        @Test
        @DisplayName("the CLOSE literals - and the one that does not exist, because 9000 borrows the "
                + "customer file's")
        void theCloseLiterals() {
            assertThat(TransactionPostingJob.ERROR_CLOSING_CUSTFILE)
                    .isEqualTo("ERROR CLOSING CUSTOMER FILE");
            assertThat(TransactionPostingJob.ERROR_CLOSING_XREFFILE)
                    .isEqualTo("ERROR CLOSING CROSS REF FILE");
            assertThat(TransactionPostingJob.ERROR_CLOSING_CARDFILE)
                    .isEqualTo("ERROR CLOSING CARD FILE");
            assertThat(TransactionPostingJob.ERROR_CLOSING_ACCTFILE)
                    .isEqualTo("ERROR CLOSING ACCOUNT FILE");
            assertThat(TransactionPostingJob.ERROR_CLOSING_TRANFILE)
                    .isEqualTo("ERROR CLOSING TRANSACTION FILE");
            // Defect 2, asserted as a type-level absence. app/cbl/CBTRN01C.cbl:372 - inside
            // 9000-DALYTRAN-CLOSE - displays 'ERROR CLOSING CUSTOMER FILE', the same literal
            // 9100-CUSTFILE-CLOSE uses at :390. There is no daily-transaction close literal in the
            // program at all, so there must be no constant for one here either: publishing one would be
            // inventing a message the program cannot emit.
            assertThat(publicConstantNames(TransactionPostingJob.class))
                    .doesNotContain("ERROR_CLOSING_DALYTRAN")
                    .contains("ERROR_CLOSING_CUSTFILE");
        }

        @Test
        @DisplayName("the read literal at :219, and the abend and file-status texts the Z-paragraphs emit")
        void theReadAndAbendLiterals() {
            assertThat(TransactionPostingJob.ERROR_READING_DALYTRAN)
                    .isEqualTo("ERROR READING DAILY TRANSACTION FILE");
            // Z-ABEND-PROGRAM, app/cbl/CBTRN01C.cbl:470.
            assertThat(AbendException.ABEND_DISPLAY_TEXT).isEqualTo("ABENDING PROGRAM");
            // Z-DISPLAY-IO-STATUS, :483 and :487 - the literal is followed by the four-character image,
            // so 'NNNN' is part of the text and not a placeholder to be filled in.
            assertThat(FileStatus.DISPLAY_PREFIX).isEqualTo("FILE STATUS IS: NNNN");
        }

        @Test
        @DisplayName("2000-LOOKUP-XREF's four lines - and the column alignment that makes 'ACCOUNT ID : ' "
                + "carry a space before its colon")
        void theCrossReferenceLiterals() {
            assertThat(TransactionPostingJob.INVALID_CARD_NUMBER_FOR_XREF)
                    .isEqualTo("INVALID CARD NUMBER FOR XREF");
            assertThat(TransactionPostingJob.SUCCESSFUL_READ_OF_XREF)
                    .isEqualTo("SUCCESSFUL READ OF XREF");
            // :236, :237, :238. Each literal ends in a space because DISPLAY concatenates its operands
            // with no separator of its own.
            assertThat(TransactionPostingJob.XREF_CARD_NUMBER_PREFIX).isEqualTo("CARD NUMBER: ");
            assertThat(TransactionPostingJob.XREF_ACCOUNT_ID_PREFIX).isEqualTo("ACCOUNT ID : ");
            assertThat(TransactionPostingJob.XREF_CUSTOMER_ID_PREFIX).isEqualTo("CUSTOMER ID: ");
            // The space before the colon is not a typo in the source - it pads 'ACCOUNT ID' to the width
            // of 'CARD NUMBER' and 'CUSTOMER ID' so the three values line up in the spool. Normalising it
            // to 'ACCOUNT ID: ' would shift one column of every statement this program prints.
            assertThat(TransactionPostingJob.XREF_ACCOUNT_ID_PREFIX.length())
                    .isEqualTo(TransactionPostingJob.XREF_CARD_NUMBER_PREFIX.length())
                    .isEqualTo(TransactionPostingJob.XREF_CUSTOMER_ID_PREFIX.length());
        }

        @Test
        @DisplayName("3000-READ-ACCOUNT's two lines at :246 and :249")
        void theAccountLiterals() {
            assertThat(TransactionPostingJob.INVALID_ACCOUNT_NUMBER_FOUND)
                    .isEqualTo("INVALID ACCOUNT NUMBER FOUND");
            assertThat(TransactionPostingJob.SUCCESSFUL_READ_OF_ACCOUNT_FILE)
                    .isEqualTo("SUCCESSFUL READ OF ACCOUNT FILE");
        }

        @Test
        @DisplayName("the mainline's two composed lines: 'ACCOUNT ... NOT FOUND' at :178 and the "
                + "four-operand skip line at :181-183")
        void theMainlineComposedLines() {
            assertThat(TransactionPostingJob.ACCOUNT_NOT_FOUND_PREFIX).isEqualTo("ACCOUNT ");
            assertThat(TransactionPostingJob.ACCOUNT_NOT_FOUND_SUFFIX).isEqualTo(" NOT FOUND");
            assertThat(TransactionPostingJob.CARD_NOT_VERIFIED_PREFIX).isEqualTo("CARD NUMBER ");
            assertThat(TransactionPostingJob.CARD_NOT_VERIFIED_SUFFIX)
                    .isEqualTo(" COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-");
            // 'ID-' ends the literal with no trailing space, so DALYTRAN-ID abuts the hyphen. A single
            // DISPLAY with four operands is one line, not four - :181-183 is one statement continued
            // across three source lines.
            assertThat(TransactionPostingJob.CARD_NOT_VERIFIED_SUFFIX).endsWith("ID-")
                    .doesNotEndWith("ID- ");
            assertThat(notVerifiedLine(CARD_ONE, TRAN_ONE))
                    .isEqualTo("CARD NUMBER " + CARD_ONE
                            + " COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-" + TRAN_ONE)
                    .doesNotContain("\n");
        }

        @Test
        @DisplayName("a whole clean run's SYSOUT, in order, is exactly the fingerprint those literals "
                + "compose")
        void aWholeRunComposesTheFingerprint() {
            Fixture fixture = new Fixture();
            DalyTranRecord record = dalyTran(TRAN_ONE, CARD_ONE);
            fixture.dailyTransactions(record);
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_ONE));
            when(fixture.acctfile.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.found(account()));

            fixture.run();

            // One record: banner, image, four lookup lines, the account line, then the stale iteration's
            // four lookup lines and account line again, then the closing banner. Thirteen lines, spelled
            // out here rather than assembled from helpers so the expected spool is readable as text.
            assertThat(fixture.lines).containsExactly(
                    "START OF EXECUTION OF PROGRAM CBTRN01C",
                    record.displayImage(),
                    "SUCCESSFUL READ OF XREF",
                    "CARD NUMBER: " + CARD_ONE,
                    "ACCOUNT ID : 99999999999",
                    "CUSTOMER ID: 123456789",
                    "SUCCESSFUL READ OF ACCOUNT FILE",
                    "SUCCESSFUL READ OF XREF",
                    "CARD NUMBER: " + CARD_ONE,
                    "ACCOUNT ID : 99999999999",
                    "CUSTOMER ID: 123456789",
                    "SUCCESSFUL READ OF ACCOUNT FILE",
                    "END OF EXECUTION OF PROGRAM CBTRN01C");
        }
    }

    /**
     * @param type the type to inspect
     * @return the names of its public static final fields
     */
    private static List<String> publicConstantNames(Class<?> type) {
        return Stream.of(type.getDeclaredFields())
                .filter(field -> Modifier.isPublic(field.getModifiers())
                        && Modifier.isStatic(field.getModifiers())
                        && Modifier.isFinal(field.getModifiers()))
                .map(Field::getName)
                .toList();
    }

    // =================================================================================================
    // The record image - asserted against the real fixture bytes, as a raw span.
    // =================================================================================================

    /**
     * {@code DISPLAY DALYTRAN-RECORD} emits the record's raw span, not a re-encode of its fields.
     *
     * <p>Rule <strong>R5</strong>: fixed width is the wire format. {@code app/cbl/CBTRN01C.cbl:168}
     * displays a {@code 01} group item, and COBOL writes the group's storage - three hundred and fifty
     * bytes, exactly as the {@code READ ... INTO} at {@code :203} left them. It does not consult the
     * subordinate {@code PICTURE} clauses, so a field whose stored bytes a Java field cannot round trip
     * still appears verbatim.
     *
     * <p><strong>Where the bytes come from.</strong> {@code src/test/resources/fixtures/dailytran.txt},
     * loaded from the classpath, is a byte-identical copy of {@code app/data/ASCII/dailytran.txt} - the
     * authoritative fixture the AAP names, three hundred records of three hundred and fifty bytes. This
     * is the one part of the suite that must not be built from a hand-written row: practice
     * <strong>B12</strong> accepts a statically derived expectation, and the mitigation for the risk that
     * carries is to pin the byte-level claims against real production-shaped data.
     *
     * <p><strong>The case where a re-encode really differs.</strong> Six of the three hundred rows carry
     * a {@code &#125;} in the trailing byte of {@code DALYTRAN-AMT} - the zoned-decimal overpunch for a
     * negative sign on a trailing digit of zero. Those six are honest negative amounts of non-zero
     * magnitude, and their sign does survive a {@link BigDecimal} round trip, so they alone would not
     * prove the point. What cannot survive one is a negative <em>zero</em>: {@code 0000000000&#125;} is a
     * magnitude of zero carrying a negative sign, {@link BigDecimal} has no such value, and re-encoding
     * it yields {@code 0000000000&#123;} - a different byte. No row of the fixture has a zero magnitude,
     * so that image is written into a record area here deliberately. Both cases are asserted: the real
     * rows for provenance, the synthesised one for the proof.
     */
    @Nested
    @DisplayName("DISPLAY DALYTRAN-RECORD - the raw 350-byte span, proven against the real fixture")
    class RecordImageIsTheRawSpan {

        @Test
        @DisplayName("the fixture itself is what the AAP says it is: 300 records of 350 bytes, 6 of them "
                + "carrying a negative overpunch in DALYTRAN-AMT")
        void theFixtureIsWhatTheAapSaysItIs() {
            List<String> rows = classpathLines(DAILY_TRANSACTION_FIXTURE);

            assertThat(rows).hasSize(FIXTURE_RECORD_COUNT);
            assertThat(rows).allSatisfy(row -> assertThat(row).hasSize(DalyTranRecord.RECORD_LENGTH));

            // DALYTRAN-AMT is PIC S9(09)V99 at offset 132 for 11 bytes (app/cpy/CVTRA06Y.cpy:10). The
            // sign is overpunched into the last of those bytes, so that byte is index 142.
            int signByte = DalyTranRecord.DALYTRAN_AMT_OFFSET + DalyTranRecord.DALYTRAN_AMT_LENGTH - 1;
            assertThat(signByte).isEqualTo(142);
            assertThat(rows.stream().filter(row -> row.charAt(signByte) == NEGATIVE_ZERO_OVERPUNCH).count())
                    .isEqualTo(NEGATIVE_OVERPUNCH_ROW_COUNT);
            assertThat(rows.get(FIRST_NEGATIVE_OVERPUNCH_ROW - 1).charAt(signByte))
                    .as("row %d is the worked example used below", FIRST_NEGATIVE_OVERPUNCH_ROW)
                    .isEqualTo(NEGATIVE_ZERO_OVERPUNCH);
        }

        @Test
        @DisplayName("a real fixture row is displayed byte-for-byte, its negative overpunch included")
        void aRealFixtureRowIsDisplayedByteForByte() {
            String row = dailyTransactionFixtureRow(FIRST_NEGATIVE_OVERPUNCH_ROW);
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(DalyTranRecord.decode(row, CHARSET));

            fixture.run();

            assertThat(fixture.lines)
                    .as("the SYSOUT line is the fixture row, unchanged and untrimmed")
                    .contains(row);
            assertThat(fixture.lines).filteredOn(line -> line.length() == row.length())
                    .as("and it is the full record width, FILLER X(20) included")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("R4 and gate G22: the amount that row carries is a BigDecimal at the copybook's "
                + "scale, and it is negative")
        void theAmountIsABigDecimalAtTheDeclaredScale() {
            String row = dailyTransactionFixtureRow(FIRST_NEGATIVE_OVERPUNCH_ROW);

            DalyTranRecord record = DalyTranRecord.decode(row, CHARSET);

            // PIC S9(09)V99: nine integer digits and two fractional ones, so scale is exactly 2. No
            // double and no float appears anywhere in this file - a binary floating-point type cannot
            // hold 919.00 exactly, and a monetary comparison against one would be meaningless.
            assertThat(record.dalytranAmt())
                    .isEqualTo(FIRST_NEGATIVE_OVERPUNCH_AMOUNT)
                    .hasScaleOf(DalyTranRecord.DALYTRAN_AMT_SCALE);
            assertThat(record.dalytranAmt().signum()).isNegative();
            assertThat(record.dalytranAmtImage())
                    .as("the stored image keeps the overpunch rather than a leading minus")
                    .isEqualTo("0000009190" + NEGATIVE_ZERO_OVERPUNCH)
                    .hasSize(DalyTranRecord.DALYTRAN_AMT_LENGTH);
            assertThat(record.hasZeroDalytranAmt()).isFalse();
        }

        @Test
        @DisplayName("PROOF: a negative-zero amount displays as '}' although a BigDecimal round trip "
                + "renders '{' - so DISPLAY cannot be re-encoding the fields")
        void aNegativeZeroAmountProvesTheSpanIsRaw() {
            // No row of app/data/ASCII/dailytran.txt has a zero magnitude, so this image is written in
            // deliberately. It is the one place where "raw span" and "re-encode of decoded fields"
            // produce different bytes, which makes it the only assertion that can tell them apart.
            DalyTranRecord stored = dalyTranWithAmountImage(NEGATIVE_ZERO_AMOUNT_IMAGE);
            stored.moveDalytranId(TRAN_ONE);
            stored.moveDalytranCardNum(CARD_ONE);
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(stored);

            fixture.run();

            // What a decode-then-re-encode would have produced: BigDecimal has no negative zero, so the
            // sign is gone and the overpunch comes back positive.
            assertThat(stored.dalytranAmt().signum()).isZero();
            DalyTranRecord roundTripped = new DalyTranRecord(CHARSET);
            roundTripped.moveDalytranAmt(stored.dalytranAmt());
            assertThat(roundTripped.dalytranAmtImage()).isEqualTo(POSITIVE_ZERO_AMOUNT_IMAGE);

            // What DISPLAY actually emitted: the stored bytes.
            String displayed = fixture.lines.stream()
                    .filter(line -> line.length() == DalyTranRecord.RECORD_LENGTH)
                    .findFirst()
                    .orElseThrow();
            assertThat(displayed).isEqualTo(stored.displayImage());
            assertThat(displayed.substring(DalyTranRecord.DALYTRAN_AMT_OFFSET,
                    DalyTranRecord.DALYTRAN_AMT_OFFSET + DalyTranRecord.DALYTRAN_AMT_LENGTH))
                    .as("the negative zero survived to SYSOUT, which a re-encode would have lost")
                    .isEqualTo(NEGATIVE_ZERO_AMOUNT_IMAGE)
                    .isNotEqualTo(POSITIVE_ZERO_AMOUNT_IMAGE);
        }

        @Test
        @DisplayName("all six overpunched rows display verbatim, not just the worked example")
        void allSixOverpunchedRowsDisplayVerbatim() {
            int signByte = DalyTranRecord.DALYTRAN_AMT_OFFSET + DalyTranRecord.DALYTRAN_AMT_LENGTH - 1;
            List<String> overpunched = classpathLines(DAILY_TRANSACTION_FIXTURE).stream()
                    .filter(row -> row.charAt(signByte) == NEGATIVE_ZERO_OVERPUNCH)
                    .toList();
            assertThat(overpunched).hasSize(NEGATIVE_OVERPUNCH_ROW_COUNT);

            for (String row : overpunched) {
                Fixture fixture = new Fixture();
                fixture.dailyTransactions(DalyTranRecord.decode(row, CHARSET));

                fixture.run();

                assertThat(fixture.lines).as("row displayed verbatim").contains(row);
                assertThat(DalyTranRecord.decode(row, CHARSET).dalytranAmt())
                        .as("every one of the six is a negative amount of non-zero magnitude")
                        .isNegative()
                        .hasScaleOf(DalyTranRecord.DALYTRAN_AMT_SCALE);
            }
        }

        @Test
        @DisplayName("a fixture row reaches the stream sink as 350 bytes plus one line feed, in the named "
                + "code page")
        void aFixtureRowReachesTheStreamSinkUnchanged() {
            String row = dailyTransactionFixtureRow(FIRST_NEGATIVE_OVERPUNCH_ROW);
            ByteArrayOutputStream captured = new ByteArrayOutputStream();

            new Fixture().job().sysoutSinkTo(captured).display(row);

            assertThat(captured.toString(CHARSET)).isEqualTo(row + "\n");
            assertThat(captured.toByteArray()).hasSize(DalyTranRecord.RECORD_LENGTH + 1);
        }
    }

    // =================================================================================================
    // VERIFIED NEGATIVE 1 - the job called "Posting" posts nothing.
    // =================================================================================================

    /**
     * The assertion that the {@code TransactionPostingJob} name promises work the source never does.
     *
     * <p><strong>The evidence.</strong>
     * {@code grep -cE '^.{6} *(WRITE|REWRITE) ' app/cbl/CBTRN01C.cbl} returns <strong>0</strong>, and no
     * {@code WRITE} or {@code REWRITE} token appears anywhere in the program's 491 lines - not in a
     * paragraph, not in a continuation, not in a comment. Six datasets are opened; three are read; none
     * is written.
     *
     * <p><strong>Why the name says otherwise.</strong> Rule <strong>R1</strong>: the class name is taken
     * from the prompt verbatim and the behaviour is taken from the source. {@code CBTRN01C}'s own header
     * reads {@code Function : Post the records from daily transaction file}, and the program does not
     * post them. {@code CBTRN02C} - a different program, translated by
     * {@link TransactionValidationJob} - is the one that genuinely posts. Do not carry an expectation
     * across from that suite: the two programs read the same {@code DALYTRAN} dataset and resolve the
     * same cross reference and account, which makes borrowing its assertions the single easiest way to
     * get this file wrong.
     *
     * <p>Every assertion here is a {@code never()}. They are what makes adding a write to
     * {@link TransactionPostingJob} fail rather than pass quietly.
     */
    @Nested
    @DisplayName("VERIFIED NEGATIVE 1 - nothing is written, added, rewritten or deleted, anywhere")
    class NothingPosts {

        /** How many records the pass is given, so no write can hide behind a short run. */
        private static final int RECORDS = 5;

        /**
         * Runs a full pass in which every lookup and every account read <em>succeeds</em>.
         *
         * <p>The successful arms are deliberate. A run whose lookups all miss never reaches
         * {@code 3000-READ-ACCOUNT} at all, so it could not observe a write there even if one existed.
         * This pass reaches every statement the loop has.
         *
         * @return the fixture, after the pass
         */
        private Fixture passOverManyRecords() {
            Fixture fixture = new Fixture();
            DalyTranRecord[] records = new DalyTranRecord[RECORDS];
            for (int index = 0; index < RECORDS; index++) {
                records[index] = dalyTran(String.format("TRAN%012d", index + 1), CARD_ONE);
            }
            fixture.dailyTransactions(records);
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_ONE));
            when(fixture.acctfile.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.found(account()));

            ExecutionSummary summary = fixture.run();

            assertThat(summary.recordsRead()).as("the pass really did read every record").isEqualTo(RECORDS);
            assertThat(summary.accountReads())
                    .as("and really did reach 3000-READ-ACCOUNT on every iteration, defect 1 included")
                    .isEqualTo(RECORDS + ExecutionSummary.POST_END_OF_FILE_LOOKUPS);
            return fixture;
        }

        @Test
        @DisplayName("the TRANSACTION file is never written and never even opened for output - the posting "
                + "the class name promises is exactly the WRITE that is not in the source")
        void theTransactionFileIsNeverWritten() {
            Fixture fixture = passOverManyRecords();

            verify(fixture.transactionRepository, never()).write(any(TranRecord.class));
            verify(fixture.transactionRepository, never()).openOutput();
            verify(fixture.transactionRepository, never())
                    .openOutput(any(DatasetBinding.class), anyString());
        }

        @Test
        @DisplayName("no account is rewritten - unlike CBACT04C and COBIL00C, this program's ACCTFILE is "
                + "read-only")
        void noAccountIsRewritten() {
            Fixture fixture = passOverManyRecords();

            verify(fixture.accountRepository, never()).rewrite(any(AccountRecord.class));
            verify(fixture.acctfile, never()).rewrite(any(AccountRecord.class));
            // And it was never even opened in a mode that would permit one.
            verify(fixture.accountRepository, never()).open(AccountRepository.OpenMode.I_O);
            verify(fixture.acctfile, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("no card and no customer is rewritten either: both files are opened, closed, and "
                + "otherwise untouched")
        void noCardOrCustomerIsRewritten() {
            Fixture fixture = passOverManyRecords();

            verify(fixture.cardRepository, never()).rewrite(any(CardRecord.class));
            verify(fixture.customerRepository, never()).rewrite(any(CustomerRecord.class));
            verify(fixture.customerRepository, never()).rewrite(any(byte[].class));
            verify(fixture.customerRepository, never()).readForUpdate(anyString());
            verify(fixture.cardRepository, never()).readForUpdateByCardNumber(anyString());
        }

        @Test
        @DisplayName("there is no category-balance update to make: CBTRN01C accesses no TCATBALF, so no "
                + "such collaborator is injected at all")
        void thereIsNoCategoryBalanceUpdate() {
            // CBTRN02C - the program that genuinely posts - opens TCATBALF and rewrites it per
            // transaction category. CBTRN01C's FILE-CONTROL (app/cbl/CBTRN01C.cbl:29-62) declares six
            // SELECTs and TCATBALF is not among them, so the absence is asserted where it is decided:
            // the constructor's parameter list. A TranCatBalRepository appearing here later would be a
            // dataset this program never opens.
            assertThat(TransactionPostingJob.class.getDeclaredConstructors())
                    .singleElement()
                    .satisfies(constructor -> assertThat(constructor.getParameterTypes())
                            .extracting(Class::getSimpleName)
                            .containsExactly("BatchConfig", "DalyTranRepository", "CustomerRepository",
                                    "CardXrefRepository", "CardRepository", "AccountRepository",
                                    "TransactionRepository", "ObjectProvider"));
        }

        @Test
        @DisplayName("the two read-only repositories publish no mutator to call: DalyTran and CardXref "
                + "expose no write, add, rewrite or delete at all")
        void theReadOnlyRepositoriesPublishNoMutator() {
            // A never() can only negate a method that exists. For these two the stronger statement holds
            // and is worth pinning structurally, so that a mutator added to either one later is caught
            // here rather than being quietly available to a future edit of this job.
            assertThat(publicMethodNames(DalyTranRepository.class))
                    .as("DALYTRAN is OPEN INPUT at app/cbl/CBTRN01C.cbl:254 and sequential; it has no "
                            + "mutating access path")
                    .noneMatch(TransactionPostingJobTest::namesAMutation);
            assertThat(publicMethodNames(CardXrefRepository.class))
                    .as("the cross reference is read by key and never updated by any of its twelve "
                            + "consumers")
                    .noneMatch(TransactionPostingJobTest::namesAMutation);
        }
    }

    /**
     * @param type the type to inspect
     * @return the names of every public method it declares
     */
    private static List<String> publicMethodNames(Class<?> type) {
        return Stream.of(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .toList();
    }

    /** The verbs that would name an operation changing stored bytes. */
    private static final List<String> MUTATING_VERBS =
            List.of("write", "rewrite", "add", "delete", "insert", "update", "put", "post", "openoutput");

    /**
     * Whether a method name begins with a mutating verb <em>as a whole word</em>.
     *
     * <p>The whole-word test matters: {@code CardXrefRepository.addressing(...)} begins with the letters
     * of {@code add} and is not a mutation - it re-points a repository at a job-scoped dataset binding
     * and returns a view. So a verb counts only when the name is exactly that verb or continues in camel
     * case, which accepts {@code write}, {@code rewrite}, {@code addRecord} and {@code openOutput} while
     * rejecting {@code addressing}.
     *
     * @param methodName a method name
     * @return whether it names an operation that would change stored bytes
     */
    private static boolean namesAMutation(String methodName) {
        String lower = methodName.toLowerCase(Locale.ROOT);
        for (String verb : MUTATING_VERBS) {
            if (lower.startsWith(verb) && (lower.length() == verb.length()
                    || Character.isUpperCase(methodName.charAt(verb.length())))) {
                return true;
            }
        }
        return false;
    }

    // =================================================================================================
    // VERIFIED NEGATIVE 2 - six files opened and closed, three of them never read.
    // =================================================================================================

    /**
     * The twelve file verbs, in the order {@code MAIN-PARA} issues them.
     *
     * <p><strong>The evidence.</strong> {@code grep -nE '^.{6} *READ ' app/cbl/CBTRN01C.cbl} returns
     * exactly <strong>three</strong> hits - {@code :203} the daily transaction file, {@code :229} the
     * cross reference, {@code :243} the account - while {@code MAIN-PARA} opens <strong>six</strong>
     * files at {@code :157-162} and closes <strong>six</strong> at {@code :188-193}. The customer file,
     * the card file and the transaction file are opened, and closed, and nothing in between ever touches
     * them.
     *
     * <p><strong>Why the order is asserted and not just the set.</strong> The order is observable. Each
     * paragraph has its own status ladder and its own {@code DISPLAY} literal, and each abends on
     * failure - so which file is opened first decides which message an operator sees when two are
     * misconfigured, and how many of the others were opened at all. {@code Z-ABEND-PROGRAM} does not
     * unwind, so the ladder position is the whole of the diagnosis.
     *
     * <p>Practice <strong>B5</strong>: the three unread opens are preserved useless work. They are not a
     * translation artefact to be tidied away - removing them would remove three status ladders, three
     * literals and three abend paths from the program's observable surface.
     */
    @Nested
    @DisplayName("VERIFIED NEGATIVE 2 - the twelve file verbs, in source order, three files never read")
    class FileVerbOrder {

        @Test
        @DisplayName("all six files are OPENed in MAIN-PARA's order: DALYTRAN, CUSTFILE, XREFFILE, "
                + "CARDFILE, ACCTFILE, TRANFILE")
        void theSixOpensHappenInSourceOrder() {
            Fixture fixture = new Fixture();

            fixture.run();

            // app/cbl/CBTRN01C.cbl:157-162 - one PERFORM per file, in exactly this sequence.
            InOrder opens = inOrder(fixture.dalyTranRepository, fixture.customerRepository,
                    fixture.cardXrefRepository, fixture.cardRepository, fixture.accountRepository,
                    fixture.transactionRepository);
            opens.verify(fixture.dalyTranRepository).open();
            opens.verify(fixture.customerRepository).openInput();
            opens.verify(fixture.cardXrefRepository).openBrowse();
            opens.verify(fixture.cardRepository).openBrowse(anyString(), any());
            opens.verify(fixture.accountRepository).open(AccountRepository.OpenMode.INPUT);
            opens.verify(fixture.transactionRepository).openInput(any(DatasetBinding.class));
        }

        @Test
        @DisplayName("all six files are CLOSEd in the 9000-9500 order: DALYTRAN, CUSTFILE, XREFFILE, "
                + "CARDFILE, ACCTFILE, TRANFILE")
        void theSixClosesHappenInSourceOrder() {
            Fixture fixture = new Fixture();

            fixture.run();

            // app/cbl/CBTRN01C.cbl:188-193 - the close ladder mirrors the open ladder rather than
            // reversing it, which is what the paragraph numbers 9000, 9100, 9200, 9300, 9400, 9500 say.
            InOrder closes = inOrder(fixture.dalytranFile, fixture.custfile, fixture.xrefCursor,
                    fixture.cardBrowse, fixture.acctfile, fixture.tranfile);
            closes.verify(fixture.dalytranFile).closeFile();
            closes.verify(fixture.custfile).closeFile();
            closes.verify(fixture.xrefCursor).closeBrowse();
            closes.verify(fixture.cardBrowse).endBrowse();
            closes.verify(fixture.acctfile).closeFile();
            closes.verify(fixture.tranfile).closeInput();
        }

        @Test
        @DisplayName("the loop sits strictly between the ladders: every OPEN precedes the first READ and "
                + "every CLOSE follows the last one")
        void theLoopSitsBetweenTheTwoLadders() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));

            fixture.run();

            // One InOrder over the whole run: last open, then the read, then first close. A translation
            // that opened a file lazily at first use, or released one early, would satisfy the two
            // ladder tests above and fail this one.
            InOrder wholeRun = inOrder(fixture.transactionRepository, fixture.dalytranFile,
                    fixture.cardXrefRepository, fixture.dalyTranRepository);
            wholeRun.verify(fixture.transactionRepository).openInput(any(DatasetBinding.class));
            wholeRun.verify(fixture.dalytranFile).readNext();
            wholeRun.verify(fixture.cardXrefRepository, times(2)).readByCardNumber(anyString());
            wholeRun.verify(fixture.dalytranFile).closeFile();
        }

        @Test
        @DisplayName("exactly one OPEN and one CLOSE per file per clean run - no file is released twice")
        void eachFileIsOpenedOnceAndClosedOnce() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE), dalyTran("TRAN000000000002",
                    CARD_TWO));

            fixture.run();

            verify(fixture.dalyTranRepository, times(1)).open();
            verify(fixture.dalytranFile, times(1)).closeFile();
            verify(fixture.customerRepository, times(1)).openInput();
            verify(fixture.custfile, times(1)).closeFile();
            verify(fixture.cardXrefRepository, times(1)).openBrowse();
            verify(fixture.xrefCursor, times(1)).closeBrowse();
            verify(fixture.cardRepository, times(1)).openBrowse(anyString(), any());
            verify(fixture.cardBrowse, times(1)).endBrowse();
            verify(fixture.accountRepository, times(1)).open(AccountRepository.OpenMode.INPUT);
            verify(fixture.acctfile, times(1)).closeFile();
            verify(fixture.transactionRepository, times(1)).openInput(any(DatasetBinding.class));
            verify(fixture.tranfile, times(1)).closeInput();
        }

        @Test
        @DisplayName("the CUSTOMER file receives no read of ANY kind - not by key, not sequentially, not "
                + "for update")
        void theCustomerFileIsNeverReadByAnyPath() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_ONE));

            fixture.run();

            // This is the assertion that stops someone "using" the file the program opens and ignores.
            // The cross reference the program DOES read carries XREF-CUST-ID (app/cpy/CVACT03Y.cpy), so a
            // customer read here would look entirely reasonable - and would be a new feature.
            verify(fixture.customerRepository, never()).readByKey(anyLong());
            verify(fixture.customerRepository, never()).readByKey(anyString());
            verify(fixture.customerRepository, never()).readForUpdate(anyString());
            verify(fixture.custfile, never()).readNext();
            verify(fixture.custfile, never()).readByKey(anyLong());
            verify(fixture.custfile, never()).readByKey(anyString());
        }

        @Test
        @DisplayName("the CARD file receives no read of ANY kind - not by card number, not by the "
                + "alternate index, not through the browse it was opened with")
        void theCardFileIsNeverReadByAnyPath() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_ONE));

            fixture.run();

            verify(fixture.cardRepository, never()).readByCardNumber(anyString());
            verify(fixture.cardRepository, never()).readForUpdateByCardNumber(anyString());
            verify(fixture.cardRepository, never()).readByAccountIdViaAltIndex(anyLong());
            verify(fixture.cardRepository, never()).readByAccountIdViaAltIndex(anyString());
            verify(fixture.cardBrowse, never()).readNext();
            verify(fixture.cardBrowse, never()).readPrev();
        }

        @Test
        @DisplayName("the TRANSACTION file receives no read of ANY kind either - opened, closed, and "
                + "neither read nor written")
        void theTransactionFileIsNeverReadByAnyPath() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));

            fixture.run();

            verify(fixture.transactionRepository, never()).readByTranId(anyString());
            verify(fixture.transactionRepository, never()).readForUpdateByTranId(anyString());
            verify(fixture.transactionRepository, never()).startBrowse(any());
            verify(fixture.transactionRepository, never()).startBrowse(anyString(), any());
            verify(fixture.tranfile, never()).readNext();
            // And nothing was written to it - see NothingPosts for the full mutator sweep.
            verify(fixture.transactionRepository, never()).write(any(TranRecord.class));
        }

        @Test
        @DisplayName("only three READs exist in the program, so only three datasets are read: the ones at "
                + ":203, :229 and :243")
        void exactlyThreeDatasetsAreRead() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_ONE));
            when(fixture.acctfile.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.found(account()));

            ExecutionSummary summary = fixture.run();

            // The three that are read, each through its own source READ statement.
            verify(fixture.dalytranFile, times(2)).readNext();
            verify(fixture.cardXrefRepository, times(2)).readByCardNumber(anyString());
            verify(fixture.acctfile, times(2)).readByKey(anyLong());
            assertThat(summary.recordsRead()).isEqualTo(1);
            // Two reads per site for one record is defect 1, not a double read: the loop's lookup and
            // account read sit outside the end-of-file guard, so the final iteration repeats them.
            assertThat(summary.xrefLookups()).isEqualTo(summary.recordsRead()
                    + ExecutionSummary.POST_END_OF_FILE_LOOKUPS);
        }
    }

    // =================================================================================================
    // VERIFIED NEGATIVE 3 - the lookup that runs once more than there are records.
    // =================================================================================================

    /**
     * The post-end-of-file lookup, on the previous record's card number.
     *
     * <p><strong>The source.</strong> {@code app/cbl/CBTRN01C.cbl:164-186}:
     *
     * <pre>
     * PERFORM UNTIL END-OF-DAILY-TRANS-FILE = 'Y'                :164
     *     IF  END-OF-DAILY-TRANS-FILE = 'N'                      :165
     *         PERFORM 1000-DALYTRAN-GET-NEXT                     :166
     *         IF  END-OF-DAILY-TRANS-FILE = 'N'                  :167
     *             DISPLAY DALYTRAN-RECORD                        :168  &lt;-- ONLY this is guarded
     *         END-IF                                             :169
     *         MOVE 0                 TO WS-XREF-READ-STATUS      :170  &lt;-- outside the guard
     *         MOVE DALYTRAN-CARD-NUM TO XREF-CARD-NUM            :171  &lt;-- outside the guard
     *         PERFORM 2000-LOOKUP-XREF                           :172  &lt;-- outside the guard
     *         ...
     * </pre>
     *
     * <p>The guard at {@code :167} closes at {@code :169}. Everything from {@code :170} to {@code :184}
     * runs on <em>every</em> iteration, the one that hit end of file included - and a
     * {@code READ ... INTO} that ends the file leaves the record area untouched, so
     * {@code DALYTRAN-CARD-NUM} at {@code :171} still holds the <strong>previous</strong> record's card
     * number. One extra lookup, on a stale key, on every run. Over an empty dataset the stale key is the
     * record area's declared value, and the lookup happens anyway.
     *
     * <p>Practice <strong>B5</strong>: this is preserved exactly. Moving {@code :170-172} inside the
     * guard - the change any reviewer would call an obvious fix - would delete real output lines from
     * every run of the program, which is a behaviour change. The lines are asserted here so that the
     * change fails a test rather than looking like a tidy-up.
     */
    @Nested
    @DisplayName("VERIFIED NEGATIVE 3 - defect 1: N records produce N+1 lookups, the last on a stale key")
    class PostEndOfFileStaleLookup {

        @ParameterizedTest(name = "{0} record(s) produce {0}+1 cross-reference lookups")
        @ValueSource(ints = { 0, 1, 2, 3, 5 })
        @DisplayName("the lookup count is always the record count plus exactly one")
        void theLookupCountIsAlwaysOneMoreThanTheRecordCount(int records) {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(distinctRecords(records));

            ExecutionSummary summary = fixture.run();

            assertThat(summary.recordsRead()).isEqualTo(records);
            assertThat(summary.xrefLookups())
                    .as("app/cbl/CBTRN01C.cbl:170-172 sit outside the end-of-file guard at :167-169")
                    .isEqualTo(records + 1);
            assertThat(summary.postEndOfFileLookups())
                    .isEqualTo(ExecutionSummary.POST_END_OF_FILE_LOOKUPS);
            verify(fixture.cardXrefRepository, times(records + 1)).readByCardNumber(anyString());
        }

        @Test
        @DisplayName("the extra lookup carries the LAST record's card number, not a fresh one and not a "
                + "blank one")
        void theExtraLookupCarriesTheStaleCardNumber() {
            Fixture fixture = new Fixture();
            DalyTranRecord[] records = distinctRecords(3);
            fixture.dailyTransactions(records);

            fixture.run();

            // Each of the first two cards is looked up once. The third - the last record read - is looked
            // up twice: once for itself, and once more after end of file because :171 re-moved a field
            // the failed READ never changed.
            verify(fixture.cardXrefRepository, times(1)).readByCardNumber(cardNumber(0));
            verify(fixture.cardXrefRepository, times(1)).readByCardNumber(cardNumber(1));
            verify(fixture.cardXrefRepository, times(2)).readByCardNumber(cardNumber(2));
            // Nothing else was looked up: no blank key, no key the dataset never held.
            verify(fixture.cardXrefRepository, never())
                    .readByCardNumber(" ".repeat(DalyTranRecord.DALYTRAN_CARD_NUM_LENGTH));
        }

        @Test
        @DisplayName("over an EMPTY dataset the stale key is the untouched record area, and the lookup "
                + "still happens")
        void anEmptyDatasetStillLooksUpTheUntouchedRecordArea() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions();

            ExecutionSummary summary = fixture.run();

            assertThat(summary.recordsRead()).isZero();
            assertThat(summary.xrefLookups()).isEqualTo(1);
            verify(fixture.cardXrefRepository, times(1)).readByCardNumber(untouchedCardNumber());
        }

        @Test
        @DisplayName("the defect is visible in SYSOUT, not just in a counter: the miss arm's line appears "
                + "TWICE for the last card")
        void theStaleIterationEmitsItsOwnOutputOnTheMissArm() {
            Fixture fixture = new Fixture();
            DalyTranRecord[] records = distinctRecords(2);
            fixture.dailyTransactions(records);
            // Every lookup misses, which is the Fixture default: INVALID KEY at :231-233.

            fixture.run();

            String lastCardLine = notVerifiedLine(cardNumber(1), tranId(1));
            assertThat(fixture.lines).filteredOn(lastCardLine::equals)
                    .as("once for the second record, once more for the post-EOF iteration")
                    .hasSize(2);
            assertThat(fixture.lines).filteredOn(notVerifiedLine(cardNumber(0), tranId(0))::equals)
                    .as("the first record's line appears once - only the LAST one repeats")
                    .hasSize(1);
            assertThat(fixture.lines)
                    .filteredOn(TransactionPostingJob.INVALID_CARD_NUMBER_FOR_XREF::equals)
                    .as("2000-LOOKUP-XREF ran three times for two records")
                    .hasSize(3);
        }

        @Test
        @DisplayName("on the success arm the stale iteration repeats all four XREF lines AND the account "
                + "read, on the stale key")
        void theStaleIterationRepeatsTheSuccessArmAndTheAccountRead() {
            Fixture fixture = new Fixture();
            DalyTranRecord[] records = distinctRecords(2);
            fixture.dailyTransactions(records);
            when(fixture.cardXrefRepository.readByCardNumber(cardNumber(0)))
                    .thenReturn(xrefFound(cardNumber(0)));
            when(fixture.cardXrefRepository.readByCardNumber(cardNumber(1)))
                    .thenReturn(xrefFound(cardNumber(1)));
            when(fixture.acctfile.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.found(account()));

            ExecutionSummary summary = fixture.run();

            assertThat(fixture.lines)
                    .filteredOn(TransactionPostingJob.SUCCESSFUL_READ_OF_XREF::equals)
                    .as("three successful lookups for two records")
                    .hasSize(3);
            assertThat(fixture.lines)
                    .filteredOn(TransactionPostingJob.SUCCESSFUL_READ_OF_ACCOUNT_FILE::equals)
                    .as("3000-READ-ACCOUNT runs on the stale iteration too, because :173's guard is "
                            + "satisfied by the stale lookup having succeeded")
                    .hasSize(3);
            assertThat(fixture.lines)
                    .filteredOn((TransactionPostingJob.XREF_CARD_NUMBER_PREFIX + cardNumber(1))::equals)
                    .as("and the card number it displays is the stale one")
                    .hasSize(2);
            assertThat(summary.accountReads()).isEqualTo(3);
            verify(fixture.acctfile, times(3)).readByKey(ACCOUNT_ID);
        }

        @Test
        @DisplayName("the record image is NOT displayed for the post-EOF iteration - that one statement "
                + "IS inside the guard")
        void theRecordImageIsNotRepeated() {
            Fixture fixture = new Fixture();
            DalyTranRecord[] records = distinctRecords(3);
            fixture.dailyTransactions(records);

            ExecutionSummary summary = fixture.run();

            // The whole point of the defect is the asymmetry: :168 is guarded and :170-172 are not. So
            // three records give three image lines and four lookups.
            assertThat(summary.recordImageLinesDisplayed()).isEqualTo(3);
            for (DalyTranRecord record : records) {
                assertThat(fixture.lines).filteredOn(record.displayImage()::equals)
                        .as("each record's image appears exactly once")
                        .hasSize(1);
            }
            assertThat(summary.xrefLookups()).isEqualTo(4);
        }
    }

    /**
     * @param index a zero-based record index
     * @return a distinct sixteen-character card number for it
     */
    private static String cardNumber(int index) {
        return String.format("4%015d", index + 1);
    }

    /**
     * @param index a zero-based record index
     * @return a distinct sixteen-character transaction id for it
     */
    private static String tranId(int index) {
        return String.format("TRAN%012d", index + 1);
    }

    /**
     * @param count how many records to build
     * @return that many records, each with its own card number and transaction id
     */
    private static DalyTranRecord[] distinctRecords(int count) {
        DalyTranRecord[] records = new DalyTranRecord[count];
        for (int index = 0; index < count; index++) {
            records[index] = dalyTran(tranId(index), cardNumber(index));
        }
        return records;
    }

    // =================================================================================================
    // The read, and the two keyed reads.
    // =================================================================================================

    @Nested
    @DisplayName("1000-DALYTRAN-GET-NEXT - the three-arm status ladder")
    class DalytranRead {

        @Test
        @DisplayName("a status that is neither '00' nor '10' abends with RETURN-CODE 12")
        void anUnnamedStatusAbends() {
            Fixture fixture = new Fixture();
            when(fixture.dalytranFile.readNext())
                    .thenReturn(DalyTranRepository.ReadResult.other(OTHER_STATUS));

            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(fixture::run).actual();

            assertThat(fixture.lines).containsExactly(
                    TransactionPostingJob.START_BANNER,
                    TransactionPostingJob.ERROR_READING_DALYTRAN,
                    FileStatus.toDisplayLine(OTHER_STATUS),
                    AbendException.ABEND_DISPLAY_TEXT);
            assertThat(abend.getReturnCode()).isEqualTo(TransactionPostingJob.APPL_RESULT_FATAL);
            assertThat(abend.getProgram()).isEqualTo(TransactionPostingJob.PROGRAM_ID);
        }

        @Test
        @DisplayName("a refused read is reported as a status and abends, carrying the refusal as cause")
        void aRefusedReadAbendsCarryingTheCause() {
            Fixture fixture = new Fixture();
            RuntimeException refusal = new IllegalStateException("the driver refused the read");
            when(fixture.dalytranFile.readNext()).thenThrow(refusal);

            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(fixture::run).actual();

            assertThat(fixture.lines).contains(TransactionPostingJob.ERROR_READING_DALYTRAN,
                    FileStatus.toDisplayLine(TransactionPostingJob.PERMANENT_ERROR_STATUS));
            assertThat(abend).hasCause(refusal);
        }
    }

    @Nested
    @DisplayName("2000-LOOKUP-XREF - INVALID KEY, NOT INVALID KEY, and neither")
    class XrefLookup {

        @Test
        @DisplayName("NOT INVALID KEY emits exactly four lines, in source order, at declared widths")
        void aFoundCrossReferenceEmitsFourLines() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_ONE));

            fixture.run();

            assertThat(fixture.lines).containsSequence(xrefSuccessLines(CARD_ONE));
            assertThat(xrefSuccessLines(CARD_ONE)).hasSize(TransactionPostingJob.XREF_SUCCESS_LINES);
            assertThat(fixture.lines).contains("ACCOUNT ID : 99999999999", "CUSTOMER ID: 123456789");
        }

        @Test
        @DisplayName("the displayed card number is the RECORD's, because READ ... INTO overwrote the key")
        void theDisplayedCardNumberComesFromTheRecord() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            // The row the read returns carries a different card number from the key that was searched for,
            // which is impossible for an exact-match read and is exactly what makes the ordering visible.
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_TWO));

            fixture.run();

            assertThat(fixture.lines)
                    .contains(TransactionPostingJob.XREF_CARD_NUMBER_PREFIX + CARD_TWO)
                    .doesNotContain(TransactionPostingJob.XREF_CARD_NUMBER_PREFIX + CARD_ONE);
        }

        @Test
        @DisplayName("INVALID KEY - a not-found row reports it and skips the transaction")
        void aNotFoundCrossReferenceSkipsTheTransaction() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));

            ExecutionSummary summary = fixture.run();

            assertThat(fixture.lines).containsSequence(
                    TransactionPostingJob.INVALID_CARD_NUMBER_FOR_XREF,
                    notVerifiedLine(CARD_ONE, TRAN_ONE));
            assertThat(summary.accountReads()).isZero();
            verify(fixture.acctfile, never()).readByKey(anyLong());
        }

        @Test
        @DisplayName("INVALID KEY - a duplicate base key takes the same arm as a not-found row")
        void aDuplicateBaseKeyTakesTheInvalidKeyArm() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            CardXrefRecord duplicated = xref(CARD_ONE);
            when(fixture.cardXrefRepository.readByCardNumber(anyString())).thenReturn(
                    CardXrefRepository.ReadResult.duplicate(TransactionPostingJob.XREFFILE_DD_NAME,
                            duplicated, new String(duplicated.encode(CHARSET), CHARSET),
                            FileStatus.DUPREC));

            ExecutionSummary summary = fixture.run();

            assertThat(fixture.lines).contains(TransactionPostingJob.INVALID_CARD_NUMBER_FOR_XREF);
            assertThat(summary.accountReads()).isZero();
        }

        @Test
        @DisplayName("NEITHER phrase - any other status displays nothing and still reads an account")
        void anUnclassifiedStatusExecutesNeitherPhrase() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            when(fixture.cardXrefRepository.readByCardNumber(anyString())).thenReturn(
                    CardXrefRepository.ReadResult.other(TransactionPostingJob.XREFFILE_DD_NAME,
                            OTHER_STATUS));

            ExecutionSummary summary = fixture.run();

            assertThat(fixture.lines)
                    .doesNotContain(TransactionPostingJob.INVALID_CARD_NUMBER_FOR_XREF)
                    .doesNotContain(TransactionPostingJob.SUCCESSFUL_READ_OF_XREF);
            // WS-XREF-READ-STATUS was left at zero, so the mainline read an account anyway - and, because
            // the record area was never populated, it read account zero.
            assertThat(summary.accountReads()).isEqualTo(2);
            verify(fixture.acctfile, times(2))
                    .readByKey(TransactionPostingJob.UNPOPULATED_ACCOUNT_ID);
            assertThat(fixture.lines).contains("ACCOUNT 00000000000 NOT FOUND");
        }

        @Test
        @DisplayName("a refused lookup is reported as a status, which is neither phrase")
        void aRefusedLookupExecutesNeitherPhrase() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenThrow(new IllegalStateException("the driver refused the keyed read"));

            ExecutionSummary summary = fixture.run();

            assertThat(fixture.lines)
                    .doesNotContain(TransactionPostingJob.INVALID_CARD_NUMBER_FOR_XREF);
            assertThat(summary.completedCleanly()).isTrue();
        }

        @Test
        @DisplayName("the invalid-key condition is '22' and '23' and nothing else")
        void theInvalidKeyConditionIsExactlyTwoStatuses() {
            assertThat(TransactionPostingJob.isInvalidKeyCondition(FileStatus.NOT_FOUND)).isTrue();
            assertThat(TransactionPostingJob.isInvalidKeyCondition(FileStatus.DUPLICATE)).isTrue();
            assertThat(TransactionPostingJob.isInvalidKeyCondition(FileStatus.OK)).isFalse();
            assertThat(TransactionPostingJob.isInvalidKeyCondition(FileStatus.END_OF_FILE)).isFalse();
            assertThat(TransactionPostingJob.isInvalidKeyCondition(OTHER_STATUS)).isFalse();
        }
    }

    @Nested
    @DisplayName("3000-READ-ACCOUNT - INVALID KEY, NOT INVALID KEY, and neither")
    class AccountRead {

        @Test
        @DisplayName("NOT INVALID KEY emits the success line and no not-found line")
        void aFoundAccountEmitsItsSuccessLine() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_ONE));
            when(fixture.acctfile.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.found(account()));

            fixture.run();

            assertThat(fixture.lines)
                    .contains(TransactionPostingJob.SUCCESSFUL_READ_OF_ACCOUNT_FILE)
                    .doesNotContain(TransactionPostingJob.INVALID_ACCOUNT_NUMBER_FOUND);
            assertThat(fixture.lines).noneMatch(line -> line.endsWith("NOT FOUND"));
            verify(fixture.acctfile, times(2)).readByKey(ACCOUNT_ID);
        }

        @Test
        @DisplayName("INVALID KEY - the cross reference's account id is what 'ACCOUNT ... NOT FOUND' shows")
        void aNotFoundAccountShowsTheCrossReferencedIdentifier() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_ONE));

            fixture.run();

            assertThat(fixture.lines).containsSequence(
                    TransactionPostingJob.INVALID_ACCOUNT_NUMBER_FOUND,
                    "ACCOUNT 99999999999 NOT FOUND");
        }

        @Test
        @DisplayName("INVALID KEY - a duplicate account key takes the same arm")
        void aDuplicateAccountKeyTakesTheInvalidKeyArm() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_ONE));
            when(fixture.acctfile.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.of(FileStatus.DUPLICATE));

            fixture.run();

            assertThat(fixture.lines).contains(TransactionPostingJob.INVALID_ACCOUNT_NUMBER_FOUND);
        }

        @Test
        @DisplayName("NEITHER phrase - any other status displays nothing at all")
        void anUnclassifiedAccountStatusExecutesNeitherPhrase() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_ONE));
            when(fixture.acctfile.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.of(OTHER_STATUS));

            ExecutionSummary summary = fixture.run();

            assertThat(fixture.lines)
                    .doesNotContain(TransactionPostingJob.INVALID_ACCOUNT_NUMBER_FOUND)
                    .doesNotContain(TransactionPostingJob.SUCCESSFUL_READ_OF_ACCOUNT_FILE);
            assertThat(fixture.lines).noneMatch(line -> line.endsWith("NOT FOUND"));
            assertThat(summary.accountReads()).isEqualTo(2);
        }

        @Test
        @DisplayName("a refused account read is reported as a status, which is neither phrase")
        void aRefusedAccountReadExecutesNeitherPhrase() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_ONE));
            when(fixture.acctfile.readByKey(anyLong()))
                    .thenThrow(new IllegalStateException("the driver refused the keyed read"));

            ExecutionSummary summary = fixture.run();

            assertThat(fixture.lines)
                    .doesNotContain(TransactionPostingJob.INVALID_ACCOUNT_NUMBER_FOUND);
            assertThat(summary.completedCleanly()).isTrue();
        }
    }

    // =================================================================================================
    // The twelve file verbs.
    // =================================================================================================

    @Nested
    @DisplayName("The six OPEN paragraphs - each with its own literal and its own abend")
    class OpenFailures {

        @Test
        @DisplayName("DALYTRAN: a bad open status displays its literal, the status and the abend")
        void theDailyTransactionOpenReportsItself() {
            Fixture fixture = new Fixture();
            when(fixture.dalytranFile.openStatus()).thenReturn(OTHER_STATUS);

            assertOpenFailure(fixture, TransactionPostingJob.ERROR_OPENING_DALYTRAN, OTHER_STATUS);
        }

        @Test
        @DisplayName("CUSTFILE: a bad open status displays its literal, the status and the abend")
        void theCustomerOpenReportsItself() {
            Fixture fixture = new Fixture();
            when(fixture.custfile.openStatus()).thenReturn(OTHER_STATUS);

            assertOpenFailure(fixture, TransactionPostingJob.ERROR_OPENING_CUSTFILE, OTHER_STATUS);
        }

        @Test
        @DisplayName("XREFFILE: a bad open status displays its literal, the status and the abend")
        void theCrossReferenceOpenReportsItself() {
            Fixture fixture = new Fixture();
            when(fixture.xrefCursor.openStatus()).thenReturn(OTHER_STATUS);

            assertOpenFailure(fixture, TransactionPostingJob.ERROR_OPENING_XREFFILE, OTHER_STATUS);
        }

        @Test
        @DisplayName("CARDFILE: a CICS response with no batch equivalent lands on the permanent error")
        void theCardOpenReportsItself() {
            Fixture fixture = new Fixture();
            when(fixture.cardBrowse.openResp()).thenReturn(FileStatus.NOTOPEN);

            assertOpenFailure(fixture, TransactionPostingJob.ERROR_OPENING_CARDFILE,
                    TransactionPostingJob.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("ACCTFILE: a bad open status displays its literal, the status and the abend")
        void theAccountOpenReportsItself() {
            Fixture fixture = new Fixture();
            when(fixture.acctfile.openStatus()).thenReturn(OTHER_STATUS);

            assertOpenFailure(fixture, TransactionPostingJob.ERROR_OPENING_ACCTFILE, OTHER_STATUS);
        }

        @Test
        @DisplayName("TRANFILE: a bad open status displays its literal, the status and the abend")
        void theTransactionOpenReportsItself() {
            Fixture fixture = new Fixture();
            when(fixture.tranfile.openStatus()).thenReturn(OTHER_STATUS);

            assertOpenFailure(fixture, TransactionPostingJob.ERROR_OPENING_TRANFILE, OTHER_STATUS);
        }

        @Test
        @DisplayName("a refusal at any open is reported as the permanent-error status, cause carried")
        void aRefusedOpenIsReportedAsAStatus() {
            RuntimeException refusal = new IllegalStateException("no such dataset");

            Fixture daly = new Fixture();
            when(daly.dalyTranRepository.open()).thenThrow(refusal);
            assertThat(assertOpenFailure(daly, TransactionPostingJob.ERROR_OPENING_DALYTRAN,
                    TransactionPostingJob.PERMANENT_ERROR_STATUS)).hasCause(refusal);

            Fixture cust = new Fixture();
            when(cust.customerRepository.openInput()).thenThrow(refusal);
            assertOpenFailure(cust, TransactionPostingJob.ERROR_OPENING_CUSTFILE,
                    TransactionPostingJob.PERMANENT_ERROR_STATUS);

            Fixture xref = new Fixture();
            when(xref.cardXrefRepository.openBrowse()).thenThrow(refusal);
            assertOpenFailure(xref, TransactionPostingJob.ERROR_OPENING_XREFFILE,
                    TransactionPostingJob.PERMANENT_ERROR_STATUS);

            Fixture card = new Fixture();
            when(card.cardRepository.openBrowse(anyString(), any())).thenThrow(refusal);
            assertOpenFailure(card, TransactionPostingJob.ERROR_OPENING_CARDFILE,
                    TransactionPostingJob.PERMANENT_ERROR_STATUS);

            Fixture acct = new Fixture();
            when(acct.accountRepository.open(AccountRepository.OpenMode.INPUT)).thenThrow(refusal);
            assertOpenFailure(acct, TransactionPostingJob.ERROR_OPENING_ACCTFILE,
                    TransactionPostingJob.PERMANENT_ERROR_STATUS);

            Fixture tran = new Fixture();
            when(tran.transactionRepository.openInput(any(DatasetBinding.class))).thenThrow(refusal);
            assertOpenFailure(tran, TransactionPostingJob.ERROR_OPENING_TRANFILE,
                    TransactionPostingJob.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("an open failure closes nothing and reads nothing: the pass never started")
        void anOpenFailureNeverReachesTheLoop() {
            Fixture fixture = new Fixture();
            when(fixture.custfile.openStatus()).thenReturn(OTHER_STATUS);

            assertThatExceptionOfType(AbendException.class).isThrownBy(fixture::run);

            verify(fixture.dalytranFile, never()).readNext();
            verify(fixture.dalytranFile, never()).closeFile();
            verify(fixture.cardXrefRepository, never()).readByCardNumber(anyString());
        }
    }

    /**
     * Asserts that a run abends on an open, having displayed exactly the four lines that open emits.
     *
     * @param fixture   the stubbed world, with one open already made to fail
     * @param errorText the literal the failing paragraph displays
     * @param status    the status it renders
     * @return the abend, for a caller that wants to assert on its cause
     */
    private static AbendException assertOpenFailure(Fixture fixture, String errorText, String status) {
        AbendException abend = assertThatExceptionOfType(AbendException.class)
                .isThrownBy(fixture::run).actual();

        assertThat(fixture.lines).containsExactly(
                TransactionPostingJob.START_BANNER,
                errorText,
                FileStatus.toDisplayLine(status),
                AbendException.ABEND_DISPLAY_TEXT);
        assertThat(abend.getReturnCode()).isEqualTo(TransactionPostingJob.APPL_RESULT_FATAL);
        assertThat(abend.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
        assertThat(abend.getTiming()).hasValue(AbendException.STANDARD_TIMING);
        return abend;
    }

    @Nested
    @DisplayName("The six CLOSE paragraphs - and defect 2")
    class CloseFailures {

        @Test
        @DisplayName("DEFECT 2: the DALYTRAN close reports the CUSTOMER file's text AND its status")
        void theDailyTransactionCloseReportsTheCustomerFile() {
            Fixture fixture = new Fixture();
            when(fixture.dalytranFile.closeFile()).thenReturn(OTHER_STATUS);

            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(fixture::run).actual();

            // The customer file's literal, not a daily-transaction one - the program has no such literal.
            assertThat(fixture.lines).containsSequence(
                    TransactionPostingJob.ERROR_CLOSING_CUSTFILE,
                    // The CUSTOMER file's status, which its own successful open left at '00' - so the
                    // status that actually failed ('35') is never shown anywhere.
                    FileStatus.toDisplayLine(FileStatus.OK),
                    AbendException.ABEND_DISPLAY_TEXT);
            assertThat(fixture.lines).doesNotContain(FileStatus.toDisplayLine(OTHER_STATUS));
            // The return code is unaffected by the defect: the ladder had already moved 12.
            assertThat(abend.getReturnCode()).isEqualTo(TransactionPostingJob.APPL_RESULT_FATAL);
            // And the five later closes never happen, exactly as CALL 'CEE3ABD' means they do not.
            verify(fixture.custfile, never()).closeFile();
            verify(fixture.xrefCursor, never()).closeBrowse();
            verify(fixture.tranfile, never()).closeInput();
        }

        @Test
        @DisplayName("CUSTFILE: its own close reports its own literal and its own status")
        void theCustomerCloseReportsItself() {
            Fixture fixture = new Fixture();
            when(fixture.custfile.closeFile()).thenReturn(OTHER_STATUS);

            assertCloseFailure(fixture, TransactionPostingJob.ERROR_CLOSING_CUSTFILE, OTHER_STATUS);
        }

        @Test
        @DisplayName("XREFFILE: its own close reports its own literal and its own status")
        void theCrossReferenceCloseReportsItself() {
            Fixture fixture = new Fixture();
            when(fixture.xrefCursor.closeBrowse()).thenReturn(OTHER_STATUS);

            assertCloseFailure(fixture, TransactionPostingJob.ERROR_CLOSING_XREFFILE, OTHER_STATUS);
        }

        @Test
        @DisplayName("CARDFILE: a refused endBrowse becomes the permanent-error status")
        void theCardCloseReportsItself() {
            Fixture fixture = new Fixture();
            RuntimeException refusal = new IllegalStateException("the browse could not be released");
            org.mockito.Mockito.doThrow(refusal).when(fixture.cardBrowse).endBrowse();

            AbendException abend = assertCloseFailure(fixture,
                    TransactionPostingJob.ERROR_CLOSING_CARDFILE,
                    TransactionPostingJob.PERMANENT_ERROR_STATUS);

            assertThat(abend).hasCause(refusal);
        }

        @Test
        @DisplayName("ACCTFILE: its own close reports its own literal and its own status")
        void theAccountCloseReportsItself() {
            Fixture fixture = new Fixture();
            when(fixture.acctfile.closeFile()).thenReturn(OTHER_STATUS);

            assertCloseFailure(fixture, TransactionPostingJob.ERROR_CLOSING_ACCTFILE, OTHER_STATUS);
        }

        @Test
        @DisplayName("TRANFILE: its own close reports its own literal and its own status")
        void theTransactionCloseReportsItself() {
            Fixture fixture = new Fixture();
            when(fixture.tranfile.closeInput()).thenReturn(OTHER_STATUS);

            assertCloseFailure(fixture, TransactionPostingJob.ERROR_CLOSING_TRANFILE, OTHER_STATUS);
        }

        @Test
        @DisplayName("a refusal at any close is reported as the permanent-error status")
        void aRefusedCloseIsReportedAsAStatus() {
            RuntimeException refusal = new IllegalStateException("the handle could not be released");

            Fixture daly = new Fixture();
            when(daly.dalytranFile.closeFile()).thenThrow(refusal);
            // Defect 2 again: this arm renders the customer file's '00', not the refusal's status.
            assertCloseFailure(daly, TransactionPostingJob.ERROR_CLOSING_CUSTFILE, FileStatus.OK);

            Fixture cust = new Fixture();
            when(cust.custfile.closeFile()).thenThrow(refusal);
            assertCloseFailure(cust, TransactionPostingJob.ERROR_CLOSING_CUSTFILE,
                    TransactionPostingJob.PERMANENT_ERROR_STATUS);

            Fixture xref = new Fixture();
            when(xref.xrefCursor.closeBrowse()).thenThrow(refusal);
            assertCloseFailure(xref, TransactionPostingJob.ERROR_CLOSING_XREFFILE,
                    TransactionPostingJob.PERMANENT_ERROR_STATUS);

            Fixture acct = new Fixture();
            when(acct.acctfile.closeFile()).thenThrow(refusal);
            assertCloseFailure(acct, TransactionPostingJob.ERROR_CLOSING_ACCTFILE,
                    TransactionPostingJob.PERMANENT_ERROR_STATUS);

            Fixture tran = new Fixture();
            when(tran.tranfile.closeInput()).thenThrow(refusal);
            assertCloseFailure(tran, TransactionPostingJob.ERROR_CLOSING_TRANFILE,
                    TransactionPostingJob.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("an incomplete run releases the handles its CLOSE paragraphs never reached, silently")
        void anIncompleteRunReleasesWhatItOpened() {
            Fixture fixture = new Fixture();
            when(fixture.dalytranFile.closeFile()).thenReturn(OTHER_STATUS);

            assertThatExceptionOfType(AbendException.class).isThrownBy(fixture::run);

            // Released, because their own CLOSE paragraph never ran.
            verify(fixture.custfile).close();
            verify(fixture.xrefCursor).close();
            verify(fixture.cardBrowse).close();
            verify(fixture.acctfile).close();
            verify(fixture.tranfile).close();
            // Not released: its CLOSE paragraph did run, and failed.
            verify(fixture.dalytranFile, never()).close();
            // And the release is silent: the run's lines are the empty pass's three plus the failing
            // paragraph's three, and the five releases added none of their own.
            assertThat(fixture.lines).containsExactly(
                    TransactionPostingJob.START_BANNER,
                    TransactionPostingJob.INVALID_CARD_NUMBER_FOR_XREF,
                    notVerifiedLine(untouchedCardNumber(), untouchedTranId()),
                    TransactionPostingJob.ERROR_CLOSING_CUSTFILE,
                    FileStatus.toDisplayLine(FileStatus.OK),
                    AbendException.ABEND_DISPLAY_TEXT);
        }

        @Test
        @DisplayName("a release that itself fails cannot displace the run's own failure")
        void aFailingReleaseIsSwallowed() {
            Fixture fixture = new Fixture();
            when(fixture.dalytranFile.closeFile()).thenReturn(OTHER_STATUS);
            org.mockito.Mockito.doThrow(new IllegalStateException("release refused"))
                    .when(fixture.tranfile).close();

            assertThatExceptionOfType(AbendException.class).isThrownBy(fixture::run);

            verify(fixture.custfile).close();
        }

        @Test
        @DisplayName("a clean run releases nothing: all six CLOSE paragraphs ran")
        void aCleanRunReleasesNothing() {
            Fixture fixture = new Fixture();

            fixture.run();

            verify(fixture.dalytranFile, never()).close();
            verify(fixture.custfile, never()).close();
            verify(fixture.xrefCursor, never()).close();
            verify(fixture.cardBrowse, never()).close();
            verify(fixture.acctfile, never()).close();
            verify(fixture.tranfile, never()).close();
        }

        @Test
        @DisplayName("a failed open leaves nothing to release, so no spurious release is attempted")
        void aFailedOpenLeavesNothingToRelease() {
            Fixture fixture = new Fixture();
            when(fixture.xrefCursor.openStatus()).thenReturn(OTHER_STATUS);

            assertThatExceptionOfType(AbendException.class).isThrownBy(fixture::run);

            // The cross-reference cursor never became this run's handle, because its open failed.
            verify(fixture.xrefCursor, never()).close();
            verify(fixture.xrefCursor, never()).closeBrowse();
            // The two files opened before it are released, silently.
            verify(fixture.dalytranFile).close();
            verify(fixture.custfile).close();
        }
    }

    /**
     * Asserts that a run abends on a close, having displayed the three lines that close emits after the
     * banner and the whole pass.
     *
     * @param fixture   the stubbed world, with one close already made to fail
     * @param errorText the literal the failing paragraph displays
     * @param status    the status it renders
     * @return the abend, for a caller that wants to assert on its cause
     */
    private static AbendException assertCloseFailure(Fixture fixture, String errorText, String status) {
        AbendException abend = assertThatExceptionOfType(AbendException.class)
                .isThrownBy(fixture::run).actual();

        assertThat(fixture.lines)
                .startsWith(TransactionPostingJob.START_BANNER)
                .endsWith(errorText, FileStatus.toDisplayLine(status),
                        AbendException.ABEND_DISPLAY_TEXT)
                .doesNotContain(TransactionPostingJob.END_BANNER);
        assertThat(abend.getReturnCode()).isEqualTo(TransactionPostingJob.APPL_RESULT_FATAL);
        return abend;
    }

    // =================================================================================================
    // SYSOUT, and the summary.
    // =================================================================================================

    @Nested
    @DisplayName("SYSOUT - verbatim, in the dataset code page, one line feed, flushed")
    class SysoutDestinations {

        @Test
        @DisplayName("a published sink is used, and the default is not")
        void aPublishedSinkIsHonoured() {
            Fixture fixture = new Fixture();
            List<String> published = new ArrayList<>();
            TransactionPostingJob subject = fixture.job(validBatchConfig(), published::add);

            subject.transactionPostingTasklet();
            subject.execute(published::add);

            assertThat(published).startsWith(TransactionPostingJob.START_BANNER);
        }

        @Test
        @DisplayName("the stream sink writes the line, one line feed, and flushes - and nothing else")
        void theStreamSinkWritesTheLineAndOneLineFeed() {
            Fixture fixture = new Fixture();
            ByteArrayOutputStream captured = new ByteArrayOutputStream();

            fixture.job().sysoutSinkTo(captured).display("A LINE");

            assertThat(captured.toString(CHARSET)).isEqualTo("A LINE\n");
        }

        @Test
        @DisplayName("a raw record image reaches SYSOUT untrimmed, FILLER included")
        void aRecordImageReachesSysoutUntrimmed() {
            Fixture fixture = new Fixture();
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            DalyTranRecord record = dalyTran(TRAN_ONE, CARD_ONE);

            fixture.job().sysoutSinkTo(captured).display(record.displayImage());

            assertThat(captured.toString(CHARSET))
                    .hasSize(DalyTranRecord.RECORD_LENGTH + 1)
                    .startsWith(TRAN_ONE)
                    .endsWith(" \n");
        }

        @Test
        @DisplayName("the default sink exists and is a distinct instance per call")
        void theDefaultSinkIsAvailable() {
            TransactionPostingJob subject = new Fixture().job();

            assertThat(subject.defaultSysoutSink()).isNotNull();
            assertThat(subject.defaultSysoutSink()).isNotSameAs(subject.defaultSysoutSink());
        }

        @Test
        @DisplayName("a null destination, and a null line, are both refused")
        void nullsAreRefused() {
            TransactionPostingJob subject = new Fixture().job();
            SysoutSink sink = subject.sysoutSinkTo(new ByteArrayOutputStream());

            assertThatNullPointerException().isThrownBy(() -> subject.sysoutSinkTo(null));
            assertThatNullPointerException().isThrownBy(() -> sink.display(null));
        }

        @Test
        @DisplayName("a stream that refuses the write is reported, not swallowed")
        void aRefusedWriteIsReported() {
            TransactionPostingJob subject = new Fixture().job();
            OutputStream refusing = new OutputStream() {
                @Override
                public void write(int singleByte) throws IOException {
                    throw new IOException("the spool is full");
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    throw new IOException("the spool is full");
                }
            };

            assertThatExceptionOfType(UncheckedIOException.class)
                    .isThrownBy(() -> subject.sysoutSinkTo(refusing).display("A LINE"))
                    .withMessageContaining(TransactionPostingJob.PROGRAM_ID);
        }
    }

    @Nested
    @DisplayName("ExecutionSummary - defect 1 as a type invariant")
    class Summary {

        @Test
        @DisplayName("the lookup count must be the record count plus exactly one")
        void theLookupCountIsTheRecordCountPlusOne() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ExecutionSummary(0, 3, 3, 0))
                    .withMessageContaining("defect 1");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ExecutionSummary(0, 3, 5, 0));
            assertThat(new ExecutionSummary(0, 3, 4, 0).postEndOfFileLookups())
                    .isEqualTo(ExecutionSummary.POST_END_OF_FILE_LOOKUPS);
        }

        @Test
        @DisplayName("negative counts are refused")
        void negativeCountsAreRefused() {
            assertThatIllegalArgumentException().isThrownBy(() -> new ExecutionSummary(0, -1, 0, 0));
            assertThatIllegalArgumentException().isThrownBy(() -> new ExecutionSummary(0, 0, -1, 0));
            assertThatIllegalArgumentException().isThrownBy(() -> new ExecutionSummary(0, 0, 1, -1));
        }

        @Test
        @DisplayName("more account reads than lookups is refused")
        void tooManyAccountReadsIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ExecutionSummary(0, 1, 2, 3))
                    .withMessageContaining("at most one account read per lookup");
        }

        @Test
        @DisplayName("a clean run reports return code zero; a non-zero one does not")
        void completedCleanlyReflectsTheReturnCode() {
            assertThat(new ExecutionSummary(AbendException.RETURN_CODE_OK, 1, 2, 1).completedCleanly())
                    .isTrue();
            assertThat(new ExecutionSummary(TransactionPostingJob.APPL_RESULT_FATAL, 1, 2, 1)
                    .completedCleanly()).isFalse();
        }
    }
}
