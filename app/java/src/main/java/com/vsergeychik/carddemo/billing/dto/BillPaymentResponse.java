package com.vsergeychik.carddemo.billing.dto;

import com.vsergeychik.carddemo.common.NavigationContext;

/**
 * The outbound payload of the CardDemo bill-payment screen: a field-for-field projection of
 * {@code 01 COBIL0AO REDEFINES COBIL0AI}, the output view of the {@code COBIL00} symbolic map
 * declared in {@code app/cpy-bms/COBIL00.CPY}.
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
 *   <tr><td>Screen size</td><td>{@code SIZE=(24,80)}</td>
 *       <td>{@code app/bms/COBIL00.bms:28}</td></tr>
 *   <tr><td>Program</td><td>{@value #PROGRAM_NAME}</td>
 *       <td>{@code app/csd/CARDDEMO.CSD:196}, {@code app/cbl/COBIL00C.cbl:37}</td></tr>
 *   <tr><td>Transaction</td><td>{@value #TRANSACTION_ID}</td>
 *       <td>{@code app/csd/CARDDEMO.CSD:337-338}, {@code app/cbl/COBIL00C.cbl:38}</td></tr>
 *   <tr><td>REST resource</td><td>{@code POST /api/billpay}</td><td>this migration</td></tr>
 * </table>
 *
 * <p>The CSD binds transaction to program directly: {@code DEFINE TRANSACTION(CB00) GROUP(CARDDEMO)}
 * followed by {@code PROGRAM(COBIL00C) TWASIZE(0) PROFILE(DFHCICST) STATUS(ENABLED)}. This payload
 * is what {@code EXEC CICS SEND MAP('COBIL0A') MAPSET('COBIL00') FROM(COBIL0AO) ERASE CURSOR} at
 * {@code app/cbl/COBIL00C.cbl:295-301} now stands in for.
 *
 * <p>The program's own header states its purpose, transcribed here verbatim from
 * {@code app/cbl/COBIL00C.cbl:5-6} - including the source's own spelling of "tractionsaction", which
 * is left exactly as written because this migration records source facts rather than correcting
 * them:
 *
 * <pre>
 * * Function    : Bill Payment - Pay account balance in full and a
 * *               tractionsaction for the online bill payment.
 * </pre>
 *
 * <h2>The ten payload members, and why there are exactly ten</h2>
 *
 * A BMS symbolic map is a group item whose members repeat in a fixed pattern. {@code 01 COBIL0AO}
 * opens with a {@value #TIOAPFX_PREFIX_LENGTH}-byte unnamed {@code FILLER} - the {@code TIOAPFX=YES}
 * prefix requested at {@code app/bms/COBIL00.bms:24} - and then, for each screen field, declares a
 * {@value #FIELD_PROLOGUE_LENGTH}-byte prologue followed by the data item:
 *
 * <pre>
 *  02  FILLER PICTURE X(3).                reserved                               3 bytes
 *  02  ACTIDINC    PICTURE X.              the colour attribute                   1 byte
 *  02  ACTIDINP    PICTURE X.              the programmed-symbol attribute        1 byte
 *  02  ACTIDINH    PICTURE X.              the highlight attribute                1 byte
 *  02  ACTIDINV    PICTURE X.              the validation attribute               1 byte
 *  02  ACTIDINO  PIC X(11).                THE DATA - this is the payload field
 * </pre>
 *
 * <p><strong>Only the {@code xxxO} items are payload members.</strong> The {@code xxxC},
 * {@code xxxP}, {@code xxxH} and {@code xxxV} items are field-attribute <em>metadata</em>; they are
 * not carried as payload members and no member of this class is named after one. Consequently there
 * is no {@code errMsgC}, no {@code actIdInH} and no {@code curBalV} here. The one attribute the
 * program actually writes at run time is carried instead by the purposefully named
 * {@link #getMessageHighlight()}, described below.
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
 *   <tr><td>1</td><td>{@code TRNNAMEO PIC X(4)} (L86)</td><td>{@link #getTrnName()}</td>
 *       <td>{@value #TRN_NAME_LENGTH}</td>
 *       <td>L34 {@code ASKIP,FSET,NORM} BLUE {@code (1,7)}</td></tr>
 *   <tr><td>2</td><td>{@code TITLE01O PIC X(40)} (L92)</td><td>{@link #getTitle01()}</td>
 *       <td>{@value #TITLE01_LENGTH}</td>
 *       <td>L38 {@code ASKIP,FSET,NORM} YELLOW {@code (1,21)}</td></tr>
 *   <tr><td>3</td><td>{@code CURDATEO PIC X(8)} (L98)</td><td>{@link #getCurDate()}</td>
 *       <td>{@value #CUR_DATE_LENGTH}</td>
 *       <td>L47 {@code ASKIP,FSET,NORM} BLUE {@code (1,71)} {@code INITIAL='mm/dd/yy'}</td></tr>
 *   <tr><td>4</td><td>{@code PGMNAMEO PIC X(8)} (L104)</td><td>{@link #getPgmName()}</td>
 *       <td>{@value #PGM_NAME_LENGTH}</td>
 *       <td>L57 {@code ASKIP,FSET,NORM} BLUE {@code (2,7)}</td></tr>
 *   <tr><td>5</td><td>{@code TITLE02O PIC X(40)} (L110)</td><td>{@link #getTitle02()}</td>
 *       <td>{@value #TITLE02_LENGTH}</td>
 *       <td>L61 {@code ASKIP,FSET,NORM} YELLOW {@code (2,21)}</td></tr>
 *   <tr><td>6</td><td>{@code CURTIMEO PIC X(8)} (L116)</td><td>{@link #getCurTime()}</td>
 *       <td>{@value #CUR_TIME_LENGTH}</td>
 *       <td>L70 {@code ASKIP,FSET,NORM} BLUE {@code (2,71)} {@code INITIAL='hh:mm:ss'}</td></tr>
 *   <tr><td>7</td><td>{@code ACTIDINO PIC X(11)} (L122)</td><td>{@link #getActIdIn()}</td>
 *       <td>{@value #ACT_ID_IN_LENGTH}</td>
 *       <td>L85 <strong>{@code FSET,IC,NORM,UNPROT}</strong> GREEN {@code (6,21)}
 *           {@code HILIGHT=UNDERLINE}</td></tr>
 *   <tr><td>8</td><td>{@code CURBALO PIC X(14)} (L128)</td><td>{@link #getCurBal()}</td>
 *       <td>{@value #CUR_BAL_LENGTH}</td>
 *       <td>L103 {@code ASKIP,FSET,NORM} BLUE {@code (11,32)}</td></tr>
 *   <tr><td>9</td><td>{@code CONFIRMO PIC X(1)} (L134)</td><td>{@link #getConfirm()}</td>
 *       <td>{@value #CONFIRM_LENGTH}</td>
 *       <td>L115 <strong>{@code FSET,NORM,UNPROT}</strong> GREEN {@code (15,60)}
 *           {@code HILIGHT=UNDERLINE}</td></tr>
 *   <tr><td>10</td><td>{@code ERRMSGO PIC X(78)} (L140)</td><td>{@link #getErrMsg()}</td>
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
 * view occupies the identical byte span. It works because both views have the same
 * {@value #FIELD_PROLOGUE_LENGTH}-byte per-field prologue: the input side spends it on
 * {@code xxxL} (2 bytes, {@code COMP PIC S9(4)}), {@code xxxF} (1 byte) and a 4-byte {@code FILLER},
 * while the output side spends it on a 3-byte {@code FILLER} and the four attribute items. The data
 * widths are unchanged, which is why {@code BillPaymentRequest} carries these same ten member names
 * at these same ten widths. Any divergence between the two is a defect in one of them.
 *
 * <p>{@code COBIL00C} writes some fields through the input view and others through the output view -
 * {@code ACTIDINI} at lines 118-119, {@code CURBALI} at 194 and {@code CONFIRMI} at 563-565 through
 * {@code COBIL0AI}; {@code TITLE01O}, {@code TITLE02O}, {@code TRNNAMEO}, {@code PGMNAMEO},
 * {@code CURDATEO} and {@code CURTIMEO} at 323-338 and {@code ERRMSGO} at 293 through
 * {@code COBIL0AO}. That is legal precisely because the two views share one span, and it is why both
 * payload types carry all ten fields. The fields are <strong>not</strong> split by which view the
 * COBOL happened to touch them through: {@code actIdIn}, {@code curBal} and {@code confirm} are
 * written through the input view and are nonetheless genuine response members, because the bytes the
 * program sends are the bytes it wrote.
 *
 * <h2>Presentation state that is not a screen field</h2>
 *
 * Two members carry observable presentation behaviour that has no {@code xxxO} item of its own,
 * because in CICS it is expressed through the metadata items rather than through the data:
 *
 * <ul>
 *   <li>{@link #getCursorField()} collapses the <strong>seventeen</strong> {@code MOVE -1 TO xxxL}
 *       cursor-positioning statements into a single indicator. See {@link CursorField}.</li>
 *   <li>{@link #getMessageHighlight()} carries the one field-attribute override the program
 *       performs, {@code MOVE DFHGREEN TO ERRMSGC OF COBIL0AO} at
 *       {@code app/cbl/COBIL00C.cbl:526}.</li>
 * </ul>
 *
 * <p>Both are deliberately named for what they mean rather than after the COBOL metadata items they
 * derive from, so that no member of this class can be mistaken for a projection of an {@code xxxL},
 * {@code xxxF}, {@code xxxA}, {@code xxxC}, {@code xxxP}, {@code xxxH} or {@code xxxV} item.
 *
 * <h2>Statelessness: the conversation travels in this payload</h2>
 *
 * CICS is pseudo-conversational. {@code COBIL00C} runs to completion for every keystroke and
 * survives only through what it hands back on {@code EXEC CICS RETURN TRANSID(WS-TRANID)
 * COMMAREA(CARDDEMO-COMMAREA)} at lines 146-149. This migration preserves that shape exactly, so
 * the conversation travels in this payload: {@link #getNavigationContext()} carries the
 * communication area, {@link #getNextProgram()}, {@link #getNextMapset()} and {@link #getNextMap()}
 * carry what {@code EXEC CICS XCTL} used to do, and the enter-versus-re-enter context travels
 * <em>inside</em> the communication area as {@code CDEMO-PGM-CONTEXT}, where the copybook declares
 * it. There is deliberately no second copy of that context on this class.
 *
 * <p>Nothing here is server-side state. This type is deliberately free of {@code HttpSession},
 * {@code @SessionAttributes}, any server-side cache and any static holder - a static holder would be
 * a session by another name and would break both request isolation and test determinism. Equally
 * there is no HTTP redirect, no {@code Location} header helper and no server-side forward: the
 * client reads {@link #getNextProgram()} and issues the follow-up call itself.
 *
 * <h2>Why this is a plain mutable class with hand-written accessors</h2>
 *
 * A {@code record} was considered and rejected. This type carries twenty-two members, and the unit
 * tests plus the twenty declarative parity cases for {@code COBIL00C} construct
 * <em>partially populated</em> instances constantly - a twenty-two-component canonical constructor
 * makes that painful and brittle, while a no-argument constructor plus setters makes it a single
 * line per field. The controller also fills this object <em>incrementally</em>, mirroring the
 * program's own sequence: {@code POPULATE-HEADER-INFO} writes the six header fields at lines
 * 323-338, then line 293 writes the message, then the cursor and highlight are applied. Setter-based
 * mutation matches that directly, where an immutable record would have to be rebuilt at each step.
 * Jackson also binds a no-argument bean with no {@code @JsonCreator} ceremony. The accessors are
 * written out by hand rather than generated so that each member's correspondence to its
 * symbolic-map item stays visible during review; no accessor-generating and no bean-mapping library
 * is used anywhere in this module.
 *
 * <p>Instance mutability is intended. <strong>Static</strong> mutable state is not: every static
 * member of this class is {@code final} and is either an {@code int} or a {@code String}, so there
 * is no static array, no static collection and no static non-final field. COBOL
 * {@code WORKING-STORAGE} never becomes a static Java field. The nested {@link CursorField} is an
 * {@code enum} and is therefore inherently immutable.
 *
 * <h2>This class contains no decision logic, by design</h2>
 *
 * Every member is stored and returned as given. Nothing is trimmed, padded, defaulted at write time,
 * upper-cased or coerced from empty to {@code null}, no setter validates, no getter derives a value
 * and no field is lazily initialised. That is a behavioural requirement rather than a stylistic one,
 * for two reasons.
 *
 * <p>First, distinctness. {@code MOVE LOW-VALUES TO COBIL0AO} at line 114 and the {@code MOVE
 * SPACES} of {@code INITIALIZE-ALL-FIELDS} at lines 563-565 leave a field in two <em>different</em>
 * observable states, and the program tests for both - {@code WHEN ACTIDINI = SPACES OR LOW-VALUES}
 * at line 159, and {@code WHEN SPACES} and {@code WHEN LOW-VALUES} as separate arms at lines
 * 182-183. On the wire those states arrive as JSON {@code null}, as an empty string and as an
 * all-blank string, and all three must survive a round trip unaltered for those states to stay
 * distinguishable. Normalising them here would silently merge cases the COBOL distinguishes, which
 * is why every one of the ten map members is independently nullable and why the module's shared
 * object-mapper configuration refuses trimming and empty-string-to-{@code null} coercion.
 *
 * <p>Second, the decision logic belongs one layer in. Which program is next, which field the cursor
 * parks on and whether the message is highlighted are all decided by {@code BillPaymentController}
 * and {@code BillPaymentService}, where the parity tests reach them with no HTTP layer in the path.
 * Keeping this carrier free of conditional logic also keeps it free of branches, which matters
 * concretely: coverage is gated on the branch counter at package level as well as at bundle level,
 * so a branch added to a data carrier becomes a branch its package must then prove it exercised.
 *
 * <p>{@code equals} and {@code hashCode} are omitted for that same reason. Any correct Java
 * {@code equals} needs a type test, and a type test is a branch; the parity differ compares field by
 * field rather than by whole-object equality, so neither method is needed. {@link #toString()} is
 * provided because straight concatenation costs no branch.
 *
 * <h2>No inbound constraint is declared, deliberately</h2>
 *
 * Unlike {@code BillPaymentRequest}, this type declares no Bean Validation constraint on any
 * member. A response is produced by this application rather than accepted from a client, so there is
 * no untrusted boundary here to enforce a width at; the widths are instead published as the
 * {@code static final int} constants below and are documented on every accessor, which is what makes
 * them auditable against the copybook. Nor does this class carry any Jackson annotation: the field
 * names are the wire names precisely so that each one traces 1:1 to its {@code xxxO} item, and the
 * module's shared object-mapper configuration - not a per-class annotation and not a local
 * {@code ObjectMapper} - owns naming, trimming and inclusion. In particular null members are
 * <em>not</em> excluded from the serialized body, because a null member is a meaningful
 * {@code LOW-VALUES} state and must appear on the wire.
 *
 * <h2>Source facts recorded rather than corrected</h2>
 *
 * <ul>
 *   <li>{@code app/bms/COBIL00.bms:1-2} carries the header comment
 *       {@code *    CardDemo - Main Menu Screen}, although {@code COBIL00} is the bill-payment
 *       screen and the main menu is {@code COMEN01}. The comment is wrong in the source and is left
 *       exactly as it stands; it is recorded here so a reader comparing the two files is not
 *       misled.</li>
 *   <li>{@code app/cpy-bms/COBIL00.CPY:61} spells the length item of the {@code CURBAL} field
 *       {@code CURBALL}, with a doubled {@code L}. It reads like a typing slip but is in fact the
 *       {@code xxxL} suffix applied to a field name that already ends in {@code L}. Either way it is
 *       carried as the source spells it, and it changes nothing here because length items are
 *       metadata and are not payload members.</li>
 *   <li>The cursor-positioning statement count is <strong>seventeen</strong>, not fifteen: fifteen
 *       write {@code ACTIDINL} and two write {@code CONFIRML}. Every line number is listed on
 *       {@link CursorField}. This is recorded rather than quietly reconciled because a folder-level
 *       specification for this screen states fifteen, which is the {@code ACTIDINL} subtotal rather
 *       than the total.</li>
 *   <li>{@code WS-MESSAGE} is {@code PIC X(80)} at {@code app/cbl/COBIL00C.cbl:39} while
 *       {@code ERRMSGO} is {@code PIC X(78)}, so {@code MOVE WS-MESSAGE TO ERRMSGO} at line 293
 *       truncates two bytes from the right. {@link #ERR_MSG_LENGTH} therefore stays at
 *       {@value #ERR_MSG_LENGTH} and is not widened to match the source of the move; see
 *       {@link #WS_MESSAGE_LENGTH}.</li>
 * </ul>
 *
 * <h2>Rules</h2>
 *
 * No user-specified rules were provided for this project: {@code review_rules} returns the single
 * line "No user rules provided.", and that is the whole document. Their absence is not treated as
 * licence to lower the bar - the enterprise-standard practices recorded in the migration plan govern
 * instead, and the ones that bear on this file are the reasons given throughout this comment.
 *
 * @see BillPaymentRequest
 * @see NavigationContext
 */
public class BillPaymentResponse {

    // =================================================================================================
    // Identity of the screen this payload projects. Declared as constants rather than repeated as
    // literals so that the values a client reads back in trnName, pgmName, nextMapset and nextMap have
    // exactly one definition in this module.
    // =================================================================================================

    /**
     * The mapset name, {@code COBIL00}, as declared by {@code DEFINE MAPSET(COBIL00) GROUP(CARDDEMO)}
     * at {@code app/csd/CARDDEMO.CSD:114} and by {@code COBIL00 DFHMSD} at
     * {@code app/bms/COBIL00.bms:19}. Also the value {@code COBIL00C} names in
     * {@code MAPSET('COBIL00')} at lines 297 and 310, and therefore the value of
     * {@link #getNextMapset()} whenever this screen re-displays itself.
     */
    public static final String MAPSET_NAME = "COBIL00";

    /**
     * The map name, {@code COBIL0A}, as declared by {@code COBIL0A DFHMDI COLUMN=1, LINE=1,
     * SIZE=(24,80)} at {@code app/bms/COBIL00.bms:26-28} and named in {@code MAP('COBIL0A')} at
     * {@code app/cbl/COBIL00C.cbl:296} and {@code 309}. The value of {@link #getNextMap()} whenever
     * this screen re-displays itself.
     */
    public static final String MAP_NAME = "COBIL0A";

    /**
     * The program name, {@code COBIL00C}, as declared by {@code DEFINE PROGRAM(COBIL00C)
     * GROUP(CARDDEMO)} at {@code app/csd/CARDDEMO.CSD:196} and held in {@code WS-PGMNAME} at
     * {@code app/cbl/COBIL00C.cbl:37}. {@code POPULATE-HEADER-INFO} moves it to {@code PGMNAMEO} at
     * line 326, so it is the expected value of {@link #getPgmName()}.
     */
    public static final String PROGRAM_NAME = "COBIL00C";

    /**
     * The transaction identifier, {@code CB00}, as declared by {@code DEFINE TRANSACTION(CB00)
     * GROUP(CARDDEMO)} with {@code PROGRAM(COBIL00C)} at {@code app/csd/CARDDEMO.CSD:337-338} and
     * held in {@code WS-TRANID} at {@code app/cbl/COBIL00C.cbl:38}. {@code POPULATE-HEADER-INFO}
     * moves it to {@code TRNNAMEO} at line 325, so it is the expected value of
     * {@link #getTrnName()}. {@code RETURN-TO-PREV-SCREEN} also moves it to
     * {@code CDEMO-FROM-TRANID} at line 278 before transferring control.
     */
    public static final String TRANSACTION_ID = "CB00";

    /**
     * The sign-on program, {@code COSGN00C}. Reachable as {@link #getNextProgram()} on two paths:
     * the cold start at {@code app/cbl/COBIL00C.cbl:107-109}, where {@code EIBCALEN = 0} means no
     * communication area was passed at all, and the {@code RETURN-TO-PREV-SCREEN} fallback at lines
     * 275-277, where {@code CDEMO-TO-PROGRAM} is {@code LOW-VALUES OR SPACES}.
     */
    public static final String SIGN_ON_PROGRAM = "COSGN00C";

    /**
     * The main menu program, {@code COMEN01C}. Reachable as {@link #getNextProgram()} on the
     * {@code DFHPF3} path at {@code app/cbl/COBIL00C.cbl:129-130}, where the screen was reached
     * without a caller because {@code CDEMO-FROM-PROGRAM} is {@code SPACES OR LOW-VALUES}.
     */
    public static final String MAIN_MENU_PROGRAM = "COMEN01C";

    // =================================================================================================
    // Geometry of the symbolic map. Every number here is read off app/cpy-bms/COBIL00.CPY rather than
    // assumed, and the two totals are computed by the compiler from the parts.
    // =================================================================================================

    /**
     * Width of the unnamed leading {@code FILLER}, {@value #TIOAPFX_PREFIX_LENGTH} bytes:
     * {@code 02 FILLER PIC X(12)} at {@code app/cpy-bms/COBIL00.CPY:80}, which is the same leading
     * filler {@code 01 COBIL0AI} declares at line 18. It exists because the mapset is generated with
     * {@code TIOAPFX=YES} at {@code app/bms/COBIL00.bms:24}, which reserves room for the terminal
     * input/output area prefix ahead of the first field.
     */
    public static final int TIOAPFX_PREFIX_LENGTH = 12;

    /**
     * Width of the per-field prologue, {@value #FIELD_PROLOGUE_LENGTH} bytes. On the output view it
     * is a 3-byte {@code FILLER} plus the four one-byte attribute items {@code xxxC}, {@code xxxP},
     * {@code xxxH} and {@code xxxV}; on the input view it is the 2-byte {@code xxxL}, the one-byte
     * {@code xxxF} and a 4-byte {@code FILLER}. Both spend the same seven bytes, which is what allows
     * {@code 01 COBIL0AO} to {@code REDEFINE 01 COBIL0AI} field for field.
     */
    public static final int FIELD_PROLOGUE_LENGTH = 7;

    /**
     * The number of screen fields the map declares, {@value #MAP_FIELD_COUNT}. Counted directly: the
     * output view declares exactly ten {@code xxxO} data items, at
     * {@code app/cpy-bms/COBIL00.CPY} lines 86, 92, 98, 104, 110, 116, 122, 128, 134 and 140, and
     * {@code app/bms/COBIL00.bms} carries exactly ten name-labelled {@code DFHMDF} statements against
     * twenty-four in total.
     */
    public static final int MAP_FIELD_COUNT = 10;

    // =================================================================================================
    // The ten field widths, each the PICTURE clause of its xxxO item. These are the authoritative
    // widths for this screen and are published because a response declares no inbound constraint.
    // =================================================================================================

    /**
     * Width of {@code TRNNAMEO PIC X(4)} at {@code app/cpy-bms/COBIL00.CPY:86},
     * {@value #TRN_NAME_LENGTH}. Cross-checks against {@code LENGTH=4} at
     * {@code app/bms/COBIL00.bms:36}.
     */
    public static final int TRN_NAME_LENGTH = 4;

    /**
     * Width of {@code TITLE01O PIC X(40)} at {@code app/cpy-bms/COBIL00.CPY:92},
     * {@value #TITLE01_LENGTH}. Cross-checks against {@code LENGTH=40} at
     * {@code app/bms/COBIL00.bms:40}.
     */
    public static final int TITLE01_LENGTH = 40;

    /**
     * Width of {@code CURDATEO PIC X(8)} at {@code app/cpy-bms/COBIL00.CPY:98},
     * {@value #CUR_DATE_LENGTH}. Cross-checks against {@code LENGTH=8} at
     * {@code app/bms/COBIL00.bms:49}, and is exactly the width of the {@code mm/dd/yy} literal the
     * mapset supplies as that field's {@code INITIAL} value.
     */
    public static final int CUR_DATE_LENGTH = 8;

    /**
     * Width of {@code PGMNAMEO PIC X(8)} at {@code app/cpy-bms/COBIL00.CPY:104},
     * {@value #PGM_NAME_LENGTH}. Cross-checks against {@code LENGTH=8} at
     * {@code app/bms/COBIL00.bms:59}, and is exactly the width of {@code WS-PGMNAME PIC X(08)}.
     */
    public static final int PGM_NAME_LENGTH = 8;

    /**
     * Width of {@code TITLE02O PIC X(40)} at {@code app/cpy-bms/COBIL00.CPY:110},
     * {@value #TITLE02_LENGTH}. Cross-checks against {@code LENGTH=40} at
     * {@code app/bms/COBIL00.bms:63}.
     */
    public static final int TITLE02_LENGTH = 40;

    /**
     * Width of {@code CURTIMEO PIC X(8)} at {@code app/cpy-bms/COBIL00.CPY:116},
     * {@value #CUR_TIME_LENGTH}. Cross-checks against {@code LENGTH=8} at
     * {@code app/bms/COBIL00.bms:72}, and is exactly the width of the {@code hh:mm:ss} literal the
     * mapset supplies as that field's {@code INITIAL} value.
     */
    public static final int CUR_TIME_LENGTH = 8;

    /**
     * Width of {@code ACTIDINO PIC X(11)} at {@code app/cpy-bms/COBIL00.CPY:122},
     * {@value #ACT_ID_IN_LENGTH}. Cross-checks against {@code LENGTH=11} at
     * {@code app/bms/COBIL00.bms:88}, and against {@code ACCT-ID PIC 9(11)} in the account record -
     * eleven digits of account identifier, which is what the field accepts and echoes.
     */
    public static final int ACT_ID_IN_LENGTH = 11;

    /**
     * Width of {@code CURBALO PIC X(14)} at {@code app/cpy-bms/COBIL00.CPY:128},
     * {@value #CUR_BAL_LENGTH}. Cross-checks against {@code LENGTH=14} at
     * {@code app/bms/COBIL00.bms:105}, and against the edit mask that fills it - see
     * {@link #getCurBal()} for why fourteen is exactly right and why this field is text.
     */
    public static final int CUR_BAL_LENGTH = 14;

    /**
     * Width of {@code CONFIRMO PIC X(1)} at {@code app/cpy-bms/COBIL00.CPY:134},
     * {@value #CONFIRM_LENGTH}. Cross-checks against {@code LENGTH=1} at
     * {@code app/bms/COBIL00.bms:118}. One character is all the field holds, which is why the
     * program's confirmation test at lines 173-191 compares single characters.
     */
    public static final int CONFIRM_LENGTH = 1;

    /**
     * Width of {@code ERRMSGO PIC X(78)} at {@code app/cpy-bms/COBIL00.CPY:140},
     * {@value #ERR_MSG_LENGTH}. Cross-checks against {@code LENGTH=78} at
     * {@code app/bms/COBIL00.bms:129}. Note that this is <em>narrower</em> than the working-storage
     * field the program moves into it; see {@link #WS_MESSAGE_LENGTH}.
     */
    public static final int ERR_MSG_LENGTH = 78;

    /**
     * Total width of the ten data items, {@value #MAP_DATA_LENGTH} bytes. Declared as the sum of the
     * ten width constants above rather than as a typed-in total, so the arithmetic is carried out by
     * the compiler rather than asserted by hand: 4 + 40 + 8 + 8 + 40 + 8 + 11 + 14 + 1 + 78.
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
     * Total width of {@code 01 COBIL0AO}, {@value #SYMBOLIC_MAP_LENGTH} bytes: the
     * {@value #TIOAPFX_PREFIX_LENGTH}-byte prefix, plus {@value #MAP_FIELD_COUNT} prologues of
     * {@value #FIELD_PROLOGUE_LENGTH} bytes, plus {@value #MAP_DATA_LENGTH} bytes of data. That is
     * 12 + 70 + 212. {@code 01 COBIL0AI} is the same width because the output view
     * {@code REDEFINES} it.
     */
    public static final int SYMBOLIC_MAP_LENGTH = TIOAPFX_PREFIX_LENGTH
            + (MAP_FIELD_COUNT * FIELD_PROLOGUE_LENGTH)
            + MAP_DATA_LENGTH;

    /**
     * Width of {@code WS-MESSAGE PIC X(80)} at {@code app/cbl/COBIL00C.cbl:39},
     * {@value #WS_MESSAGE_LENGTH} - the working-storage field every message text is composed into
     * before being sent.
     *
     * <p>Published because it does not match the field it is moved into.
     * {@code MOVE WS-MESSAGE TO ERRMSGO OF COBIL0AO} at line 293 moves
     * {@value #WS_MESSAGE_LENGTH} bytes into a {@value #ERR_MSG_LENGTH}-byte field, and a COBOL
     * {@code MOVE} between alphanumeric items truncates on the <strong>right</strong>, so the final
     * two bytes are discarded. {@link #ERR_MSG_LENGTH} is therefore the width of what the screen
     * receives and is deliberately not widened to {@value #WS_MESSAGE_LENGTH} to match the source of
     * the move. No message this program composes is long enough for the truncation to remove a
     * visible character - the longest, the successful-payment text of lines 527-531, reaches 61
     * characters with a full sixteen-character transaction identifier, and the longest fixed text,
     * {@code 'Invalid value. Valid values are (Y/N)...'} at line 187, is 40 - but the two widths are
     * genuinely different and are recorded as such rather than reconciled.
     */
    public static final int WS_MESSAGE_LENGTH = 80;

    /**
     * Width of a BMS field-attribute byte, {@value #MESSAGE_HIGHLIGHT_LENGTH}. Every attribute item
     * on the output view - {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV} - is declared
     * {@code PICTURE X}, a single byte. It is the width of {@link #getMessageHighlight()}.
     */
    public static final int MESSAGE_HIGHLIGHT_LENGTH = 1;

    // =================================================================================================
    // The CDEMO-CB00-INFO extension. app/cbl/COBIL00C.cbl:64-72 appends a 05-level group INSIDE
    // 01 CARDDEMO-COMMAREA, immediately after COPY COCOM01Y, so this program's communication area is
    // 58 bytes longer than the shared 160-byte one. Those 58 bytes are declared as six flat members on
    // this class rather than being added to NavigationContext, which must stay exactly 160 bytes
    // because all seventeen controllers share it.
    // =================================================================================================

    /**
     * Width of {@code CDEMO-CB00-TRNID-FIRST PIC X(16)} at {@code app/cbl/COBIL00C.cbl:65},
     * {@value #TRN_ID_FIRST_LENGTH} - a transaction identifier, matching
     * {@code TRAN-ID PIC X(16)} in the transaction record.
     */
    public static final int TRN_ID_FIRST_LENGTH = 16;

    /**
     * Width of {@code CDEMO-CB00-TRNID-LAST PIC X(16)} at {@code app/cbl/COBIL00C.cbl:66},
     * {@value #TRN_ID_LAST_LENGTH}.
     */
    public static final int TRN_ID_LAST_LENGTH = 16;

    /**
     * Digit count of {@code CDEMO-CB00-PAGE-NUM PIC 9(08)} at {@code app/cbl/COBIL00C.cbl:67},
     * {@value #PAGE_NUM_DIGITS}. The item is scale-free - eight digits and no {@code V} - which is
     * why {@link #getPageNum()} is an {@code int} rather than a decimal type.
     */
    public static final int PAGE_NUM_DIGITS = 8;

    /**
     * Width of {@code CDEMO-CB00-NEXT-PAGE-FLG PIC X(01)} at {@code app/cbl/COBIL00C.cbl:68},
     * {@value #NEXT_PAGE_FLG_LENGTH}.
     */
    public static final int NEXT_PAGE_FLG_LENGTH = 1;

    /**
     * Width of {@code CDEMO-CB00-TRN-SEL-FLG PIC X(01)} at {@code app/cbl/COBIL00C.cbl:71},
     * {@value #TRN_SEL_FLG_LENGTH}.
     */
    public static final int TRN_SEL_FLG_LENGTH = 1;

    /**
     * Width of {@code CDEMO-CB00-TRN-SELECTED PIC X(16)} at {@code app/cbl/COBIL00C.cbl:72},
     * {@value #TRN_SELECTED_LENGTH}. This is the one member of the extension the program actually
     * reads; see {@link #getTrnSelected()}.
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
     * The value of {@code 88 NEXT-PAGE-YES} at {@code app/cbl/COBIL00C.cbl:69}, {@code 'Y'}.
     */
    public static final String NEXT_PAGE_YES = "Y";

    /**
     * The value of {@code 88 NEXT-PAGE-NO} at {@code app/cbl/COBIL00C.cbl:70}, {@code 'N'}, which is
     * also the {@code VALUE} clause on the field itself at line 68 and therefore the initial state of
     * {@link #getNextPageFlg()}.
     */
    public static final String NEXT_PAGE_NO = "N";

    // =================================================================================================
    // The cursor-field indicator. Nested here rather than written as a separate file because
    // billing/dto holds exactly two types - this response and its request twin - and a third file
    // would widen that folder for a three-constant enumeration used by one member.
    // =================================================================================================

    /**
     * Which screen field the cursor is placed in when this response is rendered.
     *
     * <p>In CICS, {@code MOVE -1 TO xxxL} is the idiom for "put the cursor here": the length item of
     * the target field is set negative, and the {@code CURSOR} option on
     * {@code EXEC CICS SEND MAP} - present at {@code app/cbl/COBIL00C.cbl:300} - honours it.
     * {@code COBIL00C} does this in <strong>seventeen</strong> places, and because {@code xxxL} is a
     * prologue item sharing its bytes with the output view's attribute items, none of those writes
     * can be modelled as a payload field. All seventeen collapse into this one indicator:
     *
     * <table border="1">
     *   <caption>Every cursor-positioning statement in {@code app/cbl/COBIL00C.cbl}</caption>
     *   <tr><th>Constant</th><th>Statement</th><th>Count</th><th>Lines</th></tr>
     *   <tr><td>{@link #ACTIDIN}</td><td>{@code MOVE -1 TO ACTIDINL OF COBIL0AI}</td><td>15</td>
     *       <td>115, 163, 203, 363, 370, 394, 401, 427, 434, 458, 465, 494, 538, 545, 562</td></tr>
     *   <tr><td>{@link #CONFIRM}</td><td>{@code MOVE -1 TO CONFIRML OF COBIL0AI}</td><td>2</td>
     *       <td>189, 239</td></tr>
     *   <tr><td>{@link #NONE}</td><td>no cursor statement on the path</td><td>-</td>
     *       <td>for example the invalid-key path at 138-141</td></tr>
     * </table>
     *
     * <p>The count is <strong>seventeen</strong>, which is worth stating because fifteen is the
     * {@code ACTIDINL} subtotal and is easy to mistake for the total. The two {@code CONFIRML} sites
     * are the only cursor placements anywhere in the program that are not {@code ACTIDIN}, and both
     * belong to the confirmation field: line 189 is the {@code WHEN OTHER} arm of the confirmation
     * switch, reached when the field holds something other than {@code 'Y'}, {@code 'y'},
     * {@code 'N'}, {@code 'n'}, spaces or {@code LOW-VALUES}, which sends
     * {@code 'Invalid value. Valid values are (Y/N)...'}; and line 239 is the path that has an account
     * and a payable balance but no confirmation yet, which sends
     * {@code 'Confirm to make a bill payment...'}. In both cases the cursor is placed where the user
     * has to type next, which is the confirmation field rather than the account field.
     *
     * <p>No other field on this screen is ever a cursor target. Only {@code ACTIDIN} and
     * {@code CONFIRM} are declared {@code UNPROT} in the mapset - the other eight are {@code ASKIP} -
     * so no other field could usefully be one, and these three constants are exhaustive.
     *
     * <p>Declared as an {@code enum} rather than as a string with comparison helpers for two reasons.
     * It makes the three reachable states exhaustive and referable by name from a test assertion, and
     * it adds no branch to this class: a string would invite a comparison helper, and coverage here
     * is gated on the branch counter at package level as well as at bundle level.
     */
    public enum CursorField {

        /**
         * No cursor repositioning is requested, so the terminal applies the map's own default.
         *
         * <p>That default is {@link #ACTIDIN}, because {@code app/bms/COBIL00.bms:85} declares that
         * field {@code ATTRB=(FSET,IC,NORM,UNPROT)} and the {@code IC} option means "insert cursor":
         * it is the mapset's declared initial cursor position. This constant therefore means "the
         * program did not override the map default on this path", not "the cursor is nowhere".
         *
         * <p>It is genuinely reachable. The invalid-key path at {@code app/cbl/COBIL00C.cbl:138-141}
         * sets the message from {@code CCDA-MSG-INVALID-KEY} and sends the screen without any
         * {@code MOVE -1}, so the cursor stays where the map put it.
         *
         * <p>This is the initial value of {@link BillPaymentResponse#getCursorField()}, so a response
         * that never has a cursor field assigned reports exactly what the program's own no-override
         * paths do.
         */
        NONE,

        /**
         * The cursor is placed in the account-identifier field, {@code ACTIDIN} at {@code (6,21)}.
         *
         * <p>Fifteen of the program's seventeen cursor statements select this field, at
         * {@code app/cbl/COBIL00C.cbl} lines 115, 163, 203, 363, 370, 394, 401, 427, 434, 458, 465,
         * 494, 538, 545 and 562 - first entry, every validation and file-access failure, and the
         * screen-clearing path. Line 562 sits inside {@code INITIALIZE-ALL-FIELDS}, which is why a
         * cleared screen also parks the cursor here, and why a successful payment does too: the
         * successful-{@code WRITE} arm at line 524 performs that paragraph.
         *
         * <p>This is also the field the mapset marks {@code IC}, so selecting it explicitly agrees
         * with the map default rather than overriding it.
         */
        ACTIDIN,

        /**
         * The cursor is placed in the confirmation field, {@code CONFIRM} at {@code (15,60)}.
         *
         * <p>The program's only two non-{@code ACTIDIN} cursor placements, at
         * {@code app/cbl/COBIL00C.cbl:189} and {@code 239}: an unrecognised confirmation value, and a
         * payable balance awaiting confirmation.
         */
        CONFIRM
    }

    // =================================================================================================
    // THE TEN PAYLOAD MEMBERS, in the order 01 COBIL0AO declares them. Each is the xxxO data item and
    // nothing else: no attribute item, no length item, no flag byte. Each is a String because every
    // xxxO item is PIC X(n) - the symbolic map has no numeric data item at all, not even for the
    // balance, which arrives already edited into characters.
    //
    // Every member stays independently nullable and is stored exactly as given. COBOL distinguishes
    // LOW-VALUES from SPACES and this program tests for both separately, so null, "" and an all-blank
    // string are three distinct states here and none is normalised into another.
    // =================================================================================================

    /**
     * {@code TRNNAMEO PIC X(4)} - the transaction identifier shown at {@code (1,7)} after the
     * {@code 'Tran:'} label.
     *
     * <p>Written by {@code POPULATE-HEADER-INFO} at {@code app/cbl/COBIL00C.cbl:325},
     * {@code MOVE WS-TRANID TO TRNNAMEO OF COBIL0AO}, so its value is {@value #TRANSACTION_ID}.
     */
    private String trnName;

    /**
     * {@code TITLE01O PIC X(40)} - the first title line, shown at {@code (1,21)} in yellow.
     *
     * <p>Written by {@code POPULATE-HEADER-INFO} at {@code app/cbl/COBIL00C.cbl:323},
     * {@code MOVE CCDA-TITLE01 TO TITLE01O OF COBIL0AO}. {@code CCDA-TITLE01} is a literal from the
     * shared {@code COTTL01Y} copybook, so the text is supplied by this module's screen-title
     * constants rather than composed here.
     */
    private String title01;

    /**
     * {@code CURDATEO PIC X(8)} - the current date shown at {@code (1,71)} after the {@code 'Date:'}
     * label.
     *
     * <p>Written by {@code POPULATE-HEADER-INFO} at {@code app/cbl/COBIL00C.cbl:332},
     * {@code MOVE WS-CURDATE-MM-DD-YY TO CURDATEO OF COBIL0AO}, having assembled the parts at lines
     * 328-330 from {@code FUNCTION CURRENT-DATE}. The rendering is {@code MM/DD/YY} - month, day, then
     * the last two digits of the year, taken by {@code WS-CURDATE-YEAR(3:2)} - which is exactly the
     * shape of the {@code 'mm/dd/yy'} literal the mapset supplies as the field's {@code INITIAL}
     * value.
     */
    private String curDate;

    /**
     * {@code PGMNAMEO PIC X(8)} - the program name shown at {@code (2,7)} after the {@code 'Prog:'}
     * label.
     *
     * <p>Written by {@code POPULATE-HEADER-INFO} at {@code app/cbl/COBIL00C.cbl:326},
     * {@code MOVE WS-PGMNAME TO PGMNAMEO OF COBIL0AO}, so its value is {@value #PROGRAM_NAME}.
     */
    private String pgmName;

    /**
     * {@code TITLE02O PIC X(40)} - the second title line, shown at {@code (2,21)} in yellow.
     *
     * <p>Written by {@code POPULATE-HEADER-INFO} at {@code app/cbl/COBIL00C.cbl:324},
     * {@code MOVE CCDA-TITLE02 TO TITLE02O OF COBIL0AO}, from the same shared {@code COTTL01Y}
     * copybook as {@link #getTitle01()}.
     */
    private String title02;

    /**
     * {@code CURTIMEO PIC X(8)} - the current time shown at {@code (2,71)} after the {@code 'Time:'}
     * label.
     *
     * <p>Written by {@code POPULATE-HEADER-INFO} at {@code app/cbl/COBIL00C.cbl:338},
     * {@code MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO OF COBIL0AO}, having assembled the parts at lines
     * 334-336. The rendering is {@code HH:MM:SS}, matching the {@code 'hh:mm:ss'} literal the mapset
     * supplies as the field's {@code INITIAL} value.
     */
    private String curTime;

    /**
     * {@code ACTIDINO PIC X(11)} - the account identifier, the screen's primary input field, at
     * {@code (6,21)} after the {@code 'Enter Acct ID:'} label.
     *
     * <p>One of only two fields the mapset declares {@code UNPROT}, and the one it marks {@code IC},
     * so it is where the cursor starts. It is echoed back so the user sees what was entered.
     *
     * <p>The program writes it through the <em>input</em> view, which is the same byte span: at
     * {@code app/cbl/COBIL00C.cbl:118-119} it seeds the field from
     * {@code CDEMO-CB00-TRN-SELECTED} when another screen navigated here with an account already
     * chosen, and at line 563 {@code INITIALIZE-ALL-FIELDS} blanks it. Either way the bytes the
     * program sends are the bytes it wrote, which is why this is a genuine response member.
     */
    private String actIdIn;

    /**
     * {@code CURBALO PIC X(14)} - the account's current balance, shown at {@code (11,32)} after the
     * {@code 'Your current balance is: '} label.
     *
     * <p><strong>This member is text, and never a number.</strong> The program declares
     * {@code 05 WS-CURR-BAL PIC +9999999999.99} at {@code app/cbl/COBIL00C.cbl:56} - an
     * <em>edited</em> picture: one sign character, ten digits, a literal decimal point and two more
     * digits, which is 1 + 10 + 1 + 2 = {@value #CUR_BAL_LENGTH} characters, and is precisely why
     * {@code CURBALO} is {@code PIC X(14)}. Line 193 moves the packed account balance into that edit
     * mask and line 194 moves the resulting characters onto the screen, so what travels here is the
     * rendered form: sign and decimal point included, fixed width, zero-filled on the left.
     *
     * <p>It is therefore a {@code String} and must stay one. Parsing it into a numeric type here
     * would discard the leading zeros, the explicit sign character and the exact column alignment
     * that the field-for-field parity comparison checks, and re-rendering it would risk a different
     * mask. The balance as a <em>number</em> lives in the account record, at its own declared scale,
     * and the arithmetic on it - {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT} at line 234
     * - is performed in the service layer. No numeric convenience accessor is offered on this class
     * for that reason.
     *
     * <p>Note that {@code WS-TRAN-AMT PIC +99999999.99} at line 55 is a <em>different</em> mask, two
     * digits narrower, and is not this field.
     */
    private String curBal;

    /**
     * {@code CONFIRMO PIC X(1)} - the one-character payment confirmation, at {@code (15,60)} after the
     * {@code 'Do you want to pay your balance now. Please confirm: '} label and before the
     * {@code '(Y/N)'} hint.
     *
     * <p>The screen's second and last {@code UNPROT} field. Echoed back so the user sees what was
     * entered; blanked through the input view by {@code INITIALIZE-ALL-FIELDS} at
     * {@code app/cbl/COBIL00C.cbl:565} whenever the screen is cleared or a payment has just
     * succeeded.
     *
     * <p>The values the program recognises are {@code 'Y'}, {@code 'y'}, {@code 'N'}, {@code 'n'},
     * spaces and {@code LOW-VALUES}, tested as separate {@code WHEN} arms at lines 173-191; anything
     * else takes the {@code WHEN OTHER} arm. Those tests live in the service layer, not here.
     */
    private String confirm;

    /**
     * {@code ERRMSGO PIC X(78)} - the message line, shown bright red at {@code (23,1)}.
     *
     * <p>Every message this screen displays lands here, through the single statement
     * {@code MOVE WS-MESSAGE TO ERRMSGO OF COBIL0AO} at {@code app/cbl/COBIL00C.cbl:293}, which
     * {@code SEND-BILLPAY-SCREEN} executes on every send. The field is cleared unconditionally on
     * entry at line 105, so a response carrying no message carries blanks rather than a stale one.
     *
     * <p>The vocabulary, composed by the service layer and merely carried here, is: the validation
     * texts {@code 'Acct ID can NOT be empty...'} (line 161),
     * {@code 'Invalid value. Valid values are (Y/N)...'} (187),
     * {@code 'You have nothing to pay...'} (201) and
     * {@code 'Confirm to make a bill payment...'} (237); the file-access texts
     * {@code 'Account ID NOT found...'} (361, 392, 425),
     * {@code 'Unable to lookup Account...'} (368), {@code 'Unable to Update Account...'} (399),
     * {@code 'Unable to lookup XREF AIX file...'} (432),
     * {@code 'Transaction ID NOT found...'} (456),
     * {@code 'Unable to lookup Transaction...'} (463, 492),
     * {@code 'Tran ID already exist...'} (536) and
     * {@code 'Unable to Add Bill pay Transaction...'} (543); the shared invalid-key text
     * {@code CCDA-MSG-INVALID-KEY} (140); and the composed success text of lines 527-531,
     * {@code 'Payment successful. '} followed by {@code ' Your Transaction ID is '}, the transaction
     * identifier and a full stop - which renders with two spaces after the first sentence, because
     * the first literal ends with a space and the second begins with one.
     *
     * <p>The longest of these is 61 characters, so {@value #ERR_MSG_LENGTH} is comfortably
     * sufficient. It is nonetheless narrower than the {@value #WS_MESSAGE_LENGTH}-byte field the
     * program moves from; see {@link #WS_MESSAGE_LENGTH} for why the width stays as the screen
     * declares it.
     *
     * <p>The colour this text renders in is not fixed: see {@link #getMessageHighlight()}.
     */
    private String errMsg;

    // =================================================================================================
    // Presentation state that has no xxxO item of its own. Both members below are collapsed metadata
    // projections, named for what they mean so that neither can be mistaken for a field projection.
    // =================================================================================================

    /**
     * Which field the cursor is placed in, collapsing the program's seventeen
     * {@code MOVE -1 TO xxxL} statements into one indicator.
     *
     * <p>Initialised to {@link CursorField#NONE}, which is the honest initial state: it means no
     * override, and the terminal then applies the mapset's own {@code IC} field. See
     * {@link CursorField} for the complete site list and the reasoning.
     */
    private CursorField cursorField = CursorField.NONE;

    /**
     * The colour attribute applied to the message line, or {@code null} where the program applies
     * none.
     *
     * <p>This carries the single field-attribute override in the entire 573-line program:
     * {@code MOVE DFHGREEN TO ERRMSGC OF COBIL0AO} at {@code app/cbl/COBIL00C.cbl:526}, on the
     * successful-{@code WRITE} arm of {@code WRITE-TRANSACT-FILE}. Verified by exhaustive search: no
     * other {@code xxxC}, {@code xxxP}, {@code xxxH} or {@code xxxV} item is ever written anywhere in
     * the program, so no other field on this screen needs a runtime attribute carrier and none is
     * offered.
     *
     * <p>It is observable behaviour rather than decoration, which is why it is carried at all. The
     * mapset declares the message line {@code COLOR=RED} at {@code app/bms/COBIL00.bms:128}, so a
     * message renders red by default and green only on a successful payment - the one case where the
     * line reports success rather than a problem. {@code null} therefore means "no override, render
     * in the map's declared red", and is the initial state.
     *
     * <p>Carried as a plain {@code String} of {@value #MESSAGE_HIGHLIGHT_LENGTH} character - every
     * BMS attribute item is {@code PICTURE X} - and deliberately <em>not</em> named after the
     * {@code ERRMSGC} item it derives from, for the same reason no member here is named after an
     * {@code xxxL} or {@code xxxA} item. The attribute constants themselves belong to this module's
     * shared BMS attribute class, which the service consumes; this class only transports the byte, so
     * it does not depend on that class and its only repository-internal dependency stays
     * {@link NavigationContext}.
     */
    private String messageHighlight;

    // =================================================================================================
    // Navigation. CICS transferred control with EXEC CICS XCTL; a stateless REST response instead
    // names where the client should go next and lets the client go there.
    // =================================================================================================

    /**
     * The communication area handed back to the client, {@code 01 CARDDEMO-COMMAREA} from
     * {@code app/cpy/COCOM01Y.cpy}, {@value NavigationContext#COMMAREA_LENGTH} bytes.
     *
     * <p>{@code COBIL00C} returns it on {@code EXEC CICS RETURN TRANSID(WS-TRANID)
     * COMMAREA(CARDDEMO-COMMAREA)} at {@code app/cbl/COBIL00C.cbl:146-149} and passes it on
     * {@code EXEC CICS XCTL ... COMMAREA(CARDDEMO-COMMAREA)} at lines 281-284. Either way the client
     * carries it into the next call, which is what makes the conversation work without server-side
     * state.
     *
     * <p>Referenced, not extended and not redeclared: {@link NavigationContext} is a {@code record}
     * shared by all seventeen controllers and must stay exactly
     * {@value NavigationContext#COMMAREA_LENGTH} bytes, so this program's extra 58 bytes are the six
     * flat members below rather than additions to it.
     *
     * <p>Before transferring control, {@code RETURN-TO-PREV-SCREEN} stamps its own identity into this
     * area at lines 278-280 - {@code CDEMO-FROM-TRANID} becomes {@value #TRANSACTION_ID},
     * {@code CDEMO-FROM-PROGRAM} becomes {@value #PROGRAM_NAME} and {@code CDEMO-PGM-CONTEXT} becomes
     * zero, so the next screen knows who called it and that it is being entered fresh. This class
     * carries the result of that stamping; the controller performs it.
     */
    private NavigationContext navigationContext;

    /**
     * The program the client should call next, replacing
     * {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)} at {@code app/cbl/COBIL00C.cbl:281-284} - the
     * program's only transfer of control.
     *
     * <p>Four values are reachable, all verified against the source:
     *
     * <ul>
     *   <li>{@value #SIGN_ON_PROGRAM} - the cold start at lines 107-109, where {@code EIBCALEN = 0}
     *       means no communication area was passed, and the {@code RETURN-TO-PREV-SCREEN} fallback at
     *       lines 275-277, where {@code CDEMO-TO-PROGRAM} is {@code LOW-VALUES OR SPACES}</li>
     *   <li>{@value #MAIN_MENU_PROGRAM} - the {@code DFHPF3} path at lines 129-130, where
     *       {@code CDEMO-FROM-PROGRAM} is {@code SPACES OR LOW-VALUES} so there is no caller to
     *       return to</li>
     *   <li>the echoed {@code CDEMO-FROM-PROGRAM} - ordinary {@code DFHPF3} back-navigation, lines
     *       132-133</li>
     *   <li>the echoed {@code CDEMO-TO-PROGRAM} - whatever the communication area already carried</li>
     * </ul>
     *
     * <p>Which of the four applies is decided by the controller, not here. {@code null} means the
     * screen re-displays itself and no transfer is requested, which is what every
     * {@code SEND-BILLPAY-SCREEN} path does.
     *
     * <p>This is a <strong>named target, not a redirect.</strong> There is deliberately no HTTP
     * redirect, no {@code Location} header helper and no server-side forward anywhere in this type:
     * the client reads this member and issues the follow-up call itself, which is what keeps the
     * server free of conversation state.
     */
    private String nextProgram;

    /**
     * The mapset the client should render next, {@value #MAPSET_NAME} whenever this screen
     * re-displays itself.
     *
     * <p>From {@code MAPSET('COBIL00')} on the {@code EXEC CICS SEND MAP} at
     * {@code app/cbl/COBIL00C.cbl:297}. Carried explicitly because a stateless client cannot infer
     * which screen a payload belongs to.
     */
    private String nextMapset;

    /**
     * The map the client should render next, {@value #MAP_NAME} whenever this screen re-displays
     * itself.
     *
     * <p>From {@code MAP('COBIL0A')} on the {@code EXEC CICS SEND MAP} at
     * {@code app/cbl/COBIL00C.cbl:296}.
     */
    private String nextMap;

    // =================================================================================================
    // The six CDEMO-CB00-INFO members, echoed back so the client can carry them into the next call.
    // FIVE OF THE SIX ARE NEVER READ by COBIL00C - verified by exhaustive search. They are carried
    // anyway because the source declares them: a like-for-like migration preserves the declared shape
    // of the communication area, and dropping a member would change what the next screen receives.
    // =================================================================================================

    /**
     * {@code CDEMO-CB00-TRNID-FIRST PIC X(16)}, declared at {@code app/cbl/COBIL00C.cbl:65}.
     *
     * <p>Declared by the source and <strong>never read by it</strong>: exhaustive search finds no
     * reference to this item anywhere in the program's procedure division. Carried, not deleted.
     */
    private String trnIdFirst;

    /**
     * {@code CDEMO-CB00-TRNID-LAST PIC X(16)}, declared at {@code app/cbl/COBIL00C.cbl:66}.
     *
     * <p>Declared by the source and <strong>never read by it</strong>. Carried, not deleted.
     */
    private String trnIdLast;

    /**
     * {@code CDEMO-CB00-PAGE-NUM PIC 9(08)}, declared at {@code app/cbl/COBIL00C.cbl:67}.
     *
     * <p>An {@code int}, because the item is a scale-free {@code PIC 9} of
     * {@value #PAGE_NUM_DIGITS} digits with no {@code V} and no sign: eight digits fit an {@code int}
     * with room to spare. Only picture clauses carrying an implied decimal point become a decimal
     * type, and this one does not, so it is deliberately neither a decimal type nor a floating-point
     * one.
     *
     * <p>Declared by the source and <strong>never read by it</strong>. Carried, not deleted. Its
     * initial value is zero, which is what a numeric {@code PIC 9} item initialises to and what the
     * screen's single, unpaginated page amounts to in any case.
     */
    private int pageNum;

    /**
     * {@code CDEMO-CB00-NEXT-PAGE-FLG PIC X(01) VALUE 'N'}, declared at
     * {@code app/cbl/COBIL00C.cbl:68}, over which the source declares
     * {@code 88 NEXT-PAGE-YES VALUE 'Y'} at line 69 and {@code 88 NEXT-PAGE-NO VALUE 'N'} at line 70.
     *
     * <p>Initialised to {@value #NEXT_PAGE_NO}, honouring the {@code VALUE} clause the source puts on
     * the item itself - the one member of this group with an initial value.
     *
     * <p>Declared by the source and <strong>never read by it</strong>. Carried, not deleted. Note that
     * the two condition names are not exhaustive: a flag holding a space, a {@code LOW-VALUES} byte or
     * {@code null} satisfies neither, exactly as two independent {@code 88}-level tests behave in
     * COBOL, so no predicate is offered here that would imply otherwise.
     */
    private String nextPageFlg = NEXT_PAGE_NO;

    /**
     * {@code CDEMO-CB00-TRN-SEL-FLG PIC X(01)}, declared at {@code app/cbl/COBIL00C.cbl:71}.
     *
     * <p>Declared by the source and <strong>never read by it</strong>, and carrying no condition name.
     * Carried, not deleted.
     */
    private String trnSelFlg;

    /**
     * {@code CDEMO-CB00-TRN-SELECTED PIC X(16)}, declared at {@code app/cbl/COBIL00C.cbl:72}.
     *
     * <p><strong>The one member of this group the program actually reads.</strong> On first entry it
     * is tested at lines 116-117 and, where it holds neither spaces nor {@code LOW-VALUES}, moved into
     * the account-identifier field at lines 118-119 and processed immediately - which is how another
     * screen navigates here with an account already chosen and gets its balance displayed without the
     * user retyping it.
     *
     * <p>Echoed back so that behaviour survives the next round trip.
     */
    private String trnSelected;

    // =================================================================================================
    // Construction. One no-argument constructor and nothing else: the controller fills this object
    // incrementally, mirroring the program's own sequence, and the tests build partially populated
    // instances with no Spring context in the path.
    // =================================================================================================

    /**
     * Creates an empty response.
     *
     * <p>All ten map members, both navigation names and five of the six communication-area extension
     * members start {@code null}; {@link #getCursorField()} starts {@link CursorField#NONE},
     * {@link #getNextPageFlg()} starts {@value #NEXT_PAGE_NO} per the source's {@code VALUE} clause,
     * and {@link #getPageNum()} starts zero. Nothing else is defaulted, because a {@code null} member
     * is the meaningful {@code LOW-VALUES} state that {@code MOVE LOW-VALUES TO COBIL0AO} at
     * {@code app/cbl/COBIL00C.cbl:114} produces and must remain distinguishable from blanks.
     */
    public BillPaymentResponse() {
        // Every field carries its declared initial state; there is deliberately nothing to compute
        // here, and no validation, because this type holds no decision logic.
    }

    // =================================================================================================
    // Accessors for the ten payload members. Each one stores and returns its value exactly as given:
    // no trimming, no padding, no defaulting, no case folding and no empty-to-null coercion.
    // =================================================================================================

    /**
     * Returns {@code TRNNAMEO}, the transaction identifier shown in the screen header.
     *
     * @return at most {@value #TRN_NAME_LENGTH} characters, normally {@value #TRANSACTION_ID},
     *         possibly {@code null}
     */
    public String getTrnName() {
        return trnName;
    }

    /**
     * Sets {@code TRNNAMEO}. Stored as given.
     *
     * @param trnName the transaction identifier, at most {@value #TRN_NAME_LENGTH} characters;
     *                {@code null} is permitted and preserved
     */
    public void setTrnName(String trnName) {
        this.trnName = trnName;
    }

    /**
     * Returns {@code TITLE01O}, the first header title line.
     *
     * @return at most {@value #TITLE01_LENGTH} characters, possibly {@code null}
     */
    public String getTitle01() {
        return title01;
    }

    /**
     * Sets {@code TITLE01O}. Stored as given.
     *
     * @param title01 the first title line, at most {@value #TITLE01_LENGTH} characters;
     *                {@code null} is permitted and preserved
     */
    public void setTitle01(String title01) {
        this.title01 = title01;
    }

    /**
     * Returns {@code CURDATEO}, the current date rendered {@code MM/DD/YY}.
     *
     * @return at most {@value #CUR_DATE_LENGTH} characters, possibly {@code null}
     */
    public String getCurDate() {
        return curDate;
    }

    /**
     * Sets {@code CURDATEO}. Stored as given, so the caller owns the {@code MM/DD/YY} rendering.
     *
     * @param curDate the current date, at most {@value #CUR_DATE_LENGTH} characters;
     *                {@code null} is permitted and preserved
     */
    public void setCurDate(String curDate) {
        this.curDate = curDate;
    }

    /**
     * Returns {@code PGMNAMEO}, the program name shown in the screen header.
     *
     * @return at most {@value #PGM_NAME_LENGTH} characters, normally {@value #PROGRAM_NAME},
     *         possibly {@code null}
     */
    public String getPgmName() {
        return pgmName;
    }

    /**
     * Sets {@code PGMNAMEO}. Stored as given.
     *
     * @param pgmName the program name, at most {@value #PGM_NAME_LENGTH} characters;
     *                {@code null} is permitted and preserved
     */
    public void setPgmName(String pgmName) {
        this.pgmName = pgmName;
    }

    /**
     * Returns {@code TITLE02O}, the second header title line.
     *
     * @return at most {@value #TITLE02_LENGTH} characters, possibly {@code null}
     */
    public String getTitle02() {
        return title02;
    }

    /**
     * Sets {@code TITLE02O}. Stored as given.
     *
     * @param title02 the second title line, at most {@value #TITLE02_LENGTH} characters;
     *                {@code null} is permitted and preserved
     */
    public void setTitle02(String title02) {
        this.title02 = title02;
    }

    /**
     * Returns {@code CURTIMEO}, the current time rendered {@code HH:MM:SS}.
     *
     * @return at most {@value #CUR_TIME_LENGTH} characters, possibly {@code null}
     */
    public String getCurTime() {
        return curTime;
    }

    /**
     * Sets {@code CURTIMEO}. Stored as given, so the caller owns the {@code HH:MM:SS} rendering.
     *
     * @param curTime the current time, at most {@value #CUR_TIME_LENGTH} characters;
     *                {@code null} is permitted and preserved
     */
    public void setCurTime(String curTime) {
        this.curTime = curTime;
    }

    /**
     * Returns {@code ACTIDINO}, the account identifier echoed back to the screen.
     *
     * @return at most {@value #ACT_ID_IN_LENGTH} characters, possibly {@code null}
     */
    public String getActIdIn() {
        return actIdIn;
    }

    /**
     * Sets {@code ACTIDINO}. Stored as given - in particular an all-blank value and {@code null} stay
     * distinct, because the program tests {@code SPACES} and {@code LOW-VALUES} separately.
     *
     * @param actIdIn the account identifier, at most {@value #ACT_ID_IN_LENGTH} characters;
     *                {@code null} is permitted and preserved
     */
    public void setActIdIn(String actIdIn) {
        this.actIdIn = actIdIn;
    }

    /**
     * Returns {@code CURBALO}, the account balance <strong>already rendered as characters</strong>
     * through the {@code PIC +9999999999.99} edit mask - sign, ten digits, decimal point, two digits.
     *
     * <p>Text by contract, never a number; see the field documentation for why converting it here
     * would lose parity-relevant detail.
     *
     * @return at most {@value #CUR_BAL_LENGTH} characters, possibly {@code null}
     */
    public String getCurBal() {
        return curBal;
    }

    /**
     * Sets {@code CURBALO}. Stored as given, so the caller owns the edit-mask rendering.
     *
     * @param curBal the edited balance text, at most {@value #CUR_BAL_LENGTH} characters;
     *               {@code null} is permitted and preserved
     */
    public void setCurBal(String curBal) {
        this.curBal = curBal;
    }

    /**
     * Returns {@code CONFIRMO}, the one-character payment confirmation echoed back to the screen.
     *
     * @return at most {@value #CONFIRM_LENGTH} character, possibly {@code null}
     */
    public String getConfirm() {
        return confirm;
    }

    /**
     * Sets {@code CONFIRMO}. Stored as given, with no case folding: the program treats {@code 'Y'} and
     * {@code 'y'} as separate {@code WHEN} arms rather than by normalising the value.
     *
     * @param confirm the confirmation character, at most {@value #CONFIRM_LENGTH} character;
     *                {@code null} is permitted and preserved
     */
    public void setConfirm(String confirm) {
        this.confirm = confirm;
    }

    /**
     * Returns {@code ERRMSGO}, the message line.
     *
     * @return at most {@value #ERR_MSG_LENGTH} characters, possibly {@code null}
     */
    public String getErrMsg() {
        return errMsg;
    }

    /**
     * Sets {@code ERRMSGO}. Stored as given and never truncated here: where a caller supplies text
     * from the program's {@value #WS_MESSAGE_LENGTH}-byte working-storage message field, applying the
     * two-byte right truncation that {@code MOVE WS-MESSAGE TO ERRMSGO} performs is the caller's
     * responsibility, so that the truncation happens once and visibly rather than silently here.
     *
     * @param errMsg the message text, at most {@value #ERR_MSG_LENGTH} characters;
     *               {@code null} is permitted and preserved
     */
    public void setErrMsg(String errMsg) {
        this.errMsg = errMsg;
    }

    // =================================================================================================
    // Accessors for the two collapsed presentation-state members.
    // =================================================================================================

    /**
     * Returns which field the cursor is placed in.
     *
     * @return one of the three {@link CursorField} constants, {@link CursorField#NONE} unless
     *         assigned; {@code null} only where a caller assigned it explicitly
     */
    public CursorField getCursorField() {
        return cursorField;
    }

    /**
     * Sets which field the cursor is placed in. Stored as given.
     *
     * @param cursorField the cursor target; {@link CursorField#NONE} requests no override, leaving the
     *                    terminal to apply the mapset's declared {@code IC} field
     */
    public void setCursorField(CursorField cursorField) {
        this.cursorField = cursorField;
    }

    /**
     * Returns the colour attribute applied to the message line.
     *
     * @return a {@value #MESSAGE_HIGHLIGHT_LENGTH}-character BMS attribute value, or {@code null}
     *         where the program applies no override and the map's declared red stands
     */
    public String getMessageHighlight() {
        return messageHighlight;
    }

    /**
     * Sets the colour attribute applied to the message line. Stored as given.
     *
     * @param messageHighlight the BMS attribute value, {@value #MESSAGE_HIGHLIGHT_LENGTH} character;
     *                         {@code null} requests no override
     */
    public void setMessageHighlight(String messageHighlight) {
        this.messageHighlight = messageHighlight;
    }

    // =================================================================================================
    // Accessors for the carried communication area and the navigation targets.
    // =================================================================================================

    /**
     * Returns the {@value NavigationContext#COMMAREA_LENGTH}-byte communication area handed back to
     * the client.
     *
     * @return the carried context, or {@code null} where none was set
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    /**
     * Sets the communication area handed back to the client. Stored as given; the carrier is a
     * {@code record} and is neither copied nor rebuilt here.
     *
     * @param navigationContext the context to carry; {@code null} is permitted and preserved
     */
    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = navigationContext;
    }

    /**
     * Returns the program the client should call next.
     *
     * @return one of {@value #SIGN_ON_PROGRAM}, {@value #MAIN_MENU_PROGRAM}, an echoed
     *         {@code CDEMO-FROM-PROGRAM} or {@code CDEMO-TO-PROGRAM} value, or {@code null} where the
     *         screen re-displays itself and no transfer is requested
     */
    public String getNextProgram() {
        return nextProgram;
    }

    /**
     * Sets the program the client should call next. Stored as given; this names a target and does not
     * cause a redirect or a server-side forward.
     *
     * @param nextProgram the next program name; {@code null} requests no transfer
     */
    public void setNextProgram(String nextProgram) {
        this.nextProgram = nextProgram;
    }

    /**
     * Returns the mapset the client should render next.
     *
     * @return {@value #MAPSET_NAME} where this screen re-displays itself, possibly {@code null}
     */
    public String getNextMapset() {
        return nextMapset;
    }

    /**
     * Sets the mapset the client should render next. Stored as given.
     *
     * @param nextMapset the next mapset name; {@code null} is permitted and preserved
     */
    public void setNextMapset(String nextMapset) {
        this.nextMapset = nextMapset;
    }

    /**
     * Returns the map the client should render next.
     *
     * @return {@value #MAP_NAME} where this screen re-displays itself, possibly {@code null}
     */
    public String getNextMap() {
        return nextMap;
    }

    /**
     * Sets the map the client should render next. Stored as given.
     *
     * @param nextMap the next map name; {@code null} is permitted and preserved
     */
    public void setNextMap(String nextMap) {
        this.nextMap = nextMap;
    }

    // =================================================================================================
    // Accessors for the six CDEMO-CB00-INFO members. Five of the six are never read by the program;
    // they are carried, and accessible, because the source declares them.
    // =================================================================================================

    /**
     * Returns {@code CDEMO-CB00-TRNID-FIRST}. Declared by the source and never read by it.
     *
     * @return at most {@value #TRN_ID_FIRST_LENGTH} characters, possibly {@code null}
     */
    public String getTrnIdFirst() {
        return trnIdFirst;
    }

    /**
     * Sets {@code CDEMO-CB00-TRNID-FIRST}. Stored as given.
     *
     * @param trnIdFirst the first transaction identifier of a page, at most
     *                   {@value #TRN_ID_FIRST_LENGTH} characters; {@code null} is permitted
     */
    public void setTrnIdFirst(String trnIdFirst) {
        this.trnIdFirst = trnIdFirst;
    }

    /**
     * Returns {@code CDEMO-CB00-TRNID-LAST}. Declared by the source and never read by it.
     *
     * @return at most {@value #TRN_ID_LAST_LENGTH} characters, possibly {@code null}
     */
    public String getTrnIdLast() {
        return trnIdLast;
    }

    /**
     * Sets {@code CDEMO-CB00-TRNID-LAST}. Stored as given.
     *
     * @param trnIdLast the last transaction identifier of a page, at most
     *                  {@value #TRN_ID_LAST_LENGTH} characters; {@code null} is permitted
     */
    public void setTrnIdLast(String trnIdLast) {
        this.trnIdLast = trnIdLast;
    }

    /**
     * Returns {@code CDEMO-CB00-PAGE-NUM}. Declared by the source and never read by it.
     *
     * @return the page number, at most {@value #PAGE_NUM_DIGITS} digits, zero unless assigned
     */
    public int getPageNum() {
        return pageNum;
    }

    /**
     * Sets {@code CDEMO-CB00-PAGE-NUM}. Stored as given, with no range check: the
     * {@value #PAGE_NUM_DIGITS}-digit picture is a storage width rather than a validated constraint,
     * and the source performs no such check.
     *
     * @param pageNum the page number, at most {@value #PAGE_NUM_DIGITS} digits
     */
    public void setPageNum(int pageNum) {
        this.pageNum = pageNum;
    }

    /**
     * Returns {@code CDEMO-CB00-NEXT-PAGE-FLG}. Declared by the source and never read by it.
     *
     * @return {@value #NEXT_PAGE_YES}, {@value #NEXT_PAGE_NO} or any other single character the
     *         communication area carried; {@value #NEXT_PAGE_NO} unless assigned
     */
    public String getNextPageFlg() {
        return nextPageFlg;
    }

    /**
     * Sets {@code CDEMO-CB00-NEXT-PAGE-FLG}. Stored as given, including a value matching neither
     * condition name, because two {@code 88}-level tests in COBOL are independent rather than
     * exhaustive.
     *
     * @param nextPageFlg the flag, at most {@value #NEXT_PAGE_FLG_LENGTH} character;
     *                    {@code null} is permitted and preserved
     */
    public void setNextPageFlg(String nextPageFlg) {
        this.nextPageFlg = nextPageFlg;
    }

    /**
     * Returns {@code CDEMO-CB00-TRN-SEL-FLG}. Declared by the source and never read by it, and
     * carrying no condition name.
     *
     * @return at most {@value #TRN_SEL_FLG_LENGTH} character, possibly {@code null}
     */
    public String getTrnSelFlg() {
        return trnSelFlg;
    }

    /**
     * Sets {@code CDEMO-CB00-TRN-SEL-FLG}. Stored as given.
     *
     * @param trnSelFlg the selection flag, at most {@value #TRN_SEL_FLG_LENGTH} character;
     *                  {@code null} is permitted and preserved
     */
    public void setTrnSelFlg(String trnSelFlg) {
        this.trnSelFlg = trnSelFlg;
    }

    /**
     * Returns {@code CDEMO-CB00-TRN-SELECTED} - the one member of the extension the program reads,
     * at lines 116-119, to seed the account field when another screen navigated here with an account
     * already chosen.
     *
     * @return at most {@value #TRN_SELECTED_LENGTH} characters, possibly {@code null}
     */
    public String getTrnSelected() {
        return trnSelected;
    }

    /**
     * Sets {@code CDEMO-CB00-TRN-SELECTED}. Stored as given - in particular an all-blank value and
     * {@code null} stay distinct, because the source's test at lines 116-117 excludes {@code SPACES}
     * and {@code LOW-VALUES} separately.
     *
     * @param trnSelected the selected identifier, at most {@value #TRN_SELECTED_LENGTH} characters;
     *                    {@code null} is permitted and preserved
     */
    public void setTrnSelected(String trnSelected) {
        this.trnSelected = trnSelected;
    }

    // =================================================================================================
    // Diagnostics.
    // =================================================================================================

    /**
     * Returns a single-line rendering naming every member of this response.
     *
     * <p>Built by straight concatenation, which keeps the method free of any decision point. A
     * {@code null} member renders as {@code null} and {@link #getNavigationContext()} interpolates
     * that carrier's own rendering, which applies whatever masking the carrier defines - that masking
     * belongs to the carrier, is applied consistently wherever the carrier appears, and is neither
     * repeated nor undone here.
     *
     * <p>{@link #getActIdIn()} is rendered in the clear, deliberately. The 3270 screen displays the
     * account identifier plainly at {@code (6,21)} under a {@code 'Enter Acct ID:'} label, this is a
     * like-for-like migration, and the module's disclosure guard classifies the sensitive components
     * by exact name - cardholder numbers, security codes, national identifiers, names and passwords -
     * none of which this screen carries. Masking it would be an unrequested change to observable
     * behaviour, and it is the field a bill-payment parity failure is diagnosed from.
     *
     * <p>The output is a diagnostic aid and not a wire format: the JSON body is produced by the
     * module's shared object-mapper configuration, and nothing derives from this string.
     *
     * @return a single-line rendering naming every member, never {@code null}
     */
    @Override
    public String toString() {
        return "BillPaymentResponse[trnName=" + trnName
                + ", title01=" + title01
                + ", curDate=" + curDate
                + ", pgmName=" + pgmName
                + ", title02=" + title02
                + ", curTime=" + curTime
                + ", actIdIn=" + actIdIn
                + ", curBal=" + curBal
                + ", confirm=" + confirm
                + ", errMsg=" + errMsg
                + ", cursorField=" + cursorField
                + ", messageHighlight=" + messageHighlight
                + ", navigationContext=" + navigationContext
                + ", nextProgram=" + nextProgram
                + ", nextMapset=" + nextMapset
                + ", nextMap=" + nextMap
                + ", trnIdFirst=" + trnIdFirst
                + ", trnIdLast=" + trnIdLast
                + ", pageNum=" + pageNum
                + ", nextPageFlg=" + nextPageFlg
                + ", trnSelFlg=" + trnSelFlg
                + ", trnSelected=" + trnSelected
                + "]";
    }
}
