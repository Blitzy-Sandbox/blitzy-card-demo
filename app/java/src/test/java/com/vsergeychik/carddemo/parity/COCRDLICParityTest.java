package com.vsergeychik.carddemo.parity;

import com.vsergeychik.carddemo.card.CardListController;
import com.vsergeychik.carddemo.card.CardRepository;
import com.vsergeychik.carddemo.card.CardRepository.BrowseDirection;
import com.vsergeychik.carddemo.card.CardRepository.CardBrowse;
import com.vsergeychik.carddemo.card.CardRepository.CardReadResult;
import com.vsergeychik.carddemo.card.dto.CardListRequest;
import com.vsergeychik.carddemo.card.dto.CardListRequest.FirstListRow;
import com.vsergeychik.carddemo.card.dto.CardListRequest.ListRow;
import com.vsergeychik.carddemo.card.dto.CardListRequest.PageCursor;
import com.vsergeychik.carddemo.card.dto.CardListRequest.StopperListRow;
import com.vsergeychik.carddemo.card.dto.CardListResponse;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.card.dto.CardSelectRequest;
import com.vsergeychik.carddemo.card.dto.CardSelectResponse;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedResponse;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedSend;
import com.vsergeychik.carddemo.parity.ParityCase.RepositoryOperation;
import com.vsergeychik.carddemo.parity.ParityCase.Termination;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.parity.ParityHarness.Invocation;
import com.vsergeychik.carddemo.parity.ParityHarness.SeededDataset;
import com.vsergeychik.carddemo.parity.ParityHarness.UnitOutcome;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Parity gate for {@code COCRDLIC}, the CICS online card-list transaction {@code CCLI}
 * [{@code app/csd/CARDDEMO.CSD}], projected onto {@code GET /api/cards}.
 *
 * <h2>Where the expected values come from: statically derived, never captured</h2>
 * <p><strong>Every expectation in {@code src/test/resources/parity/COCRDLIC/} was derived by reading
 * {@code app/cbl/COCRDLIC.cbl} paragraph by paragraph, not by executing the legacy program.</strong>
 * Executing it is impossible in this environment - there is no z/OS runtime, the available COBOL
 * compiler has its indexed file handler disabled, no CICS emulator is present, and {@code DFHAID},
 * {@code DFHBMSCA} and {@code DFHATTR} are IBM-supplied and absent from this repository. The
 * substitution is recorded as risk <strong>R-A</strong> and is stated here rather than buried, because
 * a statically derived expectation can encode a misreading of the COBOL where a captured one cannot
 * (practice B12).
 *
 * <p>Four things bound that risk, and each is visible in the case files:
 * <ul>
 *   <li>Widths and offsets come from the copybooks mechanically - {@code app/cpy/CVACT02Y.cpy} for the
 *       150-byte card record, {@code app/cpy-bms/COCRDLI.CPY} for the 45 payload items,
 *       {@code app/cpy/COCOM01Y.cpy} for the 160-byte communication area - never from prose.</li>
 *   <li>Every case is seeded from the real fixture {@code app/data/ASCII/carddata.txt}, copied
 *       byte-for-byte to {@code src/test/resources/fixtures/carddata.txt}: 50 rows of 150 bytes,
 *       ascending by card number. No synthetic row is invented.</li>
 *   <li>Each case's {@code description} names the source line range it pins, so a reviewer checks the
 *       derivation against the COBOL without re-deriving it.</li>
 *   <li>The comparison is field by field in <strong>both</strong> directions: {@link FieldDiffer}
 *       reports a field the run produced that the case failed to pin, so an unstated field cannot pass
 *       by omission.</li>
 * </ul>
 *
 * <h2>Page size seven is behaviour, not configuration</h2>
 * <p>{@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} [{@code app/cbl/COCRDLIC.cbl:177-178}] bounds
 * the forward read loop at {@code :1191} and the backward loop at {@code :1284-1286}, and
 * {@code WS-EDIT-SELECT} / {@code WS-EDIT-SELECT-ERRORS} are both {@code OCCURS 7 TIMES}
 * [{@code :76}, {@code :86}]. Seven is therefore part of what the program does, in the same sense the
 * account-filter edit is (gate <strong>G39</strong>). This class asserts the literal behaviour - seven
 * rows placed, an eighth look-ahead read, a partial page short of seven - and
 * {@link #pageSizeIsBehaviourNotConfiguration()} additionally proves there is no property, setter or
 * environment seam through which it could be changed. It is never asserted by reading a configuration
 * value back, because that would test the configuration rather than the program.
 *
 * <h2>Two mapsets, and the one the source actually sends</h2>
 * <p>{@code COCRDLIC} is the program the plan describes as driving two mapsets, and the relationship is
 * real but narrower than "two sends". Verified in the source:
 * <ul>
 *   <li>{@code COPY COCRDLI.} at {@code :276} is live, and {@code CCRDLIAO} is the one and only map
 *       sent - {@code EXEC CICS SEND MAP(LIT-THISMAP) MAPSET(LIT-THISMAPSET) FROM(CCRDLIAO)} at
 *       {@code :939-946}, reached from six of the eight arms of the {@code :418-583}
 *       {@code EVALUATE}.</li>
 *   <li><strong>{@code *COPY COCRDSL.} at {@code :274} is commented out.</strong> {@code COCRDSL}'s
 *       contribution is its <em>navigation triple</em>: {@code LIT-CARDDTLMAPSET VALUE 'COCRDSL'}
 *       [{@code :199-200}] and {@code LIT-CARDDTLMAP VALUE 'CCRDSLA'} [{@code :201-202}] are moved into
 *       {@code CCARD-NEXT-MAPSET} and {@code CCARD-NEXT-MAP} at {@code :528-529} immediately before the
 *       transfer at {@code :538}. The plan's summary says {@code COCRDSL} is copied by both
 *       {@code COCRDSLC} and {@code COCRDLIC}; in this program that copy is commented, and the
 *       divergence is recorded rather than reconciled away (practice B4).</li>
 * </ul>
 * <p>The dependency on the screen this program does not own is nonetheless genuine and is asserted
 * here: {@link CardListController} imports {@link CardSelectRequest} and {@link CardSelectResponse} and
 * checks its four card-detail literals against them, so the two screens cannot drift apart.
 * {@link #cardSelectProjectionIsSharedNotDuplicated()} pins that, and pins the 15 field lengths coming
 * from a single type rather than from a copy - duplicating the DTO would create two sources of truth
 * for those 15 widths.
 *
 * <h2>The unit is the controller, constructed as a plain Java object</h2>
 * <p>{@code unitKind} is {@link UnitKind#CONTROLLER_POJO}. The plan assigns this program no service
 * class - within the {@code card} package only {@code COCRDUPC} has one - so every decision
 * {@code COCRDLIC} makes lives in {@link CardListController}, and reaching it means constructing the
 * controller. It is constructed through its canonical constructor
 * {@code CardListController(CardRepository, FixedWidthCodec, Clock)} with a fixture-backed repository
 * stub, and its handler method is then called directly.
 *
 * <p>This satisfies gate <strong>G51</strong> rather than bending it. The requirement is that no HTTP
 * layer sits between the assertion and the code, and calling a Java method on a Java object involves
 * none: there is no {@code MockMvc}, no {@code TestRestTemplate}, no {@code WebTestClient}, no servlet
 * container, no dispatcher, no filter chain, no {@code JobLauncher} and no JSON round trip anywhere in
 * this file. There is no Spring context either - the constructor needs only the three collaborators it
 * declares.
 *
 * <h2>What the harness can express, and what it cannot</h2>
 * <p>{@link ParityCase.ScreenRequest} carries {@code EIBCALEN}, the AID, the {@code CDEMO-*}
 * communication area and the {@code xxxI} map items. {@code COCRDLIC}'s pagination cursor is
 * <em>not</em> in that vocabulary: {@code WS-THIS-PROGCOMMAREA} [{@code :229-248}] names its fields
 * {@code WS-CA-LAST-CARD-NUM}, {@code WS-CA-SCREEN-NUM}, {@code WS-CA-NEXT-PAGE-IND} and the rest, and
 * the case model's communication-area member accepts {@code CDEMO-} names only - correctly, since those
 * are the fields {@code app/cpy/COCOM01Y.cpy} defines.
 *
 * <p>So the 20 cases cover every path reachable from an inbound cursor in its {@code INITIALIZE}d
 * state, and the two arms that require a cursor already carrying a page - {@code PF8} page-down at
 * {@code :486-497} and {@code PF7} page-up at {@code :501-513}, the latter being the only caller of the
 * {@code :1284-1286} {@code COMPUTE} - are driven by {@link #pageDownAdvancesOnlyByPayloadCursor()} and
 * {@link #pageUpComputesScrnCounterAsMaxScreenLinesPlusOne()}, which build the cursor in the request
 * payload. That is not a workaround: it is the same statelessness the gate asks for
 * (<strong>G37</strong>, rule <strong>R6</strong>), demonstrated by the fact that the only way to reach
 * page two is to send page one's cursor back.
 *
 * @see ParityHarness for the seed, the invocation and the fingerprint
 * @see FieldDiffer for the field-by-field comparison and the diff count the gate is stated in
 */
class COCRDLICParityTest {

    // =================================================================================================
    // Identity. The program name doubles as the resource directory name, which is why it is one
    // constant and not two.
    // =================================================================================================

    /** The COBOL program under parity, and the {@code parity/<PROGRAM>/} directory holding its cases. */
    private static final String PROGRAM = "COCRDLIC";

    /**
     * The dataset binding key {@code COCRDLIC} browses, from {@code app/csd/CARDDEMO.CSD} and
     * {@code src/main/resources/application-test.yml}.
     *
     * <p>{@code CARDDAT} is the base KSDS. {@code CARDAIX} is an alternate-index <em>path</em> over the
     * same base keyed by account id, not a second dataset and not a second table (gate
     * <strong>G45</strong>) - {@link #cardAixIsAPathOverCarddatNotASecondTable()} pins that. No
     * fully-qualified dataset name appears anywhere in this file (gate <strong>G46</strong>); a case
     * addresses a dataset through this key alone.
     */
    private static final String CARDDAT = CardRepository.BASE_DD_NAME;

    /** The 1-based COBOL subscript of the first screen row, {@code app/cbl/COCRDLIC.cbl:1099}. */
    private static final int FIRST_ROW = 1;

    /**
     * {@code WS-MAX-SCREEN-LINES}, {@code app/cbl/COCRDLIC.cbl:177-178}.
     *
     * <p>Restated here as a literal on purpose. Reading it from {@link CardListRequest#PAGE_SIZE} would
     * make this file agree with the implementation by construction and assert nothing; a literal
     * disagrees loudly when either side moves.
     */
    private static final int MAX_SCREEN_LINES = 7;

    /** {@code LIT-THISPGM}, {@code app/cbl/COCRDLIC.cbl:179-180}. */
    private static final String THIS_PROGRAM = "COCRDLIC";

    /** {@code LIT-THISTRANID}, {@code app/cbl/COCRDLIC.cbl:181-182}. */
    private static final String THIS_TRANID = "CCLI";

    /** {@code LIT-MENUPGM}, the literal target of the {@code XCTL} at {@code :403}. */
    private static final String MENU_PROGRAM = "COMEN01C";

    /** {@code LIT-CARDDTLPGM}, moved into {@code CCARD-NEXT-PROG} at {@code :526} for the {@code :539} transfer. */
    private static final String CARD_DETAIL_PROGRAM = "COCRDSLC";

    /** {@code LIT-CARDUPDPGM}, moved into {@code CCARD-NEXT-PROG} at {@code :554} for the {@code :567} transfer. */
    private static final String CARD_UPDATE_PROGRAM = "COCRDUPC";

    /** The {@code PIC X} pad character, and what an unwritten alphanumeric map item holds after a {@code MOVE SPACES}. */
    private static final char SPACE = ' ';

    /** {@code LOW-VALUES}, one byte of binary zero - what {@code MOVE LOW-VALUES TO CCRDLIAO} [{@code :643}] leaves. */
    private static final char LOW_VALUE = '\u0000';

    /**
     * The prefix that tells {@code WS-THIS-PROGCOMMAREA}'s items apart from
     * {@code CARDDEMO-COMMAREA}'s inside one declared communication area.
     *
     * <p>{@code app/cbl/COCRDLIC.cbl:229-248} names every item of this program's own 254-byte
     * extension {@code WS-CA-...} or {@code WS-RETURN-FLAG}, while every item of the shared 160-byte
     * area is named {@code CDEMO-...} [{@code app/cpy/COCOM01Y.cpy}]. The two halves arrive as one
     * {@code DFHCOMMAREA} [{@code :327-331}], so a case declares them together and this prefix routes
     * each name to the half it belongs to: {@link #navigationFrom} takes the {@code CDEMO-} items and
     * {@link #pageCursorFrom} takes these.
     */
    private static final String CURSOR_FIELD_PREFIX = "WS-";

    /**
     * The {@code xxxI} map items {@code COCRDLIC} actually reads, and nothing else.
     *
     * <p>{@code 2100-RECEIVE-SCREEN} reads {@code ACCTSIDI} and {@code CARDSIDI} [{@code :969-970}] and
     * the seven {@code CRDSELnI} items [{@code :972-978}]; the row table is read back from
     * {@code ACCTNOnI}, {@code CRDNUMnI} and {@code CRDSTSnI}, which are the input twins of the items
     * {@code COMMON-RETURN} sent out. The nine header items and the two message items are never read by
     * this program, so a case naming one of them would pin an input the program discards - which
     * {@link #mapFieldOf} refuses rather than silently ignores.
     */
    private static final Set<String> READABLE_MAP_ITEMS = buildReadableMapItems();

    /** Every {@code DFHAID} mnemonic mapped back to the byte it names, inverted from {@link CicsAid}. */
    private static final Map<String, Byte> AID_BY_MNEMONIC = buildAidByMnemonic();

    /**
     * The authoritative card fixture on the test classpath - a byte-for-byte copy of
     * {@code app/data/ASCII/carddata.txt}, 50 records of 150 bytes ascending by card number.
     */
    private static final String CARD_FIXTURE = "fixtures/carddata.txt";

    /**
     * The instant every case and every targeted assertion pins, so {@code CURDATEO} renders
     * {@code 07/19/22} and {@code CURTIMEO} renders {@code 23:12:34} on every run (gate
     * <strong>G54</strong>).
     */
    private static final String PINNED_CLOCK = "2022-07-19T23:12:34";

    /**
     * The harness, rebuilt per test method.
     *
     * <p>An instance field rather than a {@code static} one, so nothing about this class is shared
     * mutable state (practice <strong>B9</strong>, gate <strong>G53</strong>) and two methods cannot
     * observe each other's work.
     */
    private ParityHarness harness;

    @BeforeEach
    void buildHarness() {
        // US-ASCII, named explicitly. The fixtures are ASCII and the platform default is never
        // consulted anywhere in this module (practice B8).
        harness = ParityHarness.usAscii();
    }

    // =================================================================================================
    // THE GATE - 20 declarative cases, diff count zero on every one of them.
    // =================================================================================================

    /**
     * Loads {@code COCRDLIC}'s cases and refuses any count other than twenty (gate
     * <strong>G15</strong>).
     *
     * <p>{@link ParityHarness#casesOf(String)} loads {@code case01} through {@code case20} by name, so
     * a missing file fails there. The count is re-checked here anyway, and loudly: the gate is stated
     * as twenty cases per program, and a suite that quietly ran nineteen would report a clean diff
     * count for a program one of whose branches nobody had looked at.
     *
     * @return the twenty cases in {@code case01}..{@code case20} order
     */
    static List<ParityCase> cases() {
        List<ParityCase> loaded = ParityHarness.casesOf(PROGRAM);
        if (loaded.size() != ParityHarness.CASES_PER_PROGRAM) {
            throw new IllegalStateException("Program " + PROGRAM + " must have exactly "
                    + ParityHarness.CASES_PER_PROGRAM + " parity cases but "
                    + ParityHarness.CASE_RESOURCE_ROOT + PROGRAM + "/ yielded " + loaded.size()
                    + ". The gate is stated as 20 cases per program and a module is not complete until "
                    + "its diff count is zero across all 20 of them, so a short list is a smaller gate "
                    + "wearing the same name.");
        }
        return loaded;
    }

    /**
     * Runs one case and requires a diff count of exactly zero (gate <strong>G18</strong>).
     *
     * <p>{@link ParityHarness#judge} deliberately does not assert - it returns the differences so the
     * assertion can live here and read as "3 difference(s) on case07", with all three rendered. The
     * rendering is passed as the assertion description rather than concatenated into a message, so
     * AssertJ prints it only on failure.
     *
     * @param parityCase one of the twenty cases; supplied by {@link #cases()}
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("COCRDLIC parity: every field of every case matches, diff count zero")
    void parityCaseHasZeroDiffs(ParityCase parityCase) {
        DiffResult result = harness.judge(parityCase, UnitKind.CONTROLLER_POJO,
                this::invokeCardList);

        assertThat(result.count())
                .as("%s/%s produced %d difference(s):%n%s", PROGRAM, parityCase.caseId(),
                        result.count(), result.render())
                .isZero();
    }

    // =================================================================================================
    // THE ADAPTER - construct CardListController as a plain object and call its handler.
    // =================================================================================================

    /**
     * Constructs the controller and calls it, then records what the call observably produced.
     *
     * <p>Three collaborators and nothing else: the fixture-backed repository stub, the codec carrying
     * the case's named code page, and the case's pinned clock. The clock is pinned so
     * {@code FUNCTION CURRENT-DATE} [{@code :645}, {@code :652}] renders the same {@code CURDATEO} and
     * {@code CURTIMEO} on every run (practice <strong>B7</strong>, gate <strong>G54</strong>).
     *
     * <p>{@code COCRDLIC} writes to no dataset. It browses {@code CARDDAT} and returns, so the final
     * state is the seed unchanged - which is a positive assertion, not an omission: it says the
     * transaction left the card master exactly as it found it.
     *
     * @param invocation the seeded datasets, the online request, the clock, the codec and the recorder
     * @return the outcome the harness fingerprints
     */
    private UnitOutcome invokeCardList(Invocation invocation) {
        SeededDataset carddat = invocation.dataset(CARDDAT);
        FixedWidthCodec codec = invocation.codec();

        CardListController controller = new CardListController(
                stubbedCardRepository(invocation, carddat), codec, invocation.clock());

        CardListResponse painted = controller.getCards(
                requestFrom(invocation, codec),
                Integer.valueOf(Byte.toUnsignedInt(aidByteOf(invocation))),
                null).screen();

        return invocation.recorder()
                .finalStateUnchanged(carddat, CardRecord.LAYOUT)
                .response(project(painted, codec))
                // COCRDLIC contains no CALL 'CEE3ABD' and never moves anything into RETURN-CODE, so a
                // completed online transaction reports zero. An abend would arrive at the harness as
                // AbendException and be compared like any other expectation.
                .returnCode(FileStatus.APPL_AOK)
                .build();
    }

    /**
     * A {@link CardRepository} whose browse walks the seeded {@code CARDDAT} rows.
     *
     * <p>{@code COCRDLIC} reaches the card master through exactly one operation -
     * {@code EXEC CICS STARTBR} followed by {@code READNEXT} [{@code :1129-1154}] or {@code READPREV}
     * [{@code :1273-1330}] - so this is the whole seam, and the stub reproduces the two VSAM behaviours
     * the program depends on:
     * <ul>
     *   <li><strong>{@code GTEQ} positioning.</strong> {@code STARTBR ... GTEQ} [{@code :1133}] anchors
     *       on the first key greater than or equal to {@code WS-CARD-RID-CARDNUM}. On a fresh cursor
     *       that field holds {@code LOW-VALUES}, which sorts below every key, so the browse anchors on
     *       the first row - which is why a cold start lists from the top of the file. Backwards, the
     *       anchor is the last key less than or equal to it.</li>
     *   <li><strong>Ascending key order.</strong> The rows are sorted by the 16-byte
     *       {@code CARD-NUM} primary key [{@code app/cpy/CVACT02Y.cpy}], because a KSDS browse returns
     *       key order and not insertion order. The fixture is already in that order; sorting it here
     *       means a case that seeds a subset in any order still browses like the dataset it stands
     *       for.</li>
     * </ul>
     *
     * <p>A case may declare a forced {@link RepositoryOperation#READ_NEXT} or
     * {@link RepositoryOperation#START_BROWSE} outcome to reach the {@code WHEN OTHER} arms at
     * {@code :1246-1254}, {@code :1222-1230} and {@code :1361-1369}. Those arms are unreachable from any
     * arrangement of seeded rows - they need a genuine I/O failure - and the {@code RESP} and
     * {@code RESP2} values matter as well as the arm, because {@code :1252-1253} moves both into the
     * composed {@code WS-FILE-ERROR-MESSAGE}.
     *
     * <p>A case may equally declare {@link FileStatus.Outcome#DUPLICATE}, which is the other outcome no
     * arrangement of seeded rows produces: {@code CARDDAT}'s primary key is unique, so only the
     * alternate-index path this program never opens could present a duplicate key. Each read then serves
     * the row the seed holds under {@code DFHRESP(DUPREC)}, which drives the second condition of the
     * shared pair at {@code :1157-1158} and {@code :1208-1209}. See {@link #declaredReadOutcome} for why
     * the remaining three outcomes stay refused.
     *
     * @param invocation the invocation, consulted for a forced outcome
     * @param carddat    the seeded card master
     * @return the stub; never {@code null}
     */
    private CardRepository stubbedCardRepository(Invocation invocation, SeededDataset carddat) {
        List<String> rows = new ArrayList<>(carddat.rows());
        rows.sort(Comparator.comparing(COCRDLICParityTest::primaryKeyOf));

        CardRepository repository = Mockito.mock(CardRepository.class);
        Mockito.when(repository.startBrowse(Mockito.anyString(), Mockito.any(BrowseDirection.class)))
                .thenAnswer(call -> {
                    // Taken here, inside the answer, so a declared outcome is consumed only when the
                    // unit actually opens a browse. Hoisting it into the method body would consume it
                    // even on a path that never reads, and the harness could no longer tell a case that
                    // drove an arm from one that only claimed to.
                    Optional<ParityCase.ForcedOutcome> declared = declaredReadOutcome(invocation);
                    return browseOver(rows, call.getArgument(0), call.getArgument(1),
                            forcedFailure(declared), forcesDuplicateKey(declared));
                });
        return repository;
    }

    /**
     * The forced read outcome a case declared, taken through the invocation so the harness can see it
     * was consumed.
     *
     * <p>Taken when the browse opens, and taken unconditionally when declared. The harness refuses a run
     * that left a declared forced outcome unasked-for, and rightly: an unconsumed declaration forces
     * nothing, the run takes the ordinary path, and the case passes while its description claims it
     * drove an arm it never reached.
     *
     * <p><strong>Two outcomes may be forced, and only two.</strong> Both name an arm of
     * {@code EVALUATE WS-RESP-CD} that no arrangement of seeded {@code CARDDAT} rows can reach:
     * <ul>
     *   <li>{@link FileStatus.Outcome#OTHER} - {@code WHEN OTHER} at {@code :1246-1254},
     *       {@code :1222-1230} and {@code :1361-1369}. It needs a genuine I/O failure, and the
     *       {@code RESP} and {@code RESP2} values matter as well as the arm because {@code :1252-1253}
     *       moves both into the composed {@code WS-FILE-ERROR-MESSAGE}.</li>
     *   <li>{@link FileStatus.Outcome#DUPLICATE} - the {@code WHEN DFHRESP(DUPREC)} half of the shared
     *       pair at {@code :1157-1158} and {@code :1208-1209}. {@code COCRDLIC} browses
     *       {@code LIT-CARD-FILE} {@code 'CARDDAT '} [{@code :213-214}], whose primary key is unique, so
     *       a base-KSDS read never presents a duplicate key however the rows are arranged - the
     *       non-unique alternate key belongs to {@code CARDAIX} [{@code :215-217}], which this program
     *       declares and never opens. Forcing is therefore the only route to an arm the source genuinely
     *       has, and gate <strong>G47</strong> requires the {@code '22'} outcome to be driven at each
     *       call site that has it.</li>
     * </ul>
     *
     * <p>{@link FileStatus.Outcome#OK} and {@link FileStatus.Outcome#END_OF_FILE} stay refused because
     * seeding produces them - a row, or fewer rows than the browse asks for - and forcing either would
     * hide the seed that should have produced it. {@link FileStatus.Outcome#NOT_FOUND} stays refused
     * because a browse never returns it at all.
     *
     * @param invocation the invocation
     * @return the outcome the case declared, or {@link Optional#empty()} when it forces nothing
     */
    private static Optional<ParityCase.ForcedOutcome> declaredReadOutcome(Invocation invocation) {
        if (!invocation.hasForcedOutcome(RepositoryOperation.READ_NEXT)) {
            return Optional.empty();
        }
        ParityCase.ForcedOutcome forced = invocation.forcedOutcome(RepositoryOperation.READ_NEXT);
        if (forced.outcome() != FileStatus.Outcome.OTHER
                && forced.outcome() != FileStatus.Outcome.DUPLICATE) {
            throw new IllegalArgumentException("Case " + invocation.program() + '/'
                    + invocation.caseId() + " forces read outcome " + forced.outcome()
                    + ", but the only read outcomes COCRDLIC cannot be driven to by seeding rows are "
                    + FileStatus.Outcome.OTHER + " and " + FileStatus.Outcome.DUPLICATE
                    + ". OK arrives by seeding a row, END_OF_FILE by seeding fewer rows than the "
                    + "browse asks for, and NOT_FOUND is never returned by a browse at all - forcing "
                    + "any of those would hide the seed that should have produced it.");
        }
        return Optional.of(forced);
    }

    /**
     * The failure a case declared, ready to be returned instead of a record.
     *
     * @param declared the declared outcome, if any
     * @return the failure result, or {@link Optional#empty()} unless {@link FileStatus.Outcome#OTHER}
     *     was declared
     */
    private static Optional<CardReadResult> forcedFailure(
            Optional<ParityCase.ForcedOutcome> declared) {

        if (declared.isEmpty() || declared.get().outcome() != FileStatus.Outcome.OTHER) {
            return Optional.empty();
        }
        ParityCase.ForcedOutcome forced = declared.get();
        int resp = forced.resp() == null ? FileStatus.INVREQ : forced.resp();
        int resp2 = forced.resp2() == null ? FileStatus.NO_REASON_CODE : forced.resp2();
        return Optional.of(CardReadResult.reportedFailure(resp, resp2));
    }

    /**
     * Whether every read of the browse must report {@code DFHRESP(DUPREC)} over the row it returns.
     *
     * <p>{@link ParityCase.ForcedOutcome} carries no occurrence index, so a per-operation declaration
     * means every read of that operation - which is the whole assertion here. Each read still returns
     * the record the seed would have returned, only under {@link FileStatus.Outcome#DUPLICATE}, so a
     * translation that had split the shared {@code :1157-1158} body would place different rows and diff,
     * while the faithful one produces a page byte-identical to the {@code NORMAL} run. The identity is
     * the proof that consecutive {@code WHEN} phrases share the statements that follow them.
     *
     * @param declared the declared outcome, if any
     * @return {@code true} when {@link FileStatus.Outcome#DUPLICATE} was declared
     */
    private static boolean forcesDuplicateKey(Optional<ParityCase.ForcedOutcome> declared) {
        return declared.isPresent()
                && declared.get().outcome() == FileStatus.Outcome.DUPLICATE;
    }

    /**
     * One browse over the seeded rows, positioned the way {@code STARTBR ... GTEQ} positions.
     *
     * <p>The cursor is per-browse local state held in the returned mock's answer, so two browses in one
     * run - the forward read and the look-ahead share one, the page-up read opens its own - cannot see
     * each other's position.
     *
     * @param rows      the seeded rows in key order
     * @param anchor    {@code WS-CARD-RID-CARDNUM}, the {@code RIDFLD}
     * @param direction forward for {@code READNEXT}, backward for {@code READPREV}
     * @param forced    a forced failure to return instead of a record, if the case declared one
     * @return the browse; never {@code null}
     */
    private static CardBrowse browseOver(List<String> rows, String anchor,
            BrowseDirection direction, Optional<CardReadResult> forced) {

        return browseOver(rows, anchor, direction, forced, false);
    }

    /**
     * One browse over the seeded rows, optionally reporting every record it returns as a duplicate key.
     *
     * @param rows              the seeded rows in key order
     * @param anchor            {@code WS-CARD-RID-CARDNUM}, the {@code RIDFLD}
     * @param direction         forward for {@code READNEXT}, backward for {@code READPREV}
     * @param forced            a forced failure to return instead of a record, if the case declared one
     * @param reportDuplicateKey whether each record-bearing read reports {@code DFHRESP(DUPREC)}
     * @return the browse; never {@code null}
     */
    private static CardBrowse browseOver(List<String> rows, String anchor,
            BrowseDirection direction, Optional<CardReadResult> forced,
            boolean reportDuplicateKey) {

        CardBrowse browse = Mockito.mock(CardBrowse.class);
        // int[1] rather than a field: this is per-browse state, unreachable from anywhere else, and a
        // field would be shared mutable state on the test class (practice B9).
        int[] position = new int[1];
        boolean[] positioned = new boolean[1];

        Mockito.when(browse.readNext()).thenAnswer(call -> forced.orElseGet(() ->
                asDeclared(read(rows, anchor, direction, position, positioned),
                        reportDuplicateKey)));
        Mockito.when(browse.readPrev()).thenAnswer(call -> forced.orElseGet(() ->
                asDeclared(read(rows, anchor, direction, position, positioned),
                        reportDuplicateKey)));
        return browse;
    }

    /**
     * Re-reports a served read under {@code DFHRESP(DUPREC)} when the case declared that outcome.
     *
     * <p>The record and its exact bytes are the ones the seed served, and the browse has already
     * advanced over them, so the only thing that changes is the response the program evaluates: the
     * shared {@code WHEN DFHRESP(NORMAL)} / {@code WHEN DFHRESP(DUPREC)} body at {@code :1157-1158} is
     * entered through its second condition rather than its first. An end of file is left alone -
     * exhausting the rows is how {@code DFHRESP(ENDFILE)} arrives, and a duplicate-key declaration must
     * not manufacture a record where the seed has none.
     *
     * @param served             the read the seeded browse produced
     * @param reportDuplicateKey whether to re-report a record-bearing read as a duplicate key
     * @return the read outcome the program sees; never {@code null}
     */
    private static CardReadResult asDeclared(CardReadResult served, boolean reportDuplicateKey) {
        if (!reportDuplicateKey || !served.isRecordReturned()) {
            return served;
        }
        return CardReadResult.duplicateKey(served.requireRecord(), served.requireStoredImage());
    }

    /**
     * The next record of a browse, or {@code DFHRESP(ENDFILE)} when the browse has run off the end.
     *
     * <p>The first read positions and returns; every later read advances first. That is the CICS
     * sequence: {@code STARTBR} establishes the position and the first {@code READNEXT} returns the
     * record at it.
     *
     * @param rows       the seeded rows in key order
     * @param anchor     the {@code RIDFLD} the browse was started on
     * @param direction  the browse direction
     * @param position   the one-element cursor
     * @param positioned whether the first read has happened
     * @return the read result; never {@code null}
     */
    private static CardReadResult read(List<String> rows, String anchor, BrowseDirection direction,
            int[] position, boolean[] positioned) {

        if (!positioned[0]) {
            positioned[0] = true;
            int anchored = anchorIndex(rows, anchor, direction);
            if (anchored < 0) {
                return CardReadResult.endOfFile();
            }
            position[0] = anchored;
        } else {
            position[0] += direction == BrowseDirection.FORWARD ? 1 : -1;
        }
        if (position[0] < 0 || position[0] >= rows.size()) {
            return CardReadResult.endOfFile();
        }
        String image = rows.get(position[0]);
        return CardReadResult.normal(CardRecord.decodeImage(image, ParityHarness.FIXTURE_CHARSET),
                image);
    }

    /**
     * Where {@code STARTBR ... GTEQ} lands: the first key at or after the anchor going forward, the
     * last key at or before it going backward.
     *
     * @param rows      the seeded rows in key order
     * @param anchor    the {@code RIDFLD}
     * @param direction the browse direction
     * @return the zero-based index, or {@code -1} when no row satisfies the anchor
     */
    private static int anchorIndex(List<String> rows, String anchor, BrowseDirection direction) {
        String key = anchor.length() > CardRecord.CARD_NUM_LENGTH
                ? anchor.substring(0, CardRecord.CARD_NUM_LENGTH)
                : anchor;
        if (direction == BrowseDirection.FORWARD) {
            for (int index = 0; index < rows.size(); index++) {
                if (primaryKeyOf(rows.get(index)).compareTo(key) >= 0) {
                    return index;
                }
            }
            return -1;
        }
        for (int index = rows.size() - 1; index >= 0; index--) {
            if (primaryKeyOf(rows.get(index)).compareTo(key) <= 0) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The 16-byte {@code CARD-NUM} primary key of a 150-byte row, {@code app/cpy/CVACT02Y.cpy}.
     *
     * @param row the record image
     * @return the key; never {@code null}
     */
    private static String primaryKeyOf(String row) {
        return row.substring(0, CardRecord.CARD_NUM_LENGTH);
    }

    // =================================================================================================
    // REQUEST - the inbound payload, built from the case's screen request and nothing else.
    // =================================================================================================

    /**
     * The payload the transaction is invoked with, or {@code null} for {@code EIBCALEN = 0}.
     *
     * <p>{@code app/cbl/COCRDLIC.cbl:315} tests {@code IF EIBCALEN = 0} to tell a transaction started
     * fresh from one continuing a pseudo-conversation. A {@code GET} nobody sent a body with is exactly
     * that, so a case declaring {@code eibcalen: 0} passes {@code null} - and the controller's own
     * {@code null} handling is then part of what is under test rather than something the adapter papers
     * over.
     *
     * <p>Everything a continuing request carries comes from the case: the communication area from
     * {@link Invocation#commarea()} and the received map items from {@link Invocation#mapFields()}.
     * Nothing is defaulted in silently, because a field the case did not state is a field nobody chose.
     *
     * @param invocation the invocation
     * @param codec      the codec, for the {@code PIC X} move rule each item's width imposes
     * @return the payload, or {@code null} for the cold start
     */
    private static CardListRequest requestFrom(Invocation invocation, FixedWidthCodec codec) {
        if (invocation.eibcalen() == 0) {
            return null;
        }
        CardListRequest request = new CardListRequest();
        request.setNavigationContext(navigationFrom(invocation.commarea(), codec));
        request.setPageCursor(pageCursorFrom(invocation.commarea(), codec, invocation));

        Map<String, String> map = invocation.mapFields();
        request.setAcctsid(codec.movePicX(mapFieldOf(map, "ACCTSIDI", invocation),
                CardListRequest.ACCTSID_LENGTH));
        request.setCardsid(codec.movePicX(mapFieldOf(map, "CARDSIDI", invocation),
                CardListRequest.CARDSID_LENGTH));
        request.setRows(rowsFrom(map, invocation, codec));
        requireOnlyReadableMapItems(map, invocation);
        return request;
    }

    /**
     * The seven {@code CCRDLIAI} rows, in COBOL subscript order.
     *
     * <p>Row one is a {@link FirstListRow} of four items and rows two through seven are
     * {@link StopperListRow}s of five: {@code app/bms/COCRDLI.bms} gives rows two onward a
     * {@code CRDSTPn} stopper field and row one none, and the asymmetry is the map's, not a modelling
     * choice. A {@code CRDSTPn} value is never read by {@code COCRDLIC} - {@code 2100-RECEIVE-SCREEN}
     * reads only {@code CRDSELnI} [{@code :972-978}] and the row table is read back from
     * {@code ACCTNOnI}, {@code CRDNUMnI} and {@code CRDSTSnI} - so it is carried as {@code LOW-VALUES}.
     *
     * @param map        the case's map items
     * @param invocation the invocation, for a diagnostic
     * @param codec      the codec
     * @return exactly seven rows
     */
    private static List<ListRow> rowsFrom(Map<String, String> map, Invocation invocation,
            FixedWidthCodec codec) {

        List<ListRow> rows = new ArrayList<>(MAX_SCREEN_LINES);
        for (int row = FIRST_ROW; row <= MAX_SCREEN_LINES; row++) {
            String select = codec.movePicX(mapFieldOf(map, "CRDSEL" + row + "I", invocation),
                    CardListRequest.CRDSEL_LENGTH);
            String acctNo = codec.movePicX(mapFieldOf(map, "ACCTNO" + row + "I", invocation),
                    CardListRequest.ACCTNO_LENGTH);
            String cardNum = codec.movePicX(mapFieldOf(map, "CRDNUM" + row + "I", invocation),
                    CardListRequest.CRDNUM_LENGTH);
            String status = codec.movePicX(mapFieldOf(map, "CRDSTS" + row + "I", invocation),
                    CardListRequest.CRDSTS_LENGTH);
            rows.add(row == FIRST_ROW
                    ? new FirstListRow(select, acctNo, cardNum, status)
                    : new StopperListRow(select, String.valueOf(LOW_VALUE), acctNo, cardNum, status));
        }
        return rows;
    }

    /**
     * One received map item, defaulting to {@code LOW-VALUES} at the item's own width.
     *
     * <p>{@code LOW-VALUES} rather than spaces, and the difference is behaviour rather than taste:
     * {@code 2210-EDIT-ACCOUNT} tests {@code IF CC-ACCT-ID EQUAL LOW-VALUES OR EQUAL SPACES}
     * [{@code :1007-1008}] - either satisfies the not-supplied branch - but
     * {@code 88 SELECT-BLANK VALUES ' ', LOW-VALUES} [{@code :80-82}] and
     * {@code IF WS-EACH-CARD(n) EQUAL LOW-VALUES} [{@code :680}] distinguish the two, so a field the
     * terminal never transmitted must arrive as {@code LOW-VALUES} and not as spaces.
     *
     * @param map        the case's map items
     * @param item       the {@code xxxI} item name
     * @param invocation the invocation, for a diagnostic
     * @return the value the case stated, or a single {@code LOW-VALUE} for the {@code PIC X} pad to
     *     extend
     */
    private static String mapFieldOf(Map<String, String> map, String item, Invocation invocation) {
        if (!READABLE_MAP_ITEMS.contains(item)) {
            throw new IllegalStateException("Case " + invocation.program() + '/'
                    + invocation.caseId() + " asked for map item " + item
                    + ", which this adapter does not read. The readable items are "
                    + READABLE_MAP_ITEMS + '.');
        }
        String value = map.get(item);
        return value == null ? String.valueOf(LOW_VALUE) : value;
    }

    /**
     * Refuses a case that states a map item {@code COCRDLIC} never reads.
     *
     * <p>The nine header items, {@code INFOMSGI} and {@code ERRMSGI} are transmitted by the terminal and
     * discarded by the program: {@code 2100-RECEIVE-SCREEN} [{@code :962-979}] moves nothing out of
     * them. A case that set one of them would look like it was driving the program and would in fact be
     * driving nothing, and it would keep passing after the field it named stopped existing - so it is
     * refused with the items that do count named in the message.
     *
     * @param map        the case's map items
     * @param invocation the invocation, for a diagnostic
     */
    private static void requireOnlyReadableMapItems(Map<String, String> map, Invocation invocation) {
        List<String> ignored = new ArrayList<>();
        for (String item : map.keySet()) {
            if (!READABLE_MAP_ITEMS.contains(item)) {
                ignored.add(item);
            }
        }
        if (!ignored.isEmpty()) {
            throw new IllegalStateException("Case " + invocation.program() + '/'
                    + invocation.caseId() + " states map item(s) " + ignored
                    + " that COCRDLIC never reads - 2100-RECEIVE-SCREEN at app/cbl/COCRDLIC.cbl:962-979 "
                    + "moves nothing out of the header items, INFOMSGI or ERRMSGI. Setting one drives "
                    + "nothing, so the case would assert less than it appears to. The items the program "
                    + "reads are " + READABLE_MAP_ITEMS + '.');
        }
    }

    /**
     * The inbound {@code CARDDEMO-COMMAREA}, built field by field from the case's {@code CDEMO-*} names.
     *
     * <p>Built through {@link NavigationContext#empty()} and its {@code with*} methods, so every field a
     * case does not name holds what {@code INITIALIZE CARDDEMO-COMMAREA} leaves it holding - spaces for
     * an alphanumeric item and zero for a {@code PIC 9} one. The four numeric members are parsed here
     * rather than stored as images, because {@code CDEMO-ACCT-ID} is {@code PIC 9(11)} and
     * {@code CDEMO-CARD-NUM} is {@code PIC 9(16)}; the case states them as the zero-filled digit strings
     * the copybook stores.
     *
     * @param commarea the case's communication-area fields
     * @param codec    the codec, for the {@code PIC X} move rule
     * @return the context; never {@code null}
     */
    private static NavigationContext navigationFrom(Map<String, String> commarea,
            FixedWidthCodec codec) {

        NavigationContext context = NavigationContext.empty();
        for (Map.Entry<String, String> entry : commarea.entrySet()) {
            String field = entry.getKey();
            if (field.startsWith(CURSOR_FIELD_PREFIX)) {
                // WS-THIS-PROGCOMMAREA, the second half of the same area; see pageCursorFrom.
                continue;
            }
            String value = entry.getValue();
            context = switch (field) {
                case NavigationContext.FROM_TRANID_FIELD -> context.withFromTranid(
                        codec.movePicX(value, NavigationContext.FROM_TRANID_LENGTH));
                case NavigationContext.FROM_PROGRAM_FIELD -> context.withFromProgram(
                        codec.movePicX(value, NavigationContext.FROM_PROGRAM_LENGTH));
                case NavigationContext.TO_TRANID_FIELD -> context.withToTranid(
                        codec.movePicX(value, NavigationContext.TO_TRANID_LENGTH));
                case NavigationContext.TO_PROGRAM_FIELD -> context.withToProgram(
                        codec.movePicX(value, NavigationContext.TO_PROGRAM_LENGTH));
                case NavigationContext.USER_ID_FIELD -> context.withUserId(
                        codec.movePicX(value, NavigationContext.USER_ID_LENGTH));
                case NavigationContext.USER_TYPE_FIELD -> context.withUserType(
                        codec.movePicX(value, NavigationContext.USER_TYPE_LENGTH));
                case NavigationContext.PGM_CONTEXT_FIELD -> context.withPgmContext(
                        codec.decodePic9AsInt(value));
                case NavigationContext.CUST_ID_FIELD -> context.withCustId(
                        codec.decodePic9AsInt(value));
                case NavigationContext.CUST_FNAME_FIELD -> context.withCustFname(
                        codec.movePicX(value, NavigationContext.CUST_FNAME_LENGTH));
                case NavigationContext.CUST_MNAME_FIELD -> context.withCustMname(
                        codec.movePicX(value, NavigationContext.CUST_MNAME_LENGTH));
                case NavigationContext.CUST_LNAME_FIELD -> context.withCustLname(
                        codec.movePicX(value, NavigationContext.CUST_LNAME_LENGTH));
                case NavigationContext.ACCT_ID_FIELD -> context.withAcctId(
                        codec.decodePic9(value));
                case NavigationContext.ACCT_STATUS_FIELD -> context.withAcctStatus(
                        codec.movePicX(value, NavigationContext.ACCT_STATUS_LENGTH));
                case NavigationContext.CARD_NUM_FIELD -> context.withCardNum(
                        codec.decodePic9(value));
                case NavigationContext.LAST_MAP_FIELD -> context.withLastMap(
                        codec.movePicX(value, NavigationContext.LAST_MAP_LENGTH));
                case NavigationContext.LAST_MAPSET_FIELD -> context.withLastMapset(
                        codec.movePicX(value, NavigationContext.LAST_MAPSET_LENGTH));
                default -> throw new IllegalStateException("Commarea field " + field
                        + " is not one of the sixteen CARDDEMO-COMMAREA items app/cpy/COCOM01Y.cpy "
                        + "defines. COCRDLIC's own extension is WS-THIS-PROGCOMMAREA at "
                        + "app/cbl/COCRDLIC.cbl:229-248, whose fields are named WS-CA-... and "
                        + "WS-RETURN-FLAG; those are read by pageCursorFrom, not here.");
            };
        }
        return context;
    }

    /**
     * The inbound {@code WS-THIS-PROGCOMMAREA} paging cursor, built from the {@code WS-} prefixed
     * items of the same communication area.
     *
     * <p><strong>Why the cursor arrives in the commarea.</strong>
     * {@code app/cbl/COCRDLIC.cbl:327-331} restores the two halves of one physical
     * {@code DFHCOMMAREA}: {@code MOVE DFHCOMMAREA(1:LENGTH OF CARDDEMO-COMMAREA)} into the shared
     * 160-byte area, then
     * {@code MOVE DFHCOMMAREA(LENGTH OF CARDDEMO-COMMAREA + 1: LENGTH OF WS-THIS-PROGCOMMAREA)} into
     * the program's own 254-byte extension - a 58-byte cursor prefix [{@code :229-248}] followed by
     * the 196-byte row table [{@code :252-260}]. The row table reaches this adapter as the received
     * {@code ACCTNOnI}, {@code CRDNUMnI} and {@code CRDSTSnI} items, because that is where the DTO
     * carries it; the cursor prefix has no map items to travel in, so it is declared alongside the
     * {@code CDEMO-} fields it physically shares an area with. {@code COTRN00C} does exactly the same
     * thing and needs no special handling only because it happens to name its own extension
     * {@code CDEMO-CT00-...} [{@code app/cbl/COTRN00C.cbl:62-70}].
     *
     * <p><strong>What a case that says nothing gets.</strong> {@link PageCursor#firstPage()} - which
     * is what {@code new CardListRequest()} assigns anyway, and what {@code :324-325} and
     * {@code :341-342} set: page 1 with {@code CA-LAST-PAGE-NOT-SHOWN}. A case declaring no
     * {@code WS-} item is therefore unaffected by this method and keeps the exact state it had before
     * the method existed. Only a case that means to arrive mid-browse has to say so, and it says so in
     * the copybook's own field names.
     *
     * <p>None of this is server-side state (rule <strong>R6</strong>, gate <strong>G37</strong>): the
     * cursor is read out of the payload on the way in and written back into the payload on the way
     * out, and the harness holds no browse position between invocations.
     *
     * @param commarea   the case's communication-area fields, {@code CDEMO-} and {@code WS-} together
     * @param codec      the codec, for the {@code PIC X} move rule and the {@code PIC 9} decode
     * @param invocation the invocation, for a diagnostic
     * @return the cursor the transaction is entered with; never {@code null}
     */
    private static PageCursor pageCursorFrom(Map<String, String> commarea, FixedWidthCodec codec,
            Invocation invocation) {

        PageCursor declared = PageCursor.firstPage();
        String lastCardNum = declared.lastCardKey().cardNum();
        long lastAcctId = declared.lastCardKey().acctId();
        String firstCardNum = declared.firstCardKey().cardNum();
        long firstAcctId = declared.firstCardKey().acctId();
        int screenNum = declared.screenNum();
        int lastPageDisplayed = declared.lastPageDisplayed();
        String nextPageInd = declared.nextPageInd();
        String returnFlag = declared.returnFlag();

        for (Map.Entry<String, String> entry : commarea.entrySet()) {
            String field = entry.getKey();
            if (!field.startsWith(CURSOR_FIELD_PREFIX)) {
                continue;
            }
            String value = entry.getValue();
            switch (field) {
                // :230-232  WS-CA-LAST-CARDKEY - X(16) then 9(11), what PF8 browses forward from.
                case "WS-CA-LAST-CARD-NUM" -> lastCardNum =
                        codec.movePicX(value, CardListRequest.CURSOR_CARD_NUM_LENGTH);
                case "WS-CA-LAST-CARD-ACCT-ID" -> lastAcctId = codec.decodePic9(value);
                // :233-235  WS-CA-FIRST-CARDKEY - what PF7 browses back from [:504-505].
                case "WS-CA-FIRST-CARD-NUM" -> firstCardNum =
                        codec.movePicX(value, CardListRequest.CURSOR_CARD_NUM_LENGTH);
                case "WS-CA-FIRST-CARD-ACCT-ID" -> firstAcctId = codec.decodePic9(value);
                // :237-238  WS-CA-SCREEN-NUM PIC 9(1), with 88 CA-FIRST-PAGE VALUE 1.
                case "WS-CA-SCREEN-NUM" -> screenNum = codec.decodePic9AsInt(value);
                // :239-241  WS-CA-LAST-PAGE-DISPLAYED PIC 9(1): 0 shown, 9 not shown.
                case "WS-CA-LAST-PAGE-DISPLAYED" -> lastPageDisplayed =
                        codec.decodePic9AsInt(value);
                // :242-244  WS-CA-NEXT-PAGE-IND PIC X(1): LOW-VALUES off, 'Y' on.
                case "WS-CA-NEXT-PAGE-IND" -> nextPageInd =
                        codec.movePicX(value, CardListRequest.NEXT_PAGE_IND_LENGTH);
                // :246-248  WS-RETURN-FLAG PIC X(1), declared and never assigned by the program.
                case "WS-RETURN-FLAG" -> returnFlag =
                        codec.movePicX(value, CardListRequest.RETURN_FLAG_LENGTH);
                default -> throw new IllegalStateException("Case " + invocation.program() + '/'
                        + invocation.caseId() + " states commarea field " + field
                        + ", which is not one of the eight WS-THIS-PROGCOMMAREA cursor items "
                        + "app/cbl/COCRDLIC.cbl:229-248 declares. The eight are "
                        + "WS-CA-LAST-CARD-NUM, WS-CA-LAST-CARD-ACCT-ID, WS-CA-FIRST-CARD-NUM, "
                        + "WS-CA-FIRST-CARD-ACCT-ID, WS-CA-SCREEN-NUM, WS-CA-LAST-PAGE-DISPLAYED, "
                        + "WS-CA-NEXT-PAGE-IND and WS-RETURN-FLAG. The 196-byte row table that "
                        + "follows them travels as the ACCTNOnI, CRDNUMnI and CRDSTSnI map items.");
            }
        }
        return new PageCursor(new CardListRequest.CardKey(lastCardNum, lastAcctId),
                new CardListRequest.CardKey(firstCardNum, firstAcctId),
                screenNum, lastPageDisplayed, nextPageInd, returnFlag);
    }

    // =================================================================================================
    // PROJECTION - everything the invocation observably produced, and nothing derived from the case.
    // =================================================================================================

    /**
     * Projects the painted response into the shape {@link FieldDiffer} judges.
     *
     * <p><strong>How a transfer is told from a repaint.</strong>
     * {@link CardListResponse#getNextProgram()} is written by exactly one method,
     * {@code setNextTarget}, and that method is called from exactly the two transfer sites in
     * {@link CardListController} - so a blank {@code nextProgram} means no {@code XCTL} happened. That
     * maps onto the send count exactly rather than approximately: every one of the eight arms of the
     * {@code :418-583} {@code EVALUATE} either transfers control - {@code :538}, {@code :566} - or
     * performs {@code 1000-SEND-MAP} exactly once and then {@code GO TO COMMON-RETURN}
     * [{@code :436}, {@code :452}, {@code :480}, {@code :495}, {@code :511}, {@code :580}]. There is no
     * arm that sends twice and none that sends none and still returns, and {@code :586-601} is
     * unreachable because both remaining arms transfer. So {@code XCTL} carries no send and
     * {@code RETURN_TRANSID} carries exactly one.
     *
     * @param painted the response the controller returned
     * @param codec   the codec, for rendering the communication area at its declared widths
     * @return the observed response; never {@code null}
     */
    private static ObservedResponse project(CardListResponse painted, FixedWidthCodec codec) {
        boolean transferred = !isBlank(painted.getNextProgram());
        return new ObservedResponse(
                trimmedOrNull(painted.getNextProgram()),
                trimmedOrNull(painted.getNextMapset()),
                trimmedOrNull(painted.getNextMap()),
                navigationImageOf(painted.getNavigationContext(), codec),
                transferred ? List.of() : List.of(sendOf(painted)),
                cursorItemOf(painted.screenMetadata()),
                transferred ? Termination.XCTL : Termination.RETURN_TRANSID);
    }

    /**
     * The one {@code EXEC CICS SEND MAP} a non-transferring arm performs: its 45 payload items and the
     * attribute items the program moved a value into.
     *
     * <p>All 45 items are published, at their declared widths and untrimmed, because the gate is
     * field-for-field and a projection that dropped the items it thought uninteresting would decide what
     * gets checked. {@link CardListResponse#fieldImages()} is that map, in copybook order.
     *
     * @param painted the response
     * @return the send; never {@code null}
     */
    private static ObservedSend sendOf(CardListResponse painted) {
        return new ObservedSend(painted.fieldImages(), attributesOf(painted));
    }

    /**
     * The attribute items carrying a value, keyed by symbolic-map name and valued with the
     * {@code DFHBMSCA} mnemonic the program moved.
     *
     * <p>Only the {@code xxxC} colour items appear, and only where the byte is non-zero, because those
     * are the only attribute items {@code COCRDLIC} writes to and a zero byte is what
     * {@code MOVE LOW-VALUES TO CCRDLIAO} [{@code :643}] left. Three colours reach them:
     * {@code DFHBMDAR} into {@code INFOMSGC} at {@code :671}, {@code DFHNEUTR} into the same item at
     * {@code :929}, and {@code DFHRED} into {@code ACCTSIDC} [{@code :873}], {@code CARDSIDC}
     * [{@code :878}] and {@code CRDSELnC} [{@code :756}, {@code :769}, {@code :781}, {@code :793},
     * {@code :804}, {@code :816}, {@code :827}]. There is no {@code MOVE DFHRED TO ERRMSGC} in this
     * program, which is why the error line's own colour never appears here.
     *
     * <p>The protection bytes the program moves - {@code DFHBMPRF}, {@code DFHBMPRO} and
     * {@code DFHBMFSE} at {@code :753}-{@code :830} - target {@code CRDSELnA OF CCRDLIAI}, the attribute
     * view of the <em>input</em> group's flag byte. That is not one of the output group's
     * {@code C}/{@code P}/{@code H}/{@code V} items and so has no home in this map; it is asserted
     * directly instead, by {@link #reenterHighlightsOnlyTheOffendingRow()}.
     *
     * @param painted the response
     * @return the attribute items; empty on a screen that moved no colour
     */
    private static Map<String, String> attributesOf(CardListResponse painted) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (Map.Entry<String, ScreenMetadata.FieldMetadata> quad
                : painted.screenMetadata().fields().entrySet()) {
            int colour = quad.getValue().colour();
            if (colour == 0) {
                continue;
            }
            attributes.put(quad.getKey() + 'C', attributeMnemonicOf((byte) colour));
        }
        return attributes;
    }

    /**
     * The {@code DFHBMSCA} mnemonic naming one attribute byte, looked up across all three of the
     * copybook's sections.
     *
     * <p>Colour first, because an {@code xxxC} item is a colour item and {@code DFHRED} and
     * {@code DFHNEUTR} must resolve as colours. Field attributes second, and that fallback is not
     * defensive - it is required by the source. {@code MOVE DFHBMDAR TO INFOMSGC OF CCRDLIAO}
     * [{@code app/cbl/COCRDLIC.cbl:671}] moves a <em>field-attribute</em> constant, {@code X'4C'}, into
     * a colour item; {@code DFHBMDAR} is the dark, non-display attribute and appears nowhere in the
     * copybook's colour section. The program does it anyway, so a resolver that consulted only the
     * colour table would render the byte as an unnamed hexadecimal literal and every case pinning that
     * screen would have to restate the same quirk. Highlights last, for completeness.
     *
     * <p>A byte in none of the three tables is rendered by {@link BmsAttributes#toHex(byte)} rather
     * than invented a name for, so an unknown value reads as unknown.
     *
     * @param attribute the byte the program moved
     * @return the mnemonic, or the hexadecimal rendering when the copybook names no such value
     */
    private static String attributeMnemonicOf(byte attribute) {
        Byte key = Byte.valueOf(attribute);
        String colour = BmsAttributes.COLOUR_MNEMONICS.get(key);
        if (colour != null) {
            return colour;
        }
        String field = BmsAttributes.FIELD_ATTRIBUTE_MNEMONICS.get(key);
        if (field != null) {
            return field;
        }
        String highlight = BmsAttributes.HIGHLIGHT_MNEMONICS.get(key);
        return highlight != null ? highlight : BmsAttributes.toHex(attribute);
    }

    /**
     * The {@code xxxL} item that received {@code MOVE -1}, which is how COBOL positions the cursor.
     *
     * <p>{@link ScreenMetadata#cursorField()} carries the {@code DFHMDF} label - {@code ACCTSID} - and
     * the length item is that label with {@code L} appended, so {@code ACCTSIDL} is what a case pins.
     * The suffix is added here rather than stored twice.
     *
     * @param metadata the screen's metadata
     * @return the {@code xxxL} item name, or {@code null} when the program issued no cursor request
     */
    private static String cursorItemOf(ScreenMetadata metadata) {
        String label = metadata.cursorField();
        return label == null ? null : label + 'L';
    }

    /**
     * The communication area as sixteen named images at their copybook widths.
     *
     * <p>This is where statelessness is actually asserted (rule <strong>R6</strong>, gate
     * <strong>G37</strong>): the conversation state is in the payload, so it is comparable at all, and
     * {@link FieldDiffer} compares it in both directions - a field the case failed to pin is reported
     * just as loudly as one that differs.
     *
     * @param context the returned context
     * @param codec   the codec, applying the {@code PIC X} pad and the {@code PIC 9} zero-fill
     * @return the sixteen images keyed by copybook name, in copybook order
     */
    private static Map<String, String> navigationImageOf(NavigationContext context,
            FixedWidthCodec codec) {

        Map<String, String> images = new LinkedHashMap<>();
        images.put(NavigationContext.FROM_TRANID_FIELD,
                codec.movePicX(context.fromTranid(), NavigationContext.FROM_TRANID_LENGTH));
        images.put(NavigationContext.FROM_PROGRAM_FIELD,
                codec.movePicX(context.fromProgram(), NavigationContext.FROM_PROGRAM_LENGTH));
        images.put(NavigationContext.TO_TRANID_FIELD,
                codec.movePicX(context.toTranid(), NavigationContext.TO_TRANID_LENGTH));
        images.put(NavigationContext.TO_PROGRAM_FIELD,
                codec.movePicX(context.toProgram(), NavigationContext.TO_PROGRAM_LENGTH));
        images.put(NavigationContext.USER_ID_FIELD,
                codec.movePicX(context.userId(), NavigationContext.USER_ID_LENGTH));
        images.put(NavigationContext.USER_TYPE_FIELD,
                codec.movePicX(context.userType(), NavigationContext.USER_TYPE_LENGTH));
        images.put(NavigationContext.PGM_CONTEXT_FIELD,
                codec.movePic9(context.pgmContext(), NavigationContext.PGM_CONTEXT_LENGTH));
        images.put(NavigationContext.CUST_ID_FIELD,
                codec.movePic9(context.custId(), NavigationContext.CUST_ID_LENGTH));
        images.put(NavigationContext.CUST_FNAME_FIELD,
                codec.movePicX(context.custFname(), NavigationContext.CUST_FNAME_LENGTH));
        images.put(NavigationContext.CUST_MNAME_FIELD,
                codec.movePicX(context.custMname(), NavigationContext.CUST_MNAME_LENGTH));
        images.put(NavigationContext.CUST_LNAME_FIELD,
                codec.movePicX(context.custLname(), NavigationContext.CUST_LNAME_LENGTH));
        images.put(NavigationContext.ACCT_ID_FIELD,
                codec.movePic9(context.acctId(), NavigationContext.ACCT_ID_LENGTH));
        images.put(NavigationContext.ACCT_STATUS_FIELD,
                codec.movePicX(context.acctStatus(), NavigationContext.ACCT_STATUS_LENGTH));
        images.put(NavigationContext.CARD_NUM_FIELD,
                codec.movePic9(context.cardNum(), NavigationContext.CARD_NUM_LENGTH));
        images.put(NavigationContext.LAST_MAP_FIELD,
                codec.movePicX(context.lastMap(), NavigationContext.LAST_MAP_LENGTH));
        images.put(NavigationContext.LAST_MAPSET_FIELD,
                codec.movePicX(context.lastMapset(), NavigationContext.LAST_MAPSET_LENGTH));
        return images;
    }

    /**
     * The AID byte the case named, defaulting to {@code DFHENTER}.
     *
     * <p>{@code DFHENTER} is the right default because it is the key the program itself falls back to:
     * {@code :378-380} forces {@code CCARD-AID-ENTER} for every AID it does not handle, so no other
     * default could reach a branch an operator could not have reached.
     *
     * @param invocation the invocation
     * @return the raw {@code EIBAID} byte
     */
    private static byte aidByteOf(Invocation invocation) {
        String mnemonic = invocation.aid();
        if (mnemonic == null) {
            return CicsAid.DFHENTER;
        }
        Byte aid = AID_BY_MNEMONIC.get(mnemonic);
        if (aid == null) {
            throw new IllegalStateException("Case " + invocation.program() + '/'
                    + invocation.caseId() + " names AID " + mnemonic
                    + ", which common.CicsAid does not publish. The permitted set is "
                    + new TreeSet<>(AID_BY_MNEMONIC.keySet()) + '.');
        }
        return aid.byteValue();
    }

    /**
     * Whether a value carries nothing - entirely spaces, entirely {@code LOW-VALUES}, or empty.
     *
     * @param value the value
     * @return {@code true} when it carries no data
     */
    private static boolean isBlank(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != SPACE && character != LOW_VALUE) {
                return false;
            }
        }
        return true;
    }

    /**
     * A navigation name trimmed to the token it carries, or {@code null} when it carries none.
     *
     * <p>{@code CCARD-NEXT-PROG} is {@code PIC X(8)} and {@code CCARD-NEXT-MAPSET} and
     * {@code CCARD-NEXT-MAP} are {@code PIC X(7)} [{@code app/cpy/CVCRD01Y.cpy}], so a seven-character
     * program name arrives space-padded. The pad is removed here because the response fields are
     * <em>names</em> rather than record spans - a client resolves them, it does not lay them out - and
     * {@link ParityCase.ExpectedResponse} validates them as names for the same reason.
     *
     * @param value the padded name
     * @return the trimmed name, or {@code null} when the field carries nothing
     */
    private static String trimmedOrNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    /**
     * The {@code xxxI} items {@code COCRDLIC} reads, built once from the widths in
     * {@code app/cpy-bms/COCRDLI.CPY}.
     *
     * @return an unmodifiable set of {@value #MAX_SCREEN_LINES} times four row items plus the two
     *     filters
     */
    private static Set<String> buildReadableMapItems() {
        Set<String> items = new TreeSet<>();
        items.add("ACCTSIDI");
        items.add("CARDSIDI");
        for (int row = FIRST_ROW; row <= MAX_SCREEN_LINES; row++) {
            items.add("CRDSEL" + row + "I");
            items.add("ACCTNO" + row + "I");
            items.add("CRDNUM" + row + "I");
            items.add("CRDSTS" + row + "I");
        }
        return Set.copyOf(items);
    }

    /**
     * {@link CicsAid#mnemonicsByAid()} inverted, so a case's mnemonic resolves back to its byte.
     *
     * <p>Inverted from the one published table rather than restated, because {@code DFHAID} is
     * IBM-supplied and absent from this repository (risk <strong>R-D</strong>) and
     * {@code common.CicsAid} is its single reproduction. A second copy here would be a second place for
     * {@code DFHPF10}'s {@code 0x7A} to be wrong.
     *
     * @return every mnemonic mapped to its byte
     */
    private static Map<String, Byte> buildAidByMnemonic() {
        Map<String, Byte> byMnemonic = new LinkedHashMap<>();
        for (Map.Entry<Byte, String> entry : CicsAid.mnemonicsByAid().entrySet()) {
            byMnemonic.put(entry.getValue(), entry.getKey());
        }
        return Map.copyOf(byMnemonic);
    }

    // =================================================================================================
    // TARGETED ASSERTIONS
    //
    // The gates whose subject the case model has no vocabulary for. Each one names the gate it closes
    // and the source line it reads, and each drives CardListController as a plain object exactly as the
    // parameterized gate above does - so nothing here reaches the code by a different route.
    // =================================================================================================

    /**
     * Gate <strong>G39</strong>: the page size is seven, and nothing can change it.
     *
     * <p>Two halves, and the second is the one that matters. The first asserts the literal behaviour: a
     * 50-row card master yields seven placed rows and an eighth row that is <em>not</em> placed, which is
     * {@code IF WS-SCRN-COUNTER = WS-MAX-SCREEN-LINES SET READ-LOOP-EXIT TO TRUE}
     * [{@code app/cbl/COCRDLIC.cbl:1191-1192}]. The second asserts that there is no seam through which
     * seven could become ten: {@code CardListRequest.PAGE_SIZE} and {@code CardListResponse.PAGE_SIZE}
     * are compile-time constants, the map declares exactly seven row bands, and asking for an eighth row
     * is rejected rather than accommodated.
     *
     * <p>Deliberately not asserted by reading a property: a page size that lived in
     * {@code application.yml} would satisfy an assertion against that file while having changed the
     * program's behaviour, which is the whole reason the plan calls this behaviour rather than
     * configuration (practice <strong>B5</strong>).
     */
    @Test
    @DisplayName("G39: page size 7 is behaviour - seven rows placed, an eighth row refused outright")
    void pageSizeIsBehaviourNotConfiguration() {
        CardListResponse painted = listFrom(fixtureRows(0, 50), CicsAid.DFHENTER);

        assertThat(painted.screenRows())
                .as("WS-SCREEN-DATA is OCCURS 7 TIMES [app/cbl/COCRDLIC.cbl:76]")
                .hasSize(MAX_SCREEN_LINES);
        for (int row = FIRST_ROW; row <= MAX_SCREEN_LINES; row++) {
            assertThat(painted.screenRow(row).rowCardNum().trim())
                    .as("row %d of the first page is placed", row)
                    .isNotEmpty();
        }

        // The eighth row does not exist, at either end of the DTO pair. A page size that were tunable
        // would have to be able to name row 8, and neither type can - the subscript conversion refuses
        // it, naming the OCCURS bound and the 1-based-to-0-based rule in the same breath.
        assertThatExceptionOfType(IndexOutOfBoundsException.class)
                .as("an eighth row is refused, not accommodated")
                .isThrownBy(() -> painted.screenRow(MAX_SCREEN_LINES + 1))
                .withMessageContaining("OCCURS 7 TIMES");
        assertThatExceptionOfType(IndexOutOfBoundsException.class)
                .isThrownBy(() -> CardListResponse.acctnoItem(MAX_SCREEN_LINES + 1));
        assertThatExceptionOfType(IndexOutOfBoundsException.class)
                .as("and neither does row zero, because COBOL subscripts start at one")
                .isThrownBy(() -> painted.screenRow(0));
        assertThat(CardListRequest.PAGE_SIZE)
                .as("WS-MAX-SCREEN-LINES VALUE 7 [app/cbl/COCRDLIC.cbl:177-178]")
                .isEqualTo(MAX_SCREEN_LINES)
                .isEqualTo(CardListResponse.PAGE_SIZE)
                .isEqualTo(CardListRequest.SELECT_FLAGS_LENGTH);
    }

    /**
     * Gate <strong>G28</strong>: {@code COMPUTE WS-SCRN-COUNTER = WS-MAX-SCREEN-LINES + 1}
     * [{@code app/cbl/COCRDLIC.cbl:1284-1286}], the one named arithmetic site in this program.
     *
     * <p>It belongs to {@code 9100-READ-BACKWARDS} alone. Reading forward sets the counter with
     * {@code MOVE ZEROES} [{@code :1140}] and grows it with {@code ADD 1} [{@code :1163}]; reading
     * backwards starts it at eight and counts <em>down</em>, and the extra one is spent on the discarded
     * first {@code READPREV} [{@code :1294-1307}] that repositions off the current page's first record.
     * The observable consequence, and what is asserted here, is that a page-up fills exactly seven rows
     * ending at the record before the one the page started on - so eight reads produce seven rows.
     *
     * <p>Also the only arm that needs an inbound cursor carrying a page, which is why it is here rather
     * than in a case file: {@code WS-CA-SCREEN-NUM} lives in {@code WS-THIS-PROGCOMMAREA}
     * [{@code :237}] and travels in the response payload, not in the {@code CDEMO-} communication area.
     */
    @Test
    @DisplayName("G28: page-up computes WS-SCRN-COUNTER = 7 + 1, spends one read, fills seven rows")
    void pageUpComputesScrnCounterAsMaxScreenLinesPlusOne() {
        List<String> rows = fixtureRows(0, 50);
        // Page two: its first record is fixture row 8, so WS-CA-FIRST-CARDKEY holds that key.
        CardListRequest request = continuingRequest(cursorAt(rows, 7, 7, 2));

        CardListResponse painted = listFrom(rows, CicsAid.DFHPF7, request);

        // Eight READPREVs from the anchor: one discarded, seven placed - fixture rows 1 through 7 in
        // ascending order, because the backward loop writes row 7 first and row 1 last.
        for (int row = FIRST_ROW; row <= MAX_SCREEN_LINES; row++) {
            assertThat(painted.screenRow(row).rowCardNum())
                    .as("page-up placed fixture row %d into screen row %d", row, row)
                    .isEqualTo(primaryKeyOf(rows.get(row - 1)));
        }
        // :508  SUBTRACT 1 FROM WS-CA-SCREEN-NUM, into a PIC 9(1) receiver.
        assertThat(painted.getPageCursor().screenNum()).isEqualTo(1);
        // :1350-1353  the record that filled row 1 becomes the page's first key.
        assertThat(painted.getPageCursor().firstCardNum()).isEqualTo(primaryKeyOf(rows.get(0)));
    }

    /**
     * Gates <strong>G37</strong> and <strong>R6</strong>: paging forward is possible only by sending the
     * previous page's cursor back.
     *
     * <p>{@code WHEN CCARD-AID-PFK08 AND CA-NEXT-PAGE-EXISTS} [{@code :486-487}] reads from
     * {@code WS-CA-LAST-CARD-NUM} and adds one to the page number [{@code :488-492}]. Both values are in
     * the inbound payload; the server holds nothing. The proof is the contrast asserted here: the same
     * controller instance, the same {@code PF8}, the same seeded rows - page two when the cursor is sent
     * and page one when it is not.
     */
    @Test
    @DisplayName("G37: PF8 reaches page two only through the cursor in the payload")
    void pageDownAdvancesOnlyByPayloadCursor() {
        List<String> rows = fixtureRows(0, 50);
        CardListController controller = controllerOver(rows);

        // With the cursor: WS-CA-LAST-CARD-NUM is fixture row 8's key, so the browse anchors there.
        CardListResponse withCursor = controller
                .getCards(continuingRequest(cursorAt(rows, 7, 7, 1)), aid(CicsAid.DFHPF8), null)
                .screen();
        assertThat(withCursor.screenRow(FIRST_ROW).rowCardNum())
                .as(":488-489 MOVE WS-CA-LAST-CARD-NUM TO WS-CARD-RID-CARDNUM")
                .isEqualTo(primaryKeyOf(rows.get(7)));
        assertThat(withCursor.getPageCursor().screenNum())
                .as(":492 ADD +1 TO WS-CA-SCREEN-NUM")
                .isEqualTo(2);

        // Without it: the PF8 arm cannot match, WHEN OTHER at :572 reads from an INITIALIZEd first key,
        // and the transaction lists page one again. Nothing was remembered between the two calls.
        CardListResponse withoutCursor = controller
                .getCards(continuingRequest(PageCursor.firstPage()), aid(CicsAid.DFHPF8), null)
                .screen();
        assertThat(withoutCursor.screenRow(FIRST_ROW).rowCardNum())
                .isEqualTo(primaryKeyOf(rows.get(0)));
        assertThat(withoutCursor.getPageCursor().screenNum()).isEqualTo(1);
    }

    /**
     * Gate <strong>G37</strong>: two sequential invocations share nothing but the payload.
     *
     * <p>Asserted against one controller instance, because that is the shape a Spring singleton has and
     * therefore the shape in which shared state would show up. {@code INITIALIZE CC-WORK-AREA
     * WS-MISC-STORAGE WS-COMMAREA} [{@code :300-302}] is the first statement of {@code 0000-MAIN} and a
     * fresh work area per call is that statement; the assertion is that a first call carrying filters
     * and a selection leaves no trace in a second call that carries neither.
     */
    @Test
    @DisplayName("G37/G53: one controller, two calls, no state carried between them")
    void sequentialInvocationsShareNothingButThePayload() {
        List<String> rows = fixtureRows(0, 50);
        CardListController controller = controllerOver(rows);

        CardListRequest filtered = continuingRequest(PageCursor.firstPage());
        filtered.setAcctsid("NOTANUMBER!");
        filtered.setRows(selectionRows("S", rows, 1));
        CardListResponse first = controller.getCards(filtered, aid(CicsAid.DFHENTER), null).screen();
        assertThat(first.getErrmsgo().trim())
                .as(":1021-1023 the account-filter edit message")
                .startsWith("ACCOUNT FILTER");

        CardListResponse second = controller
                .getCards(continuingRequest(PageCursor.firstPage()), aid(CicsAid.DFHENTER), null)
                .screen();
        assertThat(second.getErrmsgo().trim())
                .as("the second call carries no filter, so no filter message survives")
                .isEmpty();
        assertThat(second.getAcctsido().trim())
                .as(":847 moves CC-ACCT-ID, which the second call never supplied")
                .isEmpty();
        assertThat(second.screenRow(FIRST_ROW).rowCardNum())
                .as("the second call lists from the top, unaffected by the first")
                .isEqualTo(primaryKeyOf(rows.get(0)));
    }

    /**
     * Gate <strong>G34</strong>: the {@code CC-ACCT-ID} / {@code CC-ACCT-ID-N} {@code REDEFINES} pair -
     * two typed accessors over one backing span.
     *
     * <p>{@code app/cpy/CVCRD01Y.cpy} declares {@code CC-ACCT-ID PIC X(11)} and, redefining the same
     * eleven bytes, {@code CC-ACCT-ID-N PIC 9(11)}. {@code COCRDLIC} depends on both views of those bytes
     * in one paragraph: {@code 2210-EDIT-ACCOUNT} tests the alphanumeric view for {@code LOW-VALUES} and
     * {@code SPACES} and the numeric view for {@code ZEROS} [{@code :1007-1009}], then moves the numeric
     * view into {@code CDEMO-ACCT-ID} [{@code :1027}]. There are 96 non-comment {@code REDEFINES} sites in
     * the codebase - 80 in {@code app/cbl} and 16 in {@code app/cpy}, counted mechanically by
     * {@code RedefinesCensusTest} - and this is the classic place one diverges, so the round trip is
     * asserted in both directions and at the boundary values that distinguish the views.
     */
    @Test
    @DisplayName("G34: CC-ACCT-ID and CC-ACCT-ID-N are one span seen two ways, round trip both ways")
    void redefinesPairRoundTripsThroughBothAccessors() {
        CardScreenState state = new CardScreenState();

        // Alphanumeric in, numeric out. Eleven digits, so both views are meaningful.
        state.setCcAcctId("00000000050");
        assertThat(state.getCcAcctIdN()).isEqualTo(50L);
        assertThat(state.isCcAcctIdNumeric()).isTrue();

        // Numeric in, alphanumeric out - and the image is zero-filled to the declared eleven, because
        // PIC 9(11) has no other representation for fifty.
        state.setCcAcctIdN(50L);
        assertThat(state.getCcAcctId())
                .hasSize(CardScreenState.CC_ACCT_ID_LENGTH)
                .isEqualTo("00000000050");

        // The three states :1007-1009 distinguishes, which is why both views exist.
        state.setCcAcctIdToLowValues();
        assertThat(state.isCcAcctIdLowValues()).isTrue();
        assertThat(state.isCcAcctIdSpaces()).isFalse();

        state.setCcAcctId(CardScreenState.spaces(CardScreenState.CC_ACCT_ID_LENGTH));
        assertThat(state.isCcAcctIdSpaces()).isTrue();
        assertThat(state.isCcAcctIdLowValues()).isFalse();

        state.setCcAcctIdN(0L);
        assertThat(state.isCcAcctIdNZeros())
                .as(":1009 OR CC-ACCT-ID-N EQUAL ZEROS - the third not-supplied form")
                .isTrue();

        // A non-numeric image is still storable and is what :1017 rejects. The alphanumeric view keeps
        // the bytes; the numeric view refuses to interpret them.
        state.setCcAcctId("1234ABCD567");
        assertThat(state.getCcAcctId()).isEqualTo("1234ABCD567");
        assertThat(state.isCcAcctIdNumeric()).isFalse();
    }

    /**
     * Gates <strong>G19</strong> and <strong>G21</strong>: the card record is 150 bytes and its
     * {@code FILLER} is present.
     *
     * <p>{@code app/cpy/CVACT02Y.cpy} sums to {@code 16 + 11 + 3 + 50 + 10 + 1 + 59 = 150}, and the last
     * of those is {@code FILLER}. A codec that omitted the {@code FILLER} would produce a 91-byte record
     * that decoded correctly and re-encoded wrongly, which is why the total width is asserted rather
     * than the fields: the width fails immediately when the {@code FILLER} is dropped.
     *
     * <p>Asserted on a round trip through a real fixture row, so the assertion covers the decode as well
     * as the encode and covers the fixture as well as the model.
     */
    @Test
    @DisplayName("G19/G21: CVACT02Y is 150 bytes with its FILLER emitted, and round-trips exactly")
    void cardRecordIsOneHundredAndFiftyBytesIncludingFiller() {
        String image = fixtureRows(0, 1).get(0);
        assertThat(image)
                .as("app/data/ASCII/carddata.txt rows are exactly the copybook width")
                .hasSize(CardRecord.RECORD_LENGTH)
                .hasSize(150);

        CardRecord decoded = CardRecord.decodeImage(image, ParityHarness.FIXTURE_CHARSET);
        String reEncoded = decoded.encodeToImage(ParityHarness.FIXTURE_CHARSET);
        assertThat(reEncoded)
                .as("re-encoding restores every span, FILLER included")
                .hasSize(CardRecord.RECORD_LENGTH)
                .isEqualTo(image);
        assertThat(CardRecord.LAYOUT.recordLength()).isEqualTo(CardRecord.RECORD_LENGTH);
        assertThat(CardRepository.RECORD_LENGTH).isEqualTo(CardRecord.RECORD_LENGTH);
    }

    /**
     * Gate <strong>G45</strong>: {@code CARDAIX} is an alternate-index path over {@code CARDDAT}, not a
     * second dataset.
     *
     * <p>{@code app/csd/CARDDEMO.CSD} gives {@code CARDDAT} and {@code CARDAIX} the <em>same</em>
     * {@code DSNAME} stem - the second is a {@code .AIX.PATH} over the first - and {@code COCRDLIC}
     * declares both literals, {@code LIT-CARD-FILE VALUE 'CARDDAT '} [{@code :213-214}] and
     * {@code LIT-CARD-FILE-ACCT-PATH VALUE 'CARDAIX '} [{@code :215-217}], each padded to the
     * eight-character CICS file name. One repository serves both, with the alternate key reached by a
     * second finder rather than by a second table.
     */
    @Test
    @DisplayName("G45: CARDAIX is a second access path on one repository, never a second table")
    void cardAixIsAPathOverCarddatNotASecondTable() {
        assertThat(CardRepository.BASE_DD_NAME).isEqualTo("CARDDAT");
        assertThat(CardRepository.ALTERNATE_INDEX_DD_NAME).isEqualTo("CARDAIX");
        assertThat(CardRepository.BASE_CICS_FILE_NAME)
                .as(":213-214 LIT-CARD-FILE PIC X(8) VALUE 'CARDDAT '")
                .hasSize(CardRepository.CICS_FILE_NAME_LENGTH)
                .isEqualTo("CARDDAT ");
        assertThat(CardRepository.ALTERNATE_INDEX_CICS_FILE_NAME)
                .as(":215-217 LIT-CARD-FILE-ACCT-PATH PIC X(8) VALUE 'CARDAIX '")
                .hasSize(CardRepository.CICS_FILE_NAME_LENGTH)
                .isEqualTo("CARDAIX ");

        // One base record layout serves both paths, which is the whole assertion: an alternate index is
        // a different way in to the same 150 bytes.
        assertThat(CardRecord.cardDatPrimaryKeySpan().length())
                .as("CARD-NUM is the base key")
                .isEqualTo(CardRecord.CARD_NUM_LENGTH);
        assertThat(CardRecord.cardAixAlternateKeySpan().length())
                .as("CARD-ACCT-ID is the alternate key, over the same record")
                .isEqualTo(CardRecord.CARD_ACCT_ID_LENGTH);

        // And COCRDLIC browses the base only: 9000-READ-FORWARD and 9100-READ-BACKWARDS both name
        // LIT-CARD-FILE. The account filter is applied in 9500-FILTER-RECORDS [:1385-1394] by comparing
        // CARD-ACCT-ID in the program, not by switching to the alternate path.
        List<String> rows = fixtureRows(0, 50);
        CardRepository repository = stubbedRepositoryOver(rows);
        CardListRequest request = continuingRequest(PageCursor.firstPage());
        // ACCTSIDI as the terminal transmits an untouched field: LOW-VALUES at the item's declared width.
        request.setAcctsid(CardScreenState.lowValues(CardListRequest.ACCTSID_LENGTH));

        controllerOver(repository).getCards(request, aid(CicsAid.DFHENTER), null);

        Mockito.verify(repository, Mockito.atLeastOnce())
                .startBrowse(Mockito.anyString(), Mockito.eq(BrowseDirection.FORWARD));
        Mockito.verify(repository, Mockito.never()).readByAccountIdViaAltIndex(Mockito.anyLong());
        Mockito.verify(repository, Mockito.never()).readByCardNumber(Mockito.anyString());
    }

    /**
     * Gate <strong>G40</strong>: all three {@code XCTL} sites resolve to a {@code nextProgram} response
     * field, and no transfer happens on the server.
     *
     * <p>The three sites are two shapes. {@code :403} names a literal, {@code LIT-MENUPGM}; {@code :539}
     * and {@code :567} name a field, {@code CCARD-NEXT-PROG}, loaded from {@code LIT-CARDDTLPGM}
     * [{@code :526}] and {@code LIT-CARDUPDPGM} [{@code :554}] respectively. All three become a name in
     * the response for the client to resolve, and each carries its own mapset and map - which is where
     * {@code COCRDSL} enters this program.
     *
     * <p>The absence half of the assertion is the important one: no forward, no redirect, no session.
     * What comes back is a name, and the response still carries the communication area the transfer would
     * have passed in {@code COMMAREA(CARDDEMO-COMMAREA)}.
     */
    @Test
    @DisplayName("G40: the three XCTL sites become nextProgram, with no server-side forward")
    void threeTransferSitesBecomeNextProgramNames() {
        List<String> rows = fixtureRows(0, 50);

        // :384-406  PF3 with CDEMO-FROM-PROGRAM = LIT-THISPGM. On a cold start :319 has already set it,
        // so the guard holds and the literal transfer is reached.
        CardListResponse toMenu = listFrom(rows, CicsAid.DFHPF3);
        assertThat(toMenu.getNextProgram().trim()).isEqualTo(MENU_PROGRAM);
        assertThat(toMenu.getNavigationContext().toProgram().trim())
                .as(":392 MOVE LIT-MENUPGM TO CDEMO-TO-PROGRAM")
                .isEqualTo(MENU_PROGRAM);
        assertThat(toMenu.getNextMapset().trim())
                .as(":394 MOVE LIT-MENUMAPSET TO CCARD-NEXT-MAPSET")
                .isEqualTo("COMEN01");
        assertThat(toMenu.getNextMap().trim())
                .as(":395 moves LIT-THISMAP, not LIT-MENUMAP - a source quirk, preserved")
                .isEqualTo("CCRDLIA");

        // :517-541  ENTER with 'S' on a row -> the card detail view, which is COCRDSL's mapset.
        CardListResponse toDetail = listFrom(rows, CicsAid.DFHENTER,
                selectedRequest("S", rows, 1));
        assertThat(toDetail.getNextProgram().trim()).isEqualTo(CARD_DETAIL_PROGRAM);
        assertThat(toDetail.getNextMapset().trim()).isEqualTo("COCRDSL");
        assertThat(toDetail.getNextMap().trim()).isEqualTo("CCRDSLA");
        assertThat(toDetail.getNavigationContext().cardNum())
                .as(":533-534 MOVE WS-ROW-CARD-NUM(I-SELECTED) TO CDEMO-CARD-NUM")
                .isEqualTo(Long.parseLong(primaryKeyOf(rows.get(0))));

        // :545-569  ENTER with 'U' on a row -> the card update program.
        CardListResponse toUpdate = listFrom(rows, CicsAid.DFHENTER,
                selectedRequest("U", rows, 1));
        assertThat(toUpdate.getNextProgram().trim()).isEqualTo(CARD_UPDATE_PROGRAM);
        assertThat(toUpdate.getNextMapset().trim()).isEqualTo("COCRDUP");
        assertThat(toUpdate.getNextMap().trim()).isEqualTo("CCRDUPA");

        // A transfer sends no map. Six arms send exactly one; these two send none, and that difference is
        // what the parity model calls XCTL versus RETURN_TRANSID.
        assertThat(toDetail.getTrnnameo().trim())
                .as("1000-SEND-MAP is skipped by a transfer, so TRNNAMEO was never moved")
                .isEmpty();
    }

    /**
     * The two mapset projections {@code COCRDLIC} touches, each field name and width traced to its
     * symbolic map.
     *
     * <p>{@code COCRDLI} is the map it sends: 45 payload items, and
     * {@link CardListResponse#fieldImages()} publishes exactly that many, keyed by {@code xxxO} name in
     * copybook order. {@code COCRDSL} is the map it names on a transfer, and {@code COCRDLIC} does not
     * duplicate it - it shares {@link CardSelectResponse}, whose 15 payload items are the ones
     * {@code app/cpy-bms/COCRDSL.CPY} declares.
     *
     * <p><strong>Why the sharing matters.</strong> Duplicating the card-select DTO into the card package's
     * list screen would create two independent statements of 15 field widths, and the day one changed the
     * other would keep passing. The shared type makes that impossible, and
     * {@link CardListController}'s own constructor already refuses to start if its four card-detail
     * literals disagree with {@link CardSelectResponse} - which is asserted here from the outside.
     */
    @Test
    @DisplayName("Two mapsets: COCRDLI is sent with 45 items, COCRDSL is named through the shared DTO")
    void cardSelectProjectionIsSharedNotDuplicated() {
        CardListResponse painted = listFrom(fixtureRows(0, 50), CicsAid.DFHENTER);

        assertThat(painted.fieldImages())
                .as("app/cpy-bms/COCRDLI.CPY declares 45 xxxI/xxxO payload items")
                .hasSize(45);
        assertThat(painted.payloadFieldCount()).isEqualTo(painted.fieldImages().size());
        assertThat(painted.fieldImages().keySet())
                .as("the header band, in copybook order, PAGENOO seventh")
                .startsWith("TRNNAMEO", "TITLE01O", "CURDATEO", "PGMNAMEO", "TITLE02O", "CURTIMEO",
                        "PAGENOO", "ACCTSIDO", "CARDSIDO")
                .endsWith("INFOMSGO", "ERRMSGO");

        // The shared card-select screen: its four literals are the ones COCRDLIC declares at :195-202,
        // and its filter widths are the ones the communication area hands over at :531-534.
        assertThat(CardSelectResponse.THIS_PROGRAM).isEqualTo(CARD_DETAIL_PROGRAM);
        assertThat(CardSelectResponse.THIS_MAPSET).isEqualTo("COCRDSL");
        assertThat(CardSelectResponse.MAP_NAME).isEqualTo("CCRDSLA");
        assertThat(CardSelectResponse.THIS_TRANID).isEqualTo("CCDL");
        assertThat(CardSelectRequest.ACCTSID_LENGTH)
                .as("CC-ACCT-ID hands over 11 characters, and the receiving screen declares 11")
                .isEqualTo(NavigationContext.ACCT_ID_LENGTH);
        assertThat(CardSelectRequest.CARDSID_LENGTH)
                .as("CDEMO-CARD-NUM hands over 16, and the receiving screen declares 16")
                .isEqualTo(NavigationContext.CARD_NUM_LENGTH);
    }

    /**
     * The {@code CSSTRPFY} ladder [{@code app/cbl/COCRDLIC.cbl:1416}] and what {@code COCRDLIC} then does
     * with it.
     *
     * <p>{@code YYYY-STORE-PFKEY} is an {@code EVALUATE} with no {@code WHEN OTHER}, no {@code DFHPA3}
     * arm, and no clearing of {@code CCARD-AID} beforehand - so an AID it does not name leaves the field
     * exactly as {@code INITIALIZE CC-WORK-AREA} [{@code :300}] left it, which is {@code LOW-VALUES} and
     * therefore none of the fifteen conditions. {@code DFHPF13} through {@code DFHPF24} <strong>fold
     * back</strong> onto {@code PFK01} through {@code PFK12} [{@code app/cpy/CSSTRPFY.cpy:54-60}].
     *
     * <p>{@code COCRDLIC} then narrows further: only {@code ENTER}, {@code PFK03}, {@code PFK07} and
     * {@code PFK08} are valid [{@code :371-376}], and everything else is forced to {@code ENTER}
     * [{@code :378-380}]. Both consequences are asserted - the resolution and the forcing - because a
     * resolver that folded correctly and a program that then ignored the fold would look identical from
     * the outside if only one were checked.
     */
    @Test
    @DisplayName("CSSTRPFY: PF13 folds to PFK01, an unnamed AID resolves to nothing, both become ENTER")
    void pfKeyLadderFoldsAndUnnamedAidsBecomeEnter() {
        // The fold. PF13 is a distinct byte from PF1 and resolves to the same mnemonic.
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPF13))
                .as("app/cpy/CSSTRPFY.cpy:54-55  WHEN EIBAID = DFHPF13 -> SET CCARD-AID-PFK01")
                .contains(AidKey.PFK01);
        assertThat(CicsAid.DFHPF13).isNotEqualTo(CicsAid.DFHPF1);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPF1)).contains(AidKey.PFK01);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPF15)).contains(AidKey.PFK03);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPF24)).contains(AidKey.PFK12);

        // The arms the ladder does name, and the two it does not.
        assertThat(PfKeyResolver.resolve(CicsAid.DFHENTER)).contains(AidKey.ENTER);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHCLEAR)).contains(AidKey.CLEAR);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPA1)).contains(AidKey.PA1);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPA2)).contains(AidKey.PA2);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3))
                .as("CSSTRPFY has no DFHPA3 arm, so nothing is set")
                .isEmpty();
        assertThat(PfKeyResolver.resolve(CicsAid.DFHCLRP)).isEmpty();

        // Not clearing first is what makes an unmatched AID leave the previous value standing.
        assertThat(PfKeyResolver.storePfKey(CicsAid.DFHPA3, Optional.of(AidKey.PFK08)))
                .as("no WHEN OTHER and no reset, so the incoming value survives")
                .contains(AidKey.PFK08);

        // And COCRDLIC's own narrowing: PFK01 from the fold, and PA3 from no arm at all, both list.
        List<String> rows = fixtureRows(0, 50);
        for (byte forced : new byte[] {CicsAid.DFHPF13, CicsAid.DFHPA3, CicsAid.DFHCLEAR,
                CicsAid.DFHPF5}) {
            CardListResponse painted = listFrom(rows, forced);
            assertThat(painted.getCardScreenState().isCcardAidEnter())
                    .as(":378-380 IF PFK-INVALID SET CCARD-AID-ENTER TO TRUE, AID 0x%02X",
                            Byte.toUnsignedInt(forced))
                    .isTrue();
            assertThat(painted.screenRow(FIRST_ROW).rowCardNum())
                    .as("forced to ENTER, so the WHEN OTHER arm at :572 lists page one")
                    .isEqualTo(primaryKeyOf(rows.get(0)));
        }
    }

    /**
     * Gates <strong>G38</strong> and <strong>G30</strong>: a first entry paints, a re-entry validates, and
     * only the offending row is highlighted.
     *
     * <p>{@code COCRDLIC} does <strong>not</strong> copy {@code CSSETATY} - the eighteen-copybook
     * {@code COACTUPC} owns that include - so the highlight rule here is the program's own
     * {@code 1250-SETUP-ARRAY-ATTRIBS} [{@code :748-832}], driven by the per-row error flag rather than by
     * a shared setter. What plays the part of the {@code ENTER}/{@code REENTER} distinction is
     * {@code IF EIBCALEN > 0 AND CDEMO-FROM-PROGRAM EQUAL LIT-THISPGM} [{@code :357-362}]: a screen
     * arriving from somewhere else is painted without {@code 2000-RECEIVE-MAP} running at all, so nothing
     * typed is validated and nothing is highlighted.
     *
     * <p>Both halves are asserted, and the second is asserted per row: two selections make rows one and
     * three red and leave rows two and four through seven untouched, which is the difference between
     * highlighting the offender and highlighting the screen.
     */
    @Test
    @DisplayName("G38: arriving from elsewhere paints without validating; a re-entry reddens only the offending rows")
    void reenterHighlightsOnlyTheOffendingRow() {
        List<String> rows = fixtureRows(0, 50);

        // Arriving from the menu: :357-362 skips 2000-RECEIVE-MAP, so the typed 'X' is never read.
        CardListRequest fromMenu = continuingRequest(PageCursor.firstPage());
        fromMenu.setNavigationContext(NavigationContext.empty()
                .withFromTranid("CM00")
                .withFromProgram(MENU_PROGRAM)
                .withPgmEnter());
        fromMenu.setRows(selectionRows("X", rows, 1));
        CardListResponse painted = listFrom(rows, CicsAid.DFHENTER, fromMenu);
        assertThat(painted.getErrmsgo().trim())
                .as("nothing was received, so nothing failed edit")
                .isEmpty();
        assertThat(painted.fieldAttributes("CRDSEL1").colour())
                .as("no row is red when no row was validated")
                .isEqualTo((byte) 0);

        // Re-entering from itself with two selections: :1084-1095 flags both, :1104 marks each flagged row,
        // and 1250-SETUP-ARRAY-ATTRIBS reddens exactly those.
        CardListRequest fromSelf = selectedRequest("S", rows, 1);
        fromSelf.setRows(twoSelectionRows(rows));
        CardListResponse rejected = listFrom(rows, CicsAid.DFHENTER, fromSelf);
        assertThat(rejected.getErrmsgo().trim())
                .as(":123-124 88 WS-MORE-THAN-1-ACTION")
                .isEqualTo("PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE");
        assertThat(rejected.fieldAttributes("CRDSEL1").colour())
                .as(":756 MOVE DFHRED TO CRDSEL1C for the flagged row")
                .isEqualTo(BmsAttributes.DFHRED);
        assertThat(rejected.fieldAttributes("CRDSEL3").colour())
                .as(":781 MOVE DFHRED TO CRDSEL3C for the second flagged row")
                .isEqualTo(BmsAttributes.DFHRED);
        for (int untouched : new int[] {2, 4, 5, 6, 7}) {
            assertThat(rejected.fieldAttributes("CRDSEL" + untouched).colour())
                    .as("row %d typed nothing, so it is not reddened", untouched)
                    .isEqualTo((byte) 0);
        }
        // :428 MOVE LIT-THISPGM TO CCARD-NEXT-PROG. It goes into CC-WORK-AREA, not into the response's
        // own navigation triple: that triple is written only by the two transfer sites, and this arm
        // repaints rather than transfers. Both statements are asserted, because a translation that
        // conflated them would look right on one of them.
        assertThat(rejected.getCardScreenState().getCcardNextProg().trim())
                .as(":428 the input-error arm names this program, so the operator corrects and resends")
                .isEqualTo(THIS_PROGRAM);
        assertThat(rejected.getNextProgram().trim())
                .as("no XCTL happened, so the response names no next program")
                .isEmpty();
    }

    /**
     * Practice <strong>B5</strong>: the asterisk at {@code :757-759} is preserved even though
     * {@code COCRDLIC} cannot reach it.
     *
     * <p>{@code 1250-SETUP-ARRAY-ATTRIBS} stamps an asterisk into {@code CRDSEL1O} when row one carries a
     * selection error <em>and</em> its selection character is blank. No path through {@code 2250-EDIT-ARRAY}
     * produces that combination: the error flag is set only where the character is {@code 'S'} or
     * {@code 'U'} [{@code :1088-1093}, {@code :1101-1104}] or where it is some other non-blank character
     * [{@code :1106-1110}], and a blank row falls to {@code WHEN SELECT-BLANK ... CONTINUE}.
     *
     * <p>So the statement is dead code in this program, and preserving dead code without proving it does
     * what the source says would leave nothing to distinguish a faithful translation from a deleted one.
     * It is therefore driven directly, which is exactly why the highlight is a method on the response
     * rather than an inline branch.
     */
    @Test
    @DisplayName("B5: the row-one asterisk at :757-759 is preserved and does what the source says")
    void rowOneAsteriskIsPreservedThoughUnreachable() {
        CardListResponse response = new CardListResponse();
        // WS-EACH-CARD(1) not LOW-VALUES: 1200-SCREEN-ARRAY-INIT has placed a row.
        response.setScreenRow(FIRST_ROW,
                new CardListResponse.ScreenRow("00000000050", "0500024453765740", "Y"));
        // WS-ROW-CRDSELECT-ERROR(1) = '1', with WS-EDIT-SELECT(1) still LOW-VALUES.
        response.setWsRowCrdselectError(FIRST_ROW, String.valueOf(CardListRequest.ROW_SELECT_ERROR));
        response.setEditSelect(FIRST_ROW, String.valueOf(LOW_VALUE));

        boolean highlighted = response.applyRowSelectHighlight(FIRST_ROW, false);

        assertThat(highlighted).isTrue();
        assertThat(response.fieldAttributes("CRDSEL1").colour())
                .as(":756 MOVE DFHRED TO CRDSEL1C")
                .isEqualTo(BmsAttributes.DFHRED);
        assertThat(response.field("CRDSEL1O"))
                .as(":758 MOVE '*' TO CRDSEL1O, reachable only by calling the paragraph directly")
                .isEqualTo(FieldAttributeSetter.ASTERISK);

        // FLG-PROTECT-SELECT-ROWS-YES short-circuits the whole block at :751-752, so a filter that failed
        // edit protects the rows instead of reddening them.
        CardListResponse protectedRows = new CardListResponse();
        protectedRows.setScreenRow(FIRST_ROW,
                new CardListResponse.ScreenRow("00000000050", "0500024453765740", "Y"));
        protectedRows.setWsRowCrdselectError(FIRST_ROW,
                String.valueOf(CardListRequest.ROW_SELECT_ERROR));
        assertThat(protectedRows.applyRowSelectHighlight(FIRST_ROW, true)).isFalse();
        assertThat(protectedRows.fieldAttributes("CRDSEL1").colour()).isEqualTo((byte) 0);
    }

    /**
     * Gate <strong>G47</strong>: every {@code EVALUATE WS-RESP-CD} outcome, at each of the three call
     * sites that has one.
     *
     * <p>{@code COCRDLIC} consults a response in three places and the arms differ between them:
     * <ul>
     *   <li>{@code 9000-READ-FORWARD}'s loop read [{@code :1156-1255}] - {@code NORMAL}/{@code DUPREC}
     *       place a row, {@code ENDFILE} ends the page, {@code WHEN OTHER} composes the file-error
     *       message.</li>
     *   <li>the look-ahead read [{@code :1207-1231}] - {@code NORMAL}/{@code DUPREC} mean a next page
     *       exists, {@code ENDFILE} means it does not and yields {@code 'NO MORE RECORDS TO SHOW'},
     *       {@code WHEN OTHER} composes the same message.</li>
     *   <li>{@code 9100-READ-BACKWARDS}' two reads [{@code :1304-1318}, {@code :1332-1370}] - only two
     *       arms each, since a backward {@code ENDFILE} falls into {@code WHEN OTHER}.</li>
     * </ul>
     *
     * <p>The empty-browse outcome is the one worth naming separately: a browse that returns nothing at all
     * leaves {@code CARD-RECORD} untouched, so {@code :1236-1237} stores the {@code WORKING-STORAGE} zeros
     * as the page's last key and {@code :1241-1244} raises {@code 'NO RECORDS FOUND FOR THIS SEARCH
     * CONDITION.'} rather than the end-of-page message.
     *
     * <p>The {@code DFHRESP(DUPREC)} half of the shared pair is driven by
     * {@code src/test/resources/parity/COCRDLIC/case03.json} rather than here, because its whole assertion
     * is that a duplicate-key read paints a page byte-identical to a {@code NORMAL} one - which is a
     * field-for-field comparison of all 45 items and all eight rows, and that is what the parameterized
     * gate does. This method covers the arms whose observable effect is a single message or key.
     */
    @Test
    @DisplayName("G47: the response ladder - a full page, a short page, an empty browse, and a failure")
    void fileStatusLadderIsDrivenAtEveryCallSite() {
        // NORMAL then ENDFILE on the look-ahead: seven rows, and no next page.
        CardListResponse exactPage = listFrom(fixtureRows(0, MAX_SCREEN_LINES), CicsAid.DFHENTER);
        assertThat(exactPage.getPageCursor().isNextPageNotExists())
                .as(":1215-1216 the look-ahead reached ENDFILE")
                .isTrue();
        assertThat(exactPage.getErrmsgo().trim())
                .as(":1218-1221 IF WS-ERROR-MSG-OFF MOVE 'NO MORE RECORDS TO SHOW'")
                .isEqualTo("NO MORE RECORDS TO SHOW");

        // ENDFILE inside the loop, with rows already placed: a short page, same message.
        CardListResponse shortPage = listFrom(fixtureRows(0, 4), CicsAid.DFHENTER);
        assertThat(shortPage.getErrmsgo().trim()).isEqualTo("NO MORE RECORDS TO SHOW");
        assertThat(shortPage.screenRow(4).rowCardNum().trim()).isNotEmpty();
        assertThat(shortPage.screenRow(5).rowCardNum().trim())
                .as("MOVE LOW-VALUES TO WS-ALL-ROWS left row 5 empty")
                .isEmpty();

        // ENDFILE on the very first read: the empty-browse outcome.
        CardListResponse empty = listFrom(List.of(), CicsAid.DFHENTER);
        assertThat(empty.getErrmsgo().trim())
                .as(":1241-1244 WS-CA-SCREEN-NUM = 1 AND WS-SCRN-COUNTER = 0")
                .isEqualTo("NO RECORDS FOUND FOR THIS SEARCH CONDITION.");
        assertThat(empty.getPageCursor().lastCardNum().trim())
                .as(":1236-1237 store an untouched CARD-RECORD, which is LOW-VALUES")
                .isEmpty();

        // WHEN OTHER: a forced INVREQ composes WS-FILE-ERROR-MESSAGE, which is exactly 80 characters
        // [:153-171] and is then truncated into the 75-character WS-ERROR-MSG [:117].
        CardListResponse failed = listFromFailedBrowse(fixtureRows(0, 50));
        // The group is composed from five literals and four moved fields [:153-171]. Each span is
        // written out at its declared width rather than as one opaque string, so a width that drifted
        // names itself: 'File Error:' in X(12), ERROR-OPNAME X(8), ' on ' X(4), ERROR-FILE X(9),
        // ' returned RESP ' X(15), ERROR-RESP X(10), ',RESP2 ' X(7), ERROR-RESP2 X(10), FILLER X(5).
        String composed = "File Error:".concat(" ")
                + "READ    "
                + " on "
                + "CARDDAT  "
                + " returned RESP "
                + "000000016 "
                + ",RESP2 "
                + "000000000 "
                + "     ";
        assertThat(composed)
                .as(":153-171 WS-FILE-ERROR-MESSAGE sums to exactly eighty characters")
                .hasSize(80);
        assertThat(failed.getErrmsgo())
                .as(":1254 truncates the eighty into WS-ERROR-MSG PIC X(75), and :924 pads that into "
                        + "ERRMSGO PIC X(78)")
                .hasSize(CardListResponse.ERRMSGO_LENGTH)
                .isEqualTo(composed.substring(0, 75) + "   ");
        assertThat(failed.getErrmsgo())
                .as("both RESP and RESP2 are reported, each as a nine-digit PIC X(10) item")
                .contains(String.valueOf(FileStatus.INVREQ))
                .contains(",RESP2");
    }

    // =================================================================================================
    // HELPERS FOR THE TARGETED ASSERTIONS
    //
    // The same controller, the same repository stub and the same fixture the parameterized gate uses, so
    // no assertion in this file reaches the code by a route the gate does not.
    // =================================================================================================

    /**
     * A controller over the given card master, built the way production builds it.
     *
     * @param rows the seeded 150-byte rows
     * @return the controller; never {@code null}
     */
    private static CardListController controllerOver(List<String> rows) {
        return controllerOver(stubbedRepositoryOver(rows));
    }

    /**
     * A controller over an already-built repository, for an assertion that verifies the repository too.
     *
     * @param repository the card repository
     * @return the controller; never {@code null}
     */
    private static CardListController controllerOver(CardRepository repository) {
        return new CardListController(repository,
                new FixedWidthCodec(ParityHarness.FIXTURE_CHARSET),
                ParityHarness.fixedClockAt(LocalDateTime.parse(PINNED_CLOCK)));
    }

    /**
     * The same fixture-backed browse the parity adapter installs, without a forced outcome.
     *
     * @param rows the seeded rows
     * @return the stub; never {@code null}
     */
    private static CardRepository stubbedRepositoryOver(List<String> rows) {
        return stubbedRepositoryOver(rows, Optional.empty());
    }

    /**
     * The fixture-backed browse, optionally returning a forced failure instead of a record.
     *
     * @param rows   the seeded rows
     * @param forced the failure to return, or empty for an ordinary browse
     * @return the stub; never {@code null}
     */
    private static CardRepository stubbedRepositoryOver(List<String> rows,
            Optional<CardReadResult> forced) {

        List<String> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing(COCRDLICParityTest::primaryKeyOf));
        CardRepository repository = Mockito.mock(CardRepository.class);
        Mockito.when(repository.startBrowse(Mockito.anyString(), Mockito.any(BrowseDirection.class)))
                .thenAnswer(call -> browseOver(ordered, call.getArgument(0), call.getArgument(1),
                        forced));
        return repository;
    }

    /**
     * Lists with no inbound payload - the {@code EIBCALEN = 0} cold start.
     *
     * @param rows   the seeded rows
     * @param eibAid the raw AID byte
     * @return the painted or transferred response
     */
    private static CardListResponse listFrom(List<String> rows, byte eibAid) {
        return listFrom(rows, eibAid, null);
    }

    /**
     * Lists with the given payload.
     *
     * @param rows    the seeded rows
     * @param eibAid  the raw AID byte
     * @param request the payload, or {@code null} for the cold start
     * @return the painted or transferred response
     */
    private static CardListResponse listFrom(List<String> rows, byte eibAid,
            CardListRequest request) {
        return controllerOver(rows).getCards(request, aid(eibAid), null).screen();
    }

    /**
     * Lists against a browse whose reads all fail with {@code DFHRESP(INVREQ)}, to reach
     * {@code WHEN OTHER}.
     *
     * @param rows the seeded rows, which the failing browse never returns
     * @return the painted response carrying the composed diagnostic
     */
    private static CardListResponse listFromFailedBrowse(List<String> rows) {
        CardRepository failing = stubbedRepositoryOver(rows,
                Optional.of(CardReadResult.reportedFailure(FileStatus.INVREQ,
                        FileStatus.NO_REASON_CODE)));
        return controllerOver(failing).getCards(null, aid(CicsAid.DFHENTER), null).screen();
    }

    /**
     * A continuing payload from this program itself, so {@code :357-362} runs {@code 2000-RECEIVE-MAP}.
     *
     * <p>The communication area states the six fields {@code :318-323} sets on a first entry, which is
     * what a screen returned by {@code COMMON-RETURN} [{@code :605-608}] carries back.
     *
     * @param cursor the pagination cursor the payload carries
     * @return the payload; never {@code null}
     */
    private static CardListRequest continuingRequest(PageCursor cursor) {
        CardListRequest request = new CardListRequest();
        request.setNavigationContext(NavigationContext.empty()
                .withFromTranid(THIS_TRANID)
                .withFromProgram(THIS_PROGRAM)
                .withUserTypeUser()
                .withPgmEnter()
                .withLastMap("CCRDLIA")
                .withLastMapset("COCRDLI"));
        request.setPageCursor(cursor);
        return request;
    }

    /**
     * A pagination cursor standing where a previous send left it.
     *
     * <p>{@code WS-THIS-PROGCOMMAREA} [{@code app/cbl/COCRDLIC.cbl:229-248}] carries two keys and a page
     * number: {@code WS-CA-LAST-CARDKEY} is what {@code PF8} browses forward from [{@code :488-489}] and
     * {@code WS-CA-FIRST-CARDKEY} is what {@code PF7} browses back from [{@code :504-505}]. The next-page
     * indicator is set to {@code 'Y'} because that is the state a send that found an eighth record leaves
     * [{@code :1210-1211}], and it is what the {@code PF8} arm's second condition tests.
     *
     * @param rows       the seeded rows the keys are taken from
     * @param lastIndex  the zero-based row {@code WS-CA-LAST-CARDKEY} names
     * @param firstIndex the zero-based row {@code WS-CA-FIRST-CARDKEY} names
     * @param screenNum  {@code WS-CA-SCREEN-NUM}, a {@code PIC 9(1)} page number
     * @return the cursor; never {@code null}
     */
    private static PageCursor cursorAt(List<String> rows, int lastIndex, int firstIndex,
            int screenNum) {

        return new PageCursor(keyOf(rows.get(lastIndex)), keyOf(rows.get(firstIndex)), screenNum,
                CardListRequest.LAST_PAGE_NOT_SHOWN,
                String.valueOf(CardListRequest.NEXT_PAGE_EXISTS),
                String.valueOf(SPACE));
    }

    /**
     * The two-part card key of one record - {@code WS-CA-LAST-CARDKEY}'s shape
     * [{@code app/cbl/COCRDLIC.cbl:230-232}].
     *
     * @param row the 150-byte record image
     * @return the key; never {@code null}
     */
    private static CardListRequest.CardKey keyOf(String row) {
        CardRecord card = CardRecord.decodeImage(row, ParityHarness.FIXTURE_CHARSET);
        return new CardListRequest.CardKey(card.cardNum(), card.cardAcctId());
    }

    /**
     * A continuing payload with one row selected and that row's data present, as a returned screen
     * carries it.
     *
     * <p>The row data matters as much as the selection character: {@code :531-534} reads
     * {@code WS-ROW-ACCTNO(I-SELECTED)} and {@code WS-ROW-CARD-NUM(I-SELECTED)} out of the row table, and
     * {@code restoreScreenRowTable} rebuilds that table from the row items the previous send wrote. A
     * selection on a row carrying no data would transfer zeros.
     *
     * @param action   {@code "S"} to view or {@code "U"} to update
     * @param rows     the seeded rows, whose first page the payload echoes back
     * @param cobolRow the 1-based row the operator typed on
     * @return the payload; never {@code null}
     */
    private static CardListRequest selectedRequest(String action, List<String> rows, int cobolRow) {
        CardListRequest request = continuingRequest(PageCursor.firstPage());
        request.setRows(selectionRows(action, rows, cobolRow));
        return request;
    }

    /**
     * Seven map rows echoing the first page, with one action character typed.
     *
     * @param action   the character typed, or {@code ""} for none
     * @param rows     the seeded rows
     * @param cobolRow the 1-based row it was typed on
     * @return exactly seven rows
     */
    private static List<ListRow> selectionRows(String action, List<String> rows, int cobolRow) {
        return mapRows(rows, Map.of(Integer.valueOf(cobolRow), action));
    }

    /**
     * Seven map rows echoing the first page with {@code 'S'} on row one and {@code 'U'} on row three -
     * the two-selection state {@code :1084-1095} rejects.
     *
     * @param rows the seeded rows
     * @return exactly seven rows
     */
    private static List<ListRow> twoSelectionRows(List<String> rows) {
        return mapRows(rows, Map.of(Integer.valueOf(1), "S", Integer.valueOf(3), "U"));
    }

    /**
     * Seven map rows carrying the first page's data plus the given typed characters.
     *
     * @param rows    the seeded rows
     * @param actions the typed characters by 1-based row number
     * @return exactly seven rows
     */
    private static List<ListRow> mapRows(List<String> rows, Map<Integer, String> actions) {
        FixedWidthCodec codec = new FixedWidthCodec(ParityHarness.FIXTURE_CHARSET);
        List<ListRow> mapped = new ArrayList<>(MAX_SCREEN_LINES);
        for (int row = FIRST_ROW; row <= MAX_SCREEN_LINES; row++) {
            boolean seeded = row <= rows.size();
            CardRecord card = seeded
                    ? CardRecord.decodeImage(rows.get(row - 1), ParityHarness.FIXTURE_CHARSET)
                    : null;
            String select = actions.getOrDefault(Integer.valueOf(row), String.valueOf(LOW_VALUE));
            String acctNo = seeded ? card.cardAcctIdImage(codec) : String.valueOf(LOW_VALUE);
            String cardNum = seeded ? card.cardNum() : String.valueOf(LOW_VALUE);
            String status = seeded ? card.cardActiveStatus() : String.valueOf(LOW_VALUE);
            mapped.add(row == FIRST_ROW
                    ? new FirstListRow(select, acctNo, cardNum, status)
                    : new StopperListRow(select, String.valueOf(LOW_VALUE), acctNo, cardNum, status));
        }
        return mapped;
    }

    /**
     * The AID as the query parameter carries it - an unsigned {@code 0..255} integer.
     *
     * @param eibAid the raw byte
     * @return the boxed unsigned value
     */
    private static Integer aid(byte eibAid) {
        return Integer.valueOf(Byte.toUnsignedInt(eibAid));
    }

    /**
     * Rows {@code fromRow} through {@code fromRow + count} of the authoritative card fixture.
     *
     * <p>Read from {@code src/test/resources/fixtures/carddata.txt}, which is a byte-for-byte copy of
     * {@code app/data/ASCII/carddata.txt}: 50 records of 150 bytes, ascending by card number. Decoded with
     * the charset named explicitly, never the platform default (practice <strong>B8</strong>).
     *
     * @param fromRow the zero-based first row
     * @param count   how many rows
     * @return the rows, verbatim
     */
    private static List<String> fixtureRows(int fromRow, int count) {
        List<String> rows = readCardFixture();
        if (fromRow < 0 || count < 0 || fromRow + count > rows.size()) {
            throw new IllegalArgumentException(CARD_FIXTURE + " holds " + rows.size()
                    + " rows, so rows " + fromRow + " through " + (fromRow + count)
                    + " do not exist. Seeding fewer rows than a test describes is the silent-pass "
                    + "failure the harness exists to prevent.");
        }
        return List.copyOf(rows.subList(fromRow, fromRow + count));
    }

    /**
     * Every row of the card fixture, decoded with the code page named explicitly.
     *
     * <p>A carriage return is refused rather than stripped, for the reason the harness refuses one: a
     * fixture checked out with translated line endings makes every row one byte wider than its copybook,
     * and the failure would then surface far from its cause.
     *
     * @return the 50 rows, verbatim and in fixture order
     */
    private static List<String> readCardFixture() {
        byte[] content;
        try (InputStream stream = COCRDLICParityTest.class.getClassLoader()
                .getResourceAsStream(CARD_FIXTURE)) {
            if (stream == null) {
                throw new IllegalStateException("Fixture " + CARD_FIXTURE + " is not on the test "
                        + "classpath. It is a byte-for-byte copy of app/data/ASCII/carddata.txt and is "
                        + "the authoritative input every COCRDLIC case is seeded from.");
            }
            content = stream.readAllBytes();
        } catch (IOException unreadable) {
            throw new IllegalStateException("Fixture " + CARD_FIXTURE + " could not be read, so no "
                    + "COCRDLIC assertion has an input to stand on.", unreadable);
        }
        String text = new String(content, ParityHarness.FIXTURE_CHARSET);
        List<String> rows = new ArrayList<>();
        for (String row : text.split("\n", -1)) {
            if (row.isEmpty()) {
                continue;
            }
            if (row.indexOf('\r') >= 0) {
                throw new IllegalStateException("Fixture " + CARD_FIXTURE + " contains a carriage "
                        + "return, so the working tree translated its line endings and every row is one "
                        + "byte wider than app/cpy/CVACT02Y.cpy declares. Restore the fixture rather "
                        + "than stripping the byte here.");
            }
            if (row.length() != CardRecord.RECORD_LENGTH) {
                throw new IllegalStateException("Fixture " + CARD_FIXTURE + " has a row of "
                        + row.length() + " characters; app/cpy/CVACT02Y.cpy declares exactly "
                        + CardRecord.RECORD_LENGTH + ", FILLER X(59) included.");
            }
            rows.add(row);
        }
        return rows;
    }
}
