package com.vsergeychik.carddemo.admin;

import com.vsergeychik.carddemo.admin.model.MenuOptions;
import com.vsergeychik.carddemo.admin.model.MenuOptions.MenuOption;
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
 * The decision core of {@code app/cbl/COMEN01C.cbl} - the CardDemo main menu for regular users, CICS
 * transaction {@code CM00}.
 *
 * <h2>Why this class exists</h2>
 * Every branch {@code COMEN01C} contains lives here and nowhere else. {@code MainMenuController}
 * carries <strong>no</strong> branch logic at all: it maps its request DTO onto {@link MainMenuInput},
 * calls {@link #handle(MainMenuInput)} once, and projects the returned {@link MainMenuOutcome} onto its
 * response DTO. That split is not a style preference. The migration's coverage gate is a
 * <em>per-package</em> JaCoCo {@code BRANCH} ratio of 0.90, and a branch reachable only through
 * {@code MockMvc} is a branch that gate cannot see. Putting the decisions in a plain object that a
 * JUnit test constructs directly - no {@code MockMvc}, no {@code WebApplicationContext}, no HTTP, no
 * {@code JobLauncher} - is what makes the gate satisfiable, and it is what makes a failing parity case
 * point at one statement of one paragraph.
 *
 * <p>The user-type authorisation filter of {@link #isAdminOnlyOption(MainMenuOptionTable, OptionalInt)}
 * is the single most important reason that split matters here: it is the one decision in this package
 * that has no counterpart in the sibling program, it is reached on paths a casual reading misses, and
 * every one of its inputs must be drivable from a plain test.
 *
 * <p>Accordingly every method below takes its inputs as parameters and returns a value. None reaches
 * into a request object, none consults a clock, a random source or an environment variable, and none
 * touches a dataset. The class is deterministic: the same {@link MainMenuInput} always produces the
 * same {@link MainMenuOutcome}, byte for byte.
 *
 * <h2>This is a like-for-like migration</h2>
 * {@code COMEN01C} is 282 lines and this class reproduces its observable behaviour exactly - the
 * evaluation order of its {@code EVALUATE}, the guard chain of its three consecutive {@code IF}
 * statements, the composition of every literal it emits, and the odd things it does. Where the source
 * does something surprising, this class does the same surprising thing and the comment beside it cites
 * the line. Nothing is corrected, simplified, tidied or extended.
 *
 * <p><strong>The most conspicuous of those surprises is the "coming soon" message.</strong> Line 161
 * composes the option name {@code DELIMITED BY SPACE}, and line 162's literal has no leading space, so
 * the program really does emit {@code This option Accountis coming soon ...} - no space before
 * {@code is} and only the first word of the name. See {@link #COMING_SOON_SUFFIX} and
 * {@link #stringDelimitedBySpace(String)}. It looks like a defect and it is reproduced, not repaired.
 *
 * <p><strong>No user-specified rules govern this file.</strong> {@code review_rules} returns exactly
 * one line - "No user rules provided." - and that is the whole document. Their absence is not licence
 * to lower the bar, so the migration's own enterprise-practice substitutes bind instead, and the ones
 * that shape this file are named at the declarations they shape.
 *
 * <h2>What the source actually does, and what it does not</h2>
 * Four facts about {@code COMEN01C} were verified by exhaustive search of the program and are the
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
 *       no return code other than the CICS default is produced. There is also no {@code GO TO} and no
 *       {@code PERFORM ... THRU} anywhere in the program, so no restructuring of unstructured control
 *       flow was required: the paragraphs below map one-to-one onto methods.</li>
 *   <li><strong>Nine copybooks, at lines 50 to 61</strong> - {@code COCOM01Y}, {@code COMEN02Y},
 *       {@code COMEN01}, {@code COTTL01Y}, {@code CSDAT01Y}, {@code CSMSG01Y}, {@code CSUSR01Y},
 *       {@code DFHAID} and {@code DFHBMSCA}. It does <em>not</em> copy {@code CSSTRPFY},
 *       {@code DFHATTR}, {@code CSMSG02Y} or {@code CSSETATY}, so no PF-key store, no extended
 *       attribute set and no field-highlight helper belongs in this file. The only DFH symbols the
 *       program uses are {@code DFHENTER}, {@code DFHPF3} and {@code DFHGREEN}.</li>
 * </ul>
 *
 * <h2>Where the layer boundary falls</h2>
 * {@code SEND-MENU-SCREEN} at lines 182 to 194 performs three things in order:
 * {@code POPULATE-HEADER-INFO}, {@code BUILD-MENU-OPTIONS}, then
 * {@code MOVE WS-MESSAGE TO ERRMSGO} and the {@code SEND MAP}. The second and third are this class's
 * work and the first is not, because {@code POPULATE-HEADER-INFO} reads
 * {@code FUNCTION CURRENT-DATE} at line 214 and a service that read a clock could not be asserted
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
 *       {@link MainMenuOutcome#message()} into {@code ERRMSGO}, which is {@code PIC X(78)} at
 *       {@code app/cpy-bms/COMEN01.CPY:260}. {@link MainMenuOutcome#message()} is exposed at its
 *       full eighty characters precisely so that truncation is the controller's explicit, reviewable
 *       step rather than something this class did quietly.</li>
 * </ul>
 *
 * <h2>Statelessness</h2>
 * CICS is pseudo-conversational: {@code COMEN01C} ends at lines 107 to 110 with
 * {@code EXEC CICS RETURN TRANSID('CM00') COMMAREA(CARDDEMO-COMMAREA)} and the next keystroke starts
 * the program again from the top with that area handed back to it. The Java projection keeps that
 * shape and keeps <strong>no server-side state whatsoever</strong>: no session, no scoped bean, no
 * cache, no static mutable field. The whole conversation - the communication area, the attention
 * identifier and the screen's own field values - arrives in {@link MainMenuInput} and leaves in
 * {@link MainMenuOutcome}, and the client re-supplies it on the next call.
 *
 * <p>That is also how {@code CDEMO-USER-TYPE} reaches the authorisation filter. This program never
 * assigns it: the only assignment in the whole of {@code COMEN01C} is the <strong>commented-out</strong>
 * line 150, so the value is purely inbound, put there by {@code COSGN00C} - whose own role decision at
 * {@code app/cbl/COSGN00C.cbl:230-240} is what routes an administrator to {@code COADM01C} and a
 * regular user to {@code COMEN01C} in the first place. In the Java projection that decision is a role
 * field on the sign-on response and the client issues the follow-up call, so there is no server-side
 * forward and no session affinity.
 *
 * <p>The three instance fields this class does hold are immutable and none of them is COBOL
 * {@code WORKING-STORAGE} that the program mutates. {@code WS-ERR-FLG}, {@code WS-OPTION},
 * {@code WS-OPTION-X}, {@code WS-IDX}, {@code WS-MESSAGE} and {@code WS-MENU-OPT-TXT} are all
 * method-local variables or components of the returned outcome, because a singleton bean holding
 * per-request working storage in a field would break request isolation and test determinism alike.
 *
 * <h2>Security posture is preserved, not improved</h2>
 * The authorisation filter is a <strong>plain single-byte data comparison</strong>: is the inbound
 * user type the character {@code 'U'}, and does this menu entry's authorisation column hold
 * {@code 'A'}. That is precisely what line 136 and line 137 do. No Spring Security appears here, no
 * {@code @PreAuthorize}, no {@code @Secured}, no authority, no filter chain and no hashing - every one
 * of those would change behaviour, and this migration adds no features. Authentication itself belongs
 * to {@code COSGN00C}; by the time {@code CM00} runs it has already happened.
 *
 * <h2>Naming: this package has no divergence to warn about</h2>
 * Elsewhere in this migration a prompt-mandated class name can contradict what its COBOL program
 * does, and every such divergence is flagged where it occurs. There is none here.
 * {@code COMEN01C}'s own header reads {@code Function : Main Menu for the Regular users} and this is a
 * main menu controller's service; its sibling {@code COADM01C} is likewise the real admin menu.
 * Contrast {@code transaction.TransactionMenuController}, whose {@code COTRN00C} actually
 * <em>lists</em> transactions, and {@code user.UserMenuController}, whose {@code COUSR00C} actually
 * <em>lists users</em>: those two are list screens wearing menu names. These two are the menus.
 *
 * <h2>Deliberately no shared base class with {@code AdminMenuService}</h2>
 * {@code COMEN01C} and {@code COADM01C} are about ninety-five percent identical, and they are
 * deliberately <strong>not</strong> given a common superclass. What differs between them is exactly
 * the byte-level detail parity depends on:
 * <ul>
 *   <li>the option name is composed {@code DELIMITED BY SPACE} here and is
 *       <em>commented out altogether</em> there, so the two programs emit different messages from what
 *       looks like the same statement - thirty characters there, and here a name-dependent
 *       thirty-four to forty-one;</li>
 *   <li>the {@code EVALUATE WS-IDX} of {@code BUILD-MENU-OPTIONS} has twelve arms here and ten
 *       there;</li>
 *   <li>the user-type authorisation filter exists only here;</li>
 *   <li>the option table is {@code OCCURS 12} with an active count of 10 here, and {@code OCCURS 9}
 *       with an active count of 4 there.</li>
 * </ul>
 * A shared superclass would have to parameterise precisely the differences that must stay visible. The
 * migration reserves the template-method treatment for the four near-identical <em>batch</em> readers
 * and for nothing else. This is also the stated resolution of the "JOBOL" anti-pattern: refactor
 * <em>readability</em> - layering, structured control flow, named helpers - and hold <em>semantics</em>
 * byte-exact.
 *
 * <h2>Thread safety</h2>
 * Immutable after construction and safe to share across threads. The three fields are final and hold
 * an immutable codec, a {@link String} and an immutable record. Every method is either a pure function
 * of its parameters or a reader of those final fields; nothing is lazily initialised and no array or
 * mutable collection is ever handed out.
 *
 * @see MenuOptions
 * @see NavigationContext
 * @see AdminMenuService
 */
@Service
public class MainMenuService {

    // =================================================================================================
    // Section 1 - program and screen identity.
    //
    // Every literal below is transcribed from a source line that is cited beside it, and each is
    // declared once so that no method retypes a name the parity differ will compare.
    // =================================================================================================

    /** {@code WS-PGMNAME PIC X(08) VALUE 'COMEN01C'} - {@code app/cbl/COMEN01C.cbl:36}. */
    public static final String PROGRAM_NAME = "COMEN01C";

    /**
     * {@code WS-TRANID PIC X(04) VALUE 'CM00'} - {@code app/cbl/COMEN01C.cbl:37}.
     *
     * <p>The same four characters that {@code app/csd/CARDDEMO.CSD:399-400} maps to
     * {@code PROGRAM(COMEN01C)}, that line 108 names on {@code EXEC CICS RETURN TRANSID}, and that
     * line 147 moves into {@code CDEMO-FROM-TRANID} before transferring to a menu option.
     */
    public static final String TRANSACTION_ID = "CM00";

    /** {@code MAPSET('COMEN01')} - {@code app/cbl/COMEN01C.cbl:191} and {@code :203}. */
    public static final String MAPSET_NAME = "COMEN01";

    /** {@code MAP('COMEN1A')} - {@code app/cbl/COMEN01C.cbl:190} and {@code :202}. */
    public static final String MAP_NAME = "COMEN1A";

    /**
     * {@code 'COSGN00C'} - the sign-on program, named at three separate sites.
     *
     * <p>Lines 83, 97 and 173, and the three are <strong>not</strong> interchangeable: line 83 moves
     * it into {@code CDEMO-FROM-PROGRAM}, line 97 into {@code CDEMO-TO-PROGRAM}, and line 173 into
     * {@code CDEMO-TO-PROGRAM} as a default. See {@link #handle(MainMenuInput, MainMenuOptionTable)}
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
     * {@code WS-MESSAGE PIC X(80) VALUE SPACES} - {@code app/cbl/COMEN01C.cbl:38}.
     *
     * <p>Eighty, and deliberately not the fifty of {@code SystemMessages.MESSAGE_LENGTH}: that
     * constant is the width of {@code CCDA-MSG-INVALID-KEY PIC X(50)} in
     * {@code app/cpy/CSMSG01Y.cpy}. Line 101 moves the fifty-byte message into this eighty-byte field,
     * a widening {@code MOVE} that left-justifies and pads on the right, and neither width is the
     * seventy-eight of {@code ERRMSGO}. All three are real and distinct.
     */
    public static final int MESSAGE_LENGTH = 80;

    /** {@code WS-MENU-OPT-TXT PIC X(40) VALUE SPACES} - {@code app/cbl/COMEN01C.cbl:48}. */
    public static final int OPTION_TEXT_LENGTH = 40;

    /** {@code WS-OPTION-X PIC X(02) JUST RIGHT} - {@code app/cbl/COMEN01C.cbl:45}. */
    public static final int OPTION_X_LENGTH = 2;

    /** {@code WS-OPTION PIC 9(02) VALUE 0} - {@code app/cbl/COMEN01C.cbl:46}. */
    public static final int OPTION_DIGITS = 2;

    /**
     * {@code OPTIONI PIC X(2)} and {@code OPTIONO PIC X(2)} - {@code app/cpy-bms/COMEN01.CPY:132}
     * and {@code :254}, corroborated by {@code OPTION DFHMDF ... LENGTH=2} in
     * {@code app/bms/COMEN01.bms:145-149}.
     *
     * <p>This is the value {@code LENGTH OF OPTIONI} yields at
     * {@code app/cbl/COMEN01C.cbl:118}, which is where the trailing-space scan starts.
     */
    public static final int OPTION_LENGTH = 2;

    /**
     * The twelve {@code OPTN001O} through {@code OPTN012O} lines of the map, each
     * {@value #OPTION_TEXT_LENGTH} bytes - {@code app/cpy-bms/COMEN01.CPY:182-248}.
     *
     * <p>All twelve exist on the screen, this program has a dispatch arm for every one of them, and
     * with an active count of {@value MenuOptions#ACTIVE_OPTION_COUNT} it writes the first ten. Both
     * facts are preserved rather than collapsed - see {@link #buildMenuOptions(MainMenuOptionTable)}.
     */
    public static final int OPTION_LINE_COUNT = 12;

    /**
     * The number of arms in the {@code EVALUATE WS-IDX} of {@code BUILD-MENU-OPTIONS}:
     * {@code WHEN 1} through {@code WHEN 12} at {@code app/cbl/COMEN01C.cbl:249-272}, then
     * {@code WHEN OTHER CONTINUE} at {@code :273-274}.
     *
     * <p>Twelve, not ten - and this is one of the differences from {@code COADM01C}, whose
     * {@code EVALUATE} stops at {@code WHEN 10} and can therefore never write {@code OPTN011O} or
     * {@code OPTN012O}. This program can write all twelve. Arms 11 and 12 are nevertheless unreachable
     * while the active count is {@value MenuOptions#ACTIVE_OPTION_COUNT}, and they are preserved
     * exactly as written: dead code in a like-for-like migration is preserved, never cleaned up.
     */
    public static final int OPTION_DISPATCH_ARM_COUNT = 12;

    /** {@code WS-USRSEC-FILE PIC X(08)} - {@code app/cbl/COMEN01C.cbl:39}. */
    public static final int USRSEC_FILE_NAME_LENGTH = 8;

    /**
     * The five bytes {@code app/cbl/COMEN01C.cbl:146} compares:
     * {@code CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5)}.
     *
     * <p>A reference modification of the first five characters of an eight-character field, which is
     * why {@link MenuOption#menuOptPgmName()} must be held untrimmed at its full declared width.
     */
    public static final int DUMMY_PREFIX_LENGTH = 5;

    // =================================================================================================
    // Section 3 - the literals this program emits, byte for byte.
    //
    // Each carries its verified character count, because the parity differ compares these field by
    // field and a single character of drift is a diff.
    // =================================================================================================

    /**
     * {@code 'DUMMY'} - the sentinel prefix tested at {@code app/cbl/COMEN01C.cbl:146}.
     *
     * <p>No entry in {@code app/cpy/COMEN02Y.cpy} begins with it, so the comparison is always true in
     * production and the {@code XCTL} always fires. The branch nevertheless exists in the source and so
     * exists here, unaltered, and is driven by supplying a stub option table - see
     * {@link MainMenuOptionTable}. Deleting an unreachable branch would be a behaviour change, and it
     * is the only way to reach the "coming soon" message at all.
     */
    public static final String DUMMY_PROGRAM_PREFIX = "DUMMY";

    /**
     * {@code 'Please enter a valid option number...'} - {@code app/cbl/COMEN01C.cbl:131}, verified
     * <strong>37</strong> characters, three trailing full stops and no trailing space.
     *
     * <p>Moved into {@code WS-MESSAGE PIC X(80)}, so the emitted image is these 37 characters followed
     * by 43 spaces.
     */
    public static final String INVALID_OPTION_MESSAGE = "Please enter a valid option number...";

    /**
     * {@code 'No access - Admin Only option... '} - {@code app/cbl/COMEN01C.cbl:140}, verified
     * <strong>33</strong> characters <strong>including one trailing space</strong>.
     *
     * <p>The trailing space is real and is transcribed deliberately. Line 139's
     * {@code MOVE SPACES TO WS-MESSAGE} precedes it and line 140 then moves this literal into the same
     * {@code PIC X(80)} field, so the emitted image is these 33 characters followed by 47 spaces and
     * the literal's own trailing space is invisible in the result. It is kept regardless: the literal
     * is what the source declares, and a differ that ever compares the literal rather than the padded
     * image would see the difference.
     *
     * <p>This message has no counterpart in {@code COADM01C} - the whole authorisation filter is
     * unique to this program. See {@link #isAdminOnlyOption(MainMenuOptionTable, OptionalInt)}.
     */
    public static final String NO_ACCESS_MESSAGE = "No access - Admin Only option... ";

    /**
     * {@code 'This option '} - the first {@code STRING} operand at
     * {@code app/cbl/COMEN01C.cbl:159}, verified <strong>12</strong> characters including its trailing
     * space, and specified {@code DELIMITED BY SIZE} so all twelve are sent.
     */
    public static final String COMING_SOON_PREFIX = "This option ";

    /**
     * {@code 'is coming soon ...'} - the third {@code STRING} operand at
     * {@code app/cbl/COMEN01C.cbl:162}, verified <strong>18</strong> characters, and specified
     * {@code DELIMITED BY SIZE}.
     *
     * <p><strong>It has no leading space, and the operand before it is delimited by space.</strong>
     * The statement is:
     * <pre>
     *   L159          STRING 'This option '       DELIMITED BY SIZE
     *   L160                 CDEMO-MENU-OPT-NAME(WS-OPTION)
     *   L161                                      DELIMITED BY SPACE
     *   L162                 'is coming soon ...' DELIMITED BY SIZE
     *   L163            INTO WS-MESSAGE
     * </pre>
     * {@code DELIMITED BY SPACE} sends the characters up to, and not including, the first space of the
     * sending item. Every {@code CDEMO-MENU-OPT-NAME} is a 35-byte space-padded literal, so only its
     * first word survives, and because this suffix begins with {@code 'i'} the two run together:
     * <table border="1">
     *   <caption>Verified composed messages</caption>
     *   <tr><th>option</th><th>{@code CDEMO-MENU-OPT-NAME}</th><th>composed {@code WS-MESSAGE} text</th>
     *       <th>length</th></tr>
     *   <tr><td>1</td><td>{@code Account View}</td>
     *       <td>{@code This option Accountis coming soon ...}</td><td>37</td></tr>
     *   <tr><td>3</td><td>{@code Credit Card List}</td>
     *       <td>{@code This option Creditis coming soon ...}</td><td>36</td></tr>
     *   <tr><td>6</td><td>{@code Transaction List}</td>
     *       <td>{@code This option Transactionis coming soon ...}</td><td>41</td></tr>
     *   <tr><td>10</td><td>{@code Bill Payment}</td>
     *       <td>{@code This option Billis coming soon ...}</td><td>34</td></tr>
     * </table>
     *
     * <p>There is no space between the truncated name and {@code is}. This looks like a defect and it
     * is not this migration's to fix: no new features and no changed behaviour, and dead or defective
     * behaviour is preserved rather than repaired. Contrast {@code COADM01C}, whose lines 150 and 151
     * <em>comment the name out entirely</em> and so emit exactly
     * {@code This option is coming soon ...} - thirty characters. The two sibling programs produce
     * different messages from what looks like the same code.
     *
     * @see #stringDelimitedBySpace(String)
     */
    public static final String COMING_SOON_SUFFIX = "is coming soon ...";

    /**
     * {@code '. '} - the second {@code STRING} operand of {@code BUILD-MENU-OPTIONS} at
     * {@code app/cbl/COMEN01C.cbl:244}, two characters: a full stop and a space.
     */
    public static final String OPTION_NUMBER_SEPARATOR = ". ";

    /**
     * {@code 88 ERR-FLG-ON VALUE 'Y'} - {@code app/cbl/COMEN01C.cbl:41}, the literal moved by line 130
     * and the condition name {@code SET ERR-FLG-ON TO TRUE} asserts at line 138.
     */
    public static final String ERR_FLG_ON = "Y";

    /**
     * {@code 88 ERR-FLG-OFF VALUE 'N'} - {@code app/cbl/COMEN01C.cbl:42}, and the
     * {@code VALUE} clause of {@code WS-ERR-FLG} at line 40 that
     * {@code SET ERR-FLG-OFF TO TRUE} at line 77 restores.
     */
    public static final String ERR_FLG_OFF = "N";

    /**
     * {@code ZEROS} as {@code app/cbl/COMEN01C.cbl:129} compares it: {@code WS-OPTION = ZEROS}.
     *
     * <p>{@code WS-OPTION} is {@code PIC 9(02)}, so the figurative constant compares as the numeric
     * value zero.
     */
    public static final int ZERO_OPTION = 0;

    /**
     * {@code 'A'} - the one character {@code app/cbl/COMEN01C.cbl:137} compares
     * {@code CDEMO-MENU-OPT-USRTYPE(WS-OPTION)} against.
     *
     * <p>Declared separately from {@link NavigationContext#USER_TYPE_ADMIN} even though the two hold
     * the same character, because they are <strong>different fields</strong>:
     * {@code CDEMO-USER-TYPE} in {@code app/cpy/COCOM01Y.cpy} says who the signed-on user is, and
     * {@code CDEMO-MENU-OPT-USRTYPE} in {@code app/cpy/COMEN02Y.cpy} says which user type a menu entry
     * is restricted to. Collapsing them onto one constant would assert an identity the copybooks do
     * not.
     *
     * <p>All ten entries {@code COMEN02Y} values carry {@code 'U'}, so this comparison is false for
     * every entry the real table can offer. It is nonetheless implemented completely, and the true arm
     * is driven with a stub table.
     */
    public static final String ADMIN_ONLY_USRTYPE = "A";

    /**
     * The colour the map declares for {@code ERRMSG}: {@code COLOR=RED} at
     * {@code app/bms/COMEN01.bms:154-157}.
     *
     * <p>This is the colour in force unless the program overrides it, and the only override is
     * {@code MOVE DFHGREEN TO ERRMSGC} at {@code app/cbl/COMEN01C.cbl:158}. So a
     * {@link MainMenuOutcome#messageColour()} of this value means "the program did not touch
     * {@code ERRMSGC}", which is what {@link MainMenuOutcome#messageColourOverridden()} reports
     * without a caller having to know which byte means what.
     */
    public static final byte MAP_MESSAGE_COLOUR = BmsAttributes.DFHRED;

    /**
     * {@code MOVE DFHGREEN TO ERRMSGC OF COMEN1AO} - {@code app/cbl/COMEN01C.cbl:158}, the program's
     * one and only attribute write.
     *
     * <p>It applies on exactly one path: the "coming soon" path, reached only when the
     * {@value #DUMMY_PROGRAM_PREFIX} test at line 146 suppressed the transfer. Green for an
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
     * <p>{@code app/cbl/COMEN01C.cbl:39} declares {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '} -
     * the eight-byte <em>logical</em> file name with its two trailing spaces, not a dataset name. The
     * dataset name behind it lives only in configuration and never in Java. See
     * {@link #usrSecFileName()}.
     */
    public static final String USRSEC_DATASET_KEY = "USRSEC";

    /**
     * The name of the code page {@link #MainMenuService(DatasetBindings)} builds its codec over:
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

    /**
     * The character COBOL pads an alphanumeric field with, the byte the scan at line 119 tests, and the
     * delimiter of the {@code DELIMITED BY SPACE} phrase on line 161.
     */
    private static final char SPACE = ' ';

    /** The character {@code INSPECT ... REPLACING ALL ' ' BY '0'} substitutes at line 123. */
    private static final char ZERO_DIGIT = '0';

    /** The upper bound of the digit range an unsigned {@code PIC 9} {@code DISPLAY} field may hold. */
    private static final char NINE_DIGIT = '9';

    /** The byte {@code LOW-VALUES} fills, tested by the combined relation at line 172. */
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
     * {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '} - {@code app/cbl/COMEN01C.cbl:39}, declared
     * and then never referenced again anywhere in the program.
     *
     * @see #usrSecFileName()
     */
    private final String usrSecFileName;

    /**
     * {@code COPY CSUSR01Y.} - {@code app/cbl/COMEN01C.cbl:58}, which brings
     * {@code 01 SEC-USER-DATA} into working storage. Not one of its six fields is read or written by
     * this program; the only mention of any of them anywhere in {@code COMEN01C} is the
     * commented-out line 150.
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
     * never called, because {@code COMEN01C} performs no input or output and its screen fields are
     * character data that travels in a payload. Where a caller nonetheless wants one codec instance
     * shared across a call path, {@link #MainMenuService(DatasetBindings, FixedWidthCodec)} takes it
     * explicitly - so the choice is never hidden, merely defaulted.
     *
     * @param datasetBindings the {@code carddemo.datasets} catalogue; must declare
     *                        {@value #USRSEC_DATASET_KEY}
     * @throws NullPointerException  if {@code datasetBindings} is {@code null}
     * @throws IllegalStateException if {@value #USRSEC_DATASET_KEY} is not configured, or is
     *                               configured with a record width other than the eighty bytes
     *                               {@code app/cpy/CSUSR01Y.cpy} declares
     */
    @Autowired
    public MainMenuService(DatasetBindings datasetBindings) {
        this(datasetBindings, new FixedWidthCodec(DEFAULT_MESSAGE_CHARSET));
    }

    /**
     * Creates the service with the codec stated explicitly.
     *
     * <p>This is the constructor a unit test uses, and the one a caller uses to share a single codec
     * instance across a request. Both constructors behave identically for every value this class
     * produces, for the reason set out on {@link #MainMenuService(DatasetBindings)}.
     *
     * @param datasetBindings the {@code carddemo.datasets} catalogue; must declare
     *                        {@value #USRSEC_DATASET_KEY}
     * @param codec           the fixed-width character codec this service composes every image with
     * @throws NullPointerException  if either argument is {@code null}
     * @throws IllegalStateException if {@value #USRSEC_DATASET_KEY} is not configured, or is
     *                               configured with a record width other than the eighty bytes
     *                               {@code app/cpy/CSUSR01Y.cpy} declares
     */
    public MainMenuService(DatasetBindings datasetBindings, FixedWidthCodec codec) {
        Objects.requireNonNull(datasetBindings, "The carddemo.datasets binding catalogue is required: "
                + "app/cbl/COMEN01C.cbl:39 declares WS-USRSEC-FILE, and the name behind it is resolved "
                + "from configuration because dataset names are never written in Java");
        this.codec = Objects.requireNonNull(codec, "A fixed-width codec is required: every image this "
                + "service composes is padded, truncated or concatenated through it so that the "
                + "direction of each operation is explicit at the call site");

        // app/cbl/COMEN01C.cbl:39 - WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '. Resolving the key
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
                    + "X(08), SEC-USR-TYPE X(01) and SEC-USR-FILLER X(23). app/cbl/COMEN01C.cbl:58 "
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
     * Everything {@code COMEN01C} learns about one invocation: the communication area, the attention
     * identifier and the one screen field it reads.
     *
     * <p>Those three are exactly what the program consults. It reads {@code EIBCALEN} through the
     * presence or absence of {@code DFHCOMMAREA} (lines 67 to 69 and 82), {@code EIBAID} at line 93 -
     * its only site - and {@code OPTIONI} at lines 118 to 122. It reads no other map field, no clock
     * and no dataset.
     *
     * <p>The communication area is also where {@code CDEMO-USER-TYPE} arrives, which is the first
     * conjunct of the authorisation filter at line 136. This program never assigns that field - the
     * only assignment is the commented-out line 150 - so it is purely inbound.
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
    public record MainMenuInput(NavigationContext navigationContext, byte eibAid, String option) {

        /**
         * Requires the one component that is never absent.
         *
         * @throws NullPointerException if {@code option} is {@code null}; a {@code PIC X(2)} screen
         *                              field always holds two bytes, so pass spaces to blank it
         */
        public MainMenuInput {
            Objects.requireNonNull(option, "OPTIONI is PIC X(2) (app/cpy-bms/COMEN01.CPY:132) and a "
                    + "COBOL field is never null; pass spaces for an empty screen field. Only the "
                    + "communication area may be absent, and its absence models EIBCALEN = 0");
        }

        /**
         * An invocation with no communication area - the Java form of {@code EIBCALEN = 0} at
         * {@code app/cbl/COMEN01C.cbl:82}, which is the transaction being typed at a clear screen
         * rather than transferred to.
         *
         * @param eibAid the attention identifier; immaterial on this path, because the program
         *               diverts to the sign-on screen before it ever reaches line 93
         * @param option the {@code OPTIONI} field; likewise never inspected on this path
         * @return the input, never {@code null}
         */
        public static MainMenuInput withoutCommarea(byte eibAid, String option) {
            return new MainMenuInput(null, eibAid, option);
        }

        /**
         * Whether a communication area accompanied this invocation - the negation of
         * {@code IF EIBCALEN = 0}.
         *
         * @return {@code true} when {@link #navigationContext()} is present, which is the
         *         {@code ELSE} branch at {@code app/cbl/COMEN01C.cbl:85}
         */
        public boolean isCommareaPresent() {
            return navigationContext != null;
        }

        /**
         * Whether {@code 88 CDEMO-PGM-REENTER VALUE 1} holds - {@code app/cpy/COCOM01Y.cpy:31}.
         *
         * <p>Deliberately <strong>not</strong> the negation of an "is enter" test.
         * {@code CDEMO-PGM-CONTEXT} is {@code PIC 9(01)} and can hold any digit, so a context of, say,
         * 9 satisfies neither condition name - and {@code app/cbl/COMEN01C.cbl:87} tests
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
     * The outcome of {@code RECEIVE-MENU-SCREEN} at {@code app/cbl/COMEN01C.cbl:199-207}, including
     * the two condition codes the source captures and never looks at.
     *
     * <p>The statement is
     * {@code EXEC CICS RECEIVE MAP('COMEN1A') MAPSET('COMEN01') INTO(COMEN1AI) RESP(WS-RESP-CD)
     * RESP2(WS-REAS-CD)}, and searching the program shows neither {@code WS-RESP-CD} nor
     * {@code WS-REAS-CD} is ever tested afterwards. They are therefore carried here and
     * <strong>no branch depends on them</strong>. Inventing error handling for a receive the source
     * does not check would be adding behaviour.
     *
     * <p>This is also why the migration's file-status gate has <strong>zero call sites in this
     * package</strong>: a {@code FileStatus} outcome is asserted per repository call, and this program
     * makes none. The absence is a property of {@code COMEN01C}, not an omission here.
     *
     * @param performed  whether the paragraph ran at all. It runs on exactly one path - the
     *                   re-entry path at {@code app/cbl/COMEN01C.cbl:92} - and not on the
     *                   absent-communication-area or first-entry paths
     * @param respCode   {@code WS-RESP-CD PIC S9(09) COMP} at {@code app/cbl/COMEN01C.cbl:43},
     *                   captured and untested
     * @param reasonCode {@code WS-REAS-CD PIC S9(09) COMP} at {@code app/cbl/COMEN01C.cbl:44},
     *                   captured and untested
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
         * {@code app/cbl/COMEN01C.cbl:43-44}.
         */
        public static final ReceiveOutcome NOT_PERFORMED =
                new ReceiveOutcome(false, RESP_NORMAL, RESP2_NONE);

        /** A completed receive reporting {@code DFHRESP(NORMAL)}. */
        public static final ReceiveOutcome NORMAL =
                new ReceiveOutcome(true, RESP_NORMAL, RESP2_NONE);
    }

    /**
     * The main-menu option table as one invocation sees it: the {@code OCCURS} slots and the active
     * count, held as two separate facts.
     *
     * <p>{@code app/cpy/COMEN02Y.cpy} declares {@code CDEMO-MENU-OPT OCCURS 12 TIMES} at line 88 but
     * {@code CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10} at line 21, and the two numbers do different
     * work: {@code app/cbl/COMEN01C.cbl:128} compares the entered option against the <em>count</em>
     * and lines 137, 146, 153 and 160 subscript the <em>table</em>. Conflating them - trimming the
     * table to ten, or looping to twelve - is the defect this type exists to prevent. Slots 11 and 12
     * are present-but-unvalued and are never trimmed away.
     *
     * <p>It is a parameter rather than a hard-wired reference to the copybook for two reasons: the
     * {@value #DUMMY_PROGRAM_PREFIX} branch at line 146 is unreachable with the real table, and so is
     * the true arm of the authorisation filter at line 137, because every entry the copybook values
     * carries user type {@code 'U'}. A branch that cannot be driven cannot be shown to behave.
     * {@link #copybook()} supplies the real table for production; a stub supplies whatever a test needs
     * to reach those branches.
     *
     * @param slots       the {@code OCCURS} slots in COBOL declaration order, indexed from 0 in the
     *                    Java sense, an absent slot being one the copybook gives no {@code VALUE}
     * @param activeCount {@code CDEMO-MENU-OPT-COUNT} - how many leading slots the menu offers
     */
    public record MainMenuOptionTable(List<Optional<MenuOption>> slots, int activeCount) {

        /**
         * Copies the slot list defensively and checks the two invariants a menu table must satisfy for
         * the program's subscripting to be meaningful.
         *
         * <p>Note what is <strong>not</strong> checked: the slots beyond {@code activeCount} may be
         * absent, which is exactly the state {@code COMEN02Y} leaves slots 11 and 12 in, and the list
         * is not required to be {@value MenuOptions#TABLE_SIZE} long. A wider stub is what makes the
         * {@code WHEN OTHER CONTINUE} arm of {@code BUILD-MENU-OPTIONS} reachable.
         *
         * @throws NullPointerException     if {@code slots} is {@code null} or contains {@code null}
         * @throws IllegalArgumentException if {@code activeCount} is negative or exceeds the number of
         *                                  slots, or if any of the first {@code activeCount} slots is
         *                                  absent
         */
        public MainMenuOptionTable {
            Objects.requireNonNull(slots, "An OCCURS slot list is required; COMEN02Y.cpy declares "
                    + MenuOptions.TABLE_SIZE + " slots");
            slots = List.copyOf(slots);
            if (activeCount < 0 || activeCount > slots.size()) {
                throw new IllegalArgumentException("CDEMO-MENU-OPT-COUNT is " + activeCount
                        + ", which does not address a table of " + slots.size() + " slot(s). "
                        + "app/cbl/COMEN01C.cbl:128 compares the entered option against the count and "
                        + ":146 then subscripts the table with it, so a count beyond the table would "
                        + "admit an option the table cannot resolve.");
            }
            for (int subscript = 1; subscript <= activeCount; subscript++) {
                if (slots.get(subscript - 1).isEmpty()) {
                    throw new IllegalArgumentException("Slot " + subscript + " of "
                            + slots.size() + " is absent, yet CDEMO-MENU-OPT-COUNT is " + activeCount
                            + " and so offers it. app/cbl/COMEN01C.cbl:146 would subscript an "
                            + "unvalued entry: every slot up to the active count must carry a value.");
                }
            }
        }

        /**
         * The real table of {@code app/cpy/COMEN02Y.cpy}: {@value MenuOptions#TABLE_SIZE} slots of
         * which the first {@value MenuOptions#ACTIVE_OPTION_COUNT} carry values, with the active
         * count the copybook declares.
         *
         * @return the copybook table, never {@code null}
         */
        public static MainMenuOptionTable copybook() {
            return new MainMenuOptionTable(MenuOptions.options(), MenuOptions.ACTIVE_OPTION_COUNT);
        }

        /**
         * The entry a 1-based COBOL subscript addresses - the Java form of
         * {@code CDEMO-MENU-OPT(cobolSubscript)}, for a subscript a guard has already validated.
         *
         * <p>The conversion from the 1-based COBOL subscript to the 0-based Java index is written
         * once, here, and never inline at a call site: an off-by-one on an {@code OCCURS} table is the
         * single largest defect risk in this migration.
         *
         * <p>This accessor <strong>throws</strong> for a subscript outside the table, which is correct
         * for the dispatch path at lines 146 to 160 because the guard at lines 127 to 134 has already
         * rejected 0 and anything above the active count. It is deliberately <em>not</em> what the
         * authorisation filter at line 137 uses - see {@link #optionWithinTable(int)}.
         *
         * @param cobolSubscript the COBOL subscript, from 1 to the number of slots inclusive
         * @return the addressed entry, or empty for a slot carrying no value
         * @throws IndexOutOfBoundsException if {@code cobolSubscript} is below 1 or above
         *                                   {@link #slots()}{@code .size()}
         */
        public Optional<MenuOption> optionBySubscript(int cobolSubscript) {
            if (cobolSubscript < 1 || cobolSubscript > slots.size()) {
                throw new IndexOutOfBoundsException("COBOL subscript " + cobolSubscript
                        + " is outside 1.." + slots.size() + "; COMEN02Y.cpy declares "
                        + "CDEMO-MENU-OPT OCCURS " + slots.size() + " TIMES and COBOL has no "
                        + "subscript 0");
            }
            return slots.get(cobolSubscript - 1);
        }

        /**
         * The entry a 1-based COBOL subscript addresses, <strong>bounded to the table's declared
         * {@code OCCURS} range and never throwing</strong> - the accessor the authorisation filter at
         * {@code app/cbl/COMEN01C.cbl:137} needs.
         *
         * <p>Why a second accessor exists at all is the hardest parity point in this package. The
         * filter is <em>not</em> guarded by {@code IF NOT ERR-FLG-ON}, so it evaluates
         * {@code CDEMO-MENU-OPT-USRTYPE(WS-OPTION)} even after the validation at lines 127 to 134 has
         * already rejected the option. {@code WS-OPTION} can therefore be 0, or 11, or 99, at the
         * moment the table is subscripted. COBOL compiled without {@code SSRANGE} does not range-check
         * a subscript: it computes an offset and reads whatever bytes are there. Java would throw
         * {@link IndexOutOfBoundsException} - a crash the COBOL does not produce, and therefore itself
         * a parity violation.
         *
         * <p>The resolution is to keep the evaluation order and the ungated structure exactly, and to
         * bound the read: a subscript outside 1 to {@link #slots()}{@code .size()} yields empty, which
         * the filter reads as "not {@value #ADMIN_ONLY_USRTYPE}" and so evaluates false. That is
         * observationally identical to the source in every reachable case, because all
         * {@value MenuOptions#ACTIVE_OPTION_COUNT} entries {@code COMEN02Y} values carry user type
         * {@code 'U'}, slots 11 and 12 carry no value at all, and a subscript of 0 is below the table -
         * so line 137's {@code = 'A'} comparison is false for every subscript the program can actually
         * arrive with. For the copybook table the bound is exactly the {@code OCCURS 12 TIMES} of
         * {@code app/cpy/COMEN02Y.cpy:88}; for a stub it is that stub's own declared length.
         *
         * @param cobolSubscript any integer, including one no COBOL table could address
         * @return the addressed entry, or empty when the subscript lies outside the declared
         *         {@code OCCURS} range or the slot carries no value
         */
        public Optional<MenuOption> optionWithinTable(int cobolSubscript) {
            if (cobolSubscript < 1 || cobolSubscript > slots.size()) {
                return Optional.empty();
            }
            return slots.get(cobolSubscript - 1);
        }
    }

    /**
     * The four-step normalisation of {@code app/cbl/COMEN01C.cbl:117-125}, with every intermediate
     * preserved so each step can be asserted on its own.
     *
     * <p>Exposing the intermediates is the point. The third step is a receiver <em>attribute</em>
     * declared seventy-two lines away from the statement it governs, and a reader who checks only the
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
                        + ") JUST RIGHT (app/cbl/COMEN01C.cbl:45) and WS-OPTION is PIC 9(0"
                        + OPTION_DIGITS + ") (app/cbl/COMEN01C.cbl:46), but an image is "
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
         * The value line 125 moves into {@code OPTIONO OF COMEN1AO}, echoing the normalised option
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
     * Everything one invocation of {@code COMEN01C} produces.
     *
     * <p>Read it as the state of the program at the moment it leaves: either at
     * {@code EXEC CICS RETURN} (lines 107 to 110) having painted a screen, or at one of the two
     * {@code EXEC CICS XCTL} statements having named a successor. {@link #hasNextProgram()}
     * distinguishes the two, and exactly one of {@link #screenPainted()} and
     * {@link #hasNextProgram()} is ever true.
     *
     * <p>One invocation can execute {@code PERFORM SEND-MENU-SCREEN} more than once - the validation
     * at line 133 and the authorisation filter at line 142 are consecutive, ungated {@code IF}
     * statements and both can fire in the same pass. A {@code PERFORM} returns, so the program really
     * does paint twice, and the terminal shows the second. This record therefore carries the
     * <strong>final</strong> state, which is the one the operator sees.
     *
     * @param optionLines                the twelve {@code OPTN001O} to {@code OPTN012O} lines in map
     *                                   order, index 0 being {@code OPTN001O}, each
     *                                   {@value #OPTION_TEXT_LENGTH} characters. All twelve are always
     *                                   present; those the program does not write are spaces
     * @param message                    {@code WS-MESSAGE}, exactly {@value #MESSAGE_LENGTH}
     *                                   characters. The controller truncates it on the right to the
     *                                   seventy-eight of {@code ERRMSGO}
     * @param messageColour              {@code ERRMSGC OF COMEN1AO}: {@link #MAP_MESSAGE_COLOUR} unless
     *                                   line 158 overrode it with {@link #COMING_SOON_MESSAGE_COLOUR}
     * @param errorFlag                  whether {@code 88 ERR-FLG-ON} holds at the moment the program
     *                                   leaves
     * @param option                     {@code OPTIONO OF COMEN1AO}, {@value #OPTION_LENGTH}
     *                                   characters
     * @param nextProgram                the {@code EXEC CICS XCTL PROGRAM(...)} target at its declared
     *                                   {@value NavigationContext#TO_PROGRAM_LENGTH} characters, or
     *                                   spaces when the program returned to CICS instead of
     *                                   transferring
     * @param nextProgramCarriesCommarea whether that transfer passed the communication area.
     *                                   <strong>The two transfers differ</strong>: lines 152 to 155
     *                                   specify {@code COMMAREA(CARDDEMO-COMMAREA)} and lines 175 to
     *                                   177 specify none
     * @param screenPainted              whether {@code SEND-MENU-SCREEN} ran, that is whether
     *                                   {@code EXEC CICS SEND MAP} at lines 189 to 194 executed
     * @param resetAllOutputFields       whether {@code MOVE LOW-VALUES TO COMEN1AO} at line 89 ran
     *                                   first, clearing every output field before the paint. True on
     *                                   the first-entry path only
     * @param navigationContext          {@code CARDDEMO-COMMAREA} as it stands when the program
     *                                   leaves - the area line 109 hands back so the client can
     *                                   re-supply it
     * @param transactionId              {@code TRANSID} on line 108, {@value #TRANSACTION_ID}
     * @param mapsetName                 {@code MAPSET('COMEN01')}
     * @param mapName                    {@code MAP('COMEN1A')}
     * @param receive                    the outcome of {@code RECEIVE-MENU-SCREEN}, including the two
     *                                   condition codes the source never tests
     */
    public record MainMenuOutcome(List<String> optionLines,
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
        public MainMenuOutcome {
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
                        + " option lines, OPTN001O to OPTN012O (app/cpy-bms/COMEN01.CPY:182-248), but "
                        + optionLines.size() + " were supplied. All twelve are always carried; the ones "
                        + "this program does not write are spaces.");
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
                        + ") (app/cbl/COMEN01C.cbl:38) but the image is " + message.length()
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
         *         {@link #COMING_SOON_MESSAGE_COLOUR}, which line 158 is the sole source of
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
         * {@code app/cbl/COMEN01C.cbl:248-275} addresses them: subscript 1 is {@code OPTN001O}.
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
    // Section 7 - MAIN-PARA, app/cbl/COMEN01C.cbl:75-110.
    // =================================================================================================

    /**
     * Runs {@code COMEN01C} against the copybook option table - the entry point for production.
     *
     * @param input the invocation
     * @return the outcome
     * @throws NullPointerException if {@code input} is {@code null}
     */
    public MainMenuOutcome handle(MainMenuInput input) {
        return handle(input, MainMenuOptionTable.copybook());
    }

    /**
     * Runs {@code COMEN01C} against a stated option table, reproducing {@code MAIN-PARA} at
     * {@code app/cbl/COMEN01C.cbl:75-110} statement for statement and in source order.
     *
     * <p>The COBOL, with the line numbers this method's comments cite:
     * <pre>
     *   L77   SET ERR-FLG-OFF TO TRUE
     *   L79   MOVE SPACES TO WS-MESSAGE
     *   L80                 ERRMSGO OF COMEN1AO
     *   L82   IF EIBCALEN = 0
     *   L83       MOVE 'COSGN00C' TO CDEMO-FROM-PROGRAM
     *   L84       PERFORM RETURN-TO-SIGNON-SCREEN
     *   L85   ELSE
     *   L86       MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA
     *   L87       IF NOT CDEMO-PGM-REENTER
     *   L88           SET CDEMO-PGM-REENTER TO TRUE
     *   L89           MOVE LOW-VALUES       TO COMEN1AO
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
     *       9 takes the first-entry path. See {@link MainMenuInput#isReenter()}.</li>
     * </ul>
     *
     * <p>On the first-entry path <strong>no option is read, no validation runs and the authorisation
     * filter is never reached</strong>: the program paints the screen and returns.
     *
     * @param input       the invocation
     * @param optionTable the main-menu option table to offer and subscript
     * @return the outcome
     * @throws NullPointerException if either argument is {@code null}
     */
    public MainMenuOutcome handle(MainMenuInput input, MainMenuOptionTable optionTable) {
        Objects.requireNonNull(input, "An invocation is required");
        Objects.requireNonNull(optionTable, "An option table is required; "
                + "MainMenuOptionTable.copybook() supplies the one app/cpy/COMEN02Y.cpy declares");

        // L77 SET ERR-FLG-OFF TO TRUE. WS-ERR-FLG is method-local, never a field: a singleton bean
        // holding per-request working storage would break request isolation.
        boolean errorFlag = false;

        // L79-L80 MOVE SPACES TO WS-MESSAGE and to ERRMSGO OF COMEN1AO. Both receivers start blank.
        String wsMessage = spaces(MESSAGE_LENGTH);

        // L82 IF EIBCALEN = 0 - no communication area at all.
        if (!input.isCommareaPresent()) {
            // L83 MOVE 'COSGN00C' TO CDEMO-FROM-PROGRAM. FROM, not TO.
            //
            // The area itself is WORKING-STORAGE brought in by COPY COCOM01Y at line 50 and, having no
            // VALUE clause, holds binary zeros when the transaction is entered cold. NavigationContext
            // .empty() presents it as spaces and zeros instead, which reaches the same place: the
            // combined relation at line 172 tests LOW-VALUES *or* SPACES, so the source satisfies its
            // first half and this satisfies its second, and both default CDEMO-TO-PROGRAM to
            // 'COSGN00C'. Both halves are exercised independently through resolveSignonTarget.
            //
            // Note also that a cold area carries no CDEMO-USER-TYPE, so nothing on this path could
            // consult the authorisation filter even if it reached it - which it does not.
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
            // L89 MOVE LOW-VALUES TO COMEN1AO clears every output field before the paint; L90 sends.
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
        // single token PFK03, and COMEN01C does not copy CSSTRPFY - it tests EIBAID inline. Choosing
        // the arm by resolved token would therefore make PF15 behave as PF3 in a program that has no
        // such behaviour. Comparing the byte through PfKeyResolver.isAid keeps the two identical for
        // DFHENTER and DFHPF3 and nothing more, and keeps every other byte - resolvable or not - on
        // WHEN OTHER, exactly as the inline EVALUATE does.
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
    // Section 8 - option normalisation, app/cbl/COMEN01C.cbl:117-125.
    // =================================================================================================

    /**
     * Normalises the received {@code OPTIONI} value exactly as
     * {@code app/cbl/COMEN01C.cbl:117-125} does, in four steps.
     *
     * <p>Public because each step must be assertable on its own; the third is the one an
     * implementation gets wrong silently.
     *
     * <ol>
     *   <li><strong>Trailing-space scan, lines 117 to 121.</strong>
     *       <pre>
     *   PERFORM VARYING WS-IDX
     *           FROM LENGTH OF OPTIONI OF COMEN1AI BY -1 UNTIL
     *           OPTIONI OF COMEN1AI(WS-IDX:1) NOT = SPACES OR
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
     *       {@code MOVE OPTIONI OF COMEN1AI(1:WS-IDX) TO WS-OPTION-X} sends the <em>leading</em>
     *       {@code WS-IDX} bytes.</li>
     *   <li><strong>The {@code JUSTIFIED RIGHT} receiver, line 45.</strong> {@code WS-OPTION-X} is
     *       declared {@code PIC X(02) JUST RIGHT}, so that {@code MOVE} aligns the sender at the
     *       receiver's rightmost position and space-fills to the left. This is why a one-byte
     *       {@code "3"} becomes {@code " 3"} and not {@code "3 "}. The map agrees:
     *       {@code OPTION DFHMDF ... JUSTIFY=(RIGHT,ZERO) LENGTH=2} at
     *       {@code app/bms/COMEN01.bms:145-149}.</li>
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
     * <p>The last row is the one that matters for the two guards that follow.
     * {@code MOVE WS-OPTION-X TO WS-OPTION} sends alphanumeric to numeric {@code DISPLAY}, and at equal
     * widths the two characters cross unchanged - so {@code WS-OPTION} really does come to hold
     * {@code "1x"}, which is precisely what makes {@code WS-OPTION IS NOT NUMERIC} at line 127 a test
     * with something to detect. This method therefore reports a non-numeric field as
     * {@link OptionalInt#empty()} rather than throwing: an exception would replace a branch the program
     * has with one it does not. It is also the state that leaves the authorisation filter at line 137
     * with no usable subscript at all - see
     * {@link #isAdminOnlyOption(MainMenuOptionTable, OptionalInt)}.
     *
     * @param receivedOption the {@code OPTIONI} value as received; moved into its declared
     *                       {@value #OPTION_LENGTH}-character width first
     * @return every intermediate of the normalisation
     * @throws NullPointerException if {@code receivedOption} is {@code null}
     */
    public OptionNormalisation normaliseOption(String receivedOption) {
        Objects.requireNonNull(receivedOption, "OPTIONI is PIC X(" + OPTION_LENGTH + ") and is never "
                + "null; pass spaces for an empty screen field");

        // OPTIONI is PIC X(2) (app/cpy-bms/COMEN01.CPY:132). CICS delivers the received value into a
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

    // =================================================================================================
    // Section 9 - PROCESS-ENTER-KEY, app/cbl/COMEN01C.cbl:115-165.
    //
    // THREE consecutive IF statements, in this order and with no ELSE between any of them:
    //
    //   L127-L134  validation      - guarded by nothing
    //   L136-L143  the filter      - guarded by nothing        <-- unique to COMEN01C
    //   L145-L165  the dispatch    - guarded by IF NOT ERR-FLG-ON
    //
    // Only the third carries a guard. That asymmetry is the whole of section 9's difficulty and it is
    // preserved exactly: evaluation order and fall-through outcomes are held invariant.
    // =================================================================================================

    /**
     * Whether the entered option is restricted to administrators - the second conjunct of
     * {@code app/cbl/COMEN01C.cbl:137},
     * {@code CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'}.
     *
     * <p>Public and separate from {@link #processEnterKey} because it is the one decision in this
     * package with no sibling counterpart, and because it must be assertable for subscripts the menu
     * can never legitimately produce. Its contract is that it <strong>never throws</strong>, for any
     * integer and for the absent case alike:
     * <ul>
     *   <li><strong>{@link OptionalInt#empty()}</strong> - {@code WS-OPTION} does not hold digits, so
     *       there is no COBOL subscript at all. False.</li>
     *   <li><strong>0</strong>, or anything above the table's declared {@code OCCURS} length - outside
     *       the table. False, via {@link MainMenuOptionTable#optionWithinTable(int)}.</li>
     *   <li><strong>11 or 12 against the copybook table</strong> - inside the {@code OCCURS 12} range
     *       but carrying no value. False.</li>
     *   <li><strong>1 to 10 against the copybook table</strong> - present, and every one of them
     *       carries {@code 'U'}. False.</li>
     * </ul>
     * So with the real table this predicate is false for every subscript the program can arrive with,
     * which is why the true arm is driven with a stub. That total falsity is also what makes the bounded
     * read observationally identical to the unchecked COBOL subscript it stands in for: the source may
     * read stray bytes, but no byte it could read makes line 137 true on a path this program reaches.
     *
     * @param optionTable the table whose authorisation column is consulted
     * @param wsOption    {@code WS-OPTION} as {@link #normaliseOption(String)} left it
     * @return {@code true} only when the subscript addresses a valued entry whose
     *         {@code CDEMO-MENU-OPT-USRTYPE} is exactly {@value #ADMIN_ONLY_USRTYPE}
     * @throws NullPointerException if either argument is {@code null}
     */
    public boolean isAdminOnlyOption(MainMenuOptionTable optionTable, OptionalInt wsOption) {
        Objects.requireNonNull(optionTable, "An option table is required to read "
                + "CDEMO-MENU-OPT-USRTYPE");
        Objects.requireNonNull(wsOption, "An OptionalInt is required; OptionalInt.empty() is the "
                + "non-numeric WS-OPTION that line 137 would subscript with no usable value");

        if (wsOption.isEmpty()) {
            return false;
        }
        return optionTable.optionWithinTable(wsOption.getAsInt())
                .map(entry -> ADMIN_ONLY_USRTYPE.equals(entry.menuOptUsrType()))
                .orElse(false);
    }

    /**
     * {@code PROCESS-ENTER-KEY}, {@code app/cbl/COMEN01C.cbl:115-165}: normalise, validate, filter,
     * then dispatch.
     *
     * <p>The three {@code IF} statements, transcribed with their guards:
     * <pre>
     *   L127  IF WS-OPTION IS NOT NUMERIC OR
     *   L128     WS-OPTION &gt; CDEMO-MENU-OPT-COUNT OR
     *   L129     WS-OPTION = ZEROS
     *   L130      MOVE 'Y' TO WS-ERR-FLG
     *   L131      MOVE 'Please enter a valid option number...' TO WS-MESSAGE
     *   L133      PERFORM SEND-MENU-SCREEN
     *   L134  END-IF
     *
     *   L136  IF CDEMO-USRTYP-USER AND
     *   L137     CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
     *   L138      SET ERR-FLG-ON TO TRUE
     *   L139      MOVE SPACES    TO WS-MESSAGE
     *   L140      MOVE 'No access - Admin Only option... ' TO WS-MESSAGE
     *   L142      PERFORM SEND-MENU-SCREEN
     *   L143  END-IF
     *
     *   L145  IF NOT ERR-FLG-ON
     *   L146      IF CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'
     *   L147          MOVE WS-TRANID  TO CDEMO-FROM-TRANID
     *   L148          MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM
     *   L149  *       MOVE WS-USER-ID   TO CDEMO-USER-ID
     *   L150  *       MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
     *   L151          MOVE ZEROS      TO CDEMO-PGM-CONTEXT
     *   L152          EXEC CICS XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION))
     *   L154                         COMMAREA(CARDDEMO-COMMAREA)
     *   L156      END-IF
     *   L157      MOVE SPACES   TO WS-MESSAGE
     *   L158      MOVE DFHGREEN TO ERRMSGC OF COMEN1AO
     *   L159      STRING 'This option '                 DELIMITED BY SIZE
     *   L160             CDEMO-MENU-OPT-NAME(WS-OPTION)  DELIMITED BY SPACE
     *   L162             'is coming soon ...'            DELIMITED BY SIZE
     *   L163        INTO WS-MESSAGE
     *   L164      PERFORM SEND-MENU-SCREEN
     *   L165  END-IF.
     * </pre>
     *
     * <p><strong>The middle {@code IF} is not guarded, and that is the point.</strong> Unlike the
     * dispatch, the authorisation filter at lines 136 to 143 runs whether or not the validation above
     * it already set the error flag. {@code PERFORM SEND-MENU-SCREEN} on line 133 <em>returns</em> - it
     * is a {@code PERFORM}, not a transfer - so the program really does fall through to line 136, and
     * from there to line 145 whose {@code IF NOT ERR-FLG-ON} is by then false. Two consequences follow
     * and both are reproduced:
     * <ul>
     *   <li>The filter can subscript the option table with a value the validation has already
     *       rejected - 0, or 11, or 99. That is why the read goes through
     *       {@link #isAdminOnlyOption(MainMenuOptionTable, OptionalInt)}, which is bounded and cannot
     *       throw. COBOL also gives no guarantee that {@code AND} short-circuits, so both conjuncts are
     *       evaluated into locals below rather than relying on Java's {@code &&} to skip the second;
     *       the second is safe on its own regardless.</li>
     *   <li>Both {@code IF}s can fire in one pass, painting the screen twice. The returned outcome is
     *       the last paint, which is what the terminal shows. Reachable with a table whose active count
     *       is below an entry that carries {@code 'A'}.</li>
     * </ul>
     * The screens painted at lines 133 and 142 are therefore held in a local and returned at the end,
     * rather than returned from inside their {@code IF}: returning early would make the later guards
     * structurally unreachable, which is the same thing as merging {@code IF} statements the source
     * keeps apart.
     *
     * @param input       the invocation, for the received {@code OPTIONI} value
     * @param context     {@code CARDDEMO-COMMAREA} on entry to the paragraph, whose
     *                    {@code CDEMO-USER-TYPE} is the filter's first conjunct
     * @param optionTable the option table to validate against and subscript
     * @param receive     the outcome of the {@code RECEIVE MAP} that preceded this paragraph
     * @return the outcome
     */
    private MainMenuOutcome processEnterKey(MainMenuInput input,
            NavigationContext context,
            MainMenuOptionTable optionTable,
            ReceiveOutcome receive) {

        OptionNormalisation normalisation = normaliseOption(input.option());
        // L125 MOVE WS-OPTION TO OPTIONO OF COMEN1AO.
        String optionEcho = normalisation.optionEcho();

        // WS-ERR-FLG and WS-MESSAGE, both method-local (never fields).
        boolean errorFlag = false;
        String wsMessage = spaces(MESSAGE_LENGTH);
        MainMenuOutcome painted = null;

        // L127-L129: three terms joined by OR, in source order. Java's || short-circuits, which is what
        // makes the second and third terms well defined: they read the numeric value, and the value is
        // only meaningful once the first term has established that the field holds digits. IBM COBOL
        // evaluates the terms left to right, so the outcome is identical either way - the first term
        // being true is already sufficient for the condition.
        if (!normalisation.isNumeric()                                          // L127
                || normalisation.option().getAsInt() > optionTable.activeCount()  // L128
                || normalisation.option().getAsInt() == ZERO_OPTION) {            // L129
            // L130 MOVE 'Y' TO WS-ERR-FLG.
            errorFlag = true;
            // L131-L132: a 37-character literal into WS-MESSAGE PIC X(80), left-justified and
            // right-space-padded to eighty.
            wsMessage = codec.movePicX(INVALID_OPTION_MESSAGE, MESSAGE_LENGTH);
            // L133 PERFORM SEND-MENU-SCREEN - which RETURNS, so control reaches L136 next.
            painted = sendMenuScreen(context,
                    errorFlag,
                    wsMessage,
                    MAP_MESSAGE_COLOUR,
                    optionEcho,
                    false,
                    optionTable,
                    receive);
        }

        // L136-L143 THE USER-TYPE AUTHORISATION FILTER - reached unconditionally, including when the
        // validation above has already failed. Not hoisted into that IF's ELSE, and not moved after the
        // dispatch: its position between the two is part of the program's observable behaviour.
        //
        // Both conjuncts are evaluated into locals because COBOL does not promise to short-circuit AND.
        // L136 - the first conjunct: 88 CDEMO-USRTYP-USER VALUE 'U' (app/cpy/COCOM01Y.cpy:28). It is
        // NOT the negation of CDEMO-USRTYP-ADMIN: a blank user type, which is what a COMMAREA carries
        // before sign-on, satisfies neither, and NavigationContext.isUser() reflects that.
        boolean usrTypeIsUser = context.isUser();
        // L137 - the second conjunct, bounded so an out-of-range subscript cannot throw where COBOL
        // would merely read stray bytes.
        boolean optionIsAdminOnly = isAdminOnlyOption(optionTable, normalisation.option());
        if (usrTypeIsUser && optionIsAdminOnly) {
            // L138 SET ERR-FLG-ON TO TRUE. Note SET ... TO TRUE here where line 130 used MOVE 'Y':
            // two spellings of the same one-byte store, and both land on WS-ERR-FLG.
            errorFlag = true;
            // L139 MOVE SPACES TO WS-MESSAGE. Kept as its own statement even though line 140 overlays
            // the whole field immediately afterwards - the source performs both, and preserving the
            // sequence is what makes this method readable against the listing.
            wsMessage = spaces(MESSAGE_LENGTH);
            // L140-L141: the 33-character literal, including its trailing space, into WS-MESSAGE
            // PIC X(80) - left-justified and right-space-padded to eighty.
            wsMessage = codec.movePicX(NO_ACCESS_MESSAGE, MESSAGE_LENGTH);
            // L142 PERFORM SEND-MENU-SCREEN. This overwrites any screen line 133 painted, which is
            // exactly what a second SEND MAP does to the terminal.
            painted = sendMenuScreen(context,
                    errorFlag,
                    wsMessage,
                    MAP_MESSAGE_COLOUR,
                    optionEcho,
                    false,
                    optionTable,
                    receive);
        }

        // L145 IF NOT ERR-FLG-ON - the only one of the three IFs that is guarded.
        if (!errorFlag) {
            int wsOption = normalisation.option().getAsInt();
            // CDEMO-MENU-OPT-PGMNAME(WS-OPTION) - a 1-based subscript. The validation above has already
            // rejected 0 and anything above the active count, and MainMenuOptionTable guarantees every
            // slot up to that count carries a value, so the entry is always present here. This is the
            // throwing accessor deliberately: on this path an absent entry would be a broken invariant
            // rather than a subscript the program legitimately arrived with.
            MenuOption entry = optionTable.optionBySubscript(wsOption).orElseThrow();
            // The eight-byte name, untrimmed: line 146 reference-modifies its first five characters.
            String targetProgram = entry.menuOptPgmName();

            // L146 IF CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'.
            if (!DUMMY_PROGRAM_PREFIX.equals(targetProgram.substring(0, DUMMY_PREFIX_LENGTH))) {
                NavigationContext transferring = context
                        .withFromTranid(TRANSACTION_ID)   // L147 MOVE WS-TRANID  TO CDEMO-FROM-TRANID
                        .withFromProgram(PROGRAM_NAME)    // L148 MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM
                        // L149 * MOVE WS-USER-ID   TO CDEMO-USER-ID     <- COMMENTED OUT IN THE SOURCE
                        // L150 * MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE   <- COMMENTED OUT IN THE SOURCE
                        //
                        // Both are preserved as comments and NEITHER is implemented. They are not an
                        // oversight to be completed: WS-USER-ID is not declared anywhere in this
                        // program's WORKING-STORAGE, so line 149 could not even compile if uncommented,
                        // and line 150 is the only mention of any SEC-USER-DATA field in the whole
                        // program. Together they are the proof that CDEMO-USER-TYPE is inbound-only
                        // here - which is precisely why the filter above reads it and never writes it.
                        .withPgmEnter();                  // L151 MOVE ZEROS     TO CDEMO-PGM-CONTEXT

                // L152-L155 EXEC CICS XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION))
                //                          COMMAREA(CARDDEMO-COMMAREA).
                //
                // RETURNING HERE IS THE WHOLE POINT. In CICS an XCTL never comes back: control leaves
                // COMEN01C permanently and the statements after END-IF on line 156 - MOVE SPACES TO
                // WS-MESSAGE, MOVE DFHGREEN TO ERRMSGC, the STRING, and PERFORM SEND-MENU-SCREEN - are
                // reachable ONLY when line 146 found 'DUMMY' and skipped the transfer. Translating XCTL
                // into a response field removes that natural termination, so the return must be
                // explicit. Falling through would attach a 'This option ...is coming soon ...' message
                // to a successful transfer, which COMEN01C never emits - a silent parity failure.
                return transferToProgram(targetProgram,
                        transferring,
                        true,
                        wsMessage,
                        optionEcho,
                        receive);
            }

            // Reached only on the 'DUMMY' path (line 156 END-IF).
            // L157 MOVE SPACES TO WS-MESSAGE, then L159-L163's STRING overlays the composed text at the
            // start of the field and leaves the remainder as the spaces line 157 put there.
            //
            // The middle operand is DELIMITED BY SPACE, not BY SIZE: only the characters up to the
            // first space of the 35-byte name are sent. And 'is coming soon ...' has no leading space.
            // So option 1 emits "This option Accountis coming soon ..." - one word of the name and no
            // separator before "is". That is what the program does; see COMING_SOON_SUFFIX.
            wsMessage = codec.padToDeclaredWidth(
                    codec.concatenateDelimitedBySize(COMING_SOON_PREFIX,
                            stringDelimitedBySpace(entry.menuOptName()),
                            COMING_SOON_SUFFIX),
                    MESSAGE_LENGTH);
            // L158 MOVE DFHGREEN TO ERRMSGC OF COMEN1AO - overriding the map's COLOR=RED.
            // L164 PERFORM SEND-MENU-SCREEN.
            painted = sendMenuScreen(context,
                    errorFlag,
                    wsMessage,
                    COMING_SOON_MESSAGE_COLOUR,
                    optionEcho,
                    false,
                    optionTable,
                    receive);
        }

        // Back in MAIN-PARA at L107-L110, EXEC CICS RETURN. Exactly one of the three IF statements
        // above painted last: the third runs precisely when neither of the first two set the flag, and
        // if either of them did, at least one of them painted. So `painted` is always set here.
        return Objects.requireNonNull(painted, "PROCESS-ENTER-KEY reached its end without painting a "
                + "screen. app/cbl/COMEN01C.cbl:127-165 is three IF statements whose union is total - "
                + "the third is guarded by NOT ERR-FLG-ON and the flag can only have been set by one "
                + "of the first two, each of which paints - so this cannot occur and indicates the "
                + "guard structure has been altered.");
    }

    // =================================================================================================
    // Section 10 - RETURN-TO-SIGNON-SCREEN, app/cbl/COMEN01C.cbl:170-177.
    // =================================================================================================

    /**
     * {@code RETURN-TO-SIGNON-SCREEN}, {@code app/cbl/COMEN01C.cbl:170-177}.
     *
     * <pre>
     *   L172  IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES
     *   L173      MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
     *   L174  END-IF
     *   L175  EXEC CICS
     *   L176      XCTL PROGRAM(CDEMO-TO-PROGRAM)
     *   L177  END-EXEC.
     * </pre>
     *
     * <p>Line 172 is an <strong>abbreviated combined relation</strong>: COBOL reads
     * {@code A = X OR Y} as {@code A = X OR A = Y}, so it means "equal to {@code LOW-VALUES}, or equal
     * to {@code SPACES}" and not "equal to the result of some expression". Both halves are implemented
     * and both are independently testable - a field of binary zeros satisfies the first, a field of
     * spaces the second, and a field that is partly one and partly the other satisfies neither.
     *
     * <p>The {@code XCTL} on lines 175 to 177 specifies <strong>no {@code COMMAREA}</strong>. That is
     * the difference from the option transfer at lines 152 to 155, which does specify one, and it is
     * recorded on the outcome as {@link MainMenuOutcome#nextProgramCarriesCommarea()} rather than being
     * smoothed over: the sign-on program is entered with no communication area, which is exactly the
     * condition its own {@code EIBCALEN = 0} test is looking for.
     *
     * @param context   {@code CARDDEMO-COMMAREA} as the caller left it, its {@code CDEMO-TO-PROGRAM}
     *                  being what line 172 tests
     * @param input     the invocation, for the {@code OPTIONO} value the map already carries
     * @param wsMessage {@code WS-MESSAGE}, still the spaces line 79 left in it on every path that
     *                  reaches here
     * @param receive   the outcome of the {@code RECEIVE MAP}, or
     *                  {@link ReceiveOutcome#NOT_PERFORMED}
     * @return the outcome, naming the sign-on program as the transfer target
     */
    private MainMenuOutcome returnToSignonScreen(NavigationContext context,
            MainMenuInput input,
            String wsMessage,
            ReceiveOutcome receive) {

        // L172-L174, in resolveSignonTarget so both halves of the combined relation are assertable.
        String toProgram = resolveSignonTarget(context.toProgram());

        // L175-L177 XCTL PROGRAM(CDEMO-TO-PROGRAM), with no COMMAREA. Returns immediately for the same
        // reason the option transfer does: an XCTL never comes back.
        return transferToProgram(toProgram,
                context.withToProgram(toProgram),
                false,
                wsMessage,
                receivedOptionImage(input),
                receive);
    }

    /**
     * The default of {@code app/cbl/COMEN01C.cbl:172-174}: supply {@code 'COSGN00C'} when
     * {@code CDEMO-TO-PROGRAM} names nothing.
     *
     * <p>Public because the combined relation has two independent halves and each must be assertable on
     * its own. Reaching them both through {@link #handle(MainMenuInput, MainMenuOptionTable)} is not
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
        // L172 IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES - an abbreviated combined relation, so the
        // subject is repeated against each of the two figurative constants.
        if (isAllOf(image, LOW_VALUE) || isAllOf(image, SPACE)) {
            // L173 MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM.
            return codec.movePicX(SIGNON_PROGRAM, NavigationContext.TO_PROGRAM_LENGTH);
        }
        return image;
    }

    // =================================================================================================
    // Section 11 - SEND-MENU-SCREEN, BUILD-MENU-OPTIONS and RECEIVE-MENU-SCREEN,
    //             app/cbl/COMEN01C.cbl:182-194, :236-277 and :199-207.
    // =================================================================================================

    /**
     * Composes the twelve {@code OPTN00nO} lines of {@code BUILD-MENU-OPTIONS},
     * {@code app/cbl/COMEN01C.cbl:236-277}.
     *
     * <pre>
     *   L238  PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL
     *   L239                  WS-IDX &gt; CDEMO-MENU-OPT-COUNT
     *   L241      MOVE SPACES TO WS-MENU-OPT-TXT
     *   L243      STRING CDEMO-MENU-OPT-NUM(WS-IDX)  DELIMITED BY SIZE
     *   L244             '. '                        DELIMITED BY SIZE
     *   L245             CDEMO-MENU-OPT-NAME(WS-IDX) DELIMITED BY SIZE
     *   L246        INTO WS-MENU-OPT-TXT
     *   L248      EVALUATE WS-IDX
     *   L249          WHEN 1  MOVE WS-MENU-OPT-TXT TO OPTN001O
     *   ...
     *   L271          WHEN 12 MOVE WS-MENU-OPT-TXT TO OPTN012O
     *   L273          WHEN OTHER CONTINUE
     *   L275      END-EVALUATE
     *   L277  END-PERFORM.
     * </pre>
     *
     * <p>Five things about that loop are load-bearing:
     * <ul>
     *   <li><strong>It is 1-based and inclusive of the count.</strong> {@code FROM 1 BY 1 UNTIL
     *       WS-IDX &gt; count} runs for 1 through 10 when the count is
     *       {@value MenuOptions#ACTIVE_OPTION_COUNT}. Java indices are 0-based, and the shift is
     *       applied in exactly one place here - the list write - with the COBOL subscript kept as the
     *       loop variable so the source and this method count the same way.</li>
     *   <li><strong>The bound is the active count, not the table size.</strong> The table is
     *       {@code OCCURS 12} and the count is 10, so slots 11 and 12 are never visited and their lines
     *       stay spaces. Neither number is substituted for the other, and the table is never
     *       trimmed.</li>
     *   <li><strong>The option number keeps its leading zero.</strong> {@code CDEMO-MENU-OPT-NUM} is
     *       {@code PIC 9(02)} and {@code STRING ... DELIMITED BY SIZE} moves the field's whole stored
     *       image, so line 1 reads {@code 01. } and not {@code 1. }, and line 10 reads {@code 10. }.
     *       The number therefore comes from {@link MenuOption#menuOptNumImage()} and never from the
     *       {@code int}.</li>
     *   <li><strong>The name here is {@code DELIMITED BY SIZE}, unlike line 161.</strong> All
     *       {@value MenuOptions#OPT_NAME_LENGTH} bytes of the name are sent, padding included - which
     *       is why a menu line ends in spaces where the "coming soon" message keeps only the first
     *       word. Two operands of the same field in the same program, delimited two different ways.</li>
     *   <li><strong>The line is 39 characters into a 40-character field.</strong> Two for the number,
     *       two for {@value #OPTION_NUMBER_SEPARATOR}, {@value MenuOptions#OPT_NAME_LENGTH} for the
     *       name. {@code STRING} overwrites only what it writes, and line 241 has just cleared the
     *       field, so character 40 stays a space.</li>
     * </ul>
     *
     * <p>There are twelve dispatch arms plus {@code WHEN OTHER CONTINUE}, and that too differs from
     * {@code COADM01C}, which stops at {@code WHEN 10}. The {@code WHEN OTHER} arm cannot fire with the
     * copybook table - the loop stops at 10 and the arms run to 12 - and is reached here only by a stub
     * table declaring more slots than the map has lines, where it correctly writes nothing.
     *
     * @param optionTable the table to compose from
     * @return exactly {@value #OPTION_LINE_COUNT} lines of {@value #OPTION_TEXT_LENGTH} characters,
     *         index 0 being {@code OPTN001O}; every line the program does not write is spaces
     * @throws NullPointerException if {@code optionTable} is {@code null}
     */
    public List<String> buildMenuOptions(MainMenuOptionTable optionTable) {
        Objects.requireNonNull(optionTable, "An option table is required to compose the menu lines");

        // COMEN1AO's twelve line fields, each already cleared to spaces.
        List<String> lines = new ArrayList<>(blankOptionLines());

        // L238-L239 PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > CDEMO-MENU-OPT-COUNT.
        for (int wsIdx = 1; wsIdx <= optionTable.activeCount(); wsIdx++) {
            MenuOption entry = optionTable.optionBySubscript(wsIdx).orElseThrow();

            // L241 MOVE SPACES TO WS-MENU-OPT-TXT, then L243-L246's STRING overlays 39 characters at
            // the start of the 40-byte field and leaves character 40 as the space line 241 put there.
            String wsMenuOptTxt = codec.padToDeclaredWidth(
                    codec.concatenateDelimitedBySize(entry.menuOptNumImage(),
                            OPTION_NUMBER_SEPARATOR,
                            entry.menuOptName()),
                    OPTION_TEXT_LENGTH);

            // L248-L275 EVALUATE WS-IDX: arms WHEN 1 through WHEN 12 each move the text to their own
            // OPTN00nO field, and WHEN OTHER does nothing. A subscript above the arm count therefore
            // leaves its line as spaces, which is the CONTINUE of line 274.
            if (wsIdx <= OPTION_DISPATCH_ARM_COUNT) {
                lines.set(wsIdx - 1, wsMenuOptTxt);
            }
        }
        return List.copyOf(lines);
    }

    /**
     * {@code SEND-MENU-SCREEN}, {@code app/cbl/COMEN01C.cbl:182-194}, less the header the controller
     * owns.
     *
     * <p>The paragraph performs {@code POPULATE-HEADER-INFO}, then {@code BUILD-MENU-OPTIONS}, then
     * {@code MOVE WS-MESSAGE TO ERRMSGO}, then {@code EXEC CICS SEND MAP('COMEN1A') MAPSET('COMEN01')
     * FROM(COMEN1AO) ERASE}. The first of those reads {@code FUNCTION CURRENT-DATE} at line 214 and is
     * the controller's, so that this service stays deterministic; the rest is here.
     *
     * @param context              {@code CARDDEMO-COMMAREA} as it will be handed back on line 109
     * @param errorFlag            {@code WS-ERR-FLG} at the moment of the send
     * @param wsMessage            {@code WS-MESSAGE}, exactly {@value #MESSAGE_LENGTH} characters, which
     *                             line 187 moves into {@code ERRMSGO}
     * @param messageColour        {@code ERRMSGC}
     * @param optionEcho           {@code OPTIONO}
     * @param resetAllOutputFields whether {@code MOVE LOW-VALUES TO COMEN1AO} on line 89 preceded this
     * @param optionTable          the table {@code BUILD-MENU-OPTIONS} composes from
     * @param receive              the outcome of the {@code RECEIVE MAP}, if one occurred
     * @return the outcome, with no transfer target
     */
    private MainMenuOutcome sendMenuScreen(NavigationContext context,
            boolean errorFlag,
            String wsMessage,
            byte messageColour,
            String optionEcho,
            boolean resetAllOutputFields,
            MainMenuOptionTable optionTable,
            ReceiveOutcome receive) {

        // L185 PERFORM BUILD-MENU-OPTIONS; L187 MOVE WS-MESSAGE TO ERRMSGO; L189-L194 SEND MAP ... ERASE.
        return new MainMenuOutcome(buildMenuOptions(optionTable),
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
     * a {@code SEND} - so the twelve lines stay as they were.
     *
     * <h4>The mapset and map are blank here, and that is deliberate</h4>
     * An {@code XCTL} hands control to another program, and which map that program will paint is its
     * decision, made after this one has ended. Neither statement names a map: line 153 names only
     * {@code CDEMO-MENU-OPT-PGMNAME(WS-OPTION)} and line 176 only {@code CDEMO-TO-PROGRAM}. Nor does
     * {@code COMEN01C} ever assign {@code CDEMO-LAST-MAPSET} or {@code CDEMO-LAST-MAP} - the copybook
     * declares them and the program references neither, confirmed by exhaustive search over
     * {@code app/cbl/COMEN01C.cbl}, exactly as {@code COADM01C} references neither. Naming
     * {@value #MAPSET_NAME} and {@value #MAP_NAME} on a transfer would tell the client to paint the
     * screen it is leaving, which is the one answer that is certainly wrong. Blank means "not stated
     * here": the client follows the successor named in {@code nextProgram}, and the target's own reply
     * names its map. The {@code SEND} path is the other case, and it still names this screen.
     *
     * @param targetProgram   the transfer target, at most
     *                        {@value NavigationContext#TO_PROGRAM_LENGTH} characters
     * @param context         {@code CARDDEMO-COMMAREA} as it stands at the transfer
     * @param carriesCommarea whether the {@code XCTL} specifies {@code COMMAREA}: true for lines 152 to
     *                        155, false for lines 175 to 177
     * @param wsMessage       {@code WS-MESSAGE} as it stands, which on both transfer paths is the
     *                        spaces line 79 left in it
     * @param optionEcho      {@code OPTIONO} as the map already holds it
     * @param receive         the outcome of the {@code RECEIVE MAP}, if one occurred
     * @return the outcome, naming the successor
     */
    private MainMenuOutcome transferToProgram(String targetProgram,
            NavigationContext context,
            boolean carriesCommarea,
            String wsMessage,
            String optionEcho,
            ReceiveOutcome receive) {

        return new MainMenuOutcome(blankOptionLines(),
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
     * {@code RECEIVE-MENU-SCREEN}, {@code app/cbl/COMEN01C.cbl:199-207}.
     *
     * <p>The statement reads the map into {@code COMEN1AI} and captures {@code RESP} and {@code RESP2},
     * neither of which the program ever tests. In the stateless projection the terminal input has
     * already arrived - it is {@link MainMenuInput#option()} and {@link MainMenuInput#eibAid()} - so
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
    // Section 12 - the dead declarations, preserved.
    //
    // Practice: dead and orphan code is preserved, not cleaned up. Two of COMEN01C's declarations are
    // never read by the program and both survive here, because a declaration is part of what the
    // program is. The third and fourth dead items are the RESP/RESP2 pair carried on ReceiveOutcome and
    // the two commented-out MOVE statements transcribed in processEnterKey at lines 149 and 150.
    // =================================================================================================

    /**
     * {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '} - {@code app/cbl/COMEN01C.cbl:39}, declared and
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
     * {@code COPY CSUSR01Y.} - {@code app/cbl/COMEN01C.cbl:58}, which brings {@code 01 SEC-USER-DATA}
     * into working storage where not one of its six fields is read or written.
     *
     * <p>The only mention of any of them anywhere in {@code COMEN01C} is the commented-out
     * {@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE} at line 150. The copybook has twelve consumers
     * across the application and this program is one of them, so the declaration is preserved and
     * mapped onto the single Java type that copybook owns rather than being dropped as unused.
     *
     * <p><strong>This is a declaration and emphatically not a data-access path.</strong> Searching
     * {@code COMEN01C} for {@code READ}, {@code STARTBR}, {@code READNEXT}, {@code WRITE},
     * {@code REWRITE} and {@code DELETE} returns nothing at all: the program's only file-related
     * artefacts are this unused record and the equally unused {@code WS-USRSEC-FILE} name. The
     * security-user repository is therefore <strong>not</strong> a collaborator of this service and does
     * not appear in either constructor - injecting it and reading a user would invent a {@code USRSEC}
     * access the program never performs, which is a new feature, not a migration. That holds even
     * though the authorisation filter compares a user type: the type is read from the communication
     * area the client supplied, never from the file. Authentication belongs to {@code COSGN00C}; by the
     * time {@code CM00} runs, it has already happened.
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
     * right-truncation of {@link MainMenuOutcome#message()} into {@code ERRMSGO} through the same
     * codec, rather than reaching for a substring and getting the direction wrong.
     *
     * @return the codec supplied at construction, never {@code null}
     */
    public FixedWidthCodec codec() {
        return codec;
    }

    // =================================================================================================
    // Section 13 - private character primitives.
    //
    // Small, named and commented, because each one is a COBOL rule that a plain Java operator would get
    // subtly wrong. The two directions of an unqualified MOVE belong to FixedWidthCodec and are taken
    // from it; what remains here is what the codec deliberately does not own - a receiver attribute, an
    // INSPECT, a figurative-constant comparison, and one STRING delimiter.
    // =================================================================================================

    /**
     * The {@code DELIMITED BY SPACE} phrase of the {@code STRING} statement at
     * {@code app/cbl/COMEN01C.cbl:160-161}: send the characters up to, and not including, the first
     * space of the sending item.
     *
     * <p>Written out explicitly rather than expressed as a trim or a split, because neither of those is
     * this rule and both get it wrong in a way a reader would not notice:
     * <ul>
     *   <li>A <em>trailing</em> trim would send {@code "Account View"} in full, since the delimiter it
     *       stops at is not the last space but the <strong>first</strong>.</li>
     *   <li>A split on whitespace would agree by accident for these ten literals and diverge for a
     *       value that begins with a space, where {@code DELIMITED BY SPACE} sends
     *       <strong>nothing at all</strong> - the delimiter is at position 1, so the sending portion is
     *       empty. That case is implemented and is a unit test.</li>
     *   <li>A value containing no space at all is sent whole, which is the other boundary.</li>
     * </ul>
     * It does not live in {@link FixedWidthCodec} because the codec owns what a {@code PICTURE} implies
     * - the two justification-and-fill directions - and a {@code STRING} delimiter is a property of one
     * statement's operand instead. The surrounding composition and the pad to
     * {@value #MESSAGE_LENGTH} still go through the codec, so only the delimiter itself is local.
     *
     * @param source the sending item, at its full declared width including any padding
     * @return the leading characters before the first space, or the whole value when it holds none, or
     *         the empty string when its first character is a space
     */
    private String stringDelimitedBySpace(String source) {
        int firstSpace = source.indexOf(SPACE);
        return firstSpace < 0 ? source : source.substring(0, firstSpace);
    }

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
     * particular receiver, {@code WS-OPTION-X} at {@code app/cbl/COMEN01C.cbl:45}, and not a property of
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
     * {@code INSPECT identifier REPLACING ALL ' ' BY '0'} - {@code app/cbl/COMEN01C.cbl:123}.
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
     * scan at {@code app/cbl/COMEN01C.cbl:119} indexes {@code OPTIONI OF COMEN1AI(WS-IDX:1)} with a
     * 1-based position, and every caller passes a position the loop bound has already constrained.
     *
     * @param image    the field's characters
     * @param oneBased the 1-based character position
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
     * <p>{@code MOVE WS-OPTION TO OPTIONO} happens once, at {@code app/cbl/COMEN01C.cbl:125} inside
     * {@code PROCESS-ENTER-KEY}. On every other path the field keeps whatever it already held, and
     * since no server-side state exists that is the value the client re-supplied - moved into its
     * declared {@value #OPTION_LENGTH}-character width first.
     *
     * @param input the invocation
     * @return exactly {@value #OPTION_LENGTH} characters
     */
    private String receivedOptionImage(MainMenuInput input) {
        return codec.movePicX(input.option(), OPTION_LENGTH);
    }

    /**
     * The twelve {@code OPTN00nO} fields as {@code MOVE LOW-VALUES TO COMEN1AO} leaves them, and as they
     * remain on every path that never performs {@code BUILD-MENU-OPTIONS}.
     *
     * <p>The unpainted image, not spaces: line {@code app/cbl/COMEN01C.cbl:89} moves {@code X'00'}, and a screen field that was
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
