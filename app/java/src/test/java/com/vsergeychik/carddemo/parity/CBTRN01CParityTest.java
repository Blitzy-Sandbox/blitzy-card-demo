package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.account.AccountRepository;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.CardRepository;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.customer.CustomerRepository;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;
import com.vsergeychik.carddemo.transaction.DalyTranRepository;
import com.vsergeychik.carddemo.transaction.TransactionPostingJob;
import com.vsergeychik.carddemo.transaction.TransactionPostingJob.ExecutionSummary;
import com.vsergeychik.carddemo.transaction.TransactionPostingJob.SysoutSink;
import com.vsergeychik.carddemo.transaction.TransactionRepository;
import com.vsergeychik.carddemo.transaction.model.DalyTranRecord;
import com.vsergeychik.carddemo.transaction.model.TranRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The twenty-case behavioural-parity gate for {@code app/cbl/CBTRN01C.cbl} - the orphan - against its
 * Java translation {@link TransactionPostingJob}.
 *
 * <h2>The program posts nothing, and the class name says it does</h2>
 * <p>{@code CBTRN01C}'s own {@code Function :} header reads "Post the records from daily transaction
 * file", and the prompt-mandated Java name is {@code TransactionPostingJob}. Both are misleading, and
 * this gate is written so that believing either one fails the build. The source contains
 * <strong>no {@code WRITE} and no {@code REWRITE} anywhere</strong>; all six of its {@code SELECT}
 * statements are opened {@code OPEN INPUT} ({@code :254}, {@code :273}, {@code :291}, {@code :309},
 * {@code :327}, {@code :345}), so posting is not merely absent but impossible. What it does is read
 * {@code DALYTRAN} sequentially, display each 350-byte record, resolve the record's card number in the
 * cross reference, read the account that resolves, emit its verify and skip messages, and close all
 * six files. The real {@code POSTTRAN} poster is {@code CBTRN02C}, which migrates as
 * {@code TransactionValidationJob} - the divergence register in AAP 0.8.4 records the swap, and an
 * expectation here that asserted a posted transaction record would be a <em>behaviour change</em>
 * rather than a fix (practice B4). Every one of the twenty cases therefore declares
 * {@code expectedWrites} empty and asserts, on the writes channel, that each dataset the run reached
 * was reached and <strong>no record was written to it</strong>.
 *
 * <h2>It is an orphan, and it stays one</h2>
 * <p>No JCL invokes {@code CBTRN01C}: there is no {@code EXEC PGM=CBTRN01C} anywhere in
 * {@code app/jcl/} or {@code app/proc/}, which is why the job takes <strong>no job parameter</strong> -
 * there is no {@code PARM} to take - and why {@link #everyCaseDeclaresNoJobParameter()} asserts the
 * absence for all twenty rather than leaving it implied. It is nonetheless one of the 28 in-scope
 * programs, so it migrates as a fully runnable Spring Batch job with <strong>no scheduled
 * trigger</strong> (requirement I4, gate G13): {@link #nothingTriggersTheOrphanJob()} asserts that the
 * class carries no scheduling annotation, implements no runner interface and holds no launcher, and
 * {@link #theOrphanJobIsRunnable()} asserts that the job, its step and its tasklet are all real. Do not
 * delete it, do not wire it into a pipeline, and do not add the writes its name implies (practice B5).
 *
 * <h2>Baseline provenance - statically derived, never captured</h2>
 * <p>Every expected value in this file is <strong>statically derived</strong>: produced by structured
 * reading of each COBOL paragraph and cross-checked against four authoritative sources - the byte
 * layouts of {@code app/cpy/CVTRA06Y.cpy} (350), {@code CVACT03Y.cpy} (50), {@code CVACT01Y.cpy} (300),
 * {@code CVACT02Y.cpy} (150), {@code CVCUS01Y.cpy} (500) and {@code CVTRA05Y.cpy} (350); the absence of
 * any JCL for this program, which is what fixes its parameter list as empty; the {@code DFHMDF} field
 * definitions, of which a batch program has none; and the real ASCII fixtures in
 * {@code app/data/ASCII}, from which every card number, customer id, account id, transaction id and
 * amount image used below is taken verbatim. They are <strong>not</strong> captured from, recorded
 * against or replayed from any execution of the legacy COBOL, because executing it is empirically
 * impossible in this environment - no z/OS runtime, an indexed-file handler the available compiler
 * reports as disabled, and no Language Environment {@code CEE*} services, so {@code CALL 'CEE3ABD'} at
 * {@code app/cbl/CBTRN01C.cbl:473} cannot be reached at all. This substitutes the <em>provenance</em>
 * of the expected values and nothing else: twenty cases, field-for-field diffing and the
 * diff-count-equals-zero gate all stand, and the substitution is escalated for confirmation rather than
 * silently absorbed (risk R-A, practice B12).
 *
 * <p><strong>Nothing here is derived from the translation it judges.</strong> Every expected literal is
 * written out in full rather than referenced from a constant on the class under test: an expectation
 * that read {@code TransactionPostingJob.SUCCESSFUL_READ_OF_XREF} would pass whatever that constant
 * happened to say, which is the one thing a parity gate must never do. {@link #theCobolLiteralsMatch()}
 * then compares the two sets once, in one place, so a drift is reported as a drift instead of being
 * absorbed silently by every expectation at once.
 *
 * <h2>Two source properties are reproduced deliberately, each with its own cases</h2>
 * <ol>
 *   <li><strong>The post-end-of-file lookup on a stale card number.</strong> The three statements at
 *       {@code :170-172} sit <em>outside</em> the {@code IF END-OF-DAILY-TRANS-FILE = 'N'} guard that
 *       closes at {@code :169}, so the iteration whose {@code READ} reported end of file still performs
 *       a cross-reference lookup - on the {@code DALYTRAN-CARD-NUM} the previous record left in the
 *       record area, because {@code READ ... INTO} does not disturb its receiving area {@code AT END}.
 *       A run therefore performs {@code recordsRead + 1} lookups, always, and never fewer.
 *       {@code case01} pins the repeat after one clean record, {@code case02} pins that the repeat uses
 *       the <em>last</em> record's card and not the first, and {@code case04} pins the degenerate form:
 *       over an empty dataset the lookup runs on the untouched record area, whose
 *       {@code DALYTRAN-CARD-NUM} is sixteen spaces. Hoisting those statements inside the guard makes
 *       all three fail.</li>
 *   <li><strong>{@code 9000-DALYTRAN-CLOSE} reports the wrong file.</strong> {@code :372-373} displays
 *       {@code 'ERROR CLOSING CUSTOMER FILE'} and moves {@code CUSTFILE-STATUS} - not
 *       {@code DALYTRAN-STATUS} - into {@code IO-STATUS} on the arm reached when closing the
 *       <em>daily transaction</em> file fails. So the operator is told the wrong file failed and is
 *       shown the wrong status: {@code 'FILE STATUS IS: NNNN0000'}, because the customer file's own
 *       open succeeded. {@code case20} pins both halves; correcting either makes it fail.</li>
 * </ol>
 * <p>Neither is fixed here. Fixing either would change what this program has always reported, which is
 * a behaviour change and a parity violation (practice B5).
 *
 * <h2>Where the cases come from: {@code parity/CBTRN01C/}, and nowhere else</h2>
 * <p>{@link #cases()} returns {@code ParityHarness.casesOf("CBTRN01C")} and builds nothing, so the twenty
 * {@code caseNN.json} files under {@code src/test/resources/parity/CBTRN01C/} <em>are</em> the case set.
 * This class once declared its own twenty in Java, because that directory was absent when it was
 * written; those declarations have been deleted rather than kept alongside the fixtures, because two
 * case sets for one program is worse than either alone - the shipped files were never executed, they
 * numbered the failure paths differently, and each set silently vouched for the other.
 *
 * <p>What a case declares, it declares completely. Its seeds are its {@code inputs}; the arms no seeded
 * row can reach - a refused open, a read reporting a status this program never names, a close that fails
 * - are its {@code unitStimulus.callSiteOutcomes}, named by call site rather than selected here by case
 * identifier; and the reads it must issue are its {@code expectedOperations}. Nothing about a run is
 * decided by which ordinal the case happens to hold, which is what makes renumbering safe and a review
 * of the file sufficient. {@link #resourceConventionIsHonoured()} asserts that this class's stem and that
 * directory agree, rather than stating it in a comment.
 *
 * <h2>How the unit is reached: {@code BATCH_JOB}, and no launcher</h2>
 * <p>The unit is {@link TransactionPostingJob} constructed through its own constructor as a plain Java
 * object, with the tasklet's program body invoked directly through
 * {@link TransactionPostingJob#execute(SysoutSink)}. There is no {@code JobLauncher}, no job
 * repository in the path, no asynchronous executor, no HTTP layer, no servlet-test harness, no
 * application context, no database and no filesystem between the assertion and the program body (gate
 * G51). {@link JobRepository} and {@link PlatformTransactionManager} appear once each, as mocks handed to
 * {@link BatchConfig} so that its contract and dataset catalogues can be read; neither is touched by a
 * run. The six collaborators are stubbed from the case's seeded rows and decode them through the
 * <em>production</em> decoders - {@link DalyTranRecord}, {@link CardXrefRecord}, {@link AccountRecord} -
 * so a row the repositories could not have produced cannot enter a run, and the code page is always
 * {@link ParityHarness#FIXTURE_CHARSET}, stated explicitly rather than taken from the platform
 * (practice B8).
 *
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - so no user rule governs
 * this file; the binding standard is the enterprise-practice set B1-B12. The gates it discharges
 * directly are G13 (a runnable orphan nothing triggers), G15 (twenty cases, loudly), G16 (the 36-to-50
 * cross-reference pad), G18 (diff count zero, per case), G19 and G21 (the 350-, 300- and 50-byte record
 * widths with every {@code FILLER} present and space-filled), G22 (no binary floating point), G35 (the
 * abend's {@code RETURN-CODE} 12), G46 (no dataset name in Java), G47 (every file-status outcome per
 * call site), G51 (no launcher and no HTTP), G52 (no wildcard import), G53 (no mutable static state) and
 * G54 (non-interactive).
 */
@DisplayName("CBTRN01C parity - the orphan that opens six files, posts nothing, and looks up one card too many")
class CBTRN01CParityTest {

    /** The program this class gates, which is also the name of its {@code parity/<PROGRAM>/} directory. */
    private static final String PROGRAM = "CBTRN01C";

    /** How the harness must reach the unit: the program body of a batch tasklet, with no launcher. */
    private static final ParityCase.UnitKind UNIT_KIND = ParityCase.UnitKind.BATCH_JOB;

    /**
     * The code page every fixture, every seeded row and every expected image is read and written under,
     * named explicitly and never taken from the platform (practice B8).
     */
    private static final Charset ASCII = ParityHarness.FIXTURE_CHARSET;

    // =================================================================================================
    // The six DD names, transcribed from the SELECT statements rather than borrowed from the class under
    // test, so a renamed DD is reported here instead of being followed silently. Each is a binding key:
    // no dataset name appears anywhere in this file (gate G46).
    // =================================================================================================

    /** {@code SELECT DALYTRAN-FILE ASSIGN TO DALYTRAN} - {@code app/cbl/CBTRN01C.cbl:29}. */
    private static final String DALYTRAN = "DALYTRAN";

    /** {@code SELECT CUSTOMER-FILE ASSIGN TO CUSTFILE} - {@code app/cbl/CBTRN01C.cbl:34}. */
    private static final String CUSTFILE = "CUSTFILE";

    /** {@code SELECT XREF-FILE ASSIGN TO XREFFILE} - {@code app/cbl/CBTRN01C.cbl:40}. */
    private static final String XREFFILE = "XREFFILE";

    /** {@code SELECT CARD-FILE ASSIGN TO CARDFILE} - {@code app/cbl/CBTRN01C.cbl:46}. */
    private static final String CARDFILE = "CARDFILE";

    /** {@code SELECT ACCOUNT-FILE ASSIGN TO ACCTFILE} - {@code app/cbl/CBTRN01C.cbl:52}. */
    private static final String ACCTFILE = "ACCTFILE";

    /** {@code SELECT TRANSACT-FILE ASSIGN TO TRANFILE} - {@code app/cbl/CBTRN01C.cbl:58}. */
    private static final String TRANFILE = "TRANFILE";

    /**
     * The six DDs in the order {@code MAIN-PARA} opens them - {@code :157-162} - which is also the order
     * it closes them in at {@code :188-193} and therefore the order every trace expectation reads in.
     */
    private static final List<String> ALL_DDS =
            List.of(DALYTRAN, CUSTFILE, XREFFILE, CARDFILE, ACCTFILE, TRANFILE);

    /**
     * The three DDs this program actually reads: the daily file sequentially at {@code :167}, and the
     * cross reference and account files by key at {@code :202-208} and {@code :226-232}. The other three
     * are opened and closed and never touched in between, which is behaviour in its own right.
     */
    private static final List<String> READ_DDS = List.of(DALYTRAN, XREFFILE, ACCTFILE);

    /**
     * Renders an account key back into the eleven digits {@code XREF-ACCT-ID} carried, so a recorded
     * operation names the key the COBOL moved rather than the {@code long} the repository takes. Immutable
     * and therefore safe as a constant (practice B9).
     */
    private static final FixedWidthCodec ACCOUNT_KEY_CODEC =
            new FixedWidthCodec(ParityHarness.FIXTURE_CHARSET);

    // =================================================================================================
    // Record widths, transcribed from the copybooks. Held here as literals so an expectation never
    // borrows a width from the code it judges; theCopybookWidthsMatch() compares the two sets once.
    // =================================================================================================

    /** {@code DALYTRAN-RECORD} - {@code app/cpy/CVTRA06Y.cpy}, 350 bytes. */
    private static final int DALYTRAN_WIDTH = 350;

    /** {@code CUSTOMER-RECORD} - {@code app/cpy/CVCUS01Y.cpy}, 500 bytes. */
    private static final int CUSTFILE_WIDTH = 500;

    /** {@code CARD-XREF-RECORD} - {@code app/cpy/CVACT03Y.cpy}, 50 bytes including {@code FILLER X(14)}. */
    private static final int XREF_WIDTH = 50;

    /** The width {@code cardxref.txt} actually holds: 16 + 9 + 11, with the trailing {@code FILLER} absent. */
    private static final int XREF_FIXTURE_WIDTH = 36;

    /** {@code CARD-RECORD} - {@code app/cpy/CVACT02Y.cpy}, 150 bytes. */
    private static final int CARDFILE_WIDTH = 150;

    /** {@code ACCOUNT-RECORD} - {@code app/cpy/CVACT01Y.cpy}, 300 bytes. */
    private static final int ACCTFILE_WIDTH = 300;

    /** {@code TRAN-RECORD} - {@code app/cpy/CVTRA05Y.cpy}, 350 bytes. */
    private static final int TRANFILE_WIDTH = 350;

    // =================================================================================================
    // Every DISPLAY literal, written out here rather than referenced from the class under test.
    // =================================================================================================

    /** {@code app/cbl/CBTRN01C.cbl:156}. */
    private static final String START_BANNER = "START OF EXECUTION OF PROGRAM CBTRN01C";

    /** {@code app/cbl/CBTRN01C.cbl:195}. */
    private static final String END_BANNER = "END OF EXECUTION OF PROGRAM CBTRN01C";

    /** {@code app/cbl/CBTRN01C.cbl:263}. */
    private static final String ERROR_OPENING_DALYTRAN = "ERROR OPENING DAILY TRANSACTION FILE";

    /** {@code app/cbl/CBTRN01C.cbl:282}. */
    private static final String ERROR_OPENING_CUSTFILE = "ERROR OPENING CUSTOMER FILE";

    /** {@code app/cbl/CBTRN01C.cbl:300}. */
    private static final String ERROR_OPENING_XREFFILE = "ERROR OPENING CROSS REF FILE";

    /** {@code app/cbl/CBTRN01C.cbl:318}. */
    private static final String ERROR_OPENING_CARDFILE = "ERROR OPENING CARD FILE";

    /** {@code app/cbl/CBTRN01C.cbl:336}. */
    private static final String ERROR_OPENING_ACCTFILE = "ERROR OPENING ACCOUNT FILE";

    /** {@code app/cbl/CBTRN01C.cbl:354}. */
    private static final String ERROR_OPENING_TRANFILE = "ERROR OPENING TRANSACTION FILE";

    /** {@code app/cbl/CBTRN01C.cbl:219}. */
    private static final String ERROR_READING_DALYTRAN = "ERROR READING DAILY TRANSACTION FILE";

    /**
     * {@code app/cbl/CBTRN01C.cbl:390}, and also {@code :372} - which is the second reproduced source
     * property: the paragraph that closes the <em>daily transaction</em> file displays the customer
     * file's literal.
     */
    private static final String ERROR_CLOSING_CUSTFILE = "ERROR CLOSING CUSTOMER FILE";

    /** {@code app/cbl/CBTRN01C.cbl:408}. */
    private static final String ERROR_CLOSING_XREFFILE = "ERROR CLOSING CROSS REF FILE";

    /** {@code app/cbl/CBTRN01C.cbl:426}. */
    private static final String ERROR_CLOSING_CARDFILE = "ERROR CLOSING CARD FILE";

    /** {@code app/cbl/CBTRN01C.cbl:444}. */
    private static final String ERROR_CLOSING_ACCTFILE = "ERROR CLOSING ACCOUNT FILE";

    /** {@code app/cbl/CBTRN01C.cbl:462}. */
    private static final String ERROR_CLOSING_TRANFILE = "ERROR CLOSING TRANSACTION FILE";

    /** {@code app/cbl/CBTRN01C.cbl:232}. */
    private static final String INVALID_CARD_NUMBER_FOR_XREF = "INVALID CARD NUMBER FOR XREF";

    /** {@code app/cbl/CBTRN01C.cbl:235}. */
    private static final String SUCCESSFUL_READ_OF_XREF = "SUCCESSFUL READ OF XREF";

    /** {@code app/cbl/CBTRN01C.cbl:236} - the literal operand, whose trailing space is part of it. */
    private static final String XREF_CARD_NUMBER_PREFIX = "CARD NUMBER: ";

    /**
     * {@code app/cbl/CBTRN01C.cbl:237} - {@code 'ACCOUNT ID : '}, with the space before the colon that
     * aligns it under the line above. Two spaces around the colon, not one, and not a typo to correct.
     */
    private static final String XREF_ACCOUNT_ID_PREFIX = "ACCOUNT ID : ";

    /** {@code app/cbl/CBTRN01C.cbl:238}. */
    private static final String XREF_CUSTOMER_ID_PREFIX = "CUSTOMER ID: ";

    /** {@code app/cbl/CBTRN01C.cbl:246}. */
    private static final String INVALID_ACCOUNT_NUMBER_FOUND = "INVALID ACCOUNT NUMBER FOUND";

    /** {@code app/cbl/CBTRN01C.cbl:249}. */
    private static final String SUCCESSFUL_READ_OF_ACCOUNT_FILE = "SUCCESSFUL READ OF ACCOUNT FILE";

    /** The first operand of {@code DISPLAY 'ACCOUNT ' ACCT-ID ' NOT FOUND'} - {@code :178}. */
    private static final String ACCOUNT_NOT_FOUND_PREFIX = "ACCOUNT ";

    /** The third operand of the same {@code DISPLAY} - {@code :178}. */
    private static final String ACCOUNT_NOT_FOUND_SUFFIX = " NOT FOUND";

    /** The first operand of the four-operand {@code DISPLAY} at {@code :181-183}. */
    private static final String CARD_NOT_VERIFIED_PREFIX = "CARD NUMBER ";

    /** The third operand of that same single {@code DISPLAY} - {@code :182}. */
    private static final String CARD_NOT_VERIFIED_SUFFIX =
            " COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-";

    /** {@code Z-ABEND-PROGRAM} - {@code app/cbl/CBTRN01C.cbl:470}. */
    private static final String ABENDING_PROGRAM = "ABENDING PROGRAM";

    /**
     * The literal of {@code DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04} - {@code :483} and {@code :487}.
     *
     * <p>{@code NNNN} is genuinely part of the COBOL literal and is not a placeholder awaiting
     * substitution: the four-character {@code IO-STATUS-04} image follows it, so status {@code '00'}
     * produces {@code 'FILE STATUS IS: NNNN0000'}.
     */
    private static final String FILE_STATUS_PREFIX = "FILE STATUS IS: NNNN";

    // =================================================================================================
    // The file statuses the twenty cases drive, and the two return codes the program can leave.
    // =================================================================================================

    /** {@code IF <FILE>-STATUS = '00'} - the arm every open, close and read tests first. */
    private static final String STATUS_OK = "00";

    /** {@code IF DALYTRAN-STATUS = '10'} - {@code :207}, the only non-error second arm. */
    private static final String STATUS_END_OF_FILE = "10";

    /** A duplicate on the base key, which the {@code INVALID KEY} phrase of a keyed read runs for. */
    private static final String STATUS_DUPLICATE = "22";

    /** What an absent record reports, and what the {@code INVALID KEY} phrase primarily runs for. */
    private static final String STATUS_NOT_FOUND = "23";

    /**
     * A VSAM open failure: the dataset named by the DD was not found. Neither {@code '00'} nor
     * {@code '10'}, so it takes the {@code ELSE MOVE 12} arm of whichever paragraph reported it.
     */
    private static final String STATUS_OPEN_FAILED = "35";

    /**
     * The status this module reports for a dataset it could not reach at all: {@code IO-STAT1 = '9'}
     * with a feedback code of zero in the second byte.
     *
     * <p>{@code Z-DISPLAY-IO-STATUS} recognises a leading {@code '9'} as an extended status
     * ({@code :477-483}): it renders that byte verbatim and the second byte's numeric value in three
     * digits, so this status renders {@code 'FILE STATUS IS: NNNN9000'}.
     */
    private static final String STATUS_PERMANENT_ERROR = "9\u0000";

    /** What a run that returns at all returns: {@code CBTRN01C} never moves a value into {@code RETURN-CODE}. */
    private static final int RETURN_CODE_OK = 0;

    /**
     * {@code MOVE 12 TO APPL-RESULT} then {@code CALL 'CEE3ABD'} - the code every abend of this program
     * carries.
     */
    private static final int RETURN_CODE_FATAL = 12;

    /** {@code DFHRESP(NORMAL)}: the response a CICS browse start reports when the file was there. */
    private static final int CICS_RESP_NORMAL = 0;

    /** {@code DFHRESP(NOTFND)}: no record at or beyond the key the browse was positioned at. */
    private static final int CICS_RESP_NOTFND = 13;

    /** {@code DFHRESP(DUPREC)}: a duplicate on the base key, which a keyed read reports as {@code '22'}. */
    private static final int CICS_RESP_DUPREC = 14;

    // =================================================================================================
    // Field widths and real values taken from app/data/ASCII. Every card number, customer id, account
    // id, transaction id and amount image below is production fixture data, quoted with its zero-based
    // row so it can be checked; theSeedRowsAreTheShippedFixtureRows() reads the fixtures and proves it.
    // =================================================================================================

    /** {@code XREF-CARD-NUM PIC X(16)}, and {@code DALYTRAN-CARD-NUM PIC X(16)}. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** {@code XREF-CUST-ID PIC 9(09)}. */
    private static final int CUSTOMER_ID_WIDTH = 9;

    /** {@code XREF-ACCT-ID PIC 9(11)}, and {@code ACCT-ID PIC 9(11)}. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** {@code DALYTRAN-ID PIC X(16)}. */
    private static final int TRANSACTION_ID_WIDTH = 16;

    /**
     * The zero-based offset of {@code DALYTRAN-CARD-NUM} within {@code DALYTRAN-RECORD}: the eleven spans
     * before it - 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 - sum to 262.
     */
    private static final int DALYTRAN_CARD_NUM_OFFSET = 262;

    /** {@code dailytran.txt} row 0: {@code DALYTRAN-CARD-NUM}, which {@code cardxref.txt} row 27 carries. */
    private static final String CARD_ONE = "4859452612877065";

    /** {@code dailytran.txt} row 0: {@code DALYTRAN-ID}. */
    private static final String TRAN_ID_ONE = "0000000000683580";

    /** {@code cardxref.txt}: the {@code XREF-CUST-ID} of {@link #CARD_ONE}. */
    private static final int CUST_ONE = 7;

    /**
     * {@code cardxref.txt}: the {@code XREF-ACCT-ID} of {@link #CARD_ONE}, which {@code acctdata.txt}
     * carries.
     */
    private static final long ACCT_ONE = 7L;

    /**
     * {@code dailytran.txt} row 1: {@code DALYTRAN-CARD-NUM}, a second card so that a stale value is
     * distinguishable from a fresh one.
     */
    private static final String CARD_TWO = "0927987108636232";

    /** {@code dailytran.txt} row 1: {@code DALYTRAN-ID}. */
    private static final String TRAN_ID_TWO = "0000000001774260";

    /** {@code cardxref.txt}: the {@code XREF-CUST-ID} of {@link #CARD_TWO}. */
    private static final int CUST_TWO = 20;

    /** {@code cardxref.txt}: the {@code XREF-ACCT-ID} of {@link #CARD_TWO}. */
    private static final long ACCT_TWO = 20L;

    /**
     * A sixteen-digit card number that no row of {@code cardxref.txt} carries, so a keyed read of it
     * reports {@code '23'} and takes the {@code INVALID KEY} arm.
     */
    private static final String CARD_ABSENT = "9999999999999999";

    /** A customer id for a cross-reference row whose account is deliberately absent from the account file. */
    private static final int CUST_ABSENT = 999999999;

    /**
     * An eleven-digit account id no row of {@code acctdata.txt} carries - the fixture holds
     * {@code 00000000001} through {@code 00000000050} - so a keyed read of it reports {@code '23'}.
     */
    private static final long ACCT_ABSENT = 99999999999L;

    /**
     * {@code dailytran.txt} row 0: {@code DALYTRAN-AMT}, a zoned {@code PIC S9(09)V99} whose trailing
     * {@code 'G'} is the positive overpunch of the digit 7, so the value is {@code +504.77}.
     */
    private static final String AMOUNT_POSITIVE = "0000005047G";

    /**
     * {@code dailytran.txt} row 1: {@code DALYTRAN-AMT} carrying a <strong>negative</strong> zoned
     * overpunch. The trailing {@code '}'} encodes the digit 0 with a negative sign, so the eleven bytes
     * {@code 0000009190}} are {@code -919.00} and not {@code +919.00} - and never {@code 91900000000},
     * which is what reading the byte as an ordinary character would produce.
     */
    private static final String AMOUNT_NEGATIVE = "0000009190}";

    /** What {@link #AMOUNT_NEGATIVE} decodes to at {@code PIC S9(09)V99}'s scale of two. */
    private static final BigDecimal AMOUNT_NEGATIVE_VALUE = new BigDecimal("-919.00");

    /** {@code dailytran.txt}: the {@code DALYTRAN-ORIG-TS} every row of the fixture carries. */
    private static final String ORIGIN_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /** {@code dailytran.txt}: {@code DALYTRAN-PROC-TS} is blank in all 300 rows - the file is unprocessed. */
    private static final String PROCESS_TIMESTAMP_BLANK = "";

    /** {@code dailytran.txt}: the {@code DALYTRAN-MERCHANT-ID} every row of the fixture carries. */
    private static final String MERCHANT_ID = "800000000";

    /** The classpath directory the nine ASCII fixtures are published under, as {@code DatasetInput} names it. */
    private static final String FIXTURE_ROOT = "fixtures/";

    /** The 300-row, 350-byte daily transaction fixture. */
    private static final String DAILYTRAN_FIXTURE = "dailytran.txt";

    /** The 50-row, 36-byte cross-reference fixture - the one width deviation in the fixture set. */
    private static final String CARDXREF_FIXTURE = "cardxref.txt";

    /** The 50-row, 300-byte account fixture. */
    private static final String ACCTDATA_FIXTURE = "acctdata.txt";

    /** The 50-row, 150-byte card fixture - opened by this program and never read. */
    private static final String CARDDATA_FIXTURE = "carddata.txt";

    /** The 50-row, 500-byte customer fixture - opened by this program and never read. */
    private static final String CUSTDATA_FIXTURE = "custdata.txt";

    /**
     * How many fixture rows the twenty cases seed between them, counted once so that
     * {@link #theSeedRowsAreTheShippedFixtureRows()} cannot pass over a case set that seeds nothing. A
     * case added or reshaped changes this number, which is the point: the count asserts that the baseline
     * still has data in it rather than describing it.
     */
    private static final int SEEDED_FIXTURE_ROWS = 105;

    /** The verb a trace entry records for {@code OPEN INPUT}. */
    private static final String TRACE_OPEN = "OPEN ";

    /** The verb a trace entry records for a {@code READ}, keyed or sequential. */
    private static final String TRACE_READ = "READ ";

    /** The verb a trace entry records for {@code CLOSE}. */
    private static final String TRACE_CLOSE = "CLOSE ";

    // =================================================================================================
    // THE GATE.
    // =================================================================================================

    /**
     * Runs one case and requires the difference count to be zero.
     *
     * <p>Two assertions, and the second is not a duplicate of the first. The fingerprint says what the
     * run produced - every line, the return code, and that no record was written to any dataset - and
     * the trace says <em>which file verbs were issued, in what order</em>, which no fingerprint can
     * express. Both matter here: this program's whole behaviour is twelve file verbs, one sequential
     * read per record and two keyed reads per lookup, and a translation that opened the six files in a
     * different order, read the customer file it is only supposed to open, or issued a keyed read the
     * source does not issue would produce identical {@code SYSOUT} in several of these cases.
     *
     * @param scenario the case, its injected backend conditions and its expected verb trace
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("CBTRN01C parity: every case produces zero differences")
    void producesNoDifferences(ParityScenario scenario) {
        PostingRun unit = new PostingRun(scenario);

        FieldDiffer.DiffResult result =
                ParityHarness.usAscii().judge(scenario.parityCase(), UNIT_KIND, unit);

        assertThat(result.count())
                .withFailMessage("%s", result.render())
                .isZero();
        assertThat(unit.operations())
                .as("the reads %s issued, in order and with their keys, as the case declares them: "
                        + "CBTRN01C reads DALYTRAN sequentially at :167 and XREFFILE and ACCTFILE by key "
                        + "at :202-208 and :226-232, and reads CUSTFILE, CARDFILE and TRANFILE never",
                        scenario.caseId())
                .containsExactlyElementsOf(scenario.parityCase().expectedOperations());
        assertThat(unit.openAndCloseTrace())
                .as("the opens and closes %s issued, in order. The sequence is not declared per case "
                        + "because it is not a property of the case: MAIN-PARA opens the six at "
                        + ":157-162 and closes the six at :188-193 in one fixed order, and how far it "
                        + "gets follows from which verb the case scripts a failure at. So the "
                        + "expectation is derived from the declared stimulus and the transcribed source "
                        + "order, while the observation is what the stubs were actually asked for - two "
                        + "derivations that agree only if the translation issues the same verbs in the "
                        + "same order", scenario.caseId())
                .containsExactlyElementsOf(derivedOpenAndCloseTrace(scenario.parityCase()));
    }

    /**
     * The {@code OPEN} and {@code CLOSE} sequence a case must produce, derived from what it declares.
     *
     * <p>Three rules, all transcribed from {@code MAIN-PARA}:
     * <ul>
     *   <li>The opens run in {@link #ALL_DDS} order and stop at the first one that fails, inclusive:
     *       every open paragraph abends on a status other than {@code '00'}, so the opens after it never
     *       happen.</li>
     *   <li>A run whose opens all succeeded but whose sequential read failed abends inside the loop, so
     *       none of the six closes is reached.</li>
     *   <li>Otherwise the closes run in the same order and stop at the first one that fails, inclusive,
     *       for the same reason.</li>
     * </ul>
     *
     * @param parityCase the case, whose declared stimulus says which verb fails
     * @return the entries the run must issue, in order
     */
    private static List<String> derivedOpenAndCloseTrace(ParityCase parityCase) {
        Injection injection = injectionFrom(parityCase.unitStimulus());
        List<String> entries = new ArrayList<>(ALL_DDS.size() * 2);
        for (String dataset : ALL_DDS) {
            entries.add(TRACE_OPEN + dataset);
            if (!STATUS_OK.equals(effectiveOpenStatus(injection, dataset))) {
                return List.copyOf(entries);
            }
        }
        if (injection.readStatus() != null) {
            return List.copyOf(entries);
        }
        for (String dataset : ALL_DDS) {
            entries.add(TRACE_CLOSE + dataset);
            boolean refused = CARDFILE.equals(dataset) && injection.cardCloseRefused();
            if (refused || !STATUS_OK.equals(injection.closeStatusOf(dataset))) {
                return List.copyOf(entries);
            }
        }
        return List.copyOf(entries);
    }

    /**
     * The twenty cases, in {@code case01} through {@code case20} order.
     *
     * <p>This method is the single seam between the gate and the case set. It is where a shipped
     * {@code src/test/resources/parity/CBTRN01C/} fixture directory is adopted - the harness returns the
     * same twenty {@link ParityCase} values from {@code ParityHarness.casesOf(PROGRAM)}, validated by the
     * same constructor - and nothing else in this class depends on which of the two supplied them.
     *
     * @return the twenty scenarios; never {@code null}
     */
    private static List<ParityScenario> cases() {
        List<ParityScenario> scenarios = ParityHarness.casesOf(PROGRAM).stream()
                .map(ParityScenario::new)
                .toList();
        requireCompleteCaseSet(scenarios);
        return scenarios;
    }

    /**
     * One shipped case by ordinal, for the handful of assertions that need a specific run shape rather
     * than the whole set.
     *
     * @param ordinal the case number, 1 through 20
     * @return that case's scenario
     */
    private static ParityScenario scenarioOf(int ordinal) {
        return cases().get(ordinal - 1);
    }

    /**
     * Refuses a case set that is not exactly {@code case01} through {@code case20} of {@value #PROGRAM},
     * in order and without repetition.
     *
     * <p>Loud on purpose, and checked inside the supplier so it cannot be bypassed by running a single
     * case. "The diff count is zero across all twenty cases" is satisfied vacuously by a set of four, so
     * a short, long, misnumbered or duplicated set is a gate that has stopped asking the questions while
     * still reporting green.
     *
     * @param scenarios the declared set
     * @throws IllegalStateException if the set is not the exact twenty
     */
    private static void requireCompleteCaseSet(List<ParityScenario> scenarios) {
        if (scenarios.size() != ParityHarness.CASES_PER_PROGRAM) {
            throw new IllegalStateException(PROGRAM + " declares " + scenarios.size()
                    + " parity case(s) but the gate requires exactly "
                    + ParityHarness.CASES_PER_PROGRAM + ". A short set is not a smaller gate, it is a "
                    + "gate that passes without asking the questions.");
        }
        for (int ordinal = 1; ordinal <= scenarios.size(); ordinal++) {
            String expected = ParityHarness.caseId(ordinal);
            ParityCase declared = scenarios.get(ordinal - 1).parityCase();
            if (!expected.equals(declared.caseId())) {
                throw new IllegalStateException("Case " + ordinal + " of " + PROGRAM + " is declared "
                        + declared.caseId() + " where the set requires " + expected
                        + ". The identifiers are positional: a gap or a repeat means a case nobody is "
                        + "running, or one running twice while another runs not at all.");
            }
            if (!PROGRAM.equals(declared.program())) {
                throw new IllegalStateException("Case " + expected + " names program "
                        + declared.program() + " but this class gates " + PROGRAM + ", whose cases live "
                        + "in " + ParityHarness.CASE_RESOURCE_ROOT + PROGRAM + '/');
            }
        }
    }

    // =================================================================================================
    // Invariants of the case set itself. These assert the shape every case must have, so a new case
    // cannot be added that quietly drops the unit kind, introduces a job parameter or stops asserting
    // that nothing was written.
    // =================================================================================================

    /**
     * Exactly twenty cases exist, named {@code case01} through {@code case20} (gate G15).
     */
    @Test
    @DisplayName("the program declares exactly twenty cases, case01 through case20 (G15)")
    void theProgramDeclaresExactlyTwentyCases() {
        List<ParityScenario> declared = cases();

        assertThat(declared).hasSize(ParityHarness.CASES_PER_PROGRAM);
        assertThat(declared.stream().map(ParityScenario::caseId).toList())
                .containsExactlyElementsOf(expectedCaseIds())
                .doesNotHaveDuplicates();
    }

    /**
     * Every case declares the same contract: this program, the batch-job unit kind, no online members,
     * and no expected write of any kind.
     */
    @Test
    @DisplayName("all twenty cases declare CBTRN01C, BATCH_JOB, no screen members and no expected write")
    void everyCaseDeclaresTheSameContract() {
        for (ParityScenario scenario : cases()) {
            ParityCase declared = scenario.parityCase();

            assertThat(declared.program()).isEqualTo(PROGRAM);
            assertThat(declared.unitKind()).isEqualTo(UNIT_KIND);
            assertThat(declared.description()).isNotBlank();
            assertThat(declared.screenRequest())
                    .as("%s is a batch case: CBTRN01C contains no EXEC CICS and drives no map",
                            scenario.caseId())
                    .isNull();
            assertThat(declared.expectedResponse()).isNull();
            assertThat(declared.expectedWrites())
                    .as("%s - CBTRN01C issues no WRITE and no REWRITE anywhere, and opens all six of "
                            + "its files OPEN INPUT, so an expected write would assert behaviour the "
                            + "program cannot have", scenario.caseId())
                    .isEmpty();
            assertThat(declared.inputs().keySet())
                    .as("%s seeds only datasets this program declares a SELECT for", scenario.caseId())
                    .isSubsetOf(ALL_DDS);
        }
    }

    /**
     * No case declares a job parameter, because no JCL declares an {@code EXEC} card for this program and
     * therefore no {@code PARM} exists for one to come from.
     */
    @Test
    @DisplayName("no case declares a job parameter: CBTRN01C is an orphan with no JCL and no PARM")
    void everyCaseDeclaresNoJobParameter() {
        for (ParityScenario scenario : cases()) {
            assertThat(scenario.parityCase().jobParameters())
                    .as("%s - there is no EXEC PGM=CBTRN01C in app/jcl or app/proc, so there is no "
                            + "PARM to parse and no LINKAGE SECTION to receive one", scenario.caseId())
                    .isEmpty();
        }
    }

    /**
     * Every dataset-level expectation on the writes channel asserts <strong>zero</strong> rows.
     *
     * <p>This is the class's central claim stated once over the whole set: every dataset any case says
     * the run reached is a dataset the run wrote nothing to. The row counts are what make it an
     * assertion rather than a description - a dataset expectation of one row would pass for a
     * translation that had started posting.
     */
    @Test
    @DisplayName("every writes-channel expectation is zero rows: the program posts nothing, anywhere")
    void everyCaseAssertsThatNothingWasWritten() {
        int writesExpectations = 0;
        for (ParityScenario scenario : cases()) {
            for (ParityCase.ExpectedDataset expectation : scenario.parityCase().expectedDatasets()) {
                if (expectation.channel() != ParityCase.DatasetChannel.WRITES) {
                    continue;
                }
                writesExpectations++;
                assertThat(expectation.rowCount())
                        .as("%s expects %s to have been written to", scenario.caseId(),
                                expectation.dataset())
                        .isZero();
                assertThat(expectation.recordLength())
                        .as("%s leaves %s's record width unasserted; the width is what proves every "
                                + "FILLER span was accounted for", scenario.caseId(),
                                expectation.dataset())
                        .isEqualTo(widthOf(expectation.dataset()));
            }
        }
        assertThat(writesExpectations)
                .as("the twenty cases between them must assert the empty writes channel of every "
                        + "dataset a run reaches, or the claim that nothing is posted is untested")
                .isGreaterThanOrEqualTo(ALL_DDS.size());
    }

    /**
     * The class stem, the program name and the harness's resource convention agree.
     *
     * <p>Asserted rather than stated in a comment, because the agreement is what makes the
     * {@link #cases()} seam a drop-in: a class named for one program that declared cases for another
     * would load a fixture directory nobody had written.
     */
    @Test
    @DisplayName("the class stem, the program name and parity/CBTRN01C/ agree")
    void resourceConventionIsHonoured() {
        assertThat(CBTRN01CParityTest.class.getSimpleName()).isEqualTo(PROGRAM + "ParityTest");
        assertThat(ParityHarness.caseResourcePath(PROGRAM, ParityHarness.caseId(1)))
                .isEqualTo(ParityHarness.CASE_RESOURCE_ROOT + PROGRAM + "/case01"
                        + ParityHarness.CASE_RESOURCE_EXTENSION);
    }

    /**
     * The literals this file asserts against are the literals the translation emits.
     *
     * <p>Every expectation in this class is written out independently, which is the only way a parity
     * gate can fail when a message is wrong. The cost of that independence is that a deliberate change
     * on one side would otherwise show up as twenty failures with no explanation, so the two sets are
     * compared once, here, where a drift reads as a drift. This assertion is <em>not</em> what pins the
     * text - the twenty cases do that - and removing it would weaken nothing except the diagnostic.
     */
    @Test
    @DisplayName("the transcribed COBOL literals are the ones the translation emits")
    void theCobolLiteralsMatch() {
        assertThat(START_BANNER).isEqualTo(TransactionPostingJob.START_BANNER);
        assertThat(END_BANNER).isEqualTo(TransactionPostingJob.END_BANNER);
        assertThat(ERROR_OPENING_DALYTRAN).isEqualTo(TransactionPostingJob.ERROR_OPENING_DALYTRAN);
        assertThat(ERROR_OPENING_CUSTFILE).isEqualTo(TransactionPostingJob.ERROR_OPENING_CUSTFILE);
        assertThat(ERROR_OPENING_XREFFILE).isEqualTo(TransactionPostingJob.ERROR_OPENING_XREFFILE);
        assertThat(ERROR_OPENING_CARDFILE).isEqualTo(TransactionPostingJob.ERROR_OPENING_CARDFILE);
        assertThat(ERROR_OPENING_ACCTFILE).isEqualTo(TransactionPostingJob.ERROR_OPENING_ACCTFILE);
        assertThat(ERROR_OPENING_TRANFILE).isEqualTo(TransactionPostingJob.ERROR_OPENING_TRANFILE);
        assertThat(ERROR_READING_DALYTRAN).isEqualTo(TransactionPostingJob.ERROR_READING_DALYTRAN);
        assertThat(ERROR_CLOSING_CUSTFILE).isEqualTo(TransactionPostingJob.ERROR_CLOSING_CUSTFILE);
        assertThat(ERROR_CLOSING_XREFFILE).isEqualTo(TransactionPostingJob.ERROR_CLOSING_XREFFILE);
        assertThat(ERROR_CLOSING_CARDFILE).isEqualTo(TransactionPostingJob.ERROR_CLOSING_CARDFILE);
        assertThat(ERROR_CLOSING_ACCTFILE).isEqualTo(TransactionPostingJob.ERROR_CLOSING_ACCTFILE);
        assertThat(ERROR_CLOSING_TRANFILE).isEqualTo(TransactionPostingJob.ERROR_CLOSING_TRANFILE);
        assertThat(INVALID_CARD_NUMBER_FOR_XREF)
                .isEqualTo(TransactionPostingJob.INVALID_CARD_NUMBER_FOR_XREF);
        assertThat(SUCCESSFUL_READ_OF_XREF).isEqualTo(TransactionPostingJob.SUCCESSFUL_READ_OF_XREF);
        assertThat(XREF_CARD_NUMBER_PREFIX).isEqualTo(TransactionPostingJob.XREF_CARD_NUMBER_PREFIX);
        assertThat(XREF_ACCOUNT_ID_PREFIX).isEqualTo(TransactionPostingJob.XREF_ACCOUNT_ID_PREFIX);
        assertThat(XREF_CUSTOMER_ID_PREFIX).isEqualTo(TransactionPostingJob.XREF_CUSTOMER_ID_PREFIX);
        assertThat(INVALID_ACCOUNT_NUMBER_FOUND)
                .isEqualTo(TransactionPostingJob.INVALID_ACCOUNT_NUMBER_FOUND);
        assertThat(SUCCESSFUL_READ_OF_ACCOUNT_FILE)
                .isEqualTo(TransactionPostingJob.SUCCESSFUL_READ_OF_ACCOUNT_FILE);
        assertThat(ACCOUNT_NOT_FOUND_PREFIX).isEqualTo(TransactionPostingJob.ACCOUNT_NOT_FOUND_PREFIX);
        assertThat(ACCOUNT_NOT_FOUND_SUFFIX).isEqualTo(TransactionPostingJob.ACCOUNT_NOT_FOUND_SUFFIX);
        assertThat(CARD_NOT_VERIFIED_PREFIX).isEqualTo(TransactionPostingJob.CARD_NOT_VERIFIED_PREFIX);
        assertThat(CARD_NOT_VERIFIED_SUFFIX).isEqualTo(TransactionPostingJob.CARD_NOT_VERIFIED_SUFFIX);
        assertThat(ABENDING_PROGRAM).isEqualTo(AbendException.ABEND_DISPLAY_TEXT);
        assertThat(FILE_STATUS_PREFIX).isEqualTo(FileStatus.DISPLAY_PREFIX);
    }

    /**
     * The widths, statuses and CICS responses transcribed here are the ones the module uses.
     *
     * <p>The widths come from the copybooks and the record classes derive them independently - each
     * {@code RecordLayout} verifies at class initialisation that its spans sum to the declared length -
     * so agreement here means two independent transcriptions of the same copybook match (gate G19).
     */
    @Test
    @DisplayName("the transcribed record widths, file statuses and CICS responses match the module's")
    void theCopybookWidthsAndStatusVocabularyMatch() {
        assertThat(DALYTRAN_WIDTH).isEqualTo(DalyTranRecord.RECORD_LENGTH);
        assertThat(CUSTFILE_WIDTH).isEqualTo(CustomerRecord.RECORD_LENGTH);
        assertThat(XREF_WIDTH).isEqualTo(CardXrefRecord.RECORD_LENGTH);
        assertThat(XREF_FIXTURE_WIDTH)
                .isEqualTo(CARD_NUMBER_WIDTH + CUSTOMER_ID_WIDTH + ACCOUNT_ID_WIDTH)
                .isEqualTo(XREF_WIDTH - CardXrefRecord.FILLER_LENGTH);
        assertThat(CARDFILE_WIDTH).isEqualTo(CardRecord.RECORD_LENGTH);
        assertThat(ACCTFILE_WIDTH).isEqualTo(AccountRecord.RECORD_LENGTH);
        assertThat(TRANFILE_WIDTH).isEqualTo(TranRecord.RECORD_LENGTH);

        assertThat(STATUS_OK).isEqualTo(FileStatus.OK);
        assertThat(STATUS_END_OF_FILE).isEqualTo(FileStatus.END_OF_FILE);
        assertThat(STATUS_DUPLICATE).isEqualTo(FileStatus.DUPLICATE);
        assertThat(STATUS_NOT_FOUND).isEqualTo(FileStatus.NOT_FOUND);
        assertThat(STATUS_PERMANENT_ERROR)
                .isEqualTo(TransactionPostingJob.PERMANENT_ERROR_STATUS)
                .hasSize(FileStatus.STATUS_LENGTH);
        assertThat(STATUS_OPEN_FAILED)
                .as("'35' must be none of the four statuses the guards name, so that it reaches the "
                        + "ELSE MOVE 12 arm of whichever paragraph reported it")
                .isNotIn(FileStatus.OK, FileStatus.END_OF_FILE, FileStatus.DUPLICATE,
                        FileStatus.NOT_FOUND);

        assertThat(CICS_RESP_NORMAL).isEqualTo(FileStatus.NORMAL);
        assertThat(CICS_RESP_NOTFND).isEqualTo(FileStatus.NOTFND);
        assertThat(CICS_RESP_DUPREC).isEqualTo(FileStatus.DUPREC);
        assertThat(RETURN_CODE_OK).isEqualTo(AbendException.RETURN_CODE_OK);
        assertThat(RETURN_CODE_FATAL).isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
    }

    /**
     * Every row every case seeds is a row of the shipped fixture it claims to come from, byte for byte.
     *
     * <p>The cases hold their seed rows as literals - 350, 300, 150, 500 or 36 characters of production
     * data - and a case that seeded a mistyped row and expected that same mistyped row back would pass
     * without this. So every row of every case is looked up in {@code app/data/ASCII}, read from the
     * classpath under an explicitly named code page, and the whole set has to be found there. Agreement
     * proves the rows are real production records rather than plausible-looking ones, which is what makes
     * the twenty cases a baseline derived from data the mainframe actually shipped.
     *
     * <p>The two composed rows below are a second, independent transcription of the same records, built
     * span by span from the copybook offsets. They exist so that the offsets this class uses elsewhere -
     * {@code DALYTRAN-CARD-NUM} at 262, the eleven-digit account key - are checked against the fixture
     * too, and they are compared here rather than seeded anywhere.
     */
    @Test
    @DisplayName("every row every case seeds is byte-identical to a row of app/data/ASCII")
    void theSeedRowsAreTheShippedFixtureRows() {
        Map<String, List<String>> fixtures = Map.of(
                DALYTRAN, fixtureRows(DAILYTRAN_FIXTURE),
                XREFFILE, fixtureRows(CARDXREF_FIXTURE),
                ACCTFILE, fixtureRows(ACCTDATA_FIXTURE),
                CARDFILE, fixtureRows(CARDDATA_FIXTURE),
                CUSTFILE, fixtureRows(CUSTDATA_FIXTURE));
        List<String> daily = fixtures.get(DALYTRAN);
        List<String> crossReference = fixtures.get(XREFFILE);
        List<String> accounts = fixtures.get(ACCTFILE);

        assertThat(daily).hasSize(300).allMatch(row -> row.length() == DALYTRAN_WIDTH);
        assertThat(crossReference).hasSize(50).allMatch(row -> row.length() == XREF_FIXTURE_WIDTH);
        assertThat(accounts).hasSize(50).allMatch(row -> row.length() == ACCTFILE_WIDTH);

        int seededRows = 0;
        for (ParityScenario scenario : cases()) {
            for (Map.Entry<String, ParityCase.DatasetInput> seeded
                    : scenario.parityCase().inputs().entrySet()) {
                List<String> fixture = fixtures.get(seeded.getKey());
                if (fixture == null) {
                    assertThat(seeded.getValue().rows())
                            .as("%s seeds %s, for which app/data/ASCII ships no fixture, so the only "
                                    + "honest content for it is none", scenario.caseId(),
                                    seeded.getKey())
                            .isEmpty();
                    continue;
                }
                for (String row : seeded.getValue().rows()) {
                    seededRows++;
                    assertThat(fixture)
                            .as("%s seeds a %s row of %d character(s) that app/data/ASCII does not hold",
                                    scenario.caseId(), seeded.getKey(), row.length())
                            .contains(row);
                }
            }
        }
        assertThat(seededRows)
                .as("the twenty cases seed real rows rather than none: a set that seeded nothing would "
                        + "satisfy the loop above vacuously")
                .isEqualTo(SEEDED_FIXTURE_ROWS);

        assertThat(dailyTransactionRowOne()).isEqualTo(daily.get(0));
        assertThat(dailyTransactionRowTwo()).isEqualTo(daily.get(1));
        assertThat(crossReference).contains(xrefFixtureRow(CARD_ONE, CUST_ONE, ACCT_ONE),
                xrefFixtureRow(CARD_TWO, CUST_TWO, ACCT_TWO));
        assertThat(accounts).contains(accountRowOne(), accountRowTwo());
        assertThat(accounts)
                .as("no account row carries %s, which is what makes it usable as an absent key",
                        ACCT_ABSENT)
                .noneMatch(row -> row.startsWith(digits(ACCT_ABSENT, ACCOUNT_ID_WIDTH)));
        assertThat(crossReference)
                .as("no cross-reference row carries %s, which is what makes it usable as an absent key",
                        CARD_ABSENT)
                .noneMatch(row -> row.startsWith(CARD_ABSENT));
    }

    /**
     * The cross-reference fixture is 36 bytes wide, its copybook declares 50, and the case's declared
     * normalisation is what closes the gap - at seed time, before anything decodes a row (gate G16).
     *
     * <p>The 14 bytes {@code FILLER PIC X(14)} occupies are supplied as spaces, so a decoded record
     * addresses {@code FILLER} as fourteen spaces and the record measures the 50 bytes
     * {@code app/cpy/CVACT03Y.cpy} declares. Every case that seeds the cross reference declares the pad,
     * which {@link #everyCrossReferenceSeedDeclaresThePad()} asserts for the set.
     */
    @Test
    @DisplayName("the 36-byte cross-reference row pads to the 50 bytes CVACT03Y declares (G16)")
    void theCrossReferenceRowPadsFromThirtySixToFifty() {
        String fixtureRow = xrefFixtureRow(CARD_ONE, CUST_ONE, ACCT_ONE);
        String recordImage = xrefRecordImage(CARD_ONE, CUST_ONE, ACCT_ONE);

        assertThat(fixtureRow).hasSize(XREF_FIXTURE_WIDTH);
        assertThat(recordImage).hasSize(XREF_WIDTH)
                .startsWith(fixtureRow)
                .endsWith(blanks(CardXrefRecord.FILLER_LENGTH));
        assertThat(ParityCase.Normalisation.CARDXREF_FILLER_PAD_36_TO_50.normaliseSeedRow(fixtureRow,
                XREFFILE)).isEqualTo(recordImage);
    }

    /**
     * Every case that seeds the cross reference declares the 36-to-50 pad, and no case declares it for a
     * dataset that pad does not describe.
     */
    @Test
    @DisplayName("every cross-reference seed declares the 36-to-50 pad and nothing else does")
    void everyCrossReferenceSeedDeclaresThePad() {
        for (ParityScenario scenario : cases()) {
            ParityCase declared = scenario.parityCase();
            boolean seedsRows = declared.inputs().containsKey(XREFFILE)
                    && !declared.inputs().get(XREFFILE).declaredEmpty();

            assertThat(declared.normalisations())
                    .as("%s seeds %d cross-reference row set(s)", scenario.caseId(), seedsRows ? 1 : 0)
                    .containsExactlyElementsOf(seedsRows
                            ? List.of(new ParityCase.DatasetNormalisation(XREFFILE,
                                    ParityCase.Normalisation.CARDXREF_FILLER_PAD_36_TO_50))
                            : List.of());
        }
    }

    /**
     * A negative zoned overpunch survives the journey from a seeded row to a decoded amount (gates G22
     * and G24).
     *
     * <p>{@code dailytran.txt} row 1 carries {@code DALYTRAN-AMT} as {@code 0000009190}}, and the
     * trailing {@code '}'} is the overpunch of a negative zero: the value is {@code -919.00}. Decoded
     * through {@link FixedWidthCodec} at {@code PIC S9(09)V99}'s scale of two it is a
     * {@link BigDecimal}, never a {@code double} - and its raw span survives the run untouched, which is
     * what {@code case09} asserts on the final-state channel.
     */
    @Test
    @DisplayName("a negative zoned overpunch decodes to a scale-2 BigDecimal, not to a positive amount")
    void theNegativeOverpunchDecodesThroughTheCodec() {
        FixedWidthCodec codec = new FixedWidthCodec(ASCII);
        DalyTranRecord record = DalyTranRecord.decode(
                codec.encodeImage(dailyTransactionRowTwo(), "a DALYTRAN row"), ASCII);

        assertThat(record.dalytranAmtImage()).isEqualTo(AMOUNT_NEGATIVE);
        assertThat(record.dalytranAmt())
                .isEqualByComparingTo(AMOUNT_NEGATIVE_VALUE)
                .isNegative();
        assertThat(record.dalytranAmt().scale()).isEqualTo(2);
        assertThat(record.displayImage())
                .as("DISPLAY DALYTRAN-RECORD at :168 writes the whole group item, overpunch and all")
                .hasSize(DALYTRAN_WIDTH)
                .isEqualTo(dailyTransactionRowTwo());
    }

    // =================================================================================================
    // Gate G13 - the orphan is runnable, and nothing triggers it.
    // =================================================================================================

    /**
     * The job, its single step and its tasklet are all real, so the orphan can be launched deliberately.
     */
    @Test
    @DisplayName("Gate G13 - the orphan job, its step and its tasklet are all real and launchable")
    void theOrphanJobIsRunnable() {
        PostingRun unit = new PostingRun(scenarioOf(4));
        TransactionPostingJob job = unit.jobOverEmptyDatasets();

        assertThat(job.transactionPostingJob()).isNotNull();
        assertThat(job.transactionPostingJob().getName()).isEqualTo(TransactionPostingJob.JOB_NAME);
        assertThat(job.transactionPostingStep()).isNotNull();
        assertThat(job.transactionPostingStep().getName()).isEqualTo(TransactionPostingJob.STEP_NAME);
        assertThat(job.transactionPostingTasklet()).isNotNull();
        assertThat(job.jobParameters().getParameters())
                .as("no JCL declares an EXEC card for CBTRN01C, so the job takes no parameter")
                .isEmpty();
    }

    /**
     * Nothing schedules, triggers or auto-runs the orphan (gate G13, requirement I4).
     *
     * <p>Asserted by reflection over the class itself rather than by observing that a run did not happen,
     * because "no trigger exists" is a statement about the code and not about one execution. A
     * {@code @Scheduled} method, an {@code @EnableScheduling} annotation, a {@code CommandLineRunner} or
     * a held {@code JobLauncher} would each turn a job that is meant to be launched deliberately into one
     * that runs itself.
     */
    @Test
    @DisplayName("Gate G13 - no schedule, no runner, no launcher: the orphan runs only when asked")
    void nothingTriggersTheOrphanJob() {
        assertThat(TransactionPostingJob.class.getDeclaredAnnotations())
                .as("@Configuration and nothing else; @Scheduled or @EnableScheduling here would run "
                        + "the orphan on a timer")
                .hasSize(1);
        assertThat(TransactionPostingJob.class.getInterfaces())
                .as("no CommandLineRunner, no ApplicationRunner, no InitializingBean")
                .isEmpty();
        assertThat(Stream.of(TransactionPostingJob.class.getDeclaredFields())
                .map(field -> field.getType().getName()))
                .as("no JobLauncher, runner or scheduler is held as state")
                .noneMatch(name -> name.contains("JobLauncher") || name.contains("Runner")
                        || name.contains("Scheduler"));
        assertThat(Stream.of(TransactionPostingJob.class.getDeclaredMethods())
                .flatMap(method -> Stream.of(method.getDeclaredAnnotations()))
                .map(annotation -> annotation.annotationType().getName()))
                .as("no method carries a scheduling annotation")
                .noneMatch(name -> name.contains("Scheduled"));
    }

    // =================================================================================================
    // Two assertions the twenty cases have no slot for, and one they make collectively.
    // =================================================================================================

    /**
     * The two close arms no case has a slot for: the account file's and the transaction file's.
     *
     * <p>Twelve file verbs can each fail and each abends, which is twelve cases; twenty slots cannot hold
     * those twelve plus the ten behaviours the pass itself has. These two are the least load-bearing of
     * the twelve - neither carries a defect and neither is the file the class name promises to post to -
     * so they are asserted here, directly against the emitted lines, rather than displacing a case.
     * Each still proves what a case would: the paragraph's own literal, the failing file's own status,
     * the abend line, and a {@code RETURN-CODE} of 12.
     */
    @Test
    @DisplayName("the account and transaction close failures abend with their own literals and RC 12")
    void theRemainingTwoCloseArmsAbendWithTheirOwnLiterals() {
        for (Map.Entry<String, String> arm : Map.of(ACCTFILE, ERROR_CLOSING_ACCTFILE,
                TRANFILE, ERROR_CLOSING_TRANFILE).entrySet()) {
            PostingRun unit = new PostingRun(scenarioOf(4),
                    Injection.closeFailure(arm.getKey(), STATUS_OPEN_FAILED));

            AbendException abend = unit.runExpectingAbend();

            assertThat(unit.lines())
                    .as("the close of %s failed, so its own paragraph's literal is displayed",
                            arm.getKey())
                    .containsExactlyElementsOf(concat(List.of(
                            List.of(START_BANNER),
                            skipLines(blanks(CARD_NUMBER_WIDTH), blanks(TRANSACTION_ID_WIDTH)),
                            abendLines(arm.getValue(), STATUS_OPEN_FAILED))));
            assertThat(unit.trace())
                    .as("every close up to and including %s was issued, and none after it", arm.getKey())
                    .containsExactlyElementsOf(concat(List.of(openTrace(ALL_DDS.size()),
                            recordTrace(false), closeTrace(ALL_DDS.indexOf(arm.getKey()) + 1))));
            assertThat(abend.getReturnCode())
                    .as("MOVE 12 TO APPL-RESULT precedes CALL 'CEE3ABD' at :473 (gate G35)")
                    .isEqualTo(RETURN_CODE_FATAL);
            assertThat(abend.getProgram()).isEqualTo(PROGRAM);
        }
    }

    /**
     * The customer file and the card file are opened, closed and <strong>never read</strong>.
     *
     * <p>{@code 0100-CUSTFILE-OPEN} and {@code 9100-CUSTFILE-CLOSE} are two of this program's twelve file
     * verbs, and {@code 0300-CARDFILE-OPEN} and {@code 9300-CARDFILE-CLOSE} are two more, but no
     * paragraph anywhere reads either file. Proving that from the fingerprint alone is impossible - an
     * unused read produces no line - so it is proved from the collaborators: not one read method is
     * invoked on either repository during {@code case11}, the one case that seeds <em>all six</em> of
     * this program's datasets with real fixture rows, so that "never read" is a statement about files
     * that hold records rather than about files that are empty.
     */
    @Test
    @DisplayName("CUSTFILE and CARDFILE are opened and closed but never read, both of them non-empty")
    void theCustomerAndCardFilesAreNeverRead() {
        PostingRun unit = new PostingRun(scenarioOf(11));

        ExecutionSummary summary = unit.run();

        assertThat(summary.recordsRead())
                .as("case11 is the case that seeds all six datasets, so 'never read' is a statement "
                        + "about files that hold rows rather than about files that are empty")
                .isEqualTo(2);
        assertThat(unit.customerFileVerbs())
                .as("0100-CUSTFILE-OPEN and 9100-CUSTFILE-CLOSE are the only customer-file verbs")
                .isNotEmpty()
                .noneMatch(verb -> verb.startsWith("read"));
        assertThat(unit.cardFileVerbs())
                .as("0300-CARDFILE-OPEN and 9300-CARDFILE-CLOSE are the only card-file verbs")
                .isNotEmpty()
                .noneMatch(verb -> verb.startsWith("read"));
        assertThat(unit.transactionFileVerbs())
                .as("0500-TRANFILE-OPEN and 9500-TRANFILE-CLOSE are the only transaction-file verbs: "
                        + "the posting this job's name promises is exactly the write it never issues")
                .isNotEmpty()
                .noneMatch(verb -> verb.startsWith("read") || verb.startsWith("write")
                        || verb.startsWith("rewrite") || verb.startsWith("openOutput"));
        assertThat(unit.trace())
                .as("no trace entry reads either file")
                .doesNotContain(TRACE_READ + CUSTFILE, TRACE_READ + CARDFILE, TRACE_READ + TRANFILE);
    }

    /**
     * A run performs one more cross-reference lookup than it reads records - always, and never fewer.
     *
     * <p>The post-end-of-file lookup expressed as a number, over the three run shapes the shipped cases
     * provide: {@code case04}'s empty dataset, {@code case01}'s single record and {@code case10}'s six.
     * The summary's own constructor enforces the relation, so this asserts the numbers a real run
     * reported rather than a relation a builder could have imposed.
     */
    @Test
    @DisplayName("xrefLookups is always recordsRead + 1: the stale lookup happens on every run")
    void everyRunPerformsOneLookupBeyondItsRecordCount() {
        for (ParityScenario scenario : List.of(scenarioOf(4), scenarioOf(1), scenarioOf(10))) {
            PostingRun unit = new PostingRun(scenario);

            ExecutionSummary summary = unit.run();

            assertThat(summary.xrefLookups())
                    .as("%s read %d record(s)", scenario.caseId(), summary.recordsRead())
                    .isEqualTo(summary.recordsRead() + 1);
            assertThat(summary.postEndOfFileLookups()).isOne();
            assertThat(summary.recordImageLinesDisplayed())
                    .as("one DISPLAY DALYTRAN-RECORD per record read, from the mainline at :168 only")
                    .isEqualTo(summary.recordsRead());
            assertThat(summary.returnCode()).isEqualTo(RETURN_CODE_OK);
            assertThat(summary.completedCleanly()).isTrue();
        }
    }

    // =================================================================================================
    // The case model: a shipped ParityCase, and the decoder that turns its declared stimulus into the
    // backend conditions the adapter's stubs answer with.
    // =================================================================================================

    /**
     * One case, exactly as {@code src/test/resources/parity/CBTRN01C/} ships it.
     *
     * <p>A wrapper rather than the {@link ParityCase} itself for one reason: a parameterized test's
     * display name is the argument's {@code toString()}, and a whole case renders as several kilobytes of
     * prose. Everything the adapter needs - the seeds, the stimulus, the expected operations - comes from
     * the case, so there is nothing else to carry.
     *
     * @param parityCase the case the differ judges and the adapter reproduces
     */
    private record ParityScenario(ParityCase parityCase) {

        /**
         * @param parityCase the shipped case
         */
        private ParityScenario {
            Objects.requireNonNull(parityCase, "A ParityCase is required: it is the expectation side");
        }

        /** @return {@code case01} through {@code case20} */
        private String caseId() {
            return parityCase.caseId();
        }

        /**
         * @return the case identifier, which is what a parameterized test's display name shows and all a
         *         reader needs to find the declaration
         */
        @Override
        public String toString() {
            return parityCase.caseId();
        }
    }

    // =================================================================================================
    // The injected backend conditions. Every arm the seeded data cannot reach - a failing open, a
    // failing close, a read that reports something the ladder does not name - is reached from here, and
    // every one of them is DECLARED by the case rather than selected in Java by case identifier.
    // =================================================================================================

    /**
     * Decodes a case's declared stimulus into the backend conditions its stubs answer with.
     *
     * <p>The one place a call-site name becomes a stub's behaviour. Six DDs times three verbs is
     * eighteen call sites, each named {@code <VERB>-<DD>} - {@code OPEN-CARDFILE},
     * {@code READ-DALYTRAN}, {@code CLOSE-CUSTFILE} - so a case reads as the paragraph names it and this
     * method is the only thing that has to know the correspondence. A name that matches no site is
     * refused here rather than silently ignored, because a silently ignored control is a case asserting
     * the opposite of what it says.
     *
     * <p>Two sites are irregular, and both irregularities are the module's rather than this method's.
     * The card file reports its open as a CICS response because {@code CardRepository} exists mainly for
     * seventeen online callers, so {@code OPEN-CARDFILE} may declare {@code resp} instead of
     * {@code status}; and ending the card browse reports nothing at all, so {@code CLOSE-CARDFILE} can
     * only be {@code refused}. A {@code refused} outcome anywhere else means the dataset was
     * unreachable, which this module reports as its own permanent-error status - the {@code '9'} plus
     * low-value pair that {@code Z-DISPLAY-IO-STATUS} renders as {@code 9000}, and the one status no
     * JSON string can hold.
     *
     * @param stimulus the case's declared stimulus; {@link ParityCase.UnitStimulus#NONE} for the
     *                 fourteen cases whose whole stimulus is their seed
     * @return the conditions to drive
     * @throws IllegalArgumentException if a declared call site is not one of this program's eighteen, if
     *                                  a case declares an operation script, linkage, a step status or an
     *                                  environment variant - none of which this program has - or if a
     *                                  site declares an outcome shape that site cannot report
     */
    private static Injection injectionFrom(ParityCase.UnitStimulus stimulus) {
        if (!stimulus.operationScript().isEmpty() || !stimulus.linkage().isEmpty()
                || !stimulus.stepStatuses().isEmpty() || !stimulus.environment().isEmpty()) {
            throw new IllegalArgumentException("CBTRN01C takes no linkage, calls no subprogram, follows "
                    + "no job step and runs under no environment variant: it is an orphan with no JCL "
                    + "at all, so its only stimulus is its seeded rows and the outcome of one of its "
                    + "eighteen file call sites.");
        }
        Map<String, String> openStatus = new LinkedHashMap<>();
        Map<String, String> closeStatus = new LinkedHashMap<>();
        Integer cardOpenResp = null;
        boolean cardCloseRefused = false;
        String readStatus = null;
        int goodReads = 0;
        String xrefStatus = null;
        String accountStatus = null;
        for (Map.Entry<String, ParityCase.CallSiteOutcome> declared
                : stimulus.callSiteOutcomes().entrySet()) {
            String site = declared.getKey();
            ParityCase.CallSiteOutcome outcome = declared.getValue();
            String dataset = datasetOfCallSite(site);
            if (site.equals(TRACE_OPEN.strip() + '-' + dataset)) {
                if (CARDFILE.equals(dataset) && outcome.resp() != null) {
                    cardOpenResp = outcome.resp();
                } else {
                    openStatus.put(dataset, statusOf(site, outcome));
                }
            } else if (site.equals(TRACE_CLOSE.strip() + '-' + dataset)) {
                if (CARDFILE.equals(dataset)) {
                    requireRefusal(site, outcome);
                    cardCloseRefused = true;
                } else {
                    closeStatus.put(dataset, statusOf(site, outcome));
                }
            } else if (DALYTRAN.equals(dataset)) {
                readStatus = statusOf(site, outcome);
                goodReads = outcome.recordsBefore();
            } else if (XREFFILE.equals(dataset)) {
                xrefStatus = statusOf(site, outcome);
            } else {
                accountStatus = statusOf(site, outcome);
            }
        }
        return new Injection(openStatus, closeStatus, cardOpenResp, cardCloseRefused, readStatus,
                goodReads, xrefStatus, accountStatus);
    }

    /**
     * @param site the declared call-site name
     * @return the DD it addresses
     * @throws IllegalArgumentException if the name is not {@code OPEN-}, {@code READ-} or {@code CLOSE-}
     *                                  followed by one of the six DDs, or names a read of a file this
     *                                  program never reads
     */
    private static String datasetOfCallSite(String site) {
        for (String verb : List.of(TRACE_OPEN, TRACE_READ, TRACE_CLOSE)) {
            String prefix = verb.strip() + '-';
            if (!site.startsWith(prefix)) {
                continue;
            }
            String dataset = site.substring(prefix.length());
            if (!ALL_DDS.contains(dataset)) {
                throw new IllegalArgumentException("Call site " + site + " names DD " + dataset
                        + ", which CBTRN01C declares no SELECT for. The six it declares are "
                        + ALL_DDS + '.');
            }
            if (TRACE_READ.equals(verb) && !READ_DDS.contains(dataset)) {
                throw new IllegalArgumentException("Call site " + site + " scripts a read of " + dataset
                        + ", which CBTRN01C opens and closes but never reads. The three it reads are "
                        + READ_DDS + ", so an outcome here would be scripted for a call that is never "
                        + "made and the case would assert nothing.");
            }
            return dataset;
        }
        throw new IllegalArgumentException("Call site " + site + " is not one of CBTRN01C's eighteen: "
                + "each is OPEN-, READ- or CLOSE- followed by one of " + ALL_DDS + '.');
    }

    /**
     * @param site    the call site, for the failure message
     * @param outcome the declared outcome
     * @return the {@code FILE STATUS} it reports, translating a refusal into this module's
     *         permanent-error status
     * @throws IllegalArgumentException if the site declares a CICS response, which only the card file's
     *                                  open reports
     */
    private static String statusOf(String site, ParityCase.CallSiteOutcome outcome) {
        if (outcome.resp() != null) {
            throw new IllegalArgumentException("Call site " + site + " declares a CICS RESP, but the "
                    + "only open this module reports as a response is OPEN-" + CARDFILE + "'s. Declare "
                    + "a two-character FILE STATUS instead.");
        }
        if (outcome.status() != null) {
            return outcome.status();
        }
        return STATUS_PERMANENT_ERROR;
    }

    /**
     * @param site    the call site, for the failure message
     * @param outcome the declared outcome
     * @throws IllegalArgumentException if the outcome is anything other than a refusal
     */
    private static void requireRefusal(String site, ParityCase.CallSiteOutcome outcome) {
        if (!outcome.isRefused() || outcome.status() != null) {
            throw new IllegalArgumentException("Call site " + site + " reports no status at all: "
                    + "9300-CARDFILE-CLOSE ends a browse, and ending a browse either succeeds or is "
                    + "refused. Declare \"refused\": true.");
        }
    }

    /**
     * The backend conditions one run is driven under.
     *
     * @param openStatus               the status each DD's {@code OPEN} reports; a DD absent from the map
     *                                 reports {@code '00'}
     * @param closeStatus              the status each DD's {@code CLOSE} reports; absent means {@code '00'}
     * @param cardOpenResp             the CICS response the card browse's open reports, or {@code null}
     *                                 for {@code DFHRESP(NORMAL)} - the card file is the one open this
     *                                 module reports as a response rather than as a file status
     * @param cardCloseRefused         whether ending the card browse is refused, which is the only way
     *                                 that close can fail: ending a browse reports no status
     * @param readStatus               the status the sequential read reports after
     *                                 {@code goodReadsBeforeReadStatus} records, or {@code null} to yield
     *                                 every seeded record and then end of file
     * @param goodReadsBeforeReadStatus how many records are read successfully before {@code readStatus}
     * @param xrefStatus               the status every cross-reference read reports, or {@code null} to
     *                                 answer from the seeded rows
     * @param accountStatus            the status every account read reports, or {@code null} to answer
     *                                 from the seeded rows
     */
    private record Injection(Map<String, String> openStatus, Map<String, String> closeStatus,
                             Integer cardOpenResp, boolean cardCloseRefused, String readStatus,
                             int goodReadsBeforeReadStatus, String xrefStatus, String accountStatus) {

        /**
         * Freezes both maps and refuses a DD name this program does not declare.
         *
         * @throws IllegalArgumentException if a key is not one of the six DDs, or a status is not two
         *                                  characters, or the read count is negative
         */
        private Injection {
            openStatus = requireStatusesByDd(openStatus, "openStatus");
            closeStatus = requireStatusesByDd(closeStatus, "closeStatus");
            if (readStatus != null && readStatus.length() != 2) {
                throw new IllegalArgumentException("A FILE STATUS is exactly two characters, as "
                        + "DALYTRAN-STATUS is declared at app/cbl/CBTRN01C.cbl:100-102; got '"
                        + readStatus + '\'');
            }
            if (goodReadsBeforeReadStatus < 0) {
                throw new IllegalArgumentException("A run cannot read " + goodReadsBeforeReadStatus
                        + " records before its read fails");
            }
        }

        /** @return conditions under which every verb succeeds - the shape fourteen of the cases run under */
        private static Injection none() {
            return new Injection(Map.of(), Map.of(), null, false, null, 0, null, null);
        }

        /**
         * @param dataset the DD whose open fails
         * @param status  the status it reports
         * @return the conditions
         */
        private static Injection openFailure(String dataset, String status) {
            return new Injection(Map.of(dataset, status), Map.of(), null, false, null, 0, null, null);
        }

        /**
         * @param resp the CICS response the card browse's open reports
         * @return the conditions
         */
        private static Injection cardOpenResp(int resp) {
            return new Injection(Map.of(), Map.of(), resp, false, null, 0, null, null);
        }

        /**
         * @param dataset the DD whose close fails
         * @param status  the status it reports
         * @return the conditions
         */
        private static Injection closeFailure(String dataset, String status) {
            return new Injection(Map.of(), Map.of(dataset, status), null, false, null, 0, null, null);
        }

        /** @return conditions under which ending the card browse is refused */
        private static Injection refusedCardClose() {
            return new Injection(Map.of(), Map.of(), null, true, null, 0, null, null);
        }

        /**
         * @param goodReads how many records are read before the failure
         * @param status    the status the next read reports
         * @return the conditions
         */
        private static Injection readFailure(int goodReads, String status) {
            return new Injection(Map.of(), Map.of(), null, false, status, goodReads, null, null);
        }

        /**
         * @param status the status every cross-reference read reports
         * @return the conditions
         */
        private static Injection xrefStatus(String status) {
            return new Injection(Map.of(), Map.of(), null, false, null, 0, status, null);
        }

        /**
         * @param status the status every account read reports
         * @return the conditions
         */
        private static Injection accountStatus(String status) {
            return new Injection(Map.of(), Map.of(), null, false, null, 0, null, status);
        }

        /**
         * @param dataset the DD
         * @return the status that DD's open reports
         */
        private String openStatusOf(String dataset) {
            return openStatus.getOrDefault(dataset, STATUS_OK);
        }

        /**
         * @param dataset the DD
         * @return the status that DD's close reports
         */
        private String closeStatusOf(String dataset) {
            return closeStatus.getOrDefault(dataset, STATUS_OK);
        }

        /** @return the CICS response the card browse's open reports */
        private int cardOpenRespOrNormal() {
            return cardOpenResp == null ? CICS_RESP_NORMAL : cardOpenResp;
        }

        /**
         * @param source the declared map
         * @param member the member being validated, named in a failure
         * @return an unmodifiable copy
         */
        private static Map<String, String> requireStatusesByDd(Map<String, String> source,
                String member) {
            Objects.requireNonNull(source, "Injection." + member + " is required; use Map.of()");
            for (Map.Entry<String, String> entry : source.entrySet()) {
                if (!ALL_DDS.contains(entry.getKey())) {
                    throw new IllegalArgumentException("Injection." + member + " names DD "
                            + entry.getKey() + ", which CBTRN01C declares no SELECT for, so nothing "
                            + "would ever ask for it and the injection would force nothing. The six it "
                            + "declares are " + ALL_DDS + '.');
                }
                if (entry.getValue() == null || entry.getValue().length() != 2) {
                    throw new IllegalArgumentException("Injection." + member + '[' + entry.getKey()
                            + "] is not a two-character FILE STATUS");
                }
            }
            return Map.copyOf(source);
        }
    }

    /**
     * @param injection the conditions
     * @param dataset   the DD
     * @return the batch {@code FILE STATUS} that DD's open reports, translating the card file's CICS
     *         response the way {@code 0300-CARDFILE-OPEN}'s seam does
     */
    private static String effectiveOpenStatus(Injection injection, String dataset) {
        return CARDFILE.equals(dataset)
                ? batchStatusOfCicsResponse(injection.cardOpenRespOrNormal())
                : injection.openStatusOf(dataset);
    }

    /**
     * The CICS-to-batch status correspondence, transcribed rather than borrowed so the case and the
     * translation derive it separately.
     *
     * @param resp the CICS response
     * @return the batch file status it corresponds to, or the permanent-error status where there is none
     */
    private static String batchStatusOfCicsResponse(int resp) {
        if (resp == CICS_RESP_NORMAL) {
            return STATUS_OK;
        }
        if (resp == CICS_RESP_NOTFND) {
            return STATUS_NOT_FOUND;
        }
        if (resp == CICS_RESP_DUPREC) {
            return STATUS_DUPLICATE;
        }
        return STATUS_PERMANENT_ERROR;
    }

    // =================================================================================================
    // THE ADAPTER. How the harness reaches CBTRN01C's body with nothing in between.
    // =================================================================================================

    /**
     * Constructs the job over one case's seeded datasets and calls the program body.
     *
     * <p>Everything is per-instance: the six collaborators, the captured lines, the verb trace and the
     * job itself belong to one {@code PostingRun} and are shared with nothing, so no two cases can see
     * each other's state and this class holds no mutable static field anywhere (practice B9, gate G53).
     *
     * <p>Observations go into {@link ParityHarness.Invocation#recorder()} <em>before</em> an
     * {@link AbendException} is allowed to leave, which is the only ordering that works: ten of the
     * twenty cases abend, several after a full iteration has already displayed six lines, and a method's
     * return value cannot carry an observation past an exception. The recorder can, and the harness
     * drains it and takes the {@code RETURN-CODE} from the abend.
     *
     * <p>The case's {@code jobParameters} are never read, because there are none to read: no JCL
     * declares an {@code EXEC} card for this program.
     */
    private static final class PostingRun implements ParityHarness.ParityUnit {

        /** The case this run reproduces. */
        private final ParityScenario scenario;

        /** The backend conditions this run is driven under. */
        private final Injection injection;

        /** Every {@code DISPLAY} line, in emission order. */
        private final List<String> lines = new ArrayList<>();

        /** Every file verb, in the order it was issued. */
        private final List<String> trace = new ArrayList<>();

        /**
         * Every read the run issued, in order, in the vocabulary the case declares them in: the daily
         * file's sequential read carries no key, and the two keyed reads carry the key they were given.
         */
        private final List<ParityCase.ExpectedOperation> operations = new ArrayList<>();

        /** The DDs whose {@code OPEN} reported {@code '00'}, in open order. */
        private final Set<String> openedOk = new LinkedHashSet<>();

        /**
         * The customer repository - opened and closed, never read. Retained, along with the three
         * collaborators below, purely so the assertions that no read or write verb ever reached the
         * three files this program opens and does not use can inspect the recorded invocations. The
         * other three collaborators are locals in their builders, because nothing inspects them.
         */
        private CustomerRepository customerRepository;

        /** The customer file handle. */
        private CustomerRepository.CustomerFile custfile;

        /** The card repository - opened and closed, never read. */
        private CardRepository cardRepository;

        /** The card browse, which is how this module reports the card file's open. */
        private CardRepository.CardBrowse cardBrowse;

        /** The transaction repository - opened and closed, never read and never written. */
        private TransactionRepository transactionRepository;

        /** The transaction input handle. */
        private TransactionRepository.InputFile tranfile;

        /** What the run reported, or {@code null} when it abended. */
        private ExecutionSummary summary;

        /** The abend the run raised, or {@code null} when it returned. */
        private AbendException abend;

        /**
         * Reproduces a shipped case under the stimulus that case declares.
         *
         * @param scenario the case to reproduce
         */
        private PostingRun(ParityScenario scenario) {
            this(scenario, injectionFrom(scenario.parityCase().unitStimulus()));
        }

        /**
         * Reproduces a case's seeds under conditions supplied here instead of by the case.
         *
         * <p>Used only by the three assertions the twenty cases have no slot for - the two close arms and
         * the refused browse end - which borrow a shipped case's seeds and drive a condition of their
         * own. The gate never uses it: {@link #producesNoDifferences(ParityScenario)} always goes through
         * the one-argument constructor, so a case's stimulus is only ever the stimulus it declares.
         *
         * @param scenario  the case whose seeds and expectations to use
         * @param injection the conditions to drive instead of the case's own
         */
        private PostingRun(ParityScenario scenario, Injection injection) {
            this.scenario = Objects.requireNonNull(scenario, "A scenario is required");
            this.injection = Objects.requireNonNull(injection, "An Injection is required");
        }

        @Override
        public ParityHarness.UnitOutcome invoke(ParityHarness.Invocation invocation) {
            TransactionPostingJob job = job(seededInputs(invocation));
            ParityHarness.UnitOutcome.Builder recorder = invocation.recorder();
            try {
                summary = job.execute(lines::add);
                record(recorder, invocation);
                recorder.returnCode(summary.returnCode());
            } catch (AbendException raised) {
                abend = raised;
                record(recorder, invocation);
                throw raised;
            }
            return null;
        }

        /**
         * Reports what the run produced: that every dataset it reached was written to zero times, what
         * every dataset it reached held afterwards, and every {@code DISPLAY} line in emission order.
         *
         * <p>The writes channel is reported for every dataset whose {@code OPEN} succeeded and is
         * reported as <em>opened and not written to</em>, which is a stronger statement than omitting
         * the dataset: omitting it would say only that the case never mentioned it, while this says the
         * run reached it and produced no record. That is the whole claim of this gate, and it is made in
         * every case rather than in one.
         *
         * <p>The final-state channel is reported for <strong>the same set, unconditionally</strong>, and
         * that is deliberate. What is observable is decided by what the run reached - a DD whose
         * {@code OPEN} reported {@code '00'} is allocated and readable whether or not any paragraph
         * touched it - and never by what the case expects. Consulting the expectation to decide what to
         * observe would make the two sides of the comparison one side: a dataset the case forgot would
         * also be a dataset nobody looked at, and the case would pass by omission. So every reached
         * dataset is reported and every case accounts for all of them, including the empty ones, whose
         * zero rows are themselves the assertion that this program posts nothing.
         *
         * @param recorder   where observations go
         * @param invocation the invocation holding the seeded rows
         */
        private void record(ParityHarness.UnitOutcome.Builder recorder,
                ParityHarness.Invocation invocation) {
            for (String dataset : openedOk) {
                recorder.openedWithoutWriting(dataset, layoutOf(dataset));
                recorder.finalState(dataset, layoutOf(dataset), rowsOf(invocation, dataset));
            }
            for (String line : lines) {
                recorder.display(line);
            }
        }

        /**
         * Runs the case through the harness - so that the seeding, the 36-to-50 pad and the code page are
         * the harness's own - and returns what the program reported.
         *
         * @return the summary
         * @throws IllegalStateException if the run abended, which a caller asking for a summary did not
         *                               expect
         */
        private ExecutionSummary run() {
            ParityHarness.usAscii().run(scenario.parityCase(), UNIT_KIND, this);
            if (summary == null) {
                throw new IllegalStateException("Case " + scenario.caseId() + " abended, so it has no "
                        + "ExecutionSummary. Use runExpectingAbend() for a case that abends.");
            }
            return summary;
        }

        /**
         * Runs the case and returns the abend it raised.
         *
         * <p>The harness treats an abend as an observation rather than a failure - it drains the recorder
         * and takes the {@code RETURN-CODE} - so the exception itself is captured on the way through
         * rather than caught from the harness.
         *
         * @return the abend
         * @throws IllegalStateException if the run did not abend
         */
        private AbendException runExpectingAbend() {
            ParityHarness.usAscii().run(scenario.parityCase(), UNIT_KIND, this);
            if (abend == null) {
                throw new IllegalStateException("Case " + scenario.caseId() + " was expected to abend "
                        + "and returned normally instead, which means the arm under test was never "
                        + "reached and the assertions below would pass for the wrong reason.");
            }
            return abend;
        }

        /**
         * The job over collaborators that hold nothing, for the assertions about the published beans.
         *
         * @return the job
         */
        private TransactionPostingJob jobOverEmptyDatasets() {
            return job(new SeededInputs(List.of(), Map.of(), Map.of()));
        }

        /** @return every {@code DISPLAY} line, in emission order */
        private List<String> lines() {
            return List.copyOf(lines);
        }

        /** @return every file verb, in the order it was issued */
        private List<String> trace() {
            return List.copyOf(trace);
        }

        /** @return every read the run issued, in order, keys included */
        private List<ParityCase.ExpectedOperation> operations() {
            return List.copyOf(operations);
        }

        /**
         * @return the {@code OPEN} and {@code CLOSE} entries of the trace, in issue order, with the reads
         *         between them removed - the sequence {@code MAIN-PARA} is responsible for
         */
        private List<String> openAndCloseTrace() {
            return trace.stream().filter(entry -> !entry.startsWith(TRACE_READ)).toList();
        }

        /** @return every method invoked on the customer repository and its handle */
        private List<String> customerFileVerbs() {
            return verbsOn(customerRepository, custfile);
        }

        /** @return every method invoked on the card repository and its browse */
        private List<String> cardFileVerbs() {
            return verbsOn(cardRepository, cardBrowse);
        }

        /**
         * @return every method invoked on the transaction repository and its handle - the direct
         *         evidence that this program, whose name promises posting, issues no write of any kind
         */
        private List<String> transactionFileVerbs() {
            return verbsOn(transactionRepository, tranfile);
        }

        /**
         * @param collaborators the mocks to inspect
         * @return the names of every method invoked on them, in invocation order per mock
         */
        private static List<String> verbsOn(Object... collaborators) {
            List<String> names = new ArrayList<>();
            for (Object collaborator : collaborators) {
                mockingDetails(collaborator).getInvocations()
                        .forEach(invocation -> names.add(invocation.getMethod().getName()));
            }
            return List.copyOf(names);
        }

        // -------------------------------------------------------------------------------------------
        // Observation. Each stub reports through one of these, so the trace records a verb only when the
        // program actually issued it.
        // -------------------------------------------------------------------------------------------

        /**
         * @param dataset the DD being opened
         * @param status  the status its open reports
         * @return {@code status}, so a stub can answer with it directly
         */
        private String opened(String dataset, String status) {
            trace.add(TRACE_OPEN + dataset);
            if (STATUS_OK.equals(status)) {
                openedOk.add(dataset);
            }
            return status;
        }

        /**
         * @param dataset the DD being opened
         * @param resp    the CICS response its open reports
         * @return {@code resp}
         */
        private int openedWithResponse(String dataset, int resp) {
            trace.add(TRACE_OPEN + dataset);
            if (STATUS_OK.equals(batchStatusOfCicsResponse(resp))) {
                openedOk.add(dataset);
            }
            return resp;
        }

        /**
         * @param dataset the DD being closed
         * @param status  the status its close reports
         * @return {@code status}
         */
        private String closed(String dataset, String status) {
            trace.add(TRACE_CLOSE + dataset);
            return status;
        }

        /**
         * The sequential read of the daily file, which carries no key: {@code READ DALYTRAN-FILE INTO}
         * at {@code :167} takes the next record, wherever the pass happens to be.
         */
        private void readNext() {
            trace.add(TRACE_READ + DALYTRAN);
            operations.add(new ParityCase.ExpectedOperation(DALYTRAN,
                    ParityCase.RepositoryOperation.READ_NEXT, null));
        }

        /**
         * A keyed read, recorded with the key it was given.
         *
         * <p>A key that is entirely blank is recorded as absent rather than as spaces, because that is
         * what it is: the post-end-of-file lookup at {@code :170-172} reads on whatever the record area
         * last held, and on an empty dataset it never held anything. That the lookup happened at all,
         * and with what, is asserted by the {@code CARD NUMBER: } line the fingerprint already pins.
         *
         * @param dataset the DD being read
         * @param key     the key the read was given
         */
        private void readByKey(String dataset, String key) {
            trace.add(TRACE_READ + dataset);
            String trimmed = key == null ? "" : key.strip();
            operations.add(new ParityCase.ExpectedOperation(dataset,
                    ParityCase.RepositoryOperation.READ, trimmed.isEmpty() ? null : trimmed));
        }

        // -------------------------------------------------------------------------------------------
        // Wiring. Six stubbed collaborators, all answering from the case's seeded rows, and the
        // contract and dataset catalogues configuration declares.
        // -------------------------------------------------------------------------------------------

        /**
         * @param inputs the decoded seed
         * @return the job over stubs that answer from it
         */
        private TransactionPostingJob job(SeededInputs inputs) {
            DalyTranRepository dalyTran = stubDalyTranRepository(inputs.daily());
            CardXrefRepository cardXref = stubCardXrefRepository(inputs.crossReference());
            AccountRepository account = stubAccountRepository(inputs.accounts());
            customerRepository = stubCustomerRepository();
            cardRepository = stubCardRepository();
            transactionRepository = stubTransactionRepository();
            // No SYSOUT bean is published, so nothing can reach the process's standard output: the sink
            // this run displays through is the one passed to execute, and it is a list in this instance.
            return new TransactionPostingJob(batchConfig(), dalyTran, customerRepository, cardXref,
                    cardRepository, account, transactionRepository,
                    new SuppliedProvider<SysoutSink>(null));
        }

        /**
         * {@code DALYTRAN} - one sequential pass that yields the seeded records and then end of file, or
         * the injected status once the injected number of records have been read.
         *
         * @param records the seeded records, in dataset order
         * @return the repository
         */
        private DalyTranRepository stubDalyTranRepository(List<DalyTranRecord> records) {
            DalyTranRepository repository = mock(DalyTranRepository.class);
            DalyTranRepository.DalytranFile dalytranFile = mock(DalyTranRepository.DalytranFile.class);
            when(repository.datasetCharset()).thenReturn(ASCII);
            when(repository.open()).thenReturn(dalytranFile);
            when(dalytranFile.openStatus())
                    .thenAnswer(call -> opened(DALYTRAN, injection.openStatusOf(DALYTRAN)));
            when(dalytranFile.closeFile())
                    .thenAnswer(call -> closed(DALYTRAN, injection.closeStatusOf(DALYTRAN)));
            Deque<DalyTranRepository.ReadResult> reads = readQueue(records);
            when(dalytranFile.readNext()).thenAnswer(call -> {
                readNext();
                return reads.isEmpty() ? DalyTranRepository.ReadResult.endOfFile() : reads.removeFirst();
            });
            return repository;
        }

        /**
         * The outcomes the sequential pass reports, in order: every seeded record and then end of file,
         * or the injected number of records and then the injected status.
         *
         * @param records the seeded records
         * @return the queue
         */
        private Deque<DalyTranRepository.ReadResult> readQueue(List<DalyTranRecord> records) {
            Deque<DalyTranRepository.ReadResult> queue = new ArrayDeque<>();
            int good = injection.readStatus() == null
                    ? records.size()
                    : Math.min(injection.goodReadsBeforeReadStatus(), records.size());
            for (int index = 0; index < good; index++) {
                queue.add(DalyTranRepository.ReadResult.found(records.get(index)));
            }
            queue.add(injection.readStatus() == null
                    ? DalyTranRepository.ReadResult.endOfFile()
                    : DalyTranRepository.ReadResult.other(injection.readStatus()));
            return queue;
        }

        /**
         * {@code CUSTFILE} - an open and a close, and no read method stubbed at all.
         *
         * <p>Deliberately bare: {@code CBTRN01C} never reads the customer file, so a read here would
         * return a Mockito default and the run would fail on it. That is the intended outcome - a
         * translation that started reading this file could not pass quietly.
         *
         * @return the repository
         */
        private CustomerRepository stubCustomerRepository() {
            CustomerRepository repository = mock(CustomerRepository.class);
            custfile = mock(CustomerRepository.CustomerFile.class);
            when(repository.openInput()).thenReturn(custfile);
            when(custfile.openStatus())
                    .thenAnswer(call -> opened(CUSTFILE, injection.openStatusOf(CUSTFILE)));
            when(custfile.closeFile())
                    .thenAnswer(call -> closed(CUSTFILE, injection.closeStatusOf(CUSTFILE)));
            return repository;
        }

        /**
         * {@code XREFFILE} - the {@code CCXREF} base cluster, read by the sixteen-character card number,
         * with the browse used purely as the {@code OPEN INPUT} seam.
         *
         * <p>{@code addressing} returns the same stub, which is what makes the keyed reads land here
         * after the job has rebased the repository onto its own DD. The alternate-index path is never
         * opened by this program, and nothing here answers for it (gate G45).
         *
         * @param rows the seeded cross-reference records, keyed by card number
         * @return the repository
         */
        private CardXrefRepository stubCardXrefRepository(Map<String, CardXrefRecord> rows) {
            CardXrefRepository repository = mock(CardXrefRepository.class);
            CardXrefRepository.BrowseCursor xrefCursor = mock(CardXrefRepository.BrowseCursor.class);
            when(repository.addressing(any(), anyString(), isNull(), anyString()))
                    .thenReturn(repository);
            when(repository.openBrowse()).thenReturn(xrefCursor);
            when(xrefCursor.openStatus())
                    .thenAnswer(call -> opened(XREFFILE, injection.openStatusOf(XREFFILE)));
            when(xrefCursor.closeBrowse())
                    .thenAnswer(call -> closed(XREFFILE, injection.closeStatusOf(XREFFILE)));
            when(repository.readByCardNumber(anyString())).thenAnswer(call -> {
                readByKey(XREFFILE, call.getArgument(0, String.class));
                CardXrefRecord found = rows.get(call.<String>getArgument(0));
                if (injection.xrefStatus() != null) {
                    return forcedXrefOutcome(injection.xrefStatus(), found);
                }
                return found == null
                        ? CardXrefRepository.ReadResult.notFound(XREFFILE)
                        : CardXrefRepository.ReadResult.found(XREFFILE, found, storedImage(found));
            });
            return repository;
        }

        /**
         * @param status the injected status
         * @param found  the record the seed holds for that key, if any
         * @return the outcome that status corresponds to
         * @throws IllegalStateException if a duplicate is injected for a key the case did not seed, since
         *                               CICS returns the first matching record alongside that condition
         */
        private static CardXrefRepository.ReadResult forcedXrefOutcome(String status,
                CardXrefRecord found) {
            if (STATUS_NOT_FOUND.equals(status)) {
                return CardXrefRepository.ReadResult.notFound(XREFFILE);
            }
            if (STATUS_DUPLICATE.equals(status)) {
                if (found == null) {
                    throw new IllegalStateException("A '" + STATUS_DUPLICATE + "' outcome carries the "
                            + "first matching record, because CICS returns one alongside the duplicate "
                            + "condition, so the case must seed the row it duplicates.");
                }
                return CardXrefRepository.ReadResult.duplicate(XREFFILE, found, storedImage(found),
                        CICS_RESP_DUPREC);
            }
            return CardXrefRepository.ReadResult.other(XREFFILE, status);
        }

        /**
         * {@code CARDFILE} - a browse whose open reports a CICS response, and an end that reports nothing.
         *
         * <p>No read method is stubbed, for the same reason as the customer file: this program never reads
         * the card file.
         *
         * @return the repository
         */
        private CardRepository stubCardRepository() {
            CardRepository repository = mock(CardRepository.class);
            cardBrowse = mock(CardRepository.CardBrowse.class);
            when(repository.addressing(any(), anyString())).thenReturn(repository);
            when(repository.openBrowse(anyString(), any())).thenReturn(cardBrowse);
            when(cardBrowse.openResp())
                    .thenAnswer(call -> openedWithResponse(CARDFILE, injection.cardOpenRespOrNormal()));
            doAnswer(call -> {
                closed(CARDFILE, STATUS_OK);
                if (injection.cardCloseRefused()) {
                    throw new IllegalStateException("the card browse could not be ended");
                }
                return null;
            }).when(cardBrowse).endBrowse();
            return repository;
        }

        /**
         * {@code ACCTFILE} - opened {@code INPUT} and read by the eleven-digit account id.
         *
         * <p>Only {@code OpenMode.INPUT} is stubbed, so an open for update would return nothing and fail
         * the run: {@code CBTRN01C} reads accounts and never rewrites one.
         *
         * @param rows the seeded account records, keyed by account id
         * @return the repository
         */
        private AccountRepository stubAccountRepository(Map<Long, AccountRecord> rows) {
            AccountRepository repository = mock(AccountRepository.class);
            AccountRepository.AccountFile acctfile = mock(AccountRepository.AccountFile.class);
            when(repository.open(AccountRepository.OpenMode.INPUT)).thenReturn(acctfile);
            when(acctfile.openStatus())
                    .thenAnswer(call -> opened(ACCTFILE, injection.openStatusOf(ACCTFILE)));
            when(acctfile.closeFile())
                    .thenAnswer(call -> closed(ACCTFILE, injection.closeStatusOf(ACCTFILE)));
            when(acctfile.readByKey(anyLong())).thenAnswer(call -> {
                readByKey(ACCTFILE, ACCOUNT_KEY_CODEC.movePic9(call.getArgument(0, Long.class),
                        ACCOUNT_ID_WIDTH));
                if (injection.accountStatus() != null) {
                    return AccountRepository.ReadResult.of(injection.accountStatus());
                }
                AccountRecord found = rows.get(call.<Long>getArgument(0));
                return found == null
                        ? AccountRepository.ReadResult.notFound()
                        : AccountRepository.ReadResult.found(found);
            });
            return repository;
        }

        /**
         * {@code TRANFILE} - an open and a close, and nothing else: not one read, and above all not one
         * write.
         *
         * @return the repository
         */
        private TransactionRepository stubTransactionRepository() {
            TransactionRepository repository = mock(TransactionRepository.class);
            tranfile = mock(TransactionRepository.InputFile.class);
            when(repository.openInput(any(DatasetBinding.class))).thenReturn(tranfile);
            when(tranfile.openStatus())
                    .thenAnswer(call -> opened(TRANFILE, injection.openStatusOf(TRANFILE)));
            when(tranfile.closeInput())
                    .thenAnswer(call -> closed(TRANFILE, injection.closeStatusOf(TRANFILE)));
            return repository;
        }
    }

    /**
     * One case's seeded datasets, decoded through the production decoders.
     *
     * @param daily          {@code DALYTRAN}, in the order the sequential pass yields them
     * @param crossReference {@code XREFFILE}, keyed by the sixteen-character card number exactly as
     *                       {@code 2000-LOOKUP-XREF} builds the key
     * @param accounts       {@code ACCTFILE}, keyed by the eleven-digit account id
     */
    private record SeededInputs(List<DalyTranRecord> daily,
                                Map<String, CardXrefRecord> crossReference,
                                Map<Long, AccountRecord> accounts) {
    }

    /**
     * Decodes the seeded rows into the records the collaborators hand back.
     *
     * <p>Every decode goes through the production decoder for that copybook, so a row the repositories
     * could not have produced cannot enter a run - and the cross-reference rows arrive already padded
     * from 36 to the 50 bytes {@code app/cpy/CVACT03Y.cpy} declares, because the case declared that
     * normalisation and the harness applied it at seeding time (gate G16).
     *
     * @param invocation the invocation holding the seeded datasets
     * @return the decoded seed
     */
    private static SeededInputs seededInputs(ParityHarness.Invocation invocation) {
        FixedWidthCodec codec = invocation.codec();
        List<DalyTranRecord> daily = new ArrayList<>();
        for (String row : rowsOf(invocation, DALYTRAN)) {
            daily.add(DalyTranRecord.decode(row, ASCII));
        }
        Map<String, CardXrefRecord> crossReference = new LinkedHashMap<>();
        for (String row : rowsOf(invocation, XREFFILE)) {
            CardXrefRecord record = CardXrefRecord.decode(
                    codec.encodeImage(row, "an " + XREFFILE + " row"), codec);
            crossReference.put(record.xrefCardNum(), record);
        }
        Map<Long, AccountRecord> accounts = new LinkedHashMap<>();
        for (String row : rowsOf(invocation, ACCTFILE)) {
            AccountRecord record = AccountRecord.decode(row, ASCII);
            accounts.put(record.getAcctId(), record);
        }
        return new SeededInputs(List.copyOf(daily), Map.copyOf(crossReference), Map.copyOf(accounts));
    }

    /**
     * @param invocation the invocation holding the seeded datasets
     * @param dataset    the binding key
     * @return that dataset's rows, or an empty list when the case seeds it as an existing empty dataset
     *         or does not seed it at all
     */
    private static List<String> rowsOf(ParityHarness.Invocation invocation, String dataset) {
        return invocation.hasDataset(dataset) ? invocation.dataset(dataset).rows() : List.of();
    }

    /**
     * @param record the record a keyed read returns
     * @return the 50-byte image it was decoded from, which a read outcome carries alongside it
     */
    private static String storedImage(CardXrefRecord record) {
        return new String(record.encode(ASCII), ASCII);
    }

    // =================================================================================================
    // The contract and dataset catalogues, in the shape application.yml declares them. Every dataset name
    // here is a TEST. name: no production DSN literal appears anywhere in this file (gate G46).
    //
    // The catalogues are built here rather than bound from src/main/resources/application-test.yml, and
    // that is deliberate. Loading the profile would need a Spring context, and a context would bring a
    // JobLauncher and the real repositories with it - which is exactly what gate G51 excludes. What the
    // job's start-up guards actually read from a binding is its record length, its copybook name and its
    // key geometry, all three of which come from the copybooks these cases already transcribe, so the
    // profile would supply nothing this catalogue does not while costing the isolation. The profile
    // remains the production shape for a launched run; this is the same shape without the container.
    // =================================================================================================

    /** The prefix every dataset name in this file carries, so none can be mistaken for a real one. */
    private static final String TEST_DSNAME_PREFIX = "TEST.CBTRN01C.";

    /**
     * @return the batch seam over the contract this program's configuration declares - {@value #PROGRAM},
     *         no parameter, one ungated step - and the six-entry dataset catalogue
     */
    private static BatchConfig batchConfig() {
        JobContracts contracts = new JobContracts();
        contracts.put(TransactionPostingJob.JOB_KEY, new JobContract(PROGRAM, List.of(),
                List.of(new StepContract(TransactionPostingJob.STEP_NAME, PROGRAM, false)), null,
                Map.of()));
        // Both are mocks and neither is touched by a run: nothing here launches a job, so no job
        // repository is written to and no transaction is begun (gate G51).
        return new BatchConfig(new SuppliedProvider<>(mock(JobRepository.class)),
                new SuppliedProvider<>(mock(PlatformTransactionManager.class)), contracts, bindings());
    }

    /** @return the six bindings the job's start-up guards read, each at its copybook width */
    private static DatasetBindings bindings() {
        DatasetBindings catalogue = new DatasetBindings();
        for (String dataset : ALL_DDS) {
            catalogue.put(dataset, binding(dataset));
        }
        return catalogue;
    }

    /**
     * One binding, with the key geometry the program's own {@code RECORD KEY} clauses declare -
     * {@code FD-CUST-ID} 9 digits, {@code FD-XREF-CARD-NUM} and {@code FD-CARD-NUM} and
     * {@code FD-TRANS-ID} 16 characters, {@code FD-ACCT-ID} 11 digits - and none for the daily file,
     * which is {@code ORGANIZATION IS SEQUENTIAL}.
     *
     * @param dataset the binding key
     * @return the binding
     */
    private static DatasetBinding binding(String dataset) {
        boolean sequential = DALYTRAN.equals(dataset);
        return new DatasetBinding(TEST_DSNAME_PREFIX + dataset,
                sequential ? "sequential" : DatasetBinding.KSDS,
                false,
                "FB",
                sequential ? Integer.valueOf(0) : null,
                widthOf(dataset),
                copybookOf(dataset),
                sequential ? null : Integer.valueOf(keyLengthOf(dataset)),
                sequential ? null : Integer.valueOf(0),
                null,
                null);
    }

    /**
     * An {@link ObjectProvider} over one optional bean, which is what the container hands a constructor.
     *
     * @param <T> the bean type
     */
    private static final class SuppliedProvider<T> implements ObjectProvider<T> {

        /** The bean, or {@code null} when the container publishes none. */
        private final T bean;

        /**
         * @param bean the bean, or {@code null}
         */
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
    // Per-dataset facts: the width, the copybook, the key and the layout an expectation addresses.
    // =================================================================================================

    /**
     * @param dataset the binding key
     * @return the record width its copybook declares
     */
    private static int widthOf(String dataset) {
        return switch (dataset) {
            case DALYTRAN -> DALYTRAN_WIDTH;
            case CUSTFILE -> CUSTFILE_WIDTH;
            case XREFFILE -> XREF_WIDTH;
            case CARDFILE -> CARDFILE_WIDTH;
            case ACCTFILE -> ACCTFILE_WIDTH;
            case TRANFILE -> TRANFILE_WIDTH;
            default -> throw new IllegalArgumentException(unknownDataset(dataset));
        };
    }

    /**
     * @param dataset the binding key
     * @return the {@code app/cpy} member that declares its record
     */
    private static String copybookOf(String dataset) {
        return switch (dataset) {
            case DALYTRAN -> "CVTRA06Y";
            case CUSTFILE -> "CVCUS01Y";
            case XREFFILE -> "CVACT03Y";
            case CARDFILE -> "CVACT02Y";
            case ACCTFILE -> "CVACT01Y";
            case TRANFILE -> "CVTRA05Y";
            default -> throw new IllegalArgumentException(unknownDataset(dataset));
        };
    }

    /**
     * @param dataset the binding key
     * @return the width of the {@code RECORD KEY} the program declares for it
     */
    private static int keyLengthOf(String dataset) {
        return switch (dataset) {
            case CUSTFILE -> CUSTOMER_ID_WIDTH;
            case XREFFILE, CARDFILE, TRANFILE -> CARD_NUMBER_WIDTH;
            case ACCTFILE -> ACCOUNT_ID_WIDTH;
            default -> throw new IllegalArgumentException(unknownDataset(dataset));
        };
    }

    /**
     * @param dataset the binding key
     * @return the layout an expectation addresses its records by
     */
    private static RecordLayout layoutOf(String dataset) {
        return switch (dataset) {
            case DALYTRAN -> DalyTranRecord.LAYOUT;
            case CUSTFILE -> CustomerRecord.LAYOUT;
            case XREFFILE -> CardXrefRecord.LAYOUT;
            case CARDFILE -> CardRecord.LAYOUT;
            case ACCTFILE -> AccountRecord.LAYOUT;
            case TRANFILE -> TranRecord.LAYOUT;
            default -> throw new IllegalArgumentException(unknownDataset(dataset));
        };
    }

    /**
     * @param dataset the offending key
     * @return the diagnostic for a dataset this program declares no {@code SELECT} for
     */
    private static String unknownDataset(String dataset) {
        return "CBTRN01C declares no SELECT for dataset " + dataset + "; the six it declares at "
                + "app/cbl/CBTRN01C.cbl:29-62 are " + ALL_DDS + '.';
    }

    // =================================================================================================
    // Case-building helpers: seeds, expectations and normalisations.
    // =================================================================================================

    /** @return an empty, ordered seed set to add datasets to */
    private static Seeds seeds() {
        return new Seeds();
    }

    /**
     * An ordered seed set, so a case's datasets appear in the order it declares them.
     *
     * <p>Per-call and never shared: an instance is created by {@link #seeds()}, filled and immediately
     * frozen into the case, so this introduces no shared mutable state (gate G53).
     */
    private static final class Seeds {

        /** The datasets, in declaration order. */
        private final Map<String, ParityCase.DatasetInput> inputs = new LinkedHashMap<>();

        /**
         * @param dataset the binding key
         * @param input   how to seed it
         * @return this set
         */
        private Seeds with(String dataset, ParityCase.DatasetInput input) {
            inputs.put(dataset, input);
            return this;
        }

        /** @return the seed set, in declaration order */
        private Map<String, ParityCase.DatasetInput> build() {
            return new LinkedHashMap<>(inputs);
        }
    }

    /**
     * @param images the literal rows to seed, at their copybook width or - for the cross reference - at
     *               the 36 the fixture holds
     * @return the input
     */
    private static ParityCase.DatasetInput rows(String... images) {
        return new ParityCase.DatasetInput(List.of(images), null, null, null, null, null, null);
    }

    // =================================================================================================
    // Line and trace composition. Every line the program can emit is built here, from the literals above.
    // =================================================================================================

    /**
     * The four lines {@code 2000-LOOKUP-XREF} displays on its {@code NOT INVALID KEY} arm - {@code :235}
     * to {@code :238} - with the three field values shown as the {@code PICTURE} clauses render them.
     *
     * @param cardNumber      {@code XREF-CARD-NUM}, sixteen characters
     * @param customerIdImage {@code XREF-CUST-ID}, nine digits
     * @param accountIdImage  {@code XREF-ACCT-ID}, eleven digits
     * @return the four lines, in order
     */
    private static List<String> xrefFoundLines(String cardNumber, String customerIdImage,
            String accountIdImage) {
        return List.of(SUCCESSFUL_READ_OF_XREF,
                XREF_CARD_NUMBER_PREFIX + exact(cardNumber, CARD_NUMBER_WIDTH, "XREF-CARD-NUM"),
                XREF_ACCOUNT_ID_PREFIX + exact(accountIdImage, ACCOUNT_ID_WIDTH, "XREF-ACCT-ID"),
                XREF_CUSTOMER_ID_PREFIX + exact(customerIdImage, CUSTOMER_ID_WIDTH, "XREF-CUST-ID"));
    }

    /**
     * @param cardNumber the card number
     * @param customerId the customer id
     * @param accountId  the account id
     * @return the same four lines, from numeric values
     */
    private static List<String> xrefFoundLines(String cardNumber, int customerId, long accountId) {
        return xrefFoundLines(cardNumber, digits(customerId, CUSTOMER_ID_WIDTH),
                digits(accountId, ACCOUNT_ID_WIDTH));
    }

    /**
     * The two lines an unresolvable card produces: {@code :232}'s from the {@code INVALID KEY} phrase,
     * then the mainline's single four-operand {@code DISPLAY} at {@code :181-183}.
     *
     * @param cardNumber    the sixteen-character card number, untrimmed
     * @param transactionId the sixteen-character transaction id, untrimmed
     * @return the two lines, in order
     */
    private static List<String> skipLines(String cardNumber, String transactionId) {
        return List.of(INVALID_CARD_NUMBER_FOR_XREF,
                CARD_NOT_VERIFIED_PREFIX + exact(cardNumber, CARD_NUMBER_WIDTH, "DALYTRAN-CARD-NUM")
                        + CARD_NOT_VERIFIED_SUFFIX
                        + exact(transactionId, TRANSACTION_ID_WIDTH, "DALYTRAN-ID"));
    }

    /**
     * The two lines an absent account produces: {@code :246}'s from the {@code INVALID KEY} phrase, then
     * the mainline's at {@code :178}.
     *
     * @param accountIdImage the eleven digits {@code ACCT-ID} holds
     * @return the two lines, in order
     */
    private static List<String> accountNotFoundLines(String accountIdImage) {
        return List.of(INVALID_ACCOUNT_NUMBER_FOUND,
                ACCOUNT_NOT_FOUND_PREFIX + exact(accountIdImage, ACCOUNT_ID_WIDTH, "ACCT-ID")
                        + ACCOUNT_NOT_FOUND_SUFFIX);
    }

    /**
     * @param accountId the account id
     * @return the same two lines, from a numeric value
     */
    private static List<String> accountNotFoundLines(long accountId) {
        return accountNotFoundLines(digits(accountId, ACCOUNT_ID_WIDTH));
    }

    /**
     * The three lines every failing file verb produces, in the order the shared arm emits them: the
     * paragraph's own literal, the rendered status, and {@code Z-ABEND-PROGRAM}'s line.
     *
     * @param errorText the paragraph's literal
     * @param status    the two-character status it moved into {@code IO-STATUS}
     * @return the three lines, in order
     */
    private static List<String> abendLines(String errorText, String status) {
        return List.of(errorText, fileStatusLine(status), ABENDING_PROGRAM);
    }

    /**
     * {@code DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04} - {@code app/cbl/CBTRN01C.cbl:476-489}, both
     * arms, transcribed rather than borrowed.
     *
     * <p>The extended arm is taken when {@code IO-STATUS} is not numeric or its first byte is
     * {@code '9'}: the first byte is rendered verbatim and the second byte's <em>numeric value</em> fills
     * three digits, because {@code MOVE IO-STAT2 TO TWO-BYTES-RIGHT} deposits the raw byte into the
     * low-order half of a {@code PIC 9(4) BINARY} and the {@code REDEFINES} then reads it as a number.
     * The numeric arm is simply {@code '00'} followed by the two status characters.
     *
     * @param status the two-character file status
     * @return the complete line
     */
    private static String fileStatusLine(String status) {
        char first = status.charAt(0);
        char second = status.charAt(1);
        String image = first == '9' || !isSingleByteDigit(first) || !isSingleByteDigit(second)
                ? first + String.format("%03d", ((int) second) & 0xFF)
                : "00" + status;
        return FILE_STATUS_PREFIX + image;
    }

    /**
     * @param character one byte of {@code IO-STATUS}
     * @return whether it is one of the ten ASCII digits, which is what the COBOL class condition
     *         {@code IO-STATUS NOT NUMERIC} tests - and not what a Unicode digit test would
     */
    private static boolean isSingleByteDigit(char character) {
        return character >= '0' && character <= '9';
    }

    /**
     * @param count how many of the six opens were issued
     * @return those {@code OPEN} entries, in the order {@code :157-162} issues them
     */
    private static List<String> openTrace(int count) {
        List<String> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            entries.add(TRACE_OPEN + ALL_DDS.get(index));
        }
        return List.copyOf(entries);
    }

    /**
     * @param count how many of the six closes were issued
     * @return those {@code CLOSE} entries, in the order {@code :188-193} issues them
     */
    private static List<String> closeTrace(int count) {
        List<String> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            entries.add(TRACE_CLOSE + ALL_DDS.get(index));
        }
        return List.copyOf(entries);
    }

    /**
     * One iteration of the loop: the sequential read, the cross-reference lookup that always follows it -
     * end of file or not - and the account read when the lookup did not report an invalid key.
     *
     * @param accountRead whether {@code 3000-READ-ACCOUNT} was performed
     * @return the entries, in order
     */
    private static List<String> recordTrace(boolean accountRead) {
        List<String> entries = new ArrayList<>(3);
        entries.add(TRACE_READ + DALYTRAN);
        entries.add(TRACE_READ + XREFFILE);
        if (accountRead) {
            entries.add(TRACE_READ + ACCTFILE);
        }
        return List.copyOf(entries);
    }

    /**
     * @param parts the fragments, in order
     * @return one flat list of them
     */
    private static List<String> concat(List<List<String>> parts) {
        List<String> flattened = new ArrayList<>();
        for (List<String> part : parts) {
            flattened.addAll(part);
        }
        return List.copyOf(flattened);
    }

    // =================================================================================================
    // COBOL record composition, span by span from the copybooks. A width is never a counted run of spaces
    // in a literal, and an over-long value is refused rather than clipped.
    // =================================================================================================

    /**
     * {@code DALYTRAN-RECORD} - {@code app/cpy/CVTRA06Y.cpy}, whose fourteen spans are
     * {@code DALYTRAN-ID X(16)}, {@code -TYPE-CD X(02)}, {@code -CAT-CD 9(04)}, {@code -SOURCE X(10)},
     * {@code -DESC X(100)}, {@code -AMT S9(09)V99} (11 zoned bytes), {@code -MERCHANT-ID 9(09)},
     * {@code -MERCHANT-NAME X(50)}, {@code -MERCHANT-CITY X(50)}, {@code -MERCHANT-ZIP X(10)},
     * {@code -CARD-NUM X(16)}, {@code -ORIG-TS X(26)}, {@code -PROC-TS X(26)} and {@code FILLER X(20)} -
     * summing to exactly 350.
     *
     * @param transactionId    {@code DALYTRAN-ID}
     * @param typeCode         {@code DALYTRAN-TYPE-CD}
     * @param categoryCode     {@code DALYTRAN-CAT-CD}
     * @param source           {@code DALYTRAN-SOURCE}
     * @param description      {@code DALYTRAN-DESC}
     * @param amountImage      {@code DALYTRAN-AMT}, as the eleven zoned bytes including the overpunch
     * @param merchantId       {@code DALYTRAN-MERCHANT-ID}
     * @param merchantName     {@code DALYTRAN-MERCHANT-NAME}
     * @param merchantCity     {@code DALYTRAN-MERCHANT-CITY}
     * @param merchantZip      {@code DALYTRAN-MERCHANT-ZIP}
     * @param cardNumber       {@code DALYTRAN-CARD-NUM}
     * @param originTimestamp  {@code DALYTRAN-ORIG-TS}
     * @param processTimestamp {@code DALYTRAN-PROC-TS}
     * @return the 350-byte record image
     */
    private static String dalytranRow(String transactionId, String typeCode, String categoryCode,
            String source, String description, String amountImage, String merchantId,
            String merchantName, String merchantCity, String merchantZip, String cardNumber,
            String originTimestamp, String processTimestamp) {
        String image = exact(transactionId, TRANSACTION_ID_WIDTH, "DALYTRAN-ID")
                + exact(typeCode, 2, "DALYTRAN-TYPE-CD")
                + exact(categoryCode, 4, "DALYTRAN-CAT-CD")
                + pad(source, 10)
                + pad(description, 100)
                + exact(amountImage, 11, "DALYTRAN-AMT")
                + exact(merchantId, 9, "DALYTRAN-MERCHANT-ID")
                + pad(merchantName, 50)
                + pad(merchantCity, 50)
                + pad(merchantZip, 10)
                + exact(cardNumber, CARD_NUMBER_WIDTH, "DALYTRAN-CARD-NUM")
                + pad(originTimestamp, 26)
                + pad(processTimestamp, 26)
                + blanks(20);
        return requireWidth(image, DALYTRAN_WIDTH, copybookOf(DALYTRAN));
    }

    /** @return {@code app/data/ASCII/dailytran.txt} row 0, rebuilt span by span */
    private static String dailyTransactionRowOne() {
        return dalytranRow(TRAN_ID_ONE, "01", "0001", "POS TERM", "Purchase at Abshire-Lowe",
                AMOUNT_POSITIVE, MERCHANT_ID, "Abshire-Lowe", "North Enoshaven", "72112", CARD_ONE,
                ORIGIN_TIMESTAMP, PROCESS_TIMESTAMP_BLANK);
    }

    /** @return {@code app/data/ASCII/dailytran.txt} row 1, whose amount carries a negative overpunch */
    private static String dailyTransactionRowTwo() {
        return dalytranRow(TRAN_ID_TWO, "03", "0001", "OPERATOR",
                "Return item at Nitzsche, Nicolas and Lowe", AMOUNT_NEGATIVE, MERCHANT_ID,
                "Nitzsche, Nicolas and Lowe", "Fidelshire", "53378", CARD_TWO, ORIGIN_TIMESTAMP,
                PROCESS_TIMESTAMP_BLANK);
    }

    /**
     * {@code ACCOUNT-RECORD} - {@code app/cpy/CVACT01Y.cpy}, whose thirteen spans are
     * {@code ACCT-ID 9(11)}, {@code -ACTIVE-STATUS X(01)}, three {@code S9(10)V99} monetary spans of 12
     * zoned bytes each, {@code -OPEN-DATE X(10)}, {@code -EXPIRAION-DATE X(10)} - the copybook's own
     * misspelling, preserved and never corrected - {@code -REISSUE-DATE X(10)}, two more monetary spans,
     * {@code -ADDR-ZIP X(10)}, {@code -GROUP-ID X(10)} and {@code FILLER X(178)}, summing to exactly 300.
     *
     * @param accountId          {@code ACCT-ID}
     * @param activeStatus       {@code ACCT-ACTIVE-STATUS}
     * @param currentBalance     {@code ACCT-CURR-BAL}, as its twelve zoned bytes
     * @param creditLimit        {@code ACCT-CREDIT-LIMIT}
     * @param cashCreditLimit    {@code ACCT-CASH-CREDIT-LIMIT}
     * @param openDate           {@code ACCT-OPEN-DATE}
     * @param expiraionDate      {@code ACCT-EXPIRAION-DATE}
     * @param reissueDate        {@code ACCT-REISSUE-DATE}
     * @param currentCycleCredit {@code ACCT-CURR-CYC-CREDIT}
     * @param currentCycleDebit  {@code ACCT-CURR-CYC-DEBIT}
     * @param addressZip         {@code ACCT-ADDR-ZIP}
     * @param groupId            {@code ACCT-GROUP-ID}
     * @return the 300-byte record image
     */
    private static String accountRow(long accountId, String activeStatus, String currentBalance,
            String creditLimit, String cashCreditLimit, String openDate, String expiraionDate,
            String reissueDate, String currentCycleCredit, String currentCycleDebit,
            String addressZip, String groupId) {
        String image = digits(accountId, ACCOUNT_ID_WIDTH)
                + exact(activeStatus, 1, "ACCT-ACTIVE-STATUS")
                + exact(currentBalance, 12, "ACCT-CURR-BAL")
                + exact(creditLimit, 12, "ACCT-CREDIT-LIMIT")
                + exact(cashCreditLimit, 12, "ACCT-CASH-CREDIT-LIMIT")
                + exact(openDate, 10, "ACCT-OPEN-DATE")
                + exact(expiraionDate, 10, "ACCT-EXPIRAION-DATE")
                + exact(reissueDate, 10, "ACCT-REISSUE-DATE")
                + exact(currentCycleCredit, 12, "ACCT-CURR-CYC-CREDIT")
                + exact(currentCycleDebit, 12, "ACCT-CURR-CYC-DEBIT")
                + pad(addressZip, 10)
                + pad(groupId, 10)
                + blanks(178);
        return requireWidth(image, ACCTFILE_WIDTH, copybookOf(ACCTFILE));
    }

    /** @return the {@code acctdata.txt} row for account 7, rebuilt span by span */
    private static String accountRowOne() {
        return accountRow(ACCT_ONE, "Y", "00000001930{", "00000020650{", "00000002640{", "2012-10-12",
                "2024-12-13", "2024-12-13", "00000000000{", "00000000000{", "A000000000", "");
    }

    /** @return the {@code acctdata.txt} row for account 20, rebuilt span by span */
    private static String accountRowTwo() {
        return accountRow(ACCT_TWO, "Y", "00000003690{", "00000037670{", "00000010400{", "2014-02-27",
                "2024-03-13", "2024-03-13", "00000000000{", "00000000000{", "A000000000", "");
    }

    /**
     * A {@code cardxref.txt} row as the fixture holds it: 36 bytes, with the trailing
     * {@code FILLER X(14)} absent.
     *
     * @param cardNumber {@code XREF-CARD-NUM}
     * @param customerId {@code XREF-CUST-ID}
     * @param accountId  {@code XREF-ACCT-ID}
     * @return the 36-byte row
     */
    private static String xrefFixtureRow(String cardNumber, int customerId, long accountId) {
        String row = exact(cardNumber, CARD_NUMBER_WIDTH, CardXrefRecord.XREF_CARD_NUM_NAME)
                + digits(customerId, CUSTOMER_ID_WIDTH)
                + digits(accountId, ACCOUNT_ID_WIDTH);
        return requireWidth(row, XREF_FIXTURE_WIDTH, copybookOf(XREFFILE));
    }

    /**
     * The same row at the 50 bytes {@code app/cpy/CVACT03Y.cpy} declares, which is what the case's
     * normalisation produces at seed time.
     *
     * @param cardNumber {@code XREF-CARD-NUM}
     * @param customerId {@code XREF-CUST-ID}
     * @param accountId  {@code XREF-ACCT-ID}
     * @return the 50-byte record image
     */
    private static String xrefRecordImage(String cardNumber, int customerId, long accountId) {
        return requireWidth(xrefFixtureRow(cardNumber, customerId, accountId)
                + blanks(CardXrefRecord.FILLER_LENGTH), XREF_WIDTH, copybookOf(XREFFILE));
    }

    /**
     * @param width the declared width
     * @return that many spaces - the content of a cleared {@code PIC X} span
     */
    private static String blanks(int width) {
        return " ".repeat(width);
    }

    /**
     * A value in a {@code PIC X(width)} item: right-padded with spaces, exactly as an alphanumeric
     * {@code MOVE} into a wider receiver pads.
     *
     * @param value the value moved
     * @param width the receiver's declared width
     * @return an image of exactly {@code width} characters
     * @throws IllegalArgumentException if the value is wider than the receiver. COBOL would truncate on
     *                                  the right; an expectation that silently truncated would assert a
     *                                  value nobody wrote, so this refuses instead
     */
    private static String pad(String value, int width) {
        if (value.length() > width) {
            throw new IllegalArgumentException('\'' + value + "' is " + value.length()
                    + " characters and the receiver is PIC X(" + width + "). A COBOL MOVE would truncate "
                    + "on the right; state the truncated image outright rather than letting a composer "
                    + "shorten it.");
        }
        return value + blanks(width - value.length());
    }

    /**
     * A value that must already fill its span exactly - every numeric span, and the two keys.
     *
     * @param value the value
     * @param width the span's declared width
     * @param field the field name, quoted in a failure
     * @return {@code value}
     * @throws IllegalArgumentException if it is not exactly {@code width} characters
     */
    private static String exact(String value, int width, String field) {
        if (value.length() != width) {
            throw new IllegalArgumentException(field + " is declared " + width + " characters and '"
                    + value + "' is " + value.length() + ". A zoned numeric span is zero-filled to its "
                    + "width and a key fills it exactly, so neither may be padded here.");
        }
        return value;
    }

    /**
     * @param value the value
     * @param width the span's declared width
     * @return the zero-filled image a {@code PIC 9(width)} item holds
     */
    private static String digits(long value, int width) {
        return exact(String.format("%0" + width + "d", value), width, "a PIC 9(" + width + ") item");
    }

    /**
     * @param image    the composed image
     * @param width    the width its copybook declares
     * @param copybook the copybook member, quoted in a failure
     * @return {@code image}
     * @throws IllegalStateException if the spans do not sum to the declared width, which is exactly the
     *                              failure a dropped {@code FILLER} produces
     */
    private static String requireWidth(String image, int width, String copybook) {
        if (image.length() != width) {
            throw new IllegalStateException("A record composed from " + copybook + "'s spans measures "
                    + image.length() + " characters where the copybook declares " + width
                    + ". A record short by exactly one span is a dropped FILLER, and every offset after "
                    + "it is wrong.");
        }
        return image;
    }

    /** @return {@code case01} through {@code case20}, in order */
    private static List<String> expectedCaseIds() {
        List<String> identifiers = new ArrayList<>(ParityHarness.CASES_PER_PROGRAM);
        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            identifiers.add(ParityHarness.caseId(ordinal));
        }
        return List.copyOf(identifiers);
    }

    // =================================================================================================
    // The full pass, derived from the shipped fixtures rather than transcribed.
    // =================================================================================================

    /**
     * What a pass over the whole daily transaction fixture produces.
     *
     * @param lines          every {@code DISPLAY} line, in emission order
     * @param trace          every file verb, in order
     * @param finalStateRows every row of the daily file, pinned unchanged
     */
    private record FullPass(List<String> lines, List<String> trace,
                            List<ParityCase.ExpectedRecord> finalStateRows) {
    }

    /**
     * Reads one of the nine fixtures from the test classpath, under an explicitly named code page.
     *
     * <p>Rows are line-feed separated and otherwise verbatim: a trailing space is data and so is a
     * trailing sign overpunch. A carriage return is refused rather than stripped, because a fixture
     * checked out with translated line endings is a corrupted fixture - every row would be one byte wider
     * than its copybook, and the failure would surface far from its cause.
     *
     * @param fixture the file name
     * @return its rows, in file order
     * @throws IllegalStateException if the fixture is absent or holds a carriage return
     */
    private static List<String> fixtureRows(String fixture) {
        String resource = FIXTURE_ROOT + fixture;
        try (InputStream stream =
                CBTRN01CParityTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Fixture " + resource + " is not on the test classpath. "
                        + "The nine fixtures are byte-for-byte copies of app/data/ASCII and are what "
                        + "every parity case is seeded from.");
            }
            List<String> rows = new ArrayList<>();
            for (String row : new String(stream.readAllBytes(), ASCII).split("\n", -1)) {
                if (row.isEmpty()) {
                    continue;
                }
                if (row.indexOf('\r') >= 0) {
                    throw new IllegalStateException("A row of fixture " + resource + " contains a "
                            + "carriage return, so the working tree translated the line endings and "
                            + "every row is one byte wider than its copybook. Restore the fixture rather "
                            + "than stripping the byte here.");
                }
                rows.add(row);
            }
            return List.copyOf(rows);
        } catch (IOException failure) {
            throw new UncheckedIOException("Reading fixture " + resource + " failed", failure);
        }
    }
}
