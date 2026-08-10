package com.vsergeychik.carddemo.parity;

import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.transaction.TransactionMenuController;
import com.vsergeychik.carddemo.transaction.TransactionMenuController.WorkArea;
import com.vsergeychik.carddemo.transaction.TransactionRepository;
import com.vsergeychik.carddemo.transaction.TransactionRepository.Browse;
import com.vsergeychik.carddemo.transaction.TransactionRepository.BrowseDirection;
import com.vsergeychik.carddemo.transaction.TransactionRepository.ReadResult;
import com.vsergeychik.carddemo.transaction.dto.TransactionListRequest;
import com.vsergeychik.carddemo.transaction.dto.TransactionListRequest.PaginationCursor;
import com.vsergeychik.carddemo.transaction.dto.TransactionListResponse;
import com.vsergeychik.carddemo.transaction.dto.TransactionListResponse.TransactionListCursor;
import com.vsergeychik.carddemo.transaction.model.TranRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The twenty-case parity suite for {@code COTRN00C}, the CICS online program behind transaction
 * {@code CT00}, projected onto {@code GET /api/transactions}.
 *
 * <h2>Where the expected values come from, and where they do not</h2>
 * <p><strong>This baseline is statically derived. It was never captured from a running COBOL
 * program.</strong> Executing the twenty-eight legacy programs is impossible in this environment -
 * eight blockers are individually verified, from a COBOL compiler whose indexed-file handler is
 * disabled through to the absence of any CICS emulator and of the three IBM-supplied copybooks
 * {@code DFHAID}, {@code DFHBMSCA} and {@code DFHATTR}. Every expectation in
 * {@code src/test/resources/parity/COTRN00C/case01..case20.json} was therefore derived by reading
 * {@code app/cbl/COTRN00C.cbl} paragraph by paragraph and cross-checking four authorities: the
 * copybook byte layouts in {@code app/cpy}, the symbolic-map widths in
 * {@code app/cpy-bms/COTRN00.CPY}, the {@code DFHMDF} field definitions in
 * {@code app/bms/COTRN00.bms}, and the transaction-to-program binding in
 * {@code app/csd/CARDDEMO.CSD}. Each case file names the source lines it pins, so a reviewer can
 * check the derivation against the oracle rather than having to trust it. Every substantive part of
 * the gate survives the substitution - twenty cases, field-by-field diffing, a diff count that must
 * be zero - and only the provenance of the numbers differs. This is recorded as an accepted risk and
 * is stated here rather than left implicit.
 *
 * <h2>The class name says Menu; the program lists transactions</h2>
 * <p>{@code TransactionMenuController} is the name this migration is required to use, and the name
 * is honoured verbatim. It does not describe the behaviour: {@code COTRN00C}'s own header reads
 * {@code Function : List Transactions from TRANSACT file}, and the program paints a ten-row scrolling
 * <em>list</em> with PF7/PF8 paging and a per-row selection cell. It is not a menu, it holds no menu
 * option table, and it copies neither {@code COMEN02Y} nor {@code COADM02Y}. The divergence is
 * documented rather than acted on: a mandated name never licenses adding, removing or altering
 * logic, so every assertion below is taken from the paired source and none from the name.
 *
 * <h2>Page size ten is behaviour, not configuration</h2>
 * <p>The ten comes from two loop bounds in the source - {@code UNTIL WS-IDX > 10} at {@code :290} and
 * {@code :344}, and {@code UNTIL WS-IDX >= 11} at {@code :297} - and from the ten-armed
 * {@code EVALUATE WS-IDX} at {@code :390-445}. It is not a property, not a request parameter and not
 * a tunable: changing it would change which records a given page shows and would therefore break
 * parity outright. {@link PageSizeIsBehaviour} asserts the literal ten in both halves of the DTO
 * pair and asserts that no eleventh row exists to page into, so a later attempt to externalise the
 * value fails here first.
 *
 * <h2>Why the controller is invoked as a plain object, and why that is not a G51 violation</h2>
 * <p>The unit under test is {@link TransactionMenuController} itself, constructed through its
 * canonical constructor as an ordinary Java object and called through
 * {@link TransactionMenuController#listTransactions(TransactionListRequest, byte, WorkArea)}. There
 * is <strong>no</strong> {@code MockMvc}, no {@code TestRestTemplate}, no {@code WebTestClient}, no
 * Spring context and no {@code JobLauncher} anywhere in this file. That looks unusual for a
 * {@code @RestController} and it is deliberate, for two reasons that a reviewer should not have to
 * reconstruct:
 * <ul>
 *   <li><strong>The rule is that no HTTP layer may sit between the assertion and the code.</strong>
 *       Direct construction has none - it is the strictest possible reading of that rule, not an
 *       exception to it. Putting a servlet container in the path would add a JSON round trip whose
 *       failures are indistinguishable from the arithmetic failures this suite exists to catch.</li>
 *   <li><strong>The {@code transaction} package has no service class to invoke instead.</strong> All
 *       four of its online programs keep their decision logic in the controller, so the controller
 *       <em>is</em> the decision layer here. The alternative - invoking a service - does not exist,
 *       and inventing one would be a redesign.</li>
 * </ul>
 * <p>{@code TRANSACT} is reached through a stubbed {@link TransactionRepository}: never a
 * {@code JdbcTemplate}, never a live dataset, and never by dataset name. No case file and no line of
 * this class contains a mainframe dataset name, which {@link ScopeAndProvenance} asserts directly.
 *
 * <p>{@link TransactionListRequest} and {@link TransactionListResponse} are imported because they
 * <em>are</em> the controller's signature - {@code listTransactions} takes the one and returns the
 * other - so reaching the unit under test at all requires both. They are the DTO pair projected
 * field-for-field from {@code app/cpy-bms/COTRN00.CPY}, and the payload names and lengths this suite
 * asserts are read from them rather than restated, so a drift between the symbolic map and the DTO
 * fails here instead of being masked by a second copy of the same number.
 *
 * <h2>What the diff judges, and what is asserted alongside it</h2>
 * <p>The differ compares the response - next program, next mapset, next map, cursor field,
 * termination, all twenty-two commarea fields, the ordered send list - the {@code RETURN-CODE}, and
 * the two message channels. Two aspects of the observation need explaining:
 * <ul>
 *   <li><strong>Each send carries the six fields {@code POPULATE-HEADER-INFO} moves, and only
 *       those.</strong> {@code :567-586} moves {@code CCDA-TITLE01}, {@code CCDA-TITLE02},
 *       {@code WS-TRANID}, {@code WS-PGMNAME} and the two clock renderings on <em>every</em> send of
 *       an invocation, so under a pinned clock those six are provably identical across the sends of
 *       one run and can honestly be attributed to each. The rest of {@code COTRN0AO} cannot: the
 *       program mutates one output area in place and sends it repeatedly, so a per-send snapshot of
 *       the row block does not exist to be reported. Rather than attribute the final screen to an
 *       earlier send - which would assert something false - the row block, the page number and the
 *       error line are pinned by the targeted assertions in {@link PaintedPage},
 *       {@link PageArithmetic} and {@link Messages}, which read the final painted response
 *       directly.</li>
 *   <li><strong>No dataset channel is reported at all.</strong> {@code COTRN00C} only ever issues
 *       {@code STARTBR}, {@code READNEXT}, {@code READPREV} and {@code ENDBR}; there is no
 *       {@code WRITE}, {@code REWRITE} or {@code DELETE} anywhere in its 699 lines. An empty write
 *       channel and an empty final-state channel are therefore the honest fingerprint, and
 *       {@link ReadOnlyAccess} proves it from the other side by verifying that no mutating
 *       repository method was called on any of the twenty cases.</li>
 * </ul>
 *
 * <h2>Fixed-point and encoding discipline</h2>
 * <p>No {@code double} or {@code float} appears here. The one monetary rendering this screen performs
 * is re-derived through {@link CobolDecimal} at scale two with {@link RoundingMode#DOWN}, which is
 * the only faithful mode because the keyword {@code ROUNDED} appears zero times in all twenty-eight
 * programs. Every charset is named explicitly - {@code US-ASCII} for the text fixtures - and never
 * taken from the platform. There is no static mutable state: the work area, the request, the response
 * and the stubbed repository are all created per invocation, exactly as {@code WORKING-STORAGE} is
 * per-task, and {@link Statelessness} asserts that by reflection and by re-running a case on a reused
 * controller instance.
 */
@DisplayName("COTRN00C parity: twenty statically derived cases, each of which must diff in nothing")
final class COTRN00CParityTest {

    // =================================================================================================
    // Identity. Every constant here is read from the type that owns it rather than restated, so a
    // drift in the DTO pair or the repository fails this suite instead of being papered over by a
    // second copy of the same literal.
    // =================================================================================================

    /** The program whose behaviour is the oracle, and the resource directory that holds its cases. */
    private static final String PROGRAM = "COTRN00C";

    /** The {@code TRANSACT} binding key, which is the one dataset every case seeds. */
    private static final String TRANSACT = TransactionRepository.CICS_FILE_NAME;

    /** Every case declares this kind, and the harness checks the declaration before the unit runs. */
    private static final ParityCase.UnitKind UNIT_KIND = ParityCase.UnitKind.CONTROLLER_POJO;

    /** The code page the nine ASCII fixtures and every seeded row are stated in. */
    private static final Charset CHARSET = StandardCharsets.US_ASCII;

    /** {@code 05 WS-MESSAGE PIC X(80)} - {@code app/cbl/COTRN00C.cbl:38}. */
    private static final int WS_MESSAGE_WIDTH = 80;

    /**
     * The six fields {@code POPULATE-HEADER-INFO} moves at {@code :571-586}, in source order. They are
     * the only part of {@code COTRN0AO} that is identical on every send of one invocation, which is
     * what makes them the honest per-send observation.
     */
    private static final List<String> HEADER_PREFIXES = List.of(
            TransactionListResponse.TITLE01,
            TransactionListResponse.TITLE02,
            TransactionListResponse.TRNNAME,
            TransactionListResponse.PGMNAME,
            TransactionListResponse.CURDATE,
            TransactionListResponse.CURTIME);

    /**
     * The five {@code EVALUATE EIBAID} arms at {@code :119-134}, in source order, named by the
     * {@code DFHAID} mnemonic each tests. {@code DFHPF12} stands for {@code WHEN OTHER}: it is a real
     * attention identifier that this program names nowhere, so it must fall through all four named
     * arms to reach the default.
     */
    private static final List<String> ENTER_ARM = List.of("DFHENTER");

    /** The four arms {@code EVALUATE EIBAID} names explicitly, in source order. */
    private static final List<String> NAMED_AIDS =
            List.of("DFHENTER", "DFHPF3", "DFHPF7", "DFHPF8");

    /** The mnemonic that must reach {@code WHEN OTHER} at {@code :129}. */
    private static final String UNNAMED_AID = "DFHPF12";

    /**
     * The nine message literals {@code COTRN00C} moves into {@code WS-MESSAGE}, transcribed from the
     * source lines named beside each. Three of them are near-identical top-of-page texts emitted from
     * three different places, which is precisely why they are listed rather than paraphrased: a
     * translation that used one where the source uses another would be wrong in a way no amount of
     * reading the Java would reveal.
     */
    private static final String MSG_AT_TOP = "You are at the top of the page...";
    private static final String MSG_REACHED_BOTTOM = "You have reached the bottom of the page...";
    private static final String MSG_REACHED_TOP = "You have reached the top of the page...";
    private static final String MSG_UNABLE = "Unable to lookup transaction...";
    private static final String MSG_ALREADY_TOP = "You are already at the top of the page...";
    private static final String MSG_ALREADY_BOTTOM = "You are already at the bottom of the page...";

    /** {@code COSGN00C}, the target of the no-commarea guard at {@code :108}. */
    private static final String SIGNON_PGM = "COSGN00C";

    /** {@code COMEN01C}, the target of the {@code DFHPF3} arm at {@code :123}. */
    private static final String MENU_PGM = "COMEN01C";

    /** {@code COTRN01C}, the target of an accepted row selection at {@code :188}. */
    private static final String TRAN_VIEW_PGM = "COTRN01C";

    // =================================================================================================
    // Case supply. ParityHarness.cases(String) already refuses a set that is not exactly case01
    // through case20 - a short set is not a smaller gate, it is a gate that passes vacuously - so the
    // loud failure the suite needs is inherited rather than reimplemented. The assertions below state
    // the invariant anyway, because a reader of this file should not have to open the harness to
    // discover that twenty is enforced.
    // =================================================================================================

    /**
     * The twenty declared cases, in ordinal order, as the parameterized test consumes them.
     *
     * @return exactly twenty cases; the harness throws rather than returning fewer
     */
    private static List<ParityCase> declaredCases() {
        List<ParityCase> cases = ParityHarness.casesOf(PROGRAM);
        assertThat(cases)
                .as("%s must declare exactly %d parity cases; %s enforces it, and this assertion "
                        + "restates the invariant where a reader of this suite will see it",
                        PROGRAM, ParityHarness.CASES_PER_PROGRAM, ParityHarness.class.getSimpleName())
                .hasSize(ParityHarness.CASES_PER_PROGRAM);
        return cases;
    }

    /**
     * The same twenty cases as a stream, which is the shape {@code @MethodSource} wants.
     *
     * <p>Each case is wrapped in a {@link Named} carrying its case id, so the gate below reports
     * {@code case07} rather than the whole of {@link ParityCase#toString()}. The wrapper is unwrapped
     * before the test sees it, so the parameter stays a {@code ParityCase}: this buys legibility in
     * the failure line at no cost to the assertion. It matters because the gate is stated per case -
     * a reader looking at a red build needs to know which of the twenty diverged before reading
     * anything else.
     *
     * @return a stream over exactly twenty cases, each named by its case id
     */
    private static Stream<Named<ParityCase>> cases() {
        return declaredCases().stream().map(parityCase -> Named.of(parityCase.caseId(), parityCase));
    }

    /**
     * Loads one case by ordinal, for a targeted assertion that is about that case specifically.
     *
     * @param ordinal the case number, {@code 1} through {@code 20}
     * @return the loaded case
     */
    private static ParityCase caseNumber(int ordinal) {
        return ParityHarness.usAscii().load(PROGRAM, ParityHarness.caseId(ordinal));
    }

    // =================================================================================================
    // The browse, in memory. This is the only part of the suite that stands in for a backend, and its
    // contract is the one TransactionRepository.startBrowse documents rather than an approximation of
    // it: positioning is at-or-after going forward and at-or-before going backward, both inclusive of
    // the anchor record, a boundary browse starts at the first or last record, and running off the end
    // reports END_OF_FILE and keeps reporting it rather than wrapping round.
    //
    // GTEQ is in force because it is the STARTBR default: app/cbl/COTRN00C.cbl:597 has the keyword
    // commented out, which changed nothing. Whether to discard the anchor record is the caller's
    // decision, and COTRN00C makes it at :285 and :339 - which is exactly the skip-read guard cases 13
    // to 17 exist to pin.
    // =================================================================================================

    /**
     * One positioned browse over the seeded rows, walked in one direction.
     *
     * <p>Per-invocation state, created inside the adapter and unreachable from anywhere else, so no
     * two cases can see each other's cursor.
     */
    private static final class TransactBrowseCursor {

        /** The seeded records in ascending {@code TRAN-ID} order, which is KSDS key order. */
        private final List<TranRecord> ascending;

        /** The direction this browse is walked; a read the other way is never issued to it. */
        private final BrowseDirection direction;

        /** The {@code RIDFLD} to position at, or {@code null} for a boundary browse. */
        private final String anchor;

        /**
         * The outcome forced onto the positioning probe, or {@code null} to let the data decide. This
         * is how the {@code WHEN DFHRESP(NOTFND)} arm at {@code :605} and the {@code WHEN OTHER} arm at
         * {@code :612} are reached over a file that has records to offer.
         */
        private final ParityCase.ForcedOutcome forcedPositioning;

        /**
         * The outcome forced onto every read <em>after</em> the probe, or {@code null}. Kept separate
         * from the probe's force on purpose: in CICS terms the probe is what carries the
         * {@code STARTBR} status, so a case that forced both through one declaration could not say
         * which of the two arms it was exercising.
         */
        private final ParityCase.ForcedOutcome forcedRead;

        /** Index of the record the next read returns; {@code -1} or {@code size} means past the end. */
        private int next;

        /** Whether {@link #position()} has run, which happens on the first read rather than eagerly. */
        private boolean positioned;

        /** Once the end has been reported it stays reported; a browse does not wrap. */
        private boolean exhausted;

        /** How many reads this browse has served, the first of which is the positioning probe. */
        private int reads;

        TransactBrowseCursor(List<TranRecord> ascending,
                             BrowseDirection direction,
                             String anchor,
                             ParityCase.ForcedOutcome forcedPositioning,
                             ParityCase.ForcedOutcome forcedRead) {
            this.ascending = ascending;
            this.direction = direction;
            this.anchor = anchor;
            this.forcedPositioning = forcedPositioning;
            this.forcedRead = forcedRead;
        }

        /**
         * Serves one read in this browse's direction.
         *
         * @param requested the direction the caller read in, checked against the browse's own so a
         *                  {@code READPREV} on a forward browse is a defect rather than a silent
         *                  success
         * @return the discriminated outcome
         */
        ReadResult read(BrowseDirection requested) {
            if (requested != direction) {
                throw new IllegalStateException("A " + requested + " read was issued against a "
                        + direction + " browse. COTRN00C opens a separate STARTBR per direction - "
                        + "FORWARD from PROCESS-PAGE-FORWARD at :281 and BACKWARD from "
                        + "PROCESS-PAGE-BACKWARD at :335 - so mixing them is a defect in the adapter, "
                        + "not an outcome to report.");
            }
            reads = reads + 1;
            ParityCase.ForcedOutcome forced = reads == 1 ? forcedPositioning : forcedRead;
            if (forced != null) {
                return forcedResult(forced);
            }
            if (!positioned) {
                position();
                positioned = true;
            }
            if (exhausted || next < 0 || next >= ascending.size()) {
                exhausted = true;
                return ReadResult.endOfFile(TRANSACT);
            }
            TranRecord record = ascending.get(next);
            next = next + (direction == BrowseDirection.FORWARD ? 1 : -1);
            return ReadResult.found(TRANSACT, record);
        }

        /** Ends the browse. Called twice on most paths - once at {@code :322} or {@code :371} and once
         * by the try-with-resources close - which is tolerated exactly as CICS tolerates it. */
        void end() {
            exhausted = true;
        }

        /** How many reads were served, the probe included. */
        int reads() {
            return reads;
        }

        /**
         * Positions the cursor, inclusive of the anchor, per the contract
         * {@link TransactionRepository#startBrowse(String, BrowseDirection)} documents.
         */
        private void position() {
            if (anchor == null) {
                next = direction == BrowseDirection.FORWARD ? 0 : ascending.size() - 1;
                return;
            }
            if (direction == BrowseDirection.FORWARD) {
                next = ascending.size();
                for (int index = 0; index < ascending.size(); index++) {
                    if (ascending.get(index).tranId().compareTo(anchor) >= 0) {
                        next = index;
                        return;
                    }
                }
                return;
            }
            next = -1;
            for (int index = ascending.size() - 1; index >= 0; index--) {
                if (ascending.get(index).tranId().compareTo(anchor) <= 0) {
                    next = index;
                    return;
                }
            }
        }

        /**
         * Turns a declared outcome into the {@link ReadResult} the repository would report for it.
         *
         * @param forced the outcome the case declared
         * @return the corresponding result
         */
        private ReadResult forcedResult(ParityCase.ForcedOutcome forced) {
            return switch (forced.outcome()) {
                case OK -> ReadResult.found(TRANSACT, requireRecordToForce(FileStatus.Outcome.OK));
                case END_OF_FILE -> ReadResult.endOfFile(TRANSACT);
                case NOT_FOUND -> ReadResult.notFound(TRANSACT);
                case DUPLICATE -> ReadResult.duplicate(TRANSACT,
                        requireRecordToForce(FileStatus.Outcome.DUPLICATE));
                case OTHER -> ReadResult.other(TRANSACT, TransactionRepository.PERMANENT_ERROR_STATUS);
            };
        }

        /** A forced record-bearing outcome needs a record; a case that forces one over an empty file
         * has mis-stated itself and is told so rather than producing a null record. */
        private TranRecord requireRecordToForce(FileStatus.Outcome outcome) {
            if (ascending.isEmpty()) {
                throw new IllegalStateException("A forced " + outcome + " outcome carries a record, "
                        + "but the case seeded no row into " + TRANSACT + " for it to carry. Seed a "
                        + "row, or force END_OF_FILE, NOT_FOUND or OTHER instead.");
            }
            return ascending.get(0);
        }
    }

    /**
     * Builds the stubbed {@code TRANSACT} file: a mocked repository whose two {@code startBrowse}
     * overloads hand back a mocked handle driven by an in-memory cursor over the seeded rows.
     *
     * <p>Mocking is used rather than a hand-written subclass because {@code Browse} is final and
     * because a mock is what lets {@link ReadOnlyAccess} prove a negative - that no mutating method
     * was ever called - which a subclass could only assert by overriding every one of them.
     *
     * @param invocation the seeded datasets and the case's forced outcomes
     * @param cursors    every cursor this repository hands out, so a test can count the reads
     * @return the stubbed repository
     */
    private static TransactionRepository stubbedTransactFile(ParityHarness.Invocation invocation,
                                                             List<TransactBrowseCursor> cursors) {
        List<TranRecord> ascending = seededRecords(invocation);
        TransactionRepository repository = Mockito.mock(TransactionRepository.class);
        Mockito.when(repository.startBrowse(any(BrowseDirection.class)))
                .thenAnswer(call -> handleFor(invocation, cursors, ascending,
                        call.getArgument(0, BrowseDirection.class), null));
        Mockito.when(repository.startBrowse(anyString(), any(BrowseDirection.class)))
                .thenAnswer(call -> handleFor(invocation, cursors, ascending,
                        call.getArgument(1, BrowseDirection.class),
                        keyImageOf(call.getArgument(0, String.class))));
        return repository;
    }

    /**
     * Creates one positioned handle and records its cursor.
     *
     * <p>The forced outcomes are taken through {@link ParityHarness.Invocation#forcedOutcome} here,
     * at the call site that consumes them, which is what makes an unconsumed declaration fail the run
     * rather than quietly forcing nothing.
     */
    private static Browse handleFor(ParityHarness.Invocation invocation,
                                    List<TransactBrowseCursor> cursors,
                                    List<TranRecord> ascending,
                                    BrowseDirection direction,
                                    String anchor) {
        TransactBrowseCursor cursor = new TransactBrowseCursor(ascending, direction, anchor,
                declaredOutcome(invocation, ParityCase.RepositoryOperation.START_BROWSE),
                declaredOutcome(invocation, ParityCase.RepositoryOperation.READ_NEXT));
        cursors.add(cursor);
        Browse handle = Mockito.mock(Browse.class);
        Mockito.when(handle.direction()).thenReturn(direction);
        Mockito.when(handle.readNext()).thenAnswer(call -> cursor.read(BrowseDirection.FORWARD));
        Mockito.when(handle.readPrev()).thenAnswer(call -> cursor.read(BrowseDirection.BACKWARD));
        Mockito.doAnswer(call -> {
            cursor.end();
            return null;
        }).when(handle).endBrowse();
        return handle;
    }

    /** The outcome the case forced for one operation, or {@code null} when it forced none. */
    private static ParityCase.ForcedOutcome declaredOutcome(ParityHarness.Invocation invocation,
                                                            ParityCase.RepositoryOperation operation) {
        return invocation.hasForcedOutcome(operation) ? invocation.forcedOutcome(operation) : null;
    }

    /**
     * Decodes the seeded {@code TRANSACT} rows into records, in ascending key order.
     *
     * <p>{@link TranRecord#decode(String, Charset)} rejects a row that is not exactly its declared 350
     * bytes, so a mis-measured seed fails here rather than silently shifting every field offset.
     */
    private static List<TranRecord> seededRecords(ParityHarness.Invocation invocation) {
        List<TranRecord> records = new ArrayList<>();
        if (!invocation.hasDataset(TRANSACT)) {
            return records;
        }
        ParityHarness.SeededDataset seeded = invocation.dataset(TRANSACT);
        for (String row : seeded.rows()) {
            records.add(TranRecord.decode(row, invocation.charset()));
        }
        records.sort(Comparator.comparing(TranRecord::tranId));
        return records;
    }

    /**
     * Reshapes a {@code RIDFLD} to the sixteen-byte key, space padded on the right, exactly as
     * {@code MOVE TRNIDINI OF COTRN0AI TO TRAN-ID} pads it at {@code :210}.
     */
    private static String keyImageOf(String ridfld) {
        return new FixedWidthCodec(CHARSET).movePicX(ridfld, TranRecord.TRAN_ID_KEY_LENGTH);
    }

    // =================================================================================================
    // The inbound payload. Rule R6 in one method: the whole conversation - the 160-byte commarea, the
    // 58-byte CDEMO-CT00-INFO extension appended to it, the attention identifier and the screen field
    // values - arrives in the request body, and nothing arrives in a session.
    // =================================================================================================

    /**
     * Assembles the request a case describes.
     *
     * @param invocation the case's screen request, already validated by the case model
     * @return the payload to hand the controller
     */
    private static TransactionListRequest requestOf(ParityHarness.Invocation invocation) {
        TransactionListRequest request = new TransactionListRequest();
        Map<String, String> commarea = invocation.commarea();
        if (!commarea.isEmpty()) {
            // :111  MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA, which covers both the shared
            // area and this program's own extension to it.
            request.setNavigationContext(navigationContextOf(commarea));
            request.setCursor(paginationCursorOf(commarea));
        }
        assertThat(request.commareaLength())
                .as("case %s declares EIBCALEN %d, so the assembled payload must report exactly that "
                        + "length: %d bytes of CARDDEMO-COMMAREA plus the %d-byte CDEMO-CT00-INFO "
                        + "extension, or zero when no area was passed",
                        invocation.caseId(), invocation.eibcalen(),
                        NavigationContext.COMMAREA_LENGTH, PaginationCursor.CURSOR_LENGTH)
                .isEqualTo(invocation.eibcalen());

        // The key indication travels in the payload too. The byte is what EVALUATE EIBAID tests and is
        // passed separately below; the token is set so the payload the controller receives is the one
        // production traffic would carry rather than a half-populated one.
        PfKeyResolver.resolve(aidByteOf(invocation.aid()))
                .ifPresent(key -> request.setAid(key.token()));

        // The received map. RECEIVE-TRNLST-SCREEN at :554-562 copies COTRN0AI wholesale, so the case
        // states only the xxxI items it fills and every other item keeps the spaces the request was
        // constructed with.
        for (Map.Entry<String, String> field : invocation.mapFields().entrySet()) {
            request.setPayloadValue(basePrefixOf(field.getKey()), field.getValue());
        }
        return request;
    }

    /**
     * Builds the 160-byte {@code CARDDEMO-COMMAREA} from the case's field map.
     *
     * <p>Every component is named, so a case that omitted one would fail loudly here rather than
     * silently receiving a default that the COBOL never produced.
     */
    private static NavigationContext navigationContextOf(Map<String, String> commarea) {
        return new NavigationContext(
                text(commarea, NavigationContext.FROM_TRANID_FIELD),
                text(commarea, NavigationContext.FROM_PROGRAM_FIELD),
                text(commarea, NavigationContext.TO_TRANID_FIELD),
                text(commarea, NavigationContext.TO_PROGRAM_FIELD),
                text(commarea, NavigationContext.USER_ID_FIELD),
                text(commarea, NavigationContext.USER_TYPE_FIELD),
                digit(commarea, NavigationContext.PGM_CONTEXT_FIELD),
                digit(commarea, NavigationContext.CUST_ID_FIELD),
                text(commarea, NavigationContext.CUST_FNAME_FIELD),
                text(commarea, NavigationContext.CUST_MNAME_FIELD),
                text(commarea, NavigationContext.CUST_LNAME_FIELD),
                number(commarea, NavigationContext.ACCT_ID_FIELD),
                text(commarea, NavigationContext.ACCT_STATUS_FIELD),
                number(commarea, NavigationContext.CARD_NUM_FIELD),
                text(commarea, NavigationContext.LAST_MAP_FIELD),
                text(commarea, NavigationContext.LAST_MAPSET_FIELD));
    }

    /**
     * Builds the 58-byte {@code CDEMO-CT00-INFO} extension from the case's field map.
     *
     * <p>Deliberately separate from {@link NavigationContext}: the shared area is exactly 160 bytes
     * and stays that width for all seventeen online programs, and this program's paging cursor is an
     * extension appended after it, not a member of it. Folding the two together would widen a
     * structure that twelve other programs share.
     */
    private static PaginationCursor paginationCursorOf(Map<String, String> commarea) {
        PaginationCursor cursor = new PaginationCursor();
        cursor.setTrnidFirst(text(commarea, PaginationCursor.TRNID_FIRST_FIELD));
        cursor.setTrnidLast(text(commarea, PaginationCursor.TRNID_LAST_FIELD));
        cursor.setPageNum((int) number(commarea, PaginationCursor.PAGE_NUM_FIELD));
        cursor.setNextPageFlg(text(commarea, PaginationCursor.NEXT_PAGE_FLG_FIELD));
        cursor.setTrnSelFlg(text(commarea, PaginationCursor.TRN_SEL_FLG_FIELD));
        cursor.setTrnSelected(text(commarea, PaginationCursor.TRN_SELECTED_FIELD));
        return cursor;
    }

    /** One {@code PIC X} commarea field, verbatim. */
    private static String text(Map<String, String> commarea, String field) {
        String value = commarea.get(field);
        assertThat(value)
                .as("the case must state commarea field %s: FieldDiffer compares the returned "
                        + "navigation context in BOTH directions, so a field nobody passed in and "
                        + "nobody pinned is a field nobody has checked", field)
                .isNotNull();
        return value;
    }

    /**
     * One {@code PIC 9} commarea field, decoded from its zero-filled image, for a field whose Java
     * component is a {@code long} - the eleven-digit account identifier and the sixteen-digit card
     * number, neither of which fits an {@code int}.
     */
    private static long number(Map<String, String> commarea, String field) {
        return Long.parseLong(text(commarea, field).trim());
    }

    /**
     * The same decode for a field whose Java component is an {@code int}: the one-digit program
     * context and the nine-digit customer identifier. Declared separately rather than cast at the call
     * site so that a widening mistake is a compile error rather than a silent narrowing.
     */
    private static int digit(Map<String, String> commarea, String field) {
        return Integer.parseInt(text(commarea, field).trim());
    }

    /**
     * The {@code EIBAID} byte a {@code DFHAID} mnemonic names.
     *
     * <p>{@code DFHAID} is IBM-supplied and absent from this repository, so {@link CicsAid} is the
     * single reproduction of it and the mapping is inverted from that one table rather than restated
     * here. An absent mnemonic is {@code DFHNULL}, which is what {@code EIBAID} holds when the task
     * was not started from a terminal key - the state the no-commarea guard runs in.
     */
    private static byte aidByteOf(String mnemonic) {
        if (mnemonic == null) {
            return CicsAid.DFHNULL;
        }
        for (Map.Entry<Byte, String> entry : CicsAid.mnemonicsByAid().entrySet()) {
            if (entry.getValue().equals(mnemonic)) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("The case names AID " + mnemonic + ", which common.CicsAid "
                + "does not declare. The case model validates the mnemonic against that same table, "
                + "so reaching here means the two have drifted apart.");
    }

    /** The base field prefix behind a symbolic-map {@code xxxI} input item name. */
    private static String basePrefixOf(String inputItemName) {
        assertThat(inputItemName)
                .as("a case states received screen fields by their symbolic-map xxxI item names, "
                        + "because those are the only payload-bearing items of a received map")
                .endsWith(TransactionListRequest.INPUT_ITEM_SUFFIX);
        return inputItemName.substring(0,
                inputItemName.length() - TransactionListRequest.INPUT_ITEM_SUFFIX.length());
    }

    // =================================================================================================
    // The observation. Everything below reads what the run produced and reports it; nothing here
    // decides anything, which is what keeps the adapter from being able to pass a case by agreeing
    // with itself.
    // =================================================================================================

    /**
     * Projects the response onto the shape the differ judges.
     *
     * @param ws       the work area the run used, which carries the send count, the cursor field and
     *                 whether control was transferred
     * @param response the painted response
     * @param codec    the codec every rendering goes through, carrying the named code page
     * @return the observation
     */
    private static FieldDiffer.ObservedResponse observedResponseOf(WorkArea ws,
                                                                  TransactionListResponse response,
                                                                  FixedWidthCodec codec) {
        return new FieldDiffer.ObservedResponse(
                blankToAbsent(response.getNextProgram()),
                blankToAbsent(response.getNextMapset()),
                blankToAbsent(response.getNextMap()),
                observedNavigationOf(response, codec),
                observedSendsOf(ws, response),
                cursorItemOf(ws),
                ws.isTransferred()
                        ? ParityCase.Termination.XCTL
                        : ParityCase.Termination.RETURN_TRANSID);
    }

    /**
     * All twenty-two commarea fields the response carries, each rendered at the width its
     * {@code PICTURE} clause declares.
     *
     * <p>Sixteen come from {@code app/cpy/COCOM01Y.cpy} and six from the {@code CDEMO-CT00-INFO}
     * extension {@code app/cbl/COTRN00C.cbl:62-70} appends to it. All twenty-two are reported because
     * the differ compares this map in both directions: an unreported field would be silently
     * unchecked, and the commarea is what the next transaction in the conversation receives.
     */
    private static Map<String, String> observedNavigationOf(TransactionListResponse response,
                                                            FixedWidthCodec codec) {
        NavigationContext context = response.getNavigationContext();
        Map<String, String> navigation = new LinkedHashMap<>();
        navigation.put(NavigationContext.FROM_TRANID_FIELD,
                codec.movePicX(context.fromTranid(), NavigationContext.FROM_TRANID_LENGTH));
        navigation.put(NavigationContext.FROM_PROGRAM_FIELD,
                codec.movePicX(context.fromProgram(), NavigationContext.FROM_PROGRAM_LENGTH));
        navigation.put(NavigationContext.TO_TRANID_FIELD,
                codec.movePicX(context.toTranid(), NavigationContext.TO_TRANID_LENGTH));
        navigation.put(NavigationContext.TO_PROGRAM_FIELD,
                codec.movePicX(context.toProgram(), NavigationContext.TO_PROGRAM_LENGTH));
        navigation.put(NavigationContext.USER_ID_FIELD,
                codec.movePicX(context.userId(), NavigationContext.USER_ID_LENGTH));
        navigation.put(NavigationContext.USER_TYPE_FIELD,
                codec.movePicX(context.userType(), NavigationContext.USER_TYPE_LENGTH));
        navigation.put(NavigationContext.PGM_CONTEXT_FIELD,
                codec.movePic9(context.pgmContext(), NavigationContext.PGM_CONTEXT_LENGTH));
        navigation.put(NavigationContext.CUST_ID_FIELD,
                codec.movePic9(context.custId(), NavigationContext.CUST_ID_LENGTH));
        navigation.put(NavigationContext.CUST_FNAME_FIELD,
                codec.movePicX(context.custFname(), NavigationContext.CUST_FNAME_LENGTH));
        navigation.put(NavigationContext.CUST_MNAME_FIELD,
                codec.movePicX(context.custMname(), NavigationContext.CUST_MNAME_LENGTH));
        navigation.put(NavigationContext.CUST_LNAME_FIELD,
                codec.movePicX(context.custLname(), NavigationContext.CUST_LNAME_LENGTH));
        navigation.put(NavigationContext.ACCT_ID_FIELD,
                codec.movePic9(context.acctId(), NavigationContext.ACCT_ID_LENGTH));
        navigation.put(NavigationContext.ACCT_STATUS_FIELD,
                codec.movePicX(context.acctStatus(), NavigationContext.ACCT_STATUS_LENGTH));
        navigation.put(NavigationContext.CARD_NUM_FIELD,
                codec.movePic9(context.cardNum(), NavigationContext.CARD_NUM_LENGTH));
        navigation.put(NavigationContext.LAST_MAP_FIELD,
                codec.movePicX(context.lastMap(), NavigationContext.LAST_MAP_LENGTH));
        navigation.put(NavigationContext.LAST_MAPSET_FIELD,
                codec.movePicX(context.lastMapset(), NavigationContext.LAST_MAPSET_LENGTH));

        TransactionListCursor cursor = response.getCursor();
        navigation.put(TransactionListCursor.TRNID_FIRST_FIELD,
                codec.movePicX(cursor.getTrnidFirst(), TransactionListCursor.TRNID_FIRST_LENGTH));
        navigation.put(TransactionListCursor.TRNID_LAST_FIELD,
                codec.movePicX(cursor.getTrnidLast(), TransactionListCursor.TRNID_LAST_LENGTH));
        navigation.put(TransactionListCursor.PAGE_NUM_FIELD,
                codec.movePic9(cursor.getPageNum(), TransactionListCursor.PAGE_NUM_LENGTH));
        navigation.put(TransactionListCursor.NEXT_PAGE_FLG_FIELD,
                codec.movePicX(cursor.getNextPageFlg(), TransactionListCursor.NEXT_PAGE_FLG_LENGTH));
        navigation.put(TransactionListCursor.TRN_SEL_FLG_FIELD,
                codec.movePicX(cursor.getTrnSelFlg(), TransactionListCursor.TRN_SEL_FLG_LENGTH));
        navigation.put(TransactionListCursor.TRN_SELECTED_FIELD,
                codec.movePicX(cursor.getTrnSelected(), TransactionListCursor.TRN_SELECTED_LENGTH));
        return navigation;
    }

    /**
     * One entry per {@code EXEC CICS SEND}, each carrying the six fields
     * {@code POPULATE-HEADER-INFO} moves and no attribute.
     *
     * <p>The count is the behaviour this list exists to pin: several paths through {@code COTRN00C}
     * perform {@code SEND-TRNLST-SCREEN} twice in one invocation - once from a file paragraph's
     * end-of-data or failure arm and once from {@code :326} or {@code :374} - and a translation that
     * collapsed them would have changed what the terminal saw. The six fields are attributed to every
     * send because {@code :571-586} moves exactly those six on every send, from two copybook literals,
     * two program literals and one pinned clock, so they cannot differ between the sends of one run.
     * The attribute map is empty on every send because {@code COTRN00C} moves no attribute anywhere:
     * it copies {@code DFHBMSCA} at {@code :81} and never uses it, and it does not copy
     * {@code CSSETATY} at all.
     */
    private static List<FieldDiffer.ObservedSend> observedSendsOf(WorkArea ws,
                                                                 TransactionListResponse response) {
        Map<String, String> header = new LinkedHashMap<>();
        for (String prefix : HEADER_PREFIXES) {
            header.put(TransactionListResponse.outputItemName(prefix), response.payloadValue(prefix));
        }
        List<FieldDiffer.ObservedSend> sends = new ArrayList<>(ws.sendCount());
        for (int send = 0; send < ws.sendCount(); send++) {
            sends.add(FieldDiffer.ObservedSend.ofFields(header));
        }
        return sends;
    }

    /**
     * The symbolic-map length item that received {@code MOVE -1}, which is how COBOL positions the
     * cursor.
     *
     * <p>{@code COTRN00C} has exactly one cursor target, {@code TRNIDINL}, and moves {@code -1} into
     * it at eleven separate sites. The work area records the base field name, so it is turned into the
     * length item's name through the DTO's own naming rule rather than by string concatenation here.
     */
    private static String cursorItemOf(WorkArea ws) {
        String base = ws.cursorField();
        return base == null ? null : TransactionListRequest.lengthItemName(base);
    }

    /**
     * Reports an all-blank navigation target as absent.
     *
     * <p>{@code CDEMO-TO-PROGRAM} is {@code PIC X(8)} and a path that transfers nowhere leaves it
     * spaces, which is the absence of a target rather than a target named by eight spaces. The case
     * model refuses a blank value for the same reason, so the two agree.
     */
    private static String blankToAbsent(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // =================================================================================================
    // The run. One method reaches the unit under test, and every assertion in this file goes through
    // it, so there is exactly one place where the controller is constructed and called.
    // =================================================================================================

    /** Everything one run produced, so a targeted assertion can read what the differ has judged. */
    private record Observed(ParityCase parityCase,
                            TransactionListResponse response,
                            WorkArea work,
                            TransactionRepository repository,
                            List<TransactBrowseCursor> cursors,
                            FieldDiffer.DiffResult diffs) {
    }

    /**
     * Seeds the case, invokes {@code COTRN00C}'s translation as a plain Java object, captures the
     * fingerprint and judges it.
     *
     * @param parityCase the case to run
     * @return the run, judged
     */
    private static Observed execute(ParityCase parityCase) {
        ParityHarness harness = ParityHarness.usAscii();
        AtomicReference<TransactionListResponse> painted = new AtomicReference<>();
        AtomicReference<WorkArea> work = new AtomicReference<>();
        AtomicReference<TransactionRepository> file = new AtomicReference<>();
        List<TransactBrowseCursor> cursors = new ArrayList<>();

        FieldDiffer.DiffResult diffs = harness.judge(parityCase, UNIT_KIND, invocation -> {
            FixedWidthCodec codec = new FixedWidthCodec(invocation.charset());
            TransactionRepository repository = stubbedTransactFile(invocation, cursors);
            file.set(repository);

            // The canonical constructor: no Spring context, no MockMvc, no servlet container and no
            // JobLauncher - only a stubbed repository, a codec carrying a named code page and the
            // pinned clock FUNCTION CURRENT-DATE is read from at :569.
            TransactionMenuController controller =
                    new TransactionMenuController(repository, codec, invocation.clock());

            TransactionListRequest request = requestOf(invocation);
            WorkArea ws = new WorkArea();
            TransactionListResponse response = controller.listTransactions(request,
                    aidByteOf(invocation.aid()), ws);
            painted.set(response);
            work.set(ws);

            invocation.recorder()
                    .response(observedResponseOf(ws, response, codec))
                    .returnCode(0)
                    // Both message channels, every time. WS-MESSAGE is PIC X(80) and always holds a
                    // value - :102 moves SPACES into it before anything else - and ERRMSGO is the
                    // PIC X(78) field :531 truncates it into, so reporting the pair is what makes the
                    // 80-to-78 move comparable at all. EmittedMessage rejects a text that is not
                    // exactly its channel's width, so a drift in either declaration fails here.
                    .message(new ParityCase.EmittedMessage(
                            ParityCase.MessageChannel.WS_MESSAGE_80, ws.message()))
                    .message(new ParityCase.EmittedMessage(
                            ParityCase.MessageChannel.SCREEN_ERRMSG_78, response.getErrmsgO()));
            return invocation.recorder().build();
        });

        return new Observed(parityCase, painted.get(), work.get(), file.get(), cursors, diffs);
    }

    /**
     * Runs a case and requires it to be clean, for a targeted assertion that builds on a case having
     * already passed.
     *
     * @param ordinal the case number
     * @return the run
     */
    private static Observed clean(int ordinal) {
        Observed observed = execute(caseNumber(ordinal));
        assertThat(observed.diffs().count())
                .as("%s must diff in nothing before anything further is asserted about it:%n%s",
                        observed.parityCase().caseId(), observed.diffs().render())
                .isZero();
        return observed;
    }

    // =================================================================================================
    // THE GATE. Everything above exists to make this one assertion mean something.
    // =================================================================================================

    /**
     * The parity gate: every one of the twenty cases must diff in nothing.
     *
     * <p>Stated as a diff <em>count</em> rather than as a boolean because the count is what the gate
     * is written in terms of, and because a count of seven and a count of one are different amounts of
     * work to do. The rendered diff list is attached to the failure so a reader sees which field, on
     * which channel, with which expected and observed value - not merely that something was wrong.
     *
     * @param parityCase one of the twenty declared cases
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("The gate: diff count must be exactly zero, case by case")
    void everyDeclaredCaseProducesZeroDiffs(ParityCase parityCase) {
        Observed observed = execute(parityCase);

        assertThat(observed.diffs().program()).isEqualTo(PROGRAM);
        assertThat(observed.diffs().caseId()).isEqualTo(parityCase.caseId());
        assertThat(observed.diffs().count())
                .as("%s/%s must produce a diff count of zero. A module is not complete until the "
                        + "count is zero across ALL twenty of its cases, so one difference here is a "
                        + "failure of the whole program and not of one case.%n%s",
                        PROGRAM, parityCase.caseId(), observed.diffs().render())
                .isZero();
        assertThat(observed.diffs().isClean()).isTrue();
        assertThat(observed.diffs().entries()).isEmpty();
    }

    // =================================================================================================
    @Nested
    @DisplayName("The suite's own shape: twenty cases, and each of them complete")
    class SuiteShape {

        @Test
        @DisplayName("exactly twenty cases exist, named case01 through case20")
        void exactlyTwentyCasesExist() {
            List<ParityCase> cases = declaredCases();
            List<String> ids = new ArrayList<>();
            for (ParityCase parityCase : cases) {
                ids.add(parityCase.caseId());
            }
            List<String> expected = new ArrayList<>();
            for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
                expected.add(ParityHarness.caseId(ordinal));
            }
            assertThat(ids)
                    .as("the resource directory %s must hold exactly the twenty case files the gate "
                            + "names, in order and with nothing else beside them",
                            ParityHarness.CASE_RESOURCE_ROOT + PROGRAM)
                    .containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("every case names this program, this unit kind and the pinned code page")
        void everyCaseNamesThisProgram() {
            for (ParityCase parityCase : declaredCases()) {
                assertThat(parityCase.program()).isEqualTo(PROGRAM);
                assertThat(parityCase.unitKind())
                        .as("%s: the unit is a controller invoked as a plain object, which is what "
                                + "keeps every HTTP layer out of the path", parityCase.caseId())
                        .isEqualTo(UNIT_KIND);
                assertThat(parityCase.screenRequest())
                        .as("%s: COTRN00C is an online program, so every case describes a screen "
                                + "request", parityCase.caseId())
                        .isNotNull();
                assertThat(parityCase.screenRequest().charset())
                        .as("%s: the code page is named explicitly and never taken from the platform",
                                parityCase.caseId())
                        .isEqualTo(CHARSET.name());
                assertThat(parityCase.screenRequest().pinnedClock())
                        .as("%s: FUNCTION CURRENT-DATE at :569 reaches the screen as CURDATEO and "
                                + "CURTIMEO, so the clock is pinned rather than read from the host",
                                parityCase.caseId())
                        .isNotNull();
                assertThat(parityCase.description())
                        .as("%s: a statically derived expectation is only reviewable if it says which "
                                + "source lines it was derived from", parityCase.caseId())
                        .isNotBlank();
            }
        }

        @Test
        @DisplayName("every case seeds TRANSACT and pins all twenty-two commarea fields")
        void everyCaseSeedsTransactAndPinsTheWholeCommarea() {
            for (ParityCase parityCase : declaredCases()) {
                assertThat(parityCase.inputs().keySet())
                        .as("%s: TRANSACT is the only dataset COTRN00C touches, and it is seeded even "
                                + "on the paths that never open it so that 'the browse never happened' "
                                + "is an assertion rather than a vacuous statement",
                                parityCase.caseId())
                        .containsExactly(TRANSACT);
                assertThat(parityCase.expectedResponse()).isNotNull();
                assertThat(parityCase.expectedResponse().navigation())
                        .as("%s: sixteen CARDDEMO-COMMAREA fields plus the six of CDEMO-CT00-INFO. "
                                + "FieldDiffer compares this map in both directions, so a field the "
                                + "case does not pin is a field nobody has checked",
                                parityCase.caseId())
                        .hasSize(22);
                assertThat(parityCase.expectedResponse().cursorField())
                        .as("%s: COTRN00C positions the cursor with MOVE -1 TO TRNIDINL and has no "
                                + "other cursor target", parityCase.caseId())
                        .isEqualTo(TransactionListRequest.lengthItemName(
                                TransactionListRequest.TRNIDIN_FIELD));
                assertThat(parityCase.expectedReturnCode())
                        .as("%s: an online program sets no RETURN-CODE, so zero is the whole of the "
                                + "expectation", parityCase.caseId())
                        .isZero();
            }
        }

        @Test
        @DisplayName("no case expects a written record, because the program writes none")
        void noCaseExpectsAWrittenRecord() {
            for (ParityCase parityCase : declaredCases()) {
                assertThat(parityCase.expectedWrites())
                        .as("%s: COTRN00C issues STARTBR, READNEXT, READPREV and ENDBR and nothing "
                                + "else - there is no WRITE, REWRITE or DELETE in its 699 lines",
                                parityCase.caseId())
                        .isEmpty();
                assertThat(parityCase.expectedFinalState()).isEmpty();
                assertThat(parityCase.expectedDatasets()).isEmpty();
                assertThat(parityCase.normalisations())
                        .as("%s: the two shipped normalisations widen CARDXREF and USRSEC rows, and "
                                + "neither dataset is reachable from this screen", parityCase.caseId())
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("every case pins both message channels, at their own declared widths")
        void everyCasePinsBothMessageChannels() {
            for (ParityCase parityCase : declaredCases()) {
                List<ParityCase.EmittedMessage> messages = parityCase.expectedMessages();
                assertThat(messages)
                        .as("%s: WS-MESSAGE PIC X(80) and the ERRMSGO PIC X(78) it is truncated into",
                                parityCase.caseId())
                        .hasSize(2);
                assertThat(messages.get(0).channel())
                        .isEqualTo(ParityCase.MessageChannel.WS_MESSAGE_80);
                assertThat(messages.get(1).channel())
                        .isEqualTo(ParityCase.MessageChannel.SCREEN_ERRMSG_78);
                assertThat(messages.get(0).text()).hasSize(WS_MESSAGE_WIDTH);
                assertThat(messages.get(1).text()).hasSize(TransactionListResponse.ERRMSG_LENGTH);
            }
        }
    }

    // =================================================================================================
    @Nested
    @DisplayName("Page size ten: behaviour taken from two loop bounds, not configuration")
    class PageSizeIsBehaviour {

        @Test
        @DisplayName("ten is stated identically in both halves of the DTO pair and nowhere else")
        void tenIsStatedIdenticallyInBothHalvesOfThePair() {
            assertThat(TransactionListRequest.PAGE_SIZE)
                    .as("UNTIL WS-IDX > 10 at :290 and :344, UNTIL WS-IDX >= 11 at :297, and a "
                            + "ten-armed EVALUATE WS-IDX at :390-445. Ten is the program's behaviour "
                            + "and cannot be made a property without changing which records a page "
                            + "shows")
                    .isEqualTo(10)
                    .isEqualTo(TransactionListResponse.PAGE_SIZE)
                    .isEqualTo(TransactionListRequest.ROW_COUNT)
                    .isEqualTo(TransactionListResponse.ROW_COUNT)
                    .isEqualTo(TransactionListResponse.LAST_ROW);
            assertThat(TransactionListResponse.FIRST_ROW)
                    .as("MOVE 1 TO WS-IDX at :295 - COBOL screen rows are 1-based, so the first row "
                            + "is row one and there is no row zero")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("there is no eleventh row to page into, in either direction")
        void thereIsNoEleventhRow() {
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .as("an eleventh row would silently exist if the page size were widened without "
                            + "widening the map, and the eleventh record would then be painted into a "
                            + "cell app/bms/COTRN00.bms does not declare")
                    .isThrownBy(() -> TransactionListRequest.requireValidRow(
                            TransactionListRequest.PAGE_SIZE + 1));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> TransactionListRequest.requireValidRow(0));
            assertThat(TransactionListResponse.fieldPrefixes())
                    .as("eight header items, five per row across ten rows, and one error line: the "
                            + "fifty-nine DFHMDF fields of mapset COTRN00")
                    .hasSize(TransactionListRequest.FIELD_COUNT)
                    .hasSize(59);
        }

        @Test
        @DisplayName("case02 paints exactly ten of twelve records and leaves the eleventh unpainted")
        void aFullPageStopsAtTenOfTwelve() {
            Observed observed = clean(2);
            TransactionListResponse response = observed.response();
            for (int row = TransactionListResponse.FIRST_ROW;
                 row <= TransactionListResponse.LAST_ROW; row++) {
                assertThat(response.getRowTransactionId(row))
                        .as("screen row %d takes record %d: the loop at :297 populates rows one to ten "
                                + "in ascending key order", row, row)
                        .isEqualTo(keyImageOf(tranIdImage(row)));
            }
            List<String> painted = new ArrayList<>();
            for (int row = TransactionListResponse.FIRST_ROW;
                 row <= TransactionListResponse.LAST_ROW; row++) {
                painted.add(response.getRowTransactionId(row));
            }
            assertThat(painted)
                    .as("record eleven exists on file and is read by the look-ahead READNEXT at :308, "
                            + "but it is NOT painted - it only sets NEXT-PAGE-YES at :310")
                    .doesNotContain(keyImageOf(tranIdImage(11)))
                    .doesNotContain(keyImageOf(tranIdImage(12)));
            assertThat(observed.work().cursor().getNextPageFlg())
                    .as("the eleventh record is what SET NEXT-PAGE-YES at :310 is derived from")
                    .isEqualTo(TransactionListCursor.NEXT_PAGE_YES);
        }
    }

    /**
     * The {@code TRAN-ID} the seed generator gives record {@code n}: {@code PIC X(16)} filled with the
     * zero-padded ordinal, which is both a valid key and sortable in KSDS order.
     *
     * @param ordinal the record's position in the seed, from one
     * @return the sixteen-character key image
     */
    private static String tranIdImage(int ordinal) {
        return new FixedWidthCodec(CHARSET).movePic9(ordinal, TranRecord.TRAN_ID_KEY_LENGTH);
    }

    // =================================================================================================
    @Nested
    @DisplayName("The painted page: which records land in which row, and in which order")
    class PaintedPage {

        @Test
        @DisplayName("a partial first page fills rows one to five and leaves six to ten blanked")
        void aPartialPageLeavesTheTailBlank() {
            Observed observed = clean(4);
            for (int row = 1; row <= 5; row++) {
                assertThat(observed.response().getRowTransactionId(row))
                        .isEqualTo(keyImageOf(tranIdImage(row)));
            }
            for (int row = 6; row <= TransactionListResponse.LAST_ROW; row++) {
                assertThat(observed.response().getRowTransactionId(row))
                        .as("row %d was blanked by INITIALIZE-TRAN-DATA at :450-508 and never "
                                + "repopulated, because the loop at :297 stopped at WS-IDX six", row)
                        .isBlank();
                assertThat(observed.response().getRowDescription(row)).isBlank();
                assertThat(observed.response().getRowTransactionDate(row)).isBlank();
                assertThat(observed.response().getRowAmount(row)).isBlank();
            }
        }

        @Test
        @DisplayName("a backward page fills rows top-down while reading bottom-up")
        void aBackwardPageReadsDescendingAndPaintsAscending() {
            Observed observed = clean(15);
            for (int row = TransactionListResponse.FIRST_ROW;
                 row <= TransactionListResponse.LAST_ROW; row++) {
                assertThat(observed.response().getRowTransactionId(row))
                        .as("PROCESS-PAGE-BACKWARD primes WS-IDX at ten (:349) and decrements it "
                                + "(:355), so the records arrive in descending order and are painted "
                                + "into descending rows - which leaves the page ascending on the "
                                + "terminal. Row %d must hold record %d", row, row + 10)
                        .isEqualTo(keyImageOf(tranIdImage(row + 10)));
            }
        }

        @Test
        @DisplayName("a partial backward page fills rows six to ten and leaves one to five blanked")
        void aPartialBackwardPageLeavesTheHeadBlank() {
            Observed observed = clean(17);

            // PF7 anchored on record six, so only records five down to one lie below the anchor.
            // WS-IDX is primed at ten and decremented, so those five land in rows ten down to six and
            // the head of the page is never reached at all - which is the opposite end from the one a
            // forward partial page leaves blank.
            int availableBelowAnchor = 5;
            int firstPopulatedRow = TransactionListResponse.LAST_ROW - availableBelowAnchor + 1;
            assertThat(firstPopulatedRow).isEqualTo(6);

            for (int row = TransactionListResponse.FIRST_ROW; row < firstPopulatedRow; row++) {
                assertThat(observed.response().getRowTransactionId(row))
                        .as("row %d is never reached: WS-IDX counts down from ten and end-of-file "
                                + "stopped it at five, so the blanking done at :343-347 is all that "
                                + "ever touched this row", row)
                        .isBlank();
            }
            for (int row = firstPopulatedRow; row <= TransactionListResponse.LAST_ROW; row++) {
                int record = row - (TransactionListResponse.LAST_ROW - availableBelowAnchor);
                assertThat(observed.response().getRowTransactionId(row))
                        .as("row %d takes record %d. The reads arrive descending - five, four, three, "
                                + "two, one - and land in rows ten, nine, eight, seven, six, so the "
                                + "five records still read ascending on the terminal", row, record)
                        .isEqualTo(keyImageOf(tranIdImage(record)));
            }
        }

        @Test
        @DisplayName("the description is truncated on the right, from X(100) to X(26)")
        void theDescriptionIsTruncatedOnTheRight() {
            Observed observed = clean(2);
            String painted = observed.response().getRowDescription(1);
            assertThat(painted)
                    .as("TRAN-DESC is PIC X(100) in app/cpy/CVTRA05Y.cpy and TDESC01I is PIC X(26) in "
                            + "app/cpy-bms/COTRN00.CPY, so MOVE TRAN-DESC TO TDESC01I at :395 keeps "
                            + "the LEFTMOST 26 characters. Truncating on the other end would produce a "
                            + "plausible-looking description that is simply the wrong text")
                    .hasSize(TransactionListResponse.TDESC_LENGTH)
                    .isEqualTo("TRANSACTION 01 DESCRIPTION");
        }

        @Test
        @DisplayName("the amount renders as PIC +99999999.99 at scale two, truncating")
        void theAmountRendersAsTheEditedPicture() {
            Observed observed = clean(2);
            for (int row = TransactionListResponse.FIRST_ROW;
                 row <= TransactionListResponse.LAST_ROW; row++) {
                BigDecimal seeded = seededAmount(row);
                assertThat(observed.response().getRowAmount(row))
                        .as("MOVE TRAN-AMT TO WS-TRAN-AMT at :383 renders S9(09)V99 through the edited "
                                + "picture +99999999.99 declared at :56, which is twelve characters: a "
                                + "sign, eight integer digits, a point and two decimals")
                        .hasSize(TransactionListResponse.TAMT_LENGTH)
                        .isEqualTo(editedAmountOf(seeded));
            }
        }

        @Test
        @DisplayName("the date is TRAN-ORIG-TS reduced to mm/dd/yy, never the current date")
        void theDateComesFromTheRecordAndNotFromTheClock() {
            Observed observed = clean(2);
            assertThat(observed.response().getRowTransactionDate(1))
                    .as("MOVE TRAN-ORIG-TS TO WS-TIMESTAMP at :384 and three two-byte slices at "
                            + "385-387 give mm/dd/yy of the record's own origination timestamp "
                            + "'2022-06-10 19:27:53.000000'. The header's CURDATEO comes from the "
                            + "clock instead, and the two must not be confused")
                    .hasSize(TransactionListResponse.TDATE_LENGTH)
                    .isEqualTo("06/10/22");
            assertThat(observed.response().getCurdateO())
                    .as("the header date is the pinned clock's, which is a different month entirely")
                    .isEqualTo("07/19/22");
            assertThat(observed.response().getCurtimeO()).isEqualTo("23:12:34");
        }

        /**
         * The amount the seed generator gives record {@code n}: {@code 100.00} plus the ordinal, which
         * makes every row distinguishable and keeps the two decimal places non-zero-only.
         */
        private BigDecimal seededAmount(int ordinal) {
            return CobolDecimal.store(new BigDecimal(10000 + ordinal * 100).movePointLeft(2),
                    CobolDecimal.MONETARY_SCALE);
        }

        /**
         * Renders {@code PIC +99999999.99} from a stored amount, mechanically, from the picture clause
         * at {@code app/cbl/COTRN00C.cbl:56}.
         *
         * <p>Derived here rather than borrowed from the controller, so that agreeing with it is
         * evidence. {@link CobolDecimal} supplies the store-with-truncation rule, which is the only
         * faithful one: {@code ROUNDED} appears zero times in all twenty-eight programs, so COBOL
         * truncates excess fractional digits, and {@link RoundingMode#DOWN} is what that means.
         */
        private String editedAmountOf(BigDecimal amount) {
            BigDecimal stored = CobolDecimal.store(amount, CobolDecimal.MONETARY_SCALE);
            assertThat(stored.scale()).isEqualTo(CobolDecimal.MONETARY_SCALE);
            assertThat(CobolDecimal.COBOL_ROUNDING).isEqualTo(RoundingMode.DOWN);
            String digits = new FixedWidthCodec(CHARSET).movePic9(
                    stored.abs().movePointRight(CobolDecimal.MONETARY_SCALE).longValueExact(), 10);
            return (stored.signum() < 0 ? "-" : "+") + digits.substring(0, 8) + '.'
                    + digits.substring(8);
        }
    }

    // =================================================================================================
    @Nested
    @DisplayName("Page arithmetic: the four named sites, each with its own case")
    class PageArithmetic {

        @Test
        @DisplayName(":301 - COMPUTE WS-IDX = WS-IDX + 1, ten times for a full page")
        void theForwardCounterStepsOncePerPaintedRow() {
            assertThat(clean(2).work().idx())
                    .as("WS-IDX is primed at one by :295 and stepped by :301 after each painted row, "
                            + "so a full page leaves it at eleven - the value that made 'UNTIL WS-IDX "
                            + ">= 11' at :297 true and ended the loop")
                    .isEqualTo(TransactionListResponse.PAGE_SIZE + 1);
            assertThat(clean(4).work().idx())
                    .as("five records painted, so :301 ran five times and the sixth READNEXT reported "
                            + "end-of-file with WS-IDX at six")
                    .isEqualTo(6);
            assertThat(clean(13).work().idx())
                    .as("two records painted on the short final page, leaving WS-IDX at three")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName(":306-307 - COMPUTE CDEMO-CT00-PAGE-NUM + 1 on the not-at-end path")
        void theFirstPageNumberSiteRunsWhenTheBrowseHasMore() {
            assertThat(clean(2).work().cursor().getPageNum())
                    .as("an ENTER zeroes the page number at :224 and :306-307 makes it one")
                    .isEqualTo(1);
            assertThat(clean(14).work().cursor().getPageNum())
                    .as("the same COMPUTE from a non-zero start: page one plus one is two, which is "
                            + "why a middle page is a distinct assertion from a first page")
                    .isEqualTo(2);
            assertThat(clean(14).work().cursor().getNextPageFlg())
                    .as("the look-ahead READNEXT at :308 found record twenty-one, so :310 sets Y")
                    .isEqualTo(TransactionListCursor.NEXT_PAGE_YES);
        }

        @Test
        @DisplayName(":317-318 - the same increment on the at-end path, gated by IF WS-IDX > 1")
        void theSecondPageNumberSiteRunsOnlyWhenARowWasPainted() {
            assertThat(clean(4).work().cursor().getPageNum())
                    .as("a partial first page reaches the ELSE at :314, and WS-IDX six being greater "
                            + "than one lets :317-318 make the page number one")
                    .isEqualTo(1);
            assertThat(clean(13).work().cursor().getPageNum())
                    .as("the same site from page one gives two")
                    .isEqualTo(2);
            assertThat(clean(5).work().cursor().getPageNum())
                    .as("an empty result reaches :316 with WS-IDX still at one, so 'IF WS-IDX > 1' is "
                            + "FALSE and the page number stays ZERO. An unconditional increment would "
                            + "show page one over an empty list")
                    .isZero();
            assertThat(clean(6).work().cursor().getPageNum())
                    .as("a NOTFND position leaves it at zero for the same reason")
                    .isZero();
        }

        @Test
        @DisplayName(":355 - COMPUTE WS-IDX = WS-IDX - 1, the descending counterpart")
        void theBackwardCounterStepsDown() {
            assertThat(clean(15).work().idx())
                    .as("WS-IDX is primed at TEN by :349 and decremented by :355, so a full backward "
                            + "page leaves it at zero - the value that made 'UNTIL WS-IDX <= 0' at "
                            + ":351 true")
                    .isZero();
            assertThat(clean(17).work().idx())
                    .as("five records painted going backwards, so :355 ran five times from ten")
                    .isEqualTo(5);
        }

        @Test
        @DisplayName(":364 SUBTRACT versus :366 MOVE 1 - two ways to reach the same number")
        void theTwoRewindSitesAreDistinguished() {
            Observed subtracted = clean(15);
            assertThat(subtracted.work().cursor().getPageNum())
                    .as("page three minus one is two, via SUBTRACT 1 FROM CDEMO-CT00-PAGE-NUM at :364")
                    .isEqualTo(2);
            assertThat(subtracted.work().isTransactNotEof())
                    .as("the extra READPREV at :360 found a record, which is the first conjunct of "
                            + ":362 and the reason :364 rather than :366 ran")
                    .isTrue();

            Observed moved = clean(16);
            assertThat(moved.work().cursor().getPageNum())
                    .as("MOVE 1 TO CDEMO-CT00-PAGE-NUM at :366, reached because the READPREV at :360 "
                            + "ran off the front of the file. The value one is what page two minus one "
                            + "would also have produced, so the paths are told apart by the end-of-file "
                            + "condition and by the message the ENDFILE arm emitted, not by the number")
                    .isEqualTo(1);
            assertThat(moved.work().isTransactEof()).isTrue();
            assertThat(moved.work().message())
                    .isEqualTo(paddedMessage(MSG_REACHED_TOP));

            Observed neither = clean(17);
            assertThat(neither.work().cursor().getPageNum())
                    .as("a backward page that ran out mid-page never reaches :359 at all, so neither "
                            + "rewind site runs and the page number stays at the two it arrived with - "
                            + "while showing the first five records of the file. That is an inherited "
                            + "oddity of the program and is preserved rather than tidied")
                    .isEqualTo(2);
        }
    }

    /** One message literal padded into {@code WS-MESSAGE PIC X(80)}, which is how the program holds it. */
    private static String paddedMessage(String literal) {
        return new FixedWidthCodec(CHARSET).movePicX(literal, WS_MESSAGE_WIDTH);
    }

    // =================================================================================================
    @Nested
    @DisplayName("Ordered dispatch: EVALUATE decides by position, and the position is the behaviour")
    class OrderedDispatch {

        @Test
        @DisplayName("all five EVALUATE EIBAID arms are driven, WHEN OTHER last")
        void allFiveEibaidArmsAreDriven() {
            Set<String> driven = new LinkedHashSet<>();
            for (ParityCase parityCase : declaredCases()) {
                String aid = parityCase.screenRequest().aid();
                if (aid != null) {
                    driven.add(aid);
                }
            }
            assertThat(driven)
                    .as("the four keys :119-128 names by mnemonic, plus one that it names nowhere so "
                            + "that WHEN OTHER at :129 is reached by falling through all four rather "
                            + "than by being addressed directly")
                    .containsAll(NAMED_AIDS)
                    .contains(UNNAMED_AID);
            assertThat(NAMED_AIDS)
                    .as("source order matters: ENTER first, then PF3, then PF7, then PF8. A switch that "
                            + "reordered them would still pass every case that presses one key at a "
                            + "time, which is why the order is asserted as a fact about the source")
                    .startsWith(ENTER_ARM.get(0))
                    .containsExactly("DFHENTER", "DFHPF3", "DFHPF7", "DFHPF8");
            assertThat(NAMED_AIDS).doesNotContain(UNNAMED_AID);
        }

        @Test
        @DisplayName("an unnamed key reaches WHEN OTHER and touches no file")
        void anUnnamedKeyReachesWhenOtherAndTouchesNoFile() {
            Observed observed = clean(10);
            assertThat(observed.work().errFlg())
                    .as("MOVE 'Y' TO WS-ERR-FLG at :130")
                    .isEqualTo(WorkArea.FLAG_YES);
            assertThat(observed.work().message())
                    .as("CCDA-MSG-INVALID-KEY, the fifty-character literal app/cpy/CSMSG01Y.cpy "
                            + "declares, moved into the eighty-byte WS-MESSAGE at :132")
                    .startsWith("Invalid key pressed. Please see below...")
                    .hasSize(WS_MESSAGE_WIDTH);
            assertThat(observed.work().sendCount()).isEqualTo(1);
            assertThat(observed.cursors())
                    .as("the WHEN OTHER arm issues no STARTBR, so no browse is ever positioned")
                    .isEmpty();
            assertThat(observed.work().readCount()).isZero();
            assertThat(PfKeyResolver.resolve(aidByteOf(UNNAMED_AID)))
                    .as("%s is a real attention identifier - it is not an unrecognised byte - so it "
                            + "reaches WHEN OTHER by not being one of the four the program names",
                            UNNAMED_AID)
                    .isPresent();
        }

        @Test
        @DisplayName("the ten-way selection EVALUATE is ordered: the earlier cell wins")
        void theEarlierSelectionCellWins() {
            Observed observed = clean(20);
            assertThat(observed.work().selectedRow())
                    .as("rows three and seven both carry 'S'. EVALUATE TRUE at :148 tests its WHEN "
                            + "clauses in source order and the FIRST match wins, so arm three matches "
                            + "and arms four to ten are never evaluated")
                    .isEqualTo(3);
            assertThat(observed.work().cursor().getTrnSelected())
                    .as("CDEMO-CT00-TRN-SELECTED takes TRNID03I at :157, not TRNID07I at :169. This is "
                            + "the ONE field that distinguishes an ordered implementation from an "
                            + "unordered one: every other assertion in this case passes either way")
                    .isEqualTo(keyImageOf(tranIdImage(4)))
                    .isNotEqualTo(keyImageOf(tranIdImage(9)));
            assertThat(observed.work().cursor().getTrnSelFlg()).isEqualTo("S");
        }

        @Test
        @DisplayName("a selection in a middle cell proves the chain is walked, not short-circuited")
        void aMiddleCellProvesTheChainIsWalked() {
            Observed observed = clean(19);
            assertThat(observed.work().selectedRow())
                    .as("arms one to four must all evaluate false before arm five at :161 matches")
                    .isEqualTo(5);
            assertThat(observed.work().cursor().getTrnSelected())
                    .isEqualTo(keyImageOf(tranIdImage(7)));
        }

        @Test
        @DisplayName("'S' and 's' are enumerated, not case-folded")
        void bothSelectorLettersAreEnumeratedRatherThanFolded() {
            assertThat(clean(18).work().cursor().getTrnSelFlg())
                    .as("WHEN 'S' at :186")
                    .isEqualTo("S");
            assertThat(clean(19).work().cursor().getTrnSelFlg())
                    .as("WHEN 's' at :187, a second WHEN clause sharing one body. There is no folding "
                            + "here, so any other letter falls to WHEN OTHER at :196 instead")
                    .isEqualTo("s");
        }
    }

    // =================================================================================================
    @Nested
    @DisplayName("Stateless navigation: every XCTL becomes a response field the client resolves")
    class StatelessNavigation {

        @Test
        @DisplayName("an accepted selection names COTRN01C and performs no server-side forward")
        void anAcceptedSelectionNamesTheNextProgram() {
            for (int ordinal : new int[]{18, 19, 20}) {
                Observed observed = clean(ordinal);
                assertThat(observed.response().getNextProgram())
                        .as("case%02d: EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) at :192-195 becomes a "
                                + "response field naming the target. Nothing is forwarded, nothing is "
                                + "redirected and no session is consulted - the client issues the next "
                                + "call", ordinal)
                        .isEqualTo(TRAN_VIEW_PGM);
                assertThat(observed.response().getNavigationContext().toProgram())
                        .as("case%02d: MOVE 'COTRN01C' TO CDEMO-TO-PROGRAM at :188", ordinal)
                        .isEqualTo(TRAN_VIEW_PGM);
                assertThat(observed.response().getNavigationContext().pgmContext())
                        .as("case%02d: MOVE 0 TO CDEMO-PGM-CONTEXT at :191, so the next program is "
                                + "entered in ENTER state rather than REENTER", ordinal)
                        .isZero();
                assertThat(observed.work().isTransferred())
                        .as("case%02d: control transferred, so the EXEC CICS RETURN at :138 is not "
                                + "reached", ordinal)
                        .isTrue();
                assertThat(observed.work().sendCount())
                        .as("case%02d: an accepted selection paints nothing - :206-229 is unreached",
                                ordinal)
                        .isZero();
                assertThat(observed.cursors())
                        .as("case%02d: and it opens no browse either", ordinal)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("PF3 names COMEN01C and returns the conversation to ENTER state")
        void pf3NamesTheMenuProgram() {
            Observed observed = clean(9);
            assertThat(observed.response().getNextProgram()).isEqualTo(MENU_PGM);
            assertThat(observed.response().getNavigationContext().fromTranid())
                    .as("MOVE WS-TRANID TO CDEMO-FROM-TRANID at :515")
                    .isEqualTo("CT00");
            assertThat(observed.response().getNavigationContext().fromProgram())
                    .as("MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM at :516")
                    .isEqualTo(PROGRAM);
            assertThat(observed.response().getNavigationContext().pgmContext())
                    .as("MOVE ZEROS TO CDEMO-PGM-CONTEXT at :517")
                    .isZero();
            assertThat(observed.work().sendCount()).isZero();
            assertThat(observed.cursors()).isEmpty();
        }

        @Test
        @DisplayName("no commarea sends control to COSGN00C without reading anything")
        void noCommareaSendsControlToSignOn() {
            Observed observed = clean(1);
            assertThat(observed.response().getNextProgram())
                    .as("MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM at :108, then RETURN-TO-PREV-SCREEN")
                    .isEqualTo(SIGNON_PGM);
            assertThat(observed.work().eibcalen()).isZero();
            assertThat(observed.work().sendCount()).isZero();
            assertThat(observed.work().readCount())
                    .as("TRANSACT was seeded with twelve records and not one of them was read: the "
                            + "guard at :107 returns before PROCESS-ENTER-KEY is ever performed")
                    .isZero();
            assertThat(observed.cursors()).isEmpty();
        }

        @Test
        @DisplayName("a path that transfers nowhere names the mapset it painted and returns to itself")
        void aPaintingPathReturnsToItself() {
            Observed observed = clean(2);
            assertThat(observed.work().isTransferred()).isFalse();
            assertThat(observed.response().getNextProgram())
                    .as("CDEMO-TO-PROGRAM is PIC X(8) and a path that transfers nowhere leaves it "
                            + "spaces, which is the absence of a target rather than a target")
                    .isBlank();
            assertThat(observed.response().getNextMapset())
                    .isEqualTo(TransactionListResponse.MAPSET_NAME)
                    .isEqualTo("COTRN00");
            assertThat(observed.response().getNextMap())
                    .isEqualTo(TransactionListResponse.MAP_NAME)
                    .isEqualTo("COTRN0A");
        }
    }

    // =================================================================================================
    @Nested
    @DisplayName("The 58-byte CDEMO-CT00-INFO extension, which is not part of the shared commarea")
    class CommareaExtension {

        @Test
        @DisplayName("the six fields are 16, 16, 8, 1, 1 and 16 bytes, summing to 58")
        void theSixFieldWidthsSumToFiftyEight() {
            assertThat(TransactionListCursor.TRNID_FIRST_LENGTH)
                    .as("10 CDEMO-CT00-TRNID-FIRST PIC X(16) - app/cbl/COTRN00C.cbl:63")
                    .isEqualTo(16);
            assertThat(TransactionListCursor.TRNID_LAST_LENGTH)
                    .as("10 CDEMO-CT00-TRNID-LAST PIC X(16) - :64")
                    .isEqualTo(16);
            assertThat(TransactionListCursor.PAGE_NUM_LENGTH)
                    .as("10 CDEMO-CT00-PAGE-NUM PIC 9(08) - :65")
                    .isEqualTo(8);
            assertThat(TransactionListCursor.NEXT_PAGE_FLG_LENGTH)
                    .as("10 CDEMO-CT00-NEXT-PAGE-FLG PIC X(01) - :66")
                    .isEqualTo(1);
            assertThat(TransactionListCursor.TRN_SEL_FLG_LENGTH)
                    .as("10 CDEMO-CT00-TRN-SEL-FLG PIC X(01) - :69")
                    .isEqualTo(1);
            assertThat(TransactionListCursor.TRN_SELECTED_LENGTH)
                    .as("10 CDEMO-CT00-TRN-SELECTED PIC X(16) - :70")
                    .isEqualTo(16);
            assertThat(TransactionListCursor.CURSOR_LENGTH)
                    .as("16 + 16 + 8 + 1 + 1 + 16")
                    .isEqualTo(58)
                    .isEqualTo(PaginationCursor.CURSOR_LENGTH);
            assertThat(TransactionListCursor.LAYOUT.recordLength())
                    .as("and the layout that serialises it declares the same width, every byte "
                            + "accounted for")
                    .isEqualTo(58);
        }

        @Test
        @DisplayName("the shared commarea stays exactly 160 bytes, and the two are added not merged")
        void theSharedCommareaIsNotWidened() {
            assertThat(NavigationContext.COMMAREA_LENGTH)
                    .as("app/cpy/COCOM01Y.cpy is copied by all seventeen online programs. Folding "
                            + "COTRN00C's paging cursor into it would widen a structure twelve other "
                            + "programs share, and every one of their commarea images would move")
                    .isEqualTo(160);
            assertThat(TransactionListCursor.COMMAREA_WITH_CURSOR_LENGTH)
                    .as("160 + 58: what MOVE DFHCOMMAREA(1:EIBCALEN) at :111 actually copies")
                    .isEqualTo(218)
                    .isEqualTo(PaginationCursor.COMMAREA_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH + TransactionListCursor.CURSOR_LENGTH);
        }

        @Test
        @DisplayName("every case reports the extension at those exact widths")
        void everyCaseReportsTheExtensionAtItsDeclaredWidths() {
            Map<String, Integer> declared = new LinkedHashMap<>();
            declared.put(TransactionListCursor.TRNID_FIRST_FIELD,
                    TransactionListCursor.TRNID_FIRST_LENGTH);
            declared.put(TransactionListCursor.TRNID_LAST_FIELD,
                    TransactionListCursor.TRNID_LAST_LENGTH);
            declared.put(TransactionListCursor.PAGE_NUM_FIELD,
                    TransactionListCursor.PAGE_NUM_LENGTH);
            declared.put(TransactionListCursor.NEXT_PAGE_FLG_FIELD,
                    TransactionListCursor.NEXT_PAGE_FLG_LENGTH);
            declared.put(TransactionListCursor.TRN_SEL_FLG_FIELD,
                    TransactionListCursor.TRN_SEL_FLG_LENGTH);
            declared.put(TransactionListCursor.TRN_SELECTED_FIELD,
                    TransactionListCursor.TRN_SELECTED_LENGTH);

            for (ParityCase parityCase : declaredCases()) {
                Map<String, String> navigation = parityCase.expectedResponse().navigation();
                for (Map.Entry<String, Integer> field : declared.entrySet()) {
                    assertThat(navigation.get(field.getKey()))
                            .as("%s: %s is %d bytes wide and the case must state it at that width, "
                                    + "space padded or zero filled as its PICTURE requires",
                                    parityCase.caseId(), field.getKey(), field.getValue())
                            .isNotNull()
                            .hasSize(field.getValue());
                }
                assertThat(navigation.get(TransactionListCursor.NEXT_PAGE_FLG_FIELD))
                        .as("%s: the 88-level condition names at :67-68 declare only Y and N",
                                parityCase.caseId())
                        .isIn(TransactionListCursor.NEXT_PAGE_YES, TransactionListCursor.NEXT_PAGE_NO);
            }
        }
    }

    // =================================================================================================
    @Nested
    @DisplayName("The 350-byte TRAN-RECORD, FILLER included")
    class RecordGeometry {

        @Test
        @DisplayName("the declared width is 350 and the spans account for every byte of it")
        void theDeclaredWidthIsThreeHundredAndFifty() {
            assertThat(TranRecord.RECORD_LENGTH)
                    .as("app/cpy/CVTRA05Y.cpy says RECLN = 350 in its own header comment, and the "
                            + "fourteen fields it declares sum to it")
                    .isEqualTo(350);
            assertThat(TranRecord.sumOfDeclaredSpanLengths())
                    .as("the sum of the declared spans, FILLER included. Omit the FILLER and this is "
                            + "330, which is the failure mode a total-width check exists to catch: "
                            + "every offset after the omission would be wrong and every field before "
                            + "it would still look right")
                    .isEqualTo(TranRecord.RECORD_LENGTH);
            assertThat(TranRecord.LAYOUT.recordLength()).isEqualTo(350);
            assertThat(TranRecord.FILLER_OFFSET + TranRecord.FILLER_LENGTH)
                    .as("05 FILLER PIC X(20) is the last field, so its span ends at the record's end")
                    .isEqualTo(TranRecord.RECORD_LENGTH);
            assertThat(TranRecord.FILLER_LENGTH).isEqualTo(20);
            assertThat(TranRecord.TRAN_ID_OFFSET).isZero();
            assertThat(TranRecord.TRAN_ID_KEY_LENGTH)
                    .as("TRAN-ID is the KSDS key and the RIDFLD every STARTBR positions on")
                    .isEqualTo(16);
        }

        @Test
        @DisplayName("every seeded row is exactly 350 bytes and round-trips byte for byte")
        void everySeededRowIsExactlyOneRecordWide() {
            for (ParityCase parityCase : declaredCases()) {
                ParityCase.DatasetInput input = parityCase.inputs().get(TRANSACT);
                assertThat(input).isNotNull();
                for (String row : input.rows()) {
                    assertThat(row)
                            .as("%s: a row that is not exactly %d characters would shift every field "
                                    + "offset after the shortfall", parityCase.caseId(),
                                    TranRecord.RECORD_LENGTH)
                            .hasSize(TranRecord.RECORD_LENGTH);
                    TranRecord record = TranRecord.decode(row, CHARSET);
                    assertThat(record.rawImage())
                            .as("%s: the record area is retained verbatim, so encoding it again "
                                    + "reproduces the stored bytes exactly - sign overpunches included",
                                    parityCase.caseId())
                            .hasSize(TranRecord.RECORD_LENGTH);
                    assertThat(record.displayImage()).isEqualTo(row);
                    assertThat(record.filler())
                            .as("%s: FILLER is written as spaces, never left unwritten and never "
                                    + "dropped", parityCase.caseId())
                            .hasSize(TranRecord.FILLER_LENGTH)
                            .isBlank();
                }
            }
        }

        @Test
        @DisplayName("the declared-empty seed carries the width no row could measure")
        void theEmptySeedCarriesItsDeclaredWidth() {
            ParityCase.DatasetInput input = caseNumber(5).inputs().get(TRANSACT);
            assertThat(input.rows()).isEmpty();
            assertThat(input.recordLength())
                    .as("a dataset holding no row has no row to measure, so the width is declared. "
                            + "That is how an empty TRANSACT is told apart from an empty 430-byte "
                            + "reject file or an empty 133-byte report")
                    .isEqualTo(TranRecord.RECORD_LENGTH);
            assertThat(input.copybook()).isEqualTo("CVTRA05Y");
        }
    }

    // =================================================================================================
    @Nested
    @DisplayName("Messages: nine literals, two channels, and a truncation with a direction")
    class Messages {

        @Test
        @DisplayName("each of the six paging literals is emitted byte-exactly by its own path")
        void eachPagingLiteralComesFromItsOwnPath() {
            assertThat(clean(5).work().message())
                    .as("STARTBR's end-of-data arm at :608-609")
                    .isEqualTo(paddedMessage(MSG_AT_TOP));
            assertThat(clean(3).work().message())
                    .as("READNEXT's ENDFILE arm at :642-643")
                    .isEqualTo(paddedMessage(MSG_REACHED_BOTTOM));
            assertThat(clean(16).work().message())
                    .as("READPREV's ENDFILE arm at :676-677 - a THIRD top-of-page literal, distinct "
                            + "from STARTBR's and from PF7's")
                    .isEqualTo(paddedMessage(MSG_REACHED_TOP));
            assertThat(clean(7).work().message())
                    .as("STARTBR's WHEN OTHER arm at :615-616")
                    .isEqualTo(paddedMessage(MSG_UNABLE));
            assertThat(clean(8).work().message())
                    .as("READNEXT's WHEN OTHER arm at :649-650 - the same literal from a different "
                            + "paragraph, which is why both are driven")
                    .isEqualTo(paddedMessage(MSG_UNABLE));
            assertThat(clean(11).work().message())
                    .as("PROCESS-PF7-KEY's already-at-the-top text at :248-249")
                    .isEqualTo(paddedMessage(MSG_ALREADY_TOP));
            assertThat(clean(12).work().message())
                    .as("PROCESS-PF8-KEY's already-at-the-bottom text at :270-271")
                    .isEqualTo(paddedMessage(MSG_ALREADY_BOTTOM));
        }

        @Test
        @DisplayName("MOVE WS-MESSAGE TO ERRMSGO truncates on the RIGHT, from 80 to 78")
        void theMessageTruncatesOnTheRight() {
            FixedWidthCodec codec = new FixedWidthCodec(CHARSET);
            StringBuilder probe = new StringBuilder();
            for (int position = 0; position < WS_MESSAGE_WIDTH; position++) {
                probe.append((char) ('A' + position % 26));
            }
            String eighty = codec.movePicX(probe.toString(), WS_MESSAGE_WIDTH);
            String seventyEight = codec.movePicX(eighty, TransactionListResponse.ERRMSG_LENGTH);
            assertThat(seventyEight)
                    .as("MOVE WS-MESSAGE TO ERRMSGO OF COTRN0AO at :531 moves PIC X(80) into PIC "
                            + "X(78). COBOL truncates an alphanumeric MOVE on the RIGHT, so the two "
                            + "characters that are lost are the LAST two - not the first two. Getting "
                            + "the direction wrong would shift every visible character of every "
                            + "message by two positions while keeping the length correct")
                    .hasSize(TransactionListResponse.ERRMSG_LENGTH)
                    .isEqualTo(eighty.substring(0, TransactionListResponse.ERRMSG_LENGTH))
                    .isNotEqualTo(eighty.substring(WS_MESSAGE_WIDTH
                            - TransactionListResponse.ERRMSG_LENGTH));
        }

        @Test
        @DisplayName("every case's screen line is the leading 78 characters of its 80-byte message")
        void everyCasesScreenLineIsTheLeadingSeventyEight() {
            for (ParityCase parityCase : declaredCases()) {
                String eighty = parityCase.expectedMessages().get(0).text();
                String seventyEight = parityCase.expectedMessages().get(1).text();
                assertThat(seventyEight)
                        .as("%s: the two channels are related by the MOVE at :531, so the shorter is "
                                + "the leading %d characters of the longer", parityCase.caseId(),
                                TransactionListResponse.ERRMSG_LENGTH)
                        .isEqualTo(eighty.substring(0, TransactionListResponse.ERRMSG_LENGTH));
            }
        }

        @Test
        @DisplayName("the error line is only painted by a SEND, never by the MOVE at :102 alone")
        void theErrorLineIsOnlyPaintedByASend() {
            Observed transferred = clean(1);
            assertThat(transferred.work().sendCount()).isZero();
            assertThat(transferred.response().getErrmsgO())
                    .as("MOVE SPACES TO ERRMSGO at :103 leaves 78 spaces, and with no SEND the "
                            + "eighty-byte WS-MESSAGE is never moved over them")
                    .hasSize(TransactionListResponse.ERRMSG_LENGTH)
                    .isBlank();

            Observed painted = clean(11);
            assertThat(painted.work().sendCount()).isEqualTo(1);
            assertThat(painted.response().getErrmsgO())
                    .isEqualTo(paddedMessage(MSG_ALREADY_TOP)
                            .substring(0, TransactionListResponse.ERRMSG_LENGTH));
        }

        @Test
        @DisplayName("no send carries an attribute, because the program moves none")
        void noSendCarriesAnAttribute() {
            for (ParityCase parityCase : declaredCases()) {
                for (ParityCase.ScreenSend send : parityCase.expectedResponse().sends()) {
                    assertThat(send.attributes())
                            .as("%s: COTRN00C copies DFHBMSCA at :81 and never uses it, and it does "
                                    + "not copy CSSETATY at all, so no %s colour item is ever "
                                    + "assigned - not even %s, the red CSSETATY would have moved",
                                    parityCase.caseId(), FieldAttributeSetter.COLOUR_ITEM_SUFFIX,
                                    Integer.toHexString(BmsAttributes.DFHRED & 0xFF))
                            .isEmpty();
                    assertThat(send.fields().keySet())
                            .as("%s: each send carries the six fields POPULATE-HEADER-INFO moves at "
                                    + ":571-586, which are the only part of COTRN0AO that is identical "
                                    + "on every send of one invocation", parityCase.caseId())
                            .containsExactlyInAnyOrder("TITLE01O", "TITLE02O", "TRNNAMEO", "PGMNAMEO",
                                    "CURDATEO", "CURTIMEO");
                }
            }
        }

        @Test
        @DisplayName("no field is highlighted on any path")
        void noFieldIsHighlightedOnAnyPath() {
            for (ParityCase parityCase : declaredCases()) {
                Observed observed = execute(parityCase);
                assertThat(observed.diffs().count()).isZero();
                for (String prefix : TransactionListResponse.fieldPrefixes()) {
                    assertThat(observed.response().isFieldHighlighted(prefix))
                            .as("%s: %s%s would have to be assigned for %s to be highlighted, and "
                                    + "COTRN00C assigns no attribute item anywhere",
                                    parityCase.caseId(), prefix,
                                    FieldAttributeSetter.COLOUR_ITEM_SUFFIX, prefix)
                            .isFalse();
                    assertThat(observed.response().attributesOf(prefix).colour())
                            .as("%s: and in particular it is not red", parityCase.caseId())
                            .isNotEqualTo(BmsAttributes.DFHRED);
                }
            }
        }
    }

    // =================================================================================================
    @Nested
    @DisplayName("File outcomes: every arm of every file paragraph, per call site")
    class FileOutcomes {

        @Test
        @DisplayName("STARTBR reports OK, end-of-file, not-found and a refusal across the suite")
        void everyStartbrArmIsReached() {
            assertThat(clean(2).work().startbrOutcome())
                    .as("WHEN DFHRESP(NORMAL) at :603")
                    .isEqualTo(FileStatus.Outcome.OK);
            assertThat(clean(5).work().startbrOutcome())
                    .as("an empty file: the positioning probe finds nothing, which reaches the same "
                            + "arm as NOTFND at :605")
                    .isEqualTo(FileStatus.Outcome.END_OF_FILE);
            assertThat(clean(6).work().startbrOutcome())
                    .as("WHEN DFHRESP(NOTFND) at :605, reached as NOTFND specifically")
                    .isEqualTo(FileStatus.Outcome.NOT_FOUND);
            assertThat(clean(7).work().startbrOutcome())
                    .as("WHEN OTHER at :612")
                    .isEqualTo(FileStatus.Outcome.OTHER);
        }

        @Test
        @DisplayName("READNEXT and READPREV each report OK, ENDFILE and a refusal")
        void everyReadArmIsReached() {
            assertThat(clean(14).work().lastReadOutcome())
                    .as("a full middle page ends on the look-ahead READNEXT at :308, which found a "
                            + "record - WHEN DFHRESP(NORMAL) at :637")
                    .isEqualTo(FileStatus.Outcome.OK);
            assertThat(clean(3).work().lastReadOutcome())
                    .as("WHEN DFHRESP(ENDFILE) at :639, from READNEXT")
                    .isEqualTo(FileStatus.Outcome.END_OF_FILE);
            assertThat(clean(8).work().lastReadOutcome())
                    .as("WHEN OTHER at :646, from READNEXT")
                    .isEqualTo(FileStatus.Outcome.OTHER);
            assertThat(clean(15).work().lastReadOutcome())
                    .as("WHEN DFHRESP(NORMAL) at :671, from READPREV - the extra read at :360 found "
                            + "record ten")
                    .isEqualTo(FileStatus.Outcome.OK);
            assertThat(clean(16).work().lastReadOutcome())
                    .as("WHEN DFHRESP(ENDFILE) at :673, from READPREV")
                    .isEqualTo(FileStatus.Outcome.END_OF_FILE);
        }

        @Test
        @DisplayName("the skip-read guards name different key sets in the two directions")
        void theSkipReadGuardsNameDifferentKeySets() {
            assertThat(clean(2).work().readCount())
                    .as("ENTER satisfies 'IF EIBAID NOT = DFHENTER AND DFHPF7 AND DFHPF3' at :285 "
                            + "falsely, so the extra READNEXT at :286 is SKIPPED: ten reads paint the "
                            + "page and one more looks ahead, so eleven in all and record one is not "
                            + "consumed")
                    .isEqualTo(11);
            assertThat(clean(14).work().readCount())
                    .as("PF8 does NOT satisfy it, so :286 runs and consumes the anchor record: one "
                            + "anchor read, ten painting reads and one look-ahead is twelve. Skip that "
                            + "first read and the new page would repeat the row already shown")
                    .isEqualTo(12);
            assertThat(clean(15).work().readCount())
                    .as("PF7 against the BACKWARD guard at :339, which names DFHENTER and DFHPF8 and "
                            + "so does not exempt PF7: one anchor read, ten painting reads and the "
                            + "extra read at :360 is twelve")
                    .isEqualTo(12);
        }

        @Test
        @DisplayName("a refused STARTBR bypasses the whole paragraph, including ENDBR at :322")
        void aRefusedStartbrBypassesTheParagraph() {
            Observed observed = clean(7);
            assertThat(observed.work().errFlg())
                    .as("MOVE 'Y' TO WS-ERR-FLG at :614 - the only STARTBR arm that sets it")
                    .isEqualTo(WorkArea.FLAG_YES);
            assertThat(observed.work().sendCount())
                    .as("two sends. One is :618's, from the failure arm itself; the other is :116's, "
                            + "which MAIN-PARA performs unconditionally after PROCESS-ENTER-KEY on the "
                            + "first-entry path. The SEND at :326 is the one that is unreachable, "
                            + "because :283 returned first")
                    .isEqualTo(2);
            assertThat(observed.cursors()).hasSize(1);
            assertThat(observed.cursors().get(0).reads())
                    .as("only the positioning probe was served; no read of the browse followed it")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("an end-of-data position does NOT set the error flag, so paging continues")
        void anEndOfDataPositionDoesNotSetTheErrorFlag() {
            for (int ordinal : new int[]{5, 6}) {
                Observed observed = clean(ordinal);
                assertThat(observed.work().isErrFlgOff())
                        .as("case%02d: the arm at :605-611 sets TRANSACT-EOF and a message but never "
                                + "WS-ERR-FLG, which is precisely why :283 lets execution reach ENDBR "
                                + "at :322 and the second SEND at :326", ordinal)
                        .isTrue();
                assertThat(observed.work().isTransactEof()).isTrue();
                assertThat(observed.work().sendCount())
                        .as("case%02d: three sends - one from the arm at :611, one from :326, and one "
                                + "from :116, which MAIN-PARA performs after PROCESS-ENTER-KEY on the "
                                + "first-entry path", ordinal)
                        .isEqualTo(3);
            }
        }
    }

    // =================================================================================================
    @Nested
    @DisplayName("Read-only access: the screen browses TRANSACT and mutates nothing")
    class ReadOnlyAccess {

        @Test
        @DisplayName("no case calls a mutating repository method, or any keyed read")
        void noCaseMutatesTheFile() {
            for (ParityCase parityCase : declaredCases()) {
                Observed observed = execute(parityCase);
                assertThat(observed.diffs().count()).isZero();
                TransactionRepository repository = observed.repository();

                verify(repository, never()).write(any(TranRecord.class));
                verify(repository, never()).readByTranId(anyString());
                verify(repository, never()).readForUpdateByTranId(anyString());
                verify(repository, never()).openOutput();
                verify(repository, never()).openInput();

                assertThat(parityCase.expectedWrites())
                        .as("%s: and the case agrees, which is the other half of the same statement",
                                parityCase.caseId())
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("the file is reached only through the repository, never by dataset name")
        void theFileIsReachedOnlyThroughTheRepository() {
            assertThat(TRANSACT)
                    .as("WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT' at :39 is the CICS file name, "
                            + "which is a binding key and not a dataset name. The fully qualified "
                            + "dataset name lives in application.yml and reaches no Java source")
                    .isEqualTo("TRANSACT")
                    .hasSize(TransactionRepository.CICS_FILE_NAME_LENGTH)
                    .doesNotContain(".");
        }

        @Test
        @DisplayName("a path that opens no browse issues no repository call at all")
        void aPathThatOpensNoBrowseIssuesNoCall() {
            for (int ordinal : new int[]{1, 9, 10, 11, 12, 18, 19, 20}) {
                Observed observed = clean(ordinal);
                assertThat(observed.cursors())
                        .as("case%02d: no STARTBR is issued on this path, so the file is never "
                                + "positioned", ordinal)
                        .isEmpty();
                assertThat(observed.work().readCount())
                        .as("case%02d: and nothing is read", ordinal)
                        .isZero();
                assertThat(observed.work().startbrOutcome())
                        .as("case%02d: with no STARTBR there is no STARTBR outcome to record", ordinal)
                        .isNull();
            }
        }
    }

    // =================================================================================================
    @Nested
    @DisplayName("Statelessness: the conversation lives in the payload and nowhere else")
    class Statelessness {

        @Test
        @DisplayName("the controller holds no static mutable state")
        void theControllerHoldsNoStaticMutableState() {
            assertNoStaticMutableState(TransactionMenuController.class);
            assertNoStaticMutableState(WorkArea.class);
            assertNoStaticMutableState(TransactionListRequest.class);
            assertNoStaticMutableState(TransactionListResponse.class);
        }

        @Test
        @DisplayName("two invocations on ONE controller instance share nothing but their payloads")
        void twoInvocationsOnOneInstanceShareNothing() {
            // The same seam the parity gate uses, but with a single controller reused across two
            // different cases. A controller that cached a page, a cursor or a work area would make the
            // second result depend on the first, and one of the two comparisons would fail.
            ParityHarness harness = ParityHarness.usAscii();
            ParityCase middlePage = caseNumber(14);
            ParityCase firstPage = caseNumber(2);

            AtomicReference<TransactionMenuController> shared = new AtomicReference<>();
            FieldDiffer.DiffResult firstRun = harness.judge(middlePage, UNIT_KIND,
                    invocation -> runOn(sharedController(shared, invocation), invocation));
            FieldDiffer.DiffResult secondRun = harness.judge(firstPage, UNIT_KIND,
                    invocation -> runOn(sharedController(shared, invocation), invocation));

            assertThat(firstRun.count())
                    .as("case14 on a fresh controller:%n%s", firstRun.render())
                    .isZero();
            assertThat(secondRun.count())
                    .as("case02 on the SAME controller instance, immediately after case14 paged to "
                            + "page two. If any paging state had survived the first call, page one "
                            + "would come back wrong here:%n%s", secondRun.render())
                    .isZero();
            assertThat(shared.get())
                    .as("both runs really did use one instance, which is what makes the second "
                            + "assertion mean anything")
                    .isNotNull();
        }

        @Test
        @DisplayName("a case run twice produces an identical fingerprint")
        void aCaseRunTwiceProducesTheSameFingerprint() {
            for (int ordinal : new int[]{2, 13, 15, 16}) {
                ParityCase parityCase = caseNumber(ordinal);
                Observed first = execute(parityCase);
                Observed second = execute(parityCase);
                assertThat(first.diffs().count()).isZero();
                assertThat(second.diffs().count()).isZero();
                assertThat(second.response().payloadFieldValues())
                        .as("case%02d: the run is deterministic. The clock is pinned, the seed is "
                                + "declared and nothing is read from the host, so a second run of the "
                                + "same case paints the same fifty-nine fields", ordinal)
                        .isEqualTo(first.response().payloadFieldValues());
                assertThat(second.work().sendCount()).isEqualTo(first.work().sendCount());
                assertThat(second.work().cursor().getPageNum())
                        .isEqualTo(first.work().cursor().getPageNum());
            }
        }

        @Test
        @DisplayName("EIBCALEN is 218 whenever a commarea travels, and 0 when none does")
        void eibcalenAgreesWithWhatTheCommareaWeighs() {
            for (ParityCase parityCase : declaredCases()) {
                int eibcalen = parityCase.screenRequest().eibcalen();
                boolean carried = !parityCase.screenRequest().commarea().isEmpty();
                assertThat(eibcalen)
                        .as("%s: MOVE DFHCOMMAREA(1:EIBCALEN) at :111 copies the 160-byte shared area "
                                + "plus the 58-byte extension, so a commarea-bearing request weighs "
                                + "exactly 218 and one without weighs nothing", parityCase.caseId())
                        .isEqualTo(carried ? TransactionListCursor.COMMAREA_WITH_CURSOR_LENGTH : 0);
            }
        }

        /** Constructs the shared controller on first use and returns the same instance afterwards. */
        private TransactionMenuController sharedController(
                AtomicReference<TransactionMenuController> holder,
                ParityHarness.Invocation invocation) {
            if (holder.get() == null) {
                holder.set(new TransactionMenuController(
                        stubbedTransactFile(invocation, new ArrayList<>()),
                        new FixedWidthCodec(invocation.charset()), invocation.clock()));
            }
            return holder.get();
        }

        /**
         * Runs one invocation against an already-constructed controller.
         *
         * <p>The repository the controller was built with belongs to the FIRST invocation, which is
         * the point: a controller that had captured per-request state would carry it here, and the
         * seeded data it browses is the first case's. Only case14's own run is judged against case14,
         * so this seam is used exclusively by the shared-instance assertion above.
         */
        private ParityHarness.UnitOutcome runOn(TransactionMenuController controller,
                                                ParityHarness.Invocation invocation) {
            FixedWidthCodec codec = new FixedWidthCodec(invocation.charset());
            TransactionListRequest request = requestOf(invocation);
            WorkArea ws = new WorkArea();
            TransactionListResponse response = controller.listTransactions(request,
                    aidByteOf(invocation.aid()), ws);
            return invocation.recorder()
                    .response(observedResponseOf(ws, response, codec))
                    .returnCode(0)
                    .message(new ParityCase.EmittedMessage(
                            ParityCase.MessageChannel.WS_MESSAGE_80, ws.message()))
                    .message(new ParityCase.EmittedMessage(
                            ParityCase.MessageChannel.SCREEN_ERRMSG_78, response.getErrmsgO()))
                    .build();
        }

        /** Fails if a type declares a static field that is not final. */
        private void assertNoStaticMutableState(Class<?> type) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("%s.%s is static and not final. COBOL WORKING-STORAGE is per-task, so a "
                                + "static equivalent would leak one request's paging state into "
                                + "another's and make test order significant", type.getSimpleName(),
                                field.getName())
                        .isTrue();
            }
        }
    }

    // =================================================================================================
    @Nested
    @DisplayName("Scope and provenance: what this suite may contain, and where its numbers came from")
    class ScopeAndProvenance {

        @Test
        @DisplayName("no case file names a mainframe dataset")
        void noCaseFileNamesAMainframeDataset() {
            for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
                String resource = ParityHarness.caseResourcePath(PROGRAM,
                        ParityHarness.caseId(ordinal));
                String raw = readResource(resource);
                assertThat(raw)
                        .as("%s: a dataset is addressed by its binding key - TRANSACT - and the fully "
                                + "qualified name lives in application.yml. A literal here would put "
                                + "one back into source", resource)
                        .doesNotContain("AWS.M2.CARDDEMO")
                        .doesNotContain("VSAM.KSDS");
                assertThat(raw)
                        .as("%s: and no credential of any shape belongs in a fixture", resource)
                        .doesNotContain("PASSWORD")
                        .doesNotContain("password");
            }
        }

        @Test
        @DisplayName("every case states the source lines its expectation was derived from")
        void everyCaseStatesItsDerivation() {
            for (ParityCase parityCase : declaredCases()) {
                assertThat(parityCase.description())
                        .as("%s: the baseline is statically derived rather than captured, so a "
                                + "reviewer must be able to check the derivation against "
                                + "app/cbl/COTRN00C.cbl. A description that cites no line is not "
                                + "reviewable", parityCase.caseId())
                        .contains(":")
                        .hasSizeGreaterThan(200);
            }
        }

        @Test
        @DisplayName("the suite reaches the file only through the mandated dependencies")
        void theSuiteReachesTheFileOnlyThroughItsDependencies() {
            // Stated as an assertion rather than as a comment so that adding an HTTP layer, a Spring
            // context or a job launcher to this suite has somewhere to fail. Each of the four types
            // below is the one this file is allowed to reach TRANSACT and COTRN00C through.
            assertThat(TransactionMenuController.TRANSACTIONS_PATH)
                    .as("the REST projection of transaction CT00, declared by the controller itself. "
                            + "This suite never issues a request to it - the mapping is asserted, not "
                            + "exercised, because a servlet container between the assertion and the "
                            + "arithmetic would add failures that look like parity failures")
                    .isEqualTo("/api/transactions");
            assertThat(TransactionListRequest.TRANSACTION_ID)
                    .isEqualTo("CT00")
                    .isEqualTo(TransactionListResponse.TRANSACTION_ID);
            assertThat(TransactionListRequest.PROGRAM_NAME)
                    .isEqualTo(PROGRAM)
                    .isEqualTo(TransactionListResponse.PROGRAM_NAME);
            assertThat(TransactionListRequest.MAPSET_NAME).isEqualTo("COTRN00");
            assertThat(TransactionListRequest.MAP_NAME).isEqualTo("COTRN0A");
            assertThat(Optional.of(CHARSET).map(Charset::name))
                    .as("the code page is named, never defaulted")
                    .contains("US-ASCII");
        }

        /** Reads one classpath resource as UTF-8, which is how the case files are stored. */
        private String readResource(String resource) {
            try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertThat(stream).as("resource %s must exist on the test classpath", resource)
                        .isNotNull();
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                throw new UncheckedIOException("case resource " + resource + " is unreadable",
                        unreadable);
            }
        }
    }
}
