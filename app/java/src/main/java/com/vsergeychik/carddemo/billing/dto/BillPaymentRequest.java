package com.vsergeychik.carddemo.billing.dto;

import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.constraints.Size;

/**
 * The inbound payload of the CardDemo bill-payment screen: a field-for-field projection of
 * {@code 01 COBIL0AI}, the input view of the {@code COBIL00} symbolic map declared in
 * {@code app/cpy-bms/COBIL00.CPY}.
 *
 * <p>{@code app/cpy-bms/COBIL00.CPY} together with {@code app/bms/COBIL00.bms} is the
 * <strong>authoritative presentation contract</strong> for this type. No component library and no
 * design system exists in this repository, and none is permitted; the BMS mapset and its symbolic
 * map <em>are</em> the screen definition. Every member below traces to one {@code DFHMDF} field
 * definition, and every width traces to one symbolic-map {@code PICTURE} clause.
 *
 * <h2>What this type binds to</h2>
 *
 * <table border="1">
 *   <caption>The CICS artefacts this payload replaces</caption>
 *   <tr><th>Artefact</th><th>Value</th><th>Declared at</th></tr>
 *   <tr><td>Mapset</td><td>{@value #MAPSET_NAME}</td>
 *       <td>{@code app/csd/CARDDEMO.CSD:114}, {@code app/bms/COBIL00.bms:19}</td></tr>
 *   <tr><td>Map</td><td>{@value #MAP_NAME}</td><td>{@code app/bms/COBIL00.bms:26}</td></tr>
 *   <tr><td>Program</td><td>{@value #PROGRAM_NAME}</td>
 *       <td>{@code app/csd/CARDDEMO.CSD:196}, {@code app/cbl/COBIL00C.cbl:37}</td></tr>
 *   <tr><td>Transaction</td><td>{@value #TRANSACTION_ID}</td>
 *       <td>{@code app/csd/CARDDEMO.CSD:337-338}, {@code app/cbl/COBIL00C.cbl:38}</td></tr>
 *   <tr><td>REST resource</td><td>{@code POST /api/billpay}</td><td>this migration</td></tr>
 * </table>
 *
 * <p>The CSD binds the two together directly: {@code DEFINE TRANSACTION(CB00) GROUP(CARDDEMO)}
 * followed by {@code PROGRAM(COBIL00C) TWASIZE(0) PROFILE(DFHCICST) STATUS(ENABLED)}. Entering
 * {@value #TRANSACTION_ID} at a 3270 terminal is what {@code POST /api/billpay} now stands in for.
 *
 * <p>The program's own header states its purpose, transcribed here verbatim from
 * {@code app/cbl/COBIL00C.cbl:5-6} - including the source's own spelling of "tractionsaction",
 * which is left exactly as written because this migration records source facts rather than
 * correcting them:
 *
 * <pre>
 * * Function    : Bill Payment - Pay account balance in full and a
 * *               tractionsaction for the online bill payment.
 * </pre>
 *
 * <h2>The ten payload members, and why there are exactly ten</h2>
 *
 * A BMS symbolic map is a group item whose members repeat in a fixed pattern. {@code 01 COBIL0AI}
 * opens with a {@value #TIOAPFX_PREFIX_LENGTH}-byte unnamed {@code FILLER} - the {@code TIOAPFX=YES}
 * prefix requested at {@code app/bms/COBIL00.bms:24} - and then, for each screen field, declares a
 * {@value #FIELD_PROLOGUE_LENGTH}-byte prologue followed by the data item:
 *
 * <pre>
 *  02  ACTIDINL    COMP  PIC  S9(4).      the inbound length CICS reports        2 bytes
 *  02  ACTIDINF    PICTURE X.             the flag byte                          1 byte
 *  02  FILLER REDEFINES ACTIDINF.
 *    03 ACTIDINA    PICTURE X.            the attribute view over the flag       0 bytes
 *  02  FILLER   PICTURE X(4).             reserved                               4 bytes
 *  02  ACTIDINI  PIC X(11).               THE DATA - this is the payload field
 * </pre>
 *
 * <p><strong>Only the {@code xxxI} items are payload members.</strong> The {@code xxxL} length item,
 * the {@code xxxF} flag byte and the {@code xxxA} attribute view are validation and field-highlight
 * <em>metadata</em>; they are not carried here and no member is named after one. Consequently there
 * is no {@code actIdInL}, no {@code confirmF} and no {@code errMsgA} on this class. The
 * {@code xxxL} items do appear in the source as cursor-positioning writes - {@code MOVE -1 TO
 * ACTIDINL} at {@code app/cbl/COBIL00C.cbl:115}, {@code 163}, {@code 203} and {@code 562}, and
 * {@code MOVE -1 TO CONFIRML} at {@code 189} and {@code 239} - which is presentation behaviour owned
 * by the controller, not payload state.
 *
 * <p>{@code app/bms/COBIL00.bms} carries <strong>24</strong> {@code DFHMDF} statements: these ten
 * name-labelled fields plus fourteen unnamed ones. The fourteen are screen furniture - the literals
 * {@code 'Tran:'}, {@code 'Date:'}, {@code 'Prog:'}, {@code 'Time:'}, {@code 'Bill Payment'},
 * {@code 'Enter Acct ID:'}, a 70-character rule at line 8, {@code 'Your current balance is: '},
 * {@code 'Do you want to pay your balance now. Please confirm: '}, {@code '(Y/N)'} and
 * {@code 'ENTER=Continue  F3=Back  F4=Clear'} - plus three {@code LENGTH=0} stopper fields at
 * {@code (6,33)}, {@code (11,47)} and {@code (15,62)}. Furniture is rendered by the client and is
 * modelled by no member here. {@code COBIL00} is the smallest of the seventeen mapsets, so ten
 * members is the whole of it; an eleventh would mean furniture or metadata had been modelled.
 *
 * <table border="1">
 *   <caption>The ten members, in symbolic-map source order</caption>
 *   <tr><th>#</th><th>Symbolic-map item</th><th>Member</th><th>Width</th>
 *       <th>{@code DFHMDF} attributes</th></tr>
 *   <tr><td>1</td><td>{@code TRNNAMEI PIC X(4)} (L24)</td><td>{@link #getTrnName()}</td>
 *       <td>{@value #TRN_NAME_LENGTH}</td>
 *       <td>L34 {@code ASKIP,FSET,NORM} BLUE {@code (1,7)}</td></tr>
 *   <tr><td>2</td><td>{@code TITLE01I PIC X(40)} (L30)</td><td>{@link #getTitle01()}</td>
 *       <td>{@value #TITLE01_LENGTH}</td>
 *       <td>L38 {@code ASKIP,FSET,NORM} YELLOW {@code (1,21)}</td></tr>
 *   <tr><td>3</td><td>{@code CURDATEI PIC X(8)} (L36)</td><td>{@link #getCurDate()}</td>
 *       <td>{@value #CUR_DATE_LENGTH}</td>
 *       <td>L47 {@code ASKIP,FSET,NORM} BLUE {@code (1,71)} {@code INITIAL='mm/dd/yy'}</td></tr>
 *   <tr><td>4</td><td>{@code PGMNAMEI PIC X(8)} (L42)</td><td>{@link #getPgmName()}</td>
 *       <td>{@value #PGM_NAME_LENGTH}</td>
 *       <td>L57 {@code ASKIP,FSET,NORM} BLUE {@code (2,7)}</td></tr>
 *   <tr><td>5</td><td>{@code TITLE02I PIC X(40)} (L48)</td><td>{@link #getTitle02()}</td>
 *       <td>{@value #TITLE02_LENGTH}</td>
 *       <td>L61 {@code ASKIP,FSET,NORM} YELLOW {@code (2,21)}</td></tr>
 *   <tr><td>6</td><td>{@code CURTIMEI PIC X(8)} (L54)</td><td>{@link #getCurTime()}</td>
 *       <td>{@value #CUR_TIME_LENGTH}</td>
 *       <td>L70 {@code ASKIP,FSET,NORM} BLUE {@code (2,71)} {@code INITIAL='hh:mm:ss'}</td></tr>
 *   <tr><td>7</td><td>{@code ACTIDINI PIC X(11)} (L60)</td><td>{@link #getActIdIn()}</td>
 *       <td>{@value #ACT_ID_IN_LENGTH}</td>
 *       <td>L85 <strong>{@code FSET,IC,NORM,UNPROT}</strong> GREEN {@code (6,21)}
 *           {@code HILIGHT=UNDERLINE}</td></tr>
 *   <tr><td>8</td><td>{@code CURBALI PIC X(14)} (L66)</td><td>{@link #getCurBal()}</td>
 *       <td>{@value #CUR_BAL_LENGTH}</td>
 *       <td>L103 {@code ASKIP,FSET,NORM} BLUE {@code (11,32)}</td></tr>
 *   <tr><td>9</td><td>{@code CONFIRMI PIC X(1)} (L72)</td><td>{@link #getConfirm()}</td>
 *       <td>{@value #CONFIRM_LENGTH}</td>
 *       <td>L115 <strong>{@code FSET,NORM,UNPROT}</strong> GREEN {@code (15,60)}
 *           {@code HILIGHT=UNDERLINE}</td></tr>
 *   <tr><td>10</td><td>{@code ERRMSGI PIC X(78)} (L78)</td><td>{@link #getErrMsg()}</td>
 *       <td>{@value #ERR_MSG_LENGTH}</td>
 *       <td>L127 {@code ASKIP,BRT,FSET} RED {@code (23,1)}</td></tr>
 *   <tr><td></td><td><strong>Data total</strong></td><td></td>
 *       <td><strong>{@value #MAP_DATA_LENGTH}</strong></td><td></td></tr>
 * </table>
 *
 * <p>The map is therefore {@value #SYMBOLIC_MAP_LENGTH} bytes wide:
 * {@value #TIOAPFX_PREFIX_LENGTH} prefix + {@value #MAP_FIELD_COUNT} fields at
 * {@value #FIELD_PROLOGUE_LENGTH} bytes of prologue each + {@value #MAP_DATA_LENGTH} bytes of data,
 * that is 12 + 70 + 212 = {@value #SYMBOLIC_MAP_LENGTH}. {@link #MAP_DATA_LENGTH} and
 * {@link #SYMBOLIC_MAP_LENGTH} are not typed-in totals: both are declared as the sum of the
 * individual width constants, so a mistyped width changes the total rather than hiding inside it.
 *
 * <h2>Request and response carry the same ten names at the same widths</h2>
 *
 * {@code app/cpy-bms/COBIL00.CPY:79} declares {@code 01 COBIL0AO REDEFINES COBIL0AI}, so the output
 * view occupies the identical byte span - it merely renames the per-field prologue
 * ({@code xxxC}, {@code xxxP}, {@code xxxH}, {@code xxxV}) and the data item ({@code xxxO}). The
 * data widths are unchanged, which is why {@code BillPaymentResponse} carries these same ten member
 * names at these same ten widths.
 *
 * <p>{@code COBIL00C} writes some fields through the input view and others through the output view -
 * {@code ACTIDINI} at lines 118-119, {@code CURBALI} at 194, {@code CONFIRMI} at 563-565 through
 * {@code COBIL0AI}; {@code TITLE01O}, {@code TITLE02O}, {@code TRNNAMEO}, {@code PGMNAMEO},
 * {@code CURDATEO} and {@code CURTIMEO} at 323-338 and {@code ERRMSGO} at 293 through
 * {@code COBIL0AO}. That is legal precisely because the two views share one span, and it is why
 * both payload types carry all ten fields. The fields are not split by which view touched them.
 *
 * <h2>Two members are inbound echo only</h2>
 *
 * {@link #getCurBal()} and {@link #getErrMsg()} are present because the contract is a 1:1 projection
 * of all ten screen fields, not because the program reads them inbound. Verified by exhaustive
 * search of {@code app/cbl/COBIL00C.cbl}: {@code CURBALI} appears only as a destination, at line 194
 * ({@code MOVE WS-CURR-BAL TO CURBALI}) and line 564 (space-filled by
 * {@code INITIALIZE-ALL-FIELDS}); {@code ERRMSGO} appears only at line 105 (cleared unconditionally
 * on entry), line 293 ({@code MOVE WS-MESSAGE TO ERRMSGO}) and line 526 (a colour attribute).
 * Neither is ever read as input. {@code BillPaymentService} therefore ignores both; a client may
 * echo back whatever it was last sent without changing any outcome.
 *
 * <h2>Statelessness: the conversation travels in this payload</h2>
 *
 * CICS is pseudo-conversational. {@code COBIL00C} runs to completion for every keystroke and
 * survives only through what it hands back on {@code EXEC CICS RETURN TRANSID(WS-TRANID)
 * COMMAREA(CARDDEMO-COMMAREA)} at lines 146-149. This migration preserves that shape exactly, so
 * all three pieces of conversation state are members of this payload:
 * {@link #getNavigationContext()} for the communication area, {@link #getAid()} for the attention
 * identifier and {@link #getPgmContext()} for the enter-versus-re-enter context.
 *
 * <p>Nothing here is server-side state. This type is deliberately free of {@code HttpSession},
 * {@code @SessionAttributes}, any server-side cache and any static holder - a static holder would be
 * a session by another name and would break both request isolation and test determinism.
 *
 * <h2>Why this is a plain mutable class with hand-written accessors</h2>
 *
 * A {@code record} was considered and rejected. This type carries nineteen members, and the unit
 * tests plus the twenty declarative parity cases for {@code COBIL00C} construct
 * <em>partially populated</em> instances constantly - a nineteen-component canonical constructor
 * makes that painful and brittle, while a no-argument constructor plus setters makes it a single
 * line per field. Jackson also binds a no-argument bean with no {@code @JsonCreator} ceremony. The
 * accessors are written out by hand rather than generated so that each member's correspondence to
 * its symbolic-map item stays visible during review; no accessor-generating and no bean-mapping
 * library is used anywhere in this module.
 *
 * <p>Instance mutability is intended. <strong>Static</strong> mutable state is not: every static
 * member of this class is {@code final} and is either an {@code int} or a {@code String}, so there
 * is no static array, no static collection and no static non-final field. COBOL
 * {@code WORKING-STORAGE} never becomes a static Java field.
 *
 * <h2>This class contains no decision logic, by design</h2>
 *
 * Every member is stored and returned as given. Nothing is trimmed, padded, defaulted,
 * upper-cased or coerced from empty to {@code null}, and no setter validates. That is a behavioural
 * requirement rather than a stylistic one, for two reasons.
 *
 * <p>First, distinctness. {@code MOVE LOW-VALUES TO COBIL0AO} at line 114 and the {@code MOVE
 * SPACES} of {@code INITIALIZE-ALL-FIELDS} at lines 563-565 leave a field in two
 * <em>different</em> observable states, and the program tests for both - {@code WHEN ACTIDINI = SPACES
 * OR LOW-VALUES} at line 159, {@code WHEN SPACES} and {@code WHEN LOW-VALUES} as separate arms at
 * lines 182-183. On the wire those states arrive as JSON {@code null}, as an empty string and as an
 * all-blank string, and all three must reach the service unaltered for those tests to remain
 * reachable. Normalising them here would silently merge cases the COBOL distinguishes.
 *
 * <p>Second, the decision logic belongs one layer in. The {@code = SPACES OR LOW-VALUES} test, the
 * {@code 'Y'}/{@code 'y'}/{@code 'N'}/{@code 'n'} confirmation switch of lines 173-191 and the
 * {@code ACCT-CURR-BAL <= ZEROS} test of line 198 all live in {@code BillPaymentService}, where the
 * parity tests reach them with no HTTP layer in the path. Keeping this carrier free of conditional
 * logic also keeps it free of branches, which matters concretely: coverage is gated on the branch
 * counter at package level as well as at bundle level, so a branch added to a data carrier becomes
 * a branch its package must then prove it exercised.
 *
 * <p>{@code equals} and {@code hashCode} are omitted for that same reason. Any correct Java
 * {@code equals} needs a type test, and a type test is a branch; the parity differ compares field by
 * field rather than by whole-object equality, so neither method is needed. {@link #toString()} is
 * provided because it costs no branch, and it deliberately does <strong>not</strong> mask
 * {@link #getActIdIn()}: the 3270 screen shows the account identifier in the clear at
 * {@code (6,21)}, and this is a like-for-like migration.
 *
 * <h2>Validation: two constraints, and deliberately no presence constraint</h2>
 *
 * Only {@code ACTIDIN} and {@code CONFIRM} are declared {@code UNPROT} in the mapset, so only those
 * two can be typed into at a terminal; the other eight are {@code ASKIP} and CICS never reports an
 * inbound length for them. Accordingly {@link #getActIdIn()} carries
 * {@code @Size(max = }{@value #ACT_ID_IN_LENGTH}{@code )} and {@link #getConfirm()} carries
 * {@code @Size(max = }{@value #CONFIRM_LENGTH}{@code )}, and the remaining eight carry no constraint
 * annotation at all. Their widths are still explicit: each is a named length constant, cited in that
 * member's documentation.
 *
 * <p><strong>No presence constraint of any kind is declared on any member.</strong> A blank
 * field is not a protocol error in this program, it is an ordinary outcome that produces a message:
 * an empty account identifier yields {@code 'Acct ID can NOT be empty...'} with the cursor
 * repositioned and the screen re-sent (lines 159-164), and a blank confirmation is a
 * <em>valid</em> path that performs {@code READ-ACCTDAT-FILE} (lines 182-184) - it is how a user asks
 * to see the balance before deciding to pay. A presence constraint would turn both into rejected
 * requests and delete observable behaviour.
 *
 * <h2>Source facts recorded rather than corrected</h2>
 *
 * <ul>
 *   <li>{@code app/bms/COBIL00.bms:1-2} carries the header comment
 *       {@code *    CardDemo - Main Menu Screen}, although {@code COBIL00} is the bill-payment
 *       screen and the main menu is {@code COMEN01}. The comment is wrong in the source and is left
 *       wrong there.</li>
 *   <li>{@code app/cpy-bms/COBIL00.CPY:61} spells the length item for {@code CURBAL} as
 *       {@code CURBALL}. It reads like a typo but is the mechanical {@code xxxL} suffix applied to
 *       the stem {@code CURBAL}, which happens to end in {@code L}. The generator's own naming rule
 *       produced it, and the data item at line 66 is spelled {@code CURBALI} as normal.</li>
 *   <li>{@code app/cbl/COBIL00C.cbl:39} declares {@code WS-MESSAGE PIC X(80)} while
 *       {@code ERRMSGI} and {@code ERRMSGO} are {@code PIC X(78)}, so {@code MOVE WS-MESSAGE TO
 *       ERRMSGO} at line 293 truncates two bytes from the right on every message. The screen field
 *       is 78 characters wide and {@link #ERR_MSG_LENGTH} stays 78; the mismatch is documented here
 *       rather than resolved by widening either side.</li>
 *   <li>Five of the six {@code CDEMO-CB00-INFO} members are declared and never read. They are
 *       carried regardless; see the notes on each.</li>
 * </ul>
 *
 * <p>Its outbound twin is {@code BillPaymentResponse}, which projects {@code 01 COBIL0AO} and
 * therefore carries the same ten member names at the same ten widths. The two are referred to here in
 * prose rather than through a {@code @see} link, as are {@code BillPaymentController} and
 * {@code BillPaymentService}: none of them is a dependency of this type, so this file resolves
 * completely on its own.
 *
 * @see NavigationContext
 */
public final class BillPaymentRequest {

    // =================================================================================================
    // Identity of the CICS artefacts this payload replaces. These are not payload members: they are
    // the literal values the source declares, kept here so the binding is stated once and can be
    // asserted rather than retyped. WS-TRANID and WS-PGMNAME are the values POPULATE-HEADER-INFO
    // moves into TRNNAMEO and PGMNAMEO at app/cbl/COBIL00C.cbl:325-326, so two of the ten members
    // below are populated from exactly these two constants.
    // =================================================================================================

    /**
     * The mapset name, {@code COBIL00}: {@code DEFINE MAPSET(COBIL00) GROUP(CARDDEMO)} at
     * {@code app/csd/CARDDEMO.CSD:114}, and the label on {@code DFHMSD} at
     * {@code app/bms/COBIL00.bms:19}. Named on every {@code SEND MAP} and {@code RECEIVE MAP} in the
     * program, at lines 297 and 310.
     */
    public static final String MAPSET_NAME = "COBIL00";

    /**
     * The map name, {@code COBIL0A}: the label on {@code DFHMDI} at
     * {@code app/bms/COBIL00.bms:26}, which declares {@code COLUMN=1}, {@code LINE=1} and
     * {@code SIZE=(24,80)}. Seven characters, because the eighth position of the symbolic-map group
     * item carries the direction suffix - {@code COBIL0AI} for input, {@code COBIL0AO} for output.
     */
    public static final String MAP_NAME = "COBIL0A";

    /**
     * The program name, {@code COBIL00C}: {@code DEFINE PROGRAM(COBIL00C) GROUP(CARDDEMO)} at
     * {@code app/csd/CARDDEMO.CSD:196}, and {@code WS-PGMNAME PIC X(08) VALUE 'COBIL00C'} at
     * {@code app/cbl/COBIL00C.cbl:37}. This is the value that reaches {@link #getPgmName()}.
     */
    public static final String PROGRAM_NAME = "COBIL00C";

    /**
     * The transaction identifier, {@code CB00}: {@code DEFINE TRANSACTION(CB00) GROUP(CARDDEMO)}
     * with {@code PROGRAM(COBIL00C)} at {@code app/csd/CARDDEMO.CSD:337-338}, and
     * {@code WS-TRANID PIC X(04) VALUE 'CB00'} at {@code app/cbl/COBIL00C.cbl:38}. This is the value
     * that reaches {@link #getTrnName()}, and the {@code TRANSID} the program returns with at
     * line 147.
     */
    public static final String TRANSACTION_ID = "CB00";

    // =================================================================================================
    // Symbolic-map geometry. Declared so the 294-byte total is derived from the individual widths
    // rather than asserted alongside them (practice B8: explicit at every boundary).
    // =================================================================================================

    /**
     * The {@value #TIOAPFX_PREFIX_LENGTH}-byte unnamed {@code FILLER} that opens
     * {@code 01 COBIL0AI} at {@code app/cpy-bms/COBIL00.CPY:18}, present because
     * {@code app/bms/COBIL00.bms:24} requests {@code TIOAPFX=YES}. It carries no screen data and
     * therefore no member.
     */
    public static final int TIOAPFX_PREFIX_LENGTH = 12;

    /**
     * The per-field prologue that precedes every data item in the symbolic map:
     * {@code xxxL COMP PIC S9(4)} occupies 2 bytes, {@code xxxF PICTURE X} occupies 1, and the
     * reserved {@code FILLER PICTURE X(4)} occupies 4, totalling
     * {@value #FIELD_PROLOGUE_LENGTH}. The {@code xxxA} item adds nothing because it
     * {@code REDEFINES} the flag byte.
     */
    public static final int FIELD_PROLOGUE_LENGTH = 7;

    /**
     * The number of screen fields in {@code COBIL00} that carry a name and therefore a payload
     * member: {@value #MAP_FIELD_COUNT}. The mapset declares 24 {@code DFHMDF} statements in total;
     * the other fourteen are unnamed literals and stopper fields.
     */
    public static final int MAP_FIELD_COUNT = 10;

    // =================================================================================================
    // The ten declared field widths, taken from the xxxI PICTURE clauses of app/cpy-bms/COBIL00.CPY
    // and cross-checked against the LENGTH= operand of the matching DFHMDF in app/bms/COBIL00.bms.
    // =================================================================================================

    /**
     * Width of {@code TRNNAMEI PIC X(4)} at {@code app/cpy-bms/COBIL00.CPY:24}, matching
     * {@code LENGTH=4} at {@code app/bms/COBIL00.bms:36}. Four characters, exactly the width of a
     * CICS transaction identifier.
     */
    public static final int TRN_NAME_LENGTH = 4;

    /**
     * Width of {@code TITLE01I PIC X(40)} at {@code app/cpy-bms/COBIL00.CPY:30}, matching
     * {@code LENGTH=40} at {@code app/bms/COBIL00.bms:40}.
     */
    public static final int TITLE01_LENGTH = 40;

    /**
     * Width of {@code CURDATEI PIC X(8)} at {@code app/cpy-bms/COBIL00.CPY:36}, matching
     * {@code LENGTH=8} at {@code app/bms/COBIL00.bms:49}. Eight characters holds {@code mm/dd/yy},
     * which is both the {@code INITIAL} value in the mapset and the shape the program builds.
     */
    public static final int CUR_DATE_LENGTH = 8;

    /**
     * Width of {@code PGMNAMEI PIC X(8)} at {@code app/cpy-bms/COBIL00.CPY:42}, matching
     * {@code LENGTH=8} at {@code app/bms/COBIL00.bms:59}. Eight characters, exactly the width of a
     * program name in this application.
     */
    public static final int PGM_NAME_LENGTH = 8;

    /**
     * Width of {@code TITLE02I PIC X(40)} at {@code app/cpy-bms/COBIL00.CPY:48}, matching
     * {@code LENGTH=40} at {@code app/bms/COBIL00.bms:63}.
     */
    public static final int TITLE02_LENGTH = 40;

    /**
     * Width of {@code CURTIMEI PIC X(8)} at {@code app/cpy-bms/COBIL00.CPY:54}, matching
     * {@code LENGTH=8} at {@code app/bms/COBIL00.bms:72}. Eight characters holds
     * {@code hh:mm:ss}.
     */
    public static final int CUR_TIME_LENGTH = 8;

    /**
     * Width of {@code ACTIDINI PIC X(11)} at {@code app/cpy-bms/COBIL00.CPY:60}, matching
     * {@code LENGTH=11} at {@code app/bms/COBIL00.bms:88}. Eleven characters, the same width as
     * {@code ACCT-ID PIC 9(11)} in {@code app/cpy/CVACT01Y.cpy} and as
     * {@code CDEMO-ACCT-ID PIC 9(11)} in the communication area. This is the constraint applied to
     * {@link #getActIdIn()}.
     */
    public static final int ACT_ID_IN_LENGTH = 11;

    /**
     * Width of {@code CURBALI PIC X(14)} at {@code app/cpy-bms/COBIL00.CPY:66}, matching
     * {@code LENGTH=14} at {@code app/bms/COBIL00.bms:105}. Fourteen is not arbitrary: it is the
     * rendered width of the edit mask {@code WS-CURR-BAL PIC +9999999999.99} declared at
     * {@code app/cbl/COBIL00C.cbl:56} - one sign, ten digits, one decimal point and two more
     * digits. See {@link #getCurBal()} for why the member is text rather than a number.
     */
    public static final int CUR_BAL_LENGTH = 14;

    /**
     * Width of {@code CONFIRMI PIC X(1)} at {@code app/cpy-bms/COBIL00.CPY:72}, matching
     * {@code LENGTH=1} at {@code app/bms/COBIL00.bms:118}. One character, which is why the mapset
     * places the literal {@code '(Y/N)'} beside it at {@code (15,63)}. This is the constraint
     * applied to {@link #getConfirm()}.
     */
    public static final int CONFIRM_LENGTH = 1;

    /**
     * Width of {@code ERRMSGI PIC X(78)} at {@code app/cpy-bms/COBIL00.CPY:78}, matching
     * {@code LENGTH=78} at {@code app/bms/COBIL00.bms:129}. Seventy-eight, not eighty: the program's
     * {@code WS-MESSAGE} is {@code PIC X(80)}, so {@code MOVE WS-MESSAGE TO ERRMSGO} at line 293
     * loses the final two bytes. The screen field governs, and this constant stays at 78.
     */
    public static final int ERR_MSG_LENGTH = 78;

    /**
     * Total width of the ten data items, {@value #MAP_DATA_LENGTH} bytes, declared as the sum of the
     * ten width constants so that the arithmetic 4 + 40 + 8 + 8 + 40 + 8 + 11 + 14 + 1 + 78 is
     * carried out by the compiler rather than asserted by hand.
     */
    public static final int MAP_DATA_LENGTH = TRN_NAME_LENGTH
            + TITLE01_LENGTH
            + CUR_DATE_LENGTH
            + PGM_NAME_LENGTH
            + TITLE02_LENGTH
            + CUR_TIME_LENGTH
            + ACT_ID_IN_LENGTH
            + CUR_BAL_LENGTH
            + CONFIRM_LENGTH
            + ERR_MSG_LENGTH;

    /**
     * Total width of {@code 01 COBIL0AI}, {@value #SYMBOLIC_MAP_LENGTH} bytes: the
     * {@value #TIOAPFX_PREFIX_LENGTH}-byte prefix, plus {@value #MAP_FIELD_COUNT} prologues of
     * {@value #FIELD_PROLOGUE_LENGTH} bytes, plus {@value #MAP_DATA_LENGTH} bytes of data. That is
     * 12 + 70 + 212. {@code 01 COBIL0AO} is the same width because it {@code REDEFINES} this group.
     */
    public static final int SYMBOLIC_MAP_LENGTH = TIOAPFX_PREFIX_LENGTH
            + (MAP_FIELD_COUNT * FIELD_PROLOGUE_LENGTH)
            + MAP_DATA_LENGTH;

    // =================================================================================================
    // The CDEMO-CB00-INFO extension. app/cbl/COBIL00C.cbl:64-72 appends a 05-level group INSIDE
    // 01 CARDDEMO-COMMAREA, immediately after COPY COCOM01Y, so this program's communication area is
    // 58 bytes longer than the shared 160-byte one. Those 58 bytes are declared as six flat members
    // on this class rather than being added to NavigationContext, which must stay exactly 160 bytes
    // because all seventeen controllers share it.
    // =================================================================================================

    /**
     * Width of {@code CDEMO-CB00-TRNID-FIRST PIC X(16)} at {@code app/cbl/COBIL00C.cbl:65}. Sixteen
     * characters, the width of {@code TRAN-ID} in {@code app/cpy/CVTRA05Y.cpy}.
     */
    public static final int TRN_ID_FIRST_LENGTH = 16;

    /**
     * Width of {@code CDEMO-CB00-TRNID-LAST PIC X(16)} at {@code app/cbl/COBIL00C.cbl:66}.
     */
    public static final int TRN_ID_LAST_LENGTH = 16;

    /**
     * Digit count of {@code CDEMO-CB00-PAGE-NUM PIC 9(08)} at {@code app/cbl/COBIL00C.cbl:67}. Eight
     * digits with no {@code V} and no assumed decimal position, which is exactly the condition under
     * which a COBOL numeric item becomes a Java {@code int} rather than a scaled decimal.
     */
    public static final int PAGE_NUM_DIGITS = 8;

    /**
     * Width of {@code CDEMO-CB00-NEXT-PAGE-FLG PIC X(01)} at {@code app/cbl/COBIL00C.cbl:68}.
     */
    public static final int NEXT_PAGE_FLG_LENGTH = 1;

    /**
     * Width of {@code CDEMO-CB00-TRN-SEL-FLG PIC X(01)} at {@code app/cbl/COBIL00C.cbl:71}.
     */
    public static final int TRN_SEL_FLG_LENGTH = 1;

    /**
     * Width of {@code CDEMO-CB00-TRN-SELECTED PIC X(16)} at {@code app/cbl/COBIL00C.cbl:72}.
     */
    public static final int TRN_SELECTED_LENGTH = 16;

    /**
     * Total width of {@code CDEMO-CB00-INFO}, {@value #CB00_INFO_LENGTH} bytes, declared as the sum
     * of its six members: 16 + 16 + 8 + 1 + 1 + 16.
     */
    public static final int CB00_INFO_LENGTH = TRN_ID_FIRST_LENGTH
            + TRN_ID_LAST_LENGTH
            + PAGE_NUM_DIGITS
            + NEXT_PAGE_FLG_LENGTH
            + TRN_SEL_FLG_LENGTH
            + TRN_SELECTED_LENGTH;

    /**
     * Total width of the communication area as {@code COBIL00C} declares it,
     * {@value #CB00_COMMAREA_LENGTH} bytes: the shared
     * {@value NavigationContext#COMMAREA_LENGTH}-byte {@code CARDDEMO-COMMAREA} plus this program's
     * {@value #CB00_INFO_LENGTH}-byte extension. Sixteen of the seventeen online programs extend the
     * communication area this way, each with its own suffix group, which is why the extension is
     * modelled per screen rather than centrally.
     */
    public static final int CB00_COMMAREA_LENGTH =
            NavigationContext.COMMAREA_LENGTH + CB00_INFO_LENGTH;

    /**
     * The value of {@code 88 NEXT-PAGE-YES} at {@code app/cbl/COBIL00C.cbl:69}, {@code 'Y'}. Tested
     * by {@link #nextPageYes()}.
     */
    public static final String NEXT_PAGE_YES = "Y";

    /**
     * The value of {@code 88 NEXT-PAGE-NO} at {@code app/cbl/COBIL00C.cbl:70}, {@code 'N'}, which is
     * also the {@code VALUE} clause on the field itself at line 68 and therefore the initial state
     * of {@link #getNextPageFlg()}. Tested by {@link #nextPageNo()}.
     */
    public static final String NEXT_PAGE_NO = "N";

    // =================================================================================================
    // THE TEN PAYLOAD MEMBERS, in the order 01 COBIL0AI declares them. Each is the xxxI data item and
    // nothing else: no length item, no flag byte, no attribute view. Each is a String because every
    // xxxI item is PIC X(n) - the symbolic map has no numeric data item at all.
    //
    // Every member stays independently nullable. COBOL distinguishes LOW-VALUES from SPACES and the
    // program tests for both separately, so JSON null, the empty string and an all-blank string are
    // three distinct inbound states that must survive binding unchanged.
    // =================================================================================================

    /**
     * {@code TRNNAMEI PIC X(4)}, {@code app/cpy-bms/COBIL00.CPY:24}. The transaction identifier shown
     * at {@code (1,7)} beside the {@code 'Tran:'} literal;
     * {@code app/bms/COBIL00.bms:34-37} declares it {@code ASKIP,FSET,NORM} in BLUE.
     *
     * <p>Populated by the program from {@code WS-TRANID} at {@code app/cbl/COBIL00C.cbl:325}, so its
     * value on a well-formed screen is {@value #TRANSACTION_ID}. Display only:
     * {@code ASKIP} means the terminal cannot place the cursor in it, so nothing a user does can
     * change it. Width {@value #TRN_NAME_LENGTH}.
     */
    private String trnName;

    /**
     * {@code TITLE01I PIC X(40)}, {@code app/cpy-bms/COBIL00.CPY:30}. The first title line at
     * {@code (1,21)}; {@code app/bms/COBIL00.bms:38-41} declares it {@code ASKIP,FSET,NORM} in
     * YELLOW.
     *
     * <p>Populated by the program from {@code CCDA-TITLE01} of {@code app/cpy/COTTL01Y.cpy} at
     * {@code app/cbl/COBIL00C.cbl:323}. Display only. Width {@value #TITLE01_LENGTH}.
     */
    private String title01;

    /**
     * {@code CURDATEI PIC X(8)}, {@code app/cpy-bms/COBIL00.CPY:36}. The current date at
     * {@code (1,71)} beside the {@code 'Date:'} literal;
     * {@code app/bms/COBIL00.bms:47-51} declares it {@code ASKIP,FSET,NORM} in BLUE with
     * {@code INITIAL='mm/dd/yy'}.
     *
     * <p>Built by {@code POPULATE-HEADER-INFO} from {@code FUNCTION CURRENT-DATE} at
     * {@code app/cbl/COBIL00C.cbl:321} and moved in as {@code mm/dd/yy} at line 332 - note that
     * line 330 takes only the last two characters of the year, {@code WS-CURDATE-YEAR(3:2)}. Display
     * only. Width {@value #CUR_DATE_LENGTH}.
     */
    private String curDate;

    /**
     * {@code PGMNAMEI PIC X(8)}, {@code app/cpy-bms/COBIL00.CPY:42}. The program name at
     * {@code (2,7)} beside the {@code 'Prog:'} literal;
     * {@code app/bms/COBIL00.bms:57-60} declares it {@code ASKIP,FSET,NORM} in BLUE.
     *
     * <p>Populated by the program from {@code WS-PGMNAME} at {@code app/cbl/COBIL00C.cbl:326}, so its
     * value on a well-formed screen is {@value #PROGRAM_NAME}. Display only. Width
     * {@value #PGM_NAME_LENGTH}.
     */
    private String pgmName;

    /**
     * {@code TITLE02I PIC X(40)}, {@code app/cpy-bms/COBIL00.CPY:48}. The second title line at
     * {@code (2,21)}; {@code app/bms/COBIL00.bms:61-64} declares it {@code ASKIP,FSET,NORM} in
     * YELLOW.
     *
     * <p>Populated by the program from {@code CCDA-TITLE02} of {@code app/cpy/COTTL01Y.cpy} at
     * {@code app/cbl/COBIL00C.cbl:324}. Display only. Width {@value #TITLE02_LENGTH}.
     */
    private String title02;

    /**
     * {@code CURTIMEI PIC X(8)}, {@code app/cpy-bms/COBIL00.CPY:54}. The current time at
     * {@code (2,71)} beside the {@code 'Time:'} literal;
     * {@code app/bms/COBIL00.bms:70-74} declares it {@code ASKIP,FSET,NORM} in BLUE with
     * {@code INITIAL='hh:mm:ss'}.
     *
     * <p>Built by {@code POPULATE-HEADER-INFO} at {@code app/cbl/COBIL00C.cbl:334-338}. Display only.
     * Width {@value #CUR_TIME_LENGTH}.
     */
    private String curTime;

    /**
     * {@code ACTIDINI PIC X(11)}, {@code app/cpy-bms/COBIL00.CPY:60}. <strong>The account identifier
     * the user types</strong>, at {@code (6,21)} beside the {@code 'Enter Acct ID:'} literal.
     * {@code app/bms/COBIL00.bms:85-89} declares it {@code FSET,IC,NORM,UNPROT} in GREEN with
     * {@code HILIGHT=UNDERLINE} - {@code UNPROT} makes it writable and {@code IC} places the initial
     * cursor here, which is why it is one of only two members that carry a validation constraint.
     *
     * <p>This is the primary input of the screen. {@code app/cbl/COBIL00C.cbl:170-171} moves it into
     * both {@code ACCT-ID} and {@code XREF-ACCT-ID} to drive the account read and the card
     * cross-reference read. It may also arrive pre-filled rather than typed: on first entry, when
     * {@link #getTrnSelected()} is neither spaces nor {@code LOW-VALUES}, lines 118-119 copy that
     * value here and process the screen immediately.
     *
     * <p>Constrained with {@code @Size(max = }{@value #ACT_ID_IN_LENGTH}{@code )} and nothing more.
     * There is deliberately no presence constraint: an empty value is a documented outcome, not a
     * protocol error. Lines 159-164 answer it with {@code 'Acct ID can NOT be empty...'}, reposition
     * the cursor and re-send the screen, and the test at line 159 is
     * {@code = SPACES OR LOW-VALUES} - a test that only stays reachable while blank and absent values
     * arrive here unaltered.
     */
    @Size(max = ACT_ID_IN_LENGTH)
    private String actIdIn;

    /**
     * {@code CURBALI PIC X(14)}, {@code app/cpy-bms/COBIL00.CPY:66}. The account's current balance as
     * <strong>already-formatted text</strong>, at {@code (11,32)} after the
     * {@code 'Your current balance is: '} literal; {@code app/bms/COBIL00.bms:103-106} declares it
     * {@code ASKIP,FSET,NORM} in BLUE.
     *
     * <p><strong>This member is a {@code String} and must stay one.</strong> The program does not put
     * a number on the screen: {@code app/cbl/COBIL00C.cbl:193} moves {@code ACCT-CURR-BAL} into
     * {@code WS-CURR-BAL PIC +9999999999.99} (declared at line 56), and line 194 moves that
     * <em>edited</em> rendering here. The mask is what makes the field fourteen characters wide - one
     * sign, ten digits, a decimal point, two digits - and the sign is always present because the mask
     * begins with {@code +}. Parsing it back into a number is the service's concern, not this
     * carrier's, and holding it as a number here would lose the mask and with it the exact bytes the
     * screen displayed. Do not confuse the mask with {@code WS-TRAN-AMT PIC +99999999.99} at line 55,
     * which is twelve characters and belongs to the generated transaction rather than to this field.
     *
     * <p>Inbound this member is <strong>echo only</strong>. Exhaustive search of the program finds
     * {@code CURBALI} used solely as a destination - written at line 194 and space-filled by
     * {@code INITIALIZE-ALL-FIELDS} at line 564 - and never read. {@code BillPaymentService} ignores
     * whatever arrives here; it recomputes the balance from the account record. The member exists
     * because the contract is a 1:1 projection of all ten screen fields. Width
     * {@value #CUR_BAL_LENGTH}.
     */
    private String curBal;

    /**
     * {@code CONFIRMI PIC X(1)}, {@code app/cpy-bms/COBIL00.CPY:72}. <strong>The one-character
     * confirmation the user types</strong>, at {@code (15,60)} after the
     * {@code 'Do you want to pay your balance now. Please confirm: '} literal and before the
     * {@code '(Y/N)'} hint at {@code (15,63)}. {@code app/bms/COBIL00.bms:115-119} declares it
     * {@code FSET,NORM,UNPROT} in GREEN with {@code HILIGHT=UNDERLINE}, so it is the second and last
     * writable field and the second to carry a validation constraint.
     *
     * <p>{@code app/cbl/COBIL00C.cbl:173-191} evaluates it in strict order, and all five arms
     * matter: {@code 'Y'} or {@code 'y'} sets the pay flag and reads the account; {@code 'N'} or
     * {@code 'n'} clears the screen and suppresses further processing;
     * <strong>{@code SPACES} or {@code LOW-VALUES} is a valid path</strong> that reads the account so
     * the balance can be displayed - it is how a user asks to see what is owed before deciding - and
     * anything else answers {@code 'Invalid value. Valid values are (Y/N)...'} with the cursor
     * repositioned.
     *
     * <p>Constrained with {@code @Size(max = }{@value #CONFIRM_LENGTH}{@code )} and nothing more.
     * A presence constraint here would reject exactly the blank value that the third arm treats as
     * legitimate, deleting a whole path through the program. The comparison itself - including the
     * lower-case variants - belongs to {@code BillPaymentService}, which is why no constant for
     * {@code 'Y'} or {@code 'N'} is declared on this carrier and no predicate over this member is
     * offered.
     */
    @Size(max = CONFIRM_LENGTH)
    private String confirm;

    /**
     * {@code ERRMSGI PIC X(78)}, {@code app/cpy-bms/COBIL00.CPY:78}. The message line at
     * {@code (23,1)}; {@code app/bms/COBIL00.bms:127-130} declares it {@code ASKIP,BRT,FSET} in RED,
     * bright red being how this application signals a failed edit.
     *
     * <p>Inbound this member is <strong>echo only</strong>, and emphatically so: the very first thing
     * {@code MAIN-PARA} does is clear it, at {@code app/cbl/COBIL00C.cbl:104-105}, before any
     * decision is taken. It is written at line 293 and given a colour at line 526, and it is never
     * read. Whatever a client echoes back is discarded.
     *
     * <p>Width {@value #ERR_MSG_LENGTH}, and that number is two short of the message it carries:
     * {@code WS-MESSAGE} is {@code PIC X(80)} at line 39, so {@code MOVE WS-MESSAGE TO ERRMSGO} at
     * line 293 discards the two right-most bytes of every message. The screen field is the narrower
     * of the two and it governs. This is recorded rather than resolved - widening the field or the
     * message would change what the screen shows.
     */
    private String errMsg;

    // =================================================================================================
    // CONVERSATION STATE. CICS keeps none between keystrokes and neither does this application: the
    // communication area, the attention identifier and the enter-versus-re-enter context all travel
    // in the payload. There is no HttpSession, no @SessionAttributes, no server-side cache and no
    // static holder anywhere in this class.
    // =================================================================================================

    /**
     * The shared communication area, {@code 01 CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy},
     * modelled once for all seventeen online programs by {@link NavigationContext} and exactly
     * {@value NavigationContext#COMMAREA_LENGTH} bytes wide. It carries the from and to transaction
     * and program names, the signed-on user and user type, the program context, and the carried
     * customer, account and card identifiers.
     *
     * <p>{@code COBIL00C} receives it at {@code app/cbl/COBIL00C.cbl:111}
     * ({@code MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA}) and hands it straight back at
     * lines 146-149 on {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)}.
     *
     * <p><strong>{@code null} is meaningful here.</strong> It expresses the cold start that the
     * program detects as {@code EIBCALEN = 0} at line 107 - arriving with no communication area at
     * all - to which the program responds by moving {@code 'COSGN00C'} into
     * {@code CDEMO-TO-PROGRAM} at line 108 and returning to the sign-on screen at line 109. That
     * decision belongs to {@code BillPaymentController}; this carrier's only obligation is to be able
     * to represent the absence, which is why the member is nullable and carries no presence
     * constraint.
     *
     * <p>{@link NavigationContext} is referenced, never extended and never re-declared. Its sixteen
     * fields are not copied onto this class, and this program's own 58-byte extension is <em>not</em>
     * pushed into it - see the six members below.
     */
    private NavigationContext navigationContext;

    /**
     * The resolved attention identifier: which key the user pressed, as the token that stands in for
     * the CICS {@code EIBAID} byte. Five characters, matching {@code CCARD-AID PIC X(5)} of
     * {@code app/cpy/CVCRD01Y.cpy}.
     *
     * <p>{@code app/cbl/COBIL00C.cbl:125-142} evaluates {@code EIBAID} in a strict four-arm order
     * that this member makes reproducible without any server-side terminal state:
     * {@code DFHENTER} processes the screen, {@code DFHPF3} navigates back - to
     * {@code CDEMO-FROM-PROGRAM} where one is set and to {@code 'COMEN01C'} otherwise, per lines
     * 129-134 - {@code DFHPF4} clears the current screen, and {@code WHEN OTHER} raises the standard
     * invalid-key message. The mapset advertises exactly that subset at {@code (24,1)} with
     * {@code INITIAL='ENTER=Continue  F3=Back  F4=Clear'}.
     *
     * <p>Carried as a plain {@code String} on purpose. The controller reads the raw {@code EIBAID}
     * equivalent, resolves it through the shared key resolver and places the resulting token here; the
     * resolution therefore happens once, in one place, and this carrier stays free of any dependency
     * on the AID constant set. Its only repository-internal reference is {@link NavigationContext},
     * as with every other screen payload in this module.
     */
    private String aid;

    /**
     * The enter-versus-re-enter context: the wire-level projection of
     * {@code CDEMO-PGM-CONTEXT PIC 9(01)}, declared at {@code app/cpy/COCOM01Y.cpy:29} with
     * {@code 88 CDEMO-PGM-ENTER VALUE 0} and {@code 88 CDEMO-PGM-REENTER VALUE 1} at lines 30-31.
     * Use {@link NavigationContext#PGM_CONTEXT_ENTER} and
     * {@link NavigationContext#PGM_CONTEXT_REENTER} rather than the bare literals 0 and 1.
     *
     * <p>This is the single most consequential branch in the program.
     * {@code app/cbl/COBIL00C.cbl:112} tests {@code IF NOT CDEMO-PGM-REENTER}: on first entry it
     * flips the context to re-enter, blanks the whole output map with
     * {@code MOVE LOW-VALUES TO COBIL0AO} at line 114, positions the cursor and paints the screen; on
     * re-entry it instead receives the screen and dispatches on the attention identifier. Both paths
     * must be exercisable, so the context has to be an explicit part of the request rather than
     * something the server remembers.
     *
     * <p><strong>Authority.</strong> {@link NavigationContext#pgmContext()} remains the byte-level
     * authority for the {@value NavigationContext#COMMAREA_LENGTH}-byte parity image - it is the field
     * that gets written into the communication area and compared byte for byte. This member is the
     * wire-level projection the controller reads. {@code BillPaymentController} is responsible for
     * keeping the two consistent and must never let them diverge: the projection is a convenience for
     * the transport, not a second source of truth.
     *
     * <p>No predicate over this member is offered here. A test such as
     * {@code context == PGM_CONTEXT_REENTER} is a decision, and decisions belong to the controller and
     * the service where the tests that must cover them live.
     */
    private int pgmContext;

    // =================================================================================================
    // THE SIX CDEMO-CB00-INFO MEMBERS, flat on this class, from app/cbl/COBIL00C.cbl:64-72.
    //
    // Five of the six are declared in the source and never read. They are carried anyway: this is a
    // like-for-like migration, and dead declarations are part of what the source says. Exhaustive
    // search of the program finds exactly two non-declaration references to the whole group, both to
    // TRN-SELECTED, at lines 116 and 118.
    //
    // They are NOT added to NavigationContext, which must stay exactly 160 bytes for all seventeen
    // controllers, and they are NOT wrapped in a nested type, because the screen contract is these
    // two payload classes and nothing else.
    // =================================================================================================

    /**
     * {@code CDEMO-CB00-TRNID-FIRST PIC X(16)}, {@code app/cbl/COBIL00C.cbl:65}. The first
     * transaction identifier of a page, in the pagination idiom this application uses on its list
     * screens.
     *
     * <p><strong>Declared and never read.</strong> {@code COBIL00C} contains no reference to it
     * outside its own declaration. It is preserved rather than dropped, because removing a declaration
     * the source makes would be a change to the source's meaning. Width
     * {@value #TRN_ID_FIRST_LENGTH}.
     */
    private String trnIdFirst;

    /**
     * {@code CDEMO-CB00-TRNID-LAST PIC X(16)}, {@code app/cbl/COBIL00C.cbl:66}. The last transaction
     * identifier of a page.
     *
     * <p><strong>Declared and never read.</strong> Width {@value #TRN_ID_LAST_LENGTH}.
     */
    private String trnIdLast;

    /**
     * {@code CDEMO-CB00-PAGE-NUM PIC 9(08)}, {@code app/cbl/COBIL00C.cbl:67}. The current page
     * number.
     *
     * <p>An {@code int}, and correctly so: {@value #PAGE_NUM_DIGITS} digits with no {@code V} and no
     * implied decimal position. That is the one shape of COBOL numeric item that maps to a Java
     * integer - every scaled item in this migration is held as an exact fixed-point decimal instead,
     * and no binary floating-point type appears anywhere in the module.
     *
     * <p><strong>Declared and never read.</strong> Defaults to 0, the value a fresh
     * {@code PIC 9(08)} item without a {@code VALUE} clause holds once initialised.
     */
    private int pageNum;

    /**
     * {@code CDEMO-CB00-NEXT-PAGE-FLG PIC X(01) VALUE 'N'}, {@code app/cbl/COBIL00C.cbl:68}, with
     * {@code 88 NEXT-PAGE-YES VALUE 'Y'} and {@code 88 NEXT-PAGE-NO VALUE 'N'} at lines 69-70.
     * Whether a further page is available.
     *
     * <p>Initialised to {@value #NEXT_PAGE_NO} at the declaration, which is what the source's
     * {@code VALUE 'N'} clause means. This is the only member with a non-default initial state, and
     * the initialiser is on the field rather than in the constructor so the value survives binding: a
     * payload that omits the property keeps {@value #NEXT_PAGE_NO} rather than becoming {@code null}.
     *
     * <p><strong>Declared and never read.</strong> The two condition names are still projected as
     * {@link #nextPageYes()} and {@link #nextPageNo()}, since the source declares them. Width
     * {@value #NEXT_PAGE_FLG_LENGTH}.
     */
    private String nextPageFlg = NEXT_PAGE_NO;

    /**
     * {@code CDEMO-CB00-TRN-SEL-FLG PIC X(01)}, {@code app/cbl/COBIL00C.cbl:71}. A selection flag.
     *
     * <p><strong>Declared and never read.</strong> Note that the source declares no {@code 88}-level
     * condition over this field, unlike its neighbour, so no predicate is projected for it. Width
     * {@value #TRN_SEL_FLG_LENGTH}.
     */
    private String trnSelFlg;

    /**
     * {@code CDEMO-CB00-TRN-SELECTED PIC X(16)}, {@code app/cbl/COBIL00C.cbl:72}.
     * <strong>The only member of the extension the program actually reads</strong>, and the deep-link
     * into this screen.
     *
     * <p>On first entry - and only there -
     * {@code app/cbl/COBIL00C.cbl:116-117} tests
     * {@code IF CDEMO-CB00-TRN-SELECTED NOT = SPACES AND LOW-VALUES}, and where that holds, lines
     * 118-119 copy this value into {@code ACTIDINI} and line 120 processes the screen immediately, so
     * the balance is on display before the user has typed anything. Where it does not hold, the screen
     * is simply painted empty. That is the mechanism by which another transaction hands an account to
     * this one.
     *
     * <p>Both halves of the test must stay reachable, so this member is nullable and is never
     * defaulted or blank-normalised here; the comparison itself belongs to the service. Width
     * {@value #TRN_SELECTED_LENGTH}.
     */
    private String trnSelected;

    /**
     * Creates an empty request.
     *
     * <p>Every {@link String} member starts as {@code null} and {@link #getPageNum()} starts as 0.
     * The single exception is {@link #getNextPageFlg()}, which starts as {@value #NEXT_PAGE_NO} to
     * honour the {@code VALUE 'N'} clause the source attaches to that field alone.
     *
     * <p>{@code null} is not the same as spaces and neither is the same as {@code LOW-VALUES}. A
     * fresh instance therefore does not stand for a space-filled screen; it stands for a screen about
     * which nothing has been said yet. Callers that mean spaces are expected to say so, exactly as
     * {@code INITIALIZE-ALL-FIELDS} does at {@code app/cbl/COBIL00C.cbl:563-565}.
     *
     * <p>This constructor is what makes the type usable from a plain JUnit test with no Spring
     * context - construct, set the two or three members a case exercises, and pass it in - and it is
     * what lets Jackson bind an inbound body with no {@code @JsonCreator}.
     */
    public BillPaymentRequest() {
        // Deliberately empty. The one non-default initial state, nextPageFlg, is set at its
        // declaration so that it also survives deserialization of a payload that omits the property.
    }

    // =================================================================================================
    // Accessors for the ten payload members. Each stores and returns exactly what it was given: no
    // trimming, no padding to the declared width, no upper-casing, no empty-to-null coercion and no
    // validation. The declared widths are carried by the constants and the annotations, and every
    // decision over these values belongs to BillPaymentService.
    // =================================================================================================

    /**
     * Returns {@code TRNNAMEI}, the transaction identifier shown at {@code (1,7)}.
     *
     * @return the transaction identifier, at most {@value #TRN_NAME_LENGTH} characters, possibly
     *         {@code null}
     */
    public String getTrnName() {
        return trnName;
    }

    /**
     * Sets {@code TRNNAMEI}. Display-only on the screen; supplied by the header population step
     * rather than by the user.
     *
     * @param trnName the transaction identifier, normally {@value #TRANSACTION_ID}; {@code null} is
     *                accepted and preserved
     */
    public void setTrnName(String trnName) {
        this.trnName = trnName;
    }

    /**
     * Returns {@code TITLE01I}, the first title line shown at {@code (1,21)}.
     *
     * @return the first title line, at most {@value #TITLE01_LENGTH} characters, possibly
     *         {@code null}
     */
    public String getTitle01() {
        return title01;
    }

    /**
     * Sets {@code TITLE01I}.
     *
     * @param title01 the first title line; {@code null} is accepted and preserved
     */
    public void setTitle01(String title01) {
        this.title01 = title01;
    }

    /**
     * Returns {@code CURDATEI}, the current date shown at {@code (1,71)} as {@code mm/dd/yy}.
     *
     * @return the current date, at most {@value #CUR_DATE_LENGTH} characters, possibly {@code null}
     */
    public String getCurDate() {
        return curDate;
    }

    /**
     * Sets {@code CURDATEI}.
     *
     * @param curDate the current date in {@code mm/dd/yy} form; {@code null} is accepted and
     *                preserved
     */
    public void setCurDate(String curDate) {
        this.curDate = curDate;
    }

    /**
     * Returns {@code PGMNAMEI}, the program name shown at {@code (2,7)}.
     *
     * @return the program name, at most {@value #PGM_NAME_LENGTH} characters, possibly {@code null}
     */
    public String getPgmName() {
        return pgmName;
    }

    /**
     * Sets {@code PGMNAMEI}.
     *
     * @param pgmName the program name, normally {@value #PROGRAM_NAME}; {@code null} is accepted and
     *                preserved
     */
    public void setPgmName(String pgmName) {
        this.pgmName = pgmName;
    }

    /**
     * Returns {@code TITLE02I}, the second title line shown at {@code (2,21)}.
     *
     * @return the second title line, at most {@value #TITLE02_LENGTH} characters, possibly
     *         {@code null}
     */
    public String getTitle02() {
        return title02;
    }

    /**
     * Sets {@code TITLE02I}.
     *
     * @param title02 the second title line; {@code null} is accepted and preserved
     */
    public void setTitle02(String title02) {
        this.title02 = title02;
    }

    /**
     * Returns {@code CURTIMEI}, the current time shown at {@code (2,71)} as {@code hh:mm:ss}.
     *
     * @return the current time, at most {@value #CUR_TIME_LENGTH} characters, possibly {@code null}
     */
    public String getCurTime() {
        return curTime;
    }

    /**
     * Sets {@code CURTIMEI}.
     *
     * @param curTime the current time in {@code hh:mm:ss} form; {@code null} is accepted and
     *                preserved
     */
    public void setCurTime(String curTime) {
        this.curTime = curTime;
    }

    /**
     * Returns {@code ACTIDINI}, the account identifier the user typed - the primary input of this
     * screen.
     *
     * <p>Returned exactly as received. Blank and absent values are preserved as distinct states
     * because {@code app/cbl/COBIL00C.cbl:159} tests {@code = SPACES OR LOW-VALUES} and answers with
     * a message rather than rejecting the request.
     *
     * @return the account identifier, at most {@value #ACT_ID_IN_LENGTH} characters, possibly
     *         {@code null}, empty or blank
     */
    public String getActIdIn() {
        return actIdIn;
    }

    /**
     * Sets {@code ACTIDINI}.
     *
     * <p>Stores the value verbatim. Nothing is trimmed, padded or rejected here: the emptiness test
     * and the account lookup both belong to {@code BillPaymentService}.
     *
     * @param actIdIn the account identifier; {@code null}, empty and blank are all accepted and
     *                preserved as distinct states
     */
    public void setActIdIn(String actIdIn) {
        this.actIdIn = actIdIn;
    }

    /**
     * Returns {@code CURBALI}, the current balance as the already-edited
     * {@code +9999999999.99} text the screen displayed.
     *
     * <p>Inbound this is echo only and {@code BillPaymentService} ignores it. The value is text, not
     * a number: it retains the mask's leading sign and its fixed
     * {@value #CUR_BAL_LENGTH}-character shape.
     *
     * @return the edited balance text, at most {@value #CUR_BAL_LENGTH} characters, possibly
     *         {@code null}
     */
    public String getCurBal() {
        return curBal;
    }

    /**
     * Sets {@code CURBALI}.
     *
     * @param curBal the edited balance text as rendered through
     *               {@code WS-CURR-BAL PIC +9999999999.99}; {@code null} is accepted and preserved
     */
    public void setCurBal(String curBal) {
        this.curBal = curBal;
    }

    /**
     * Returns {@code CONFIRMI}, the one-character confirmation the user typed.
     *
     * <p>Returned exactly as received, in the case it was typed in. The
     * {@code 'Y'}/{@code 'y'}/{@code 'N'}/{@code 'n'} evaluation, and the blank arm that legitimately
     * asks to see the balance, all belong to {@code BillPaymentService}; folding the case here would
     * merge arms the source keeps separate.
     *
     * @return the confirmation character, at most {@value #CONFIRM_LENGTH} character, possibly
     *         {@code null}, empty or blank
     */
    public String getConfirm() {
        return confirm;
    }

    /**
     * Sets {@code CONFIRMI}.
     *
     * @param confirm the confirmation character; {@code null}, empty and blank are all accepted and
     *                preserved, since a blank confirmation is a valid request to display the balance
     */
    public void setConfirm(String confirm) {
        this.confirm = confirm;
    }

    /**
     * Returns {@code ERRMSGI}, the message line shown at {@code (23,1)}.
     *
     * <p>Inbound this is echo only. The program clears the message unconditionally at
     * {@code app/cbl/COBIL00C.cbl:104-105} before taking any decision, so nothing a client sends here
     * can influence the outcome.
     *
     * @return the message text, at most {@value #ERR_MSG_LENGTH} characters, possibly {@code null}
     */
    public String getErrMsg() {
        return errMsg;
    }

    /**
     * Sets {@code ERRMSGI}.
     *
     * @param errMsg the message text; {@code null} is accepted and preserved
     */
    public void setErrMsg(String errMsg) {
        this.errMsg = errMsg;
    }

    // =================================================================================================
    // Accessors for the conversation state.
    // =================================================================================================

    /**
     * Returns the {@value NavigationContext#COMMAREA_LENGTH}-byte communication area this request
     * arrived with.
     *
     * @return the communication area, or {@code null} where the request carries none - the cold start
     *         the program detects as {@code EIBCALEN = 0} at {@code app/cbl/COBIL00C.cbl:107}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    /**
     * Sets the communication area.
     *
     * @param navigationContext the communication area; {@code null} is accepted and preserved,
     *                          because absence is the state that drives the return to the sign-on
     *                          screen
     */
    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = navigationContext;
    }

    /**
     * Returns the resolved attention identifier - which key the user pressed.
     *
     * <p>Dispatched on by {@code app/cbl/COBIL00C.cbl:125-142} in the order enter, F3, F4, then
     * everything else.
     *
     * @return the five-character attention-identifier token, possibly {@code null} where no key
     *         indication accompanied the request
     */
    public String getAid() {
        return aid;
    }

    /**
     * Sets the resolved attention identifier.
     *
     * @param aid the five-character token the controller obtained by resolving the raw key
     *            indication; {@code null} is accepted and preserved
     */
    public void setAid(String aid) {
        this.aid = aid;
    }

    /**
     * Returns the enter-versus-re-enter context: {@value NavigationContext#PGM_CONTEXT_ENTER} on first
     * entry, {@value NavigationContext#PGM_CONTEXT_REENTER} on re-entry.
     *
     * <p>Compare against {@link NavigationContext#PGM_CONTEXT_ENTER} and
     * {@link NavigationContext#PGM_CONTEXT_REENTER} rather than against bare literals. The comparison
     * itself is the caller's, matching {@code IF NOT CDEMO-PGM-REENTER} at
     * {@code app/cbl/COBIL00C.cbl:112}.
     *
     * @return the program context, 0 or 1 in normal use and any other value only where a client sent
     *         one
     */
    public int getPgmContext() {
        return pgmContext;
    }

    /**
     * Sets the enter-versus-re-enter context.
     *
     * <p>Accepts the value as given, including values outside 0 and 1. {@code CDEMO-PGM-CONTEXT} is a
     * {@code PIC 9(01)} item over which the source declares two condition names rather than an
     * enumeration, so a third value is possible in the COBOL and remains possible here; the two
     * condition names are simply both false for it, exactly as the source behaves.
     *
     * @param pgmContext the program context, normally
     *                   {@value NavigationContext#PGM_CONTEXT_ENTER} or
     *                   {@value NavigationContext#PGM_CONTEXT_REENTER}
     */
    public void setPgmContext(int pgmContext) {
        this.pgmContext = pgmContext;
    }

    // =================================================================================================
    // Accessors for the six CDEMO-CB00-INFO members. Five of the six are never read by the program;
    // they are carried, and accessible, because the source declares them.
    // =================================================================================================

    /**
     * Returns {@code CDEMO-CB00-TRNID-FIRST}. Declared by the source and never read by it.
     *
     * @return the first transaction identifier of a page, at most {@value #TRN_ID_FIRST_LENGTH}
     *         characters, possibly {@code null}
     */
    public String getTrnIdFirst() {
        return trnIdFirst;
    }

    /**
     * Sets {@code CDEMO-CB00-TRNID-FIRST}.
     *
     * @param trnIdFirst the first transaction identifier of a page; {@code null} is accepted and
     *                   preserved
     */
    public void setTrnIdFirst(String trnIdFirst) {
        this.trnIdFirst = trnIdFirst;
    }

    /**
     * Returns {@code CDEMO-CB00-TRNID-LAST}. Declared by the source and never read by it.
     *
     * @return the last transaction identifier of a page, at most {@value #TRN_ID_LAST_LENGTH}
     *         characters, possibly {@code null}
     */
    public String getTrnIdLast() {
        return trnIdLast;
    }

    /**
     * Sets {@code CDEMO-CB00-TRNID-LAST}.
     *
     * @param trnIdLast the last transaction identifier of a page; {@code null} is accepted and
     *                  preserved
     */
    public void setTrnIdLast(String trnIdLast) {
        this.trnIdLast = trnIdLast;
    }

    /**
     * Returns {@code CDEMO-CB00-PAGE-NUM}. Declared by the source and never read by it.
     *
     * @return the page number, an integer of at most {@value #PAGE_NUM_DIGITS} digits, 0 where unset
     */
    public int getPageNum() {
        return pageNum;
    }

    /**
     * Sets {@code CDEMO-CB00-PAGE-NUM}.
     *
     * @param pageNum the page number; stored as given, since a {@code PIC 9(08)} item carries no
     *                range check of its own beyond its width
     */
    public void setPageNum(int pageNum) {
        this.pageNum = pageNum;
    }

    /**
     * Returns {@code CDEMO-CB00-NEXT-PAGE-FLG}. Declared by the source and never read by it.
     *
     * @return the next-page flag, at most {@value #NEXT_PAGE_FLG_LENGTH} character, initially
     *         {@value #NEXT_PAGE_NO}
     */
    public String getNextPageFlg() {
        return nextPageFlg;
    }

    /**
     * Sets {@code CDEMO-CB00-NEXT-PAGE-FLG}.
     *
     * @param nextPageFlg the next-page flag, normally {@value #NEXT_PAGE_YES} or
     *                    {@value #NEXT_PAGE_NO}; {@code null} and any other value are accepted and
     *                    preserved, in which case both condition names are false
     */
    public void setNextPageFlg(String nextPageFlg) {
        this.nextPageFlg = nextPageFlg;
    }

    /**
     * Whether {@code 88 NEXT-PAGE-YES VALUE 'Y'} holds - that is, whether
     * {@link #getNextPageFlg()} is exactly {@value #NEXT_PAGE_YES}.
     *
     * <p>A projection of the condition name the source declares at
     * {@code app/cbl/COBIL00C.cbl:69}, evaluated as a single comparison against the literal so that
     * this carrier gains no decision point of its own. A {@code null} flag makes this false, which is
     * the same outcome the COBOL produces for any value other than {@code 'Y'}.
     *
     * <p><strong>Deliberately not named {@code isNextPageYes}.</strong> A no-argument
     * {@code boolean} method whose name begins with {@code is} is a JavaBean read accessor, and the
     * JSON mapper would auto-detect it as a property and publish {@code nextPageYes} on the wire.
     * That would break the contract twice over: the payload would carry a field tracing to no
     * {@code DFHMDF} definition, and a client echoing the payload back - which is exactly how this
     * pseudo-conversational protocol works - would be rejected, because a read-only property has no
     * matching setter to bind to. Dropping the prefix keeps the published property set to the
     * nineteen real members without needing a mapper annotation to suppress anything. Verified: the
     * serialized body contains {@code nextPageFlg} and no {@code nextPageYes}.
     *
     * @return {@code true} where the flag is {@value #NEXT_PAGE_YES}
     */
    public boolean nextPageYes() {
        return NEXT_PAGE_YES.equals(nextPageFlg);
    }

    /**
     * Whether {@code 88 NEXT-PAGE-NO VALUE 'N'} holds - that is, whether
     * {@link #getNextPageFlg()} is exactly {@value #NEXT_PAGE_NO}.
     *
     * <p>A projection of the condition name the source declares at
     * {@code app/cbl/COBIL00C.cbl:70}. This is <strong>not</strong> the negation of
     * {@link #nextPageYes()}: the pair is not exhaustive, so a flag holding a space, a
     * {@code LOW-VALUES} byte or {@code null} makes both false, exactly as two independent
     * {@code 88}-level tests behave in COBOL.
     *
     * <p>Named without the {@code is} prefix for the reason given on {@link #nextPageYes()}.
     *
     * @return {@code true} where the flag is {@value #NEXT_PAGE_NO}
     */
    public boolean nextPageNo() {
        return NEXT_PAGE_NO.equals(nextPageFlg);
    }

    /**
     * Returns {@code CDEMO-CB00-TRN-SEL-FLG}. Declared by the source and never read by it, and
     * carrying no condition name, so no predicate is offered over it.
     *
     * @return the selection flag, at most {@value #TRN_SEL_FLG_LENGTH} character, possibly
     *         {@code null}
     */
    public String getTrnSelFlg() {
        return trnSelFlg;
    }

    /**
     * Sets {@code CDEMO-CB00-TRN-SEL-FLG}.
     *
     * @param trnSelFlg the selection flag; {@code null} is accepted and preserved
     */
    public void setTrnSelFlg(String trnSelFlg) {
        this.trnSelFlg = trnSelFlg;
    }

    /**
     * Returns {@code CDEMO-CB00-TRN-SELECTED}, the deep-link value - the one member of the extension
     * the program reads.
     *
     * <p>Where this is neither spaces nor {@code LOW-VALUES}, first entry copies it into
     * {@link #getActIdIn()} and processes the screen straight away
     * ({@code app/cbl/COBIL00C.cbl:116-120}). {@code BillPaymentService} performs that test, so the
     * value is returned exactly as received - blank and absent stay distinguishable.
     *
     * @return the pre-selected account identifier, at most {@value #TRN_SELECTED_LENGTH} characters,
     *         possibly {@code null}, empty or blank
     */
    public String getTrnSelected() {
        return trnSelected;
    }

    /**
     * Sets {@code CDEMO-CB00-TRN-SELECTED}.
     *
     * @param trnSelected the pre-selected account identifier; {@code null}, empty and blank are all
     *                    accepted and preserved as distinct states
     */
    public void setTrnSelected(String trnSelected) {
        this.trnSelected = trnSelected;
    }

    /**
     * Returns a diagnostic rendering of every member, for test failure messages and log lines.
     *
     * <p>Nothing is masked. {@link #getActIdIn()} appears in the clear because the 3270 screen shows
     * it in the clear at {@code (6,21)} under {@code ATTRB=(FSET,IC,NORM,UNPROT)} with no
     * {@code DRK} attribute, and this migration neither weakens nor strengthens the posture it
     * inherited. This screen has no password field, so there is nothing on it that the source treats
     * as secret.
     *
     * <p>Built by straight concatenation, which keeps the method free of any decision point. The
     * output is a diagnostic aid and not a wire format: the JSON body is produced by the module's
     * shared object mapper configuration, and the fixed-width parity image is produced by the record
     * codec. Neither derives from this string.
     *
     * @return a single-line rendering naming every member, never {@code null}
     */
    @Override
    public String toString() {
        return "BillPaymentRequest[trnName=" + trnName
                + ", title01=" + title01
                + ", curDate=" + curDate
                + ", pgmName=" + pgmName
                + ", title02=" + title02
                + ", curTime=" + curTime
                + ", actIdIn=" + actIdIn
                + ", curBal=" + curBal
                + ", confirm=" + confirm
                + ", errMsg=" + errMsg
                + ", navigationContext=" + navigationContext
                + ", aid=" + aid
                + ", pgmContext=" + pgmContext
                + ", trnIdFirst=" + trnIdFirst
                + ", trnIdLast=" + trnIdLast
                + ", pageNum=" + pageNum
                + ", nextPageFlg=" + nextPageFlg
                + ", trnSelFlg=" + trnSelFlg
                + ", trnSelected=" + trnSelected
                + "]";
    }
}
