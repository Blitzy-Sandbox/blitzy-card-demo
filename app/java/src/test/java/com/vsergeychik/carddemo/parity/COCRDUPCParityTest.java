package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vsergeychik.carddemo.card.CardRepository;
import com.vsergeychik.carddemo.card.CardUpdateController;
import com.vsergeychik.carddemo.card.CardUpdateService;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CardDetails;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.DetailGroup;
import com.vsergeychik.carddemo.card.dto.CardUpdateResponse;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.config.DatasetUnitOfWork;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedResponse;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedSend;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetChannel;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetInput;
import com.vsergeychik.carddemo.parity.ParityCase.EmittedMessage;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedDataset;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedRecord;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedResponse;
import com.vsergeychik.carddemo.parity.ParityCase.ForcedOutcome;
import com.vsergeychik.carddemo.parity.ParityCase.MessageChannel;
import com.vsergeychik.carddemo.parity.ParityCase.RepositoryOperation;
import com.vsergeychik.carddemo.parity.ParityCase.ScreenRequest;
import com.vsergeychik.carddemo.parity.ParityCase.ScreenSend;
import com.vsergeychik.carddemo.parity.ParityCase.Termination;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.parity.ParityHarness.Invocation;
import com.vsergeychik.carddemo.parity.ParityHarness.SeededDataset;
import com.vsergeychik.carddemo.parity.ParityHarness.UnitOutcome;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

/**
 * The behavioural-parity gate for {@code COCRDUPC} - the credit-card update screen, CSD transaction
 * {@code CCUP}, projected as {@code PUT /api/cards/{cardNum}}.
 *
 * <p>Twenty declarative cases, {@code case01} through {@code case20}, each executed against the
 * translated unit and judged field by field by {@link FieldDiffer}. <strong>The module is not complete
 * until the diff count is zero across all twenty.</strong> That gate is per module rather than per
 * build: nineteen clean cases and one difference means this module is incomplete, not ninety-five per
 * cent done. The count itself is enforced before a single case runs - see {@link #cases()} - because a
 * lost case would make the gate <em>quieter</em> rather than louder, and an assertion nobody runs
 * cannot fail.
 *
 * <h2>Which unit each case drives</h2>
 *
 * <p>Stated per case rather than left to be inferred, because the two units answer different
 * questions:
 *
 * <ul>
 *   <li><strong>{@code case01} - {@code case14} drive {@link CardUpdateService}</strong> as
 *       {@link UnitKind#SERVICE}, constructed through its own two-argument constructor over a
 *       fixture-backed {@link CardRepository} and a real {@link DatasetUnitOfWork}. This is where
 *       {@code 9200-WRITE-PROCESSING} and the optimistic-concurrency check of
 *       {@code 9300-CHECK-CHANGE-IN-REC} live, so it is where the headline gate is asserted.</li>
 *   <li><strong>{@code case15} - {@code case20} drive {@link CardUpdateController}</strong> as
 *       {@link UnitKind#CONTROLLER_POJO}, constructed through its four-argument constructor and called
 *       directly on {@code updateCardDetail}. These are the screen-shaped paths: the {@code XCTL}
 *       hand-off, the {@code ENTER} versus {@code REENTER} split, the error-highlight matrix and the
 *       seventeen-field payload.</li>
 * </ul>
 *
 * <p>Neither unit is reached through a framework. There is no {@code MockMvc}, no
 * {@code TestRestTemplate}, no {@code WebTestClient}, no {@code JobLauncher} and no Spring context
 * anywhere in this class: every collaborator is passed to a constructor by hand. That is what makes the
 * arithmetic and the branch structure reachable without an HTTP layer in the path, which is the
 * condition the coverage bar depends on.
 *
 * <h2>Rules governing this file</h2>
 *
 * <p><strong>{@code review_rules} returns exactly one line: "No user rules provided."</strong> That
 * single line is the whole document - there is nothing further to page through - and it was
 * re-confirmed immediately before this class was written. <em>No project rule forces this file into
 * scope</em>; the migration plan does, in its test-additions inventory.
 *
 * <p>Their absence is not licence to lower the bar, so the plan's enterprise best practices
 * <strong>B1-B12</strong> bind in their place. The ones that shaped this class are named at the point
 * they apply rather than listed abstractly here: <strong>B1</strong> and <strong>B2</strong> on the
 * closed dependency set - JUnit 5, AssertJ, Mockito and H2 at test scope and nothing else, no Lombok,
 * no Testcontainers, no Spring Security; <strong>B3</strong> on the reference inputs, not one byte of
 * which is written; <strong>B4</strong> on documenting a divergence instead of repairing it;
 * <strong>B5</strong> on relaxing no validation and correcting no calculation; <strong>B7</strong> on
 * determinism; <strong>B8</strong> on explicitness; <strong>B9</strong> on the absence of static
 * mutable state; <strong>B10</strong> on tests shipping with the implementation; <strong>B11</strong>
 * on hand-written codecs rather than an opaque copybook parser; and <strong>B12</strong> on the
 * baseline's provenance.
 *
 * <h2>The baseline is statically derived. It was never captured from a running program.</h2>
 *
 * <p>Every expected value below was produced by structured reading of {@code app/cbl/COCRDUPC.cbl},
 * cross-checked against five authoritative sources: the symbolic map {@code app/cpy-bms/COCRDUP.CPY}
 * for the seventeen payload field names and widths, the mapset {@code app/bms/COCRDUP.bms} for the
 * {@code DFHMDF} definitions behind them, the copybooks {@code app/cpy/CVACT02Y.cpy},
 * {@code app/cpy/CVCRD01Y.cpy}, {@code app/cpy/COCOM01Y.cpy} and {@code app/cpy/CSSTRPFY.cpy} for the
 * record and work-area layouts, {@code app/csd/CARDDEMO.CSD} for the file and transaction definitions,
 * and the real fixture {@code app/data/ASCII/carddata.txt} for input data. Widths and offsets are taken
 * mechanically from the copybooks rather than from prose, and every case seeds genuine fixture rows
 * rather than invented ones.
 *
 * <p>They are <strong>not</strong> recorded from, replayed from, or diffed against any execution of the
 * legacy COBOL, and nothing here should be read as though they were. Executing it is empirically
 * impossible in this environment: there is no z/OS and no CICS runtime; the available COBOL compiler
 * reports its indexed file handler as disabled, which excludes every program using
 * {@code ORGANIZATION INDEXED}; no Language Environment {@code CEE*} services exist; and the
 * IBM-supplied {@code DFHBMSCA} and {@code DFHAID} that this program copies at {@code :327-328} are
 * absent from the repository altogether - which is why {@link BmsAttributes} and {@link CicsAid}
 * reproduce their constants from IBM CICS documentation rather than from source here. That gap is
 * recorded rather than papered over (practice <strong>B4</strong>, plan risk <strong>R-D</strong>), and
 * it is why {@link #pfKeysResolveExactlyAsCsstrpfyMapsThem()} and
 * {@link #theExpiryDayIsAlwaysDark()} pin the specific constant values this program depends on instead
 * of trusting them.
 *
 * <p>This substitutes the <em>provenance</em> of the expected values and nothing else. Twenty cases,
 * field-for-field diffing, the diff-count-equals-zero gate and the branch-coverage bar are all
 * preserved unchanged. Because it nonetheless modifies a stated success criterion it is escalated for
 * explicit user confirmation rather than quietly absorbed (practice <strong>B12</strong>, plan risk
 * <strong>R-A</strong>). The residual exposure is worth stating plainly: a statically derived
 * expectation can encode a misreading of the COBOL where a captured one could not.
 *
 * <h2>The optimistic-concurrency check already exists in the COBOL. No version column is introduced.</h2>
 *
 * <p>{@code 9300-CHECK-CHANGE-IN-REC} at {@code app/cbl/COCRDUPC.cbl:1498-1523} re-reads the record
 * under the lock and compares it <em>field for field</em> against the snapshot the screen was painted
 * from, before rewriting. It is one of only two such paragraphs in the whole application - the other is
 * in {@code COACTUPC} - and it is <strong>not</strong> something this migration added. Five of the
 * twenty cases are pointed at it directly: {@code case01} no change detected, {@code case02} one field
 * changed underneath, {@code case03} several fields changed, {@code case04} a change on the byte
 * immediately before the {@code FILLER} span, and {@code case05} a successful rewrite after a clean
 * check.
 *
 * <p>Replacing that comparison with a version column would be a schema change, which the plan forbids
 * outright: no DDL, no entity annotations and no generated table definitions anywhere in the module.
 * {@link #noVersionColumnOrSchemaArtefactIsIntroduced()} asserts the absence rather than trusting it.
 *
 * <h2>Three source behaviours that look like defects and are preserved anyway</h2>
 *
 * <p>Practice <strong>B5</strong> is binding here: relax no validation, correct no calculation, assert
 * the source as written.
 *
 * <ul>
 *   <li><strong>The rewrite always zeroes the CVV.</strong> {@code CCUP-NEW-CVV-CD} is declared at
 *       {@code :306} and assigned <em>nowhere</em> in the program - there is no CVV field on the
 *       {@code COCRDUP} map to receive it - so at {@code :1464} it still holds the spaces that
 *       {@code INITIALIZE CCUP-NEW-DETAILS} at {@code :586} left there. Those spaces are moved into
 *       {@code CARD-CVV-CD-X PIC X(03)} and read straight back out through its
 *       {@code CARD-CVV-CD-N PIC 9(03)} redefinition, which yields zero. Every successful rewrite
 *       therefore replaces the stored CVV with {@code 000}. {@code case14} pins it and
 *       {@link #theRewriteAlwaysZeroesTheCvvBecauseCcupNewCvvCdIsNeverAssigned()} explains it.</li>
 *   <li><strong>The concurrency check ignores both key fields.</strong> The six-way comparison at
 *       {@code :1503-1508} covers the CVV, the embossed name, the three expiry components and the
 *       active status. {@code CARD-NUM} and {@code CARD-ACCT-ID} are absent from it.
 *       {@link #theConcurrencyCheckIgnoresBothKeyFields()} asserts that absence, because a translation
 *       that helpfully added them would reject updates the COBOL accepts.</li>
 *   <li><strong>An unrecognised attention identifier is remapped to {@code ENTER}, not rejected.</strong>
 *       {@code :413-424} sets {@code PFK-INVALID}, admits only {@code ENTER}, {@code PF03},
 *       {@code PF05} while changes are validated-but-unconfirmed, and {@code PF12} once details have
 *       been fetched - then quietly does {@code SET CCARD-AID-ENTER TO TRUE} for everything else.
 *       {@code case19} presses {@code PF9} and expects, byte for byte, the screen {@code case18} gets
 *       from {@code ENTER}.</li>
 * </ul>
 *
 * <h2>{@code CARDAIX} is a second finder, never a second table</h2>
 *
 * <p>{@code app/csd/CARDDEMO.CSD} defines {@code CARDDAT} over the base cluster and {@code CARDAIX} as
 * an alternate-index <em>path</em> over that same cluster, and {@code :251-254} names both. One
 * {@link CardRepository} therefore carries both access paths, and
 * {@link #theAlternateIndexIsASecondFinderOnTheSameRepository()} drives the alternate key on the very
 * object the base read is served from, which is the property the gate is about.
 */
@DisplayName("COCRDUPC parity - the credit-card update screen, transaction CCUP")
class COCRDUPCParityTest {

    // =================================================================================================
    // Identity. The program name is this class's stem and the parity resource directory's stem.
    // =================================================================================================

    /** The COBOL program this class gates, spelled as {@code app/cbl/} and the resource root spell it. */
    private static final String PROGRAM = "COCRDUPC";

    /** The card file's binding key, taken from the repository rather than re-spelled (gate G46). */
    private static final String CARDDAT = CardRepository.BASE_DD_NAME;

    /**
     * The instant every case pins, from this source's own version footer:
     * {@code Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:12:33 CDT} at
     * {@code app/cbl/COCRDUPC.cbl:1559}. A fixed clock rather than a system one, so
     * {@code CURDATEO} and {@code CURTIMEO} are assertable at all (practice <strong>B7</strong>).
     */
    private static final String PINNED_CLOCK = "2022-07-19T23:12:33";

    /** The fixtures' code page, named rather than defaulted (practice <strong>B8</strong>). */
    private static final Charset FIXTURE_CHARSET = StandardCharsets.US_ASCII;

    /** The same code page as a case declares it. */
    private static final String CHARSET_NAME = "US-ASCII";

    /**
     * {@code EIBCALEN} on a cold start - the {@code :388} guard's left arm. CICS passes no commarea, so
     * {@code INITIALIZE CARDDEMO-COMMAREA WS-THIS-PROGCOMMAREA} runs at {@code :391-392}.
     */
    private static final int EIBCALEN_NONE = 0;

    /**
     * {@code EIBCALEN} on a pseudo-conversational re-entry: {@code 160 + 329}, being
     * {@code CARDDEMO-COMMAREA} from {@code app/cpy/COCOM01Y.cpy} followed by
     * {@code WS-THIS-PROGCOMMAREA} at {@code :274-321}, which is how {@code :396-400} slices what came
     * back.
     */
    private static final int EIBCALEN_FULL =
            NavigationContext.COMMAREA_LENGTH + CardUpdateRequest.CommArea.RECORD_LENGTH;

    /** {@code LIT-THISPGM PIC X(8) VALUE 'COCRDUPC'} at {@code :219-220}. */
    private static final String THIS_PGM = "COCRDUPC";

    /** {@code LIT-THISTRANID PIC X(4) VALUE 'CCUP'} at {@code :221-222}. */
    private static final String THIS_TRANID = "CCUP";

    /** {@code LIT-THISMAPSET PIC X(8) VALUE 'COCRDUP '} at {@code :223-224}, trailing blank trimmed. */
    private static final String THIS_MAPSET = "COCRDUP";

    /** {@code LIT-THISMAP PIC X(7) VALUE 'CCRDUPA'} at {@code :225-226}. */
    private static final String THIS_MAP = "CCRDUPA";

    /** {@code LIT-CCLISTPGM PIC X(8) VALUE 'COCRDLIC'} at {@code :227-228} - the card list. */
    private static final String CARD_LIST_PGM = "COCRDLIC";

    /** {@code LIT-CCLISTTRANID PIC X(4) VALUE 'CCLI'} at {@code :229-230}. */
    private static final String CARD_LIST_TRANID = "CCLI";

    /** {@code LIT-MENUPGM PIC X(8) VALUE 'COMEN01C'} at {@code :235-236} - the main menu. */
    private static final String MENU_PGM = "COMEN01C";

    /** {@code LIT-MENUTRANID PIC X(4) VALUE 'CM00'} at {@code :237-238}. */
    private static final String MENU_TRANID = "CM00";

    // =================================================================================================
    // The seed. Three genuine rows of app/data/ASCII/carddata.txt, at the copybook's own 150 bytes.
    // =================================================================================================

    /**
     * Row 1 of {@code app/data/ASCII/carddata.txt}, composed from the {@code CVACT02Y} spans rather than
     * pasted as one string, so every offset in it is visible and checkable against the copybook.
     *
     * <p>Its embossed name is <strong>mixed case</strong> - {@code "Aniya Von"} - and that is not
     * incidental. {@code 9300-CHECK-CHANGE-IN-REC} folds the re-read name to upper case at
     * {@code :1499-1501} <em>before</em> comparing, so this one row makes the fold observable:
     * {@code case01} compares against the folded form and finds no change, {@code case06} compares
     * against the raw form and finds one.
     */
    private static final String ROW_01 = row("0500024453765740", "00000000050", "747", "Aniya Von",
            "2023-03-09", "Y");

    /** Row 2 of the fixture: account {@code 00000000027}, CVV {@code 567}, expiring 2025-07-13. */
    private static final String ROW_02 = row("0683586198171516", "00000000027", "567", "Ward Jones",
            "2025-07-13", "Y");

    /** Row 3 of the fixture: account {@code 00000000002}, CVV {@code 028}, expiring 2024-08-11. */
    private static final String ROW_03 = row("0923877193247330", "00000000002", "028",
            "Enrico Rosenbaum", "2024-08-11", "Y");

    /** The seeded {@code CARDDAT} slice every case starts from, in fixture order. */
    private static final List<String> SEED_ROWS = List.of(ROW_01, ROW_02, ROW_03);

    /** {@code CARD-NUM} of {@link #ROW_01}, the key most cases address. */
    private static final String KEY_01 = "0500024453765740";

    /** {@code CARD-ACCT-ID} of {@link #ROW_01}, at its declared {@code PIC 9(11)} width. */
    private static final String ACCT_01 = "00000000050";

    /** {@code CARD-NUM} of {@link #ROW_03}. */
    private static final String KEY_03 = "0923877193247330";

    /** {@code CARD-ACCT-ID} of {@link #ROW_03}. */
    private static final String ACCT_03 = "00000000002";

    /**
     * The seventeen payload fields of the {@code COCRDUP} screen with the widths
     * {@code app/cpy-bms/COCRDUP.CPY} declares for them, keyed by the field stem.
     *
     * <p>Taken from the {@code xxxI PIC X(n)} items and from nothing else. The {@code xxxL COMP PIC
     * S9(4)} length items, the {@code xxxF PICTURE X} flag bytes and the {@code xxxA} attribute
     * redefinitions of those bytes are validation and highlight <em>metadata</em>; they are not payload
     * fields and they are deliberately absent from this map. {@code Map.of} is unmodifiable, so this is
     * a constant rather than shared mutable state (practice <strong>B9</strong>).
     */
    private static final Map<String, Integer> MAP_FIELD_WIDTHS = Map.ofEntries(
            Map.entry("TRNNAME", 4), Map.entry("TITLE01", 40), Map.entry("CURDATE", 8),
            Map.entry("PGMNAME", 8), Map.entry("TITLE02", 40), Map.entry("CURTIME", 8),
            Map.entry("ACCTSID", 11), Map.entry("CARDSID", 16), Map.entry("CRDNAME", 50),
            Map.entry("CRDSTCD", 1), Map.entry("EXPMON", 2), Map.entry("EXPYEAR", 4),
            Map.entry("EXPDAY", 2), Map.entry("INFOMSG", 40), Map.entry("ERRMSG", 80),
            Map.entry("FKEYS", 21), Map.entry("FKEYSC", 18));

    /**
     * The seventeen field stems in symbolic-map declaration order, which is the order
     * {@code app/cpy-bms/COCRDUP.CPY:17-120} lists them and the order every send is rendered in.
     */
    private static final List<String> MAP_FIELDS = List.of("TRNNAME", "TITLE01", "CURDATE", "PGMNAME",
            "TITLE02", "CURTIME", "ACCTSID", "CARDSID", "CRDNAME", "CRDSTCD", "EXPMON", "EXPYEAR",
            "EXPDAY", "INFOMSG", "ERRMSG", "FKEYS", "FKEYSC");

    // =================================================================================================
    // Message literals, transcribed from the WS-RETURN-MSG and WS-INFO-MSG 88-levels.
    // =================================================================================================

    /** {@code 88 WS-PROMPT-FOR-ACCT} at {@code :177-178}. */
    private static final String MSG_ACCT_NOT_PROVIDED = "Account number not provided";

    /**
     * {@code 88 NO-SEARCH-CRITERIA-RECEIVED} at {@code :185-186}.
     *
     * <p>Set by {@code 1200-EDIT-MAP-INPUTS:655-658} - {@code IF FLG-ACCTFILTER-BLANK AND
     * FLG-CARDFILTER-BLANK} - and it outranks both single-field prompts because it is assigned after
     * them, unguarded by {@code WS-RETURN-MSG-OFF}.
     */
    private static final String MSG_NO_SEARCH_CRITERIA = "No input received";

    /** {@code 88 SEARCHED-ACCT-NOT-NUMERIC}'s literal as {@code 1210-EDIT-ACCOUNT:745} spells it. */
    private static final String MSG_ACCT_NOT_NUMERIC =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /** {@code 88 CARD-EXPIRY-MONTH-NOT-VALID} at {@code :197-198} - the message {@code case09} carries in. */
    private static final String MSG_EXPIRY_MONTH_NOT_VALID =
            "Card expiry month must be between 1 and 12";

    /** {@code 88 PROMPT-FOR-SEARCH-KEYS} at {@code :162-163}, a {@code WS-INFO-MSG PIC X(40)} value. */
    private static final String INFO_PROMPT_FOR_KEYS = "Please enter Account and Card Number";

    /**
     * The {@code unitStimulus.linkage} name a {@code SERVICE} case states {@code WS-RETURN-MSG} under.
     *
     * <p>{@code WS-RETURN-MSG PIC X(75)} is an input to {@code 9200-WRITE-PROCESSING} rather than a
     * screen field, so it has no {@code xxxI} item to travel in and no place in the commarea. Declaring
     * it as a linkage value is what keeps it visible in the case file: on every path that reaches 9200
     * through {@code 0000-MAIN} it is {@code SPACES}, cleared at {@code :384}, and a case that arranged
     * anything else would be arranging it in the open.
     */
    private static final String LINKAGE_RETURN_MSG = "WS-RETURN-MSG";

    /**
     * The commarea key carrying the 329-byte {@code WS-THIS-PROGCOMMAREA} image.
     *
     * <p>{@code :404-406} moves {@code DFHCOMMAREA} beyond the 160-byte {@code CARDDEMO-COMMAREA} into
     * this area whole, so a case that declares a warm turn declares the image, and the adapter decodes it
     * at the offsets {@code app/cbl/COCRDUPC.cbl:206-241} lays out - through the production
     * {@link CardUpdateRequest.CommArea#decode} rather than a second reading of the same copybook.
     */
    private static final String PROGRAM_AREA_KEY = "WS-THIS-PROGCOMMAREA";

    // =================================================================================================
    // The gate.
    // =================================================================================================

    /**
     * The twenty cases, loaded from {@code src/test/resources/parity/COCRDUPC/} and from nowhere else.
     *
     * <p>The resource directory is the only source. That is not a stylistic preference: a suite that
     * built its cases in Java left the twenty shipped files unread, so they asserted nothing while
     * reading in review as though they were the gate - and five of them had been added, one per commit,
     * to a suite that could not run them. {@link ParityHarness#casesOf} refuses anything but exactly
     * {@code case01.json} through {@code case20.json}, so a file added to the directory is executed the
     * moment it exists and a file missing from it fails the class before a single case runs.
     *
     * <p>The count and the numbering are re-checked here as well, because a set that lost an entry would
     * otherwise make this gate <em>quieter</em>: nineteen passing cases and no failure at all is the
     * worst possible outcome for an acceptance check.
     *
     * @return the twenty cases this program owns, in case order; never {@code null}
     */
    private static List<ParityCase> cases() {
        List<ParityCase> loaded = ParityHarness.casesOf(PROGRAM);
        if (loaded.size() != ParityHarness.CASES_PER_PROGRAM) {
            throw new IllegalStateException("The parity gate for " + PROGRAM + " requires exactly "
                    + ParityHarness.CASES_PER_PROGRAM + " cases under src/test/resources/parity/"
                    + PROGRAM + "/, named case01 through case" + ParityHarness.CASES_PER_PROGRAM
                    + ", but " + loaded.size() + " loaded. A short set is not a smaller gate, it is a "
                    + "missing one: an assertion nobody runs cannot fail.");
        }
        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            String required = ParityHarness.caseId(ordinal);
            String declared = loaded.get(ordinal - 1).caseId();
            if (!required.equals(declared)) {
                throw new IllegalStateException("Parity case " + ordinal + " for " + PROGRAM
                        + " is \"" + declared + "\" where the gate requires \"" + required
                        + "\". The identifiers are the file-name stems and they are ordered, so a "
                        + "mis-numbered file would silently take another case's place.");
            }
        }
        return loaded;
    }

    /**
     * Runs one case against the unit it names and requires the differ to find nothing.
     *
     * <p>The assertion is on {@link DiffResult#count()} with the whole of {@link DiffResult#render()}
     * attached as the description, so a failure prints every difference - each naming its dataset or
     * channel, the COBOL field, that field's declared offset and length, the expected image and the
     * observed one - instead of a bare "expected 0 but was 3". A difference is only actionable next to
     * the field it belongs to.
     *
     * <p>The unit kind comes from the case, because the case is what declares it. This program owns two
     * units and each case names which one it is about, so {@link #unit} dispatches on that declaration
     * rather than on the case identifier: an identifier names a case, it never configures one.
     *
     * @param parityCase the case to run, supplied by {@link #cases()}
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("COCRDUPC: every case diffs to zero against the COBOL-derived baseline")
    void theTranslationMatchesTheCobolFieldForField(ParityCase parityCase) {
        DiffResult result = ParityHarness.usAscii()
                .judge(parityCase, parityCase.unitKind(), COCRDUPCParityTest::unit);

        assertThat(result.count())
                .describedAs("%s/%s (%s) must diff to zero. %s", PROGRAM, parityCase.caseId(),
                        parityCase.unitKind(), result.render())
                .isZero();
    }

    /**
     * Reaches the unit the case declares.
     *
     * <p>Two kinds and no default that guesses. A case declaring {@code BATCH_JOB} or {@code COMPONENT}
     * would be a case file describing a program this is not, and running it through either adapter would
     * report a plausible failure against the wrong unit.
     *
     * @param invocation the seeded datasets, the pinned clock, the declared stimulus and the recorder
     * @return the recorded outcome; never {@code null}
     */
    private static UnitOutcome unit(Invocation invocation) {
        return switch (invocation.unitKind()) {
            case CONTROLLER_POJO -> controllerUnit(invocation);
            case SERVICE -> serviceUnit(invocation);
            case BATCH_JOB, COMPONENT -> throw new IllegalStateException("Case " + PROGRAM + '/'
                    + invocation.caseId() + " declares unitKind " + invocation.unitKind()
                    + ", which COCRDUPC has no unit for: it is a CICS online program, so its units are "
                    + "CardUpdateController (CONTROLLER_POJO) and CardUpdateService (SERVICE).");
        };
    }

    // =================================================================================================
    // Structural gates. Each asserts a property the twenty cases rest on, so that a broken foundation
    // fails by name here instead of surfacing as twenty confusing diffs.
    // =================================================================================================

    /**
     * This class's stem, the program name and the harness's resource convention agree.
     *
     * <p>The twenty cases are loaded from {@code src/test/resources/parity/COCRDUPC/} and the convention
     * is asserted here because the identifiers a case carries <em>are</em> that directory's file-name
     * stems: {@link ParityHarness#caseResourcePath} composes the path the loader reads from, so if this
     * class's stem, the program name and that composition ever disagreed, the suite would be loading one
     * program's cases under another program's name.
     */
    @Test
    @DisplayName("the class stem, the program name and the parity/COCRDUPC convention agree")
    void theResourceConventionIsHonoured() {
        assertThat(COCRDUPCParityTest.class.getSimpleName()).isEqualTo(PROGRAM + "ParityTest");
        assertThat(ParityHarness.caseResourcePath(PROGRAM, ParityHarness.caseId(1)))
                .isEqualTo(ParityHarness.CASE_RESOURCE_ROOT + PROGRAM + "/case01"
                        + ParityHarness.CASE_RESOURCE_EXTENSION);
        assertThat(ParityHarness.caseResourcePath(PROGRAM, ParityHarness.caseId(20)))
                .isEqualTo("parity/COCRDUPC/case20.json");
        assertThat(ParityHarness.CASES_PER_PROGRAM).isEqualTo(20);
        assertThat(cases()).hasSize(ParityHarness.CASES_PER_PROGRAM);
    }

    /**
     * The seventeen payload field widths are the symbolic map's own.
     *
     * <p>Asserted against {@link CardUpdateResponse}'s declarations so that the map, the DTO and this
     * gate cannot disagree silently. Seventeen is the whole payload: {@code app/bms/COCRDUP.bms}
     * declares seventeen {@code DFHMDF} fields and {@code app/cpy-bms/COCRDUP.CPY} exposes seventeen
     * {@code xxxI} items, one per field, with no eighteenth on either side.
     */
    @Test
    @DisplayName("the seventeen payload fields carry the widths COCRDUP.CPY declares")
    void theSeventeenPayloadFieldsAreTheSymbolicMapsOwn() {
        assertThat(MAP_FIELD_WIDTHS).hasSize(17);
        assertThat(MAP_FIELDS).hasSize(17).containsExactlyInAnyOrderElementsOf(
                MAP_FIELD_WIDTHS.keySet());
        assertThat(CardUpdateResponse.MAPSET_NAME).isEqualTo(THIS_MAPSET);
        assertThat(CardUpdateResponse.MAP_NAME).isEqualTo(THIS_MAP);
        assertThat(CardUpdateResponse.TRANSACTION_ID).isEqualTo(THIS_TRANID);
        assertThat(CardUpdateResponse.PROGRAM_NAME).isEqualTo(THIS_PGM);
        assertThat(CardUpdateResponse.INPUT_GROUP_NAME).isEqualTo("CCRDUPAI");
        assertThat(CardUpdateResponse.OUTPUT_GROUP_NAME).isEqualTo("CCRDUPAO");

        CardUpdateResponse painted = new CardUpdateResponse();
        assertThat(painted.fieldImages().keySet())
                .as("every payload field is exposed as its xxxO output item, and only those")
                .containsExactlyInAnyOrderElementsOf(
                        MAP_FIELDS.stream().map(stem -> stem + "O").toList());
    }

    /**
     * {@code CARD-RECORD} occupies exactly 150 bytes and its {@code FILLER X(59)} is one of them.
     *
     * <p>Offsets are asserted rather than lengths alone, because a layout can carry the right widths in
     * the wrong order and still total 150. Omitting the {@code FILLER} would move nothing and break
     * everything: the record would come out 91 bytes wide and every downstream offset with it, which is
     * why {@code case05} asserts the whole 150-byte image and not merely its named fields.
     */
    @Test
    @DisplayName("CARD-RECORD is 150 bytes, FILLER X(59) included and space-filled")
    void theCardRecordIsOneHundredAndFiftyBytesIncludingItsFiller() {
        assertThat(CardRecord.RECORD_LENGTH).isEqualTo(150);
        assertThat(CardRecord.CARD_NUM_OFFSET).isZero();
        assertThat(CardRecord.CARD_NUM_LENGTH).isEqualTo(16);
        assertThat(CardRecord.CARD_ACCT_ID_OFFSET).isEqualTo(16);
        assertThat(CardRecord.CARD_ACCT_ID_LENGTH).isEqualTo(11);
        assertThat(CardRecord.CARD_CVV_CD_OFFSET).isEqualTo(27);
        assertThat(CardRecord.CARD_CVV_CD_LENGTH).isEqualTo(3);
        assertThat(CardRecord.CARD_EMBOSSED_NAME_OFFSET).isEqualTo(30);
        assertThat(CardRecord.CARD_EMBOSSED_NAME_LENGTH).isEqualTo(50);
        assertThat(CardRecord.CARD_EXPIRAION_DATE_OFFSET).isEqualTo(80);
        assertThat(CardRecord.CARD_EXPIRAION_DATE_LENGTH).isEqualTo(10);
        assertThat(CardRecord.CARD_ACTIVE_STATUS_OFFSET).isEqualTo(90);
        assertThat(CardRecord.CARD_ACTIVE_STATUS_LENGTH).isEqualTo(1);
        assertThat(CardRecord.FILLER_OFFSET).isEqualTo(91);
        assertThat(CardRecord.FILLER_LENGTH).isEqualTo(59);
        assertThat(CardRecord.FILLER_OFFSET + CardRecord.FILLER_LENGTH)
                .isEqualTo(CardRecord.RECORD_LENGTH);
        assertThat(CardRecord.LAYOUT.recordLength()).isEqualTo(150);

        assertThat(SEED_ROWS).allSatisfy(image -> assertThat(image).hasSize(150));
        assertThat(ROW_01.substring(CardRecord.FILLER_OFFSET))
                .as("the fixture's reserved span is 59 spaces, and the codec must emit it as such")
                .isEqualTo(" ".repeat(59));
        assertThat(CardRecord.decodeImage(ROW_01, FIXTURE_CHARSET).encodeToImage(FIXTURE_CHARSET))
                .as("a decode-then-encode round trip is byte-identical, FILLER included")
                .isEqualTo(ROW_01);
        assertThat(CardUpdateService.CARD_UPDATE_RECORD_LENGTH)
                .as("CARD-UPDATE-RECORD at :314-321 restates the same 150 bytes as CVACT02Y")
                .isEqualTo(CardRecord.RECORD_LENGTH);
    }

    /**
     * {@code CARDDEMO-COMMAREA} is exactly 160 bytes, and the returned commarea is
     * {@code 160 + 329}.
     *
     * <p>{@code app/cpy/COCOM01Y.cpy} sums to 160: {@code 4+8+4+8+8+1+1} of the general group,
     * {@code 9+25+25+25} of the customer group, {@code 11+1} of the account group, {@code 16} of the
     * card group, and {@code 7+7} of the trailing map names. That total is what {@code :396} slices with
     * {@code MOVE DFHCOMMAREA (1:LENGTH OF CARDDEMO-COMMAREA)}, so a wrong width would mis-slice
     * everything after it.
     */
    @Test
    @DisplayName("CARDDEMO-COMMAREA is 160 bytes and the returned area is 160 + 329")
    void theCommareaIsExactlyOneHundredAndSixtyBytes() {
        assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
        assertThat(4 + 8 + 4 + 8 + 8 + 1 + 1 + 9 + 25 + 25 + 25 + 11 + 1 + 16 + 7 + 7)
                .isEqualTo(NavigationContext.COMMAREA_LENGTH);
        assertThat(CardUpdateRequest.CommArea.RECORD_LENGTH)
                .as("WS-THIS-PROGCOMMAREA at :274-321 is 1 + 89 + 89 + 150")
                .isEqualTo(1 + CardDetails.RECORD_LENGTH + CardDetails.RECORD_LENGTH
                        + CardUpdateRequest.CardUpdateRecord.RECORD_LENGTH);
        assertThat(EIBCALEN_FULL).isEqualTo(489);
        assertThat(NavigationContext.empty().toFixedWidth(new FixedWidthCodec(FIXTURE_CHARSET)))
                .hasSize(NavigationContext.COMMAREA_LENGTH);
    }

    /**
     * {@code CC-ACCT-ID X(11)} and {@code CC-ACCT-ID-N 9(11)} are two typed views of one span.
     *
     * <p>{@code app/cpy/CVCRD01Y.cpy:34-36} declares the alphanumeric item and redefines it as numeric;
     * the program uses both, reading digits through {@code CC-ACCT-ID-N} at {@code :1463} while
     * {@code 1210-EDIT-ACCOUNT} tests the character form for blanks. A round trip in each direction is
     * asserted, because a redefinition that only works one way is not a redefinition (gate
     * <strong>G34</strong>).
     */
    @Test
    @DisplayName("CC-ACCT-ID and CC-ACCT-ID-N are one span read two ways")
    void theRedefinesPairIsTwoTypedViewsOfOneSpan() {
        assertThat(CardScreenState.CC_ACCT_ID_LENGTH).isEqualTo(11);
        assertThat(CardScreenState.CC_ACCT_ID_N_SPAN.offset())
                .as("the numeric view starts where the character view starts - it is the same storage")
                .isEqualTo(CardScreenState.CC_ACCT_ID_SPAN.offset());
        assertThat(CardScreenState.CC_ACCT_ID_N_SPAN.length())
                .isEqualTo(CardScreenState.CC_ACCT_ID_SPAN.length());
        assertThat(CardScreenState.CC_ACCT_ID_N_SPAN.redefinition()).isTrue();
        assertThat(CardScreenState.CC_ACCT_ID_SPAN.redefinition()).isFalse();

        CardScreenState work = new CardScreenState();
        work.initializeWorkArea();

        // Character view in, numeric view out.
        work.setCcAcctId(ACCT_01);
        assertThat(work.getCcAcctId()).isEqualTo(ACCT_01);
        assertThat(work.getCcAcctIdN()).isEqualTo(50L);

        // Numeric view in, character view out - zero-filled on the left, as PIC 9 always is.
        work.setCcAcctIdN(27L);
        assertThat(work.getCcAcctIdN()).isEqualTo(27L);
        assertThat(work.getCcAcctId()).isEqualTo("00000000027");

        // The same property on the other two pairs the copybook declares at :37-42.
        work.setCcCardNum(KEY_01);
        assertThat(work.getCcCardNumN()).isEqualTo(500_024_453_765_740L);
        work.setCcCardNumN(683_586_198_171_516L);
        assertThat(work.getCcCardNum()).isEqualTo("0683586198171516");
        work.setCcCustIdN(9L);
        assertThat(work.getCcCustId()).isEqualTo("000000009");
    }

    /**
     * {@code CARDAIX} is a second finder on the one {@code CardRepository}, never a second table.
     *
     * <p>{@code app/csd/CARDDEMO.CSD} defines {@code CARDDAT} over the base cluster and {@code CARDAIX}
     * as an alternate-index path over the same one; {@code :251-254} names both as
     * {@code LIT-CARDFILENAME} and {@code LIT-CARDFILENAME-ACCT-PATH}. The gate is that both keys are
     * reached through a single object, so the assertion drives them both on one instance rather than
     * merely checking that two constants exist (gate <strong>G45</strong>).
     */
    @Test
    @DisplayName("CARDAIX is an alternate-index finder on the same repository as CARDDAT")
    void theAlternateIndexIsASecondFinderOnTheSameRepository() {
        assertThat(CardRepository.BASE_DD_NAME).isEqualTo("CARDDAT");
        assertThat(CardRepository.ALTERNATE_INDEX_DD_NAME).isEqualTo("CARDAIX");
        assertThat(CardRepository.BASE_CICS_FILE_NAME.trim()).isEqualTo("CARDDAT");
        assertThat(CardRepository.ALTERNATE_INDEX_CICS_FILE_NAME.trim()).isEqualTo("CARDAIX");
        assertThat(CardRepository.RECORD_LENGTH).isEqualTo(CardRecord.RECORD_LENGTH);
        assertThat(CardUpdateService.CICS_FILE_NAME)
                .as("9200-WRITE-PROCESSING addresses the base cluster, not the path - :1428 and :1478")
                .isEqualTo(CardRepository.BASE_CICS_FILE_NAME);

        List<CardRepository> served = new ArrayList<>(1);
        CardRepository repository = fixtureRepository(SEED_ROWS, Map.of(), served);

        assertThat(repository.readByCardNumber(KEY_01).requireRecord().cardAcctId()).isEqualTo(50L);
        assertThat(repository.readByAccountIdViaAltIndex(ACCT_03).requireRecord().cardNum())
                .isEqualTo(KEY_03);
        assertThat(served)
                .as("both access paths were answered by one repository instance")
                .containsExactly(repository, repository);
    }

    /**
     * {@code 9300-CHECK-CHANGE-IN-REC} compares all six mutable fields, and each one alone is enough.
     *
     * <p>This is the concurrency proof the gate asks for, expressed the only way that proves anything:
     * perturb one field of the snapshot at a time and require the check to notice. Six perturbations,
     * six detections. A translation that dropped any single comparison would still pass a test that only
     * ever changed the CVV, and would then silently overwrite whatever a concurrent transaction had
     * written to the field it forgot (gate <strong>G43</strong>).
     */
    @Test
    @DisplayName("9300 detects a change in any one of its six compared fields")
    void theConcurrencyCheckComparesAllSixMutableFields() {
        FixedWidthCodec codec = new FixedWidthCodec(FIXTURE_CHARSET);
        CardUpdateService service = serviceOver(fixtureRepository(SEED_ROWS, Map.of()));
        CardRecord stored = CardRecord.decodeImage(ROW_01, FIXTURE_CHARSET);
        CardDetails clean = snapshotOf(stored, codec);

        assertThat(service.checkChangeInRec(stored, clean, codec).dataWasChanged())
                .as("the unperturbed snapshot matches, so the rewrite is allowed to proceed")
                .isFalse();

        Map<String, CardDetails> perturbations = new LinkedHashMap<>();
        perturbations.put("CARD-CVV-CD", clean.withCvvCd("999"));
        perturbations.put("CARD-EMBOSSED-NAME", clean.withCrdname(picX("SOMEONE ELSE", 50)));
        perturbations.put("CARD-EXPIRAION-DATE(1:4)", clean.withExpyear("2099"));
        perturbations.put("CARD-EXPIRAION-DATE(6:2)", clean.withExpmon("12"));
        perturbations.put("CARD-EXPIRAION-DATE(9:2)", clean.withExpday("31"));
        perturbations.put("CARD-ACTIVE-STATUS", clean.withCrdstcd("N"));

        assertThat(perturbations)
                .as("the six operands of the single AND chain at app/cbl/COCRDUPC.cbl:1503-1508")
                .hasSize(6);
        perturbations.forEach((field, snapshot) -> {
            CardUpdateService.ChangeCheck check = service.checkChangeInRec(stored, snapshot, codec);
            assertThat(check.dataWasChanged())
                    .as("a change to %s alone must be detected, or a concurrent update to it would be "
                            + "silently overwritten", field)
                    .isTrue();
            assertThat(check.describeDifferences())
                    .as("the report names the field that differed, for %s", field)
                    .isNotBlank();
            assertThat(check.oldDetails())
                    .as("the ELSE arm at :1512-1517 repaints the snapshot from the record it read, so "
                            + "the screen the user sees next shows what is actually stored")
                    .isEqualTo(snapshotOf(stored, codec));
        });
    }

    /**
     * The check ignores {@code CARD-NUM} and {@code CARD-ACCT-ID}, exactly as the source does.
     *
     * <p>The AND chain at {@code :1503-1508} has six operands and neither key is among them. That is
     * asserted here as a positive statement rather than left implicit, because it is precisely the kind
     * of omission a careful translator would "fix" - and fixing it would reject updates the COBOL
     * accepts, which is a changed business rule (practice <strong>B5</strong>).
     */
    @Test
    @DisplayName("9300 compares neither CARD-NUM nor CARD-ACCT-ID, as the source does not")
    void theConcurrencyCheckIgnoresBothKeyFields() {
        FixedWidthCodec codec = new FixedWidthCodec(FIXTURE_CHARSET);
        CardUpdateService service = serviceOver(fixtureRepository(SEED_ROWS, Map.of()));
        CardRecord stored = CardRecord.decodeImage(ROW_01, FIXTURE_CHARSET);
        CardDetails clean = snapshotOf(stored, codec);

        assertThat(service.checkChangeInRec(stored, clean.withCardid("9999999999999999"), codec)
                .dataWasChanged())
                .as("CARD-NUM is absent from the comparison, so a differing snapshot key changes nothing")
                .isFalse();
        assertThat(service.checkChangeInRec(stored, clean.withAcctid("99999999999"), codec)
                .dataWasChanged())
                .as("CARD-ACCT-ID is absent from the comparison too")
                .isFalse();
    }

    /**
     * No version column, no schema artefact, no ORM annotation is introduced anywhere this gate can see.
     *
     * <p>The concurrency control is the field-for-field re-read, and it is complete on its own. A version
     * column would be a schema change, which the plan forbids: no DDL, no entity annotations, no
     * generated table definitions. Asserted by reflection over the two types that would have to carry
     * such a column, so the claim is checked rather than merely written down (gates <strong>G43</strong>
     * and <strong>G44</strong>).
     */
    @Test
    @DisplayName("no version column and no ORM annotation is introduced for concurrency control")
    void noVersionColumnOrSchemaArtefactIsIntroduced() {
        for (Class<?> type : List.of(CardRecord.class, CardUpdateRequest.CardUpdateRecord.class)) {
            assertThat(type.getRecordComponents())
                    .as("%s carries only the copybook's own items - no version, no timestamp, no "
                            + "surrogate key", type.getSimpleName())
                    .noneSatisfy(component -> assertThat(component.getName().toLowerCase())
                            .containsAnyOf("version", "revision", "rowversion", "etag", "optlock"));
            assertThat(type.getAnnotations())
                    .as("%s carries no persistence annotation, so no table is declared anywhere",
                            type.getSimpleName())
                    .noneSatisfy(annotation -> assertThat(
                            annotation.annotationType().getName().toLowerCase())
                            .containsAnyOf("jakarta.persistence", "javax.persistence",
                                    "hibernate"));
        }
        assertThat(CardRecord.LAYOUT.recordLength())
                .as("the record is exactly the copybook's 150 bytes, with nothing appended to it")
                .isEqualTo(150);
    }

    /**
     * Every rewrite writes {@code CARD-CVV-CD = 000}, because {@code CCUP-NEW-CVV-CD} is never assigned.
     *
     * <p>This looks like a defect and it is preserved as one. {@code CCUP-NEW-CVV-CD PIC X(3)} is
     * declared at {@code :306} and appears exactly twice in the whole program: in that declaration and
     * at {@code :1464}, where it is read. Nothing ever writes to it - the {@code COCRDUP} map has no CVV
     * field to receive, and {@code 1100-RECEIVE-MAP} at {@code :578-638} populates the other seven
     * {@code CCUP-NEW-} items and not this one - so it still holds the spaces that
     * {@code INITIALIZE CCUP-NEW-DETAILS} at {@code :586} left there.
     *
     * <p>Those spaces go into {@code CARD-CVV-CD-X PIC X(03)} and come straight back out of its
     * {@code CARD-CVV-CD-N PIC 9(03)} redefinition at {@code :1465}. Reading a space through a zoned
     * numeric picture yields the digit its low nibble names, which is zero, so the stored CVV is
     * replaced by {@code 000} on every successful update. {@code case14} pins that on the wire; this
     * asserts the mechanism, including the right-hand padding that makes a one-character value read as
     * hundreds (practice <strong>B5</strong>).
     */
    @Test
    @DisplayName("the rewrite zeroes CARD-CVV-CD because CCUP-NEW-CVV-CD is never assigned")
    void theRewriteAlwaysZeroesTheCvvBecauseCcupNewCvvCdIsNeverAssigned() {
        FixedWidthCodec codec = new FixedWidthCodec(FIXTURE_CHARSET);

        String initialised = CardUpdateService.redefinedCvvImage("   ", codec);
        assertThat(initialised).isEqualTo("   ");
        assertThat(CardUpdateService.zonedDigitsValue(initialised, codec))
                .as("spaces read through PIC 9(03) are zero, so the rewrite stores 000")
                .isZero();

        assertThat(CardUpdateService.zonedDigitsValue(
                CardUpdateService.redefinedCvvImage("747", codec), codec))
                .as("a supplied three-digit value survives the redefinition unchanged")
                .isEqualTo(747);
        assertThat(CardUpdateService.redefinedCvvImage("7", codec))
                .as("MOVE to PIC X(3) pads on the RIGHT, so one digit lands in the hundreds column")
                .isEqualTo("7  ");
        assertThat(CardUpdateService.zonedDigitsValue(
                CardUpdateService.redefinedCvvImage("7", codec), codec))
                .isEqualTo(700);
    }

    /**
     * A cross-width {@code MOVE} truncates {@code PIC X} on the right and {@code PIC 9} on the left.
     *
     * <p>The two directions are opposite and getting one backwards is silent: a name would keep its tail
     * instead of its head, or an account number would keep its leading zeros instead of its digits.
     * {@code 9200-WRITE-PROCESSING} performs six such moves at {@code :1462-1475}, so the rule is
     * asserted on the codec that owns it before any case relies on it.
     *
     * <p>The commarea items themselves are a deliberate exception, and the second half of this test pins
     * that: {@link CardDetails} refuses an over-wide value outright rather than truncating it, because
     * these are the very bytes {@code 9300-CHECK-CHANGE-IN-REC} compares to decide whether the record
     * changed. A silently shortened snapshot would make that comparison lie.
     */
    @Test
    @DisplayName("MOVE truncates PIC X on the right and PIC 9 on the left")
    void crossWidthMovesTruncateTheWayCobolDoes() {
        FixedWidthCodec codec = new FixedWidthCodec(FIXTURE_CHARSET);

        assertThat(codec.movePicX("ABCDEFGHIJKL", 10))
                .as("PIC X keeps the leftmost bytes and drops the tail")
                .isEqualTo("ABCDEFGHIJ");
        assertThat(codec.movePicX("ABC", 10))
                .as("PIC X pads on the right with spaces")
                .isEqualTo("ABC       ");
        assertThat(codec.movePic9("123456789012345", 11))
                .as("PIC 9 keeps the rightmost digits and drops the high-order ones")
                .isEqualTo("56789012345");
        assertThat(codec.movePic9("27", 11))
                .as("PIC 9 pads on the left with zeros")
                .isEqualTo("00000000027");
        assertThat(codec.concatenateDelimitedBySize("2028", "-", "11", "-", "30"))
                .as("STRING ... DELIMITED BY SIZE at :1467-1474 takes every operand at its full width")
                .isEqualTo("2028-11-30")
                .hasSize(CardRecord.CARD_EXPIRAION_DATE_LENGTH);

        assertThatThrownBy(() -> snapshotOf(CardRecord.decodeImage(ROW_01, FIXTURE_CHARSET), codec)
                .withCrdname("X".repeat(51)))
                .as("a commarea item is never silently truncated - 9300 compares these bytes")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PIC X(50)");
    }

    /**
     * {@code CSSTRPFY} maps every attention identifier the way {@code app/cpy/CSSTRPFY.cpy} maps it,
     * including the fold and the silence.
     *
     * <p>{@code COCRDUPC} is one of only five programs that {@code COPY 'CSSTRPFY'} - it does so at
     * {@code :1528} - so the shared resolver is the one under test rather than an inline chain. Two
     * properties matter beyond the obvious sixteen:
     *
     * <ul>
     *   <li>{@code PF13} through {@code PF24} <strong>fold</strong> onto {@code PFK01} through
     *       {@code PFK12} ({@code CSSTRPFY.cpy:54-77}). A terminal sending the upper bank must behave as
     *       though the lower bank were pressed.</li>
     *   <li>There is <strong>no {@code WHEN OTHER}</strong>. An identifier the {@code EVALUATE} does not
     *       list leaves {@code CCARD-AID} untouched, which is an empty resolution and not a default -
     *       so {@code storePfKey} must return what it was given.</li>
     * </ul>
     */
    @Test
    @DisplayName("CSSTRPFY resolves every AID, folds PF13-PF24 and defaults nothing")
    void pfKeysResolveExactlyAsCsstrpfyMapsThem() {
        Map<Byte, AidKey> direct = new LinkedHashMap<>();
        direct.put(CicsAid.DFHENTER, AidKey.ENTER);
        direct.put(CicsAid.DFHCLEAR, AidKey.CLEAR);
        direct.put(CicsAid.DFHPA1, AidKey.PA1);
        direct.put(CicsAid.DFHPA2, AidKey.PA2);
        direct.put(CicsAid.DFHPF1, AidKey.PFK01);
        direct.put(CicsAid.DFHPF2, AidKey.PFK02);
        direct.put(CicsAid.DFHPF3, AidKey.PFK03);
        direct.put(CicsAid.DFHPF4, AidKey.PFK04);
        direct.put(CicsAid.DFHPF5, AidKey.PFK05);
        direct.put(CicsAid.DFHPF6, AidKey.PFK06);
        direct.put(CicsAid.DFHPF7, AidKey.PFK07);
        direct.put(CicsAid.DFHPF8, AidKey.PFK08);
        direct.put(CicsAid.DFHPF9, AidKey.PFK09);
        direct.put(CicsAid.DFHPF10, AidKey.PFK10);
        direct.put(CicsAid.DFHPF11, AidKey.PFK11);
        direct.put(CicsAid.DFHPF12, AidKey.PFK12);
        assertThat(direct)
                .as("the sixteen 88-levels of CVCRD01Y:3-19 - the plan's count of fifteen is one short "
                        + "of what app/cpy/CVCRD01Y.cpy actually declares, and the source wins")
                .hasSize(16);
        direct.forEach((aid, expected) -> assertThat(PfKeyResolver.resolve(aid))
                .as("EIBAID 0x%02X resolves to %s", aid, expected)
                .contains(expected));

        Map<Byte, AidKey> folded = new LinkedHashMap<>();
        folded.put(CicsAid.DFHPF13, AidKey.PFK01);
        folded.put(CicsAid.DFHPF14, AidKey.PFK02);
        folded.put(CicsAid.DFHPF15, AidKey.PFK03);
        folded.put(CicsAid.DFHPF16, AidKey.PFK04);
        folded.put(CicsAid.DFHPF17, AidKey.PFK05);
        folded.put(CicsAid.DFHPF18, AidKey.PFK06);
        folded.put(CicsAid.DFHPF19, AidKey.PFK07);
        folded.put(CicsAid.DFHPF20, AidKey.PFK08);
        folded.put(CicsAid.DFHPF21, AidKey.PFK09);
        folded.put(CicsAid.DFHPF22, AidKey.PFK10);
        folded.put(CicsAid.DFHPF23, AidKey.PFK11);
        folded.put(CicsAid.DFHPF24, AidKey.PFK12);
        assertThat(folded).hasSize(12);
        folded.forEach((aid, expected) -> assertThat(PfKeyResolver.resolve(aid))
                .as("EIBAID 0x%02X is the upper bank and folds onto %s", aid, expected)
                .contains(expected));

        // The silence. DFHPA3 and DFHPEN are real DFHAID constants that CSSTRPFY's EVALUATE omits.
        for (byte unlisted : new byte[] {CicsAid.DFHPA3, CicsAid.DFHPEN, CicsAid.DFHCLRP,
                CicsAid.DFHOPID}) {
            assertThat(PfKeyResolver.resolve(unlisted))
                    .as("EIBAID 0x%02X is not one of the EVALUATE's WHEN clauses and there is no "
                            + "WHEN OTHER, so nothing is resolved", unlisted)
                    .isEmpty();
            assertThat(PfKeyResolver.storePfKey(unlisted, Optional.of(AidKey.PFK05)))
                    .as("an unlisted identifier leaves CCARD-AID exactly as it was")
                    .contains(AidKey.PFK05);
        }
        assertThat(PfKeyResolver.storePfKey(CicsAid.DFHPF13, Optional.of(AidKey.PFK05)))
                .as("a listed identifier overwrites CCARD-AID, folded")
                .contains(AidKey.PFK01);
    }

    /**
     * The five arms of {@code 0000-MAIN}'s {@code EVALUATE} are ordered, and the order is behaviour.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:429-543} is a first-match-wins {@code EVALUATE TRUE} whose arms are,
     * in source order: the {@code XCTL} exit at {@code :435-476}; the entry from the card list at
     * {@code :482-497}; the fresh-entry prompt at {@code :502-511}, which performs
     * {@code 3000-SEND-MAP THRU 3000-SEND-MAP-EXIT} at {@code :507}; the post-update reset at
     * {@code :517-528}, which performs the same range at {@code :524}; and {@code WHEN OTHER} at
     * {@code :535-542}.
     *
     * <p>The ordering is asserted through overlapping conditions, which is the only way it can be. A
     * commarea that satisfies both arm one and arm four - changes okayed and done, having arrived from
     * the card list - must take arm one and transfer control, not arm four and repaint. Reversing the two
     * would leave the user on a screen the COBOL navigates away from, and no assertion on outcomes alone
     * would notice (gates <strong>G30</strong> and rule <strong>R7</strong>).
     */
    @Test
    @DisplayName("0000-MAIN's EVALUATE is first-match-wins, and arm one outranks arm four")
    void theEvaluateArmsArePickedInSourceOrder() {
        // Arm 1 and arm 4 are both satisfied: CCUP-CHANGES-OKAYED-AND-DONE with CDEMO-LAST-MAPSET equal
        // to LIT-CCLISTMAPSET matches :436-437, and CCUP-CHANGES-OKAYED-AND-DONE alone matches :517.
        CardUpdateRequest overlapping = requestWith(navigationFromCardList()
                .withLastMapset("COCRDLI").withLastMap("CCRDSLA"));
        overlapping.setCommArea(new CardUpdateRequest.CommArea(
                CardUpdateRequest.ChangeAction.changesOkayedAndDone(),
                snapshotOf(CardRecord.decodeImage(ROW_01, FIXTURE_CHARSET),
                        new FixedWidthCodec(FIXTURE_CHARSET)),
                newDetailsOf(ACCT_01, KEY_01, picX("ANIYA VON", 50), "N", "11", "2028", "30"),
                CardUpdateRequest.CardUpdateRecord.initialised()));

        CardUpdateResponse painted = paint(overlapping, EIBCALEN_FULL, CicsAid.DFHENTER);

        assertThat(token(painted.getNextProgram()))
                .as("arm one wins: control transfers, so a next program is named")
                .isEqualTo(CARD_LIST_PGM);
        assertThat(token(painted.getNextMap()))
                .as("arm four would have repainted CCRDUPA; arm one sends no map at all")
                .isNull();
        assertThat(token(painted.getNextMapset())).isNull();
    }

    /**
     * The error-highlight matrix of {@code CSSETATY} applies in {@code REENTER} and not in
     * {@code ENTER}.
     *
     * <p>Four states per field, and the difference between them is what a user sees. {@link
     * FieldAttributeSetter} owns the rule; this asserts it directly so that {@code case16} through
     * {@code case18} can assert its consequences on the wire (gate <strong>G38</strong>).
     */
    @Test
    @DisplayName("the highlight matrix reddens a field only in REENTER, and asterisks only a blank one")
    void theHighlightMatrixAppliesOnlyOnReentry() {
        FieldAttributeSetter.FieldHighlight okOnEntry =
                FieldAttributeSetter.resolveFromFlags(false, false, false, "ACCTSID", "CCRDUPAO");
        assertThat(okOnEntry.untouched())
                .as("a valid field is never highlighted, on either pass")
                .isTrue();

        FieldAttributeSetter.FieldHighlight notOkOnEntry =
                FieldAttributeSetter.resolveFromFlags(true, false, false, "ACCTSID", "CCRDUPAO");
        assertThat(notOkOnEntry.untouched())
                .as("first entry paints the screen; it does not judge what has not been typed yet")
                .isTrue();

        FieldAttributeSetter.FieldHighlight notOkOnReentry =
                FieldAttributeSetter.resolveFromFlags(true, false, true, "ACCTSID", "CCRDUPAO");
        assertThat(notOkOnReentry.colourItemAssigned()).isTrue();
        assertThat(notOkOnReentry.colourItemValue()).isEqualTo(BmsAttributes.DFHRED);
        assertThat(notOkOnReentry.colourItemName()).isEqualTo("ACCTSIDC");
        assertThat(notOkOnReentry.outputItemAssigned())
                .as("an invalid value is reddened but kept, so the user can see what was rejected")
                .isFalse();

        FieldAttributeSetter.FieldHighlight blankOnReentry =
                FieldAttributeSetter.resolveFromFlags(false, true, true, "ACCTSID", "CCRDUPAO");
        assertThat(blankOnReentry.colourItemValue()).isEqualTo(BmsAttributes.DFHRED);
        assertThat(blankOnReentry.outputItemAssigned())
                .as("a blank field is reddened AND marked, because there is nothing to show otherwise")
                .isTrue();
        assertThat(blankOnReentry.outputItemValue()).isEqualTo(FieldAttributeSetter.ASTERISK);
        assertThat(blankOnReentry.outputItemName()).isEqualTo("ACCTSIDO");

        assertThat(FieldAttributeSetter.FieldValidationState.of(true, false).notOk()).isTrue();
        assertThat(FieldAttributeSetter.FieldValidationState.of(false, true).blank()).isTrue();
    }

    /**
     * {@code EXPDAYC} carries {@code DFHBMDAR} on every send, so the expiry day is never displayed.
     *
     * <p>{@code MOVE DFHBMDAR TO EXPDAYC OF CCRDUPAO} at {@code app/cbl/COCRDUPC.cbl:1285} is
     * unconditional - it sits in {@code 3300-SETUP-SCREEN-ATTRS} outside every {@code IF} - and
     * {@code DFHBMDAR} is the dark, non-display attribute. The field exists in the map, is populated at
     * {@code :1110} and {@code :1123}, and is then hidden.
     *
     * <p>Worth an assertion of its own because it is the single most likely thing to be "tidied away" by
     * someone who assumes an invisible field is a mistake. It is not a mistake; it is the screen the
     * program paints, and {@code case16} through {@code case20} all expect it.
     */
    @Test
    @DisplayName("EXPDAYC is DFHBMDAR on every send - the expiry day is deliberately dark")
    void theExpiryDayIsAlwaysDark() {
        assertThat(BmsAttributes.DFHBMDAR).isEqualTo((byte) 0x4C);
        assertThat(BmsAttributes.FIELD_ATTRIBUTE_MNEMONICS.get(BmsAttributes.DFHBMDAR))
                .as("the mnemonic must be nameable, because a case declares attributes by mnemonic")
                .isEqualTo("DFHBMDAR");
        assertThat(BmsAttributes.COLOUR_MNEMONICS.get(BmsAttributes.DFHRED)).isEqualTo("DFHRED");

        CardUpdateResponse painted = paint(null, EIBCALEN_NONE, CicsAid.DFHENTER);
        assertThat(painted.attributesOf("EXPDAY").getColour()).isEqualTo(BmsAttributes.DFHBMDAR);
        for (String stem : MAP_FIELDS) {
            if ("EXPDAY".equals(stem)) {
                continue;
            }
            assertThat(painted.attributesOf(stem).getColour())
                    .as("%sC is left at its default on a first, valid entry", stem)
                    .isEqualTo(CardUpdateResponse.FieldAttributes.DEFAULT);
        }
    }

    /**
     * The programmed-symbol and validation planes are never written, on any of the twenty cases.
     *
     * <p>{@code DFHBMSCA} publishes no mnemonic table for either plane, so a value on one could not be
     * declared in a case at all. Rather than omit them silently - which would leave two of the four
     * attribute planes unasserted - every one of the seventeen {@code xxxP} and {@code xxxV} bytes is
     * required to stay at its default across every case. That is a stronger statement than an
     * unnameable expectation would have been, and an explicit one.
     */
    @Test
    @DisplayName("the xxxP and xxxV attribute planes stay untouched on every case")
    void theProgrammedSymbolAndValidationPlanesAreNeverWritten() {
        for (ParityCase parityCase : cases()) {
            if (parityCase.unitKind() != UnitKind.CONTROLLER_POJO) {
                continue;
            }
            ScreenRequest request = parityCase.screenRequest();
            CardUpdateResponse painted = paint(requestFrom(request), request.eibcalen(),
                    aidByte(request.aid()), pathCardNumberOf(request));
            for (String stem : MAP_FIELDS) {
                CardUpdateResponse.FieldAttributes attributes = painted.attributesOf(stem);
                assertThat(attributes.getPs())
                        .as("%s/%sP - no program in this application writes a programmed-symbol byte",
                                parityCase.caseId(), stem)
                        .isEqualTo(CardUpdateResponse.FieldAttributes.DEFAULT);
                assertThat(attributes.getValidn())
                        .as("%s/%sV - nor a validation byte", parityCase.caseId(), stem)
                        .isEqualTo(CardUpdateResponse.FieldAttributes.DEFAULT);
            }
        }
    }

    // =================================================================================================
    // The declared program area. One decoder, shared by both adapters, reading the image the case states.
    // =================================================================================================

    /**
     * Decodes the {@code WS-THIS-PROGCOMMAREA} image the case declares.
     *
     * <p>Absent, the area is the one {@code :390-393} leaves behind: {@code INITIALIZE} over both halves,
     * which is {@code CCUP-CHANGE-ACTION} at {@code LOW-VALUES} and both snapshots all spaces. That is
     * the cold-start state and it is a legitimate thing for a case to declare by omission, because
     * {@code EIBCALEN} zero is how CICS reports it.
     *
     * @param invocation the invocation, for the case's commarea and code page
     * @return the decoded area; never {@code null}
     * @throws IllegalStateException if the declared image is not exactly the area's declared width, since
     *                               a short image is a case file that cannot describe the run it claims
     */
    private static CardUpdateRequest.CommArea programAreaOf(Invocation invocation) {
        String image = invocation.commarea().get(PROGRAM_AREA_KEY);
        if (image == null) {
            return CardUpdateRequest.CommArea.initialised();
        }
        if (image.length() != CardUpdateRequest.CommArea.RECORD_LENGTH) {
            throw new IllegalStateException("Case " + invocation.program() + '/' + invocation.caseId()
                    + " declares a " + PROGRAM_AREA_KEY + " image of " + image.length()
                    + " characters where WS-THIS-PROGCOMMAREA is "
                    + CardUpdateRequest.CommArea.RECORD_LENGTH + " bytes - "
                    + "CARD-UPDATE-SCREEN-DATA, CCUP-OLD-DETAILS, CCUP-NEW-DETAILS and "
                    + "CARD-UPDATE-RECORD, at app/cbl/COCRDUPC.cbl:206-241. A short image would decode "
                    + "into a state the program cannot be in.");
        }
        return CardUpdateRequest.CommArea.decode(image.getBytes(invocation.charset()),
                invocation.codec());
    }

    /**
     * The card number the URI carries, taken from the case.
     *
     * <p>{@code CCUP-CARD-NUM} on the screen and the {@code {cardNum}} path variable are one key in this
     * translation, so the value has to come from the case rather than from a constant: a hard-coded key
     * disagreeing with a typed one is two keys in one request, which the handler refuses outright - and
     * refuses correctly, since a 3270 has one key field and no URI.
     *
     * <p>Order of preference mirrors where the program reads the key from: what the operator typed
     * ({@code CARDSIDI}), then what the conversation carried ({@code CDEMO-CARD-NUM}), then spaces - the
     * cold-start state in which {@code 1210-EDIT-ACCOUNT} prompts for it.
     *
     * @param invocation the invocation, for the case's received fields and commarea
     * @return the sixteen-character key, space-padded, never {@code null}
     */
    private static String pathCardNumberOf(Invocation invocation) {
        return pathCardNumber(invocation.mapFields(), invocation.commarea());
    }

    /**
     * The card number the URI carries, for a call site holding a {@link ScreenRequest}.
     *
     * @param declared the case's screen request
     * @return the sixteen-character key, space-padded, never {@code null}
     */
    private static String pathCardNumberOf(ScreenRequest declared) {
        return pathCardNumber(declared.mapFields(), declared.commarea());
    }

    /**
     * The card number the URI carries, resolved from the case's received fields and commarea.
     *
     * @param mapFields the received {@code xxxI} items
     * @param commarea  the inbound {@code CARDDEMO-COMMAREA} fields
     * @return the sixteen-character key, space-padded, never {@code null}
     */
    private static String pathCardNumber(Map<String, String> mapFields,
                                         Map<String, String> commarea) {
        String typed = mapFields.get("CARDSIDI");
        if (typed != null) {
            return picX(typed.trim(), CardDetails.CARDID_LENGTH);
        }
        String carried = commarea.get("CDEMO-CARD-NUM");
        return carried == null || carried.isBlank()
                ? picX("", CardDetails.CARDID_LENGTH)
                : picX(carried.trim(), CardDetails.CARDID_LENGTH);
    }

    /**
     * The forced outcomes the case declared, among the operations named.
     *
     * <p>Taken through {@link Invocation#forcedOutcome} rather than read off the case, because that call
     * is what marks the outcome consumed - and the harness refuses a run that left a declared outcome
     * unasked-for, which is how a case claiming to drive a {@code WHEN OTHER} arm is stopped from quietly
     * taking the ordinary path instead.
     *
     * @param invocation the invocation
     * @param operations the operations this adapter is able to force
     * @return the declared outcomes among those operations, in the order named
     */
    private static Map<RepositoryOperation, ForcedOutcome> forcedOutcomesOf(Invocation invocation,
                                                                           RepositoryOperation...
                                                                                   operations) {
        Map<RepositoryOperation, ForcedOutcome> forced = new LinkedHashMap<>();
        for (RepositoryOperation operation : operations) {
            if (invocation.hasForcedOutcome(operation)) {
                forced.put(operation, invocation.forcedOutcome(operation));
            }
        }
        return forced;
    }

    /**
     * Requires the case's received map fields to agree with the {@code CCUP-NEW-DETAILS} it declares.
     *
     * <p>Both are in the case file and both describe the same seven values, because that is what the
     * program does: {@code 2000-PROCESS-INPUTS} moves each received {@code xxxI} item into its
     * {@code CCUP-NEW-} counterpart before the confirm arm reaches
     * {@code 9200-WRITE-PROCESSING}. Stating them twice is how a case remains readable at both levels -
     * what was typed, and what the area therefore holds - and this check is what stops the two drifting
     * into a case that would exercise something no operator could have produced.
     *
     * @param invocation the invocation, for the case's received map fields
     * @param newDetails the {@code CCUP-NEW-DETAILS} the declared area decoded to
     */
    private static void requireReceivedFieldsMatch(Invocation invocation, CardDetails newDetails) {
        Map<String, String> received = invocation.mapFields();
        Map<String, String> fromArea = Map.of(
                "ACCTSIDI", newDetails.acctid(),
                "CARDSIDI", newDetails.cardid(),
                "CRDNAMEI", newDetails.crdname(),
                "CRDSTCDI", newDetails.crdstcd(),
                "EXPMONI", newDetails.expmon(),
                "EXPYEARI", newDetails.expyear(),
                "EXPDAYI", newDetails.expday());
        for (Map.Entry<String, String> entry : fromArea.entrySet()) {
            String typed = received.get(entry.getKey());
            if (typed == null) {
                continue;
            }
            if (!picX(typed.trim(), entry.getValue().length()).equals(entry.getValue())) {
                throw new IllegalStateException("Case " + invocation.program() + '/'
                        + invocation.caseId() + " declares " + entry.getKey() + " as '" + typed
                        + "' but its WS-THIS-PROGCOMMAREA holds '" + entry.getValue() + "' in the "
                        + "matching CCUP-NEW- item. 2000-PROCESS-INPUTS copies one into the other, so "
                        + "the two cannot disagree in a run the program could have produced.");
            }
        }
    }

    // =================================================================================================
    // THE SERVICE ADAPTER. Constructs CardUpdateService through its own constructor and calls
    // writeProcessing. No Spring context, no HTTP layer, no job launcher.
    // =================================================================================================

    /**
     * Reaches {@link CardUpdateService#writeProcessing} for one case and records what it produced.
     *
     * <p>Three collaborators, all supplied by hand: a fixture-backed {@link CardRepository} over the
     * case's seeded {@code CARDDAT} rows, a real {@link DatasetUnitOfWork} over an in-memory database,
     * and the harness's codec, which carries the case's code page rather than a platform default.
     *
     * <p>The unit of work is deliberately real rather than a stand-in. The point of
     * {@code 9200-WRITE-PROCESSING} is that the lock the {@code READ ... UPDATE} takes at {@code :1429}
     * survives to the {@code REWRITE} at {@code :1478}, and only a genuine transaction boundary can be
     * observed doing that; something that merely ran the body would let the arrangement pass while
     * proving nothing about it.
     *
     * <h2>Every argument comes from the case</h2>
     * <p>{@code 9200-WRITE-PROCESSING} takes four things the seeded rows cannot supply: the key the
     * {@code READ ... UPDATE} at {@code :1425} positions with, the snapshot the screen was painted from
     * ({@code CCUP-OLD-DETAILS}), what the operator typed ({@code CCUP-NEW-DETAILS}) and
     * {@code WS-RETURN-MSG} as it stands on entry. Each is read from the case here:
     * <ul>
     *   <li>the snapshot and the typed values are decoded from the {@code WS-THIS-PROGCOMMAREA} image the
     *       case's commarea carries, at the offsets {@code app/cbl/COCRDUPC.cbl:206-241} declares - which
     *       is exactly what {@code :404-406} does with {@code DFHCOMMAREA};</li>
     *   <li>the key is {@code CDEMO-CARD-NUM} from the same commarea, which is what
     *       {@code 1000-SEND-MAP}'s caller loaded {@code CC-CARD-NUM} from;</li>
     *   <li>{@code WS-RETURN-MSG} is the case's {@code unitStimulus.linkage} entry of that name, because
     *       it is a linkage value rather than a screen field and would otherwise be invisible in the
     *       case file.</li>
     * </ul>
     *
     * @param invocation the seeded datasets, the pinned clock, the codec, the declared stimulus and the
     *                   recorder
     * @return the recorded outcome; never {@code null}
     */
    private static UnitOutcome serviceUnit(Invocation invocation) {
        CardUpdateRequest.CommArea area = programAreaOf(invocation);
        String cardKey = picX(invocation.commarea().getOrDefault("CDEMO-CARD-NUM", "").trim(),
                CardDetails.CARDID_LENGTH);
        CardDetails oldDetails = area.oldDetails();
        CardDetails newDetails = area.newDetails();
        requireReceivedFieldsMatch(invocation, newDetails);
        String returnMessage = invocation.stimulus().linkageValue(LINKAGE_RETURN_MSG)
                .orElseThrow(() -> new IllegalStateException("Case " + PROGRAM + '/'
                        + invocation.caseId() + " declares unitKind SERVICE but no "
                        + LINKAGE_RETURN_MSG + " linkage value. 9200-WRITE-PROCESSING is handed "
                        + "WS-RETURN-MSG as it stands on entry - SPACES on every path that reaches it "
                        + "through 0000-MAIN, which clears it at :384 - and a case that left it "
                        + "unstated would be running with a value nobody declared."));
        return serviceUnit(invocation, cardKey, oldDetails, newDetails, returnMessage);
    }

    /**
     * Runs {@code 9200-WRITE-PROCESSING} with the four values the case supplied.
     *
     * @param invocation    the seeded datasets, the pinned clock, the codec and the recorder
     * @param cardKey       {@code CC-CARD-NUM}
     * @param oldDetails    {@code CCUP-OLD-DETAILS}
     * @param newDetails    {@code CCUP-NEW-DETAILS}
     * @param returnMessage {@code WS-RETURN-MSG} on entry
     * @return the recorded outcome; never {@code null}
     */
    private static UnitOutcome serviceUnit(Invocation invocation,
                                           String cardKey,
                                           CardDetails oldDetails,
                                           CardDetails newDetails,
                                           String returnMessage) {
        SeededDataset seeded = invocation.dataset(CARDDAT);
        Map<RepositoryOperation, ForcedOutcome> forced = new LinkedHashMap<>();
        for (RepositoryOperation operation : List.of(RepositoryOperation.READ_FOR_UPDATE,
                RepositoryOperation.REWRITE)) {
            if (invocation.hasForcedOutcome(operation)) {
                // Taken through forcedOutcome rather than read off the case, because that call marks the
                // outcome consumed and the harness refuses a run whose forced outcome never fired.
                forced.put(operation, invocation.forcedOutcome(operation));
            }
        }

        List<String> rows = new ArrayList<>(seeded.rows());
        CardRepository repository = fixtureRepository(rows, forced);
        CardUpdateService service = serviceOver(repository);

        CardScreenState workArea = new CardScreenState();
        workArea.initializeWorkArea();
        workArea.setCcCardNum(cardKey);
        // Through the numeric redefinition, which is how :1463 reads it. CC-ACCT-ID-N and CC-ACCT-ID are
        // one span, so loading either loads both - which case12 relies on and gate G34 is about.
        workArea.setCcAcctIdN(Long.parseLong(newDetails.acctid().trim()));

        CardUpdateService.WriteResult result = service.writeProcessing(workArea, oldDetails,
                newDetails, returnMessage, invocation.codec());

        UnitOutcome.Builder recorder = invocation.recorder();
        // isRewritten(), not cardUpdateRecord().isPresent(). The staging at :1461-1475 happens BEFORE the
        // REWRITE at :1477, so a record that was staged and then refused is still present in the result -
        // and reporting it as a write would claim the dataset changed when it did not. Exactly one of the
        // four outcomes rewrote anything.
        Optional<String> rewritten = result.isRewritten()
                ? result.cardUpdateRecord()
                        .map(CardUpdateService::asCardRecord)
                        .map(record -> record.encodeToImage(invocation.charset()))
                : Optional.empty();
        if (rewritten.isPresent()) {
            recorder.wrote(CARDDAT, CardRecord.LAYOUT, rewritten.get());
            recorder.finalState(CARDDAT, CardRecord.LAYOUT, rows);
        } else {
            // The positive form of "no rewrite happened". An empty write channel and a dataset that still
            // holds exactly what it was seeded with are two halves of one assertion, and both are needed:
            // a write that was made and then reverted would satisfy only the second.
            recorder.openedWithoutWriting(CARDDAT, CardRecord.LAYOUT);
            recorder.finalStateUnchanged(seeded, CardRecord.LAYOUT);
        }
        // WS-RETURN-MSG is PIC X(75). Neither of the two width-bearing message channels is 75 - one is
        // the 80-byte WORKING-STORAGE message of the batch programs, the other the 78-byte screen field
        // of the maps that declare ERRMSGO at that width, and this map declares it at 80 - so the
        // width-free channel is the only faithful one. The width itself is asserted by
        // theReturnMessageIsSeventyFiveBytesWide() rather than left unstated.
        recorder.display(result.returnMessage());
        // EXEC CICS RETURN, never EXEC CICS ABEND: no path through 9200-WRITE-PROCESSING abends, so the
        // COBOL RETURN-CODE is zero. Stated rather than defaulted, because a defaulted return code is
        // indistinguishable from one nobody considered.
        recorder.returnCode(0);
        return recorder.build();
    }

    // =================================================================================================
    // THE CONTROLLER ADAPTER. Constructs CardUpdateController as a plain object and calls its handler.
    // =================================================================================================

    /**
     * Reaches {@link CardUpdateController#updateCardDetail} for one case and records the response.
     *
     * <p>Four collaborators, none of them framework: the fixture-backed repository, a
     * {@link CardUpdateService} over that same repository, the case's pinned clock, and the fixtures'
     * code page. The handler is called as a plain method on a plain object, and the {@code EIBCALEN} and
     * {@code EIBAID} that CICS would have supplied are passed as the two request parameters the
     * translation exposes for exactly that purpose.
     *
     * @param invocation the seeded datasets, the AID, the inbound commarea, the pinned clock and the
     *                   recorder
     * @return the recorded outcome; never {@code null}
     */
    private static UnitOutcome controllerUnit(Invocation invocation) {
        SeededDataset seeded = invocation.dataset(CARDDAT);
        List<String> rows = new ArrayList<>(seeded.rows());
        List<String> rewritten = new ArrayList<>();
        CardRepository repository = fixtureRepository(rows,
                forcedOutcomesOf(invocation, RepositoryOperation.READ,
                        RepositoryOperation.READ_FOR_UPDATE, RepositoryOperation.REWRITE),
                new ArrayList<>(), rewritten::add);

        CardUpdateController controller = new CardUpdateController(repository,
                serviceOver(repository), invocation.clock(), invocation.charset());

        CardUpdateRequest request = requestFrom(invocation.commarea(), invocation.mapFields(),
                programAreaOf(invocation));
        ScreenResponse<CardUpdateResponse> body = controller.updateCardDetail(
                pathCardNumberOf(invocation), request, null,
                invocation.eibcalen(), Byte.toUnsignedInt(aidByte(invocation.aid()))).getBody();
        CardUpdateResponse painted = java.util.Objects.requireNonNull(body,
                "COCRDUPC ends in EXEC CICS XCTL or EXEC CICS RETURN on every path, so the handler "
                        + "always answers with a body").screen();

        UnitOutcome.Builder recorder = invocation.recorder();
        recorder.response(observed(painted, body));
        // THE DATASET IS OBSERVED ON EVERY CONTROLLER CASE, not only on the cases that expect
        // something of it. app/csd/CARDDEMO.CSD:25-36 defines CARDDAT with STATUS(ENABLED)
        // OPENTIME(FIRSTREF), so the file is available to the transaction whether or not this path
        // touches it - the program issues no OPEN and has none to fail - which makes "nothing was
        // written and the three rows are exactly as seeded" an assertion available to every case
        // rather than a silence. Recorded unconditionally and compared afterwards: what a run
        // produced must never be selected by what the case expects of it.
        if (rewritten.isEmpty()) {
            recorder.openedWithoutWriting(CARDDAT, CardRecord.LAYOUT);
        } else {
            rewritten.forEach(image -> recorder.wrote(CARDDAT, CardRecord.LAYOUT, image));
        }
        recorder.finalState(CARDDAT, CardRecord.LAYOUT, rows);
        recorder.returnCode(0);
        return recorder.build();
    }

    /**
     * Projects the returned payload onto the observation shape {@link FieldDiffer} judges.
     *
     * <p>Four decisions are worth stating, because each is the difference between an observation that can
     * fail and one that cannot.
     *
     * <p><strong>A blank next-screen token is reported absent.</strong> The three carriers are initialised
     * to spaces at their declared widths - {@code CCARD-NEXT-PROG X(8)}, {@code CCARD-NEXT-MAPSET X(7)}
     * and {@code CCARD-NEXT-MAP X(7)} - and a path that never assigns one leaves those spaces behind.
     * Seven spaces is not a BMS map name, so the faithful report is that no target was named.
     *
     * <p><strong>The send count is derived from the map name, not assumed.</strong> A named map is exactly
     * the condition "this invocation performed {@code EXEC CICS SEND MAP}": the {@code XCTL} arm at
     * {@code :473-476} never assigns one and correctly reports zero sends, while every arm that reaches
     * {@code 3400-SEND-SCREEN} does.
     *
     * <p><strong>The cursor is reported as the {@code xxxL} item.</strong> COBOL positions the cursor by
     * moving {@code -1} into a symbolic-map length item, so the length item <em>is</em> the cursor. The
     * response metadata names the field stem, and the {@code L} suffix is what turns that stem back into
     * the item the COBOL wrote.
     *
     * <p><strong>{@code XCTL} and {@code RETURN} are distinguished by what was assigned, not by which key
     * was pressed.</strong> A named next program with no map sent is the transfer at {@code :473}, which
     * never reaches the {@code EXEC CICS RETURN} in {@code COMMON-RETURN}; anything else returned.
     *
     * @param painted the payload the handler returned
     * @param body    the envelope, for the cursor the screen metadata carries
     * @return the observation
     */
    private static ObservedResponse observed(CardUpdateResponse painted,
                                             ScreenResponse<CardUpdateResponse> body) {
        String nextProgram = token(painted.getNextProgram());
        String nextMapset = token(painted.getNextMapset());
        String nextMap = token(painted.getNextMap());

        List<ObservedSend> sends = new ArrayList<>(1);
        if (nextMap != null) {
            sends.add(new ObservedSend(painted.fieldImages(), colourMnemonics(painted)));
        }

        String cursorStem = body.screenMetadata() == null ? null
                : body.screenMetadata().cursorField();
        String cursorField = cursorStem == null || cursorStem.isBlank() ? null : cursorStem + "L";

        Termination termination = nextProgram != null && sends.isEmpty()
                ? Termination.XCTL
                : Termination.RETURN_TRANSID;

        return new ObservedResponse(nextProgram, nextMapset, nextMap,
                navigationImage(painted.getNavigationContext()), sends, cursorField, termination);
    }

    /**
     * The seventeen {@code xxxC} extended-colour items, valued with the mnemonic the program moved.
     *
     * <p>The colour plane only. {@code DFHBMSCA} publishes no mnemonic table for a programmed-symbol or a
     * validation byte, and its highlight table maps {@code 0x00} to a name the case model does not accept
     * as an attribute value, so those three planes cannot be declared in a case at all. They are asserted
     * instead by {@link #theProgrammedSymbolAndValidationPlanesAreNeverWritten()}, which requires all of
     * them to stay at their defaults on every case - a stronger statement than an unnameable expectation,
     * and an explicit one rather than a silent omission.
     *
     * @param painted the payload
     * @return seventeen attribute items keyed by symbolic-map name
     */
    private static Map<String, String> colourMnemonics(CardUpdateResponse painted) {
        Map<String, String> items = new LinkedHashMap<>();
        for (String stem : MAP_FIELDS) {
            byte colour = painted.attributesOf(stem).getColour();
            String mnemonic = BmsAttributes.COLOUR_MNEMONICS.get(colour);
            if (mnemonic == null) {
                // DFHBMDAR, which :1285 moves onto EXPDAYC, is a field-attribute value rather than a
                // colour, so it lives in the other table. Both are permitted mnemonics.
                mnemonic = BmsAttributes.FIELD_ATTRIBUTE_MNEMONICS.get(colour);
            }
            items.put(stem + "C", java.util.Objects.requireNonNull(mnemonic,
                    () -> "No DFHBMSCA or DFHATTR mnemonic names the byte 0x"
                            + String.format("%02X", colour) + " that " + stem + "C carries. An "
                            + "attribute is declared by mnemonic rather than by raw byte, so a value "
                            + "with no name cannot be expressed in a case and is a defect rather than "
                            + "something to render numerically."));
        }
        return items;
    }

    // =================================================================================================
    // The fixture-backed CARDDAT stub - one repository, both access paths, and the case decides the arm.
    // =================================================================================================

    /**
     * A {@link CardRepository} over a mutable copy of the case's seeded rows.
     *
     * <p>The reads answer from the seeded slice: a row whose first sixteen bytes equal the key comes back
     * as {@code NORMAL} carrying that row decoded through {@link CardRecord#decodeImage}, and no match
     * comes back as {@code NOTFND}. The rewrite replaces the row in place, keyed on {@code CARD-NUM},
     * which is what makes the final-state channel meaningful: the dataset the adapter reports afterwards
     * is the one the unit actually changed, not a re-derivation of what it should have changed.
     *
     * <p>Every method the program does not use throws. {@code COCRDUPC}'s file verbs are the two reads and
     * the one rewrite of {@code 9000-READ-DATA}, {@code 9100-GETCARD-BYACCTCARD} and
     * {@code 9200-WRITE-PROCESSING}; it issues no {@code WRITE}, no {@code DELETE} and no browse anywhere.
     * A call to anything else is a translation reaching for a verb the source does not contain, and it must
     * fail loudly here rather than receive a silent default.
     *
     * @param rows  the seeded rows, mutated in place by a rewrite
     * @param forced the outcomes the case forces, by operation
     * @return the stub
     */
    private static CardRepository fixtureRepository(List<String> rows,
                                                   Map<RepositoryOperation, ForcedOutcome> forced) {
        return fixtureRepository(rows, forced, new ArrayList<>());
    }

    /**
     * The same stub, additionally recording which instance served each call.
     *
     * @param rows   the seeded rows, mutated in place by a rewrite
     * @param forced the outcomes the case forces, by operation
     * @param served every repository instance that answered a call, in order
     * @return the stub
     */
    private static CardRepository fixtureRepository(List<String> rows,
                                                    Map<RepositoryOperation, ForcedOutcome> forced,
                                                    List<CardRepository> served) {
        return fixtureRepository(rows, forced, served, image -> { });
    }

    /**
     * The fixture-backed repository, with every accepted rewrite reported to an observer.
     *
     * <p>The observer is how a controller case observes the dataset without the adapter having to guess:
     * a rewrite that the repository accepted is a row the run changed, and one it refused is not. Only
     * accepted rewrites are reported, because {@code 9200-WRITE-PROCESSING}'s lock-error and
     * rewrite-error arms leave the record staged and the dataset untouched.
     *
     * @param rows     the mutable seeded rows, in key order
     * @param forced   the outcomes the case forces, by operation
     * @param served   every repository call, for the no-second-repository assertions
     * @param onRewrite called with the stored image of each accepted rewrite
     * @return the repository
     */
    private static CardRepository fixtureRepository(List<String> rows,
                                                    Map<RepositoryOperation, ForcedOutcome> forced,
                                                    List<CardRepository> served,
                                                    java.util.function.Consumer<String> onRewrite) {
        CardRepository repository = Mockito.mock(CardRepository.class, unstubbed -> {
            throw new UnsupportedOperationException("COCRDUPC called CardRepository."
                    + unstubbed.getMethod().getName() + ", for which it has no statement. Its file "
                    + "verbs are the EXEC CICS READ at app/cbl/COCRDUPC.cbl:1355-1365, the READ ... "
                    + "UPDATE at :1427-1436 and the REWRITE at :1477-1483 - there is no WRITE, no "
                    + "DELETE and no browse anywhere in the program - so a call to anything else is a "
                    + "translation reaching for a verb the source does not contain.");
        });
        // doAnswer rather than when(...).thenAnswer(...): the latter would invoke the method in order to
        // record the stub, which the strict default answer above would turn into a failure during setup.
        Mockito.doAnswer(read -> {
            served.add(repository);
            return readByKey(rows, read.getArgument(0), forced.get(RepositoryOperation.READ));
        }).when(repository).readByCardNumber(ArgumentMatchers.anyString());
        Mockito.doAnswer(read -> {
            served.add(repository);
            return readByKey(rows, read.getArgument(0),
                    forced.get(RepositoryOperation.READ_FOR_UPDATE));
        }).when(repository).readForUpdateByCardNumber(ArgumentMatchers.anyString());
        Mockito.doAnswer(read -> {
            served.add(repository);
            return readByAccountKey(rows, read.getArgument(0));
        }).when(repository).readByAccountIdViaAltIndex(ArgumentMatchers.anyString());
        Mockito.doAnswer(rewrite -> {
            served.add(repository);
            CardRepository.CardWriteResult result = rewriteRow(rows, rewrite.getArgument(0),
                    forced.get(RepositoryOperation.REWRITE));
            if (result.outcome() == FileStatus.Outcome.OK) {
                onRewrite.accept(((CardRecord) rewrite.getArgument(0))
                        .encodeToImage(StandardCharsets.US_ASCII));
            }
            return result;
        }).when(repository).rewrite(ArgumentMatchers.any());
        return repository;
    }

    /**
     * Answers a keyed read of {@code CARDDAT} from the seeded rows, or the outcome the case forced.
     *
     * @param rows       the seeded rows
     * @param cardNumber the sixteen-character key moved into the RIDFLD
     * @param forced     the forced outcome, or {@code null} to answer from the data
     * @return the read outcome
     */
    private static CardRepository.CardReadResult readByKey(List<String> rows, String cardNumber,
                                                           ForcedOutcome forced) {
        if (forced != null) {
            return switch (forced.outcome()) {
                case OK -> rowKeyed(rows, cardNumber)
                        .map(image -> CardRepository.CardReadResult.normal(
                                CardRecord.decodeImage(image, FIXTURE_CHARSET), image))
                        .orElseGet(CardRepository.CardReadResult::notFound);
                case NOT_FOUND -> CardRepository.CardReadResult.notFound();
                case END_OF_FILE -> CardRepository.CardReadResult.endOfFile();
                case DUPLICATE -> rowKeyed(rows, cardNumber)
                        .map(image -> CardRepository.CardReadResult.duplicateKey(
                                CardRecord.decodeImage(image, FIXTURE_CHARSET), image))
                        .orElseGet(CardRepository.CardReadResult::notFound);
                case OTHER -> CardRepository.CardReadResult.reportedFailure(
                        forced.resp() == null ? 16 : forced.resp(),
                        forced.resp2() == null ? 0 : forced.resp2());
            };
        }
        return rowKeyed(rows, cardNumber)
                .map(image -> CardRepository.CardReadResult.normal(
                        CardRecord.decodeImage(image, FIXTURE_CHARSET), image))
                .orElseGet(CardRepository.CardReadResult::notFound);
    }

    /**
     * Answers a read through the {@code CARDAIX} alternate index - a second finder on the same base
     * cluster, never a second dataset (gate <strong>G45</strong>).
     *
     * @param rows            the seeded rows
     * @param accountIdDigits the eleven-digit account key
     * @return the first seeded row whose account matches, or {@code NOTFND}
     */
    private static CardRepository.CardReadResult readByAccountKey(List<String> rows,
                                                                  String accountIdDigits) {
        for (String image : rows) {
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
     * Replaces the row the record's key addresses, so the final-state channel reports real mutation.
     *
     * @param rows   the seeded rows, mutated in place
     * @param record the record the unit staged
     * @param forced the forced outcome, or {@code null} to let the rewrite succeed
     * @return the write outcome
     */
    private static CardRepository.CardWriteResult rewriteRow(List<String> rows, CardRecord record,
                                                             ForcedOutcome forced) {
        if (forced != null && forced.outcome() != FileStatus.Outcome.OK) {
            return CardRepository.CardWriteResult.reportedFailure(
                    forced.resp() == null ? 16 : forced.resp(),
                    forced.resp2() == null ? 0 : forced.resp2());
        }
        String image = record.encodeToImage(FIXTURE_CHARSET);
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).startsWith(record.cardNum())) {
                rows.set(index, image);
                return CardRepository.CardWriteResult.normal();
            }
        }
        return CardRepository.CardWriteResult.reportedFailure(13, 0);
    }

    /**
     * The seeded row whose primary key matches, comparing the full sixteen bytes.
     *
     * @param rows       the seeded rows
     * @param cardNumber the key, already at its declared width
     * @return the matching row image, or empty
     */
    private static Optional<String> rowKeyed(List<String> rows, String cardNumber) {
        if (cardNumber == null || cardNumber.length() != CardRecord.CARD_NUM_LENGTH) {
            return Optional.empty();
        }
        for (String image : rows) {
            if (image.startsWith(cardNumber)) {
                return Optional.of(image);
            }
        }
        return Optional.empty();
    }

    /**
     * A {@link CardUpdateService} over the given repository and a real transaction boundary.
     *
     * @param repository the fixture-backed card file
     * @return the service, constructed through its own two-argument constructor
     */
    private static CardUpdateService serviceOver(CardRepository repository) {
        return new CardUpdateService(repository, unitOfWork());
    }

    /**
     * A unit of work over a real transaction manager and a real single connection.
     *
     * <p>Deliberately not a stand-in, for the reason given on {@link #serviceUnit}. An in-memory database
     * is used because the subject is the boundary - which is the framework's behaviour - rather than any
     * deployment driver's. The URL is unique per call, so no two cases can see each other's connection
     * and there is no shared mutable state (practice <strong>B9</strong>).
     *
     * @return a unit of work whose {@code execute} opens a genuine transaction
     */
    private static DatasetUnitOfWork unitOfWork() {
        SingleConnectionDataSource source = new SingleConnectionDataSource(
                "jdbc:h2:mem:cocrdupc-parity-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "", true);
        source.setSuppressClose(true);
        DataSource dataSource = source;
        return new DatasetUnitOfWork(new JdbcTransactionManager(dataSource));
    }

    // =================================================================================================
    // Record, screen and expectation builders. Every one of them composes from the copybook's declared
    // widths rather than from a pasted literal, so an offset can be read off the call site.
    // =================================================================================================

    /**
     * Composes a 150-byte {@code CARD-RECORD} image from its {@code CVACT02Y} spans.
     *
     * @param cardNum      {@code CARD-NUM PIC X(16)}
     * @param acctId       {@code CARD-ACCT-ID PIC 9(11)}, already at width
     * @param cvv          {@code CARD-CVV-CD PIC 9(03)}, already at width
     * @param embossedName {@code CARD-EMBOSSED-NAME PIC X(50)}, padded here
     * @param expiry       {@code CARD-EXPIRAION-DATE PIC X(10)} - the copybook's misspelling, kept
     * @param status       {@code CARD-ACTIVE-STATUS PIC X(01)}
     * @return the image, exactly {@link CardRecord#RECORD_LENGTH} characters
     */
    private static String row(String cardNum, String acctId, String cvv, String embossedName,
                              String expiry, String status) {
        String image = picX(cardNum, CardRecord.CARD_NUM_LENGTH)
                + picX(acctId, CardRecord.CARD_ACCT_ID_LENGTH)
                + picX(cvv, CardRecord.CARD_CVV_CD_LENGTH)
                + picX(embossedName, CardRecord.CARD_EMBOSSED_NAME_LENGTH)
                + picX(expiry, CardRecord.CARD_EXPIRAION_DATE_LENGTH)
                + picX(status, CardRecord.CARD_ACTIVE_STATUS_LENGTH)
                + " ".repeat(CardRecord.FILLER_LENGTH);
        if (image.length() != CardRecord.RECORD_LENGTH) {
            throw new IllegalStateException("A CARD-RECORD image composed from CVACT01Y's spans came "
                    + "out " + image.length() + " characters wide where the copybook declares "
                    + CardRecord.RECORD_LENGTH + ". The composition above is the copybook read out "
                    + "loud, so a mismatch here is a transcription error in this class.");
        }
        return image;
    }

    /**
     * The 150-byte image {@code 9200-WRITE-PROCESSING} stages, with the CVV the source always produces.
     *
     * <p>{@code 000} is not a parameter. {@code CCUP-NEW-CVV-CD} is never assigned anywhere in the
     * program, so the redefinition hop at {@code :1464-1465} yields zero on every successful rewrite, and
     * a builder that let a case choose otherwise would let a case assert something the source cannot do.
     *
     * @param cardNum      {@code CARD-UPDATE-NUM}, from {@code CCUP-NEW-CARDID} at {@code :1462}
     * @param acctId       {@code CARD-UPDATE-ACCT-ID}, from {@code CC-ACCT-ID-N} at {@code :1463}
     * @param embossedName {@code CARD-UPDATE-EMBOSSED-NAME}, from {@code :1466}
     * @param expiry       {@code CARD-UPDATE-EXPIRAION-DATE}, composed by the {@code STRING} at
     *                     {@code :1467-1474}
     * @param status       {@code CARD-UPDATE-ACTIVE-STATUS}, from {@code :1475}
     * @return the image, exactly 150 characters
     */
    private static String stagedImage(String cardNum, String acctId, String embossedName,
                                      String expiry, String status) {
        return row(cardNum, acctId, "000", embossedName, expiry, status);
    }

    /**
     * A {@code MOVE} to {@code PIC X(n)}: the leftmost {@code n} bytes, space-padded on the right.
     *
     * @param value  the sending item
     * @param length the receiver's declared width
     * @return the value at exactly that width
     */
    private static String picX(String value, int length) {
        String source = value == null ? "" : value;
        return source.length() >= length ? source.substring(0, length)
                : source + " ".repeat(length - source.length());
    }

    /**
     * The {@code CCUP-OLD-DETAILS} snapshot that a clean check needs for the given stored row.
     *
     * <p>The embossed name is folded to upper case, because {@code :1499-1501} folds the record's copy
     * before comparing and a snapshot that was not folded would differ - which is exactly what
     * {@code case06} asserts.
     *
     * @param storedImage the 150-byte row as {@code CARDDAT} holds it
     * @return the snapshot, in the {@link DetailGroup#OLD} group
     */
    private static CardDetails foldedSnapshotOf(String storedImage) {
        CardRecord stored = CardRecord.decodeImage(storedImage, FIXTURE_CHARSET);
        return snapshotOf(stored, new FixedWidthCodec(FIXTURE_CHARSET));
    }

    /**
     * The snapshot {@code 1000-SEND-MAP}'s read leaves behind for a record, as {@code :1345-1358} builds
     * it.
     *
     * @param stored the record
     * @param codec  the codec, which renders the record's {@code PIC 9(03)} CVV into the snapshot's
     *               {@code PIC X(3)} form so that like is compared with like
     * @return the snapshot, in the {@link DetailGroup#OLD} group
     */
    private static CardDetails snapshotOf(CardRecord stored, FixedWidthCodec codec) {
        CardRecord folded = CardUpdateService.foldEmbossedName(stored);
        return new CardDetails(DetailGroup.OLD,
                codec.movePic9(stored.cardAcctId(), CardDetails.ACCTID_LENGTH),
                picX(stored.cardNum(), CardDetails.CARDID_LENGTH),
                codec.movePic9(stored.cardCvvCd(), CardDetails.CVV_CD_LENGTH),
                picX(folded.cardEmbossedName(), CardDetails.CRDNAME_LENGTH),
                folded.cardExpiraionDateYear(),
                folded.cardExpiraionDateMonth(),
                folded.cardExpiraionDateDay(),
                picX(folded.cardActiveStatus(), CardDetails.CRDSTCD_LENGTH));
    }

    /**
     * The {@code CCUP-NEW-DETAILS} group as {@code 1100-RECEIVE-MAP} leaves it.
     *
     * <p>The CVV is spaces and cannot be otherwise: the {@code COCRDUP} map has no CVV field, and
     * {@code :578-638} populates the other seven items and not this one.
     *
     * @param acctid  {@code CCUP-NEW-ACCTID}
     * @param cardid  {@code CCUP-NEW-CARDID}
     * @param crdname {@code CCUP-NEW-CRDNAME}, already at its fifty-character width
     * @param crdstcd {@code CCUP-NEW-CRDSTCD}
     * @param expmon  {@code CCUP-NEW-EXPMON}
     * @param expyear {@code CCUP-NEW-EXPYEAR}
     * @param expday  {@code CCUP-NEW-EXPDAY}
     * @return the group, in the {@link DetailGroup#NEW} group
     */
    private static CardDetails newDetailsOf(String acctid, String cardid, String crdname,
                                            String crdstcd, String expmon, String expyear,
                                            String expday) {
        return new CardDetails(DetailGroup.NEW, picX(acctid, CardDetails.ACCTID_LENGTH),
                picX(cardid, CardDetails.CARDID_LENGTH), "   ",
                picX(crdname, CardDetails.CRDNAME_LENGTH), expyear, expmon, expday, crdstcd);
    }

    /**
     * An expectation of one rewritten row, named field by field <em>and</em> as a whole image.
     *
     * <p>Both, not either. The differ requires an expectation to account for every byte of the layout and
     * reports the shortfall when it does not, on the grounds that a byte no expectation covers is a byte
     * that can be wrong while the diff count is zero - a {@code FILLER} written as zeroes rather than
     * spaces being the obvious way for that to happen. Naming the fields as well makes a failure report
     * say which one differed instead of only that the images did.
     *
     * @param writeIndex the 0-based <em>write position</em>, not a dataset row index. A single rewrite is
     *                   write 0 whichever row it replaced
     * @param fields     the fields to name individually
     * @param image      the whole 150-byte record, which covers the reserved span the fields do not
     * @return the expectation
     */
    private static List<ExpectedRecord> rewriteAt(int writeIndex, Map<String, String> fields,
                                                  String image) {
        return List.of(new ExpectedRecord(CARDDAT, writeIndex, fields, image));
    }

    /** @return the empty write channel that every abandoned rewrite must produce */
    private static List<ExpectedRecord> noRewrite() {
        return List.of();
    }

    /** @return the seeded rows with the row at {@code rowIndex} replaced */
    private static List<String> replaceRow(int rowIndex, String image) {
        List<String> rows = new ArrayList<>(SEED_ROWS);
        rows.set(rowIndex, image);
        return List.copyOf(rows);
    }

    /** @return a final-state expectation asserting the whole image of every row, FILLER included */
    private static List<ExpectedRecord> finalStateOf(List<String> rows) {
        List<ExpectedRecord> expectations = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            expectations.add(new ExpectedRecord(CARDDAT, index, Map.of(), rows.get(index)));
        }
        return List.copyOf(expectations);
    }

    /** @return the cleared {@code WS-RETURN-MSG} that a successful pass leaves, at its 75-byte width */
    private static List<EmittedMessage> blankReturnMessage() {
        return List.of(new EmittedMessage(MessageChannel.DISPLAY_LINE,
                CardUpdateService.RETURN_MESSAGE_OFF));
    }

    /** @return a {@code WS-RETURN-MSG} expectation, space-padded to the declared {@code PIC X(75)} */
    private static List<EmittedMessage> returnMessage(String text) {
        return List.of(new EmittedMessage(MessageChannel.DISPLAY_LINE,
                picX(text, CardUpdateService.RETURN_MESSAGE_LENGTH)));
    }

    // =================================================================================================
    // Screen expectation builders, for the six controller cases.
    // =================================================================================================

    /**
     * The prompt screen as the <em>third</em> {@code EVALUATE} arm paints it, with no map received.
     *
     * <p>{@code :502-511} performs {@code INITIALIZE WS-THIS-PROGCOMMAREA} and then
     * {@code 3000-SEND-MAP} directly. It never performs {@code 1000-PROCESS-INPUTS}, so
     * {@code 1100-RECEIVE-MAP} never runs and none of the seven writable {@code CCUP-NEW-} items is ever
     * populated - every one of them is still {@code LOW-VALUES} when {@code 3200-SETUP-SCREEN-VARS}
     * moves it to the screen. That is why all seven output items here are NUL-filled at their declared
     * widths rather than blank: {@code LOW-VALUES} is {@code 0x00}, not a space, and the two are
     * different bytes on the wire.
     *
     * @return the seventeen output items
     */
    private static Map<String, String> unreceivedPromptScreen() {
        Map<String, String> fields = new LinkedHashMap<>(screenHeader());
        fields.put("ACCTSIDO", lowValues("ACCTSID"));
        fields.put("CARDSIDO", lowValues("CARDSID"));
        fields.put("CRDNAMEO", lowValues("CRDNAME"));
        fields.put("CRDSTCDO", lowValues("CRDSTCD"));
        fields.put("EXPMONO", lowValues("EXPMON"));
        fields.put("EXPYEARO", lowValues("EXPYEAR"));
        fields.put("EXPDAYO", lowValues("EXPDAY"));
        fields.put("INFOMSGO", picX(INFO_PROMPT_FOR_KEYS, MAP_FIELD_WIDTHS.get("INFOMSG")));
        fields.put("ERRMSGO", picX("", MAP_FIELD_WIDTHS.get("ERRMSG")));
        fields.put("FKEYSO", lowValues("FKEYS"));
        fields.put("FKEYSCO", lowValues("FKEYSC"));
        return Map.copyOf(fields);
    }

    /**
     * The prompt screen as {@code WHEN OTHER} paints it, after the map has been received and edited.
     *
     * <p>{@code :535-542} performs {@code 1000-PROCESS-INPUTS} - and therefore
     * {@code 1100-RECEIVE-MAP} - before deciding and sending, so the two search keys carry what the user
     * supplied, or the {@code '*'} marker {@code CSSETATY} substitutes for a blank one. The five
     * card-detail items are still {@code LOW-VALUES}, because no record has been fetched to fill them.
     *
     * <p>The contrast with {@link #unreceivedPromptScreen()} is the whole point of running both:
     * {@code case16} and {@code case20} take the arm that does not receive, {@code case17} through
     * {@code case19} take the arm that does, and the two arms paint measurably different screens.
     *
     * @param acctsid   {@code ACCTSIDO} at its eleven-character width
     * @param cardsid   {@code CARDSIDO} at its sixteen-character width
     * @param errorText the {@code WS-RETURN-MSG} text {@code COMMON-RETURN} moves to the screen
     * @return the seventeen output items
     */
    private static Map<String, String> receivedPromptScreen(String acctsid, String cardsid,
                                                            String errorText) {
        Map<String, String> fields = new LinkedHashMap<>(screenHeader());
        fields.put("ACCTSIDO", acctsid);
        fields.put("CARDSIDO", cardsid);
        fields.put("CRDNAMEO", lowValues("CRDNAME"));
        fields.put("CRDSTCDO", lowValues("CRDSTCD"));
        fields.put("EXPMONO", lowValues("EXPMON"));
        fields.put("EXPYEARO", lowValues("EXPYEAR"));
        fields.put("EXPDAYO", lowValues("EXPDAY"));
        fields.put("INFOMSGO", picX(INFO_PROMPT_FOR_KEYS, MAP_FIELD_WIDTHS.get("INFOMSG")));
        fields.put("ERRMSGO", picX(errorText, MAP_FIELD_WIDTHS.get("ERRMSG")));
        fields.put("FKEYSO", lowValues("FKEYS"));
        fields.put("FKEYSCO", lowValues("FKEYSC"));
        return Map.copyOf(fields);
    }

    /**
     * The six header items every send carries, identical on every path.
     *
     * <p>{@code TRNNAMEO} is {@code LIT-THISTRANID}, moved at {@code :380}; {@code PGMNAMEO} is
     * {@code LIT-THISPGM}; the two title lines are {@code COTTL01Y}'s literals, centred as that copybook
     * centres them; and the date and time are the pinned clock's, which is the only reason they are
     * assertable at all.
     *
     * @return the six header output items
     */
    private static Map<String, String> screenHeader() {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("TRNNAMEO", THIS_TRANID);
        header.put("TITLE01O", picX("      AWS Mainframe Modernization", 40));
        header.put("CURDATEO", "07/19/22");
        header.put("PGMNAMEO", THIS_PGM);
        header.put("TITLE02O", picX("              CardDemo", 40));
        header.put("CURTIMEO", "23:12:33");
        return header;
    }

    /**
     * {@code LOW-VALUES} at a field's declared width - {@code 0x00} repeated, not spaces.
     *
     * <p>COBOL's {@code LOW-VALUES} figurative constant is the lowest value in the collating sequence,
     * which is {@code 0x00} in both EBCDIC and ASCII. A field left at {@code LOW-VALUES} and a field
     * moved to {@code SPACES} occupy the same number of bytes and look identical in a log, so the
     * distinction has to be asserted rather than eyeballed.
     *
     * @param stem the field stem
     * @return the field's declared width of NUL characters
     */
    private static String lowValues(String stem) {
        return "\u0000".repeat(MAP_FIELD_WIDTHS.get(stem));
    }

    /**
     * The seventeen {@code xxxC} colour items of a screen with nothing highlighted.
     *
     * <p>Sixteen at {@code DFHDFCOL} and {@code EXPDAYC} at {@code DFHBMDAR}, which {@code :1285} moves
     * unconditionally, so the expiry day is dark on every send this program makes.
     *
     * @return the seventeen attribute items
     */
    private static Map<String, String> defaultColours() {
        Map<String, String> colours = new LinkedHashMap<>();
        for (String stem : MAP_FIELDS) {
            colours.put(stem + "C", "EXPDAY".equals(stem) ? "DFHBMDAR" : "DFHDFCOL");
        }
        return Map.copyOf(colours);
    }

    /**
     * The seventeen {@code xxxC} colour items with the named fields reddened by {@code CSSETATY}.
     *
     * <p>More than one field can be in error on a single pass: {@code 1200-EDIT-MAP-INPUTS:621-627}
     * performs {@code 1210-EDIT-ACCOUNT} and then {@code 1220-EDIT-CARD} unconditionally, and each sets
     * its own filter flag, so the highlight matrix reddens each independently.
     *
     * @param erroredStems the field stems whose colour items carry {@code DFHRED}
     * @return the seventeen attribute items
     */
    private static Map<String, String> erroredColours(String... erroredStems) {
        Map<String, String> colours = new LinkedHashMap<>(defaultColours());
        for (String erroredStem : erroredStems) {
            colours.put(erroredStem + "C", "DFHRED");
        }
        return Map.copyOf(colours);
    }

    // =================================================================================================
    // Commarea projection. One conversion each way, so a case declares a NavigationContext and the
    // differ sees the field names app/cpy/COCOM01Y.cpy spells.
    // =================================================================================================

    /**
     * Renders a {@link NavigationContext} as the {@code CARDDEMO-COMMAREA} field images a case declares.
     *
     * <p>All sixteen fields, always. {@link FieldDiffer} compares the navigation map in both directions,
     * so a partial expectation would fail on the fields it omitted rather than pass on the ones it
     * declared - and rightly: conversation state travels in the payload, which makes every one of these
     * fields part of the observable response (rule <strong>R6</strong>).
     *
     * @param context the context
     * @return the sixteen fields keyed as the copybook names them
     */
    private static Map<String, String> navigationImage(NavigationContext context) {
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
        return Map.copyOf(image);
    }

    /**
     * Rebuilds a {@link NavigationContext} from the field images a case declared.
     *
     * @param declared the declared fields; a field left out keeps the initial value
     *                 {@link NavigationContext#empty()} gives it
     * @return the context
     */
    private static NavigationContext navigationFrom(Map<String, String> declared) {
        NavigationContext initial = NavigationContext.empty();
        if (declared.isEmpty()) {
            return initial;
        }
        return new NavigationContext(
                text(declared, NavigationContext.FROM_TRANID_FIELD, initial.fromTranid()),
                text(declared, NavigationContext.FROM_PROGRAM_FIELD, initial.fromProgram()),
                text(declared, NavigationContext.TO_TRANID_FIELD, initial.toTranid()),
                text(declared, NavigationContext.TO_PROGRAM_FIELD, initial.toProgram()),
                text(declared, NavigationContext.USER_ID_FIELD, initial.userId()),
                text(declared, NavigationContext.USER_TYPE_FIELD, initial.userType()),
                (int) number(declared, NavigationContext.PGM_CONTEXT_FIELD, initial.pgmContext()),
                (int) number(declared, NavigationContext.CUST_ID_FIELD, initial.custId()),
                text(declared, NavigationContext.CUST_FNAME_FIELD, initial.custFname()),
                text(declared, NavigationContext.CUST_MNAME_FIELD, initial.custMname()),
                text(declared, NavigationContext.CUST_LNAME_FIELD, initial.custLname()),
                number(declared, NavigationContext.ACCT_ID_FIELD, initial.acctId()),
                text(declared, NavigationContext.ACCT_STATUS_FIELD, initial.acctStatus()),
                number(declared, NavigationContext.CARD_NUM_FIELD, initial.cardNum()),
                text(declared, NavigationContext.LAST_MAP_FIELD, initial.lastMap()),
                text(declared, NavigationContext.LAST_MAPSET_FIELD, initial.lastMapset()));
    }

    /** @return the declared text, or the fallback when the field was not declared */
    private static String text(Map<String, String> declared, String field, String fallback) {
        String value = declared.get(field);
        return value == null ? fallback : value;
    }

    /** @return the declared digits as a number, or the fallback when the field was not declared */
    private static long number(Map<String, String> declared, String field, long fallback) {
        String value = declared.get(field);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Long.parseLong(value.trim());
    }

    /** @return a {@code PIC 9(n)} image: the value zero-filled on the left to the declared width */
    private static String digits(long value, int length) {
        return new FixedWidthCodec(FIXTURE_CHARSET).movePic9(value, length);
    }

    /**
     * The commarea a case supplies as a {@link NavigationContext}, for the two paths that need one before
     * the harness is involved.
     *
     * @return the context {@code case15}, {@code case17}, {@code case18} and {@code case19} arrive with
     */
    private static NavigationContext navigationFromCardList() {
        return NavigationContext.empty()
                .withFromTranid(CARD_LIST_TRANID)
                .withFromProgram(CARD_LIST_PGM)
                .withUserId("USER0001")
                .withUserTypeUser()
                .withPgmReenter()
                .withAcctId(50L)
                .withCardNum(500_024_453_765_740L)
                .withLastMapset(THIS_MAPSET)
                .withLastMap(THIS_MAP);
    }

    // =================================================================================================
    // Request construction and direct controller invocation, for the standalone gates above.
    // =================================================================================================

    /** @return a request carrying the given commarea and nothing else */
    private static CardUpdateRequest requestWith(NavigationContext context) {
        CardUpdateRequest request = new CardUpdateRequest();
        request.setNavigationContext(context);
        return request;
    }

    /** @return a request rebuilt from a case's declared {@link ScreenRequest} */
    private static CardUpdateRequest requestFrom(ScreenRequest declared) {
        return declared.eibcalen() == EIBCALEN_NONE && declared.commarea().isEmpty()
                && declared.mapFields().isEmpty()
                ? null
                : requestFrom(declared.commarea(), declared.mapFields(),
                        programAreaFrom(declared));
    }

    /**
     * Decodes the {@code WS-THIS-PROGCOMMAREA} image a screen request declares, for the call sites that
     * hold a {@link ScreenRequest} rather than an {@link Invocation}.
     *
     * @param declared the case's screen request
     * @return the decoded area, or the {@code INITIALIZE}d one when the case declares no image
     */
    private static CardUpdateRequest.CommArea programAreaFrom(ScreenRequest declared) {
        String image = declared.commarea().get(PROGRAM_AREA_KEY);
        return image == null
                ? CardUpdateRequest.CommArea.initialised()
                : CardUpdateRequest.CommArea.decode(image.getBytes(FIXTURE_CHARSET),
                        new FixedWidthCodec(FIXTURE_CHARSET));
    }

    /**
     * Builds the request the handler receives from a case's commarea and map fields.
     *
     * <p>The seven writable {@code xxxI} items are the ones {@code 1100-RECEIVE-MAP} reads at
     * {@code :578-638}; the header ten are output-only and a case never supplies them.
     *
     * @param commarea  the inbound {@code CARDDEMO-COMMAREA} fields
     * @param mapFields the inbound {@code xxxI} items
     * @return the request, or {@code null} when the case declares neither - the cold start
     */
    private static CardUpdateRequest requestFrom(Map<String, String> commarea,
                                                 Map<String, String> mapFields,
                                                 CardUpdateRequest.CommArea programArea) {
        if (commarea.isEmpty() && mapFields.isEmpty()) {
            return null;
        }
        CardUpdateRequest request = new CardUpdateRequest();
        request.setNavigationContext(navigationFrom(commarea));
        // :404-406 MOVE DFHCOMMAREA(LENGTH OF CARDDEMO-COMMAREA + 1: LENGTH OF WS-THIS-PROGCOMMAREA).
        // The area is a request field in this translation because the conversation is stateless, so the
        // warm turns a case declares are the warm turns the handler sees.
        request.setCommArea(programArea);
        request.setAcctsid(mapFields.get("ACCTSIDI"));
        request.setCardsid(mapFields.get("CARDSIDI"));
        request.setCrdname(mapFields.get("CRDNAMEI"));
        request.setCrdstcd(mapFields.get("CRDSTCDI"));
        request.setExpmon(mapFields.get("EXPMONI"));
        request.setExpyear(mapFields.get("EXPYEARI"));
        request.setExpday(mapFields.get("EXPDAYI"));
        return request;
    }

    /**
     * Constructs the controller and calls its handler once, for the standalone gates.
     *
     * @param request  the request, or {@code null} for a cold start
     * @param eibcalen {@code EIBCALEN}
     * @param aid      the raw {@code EIBAID} byte
     * @return the painted screen
     */
    private static CardUpdateResponse paint(CardUpdateRequest request, int eibcalen, byte aid) {
        return paint(request, eibcalen, aid, KEY_01);
    }

    /**
     * Constructs the controller and calls its handler once for a stated URI key.
     *
     * <p>The key is a parameter because {@code CCUP-CARD-NUM} and the {@code {cardNum}} path variable are
     * one key: a caller that stated a different one in the payload would be stating two, which the
     * handler refuses before it reads anything.
     *
     * @param request        the request, or {@code null} for a cold start
     * @param eibcalen       {@code EIBCALEN}
     * @param aid            the raw {@code EIBAID} byte
     * @param pathCardNumber the sixteen-character key the URI carries
     * @return the painted screen
     */
    private static CardUpdateResponse paint(CardUpdateRequest request, int eibcalen, byte aid,
                                            String pathCardNumber) {
        CardRepository repository = fixtureRepository(new ArrayList<>(SEED_ROWS), Map.of());
        CardUpdateController controller = new CardUpdateController(repository,
                serviceOver(repository), ParityHarness.usAscii().clock(), FIXTURE_CHARSET);
        ScreenResponse<CardUpdateResponse> body = controller.updateCardDetail(pathCardNumber, request,
                null, eibcalen, Byte.toUnsignedInt(aid)).getBody();
        return java.util.Objects.requireNonNull(body, "every path answers with a body").screen();
    }

    /** @return the raw {@code EIBAID} byte a {@code DFHAID} mnemonic names, or {@code DFHENTER} */
    private static byte aidByte(String mnemonic) {
        if (mnemonic == null || mnemonic.isBlank()) {
            return CicsAid.DFHENTER;
        }
        for (Map.Entry<Byte, String> entry : CicsAid.mnemonicsByAid().entrySet()) {
            if (entry.getValue().equals(mnemonic)) {
                return entry.getKey();
            }
        }
        throw new IllegalArgumentException("\"" + mnemonic + "\" is not a DFHAID mnemonic. The "
                + "copybook is IBM-supplied and absent from this repository, so common.CicsAid is the "
                + "single reproduction of it and the permitted names come from there.");
    }

    /**
     * A next-screen token, with blank reported as absent.
     *
     * @param value the carrier's contents at its declared width
     * @return the trimmed token, or {@code null} when nothing was assigned
     */
    private static String token(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

}
