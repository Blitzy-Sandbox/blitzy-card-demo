package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.account.AccountRepository;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.transaction.TransactionRepository;
import com.vsergeychik.carddemo.transaction.TransactionViewController;
import com.vsergeychik.carddemo.transaction.TransactionViewController.ProgramState;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewRequest;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewResponse;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewResponse.ScreenField;
import com.vsergeychik.carddemo.transaction.model.TranRecord;
import com.vsergeychik.carddemo.util.DateUtilityJob;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The parity gate for {@code app/cbl/COTRN02C.cbl} - twenty declarative cases, judged field by field,
 * with a required diff count of zero.
 *
 * <h2>Risk R-B: the class is called {@code TransactionViewController} and the program <em>adds</em></h2>
 *
 * <p>This is the other half of the highest-risk naming ambiguity in the migration, and it is recorded
 * here in full rather than quietly reconciled, because a reader who trusts the class name will write a
 * read-only expectation and a read-only expectation will pass against a program that inserts nothing -
 * which is precisely the failure this file exists to prevent.
 *
 * <ul>
 *   <li><strong>The build prompt's mapping.</strong> {@code COTRN02C} maps to
 *       {@link TransactionViewController} and {@code COTRN01C} maps to
 *       {@code TransactionAddController}.</li>
 *   <li><strong>The verified source behaviour is the opposite.</strong>
 *       {@code app/cbl/COTRN02C.cbl:5} states
 *       {@code Function    : Add a new Transaction to TRANSACT file}, and the program browses
 *       {@code TRANSACT} backwards for the highest key ({@code STARTBR} at {@code :644},
 *       {@code READPREV} at {@code :675}, {@code ENDBR} at {@code :704}), adds one to it, and
 *       <strong>writes</strong> a 350-byte record at {@code :713}.</li>
 *   <li><strong>An independent corroboration.</strong> {@code README.md:213-231} carries the project's
 *       own online inventory and records {@code CT02 | COTRN02 | COTRN02C | Transaction Add} against
 *       {@code CT01 | COTRN01 | COTRN01C | Transaction View} - the inverse of the prompt's mapping.</li>
 *   <li><strong>The resolution, by rule R1.</strong> The name comes from the prompt and the behaviour
 *       comes from the source. The prompt's class name is honoured verbatim; <em>every</em> expectation
 *       in this file therefore describes an <strong>INSERT</strong> - resolve the account or the card
 *       through the cross-reference, validate the eleven data fields, generate the next identifier from
 *       a backward browse, and write.</li>
 *   <li><strong>It is flagged, not decided.</strong> Per practice B4 the conflict is documented rather
 *       than corrected: the Agent Action Plan escalates it for <strong>explicit user confirmation</strong>
 *       and nothing here guesses which artefact is authoritative about <em>intent</em> - only about
 *       behaviour, where the source is the only oracle there is.</li>
 * </ul>
 *
 * <p><strong>Consequence for anyone editing this file:</strong> a case that asserts no write on the
 * confirmed-add path would be wrong. {@link RiskRb} makes that failure immediate rather than subtle. The
 * mirror-image test is {@code COTRN01CParityTest}, whose class is named {@code TransactionAddController}
 * and whose source performs a single keyed {@code READ} and never writes.
 *
 * <h2>Risk R-A: the baseline is statically derived, never captured</h2>
 *
 * <p>Every expected value in {@code src/test/resources/parity/COTRN02C/} was derived by reading the
 * COBOL, its copybooks, its BMS mapset and the CSD - <strong>not</strong> by executing the legacy
 * program. Execution is impossible in this environment; the eight independently verified blockers are
 * recorded in the migration plan and include a COBOL compiler whose indexed-file handler is disabled,
 * the absence of any Language Environment {@code CEE*} service (so no {@code CEEDAYS} behind
 * {@code CSUTLDTC}), the absence of a CICS emulator, and the absence of the IBM-supplied {@code DFHAID}
 * and {@code DFHBMSCA} copybooks this program copies at {@code :91-92}. Practice B12 requires that limit
 * be stated where the expectations live rather than absorbed silently, so it is stated here.
 *
 * <p>Because a statically derived expectation can encode a misreading where a captured one cannot, each
 * case's {@code description} names the source lines it was derived from, and the structural assertions in
 * the nested classes below re-derive every width mechanically from the copybook-backed layouts instead of
 * restating them as literals.
 *
 * <h2>No user rules were provided</h2>
 *
 * <p>{@code review_rules} returns exactly one line - "No user rules provided" - so no rule forces this
 * file into scope and no rule constrains how it is written. Per <strong>UR4</strong> that absence is
 * stated explicitly and is <em>not</em> treated as permission to lower the bar: the standard held here is
 * the Agent Action Plan's twelve enterprise practices, cited by name where they bite. B1/B2 (only
 * JUnit 5.12.2, AssertJ 3.27.7 and Mockito 5.17.0, all managed by the Spring Boot 3.5.16 parent), B7
 * (a pinned clock, nothing interactive), B8 (no wildcard import, no {@code double}, no rounding mode but
 * {@link RoundingMode#DOWN}, a named code page, no dataset-name literal), B9 (no static mutable state),
 * B10 (shipped with the implementation), B12 (the provenance statement above).
 *
 * <h2>What the twenty cases pin</h2>
 *
 * <p>Four datasets are named by the program and only <strong>two</strong> are ever touched:
 * {@code TRANSACT} and the cross-reference, which is reached by <em>two access paths over one
 * dataset</em> - the {@code CXACAIX} alternate index at {@code :578} and the {@code CCXREF} base cluster
 * at {@code :611} - never as two tables (gate G45). {@code ACCTDAT} is declared at {@code :40} and
 * {@code COPY CVACT01Y} at {@code :89} and neither is referenced anywhere in the program; both are
 * preserved as dead declarations (practice B5) and are asserted to stay dead by {@link DeadDeclarations}.
 *
 * <p>Between them the cases drive: the {@code EIBCALEN = 0} guard; first entry with and without a
 * selection carried in {@code CDEMO-CT02-TRN-SELECTED}; every arm of the ordered inline
 * {@code EVALUATE EIBAID} including {@code WHEN OTHER}; both key-field guard chains and their
 * {@code FUNCTION NUMVAL} conversions; the eleven blank-field arms and the three positional-shape arms;
 * both {@code CSUTLDTC} call sites with the {@code '0000'}-or-{@code '2513'} acceptance rule; the
 * {@code FUNCTION NUMVAL-C} conversion of the amount at both of its sites; the backward browse and the
 * {@code ENDFILE} arm that makes an empty master loadable; the 350-byte written image; the
 * {@code DFHGREEN} success line; the duplicate and {@code WHEN OTHER} write arms with their
 * {@code DISPLAY 'RESP:' ... 'REAS:'} lines; and the {@code nextProgram} that replaces
 * {@code EXEC CICS XCTL} at {@code :509}.
 *
 * <p>The unit is constructed as a plain Java object and its {@code MAIN-PARA} method is called directly.
 * There is no {@code MockMvc}, no {@code TestRestTemplate}, no {@code WebTestClient} and no
 * {@code JobLauncher} anywhere in the path, so the program's decisions are reached with no HTTP layer
 * between the assertion and the arithmetic (gate G51).
 *
 * @see ParityHarness for how a case is seeded, run and fingerprinted
 * @see FieldDiffer for the field-by-field comparison the diff count comes from
 */
@DisplayName("COTRN02C parity - transaction CT02, a 350-byte INSERT, and the class name says View")
final class COTRN02CParityTest {

    /**
     * The program under test, which is also the name of its case directory:
     * {@code src/test/resources/parity/COTRN02C/}.
     */
    private static final String PROGRAM = "COTRN02C";

    /**
     * {@code COTRN02C} is a CICS online program and the migration gives it a controller with no service
     * beneath it, so the unit a case reaches is the controller itself, constructed as a plain object.
     */
    private static final ParityCase.UnitKind UNIT_KIND = ParityCase.UnitKind.CONTROLLER_POJO;

    /**
     * The dataset binding key for {@code WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'} ({@code :39}).
     *
     * <p>Taken from the repository rather than written as a literal, so no dataset name is spelled out in
     * Java (gate G46) and the key a case declares cannot drift from the key the repository answers to.
     */
    private static final String TRANSACT_DATASET = TransactionRepository.CICS_FILE_NAME;

    /**
     * The dataset binding key for the cross-reference: {@code WS-CCXREF-FILE PIC X(08) VALUE 'CCXREF  '}
     * ({@code :41}).
     *
     * <p><strong>One key, not two.</strong> {@code WS-CXACAIX-FILE} ({@code :42}) names an alternate-index
     * <em>path</em> over this same base cluster, so a case seeds the base and both finders answer from it.
     * Seeding two datasets would model two tables, which is exactly what gate G45 forbids.
     */
    private static final String CCXREF_DATASET = CardXrefRepository.BASE_DD_NAME;

    /**
     * The {@code EIBAID} a request carries when the case declares none: {@code DFHNULL}, the attention
     * identifier CICS reports when no key raised the interrupt, which matches none of the four named arms
     * of the {@code EVALUATE} at {@code :133-152} and therefore falls to {@code WHEN OTHER}.
     */
    private static final String NO_AID_TOKEN = null;

    /**
     * The status the {@code WHEN OTHER} arms are reached with when a case forces one.
     *
     * <p>Those arms are written for conditions the source does not enumerate, so no enumerated status can
     * reach them. {@link FileStatus#RECORD_LENGTH_CONFLICT} is used because it is a real status that is
     * deliberately none of the ones the program's own arms test for, which is exactly the shape of an
     * unexpected condition.
     */
    private static final String UNEXPECTED_STATUS = FileStatus.RECORD_LENGTH_CONFLICT;

    // =================================================================================================
    // The gate.
    // =================================================================================================

    /**
     * The program's twenty cases, in ascending case order.
     *
     * <p>{@link ParityHarness#casesOf(String)} is the only loader used, and it refuses anything other than
     * exactly {@code case01.json} through {@code case20.json} - a short set, a long set, or a directory
     * holding a stray file all fail loudly there. The redundant check below states the count a second time
     * at the call site so a reader of this class can see the number the gate requires without following
     * the call (gate G15).
     *
     * @return exactly twenty cases
     */
    static List<ParityCase> cases() {
        List<ParityCase> loaded = ParityHarness.casesOf(PROGRAM);

        assertThat(loaded)
                .as("the gate is stated as twenty declarative cases per program, and 'diff count is "
                        + "zero across all twenty' is satisfied vacuously by a shorter set - so the "
                        + "count is asserted before a single case runs")
                .hasSize(ParityHarness.CASES_PER_PROGRAM);

        List<String> ids = new ArrayList<>(loaded.size());
        for (ParityCase parityCase : loaded) {
            ids.add(parityCase.caseId());
            assertThat(parityCase.program())
                    .as("every case in parity/%s/ must name that program, or it is judging a different "
                            + "one", PROGRAM)
                    .isEqualTo(PROGRAM);
            assertThat(parityCase.unitKind())
                    .as("%s is a CICS online program reached as a plain controller object; a case "
                            + "declaring any other unit kind would be run by a different adapter",
                            PROGRAM)
                    .isEqualTo(UNIT_KIND);
            assertThat(parityCase.jobParameters())
                    .as("COTRN02C is an online transaction: it has no PARM and no job parameter")
                    .isEmpty();
        }

        List<String> expected = new ArrayList<>(ParityHarness.CASES_PER_PROGRAM);
        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            expected.add(ParityHarness.caseId(ordinal));
        }
        assertThat(ids)
                .as("the twenty cases are case01 through case20 in ascending order, so a gap or a "
                        + "duplicate is visible here rather than as a quietly smaller suite")
                .containsExactlyElementsOf(expected);
        return loaded;
    }

    /**
     * The gate: one case, run once, judged field by field, and required to differ in nothing.
     *
     * <p>The assertion is on the whole {@link FieldDiffer.DiffResult} rather than on a boolean, so a
     * failure reports the count and then every difference the differ found, each naming the field, the
     * expected value, the observed value and why the field matters. That rendering is the differ's work
     * and is surfaced verbatim rather than summarised.
     *
     * @param parityCase one of the twenty cases
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("field-for-field identical to app/cbl/COTRN02C.cbl")
    void isFieldForFieldIdenticalToTheCobol(ParityCase parityCase) {
        FieldDiffer.DiffResult diff =
                ParityHarness.usAscii().judge(parityCase, UNIT_KIND, COTRN02CParityTest::execute);

        assertThat(diff.count())
                .as("%s/%s must produce a diff count of zero. A module is not complete until the count "
                        + "is zero across all twenty of its cases, so a single difference here is a "
                        + "failed gate rather than a tolerance.%n%s",
                        parityCase.program(), parityCase.caseId(), diff.render())
                .isZero();
        assertThat(diff.isClean())
                .as("the differ reported a clean result and a non-zero count, or the reverse - the two "
                        + "must agree.%n%s", diff.render())
                .isTrue();
    }

    // =================================================================================================
    // The adapter: how a case reaches COTRN02C. No HTTP, no job launcher, no session (gates G37, G51).
    // =================================================================================================

    /**
     * Constructs {@link TransactionViewController} as a plain object and calls its {@code MAIN-PARA}
     * method once.
     *
     * <p>One invocation is one CICS task. The four collaborators are supplied explicitly:
     *
     * <ul>
     *   <li>the {@code TRANSACT} master, stubbed over the case's own seeded rows so that the backward
     *       browse and the write answer from the data the case declares rather than from a hand-written
     *       outcome;</li>
     *   <li>the cross-reference dataset, likewise stubbed over one seeded base cluster answering
     *       <em>both</em> access paths (gate G45);</li>
     *   <li>a <strong>real</strong> {@link DateUtilityJob}, not a stub. It is deterministic and the three
     *       {@code CSUTLDTC} outcomes this screen distinguishes are reachable with three real dates, so
     *       stubbing it would assert the controller against a fiction. The full nine-token feedback table
     *       is pinned by {@code CSUTLDTCParityTest}; the only thing asserted here is the acceptance
     *       <em>rule</em>;</li>
     *   <li>the case's pinned clock, so the two header items {@code POPULATE-HEADER-INFO} builds at
     *       {@code :554-570} are comparable byte for byte and the run is deterministic (practice B7).</li>
     * </ul>
     *
     * <p>No {@code DataSource} and no transaction manager are wired, because this program takes no record
     * lock: the browse is read-only and the {@code WRITE} at {@code :713} carries no {@code UPDATE}
     * option to pair with. The code page is the case's, never the platform default (practice B8).
     *
     * @param invocation the seeded, clocked invocation the harness prepared
     * @return the fingerprint of that one run
     */
    private static ParityHarness.UnitOutcome execute(ParityHarness.Invocation invocation) {
        TransactMaster master = masterOf(invocation);
        CrossReference crossReference = crossReferenceOf(invocation);

        TransactionRepository transactions = transactionRepositoryFor(invocation, master);
        CardXrefRepository crossReferenceRepository =
                cardXrefRepositoryFor(invocation, crossReference);

        TransactionViewController controller = new TransactionViewController(
                transactions,
                crossReferenceRepository,
                new DateUtilityJob(invocation.charset()),
                invocation.clock(),
                invocation.charset());

        ProgramState state = controller.mainPara(requestOf(invocation));

        requireOnlyTheSourcesOwnAccessPaths(transactions, crossReferenceRepository);
        return fingerprintOf(invocation, master, state);
    }

    /**
     * Proves, on <em>every</em> case rather than on one, that the run reached the datasets only the way
     * the source reaches them.
     *
     * <p>Three claims, and each of them is a translation defect that no field comparison would catch:
     *
     * <ul>
     *   <li>{@code TRANSACT} is never read by key. The program has no {@code EXEC CICS READ} against it -
     *       the highest identifier comes from a browse, deliberately, because {@code READ} needs a key and
     *       the point of the browse is that the key is not yet known.</li>
     *   <li>{@code TRANSACT} is never browsed <em>forwards</em>. {@code STARTBR} at {@code :644} is issued
     *       with {@code TRAN-ID} holding {@code HIGH-VALUES}, which positions past the end so the first
     *       {@code READPREV} returns the highest key. A forward browse would return the <em>lowest</em>,
     *       and every generated identifier after that would be wrong by the size of the file.</li>
     *   <li>The cross-reference is never rewritten or browsed. The program only reads it, by one of its two
     *       keys.</li>
     * </ul>
     *
     * @param transactions    the {@code TRANSACT} master the run went through
     * @param crossReference  the cross-reference the run went through
     */
    private static void requireOnlyTheSourcesOwnAccessPaths(TransactionRepository transactions,
                                                            CardXrefRepository crossReference) {
        verify(transactions, never()).readByTranId(anyString());
        verify(transactions, never()).readForUpdateByTranId(anyString());
        verify(transactions, never())
                .startBrowse(TransactionRepository.BrowseDirection.FORWARD);
        verify(transactions, never()).openInput();
        verify(transactions, never()).openOutput();
        verify(crossReference, never()).openBrowse();
    }

    // =================================================================================================
    // The TRANSACT master, as a key-sequenced file private to one run.
    // =================================================================================================

    /**
     * The {@code TRANSACT} dataset as this case declares it, held in key sequence.
     *
     * <p>An instance per run, never a static field, so twenty cases cannot see each other's rows
     * (practice B9, gate G53). Key sequence rather than declaration order because that is what a KSDS is:
     * {@code app/csd/CARDDEMO.CSD} defines {@code TRANSACT} over
     * {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}, and "the record with the highest key" is only a
     * well-defined thing to browse for if the file is ordered by key.
     */
    private static final class TransactMaster {

        /** The 350-byte record images, ascending by the sixteen-character {@code TRAN-ID}. */
        private final List<String> rows;

        /** The case's code page. */
        private final Charset charset;

        /** The move-rule engine, used to bring a short declared row up to the copybook's width. */
        private final FixedWidthCodec codec;

        /**
         * The backward browse position: the index one <em>above</em> the next row a {@code READPREV} will
         * return. Starts at the row count, which is where {@code RIDFLD} holding {@code HIGH-VALUES}
         * positions it.
         */
        private int backwardCursor;

        /**
         * @param seededRows the rows the case declared, in any order
         * @param charset    the case's code page
         */
        TransactMaster(List<String> seededRows, Charset charset) {
            this.charset = Objects.requireNonNull(charset, "A code page is required");
            this.codec = new FixedWidthCodec(charset);
            List<String> ordered = new ArrayList<>(seededRows.size());
            for (String row : seededRows) {
                ordered.add(codec.padToDeclaredWidth(row, TranRecord.RECORD_LENGTH));
            }
            ordered.sort(String::compareTo);
            this.rows = ordered;
            this.backwardCursor = ordered.size();
        }

        /** @return the rows as the dataset now holds them, in key sequence */
        List<String> rows() {
            return Collections.unmodifiableList(new ArrayList<>(rows));
        }

        /**
         * Positions a fresh backward browse. {@code EXEC CICS STARTBR} with {@code RIDFLD} at
         * {@code HIGH-VALUES} positions past the last record, so the next {@code READPREV} returns it.
         */
        void startBackwardBrowse() {
            backwardCursor = rows.size();
        }

        /**
         * {@code EXEC CICS READPREV} - {@code :675-683}.
         *
         * @return the next lower record, or {@code ENDFILE} once the browse is exhausted, which for an
         *         empty master is on the very first read - the arm at {@code :688-689} that makes an empty
         *         file loadable
         */
        TransactionRepository.ReadResult readPrev() {
            if (backwardCursor <= 0) {
                return TransactionRepository.ReadResult
                        .endOfFile(TransactionRepository.CICS_FILE_NAME);
            }
            backwardCursor--;
            return TransactionRepository.ReadResult.found(TransactionRepository.CICS_FILE_NAME,
                    TranRecord.decode(rows.get(backwardCursor), charset));
        }

        /**
         * {@code EXEC CICS WRITE} - {@code :713-721}: insert the record at its key position.
         *
         * @param record the 350-byte record the program built
         * @return {@code written}, or {@code duplicate} when a row already carries that key - which is
         *         what {@code DFHRESP(DUPREC)} at {@code :736} means for a KSDS base cluster
         */
        TransactionRepository.WriteResult insert(TranRecord record) {
            String image = new String(record.encode(charset), charset);
            String key = keyOf(image);
            for (String row : rows) {
                if (keyOf(row).equals(key)) {
                    return TransactionRepository.WriteResult
                            .duplicate(TransactionRepository.CICS_FILE_NAME);
                }
            }
            rows.add(image);
            rows.sort(String::compareTo);
            return TransactionRepository.WriteResult
                    .written(TransactionRepository.CICS_FILE_NAME);
        }

        /**
         * @param image a 350-byte record image
         * @return its sixteen-character {@code TRAN-ID} span, which is the cluster's key
         */
        private static String keyOf(String image) {
            return image.substring(TranRecord.TRAN_ID_OFFSET,
                    TranRecord.TRAN_ID_OFFSET + TranRecord.TRAN_ID_LENGTH);
        }
    }

    /**
     * Builds the master from whatever the case seeded, including nothing at all.
     *
     * @param invocation the invocation being run
     * @return the master, empty when the case declares no {@code TRANSACT} rows
     */
    private static TransactMaster masterOf(ParityHarness.Invocation invocation) {
        List<String> rows = invocation.hasDataset(TRANSACT_DATASET)
                ? invocation.dataset(TRANSACT_DATASET).rows()
                : List.of();
        return new TransactMaster(rows, invocation.charset());
    }

    /**
     * The {@code TRANSACT} repository over that master.
     *
     * <p>The browse is answered from the seeded rows, so the {@code NORMAL} and {@code ENDFILE} arms of
     * {@code EVALUATE WS-RESP-CD} at {@code :685-697} are both reached by the same mechanism a terminal
     * would reach them by: rows present, or no rows. The {@code WHEN OTHER} arm at {@code :690} is
     * different in kind - it is written for a condition the source does not enumerate, so no seeded row
     * can produce it, and a case reaches it by declaring a forced outcome for the browse read.
     *
     * @param invocation the invocation being run
     * @param master     the dataset the repository reads and writes
     * @return the repository the controller will go through
     */
    private static TransactionRepository transactionRepositoryFor(ParityHarness.Invocation invocation,
                                                                  TransactMaster master) {
        TransactionRepository repository = mock(TransactionRepository.class);
        when(repository.datasetCharset()).thenReturn(invocation.charset());
        when(repository.recordLength()).thenReturn(TranRecord.RECORD_LENGTH);
        when(repository.startBrowse(TransactionRepository.BrowseDirection.BACKWARD))
                .thenAnswer(positioning -> backwardBrowseOver(invocation, master));
        when(repository.write(any()))
                .thenAnswer(writing -> writeOutcomeOf(invocation, master,
                        writing.getArgument(0, TranRecord.class)));
        return repository;
    }

    /**
     * A backward browse over the master, positioned afresh.
     *
     * <p>{@code readNext} is stubbed to fail rather than left to return {@code null}: a translation that
     * read the browse forwards would otherwise get a silent {@code null} and fail somewhere unrelated,
     * whereas the message here names the mistake.
     *
     * @param invocation the invocation being run
     * @param master     the dataset being browsed
     * @return the browse handle
     */
    private static TransactionRepository.Browse backwardBrowseOver(ParityHarness.Invocation invocation,
                                                                   TransactMaster master) {
        master.startBackwardBrowse();
        TransactionRepository.Browse browse = mock(TransactionRepository.Browse.class);
        when(browse.direction()).thenReturn(TransactionRepository.BrowseDirection.BACKWARD);
        when(browse.readPrev()).thenAnswer(reading -> browseReadOf(invocation, master));
        when(browse.readNext()).thenThrow(new IllegalStateException(
                "COTRN02C issues EXEC CICS READPREV at app/cbl/COTRN02C.cbl:675 and never READNEXT. A "
                        + "forward read of a backward browse would return the lowest key rather than the "
                        + "highest, and every identifier this program generated afterwards would be "
                        + "wrong."));
        return browse;
    }

    /**
     * One {@code READPREV}, honouring a forced outcome when the case declared one.
     *
     * @param invocation the invocation being run
     * @param master     the dataset being browsed
     * @return the outcome the read reports
     */
    private static TransactionRepository.ReadResult browseReadOf(ParityHarness.Invocation invocation,
                                                                 TransactMaster master) {
        if (!invocation.hasForcedOutcome(ParityCase.RepositoryOperation.READ_NEXT)) {
            return master.readPrev();
        }
        ParityCase.ForcedOutcome forced =
                invocation.forcedOutcome(ParityCase.RepositoryOperation.READ_NEXT);
        return switch (forced.outcome()) {
            case END_OF_FILE -> TransactionRepository.ReadResult
                    .endOfFile(TransactionRepository.CICS_FILE_NAME);
            case NOT_FOUND -> TransactionRepository.ReadResult
                    .notFound(TransactionRepository.CICS_FILE_NAME);
            case OTHER -> TransactionRepository.ReadResult
                    .other(TransactionRepository.CICS_FILE_NAME, UNEXPECTED_STATUS);
            case OK, DUPLICATE -> throw new IllegalArgumentException("A COTRN02C case forced the "
                    + forced.outcome() + " outcome for the TRANSACT browse read. That outcome carries a "
                    + "record, and forcing it would require inventing 350 bytes no copybook, fixture or "
                    + "case declared - so the identifier generated from it would be compared against an "
                    + "expectation derived from nothing. Seed a TRANSACT row instead: the row-backed "
                    + "browse then reports " + FileStatus.Outcome.OK + " for a file that holds one and "
                    + FileStatus.Outcome.END_OF_FILE + " for a file that does not.");
        };
    }

    /**
     * One {@code WRITE}, honouring a forced outcome when the case declared one.
     *
     * <p>The two rejecting arms at {@code :736} and {@code :742} cannot be reached from seeded data: the
     * identifier the program writes is one above the highest key that exists, so it never collides, and a
     * backend refusal is by definition not something a row can express. A case reaches them by declaring
     * a forced outcome, and the harness then requires the run to have actually consumed it.
     *
     * @param invocation the invocation being run
     * @param master     the dataset being written to
     * @param record     the record the program built
     * @return the outcome the write reports
     */
    private static TransactionRepository.WriteResult writeOutcomeOf(ParityHarness.Invocation invocation,
                                                                    TransactMaster master,
                                                                    TranRecord record) {
        if (!invocation.hasForcedOutcome(ParityCase.RepositoryOperation.WRITE)) {
            return master.insert(record);
        }
        ParityCase.ForcedOutcome forced =
                invocation.forcedOutcome(ParityCase.RepositoryOperation.WRITE);
        return switch (forced.outcome()) {
            case DUPLICATE -> TransactionRepository.WriteResult
                    .duplicate(TransactionRepository.CICS_FILE_NAME);
            case OTHER -> TransactionRepository.WriteResult
                    .other(TransactionRepository.CICS_FILE_NAME, UNEXPECTED_STATUS);
            case OK -> throw new IllegalArgumentException("A COTRN02C case forced the "
                    + forced.outcome() + " outcome for the TRANSACT write. A successful write is what "
                    + "the seeded master already reports, and forcing it would suppress the duplicate "
                    + "check while leaving the case's final-state expectation describing a row that was "
                    + "never inserted.");
            case NOT_FOUND, END_OF_FILE -> throw new IllegalArgumentException("A COTRN02C case forced "
                    + "the " + forced.outcome() + " outcome for the TRANSACT write. EXEC CICS WRITE "
                    + "raises neither condition, so no arm of WRITE-TRANSACT-FILE at "
                    + "app/cbl/COTRN02C.cbl:723-749 is written for it; use " + FileStatus.Outcome.OTHER
                    + " to reach the WHEN OTHER arm at :742.");
        };
    }

    // =================================================================================================
    // The cross-reference: ONE dataset, TWO access paths (gate G45).
    // =================================================================================================

    /**
     * The cross-reference dataset as this case declares it - one base cluster, read by either of its two
     * keys.
     *
     * <p>This is the shape gate G45 asks for, expressed as data rather than as a comment: there is a
     * single list of records here, and the two finders below differ only in which span of each record they
     * compare the key against. A translation that had made {@code CXACAIX} a second table would need a
     * second list, and there is nowhere to put one.
     *
     * <p>Rows arrive already right-padded from 36 to 50 bytes, because a case seeding
     * {@code app/data/ASCII/cardxref.txt} declares the
     * {@link ParityCase.Normalisation#CARDXREF_FILLER_PAD_36_TO_50} normalisation and the harness applies
     * it at seed time (gate G16). The trailing {@code FILLER PIC X(14)} that {@code app/cpy/CVACT03Y.cpy}
     * declares is absent from the fixture and is supplied as spaces.
     */
    private static final class CrossReference {

        /** The decoded records, in the order the case seeded them. */
        private final List<CardXrefRecord> records;

        /** The 50-byte image each record was decoded from, index-aligned with {@link #records}. */
        private final List<String> images;

        /** The move-rule engine, used to bring a supplied key up to its declared width. */
        private final FixedWidthCodec codec;

        /**
         * @param seededRows the 50-byte rows the case declared, already normalised by the harness
         * @param charset    the case's code page
         */
        CrossReference(List<String> seededRows, Charset charset) {
            this.codec = new FixedWidthCodec(Objects.requireNonNull(charset,
                    "A code page is required"));
            List<CardXrefRecord> decoded = new ArrayList<>(seededRows.size());
            List<String> raw = new ArrayList<>(seededRows.size());
            for (String row : seededRows) {
                String image = codec.padToDeclaredWidth(row, CardXrefRecord.RECORD_LENGTH);
                raw.add(image);
                decoded.add(CardXrefRecord.decode(image.getBytes(charset), codec));
            }
            this.records = decoded;
            this.images = raw;
        }

        /**
         * {@code EXEC CICS READ DATASET(WS-CCXREF-FILE) RIDFLD(XREF-CARD-NUM)} - {@code :611-619}: the
         * base cluster and its sixteen-character primary key.
         *
         * @param key the sixteen-character image the program moved into {@code XREF-CARD-NUM}
         * @return the found or not-found outcome, named against the base access path
         */
        CardXrefRepository.ReadResult byCardNumber(String key) {
            String wanted = codec.movePicX(key == null ? "" : key,
                    CardXrefRecord.XREF_CARD_NUM_LENGTH);
            for (int index = 0; index < records.size(); index++) {
                if (records.get(index).xrefCardNum().equals(wanted)) {
                    return CardXrefRepository.ReadResult.found(CardXrefRepository.BASE_DD_NAME,
                            records.get(index), images.get(index));
                }
            }
            return CardXrefRepository.ReadResult.notFound(CardXrefRepository.BASE_DD_NAME);
        }

        /**
         * {@code EXEC CICS READ DATASET(WS-CXACAIX-FILE) RIDFLD(XREF-ACCT-ID)} - {@code :578-586}: the
         * alternate-index path over the same cluster and its eleven-digit key.
         *
         * @param key the eleven-digit image the program moved into {@code XREF-ACCT-ID}
         * @return the found or not-found outcome, named against the alternate-index access path
         */
        CardXrefRepository.ReadResult byAccountId(String key) {
            String wanted = codec.movePicX(key == null ? "" : key,
                    CardXrefRecord.XREF_ACCT_ID_LENGTH);
            for (int index = 0; index < records.size(); index++) {
                String held = codec.movePic9(records.get(index).xrefAcctId(),
                        CardXrefRecord.XREF_ACCT_ID_LENGTH);
                if (held.equals(wanted)) {
                    return CardXrefRepository.ReadResult.found(
                            CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                            records.get(index), images.get(index));
                }
            }
            return CardXrefRepository.ReadResult
                    .notFound(CardXrefRepository.ALTERNATE_INDEX_DD_NAME);
        }
    }

    /**
     * Builds the cross-reference from whatever the case seeded, including nothing at all.
     *
     * @param invocation the invocation being run
     * @return the dataset, empty when the case declares no cross-reference rows
     */
    private static CrossReference crossReferenceOf(ParityHarness.Invocation invocation) {
        List<String> rows = invocation.hasDataset(CCXREF_DATASET)
                ? invocation.dataset(CCXREF_DATASET).rows()
                : List.of();
        return new CrossReference(rows, invocation.charset());
    }

    /**
     * The cross-reference repository over that dataset.
     *
     * <p>Both finders share one forced-outcome declaration, keyed {@code read}, because a single run takes
     * exactly one of the two paths: {@code EVALUATE TRUE} at {@code :195} chooses between them on whether
     * the account field is blank, and never performs both. A case therefore cannot be ambiguous about
     * which read it is forcing.
     *
     * @param invocation     the invocation being run
     * @param crossReference the dataset the finders read
     * @return the repository the controller will go through
     */
    private static CardXrefRepository cardXrefRepositoryFor(ParityHarness.Invocation invocation,
                                                            CrossReference crossReference) {
        CardXrefRepository repository = mock(CardXrefRepository.class);
        when(repository.readByCardNumber(anyString()))
                .thenAnswer(reading -> invocation
                        .hasForcedOutcome(ParityCase.RepositoryOperation.READ)
                                ? forcedXrefResult(invocation, CardXrefRepository.BASE_DD_NAME)
                                : crossReference.byCardNumber(reading.getArgument(0, String.class)));
        when(repository.readByAccountIdViaAltIndex(anyString()))
                .thenAnswer(reading -> invocation
                        .hasForcedOutcome(ParityCase.RepositoryOperation.READ)
                                ? forcedXrefResult(invocation,
                                        CardXrefRepository.ALTERNATE_INDEX_DD_NAME)
                                : crossReference.byAccountId(reading.getArgument(0, String.class)));
        return repository;
    }

    /**
     * Translates a case's declared {@link ParityCase.ForcedOutcome} into the outcome a cross-reference read
     * reports.
     *
     * <p>{@code OK} and {@code DUPLICATE} are refused rather than mapped. Both carry a record, and a forced
     * one would have to invent that record's 50 bytes - a record no copybook, no fixture and no case
     * declared - and the card number taken from it would then be written into a 350-byte transaction and
     * compared against an expectation derived from nothing. A case that wants a record present seeds one.
     *
     * @param invocation the invocation being run
     * @param ddName     the access path the read was issued against
     * @return the corresponding read outcome
     */
    private static CardXrefRepository.ReadResult forcedXrefResult(ParityHarness.Invocation invocation,
                                                                  String ddName) {
        ParityCase.ForcedOutcome forced =
                invocation.forcedOutcome(ParityCase.RepositoryOperation.READ);
        return switch (forced.outcome()) {
            case NOT_FOUND -> CardXrefRepository.ReadResult.notFound(ddName);
            case END_OF_FILE -> CardXrefRepository.ReadResult.endOfFile(ddName);
            case OTHER -> CardXrefRepository.ReadResult.other(ddName, UNEXPECTED_STATUS);
            case OK, DUPLICATE -> throw new IllegalArgumentException("A COTRN02C case forced the "
                    + forced.outcome() + " outcome for the cross-reference read. That outcome carries a "
                    + "record, and forcing it would require inventing the 50 bytes of a CARD-XREF-RECORD "
                    + "that no copybook, fixture or case declared - and the card number taken from it "
                    + "would reach TRAN-CARD-NUM. Seed a " + CCXREF_DATASET + " row instead: the "
                    + "row-backed read then reports " + FileStatus.Outcome.OK + " for a key that matches "
                    + "and " + FileStatus.Outcome.NOT_FOUND + " for one that does not.");
        };
    }

    // =================================================================================================
    // Building the inbound screen: the commarea, the EIBAID and the received map.
    // =================================================================================================

    /**
     * Assembles the request from the three things {@code COTRN02C} is driven by.
     *
     * <p>{@code EIBCALEN = 0} is expressed by leaving the communication area absent, which is what the
     * translation reads it as at {@code :115} - a request with no area cannot say who called, and that is
     * the whole content of the condition. A case declaring {@code eibcalen: 0} therefore produces a request
     * carrying no {@link NavigationContext} at all rather than one carrying a blank area, and the two are
     * not the same state.
     *
     * <p>For a non-zero length, the area is assembled through the codec from the field images the case
     * declares and then parsed back by the domain types. That is
     * {@code MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA} done the way the copybook describes it:
     * the case names fields, the codec lays them out at their declared offsets and widths, and any field
     * the case does not name keeps the initialised content its {@code VALUE} clause or its picture gives
     * it. Nothing here decides what a field means.
     *
     * @param invocation the invocation being run
     * @return the inbound screen
     */
    private static TransactionViewRequest requestOf(ParityHarness.Invocation invocation) {
        TransactionViewRequest request = new TransactionViewRequest();
        FixedWidthCodec codec = invocation.codec();

        if (invocation.eibcalen() > 0) {
            request.setNavigationContext(NavigationContext.fromFixedWidth(codec,
                    codec.serialise(NavigationContext.LAYOUT,
                            subsetOf(invocation.commarea(), baseCommareaFieldNames()))));
            request.setCt02Info(TransactionViewRequest.Ct02Info.fromFixedWidth(
                    codec.serialise(TransactionViewRequest.Ct02Info.LAYOUT,
                            subsetOf(invocation.commarea(), extensionFieldNames())),
                    invocation.charset()));
        }

        // EIBAID. Declared by DFHAID mnemonic in the case, because the mnemonic is what a reader of the
        // case needs to see, and converted here to the token the payload projects. An undeclared AID stays
        // absent, which the translation reads as DFHNULL - the AID CICS reports when no key raised the
        // interrupt, and one that matches none of the four named arms of the EVALUATE at :133-152.
        String token = aidTokenOf(invocation.aid());
        if (token != NO_AID_TOKEN) {
            request.setAid(token);
        }

        for (Map.Entry<String, String> field : invocation.mapFields().entrySet()) {
            applyReceivedField(request, field.getKey(), field.getValue());
        }
        return request;
    }

    /**
     * Narrows a case's communication-area declaration to the fields one layout actually owns.
     *
     * <p>{@code COTRN02C} declares its 58-byte {@code CDEMO-CT02-INFO} extension <em>inside</em> the same
     * {@code 01} group as the 160-byte {@code CARDDEMO-COMMAREA} at {@code :72-80}, which is what makes the
     * passed area 218 bytes. In Java the two are separate types - the extension travels in the payload
     * rather than by widening {@link NavigationContext}, which all seventeen online programs share - so a
     * case's single flat {@code commarea} map has to be split, and the split is made by asking each layout
     * which names it declares rather than by matching a name prefix. A prefix test would quietly send an
     * unrecognised {@code CDEMO-} field nowhere; this way the codec rejects it.
     *
     * @param values every communication-area field the case declares
     * @param names  the names one layout declares a span for
     * @return only the entries that layout owns
     */
    private static Map<String, String> subsetOf(Map<String, String> values, List<String> names) {
        Map<String, String> owned = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (names.contains(entry.getKey())) {
                owned.put(entry.getKey(), entry.getValue());
            }
        }
        return owned;
    }

    /**
     * The sixteen field names the 160-byte {@code CARDDEMO-COMMAREA} declares, read from its own layout so
     * this class restates no offset and no width.
     *
     * @return the declared names, in copybook order
     */
    private static List<String> baseCommareaFieldNames() {
        List<String> names = new ArrayList<>();
        NavigationContext.LAYOUT.spans().forEach(span -> names.add(span.name()));
        return names;
    }

    /**
     * The six field names the 58-byte {@code CDEMO-CT02-INFO} extension declares ({@code :72-80}), read
     * from its own layout for the same reason.
     *
     * @return the declared names, in copybook order
     */
    private static List<String> extensionFieldNames() {
        List<String> names = new ArrayList<>();
        TransactionViewRequest.Ct02Info.LAYOUT.spans().forEach(span -> names.add(span.name()));
        return names;
    }

    /**
     * The payload token a {@code DFHAID} mnemonic stands for.
     *
     * <p>Two indirections, deliberately, because they are the two the translation itself makes.
     * {@code DFHAID} is IBM-supplied and absent from this repository (risk R-D), so {@link CicsAid} is the
     * single reproduction of it and the mnemonic-to-byte correspondence is read from there rather than
     * restated; and the byte is then resolved through {@link PfKeyResolver}, which is the one place the
     * migration turns an {@code EIBAID} into a key identity. {@code COTRN02C} is one of the twelve
     * programs that test {@code EIBAID} <em>inline</em> rather than copying {@code CSSTRPFY}, and the
     * requirement is that the shared resolver reproduce those inline tests as identical boolean outcomes -
     * which is exactly what routing the case's mnemonic through it asserts.
     *
     * <p>A mnemonic the resolver does not recognise - {@code DFHNULL} above all - yields no token, and the
     * absent token is read as {@code DFHNULL} again on the way back in. That round trip is the
     * {@code WHEN OTHER} arm.
     *
     * @param mnemonic the mnemonic the case declared, or {@code null} for no AID
     * @return the five-character token, or {@code null} when the case declared none or the mnemonic
     *         resolves to no key identity
     */
    private static String aidTokenOf(String mnemonic) {
        if (mnemonic == null) {
            return NO_AID_TOKEN;
        }
        for (Map.Entry<Byte, String> entry : CicsAid.mnemonicsByAid().entrySet()) {
            if (entry.getValue().equals(mnemonic)) {
                return PfKeyResolver.resolve(entry.getKey())
                        .map(PfKeyResolver.AidKey::token)
                        .orElse(NO_AID_TOKEN);
            }
        }
        throw new IllegalArgumentException('"' + mnemonic + "\" is not a DFHAID mnemonic that "
                + CicsAid.class.getName() + " reproduces, yet ParityCase accepted it. The two read the "
                + "same map, so this means they have drifted apart.");
    }

    /**
     * {@code EXEC CICS RECEIVE MAP('COTRN2A') MAPSET('COTRN02') INTO(COTRN2AI)} - one received field.
     *
     * <p>Written as an explicit dispatch over the twenty-one {@code xxxI} names the symbolic map declares,
     * resolved through {@link TransactionViewRequest.ScreenField#inputItem()} so a name can never be
     * mistyped here and so the accepted set is the copybook's rather than this file's. Explicit rather than
     * reflective on purpose: the copybook-to-field correspondence is the thing under test, and a reflective
     * setter would make it invisible to a reviewer.
     *
     * <p>Every one of the twenty-one is settable even though the program reads only fourteen of them,
     * because all twenty-one carry {@code FSET} in {@code app/bms/COTRN02.bms} - so CICS returns all
     * twenty-one whether or not the operator touched them, {@code RECEIVE-TRNADD-SCREEN} copies all
     * twenty-one, and a case that could not set them could not assert the echo.
     *
     * @param request the request being assembled
     * @param name    the {@code xxxI} item name
     * @param value   the image the case declared
     */
    private static void applyReceivedField(TransactionViewRequest request, String name, String value) {
        for (TransactionViewRequest.ScreenField field : TransactionViewRequest.ScreenField.values()) {
            if (field.inputItem().equals(name)) {
                request.setPayloadValue(field, value);
                return;
            }
        }
        List<String> declared = new ArrayList<>();
        for (TransactionViewRequest.ScreenField field : TransactionViewRequest.ScreenField.values()) {
            declared.add(field.inputItem());
        }
        throw new IllegalArgumentException('"' + name + "\" is not one of the "
                + TransactionViewResponse.FIELD_COUNT + " xxxI items app/cpy-bms/COTRN02.CPY declares. "
                + "The declared set is " + declared + ". The xxxL, xxxF and xxxA items are length, flag "
                + "and attribute metadata and are not payload fields, so they are not settable from a "
                + "case.");
    }

    // =================================================================================================
    // Projecting the run into a fingerprint.
    // =================================================================================================

    /**
     * Everything one execution of {@code COTRN02C} leaves behind that a case can compare.
     *
     * <p>Five channels, and the second and third are the ones a reader is most likely to overlook:
     *
     * <ol>
     *   <li>the record written, at its full declared 350 bytes, on the one path that writes. Recorded from
     *       {@link ProgramState#writtenRecords()} rather than from the master, because <em>what the program
     *       handed to the dataset</em> is the observation - the duplicate and {@code WHEN OTHER} arms hand
     *       over a record that is then refused, and a fingerprint taken from the master would report those
     *       runs as having written nothing at all;</li>
     *   <li>{@code TRANSACT}'s final state, reported on <strong>every</strong> path including the many that
     *       never reach the write. That is a positive assertion rather than bookkeeping: "exactly as
     *       seeded" is the honest answer for a rejected screen, and it is the only channel that can say
     *       so;</li>
     *   <li>the online response - the screen, the 218-byte communication area, the next program, the cursor
     *       and the termination;</li>
     *   <li>{@code WS-MESSAGE} at its declared {@code PIC X(80)} and {@code ERRMSGO} at its declared
     *       {@code PIC X(78)}, reported as two separate channels. {@code MOVE WS-MESSAGE TO ERRMSGO} at
     *       {@code :520} moves 80 characters into a 78-character receiver, so the two differ by the two
     *       bytes COBOL loses on the right, and reporting only one of them would make that move
     *       unobservable;</li>
     *   <li>the {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} lines from the five byte-identical
     *       {@code WHEN OTHER} arms at {@code :598}, {@code :631}, {@code :662}, {@code :691} and
     *       {@code :743}.</li>
     * </ol>
     *
     * <p>The {@code DISPLAY} lines are recorded <strong>before</strong> the two message channels because
     * that is the order they happen in: every one of the five arms displays and only then composes the
     * message it rejects with.
     *
     * <p>The {@code RETURN-CODE} is reported as zero. {@code COTRN02C} is an online program: it sets no
     * {@code RETURN-CODE} and contains no {@code CALL 'CEE3ABD'}, so zero is the value and it is stated
     * rather than defaulted.
     *
     * @param invocation the invocation that was run
     * @param master     the {@code TRANSACT} master as the run left it
     * @param state      the working storage as it stood when the task ended
     * @return the recorded outcome
     */
    private static ParityHarness.UnitOutcome fingerprintOf(ParityHarness.Invocation invocation,
                                                           TransactMaster master,
                                                           ProgramState state) {
        FixedWidthCodec codec = invocation.codec();
        ParityHarness.UnitOutcome.Builder recorder = invocation.recorder();

        for (String written : state.writtenRecords()) {
            recorder.wrote(TRANSACT_DATASET, TranRecord.LAYOUT, written);
        }
        recorder.finalState(TRANSACT_DATASET, TranRecord.LAYOUT, master.rows());
        recorder.response(observedResponseOf(codec, state));

        for (String line : state.displayLines()) {
            recorder.display(line);
        }
        if (state.screenSent()) {
            recorder.message(new ParityCase.EmittedMessage(
                    ParityCase.MessageChannel.WS_MESSAGE_80, state.message()));
            recorder.message(new ParityCase.EmittedMessage(
                    ParityCase.MessageChannel.SCREEN_ERRMSG_78, state.response().getErrmsgo()));
        }

        recorder.returnCode(0);
        return recorder.build();
    }

    /**
     * The response as the differ compares it.
     *
     * <h4>Why there is at most one send</h4>
     *
     * <p>{@code SEND-TRNADD-SCREEN} ({@code :516-534}) ends with {@code EXEC CICS RETURN}, so it is
     * <strong>terminal</strong>: control never returns to the statement after the {@code PERFORM}, and no
     * path through this program paints a second screen. That is the structural difference from
     * {@code COTRN01C}, whose send is not terminal and which therefore reports a send <em>count</em>. Here
     * the count is zero or one, and a state reporting otherwise is refused rather than reported, because a
     * single {@link ProgramState} cannot carry two different screens.
     *
     * <h4>Why a blank mapset is reported as none</h4>
     *
     * <p>{@code RETURN-TO-PREV-SCREEN} sends no map before transferring - the target program paints its own
     * - so the response blanks its mapset and map rather than leaving them naming this screen. A blank is
     * how the 3270 layer says "no map"; {@code null} is how the case model says it, because
     * {@link ParityCase.ExpectedResponse} validates a mapset name against a pattern that a run of spaces
     * cannot satisfy. Translating one to the other here keeps the case readable and loses nothing: the
     * blank and the absence carry the same single fact.
     *
     * @param codec the case's code page and move rules
     * @param state the working storage as it stood when the task ended
     * @return the observed response
     */
    private static FieldDiffer.ObservedResponse observedResponseOf(FixedWidthCodec codec,
                                                                  ProgramState state) {
        TransactionViewResponse response = state.response();

        // The 218 bytes the XCTL at :509 and the RETURN at :530 both pass, projected field by field.
        // Statelessness (rule R6, gate G37) is what makes this comparable at all: the conversation state
        // is in the payload, so it can be read off the response instead of out of a session.
        Map<String, String> navigation = new LinkedHashMap<>();
        navigation.putAll(codec.deserialise(NavigationContext.LAYOUT,
                response.getNavigationContext().toFixedWidth(codec)));
        navigation.putAll(extensionImagesOf(codec, response.getCt02Info()));

        return new FieldDiffer.ObservedResponse(
                response.getNextProgram(),
                namedOrNone(response.getNextMapset()),
                namedOrNone(response.getNextMap()),
                navigation,
                sendsOf(state),
                cursorLengthItemOf(state),
                terminationOf(state));
    }

    /**
     * The 58-byte {@code CDEMO-CT02-INFO} extension as six named field images.
     *
     * <p>Rendered through the extension's own {@link TransactionViewRequest.Ct02Info#LAYOUT} rather than by
     * reading the group's components, so every value is the image the field actually occupies -
     * {@code CDEMO-CT02-PAGE-NUM} as eight digits, the two identifiers as sixteen characters each - at the
     * copybook's declared width.
     *
     * <p>The response and the request each carry their own projection of the group, so the response's is
     * copied into the request's here purely to reach that layout. The copy goes through the group's own
     * {@code PIC X} setters, so a value is padded or truncated exactly as a {@code MOVE} would do it.
     *
     * @param codec the case's code page and move rules
     * @param info  the extension the response is carrying forward
     * @return field name to image, in copybook order
     */
    private static Map<String, String> extensionImagesOf(FixedWidthCodec codec,
                                                         TransactionViewResponse.Ct02Info info) {
        TransactionViewRequest.Ct02Info projection = new TransactionViewRequest.Ct02Info();
        projection.setTrnidFirst(info.getTrnidFirst());
        projection.setTrnidLast(info.getTrnidLast());
        projection.setPageNum(info.getPageNum());
        projection.setNextPageFlg(info.getNextPageFlg());
        projection.setTrnSelFlg(info.getTrnSelFlg());
        projection.setTrnSelected(info.getTrnSelected());
        return codec.deserialise(TransactionViewRequest.Ct02Info.LAYOUT,
                projection.toFixedWidth(codec.charset()));
    }

    /**
     * Every {@code EXEC CICS SEND MAP} the run performed, which is zero or one.
     *
     * <p>All twenty-one {@code xxxO} items are reported on the send, taken from the response's own
     * projection so the set is the copybook's. One attribute item is reported with them:
     * {@code ERRMSGC}, which is the only attribute byte this program ever assigns -
     * {@code MOVE DFHGREEN TO ERRMSGC OF COTRN2AO} at {@code :727}, on the successful-write arm and nowhere
     * else. It is reported on <em>every</em> send rather than only when it was assigned, so a case can pin
     * the default colour on a rejecting path and {@link BmsAttributes#DFHGREEN} on the accepting one, and
     * the mnemonic comes from {@link BmsAttributes} - which reproduces the absent {@code DFHBMSCA} from IBM
     * CICS documentation (risk R-D) - rather than from a byte written here.
     *
     * @param state the working storage as it stood when the task ended
     * @return zero or one send
     * @throws IllegalStateException if the run reported more than one
     */
    private static List<FieldDiffer.ObservedSend> sendsOf(ProgramState state) {
        if (!state.screenSent()) {
            return List.of();
        }

        Map<String, String> painted = new LinkedHashMap<>();
        for (ScreenField field : ScreenField.values()) {
            painted.put(field.outputItemName(), state.response().getOutputItem(field));
        }
        Map<String, String> attributes = Map.of(
                ScreenField.ERRMSG.colourItemName(),
                BmsAttributes.colourMnemonic(
                        state.response().getMetadata(ScreenField.ERRMSG).getColour()));
        return List.of(new FieldDiffer.ObservedSend(painted, attributes));
    }

    /**
     * A mapset or map name, or {@code null} where the program named none.
     *
     * @param reference the value the response carries
     * @return the name, or {@code null} when it is absent or blank
     */
    private static String namedOrNone(String reference) {
        return reference == null || reference.isBlank() ? null : reference;
    }

    /**
     * The symbolic-map length item {@code MOVE -1} was moved into, or {@code null} where the program
     * requested no cursor.
     *
     * <p>COBOL positions the cursor by moving {@code -1} into a field's {@code xxxL} item, so the length
     * item <em>is</em> the cursor and it is reported under that name rather than under the field's - which
     * is also why it is metadata rather than payload (gate G9).
     *
     * <p>Where more than one length item holds {@code -1}, the <strong>first in map declaration order</strong>
     * is reported, because that is where a 3270 puts the cursor. This program reaches that state on the
     * first-entry path that also processes a selection: {@code :123} sets {@code ACTIDINL} and a rejection
     * inside {@code PROCESS-ENTER-KEY} then sets another, and the cursor lands on the account field.
     *
     * @param state the working storage as it stood when the task ended
     * @return the {@code xxxL} item name, or {@code null}
     */
    private static String cursorLengthItemOf(ProgramState state) {
        String label = state.screenMetadata().cursorField();
        return label == null ? null : label + LENGTH_ITEM_SUFFIX;
    }

    /**
     * How the task ended: {@code EXEC CICS XCTL} at {@code :508-511} or {@code EXEC CICS RETURN} at
     * {@code :530-534}.
     *
     * <p>The two are not interchangeable and cannot both happen: an {@code XCTL} transfers and never comes
     * back, so the {@code RETURN} that follows it in the source is not reached. Neither happening would mean
     * a path fell out of {@code MAIN-PARA} without terminating, which no arm of the source does, so it is
     * refused rather than reported as a difference - a fingerprint that cannot say how the task ended is not
     * a fingerprint of a CICS program.
     *
     * @param state the working storage as it stood when the task ended
     * @return the termination
     * @throws IllegalStateException if the run reported both or neither
     */
    private static ParityCase.Termination terminationOf(ProgramState state) {
        if (state.transferred() == state.returned()) {
            throw new IllegalStateException("The run reports transferred=" + state.transferred()
                    + " and returned=" + state.returned() + ". Exactly one must hold: every arm of "
                    + "app/cbl/COTRN02C.cbl:107-159 ends either at the XCTL of :508-511 or at the "
                    + "EXEC CICS RETURN of :530-534, and an XCTL never reaches that RETURN.");
        }
        return state.transferred() ? ParityCase.Termination.XCTL
                : ParityCase.Termination.RETURN_TRANSID;
    }

    /**
     * The suffix a symbolic map gives a field's length item - the {@code L} of {@code ACTIDINL}.
     *
     * <p>Stated once here because {@link FieldAttributeSetter} owns the {@code O} and {@code C} suffixes but
     * not this one: {@code CSSETATY} writes a colour and an output item and never a length item, since
     * moving {@code -1} into a length item is the program's own cursor request rather than an attribute
     * decision.
     */
    private static final String LENGTH_ITEM_SUFFIX = "L";

    // =================================================================================================
    // Fixtures for the assertions that do not go through a case file.
    //
    // The twenty cases judge behaviour. The nested classes below pin the structural facts the cases are
    // *written against* - the record widths, the two commarea widths, the intrinsic conversion table, the
    // CSUTLDTC acceptance rule, the AID dispatch order and the absence of highlighting. A case cannot
    // assert those: it can only be consistent with them, and a case consistent with a wrong width still
    // reports a diff count of zero.
    // =================================================================================================

    /** The instant the direct assertions pin, matching the one the case files declare. */
    private static final Instant PINNED_INSTANT = Instant.parse("2022-07-19T23:12:34Z");

    /** An eleven-digit account identifier - all eleven, because {@code IS NUMERIC} accepts nothing less. */
    private static final String ACCOUNT_ID = "00000000011";

    /** The sixteen-digit card number the seeded cross-reference row carries. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** A ten-character date {@code CSUTLDTC} reports valid, with severity {@code '0000'}. */
    private static final String VALID_DATE = "2022-07-18";

    /** A date before the Lillian epoch: severity 3, message {@code 2513}, and both call sites accept it. */
    private static final String TOLERATED_DATE = "1500-01-01";

    /** A date with month 13: severity 3, message {@code 2517}, and both call sites reject it. */
    private static final String REJECTED_DATE = "2022-13-01";

    /**
     * Runs {@code work} against a controller wired exactly as {@link #execute} wires one.
     *
     * @param transactions   the {@code TRANSACT} master
     * @param crossReference the cross-reference dataset
     * @param work           what to do with the controller
     * @param <T>            whatever the caller wants back
     * @return the result of {@code work}
     */
    private static <T> T withController(TransactionRepository transactions,
                                        CardXrefRepository crossReference,
                                        Function<TransactionViewController, T> work) {
        return work.apply(new TransactionViewController(
                transactions,
                crossReference,
                new DateUtilityJob(ParityHarness.FIXTURE_CHARSET),
                Clock.fixed(PINNED_INSTANT, ZoneOffset.UTC),
                ParityHarness.FIXTURE_CHARSET));
    }

    /** @return a {@code TRANSACT} repository whose backward browse holds one record keyed {@code ...50} */
    private static TransactionRepository masterHoldingOneRecord() {
        TransactionRepository repository = mock(TransactionRepository.class);
        TransactionRepository.Browse browse = mock(TransactionRepository.Browse.class);
        when(browse.readPrev()).thenReturn(TransactionRepository.ReadResult.found(
                TransactionRepository.CICS_FILE_NAME, recordKeyed("0000000000000050")));
        when(repository.startBrowse(TransactionRepository.BrowseDirection.BACKWARD))
                .thenReturn(browse);
        when(repository.write(any())).thenReturn(TransactionRepository.WriteResult
                .written(TransactionRepository.CICS_FILE_NAME));
        return repository;
    }

    /** @return a cross-reference whose alternate-index read resolves {@link #ACCOUNT_ID} to a card */
    private static CardXrefRepository crossReferenceResolvingTheAccount() {
        CardXrefRepository repository = mock(CardXrefRepository.class);
        CardXrefRecord record = new CardXrefRecord(CARD_NUMBER, 123_456_789, 11L);
        when(repository.readByAccountIdViaAltIndex(anyString()))
                .thenReturn(CardXrefRepository.ReadResult.found(
                        CardXrefRepository.ALTERNATE_INDEX_DD_NAME, record,
                        new String(record.encode(ParityHarness.FIXTURE_CHARSET),
                                ParityHarness.FIXTURE_CHARSET)));
        return repository;
    }

    /** @return a 350-byte {@code TRAN-RECORD} whose key is {@code tranId} and whose amount is 504.77 */
    private static TranRecord recordKeyed(String tranId) {
        TranRecord record = new TranRecord(ParityHarness.FIXTURE_CHARSET);
        record.moveTranId(tranId);
        record.moveTranTypeCd("01");
        record.moveTranCatCd(1);
        record.moveTranSource("POS TERM  ");
        record.moveTranDesc("PARITY DESCRIPTION");
        record.moveTranAmt(new BigDecimal("504.77"));
        record.moveTranMerchantId(123_456_789L);
        record.moveTranMerchantName("PARITY MERCHANT");
        record.moveTranMerchantCity("PARITY CITY");
        record.moveTranMerchantZip("0000012345");
        record.moveTranCardNum(CARD_NUMBER);
        record.moveTranOrigTs(VALID_DATE);
        record.moveTranProcTs(VALID_DATE);
        return record;
    }

    /** @return a re-entry request carrying the given {@code DFHAID} mnemonic's token */
    private static TransactionViewRequest reentryWith(String aidMnemonic) {
        TransactionViewRequest request = new TransactionViewRequest();
        request.setNavigationContext(NavigationContext.empty().withPgmReenter());
        request.setAid(aidTokenOf(aidMnemonic));
        return request;
    }

    /** @return a re-entry request with every field validly filled and the add confirmed */
    private static TransactionViewRequest confirmedAdd(String origDate, String procDate) {
        TransactionViewRequest request = reentryWith("DFHENTER");
        request.setActidin(ACCOUNT_ID);
        request.setTtypcd("01");
        request.setTcatcd("0001");
        request.setTrnsrc("POS TERM  ");
        request.setTdesc("Coffee and a newspaper");
        request.setTrnamt("+00000012.34");
        request.setTorigdt(origDate);
        request.setTprocdt(procDate);
        request.setMid("123456789");
        request.setMname("Kwik-E-Mart");
        request.setMcity("Springfield");
        request.setMzip("0000012345");
        request.setConfirm("Y");
        return request;
    }

    // =================================================================================================

    @Nested
    @DisplayName("Risk R-B - the class name says view and the program adds")
    class RiskRb {

        @Test
        @DisplayName("the source's own header says Add, and it browses, adds one and writes")
        void theCobolSourceAdds() throws IOException {
            String cobol = Files.readString(repositoryFile("app/cbl/COTRN02C.cbl"),
                    StandardCharsets.UTF_8);

            assertThat(cobol)
                    .as("the Function header is the primary evidence for risk R-B and the reason every "
                            + "expectation in this file describes an insert")
                    .contains("Function    : Add a new Transaction to TRANSACT file");
            assertThat(cobol)
                    .as("the four statements that make it an insert: position past the end, read the "
                            + "highest key, end the browse, write the new record")
                    .contains("EXEC CICS STARTBR")
                    .contains("EXEC CICS READPREV")
                    .contains("EXEC CICS ENDBR")
                    .contains("EXEC CICS WRITE");
            assertThat(cobol)
                    .as("the identifier is a high-water mark plus one, which is the whole reason the "
                            + "browse is backward")
                    .contains("MOVE HIGH-VALUES TO TRAN-ID")
                    .contains("ADD 1 TO WS-TRAN-ID-N");
            assertThat(cobol)
                    .as("this program neither rewrites nor deletes, so an expectation naming either "
                            + "would be describing a different program")
                    .doesNotContain("EXEC CICS REWRITE")
                    .doesNotContain("EXEC CICS DELETE");
        }

        @Test
        @DisplayName("README.md's own inventory records CT02 as Transaction Add, not View")
        void theProjectInventoryContradictsThePromptsMapping() throws IOException {
            String readme = Files.readString(repositoryFile("README.md"), StandardCharsets.UTF_8);

            assertThat(readme)
                    .as("README.md:213-231 is the independent corroboration of risk R-B; if this row ever "
                            + "changes, the resolution recorded in this class must be revisited")
                    .contains("| CT02 | COTRN02 | COTRN02C | Transaction Add     |");
            assertThat(readme)
                    .as("and the sibling really is the one that views")
                    .contains("| CT01 | COTRN01 | COTRN01C | Transaction View    |");
        }

        @Test
        @DisplayName("the confirmed-add path writes exactly one 350-byte record")
        void theConfirmedPathWrites() {
            TransactionRepository transactions = masterHoldingOneRecord();

            ProgramState state = withController(transactions, crossReferenceResolvingTheAccount(),
                    controller -> controller.mainPara(confirmedAdd(VALID_DATE, VALID_DATE)));

            assertThat(state.writtenRecords())
                    .as("app/cbl/COTRN02C.cbl:713 - one EXEC CICS WRITE, and the class name that says "
                            + "otherwise is honoured for its spelling only (rule R1)")
                    .hasSize(1)
                    .allSatisfy(image -> assertThat(image).hasSize(TranRecord.RECORD_LENGTH));
            verify(transactions).write(any());
            assertThat(state.tranRecord().tranId())
                    .as(":448-449 - the highest existing key was ...50, so the new record is ...51")
                    .isEqualTo("0000000000000051");
        }

        @Test
        @DisplayName("no rejected path writes anything at all")
        void aRejectedPathNeverWrites() {
            TransactionRepository transactions = masterHoldingOneRecord();

            withController(transactions, crossReferenceResolvingTheAccount(), controller -> {
                // The key guard, the eleven-arm empty cascade, the shape guards, the date guard, the
                // confirmation guard and the unnamed-key arm: six ways to be rejected, none of which
                // reaches :713.
                controller.mainPara(reentryWith("DFHENTER"));
                controller.mainPara(reentryWith("DFHPF3"));
                controller.mainPara(reentryWith("DFHPF4"));
                controller.mainPara(reentryWith("DFHPF12"));
                controller.mainPara(new TransactionViewRequest());

                TransactionViewRequest unconfirmed = confirmedAdd(VALID_DATE, VALID_DATE);
                unconfirmed.setConfirm(" ");
                assertThat(controller.mainPara(unconfirmed).message())
                        .as(":178-182 - a blank CONFIRM asks again rather than adding")
                        .startsWith(TransactionViewController.MSG_CONFIRM_TO_ADD);

                TransactionViewRequest badDate = confirmedAdd(REJECTED_DATE, VALID_DATE);
                assertThat(controller.mainPara(badDate).message())
                        .as(":404-409 - a date CSUTLDTC rejects stops the add")
                        .startsWith(TransactionViewController.MSG_ORIG_DATE_INVALID);
                return null;
            });

            verify(transactions, never()).write(any());
        }
    }

    @Nested
    @DisplayName("Practice B5 - ACCTDAT and CVACT01Y are declared, never used, and stay that way")
    class DeadDeclarations {

        @Test
        @DisplayName("the COBOL names ACCTDAT and copies CVACT01Y and references neither")
        void theSourceDeclaresThemAndUsesNeither() throws IOException {
            String cobol = Files.readString(repositoryFile("app/cbl/COTRN02C.cbl"),
                    StandardCharsets.UTF_8);

            assertThat(cobol)
                    .as("L40 declares the literal and L89 copies the record layout")
                    .contains("WS-ACCTDAT-FILE            PIC X(08) VALUE 'ACCTDAT '")
                    .contains("COPY CVACT01Y");
            assertThat(cobol)
                    .as("and no EXEC CICS command names WS-ACCTDAT-FILE, which is what makes the "
                            + "declaration dead rather than merely quiet")
                    .doesNotContain("DATASET   (WS-ACCTDAT-FILE)");
            assertThat(cobol)
                    .as("nor does any statement name the 01 group the copybook brings in, or any of the "
                            + "eleven fields of it that carry a distinctive name - including the "
                            + "misspelled ACCT-EXPIRAION-DATE, which is preserved as the copybook spells "
                            + "it wherever it IS modelled (implicit requirement I1)")
                    .doesNotContain("ACCOUNT-RECORD")
                    .doesNotContain("ACCT-ACTIVE-STATUS")
                    .doesNotContain("ACCT-CURR-BAL")
                    .doesNotContain("ACCT-CREDIT-LIMIT")
                    .doesNotContain("ACCT-EXPIRAION-DATE")
                    .doesNotContain("ACCT-GROUP-ID")
                    .doesNotContain("ACCT-OPEN-DATE");

            // ACCT-ID is the twelfth field and cannot be tested by absence, because it is a substring of
            // XREF-ACCT-ID (the cross-reference key, :580 and :585) and of WS-ACCT-ID-N (the program's own
            // PIC 9(11) carrier, :55). Counting is the precise form of the same claim: every occurrence
            // belongs to one of those two names, so none of them is a reference to the dead copybook's key.
            assertThat(occurrencesOf(cobol, "ACCT-ID"))
                    .as("every ACCT-ID in the source is part of XREF-ACCT-ID or WS-ACCT-ID-N; a bare one "
                            + "would be a reference to the dead COPY's key field")
                    .isEqualTo(occurrencesOf(cobol, "XREF-ACCT-ID")
                            + occurrencesOf(cobol, "WS-ACCT-ID"));
        }

        /**
         * @param text  the text to scan
         * @param token the token to count
         * @return how many times {@code token} occurs in {@code text}, counting overlaps as COBOL names
         *         cannot overlap
         */
        private int occurrencesOf(String text, String token) {
            int count = 0;
            int from = text.indexOf(token);
            while (from >= 0) {
                count++;
                from = text.indexOf(token, from + token.length());
            }
            return count;
        }

        @Test
        @DisplayName("the translation preserves both as constants and wires no account access")
        void theTranslationKeepsThemVisibleAndUnused() {
            assertThat(TransactionViewController.WS_ACCTDAT_FILE)
                    .as("the dead literal is sourced from the repository's own constant, so this file's "
                            + "only mention of the dataset is compile-checked and no name is retyped")
                    .isEqualTo(AccountRepository.CICS_FILE_NAME);
            assertThat(TransactionViewController.COPIED_ACCOUNT_RECORD_LENGTH)
                    .as("CVACT01Y declares a 300-byte ACCOUNT-RECORD, and recording its width keeps the "
                            + "dead COPY visible without modelling an account here")
                    .isEqualTo(AccountRecord.RECORD_LENGTH)
                    .isEqualTo(300);

            List<Class<?>> wired = List.of(TransactionRepository.class, CardXrefRepository.class,
                    DateUtilityJob.class, Clock.class, Charset.class);
            assertThat(TransactionViewController.class.getConstructors())
                    .as("no constructor takes an AccountRepository: the program performs no account "
                            + "access, so there is nowhere for one to be injected and nowhere for a "
                            + "well-meaning 'completion' of the migration to add a read (practice B5)")
                    .allSatisfy(constructor -> assertThat(List.of(constructor.getParameterTypes()))
                            .doesNotContain(AccountRepository.class)
                            .isSubsetOf(wired));
        }
    }

    @Nested
    @DisplayName("The widths every case is written against")
    class DeclaredWidths {

        @Test
        @DisplayName("TRAN-RECORD is 350 bytes and its FILLER is present and space-filled")
        void tranRecordIsThreeHundredAndFiftyBytesIncludingFiller() {
            TranRecord record = recordKeyed("0000000000000051");

            assertThat(TranRecord.RECORD_LENGTH)
                    .as("app/cpy/CVTRA05Y.cpy declares a 350-byte TRAN-RECORD, and app/cbl/COTRN02C.cbl:718 "
                            + "writes LENGTH OF TRAN-RECORD - so 350 is the width on the wire (gate G19)")
                    .isEqualTo(350);
            assertThat(record.displayImage())
                    .as("a serialised record is exactly its declared width, which is the check that fails "
                            + "immediately if a FILLER span were omitted (gate G21)")
                    .hasSize(TranRecord.RECORD_LENGTH);
            assertThat(TranRecord.sumOfDeclaredSpanLengths())
                    .as("every byte of the record belongs to a declared span; a gap would leave the sum "
                            + "short of the record length")
                    .isEqualTo(TranRecord.RECORD_LENGTH);
            assertThat(record.rawSpan(TranRecord.FILLER))
                    .as("CVTRA05Y's trailing FILLER PIC X(20) is emitted as spaces; omit it and the record "
                            + "is 330 bytes while every offset before it still looks right")
                    .isEqualTo(" ".repeat(TranRecord.FILLER_LENGTH));
        }

        @Test
        @DisplayName("the commarea is 160 bytes and the CT02 extension is a separate 58")
        void theExtensionIsNotFoldedIntoTheCommarea() {
            assertThat(NavigationContext.COMMAREA_LENGTH)
                    .as("COCOM01Y's CARDDEMO-COMMAREA is 160 bytes and is shared by all seventeen online "
                            + "programs, so a per-program extension must not be folded into it")
                    .isEqualTo(160);
            assertThat(TransactionViewRequest.Ct02Info.CT02_INFO_LENGTH)
                    .as("CDEMO-CT02-INFO is 16 + 16 + 8 + 1 + 1 + 16 = 58 bytes "
                            + "(app/cbl/COTRN02C.cbl:72-80)")
                    .isEqualTo(58)
                    .isEqualTo(TransactionViewResponse.Ct02Info.LENGTH);
            assertThat(ProgramState.PASSED_COMMAREA_LENGTH)
                    .as("the area the XCTL at :509 and the RETURN at :530 pass is the two together")
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH
                            + TransactionViewRequest.Ct02Info.CT02_INFO_LENGTH)
                    .isEqualTo(218);

            assertThat(baseCommareaFieldNames())
                    .as("the sixteen COCOM01Y fields and the six extension fields are disjoint, which is "
                            + "what lets a case declare them in one flat map")
                    .hasSize(16)
                    .doesNotContainAnyElementsOf(extensionFieldNames());
            assertThat(extensionFieldNames())
                    .containsExactly(TransactionViewRequest.Ct02Info.TRNID_FIRST_FIELD,
                            TransactionViewRequest.Ct02Info.TRNID_LAST_FIELD,
                            TransactionViewRequest.Ct02Info.PAGE_NUM_FIELD,
                            TransactionViewRequest.Ct02Info.NEXT_PAGE_FLG_FIELD,
                            TransactionViewRequest.Ct02Info.TRN_SEL_FLG_FIELD,
                            TransactionViewRequest.Ct02Info.TRN_SELECTED_FIELD);
        }

        @Test
        @DisplayName("WS-MESSAGE is 80 and ERRMSGO is 78, so :520 loses the last two bytes")
        void theMessageIsTruncatedByTwoOnItsWayToTheScreen() {
            assertThat(ParityCase.MessageChannel.WS_MESSAGE_80.fixedWidth())
                    .as("app/cbl/COTRN02C.cbl:37 declares WS-MESSAGE PIC X(80)")
                    .isEqualTo(TransactionViewController.WS_MESSAGE_LENGTH)
                    .isEqualTo(80);
            assertThat(ParityCase.MessageChannel.SCREEN_ERRMSG_78.fixedWidth())
                    .as("the symbolic map declares ERRMSGO PIC X(78)")
                    .isEqualTo(TransactionViewResponse.ERRMSGO_LENGTH)
                    .isEqualTo(78);

            // A message wide enough to actually lose its last two characters, which none of this program's
            // own twenty-six literals is - so the rule is proved rather than assumed.
            TransactionViewResponse response = new TransactionViewResponse();
            response.setErrmsgo("X".repeat(TransactionViewController.WS_MESSAGE_LENGTH));
            assertThat(response.getErrmsgo())
                    .as("MOVE PIC X(80) TO PIC X(78) keeps the leading 78 characters and discards the rest "
                            + "on the right - a PIC X receiver truncates on the right, a PIC 9 receiver on "
                            + "the left, and a plain Java assignment does neither")
                    .hasSize(TransactionViewResponse.ERRMSGO_LENGTH)
                    .isEqualTo("X".repeat(78));
        }

        @Test
        @DisplayName("the symbolic map projects exactly 21 payload fields and its cursor is metadata")
        void theScreenHasTwentyOnePayloadFields() {
            assertThat(ScreenField.values())
                    .as("app/bms/COTRN02.bms declares 21 DFHMDF fields and app/cpy-bms/COTRN02.CPY declares "
                            + "21 xxxI items; the payload is those and nothing else")
                    .hasSize(TransactionViewResponse.FIELD_COUNT)
                    .hasSize(21);
            assertThat(TransactionViewRequest.ScreenField.values())
                    .as("the two projections of one copybook must agree about how many fields it has")
                    .hasSameSizeAs(ScreenField.values());
            assertThat(TransactionViewRequest.ScreenField.ACTIDIN.lengthItem())
                    .as("the cursor is requested by moving -1 into a length item, and the length item is "
                            + "the field's label plus L - which is metadata, not payload (gate G9)")
                    .isEqualTo(ScreenField.ACTIDIN.label() + LENGTH_ITEM_SUFFIX)
                    .isEqualTo("ACTIDINL");
        }

        @Test
        @DisplayName("the cross-reference record is 50 bytes, of which the fixture supplies only 36")
        void theCardXrefFixtureIsShortByItsFiller() {
            assertThat(CardXrefRecord.RECORD_LENGTH)
                    .as("app/cpy/CVACT03Y.cpy declares 16 + 9 + 11 + FILLER X(14) = 50")
                    .isEqualTo(50);
            assertThat(ParityCase.Normalisation.CARDXREF_FILLER_PAD_36_TO_50.sourceWidth())
                    .as("app/data/ASCII/cardxref.txt measures 36 bytes a row, with the FILLER absent")
                    .isEqualTo(36);
            assertThat(ParityCase.Normalisation.CARDXREF_FILLER_PAD_36_TO_50.targetWidth())
                    .as("so a seeded row is right-padded to the copybook's width before anything reads it "
                            + "(gate G16)")
                    .isEqualTo(CardXrefRecord.RECORD_LENGTH);
            assertThat(ParityCase.Normalisation.CARDXREF_FILLER_PAD_36_TO_50.describes(CCXREF_DATASET))
                    .as("and the normalisation is declared for the binding key this program's cases seed")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Gate G29 - FUNCTION NUMVAL and NUMVAL-C accept and reject exactly as COBOL does")
    class NumericIntrinsicParity {

        @Test
        @DisplayName("NUMVAL accepts a signed integer with surrounding spaces and a trailing CR")
        void numvalAcceptsWhatTheIntrinsicAccepts() {
            assertThat(TransactionViewController.testNumval(ACCOUNT_ID))
                    .as("L204 converts ACTIDINI, which the IS NUMERIC guard at L197 has already forced to "
                            + "eleven digits")
                    .isEqualTo(TransactionViewController.NUMVAL_CONFORMS);
            assertThat(TransactionViewController.numval(ACCOUNT_ID))
                    .isEqualByComparingTo(new BigDecimal("11"));

            assertThat(TransactionViewController.testNumval("  12345    "))
                    .as("spaces are permitted between the elements of the argument")
                    .isEqualTo(TransactionViewController.NUMVAL_CONFORMS);
            assertThat(TransactionViewController.numval("  12345    "))
                    .isEqualByComparingTo(new BigDecimal("12345"));

            assertThat(TransactionViewController.numval("-000123"))
                    .as("a leading sign is part of the argument format")
                    .isEqualByComparingTo(new BigDecimal("-123"));
            assertThat(TransactionViewController.numval("12CR"))
                    .as("CR belongs to both intrinsics and means the value is negative")
                    .isEqualByComparingTo(new BigDecimal("-12"));
        }

        @Test
        @DisplayName("NUMVAL rejects a comma, a currency sign, an embedded space and a second point")
        void numvalRejectsWhatTheIntrinsicRejects() {
            // The seven malformed inputs gate G29 names, each with the one-based position of the first
            // character in error. A non-conforming argument converts to zero, which is why the verdict has
            // to be asserted alongside the value: zero alone cannot tell "00" from "ab".
            assertThat(TransactionViewController.testNumval("1,234"))
                    .as("a digit-grouping comma is a NUMVAL-C extension and is an error here, at the comma")
                    .isEqualTo(2);
            assertThat(TransactionViewController.testNumval("$12"))
                    .as("a currency sign is likewise NUMVAL-C only")
                    .isNotEqualTo(TransactionViewController.NUMVAL_CONFORMS);
            assertThat(TransactionViewController.testNumval("12 34"))
                    .as("an embedded space ends the digits, so the digit after it is in error")
                    .isEqualTo(4);
            assertThat(TransactionViewController.testNumval("1.2.3"))
                    .as("a second decimal point is in error at the point")
                    .isEqualTo(4);
            assertThat(TransactionViewController.testNumval("(12)"))
                    .as("a parenthesised negative is not an IBM NUMVAL argument at all")
                    .isNotEqualTo(TransactionViewController.NUMVAL_CONFORMS);
            assertThat(TransactionViewController.testNumval(""))
                    .as("an empty argument holds no digit, so the error is one past its end")
                    .isEqualTo(1);
            assertThat(TransactionViewController.testNumval(" ".repeat(11)))
                    .as("an all-spaces argument likewise holds no digit; this is the state the guard at "
                            + "L196 excludes before the conversion is ever reached")
                    .isEqualTo(12);

            for (String malformed : List.of("1,234", "$12", "12 34", "1.2.3", "(12)", "",
                    " ".repeat(11))) {
                assertThat(TransactionViewController.numval(malformed))
                        .as("a non-conforming argument converts to zero, and never to a guess")
                        .isEqualByComparingTo(BigDecimal.ZERO);
            }
        }

        @Test
        @DisplayName("NUMVAL-C additionally accepts a currency sign and grouping commas, at scale 2")
        void numvalCAcceptsTheCurrencyExtensions() {
            assertThat(TransactionViewController.testNumvalC("+00000012.34"))
                    .as("L383 and L456 convert TRNAMTI, which the positional guard at L340-343 has already "
                            + "forced into the shape [-+]dddddddd.dd")
                    .isEqualTo(TransactionViewController.NUMVAL_CONFORMS);
            assertThat(scaled(TransactionViewController.numvalC("+00000012.34")))
                    .as("stored into WS-TRAN-AMT-N PIC S9(9)V99 - scale exactly two, truncating, because "
                            + "ROUNDED appears zero times in all twenty-eight programs (rules R2 and R3)")
                    .isEqualTo(new BigDecimal("12.34"));
            assertThat(scaled(TransactionViewController.numvalC("-00000012.34")))
                    .isEqualTo(new BigDecimal("-12.34"));
            assertThat(scaled(TransactionViewController.numvalC("$1,234.56")))
                    .as("the currency sign and the grouping comma are the two things NUMVAL-C adds")
                    .isEqualTo(new BigDecimal("1234.56"));
            assertThat(scaled(TransactionViewController.numvalC("12.34DB")))
                    .as("DB, like CR, marks the value negative")
                    .isEqualTo(new BigDecimal("-12.34"));
            assertThat(scaled(TransactionViewController.numvalC("+99999999.99")))
                    .as("the widest amount the eight-digit mask at L385 can re-display")
                    .isEqualTo(new BigDecimal("99999999.99"));
        }

        @Test
        @DisplayName("NUMVAL-C rejects a parenthesised negative, an embedded space and a second point")
        void numvalCRejectsWhatTheIntrinsicRejects() {
            assertThat(TransactionViewController.testNumvalC("(12.34)"))
                    .as("parentheses are not part of the IBM NUMVAL-C argument format either")
                    .isNotEqualTo(TransactionViewController.NUMVAL_CONFORMS);
            assertThat(TransactionViewController.testNumvalC("12 .34"))
                    .isEqualTo(4);
            assertThat(TransactionViewController.testNumvalC("1.2.3"))
                    .isEqualTo(4);
            assertThat(TransactionViewController.testNumvalC(""))
                    .isEqualTo(1);
            assertThat(TransactionViewController.testNumvalC(" ".repeat(12)))
                    .isEqualTo(13);
        }

        @Test
        @DisplayName("the positional amount guard, not the conversion, is what the operator sees")
        void theShapeGuardRunsBeforeTheConversion() {
            assertThat(TransactionViewController.isMalformedAmount("+00000012.34")).isFalse();
            assertThat(TransactionViewController.isMalformedAmount("-00000012.34")).isFalse();
            assertThat(TransactionViewController.isMalformedAmount("100000012.34"))
                    .as("L340 - (1:1) must be '-' or '+', so a digit in the sign position is rejected")
                    .isTrue();
            assertThat(TransactionViewController.isMalformedAmount("+0000001234"))
                    .as("L342 - (10:1) must be '.', so an amount with no point is rejected")
                    .isTrue();
            assertThat(TransactionViewController.isMalformedDate(VALID_DATE)).isFalse();
            assertThat(TransactionViewController.isMalformedDate("2022/07/18"))
                    .as("L357 and L359 - the two separators must be hyphens")
                    .isTrue();
            assertThat(TransactionViewController.isMalformedDate(REJECTED_DATE))
                    .as("month 13 is numerically well formed, which is exactly why CSUTLDTC is called at "
                            + "all - the positional guard cannot see it")
                    .isFalse();
        }

        /**
         * Stores a converted value into {@code WS-TRAN-AMT-N PIC S9(9)V99} the one way this migration
         * stores a monetary value.
         *
         * @param value the value {@code FUNCTION NUMVAL-C} produced
         * @return the value at scale {@value CobolDecimal#MONETARY_SCALE}, truncated
         */
        private BigDecimal scaled(BigDecimal value) {
            assertThat(CobolDecimal.COBOL_ROUNDING)
                    .as("the only faithful mode, because ROUNDED never appears in the source (gate G24)")
                    .isEqualTo(RoundingMode.DOWN);
            assertThat(CobolDecimal.MONETARY_SCALE)
                    .as("CVTRA05Y declares TRAN-AMT PIC S9(09)V99 and COTRN02C declares WS-TRAN-AMT-N "
                            + "PIC S9(9)V99 - the same scale twice")
                    .isEqualTo(TranRecord.TRAN_AMT_SCALE)
                    .isEqualTo(2);
            return CobolDecimal.storeMonetary(value);
        }
    }

    @Nested
    @DisplayName("The CSUTLDTC acceptance rule - severity '0000', or message 2513 tolerated")
    class CsutldtcAcceptanceRule {

        /** The subprogram, used for real because it is deterministic (see {@link #execute}). */
        private final DateUtilityJob dateUtility = new DateUtilityJob(ParityHarness.FIXTURE_CHARSET);

        @Test
        @DisplayName("a valid date reports severity '0000' and 'Date is valid'")
        void aValidDateIsAcceptedOutright() {
            DateUtilityJob.DateValidationResult result =
                    dateUtility.validateDate(VALID_DATE, TransactionViewController.WS_DATE_FORMAT);

            assertThat(result.severityCode())
                    .as("app/cbl/COTRN02C.cbl:397 and :417 - IF CSUTLDTC-RESULT-SEV-CD = '0000' CONTINUE")
                    .isEqualTo(TransactionViewController.CSUTLDTC_SEVERITY_OK);
            assertThat(result.result())
                    .as("FC-INVALID-DATE is the token that means valid, and its 15-character text is "
                            + "pinned in full by CSUTLDTCParityTest")
                    .isEqualTo("Date is valid  ");
            assertThat(result.returnCode())
                    .as("MOVE WS-SEVERITY-N TO RETURN-CODE - zero for a valid date")
                    .isZero();
            assertThat(result.message())
                    .as("the 80-byte WS-MESSAGE image: 4 + 11 + 4 + 1 + 15 + 1 + 9 + 10 + 1 + 10 + 1 + 3")
                    .hasSize(DateUtilityJob.LS_RESULT_LENGTH)
                    .hasSize(80);
        }

        @Test
        @DisplayName("message 2513 is an error and both call sites tolerate it anyway")
        void theUnsupportedRangeErrorIsTolerated() {
            DateUtilityJob.DateValidationResult result =
                    dateUtility.validateDate(TOLERATED_DATE, TransactionViewController.WS_DATE_FORMAT);

            assertThat(result.severityCode())
                    .as("severity 3, so the '0000' arm is not taken")
                    .isNotEqualTo(TransactionViewController.CSUTLDTC_SEVERITY_OK);
            assertThat(result.messageNumber())
                    .as("app/cbl/CSUTLDTC.cbl:66 declares 88 FC-UNSUPP-RANGE VALUE X'000309D1...', whose "
                            + "second halfword X'09D1' is decimal 2513")
                    .isEqualTo(TransactionViewController.CSUTLDTC_TOLERATED_MESSAGE_NUMBER);
            assertThat(result.result()).isEqualTo("Unsupp. Range  ");
            assertThat(result.returnCode()).isEqualTo(3);

            // :400 and :420 - IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513' ... so 2513 falls through and the
            // screen proceeds. Turning this into a rejection would refuse dates the legacy program accepts.
            ProgramState state = withController(masterHoldingOneRecord(),
                    crossReferenceResolvingTheAccount(),
                    controller -> controller.mainPara(confirmedAdd(TOLERATED_DATE, TOLERATED_DATE)));

            assertThat(state.errFlagOn())
                    .as("a tolerated date raises no error flag")
                    .isFalse();
            assertThat(state.writtenRecords())
                    .as("and the add completes, which is the whole content of the tolerance")
                    .hasSize(1);
        }

        @Test
        @DisplayName("any other feedback token rejects, on each of the two call sites separately")
        void anyOtherTokenRejects() {
            DateUtilityJob.DateValidationResult result =
                    dateUtility.validateDate(REJECTED_DATE, TransactionViewController.WS_DATE_FORMAT);

            assertThat(result.messageNumber())
                    .as("FC-INVALID-MONTH is X'000309D5', whose second halfword is decimal 2517")
                    .isEqualTo("2517");
            assertThat(result.result()).isEqualTo("Invalid month  ");

            withController(masterHoldingOneRecord(), crossReferenceResolvingTheAccount(), controller -> {
                ProgramState origin = controller.mainPara(confirmedAdd(REJECTED_DATE, VALID_DATE));
                assertThat(origin.message())
                        .as(":405-406 - the first call site's own literal")
                        .startsWith(TransactionViewController.MSG_ORIG_DATE_INVALID);
                assertThat(origin.cursorRequestedOn(ScreenField.TORIGDT))
                        .as(":408 - MOVE -1 TO TORIGDTL")
                        .isTrue();

                ProgramState processing = controller.mainPara(confirmedAdd(VALID_DATE, REJECTED_DATE));
                assertThat(processing.message())
                        .as(":425-426 - the second call site's own literal, which is a different string")
                        .startsWith(TransactionViewController.MSG_PROC_DATE_INVALID);
                assertThat(processing.cursorRequestedOn(ScreenField.TPROCDT))
                        .as(":428 - MOVE -1 TO TPROCDTL")
                        .isTrue();
                return null;
            });
        }
    }

    @Nested
    @DisplayName("Gate G30 - EVALUATE EIBAID keeps the source's order with WHEN OTHER last")
    class AidDispatch {

        @Test
        @DisplayName("the four named arms are the four the source names, and nothing else matches")
        void onlyTheFourNamedKeysMatchAnArm() {
            assertThat(PfKeyResolver.isEnter(CicsAid.DFHENTER)).isTrue();
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHPF3)).isTrue();
            assertThat(PfKeyResolver.isPf4(CicsAid.DFHPF4)).isTrue();
            assertThat(PfKeyResolver.isPf5(CicsAid.DFHPF5)).isTrue();

            for (byte aid : new byte[] {CicsAid.DFHNULL, CicsAid.DFHCLEAR, CicsAid.DFHPA1,
                    CicsAid.DFHPF1, CicsAid.DFHPF7, CicsAid.DFHPF12}) {
                assertThat(PfKeyResolver.isEnter(aid) || PfKeyResolver.isPf3(aid)
                        || PfKeyResolver.isPf4(aid) || PfKeyResolver.isPf5(aid))
                        .as("EIBAID 0x%02X must fall to WHEN OTHER at :148. COTRN02C tests EIBAID inline "
                                + "rather than through CSSTRPFY, and the shared resolver has to reproduce "
                                + "those inline tests as identical boolean outcomes", aid)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("PF3 with no caller falls back to the main menu, and with one echoes it")
        void pf3ResolvesItsTargetFromTheCommarea() {
            withController(masterHoldingOneRecord(), crossReferenceResolvingTheAccount(), controller -> {
                ProgramState blankCaller = controller.mainPara(reentryWith("DFHPF3"));
                assertThat(blankCaller.response().getNextProgram())
                        .as(":137-138 - a blank CDEMO-FROM-PROGRAM defaults the target to COMEN01C")
                        .isEqualTo(TransactionViewController.MAIN_MENU_PROGRAM);
                assertThat(blankCaller.transferred())
                        .as("the PF3 arm reaches the XCTL at :508-511 and never the RETURN at :530")
                        .isTrue();
                assertThat(blankCaller.screenSent())
                        .as("no map is sent before transferring - the target paints its own")
                        .isFalse();
                assertThat(namedOrNone(blankCaller.response().getNextMapset()))
                        .as("and the mapset is blanked rather than left naming the screen being left")
                        .isNull();

                TransactionViewRequest fromMenu = reentryWith("DFHPF3");
                fromMenu.setNavigationContext(NavigationContext.empty()
                        .withPgmReenter()
                        .withFromProgram(TransactionViewController.MAIN_MENU_PROGRAM));
                assertThat(controller.mainPara(fromMenu).response().getNextProgram())
                        .as(":140-141 - a named caller is echoed back as the target")
                        .isEqualTo(TransactionViewController.MAIN_MENU_PROGRAM);

                ProgramState coldStart = controller.mainPara(new TransactionViewRequest());
                assertThat(coldStart.response().getNextProgram())
                        .as(":115-116 - no commarea, so the program abandons the transaction for signon")
                        .isEqualTo(TransactionViewController.SIGN_ON_PROGRAM);
                assertThat(coldStart.transferred()).isTrue();
                return null;
            });
        }

        @Test
        @DisplayName("an unnamed key takes WHEN OTHER and reports the invalid-key message")
        void whenOtherReportsTheInvalidKeyMessage() {
            withController(masterHoldingOneRecord(), crossReferenceResolvingTheAccount(), controller -> {
                ProgramState state = controller.mainPara(reentryWith("DFHPF12"));

                assertThat(state.errFlagOn())
                        .as(":149 - MOVE 'Y' TO WS-ERR-FLG")
                        .isTrue();
                assertThat(state.message())
                        .as(":150 - CCDA-MSG-INVALID-KEY moved into PIC X(80)")
                        .hasSize(TransactionViewController.WS_MESSAGE_LENGTH)
                        .startsWith("Invalid key pressed. Please see below...");
                assertThat(state.returned())
                        .as(":151 sends the screen, and the send is terminal at :530")
                        .isTrue();
                assertThat(state.response().getNextProgram())
                        .as("EXEC CICS RETURN TRANSID('CT02'), and the CSD binds CT02 to this program")
                        .isEqualTo(TransactionViewController.PROGRAM_NAME);
                assertThat(state.response().getNextMapset())
                        .as("the screen is named on a path that sends one")
                        .isEqualTo(TransactionViewResponse.MAPSET_NAME);
                return null;
            });
        }

        @Test
        @DisplayName("PF4 clears every input field and puts the cursor back on the account field")
        void pf4ClearsTheForm() {
            withController(masterHoldingOneRecord(), crossReferenceResolvingTheAccount(), controller -> {
                TransactionViewRequest filled = confirmedAdd(VALID_DATE, VALID_DATE);
                filled.setAid(aidTokenOf("DFHPF4"));

                ProgramState state = controller.mainPara(filled);

                assertThat(state.errFlagOn())
                        .as(":754-757 - CLEAR-CURRENT-SCREEN raises no error flag; it is not a rejection")
                        .isFalse();
                assertThat(state.message())
                        .as(":779 - WS-MESSAGE is one of the fifteen receivers INITIALIZE-ALL-FIELDS blanks")
                        .isEqualTo(" ".repeat(TransactionViewController.WS_MESSAGE_LENGTH));
                assertThat(state.actidinI()).isBlank();
                assertThat(state.confirmI()).isBlank();
                assertThat(state.cursorRequestedOn(ScreenField.ACTIDIN))
                        .as(":763 - MOVE -1 TO ACTIDINL")
                        .isTrue();
                assertThat(state.writtenRecords())
                        .as("clearing the form is not adding a transaction")
                        .isEmpty();
                return null;
            });
        }
    }

    @Nested
    @DisplayName("Gate G38 - this screen highlights in neither state; gate G37 - it keeps no session")
    class HighlightingAndStatelessness {

        @Test
        @DisplayName("the CSSETATY decision is 'touch nothing' on both ENTER and REENTER")
        void neverHighlightsInEitherState() {
            for (boolean reenter : new boolean[] {false, true}) {
                FieldHighlight decision = FieldAttributeSetter.resolveFromFlags(false, false, reenter);

                assertThat(decision.untouched())
                        .as("COTRN02C does not copy CSSETATY - its copybook list at :71-93 does not name "
                                + "it - and declares no FLG-<field>-NOT-OK pair, so with reenter=%s the "
                                + "resolver writes no byte. Gate G38 asks that highlighting apply only in "
                                + "REENTER; this program satisfies it by applying none in either state",
                                reenter)
                        .isTrue();
                assertThat(decision.colourItemAssigned()).isFalse();
                assertThat(decision.outputItemAssigned()).isFalse();
            }
        }

        @Test
        @DisplayName("no rejecting path ever colours a field, and the one colour it sets is DFHGREEN")
        void theOnlyAttributeAssignmentIsTheSuccessLine() {
            withController(masterHoldingOneRecord(), crossReferenceResolvingTheAccount(), controller -> {
                for (TransactionViewRequest rejected : List.of(reentryWith("DFHENTER"),
                        reentryWith("DFHPF12"), confirmedAddWithout(ScreenField.TDESC))) {
                    ProgramState state = controller.mainPara(rejected);
                    assertThat(state.response().getMetadata(ScreenField.ERRMSG).getColour())
                            .as("no rejecting arm of this program moves an attribute byte, so ERRMSGC "
                                    + "holds the map's default colour 0x%02X", BmsAttributes.DFHDFCOL)
                            .isEqualTo(BmsAttributes.DFHDFCOL);
                    assertThat(state.response().getMetadata(ScreenField.ACTIDIN).getColour())
                            .as("and DFHRED (0x%02X) never reaches a field, because CSSETATY would write "
                                    + "its asterisk into storage the operator's own value occupies",
                                    BmsAttributes.DFHRED)
                            .isNotEqualTo(BmsAttributes.DFHRED);
                }

                ProgramState added = controller.mainPara(confirmedAdd(VALID_DATE, VALID_DATE));
                assertThat(added.response().getMetadata(ScreenField.ERRMSG).getColour())
                        .as(":727 - MOVE DFHGREEN TO ERRMSGC OF COTRN2AO, the program's only attribute "
                                + "assignment anywhere, reproduced from IBM CICS documentation because "
                                + "DFHBMSCA is absent from this repository (risk R-D)")
                        .isEqualTo(BmsAttributes.DFHGREEN);
                assertThat(BmsAttributes.colourMnemonic(BmsAttributes.DFHGREEN))
                        .as("and a case pins it by mnemonic rather than by byte")
                        .isEqualTo("DFHGREEN");
                return null;
            });
        }

        @Test
        @DisplayName("two runs of one controller cannot see each other - WORKING-STORAGE is per task")
        void theControllerKeepsNoStateBetweenRuns() {
            withController(masterHoldingOneRecord(), crossReferenceResolvingTheAccount(), controller -> {
                ProgramState first = controller.mainPara(confirmedAdd(VALID_DATE, VALID_DATE));
                // A second run with an empty form. If any WORKING-STORAGE item had become a field on the
                // controller, the first run's amount, merchant and card number would still be here.
                ProgramState second = controller.mainPara(reentryWith("DFHENTER"));

                assertThat(first.writtenRecords()).hasSize(1);
                assertThat(second.writtenRecords())
                        .as("the second run was rejected at :224-229 and never reached the write")
                        .isEmpty();
                assertThat(second.message())
                        .as(":225-227 - Account or Card Number must be entered...")
                        .startsWith(TransactionViewController.MSG_KEY_REQUIRED);
                assertThat(second.actidinI())
                        .as("a shared field would show the first run's normalised account id here")
                        .isNotEqualTo(ACCOUNT_ID);
                assertThat(second.response())
                        .as("each run builds its own response, so the two are distinct objects")
                        .isNotSameAs(first.response());
                return null;
            });
        }

        /**
         * A confirmed add with one field blanked, so exactly one arm of the eleven-arm empty cascade at
         * {@code :251-320} is reached.
         *
         * @param blanked the field to leave empty
         * @return the request
         */
        private TransactionViewRequest confirmedAddWithout(ScreenField blanked) {
            TransactionViewRequest request = confirmedAdd(VALID_DATE, VALID_DATE);
            request.setPayloadValue(TransactionViewRequest.ScreenField.valueOf(blanked.name()),
                    TransactionViewRequest.spaces(blanked.width()));
            return request;
        }
    }

    @Nested
    @DisplayName("Gate G47 - every browse and write outcome the source names is reachable")
    class BrowseAndWriteOutcomes {

        @Test
        @DisplayName("an empty master reports ENDFILE, which zeros TRAN-ID and yields identifier 1")
        void anEmptyMasterIsLoadable() {
            TransactionRepository transactions = mock(TransactionRepository.class);
            TransactionRepository.Browse browse = mock(TransactionRepository.Browse.class);
            when(browse.readPrev()).thenReturn(TransactionRepository.ReadResult
                    .endOfFile(TransactionRepository.CICS_FILE_NAME));
            when(transactions.startBrowse(TransactionRepository.BrowseDirection.BACKWARD))
                    .thenReturn(browse);
            when(transactions.write(any())).thenReturn(TransactionRepository.WriteResult
                    .written(TransactionRepository.CICS_FILE_NAME));

            ProgramState state = withController(transactions, crossReferenceResolvingTheAccount(),
                    controller -> controller.mainPara(confirmedAdd(VALID_DATE, VALID_DATE)));

            assertThat(state.errFlagOn())
                    .as(":688-689 - WHEN DFHRESP(ENDFILE) MOVE ZEROS TO TRAN-ID raises no error flag, and "
                            + "getting that wrong is the difference between an empty master being loadable "
                            + "and being permanently unloadable")
                    .isFalse();
            assertThat(state.tranRecord().tranId())
                    .as(":448-449 - zero plus one, at the sixteen-digit width of TRAN-ID")
                    .isEqualTo("0000000000000001");
            assertThat(state.writtenRecords()).hasSize(1);
        }

        @Test
        @DisplayName("a refused READPREV displays RESP and REAS, rejects, and never reaches the write")
        void theReadprevWhenOtherArmDisplaysAndRejects() {
            TransactionRepository transactions = mock(TransactionRepository.class);
            TransactionRepository.Browse browse = mock(TransactionRepository.Browse.class);
            when(browse.readPrev()).thenReturn(TransactionRepository.ReadResult
                    .other(TransactionRepository.CICS_FILE_NAME, UNEXPECTED_STATUS));
            when(transactions.startBrowse(TransactionRepository.BrowseDirection.BACKWARD))
                    .thenReturn(browse);

            ProgramState state = withController(transactions, crossReferenceResolvingTheAccount(),
                    controller -> controller.mainPara(confirmedAdd(VALID_DATE, VALID_DATE)));

            assertThat(state.displayLines())
                    .as(":691 - DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD, the same nine-character "
                            + "rendering the write's own WHEN OTHER arm uses")
                    .hasSize(1);
            assertThat(state.message())
                    .as(":692-695 - the literal is the one STARTBR's WHEN OTHER arm also carries. READPREV "
                            + "declares no DFHRESP(NOTFND) arm of its own, so a not-found lands here too")
                    .startsWith(TransactionViewController.MSG_TRANSACTION_LOOKUP_FAILED);
            assertThat(state.cursorRequestedOn(ScreenField.ACTIDIN))
                    .as(":696 - MOVE -1 TO ACTIDINL OF COTRN2AI")
                    .isTrue();
            assertThat(state.errFlagOn())
                    .as(":693 - MOVE 'Y' TO WS-ERR-FLG, unlike the ENDFILE arm above which raises none")
                    .isTrue();
            assertThat(state.writtenRecords())
                    .as(":697 PERFORM SEND-TRNADD-SCREEN, whose EXEC CICS RETURN at :530 ends the task, so "
                            + "ADD-TRANSACTION never reaches its WRITE at :713")
                    .isEmpty();
            verify(transactions, never()).write(any());
        }

        @Test
        @DisplayName("the two unreachable STARTBR arms are still rendered, with their own literals")
        void bothRejectingPositioningArmsExist() {
            withController(masterHoldingOneRecord(), crossReferenceResolvingTheAccount(), controller -> {
                for (Map.Entry<FileStatus.Outcome, String> arm : Map.of(
                        FileStatus.Outcome.NOT_FOUND,
                        TransactionViewController.MSG_TRANSACTION_ID_NOT_FOUND,
                        FileStatus.Outcome.OTHER,
                        TransactionViewController.MSG_TRANSACTION_LOOKUP_FAILED).entrySet()) {
                    ProgramState state = new ProgramState(new FixedWidthCodec(
                            ParityHarness.FIXTURE_CHARSET));

                    controller.startbrOutcome(state, arm.getKey());

                    assertThat(state.message())
                            .as(":655-667 - positioning reports NORMAL through the repository, so these two "
                                    + "arms are unreachable from a parity case and are exercised here "
                                    + "instead. Deleting either would lose a literal the source carries")
                            .startsWith(arm.getValue());
                    assertThat(state.cursorRequestedOn(ScreenField.ACTIDIN))
                            .as("both place the cursor on ACTIDINL - the account field, because the map has "
                                    + "no transaction field to place it on")
                            .isTrue();
                }
                return null;
            });
        }

        @Test
        @DisplayName("a refused write displays RESP and REAS and reports 'Unable to Add Transaction...'")
        void theWriteWhenOtherArmDisplaysAndRejects() {
            TransactionRepository transactions = masterHoldingOneRecord();
            when(transactions.write(any())).thenReturn(TransactionRepository.WriteResult
                    .other(TransactionRepository.CICS_FILE_NAME, UNEXPECTED_STATUS));

            ProgramState state = withController(transactions, crossReferenceResolvingTheAccount(),
                    controller -> controller.mainPara(confirmedAdd(VALID_DATE, VALID_DATE)));

            assertThat(state.displayLines())
                    .as(":743 - DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD, both PIC S9(09) COMP so "
                            + "both render as nine characters")
                    .hasSize(1);
            assertThat(state.displayLines().get(0))
                    .startsWith(TransactionViewController.DISPLAY_RESP_PREFIX)
                    .contains(TransactionViewController.DISPLAY_REAS_PREFIX)
                    .hasSize(TransactionViewController.DISPLAY_RESP_PREFIX.length()
                            + TransactionViewController.DISPLAY_REAS_PREFIX.length()
                            + 2 * TransactionViewController.WS_RESP_CD_DIGITS);
            assertThat(state.message())
                    .as(":744-748")
                    .startsWith(TransactionViewController.MSG_UNABLE_TO_ADD);
            assertThat(state.writtenRecords())
                    .as("the record was handed over and refused, so it is still an observation - a "
                            + "fingerprint taken from the dataset instead would report this run as having "
                            + "written nothing")
                    .hasSize(1);
        }

        @Test
        @DisplayName("a duplicate write reports 'Tran ID already exist...' and displays nothing")
        void theWriteDuplicateArmRejectsWithoutDisplaying() {
            TransactionRepository transactions = masterHoldingOneRecord();
            when(transactions.write(any())).thenReturn(TransactionRepository.WriteResult
                    .duplicate(TransactionRepository.CICS_FILE_NAME));

            ProgramState state = withController(transactions, crossReferenceResolvingTheAccount(),
                    controller -> controller.mainPara(confirmedAdd(VALID_DATE, VALID_DATE)));

            assertThat(state.message())
                    .as(":735-741 - DUPKEY and DUPREC are two WHEN clauses over one action")
                    .startsWith(TransactionViewController.MSG_TRAN_ID_ALREADY_EXISTS);
            assertThat(state.displayLines())
                    .as("that arm carries no DISPLAY, unlike the WHEN OTHER arm below it")
                    .isEmpty();
            assertThat(state.cursorRequestedOn(ScreenField.ACTIDIN)).isTrue();
        }

        @Test
        @DisplayName("an incoherent forced outcome is refused rather than quietly mapped")
        void anIncoherentForcedOutcomeIsRefused() {
            assertThatThrownBy(() -> ParityHarness.usAscii().run(
                    syntheticConfirmedAdd(ParityCase.RepositoryOperation.WRITE,
                            FileStatus.Outcome.OK),
                    UNIT_KIND, COTRN02CParityTest::execute))
                    .as("a case that forced a successful write would suppress the duplicate check and "
                            + "leave its own final-state expectation describing a row that was never "
                            + "inserted - a false pass with a plausible-looking case file behind it")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("forced the OK outcome for the TRANSACT write");

            assertThatThrownBy(() -> ParityHarness.usAscii().run(
                    syntheticConfirmedAdd(ParityCase.RepositoryOperation.READ,
                            FileStatus.Outcome.DUPLICATE),
                    UNIT_KIND, COTRN02CParityTest::execute))
                    .as("and a case that forced a cross-reference read to carry a record would have to "
                            + "invent the 50 bytes of it, whose card number would then reach "
                            + "TRAN-CARD-NUM")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("forced the DUPLICATE outcome for the cross-reference read");
        }

        @Test
        @DisplayName("a forced outcome nothing asks for fails the run rather than passing quietly")
        void anUnconsumedForcedOutcomeFailsTheRun() {
            // REWRITE is an operation this program does not perform at all - the source has no
            // EXEC CICS REWRITE anywhere - so nothing can consume it, which is exactly the shape of a
            // case whose description claims an arm the run never reached.
            assertThatThrownBy(() -> ParityHarness.usAscii().run(
                    syntheticConfirmedAdd(ParityCase.RepositoryOperation.REWRITE,
                            FileStatus.Outcome.OTHER),
                    UNIT_KIND, COTRN02CParityTest::execute))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("that nothing asked for during the run");
        }

        /**
         * A synthetic case that drives a complete, valid, confirmed add and forces one outcome.
         *
         * <p>Built in code rather than loaded from a resource, because it exists to reach the adapter's own
         * refusals - and a refusal is by definition something no shippable case file can express, since a
         * case that provokes one can never report a diff count of zero.
         *
         * @param operation the operation to force
         * @param outcome   the outcome to force
         * @return the case
         */
        private ParityCase syntheticConfirmedAdd(ParityCase.RepositoryOperation operation,
                                                 FileStatus.Outcome outcome) {
            Map<String, ParityCase.DatasetInput> inputs = Map.of(CCXREF_DATASET,
                    ParityCase.DatasetInput.ofRows(List.of(
                            CARD_NUMBER + "123456789" + ACCOUNT_ID)));
            return new ParityCase(PROGRAM, ParityHarness.caseId(1),
                    "A synthetic case used only to reach the adapter's refusal of an incoherent or "
                            + "unconsumed forced outcome; it is never loaded from a resource and never "
                            + "judged, because a case that provokes a refusal can never report zero.",
                    UNIT_KIND, inputs, Map.of(),
                    new ParityCase.ScreenRequest(ProgramState.PASSED_COMMAREA_LENGTH, "DFHENTER",
                            PINNED_INSTANT.atZone(ZoneOffset.UTC).toLocalDateTime().toString(),
                            ParityHarness.FIXTURE_CHARSET.name(),
                            // CDEMO-PGM-CONTEXT at '1' is the re-enter state, which is the only state that
                            // reads the map at all - a first entry paints the screen and stops.
                            Map.of(NavigationContext.PGM_CONTEXT_FIELD,
                                    String.valueOf(NavigationContext.PGM_CONTEXT_REENTER)),
                            receivedFields(),
                            Map.of(operation, new ParityCase.ForcedOutcome(outcome, null, null))),
                    new ParityCase.ExpectedResponse(TransactionViewController.PROGRAM_NAME,
                            TransactionViewResponse.MAPSET_NAME, TransactionViewResponse.MAP_NAME,
                            Map.of(), List.of(), null, ParityCase.Termination.RETURN_TRANSID),
                    List.of(), List.of(), 0, List.of(),
                    List.of(new ParityCase.DatasetNormalisation(CCXREF_DATASET,
                            ParityCase.Normalisation.CARDXREF_FILLER_PAD_36_TO_50)));
        }

        /**
         * The fourteen {@code xxxI} items a valid confirmed add carries, keyed as a case file keys them.
         *
         * @return the received map
         */
        private Map<String, String> receivedFields() {
            Map<String, String> received = new LinkedHashMap<>();
            received.put(TransactionViewRequest.ScreenField.ACTIDIN.inputItem(), ACCOUNT_ID);
            received.put(TransactionViewRequest.ScreenField.TTYPCD.inputItem(), "01");
            received.put(TransactionViewRequest.ScreenField.TCATCD.inputItem(), "0001");
            received.put(TransactionViewRequest.ScreenField.TRNSRC.inputItem(), "POS TERM  ");
            received.put(TransactionViewRequest.ScreenField.TDESC.inputItem(),
                    "Coffee and a newspaper");
            received.put(TransactionViewRequest.ScreenField.TRNAMT.inputItem(), "+00000012.34");
            received.put(TransactionViewRequest.ScreenField.TORIGDT.inputItem(), VALID_DATE);
            received.put(TransactionViewRequest.ScreenField.TPROCDT.inputItem(), VALID_DATE);
            received.put(TransactionViewRequest.ScreenField.MID.inputItem(), "123456789");
            received.put(TransactionViewRequest.ScreenField.MNAME.inputItem(), "Kwik-E-Mart");
            received.put(TransactionViewRequest.ScreenField.MCITY.inputItem(), "Springfield");
            received.put(TransactionViewRequest.ScreenField.MZIP.inputItem(), "0000012345");
            received.put(TransactionViewRequest.ScreenField.CONFIRM.inputItem(), "Y");
            return received;
        }
    }


    /**
     * Resolves a repository-relative path from wherever the suite was launched.
     *
     * <p>Reference sources are read to <em>assert</em> facts about them and never written: the trees under
     * {@code app/cbl}, {@code app/cpy}, {@code app/cpy-bms}, {@code app/bms}, {@code app/jcl},
     * {@code app/proc}, {@code app/csd}, {@code app/ctl}, {@code app/catlg} and {@code app/data} are the
     * only oracle this migration has (practice B3).
     *
     * @param relativePath the path relative to the repository root
     * @return the resolved path
     * @throws IllegalStateException if it cannot be found at or above the working directory
     */
    private static Path repositoryFile(String relativePath) {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path resolved = candidate.resolve(relativePath);
            if (Files.exists(resolved)) {
                return resolved;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not find " + relativePath + " at or above "
                + Path.of("").toAbsolutePath() + ". The parity assertions read the COBOL source and "
                + "README.md as evidence for risk R-B, so the suite must run inside the repository.");
    }
}
