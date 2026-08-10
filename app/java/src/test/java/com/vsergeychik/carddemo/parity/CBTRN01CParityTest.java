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
 *       {@code case01} pins the repeat after one clean record, {@code case06} pins that the repeat uses
 *       the <em>last</em> record's card and not the first, and {@code case04} pins the degenerate form:
 *       over an empty dataset the lookup runs on the untouched record area, whose
 *       {@code DALYTRAN-CARD-NUM} is sixteen spaces. Hoisting those statements inside the guard makes
 *       all three fail.</li>
 *   <li><strong>{@code 9000-DALYTRAN-CLOSE} reports the wrong file.</strong> {@code :372-373} displays
 *       {@code 'ERROR CLOSING CUSTOMER FILE'} and moves {@code CUSTFILE-STATUS} - not
 *       {@code DALYTRAN-STATUS} - into {@code IO-STATUS} on the arm reached when closing the
 *       <em>daily transaction</em> file fails. So the operator is told the wrong file failed and is
 *       shown the wrong status: {@code 'FILE STATUS IS: NNNN0000'}, because the customer file's own
 *       open succeeded. {@code case17} pins both halves; correcting either makes it fail.</li>
 * </ol>
 * <p>Neither is fixed here. Fixing either would change what this program has always reported, which is
 * a behaviour change and a parity violation (practice B5).
 *
 * <h2>Why the cases are declared here rather than loaded from {@code parity/CBTRN01C/}</h2>
 * <p>The harness convention is that {@code CBTRN01CParityTest} reads
 * {@code src/test/resources/parity/CBTRN01C/}, and this class's stem matches that directory exactly -
 * {@link #resourceConventionIsHonoured()} asserts the agreement rather than stating it in a comment.
 * That directory is not present in this working tree, so the twenty cases are declared in Java through
 * {@link ParityCase}'s own canonical constructor: the identical constructor a JSON fixture is bound
 * through, with the identical validation of case identifiers, dataset binding keys, input shapes,
 * fixture names, record-expectation completeness, message channels and width normalisations. A
 * declaration that a fixture file would reject cannot be written here either. When the fixture set is
 * shipped, {@link #cases()} is the single seam that adopts it - {@code ParityHarness.casesOf("CBTRN01C")}
 * returns the same twenty {@link ParityCase} values this method builds - and no other line of this class
 * changes.
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
        assertThat(unit.trace())
                .as("the file verbs %s issued, in order: CBTRN01C opens six files at :157-162, reads "
                        + "DALYTRAN sequentially, reads XREFFILE and ACCTFILE by key, and closes six "
                        + "files at :188-193. It issues no WRITE and no REWRITE anywhere, and it never "
                        + "reads CUSTFILE or CARDFILE at all", scenario.caseId())
                .containsExactlyElementsOf(scenario.expectedTrace());
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
        List<ParityScenario> scenarios = List.of(case01(), case02(), case03(), case04(), case05(),
                case06(), case07(), case08(), case09(), case10(), case11(), case12(), case13(),
                case14(), case15(), case16(), case17(), case18(), case19(), case20());
        requireCompleteCaseSet(scenarios);
        return scenarios;
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
     * The rows these cases seed are the rows the shipped fixtures hold, byte for byte.
     *
     * <p>The cases build their seed rows span by span from the copybook offsets rather than pasting 350
     * characters into a literal, which keeps them readable - and would also let a transcription error
     * hide, because a case that seeds a wrong row and expects that same wrong row still passes. So the
     * composed rows are compared here against {@code app/data/ASCII}, read from the classpath under an
     * explicitly named code page. Agreement proves three things at once: the offsets are right, the
     * values are real production data, and the composer pads exactly as a COBOL {@code MOVE} into a
     * fixed-width receiver does.
     */
    @Test
    @DisplayName("every composed seed row is byte-identical to its row in app/data/ASCII")
    void theSeedRowsAreTheShippedFixtureRows() {
        List<String> daily = fixtureRows(DAILYTRAN_FIXTURE);
        List<String> crossReference = fixtureRows(CARDXREF_FIXTURE);
        List<String> accounts = fixtureRows(ACCTDATA_FIXTURE);

        assertThat(daily).hasSize(300).allMatch(row -> row.length() == DALYTRAN_WIDTH);
        assertThat(crossReference).hasSize(50).allMatch(row -> row.length() == XREF_FIXTURE_WIDTH);
        assertThat(accounts).hasSize(50).allMatch(row -> row.length() == ACCTFILE_WIDTH);

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
        PostingRun unit = new PostingRun(case04());
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
            PostingRun unit = new PostingRun(
                    sameSeedsWith(case17(), Injection.closeFailure(arm.getKey(), STATUS_OPEN_FAILED)));

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
     * invoked on either repository across a full 300-record pass, whose customer and card files are
     * seeded with all fifty of their rows precisely so that "never read" is a statement about a
     * non-empty file.
     */
    @Test
    @DisplayName("CUSTFILE and CARDFILE are opened and closed but never read, across a full 300-row pass")
    void theCustomerAndCardFilesAreNeverRead() {
        PostingRun unit = new PostingRun(case05());

        ExecutionSummary summary = unit.run();

        assertThat(summary.recordsRead()).isEqualTo(300);
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
     * <p>The post-end-of-file lookup expressed as a number, over three run shapes at once: an empty
     * dataset, a single record and a full 300-record pass. The summary's own constructor enforces the
     * relation, so this asserts the numbers a real run reported rather than a relation a builder could
     * have imposed.
     */
    @Test
    @DisplayName("xrefLookups is always recordsRead + 1: the stale lookup happens on every run")
    void everyRunPerformsOneLookupBeyondItsRecordCount() {
        for (ParityScenario scenario : List.of(case04(), case01(), case05())) {
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
    // THE TWENTY CASES.
    //
    // Cases 01 to 10 exercise the pass itself: what the program displays for a record that resolves, one
    // whose card does not, one whose account does not, an empty file, all 300 fixture rows, and each of
    // the three arms a keyed read can leave the mainline in. Cases 11 to 20 exercise the twelve file
    // verbs that can fail, each of which displays its own literal, renders a status and abends with
    // RETURN-CODE 12 - including the two whose reported file is not the file that failed.
    // =================================================================================================

    /**
     * A single record that resolves cleanly, and the post-end-of-file lookup that follows it.
     *
     * @return the case
     */
    private static ParityScenario case01() {
        return scenario("case01",
                "One daily transaction that resolves end to end, which is also the smallest case that "
                        + "shows the post-end-of-file lookup. app/data/ASCII/dailytran.txt row 0 carries "
                        + "card 4859452612877065, cardxref.txt resolves it to customer 7 and account 7, "
                        + "and acctdata.txt carries account 7 - so :168 displays the 350-byte record, "
                        + "2000-LOOKUP-XREF takes NOT INVALID KEY and displays its four lines, and "
                        + "3000-READ-ACCOUNT displays its one. The loop then reads again, gets '10', "
                        + "and the three statements at :170-172 - which sit outside the guard that "
                        + "closed at :169 - look the SAME card up a second time on the stale record "
                        + "area, so all five lines repeat before the six closes and the end banner. "
                        + "Twelve file verbs, thirteen lines, no record written anywhere. The three "
                        + "seeded datasets are pinned unchanged on the final-state channel at their "
                        + "copybook widths - 350, 50 and 300 - which is what proves every FILLER span "
                        + "was emitted (gates G19 and G21), and the cross-reference row is seeded at "
                        + "the 36 bytes the fixture holds and asserted at the 50 CVACT03Y declares "
                        + "(gate G16).",
                seeds().with(DALYTRAN, rows(dailyTransactionRowOne()))
                        .with(XREFFILE, rows(xrefFixtureRow(CARD_ONE, CUST_ONE, ACCT_ONE)))
                        .with(ACCTFILE, rows(accountRowOne()))
                        .build(),
                Injection.none(),
                List.of(bytes(DALYTRAN, 0, dailyTransactionRowOne()),
                        xrefFields(0, CARD_ONE, CUST_ONE, ACCT_ONE),
                        bytes(ACCTFILE, 0, accountRowOne())),
                List.of(DALYTRAN, XREFFILE, ACCTFILE),
                RETURN_CODE_OK,
                concat(List.of(
                        List.of(START_BANNER, dailyTransactionRowOne()),
                        xrefFoundLines(CARD_ONE, CUST_ONE, ACCT_ONE),
                        List.of(SUCCESSFUL_READ_OF_ACCOUNT_FILE),
                        xrefFoundLines(CARD_ONE, CUST_ONE, ACCT_ONE),
                        List.of(SUCCESSFUL_READ_OF_ACCOUNT_FILE, END_BANNER))),
                concat(List.of(openTrace(ALL_DDS.size()), recordTrace(true), recordTrace(true),
                        closeTrace(ALL_DDS.size()))));
    }

    /**
     * A card the cross reference does not carry: the {@code INVALID KEY} arm and the skip message.
     *
     * @return the case
     */
    private static ParityScenario case02() {
        return scenario("case02",
                "A card number no cross-reference row carries, which is the '23' arm of the keyed read "
                        + "at :229-233 (gate G47). The INVALID KEY phrase displays 'INVALID CARD NUMBER "
                        + "FOR XREF' and moves 4 into WS-XREF-READ-STATUS, so the test at :173 fails and "
                        + "the ELSE at :180-183 displays ONE line built from four operands with no "
                        + "separator between them: 'CARD NUMBER ', the sixteen-byte card number, ' COULD "
                        + "NOT BE VERIFIED. SKIPPING TRANSACTION ID-' and the sixteen-byte transaction "
                        + "id. No account read is issued at all - which the trace asserts, because the "
                        + "absence of a read cannot be seen in the output - and the stale lookup after "
                        + "end of file repeats both lines for the same unresolvable card. The account "
                        + "file is seeded and left completely unread.",
                seeds().with(DALYTRAN, rows(dailyTransactionRow(CARD_ABSENT, TRAN_ID_ONE)))
                        .with(XREFFILE, rows(xrefFixtureRow(CARD_ONE, CUST_ONE, ACCT_ONE)))
                        .with(ACCTFILE, rows(accountRowOne()))
                        .build(),
                Injection.none(),
                List.of(bytes(DALYTRAN, 0, dailyTransactionRow(CARD_ABSENT, TRAN_ID_ONE))),
                List.of(DALYTRAN),
                RETURN_CODE_OK,
                concat(List.of(
                        List.of(START_BANNER, dailyTransactionRow(CARD_ABSENT, TRAN_ID_ONE)),
                        skipLines(CARD_ABSENT, TRAN_ID_ONE),
                        skipLines(CARD_ABSENT, TRAN_ID_ONE),
                        List.of(END_BANNER))),
                concat(List.of(openTrace(ALL_DDS.size()), recordTrace(false), recordTrace(false),
                        closeTrace(ALL_DDS.size()))));
    }

    /**
     * A cross reference that resolves to an account the account file does not carry.
     *
     * @return the case
     */
    private static ParityScenario case03() {
        return scenario("case03",
                "The cross reference resolves and the account does not exist, which is the '23' arm of "
                        + "3000-READ-ACCOUNT at :243-247. Two lines follow, from two different "
                        + "paragraphs: 'INVALID ACCOUNT NUMBER FOUND' from the INVALID KEY phrase at "
                        + ":246, then 'ACCOUNT 99999999999 NOT FOUND' from the mainline at :178. The "
                        + "eleven digits in that second line are the cross reference's XREF-ACCT-ID and "
                        + "not the account file's ACCT-ID, because a READ that took INVALID KEY left "
                        + "ACCOUNT-RECORD untouched - so the value shown is the one :175 moved in. The "
                        + "account file is seeded with a real account that is not the one being asked "
                        + "for, so the read fails on a populated file rather than an empty one, and "
                        + "both its row and the cross-reference row are pinned unchanged.",
                seeds().with(DALYTRAN, rows(dailyTransactionRow(CARD_ONE, TRAN_ID_ONE)))
                        .with(XREFFILE, rows(xrefFixtureRow(CARD_ONE, CUST_ABSENT, ACCT_ABSENT)))
                        .with(ACCTFILE, rows(accountRowOne()))
                        .build(),
                Injection.none(),
                List.of(xrefFields(0, CARD_ONE, CUST_ABSENT, ACCT_ABSENT),
                        bytes(ACCTFILE, 0, accountRowOne())),
                List.of(XREFFILE, ACCTFILE),
                RETURN_CODE_OK,
                concat(List.of(
                        List.of(START_BANNER, dailyTransactionRow(CARD_ONE, TRAN_ID_ONE)),
                        xrefFoundLines(CARD_ONE, CUST_ABSENT, ACCT_ABSENT),
                        accountNotFoundLines(ACCT_ABSENT),
                        xrefFoundLines(CARD_ONE, CUST_ABSENT, ACCT_ABSENT),
                        accountNotFoundLines(ACCT_ABSENT),
                        List.of(END_BANNER))),
                concat(List.of(openTrace(ALL_DDS.size()), recordTrace(true), recordTrace(true),
                        closeTrace(ALL_DDS.size()))));
    }

    /**
     * An empty daily transaction file: the first read ends the pass, and the stale lookup still runs.
     *
     * @return the case
     */
    private static ParityScenario case04() {
        return scenario("case04",
                "An empty daily transaction file, which is the degenerate form of the post-end-of-file "
                        + "lookup. The first READ reports '10', :208 moves 16 into APPL-RESULT and :217 "
                        + "moves 'Y' into END-OF-DAILY-TRANS-FILE, so no record line is displayed - and "
                        + "then :170-172 run anyway, because they are outside the guard. The card number "
                        + "they look up is the one the untouched DALYTRAN-RECORD area holds: sixteen "
                        + "spaces, the declared value of a PIC X(16) item, which no cross-reference row "
                        + "carries. So an empty input still produces four lines - the banner, the "
                        + "INVALID CARD NUMBER line, a skip line whose card number and transaction id "
                        + "are both blank, and the end banner - and the program still returns zero. The "
                        + "daily file is seeded as a dataset that EXISTS and holds no row, which is a "
                        + "different assertion from not seeding it, and it is asserted empty on the "
                        + "final-state channel at its declared 350-byte width.",
                seeds().with(DALYTRAN, emptyDataset(DALYTRAN_WIDTH, "CVTRA06Y"))
                        .with(XREFFILE, rows(xrefFixtureRow(CARD_ONE, CUST_ONE, ACCT_ONE)))
                        .with(ACCTFILE, rows(accountRowOne()))
                        .build(),
                Injection.none(),
                List.of(),
                List.of(DALYTRAN),
                RETURN_CODE_OK,
                concat(List.of(
                        List.of(START_BANNER),
                        skipLines(blanks(CARD_NUMBER_WIDTH), blanks(TRANSACTION_ID_WIDTH)),
                        List.of(END_BANNER))),
                concat(List.of(openTrace(ALL_DDS.size()), recordTrace(false),
                        closeTrace(ALL_DDS.size()))));
    }

    /**
     * All 300 rows of the shipped daily transaction fixture, against all 50 cross-reference and account
     * rows, with the customer and card files seeded in full and never read.
     *
     * @return the case
     */
    private static ParityScenario case05() {
        FullPass pass = fullPass();
        return scenario("case05",
                "The full pass: all 300 rows of app/data/ASCII/dailytran.txt against all 50 rows of "
                        + "cardxref.txt and acctdata.txt, with custdata.txt and carddata.txt seeded in "
                        + "full precisely so that 'opened and never read' is a statement about "
                        + "non-empty files. Every one of the 300 cards resolves and every resolved "
                        + "account exists, so the run is 300 x (one record image, four cross-reference "
                        + "lines, one account line) plus the five lines of the stale lookup on row "
                        + "299's card plus two banners: 1807 lines, in order, byte for byte. The "
                        + "expectations are derived mechanically from the fixtures and the copybook "
                        + "offsets rather than transcribed, which is what makes 300 rows tractable "
                        + "without weakening them - each record image is asserted to be its fixture row "
                        + "exactly once, so a translation that displayed a record twice, trimmed it, or "
                        + "reordered the pass fails. All 300 rows are also pinned unchanged on the "
                        + "final-state channel, and all six datasets are asserted to have been reached "
                        + "and written to zero times: this is the case that proves, at scale, that the "
                        + "program the prompt names TransactionPostingJob posts nothing.",
                seeds().with(DALYTRAN, fixture(DAILYTRAN_FIXTURE))
                        .with(CUSTFILE, fixture(CUSTDATA_FIXTURE))
                        .with(XREFFILE, fixture(CARDXREF_FIXTURE))
                        .with(CARDFILE, fixture(CARDDATA_FIXTURE))
                        .with(ACCTFILE, fixture(ACCTDATA_FIXTURE))
                        .build(),
                Injection.none(),
                pass.finalStateRows(),
                List.of(DALYTRAN),
                RETURN_CODE_OK,
                pass.lines(),
                pass.trace());
    }

    /**
     * Two records, the second unresolvable: the stale lookup uses the last card and not the first.
     *
     * @return the case
     */
    private static ParityScenario case06() {
        String rowOne = dailyTransactionRow(CARD_ONE, TRAN_ID_ONE);
        String rowTwo = dailyTransactionRow(CARD_ABSENT, TRAN_ID_TWO);
        return scenario("case06",
                "Which card the post-end-of-file lookup uses. Two records are read: the first resolves, "
                        + "the second carries a card no cross-reference row holds. A READ ... INTO "
                        + "leaves its receiving area untouched AT END, so the area still holds the "
                        + "SECOND record when :171 moves DALYTRAN-CARD-NUM into XREF-CARD-NUM for the "
                        + "third lookup - which therefore repeats the second record's skip lines and "
                        + "not the first record's four resolved ones. A translation that kept the last "
                        + "SUCCESSFUL lookup's card, or the first record's, produces the same line "
                        + "count and different text, which is exactly why the text is compared and not "
                        + "the count. Both daily rows are pinned unchanged.",
                seeds().with(DALYTRAN, rows(rowOne, rowTwo))
                        .with(XREFFILE, rows(xrefFixtureRow(CARD_ONE, CUST_ONE, ACCT_ONE)))
                        .with(ACCTFILE, rows(accountRowOne()))
                        .build(),
                Injection.none(),
                List.of(bytes(DALYTRAN, 0, rowOne), bytes(DALYTRAN, 1, rowTwo)),
                List.of(DALYTRAN),
                RETURN_CODE_OK,
                concat(List.of(
                        List.of(START_BANNER, rowOne),
                        xrefFoundLines(CARD_ONE, CUST_ONE, ACCT_ONE),
                        List.of(SUCCESSFUL_READ_OF_ACCOUNT_FILE, rowTwo),
                        skipLines(CARD_ABSENT, TRAN_ID_TWO),
                        skipLines(CARD_ABSENT, TRAN_ID_TWO),
                        List.of(END_BANNER))),
                concat(List.of(openTrace(ALL_DDS.size()), recordTrace(true), recordTrace(false),
                        recordTrace(false), closeTrace(ALL_DDS.size()))));
    }

    /**
     * A cross-reference read that reports neither success nor an invalid key: neither phrase runs.
     *
     * @return the case
     */
    private static ParityScenario case07() {
        String row = dailyTransactionRow(CARD_ONE, TRAN_ID_ONE);
        return scenario("case07",
                "The third outcome of the keyed read at :229 - neither '00' nor an invalid key - and the "
                        + "arm nobody writes on purpose. A READ with a FILE STATUS clause and no USE "
                        + "procedure reports an unreachable dataset through that clause and passes "
                        + "control to the next statement with NEITHER conditional phrase executed. So "
                        + "2000-LOOKUP-XREF displays nothing at all, WS-XREF-READ-STATUS keeps the zero "
                        + ":170 put there, the test at :173 SUCCEEDS, and the mainline goes on to read "
                        + "an account for whatever XREF-ACCT-ID the record area holds - which, no lookup "
                        + "having ever succeeded, is the zero a PIC 9(11) item is initialised to. The "
                        + "run therefore reads account 00000000000, does not find it, and displays "
                        + "'ACCOUNT 00000000000 NOT FOUND'. Two lines per iteration and not four, no "
                        + "abend, and RETURN-CODE 0: a keyed read has no abend path in this program "
                        + "(gate G47).",
                seeds().with(DALYTRAN, rows(row))
                        .with(XREFFILE, rows(xrefFixtureRow(CARD_ONE, CUST_ONE, ACCT_ONE)))
                        .with(ACCTFILE, rows(accountRowOne()))
                        .build(),
                Injection.xrefStatus(STATUS_PERMANENT_ERROR),
                List.of(bytes(DALYTRAN, 0, row)),
                List.of(DALYTRAN),
                RETURN_CODE_OK,
                concat(List.of(
                        List.of(START_BANNER, row),
                        accountNotFoundLines(0L),
                        accountNotFoundLines(0L),
                        List.of(END_BANNER))),
                concat(List.of(openTrace(ALL_DDS.size()), recordTrace(true), recordTrace(true),
                        closeTrace(ALL_DDS.size()))));
    }

    /**
     * An account read that reports neither success nor an invalid key: no line, and no 'NOT FOUND'.
     *
     * @return the case
     */
    private static ParityScenario case08() {
        String row = dailyTransactionRow(CARD_ONE, TRAN_ID_ONE);
        return scenario("case08",
                "The same third outcome one paragraph later, at the account read of :243, where its "
                        + "consequence is the opposite: silence. Neither phrase of the READ executes, so "
                        + "'SUCCESSFUL READ OF ACCOUNT FILE' is not displayed - and WS-ACCT-READ-STATUS "
                        + "keeps the zero :174 put there, so the test at :177 fails and 'ACCOUNT ... NOT "
                        + "FOUND' is not displayed either. A dataset the program could not reach at all "
                        + "is thus reported by four lines about the cross reference and nothing "
                        + "whatsoever about the account, which is the least obvious behaviour in this "
                        + "program and the reason this arm gets a case of its own. The account read IS "
                        + "issued, which the trace asserts, so the difference between this and a skipped "
                        + "read is visible somewhere.",
                seeds().with(DALYTRAN, rows(row))
                        .with(XREFFILE, rows(xrefFixtureRow(CARD_ONE, CUST_ONE, ACCT_ONE)))
                        .with(ACCTFILE, rows(accountRowOne()))
                        .build(),
                Injection.accountStatus(STATUS_PERMANENT_ERROR),
                List.of(xrefFields(0, CARD_ONE, CUST_ONE, ACCT_ONE)),
                List.of(XREFFILE),
                RETURN_CODE_OK,
                concat(List.of(
                        List.of(START_BANNER, row),
                        xrefFoundLines(CARD_ONE, CUST_ONE, ACCT_ONE),
                        xrefFoundLines(CARD_ONE, CUST_ONE, ACCT_ONE),
                        List.of(END_BANNER))),
                concat(List.of(openTrace(ALL_DDS.size()), recordTrace(true), recordTrace(true),
                        closeTrace(ALL_DDS.size()))));
    }

    /**
     * A duplicate on the cross reference's base key, carrying the row whose amount is negative.
     *
     * @return the case
     */
    private static ParityScenario case09() {
        String row = dailyTransactionRowTwo();
        return scenario("case09",
                "A '22' on the cross reference, which is the second status the INVALID KEY phrase runs "
                        + "for (gate G47): a duplicate on the base key takes the same arm as an absent "
                        + "record, so the run displays 'INVALID CARD NUMBER FOR XREF' and the skip line "
                        + "even though a record WAS returned - and the record is not moved into "
                        + "CARD-XREF-RECORD, because only the NOT INVALID KEY phrase does that. The "
                        + "record seeded here is dailytran.txt row 1, whose DALYTRAN-AMT is 0000009190} "
                        + "- a zoned PIC S9(09)V99 whose trailing overpunch is NEGATIVE, so the amount "
                        + "is -919.00. The program neither reads nor writes that field, and the point "
                        + "of the case is that it survives untouched: the 350-byte image is displayed "
                        + "verbatim at :168 and pinned byte for byte on the final-state channel, "
                        + "overpunch included. A codec that normalised '}' to '0' would change the "
                        + "displayed line and the stored row at once (gates G19, G21, G22).",
                seeds().with(DALYTRAN, rows(row))
                        .with(XREFFILE, rows(xrefFixtureRow(CARD_TWO, CUST_TWO, ACCT_TWO)))
                        .with(ACCTFILE, rows(accountRowTwo()))
                        .build(),
                Injection.xrefStatus(STATUS_DUPLICATE),
                List.of(bytes(DALYTRAN, 0, row), xrefFields(0, CARD_TWO, CUST_TWO, ACCT_TWO)),
                List.of(DALYTRAN, XREFFILE),
                RETURN_CODE_OK,
                concat(List.of(
                        List.of(START_BANNER, row),
                        skipLines(CARD_TWO, TRAN_ID_TWO),
                        skipLines(CARD_TWO, TRAN_ID_TWO),
                        List.of(END_BANNER))),
                concat(List.of(openTrace(ALL_DDS.size()), recordTrace(false), recordTrace(false),
                        closeTrace(ALL_DDS.size()))));
    }

    /**
     * A sequential read that reports {@code '23'}: the third arm of the read ladder abends.
     *
     * @return the case
     */
    private static ParityScenario case10() {
        String row = dailyTransactionRow(CARD_ONE, TRAN_ID_ONE);
        return scenario("case10",
                "The third arm of 1000-DALYTRAN-GET-NEXT, reached mid-pass. The ladder at :204-212 names "
                        + "only '00' and '10'; everything else - including a keyed status like '23', "
                        + "which is what this case injects on the second read - moves 12 into "
                        + "APPL-RESULT, and the guard at :213-224 then displays 'ERROR READING DAILY "
                        + "TRANSACTION FILE', renders the status as 'FILE STATUS IS: NNNN0023' and "
                        + "abends. The first record was already read, displayed and resolved, and its "
                        + "six lines are asserted before the three failure lines: an abend does not "
                        + "unwind what the pass had already emitted. No CLOSE paragraph is reached, so "
                        + "the trace ends at the failing read - CALL 'CEE3ABD' terminates the task, and "
                        + "the end banner at :195 is never displayed. RETURN-CODE 12 (gate G35).",
                seeds().with(DALYTRAN, rows(row))
                        .with(XREFFILE, rows(xrefFixtureRow(CARD_ONE, CUST_ONE, ACCT_ONE)))
                        .with(ACCTFILE, rows(accountRowOne()))
                        .build(),
                Injection.readFailure(1, STATUS_NOT_FOUND),
                List.of(),
                List.of(),
                RETURN_CODE_FATAL,
                concat(List.of(
                        List.of(START_BANNER, row),
                        xrefFoundLines(CARD_ONE, CUST_ONE, ACCT_ONE),
                        List.of(SUCCESSFUL_READ_OF_ACCOUNT_FILE),
                        abendLines(ERROR_READING_DALYTRAN, STATUS_NOT_FOUND))),
                concat(List.of(openTrace(ALL_DDS.size()), recordTrace(true),
                        List.of(TRACE_READ + DALYTRAN))));
    }

    /**
     * {@code 0000-DALYTRAN-OPEN} fails: the run abends before anything else is opened.
     *
     * @return the case
     */
    private static ParityScenario case11() {
        return openFailureCase("case11", DALYTRAN, ERROR_OPENING_DALYTRAN, STATUS_OPEN_FAILED,
                "the first of the six opens, so nothing else is opened at all and the writes channel is "
                        + "empty rather than merely zero-rowed");
    }

    /**
     * {@code 0100-CUSTFILE-OPEN} fails: a file this program never reads still abends the run.
     *
     * @return the case
     */
    private static ParityScenario case12() {
        return openFailureCase("case12", CUSTFILE, ERROR_OPENING_CUSTFILE, STATUS_OPEN_FAILED,
                "the customer file, which no paragraph of this program ever reads - and which can "
                        + "nonetheless end the run before a single daily transaction has been read, "
                        + "because OPEN is a verb like any other and its failure is fatal");
    }

    /**
     * {@code 0200-XREFFILE-OPEN} fails.
     *
     * @return the case
     */
    private static ParityScenario case13() {
        return openFailureCase("case13", XREFFILE, ERROR_OPENING_XREFFILE, STATUS_OPEN_FAILED,
                "the cross reference, whose literal reads 'CROSS REF FILE' where the DD is named "
                        + "XREFFILE - the text is the paragraph's own and is asserted as written");
    }

    /**
     * {@code 0300-CARDFILE-OPEN} fails, reported as a CICS response and translated to a batch status.
     *
     * @return the case
     */
    private static ParityScenario case14() {
        return openFailureCase("case14", CARDFILE, ERROR_OPENING_CARDFILE, STATUS_NOT_FOUND,
                "the card file, whose open is reported by CardRepository as a CICS response because its "
                        + "other seventeen callers are online programs. DFHRESP(NOTFND) translates to "
                        + "batch FILE STATUS '23', so the rendered line is 'FILE STATUS IS: NNNN0023' - "
                        + "a status this program's open ladder treats like any other non-'00' value, "
                        + "since :255 tests for '00' and nothing else");
    }

    /**
     * {@code 0400-ACCTFILE-OPEN} fails.
     *
     * @return the case
     */
    private static ParityScenario case15() {
        return openFailureCase("case15", ACCTFILE, ERROR_OPENING_ACCTFILE, STATUS_OPEN_FAILED,
                "the account file, opened INPUT and never I-O: this program reads accounts and never "
                        + "rewrites one, so a failure here costs the run its only keyed lookup target");
    }

    /**
     * {@code 0500-TRANFILE-OPEN} fails: the file the class name promises to post to.
     *
     * @return the case
     */
    private static ParityScenario case16() {
        return openFailureCase("case16", TRANFILE, ERROR_OPENING_TRANFILE, STATUS_OPEN_FAILED,
                "the transaction file - the file the prompt-mandated name TransactionPostingJob promises "
                        + "records will be posted to. It is opened INPUT at :345, closed at :453 and "
                        + "never read or written in between, so this case abends on the open of a file "
                        + "the program only ever holds. The five files opened before it are asserted to "
                        + "have been reached and written to zero times");
    }

    /**
     * {@code 9000-DALYTRAN-CLOSE} fails - and reports the customer file's literal and status.
     *
     * @return the case
     */
    private static ParityScenario case17() {
        return scenario("case17",
                "The second reproduced source property, in full. Closing the DAILY TRANSACTION file "
                        + "fails, and :372-373 displays 'ERROR CLOSING CUSTOMER FILE' and moves "
                        + "CUSTFILE-STATUS - not DALYTRAN-STATUS - into IO-STATUS. So the operator is "
                        + "told the wrong file failed and is shown the wrong status: the daily file "
                        + "reported '35', and the line rendered is 'FILE STATUS IS: NNNN0000', because "
                        + "the customer file's own open succeeded and left '00' in its status field. "
                        + "Correcting either half fails this case: a translation that displayed the "
                        + "daily file's literal, or rendered '35', would be reporting something this "
                        + "program has never reported. The five closes after it never happen, exactly as "
                        + "CALL 'CEE3ABD' means they do not.",
                emptyPassSeeds(),
                Injection.closeFailure(DALYTRAN, STATUS_OPEN_FAILED),
                List.of(),
                List.of(),
                RETURN_CODE_FATAL,
                concat(List.of(
                        List.of(START_BANNER),
                        skipLines(blanks(CARD_NUMBER_WIDTH), blanks(TRANSACTION_ID_WIDTH)),
                        abendLines(ERROR_CLOSING_CUSTFILE, STATUS_OK))),
                concat(List.of(openTrace(ALL_DDS.size()), recordTrace(false), closeTrace(1))));
    }

    /**
     * {@code 9100-CUSTFILE-CLOSE} fails - the same literal, and this time the right status.
     *
     * @return the case
     */
    private static ParityScenario case18() {
        return scenario("case18",
                "The companion to case17, and the reason both are needed. 9100-CUSTFILE-CLOSE displays "
                        + "the SAME literal - 'ERROR CLOSING CUSTOMER FILE' at :390 - but moves the "
                        + "customer file's own close status, so the rendered line is 'FILE STATUS IS: "
                        + "NNNN0035'. The two cases therefore differ only in the four characters of the "
                        + "IO-STATUS-04 image, which is precisely what distinguishes a paragraph "
                        + "reporting its own file from one reporting another's. A translation that "
                        + "collapsed the two paragraphs into one shared helper keyed on the literal "
                        + "would pass one of these cases and fail the other.",
                emptyPassSeeds(),
                Injection.closeFailure(CUSTFILE, STATUS_OPEN_FAILED),
                List.of(),
                List.of(),
                RETURN_CODE_FATAL,
                concat(List.of(
                        List.of(START_BANNER),
                        skipLines(blanks(CARD_NUMBER_WIDTH), blanks(TRANSACTION_ID_WIDTH)),
                        abendLines(ERROR_CLOSING_CUSTFILE, STATUS_OPEN_FAILED))),
                concat(List.of(openTrace(ALL_DDS.size()), recordTrace(false), closeTrace(2))));
    }

    /**
     * {@code 9200-XREFFILE-CLOSE} fails.
     *
     * @return the case
     */
    private static ParityScenario case19() {
        return scenario("case19",
                "9200-XREFFILE-CLOSE fails at :399, displays 'ERROR CLOSING CROSS REF FILE' at :408 and "
                        + "renders the cross reference's own status. Two closes have already succeeded "
                        + "and emitted nothing - a successful CLOSE displays no line - so the failure "
                        + "lines follow the pass's own output directly, and the three closes after it "
                        + "are never reached. The trace is what shows the ordering: the daily and "
                        + "customer files were closed, the cross reference was closed, and the card, "
                        + "account and transaction files were not.",
                emptyPassSeeds(),
                Injection.closeFailure(XREFFILE, STATUS_OPEN_FAILED),
                List.of(),
                List.of(),
                RETURN_CODE_FATAL,
                concat(List.of(
                        List.of(START_BANNER),
                        skipLines(blanks(CARD_NUMBER_WIDTH), blanks(TRANSACTION_ID_WIDTH)),
                        abendLines(ERROR_CLOSING_XREFFILE, STATUS_OPEN_FAILED))),
                concat(List.of(openTrace(ALL_DDS.size()), recordTrace(false), closeTrace(3))));
    }

    /**
     * {@code 9300-CARDFILE-CLOSE} is refused by the backend: the permanent-error status renders as 9000.
     *
     * @return the case
     */
    private static ParityScenario case20() {
        return scenario("case20",
                "9300-CARDFILE-CLOSE fails, and it is the one close whose failure cannot be a file "
                        + "status: ending a browse does not report one, so a refusal arrives as a thrown "
                        + "backend failure and is reported as this module's permanent-error status - "
                        + "IO-STAT1 '9' with a zero feedback code. Z-DISPLAY-IO-STATUS takes its "
                        + "extended-status arm for that ('9' at :478), rendering the first byte verbatim "
                        + "and the second byte's numeric value in three digits: 'FILE STATUS IS: "
                        + "NNNN9000', not 'NNNN0090' and not 'NNNN9   '. Nothing is swallowed - the "
                        + "refusal travels out as the abend's cause - and 'ERROR CLOSING CARD FILE' is "
                        + "displayed before it, with RETURN-CODE 12.",
                emptyPassSeeds(),
                Injection.refusedCardClose(),
                List.of(),
                List.of(),
                RETURN_CODE_FATAL,
                concat(List.of(
                        List.of(START_BANNER),
                        skipLines(blanks(CARD_NUMBER_WIDTH), blanks(TRANSACTION_ID_WIDTH)),
                        abendLines(ERROR_CLOSING_CARDFILE, STATUS_PERMANENT_ERROR))),
                concat(List.of(openTrace(ALL_DDS.size()), recordTrace(false), closeTrace(4))));
    }

    /**
     * The six open-failure cases, which differ only in which paragraph fails and what it displays.
     *
     * <p>Written once because the source writes them six times identically apart from the file, the
     * status field and the literal: {@code MOVE 8 TO APPL-RESULT}, {@code OPEN INPUT}, the {@code '00'}
     * test, then {@code DISPLAY}, {@code MOVE ... TO IO-STATUS}, {@code Z-DISPLAY-IO-STATUS} and
     * {@code Z-ABEND-PROGRAM}. Every case still asserts its own literal, its own rendered status, its own
     * trace and its own set of already-opened datasets.
     *
     * @param caseId      the case identifier
     * @param dataset     the DD whose open fails
     * @param errorText   the literal that paragraph displays
     * @param status      the status it renders
     * @param explanation what this particular open failure proves, appended to the shared description
     * @return the case
     */
    private static ParityScenario openFailureCase(String caseId, String dataset, String errorText,
            String status, String explanation) {
        Injection injection = CARDFILE.equals(dataset)
                ? Injection.cardOpenResp(CICS_RESP_NOTFND)
                : Injection.openFailure(dataset, status);
        int position = ALL_DDS.indexOf(dataset) + 1;
        return scenario(caseId,
                "OPEN INPUT of " + dataset + " fails, which is open " + position + " of the six at "
                        + ":157-162. The paragraph displays its own literal, renders the failing file's "
                        + "own status through Z-DISPLAY-IO-STATUS and abends with RETURN-CODE 12, so the "
                        + "loop never runs, no record is read, no CLOSE paragraph is reached and the end "
                        + "banner at :195 is never displayed - four lines in total. This one is "
                        + explanation + ".",
                seeds().with(DALYTRAN, rows(dailyTransactionRowOne())).build(),
                injection,
                List.of(),
                List.of(),
                RETURN_CODE_FATAL,
                concat(List.of(List.of(START_BANNER), abendLines(errorText, status))),
                openTrace(position));
    }

    // =================================================================================================
    // The case model: a ParityCase, the backend conditions its run is driven under, the datasets whose
    // final state it pins, and the file verbs it expects in order.
    // =================================================================================================

    /**
     * One case, with everything the adapter needs to reproduce the run the case describes.
     *
     * @param parityCase     the case the differ judges, built through {@link ParityCase}'s own
     *                       constructor
     * @param injection      the backend conditions this run is driven under, for the arms no seeded row
     *                       can reach
     * @param finalStatePins the datasets whose final state the adapter reports, each of which the case
     *                       must both seed and account for row by row
     * @param expectedTrace  the file verbs the run must issue, in order
     */
    private record ParityScenario(ParityCase parityCase, Injection injection,
                                  List<String> finalStatePins, List<String> expectedTrace) {

        /**
         * Refuses a scenario the adapter could not honour.
         *
         * @throws IllegalArgumentException if a pinned dataset is not one this program declares, or is
         *                                  one the case never seeds - either of which would make the
         *                                  adapter report a dataset it has no rows for
         */
        private ParityScenario {
            Objects.requireNonNull(parityCase, "A ParityCase is required: it is the expectation side");
            Objects.requireNonNull(injection, "An Injection is required; use Injection.none()");
            Objects.requireNonNull(finalStatePins, "A pin list is required; use List.of() for none");
            Objects.requireNonNull(expectedTrace, "An expected verb trace is required");
            for (String dataset : finalStatePins) {
                if (!ALL_DDS.contains(dataset)) {
                    throw new IllegalArgumentException("Case " + parityCase.caseId() + " pins the final "
                            + "state of " + dataset + ", which CBTRN01C declares no SELECT for. The six "
                            + "it declares are " + ALL_DDS + '.');
                }
                if (!parityCase.inputs().containsKey(dataset)) {
                    throw new IllegalArgumentException("Case " + parityCase.caseId() + " pins the final "
                            + "state of " + dataset + " without seeding it, so there would be no rows "
                            + "to report and the pin would assert an empty dataset by accident. Seed "
                            + dataset + ", or drop the pin.");
                }
            }
            finalStatePins = List.copyOf(finalStatePins);
            expectedTrace = List.copyOf(expectedTrace);
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

    /**
     * Builds one case and its scenario, deriving the writes-channel expectations from the open order.
     *
     * <p>The set of datasets a case expects to have been reached is <strong>not</strong> a parameter: it
     * is computed from the injected open statuses and the order {@code MAIN-PARA} opens the six files
     * in, because an open that fails abends and the opens after it never happen. The adapter observes
     * the same set independently, from the statuses its stubs actually returned, so the two are derived
     * separately and compared by the differ.
     *
     * @param caseId         the case identifier
     * @param description    what the case exercises, in prose
     * @param inputs         the datasets to seed
     * @param injection      the backend conditions
     * @param finalStateRows the rows pinned on the final-state channel, complete for every dataset named
     * @param finalStatePins the datasets whose final state is reported
     * @param returnCode     the expected {@code RETURN-CODE}
     * @param lines          the expected {@code DISPLAY} lines, in emission order
     * @param trace          the expected file verbs, in order
     * @return the scenario
     */
    private static ParityScenario scenario(String caseId, String description,
            Map<String, ParityCase.DatasetInput> inputs, Injection injection,
            List<ParityCase.ExpectedRecord> finalStateRows, List<String> finalStatePins,
            int returnCode, List<String> lines, List<String> trace) {

        List<ParityCase.ExpectedDataset> datasets = new ArrayList<>();
        for (String dataset : openedDatasets(injection)) {
            datasets.add(new ParityCase.ExpectedDataset(dataset, ParityCase.DatasetChannel.WRITES, 0,
                    widthOf(dataset)));
        }
        for (String dataset : finalStatePins) {
            datasets.add(new ParityCase.ExpectedDataset(dataset, ParityCase.DatasetChannel.FINAL_STATE,
                    rowsPinnedFor(finalStateRows, dataset), widthOf(dataset)));
        }
        ParityCase parityCase = new ParityCase(PROGRAM, caseId, description, UNIT_KIND, inputs,
                Map.of(), null, null, List.of(), finalStateRows, returnCode, messages(lines),
                normalisationsFor(inputs), List.copyOf(datasets));
        return new ParityScenario(parityCase, injection, finalStatePins, trace);
    }

    /**
     * The same seeds, expectations and trace as another scenario, driven under different backend
     * conditions.
     *
     * <p>Used only by {@link #theRemainingTwoCloseArmsAbendWithTheirOwnLiterals()}, which asserts the
     * emitted lines directly rather than through the differ: the borrowed case's own expectations are
     * <em>not</em> judged there, and nothing in the twenty is affected.
     *
     * @param base      the scenario to borrow the seeds from
     * @param injection the conditions to drive instead
     * @return the derived scenario
     */
    private static ParityScenario sameSeedsWith(ParityScenario base, Injection injection) {
        return new ParityScenario(base.parityCase(), injection, base.finalStatePins(),
                base.expectedTrace());
    }

    // =================================================================================================
    // The injected backend conditions. Every arm the seeded data cannot reach - a failing open, a
    // failing close, a read that reports something the ladder does not name - is reached from here.
    // =================================================================================================

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
     * The datasets a run reaches, in open order, given the conditions it is driven under.
     *
     * <p>{@code MAIN-PARA} opens the six in a fixed order and each open abends on failure, so the set is
     * a prefix of {@link #ALL_DDS} - which is why an open failure on the first DD leaves the writes
     * channel empty rather than zero-rowed.
     *
     * @param injection the conditions
     * @return the DDs whose {@code OPEN} reports {@code '00'}, in open order
     */
    private static List<String> openedDatasets(Injection injection) {
        List<String> opened = new ArrayList<>(ALL_DDS.size());
        for (String dataset : ALL_DDS) {
            if (!STATUS_OK.equals(effectiveOpenStatus(injection, dataset))) {
                break;
            }
            opened.add(dataset);
        }
        return List.copyOf(opened);
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
         * @param scenario the case to reproduce
         */
        private PostingRun(ParityScenario scenario) {
            this.scenario = scenario;
            this.injection = scenario.injection();
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
         * Reports what the run produced: that every dataset it reached was written to zero times, the
         * final state of the datasets the case pins, and every {@code DISPLAY} line in emission order.
         *
         * <p>The writes channel is reported for every dataset whose {@code OPEN} succeeded and is
         * reported as <em>opened and not written to</em>, which is a stronger statement than omitting
         * the dataset: omitting it would say only that the case never mentioned it, while this says the
         * run reached it and produced no record. That is the whole claim of this gate, and it is made in
         * every case rather than in one.
         *
         * @param recorder   where observations go
         * @param invocation the invocation holding the seeded rows
         */
        private void record(ParityHarness.UnitOutcome.Builder recorder,
                ParityHarness.Invocation invocation) {
            for (String dataset : openedOk) {
                recorder.openedWithoutWriting(dataset, layoutOf(dataset));
            }
            for (String dataset : scenario.finalStatePins()) {
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
         * @param dataset the DD being read
         */
        private void read(String dataset) {
            trace.add(TRACE_READ + dataset);
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
                read(DALYTRAN);
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
                read(XREFFILE);
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
                read(ACCTFILE);
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

    /** @return the seed set the close-failure cases share: a daily file that exists and holds no row */
    private static Map<String, ParityCase.DatasetInput> emptyPassSeeds() {
        return seeds().with(DALYTRAN, emptyDataset(DALYTRAN_WIDTH, copybookOf(DALYTRAN))).build();
    }

    /**
     * @param images the literal rows to seed, at their copybook width or - for the cross reference - at
     *               the 36 the fixture holds
     * @return the input
     */
    private static ParityCase.DatasetInput rows(String... images) {
        return new ParityCase.DatasetInput(List.of(images), null, null, null, null, null, null);
    }

    /**
     * @param fixture the fixture file name
     * @return an input seeding every row of it
     */
    private static ParityCase.DatasetInput fixture(String fixture) {
        return new ParityCase.DatasetInput(List.of(), fixture, null, null, null, null, null);
    }

    /**
     * @param recordLength the width the dataset reports
     * @param copybook     the member that declares it
     * @return an input for a dataset that <em>exists</em> and holds no row, which is what reaches the
     *         first-read end-of-file branch
     */
    private static ParityCase.DatasetInput emptyDataset(int recordLength, String copybook) {
        return new ParityCase.DatasetInput(List.of(), null, null, null, Boolean.TRUE, recordLength,
                copybook);
    }

    /**
     * A row pinned by its whole image, which asserts every byte and therefore the total width - and so
     * that every {@code FILLER} span was emitted (gates G19 and G21).
     *
     * @param dataset  the binding key
     * @param rowIndex the zero-based row
     * @param image    the complete record image
     * @return the expectation
     */
    private static ParityCase.ExpectedRecord bytes(String dataset, int rowIndex, String image) {
        return new ParityCase.ExpectedRecord(dataset, rowIndex, Map.of(),
                requireWidth(image, widthOf(dataset), copybookOf(dataset)));
    }

    /**
     * A cross-reference row pinned field by field, including the {@code FILLER} the fixture does not
     * carry and the pad supplies.
     *
     * <p>All four spans are named, which is what makes the expectation complete: 16 + 9 + 11 + 14 is the
     * whole 50 bytes, so no byte of the record goes unasserted.
     *
     * @param rowIndex   the zero-based row
     * @param cardNumber {@code XREF-CARD-NUM}
     * @param customerId {@code XREF-CUST-ID}
     * @param accountId  {@code XREF-ACCT-ID}
     * @return the expectation
     */
    private static ParityCase.ExpectedRecord xrefFields(int rowIndex, String cardNumber,
            int customerId, long accountId) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(CardXrefRecord.XREF_CARD_NUM_NAME, exact(cardNumber, CARD_NUMBER_WIDTH,
                CardXrefRecord.XREF_CARD_NUM_NAME));
        fields.put(CardXrefRecord.XREF_CUST_ID_NAME, digits(customerId, CUSTOMER_ID_WIDTH));
        fields.put(CardXrefRecord.XREF_ACCT_ID_NAME, digits(accountId, ACCOUNT_ID_WIDTH));
        fields.put("FILLER", blanks(CardXrefRecord.FILLER_LENGTH));
        return new ParityCase.ExpectedRecord(XREFFILE, rowIndex, fields, null);
    }

    /**
     * @param expectations the pinned rows
     * @param dataset      the binding key
     * @return how many of them address that dataset, which is the row count its dataset-level expectation
     *         declares
     */
    private static int rowsPinnedFor(List<ParityCase.ExpectedRecord> expectations, String dataset) {
        int pinned = 0;
        for (ParityCase.ExpectedRecord expectation : expectations) {
            if (dataset.equals(expectation.dataset())) {
                pinned++;
            }
        }
        return pinned;
    }

    /**
     * @param lines the expected lines, in emission order
     * @return them on the {@code DISPLAY} channel, whose width is whatever the concatenated operands make
     *         it
     */
    private static List<ParityCase.EmittedMessage> messages(List<String> lines) {
        List<ParityCase.EmittedMessage> emitted = new ArrayList<>(lines.size());
        for (String line : lines) {
            emitted.add(new ParityCase.EmittedMessage(ParityCase.MessageChannel.DISPLAY_LINE, line));
        }
        return List.copyOf(emitted);
    }

    /**
     * @param inputs the seed set
     * @return the 36-to-50 cross-reference pad when this case seeds cross-reference rows, and nothing
     *         otherwise - a normalisation for a dataset that is unseeded or declared empty has no row to
     *         apply to and is refused by {@link ParityCase}
     */
    private static List<ParityCase.DatasetNormalisation> normalisationsFor(
            Map<String, ParityCase.DatasetInput> inputs) {
        ParityCase.DatasetInput crossReference = inputs.get(XREFFILE);
        if (crossReference == null || crossReference.declaredEmpty()) {
            return List.of();
        }
        return List.of(new ParityCase.DatasetNormalisation(XREFFILE,
                ParityCase.Normalisation.CARDXREF_FILLER_PAD_36_TO_50));
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
     * Row 0's shape carrying another card number and transaction id, for the cases whose subject is which
     * key the lookup is issued with rather than what the rest of the record holds.
     *
     * @param cardNumber    {@code DALYTRAN-CARD-NUM}
     * @param transactionId {@code DALYTRAN-ID}
     * @return the 350-byte record image
     */
    private static String dailyTransactionRow(String cardNumber, String transactionId) {
        return dalytranRow(transactionId, "01", "0001", "POS TERM", "Purchase at Abshire-Lowe",
                AMOUNT_POSITIVE, MERCHANT_ID, "Abshire-Lowe", "North Enoshaven", "72112", cardNumber,
                ORIGIN_TIMESTAMP, PROCESS_TIMESTAMP_BLANK);
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
     * Derives the expectations for a pass over all 300 rows of {@code dailytran.txt}.
     *
     * <p>Mechanical, and derived from the <em>fixtures and the copybook offsets</em> - never from the
     * translation. Each row supplies its own image line, its card number is resolved against
     * {@code cardxref.txt} by the same 16/9/11 offsets {@code app/cpy/CVACT03Y.cpy} declares, and the
     * resolved account id is looked for among the eleven-digit keys of {@code acctdata.txt}. The lines
     * each outcome produces are the ones written out above, so the derivation decides only <em>which</em>
     * lines and in what order - which is the part of the program a 300-row pass is worth asserting.
     *
     * <p>The post-end-of-file lookup is derived the same way, on the last row's card number and
     * transaction id, because that is what the untouched record area still holds.
     *
     * @return the derived expectations
     */
    private static FullPass fullPass() {
        Map<String, String[]> crossReference = new LinkedHashMap<>();
        for (String row : fixtureRows(CARDXREF_FIXTURE)) {
            crossReference.put(row.substring(0, CARD_NUMBER_WIDTH), new String[] {
                    row.substring(CARD_NUMBER_WIDTH, CARD_NUMBER_WIDTH + CUSTOMER_ID_WIDTH),
                    row.substring(CARD_NUMBER_WIDTH + CUSTOMER_ID_WIDTH)});
        }
        Set<String> accounts = new LinkedHashSet<>();
        for (String row : fixtureRows(ACCTDATA_FIXTURE)) {
            accounts.add(row.substring(0, ACCOUNT_ID_WIDTH));
        }

        List<String> daily = fixtureRows(DAILYTRAN_FIXTURE);
        List<String> lines = new ArrayList<>();
        List<String> trace = new ArrayList<>(openTrace(ALL_DDS.size()));
        List<ParityCase.ExpectedRecord> pinned = new ArrayList<>(daily.size());
        lines.add(START_BANNER);

        // The record area before the first read: a PIC X span holds spaces, which is what the lookup of
        // an empty file would be issued with. Both values are replaced by every successful read, and the
        // pair that survives the last one is what the post-end-of-file lookup uses.
        String card = blanks(CARD_NUMBER_WIDTH);
        String transactionId = blanks(TRANSACTION_ID_WIDTH);
        for (int index = 0; index < daily.size(); index++) {
            String row = daily.get(index);
            pinned.add(bytes(DALYTRAN, index, row));
            lines.add(row);
            card = row.substring(DALYTRAN_CARD_NUM_OFFSET,
                    DALYTRAN_CARD_NUM_OFFSET + CARD_NUMBER_WIDTH);
            transactionId = row.substring(0, TRANSACTION_ID_WIDTH);
            lines.addAll(lookupLines(card, transactionId, crossReference, accounts));
            trace.addAll(recordTrace(crossReference.containsKey(card)));
        }
        lines.addAll(lookupLines(card, transactionId, crossReference, accounts));
        trace.addAll(recordTrace(crossReference.containsKey(card)));
        lines.add(END_BANNER);
        trace.addAll(closeTrace(ALL_DDS.size()));

        return new FullPass(List.copyOf(lines), List.copyOf(trace), List.copyOf(pinned));
    }

    /**
     * The lines one cross-reference lookup and its account read produce.
     *
     * @param card           the sixteen-character card number the lookup is issued with
     * @param transactionId  the transaction id the skip line shows
     * @param crossReference the cross-reference fixture, keyed by card number
     * @param accounts       the eleven-digit keys the account fixture holds
     * @return the lines, in order
     */
    private static List<String> lookupLines(String card, String transactionId,
            Map<String, String[]> crossReference, Set<String> accounts) {
        String[] resolved = crossReference.get(card);
        if (resolved == null) {
            return skipLines(card, transactionId);
        }
        List<String> lines = new ArrayList<>(xrefFoundLines(card, resolved[0], resolved[1]));
        if (accounts.contains(resolved[1])) {
            lines.add(SUCCESSFUL_READ_OF_ACCOUNT_FILE);
        } else {
            lines.addAll(accountNotFoundLines(resolved[1]));
        }
        return List.copyOf(lines);
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
