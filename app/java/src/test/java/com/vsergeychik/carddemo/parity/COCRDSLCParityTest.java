package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vsergeychik.carddemo.card.CardRepository;
import com.vsergeychik.carddemo.card.CardSelectController;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.card.dto.CardSelectRequest;
import com.vsergeychik.carddemo.card.dto.CardSelectResponse;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedResponse;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedSend;
import com.vsergeychik.carddemo.parity.ParityCase.EmittedMessage;
import com.vsergeychik.carddemo.parity.ParityCase.ForcedOutcome;
import com.vsergeychik.carddemo.parity.ParityCase.MessageChannel;
import com.vsergeychik.carddemo.parity.ParityCase.RepositoryOperation;
import com.vsergeychik.carddemo.parity.ParityCase.Termination;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.parity.ParityHarness.Invocation;
import com.vsergeychik.carddemo.parity.ParityHarness.SeededDataset;
import com.vsergeychik.carddemo.parity.ParityHarness.UnitOutcome;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * The behavioural-parity gate for {@code COCRDSLC} - the credit-card detail screen, CSD transaction
 * {@code CCDL}, projected as {@code GET /api/cards/{cardNum}} onto
 * {@link com.vsergeychik.carddemo.card.CardSelectController}.
 *
 * <p>Twenty declarative cases live beside this class under
 * {@code src/test/resources/parity/COCRDSLC/}, one per file, {@code case01} through {@code case20}.
 * Each is loaded, executed against the translated unit, and judged field by field by
 * {@link FieldDiffer}. <strong>The module is not complete until the diff count is zero across all
 * twenty.</strong> That is the gate, and it is per module rather than per build: nineteen clean cases
 * and one difference means this module is incomplete, not ninety-five per cent done.
 *
 * <h2>Rules governing this file</h2>
 *
 * <p><strong>{@code review_rules} returns exactly one line: "No user rules provided."</strong> That
 * single line is the whole document - there is nothing further to page through, and it was
 * re-confirmed immediately before this class was written. No rule forces this file into scope; the
 * migration plan does, in its test-additions inventory. Their absence is <em>not</em> licence to lower
 * the bar, so the plan's enterprise best practices <strong>B1-B12</strong> bind in their place. The
 * ones that shaped this class are named where they apply: B1 and B2 on the closed dependency set, B4
 * on documenting a divergence instead of repairing it, B7 on determinism, B8 on explicitness, B9 on
 * the absence of static mutable state, B10 on tests shipping with the implementation, and B12 on the
 * baseline's provenance.
 *
 * <h2>The baseline is statically derived. It was never captured from a running program.</h2>
 *
 * <p>Every expected value in the twenty case files was produced by structured reading of
 * {@code app/cbl/COCRDSLC.cbl}, cross-checked against four authoritative sources: the symbolic map
 * {@code app/cpy-bms/COCRDSL.CPY} for field names and widths, the mapset {@code app/bms/COCRDSL.bms}
 * for the {@code DFHMDF} definitions behind them, the copybooks {@code app/cpy/CVACT02Y.cpy},
 * {@code app/cpy/CVCRD01Y.cpy}, {@code app/cpy/COCOM01Y.cpy} and {@code app/cpy/CSSTRPFY.cpy} for
 * record and work-area layouts, and the real fixture {@code app/data/ASCII/carddata.txt} for input
 * data. Widths and offsets are taken mechanically from the copybooks rather than from prose, and every
 * case seeds genuine fixture rows rather than invented ones.
 *
 * <p>They are <strong>not</strong> recorded from, replayed from, or diffed against any execution of
 * the legacy COBOL, and nothing here should be read as though they were. Executing it is empirically
 * impossible in this environment: there is no z/OS and no CICS runtime; the available COBOL compiler
 * reports its indexed file handler as disabled, which excludes every program using
 * {@code ORGANIZATION INDEXED}; no Language Environment {@code CEE*} services exist; and the
 * IBM-supplied {@code DFHAID}, {@code DFHBMSCA} and {@code DFHATTR} that this program copies at
 * {@code :208-209} are absent from the repository altogether.
 *
 * <p>This substitutes the <em>provenance</em> of the expected values and nothing else. Twenty cases,
 * field-for-field diffing, the diff-count-equals-zero gate and the branch-coverage bar are all
 * preserved unchanged. Because it nonetheless modifies a stated success criterion it is escalated for
 * explicit user confirmation rather than quietly absorbed (practice <strong>B12</strong>, plan risk
 * <strong>R-A</strong>). The residual exposure is honest and worth stating: a statically derived
 * expectation can encode a misreading of the COBOL where a captured one could not.
 *
 * <h2>The class name says Select. The program is a View. Neither is changed.</h2>
 *
 * <p>{@code CardSelectController} is the name the build prompt mandates and it is kept
 * <strong>verbatim</strong>. It does not describe the program. Three independent readings agree that
 * {@code COCRDSLC} is a detail view and selects nothing: its own header at
 * {@code app/cbl/COCRDSLC.cbl:4} reads "Accept and process credit card detail request";
 * {@code README.md:213-231} documents transaction {@code CCDL} as "Credit Card View"; and the code
 * reads exactly one record and projects it onto fifteen fields, offering no list and no selection.
 *
 * <p>Rule <strong>R1</strong> resolves it - the name comes from the prompt, the behaviour comes from
 * the source - so the class is not renamed and the cases below assert detail-view behaviour. This is
 * one of the sixteen entries in the plan's class-name divergence register, and it is recorded here
 * rather than repaired (practice <strong>B4</strong>).
 *
 * <h2>{@code COCRDSL} is the one symbolic map two programs consume</h2>
 *
 * <p>{@code app/cbl/COCRDSLC.cbl:215} copies {@code COCRDSL}, and so does
 * {@code app/cbl/COCRDLIC.cbl} - the card list drives two maps, its own {@code COCRDLI} and this
 * screen's {@code COCRDSL}. Every other one of the seventeen symbolic maps has a single consumer.
 * {@link #theFifteenSharedFieldWidthsAreTheSymbolicMapsOwn()} therefore pins all fifteen widths from
 * {@code app/cpy-bms/COCRDSL.CPY} against <em>both</em> projections of the map, so that changing one
 * width fails here and in {@code COCRDLICParityTest} together. Two tests failing on one change is the
 * signal that the type is genuinely shared; one failing would be the signal that it is not.
 *
 * <h2>No HTTP sits between the assertion and the code</h2>
 *
 * <p>Every case declares {@link UnitKind#CONTROLLER_POJO} and is reached the way that name says: the
 * controller is constructed through its own constructor as a plain Java object and its handler method
 * is called directly. There is no {@code MockMvc}, no test REST template, no web test client, no
 * servlet container and no application context anywhere in this file - no request, no dispatcher, no
 * filter chain and no serialisation round trip between the assertion and the decision logic. Calling a
 * Java method on a Java object is not HTTP, which is why this shape satisfies the requirement rather
 * than bending it.
 *
 * <p>The controller is the right unit for this program: the {@code card} package lifts logic into a
 * service for {@code COCRDUPC} alone, so {@code COCRDSLC}'s edits, reads and screen painting all live
 * in the controller and there is no service to reach instead.
 *
 * <h2>One repository, two access paths - never two tables</h2>
 *
 * <p>{@code app/cbl/COCRDSLC.cbl} names two CICS files: {@code CARDDAT} at {@code :187-188} and
 * {@code CARDAIX} at {@code :189-190}. {@code CARDAIX} is an alternate-index <em>path</em> over the
 * {@code CARDDAT} base cluster, not a second dataset, and {@code app/jcl/INTCALC.jcl} makes the shape
 * explicit for the sibling cross-reference file by opening it twice in one step - {@code XREFFILE} on
 * the base and {@code XREFFIL1} on the path. Accordingly every case here reads through <em>one</em>
 * {@link CardRepository}, and {@link #theAlternateIndexIsASecondFinderOnTheSameRepository()} proves
 * both finders live on that one object. A case expecting a distinct {@code CARDAIX} table would be a
 * schema change, which the migration forbids outright.
 *
 * <p>The {@code CARDAIX} read is nonetheless unreachable from any case, and the reason is a property
 * of the source rather than a gap here: {@code 9150-GETCARD-BYACCT} at {@code :779-812} is
 * <strong>never performed</strong>. Only {@code 9000-READ-DATA} at {@code :726-730} reaches a read
 * paragraph and it performs {@code 9100-GETCARD-BYACCTCARD} alone. Driving the dead paragraph from a
 * whole-program case would mean inventing a {@code PERFORM} the program does not contain, so the
 * alternate path is asserted at the repository seam instead, where it exists without being invented
 * (practice <strong>B5</strong>).
 *
 * <h2>Determinism</h2>
 *
 * <p>Each case pins its own instant and each run builds a {@link Clock#fixed} from it, so
 * {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} - performed twice, at {@code :430} and again
 * at {@code :437} - yields byte-identical {@code CURDATEO} and {@code CURTIMEO} images every time. The
 * code page is named explicitly and never taken from the platform. There is no static mutable state:
 * every constant here is immutable, the harness, the differ, the controller and the stub repository are
 * all built per invocation, and no two cases can observe each other.
 *
 * @see ParityHarness for how a case is seeded, invoked and captured
 * @see FieldDiffer for how the resulting fingerprint is judged
 * @see CardSelectController for the translation under test
 */
final class COCRDSLCParityTest {

    /**
     * The program these cases pin, which is also the {@code parity/<PROGRAM>/} directory segment.
     *
     * <p>Stated once so the class name, the resource directory and the {@code program} member of every
     * case file cannot drift apart: {@link #cases()} loads by this value and
     * {@link #everyCaseNamesThisProgramAndPinsOneNamedBranch(ParityCase)} asserts each file agrees with it.
     */
    private static final String PROGRAM = "COCRDSLC";

    /** The dataset binding key this program reads - the {@code CARDDAT} base cluster. */
    private static final String CARDDAT = CardRepository.BASE_DD_NAME;

    /** The number of cases the gate requires, restated locally so a wrong count fails loudly. */
    private static final int REQUIRED_CASES = ParityHarness.CASES_PER_PROGRAM;

    /** The code page the nine ASCII fixtures are written in; never the platform default (B8). */
    private static final Charset FIXTURE_CHARSET = StandardCharsets.US_ASCII;

    /**
     * The {@code L} suffix BMS appends to a {@code DFHMDF} label to name the symbolic-map length item.
     *
     * <p>{@code ScreenMetadata} publishes the label - {@code ACCTSID} - while a case declares the item
     * that received the {@code MOVE -1}, which is {@code ACCTSIDL}. This is the one character between
     * them.
     */
    private static final String LENGTH_ITEM_SUFFIX = "L";

    /** The {@code C} suffix naming a field's extended-colour attribute item, {@code ACCTSIDC}. */
    private static final String COLOUR_ITEM_SUFFIX = "C";

    /** The {@code H} suffix naming a field's extended-highlight attribute item, {@code ACCTSIDH}. */
    private static final String HIGHLIGHT_ITEM_SUFFIX = "H";

    /**
     * Every {@code DFHAID} mnemonic keyed by its raw {@code EIBAID} byte, from {@code common.CicsAid}.
     *
     * <p>Taken from the class that reproduces the absent IBM copybook rather than re-listed, so the
     * mnemonic a case file writes and the byte this class hands the controller cannot diverge.
     * {@link Map#copyOf} makes the view unmodifiable, so this is a constant and not shared mutable
     * state (practice <strong>B9</strong>).
     */
    private static final Map<Byte, String> AID_MNEMONICS = Map.copyOf(CicsAid.mnemonicsByAid());

    // =================================================================================================
    // The gate.
    // =================================================================================================

    /**
     * The twenty cases, in {@code case01} to {@code case20} order.
     *
     * <p>The count is checked here rather than left to a separate test, because a directory that lost a
     * file would otherwise make the gate <em>quieter</em>: nineteen passing cases and no failure at all
     * is the worst possible outcome for an acceptance check, so a wrong count fails the whole class
     * before a single case runs.
     *
     * @return the twenty cases this program owns
     */
    private static List<ParityCase> cases() {
        List<ParityCase> loaded = ParityHarness.casesOf(PROGRAM);
        if (loaded.size() != REQUIRED_CASES) {
            throw new IllegalStateException("Parity gate for " + PROGRAM + " requires exactly "
                    + REQUIRED_CASES + " cases under src/test/resources/parity/" + PROGRAM
                    + "/, named case01 through case" + REQUIRED_CASES + ", but " + loaded.size()
                    + " loaded. A missing case does not weaken the gate - it removes it, because an "
                    + "assertion nobody runs cannot fail.");
        }
        for (int ordinal = 1; ordinal <= REQUIRED_CASES; ordinal++) {
            String expected = ParityHarness.caseId(ordinal);
            String actual = loaded.get(ordinal - 1).caseId();
            if (!expected.equals(actual)) {
                throw new IllegalStateException("Parity case " + ordinal + " for " + PROGRAM
                        + " is \"" + actual + "\" where the gate requires \"" + expected
                        + "\". The identifiers are the file-name stems and they are ordered, so a "
                        + "mis-numbered file would silently take another case's place.");
            }
        }
        return loaded;
    }

    /**
     * Runs one case and requires the differ to find nothing.
     *
     * <p>The assertion is on {@link DiffResult#count()} with {@link DiffResult#render()} as the
     * description, so a failure prints every difference - each naming its dataset or channel, its COBOL
     * field, that field's declared offset and length, the expected image and the observed one - rather
     * than a bare "expected 0 but was 3".
     *
     * @param parityCase the case to run, supplied by {@link #cases()}
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("COCRDSLC: every case diffs to zero against the COBOL-derived baseline")
    void theTranslationMatchesTheCobolFieldForField(ParityCase parityCase) {
        DiffResult result = ParityHarness.usAscii()
                .judge(parityCase, UnitKind.CONTROLLER_POJO, COCRDSLCParityTest::invoke);

        assertThat(result.count())
                .describedAs("%s/%s must diff to zero. %s", PROGRAM, parityCase.caseId(),
                        result.render())
                .isZero();
    }

    // =================================================================================================
    // The adapter: construct the controller as a plain object and call its handler.
    // =================================================================================================

    /**
     * Constructs {@link CardSelectController} through its constructor and calls
     * {@code viewCardDetail} directly, then records what the invocation observably produced.
     *
     * <p>Three collaborators, all supplied here and none of them framework: the repository is a
     * fixture-backed stub over the seeded {@code CARDDAT} rows, the clock is fixed at the instant the
     * case pins, and the code page is the fixtures' own. Nothing is autowired, because nothing needs to
     * be - the constructor takes exactly these three.
     *
     * @param invocation the seeded datasets, the AID, the inbound commarea, the pinned clock and the
     *                   recorder
     * @return the outcome the recorder holds
     */
    private static UnitOutcome invoke(Invocation invocation) {
        SeededDataset seeded = invocation.dataset(CARDDAT);
        CardSelectRequest request = requestOf(invocation);
        String pathCardNumber = pathCardNumberOf(invocation);

        CardSelectController controller = new CardSelectController(
                seededCardRepository(invocation, seeded), invocation.clock(), FIXTURE_CHARSET);

        ScreenResponse<CardSelectResponse> body = controller.viewCardDetail(
                pathCardNumber,
                request,
                null,
                invocation.eibcalen(),
                Byte.toUnsignedInt(aidByteOf(invocation))).getBody();
        CardSelectResponse painted = Objects.requireNonNull(body, "COCRDSLC always ends in EXEC CICS "
                + "RETURN, so viewCardDetail always answers 200 OK with a body").screen();

        UnitOutcome.Builder recorder = invocation.recorder();
        // The seeded rows, on the final-state channel and unchanged. COCRDSLC issues no WRITE, no
        // REWRITE and no DELETE - its only file verb is the READ at :742-750 - so "exactly as seeded" is
        // the positive assertion this program's every path needs, and an empty write channel is the
        // other half of it.
        recorder.finalStateUnchanged(seeded, CardRecord.LAYOUT);
        recorder.response(observed(painted));
        emitPlainText(painted, recorder);
        // EXEC CICS RETURN, never EXEC CICS ABEND: every path through this program ends normally, so the
        // COBOL RETURN-CODE is zero. Stated rather than defaulted, because a defaulted return code is
        // indistinguishable from one nobody thought about.
        recorder.returnCode(0);
        return recorder.build();
    }

    /**
     * Projects the returned payload onto the observation shape {@link FieldDiffer} judges.
     *
     * <p>Four decisions are worth stating, because each is the difference between an observation that
     * can fail and one that cannot.
     *
     * <p><strong>A blank next-screen token is reported absent.</strong> The three carriers are
     * initialised to spaces at their declared widths, and a path that never assigns one leaves those
     * spaces behind. Seven spaces is not a BMS map name; the faithful report is that no target was
     * named, so {@link #tokenOrAbsent(String)} maps blank to {@code null}. Reporting the spaces
     * verbatim would also make the case unwritable, since a case validates these three against the
     * copybook name shape.
     *
     * <p><strong>The send count is derived from the map name, not assumed.</strong>
     * {@code 1400-SEND-SCREEN} at {@code :565-566} is the only place {@code CCARD-NEXT-MAP} is
     * assigned before a send, so a named map is exactly the condition "this invocation performed
     * {@code EXEC CICS SEND MAP}". The {@code XCTL} arm at {@code :331} and the {@code SEND TEXT} arm at
     * {@code :839} both leave it blank and both correctly report zero sends.
     *
     * <p><strong>The cursor is reported as the {@code xxxL} item, not the label.</strong> COBOL
     * positions the cursor by moving {@code -1} into the length item - {@code :518}, {@code :521} and
     * {@code :523} all do - so the length item is the cursor, and that is what a case declares.
     *
     * <p><strong>All three terminations are distinguished by what was assigned, not by which key was
     * pressed.</strong> A named next program with no map sent is the {@code XCTL} at {@code :331-334},
     * which transfers control and never reaches the {@code EXEC CICS RETURN} three paragraphs later. A
     * map sent, or a program named alongside one, is {@code COMMON-RETURN} at {@code :394-406}, whose
     * {@code EXEC CICS RETURN TRANSID(LIT-THISTRANID) COMMAREA(WS-COMMAREA)} keeps the
     * pseudo-conversation alive. Neither one named, with the error line no longer holding
     * {@code LOW-VALUES}, is {@code SEND-PLAIN-TEXT} at {@code :838-851} - see
     * {@link #sentPlainText(CardSelectResponse)} - whose {@code EXEC CICS RETURN} at {@code :846-847} is
     * <strong>bare</strong>, so it is reported as {@link Termination#RETURN_NO_TRANSID} and not as
     * {@link Termination#RETURN_TRANSID}. Collapsing the third onto the second would assert that the
     * conversation continues when the source ends it, which is exactly the normalisation the parity
     * contract exists to prevent; {@code case20} is the case that pins it.
     *
     * @param painted the payload the handler returned
     * @return the observation
     */
    private static ObservedResponse observed(CardSelectResponse painted) {
        String nextProgram = tokenOrAbsent(painted.getNextProgram());
        String nextMapset = tokenOrAbsent(painted.getNextMapset());
        String nextMap = tokenOrAbsent(painted.getNextMap());

        List<ObservedSend> sends = new ArrayList<>(1);
        if (nextMap != null) {
            sends.add(new ObservedSend(painted.fieldImages(), attributeMnemonics(painted)));
        }

        String cursorField = painted.getCursorField() == null
                ? null
                : painted.getCursorField() + LENGTH_ITEM_SUFFIX;

        Termination termination;
        if (nextProgram != null && sends.isEmpty()) {
            termination = Termination.XCTL;
        } else if (sentPlainText(painted)) {
            termination = Termination.RETURN_NO_TRANSID;
        } else {
            termination = Termination.RETURN_TRANSID;
        }

        return new ObservedResponse(nextProgram, nextMapset, nextMap,
                navigation(painted), sends, cursorField, termination);
    }

    /**
     * The {@code CARDDEMO-COMMAREA} the response carries, keyed by the copybook's own field names.
     *
     * <p>This is where statelessness is asserted (gate G37, rule R6). The conversation state travels in
     * the payload, so it is comparable, and every one of the sixteen fields is reported - the differ
     * compares in both directions, so a field the case does not pin is reported as unpinned rather than
     * skipped. A translation that kept this state in an {@code HttpSession} could not satisfy the
     * comparison at all, because there would be nothing in the response to compare.
     *
     * @param painted the payload the handler returned
     * @return the sixteen commarea fields as images at their declared widths
     */
    private static Map<String, String> navigation(CardSelectResponse painted) {
        return NavigationImage.of(painted.getNavigationContext());
    }

    /**
     * The attribute items this program writes, named by their symbolic-map items and valued with the
     * {@code DFHBMSCA} mnemonic the program moved.
     *
     * <p>Thirty items: the fifteen {@code xxxC} extended-colour items and the fifteen {@code xxxH}
     * extended-highlight items. Those two planes are the ones {@code 1300-SETUP-SCREEN-ATTRS} reaches -
     * {@code DFHDFCOL} at {@code :529-530}, {@code DFHRED} at {@code :534}, {@code :538}, {@code :544}
     * and {@code :550}, {@code DFHBMDAR} at {@code :554} and {@code DFHNEUTR} at {@code :556} - and each
     * of the sixty bytes on them has a mnemonic, including {@code 0x00}, which is {@code DFHDFCOL} on
     * the colour plane and {@code DFHDFHI} on the highlight plane.
     *
     * <p>The {@code xxxP} and {@code xxxV} planes are deliberately absent, and not because they do not
     * matter. {@code DFHBMSCA} publishes no mnemonic table for a programmed-symbol or validation byte,
     * so a value on either plane cannot be named as a mnemonic and a case could not declare one. They
     * are asserted instead by {@link #theProgrammedSymbolAndValidationPlanesAreNeverWritten()}, which
     * requires all thirty bytes to stay at {@code 0x00} on every one of the twenty cases - a stronger
     * statement than an unnameable expectation, and an explicit one rather than a silent omission.
     *
     * @param painted the payload the handler returned
     * @return thirty attribute items keyed by symbolic-map name
     */
    private static Map<String, String> attributeMnemonics(CardSelectResponse painted) {
        Map<String, String> items = new LinkedHashMap<>();
        for (CardSelectResponse.ScreenField field : CardSelectResponse.ScreenField.values()) {
            CardSelectResponse.FieldAttributes quad = painted.attributes(field);
            items.put(field.dfhmdfLabel() + COLOUR_ITEM_SUFFIX, colourMnemonic(quad.getColour()));
            items.put(field.dfhmdfLabel() + HIGHLIGHT_ITEM_SUFFIX,
                    highlightMnemonic(quad.getHilight()));
        }
        return items;
    }

    /**
     * Names an extended-colour byte, refusing an unnameable one rather than rendering it.
     *
     * @param colour the byte the program moved into an {@code xxxC} item
     * @return the {@code DFHBMSCA} mnemonic
     * @throws IllegalStateException if the byte is not one of the eight declared colours
     */
    private static String colourMnemonic(byte colour) {
        String mnemonic = BmsAttributes.COLOUR_MNEMONICS.get(colour);
        if (mnemonic == null) {
            throw new IllegalStateException(String.format(
                    "COCRDSLC moved 0x%02X into an extended-colour item, which is not one of the eight "
                            + "DFHBMSCA colours. A colour a case cannot name is a colour nobody can "
                            + "assert, so this is reported rather than rendered as a raw byte.",
                    Byte.toUnsignedInt(colour)));
        }
        return mnemonic;
    }

    /**
     * Names an extended-highlight byte, refusing an unnameable one rather than rendering it.
     *
     * @param highlight the byte in an {@code xxxH} item
     * @return the {@code DFHBMSCA} mnemonic
     * @throws IllegalStateException if the byte is not one of the four declared highlights
     */
    private static String highlightMnemonic(byte highlight) {
        String mnemonic = BmsAttributes.HIGHLIGHT_MNEMONICS.get(highlight);
        if (mnemonic == null) {
            throw new IllegalStateException(String.format(
                    "COCRDSLC left 0x%02X in an extended-highlight item, which is not one of the four "
                            + "DFHBMSCA highlights. The program writes no highlight at all, so anything "
                            + "other than DFHDFHI is itself the finding.",
                    Byte.toUnsignedInt(highlight)));
        }
        return mnemonic;
    }

    /**
     * A next-screen token, or {@code null} when the path assigned none.
     *
     * @param token the carrier's value, space-filled at its declared width when unassigned
     * @return the trimmed token, or {@code null} when it is blank
     */
    private static String tokenOrAbsent(String token) {
        return token == null || token.isBlank() ? null : token.trim();
    }

    /**
     * Records the {@code SEND TEXT} at {@code :839-844} as an emitted line when it happened.
     *
     * <p>{@code SEND-PLAIN-TEXT} transmits {@code WS-RETURN-MSG PIC X(75)} and sends no map, so the
     * fifteen data items stay exactly as {@code MOVE LOW-VALUES TO CCRDSLAO} left them and there is no
     * screen send to carry the text. Reporting it as a message on the variable-width
     * {@link MessageChannel#DISPLAY_LINE} channel is what makes the {@code WHEN OTHER} arm assertable at
     * all - without it the only observable difference between that arm and a path that did nothing would
     * be invisible.
     *
     * <p>Detected by exactly the condition that distinguishes the arm: no map was named, no program was
     * named, and the error line carries something other than the {@code LOW-VALUES} the initialisation
     * left. The eighty-character field is reported at the seventy-five the {@code SEND TEXT} actually
     * transmitted, because {@code LENGTH(LENGTH OF WS-RETURN-MSG)} is what the source states.
     *
     * @param painted  the payload the handler returned
     * @param recorder the recorder to write the line into
     */
    private static void emitPlainText(CardSelectResponse painted, UnitOutcome.Builder recorder) {
        if (!sentPlainText(painted)) {
            return;
        }
        recorder.message(new EmittedMessage(MessageChannel.DISPLAY_LINE,
                painted.getErrmsgo().substring(0, CardScreenState.CCARD_RETURN_MSG_LENGTH)));
    }

    /**
     * Whether this invocation left through {@code SEND-PLAIN-TEXT} at
     * {@code app/cbl/COCRDSLC.cbl:838-851} rather than through {@code COMMON-RETURN} or the
     * {@code XCTL}.
     *
     * <p>The program has exactly three exits and this predicate separates the third from the other two.
     * {@code COMMON-RETURN} at {@code :394-406} is always preceded by {@code 1400-SEND-SCREEN}, which
     * names the map at {@code :565-566}; the {@code XCTL} at {@code :331-334} names the next program at
     * {@code :332}; and {@code SEND-PLAIN-TEXT} names <em>neither</em> while writing the transmitted text
     * onto the error-line carrier, displacing the {@code LOW-VALUES} that {@code MOVE LOW-VALUES TO
     * CCRDSLAO} at {@code :428} left there. So "no map, no program, and an error line that is no longer
     * {@code LOW-VALUES}" is not a heuristic - it is the exact complement of the other two exits.
     *
     * <p>Factored out because two separate observations depend on it and they must not be allowed to
     * disagree: the emitted line, and the {@link Termination} reported by {@link #observed}. When they
     * were computed independently one of them was wrong - the line was recorded correctly while the
     * termination was reported as {@link Termination#RETURN_TRANSID}, which claims the
     * pseudo-conversation continues under {@code TRANSID('CCDL')} with a 2000-byte commarea when in fact
     * {@code :846-847} is a <strong>bare</strong> {@code EXEC CICS RETURN} carrying no {@code TRANSID}
     * and no {@code COMMAREA} and the conversation ends. One predicate, one reading.
     *
     * @param painted the payload the handler returned
     * @return {@code true} when the run ended at {@code :846-847}
     */
    private static boolean sentPlainText(CardSelectResponse painted) {
        boolean sentMap = tokenOrAbsent(painted.getNextMap()) != null;
        boolean transferred = tokenOrAbsent(painted.getNextProgram()) != null;
        boolean errorLineUntouched = painted.getErrmsgo().equals(
                CardScreenState.lowValues(CardSelectResponse.ERRMSGO_LENGTH));
        return !sentMap && !transferred && !errorLineUntouched;
    }

    // =================================================================================================
    // Building the invocation's three inputs: the map area, the URI's card number and the AID.
    // =================================================================================================

    /**
     * The terminal input area the case declares, or {@code null} for the cold start.
     *
     * <p>{@code null} rather than an empty request, because {@code app/cbl/COCRDSLC.cbl:268} branches on
     * {@code EIBCALEN = 0} and the two states are not interchangeable: an absent communication area is
     * the cold start, an initialised one is not. A case declaring no {@code commarea} is declaring the
     * former.
     *
     * <p>Only {@code ACCTSIDI} and {@code CARDSIDI} are set from {@code mapFields}, because those are the
     * only two items the program reads off the map - {@code :615-619} and {@code :622-626}. The other
     * thirteen are titles, the date and time header, the record it displays and the two message lines,
     * all of which it writes rather than reads, so setting them would state an input the program has no
     * way to observe.
     *
     * @param invocation the invocation carrying the case's screen request
     * @return the request to hand the handler, or {@code null} for a bodiless call
     */
    private static CardSelectRequest requestOf(Invocation invocation) {
        Map<String, String> commarea = invocation.commarea();
        if (commarea.isEmpty()) {
            return null;
        }
        CardSelectRequest request = new CardSelectRequest();
        Map<String, String> mapFields = invocation.mapFields();
        String accountFilter = mapFields.get(
                CardSelectRequest.ScreenField.ACCTSID.symbolicItemName());
        if (accountFilter != null) {
            request.setAcctsid(accountFilter);
        }
        String cardFilter = mapFields.get(CardSelectRequest.ScreenField.CARDSID.symbolicItemName());
        if (cardFilter != null) {
            request.setCardsid(cardFilter);
        }
        request.setNavigationContext(NavigationImage.from(commarea));
        return request;
    }

    /**
     * The card number the URI names, which the controller reconciles with {@code CARDSID}.
     *
     * <p>Taken from the case's {@code CARDSIDI} verbatim, including its trailing spaces, because the URI
     * and the typed field are the same value by construction: {@code bind} moves the path into
     * {@code CARDSID} and - when a communication area travelled - into {@code CDEMO-CARD-NUM}, so the
     * path is the single statement of which record is read. A case that named a different value in each
     * would be asserting a reconciliation the program does not perform.
     *
     * <p>Sixteen spaces when the case declares no {@code CARDSIDI}: {@code CARDSID} is {@code PIC X(16)}
     * and a COBOL alphanumeric item has no absent state, so "the operator typed nothing" is spaces and
     * not null.
     *
     * @param invocation the invocation carrying the case's map fields
     * @return the path value, exactly {@value CardSelectRequest#CARDSID_LENGTH} characters or fewer
     */
    private static String pathCardNumberOf(Invocation invocation) {
        String typed = invocation.mapFields()
                .get(CardSelectRequest.ScreenField.CARDSID.symbolicItemName());
        return typed == null ? CardSelectRequest.spaces(CardSelectRequest.CARDSID_LENGTH) : typed;
    }

    /**
     * The raw {@code EIBAID} byte the case declares, or {@link CicsAid#DFHENTER} when it declares none.
     *
     * <p>The case names the key by its {@code DFHAID} mnemonic and this resolves it to the byte, rather
     * than the other way round, for one reason: {@code DFHAID} is IBM-supplied and absent from this
     * repository, so {@code common.CicsAid} is the single reproduction of it and both sides of the
     * comparison must come from that one place.
     *
     * <p>Absent means {@code DFHENTER} because that is what {@code :291-299} makes of any key it does not
     * act on - an unrecognised AID is coerced to {@code ENTER}, never refused - so {@code ENTER} is the
     * neutral choice a case makes by saying nothing.
     *
     * @param invocation the invocation carrying the case's AID
     * @return the raw attention-identifier byte
     * @throws IllegalStateException if the mnemonic names no AID {@code CicsAid} defines
     */
    private static byte aidByteOf(Invocation invocation) {
        String mnemonic = invocation.hasScreenRequest() ? invocation.aid() : null;
        if (mnemonic == null) {
            return CicsAid.DFHENTER;
        }
        for (Map.Entry<Byte, String> entry : AID_MNEMONICS.entrySet()) {
            if (entry.getValue().equals(mnemonic)) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("Case " + invocation.program() + '/' + invocation.caseId()
                + " names the AID \"" + mnemonic + "\", which common.CicsAid does not define. DFHAID is "
                + "absent from this repository, so that class is the only reproduction of it and a "
                + "mnemonic it does not carry names no key.");
    }

    // =================================================================================================
    // The fixture-backed CARDDAT stub - one repository, and the case decides the arm.
    // =================================================================================================

    /**
     * A {@link CardRepository} over the case's seeded {@code CARDDAT} rows.
     *
     * <p>The read answers from the seeded slice: a row whose first sixteen bytes equal the key returns
     * {@code NORMAL} carrying that row decoded through {@link CardRecord#decodeImage}, and no match
     * returns {@code NOTFND}. That is the whole of {@code 9100-GETCARD-BYACCTCARD}'s reachable behaviour
     * from real data, and it keeps every case seeded from genuine fixture rows rather than from invented
     * ones.
     *
     * <p>An arm the data cannot reach - the {@code WHEN OTHER} at {@code :762-771} - is reached by the
     * case declaring a {@code forcedOutcome} for {@code read}. Taking it through
     * {@link Invocation#forcedOutcome} rather than reading it off the case is deliberate: that call marks
     * the outcome consumed, and the harness refuses a run that declared one nothing asked for. A case
     * whose forced outcome never fired would otherwise take the ordinary path, meet every expectation
     * about the ordinary path, and pass while its description claimed it had exercised the other arm.
     *
     * <p>Every other repository method throws. {@code COCRDSLC}'s only file verb is the {@code READ} at
     * {@code :742-750} - it issues no {@code WRITE}, no {@code REWRITE}, no {@code DELETE} and no browse -
     * so a call to anything else is a translation that reached for a verb the program does not contain,
     * and that must fail loudly rather than receive a default.
     *
     * @param invocation the invocation, consulted for a forced outcome
     * @param seeded     the seeded {@code CARDDAT} rows
     * @return the stub
     */
    private static CardRepository seededCardRepository(Invocation invocation, SeededDataset seeded) {
        CardRepository repository = Mockito.mock(CardRepository.class, unstubbed -> {
            throw new UnsupportedOperationException("COCRDSLC called CardRepository."
                    + unstubbed.getMethod().getName() + ", which it has no statement for. Its only "
                    + "file verb is the EXEC CICS READ at app/cbl/COCRDSLC.cbl:742-750 - there is no "
                    + "WRITE, no REWRITE, no DELETE and no browse anywhere in the program - so a call "
                    + "to anything else is a translation reaching for a verb the source does not "
                    + "contain, and it fails here rather than receiving a default.");
        });
        // doAnswer rather than when(...).thenAnswer(...): the latter would call the method to record the
        // stub, which the strict default answer above turns into a failure during setup.
        Mockito.doAnswer(read -> readByKey(invocation, seeded, read.getArgument(0)))
                .when(repository).readByCardNumber(ArgumentMatchers.anyString());
        Mockito.doAnswer(read -> readByAccountKey(seeded, read.getArgument(0)))
                .when(repository).readByAccountIdViaAltIndex(ArgumentMatchers.anyString());
        return repository;
    }

    /**
     * Answers {@code EXEC CICS READ FILE('CARDDAT ') RIDFLD(WS-CARD-RID-CARDNUM)} from the seeded rows.
     *
     * @param invocation the invocation, consulted for a forced outcome
     * @param seeded     the seeded {@code CARDDAT} rows
     * @param cardNumber the sixteen-character key {@code :740} moved into the RIDFLD
     * @return the outcome the case forced, or the one the seeded rows imply
     */
    private static CardRepository.CardReadResult readByKey(Invocation invocation,
            SeededDataset seeded, String cardNumber) {
        if (invocation.hasForcedOutcome(RepositoryOperation.READ)) {
            return forcedRead(invocation, seeded,
                    invocation.forcedOutcome(RepositoryOperation.READ), cardNumber);
        }
        return rowKeyed(seeded, cardNumber)
                .map(image -> CardRepository.CardReadResult.normal(
                        CardRecord.decodeImage(image, FIXTURE_CHARSET), image))
                .orElseGet(CardRepository.CardReadResult::notFound);
    }

    /**
     * Answers {@code EXEC CICS READ FILE('CARDAIX ') RIDFLD(WS-CARD-RID-ACCT-ID)} - the alternate index
     * over the same base cluster, and a second finder rather than a second dataset (gate G45).
     *
     * <p>Unreachable from any of the twenty cases, because {@code 9150-GETCARD-BYACCT} is never
     * performed; see the class documentation. It is answered here so that
     * {@link #theAlternateIndexIsASecondFinderOnTheSameRepository()} can drive it on the very object the
     * base read is served from, which is the property the gate is about.
     *
     * @param seeded          the seeded {@code CARDDAT} rows
     * @param accountIdDigits the eleven-digit account key
     * @return the first seeded row whose account matches, or {@code NOTFND}
     */
    private static CardRepository.CardReadResult readByAccountKey(SeededDataset seeded,
            String accountIdDigits) {
        for (String image : seeded.rows()) {
            String account = image.substring(CardRecord.CARD_ACCT_ID_OFFSET,
                    CardRecord.CARD_ACCT_ID_OFFSET + CardRecord.CARD_ACCT_ID_LENGTH);
            if (account.equals(accountIdDigits)) {
                return CardRepository.CardReadResult.normal(
                        CardRecord.decodeImage(image, FIXTURE_CHARSET), image);
            }
        }
        return CardRepository.CardReadResult.notFound();
    }

    /**
     * The seeded row whose primary key matches, comparing the full sixteen bytes.
     *
     * @param seeded     the seeded rows
     * @param cardNumber the key, already at its declared width
     * @return the matching row image, or empty
     */
    private static Optional<String> rowKeyed(SeededDataset seeded, String cardNumber) {
        if (cardNumber.length() != CardRecord.CARD_NUM_LENGTH) {
            return Optional.empty();
        }
        for (String image : seeded.rows()) {
            if (image.startsWith(cardNumber)) {
                return Optional.of(image);
            }
        }
        return Optional.empty();
    }

    /**
     * Turns a case's forced outcome into the read result the CICS arm it names would produce.
     *
     * @param invocation the invocation, for the case identifier in a diagnostic
     * @param seeded     the seeded rows, for the arms that still return a record
     * @param outcome    the forced outcome
     * @param cardNumber the key
     * @return the read result
     */
    private static CardRepository.CardReadResult forcedRead(Invocation invocation,
            SeededDataset seeded, ForcedOutcome outcome, String cardNumber) {
        int reasonCode = outcome.resp2() == null ? 0 : outcome.resp2();
        return switch (outcome.outcome()) {
            case OK -> rowKeyed(seeded, cardNumber)
                    .map(image -> CardRepository.CardReadResult.normal(
                            CardRecord.decodeImage(image, FIXTURE_CHARSET), image))
                    .orElseThrow(() -> new IllegalStateException("Case " + invocation.caseId()
                            + " forces OK for the CARDDAT read but seeds no row keyed "
                            + cardNumber.trim() + ", so there is no record to return."));
            case NOT_FOUND -> CardRepository.CardReadResult.notFound();
            case END_OF_FILE -> CardRepository.CardReadResult.endOfFile();
            case DUPLICATE -> rowKeyed(seeded, cardNumber)
                    .map(image -> CardRepository.CardReadResult.duplicateKey(
                            CardRecord.decodeImage(image, FIXTURE_CHARSET), image))
                    .orElseThrow(() -> new IllegalStateException("Case " + invocation.caseId()
                            + " forces DUPLICATE but seeds no row keyed " + cardNumber.trim()));
            case OTHER -> CardRepository.CardReadResult.reportedFailure(
                    outcome.resp() == null ? FileStatus.LENGERR : outcome.resp(), reasonCode);
        };
    }

    // =================================================================================================
    // The gates a declarative case cannot express, asserted directly.
    //
    // Each of these is behaviour the twenty cases depend on but cannot state: a record width, a
    // copybook-declared span, a repository's shape, or an attribute plane that has no mnemonic to name
    // it. They are assertions rather than comments, because a documented invariant nobody checks is an
    // invariant that drifts.
    // =================================================================================================

    /**
     * Every case file agrees with this class about which program it pins and says which branch it pins.
     *
     * <p>The {@code program} member is also the resource-directory segment, so a file that named another
     * program would sit in this directory and be loaded by this gate while asserting something else. And
     * the {@code description} is how a reviewer audits parity coverage without re-reading the COBOL, so a
     * case that did not cite a paragraph, an {@code EVALUATE} arm or an {@code 88}-level would leave the
     * coverage unauditable - the point of requiring a source citation is that it can be checked.
     *
     * @param parityCase one of the twenty cases
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("every case names COCRDSLC and cites the source line it pins")
    void everyCaseNamesThisProgramAndPinsOneNamedBranch(ParityCase parityCase) {
        assertThat(parityCase.program()).isEqualTo(PROGRAM);
        assertThat(parityCase.unitKind())
                .describedAs("COCRDSLC keeps its decision logic in the controller - the card package "
                        + "lifts a service out for COCRDUPC alone - so every case reaches it as a plain "
                        + "Java object")
                .isEqualTo(UnitKind.CONTROLLER_POJO);
        assertThat(parityCase.description())
                .describedAs("%s must cite the COBOL line, paragraph or 88-level it pins, so parity "
                        + "coverage can be audited without re-reading the program", parityCase.caseId())
                .contains(":")
                .hasSizeGreaterThan(80);
        assertThat(parityCase.inputs())
                .describedAs("every case seeds the CARDDAT base cluster and nothing else: CARDAIX is a "
                        + "path over it, not a dataset of its own (gate G45)")
                .containsOnlyKeys(CARDDAT);
        assertThat(parityCase.expectedWrites())
                .describedAs("COCRDSLC issues no WRITE, REWRITE or DELETE, so every case asserts "
                        + "positively that nothing was written")
                .isEmpty();
        assertThat(parityCase.expectedReturnCode())
                .describedAs("every path ends in EXEC CICS RETURN rather than EXEC CICS ABEND")
                .isZero();
    }

    /**
     * The fifteen field widths are the symbolic map's, and both projections of the shared map agree.
     *
     * <p>{@code COCRDSL} is the one symbolic map of the seventeen that two programs consume -
     * {@code app/cbl/COCRDSLC.cbl:215} and {@code app/cbl/COCRDLIC.cbl} both {@code COPY} it - so the
     * request and response projections of it are shared types, and a width changed in one place has to
     * fail in both consumers' tests or it is not shared at all. Asserting the request side against the
     * response side here is what couples them: the twelve data widths must match exactly, and the three
     * that differ must differ by the amounts the copybooks declare and no others.
     */
    @Test
    @DisplayName("the fifteen COCRDSL widths are the symbolic map's own, on both projections")
    void theFifteenSharedFieldWidthsAreTheSymbolicMapsOwn() {
        assertThat(CardSelectRequest.FIELD_COUNT).isEqualTo(15);
        assertThat(CardSelectResponse.FIELD_COUNT).isEqualTo(CardSelectRequest.FIELD_COUNT);
        assertThat(CardSelectRequest.ScreenField.values())
                .hasSize(CardSelectRequest.FIELD_COUNT);
        assertThat(CardSelectResponse.ScreenField.values())
                .hasSize(CardSelectResponse.FIELD_COUNT);

        // app/cpy-bms/COCRDSL.CPY, in declaration order: the xxxI PICTURE clauses and nothing else. The
        // xxxL, xxxF and xxxA items beside them are length, flag and attribute metadata and are not
        // payload fields, which is why none of them appears here.
        Map<String, Integer> declared = new LinkedHashMap<>();
        declared.put("TRNNAME", 4);
        declared.put("TITLE01", 40);
        declared.put("CURDATE", 8);
        declared.put("PGMNAME", 8);
        declared.put("TITLE02", 40);
        declared.put("CURTIME", 8);
        declared.put("ACCTSID", 11);
        declared.put("CARDSID", 16);
        declared.put("CRDNAME", 50);
        declared.put("CRDSTCD", 1);
        declared.put("EXPMON", 2);
        declared.put("EXPYEAR", 4);
        declared.put("INFOMSG", 40);
        declared.put("ERRMSG", 80);
        declared.put("FKEYS", 75);

        for (CardSelectRequest.ScreenField field : CardSelectRequest.ScreenField.values()) {
            assertThat(field.length())
                    .describedAs("%sI PIC X(n) in app/cpy-bms/COCRDSL.CPY", field.label())
                    .isEqualTo(declared.get(field.label()));
            assertThat(field.symbolicItemName()).isEqualTo(field.label() + "I");
        }
        for (CardSelectResponse.ScreenField field : CardSelectResponse.ScreenField.values()) {
            assertThat(field.length())
                    .describedAs("%sO PIC X(n) in app/cpy-bms/COCRDSL.CPY - the shared map's width, so "
                            + "changing it must fail here AND in COCRDLICParityTest", field.dfhmdfLabel())
                    .isEqualTo(declared.get(field.dfhmdfLabel()));
            assertThat(field.cobolName()).isEqualTo(field.dfhmdfLabel() + "O");
        }
    }

    /**
     * {@code CARDAIX} is a second finder on the one {@code CardRepository}, never a second table.
     *
     * <p>This is gate G45, and it is the single most likely place a JDBC migration invents a schema.
     * {@code app/cbl/COCRDSLC.cbl:187-190} names {@code CARDDAT} and {@code CARDAIX}; the second is an
     * alternate-index path over the first's base cluster. {@code app/jcl/INTCALC.jcl} states the shape
     * outright for the sibling cross-reference file by opening it twice in one step, {@code XREFFILE} on
     * the base and {@code XREFFIL1} on the path.
     *
     * <p>So the assertion is deliberately about <em>one object</em>: the same repository instance answers
     * the primary-key read and the alternate-key read, and both return the same record for the same
     * seeded row. A design with two repositories or two tables could not satisfy that, and it would be a
     * schema change the migration forbids.
     */
    @Test
    @DisplayName("CARDAIX is a second finder on the same repository, not a second table")
    void theAlternateIndexIsASecondFinderOnTheSameRepository() {
        assertThat(CardRepository.BASE_DD_NAME).isEqualTo("CARDDAT");
        assertThat(CardRepository.ALTERNATE_INDEX_DD_NAME).isEqualTo("CARDAIX");
        assertThat(CardRepository.BASE_CICS_FILE_NAME)
                .describedAs("LIT-CARDFILENAME PIC X(8) VALUE 'CARDDAT ' at app/cbl/COCRDSLC.cbl:187-188")
                .isEqualTo("CARDDAT ");
        assertThat(CardRepository.ALTERNATE_INDEX_CICS_FILE_NAME)
                .describedAs("LIT-CARDFILENAME-ACCT-PATH PIC X(8) VALUE 'CARDAIX ' at :189-190")
                .isEqualTo("CARDAIX ");
        assertThat(CardRepository.RECORD_LENGTH).isEqualTo(CardRecord.RECORD_LENGTH);

        ParityCase firstCase = cases().get(0);
        ParityHarness harness = ParityHarness.usAscii();
        SeededDataset seeded = harness.seed(firstCase).get(CARDDAT);
        String seededRow = seeded.row(0);
        String primaryKey = seededRow.substring(CardRecord.CARD_NUM_OFFSET,
                CardRecord.CARD_NUM_OFFSET + CardRecord.CARD_NUM_LENGTH);
        String alternateKey = seededRow.substring(CardRecord.CARD_ACCT_ID_OFFSET,
                CardRecord.CARD_ACCT_ID_OFFSET + CardRecord.CARD_ACCT_ID_LENGTH);

        // One repository object, obtained the way the controller is given one, then asked both ways.
        List<CardRepository> theOneRepository = new ArrayList<>(1);
        harness.run(firstCase, UnitKind.CONTROLLER_POJO, invocation -> {
            theOneRepository.add(seededCardRepository(invocation, invocation.dataset(CARDDAT)));
            return invocation.recorder().returnCode(0).build();
        });
        CardRepository repository = theOneRepository.get(0);

        CardRepository.CardReadResult viaBaseKey = repository.readByCardNumber(primaryKey);
        CardRepository.CardReadResult viaAlternateKey =
                repository.readByAccountIdViaAltIndex(alternateKey);

        assertThat(viaBaseKey.isNormal()).isTrue();
        assertThat(viaAlternateKey.isNormal()).isTrue();
        assertThat(viaAlternateKey.requireStoredImage())
                .describedAs("CARDAIX is a path over CARDDAT's base cluster, so both keys reach the same "
                        + "150 bytes. Two tables could not satisfy this, and creating one would be the "
                        + "schema change the migration forbids (gate G45).")
                .isEqualTo(viaBaseKey.requireStoredImage());
        assertThat(viaAlternateKey.requireRecord()).isEqualTo(viaBaseKey.requireRecord());
    }

    /**
     * The {@code xxxP} and {@code xxxV} attribute planes are never written, on any of the twenty cases.
     *
     * <p>{@code 1300-SETUP-SCREEN-ATTRS} at {@code :502-558} reaches the colour plane and nothing else -
     * {@code DFHDFCOL} at {@code :529-530}, {@code DFHRED} at {@code :534}, {@code :538}, {@code :544}
     * and {@code :550}, {@code DFHBMDAR} at {@code :554} and {@code DFHNEUTR} at {@code :556}. It issues
     * no programmed-symbol and no validation move at all, so both planes must be exactly as
     * {@code MOVE LOW-VALUES TO CCRDSLAO} at {@code :428} left them.
     *
     * <p>Asserted here rather than in the case files because {@code DFHBMSCA} publishes no mnemonic table
     * for either plane, so a value on one cannot be named as a mnemonic and a case cannot declare it.
     * This is the stronger statement anyway: all thirty bytes, on every one of the twenty cases.
     *
     * @param parityCase one of the twenty cases
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("COCRDSLC writes no programmed-symbol or validation attribute on any case")
    void theProgrammedSymbolAndValidationPlanesAreNeverWritten(ParityCase parityCase) {
        CardSelectResponse painted = paint(parityCase);
        for (CardSelectResponse.ScreenField field : CardSelectResponse.ScreenField.values()) {
            CardSelectResponse.FieldAttributes quad = painted.attributes(field);
            assertThat(quad.getPs())
                    .describedAs("%s must stay 0x00: COCRDSLC issues no programmed-symbol move",
                            field.psItemName())
                    .isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(quad.getValidn())
                    .describedAs("%s must stay 0x00: COCRDSLC issues no validation move",
                            field.validnItemName())
                    .isEqualTo(BmsAttributes.DFHDFCOL);
        }
    }

    /**
     * The {@code CARD-RECORD} the read returns is 150 bytes with its {@code FILLER} present.
     *
     * <p>Gates G19 and G21. {@code app/cpy/CVACT02Y.cpy} declares the record and closes it with
     * {@code FILLER X(59)}; omitting that span would shift nothing inside the record - it is last - but
     * would shorten the record to 91 bytes and break the fixture's own geometry, which is why the total
     * width is the cheapest possible check for it. The layout's spans are required to tile the record
     * exactly, so a missing or overlapping span fails here rather than as a cascade of field differences
     * later.
     */
    @Test
    @DisplayName("CARD-RECORD is 150 bytes and its FILLER X(59) is a declared span")
    void theCardRecordIsOneHundredAndFiftyBytesWithItsFillerPresent() {
        assertThat(CardRecord.RECORD_LENGTH).isEqualTo(150);
        assertThat(CardRecord.FILLER_OFFSET).isEqualTo(91);
        assertThat(CardRecord.FILLER_LENGTH).isEqualTo(59);
        assertThat(CardRecord.FILLER_OFFSET + CardRecord.FILLER_LENGTH)
                .describedAs("the FILLER closes the record, so the spans tile it exactly")
                .isEqualTo(CardRecord.RECORD_LENGTH);
        assertThat(CardRecord.LAYOUT.recordLength()).isEqualTo(CardRecord.RECORD_LENGTH);

        ParityCase firstCase = cases().get(0);
        SeededDataset seeded = ParityHarness.usAscii().seed(firstCase).get(CARDDAT);
        assertThat(seeded.recordLength()).isEqualTo(CardRecord.RECORD_LENGTH);
        for (String row : seeded.rows()) {
            assertThat(row).hasSize(CardRecord.RECORD_LENGTH);
            assertThat(CardRecord.decodeImage(row, FIXTURE_CHARSET).encodeToImage(FIXTURE_CHARSET))
                    .describedAs("a decode-then-encode round trip must return the same 150 bytes, which "
                            + "it cannot do if the FILLER is dropped on either leg")
                    .isEqualTo(row);
        }
    }

    /**
     * The conversation is 160 bytes in the payload and nothing is held server-side.
     *
     * <p>Gates G37 and rule R6. {@code app/cpy/COCOM01Y.cpy} is 160 bytes, and
     * {@code app/cbl/COCRDSLC.cbl:274-275} reads exactly that many out of {@code DFHCOMMAREA} before
     * {@code :276-278} takes this program's own twelve-byte trailer from immediately after it. Both
     * halves travel in the request and come back in the response, so the whole 172 the
     * {@code EXEC CICS RETURN} at {@code :402-406} passes is client-visible.
     *
     * <p>The statelessness claim is then structural rather than asserted by inspection: two controllers
     * built from the same seeded data, given the same request, produce identical commareas - which they
     * could not if either had retained anything between them.
     */
    @Test
    @DisplayName("the commarea is 160 bytes, travels in the payload, and no state is retained")
    void theConversationTravelsInThePayloadAndNothingIsRetained() {
        assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
        assertThat(NavigationContext.LAYOUT.recordLength()).isEqualTo(NavigationContext.COMMAREA_LENGTH);
        assertThat(CardSelectRequest.PASSED_COMMAREA_LENGTH)
                .describedAs("CARDDEMO-COMMAREA plus WS-THIS-PROGCOMMAREA, :397-400")
                .isEqualTo(NavigationContext.COMMAREA_LENGTH
                        + CardSelectRequest.ThisProgCommarea.RECORD_LENGTH);

        ParityCase reentry = cases().get(4);
        CardSelectResponse first = paint(reentry);
        CardSelectResponse second = paint(reentry);
        assertThat(NavigationImage.of(second.getNavigationContext()))
                .describedAs("the same request twice must yield the same commarea: a controller that "
                        + "retained conversation state between invocations could not")
                .isEqualTo(NavigationImage.of(first.getNavigationContext()));
        assertThat(second.getThisProgCommarea())
                .describedAs("the twelve-byte trailer :276-278 restores must come back, or the next turn "
                        + "receives 160 bytes where this program returned 172")
                .isEqualTo(first.getThisProgCommarea());
    }

    /**
     * {@code CC-ACCT-ID} and {@code CC-ACCT-ID-N} are two typed views over one eleven-byte span.
     *
     * <p>Gate G34. {@code app/cpy/CVCRD01Y.cpy} declares {@code CC-ACCT-ID PIC X(11)} and
     * {@code CC-ACCT-ID-N REDEFINES CC-ACCT-ID PIC 9(11)}, and {@code COCRDSLC} uses both: {@code :342}
     * writes through the numeric view on the card-list arm, while {@code :651-653} tests the
     * alphanumeric view for {@code LOW-VALUES} and {@code SPACES} and the numeric one for {@code ZEROS}
     * in the same condition. Two accessors, one span - so a round trip through either must be visible
     * through the other.
     *
     * <p>The two sibling pairs on the same work area are asserted with it, because all three are declared
     * the same way and a translation that got one right by accident could have got the others wrong.
     */
    @Test
    @DisplayName("the three CVCRD01Y REDEFINES pairs are two views over one span each")
    void theRedefinesPairsRoundTripThroughBothAccessors() {
        CardScreenState work = new CardScreenState();
        work.initializeWorkArea();

        // The numeric view in, the alphanumeric view out: a PIC 9(11) store zero-fills on the left.
        work.setCcAcctIdN(50L);
        assertThat(work.getCcAcctId()).isEqualTo("00000000050");
        assertThat(work.getCcAcctIdN()).isEqualTo(50L);
        assertThat(work.isCcAcctIdNumeric()).isTrue();

        // The alphanumeric view in, the numeric view out - the same span read the other way.
        work.setCcAcctId("00000000099");
        assertThat(work.getCcAcctIdN()).isEqualTo(99L);
        assertThat(work.getCcAcctId()).hasSize(CardScreenState.CC_ACCT_ID_LENGTH);

        // :651-653's three disjuncts, each a different reading of the same eleven bytes.
        work.setCcAcctIdToLowValues();
        assertThat(work.isCcAcctIdLowValues()).isTrue();
        work.setCcAcctId(CardScreenState.spaces(CardScreenState.CC_ACCT_ID_LENGTH));
        assertThat(work.isCcAcctIdSpaces()).isTrue();
        work.setCcAcctIdN(0L);
        assertThat(work.isCcAcctIdNZeros()).isTrue();
        assertThat(work.isCcAcctIdSpaces())
                .describedAs("eleven typed zeros are neither LOW-VALUES nor SPACES, which is exactly why "
                        + ":653 needs a third disjunct")
                .isFalse();

        work.setCcCardNumN(500024453765740L);
        assertThat(work.getCcCardNum()).isEqualTo("0500024453765740");
        assertThat(work.getCcCardNumN()).isEqualTo(500024453765740L);
        work.setCcCustIdN(9L);
        assertThat(work.getCcCustId()).isEqualTo("000000009");
        assertThat(work.getCcCustIdN()).isEqualTo(9L);
    }

    /**
     * {@code YYYY-STORE-PFKEY} maps every AID the copybook enumerates, folds PF13-PF24 back, and leaves
     * an unmatched one alone.
     *
     * <p>{@code app/cpy/CSSTRPFY.cpy} is copied at {@code app/cbl/COCRDSLC.cbl:855} - one of only five
     * programs that copy it - and its {@code EVALUATE} has three properties that a translation loses
     * easily and that the twenty cases can only sample. All three are asserted here: {@code PF1} through
     * {@code PF12} map one-to-one onto {@code PFK01} through {@code PFK12}; {@code PF13} through
     * {@code PF24} fold <em>back</em> onto the same twelve tokens rather than extending the range, so
     * {@code DFHPF13} yields {@code PFK01} and is the identical enum constant {@code DFHPF1} yields; and
     * an AID with no branch - {@code DFHPA3} is the obvious one, since the copybook tests {@code PA1} and
     * {@code PA2} but not {@code PA3} - leaves {@code CCARD-AID} holding whatever it held, because there
     * is no {@code WHEN OTHER} and the field is not cleared first.
     */
    @Test
    @DisplayName("CSSTRPFY maps ENTER, CLEAR, PA1, PA2 and PF1-PF24, and leaves an unknown AID alone")
    void thePfKeyResolverReproducesTheCopybookEvaluate() {
        assertThat(PfKeyResolver.resolve(CicsAid.DFHENTER)).contains(AidKey.ENTER);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHCLEAR)).contains(AidKey.CLEAR);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPA1)).contains(AidKey.PA1);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPA2)).contains(AidKey.PA2);
        assertThat(AidKey.PA1.token())
                .describedAs("CVCRD01Y declares VALUE 'PA1  ' - two trailing spaces in a PIC X(5) item")
                .isEqualTo("PA1  ");

        byte[] functionKeys = {CicsAid.DFHPF1, CicsAid.DFHPF2, CicsAid.DFHPF3, CicsAid.DFHPF4,
                CicsAid.DFHPF5, CicsAid.DFHPF6, CicsAid.DFHPF7, CicsAid.DFHPF8, CicsAid.DFHPF9,
                CicsAid.DFHPF10, CicsAid.DFHPF11, CicsAid.DFHPF12};
        byte[] foldedKeys = {CicsAid.DFHPF13, CicsAid.DFHPF14, CicsAid.DFHPF15, CicsAid.DFHPF16,
                CicsAid.DFHPF17, CicsAid.DFHPF18, CicsAid.DFHPF19, CicsAid.DFHPF20, CicsAid.DFHPF21,
                CicsAid.DFHPF22, CicsAid.DFHPF23, CicsAid.DFHPF24};
        for (int index = 0; index < functionKeys.length; index++) {
            AidKey direct = PfKeyResolver.resolve(functionKeys[index]).orElseThrow();
            AidKey folded = PfKeyResolver.resolve(foldedKeys[index]).orElseThrow();
            assertThat(direct.token()).isEqualTo(String.format("PFK%02d", index + 1));
            assertThat(folded)
                    .describedAs("CSSTRPFY L%d folds PF%d back onto %s rather than extending the range",
                            54 + index * 2, index + 13, direct)
                    .isSameAs(direct);
        }

        assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3))
                .describedAs("CSSTRPFY tests PA1 and PA2 but never PA3, and it has no WHEN OTHER")
                .isEmpty();
        assertThat(PfKeyResolver.storePfKey(CicsAid.DFHPA3, Optional.of(AidKey.PFK03)))
                .describedAs("no match leaves CCARD-AID exactly as it was; it is never cleared")
                .contains(AidKey.PFK03);
        assertThat(PfKeyResolver.storePfKey(CicsAid.DFHPA3, Optional.empty()))
                .describedAs("with nothing recorded before it, no match still records nothing - which is "
                        + "what lets :297-299 coerce five spaces to ENTER")
                .isEmpty();
        assertThatThrownBy(() -> PfKeyResolver.storePfKey(CicsAid.DFHENTER, null))
                .describedAs("an absent token is Optional.empty(), never null")
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * Only {@code ENTER} and {@code PF3} act; every other key is coerced to {@code ENTER} and redisplays.
     *
     * <p>{@code app/cbl/COCRDSLC.cbl:291-299} sets {@code PFK-INVALID}, turns it valid only for
     * {@code CCARD-AID-ENTER} or {@code CCARD-AID-PFK03}, and then {@code SET CCARD-AID-ENTER TO TRUE}
     * for everything else. So an unusable key is never refused - it repaints the screen - and the two
     * that are usable must <em>not</em> be coerced. The twenty cases sample four keys between them; this
     * drives the whole set, which is what makes the coercion assertable rather than assumed.
     *
     * @param aid the raw {@code EIBAID} byte, as an unsigned integer
     */
    @ParameterizedTest(name = "EIBAID 0x{0}")
    @ValueSource(ints = {0x7D, 0x6D, 0x6C, 0x6E, 0x6B, 0xF1, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7,
            0xF8, 0xF9, 0x7A, 0x7B, 0x7C, 0xC1, 0x4A, 0x40})
    @DisplayName("only ENTER and PF3 act; every other AID is coerced to ENTER and repaints")
    void everyOtherAidIsCoercedToEnterAndRepaints(int aid) {
        ParityCase coldStart = cases().get(0);
        CardSelectResponse painted = paintWithAid(coldStart, (byte) aid);

        boolean actsOnItsOwn = aid == Byte.toUnsignedInt(CicsAid.DFHENTER)
                || aid == Byte.toUnsignedInt(CicsAid.DFHPF3);
        if (actsOnItsOwn && aid == Byte.toUnsignedInt(CicsAid.DFHPF3)) {
            // PF3 is the one key that must NOT be coerced: it transfers control at :331-334, so no map is
            // sent and the response names the next program instead of the next map.
            assertThat(tokenOrAbsent(painted.getNextMap())).isNull();
            assertThat(tokenOrAbsent(painted.getNextProgram())).isNotNull();
        } else {
            // ENTER, and every key :298 coerces to ENTER, repaint this screen.
            assertThat(tokenOrAbsent(painted.getNextMap()))
                    .describedAs("EIBAID 0x%02X must repaint CCRDSLA rather than be refused", aid)
                    .isEqualTo(CardSelectResponse.MAP_NAME);
            assertThat(painted.getPgmnameo()).isEqualTo(CardSelectResponse.THIS_PROGRAM);
            assertThat(painted.getTrnnameo()).isEqualTo(CardSelectResponse.THIS_TRANID);
        }
    }

    /**
     * Every {@code FileStatus} arm of the one read call site is reachable and lands where the source says.
     *
     * <p>Gate G47. {@code 9100-GETCARD-BYACCTCARD} at {@code :752-772} enumerates three arms -
     * {@code DFHRESP(NORMAL)}, {@code DFHRESP(NOTFND)} and {@code WHEN OTHER} - and each leaves a
     * different screen behind. Two are reachable from seeded data alone and the third is not, which is
     * why one case forces it; this test proves all three arms of the classification itself, so a
     * repository result that classified a response into the wrong arm fails here rather than as a
     * puzzling screen difference.
     */
    @Test
    @DisplayName("the CARDDAT read classifies NORMAL, NOTFND and WHEN OTHER as the source expects")
    void everyReadOutcomeOfTheOneCallSiteIsClassifiedCorrectly() {
        ParityCase firstCase = cases().get(0);
        SeededDataset seeded = ParityHarness.usAscii().seed(firstCase).get(CARDDAT);
        String seededRow = seeded.row(0);
        String primaryKey = seededRow.substring(CardRecord.CARD_NUM_OFFSET,
                CardRecord.CARD_NUM_OFFSET + CardRecord.CARD_NUM_LENGTH);
        String alternateKey = seededRow.substring(CardRecord.CARD_ACCT_ID_OFFSET,
                CardRecord.CARD_ACCT_ID_OFFSET + CardRecord.CARD_ACCT_ID_LENGTH);

        CardRepository.CardReadResult found = readByAccountKey(seeded, alternateKey);
        assertThat(found.isNormal()).isTrue();
        assertThat(found.resp()).isEqualTo(FileStatus.NORMAL);
        assertThat(found.requireRecord().cardNum()).isEqualTo(primaryKey);
        assertThat(found.requireStoredImage()).isEqualTo(seededRow);

        CardRepository.CardReadResult absent = readByAccountKey(seeded, "99999999999");
        assertThat(absent.isNotFound()).isTrue();
        assertThat(absent.resp()).isEqualTo(FileStatus.NOTFND);
        assertThat(absent.record()).isEmpty();

        CardRepository.CardReadResult failure =
                CardRepository.CardReadResult.reportedFailure(FileStatus.LENGERR, 3);
        assertThat(failure.isFailure()).isTrue();
        assertThat(failure.isNotFound())
                .describedAs("WHEN OTHER is a distinct arm from DFHRESP(NOTFND): :755-761 sets both "
                        + "filter flags NOT-OK while :762-771 guards only one of them")
                .isFalse();
        assertThat(failure.resp()).isEqualTo(FileStatus.LENGERR);
        assertThat(failure.resp2()).isEqualTo(3);
    }

    // =================================================================================================
    // Painting one case outside the harness, for the assertions above.
    // =================================================================================================

    /**
     * Runs one case's request through a freshly built controller and returns the payload.
     *
     * <p>Used by the direct assertions rather than by the gate: the gate goes through
     * {@link ParityHarness#judge}, which seeds, invokes and judges in one step. This is the same
     * construction and the same call with the differ left out.
     *
     * @param parityCase the case whose request to replay
     * @return the payload the handler returned
     */
    private static CardSelectResponse paint(ParityCase parityCase) {
        return paintWithAid(parityCase, null);
    }

    /**
     * Runs one case's request with an AID of the caller's choosing.
     *
     * @param parityCase the case whose request to replay
     * @param overrideAid the AID byte to send, or {@code null} to use the case's own
     * @return the payload the handler returned
     */
    private static CardSelectResponse paintWithAid(ParityCase parityCase, Byte overrideAid) {
        ParityHarness harness = ParityHarness.usAscii();
        List<CardSelectResponse> captured = new ArrayList<>(1);
        harness.run(parityCase, UnitKind.CONTROLLER_POJO, invocation -> {
            SeededDataset seeded = invocation.dataset(CARDDAT);
            CardSelectController controller = new CardSelectController(
                    seededCardRepository(invocation, seeded), invocation.clock(), FIXTURE_CHARSET);
            byte aid = overrideAid == null ? aidByteOf(invocation) : overrideAid;
            ScreenResponse<CardSelectResponse> body = controller.viewCardDetail(
                    pathCardNumberOf(invocation), requestOf(invocation), null,
                    invocation.eibcalen(), Byte.toUnsignedInt(aid)).getBody();
            captured.add(Objects.requireNonNull(body, "viewCardDetail always answers with a body")
                    .screen());
            UnitOutcome.Builder recorder = invocation.recorder();
            recorder.finalStateUnchanged(seeded, CardRecord.LAYOUT);
            recorder.returnCode(0);
            return recorder.build();
        });
        return captured.get(0);
    }

    // =================================================================================================
    // CARDDEMO-COMMAREA, both directions.
    // =================================================================================================

    /**
     * The {@code CARDDEMO-COMMAREA} as sixteen named images, and back again.
     *
     * <p>Both directions are needed and they are inverses: a case states the inbound area as images
     * keyed by {@code app/cpy/COCOM01Y.cpy}'s own field names, and the response's area is compared the
     * same way. Keeping the pairing in one place is what stops a field being read under one name and
     * written under another - the failure that would make a real difference invisible.
     *
     * <p>The widths are the copybook's, taken from {@code common.NavigationContext}'s own constants
     * rather than restated, and the two numeric items that a case writes as digits -
     * {@code CDEMO-CUST-ID PIC 9(09)}, {@code CDEMO-ACCT-ID PIC 9(11)}, {@code CDEMO-CARD-NUM PIC 9(16)}
     * and the single-digit {@code CDEMO-PGM-CONTEXT} - are zero-filled on the left as a numeric
     * {@code MOVE} does, never space-padded on the right.
     *
     * <p>A holder for two static methods and no state, which is why it is declared here rather than made
     * a type of its own: it is meaningless away from this test's two uses of it.
     */
    private static final class NavigationImage {

        /** Not instantiable: two static conversions and no state (practice B9). */
        private NavigationImage() {
            throw new AssertionError("NavigationImage is a holder for two conversions");
        }

        /**
         * The commarea a response carries, as sixteen images at their declared widths.
         *
         * @param context the commarea the response returned; never {@code null}
         * @return the images keyed by copybook field name, in copybook order
         */
        private static Map<String, String> of(NavigationContext context) {
            Map<String, String> image = new LinkedHashMap<>();
            image.put(NavigationContext.FROM_TRANID_FIELD, context.fromTranid());
            image.put(NavigationContext.FROM_PROGRAM_FIELD, context.fromProgram());
            image.put(NavigationContext.TO_TRANID_FIELD, context.toTranid());
            image.put(NavigationContext.TO_PROGRAM_FIELD, context.toProgram());
            image.put(NavigationContext.USER_ID_FIELD, context.userId());
            image.put(NavigationContext.USER_TYPE_FIELD, context.userType());
            image.put(NavigationContext.PGM_CONTEXT_FIELD,
                    digits(context.pgmContext(), NavigationContext.PGM_CONTEXT_LENGTH));
            image.put(NavigationContext.CUST_ID_FIELD,
                    digits(context.custId(), NavigationContext.CUST_ID_LENGTH));
            image.put(NavigationContext.CUST_FNAME_FIELD, context.custFname());
            image.put(NavigationContext.CUST_MNAME_FIELD, context.custMname());
            image.put(NavigationContext.CUST_LNAME_FIELD, context.custLname());
            image.put(NavigationContext.ACCT_ID_FIELD,
                    digits(context.acctId(), NavigationContext.ACCT_ID_LENGTH));
            image.put(NavigationContext.ACCT_STATUS_FIELD, context.acctStatus());
            image.put(NavigationContext.CARD_NUM_FIELD,
                    digits(context.cardNum(), NavigationContext.CARD_NUM_LENGTH));
            image.put(NavigationContext.LAST_MAP_FIELD, context.lastMap());
            image.put(NavigationContext.LAST_MAPSET_FIELD, context.lastMapset());
            return image;
        }

        /**
         * The commarea a case declares, rebuilt into the record the handler is given.
         *
         * <p>A field the case omits takes {@link NavigationContext#empty()}'s value for it, which is
         * spaces for an alphanumeric item and zero for a numeric one - exactly what COBOL's
         * {@code INITIALIZE} leaves. So a case states the fields its path depends on and says nothing
         * about the rest, rather than restating sixteen values to change one.
         *
         * @param declared the case's {@code commarea} member
         * @return the commarea record
         */
        private static NavigationContext from(Map<String, String> declared) {
            NavigationContext empty = NavigationContext.empty();
            return new NavigationContext(
                    text(declared, NavigationContext.FROM_TRANID_FIELD, empty.fromTranid()),
                    text(declared, NavigationContext.FROM_PROGRAM_FIELD, empty.fromProgram()),
                    text(declared, NavigationContext.TO_TRANID_FIELD, empty.toTranid()),
                    text(declared, NavigationContext.TO_PROGRAM_FIELD, empty.toProgram()),
                    text(declared, NavigationContext.USER_ID_FIELD, empty.userId()),
                    text(declared, NavigationContext.USER_TYPE_FIELD, empty.userType()),
                    (int) number(declared, NavigationContext.PGM_CONTEXT_FIELD,
                            empty.pgmContext()),
                    (int) number(declared, NavigationContext.CUST_ID_FIELD, empty.custId()),
                    text(declared, NavigationContext.CUST_FNAME_FIELD, empty.custFname()),
                    text(declared, NavigationContext.CUST_MNAME_FIELD, empty.custMname()),
                    text(declared, NavigationContext.CUST_LNAME_FIELD, empty.custLname()),
                    number(declared, NavigationContext.ACCT_ID_FIELD, empty.acctId()),
                    text(declared, NavigationContext.ACCT_STATUS_FIELD, empty.acctStatus()),
                    number(declared, NavigationContext.CARD_NUM_FIELD, empty.cardNum()),
                    text(declared, NavigationContext.LAST_MAP_FIELD, empty.lastMap()),
                    text(declared, NavigationContext.LAST_MAPSET_FIELD, empty.lastMapset()));
        }

        /**
         * One alphanumeric field, or the initialised value when the case states none.
         *
         * @param declared    the case's commarea
         * @param field       the copybook field name
         * @param initialised what {@code INITIALIZE} would leave
         * @return the value
         */
        private static String text(Map<String, String> declared, String field, String initialised) {
            String stated = declared.get(field);
            return stated == null ? initialised : stated;
        }

        /**
         * One numeric field, or the initialised value when the case states none.
         *
         * @param declared    the case's commarea
         * @param field       the copybook field name
         * @param initialised what {@code INITIALIZE} would leave
         * @return the value
         */
        private static long number(Map<String, String> declared, String field, long initialised) {
            String stated = declared.get(field);
            if (stated == null || stated.isBlank()) {
                return initialised;
            }
            return Long.parseLong(stated.trim());
        }

        /**
         * A numeric field as a {@code PIC 9(n)} image: zero-filled on the left, never space-padded.
         *
         * @param value the value
         * @param width the declared number of digits
         * @return exactly {@code width} digits
         */
        private static String digits(long value, int width) {
            String rendered = Long.toString(value);
            if (rendered.length() >= width) {
                return rendered.substring(rendered.length() - width);
            }
            return "0".repeat(width - rendered.length()) + rendered;
        }
    }
}
