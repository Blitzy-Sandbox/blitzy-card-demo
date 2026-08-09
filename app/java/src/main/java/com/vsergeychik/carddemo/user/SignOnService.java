package com.vsergeychik.carddemo.user;

import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.user.model.SecUserRecord;

import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;

/**
 * Every decision {@code app/cbl/COSGN00C.cbl} makes - the CardDemo sign-on transaction {@code CC00}.
 *
 * <p>The whole of the program's logic lives here rather than in the controller. {@code SignOnController}
 * is a thin shell that maps its request DTO onto {@link SignOnInput}, calls {@link #handle(SignOnInput)}
 * once and maps {@link SignOnOutcome} onto its response DTO; it adds no decision of its own. That split
 * is what lets every branch below be driven by constructing a plain record in a JUnit test, with no
 * {@code MockMvc}, no servlet type and no {@code JobLauncher} anywhere in the path - which is what makes
 * the module's 90%-or-better <em>branch</em> coverage bar reachable at all.
 *
 * <h2>The password comparison is PLAINTEXT, deliberately and visibly</h2>
 *
 * <p>{@code app/cbl/COSGN00C.cbl:223} is a single statement:
 *
 * <pre>
 * IF SEC-USR-PWD = WS-USER-PWD
 * </pre>
 *
 * <p>{@code SEC-USR-PWD} is {@code PIC X(08)} [{@code app/cpy/CSUSR01Y.cpy:21}] and is stored in the
 * clear in the {@code USRSEC} KSDS - {@code app/jcl/DUSRSECJ.jcl:35-44} seeds it as the literal
 * {@code PASSWORD} for all ten users. {@link #readUserSecFile} performs exactly that comparison, on the
 * clear text, and <strong>nothing else</strong>. There is no hash, no salt, no key-derivation function,
 * no {@code PasswordEncoder}, no BCrypt, no token, no JWT and no Spring Security filter chain anywhere
 * in this class or in this module.
 *
 * <p>That is not an oversight and it is not a default that nobody revisited. This is a like-for-like
 * migration whose acceptance test is a field-for-field diff against the COBOL, so introducing hashing
 * would change an observable outcome: a hashed comparison would reject every existing record in
 * {@code USRSEC} and the sign-on transaction would stop working. Spring Security is a named exclusion
 * from the migration's closed dependency set for the same reason. The plaintext comparison is therefore
 * an <em>inherited property of the legacy design</em>, recorded here so that it stays visible to whoever
 * reads this file rather than being buried in generated code, and it is an explicit non-goal of this
 * migration to change it. Hardening it is a separate, deliberate decision about the legacy system,
 * taken with the {@code USRSEC} dataset and its loader in scope - not a side effect of a translation.
 *
 * <p>The opposite mistake is equally forbidden. Nothing here <em>weakens</em> the posture either: the
 * submitted password is a method-local value that never becomes a field, never reaches
 * {@link SignOnOutcome}, is never logged - this class has no logger at all - and is redacted from
 * {@link SignOnInput#toString()}, which a record's generated {@code toString} would otherwise print in
 * full.
 *
 * <h2>No state survives a call</h2>
 *
 * <p>{@code WS-ERR-FLG}, {@code WS-MESSAGE}, {@code WS-USER-ID}, {@code WS-USER-PWD} and
 * {@code SEC-USER-DATA} are COBOL {@code WORKING-STORAGE}: per-task storage that a fresh CICS task gets
 * a fresh copy of. Every one of them is a <strong>method-local variable</strong> here. This class has
 * exactly one field, the injected repository, and it is {@code final}. There is no static mutable state,
 * no instance mutable state and no {@code @Autowired} field, so two concurrent sign-on attempts cannot
 * see each other's user id, and two successive calls in a test cannot influence one another.
 *
 * <p>The CICS conversation state travels the same way. {@code CARDDEMO-COMMAREA} arrives on
 * {@link SignOnInput#navigationContext()} and leaves on {@link SignOnOutcome#navigationContext()};
 * {@link NavigationContext} is an immutable record, so the caller's copy is never mutated underneath it.
 * Nothing is kept in an {@code HttpSession}, a {@code ThreadLocal} or a cache.
 *
 * <h2>Behaviour preserved exactly as written, including four things that look like defects</h2>
 *
 * <ol>
 *   <li><strong>The uppercase normalisation is unconditional, and it happens after the validation
 *       branch.</strong> The two {@code MOVE FUNCTION UPPER-CASE} statements at
 *       {@code COSGN00C.cbl:132-137} sit <em>after</em> {@code END-EVALUATE}, so they run on the two
 *       validation-failure paths as well as on the success path. {@code CDEMO-USER-ID} is therefore
 *       populated even when validation failed, and the communication area handed back at
 *       {@code :100} carries it. Only then does {@code :138} gate the file read on the error flag.
 *       See {@link #processEnterKey}.</li>
 *   <li><strong>The comparison is case-insensitive on the submitted side only.</strong>
 *       {@code :135-136} uppercases the submitted password before the comparison, while
 *       {@code :223} uses the stored {@code SEC-USR-PWD} verbatim. So a stored lower-case password can
 *       never be matched, and a submitted lower-case one can. Both halves are reproduced.</li>
 *   <li><strong>The wrong-password arm does not set the error flag.</strong> Every other failure path
 *       in the program moves {@code 'Y'} to {@code WS-ERR-FLG}; the {@code ELSE} at {@code :241-246}
 *       does not. The asymmetry is preserved rather than harmonised.</li>
 *   <li><strong>First entry is detected with {@code EIBCALEN}, not with the re-enter flag.</strong>
 *       {@code :80} tests {@code IF EIBCALEN = 0}, where the four {@code COUSR0x} programs test
 *       {@code IF NOT CDEMO-PGM-REENTER}. {@code COSGN00C} never reads
 *       {@code CDEMO-PGM-CONTEXT} at all, so the absence of a communication area is modelled directly
 *       - see {@link SignOnInput#isCommareaPresent()}.</li>
 * </ol>
 *
 * <h2>CICS constructs and their Java form</h2>
 *
 * <table border="1">
 *   <caption>Construct mapping</caption>
 *   <tr><th>COBOL / CICS</th><th>Here</th></tr>
 *   <tr><td>{@code IF EIBCALEN = 0} ({@code :80})</td>
 *       <td>an absent {@link SignOnInput#navigationContext()}</td></tr>
 *   <tr><td>{@code EVALUATE EIBAID} ({@code :85-95})</td>
 *       <td>{@link PfKeyResolver#isEnter(byte)} then {@link PfKeyResolver#isPf3(byte)}, in that
 *           order, then the invalid-key arm</td></tr>
 *   <tr><td>{@code EXEC CICS RECEIVE MAP} ({@code :110-115})</td>
 *       <td>{@link ReceiveOutcome} - its {@code RESP} is captured and never tested, exactly as the
 *           source captures and never tests it</td></tr>
 *   <tr><td>{@code EXEC CICS READ DATASET('USRSEC')} ({@code :211-219})</td>
 *       <td>{@link SecUserRepository#read(String)}</td></tr>
 *   <tr><td>{@code MOVE -1 TO USERIDL} / {@code PASSWDL}</td>
 *       <td>{@link CursorField}</td></tr>
 *   <tr><td>{@code EXEC CICS SEND MAP} ({@code :151-157})</td>
 *       <td>{@link SignOnOutcome#screenPainted()}</td></tr>
 *   <tr><td>{@code EXEC CICS SEND TEXT} ({@code :164-169})</td>
 *       <td>{@link SignOnOutcome#plainTextSent()}</td></tr>
 *   <tr><td>{@code EXEC CICS XCTL PROGRAM(...)} ({@code :231-239})</td>
 *       <td>{@link SignOnOutcome#nextProgram()} - a response field the client acts on, never a
 *           server-side forward</td></tr>
 *   <tr><td>{@code EXEC CICS RETURN TRANSID} ({@code :98-102}) versus the bare
 *           {@code EXEC CICS RETURN} ({@code :171-172})</td>
 *       <td>{@link Termination}</td></tr>
 * </table>
 *
 * <p>What this class does <strong>not</strong> model, because it is presentation rather than decision:
 * {@code POPULATE-HEADER-INFO} at {@code :177-204}, which stamps the titles, the current date and time
 * and the {@code APPLID} and {@code SYSID} onto the output map. Those belong to the controller and its
 * response DTO. Note in passing that the program never writes {@code USERIDO}: it only ever reads
 * {@code USERIDI}, at {@code :118} and {@code :132}.
 *
 * <p>No project-specific rules were supplied for this migration - the rules document contains the single
 * line "No user rules provided." Their absence is not treated as licence to relax anything; the
 * enterprise practices cited above (notably <strong>B5</strong> preserve odd behaviour,
 * <strong>B6</strong> neither weaken nor unrequestedly strengthen the security posture,
 * <strong>B8</strong> explicit over implicit and <strong>B9</strong> no static mutable state) govern in
 * their place.
 *
 * @see SecUserRepository
 * @see NavigationContext
 */
@Service
public class SignOnService {

    // =================================================================================================
    // Section 1 - the WS-VARIABLES literals, app/cbl/COSGN00C.cbl:35-46.
    //
    // Each constant carries the line it was read from. None is derived from another, so a mistyped value
    // cannot propagate silently from one to the next.
    // =================================================================================================

    /**
     * {@code WS-PGMNAME PIC X(08) VALUE 'COSGN00C'} [{@code app/cbl/COSGN00C.cbl:36}].
     *
     * <p>Moved to {@code CDEMO-FROM-PROGRAM} at {@code :225} on a successful sign-on. Exactly eight
     * characters, which is {@link NavigationContext#FROM_PROGRAM_LENGTH}, so the move needs no padding.
     */
    public static final String PROGRAM_NAME = "COSGN00C";

    /**
     * {@code WS-TRANID PIC X(04) VALUE 'CC00'} [{@code app/cbl/COSGN00C.cbl:37}].
     *
     * <p>Serves two purposes in the source: it is moved to {@code CDEMO-FROM-TRANID} at {@code :224},
     * and it is the {@code TRANSID} on the {@code EXEC CICS RETURN} at {@code :99} that continues the
     * pseudo-conversation. It is also the transaction {@code app/csd/CARDDEMO.CSD} associates with this
     * program.
     */
    public static final String TRANSACTION_ID = "CC00";

    /**
     * Declared width of {@code WS-MESSAGE PIC X(80)} [{@code app/cbl/COSGN00C.cbl:38}].
     *
     * <p>Every message this service produces is exactly this wide, space-filled on the right.
     *
     * <p>Note that the screen field is <em>narrower</em>: {@code ERRMSGI/ERRMSGO} is
     * {@code PIC X(78)} in {@code app/cpy-bms/COSGN00.CPY}, so {@code MOVE WS-MESSAGE TO ERRMSGO} at
     * {@code :149} truncates 80 down to 78 on the right. That truncation belongs to the controller and
     * its response DTO, which own the screen; this service owns {@code WS-MESSAGE} and hands over all
     * eighty characters. None of the five messages below is long enough for the difference to matter,
     * but the widths are still kept distinct because a field-for-field diff reports them separately.
     */
    public static final int MESSAGE_LENGTH = 80;

    /**
     * {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '} [{@code app/cbl/COSGN00C.cbl:39}] - the
     * {@code DATASET} operand of the {@code EXEC CICS READ} at {@code :212}.
     *
     * <p>Eight characters including the two trailing spaces that fill the {@code PIC X(08)} field; a
     * CICS file name is a fixed-width field, not a trimmed token. It matches
     * {@link SecUserRepository#CICS_FILE_NAME_IMAGE}, and the assertion that the two agree is what
     * guarantees this service and its repository name the same file. The dataset <em>name</em> behind it
     * is never written in Java: it is resolved from {@code carddemo.datasets.USRSEC.dsname}.
     */
    public static final String USRSEC_FILE_NAME = "USRSEC  ";

    /**
     * {@code 88 ERR-FLG-ON VALUE 'Y'} [{@code app/cbl/COSGN00C.cbl:41}] - the image
     * {@code WS-ERR-FLG PIC X(01)} holds when the condition is asserted.
     */
    public static final String ERR_FLG_ON = "Y";

    /**
     * {@code 88 ERR-FLG-OFF VALUE 'N'} [{@code app/cbl/COSGN00C.cbl:42}] - and the {@code VALUE 'N'}
     * the field is initialised to at {@code :40}, which {@code :75} re-asserts on entry.
     */
    public static final String ERR_FLG_OFF = "N";

    /**
     * Declared width of {@code WS-USER-ID PIC X(08)} [{@code app/cbl/COSGN00C.cbl:45}], which is also
     * the width of {@code USERIDI PIC X(8)} in {@code app/cpy-bms/COSGN00.CPY}, of
     * {@code SEC-USR-ID PIC X(08)} in {@code app/cpy/CSUSR01Y.cpy} and of {@code CDEMO-USER-ID} in
     * {@code app/cpy/COCOM01Y.cpy}.
     *
     * <p>It is also the {@code KEYLENGTH} on the {@code EXEC CICS READ} at {@code :216} and the
     * {@code KEYS(8,0)} of the KSDS defined by {@code app/jcl/DUSRSECJ.jcl:65}. Because
     * {@link SecUserRepository#read(String)} requires a key of exactly this width, every user-id value
     * is brought to it before use.
     */
    public static final int USER_ID_LENGTH = 8;

    /**
     * Declared width of {@code WS-USER-PWD PIC X(08)} [{@code app/cbl/COSGN00C.cbl:46}], matching
     * {@code PASSWDI PIC X(8)} in {@code app/cpy-bms/COSGN00.CPY} and
     * {@code SEC-USR-PWD PIC X(08)} in {@code app/cpy/CSUSR01Y.cpy}.
     *
     * <p>Equal widths on both sides are what makes {@code :223} a plain byte-for-byte comparison,
     * trailing spaces included.
     */
    public static final int PASSWORD_LENGTH = 8;

    /**
     * Declared width of {@code CDEMO-USER-TYPE PIC X(01)} [{@code app/cpy/COCOM01Y.cpy:26}], which is
     * the width of the role {@link SignOnOutcome#role()} carries.
     */
    public static final int ROLE_LENGTH = NavigationContext.USER_TYPE_LENGTH;

    /**
     * Declared width of an {@code XCTL PROGRAM} target, from
     * {@code CDEMO-TO-PROGRAM PIC X(08)} [{@code app/cpy/COCOM01Y.cpy:24}].
     */
    public static final int NEXT_PROGRAM_LENGTH = NavigationContext.TO_PROGRAM_LENGTH;

    // =================================================================================================
    // Section 2 - the screen, the transfer targets and the two response values the source enumerates.
    // =================================================================================================

    /**
     * {@code MAPSET('COSGN00')} [{@code app/cbl/COSGN00C.cbl:112} and {@code :153}] - the only mapset
     * this program drives.
     *
     * <p>A constant rather than a component of {@link SignOnOutcome}, because it never varies: unlike
     * {@code COCRDLIC}, which drives two maps, {@code COSGN00C} sends and receives exactly one.
     */
    public static final String MAPSET_NAME = "COSGN00";

    /**
     * {@code MAP('COSGN0A')} [{@code app/cbl/COSGN00C.cbl:111} and {@code :152}].
     */
    public static final String MAP_NAME = "COSGN0A";

    /**
     * {@code EXEC CICS XCTL PROGRAM('COADM01C')} [{@code app/cbl/COSGN00C.cbl:231-232}] - where an
     * administrator goes after signing on.
     *
     * <p>Carried on {@link SignOnOutcome#nextProgram()} so the client issues the follow-up call itself.
     * There is no server-side forward and no redirect chain: the transaction is stateless, and the
     * communication area travels in the payload.
     */
    public static final String ADMIN_PROGRAM = "COADM01C";

    /**
     * {@code EXEC CICS XCTL PROGRAM('COMEN01C')} [{@code app/cbl/COSGN00C.cbl:236-237}] - where every
     * non-administrator goes after signing on.
     *
     * <p>"Every non-administrator" is exact: {@code :230} is {@code IF CDEMO-USRTYP-ADMIN ... ELSE},
     * a two-way split, so a user type of {@code 'U'}, a space or any other byte all arrive here. See
     * {@link #resolveNextProgram(NavigationContext)}.
     */
    public static final String USER_PROGRAM = "COMEN01C";

    /**
     * The {@code WHEN 0} arm of {@code EVALUATE WS-RESP-CD} [{@code app/cbl/COSGN00C.cbl:222}].
     *
     * <p>{@code COSGN00C} is the only online program in the estate that compares {@code RESP} against
     * <em>raw numeric literals</em> instead of the {@code DFHRESP} condition names; {@code 0} is
     * {@code DFHRESP(NORMAL)}, which {@link FileStatus#NORMAL} states once for the whole module.
     */
    public static final int RESP_NORMAL = FileStatus.NORMAL;

    /**
     * The {@code WHEN 13} arm of {@code EVALUATE WS-RESP-CD} [{@code app/cbl/COSGN00C.cbl:247}].
     *
     * <p>{@code 13} is {@code DFHRESP(NOTFND)} - see {@link FileStatus#NOTFND}. The literal is what the
     * source wrote; the name is what makes it legible. Note that the third arm, {@code WHEN OTHER} at
     * {@code :252}, enumerates <strong>no</strong> value and therefore covers every response that is
     * neither of these two.
     */
    public static final int RESP_NOTFND = FileStatus.NOTFND;

    /**
     * The value {@code MOVE -1 TO ...L} writes into a symbolic-map length item to place the cursor on
     * that field - {@code app/cbl/COSGN00C.cbl:82}, {@code :121}, {@code :126}, {@code :244},
     * {@code :250} and {@code :255}.
     *
     * <p>It is a cursor instruction rather than a data length, which is why {@link CursorField} models
     * it as a named target instead of a number on the payload.
     */
    public static final int CURSOR_POSITION = -1;

    // =================================================================================================
    // Section 3 - the five message literals, byte for byte.
    //
    // Each is written out in full rather than composed, and each ends with a space before the ellipsis
    // exactly where the source has one. A field-for-field diff compares these character by character, so
    // a "tidied" spacing would be a parity failure. Two further messages - the thank-you and the
    // invalid-key text - are NOT declared here: they belong to app/cpy/CSMSG01Y.cpy and are taken from
    // SystemMessages, which owns that copybook.
    // =================================================================================================

    /**
     * {@code 'Please enter User ID ...'} [{@code app/cbl/COSGN00C.cbl:120}] - twenty-four characters,
     * with a single space between {@code ID} and the ellipsis.
     */
    public static final String MSG_ENTER_USER_ID = "Please enter User ID ...";

    /**
     * {@code 'Please enter Password ...'} [{@code app/cbl/COSGN00C.cbl:125}] - twenty-five characters.
     */
    public static final String MSG_ENTER_PASSWORD = "Please enter Password ...";

    /**
     * {@code 'Wrong Password. Try again ...'} [{@code app/cbl/COSGN00C.cbl:242-243}] - twenty-nine
     * characters, with a full stop after {@code Password} and a space before the ellipsis.
     *
     * <p>This is the one failure message the source produces <em>without</em> setting
     * {@code WS-ERR-FLG}; see {@link #readUserSecFile}.
     */
    public static final String MSG_WRONG_PASSWORD = "Wrong Password. Try again ...";

    /**
     * {@code 'User not found. Try again ...'} [{@code app/cbl/COSGN00C.cbl:249}] - twenty-nine
     * characters. The {@code WHEN 13} arm.
     */
    public static final String MSG_USER_NOT_FOUND = "User not found. Try again ...";

    /**
     * {@code 'Unable to verify the User ...'} [{@code app/cbl/COSGN00C.cbl:254}] - twenty-nine
     * characters. The {@code WHEN OTHER} arm.
     *
     * <p>Distinct from {@link #MSG_USER_NOT_FOUND} on purpose: "no such user" and "the security file
     * could not be read" are different facts, and collapsing them would tell an operator the wrong
     * thing.
     */
    public static final String MSG_UNABLE_TO_VERIFY = "Unable to verify the User ...";

    // =================================================================================================
    // Section 4 - the two figurative constants the validation branch tests against.
    // =================================================================================================

    /**
     * The character COBOL's {@code SPACES} figurative constant fills an alphanumeric field with.
     */
    private static final char SPACE = ' ';

    /**
     * The character COBOL's {@code LOW-VALUES} figurative constant fills a field with: the lowest value
     * in the collating sequence, {@code X'00'}.
     *
     * <p>This is what CICS leaves in a symbolic-map {@code xxxI} item for a field the terminal never
     * transmitted, which is why {@code :118} and {@code :123} test for it in addition to spaces: a field
     * the user blanked and a field the user never touched are both "empty", and they are not the same
     * bytes.
     */
    private static final char LOW_VALUE = '\u0000';

    /**
     * The lowest and highest characters {@code FUNCTION UPPER-CASE} folds, and the distance it folds
     * them by. See {@link #upperCase(String)} for why the fold is written out rather than delegated to
     * {@link String#toUpperCase()}.
     */
    private static final char LOWER_CASE_A = 'a';

    /** The upper bound of the folded range; see {@link #LOWER_CASE_A}. */
    private static final char LOWER_CASE_Z = 'z';

    /** The offset from a lower-case letter to its upper-case counterpart; see {@link #LOWER_CASE_A}. */
    private static final int CASE_FOLD_OFFSET = 'a' - 'A';

    // =================================================================================================
    // Section 5 - the one collaborator.
    // =================================================================================================

    /**
     * The {@code USRSEC} file, the only resource {@code COSGN00C} touches.
     *
     * <p>{@code final} and constructor-injected. It is the sole field on this class: everything the
     * program keeps in {@code WORKING-STORAGE} is a method-local variable instead, so nothing about one
     * invocation can leak into another.
     */
    private final SecUserRepository secUserRepository;

    /**
     * Wires the sign-on service to the security-user file.
     *
     * <p>Constructor injection, not field injection: it makes the dependency visible in the type,
     * permits the field to be {@code final}, and lets a unit test build the service with a stubbed
     * repository and no Spring context at all - which is how every branch below is driven.
     *
     * <p>Nothing else is injected. The program reads no other dataset, consults no clock in any code
     * path that reaches a decision - {@code POPULATE-HEADER-INFO} is presentation and belongs to the
     * controller - and needs no fixed-width codec, because the one fixed-width receiver it owns,
     * {@code WS-MESSAGE PIC X(80)}, is filled by {@link #movePicX(String, int)} below.
     *
     * @param secUserRepository the {@code USRSEC} repository
     * @throws NullPointerException if {@code secUserRepository} is {@code null}
     */
    public SignOnService(SecUserRepository secUserRepository) {
        this.secUserRepository = Objects.requireNonNull(secUserRepository, "The USRSEC repository is "
                + "required: app/cbl/COSGN00C.cbl:211-219 reads the security-user file, and this "
                + "service has no other way to reach it");
    }

    /**
     * The {@code USRSEC} repository this service was wired to.
     *
     * <p>Exposed for assertions and diagnostics only - it is not a way to reach around this class, and
     * this service never hands it out to anything it calls.
     *
     * @return the repository, never {@code null}
     */
    public SecUserRepository secUserRepository() {
        return secUserRepository;
    }

    // =================================================================================================
    // Section 6 - the carriers.
    //
    // The service takes and returns its own immutable value types rather than the controller's request
    // and response DTOs, so no HTTP or serialisation concern reaches a decision and a JUnit test can
    // drive every branch by constructing a plain record. The controller owns the mapping both ways.
    // =================================================================================================

    /**
     * Which symbolic-map length item received the {@code MOVE -1} that places the cursor.
     *
     * <p>{@code COSGN00C} positions the cursor on exactly two fields and, on one path, on neither:
     *
     * <ul>
     *   <li>{@link #USER_ID} - {@code MOVE -1 TO USERIDL OF COSGN0AI} at {@code :82} (first entry),
     *       {@code :121} (blank user id), {@code :250} (user not found) and {@code :255} (unable to
     *       verify);</li>
     *   <li>{@link #PASSWORD} - {@code MOVE -1 TO PASSWDL OF COSGN0AI} at {@code :126} (blank
     *       password) and {@code :244} (wrong password);</li>
     *   <li>{@link #NONE} - the invalid-key arm at {@code :91-94}, which paints the screen without
     *       repositioning the cursor at all, and every path that transfers control or ends the
     *       conversation rather than painting.</li>
     * </ul>
     *
     * <p>Modelled as a named target rather than as the number {@value SignOnService#CURSOR_POSITION} on
     * a payload field, because {@code -1} in a length item is an instruction to CICS and not a length: a
     * client that echoed it back as data would be sending nonsense.
     */
    public enum CursorField {

        /**
         * No field was repositioned - the invalid-key arm, and every non-painting path.
         *
         * <p>Its {@link #lengthItemName()} is empty, which is why the method returns an
         * {@link Optional} rather than a name that would have to be invented.
         */
        NONE(""),

        /** {@code USERIDL OF COSGN0AI}, the user-id field's length item. */
        USER_ID("USERIDL"),

        /** {@code PASSWDL OF COSGN0AI}, the password field's length item. */
        PASSWORD("PASSWDL");

        /** The symbolic-map length item's name, or an empty string for {@link #NONE}. */
        private final String lengthItem;

        /**
         * Binds a cursor target to the symbolic-map item the source writes {@code -1} into.
         *
         * @param lengthItem the item's name as {@code app/cpy-bms/COSGN00.CPY} spells it, or an empty
         *                   string when no item is written
         */
        CursorField(String lengthItem) {
            this.lengthItem = lengthItem;
        }

        /**
         * The symbolic-map length item this target writes to, named exactly as
         * {@code app/cpy-bms/COSGN00.CPY} spells it.
         *
         * @return the item name, or an empty {@link Optional} for {@link #NONE}
         */
        public Optional<String> lengthItemName() {
            return isPositioned() ? Optional.of(lengthItem) : Optional.empty();
        }

        /**
         * Whether a {@code MOVE -1} was performed at all.
         *
         * @return {@code true} for every target but {@link #NONE}
         */
        public boolean isPositioned() {
            return this != NONE;
        }
    }

    /**
     * How the task left - the three distinct exits {@code COSGN00C} has, which a stateless caller has to
     * be able to tell apart.
     *
     * <p>CICS gives the program three ways out and they are not interchangeable. The distinction is the
     * whole of the pseudo-conversational contract: it says whether the client should send the
     * communication area back to this same transaction, call a different program, or stop.
     */
    public enum Termination {

        /**
         * {@code EXEC CICS XCTL PROGRAM(...) COMMAREA(CARDDEMO-COMMAREA)} at {@code :231-239} - control
         * was transferred, and {@link SignOnOutcome#nextProgram()} names the target.
         *
         * <p>Reached on a successful sign-on and nowhere else. The {@code EXEC CICS RETURN} at
         * {@code :98-102} is never executed on this path, because {@code XCTL} does not come back.
         */
        XCTL,

        /**
         * {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)} at {@code :98-102} -
         * the pseudo-conversation continues, and the next keystroke re-enters this same transaction
         * with the communication area the outcome carries.
         *
         * <p>This is the ordinary exit: first entry, both validation failures, all three read outcomes
         * that do not sign on, and the invalid-key arm.
         */
        RETURN_TRANSID,

        /**
         * The bare {@code EXEC CICS RETURN} inside {@code SEND-PLAIN-TEXT} at {@code :171-172} - no
         * {@code TRANSID}, so the conversation <strong>ends</strong> and the terminal is released.
         *
         * <p>Reached only on the PF3 arm at {@code :88-90}. Because {@code SEND-PLAIN-TEXT} returns from
         * inside itself, the {@code EXEC CICS RETURN TRANSID} at {@code :98} is unreachable on this
         * path - which is exactly why PF3 is a sign-on decision and not a transport detail, and why it
         * is modelled here rather than left to the controller.
         */
        RETURN_NO_TRANSID;

        /**
         * Whether the client should send the communication area back to transaction
         * {@value SignOnService#TRANSACTION_ID}.
         *
         * @return {@code true} only for {@link #RETURN_TRANSID}
         */
        public boolean conversationContinues() {
            return this == RETURN_TRANSID;
        }

        /**
         * The {@code TRANSID} the {@code EXEC CICS RETURN} carried.
         *
         * @return {@value SignOnService#TRANSACTION_ID} for {@link #RETURN_TRANSID}, or an empty
         *         {@link Optional} for the two exits that name no transaction
         */
        public Optional<String> returnTransid() {
            return conversationContinues() ? Optional.of(TRANSACTION_ID) : Optional.empty();
        }
    }

    /**
     * Everything {@code COSGN00C} learns about one invocation: the communication area, the attention
     * identifier and the two screen fields it reads.
     *
     * <p>Those four are exactly what the program consults. It reads {@code EIBCALEN} through the
     * presence or absence of {@code DFHCOMMAREA} ({@code :65-67} and {@code :80}), {@code EIBAID} at
     * {@code :85} - its only site - and {@code USERIDI} and {@code PASSWDI} at {@code :118}, {@code :123}
     * and {@code :132-136}. It reads no other map field and, in any path that reaches a decision, no
     * clock.
     *
     * @param navigationContext {@code CARDDEMO-COMMAREA} as received, or {@code null} when no
     *                          communication area accompanied the request. {@code null} <em>is</em> the
     *                          representation of {@code EIBCALEN = 0}: a separate boolean flag would be
     *                          a second source of truth able to contradict the reference beside it,
     *                          whereas absence cannot contradict itself
     * @param eibAid            the raw EBCDIC attention-identifier byte the terminal sent. Held as the
     *                          byte rather than as a decoded token because {@code :85} compares the
     *                          byte, and because the byte is the only form in which an unrecognised key
     *                          can still be represented
     * @param userId            {@code USERIDI OF COSGN0AI} as received, or {@code null} for a field the
     *                          terminal never transmitted. See the note on {@code null} below
     * @param password          {@code PASSWDI OF COSGN0AI} as received, or {@code null} on the same
     *                          terms. It is used and discarded: it never becomes a field, never reaches
     *                          {@link SignOnOutcome}, and is redacted from {@link #toString()}
     */
    public record SignOnInput(NavigationContext navigationContext,
                              byte eibAid,
                              String userId,
                              String password) {

        /**
         * Carries every component exactly as it arrives, {@code null} included.
         *
         * <p><strong>Why {@code null} is accepted rather than rejected or filled in.</strong>
         * {@code :118} and {@code :123} each test {@code = SPACES OR LOW-VALUES}, so the program itself
         * distinguishes three states of a screen field, not two: it holds spaces because the user
         * blanked it, it holds {@code LOW-VALUES} because the terminal never transmitted it, or it holds
         * data. A JSON payload that omits the member is precisely the second case - the field was not
         * transmitted - so {@code null} is carried through and read as {@code LOW-VALUES} by
         * {@link SignOnService#receivedFieldImage(String, int)}. Substituting spaces here would erase a
         * distinction the source makes; throwing would turn "the user pressed ENTER on an untouched
         * screen", which the program answers with a message, into a server error.
         *
         * <p>A value <em>shorter</em> than the field's declared width is likewise carried verbatim and
         * brought to width later, where the COBOL alphanumeric {@code MOVE} rule is applied explicitly.
         */
        public SignOnInput {
            // Intentionally empty. Every component is carried verbatim: an absent communication area is
            // EIBCALEN = 0, and an absent screen field is LOW-VALUES. Both are states this type must be
            // able to express rather than states to fill in.
        }

        /**
         * An invocation with no communication area - the Java form of {@code IF EIBCALEN = 0} at
         * {@code app/cbl/COSGN00C.cbl:80}, which is the transaction being typed at a clear screen rather
         * than being returned to.
         *
         * @param eibAid   the attention identifier; immaterial on this path, because the program paints
         *                 the sign-on screen and returns before it ever reaches {@code :85}
         * @param userId   {@code USERIDI}; likewise never inspected on this path
         * @param password {@code PASSWDI}; likewise never inspected on this path
         * @return the input, never {@code null}
         */
        public static SignOnInput withoutCommarea(byte eibAid, String userId, String password) {
            return new SignOnInput(null, eibAid, userId, password);
        }

        /**
         * Whether a communication area accompanied this invocation - the negation of
         * {@code IF EIBCALEN = 0}.
         *
         * @return {@code true} when {@link #navigationContext()} is present, which is the {@code ELSE}
         *         branch at {@code app/cbl/COSGN00C.cbl:84}
         */
        public boolean isCommareaPresent() {
            return navigationContext != null;
        }

        /**
         * The diagnostic rendering, with the password replaced by a fixed marker.
         *
         * <p>Overriding is <strong>mandatory</strong>, not stylistic: a record's generated
         * {@code toString} prints every component, so the inherited implementation would put the
         * submitted password into any log line, assertion failure or exception message that rendered
         * this input. The marker is a constant rather than a mask derived from the value, so the
         * rendering cannot disclose the password, its length, or whether one was supplied at all.
         *
         * @return the rendering; never contains the password
         */
        @Override
        public String toString() {
            return "SignOnInput[navigationContext=" + navigationContext
                    + ", eibAid=0x" + hexImage(eibAid)
                    + ", userId=" + userId
                    + ", password=" + NavigationContext.REDACTED + "]";
        }
    }

    /**
     * The outcome of the {@code EXEC CICS RECEIVE MAP} at {@code app/cbl/COSGN00C.cbl:110-115},
     * including the two condition codes the source captures and never looks at.
     *
     * <p>The statement is
     * {@code EXEC CICS RECEIVE MAP('COSGN0A') MAPSET('COSGN00') RESP(WS-RESP-CD) RESP2(WS-REAS-CD)}, and
     * searching the program shows that neither {@code WS-RESP-CD} nor {@code WS-REAS-CD} is tested
     * between {@code :115} and {@code :219}, where the {@code EXEC CICS READ} overwrites both. So the
     * receive's response is captured, immediately shadowed and never acted on. It is therefore carried
     * here and <strong>no branch depends on it</strong>. Inventing error handling for a receive the
     * source does not check would be adding behaviour, and the {@code EVALUATE WS-RESP-CD} at
     * {@code :221} would then be reading a value this class had already interpreted.
     *
     * @param performed  whether the {@code RECEIVE} ran at all. It runs on exactly one path - the ENTER
     *                   arm at {@code :87} - and not on the first-entry, PF3 or invalid-key paths
     * @param respCode   {@code WS-RESP-CD PIC S9(09) COMP} at {@code :43}, captured and untested
     * @param reasonCode {@code WS-REAS-CD PIC S9(09) COMP} at {@code :44}, captured and untested
     */
    public record ReceiveOutcome(boolean performed, int respCode, int reasonCode) {

        /**
         * The paragraph did not run: the three paths that never receive the map.
         *
         * <p>Both codes are zero because {@code :43-44} initialise the fields to {@code ZEROS} and
         * nothing has written them yet.
         */
        public static final ReceiveOutcome NOT_PERFORMED = new ReceiveOutcome(false, 0, 0);

        /**
         * A {@code RECEIVE MAP} that reported {@code DFHRESP(NORMAL)} - the ordinary case, and the only
         * one this service can produce, because the map arrives already decoded on
         * {@link SignOnInput}.
         *
         * @return the outcome, with {@link #performed()} true and both codes zero
         */
        public static ReceiveOutcome normal() {
            return new ReceiveOutcome(true, RESP_NORMAL, FileStatus.NO_REASON_CODE);
        }
    }

    /**
     * Everything one run of {@code COSGN00C} produced, and everything the controller needs to build its
     * response - so that the controller itself makes no decision.
     *
     * <p>The submitted password is <strong>not</strong> a component and there is no accessor for it. It
     * is consumed inside {@link SignOnService#readUserSecFile} and discarded there.
     *
     * @param signedOn             whether the plaintext comparison at {@code :223} succeeded and the
     *                             program transferred control. True on exactly one path
     * @param role                 {@code CDEMO-USER-TYPE} as the sign-on established it, from
     *                             {@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE} at {@code :227}; a single
     *                             space when no sign-on occurred. Exactly
     *                             {@value SignOnService#ROLE_LENGTH} character
     * @param nextProgram          the {@code EXEC CICS XCTL PROGRAM} target - {@code 'COADM01C'} at
     *                             {@code :232} or {@code 'COMEN01C'} at {@code :237} - space-padded to
     *                             {@value SignOnService#NEXT_PROGRAM_LENGTH}; all spaces when no transfer
     *                             happened
     * @param message              {@code WS-MESSAGE}, exactly {@value SignOnService#MESSAGE_LENGTH}
     *                             characters, space-filled on the right. All spaces on the two paths
     *                             that produce no message
     * @param errorFlag            whether {@code 88 ERR-FLG-ON} holds, that is whether
     *                             {@code WS-ERR-FLG} was moved {@code 'Y'}. Note the deliberate
     *                             asymmetry: the wrong-password path produces a message with this
     *                             {@code false}
     * @param cursorField          which length item received the {@code MOVE -1}
     * @param screenPainted        whether {@code SEND-SIGNON-SCREEN} ({@code :145-157}) transmitted the
     *                             map
     * @param resetAllOutputFields whether {@code MOVE LOW-VALUES TO COSGN0AO} at {@code :81} cleared
     *                             every output field first. True on the first-entry path alone
     * @param plainTextSent        whether {@code SEND-PLAIN-TEXT} ({@code :162-172}) transmitted
     *                             unformatted text. True on the PF3 path alone
     * @param termination          how the task left
     * @param navigationContext    {@code CARDDEMO-COMMAREA} as it stands when the program leaves: the
     *                             area {@code :100} hands back, or the area {@code :233} and
     *                             {@code :238} pass to the next program. Never {@code null} - the source
     *                             always returns an area, even on the cold-start path
     * @param receive              the outcome of the {@code RECEIVE MAP}
     * @param resolvedAid          the token {@link PfKeyResolver} maps {@link SignOnInput#eibAid()} onto,
     *                             or empty for a byte it recognises no mapping for. Carried for
     *                             diagnostics; on the first-entry path the program never consults the
     *                             attention identifier at all
     * @param readOutcome          how the {@code EXEC CICS READ} at {@code :211-219} classified, or empty
     *                             on the four paths that never read the file
     */
    public record SignOnOutcome(boolean signedOn,
                                String role,
                                String nextProgram,
                                String message,
                                boolean errorFlag,
                                CursorField cursorField,
                                boolean screenPainted,
                                boolean resetAllOutputFields,
                                boolean plainTextSent,
                                Termination termination,
                                NavigationContext navigationContext,
                                ReceiveOutcome receive,
                                Optional<PfKeyResolver.AidKey> resolvedAid,
                                Optional<FileStatus.Outcome> readOutcome) {

        /**
         * Checks every declared width and the two invariants that tie the success flag to the way the
         * task left, so a composition error is caught where the outcome is built rather than where a
         * client later reads back something impossible.
         *
         * @throws NullPointerException     if any reference component is {@code null}
         * @throws IllegalArgumentException if a width departs from its copybook declaration, if
         *                                  {@link #signedOn()} disagrees with {@link #termination()}, or
         *                                  if it disagrees with whether {@link #nextProgram()} names a
         *                                  program
         */
        public SignOnOutcome {
            Objects.requireNonNull(role, "CDEMO-USER-TYPE is PIC X(" + ROLE_LENGTH + ") and is never "
                    + "null; pass a space when no sign-on established a role");
            Objects.requireNonNull(nextProgram, "An XCTL target is PIC X(" + NEXT_PROGRAM_LENGTH
                    + ") and is never null; pass spaces when the program did not transfer control");
            Objects.requireNonNull(message, "WS-MESSAGE is PIC X(" + MESSAGE_LENGTH + ") and is never "
                    + "null; pass spaces for a blank message");
            Objects.requireNonNull(cursorField, "A cursor target is required; use CursorField.NONE "
                    + "where the source performs no MOVE -1");
            Objects.requireNonNull(termination, "A termination is required: app/cbl/COSGN00C.cbl leaves "
                    + "through an XCTL, a RETURN TRANSID or a bare RETURN, and every path is one of them");
            Objects.requireNonNull(navigationContext, "CARDDEMO-COMMAREA is required: the source hands "
                    + "an area back or on, on every path");
            Objects.requireNonNull(receive, "A receive outcome is required; use "
                    + "ReceiveOutcome.NOT_PERFORMED on a path that never receives the map");
            Objects.requireNonNull(resolvedAid, "The resolved AID is an Optional, never null");
            Objects.requireNonNull(readOutcome, "The read outcome is an Optional, never null; it is "
                    + "empty on the paths that never read USRSEC");
            if (role.length() != ROLE_LENGTH) {
                throw new IllegalArgumentException("CDEMO-USER-TYPE is PIC X(" + ROLE_LENGTH
                        + ") (app/cpy/COCOM01Y.cpy:26) but the image is " + role.length()
                        + " character(s) wide");
            }
            if (nextProgram.length() != NEXT_PROGRAM_LENGTH) {
                throw new IllegalArgumentException("An XCTL target is PIC X(" + NEXT_PROGRAM_LENGTH
                        + ") (app/cpy/COCOM01Y.cpy:24) but the image is " + nextProgram.length()
                        + " character(s) wide");
            }
            if (message.length() != MESSAGE_LENGTH) {
                throw new IllegalArgumentException("WS-MESSAGE is PIC X(" + MESSAGE_LENGTH
                        + ") (app/cbl/COSGN00C.cbl:38) but the image is " + message.length()
                        + " character(s) wide");
            }
            // A successful sign-on is an XCTL and nothing else: :231-239 is the only transfer in the
            // program, and it is reachable only from the true branch of :223.
            if (signedOn != (termination == Termination.XCTL)) {
                throw new IllegalArgumentException("A sign-on succeeds exactly when the task left "
                        + "through an XCTL (app/cbl/COSGN00C.cbl:231-239), but signedOn=" + signedOn
                        + " was given with termination=" + termination);
            }
            // ... and an XCTL always names its target, while every other exit names none.
            if (signedOn == nextProgram.isBlank()) {
                throw new IllegalArgumentException("An XCTL names its target program and no other exit "
                        + "does, but signedOn=" + signedOn + " was given with nextProgram='"
                        + nextProgram + "'");
            }
        }

        /**
         * The one-character image of {@code WS-ERR-FLG} as the source stores it.
         *
         * @return {@value SignOnService#ERR_FLG_ON} or {@value SignOnService#ERR_FLG_OFF}
         */
        public String errFlgImage() {
            return errorFlag ? ERR_FLG_ON : ERR_FLG_OFF;
        }

        /**
         * Whether the program left through an {@code EXEC CICS XCTL} naming a follow-on program.
         *
         * @return {@code true} when {@link #nextProgram()} names a program rather than holding spaces
         */
        public boolean hasNextProgram() {
            return !nextProgram.isBlank();
        }

        /**
         * Whether the established role is the administrator role - {@code 88 CDEMO-USRTYP-ADMIN} at
         * {@code app/cpy/COCOM01Y.cpy:27}.
         *
         * <p>Exact and case-sensitive, as a COBOL alphanumeric comparison is, and deliberately
         * <strong>not</strong> the negation of a regular-user test: a blank role satisfies neither.
         *
         * @return {@code true} only when {@link #role()} is exactly
         *         {@link NavigationContext#USER_TYPE_ADMIN}
         */
        public boolean isAdminRole() {
            return NavigationContext.USER_TYPE_ADMIN.equals(role);
        }

        /**
         * The {@code TRANSID} on the {@code EXEC CICS RETURN}, where the return carried one.
         *
         * @return {@value SignOnService#TRANSACTION_ID} when the pseudo-conversation continues, or an
         *         empty {@link Optional} after an {@code XCTL} or a bare {@code RETURN}
         */
        public Optional<String> returnTransid() {
            return termination.returnTransid();
        }
    }

    // =================================================================================================
    // Section 7 - MAIN-PARA, app/cbl/COSGN00C.cbl:73-102.
    // =================================================================================================

    /**
     * Runs {@code COSGN00C} once - the single entry point, and the Java form of {@code MAIN-PARA}.
     *
     * <pre>
     * MAIN-PARA.
     *     SET ERR-FLG-OFF TO TRUE                                        :75
     *     MOVE SPACES TO WS-MESSAGE ERRMSGO OF COSGN0AO                  :77-78
     *     IF EIBCALEN = 0                                                :80
     *         MOVE LOW-VALUES TO COSGN0AO                                :81
     *         MOVE -1       TO USERIDL OF COSGN0AI                       :82
     *         PERFORM SEND-SIGNON-SCREEN                                 :83
     *     ELSE
     *         EVALUATE EIBAID                                            :85
     *             WHEN DFHENTER  PERFORM PROCESS-ENTER-KEY               :86-87
     *             WHEN DFHPF3    MOVE CCDA-MSG-THANK-YOU TO WS-MESSAGE   :88-89
     *                            PERFORM SEND-PLAIN-TEXT                 :90
     *             WHEN OTHER     MOVE 'Y' TO WS-ERR-FLG                  :91-92
     *                            MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE :93
     *                            PERFORM SEND-SIGNON-SCREEN              :94
     *         END-EVALUATE
     *     END-IF.
     *     EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA) :98-102
     * </pre>
     *
     * <p><strong>Why the attention identifier is dispatched by byte and not by resolved token.</strong>
     * {@code :85} is {@code EVALUATE EIBAID}, an ordered {@code EVALUATE} over the raw byte, and
     * {@code COSGN00C} does <em>not</em> copy {@code CSSTRPFY} - it is one of the twelve online programs
     * that test {@code EIBAID} inline. {@link PfKeyResolver#isEnter(byte)} and
     * {@link PfKeyResolver#isPf3(byte)} are therefore used to choose the arm, because they are exactly
     * the byte comparisons {@code :86} and {@code :88} perform. Choosing the arm from
     * {@link PfKeyResolver#resolve(byte)} instead would fold PF15 onto PF3, since {@code CSSTRPFY} maps
     * both to the single token {@code PFK03} - and this program has no such behaviour. The resolved
     * token is still carried on the outcome, and a byte the resolver recognises no mapping for lands on
     * the invalid-key arm, which is where {@code WHEN OTHER} puts it too.
     *
     * <p>The arms are tested in source order, and the last one is the default. That ordering is
     * semantic: a COBOL {@code EVALUATE} selects the first {@code WHEN} whose condition holds.
     *
     * @param input the invocation
     * @return everything the run produced; never {@code null}
     * @throws NullPointerException  if {@code input} is {@code null}
     * @throws IllegalStateException if the security-user file is misconfigured to the point that it
     *                               cannot be read at all. That is a defect in this module's wiring
     *                               rather than a CICS response, so it is reported as one instead of
     *                               being folded into the {@code WHEN OTHER} arm - see
     *                               {@link #readUserSecFile}
     */
    public SignOnOutcome handle(SignOnInput input) {
        Objects.requireNonNull(input, "An invocation is required");

        // :75 SET ERR-FLG-OFF TO TRUE. WS-ERR-FLG is method-local, never a field: a singleton bean
        // holding per-request working storage would break request isolation.
        boolean errorFlag = false;

        // :77-78 MOVE SPACES TO WS-MESSAGE and to ERRMSGO OF COSGN0AO. Both receivers start blank; the
        // second is the screen field the controller fills from the first.
        String wsMessage = spaces(MESSAGE_LENGTH);

        // Resolved once, carried on every outcome. On the first-entry path below the program never
        // consults EIBAID at all, so the token is diagnostic there rather than load-bearing.
        Optional<PfKeyResolver.AidKey> resolvedAid = PfKeyResolver.resolve(input.eibAid());

        // :80 IF EIBCALEN = 0 - no communication area accompanied the request.
        if (!input.isCommareaPresent()) {
            // :81 MOVE LOW-VALUES TO COSGN0AO clears every output field before the paint.
            // :82 MOVE -1 TO USERIDL puts the cursor on the user-id field.
            // :83 PERFORM SEND-SIGNON-SCREEN transmits, and :98 then returns the area.
            //
            // The area returned is CARDDEMO-COMMAREA, which COPY COCOM01Y brings in as WORKING-STORAGE
            // at :48 with no VALUE clause, so on a cold task it still holds binary zeros.
            // NavigationContext.empty() presents that initialised area as spaces and zeros instead,
            // because PIC 9(01) has no representation for a binary zero byte. Nothing observable turns
            // on the difference here: COSGN00C never reads CDEMO-PGM-CONTEXT, and empty() leaves it at
            // the CDEMO-PGM-ENTER value the next entry would want anyway.
            return sendSignonScreen(NavigationContext.empty(),
                    errorFlag,
                    wsMessage,
                    CursorField.USER_ID,
                    true,
                    ReceiveOutcome.NOT_PERFORMED,
                    resolvedAid,
                    Optional.empty());
        }

        // :84 ELSE - an area was passed, so DFHCOMMAREA is addressable and EIBAID decides.
        NavigationContext context = input.navigationContext();

        // :85 EVALUATE EIBAID, first match wins.
        if (PfKeyResolver.isEnter(input.eibAid())) {
            // :86-87 WHEN DFHENTER: PERFORM PROCESS-ENTER-KEY.
            return processEnterKey(input, context, resolvedAid);
        }
        if (PfKeyResolver.isPf3(input.eibAid())) {
            // :88-89 WHEN DFHPF3: MOVE CCDA-MSG-THANK-YOU TO WS-MESSAGE. A PIC X(50) sender into a
            // PIC X(80) receiver, so the fifty characters are left-justified and the remaining thirty
            // are spaces - movePicX states that direction at the call site.
            //
            // CCDA-MSG-THANK-YOU comes from app/cpy/CSMSG01Y.cpy, which COSGN00C copies at :54, and is
            // owned by SystemMessages. It is NOT the similarly named forty-character title literal in
            // COTTL01Y: different text, different width, different copybook.
            wsMessage = movePicX(SystemMessages.CCDA_MSG_THANK_YOU, MESSAGE_LENGTH);
            // :90 PERFORM SEND-PLAIN-TEXT, which sends unformatted text and then issues a bare
            // EXEC CICS RETURN at :171-172 - no TRANSID, so the conversation ends here and :98 is never
            // reached. Note it does not set the error flag: signing off is not an error.
            return sendPlainText(context, wsMessage, resolvedAid);
        }

        // :91 WHEN OTHER - any other key, and equally any byte PfKeyResolver recognises no mapping for.
        // :92 MOVE 'Y' TO WS-ERR-FLG.
        errorFlag = true;
        // :93 MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE, again PIC X(50) into PIC X(80).
        wsMessage = movePicX(SystemMessages.CCDA_MSG_INVALID_KEY, MESSAGE_LENGTH);
        // :94 PERFORM SEND-SIGNON-SCREEN. Note what this arm does NOT do: there is no MOVE -1 anywhere
        // in :91-94, so no field is repositioned and the cursor target is NONE.
        return sendSignonScreen(context,
                errorFlag,
                wsMessage,
                CursorField.NONE,
                false,
                ReceiveOutcome.NOT_PERFORMED,
                resolvedAid,
                Optional.empty());
    }

    // =================================================================================================
    // Section 8 - PROCESS-ENTER-KEY, app/cbl/COSGN00C.cbl:108-140.
    // =================================================================================================

    /**
     * {@code PROCESS-ENTER-KEY} - validate the two fields, normalise them, and read the file if nothing
     * was wrong.
     *
     * <pre>
     * PROCESS-ENTER-KEY.
     *     EXEC CICS RECEIVE MAP('COSGN0A') MAPSET('COSGN00')
     *               RESP(WS-RESP-CD) RESP2(WS-REAS-CD) END-EXEC.          :110-115
     *     EVALUATE TRUE                                                   :117
     *         WHEN USERIDI OF COSGN0AI = SPACES OR LOW-VALUES             :118
     *             MOVE 'Y' TO WS-ERR-FLG                                  :119
     *             MOVE 'Please enter User ID ...' TO WS-MESSAGE            :120
     *             MOVE -1  TO USERIDL OF COSGN0AI                         :121
     *             PERFORM SEND-SIGNON-SCREEN                              :122
     *         WHEN PASSWDI OF COSGN0AI = SPACES OR LOW-VALUES             :123
     *             MOVE 'Y' TO WS-ERR-FLG                                  :124
     *             MOVE 'Please enter Password ...' TO WS-MESSAGE           :125
     *             MOVE -1  TO PASSWDL OF COSGN0AI                         :126
     *             PERFORM SEND-SIGNON-SCREEN                              :127
     *         WHEN OTHER  CONTINUE                                        :128-129
     *     END-EVALUATE.                                                   :130
     *     MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI) TO
     *                     WS-USER-ID CDEMO-USER-ID                        :132-134
     *     MOVE FUNCTION UPPER-CASE(PASSWDI OF COSGN0AI) TO WS-USER-PWD    :135-136
     *     IF NOT ERR-FLG-ON PERFORM READ-USER-SEC-FILE END-IF.            :138-140
     * </pre>
     *
     * <p><strong>Both emptiness tests are two conditions, not one.</strong> {@code = SPACES OR
     * LOW-VALUES} is satisfied by a field of all spaces <em>or</em> a field of all {@code X'00'}, and by
     * nothing else - a field holding four {@code X'00'} bytes then four spaces satisfies neither and
     * falls through to {@code WHEN OTHER}. Collapsing the pair into a Java emptiness test would answer
     * differently for the untransmitted case, and a mixed field would be misread as empty. See
     * {@link #isSpacesOrLowValues(String)}.
     *
     * <p><strong>The ordering at {@code :130-140} is the subtle part of this program.</strong> The two
     * {@code MOVE FUNCTION UPPER-CASE} statements sit <em>after</em> {@code END-EVALUATE} and are
     * unconditional, so:
     *
     * <ol>
     *   <li>they execute on the two error paths as well as on the success path, which means
     *       {@code CDEMO-USER-ID} ends up holding the upper-cased user id <em>even when validation
     *       failed</em>, and the communication area returned at {@code :100} carries it. A guard clause
     *       returning early on a validation failure would skip the normalisation and diverge, so the
     *       flow below normalises first, unconditionally, and only then decides whether to read;</li>
     *   <li>they execute <em>after</em> {@code SEND-SIGNON-SCREEN} has already transmitted on those two
     *       paths. That ordering is faithfully preserved and is unobservable on the screen, for the
     *       reason recorded on this class: the program never writes {@code USERIDO}, so the transmitted
     *       map carries no user id either way. It is observable in the returned communication area, and
     *       that is what {@link SignOnOutcome#navigationContext()} shows.</li>
     * </ol>
     *
     * <p><strong>The password is upper-cased too</strong> ({@code :135-136}), while the stored
     * {@code SEC-USR-PWD} is compared verbatim at {@code :223}. The comparison is therefore
     * case-insensitive on the submitted side and case-sensitive on the stored side. That is behaviour,
     * not a defect: it is reproduced, and neither half is "corrected".
     *
     * @param input       the invocation, for the two screen fields
     * @param context     the communication area as received
     * @param resolvedAid the token for the attention identifier, carried through to the outcome
     * @return the outcome of this path
     */
    private SignOnOutcome processEnterKey(SignOnInput input,
                                          NavigationContext context,
                                          Optional<PfKeyResolver.AidKey> resolvedAid) {

        // :110-115 EXEC CICS RECEIVE MAP. Its RESP and RESP2 are captured into WS-RESP-CD and
        // WS-REAS-CD and never tested before :219 overwrites both, so nothing below branches on them.
        ReceiveOutcome receive = ReceiveOutcome.normal();

        // The two symbolic-map items as CICS delivers them: exactly their declared width, with an
        // untransmitted field arriving as LOW-VALUES.
        String useridi = receivedFieldImage(input.userId(), USER_ID_LENGTH);
        String passwdi = receivedFieldImage(input.password(), PASSWORD_LENGTH);

        boolean errorFlag = false;
        String wsMessage = spaces(MESSAGE_LENGTH);
        CursorField cursorField = CursorField.NONE;

        // :117 EVALUATE TRUE - ordered, first match wins.
        if (isSpacesOrLowValues(useridi)) {
            // :118-122 WHEN USERIDI = SPACES OR LOW-VALUES.
            errorFlag = true;                                              // :119
            wsMessage = movePicX(MSG_ENTER_USER_ID, MESSAGE_LENGTH);       // :120
            cursorField = CursorField.USER_ID;                             // :121
            // :122 PERFORM SEND-SIGNON-SCREEN happens HERE, before the normalisation below.
        } else if (isSpacesOrLowValues(passwdi)) {
            // :123-127 WHEN PASSWDI = SPACES OR LOW-VALUES. Reached only when the user id was NOT
            // empty, because EVALUATE stops at the first match: a request with both fields empty
            // produces the user-id message and never the password one.
            errorFlag = true;                                              // :124
            wsMessage = movePicX(MSG_ENTER_PASSWORD, MESSAGE_LENGTH);      // :125
            cursorField = CursorField.PASSWORD;                            // :126
            // :127 PERFORM SEND-SIGNON-SCREEN, likewise before the normalisation.
        }
        // :128-129 WHEN OTHER CONTINUE - deliberately no else branch: the fall-through does nothing.

        // :132-134 MOVE FUNCTION UPPER-CASE(USERIDI) TO WS-USER-ID, CDEMO-USER-ID. UNCONDITIONAL: this
        // runs on all three paths above, which is the quirk documented on this method. Both receivers
        // are PIC X(08) and the sender is PIC X(8), so the move neither pads nor truncates.
        String wsUserId = upperCase(useridi);
        NavigationContext normalised = context.withUserId(wsUserId);

        // :135-136 MOVE FUNCTION UPPER-CASE(PASSWDI) TO WS-USER-PWD. Also unconditional. This value is
        // method-local and stays that way: it is handed to readUserSecFile, compared there, and never
        // stored, returned or logged.
        String wsUserPwd = upperCase(passwdi);

        // :138-140 IF NOT ERR-FLG-ON PERFORM READ-USER-SEC-FILE. Tests the 88-level ERR-FLG-ON, so a
        // path that set the flag skips the file read entirely - the read is gated, the normalisation
        // above is not.
        if (!errorFlag) {
            return readUserSecFile(normalised, wsUserId, wsUserPwd, receive, resolvedAid);
        }

        // Either validation arm: the screen was painted at :122 or :127 and :98 then returns the area,
        // which by now carries the normalised user id.
        return sendSignonScreen(normalised,
                errorFlag,
                wsMessage,
                cursorField,
                false,
                receive,
                resolvedAid,
                Optional.empty());
    }

    // =================================================================================================
    // Section 9 - READ-USER-SEC-FILE, app/cbl/COSGN00C.cbl:209-257.
    // =================================================================================================

    /**
     * {@code READ-USER-SEC-FILE} - read {@code USRSEC} by key, then branch three ways on the response.
     *
     * <pre>
     * READ-USER-SEC-FILE.
     *     EXEC CICS READ DATASET(WS-USRSEC-FILE) INTO(SEC-USER-DATA)
     *          LENGTH(LENGTH OF SEC-USER-DATA) RIDFLD(WS-USER-ID)
     *          KEYLENGTH(LENGTH OF WS-USER-ID)
     *          RESP(WS-RESP-CD) RESP2(WS-REAS-CD) END-EXEC.               :211-219
     *     EVALUATE WS-RESP-CD                                             :221
     *         WHEN 0                                                      :222
     *             IF SEC-USR-PWD = WS-USER-PWD                            :223
     *                 MOVE WS-TRANID    TO CDEMO-FROM-TRANID              :224
     *                 MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM             :225
     *                 MOVE WS-USER-ID   TO CDEMO-USER-ID                  :226
     *                 MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE                :227
     *                 MOVE ZEROS        TO CDEMO-PGM-CONTEXT              :228
     *                 IF CDEMO-USRTYP-ADMIN                               :230
     *                      EXEC CICS XCTL PROGRAM('COADM01C') ...         :231-234
     *                 ELSE EXEC CICS XCTL PROGRAM('COMEN01C') ...         :235-239
     *                 END-IF
     *             ELSE
     *                 MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE   :242-243
     *                 MOVE -1 TO PASSWDL OF COSGN0AI                      :244
     *                 PERFORM SEND-SIGNON-SCREEN                          :245
     *             END-IF
     *         WHEN 13                                                     :247
     *             MOVE 'Y' TO WS-ERR-FLG                                  :248
     *             MOVE 'User not found. Try again ...' TO WS-MESSAGE       :249
     *             MOVE -1  TO USERIDL OF COSGN0AI                         :250
     *             PERFORM SEND-SIGNON-SCREEN                              :251
     *         WHEN OTHER                                                  :252
     *             MOVE 'Y' TO WS-ERR-FLG                                  :253
     *             MOVE 'Unable to verify the User ...' TO WS-MESSAGE       :254
     *             MOVE -1  TO USERIDL OF COSGN0AI                         :255
     *             PERFORM SEND-SIGNON-SCREEN                              :256
     *     END-EVALUATE.                                                   :257
     * </pre>
     *
     * <p><strong>The password comparison at {@code :223} is on the clear text.</strong> Both sides are
     * {@code PIC X(08)}, so it is a byte-for-byte comparison of eight characters, trailing spaces
     * included, and {@link String#equals(Object)} on two eight-character images is exactly that. The
     * submitted side has been upper-cased by {@code :135-136}; the stored side has not. Nothing is
     * hashed, encoded or normalised beyond that - see the note on this class for why that is deliberate
     * and where the decision to change it would belong.
     *
     * <p><strong>The third arm is {@code else}, not a not-found-and-not-ok classification.</strong>
     * {@code WHEN OTHER} at {@code :252} enumerates no value, so it covers every response that is
     * neither {@code 0} nor {@code 13} - including the end-of-file and duplicate classifications the
     * repository can also report. Using
     * {@link SecUserRepository.ReadResult#isOther()} for this arm would therefore be wrong: it is true
     * only for the outcome the module calls {@code OTHER}, so an end-of-file result would fall through
     * every arm and produce no message at all.
     *
     * <p><strong>No exception is caught.</strong> The repository maps a backend refusal onto a read
     * outcome, which arrives on the third arm as the source intends. What it does <em>not</em> map is a
     * misconfigured dataset binding, which it raises as an {@link IllegalStateException}. That is a
     * defect in this module's wiring rather than a CICS response, and folding it into
     * {@link #MSG_UNABLE_TO_VERIFY} would hide a broken deployment behind a message that tells an
     * operator to try again. It propagates.
     *
     * @param context     the communication area, already carrying the normalised user id
     * @param wsUserId    {@code WS-USER-ID}: the upper-cased user id, exactly
     *                    {@value #USER_ID_LENGTH} characters, which is the {@code RIDFLD} and the
     *                    {@code KEYLENGTH} the read needs
     * @param wsUserPwd   {@code WS-USER-PWD}: the upper-cased submitted password, exactly
     *                    {@value #PASSWORD_LENGTH} characters. Used here and discarded
     * @param receive     the receive outcome to carry through
     * @param resolvedAid the token for the attention identifier, carried through
     * @return the outcome of this path
     */
    private SignOnOutcome readUserSecFile(NavigationContext context,
                                          String wsUserId,
                                          String wsUserPwd,
                                          ReceiveOutcome receive,
                                          Optional<PfKeyResolver.AidKey> resolvedAid) {

        // :211-219 EXEC CICS READ DATASET('USRSEC  ') RIDFLD(WS-USER-ID) KEYLENGTH(8). No UPDATE
        // option, so no record is held: the sign-on read takes no lock.
        SecUserRepository.ReadResult read = secUserRepository.read(wsUserId);
        Optional<FileStatus.Outcome> readOutcome = Optional.of(read.outcome());

        // :221 EVALUATE WS-RESP-CD, over raw numeric literals.
        if (read.isFound()) {
            // :222 WHEN 0.
            SecUserRecord secUserData = read.requireRecord();

            // :223 IF SEC-USR-PWD = WS-USER-PWD - the plaintext comparison, byte for byte across all
            // eight characters. SecUserRecord guarantees its components are exactly their declared
            // width, so no trimming or padding is interposed here.
            if (secUserData.secUsrPwd().equals(wsUserPwd)) {
                NavigationContext signedOn = context
                        // :224 MOVE WS-TRANID TO CDEMO-FROM-TRANID.
                        .withFromTranid(TRANSACTION_ID)
                        // :225 MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM.
                        .withFromProgram(PROGRAM_NAME)
                        // :226 MOVE WS-USER-ID TO CDEMO-USER-ID. Redundant - :134 already moved the same
                        // value into the same field - and performed anyway, because the source performs
                        // it and "harmless today" is not a licence to restructure.
                        .withUserId(wsUserId)
                        // :227 MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE. Exactly one character, straight
                        // from the record; this is the value the 88-levels are tested against.
                        .withUserType(secUserData.secUsrType())
                        // :228 MOVE ZEROS TO CDEMO-PGM-CONTEXT, which asserts 88 CDEMO-PGM-ENTER.
                        .withPgmEnter();

                // :230-240 IF CDEMO-USRTYP-ADMIN ... ELSE ... END-IF.
                return transferControl(signedOn, resolveNextProgram(signedOn), receive, resolvedAid,
                        readOutcome);
            }

            // :241-246 ELSE - the password did not match.
            // :242-243 MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE.
            // :244 MOVE -1 TO PASSWDL. :245 PERFORM SEND-SIGNON-SCREEN.
            //
            // There is deliberately NO 'Y' TO WS-ERR-FLG here. Every other failure path in the program
            // sets the flag; this one does not, and the asymmetry is preserved rather than harmonised.
            // Nothing downstream in COSGN00C reads the flag after this point - :138 has already been
            // evaluated - so the difference is visible in the returned state and nowhere else, which is
            // precisely why it would be easy to "tidy" and wrong to.
            return sendSignonScreen(context,
                    false,
                    movePicX(MSG_WRONG_PASSWORD, MESSAGE_LENGTH),
                    CursorField.PASSWORD,
                    false,
                    receive,
                    resolvedAid,
                    readOutcome);
        }

        if (read.isNotFound()) {
            // :247-251 WHEN 13, which is DFHRESP(NOTFND): there is no such user id in USRSEC.
            return sendSignonScreen(context,
                    true,
                    movePicX(MSG_USER_NOT_FOUND, MESSAGE_LENGTH),
                    CursorField.USER_ID,
                    false,
                    receive,
                    resolvedAid,
                    readOutcome);
        }

        // :252-256 WHEN OTHER - every response the source did not enumerate.
        return sendSignonScreen(context,
                true,
                movePicX(MSG_UNABLE_TO_VERIFY, MESSAGE_LENGTH),
                CursorField.USER_ID,
                false,
                receive,
                resolvedAid,
                readOutcome);
    }

    /**
     * {@code IF CDEMO-USRTYP-ADMIN ... ELSE ...} at {@code app/cbl/COSGN00C.cbl:230-240} - which program
     * a signed-on user goes to.
     *
     * <p>A <strong>two-way</strong> split, exactly as the source writes it. The condition tested is
     * {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'}, so every other value of {@code CDEMO-USER-TYPE} takes the
     * {@code ELSE} branch: {@code 'U'} does, and so does a space, a lower-case {@code 'a'}, and any
     * unexpected byte a corrupt record might carry. There is deliberately no third
     * "invalid user type" branch and no rejection of an unrecognised type - adding either would invent a
     * behaviour the program does not have, and would turn a signed-on user away.
     *
     * <p>The condition is asked of {@link NavigationContext#isAdmin()} rather than re-tested against a
     * raw character here, so the {@code 88}-level lives in exactly one place. It is deliberately not
     * expressed as the negation of {@link NavigationContext#isUser()}, which would report a blank type
     * as a regular user by accident rather than by rule.
     *
     * @param signedOn the communication area with {@code CDEMO-USER-TYPE} already set from
     *                 {@code SEC-USR-TYPE}
     * @return {@value #ADMIN_PROGRAM} or {@value #USER_PROGRAM}, space-padded to
     *         {@value #NEXT_PROGRAM_LENGTH} characters
     * @throws NullPointerException if {@code signedOn} is {@code null}
     */
    public String resolveNextProgram(NavigationContext signedOn) {
        Objects.requireNonNull(signedOn, "A communication area is required to read CDEMO-USER-TYPE from");
        // :230 IF CDEMO-USRTYP-ADMIN -> :232 PROGRAM('COADM01C'); :235 ELSE -> :237 PROGRAM('COMEN01C').
        String target = signedOn.isAdmin() ? ADMIN_PROGRAM : USER_PROGRAM;
        // Both literals are already eight characters, so this move is an identity - written anyway so
        // the receiver's declared width is stated at the point the value is produced.
        return movePicX(target, NEXT_PROGRAM_LENGTH);
    }

    // =================================================================================================
    // Section 10 - the three exits: SEND-SIGNON-SCREEN, SEND-PLAIN-TEXT and the XCTL pair.
    // =================================================================================================

    /**
     * {@code SEND-SIGNON-SCREEN} at {@code app/cbl/COSGN00C.cbl:145-157} followed by the
     * {@code EXEC CICS RETURN TRANSID} at {@code :98-102} - the ordinary exit, taken by six of the
     * program's eight paths.
     *
     * <p>The paragraph performs {@code POPULATE-HEADER-INFO}, moves {@code WS-MESSAGE} to
     * {@code ERRMSGO} and sends the map with {@code ERASE CURSOR}. Only the message and the cursor are
     * decisions; the header is presentation and belongs to the controller, which is why nothing here
     * reads a clock or an {@code APPLID}.
     *
     * @param context              the communication area to hand back
     * @param errorFlag            whether {@code WS-ERR-FLG} holds {@code 'Y'}
     * @param message              {@code WS-MESSAGE}, already at its declared width
     * @param cursorField          the length item that received the {@code MOVE -1}
     * @param resetAllOutputFields whether {@code MOVE LOW-VALUES TO COSGN0AO} ran first
     * @param receive              the receive outcome
     * @param resolvedAid          the resolved attention identifier
     * @param readOutcome          how the file read classified, or empty if no read happened
     * @return the outcome
     */
    private SignOnOutcome sendSignonScreen(NavigationContext context,
                                           boolean errorFlag,
                                           String message,
                                           CursorField cursorField,
                                           boolean resetAllOutputFields,
                                           ReceiveOutcome receive,
                                           Optional<PfKeyResolver.AidKey> resolvedAid,
                                           Optional<FileStatus.Outcome> readOutcome) {
        return new SignOnOutcome(false,
                spaces(ROLE_LENGTH),
                spaces(NEXT_PROGRAM_LENGTH),
                message,
                errorFlag,
                cursorField,
                true,
                resetAllOutputFields,
                false,
                Termination.RETURN_TRANSID,
                context,
                receive,
                resolvedAid,
                readOutcome);
    }

    /**
     * {@code SEND-PLAIN-TEXT} at {@code app/cbl/COSGN00C.cbl:162-172} - the PF3 exit.
     *
     * <pre>
     * EXEC CICS SEND TEXT FROM(WS-MESSAGE) LENGTH(LENGTH OF WS-MESSAGE)
     *           ERASE FREEKB END-EXEC.                                    :164-169
     * EXEC CICS RETURN END-EXEC.                                          :171-172
     * </pre>
     *
     * <p>Two things distinguish this exit from every other. The transmission is <em>unformatted text</em>
     * rather than a map, so no screen field is written and no cursor is positioned; and the
     * {@code EXEC CICS RETURN} carries <strong>no</strong> {@code TRANSID}, so the pseudo-conversation
     * ends and the {@code RETURN TRANSID} at {@code :98} is never reached. The communication area is
     * still handed back on the outcome, because the caller supplied it and the transaction did not
     * consume it.
     *
     * <p>The error flag stays clear: signing off with PF3 is not an error, and {@code :88-90} sets no
     * flag.
     *
     * @param context     the communication area as received
     * @param message     {@code WS-MESSAGE}, the thank-you text at its declared width
     * @param resolvedAid the resolved attention identifier
     * @return the outcome
     */
    private SignOnOutcome sendPlainText(NavigationContext context,
                                        String message,
                                        Optional<PfKeyResolver.AidKey> resolvedAid) {
        return new SignOnOutcome(false,
                spaces(ROLE_LENGTH),
                spaces(NEXT_PROGRAM_LENGTH),
                message,
                false,
                CursorField.NONE,
                false,
                false,
                true,
                Termination.RETURN_NO_TRANSID,
                context,
                ReceiveOutcome.NOT_PERFORMED,
                resolvedAid,
                Optional.empty());
    }

    /**
     * The {@code EXEC CICS XCTL} pair at {@code app/cbl/COSGN00C.cbl:231-239} - the only successful
     * exit.
     *
     * <p>Both statements pass {@code COMMAREA(CARDDEMO-COMMAREA)}, so the area travels to the next
     * program; here it travels on the outcome and the client sends it with its follow-up call. There is
     * no server-side forward, no redirect and no session affinity: the target is a response field, which
     * is what keeps the transaction stateless.
     *
     * <p>No message is produced and no screen is painted - {@code XCTL} does not return, so
     * {@code SEND-SIGNON-SCREEN} is not performed and {@code :98} is not reached.
     *
     * @param signedOn    the communication area with the five sign-on fields set
     * @param nextProgram the transfer target, at its declared width
     * @param receive     the receive outcome
     * @param resolvedAid the resolved attention identifier
     * @param readOutcome how the file read classified
     * @return the outcome
     */
    private SignOnOutcome transferControl(NavigationContext signedOn,
                                          String nextProgram,
                                          ReceiveOutcome receive,
                                          Optional<PfKeyResolver.AidKey> resolvedAid,
                                          Optional<FileStatus.Outcome> readOutcome) {
        return new SignOnOutcome(true,
                signedOn.userType(),
                nextProgram,
                spaces(MESSAGE_LENGTH),
                false,
                CursorField.NONE,
                false,
                false,
                false,
                Termination.XCTL,
                signedOn,
                receive,
                resolvedAid,
                readOutcome);
    }

    // =================================================================================================
    // Section 11 - the COBOL primitives this program needs.
    //
    // Four of them: the two figurative constants, the alphanumeric MOVE rule and FUNCTION UPPER-CASE.
    // They are written here rather than delegated because this service's only collaborator is its
    // repository - the migration plan gives it no fixed-width codec, and it needs none: it owns exactly
    // one fixed-width receiver, WS-MESSAGE PIC X(80), and two eight-character screen fields.
    //
    // No character conversion happens anywhere in this class. Nothing is encoded to bytes, nothing is
    // decoded from bytes, and the platform default charset - which is never correct for mainframe data -
    // is never consulted. The code page belongs to the repository, which is given it explicitly.
    // =================================================================================================

    /**
     * {@code FUNCTION UPPER-CASE} as {@code app/cbl/COSGN00C.cbl:132} and {@code :135} apply it: fold
     * {@code a}-{@code z} to {@code A}-{@code Z} and leave every other character exactly as it is.
     *
     * <p><strong>Why the fold is written out instead of calling {@link String#toUpperCase()}.</strong>
     * Two reasons, and both are correctness rather than taste:
     *
     * <ol>
     *   <li><strong>{@code String.toUpperCase()} can change a string's length.</strong> The sharp s
     *       upper-cases to two characters, so a submitted {@code stra&szlig;e} would come back nine
     *       characters wide from an eight-character field. That would break the invariant this whole
     *       method exists to protect: {@link SecUserRepository#read(String)} requires a key of exactly
     *       {@value #USER_ID_LENGTH} characters and rejects anything else, and
     *       {@link NavigationContext} rejects an over-long {@code CDEMO-USER-ID}. A COBOL
     *       {@code FUNCTION UPPER-CASE} cannot change a field's length: it returns the same number of
     *       character positions it was given.</li>
     *   <li><strong>{@code String.toUpperCase()} without a locale is locale-sensitive.</strong> In a
     *       Turkish locale it folds {@code i} to a dotted capital {@code I}, so the same user id would
     *       sign on or fail depending on the JVM's default locale. A COBOL fold is a property of the
     *       code page, not of an operator's regional settings.</li>
     * </ol>
     *
     * <p>Restricting the fold to the twenty-six unaccented letters is also faithful to the data: the
     * {@code USRSEC} records seeded by {@code app/jcl/DUSRSECJ.jcl:35-44} are upper-case Latin letters
     * and digits throughout, and every other byte a single-byte code page can carry is left untouched -
     * which is what {@code FUNCTION UPPER-CASE} does with it.
     *
     * <p>Exposed publicly because it is a faithful reproduction of a COBOL intrinsic whose two
     * deliberate departures from the obvious Java call deserve to be assertable on their own.
     *
     * @param value the value to fold; any width
     * @return the folded value, the same length as {@code value}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static String upperCase(String value) {
        Objects.requireNonNull(value, "FUNCTION UPPER-CASE takes a field, and a COBOL field is never "
                + "null; pass spaces or LOW-VALUES for an empty one");
        StringBuilder folded = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= LOWER_CASE_A && character <= LOWER_CASE_Z) {
                folded.append((char) (character - CASE_FOLD_OFFSET));
            } else {
                folded.append(character);
            }
        }
        return folded.toString();
    }

    /**
     * One symbolic-map {@code xxxI} item as CICS delivers it: exactly its declared width, with a field
     * the terminal never transmitted arriving as {@code LOW-VALUES}.
     *
     * <p>This is the seam where an absent JSON member becomes the figurative constant {@code :118} and
     * {@code :123} test for. A {@code null} value means the member was not supplied, which is the same
     * fact CICS records by leaving the input item at {@code X'00'} - so it becomes {@code LOW-VALUES} of
     * the declared width. A value that <em>was</em> supplied goes through the alphanumeric {@code MOVE}
     * rule instead, so a short value is space-padded on the right, exactly as CICS pads a received field
     * into its wider symbolic-map item.
     *
     * <p>The two cases are kept distinct on purpose: mapping {@code null} to spaces would produce the
     * same message but the wrong bytes, and a field-for-field diff of the returned communication area
     * would report it.
     *
     * @param received the value as received, or {@code null} if it was not supplied
     * @param width    the item's declared width
     * @return the image, exactly {@code width} characters
     */
    private static String receivedFieldImage(String received, int width) {
        return received == null ? lowValues(width) : movePicX(received, width);
    }

    /**
     * Whether {@code = SPACES OR LOW-VALUES} holds for a screen field -
     * {@code app/cbl/COSGN00C.cbl:118} and {@code :123}.
     *
     * <p>Two conditions, tested independently, exactly as the source writes them. A field of all spaces
     * satisfies the first; a field of all {@code X'00'} satisfies the second; <strong>a field that mixes
     * them satisfies neither</strong> and falls through to {@code WHEN OTHER}, where it is treated as
     * data and used as a key. That last case is why this cannot be a Java emptiness or blank test:
     * {@link String#isBlank()} would report a run of {@code X'00'} as non-blank while
     * {@code String.trim().isEmpty()} would report a mixed field as empty, and both would answer
     * differently from the COBOL.
     *
     * @param image the field image, at its declared width
     * @return {@code true} when the image is all spaces or all low-values
     */
    private static boolean isSpacesOrLowValues(String image) {
        return image.equals(spaces(image.length())) || image.equals(lowValues(image.length()));
    }

    /**
     * The COBOL alphanumeric {@code MOVE} rule for a {@code PIC X} receiver: left-justify the sender in
     * the receiver, pad on the right with spaces if the sender is shorter, and truncate on the right if
     * it is longer.
     *
     * <p>Stated explicitly at every call site rather than left to a plain Java assignment, which would
     * neither pad nor truncate. The direction of the loss matters and is chosen deliberately: a
     * {@code PIC X} receiver loses its <em>rightmost</em> characters, where a {@code PIC 9} receiver
     * would lose its leftmost digits. Every receiver this service writes is {@code PIC X}.
     *
     * <p>The module's general fixed-width codec owns this rule for records; this service holds no codec,
     * so the rule is applied here for the three receivers it owns - {@code WS-MESSAGE PIC X(80)},
     * {@code USERIDI PIC X(8)} and {@code PASSWDI PIC X(8)} - plus the eight-character transfer target.
     *
     * @param value the sending value
     * @param width the receiver's declared width
     * @return the value at exactly {@code width} characters
     */
    private static String movePicX(String value, int width) {
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        return value + spaces(width - value.length());
    }

    /**
     * COBOL's {@code SPACES} figurative constant, filled to a width.
     *
     * @param width the number of characters
     * @return a run of spaces
     */
    private static String spaces(int width) {
        return String.valueOf(SPACE).repeat(width);
    }

    /**
     * COBOL's {@code LOW-VALUES} figurative constant, filled to a width: the {@code X'00'} bytes CICS
     * leaves in a symbolic-map input item for a field the terminal never transmitted.
     *
     * @param width the number of characters
     * @return a run of low-values
     */
    private static String lowValues(int width) {
        return String.valueOf(LOW_VALUE).repeat(width);
    }

    /**
     * The two-character upper-case hexadecimal image of a byte, for diagnostics.
     *
     * <p>The byte is masked to eight bits first. Without the mask a negative {@code byte} - and most
     * attention identifiers are negative as Java bytes, PF3 being {@code 0xF3} - would widen to a
     * negative {@code int} and render as eight {@code F}-prefixed digits. The digits are taken from an
     * immutable {@link String} rather than a {@code char} array so that this class holds no mutable
     * static state of any kind.
     *
     * @param value the byte
     * @return two upper-case hexadecimal digits
     */
    private static String hexImage(byte value) {
        int unsigned = value & 0xFF;
        return String.valueOf(HEX_DIGITS.charAt(unsigned >>> 4))
                + HEX_DIGITS.charAt(unsigned & 0x0F);
    }

    /** The hexadecimal digits {@link #hexImage(byte)} renders with; a String, so it is immutable. */
    private static final String HEX_DIGITS = "0123456789ABCDEF";
}
