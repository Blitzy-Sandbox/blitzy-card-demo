package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.vsergeychik.carddemo.admin.AdminMenuController;
import com.vsergeychik.carddemo.admin.AdminMenuService;
import com.vsergeychik.carddemo.admin.AdminMenuService.AdminMenuInput;
import com.vsergeychik.carddemo.admin.AdminMenuService.AdminMenuOptionTable;
import com.vsergeychik.carddemo.admin.AdminMenuService.AdminMenuOutcome;
import com.vsergeychik.carddemo.admin.dto.AdminMenuRequest;
import com.vsergeychik.carddemo.admin.dto.AdminMenuResponse;
import com.vsergeychik.carddemo.admin.model.AdminMenuOptions;
import com.vsergeychik.carddemo.admin.model.AdminMenuOptions.AdminMenuOption;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedResponse;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedSend;
import com.vsergeychik.carddemo.parity.ParityCase.EmittedMessage;
import com.vsergeychik.carddemo.parity.ParityCase.MessageChannel;
import com.vsergeychik.carddemo.parity.ParityCase.Termination;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.parity.ParityHarness.DecodedFingerprint;
import com.vsergeychik.carddemo.parity.ParityHarness.Invocation;
import com.vsergeychik.carddemo.parity.ParityHarness.ParityUnit;
import com.vsergeychik.carddemo.parity.ParityHarness.SeededDataset;
import com.vsergeychik.carddemo.parity.ParityHarness.UnitOutcome;
import com.vsergeychik.carddemo.user.model.SecUserRecord;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The twenty-case parity gate for {@code app/cbl/COADM01C.cbl} - the CardDemo administrator menu,
 * CSD transaction {@code CA00}, mapset {@code COADM01}, map {@code COADM1A}, projected as
 * {@code GET /api/admin/menu}.
 *
 * <h2>Where the expected values come from, and where they emphatically do not</h2>
 *
 * <p><strong>This baseline is statically derived, never captured.</strong> Every expected value in
 * {@code src/test/resources/parity/COADM01C/case01.json} through {@code case20.json} was obtained by
 * reading {@code app/cbl/COADM01C.cbl} paragraph by paragraph and cross-checking four independent
 * authorities: the record and table layouts in {@code app/cpy} (offsets and {@code PICTURE}
 * clauses), the screen field widths in {@code app/cpy-bms/COADM01.CPY} and
 * {@code app/bms/COADM01.bms}, the transaction-to-program-to-mapset bindings in
 * {@code app/csd/CARDDEMO.CSD}, and the shipped fixture data. Not one expectation was recorded from
 * a running COBOL program, because no COBOL program can be run in this environment: there is no
 * z/OS, the available compiler has its indexed-file handler disabled, no Language Environment
 * {@code CEE*} service exists, there is no CICS emulator, and the three IBM-supplied copybooks
 * {@code DFHAID}, {@code DFHBMSCA} and {@code DFHATTR} are absent from the repository altogether.
 * That substitution is a documented, escalated deviation from a stated success criterion - risk
 * {@code R-A} - and it is stated here rather than left for a reader to infer, because a statically
 * derived expectation can encode a misreading where a captured one cannot.
 *
 * <p>Three mitigations apply and all three are visible in this file. Every literal is transcribed
 * from a COBOL source and none is computed from the translation, so a case can never agree with the
 * code by construction - {@link #copybookLiteralsAgreeWithTheTranslation()} then compares the two
 * transcriptions against each other, and a disagreement fails loudly rather than silently adopting
 * the translation's opinion. The twenty cases are aimed at the enumerable branch surface rather than
 * spread evenly: the three-term option guard gets one case per term, the ordered
 * {@code EVALUATE EIBAID} one case per arm. And the assertions that could pass vacuously are given
 * teeth explicitly, which is what {@link #shiftingTheOccursBaseBreaksTheMenuExpectations()} is for.
 *
 * <h2>This program touches no dataset at all, and that is asserted rather than assumed</h2>
 *
 * <p>{@code COADM01C} declares no {@code SELECT} and no {@code FD}, and its five {@code EXEC CICS}
 * statements are one {@code RETURN}, one {@code SEND MAP} and one {@code RECEIVE MAP} - there is no
 * {@code READ}, {@code STARTBR}, {@code READNEXT}, {@code WRITE}, {@code REWRITE} or
 * {@code DELETE} anywhere in its 268 lines. So no repository is reachable from the translation and
 * gate {@code G47}, which exercises each {@code FILE STATUS} outcome per repository call site, has
 * no call site to exercise in this package. An absence cannot be proved by writing nothing, so
 * {@code case16} seeds the security file at the eighty bytes {@code app/cpy/CSUSR01Y.cpy} declares -
 * making it present and readable for the whole run - and
 * {@link #noCaseProducesAnyDatasetActivity(ParityCase)} then requires <em>both</em> record channels
 * of every fingerprint to come back empty. Seeding without expecting is the point: a case that
 * merely omitted the dataset could not tell "the unit read nothing" from "nobody looked".
 *
 * <h2>{@code COADM01C} and {@code COMEN01C} are near-twins whose expectations do not transfer</h2>
 *
 * <p>The two programs are 268 and 282 lines, both contain five {@code EXEC CICS} statements, and
 * both drive a twenty-field 167-line mapset - so it is tempting to copy one test onto the other.
 * Their option tables make that wrong. {@code app/cpy/COADM02Y.cpy} is {@code OCCURS 9} with four
 * valued entries and <strong>no</strong> authorisation column, while {@code app/cpy/COMEN02Y.cpy} is
 * {@code OCCURS 12} with ten populated options <strong>and</strong> an {@code X(01)} user-type
 * column that filters what a signed-on user may see. Nothing below is shared with the main-menu
 * program, and nothing below filters an option by user type either: {@code COADM01C} composes the
 * same four lines regardless of who is looking, and it does so because {@code COADM02Y} declares no
 * authorisation column at all rather than because any one case varies the administrator. The four
 * composed lines are pinned by {@code case04} and {@code case05} at the two ends of the active
 * range, and {@code case06} and {@code case13} then pin the five slots past it - {@code case06} from
 * the unwritten side and {@code case13} at the {@code OCCURS} bound itself.
 *
 * <h2>How the unit is reached: no HTTP, no launcher, no container</h2>
 *
 * <p>Sixteen cases construct {@link AdminMenuService} directly and call it as a plain object
 * ({@link UnitKind#SERVICE}); four construct {@link AdminMenuController} and invoke its handler
 * method directly ({@link UnitKind#CONTROLLER_POJO}), which is the only way to observe the six
 * header fields {@code POPULATE-HEADER-INFO} renders and the seventy-eight-byte {@code ERRMSGO} the
 * eighty-byte {@code WS-MESSAGE} is truncated into. There is no {@code MockMvc}, no
 * {@code TestRestTemplate}, no {@code WebTestClient}, no {@code JobLauncher}, no application context
 * and no servlet container anywhere in this file - a parity assertion is about byte layout and
 * ordering, and neither is improved by putting a framework between the assertion and the code.
 * The clock is always the fixed one the case pins, so the date and time header is comparable.
 *
 * <h3>The four imports that are not in this file's declared dependency list</h3>
 *
 * <p>{@link AdminMenuRequest}, {@link AdminMenuResponse}, {@link DatasetBinding} and
 * {@link DatasetBindings} are imported because they are the declared parameter and payload types of
 * the public constructor and the public handler method of two classes that <em>are</em> declared
 * dependencies: {@code AdminMenuService} can only be built by handing it a binding catalogue, and
 * {@code AdminMenuController.getAdminMenu} can only be called by handing it a request and only
 * answers with a screen. Naming a declared dependency's own signature is not the same as inventing a
 * dependency, and each of the four was verified present in the module before being named here. Every
 * other type this file touches is reached through {@code var} - the {@code ScreenResponse} envelope
 * and the {@code ScreenMetadata} beside it among them - so the import list is the smallest one that
 * compiles.
 *
 * @see AdminMenuService the translated program, which carries every branch
 * @see AdminMenuController the HTTP adapter, which carries the header and the field projection
 */
@DisplayName("COADM01C parity gate - admin menu, CSD transaction CA00, GET /api/admin/menu")
class COADM01CParityTest {

    // =================================================================================================
    // Section 1 - program identity.
    // =================================================================================================

    /** The program whose {@code parity/COADM01C/} case directory this class is the gate for. */
    private static final String PROGRAM = "COADM01C";

    /** {@code WS-TRANID PIC X(04) VALUE 'CA00'} - {@code app/cbl/COADM01C.cbl:37}. */
    private static final String TRANSACTION_ID = "CA00";

    /** {@code MAPSET('COADM01')} - {@code app/cbl/COADM01C.cbl:181}. */
    private static final String MAPSET_NAME = "COADM01";

    /** {@code MAP('COADM1A')} - {@code app/cbl/COADM01C.cbl:180}. */
    private static final String MAP_NAME = "COADM1A";

    /** {@code MOVE 'COSGN00C'} - {@code app/cbl/COADM01C.cbl:83}, {@code :97} and {@code :163}. */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    // =================================================================================================
    // Section 2 - literals transcribed from the COBOL sources.
    //
    // Every constant below is copied from a source file named in its comment, character by character,
    // and NOT read out of the Java translation. That is what stops a case agreeing with the code by
    // construction: if the translation mis-transcribed a copybook, these literals disagree with it and
    // copybookLiteralsAgreeWithTheTranslation() says so by name.
    // =================================================================================================

    /** {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4} - {@code app/cpy/COADM02Y.cpy:20}. */
    private static final int ACTIVE_OPTION_COUNT = 4;

    /** {@code CDEMO-ADMIN-OPT OCCURS 9 TIMES} - {@code app/cpy/COADM02Y.cpy:45}. */
    private static final int OCCURS_TABLE_SIZE = 9;

    /** {@code OPTN001I} to {@code OPTN012I} - {@code app/cpy-bms/COADM01.CPY:60-126}. */
    private static final int MENU_LINE_COUNT = 12;

    /** {@code OPTN00nO PIC X(40)} - {@code app/cpy-bms/COADM01.CPY:182}. */
    private static final int MENU_LINE_LENGTH = 40;

    /** {@code TRNNAMEO PIC X(4)} - {@code app/cpy-bms/COADM01.CPY:146}. */
    private static final int TRN_NAME_LENGTH = 4;

    /** {@code TITLE01O} and {@code TITLE02O PIC X(40)} - {@code app/cpy-bms/COADM01.CPY:152, 170}. */
    private static final int TITLE_LENGTH = 40;

    /** {@code CURDATEO PIC X(8)} - {@code app/cpy-bms/COADM01.CPY:158}, rendered {@code mm/dd/yy}. */
    private static final int CURDATE_LENGTH = 8;

    /** {@code PGMNAMEO PIC X(8)} - {@code app/cpy-bms/COADM01.CPY:164}. */
    private static final int PGM_NAME_LENGTH = 8;

    /** {@code CURTIMEO PIC X(8)} - {@code app/cpy-bms/COADM01.CPY:176}, rendered {@code hh:mm:ss}. */
    private static final int CURTIME_LENGTH = 8;

    /** {@code OPTIONO PIC X(2)} - {@code app/cpy-bms/COADM01.CPY:254}. */
    private static final int OPTION_LENGTH = 2;

    /** {@code WS-MESSAGE PIC X(80)} - {@code app/cbl/COADM01C.cbl:38}. */
    private static final int WS_MESSAGE_LENGTH = 80;

    /** {@code ERRMSGO PIC X(78)} - {@code app/cpy-bms/COADM01.CPY:260}. */
    private static final int ERRMSG_LENGTH = 78;

    /** {@code WS-USRSEC-FILE PIC X(08)} - {@code app/cbl/COADM01C.cbl:39}. */
    private static final int USRSEC_FILE_NAME_LENGTH = 8;

    /**
     * {@code WS-USRSEC-FILE ... VALUE 'USRSEC  '} - {@code app/cbl/COADM01C.cbl:39}, declared and
     * never referenced again. Note the two trailing spaces: the value is the eight-byte
     * <em>logical</em> file name padded to its declared width, not a dataset name.
     */
    private static final String USRSEC_FILE_NAME = "USRSEC  ";

    /** The {@code carddemo.datasets} key {@code application.yml} declares the security file under. */
    private static final String USRSEC_DATASET_KEY = "USRSEC";

    /**
     * {@code carddemo.datasets.USRSEC.dsname} exactly as
     * {@code src/main/resources/application-test.yml} declares it. Quoted from the test profile
     * rather than invented so no third spelling of the same binding exists, and deliberately not a
     * production dataset name - gate {@code G46} forbids one in Java.
     */
    private static final String USRSEC_TEST_DSNAME = "CARDDEMO.TEST.USRSEC.VSAM.KSDS";

    /** {@code CSUSR01Y} - the copybook {@code app/cbl/COADM01C.cbl:58} copies for the dead record. */
    private static final String USRSEC_COPYBOOK = "CSUSR01Y";

    /** {@code RECFM=FB}, as {@code app/jcl/DUSRSECJ.jcl:48} declares the security file. */
    private static final String USRSEC_RECORD_FORMAT = "FB";

    /** The four {@code CDEMO-ADMIN-OPT-NAME} values, {@code PIC X(35)} each. */
    private static final List<String> OPTION_NAMES = List.of(
            "User List (Security)               ",   // app/cpy/COADM02Y.cpy:26
            "User Add (Security)                ",   // app/cpy/COADM02Y.cpy:31
            "User Update (Security)             ",   // app/cpy/COADM02Y.cpy:36
            "User Delete (Security)             ");  // app/cpy/COADM02Y.cpy:41

    /** The four {@code CDEMO-ADMIN-OPT-PGMNAME} values, {@code PIC X(08)} each. */
    private static final List<String> OPTION_PROGRAMS = List.of(
            "COUSR00C",   // app/cpy/COADM02Y.cpy:27
            "COUSR01C",   // app/cpy/COADM02Y.cpy:32
            "COUSR02C",   // app/cpy/COADM02Y.cpy:37
            "COUSR03C");  // app/cpy/COADM02Y.cpy:42

    /** {@code CCDA-TITLE01} - {@code app/cpy/COTTL01Y.cpy}, {@code PIC X(40)}. */
    private static final String CCDA_TITLE01 = "      AWS Mainframe Modernization       ";

    /** {@code CCDA-TITLE02} - {@code app/cpy/COTTL01Y.cpy}, the live line, {@code PIC X(40)}. */
    private static final String CCDA_TITLE02 = "              CardDemo                  ";

    /**
     * {@code CCDA-MSG-INVALID-KEY PIC X(50)} - {@code app/cpy/CSMSG01Y.cpy:20}, the text line 101
     * moves into {@code WS-MESSAGE}. Assembled from its forty characters of text plus the ten spaces
     * that fill the declared width, so the width is visible rather than counted by eye.
     */
    private static final String CCDA_MSG_INVALID_KEY =
            "Invalid key pressed. Please see below..." + "          ";

    /** The literal at {@code app/cbl/COADM01C.cbl:131}, 37 characters. */
    private static final String INVALID_OPTION_MESSAGE = "Please enter a valid option number...";

    /**
     * The two {@code STRING} operands at {@code app/cbl/COADM01C.cbl:149} and {@code :152}, joined.
     * The {@code CDEMO-ADMIN-OPT-NAME} operand between them is commented out at {@code :150-151} and
     * stays out - implementing it would change what the screen says.
     */
    private static final String COMING_SOON_MESSAGE = "This option " + "is coming soon ...";

    /** {@code MOVE DFHGREEN TO ERRMSGC} - {@code app/cbl/COADM01C.cbl:148}. */
    private static final String GREEN_MNEMONIC = "DFHGREEN";

    /** {@code ERRMSG DFHMDF ... COLOR=RED} - {@code app/bms/COADM01.bms:154-157}, the map default. */
    private static final String RED_MNEMONIC = "DFHRED";

    /** {@code CARDDEMO-COMMAREA} totals 160 bytes across the 16 fields of {@code COCOM01Y}. */
    private static final int COMMAREA_LENGTH = 160;

    // =================================================================================================
    // Section 3 - symbolic-map item names, spelled as app/cpy-bms/COADM01.CPY spells them.
    // =================================================================================================

    /** {@code TRNNAMEO PIC X(4)} - {@code app/cpy-bms/COADM01.CPY:146}. */
    private static final String TRNNAME_FIELD = "TRNNAMEO";

    /** {@code TITLE01O PIC X(40)} - {@code app/cpy-bms/COADM01.CPY:152}. */
    private static final String TITLE01_FIELD = "TITLE01O";

    /** {@code CURDATEO PIC X(8)} - {@code app/cpy-bms/COADM01.CPY:158}. */
    private static final String CURDATE_FIELD = "CURDATEO";

    /** {@code PGMNAMEO PIC X(8)} - {@code app/cpy-bms/COADM01.CPY:164}. */
    private static final String PGMNAME_FIELD = "PGMNAMEO";

    /** {@code TITLE02O PIC X(40)} - {@code app/cpy-bms/COADM01.CPY:170}. */
    private static final String TITLE02_FIELD = "TITLE02O";

    /** {@code CURTIMEO PIC X(8)} - {@code app/cpy-bms/COADM01.CPY:176}. */
    private static final String CURTIME_FIELD = "CURTIMEO";

    /** {@code OPTIONO PIC X(2)} - {@code app/cpy-bms/COADM01.CPY:254}. */
    private static final String OPTION_FIELD = "OPTIONO";

    /** {@code ERRMSGO PIC X(78)} - {@code app/cpy-bms/COADM01.CPY:260}. */
    private static final String ERRMSG_FIELD = "ERRMSGO";

    /**
     * {@code ERRMSGC PICTURE X} - {@code app/cpy-bms/COADM01.CPY:256}, the colour attribute byte.
     * The only attribute item {@code COADM01C} ever writes, at line 148.
     */
    private static final String ERRMSG_COLOUR_FIELD = "ERRMSGC";

    /** {@code OPTIONI PIC X(2)} - {@code app/cpy-bms/COADM01.CPY:132}, the one field the program reads. */
    private static final String OPTION_INPUT_FIELD = "OPTIONI";

    // =================================================================================================
    // Section 4 - derived images. Composed from the transcribed literals above, never read back from
    // the translation, and each checked against its declared width the moment it is built.
    // =================================================================================================

    /**
     * An {@code OPTN00nO} line the program never writes, as
     * {@code MOVE LOW-VALUES TO COADM1AO} at {@code app/cbl/COADM01C.cbl:89} leaves it: forty
     * {@code X'00'} characters.
     *
     * <p>Note which statement this is <em>not</em>. {@code MOVE SPACES TO WS-ADMIN-OPT-TXT} at
     * {@code :231} targets a {@code WORKING-STORAGE} item, the composition buffer - not a map field. The
     * {@code MOVE ... TO OPTN00nO} statements live inside the {@code EVALUATE} arms at {@code :238-261}
     * and run only for a subscript the loop reaches, so a line past
     * {@code CDEMO-ADMIN-OPT-COUNT} and a line whose arm is {@code WHEN OTHER / CONTINUE} are
     * both simply never written. An earlier revision of this constant cited {@code :231} and expected
     * spaces, conflating the buffer with the field.
     */
    private static final String BLANK_MENU_LINE = ScreenFieldImage.unpainted(MENU_LINE_LENGTH);

    /** {@code MOVE SPACES TO WS-MESSAGE} - {@code app/cbl/COADM01C.cbl:79}, eighty spaces. */
    private static final String BLANK_WS_MESSAGE = spaces(WS_MESSAGE_LENGTH);

    /** {@code MOVE SPACES TO ERRMSGO} - {@code app/cbl/COADM01C.cbl:80}, seventy-eight spaces. */
    private static final String BLANK_ERRMSG = spaces(ERRMSG_LENGTH);

    /**
     * {@code OPTIONO} as {@code MOVE LOW-VALUES TO COADM1AO} leaves it - {@code :89}, so {@code X'00'}
     * at its declared width rather than spaces. {@code MOVE WS-OPTION TO OPTIONO} at {@code :125} is
     * the only writer, and it is inside {@code PROCESS-ENTER-KEY}.
     */
    private static final String BLANK_OPTION = ScreenFieldImage.unpainted(OPTION_LENGTH);

    /**
     * The twelve {@code OPTN00nO} lines a full paint produces: four composed by
     * {@code BUILD-MENU-OPTIONS} and eight left as the spaces the map initialises them to, because
     * {@code CDEMO-ADMIN-OPT-COUNT} is 4 and the {@code EVALUATE} at {@code :238-261} has arms only
     * for subscripts 1 to 10.
     */
    private static final List<String> PAINTED_MENU_LINES = paintedMenuLines();

    /**
     * Every eighty-byte image {@code COADM01C} can leave in {@code WS-MESSAGE}, and there are exactly
     * four: the spaces line 79 initialises it to, the option rejection at line 131, the invalid-key
     * text at line 101, and the coming-soon text lines 149 to 153 compose. Used to prove the direction
     * of the narrowing at line 177 rather than merely its width.
     */
    private static final List<String> PRODUCIBLE_WS_MESSAGES = List.of(
            BLANK_WS_MESSAGE,
            picX(INVALID_OPTION_MESSAGE, WS_MESSAGE_LENGTH),
            picX(CCDA_MSG_INVALID_KEY, WS_MESSAGE_LENGTH),
            picX(COMING_SOON_MESSAGE, WS_MESSAGE_LENGTH));

    /**
     * Lower-cased fragments of the type names a data-access collaborator would carry.
     *
     * <p>Checked against the declared fields of {@link AdminMenuService} because {@code COADM01C} has
     * no {@code SELECT}, no {@code FD} and no file-handling {@code EXEC CICS} command, so any of these
     * on the service would be a capability the program does not have.
     */
    private static final List<String> DATA_ACCESS_TYPE_MARKERS =
            List.of("repository", "jdbctemplate", "datasource", "connection", "entitymanager");

    // =================================================================================================
    // Section 5 - case dispatch. Every one of the twenty case identifiers appears exactly once, and
    // the absence of a default arm is deliberate: a new case that nobody wired up must not run
    // through some fallback adapter and pass.
    // =================================================================================================

    /**
     * The attention identifier byte behind each {@code DFHAID} mnemonic, inverted from
     * {@link CicsAid#mnemonicsByAid()} rather than re-listed, so the two cannot drift.
     *
     * <p>{@code DFHAID} is IBM-supplied and absent from this repository, which makes
     * {@code common.CicsAid} the single reproduction of it. Immutable once built, so this static
     * field holds no mutable state.
     */
    private static final Map<String, Byte> AID_BY_MNEMONIC = aidByMnemonic();

    /** Case identifiers driven through {@link AdminMenuController} as a plain Java object. */
    private static final List<String> CONTROLLER_CASES =
            List.of("case01", "case02", "case15");

    /** Case identifiers driven through {@link AdminMenuService} with the copybook option table. */
    private static final List<String> SERVICE_CASES =
            List.of("case03", "case04", "case05", "case06", "case07", "case09", "case10", "case11",
                    "case12", "case13", "case14", "case19", "case20");

    // =================================================================================================
    // Section 6 - the gate itself.
    // =================================================================================================

    /**
     * The twenty declarative cases, loaded from {@code src/test/resources/parity/COADM01C/}.
     *
     * <p>{@link ParityHarness#casesOf(String)} refuses anything other than exactly
     * {@code case01.json} through {@code case20.json}: a short set fails naming every absent file,
     * and a stray file in the directory fails too, because a case file nothing loads reads in review
     * as though it were part of the gate. So the twenty-case invariant is enforced at load time and
     * not merely asserted afterwards.
     *
     * @return the twenty cases in ascending case order
     */
    static List<ParityCase> cases() {
        return ParityHarness.casesOf(PROGRAM);
    }

    /**
     * Runs one case and requires a diff count of zero - the parity gate, stated per case.
     *
     * <p>The run and the judgement are separate calls rather than the combined
     * {@code judge(case, kind, unit)} so that the fingerprint is available to the assertions that
     * follow: proving both record channels came back empty is the whole of {@code case16}, and a
     * diff count cannot express it.
     *
     * @param parityCase one of the twenty cases
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("produces zero differences against its statically derived expectation")
    void theCaseProducesZeroDifferences(ParityCase parityCase) {
        ParityHarness harness = ParityHarness.usAscii();

        DecodedFingerprint fingerprint =
                harness.run(parityCase, parityCase.unitKind(), unitFor(parityCase));
        DiffResult diff = harness.judge(parityCase, fingerprint);

        assertThat(diff.count())
                .describedAs("%s: the gate requires a diff count of 0 across all twenty cases.%n%s",
                        parityCase.caseId(), diff.render())
                .isZero();

        assertNoDatasetActivity(parityCase, fingerprint);
        assertReturnCodeIsNormal(parityCase, fingerprint);
    }

    // =================================================================================================
    // Section 7 - structural checks on the case set itself. A gate whose cases nobody validates is a
    // gate that can be weakened by editing a fixture, so the fixtures are held to a contract too.
    // =================================================================================================

    /**
     * The case set is exactly twenty, named {@code case01} to {@code case20}, all naming this program.
     *
     * <p>Gate {@code G15}. {@link ParityHarness#casesOf(String)} already refuses a short or a padded
     * set at load time; what is added here is that every loaded case names {@code COADM01C} rather
     * than some other program's, which a copied fixture would get wrong.
     */
    @Test
    @DisplayName("declares exactly twenty cases, case01 to case20, all naming COADM01C")
    void theCaseSetIsExactlyTwentyCasesForThisProgram() {
        List<ParityCase> loaded = cases();

        assertThat(loaded).hasSize(ParityHarness.CASES_PER_PROGRAM);
        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            ParityCase parityCase = loaded.get(ordinal - 1);
            assertThat(parityCase.caseId())
                    .describedAs("case %d must be %s", ordinal, ParityHarness.caseId(ordinal))
                    .isEqualTo(ParityHarness.caseId(ordinal));
            assertThat(parityCase.program())
                    .describedAs("%s names the wrong program, so it was copied from another "
                            + "program's directory", parityCase.caseId())
                    .isEqualTo(PROGRAM);
            assertThat(parityCase.description().length())
                    .describedAs("%s must say what it exercises, citing the COBOL it derives from",
                            parityCase.caseId())
                    .isGreaterThan(120);
        }
    }

    /**
     * Every case is dispatched by name, and the two dispatch lists partition the twenty.
     *
     * <p>{@link #unitFor(ParityCase)} has no fallback arm, so a case nobody wired up throws rather
     * than running through some default adapter and reporting a clean diff for the wrong reason. This
     * test is what turns that throw into a named failure at build time.
     */
    @Test
    @DisplayName("dispatches all twenty cases by name, with no case falling through")
    void everyCaseIsDispatchedByName() {
        List<String> dispatched = new ArrayList<>();
        for (ParityCase parityCase : cases()) {
            assertThat(unitFor(parityCase))
                    .describedAs("%s has no adapter", parityCase.caseId())
                    .isNotNull();
            dispatched.add(parityCase.caseId());
        }

        assertThat(dispatched).hasSize(ParityHarness.CASES_PER_PROGRAM).doesNotHaveDuplicates();
        assertThat(CONTROLLER_CASES).doesNotHaveDuplicates();
        assertThat(SERVICE_CASES).doesNotHaveDuplicates();
        assertThat(CONTROLLER_CASES).doesNotContainAnyElementsOf(SERVICE_CASES);
    }

    /**
     * The declared unit kind matches the adapter that will be used, for every case.
     *
     * <p>{@link ParityHarness#run(ParityCase, UnitKind, ParityUnit)} already refuses a mismatch, but
     * it refuses it one case at a time and only when that case runs. Checking the whole set here
     * turns a mis-declared fixture into one failure that names it.
     */
    @Test
    @DisplayName("declares SERVICE for the seventeen service cases and CONTROLLER_POJO for the three")
    void everyCaseDeclaresTheUnitKindItsAdapterConstructs() {
        for (ParityCase parityCase : cases()) {
            UnitKind expected = CONTROLLER_CASES.contains(parityCase.caseId())
                    ? UnitKind.CONTROLLER_POJO
                    : UnitKind.SERVICE;
            assertThat(parityCase.unitKind())
                    .describedAs("%s is dispatched as %s but declares %s", parityCase.caseId(),
                            expected, parityCase.unitKind())
                    .isEqualTo(expected);
        }
        assertThat(cases().stream().filter(one -> one.unitKind() == UnitKind.CONTROLLER_POJO).count())
                .isEqualTo(CONTROLLER_CASES.size());
    }

    /**
     * Not one case declares a dataset expectation, because {@code COADM01C} performs no I/O.
     *
     * <p>The complement of {@link #assertNoDatasetActivity(ParityCase, DecodedFingerprint)}: that
     * method proves the run produced no dataset activity, and this one proves no case ever asked for
     * any. Together they close the loop - an expectation could not be satisfied by an observation
     * that should not exist, and an observation could not slip past by having been expected.
     */
    @Test
    @DisplayName("declares no write, no final state and no dataset expectation in any case")
    void noCaseDeclaresADatasetExpectation() {
        for (ParityCase parityCase : cases()) {
            assertThat(parityCase.expectedWrites())
                    .describedAs("%s expects a write, but COADM01C has no WRITE, REWRITE or DELETE "
                            + "anywhere in its 268 lines", parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.expectedFinalState())
                    .describedAs("%s expects a final dataset state, but COADM01C opens no dataset",
                            parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.expectedDatasets())
                    .describedAs("%s expects a dataset, but COADM01C creates none",
                            parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.jobParameters())
                    .describedAs("%s declares a job parameter, but COADM01C is an online program "
                            + "reached by transaction CA00 and not by EXEC PGM=", parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.screenRequest().forcedOutcomes())
                    .describedAs("%s forces a repository outcome, but COADM01C has no repository "
                            + "call site to force one at", parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.expectedResponse().cursorField())
                    .describedAs("%s expects a cursor, but COADM01C never moves -1 into an xxxL item",
                            parityCase.caseId())
                    .isNull();
        }
    }

    /**
     * Every field any case pins is a real symbolic-map item, at exactly its declared width.
     *
     * <p>Gate {@code G9}: every payload field must trace to a {@code DFHMDF} definition and every
     * width to the matching {@code xxxI} {@code PICTURE} clause. The differ compares whatever the case
     * declares against whatever the unit produced, so a fixture pinning {@code ERRMSGO} at seventy-six
     * characters, or inventing an {@code OPTN013O}, would fail for a reason that reads like a code
     * defect. Checking the fixtures against the map here turns that into one failure naming the field.
     *
     * <p>The same is done for the attribute items and the communication area: the only attribute
     * {@code COADM01C} writes is {@code ERRMSGC} and the only values it can hold are the map's declared
     * {@code COLOR=RED} and the {@code DFHGREEN} line 148 overrides it with, while every navigation key
     * must be one of the sixteen {@code COCOM01Y} fields at the width that copybook declares - the
     * sixteen widths coming from {@code NavigationContext}'s own constants rather than being retyped.
     */
    @Test
    @DisplayName("pins only real symbolic-map fields, each at exactly its declared width")
    void everyPinnedScreenFieldMatchesItsDeclaredWidth() {
        Map<String, Integer> screenWidths = declaredScreenWidths();
        Map<String, Integer> commareaWidths = declaredCommareaWidths();

        for (ParityCase parityCase : cases()) {
            for (Map.Entry<String, String> field : parityCase.expectedResponse().navigation()
                    .entrySet()) {
                assertThat(commareaWidths)
                        .describedAs("%s pins '%s', which app/cpy/COCOM01Y.cpy does not declare",
                                parityCase.caseId(), field.getKey())
                        .containsKey(field.getKey());
                assertThat(field.getValue().length())
                        .describedAs("%s pins %s at the wrong width", parityCase.caseId(),
                                field.getKey())
                        .isEqualTo(commareaWidths.get(field.getKey()));
            }
            assertThat(parityCase.expectedResponse().navigation())
                    .describedAs("%s must pin all sixteen COMMAREA fields, because the differ compares "
                            + "the navigation context in both directions", parityCase.caseId())
                    .hasSameSizeAs(commareaWidths);

            for (var send : parityCase.expectedResponse().sends()) {
                for (Map.Entry<String, String> field : send.fields().entrySet()) {
                    assertThat(screenWidths)
                            .describedAs("%s pins '%s', which is not one of the twenty labelled "
                                    + "DFHMDF fields of mapset COADM01", parityCase.caseId(),
                                    field.getKey())
                            .containsKey(field.getKey());
                    assertThat(field.getValue().length())
                            .describedAs("%s pins %s at %d character(s)", parityCase.caseId(),
                                    field.getKey(), field.getValue().length())
                            .isEqualTo(screenWidths.get(field.getKey()));
                }
                assertThat(send.attributes())
                        .describedAs("%s: ERRMSGC is the only attribute item COADM01C ever writes, at "
                                + "line 148", parityCase.caseId())
                        .containsOnlyKeys(ERRMSG_COLOUR_FIELD);
                assertThat(send.attributes().get(ERRMSG_COLOUR_FIELD))
                        .describedAs("%s: the map declares COLOR=RED and only line 148 overrides it",
                                parityCase.caseId())
                        .isIn(RED_MNEMONIC, GREEN_MNEMONIC);
            }
        }
    }

    /**
     * The twenty labelled {@code DFHMDF} fields of mapset {@code COADM01} and their declared widths.
     *
     * @return field name to width, in map order
     */
    private static Map<String, Integer> declaredScreenWidths() {
        Map<String, Integer> widths = new LinkedHashMap<>();
        widths.put(TRNNAME_FIELD, TRN_NAME_LENGTH);
        widths.put(TITLE01_FIELD, TITLE_LENGTH);
        widths.put(CURDATE_FIELD, CURDATE_LENGTH);
        widths.put(PGMNAME_FIELD, PGM_NAME_LENGTH);
        widths.put(TITLE02_FIELD, TITLE_LENGTH);
        widths.put(CURTIME_FIELD, CURTIME_LENGTH);
        for (int subscript = 1; subscript <= MENU_LINE_COUNT; subscript++) {
            widths.put(menuLineField(subscript), MENU_LINE_LENGTH);
        }
        widths.put(OPTION_FIELD, OPTION_LENGTH);
        widths.put(ERRMSG_FIELD, ERRMSG_LENGTH);
        return Collections.unmodifiableMap(widths);
    }

    /**
     * The sixteen {@code CARDDEMO-COMMAREA} fields and their declared widths, taken from
     * {@link NavigationContext}'s own constants so no width is retyped here.
     *
     * @return field name to width, in copybook declaration order
     */
    private static Map<String, Integer> declaredCommareaWidths() {
        Map<String, Integer> widths = new LinkedHashMap<>();
        widths.put(NavigationContext.FROM_TRANID_FIELD, NavigationContext.FROM_TRANID_LENGTH);
        widths.put(NavigationContext.FROM_PROGRAM_FIELD, NavigationContext.FROM_PROGRAM_LENGTH);
        widths.put(NavigationContext.TO_TRANID_FIELD, NavigationContext.TO_TRANID_LENGTH);
        widths.put(NavigationContext.TO_PROGRAM_FIELD, NavigationContext.TO_PROGRAM_LENGTH);
        widths.put(NavigationContext.USER_ID_FIELD, NavigationContext.USER_ID_LENGTH);
        widths.put(NavigationContext.USER_TYPE_FIELD, NavigationContext.USER_TYPE_LENGTH);
        widths.put(NavigationContext.PGM_CONTEXT_FIELD, NavigationContext.PGM_CONTEXT_LENGTH);
        widths.put(NavigationContext.CUST_ID_FIELD, NavigationContext.CUST_ID_LENGTH);
        widths.put(NavigationContext.CUST_FNAME_FIELD, NavigationContext.CUST_FNAME_LENGTH);
        widths.put(NavigationContext.CUST_MNAME_FIELD, NavigationContext.CUST_MNAME_LENGTH);
        widths.put(NavigationContext.CUST_LNAME_FIELD, NavigationContext.CUST_LNAME_LENGTH);
        widths.put(NavigationContext.ACCT_ID_FIELD, NavigationContext.ACCT_ID_LENGTH);
        widths.put(NavigationContext.ACCT_STATUS_FIELD, NavigationContext.ACCT_STATUS_LENGTH);
        widths.put(NavigationContext.CARD_NUM_FIELD, NavigationContext.CARD_NUM_LENGTH);
        widths.put(NavigationContext.LAST_MAP_FIELD, NavigationContext.LAST_MAP_LENGTH);
        widths.put(NavigationContext.LAST_MAPSET_FIELD, NavigationContext.LAST_MAPSET_LENGTH);
        return Collections.unmodifiableMap(widths);
    }

    /**
     * The transcribed literals and the translation's own constants agree.
     *
     * <p>Two independent transcriptions of the same copybooks: the constants in section 2 of this
     * file, and the ones the production classes carry. Comparing them is not circular, because
     * neither was derived from the other - if they disagree, one of the two mis-read
     * {@code app/cpy}, and the failure names which field.
     *
     * <p>{@code CCDA-MSG-INVALID-KEY} and {@code CCDA-THANK-YOU} are checked to be
     * <strong>different</strong> as well as individually correct: they are {@code PIC X(50)} and
     * {@code PIC X(40)} respectively, live in different copybooks, and substituting one for the other
     * is exactly the kind of near-miss a byte comparison exists to catch.
     */
    @Test
    @DisplayName("agrees with the translation about every copybook literal it transcribes")
    void copybookLiteralsAgreeWithTheTranslation() {
        assertThat(AdminMenuService.PROGRAM_NAME).isEqualTo(PROGRAM);
        assertThat(AdminMenuService.TRANSACTION_ID).isEqualTo(TRANSACTION_ID);
        assertThat(AdminMenuService.MAPSET_NAME).isEqualTo(MAPSET_NAME);
        assertThat(AdminMenuService.MAP_NAME).isEqualTo(MAP_NAME);
        assertThat(AdminMenuService.SIGNON_PROGRAM).isEqualTo(SIGNON_PROGRAM);
        assertThat(AdminMenuService.MESSAGE_LENGTH).isEqualTo(WS_MESSAGE_LENGTH);
        assertThat(AdminMenuService.OPTION_TEXT_LENGTH).isEqualTo(MENU_LINE_LENGTH);
        assertThat(AdminMenuService.OPTION_LINE_COUNT).isEqualTo(MENU_LINE_COUNT);
        assertThat(AdminMenuService.OPTION_LENGTH).isEqualTo(OPTION_LENGTH);
        assertThat(AdminMenuService.INVALID_OPTION_MESSAGE).isEqualTo(INVALID_OPTION_MESSAGE);
        assertThat(AdminMenuService.COMING_SOON_MESSAGE).isEqualTo(COMING_SOON_MESSAGE);

        assertThat(AdminMenuOptions.ACTIVE_OPTION_COUNT).isEqualTo(ACTIVE_OPTION_COUNT);
        assertThat(AdminMenuOptions.TABLE_SIZE).isEqualTo(OCCURS_TABLE_SIZE);
        assertThat(AdminMenuOptions.OPT_NAME_LENGTH).isEqualTo(OPTION_NAMES.get(0).length());
        for (int subscript = 1; subscript <= ACTIVE_OPTION_COUNT; subscript++) {
            AdminMenuOption entry = AdminMenuOptions.optionBySubscript(subscript).orElseThrow();
            assertThat(entry.adminOptName())
                    .describedAs("CDEMO-ADMIN-OPT-NAME(%d)", subscript)
                    .isEqualTo(OPTION_NAMES.get(subscript - 1));
            assertThat(entry.adminOptPgmName())
                    .describedAs("CDEMO-ADMIN-OPT-PGMNAME(%d)", subscript)
                    .isEqualTo(OPTION_PROGRAMS.get(subscript - 1));
        }

        assertThat(ScreenTitles.CCDA_TITLE01).isEqualTo(CCDA_TITLE01);
        assertThat(ScreenTitles.CCDA_TITLE02).isEqualTo(CCDA_TITLE02);
        assertThat(SystemMessages.CCDA_MSG_INVALID_KEY).isEqualTo(CCDA_MSG_INVALID_KEY);
        assertThat(CCDA_MSG_INVALID_KEY)
                .describedAs("CCDA-MSG-INVALID-KEY is PIC X(50) in app/cpy/CSMSG01Y.cpy while "
                        + "CCDA-THANK-YOU is PIC X(40) in app/cpy/COTTL01Y.cpy; one is never the other")
                .isNotEqualTo(ScreenTitles.CCDA_THANK_YOU)
                .hasSize(50);

        assertThat(BmsAttributes.colourMnemonic(BmsAttributes.DFHRED)).isEqualTo(RED_MNEMONIC);
        assertThat(BmsAttributes.colourMnemonic(BmsAttributes.DFHGREEN)).isEqualTo(GREEN_MNEMONIC);
        assertThat(AdminMenuService.MAP_MESSAGE_COLOUR).isEqualTo(BmsAttributes.DFHRED);
        assertThat(AdminMenuService.COMING_SOON_MESSAGE_COLOUR).isEqualTo(BmsAttributes.DFHGREEN);

        assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(COMMAREA_LENGTH);
        assertThat(SecUserRecord.RECORD_LENGTH).isEqualTo(80);
    }

    /**
     * The off-by-one proof: a shifted {@code OCCURS} base breaks the menu expectations.
     *
     * <p>Gate {@code G33}. {@code app/cbl/COADM01C.cbl:228} is
     * {@code PERFORM VARYING WS-IDX FROM 1 BY 1}, so COBOL subscript 1 addresses the first entry
     * while a Java index of 1 would address the second. An assertion that the first menu line reads
     * {@code 01. User List (Security)} is only worth making if a mis-indexed implementation would
     * fail it, so that implementation is built here - a table whose slots are rotated by one, which
     * is exactly what a 0-based read of a 1-based subscript produces - and every one of the four
     * composed lines is required to differ from the copybook's.
     *
     * <p>The bound is checked in the same way. {@code UNTIL WS-IDX > CDEMO-ADMIN-OPT-COUNT} is
     * inclusive of the count, so a table declaring one option fewer must leave {@code OPTN004O}
     * blank - which is the failure mode an exclusive bound would produce on the real table.
     */
    @Test
    @DisplayName("fails its own menu expectations when the OCCURS base is shifted by one")
    void shiftingTheOccursBaseBreaksTheMenuExpectations() {
        AdminMenuService service = service(ParityHarness.usAscii().codec());

        List<String> correct = service.buildMenuOptions(AdminMenuOptionTable.copybook());
        assertThat(correct)
                .describedAs("the copybook table must compose exactly the four lines this class "
                        + "transcribed from app/cpy/COADM02Y.cpy")
                .isEqualTo(PAINTED_MENU_LINES);

        List<String> shifted = service.buildMenuOptions(rotatedByOne());
        for (int subscript = 1; subscript <= ACTIVE_OPTION_COUNT; subscript++) {
            assertThat(shifted.get(subscript - 1))
                    .describedAs("a 0-based read of the 1-based subscript %d must NOT reproduce the "
                            + "copybook's line; if it does, the menu expectations have no teeth",
                            subscript)
                    .isNotEqualTo(PAINTED_MENU_LINES.get(subscript - 1));
        }

        List<String> shortByOne = service.buildMenuOptions(truncatedByOne());
        assertThat(shortByOne.get(ACTIVE_OPTION_COUNT - 1))
                .describedAs("UNTIL WS-IDX > count is inclusive of the count, so an exclusive bound "
                        + "would leave the last active line blank - which is what this proves would "
                        + "be visible")
                .isEqualTo(BLANK_MENU_LINE);
        assertThat(correct.get(ACTIVE_OPTION_COUNT - 1)).isNotEqualTo(BLANK_MENU_LINE);

        assertThatExceptionOfType(IndexOutOfBoundsException.class)
                .describedAs("COBOL has no subscript 0, so addressing one must fail rather than "
                        + "silently return the first entry")
                .isThrownBy(() -> AdminMenuOptionTable.copybook().optionBySubscript(0));
    }

    // =================================================================================================
    // Section 8 - the adapters. One per shape, chosen by case identifier, each constructing its unit
    // and calling it as a plain object. Assertions that need the unit itself live inside the adapter,
    // where the instance is in scope; an AssertionError raised there is an Error rather than an
    // Exception, so the harness lets it through untouched and the failure reads at its own call site.
    // =================================================================================================

    /**
     * The adapter one case is run through, selected by name.
     *
     * <p>No fallback arm, deliberately: a case identifier nobody wired up throws here instead of
     * running through a default adapter and reporting a clean diff for the wrong reason.
     *
     * @param parityCase the case about to run
     * @return how to construct and call its unit
     * @throws IllegalArgumentException if the case identifier has no adapter
     */
    private static ParityUnit unitFor(ParityCase parityCase) {
        return switch (parityCase.caseId()) {
            case "case01", "case02", "case15" -> COADM01CParityTest::runController;
            case "case03" -> COADM01CParityTest::runSignonDefaultingGuard;
            case "case08" -> COADM01CParityTest::runDummyPrefixBranch;
            case "case16" -> COADM01CParityTest::runWithSecurityFileSeeded;
            case "case17" -> COADM01CParityTest::runWithUnusedWorkingStorageChecked;
            case "case18" -> COADM01CParityTest::runThreeTimesForStatelessness;
            case "case04", "case05", "case06", "case07", "case09", "case10", "case11",
                    "case12", "case13", "case14", "case19",
                    "case20" -> COADM01CParityTest::runService;
            default -> throw new IllegalArgumentException("Case " + parityCase.caseId()
                    + " of " + PROGRAM + " has no adapter. Every case is dispatched by name and there "
                    + "is no fallback, because a case running through some default adapter would "
                    + "report a diff count of zero for a run nobody chose.");
        };
    }

    /**
     * The service adapter: construct {@link AdminMenuService}, hand it the three values
     * {@code COADM01C} consults, and record what it produced.
     *
     * <p>Also asserts, per case, the one thing the differ cannot see on a transfer path. Lines 117 to
     * 125 normalise {@code OPTIONI} and echo the result into {@code OPTIONO}, but an
     * {@code EXEC CICS XCTL} sends no map - so on those paths there is no {@code ScreenSend} to carry
     * the echoed value and it is checked here instead.
     *
     * @param invocation the seeded inputs, the pinned clock and the codec
     * @return {@code null}, meaning the recorder holds the observation
     */
    private static UnitOutcome runService(Invocation invocation) {
        AdminMenuService service = service(invocation.codec());
        AdminMenuInput input = inputOf(invocation);

        AdminMenuOutcome outcome = service.handle(input);

        assertServiceInvariants(service, input, outcome);
        recordServiceOutcome(invocation, outcome);
        return null;
    }

    /**
     * {@code case03}: the {@code LOW-VALUES} term of the combined relation at
     * {@code app/cbl/COADM01C.cbl:162}.
     *
     * <p>Runs the {@code EIBCALEN = 0} path exactly as {@link #runService(Invocation)} does - lines 82
     * to 84 divert to {@code RETURN-TO-SIGNON-SCREEN}, and the transfer that produces is the
     * observation - and then drives the relation itself with the {@code CDEMO-TO-PROGRAM} image the
     * case declares. That second step belongs here rather than in the case's expectation because the
     * relation's two terms are not both reachable through {@code handle}: line 97 has already written
     * a name on the one path that arrives carrying a communication area, and the cold-start path
     * arrives with the un-{@code VALUE}d working-storage area, which the source holds as binary zeros
     * and this projection's {@code NavigationContext#empty()} presents as spaces.
     * {@link AdminMenuService#resolveSignonTarget(String)} is public for exactly that reason, and this
     * is the adapter that uses it.
     *
     * <p>The declared image is required to be all low-values at the field's declared width, so the
     * fixture cannot quietly degrade into the spaces {@code case01} declares and collapse the pair
     * into one case. Both terms are then required to converge on the single target the run named, and
     * a half-and-half image is required to satisfy neither - which is what stops a translation that
     * had replaced the relation with one blank test from passing both halves of the pair.
     *
     * @param invocation the seeded inputs, the pinned clock and the codec
     * @return {@code null}, meaning the recorder holds the observation
     */
    private static UnitOutcome runSignonDefaultingGuard(Invocation invocation) {
        AdminMenuService service = service(invocation.codec());
        AdminMenuInput input = inputOf(invocation);

        AdminMenuOutcome outcome = service.handle(input);

        assertServiceInvariants(service, input, outcome);
        assertThat(outcome.nextProgramCarriesCommarea())
                .describedAs("the XCTL at 165-167 specifies NO COMMAREA, unlike the one at 142-145, "
                        + "and an absent commarea is the condition COSGN00C's own EIBCALEN = 0 test "
                        + "looks for")
                .isFalse();

        String declared = invocation.commarea().get(NavigationContext.TO_PROGRAM_FIELD);
        assertThat(declared)
                .describedAs("%s exists to drive the LOW-VALUES term of line 162, so it must declare "
                        + "%s as X'00' at every one of its declared bytes; spaces are case01's input "
                        + "and would collapse the two cases into one", invocation.caseId(),
                        NavigationContext.TO_PROGRAM_FIELD)
                .isEqualTo(ScreenFieldImage.unpainted(NavigationContext.TO_PROGRAM_LENGTH));

        String target = picX(SIGNON_PROGRAM, NavigationContext.TO_PROGRAM_LENGTH);
        assertThat(service.resolveSignonTarget(declared))
                .describedAs("term one: a CDEMO-TO-PROGRAM of binary zeros defaults to 'COSGN00C' at "
                        + "line 163")
                .isEqualTo(target);
        assertThat(service.resolveSignonTarget(spaces(NavigationContext.TO_PROGRAM_LENGTH)))
                .describedAs("term two, which is the term this projection's cold-start area takes and "
                        + "the one case01 pins; the two converge, and that convergence is why the pair "
                        + "carries one expectation between them")
                .isEqualTo(target);
        assertThat(picX(outcome.nextProgram(), NavigationContext.TO_PROGRAM_LENGTH))
                .describedAs("and the target both terms produce is the program the run transferred to")
                .isEqualTo(target);

        String halfAndHalf = ScreenFieldImage.unpainted(NavigationContext.TO_PROGRAM_LENGTH / 2)
                + spaces(NavigationContext.TO_PROGRAM_LENGTH / 2);
        assertThat(service.resolveSignonTarget(halfAndHalf))
                .describedAs("= LOW-VALUES needs every byte zero and = SPACES every byte a space, so a "
                        + "field that is half of each satisfies neither term and is transferred to "
                        + "unchanged")
                .isEqualTo(halfAndHalf);

        recordServiceOutcome(invocation, outcome);
        return null;
    }

    /**
     * {@code case08}: the {@code 'DUMMY'} five-byte-prefix branch at
     * {@code app/cbl/COADM01C.cbl:138}.
     *
     * <p>{@code app/cpy/COADM02Y.cpy} names no program beginning {@code DUMMY}, so the branch cannot
     * be reached from the copybook table at all - and it must still be exercised, because dead and
     * unreachable code is preserved rather than cleaned up. The table handed in therefore differs from
     * the copybook's first entry in <strong>nothing but the program name</strong>: same option number,
     * same {@code PIC X(35)} name, so the program name is the only variable under test and the
     * composed {@code OPTN001O} line is byte-identical to the real one.
     *
     * @param invocation the seeded inputs, the pinned clock and the codec
     * @return {@code null}, meaning the recorder holds the observation
     */
    private static UnitOutcome runDummyPrefixBranch(Invocation invocation) {
        AdminMenuService service = service(invocation.codec());
        AdminMenuInput input = inputOf(invocation);
        AdminMenuOptionTable dummyTable = singleEntryTable(
                AdminMenuOption.of(1, OPTION_NAMES.get(0), "DUMMY001"));

        AdminMenuOutcome outcome = service.handle(input, dummyTable);
        assertServiceInvariants(service, input, outcome);

        assertThat(dummyTable.optionBySubscript(1).orElseThrow().adminOptPgmName())
                .describedAs("line 138 reference-modifies the first five characters, so the prefix is "
                        + "what matters and the remaining three bytes are ordinary name characters")
                .startsWith(AdminMenuService.DUMMY_PROGRAM_PREFIX)
                .hasSize(OPTION_PROGRAMS.get(0).length());
        assertThat(outcome.hasNextProgram())
                .describedAs("line 138 found 'DUMMY', so the XCTL at 142-145 is skipped entirely")
                .isFalse();
        assertThat(outcome.screenPainted())
                .describedAs("lines 147-154 are reachable only here, and line 154 sends the map")
                .isTrue();
        assertThat(outcome.messageColourOverridden())
                .describedAs("line 148 MOVE DFHGREEN TO ERRMSGC overrides the mapset's COLOR=RED, and "
                        + "this is the only statement in the program that does")
                .isTrue();
        assertThat(outcome.errFlgImage())
                .describedAs("WS-ERR-FLG is untouched on this path: a skipped transfer is a "
                        + "successful outcome, not a rejection")
                .isEqualTo(AdminMenuService.ERR_FLG_OFF);
        assertThat(outcome.message())
                .describedAs("lines 149-153 STRING the two live operands only; the "
                        + "CDEMO-ADMIN-OPT-NAME operand at 150-151 is commented out and stays out")
                .isEqualTo(picX(COMING_SOON_MESSAGE, WS_MESSAGE_LENGTH));
        assertThat(outcome.optionLine(1))
                .describedAs("the injected entry differs from the copybook's only in its program "
                        + "name, so the composed line must be byte-identical to the real one")
                .isEqualTo(PAINTED_MENU_LINES.get(0));

        recordServiceOutcome(invocation, outcome);
        return null;
    }

    /**
     * {@code case16}: the security file is seeded and available, and nothing touches it.
     *
     * <p>Reads the seeded rows before and after the run and requires them identical, then requires the
     * translation to hold no data-access collaborator at all - which is what makes the emptiness of
     * both record channels a property of the code rather than of this particular input.
     *
     * @param invocation the seeded inputs, the pinned clock and the codec
     * @return {@code null}, meaning the recorder holds the observation
     */
    private static UnitOutcome runWithSecurityFileSeeded(Invocation invocation) {
        SeededDataset usrsec = invocation.dataset(USRSEC_DATASET_KEY);
        assertThat(usrsec.recordLength())
                .describedAs("app/cpy/CSUSR01Y.cpy declares SEC-USER-DATA at 80 bytes, so the seed "
                        + "must have been padded from the 57 characters app/jcl/DUSRSECJ.jcl carries")
                .isEqualTo(SecUserRecord.RECORD_LENGTH);
        List<String> before = List.copyOf(usrsec.rows());

        AdminMenuService service = service(invocation.codec());
        AdminMenuInput input = inputOf(invocation);
        AdminMenuOutcome outcome = service.handle(input);
        assertServiceInvariants(service, input, outcome);

        assertThat(usrsec.rows())
                .describedAs("COADM01C opens no dataset, so every seeded row must be exactly as it "
                        + "was seeded")
                .isEqualTo(before);
        assertNoDataAccessCollaborator(service);

        recordServiceOutcome(invocation, outcome);
        return null;
    }

    /**
     * {@code case17}: the two declarations {@code COADM01C} never references.
     *
     * <p>{@code COPY CSUSR01Y} at {@code app/cbl/COADM01C.cbl:58} and {@code WS-USRSEC-FILE} at
     * {@code :39} are both dead, and both survive the translation because a declaration is part of
     * what the program is. The record is checked blank before and after, so "never read" is asserted
     * as "never changed and never disclosed" rather than assumed.
     *
     * @param invocation the seeded inputs, the pinned clock and the codec
     * @return {@code null}, meaning the recorder holds the observation
     */
    private static UnitOutcome runWithUnusedWorkingStorageChecked(Invocation invocation) {
        AdminMenuService service = service(invocation.codec());

        SecUserRecord beforeRun = service.secUserData();
        assertThat(beforeRun)
                .describedAs("SEC-USER-DATA has no VALUE clause, so an initialised copy is blank")
                .isEqualTo(SecUserRecord.blank());

        AdminMenuInput input = inputOf(invocation);
        AdminMenuOutcome outcome = service.handle(input);
        assertServiceInvariants(service, input, outcome);

        assertThat(service.secUserData())
                .describedAs("not one of SEC-USER-DATA's six fields is read or written by COADM01C, "
                        + "so the record is unchanged by a whole invocation")
                .isEqualTo(beforeRun)
                .isEqualTo(SecUserRecord.blank());
        assertThat(service.usrSecFileName())
                .describedAs("WS-USRSEC-FILE VALUE 'USRSEC  ' - the logical file name padded to its "
                        + "declared PIC X(08), with the source literal's two trailing spaces intact")
                .isEqualTo(USRSEC_FILE_NAME)
                .hasSize(USRSEC_FILE_NAME_LENGTH);
        assertThat(observableStrings(outcome))
                .describedAs("neither dead declaration may leak onto the screen: WS-USRSEC-FILE is "
                        + "never moved anywhere, so its value must appear in no field of the response")
                .noneMatch(image -> image.contains(USRSEC_DATASET_KEY));

        recordServiceOutcome(invocation, outcome);
        return null;
    }

    /**
     * {@code case18}: statelessness, driven by running the same invocation three times.
     *
     * <p>Twice against one service instance and once against a freshly constructed one. All three
     * outcomes must be equal: a translation holding {@code WS-ERR-FLG}, {@code WS-MESSAGE} or
     * {@code WS-OPTION} in a field of a singleton bean would leak the first call into the second, and
     * a translation keeping the communication area in a server-side session would have nothing to
     * return. Only the first outcome is reported, because one invocation produces one response.
     *
     * @param invocation the seeded inputs, the pinned clock and the codec
     * @return {@code null}, meaning the recorder holds the observation
     */
    private static UnitOutcome runThreeTimesForStatelessness(Invocation invocation) {
        AdminMenuService shared = service(invocation.codec());
        AdminMenuInput input = inputOf(invocation);

        AdminMenuOutcome first = shared.handle(input);
        AdminMenuOutcome second = shared.handle(input);
        AdminMenuOutcome third = service(invocation.codec()).handle(input);

        assertServiceInvariants(shared, input, first);
        assertThat(second)
                .describedAs("a second call on the same instance must produce the identical outcome; "
                        + "anything else means per-request working storage became a field")
                .isEqualTo(first);
        assertThat(third)
                .describedAs("a fresh instance must produce the identical outcome; anything else "
                        + "means the outcome depended on something outside the request")
                .isEqualTo(first);
        assertThat(first.navigationContext())
                .describedAs("the communication area travels in the payload, which is the only reason "
                        + "it is comparable at all")
                .isNotNull();
        assertNoMutableState(shared);

        recordServiceOutcome(invocation, first);
        return null;
    }

    /**
     * The controller adapter: construct {@link AdminMenuController} over the service and the pinned
     * clock, and call its handler method directly as a plain Java object.
     *
     * <p>An {@code eibcalen} of zero is passed as an <strong>absent request body</strong>, because
     * that is how {@code GET /api/admin/menu} expresses {@code IF EIBCALEN = 0} at
     * {@code app/cbl/COADM01C.cbl:82} - the parameter is {@code required = false} and a plain
     * {@code GET} arrives {@code null}. No {@code MockMvc} and no servlet container is involved; the
     * method is invoked the way any other method is.
     *
     * @param invocation the seeded inputs, the pinned clock and the codec
     * @return {@code null}, meaning the recorder holds the observation
     */
    private static UnitOutcome runController(Invocation invocation) {
        AdminMenuService service = service(invocation.codec());
        AdminMenuController controller = new AdminMenuController(service, invocation.clock());

        var screen = controller
                .getAdminMenu(invocation.eibcalen() == 0 ? null : requestOf(invocation))
                .screen();

        assertThat(screen.trnName()).isEqualTo(TRANSACTION_ID);
        assertThat(screen.pgmName()).isEqualTo(PROGRAM);
        assertThat(screen.title01()).isEqualTo(CCDA_TITLE01);
        assertThat(screen.title02()).isEqualTo(CCDA_TITLE02);
        assertThat(screen.errMsg())
                .describedAs("MOVE WS-MESSAGE TO ERRMSGO at line 177 narrows PIC X(80) to PIC X(78)")
                .hasSize(ERRMSG_LENGTH);
        assertThat(screen.screenMetadata().resetAllOutputFields())
                .describedAs("MOVE LOW-VALUES TO COADM1AO at line 89 runs on the first-entry path "
                        + "alone, and the flag must reach the client beside the screen rather than "
                        + "inside it")
                .isEqualTo(screen.resetAllOutputFields());
        assertThat(screen.screenMetadata().messageColour())
                .describedAs("ERRMSGC travels as metadata, unsigned, because the attribute IBM "
                        + "documents as X'F2' is a negative Java byte")
                .isEqualTo(Byte.toUnsignedInt(screen.messageColour()));
        assertThat(screen.screenMetadata().cursorField())
                .describedAs("COADM01C never moves -1 into an xxxL item, so no cursor is requested")
                .isNull();
        assertTruncationKeptTheLeadingBytes(screen.errMsg());

        boolean transferred = !isBlank(screen.nextProgram());
        List<ObservedSend> sends = transferred
                ? List.of()
                : List.of(new ObservedSend(controllerSendFields(screen), colour(screen.messageColour())));

        recordObservation(invocation,
                observedResponse(screen.nextProgram(), screen.nextMapset(), screen.nextMap(),
                        navigationImages(invocation.codec(), screen.navigationContext()), sends,
                        transferred),
                new EmittedMessage(MessageChannel.SCREEN_ERRMSG_78, screen.errMsg()));
        return null;
    }

    // =================================================================================================
    // Section 9 - projecting an outcome onto the observation types the differ compares.
    // =================================================================================================

    /**
     * Records what a service run produced: the response, the eighty-byte {@code WS-MESSAGE} and the
     * normal return code.
     *
     * <p>{@code WS-MESSAGE} is reported on {@link MessageChannel#WS_MESSAGE_80} rather than inside the
     * send, because it is {@code WORKING-STORAGE} at {@code app/cbl/COADM01C.cbl:38} and not a screen
     * field: the screen field is the seventy-eight-byte {@code ERRMSGO} that line 177 moves it into,
     * and only the controller produces that. Keeping the two on separate channels is what makes the
     * narrowing visible - {@code case11} pins the eighty and {@code case15} the seventy-eight, and a
     * comparison across channels is refused by the differ.
     *
     * @param invocation the run being recorded into
     * @param outcome    what the service produced
     */
    private static void recordServiceOutcome(Invocation invocation, AdminMenuOutcome outcome) {
        boolean transferred = outcome.hasNextProgram();
        assertThat(transferred)
                .describedAs("exactly one of EXEC CICS XCTL and EXEC CICS SEND MAP happens on any one "
                        + "path through COADM01C, so a run reporting both or neither has lost the "
                        + "shape of the program")
                .isNotEqualTo(outcome.screenPainted());

        List<ObservedSend> sends = outcome.screenPainted()
                ? List.of(new ObservedSend(serviceSendFields(outcome), colour(outcome.messageColour())))
                : List.of();

        recordObservation(invocation,
                observedResponse(outcome.nextProgram(), outcome.mapsetName(), outcome.mapName(),
                        navigationImages(invocation.codec(), outcome.navigationContext()), sends,
                        transferred),
                new EmittedMessage(MessageChannel.WS_MESSAGE_80, outcome.message()));
    }

    /**
     * Writes one response, one emitted line and the return code into the run's recorder.
     *
     * <p>Neither record channel is written to at all, and that is the observation {@code case16}
     * makes: {@code COADM01C} touches no dataset, so an empty {@code writes} and an empty
     * {@code finalState} is the accurate report rather than an omission.
     *
     * @param invocation the run being recorded into
     * @param response   the online response the unit returned
     * @param message    the message image, on the channel whose width it was measured at
     */
    private static void recordObservation(Invocation invocation, ObservedResponse response,
            EmittedMessage message) {
        invocation.recorder()
                .response(response)
                .message(message)
                // EXEC CICS RETURN and EXEC CICS XCTL are both successful terminations; an online
                // transaction sets no step return code, so the normal value is stated rather than
                // defaulted.
                .returnCode(0);
    }

    /**
     * Assembles the observed response, mapping a blank scalar to {@code null}.
     *
     * <p>That mapping is required rather than cosmetic. {@code AdminMenuOutcome} carries an eight-space
     * {@code nextProgram} on a {@code SEND} path and a seven-space mapset and map on a transfer path,
     * because a COBOL {@code PIC X} field is never absent - but {@code ExpectedResponse} validates
     * those three against program-name and map-name patterns that reject spaces, and the differ
     * compares them with {@code Objects.equals}. So a blank field has to arrive as "not stated here",
     * which is what {@code null} means on both sides.
     *
     * <p>The termination follows the same fact from the other direction: an {@code XCTL} transfers
     * control and never reaches the {@code EXEC CICS RETURN} at lines 107 to 110, so exactly one of
     * the two terminations applies and it is decided by whether a successor was named.
     *
     * @param nextProgram the {@code XCTL} target, or spaces
     * @param nextMapset  the mapset the {@code SEND} named, or spaces
     * @param nextMap     the map the {@code SEND} named, or spaces
     * @param navigation  the sixteen {@code CARDDEMO-COMMAREA} field images
     * @param sends       one send for a painted screen, none for a transfer
     * @param transferred whether an {@code XCTL} named a successor
     * @return the observation the differ compares against the case
     */
    private static ObservedResponse observedResponse(String nextProgram, String nextMapset,
            String nextMap, Map<String, String> navigation, List<ObservedSend> sends,
            boolean transferred) {
        return new ObservedResponse(named(nextProgram), named(nextMapset), named(nextMap), navigation,
                sends, null, transferred ? Termination.XCTL : Termination.RETURN_TRANSID);
    }

    /**
     * The thirteen {@code COADM1AO} items a service run produces: the twelve menu lines and the echoed
     * option.
     *
     * <p>{@code ERRMSGO} is deliberately absent. The service produces {@code WS-MESSAGE} at eighty
     * bytes, and manufacturing a seventy-eight-byte screen field from it here would be this test doing
     * the work line 177 does - which is precisely the work {@code case15} exists to check.
     *
     * @param outcome what the service produced
     * @return the send's payload items, keyed by symbolic-map name in map order
     */
    private static Map<String, String> serviceSendFields(AdminMenuOutcome outcome) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int subscript = 1; subscript <= MENU_LINE_COUNT; subscript++) {
            fields.put(menuLineField(subscript), outcome.optionLine(subscript));
        }
        fields.put(OPTION_FIELD, outcome.option());
        return fields;
    }

    /**
     * All twenty {@code COADM1AO} items a controller run produces, in map order.
     *
     * <p>Twenty is the count of name-labelled {@code DFHMDF} fields in {@code app/bms/COADM01.bms},
     * and each width comes from the matching {@code xxxI} item of {@code app/cpy-bms/COADM01.CPY} -
     * the {@code xxxL}, {@code xxxF} and {@code xxxA} items are length, flag and attribute metadata
     * and are not payload, which is why only {@code ERRMSGC} appears and only as an attribute.
     *
     * @param screen the projected screen
     * @return the send's payload items, keyed by symbolic-map name in map order
     */
    private static Map<String, String> controllerSendFields(AdminMenuResponse screen) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(TRNNAME_FIELD, screen.trnName());
        fields.put(TITLE01_FIELD, screen.title01());
        fields.put(CURDATE_FIELD, screen.curDate());
        fields.put(PGMNAME_FIELD, screen.pgmName());
        fields.put(TITLE02_FIELD, screen.title02());
        fields.put(CURTIME_FIELD, screen.curTime());
        List<String> lines = screen.optionLines();
        for (int subscript = 1; subscript <= MENU_LINE_COUNT; subscript++) {
            fields.put(menuLineField(subscript), lines.get(subscript - 1));
        }
        fields.put(OPTION_FIELD, screen.option());
        fields.put(ERRMSG_FIELD, screen.errMsg());
        return fields;
    }

    /**
     * The one attribute item {@code COADM01C} writes, valued with its {@code DFHBMSCA} mnemonic.
     *
     * <p>Named rather than numeric because colour is behaviour in this program, not decoration: the
     * map declares {@code COLOR=RED} and line 148 overrides it with {@code DFHGREEN} on the
     * coming-soon path alone, which is how the screen distinguishes a rejection from a confirmation.
     *
     * @param messageColour the {@code ERRMSGC} byte
     * @return a single-entry attribute map
     */
    private static Map<String, String> colour(byte messageColour) {
        return Map.of(ERRMSG_COLOUR_FIELD, BmsAttributes.colourMnemonic(messageColour));
    }

    // =================================================================================================
    // Section 10 - assertions that hold for every case, and the two branch drives that need every AID
    // or every worked option in one place rather than one case at a time.
    // =================================================================================================

    /**
     * Neither record channel carries anything, for any case.
     *
     * <p>{@code COADM01C} declares no {@code SELECT} and no {@code FD}, and its five
     * {@code EXEC CICS} statements are one {@code RETURN}, one {@code SEND MAP} and one
     * {@code RECEIVE MAP} - so an empty {@code writes} and an empty {@code finalState} is the accurate
     * report of what it did, and the harness explicitly authorises empty channels for a unit that
     * touches no dataset at all. {@code case16} makes the assertion non-vacuous by seeding the
     * security file first.
     *
     * @param parityCase  the case that ran, named in the failure
     * @param fingerprint what the run produced
     */
    private static void assertNoDatasetActivity(ParityCase parityCase,
            DecodedFingerprint fingerprint) {
        assertThat(fingerprint.writes())
                .describedAs("%s: COADM01C has no WRITE, REWRITE or DELETE anywhere in its 268 lines, "
                        + "so nothing may appear on the writes channel", parityCase.caseId())
                .isEmpty();
        assertThat(fingerprint.finalState())
                .describedAs("%s: COADM01C opens no dataset, so no dataset may appear on the final "
                        + "state channel either", parityCase.caseId())
                .isEmpty();
    }

    /**
     * The transaction ended normally, for any case.
     *
     * <p>{@code COADM01C} is reached by CICS transaction {@code CA00} rather than by
     * {@code EXEC PGM=}, contains no {@code CALL 'CEE3ABD'} and sets no {@code RETURN-CODE}: both of
     * its terminations - {@code EXEC CICS RETURN} at lines 107 to 110 and {@code EXEC CICS XCTL} - are
     * successful, so zero is the only value any of the twenty cases may report.
     *
     * @param parityCase  the case that ran, named in the failure
     * @param fingerprint what the run produced
     */
    private static void assertReturnCodeIsNormal(ParityCase parityCase,
            DecodedFingerprint fingerprint) {
        assertThat(fingerprint.returnCode())
                .describedAs("%s: COADM01C is an online program with no abend site and no RETURN-CODE "
                        + "of its own", parityCase.caseId())
                .isZero();
    }

    /**
     * The invariants every service run must satisfy, whichever of {@code COADM01C}'s paths it took.
     *
     * @param service the unit, for its normalisation intermediates
     * @param input   the three values the program consulted
     * @param outcome what the run produced
     */
    private static void assertServiceInvariants(AdminMenuService service, AdminMenuInput input,
            AdminMenuOutcome outcome) {
        assertScreenIdentity(outcome);
        assertOptionEcho(service, input, outcome);
        assertEvaluateArm(input, outcome);
    }

    /**
     * The transaction, mapset and map the run names are the ones the CSD and the source agree on.
     *
     * <p>{@code EXEC CICS RETURN TRANSID(WS-TRANID)} at lines 107 to 110 always names {@code CA00}, so
     * that is asserted on every path. The mapset and map are named only where a map was actually sent:
     * neither {@code XCTL} names one - line 143 names a program and line 166 names a program - so
     * telling a client to paint the screen the program is leaving would be the one answer that is
     * certainly wrong.
     *
     * @param outcome what the run produced
     */
    private static void assertScreenIdentity(AdminMenuOutcome outcome) {
        assertThat(outcome.transactionId())
                .describedAs("RETURN TRANSID(WS-TRANID) at line 108, and DEFINE TRANSACTION(CA00) "
                        + "PROGRAM(COADM01C) at app/csd/CARDDEMO.CSD:327-328")
                .isEqualTo(TRANSACTION_ID);
        if (outcome.screenPainted()) {
            assertThat(outcome.mapsetName()).isEqualTo(MAPSET_NAME);
            assertThat(outcome.mapName()).isEqualTo(MAP_NAME);
        } else {
            assertThat(isBlank(outcome.mapsetName()) && isBlank(outcome.mapName()))
                    .describedAs("neither EXEC CICS XCTL names a map, so a transfer must name none "
                            + "either: which map the successor paints is the successor's decision")
                    .isTrue();
        }
    }

    /**
     * {@code OPTIONO} carries the value the path it was on actually puts there - one of three.
     *
     * <p>Three paths write the echoed option three different ways, and the differences are the
     * assertion:
     * <ul>
     *   <li><strong>First entry</strong> - {@code MOVE LOW-VALUES TO COADM1AO} at line 89 clears every
     *       output field before the paint, and {@code OPTIONO} is one of them, so the field comes back
     *       <em>blank</em> however much the client typed. No option is read and no validation runs on
     *       this path at all.</li>
     *   <li><strong>Re-entry on the {@code DFHENTER} arm</strong> - {@code PROCESS-ENTER-KEY} runs and
     *       line 125 echoes the <em>normalised</em> option, so {@code "7 "} comes back as
     *       {@code "07"}.</li>
     *   <li><strong>Re-entry on any other arm</strong> - the paragraph never runs, nothing writes
     *       {@code OPTIONO}, and the field keeps the image the client re-supplied, so {@code "7 "}
     *       comes back as {@code "7 "}. That is statelessness made visible: the field survives only
     *       because it travelled in the payload.</li>
     * </ul>
     * A translation that normalised eagerly, or that cleared the field on the wrong path, or that held
     * the option in a session, produces the same value on two of the three and fails the other.
     *
     * <p>The expected normalisation is computed from the COBOL rules in {@link #normalisedOption} and
     * not read back from {@link AdminMenuService#normaliseOption(String)}; that method's own
     * intermediates are then checked against it, so the two transcriptions confirm each other.
     *
     * @param service the unit, for its normalisation intermediates
     * @param input   the three values the program consulted
     * @param outcome what the run produced
     */
    private static void assertOptionEcho(AdminMenuService service, AdminMenuInput input,
            AdminMenuOutcome outcome) {
        String asDelivered = picX(input.option(), OPTION_LENGTH);

        if (input.isCommareaPresent() && !input.isReenter()) {
            assertThat(outcome.option())
                    .describedAs("MOVE LOW-VALUES TO COADM1AO at line 89 clears OPTIONO before the "
                            + "first-entry paint, and no option is read on that path")
                    .isEqualTo(BLANK_OPTION);
            return;
        }
        if (!input.isCommareaPresent()
                || !PfKeyResolver.isAid(input.eibAid(), CicsAid.DFHENTER)) {
            assertThat(outcome.option())
                    .describedAs("PROCESS-ENTER-KEY did not run on this path, so line 125 never wrote "
                            + "OPTIONO and the field must still hold the re-supplied image")
                    .isEqualTo(asDelivered);
            return;
        }

        String expectedEcho = normalisedOption(asDelivered);
        assertThat(outcome.option())
                .describedAs("line 125 MOVE WS-OPTION TO OPTIONO echoes the normalised option, so "
                        + "'%s' becomes '%s'", asDelivered, expectedEcho)
                .isEqualTo(expectedEcho);

        var normalisation = service.normaliseOption(input.option());
        assertThat(normalisation.optionEcho()).isEqualTo(expectedEcho);
        assertThat(normalisation.receivedOptionI()).isEqualTo(asDelivered);
        assertThat(normalisation.justifiedOptionX())
                .describedAs("WS-OPTION-X is PIC X(02) JUST RIGHT at line 45, so the sender lands at "
                        + "the rightmost position and the field is space-filled on the LEFT")
                .isEqualTo(justifiedOption(asDelivered));
    }

    /**
     * The arm of {@code EVALUATE EIBAID} the case took is the arm its attention identifier selects.
     *
     * <p>{@code app/cbl/COADM01C.cbl:93-103} is an ordered {@code EVALUATE}: {@code DFHENTER} first,
     * {@code DFHPF3} second, {@code WHEN OTHER} last. The three are mutually exclusive here because
     * the first two compare distinct bytes, so which arm ran is decided by the byte alone - and this
     * check pins the observable consequence of each.
     *
     * <p>It applies only where the {@code EVALUATE} is actually reached. Line 93 sits inside the
     * {@code ELSE} of the re-enter test at line 87 and inside the {@code ELSE} of the
     * {@code EIBCALEN = 0} test at line 82, so on a cold start or a first entry the AID is never read
     * and asserting an arm would be asserting a branch the program did not take.
     *
     * @param input   the three values the program consulted
     * @param outcome what the run produced
     */
    private static void assertEvaluateArm(AdminMenuInput input, AdminMenuOutcome outcome) {
        if (!input.isCommareaPresent() || !input.isReenter()) {
            return;
        }
        byte eibAid = input.eibAid();
        if (PfKeyResolver.isAid(eibAid, CicsAid.DFHENTER)) {
            assertThat(outcome.message())
                    .describedAs("the DFHENTER arm performs PROCESS-ENTER-KEY, which never produces "
                            + "the invalid-key text")
                    .isNotEqualTo(picX(CCDA_MSG_INVALID_KEY, WS_MESSAGE_LENGTH));
            return;
        }
        if (PfKeyResolver.isAid(eibAid, CicsAid.DFHPF3)) {
            assertThat(outcome.nextProgram())
                    .describedAs("line 97 moves 'COSGN00C' into CDEMO-TO-PROGRAM and lines 165-167 "
                            + "transfer to it")
                    .isEqualTo(picX(SIGNON_PROGRAM, NavigationContext.TO_PROGRAM_LENGTH));
            assertThat(outcome.nextProgramCarriesCommarea())
                    .describedAs("the XCTL at 165-167 specifies NO COMMAREA, unlike the one at 142-145")
                    .isFalse();
            return;
        }
        assertThat(outcome.errFlgImage())
                .describedAs("line 100 MOVE 'Y' TO WS-ERR-FLG on the WHEN OTHER arm")
                .isEqualTo(AdminMenuService.ERR_FLG_ON);
        assertThat(outcome.message())
                .describedAs("line 101 moves CCDA-MSG-INVALID-KEY, a PIC X(50) sender, into the "
                        + "PIC X(80) WS-MESSAGE - left-justified, space-padded to eighty")
                .isEqualTo(picX(CCDA_MSG_INVALID_KEY, WS_MESSAGE_LENGTH));
        assertThat(outcome.screenPainted())
                .describedAs("line 102 performs SEND-MENU-SCREEN")
                .isTrue();
    }

    /**
     * All four steps of the option normalisation, over the five inputs that pin it down.
     *
     * <p>{@code app/cbl/COADM01C.cbl:117-125} is a descending test-before scan, a reference-modified
     * {@code MOVE} into a {@code JUSTIFIED RIGHT} receiver, an {@code INSPECT ... REPLACING} and a
     * cross-category {@code MOVE} into {@code PIC 9(02)}. The five rows below are the whole behaviour:
     * a blank field, a leading space that is <em>not</em> relocated because the widths are equal, a
     * trailing space that <em>is</em>, two digits that cross untouched, and a non-digit that survives
     * the move into the numeric field - which is what gives {@code IS NOT NUMERIC} at line 127
     * something to detect. Every expected value is derived from the COBOL rules in
     * {@link #normalisedOption(String)} and {@link #justifiedOption(String)}, not read back from the
     * translation.
     */
    @Test
    @DisplayName("reproduces the four-step option normalisation over its five worked inputs")
    void theOptionNormalisationReproducesAllFourSteps() {
        AdminMenuService service = service(ParityHarness.usAscii().codec());

        record Worked(String received, int wsIdx, String justified, String optionX, boolean numeric) {
        }
        List<Worked> worked = List.of(
                new Worked("  ", 1, "  ", "00", true),
                new Worked(" 3", 2, " 3", "03", true),
                new Worked("3 ", 1, " 3", "03", true),
                new Worked("12", 2, "12", "12", true),
                new Worked("1x", 2, "1x", "1x", false));

        for (Worked row : worked) {
            var normalisation = service.normaliseOption(row.received());
            assertThat(normalisation.wsIdx())
                    .describedAs("WS-IDX after the descending scan over '%s'", row.received())
                    .isEqualTo(row.wsIdx());
            assertThat(normalisation.justifiedOptionX())
                    .describedAs("WS-OPTION-X after the JUST RIGHT move of '%s'", row.received())
                    .isEqualTo(row.justified())
                    .isEqualTo(justifiedOption(picX(row.received(), OPTION_LENGTH)));
            assertThat(normalisation.optionX())
                    .describedAs("WS-OPTION-X after INSPECT REPLACING ALL ' ' BY '0' over '%s'",
                            row.received())
                    .isEqualTo(row.optionX())
                    .isEqualTo(normalisedOption(picX(row.received(), OPTION_LENGTH)));
            assertThat(normalisation.isNumeric())
                    .describedAs("WS-OPTION IS NOT NUMERIC at line 127, for '%s'", row.received())
                    .isEqualTo(row.numeric());
        }
    }

    /**
     * Every {@code DFHAID} byte lands on exactly the arm {@code EVALUATE EIBAID} names for it.
     *
     * <p>Gate {@code G30} asks for the arms in source order with {@code WHEN OTHER} last, and order
     * only means something if the default really is a default. So every mnemonic
     * {@code common.CicsAid} defines is driven here: {@code DFHENTER} must reach
     * {@code PROCESS-ENTER-KEY}, {@code DFHPF3} must transfer to the sign-on program, and
     * <strong>every other AID</strong> - including the ones {@code PfKeyResolver} resolves to a real
     * key token, such as {@code DFHPF12} - must produce the invalid-key message. That last part is the
     * point: {@code COADM01C} does not copy {@code CSSTRPFY}, so resolving the byte to a token and
     * branching on the token would make some other key behave like {@code PF3} in a program that has
     * no such behaviour.
     */
    @Test
    @DisplayName("routes DFHENTER and DFHPF3 to their own arms and every other AID to WHEN OTHER")
    void theEvaluateEibaidArmsAreOrderedAndExhaustive() {
        AdminMenuService service = service(ParityHarness.usAscii().codec());
        NavigationContext reentered = NavigationContext.empty().withPgmReenter();
        String invalidKeyImage = picX(CCDA_MSG_INVALID_KEY, WS_MESSAGE_LENGTH);
        int otherArmCount = 0;

        for (Map.Entry<String, Byte> aid : AID_BY_MNEMONIC.entrySet()) {
            byte eibAid = aid.getValue();
            AdminMenuOutcome outcome =
                    service.handle(new AdminMenuInput(reentered, eibAid, BLANK_OPTION));

            if (eibAid == CicsAid.DFHENTER) {
                assertThat(outcome.message())
                        .describedAs("%s must reach PROCESS-ENTER-KEY, which rejects a blank option "
                                + "with its own message", aid.getKey())
                        .isEqualTo(picX(INVALID_OPTION_MESSAGE, WS_MESSAGE_LENGTH));
            } else if (eibAid == CicsAid.DFHPF3) {
                assertThat(outcome.nextProgram())
                        .describedAs("%s must exit to the sign-on screen", aid.getKey())
                        .isEqualTo(picX(SIGNON_PROGRAM, NavigationContext.TO_PROGRAM_LENGTH));
            } else {
                otherArmCount++;
                assertThat(outcome.message())
                        .describedAs("%s has no arm of its own, so WHEN OTHER must catch it - even "
                                + "though PfKeyResolver resolves it to %s", aid.getKey(),
                                PfKeyResolver.resolve(eibAid))
                        .isEqualTo(invalidKeyImage);
                assertThat(outcome.errFlgImage()).isEqualTo(AdminMenuService.ERR_FLG_ON);
            }
        }

        assertThat(otherArmCount)
                .describedAs("WHEN OTHER must catch every AID but the two named ones; a count of zero "
                        + "would mean this walk drove nothing")
                .isEqualTo(AID_BY_MNEMONIC.size() - 2);
    }

    /**
     * {@code MOVE WS-MESSAGE TO ERRMSGO} kept the leading bytes, not the trailing ones.
     *
     * <p>{@code app/cbl/COADM01C.cbl:177} sends {@code PIC X(80)} to {@code PIC X(78)}, and a COBOL
     * alphanumeric {@code MOVE} fills the receiver from the left and discards the overflow. So the
     * observed field must equal the leading seventy-eight characters of one of the four eighty-byte
     * images {@code COADM01C} can produce, and must not equal that image's trailing seventy-eight -
     * which is what a receiver filled from the right would hold.
     *
     * <p>The negative half is skipped for an all-space message, and deliberately: the leading and the
     * trailing seventy-eight of eighty spaces are the same string, so no direction is observable there.
     * Every non-blank message in this program does distinguish them.
     *
     * @param errMsg the observed {@code ERRMSGO} image
     */
    private static void assertTruncationKeptTheLeadingBytes(String errMsg) {
        List<String> leading = new ArrayList<>();
        List<String> trailing = new ArrayList<>();
        for (String wsMessage : PRODUCIBLE_WS_MESSAGES) {
            leading.add(wsMessage.substring(0, ERRMSG_LENGTH));
            if (!wsMessage.isBlank()) {
                trailing.add(wsMessage.substring(WS_MESSAGE_LENGTH - ERRMSG_LENGTH));
            }
        }

        assertThat(leading)
                .describedAs("ERRMSGO must be the LEADING 78 characters of one of the four 80-byte "
                        + "images COADM01C can put in WS-MESSAGE")
                .contains(errMsg);
        if (!errMsg.isBlank()) {
            assertThat(trailing)
                    .describedAs("a receiver filled from the right would hold the TRAILING 78 "
                            + "characters, shifting every character two places")
                    .doesNotContain(errMsg);
        }
    }

    /**
     * The translation holds no data-access collaborator, so the absence of I/O is structural.
     *
     * <p>Reading the seeded rows back unchanged shows this run touched nothing; this shows no run
     * could. {@code COADM01C} has no {@code SELECT}, no {@code FD} and no file-handling
     * {@code EXEC CICS} command, so a repository, a {@code JdbcTemplate}, a {@code DataSource} or a
     * {@code Connection} on the service would be a capability the program does not have.
     *
     * @param service the unit under test
     */
    private static void assertNoDataAccessCollaborator(AdminMenuService service) {
        for (Field field : service.getClass().getDeclaredFields()) {
            String type = field.getType().getName();
            for (String forbidden : DATA_ACCESS_TYPE_MARKERS) {
                assertThat(type.toLowerCase(Locale.ROOT))
                        .describedAs("field '%s' is a %s, but COADM01C performs no dataset access at "
                                + "all - it declares no SELECT, no FD and no file-handling EXEC CICS "
                                + "command", field.getName(), type)
                        .doesNotContain(forbidden);
            }
        }
    }

    /**
     * The translation holds no mutable state, so two requests cannot see each other.
     *
     * <p>{@code WS-ERR-FLG}, {@code WS-MESSAGE}, {@code WS-OPTION} and {@code WS-IDX} are
     * {@code WORKING-STORAGE} in the COBOL, which in CICS is per-task; as fields of a Spring singleton
     * they would be shared across requests and the second caller would see the first caller's message.
     * Every declared instance field must therefore be {@code final}, and no mutable static may exist.
     *
     * @param service the unit under test
     */
    private static void assertNoMutableState(AdminMenuService service) {
        for (Field field : service.getClass().getDeclaredFields()) {
            assertThat(Modifier.isFinal(field.getModifiers()))
                    .describedAs("field '%s' is not final; COBOL WORKING-STORAGE is per-task and must "
                            + "never become assignable state on a shared bean", field.getName())
                    .isTrue();
            assertThat(field.getType().isArray())
                    .describedAs("field '%s' is an array, whose elements stay writable however final "
                            + "the reference is", field.getName())
                    .isFalse();
        }
    }

    // =================================================================================================
    // Section 11 - construction and image helpers.
    //
    // Every COBOL move rule this class relies on is transcribed here, once, so that no expectation is
    // ever obtained by asking the translation what it thinks the answer is.
    // =================================================================================================

    /**
     * The unit, wired the way the container wires it but with the codec stated explicitly.
     *
     * <p>The codec is shared with the harness so one code page governs the whole run - practice
     * {@code B8}: the charset is always named and never the platform default.
     *
     * @param codec the fixed-width codec the harness is running under
     * @return a fresh service
     */
    private static AdminMenuService service(FixedWidthCodec codec) {
        return new AdminMenuService(usrSecBindings(), codec);
    }

    /**
     * The {@code carddemo.datasets} catalogue holding the one entry the service resolves.
     *
     * <p>{@code AdminMenuService} needs a catalogue for a single reason: {@code WS-USRSEC-FILE} at
     * {@code app/cbl/COADM01C.cbl:39} is resolved from configuration rather than from a literal, so no
     * dataset name is written in Java in the production class. The entry mirrors
     * {@code src/main/resources/application-test.yml} exactly - the same test dataset name, the
     * {@code CSUSR01Y} copybook, the eighty-byte record and the eight-byte key - so the constructor's
     * width check has something real to check against and gate {@code G46} still holds, because no
     * production dataset name appears.
     *
     * @return a catalogue with {@code USRSEC} declared as the test profile declares it
     */
    private static DatasetBindings usrSecBindings() {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(USRSEC_DATASET_KEY, new DatasetBinding(USRSEC_TEST_DSNAME,
                DatasetBinding.KSDS,
                false,
                USRSEC_RECORD_FORMAT,
                null,
                SecUserRecord.RECORD_LENGTH,
                USRSEC_COPYBOOK,
                SecUserRecord.KEY_LENGTH,
                null,
                null,
                null));
        return catalogue;
    }

    /**
     * The three values {@code COADM01C} consults, taken from the case's declared invocation.
     *
     * <p>An {@code eibcalen} of zero becomes an absent communication area, because absence <em>is</em>
     * the representation of {@code IF EIBCALEN = 0} at {@code app/cbl/COADM01C.cbl:82} - a separate
     * flag would be a second source of truth able to contradict the reference beside it. The option is
     * passed through unnormalised, spaces and all, because lines 117 to 125 are what consume it and
     * trimming it here would destroy their input.
     *
     * @param invocation the run
     * @return the service input
     */
    private static AdminMenuInput inputOf(Invocation invocation) {
        byte eibAid = aidByte(invocation);
        String option = invocation.mapFields().getOrDefault(OPTION_INPUT_FIELD, BLANK_OPTION);
        NavigationContext context = commareaContext(invocation);
        return context == null
                ? AdminMenuInput.withoutCommarea(eibAid, option)
                : new AdminMenuInput(context, eibAid, option);
    }

    /**
     * The inbound payload a client continuing the pseudo-conversation would send back.
     *
     * <p>All twenty screen fields are present at their declared widths, because a {@code PIC X} field
     * is never absent, and nineteen of them are spaces: {@code COADM01C} reads {@code OPTIONI} and
     * nothing else, so supplying the rest as blanks reproduces a re-supplied blank screen and
     * simultaneously demonstrates that the program ignores them.
     *
     * @param invocation the run
     * @return the request payload
     */
    private static AdminMenuRequest requestOf(Invocation invocation) {
        return new AdminMenuRequest(spaces(TRANSACTION_ID.length()),
                spaces(CCDA_TITLE01.length()),
                spaces(CURDATE_LENGTH),
                spaces(PROGRAM.length()),
                spaces(CCDA_TITLE02.length()),
                spaces(CURTIME_LENGTH),
                BLANK_MENU_LINE, BLANK_MENU_LINE, BLANK_MENU_LINE, BLANK_MENU_LINE,
                BLANK_MENU_LINE, BLANK_MENU_LINE, BLANK_MENU_LINE, BLANK_MENU_LINE,
                BLANK_MENU_LINE, BLANK_MENU_LINE, BLANK_MENU_LINE, BLANK_MENU_LINE,
                invocation.mapFields().getOrDefault(OPTION_INPUT_FIELD, BLANK_OPTION),
                BLANK_ERRMSG,
                commareaContext(invocation),
                aidByte(invocation));
    }

    /**
     * The communication area the case declared, decoded through the codec rather than by hand.
     *
     * <p>{@code serialise} starts from an initialised record - spaces for every {@code PIC X} span and
     * zeros for every {@code PIC 9} span - and overwrites only the fields the case names, which is
     * exactly what a partially populated {@code CARDDEMO-COMMAREA} holds. An unknown field name is
     * refused by the codec rather than ignored, so a mis-spelled key in a fixture fails loudly.
     *
     * @param invocation the run
     * @return the decoded area, or {@code null} when {@code EIBCALEN} is zero
     */
    private static NavigationContext commareaContext(Invocation invocation) {
        if (invocation.eibcalen() == 0) {
            return null;
        }
        FixedWidthCodec codec = invocation.codec();
        return NavigationContext.fromFixedWidth(codec,
                codec.serialise(NavigationContext.LAYOUT, invocation.commarea()));
    }

    /**
     * The sixteen {@code CARDDEMO-COMMAREA} field images, keyed by the copybook's own names.
     *
     * <p>Produced by encoding the area and decoding it field by field, so the images are the bytes the
     * area actually holds rather than a rendering of the record's Java components: a {@code PIC 9(11)}
     * account identifier reads back zero-filled to eleven digits and a {@code PIC X(25)} name
     * space-padded to twenty-five, which is what the differ compares.
     *
     * @param codec   the run's codec
     * @param context the area as the program left it
     * @return the field images in copybook declaration order
     */
    private static Map<String, String> navigationImages(FixedWidthCodec codec,
            NavigationContext context) {
        return codec.deserialise(NavigationContext.LAYOUT, context.toFixedWidth(codec));
    }

    /**
     * The attention-identifier byte the case declared.
     *
     * <p>Only the {@code EIBCALEN = 0} path may omit it, because line 93 is the program's single
     * {@code EIBAID} reference and that path never reaches it. {@code DFHENTER} stands in there for the
     * same reason the controller's own cold-start request uses it: a terminal always presents some AID,
     * and this is the one the program treats as ordinary.
     *
     * @param invocation the run
     * @return the raw {@code EIBAID} byte
     */
    private static byte aidByte(Invocation invocation) {
        String mnemonic = invocation.aid();
        if (mnemonic == null) {
            assertThat(invocation.eibcalen())
                    .describedAs("only the EIBCALEN = 0 path may omit the AID; every other path "
                            + "reaches the EVALUATE at line 93 and the byte it compares is behaviour")
                    .isZero();
            return CicsAid.DFHENTER;
        }
        Byte eibAid = AID_BY_MNEMONIC.get(mnemonic);
        assertThat(eibAid)
                .describedAs("'%s' is not a DFHAID mnemonic; common.CicsAid is the single reproduction "
                        + "of that IBM-supplied copybook, which is absent from this repository",
                        mnemonic)
                .isNotNull();
        return eibAid;
    }

    /**
     * A response scalar as the differ needs it: the value, or {@code null} when the field is blank.
     *
     * @param image the {@code PIC X} image, possibly all spaces
     * @return the trimmed value, or {@code null} for a field naming nothing
     */
    private static String named(String image) {
        return isBlank(image) ? null : image.trim();
    }

    /**
     * Whether a {@code PIC X} field names nothing.
     *
     * @param image the image
     * @return {@code true} when it is empty or all spaces
     */
    private static boolean isBlank(String image) {
        return image == null || image.isBlank();
    }

    /**
     * The symbolic-map output item for a 1-based menu subscript: subscript 1 is {@code OPTN001O}.
     *
     * @param cobolSubscript from 1 to {@value #MENU_LINE_COUNT}
     * @return the {@code xxxO} item name
     */
    private static String menuLineField(int cobolSubscript) {
        return String.format(Locale.ROOT, "OPTN%03dO", cobolSubscript);
    }

    /**
     * A COBOL alphanumeric {@code MOVE}: fill the receiver from the left, discarding any overflow.
     *
     * @param source the sending field
     * @param width  the receiver's declared width
     * @return exactly {@code width} characters
     */
    private static String picX(String source, int width) {
        return source.length() >= width
                ? source.substring(0, width)
                : source + spaces(width - source.length());
    }

    /**
     * {@code app/cbl/COADM01C.cbl:117-122} and the {@code JUST RIGHT} receiver at line 45.
     *
     * <p>The descending scan is written as the same test-before loop the source uses, rather than as a
     * trim or a last-index search, so the reason the answer is 1 for {@code "3 "} stays visible: the
     * loop sets {@code WS-IDX} from {@code LENGTH OF OPTIONI}, tests, and only then decrements.
     *
     * @param optionI {@code OPTIONI} at its declared width
     * @return {@code WS-OPTION-X} immediately after line 122, before the {@code INSPECT}
     */
    private static String justifiedOption(String optionI) {
        int wsIdx = OPTION_LENGTH;
        while (optionI.charAt(wsIdx - 1) == ' ' && wsIdx != 1) {
            wsIdx--;
        }
        String sent = optionI.substring(0, wsIdx);
        return spaces(OPTION_LENGTH - sent.length()) + sent;
    }

    /**
     * {@code app/cbl/COADM01C.cbl:123}: {@code INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'}.
     *
     * <p>Line 124 then moves the two characters into {@code WS-OPTION PIC 9(02)} and line 125 echoes
     * them into {@code OPTIONO}; both are equal-width moves, so this image is all three fields'.
     *
     * @param optionI {@code OPTIONI} at its declared width
     * @return {@code WS-OPTION-X} after the {@code INSPECT}
     */
    private static String normalisedOption(String optionI) {
        return justifiedOption(optionI).replace(' ', '0');
    }

    /** {@code n} spaces, which is what COBOL leaves in an unset {@code PIC X} span. */
    private static String spaces(int width) {
        return " ".repeat(width);
    }

    /**
     * Every character-valued thing one outcome exposes, for a leak check.
     *
     * <p>The twelve menu lines, the message, the echoed option, the transfer target and the transaction,
     * mapset and map names - which between them is everything of {@code COADM01C}'s state that can
     * reach a terminal.
     *
     * @param outcome what the run produced
     * @return the images, in no particular order
     */
    private static List<String> observableStrings(AdminMenuOutcome outcome) {
        List<String> images = new ArrayList<>();
        for (int subscript = 1; subscript <= MENU_LINE_COUNT; subscript++) {
            images.add(outcome.optionLine(subscript));
        }
        images.add(outcome.message());
        images.add(outcome.option());
        images.add(outcome.nextProgram());
        images.add(outcome.transactionId());
        images.add(outcome.mapsetName());
        images.add(outcome.mapName());
        return List.copyOf(images);
    }

    /**
     * The twelve menu lines a full paint produces, composed from the transcribed copybook literals.
     *
     * @return four composed lines followed by eight blank ones
     */
    private static List<String> paintedMenuLines() {
        List<String> lines = new ArrayList<>(MENU_LINE_COUNT);
        for (int subscript = 1; subscript <= ACTIVE_OPTION_COUNT; subscript++) {
            // app/cbl/COADM01C.cbl:233-236 STRING num, '. ', name DELIMITED BY SIZE into PIC X(40).
            lines.add(picX(String.format(Locale.ROOT, "%02d", subscript) + ". "
                    + OPTION_NAMES.get(subscript - 1), MENU_LINE_LENGTH));
        }
        while (lines.size() < MENU_LINE_COUNT) {
            lines.add(BLANK_MENU_LINE);
        }
        return List.copyOf(lines);
    }

    /**
     * The nine {@code OCCURS} slots of {@code app/cpy/COADM02Y.cpy}, built from this class's own
     * transcription so the shifted variants below are independent of the translation's table.
     *
     * @return four valued slots followed by five empty ones
     */
    private static List<Optional<AdminMenuOption>> copybookSlots() {
        List<Optional<AdminMenuOption>> slots = new ArrayList<>(OCCURS_TABLE_SIZE);
        for (int subscript = 1; subscript <= ACTIVE_OPTION_COUNT; subscript++) {
            slots.add(Optional.of(AdminMenuOption.of(subscript, OPTION_NAMES.get(subscript - 1),
                    OPTION_PROGRAMS.get(subscript - 1))));
        }
        while (slots.size() < OCCURS_TABLE_SIZE) {
            slots.add(Optional.empty());
        }
        return slots;
    }

    /**
     * The table a 0-based read of the 1-based subscript would effectively address: every entry moved
     * one slot earlier, cyclically so the table stays constructible.
     *
     * @return the rotated table, used only to prove the menu expectations have teeth
     */
    private static AdminMenuOptionTable rotatedByOne() {
        List<Optional<AdminMenuOption>> slots = new ArrayList<>(OCCURS_TABLE_SIZE);
        for (int subscript = 1; subscript <= ACTIVE_OPTION_COUNT; subscript++) {
            int rotated = subscript % ACTIVE_OPTION_COUNT + 1;
            slots.add(Optional.of(AdminMenuOption.of(rotated, OPTION_NAMES.get(rotated - 1),
                    OPTION_PROGRAMS.get(rotated - 1))));
        }
        while (slots.size() < OCCURS_TABLE_SIZE) {
            slots.add(Optional.empty());
        }
        return new AdminMenuOptionTable(slots, ACTIVE_OPTION_COUNT);
    }

    /**
     * The copybook table with its active count one lower - what an exclusive loop bound would produce.
     *
     * @return a table offering three options rather than four
     */
    private static AdminMenuOptionTable truncatedByOne() {
        return new AdminMenuOptionTable(copybookSlots(), ACTIVE_OPTION_COUNT - 1);
    }

    /**
     * A table offering exactly one option - the shape {@code case08} needs to reach the
     * {@code 'DUMMY'} branch.
     *
     * @param entry the single valued entry
     * @return a nine-slot table with an active count of one
     */
    private static AdminMenuOptionTable singleEntryTable(AdminMenuOption entry) {
        List<Optional<AdminMenuOption>> slots = new ArrayList<>(OCCURS_TABLE_SIZE);
        slots.add(Optional.of(entry));
        while (slots.size() < OCCURS_TABLE_SIZE) {
            slots.add(Optional.empty());
        }
        return new AdminMenuOptionTable(slots, 1);
    }

    /**
     * Inverts {@link CicsAid#mnemonicsByAid()} so a case's mnemonic can be turned into the byte line 93
     * compares.
     *
     * @return an immutable mnemonic-to-byte map
     */
    private static Map<String, Byte> aidByMnemonic() {
        Map<String, Byte> inverted = new LinkedHashMap<>();
        for (Map.Entry<Byte, String> entry : CicsAid.mnemonicsByAid().entrySet()) {
            inverted.put(entry.getValue(), entry.getKey());
        }
        return Collections.unmodifiableMap(inverted);
    }
}
