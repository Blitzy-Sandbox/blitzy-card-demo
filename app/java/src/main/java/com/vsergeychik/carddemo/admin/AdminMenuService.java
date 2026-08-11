package com.vsergeychik.carddemo.admin;

import com.vsergeychik.carddemo.admin.model.AdminMenuOptions;
import com.vsergeychik.carddemo.admin.model.AdminMenuOptions.AdminMenuOption;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.user.model.SecUserRecord;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The decision core of {@code app/cbl/COADM01C.cbl} - the CardDemo administrator menu, CICS
 * transaction {@code CA00}.
 *
 * <h2>Why this class exists</h2>
 * Every branch {@code COADM01C} contains lives here and nowhere else. {@code AdminMenuController}
 * carries <strong>no</strong> branch logic at all: it maps its request DTO onto {@link AdminMenuInput},
 * calls {@link #handle(AdminMenuInput)} once, and projects the returned {@link AdminMenuOutcome} onto
 * its response DTO. That split is not a style preference. The migration's coverage gate is a
 * <em>per-package</em> JaCoCo {@code BRANCH} ratio of 0.90, and a branch reachable only through
 * {@code MockMvc} is a branch that gate cannot see. Putting the decisions in a plain object that a
 * JUnit test constructs directly - no {@code MockMvc}, no {@code WebApplicationContext}, no HTTP, no
 * {@code JobLauncher} - is what makes the gate satisfiable, and it is what makes a failing parity case
 * point at one method rather than at a request pipeline.
 *
 * <p>Accordingly every method below takes its inputs as parameters and returns a value. None reaches
 * into a request object, none consults a clock, a random source or an environment variable, and none
 * touches a dataset. The class is deterministic: the same {@link AdminMenuInput} always produces the
 * same {@link AdminMenuOutcome}, byte for byte.
 *
 * <h2>This is a like-for-like migration</h2>
 * {@code COADM01C} is 268 lines and this class reproduces its observable behaviour exactly - the
 * evaluation order of its {@code EVALUATE}, the guard chain of its two consecutive {@code IF}
 * statements, the composition of every literal it emits, and the odd things it does. Where the source
 * does something surprising, this class does the same surprising thing and the comment beside it cites
 * the line. Nothing is corrected, simplified, tidied or extended.
 *
 * <p><strong>No user-specified rules govern this file.</strong> {@code review_rules} returns exactly
 * one line - "No user rules provided." - and that is the whole document. Their absence is not licence
 * to lower the bar, so the migration's own enterprise-practice substitutes bind instead, and the ones
 * that shape this file are named at the declarations they shape.
 *
 * <h2>What the source actually does, and what it does not</h2>
 * Four facts about {@code COADM01C} were verified by exhaustive search of the program and are the
 * reason several types a reader might expect are absent:
 * <ul>
 *   <li><strong>No arithmetic.</strong> Zero {@code ADD}, zero {@code SUBTRACT}, zero
 *       {@code COMPUTE}, zero {@code MULTIPLY}, zero {@code DIVIDE}, zero {@code COMP-3} and zero
 *       scaled {@code PIC S9(n)V(n)}. There is no monetary value anywhere in this program, so no
 *       decimal type is imported and no binary floating-point primitive appears below - neither would
 *       be permitted in this module in any case.</li>
 *   <li><strong>No file input or output.</strong> Searching the program for {@code READ},
 *       {@code STARTBR}, {@code READNEXT}, {@code WRITE}, {@code REWRITE} and {@code DELETE} returns
 *       nothing. Its only five {@code EXEC CICS} verbs are {@code RETURN}, {@code XCTL} twice,
 *       {@code SEND MAP} and {@code RECEIVE MAP}. <strong>This class therefore takes no repository
 *       of any kind</strong> - see {@link #secUserData()} for why that is worth stating explicitly
 *       rather than leaving to inference.</li>
 *   <li><strong>No abend site.</strong> No {@code CALL 'CEE3ABD'}, so no abend type is imported and
 *       no return code other than the CICS default is produced.</li>
 *   <li><strong>Nine copybooks, at lines 50 to 61</strong> - {@code COCOM01Y}, {@code COADM02Y},
 *       {@code COADM01}, {@code COTTL01Y}, {@code CSDAT01Y}, {@code CSMSG01Y}, {@code CSUSR01Y},
 *       {@code DFHAID} and {@code DFHBMSCA}. It does <em>not</em> copy {@code CSSTRPFY},
 *       {@code DFHATTR}, {@code CSMSG02Y} or {@code CSSETATY}, so no PF-key store, no extended
 *       attribute set and no field-highlight helper belongs in this file. The only DFH symbols the
 *       program uses are {@code DFHENTER}, {@code DFHPF3} and {@code DFHGREEN}.</li>
 * </ul>
 *
 * <h2>Where the layer boundary falls</h2>
 * {@code SEND-MENU-SCREEN} at lines 172 to 184 performs three things in order:
 * {@code POPULATE-HEADER-INFO}, {@code BUILD-MENU-OPTIONS}, then
 * {@code MOVE WS-MESSAGE TO ERRMSGO} and the {@code SEND MAP}. The second and third are this class's
 * work and the first is not, because {@code POPULATE-HEADER-INFO} reads
 * {@code FUNCTION CURRENT-DATE} at line 204 and a service that read a clock could not be asserted
 * deterministically. The division is therefore:
 * <ul>
 *   <li><strong>This class produces</strong> the twelve composed option lines, the eighty-byte
 *       {@code WS-MESSAGE} image, the {@code ERRMSGC} colour byte, the error flag, the echoed
 *       {@code OPTIONO} value, the {@code XCTL} target, whether a screen is painted, whether the
 *       output map was cleared first, and the outbound communication area.</li>
 *   <li><strong>The controller produces</strong> {@code TRNNAMEO}, {@code PGMNAMEO},
 *       {@code TITLE01O} and {@code TITLE02O} from the shared screen titles, {@code CURDATEO} and
 *       {@code CURTIMEO} from the shared date header driven by the injected clock, and the
 *       {@code PIC X(80)} to {@code PIC X(78)} right-truncation that places
 *       {@link AdminMenuOutcome#message()} into {@code ERRMSGO}, which is {@code PIC X(78)} at
 *       {@code app/cpy-bms/COADM01.CPY:260}. {@link AdminMenuOutcome#message()} is exposed at its
 *       full eighty characters precisely so that truncation is the controller's explicit, reviewable
 *       step rather than something this class did quietly.</li>
 * </ul>
 *
 * <h2>Statelessness</h2>
 * CICS is pseudo-conversational: {@code COADM01C} ends at lines 107 to 110 with
 * {@code EXEC CICS RETURN TRANSID('CA00') COMMAREA(CARDDEMO-COMMAREA)} and the next keystroke starts
 * the program again from the top with that area handed back to it. The Java projection keeps that
 * shape and keeps <strong>no server-side state whatsoever</strong>: no session, no scoped bean, no
 * cache, no static mutable field. The whole conversation - the communication area, the attention
 * identifier and the screen's own field values - arrives in {@link AdminMenuInput} and leaves in
 * {@link AdminMenuOutcome}, and the client re-supplies it on the next call.
 *
 * <p>The three instance fields this class does hold are immutable and none of them is COBOL
 * {@code WORKING-STORAGE} that the program mutates. {@code WS-ERR-FLG}, {@code WS-OPTION},
 * {@code WS-OPTION-X}, {@code WS-IDX}, {@code WS-MESSAGE} and {@code WS-ADMIN-OPT-TXT} are all
 * method-local variables or components of the returned outcome, because a singleton bean holding
 * per-request working storage in a field would break request isolation and test determinism alike.
 *
 * <h2>Naming: this package has no divergence to warn about</h2>
 * Elsewhere in this migration a prompt-mandated class name can contradict what its COBOL program
 * does, and every such divergence is flagged where it occurs. There is none here.
 * {@code COADM01C}'s own header reads {@code Function : Admin Menu for Admin users} and this is an
 * admin menu controller's service; its sibling {@code COMEN01C} is likewise the real main menu.
 * Contrast {@code transaction.TransactionMenuController}, whose {@code COTRN00C} actually
 * <em>lists</em> transactions, and {@code user.UserMenuController}, whose {@code COUSR00C} actually
 * <em>lists users</em>: those two are list screens wearing menu names. These two are the menus.
 *
 * <p>{@code COADM01C} and {@code COMEN01C} are about ninety-five percent identical, and they are
 * deliberately <strong>not</strong> given a shared base class. What differs between them is exactly
 * the byte-level detail parity depends on: the option name is commented out of the "coming soon"
 * message here and delimited by space there, the dispatch table has ten arms here and twelve there,
 * and the user-type authorisation filter exists only there. A shared superclass would have to
 * parameterise precisely the differences that must stay visible.
 *
 * <h2>Thread safety</h2>
 * Immutable after construction and safe to share across threads. The three fields are final and hold
 * an immutable codec, a {@link String} and an immutable record. Every method is either a pure function
 * of its parameters or a reader of those final fields; nothing is lazily initialised and no array or
 * mutable collection is ever handed out.
 *
 * @see AdminMenuOptions
 * @see NavigationContext
 */
@Service
public class AdminMenuService {

    // =================================================================================================
    // Section 1 - program and screen identity.
    //
    // Every literal below is transcribed from a source line that is cited beside it, and each is
    // declared once so that no method retypes a name the parity differ will compare.
    // =================================================================================================

    /** {@code WS-PGMNAME PIC X(08) VALUE 'COADM01C'} - {@code app/cbl/COADM01C.cbl:36}. */
    public static final String PROGRAM_NAME = "COADM01C";

    /**
     * {@code WS-TRANID PIC X(04) VALUE 'CA00'} - {@code app/cbl/COADM01C.cbl:37}.
     *
     * <p>The same four characters that {@code app/csd/CARDDEMO.CSD:327-328} maps to
     * {@code PROGRAM(COADM01C)}, that line 108 names on {@code EXEC CICS RETURN TRANSID}, and that
     * line 139 moves into {@code CDEMO-FROM-TRANID} before transferring to a menu option.
     */
    public static final String TRANSACTION_ID = "CA00";

    /** {@code MAPSET('COADM01')} - {@code app/cbl/COADM01C.cbl:181} and {@code :193}. */
    public static final String MAPSET_NAME = "COADM01";

    /** {@code MAP('COADM1A')} - {@code app/cbl/COADM01C.cbl:180} and {@code :192}. */
    public static final String MAP_NAME = "COADM1A";

    /**
     * {@code 'COSGN00C'} - the sign-on program, named at three separate sites.
     *
     * <p>Lines 83, 97 and 163, and the three are <strong>not</strong> interchangeable: line 83 moves
     * it into {@code CDEMO-FROM-PROGRAM}, line 97 into {@code CDEMO-TO-PROGRAM}, and line 163 into
     * {@code CDEMO-TO-PROGRAM} as a default. See {@link #handle(AdminMenuInput, AdminMenuOptionTable)}
     * and {@link #returnToSignonScreen}.
     */
    public static final String SIGNON_PROGRAM = "COSGN00C";

    // =================================================================================================
    // Section 2 - declared widths.
    //
    // Practice: every width is a named constant traced to a PICTURE clause or a DFHMDF LENGTH, so no
    // bare numeric literal appears at a use site where a reviewer could not check it.
    // =================================================================================================

    /**
     * {@code WS-MESSAGE PIC X(80) VALUE SPACES} - {@code app/cbl/COADM01C.cbl:38}.
     *
     * <p>Eighty, and deliberately not the fifty of {@code SystemMessages.MESSAGE_LENGTH}: that
     * constant is the width of {@code CCDA-MSG-INVALID-KEY PIC X(50)} in
     * {@code app/cpy/CSMSG01Y.cpy}. Line 101 moves the fifty-byte message into this eighty-byte field,
     * a widening {@code MOVE} that left-justifies and pads on the right, and neither width is the
     * seventy-eight of {@code ERRMSGO}. All three are real and distinct.
     */
    public static final int MESSAGE_LENGTH = 80;

    /** {@code WS-ADMIN-OPT-TXT PIC X(40) VALUE SPACES} - {@code app/cbl/COADM01C.cbl:48}. */
    public static final int OPTION_TEXT_LENGTH = 40;

    /** {@code WS-OPTION-X PIC X(02) JUST RIGHT} - {@code app/cbl/COADM01C.cbl:45}. */
    public static final int OPTION_X_LENGTH = 2;

    /** {@code WS-OPTION PIC 9(02) VALUE 0} - {@code app/cbl/COADM01C.cbl:46}. */
    public static final int OPTION_DIGITS = 2;

    /**
     * {@code OPTIONI PIC X(2)} and {@code OPTIONO PIC X(2)} - {@code app/cpy-bms/COADM01.CPY:132}
     * and {@code :254}, corroborated by {@code OPTION DFHMDF ... LENGTH=2} in
     * {@code app/bms/COADM01.bms:145-149}.
     *
     * <p>This is the value {@code LENGTH OF OPTIONI} yields at
     * {@code app/cbl/COADM01C.cbl:118}, which is where the trailing-space scan starts.
     */
    public static final int OPTION_LENGTH = 2;

    /**
     * The twelve {@code OPTN001O} through {@code OPTN012O} lines of the map, each
     * {@value #OPTION_TEXT_LENGTH} bytes - {@code app/cpy-bms/COADM01.CPY:182-248}.
     *
     * <p>All twelve exist on the screen. This program can populate at most
     * {@value #OPTION_DISPATCH_ARM_COUNT} of them and in practice populates four, and both facts are
     * preserved rather than collapsed - see {@link #buildMenuOptions(AdminMenuOptionTable)}.
     */
    public static final int OPTION_LINE_COUNT = 12;

    /**
     * The number of arms in the {@code EVALUATE WS-IDX} of {@code BUILD-MENU-OPTIONS}:
     * {@code WHEN 1} through {@code WHEN 10} at {@code app/cbl/COADM01C.cbl:239-258}, then
     * {@code WHEN OTHER CONTINUE} at {@code :259-260}.
     *
     * <p>Ten, not twelve. The map defines {@code OPTN011O} and {@code OPTN012O} and
     * {@code COMEN01C} does dispatch to twelve, but {@code COADM01C} has no arm for either, so this
     * program can never write lines 11 or 12. Arms 5 through 10 are themselves unreachable while the
     * active option count is 4, and they are preserved exactly as written: dead code in a
     * like-for-like migration is preserved, never cleaned up.
     */
    public static final int OPTION_DISPATCH_ARM_COUNT = 10;

    /** {@code WS-USRSEC-FILE PIC X(08)} - {@code app/cbl/COADM01C.cbl:39}. */
    public static final int USRSEC_FILE_NAME_LENGTH = 8;

    /**
     * The five bytes {@code app/cbl/COADM01C.cbl:138} compares:
     * {@code CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)(1:5)}.
     *
     * <p>A reference modification of the first five characters of an eight-character field, which is
     * why {@link AdminMenuOption#adminOptPgmName()} must be held untrimmed at its full declared width.
     */
    public static final int DUMMY_PREFIX_LENGTH = 5;

    // =================================================================================================
    // Section 3 - the literals this program emits, byte for byte.
    //
    // Each carries its verified character count, because the parity differ compares these field by
    // field and a single character of drift is a diff.
    // =================================================================================================

    /**
     * {@code 'DUMMY'} - the sentinel prefix tested at {@code app/cbl/COADM01C.cbl:138}.
     *
     * <p>No entry in {@code app/cpy/COADM02Y.cpy} begins with it, so the comparison is always true in
     * production and the {@code XCTL} always fires. The branch nevertheless exists in the source and so
     * exists here, unaltered, and is driven by supplying a stub option table - see
     * {@link AdminMenuOptionTable}. Deleting an unreachable branch would be a behaviour change.
     */
    public static final String DUMMY_PROGRAM_PREFIX = "DUMMY";

    /**
     * {@code 'Please enter a valid option number...'} - {@code app/cbl/COADM01C.cbl:131}, verified
     * <strong>37</strong> characters, three trailing full stops and no trailing space.
     *
     * <p>Moved into {@code WS-MESSAGE PIC X(80)}, so the emitted image is these 37 characters followed
     * by 43 spaces.
     */
    public static final String INVALID_OPTION_MESSAGE = "Please enter a valid option number...";

    /**
     * {@code 'This option '} - the first {@code STRING} operand at
     * {@code app/cbl/COADM01C.cbl:149}, verified <strong>12</strong> characters including its trailing
     * space.
     */
    public static final String COMING_SOON_PREFIX = "This option ";

    /**
     * {@code 'is coming soon ...'} - the second {@code STRING} operand at
     * {@code app/cbl/COADM01C.cbl:152}, verified <strong>18</strong> characters.
     *
     * <p>Second, not third. {@code app/cbl/COADM01C.cbl:150-151} are <strong>commented out</strong>:
     * <pre>
     *   L149          STRING 'This option '       DELIMITED BY SIZE
     *   L150   *             CDEMO-ADMIN-OPT-NAME(WS-OPTION)
     *   L151   *                                 DELIMITED BY SIZE
     *   L152                 'is coming soon ...'   DELIMITED BY SIZE
     *   L153            INTO WS-MESSAGE
     * </pre>
     * so the option name never reaches the message and the composed text is exactly
     * {@value #COMING_SOON_PREFIX}{@value #COMING_SOON_SUFFIX} - thirty characters, with no name and
     * no space between "option" and "is" beyond the prefix's own trailing one. Interpolating the name
     * would emit text the program never emits. The behaviour is reproduced, not repaired.
     */
    public static final String COMING_SOON_SUFFIX = "is coming soon ...";

    /**
     * The composed "coming soon" text: {@value #COMING_SOON_PREFIX}{@value #COMING_SOON_SUFFIX},
     * verified <strong>30</strong> characters.
     *
     * <p>Declared for assertion and documentation only. The value this class emits is built at runtime
     * through {@link FixedWidthCodec#concatenateDelimitedBySize(String...)} from the two operands
     * above, so the {@code STRING ... DELIMITED BY SIZE} statement is reproduced as a statement rather
     * than replaced by its answer.
     */
    public static final String COMING_SOON_MESSAGE = COMING_SOON_PREFIX + COMING_SOON_SUFFIX;

    /**
     * {@code '. '} - the second {@code STRING} operand of {@code BUILD-MENU-OPTIONS} at
     * {@code app/cbl/COADM01C.cbl:234}, two characters: a full stop and a space.
     */
    public static final String OPTION_NUMBER_SEPARATOR = ". ";

    /**
     * {@code 88 ERR-FLG-ON VALUE 'Y'} - {@code app/cbl/COADM01C.cbl:41}, and the literal moved by
     * lines 100 and 130.
     */
    public static final String ERR_FLG_ON = "Y";

    /**
     * {@code 88 ERR-FLG-OFF VALUE 'N'} - {@code app/cbl/COADM01C.cbl:42}, and the
     * {@code VALUE} clause of {@code WS-ERR-FLG} at line 40 that
     * {@code SET ERR-FLG-OFF TO TRUE} at line 77 restores.
     */
    public static final String ERR_FLG_OFF = "N";

    /**
     * {@code ZEROS} as {@code app/cbl/COADM01C.cbl:129} compares it: {@code WS-OPTION = ZEROS}.
     *
     * <p>{@code WS-OPTION} is {@code PIC 9(02)}, so the figurative constant compares as the numeric
     * value zero.
     */
    public static final int ZERO_OPTION = 0;

    /**
     * The colour the map declares for {@code ERRMSG}: {@code COLOR=RED} at
     * {@code app/bms/COADM01.bms:154-157}.
     *
     * <p>This is the colour in force unless the program overrides it, and the only override is
     * {@code MOVE DFHGREEN TO ERRMSGC} at {@code app/cbl/COADM01C.cbl:148}. So a
     * {@link AdminMenuOutcome#messageColour()} of this value means "the program did not touch
     * {@code ERRMSGC}", which is what {@link AdminMenuOutcome#messageColourOverridden()} reports
     * without a caller having to know which byte means what.
     */
    public static final byte MAP_MESSAGE_COLOUR = BmsAttributes.DFHRED;

    /**
     * {@code MOVE DFHGREEN TO ERRMSGC OF COADM1AO} - {@code app/cbl/COADM01C.cbl:148}, the program's
     * one and only attribute write.
     *
     * <p>It applies on exactly one path: the "coming soon" path, reached only when the
     * {@value #DUMMY_PROGRAM_PREFIX} test at line 138 suppressed the transfer. Green for an
     * informational message, overriding the map's red.
     */
    public static final byte COMING_SOON_MESSAGE_COLOUR = BmsAttributes.DFHGREEN;

    // =================================================================================================
    // Section 4 - configuration keys and the code page.
    // =================================================================================================

    /**
     * The {@code carddemo.datasets} key under which {@code application.yml} declares the security
     * user file, and the six characters {@code WS-USRSEC-FILE} holds before padding.
     *
     * <p>{@code app/cbl/COADM01C.cbl:39} declares {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '} -
     * the eight-byte <em>logical</em> file name with its two trailing spaces, not a dataset name. The
     * dataset name behind it lives only in configuration and never in Java. See
     * {@link #usrSecFileName()}.
     */
    public static final String USRSEC_DATASET_KEY = "USRSEC";

    /**
     * The name of the code page {@link #AdminMenuService(DatasetBindings)} builds its codec over:
     * {@value #DEFAULT_MESSAGE_CHARSET_NAME}.
     *
     * <p>Named explicitly and never taken from the platform default. Why a default is admissible here
     * at all is set out on that constructor.
     */
    public static final String DEFAULT_MESSAGE_CHARSET_NAME = "US-ASCII";

    /**
     * The {@link Charset} form of {@link #DEFAULT_MESSAGE_CHARSET_NAME}, resolved from that one
     * constant so the name is written exactly once. {@code US-ASCII} is one of the code pages every
     * Java runtime is required to support, so the lookup cannot fail.
     */
    private static final Charset DEFAULT_MESSAGE_CHARSET =
            Charset.forName(DEFAULT_MESSAGE_CHARSET_NAME);

    /** The character COBOL pads an alphanumeric field with, and the byte the scan at line 119 tests. */
    private static final char SPACE = ' ';

    /** The character {@code INSPECT ... REPLACING ALL ' ' BY '0'} substitutes at line 123. */
    private static final char ZERO_DIGIT = '0';

    /** The upper bound of the digit range an unsigned {@code PIC 9} {@code DISPLAY} field may hold. */
    private static final char NINE_DIGIT = '9';

    /** The byte {@code LOW-VALUES} fills, tested by the combined relation at line 162. */
    private static final char LOW_VALUE = '\u0000';

    // =================================================================================================
    // Section 5 - instance state: three immutable fields, and why each one is here.
    //
    // Nothing below is mutable and nothing below is COBOL working storage that the program changes.
    // The two dead declarations are held as instance members because the source declares them as
    // storage and a like-for-like migration preserves a declaration even when nothing reads it.
    // =================================================================================================

    /**
     * The fixed-width character codec: this class's single seam for every pad, truncate and
     * {@code STRING} composition, so the direction of each is a deliberate and reviewable choice
     * rather than an accident of a Java assignment.
     *
     * <p>Exactly four of its operations are used - {@link FixedWidthCodec#movePicX(String, int)},
     * {@link FixedWidthCodec#padToDeclaredWidth(String, int)},
     * {@link FixedWidthCodec#concatenateDelimitedBySize(String...)} and
     * {@link FixedWidthCodec#decodePic9AsInt(String)} - and every one of them is a pure function of
     * characters that never consults the code page.
     */
    private final FixedWidthCodec codec;

    /**
     * {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '} - {@code app/cbl/COADM01C.cbl:39}, declared
     * and then never referenced again anywhere in the program.
     *
     * @see #usrSecFileName()
     */
    private final String usrSecFileName;

    /**
     * {@code COPY CSUSR01Y.} - {@code app/cbl/COADM01C.cbl:58}, which brings
     * {@code 01 SEC-USER-DATA} into working storage. Not one of its six fields is read or written by
     * this program.
     *
     * @see #secUserData()
     */
    private final SecUserRecord secUserData;

    /**
     * Creates the service over the module's dataset binding catalogue, and is the constructor the
     * Spring container selects.
     *
     * <p>It carries {@link Autowired} because the class declares two constructors and the container
     * would otherwise have to guess. The catalogue is required for one reason only: to resolve the
     * dead {@code WS-USRSEC-FILE} declaration from configuration instead of from a literal, so that no
     * dataset name is written in Java anywhere in this file.
     *
     * <p>The codec is built over {@value #DEFAULT_MESSAGE_CHARSET_NAME}, and that choice changes
     * nothing this class produces. All four codec operations used here are pure character functions -
     * they pad, truncate, concatenate and parse digits - and not one of them reads the code page; the
     * code-page-sensitive half of the codec, which converts between characters and stored bytes, is
     * never called, because {@code COADM01C} performs no input or output and its screen fields are
     * character data that travels in a payload. That is the difference between this service and, say,
     * the date-validation subprogram, whose whole output is an eighty-<em>byte</em> area and which must
     * therefore be handed the deployment's dataset code page. Where a caller nonetheless wants one
     * codec instance shared across a call path, {@link #AdminMenuService(DatasetBindings,
     * FixedWidthCodec)} takes it explicitly - so the choice is never hidden, merely defaulted.
     *
     * @param datasetBindings the {@code carddemo.datasets} catalogue; must declare
     *                        {@value #USRSEC_DATASET_KEY}
     * @throws NullPointerException  if {@code datasetBindings} is {@code null}
     * @throws IllegalStateException if {@value #USRSEC_DATASET_KEY} is not configured, or is
     *                               configured with a record width other than the eighty bytes
     *                               {@code app/cpy/CSUSR01Y.cpy} declares
     */
    @Autowired
    public AdminMenuService(DatasetBindings datasetBindings) {
        this(datasetBindings, new FixedWidthCodec(DEFAULT_MESSAGE_CHARSET));
    }

    /**
     * Creates the service with the codec stated explicitly.
     *
     * <p>This is the constructor a unit test uses, and the one a caller uses to share a single codec
     * instance across a request. Both constructors behave identically for every value this class
     * produces, for the reason set out on {@link #AdminMenuService(DatasetBindings)}.
     *
     * @param datasetBindings the {@code carddemo.datasets} catalogue; must declare
     *                        {@value #USRSEC_DATASET_KEY}
     * @param codec           the fixed-width character codec this service composes every image with
     * @throws NullPointerException  if either argument is {@code null}
     * @throws IllegalStateException if {@value #USRSEC_DATASET_KEY} is not configured, or is
     *                               configured with a record width other than the eighty bytes
     *                               {@code app/cpy/CSUSR01Y.cpy} declares
     */
    public AdminMenuService(DatasetBindings datasetBindings, FixedWidthCodec codec) {
        Objects.requireNonNull(datasetBindings, "The carddemo.datasets binding catalogue is required: "
                + "app/cbl/COADM01C.cbl:39 declares WS-USRSEC-FILE, and the name behind it is resolved "
                + "from configuration because dataset names are never written in Java");
        this.codec = Objects.requireNonNull(codec, "A fixed-width codec is required: every image this "
                + "service composes is padded, truncated or concatenated through it so that the "
                + "direction of each operation is explicit at the call site");

        // app/cbl/COADM01C.cbl:39 - WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '. Resolving the key
        // through the catalogue proves the entry exists and agrees with the copybook this program
        // copies at line 58, following the same startup-validation convention the repositories use.
        // The eight-byte value itself is the LOGICAL file name padded to its declared width. The
        // configured dataset name is deliberately never read, so no mainframe dataset name is written
        // anywhere in this file.
        DatasetBinding usrSecBinding = datasetBindings.binding(USRSEC_DATASET_KEY);
        if (usrSecBinding.recordLength() != SecUserRecord.RECORD_LENGTH) {
            throw new IllegalStateException("Dataset binding '" + USRSEC_DATASET_KEY + "' declares a "
                    + "record length of " + usrSecBinding.recordLength() + ", but SEC-USER-DATA is "
                    + SecUserRecord.RECORD_LENGTH + " bytes - app/cpy/CSUSR01Y.cpy declares "
                    + "SEC-USR-ID X(08) plus SEC-USR-FNAME X(20), SEC-USR-LNAME X(20), SEC-USR-PWD "
                    + "X(08), SEC-USR-TYPE X(01) and SEC-USR-FILLER X(23). app/cbl/COADM01C.cbl:58 "
                    + "copies that record, so a differently sized one would mean the two disagree "
                    + "about the same file. Correct carddemo.datasets." + USRSEC_DATASET_KEY
                    + ".record-length to " + SecUserRecord.RECORD_LENGTH + ".");
        }
        this.usrSecFileName = this.codec.movePicX(USRSEC_DATASET_KEY, USRSEC_FILE_NAME_LENGTH);
        this.secUserData = SecUserRecord.blank();
    }

    // =================================================================================================
    // Section 6 - the carriers.
    //
    // The service takes and returns its own immutable value types rather than the controller's request
    // and response DTOs, so no HTTP or serialisation concern reaches the decisions and a JUnit test can
    // drive every branch by constructing a plain record. The controller owns the mapping in both
    // directions.
    // =================================================================================================

    /**
     * Everything {@code COADM01C} learns about one invocation: the communication area, the attention
     * identifier and the one screen field it reads.
     *
     * <p>Those three are exactly what the program consults. It reads {@code EIBCALEN} through the
     * presence or absence of {@code DFHCOMMAREA} (lines 67 to 69 and 82), {@code EIBAID} at line 93 -
     * its only site - and {@code OPTIONI} at lines 118 to 122. It reads no other map field, no clock
     * and no dataset.
     *
     * @param navigationContext {@code CARDDEMO-COMMAREA} as received, or {@code null} when no
     *                          communication area accompanied the request. {@code null} <em>is</em>
     *                          the representation of {@code EIBCALEN = 0}: a separate boolean flag
     *                          would be a second source of truth able to contradict the reference
     *                          beside it, whereas absence cannot contradict itself
     * @param eibAid            the raw attention-identifier byte, one of the {@link CicsAid}
     *                          constants. Held as the byte the terminal sent rather than as a decoded
     *                          key, because line 93 compares the byte
     * @param option            the {@code OPTIONI} field as received. Moved into its declared
     *                          {@value #OPTION_LENGTH}-character width before use, exactly as CICS
     *                          delivers it into the symbolic map
     */
    public record AdminMenuInput(NavigationContext navigationContext, byte eibAid, String option) {

        /**
         * Requires the one component that is never absent.
         *
         * @throws NullPointerException if {@code option} is {@code null}; a {@code PIC X(2)} screen
         *                              field always holds two bytes, so pass spaces to blank it
         */
        public AdminMenuInput {
            Objects.requireNonNull(option, "OPTIONI is PIC X(2) (app/cpy-bms/COADM01.CPY:132) and a "
                    + "COBOL field is never null; pass spaces for an empty screen field. Only the "
                    + "communication area may be absent, and its absence models EIBCALEN = 0");
        }

        /**
         * An invocation with no communication area - the Java form of {@code EIBCALEN = 0} at
         * {@code app/cbl/COADM01C.cbl:82}, which is the transaction being typed at a clear screen
         * rather than transferred to.
         *
         * @param eibAid the attention identifier; immaterial on this path, because the program
         *               diverts to the sign-on screen before it ever reaches line 93
         * @param option the {@code OPTIONI} field; likewise never inspected on this path
         * @return the input, never {@code null}
         */
        public static AdminMenuInput withoutCommarea(byte eibAid, String option) {
            return new AdminMenuInput(null, eibAid, option);
        }

        /**
         * Whether a communication area accompanied this invocation - the negation of
         * {@code IF EIBCALEN = 0}.
         *
         * @return {@code true} when {@link #navigationContext()} is present, which is the
         *         {@code ELSE} branch at {@code app/cbl/COADM01C.cbl:85}
         */
        public boolean isCommareaPresent() {
            return navigationContext != null;
        }

        /**
         * Whether {@code 88 CDEMO-PGM-REENTER VALUE 1} holds - {@code app/cpy/COCOM01Y.cpy:31}.
         *
         * <p>Deliberately <strong>not</strong> the negation of an "is enter" test.
         * {@code CDEMO-PGM-CONTEXT} is {@code PIC 9(01)} and can hold any digit, so a context of, say,
         * 9 satisfies neither condition name - and {@code app/cbl/COADM01C.cbl:87} tests
         * {@code IF NOT CDEMO-PGM-REENTER}, which such a context <em>would</em> satisfy. Treating the
         * two conditions as complementary would invent a branch the source does not have.
         *
         * @return {@code true} only when a communication area is present and its program context is
         *         the re-enter value
         */
        public boolean isReenter() {
            return navigationContext != null && navigationContext.isReenter();
        }
    }

    /**
     * The outcome of {@code RECEIVE-MENU-SCREEN} at {@code app/cbl/COADM01C.cbl:189-197}, including
     * the two condition codes the source captures and never looks at.
     *
     * <p>The statement is
     * {@code EXEC CICS RECEIVE MAP('COADM1A') MAPSET('COADM01') INTO(COADM1AI) RESP(WS-RESP-CD)
     * RESP2(WS-REAS-CD)}, and searching the program shows neither {@code WS-RESP-CD} nor
     * {@code WS-REAS-CD} is ever tested afterwards. They are therefore carried here and
     * <strong>no branch depends on them</strong>. Inventing error handling for a receive the source
     * does not check would be adding behaviour.
     *
     * <p>This is also why the migration's file-status gate has <strong>zero call sites in this
     * package</strong>: a {@code FileStatus} outcome is asserted per repository call, and this program
     * makes none. The absence is a property of {@code COADM01C}, not an omission here.
     *
     * @param performed   whether the paragraph ran at all. It runs on exactly one path - the
     *                    re-entry path at {@code app/cbl/COADM01C.cbl:92} - and not on the
     *                    absent-communication-area or first-entry paths
     * @param respCode    {@code WS-RESP-CD PIC S9(09) COMP} at {@code app/cbl/COADM01C.cbl:43},
     *                    captured and untested
     * @param reasonCode  {@code WS-REAS-CD PIC S9(09) COMP} at {@code app/cbl/COADM01C.cbl:44},
     *                    captured and untested
     */
    public record ReceiveOutcome(boolean performed, int respCode, int reasonCode) {

        /**
         * The {@code DFHRESP(NORMAL)} condition code, zero - the value CICS sets when a
         * {@code RECEIVE MAP} succeeds.
         */
        public static final int RESP_NORMAL = 0;

        /** The {@code RESP2} value accompanying a normal response, zero. */
        public static final int RESP2_NONE = 0;

        /**
         * The state on every path that never reaches {@code RECEIVE-MENU-SCREEN}: not performed, with
         * both codes at the {@code VALUE ZEROS} their declarations give them at
         * {@code app/cbl/COADM01C.cbl:43-44}.
         */
        public static final ReceiveOutcome NOT_PERFORMED =
                new ReceiveOutcome(false, RESP_NORMAL, RESP2_NONE);

        /** A completed receive reporting {@code DFHRESP(NORMAL)}. */
        public static final ReceiveOutcome NORMAL =
                new ReceiveOutcome(true, RESP_NORMAL, RESP2_NONE);
    }

    /**
     * The administrator option table as one invocation sees it: the {@code OCCURS} slots and the
     * active count, held as two separate facts.
     *
     * <p>{@code app/cpy/COADM02Y.cpy} declares {@code CDEMO-ADMIN-OPT OCCURS 9 TIMES} but
     * {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4}, and the two numbers do different work:
     * {@code app/cbl/COADM01C.cbl:128} compares the entered option against the <em>count</em> and
     * {@code :143} subscripts the <em>table</em>. Conflating them - trimming the table to four, or
     * looping to nine - is the defect this type exists to prevent.
     *
     * <p>It is a parameter rather than a hard-wired reference to the copybook for one reason: the
     * {@value #DUMMY_PROGRAM_PREFIX} branch at line 138 is unreachable with the real table, and a
     * branch that cannot be driven cannot be shown to behave. {@link #copybook()} supplies the real
     * table for production; a stub supplies whatever a test needs to reach that branch.
     *
     * @param slots       the {@code OCCURS} slots in COBOL declaration order, indexed from 0 in the
     *                    Java sense, an absent slot being one the copybook gives no {@code VALUE}
     * @param activeCount {@code CDEMO-ADMIN-OPT-COUNT} - how many leading slots the menu offers
     */
    public record AdminMenuOptionTable(List<Optional<AdminMenuOption>> slots, int activeCount) {

        /**
         * Copies the slot list defensively and checks the two invariants a menu table must satisfy for
         * the program's subscripting to be meaningful.
         *
         * @throws NullPointerException     if {@code slots} is {@code null} or contains {@code null}
         * @throws IllegalArgumentException if {@code activeCount} is negative or exceeds the number of
         *                                  slots, or if any of the first {@code activeCount} slots is
         *                                  absent
         */
        public AdminMenuOptionTable {
            Objects.requireNonNull(slots, "An OCCURS slot list is required; COADM02Y.cpy declares "
                    + AdminMenuOptions.TABLE_SIZE + " slots");
            slots = List.copyOf(slots);
            if (activeCount < 0 || activeCount > slots.size()) {
                throw new IllegalArgumentException("CDEMO-ADMIN-OPT-COUNT is " + activeCount
                        + ", which does not address a table of " + slots.size() + " slot(s). "
                        + "app/cbl/COADM01C.cbl:128 compares the entered option against the count and "
                        + ":143 then subscripts the table with it, so a count beyond the table would "
                        + "admit an option the table cannot resolve.");
            }
            for (int subscript = 1; subscript <= activeCount; subscript++) {
                if (slots.get(subscript - 1).isEmpty()) {
                    throw new IllegalArgumentException("Slot " + subscript + " of "
                            + slots.size() + " is absent, yet CDEMO-ADMIN-OPT-COUNT is " + activeCount
                            + " and so offers it. app/cbl/COADM01C.cbl:143 would subscript an "
                            + "unvalued entry: every slot up to the active count must carry a value.");
                }
            }
        }

        /**
         * The real table of {@code app/cpy/COADM02Y.cpy}: {@value AdminMenuOptions#TABLE_SIZE} slots of
         * which the first {@value AdminMenuOptions#ACTIVE_OPTION_COUNT} carry values, with the active
         * count the copybook declares.
         *
         * @return the copybook table, never {@code null}
         */
        public static AdminMenuOptionTable copybook() {
            return new AdminMenuOptionTable(AdminMenuOptions.options(),
                    AdminMenuOptions.ACTIVE_OPTION_COUNT);
        }

        /**
         * The entry a 1-based COBOL subscript addresses - the Java form of
         * {@code CDEMO-ADMIN-OPT(cobolSubscript)}.
         *
         * <p>The conversion from the 1-based COBOL subscript to the 0-based Java index is written
         * once, here, and never inline at a call site: an off-by-one on an {@code OCCURS} table is the
         * single largest defect risk in this migration.
         *
         * @param cobolSubscript the COBOL subscript, from 1 to the number of slots inclusive
         * @return the addressed entry, or empty for a slot carrying no value
         * @throws IndexOutOfBoundsException if {@code cobolSubscript} is below 1 or above
         *                                   {@link #slots()}{@code .size()}
         */
        public Optional<AdminMenuOption> optionBySubscript(int cobolSubscript) {
            if (cobolSubscript < 1 || cobolSubscript > slots.size()) {
                throw new IndexOutOfBoundsException("COBOL subscript " + cobolSubscript
                        + " is outside 1.." + slots.size() + "; COADM02Y.cpy declares "
                        + "CDEMO-ADMIN-OPT OCCURS " + slots.size() + " TIMES and COBOL has no "
                        + "subscript 0");
            }
            return slots.get(cobolSubscript - 1);
        }
    }

    /**
     * The four-step normalisation of {@code app/cbl/COADM01C.cbl:117-125}, with every intermediate
     * preserved so each step can be asserted on its own.
     *
     * <p>Exposing the intermediates is the point. The third step is a receiver <em>attribute</em>
     * declared thirty-seven lines away from the statement it governs, and a reader who checks only the
     * final integer cannot tell a correct implementation from one that silently left-justified.
     *
     * @param wsIdx            {@code WS-IDX} after the descending scan at lines 117 to 121: 2 when the
     *                         second byte of {@code OPTIONI} is not a space, otherwise 1
     * @param receivedOptionI  {@code OPTIONI} at its declared {@value #OPTION_LENGTH} characters - the
     *                         image the scan ran over
     * @param justifiedOptionX {@code WS-OPTION-X} immediately after line 122's
     *                         {@code JUSTIFIED RIGHT} move, before the {@code INSPECT}. This is where
     *                         {@code "3"} has become {@code " 3"}
     * @param optionX          {@code WS-OPTION-X} after line 123's
     *                         {@code INSPECT ... REPLACING ALL ' ' BY '0'}. This is also
     *                         {@code WS-OPTION}'s image after line 124, because that {@code MOVE}
     *                         crosses two fields of equal width
     * @param option           the value {@link #optionX()} denotes, or empty when it does not hold
     *                         digits - which is the state line 127's {@code IS NOT NUMERIC} exists to
     *                         detect
     */
    public record OptionNormalisation(int wsIdx,
                                      String receivedOptionI,
                                      String justifiedOptionX,
                                      String optionX,
                                      OptionalInt option) {

        /**
         * Requires every component and checks the two widths the copybook fixes.
         *
         * @throws NullPointerException     if any reference component is {@code null}
         * @throws IllegalArgumentException if either image is not at its declared width
         */
        public OptionNormalisation {
            Objects.requireNonNull(receivedOptionI, "The received OPTIONI image is required");
            Objects.requireNonNull(justifiedOptionX, "The post-JUST-RIGHT WS-OPTION-X image is "
                    + "required");
            Objects.requireNonNull(optionX, "The post-INSPECT WS-OPTION-X image is required");
            Objects.requireNonNull(option, "An OptionalInt is required; use OptionalInt.empty() for a "
                    + "field that does not hold digits rather than a sentinel integer");
            if (receivedOptionI.length() != OPTION_LENGTH) {
                throw new IllegalArgumentException("OPTIONI is PIC X(" + OPTION_LENGTH + ") but the "
                        + "image is " + receivedOptionI.length() + " character(s) wide");
            }
            // optionX is simultaneously WS-OPTION-X's image after the INSPECT and WS-OPTION's image
            // after line 124, which is why it is checked against the numeric receiver's width: the two
            // fields are PIC X(02) and PIC 9(02), and it is that equality of widths which lets a
            // non-digit character survive the MOVE for line 127 to detect.
            if (justifiedOptionX.length() != OPTION_X_LENGTH || optionX.length() != OPTION_DIGITS) {
                throw new IllegalArgumentException("WS-OPTION-X is PIC X(0" + OPTION_X_LENGTH
                        + ") JUST RIGHT (app/cbl/COADM01C.cbl:45) and WS-OPTION is PIC 9(0"
                        + OPTION_DIGITS + ") (app/cbl/COADM01C.cbl:46), but an image is "
                        + justifiedOptionX.length() + "/" + optionX.length() + " character(s) wide");
            }
        }

        /**
         * Whether {@code WS-OPTION} holds digits - the negation of line 127's
         * {@code WS-OPTION IS NOT NUMERIC}.
         *
         * @return {@code true} when {@link #option()} is present
         */
        public boolean isNumeric() {
            return option.isPresent();
        }

        /**
         * The value line 125 moves into {@code OPTIONO OF COADM1AO}, echoing the normalised option
         * back to the screen.
         *
         * <p>Identical to {@link #optionX()}: {@code MOVE WS-OPTION TO OPTIONO} sends
         * {@code PIC 9(02)} to {@code PIC X(2)}, and at equal widths the two bytes cross unchanged. It
         * is named separately because it is a different field of the record, and a reader tracing the
         * echo should not have to know they coincide.
         *
         * @return exactly {@value #OPTION_LENGTH} characters
         */
        public String optionEcho() {
            return optionX;
        }
    }

    /**
     * Everything one invocation of {@code COADM01C} produces.
     *
     * <p>Read it as the state of the program at the moment it leaves: either at
     * {@code EXEC CICS RETURN} (lines 107 to 110) having painted a screen, or at one of the two
     * {@code EXEC CICS XCTL} statements having named a successor. {@link #hasNextProgram()}
     * distinguishes the two, and exactly one of {@link #screenPainted()} and
     * {@link #hasNextProgram()} is ever true.
     *
     * @param optionLines                the twelve {@code OPTN001O} to {@code OPTN012O} lines in map
     *                                   order, index 0 being {@code OPTN001O}, each
     *                                   {@value #OPTION_TEXT_LENGTH} characters. All twelve are always
     *                                   present; those the program does not write are spaces
     * @param message                    {@code WS-MESSAGE}, exactly {@value #MESSAGE_LENGTH}
     *                                   characters. The controller truncates it on the right to the
     *                                   seventy-eight of {@code ERRMSGO}
     * @param messageColour              {@code ERRMSGC OF COADM1AO}: {@link #MAP_MESSAGE_COLOUR} unless
     *                                   line 148 overrode it with {@link #COMING_SOON_MESSAGE_COLOUR}
     * @param errorFlag                  whether {@code 88 ERR-FLG-ON} holds at the moment the program
     *                                   leaves
     * @param option                     {@code OPTIONO OF COADM1AO}, {@value #OPTION_LENGTH}
     *                                   characters
     * @param nextProgram                the {@code EXEC CICS XCTL PROGRAM(...)} target at its declared
     *                                   {@value NavigationContext#TO_PROGRAM_LENGTH} characters, or
     *                                   spaces when the program returned to CICS instead of
     *                                   transferring
     * @param nextProgramCarriesCommarea whether that transfer passed the communication area.
     *                                   <strong>The two transfers differ</strong>: lines 142 to 145
     *                                   specify {@code COMMAREA(CARDDEMO-COMMAREA)} and lines 165 to
     *                                   167 specify none
     * @param screenPainted              whether {@code SEND-MENU-SCREEN} ran, that is whether
     *                                   {@code EXEC CICS SEND MAP} at lines 179 to 184 executed
     * @param resetAllOutputFields       whether {@code MOVE LOW-VALUES TO COADM1AO} at line 89 ran
     *                                   first, clearing every output field before the paint. True on
     *                                   the first-entry path only
     * @param navigationContext          {@code CARDDEMO-COMMAREA} as it stands when the program
     *                                   leaves - the area line 109 hands back so the client can
     *                                   re-supply it
     * @param transactionId              {@code TRANSID} on line 108, {@value #TRANSACTION_ID}
     * @param mapsetName                 {@code MAPSET('COADM01')}
     * @param mapName                    {@code MAP('COADM1A')}
     * @param receive                    the outcome of {@code RECEIVE-MENU-SCREEN}, including the two
     *                                   condition codes the source never tests
     */
    public record AdminMenuOutcome(List<String> optionLines,
                                   String message,
                                   byte messageColour,
                                   boolean errorFlag,
                                   String option,
                                   String nextProgram,
                                   boolean nextProgramCarriesCommarea,
                                   boolean screenPainted,
                                   boolean resetAllOutputFields,
                                   NavigationContext navigationContext,
                                   String transactionId,
                                   String mapsetName,
                                   String mapName,
                                   ReceiveOutcome receive) {

        /**
         * Copies the line list defensively and checks every declared width, so a composition error is
         * caught where the outcome is built rather than where a byte later reads back wrong.
         *
         * @throws NullPointerException     if any reference component is {@code null}, or
         *                                  {@code optionLines} contains {@code null}
         * @throws IllegalArgumentException if the line count or any width departs from the copybook
         */
        public AdminMenuOutcome {
            Objects.requireNonNull(optionLines, "The twelve OPTN00nO lines are required");
            optionLines = List.copyOf(optionLines);
            Objects.requireNonNull(message, "WS-MESSAGE is PIC X(" + MESSAGE_LENGTH + ") and is never "
                    + "null; pass spaces for a blank message");
            Objects.requireNonNull(option, "OPTIONO is PIC X(" + OPTION_LENGTH + ") and is never null");
            Objects.requireNonNull(nextProgram, "The XCTL target is PIC X("
                    + NavigationContext.TO_PROGRAM_LENGTH + ") and is never null; pass spaces when the "
                    + "program returned to CICS rather than transferring");
            Objects.requireNonNull(navigationContext, "CARDDEMO-COMMAREA is required: line 109 hands "
                    + "it back on every return");
            Objects.requireNonNull(transactionId, "The RETURN TRANSID is required");
            Objects.requireNonNull(mapsetName, "The mapset name is required");
            Objects.requireNonNull(mapName, "The map name is required");
            Objects.requireNonNull(receive, "A receive outcome is required; use "
                    + "ReceiveOutcome.NOT_PERFORMED on a path that never receives the map");
            if (optionLines.size() != OPTION_LINE_COUNT) {
                throw new IllegalArgumentException("The map declares " + OPTION_LINE_COUNT
                        + " option lines, OPTN001O to OPTN012O (app/cpy-bms/COADM01.CPY:182-248), but "
                        + optionLines.size() + " were supplied. All twelve are always carried; the ones "
                        + "this program cannot write are spaces.");
            }
            // List.copyOf above already refuses a null element, so each line is known to be present:
            // an unwritten line is spaces, never null.
            for (int index = 0; index < optionLines.size(); index++) {
                String line = optionLines.get(index);
                if (line.length() != OPTION_TEXT_LENGTH) {
                    throw new IllegalArgumentException("Option line " + (index + 1) + " is "
                            + line.length() + " character(s) wide but OPTN00nO is PIC X("
                            + OPTION_TEXT_LENGTH + ")");
                }
            }
            if (message.length() != MESSAGE_LENGTH) {
                throw new IllegalArgumentException("WS-MESSAGE is PIC X(" + MESSAGE_LENGTH
                        + ") (app/cbl/COADM01C.cbl:38) but the image is " + message.length()
                        + " character(s) wide");
            }
            if (option.length() != OPTION_LENGTH) {
                throw new IllegalArgumentException("OPTIONO is PIC X(" + OPTION_LENGTH
                        + ") but the image is " + option.length() + " character(s) wide");
            }
            if (nextProgram.length() != NavigationContext.TO_PROGRAM_LENGTH) {
                throw new IllegalArgumentException("An XCTL target is PIC X("
                        + NavigationContext.TO_PROGRAM_LENGTH + ") but the image is "
                        + nextProgram.length() + " character(s) wide");
            }
        }

        /**
         * Whether the program left through an {@code EXEC CICS XCTL} rather than through
         * {@code EXEC CICS RETURN}.
         *
         * @return {@code true} when {@link #nextProgram()} names a program rather than holding spaces
         */
        public boolean hasNextProgram() {
            return !nextProgram.isBlank();
        }

        /**
         * Whether {@code ERRMSGC} was overridden from the map's declared {@code COLOR=RED}.
         *
         * @return {@code true} only when {@link #messageColour()} is
         *         {@link #COMING_SOON_MESSAGE_COLOUR}, which line 148 is the sole source of
         */
        public boolean messageColourOverridden() {
            return messageColour == COMING_SOON_MESSAGE_COLOUR;
        }

        /**
         * The one-character image of {@code WS-ERR-FLG} as the source stores it.
         *
         * @return {@link #ERR_FLG_ON} or {@link #ERR_FLG_OFF}
         */
        public String errFlgImage() {
            return errorFlag ? ERR_FLG_ON : ERR_FLG_OFF;
        }

        /**
         * One menu line by its 1-based COBOL subscript, the way {@code EVALUATE WS-IDX} at
         * {@code app/cbl/COADM01C.cbl:238-261} addresses them: subscript 1 is {@code OPTN001O}.
         *
         * @param cobolSubscript from 1 to {@value #OPTION_LINE_COUNT} inclusive
         * @return the line, exactly {@value #OPTION_TEXT_LENGTH} characters
         * @throws IndexOutOfBoundsException if {@code cobolSubscript} is below 1 or above
         *                                   {@value #OPTION_LINE_COUNT}
         */
        public String optionLine(int cobolSubscript) {
            if (cobolSubscript < 1 || cobolSubscript > OPTION_LINE_COUNT) {
                throw new IndexOutOfBoundsException("COBOL subscript " + cobolSubscript
                        + " is outside 1.." + OPTION_LINE_COUNT + "; the map declares OPTN001O to "
                        + "OPTN012O and COBOL has no subscript 0");
            }
            return optionLines.get(cobolSubscript - 1);
        }
    }

    // =================================================================================================
    // Section 7 - MAIN-PARA, app/cbl/COADM01C.cbl:75-110.
    // =================================================================================================

    /**
     * Runs {@code COADM01C} against the copybook option table - the entry point for production.
     *
     * @param input the invocation
     * @return the outcome
     * @throws NullPointerException if {@code input} is {@code null}
     */
    public AdminMenuOutcome handle(AdminMenuInput input) {
        return handle(input, AdminMenuOptionTable.copybook());
    }

    /**
     * Runs {@code COADM01C} against a stated option table, reproducing {@code MAIN-PARA} at
     * {@code app/cbl/COADM01C.cbl:75-110} statement for statement and in source order.
     *
     * <p>The COBOL, with the line numbers this method's comments cite:
     * <pre>
     *   L77   SET ERR-FLG-OFF TO TRUE
     *   L79   MOVE SPACES TO WS-MESSAGE
     *   L80                 ERRMSGO OF COADM1AO
     *   L82   IF EIBCALEN = 0
     *   L83       MOVE 'COSGN00C' TO CDEMO-FROM-PROGRAM
     *   L84       PERFORM RETURN-TO-SIGNON-SCREEN
     *   L85   ELSE
     *   L86       MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA
     *   L87       IF NOT CDEMO-PGM-REENTER
     *   L88           SET CDEMO-PGM-REENTER TO TRUE
     *   L89           MOVE LOW-VALUES       TO COADM1AO
     *   L90           PERFORM SEND-MENU-SCREEN
     *   L91       ELSE
     *   L92           PERFORM RECEIVE-MENU-SCREEN
     *   L93           EVALUATE EIBAID
     *   L94               WHEN DFHENTER  PERFORM PROCESS-ENTER-KEY
     *   L96               WHEN DFHPF3    MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
     *   L98                              PERFORM RETURN-TO-SIGNON-SCREEN
     *   L99               WHEN OTHER     MOVE 'Y' TO WS-ERR-FLG
     *   L101                             MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE
     *   L102                             PERFORM SEND-MENU-SCREEN
     *   L107  EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)
     * </pre>
     *
     * <p>Two details of that listing are easy to read past and both are reproduced deliberately:
     * <ul>
     *   <li><strong>Line 83 sets {@code CDEMO-FROM-PROGRAM}; line 97 sets
     *       {@code CDEMO-TO-PROGRAM}.</strong> Both move the same literal and neither is a typo for
     *       the other. The asymmetry survives here unchanged.</li>
     *   <li><strong>Line 87 tests {@code IF NOT CDEMO-PGM-REENTER}, which is not "is enter".</strong>
     *       {@code CDEMO-PGM-CONTEXT} is {@code PIC 9(01)}, so a context of 0 <em>or</em> of 2 through
     *       9 takes the first-entry path. See {@link AdminMenuInput#isReenter()}.</li>
     * </ul>
     *
     * <p>On the first-entry path <strong>no option is read and no validation runs</strong>: the program
     * paints the screen and returns.
     *
     * @param input       the invocation
     * @param optionTable the administrator option table to offer and subscript
     * @return the outcome
     * @throws NullPointerException if either argument is {@code null}
     */
    public AdminMenuOutcome handle(AdminMenuInput input, AdminMenuOptionTable optionTable) {
        Objects.requireNonNull(input, "An invocation is required");
        Objects.requireNonNull(optionTable, "An option table is required; "
                + "AdminMenuOptionTable.copybook() supplies the one app/cpy/COADM02Y.cpy declares");

        // L77 SET ERR-FLG-OFF TO TRUE. WS-ERR-FLG is method-local, never a field: a singleton bean
        // holding per-request working storage would break request isolation.
        boolean errorFlag = false;

        // L79-L80 MOVE SPACES TO WS-MESSAGE and to ERRMSGO OF COADM1AO. Both receivers start blank.
        String wsMessage = spaces(MESSAGE_LENGTH);

        // L82 IF EIBCALEN = 0 - no communication area at all.
        if (!input.isCommareaPresent()) {
            // L83 MOVE 'COSGN00C' TO CDEMO-FROM-PROGRAM. FROM, not TO.
            //
            // The area itself is WORKING-STORAGE brought in by COPY COCOM01Y at line 50 and, having no
            // VALUE clause, holds binary zeros when the transaction is entered cold. NavigationContext
            // .empty() presents it as spaces and zeros instead, which reaches the same place: the
            // combined relation at line 162 tests LOW-VALUES *or* SPACES, so the source satisfies its
            // first half and this satisfies its second, and both default CDEMO-TO-PROGRAM to
            // 'COSGN00C'. Both halves are exercised independently through
            // returnToSignonScreen(NavigationContext, ...).
            NavigationContext coldStart = NavigationContext.empty().withFromProgram(SIGNON_PROGRAM);
            // L84 PERFORM RETURN-TO-SIGNON-SCREEN.
            return returnToSignonScreen(coldStart, input, wsMessage, ReceiveOutcome.NOT_PERFORMED);
        }

        // L86 MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA - a length-bounded copy. A caller that
        // supplied fewer than the 160 bytes CARDDEMO-COMMAREA declares would leave the tail of the area
        // at whatever it already held, which for a cold area is low-values. NavigationContext is a
        // decoded record rather than a byte span, so the bound has already been applied by whoever
        // decoded it and the copy here is the reference itself.
        NavigationContext context = input.navigationContext();

        // L87 IF NOT CDEMO-PGM-REENTER - first entry into the transaction.
        if (!input.isReenter()) {
            // L88 SET CDEMO-PGM-REENTER TO TRUE, so the next keystroke takes the ELSE branch.
            NavigationContext reentered = context.withPgmReenter();
            // L89 MOVE LOW-VALUES TO COADM1AO clears every output field before the paint; L90 sends.
            // OPTIONO is one of the fields cleared, so the echoed option is unpainted on this path -
            // X'00' at its declared width, the byte the group MOVE actually writes.
            return sendMenuScreen(reentered,
                    errorFlag,
                    wsMessage,
                    MAP_MESSAGE_COLOUR,
                    ScreenFieldImage.unpainted(OPTION_LENGTH),
                    true,
                    optionTable,
                    ReceiveOutcome.NOT_PERFORMED);
        }

        // L92 PERFORM RECEIVE-MENU-SCREEN.
        ReceiveOutcome receive = receiveMenuScreen();

        // L93 EVALUATE EIBAID. An ordered EVALUATE: the first matching WHEN wins, so the arms below are
        // written in source order and the last one is WHEN OTHER.
        //
        // Each arm compares the raw byte against a CicsAid constant, which is what line 93 does.
        // PfKeyResolver.resolve(byte) is deliberately NOT used to choose the arm even though it exposes
        // an explicit no-match: it reproduces app/cpy/CSSTRPFY.cpy, where PF3 and PF15 both set the
        // single token PFK03, and COADM01C does not copy CSSTRPFY. Choosing the arm by resolved token
        // would therefore make PF15 behave as PF3 in a program that has no such behaviour. Comparing
        // the byte keeps the two identical for DFHENTER and DFHPF3 and keeps every other byte -
        // resolvable or not - on WHEN OTHER, exactly as the inline EVALUATE does.
        byte eibAid = input.eibAid();
        if (PfKeyResolver.isAid(eibAid, CicsAid.DFHENTER)) {
            // L94-L95 WHEN DFHENTER: PERFORM PROCESS-ENTER-KEY.
            return processEnterKey(input, context, optionTable, receive);
        }
        if (PfKeyResolver.isAid(eibAid, CicsAid.DFHPF3)) {
            // L96-L98 WHEN DFHPF3: MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM - TO, not FROM - then
            // PERFORM RETURN-TO-SIGNON-SCREEN.
            NavigationContext leaving = context.withToProgram(SIGNON_PROGRAM);
            return returnToSignonScreen(leaving, input, wsMessage, receive);
        }

        // L99-L102 WHEN OTHER.
        // L100 MOVE 'Y' TO WS-ERR-FLG.
        errorFlag = true;
        // L101 MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE: a PIC X(50) sender into a PIC X(80) receiver,
        // so the fifty characters are left-justified and the remaining thirty are spaces. movePicX
        // states that direction at the call site; a plain assignment would neither pad nor truncate.
        wsMessage = codec.movePicX(SystemMessages.CCDA_MSG_INVALID_KEY, MESSAGE_LENGTH);
        // L102 PERFORM SEND-MENU-SCREEN. OPTIONO is not written on this path, so the field keeps the
        // value it already carries - which, statelessly, is the one the client re-supplied.
        return sendMenuScreen(context,
                errorFlag,
                wsMessage,
                MAP_MESSAGE_COLOUR,
                receivedOptionImage(input),
                false,
                optionTable,
                receive);
    }

    // =================================================================================================
    // Section 8 - PROCESS-ENTER-KEY, app/cbl/COADM01C.cbl:115-155.
    // =================================================================================================

    /**
     * Normalises the received {@code OPTIONI} value exactly as
     * {@code app/cbl/COADM01C.cbl:117-125} does, in four steps.
     *
     * <p>Public because each step must be assertable on its own; the third is the one an
     * implementation gets wrong silently.
     *
     * <ol>
     *   <li><strong>Trailing-space scan, lines 117 to 121.</strong>
     *       <pre>
     *   PERFORM VARYING WS-IDX
     *           FROM LENGTH OF OPTIONI BY -1 UNTIL
     *           OPTIONI(WS-IDX:1) NOT = SPACES OR
     *           WS-IDX = 1
     *   END-PERFORM
     *       </pre>
     *       An <strong>empty body</strong>, and {@code PERFORM VARYING ... UNTIL} is
     *       <strong>test-before</strong>: it sets the identifier from the {@code FROM} value,
     *       evaluates the condition, and only if the condition is false runs the body, applies the
     *       {@code BY} increment and re-tests. {@code LENGTH OF OPTIONI} is
     *       {@value #OPTION_LENGTH}. So the scan starts at 2; if byte 2 is not a space it stops at 2,
     *       otherwise it decrements to 1 and stops there, because {@code WS-IDX = 1} satisfies the
     *       second term whatever byte 1 holds. It is written below as the same descending
     *       test-before loop rather than as a trim or a last-index search, so that the reason the
     *       answer is 1 for {@code "3 "} stays visible.</li>
     *   <li><strong>Reference modification, line 122.</strong>
     *       {@code MOVE OPTIONI(1:WS-IDX) TO WS-OPTION-X} sends the <em>leading</em> {@code WS-IDX}
     *       bytes.</li>
     *   <li><strong>The {@code JUSTIFIED RIGHT} receiver, line 45.</strong> {@code WS-OPTION-X} is
     *       declared {@code PIC X(02) JUST RIGHT}, so that {@code MOVE} aligns the sender at the
     *       receiver's rightmost position and space-fills to the left. This is why a one-byte
     *       {@code "3"} becomes {@code " 3"} and not {@code "3 "}. The map agrees:
     *       {@code OPTION DFHMDF ... JUSTIFY=(RIGHT,ZERO) LENGTH=2} at
     *       {@code app/bms/COADM01.bms:145-149}.</li>
     *   <li><strong>{@code INSPECT}, line 123.</strong>
     *       {@code INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'} turns {@code " 3"} into
     *       {@code "03"}. Line 124 then moves the two characters into {@code WS-OPTION PIC 9(02)} and
     *       line 125 echoes them to {@code OPTIONO}.</li>
     * </ol>
     *
     * <p>The five cases that pin the behaviour down, each of which is a unit test:
     * <table border="1">
     *   <caption>Worked normalisation cases</caption>
     *   <tr><th>{@code OPTIONI}</th><th>{@code WS-IDX}</th><th>after step 3</th>
     *       <th>after step 4</th><th>{@code WS-OPTION}</th></tr>
     *   <tr><td>{@code "  "}</td><td>1</td><td>{@code "  "}</td><td>{@code "00"}</td><td>0</td></tr>
     *   <tr><td>{@code " 3"}</td><td>2</td><td>{@code " 3"}</td><td>{@code "03"}</td><td>3</td></tr>
     *   <tr><td>{@code "3 "}</td><td>1</td><td>{@code " 3"}</td><td>{@code "03"}</td><td>3</td></tr>
     *   <tr><td>{@code "12"}</td><td>2</td><td>{@code "12"}</td><td>{@code "12"}</td><td>12</td></tr>
     *   <tr><td>{@code "1x"}</td><td>2</td><td>{@code "1x"}</td><td>{@code "1x"}</td>
     *       <td>not numeric</td></tr>
     * </table>
     *
     * <p>The last row is the one that matters for the guard that follows. {@code MOVE WS-OPTION-X TO
     * WS-OPTION} sends alphanumeric to numeric {@code DISPLAY}, and at equal widths the two characters
     * cross unchanged - so {@code WS-OPTION} really does come to hold {@code "1x"}, which is precisely
     * what makes {@code WS-OPTION IS NOT NUMERIC} at line 127 a test with something to detect. This
     * method therefore reports a non-numeric field as {@link OptionalInt#empty()} rather than throwing:
     * an exception would replace a branch the program has with one it does not.
     *
     * @param receivedOption the {@code OPTIONI} value as received; moved into its declared
     *                       {@value #OPTION_LENGTH}-character width first
     * @return every intermediate of the normalisation
     * @throws NullPointerException if {@code receivedOption} is {@code null}
     */
    public OptionNormalisation normaliseOption(String receivedOption) {
        Objects.requireNonNull(receivedOption, "OPTIONI is PIC X(" + OPTION_LENGTH + ") and is never "
                + "null; pass spaces for an empty screen field");

        // OPTIONI is PIC X(2) (app/cpy-bms/COADM01.CPY:132). CICS delivers the received value into a
        // field of exactly that width, so anything wider or narrower is moved into it first, by the
        // alphanumeric MOVE rule: pad on the right, truncate on the right.
        String optionI = codec.movePicX(receivedOption, OPTION_LENGTH);

        // L117-L121, step 1. FROM LENGTH OF OPTIONI, BY -1, and the loop condition below is the
        // negation of the source's UNTIL so that the test happens before each decrement.
        int wsIdx = optionI.length();
        while (charAtOneBased(optionI, wsIdx) == SPACE && wsIdx != 1) {
            wsIdx = wsIdx - 1;
        }

        // L122, step 2: OPTIONI(1:WS-IDX) - the leading WS-IDX bytes.
        String sender = optionI.substring(0, wsIdx);

        // L45, step 3: the receiver is JUST RIGHT.
        String justifiedOptionX = movePicXJustifiedRight(sender, OPTION_X_LENGTH);

        // L123, step 4.
        String optionX = inspectReplacingSpacesByZeros(justifiedOptionX);

        // L124 MOVE WS-OPTION-X TO WS-OPTION. WS-OPTION-X is PIC X(02) and WS-OPTION is PIC 9(02): at
        // equal widths an alphanumeric-to-numeric-display MOVE copies the characters across, so the
        // image is unchanged and no zero-fill or truncation applies. FixedWidthCodec.movePic9 is
        // deliberately not used here - it rejects a non-digit image, and a non-digit image is exactly
        // the state line 127 must be able to observe.
        OptionalInt option = isAllDigits(optionX)
                ? OptionalInt.of(codec.decodePic9AsInt(optionX))
                : OptionalInt.empty();

        return new OptionNormalisation(wsIdx, optionI, justifiedOptionX, optionX, option);
    }

    /**
     * {@code PROCESS-ENTER-KEY}, {@code app/cbl/COADM01C.cbl:115-155}: normalise, validate, then
     * dispatch.
     *
     * <p>The validation and the dispatch are <strong>two consecutive {@code IF} statements with no
     * {@code ELSE} between them</strong>, and they are kept that way here:
     * <pre>
     *   L127  IF WS-OPTION IS NOT NUMERIC OR
     *   L128     WS-OPTION &gt; CDEMO-ADMIN-OPT-COUNT OR
     *   L129     WS-OPTION = ZEROS
     *   L130      MOVE 'Y' TO WS-ERR-FLG
     *   L131      MOVE 'Please enter a valid option number...' TO WS-MESSAGE
     *   L133      PERFORM SEND-MENU-SCREEN
     *   L134  END-IF
     *   L137  IF NOT ERR-FLG-ON
     *   ...
     *   L155  END-IF.
     * </pre>
     * {@code PERFORM SEND-MENU-SCREEN} on line 133 <em>returns</em> - it is a {@code PERFORM}, not a
     * transfer - so on the error path the program really does fall through to line 137, whose
     * {@code IF NOT ERR-FLG-ON} is false by line 130 and so suppresses everything after it. That is
     * why the screen painted at line 133 is held in a local here and returned at the end, rather than
     * returned from inside the first {@code IF}: returning early would make the line 137 guard
     * structurally unreachable, which is the same thing as merging two {@code IF} statements the source
     * keeps apart.
     *
     * @param input       the invocation, for the received {@code OPTIONI} value
     * @param context     {@code CARDDEMO-COMMAREA} on entry to the paragraph
     * @param optionTable the option table to validate against and subscript
     * @param receive     the outcome of the {@code RECEIVE MAP} that preceded this paragraph
     * @return the outcome
     */
    private AdminMenuOutcome processEnterKey(AdminMenuInput input,
            NavigationContext context,
            AdminMenuOptionTable optionTable,
            ReceiveOutcome receive) {

        OptionNormalisation normalisation = normaliseOption(input.option());
        // L125 MOVE WS-OPTION TO OPTIONO OF COADM1AO.
        String optionEcho = normalisation.optionEcho();

        // WS-ERR-FLG and WS-MESSAGE, both method-local (never fields).
        boolean errorFlag = false;
        String wsMessage = spaces(MESSAGE_LENGTH);
        AdminMenuOutcome painted = null;

        // L127-L129: three terms joined by OR, in source order. Java's || short-circuits, which is what
        // makes the second and third terms well defined: they read the numeric value, and the value is
        // only meaningful once the first term has established that the field holds digits. IBM COBOL
        // evaluates the terms left to right, so the outcome is identical either way - the first term
        // being true is already sufficient for the condition.
        if (!normalisation.isNumeric()                                        // L127
                || normalisation.option().getAsInt() > optionTable.activeCount()   // L128
                || normalisation.option().getAsInt() == ZERO_OPTION) {             // L129
            // L130 MOVE 'Y' TO WS-ERR-FLG.
            errorFlag = true;
            // L131-L132: a 37-character literal into WS-MESSAGE PIC X(80), left-justified and
            // right-space-padded to eighty.
            wsMessage = codec.movePicX(INVALID_OPTION_MESSAGE, MESSAGE_LENGTH);
            // L133 PERFORM SEND-MENU-SCREEN.
            painted = sendMenuScreen(context,
                    errorFlag,
                    wsMessage,
                    MAP_MESSAGE_COLOUR,
                    optionEcho,
                    false,
                    optionTable,
                    receive);
        }

        // L137 IF NOT ERR-FLG-ON.
        if (!errorFlag) {
            int wsOption = normalisation.option().getAsInt();
            // CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION) - a 1-based subscript. The guard above has already
            // rejected 0 and anything above the active count, and AdminMenuOptionTable guarantees every
            // slot up to that count carries a value, so the entry is always present here.
            AdminMenuOption entry = optionTable.optionBySubscript(wsOption).orElseThrow();
            // The eight-byte name, untrimmed: line 138 reference-modifies its first five characters.
            String targetProgram = entry.adminOptPgmName();

            // L138 IF CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'.
            if (!DUMMY_PROGRAM_PREFIX.equals(targetProgram.substring(0, DUMMY_PREFIX_LENGTH))) {
                NavigationContext transferring = context
                        .withFromTranid(TRANSACTION_ID)   // L139 MOVE WS-TRANID  TO CDEMO-FROM-TRANID
                        .withFromProgram(PROGRAM_NAME)    // L140 MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM
                        .withPgmEnter();                  // L141 MOVE ZEROS     TO CDEMO-PGM-CONTEXT

                // L142-L145 EXEC CICS XCTL PROGRAM(CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION))
                //                          COMMAREA(CARDDEMO-COMMAREA).
                //
                // RETURNING HERE IS THE WHOLE POINT. In CICS an XCTL never comes back: control leaves
                // COADM01C permanently and the statements after END-IF on line 146 - MOVE SPACES TO
                // WS-MESSAGE, MOVE DFHGREEN TO ERRMSGC, the STRING, and PERFORM SEND-MENU-SCREEN - are
                // reachable ONLY when line 138 found 'DUMMY' and skipped the transfer. Translating XCTL
                // into a response field removes that natural termination, so the return must be
                // explicit. Falling through would attach a 'This option is coming soon ...' message to
                // a successful transfer, which COADM01C never emits - a silent parity failure.
                return transferToProgram(targetProgram,
                        transferring,
                        true,
                        wsMessage,
                        optionEcho,
                        receive);
            }

            // Reached only on the 'DUMMY' path (line 146 END-IF).
            // L147 MOVE SPACES TO WS-MESSAGE, then L149-L153's STRING overlays the composed text at the
            // start of the field and leaves the remainder as the spaces line 147 put there.
            wsMessage = codec.padToDeclaredWidth(
                    codec.concatenateDelimitedBySize(COMING_SOON_PREFIX, COMING_SOON_SUFFIX),
                    MESSAGE_LENGTH);
            // L148 MOVE DFHGREEN TO ERRMSGC OF COADM1AO - overriding the map's COLOR=RED.
            // L154 PERFORM SEND-MENU-SCREEN.
            painted = sendMenuScreen(context,
                    errorFlag,
                    wsMessage,
                    COMING_SOON_MESSAGE_COLOUR,
                    optionEcho,
                    false,
                    optionTable,
                    receive);
        }

        // Back in MAIN-PARA at L107-L110, EXEC CICS RETURN. The two IF statements above are
        // complementary on errorFlag, so exactly one of them painted a screen and `painted` is always
        // set by the time control arrives here.
        return Objects.requireNonNull(painted, "PROCESS-ENTER-KEY reached its end without painting a "
                + "screen. app/cbl/COADM01C.cbl:127-155 is two complementary IF statements - the "
                + "second runs precisely when the first did not - so this cannot occur and indicates "
                + "the guard structure has been altered.");
    }

    // =================================================================================================
    // Section 9 - RETURN-TO-SIGNON-SCREEN, app/cbl/COADM01C.cbl:160-167.
    // =================================================================================================

    /**
     * {@code RETURN-TO-SIGNON-SCREEN}, {@code app/cbl/COADM01C.cbl:160-167}.
     *
     * <pre>
     *   L162  IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES
     *   L163      MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
     *   L164  END-IF
     *   L165  EXEC CICS
     *   L166      XCTL PROGRAM(CDEMO-TO-PROGRAM)
     *   L167  END-EXEC.
     * </pre>
     *
     * <p>Line 162 is an <strong>abbreviated combined relation</strong>: COBOL reads
     * {@code A = X OR Y} as {@code A = X OR A = Y}, so it means "equal to {@code LOW-VALUES}, or equal
     * to {@code SPACES}" and not "equal to the result of some expression". Both halves are implemented
     * and both are independently testable - a field of binary zeros satisfies the first, a field of
     * spaces the second, and a field that is partly one and partly the other satisfies neither.
     *
     * <p>The {@code XCTL} on lines 165 to 167 specifies <strong>no {@code COMMAREA}</strong>. That is
     * the difference from the option transfer at lines 142 to 145, which does specify one, and it is
     * recorded on the outcome as {@link AdminMenuOutcome#nextProgramCarriesCommarea()} rather than
     * being smoothed over: the sign-on program is entered with no communication area, which is exactly
     * the condition its own {@code EIBCALEN = 0} test is looking for.
     *
     * @param context     {@code CARDDEMO-COMMAREA} as the caller left it, its {@code CDEMO-TO-PROGRAM}
     *                    being what line 162 tests
     * @param input       the invocation, for the {@code OPTIONO} value the map already carries
     * @param wsMessage   {@code WS-MESSAGE}, still the spaces line 79 left in it on every path that
     *                    reaches here
     * @param receive     the outcome of the {@code RECEIVE MAP}, or
     *                    {@link ReceiveOutcome#NOT_PERFORMED}
     * @return the outcome, naming the sign-on program as the transfer target
     */
    private AdminMenuOutcome returnToSignonScreen(NavigationContext context,
            AdminMenuInput input,
            String wsMessage,
            ReceiveOutcome receive) {

        // L162-L164, in resolveSignonTarget so both halves of the combined relation are assertable.
        String toProgram = resolveSignonTarget(context.toProgram());

        // L165-L167 XCTL PROGRAM(CDEMO-TO-PROGRAM), with no COMMAREA. Returns immediately for the same
        // reason the option transfer does: an XCTL never comes back.
        return transferToProgram(toProgram,
                context.withToProgram(toProgram),
                false,
                wsMessage,
                receivedOptionImage(input),
                receive);
    }

    /**
     * The default of {@code app/cbl/COADM01C.cbl:162-164}: supply {@code 'COSGN00C'} when
     * {@code CDEMO-TO-PROGRAM} names nothing.
     *
     * <p>Public because the combined relation has two independent halves and each must be assertable on
     * its own. Reaching them both through {@link #handle(AdminMenuInput, AdminMenuOptionTable)} is not
     * possible, and for a reason worth recording: of the two paths into
     * {@code RETURN-TO-SIGNON-SCREEN}, line 97 has just written a name, and line 83's path leaves
     * {@code CDEMO-TO-PROGRAM} at whatever the un-{@code VALUE}d {@code WORKING-STORAGE} area holds -
     * binary zeros in the source, spaces in this projection's {@link NavigationContext#empty()}. So the
     * source exercises the {@code LOW-VALUES} half where the projection exercises the {@code SPACES}
     * half, the two converge on the same target, and testing the rule directly is what shows they do.
     *
     * @param toProgram {@code CDEMO-TO-PROGRAM} as it stands; padded to its declared
     *                  {@value NavigationContext#TO_PROGRAM_LENGTH} characters before the comparison,
     *                  so a short value is not mistaken for a populated one
     * @return the transfer target: {@value #SIGNON_PROGRAM} padded to its declared width when the field
     *         is all low-values or all spaces, otherwise the field's own image
     * @throws NullPointerException if {@code toProgram} is {@code null}
     */
    public String resolveSignonTarget(String toProgram) {
        Objects.requireNonNull(toProgram, "CDEMO-TO-PROGRAM is PIC X("
                + NavigationContext.TO_PROGRAM_LENGTH + ") and is never null; pass spaces or "
                + "low-values for a field that names nothing");

        String image = codec.movePicX(toProgram, NavigationContext.TO_PROGRAM_LENGTH);
        // L162 IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES - an abbreviated combined relation, so the
        // subject is repeated against each of the two figurative constants.
        if (isAllOf(image, LOW_VALUE) || isAllOf(image, SPACE)) {
            // L163 MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM.
            return codec.movePicX(SIGNON_PROGRAM, NavigationContext.TO_PROGRAM_LENGTH);
        }
        return image;
    }

    // =================================================================================================
    // Section 10 - SEND-MENU-SCREEN and BUILD-MENU-OPTIONS,
    //             app/cbl/COADM01C.cbl:172-184 and :226-263.
    // =================================================================================================

    /**
     * Composes the twelve {@code OPTN00nO} lines of {@code BUILD-MENU-OPTIONS},
     * {@code app/cbl/COADM01C.cbl:226-263}.
     *
     * <pre>
     *   L228  PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL
     *   L229                  WS-IDX &gt; CDEMO-ADMIN-OPT-COUNT
     *   L231      MOVE SPACES TO WS-ADMIN-OPT-TXT
     *   L233      STRING CDEMO-ADMIN-OPT-NUM(WS-IDX)  DELIMITED BY SIZE
     *   L234             '. '                         DELIMITED BY SIZE
     *   L235             CDEMO-ADMIN-OPT-NAME(WS-IDX) DELIMITED BY SIZE
     *   L236        INTO WS-ADMIN-OPT-TXT
     *   L238      EVALUATE WS-IDX
     *   L239          WHEN 1  MOVE WS-ADMIN-OPT-TXT TO OPTN001O
     *   ...
     *   L257          WHEN 10 MOVE WS-ADMIN-OPT-TXT TO OPTN010O
     *   L259          WHEN OTHER CONTINUE
     *   L261      END-EVALUATE
     *   L263  END-PERFORM.
     * </pre>
     *
     * <p>Four things about that loop are load-bearing:
     * <ul>
     *   <li><strong>It is 1-based and inclusive of the count.</strong> {@code FROM 1 BY 1 UNTIL
     *       WS-IDX &gt; count} runs for 1, 2, 3, 4 when the count is 4. Java indices are 0-based, and
     *       the shift is applied in exactly one place here - the array write - with the COBOL subscript
     *       kept as the loop variable so the source and this method count the same way.</li>
     *   <li><strong>The option number keeps its leading zero.</strong> {@code CDEMO-ADMIN-OPT-NUM} is
     *       {@code PIC 9(02)} and {@code STRING ... DELIMITED BY SIZE} moves the field's whole stored
     *       image, so line 1 reads {@code 01. } and not {@code 1. }. The number therefore comes from
     *       {@link AdminMenuOption#adminOptNumImage()} and never from the {@code int}.</li>
     *   <li><strong>The line is 39 characters into a 40-character field.</strong> Two for the number,
     *       two for {@value #OPTION_NUMBER_SEPARATOR}, thirty-five for the name. {@code STRING}
     *       overwrites only what it writes, and line 231 has just cleared the field, so character 40
     *       stays a space.</li>
     *   <li><strong>There are ten dispatch arms, not twelve.</strong> Arms 5 to 10 cannot fire while
     *       the count is 4, and there is no arm at all for {@code OPTN011O} or {@code OPTN012O} even
     *       though the map declares both and {@code COMEN01C} does dispatch to twelve. Every arm is
     *       preserved and lines 11 and 12 stay unwritten - which the loop bound below enforces without
     *       needing to name them, because a count that reached 11 would fall to {@code WHEN OTHER
     *       CONTINUE} and write nothing.</li>
     * </ul>
     *
     * @param optionTable the table to compose from
     * @return exactly {@value #OPTION_LINE_COUNT} lines of {@value #OPTION_TEXT_LENGTH} characters,
     *         index 0 being {@code OPTN001O}; every line the program does not write is spaces
     * @throws NullPointerException if {@code optionTable} is {@code null}
     */
    public List<String> buildMenuOptions(AdminMenuOptionTable optionTable) {
        Objects.requireNonNull(optionTable, "An option table is required to compose the menu lines");

        // COADM1AO's twelve line fields, each already cleared to spaces.
        List<String> lines = new ArrayList<>(blankOptionLines());

        // L228-L229 PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > CDEMO-ADMIN-OPT-COUNT.
        for (int wsIdx = 1; wsIdx <= optionTable.activeCount(); wsIdx++) {
            AdminMenuOption entry = optionTable.optionBySubscript(wsIdx).orElseThrow();

            // L231 MOVE SPACES TO WS-ADMIN-OPT-TXT, then L233-L236's STRING overlays 39 characters at
            // the start of the 40-byte field and leaves character 40 as the space line 231 put there.
            String wsAdminOptTxt = codec.padToDeclaredWidth(
                    codec.concatenateDelimitedBySize(entry.adminOptNumImage(),
                            OPTION_NUMBER_SEPARATOR,
                            entry.adminOptName()),
                    OPTION_TEXT_LENGTH);

            // L238-L261 EVALUATE WS-IDX: arms WHEN 1 through WHEN 10 each move the text to their own
            // OPTN00nO field, and WHEN OTHER does nothing. A subscript above the arm count therefore
            // leaves its line as spaces, which is the CONTINUE of line 260.
            if (wsIdx <= OPTION_DISPATCH_ARM_COUNT) {
                lines.set(wsIdx - 1, wsAdminOptTxt);
            }
        }
        return List.copyOf(lines);
    }

    /**
     * {@code SEND-MENU-SCREEN}, {@code app/cbl/COADM01C.cbl:172-184}, less the header the controller
     * owns.
     *
     * <p>The paragraph performs {@code POPULATE-HEADER-INFO}, then {@code BUILD-MENU-OPTIONS}, then
     * {@code MOVE WS-MESSAGE TO ERRMSGO}, then {@code EXEC CICS SEND MAP('COADM1A') MAPSET('COADM01')
     * FROM(COADM1AO) ERASE}. The first of those reads {@code FUNCTION CURRENT-DATE} at line 204 and is
     * the controller's, so that this service stays deterministic; the rest is here.
     *
     * @param context              {@code CARDDEMO-COMMAREA} as it will be handed back on line 109
     * @param errorFlag            {@code WS-ERR-FLG} at the moment of the send
     * @param wsMessage            {@code WS-MESSAGE}, exactly {@value #MESSAGE_LENGTH} characters, which
     *                             line 177 moves into {@code ERRMSGO}
     * @param messageColour        {@code ERRMSGC}
     * @param optionEcho           {@code OPTIONO}
     * @param resetAllOutputFields whether {@code MOVE LOW-VALUES TO COADM1AO} on line 89 preceded this
     * @param optionTable          the table {@code BUILD-MENU-OPTIONS} composes from
     * @param receive              the outcome of the {@code RECEIVE MAP}, if one occurred
     * @return the outcome, with no transfer target
     */
    private AdminMenuOutcome sendMenuScreen(NavigationContext context,
            boolean errorFlag,
            String wsMessage,
            byte messageColour,
            String optionEcho,
            boolean resetAllOutputFields,
            AdminMenuOptionTable optionTable,
            ReceiveOutcome receive) {

        // L175 PERFORM BUILD-MENU-OPTIONS; L177 MOVE WS-MESSAGE TO ERRMSGO; L179-L184 SEND MAP ... ERASE.
        return new AdminMenuOutcome(buildMenuOptions(optionTable),
                wsMessage,
                messageColour,
                errorFlag,
                optionEcho,
                spaces(NavigationContext.TO_PROGRAM_LENGTH),
                false,
                true,
                resetAllOutputFields,
                context,
                TRANSACTION_ID,
                MAPSET_NAME,
                MAP_NAME,
                receive);
    }

    /**
     * The shared shape of the program's two {@code EXEC CICS XCTL} statements: name a successor and
     * leave.
     *
     * <p>No screen is painted and no menu line is composed, because neither {@code XCTL} is preceded by
     * a {@code SEND} - so the twelve lines stay as the spaces they were.
     *
     * <h4>The mapset and map are blank here, and that is deliberate</h4>
     * An {@code XCTL} hands control to another program, and which map that program will paint is its
     * decision, made after this one has ended. Neither statement names a map: line 143 names only
     * {@code CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)} and line 166 only {@code CDEMO-TO-PROGRAM}. Naming
     * {@value #MAPSET_NAME} and {@value #MAP_NAME} on a transfer would tell the client to paint the
     * screen it is leaving, which is the one answer that is certainly wrong. Blank means "not stated
     * here": the client follows the successor named in {@code nextProgram}, and the target's own reply
     * names its map. The {@code SEND} path is the other case, and it still names this screen.
     *
     * @param targetProgram   the transfer target, exactly
     *                        {@value NavigationContext#TO_PROGRAM_LENGTH} characters
     * @param context         {@code CARDDEMO-COMMAREA} as it stands at the transfer
     * @param carriesCommarea whether the {@code XCTL} specifies {@code COMMAREA}: true for lines 142 to
     *                        145, false for lines 165 to 167
     * @param wsMessage       {@code WS-MESSAGE} as it stands, which on both transfer paths is the
     *                        spaces line 79 or line 147 left in it
     * @param optionEcho      {@code OPTIONO} as the map already holds it
     * @param receive         the outcome of the {@code RECEIVE MAP}, if one occurred
     * @return the outcome, naming the successor
     */
    private AdminMenuOutcome transferToProgram(String targetProgram,
            NavigationContext context,
            boolean carriesCommarea,
            String wsMessage,
            String optionEcho,
            ReceiveOutcome receive) {

        return new AdminMenuOutcome(blankOptionLines(),
                wsMessage,
                MAP_MESSAGE_COLOUR,
                false,
                optionEcho,
                codec.movePicX(targetProgram, NavigationContext.TO_PROGRAM_LENGTH),
                carriesCommarea,
                false,
                false,
                context,
                TRANSACTION_ID,
                spaces(NavigationContext.LAST_MAPSET_LENGTH),
                spaces(NavigationContext.LAST_MAP_LENGTH),
                receive);
    }

    /**
     * {@code RECEIVE-MENU-SCREEN}, {@code app/cbl/COADM01C.cbl:189-197}.
     *
     * <p>The statement reads the map into {@code COADM1AI} and captures {@code RESP} and {@code RESP2},
     * neither of which the program ever tests. In the stateless projection the terminal input has
     * already arrived - it is {@link AdminMenuInput#option()} and {@link AdminMenuInput#eibAid()} - so
     * this method records that the paragraph ran and reports {@code DFHRESP(NORMAL)}. No branch depends
     * on either code, matching the source, and no error handling is invented for a condition the source
     * does not inspect.
     *
     * @return {@link ReceiveOutcome#NORMAL}
     */
    private ReceiveOutcome receiveMenuScreen() {
        return ReceiveOutcome.NORMAL;
    }

    // =================================================================================================
    // Section 11 - the dead declarations, preserved.
    //
    // Practice: dead and orphan code is preserved, not cleaned up. Two of COADM01C's declarations are
    // never read by the program and both survive here, because a declaration is part of what the
    // program is. A third dead item, the RESP/RESP2 pair, is carried on ReceiveOutcome.
    // =================================================================================================

    /**
     * {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '} - {@code app/cbl/COADM01C.cbl:39}, declared and
     * never referenced again anywhere in the program.
     *
     * <p>Note the two trailing spaces in the source literal: the value is the eight-byte
     * <em>logical</em> file name of the security user file, padded to its declared width - not a dataset
     * name. It is resolved from the {@code carddemo.datasets} catalogue key
     * {@value #USRSEC_DATASET_KEY} declared in {@code application.yml}, so that no mainframe dataset
     * name is written anywhere in this file, and the constructor additionally checks that the
     * configured record width agrees with the eighty bytes of the {@code CSUSR01Y} record this program
     * copies.
     *
     * <p>It is a value, not a file handle. Nothing here opens, reads or writes anything.
     *
     * @return exactly {@value #USRSEC_FILE_NAME_LENGTH} characters
     */
    public String usrSecFileName() {
        return usrSecFileName;
    }

    /**
     * {@code COPY CSUSR01Y.} - {@code app/cbl/COADM01C.cbl:58}, which brings {@code 01 SEC-USER-DATA}
     * into working storage where not one of its six fields is read or written.
     *
     * <p>The copybook has twelve consumers across the application and this program is one of them, so
     * the declaration is preserved and mapped onto the single Java type that copybook owns rather than
     * being dropped as unused.
     *
     * <p><strong>This is a declaration and emphatically not a data-access path.</strong> Searching
     * {@code COADM01C} for {@code READ}, {@code STARTBR}, {@code READNEXT}, {@code WRITE},
     * {@code REWRITE} and {@code DELETE} returns nothing at all: the program's only file-related
     * artefacts are this unused record and the equally unused {@code WS-USRSEC-FILE} name. The
     * security-user repository is therefore <strong>not</strong> a collaborator of this service and does
     * not appear in either constructor - injecting it and reading a user would invent a {@code USRSEC}
     * access the program never performs, which is a new feature, not a migration. Authentication belongs
     * to {@code COSGN00C}; by the time {@code CA00} runs, it has already happened.
     *
     * @return a blank {@link SecUserRecord}, every character field spaces, exactly as un-valued COBOL
     *         working storage presents itself
     */
    public SecUserRecord secUserData() {
        return secUserData;
    }

    /**
     * The codec this service composes every image with.
     *
     * <p>Exposed so that the controller can perform the {@code PIC X(80)} to {@code PIC X(78)}
     * right-truncation of {@link AdminMenuOutcome#message()} into {@code ERRMSGO} through the same
     * codec, rather than reaching for a substring and getting the direction wrong.
     *
     * @return the codec supplied at construction, never {@code null}
     */
    public FixedWidthCodec codec() {
        return codec;
    }

    // =================================================================================================
    // Section 12 - private character primitives.
    //
    // Small, named and commented, because each one is a COBOL rule that a plain Java operator would get
    // subtly wrong. The two directions of an unqualified MOVE belong to FixedWidthCodec and are taken
    // from it; what remains here is what the codec deliberately does not own.
    // =================================================================================================

    /**
     * A COBOL {@code MOVE} into a {@code PIC X} receiver declared {@code JUSTIFIED RIGHT}.
     *
     * <p>Aligns the sender at the receiver's rightmost character position and fills the unused positions
     * on the <strong>left</strong> with spaces; if the sender is the wider of the two, the excess
     * <strong>leftmost</strong> characters are truncated. Both halves are the mirror image of
     * {@link FixedWidthCodec#movePicX(String, int)}, which implements the unqualified rule - fill from
     * the left, truncate on the right.
     *
     * <p>It lives here rather than in the codec because {@code JUSTIFIED RIGHT} is an attribute of one
     * particular receiver, {@code WS-OPTION-X} at {@code app/cbl/COADM01C.cbl:45}, and not a property of
     * the {@code PICTURE} clause. The codec owns the two directions a {@code PICTURE} implies -
     * left-justified for {@code PIC X} and right-justified with zero fill for {@code PIC 9} - and
     * neither is this one, which right-justifies with <em>space</em> fill. Left truncation cannot arise
     * on the one path that calls it, since the sender is a reference modification of a
     * {@value #OPTION_LENGTH}-byte field into a {@value #OPTION_X_LENGTH}-byte receiver, but the rule is
     * implemented completely so that the helper is correct rather than merely sufficient.
     *
     * @param source       the sending value
     * @param targetLength the receiver's declared width, at least 1
     * @return an image of exactly {@code targetLength} characters
     */
    private String movePicXJustifiedRight(String source, int targetLength) {
        if (source.length() >= targetLength) {
            return source.substring(source.length() - targetLength);
        }
        return spaces(targetLength - source.length()) + source;
    }

    /**
     * {@code INSPECT identifier REPLACING ALL ' ' BY '0'} - {@code app/cbl/COADM01C.cbl:123}.
     *
     * <p>Every space becomes a zero digit, wherever it sits. Applied after the {@code JUSTIFIED RIGHT}
     * move has put the space on the left, this is what turns {@code " 3"} into {@code "03"} and
     * {@code "  "} into {@code "00"}. It replaces spaces only, so a non-digit such as {@code 'x'}
     * survives - which is what leaves {@code WS-OPTION} holding a non-numeric value for line 127 to
     * detect.
     *
     * @param image the field's characters
     * @return the same width, with every space replaced by a zero digit
     */
    private String inspectReplacingSpacesByZeros(String image) {
        StringBuilder inspected = new StringBuilder(image.length());
        for (int index = 0; index < image.length(); index++) {
            char character = image.charAt(index);
            inspected.append(character == SPACE ? ZERO_DIGIT : character);
        }
        return inspected.toString();
    }

    /**
     * A COBOL 1-based reference modification of a single character: {@code identifier(position:1)}.
     *
     * <p>Named rather than written inline so that the one place the subscript shifts is visible. The
     * scan at {@code app/cbl/COADM01C.cbl:119} indexes {@code OPTIONI(WS-IDX:1)} with a 1-based
     * position, and every caller passes a position the loop bound has already constrained.
     *
     * @param image      the field's characters
     * @param oneBased   the 1-based character position
     * @return the character at that position
     */
    private static char charAtOneBased(String image, int oneBased) {
        return image.charAt(oneBased - 1);
    }

    /**
     * Whether every character of an image is one particular character - the shape of a comparison
     * against a figurative constant such as {@code SPACES} or {@code LOW-VALUES}.
     *
     * <p>Every caller passes an image already at a declared width - {@code CDEMO-TO-PROGRAM} is
     * {@code PIC X(08)} and is padded to eight characters before the comparison - so the zero-length
     * case cannot arise. Were it to, the predicate is vacuously true, which is the correct answer for
     * "every character matches".
     *
     * @param image     the field's characters, at its declared width
     * @param character the figurative constant's character
     * @return {@code true} when every character matches
     */
    private static boolean isAllOf(String image, char character) {
        for (int index = 0; index < image.length(); index++) {
            if (image.charAt(index) != character) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether an image holds only decimal digits - the negation of {@code IS NOT NUMERIC} for an
     * unsigned {@code PIC 9(n)} {@code DISPLAY} field.
     *
     * <p>{@link Character#isDigit(char)} is deliberately not used: it accepts the decimal digits of
     * every Unicode script, and a zoned {@code DISPLAY} field holds one code page's ten digits and
     * nothing else.
     *
     * <p>As with {@link #isAllOf(String, char)}, the only caller passes {@code WS-OPTION-X} at its
     * declared {@value #OPTION_X_LENGTH} characters, so the zero-length case cannot arise.
     *
     * @param image the field's characters, at its declared width
     * @return {@code true} when every character is {@code '0'} to {@code '9'}
     */
    private static boolean isAllDigits(String image) {
        for (int index = 0; index < image.length(); index++) {
            char character = image.charAt(index);
            if (character < ZERO_DIGIT || character > NINE_DIGIT) {
                return false;
            }
        }
        return true;
    }

    /**
     * The {@code OPTIONO} value on a path where the program does not write it.
     *
     * <p>{@code MOVE WS-OPTION TO OPTIONO} happens once, at {@code app/cbl/COADM01C.cbl:125} inside
     * {@code PROCESS-ENTER-KEY}. On every other path the field keeps whatever it already held, and
     * since no server-side state exists that is the value the client re-supplied - moved into its
     * declared {@value #OPTION_LENGTH}-character width first.
     *
     * @param input the invocation
     * @return exactly {@value #OPTION_LENGTH} characters
     */
    private String receivedOptionImage(AdminMenuInput input) {
        return codec.movePicX(input.option(), OPTION_LENGTH);
    }

    /**
     * The twelve {@code OPTN00nO} fields as {@code MOVE LOW-VALUES TO COADM1AO} leaves them, and as they
     * remain on every path that never performs {@code BUILD-MENU-OPTIONS}.
     *
     * <p>The unpainted image, not spaces: line {@code app/cbl/COADM01C.cbl:89} moves {@code X'00'}, and a screen field that was
     * never written carries that byte. {@link ScreenFieldImage} records the choice once for all
     * seventeen screens and explains why the two figurative constants are not interchangeable.
     *
     * @return {@value #OPTION_LINE_COUNT} unpainted lines of {@value #OPTION_TEXT_LENGTH} characters
     */
    private static List<String> blankOptionLines() {
        return Collections.nCopies(OPTION_LINE_COUNT,
                ScreenFieldImage.unpainted(OPTION_TEXT_LENGTH));
    }

    /**
     * The COBOL {@code SPACES} figurative constant at a stated width.
     *
     * <p>An unconditional space fill, which is not the alphanumeric {@code MOVE} rule: that rule pads a
     * shorter sender and truncates a longer one, and it lives solely in
     * {@link FixedWidthCodec#movePicX(String, int)}.
     *
     * @param width the number of characters, zero or more
     * @return a run of {@code width} spaces
     */
    private static String spaces(int width) {
        return String.valueOf(SPACE).repeat(width);
    }
}
