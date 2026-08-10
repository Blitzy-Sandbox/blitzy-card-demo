package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.PhysicalSequence;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.config.DatasetUnitOfWork;
import com.vsergeychik.carddemo.statement.StatementGenerationJobA.DatasetUtilityPort;
import com.vsergeychik.carddemo.transaction.DateParmReader;
import com.vsergeychik.carddemo.transaction.TranCategoryRepository;
import com.vsergeychik.carddemo.transaction.TranReportWriter;
import com.vsergeychik.carddemo.transaction.TranTypeRepository;
import com.vsergeychik.carddemo.transaction.TransactionReportJob;
import com.vsergeychik.carddemo.transaction.TransactionReportJob.ExecutionSummary;
import com.vsergeychik.carddemo.transaction.TransactionReportJob.SysoutSink;
import com.vsergeychik.carddemo.transaction.TransactionRepository;
import com.vsergeychik.carddemo.transaction.model.TranCategoryRecord;
import com.vsergeychik.carddemo.transaction.model.TranRecord;
import com.vsergeychik.carddemo.transaction.model.TranReportLayouts;
import com.vsergeychik.carddemo.transaction.model.TranTypeRecord;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The twenty-case behavioural-parity gate for {@code app/cbl/CBTRN03C.cbl}, the transaction detail
 * report, against its Java translation {@link TransactionReportJob}.
 *
 * <h2>Baseline provenance - statically derived, never captured</h2>
 * <p>Every expected value in {@code parity/CBTRN03C/case01.json} through {@code case20.json} is
 * <strong>statically derived</strong>: produced by structured reading of each COBOL paragraph and
 * cross-checked against four authoritative sources - the byte layouts of {@code app/cpy/CVTRA05Y.cpy},
 * {@code CVTRA03Y.cpy}, {@code CVTRA04Y.cpy}, {@code CVACT03Y.cpy} and {@code CVTRA07Y.cpy}; the
 * {@code DD} and {@code DCB} contracts of {@code app/jcl/TRANREPT.jcl} and {@code app/proc/TRANREPT.prc};
 * the {@code DFHMDF} field definitions, which this batch program has none of; and the real ASCII
 * fixtures in {@code app/data/ASCII}. They are <strong>not</strong> captured from, recorded against or
 * replayed from any execution of the legacy COBOL, because executing it is empirically impossible in
 * this environment - no z/OS runtime, an indexed-file handler the available compiler reports as
 * disabled, and no Language Environment {@code CEE*} services, so {@code CEE3ABD} cannot be called at
 * all. This substitutes the <em>provenance</em> of the expected values and nothing else: twenty cases,
 * field-for-field diffing and the diff-count-equals-zero gate are all preserved, and the substitution
 * is escalated for confirmation rather than silently absorbed (risk R-A).
 *
 * <h2>Two source defects are reproduced deliberately, each with its own case</h2>
 * <ul>
 *   <li><strong>{@code NEXT SENTENCE} ends the whole loop</strong> - {@code app/cbl/CBTRN03C.cbl:177}.
 *       {@code NEXT SENTENCE} transfers control past the period ending the current sentence, and that
 *       period is the one closing the entire {@code PERFORM UNTIL ... END-PERFORM.} at {@code :206} -
 *       not the {@code END-IF} two lines below. So the first record whose {@code TRAN-PROC-TS (1:10)}
 *       falls outside the reporting range does not get skipped: it truncates the report and suppresses
 *       the page and grand totals at {@code :202-203} entirely. {@code case08} pins it firing on the
 *       first record and {@code case17} pins it firing on an empty input. Turning the break into a
 *       {@code continue} makes both fail. The complement is pinned too: {@code case06} keeps all five
 *       of its records inside the range, with the first and the last sitting <em>on</em> the inclusive
 *       boundaries, so the arm is never taken and the loop runs to end of file.</li>
 *   <li><strong>The end-of-file arm adds the last amount twice</strong> - {@code :197-203}. A COBOL
 *       {@code READ ... INTO} leaves its receiving area untouched {@code AT END}, so {@code TRAN-RECORD}
 *       still holds the last record read - whose {@code TRAN-AMT} {@code 1100-WRITE-TRANSACTION-REPORT}
 *       has already added to both accumulators at {@code :287-288}. Adding it again at {@code :200-201}
 *       overstates the final page total and, through {@code :297}, the grand total. {@code case07} pins
 *       the doubling numerically, {@code case06} pins it at the foot of a five-record run where the
 *       five amounts sum to 1,819.34 and both totals nonetheless read 1,880.53, and {@code case16} pins
 *       the high-order truncation it causes at the top of {@code PIC S9(09)V99}. Removing the addition
 *       makes all three fail.</li>
 * </ul>
 * <p>Neither is fixed here. Fixing either would change every report this program has ever produced,
 * which is a behaviour change and a parity violation (practice B5).
 *
 * <h2>The reporting range is a DATASET, never a PARM</h2>
 * <p>{@code app/jcl/TRANREPT.jcl:73-74} binds {@code //DATEPARM DD DSN=...DATEPARM} and the step
 * declares no {@code PARM} whatsoever, so {@code 0550-DATEPARM-READ} at {@code :220-243} is the only
 * source of {@code WS-START-DATE} and {@code WS-END-DATE}. Every case therefore seeds {@code DATEPARM}
 * as an input dataset and declares <strong>no job parameter at all</strong>, which
 * {@link #everyCaseTakesItsRangeFromTheDatasetAndNotFromAParm()} asserts for all twenty. The reader
 * decodes an 80-byte record right-truncated into the 21-byte {@code WS-DATEPARM-RECORD} receiver - two
 * {@code PIC X(10)} dates around a one-byte separator the program never reads - and its {@code '10'}
 * outcome sets {@code END-OF-FILE} to {@code 'Y'} <em>before any record is processed</em>, which
 * {@code TransactionReportJobTest} pins directly rather than through a fixture, because every case in
 * this folder seeds a range. {@code case09} spends its slot on the one arithmetic event no other case
 * reaches: the first {@code FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE)} page break at {@code :282}.
 * {@code WS-PAGE-SIZE} is {@code PIC 9(03) COMP-3 VALUE 20} at {@code :131-132}, and the break fires
 * before the seventeenth of seventeen records on one card - not before the first, because the
 * {@code WS-FIRST-TIME} block at {@code :275-280} runs ahead of the test and has already pushed the
 * counter from zero to four. It writes its page total <em>before</em> the fresh header block
 * ({@code :283} then {@code :284}) and leaves thirty records written against a counter of twenty-nine,
 * the difference being {@code 1110-WRITE-GRAND-TOTALS} at {@code :318-322} - the one paragraph in the
 * program that writes without incrementing.
 *
 * <h2>How it runs</h2>
 * <p>{@link ParityHarness} seeds each case's datasets, invokes the unit and captures a fingerprint;
 * {@link FieldDiffer} compares it field by field and the test asserts the diff count is zero. The unit
 * is reached through {@link TransactionReportJob#execute(SysoutSink, TranReportWriter.RecordSink)}, an
 * ordinary Java method: there is no batch job launcher, no job repository, no asynchronous executor,
 * no HTTP layer, no servlet-test harness, no application context, no database and no filesystem
 * between the assertion and the program body (gate G51). The {@link TranReportWriter} is <em>real</em>, so every
 * 133-byte record asserted here is bytes the production write path produced, and the code page is
 * always {@link ParityHarness#FIXTURE_CHARSET} stated explicitly rather than taken from the platform.
 *
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - so no user rule
 * governs this file; the binding standard is the enterprise-practice set B1-B12. The gates it enforces
 * directly are G15 (twenty cases, loudly), G16 (the 36-to-50 cross-reference pad), G18 (diff count
 * zero, per case), G20 and G21 (every report record 133 bytes with its {@code FILLER} intact), G22 and
 * G24 (no binary floating point, and truncation rather than rounding), G35 (the abend's return code),
 * G46 (no dataset name in Java), G47 (every file-status outcome per call site), G51 (no launcher and no
 * HTTP), G52 (no wildcard import), G53 (no mutable static state) and G54 (non-interactive).
 */
@DisplayName("CBTRN03C parity - the transaction detail report, both source defects included")
class CBTRN03CParityTest {

    /** The program this class gates, which is also its {@code parity/<PROGRAM>/} directory. */
    private static final String PROGRAM = "CBTRN03C";

    /** The code page every case is seeded and compared under, named rather than defaulted. */
    private static final Charset ASCII = ParityHarness.FIXTURE_CHARSET;

    /**
     * The dataset the fingerprint's report records are attributed to - the {@code DD} name
     * {@code app/cbl/CBTRN03C.cbl:51} assigns {@code REPORT-FILE} to.
     */
    private static final String TRANREPT = TransactionReportJob.TRANREPT_DD_NAME;

    /**
     * {@code 01 FD-REPTFILE-REC PIC X(133)} - {@code app/cbl/CBTRN03C.cbl:84-85}.
     *
     * <p>The report's {@code FD} record is a single alphanumeric span, and it has to be: seven
     * differently shaped layouts are moved into it - the name header, the blank line, both transaction
     * headers, the detail line and the page, account and grand totals - and a dataset carries one
     * layout for all of its rows. Addressing the record as the {@code FD} the COBOL declares is
     * therefore both the faithful choice and the only workable one, and it makes each expectation the
     * whole 133 bytes rather than a subset that leaves the rest of the line unasserted.
     */
    private static final RecordLayout FD_REPTFILE_REC = RecordLayout.of(
            TranReportWriter.RECORD_LENGTH,
            FieldSpan.alphanumeric("FD-REPTFILE-REC", 0, TranReportWriter.RECORD_LENGTH));

    /**
     * The physical-record ordinal a physical-sequential read is ordered by, as
     * {@code application-test.yml} configures it. Carried only so the collaborators this class
     * constructs are built exactly as the container builds them.
     */
    private static final PhysicalSequence ORDINAL = PhysicalSequence.of("_ROWID_");

    /** A dataset name for {@code TRANFILE}. Never a real one, and never a production mainframe DSN. */
    private static final String TEST_TRANFILE = "TEST.TRANSACT.DALY";

    /** A dataset name for the transaction master the unload step reads. */
    private static final String TEST_MASTER = "TEST.TRANSACT.VSAM.KSDS";

    /** A dataset name for the backup generation the unload writes and the sort reads back. */
    private static final String TEST_BACKUP = "TEST.TRANSACT.BKUP";

    /** A dataset name for the 133-byte report. */
    private static final String TEST_TRANREPT = "TEST.TRANREPT";

    /** The dataset both {@code CARDXREF} and {@code CCXREF} resolve to here. */
    private static final String TEST_CARDXREF = "TEST.CARDXREF";

    /** A dataset name for {@code TRANTYPE}. */
    private static final String TEST_TRANTYPE = "TEST.TRANTYPE";

    /** A dataset name for {@code TRANCATG}. */
    private static final String TEST_TRANCATG = "TEST.TRANCATG";

    /** A dataset name for {@code DATEPARM}. */
    private static final String TEST_DATEPARM = "TEST.DATEPARM";

    /**
     * A well-formed two-character file status that is neither {@code '00'}, nor the {@code '10'} end of
     * file, nor the {@code '23'} the three {@code INVALID KEY} arms move into {@code IO-STATUS}.
     *
     * <p>It exists because the {@code WHEN OTHER} arm of every {@code EVALUATE} in this program, and the
     * {@code ELSE} of every open, write and close ladder, is reachable only from a backend condition -
     * and {@link ParityCase} forbids {@code screenRequest}, and therefore
     * {@link ParityCase.ForcedOutcome}, on a {@link ParityCase.UnitKind#BATCH_JOB} case. So those arms
     * are pinned by the {@code @Test} methods at the foot of this class rather than by a case fixture,
     * and this is the status they use.
     */
    private static final String BACKEND_REFUSAL_STATUS = "35";

    // =============================================================================================
    // THE GATE. Twenty cases, and a diff count of zero on every one of them.
    // =============================================================================================

    /**
     * The twenty cases, loaded from {@code parity/CBTRN03C/case01.json} through {@code case20.json}.
     *
     * <p>{@link ParityHarness#casesOf(String)} is what makes the count loud: it refuses a set that is
     * short of {@value ParityHarness#CASES_PER_PROGRAM}, naming each absent case, and it equally
     * refuses a directory holding anything the twenty do not name - a {@code case21.json} that could
     * never load, or a {@code case07.JSON} whose casing looks right at a glance. A gate stated as "the
     * diff count is zero across all twenty cases" is satisfied vacuously by a set of four, so the set
     * is proved exact before a single case runs.
     *
     * @return one argument pair per case - its identifier, for a legible test name, and the case itself
     */
    private static Stream<Arguments> cases() {
        return ParityHarness.casesOf(PROGRAM).stream()
                .map(parityCase -> Arguments.of(parityCase.caseId(), parityCase));
    }

    /**
     * Runs one case and asserts that the differ found <strong>nothing</strong>.
     *
     * <p>The assertion is on the count rather than on any individual difference, and the differ's own
     * rendering is attached as the description so a failure reads as "3 differences on case07" with all
     * three shown - dataset, row index, field, offset, expected, observed and why it matters - instead
     * of a bare number. {@link FieldDiffer.DiffResult#render()} masks every classified span, so a
     * failure is diagnosable without putting record contents into a build log.
     *
     * @param caseId     the case identifier, present so the test name names the fixture to open
     * @param parityCase the case whose expectations are authoritative
     */
    @ParameterizedTest(name = "{0} - diff count must be zero")
    @MethodSource("cases")
    @DisplayName("every case is field-for-field identical to the statically derived baseline (G18)")
    void everyCaseProducesNoDifference(String caseId, ParityCase parityCase) {
        FieldDiffer.DiffResult result = ParityHarness.usAscii()
                .judge(parityCase, ParityCase.UnitKind.BATCH_JOB, new ReportRun());

        assertThat(result.count())
                .as("%s/%s must produce no difference. A module is not complete until its diff count "
                        + "is zero across all %d of its cases, so one difference here means this "
                        + "module is incomplete rather than nearly done.%n%s",
                        PROGRAM, caseId, ParityHarness.CASES_PER_PROGRAM, result.render())
                .isZero();
        assertThat(result.isClean())
                .as("%s/%s reported a clean result inconsistent with its own count of %d",
                        PROGRAM, caseId, result.count())
                .isTrue();
    }

    // =============================================================================================
    // The shape of the case set itself. A gate cannot be trusted by a suite that never checks it.
    // =============================================================================================

    /**
     * Exactly twenty cases exist, named {@code case01} through {@code case20}, and every one of them
     * belongs to this program.
     */
    @Test
    @DisplayName("the program declares exactly twenty cases, case01 through case20 (G15)")
    void theProgramDeclaresExactlyTwentyCases() {
        List<ParityCase> loaded = ParityHarness.casesOf(PROGRAM);

        assertThat(loaded)
                .as("the parity gate for %s is stated as twenty cases; a shorter set is not a smaller "
                        + "gate, it is a gate that stops asking questions", PROGRAM)
                .hasSize(ParityHarness.CASES_PER_PROGRAM);
        assertThat(loaded).extracting(ParityCase::caseId)
                .containsExactly(expectedCaseIds());
        assertThat(loaded).allSatisfy(parityCase ->
                assertThat(parityCase.program())
                        .as("a case under parity/%s/ that names another program would be judged "
                                + "against the wrong source", PROGRAM)
                        .isEqualTo(PROGRAM));
        assertThat(loaded).allSatisfy(parityCase ->
                assertThat(parityCase.description())
                        .as("%s must say which COBOL branch it pins, so parity coverage is auditable "
                                + "without re-reading the COBOL", parityCase.caseId())
                        .isNotBlank());
    }

    /**
     * Every case is a batch case that reads its reporting range from the {@code DATEPARM} dataset and
     * declares no job parameter - which is the whole of the {@code PARM}-versus-dataset distinction,
     * asserted rather than merely documented.
     */
    @Test
    @DisplayName("the reporting range comes from the DATEPARM dataset, never from a PARM")
    void everyCaseTakesItsRangeFromTheDatasetAndNotFromAParm() {
        for (ParityCase parityCase : ParityHarness.casesOf(PROGRAM)) {
            assertThat(parityCase.unitKind())
                    .as("%s drives a Spring Batch tasklet, so its unit kind is BATCH_JOB",
                            parityCase.caseId())
                    .isEqualTo(ParityCase.UnitKind.BATCH_JOB);
            assertThat(parityCase.jobParameters())
                    .as("%s declares a job parameter, but app/jcl/TRANREPT.jcl:59-80 gives STEP10R no "
                            + "PARM at all and 0550-DATEPARM-READ takes the range from a dataset. A "
                            + "case that passed the range in as a parameter would assert the opposite "
                            + "of what the JCL says", parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.inputs())
                    .as("%s must seed DD %s, because it is the only source of WS-START-DATE and "
                            + "WS-END-DATE", parityCase.caseId(), DateParmReader.DD_NAME)
                    .containsKey(DateParmReader.DD_NAME);
            assertThat(parityCase.screenRequest())
                    .as("%s is a batch case; a screen request would mean an online invocation",
                            parityCase.caseId())
                    .isNull();
            assertThat(parityCase.expectedResponse())
                    .as("%s is a batch case; an online response has nothing to compare against",
                            parityCase.caseId())
                    .isNull();
        }
    }

    /** @return {@code case01} through {@code case20}, in order */
    private static String[] expectedCaseIds() {
        String[] identifiers = new String[ParityHarness.CASES_PER_PROGRAM];
        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            identifiers[ordinal - 1] = ParityHarness.caseId(ordinal);
        }
        return identifiers;
    }

    // =============================================================================================
    // Record geometry. app/jcl/TRANREPT.jcl:78 declares DCB=(LRECL=133,RECFM=FB,BLKSIZE=0), and every
    // one of the seven layouts app/cpy/CVTRA07Y.cpy defines has to arrive at that width.
    // =============================================================================================

    /**
     * Every expected report record, in every case, on both channels, is exactly 133 characters.
     *
     * <p>Asserted over the fixtures rather than only over a run, because a case whose expectation was
     * authored at 132 or 134 would be comparing against a width the dataset can never report and the
     * resulting difference would look like a translation defect.
     */
    @Test
    @DisplayName("every expected report record is exactly 133 bytes, on both channels (G20)")
    void everyExpectedReportRecordIsOneHundredAndThirtyThreeBytes() {
        for (ParityCase parityCase : ParityHarness.casesOf(PROGRAM)) {
            List<ParityCase.ExpectedRecord> pinned = new ArrayList<>(parityCase.expectedWrites());
            pinned.addAll(parityCase.expectedFinalState());
            for (ParityCase.ExpectedRecord record : pinned) {
                assertThat(record.dataset())
                        .as("%s pins a record on a dataset this program does not write; %s is its only "
                                + "output", parityCase.caseId(), TRANREPT)
                        .isEqualTo(TRANREPT);
                assertThat(record.expectedBytes())
                        .as("%s row %d must pin the whole record: seven differently shaped layouts "
                                + "share this FD, so a field subset would leave most of the line "
                                + "unasserted", parityCase.caseId(), record.rowIndex())
                        .isNotNull();
                assertThat(record.expectedBytes().length())
                        .as("%s row %d is %d character(s); app/jcl/TRANREPT.jcl:78 declares LRECL=133",
                                parityCase.caseId(), record.rowIndex(),
                                record.expectedBytes().length())
                        .isEqualTo(TranReportWriter.RECORD_LENGTH);
            }
            assertThat(parityCase.expectedDatasets())
                    .as("%s must pin %s at dataset level, because a dataset created and left empty is "
                            + "the one thing no row expectation can describe", parityCase.caseId(),
                            TRANREPT)
                    .isNotEmpty()
                    .allSatisfy(expectation -> {
                        assertThat(expectation.dataset()).isEqualTo(TRANREPT);
                        assertThat(expectation.recordLength())
                                .as("%s must pin %s's declared width, so an empty report is still "
                                        + "identifiable as a 133-byte report", parityCase.caseId(),
                                        TRANREPT)
                                .isEqualTo(TranReportWriter.RECORD_LENGTH);
                    });
        }
    }

    /**
     * The two layouts that reach {@code TRANREPT} at their natural width, and the five that are padded
     * to it.
     *
     * <p>{@code TRANSACTION-HEADER-2} is declared {@code PIC X(133) VALUE ALL '-'} - already the record
     * width, so it passes through as 133 hyphens with no space anywhere - and {@code WS-BLANK-LINE} is
     * {@code PIC X(133) VALUE SPACES} at {@code :133}, so it passes through as 133 spaces. The other
     * five are narrower and are right-space-padded by the writer: the name header by 18 bytes, both
     * 114-byte lines by 19, and each 112-byte total line by 21.
     */
    @Test
    @DisplayName("the rule line and the blank line pass through unpadded; the other five are padded")
    void theFullWidthLayoutsPassThroughAndTheNarrowOnesArePadded() {
        assertThat(TranReportLayouts.TRANSACTION_HEADER_2_IMAGE)
                .as("TRANSACTION-HEADER-2 is PIC X(133) VALUE ALL '-'")
                .hasSize(TranReportWriter.RECORD_LENGTH)
                .isEqualTo("-".repeat(TranReportWriter.RECORD_LENGTH));
        assertThat(TranReportWriter.TRANSACTION_HEADER_2_PAD)
                .as("a layout already at the record width must not be padded")
                .isZero();
        assertThat(TranReportWriter.WS_BLANK_LINE_IMAGE)
                .as("WS-BLANK-LINE is PIC X(133) VALUE SPACES - app/cbl/CBTRN03C.cbl:133")
                .hasSize(TranReportWriter.RECORD_LENGTH)
                .isBlank();
        assertThat(TranReportWriter.WS_BLANK_LINE_PAD).isZero();

        assertThat(TranReportLayouts.REPORT_NAME_HEADER_LENGTH).isEqualTo(115);
        assertThat(TranReportWriter.REPORT_NAME_HEADER_PAD).isEqualTo(18);
        assertThat(TranReportLayouts.TRANSACTION_HEADER_1_LENGTH).isEqualTo(114);
        assertThat(TranReportWriter.TRANSACTION_HEADER_1_PAD).isEqualTo(19);
        assertThat(TranReportLayouts.TRANSACTION_DETAIL_REPORT_LENGTH).isEqualTo(114);
        assertThat(TranReportWriter.TRANSACTION_DETAIL_REPORT_PAD).isEqualTo(19);
        assertThat(TranReportLayouts.REPORT_PAGE_TOTALS_LENGTH).isEqualTo(112);
        assertThat(TranReportWriter.REPORT_PAGE_TOTALS_PAD).isEqualTo(21);
        assertThat(TranReportLayouts.REPORT_ACCOUNT_TOTALS_LENGTH).isEqualTo(112);
        assertThat(TranReportWriter.REPORT_ACCOUNT_TOTALS_PAD).isEqualTo(21);
        assertThat(TranReportLayouts.REPORT_GRAND_TOTALS_LENGTH).isEqualTo(112);
        assertThat(TranReportWriter.REPORT_GRAND_TOTALS_PAD).isEqualTo(21);

        assertThat(TranReportLayouts.PAGE_TOTAL_LEADER_IMAGE)
                .as("REPORT-PAGE-TOTALS carries FILLER PIC X(86) VALUE ALL '.'")
                .isEqualTo(".".repeat(86));
        assertThat(TranReportLayouts.ACCOUNT_TOTAL_LEADER_IMAGE)
                .as("REPORT-ACCOUNT-TOTALS carries FILLER PIC X(84) VALUE ALL '.'")
                .isEqualTo(".".repeat(84));
        assertThat(TranReportLayouts.GRAND_TOTAL_LEADER_IMAGE)
                .as("REPORT-GRAND-TOTALS carries FILLER PIC X(86) VALUE ALL '.'")
                .isEqualTo(".".repeat(86));
    }

    /**
     * The two numeric-edited masks of {@code app/cpy/CVTRA07Y.cpy}, compared as exact strings.
     *
     * <p>The mask <em>is</em> the value: a report amount is a fifteen-character image, not a number
     * that happens to be formatted, so every one of these is an equality on characters. Three cases in
     * the fixture set depend on the rules asserted here - {@code case14} on the fixed leading sign and
     * the comma that falls inside the suppressed run, {@code case15} on a four-digit amount and a
     * four-digit total whose thousands comma prints because suppression has already stopped, and
     * {@code case16} on a value that fills every position. The all-{@code Z} zero rule - a value of
     * zero blanks the entire item, the decimal point included - is asserted here directly rather than
     * through a fixture, because every amount and every total across the twenty cases is non-zero.
     */
    @Test
    @DisplayName("the -ZZZ,ZZZ,ZZZ.ZZ and +ZZZ,ZZZ,ZZZ.ZZ masks render exactly (G24)")
    void theEditMasksRenderExactly() {
        assertThat(TranReportLayouts.DETAIL_AMOUNT_MASK).isEqualTo("-ZZZ,ZZZ,ZZZ.ZZ");
        assertThat(TranReportLayouts.TOTAL_AMOUNT_MASK).isEqualTo("+ZZZ,ZZZ,ZZZ.ZZ");
        assertThat(TranReportLayouts.AMOUNT_MASK_WIDTH).isEqualTo(15);

        assertThat(TranReportLayouts.editDetailAmount(new BigDecimal("0.00")))
                .as("every digit position is Z, so a zero value blanks the whole item - the decimal "
                        + "point included")
                .isEqualTo(" ".repeat(TranReportLayouts.AMOUNT_MASK_WIDTH));
        assertThat(TranReportLayouts.editTotalAmount(new BigDecimal("0.00")))
                .as("a total that nets to zero prints a blank column, not +0.00")
                .isEqualTo(" ".repeat(TranReportLayouts.AMOUNT_MASK_WIDTH));

        assertThat(TranReportLayouts.editDetailAmount(new BigDecimal("100.00")))
                .isEqualTo("         100.00");
        assertThat(TranReportLayouts.editDetailAmount(new BigDecimal("-1234.56")))
                .as("the leading minus is a fixed insertion character in position 1 and never floats, "
                        + "and the comma inside the suppressed run becomes a space")
                .isEqualTo("-      1,234.56");
        assertThat(TranReportLayouts.editDetailAmount(new BigDecimal("1234.56")))
                .as("the same mask prints a space where a positive value would show its sign")
                .isEqualTo("       1,234.56");
        assertThat(TranReportLayouts.editDetailAmount(new BigDecimal("999999999.99")))
                .as("a value that fills the mask suppresses nothing and prints both commas")
                .isEqualTo(" 999,999,999.99");
        assertThat(TranReportLayouts.editTotalAmount(new BigDecimal("999999999.99")))
                .isEqualTo("+999,999,999.99");
        assertThat(TranReportLayouts.editTotalAmount(new BigDecimal("-250.25")))
                .as("the + mask still prints a minus for a negative total")
                .isEqualTo("-        250.25");
        assertThat(TranReportLayouts.editDetailAmount(new BigDecimal("0.01")))
                .as("suppression cannot pass the decimal point, so a sub-unit value keeps it")
                .isEqualTo("            .01");
    }

    /**
     * The arithmetic behind {@code case16}: the running totals are {@link BigDecimal} at scale exactly
     * two and every store truncates toward zero.
     *
     * <p>{@code WS-PAGE-TOTAL}, {@code WS-ACCOUNT-TOTAL} and {@code WS-GRAND-TOTAL} are all
     * {@code PIC S9(09)V99} ({@code app/cbl/CBTRN03C.cbl:134-136}), and {@code ROUNDED} appears
     * <strong>zero</strong> times in any of the 28 programs - so a store discards excess fractional
     * digits rather than rounding them, and a sum that overflows nine integer digits loses its
     * high-order digit rather than raising anything, because there is no {@code ON SIZE ERROR} either.
     * {@link com.vsergeychik.carddemo.common.CobolDecimal} is the single seam that decides both, and
     * this is the assertion that says why {@code case16}'s page total reads 999,999,999.98 where the
     * arithmetic sum is 1,999,999,999.98 (gates G22 and G24).
     */
    @Test
    @DisplayName("the totals are BigDecimal at scale 2 and every store truncates, never rounds (G24)")
    void theRunningTotalsTruncateAtNineIntegerDigitsAndScaleTwo() {
        assertThat(CobolDecimal.MONETARY_SCALE)
                .as("every monetary PICTURE in this codebase is scale 2")
                .isEqualTo(2);
        assertThat(CobolDecimal.COBOL_ROUNDING)
                .as("ROUNDED appears zero times in the 28 programs, so a store truncates")
                .isEqualTo(RoundingMode.DOWN);
        assertThat(TransactionReportJob.TOTAL_INTEGER_DIGITS).isEqualTo(9);
        assertThat(TransactionReportJob.TOTAL_SCALE).isEqualTo(CobolDecimal.MONETARY_SCALE);

        BigDecimal doubled = new BigDecimal("999999999.99").add(new BigDecimal("999999999.99"));
        assertThat(doubled)
                .as("the arithmetic sum needs ten integer digits")
                .isEqualByComparingTo(new BigDecimal("1999999999.98"));
        assertThat(CobolDecimal.storeAtPicture(doubled, TransactionReportJob.TOTAL_INTEGER_DIGITS,
                        TransactionReportJob.TOTAL_SCALE))
                .as("storing it into PIC S9(09)V99 discards the high-order digit, which is what "
                        + "case16's page and grand totals show")
                .isEqualTo(new BigDecimal("999999999.98"));
        assertThat(CobolDecimal.store(new BigDecimal("1.239"), CobolDecimal.MONETARY_SCALE))
                .as("truncation toward zero, not half-up - 1.239 stores as 1.23")
                .isEqualTo(new BigDecimal("1.23"));
        assertThat(CobolDecimal.store(new BigDecimal("-1.239"), CobolDecimal.MONETARY_SCALE))
                .as("truncation is sign-symmetric - -1.239 stores as -1.23")
                .isEqualTo(new BigDecimal("-1.23"));
    }

    /**
     * {@code TRAN-CAT-KEY} is six bytes here and seventeen bytes elsewhere, and the two must never be
     * conflated.
     *
     * <p>{@code app/cpy/CVTRA04Y.cpy} declares {@code TRAN-CAT-KEY} as {@code TRAN-TYPE-CD PIC X(02)}
     * plus {@code TRAN-CAT-CD PIC 9(04)}, which is the key {@code 1500-C-LOOKUP-TRANCATG} reads with
     * and the key {@code case11} pins in its diagnostic line. {@code app/cpy/CVTRA01Y.cpy} declares a
     * {@code TRAN-CAT-KEY} of the same name and seventeen bytes, belonging to the category-balance file
     * this program never opens.
     */
    @Test
    @DisplayName("the category lookup key is 6 bytes, not the 17-byte TRAN-CAT-KEY of CVTRA01Y")
    void theCategoryKeyIsSixBytesAndNotSeventeen() {
        assertThat(TranCategoryRepository.KEY_LENGTH)
                .as("CVTRA04Y's TRAN-CAT-KEY is TRAN-TYPE-CD X(02) + TRAN-CAT-CD 9(04)")
                .isEqualTo(6);
        assertThat(TranCategoryRepository.TRAN_CAT_BAL_KEY_LENGTH)
                .as("CVTRA01Y's identically named key belongs to a file CBTRN03C does not open")
                .isEqualTo(17);
        assertThat(TranCategoryRepository.KEY_LENGTH)
                .isNotEqualTo(TranCategoryRepository.TRAN_CAT_BAL_KEY_LENGTH);
        assertThat(TranTypeRepository.TRAN_TYPE_KEY_LENGTH)
                .as("CVTRA03Y's TRAN-TYPE is PIC X(02)")
                .isEqualTo(2);
    }

    // =============================================================================================
    // G47 - the file-status arms a BATCH_JOB case cannot reach.
    //
    // ParityCase forbids screenRequest on a batch case, and forcedOutcomes live inside it, so a case
    // fixture can only produce the statuses its seeded data produces: '00' from every successful call,
    // '10' from the end of TRANFILE and from an empty DATEPARM, and '23' from the three INVALID KEY
    // lookups. The WHEN OTHER arm of each EVALUATE, and the ELSE of each open, write and close ladder,
    // is reachable only from a backend condition - so it is pinned here, against the same program body
    // the twenty cases run, with the same collaborators and the same real writer.
    // =============================================================================================

    /**
     * A refused report write takes the {@code ELSE} of {@code 1111-WRITE-REPORT-REC}'s ladder and
     * abends, and the generation the run had already written is discarded.
     *
     * <p>{@code app/cbl/CBTRN03C.cbl:346-357}: a write has no end-of-file outcome, so the ladder moves
     * {@code 0} or {@code 12} and never {@code 16}, and {@code APPL-EOF} is never tested. The refusal
     * lands on the third write, which is {@code TRANSACTION-HEADER-1} inside the first-time header
     * block, so two records reached the sink before the abend - and both are gone afterwards, because
     * {@code app/jcl/TRANREPT.jcl:76-80} declares {@code DISP=(NEW,CATLG,DELETE)}.
     */
    @Test
    @DisplayName("a refused report write abends with RETURN-CODE 12 and discards the generation (G47)")
    void aRefusedReportWriteAbendsAndDiscardsTheGeneration() {
        Injection injection = Injection.refusingWrite(3);
        Collector collector = new Collector(injection);
        TransactionReportJob job = job(oneInRangeTransaction(), collector, injection);

        AbendException abend = catchThrowableOfType(AbendException.class,
                () -> job.execute(collector.sysout(), collector.sink()));

        assertThat(abend.getReturnCode())
                .as("every abend site in this program arrives with APPL-RESULT 12")
                .isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
        assertThat(collector.sysoutLines())
                .as("the ladder displays its own message, then 9910-DISPLAY-IO-STATUS, then "
                        + "9999-ABEND-PROGRAM")
                .containsSubsequence("ERROR WRITING REPTFILE", "ABENDING PROGRAM")
                .doesNotContain("END OF EXECUTION OF PROGRAM CBTRN03C");
        assertThat(collector.writtenImages())
                .as("the refusal is on the third write, so two records had already been written")
                .hasSize(3);
        assertThat(collector.retainedImages())
                .as("DISP=(NEW,CATLG,DELETE) leaves no report behind when the run does not reach GOBACK")
                .isEmpty();
    }

    /**
     * A {@code TRANTYPE} open that reports an unlisted status abends inside
     * {@code 0300-TRANTYPE-OPEN}, before the date range has even been read.
     *
     * <p>{@code app/cbl/CBTRN03C.cbl:430-446}. The four opens that follow it never run, so no
     * {@code 'Reporting from'} line is displayed and nothing at all is written - which is what makes
     * this distinct from every abend a case fixture can reach.
     */
    @Test
    @DisplayName("a TRANTYPE open failure abends before the range is read or anything is written (G47)")
    void aTranTypeOpenFailureAbendsBeforeAnythingIsWritten() {
        Injection injection = Injection.tranTypeOpenStatus(BACKEND_REFUSAL_STATUS);
        Collector collector = new Collector(injection);
        TransactionReportJob job = job(oneInRangeTransaction(), collector, injection);

        AbendException abend = catchThrowableOfType(AbendException.class,
                () -> job.execute(collector.sysout(), collector.sink()));

        assertThat(abend.getReturnCode()).isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
        assertThat(collector.sysoutLines())
                .containsExactly("START OF EXECUTION OF PROGRAM CBTRN03C",
                        "ERROR OPENING TRANSACTION TYPE FILE",
                        FileStatus.toDisplayLine(BACKEND_REFUSAL_STATUS),
                        "ABENDING PROGRAM");
        assertThat(collector.writtenImages()).isEmpty();
        assertThat(collector.retainedImages()).isEmpty();
    }

    /**
     * A {@code TRANFILE} read that reports a status the program does not name takes the
     * {@code WHEN OTHER} arm of the {@code EVALUATE} at {@code :251-258} and abends.
     *
     * <p>The status displayed is the one the read reported - not the {@code 23} the three keyed lookups
     * substitute - which is the difference the {@code fileStatusHint} in {@link FieldDiffer} exists to
     * make legible. Nothing has been written, because {@code 1000-TRANFILE-GET-NEXT} runs before
     * {@code 1100-WRITE-TRANSACTION-REPORT} on every pass.
     */
    @Test
    @DisplayName("a TRANFILE read failure takes WHEN OTHER and abends with its own status (G47)")
    void aTranFileReadFailureAbendsWithTheStatusItReported() {
        Injection injection = Injection.tranFileReadStatus(BACKEND_REFUSAL_STATUS);
        Collector collector = new Collector(injection);
        TransactionReportJob job = job(oneInRangeTransaction(), collector, injection);

        AbendException abend = catchThrowableOfType(AbendException.class,
                () -> job.execute(collector.sysout(), collector.sink()));

        assertThat(abend.getReturnCode()).isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
        assertThat(collector.sysoutLines())
                .containsSubsequence("ERROR READING TRANSACTION FILE",
                        FileStatus.toDisplayLine(BACKEND_REFUSAL_STATUS),
                        "ABENDING PROGRAM");
        assertThat(collector.writtenImages()).isEmpty();
    }

    /**
     * A {@code TRANREPT} close that reports a failure abends inside {@code 9100-REPTFILE-CLOSE} after a
     * complete report has been written, and the completed report is then discarded.
     *
     * <p>{@code app/cbl/CBTRN03C.cbl:532-548}. This is the sharpest separation of the two record
     * channels in the whole program: eight records were written and none survives, because the run did
     * not reach {@code 9999-GOBACK}. The close ladder is also the one place the program uses
     * {@code SUBTRACT APPL-RESULT FROM APPL-RESULT} rather than {@code MOVE 0}, which is the same
     * assignment written differently and must not change the arm taken.
     */
    @Test
    @DisplayName("a report close failure abends after a complete report and discards it (G47)")
    void aReportCloseFailureAbendsAfterACompleteReport() {
        Injection injection = Injection.refusingClose();
        Collector collector = new Collector(injection);
        TransactionReportJob job = job(oneInRangeTransaction(), collector, injection);

        AbendException abend = catchThrowableOfType(AbendException.class,
                () -> job.execute(collector.sysout(), collector.sink()));

        assertThat(abend.getReturnCode()).isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
        assertThat(collector.sysoutLines())
                .containsSubsequence("TRAN-AMT 0000001000{",
                        "ERROR CLOSING REPORT FILE",
                        "ABENDING PROGRAM")
                .doesNotContain("END OF EXECUTION OF PROGRAM CBTRN03C");
        assertThat(collector.writtenImages())
                .as("the report was complete: four headers, one detail, the page total, its rule line "
                        + "and the grand total")
                .hasSize(8);
        assertThat(collector.retainedImages())
                .as("an abnormal end deletes the generation however complete it was")
                .isEmpty();
    }

    /** @return the one in-range transaction the four status tests above run against */
    private static SeededInputs oneInRangeTransaction() {
        TranRecord record = new TranRecord(ASCII);
        record.moveTranId("TRN0000000000001");
        record.moveTranTypeCd("01");
        record.moveTranCatCd(1);
        record.moveTranSource("POS TERM  ");
        record.moveTranDesc("A statically derived fixture transaction");
        record.moveTranAmt(new BigDecimal("100.00"));
        record.moveTranMerchantId(999999999L);
        record.moveTranMerchantName("FIXTURE MERCHANT");
        record.moveTranMerchantCity("FIXTURE CITY");
        record.moveTranMerchantZip("0000000000");
        record.moveTranCardNum("0500024453765740");
        record.moveTranOrigTs("2022-03-15-11.22.33.444444");
        record.moveTranProcTs("2022-03-15-11.22.33.444444");

        Map<String, CardXrefRecord> xref = new LinkedHashMap<>();
        xref.put("0500024453765740", new CardXrefRecord("0500024453765740", 50, 50L));
        Map<String, TranTypeRecord> types = new LinkedHashMap<>();
        types.put("01", TranTypeRecord.of("01", "Purchase", ASCII));
        Map<String, TranCategoryRecord> categories = new LinkedHashMap<>();
        categories.put("010001", TranCategoryRecord.of("01", 1, "Regular Sales Draft", ASCII));

        return new SeededInputs(List.of(record), xref, types, categories,
                List.of(("2022-01-01" + " " + "2022-07-06" + " ".repeat(59))));
    }

    // =============================================================================================
    // THE ADAPTER. How the harness reaches CBTRN03C's body with nothing in between.
    // =============================================================================================

    /**
     * Constructs the report job over the case's seeded datasets and calls the program body.
     *
     * <p>Everything is per-invocation: the collaborators, the collecting sink, the capturing
     * {@code SYSOUT} and the job itself are created here and shared with nothing, so no two cases can
     * see each other's state and there is no static mutable field anywhere in this class (gate G53).
     *
     * <p>Observations go into {@link ParityHarness.Invocation#recorder()} <em>before</em> an
     * {@link AbendException} is allowed to leave, which is the only ordering that works: four of the
     * twenty cases abend, one of them after writing five records, and a method's return value cannot
     * carry an observation past an exception. The recorder can, and the harness drains it and takes the
     * {@code RETURN-CODE} from the abend.
     *
     * <p>The case's {@code jobParameters} are deliberately not read. This program has none - the
     * reporting range is a dataset - and reading one here would give a fixture a way to influence the
     * run that {@code app/jcl/TRANREPT.jcl} does not have.
     */
    private static final class ReportRun implements ParityHarness.ParityUnit {

        @Override
        public ParityHarness.UnitOutcome invoke(ParityHarness.Invocation invocation) {
            SeededInputs inputs = decode(invocation);
            Collector collector = new Collector(Injection.NONE);
            TransactionReportJob job = job(inputs, collector, Injection.NONE);
            ParityHarness.UnitOutcome.Builder recorder = invocation.recorder();
            try {
                ExecutionSummary summary = job.execute(collector.sysout(), collector.sink());
                record(recorder, collector);
                recorder.returnCode(summary.returnCode());
            } catch (AbendException abend) {
                record(recorder, collector);
                throw abend;
            }
            return null;
        }

        /**
         * Reports what the run produced: the report records in write order, the records that survive
         * the run's disposition, and every {@code DISPLAY} line in emission order.
         *
         * <p>The two record channels are reported separately because they genuinely differ.
         * {@code TRANREPT} is {@code DISP=(NEW,CATLG,DELETE)}, so a run that abends leaves no report
         * however many records it wrote - and an empty channel is reported as a dataset that exists
         * and holds nothing, rather than omitted, because omitting it would say only that the case
         * never mentioned the report.
         */
        private static void record(ParityHarness.UnitOutcome.Builder recorder, Collector collector) {
            List<String> written = collector.writtenImages();
            if (written.isEmpty()) {
                recorder.openedWithoutWriting(TRANREPT, FD_REPTFILE_REC);
            } else {
                recorder.wroteAll(TRANREPT, FD_REPTFILE_REC, written);
            }
            recorder.finalState(TRANREPT, FD_REPTFILE_REC, collector.retainedImages());
            for (String line : collector.sysoutLines()) {
                recorder.display(line);
            }
        }
    }

    /**
     * One case's seeded datasets, decoded through the production record decoders.
     *
     * @param transactions {@code TRANFILE}, in the order the sorted daily file yields them
     * @param xref         {@code CARDXREF}, keyed by the sixteen-byte card number
     * @param types        {@code TRANTYPE}, keyed by the two-byte transaction type
     * @param categories   {@code TRANCATG}, keyed by the six-byte category key
     * @param dateParmRows {@code DATEPARM}, still as 80-byte images so the reader's own 80-into-21
     *                     truncation is the thing that decodes them
     */
    private record SeededInputs(List<TranRecord> transactions,
                                Map<String, CardXrefRecord> xref,
                                Map<String, TranTypeRecord> types,
                                Map<String, TranCategoryRecord> categories,
                                List<String> dateParmRows) {
    }

    /**
     * Decodes the seeded rows into the records the collaborators will hand back.
     *
     * <p>Every decode goes through the production decoder for that copybook - {@link TranRecord},
     * {@link CardXrefRecord}, {@link TranTypeRecord}, {@link TranCategoryRecord} - so a row that the
     * repositories could not have produced cannot enter the run, and the cross-reference rows arrive
     * already padded from 36 to the 50 bytes {@code app/cpy/CVACT03Y.cpy} declares because the case
     * declared that normalisation and the harness applied it at seeding time (gate G16).
     */
    private static SeededInputs decode(ParityHarness.Invocation invocation) {
        Charset charset = invocation.charset();
        List<TranRecord> transactions = new ArrayList<>();
        for (String row : rowsOf(invocation, TransactionReportJob.TRANFILE_DD_NAME)) {
            transactions.add(TranRecord.decode(row, charset));
        }
        Map<String, CardXrefRecord> xref = new LinkedHashMap<>();
        for (String row : rowsOf(invocation, TransactionReportJob.CARDXREF_DD_NAME)) {
            CardXrefRecord record = CardXrefRecord.decode(
                    invocation.codec().encodeImage(row, "a CARDXREF row"), invocation.codec());
            xref.put(record.xrefCardNum(), record);
        }
        Map<String, TranTypeRecord> types = new LinkedHashMap<>();
        for (String row : rowsOf(invocation, TranTypeRepository.DD_NAME)) {
            TranTypeRecord record = TranTypeRecord.decode(row, charset);
            types.put(record.tranType(), record);
        }
        Map<String, TranCategoryRecord> categories = new LinkedHashMap<>();
        for (String row : rowsOf(invocation, TranCategoryRepository.DD_NAME)) {
            TranCategoryRecord record = TranCategoryRecord.decode(
                    invocation.codec().encodeImage(row, "a TRANCATG row"), charset);
            categories.put(record.tranCatKeyImage(), record);
        }
        return new SeededInputs(transactions, xref, types, categories,
                rowsOf(invocation, DateParmReader.DD_NAME));
    }

    /**
     * @param invocation the invocation holding the seeded datasets
     * @param dataset    the binding key
     * @return that dataset's rows, or an empty list when the case seeds it as an existing empty
     *     dataset or does not seed it at all
     */
    private static List<String> rowsOf(ParityHarness.Invocation invocation, String dataset) {
        return invocation.hasDataset(dataset) ? invocation.dataset(dataset).rows() : List.of();
    }

    // =============================================================================================
    // Wiring. Five stubbed collaborators, one real writer, and the contracts configuration declares.
    // =============================================================================================

    /**
     * A backend condition to inject, for the arms no seeded row can reach.
     *
     * @param tranTypeOpenStatus the status {@code 0300-TRANTYPE-OPEN}'s open reports, or {@code null}
     *                           for {@code '00'}
     * @param tranFileReadStatus the status {@code 1000-TRANFILE-GET-NEXT}'s read reports, or
     *                           {@code null} to yield the seeded records and then end of file
     * @param refusedWriteNumber the one-based report write to refuse, or zero to accept every write
     * @param closeOutcome       what the report sink's close reports; never {@code null}
     */
    private record Injection(String tranTypeOpenStatus,
                             String tranFileReadStatus,
                             int refusedWriteNumber,
                             FileStatus.Outcome closeOutcome) {

        /** Every collaborator succeeds - the shape all twenty cases run under. */
        private static final Injection NONE = new Injection(null, null, 0, FileStatus.Outcome.OK);

        /** @param status the status the type file's open reports */
        private static Injection tranTypeOpenStatus(String status) {
            return new Injection(status, null, 0, FileStatus.Outcome.OK);
        }

        /** @param status the status the transaction read reports */
        private static Injection tranFileReadStatus(String status) {
            return new Injection(null, status, 0, FileStatus.Outcome.OK);
        }

        /** @param writeNumber the one-based write to refuse */
        private static Injection refusingWrite(int writeNumber) {
            return new Injection(null, null, writeNumber, FileStatus.Outcome.OK);
        }

        /** @return an injection whose report close reports a failure */
        private static Injection refusingClose() {
            return new Injection(null, null, 0, FileStatus.Outcome.OTHER);
        }
    }

    /**
     * Builds the job over stubbed collaborators and a <em>real</em> {@link TranReportWriter}.
     *
     * <p>The writer is real on purpose: it owns the 133-byte record and every padding decision, so a
     * mocked one would make the width assertions assertions about the mock. Everything upstream of it
     * is stubbed from the seeded rows, which is what removes the database, the filesystem and the
     * application context from the path without removing one line of the program body.
     */
    private static TransactionReportJob job(SeededInputs inputs, Collector collector,
            Injection injection) {
        DatasetBindings catalogue = bindings();
        return new TransactionReportJob(
                batchConfig(catalogue),
                transactionRepository(inputs.transactions(), injection),
                cardXrefRepository(inputs.xref()),
                tranTypeRepository(inputs.types(), injection),
                tranCategoryRepository(inputs.categories()),
                dateParmReader(inputs.dateParmRows(), catalogue),
                new TranReportWriter(new JdbcTemplate(), ASCII, catalogue, RecordImageForm.CHARACTER),
                ASCII,
                new SuppliedProvider<>(collector.sysout()),
                new JdbcTemplate(),
                RecordImageForm.CHARACTER,
                ORDINAL,
                unitOfWork(),
                new SuppliedProvider<>(new InMemoryDatasetUtilityPort()));
    }

    /**
     * {@code TRANFILE} - one sequential pass that yields the seeded records and then end of file, or
     * the injected status on its first read.
     */
    private static TransactionRepository transactionRepository(List<TranRecord> records,
            Injection injection) {
        TransactionRepository repository = mock(TransactionRepository.class);
        TransactionRepository.InputFile inputFile = mock(TransactionRepository.InputFile.class);
        when(repository.openInput(any(DatasetBinding.class))).thenReturn(inputFile);
        when(inputFile.openStatus()).thenReturn(FileStatus.OK);
        when(inputFile.closeInput()).thenReturn(FileStatus.OK);
        // isOpen() answers from the handle's own invocation record rather than from a flag, so a close
        // that reports a failure still leaves the handle closed - which is what keeps the program's own
        // CLOSE at :208 the only close on the normal path.
        when(inputFile.isOpen()).thenAnswer(question -> mockingDetails(inputFile).getInvocations()
                .stream().noneMatch(call -> "closeInput".equals(call.getMethod().getName())));

        List<TransactionRepository.ReadResult> reads = new ArrayList<>();
        if (injection.tranFileReadStatus() != null) {
            reads.add(TransactionRepository.ReadResult.other(TransactionReportJob.TRANFILE_DD_NAME,
                    injection.tranFileReadStatus()));
        } else {
            for (TranRecord record : records) {
                reads.add(TransactionRepository.ReadResult.found(
                        TransactionReportJob.TRANFILE_DD_NAME, record));
            }
        }
        reads.add(TransactionRepository.ReadResult.endOfFile(TransactionReportJob.TRANFILE_DD_NAME));
        when(inputFile.readNext()).thenReturn(reads.get(0),
                reads.subList(1, reads.size()).toArray(new TransactionRepository.ReadResult[0]));
        return repository;
    }

    /** {@code CARDXREF} - a keyed read per account break, and {@code INVALID KEY} for a card absent
     * from the seed. */
    private static CardXrefRepository cardXrefRepository(Map<String, CardXrefRecord> rows) {
        CardXrefRepository repository = mock(CardXrefRepository.class);
        CardXrefRepository.BrowseCursor cursor = mock(CardXrefRepository.BrowseCursor.class);
        when(repository.openBrowse()).thenReturn(cursor);
        when(cursor.openStatus()).thenReturn(FileStatus.OK);
        when(cursor.closeBrowse()).thenReturn(FileStatus.OK);
        when(cursor.isOpen()).thenAnswer(question -> mockingDetails(cursor).getInvocations()
                .stream().noneMatch(call -> "closeBrowse".equals(call.getMethod().getName())));
        when(repository.readByCardNumber(anyString())).thenAnswer(question -> {
            String key = question.getArgument(0);
            CardXrefRecord found = rows.get(key);
            return found == null
                    ? CardXrefRepository.ReadResult.notFound(CardXrefRepository.BASE_DD_NAME)
                    : CardXrefRepository.ReadResult.found(CardXrefRepository.BASE_DD_NAME, found,
                            new String(found.encode(ASCII), ASCII));
        });
        return repository;
    }

    /** {@code TRANTYPE} - a keyed read per detail line, and {@code INVALID KEY} for an absent type. */
    private static TranTypeRepository tranTypeRepository(Map<String, TranTypeRecord> rows,
            Injection injection) {
        TranTypeRepository repository = mock(TranTypeRepository.class);
        when(repository.open()).thenReturn(injection.tranTypeOpenStatus() == null
                ? FileStatus.OK
                : injection.tranTypeOpenStatus());
        when(repository.close()).thenReturn(FileStatus.OK);
        when(repository.readByTranType(anyString())).thenAnswer(question -> {
            String key = question.getArgument(0);
            TranTypeRecord found = rows.get(key);
            return found == null
                    ? TranTypeRepository.ReadResult.notFound(key)
                    : TranTypeRepository.ReadResult.found(key, found);
        });
        return repository;
    }

    /**
     * {@code TRANCATG} - a keyed read per detail line on the six-byte key, and {@code INVALID KEY} for
     * an absent category.
     */
    private static TranCategoryRepository tranCategoryRepository(
            Map<String, TranCategoryRecord> rows) {
        TranCategoryRepository repository = mock(TranCategoryRepository.class);
        when(repository.open()).thenReturn(FileStatus.OK);
        when(repository.close()).thenReturn(FileStatus.OK);
        when(repository.keyImage(anyString(), anyInt())).thenAnswer(question ->
                keyImageOf(question.getArgument(0), question.getArgument(1)));
        when(repository.readByKey(anyString(), anyInt())).thenAnswer(question -> {
            TranCategoryRecord found = rows.get(
                    keyImageOf(question.getArgument(0), question.getArgument(1)));
            return found == null
                    ? TranCategoryRepository.ReadResult.notFound()
                    : TranCategoryRepository.ReadResult.found(found);
        });
        return repository;
    }

    /**
     * {@code FD-TRAN-CAT-KEY} as {@code 1500-C-LOOKUP-TRANCATG} displays it: {@code TRAN-TYPE-CD}
     * {@code PIC X(02)} followed by {@code TRAN-CAT-CD} {@code PIC 9(04)}, six bytes in total.
     *
     * @param tranTypeCd the two-byte type code
     * @param tranCatCd  the four-digit category code
     * @return the six-byte key image
     */
    private static String keyImageOf(String tranTypeCd, int tranCatCd) {
        return String.format("%-2s%04d", tranTypeCd, tranCatCd);
    }

    /**
     * {@code DATEPARM} - a real {@link DateParmReader} for its JDBC-free {@code decode}, wrapped so the
     * read yields the seeded row without a backend.
     *
     * <p>The decode is the real one deliberately: the 80-into-21 truncation of
     * {@code READ DATE-PARMS-FILE INTO WS-DATEPARM-RECORD} is exactly what turns an 80-byte record into
     * {@code WS-START-DATE}, the one-byte separator the program never reads and {@code WS-END-DATE}, and
     * a case that hand-sliced those spans would assert its own arithmetic rather than the reader's. An
     * empty dataset yields {@code '10'}, which is what sets {@code END-OF-FILE} before the loop begins.
     */
    private static DateParmReader dateParmReader(List<String> rows, DatasetBindings catalogue) {
        DateParmReader decoder = new DateParmReader(new JdbcTemplate(), catalogue, ASCII,
                RecordImageForm.CHARACTER, ORDINAL);
        DateParmReader reader = mock(DateParmReader.class);
        when(reader.open()).thenReturn(FileStatus.OK);
        when(reader.close()).thenReturn(FileStatus.OK);
        when(reader.read()).thenReturn(rows.isEmpty()
                ? DateParmReader.ReadResult.endOfFile()
                : DateParmReader.ReadResult.found(decoder.decode(rows.get(0))));
        return reader;
    }

    /**
     * The dataset catalogue, carrying every binding the job's startup guards check: the report at 133
     * bytes, {@code TRANFILE} at the 350 {@code app/cpy/CVTRA05Y.cpy} declares, this job's three lookup
     * DDs, the cross-reference repository's own {@code CCXREF} key naming the same dataset as
     * {@code CARDXREF}, {@code DATEPARM} at 80, and the four DDs of the two preparatory steps.
     *
     * <p>Every dataset name here is a {@code TEST.} name. No production mainframe DSN literal appears
     * anywhere in this file, because a dataset name is configuration and never Java (gate G46).
     */
    private static DatasetBindings bindings() {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(TranReportWriter.DD_NAME, new DatasetBinding(TEST_TRANREPT, "sequential", true,
                TranReportWriter.RECORD_FORMAT, TranReportWriter.BLOCK_SIZE,
                TranReportWriter.RECORD_LENGTH, "CVTRA07Y", null, null, null, null));
        catalogue.put(TransactionReportJob.TRANFILE_DD_NAME, new DatasetBinding(TEST_TRANFILE,
                "sequential", true, "FB", 0, TranRecord.RECORD_LENGTH, "CVTRA05Y", null, null, null,
                null));
        catalogue.put(TransactionReportJob.CARDXREF_DD_NAME, new DatasetBinding(TEST_CARDXREF,
                DatasetBinding.KSDS, false, "FB", null, CardXrefRecord.RECORD_LENGTH, "CVACT03Y", null,
                null, null, null));
        catalogue.put(CardXrefRepository.BASE_DD_NAME, new DatasetBinding(TEST_CARDXREF,
                DatasetBinding.KSDS, false, "FB", null, CardXrefRecord.RECORD_LENGTH, "CVACT03Y", null,
                null, null, null));
        catalogue.put(TranTypeRepository.DD_NAME, new DatasetBinding(TEST_TRANTYPE,
                DatasetBinding.KSDS, false, "FB", null, TranTypeRecord.RECORD_LENGTH, "CVTRA03Y", null,
                null, null, null));
        catalogue.put(TranCategoryRepository.DD_NAME, new DatasetBinding(TEST_TRANCATG,
                DatasetBinding.KSDS, false, "FB", null, TranCategoryRecord.RECORD_LENGTH, "CVTRA04Y",
                null, null, null, null));
        catalogue.put(DateParmReader.DD_NAME, new DatasetBinding(TEST_DATEPARM, "sequential", true,
                "FB", 0, DateParmReader.RECORD_LENGTH, "CBTRN03C", null, null, null, null));
        catalogue.put(TransactionReportJob.BACKUP_INPUT_DD_NAME, new DatasetBinding(TEST_MASTER,
                DatasetBinding.KSDS, false, TransactionReportJob.UTILITY_RECORD_FORMAT, 0,
                TranRecord.RECORD_LENGTH, "CVTRA05Y", TranRecord.TRAN_ID_KEY_LENGTH, 0, null, null));
        catalogue.put(TransactionReportJob.BACKUP_OUTPUT_DD_NAME, new DatasetBinding(TEST_BACKUP,
                "sequential", true, TransactionReportJob.UTILITY_RECORD_FORMAT, 0,
                TranRecord.RECORD_LENGTH, "CVTRA05Y", null, null, null, null));
        catalogue.put(TransactionReportJob.SORT_INPUT_DD_NAME, new DatasetBinding(TEST_BACKUP,
                "sequential", true, TransactionReportJob.UTILITY_RECORD_FORMAT, 0,
                TranRecord.RECORD_LENGTH, "CVTRA05Y", null, null, null, null));
        catalogue.put(TransactionReportJob.SORT_OUTPUT_DD_NAME, new DatasetBinding(TEST_TRANFILE,
                "sequential", true, TransactionReportJob.UTILITY_RECORD_FORMAT, 0,
                TranRecord.RECORD_LENGTH, "CVTRA05Y", null, null, null, null));
        return catalogue;
    }

    /**
     * The batch seam over the contract {@code application.yml} declares for this job: this program,
     * {@code app/jcl/TRANREPT.jcl}'s three steps in the JCL's order, no job parameter, and
     * {@code DATEPARM} as the date-range source.
     */
    private static BatchConfig batchConfig(DatasetBindings catalogue) {
        JobContracts contracts = new JobContracts();
        contracts.put(TransactionReportJob.JOB_KEY, new JobContract(
                TransactionReportJob.PROGRAM_NAME, List.of(), TransactionReportJob.REQUIRED_STEPS,
                TransactionReportJob.DATE_RANGE_SOURCE, Map.of()));
        return new BatchConfig(new SuppliedProvider<>(mock(JobRepository.class)),
                new SuppliedProvider<>(mock(PlatformTransactionManager.class)), contracts, catalogue);
    }

    /**
     * A real unit of work over a throwaway in-memory data source.
     *
     * <p>Real rather than mocked because the abnormal disposition depends on it: a mocked transaction
     * manager would let a disposition that never opens a boundary look correct, and the discard of the
     * report generation is the thing four of the twenty cases assert. The database is in memory, is
     * named per call, and is never written to by this program - the sink it discards through holds its
     * records in a list.
     */
    private static DatasetUnitOfWork unitOfWork() {
        return new DatasetUnitOfWork(new JdbcTransactionManager(new SimpleDriverDataSource(
                new org.h2.Driver(), "jdbc:h2:mem:cbtrn03c-parity-" + UUID.randomUUID(), "sa", "")));
    }

    // =============================================================================================
    // Test doubles.
    // =============================================================================================

    /**
     * An {@link ObjectProvider} over one bean, or over none.
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

    /**
     * The two destinations one run writes to: the 133-byte report and {@code SYSOUT}.
     *
     * <p>Held together because they are observed together, and because the discard has to be able to
     * empty one of them without touching the other.
     */
    private static final class Collector {

        /** Where the report records go. */
        private final ReportSink sink;

        /** Where the {@code DISPLAY} lines go. */
        private final CapturingSysout sysout = new CapturingSysout();

        private Collector(Injection injection) {
            this.sink = new ReportSink(injection);
        }

        /** @return the report sink */
        private ReportSink sink() {
            return sink;
        }

        /** @return the {@code SYSOUT} sink */
        private CapturingSysout sysout() {
            return sysout;
        }

        /** @return every record handed to the sink, in write order, decoded under the named charset */
        private List<String> writtenImages() {
            return sink.writtenImages();
        }

        /** @return the records the dataset still holds, which a discarded generation leaves empty */
        private List<String> retainedImages() {
            return sink.retainedImages();
        }

        /** @return every emitted line, in emission order */
        private List<String> sysoutLines() {
            return sysout.lines();
        }
    }

    /**
     * The report destination: it keeps every record it is handed, and separately keeps the generation
     * the run leaves behind.
     *
     * <p>The two lists are what make {@code DISP=(NEW,CATLG,DELETE)} observable.
     * {@code app/jcl/TRANREPT.jcl:76-80} gives the report a different disposition for a normal end than
     * for an abnormal one, so a run that abends after writing five records has written five records and
     * left none - and a sink that modelled only "what was written" could not say so.
     */
    private static final class ReportSink implements TranReportWriter.RecordSink {

        /** Every record handed to this sink, in write order. */
        private final List<String> written = new ArrayList<>();

        /** The generation the run leaves behind, emptied when the disposition discards it. */
        private final List<String> retained = new ArrayList<>();

        /** The condition to inject, if any. */
        private final Injection injection;

        private ReportSink(Injection injection) {
            this.injection = injection;
        }

        @Override
        public FileStatus.Outcome write(byte[] recordImage) {
            String image = new String(recordImage, ASCII);
            written.add(image);
            if (written.size() == injection.refusedWriteNumber()) {
                return FileStatus.Outcome.OTHER;
            }
            retained.add(image);
            return FileStatus.Outcome.OK;
        }

        @Override
        public FileStatus.Outcome close() {
            return injection.closeOutcome();
        }

        @Override
        public FileStatus.Outcome discard(int recordsWritten) {
            retained.clear();
            return FileStatus.Outcome.OK;
        }

        /** @return every record handed to this sink, in write order */
        private List<String> writtenImages() {
            return List.copyOf(written);
        }

        /** @return the records the dataset still holds */
        private List<String> retainedImages() {
            return List.copyOf(retained);
        }
    }

    /** Collects every {@code DISPLAY} line in emission order. */
    private static final class CapturingSysout implements SysoutSink {

        /** The lines, in emission order. */
        private final List<String> lines = new ArrayList<>();

        @Override
        public void display(String line) {
            lines.add(line);
        }

        /** @return the lines, in emission order */
        private List<String> lines() {
            return List.copyOf(lines);
        }
    }

    /**
     * An in-memory {@link DatasetUtilityPort}, so the job's two preparatory steps have a data path that
     * reaches no backend.
     *
     * <p>Neither step runs here - {@code STEP10R} is the only step this program is, and the parity gate
     * invokes the program body rather than the {@code Job} - but the constructor requires the port, and
     * supplying an in-memory one keeps a {@link JdbcTemplate} with no data source off every path.
     */
    private static final class InMemoryDatasetUtilityPort implements DatasetUtilityPort {

        /** Dataset name to its records, in write order. */
        private final Map<String, List<String>> datasets = new LinkedHashMap<>();

        @Override
        public int deleteAllRecords(DatasetBinding binding) {
            List<String> removed = datasets.put(binding.dsname(), new ArrayList<>());
            return removed == null ? 0 : removed.size();
        }

        @Override
        public List<String> readAllRecordImages(DatasetBinding binding) {
            return List.copyOf(datasets.getOrDefault(binding.dsname(), List.of()));
        }

        @Override
        public int writeRecordImages(DatasetBinding binding, List<String> recordImages) {
            datasets.computeIfAbsent(binding.dsname(), key -> new ArrayList<>()).addAll(recordImages);
            return recordImages.size();
        }
    }
}
