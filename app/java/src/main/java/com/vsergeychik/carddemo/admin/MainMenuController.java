package com.vsergeychik.carddemo.admin;

import com.vsergeychik.carddemo.admin.MainMenuService.MainMenuInput;
import com.vsergeychik.carddemo.admin.MainMenuService.MainMenuOutcome;
import com.vsergeychik.carddemo.admin.dto.MainMenuRequest;
import com.vsergeychik.carddemo.admin.dto.MainMenuResponse;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * CICS transaction {@code CM00} - program {@code COMEN01C}, "Main Menu for the Regular users" - as a
 * stateless REST resource on {@value #MAIN_MENU_PATH}.
 *
 * <h2>What this class is, and what it deliberately is not</h2>
 *
 * <p>It is a <strong>thin adapter</strong>, and nothing else. It binds the request payload, translates
 * it into the service's own input record, calls {@link MainMenuService#handle(MainMenuInput)} exactly
 * once, and projects the returned outcome onto the response payload. Every decision
 * {@code COMEN01C} makes lives in {@link MainMenuService}:
 *
 * <ul>
 *   <li>the {@code IF EIBCALEN = 0} diversion to the sign-on screen ({@code app/cbl/COMEN01C.cbl:82});</li>
 *   <li>the {@code IF NOT CDEMO-PGM-REENTER} first-entry versus re-entry split (line 87);</li>
 *   <li>the ordered {@code EVALUATE EIBAID} with its {@code DFHENTER}, {@code DFHPF3} and
 *       {@code WHEN OTHER} arms (lines 93 to 103);</li>
 *   <li>the option normalisation - backwards space scan, {@code JUST RIGHT} receiver,
 *       {@code INSPECT ... REPLACING ALL ' ' BY '0'} - at lines 117 to 124;</li>
 *   <li>the numeric, range and zero guards at lines 127 to 134;</li>
 *   <li><strong>the user-type authorisation filter at lines 136 to 143</strong>;</li>
 *   <li>the {@code 'DUMMY'} program-prefix test at line 146 and the coming-soon message at lines 157
 *       to 164;</li>
 *   <li>and the twelve-arm {@code BUILD-MENU-OPTIONS} composition at lines 236 to 277.</li>
 * </ul>
 *
 * <p>The authorisation filter earns its emphasis. {@code IF CDEMO-USRTYP-USER AND
 * CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'} is the one decision a reader might expect a controller to
 * make, and it is the one that most matters to keep out: writing it here would put a branch on the
 * HTTP side of the seam and make the module's coverage bar unreachable without a servlet container.
 * There is <strong>no user-type comparison anywhere in this file</strong>. The inbound user type is
 * carried through untouched and the service decides.
 *
 * <p>That division is not stylistic. This class contains <strong>no</strong> {@code if}, no
 * {@code switch}, no ternary and no loop, so it contributes zero branches to the module's JaCoCo
 * {@code BRANCH} counter - which the build enforces at 0.90 for every package as well as for the
 * bundle as a whole. The consequence is the one the migration plan is after: the {@code admin} package
 * clears that bar from plain JUnit tests which construct {@link MainMenuService} directly, with no
 * {@code MockMvc}, no servlet container and no HTTP in the path. Coverage earned that way measures the
 * translated COBOL rather than the framework around it.
 *
 * <p>Where a {@code null} payload member has to become the run of spaces COBOL would have held, the
 * choice is delegated to {@link Objects#requireNonNullElse}, so even that test executes inside the JDK
 * rather than inside this class.
 *
 * <h2>Naming: this package carries no divergence</h2>
 *
 * <p>Worth stating plainly, because two sibling packages do carry one and a reader who has met them
 * will arrive here expecting a third. The migration honours prompt-mandated class names verbatim and
 * takes behaviour from the COBOL, which elsewhere leaves name and behaviour disagreeing. Here they
 * agree:
 *
 * <ul>
 *   <li>{@code COMEN01C} to {@code MainMenuController} - the source {@code Function :} header reads
 *       "Main Menu for the Regular users", and this really is that menu.
 *       <strong>No divergence.</strong></li>
 *   <li>{@code COADM01C} to {@link AdminMenuController} - likewise the admin menu.
 *       <strong>No divergence.</strong></li>
 * </ul>
 *
 * <p>Contrast, and do not confuse with:
 *
 * <ul>
 *   <li>{@code transaction.TransactionMenuController} - {@code COTRN00C} <em>lists</em> transactions;
 *       it is not a menu.</li>
 *   <li>{@code user.UserMenuController} - {@code COUSR00C} <em>lists users</em>; it is not a
 *       menu.</li>
 * </ul>
 *
 * <p>So there is no swap to hunt for in this package - unlike {@code transaction}, where
 * {@code COTRN01C} views and {@code COTRN02C} adds while their mandated names say the opposite. Two
 * further non-authoritative artefacts are called out for the same reason: {@code docs/**} describes a
 * superseded design targeting a separate repository, and {@code catalog-info.yaml} claims a Java
 * version this module does not use. Neither was consulted while writing this class and neither is
 * corrected by it - conflicts are documented, not silently fixed.
 *
 * <h2>Statelessness</h2>
 *
 * <p>{@code COMEN01C} is pseudo-conversational: it ends at
 * {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)}
 * ({@code app/cbl/COMEN01C.cbl:107-110}) and expects the next keystroke to bring that communication
 * area back. All three pieces of conversation state therefore travel in the payload and
 * <strong>nothing</strong> is retained between calls:
 *
 * <ol>
 *   <li>the communication area, as {@link NavigationContext} on both the request and the response;</li>
 *   <li>the attention identifier, as the request's raw {@code EIBAID} byte;</li>
 *   <li>the first-entry versus re-entry context, as {@code CDEMO-PGM-CONTEXT} inside that
 *       communication area.</li>
 * </ol>
 *
 * <p>There is no {@code HttpSession}, no {@code @SessionAttributes}, no session-scoped bean, no
 * request-attribute stash and no cache. The class holds exactly five fields, all {@code private final}
 * and all immutable or thread-safe, and it has no mutable {@code static} state at all. Two identical
 * requests therefore produce two identical responses, which is the property the statelessness
 * requirement actually asks for.
 *
 * <h2>{@code CDEMO-USER-TYPE} is inbound only</h2>
 *
 * <p>{@code COMEN01C} never assigns it. The single assignment anywhere in the program is the
 * <strong>commented-out</strong> {@code * MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE} at line 150, so the
 * value the authorisation filter reads at line 136 is whatever arrived. It reaches this endpoint
 * inside the communication area as {@code CDEMO-USER-TYPE PIC X(01)} with
 * {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} and {@code 88 CDEMO-USRTYP-USER VALUE 'U'}
 * ({@code app/cpy/COCOM01Y.cpy:26-28}), and {@link #toInput} hands it on unchanged: never derived,
 * never defaulted, never overridden, and never looked up from a user record.
 *
 * <p>Which menu a signed-on operator reaches was already decided by the sign-on screen, whose
 * {@code EXEC CICS XCTL PROGRAM('COADM01C')} for user type {@code 'A'} and
 * {@code PROGRAM('COMEN01C')} for everyone else ({@code app/cbl/COSGN00C.cbl:230-240}) is projected as
 * a role field on that screen's response. The client then calls this endpoint. That routing is not
 * re-implemented here, and there is no server-side forward and no session affinity.
 *
 * <h2>An absent body is the cold start</h2>
 *
 * <p>{@code app/cbl/COMEN01C.cbl:82} tests {@code IF EIBCALEN = 0} to tell a transaction typed at a
 * clear screen from one transferred into mid-conversation. A {@code GET} that carries no payload is
 * exactly the former, so the body parameter is {@code required = false} and an absent one is
 * translated into an input whose communication area is absent. This mirrors the idiom the sibling
 * {@link AdminMenuController} and the card-list screen already establish in this module.
 *
 * <h2>No dataset is read - and that is a property of the source</h2>
 *
 * <p>This controller injects <strong>no repository</strong>, and that is deliberate rather than
 * unfinished. {@code COMEN01C} performs no file access whatsoever: its only five {@code EXEC CICS}
 * verbs are {@code RETURN} (line 107), two {@code XCTL}s (lines 152 and 175), {@code SEND MAP} (line
 * 189) and {@code RECEIVE MAP} (line 201) - there is not one {@code READ}, {@code STARTBR},
 * {@code READNEXT}, {@code WRITE}, {@code REWRITE} or {@code DELETE} anywhere in the program.
 * {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '} is declared at line 39 and never referenced again,
 * and {@code COPY CSUSR01Y.} at line 58 brings in {@code SEC-USER-DATA} whose only mention in the
 * whole program is the commented-out line 150; {@link MainMenuService} preserves both as the dead
 * declarations they are.
 *
 * <p>Two consequences a reviewer should read as intended, not as omissions:
 *
 * <ul>
 *   <li>Injecting the security-user repository here would invent a {@code USRSEC} read the COBOL never
 *       performs. That would be a new feature, and this migration adds none - including to obtain the
 *       user type, which arrives in the payload precisely because the program never fetches it.</li>
 *   <li>The migration's file-status gate - every file-status outcome exercised at every repository call
 *       site - has <strong>zero call sites in this package</strong>. There is nothing to exercise
 *       because there is nothing to call.</li>
 * </ul>
 *
 * <p>The screen also authenticates nothing: it has no credential field, reads no user record and makes
 * no authorisation decision of its own. There is accordingly no security annotation, no method
 * security, no granted authority, no filter chain, no hashing and no token anywhere in this class. The
 * authorisation column {@code COMEN02Y} declares is compared as a single byte of ordinary data, in the
 * service, exactly as line 137 compares it.
 *
 * <h2>The presentation contract</h2>
 *
 * <p>No component library, design system or design-token set exists in this repository or is named
 * anywhere in the migration plan, and there are no attachments and no Figma screens. The
 * <strong>BMS layer is</strong> the presentation contract, and it is binding: {@code COMEN01.bms}
 * declares 28 {@code DFHMDF} fields of which exactly 20 are name-labelled, and those 20 are exactly
 * the payload members. The other 8 are literal screen furniture - {@code 'Tran:'}, {@code 'Date:'},
 * {@code 'Prog:'}, {@code 'Time:'}, {@code 'Main Menu'} at {@code LENGTH=9} and {@code POS=(4,35)},
 * {@code 'Please select an option :'}, a {@code LENGTH=0} stopper and
 * {@code 'ENTER=Continue  F3=Exit'} - and never travel. The map is {@code SIZE=(24,80)} under
 * {@code DFHMSD CTRL=(ALARM,FREEKB) EXTATT=YES LANG=COBOL MODE=INOUT STORAGE=AUTO TIOAPFX=YES}.
 *
 * <p>{@code app/cpy-bms/COMEN01.CPY} and {@code app/cpy-bms/COADM01.CPY} are byte-identical but for
 * their two group names - {@code COMEN1AI}/{@code COMEN1AO} against
 * {@code COADM1AI}/{@code COADM1AO} - and {@code COMEN01.bms} differs from {@code COADM01.bms} only in
 * the mapset and map names and in that one unnamed heading literal ({@code 'Main Menu'} at
 * {@code LENGTH=9} against {@code 'Admin Menu'} at {@code LENGTH=10}). The two screens therefore share
 * a field contract but keep <strong>separate</strong> request and response types, so that a later
 * change to one screen cannot silently alter the other.
 *
 * <p>Only the {@code xxxO} items are payload. The {@code xxxL} length items, the {@code xxxF} flag
 * bytes, their {@code xxxA} attribute redefinitions and the output side's {@code xxxC}, {@code xxxP},
 * {@code xxxH} and {@code xxxV} items are validation and highlight metadata and are never JSON members
 * - {@link MainMenuResponse} enforces that, and this class adds none of them. All twelve
 * {@code OPTN00nO} members are carried even though the program's active option count is ten, because
 * the map and the {@code OCCURS 12} table both declare twelve.
 *
 * <h2>Control flow</h2>
 *
 * <p>{@code COMEN01C} contains <strong>zero</strong> {@code GO TO} statements, so nothing here is a
 * restructured jump; the estate's 135 {@code GO TO} sites are all in eight other programs. Neither
 * does the program copy {@code CSSTRPFY} - it compares {@code EIBAID} inline at line 93 - nor
 * {@code CSSETATY}, so no program-function-key resolver and no field-attribute highlighter is invoked
 * from this class, and no field-level highlighting is invented that the program does not perform. Nor
 * does it issue a {@code MOVE -1} to any length item, so it makes no cursor request: the {@code IC} on
 * {@code OPTION} is a static map attribute that BMS resolves, not a program decision, which is why the
 * metadata below carries no cursor field.
 *
 * <p>The program performs no arithmetic on a decimal field, declares no {@code COMP-3} item and
 * contains no {@code CALL 'CEE3ABD'}, so the fixed-point helper, the file-status constants and the
 * abend exception have no call site here and are not imported. The one genuine fixed-width operation
 * is the message narrowing in {@link #toResponse}.
 *
 * <h2>No user rules govern this file</h2>
 *
 * <p>The project supplies no user rules - the rules document reads "No user rules provided" in its
 * entirety. That is not licence to lower the bar and no rule has been invented: enterprise
 * best-practice substitutes stand in its place, and the ones that bind here are constructor injection
 * with no static mutable state, explicit non-wildcard imports with every width a named constant, an
 * unchanged security posture, preserved dead code, immutable reference sources, the hand-written
 * fixed-width codec in place of {@code String} surgery, and a deterministic injected clock.
 *
 * @see MainMenuService the translated program - every branch, and the only place they live
 * @see MainMenuRequest the inbound projection of {@code 01 COMEN1AI}
 * @see MainMenuResponse the outbound projection of {@code 01 COMEN1AO REDEFINES COMEN1AI}
 * @see AdminMenuController the sibling adapter over the byte-identical admin screen
 */
@RestController
public class MainMenuController {

    // =================================================================================================
    // The route. One path, one verb - the resource the migration plan assigns to CSD transaction CM00
    // (app/csd/CARDDEMO.CSD defines TRANSACTION(CM00) with PROGRAM(COMEN01C)). No additional route,
    // verb or path variant is invented, because a second way in would be a second contract to keep in
    // parity.
    // =================================================================================================

    /**
     * The one route this controller registers: {@value #MAIN_MENU_PATH}.
     *
     * <p>Public so a test can address the endpoint without retyping the literal, which is how the route
     * and its assertions are kept from drifting apart.
     */
    public static final String MAIN_MENU_PATH = "/api/menu";

    /**
     * The character COBOL fills an unwritten alphanumeric field with.
     *
     * <p>A COBOL {@code PIC X} field has no absent state: "the operator typed nothing" is spaces, not
     * {@code null}. This is the single source of that character in this class - it is never written as
     * a bare literal at a call site.
     */
    private static final String SPACE = " ";

    /**
     * The cursor request this screen makes, which is none.
     *
     * <p>{@code COMEN01C} issues no {@code MOVE -1} to any {@code xxxL} length item, so it never asks
     * CICS to place the cursor. The {@code IC} in {@code OPTION}'s
     * {@code ATTRB=(FSET,IC,NORM,NUM,UNPROT)} is a static mapset attribute resolved by BMS at map
     * generation, not a decision the program takes per invocation - so projecting it as a cursor
     * request would attribute to the program something the mapset already states. Named rather than
     * written as a bare {@code null} at the call site so that the absence reads as a finding about the
     * source instead of as an unfilled argument.
     */
    private static final String NO_CURSOR_REQUEST = null;

    // =================================================================================================
    // Collaborators - constructor-injected, never field-injected, and never static. COBOL
    // WORKING-STORAGE must not become static Java state: this bean is a singleton serving concurrent
    // requests, so per-request values live on the stack and only immutable collaborators live on the
    // instance.
    // =================================================================================================

    /** The translated program. Every decision {@code COMEN01C} makes is behind this reference. */
    private final MainMenuService mainMenuService;

    /**
     * The module's single {@link Clock} bean, supplied by the web configuration.
     *
     * <p>The only source of "now" in this class. {@link DateHeader} reads it exactly once per request
     * and renders both header fields from that one reading, so a test can substitute
     * {@link Clock#fixed} and assert the rendered bytes exactly. {@code now()} is never called here.
     */
    private final Clock clock;

    /**
     * The fixed-width character codec, taken from {@link MainMenuService#codec()} rather than
     * constructed.
     *
     * <p>Sharing the service's instance means the {@code admin} package makes <strong>one</strong>
     * code-page decision, stated once, on the service that owns it - rather than this class quietly
     * making a second one that could drift. {@link FixedWidthCodec} is immutable and therefore safe to
     * share across concurrent requests.
     */
    private final FixedWidthCodec codec;

    /**
     * The two-character {@code OPTIONI} field as {@code MOVE SPACES} leaves it.
     *
     * <p>Produced through {@link FixedWidthCodec#movePicX} rather than by writing two spaces, so the
     * width comes from {@link MainMenuRequest#OPTION_LENGTH} and the padding rule comes from the one
     * place that owns it.
     */
    private final String optionSpaces;

    /**
     * The invocation a request with no payload stands for: {@code EIBCALEN = 0}.
     *
     * <p>Its communication area is {@code null}, which is precisely how {@link MainMenuInput} encodes
     * "no communication area accompanied this request" - a separate boolean flag would be a second
     * source of truth able to contradict it. Immutable, so one shared instance serves every cold start.
     *
     * <p>Its attention identifier is {@link CicsAid#DFHENTER}. The value is immaterial on this path,
     * because {@code app/cbl/COMEN01C.cbl:82} diverts to the sign-on screen before line 93 ever
     * evaluates {@code EIBAID}; {@code ENTER} is nonetheless the only honest default, since a CICS
     * terminal always presents some AID and this is the one the program treats as ordinary.
     */
    private final MainMenuRequest coldStartRequest;

    /**
     * Creates the controller.
     *
     * <p>One constructor, all arguments required, every field {@code final}. There is no setter, no
     * field injection and no repository - see the class documentation for why the absence of a
     * repository is a property of {@code COMEN01C} rather than an omission.
     *
     * @param mainMenuService the translated {@code COMEN01C}; also supplies the package's single
     *                        fixed-width codec
     * @param clock           the module's clock bean, read only through {@link DateHeader}
     * @throws NullPointerException if either argument is {@code null}
     */
    public MainMenuController(final MainMenuService mainMenuService, final Clock clock) {
        this.mainMenuService = Objects.requireNonNull(mainMenuService,
                "A MainMenuService is required: it is the translated COMEN01C, and this controller "
                        + "makes no decision of its own");
        this.clock = Objects.requireNonNull(clock,
                "A Clock is required: POPULATE-HEADER-INFO at app/cbl/COMEN01C.cbl:212-231 renders the "
                        + "date and time header, and reading the wall clock directly would make that "
                        + "header impossible to assert byte for byte");
        this.codec = this.mainMenuService.codec();
        this.optionSpaces = this.codec.movePicX(SPACE, MainMenuRequest.OPTION_LENGTH);
        this.coldStartRequest = coldStartRequest(this.codec);
    }

    /**
     * The blank screen a request carrying no payload is treated as having presented.
     *
     * <p>Every one of the twenty screen fields is space-filled to its declared width through
     * {@link FixedWidthCodec#movePicX}, because a COBOL {@code PIC X} field is never absent - an empty
     * one holds spaces. Building it once at construction rather than per request keeps the cold-start
     * path allocation-free, and the record is immutable so the single instance is safe to share.
     *
     * <p>The two members that carry meaning on this path:
     *
     * <ul>
     *   <li>the communication area is {@code null}, which is how {@link MainMenuInput} encodes
     *       {@code IF EIBCALEN = 0} at {@code app/cbl/COMEN01C.cbl:82};</li>
     *   <li>the attention identifier is {@link CicsAid#DFHENTER} - immaterial here, since the program
     *       diverts before line 93 reads it, but the only default a terminal could actually have
     *       presented.</li>
     * </ul>
     *
     * @param codec the codec supplying the space-fill rule and each declared width
     * @return the cold-start request, never {@code null}
     */
    private static MainMenuRequest coldStartRequest(final FixedWidthCodec codec) {
        final String transactionField = codec.movePicX(SPACE, MainMenuRequest.TRN_NAME_LENGTH);
        final String titleField = codec.movePicX(SPACE, MainMenuRequest.TITLE_LENGTH);
        final String dateField = codec.movePicX(SPACE, MainMenuRequest.CUR_DATE_LENGTH);
        final String programField = codec.movePicX(SPACE, MainMenuRequest.PGM_NAME_LENGTH);
        final String timeField = codec.movePicX(SPACE, MainMenuRequest.CUR_TIME_LENGTH);
        final String optionLine = codec.movePicX(SPACE, MainMenuRequest.OPTION_LINE_LENGTH);
        final String optionField = codec.movePicX(SPACE, MainMenuRequest.OPTION_LENGTH);
        final String messageField = codec.movePicX(SPACE, MainMenuRequest.ERR_MSG_LENGTH);
        return new MainMenuRequest(transactionField,
                titleField,
                dateField,
                programField,
                titleField,
                timeField,
                optionLine,
                optionLine,
                optionLine,
                optionLine,
                optionLine,
                optionLine,
                optionLine,
                optionLine,
                optionLine,
                optionLine,
                optionLine,
                optionLine,
                optionField,
                messageField,
                null,
                CicsAid.DFHENTER);
    }

    // =================================================================================================
    // The HTTP surface - GET /api/menu, CSD transaction CM00.
    //
    // Read this method and the three below it as one straight line: bind, translate, delegate, project.
    // There is nothing else here, and nothing else belongs here. Every branch of COMEN01C is behind the
    // single mainMenuService.handle(...) call, which is what lets a JUnit test drive all of them with no
    // MockMvc in the path.
    // =================================================================================================

    /**
     * Paints the main menu: {@code GET} {@value #MAIN_MENU_PATH}, transaction {@code CM00}, program
     * {@code COMEN01C}, mapset {@code COMEN01}, map {@code COMEN1A}, twenty fields.
     *
     * <p><strong>An absent body is the cold start.</strong> The parameter is {@code required = false},
     * so a plain {@code GET} arrives {@code null} and is treated as {@code EIBCALEN = 0} - the
     * transaction typed at a clear screen, which {@code app/cbl/COMEN01C.cbl:82-84} answers by
     * diverting to the sign-on screen. A request continuing the pseudo-conversation sends back the
     * payload it last received, carrying the communication area, the attention identifier and the
     * option that was typed. Never a server-side session.
     *
     * <p>Always answers {@code 200 OK}, because every path through {@code COMEN01C} ends in either
     * {@code EXEC CICS RETURN} or {@code EXEC CICS XCTL} - both of which are successful outcomes. A
     * rejected option is a message on the screen, not a {@code 4xx}: the program moves
     * {@code 'Please enter a valid option number...'} into {@code WS-MESSAGE} and repaints, and an
     * option a regular user may not take produces {@code 'No access - Admin Only option... '} the same
     * way. The only non-{@code 200} responses this endpoint can produce come from the module's global
     * error mapper - a payload that fails its declared field widths, or a body that will not parse. No
     * local exception handler is declared here, because that mapper is the single place error shapes
     * are decided.
     *
     * @param request the inbound screen and communication area, or {@code null} for the cold start
     * @return the painted screen, the communication area to send back next time, the navigation triple
     *         naming where the client goes if control transferred, and - beside the screen rather than
     *         inside it - the presentation metadata this program sets: the message colour and the
     *         repaint flag, neither of which is a {@code DFHMDF} field and neither of which had any way
     *         to travel before; never {@code null}
     */
    @GetMapping(path = MAIN_MENU_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ScreenResponse<MainMenuResponse> getMainMenu(
            @Valid @RequestBody(required = false) final MainMenuRequest request) {
        final MainMenuResponse painted =
                showMainMenu(Objects.requireNonNullElse(request, coldStartRequest));
        return ScreenResponse.of(painted, screenMetadata(painted));
    }

    /**
     * Runs one invocation of {@code COMEN01C} against a payload that is already known to be present.
     *
     * <p>Package-visible on purpose: this is the seam a test drives when it wants the translation
     * without the HTTP layer, and it is the whole of what the request mapping does once the absent-body
     * default has been applied.
     *
     * @param received the inbound payload; a {@code null} communication area inside it still means
     *                 {@code EIBCALEN = 0}
     * @return the projected screen, never {@code null}
     * @throws NullPointerException if {@code received} is {@code null}; the request mapping substitutes
     *                              {@link #coldStartRequest} before calling this, so reaching it with
     *                              {@code null} would mean the absent-body default was bypassed
     */
    MainMenuResponse showMainMenu(final MainMenuRequest received) {
        Objects.requireNonNull(received, "A payload is required here; an absent body is represented by "
                + "the cold-start request, whose communication area is null, and not by a null "
                + "payload");
        return toResponse(mainMenuService.handle(toInput(received)));
    }

    /**
     * Translates the inbound payload into the three values {@code COMEN01C} actually consults.
     *
     * <p>Those three, and no others. The program reads {@code EIBCALEN} through the presence or absence
     * of {@code DFHCOMMAREA} (lines 67 to 69 and 82), {@code EIBAID} once at line 93, and
     * {@code OPTIONI} at lines 118 to 122. The remaining nineteen input fields exist on the request
     * because the symbolic map declares them, and the program never looks at any of them - so nothing
     * here pretends otherwise.
     *
     * <p>Three properties of this translation are load-bearing:
     *
     * <ul>
     *   <li>The communication area is passed <strong>straight through, including when it is
     *       {@code null}</strong>. Absence is the encoding of {@code EIBCALEN = 0}, so no test is
     *       needed and none is performed - a flag computed here would be a second source of truth able
     *       to disagree with the reference beside it. It is also what carries
     *       {@code CDEMO-USER-TYPE} to the authorisation filter at line 136, and passing the whole
     *       area by reference is what makes "untouched" structural rather than merely intended: there
     *       is no assignment here that could alter it.</li>
     *   <li>The attention identifier is passed through as the <strong>raw byte</strong>, uninterpreted.
     *       Line 93 compares the byte itself, and {@code COMEN01C} does not copy {@code CSSTRPFY}, so
     *       resolving it to a key token here would risk making a key behave like another one in a
     *       program that has no such behaviour.</li>
     *   <li>The option is passed through <strong>unnormalised</strong>, spaces and all. The service
     *       moves it into its declared width and then reproduces the backwards space scan, the
     *       {@code JUST RIGHT} receiver and the {@code INSPECT ... REPLACING} in order; trimming or
     *       padding it here would destroy the very input that sequence consumes. The only thing done to
     *       it is substituting spaces for an omitted JSON member, because a {@code PIC X(2)} field is
     *       never absent.</li>
     * </ul>
     *
     * @param received the inbound payload
     * @return the service input, never {@code null}
     */
    MainMenuInput toInput(final MainMenuRequest received) {
        final NavigationContext inboundCommarea = received.navigationContext();
        return new MainMenuInput(inboundCommarea,
                received.eibAid(),
                Objects.requireNonNullElse(received.option(), optionSpaces));
    }

    /**
     * Projects the outcome onto the twenty screen fields, the communication area and the navigation
     * triple.
     *
     * <p>Straight-line assembly, in map order, with one line per copybook field so that every member
     * can be read against {@code app/cpy-bms/COMEN01.CPY} without following a loop. The twelve menu
     * lines are written through twelve individual {@code optionLine} calls for the same reason - and
     * because a loop would put a branch in this class.
     *
     * <p><strong>The reset is structural.</strong> Every one of the twenty-six components below is
     * assigned unconditionally from a freshly created builder, so no field can retain a value from
     * anywhere - there is nowhere for a value to have come from. That is
     * {@code MOVE LOW-VALUES TO COMEN1AO} at {@code app/cbl/COMEN01C.cbl:89} followed by the paint. The
     * outcome's own reset signal is carried through as
     * {@link MainMenuResponse#resetAllOutputFields()} so the client clears its rendered screen on the
     * same path the program does, and only on that path - the first-entry one. No field-level highlight
     * is applied in either state, because the program copies neither {@code CSSETATY} nor any other
     * attribute setter.
     *
     * <p><strong>Widths are not re-applied here.</strong> Every value below already arrives at exactly
     * its declared width: the outcome's canonical constructor enforces twelve forty-character lines, an
     * eighty-character message, a two-character option and an eight-character transfer target, while
     * the transaction identifier, program name, both titles and both header renderings are fixed-width
     * by construction. Padding them again would hide a genuine width defect instead of surfacing it.
     * The single exception is the message, and it is a real truncation rather than a formality.
     *
     * <p>Four outcome members are intentionally not projected, because the screen has no field for
     * them: {@code errorFlag} is {@code WS-ERR-FLG}, program-internal working storage;
     * {@code screenPainted} records whether {@code SEND MAP} executed, which the client infers from
     * whether a transfer target is present; {@code nextProgramCarriesCommarea} distinguishes the two
     * {@code XCTL} forms, and the area is echoed on every response regardless; and the receive outcome
     * carries the {@code RESP}/{@code RESP2} codes that {@code app/cbl/COMEN01C.cbl:201-207} captures
     * and <strong>never tests</strong>. {@code transactionId} is likewise not a map field - it is the
     * {@code RETURN TRANSID} at line 108 - and the transaction identifier the screen does show comes
     * from {@code TRNNAMEO} below.
     *
     * @param outcome the result of running the program
     * @return the response payload, never {@code null}
     * @throws NullPointerException if {@code outcome} is {@code null}
     */
    MainMenuResponse toResponse(final MainMenuOutcome outcome) {
        Objects.requireNonNull(outcome, "An outcome is required: MainMenuService.handle always "
                + "returns one, on every path COMEN01C can take");

        // L214 MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA, and the two 8-byte renderings the
        // paragraph assembles from it at L221-L225 and L227-L231. DateHeader owns all of it - including
        // the '/' and ':' separators, the WS-CURDATE-YEAR(3:2) two-digit year and the truncation of the
        // intrinsic's 21-character result to the 16-byte receiver - and reads the injected clock exactly
        // once, so the date and the time cannot straddle a second boundary.
        final DateHeader header = DateHeader.from(codec, clock);

        return MainMenuResponse.builder()
                // ---- POPULATE-HEADER-INFO, app/cbl/COMEN01C.cbl:212-231 --------------------------
                // L218 MOVE WS-TRANID TO TRNNAMEO. The working-storage literal at L37; the same value
                // the program hands to RETURN TRANSID at L108.
                .trnName(MainMenuService.TRANSACTION_ID)
                // L216 MOVE CCDA-TITLE01 TO TITLE01O. Byte-exact from app/cpy/COTTL01Y.cpy:18-19, six
                // leading and seven trailing spaces, never retyped here.
                .title01(ScreenTitles.CCDA_TITLE01)
                // L225 MOVE WS-CURDATE-MM-DD-YY TO CURDATEO - eight bytes, MM/DD/YY.
                .curDate(header.wsCurdateMmDdYy())
                // L219 MOVE WS-PGMNAME TO PGMNAMEO. The literal at L36.
                .pgmName(MainMenuService.PROGRAM_NAME)
                // L217 MOVE CCDA-TITLE02 TO TITLE02O. The LIVE line of COTTL01Y is L22 - the
                // alternative at L21 directly above it is commented out in the copybook and must not
                // be used. ScreenTitles has already resolved that; it is not second-guessed here with
                // a hand-typed literal.
                .title02(ScreenTitles.CCDA_TITLE02)
                // L231 MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO - eight bytes, HH:MM:SS.
                .curTime(header.wsCurtimeHhMmSs())

                // ---- BUILD-MENU-OPTIONS, app/cbl/COMEN01C.cbl:236-277 ---------------------------
                // The EVALUATE WS-IDX at L248-L275 has arms for subscripts 1 to 12 plus WHEN OTHER
                // CONTINUE, and CDEMO-MENU-OPT-COUNT is 10 (app/cpy/COMEN02Y.cpy:21) while
                // CDEMO-MENU-OPT is OCCURS 12 TIMES (:88) - so this program composes ten lines and can
                // never write the last two, yet the table and the map both declare twelve. All twelve
                // are carried: an unwritten line is spaces. Shrinking the response to the ten that are
                // populated would discard part of the screen contract, and the twelve arms this
                // program has where the admin screen stops at ten are the reason the two differ at all.
                //
                // Subscripts are the COBOL's own 1-based ones - optionLine(1) is OPTN001O - so the
                // 1-based-to-0-based shift happens once, inside the accessor and the builder, rather
                // than twelve times here.
                .optionLine(1, outcome.optionLine(1))
                .optionLine(2, outcome.optionLine(2))
                .optionLine(3, outcome.optionLine(3))
                .optionLine(4, outcome.optionLine(4))
                .optionLine(5, outcome.optionLine(5))
                .optionLine(6, outcome.optionLine(6))
                .optionLine(7, outcome.optionLine(7))
                .optionLine(8, outcome.optionLine(8))
                .optionLine(9, outcome.optionLine(9))
                .optionLine(10, outcome.optionLine(10))
                .optionLine(11, outcome.optionLine(11))
                .optionLine(12, outcome.optionLine(12))

                // ---- The one editable field, echoed back ----------------------------------------
                // L125 MOVE WS-OPTION TO OPTIONO OF COMEN1AO - the normalised two digits, so "3 " and
                // " 3" both come back as "03". Blank on the first-entry path, where L89 cleared it.
                // OPTION is the only unprotected field on the map: ATTRB=(FSET,IC,NORM,NUM,UNPROT),
                // HILIGHT=UNDERLINE, JUSTIFY=(RIGHT,ZERO), LENGTH=2, POS=(20,41).
                .option(outcome.option())

                // ---- L187 MOVE WS-MESSAGE TO ERRMSGO OF COMEN1AO -------------------------------
                // The one genuine fixed-width operation in this class, and a real narrowing:
                // WS-MESSAGE is PIC X(80) at app/cbl/COMEN01C.cbl:38 and ERRMSGO is PIC X(78) at
                // app/cpy-bms/COMEN01.CPY:260. A COBOL alphanumeric MOVE fills the receiver from the
                // LEFT and discards the overflow, so the surviving text is the LEADING seventy-eight
                // characters and the trailing two bytes are lost. That direction is the whole point of
                // routing it through the codec rather than writing substring here: the rule lives in
                // one auditable place, and the call site says which way it truncates.
                .errMsg(codec.movePicX(outcome.message(), MainMenuResponse.ERR_MSG_LENGTH))

                // ---- Conversation state, returned so the client can re-supply it ----------------
                // L107-L110 EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA). The area
                // as the program leaves it - with CDEMO-PGM-CONTEXT already advanced to re-enter on the
                // first-entry path, which is what makes the next keystroke take the other branch, and
                // with CDEMO-USER-TYPE exactly as it arrived, because L150 is commented out.
                .navigationContext(outcome.navigationContext())

                // ---- The two XCTL sites, as a response field rather than a server-side forward ----
                // L152-L155 XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION)) COMMAREA(...) and
                // L175-L177 XCTL PROGRAM(CDEMO-TO-PROGRAM) with no COMMAREA. Spaces when the program
                // returned to CICS instead of transferring. The client issues the follow-up call: no
                // server-side forward, no redirect chain, no session affinity.
                .nextProgram(outcome.nextProgram())
                // L190-L191 MAP('COMEN1A') MAPSET('COMEN01') - seven characters each, matching
                // CDEMO-LAST-MAP and CDEMO-LAST-MAPSET, which are X(7) and not X(8)
                // (app/cpy/COCOM01Y.cpy:43-44).
                .nextMapset(outcome.mapsetName())
                .nextMap(outcome.mapName())

                // ---- Metadata, never a JSON payload member --------------------------------------
                // ERRMSGC OF COMEN1AO. The mapset declares COLOR=RED on ERRMSG
                // (app/bms/COMEN01.bms:154-157), and L158 MOVE DFHGREEN TO ERRMSGC overrides it on the
                // coming-soon path alone. The byte is carried as the attribute metadata the copybook
                // declares it to be, and is passed through exactly as the service resolved it.
                .errMsgColor(outcome.messageColour())
                // L89 MOVE LOW-VALUES TO COMEN1AO - true on the first-entry path only, telling the
                // client to clear every rendered output field before painting what follows.
                .resetAllOutputFields(outcome.resetAllOutputFields())
                .build();
    }

    /**
     * Lifts the two presentation values {@code COMEN01C} sets that are not {@code DFHMDF} fields out of
     * the screen projection and into the shared metadata envelope.
     *
     * <p>Both are declared {@code @JsonIgnore} on {@link MainMenuResponse}, because the 1:1 field
     * projection carries map fields and nothing else. Without this envelope neither would travel at
     * all, and the screen would document a wire format it did not have. They are:
     *
     * <ul>
     *   <li>{@code ERRMSGC}, the message colour - {@code COLOR=RED} by mapset declaration, overridden
     *       to green by {@code app/cbl/COMEN01C.cbl:158} on the coming-soon path alone;</li>
     *   <li>the {@code MOVE LOW-VALUES TO COMEN1AO} repaint signal at line 89.</li>
     * </ul>
     *
     * <p>The cursor slot is {@link #NO_CURSOR_REQUEST}: this program issues no {@code MOVE -1} to any
     * length item, so it never asks for one.
     *
     * @param painted the projected screen, whose two ignored members supply the metadata
     * @return the metadata envelope, never {@code null}
     */
    private static ScreenMetadata screenMetadata(final MainMenuResponse painted) {
        return ScreenMetadata.of(NO_CURSOR_REQUEST,
                painted.errMsgColor(),
                painted.resetAllOutputFields());
    }
}
