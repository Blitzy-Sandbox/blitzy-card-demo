package com.vsergeychik.carddemo.user;

import com.vsergeychik.carddemo.common.AidRequestParameter;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.user.SignOnService.CursorField;
import com.vsergeychik.carddemo.user.SignOnService.MapInputArea;
import com.vsergeychik.carddemo.user.SignOnService.SignOnInput;
import com.vsergeychik.carddemo.user.SignOnService.SignOnOutcome;
import com.vsergeychik.carddemo.user.dto.SignOnRequest;
import com.vsergeychik.carddemo.user.dto.SignOnResponse;
import jakarta.validation.Valid;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP surface of {@code app/cbl/COSGN00C.cbl} - CICS transaction {@code CC00}, mapset
 * {@code COSGN00}, map {@code COSGN0A}, the CardDemo sign-on screen.
 *
 * <p>This class is deliberately thin, and thinness here is a structural requirement rather than a
 * matter of taste. It does four things in a straight line - <strong>bind, translate, delegate,
 * project</strong> - and nothing else:
 *
 * <ol>
 *   <li>bind the payload, treating an absent body as the cold start;</li>
 *   <li>translate it into the three values {@code COSGN00C} actually consults, via
 *       {@link #toInput(SignOnRequest)};</li>
 *   <li>delegate to {@link SignOnService#handle(SignOnInput)} <em>exactly once</em>;</li>
 *   <li>project the outcome onto the screen and its presentation metadata, via
 *       {@link #toResponse(SignOnOutcome)} and {@link #toMetadata(SignOnOutcome)}.</li>
 * </ol>
 *
 * <p><strong>There is no authentication decision in this file.</strong> Not a comparison, not a
 * branch, not a guard. {@code EVALUATE EIBAID} at {@code app/cbl/COSGN00C.cbl:85-95}, the two
 * blank-field tests at {@code :118} and {@code :123}, the plaintext password comparison at
 * {@code :223} and the {@code EVALUATE WS-RESP-CD} at {@code :221-257} all live in
 * {@link SignOnService}. That split is what lets a JUnit test drive every branch of the program by
 * constructing a plain record, with no {@code MockMvc}, no servlet type and no {@code JobLauncher} in
 * the path - which is what makes the module's 90%-or-better <em>branch</em> coverage bar reachable.
 * The branches that do exist below are transport concerns: decoding the attention identifier that
 * arrived on the wire, and naming the screen field the program asked the cursor to land on.
 *
 * <h2>The password is accepted in the clear and never comes back</h2>
 *
 * <p>{@link SignOnRequest#passwd()} carries the submitted password verbatim, because {@code :223}
 * compares {@code SEC-USR-PWD} in plaintext and this is a like-for-like migration whose acceptance
 * test is a field-for-field diff. {@link SignOnService} documents at length why hashing it would be a
 * behaviour change and why Spring Security, JWT and BCrypt are named exclusions from the migration's
 * closed dependency set. Nothing here revisits that decision.
 *
 * <p>Nothing here <em>weakens</em> it either, and three properties enforce that structurally rather
 * than by convention:
 *
 * <ul>
 *   <li><strong>It is never echoed.</strong> {@link SignOnResponse} has no {@code passwd} component
 *       at all - {@link SignOnResponse#OMITTED_FIELD} records the omission, and the response projects
 *       {@value SignOnResponse#MAP_FIELD_COUNT} of the screen's
 *       {@value SignOnResponse#MAPSET_NAMED_FIELD_COUNT} named fields for exactly that reason. So the
 *       password cannot reach a client even by mistake. That matches the source: {@code
 *       SEND-SIGNON-SCREEN} at {@code :145-157} transmits {@code COSGN0AO} after writing only the
 *       header fields and the message, and the program never writes {@code PASSWDO}.</li>
 *   <li><strong>It is never logged.</strong> This class holds no logger, emits no diagnostic and
 *       renders no request. It could not print the credential if it tried.</li>
 *   <li><strong>It is never retained.</strong> It is read once inside {@link #toInput(SignOnRequest)}
 *       as a method-local expression, handed to the service, and dropped. It never becomes a field.
 *       {@link SignOnRequest#toString()} and {@link SignOnInput#toString()} both redact it, so even a
 *       stack trace that renders one of them cannot disclose it.</li>
 * </ul>
 *
 * <h2>No server-side state, by construction</h2>
 *
 * <p>CICS is pseudo-conversational: the transaction paints a screen, ends, and is re-entered from the
 * top with only the communication area surviving. That shape is preserved exactly. All conversation
 * state - the 160-byte {@code CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy}, the attention
 * identifier, and the screen's own field values - travels in the request and response payloads. There
 * is no {@code HttpSession}, no {@code @SessionAttributes}, no session or request scope, no
 * {@code ThreadLocal} and no cache anywhere in this class.
 *
 * <p>The four instance fields are all {@code final} and all immutable or effectively so
 * ({@link SignOnService} is stateless, {@link Clock} is immutable and thread-safe,
 * {@link FixedWidthCodec} is immutable, and the two identifier images and the cold-start payload are
 * immutable values). The only static state is {@code final} and immutable. One bean therefore serves
 * every concurrent request safely, and two successive requests cannot influence one another.
 *
 * <h2>First entry is {@code EIBCALEN}, not the re-enter flag</h2>
 *
 * <p>{@code :80} tests {@code IF EIBCALEN = 0}. {@code COSGN00C} is the odd one out among the five
 * {@code user} programs: the four {@code COUSR0x} programs test {@code IF NOT CDEMO-PGM-REENTER}, and
 * {@code COSGN00C} never reads {@code CDEMO-PGM-CONTEXT} at all. So first entry is modelled as
 * <em>an absent communication area</em> and nothing else. {@link SignOnRequest#inEnterState()} and
 * {@link SignOnRequest#inReenterState()} exist on the payload for the screens that need them and are
 * deliberately <strong>not consulted here</strong>; substituting them would invent a test the program
 * does not perform.
 *
 * <h2>Presentation metadata travels beside the screen, never inside it</h2>
 *
 * <p>Two things {@code COSGN00C} produces are metadata by declaration and have no {@code DFHMDF}
 * field to travel in: the {@code MOVE -1 TO USERIDL} / {@code MOVE -1 TO PASSWDL} cursor request, and
 * the {@code MOVE LOW-VALUES TO COSGN0AO} full-repaint instruction at {@code :81}. The {@code xxxL}
 * items are {@code COMP PIC S9(4)} input-group metadata and must never become payload members, so
 * both are reported in {@link ScreenMetadata} inside the shared {@link ScreenResponse} envelope -
 * separate from the screen, but present. Dropping them would leave a client unable to place the cursor
 * where the program asked.
 *
 * <p><strong>No colour attribute is set, because the program sets none.</strong>
 * {@link ScreenMetadata#messageColour()} is left absent rather than filled in. Two facts justify that
 * and both were verified in the source: {@code COSGN00C} contains no {@code MOVE} to any {@code xxxC}
 * item, and it does not copy {@code app/cpy/CSSETATY.cpy}, the include that turns a field red and
 * stamps an asterisk into it. Note also that {@code app/cbl/COSGN00C.cbl:59} reads
 * {@code *COPY DFHATTR.} - the asterisk is in column 7, so the copy is <strong>commented out</strong>
 * and no {@code DFHATTR} constant resolves into this program at all. Inventing a {@code DFHRED} move
 * here would be adding behaviour.
 *
 * <h2>Always {@code 200 OK}</h2>
 *
 * <p>Every path through {@code COSGN00C} ends in a successful CICS exit - an {@code EXEC CICS XCTL}
 * at {@code :231-239}, the {@code EXEC CICS RETURN TRANSID} at {@code :98-102}, or the bare
 * {@code EXEC CICS RETURN} at {@code :171-172}. A refused sign-on is a message on the screen, not a
 * {@code 4xx}: the program moves {@code 'Wrong Password. Try again ...'} into {@code WS-MESSAGE} and
 * repaints. A blank user id is likewise answered with {@code 'Please enter User ID ...'}, which is why
 * the request carries no {@code @NotBlank} - a {@code 400} would replace an observable screen message
 * with a protocol error and change behaviour. The only non-{@code 200} responses this endpoint can
 * produce come from the module's global error mapper: a payload exceeding a declared field width, or a
 * body that will not parse.
 *
 * <h2>Provenance</h2>
 *
 * <p>No project-specific rules were supplied for this migration - the rules document contains the
 * single line "No user rules provided." Their absence is not treated as licence to relax anything; the
 * enterprise practices the plan substitutes govern in their place, and the ones that bind this file
 * are <strong>B6</strong> (neither weaken nor unrequestedly strengthen the security posture),
 * <strong>B8</strong> (explicit over implicit - no wildcard import, every width and code page named),
 * <strong>B9</strong> (no static mutable state, constructor injection only), <strong>B10</strong>
 * (decisions live in the service so tests can reach them) and <strong>B12</strong> (a CICS construct
 * with no Java equivalent is surfaced explicitly, never silently dropped).
 *
 * @see SignOnService for every decision the program makes
 * @see SignOnRequest for the inbound screen and its eleven named fields
 * @see SignOnResponse for the projected screen
 */
@RestController
public class SignOnController {

    // =================================================================================================
    // The HTTP route. One route, and no others - app/csd/CARDDEMO.CSD:378 defines exactly one
    // transaction against this program.
    // =================================================================================================

    /**
     * {@code POST /api/signon} - the single route this controller publishes.
     *
     * <p>{@code app/csd/CARDDEMO.CSD:378} is
     * {@code DEFINE TRANSACTION(CC00) ... PROGRAM(COSGN00C)}: one transaction, one program, so one
     * route. {@code POST} rather than {@code GET} because the request body carries a credential, which
     * must not appear in a query string, a URL or an access log.
     */
    public static final String SIGNON_PATH = "/api/signon";

    /**
     * The payload member carrying the attention identifier, spelled as the client sends it.
     *
     * <p>Named in a refusal so a caller whose body states the key knows which member contradicted the
     * raw byte it also sent. Its value is the one-character {@code EIBAID} image - see
     * {@link #toEibAid(String)} - not the folded {@code CCARD-AID} token.
     */
    static final String AID_MEMBER = "aid";

    /**
     * Query parameter carrying the raw {@code EIBAID} byte as an unsigned {@code 0}-{@code 255} value.
     *
     * <p>The name is {@link AidRequestParameter#CANONICAL_NAME}, shared with every other online route.
     * This is the authoritative form and the only one that can distinguish the twelve folded pairs:
     * {@code app/cbl/COSGN00C.cbl:85} evaluates {@code EIBAID} itself, and {@code WHEN DFHPF3} at
     * {@code :88} is an equality against one byte that {@code DFHPF15} does not satisfy.
     *
     * <p>It is a query parameter and not a body member because it is not a credential and not a screen
     * field; the two members that are credentials stay in the body, which is why this route is
     * {@code POST}.
     */
    public static final String EIBAID_PARAM = AidRequestParameter.CANONICAL_NAME;

    /** The accepted alternate spelling of {@value #EIBAID_PARAM} - {@link AidRequestParameter}. */
    public static final String EIBAID_PARAM_ALIAS = AidRequestParameter.ALTERNATE_NAME;

    /** The lowest value an {@code EIBAID} byte can take, stated unsigned. */
    static final int AID_MIN = 0;

    /** The highest value an {@code EIBAID} byte can take, stated unsigned. */
    static final int AID_MAX = 255;

    /**
     * The width of the raw {@code EIBAID} form of the payload's {@code aid} member: one character.
     *
     * <p>{@link SignOnRequest#AID_LENGTH} is the width of the {@code CCARD-AID} token the responses
     * publish, which is five; this is the width of the byte itself.
     */
    static final int RAW_AID_LENGTH = 1;

    /**
     * The highest code point an attention identifier can hold: {@code U+00FF}.
     *
     * <p>{@code EIBAID} is one byte, so the whole AID space is {@code U+0000} to {@code U+00FF}.
     */
    static final char MAX_AID_CODE_POINT = 0x00FF;

    // =================================================================================================
    // Configuration keys for the two EXEC CICS ASSIGN values. See the fields they populate.
    // =================================================================================================

    /**
     * Configuration key supplying {@code APPLIDO} - {@code carddemo.cics.applid}.
     *
     * <p>{@code app/cbl/COSGN00C.cbl:198-200} obtains this field from {@code EXEC CICS ASSIGN
     * APPLID(...)}, a CICS region property with no Java equivalent, so it is bound from configuration
     * rather than computed. <strong>Required, with no default</strong> - see
     * {@link #requireRegionIdentity(String, String, String)}.
     */
    public static final String APPLID_PROPERTY = "carddemo.cics.applid";

    /**
     * Configuration key supplying {@code SYSIDO} - {@code carddemo.cics.sysid}.
     *
     * <p>{@code app/cbl/COSGN00C.cbl:202-204} obtains this field from {@code EXEC CICS ASSIGN
     * SYSID(...)}, likewise a region property, and likewise required with no default.
     */
    public static final String SYSID_PROPERTY = "carddemo.cics.sysid";

    /** The environment variable {@code application.yml} reads {@link #APPLID_PROPERTY} from. */
    public static final String APPLID_ENVIRONMENT_VARIABLE = "CARDDEMO_CICS_APPLID";

    /** The environment variable {@code application.yml} reads {@link #SYSID_PROPERTY} from. */
    public static final String SYSID_ENVIRONMENT_VARIABLE = "CARDDEMO_CICS_SYSID";

    /**
     * The code page applied to the {@code PIC X} move rule, {@link StandardCharsets#US_ASCII}.
     *
     * <p>Named explicitly and never taken from the platform default (practice <strong>B8</strong>).
     * {@code US-ASCII} is the code page of the {@code app/data/ASCII} fixtures the parity harness
     * seeds, and it is the same default the sibling controllers in this package apply to screen and
     * message text.
     */
    public static final Charset DEFAULT_WORKING_STORAGE_CHARSET = StandardCharsets.US_ASCII;

    /**
     * The {@code SPACES} figurative constant, as the sending operand of a {@code MOVE}.
     *
     * <p>A COBOL {@code PIC X} field is never absent - an empty one holds spaces - so every blank field
     * below is produced by moving this into the field's declared width through
     * {@link FixedWidthCodec#movePicX}, which fills the receiver on the right. One space is all a sender
     * needs: {@code MOVE SPACES TO} a {@code PIC X(78)} field blanks all seventy-eight, and writing the
     * width at the call site rather than in the literal is what keeps each width traceable to its
     * copybook item.
     */
    private static final String SPACES = " ";

    // =================================================================================================
    // The attention-identifier decode table.
    //
    // SignOnRequest carries EIBAID as the five-character CCARD-AID token common.PfKeyResolver
    // produces, not as a raw byte, and SignOnService.handle takes the raw byte that :85 compares. This
    // table is the one place that gap is closed. It is a decode of a transport field and not a
    // decision: every arm below round-trips, so PfKeyResolver.resolve(decode(k.token())) is k again for
    // all sixteen keys, and the SignOnControllerTest asserts precisely that.
    //
    // The map is static and final and Map.ofEntries returns an immutable map, so this is not mutable
    // static state (practice B9). Each token is taken from AidKey.token() rather than retyped, so a
    // literal cannot drift out of step with the enum - which also preserves the two trailing spaces on
    // PA1 and PA2 that a hand-typed 'PA1' would silently lose.
    // =================================================================================================

    // =================================================================================================
    // The two cursor targets, named by their DFHMDF label.
    //
    // ScreenMetadata reports the cursor by the field's DFHMDF label, not by its xxxL item name, so the
    // trailing L of USERIDL and PASSWDL is not part of these values. Both labels are read from
    // app/bms/COSGN00.bms rather than derived from the length item, so a reader can check them against
    // the mapset directly.
    // =================================================================================================

    /**
     * The {@code DFHMDF} label of the user-id field, {@code USERID} - {@code app/bms/COSGN00.bms:156}.
     *
     * <p>The target of {@code MOVE -1 TO USERIDL OF COSGN0AI} at {@code app/cbl/COSGN00C.cbl:82} (cold
     * start), {@code :121} (blank user id), {@code :250} (user not found) and {@code :255} (unable to
     * verify).
     */
    public static final String CURSOR_USERID = "USERID";

    /**
     * The {@code DFHMDF} label of the password field, {@code PASSWD} -
     * {@code app/bms/COSGN00.bms:175}.
     *
     * <p>The target of {@code MOVE -1 TO PASSWDL OF COSGN0AI} at {@code app/cbl/COSGN00C.cbl:126}
     * (blank password) and {@code :244} (wrong password).
     */
    public static final String CURSOR_PASSWD = "PASSWD";

    // =================================================================================================
    // State. Four collaborators and two values, all final, none mutable (practice B9).
    // =================================================================================================

    /** The translated {@code COSGN00C}: every decision this endpoint reports was taken here. */
    private final SignOnService signOnService;

    /**
     * The module's clock bean, read only through {@link DateHeader#from(FixedWidthCodec, Clock)}.
     *
     * <p>{@code POPULATE-HEADER-INFO} reads {@code FUNCTION CURRENT-DATE} at
     * {@code app/cbl/COSGN00C.cbl:179}. Injecting the clock rather than calling
     * {@code LocalDateTime.now()} is what lets a parity case pin the instant and compare the two header
     * fields byte for byte; this class never calls {@code now()} of its own.
     */
    private final Clock clock;

    /**
     * The {@code PIC X} move rule at the declared code page - the only way a value reaches a screen
     * field here.
     *
     * <p>{@link SignOnService} deliberately holds no codec, because the one fixed-width receiver it
     * owns is {@code WS-MESSAGE}. The screen belongs to this class, so the codec does too.
     */
    private final FixedWidthCodec codec;

    /**
     * {@code APPLIDO}, exactly {@value SignOnResponse#APPLID_LENGTH} characters.
     *
     * <p>{@code app/cbl/COSGN00C.cbl:198-200} fills this field from {@code EXEC CICS ASSIGN
     * APPLID(APPLIDO OF COSGN0AO)}. There is no Java equivalent of that statement: the application
     * identifier is a property of the CICS region, which is precisely what this module replaces. Per
     * practice <strong>B12</strong> the field is therefore kept and populated from configuration rather
     * than dropped - it is one of the eleven named {@code DFHMDF} fields, and this is the only screen
     * in the application that carries it.
     *
     * <p><strong>There is no default, and spaces are not accepted.</strong> A deployment states its
     * region through {@value #APPLID_PROPERTY}, and one that states nothing is refused at startup
     * rather than serving a blank field: this is one of the eleven fields a field-for-field diff
     * compares, every {@code COSGN00C} parity case expects a real region name in it, and a blank is
     * indistinguishable on the wire from a region that answered nothing - so a silent default would
     * turn a missing deployment input into a wrong screen nobody notices. An invented literal is
     * equally out of the question: that is fabricated data in a compared field. Brought to the declared
     * width by the {@code PIC X} rule, so a short configured value is padded on the right and an
     * over-long one is truncated on the right, exactly as a {@code MOVE} into {@code PIC X(8)} behaves.
     */
    private final String applId;

    /**
     * {@code SYSIDO}, exactly {@value SignOnResponse#SYSID_LENGTH} characters.
     *
     * <p>From {@code EXEC CICS ASSIGN SYSID(SYSIDO OF COSGN0AO)} at
     * {@code app/cbl/COSGN00C.cbl:202-204}, and handled exactly as {@link #applId} is - required, with
     * no default - through {@value #SYSID_PROPERTY}. {@code app/bms/COSGN00.bms:89-93} does declare this
     * field {@code INITIAL='        '}, eight spaces, but that is the <em>unpainted</em> state of the
     * map before the program runs, not what {@code ASSIGN} puts there: the paragraph that sends the
     * screen always overwrites it, so spaces on a painted screen would describe a region that does not
     * exist.
     */
    private final String sysId;

    /**
     * The payload a request carrying no body is treated as having presented.
     *
     * <p>All eleven screen fields are space-filled to their declared widths, because a COBOL
     * {@code PIC X} field is never absent. The two members that carry meaning on this path are the
     * communication area, which is {@code null} and is how {@code IF EIBCALEN = 0} at
     * {@code app/cbl/COSGN00C.cbl:80} is encoded, and the attention identifier, which is
     * {@code 'ENTER'} - immaterial here, because the program diverts at {@code :80} and never reaches
     * the {@code EVALUATE EIBAID} at {@code :85}, but the only key a terminal could actually have
     * presented.
     *
     * <p>Built once at construction and shared. {@link SignOnRequest} is an immutable record, so a
     * single instance is safe for concurrent use and cannot be altered by a request that receives it.
     */
    private final SignOnRequest coldStartRequest;

    // =================================================================================================
    // Construction. Constructor injection only - no field injection, no setter (practice B9).
    // =================================================================================================

    /**
     * The bean constructor, applying {@link #DEFAULT_WORKING_STORAGE_CHARSET}.
     *
     * <p>Both identifiers are bound with a <strong>bare placeholder</strong>, so a deployment that
     * declares neither the property nor its environment variable fails the refresh naming the exact key
     * - the same treatment {@code CobolCharsetConfig} gives the three code pages, and for the same
     * reason: a value that decides what a compared field contains must never be defaulted.
     *
     * @param signOnService the translated {@code COSGN00C}; must not be {@code null}
     * @param clock         the source of {@code FUNCTION CURRENT-DATE}; must not be {@code null}
     * @param applId        {@code EXEC CICS ASSIGN APPLID}, from {@value #APPLID_PROPERTY}; required,
     *                      and must hold text
     * @param sysId         {@code EXEC CICS ASSIGN SYSID}, from {@value #SYSID_PROPERTY}; required, and
     *                      must hold text
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if either identifier holds no text
     */
    @Autowired
    public SignOnController(final SignOnService signOnService,
            final Clock clock,
            @Value("${" + APPLID_PROPERTY + "}") final String applId,
            @Value("${" + SYSID_PROPERTY + "}") final String sysId) {
        this(signOnService, clock, applId, sysId, DEFAULT_WORKING_STORAGE_CHARSET);
    }

    /**
     * The full constructor, taking the {@code WORKING-STORAGE} code page explicitly.
     *
     * @param signOnService         the translated {@code COSGN00C}; must not be {@code null}
     * @param clock                 the source of {@code FUNCTION CURRENT-DATE}; must not be
     *                              {@code null}
     * @param applId                the application identifier; must not be {@code null}, must hold
     *                              text, and is brought to {@value SignOnResponse#APPLID_LENGTH}
     *                              characters by the {@code PIC X} rule
     * @param sysId                 the system identifier; must not be {@code null}, must hold text, and
     *                              is brought to {@value SignOnResponse#SYSID_LENGTH} characters the
     *                              same way
     * @param workingStorageCharset the code page applied to screen and message text; must not be
     *                              {@code null}, and is never defaulted from the platform
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if either identifier holds no text
     */
    public SignOnController(final SignOnService signOnService,
            final Clock clock,
            final String applId,
            final String sysId,
            final Charset workingStorageCharset) {
        this.signOnService = Objects.requireNonNull(signOnService,
                "A SignOnService is required: it is the translated COSGN00C, and this controller makes "
                        + "no sign-on decision of its own");
        this.clock = Objects.requireNonNull(clock,
                "A Clock is required: POPULATE-HEADER-INFO at app/cbl/COSGN00C.cbl:177-204 renders the "
                        + "date and time header, and reading the wall clock directly would make that "
                        + "header impossible to assert byte for byte");
        requireRegionIdentity(applId, APPLID_PROPERTY, APPLID_ENVIRONMENT_VARIABLE);
        requireRegionIdentity(sysId, SYSID_PROPERTY, SYSID_ENVIRONMENT_VARIABLE);
        Objects.requireNonNull(workingStorageCharset, "A code page is required for the PIC X move rule; "
                + "it is never the platform default");
        this.codec = new FixedWidthCodec(workingStorageCharset);
        this.applId = this.codec.movePicX(applId, SignOnResponse.APPLID_LENGTH);
        this.sysId = this.codec.movePicX(sysId, SignOnResponse.SYSID_LENGTH);
        this.coldStartRequest = coldStartRequest(this.codec);
    }

    /**
     * Refuses a region identifier that is absent or blank, naming what to set.
     *
     * <p>The check is here, in the one constructor both paths run through, rather than only on the
     * {@code @Value} path - so there is no seam through which a blank identifier can reach a painted
     * screen. It refuses <em>blank</em> as well as absent because the two are the same failure wearing
     * different clothes: an environment variable exported empty, a YAML key left with nothing after the
     * colon, and a placeholder that resolved to the empty string all arrive here as text with no
     * content, and all of them would put spaces in a field the parity harness compares against a real
     * region name.
     *
     * <p>What it does <strong>not</strong> police is width or spelling. An over-long value is truncated
     * on the right by the {@code PIC X} move rule that follows, because that is what {@code MOVE} into
     * {@code PIC X(8)} does and a deployment whose region name is longer than the map's field is a
     * parity question rather than a configuration error.
     *
     * @param identity            the configured value; may be {@code null}, which is refused
     * @param property            the property that supplies it
     * @param environmentVariable the environment variable {@code application.yml} reads it from
     * @throws NullPointerException  if {@code identity} is {@code null}
     * @throws IllegalStateException if {@code identity} holds no text
     */
    static void requireRegionIdentity(final String identity, final String property,
            final String environmentVariable) {
        Objects.requireNonNull(identity, "A CICS region identity is required for " + property
                + "; supply it through the environment variable " + environmentVariable + '.');
        if (identity.isBlank()) {
            throw new IllegalStateException("The CICS region identity " + property + " is configured "
                    + "but holds no text, so the sign-on screen would paint spaces in a field every "
                    + "COSGN00C parity case expects a region name in. Set " + property + " - normally "
                    + "through the environment variable " + environmentVariable + " - to the value "
                    + "EXEC CICS ASSIGN would have reported. There is deliberately no default: this "
                    + "field is compared byte for byte, so neither a blank nor an invented literal is "
                    + "an acceptable stand-in.");
        }
    }

    /**
     * Builds the payload that stands in for an absent request body: every screen field at its declared
     * width carrying the unpainted image.
     *
     * <p>{@code MOVE LOW-VALUES TO COSGN0AO} at {@code app/cbl/COSGN00C.cbl:81} is what a first entry
     * finds, so {@code X'00'} - not spaces - is what a field nobody typed into holds. The service
     * already relies on that distinction: {@code SignOnService.receivedFieldImage} maps an absent JSON
     * member to {@code LOW-VALUES} precisely because CICS leaves an untransmitted input item at
     * {@code X'00'}, and {@code :118} and {@code :123} then test {@code = SPACES OR LOW-VALUES} as two
     * separate conditions. An earlier revision of this method space-filled instead, which made a cold
     * start disagree with an omitted member about the same fact.
     *
     * <p>{@code USERIDO} and {@code PASSWDO} are the two fields where this is observable: neither is
     * ever a {@code MOVE} target anywhere in {@code COSGN00C}, so they are never painted. The six header
     * fields are overwritten by {@code POPULATE-HEADER-INFO} at {@code :181-197}, {@code APPLIDO} and
     * {@code SYSIDO} by the two {@code EXEC CICS ASSIGN} calls at {@code :198-204}, and {@code ERRMSGO}
     * by the unconditional {@code MOVE SPACES TO WS-MESSAGE, ERRMSGO} at {@code :78-79} - so for those
     * nine the starting image is not visible in the response either way.
     *
     * @param codec the codec supplying each declared width; retained in the signature because the
     *              widths are read through it and a future field may need the {@code MOVE} rule
     * @return the cold-start payload, never {@code null}
     * @see ScreenFieldImage#unpainted(int)
     */
    private static SignOnRequest coldStartRequest(final FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to size the cold-start payload");
        return new SignOnRequest(ScreenFieldImage.unpainted(SignOnRequest.TRNNAME_LENGTH),
                ScreenFieldImage.unpainted(SignOnRequest.TITLE01_LENGTH),
                ScreenFieldImage.unpainted(SignOnRequest.CURDATE_LENGTH),
                ScreenFieldImage.unpainted(SignOnRequest.PGMNAME_LENGTH),
                ScreenFieldImage.unpainted(SignOnRequest.TITLE02_LENGTH),
                ScreenFieldImage.unpainted(SignOnRequest.CURTIME_LENGTH),
                ScreenFieldImage.unpainted(SignOnRequest.APPLID_LENGTH),
                ScreenFieldImage.unpainted(SignOnRequest.SYSID_LENGTH),
                ScreenFieldImage.unpainted(SignOnRequest.USERID_LENGTH),
                ScreenFieldImage.unpainted(SignOnRequest.PASSWD_LENGTH),
                ScreenFieldImage.unpainted(SignOnRequest.ERRMSG_LENGTH),
                null,
                PfKeyResolver.AidKey.ENTER.token());
    }

    // =================================================================================================
    // The HTTP surface - POST /api/signon, CSD transaction CC00.
    //
    // Read this method and the four below it as one straight line: bind, translate, delegate, project.
    // There is nothing else here, and nothing else belongs here.
    // =================================================================================================

    /**
     * Runs the sign-on transaction: {@code POST} {@value #SIGNON_PATH}, transaction {@code CC00},
     * program {@code COSGN00C}, mapset {@code COSGN00}, map {@code COSGN0A}, eleven named fields.
     *
     * <p><strong>An absent body is the cold start.</strong> The parameter is {@code required = false},
     * so a bodyless {@code POST} arrives {@code null} and is treated as {@code EIBCALEN = 0} - the
     * transaction typed at a clear screen, which {@code app/cbl/COSGN00C.cbl:80-83} answers by clearing
     * every output field, putting the cursor on the user id and painting the sign-on screen, with no
     * validation and no read of {@code USRSEC}. A request continuing the pseudo-conversation sends back
     * the payload it last received, carrying the communication area, the attention identifier and what
     * was typed. Never a server-side session.
     *
     * <p>No {@code consumes} is declared, precisely so a bodyless call binds rather than being refused
     * with an unsupported-media-type before the program runs.
     *
     * <p>Always answers {@code 200 OK} - see the class notes on why a refused sign-on is a message
     * rather than a {@code 4xx}.
     *
     * <p><strong>The key arrives as its raw byte, and that byte is what {@code :85} is given.</strong>
     * Both spellings of {@link AidRequestParameter} bind, and either carries the whole of
     * {@code EIBAID}: all 256 values, so {@code PF15} is distinguishable from {@code PF3}. That matters
     * here because {@code COSGN00C} never copied {@code app/cpy/CSSTRPFY.cpy} - it tests {@code EIBAID}
     * inline against {@code DFHENTER} and {@code DFHPF3} at {@code :85-95} and has no clause for
     * {@code DFHPF15}, so on the mainframe {@code PF15} reaches {@code WHEN OTHER}. The five-character
     * token in the payload cannot say that, because the copybook folds {@code PF15} onto {@code 'PFK03'};
     * a caller restricted to the token could only ever get the PF3 arm. A request that sends neither
     * parameter keeps the token decode it always had, unchanged.
     *
     * @param request the inbound screen and communication area, or {@code null} for the cold start
     * @param eibaid  the raw {@code EIBAID} byte as an unsigned {@code 0}-{@code 255} value, or
     *                {@code null} when the request names no key
     * @param eibAid  the accepted alternate spelling of the same parameter
     * @return the painted screen and, beside it rather than inside it, the presentation metadata this
     *         program sets - the cursor request and the full-repaint instruction, neither of which is a
     *         {@code DFHMDF} field; never {@code null}
     */
    @PostMapping(path = SIGNON_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ScreenResponse<SignOnResponse> signOn(
            @Valid @RequestBody(required = false) final SignOnRequest request,
            @RequestParam(name = EIBAID_PARAM, required = false) final Integer eibaid,
            @RequestParam(name = EIBAID_PARAM_ALIAS, required = false) final Integer eibAid) {
        return performSignOn(Objects.requireNonNullElse(request, coldStartRequest),
                AidRequestParameter.resolve(eibaid, eibAid));
    }

    /**
     * Runs one invocation of {@code COSGN00C} against a payload already known to be present.
     *
     * <p>Package-visible on purpose: this is the seam a test drives when it wants the translation
     * without the HTTP layer, and it is the whole of what the request mapping does once the absent-body
     * default has been applied. {@link SignOnService#handle(SignOnInput)} is called <strong>exactly
     * once</strong>, and no branch in this class stands between the call and its result.
     *
     * @param received the inbound payload; a {@code null} communication area inside it still means
     *                 {@code EIBCALEN = 0}
     * @return the projected screen and its metadata, never {@code null}
     * @throws NullPointerException if {@code received} is {@code null}; the request mapping substitutes
     *                              {@link #coldStartRequest} before calling this, so reaching it with
     *                              {@code null} would mean the absent-body default was bypassed
     */
    ScreenResponse<SignOnResponse> performSignOn(final SignOnRequest received) {
        return performSignOn(received, null);
    }

    /**
     * Runs one invocation of {@code COSGN00C}, with the raw attention identifier the request stated.
     *
     * <p>The overload the request mapping calls, and the one a test drives when the distinction between
     * {@code PF3} and {@code PF15} is the point. {@link #performSignOn(SignOnRequest)} delegates here
     * with {@code null}, which is "the request named no raw byte" and leaves the payload's token as the
     * only statement of the key - exactly the behaviour that existed before the parameter did.
     *
     * @param received  the inbound payload; a {@code null} communication area inside it still means
     *                  {@code EIBCALEN = 0}
     * @param statedAid the raw {@code EIBAID} byte as an unsigned value, or {@code null} when the
     *                  request named no key
     * @return the projected screen and its metadata, never {@code null}
     * @throws NullPointerException if {@code received} is {@code null}
     */
    ScreenResponse<SignOnResponse> performSignOn(final SignOnRequest received,
            final Integer statedAid) {
        Objects.requireNonNull(received, "A payload is required here; an absent body is represented by "
                + "the cold-start request, whose communication area is null, and not by a null payload");
        return performSignOn(received, resolveEibAid(statedAid, received.aid()));
    }

    /**
     * Runs one invocation of {@code COSGN00C} with the attention identifier supplied separately.
     *
     * <p>The overload a test drives when it holds the raw byte already. Both forms above reach it: the
     * request mapping through the {@code Integer} overload, which narrows the stated value and
     * cross-checks any statement of the same key in the payload, and
     * {@link #performSignOn(SignOnRequest)} through the payload's own one-character {@code aid} image.
     *
     * @param received the inbound payload; must not be {@code null}
     * @param eibAid   the raw {@code EIBAID} byte {@code :85} evaluates
     * @return the projected screen and its metadata, never {@code null}
     * @throws NullPointerException if {@code received} is {@code null}
     */
    ScreenResponse<SignOnResponse> performSignOn(final SignOnRequest received, final byte eibAid) {
        Objects.requireNonNull(received, "A payload is required here; an absent body is represented by "
                + "the cold-start request, whose communication area is null, and not by a null payload");
        final SignOnOutcome outcome = signOnService.handle(toInput(received, eibAid));
        return ScreenResponse.of(toResponse(outcome), toMetadata(outcome));
    }

    /**
     * Translates the inbound payload into the three values {@code COSGN00C} actually consults.
     *
     * <p>Those three, and no others. The program reads {@code EIBCALEN} through the presence or absence
     * of {@code DFHCOMMAREA} ({@code :65-67} and {@code :80}), {@code EIBAID} once at {@code :85}, and
     * {@code USERIDI} and {@code PASSWDI} at {@code :118}, {@code :123}, {@code :132} and {@code :135}.
     * The remaining eight screen fields exist on the request because the symbolic map declares them, and
     * the program never looks at any of them - so nothing here pretends otherwise.
     *
     * <p>Three properties of this translation are load-bearing:
     *
     * <ul>
     *   <li>The communication area is passed <strong>straight through, including when it is
     *       {@code null}</strong>. Absence is the encoding of {@code EIBCALEN = 0}, so no test is
     *       performed here - a flag computed in this class would be a second source of truth able to
     *       disagree with the reference beside it.</li>
     *   <li>The user id and the password are passed through <strong>verbatim, {@code null}
     *       included</strong>. This is not laxity: {@code :118} and {@code :123} each test
     *       {@code = SPACES OR LOW-VALUES}, so the program distinguishes three states of a screen field
     *       - blanked by the user, never transmitted, or holding data - and an omitted JSON member is
     *       exactly the second. Substituting spaces here would erase a distinction the source makes.
     *       Neither value is trimmed, upper-cased or padded; {@code MOVE FUNCTION UPPER-CASE} at
     *       {@code :132-137} belongs to the service, which applies it unconditionally and in the source's
     *       own order.</li>
     *   <li>The attention identifier is the raw byte {@code :85} compares, taken from the query
     *       parameter or from the payload's one-character {@code aid} image - see
     *       {@link #toEibAid(String)}. Nothing else about it is interpreted; the {@code WHEN} selection
     *       remains the service's.</li>
     * </ul>
     *
     * @param received the inbound payload
     * @return the service input, never {@code null}
     * @throws NullPointerException if {@code received} is {@code null}
     */
    SignOnInput toInput(final SignOnRequest received) {
        return toInput(received, null);
    }

    /**
     * Translates the inbound payload into the three values {@code COSGN00C} consults, taking the
     * attention identifier from the raw byte when the request stated one.
     *
     * @param received  the inbound payload
     * @param statedAid the raw {@code EIBAID} byte as an unsigned value, or {@code null} when the
     *                  request named no key
     * @return the service input, never {@code null}
     * @throws NullPointerException if {@code received} is {@code null}
     */
    SignOnInput toInput(final SignOnRequest received, final Integer statedAid) {
        Objects.requireNonNull(received, "A payload is required to build a sign-on invocation");
        return toInput(received, resolveEibAid(statedAid, received.aid()));
    }

    /**
     * The same translation with the attention identifier supplied separately from the payload.
     *
     * @param received the inbound payload; must not be {@code null}
     * @param eibAid   the raw {@code EIBAID} byte {@code :85} evaluates
     * @return the service input, never {@code null}
     * @throws NullPointerException if {@code received} is {@code null}
     */
    SignOnInput toInput(final SignOnRequest received, final byte eibAid) {
        Objects.requireNonNull(received, "A payload is required to build a sign-on invocation");
        final NavigationContext inboundCommarea = received.navigationContext();
        return new SignOnInput(inboundCommarea,
                eibAid,
                received.userId(),
                received.passwd());
    }

    /**
     * Chooses which of the two statements of the key the request made is acted on: the raw byte when it
     * is there, the payload's own image otherwise.
     *
     * <p>The byte wins because it is the lossless one - see {@link AidRequestParameter} - and because
     * this program tests {@code EIBAID} inline, so a folded token would give {@code PF15} the
     * {@code PF3} arm. An image stated beside a disagreeing byte is refused rather than dropped.
     *
     * @param statedAid the raw {@code EIBAID} byte as an unsigned value, or {@code null} when absent
     * @param aidImage  the payload's one-character {@code aid} image, or {@code null} when absent
     * @return the raw EBCDIC attention-identifier byte {@code :85} is given
     */
    byte resolveEibAid(final Integer statedAid, final String aidImage) {
        if (statedAid == null) {
            return toEibAid(aidImage);
        }
        return AidRequestParameter.requireStatedAid(AID_MEMBER, statedAid, aidImage, codec);
    }

    /**
     * Reads the raw {@code EIBAID} byte out of the payload's {@code aid} member.
     *
     * <p><strong>One character is the byte.</strong> Its code point <em>is</em> the attention
     * identifier, so {@code DFHENTER} travels as {@code U+007D} and {@code DFHPF3} as {@code U+00F3},
     * and {@code app/cbl/COSGN00C.cbl:85}'s {@code EVALUATE EIBAID} compares exactly that. The same byte
     * may instead be stated as an unsigned {@code 0}-{@code 255} integer on the
     * {@value #EIBAID_PARAM} query parameter, which takes precedence when both are supplied.
     *
     * <h4>Why a {@code CCARD-AID} token is no longer decoded back to a byte</h4>
     * {@code app/cpy/CSSTRPFY.cpy} folds {@code DFHPF13}-{@code DFHPF24} onto {@code 'PFK01'}-{@code
     * 'PFK12'}, so {@code 'PFK03'} stands for {@code DFHPF3} <em>and</em> {@code DFHPF15}. Decoding it
     * had to choose, and choosing {@code DFHPF3} sent a PF15 press down {@code :88-90}'s
     * {@code WHEN DFHPF3} arm - the thank-you message and a terminating {@code SEND TEXT} - where the
     * source takes {@code WHEN OTHER} at {@code :91-94} and repaints the screen with the invalid-key
     * message. {@code COSGN00C} does not copy {@code CSSTRPFY}: it compares {@code EIBAID} itself, so
     * the fold is not its behaviour and there is nothing to invert. The token survives as derived
     * metadata on the way out, which is where {@link PfKeyResolver#resolve(byte)} produces it.
     *
     * <p><strong>Any other width, and an absent value, yield {@link CicsAid#DFHNULL}</strong>, a byte
     * {@code CSSTRPFY} does not test and {@link PfKeyResolver#resolve(byte)} maps to no key. It is
     * neither {@code DFHENTER} nor {@code DFHPF3}, so it lands on the {@code WHEN OTHER} arm at
     * {@code :91-94} - the same arm an unmapped key reaches, which is exactly where "no key I can
     * identify" belongs. No default of {@code DFHENTER} is substituted: guessing that a caller pressed
     * ENTER would run the whole validate-and-read path on a request that asked for nothing.
     *
     * @param aidImage the {@code aid} member as it arrived, or {@code null} when the payload omitted it
     * @return the raw EBCDIC attention-identifier byte; never throws
     */
    byte toEibAid(final String aidImage) {
        if (aidImage == null || aidImage.length() != RAW_AID_LENGTH) {
            return CicsAid.DFHNULL;
        }
        char stated = aidImage.charAt(0);
        if (stated > MAX_AID_CODE_POINT) {
            // EIBAID is one byte, so the whole AID space is U+0000 to U+00FF. A narrowing cast of
            // anything wider keeps the low eight bits, and U+01F3 would narrow onto 0xF3 - DFHPF3, the
            // key :88 acts on. Reported as no key rather than folded onto one the caller never pressed.
            return CicsAid.DFHNULL;
        }
        return (byte) stated;
    }

    /**
     * Projects the outcome onto the ten output screen fields, the navigation triple and the
     * communication area.
     *
     * <p>Straight-line assembly in map order, one value per copybook field, so that every member can be
     * read against {@code app/cpy-bms/COSGN00.CPY} without following a loop.
     *
     * <p><strong>The reset comes for free.</strong> {@link SignOnResponse#empty()} starts from the
     * unpainted initial state and every member the program writes is then assigned, which is
     * structurally {@code MOVE LOW-VALUES TO COSGN0AO} at {@code :81} followed by the paint: no field
     * can retain a value, because there is nowhere for a value to have come from. The outcome's own
     * reset signal is still reported, in {@link #toMetadata(SignOnOutcome)}, so a client clears its
     * rendered screen on the same path the program does.
     *
     * <p><strong>A field is painted only where the program sent it.</strong> Two of the three exits
     * transmit no map at all - {@code SEND-PLAIN-TEXT} at {@code :162-172} sends unformatted text, and
     * the {@code EXEC CICS XCTL} pair at {@code :231-239} transfers control without sending anything -
     * so on those two paths every map member is left at the image {@link SignOnResponse#empty()} gives
     * it and only the navigation members, the communication area and (on PF3) the transmitted text are
     * populated. An earlier revision painted the eight header fields and the error line on all three
     * exits, which claimed a screen the program never sent; the header comes from
     * {@code POPULATE-HEADER-INFO}, which is the first statement of {@code SEND-SIGNON-SCREEN} at
     * {@code :147} and runs nowhere else.
     *
     * <p><strong>The PF3 transmission is eighty bytes, and it is not the error line.</strong>
     * {@code EXEC CICS SEND TEXT FROM(WS-MESSAGE) LENGTH(LENGTH OF WS-MESSAGE)} sends the whole of
     * {@code WS-MESSAGE PIC X(80)} ({@code :38}) as unformatted text. Projecting it into
     * {@code ERRMSGO PIC X(78)} - as an earlier revision did - lost the two rightmost characters and
     * reported a map field for a send that had no map. {@link SignOnResponse#plainText()} carries it at
     * its own width instead.
     *
     * <p><strong>{@code USERIDO} and {@code PASSWDO} are painted by the receive, not by a {@code MOVE}.
     * </strong> {@code app/cpy-bms/COSGN00.CPY:85} declares {@code 01 COSGN0AO REDEFINES COSGN0AI}, so
     * {@code USERIDI} and {@code USERIDO} are one eight-byte span and {@code PASSWDI} and
     * {@code PASSWDO} are another. {@code EXEC CICS RECEIVE MAP} at {@code :110-115} writes them and the
     * {@code SEND MAP ... FROM(COSGN0AO)} at {@code :151-157} then transmits them, which is why an error
     * repaint comes back with the typed identifier still in the field. The service reports the two spans
     * on {@link SignOnOutcome#mapInputArea()} and they are transcribed here; the images are
     * the raw received values, because {@code MOVE FUNCTION UPPER-CASE} at {@code :132-137} writes
     * {@code WS-USER-ID} and {@code WS-USER-PWD} and never writes back into the spans.
     *
     * <p><strong>Three widenings and one truncation happen here, and each is deliberate.</strong>
     * Every value is put through {@link FixedWidthCodec#movePicX} at its declared width rather than
     * assigned, because {@link SignOnResponse} enforces <em>at most</em> its declared widths while the
     * screen contract is <em>exactly</em> those widths, and a plain assignment would neither pad nor
     * truncate:
     *
     * <ul>
     *   <li>{@code CURTIMEO} is {@code PIC X(9)} - nine, uniquely among the five {@code user} screens -
     *       while {@link DateHeader#wsCurtimeHhMmSs()} renders
     *       {@code HH:MM:SS} in eight. The {@code MOVE} at {@code :196} therefore pads one space on the
     *       right, and that is reproduced rather than left to chance.</li>
     *   <li>{@code WS-MESSAGE} is {@code PIC X(80)} at {@code :38} but {@code ERRMSGO} is
     *       {@code PIC X(78)}, so {@code MOVE WS-MESSAGE TO ERRMSGO} at {@code :149} <strong>truncates
     *       two characters off the right</strong>. Performing it through {@code movePicX} makes the
     *       direction a stated choice; a Java {@code substring} would be a guess, and taking the
     *       trailing 78 instead of the leading 78 is the classic way to get this wrong.</li>
     *   <li>{@link #applId} and {@link #sysId} were brought to width once, at construction.</li>
     * </ul>
     *
     * <p><strong>The navigation triple is how a stateless client tells the program's three exits
     * apart</strong>, and it needs no member beyond the ones the screen already declares.
     * {@code EXEC CICS SEND MAP} at {@code :151-157} names {@code MAPSET('COSGN00')} and
     * {@code MAP('COSGN0A')}, so those two are reported on exactly the paths that transmit the map and
     * are blank elsewhere:
     *
     * <table border="1">
     *   <caption>Exit projection</caption>
     *   <tr><th>Path</th><th>{@code nextProgram}</th><th>{@code nextMapset} / {@code nextMap}</th></tr>
     *   <tr><td>screen repainted - cold start, either blank field, wrong password, user not found,
     *           unable to verify, invalid key</td>
     *       <td>spaces</td><td>{@code COSGN00} / {@code COSGN0A} - send the area back to {@code CC00}
     *           and render this map again</td></tr>
     *   <tr><td>PF3 - {@code SEND TEXT} then a bare {@code EXEC CICS RETURN} with no {@code TRANSID}
     *           ({@code :164-172})</td>
     *       <td>spaces</td><td>spaces - the conversation has <strong>ended</strong>; there is no map to
     *           render and no transaction to return to, and the eighty transmitted bytes travel in
     *           {@link SignOnResponse#plainText()}</td></tr>
     *   <tr><td>signed on - {@code EXEC CICS XCTL} ({@code :231-239})</td>
     *       <td>{@code COADM01C} or {@code COMEN01C}</td>
     *       <td>spaces - control transferred, and the target program paints its own map</td></tr>
     * </table>
     *
     * <p>So the terminal PF3 response is distinguishable from a repaint by carrying no map, and from a
     * successful sign-on by carrying no program. The role and the transfer target are the point of the
     * success response: {@code :230-240} chooses {@code 'COADM01C'} for user type {@code 'A'} and
     * {@code 'COMEN01C'} otherwise, and the client issues the follow-up call. There is no server-side
     * forward, no redirect and no session affinity.
     *
     * <p>Three outcome members are intentionally not projected, because the screen has no field for
     * them: {@code errorFlag} is {@code WS-ERR-FLG}, program-internal working storage;
     * {@code screenPainted} and {@code plainTextSent} are the transmission facts the navigation triple
     * and {@link SignOnResponse#plainText()} already express between them; and {@code receive} carries
     * the {@code RESP} and {@code RESP2} codes that {@code :110-115} captures and <strong>never
     * tests</strong>.
     *
     * @param outcome the result of running the program
     * @return the response payload, never {@code null}
     * @throws NullPointerException if {@code outcome} is {@code null}
     */
    SignOnResponse toResponse(final SignOnOutcome outcome) {
        Objects.requireNonNull(outcome, "An outcome is required to project the sign-on screen");
        // :151-157 EXEC CICS SEND MAP('COSGN0A') MAPSET('COSGN00') names the map only where it
        // transmits, which is what makes the three exits distinguishable. See the table above.
        final boolean screenPainted = outcome.screenPainted();
        if (!screenPainted) {
            // Neither remaining exit transmits the map. :162-172 SEND TEXT sends unformatted text and
            // then returns with no TRANSID; :231-239 XCTL transfers control and sends nothing. So no
            // screen field is painted from those paths, and POPULATE-HEADER-INFO is not performed
            // either, because :147 performs it from inside SEND-SIGNON-SCREEN and nowhere else. The one
            // exception is the error line, which :78 blanks before the EVALUATE on every path.
            final SignOnResponse unsent = SignOnResponse.empty()
                    // :78 MOVE SPACES TO WS-MESSAGE, ERRMSGO OF COSGN0AO. That statement is at the head
                    // of MAIN-PARA, before the EVALUATE, so it writes the error line on every path
                    // including these two. The other ten items are left unpainted: nothing writes them
                    // and nothing is transmitted.
                    .withErrMsg(codec.movePicX(SPACES, SignOnResponse.ERRMSG_LENGTH))
                    // :224-240 - the role, the XCTL target and the area handed on, all decided already.
                    // The mapset and map stay blank: there is no map for a client to render.
                    .withNavigation(outcome.role(),
                            outcome.nextProgram(),
                            codec.movePicX(SPACES, SignOnResponse.NEXT_MAPSET_LENGTH),
                            codec.movePicX(SPACES, SignOnResponse.NEXT_MAP_LENGTH),
                            outcome.navigationContext());
            if (!outcome.plainTextSent()) {
                // The XCTL path: navigation and the communication area, and nothing else.
                return unsent;
            }
            // :164-169 EXEC CICS SEND TEXT FROM(WS-MESSAGE) LENGTH(LENGTH OF WS-MESSAGE) - the whole
            // PIC X(80), not the PIC X(78) of ERRMSGO, because a SEND TEXT has no map field to narrow
            // into.
            return unsent.withPlainText(
                    codec.movePicX(outcome.message(), SignOnResponse.PLAIN_TEXT_LENGTH));
        }
        // :179 MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA - read once, from the injected clock, so
        // the date and the time renderings cannot straddle a second boundary. Read here rather than
        // above, because POPULATE-HEADER-INFO runs from inside SEND-SIGNON-SCREEN only: the two exits
        // that send no map consult no clock.
        final DateHeader header = DateHeader.from(codec, clock);
        final MapInputArea mapArea = outcome.mapInputArea();
        return SignOnResponse.empty()
                // :110-115 EXEC CICS RECEIVE MAP wrote USERIDI and PASSWDI, and COSGN0AO REDEFINES
                // COSGN0AI (CPY :85) makes those the very spans :151-157 transmits. Raw images: :132-137
                // upper-cases into WS-USER-ID and WS-USER-PWD, never back into the map.
                .withReceivedMapArea(mapArea.useridi(), mapArea.passwdi())
                // POPULATE-HEADER-INFO, :177-204. TRANID and PROGRAM_NAME are the same literals as
                // WS-TRANID (:37) and WS-PGMNAME (:36), which :183-184 move into the map.
                .withHeader(codec.movePicX(SignOnResponse.TRANID, SignOnResponse.TRNNAME_LENGTH),
                        codec.movePicX(ScreenTitles.CCDA_TITLE01, SignOnResponse.TITLE01_LENGTH),
                        codec.movePicX(header.wsCurdateMmDdYy(), SignOnResponse.CURDATE_LENGTH),
                        codec.movePicX(SignOnResponse.PROGRAM_NAME, SignOnResponse.PGMNAME_LENGTH),
                        codec.movePicX(ScreenTitles.CCDA_TITLE02, SignOnResponse.TITLE02_LENGTH),
                        codec.movePicX(header.wsCurtimeHhMmSs(), SignOnResponse.CURTIME_LENGTH),
                        applId,
                        sysId)
                // :149 MOVE WS-MESSAGE TO ERRMSGO - PIC X(80) into PIC X(78), truncating on the right.
                .withErrMsg(codec.movePicX(outcome.message(), SignOnResponse.ERRMSG_LENGTH))
                // :224-240 - the role, the XCTL target and the area handed on, all decided already.
                .withNavigation(outcome.role(),
                        outcome.nextProgram(),
                        SignOnResponse.MAPSET_NAME,
                        SignOnResponse.MAP_NAME,
                        outcome.navigationContext());
    }

    /**
     * Projects the two presentation instructions {@code COSGN00C} issues that have no {@code DFHMDF}
     * field to travel in.
     *
     * <p>{@code COSGN00} declares no {@code xxxC}, {@code xxxP}, {@code xxxH} or {@code xxxV} item this
     * program writes to, so there are no per-field attribute quads to report and the field map is
     * empty - an accurate empty rather than a missing one. What is reported:
     *
     * <ul>
     *   <li><strong>The cursor request.</strong> {@code MOVE -1 TO USERIDL} or {@code MOVE -1 TO
     *       PASSWDL}, named by its {@code DFHMDF} label. The {@code xxxL} item is
     *       {@code COMP PIC S9(4)} input-group metadata and never a payload member.</li>
     *   <li><strong>The full-repaint instruction.</strong> {@code MOVE LOW-VALUES TO COSGN0AO} at
     *       {@code :81}, true on the cold-start path alone, so a client clears its rendered screen on
     *       the same path the program does.</li>
     * </ul>
     *
     * <p><strong>The message colour is reported as absent, because the program sets none.</strong>
     * {@code COSGN00C} contains no {@code MOVE} to {@code ERRMSGC} or to any other attribute item, and
     * it does not copy {@code app/cpy/CSSETATY.cpy}, the include that applies the {@code DFHRED}
     * plus-asterisk error highlight in the re-enter state; {@code :59} even has {@code COPY DFHATTR}
     * commented out. A colour asserted here would be invented, so {@code null} is passed and
     * {@link ScreenMetadata}'s non-null JSON inclusion omits the member entirely. The error indication
     * a client actually has is the message text in {@code ERRMSGO}, which the mapset already declares
     * {@code BRT} and {@code COLOR=RED} at {@code app/bms/COSGN00.bms:197-200}, together with the cursor
     * landing on the field at fault.
     *
     * @param outcome the result of running the program
     * @return the metadata, never {@code null}
     * @throws NullPointerException if {@code outcome} is {@code null}
     */
    ScreenMetadata toMetadata(final SignOnOutcome outcome) {
        Objects.requireNonNull(outcome, "An outcome is required to project the screen metadata");
        return new ScreenMetadata(cursorLabel(outcome.cursorField()),
                null,
                outcome.resetAllOutputFields(),
                Map.of());
    }

    /**
     * Names the screen field a cursor request landed on, by its {@code DFHMDF} label.
     *
     * <p>A projection of a value the service has already decided, not a decision: the arms map one to
     * one onto {@link CursorField}, with no default, so adding a target to the enum would fail to
     * compile here rather than fall through to a wrong answer.
     *
     * @param cursorField the target the service resolved; must not be {@code null} - use
     *                    {@link CursorField#NONE} where the program performs no {@code MOVE -1}
     * @return {@value #CURSOR_USERID}, {@value #CURSOR_PASSWD}, or {@code null} when the program
     *         requested no cursor position on this turn, which is how {@link ScreenMetadata} spells the
     *         absence
     * @throws NullPointerException if {@code cursorField} is {@code null}
     */
    static String cursorLabel(final CursorField cursorField) {
        Objects.requireNonNull(cursorField, "A cursor target is required; CursorField.NONE is the "
                + "value for a path that performs no MOVE -1");
        return switch (cursorField) {
            // :91-94, the invalid-key arm, contains no MOVE -1 at all, and neither does an XCTL or the
            // PF3 exit. Absence of a request is not a request for position zero.
            case NONE -> null;
            // :82 cold start, :121 blank user id, :250 user not found, :255 unable to verify.
            case USER_ID -> CURSOR_USERID;
            // :126 blank password, :244 wrong password.
            case PASSWORD -> CURSOR_PASSWD;
        };
    }
}
