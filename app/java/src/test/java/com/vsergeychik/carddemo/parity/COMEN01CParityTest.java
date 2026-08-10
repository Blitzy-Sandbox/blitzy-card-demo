package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.vsergeychik.carddemo.admin.MainMenuController;
import com.vsergeychik.carddemo.admin.MainMenuService;
import com.vsergeychik.carddemo.admin.MainMenuService.MainMenuInput;
import com.vsergeychik.carddemo.admin.MainMenuService.MainMenuOptionTable;
import com.vsergeychik.carddemo.admin.MainMenuService.MainMenuOutcome;
import com.vsergeychik.carddemo.admin.dto.MainMenuRequest;
import com.vsergeychik.carddemo.admin.dto.MainMenuResponse;
import com.vsergeychik.carddemo.admin.model.MenuOptions;
import com.vsergeychik.carddemo.admin.model.MenuOptions.MenuOption;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
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
import java.util.OptionalInt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The twenty-case parity gate for {@code app/cbl/COMEN01C.cbl} - the CardDemo main menu for regular
 * users, CSD transaction {@code CM00}, mapset {@code COMEN01}, map {@code COMEN1A}, projected as
 * {@code GET /api/menu}. This is also the screen {@code user.SignOnService} routes a
 * {@code CDEMO-USRTYP-USER} caller to, from {@code XCTL PROGRAM('COMEN01C')} at
 * {@code app/cbl/COSGN00C.cbl:237}.
 *
 * <h2>Where the expected values come from, and where they emphatically do not</h2>
 *
 * <p><strong>This baseline is statically derived, never captured.</strong> Every expected value in
 * {@code src/test/resources/parity/COMEN01C/case01.json} through {@code case20.json} was obtained by
 * reading {@code app/cbl/COMEN01C.cbl} paragraph by paragraph and cross-checking four independent
 * authorities: the option table and communication area in {@code app/cpy/COMEN02Y.cpy} and
 * {@code app/cpy/COCOM01Y.cpy} (offsets, {@code PICTURE} clauses and {@code VALUE} literals), the
 * screen field widths in {@code app/cpy-bms/COMEN01.CPY} and {@code app/bms/COMEN01.bms}, the
 * transaction-to-program-to-mapset binding in {@code app/csd/CARDDEMO.CSD}, and the message literals
 * in {@code app/cpy/CSMSG01Y.cpy} and {@code app/cpy/COTTL01Y.cpy}. Not one expectation was recorded
 * from a running COBOL program, because no COBOL program can be run in this environment: there is no
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
 * transcriptions against each other and a disagreement fails loudly. The twenty cases are aimed at
 * the enumerable branch surface rather than spread evenly: the three-term option guard gets one case
 * per term, the ordered {@code EVALUATE EIBAID} one case per arm, and the authorisation filter four.
 * And the assertions that could pass vacuously are given teeth explicitly, which is what
 * {@link #shiftingTheOccursBaseBreaksTheMenuExpectations()} is for.
 *
 * <h2>The user-type authorisation filter, which is what this program is really about</h2>
 *
 * <p>{@code app/cbl/COMEN01C.cbl:136-143} compares {@code CDEMO-MENU-OPT-USRTYPE(WS-OPTION)} -
 * the {@code PIC X(01)} column of {@code app/cpy/COMEN02Y.cpy} - against {@code 'A'} for a caller
 * whose {@code 88 CDEMO-USRTYP-USER} holds. Two properties of it are easy to lose in translation and
 * both are pinned here:
 *
 * <ul>
 *   <li><strong>The filter is not guarded by the error flag.</strong> The validation above it at
 *       lines 127 to 134 ends in {@code PERFORM SEND-MENU-SCREEN}, and a {@code PERFORM}
 *       <em>returns</em> - so the program really does fall through to line 136 with the flag already
 *       set, and both {@code IF} statements can fire in one pass. {@code case10} drives exactly that
 *       and requires the <em>second</em> paint's message, because the second send is what the
 *       terminal shows. Hoisting the filter into the validation's {@code ELSE} would make that case
 *       carry the invalid-option text instead.</li>
 *   <li><strong>The subscript can be out of range when the filter reads it.</strong> Because the
 *       filter runs after a failed validation, {@code WS-OPTION} may be 0, or 11, or 99.
 *       {@code COBOL} without {@code SSRANGE} computes an offset and reads whatever bytes are there;
 *       Java would throw. So the read is bounded to the declared {@code OCCURS 1..12} and yields
 *       nothing outside it - gate {@code G33}. {@code case06} drives a subscript below the table,
 *       {@code case08} one inside it but unvalued, and {@code case09} one far beyond it.</li>
 * </ul>
 *
 * <p>All ten valued {@code CDEMO-MENU-OPT-USRTYPE} columns of {@code app/cpy/COMEN02Y.cpy} carry
 * {@code 'U'}, so the filter's true arm is <strong>unreachable from the shipped table</strong> - the
 * filtered set a regular user sees is all ten options, which {@code case03} asserts. The true arm is
 * therefore driven with a stub table that differs from the copybook's first entry in
 * <em>nothing but the one column under test</em>, so the composed {@code OPTN001O} line stays
 * byte-identical to the real one and the column is the only variable.
 *
 * <h2>The missing space, which is source behaviour and not a typo</h2>
 *
 * <p>{@code app/cbl/COMEN01C.cbl:159-163} composes
 * {@code STRING 'This option ' DELIMITED BY SIZE, CDEMO-MENU-OPT-NAME(WS-OPTION) DELIMITED BY SPACE,
 * 'is coming soon ...' DELIMITED BY SIZE}. The middle operand stops at the first space of the
 * thirty-five-byte name and the suffix has no leading space, so option 1 emits
 * {@code This option Accountis coming soon ...} with the two words run together. {@code case13}
 * dedicates itself to that image. Inserting the "missing" space would be a behaviour change, and the
 * message proof in {@link #theComingSoonCompositionHasNoSeparatorBeforeIs()} exists so that anyone
 * who inserts it sees a named failure rather than a passing build.
 *
 * <h2>This program touches no dataset at all, and that is asserted rather than assumed</h2>
 *
 * <p>{@code COMEN01C} declares no {@code SELECT} and no {@code FD}, and its five {@code EXEC CICS}
 * statements are one {@code RETURN}, two {@code XCTL}, one {@code SEND MAP} and one
 * {@code RECEIVE MAP} - there is no {@code READ}, {@code STARTBR}, {@code READNEXT}, {@code WRITE},
 * {@code REWRITE} or {@code DELETE} anywhere in its 282 lines. So no repository is reachable from
 * the translation and gate {@code G47}, which exercises each {@code FILE STATUS} outcome per
 * repository call site, has no call site to exercise in this package. An absence cannot be proved by
 * writing nothing, so {@code case18} seeds the security file at the eighty bytes
 * {@code app/cpy/CSUSR01Y.cpy} declares - making it present and readable for the whole run - and
 * {@link #assertNoDatasetActivity(ParityCase, DecodedFingerprint)} then requires <em>both</em> record
 * channels of every fingerprint to come back empty. Seeding without expecting is the point: a case
 * that merely omitted the dataset could not tell "the unit read nothing" from "nobody looked".
 *
 * <h2>{@code COMEN01C} and {@code COADM01C} are near-twins whose expectations do not transfer</h2>
 *
 * <p>The two programs are 282 and 268 lines, both contain five {@code EXEC CICS} statements, and
 * both drive a twenty-field 167-line mapset - so it is tempting to copy one test onto the other.
 * Their option tables make that wrong. {@code app/cpy/COMEN02Y.cpy} is {@code OCCURS 12} with ten
 * populated options <strong>and</strong> an {@code X(01)} authorisation column, while
 * {@code app/cpy/COADM02Y.cpy} is {@code OCCURS 9} with four valued entries and <strong>no</strong>
 * such column. The consequences run deeper than the counts: this program has an authorisation
 * filter and a {@code No access} message that the admin menu has no equivalent of, its
 * {@code EVALUATE WS-IDX} has twelve arms where the admin menu's has ten, and its coming-soon
 * message includes the option name where {@code COADM01C} comments that operand out and emits thirty
 * characters instead of thirty-seven. Nothing below is shared with the admin-menu program.
 *
 * <h2>How the unit is reached: no HTTP, no launcher, no container - gate {@code G51}</h2>
 *
 * <p>Seventeen cases construct {@link MainMenuService} directly and call it as a plain object
 * ({@link UnitKind#SERVICE}) - which is where the authorisation filter lives, so every filter case
 * has to be one of them; three construct {@link MainMenuController} and invoke its handler method
 * directly ({@link UnitKind#CONTROLLER_POJO}), which is the only way to observe the six header
 * fields {@code POPULATE-HEADER-INFO} renders and the seventy-eight-byte {@code ERRMSGO} the
 * eighty-byte {@code WS-MESSAGE} is truncated into. There is no {@code MockMvc}, no
 * {@code TestRestTemplate}, no {@code WebTestClient}, no {@code JobLauncher}, no application context
 * and no servlet container anywhere in this file - a parity assertion is about byte layout and
 * ordering, and neither is improved by putting a framework between the assertion and the code. The
 * clock is always the fixed one the case pins, so the date and time header is comparable and the suite
 * is deterministic and non-interactive - gate {@code G54}, practice {@code B7}.
 *
 * <p>Three prohibitions hold throughout and are checked by inspection rather than at runtime, because a
 * test cannot assert its own source: no wildcard import anywhere, so every copybook-to-type
 * correspondence stays auditable (gate {@code G52}); no {@code double} or {@code float}, which this
 * program has no occasion for since it declares no decimal {@code PICTURE} and performs no arithmetic
 * at all (gate {@code G22}); and no mutable static, every constant below being immutable or an
 * unmodifiable view (gate {@code G53}, which {@link #assertNoMutableState(MainMenuService)} also
 * enforces on the unit itself). No production dataset name appears either - the one binding this file
 * declares quotes the test profile (gate {@code G46}).
 *
 * <h3>The five imports that are not in this file's declared dependency list</h3>
 *
 * <p>{@link MainMenuRequest}, {@link MainMenuResponse}, {@link DatasetBinding},
 * {@link DatasetBindings} and {@link SecUserRecord} are imported because they are the declared
 * parameter, payload and validation types of the public constructor and the public handler method of
 * two classes that <em>are</em> declared dependencies: {@code MainMenuService} can only be built by
 * handing it a binding catalogue and it validates that catalogue against
 * {@code SecUserRecord.RECORD_LENGTH}, while {@code MainMenuController.getMainMenu} can only be
 * called by handing it a request and only answers with a screen. Naming a declared dependency's own
 * signature is not the same as inventing a dependency, and each of the five was verified present in
 * the module before being named here. Every other type this file touches is reached through
 * {@code var} - the {@code ScreenResponse} envelope and the {@code ScreenMetadata} beside it among
 * them - so the import list is the smallest one that compiles.
 *
 * @see MainMenuService the translated program, which carries every branch including the filter
 * @see MainMenuController the HTTP adapter, which carries the header and the field projection
 */
@DisplayName("COMEN01C parity gate - main menu, CSD transaction CM00, GET /api/menu")
class COMEN01CParityTest {

    // =================================================================================================
    // Section 1 - program identity.
    // =================================================================================================

    /** The program whose {@code parity/COMEN01C/} case directory this class is the gate for. */
    private static final String PROGRAM = "COMEN01C";

    /** {@code WS-TRANID PIC X(04) VALUE 'CM00'} - {@code app/cbl/COMEN01C.cbl:37}. */
    private static final String TRANSACTION_ID = "CM00";

    /** {@code MAPSET('COMEN01')} - {@code app/cbl/COMEN01C.cbl:191}. */
    private static final String MAPSET_NAME = "COMEN01";

    /** {@code MAP('COMEN1A')} - {@code app/cbl/COMEN01C.cbl:190}. */
    private static final String MAP_NAME = "COMEN1A";

    /** {@code MOVE 'COSGN00C'} - {@code app/cbl/COMEN01C.cbl:83}, {@code :97} and {@code :173}. */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    // =================================================================================================
    // Section 2 - literals transcribed from the COBOL sources.
    //
    // Every constant below is copied from a source file named in its comment, character by character,
    // and NOT read out of the Java translation. That is what stops a case agreeing with the code by
    // construction: if the translation mis-transcribed a copybook, these literals disagree with it and
    // copybookLiteralsAgreeWithTheTranslation() says so by name.
    // =================================================================================================

    /** {@code CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10} - {@code app/cpy/COMEN02Y.cpy:21}. */
    private static final int ACTIVE_OPTION_COUNT = 10;

    /** {@code CDEMO-MENU-OPT OCCURS 12 TIMES} - {@code app/cpy/COMEN02Y.cpy:88}. */
    private static final int OCCURS_TABLE_SIZE = 12;

    /** {@code OPTN001I} to {@code OPTN012I} - {@code app/cpy-bms/COMEN01.CPY:60-126}. */
    private static final int MENU_LINE_COUNT = 12;

    /** {@code OPTN00nI PIC X(40)} - {@code app/cpy-bms/COMEN01.CPY:60}. */
    private static final int MENU_LINE_LENGTH = 40;

    /** {@code CDEMO-MENU-OPT-NAME PIC X(35)} - {@code app/cpy/COMEN02Y.cpy:90}. */
    private static final int OPTION_NAME_LENGTH = 35;

    /** {@code TRNNAMEI PIC X(4)} - {@code app/cpy-bms/COMEN01.CPY:24}. */
    private static final int TRN_NAME_LENGTH = 4;

    /** {@code TITLE01I} and {@code TITLE02I PIC X(40)} - {@code app/cpy-bms/COMEN01.CPY:30, 48}. */
    private static final int TITLE_LENGTH = 40;

    /** {@code CURDATEI PIC X(8)} - {@code app/cpy-bms/COMEN01.CPY:36}, rendered {@code mm/dd/yy}. */
    private static final int CURDATE_LENGTH = 8;

    /** {@code PGMNAMEI PIC X(8)} - {@code app/cpy-bms/COMEN01.CPY:42}. */
    private static final int PGM_NAME_LENGTH = 8;

    /** {@code CURTIMEI PIC X(8)} - {@code app/cpy-bms/COMEN01.CPY:54}, rendered {@code hh:mm:ss}. */
    private static final int CURTIME_LENGTH = 8;

    /** {@code OPTIONI PIC X(2)} - {@code app/cpy-bms/COMEN01.CPY:132}. */
    private static final int OPTION_LENGTH = 2;

    /** {@code WS-MESSAGE PIC X(80)} - {@code app/cbl/COMEN01C.cbl:38}. */
    private static final int WS_MESSAGE_LENGTH = 80;

    /** {@code ERRMSGI PIC X(78)} - {@code app/cpy-bms/COMEN01.CPY:138}. */
    private static final int ERRMSG_LENGTH = 78;

    /** {@code WS-USRSEC-FILE PIC X(08)} - {@code app/cbl/COMEN01C.cbl:39}. */
    private static final int USRSEC_FILE_NAME_LENGTH = 8;

    /**
     * {@code WS-USRSEC-FILE ... VALUE 'USRSEC  '} - {@code app/cbl/COMEN01C.cbl:39}, declared and
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

    /** {@code CSUSR01Y} - the copybook {@code app/cbl/COMEN01C.cbl:58} copies for the dead record. */
    private static final String USRSEC_COPYBOOK = "CSUSR01Y";

    /** {@code RECFM=FB}, as {@code app/jcl/DUSRSECJ.jcl:48} declares the security file. */
    private static final String USRSEC_RECORD_FORMAT = "FB";

    /**
     * The ten {@code CDEMO-MENU-OPT-NAME} values, {@code PIC X(35)} each.
     *
     * <p>Option 8 is the trap. {@code app/cpy/COMEN02Y.cpy:69} holds a <strong>commented-out</strong>
     * alternative reading {@code 'Transaction Add (Admin Only)       '}, and the live literal on line
     * 70 is the one below. Both are thirty-five characters, so a width check alone would not catch the
     * substitution - only the text will.
     */
    private static final List<String> OPTION_NAMES = List.of(
            "Account View                       ",   // app/cpy/COMEN02Y.cpy:27
            "Account Update                     ",   // app/cpy/COMEN02Y.cpy:33
            "Credit Card List                   ",   // app/cpy/COMEN02Y.cpy:39
            "Credit Card View                   ",   // app/cpy/COMEN02Y.cpy:45
            "Credit Card Update                 ",   // app/cpy/COMEN02Y.cpy:51
            "Transaction List                   ",   // app/cpy/COMEN02Y.cpy:57
            "Transaction View                   ",   // app/cpy/COMEN02Y.cpy:63
            "Transaction Add                    ",   // app/cpy/COMEN02Y.cpy:70, NOT :69
            "Transaction Reports                ",   // app/cpy/COMEN02Y.cpy:76
            "Bill Payment                       ");  // app/cpy/COMEN02Y.cpy:82

    /** The ten {@code CDEMO-MENU-OPT-PGMNAME} values, {@code PIC X(08)} each. */
    private static final List<String> OPTION_PROGRAMS = List.of(
            "COACTVWC",   // app/cpy/COMEN02Y.cpy:28
            "COACTUPC",   // app/cpy/COMEN02Y.cpy:34
            "COCRDLIC",   // app/cpy/COMEN02Y.cpy:40
            "COCRDSLC",   // app/cpy/COMEN02Y.cpy:46
            "COCRDUPC",   // app/cpy/COMEN02Y.cpy:52
            "COTRN00C",   // app/cpy/COMEN02Y.cpy:58
            "COTRN01C",   // app/cpy/COMEN02Y.cpy:64
            "COTRN02C",   // app/cpy/COMEN02Y.cpy:71
            "CORPT00C",   // app/cpy/COMEN02Y.cpy:77
            "COBIL00C");  // app/cpy/COMEN02Y.cpy:83

    /**
     * {@code CDEMO-MENU-OPT-USRTYPE} - {@code 'U'} on every one of the ten valued entries, at
     * {@code app/cpy/COMEN02Y.cpy:29, 35, 41, 47, 53, 59, 65, 72, 78} and {@code :84}.
     *
     * <p>Which is why the filter's second conjunct is false for every option a regular user can
     * legitimately choose, and why its true arm needs a stub table.
     */
    private static final String REGULAR_USER_USRTYPE = "U";

    /** The one character {@code app/cbl/COMEN01C.cbl:137} compares the column against. */
    private static final String ADMIN_ONLY_USRTYPE = "A";

    /** {@code CCDA-TITLE01} - {@code app/cpy/COTTL01Y.cpy}, {@code PIC X(40)}. */
    private static final String CCDA_TITLE01 = "      AWS Mainframe Modernization       ";

    /** {@code CCDA-TITLE02} - {@code app/cpy/COTTL01Y.cpy}, the live line, {@code PIC X(40)}. */
    private static final String CCDA_TITLE02 = "              CardDemo                  ";

    /**
     * {@code CCDA-MSG-INVALID-KEY PIC X(50)} - {@code app/cpy/CSMSG01Y.cpy}, the text line 101 moves
     * into {@code WS-MESSAGE}. Assembled from its forty characters of text plus the ten spaces that
     * fill the declared width, so the width is visible rather than counted by eye.
     */
    private static final String CCDA_MSG_INVALID_KEY =
            "Invalid key pressed. Please see below..." + "          ";

    /** The literal at {@code app/cbl/COMEN01C.cbl:131}, 37 characters, no trailing space. */
    private static final String INVALID_OPTION_MESSAGE = "Please enter a valid option number...";

    /**
     * The literal at {@code app/cbl/COMEN01C.cbl:140}, 33 characters <strong>including one trailing
     * space</strong>, which is real and is transcribed deliberately even though padding to
     * {@code PIC X(80)} makes it invisible in the emitted image. This message has no counterpart in
     * {@code COADM01C}: the whole authorisation filter is unique to this program.
     */
    private static final String NO_ACCESS_MESSAGE = "No access - Admin Only option... ";

    /**
     * The three {@code STRING} operands at {@code app/cbl/COMEN01C.cbl:159-162} as they compose for
     * option 1, written as the concatenation the source performs so the join is visible.
     *
     * <p>{@code 'This option '} is {@code DELIMITED BY SIZE} so all twelve characters are sent;
     * {@code CDEMO-MENU-OPT-NAME(1)} is {@code DELIMITED BY SPACE} so only {@code 'Account'} of
     * {@code 'Account View                       '} survives; and {@code 'is coming soon ...'} is
     * {@code DELIMITED BY SIZE} and has <strong>no leading space</strong>. The two therefore run
     * together into {@code Accountis}. See {@link #theComingSoonCompositionHasNoSeparatorBeforeIs()}.
     */
    private static final String COMING_SOON_MESSAGE = "This option " + "Account" + "is coming soon ...";

    /** {@code MOVE DFHGREEN TO ERRMSGC} - {@code app/cbl/COMEN01C.cbl:158}. */
    private static final String GREEN_MNEMONIC = "DFHGREEN";

    /** {@code ERRMSG DFHMDF ... COLOR=RED} - {@code app/bms/COMEN01.bms}, the map default. */
    private static final String RED_MNEMONIC = "DFHRED";

    /** {@code CARDDEMO-COMMAREA} totals 160 bytes across the 16 fields of {@code COCOM01Y}. */
    private static final int COMMAREA_LENGTH = 160;

    /** {@code '. '} - the second {@code STRING} operand of {@code BUILD-MENU-OPTIONS} at {@code :244}. */
    private static final String OPTION_NUMBER_SEPARATOR = ". ";

    /** The {@code 'DUMMY'} sentinel prefix tested at {@code app/cbl/COMEN01C.cbl:146}. */
    private static final String DUMMY_PROGRAM_PREFIX = "DUMMY";

    /**
     * A program name beginning with that sentinel, at the full {@code PIC X(08)} width.
     *
     * <p>No entry in {@code app/cpy/COMEN02Y.cpy} begins with {@value #DUMMY_PROGRAM_PREFIX}, so line
     * 146's comparison is always true in production and the {@code XCTL} always fires. The branch
     * nevertheless exists in the source and is the <em>only</em> way to reach the coming-soon message
     * at all, so {@code case13} supplies this name and the remaining three bytes are ordinary name
     * characters, which is what makes the five-byte reference modification the thing under test.
     */
    private static final String DUMMY_PROGRAM_NAME = "DUMMY001";

    // =================================================================================================
    // Section 3 - symbolic-map item names, spelled as app/cpy-bms/COMEN01.CPY spells them.
    // =================================================================================================

    /** {@code TRNNAMEO PIC X(4)} - {@code app/cpy-bms/COMEN01.CPY:146}. */
    private static final String TRNNAME_FIELD = "TRNNAMEO";

    /** {@code TITLE01O PIC X(40)} - {@code app/cpy-bms/COMEN01.CPY:152}. */
    private static final String TITLE01_FIELD = "TITLE01O";

    /** {@code CURDATEO PIC X(8)} - {@code app/cpy-bms/COMEN01.CPY:158}. */
    private static final String CURDATE_FIELD = "CURDATEO";

    /** {@code PGMNAMEO PIC X(8)} - {@code app/cpy-bms/COMEN01.CPY:164}. */
    private static final String PGMNAME_FIELD = "PGMNAMEO";

    /** {@code TITLE02O PIC X(40)} - {@code app/cpy-bms/COMEN01.CPY:170}. */
    private static final String TITLE02_FIELD = "TITLE02O";

    /** {@code CURTIMEO PIC X(8)} - {@code app/cpy-bms/COMEN01.CPY:176}. */
    private static final String CURTIME_FIELD = "CURTIMEO";

    /** {@code OPTIONO PIC X(2)} - the one editable field of the map. */
    private static final String OPTION_FIELD = "OPTIONO";

    /** {@code ERRMSGO PIC X(78)} - the seventy-eight-byte receiver of the eighty-byte message. */
    private static final String ERRMSG_FIELD = "ERRMSGO";

    /**
     * {@code ERRMSGC PICTURE X} - the colour attribute byte. The only attribute item
     * {@code COMEN01C} ever writes, at line 158.
     */
    private static final String ERRMSG_COLOUR_FIELD = "ERRMSGC";

    /** {@code OPTIONI PIC X(2)} - {@code app/cpy-bms/COMEN01.CPY:132}, the one field the program reads. */
    private static final String OPTION_INPUT_FIELD = "OPTIONI";

    // =================================================================================================
    // Section 4 - derived images. Composed from the transcribed literals above, never read back from
    // the translation, and each checked against its declared width the moment it is built.
    // =================================================================================================

    /** {@code MOVE SPACES TO WS-MENU-OPT-TXT} - {@code app/cbl/COMEN01C.cbl:241}, forty spaces. */
    private static final String BLANK_MENU_LINE = spaces(MENU_LINE_LENGTH);

    /** {@code MOVE SPACES TO WS-MESSAGE} - {@code app/cbl/COMEN01C.cbl:79}, eighty spaces. */
    private static final String BLANK_WS_MESSAGE = spaces(WS_MESSAGE_LENGTH);

    /** {@code MOVE SPACES TO ERRMSGO} - {@code app/cbl/COMEN01C.cbl:80}, seventy-eight spaces. */
    private static final String BLANK_ERRMSG = spaces(ERRMSG_LENGTH);

    /** {@code OPTIONO} as {@code MOVE LOW-VALUES TO COMEN1AO} leaves it - {@code :89}. */
    private static final String BLANK_OPTION = spaces(OPTION_LENGTH);

    /**
     * The twelve {@code OPTN00nO} lines a full paint produces: ten composed by
     * {@code BUILD-MENU-OPTIONS} and two left as the spaces the map initialises them to, because
     * {@code CDEMO-MENU-OPT-COUNT} is {@value #ACTIVE_OPTION_COUNT} while the {@code EVALUATE WS-IDX}
     * at {@code app/cbl/COMEN01C.cbl:248-275} has arms for all twelve subscripts.
     */
    private static final List<String> PAINTED_MENU_LINES = paintedMenuLines(ACTIVE_OPTION_COUNT);

    /**
     * The twelve lines a table with an active count of one produces: line 1 composed from the
     * copybook's own first entry and the remaining eleven blank.
     *
     * <p>Every stub in this file values slot 1 with {@code OPTION_NAMES.get(0)}, so this image is the
     * same for all four filter and coming-soon cases and is byte-identical to
     * {@link #PAINTED_MENU_LINES}' first line. That is deliberate: the stub varies only the column
     * under test, so the composed line cannot become a second variable.
     */
    private static final List<String> STUB_MENU_LINES = paintedMenuLines(1);

    /**
     * Every eighty-byte image {@code COMEN01C} can leave in {@code WS-MESSAGE}, and there are exactly
     * five: the spaces line 79 initialises it to, the option rejection at line 131, the No access text
     * at line 140, the invalid-key text at line 101, and the coming-soon text lines 159 to 163
     * compose. Used to prove the direction of the narrowing at line 187 rather than merely its width.
     */
    private static final List<String> PRODUCIBLE_WS_MESSAGES = List.of(
            BLANK_WS_MESSAGE,
            picX(INVALID_OPTION_MESSAGE, WS_MESSAGE_LENGTH),
            picX(NO_ACCESS_MESSAGE, WS_MESSAGE_LENGTH),
            picX(CCDA_MSG_INVALID_KEY, WS_MESSAGE_LENGTH),
            picX(COMING_SOON_MESSAGE, WS_MESSAGE_LENGTH));

    /**
     * Lower-cased fragments of the type names a data-access collaborator would carry.
     *
     * <p>Checked against the declared fields of {@link MainMenuService} because {@code COMEN01C} has
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
     * {@code common.CicsAid} the single reproduction of it - risk {@code R-D}. Immutable once built,
     * so this static field holds no mutable state.
     */
    private static final Map<String, Byte> AID_BY_MNEMONIC = aidByMnemonic();

    /**
     * A byte {@code DFHAID} gives no mnemonic to - {@code X'07'}, which is not among the AID values IBM
     * documents.
     *
     * <p>{@code EIBAID} is one byte and a terminal can present any of the 256 values, so the
     * {@code WHEN OTHER} arm at {@code app/cbl/COMEN01C.cbl:99-102} has to absorb the unnamed ones as
     * well as the named ones. No case file can declare this byte, because {@code ScreenRequest}
     * validates its AID against the mnemonics {@code common.CicsAid} defines - which is precisely why
     * {@link #theEvaluateEibaidArmsAreOrderedAndExhaustive()} drives it directly instead.
     */
    private static final byte UNRESOLVABLE_AID = (byte) 0x07;

    /** Case identifiers driven through {@link MainMenuController} as a plain Java object. */
    private static final List<String> CONTROLLER_CASES = List.of("case01", "case16", "case20");

    /** Case identifiers driven through {@link MainMenuService} with the copybook option table. */
    private static final List<String> SERVICE_CASES =
            List.of("case02", "case03", "case04", "case05", "case06", "case07", "case08", "case14",
                    "case15");

    // =================================================================================================
    // Section 6 - the gate itself.
    // =================================================================================================

    /**
     * The twenty declarative cases, loaded from {@code src/test/resources/parity/COMEN01C/}.
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
     * <p>Gate {@code G18}: a module is not complete until its diff count is zero across all twenty of
     * its cases, and {@code FieldDiffer} reaches that count field by field rather than by comparing
     * whole strings - gate {@code G17}. The run and the judgement are separate calls rather than the
     * combined {@code judge(case, kind, unit)} so that the fingerprint is available to the assertions
     * that follow: proving both record channels came back empty is the whole of {@code case18}, and a
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
     * set at load time; what is added here is that every loaded case names {@code COMEN01C} rather
     * than some other program's, which a fixture copied from the near-twin {@code COADM01C} directory
     * would get wrong.
     */
    @Test
    @DisplayName("declares exactly twenty cases, case01 to case20, all naming COMEN01C")
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
     * Every case is dispatched by name, and no case falls through to a default adapter.
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
     * it refuses it one case at a time and only when that case runs. Checking the whole set here turns
     * a mis-declared fixture into one failure that names it.
     *
     * <p>The split is not arbitrary: the authorisation filter is the <strong>service's</strong> work,
     * not the controller's, so every case that drives the filter must be a {@code SERVICE} case, and
     * the three {@code CONTROLLER_POJO} cases are exactly the ones that need the header fields or the
     * seventy-eight-byte {@code ERRMSGO} the controller alone produces.
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
        assertThat(cases().stream().filter(one -> one.unitKind() == UnitKind.SERVICE).count())
                .describedAs("the filter cases must be SERVICE cases, because the filter is the "
                        + "service's job and the controller cannot reach it")
                .isEqualTo(ParityHarness.CASES_PER_PROGRAM - CONTROLLER_CASES.size());
    }

    /**
     * Not one case declares a dataset expectation, because {@code COMEN01C} performs no I/O.
     *
     * <p>The complement of {@link #assertNoDatasetActivity(ParityCase, DecodedFingerprint)}: that
     * method proves the run produced no dataset activity, and this one proves no case ever asked for
     * any. Together they close the loop - an expectation could not be satisfied by an observation that
     * should not exist, and an observation could not slip past by having been expected.
     */
    @Test
    @DisplayName("declares no write, no final state and no dataset expectation in any case")
    void noCaseDeclaresADatasetExpectation() {
        for (ParityCase parityCase : cases()) {
            assertThat(parityCase.expectedWrites())
                    .describedAs("%s expects a write, but COMEN01C has no WRITE, REWRITE or DELETE "
                            + "anywhere in its 282 lines", parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.expectedFinalState())
                    .describedAs("%s expects a final dataset state, but COMEN01C opens no dataset",
                            parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.expectedDatasets())
                    .describedAs("%s expects a dataset, but COMEN01C creates none", parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.jobParameters())
                    .describedAs("%s declares a job parameter, but COMEN01C is an online program "
                            + "reached by transaction CM00 and not by EXEC PGM=", parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.screenRequest().forcedOutcomes())
                    .describedAs("%s forces a repository outcome, but COMEN01C has no repository call "
                            + "site to force one at", parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.expectedResponse().cursorField())
                    .describedAs("%s expects a cursor, but COMEN01C never moves -1 into an xxxL item",
                            parityCase.caseId())
                    .isNull();
        }
    }

    /**
     * Every field any case pins is a real symbolic-map item, at exactly its declared width.
     *
     * <p>Gate {@code G9}: every payload field must trace to a {@code DFHMDF} definition and every
     * width to the matching {@code xxxI} {@code PICTURE} clause. The differ compares whatever the case
     * declares against whatever the unit produced, in both directions, so a fixture pinning
     * {@code ERRMSGO} at seventy-six characters, or inventing an {@code OPTN013O}, would fail for a
     * reason that reads like a code defect. Checking the fixtures against the map here turns that into
     * one failure naming the field.
     *
     * <p>The same is done for the attribute items and the communication area: the only attribute
     * {@code COMEN01C} writes is {@code ERRMSGC} and the only values it can hold are the map's
     * declared {@code COLOR=RED} and the {@code DFHGREEN} line 158 overrides it with, while every
     * navigation key must be one of the sixteen {@code COCOM01Y} fields at the width that copybook
     * declares - the sixteen widths coming from {@link NavigationContext}'s own constants rather than
     * being retyped.
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
                            .describedAs("%s pins '%s', which is not one of the twenty labelled DFHMDF "
                                    + "fields of mapset COMEN01", parityCase.caseId(), field.getKey())
                            .containsKey(field.getKey());
                    assertThat(field.getValue().length())
                            .describedAs("%s pins %s at %d character(s)", parityCase.caseId(),
                                    field.getKey(), field.getValue().length())
                            .isEqualTo(screenWidths.get(field.getKey()));
                }
                assertThat(send.attributes())
                        .describedAs("%s: ERRMSGC is the only attribute item COMEN01C ever writes, at "
                                + "line 158", parityCase.caseId())
                        .containsOnlyKeys(ERRMSG_COLOUR_FIELD);
                assertThat(send.attributes().get(ERRMSG_COLOUR_FIELD))
                        .describedAs("%s: the map declares COLOR=RED and only line 158 overrides it",
                                parityCase.caseId())
                        .isIn(RED_MNEMONIC, GREEN_MNEMONIC);
            }
        }
    }

    /**
     * The twenty labelled {@code DFHMDF} fields of mapset {@code COMEN01} and their declared widths.
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
     * file, and the ones the production classes carry. Comparing them is not circular, because neither
     * was derived from the other - if they disagree, one of the two mis-read {@code app/cpy}, and the
     * failure names which field.
     *
     * <p>{@code CCDA-MSG-INVALID-KEY} and {@code CCDA-THANK-YOU} are checked to be
     * <strong>different</strong> as well as individually correct: they are {@code PIC X(50)} and
     * {@code PIC X(40)} respectively, live in different copybooks, and substituting one for the other
     * is exactly the kind of near-miss a byte comparison exists to catch.
     */
    @Test
    @DisplayName("agrees with the translation about every copybook literal it transcribes")
    void copybookLiteralsAgreeWithTheTranslation() {
        assertThat(MainMenuService.PROGRAM_NAME).isEqualTo(PROGRAM);
        assertThat(MainMenuService.TRANSACTION_ID).isEqualTo(TRANSACTION_ID);
        assertThat(MainMenuService.MAPSET_NAME).isEqualTo(MAPSET_NAME);
        assertThat(MainMenuService.MAP_NAME).isEqualTo(MAP_NAME);
        assertThat(MainMenuService.SIGNON_PROGRAM).isEqualTo(SIGNON_PROGRAM);
        assertThat(MainMenuService.MESSAGE_LENGTH).isEqualTo(WS_MESSAGE_LENGTH);
        assertThat(MainMenuService.OPTION_TEXT_LENGTH).isEqualTo(MENU_LINE_LENGTH);
        assertThat(MainMenuService.OPTION_LINE_COUNT).isEqualTo(MENU_LINE_COUNT);
        assertThat(MainMenuService.OPTION_LENGTH).isEqualTo(OPTION_LENGTH);
        assertThat(MainMenuService.USRSEC_FILE_NAME_LENGTH).isEqualTo(USRSEC_FILE_NAME_LENGTH);
        assertThat(MainMenuService.INVALID_OPTION_MESSAGE).isEqualTo(INVALID_OPTION_MESSAGE);
        assertThat(MainMenuService.NO_ACCESS_MESSAGE).isEqualTo(NO_ACCESS_MESSAGE);
        assertThat(MainMenuService.OPTION_NUMBER_SEPARATOR).isEqualTo(OPTION_NUMBER_SEPARATOR);
        assertThat(MainMenuService.DUMMY_PROGRAM_PREFIX).isEqualTo(DUMMY_PROGRAM_PREFIX);
        assertThat(MainMenuService.ADMIN_ONLY_USRTYPE).isEqualTo(ADMIN_ONLY_USRTYPE);
        assertThat(MainMenuService.COMING_SOON_PREFIX + "Account" + MainMenuService.COMING_SOON_SUFFIX)
                .describedAs("the two live STRING operands, joined the way lines 159 to 163 join them "
                        + "around the space-delimited first word of CDEMO-MENU-OPT-NAME(1)")
                .isEqualTo(COMING_SOON_MESSAGE);

        assertThat(MenuOptions.ACTIVE_OPTION_COUNT).isEqualTo(ACTIVE_OPTION_COUNT);
        assertThat(MenuOptions.TABLE_SIZE).isEqualTo(OCCURS_TABLE_SIZE);
        assertThat(MenuOptions.OPT_NAME_LENGTH).isEqualTo(OPTION_NAME_LENGTH);
        assertThat(MenuOptions.OPT_PGMNAME_LENGTH).isEqualTo(OPTION_PROGRAMS.get(0).length());
        assertThat(MenuOptions.OPT_USRTYPE_LENGTH).isEqualTo(ADMIN_ONLY_USRTYPE.length());
        for (int subscript = 1; subscript <= ACTIVE_OPTION_COUNT; subscript++) {
            MenuOption entry = MenuOptions.optionBySubscript(subscript).orElseThrow();
            assertThat(entry.menuOptName())
                    .describedAs("CDEMO-MENU-OPT-NAME(%d)", subscript)
                    .isEqualTo(OPTION_NAMES.get(subscript - 1));
            assertThat(entry.menuOptPgmName())
                    .describedAs("CDEMO-MENU-OPT-PGMNAME(%d)", subscript)
                    .isEqualTo(OPTION_PROGRAMS.get(subscript - 1));
            assertThat(entry.menuOptUsrType())
                    .describedAs("CDEMO-MENU-OPT-USRTYPE(%d) - all ten are 'U', which is why the "
                            + "filter's true arm needs a stub table", subscript)
                    .isEqualTo(REGULAR_USER_USRTYPE)
                    .isNotEqualTo(ADMIN_ONLY_USRTYPE);
        }
        assertThat(MenuOptions.options())
                .describedAs("OCCURS 12 with ten valued entries: slots 11 and 12 are "
                        + "present-but-unvalued and are never trimmed away")
                .hasSize(OCCURS_TABLE_SIZE);
        assertThat(MenuOptions.options().subList(ACTIVE_OPTION_COUNT, OCCURS_TABLE_SIZE))
                .allSatisfy(slot -> assertThat(slot).isEmpty());

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
        assertThat(MainMenuService.MAP_MESSAGE_COLOUR).isEqualTo(BmsAttributes.DFHRED);
        assertThat(MainMenuService.COMING_SOON_MESSAGE_COLOUR).isEqualTo(BmsAttributes.DFHGREEN);

        assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(COMMAREA_LENGTH);
        assertThat(NavigationContext.USER_TYPE_USER).isEqualTo(REGULAR_USER_USRTYPE);
        assertThat(SecUserRecord.RECORD_LENGTH).isEqualTo(80);
    }

    /**
     * The off-by-one proof: a shifted {@code OCCURS} base breaks the menu expectations.
     *
     * <p>Gate {@code G33}. {@code app/cbl/COMEN01C.cbl:238} is
     * {@code PERFORM VARYING WS-IDX FROM 1 BY 1}, so COBOL subscript 1 addresses the first entry while
     * a Java index of 1 would address the second. An assertion that the first menu line reads
     * {@code 01. Account View} is only worth making if a mis-indexed implementation would fail it, so
     * that implementation is built here - a table whose slots are rotated by one, which is exactly what
     * a 0-based read of a 1-based subscript produces - and every one of the ten composed lines is
     * required to differ from the copybook's.
     *
     * <p>The bound is checked the same way. {@code UNTIL WS-IDX > CDEMO-MENU-OPT-COUNT} is inclusive of
     * the count, so a table declaring one option fewer must leave {@code OPTN010O} blank - which is the
     * failure mode an exclusive bound would produce on the real table, and which would silently drop
     * {@code Bill Payment} off the bottom of the menu.
     *
     * <p>The two present-but-unvalued slots are pinned from the other direction: {@code OPTN011O} and
     * {@code OPTN012O} must be blank on the real table even though the {@code EVALUATE WS-IDX} has arms
     * for them, because the loop bound stops at ten. That is the {@code OCCURS 12} versus count-of-10
     * gap made observable, and it is the single largest difference from the near-twin admin menu.
     */
    @Test
    @DisplayName("fails its own menu expectations when the OCCURS base is shifted by one")
    void shiftingTheOccursBaseBreaksTheMenuExpectations() {
        MainMenuService service = service(ParityHarness.usAscii().codec());

        List<String> correct = service.buildMenuOptions(MainMenuOptionTable.copybook());
        assertThat(correct)
                .describedAs("the copybook table must compose exactly the ten lines this class "
                        + "transcribed from app/cpy/COMEN02Y.cpy, followed by two blanks")
                .isEqualTo(PAINTED_MENU_LINES);
        assertThat(correct.get(0))
                .describedAs("subscript 1 is the FIRST entry, not the second")
                .startsWith("01" + OPTION_NUMBER_SEPARATOR + "Account View");
        assertThat(correct.get(ACTIVE_OPTION_COUNT - 1))
                .describedAs("subscript 10 is the LAST valued entry")
                .startsWith("10" + OPTION_NUMBER_SEPARATOR + "Bill Payment");
        assertThat(correct.subList(ACTIVE_OPTION_COUNT, MENU_LINE_COUNT))
                .describedAs("OPTN011O and OPTN012O are present but blank: the EVALUATE at 248-275 has "
                        + "arms for twelve subscripts and the loop bound reaches only ten")
                .containsExactly(BLANK_MENU_LINE, BLANK_MENU_LINE);

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
                        + "would leave Bill Payment off the menu - which is what this proves would be "
                        + "visible")
                .isEqualTo(BLANK_MENU_LINE);
        assertThat(correct.get(ACTIVE_OPTION_COUNT - 1)).isNotEqualTo(BLANK_MENU_LINE);

        assertThatExceptionOfType(IndexOutOfBoundsException.class)
                .describedAs("COBOL has no subscript 0, so addressing one on the dispatch path must "
                        + "fail rather than silently return the first entry")
                .isThrownBy(() -> MainMenuOptionTable.copybook().optionBySubscript(0));
        assertThatExceptionOfType(IndexOutOfBoundsException.class)
                .describedAs("nor may subscript 13 resolve on a table declared OCCURS 12")
                .isThrownBy(() -> MainMenuOptionTable.copybook()
                        .optionBySubscript(OCCURS_TABLE_SIZE + 1));
    }

    /**
     * The message proof: the coming-soon text has no separator before {@code is}, and inserting one
     * fails.
     *
     * <p>{@code app/cbl/COMEN01C.cbl:159-163} is the composition; {@code DELIMITED BY SPACE} sends the
     * characters up to, and not including, the first space of the thirty-five-byte name, and
     * {@code 'is coming soon ...'} begins with a letter. So the image is
     * {@code This option Accountis coming soon ...} at thirty-seven characters. The literal with the
     * "missing" space inserted is built here and required to <strong>differ</strong>, which is what
     * makes {@code case13} a real assertion rather than a restatement of whatever the code does: anyone
     * who repairs the apparent typo sees this test name in the failure.
     *
     * <p>The same composition is checked for three more entries, because the surviving word differs per
     * option and a translation that hard-coded the first one would agree here and nowhere else.
     */
    @Test
    @DisplayName("composes the coming-soon message with no space before 'is', for every option")
    void theComingSoonCompositionHasNoSeparatorBeforeIs() {
        assertThat(COMING_SOON_MESSAGE)
                .describedAs("the composed text, thirty-seven characters, with 'Account' and 'is' run "
                        + "together exactly as the source leaves them")
                .isEqualTo("This option Accountis coming soon ...")
                .hasSize(37)
                .doesNotContain("Account is");
        assertThat(COMING_SOON_MESSAGE)
                .describedAs("inserting the 'missing' space would be a behaviour change, so the two "
                        + "images must differ; if they ever compare equal this proof has no teeth")
                .isNotEqualTo("This option Account" + " " + "is coming soon ...");

        MainMenuService service = service(ParityHarness.usAscii().codec());
        record Worked(int subscript, String surviving, int length) {
        }
        // The first space-delimited word of CDEMO-MENU-OPT-NAME, per app/cpy/COMEN02Y.cpy.
        List<Worked> worked = List.of(
                new Worked(1, "Account", 37),
                new Worked(3, "Credit", 36),
                new Worked(6, "Transaction", 41),
                new Worked(10, "Bill", 34));
        for (Worked row : worked) {
            String expected = MainMenuService.COMING_SOON_PREFIX + row.surviving()
                    + MainMenuService.COMING_SOON_SUFFIX;
            MainMenuOutcome outcome = service.handle(
                    new MainMenuInput(userReentered(), CicsAid.DFHENTER,
                            String.format(Locale.ROOT, "%02d", row.subscript())),
                    dummyPrefixTableFor(row.subscript()));

            assertThat(outcome.message())
                    .describedAs("option %d composes '%s'", row.subscript(), expected)
                    .isEqualTo(picX(expected, WS_MESSAGE_LENGTH));
            assertThat(expected.length())
                    .describedAs("option %d's composed text length", row.subscript())
                    .isEqualTo(row.length());
            assertThat(expected)
                    .describedAs("no option may acquire a separator before 'is'")
                    .doesNotContain(row.surviving() + " is");
            assertThat(outcome.messageColourOverridden())
                    .describedAs("line 158 MOVE DFHGREEN TO ERRMSGC applies on this path and no other")
                    .isTrue();
        }
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
            case "case01", "case16", "case20" -> COMEN01CParityTest::runController;
            case "case09" -> COMEN01CParityTest::runOutOfRangeSubscript;
            case "case10" -> COMEN01CParityTest::runUngatedFilterAfterFailedValidation;
            case "case11" -> COMEN01CParityTest::runRegularUserRefused;
            case "case12" -> COMEN01CParityTest::runAdministratorNeverRefused;
            case "case13" -> COMEN01CParityTest::runDummyPrefixBranch;
            case "case17" -> COMEN01CParityTest::runUserTypePassThrough;
            case "case18" -> COMEN01CParityTest::runWithSecurityFileSeeded;
            case "case19" -> COMEN01CParityTest::runThreeTimesForStatelessness;
            case "case02", "case03", "case04", "case05", "case06", "case07", "case08", "case14",
                    "case15" -> COMEN01CParityTest::runService;
            default -> throw new IllegalArgumentException("Case " + parityCase.caseId()
                    + " of " + PROGRAM + " has no adapter. Every case is dispatched by name and there "
                    + "is no fallback, because a case running through some default adapter would "
                    + "report a diff count of zero for a run nobody chose.");
        };
    }

    /**
     * The service adapter: construct {@link MainMenuService}, hand it the three values
     * {@code COMEN01C} consults, and record what it produced against the copybook option table.
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
        MainMenuService service = service(invocation.codec());
        MainMenuInput input = inputOf(invocation);

        MainMenuOutcome outcome = service.handle(input);

        assertServiceInvariants(service, input, outcome);
        recordServiceOutcome(invocation, outcome);
        return null;
    }

    /**
     * {@code case09}: the ungated filter subscripting the option table with a value no COBOL table
     * could address.
     *
     * <p>Option 99 is rejected by the guard at {@code app/cbl/COMEN01C.cbl:128}, and the filter at line
     * 136 nevertheless evaluates {@code CDEMO-MENU-OPT-USRTYPE(99)} on a table declared
     * {@code OCCURS 12}. The bounded read must yield nothing and must not throw, because the COBOL does
     * neither: without {@code SSRANGE} it computes an offset and reads whatever bytes are there, and an
     * exception would be a crash the source does not produce. So both the outcome and the absence of a
     * throw are asserted, and so is the fact that the message on the screen is the option rejection
     * rather than the No access text - which is what a filter that read stray bytes as {@code 'A'}
     * would have produced.
     *
     * @param invocation the seeded inputs, the pinned clock and the codec
     * @return {@code null}, meaning the recorder holds the observation
     */
    private static UnitOutcome runOutOfRangeSubscript(Invocation invocation) {
        MainMenuService service = service(invocation.codec());
        MainMenuInput input = inputOf(invocation);
        MainMenuOptionTable copybook = MainMenuOptionTable.copybook();

        MainMenuOutcome outcome = service.handle(input);
        assertServiceInvariants(service, input, outcome);

        int wsOption = service.normaliseOption(input.option()).option().orElseThrow();
        assertThat(wsOption)
                .describedAs("the subscript the filter reads is far outside the OCCURS 12 range, which "
                        + "is the whole point of the case")
                .isGreaterThan(OCCURS_TABLE_SIZE);
        for (int subscript : new int[] {0, OCCURS_TABLE_SIZE + 1, wsOption, Integer.MAX_VALUE}) {
            assertThatNoException()
                    .describedAs("the ungated filter must tolerate subscript %d: COBOL without SSRANGE "
                            + "reads stray bytes rather than failing", subscript)
                    .isThrownBy(() -> service.isAdminOnlyOption(copybook, OptionalInt.of(subscript)));
            assertThat(service.isAdminOnlyOption(copybook, OptionalInt.of(subscript)))
                    .describedAs("no byte an out-of-range read could see makes line 137 true on a path "
                            + "this program reaches, so subscript %d must evaluate false", subscript)
                    .isFalse();
        }
        assertThat(outcome.message())
                .describedAs("line 133 painted the option rejection and the filter added nothing, so "
                        + "the No access text must NOT be on the screen")
                .isEqualTo(picX(INVALID_OPTION_MESSAGE, WS_MESSAGE_LENGTH))
                .isNotEqualTo(picX(NO_ACCESS_MESSAGE, WS_MESSAGE_LENGTH));
        assertThat(outcome.errorFlag())
                .describedAs("line 130 MOVE 'Y' TO WS-ERR-FLG, which then suppresses the dispatch")
                .isTrue();

        recordServiceOutcome(invocation, outcome);
        return null;
    }

    /**
     * {@code case10}: both {@code IF} statements fire in one pass, and the second paint wins.
     *
     * <p>The table's active count is 1, so option 3 is rejected at
     * {@code app/cbl/COMEN01C.cbl:128} and line 133 paints the option rejection. {@code PERFORM}
     * returns, so control reaches line 136, where slot 3 carries {@code 'A'} and the caller is a
     * regular user - both conjuncts true - and line 142 repaints with the No access text. The terminal
     * shows the second send, which is why the recorded message is the No access one. Were the filter
     * hoisted into the validation's {@code ELSE}, this case would carry the option rejection instead.
     *
     * @param invocation the seeded inputs, the pinned clock and the codec
     * @return {@code null}, meaning the recorder holds the observation
     */
    private static UnitOutcome runUngatedFilterAfterFailedValidation(Invocation invocation) {
        MainMenuService service = service(invocation.codec());
        MainMenuInput input = inputOf(invocation);
        MainMenuOptionTable table = adminOnlySlotThreeTable();

        MainMenuOutcome outcome = service.handle(input, table);
        assertServiceInvariants(service, input, outcome);

        int wsOption = service.normaliseOption(input.option()).option().orElseThrow();
        assertThat(wsOption)
                .describedAs("the entered option must exceed the table's active count so the guard at "
                        + "line 128 rejects it first")
                .isGreaterThan(table.activeCount());
        assertThat(table.optionWithinTable(wsOption).orElseThrow().menuOptUsrType())
                .describedAs("and slot %d must still be a valued entry carrying 'A', so the filter at "
                        + "line 137 has something to find", wsOption)
                .isEqualTo(ADMIN_ONLY_USRTYPE);
        assertThat(service.isAdminOnlyOption(table, OptionalInt.of(wsOption)))
                .describedAs("the filter is reached with a subscript the validation already rejected, "
                        + "and it fires")
                .isTrue();
        assertThat(outcome.message())
                .describedAs("line 142's paint overwrites line 133's, so the No access text is what the "
                        + "terminal shows - NOT the option rejection")
                .isEqualTo(picX(NO_ACCESS_MESSAGE, WS_MESSAGE_LENGTH))
                .isNotEqualTo(picX(INVALID_OPTION_MESSAGE, WS_MESSAGE_LENGTH));
        assertThat(outcome.hasNextProgram())
                .describedAs("line 145's IF NOT ERR-FLG-ON is false, so the dispatch is suppressed")
                .isFalse();
        assertThat(outcome.optionLines())
                .describedAs("BUILD-MENU-OPTIONS loops to the stub's active count of %d, so only "
                        + "OPTN001O is composed and the other eleven lines are the spaces line 241 "
                        + "leaves", table.activeCount())
                .isEqualTo(STUB_MENU_LINES);

        recordServiceOutcome(invocation, outcome);
        return null;
    }

    /**
     * {@code case11}: a regular user refused an admin-only option, with no prior rejection.
     *
     * <p>The option is valid for the stub's active count, so the guard at lines 127 to 134 does not
     * fire and the filter is reached clean. Line 138 sets the flag, line 140 moves the thirty-three
     * character literal and line 142 paints; line 145 is then false and no successor is named. The stub
     * differs from the copybook's first entry in <strong>nothing but the authorisation column</strong>,
     * which is asserted here so the composed menu line cannot become a second variable.
     *
     * @param invocation the seeded inputs, the pinned clock and the codec
     * @return {@code null}, meaning the recorder holds the observation
     */
    private static UnitOutcome runRegularUserRefused(Invocation invocation) {
        MainMenuService service = service(invocation.codec());
        MainMenuInput input = inputOf(invocation);
        MainMenuOptionTable table = adminOnlyFirstEntryTable();

        MainMenuOutcome outcome = service.handle(input, table);
        assertServiceInvariants(service, input, outcome);

        MenuOption stubbed = table.optionBySubscript(1).orElseThrow();
        MenuOption copybook = MenuOptions.optionBySubscript(1).orElseThrow();
        assertThat(stubbed.menuOptName()).isEqualTo(copybook.menuOptName());
        assertThat(stubbed.menuOptPgmName()).isEqualTo(copybook.menuOptPgmName());
        assertThat(stubbed.menuOptUsrType())
                .describedAs("the authorisation column is the only thing this stub varies")
                .isEqualTo(ADMIN_ONLY_USRTYPE)
                .isNotEqualTo(copybook.menuOptUsrType());
        assertThat(input.navigationContext().isUser())
                .describedAs("88 CDEMO-USRTYP-USER must hold for line 136's first conjunct")
                .isTrue();
        assertThat(outcome.errorFlag())
                .describedAs("line 138 SET ERR-FLG-ON TO TRUE - a SET here where line 130 used MOVE 'Y'")
                .isTrue();
        assertThat(outcome.message())
                .describedAs("line 140's literal carries a trailing space that padding to PIC X(80) "
                        + "makes invisible, and the emitted image is what is compared")
                .isEqualTo(picX(NO_ACCESS_MESSAGE, WS_MESSAGE_LENGTH));
        assertThat(outcome.hasNextProgram())
                .describedAs("the dispatch at 146-155 is suppressed by the flag")
                .isFalse();
        assertThat(outcome.messageColourOverridden())
                .describedAs("only the coming-soon path touches ERRMSGC, so a refusal stays red")
                .isFalse();
        assertThat(outcome.optionLines())
                .describedAs("the stub offers one option, so the paint composes OPTN001O alone - and "
                        + "that line is byte-identical to the copybook's first")
                .isEqualTo(STUB_MENU_LINES);

        recordServiceOutcome(invocation, outcome);
        return null;
    }

    /**
     * {@code case12}: the filter's first conjunct isolated - an administrator is never refused.
     *
     * <p>The table and the option are identical to {@code case11}'s and only
     * {@code CDEMO-USER-TYPE} differs, so the two cases are a controlled comparison: one byte of input
     * produces a painted refusal versus a transfer. {@code 88 CDEMO-USRTYP-USER} is false for
     * {@code 'A'}, so {@code app/cbl/COMEN01C.cbl:136} cannot fire whatever the column says.
     *
     * @param invocation the seeded inputs, the pinned clock and the codec
     * @return {@code null}, meaning the recorder holds the observation
     */
    private static UnitOutcome runAdministratorNeverRefused(Invocation invocation) {
        MainMenuService service = service(invocation.codec());
        MainMenuInput input = inputOf(invocation);
        MainMenuOptionTable table = adminOnlyFirstEntryTable();

        MainMenuOutcome outcome = service.handle(input, table);
        assertServiceInvariants(service, input, outcome);

        assertThat(input.navigationContext().isUser())
                .describedAs("the caller is an administrator, so the first conjunct is false")
                .isFalse();
        assertThat(input.navigationContext().isAdmin()).isTrue();
        assertThat(service.isAdminOnlyOption(table, OptionalInt.of(1)))
                .describedAs("the SECOND conjunct is still true - the column really is 'A' - which is "
                        + "what makes this an isolation of the first")
                .isTrue();
        assertThat(outcome.errorFlag()).isFalse();
        assertThat(outcome.nextProgram())
                .describedAs("the dispatch at 152-155 transfers to the entry's own target program")
                .isEqualTo(picX(OPTION_PROGRAMS.get(0), NavigationContext.TO_PROGRAM_LENGTH));
        assertThat(outcome.nextProgramCarriesCommarea())
                .describedAs("line 154 specifies COMMAREA(CARDDEMO-COMMAREA), unlike the transfer at "
                        + "175-177")
                .isTrue();

        recordServiceOutcome(invocation, outcome);
        return null;
    }

    /**
     * {@code case13}: the {@code 'DUMMY'} five-byte-prefix branch, and the only way to the coming-soon
     * message.
     *
     * <p>{@code app/cpy/COMEN02Y.cpy} names no program beginning {@code DUMMY}, so the branch cannot be
     * reached from the copybook table at all - and it must still be exercised, because dead and
     * unreachable code is preserved rather than cleaned up. The table handed in therefore differs from
     * the copybook's first entry in <strong>nothing but the program name</strong>, so the composed
     * {@code OPTN001O} line is byte-identical to the real one and the prefix is the only variable.
     *
     * @param invocation the seeded inputs, the pinned clock and the codec
     * @return {@code null}, meaning the recorder holds the observation
     */
    private static UnitOutcome runDummyPrefixBranch(Invocation invocation) {
        MainMenuService service = service(invocation.codec());
        MainMenuInput input = inputOf(invocation);
        MainMenuOptionTable dummyTable = dummyPrefixTableFor(1);

        MainMenuOutcome outcome = service.handle(input, dummyTable);
        assertServiceInvariants(service, input, outcome);

        MenuOption stubbed = dummyTable.optionBySubscript(1).orElseThrow();
        assertThat(stubbed.menuOptPgmName())
                .describedAs("line 146 reference-modifies the first five characters, so the prefix is "
                        + "what matters and the remaining three bytes are ordinary name characters")
                .startsWith(DUMMY_PROGRAM_PREFIX)
                .hasSize(OPTION_PROGRAMS.get(0).length());
        assertThat(stubbed.menuOptName())
                .describedAs("the name is the copybook's own, so the composed line is unchanged")
                .isEqualTo(OPTION_NAMES.get(0));
        assertThat(outcome.hasNextProgram())
                .describedAs("line 146 found 'DUMMY', so the XCTL at 152-155 is skipped entirely")
                .isFalse();
        assertThat(outcome.screenPainted())
                .describedAs("lines 157-164 are reachable only here, and line 164 sends the map")
                .isTrue();
        assertThat(outcome.messageColourOverridden())
                .describedAs("line 158 MOVE DFHGREEN TO ERRMSGC overrides the mapset's COLOR=RED, and "
                        + "this is the only statement in the program that does")
                .isTrue();
        assertThat(outcome.errFlgImage())
                .describedAs("WS-ERR-FLG is untouched on this path: a skipped transfer is a successful "
                        + "outcome, not a rejection")
                .isEqualTo(MainMenuService.ERR_FLG_OFF);
        assertThat(outcome.message())
                .describedAs("lines 159-163 STRING all three operands, the middle one DELIMITED BY "
                        + "SPACE, and the suffix has no leading space")
                .isEqualTo(picX(COMING_SOON_MESSAGE, WS_MESSAGE_LENGTH));
        assertThat(outcome.optionLine(1))
                .describedAs("the injected entry differs from the copybook's only in its program name, "
                        + "so the composed line must be byte-identical to the real one")
                .isEqualTo(PAINTED_MENU_LINES.get(0));
        assertThat(outcome.optionLines())
                .describedAs("one active option, so one composed line and eleven blanks")
                .isEqualTo(STUB_MENU_LINES);

        recordServiceOutcome(invocation, outcome);
        return null;
    }

    /**
     * {@code case17}: {@code CDEMO-USER-TYPE} travels through unaltered, even when it names neither
     * condition.
     *
     * <p>{@code app/cbl/COMEN01C.cbl:150} - {@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE} - is commented
     * out, and it is the program's only mention of any {@code SEC-USER-DATA} field, so nothing here
     * derives, defaults or looks up the user type. A byte that satisfies neither
     * {@code 88 CDEMO-USRTYP-ADMIN} nor {@code 88 CDEMO-USRTYP-USER} must therefore come back exactly
     * as it arrived, and the dead record the program declares must stay blank throughout.
     *
     * @param invocation the seeded inputs, the pinned clock and the codec
     * @return {@code null}, meaning the recorder holds the observation
     */
    private static UnitOutcome runUserTypePassThrough(Invocation invocation) {
        MainMenuService service = service(invocation.codec());
        MainMenuInput input = inputOf(invocation);

        String suppliedUserType = input.navigationContext().userType();
        assertThat(input.navigationContext().isAdmin())
                .describedAs("the supplied type satisfies neither 88, which is what makes the "
                        + "pass-through non-vacuous")
                .isFalse();
        assertThat(input.navigationContext().isUser()).isFalse();

        MainMenuOutcome outcome = service.handle(input);
        assertServiceInvariants(service, input, outcome);

        assertThat(outcome.navigationContext().userType())
                .describedAs("line 150 is COMMENTED OUT, so CDEMO-USER-TYPE is inbound only and must "
                        + "cross the whole dispatch path unchanged")
                .isEqualTo(suppliedUserType);
        assertThat(outcome.navigationContext().userId())
                .describedAs("line 149 is commented out too, so CDEMO-USER-ID is likewise untouched")
                .isEqualTo(input.navigationContext().userId());
        assertThat(service.secUserData())
                .describedAs("implementing line 150 would need SEC-USR-TYPE, and the record COPY "
                        + "CSUSR01Y brings in is never read: it stays exactly as initialised")
                .isEqualTo(SecUserRecord.blank());
        assertThat(outcome.hasNextProgram())
                .describedAs("the filter's first conjunct is false for this type, so the option is "
                        + "dispatched rather than refused")
                .isTrue();

        recordServiceOutcome(invocation, outcome);
        return null;
    }

    /**
     * {@code case18}: the security file is seeded and available, and nothing touches it.
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
                .describedAs("app/cpy/CSUSR01Y.cpy declares SEC-USER-DATA at 80 bytes, so the seed must "
                        + "have been padded from the 57 characters app/jcl/DUSRSECJ.jcl carries")
                .isEqualTo(SecUserRecord.RECORD_LENGTH);
        List<String> before = List.copyOf(usrsec.rows());

        MainMenuService service = service(invocation.codec());
        MainMenuInput input = inputOf(invocation);
        MainMenuOutcome outcome = service.handle(input);
        assertServiceInvariants(service, input, outcome);

        assertThat(usrsec.rows())
                .describedAs("COMEN01C opens no dataset, so every seeded row must be exactly as it was "
                        + "seeded")
                .isEqualTo(before);
        assertThat(service.usrSecFileName())
                .describedAs("WS-USRSEC-FILE VALUE 'USRSEC  ' - the logical file name padded to its "
                        + "declared PIC X(08), with the source literal's two trailing spaces intact")
                .isEqualTo(USRSEC_FILE_NAME)
                .hasSize(USRSEC_FILE_NAME_LENGTH);
        assertThat(observableStrings(outcome))
                .describedAs("the dead declaration must not leak onto the screen: WS-USRSEC-FILE is "
                        + "never moved anywhere, so its value must appear in no field of the response")
                .noneMatch(image -> image.contains(USRSEC_DATASET_KEY));
        assertNoDataAccessCollaborator(service);

        recordServiceOutcome(invocation, outcome);
        return null;
    }

    /**
     * {@code case19}: statelessness, driven by running the same invocation three times.
     *
     * <p>Gates {@code G37} and {@code G40}, and rule {@code R6}. Twice against one service instance and
     * once against a freshly constructed one. All three outcomes must be equal: a translation holding
     * {@code WS-ERR-FLG}, {@code WS-MESSAGE}, {@code WS-OPTION} or {@code WS-IDX} in a field of a
     * singleton bean would leak the first call into the second, and a translation keeping the
     * communication area in a server-side session would have nothing to return. Only the first outcome
     * is reported, because one invocation produces one response.
     *
     * <p>The case ends in the {@code XCTL} at {@code app/cbl/COMEN01C.cbl:152-155}, which is the path
     * that mutates the most communication-area fields and therefore has the most to leak, and the
     * transfer target arrives as a {@code nextProgram} response field rather than as a server-side
     * forward - which is the whole of what {@code G40} asks of the eight {@code XCTL} sites.
     *
     * @param invocation the seeded inputs, the pinned clock and the codec
     * @return {@code null}, meaning the recorder holds the observation
     */
    private static UnitOutcome runThreeTimesForStatelessness(Invocation invocation) {
        MainMenuService shared = service(invocation.codec());
        MainMenuInput input = inputOf(invocation);

        MainMenuOutcome first = shared.handle(input);
        MainMenuOutcome second = shared.handle(input);
        MainMenuOutcome third = service(invocation.codec()).handle(input);

        assertServiceInvariants(shared, input, first);
        assertThat(second)
                .describedAs("a second call on the same instance must produce the identical outcome; "
                        + "anything else means per-request working storage became a field")
                .isEqualTo(first);
        assertThat(third)
                .describedAs("a fresh instance must produce the identical outcome; anything else means "
                        + "the outcome depended on something outside the request")
                .isEqualTo(first);
        assertThat(first.navigationContext().toFixedWidth(invocation.codec()))
                .describedAs("CARDDEMO-COMMAREA travels in the payload at exactly the %d bytes "
                        + "app/cpy/COCOM01Y.cpy declares, which is the only reason it is comparable",
                        COMMAREA_LENGTH)
                .hasSize(COMMAREA_LENGTH);
        assertNoMutableState(shared);

        recordServiceOutcome(invocation, first);
        return null;
    }

    /**
     * The controller adapter: construct {@link MainMenuController} over the service and the pinned
     * clock, and call its handler method directly as a plain Java object.
     *
     * <p>An {@code eibcalen} of zero is passed as an <strong>absent request body</strong>, because that
     * is how {@code GET /api/menu} expresses {@code IF EIBCALEN = 0} at
     * {@code app/cbl/COMEN01C.cbl:82} - the parameter is {@code required = false} and a plain
     * {@code GET} arrives {@code null}. No {@code MockMvc} and no servlet container is involved; the
     * method is invoked the way any other method is.
     *
     * @param invocation the seeded inputs, the pinned clock and the codec
     * @return {@code null}, meaning the recorder holds the observation
     */
    private static UnitOutcome runController(Invocation invocation) {
        MainMenuService service = service(invocation.codec());
        MainMenuController controller = new MainMenuController(service, invocation.clock());

        var envelope =
                controller.getMainMenu(invocation.eibcalen() == 0 ? null : requestOf(invocation));
        MainMenuResponse screen = envelope.screen();
        var metadata = envelope.screenMetadata();

        assertThat(screen.trnName()).isEqualTo(TRANSACTION_ID);
        assertThat(screen.pgmName()).isEqualTo(PROGRAM);
        assertThat(screen.title01()).isEqualTo(CCDA_TITLE01);
        assertThat(screen.title02()).isEqualTo(CCDA_TITLE02);
        assertThat(screen.errMsg())
                .describedAs("MOVE WS-MESSAGE TO ERRMSGO at line 187 narrows PIC X(80) to PIC X(78)")
                .hasSize(ERRMSG_LENGTH);
        assertThat(metadata.resetAllOutputFields())
                .describedAs("MOVE LOW-VALUES TO COMEN1AO at line 89 runs on the first-entry path "
                        + "alone, and the flag must reach the client beside the screen rather than "
                        + "inside it, because it is not a DFHMDF field")
                .isEqualTo(screen.resetAllOutputFields());
        assertThat(metadata.messageColour())
                .describedAs("ERRMSGC travels as metadata, unsigned, because the attribute IBM "
                        + "documents as X'F2' is a negative Java byte")
                .isEqualTo(Byte.toUnsignedInt(screen.errMsgColor()));
        assertThat(metadata.cursorField())
                .describedAs("COMEN01C never moves -1 into an xxxL item, so no cursor is requested")
                .isNull();
        assertTruncationKeptTheLeadingBytes(screen.errMsg());

        boolean transferred = !isBlank(screen.nextProgram());
        List<ObservedSend> sends = transferred
                ? List.of()
                : List.of(new ObservedSend(controllerSendFields(screen), colour(screen.errMsgColor())));

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
     * send, because it is {@code WORKING-STORAGE} at {@code app/cbl/COMEN01C.cbl:38} and not a screen
     * field: the screen field is the seventy-eight-byte {@code ERRMSGO} that line 187 moves it into,
     * and only the controller produces that. Keeping the two on separate channels is what makes the
     * narrowing visible - the service cases pin the eighty and {@code case16} the seventy-eight - and a
     * comparison across channels is refused by the differ.
     *
     * <p>One invocation can paint twice: the validation at line 133 and the filter at line 142 are
     * consecutive ungated {@code IF} statements and both can fire in the same pass, as {@code case10}
     * demonstrates. The outcome carries the <strong>final</strong> state, which is the one the operator
     * sees, so one {@code ObservedSend} is the accurate report of a painted path.
     *
     * @param invocation the run being recorded into
     * @param outcome    what the service produced
     */
    private static void recordServiceOutcome(Invocation invocation, MainMenuOutcome outcome) {
        boolean transferred = outcome.hasNextProgram();
        assertThat(transferred)
                .describedAs("exactly one of EXEC CICS XCTL and EXEC CICS SEND MAP happens on any one "
                        + "path through COMEN01C, so a run reporting both or neither has lost the shape "
                        + "of the program")
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
     * <p>Neither record channel is written to at all, and that is the observation {@code case18} makes:
     * {@code COMEN01C} touches no dataset, so an empty {@code writes} and an empty {@code finalState}
     * is the accurate report rather than an omission.
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
     * <p>That mapping is required rather than cosmetic. {@link MainMenuOutcome} carries an eight-space
     * {@code nextProgram} on a {@code SEND} path and a seven-space mapset and map on a transfer path,
     * because a COBOL {@code PIC X} field is never absent - but {@code ExpectedResponse} validates
     * those three against program-name and map-name patterns that reject spaces, and the differ compares
     * them with {@code Objects.equals}. So a blank field has to arrive as "not stated here", which is
     * what {@code null} means on both sides.
     *
     * <p>The termination follows the same fact from the other direction: an {@code XCTL} transfers
     * control and never reaches the {@code EXEC CICS RETURN} at lines 107 to 110, so exactly one of the
     * two terminations applies and it is decided by whether a successor was named. {@code COMEN01C} has
     * no {@code SEND-PLAIN-TEXT} paragraph, so {@link Termination#RETURN_NO_TRANSID} cannot arise here.
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
     * The thirteen {@code COMEN1AO} items a service run produces: the twelve menu lines and the echoed
     * option.
     *
     * <p>{@code ERRMSGO} is deliberately absent. The service produces {@code WS-MESSAGE} at eighty
     * bytes, and manufacturing a seventy-eight-byte screen field from it here would be this test doing
     * the work line 187 does - which is precisely the work {@code case16} exists to check.
     *
     * @param outcome what the service produced
     * @return the send's payload items, keyed by symbolic-map name in map order
     */
    private static Map<String, String> serviceSendFields(MainMenuOutcome outcome) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int subscript = 1; subscript <= MENU_LINE_COUNT; subscript++) {
            fields.put(menuLineField(subscript), outcome.optionLine(subscript));
        }
        fields.put(OPTION_FIELD, outcome.option());
        return fields;
    }

    /**
     * All twenty {@code COMEN1AO} items a controller run produces, in map order.
     *
     * <p>Twenty is the count of name-labelled {@code DFHMDF} fields in {@code app/bms/COMEN01.bms}, and
     * each width comes from the matching {@code xxxI} item of {@code app/cpy-bms/COMEN01.CPY} - the
     * {@code xxxL}, {@code xxxF} and {@code xxxA} items are length, flag and attribute metadata and are
     * not payload, which is why only {@code ERRMSGC} appears and only as an attribute.
     *
     * @param screen the projected screen
     * @return the send's payload items, keyed by symbolic-map name in map order
     */
    private static Map<String, String> controllerSendFields(MainMenuResponse screen) {
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
     * The one attribute item {@code COMEN01C} writes, valued with its {@code DFHBMSCA} mnemonic.
     *
     * <p>Named rather than numeric because colour is behaviour in this program, not decoration: the map
     * declares {@code COLOR=RED} and line 158 overrides it with {@code DFHGREEN} on the coming-soon path
     * alone, which is how the screen distinguishes a refusal from a confirmation.
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
     * <p>{@code COMEN01C} declares no {@code SELECT} and no {@code FD}, and its five {@code EXEC CICS}
     * statements are one {@code RETURN}, two {@code XCTL}, one {@code SEND MAP} and one
     * {@code RECEIVE MAP} - so an empty {@code writes} and an empty {@code finalState} is the accurate
     * report of what it did, and the harness explicitly authorises empty channels for a unit that
     * touches no dataset at all. {@code case18} makes the assertion non-vacuous by seeding the security
     * file first.
     *
     * @param parityCase  the case that ran, named in the failure
     * @param fingerprint what the run produced
     */
    private static void assertNoDatasetActivity(ParityCase parityCase,
            DecodedFingerprint fingerprint) {
        assertThat(fingerprint.writes())
                .describedAs("%s: COMEN01C has no WRITE, REWRITE or DELETE anywhere in its 282 lines, "
                        + "so nothing may appear on the writes channel", parityCase.caseId())
                .isEmpty();
        assertThat(fingerprint.finalState())
                .describedAs("%s: COMEN01C opens no dataset, so no dataset may appear on the final "
                        + "state channel either", parityCase.caseId())
                .isEmpty();
    }

    /**
     * The transaction ended normally, for any case.
     *
     * <p>{@code COMEN01C} is reached by CICS transaction {@code CM00} rather than by
     * {@code EXEC PGM=}, contains no {@code CALL 'CEE3ABD'} and sets no {@code RETURN-CODE}: all three
     * of its terminations - {@code EXEC CICS RETURN} at lines 107 to 110 and the two {@code XCTL}
     * statements - are successful, so zero is the only value any of the twenty cases may report.
     *
     * @param parityCase  the case that ran, named in the failure
     * @param fingerprint what the run produced
     */
    private static void assertReturnCodeIsNormal(ParityCase parityCase,
            DecodedFingerprint fingerprint) {
        assertThat(fingerprint.returnCode())
                .describedAs("%s: COMEN01C is an online program with no abend site and no RETURN-CODE "
                        + "of its own", parityCase.caseId())
                .isZero();
    }

    /**
     * The invariants every service run must satisfy, whichever of {@code COMEN01C}'s paths it took.
     *
     * @param service the unit, for its normalisation intermediates
     * @param input   the three values the program consulted
     * @param outcome what the run produced
     */
    private static void assertServiceInvariants(MainMenuService service, MainMenuInput input,
            MainMenuOutcome outcome) {
        assertScreenIdentity(outcome);
        assertOptionEcho(service, input, outcome);
        assertEvaluateArm(input, outcome);
        assertMessageIsOneTheProgramCanProduce(outcome);
        assertGreenOverrideOnlyOnReenter(input, outcome);
    }

    /**
     * The transaction, mapset and map the run names are the ones the CSD and the source agree on.
     *
     * <p>{@code EXEC CICS RETURN TRANSID(WS-TRANID)} at lines 107 to 110 always names {@code CM00}, and
     * {@code MAPSET('COMEN01')} at line 191 and {@code MAP('COMEN1A')} at line 190 are the only map
     * names the program mentions at all. The translation therefore carries all three as
     * <strong>program identity</strong> rather than as a report of what the last statement did: the
     * outcome names {@code COMEN01} and {@code COMEN1A} on every path, including the two transfer paths,
     * and the controller projects both onto the response whichever path ran.
     *
     * <p>That is asserted unconditionally here, and the unconditionality is itself the point. It would
     * be easy to assume a transfer must leave the pair blank - the near-twin admin-menu translation does
     * exactly that - and to write the expectation as a conditional that then agrees with whatever the
     * code happens to do on each path. Stating the constants once means a translation that started
     * blanking them on a transfer would fail here and in the two transfer cases, rather than passing
     * quietly. What the program never writes is the communication area's own {@code CDEMO-LAST-MAP} and
     * {@code CDEMO-LAST-MAPSET}, which every case requires to be the seven spaces they arrived as.
     *
     * @param outcome what the run produced
     */
    private static void assertScreenIdentity(MainMenuOutcome outcome) {
        assertThat(outcome.transactionId())
                .describedAs("RETURN TRANSID(WS-TRANID) at line 108, and DEFINE TRANSACTION(CM00) "
                        + "PROGRAM(COMEN01C) in app/csd/CARDDEMO.CSD")
                .isEqualTo(TRANSACTION_ID);
        assertThat(outcome.mapsetName())
                .describedAs("MAPSET('COMEN01') at line 191 is the only mapset COMEN01C names, on every "
                        + "path including a transfer")
                .isEqualTo(MAPSET_NAME);
        assertThat(outcome.mapName())
                .describedAs("MAP('COMEN1A') at line 190 is the only map COMEN01C names")
                .isEqualTo(MAP_NAME);
        assertThat(outcome.navigationContext().lastMapset())
                .describedAs("CDEMO-LAST-MAPSET is a COMMAREA field and COMEN01C never writes it, so it "
                        + "stays exactly as it arrived - which is what distinguishes the program's own "
                        + "map identity from the conversation's record of it")
                .isBlank();
        assertThat(outcome.navigationContext().lastMap())
                .describedAs("CDEMO-LAST-MAP is likewise never written by this program")
                .isBlank();
    }

    /**
     * {@code OPTIONO} carries the value the path it was on actually puts there - one of three.
     *
     * <p>Three paths write the echoed option three different ways, and the differences are the
     * assertion:
     * <ul>
     *   <li><strong>First entry</strong> - {@code MOVE LOW-VALUES TO COMEN1AO} at line 89 clears every
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
     * not read back from {@link MainMenuService#normaliseOption(String)}; that method's own
     * intermediates are then checked against it, so the two transcriptions confirm each other.
     *
     * @param service the unit, for its normalisation intermediates
     * @param input   the three values the program consulted
     * @param outcome what the run produced
     */
    private static void assertOptionEcho(MainMenuService service, MainMenuInput input,
            MainMenuOutcome outcome) {
        String asDelivered = picX(input.option(), OPTION_LENGTH);

        if (input.isCommareaPresent() && !input.isReenter()) {
            assertThat(outcome.option())
                    .describedAs("MOVE LOW-VALUES TO COMEN1AO at line 89 clears OPTIONO before the "
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
                .describedAs("line 125 MOVE WS-OPTION TO OPTIONO echoes the normalised option, so '%s' "
                        + "becomes '%s'", asDelivered, expectedEcho)
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
     * <p>{@code app/cbl/COMEN01C.cbl:93-103} is an ordered {@code EVALUATE}: {@code DFHENTER} first,
     * {@code DFHPF3} second, {@code WHEN OTHER} last. The three are mutually exclusive here because the
     * first two compare distinct bytes, so which arm ran is decided by the byte alone - and this check
     * pins the observable consequence of each.
     *
     * <p>It applies only where the {@code EVALUATE} is actually reached. Line 93 sits inside the
     * {@code ELSE} of the re-enter test at line 87 and inside the {@code ELSE} of the
     * {@code EIBCALEN = 0} test at line 82, so on a cold start or a first entry the AID is never read
     * and asserting an arm would be asserting a branch the program did not take.
     *
     * @param input   the three values the program consulted
     * @param outcome what the run produced
     */
    private static void assertEvaluateArm(MainMenuInput input, MainMenuOutcome outcome) {
        if (!input.isCommareaPresent() || !input.isReenter()) {
            return;
        }
        byte eibAid = input.eibAid();
        if (PfKeyResolver.isAid(eibAid, CicsAid.DFHENTER)) {
            assertThat(outcome.message())
                    .describedAs("the DFHENTER arm performs PROCESS-ENTER-KEY, which never produces the "
                            + "invalid-key text")
                    .isNotEqualTo(picX(CCDA_MSG_INVALID_KEY, WS_MESSAGE_LENGTH));
            return;
        }
        if (PfKeyResolver.isAid(eibAid, CicsAid.DFHPF3)) {
            assertThat(outcome.nextProgram())
                    .describedAs("line 97 moves 'COSGN00C' into CDEMO-TO-PROGRAM and lines 175-177 "
                            + "transfer to it")
                    .isEqualTo(picX(SIGNON_PROGRAM, NavigationContext.TO_PROGRAM_LENGTH));
            assertThat(outcome.nextProgramCarriesCommarea())
                    .describedAs("the XCTL at 175-177 specifies NO COMMAREA, unlike the one at 152-155")
                    .isFalse();
            return;
        }
        assertThat(outcome.errFlgImage())
                .describedAs("line 100 MOVE 'Y' TO WS-ERR-FLG on the WHEN OTHER arm")
                .isEqualTo(MainMenuService.ERR_FLG_ON);
        assertThat(outcome.message())
                .describedAs("line 101 moves CCDA-MSG-INVALID-KEY, a PIC X(50) sender, into the "
                        + "PIC X(80) WS-MESSAGE - left-justified, space-padded to eighty")
                .isEqualTo(picX(CCDA_MSG_INVALID_KEY, WS_MESSAGE_LENGTH));
        assertThat(outcome.screenPainted())
                .describedAs("line 102 performs SEND-MENU-SCREEN")
                .isTrue();
    }

    /**
     * The eighty-byte message is one of the five images {@code COMEN01C} can actually produce.
     *
     * <p>An enumeration rather than a width check. {@code WS-MESSAGE} is written at exactly four sites -
     * lines 101, 131, 140 and 159 to 163 - plus the spaces line 79 initialises it to, so any other
     * eighty-character image means the translation composed something the program cannot say. That is a
     * different and stronger statement than "the field is eighty bytes wide".
     *
     * @param outcome what the run produced
     */
    private static void assertMessageIsOneTheProgramCanProduce(MainMenuOutcome outcome) {
        assertThat(outcome.message())
                .describedAs("WS-MESSAGE is written at lines 101, 131, 140 and 159-163 and nowhere "
                        + "else, so no other eighty-byte image is producible")
                .isIn(PRODUCIBLE_WS_MESSAGES)
                .hasSize(WS_MESSAGE_LENGTH);
    }

    /**
     * All four steps of the option normalisation, over the inputs that pin it down.
     *
     * <p>{@code app/cbl/COMEN01C.cbl:117-125} is a descending test-before scan, a reference-modified
     * {@code MOVE} into a {@code JUSTIFIED RIGHT} receiver, an {@code INSPECT ... REPLACING} and a
     * cross-category {@code MOVE} into {@code PIC 9(02)}. The rows below are the whole behaviour: a
     * blank field, a leading space that is <em>not</em> relocated because the widths are equal, a
     * trailing space that <em>is</em>, two digits that cross untouched, the two-digit maximum the active
     * count admits, and a non-digit that survives the move into the numeric field - which is what gives
     * {@code IS NOT NUMERIC} at line 127 something to detect. Every expected value is derived from the
     * COBOL rules in {@link #normalisedOption(String)} and {@link #justifiedOption(String)}, not read
     * back from the translation.
     */
    @Test
    @DisplayName("reproduces the four-step option normalisation over its six worked inputs")
    void theOptionNormalisationReproducesAllFourSteps() {
        MainMenuService service = service(ParityHarness.usAscii().codec());

        record Worked(String received, int wsIdx, String justified, String optionX, boolean numeric) {
        }
        List<Worked> worked = List.of(
                new Worked("  ", 1, "  ", "00", true),
                new Worked(" 3", 2, " 3", "03", true),
                new Worked("3 ", 1, " 3", "03", true),
                new Worked("10", 2, "10", "10", true),
                new Worked("99", 2, "99", "99", true),
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
     * <p>Gate {@code G30} asks for the arms in source order with {@code WHEN OTHER} last, and order only
     * means something if the default really is a default. So every mnemonic {@code common.CicsAid}
     * defines is driven here: {@code DFHENTER} must reach {@code PROCESS-ENTER-KEY}, {@code DFHPF3} must
     * transfer to the sign-on program, and <strong>every other AID</strong> - including the ones
     * {@link PfKeyResolver} resolves to a real key token, such as {@code DFHPF15}, which
     * {@code app/cpy/CSSTRPFY.cpy} folds onto the same {@code PFK03} token as {@code DFHPF3} - must
     * produce the invalid-key message. That last part is the point: {@code COMEN01C} does not
     * {@code COPY CSSTRPFY}, so resolving the byte to a token and branching on the token would make
     * {@code PF15} behave like {@code PF3} in a program that has no such behaviour.
     */
    @Test
    @DisplayName("routes DFHENTER and DFHPF3 to their own arms and every other AID to WHEN OTHER")
    void theEvaluateEibaidArmsAreOrderedAndExhaustive() {
        MainMenuService service = service(ParityHarness.usAscii().codec());
        NavigationContext reentered = userReentered();
        String invalidKeyImage = picX(CCDA_MSG_INVALID_KEY, WS_MESSAGE_LENGTH);
        int otherArmCount = 0;

        for (Map.Entry<String, Byte> aid : AID_BY_MNEMONIC.entrySet()) {
            byte eibAid = aid.getValue();
            MainMenuOutcome outcome =
                    service.handle(new MainMenuInput(reentered, eibAid, BLANK_OPTION));

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
                assertThat(outcome.errFlgImage()).isEqualTo(MainMenuService.ERR_FLG_ON);
            }
        }

        assertThat(otherArmCount)
                .describedAs("WHEN OTHER must catch every AID but the two named ones; a count of zero "
                        + "would mean this walk drove nothing")
                .isEqualTo(AID_BY_MNEMONIC.size() - 2);

        // A byte DFHAID names nothing for. EIBAID is one byte and a terminal can present any of the
        // 256, so the WHEN OTHER arm has to absorb the ones the copybook has no mnemonic for as well as
        // the ones it does - and it must do so without consulting a resolver that would have to answer
        // "no match" for them. No case file can declare this byte, because ScreenRequest validates its
        // AID against the mnemonics, which is exactly why it is driven here instead.
        assertThat(PfKeyResolver.resolve(UNRESOLVABLE_AID))
                .describedAs("the resolver must report an explicit no-match rather than substituting a "
                        + "default, which is what makes this byte a genuine unnamed AID")
                .isEmpty();
        assertThat(AID_BY_MNEMONIC.values())
                .describedAs("and the byte must genuinely be outside the DFHAID table")
                .doesNotContain(UNRESOLVABLE_AID);
        MainMenuOutcome unnamed =
                service.handle(new MainMenuInput(reentered, UNRESOLVABLE_AID, BLANK_OPTION));
        assertThat(unnamed.message())
                .describedAs("an AID with no mnemonic at all still lands on WHEN OTHER at lines 99-102")
                .isEqualTo(invalidKeyImage);
        assertThat(unnamed.errFlgImage()).isEqualTo(MainMenuService.ERR_FLG_ON);
        assertThat(unnamed.screenPainted()).isTrue();
    }

    /**
     * The {@code DFHGREEN} override can only happen on a re-entry, and only on the coming-soon path.
     *
     * <p>Gate {@code G38} asks for both the {@code ENTER} and the {@code REENTER} path, with the
     * highlight applying only in the re-entered state. {@code COMEN01C} has no {@code CSSETATY} field
     * highlight - it does not copy that member - so its one and only attribute write is
     * {@code MOVE DFHGREEN TO ERRMSGC} at line 158, and that statement sits inside
     * {@code PROCESS-ENTER-KEY}, which the {@code DFHENTER} arm at line 95 performs and which is reached
     * only through the {@code ELSE} of the re-enter test at line 87. So the first-entry paint, the
     * absent-commarea path and every non-{@code ENTER} arm must all leave the mapset's declared
     * {@code COLOR=RED} in place, and this is the invariant that says so on every case rather than
     * leaving it to the three cases that happen to be first entries.
     *
     * @param input   the three values the program consulted
     * @param outcome what the run produced
     */
    private static void assertGreenOverrideOnlyOnReenter(MainMenuInput input,
            MainMenuOutcome outcome) {
        if (input.isReenter() && PfKeyResolver.isAid(input.eibAid(), CicsAid.DFHENTER)) {
            return;
        }
        assertThat(outcome.messageColourOverridden())
                .describedAs("line 158 is inside PROCESS-ENTER-KEY, which only the DFHENTER arm of a "
                        + "re-entry performs, so ERRMSGC must still hold the mapset's COLOR=RED here")
                .isFalse();
        assertThat(outcome.messageColour())
                .describedAs("and the byte itself must be the map's declared colour")
                .isEqualTo(MainMenuService.MAP_MESSAGE_COLOUR);
    }

    /**
     * Every one of the ten copybook options is open to a regular user, and none is refused.
     *
     * <p>This is the "filtered set" the prompt asks a case to pin, stated across all ten rather than
     * sampled. All ten {@code CDEMO-MENU-OPT-USRTYPE} columns of {@code app/cpy/COMEN02Y.cpy} carry
     * {@code 'U'}, so the second conjunct of {@code app/cbl/COMEN01C.cbl:137} is false for every option
     * a regular user can legitimately choose and the {@code No access} outcome is unreachable from the
     * shipped table. That is a property of the data rather than of the code, and asserting it option by
     * option is what would catch a single altered column locking a menu entry out of the regular-user
     * menu. It is also what makes {@code case11}'s stub table necessary rather than merely convenient.
     */
    @Test
    @DisplayName("opens all ten copybook options to a regular user, refusing none")
    void everyCopybookOptionIsOpenToARegularUser() {
        MainMenuService service = service(ParityHarness.usAscii().codec());
        MainMenuOptionTable copybook = MainMenuOptionTable.copybook();

        for (int subscript = 1; subscript <= ACTIVE_OPTION_COUNT; subscript++) {
            String option = String.format(Locale.ROOT, "%02d", subscript);
            MainMenuOutcome outcome = service.handle(
                    new MainMenuInput(userReentered(), CicsAid.DFHENTER, option));

            assertThat(service.isAdminOnlyOption(copybook, OptionalInt.of(subscript)))
                    .describedAs("option %d's authorisation column is 'U', not 'A'", subscript)
                    .isFalse();
            assertThat(outcome.errorFlag())
                    .describedAs("lines 136-137 cannot fire for option %d", subscript)
                    .isFalse();
            assertThat(outcome.message())
                    .describedAs("no message at all is emitted on a successful transfer")
                    .isEqualTo(BLANK_WS_MESSAGE);
            assertThat(outcome.nextProgram())
                    .describedAs("option %d transfers to its own target program", subscript)
                    .isEqualTo(picX(OPTION_PROGRAMS.get(subscript - 1),
                            NavigationContext.TO_PROGRAM_LENGTH));
        }
    }

    /**
     * {@code MOVE WS-MESSAGE TO ERRMSGO} kept the leading bytes, not the trailing ones.
     *
     * <p>{@code app/cbl/COMEN01C.cbl:187} sends {@code PIC X(80)} to {@code PIC X(78)}, and a COBOL
     * alphanumeric {@code MOVE} fills the receiver from the left and discards the overflow. So the
     * observed field must equal the leading seventy-eight characters of one of the five eighty-byte
     * images {@code COMEN01C} can produce, and must not equal that image's trailing seventy-eight -
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
                .describedAs("ERRMSGO must be the LEADING 78 characters of one of the five 80-byte "
                        + "images COMEN01C can put in WS-MESSAGE")
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
     * <p>Reading the seeded rows back unchanged shows this run touched nothing; this shows no run could.
     * {@code COMEN01C} has no {@code SELECT}, no {@code FD} and no file-handling {@code EXEC CICS}
     * command, so a repository, a {@code JdbcTemplate}, a {@code DataSource} or a {@code Connection} on
     * the service would be a capability the program does not have.
     *
     * @param service the unit under test
     */
    private static void assertNoDataAccessCollaborator(MainMenuService service) {
        for (Field field : service.getClass().getDeclaredFields()) {
            String type = field.getType().getName();
            for (String forbidden : DATA_ACCESS_TYPE_MARKERS) {
                assertThat(type.toLowerCase(Locale.ROOT))
                        .describedAs("field '%s' is a %s, but COMEN01C performs no dataset access at "
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
     * Every declared instance field must therefore be {@code final}, and none may be an array, whose
     * elements stay writable however final the reference is.
     *
     * @param service the unit under test
     */
    private static void assertNoMutableState(MainMenuService service) {
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
    private static MainMenuService service(FixedWidthCodec codec) {
        return new MainMenuService(usrSecBindings(), codec);
    }

    /**
     * The {@code carddemo.datasets} catalogue holding the one entry the service resolves.
     *
     * <p>{@link MainMenuService} needs a catalogue for a single reason: {@code WS-USRSEC-FILE} at
     * {@code app/cbl/COMEN01C.cbl:39} is resolved from configuration rather than from a literal, so no
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
     * The three values {@code COMEN01C} consults, taken from the case's declared invocation.
     *
     * <p>An {@code eibcalen} of zero becomes an absent communication area, because absence <em>is</em>
     * the representation of {@code IF EIBCALEN = 0} at {@code app/cbl/COMEN01C.cbl:82} - a separate flag
     * would be a second source of truth able to contradict the reference beside it. The option is passed
     * through unnormalised, spaces and all, because lines 117 to 125 are what consume it and trimming it
     * here would destroy their input.
     *
     * @param invocation the run
     * @return the service input
     */
    private static MainMenuInput inputOf(Invocation invocation) {
        byte eibAid = aidByte(invocation);
        String option = invocation.mapFields().getOrDefault(OPTION_INPUT_FIELD, BLANK_OPTION);
        NavigationContext context = commareaContext(invocation);
        return context == null
                ? MainMenuInput.withoutCommarea(eibAid, option)
                : new MainMenuInput(context, eibAid, option);
    }

    /**
     * The inbound payload a client continuing the pseudo-conversation would send back.
     *
     * <p>All twenty screen fields are present at their declared widths, because a {@code PIC X} field is
     * never absent, and nineteen of them are spaces: {@code COMEN01C} reads {@code OPTIONI} and nothing
     * else, so supplying the rest as blanks reproduces a re-supplied blank screen and simultaneously
     * demonstrates that the program ignores them.
     *
     * @param invocation the run
     * @return the request payload
     */
    private static MainMenuRequest requestOf(Invocation invocation) {
        return new MainMenuRequest(spaces(TRN_NAME_LENGTH),
                spaces(TITLE_LENGTH),
                spaces(CURDATE_LENGTH),
                spaces(PGM_NAME_LENGTH),
                spaces(TITLE_LENGTH),
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
                    .describedAs("only the EIBCALEN = 0 path may omit the AID; every other path reaches "
                            + "the EVALUATE at line 93 and the byte it compares is behaviour")
                    .isZero();
            return CicsAid.DFHENTER;
        }
        Byte eibAid = AID_BY_MNEMONIC.get(mnemonic);
        assertThat(eibAid)
                .describedAs("'%s' is not a DFHAID mnemonic; common.CicsAid is the single reproduction "
                        + "of that IBM-supplied copybook, which is absent from this repository", mnemonic)
                .isNotNull();
        return eibAid;
    }

    /**
     * A communication area in {@code CDEMO-PGM-REENTER} state carrying user type {@code 'U'} - the state
     * {@code COSGN00C} hands a regular user to {@code COMEN01C} in, after one keystroke.
     *
     * @return a fresh context, never {@code null}
     */
    private static NavigationContext userReentered() {
        return NavigationContext.empty().withPgmReenter().withUserTypeUser();
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
     * {@code app/cbl/COMEN01C.cbl:117-122} and the {@code JUST RIGHT} receiver at line 45.
     *
     * <p>The descending scan is written as the same test-before loop the source uses, rather than as a
     * trim or a last-index search, so the reason the answer is 1 for {@code "3 "} stays visible: the loop
     * sets {@code WS-IDX} from {@code LENGTH OF OPTIONI}, tests, and only then decrements.
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
     * {@code app/cbl/COMEN01C.cbl:123}: {@code INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'}.
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
     * mapset and map names - which between them is everything of {@code COMEN01C}'s state that can reach
     * a terminal.
     *
     * @param outcome what the run produced
     * @return the images, in no particular order
     */
    private static List<String> observableStrings(MainMenuOutcome outcome) {
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
     * The twelve menu lines a paint produces for a stated active count, composed from the transcribed
     * copybook literals.
     *
     * <p>{@code app/cbl/COMEN01C.cbl:243-246} is
     * {@code STRING CDEMO-MENU-OPT-NUM, '. ', CDEMO-MENU-OPT-NAME DELIMITED BY SIZE INTO PIC X(40)} -
     * all three operands {@code DELIMITED BY SIZE}, so the whole thirty-five-byte name goes in and the
     * two-plus-two-plus-thirty-five character result is space-padded to forty by the receiver.
     *
     * @param activeCount how many leading lines the loop at line 238 composes
     * @return {@code activeCount} composed lines followed by blanks to twelve
     */
    private static List<String> paintedMenuLines(int activeCount) {
        List<String> lines = new ArrayList<>(MENU_LINE_COUNT);
        for (int subscript = 1; subscript <= activeCount; subscript++) {
            lines.add(picX(String.format(Locale.ROOT, "%02d", subscript) + OPTION_NUMBER_SEPARATOR
                    + OPTION_NAMES.get(subscript - 1), MENU_LINE_LENGTH));
        }
        while (lines.size() < MENU_LINE_COUNT) {
            lines.add(BLANK_MENU_LINE);
        }
        return List.copyOf(lines);
    }

    /**
     * The twelve {@code OCCURS} slots of {@code app/cpy/COMEN02Y.cpy}, built from this class's own
     * transcription so the shifted variants below are independent of the translation's table.
     *
     * @return ten valued slots followed by two empty ones
     */
    private static List<Optional<MenuOption>> copybookSlots() {
        List<Optional<MenuOption>> slots = new ArrayList<>(OCCURS_TABLE_SIZE);
        for (int subscript = 1; subscript <= ACTIVE_OPTION_COUNT; subscript++) {
            slots.add(Optional.of(MenuOption.of(subscript, OPTION_NAMES.get(subscript - 1),
                    OPTION_PROGRAMS.get(subscript - 1), REGULAR_USER_USRTYPE)));
        }
        while (slots.size() < OCCURS_TABLE_SIZE) {
            slots.add(Optional.empty());
        }
        return slots;
    }

    /**
     * The table a 0-based read of the 1-based subscript would effectively address: every entry moved one
     * slot earlier, cyclically so the table stays constructible.
     *
     * @return the rotated table, used only to prove the menu expectations have teeth
     */
    private static MainMenuOptionTable rotatedByOne() {
        List<Optional<MenuOption>> slots = new ArrayList<>(OCCURS_TABLE_SIZE);
        for (int subscript = 1; subscript <= ACTIVE_OPTION_COUNT; subscript++) {
            int rotated = subscript % ACTIVE_OPTION_COUNT + 1;
            slots.add(Optional.of(MenuOption.of(rotated, OPTION_NAMES.get(rotated - 1),
                    OPTION_PROGRAMS.get(rotated - 1), REGULAR_USER_USRTYPE)));
        }
        while (slots.size() < OCCURS_TABLE_SIZE) {
            slots.add(Optional.empty());
        }
        return new MainMenuOptionTable(slots, ACTIVE_OPTION_COUNT);
    }

    /**
     * The copybook table with its active count one lower - what an exclusive loop bound would produce.
     *
     * @return a table offering nine options rather than ten
     */
    private static MainMenuOptionTable truncatedByOne() {
        return new MainMenuOptionTable(copybookSlots(), ACTIVE_OPTION_COUNT - 1);
    }

    /**
     * The copybook's first entry with its authorisation column changed to {@code 'A'} and nothing else,
     * offered as the only active option.
     *
     * <p>The shape {@code case11} and {@code case12} need to reach the filter's true arm, which
     * {@code app/cpy/COMEN02Y.cpy} cannot produce because all ten of its columns are {@code 'U'}. Varying
     * one column and nothing else is what keeps the composed {@code OPTN001O} line byte-identical to the
     * real one, so the column is the only variable under test.
     *
     * @return a twelve-slot table with one valued, admin-only entry and an active count of one
     */
    private static MainMenuOptionTable adminOnlyFirstEntryTable() {
        List<Optional<MenuOption>> slots = new ArrayList<>(OCCURS_TABLE_SIZE);
        slots.add(Optional.of(MenuOption.of(1, OPTION_NAMES.get(0), OPTION_PROGRAMS.get(0),
                ADMIN_ONLY_USRTYPE)));
        while (slots.size() < OCCURS_TABLE_SIZE) {
            slots.add(Optional.empty());
        }
        return new MainMenuOptionTable(slots, 1);
    }

    /**
     * A table whose active count is one but whose third slot is a valued admin-only entry.
     *
     * <p>The shape {@code case10} needs: option 3 fails the guard at
     * {@code app/cbl/COMEN01C.cbl:128} because it exceeds the active count, and the ungated filter at
     * line 137 then finds a real {@code 'A'} column at that very subscript - so both {@code IF}
     * statements fire in one pass and the second paint is the one the terminal shows. Slots 1, 2 and 4
     * carry the copybook's own entries so that only slot 3's column differs from the shipped table.
     *
     * @return a twelve-slot table with four valued entries and an active count of one
     */
    private static MainMenuOptionTable adminOnlySlotThreeTable() {
        List<Optional<MenuOption>> slots = new ArrayList<>(OCCURS_TABLE_SIZE);
        for (int subscript = 1; subscript <= 4; subscript++) {
            String usrType = subscript == 3 ? ADMIN_ONLY_USRTYPE : REGULAR_USER_USRTYPE;
            slots.add(Optional.of(MenuOption.of(subscript, OPTION_NAMES.get(subscript - 1),
                    OPTION_PROGRAMS.get(subscript - 1), usrType)));
        }
        while (slots.size() < OCCURS_TABLE_SIZE) {
            slots.add(Optional.empty());
        }
        return new MainMenuOptionTable(slots, 1);
    }

    /**
     * A table whose leading entries carry the copybook's own names but a {@value #DUMMY_PROGRAM_PREFIX}
     * program name, so {@code app/cbl/COMEN01C.cbl:146} skips the transfer and the coming-soon message
     * at 157 to 164 becomes reachable.
     *
     * <p>The name is deliberately unchanged, because the message composes from
     * {@code CDEMO-MENU-OPT-NAME} and changing it would move the assertion off the
     * {@code DELIMITED BY SPACE} rule and onto a literal of this file's own invention.
     *
     * @param activeCount how many leading slots to value, which is also {@code CDEMO-MENU-OPT-COUNT}
     * @return a twelve-slot table whose valued entries all target a {@code DUMMY} program
     */
    private static MainMenuOptionTable dummyPrefixTableFor(int activeCount) {
        List<Optional<MenuOption>> slots = new ArrayList<>(OCCURS_TABLE_SIZE);
        for (int subscript = 1; subscript <= activeCount; subscript++) {
            slots.add(Optional.of(MenuOption.of(subscript, OPTION_NAMES.get(subscript - 1),
                    DUMMY_PROGRAM_NAME, REGULAR_USER_USRTYPE)));
        }
        while (slots.size() < OCCURS_TABLE_SIZE) {
            slots.add(Optional.empty());
        }
        return new MainMenuOptionTable(slots, activeCount);
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
