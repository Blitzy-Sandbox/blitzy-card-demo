package com.vsergeychik.carddemo.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The inbound REST payload of {@code GET /api/admin/menu} - the admin menu screen of CICS
 * transaction {@value #TRANSACTION_ID}, program {@value #PROGRAM_NAME}, mapset
 * {@value #MAPSET_NAME}, map {@value #MAP_NAME}.
 *
 * <p>This type is a <strong>1:1 projection of the {@code xxxI} items of {@code 01 COADM1AI}</strong>
 * in {@code app/cpy-bms/COADM01.CPY}. It is what {@code EXEC CICS RECEIVE MAP('COADM1A')
 * MAPSET('COADM01') INTO(COADM1AI)} - {@code app/cbl/COADM01C.cbl:191-197} - used to hand the
 * program, expressed as a request body. It is <strong>pure data</strong>: no business logic, no I/O,
 * no Spring stereotype, and no annotation beyond Bean Validation.
 *
 * <h2>The symbolic map, and why the projection takes only the {@code xxxI} items</h2>
 *
 * {@code app/cpy-bms/COADM01.CPY} line 17 opens {@code 01 COADM1AI.} with a twelve-byte
 * {@code TIOAPFX} {@code FILLER} at line 18, then repeats a five-declaration group per screen
 * field:
 *
 * <pre>
 *  02  xxxL    COMP  PIC  S9(4).      &lt;- length item      2 bytes, METADATA
 *  02  xxxF    PICTURE X.             &lt;- flag byte        1 byte,  METADATA
 *  02  FILLER REDEFINES xxxF.
 *    03 xxxA    PICTURE X.            &lt;- attribute view   (redefines the flag, adds no bytes)
 *  02  FILLER   PICTURE X(4).         &lt;- reserved         4 bytes
 *  02  xxxI  PIC X(n).                &lt;- THE PAYLOAD ITEM n bytes
 * </pre>
 *
 * The stride is therefore {@value #METADATA_BYTES_PER_FIELD} + n. Line 139 opens
 * {@code 01 COADM1AO REDEFINES COADM1AI.} and mirrors the identical stride with
 * {@code FILLER X(3)} / {@code xxxC} / {@code xxxP} / {@code xxxH} / {@code xxxV} /
 * {@code xxxO PIC X(n)} - 3 + 1 + 1 + 1 + 1 = {@value #METADATA_BYTES_PER_FIELD} - which is
 * precisely why the output view can {@code REDEFINES} the input view.
 *
 * <p>Three consequences follow, and all three are binding:
 *
 * <ul>
 *   <li><strong>Only the {@code xxxI} items become members here.</strong> The {@code xxxO} items are
 *       the output view and belong to {@code AdminMenuResponse}, not to this request.</li>
 *   <li><strong>{@code xxxL}, {@code xxxF} and {@code xxxA} are metadata and are deliberately
 *       absent.</strong> {@code xxxL} is the input length CICS reports, {@code xxxF} the flag byte
 *       and {@code xxxA} the attribute view used when highlighting a field in error. None of them is
 *       a screen value, so none of them may reach the wire. They are <em>omitted outright</em> rather
 *       than modelled and hidden: the reported length is derivable from the value that is carried, so
 *       storing it as well would create a second source of truth that could disagree with the first.
 *       Field highlighting is the response's concern, driven by
 *       {@code com.vsergeychik.carddemo.common.FieldAttributeSetter}.</li>
 *   <li><strong>Only name-labelled {@code DFHMDF} fields become members.</strong>
 *       {@code app/bms/COADM01.bms} declares <strong>28</strong> {@code DFHMDF} fields of which
 *       exactly <strong>{@value #MAPPED_FIELD_COUNT}</strong> carry a name. The other eight are
 *       unnamed literal screen furniture with no symbolic-map item and therefore no payload member:
 *       {@code 'Tran:'} at (1,1) line 29, {@code 'Date:'} at (1,65) line 42, {@code 'Prog:'} at
 *       (2,1) line 52, {@code 'Time:'} at (2,65) line 65, the {@code LENGTH=10 INITIAL='Admin Menu'}
 *       heading at (4,35) line 75, {@code 'Please select an option :'} at (20,15) line 140, a
 *       {@code LENGTH=0} stopper at (20,44) line 150, and {@code 'ENTER=Continue  F3=Exit'} at
 *       (24,1) line 158.</li>
 * </ul>
 *
 * <h2>The width arithmetic, which is gate-level</h2>
 *
 * The {@value #MAPPED_FIELD_COUNT} {@code xxxI} widths are 4, 40, 8, 8, 40, 8, twelve occurrences of
 * 40, 2 and 78. They sum to {@value #PAYLOAD_DATA_LENGTH}, and the whole symbolic-map image is
 * {@value #TIOAPFX_LENGTH} + {@value #MAPPED_FIELD_COUNT} x {@value #METADATA_BYTES_PER_FIELD} +
 * {@value #PAYLOAD_DATA_LENGTH} = <strong>{@value #SYMBOLIC_MAP_LENGTH}</strong> bytes.
 *
 * <p>{@link #PAYLOAD_DATA_LENGTH} and {@link #SYMBOLIC_MAP_LENGTH} are declared as <em>computed</em>
 * expressions over the individual width constants rather than as the literals 668 and 820. A
 * mistyped width therefore changes the totals rather than hiding behind them, and the unit test that
 * pins those two totals to their expected values fails naming the discrepancy.
 *
 * <h2>{@code COMEN01.CPY} is byte-identical, and {@code MainMenuRequest} is still a separate type</h2>
 *
 * {@code app/cpy-bms/COADM01.CPY} and {@code app/cpy-bms/COMEN01.CPY} are <strong>byte-identical
 * apart from their group names</strong>. {@code diff} reports exactly two differing lines: line 17
 * ({@code 01 COADM1AI.} against {@code 01 COMEN1AI.}) and line 139
 * ({@code 01 COADM1AO REDEFINES COADM1AI.} against {@code 01 COMEN1AO REDEFINES COMEN1AI.}). Every
 * other line, including all {@value #MAPPED_FIELD_COUNT} field groups and every width, matches.
 *
 * <p>That duplication is <strong>recorded here, not removed</strong>. {@code MainMenuRequest} is
 * deliberately a separate type, and this class is deliberately not refactored into a shared base
 * class, a shared interface, a generic parameterised by group name, or a common
 * {@code MenuDtoSupport} helper. Two reasons, either sufficient on its own:
 *
 * <ul>
 *   <li>The two screens are independent contracts that merely happen to coincide today. The admin
 *       menu offers four options from {@code app/cpy/COADM02Y.cpy} while the main menu offers ten
 *       from {@code app/cpy/COMEN02Y.cpy}, gated by an {@code X(01)} user-type authorisation column
 *       that the admin table does not have. Coupling the payloads would make a future divergence in
 *       one screen a breaking change in the other.</li>
 *   <li>This migration documents conflicts and coincidences instead of resolving them. Collapsing
 *       two independently declared copybooks into one Java type would be exactly the silent
 *       structural change the migration forbids, and it would erase the field-name provenance that
 *       field-for-field parity diffing depends on.</li>
 * </ul>
 *
 * <h2>Statelessness: the payload carries the conversation</h2>
 *
 * CICS is pseudo-conversational, so {@code COADM01C} keeps nothing between keystrokes; the whole of
 * its state arrives in the communication area and the attention identifier. This request reproduces
 * that shape exactly, carrying {@link #navigationContext()} and {@link #eibAid()} as ordinary
 * payload members.
 *
 * <p>There is consequently <strong>no server-side session of any kind</strong>: no
 * {@code HttpSession}, no {@code @SessionAttributes}, no session or request scope, no
 * {@code ThreadLocal}, no static cache and no static "current request" accessor. A static holder
 * would be a session by another name and would break request isolation as well. The type is an
 * immutable record, so a value handed to a collaborator cannot change underneath it.
 *
 * <h2>What this type deliberately does not do</h2>
 *
 * <ul>
 *   <li><strong>It does not normalise, trim or parse {@link #option()}.</strong> The value is carried
 *       exactly as received, spaces and all, because the leading and trailing space pattern is the
 *       input that {@code app/cbl/COADM01C.cbl:117-124} consumes: it scans backwards for the last
 *       non-space, moves the prefix into {@code WS-OPTION-X PIC X(02) JUST RIGHT}, then
 *       {@code INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'}. Pre-trimming here would destroy that
 *       input. There is accordingly no canonical-constructor body at all - nothing is defaulted,
 *       coerced or rewritten on the way in.</li>
 *   <li><strong>It does not truncate.</strong> {@code WS-MESSAGE} is {@code PIC X(80)} and
 *       {@code ERRMSGO} is {@code PIC X(78)}, so {@code app/cbl/COADM01C.cbl:177} performs a genuine
 *       two-byte right truncation. That belongs to {@code AdminMenuController} and its fixed-width
 *       codec. This type merely declares {@link #errMsg()} at its true width of
 *       {@value #ERR_MSG_LENGTH}.</li>
 *   <li><strong>It renders no fixed-width image.</strong> The request path needs none, so no codec is
 *       imported and no {@value #SYMBOLIC_MAP_LENGTH}-byte buffer is produced here.</li>
 *   <li><strong>It holds no business logic.</strong> No option validation, no comparison against the
 *       option count, no user-type authorisation, no menu building and no {@code 'DUMMY'} program
 *       prefix test. All of that lives in the admin service layer, which is what lets those branches
 *       be covered without an HTTP layer in the path.</li>
 *   <li><strong>It authenticates nothing.</strong> The admin menu screen has no credential field, so
 *       there is no password, token or role member and no security annotation.</li>
 * </ul>
 *
 * @param trnName          {@code TRNNAMEI PIC X(4)} (line 24): the transaction identifier,
 *                         {@value #TRANSACTION_ID}
 * @param title01          {@code TITLE01I PIC X(40)} (line 30): the first title line
 * @param curDate          {@code CURDATEI PIC X(8)} (line 36): the date header, {@code MM/DD/YY}
 * @param pgmName          {@code PGMNAMEI PIC X(8)} (line 42): the program name,
 *                         {@value #PROGRAM_NAME}
 * @param title02          {@code TITLE02I PIC X(40)} (line 48): the second title line
 * @param curTime          {@code CURTIMEI PIC X(8)} (line 54): the time header, {@code HH:MM:SS}
 * @param optn001          {@code OPTN001I PIC X(40)} (line 60): menu option line 1
 * @param optn002          {@code OPTN002I PIC X(40)} (line 66): menu option line 2
 * @param optn003          {@code OPTN003I PIC X(40)} (line 72): menu option line 3
 * @param optn004          {@code OPTN004I PIC X(40)} (line 78): menu option line 4
 * @param optn005          {@code OPTN005I PIC X(40)} (line 84): menu option line 5
 * @param optn006          {@code OPTN006I PIC X(40)} (line 90): menu option line 6
 * @param optn007          {@code OPTN007I PIC X(40)} (line 96): menu option line 7
 * @param optn008          {@code OPTN008I PIC X(40)} (line 102): menu option line 8
 * @param optn009          {@code OPTN009I PIC X(40)} (line 108): menu option line 9
 * @param optn010          {@code OPTN010I PIC X(40)} (line 114): menu option line 10
 * @param optn011          {@code OPTN011I PIC X(40)} (line 120): menu option line 11
 * @param optn012          {@code OPTN012I PIC X(40)} (line 126): menu option line 12
 * @param option           {@code OPTIONI PIC X(2)} (line 132): the typed option, and the only
 *                         unprotected field on the screen. Carried raw
 * @param errMsg           {@code ERRMSGI PIC X(78)} (line 138): the error message line, 78 and never
 *                         80
 * @param navigationContext the inbound {@code CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy},
 *                         exactly 160 bytes, carrying {@code CDEMO-USER-TYPE} with its
 *                         {@code 88} admin and user levels, {@code CDEMO-PGM-CONTEXT} with its
 *                         {@code 88} enter and re-enter levels, and the {@code X(7)} - not
 *                         {@code X(8)} - last map and last mapset. {@code null} means the caller
 *                         supplied none, which is this payload's encoding of
 *                         {@code IF EIBCALEN = 0} at {@code app/cbl/COADM01C.cbl:82}; see
 *                         {@link #isCommareaPresent()}
 * @param eibAid           the raw {@code EIBAID} attention identifier byte, carried
 *                         <strong>unresolved</strong>. {@code app/cbl/COADM01C.cbl:93-103} evaluates
 *                         it against {@code DFHENTER} and {@code DFHPF3} with a {@code WHEN OTHER}
 *                         default, so exactly three outcomes exist; mapping this byte to a key token
 *                         is the shared PF-key resolver's job in the service layer and is
 *                         deliberately not done here. Declared as a {@code byte} because that is the
 *                         type the resolver and the AID constants use, so the service passes this
 *                         value straight through with no conversion
 */
public record AdminMenuRequest(@Size(max = TRN_NAME_LENGTH) String trnName,
                               @Size(max = TITLE_LENGTH) String title01,
                               @Size(max = CUR_DATE_LENGTH) String curDate,
                               @Size(max = PGM_NAME_LENGTH) String pgmName,
                               @Size(max = TITLE_LENGTH) String title02,
                               @Size(max = CUR_TIME_LENGTH) String curTime,
                               @Size(max = OPTION_LINE_LENGTH) String optn001,
                               @Size(max = OPTION_LINE_LENGTH) String optn002,
                               @Size(max = OPTION_LINE_LENGTH) String optn003,
                               @Size(max = OPTION_LINE_LENGTH) String optn004,
                               @Size(max = OPTION_LINE_LENGTH) String optn005,
                               @Size(max = OPTION_LINE_LENGTH) String optn006,
                               @Size(max = OPTION_LINE_LENGTH) String optn007,
                               @Size(max = OPTION_LINE_LENGTH) String optn008,
                               @Size(max = OPTION_LINE_LENGTH) String optn009,
                               @Size(max = OPTION_LINE_LENGTH) String optn010,
                               @Size(max = OPTION_LINE_LENGTH) String optn011,
                               @Size(max = OPTION_LINE_LENGTH) String optn012,
                               @Size(max = OPTION_LENGTH) String option,
                               @Size(max = ERR_MSG_LENGTH) String errMsg,
                               NavigationContext navigationContext,
                               byte eibAid) {

    // =================================================================================================
    // Screen identity, taken from app/csd/CARDDEMO.CSD and the program's own WORKING-STORAGE. These are
    // the four names that identify this screen uniquely across the seventeen online programs.
    // =================================================================================================

    /**
     * The CICS transaction identifier: {@code DEFINE TRANSACTION(CA00) ... PROGRAM(COADM01C)} at
     * {@code app/csd/CARDDEMO.CSD:327-328}, and the literal
     * {@code WS-TRANID PIC X(04) VALUE 'CA00'} at {@code app/cbl/COADM01C.cbl:37}.
     */
    public static final String TRANSACTION_ID = "CA00";

    /**
     * The program name: {@code DEFINE PROGRAM(COADM01C)} at {@code app/csd/CARDDEMO.CSD:189}, and the
     * literal {@code WS-PGMNAME PIC X(08) VALUE 'COADM01C'} at {@code app/cbl/COADM01C.cbl:36}.
     */
    public static final String PROGRAM_NAME = "COADM01C";

    /**
     * The BMS mapset: {@code DEFINE MAPSET(COADM01)} at {@code app/csd/CARDDEMO.CSD:110}, declared as
     * {@code COADM01 DFHMSD} at {@code app/bms/COADM01.bms:19} and named by
     * {@code MAPSET('COADM01')} at {@code app/cbl/COADM01C.cbl:193}.
     */
    public static final String MAPSET_NAME = "COADM01";

    /**
     * The BMS map: {@code COADM1A DFHMDI COLUMN=1 LINE=1 SIZE=(24,80)} at
     * {@code app/bms/COADM01.bms:26-28}, named by {@code MAP('COADM1A')} at
     * {@code app/cbl/COADM01C.cbl:192}. Seven characters, because the symbolic-map group items are
     * formed as this name plus a one-character direction suffix: {@code COADM1AI} and
     * {@code COADM1AO}.
     */
    public static final String MAP_NAME = "COADM1A";

    // =================================================================================================
    // Symbolic-map item names, spelled VERBATIM as app/cpy-bms/COADM01.CPY spells them, trailing 'I'
    // included. Every one of the twenty payload members below names its item here, so a reviewer - or
    // the parity differ, which compares by copybook field name - can walk this class against the
    // copybook line by line. A "tidied up" name would make a real difference invisible.
    // =================================================================================================

    /** Symbolic-map item behind {@link #trnName()}: {@code TRNNAMEI}, CPY line 24. */
    public static final String TRN_NAME_FIELD = "TRNNAMEI";

    /** Symbolic-map item behind {@link #title01()}: {@code TITLE01I}, CPY line 30. */
    public static final String TITLE01_FIELD = "TITLE01I";

    /** Symbolic-map item behind {@link #curDate()}: {@code CURDATEI}, CPY line 36. */
    public static final String CUR_DATE_FIELD = "CURDATEI";

    /** Symbolic-map item behind {@link #pgmName()}: {@code PGMNAMEI}, CPY line 42. */
    public static final String PGM_NAME_FIELD = "PGMNAMEI";

    /** Symbolic-map item behind {@link #title02()}: {@code TITLE02I}, CPY line 48. */
    public static final String TITLE02_FIELD = "TITLE02I";

    /** Symbolic-map item behind {@link #curTime()}: {@code CURTIMEI}, CPY line 54. */
    public static final String CUR_TIME_FIELD = "CURTIMEI";

    /** Symbolic-map item behind {@link #optn001()}: {@code OPTN001I}, CPY line 60. */
    public static final String OPTN001_FIELD = "OPTN001I";

    /** Symbolic-map item behind {@link #optn002()}: {@code OPTN002I}, CPY line 66. */
    public static final String OPTN002_FIELD = "OPTN002I";

    /** Symbolic-map item behind {@link #optn003()}: {@code OPTN003I}, CPY line 72. */
    public static final String OPTN003_FIELD = "OPTN003I";

    /** Symbolic-map item behind {@link #optn004()}: {@code OPTN004I}, CPY line 78. */
    public static final String OPTN004_FIELD = "OPTN004I";

    /** Symbolic-map item behind {@link #optn005()}: {@code OPTN005I}, CPY line 84. */
    public static final String OPTN005_FIELD = "OPTN005I";

    /** Symbolic-map item behind {@link #optn006()}: {@code OPTN006I}, CPY line 90. */
    public static final String OPTN006_FIELD = "OPTN006I";

    /** Symbolic-map item behind {@link #optn007()}: {@code OPTN007I}, CPY line 96. */
    public static final String OPTN007_FIELD = "OPTN007I";

    /** Symbolic-map item behind {@link #optn008()}: {@code OPTN008I}, CPY line 102. */
    public static final String OPTN008_FIELD = "OPTN008I";

    /** Symbolic-map item behind {@link #optn009()}: {@code OPTN009I}, CPY line 108. */
    public static final String OPTN009_FIELD = "OPTN009I";

    /** Symbolic-map item behind {@link #optn010()}: {@code OPTN010I}, CPY line 114. */
    public static final String OPTN010_FIELD = "OPTN010I";

    /** Symbolic-map item behind {@link #optn011()}: {@code OPTN011I}, CPY line 120. */
    public static final String OPTN011_FIELD = "OPTN011I";

    /** Symbolic-map item behind {@link #optn012()}: {@code OPTN012I}, CPY line 126. */
    public static final String OPTN012_FIELD = "OPTN012I";

    /** Symbolic-map item behind {@link #option()}: {@code OPTIONI}, CPY line 132. */
    public static final String OPTION_FIELD = "OPTIONI";

    /** Symbolic-map item behind {@link #errMsg()}: {@code ERRMSGI}, CPY line 138. */
    public static final String ERR_MSG_FIELD = "ERRMSGI";

    // =================================================================================================
    // Field widths. Every value below is justified by the xxxI PICTURE clause it was read from, and the
    // CPY line number is given so the justification is checkable rather than asserted. Widths are NOT
    // deduplicated by value: TITLE_LENGTH and OPTION_LINE_LENGTH are both 40 but come from different
    // PICTURE clauses, so they stay separate and a future divergence in one cannot silently move the
    // other. app/bms/COADM01.bms corroborates every one of them with a matching LENGTH= operand.
    // =================================================================================================

    /**
     * Width of {@link #trnName()}: {@code TRNNAMEI PIC X(4)}, CPY line 24. Corroborated by
     * {@code LENGTH=4} at {@code app/bms/COADM01.bms:36}.
     */
    public static final int TRN_NAME_LENGTH = 4;

    /**
     * Width of {@link #title01()} and {@link #title02()}: {@code TITLE01I PIC X(40)} at CPY line 30
     * and {@code TITLE02I PIC X(40)} at CPY line 48. Corroborated by {@code LENGTH=40} at
     * {@code app/bms/COADM01.bms:40} and {@code :63}, both {@code COLOR=YELLOW}.
     *
     * <p>Declared here from the symbolic map rather than borrowed from the screen-title constants in
     * the common package. The two happen to agree at 40, but this constant is the width of a
     * <em>screen field</em> while that one is the width of a <em>title literal</em>; they are separate
     * facts that must be free to diverge, and the common package is not among this file's declared
     * dependencies.
     */
    public static final int TITLE_LENGTH = 40;

    /**
     * Width of {@link #curDate()}: {@code CURDATEI PIC X(8)}, CPY line 36. Corroborated by
     * {@code LENGTH=8} at {@code app/bms/COADM01.bms:49}, whose {@code INITIAL='mm/dd/yy'} at
     * {@code :51} fixes the {@code MM/DD/YY} shape that {@code app/cbl/COADM01C.cbl:211-215} builds.
     */
    public static final int CUR_DATE_LENGTH = 8;

    /**
     * Width of {@link #pgmName()}: {@code PGMNAMEI PIC X(8)}, CPY line 42. Corroborated by
     * {@code LENGTH=8} at {@code app/bms/COADM01.bms:59}. Eight characters is the true width of a
     * program name in this application - {@value #PROGRAM_NAME} is exactly eight.
     */
    public static final int PGM_NAME_LENGTH = 8;

    /**
     * Width of {@link #curTime()}: {@code CURTIMEI PIC X(8)}, CPY line 54. Corroborated by
     * {@code LENGTH=8} at {@code app/bms/COADM01.bms:72}, whose {@code INITIAL='hh:mm:ss'} at
     * {@code :74} fixes the {@code HH:MM:SS} shape that {@code app/cbl/COADM01C.cbl:217-221} builds.
     */
    public static final int CUR_TIME_LENGTH = 8;

    /**
     * Width of each of {@link #optn001()} through {@link #optn012()}: {@code OPTN00nI PIC X(40)} at
     * CPY lines 60, 66, 72, 78, 84, 90, 96, 102, 108, 114, 120 and 126. Corroborated by
     * {@code LENGTH=40} at {@code app/bms/COADM01.bms:82, 87, 92, 97, 102, 107, 112, 117, 122, 127,
     * 132} and {@code 137}.
     *
     * <p>Forty is what {@code app/cbl/COADM01C.cbl:48} builds into: {@code WS-ADMIN-OPT-TXT PIC X(40)}
     * receives {@code STRING} of a two-character option number, {@code '. '} and a
     * thirty-five-character option name - 2 + 2 + 35 = 39, one short of the field, so the line is
     * always space-padded by one.
     */
    public static final int OPTION_LINE_LENGTH = 40;

    /**
     * Width of {@link #option()}: {@code OPTIONI PIC X(2)}, CPY line 132. Corroborated by
     * {@code LENGTH=2} at {@code app/bms/COADM01.bms:148}.
     *
     * <p>Two is also the width of {@code WS-OPTION-X PIC X(02) JUST RIGHT} at
     * {@code app/cbl/COADM01C.cbl:45}, which is the receiving field of the normalisation this type
     * deliberately does not perform.
     */
    public static final int OPTION_LENGTH = 2;

    /**
     * Width of {@link #errMsg()}: {@code ERRMSGI PIC X(78)}, CPY line 138. Corroborated by
     * {@code LENGTH=78} at {@code app/bms/COADM01.bms:156}.
     *
     * <p><strong>Seventy-eight, never eighty.</strong> {@code WS-MESSAGE} is {@code PIC X(80)} at
     * {@code app/cbl/COADM01C.cbl:38}, so the {@code MOVE WS-MESSAGE TO ERRMSGO} at
     * {@code app/cbl/COADM01C.cbl:177} discards two bytes off the right. That truncation is real
     * behaviour and belongs to the controller; widening this constant to 80 to "avoid" it would
     * silently change what the screen can hold.
     */
    public static final int ERR_MSG_LENGTH = 78;

    /**
     * The number of menu option lines the map declares: twelve, at CPY lines 60 through 126 and
     * {@code app/bms/COADM01.bms:80-139}.
     *
     * <p><strong>All twelve are modelled even though nothing populates all twelve.</strong> Three
     * different counts coexist in the source and none of them is twelve:
     *
     * <ul>
     *   <li>The option table {@code app/cpy/COADM02Y.cpy} is {@code OCCURS 9}.</li>
     *   <li>{@code CDEMO-ADMIN-OPT-COUNT} is 4, so {@code BUILD-MENU-OPTIONS} at
     *       {@code app/cbl/COADM01C.cbl:228-229} iterates only four times.</li>
     *   <li>That paragraph's {@code EVALUATE WS-IDX} at {@code app/cbl/COADM01C.cbl:238-261} has
     *       branches for {@code WHEN 1} through {@code WHEN 10} and {@code WHEN OTHER CONTINUE}, so
     *       the program could never populate lines 11 and 12 even if the table were full.</li>
     * </ul>
     *
     * The projection is nevertheless of the <em>map</em>, not of the program's reads and writes, so
     * the two permanently empty slots are preserved rather than trimmed away. Dropping them would be
     * a change to the screen contract, and reinstating them later would be a change to this payload.
     */
    public static final int OPTION_LINE_COUNT = 12;

    // =================================================================================================
    // Symbolic-map geometry. These are the numbers the class-level documentation quotes, expressed as
    // arithmetic over the widths above so that they cannot drift away from them.
    // =================================================================================================

    /**
     * The {@code TIOAPFX} prefix that opens both views: {@code 02 FILLER PIC X(12).} at CPY lines 18
     * and 140, present because {@code app/bms/COADM01.bms:24} declares {@code TIOAPFX=YES}.
     */
    public static final int TIOAPFX_LENGTH = 12;

    /**
     * The metadata bytes each field contributes ahead of its value: seven.
     *
     * <p>On the input view that is {@code xxxL COMP PIC S9(4)} (2) + {@code xxxF PICTURE X} (1) +
     * {@code FILLER PICTURE X(4)} (4); the {@code xxxA} item adds nothing because it
     * {@code REDEFINES} the flag byte. On the output view it is {@code FILLER PICTURE X(3)} (3) +
     * {@code xxxC} + {@code xxxP} + {@code xxxH} + {@code xxxV} (1 each). The two strides agree,
     * which is what permits {@code 01 COADM1AO REDEFINES COADM1AI.} at CPY line 139.
     */
    public static final int METADATA_BYTES_PER_FIELD = 7;

    /**
     * The number of named screen fields: twenty.
     *
     * <p>Twenty {@code xxxI} items on the input view, twenty {@code xxxO} items on the output view,
     * and twenty of the 28 {@code DFHMDF} definitions in {@code app/bms/COADM01.bms} carrying a name.
     * The eight nameless ones are literal furniture and are enumerated in the class documentation.
     */
    public static final int MAPPED_FIELD_COUNT = 20;

    /**
     * The bytes of screen <em>data</em> in the map: 4 + 40 + 8 + 8 + 40 + 8 + (12 x 40) + 2 + 78,
     * which is 668.
     *
     * <p>Computed from the width constants rather than written as the literal so that a mistyped
     * width moves this total instead of hiding inside it.
     */
    public static final int PAYLOAD_DATA_LENGTH = TRN_NAME_LENGTH
            + TITLE_LENGTH
            + CUR_DATE_LENGTH
            + PGM_NAME_LENGTH
            + TITLE_LENGTH
            + CUR_TIME_LENGTH
            + OPTION_LINE_COUNT * OPTION_LINE_LENGTH
            + OPTION_LENGTH
            + ERR_MSG_LENGTH;

    /**
     * The width of the whole symbolic map, either view: 12 + (20 x 7) + 668, which is 820.
     *
     * <p>Recorded because it is the one number that ties the projection back to the copybook as a
     * whole. This type deliberately never renders an image of that width - see the class
     * documentation - so the constant is documentary and diagnostic rather than operational.
     */
    public static final int SYMBOLIC_MAP_LENGTH =
            TIOAPFX_LENGTH + MAPPED_FIELD_COUNT * METADATA_BYTES_PER_FIELD + PAYLOAD_DATA_LENGTH;

    /**
     * The {@value #OPTION_LINE_COUNT} option-line item names in map order, {@code OPTN001I} first.
     *
     * <p>Positionally parallel to {@link #optionLines()}: element <em>i</em> here names the item whose
     * value is element <em>i</em> there, so the two can be walked together when reporting a field-level
     * difference. Deeply immutable - {@link List#of} rejects modification and its elements are
     * {@code String}s - so exposing it as a constant introduces no shared mutable state.
     */
    public static final List<String> OPTION_LINE_FIELDS = List.of(OPTN001_FIELD,
            OPTN002_FIELD,
            OPTN003_FIELD,
            OPTN004_FIELD,
            OPTN005_FIELD,
            OPTN006_FIELD,
            OPTN007_FIELD,
            OPTN008_FIELD,
            OPTN009_FIELD,
            OPTN010_FIELD,
            OPTN011_FIELD,
            OPTN012_FIELD);

    // =================================================================================================
    // Derived views. Each is computed from a component rather than stored beside it, so there is never
    // a second copy of a value that could disagree with the first. All four are @JsonIgnore: the wire
    // format carries the twenty screen fields, the communication area and the attention identifier, and
    // nothing else.
    // =================================================================================================

    /**
     * The {@value #OPTION_LINE_COUNT} menu option lines in map order, {@link #optn001()} first.
     *
     * <p>A convenience view over the twelve individually declared components, which remain the single
     * source of truth. The components are declared individually rather than as a list because each one
     * is a distinct symbolic-map item with its own name, its own {@code DFHMDF} definition and its own
     * width constraint; a bare list would lose all three.
     *
     * <p>The returned list is <strong>unmodifiable</strong>: every mutator throws
     * {@link UnsupportedOperationException}, so a caller cannot reach back into this value. It is also
     * <strong>null-tolerant</strong> by deliberate choice, because an absent screen field is
     * {@code null} on this payload and {@link List#of} would reject it - a request that simply omits
     * an option line must yield a twelve-element list containing a {@code null}, not an exception.
     *
     * @return the twelve option lines in map order, never {@code null}, always of size
     *         {@value #OPTION_LINE_COUNT}, individual elements possibly {@code null}
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
     * Whether a communication area accompanied this request.
     *
     * <p>This encodes exactly one COBOL condition: the <em>negation</em> of {@code IF EIBCALEN = 0} at
     * {@code app/cbl/COADM01C.cbl:82}. {@code EIBCALEN} is the length CICS reports for
     * {@code DFHCOMMAREA}, and zero means the transaction was entered with no communication area at
     * all - typed at a clear screen rather than transferred to from another program. The program's
     * response is to move {@code 'COSGN00C'} into {@code CDEMO-FROM-PROGRAM} and transfer to the
     * sign-on screen without ever inspecting the map.
     *
     * <p>An absent communication area is therefore represented by a {@code null}
     * {@link #navigationContext()} rather than by a separate boolean flag. A flag would be a second
     * source of truth able to contradict the reference beside it, whereas the reference cannot
     * contradict itself: absent is absent.
     *
     * @return {@code true} when a communication area is present, mirroring the {@code ELSE} branch at
     *         {@code app/cbl/COADM01C.cbl:85}; {@code false} when it is absent, mirroring
     *         {@code EIBCALEN = 0} and the route to the sign-on screen
     */
    @JsonIgnore
    public boolean isCommareaPresent() {
        return navigationContext != null;
    }

    /**
     * Whether this is a first entry into the transaction - {@code 88 CDEMO-PGM-ENTER VALUE 0}.
     *
     * <p>Read straight through to {@link NavigationContext#isEnter()} rather than stored again here,
     * so {@code CDEMO-PGM-CONTEXT} keeps exactly one home. First entry is the state in which
     * {@code app/cbl/COADM01C.cbl:87-90} sets the context to re-enter, clears the output map to
     * {@code LOW-VALUES} and paints the screen without validating anything.
     *
     * <p>Returns {@code false} when no communication area is present, because the program never
     * reaches its context test in that case: {@code EIBCALEN = 0} at
     * {@code app/cbl/COADM01C.cbl:82} diverts to the sign-on screen first. A caller must therefore
     * consult {@link #isCommareaPresent()} <em>before</em> this method, exactly as the COBOL nests the
     * two tests. The guard order is behaviour, not style.
     *
     * @return {@code true} only when a communication area is present and its program context is the
     *         enter value
     */
    @JsonIgnore
    public boolean isEnter() {
        return navigationContext != null && navigationContext.isEnter();
    }

    /**
     * Whether this is a re-entry into the transaction - {@code 88 CDEMO-PGM-REENTER VALUE 1}.
     *
     * <p>Read straight through to {@link NavigationContext#isReenter()} for the same single-source
     * reason as {@link #isEnter()}. Re-entry is the state in which
     * {@code app/cbl/COADM01C.cbl:91-103} receives the map and dispatches on {@link #eibAid()}, and it
     * is the state in which field-level error highlighting applies.
     *
     * <p>Deliberately <strong>not</strong> written as {@code !isEnter()}. {@code CDEMO-PGM-CONTEXT} is
     * {@code PIC 9(01)} and can hold any digit, so a context of, say, 9 satisfies neither condition;
     * and an absent communication area satisfies neither either. Treating the two as complementary
     * would invent a branch the COBOL does not have.
     *
     * @return {@code true} only when a communication area is present and its program context is the
     *         re-enter value
     */
    @JsonIgnore
    public boolean isReenter() {
        return navigationContext != null && navigationContext.isReenter();
    }
}
