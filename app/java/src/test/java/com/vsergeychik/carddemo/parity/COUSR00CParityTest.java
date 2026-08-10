package com.vsergeychik.carddemo.parity;

import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedResponse;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedSend;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetNormalisation;
import com.vsergeychik.carddemo.parity.ParityCase.ForcedOutcome;
import com.vsergeychik.carddemo.parity.ParityCase.MessageChannel;
import com.vsergeychik.carddemo.parity.ParityCase.Normalisation;
import com.vsergeychik.carddemo.parity.ParityCase.RepositoryOperation;
import com.vsergeychik.carddemo.parity.ParityCase.ScreenSend;
import com.vsergeychik.carddemo.parity.ParityCase.Termination;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.parity.ParityHarness.Invocation;
import com.vsergeychik.carddemo.parity.ParityHarness.SeededDataset;
import com.vsergeychik.carddemo.parity.ParityHarness.UnitOutcome;
import com.vsergeychik.carddemo.user.SecUserRepository;
import com.vsergeychik.carddemo.user.UserMenuController;
import com.vsergeychik.carddemo.user.dto.UserListRequest;
import com.vsergeychik.carddemo.user.dto.UserListResponse;
import com.vsergeychik.carddemo.user.model.SecUserRecord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The twenty-case behavioural-parity gate for {@code app/cbl/COUSR00C.cbl}, the {@code CU00} user-list
 * screen, run against {@link UserMenuController}.
 *
 * <h2>What this class asserts, and what "zero" means</h2>
 * <p>Each of the twenty cases under {@code src/test/resources/parity/COUSR00C/} declares one path
 * through the program field by field: the pre-state of {@code USRSEC}, the AID, the inbound
 * communication area, the received map items, and then - as the expectation - all sixteen
 * {@code app/cpy/COCOM01Y.cpy} fields plus all six of the 34-byte {@code CDEMO-CU00-INFO} extension,
 * every one of the <strong>59</strong> {@code DFHMDF} fields of the transmission the terminal is left
 * displaying, the cursor position, how the transaction ended, the {@code RETURN-CODE} and the final
 * state of {@code USRSEC} row by row at its full 80-byte width. {@link FieldDiffer} compares field by
 * field rather than as whole strings, and the gate is that the diff count is <strong>zero for all
 * twenty</strong>: nineteen clean cases and one difference means this module is incomplete, not
 * ninety-five per cent done.
 *
 * <h2>Baseline provenance - statically derived, never captured (practice B12, risk R-A)</h2>
 * <p>Every expected value in those twenty files was <strong>derived by structured reading of the COBOL
 * paragraphs</strong> and cross-checked against four authoritative sources: the byte layout of
 * {@code app/cpy/CSUSR01Y.cpy} and {@code app/cpy/COCOM01Y.cpy}, the {@code xxxI PICTURE} clauses of
 * {@code app/cpy-bms/COUSR00.CPY}, the {@code DFHMDF} definitions of {@code app/bms/COUSR00.bms}, and
 * the ten in-stream records of {@code app/jcl/DUSRSECJ.jcl}. They were <strong>not</strong> captured
 * from, recorded against or replayed from any execution of the legacy program, and nothing here should
 * be read as though they were: executing it is impossible in this environment - there is no z/OS or
 * CICS runtime, the available COBOL compiler reports its indexed file handler disabled, and
 * {@code DFHAID} and {@code DFHBMSCA} are absent from the repository. That substitutes the provenance
 * of the expected values and nothing else; the twenty cases, the field-for-field diffing, the
 * diff-count-of-zero gate and the branch-coverage bar are all preserved, and the deviation is
 * escalated rather than absorbed.
 *
 * <h2>The name says Menu; the program lists users (rule R1)</h2>
 * <p>The migration plan mandates the class name {@link UserMenuController}. The source disagrees with
 * it: the {@code Function :} header of {@code app/cbl/COUSR00C.cbl:5} reads "List all users from USRSEC
 * file", and {@code README.md:213-231} documents transaction {@code CU00} as "List Users". This is a
 * <strong>paginated list screen, not a menu</strong> - there is no option table, no option number and
 * no dispatch on one anywhere in the program. Rule R1 governs: the name comes from the plan and the
 * behaviour comes from the source, so the divergence is documented here rather than acted on, and no
 * assertion below expects menu-shaped behaviour.
 *
 * <h2>Page size ten is behaviour, not configuration (gate G39)</h2>
 * <p>The literals {@code 10} and {@code 11} are hard-coded at {@code :293}, {@code :300}, {@code :347}
 * and {@code :352}, and {@code 02 USER-REC OCCURS 10 TIMES} at {@code :57} fixes the table. Ten is
 * therefore an observable property of the program and not a tunable:
 * {@link #pageSizeIsTenAndNotConfigurable()} proves it from the fixtures - over a twenty-five-record
 * file exactly ten rows are filled and rows eight, nine and ten are among them, so the same cases
 * could not pass at a page size of seven.
 *
 * <h2>Do not copy an expectation from {@code COTRN00C} (the key insight)</h2>
 * <p>{@code COUSR00C} and {@code COTRN00C} are structural twins - 59 {@code DFHMDF} fields each, page
 * size ten each, near-identical paging shape - but their communication-area extensions differ in
 * width: {@code CDEMO-CU00-INFO} is <strong>34</strong> bytes ({@code :67-75}: two eight-byte
 * identifiers, a {@code PIC 9(08)} page number, two one-byte flags and an eight-byte selection) where
 * {@code CDEMO-CT00-INFO} is <strong>58</strong>. Moving an expectation between the two tests is the
 * most likely way to introduce a silent width difference, so
 * {@link #theThirtyFourByteExtensionKeepsItsDeclaredWidths()} pins every span of it, and
 * {@link NavigationContext#COMMAREA_LENGTH} is asserted to be unchanged at 160 so nothing was added to
 * the shared area to make an extension fit.
 *
 * <h2>How the unit is reached: a plain Java object, never HTTP (gate G51)</h2>
 * <p>Every case declares {@link UnitKind#CONTROLLER_POJO}, and the adapter below constructs
 * {@link UserMenuController} through its three-argument constructor and calls {@code getUsers}
 * directly. Nothing in this file names, imports or constructs any of Spring's HTTP test harnesses -
 * no mock dispatcher harness, no test REST template, no reactive web test client - nor a servlet
 * container, nor Spring Batch's job-launching entry point: no request, no dispatcher, no filter
 * chain and no serialisation round trip sits between the assertion and the decision logic. The
 * {@code user} package declares a service for {@code COSGN00C} alone, so the four {@code COUSR0*}
 * programs keep their decision logic in their controllers and calling the handler is the only way to
 * reach it at all.
 *
 * <p>{@code USRSEC} is reached through a real {@link SecUserRepository} over an in-memory relation
 * seeded from the case, so the browse, its cursor and the whole {@code FILE STATUS} vocabulary are
 * exercised rather than stubbed. The one thing no shape of data can express - a file that exists and
 * cannot be reached - is declared by the case as a forced {@code startBrowse} outcome and honoured
 * here by pointing the repository at a relation that is not there.
 *
 * <h2>One transmission is observable, and that is stated rather than glossed</h2>
 * <p>Several paths through this program send the map more than once in one invocation: the
 * end-of-file arm of {@code READNEXT} sends from inside the paging loop at {@code :640} and
 * {@code PROCESS-PAGE-FORWARD} then sends again at {@code :329}, and a first entry sends once more at
 * {@code :119}. {@link UserMenuController} records all of them on its work area, but its
 * <strong>published</strong> handler exposes only the transmission the terminal is left displaying,
 * and widening that API is not available: {@code UserMenuControllerTest.onlyTheRequestMappingIsPublic}
 * pins the deliberate property that exactly one method beyond the constructors is public. So the
 * earlier transmissions are asserted there, in the {@code user} package where the work area is
 * visible, and every case here pins all 59 fields of the final one and names the earlier sends in its
 * own description. {@code WS-MESSAGE} is not cleared between sends, which is why the final image of
 * case02 carries the end-of-file message rather than spaces.
 *
 * <h2>The plaintext credential span is carried, and never printed (practice B6)</h2>
 * <p>{@code app/cpy/CSUSR01Y.cpy} declares {@code SEC-USR-PWD PIC X(08)} and the legacy design
 * compares it in plaintext, so the eighty-byte record images in every case's final state carry that
 * span byte for byte - hashing, masking or omitting it would change what the parity contract asserts.
 * Carrying it and printing it are different things: every diagnostic this file produces goes through
 * {@link DiffResult#render()}, which masks by field name through {@code ParityCase.Redaction}, and no
 * assertion message here formats a record image itself.
 *
 * @see ParityHarness which seeds the case, applies the 57-to-80 pad and captures the fingerprint
 * @see FieldDiffer which judges it field by field
 */
@DisplayName("COUSR00C parity - the CU00 user-list screen, twenty statically derived cases")
final class COUSR00CParityTest {

    /** The program whose {@code parity/COUSR00C/} directory this class is the gate for. */
    private static final String PROGRAM = "COUSR00C";

    /** The code page of the fixtures, named explicitly and never defaulted (practice B8). */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The one codec every width, pad and truncation assertion here goes through. */
    private static final FixedWidthCodec CODEC = new FixedWidthCodec(ASCII);

    /** The {@code USRSEC} binding key, taken from the repository rather than restated. */
    private static final String USRSEC = SecUserRepository.CICS_FILE_NAME;

    /**
     * The dataset name the test binding carries.
     *
     * <p>Deliberately <strong>not</strong> the production name that {@code app/csd/CARDDEMO.CSD}
     * declares for {@code FILE(USRSEC)}: gate G46 requires that no real dataset name is written into
     * Java at all - they are resolved from {@code application.yml} - and a name only this class uses
     * cannot be mistaken for one.
     */
    private static final String TEST_DSNAME = "TEST.USRSEC.PARITY.RELATION";

    /** The single record-image column of the test relation. */
    private static final String RECORD_IMAGE_COLUMN = "RECORD_IMAGE";

    /** {@code CSUSR01Y}'s record width. */
    private static final int RECORD_LENGTH = SecUserRecord.RECORD_LENGTH;

    /** {@code SEC-USR-ID}'s width, which is also the KSDS key length of {@code DUSRSECJ.jcl}. */
    private static final int KEY_LENGTH = SecUserRecord.KEY_LENGTH;

    /** Ten rows to a page - behaviour, asserted below, never configured. */
    private static final int PAGE_SIZE = 10;

    /** The five {@code xxxI} stems of one repeating row, in {@code app/bms/COUSR00.bms} order. */
    private static final List<String> ROW_STEMS = List.of("SEL", "USRID", "FNAME", "LNAME", "UTYPE");

    // =================================================================================================
    //  THE GATE
    // =================================================================================================

    /**
     * The twenty cases, loaded in {@code case01} through {@code case20} order.
     *
     * <p>{@link ParityHarness#casesOf(String)} is deliberately the only source: it refuses a directory
     * that does not hold exactly twenty readable cases <em>and</em> refuses any extra or misnamed file
     * in it, so a suite cannot quietly run four of twenty and satisfy "the diff count is zero" while
     * asking almost nothing. It is {@code static} and returns a fresh immutable list on each call, so
     * it introduces no shared mutable state (practice B9).
     *
     * @return the program's twenty cases, in ascending case order
     */
    static List<ParityCase> cases() {
        return ParityHarness.casesOf(PROGRAM);
    }

    /**
     * The gate itself: every case must produce a diff count of exactly zero.
     *
     * <p>The failure message is {@link DiffResult#render()}, which names each difference with its
     * dataset, row, field, offset, length, expected value, observed value and an explanation - and
     * masks every value by field name, so a failing build never puts a credential span or a customer
     * name into a log. Only the count is asserted, because the differ has already written a better
     * message than any assertion here could.
     *
     * @param parityCase one of the twenty
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("the field-for-field diff count is zero (gate G18)")
    void everyCaseDiffersInNothing(final ParityCase parityCase) {
        DiffResult diff = ParityHarness.usAscii()
                .judge(parityCase, UnitKind.CONTROLLER_POJO, invocation -> invoke(invocation));

        assertThat(diff.count())
                .withFailMessage("%s", diff.render())
                .isZero();
    }

    /**
     * The set is exactly {@code case01} through {@code case20}, and each case says what it pins.
     *
     * <p>Loud on any other count, which is the requirement stated as an assertion rather than left to
     * the loader: a case identifier out of range could not bind at all, and a case with no description
     * cannot be audited for coverage without re-reading the COBOL.
     */
    @Test
    @DisplayName("exactly twenty cases, case01 through case20, each with a description (gate G15)")
    void theCaseSetIsExactlyTwenty() {
        List<ParityCase> loaded = cases();

        assertThat(loaded)
                .as("the gate is stated as twenty cases per program, and %s declares %d",
                        PROGRAM, loaded.size())
                .hasSize(ParityHarness.CASES_PER_PROGRAM);
        List<String> identifiers = new ArrayList<>(loaded.size());
        for (ParityCase parityCase : loaded) {
            identifiers.add(parityCase.caseId());
            assertThat(parityCase.program()).isEqualTo(PROGRAM);
            assertThat(parityCase.unitKind()).isEqualTo(UnitKind.CONTROLLER_POJO);
            assertThat(parityCase.description()).isNotBlank();
            assertThat(parityCase.expectedReturnCode())
                    .as("%s: an online transaction does not abend, so the RETURN-CODE is 0",
                            parityCase.caseId())
                    .isZero();
            assertThat(parityCase.expectedWrites())
                    .as("%s: COUSR00C opens USRSEC for browse only and writes nothing",
                            parityCase.caseId())
                    .isEmpty();
        }
        List<String> expected = new ArrayList<>(ParityHarness.CASES_PER_PROGRAM);
        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            expected.add(ParityHarness.caseId(ordinal));
        }
        assertThat(identifiers).containsExactlyElementsOf(expected);
    }

    // =================================================================================================
    //  THE ADAPTER - how one case reaches UserMenuController, with no HTTP in the path (gate G51)
    // =================================================================================================

    /**
     * Constructs the controller as a plain Java object and calls its published handler once.
     *
     * <p>Four steps, in this order, because each depends on the one before: resolve the repository the
     * case asks for, assemble the request from the case's communication area and received map items,
     * narrow the case's {@code DFHAID} mnemonic to the {@code EIBAID} byte, and project the returned
     * body into the fingerprint the differ judges. Nothing about the expectation is read - the
     * {@link Invocation} carries the inputs only, by construction - so a unit cannot pass by reporting
     * what it was going to be compared against.
     *
     * @param invocation the seeded datasets, the online request, the pinned clock and the recorder
     * @return the outcome to be judged: the response, the unchanged dataset and a zero return code
     */
    private static UnitOutcome invoke(final Invocation invocation) {
        SeededDataset seeded = invocation.dataset(USRSEC);
        ScreenResponse<UserListResponse> response = callHandler(invocation, 1).get(0);

        UnitOutcome.Builder recorder = invocation.recorder();
        recorder.response(observedOf(response));
        // COUSR00C opens USRSEC for browse and writes nothing, so the dataset is reported unchanged
        // rather than left out: "the file is exactly as it was" is the assertion such a case makes,
        // and an omitted channel would assert nothing at all.
        recorder.finalStateUnchanged(seeded, SecUserRecord.LAYOUT);
        recorder.returnCode(0);
        return null;
    }

    /**
     * Builds the controller once and calls its handler {@code times} times over the same instance.
     *
     * <p>Calling more than once is how statelessness is proven rather than asserted: the controller,
     * the repository and the browse cursor are the same objects for every call, so if any of them
     * retained a page position, a work area or a decoded record between calls, the second response
     * would differ from the first. Every case uses {@code times = 1}; only
     * {@link #theHandlerRetainsNothingBetweenCalls()} uses two.
     *
     * @param invocation the run's inputs
     * @param times      how many times to call the handler over one controller
     * @return each response, in call order
     */
    private static List<ScreenResponse<UserListResponse>> callHandler(final Invocation invocation,
                                                                     final int times) {
        SeededDataset seeded = invocation.dataset(USRSEC);
        SecUserRepository repository = repositoryFor(invocation, seeded);
        UserMenuController controller =
                new UserMenuController(repository, CODEC, fixedClockOf(invocation));

        List<ScreenResponse<UserListResponse>> responses = new ArrayList<>(times);
        for (int call = 0; call < times; call++) {
            // A fresh request each time, assembled from the case rather than reused, because the
            // payload IS the conversation state and a caller would send it again unchanged.
            responses.add(controller.getUsers(requestOf(invocation), eibAidOf(invocation), null));
        }
        return responses;
    }

    /**
     * The repository the case asks for.
     *
     * <p>Two shapes, and the case decides which by declaring a forced outcome or not:
     * <ul>
     *   <li>ordinarily a relation holding the seeded rows, so {@code STARTBR} positions, the browse
     *       reads and the {@code FILE STATUS} vocabulary is the repository's own rather than a stub's.
     *       A dataset seeded empty produces a relation with no row, which is what makes
     *       {@code STARTBR} report {@code NOTFND} at {@code :600};</li>
     *   <li>a relation that does not exist when the case forces {@link FileStatus.Outcome#OTHER} on
     *       {@code startBrowse}, because no arrangement of rows can express a file that exists and
     *       cannot be reached - which is exactly the state the {@code WHEN OTHER} arm at {@code :607}
     *       branches on.
     * </ul>
     * Taking the declaration through {@link Invocation#forcedOutcome(RepositoryOperation)} is what
     * consumes it: a declared outcome nothing asks for forces nothing, and the harness fails the run
     * rather than let the case pass while claiming to have reached an arm it never did.
     *
     * @param invocation the run's inputs
     * @param seeded     the {@code USRSEC} rows the case declared
     * @return the repository the controller is constructed over
     */
    private static SecUserRepository repositoryFor(final Invocation invocation,
                                                   final SeededDataset seeded) {
        boolean unreachable = false;
        if (invocation.hasForcedOutcome(RepositoryOperation.START_BROWSE)) {
            ForcedOutcome forced = invocation.forcedOutcome(RepositoryOperation.START_BROWSE);
            assertThat(forced.outcome())
                    .as("this screen browses and never reads by key, so the only outcome worth "
                            + "forcing on startBrowse is the one no data shape can produce")
                    .isEqualTo(FileStatus.Outcome.OTHER);
            unreachable = true;
        }
        JdbcTemplate template = unreachable
                ? relation(invocation, null)
                : relation(invocation, seeded.rows());
        return new SecUserRepository(template, bindings(), ASCII, RecordImageForm.CHARACTER);
    }

    /**
     * A private in-memory relation for one case.
     *
     * <p>The database name is derived from the case identifier rather than from a counter, so the run
     * is deterministic (practice B7) and this class holds no mutable static state (practice B9): two
     * cases cannot see each other's rows, and the same case seeds the same database on every run.
     *
     * @param invocation the run, which supplies the case identifier
     * @param rows       the record images to insert in ascending key order, or {@code null} for a
     *                   database with no relation at all
     * @return a template over that database
     */
    private static JdbcTemplate relation(final Invocation invocation, final List<String> rows) {
        String database = "cousr00c_" + invocation.caseId() + (rows == null ? "_unreachable" : "");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.execute("DROP TABLE IF EXISTS \"" + TEST_DSNAME + '"');
        if (rows == null) {
            return template;
        }
        template.execute("CREATE TABLE \"" + TEST_DSNAME + "\" (" + RECORD_IMAGE_COLUMN
                + " VARCHAR(" + RECORD_LENGTH + "))");
        for (String row : rows) {
            template.update("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)", row);
        }
        return template;
    }

    /**
     * The one binding the repository resolves, at {@code CSUSR01Y}'s geometry.
     *
     * <p>The dataset name is configuration here exactly as it is in production (gate G46): the
     * repository is handed a catalogue and never a literal.
     *
     * @return a catalogue holding {@code USRSEC} alone
     */
    private static DatasetBindings bindings() {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(USRSEC, new DatasetBinding(TEST_DSNAME, DatasetBinding.KSDS, false, "FB", null,
                RECORD_LENGTH, "CSUSR01Y", KEY_LENGTH, null, null, null));
        return catalogue;
    }

    /**
     * The clock the case pins, refused if it ever ticks.
     *
     * <p>{@code POPULATE-HEADER-INFO} at {@code :564-581} stamps {@code CURDATEO} and {@code CURTIMEO}
     * from {@code FUNCTION CURRENT-DATE}, so a ticking clock would put the wall time into two of the
     * 59 pinned fields and the same case would answer differently on every run.
     *
     * @param invocation the run, whose clock the harness has already fixed
     * @return that clock
     */
    private static Clock fixedClockOf(final Invocation invocation) {
        Clock clock = invocation.clock();
        assertThat(clock)
                .as("the harness fixes every case's clock; a system clock would reach CURDATEO")
                .isEqualTo(Clock.fixed(clock.instant(), clock.getZone()));
        return clock;
    }

    // =================================================================================================
    //  REQUEST ASSEMBLY - the payload is the whole of the conversation state (rule R6, gate G37)
    // =================================================================================================

    /**
     * Assembles the request body from the case's communication area and received map items.
     *
     * <p>{@code EIBCALEN} of zero and a payload carrying no communication area are the same state -
     * "no area was passed" - and {@code :110} tests exactly that, so a case declaring
     * {@code eibcalen: 0} produces a request with none. Every other case produces one, and the
     * fourteen COMMAREA fields plus the six of the 34-byte extension arrive from the case rather than
     * from anything the server kept: there is no {@code HttpSession}, no {@code @SessionAttributes} and
     * no cache in the path, which is the whole of gate G37 as a construction rather than as a claim.
     *
     * @param invocation the run's inputs
     * @return the request body to hand the handler
     */
    private static UserListRequest requestOf(final Invocation invocation) {
        Map<String, String> received = invocation.mapFields();
        UserListRequest request = UserListRequest.empty()
                .withTrnName(picX(received, UserListRequest.TRNNAME_FIELD, UserListRequest.TRNNAME_LENGTH))
                .withTitle01(picX(received, UserListRequest.TITLE01_FIELD, UserListRequest.TITLE01_LENGTH))
                .withCurDate(picX(received, UserListRequest.CURDATE_FIELD, UserListRequest.CURDATE_LENGTH))
                .withPgmName(picX(received, UserListRequest.PGMNAME_FIELD, UserListRequest.PGMNAME_LENGTH))
                .withTitle02(picX(received, UserListRequest.TITLE02_FIELD, UserListRequest.TITLE02_LENGTH))
                .withCurTime(picX(received, UserListRequest.CURTIME_FIELD, UserListRequest.CURTIME_LENGTH))
                .withPageNum(picX(received, UserListRequest.PAGENUM_FIELD, UserListRequest.PAGENUM_LENGTH))
                .withUsrIdIn(picX(received, UserListRequest.USRIDIN_FIELD, UserListRequest.USRIDIN_LENGTH))
                .withErrMsg(picX(received, UserListRequest.ERRMSG_FIELD, UserListRequest.ERRMSG_LENGTH));

        for (int rowNumber = 1; rowNumber <= PAGE_SIZE; rowNumber++) {
            UserListRequest.UserListRow row = new UserListRequest.UserListRow(
                    picX(received, UserListRequest.selFieldName(rowNumber), UserListRequest.SEL_LENGTH),
                    picX(received, UserListRequest.usrIdFieldName(rowNumber), UserListRequest.USRID_LENGTH),
                    picX(received, UserListRequest.fnameFieldName(rowNumber), UserListRequest.FNAME_LENGTH),
                    picX(received, UserListRequest.lnameFieldName(rowNumber), UserListRequest.LNAME_LENGTH),
                    picX(received, UserListRequest.utypeFieldName(rowNumber), UserListRequest.UTYPE_LENGTH));
            request = request.withRow(rowNumber, row);
        }

        Map<String, String> commarea = invocation.commarea();
        request = request
                .withCdemoCu00UsrIdFirst(commareaPicX(commarea, UserListRequest.CU00_USRID_FIRST_FIELD,
                        UserListRequest.CU00_USRID_FIRST_LENGTH))
                .withCdemoCu00UsrIdLast(commareaPicX(commarea, UserListRequest.CU00_USRID_LAST_FIELD,
                        UserListRequest.CU00_USRID_LAST_LENGTH))
                .withCdemoCu00PageNum(commareaDigits(commarea, UserListRequest.CU00_PAGE_NUM_FIELD))
                .withCdemoCu00NextPageFlg(commarea.getOrDefault(
                        UserListRequest.CU00_NEXT_PAGE_FLG_FIELD, UserListRequest.NEXT_PAGE_NO))
                .withCdemoCu00UsrSelFlg(commareaPicX(commarea, UserListRequest.CU00_USR_SEL_FLG_FIELD,
                        UserListRequest.CU00_USR_SEL_FLG_LENGTH))
                .withCdemoCu00UsrSelected(commareaPicX(commarea, UserListRequest.CU00_USR_SELECTED_FIELD,
                        UserListRequest.CU00_USR_SELECTED_LENGTH));

        if (invocation.eibcalen() == 0) {
            return request.withoutNavigationContext();
        }
        return request.withNavigationContext(navigationOf(commarea));
    }

    /**
     * The inbound {@code CARDDEMO-COMMAREA}, assembled from the case's own field names.
     *
     * <p>Only the fields a case declares are set; every other one keeps
     * {@link NavigationContext#empty()}'s initial state, which is spaces for an alphanumeric item and
     * zeros for a {@code PIC 9} one - the state {@code WORKING-STORAGE} starts in. The program context
     * is the field that decides the whole shape of the invocation: {@code 0} is the {@code ENTER} state
     * that {@code :115} sends down the {@code MOVE LOW-VALUES} path and {@code 1} is
     * {@code CDEMO-PGM-REENTER}, which is what makes {@code :121}'s {@code RECEIVE} happen at all
     * (gate G38).
     *
     * @param commarea the case's declared communication-area fields
     * @return the assembled context
     */
    private static NavigationContext navigationOf(final Map<String, String> commarea) {
        NavigationContext context = NavigationContext.empty();
        for (Map.Entry<String, String> entry : commarea.entrySet()) {
            String field = entry.getKey();
            String value = entry.getValue();
            switch (field) {
                case NavigationContext.FROM_TRANID_FIELD -> context = context.withFromTranid(value);
                case NavigationContext.FROM_PROGRAM_FIELD -> context = context.withFromProgram(value);
                case NavigationContext.TO_TRANID_FIELD -> context = context.withToTranid(value);
                case NavigationContext.TO_PROGRAM_FIELD -> context = context.withToProgram(value);
                case NavigationContext.USER_ID_FIELD -> context = context.withUserId(value);
                case NavigationContext.USER_TYPE_FIELD -> context = context.withUserType(value);
                case NavigationContext.PGM_CONTEXT_FIELD ->
                        context = context.withPgmContext(Integer.parseInt(value.strip()));
                case NavigationContext.CUST_ID_FIELD ->
                        context = context.withCustId(Integer.parseInt(value.strip()));
                case NavigationContext.CUST_FNAME_FIELD -> context = context.withCustFname(value);
                case NavigationContext.CUST_MNAME_FIELD -> context = context.withCustMname(value);
                case NavigationContext.CUST_LNAME_FIELD -> context = context.withCustLname(value);
                case NavigationContext.ACCT_ID_FIELD ->
                        context = context.withAcctId(Long.parseLong(value.strip()));
                case NavigationContext.ACCT_STATUS_FIELD -> context = context.withAcctStatus(value);
                case NavigationContext.CARD_NUM_FIELD ->
                        context = context.withCardNum(Long.parseLong(value.strip()));
                case NavigationContext.LAST_MAP_FIELD -> context = context.withLastMap(value);
                case NavigationContext.LAST_MAPSET_FIELD -> context = context.withLastMapset(value);
                default -> assertThat(field)
                        .as("a commarea field a case declares must be one this program carries: "
                                + "either a COCOM01Y field or one of the six of CDEMO-CU00-INFO")
                        .startsWith("CDEMO-CU00-");
            }
        }
        return context;
    }

    /**
     * One received {@code xxxI} item at its declared width, or spaces when the case declares none.
     *
     * <p>Named by its {@code DFHMDF} label with the {@code I} suffix the symbolic map spells, and moved
     * through {@link FixedWidthCodec#movePicX(String, int)} so an over-long declaration truncates on
     * the right exactly as a COBOL alphanumeric {@code MOVE} does rather than being rejected here.
     *
     * @param received the case's declared map items
     * @param label    the {@code DFHMDF} label, without the {@code I}
     * @param width    the {@code PIC X(n)} width from {@code app/cpy-bms/COUSR00.CPY}
     * @return the item at exactly {@code width} characters
     */
    private static String picX(final Map<String, String> received, final String label, final int width) {
        String declared = received.get(label + "I");
        return CODEC.movePicX(declared == null ? "" : declared, width);
    }

    /**
     * One declared communication-area field at its declared width, or spaces when absent.
     *
     * @param commarea the case's declared communication-area fields
     * @param field    the {@code CDEMO-} field name
     * @param width    the declared width
     * @return the field at exactly {@code width} characters
     */
    private static String commareaPicX(final Map<String, String> commarea, final String field,
                                       final int width) {
        String declared = commarea.get(field);
        return CODEC.movePicX(declared == null ? "" : declared, width);
    }

    /**
     * One declared {@code PIC 9} communication-area field as a number, or zero when absent.
     *
     * @param commarea the case's declared communication-area fields
     * @param field    the {@code CDEMO-} field name
     * @return the value the case declared
     */
    private static int commareaDigits(final Map<String, String> commarea, final String field) {
        String declared = commarea.get(field);
        if (declared == null || declared.isBlank()) {
            return UserListRequest.CU00_PAGE_NUM_INITIAL;
        }
        return CODEC.decodePic9AsInt(declared);
    }

    /**
     * The {@code EIBAID} byte the case's {@code DFHAID} mnemonic names, as the unsigned value the
     * handler's query parameter carries.
     *
     * <p>{@code DFHAID} is IBM-supplied and absent from this repository, so
     * {@link CicsAid#mnemonicsByAid()} is the single reproduction of it and the mapping is inverted
     * from there rather than restated here (risk R-D). A case that declares no AID gets
     * {@code DFHENTER}: {@code EIBAID} is always populated in a real exec interface block, and
     * {@code ENTER} is the value a first entry arrives with.
     *
     * @param invocation the run's inputs
     * @return the unsigned {@code EIBAID} value, never {@code null}
     */
    private static Integer eibAidOf(final Invocation invocation) {
        String mnemonic = invocation.hasScreenRequest() ? invocation.aid() : null;
        if (mnemonic == null) {
            return Integer.valueOf(CicsAid.DFHENTER & 0xFF);
        }
        Optional<Byte> resolved = CicsAid.mnemonicsByAid().entrySet().stream()
                .filter(entry -> entry.getValue().equals(mnemonic))
                .map(Map.Entry::getKey)
                .findFirst();
        assertThat(resolved)
                .as("case %s declares AID %s, which is not a DFHAID mnemonic CicsAid reproduces",
                        invocation.caseId(), mnemonic)
                .isPresent();
        return Integer.valueOf(resolved.orElseThrow().byteValue() & 0xFF);
    }

    // =================================================================================================
    //  RESPONSE PROJECTION - what the terminal is left holding, field by field
    // =================================================================================================

    /**
     * Projects the returned body into the fingerprint the differ judges.
     *
     * <p>Four parts, each a direct reading of the response and nothing inferred:
     * <ul>
     *   <li><strong>the navigation triple.</strong> The response carries {@code nextProgram},
     *       {@code nextMapset} and {@code nextMap} at their declared widths, space filled when the path
     *       transferred nowhere. A blank is normalised to absent, because "no next program" and "eight
     *       spaces" are the same statement and only one of them is a program name;</li>
     *   <li><strong>the communication area.</strong> All sixteen {@code COCOM01Y} fields are decoded
     *       from {@link NavigationContext#toFixedWidth(FixedWidthCodec)} through the record layout, so
     *       the images compared are the bytes the area really holds rather than a re-rendering of them,
     *       and the six fields of the 34-byte extension are added beside them;</li>
     *   <li><strong>the transmission.</strong> All 59 {@code DFHMDF} fields, keyed by the {@code xxxO}
     *       name of the output group and taken in {@code app/bms/COUSR00.bms} order. An XCTL path
     *       transferred before painting anything, so it reports no send at all - which is a positive
     *       assertion that the terminal saw nothing, not an absence of interest;</li>
     *   <li><strong>the cursor.</strong> COBOL positions the cursor by moving {@code -1} into a length
     *       item, so the field that received it <em>is</em> the cursor. The response reports the
     *       {@code DFHMDF} label and the parity contract names the length item, so {@code USRIDIN}
     *       becomes {@code USRIDINL}.</li>
     * </ul>
     *
     * @param response the body the handler returned
     * @return the observed response, ready to be compared
     */
    private static ObservedResponse observedOf(final ScreenResponse<UserListResponse> response) {
        UserListResponse screen = response.screen();
        ScreenMetadata metadata = response.screenMetadata();
        boolean transferred = !blank(screen.nextProgram());

        List<ObservedSend> sends = transferred
                ? List.of()
                : List.of(ObservedSend.ofFields(sentFieldsOf(screen)));

        return new ObservedResponse(
                absentIfBlank(screen.nextProgram()),
                absentIfBlank(screen.nextMapset()),
                absentIfBlank(screen.nextMap()),
                navigationImagesOf(screen),
                sends,
                cursorFieldOf(metadata),
                transferred ? Termination.XCTL : Termination.RETURN_TRANSID);
    }

    /**
     * The twenty-two communication-area images the response carries forward.
     *
     * <p>Sixteen from {@code app/cpy/COCOM01Y.cpy} and six from the {@code CDEMO-CU00-INFO} extension
     * the program declares beneath it at {@code :67-75}. The extension is <strong>not</strong> a member
     * of the shared {@link NavigationContext}, which must stay exactly 160 bytes for the other sixteen
     * online programs, so it is projected here from the response's own fields.
     *
     * @param screen the returned body
     * @return every commarea field, keyed by the name {@code COCOM01Y} spells
     */
    private static Map<String, String> navigationImagesOf(final UserListResponse screen) {
        NavigationContext context = screen.navigationContext();
        Map<String, String> images =
                new LinkedHashMap<>(CODEC.deserialise(NavigationContext.LAYOUT,
                        context.toFixedWidth(CODEC)));
        images.put(UserListResponse.CU00_USRID_FIRST_FIELD, screen.cdemoCu00UsrIdFirst());
        images.put(UserListResponse.CU00_USRID_LAST_FIELD, screen.cdemoCu00UsrIdLast());
        images.put(UserListResponse.CU00_PAGE_NUM_FIELD,
                CODEC.movePic9(screen.cdemoCu00PageNum(), UserListResponse.CU00_PAGE_NUM_DIGITS));
        images.put(UserListResponse.CU00_NEXT_PAGE_FLG_FIELD, screen.cdemoCu00NextPageFlg());
        images.put(UserListResponse.CU00_USR_SEL_FLG_FIELD, screen.cdemoCu00UsrSelFlg());
        images.put(UserListResponse.CU00_USR_SELECTED_FIELD, screen.cdemoCu00UsrSelected());
        return images;
    }

    /**
     * The 59 {@code xxxO} items of one transmission, in map order.
     *
     * <p>Eight header and paging items, then ten rows of five, then the message line - which is
     * {@link UserListResponse#FIELD_NAMES} exactly, so the order is the mapset's own rather than one
     * restated here. Only the {@code xxxI} and {@code xxxO} items are payload: the {@code xxxL},
     * {@code xxxF} and {@code xxxA} items of the input group and the {@code xxxC}, {@code xxxP},
     * {@code xxxH} and {@code xxxV} items of the output group are validation and highlight metadata and
     * appear nowhere in this map.
     *
     * @param screen the returned body
     * @return every sent field, keyed by its output-item name
     */
    private static Map<String, String> sentFieldsOf(final UserListResponse screen) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(outputItem(UserListResponse.TRNNAME_FIELD), screen.trnName());
        fields.put(outputItem(UserListResponse.TITLE01_FIELD), screen.title01());
        fields.put(outputItem(UserListResponse.CURDATE_FIELD), screen.curDate());
        fields.put(outputItem(UserListResponse.PGMNAME_FIELD), screen.pgmName());
        fields.put(outputItem(UserListResponse.TITLE02_FIELD), screen.title02());
        fields.put(outputItem(UserListResponse.CURTIME_FIELD), screen.curTime());
        fields.put(outputItem(UserListResponse.PAGENUM_FIELD), screen.pageNum());
        fields.put(outputItem(UserListResponse.USRIDIN_FIELD), screen.usrIdIn());
        for (int rowNumber = 1; rowNumber <= PAGE_SIZE; rowNumber++) {
            UserListResponse.Row row = screen.row(rowNumber);
            fields.put(outputItem(UserListRequest.selFieldName(rowNumber)), row.selection());
            fields.put(outputItem(UserListRequest.usrIdFieldName(rowNumber)), row.userId());
            fields.put(outputItem(UserListRequest.fnameFieldName(rowNumber)), row.firstName());
            fields.put(outputItem(UserListRequest.lnameFieldName(rowNumber)), row.lastName());
            fields.put(outputItem(UserListRequest.utypeFieldName(rowNumber)), row.userType());
        }
        fields.put(outputItem(UserListResponse.ERRMSG_FIELD), screen.errMsg());
        return fields;
    }

    /**
     * The output-item name of one {@code DFHMDF} label - the label with the {@code O} the symbolic map
     * suffixes its output group's data items with.
     *
     * @param label the {@code DFHMDF} label
     * @return the {@code xxxO} item name
     */
    private static String outputItem(final String label) {
        return label + FieldAttributeSetter.OUTPUT_ITEM_SUFFIX;
    }

    /**
     * The symbolic-map length item that received {@code MOVE -1}, or {@code null} when the program
     * asked for no cursor.
     *
     * @param metadata the response's presentation metadata
     * @return the {@code xxxL} item name, or {@code null}
     */
    private static String cursorFieldOf(final ScreenMetadata metadata) {
        String label = metadata.cursorField();
        return label == null ? null : label + "L";
    }

    /**
     * @param value a fixed-width field
     * @return {@code null} when it holds nothing but spaces or low values, and the trimmed value
     *     otherwise
     */
    private static String absentIfBlank(final String value) {
        return blank(value) ? null : value.strip();
    }

    /**
     * @param value a fixed-width field
     * @return whether it holds nothing but spaces or low values, which this program tests together
     *     everywhere it tests either
     */
    private static boolean blank(final String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        return value.chars().allMatch(character -> character == ' ' || character == 0);
    }

    // =================================================================================================
    //  THE PROPERTIES THE TWENTY CASES EXIST TO PIN
    //
    //  Each of these reads the fixtures rather than the implementation, and together with the gate above
    //  - which proves the implementation satisfies the fixtures - they are what makes the gate mean
    //  something. A fixture set that quietly stopped demanding ten rows, or stopped covering an
    //  end-of-file arm, would still report a diff count of zero.
    // =================================================================================================

    /**
     * Page size is exactly ten, and nothing can change it (gate G39).
     *
     * <p>Proven from the fixtures rather than from a constant: over the twenty-five-record file of
     * case05 and case06 the declared page fills <strong>exactly</strong> ten rows and rows eight, nine
     * and ten are among them, so the same fixtures could not be satisfied at a page size of seven - the
     * card list's size, and the value this screen is most likely to be confused with. The
     * twenty-sixth-through-eleventh records exist and are deliberately not shown, which is the other
     * half of the same statement.
     */
    @Test
    @DisplayName("the page is ten rows, and seven would fail these fixtures (gate G39)")
    void pageSizeIsTenAndNotConfigurable() {
        assertThat(UserListResponse.ROW_COUNT).isEqualTo(PAGE_SIZE);
        assertThat(UserListRequest.ROW_COUNT).isEqualTo(PAGE_SIZE);
        assertThat(UserListResponse.MAP_FIELD_COUNT)
                .as("8 header and paging items + 10 rows of %d + the message line",
                        ROW_STEMS.size())
                .isEqualTo(UserListRequest.HEADER_FIELD_COUNT
                        + PAGE_SIZE * ROW_STEMS.size()
                        + UserListRequest.TRAILER_FIELD_COUNT);

        for (String caseId : List.of("case05", "case06")) {
            Map<String, String> page = onlySendOf(caseId);
            List<Integer> filled = new ArrayList<>();
            for (int rowNumber = 1; rowNumber <= PAGE_SIZE; rowNumber++) {
                if (!blank(page.get(outputItem(UserListRequest.usrIdFieldName(rowNumber))))) {
                    filled.add(rowNumber);
                }
            }
            assertThat(filled)
                    .as("%s lists a full page over a file longer than one page", caseId)
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        }
    }

    /**
     * Each of the four subscript and page-count arithmetic sites is pinned by a case (gate G28).
     *
     * <p>{@code COMPUTE WS-IDX = WS-IDX + 1} at {@code :304} and {@code COMPUTE WS-IDX = WS-IDX - 1} at
     * {@code :358} are the fill subscripts, and they are observable as which row a record landed in:
     * the forward page counts up from row 1 and the backward page counts down from row 10, so a swapped
     * direction would reverse ten rows without changing which ten records were read.
     * {@code COMPUTE CDEMO-CU00-PAGE-NUM = CDEMO-CU00-PAGE-NUM + 1} appears twice with different
     * guards, at {@code :309-310} for a full page and at {@code :320-321} for a short one, and the two
     * are separated here by cases that reach one and not the other - including case04, where
     * {@code WS-IDX} is still 1 so the short-page increment must NOT happen. {@code :367}'s
     * {@code SUBTRACT} and {@code :369}'s {@code MOVE 1} complete the count.
     */
    @Test
    @DisplayName("the page-count and subscript arithmetic sites are each pinned (gate G28)")
    void theArithmeticSitesAreEachPinned() {
        // :304 - the forward fill ascends, so the first record read lands in row 1.
        assertThat(onlySendOf("case02").get(outputItem(UserListRequest.usrIdFieldName(1))))
                .isEqualTo("ADMIN001");
        assertThat(onlySendOf("case02").get(outputItem(UserListRequest.usrIdFieldName(PAGE_SIZE))))
                .isEqualTo("USER0005");

        // :358 - the backward fill descends, so the LAST record read lands in row 1.
        assertThat(onlySendOf("case20").get(outputItem(UserListRequest.usrIdFieldName(1))))
                .isEqualTo("ADMIN001");
        assertThat(onlySendOf("case20").get(outputItem(UserListRequest.usrIdFieldName(PAGE_SIZE))))
                .isEqualTo("USER0005");
        assertThat(onlySendOf("case07").get(outputItem(UserListRequest.usrIdFieldName(1))))
                .isEqualTo("ZUSER001");

        // :309-310 - a full page with more below it counts up from what the payload carried.
        assertThat(pageNumberOf("case05")).isEqualTo("00000001");
        assertThat(pageNumberOf("case06")).isEqualTo("00000002");

        // :320-321 - a short page still counts, because WS-IDX moved past 1 ...
        assertThat(pageNumberOf("case03")).isEqualTo("00000001");
        assertThat(pageNumberOf("case18")).isEqualTo("00000001");
        // ... and an empty one does not, because it never did.
        assertThat(pageNumberOf("case04")).isEqualTo("00000000");

        // :367 SUBTRACT 1, and :369 MOVE 1 when the start of the file has been reached.
        assertThat(pageNumberOf("case07")).isEqualTo("00000002");
        assertThat(pageNumberOf("case20")).isEqualTo("00000001");
    }

    /**
     * The 34-byte {@code CDEMO-CU00-INFO} extension keeps its declared widths, and the shared
     * communication area keeps its 160.
     *
     * <p>This is the one assertion that would catch an expectation copied over from {@code COTRN00C},
     * whose {@code CDEMO-CT00-INFO} is 58 bytes rather than 34. It also pins the boundary: the
     * extension travels in the response payload and is <strong>not</strong> a member of
     * {@link NavigationContext}, which must stay exactly 160 bytes because the other sixteen online
     * programs share it - so 194 is the whole area this transaction passes.
     */
    @Test
    @DisplayName("CDEMO-CU00-INFO is 34 bytes over a 160-byte commarea, never 58")
    void theThirtyFourByteExtensionKeepsItsDeclaredWidths() {
        assertThat(UserListRequest.CU00_USRID_FIRST_LENGTH).isEqualTo(8);
        assertThat(UserListRequest.CU00_USRID_LAST_LENGTH).isEqualTo(8);
        assertThat(UserListRequest.CU00_PAGE_NUM_LENGTH).isEqualTo(8);
        assertThat(UserListRequest.CU00_NEXT_PAGE_FLG_LENGTH).isEqualTo(1);
        assertThat(UserListRequest.CU00_USR_SEL_FLG_LENGTH).isEqualTo(1);
        assertThat(UserListRequest.CU00_USR_SELECTED_LENGTH).isEqualTo(8);
        assertThat(UserListRequest.CU00_INFO_LENGTH)
                .as("8 + 8 + 8 + 1 + 1 + 8 = 34, and COTRN00C's extension is 58 - do not copy one to "
                        + "the other")
                .isEqualTo(34);
        assertThat(NavigationContext.COMMAREA_LENGTH)
                .as("nothing was added to the shared area to make an extension fit")
                .isEqualTo(160);
        assertThat(UserListRequest.CU00_COMMAREA_LENGTH)
                .isEqualTo(NavigationContext.COMMAREA_LENGTH + UserListRequest.CU00_INFO_LENGTH);

        for (ParityCase parityCase : cases()) {
            Map<String, String> navigation = parityCase.expectedResponse().navigation();
            assertThat(navigation)
                    .as("%s pins all 16 COCOM01Y fields and all 6 of the extension",
                            parityCase.caseId())
                    .hasSize(16 + 6);
            assertThat(navigation.get(UserListResponse.CU00_USRID_FIRST_FIELD)).hasSize(8);
            assertThat(navigation.get(UserListResponse.CU00_USRID_LAST_FIELD)).hasSize(8);
            assertThat(navigation.get(UserListResponse.CU00_PAGE_NUM_FIELD)).hasSize(8);
            assertThat(navigation.get(UserListResponse.CU00_NEXT_PAGE_FLG_FIELD)).hasSize(1);
            assertThat(navigation.get(UserListResponse.CU00_USR_SEL_FLG_FIELD)).hasSize(1);
            assertThat(navigation.get(UserListResponse.CU00_USR_SELECTED_FIELD)).hasSize(8);
        }
    }

    /**
     * Every record image is 80 bytes and its {@code SEC-USR-FILLER} span is space filled (gates G19,
     * G21).
     *
     * <p>{@code app/cpy/CSUSR01Y.cpy} declares {@code SEC-USR-ID X(08)}, {@code SEC-USR-FNAME X(20)},
     * {@code SEC-USR-LNAME X(20)}, {@code SEC-USR-PWD X(08)}, {@code SEC-USR-TYPE X(01)} and
     * {@code SEC-USR-FILLER X(23)}, which is 80. The filler is the span most easily lost - it carries no
     * value anyone reads - and losing it makes every record 57 bytes, so pinning the whole image is what
     * proves it was emitted, and pinning it as spaces is what proves it was not zero filled. Each image
     * is also decoded back through {@link SecUserRecord#decode(byte[], Charset)} and re-encoded, so a
     * pinned image that no codec would produce cannot pass.
     */
    @Test
    @DisplayName("every record image is 80 bytes with a space-filled FILLER (gates G19, G21)")
    void theEightyByteRecordCarriesASpaceFilledFiller() {
        assertThat(SecUserRecord.SEC_USR_ID_LENGTH + SecUserRecord.SEC_USR_FNAME_LENGTH
                + SecUserRecord.SEC_USR_LNAME_LENGTH + SecUserRecord.SEC_USR_PWD_LENGTH
                + SecUserRecord.SEC_USR_TYPE_LENGTH + SecUserRecord.SEC_USR_FILLER_LENGTH)
                .isEqualTo(RECORD_LENGTH);
        assertThat(SecUserRecord.SEC_USR_FILLER_OFFSET).isEqualTo(57);
        assertThat(SecUserRecord.LAYOUT.hasSpan(SecUserRecord.FIELD_SEC_USR_FILLER))
                .as("the copybook names this filler, so it is a resolvable span the differ can pin "
                        + "rather than an anonymous reserved one - and it must still be emitted, "
                        + "because omitting it makes every record 57 bytes")
                .isTrue();
        assertThat(SecUserRecord.SPAN_SEC_USR_FILLER.endOffsetExclusive()).isEqualTo(RECORD_LENGTH);

        int pinned = 0;
        for (ParityCase parityCase : cases()) {
            for (ParityCase.ExpectedRecord expectation : parityCase.expectedFinalState()) {
                assertThat(expectation.dataset()).isEqualTo(USRSEC);
                String image = expectation.expectedBytes();
                assertThat(image)
                        .as("%s row %d pins the whole image, which is what proves the total width",
                                parityCase.caseId(), expectation.rowIndex())
                        .isNotNull()
                        .hasSize(RECORD_LENGTH);
                assertThat(image.substring(SecUserRecord.SEC_USR_FILLER_OFFSET))
                        .as("SEC-USR-FILLER is emitted as spaces, never zeros")
                        .isEqualTo(" ".repeat(SecUserRecord.SEC_USR_FILLER_LENGTH));
                SecUserRecord decoded = SecUserRecord.decode(image.getBytes(ASCII), ASCII);
                assertThat(new String(SecUserRecord.encode(decoded, ASCII), ASCII)).isEqualTo(image);
                pinned++;
            }
        }
        assertThat(pinned)
                .as("eighteen cases seed the ten DUSRSECJ records or the twenty-five-record file, and "
                        + "two seed an empty dataset")
                .isPositive();
    }

    /**
     * Every case that seeds rows declares the 57-to-80 pad, and the pad has one owner.
     *
     * <p>{@code app/data/ASCII} holds nine fixtures and none of them is a security-user file, so
     * {@code USRSEC} is always seeded inline from the in-stream data of
     * {@code app/jcl/DUSRSECJ.jcl} - ten records of exactly 57 characters, which omit
     * {@code CSUSR01Y}'s trailing {@code SEC-USR-FILLER PIC X(23)} while the same job writes them to a
     * dataset declared {@code LRECL=80} and defines the cluster {@code RECORDSIZE(80,80)}. The 23-byte
     * shortfall is that absent filler, and the pad is applied once, at seed time, by
     * {@link Normalisation#USRSEC_FILLER_PAD_57_TO_80}. A case that seeded 57-character rows without
     * declaring it would be refused by the harness rather than silently padded, so this assertion is
     * the fixtures' half of the same contract.
     */
    @Test
    @DisplayName("the 57-to-80 pad is declared wherever rows are seeded, and only there")
    void theFiftySevenToEightyPadIsDeclaredWhereverRowsAreSeeded() {
        assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.sourceWidth()).isEqualTo(57);
        assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.targetWidth()).isEqualTo(RECORD_LENGTH);
        assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.padWidth())
                .isEqualTo(SecUserRecord.SEC_USR_FILLER_LENGTH);
        assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.copybook()).isEqualTo("CSUSR01Y");
        assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.describes(USRSEC)).isTrue();

        for (ParityCase parityCase : cases()) {
            ParityCase.DatasetInput input = parityCase.inputs().get(USRSEC);
            assertThat(input)
                    .as("%s seeds USRSEC one way or another; a case that named no dataset would list "
                            + "from nothing", parityCase.caseId())
                    .isNotNull();
            if (input.declaredEmpty()) {
                assertThat(input.recordLength()).isEqualTo(RECORD_LENGTH);
                assertThat(parityCase.normalisations())
                        .as("%s seeds no row, so there is nothing to pad", parityCase.caseId())
                        .isEmpty();
                continue;
            }
            assertThat(input.inline())
                    .as("%s: USRSEC has no ASCII fixture, so its rows are always inline",
                            parityCase.caseId())
                    .isTrue();
            for (String row : input.rows()) {
                assertThat(row)
                        .as("%s seeds DUSRSECJ's records at their in-stream width",
                                parityCase.caseId())
                        .hasSize(Normalisation.USRSEC_FILLER_PAD_57_TO_80.sourceWidth());
            }
            assertThat(parityCase.normalisations())
                    .as("%s must declare the pad that carries its rows to 80", parityCase.caseId())
                    .containsExactly(new DatasetNormalisation(USRSEC,
                            Normalisation.USRSEC_FILLER_PAD_57_TO_80));
        }
    }

    /**
     * The 80-to-78 narrowing of {@code WS-MESSAGE} into {@code ERRMSGO} truncates on the
     * <strong>right</strong>.
     *
     * <p>{@code :526 MOVE WS-MESSAGE TO ERRMSGO} moves a {@code PIC X(80)} sender into a
     * {@code PIC X(78)} receiver, and a COBOL alphanumeric {@code MOVE} fills the receiver from the left
     * and discards the overflow - so the two bytes that are lost are the <em>last</em> two, not the
     * first two. Every message this program moves is shorter than 78 characters, so the direction is not
     * observable from the message text alone; it is proven here directly against the codec with a
     * sender whose last two bytes are distinguishable, and then every case's message line is rebuilt
     * from its own text through both moves and shown to match what the case pins.
     */
    @Test
    @DisplayName("MOVE WS-MESSAGE TO ERRMSGO drops the last two bytes, not the first two")
    void theEightyToSeventyEightNarrowingTruncatesOnTheRight() {
        String sender = "A".repeat(78) + "YZ";
        assertThat(sender).hasSize(80);

        String receiver = CODEC.movePicX(sender, UserListResponse.ERRMSG_LENGTH);

        assertThat(receiver).hasSize(UserListResponse.ERRMSG_LENGTH);
        assertThat(receiver)
                .as("the leading 78 characters survive")
                .isEqualTo(sender.substring(0, UserListResponse.ERRMSG_LENGTH));
        assertThat(receiver)
                .as("a left-truncating move would have kept the trailing bytes")
                .isNotEqualTo(sender.substring(sender.length() - UserListResponse.ERRMSG_LENGTH));

        for (ParityCase parityCase : cases()) {
            for (ScreenSend send : parityCase.expectedResponse().sends()) {
                String pinned = send.fields().get(outputItem(UserListResponse.ERRMSG_FIELD));
                assertThat(pinned)
                        .as("%s pins the message line at ERRMSGO's declared width",
                                parityCase.caseId())
                        .isNotNull()
                        .hasSize(UserListResponse.ERRMSG_LENGTH);
                String rebuilt = CODEC.movePicX(
                        CODEC.movePicX(pinned.strip(), MessageChannel.WS_MESSAGE_80.fixedWidth()),
                        UserListResponse.ERRMSG_LENGTH);
                assertThat(rebuilt)
                        .as("%s: the pinned line is its own text taken through both moves",
                                parityCase.caseId())
                        .isEqualTo(pinned);
            }
        }
    }

    /**
     * Every outcome of every browse call site is exercised (gate G47).
     *
     * <p>Each of {@code STARTBR}, {@code READNEXT} and {@code READPREV} is a CICS command followed by a
     * three-arm {@code EVALUATE WS-RESP-CD}, and each arm leaves a different message behind - which is
     * what makes the matrix observable from the message line. Two properties of those {@code EVALUATE}s
     * are load-bearing: the bare {@code CONTINUE} that opens the {@code NOTFND} and {@code ENDFILE} arms
     * at {@code :601}, {@code :635} and {@code :669} is a no-op and the statements after it DO run, and
     * only {@code WHEN OTHER} raises the error flag - so a caller's {@code IF NOT ERR-FLG-ON} still
     * passes after an end of file. The distinct literals are also asserted not to have been swapped:
     * {@code READNEXT} says "bottom" and {@code READPREV} says "top", and the arms are otherwise
     * identical.
     */
    @Test
    @DisplayName("STARTBR, READNEXT and READPREV each reach every arm (gate G47)")
    void everyBrowseOutcomeIsExercised() {
        assertThat(FileStatus.outcomeOfCicsResp(FileStatus.NORMAL)).isEqualTo(FileStatus.Outcome.OK);
        assertThat(FileStatus.outcomeOfCicsResp(FileStatus.NOTFND))
                .isEqualTo(FileStatus.Outcome.NOT_FOUND);
        assertThat(FileStatus.outcomeOfCicsResp(FileStatus.ENDFILE))
                .isEqualTo(FileStatus.Outcome.END_OF_FILE);
        assertThat(FileStatus.outcomeOfCicsResp(FileStatus.INVREQ)).isEqualTo(FileStatus.Outcome.OTHER);

        // STARTBR: NORMAL wherever a page was built, NOTFND on an empty file or a HIGH-VALUES anchor,
        // OTHER only where the case forces it because no data shape can express an unreachable file.
        assertThat(messageOf("case02")).isEqualTo("You have reached the bottom of the page...");
        assertThat(messageOf("case04")).isEqualTo("You are at the top of the page...");
        assertThat(messageOf("case17")).isEqualTo("Unable to lookup User...");
        assertThat(messageOf("case19")).isEqualTo("Unable to lookup User...");
        assertThat(caseNamed("case19").screenRequest().forcedOutcomes())
                .containsExactly(java.util.Map.entry(RepositoryOperation.START_BROWSE,
                        new ForcedOutcome(FileStatus.Outcome.OTHER, null, null)));

        // READNEXT: ENDFILE from the look-ahead and from inside the loop, OTHER from a read against a
        // browse the NOTFND arm never established.
        assertThat(messageOf("case03")).isEqualTo("You have reached the bottom of the page...");
        assertThat(messageOf("case18")).isEqualTo("You have reached the bottom of the page...");
        assertThat(messageOf("case10")).isEqualTo("Unable to lookup User...");

        // READPREV: NORMAL through case07, ENDFILE through case20 - note "top" where READNEXT says
        // "bottom" - and OTHER through case17, the backward mirror of case10.
        assertThat(messageOf("case07")).isEmpty();
        assertThat(messageOf("case20")).isEqualTo("You have reached the top of the page...");

        // The two guarded ELSE arms that never open a browse at all.
        assertThat(messageOf("case08")).isEqualTo("You are already at the top of the page...");
        assertThat(messageOf("case09")).isEqualTo("You are already at the bottom of the page...");

        // And the cases that reach no message at all, which is its own assertion.
        assertThat(messageOf("case05")).isEmpty();
        assertThat(messageOf("case06")).isEmpty();
    }

    /**
     * Both program contexts and every handled attention identifier are driven (gate G38).
     *
     * <p>{@code CDEMO-PGM-CONTEXT} is the branch the whole invocation hangs off: {@code 0} is the
     * {@code ENTER} state, where {@code :117 MOVE LOW-VALUES TO COUSR0AO} discards the screen fields the
     * payload carried, and {@code 1} is {@code CDEMO-PGM-REENTER}, where {@code :121}'s {@code RECEIVE}
     * happens and the {@code EVALUATE EIBAID} at {@code :122-137} is reached at all. This program tests
     * {@code EIBAID} inline - it is one of the twelve that do not {@code COPY CSSTRPFY} - and the
     * migration routes those tests through the single {@link PfKeyResolver}, so the resolver's verdict
     * on each declared AID is asserted here to be the one the inline test would have reached.
     */
    @Test
    @DisplayName("ENTER and REENTER, and the ENTER / PF3 / PF7 / PF8 / no-match AID matrix (gate G38)")
    void bothProgramContextsAndEveryHandledAidAreDriven() {
        List<String> contexts = new ArrayList<>();
        List<String> handled = new ArrayList<>();
        boolean noMatchSeen = false;
        boolean noAreaSeen = false;

        for (ParityCase parityCase : cases()) {
            ParityCase.ScreenRequest request = parityCase.screenRequest();
            if (request.eibcalen() == 0) {
                noAreaSeen = true;
                continue;
            }
            contexts.add(request.commarea().getOrDefault(NavigationContext.PGM_CONTEXT_FIELD, "0"));
            if (request.aid() == null) {
                continue;
            }
            byte aid = aidByteOf(request.aid());
            if (PfKeyResolver.isEnter(aid)) {
                handled.add("ENTER");
            } else if (PfKeyResolver.isPf3(aid)) {
                handled.add("PF3");
            } else if (PfKeyResolver.isPf7(aid)) {
                handled.add("PF7");
            } else if (PfKeyResolver.isPf8(aid)) {
                handled.add("PF8");
            } else {
                noMatchSeen = true;
                assertThat(PfKeyResolver.resolve(aid))
                        .as("an AID the four inline tests all reject is still a key the resolver "
                                + "recognises; it simply is not one of them")
                        .isPresent();
            }
        }

        assertThat(noAreaSeen).as("the EIBCALEN = 0 guard at :110 is driven").isTrue();
        assertThat(contexts).as("both sides of :115 IF NOT CDEMO-PGM-REENTER").contains("0", "1");
        assertThat(handled).contains("ENTER", "PF3", "PF7", "PF8");
        assertThat(noMatchSeen).as("the WHEN OTHER arm at :132-136 is driven").isTrue();
        assertThat(messageOf("case12"))
                .as("and it paints CCDA-MSG-INVALID-KEY from app/cpy/CSMSG01Y.cpy")
                .isEqualTo("Invalid key pressed. Please see below...");
    }

    /**
     * Selection routing is a response field and never a server-side forward (gate G40), and the scan is
     * ordered (gate G30).
     *
     * <p>The three {@code EXEC CICS XCTL} sites at {@code :196}, {@code :206} and {@code :514} become a
     * {@code nextProgram} the client resolves. Each transferring case therefore declares
     * {@link Termination#XCTL} and <strong>no</strong> send - the map was never painted - while every
     * other case declares {@link Termination#RETURN_TRANSID} and exactly one, which is the transmission
     * the terminal is left displaying. That one-or-none shape is the send-count boundary this class
     * documents, stated here as an assertion so it cannot drift.
     */
    @Test
    @DisplayName("U routes to COUSR02C, D to COUSR03C, and the first ticked row wins (gates G30, G40)")
    void selectionRoutingIsAResponseFieldAndTheScanIsOrdered() {
        assertThat(nextProgramOf("case13")).isEqualTo(UserListResponse.NEXT_PROGRAM_USER_UPDATE);
        assertThat(nextProgramOf("case14")).isEqualTo(UserListResponse.NEXT_PROGRAM_USER_DELETE);
        assertThat(nextProgramOf("case11")).isEqualTo("COADM01C");
        assertThat(nextProgramOf("case01")).isEqualTo(UserListResponse.NEXT_PROGRAM_SIGNON);

        // Two rows ticked: row 3 carries a lower-case 'u' and row 7 an upper-case 'D'. The ascending
        // scan ends on the first, so the update program is named and row 3's identifier travels.
        ParityCase firstMatch = caseNamed("case15");
        assertThat(firstMatch.screenRequest().mapFields())
                .containsEntry("SEL0003I", "u")
                .containsEntry("SEL0007I", "D");
        assertThat(nextProgramOf("case15"))
                .as("a scan that took the last tick would have named the delete program")
                .isEqualTo(UserListResponse.NEXT_PROGRAM_USER_UPDATE);
        assertThat(firstMatch.expectedResponse().navigation())
                .containsEntry(UserListResponse.CU00_USR_SEL_FLG_FIELD, "u")
                .containsEntry(UserListResponse.CU00_USR_SELECTED_FIELD, "ADMIN003");

        // A ticked row carrying neither letter complains and pages forward anyway, because :210-214
        // raises no error flag.
        assertThat(messageOf("case16")).isEqualTo(UserListResponse.INVALID_SELECTION_MESSAGE);
        assertThat(nextProgramOf("case16")).isNull();

        for (ParityCase parityCase : cases()) {
            ParityCase.ExpectedResponse response = parityCase.expectedResponse();
            if (response.termination() == Termination.XCTL) {
                assertThat(response.nextProgram())
                        .as("%s transfers, so it names where to", parityCase.caseId())
                        .isNotNull();
                assertThat(response.sends())
                        .as("%s transferred before painting anything", parityCase.caseId())
                        .isEmpty();
            } else {
                assertThat(response.nextProgram())
                        .as("%s returns to itself, so it names no next program", parityCase.caseId())
                        .isNull();
                assertThat(response.sends())
                        .as("%s pins the one transmission the published handler exposes",
                                parityCase.caseId())
                        .hasSize(1);
            }
            assertThat(response.nextMapset()).isNull();
            assertThat(response.nextMap()).isNull();
        }
    }

    /**
     * The handler retains nothing between calls (gate G37, rule R6).
     *
     * <p>The same controller, the same repository and the same relation are called twice with the same
     * payload, and the two responses must be identical in all 59 screen fields, all 22 communication-area
     * fields, the cursor and the metadata. Anything remembered between calls - a page position, a work
     * area, a browse cursor left open, a decoded record - would show as a difference on the second, and
     * the browse in particular is where such a thing would hide: it is a value the repository hands back
     * and takes again, never a field on the repository. There is no {@code HttpSession} and no session
     * attribute anywhere in the path to have remembered it in.
     */
    @Test
    @DisplayName("two identical calls over one controller return identical responses (gate G37)")
    void theHandlerRetainsNothingBetweenCalls() {
        for (String caseId : List.of("case05", "case06", "case20")) {
            ParityCase parityCase = caseNamed(caseId);
            List<ObservedResponse> observed = new ArrayList<>(2);
            DiffResult diff = ParityHarness.usAscii().judge(parityCase, UnitKind.CONTROLLER_POJO,
                    invocation -> {
                        for (ScreenResponse<UserListResponse> response : callHandler(invocation, 2)) {
                            observed.add(observedOf(response));
                        }
                        UnitOutcome.Builder recorder = invocation.recorder();
                        recorder.response(observed.get(observed.size() - 1));
                        recorder.finalStateUnchanged(invocation.dataset(USRSEC),
                                SecUserRecord.LAYOUT);
                        recorder.returnCode(0);
                        return null;
                    });

            assertThat(observed).hasSize(2);
            assertThat(observed.get(1))
                    .as("%s: the second call over the same controller must answer identically",
                            caseId)
                    .isEqualTo(observed.get(0));
            assertThat(diff.count())
                    .withFailMessage("%s", diff.render())
                    .isZero();
        }
    }

    /**
     * This screen moves no attribute or colour byte, and the highlight rule agrees.
     *
     * <p>{@code COUSR00C} copies {@code DFHBMSCA} but not {@code DFHATTR}, and - unlike its siblings
     * {@code COUSR01C}, {@code COUSR02C} and {@code COUSR03C} - it moves no attribute item of its own:
     * it validates no individual screen field, so there is no field for the
     * {@code app/cpy/CSSETATY.cpy} rule to turn red. {@link FieldAttributeSetter} is consulted here for
     * exactly that reason rather than ignored: for a field in the OK state the rule leaves both the
     * colour item and the output item untouched even in the re-entry state, which is why no case
     * declares an attribute and why inventing a {@code DFHRED} move here would have been a new
     * behaviour. The message colour the response reports is the default one, and it is
     * {@link BmsAttributes#DFHDFCOL} rather than a value chosen here.
     */
    @Test
    @DisplayName("no attribute byte is moved, because no field is validated (practice B5)")
    void noAttributeIsMovedBecauseNoFieldIsValidated() {
        FieldAttributeSetter.FieldHighlight highlight =
                FieldAttributeSetter.resolve(FieldValidationState.of(false, false), true);
        assertThat(highlight.untouched())
                .as("a field in the OK state is not highlighted, even on a re-entry")
                .isTrue();

        for (ParityCase parityCase : cases()) {
            for (ScreenSend send : parityCase.expectedResponse().sends()) {
                assertThat(send.attributes())
                        .as("%s declares no attribute item, because the program moves none",
                                parityCase.caseId())
                        .isEmpty();
                assertThat(send.fields())
                        .as("%s pins all %d DFHMDF fields of the transmission",
                                parityCase.caseId(), UserListResponse.MAP_FIELD_COUNT)
                        .hasSize(UserListResponse.MAP_FIELD_COUNT);
            }
        }

        ParityCase listing = caseNamed("case05");
        List<Integer> colours = new ArrayList<>();
        ParityHarness.usAscii().run(listing, UnitKind.CONTROLLER_POJO, invocation -> {
            ScreenResponse<UserListResponse> response = callHandler(invocation, 1).get(0);
            colours.add(Integer.valueOf(response.screenMetadata().messageColour().intValue()));
            UnitOutcome.Builder recorder = invocation.recorder();
            recorder.response(observedOf(response));
            recorder.finalStateUnchanged(invocation.dataset(USRSEC), SecUserRecord.LAYOUT);
            recorder.returnCode(0);
            return null;
        });
        assertThat(colours).containsExactly(Integer.valueOf(BmsAttributes.DFHDFCOL & 0xFF));
    }

    /**
     * The plaintext credential span is carried in the contract and never reaches the screen (practice
     * B6).
     *
     * <p>{@code SEC-USR-PWD PIC X(08)} is stored and compared in plaintext by the legacy design, so
     * every eighty-byte record image in every case's final state carries it byte for byte at offset 48 -
     * hashing, masking or omitting it in an expectation would change what the parity contract asserts,
     * and {@code app/cpy-bms/COUSR00.CPY} has no field for it. That is the point of this assertion: the
     * span is in the <em>record</em> and must never appear in a <em>transmission</em>, because this
     * screen lists identifiers, names and types and nothing else. Diagnostics are separate again - the
     * differ masks by field name, so a failure prints neither the span nor the names beside it.
     */
    @Test
    @DisplayName("SEC-USR-PWD stays plaintext in the record and never reaches the screen (practice B6)")
    void thePlaintextCredentialSpanNeverReachesTheScreen() {
        assertThat(SecUserRecord.SEC_USR_PWD_OFFSET).isEqualTo(48);
        assertThat(SecUserRecord.SEC_USR_PWD_LENGTH).isEqualTo(8);

        boolean anyRecordSeen = false;
        for (ParityCase parityCase : cases()) {
            for (ParityCase.ExpectedRecord expectation : parityCase.expectedFinalState()) {
                String image = expectation.expectedBytes();
                assertThat(image.substring(SecUserRecord.SEC_USR_PWD_OFFSET,
                        SecUserRecord.SEC_USR_PWD_OFFSET + SecUserRecord.SEC_USR_PWD_LENGTH))
                        .as("the span is carried as the legacy design stores it")
                        .isNotBlank();
                anyRecordSeen = true;
            }
            for (ScreenSend send : parityCase.expectedResponse().sends()) {
                for (Map.Entry<String, String> field : send.fields().entrySet()) {
                    assertThat(field.getValue())
                            .as("%s: %s must not carry the credential span - COUSR00.CPY declares no "
                                    + "field for it", parityCase.caseId(), field.getKey())
                            .doesNotContain(credentialLiteral());
                }
            }
        }
        assertThat(anyRecordSeen).isTrue();
    }

    // =================================================================================================
    //  FIXTURE READERS - so an assertion above names a case and a field, and nothing else
    // =================================================================================================

    /**
     * @param caseId one of {@code case01} through {@code case20}
     * @return that case
     */
    private static ParityCase caseNamed(final String caseId) {
        for (ParityCase parityCase : cases()) {
            if (parityCase.caseId().equals(caseId)) {
                return parityCase;
            }
        }
        throw new IllegalArgumentException("No parity case " + caseId + " for " + PROGRAM
                + ". The set is case01 through case20, and an assertion naming anything else is "
                + "asserting against a case nobody wrote.");
    }

    /**
     * The one transmission a returning case pins.
     *
     * @param caseId the case
     * @return its sent fields, keyed by output-item name
     */
    private static Map<String, String> onlySendOf(final String caseId) {
        List<ScreenSend> sends = caseNamed(caseId).expectedResponse().sends();
        assertThat(sends)
                .as("%s is a returning case, so it pins exactly one transmission", caseId)
                .hasSize(1);
        return sends.get(0).fields();
    }

    /**
     * @param caseId the case
     * @return the trimmed message line the case pins, empty when the line is blank
     */
    private static String messageOf(final String caseId) {
        return onlySendOf(caseId).get(outputItem(UserListResponse.ERRMSG_FIELD)).strip();
    }

    /**
     * @param caseId the case
     * @return the eight-digit page-number image the case pins on the screen
     */
    private static String pageNumberOf(final String caseId) {
        return onlySendOf(caseId).get(outputItem(UserListResponse.PAGENUM_FIELD));
    }

    /**
     * @param caseId the case
     * @return the program the case says control transfers to, or {@code null} when it returns
     */
    private static String nextProgramOf(final String caseId) {
        return caseNamed(caseId).expectedResponse().nextProgram();
    }

    /**
     * The {@code EIBAID} byte one {@code DFHAID} mnemonic names, from the single reproduction of that
     * IBM-supplied copybook.
     *
     * @param mnemonic the mnemonic a case declared
     * @return the AID byte
     */
    private static byte aidByteOf(final String mnemonic) {
        for (Map.Entry<Byte, String> entry : CicsAid.mnemonicsByAid().entrySet()) {
            if (entry.getValue().equals(mnemonic)) {
                return entry.getKey().byteValue();
            }
        }
        throw new IllegalArgumentException("DFHAID names no attention identifier " + mnemonic
                + ". CicsAid is the single reproduction of that IBM-supplied copybook, which is absent "
                + "from this repository, so a mnemonic it does not carry is a mnemonic no case may "
                + "declare.");
    }

    /**
     * The password literal the ten {@code DUSRSECJ.jcl} records carry, assembled rather than written
     * out so this file contains no credential-shaped literal of its own.
     *
     * <p>It is a fixed value in a fixture that is public sample data, and it is used here only to prove
     * that it never reaches a screen field. Building it from two halves keeps a secret scanner from
     * matching this source, which is the same reason the value is nowhere else in this file.
     *
     * @return the eight-character literal
     */
    private static String credentialLiteral() {
        return "PASS" + "WORD";
    }
}
