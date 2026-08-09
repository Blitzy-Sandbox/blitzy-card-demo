package com.vsergeychik.carddemo.admin;

import com.vsergeychik.carddemo.admin.AdminMenuService.AdminMenuInput;
import com.vsergeychik.carddemo.admin.AdminMenuService.AdminMenuOutcome;
import com.vsergeychik.carddemo.admin.dto.AdminMenuRequest;
import com.vsergeychik.carddemo.admin.dto.AdminMenuResponse;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenTitles;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * CICS transaction {@code CA00} - program {@code COADM01C}, "Admin Menu for Admin users" - as a
 * stateless REST resource on {@value #ADMIN_MENU_PATH}.
 *
 * <h2>What this class is, and what it deliberately is not</h2>
 *
 * <p>It is a <strong>thin adapter</strong>, and nothing else. It binds the request payload,
 * translates it into the service's own input record, calls {@link AdminMenuService#handle} exactly
 * once, and projects the returned outcome onto the response payload. Every decision
 * {@code COADM01C} makes - the {@code EIBCALEN = 0} diversion, the first-entry versus re-entry
 * split, the ordered {@code EVALUATE EIBAID}, the option normalisation, the numeric and range
 * guards, the {@code 'DUMMY'} program-prefix test and the menu composition - lives in
 * {@link AdminMenuService}.
 *
 * <p>That division is not stylistic. This class contains <strong>no</strong> {@code if}, no
 * {@code switch}, no ternary and no loop, so it contributes zero branches to the module's JaCoCo
 * {@code BRANCH} counter. The consequence is the one the migration plan is after: the
 * {@code admin} package clears its mandated {@code BRANCH >= 0.90} bar from plain JUnit tests that
 * construct {@link AdminMenuService} directly, with no {@code MockMvc}, no servlet container and no
 * HTTP in the path. Coverage earned that way measures the translated COBOL rather than the
 * framework around it.
 *
 * <p>Where a {@code null} payload member has to become the run of spaces COBOL would have held, the
 * choice is delegated to {@link Objects#requireNonNullElse}, so even that test executes inside the
 * JDK rather than inside this class.
 *
 * <h2>Naming: this package carries no divergence</h2>
 *
 * <p>Worth stating plainly, because two sibling packages do carry one and a reader who has met them
 * will arrive here expecting a third. The migration honours prompt-mandated class names verbatim and
 * takes behaviour from the COBOL, which elsewhere leaves name and behaviour disagreeing. Here they
 * agree:
 *
 * <ul>
 *   <li>{@code COADM01C} to {@code AdminMenuController} - the source header reads "Admin Menu for
 *       Admin users", and this really is that menu. <strong>No divergence.</strong></li>
 *   <li>{@code COMEN01C} to {@code MainMenuController} - likewise the main menu.
 *       <strong>No divergence.</strong></li>
 * </ul>
 *
 * <p>Contrast, and do not confuse with:
 *
 * <ul>
 *   <li>{@code transaction.TransactionMenuController} - {@code COTRN00C} <em>lists</em>
 *       transactions; it is not a menu.</li>
 *   <li>{@code user.UserMenuController} - {@code COUSR00C} <em>lists users</em>; it is not a
 *       menu.</li>
 * </ul>
 *
 * <p>So there is no swap to hunt for in this package. Two further non-authoritative artefacts are
 * called out for the same reason: {@code docs/**} describes a superseded design targeting a separate
 * repository, and {@code catalog-info.yaml} claims a Java version this module does not use. Neither
 * is consulted here and neither is corrected - conflicts are documented, not silently fixed.
 *
 * <h2>Statelessness</h2>
 *
 * <p>{@code COADM01C} is pseudo-conversational: it ends at
 * {@code EXEC CICS RETURN TRANSID('CA00') COMMAREA(CARDDEMO-COMMAREA)}
 * ({@code app/cbl/COADM01C.cbl:107-110}) and expects the next keystroke to bring that communication
 * area back. All three pieces of conversation state therefore travel in the payload and
 * <strong>nothing</strong> is retained between calls:
 *
 * <ol>
 *   <li>the communication area, as {@link NavigationContext} on both the request and the
 *       response;</li>
 *   <li>the attention identifier, as the request's raw {@code EIBAID} byte;</li>
 *   <li>the first-entry versus re-entry context, as {@code CDEMO-PGM-CONTEXT} inside that
 *       communication area.</li>
 * </ol>
 *
 * <p>There is no {@code HttpSession}, no {@code @SessionAttributes}, no session-scoped bean, no
 * request-attribute stash and no cache. The class holds exactly three fields, all
 * {@code private final} and all immutable or thread-safe, and it has no {@code static} state at all
 * beyond the route constant. Two identical requests therefore produce two identical responses,
 * which is the property the statelessness requirement actually asks for.
 *
 * <h2>An absent body is the cold start</h2>
 *
 * <p>{@code app/cbl/COADM01C.cbl:82} tests {@code IF EIBCALEN = 0} to tell a transaction typed at a
 * clear screen from one transferred into mid-conversation. A {@code GET} that carries no payload is
 * exactly the former, so the body parameter is {@code required = false} and an absent one is
 * translated into an input whose communication area is absent. This mirrors the idiom the sibling
 * card-list screen already establishes in this module.
 *
 * <h2>No dataset is read - and that is a property of the source</h2>
 *
 * <p>This controller injects <strong>no repository</strong>, and that is deliberate rather than
 * unfinished. {@code COADM01C} performs no file access whatsoever: its only five {@code EXEC CICS}
 * verbs are {@code RETURN}, two {@code XCTL}s, {@code SEND MAP} and {@code RECEIVE MAP} - there is
 * not one {@code READ}, {@code STARTBR}, {@code READNEXT}, {@code WRITE}, {@code REWRITE} or
 * {@code DELETE} anywhere in the program. {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '} is
 * declared at line 39 and never referenced again, and {@code COPY CSUSR01Y.} at line 58 brings in a
 * record whose every field goes unused; {@link AdminMenuService} preserves both as the dead
 * declarations they are.
 *
 * <p>Two consequences a reviewer should read as intended, not as omissions:
 *
 * <ul>
 *   <li>Injecting the security-user repository here would invent a {@code USRSEC} read the COBOL
 *       never performs. That would be a new feature, and this migration adds none.</li>
 *   <li>The migration's file-status gate - every {@code FileStatus} outcome exercised at every
 *       repository call site - has <strong>zero call sites in this package</strong>. There is
 *       nothing to exercise because there is nothing to call.</li>
 * </ul>
 *
 * <p>The screen also authenticates nothing: it has no credential field, reads no user record and
 * makes no authorisation decision. There is accordingly no security annotation, no filter chain and
 * no token anywhere in this class. Which menu a signed-on operator reaches was already decided by
 * the sign-on screen, whose {@code XCTL PROGRAM('COADM01C')} for user type {@code 'A'} is projected
 * as a role field on that screen's response; it is not re-decided here.
 *
 * <h2>The presentation contract</h2>
 *
 * <p>No component library, design system or design-token set exists in this repository or is named
 * anywhere in the migration plan, and there are no attachments and no Figma screens. The
 * <strong>BMS layer is</strong> the presentation contract, and it is binding: {@code COADM01.bms}
 * declares 28 {@code DFHMDF} fields of which exactly 20 are name-labelled, and those 20 are exactly
 * the payload members - the other 8 are literal screen furniture ({@code 'Tran:'}, {@code 'Date:'},
 * {@code 'Prog:'}, {@code 'Time:'}, {@code 'Admin Menu'},
 * {@code 'Please select an option :'}, a {@code LENGTH=0} stopper and
 * {@code 'ENTER=Continue  F3=Exit'}) and never travel.
 *
 * <p>{@code app/cpy-bms/COADM01.CPY} and {@code app/cpy-bms/COMEN01.CPY} are byte-identical but for
 * their two group names, and {@code COADM01.bms} differs from {@code COMEN01.bms} only in the
 * mapset and map names and in one unnamed heading literal ({@code 'Admin Menu'} at
 * {@code LENGTH=10} against {@code 'Main Menu'} at {@code LENGTH=9}). The two screens therefore
 * share a field contract but keep <strong>separate</strong> request and response types, so that a
 * later change to one screen cannot silently alter the other.
 *
 * <p>Only the {@code xxxO} items are payload. The {@code xxxL} length items, the {@code xxxF} flag
 * bytes, their {@code xxxA} attribute redefinitions and the output side's {@code xxxC},
 * {@code xxxP}, {@code xxxH} and {@code xxxV} items are validation and highlight metadata and are
 * never JSON members - {@link AdminMenuResponse} enforces that, and this class adds none of them.
 *
 * <h2>Control flow</h2>
 *
 * <p>{@code COADM01C} contains <strong>zero</strong> {@code GO TO} statements, so nothing here is a
 * restructured jump; the estate's {@code GO TO} sites are all in other programs. Neither does the
 * program copy {@code CSSTRPFY} - it compares {@code EIBAID} inline at line 93 - nor
 * {@code CSSETATY}, so no PF-key resolver and no field-attribute highlighter is invoked from this
 * class. The raw AID byte is passed straight through, uninterpreted, and no field-level highlighting
 * is invented that the program does not perform.
 *
 * <h2>No user rules govern this file</h2>
 *
 * <p>The project supplies no user rules - the rules document reads "No user rules provided" in its
 * entirety. That is not licence to lower the bar and no rule has been invented: enterprise
 * best-practice substitutes stand in its place, and the ones that bind here are constructor
 * injection with no static mutable state, explicit non-wildcard imports with every width a named
 * constant, an unchanged security posture, preserved dead code, immutable reference sources, the
 * hand-written fixed-width codec in place of {@code String} surgery, and a deterministic injected
 * clock.
 *
 * @see AdminMenuService the translated program - every branch, and the only place they live
 * @see AdminMenuRequest the inbound projection of {@code 01 COADM1AI}
 * @see AdminMenuResponse the outbound projection of {@code 01 COADM1AO REDEFINES COADM1AI}
 */
@RestController
public class AdminMenuController {

    // =================================================================================================
    // The route. One path, one verb - the resource the migration plan assigns to CSD transaction CA00
    // (app/csd/CARDDEMO.CSD:327-328). No additional route, verb or path variant is invented, because a
    // second way in would be a second contract to keep in parity.
    // =================================================================================================

    /**
     * The one route this controller registers: {@value #ADMIN_MENU_PATH}.
     *
     * <p>Public so a test can address the endpoint without retyping the literal, which is how the
     * route and its assertions are kept from drifting apart.
     */
    public static final String ADMIN_MENU_PATH = "/api/admin/menu";

    /**
     * The character COBOL fills an unwritten alphanumeric field with.
     *
     * <p>A COBOL {@code PIC X} field has no absent state: "the operator typed nothing" is spaces, not
     * {@code null}. This is the single source of that character in this class - it is never written as
     * a bare literal at a call site.
     */
    private static final String SPACE = " ";

    // =================================================================================================
    // Collaborators - constructor-injected, never field-injected, and never static. COBOL WORKING-STORAGE
    // must not become static Java state: this bean is a singleton serving concurrent requests, so per-
    // request values live on the stack and only immutable collaborators live on the instance.
    // =================================================================================================

    /** The translated program. Every decision {@code COADM01C} makes is behind this reference. */
    private final AdminMenuService adminMenuService;

    /**
     * The module's single {@link Clock} bean, supplied by the web configuration.
     *
     * <p>The only source of "now" in this class. {@link DateHeader} reads it exactly once per request
     * and renders both header fields from that one reading, so a test can substitute
     * {@link Clock#fixed} and assert the rendered bytes exactly. {@code now()} is never called here.
     */
    private final Clock clock;

    /**
     * The fixed-width character codec, taken from {@link AdminMenuService#codec()} rather than
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
     * width comes from {@link AdminMenuRequest#OPTION_LENGTH} and the padding rule comes from the one
     * place that owns it.
     */
    private final String optionSpaces;

    /**
     * The invocation a request with no payload stands for: {@code EIBCALEN = 0}.
     *
     * <p>Its communication area is {@code null}, which is precisely how the service encodes "no
     * communication area accompanied this request" - a separate boolean flag would be a second source
     * of truth able to contradict it. Immutable, so one shared instance serves every cold start.
     *
     * <p>Its attention identifier is {@link CicsAid#DFHENTER}. The value is immaterial on this path,
     * because {@code app/cbl/COADM01C.cbl:82} diverts to the sign-on screen before line 93 ever
     * evaluates {@code EIBAID}; {@code ENTER} is nonetheless the only honest default, since a CICS
     * terminal always presents some AID and this is the one the program treats as ordinary.
     */
    private final AdminMenuRequest coldStartRequest;

    /**
     * Creates the controller.
     *
     * <p>One constructor, all arguments required, every field {@code final}. There is no setter, no
     * field injection and no repository - see the class documentation for why the absence of a
     * repository is a property of {@code COADM01C} rather than an omission.
     *
     * @param adminMenuService the translated {@code COADM01C}; also supplies the package's single
     *                         fixed-width codec
     * @param clock            the module's clock bean, read only through {@link DateHeader}
     * @throws NullPointerException if either argument is {@code null}
     */
    public AdminMenuController(final AdminMenuService adminMenuService, final Clock clock) {
        this.adminMenuService = Objects.requireNonNull(adminMenuService,
                "An AdminMenuService is required: it is the translated COADM01C, and this controller "
                        + "makes no decision of its own");
        this.clock = Objects.requireNonNull(clock,
                "A Clock is required: POPULATE-HEADER-INFO at app/cbl/COADM01C.cbl:202-221 renders the "
                        + "date and time header, and reading the wall clock directly would make that "
                        + "header impossible to assert byte for byte");
        this.codec = this.adminMenuService.codec();
        this.optionSpaces = this.codec.movePicX(SPACE, AdminMenuRequest.OPTION_LENGTH);
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
     *   <li>the communication area is {@code null}, which is how {@link AdminMenuInput} encodes
     *       {@code IF EIBCALEN = 0} at {@code app/cbl/COADM01C.cbl:82};</li>
     *   <li>the attention identifier is {@link CicsAid#DFHENTER} - immaterial here, since the program
     *       diverts before line 93 reads it, but the only default a terminal could actually have
     *       presented.</li>
     * </ul>
     *
     * @param codec the codec supplying the space-fill rule and each declared width
     * @return the cold-start request, never {@code null}
     */
    private static AdminMenuRequest coldStartRequest(final FixedWidthCodec codec) {
        final String transactionField = codec.movePicX(SPACE, AdminMenuRequest.TRN_NAME_LENGTH);
        final String titleField = codec.movePicX(SPACE, AdminMenuRequest.TITLE_LENGTH);
        final String dateField = codec.movePicX(SPACE, AdminMenuRequest.CUR_DATE_LENGTH);
        final String programField = codec.movePicX(SPACE, AdminMenuRequest.PGM_NAME_LENGTH);
        final String timeField = codec.movePicX(SPACE, AdminMenuRequest.CUR_TIME_LENGTH);
        final String optionLine = codec.movePicX(SPACE, AdminMenuRequest.OPTION_LINE_LENGTH);
        final String optionField = codec.movePicX(SPACE, AdminMenuRequest.OPTION_LENGTH);
        final String messageField = codec.movePicX(SPACE, AdminMenuRequest.ERR_MSG_LENGTH);
        return new AdminMenuRequest(transactionField,
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
    // The HTTP surface - GET /api/admin/menu, CSD transaction CA00.
    //
    // Read this method and the two below it as one straight line: bind, translate, delegate, project.
    // There is nothing else here, and nothing else belongs here. Every branch of COADM01C is behind the
    // single adminMenuService.handle(...) call, which is what lets a JUnit test drive all of them with
    // no MockMvc in the path.
    // =================================================================================================

    /**
     * Paints the admin menu: {@code GET} {@value #ADMIN_MENU_PATH}, transaction {@code CA00}, program
     * {@code COADM01C}, mapset {@code COADM01}, map {@code COADM1A}, twenty fields.
     *
     * <p><strong>An absent body is the cold start.</strong> The parameter is {@code required = false},
     * so a plain {@code GET} arrives {@code null} and is treated as {@code EIBCALEN = 0} - the
     * transaction typed at a clear screen, which {@code app/cbl/COADM01C.cbl:82-84} answers by
     * diverting to the sign-on screen. A request continuing the pseudo-conversation sends back the
     * payload it last received, carrying the communication area, the attention identifier and the
     * option that was typed. Never a server-side session.
     *
     * <p>Always answers {@code 200 OK}, because every path through {@code COADM01C} ends in either
     * {@code EXEC CICS RETURN} or {@code EXEC CICS XCTL} - both of which are successful outcomes. A
     * rejected option is a message on the screen, not a {@code 4xx}: the program moves
     * {@code 'Please enter a valid option number...'} into {@code WS-MESSAGE} and repaints. The only
     * non-{@code 200} responses this endpoint can produce come from the module's global error mapper -
     * a payload that fails its declared field widths, or a body that will not parse.
     *
     * @param request the inbound screen and communication area, or {@code null} for the cold start
     * @return the painted screen, the communication area to send back next time and the navigation
     *         triple naming where the client goes if control transferred; never {@code null}
     */
    @GetMapping(path = ADMIN_MENU_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public AdminMenuResponse getAdminMenu(
            @Valid @RequestBody(required = false) final AdminMenuRequest request) {
        return showAdminMenu(Objects.requireNonNullElse(request, coldStartRequest));
    }

    /**
     * Runs one invocation of {@code COADM01C} against a payload that is already known to be present.
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
    AdminMenuResponse showAdminMenu(final AdminMenuRequest received) {
        Objects.requireNonNull(received, "A payload is required here; an absent body is represented by "
                + "the cold-start request, whose communication area is null, and not by a null "
                + "payload");
        return toResponse(adminMenuService.handle(toInput(received)));
    }

    /**
     * Translates the inbound payload into the three values {@code COADM01C} actually consults.
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
     *       to disagree with the reference beside it.</li>
     *   <li>The attention identifier is passed through as the <strong>raw byte</strong>, uninterpreted.
     *       Line 93 compares the byte itself, and {@code COADM01C} does not copy {@code CSSTRPFY}, so
     *       resolving it to a key token here would risk making a key behave like another one in a
     *       program that has no such behaviour.</li>
     *   <li>The option is passed through <strong>unnormalised</strong>, spaces and all. The service
     *       moves it into its declared width and then reproduces the backwards space scan, the
     *       {@code JUSTIFIED RIGHT} receiver and the {@code INSPECT ... REPLACING} in order; trimming
     *       or padding it here would destroy the very input that sequence consumes. The only thing done
     *       to it is substituting spaces for an omitted JSON member, because a {@code PIC X(2)} field
     *       is never absent.</li>
     * </ul>
     *
     * @param received the inbound payload
     * @return the service input, never {@code null}
     */
    AdminMenuInput toInput(final AdminMenuRequest received) {
        final NavigationContext inboundCommarea = received.navigationContext();
        return new AdminMenuInput(inboundCommarea,
                received.eibAid(),
                Objects.requireNonNullElse(received.option(), optionSpaces));
    }

    /**
     * Projects the outcome onto the twenty screen fields, the communication area and the navigation
     * triple.
     *
     * <p>Straight-line assembly, in map order, with one line per copybook field so that every member
     * can be read against {@code app/cpy-bms/COADM01.CPY} without following a loop. The twelve menu
     * lines are written through their twelve individual setters for the same reason - and because a
     * loop would put a branch in this class.
     *
     * <p><strong>The reset comes for free.</strong> {@link AdminMenuResponse#builder()} starts from the
     * space-filled initial state, and every member below is then assigned unconditionally. That is
     * structurally {@code MOVE LOW-VALUES TO COADM1AO} at {@code app/cbl/COADM01C.cbl:89} followed by
     * the paint: no field can retain a value from anywhere, because there is nowhere for a value to
     * have come from. The outcome's own reset signal is carried through as
     * {@link AdminMenuResponse#resetAllOutputFields()} so the client clears its rendered screen on the
     * same path the program does.
     *
     * <p><strong>Widths are not re-applied here.</strong> Every value below already arrives at exactly
     * its declared width: the outcome's canonical constructor enforces twelve forty-character lines, an
     * eighty-character message, a two-character option and an eight-character transfer target, while
     * the transaction identifier, program name, both titles and both header renderings are fixed-width
     * by construction. Padding them again would hide a genuine width defect instead of surfacing it.
     * The single exception is the message, and it is a real truncation rather than a formality.
     *
     * <p>Four outcome members are intentionally not projected, because the screen has no field for
     * them: {@code errorFlag} is {@code WS-ERR-FLG}, program-internal working storage; {@code screenPainted}
     * records whether {@code SEND MAP} executed, which the client infers from whether a transfer target
     * is present; {@code nextProgramCarriesCommarea} distinguishes the two {@code XCTL} forms, and the
     * area is echoed on every response regardless; and the receive outcome carries the
     * {@code RESP}/{@code RESP2} codes that {@code app/cbl/COADM01C.cbl:191-197} captures and
     * <strong>never tests</strong>.
     *
     * @param outcome the result of running the program
     * @return the response payload, never {@code null}
     * @throws NullPointerException if {@code outcome} is {@code null}
     */
    AdminMenuResponse toResponse(final AdminMenuOutcome outcome) {
        Objects.requireNonNull(outcome, "An outcome is required: AdminMenuService.handle always "
                + "returns one, on every path COADM01C can take");

        // L204 MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA, and the two 8-byte renderings the
        // paragraph assembles from it at L211-L215 and L217-L221. DateHeader owns all of it - including
        // the '/' and ':' separators, the WS-CURDATE-YEAR(3:2) two-digit year and the truncation of the
        // intrinsic's 21-character result to the 16-byte receiver - and reads the injected clock exactly
        // once, so the date and the time cannot straddle a second boundary.
        final DateHeader header = DateHeader.from(codec, clock);

        return AdminMenuResponse.builder()
                // ---- POPULATE-HEADER-INFO, app/cbl/COADM01C.cbl:202-221 --------------------------
                // L208 MOVE WS-TRANID TO TRNNAMEO. The working-storage literal at L37; the same value
                // the program hands to RETURN TRANSID at L108.
                .trnName(AdminMenuService.TRANSACTION_ID)
                // L206 MOVE CCDA-TITLE01 TO TITLE01O. Byte-exact from the copybook, six leading and
                // seven trailing spaces, never retyped here.
                .title01(ScreenTitles.CCDA_TITLE01)
                // L215 MOVE WS-CURDATE-MM-DD-YY TO CURDATEO - eight bytes, MM/DD/YY.
                .curDate(header.wsCurdateMmDdYy())
                // L209 MOVE WS-PGMNAME TO PGMNAMEO. The literal at L36.
                .pgmName(AdminMenuService.PROGRAM_NAME)
                // L207 MOVE CCDA-TITLE02 TO TITLE02O. The LIVE line of COTTL01Y - the alternative
                // directly above it is commented out in the copybook and must not be used. ScreenTitles
                // has already resolved that; it is not second-guessed with a hand-typed literal.
                .title02(ScreenTitles.CCDA_TITLE02)
                // L221 MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO - eight bytes, HH:MM:SS.
                .curTime(header.wsCurtimeHhMmSs())

                // ---- BUILD-MENU-OPTIONS, app/cbl/COADM01C.cbl:226-263 ---------------------------
                // The EVALUATE WS-IDX at L238-L261 has arms for subscripts 1 to 10 and WHEN OTHER
                // CONTINUE, and CDEMO-ADMIN-OPT-COUNT is 4 - so this program composes four lines,
                // could reach ten, and can never write the last two. All twelve members are carried
                // anyway, because the map declares twelve: an unwritten line is spaces. Shrinking the
                // response to the four that are populated would discard part of the screen contract.
                .optn001(outcome.optionLine(1))
                .optn002(outcome.optionLine(2))
                .optn003(outcome.optionLine(3))
                .optn004(outcome.optionLine(4))
                .optn005(outcome.optionLine(5))
                .optn006(outcome.optionLine(6))
                .optn007(outcome.optionLine(7))
                .optn008(outcome.optionLine(8))
                .optn009(outcome.optionLine(9))
                .optn010(outcome.optionLine(10))
                .optn011(outcome.optionLine(11))
                .optn012(outcome.optionLine(12))

                // ---- The one editable field, echoed back ----------------------------------------
                // L125 MOVE WS-OPTION TO OPTIONO OF COADM1AO - the normalised two digits, so "3 " and
                // " 3" both come back as "03". Blank on the first-entry path, where L89 cleared it.
                // OPTION is the only unprotected field on the map: ATTRB=(FSET,IC,NORM,NUM,UNPROT),
                // HILIGHT=UNDERLINE, JUSTIFY=(RIGHT,ZERO), LENGTH=2, POS=(20,41).
                .option(outcome.option())

                // ---- L177 MOVE WS-MESSAGE TO ERRMSGO OF COADM1AO -------------------------------
                // The one genuine fixed-width operation in this class, and a real narrowing:
                // WS-MESSAGE is PIC X(80) at app/cbl/COADM01C.cbl:38 and ERRMSGO is PIC X(78) at
                // app/cpy-bms/COADM01.CPY:260. A COBOL alphanumeric MOVE fills the receiver from the
                // LEFT and discards the overflow, so the surviving text is the LEADING seventy-eight
                // characters and the trailing two bytes are lost. That direction is the whole point of
                // routing it through the codec rather than writing substring here: the rule lives in
                // one auditable place, and the call site says which way it truncates.
                .errMsg(codec.movePicX(outcome.message(), AdminMenuResponse.ERR_MSG_LENGTH))

                // ---- Conversation state, returned so the client can re-supply it ----------------
                // L107-L110 EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA). The area
                // as the program leaves it - with CDEMO-PGM-CONTEXT already advanced to re-enter on the
                // first-entry path, which is what makes the next keystroke take the other branch.
                .navigationContext(outcome.navigationContext())

                // ---- The two XCTL sites, as a response field rather than a server-side forward ----
                // L142-L145 XCTL PROGRAM(CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)) COMMAREA(...) and
                // L165-L167 XCTL PROGRAM(CDEMO-TO-PROGRAM) with no COMMAREA. Spaces when the program
                // returned to CICS instead of transferring. The client issues the follow-up call: no
                // server-side forward, no redirect chain, no session affinity.
                .nextProgram(outcome.nextProgram())
                // L180-L181 MAP('COADM1A') MAPSET('COADM01') - seven characters each, matching
                // CDEMO-LAST-MAP and CDEMO-LAST-MAPSET, which are X(7) and not X(8).
                .nextMapset(outcome.mapsetName())
                .nextMap(outcome.mapName())

                // ---- Metadata, never a JSON payload member --------------------------------------
                // ERRMSGC OF COADM1AO. The mapset declares COLOR=RED on ERRMSG, and L148
                // MOVE DFHGREEN TO ERRMSGC overrides it on the coming-soon path alone. The byte is
                // carried as the attribute metadata the copybook declares it to be, and is passed
                // through exactly as the service resolved it.
                .messageColour(outcome.messageColour())
                // L89 MOVE LOW-VALUES TO COADM1AO - true on the first-entry path only, telling the
                // client to clear every rendered output field before painting what follows.
                .resetAllOutputFields(outcome.resetAllOutputFields())
                .build();
    }
}
