package com.vsergeychik.carddemo.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The inbound REST payload of {@code GET /api/menu} - the main menu screen of the CardDemo online
 * application. CICS transaction {@value #TRANSACTION_ID}, program {@value #PROGRAM_NAME}, mapset
 * {@value #MAPSET_NAME}, map {@value #MAP_NAME}.
 *
 * <p>This type is a <strong>1:1 projection of the {@code xxxI} items of {@code 01 COMEN1AI}</strong>
 * in {@code app/cpy-bms/COMEN01.CPY}, widened only by the conversation state that CICS used to keep
 * for the terminal. It is pure data: no business logic, no I/O, and no Spring annotation beyond Bean
 * Validation.
 *
 * <h2>Authoritative sources</h2>
 *
 * <table border="1">
 *   <caption>Every fact in this class traces to one of these read-only files</caption>
 *   <tr><th>File</th><th>What it supplies</th></tr>
 *   <tr><td>{@code app/cpy-bms/COMEN01.CPY}</td>
 *       <td>The symbolic map. Line 17 opens {@code 01 COMEN1AI.}; the twenty {@code xxxI} items and
 *           their {@code PICTURE} clauses are the authoritative payload names and widths</td></tr>
 *   <tr><td>{@code app/bms/COMEN01.bms}</td>
 *       <td>The mapset: {@code COMEN01 DFHMSD} at line 19, {@code COMEN1A DFHMDI SIZE=(24,80)} at
 *           line 26, and the {@code DFHMDF} field definitions with their attributes</td></tr>
 *   <tr><td>{@code app/cbl/COMEN01C.cbl}</td>
 *       <td>The program: the two screen literals at lines 36-37, the conversation branches at lines
 *           82, 87 and 93-103, and the option handling at lines 117-143</td></tr>
 *   <tr><td>{@code app/cpy/COCOM01Y.cpy}</td>
 *       <td>{@code 01 CARDDEMO-COMMAREA}, the 160-byte communication area modelled by
 *           {@link NavigationContext}</td></tr>
 *   <tr><td>{@code app/csd/CARDDEMO.CSD}</td>
 *       <td>Lines 399-400 bind {@code TRANSACTION(CM00)} to {@code PROGRAM(COMEN01C)}; line 133
 *           defines {@code MAPSET(COMEN01)}</td></tr>
 * </table>
 *
 * <h2>The projection rule, and why the metadata items are absent</h2>
 *
 * {@code COMEN01.CPY} line 17 opens the input view with a {@value #TIOAPFX_FILLER_LENGTH}-byte
 * {@code TIOAPFX} {@code FILLER} and then repeats a five-item pattern for every screen field:
 *
 * <pre>
 *  02  xxxL    COMP  PIC  S9(4).      &lt;- length item      2 bytes
 *  02  xxxF    PICTURE X.             &lt;- flag byte        1 byte
 *  02  FILLER REDEFINES xxxF.
 *    03 xxxA    PICTURE X.            &lt;- attribute view   (redefines, adds nothing)
 *  02  FILLER   PICTURE X(4).         &lt;- reserved         4 bytes
 *  02  xxxI  PIC X(n).                &lt;- THE PAYLOAD ITEM n bytes
 * </pre>
 *
 * The per-field overhead is therefore {@value #FIELD_METADATA_LENGTH} bytes, and line 139 opens
 * {@code 01 COMEN1AO REDEFINES COMEN1AI.} with the identical stride - {@code FILLER X(3)},
 * {@code xxxC}, {@code xxxP}, {@code xxxH}, {@code xxxV}, {@code xxxO PIC X(n)} - which is precisely
 * why the output view can {@code REDEFINES} the input view.
 *
 * <p>Only the {@code xxxI} items become members here. The {@code xxxL}, {@code xxxF} and
 * {@code xxxA} items are <strong>validation and highlight metadata, never payload</strong>:
 * {@code xxxL} is the input length CICS reports, which in a REST projection is simply the length of
 * the supplied string and is enforced by the {@link Size} constraints below rather than transmitted;
 * {@code xxxA} is the attribute view a program writes to when it highlights a field in error, which
 * belongs to the response, not the request. They are omitted outright, which is the strongest
 * possible guarantee that they cannot leak onto the wire. The {@code xxxO}, {@code xxxC},
 * {@code xxxP}, {@code xxxH} and {@code xxxV} items of {@code COMEN1AO} belong to the paired
 * response type.
 *
 * <h2>The geometry, enforced by construction</h2>
 *
 * {@code app/bms/COMEN01.bms} declares {@value #SCREEN_FIELD_COUNT} {@code DFHMDF} fields of which
 * exactly {@value #MAP_FIELD_COUNT} carry a name label; the other eight are unnamed literal screen
 * furniture - {@code 'Tran:'} at {@code POS=(1,1)}, {@code 'Date:'} at {@code (1,65)},
 * {@code 'Prog:'} at {@code (2,1)}, {@code 'Time:'} at {@code (2,65)}, the
 * {@code LENGTH=9 INITIAL='Main Menu'} heading at {@code (4,35)},
 * {@code 'Please select an option :'} at {@code (20,15)}, a {@code LENGTH=0} stopper at
 * {@code (20,44)}, and {@code 'ENTER=Continue  F3=Exit'} at {@code (24,1)}. Literals are painted by
 * the map, are never received from the terminal, and so are not payload.
 *
 * <p>The {@value #MAP_FIELD_COUNT} payload widths sum to
 * {@value #SYMBOLIC_MAP_PAYLOAD_LENGTH} bytes and the whole symbolic map measures
 * {@value #TIOAPFX_FILLER_LENGTH} + {@value #MAP_FIELD_COUNT} x {@value #FIELD_METADATA_LENGTH} +
 * {@value #SYMBOLIC_MAP_PAYLOAD_LENGTH} = {@value #SYMBOLIC_MAP_LENGTH} bytes. That arithmetic is
 * not merely recorded in prose: {@link #SYMBOLIC_MAP_PAYLOAD_LENGTH} and
 * {@link #SYMBOLIC_MAP_LENGTH} are declared as expressions over the individual width constants, so
 * a mistyped width changes the totals rather than leaving them agreeing with a comment that has
 * quietly become false.
 *
 * <p>Declaring the geometry is deliberately <em>not</em> the same as producing an image. This class
 * renders no fixed-width bytes at all - see the prohibitions below.
 *
 * <h2>Why an apparently duplicate type exists beside the admin menu request</h2>
 *
 * {@code app/cpy-bms/COMEN01.CPY} and {@code app/cpy-bms/COADM01.CPY} are <strong>byte-identical
 * apart from two group names</strong>: both files are exactly 10506 bytes and {@code diff} reports
 * differences only at line 17 ({@code 01 COMEN1AI.} against {@code 01 COADM1AI.}) and line 139
 * ({@code 01 COMEN1AO REDEFINES COMEN1AI.} against the admin equivalent). The two mapsets likewise
 * differ only in the mapset name, the map name and one unnamed heading literal
 * ({@code LENGTH=9 INITIAL='Main Menu'} against {@code LENGTH=10 INITIAL='Admin Menu'} at
 * {@code POS=(4,35)}).
 *
 * <p>The duplication in the legacy source is deliberate, and it is preserved here deliberately. This
 * type does not extend, implement, wrap or otherwise share code with the admin menu request, and
 * there is no shared base class, interface, generic parameterisation or helper standing between
 * them. Two independently named CICS transactions drive two independently named mapsets, and
 * collapsing them would create a coupling the legacy system does not have: the moment either screen
 * gained a field the shared abstraction would have to be unpicked, and until then a reader could no
 * longer tell which map a given field came from. The migration documents such conflicts rather than
 * resolving them.
 *
 * <p>The one behavioural difference between the two screens that touches the <em>request</em> is the
 * user-type filter at {@code app/cbl/COMEN01C.cbl:136-143}, described next.
 *
 * <h2>Statelessness: the payload carries the conversation</h2>
 *
 * CICS is pseudo-conversational. {@code COMEN01C} paints the menu, ends, and is re-entered from the
 * top when the user presses a key; the only state that survives is what the program handed back in
 * its communication area. This type preserves that shape exactly - the communication area, the
 * {@code EIBAID} key indication and the enter-versus-re-enter context all travel in the payload.
 *
 * <p>There is consequently no {@code HttpSession}, no {@code @SessionAttributes}, no
 * {@code @Scope("session")}, no {@code ThreadLocal}, no static cache and no server-side conversation
 * store anywhere in this class. The client holds the value between calls, exactly as a CICS terminal
 * held the COMMAREA.
 *
 * <p>Three conversation branches of {@code COMEN01C} are encoded, in the order the program tests
 * them:
 *
 * <ol>
 *   <li>{@code app/cbl/COMEN01C.cbl:82} - {@code IF EIBCALEN = 0}, no communication area was passed,
 *       so the transaction transfers to the sign-on screen. Encoded by a {@code null}
 *       {@link #navigationContext()}; see {@link #commareaAbsent()}.</li>
 *   <li>{@code app/cbl/COMEN01C.cbl:87} - {@code IF NOT CDEMO-PGM-REENTER}, first entry paints the
 *       screen, re-entry processes what was typed. Exposed by {@link #enter()} and
 *       {@link #reenter()}, which read through to {@link NavigationContext#isEnter()} and
 *       {@link NavigationContext#isReenter()} rather than duplicating
 *       {@code CDEMO-PGM-CONTEXT} in a second field that could disagree with it.</li>
 *   <li>{@code app/cbl/COMEN01C.cbl:93-103} - {@code EVALUATE EIBAID} over {@code DFHENTER},
 *       {@code DFHPF3} and {@code WHEN OTHER}. Carried raw by {@link #eibAid()}.</li>
 * </ol>
 *
 * <h2>{@code CDEMO-USER-TYPE} is received, never derived</h2>
 *
 * The carried user type is functionally load-bearing on this screen.
 * {@code app/cbl/COMEN01C.cbl:136-143} tests
 * {@code IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'} and answers
 * {@code 'No access - Admin Only option... '}. {@code COMEN01C} only ever <em>reads</em> that field:
 * its sole assignments, at lines 149-150, are commented out in the source.
 *
 * <p>This type therefore carries the value inbound verbatim inside {@link #navigationContext()} and
 * never derives, defaults, looks up or overwrites it. It exposes no user-type accessor, no role
 * member and no authorisation annotation of its own: the filter itself is a decision taken in the
 * main menu service, where it is reachable by a plain unit test, and authentication elsewhere in
 * this application remains the file-based plaintext comparison the legacy programs perform. Nothing
 * about the security posture is strengthened or weakened here.
 *
 * <h2>What this type deliberately does not do</h2>
 *
 * <ul>
 *   <li><strong>It does not truncate.</strong> {@code app/cbl/COMEN01C.cbl:187} moves the
 *       {@code PIC X(80)} {@code WS-MESSAGE} into the {@code PIC X(78)} {@code ERRMSGO}, losing the
 *       final two bytes on the right. That narrowing belongs to the controller and its fixed-width
 *       codec, which is where the direction of every {@code MOVE} truncation is chosen explicitly.
 *       {@link #errMsg()} is simply declared at its map width of {@value #ERR_MSG_LENGTH}.</li>
 *   <li><strong>It renders no {@value #SYMBOLIC_MAP_LENGTH}-byte image.</strong> The request path
 *       never needs one, no codec is imported, and no {@code FILLER} span is emitted, because
 *       nothing here is serialised to bytes.</li>
 *   <li><strong>It holds no business logic.</strong> No option validation, no range check against
 *       {@code CDEMO-MENU-OPT-COUNT}, no user-type filter, no menu construction, no {@code 'DUMMY'}
 *       program-name test and no coming-soon message composition. All of that lives in the service,
 *       which is what keeps the package's branch surface reachable without an HTTP layer in the
 *       path.</li>
 *   <li><strong>It does not touch the menu option table.</strong> {@code app/cpy/COMEN02Y.cpy} and
 *       its {@code X(01)} authorisation column are the service's concern.</li>
 *   <li><strong>It uses no code generator.</strong> Accessors come from the record, not from an
 *       annotation processor, so the correspondence between this file and the copybook is visible
 *       line by line.</li>
 * </ul>
 *
 * <h2>Null members, and why nothing is rejected that CICS would have accepted</h2>
 *
 * Every character member is nullable and is stored exactly as supplied. That is faithful rather than
 * lax: {@code COMEN01C} reads only {@code OPTIONI} from the received map and answers a blank or
 * non-numeric option with {@code 'Please enter a valid option number...'}
 * ({@code app/cbl/COMEN01C.cbl:127-134}) instead of refusing the request, so a Bean Validation
 * rejection would change observable behaviour. There is accordingly no {@code @NotNull} and no
 * {@code @NotBlank} anywhere in this class, and no constructor guard that throws.
 *
 * <p>{@link Size} is the single enforcement point, and it constrains only the maximum: a
 * {@code null} or shorter value is valid, and a longer one is rejected because the map field is
 * physically that wide and a wider value could never have reached {@code COMEN01C} at all. A shorter
 * value is padded on the right with spaces when the controller renders the field, exactly as a COBOL
 * {@code MOVE} into a wider {@code PIC X} receiver pads - and that padding happens there, not here.
 *
 * <p>{@link #option()} in particular is passed through <strong>raw</strong>. Lines 117-124 of the
 * program scan backwards from {@code LENGTH OF OPTIONI} for the last non-space byte, move the prefix
 * into {@code WS-OPTION-X PIC X(02) JUST RIGHT} and then
 * {@code INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'}. That logic consumes the exact space pattern
 * the terminal sent, so this type must not trim, pad, normalise, re-justify or parse it. Nor is it
 * numeric here: there is no decimal {@code PICTURE} anywhere on this screen, and no member of this
 * class is a {@code double}, a {@code float} or a {@code BigDecimal}.
 *
 * <h2>Governing practices</h2>
 *
 * No user-specified rules were provided for this project - the rules document consists of the single
 * line "No user rules provided" - so no user rule governs this file. Their absence is not treated as
 * licence to lower the standard: the enterprise best-practice substitutes of the migration plan bind
 * instead, and the ones that bite here are exact and verified dependency versions
 * ({@code jakarta.validation} 3.0.2 by way of the validation starter, never the superseded
 * {@code javax.validation}), immutable reference inputs, documenting conflicts rather than fixing
 * them, an untouched security posture, explicit over implicit (no wildcard import, every width a
 * named constant), no static mutable state, and hand-written reviewable code in place of generated
 * or third-party parsing.
 *
 * @param trnName    {@code TRNNAMEI PIC X(4)}, {@code COMEN01.CPY:24}: the transaction identifier
 *                   shown in the header
 * @param title01    {@code TITLE01I PIC X(40)}, {@code COMEN01.CPY:30}: the first title line
 * @param curDate    {@code CURDATEI PIC X(8)}, {@code COMEN01.CPY:36}: the header date
 * @param pgmName    {@code PGMNAMEI PIC X(8)}, {@code COMEN01.CPY:42}: the program name shown in the
 *                   header
 * @param title02    {@code TITLE02I PIC X(40)}, {@code COMEN01.CPY:48}: the second title line
 * @param curTime    {@code CURTIMEI PIC X(8)}, {@code COMEN01.CPY:54}: the header time
 * @param optn001    {@code OPTN001I PIC X(40)}, {@code COMEN01.CPY:60}: menu option line 1
 * @param optn002    {@code OPTN002I PIC X(40)}, {@code COMEN01.CPY:66}: menu option line 2
 * @param optn003    {@code OPTN003I PIC X(40)}, {@code COMEN01.CPY:72}: menu option line 3
 * @param optn004    {@code OPTN004I PIC X(40)}, {@code COMEN01.CPY:78}: menu option line 4
 * @param optn005    {@code OPTN005I PIC X(40)}, {@code COMEN01.CPY:84}: menu option line 5
 * @param optn006    {@code OPTN006I PIC X(40)}, {@code COMEN01.CPY:90}: menu option line 6
 * @param optn007    {@code OPTN007I PIC X(40)}, {@code COMEN01.CPY:96}: menu option line 7
 * @param optn008    {@code OPTN008I PIC X(40)}, {@code COMEN01.CPY:102}: menu option line 8
 * @param optn009    {@code OPTN009I PIC X(40)}, {@code COMEN01.CPY:108}: menu option line 9
 * @param optn010    {@code OPTN010I PIC X(40)}, {@code COMEN01.CPY:114}: menu option line 10
 * @param optn011    {@code OPTN011I PIC X(40)}, {@code COMEN01.CPY:120}: menu option line 11,
 *                   present on the map and never populated by {@code COMEN01C}
 * @param optn012    {@code OPTN012I PIC X(40)}, {@code COMEN01.CPY:126}: menu option line 12,
 *                   present on the map and never populated by {@code COMEN01C}
 * @param option     {@code OPTIONI PIC X(2)}, {@code COMEN01.CPY:132}: the selected option, the only
 *                   unprotected field on the screen, carried raw
 * @param errMsg     {@code ERRMSGI PIC X(78)}, {@code COMEN01.CPY:138}: the error message line
 * @param navigationContext the inbound {@code CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy},
 *                   or {@code null} when none was supplied, which is
 *                   {@code app/cbl/COMEN01C.cbl:82}'s {@code IF EIBCALEN = 0}
 * @param eibAid     the raw {@code EIBAID} byte that {@code app/cbl/COMEN01C.cbl:93-103} evaluates;
 *                   resolving it to a key token is the shared program-function key resolver's job in
 *                   the service, not this type's
 */
public record MainMenuRequest(
        @Size(max = MainMenuRequest.TRN_NAME_LENGTH) String trnName,
        @Size(max = MainMenuRequest.TITLE_LENGTH) String title01,
        @Size(max = MainMenuRequest.CUR_DATE_LENGTH) String curDate,
        @Size(max = MainMenuRequest.PGM_NAME_LENGTH) String pgmName,
        @Size(max = MainMenuRequest.TITLE_LENGTH) String title02,
        @Size(max = MainMenuRequest.CUR_TIME_LENGTH) String curTime,
        @Size(max = MainMenuRequest.OPTION_LINE_LENGTH) String optn001,
        @Size(max = MainMenuRequest.OPTION_LINE_LENGTH) String optn002,
        @Size(max = MainMenuRequest.OPTION_LINE_LENGTH) String optn003,
        @Size(max = MainMenuRequest.OPTION_LINE_LENGTH) String optn004,
        @Size(max = MainMenuRequest.OPTION_LINE_LENGTH) String optn005,
        @Size(max = MainMenuRequest.OPTION_LINE_LENGTH) String optn006,
        @Size(max = MainMenuRequest.OPTION_LINE_LENGTH) String optn007,
        @Size(max = MainMenuRequest.OPTION_LINE_LENGTH) String optn008,
        @Size(max = MainMenuRequest.OPTION_LINE_LENGTH) String optn009,
        @Size(max = MainMenuRequest.OPTION_LINE_LENGTH) String optn010,
        @Size(max = MainMenuRequest.OPTION_LINE_LENGTH) String optn011,
        @Size(max = MainMenuRequest.OPTION_LINE_LENGTH) String optn012,
        @Size(max = MainMenuRequest.OPTION_LENGTH) String option,
        @Size(max = MainMenuRequest.ERR_MSG_LENGTH) String errMsg,
        NavigationContext navigationContext,
        byte eibAid) {

    // =================================================================================================
    // Screen identity. Every literal below is copied byte for byte from the source that declares it,
    // so a reader can confirm the binding without leaving this file.
    // =================================================================================================

    /**
     * The CICS transaction identifier, {@code 'CM00'}.
     *
     * <p>Declared twice in the sources that matter, and identically:
     * {@code app/cbl/COMEN01C.cbl:37} holds
     * {@code 05 WS-TRANID PIC X(04) VALUE 'CM00'}, and {@code app/csd/CARDDEMO.CSD:399-400} defines
     * {@code TRANSACTION(CM00)} with {@code PROGRAM(COMEN01C)}.
     *
     * <p>Exactly {@value #TRN_NAME_LENGTH} characters, which is why it fits the
     * {@code TRNNAMEI PIC X(4)} field without any adjustment.
     */
    public static final String TRANSACTION_ID = "CM00";

    /**
     * The COBOL program name, {@code 'COMEN01C'}, from
     * {@code app/cbl/COMEN01C.cbl:36} - {@code 05 WS-PGMNAME PIC X(08) VALUE 'COMEN01C'} - and
     * corroborated by {@code app/csd/CARDDEMO.CSD:235}, {@code DEFINE PROGRAM(COMEN01C)}.
     *
     * <p>Exactly {@value #PGM_NAME_LENGTH} characters, matching {@code PGMNAMEI PIC X(8)}.
     */
    public static final String PROGRAM_NAME = "COMEN01C";

    /**
     * The BMS mapset name, {@code 'COMEN01'}, from {@code app/bms/COMEN01.bms:19}
     * ({@code COMEN01 DFHMSD}) and {@code app/csd/CARDDEMO.CSD:133}
     * ({@code DEFINE MAPSET(COMEN01)}). {@code app/cbl/COMEN01C.cbl:191} names it on the
     * {@code EXEC CICS SEND} as {@code MAPSET('COMEN01')}.
     *
     * <p>Seven characters, not eight - which is exactly why
     * {@link NavigationContext#LAST_MAPSET_LENGTH} is {@value NavigationContext#LAST_MAPSET_LENGTH}.
     */
    public static final String MAPSET_NAME = "COMEN01";

    /**
     * The BMS map name, {@code 'COMEN1A'}, from {@code app/bms/COMEN01.bms:26}
     * ({@code COMEN1A DFHMDI COLUMN=1, LINE=1, SIZE=(24,80)}) and named on the
     * {@code EXEC CICS SEND} at {@code app/cbl/COMEN01C.cbl:190} as {@code MAP('COMEN1A')}.
     *
     * <p>Seven characters, matching {@link NavigationContext#LAST_MAP_LENGTH}. The eighth character
     * of a symbolic-map group name is the direction suffix: {@code COMEN1AI} for the input view
     * projected here and {@code COMEN1AO} for the output view projected by the paired response.
     */
    public static final String MAP_NAME = "COMEN1A";

    // =================================================================================================
    // Payload widths. One named constant per distinct xxxI PICTURE, each citing the copybook line it
    // was read from. No @Size below uses a bare integer literal, so a width can only be changed in one
    // place and that place names its own source.
    // =================================================================================================

    /** {@code TRNNAMEI PIC X(4)}, {@code app/cpy-bms/COMEN01.CPY:24}. */
    public static final int TRN_NAME_LENGTH = 4;

    /**
     * {@code TITLE01I PIC X(40)}, {@code app/cpy-bms/COMEN01.CPY:30}, and
     * {@code TITLE02I PIC X(40)}, {@code app/cpy-bms/COMEN01.CPY:48}.
     *
     * <p>One constant serves both title lines because the copybook declares both at the same width
     * and {@code app/bms/COMEN01.bms:38-41} and {@code :61-64} declare both as
     * {@code LENGTH=40 COLOR=YELLOW}. The values themselves are the two screen-title literals of
     * {@code app/cpy/COTTL01Y.cpy}, which the service supplies; this type only carries them.
     */
    public static final int TITLE_LENGTH = 40;

    /**
     * {@code CURDATEI PIC X(8)}, {@code app/cpy-bms/COMEN01.CPY:36}.
     *
     * <p>{@code app/bms/COMEN01.bms:47-51} declares the field at {@code POS=(1,71)} with
     * {@code INITIAL='mm/dd/yy'}, so the eight characters are a formatted {@code MM/DD/YY} rendering
     * rather than a date value. Formatting belongs to the header helper in the service.
     */
    public static final int CUR_DATE_LENGTH = 8;

    /** {@code PGMNAMEI PIC X(8)}, {@code app/cpy-bms/COMEN01.CPY:42}. */
    public static final int PGM_NAME_LENGTH = 8;

    /**
     * {@code CURTIMEI PIC X(8)}, {@code app/cpy-bms/COMEN01.CPY:54}.
     *
     * <p>{@code app/bms/COMEN01.bms:70-74} declares it at {@code POS=(2,71)} with
     * {@code INITIAL='hh:mm:ss'} - a formatted {@code HH:MM:SS} rendering, not a time value.
     */
    public static final int CUR_TIME_LENGTH = 8;

    /**
     * {@code OPTN001I} through {@code OPTN012I}, each {@code PIC X(40)}, at
     * {@code app/cpy-bms/COMEN01.CPY} lines 60, 66, 72, 78, 84, 90, 96, 102, 108, 114, 120 and 126.
     * All twelve are declared at the identical width, so one constant covers them all.
     */
    public static final int OPTION_LINE_LENGTH = 40;

    /**
     * How many menu option lines the map declares: {@value #OPTION_LINE_COUNT}.
     *
     * <p>All twelve are members of this type even though {@code app/cbl/COMEN01C.cbl} populates only
     * the first ten, from the ten-row table of {@code app/cpy/COMEN02Y.cpy}. The projection is of the
     * <em>map</em>, not of the program's writes, and dropping the two unused lines would silently
     * change the screen contract. Unused map capacity is preserved exactly as found, in the same way
     * that dead paragraphs elsewhere in this migration remain no-ops rather than being tidied away.
     */
    public static final int OPTION_LINE_COUNT = 12;

    /**
     * {@code OPTIONI PIC X(2)}, {@code app/cpy-bms/COMEN01.CPY:132}.
     *
     * <p>{@code app/bms/COMEN01.bms:145-149} declares the field as
     * {@code ATTRB=(FSET,IC,NORM,NUM,UNPROT) HILIGHT=UNDERLINE JUSTIFY=(RIGHT,ZERO) LENGTH=2
     * POS=(20,41)} - the <strong>only</strong> unprotected field on the whole screen, and therefore
     * the only one the terminal operator can actually change. Its {@code NUM} plus
     * right-justify-zero-fill attributes are the terminal-side mirror of the program's
     * {@code JUST RIGHT} plus {@code INSPECT} normalisation at
     * {@code app/cbl/COMEN01C.cbl:117-124}, which is exactly why the value is carried raw here: that
     * normalisation consumes the space pattern the terminal sent, so pre-empting it would destroy the
     * input it works on.
     */
    public static final int OPTION_LENGTH = 2;

    /**
     * {@code ERRMSGI PIC X(78)}, {@code app/cpy-bms/COMEN01.CPY:138}, and
     * {@code app/bms/COMEN01.bms:154-157} declares {@code LENGTH=78 COLOR=RED POS=(23,1)}.
     *
     * <p>Seventy-eight, never eighty. {@code app/cbl/COMEN01C.cbl:38} declares the program's message
     * buffer as {@code WS-MESSAGE PIC X(80)} and line 187 moves it into the 78-byte field, dropping
     * the last two bytes on the right. This constant is the map width; the narrowing is the
     * controller's.
     */
    public static final int ERR_MSG_LENGTH = 78;

    // =================================================================================================
    // Symbolic-map geometry. Declared as expressions over the widths above so the totals cannot drift
    // away from the parts, and documented here purely as the copybook's measurements: this class
    // produces no fixed-width image, and nothing below is used to lay bytes out.
    // =================================================================================================

    /**
     * Every {@code DFHMDF} field definition in {@code app/bms/COMEN01.bms}:
     * {@value #SCREEN_FIELD_COUNT}.
     */
    public static final int SCREEN_FIELD_COUNT = 28;

    /**
     * The name-labelled subset of those field definitions, and therefore the number of members this
     * type projects: {@value #MAP_FIELD_COUNT}.
     *
     * <p>{@value #SCREEN_FIELD_COUNT} minus {@value #MAP_FIELD_COUNT} leaves the eight unnamed
     * literals listed in the class documentation. A field with no name has no symbolic-map item and
     * cannot be received, so it cannot be payload.
     */
    public static final int MAP_FIELD_COUNT = 20;

    /**
     * The {@code TIOAPFX} prefix at {@code app/cpy-bms/COMEN01.CPY:18} -
     * {@code 02 FILLER PIC X(12)} - present because the mapset is generated with
     * {@code TIOAPFX=YES} ({@code app/bms/COMEN01.bms:24}).
     */
    public static final int TIOAPFX_FILLER_LENGTH = 12;

    /**
     * The per-field metadata overhead that precedes every {@code xxxI} item:
     * {@value #FIELD_METADATA_LENGTH} bytes.
     *
     * <p>Two for {@code xxxL COMP PIC S9(4)}, one for {@code xxxF PICTURE X}, and four for the
     * reserved {@code FILLER PICTURE X(4)}. The {@code xxxA} item adds nothing, because it
     * {@code REDEFINES} {@code xxxF} rather than following it. None of these seven bytes is payload.
     */
    public static final int FIELD_METADATA_LENGTH = 2 + 1 + 4;

    /**
     * The sum of the {@value #MAP_FIELD_COUNT} payload widths:
     * {@value #SYMBOLIC_MAP_PAYLOAD_LENGTH} bytes.
     *
     * <p>4 + 40 + 8 + 8 + 40 + 8 + (12 x 40) + 2 + 78. Written as an expression over the width
     * constants rather than as the literal, so that mistyping a width moves this total instead of
     * leaving it quietly disagreeing with the copybook.
     */
    public static final int SYMBOLIC_MAP_PAYLOAD_LENGTH = TRN_NAME_LENGTH
            + TITLE_LENGTH
            + CUR_DATE_LENGTH
            + PGM_NAME_LENGTH
            + TITLE_LENGTH
            + CUR_TIME_LENGTH
            + (OPTION_LINE_COUNT * OPTION_LINE_LENGTH)
            + OPTION_LENGTH
            + ERR_MSG_LENGTH;

    /**
     * The width of the whole {@code 01 COMEN1AI} group item:
     * {@value #SYMBOLIC_MAP_LENGTH} bytes.
     *
     * <p>{@value #TIOAPFX_FILLER_LENGTH} + {@value #MAP_FIELD_COUNT} x
     * {@value #FIELD_METADATA_LENGTH} + {@value #SYMBOLIC_MAP_PAYLOAD_LENGTH}. The output view
     * {@code 01 COMEN1AO} at {@code app/cpy-bms/COMEN01.CPY:139} measures the same, which is what
     * makes its {@code REDEFINES} legal.
     *
     * <p>Recorded for reviewers checking this projection against the copybook. It is <em>not</em> a
     * buffer size: this type never renders those bytes.
     */
    public static final int SYMBOLIC_MAP_LENGTH = TIOAPFX_FILLER_LENGTH
            + (MAP_FIELD_COUNT * FIELD_METADATA_LENGTH)
            + SYMBOLIC_MAP_PAYLOAD_LENGTH;

    // =================================================================================================
    // Views over the state already held. Every method below reads the components and computes nothing
    // that is not a direct restatement of them, so none is stored and each is excluded from the JSON
    // payload: the wire format carries the twenty map fields, the communication area and the AID byte,
    // and nothing else. There is no static mutable field, no shared collection and no cache anywhere in
    // this class - a per-request payload that remembered anything between requests would be a session.
    // =================================================================================================

    /**
     * The twelve menu option lines as an unmodifiable list, in map order - index 0 is
     * {@link #optn001()} and index {@value #OPTION_LINE_COUNT} minus one is {@link #optn012()}.
     *
     * <p>Always exactly {@value #OPTION_LINE_COUNT} elements, because the map always declares twelve
     * whether or not the program fills them. Elements may be {@code null}, for the reason given in the
     * class documentation: a payload that omits a field leaves it unset rather than inventing a value
     * for it. That is also why the list is assembled with {@link Arrays#asList(Object...)}, which
     * admits {@code null} elements, rather than {@code List.of}, which would reject them.
     *
     * <p>A fresh wrapper is returned on each call and it is unmodifiable, so a caller can neither
     * mutate this instance through it nor observe another caller's changes: every mutator throws
     * {@link UnsupportedOperationException}. Iterating it is the natural way for the service to walk
     * the option lines without repeating twelve accessor calls.
     *
     * @return the twelve option lines in map order, never {@code null} and never resizable
     */
    @JsonIgnore
    public List<String> optionLines() {
        return Collections.unmodifiableList(Arrays.asList(optn001,
                optn002,
                optn003,
                optn004,
                optn005,
                optn006,
                optn007,
                optn008,
                optn009,
                optn010,
                optn011,
                optn012));
    }

    /**
     * One menu option line addressed by its <strong>one-based</strong> map position, so that
     * {@code optionLine(1)} is {@link #optn001()} and {@code optionLine(12)} is {@link #optn012()}.
     *
     * <p>The one-based index is deliberate and is the whole point of this method. COBOL subscripts
     * start at 1 - {@code app/cbl/COMEN01C.cbl:137} and {@code :146} address the option table as
     * {@code CDEMO-MENU-OPT-USRTYPE(WS-OPTION)} and
     * {@code CDEMO-MENU-OPT-PGMNAME(WS-OPTION)} where {@code WS-OPTION} is the number the operator
     * typed - while Java indexes from 0. Offering the COBOL convention explicitly means a translated
     * paragraph can pass the operator's option number straight through, instead of each caller
     * writing its own subtraction and one of them eventually getting it wrong.
     *
     * @param mapPosition the one-based option line position, from 1 to
     *                    {@value #OPTION_LINE_COUNT} inclusive
     * @return that option line, which may be {@code null} if the payload did not supply it
     * @throws IndexOutOfBoundsException if {@code mapPosition} is below 1 or above
     *                                   {@value #OPTION_LINE_COUNT}. The map declares exactly twelve
     *                                   lines, so any other position names a field that does not
     *                                   exist and is a programming error rather than bad input
     */
    @JsonIgnore
    public String optionLine(int mapPosition) {
        if (mapPosition < 1 || mapPosition > OPTION_LINE_COUNT) {
            throw new IndexOutOfBoundsException("Menu option line " + mapPosition
                    + " is outside the 1.." + OPTION_LINE_COUNT + " lines declared by map "
                    + MAP_NAME + " of mapset " + MAPSET_NAME);
        }
        return optionLines().get(mapPosition - 1);
    }

    /**
     * Whether a communication area was supplied with this request.
     *
     * <p>This is the negation of {@code app/cbl/COMEN01C.cbl:82}'s {@code IF EIBCALEN = 0}. That test
     * is the very first thing {@code COMEN01C} does, and when it holds the transaction abandons the
     * menu entirely and transfers to the sign-on program, so this predicate has to be consulted
     * before {@link #enter()} or {@link #reenter()} means anything - exactly as the COBOL nests the
     * re-entry test inside the {@code ELSE} of the length test.
     *
     * @return {@code true} when {@link #navigationContext()} is present
     */
    @JsonIgnore
    public boolean commareaPresent() {
        return navigationContext != null;
    }

    /**
     * Whether no communication area was supplied - {@code app/cbl/COMEN01C.cbl:82}'s
     * {@code IF EIBCALEN = 0} exactly, which is the route to the sign-on screen.
     *
     * <p>Offered alongside {@link #commareaPresent()} rather than left to the caller to negate,
     * because the COBOL states the condition in this polarity and a translated paragraph reads
     * closest to its source when it can do the same.
     *
     * @return {@code true} when {@link #navigationContext()} is absent
     */
    @JsonIgnore
    public boolean commareaAbsent() {
        return navigationContext == null;
    }

    /**
     * Whether this is a first entry to the transaction - {@code 88 CDEMO-PGM-ENTER VALUE 0} of
     * {@code app/cpy/COCOM01Y.cpy:30} holding on the carried communication area.
     *
     * <p>Read through to {@link NavigationContext#isEnter()} rather than duplicated into a second
     * field of this record: one stored copy of {@code CDEMO-PGM-CONTEXT} cannot disagree with itself,
     * whereas two could, and a request whose flag contradicted its own communication area would send
     * the service down a branch the COBOL never takes.
     *
     * <p>Returns {@code false} when no communication area was supplied, because a state that was
     * never transmitted is not being asserted. That mirrors {@link NavigationContext#empty()}, where
     * {@link NavigationContext#isAdmin()} and {@link NavigationContext#isUser()} are likewise both
     * false; and it is safe because {@code COMEN01C} decides the absent-area case at line 82, before
     * the line 87 test this predicate belongs to is ever reached.
     *
     * @return {@code true} only when a communication area is present and its program context is
     *         {@value NavigationContext#PGM_CONTEXT_ENTER}
     */
    @JsonIgnore
    public boolean enter() {
        return navigationContext != null && navigationContext.isEnter();
    }

    /**
     * Whether the terminal is returning to a screen this transaction has already painted -
     * {@code 88 CDEMO-PGM-REENTER VALUE 1} of {@code app/cpy/COCOM01Y.cpy:31} holding on the carried
     * communication area.
     *
     * <p>This is the condition {@code app/cbl/COMEN01C.cbl:87} tests as
     * {@code IF NOT CDEMO-PGM-REENTER}: when it does not hold the program paints a fresh screen, and
     * when it does the program processes what was typed. Like {@link #enter()} it reads through to the
     * communication area, and like {@link #enter()} it is {@code false} when no communication area
     * was supplied.
     *
     * <p>Note that {@link #enter()} and this method are <strong>not</strong> negations of each other,
     * for the same reason the pair on {@link NavigationContext} is not: with no communication area
     * both are false, and neither state is being claimed.
     *
     * @return {@code true} only when a communication area is present and its program context is
     *         {@value NavigationContext#PGM_CONTEXT_REENTER}
     */
    @JsonIgnore
    public boolean reenter() {
        return navigationContext != null && navigationContext.isReenter();
    }
}
