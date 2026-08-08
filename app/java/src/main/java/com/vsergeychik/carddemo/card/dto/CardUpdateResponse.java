package com.vsergeychik.carddemo.card.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CardDetails;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CardUpdateRecord;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CommArea;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.DetailGroup;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import jakarta.validation.constraints.Size;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The outbound payload of {@code PUT /api/cards/{cardNum}}: a field-for-field projection of the
 * <em>output</em> half of the card-update screen, CICS transaction {@code CCUP}, backed by
 * {@code app/cbl/COCRDUPC.cbl} (1,560 lines).
 *
 * <h2>Where every field comes from</h2>
 *
 * <p>Two files are the authoritative contract and neither is ever written by this migration
 * (practice <strong>B3</strong>):
 *
 * <ul>
 *   <li>{@code app/cpy-bms/COCRDUP.CPY} (224 lines) - the symbolic map. Its second group,
 *       {@code 01 CCRDUPAO REDEFINES CCRDUPAI.} at <strong>L121</strong>, is the output view and is
 *       the only source of this type's payload fields.</li>
 *   <li>{@code app/bms/COCRDUP.bms} (172 lines) - the mapset. It declares <strong>34</strong>
 *       {@code DFHMDF} field definitions of which exactly <strong>17 carry a name</strong>; the
 *       other 17 are unnamed literal or {@code LENGTH=0} screen furniture and get no Java field at
 *       all. The 17 names, in mapset order, are {@code TRNNAME TITLE01 CURDATE PGMNAME TITLE02
 *       CURTIME ACCTSID CARDSID CRDNAME CRDSTCD EXPMON EXPYEAR EXPDAY INFOMSG ERRMSG FKEYS
 *       FKEYSC}, and they appear here in that same order.</li>
 * </ul>
 *
 * <p>That is the whole of gate <strong>G9</strong>: every payload member below traces to a
 * name-labelled {@code DFHMDF} entry, and every width traces to a symbolic-map {@code PICTURE}
 * clause. No width is inferred, defaulted or shared with another screen.
 *
 * <h2>The output-group projection rule</h2>
 *
 * <p>{@code CCRDUPAO} redefines {@code CCRDUPAI}, so the two groups occupy the same
 * {@value #GROUP_LENGTH} bytes and describe them differently. Per field the input group declares
 * {@code xxxL COMP PIC S9(4)} (2 bytes) + {@code xxxF PICTURE X} (1 byte, with {@code xxxA} as a
 * zero-width {@code REDEFINES} overlay) + {@code FILLER PICTURE X(4)} + {@code xxxI PIC X(n)}; the
 * output group declares {@code FILLER PICTURE X(3)} + {@code xxxC} + {@code xxxP} + {@code xxxH} +
 * {@code xxxV} (four single bytes) + {@code xxxO PIC X(n)}. Both strides are therefore
 * {@code 7 + n}, which is what makes the redefinition legal.
 *
 * <p>The consequences are exact and they are the design of this class:
 *
 * <ul>
 *   <li><strong>Payload fields come from the {@code xxxO} items only.</strong> The {@code xxxI}
 *       items are the inbound half and belong to {@code CardUpdateRequest}. The two types carry the
 *       same 17 names at the same 17 widths - they are symmetric in field set and width, and
 *       asymmetric only in which item they read.</li>
 *   <li>The 3-byte {@code FILLER} overlays the input's {@code xxxL} (2) plus {@code xxxF} (1). The
 *       {@code xxxC}/{@code xxxP}/{@code xxxH}/{@code xxxV} quad overlays the input's
 *       {@code FILLER X(4)}, and it is the {@code DFHMDI}-declared
 *       {@code DSATTS}/{@code MAPATTS=(COLOR,HILIGHT,PS,VALIDN)} set laid out in <em>byte</em>
 *       order: <strong>C = COLOR, P = PS, H = HILIGHT, V = VALIDN</strong>. Note that the byte order
 *       is not the order the {@code DSATTS} operand lists.</li>
 *   <li>The quad is <strong>attribute metadata, never a JSON payload member</strong>. It is modelled
 *       as addressable, {@link JsonIgnore}-excluded state so the {@code CSSETATY} rule has a target,
 *       while the JSON wire stays limited to the 17 payload members and the state carriers.</li>
 * </ul>
 *
 * <h2>The {@code FKEYSC} / {@code FKEYSCC} collision - read this before editing</h2>
 *
 * <p>This map contains a name collision that exists <strong>only in the output group</strong> and
 * that defeats any mechanical suffix-stripping mapper:
 *
 * <ul>
 *   <li>{@code 02 FKEYSC PICTURE X.} at <strong>L214</strong> is <em>not</em> a field. It is the
 *       one-byte <strong>colour</strong> item of field {@code FKEYS}, whose quad is
 *       {@code FKEYSC}/{@code FKEYSP}/{@code FKEYSH}/{@code FKEYSV} and whose payload is
 *       {@code FKEYSO PIC X(21)} at L218.</li>
 *   <li>{@code 02 FKEYSCC PICTURE X.} at <strong>L220</strong> is the colour item of the field
 *       actually named {@code FKEYSC}, whose quad is
 *       {@code FKEYSCC}/{@code FKEYSCP}/{@code FKEYSCH}/{@code FKEYSCV} and whose payload is
 *       {@code FKEYSCO PIC X(18)} at L224.</li>
 * </ul>
 *
 * <p>So the token {@code FKEYSC} denotes a <em>field</em> in the input group (its items
 * {@code FKEYSCL}/{@code FKEYSCF}/{@code FKEYSCA}/{@code FKEYSCI} sit at L115-L120) and a
 * <em>colour byte</em> in the output group. The mapset confirms both are real screen fields: its
 * name-labelled list ends {@code ... FKEYS FKEYSC}, at {@code LENGTH=21} with
 * {@code INITIAL='ENTER=Process F3=Exit'} and {@code LENGTH=18} with
 * {@code INITIAL='F5=Save F12=Cancel'} respectively. A suffix-stripping mapper would collapse the
 * 18-byte field into {@code FKEYS} <em>and</em> bind the colour byte to the wrong owner. This class
 * therefore keeps {@code FKEYS}/{@code FKEYSO} (21) and {@code FKEYSC}/{@code FKEYSCO} (18) as two
 * independent fields with two independent quads, addressable separately by prefix - see
 * {@link #attributesOf(String)} and {@link #applyHighlight(FieldHighlight)}. Documented rather than
 * quietly absorbed, per practice <strong>B12</strong>.
 *
 * <h2>Widths are per screen and are never unified</h2>
 *
 * <p>{@code INFOMSG}/{@code ERRMSG} are 40/80 here and in {@code COCRDSL} but 45/78 in
 * {@code COCRDLI}; {@code FKEYS} is 21 here, 75 in {@code COCRDSL} and absent from
 * {@code COCRDLI}; {@code EXPDAY} exists in this map alone. There is consequently no shared base
 * type across the three card screens, and none may be introduced: a common superclass could only be
 * built by picking one screen's widths, which is a parity defect by construction.
 *
 * <h2>{@code XCTL} becomes a response field (gate G40)</h2>
 *
 * <p>{@code app/cbl/COCRDUPC.cbl:473-476} performs
 * {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)}. There is no server
 * side transfer of control in the Java form: the response names the next target through
 * {@link #getNextProgram()}, {@link #getNextMapset()} and {@link #getNextMap()}, and the client
 * issues the follow-up call. No forward, no redirect chain, no session affinity. The three tokens
 * are opaque - 8 characters for a program and 7 for a mapset or map, matching
 * {@link CardScreenState#CCARD_NEXT_PROG_LENGTH}, {@link CardScreenState#CCARD_NEXT_MAPSET_LENGTH}
 * and {@link CardScreenState#CCARD_NEXT_MAP_LENGTH} - and they are neither validated against a
 * known-program list, nor case-normalised, nor trimmed. A map name is 7 characters because the
 * symbolic group is a 7-character map name plus an {@code I} or {@code O} suffix:
 * {@code CCRDUPA} yields {@code CCRDUPAI} and {@code CCRDUPAO}.
 *
 * <h2>Statelessness (rule R6, gate G37)</h2>
 *
 * <p>Every scrap of CICS pseudo-conversational state travels in the payload: the 329-byte
 * {@code WS-THIS-PROGCOMMAREA} as {@link #getCommArea()}, the {@code CVCRD01Y} work area as
 * {@link #getCardScreenState()} and the {@code COCOM01Y} navigation commarea as
 * {@link #getNavigationContext()}. This class holds no {@code HttpSession}, no
 * {@code @SessionAttributes}, no server-side cache, no static map and no {@code ThreadLocal}, and it
 * never will.
 *
 * <p>All three carriers use the same member name on the request and on the response -
 * {@code commArea}, {@code cardScreenState} and {@code navigationContext} - and, for the program
 * commarea, the same Java type. A client echoes what it received straight back; it never transcribes
 * one shape into another. See the closing comment of this file for why the response no longer
 * declares its own copy of the 329-byte area.
 *
 * <h2>The 329-byte program commarea, decomposed</h2>
 *
 * <p>{@code app/cbl/COCRDUPC.cbl:274-321} declares {@code 01 WS-THIS-PROGCOMMAREA} as four
 * {@code 05}-level groups. It is echoed on the response so the client can send it back unchanged on
 * the next call:
 *
 * <pre>
 *   offset  bytes  group / item                        source line
 *   ------  -----  ---------------------------------   -----------
 *        0      1  CARD-UPDATE-SCREEN-DATA                L275
 *                    CCUP-CHANGE-ACTION PIC X(1)       L276-L290
 *        1     89  CCUP-OLD-DETAILS                    L291-L301
 *       90     89  CCUP-NEW-DETAILS                    L303-L313
 *      179    150  CARD-UPDATE-RECORD                  L314-L321
 *   ------  -----
 *      329    329  = 1 + 89 + 89 + 150
 * </pre>
 *
 * <p>{@link CommArea#LAYOUT} declares every one of those bytes, {@code FILLER} included, and
 * {@link RecordLayout} refuses to exist unless the spans are contiguous from offset 0 and sum to
 * exactly {@value CommArea#RECORD_LENGTH}. The arithmetic above is therefore machine-checked
 * at class-initialisation time rather than asserted in prose.
 *
 * <h2>Three preserved misspellings</h2>
 *
 * <p>{@code CCUP-OLD-EXPIRAION-DATE} (L297), {@code CCUP-NEW-EXPIRAION-DATE} (L309) and
 * {@code CARD-UPDATE-EXPIRAION-DATE} (L319) are all misspelled in the source, mirroring
 * {@code CARD-EXPIRAION-DATE} in {@code app/cpy/CVACT02Y.cpy:9}. Under AAP &sect;0.1.4
 * <strong>I1</strong> a field name is part of the contract that field-for-field diffing compares,
 * so all three are carried verbatim. The correctly spelled form appears nowhere in this file, and
 * introducing it would make a real difference invisible.
 *
 * <h2>A correction to AAP &sect;0.6.2, recorded rather than restated (practice B4)</h2>
 *
 * <p>&sect;0.6.2 summarises all 17 mapsets as declaring
 * {@code DFHMSD CTRL=(ALARM,FREEKB) EXTATT=YES ...}. For this mapset that is not what the source
 * says. {@code app/bms/COCRDUP.bms:20-24} declares
 * {@code COCRDUP DFHMSD LANG=COBOL, MODE=INOUT, STORAGE=AUTO, TIOAPFX=YES, TYPE=&&SYSPARM} - with
 * <strong>no {@code CTRL=}</strong> and <strong>no {@code EXTATT=}</strong>. The keyboard control
 * and the extended attributes are declared one level down, on the map:
 * {@code app/bms/COCRDUP.bms:25-28} declares
 * {@code CCRDUPA DFHMDI CTRL=(FREEKB), DSATTS=(COLOR,HILIGHT,PS,VALIDN),
 * MAPATTS=(COLOR,HILIGHT,PS,VALIDN), SIZE=(24,80)}. The {@code SIZE=(24,80)} in &sect;0.6.2 is
 * confirmed; {@code ALARM} is not declared for this mapset at any level. The discrepancy is
 * documented here, not silently corrected elsewhere.
 *
 * <h2>Construction and mutability</h2>
 *
 * <p>A plain class with a public no-argument constructor and hand-written accessors: no Lombok, no
 * MapStruct, no builder, no Spring context. A controller test, a {@code CardUpdateService} unit test
 * and the {@code COCRDUPC} parity test all construct and inspect instances directly (practice
 * <strong>B10</strong>). It is deliberately <em>mutable</em>, because
 * {@code app/cpy/CSSETATY.cpy} and the inlined equivalent at {@code COCRDUPC.cbl:1238-1307} move
 * colour bytes and asterisks into an already-populated map. Genuinely immutable sub-values -
 * the commarea groups and the embedded card record - are {@code record}s.
 *
 * <p>A fresh instance holds {@code LOW-VALUES} in all 17 payload fields and {@code 0x00} in all 68
 * attribute bytes, which is exactly the state {@code 3100-SCREEN-INIT} establishes with
 * {@code MOVE LOW-VALUES TO CCRDUPAO} at {@code app/cbl/COCRDUPC.cbl:1053} - the first statement of
 * the send path. {@code LOW-VALUES}, {@code SPACES} and Java {@code null} are three different
 * things here and are never conflated; {@code null} is rejected wherever a value is expected,
 * because COBOL has no absent state.
 *
 * <h2>What this class deliberately does not do</h2>
 *
 * <ul>
 *   <li><strong>No input validation.</strong> {@code COCRDUPC} owns its own edits in paragraphs
 *       {@code 1210} through {@code 1260} together with their message ordering, so no
 *       {@code @NotBlank}, {@code @Pattern} or range constraint appears here. The
 *       {@link Size} annotations state the {@code xxxO} declared widths and nothing more; every
 *       setter already normalises to exactly that width, so they are documentation of an invariant
 *       rather than a check that can fail.</li>
 *   <li><strong>No persistence.</strong> No {@code @Entity}, {@code @Table}, {@code @Column},
 *       {@code @Id} or {@code @Version}, and no JPA import (gate <strong>G44</strong>). The embedded
 *       {@link CardUpdateRecord} is a byte-layout value, not a row. Optimistic concurrency is
 *       {@code COCRDUPC}'s own {@code 9300-CHECK-CHANGE-IN-REC} field comparison, performed in
 *       {@code CardUpdateService}, never a version column (gate <strong>G43</strong>).</li>
 *   <li><strong>No arithmetic.</strong> This screen has no scaled or monetary field, so there is no
 *       {@code BigDecimal} and no rounding of any kind - gate <strong>G24</strong> is met by
 *       absence, and gate <strong>G22</strong> by there being no {@code double} or {@code float}
 *       anywhere.</li>
 *   <li><strong>No masking.</strong> {@code CARDSIDO} carries a full 16-digit card number and the
 *       commarea carries the CVV in the clear, exactly as the COBOL does. Redaction,
 *       {@code @JsonIgnore} on a payload field or a sanitising {@code toString()} would all be
 *       behaviour changes; none is added, and no new exposure is added either (practice
 *       <strong>B6</strong>).</li>
 *   <li><strong>No shared message text.</strong> The AAP directs standard info and error texts to
 *       come from {@link SystemMessages}, which carries the {@code CSMSG01Y} literals. This program
 *       uses none of them: {@code INFOMSGO} is fed from {@code WS-INFO-MSG PIC X(40)} and its six
 *       {@code 88}-level literals at {@code COCRDUPC.cbl:157-172}, and {@code ERRMSGO} from
 *       {@code WS-RETURN-MSG PIC X(75)} and its literals from {@code COCRDUPC.cbl:173} onwards,
 *       both program-local and both owned by {@code CardUpdateController}. Substituting a shared
 *       text would emit a message this transaction never emits, so the divergence is recorded here
 *       instead (practices <strong>B4</strong> and <strong>B5</strong>). Note that
 *       {@code WS-RETURN-MSG} is 75 characters and {@code ERRMSGO} is 80: that move pads, and it is
 *       why {@link #setErrmsgo(String)} routes through the {@code PIC X} rule rather than assigning
 *       (practice <strong>B11</strong>).</li>
 * </ul>
 *
 * @see CardScreenState
 * @see NavigationContext
 * @see FieldAttributeSetter
 * @see FixedWidthCodec
 */
public final class CardUpdateResponse {

    // =================================================================================================
    // Identity of the screen this payload projects. Named constants rather than inline literals, so a
    // caller never spells a map or mapset name by hand (practice B8).
    // =================================================================================================

    /** The mapset name, {@code app/bms/COCRDUP.bms:20}. */
    public static final String MAPSET_NAME = "COCRDUP";

    /** The map name, {@code app/bms/COCRDUP.bms:25}; seven characters, as BMS requires. */
    public static final String MAP_NAME = "CCRDUPA";

    /** The input symbolic group, {@code app/cpy-bms/COCRDUP.CPY:17}: {@link #MAP_NAME} plus {@code I}. */
    public static final String INPUT_GROUP_NAME = MAP_NAME + "I";

    /**
     * The output symbolic group, {@code app/cpy-bms/COCRDUP.CPY:121}: {@link #MAP_NAME} plus
     * {@code O}. This is the group this type projects, and the group both {@code CSSETATY} moves
     * qualify their targets by.
     */
    public static final String OUTPUT_GROUP_NAME = MAP_NAME + "O";

    /** The CSD transaction that runs this screen, {@code app/csd/CARDDEMO.CSD}. */
    public static final String TRANSACTION_ID = "CCUP";

    /** The COBOL program behind it, {@code app/cbl/COCRDUPC.cbl}. */
    public static final String PROGRAM_NAME = "COCRDUPC";

    // =================================================================================================
    // Declared geometry of 01 CCRDUPAO. Every width is a named constant carrying the line of the xxxO
    // item it comes from, so no payload width is ever written as a bare number (practice B8, gate G9).
    // =================================================================================================

    /**
     * The {@code DFHMDF} entries {@code app/bms/COCRDUP.bms} declares in total: <strong>34</strong>.
     * Exactly {@link #NAMED_FIELD_COUNT} of them carry a name; the remaining 17 are unnamed literal
     * or {@code LENGTH=0} furniture and are deliberately not modelled.
     */
    public static final int DFHMDF_TOTAL_COUNT = 34;

    /** The name-labelled {@code DFHMDF} entries, and therefore the payload members here: 17. */
    public static final int NAMED_FIELD_COUNT = 17;

    /**
     * The {@code 02 FILLER PIC X(12)} that opens both groups
     * ({@code app/cpy-bms/COCRDUP.CPY:18} and {@code :122}).
     *
     * <p>It is the {@code TIOAPFX=YES} prefix declared at {@code app/bms/COCRDUP.bms:23}: CICS
     * reserves twelve bytes ahead of the first field for its own use. Omitting it would shift every
     * subsequent offset by twelve.
     */
    public static final int TIOAPFX_LENGTH = 12;

    /**
     * The {@code 02 FILLER PICTURE X(3)} that precedes each field's attribute quad in the output
     * group, for example {@code app/cpy-bms/COCRDUP.CPY:123}.
     *
     * <p>These three bytes overlay the input group's {@code xxxL COMP PIC S9(4)} (two bytes) plus
     * {@code xxxF PICTURE X} (one byte).
     */
    public static final int FIELD_PREFIX_FILLER_LENGTH = 3;

    /**
     * The four one-byte attribute items {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV}
     * that precede each {@code xxxO} payload item.
     *
     * <p>They overlay the input group's {@code 02 FILLER PICTURE X(4)} and correspond to the
     * {@code DSATTS}/{@code MAPATTS=(COLOR,HILIGHT,PS,VALIDN)} set declared at
     * {@code app/bms/COCRDUP.bms:26-27}. Their <em>byte</em> order is C, P, H, V - colour, programmed
     * symbols, highlight, validation - which is not the order the operand lists them in.
     */
    public static final int ATTRIBUTE_QUAD_LENGTH = 4;

    /**
     * The bytes each field costs on top of its payload width in the output group:
     * {@value #FIELD_PREFIX_FILLER_LENGTH} + {@value #ATTRIBUTE_QUAD_LENGTH} = 7.
     *
     * <p>The input group's per-field overhead is 2 + 1 + 4, also 7, which is precisely why
     * {@code 01 CCRDUPAO} can redefine {@code 01 CCRDUPAI}.
     */
    public static final int ITEM_OVERHEAD_LENGTH = FIELD_PREFIX_FILLER_LENGTH + ATTRIBUTE_QUAD_LENGTH;

    /** {@code TRNNAMEO PIC X(4)}, {@code app/cpy-bms/COCRDUP.CPY:128} - the transaction identifier. */
    public static final int TRNNAMEO_LENGTH = 4;

    /** {@code TITLE01O PIC X(40)}, {@code app/cpy-bms/COCRDUP.CPY:134} - the upper heading line. */
    public static final int TITLE01O_LENGTH = 40;

    /** {@code CURDATEO PIC X(8)}, {@code app/cpy-bms/COCRDUP.CPY:140} - {@code mm/dd/yy}. */
    public static final int CURDATEO_LENGTH = 8;

    /** {@code PGMNAMEO PIC X(8)}, {@code app/cpy-bms/COCRDUP.CPY:146} - the program name. */
    public static final int PGMNAMEO_LENGTH = 8;

    /** {@code TITLE02O PIC X(40)}, {@code app/cpy-bms/COCRDUP.CPY:152} - the lower heading line. */
    public static final int TITLE02O_LENGTH = 40;

    /** {@code CURTIMEO PIC X(8)}, {@code app/cpy-bms/COCRDUP.CPY:158} - {@code hh:mm:ss}. */
    public static final int CURTIMEO_LENGTH = 8;

    /** {@code ACCTSIDO PIC X(11)}, {@code app/cpy-bms/COCRDUP.CPY:164} - the account number. */
    public static final int ACCTSIDO_LENGTH = 11;

    /**
     * {@code CARDSIDO PIC X(16)}, {@code app/cpy-bms/COCRDUP.CPY:170} - the card number, carried in
     * full and unmasked exactly as the COBOL sends it (practice <strong>B6</strong>).
     */
    public static final int CARDSIDO_LENGTH = 16;

    /** {@code CRDNAMEO PIC X(50)}, {@code app/cpy-bms/COCRDUP.CPY:176} - the embossed name. */
    public static final int CRDNAMEO_LENGTH = 50;

    /** {@code CRDSTCDO PIC X(1)}, {@code app/cpy-bms/COCRDUP.CPY:182} - the active status, Y or N. */
    public static final int CRDSTCDO_LENGTH = 1;

    /** {@code EXPMONO PIC X(2)}, {@code app/cpy-bms/COCRDUP.CPY:188} - the expiry month. */
    public static final int EXPMONO_LENGTH = 2;

    /** {@code EXPYEARO PIC X(4)}, {@code app/cpy-bms/COCRDUP.CPY:194} - the expiry year. */
    public static final int EXPYEARO_LENGTH = 4;

    /**
     * {@code EXPDAYO PIC X(2)}, {@code app/cpy-bms/COCRDUP.CPY:200} - the expiry day.
     *
     * <p>Unique to this map: no other card screen declares it. Its {@code DFHMDF} at
     * {@code app/bms/COCRDUP.bms:142-146} is {@code ATTRB=(DRK,FSET,PROT)}, a non-display protected
     * field, and {@code COCRDUPC.cbl:1284} moves {@link BmsAttributes#DFHBMDAR} into its colour item
     * rather than a colour, which is why the quad is modelled as a raw byte and not an enumeration of
     * colours.
     */
    public static final int EXPDAYO_LENGTH = 2;

    /**
     * {@code INFOMSGO PIC X(40)}, {@code app/cpy-bms/COCRDUP.CPY:206} - the information line.
     *
     * <p>Fed from {@code WS-INFO-MSG PIC X(40)} at {@code app/cbl/COCRDUPC.cbl:1161}, an exact-width
     * move. 40 here and in {@code COCRDSL}, but 45 in {@code COCRDLI}: never unify them.
     */
    public static final int INFOMSGO_LENGTH = 40;

    /**
     * {@code ERRMSGO PIC X(80)}, {@code app/cpy-bms/COCRDUP.CPY:212} - the error line.
     *
     * <p>Fed from {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COCRDUPC.cbl:1163}, so that move
     * pads five bytes on the right. 80 here and in {@code COCRDSL}, but 78 in {@code COCRDLI}.
     */
    public static final int ERRMSGO_LENGTH = 80;

    /**
     * {@code FKEYSO PIC X(21)}, {@code app/cpy-bms/COCRDUP.CPY:218} - the first function-key line,
     * {@code INITIAL='ENTER=Process F3=Exit'} at {@code app/bms/COCRDUP.bms:158-162}.
     *
     * <p>Its colour item is {@code FKEYSC} at {@code app/cpy-bms/COCRDUP.CPY:214}, which is
     * <em>not</em> the field {@code FKEYSC}. See the class documentation.
     */
    public static final int FKEYSO_LENGTH = 21;

    /**
     * {@code FKEYSCO PIC X(18)}, {@code app/cpy-bms/COCRDUP.CPY:224} - the second function-key line,
     * {@code INITIAL='F5=Save F12=Cancel'} at {@code app/bms/COCRDUP.bms:163-167}.
     *
     * <p>This is the payload of the field named {@code FKEYSC}; its own colour item is
     * {@code FKEYSCC} at {@code app/cpy-bms/COCRDUP.CPY:220}.
     */
    public static final int FKEYSCO_LENGTH = 18;

    /**
     * The 17 payload widths added up: <strong>353</strong> bytes.
     *
     * <p>4 + 40 + 8 + 8 + 40 + 8 + 11 + 16 + 50 + 1 + 2 + 4 + 2 + 40 + 80 + 21 + 18. Written as the
     * sum of the named constants so the total cannot drift from the parts.
     */
    public static final int PAYLOAD_LENGTH = TRNNAMEO_LENGTH + TITLE01O_LENGTH + CURDATEO_LENGTH
            + PGMNAMEO_LENGTH + TITLE02O_LENGTH + CURTIMEO_LENGTH + ACCTSIDO_LENGTH
            + CARDSIDO_LENGTH + CRDNAMEO_LENGTH + CRDSTCDO_LENGTH + EXPMONO_LENGTH
            + EXPYEARO_LENGTH + EXPDAYO_LENGTH + INFOMSGO_LENGTH + ERRMSGO_LENGTH + FKEYSO_LENGTH
            + FKEYSCO_LENGTH;

    /**
     * The whole symbolic group: <strong>484</strong> bytes =
     * {@value #TIOAPFX_LENGTH} + 17 &times; {@value #ITEM_OVERHEAD_LENGTH} +
     * {@value #PAYLOAD_LENGTH}.
     *
     * <p>{@code 01 CCRDUPAI} sums to the same 484 by the same stride, which is the condition
     * {@code 01 CCRDUPAO REDEFINES CCRDUPAI} requires. {@link #OUTPUT_GROUP_LAYOUT} proves this
     * arithmetic mechanically at class-initialisation time.
     */
    public static final int GROUP_LENGTH =
            TIOAPFX_LENGTH + NAMED_FIELD_COUNT * ITEM_OVERHEAD_LENGTH + PAYLOAD_LENGTH;

    // =================================================================================================
    // The 17 DFHMDF labels. These are the keys of every name-addressed operation, and they are the
    // (SCRNVAR2) tokens app/cpy/CSSETATY.cpy substitutes.
    // =================================================================================================

    /** {@code DFHMDF} label {@code TRNNAME}, {@code app/bms/COCRDUP.bms:34}. */
    public static final String TRNNAME = "TRNNAME";

    /** {@code DFHMDF} label {@code TITLE01}, {@code app/bms/COCRDUP.bms:38}. */
    public static final String TITLE01 = "TITLE01";

    /** {@code DFHMDF} label {@code CURDATE}, {@code app/bms/COCRDUP.bms:47}. */
    public static final String CURDATE = "CURDATE";

    /** {@code DFHMDF} label {@code PGMNAME}, {@code app/bms/COCRDUP.bms:57}. */
    public static final String PGMNAME = "PGMNAME";

    /** {@code DFHMDF} label {@code TITLE02}, {@code app/bms/COCRDUP.bms:61}. */
    public static final String TITLE02 = "TITLE02";

    /** {@code DFHMDF} label {@code CURTIME}, {@code app/bms/COCRDUP.bms:70}. */
    public static final String CURTIME = "CURTIME";

    /** {@code DFHMDF} label {@code ACCTSID}, {@code app/bms/COCRDUP.bms:84}. */
    public static final String ACCTSID = "ACCTSID";

    /** {@code DFHMDF} label {@code CARDSID}, {@code app/bms/COCRDUP.bms:96}. */
    public static final String CARDSID = "CARDSID";

    /** {@code DFHMDF} label {@code CRDNAME}, {@code app/bms/COCRDUP.bms:107}. */
    public static final String CRDNAME = "CRDNAME";

    /** {@code DFHMDF} label {@code CRDSTCD}, {@code app/bms/COCRDUP.bms:117}. */
    public static final String CRDSTCD = "CRDSTCD";

    /** {@code DFHMDF} label {@code EXPMON}, {@code app/bms/COCRDUP.bms:127}. */
    public static final String EXPMON = "EXPMON";

    /** {@code DFHMDF} label {@code EXPYEAR}, {@code app/bms/COCRDUP.bms:135}. */
    public static final String EXPYEAR = "EXPYEAR";

    /** {@code DFHMDF} label {@code EXPDAY}, {@code app/bms/COCRDUP.bms:142}. */
    public static final String EXPDAY = "EXPDAY";

    /** {@code DFHMDF} label {@code INFOMSG}, {@code app/bms/COCRDUP.bms:149}. */
    public static final String INFOMSG = "INFOMSG";

    /** {@code DFHMDF} label {@code ERRMSG}, {@code app/bms/COCRDUP.bms:154}. */
    public static final String ERRMSG = "ERRMSG";

    /**
     * {@code DFHMDF} label {@code FKEYS}, {@code app/bms/COCRDUP.bms:158} - 21 bytes.
     *
     * <p>Distinct from {@link #FKEYSC} in every respect: different width, different payload item,
     * different attribute quad. Its colour item happens to be spelled {@code FKEYSC}.
     */
    public static final String FKEYS = "FKEYS";

    /**
     * {@code DFHMDF} label {@code FKEYSC}, {@code app/bms/COCRDUP.bms:163} - 18 bytes.
     *
     * <p>A real field, despite its name being identical to {@link #FKEYS}'s colour item. Its own
     * colour item is {@code FKEYSCC}. Addressing this field and {@link #FKEYS} independently is the
     * whole reason this class is keyed by declared name rather than by stripped suffix.
     */
    public static final String FKEYSC = "FKEYSC";

    // =================================================================================================
    // Attribute item suffixes, taken from the copybook's own spelling.
    // =================================================================================================

    /** Suffix of the colour item, {@code xxxC} - the {@code COLOR} attribute. */
    public static final String COLOUR_ITEM_SUFFIX = "C";

    /** Suffix of the programmed-symbols item, {@code xxxP} - the {@code PS} attribute. */
    public static final String PS_ITEM_SUFFIX = "P";

    /** Suffix of the highlight item, {@code xxxH} - the {@code HILIGHT} attribute. */
    public static final String HILIGHT_ITEM_SUFFIX = "H";

    /** Suffix of the validation item, {@code xxxV} - the {@code VALIDN} attribute. */
    public static final String VALIDN_ITEM_SUFFIX = "V";

    /** Suffix of the payload item, {@code xxxO} - the only item that reaches the JSON wire. */
    public static final String OUTPUT_ITEM_SUFFIX = "O";

    /**
     * The {@code PIC X} pad and truncate rule, held as one shared immutable codec.
     *
     * <p>Only {@link FixedWidthCodec#movePicX(String, int)} is taken from it here, and that is a
     * <em>character-level</em> operation which converts nothing to bytes, so the code page this
     * instance carries takes no part in any result it produces. It is named
     * {@link StandardCharsets#US_ASCII} explicitly and never derived from the platform (practice
     * <strong>B8</strong>).
     *
     * <p>Every genuine byte boundary - {@link #toFixedWidth(Charset)},
     * {@link #writeInto(FixedWidthRecord)} and {@link #readFrom(FixedWidthRecord)} - takes its code
     * page from the caller instead, because a symbolic map image is bytes in a specific code page and
     * this type is bound to neither {@code IBM037} nor {@code US-ASCII}.
     *
     * <p>{@code FixedWidthCodec} is final, immutable and holds only a {@link Charset}, so one shared
     * instance is thread safe and is a constant rather than static mutable state (practice
     * <strong>B9</strong>). Borrowing the rule rather than reimplementing it keeps a single reviewable
     * copy of the pad and truncate directions in the module (practice <strong>B11</strong>).
     */
    private static final FixedWidthCodec PICTURE_RULES = new FixedWidthCodec(StandardCharsets.US_ASCII);

    // =================================================================================================
    // The declaration table. One entry per name-labelled DFHMDF, in copybook order, from which every
    // offset in the group is DERIVED by the stride rule rather than transcribed by hand.
    // =================================================================================================

    /**
     * One name-labelled screen field of {@code 01 CCRDUPAO}, carrying its declared width, the
     * copybook line its {@code xxxO} item sits on, and the absolute offset at which its six-item block
     * begins.
     *
     * <p>Only {@link #name()}, {@link #length()} and {@link #copybookLine()} are transcribed from the
     * source. {@link #fieldOffset()} and every span below are <em>derived</em> from the stride rule
     * {@code 7 + n}, so a transcription slip can shorten or lengthen the group - which
     * {@link RecordLayout} then refuses - but it can never leave a stale offset pointing into the
     * middle of a neighbouring field.
     *
     * <p>The block is laid out as
     * {@code FILLER X(3)} &rarr; {@code xxxC} &rarr; {@code xxxP} &rarr; {@code xxxH} &rarr;
     * {@code xxxV} &rarr; {@code xxxO PIC X(n)}, for example
     * {@code app/cpy-bms/COCRDUP.CPY:123-128} for {@code TRNNAME}.
     *
     * @param name         the {@code DFHMDF} label exactly as declared, for example {@code "FKEYSC"};
     *                     this is the {@code (SCRNVAR2)} token and the key of every name-addressed
     *                     operation
     * @param length       the {@code xxxO} declared width in characters
     * @param copybookLine the line of {@code app/cpy-bms/COCRDUP.CPY} declaring the {@code xxxO} item
     * @param fieldOffset  the absolute 0-based offset of the block's leading {@code FILLER X(3)}
     */
    public record ScreenField(String name, int length, int copybookLine, int fieldOffset) {

        /**
         * Validates the descriptor. A width below one or an offset before the {@code TIOAPFX} prefix
         * is a transcription error, not a screen this class could serve.
         *
         * @throws NullPointerException     if {@code name} is {@code null}
         * @throws IllegalArgumentException if {@code name} is blank, {@code length} is below 1,
         *                                  {@code copybookLine} is below 1, or {@code fieldOffset} is
         *                                  before {@link CardUpdateResponse#TIOAPFX_LENGTH}
         */
        public ScreenField {
            Objects.requireNonNull(name, "A DFHMDF label is required to declare a screen field");
            if (name.isBlank()) {
                throw new IllegalArgumentException("A screen field declares an empty DFHMDF label; "
                        + "only the 17 name-labelled entries of app/bms/COCRDUP.bms are modelled, and "
                        + "the 17 unnamed literal entries must not be");
            }
            if (length < 1) {
                throw new IllegalArgumentException("Screen field '" + name + "' declares a width of "
                        + length + "; every xxxO item of app/cpy-bms/COCRDUP.CPY is PIC X(n) with n "
                        + "of at least 1");
            }
            if (copybookLine < 1) {
                throw new IllegalArgumentException("Screen field '" + name + "' cites copybook line "
                        + copybookLine + "; every xxxO item has a real line in "
                        + "app/cpy-bms/COCRDUP.CPY");
            }
            if (fieldOffset < TIOAPFX_LENGTH) {
                throw new IllegalArgumentException("Screen field '" + name + "' begins at offset "
                        + fieldOffset + ", inside the " + TIOAPFX_LENGTH + "-byte TIOAPFX prefix "
                        + "declared at app/cpy-bms/COCRDUP.CPY:122; the first field begins at offset "
                        + TIOAPFX_LENGTH);
            }
        }

        /**
         * The {@code xxxC} colour item name.
         *
         * @return the colour item name, for example {@code "FKEYSC"} for the field {@code FKEYS}
         */
        public String colourItemName() {
            return name + COLOUR_ITEM_SUFFIX;
        }

        /**
         * The {@code xxxP} programmed-symbols item name.
         *
         * @return the programmed-symbols item name
         */
        public String psItemName() {
            return name + PS_ITEM_SUFFIX;
        }

        /**
         * The {@code xxxH} highlight item name.
         *
         * @return the highlight item name
         */
        public String hilightItemName() {
            return name + HILIGHT_ITEM_SUFFIX;
        }

        /**
         * The {@code xxxV} validation item name.
         *
         * @return the validation item name
         */
        public String validnItemName() {
            return name + VALIDN_ITEM_SUFFIX;
        }

        /**
         * The {@code xxxO} payload item name - the only item that reaches the JSON wire.
         *
         * @return the payload item name, for example {@code "FKEYSCO"}
         */
        public String outputItemName() {
            return name + OUTPUT_ITEM_SUFFIX;
        }

        /**
         * Where this field's six-item block begins, at its leading {@code FILLER PICTURE X(3)}.
         *
         * @return the absolute 0-based offset of the leading {@code FILLER}
         */
        public int prefixFillerOffset() {
            return fieldOffset;
        }

        /**
         * Where the {@code xxxC} colour byte sits, three bytes into the block.
         *
         * @return the absolute 0-based offset of the colour byte
         */
        public int colourItemOffset() {
            return fieldOffset + FIELD_PREFIX_FILLER_LENGTH;
        }

        /**
         * Where the {@code xxxP} programmed-symbols byte sits.
         *
         * @return the absolute 0-based offset of the programmed-symbols byte
         */
        public int psItemOffset() {
            return colourItemOffset() + 1;
        }

        /**
         * Where the {@code xxxH} highlight byte sits.
         *
         * @return the absolute 0-based offset of the highlight byte
         */
        public int hilightItemOffset() {
            return psItemOffset() + 1;
        }

        /**
         * Where the {@code xxxV} validation byte sits.
         *
         * @return the absolute 0-based offset of the validation byte
         */
        public int validnItemOffset() {
            return hilightItemOffset() + 1;
        }

        /**
         * Where the {@code xxxO} payload item sits, seven bytes into the block.
         *
         * @return the absolute 0-based offset of the payload item
         */
        public int outputItemOffset() {
            return fieldOffset + ITEM_OVERHEAD_LENGTH;
        }

        /**
         * Where this field's block ends, which is where the next field's block begins.
         *
         * @return the offset one past this block - {@code fieldOffset + 7 + n}, equal to the next
         *         field's {@link #fieldOffset()}
         */
        public int endOffsetExclusive() {
            return outputItemOffset() + length;
        }

        /**
         * The leading {@code FILLER PICTURE X(3)} span, which overlays the input group's
         * {@code xxxL} and {@code xxxF} items.
         *
         * @return the three-byte filler span
         */
        public FieldSpan prefixFillerSpan() {
            return FieldSpan.filler(prefixFillerOffset(), FIELD_PREFIX_FILLER_LENGTH);
        }

        /**
         * The {@code xxxC} colour span.
         *
         * @return the colour span, one byte wide
         */
        public FieldSpan colourItemSpan() {
            return FieldSpan.alphanumeric(colourItemName(), colourItemOffset(), 1);
        }

        /**
         * The {@code xxxP} programmed-symbols span.
         *
         * @return the programmed-symbols span, one byte wide
         */
        public FieldSpan psItemSpan() {
            return FieldSpan.alphanumeric(psItemName(), psItemOffset(), 1);
        }

        /**
         * The {@code xxxH} highlight span.
         *
         * @return the highlight span, one byte wide
         */
        public FieldSpan hilightItemSpan() {
            return FieldSpan.alphanumeric(hilightItemName(), hilightItemOffset(), 1);
        }

        /**
         * The {@code xxxV} validation span.
         *
         * @return the validation span, one byte wide
         */
        public FieldSpan validnItemSpan() {
            return FieldSpan.alphanumeric(validnItemName(), validnItemOffset(), 1);
        }

        /**
         * The {@code xxxO} payload span, the only span of this block that carries screen content.
         *
         * @return the payload span, {@link #length()} bytes wide
         */
        public FieldSpan outputItemSpan() {
            return FieldSpan.alphanumeric(outputItemName(), outputItemOffset(), length);
        }

        /**
         * Renders this descriptor the way the copybook declares it, for an assertion message or a
         * parity trace.
         *
         * @return for example
         *         {@code "FKEYSCO PIC X(18) at offset 466 (app/cpy-bms/COCRDUP.CPY:224)"}
         */
        public String describe() {
            return outputItemName() + " PIC X(" + length + ") at offset " + outputItemOffset()
                    + " (app/cpy-bms/COCRDUP.CPY:" + copybookLine + ")";
        }
    }

    /**
     * The 17 name-labelled fields of {@code 01 CCRDUPAO} in copybook declaration order.
     *
     * <p>Immutable and shared: {@link ScreenField} is a record of four values, and the list is
     * copied to an unmodifiable one, so this is a constant and not static mutable state (practice
     * <strong>B9</strong>).
     */
    private static final List<ScreenField> FIELDS = declareFields();

    /**
     * The 17 fields indexed by {@code DFHMDF} label, for name-addressed access.
     *
     * <p>Insertion-ordered, so iteration follows the copybook. {@code FKEYS} and {@code FKEYSC} are
     * two separate keys, which is exactly what the collision demands.
     */
    private static final Map<String, ScreenField> FIELDS_BY_NAME = indexByName(FIELDS);

    /**
     * The complete byte geometry of {@code 01 CCRDUPAO REDEFINES CCRDUPAI}: 86 spans covering all
     * {@value #GROUP_LENGTH} bytes with no gap and no overlap.
     *
     * <p>The spans are the opening {@code FILLER X(12)} plus, for each of the 17 fields, its
     * {@code FILLER X(3)}, its four attribute bytes and its {@code xxxO} payload -
     * 1 + 17 &times; 6 = 103 spans in total.
     *
     * <p>{@link RecordLayout} verifies on construction that the spans start at offset 0, are
     * contiguous, declare every byte including {@code FILLER}, repeat no referable name and sum to
     * exactly {@value #GROUP_LENGTH}. A slip in any width constant therefore fails at
     * class-initialisation time with a message naming the offending span, long before a screen image
     * is built. That is the machine check behind the 353 and 484 arithmetic in the class
     * documentation.
     */
    public static final RecordLayout OUTPUT_GROUP_LAYOUT = declareLayout(FIELDS);

    /**
     * Declares the 17 fields in copybook order, deriving each offset from the previous field's end.
     *
     * <p>Reading top to bottom, this method is the copybook: one line per name-labelled
     * {@code DFHMDF}, in the order {@code app/cpy-bms/COCRDUP.CPY} declares the {@code xxxO} items,
     * each carrying the line number it was transcribed from.
     *
     * @return the 17 descriptors, unmodifiable and in declaration order
     */
    private static List<ScreenField> declareFields() {
        List<ScreenField> declared = new ArrayList<>(NAMED_FIELD_COUNT);
        int cursor = TIOAPFX_LENGTH;
        cursor = declare(declared, TRNNAME, TRNNAMEO_LENGTH, 128, cursor);
        cursor = declare(declared, TITLE01, TITLE01O_LENGTH, 134, cursor);
        cursor = declare(declared, CURDATE, CURDATEO_LENGTH, 140, cursor);
        cursor = declare(declared, PGMNAME, PGMNAMEO_LENGTH, 146, cursor);
        cursor = declare(declared, TITLE02, TITLE02O_LENGTH, 152, cursor);
        cursor = declare(declared, CURTIME, CURTIMEO_LENGTH, 158, cursor);
        cursor = declare(declared, ACCTSID, ACCTSIDO_LENGTH, 164, cursor);
        cursor = declare(declared, CARDSID, CARDSIDO_LENGTH, 170, cursor);
        cursor = declare(declared, CRDNAME, CRDNAMEO_LENGTH, 176, cursor);
        cursor = declare(declared, CRDSTCD, CRDSTCDO_LENGTH, 182, cursor);
        cursor = declare(declared, EXPMON, EXPMONO_LENGTH, 188, cursor);
        cursor = declare(declared, EXPYEAR, EXPYEARO_LENGTH, 194, cursor);
        cursor = declare(declared, EXPDAY, EXPDAYO_LENGTH, 200, cursor);
        cursor = declare(declared, INFOMSG, INFOMSGO_LENGTH, 206, cursor);
        cursor = declare(declared, ERRMSG, ERRMSGO_LENGTH, 212, cursor);
        cursor = declare(declared, FKEYS, FKEYSO_LENGTH, 218, cursor);
        cursor = declare(declared, FKEYSC, FKEYSCO_LENGTH, 224, cursor);

        if (declared.size() != NAMED_FIELD_COUNT) {
            throw new IllegalStateException("app/bms/COCRDUP.bms declares " + NAMED_FIELD_COUNT
                    + " name-labelled DFHMDF entries of " + DFHMDF_TOTAL_COUNT + " but "
                    + declared.size() + " were declared here");
        }
        if (cursor != GROUP_LENGTH) {
            throw new IllegalStateException("The 17 declared fields end at offset " + cursor
                    + " but 01 CCRDUPAO is " + GROUP_LENGTH + " bytes wide; check the width "
                    + "constants against app/cpy-bms/COCRDUP.CPY");
        }
        return List.copyOf(declared);
    }

    /**
     * Appends one field descriptor and returns the offset the next field begins at.
     *
     * @param declared     the list being built
     * @param name         the {@code DFHMDF} label
     * @param length       the {@code xxxO} declared width
     * @param copybookLine the line of the {@code xxxO} declaration
     * @param fieldOffset  the offset this field's block begins at
     * @return {@code fieldOffset + 7 + length}, the next field's offset
     */
    private static int declare(List<ScreenField> declared, String name, int length, int copybookLine,
            int fieldOffset) {
        ScreenField field = new ScreenField(name, length, copybookLine, fieldOffset);
        declared.add(field);
        return field.endOffsetExclusive();
    }

    /**
     * Indexes the descriptors by {@code DFHMDF} label, rejecting a duplicate.
     *
     * @param fields the descriptors in declaration order
     * @return an unmodifiable insertion-ordered index
     * @throws IllegalStateException if two fields share a label, which would make the collision
     *                               unaddressable
     */
    private static Map<String, ScreenField> indexByName(List<ScreenField> fields) {
        Map<String, ScreenField> index = new LinkedHashMap<>(fields.size() * 2);
        for (ScreenField field : fields) {
            ScreenField clash = index.put(field.name(), field);
            if (clash != null) {
                throw new IllegalStateException("Two screen fields share the DFHMDF label '"
                        + field.name() + "'; app/bms/COCRDUP.bms declares 17 distinct labels, and "
                        + "FKEYS and FKEYSC are distinct among them");
            }
        }
        return Collections.unmodifiableMap(index);
    }

    /**
     * Builds the 103-span layout of the output group from the field table.
     *
     * @param fields the 17 descriptors in declaration order
     * @return the validated layout
     * @throws IllegalArgumentException from {@link RecordLayout} if the spans are not contiguous from
     *                                  offset 0 or do not sum to {@link #GROUP_LENGTH}
     */
    private static RecordLayout declareLayout(List<ScreenField> fields) {
        List<FieldSpan> spans = new ArrayList<>(1 + fields.size() * 6);
        spans.add(FieldSpan.filler(0, TIOAPFX_LENGTH));
        for (ScreenField field : fields) {
            spans.add(field.prefixFillerSpan());
            spans.add(field.colourItemSpan());
            spans.add(field.psItemSpan());
            spans.add(field.hilightItemSpan());
            spans.add(field.validnItemSpan());
            spans.add(field.outputItemSpan());
        }
        return new RecordLayout(GROUP_LENGTH, spans);
    }

    // =================================================================================================
    // The attribute quad: xxxC, xxxP, xxxH, xxxV. Metadata, addressable, never serialised.
    // =================================================================================================

    /**
     * The four one-byte extended-attribute items of one screen field, in their declared byte order:
     * {@code xxxC} colour, {@code xxxP} programmed symbols, {@code xxxH} highlight, {@code xxxV}
     * validation.
     *
     * <p>These are 3270 attribute <em>bytes</em>, not characters and not text, so each is typed
     * {@code byte}. That is also what makes it impossible to move a colour into a payload item by
     * accident: the payload items are typed {@link String}.
     *
     * <p>Mutable by design. {@code app/cpy/CSSETATY.cpy:21-22} moves {@link BmsAttributes#DFHRED}
     * into the colour item of an already-populated map, and {@code app/cbl/COCRDUPC.cbl} inlines that
     * same rule at twelve sites (L1244, L1250, L1254, L1260, L1265, L1271, L1276, L1282, L1289,
     * L1295, L1300, L1306). Two further sites move something other than red:
     * {@link BmsAttributes#DFHDFCOL} into {@code ACCTSIDC} and {@code CARDSIDC} at L1238-L1240, and
     * {@link BmsAttributes#DFHBMDAR} into {@code EXPDAYC} at L1284. The colour item therefore holds an
     * arbitrary attribute byte and is never reduced to a boolean "is in error" flag.
     *
     * <p>All four bytes start at {@code 0x00}, which is what {@code MOVE LOW-VALUES TO CCRDUPAO}
     * ({@code app/cbl/COCRDUPC.cbl:1053}) puts there. Under the BMS conventions {@code 0x00} in a
     * colour item is {@link BmsAttributes#DFHDFCOL}, the default colour, and {@code 0x00} in a
     * highlight item is {@link BmsAttributes#DFHDFHI}, the default highlight - so the
     * {@code LOW-VALUES} initial state and the "leave the attributes alone" state are the same byte,
     * by design of the 3270 data stream rather than by coincidence of this translation.
     *
     * <p>Excluded from JSON. The wire carries the 17 payload members and the state carriers; the
     * attribute bytes are how a colour reaches a 3270 terminal and have no meaning to a REST client.
     */
    public static final class FieldAttributes {

        /** The default attribute byte, {@code 0x00}: default colour and default highlight alike. */
        public static final byte DEFAULT = (byte) 0x00;

        /** {@code xxxC} - the {@code COLOR} attribute byte. */
        private byte colour;

        /** {@code xxxP} - the {@code PS} (programmed symbols) attribute byte. */
        private byte ps;

        /** {@code xxxH} - the {@code HILIGHT} attribute byte. */
        private byte hilight;

        /** {@code xxxV} - the {@code VALIDN} attribute byte. */
        private byte validn;

        /** Creates a quad in its {@code LOW-VALUES} state: all four bytes {@link #DEFAULT}. */
        public FieldAttributes() {
            reset();
        }

        /**
         * Creates a quad with all four bytes supplied.
         *
         * @param colour  the {@code xxxC} byte
         * @param ps      the {@code xxxP} byte
         * @param hilight the {@code xxxH} byte
         * @param validn  the {@code xxxV} byte
         */
        public FieldAttributes(byte colour, byte ps, byte hilight, byte validn) {
            this.colour = colour;
            this.ps = ps;
            this.hilight = hilight;
            this.validn = validn;
        }

        /**
         * Copies an existing quad.
         *
         * @param other the quad to copy
         * @throws NullPointerException if {@code other} is {@code null}
         */
        public FieldAttributes(FieldAttributes other) {
            Objects.requireNonNull(other, "A source quad is required to copy one");
            this.colour = other.colour;
            this.ps = other.ps;
            this.hilight = other.hilight;
            this.validn = other.validn;
        }

        /**
         * Restores all four bytes to {@link #DEFAULT}, reproducing the effect of
         * {@code MOVE LOW-VALUES TO CCRDUPAO} on this field's quad.
         */
        public void reset() {
            this.colour = DEFAULT;
            this.ps = DEFAULT;
            this.hilight = DEFAULT;
            this.validn = DEFAULT;
        }

        /**
         * The {@code xxxC} colour byte currently held.
         *
         * @return the colour byte
         */
        public byte getColour() {
            return colour;
        }

        /**
         * Moves an attribute byte into the {@code xxxC} colour item.
         *
         * @param colour any attribute byte - {@link BmsAttributes#DFHRED},
         *               {@link BmsAttributes#DFHDFCOL} and {@link BmsAttributes#DFHBMDAR} are the
         *               three this program moves
         */
        public void setColour(byte colour) {
            this.colour = colour;
        }

        /**
         * The {@code xxxP} programmed-symbols byte currently held.
         *
         * @return the programmed-symbols byte
         */
        public byte getPs() {
            return ps;
        }

        /**
         * Moves an attribute byte into the {@code xxxP} item.
         *
         * @param ps the programmed-symbols byte
         */
        public void setPs(byte ps) {
            this.ps = ps;
        }

        /**
         * The {@code xxxH} highlight byte currently held.
         *
         * @return the highlight byte
         */
        public byte getHilight() {
            return hilight;
        }

        /**
         * Moves an attribute byte into the {@code xxxH} item.
         *
         * @param hilight the highlight byte, for example {@link BmsAttributes#DFHUNDLN}
         */
        public void setHilight(byte hilight) {
            this.hilight = hilight;
        }

        /**
         * The {@code xxxV} validation byte currently held.
         *
         * @return the validation byte
         */
        public byte getValidn() {
            return validn;
        }

        /**
         * Moves an attribute byte into the {@code xxxV} item.
         *
         * @param validn the validation byte
         */
        public void setValidn(byte validn) {
            this.validn = validn;
        }

        /**
         * Whether the colour item currently holds {@link BmsAttributes#DFHRED}, which is what
         * {@code CSSETATY} moves into a field in error.
         *
         * @return {@code true} when the field is painted red
         */
        public boolean isRed() {
            return colour == BmsAttributes.DFHRED;
        }

        /**
         * Whether all four bytes are still {@link #DEFAULT}, that is whether nothing has been moved
         * into this quad since the group was set to {@code LOW-VALUES}.
         *
         * @return {@code true} when the quad is untouched
         */
        public boolean isDefault() {
            return colour == DEFAULT && ps == DEFAULT && hilight == DEFAULT && validn == DEFAULT;
        }

        /**
         * Renders the quad with the mnemonics {@link BmsAttributes} knows, for a log line or a parity
         * trace.
         *
         * <p>An unnamed byte is shown in the {@code X'hh'} notation the copybooks and the IBM
         * documentation use, which is what {@link BmsAttributes#toHex(byte)} produces.
         *
         * @return for example {@code "C=DFHRED P=X'00' H=DFHDFHI V=X'00'"}
         */
        public String describe() {
            return "C=" + BmsAttributes.colourMnemonic(colour)
                    + " P=" + BmsAttributes.toHex(ps)
                    + " H=" + BmsAttributes.highlightMnemonic(hilight)
                    + " V=" + BmsAttributes.toHex(validn);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FieldAttributes quad)) {
                return false;
            }
            return colour == quad.colour && ps == quad.ps && hilight == quad.hilight
                    && validn == quad.validn;
        }

        @Override
        public int hashCode() {
            return Objects.hash(colour, ps, hilight, validn);
        }

        @Override
        public String toString() {
            return "FieldAttributes[" + describe() + "]";
        }
    }

    // =================================================================================================
    // Instance state. Every reference below is final: the maps are created once and their contents
    // mutate, which is the COBOL group's own behaviour. No static mutable state exists (practice B9).
    // =================================================================================================

    /**
     * The 17 {@code xxxO} payload items, keyed by {@code DFHMDF} label and held at exactly their
     * declared widths.
     *
     * <p>Insertion-ordered so iteration follows the copybook. One storage for all 17 is what lets
     * {@code FKEYS} and {@code FKEYSC} be addressed by their real names, which no suffix-stripping
     * scheme could do.
     */
    private final Map<String, String> payload;

    /** The 17 attribute quads, keyed by the same {@code DFHMDF} labels. */
    private final Map<String, FieldAttributes> attributes;

    /**
     * The echoed {@value CommArea#RECORD_LENGTH}-byte {@code WS-THIS-PROGCOMMAREA}
     * ({@code app/cbl/COCRDUPC.cbl:274-321}).
     */
    private CommArea commArea;

    /** The echoed {@code CVCRD01Y} work area ({@code COPY CVCRD01Y} at {@code COCRDUPC.cbl:268}). */
    private CardScreenState cardScreenState;

    /** The echoed {@code COCOM01Y} commarea ({@code COPY COCOM01Y} at {@code COCRDUPC.cbl:272}). */
    private NavigationContext navigationContext;

    /** The {@code XCTL} target program, 8 characters, opaque. */
    private String nextProgram;

    /** The {@code XCTL} target mapset, 7 characters, opaque. */
    private String nextMapset;

    /** The {@code XCTL} target map, 7 characters, opaque. */
    private String nextMap;

    // =================================================================================================
    // Construction.
    // =================================================================================================

    /**
     * Creates a response in the state {@code 3100-SCREEN-INIT} establishes with its very first
     * statement, {@code MOVE LOW-VALUES TO CCRDUPAO} at {@code app/cbl/COCRDUPC.cbl:1053}.
     *
     * <p>Concretely: all 17 payload items hold {@code LOW-VALUES} at their declared widths, all 68
     * attribute bytes hold {@code 0x00}, the program commarea is
     * {@link CommArea#initialised()}, the work area is a fresh {@link CardScreenState}, the
     * navigation commarea is {@link NavigationContext#empty()} and the three {@code XCTL} target
     * tokens are spaces.
     *
     * <p>{@code LOW-VALUES} is not spaces and neither is {@code null}. The distinction is
     * load-bearing here: {@code CCUP-DETAILS-NOT-FETCHED} is true for {@code LOW-VALUES}
     * <em>and</em> for spaces but they remain two different byte states, and
     * {@code 3200-SETUP-SCREEN-VARS} moves {@code LOW-VALUES} - not spaces - into {@code ACCTSIDO},
     * {@code CARDSIDO}, {@code CRDNAMEO}, {@code CRDSTCDO}, {@code EXPDAYO}, {@code EXPMONO} and
     * {@code EXPYEARO} when there is nothing to show ({@code COCRDUPC.cbl:1089-1106}).
     */
    public CardUpdateResponse() {
        this.payload = new LinkedHashMap<>(FIELDS.size() * 2);
        this.attributes = new LinkedHashMap<>(FIELDS.size() * 2);
        for (ScreenField field : FIELDS) {
            this.payload.put(field.name(), CardScreenState.lowValues(field.length()));
            this.attributes.put(field.name(), new FieldAttributes());
        }
        this.commArea = CommArea.initialised();
        this.cardScreenState = new CardScreenState();
        this.navigationContext = NavigationContext.empty();
        this.nextProgram = CardScreenState.spaces(CardScreenState.CCARD_NEXT_PROG_LENGTH);
        this.nextMapset = CardScreenState.spaces(CardScreenState.CCARD_NEXT_MAPSET_LENGTH);
        this.nextMap = CardScreenState.spaces(CardScreenState.CCARD_NEXT_MAP_LENGTH);
    }

    /**
     * Copies an existing response, deeply enough that the copy can be mutated without touching the
     * original.
     *
     * <p>The payload strings and the commarea groups are immutable values, so copying the references
     * is a complete copy; the attribute quads and the work area are mutable and are copied field for
     * field.
     *
     * @param other the response to copy
     * @throws NullPointerException if {@code other} is {@code null}
     */
    public CardUpdateResponse(CardUpdateResponse other) {
        Objects.requireNonNull(other, "A source response is required to copy one");
        this.payload = new LinkedHashMap<>(other.payload);
        this.attributes = new LinkedHashMap<>(other.attributes.size() * 2);
        for (Map.Entry<String, FieldAttributes> quad : other.attributes.entrySet()) {
            this.attributes.put(quad.getKey(), new FieldAttributes(quad.getValue()));
        }
        this.commArea = other.commArea;
        this.cardScreenState = new CardScreenState(other.cardScreenState);
        this.navigationContext = other.navigationContext;
        this.nextProgram = other.nextProgram;
        this.nextMapset = other.nextMapset;
        this.nextMap = other.nextMap;
    }

    /**
     * Reproduces {@code 3100-SCREEN-INIT} ({@code app/cbl/COCRDUPC.cbl:1052-1076}) in full: sets the
     * whole group to {@code LOW-VALUES}, then moves the four constants and the two clock values the
     * paragraph moves.
     *
     * <p>In source order: {@code MOVE LOW-VALUES TO CCRDUPAO} (L1053);
     * {@code CCDA-TITLE01 -> TITLE01O} (L1058); {@code CCDA-TITLE02 -> TITLE02O} (L1059);
     * {@code LIT-THISTRANID -> TRNNAMEO} (L1060); {@code LIT-THISPGM -> PGMNAMEO} (L1061);
     * {@code WS-CURDATE-MM-DD-YY -> CURDATEO} (L1068); {@code WS-CURTIME-HH-MM-SS -> CURTIMEO}
     * (L1075). The transaction identifier and program name are this screen's own
     * {@link #TRANSACTION_ID} and {@link #PROGRAM_NAME}.
     *
     * <p>The attribute quads are reset too, because {@code LOW-VALUES} covers the entire 484-byte
     * group and not merely its payload items.
     *
     * @param dateHeader the captured date and time, supplying {@code mm/dd/yy} and {@code hh:mm:ss}
     * @throws NullPointerException if {@code dateHeader} is {@code null}
     */
    public void screenInit(DateHeader dateHeader) {
        Objects.requireNonNull(dateHeader, "A DateHeader is required: 3100-SCREEN-INIT moves "
                + "FUNCTION CURRENT-DATE into CURDATEO and CURTIMEO, so the clock reading must be "
                + "supplied rather than taken here");
        moveLowValuesToGroup();
        applyScreenTitles();
        setTrnnameo(TRANSACTION_ID);
        setPgmnameo(PROGRAM_NAME);
        applyDateTimeHeader(dateHeader);
    }

    /**
     * Reproduces {@code MOVE LOW-VALUES TO CCRDUPAO} ({@code app/cbl/COCRDUPC.cbl:1053}): every
     * payload item back to {@code LOW-VALUES} at its declared width, every attribute byte back to
     * {@code 0x00}.
     *
     * <p>Scoped to the symbolic map group alone. The program commarea, the work area, the navigation
     * commarea and the {@code XCTL} targets are separate storage in the COBOL and are left untouched
     * here for the same reason.
     */
    public void moveLowValuesToGroup() {
        for (ScreenField field : FIELDS) {
            payload.put(field.name(), CardScreenState.lowValues(field.length()));
            attributes.get(field.name()).reset();
        }
    }

    /**
     * Moves the two shared heading literals into {@code TITLE01O} and {@code TITLE02O}, as
     * {@code app/cbl/COCRDUPC.cbl:1058-1059} does with {@code CCDA-TITLE01} and {@code CCDA-TITLE02}.
     *
     * <p>Both literals are already {@link ScreenTitles#TITLE_LENGTH} characters, which is exactly
     * {@value #TITLE01O_LENGTH}, so the move neither pads nor truncates. They are taken from
     * {@link ScreenTitles} rather than retyped, because a retyped literal is a literal that can drift
     * from {@code app/cpy/COTTL01Y.cpy}.
     */
    public void applyScreenTitles() {
        setTitle01o(ScreenTitles.CCDA_TITLE01);
        setTitle02o(ScreenTitles.CCDA_TITLE02);
    }

    /**
     * Moves the formatted date and time into {@code CURDATEO} and {@code CURTIMEO}, as
     * {@code app/cbl/COCRDUPC.cbl:1068} and {@code :1075} do with {@code WS-CURDATE-MM-DD-YY} and
     * {@code WS-CURTIME-HH-MM-SS}.
     *
     * <p>Both are eight characters, matching {@value #CURDATEO_LENGTH} and the mapset's own
     * {@code INITIAL='mm/dd/yy'} and {@code INITIAL='hh:mm:ss'} placeholders at
     * {@code app/bms/COCRDUP.bms:51} and {@code :74}.
     *
     * @param dateHeader the captured date and time
     * @throws NullPointerException if {@code dateHeader} is {@code null}
     */
    public void applyDateTimeHeader(DateHeader dateHeader) {
        Objects.requireNonNull(dateHeader, "A DateHeader is required to fill CURDATEO and CURTIMEO");
        setCurdateo(dateHeader.wsCurdateMmDdYy());
        setCurtimeo(dateHeader.wsCurtimeHhMmSs());
    }

    // =================================================================================================
    // The 17 payload items. Each getter names its DFHMDF label, its xxxO PICTURE and the copybook line
    // it was transcribed from - the per-field basis of gate G9. Each setter performs a COBOL PIC X
    // MOVE: padded on the right with spaces when short, truncated on the RIGHT when long, exactly as
    // FixedWidthCodec#movePicX documents. The xxxC/xxxP/xxxH/xxxV quad of each field is deliberately
    // absent from this section, and from JSON; it lives in FieldAttributes and is reached by name.
    // =================================================================================================

    /**
     * {@code TRNNAMEO PIC X(4)} - {@code DFHMDF TRNNAME}, {@code app/cpy-bms/COCRDUP.CPY:128}. The
     * transaction identifier, {@value #TRANSACTION_ID}, moved at {@code app/cbl/COCRDUPC.cbl:1060}.
     *
     * @return the item at exactly {@value #TRNNAMEO_LENGTH} characters, untrimmed
     */
    @Size(max = TRNNAMEO_LENGTH)
    public String getTrnnameo() {
        return payload.get(TRNNAME);
    }

    /**
     * Moves a value into {@code TRNNAMEO OF CCRDUPAO}.
     *
     * @param value the sending value; padded or truncated on the right to
     *              {@value #TRNNAMEO_LENGTH} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void setTrnnameo(String value) {
        setOutputItem(TRNNAME, value);
    }

    /**
     * {@code TITLE01O PIC X(40)} - {@code DFHMDF TITLE01}, {@code app/cpy-bms/COCRDUP.CPY:134}. The
     * upper heading, {@link ScreenTitles#CCDA_TITLE01}, moved at {@code app/cbl/COCRDUPC.cbl:1058}.
     *
     * @return the item at exactly {@value #TITLE01O_LENGTH} characters, untrimmed
     */
    @Size(max = TITLE01O_LENGTH)
    public String getTitle01o() {
        return payload.get(TITLE01);
    }

    /**
     * Moves a value into {@code TITLE01O OF CCRDUPAO}.
     *
     * @param value the sending value; padded or truncated on the right to
     *              {@value #TITLE01O_LENGTH} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void setTitle01o(String value) {
        setOutputItem(TITLE01, value);
    }

    /**
     * {@code CURDATEO PIC X(8)} - {@code DFHMDF CURDATE}, {@code app/cpy-bms/COCRDUP.CPY:140}. The
     * {@code mm/dd/yy} heading date, moved at {@code app/cbl/COCRDUPC.cbl:1068}.
     *
     * @return the item at exactly {@value #CURDATEO_LENGTH} characters, untrimmed
     */
    @Size(max = CURDATEO_LENGTH)
    public String getCurdateo() {
        return payload.get(CURDATE);
    }

    /**
     * Moves a value into {@code CURDATEO OF CCRDUPAO}.
     *
     * @param value the sending value; padded or truncated on the right to
     *              {@value #CURDATEO_LENGTH} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void setCurdateo(String value) {
        setOutputItem(CURDATE, value);
    }

    /**
     * {@code PGMNAMEO PIC X(8)} - {@code DFHMDF PGMNAME}, {@code app/cpy-bms/COCRDUP.CPY:146}. The
     * program name, {@value #PROGRAM_NAME}, moved at {@code app/cbl/COCRDUPC.cbl:1061}.
     *
     * @return the item at exactly {@value #PGMNAMEO_LENGTH} characters, untrimmed
     */
    @Size(max = PGMNAMEO_LENGTH)
    public String getPgmnameo() {
        return payload.get(PGMNAME);
    }

    /**
     * Moves a value into {@code PGMNAMEO OF CCRDUPAO}.
     *
     * @param value the sending value; padded or truncated on the right to
     *              {@value #PGMNAMEO_LENGTH} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void setPgmnameo(String value) {
        setOutputItem(PGMNAME, value);
    }

    /**
     * {@code TITLE02O PIC X(40)} - {@code DFHMDF TITLE02}, {@code app/cpy-bms/COCRDUP.CPY:152}. The
     * lower heading, {@link ScreenTitles#CCDA_TITLE02}, moved at {@code app/cbl/COCRDUPC.cbl:1059}.
     *
     * @return the item at exactly {@value #TITLE02O_LENGTH} characters, untrimmed
     */
    @Size(max = TITLE02O_LENGTH)
    public String getTitle02o() {
        return payload.get(TITLE02);
    }

    /**
     * Moves a value into {@code TITLE02O OF CCRDUPAO}.
     *
     * @param value the sending value; padded or truncated on the right to
     *              {@value #TITLE02O_LENGTH} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void setTitle02o(String value) {
        setOutputItem(TITLE02, value);
    }

    /**
     * {@code CURTIMEO PIC X(8)} - {@code DFHMDF CURTIME}, {@code app/cpy-bms/COCRDUP.CPY:158}. The
     * {@code hh:mm:ss} heading time, moved at {@code app/cbl/COCRDUPC.cbl:1075}.
     *
     * @return the item at exactly {@value #CURTIMEO_LENGTH} characters, untrimmed
     */
    @Size(max = CURTIMEO_LENGTH)
    public String getCurtimeo() {
        return payload.get(CURTIME);
    }

    /**
     * Moves a value into {@code CURTIMEO OF CCRDUPAO}.
     *
     * @param value the sending value; padded or truncated on the right to
     *              {@value #CURTIMEO_LENGTH} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void setCurtimeo(String value) {
        setOutputItem(CURTIME, value);
    }

    /**
     * {@code ACCTSIDO PIC X(11)} - {@code DFHMDF ACCTSID}, {@code app/cpy-bms/COCRDUP.CPY:164}. The
     * account number search key, moved from {@code CC-ACCT-ID} at {@code app/cbl/COCRDUPC.cbl:1091}
     * or set to {@code LOW-VALUES} at {@code :1089} when {@code CC-ACCT-ID-N} is zero.
     *
     * <p>This is one of the four items {@code COCRDUPC} can overwrite with a single {@code '*'} when
     * the field was left blank and the program is in {@code REENTER} state
     * ({@code app/cbl/COCRDUPC.cbl:1249}), which is the {@code CSSETATY} rule inlined.
     *
     * @return the item at exactly {@value #ACCTSIDO_LENGTH} characters, untrimmed
     */
    @Size(max = ACCTSIDO_LENGTH)
    public String getAcctsido() {
        return payload.get(ACCTSID);
    }

    /**
     * Moves a value into {@code ACCTSIDO OF CCRDUPAO}.
     *
     * @param value the sending value; padded or truncated on the right to
     *              {@value #ACCTSIDO_LENGTH} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void setAcctsido(String value) {
        setOutputItem(ACCTSID, value);
    }

    /**
     * {@code CARDSIDO PIC X(16)} - {@code DFHMDF CARDSID}, {@code app/cpy-bms/COCRDUP.CPY:170}. The
     * card number, moved from {@code CC-CARD-NUM} at {@code app/cbl/COCRDUPC.cbl:1097} or set to
     * {@code LOW-VALUES} at {@code :1095} when {@code CC-CARD-NUM-N} is zero.
     *
     * <p>Carried in full, all sixteen digits, unmasked and un-redacted, because that is what the
     * COBOL sends to the terminal. Masking it here would be a behaviour change (practice
     * <strong>B6</strong>).
     *
     * @return the item at exactly {@value #CARDSIDO_LENGTH} characters, untrimmed
     */
    @Size(max = CARDSIDO_LENGTH)
    public String getCardsido() {
        return payload.get(CARDSID);
    }

    /**
     * Moves a value into {@code CARDSIDO OF CCRDUPAO}.
     *
     * @param value the sending value; padded or truncated on the right to
     *              {@value #CARDSIDO_LENGTH} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void setCardsido(String value) {
        setOutputItem(CARDSID, value);
    }

    /**
     * {@code CRDNAMEO PIC X(50)} - {@code DFHMDF CRDNAME}, {@code app/cpy-bms/COCRDUP.CPY:176}. The
     * embossed name, moved from {@code CCUP-OLD-CRDNAME} or {@code CCUP-NEW-CRDNAME} depending on the
     * change action ({@code app/cbl/COCRDUPC.cbl:1103-1128}).
     *
     * @return the item at exactly {@value #CRDNAMEO_LENGTH} characters, untrimmed
     */
    @Size(max = CRDNAMEO_LENGTH)
    public String getCrdnameo() {
        return payload.get(CRDNAME);
    }

    /**
     * Moves a value into {@code CRDNAMEO OF CCRDUPAO}.
     *
     * @param value the sending value; padded or truncated on the right to
     *              {@value #CRDNAMEO_LENGTH} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void setCrdnameo(String value) {
        setOutputItem(CRDNAME, value);
    }

    /**
     * {@code CRDSTCDO PIC X(1)} - {@code DFHMDF CRDSTCD}, {@code app/cpy-bms/COCRDUP.CPY:182}. The
     * active status, {@code Y} or {@code N} in practice, carried verbatim whatever it holds.
     *
     * @return the item at exactly {@value #CRDSTCDO_LENGTH} character, untrimmed
     */
    @Size(max = CRDSTCDO_LENGTH)
    public String getCrdstcdo() {
        return payload.get(CRDSTCD);
    }

    /**
     * Moves a value into {@code CRDSTCDO OF CCRDUPAO}.
     *
     * @param value the sending value; padded or truncated on the right to
     *              {@value #CRDSTCDO_LENGTH} character
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void setCrdstcdo(String value) {
        setOutputItem(CRDSTCD, value);
    }

    /**
     * {@code EXPMONO PIC X(2)} - {@code DFHMDF EXPMON}, {@code app/cpy-bms/COCRDUP.CPY:188}. The
     * expiry month. Its {@code DFHMDF} declares {@code JUSTIFY=(RIGHT)}
     * ({@code app/bms/COCRDUP.bms:129}), which is a terminal-side alignment instruction and changes
     * nothing about how the two characters are stored here.
     *
     * @return the item at exactly {@value #EXPMONO_LENGTH} characters, untrimmed
     */
    @Size(max = EXPMONO_LENGTH)
    public String getExpmono() {
        return payload.get(EXPMON);
    }

    /**
     * Moves a value into {@code EXPMONO OF CCRDUPAO}.
     *
     * @param value the sending value; padded or truncated on the right to
     *              {@value #EXPMONO_LENGTH} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void setExpmono(String value) {
        setOutputItem(EXPMON, value);
    }

    /**
     * {@code EXPYEARO PIC X(4)} - {@code DFHMDF EXPYEAR}, {@code app/cpy-bms/COCRDUP.CPY:194}. The
     * four-digit expiry year.
     *
     * @return the item at exactly {@value #EXPYEARO_LENGTH} characters, untrimmed
     */
    @Size(max = EXPYEARO_LENGTH)
    public String getExpyearo() {
        return payload.get(EXPYEAR);
    }

    /**
     * Moves a value into {@code EXPYEARO OF CCRDUPAO}.
     *
     * @param value the sending value; padded or truncated on the right to
     *              {@value #EXPYEARO_LENGTH} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void setExpyearo(String value) {
        setOutputItem(EXPYEAR, value);
    }

    /**
     * {@code EXPDAYO PIC X(2)} - {@code DFHMDF EXPDAY}, {@code app/cpy-bms/COCRDUP.CPY:200}. The
     * expiry day, and the one field no other card screen has.
     *
     * <p>Always shown from the <em>old</em> record even when every other field shows the new one:
     * {@code app/cbl/COCRDUPC.cbl:1122} moves {@code CCUP-OLD-EXPDAY} under
     * {@code WHEN CCUP-CHANGES-MADE} while the commented-out line above it
     * ({@code :1121}) shows that moving {@code CCUP-NEW-EXPDAY} was considered and deliberately not
     * done. That is behaviour to preserve, not a defect to fix (practice <strong>B5</strong>).
     *
     * @return the item at exactly {@value #EXPDAYO_LENGTH} characters, untrimmed
     */
    @Size(max = EXPDAYO_LENGTH)
    public String getExpdayo() {
        return payload.get(EXPDAY);
    }

    /**
     * Moves a value into {@code EXPDAYO OF CCRDUPAO}.
     *
     * @param value the sending value; padded or truncated on the right to
     *              {@value #EXPDAYO_LENGTH} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void setExpdayo(String value) {
        setOutputItem(EXPDAY, value);
    }

    /**
     * {@code INFOMSGO PIC X(40)} - {@code DFHMDF INFOMSG}, {@code app/cpy-bms/COCRDUP.CPY:206}. The
     * information line, moved from {@code WS-INFO-MSG PIC X(40)} at
     * {@code app/cbl/COCRDUPC.cbl:1161} - an exact-width move.
     *
     * <p>The six texts this screen can show are {@code COCRDUPC}'s own {@code 88}-level literals at
     * {@code app/cbl/COCRDUPC.cbl:157-172} and are chosen by {@code 3250-SETUP-INFOMSG}
     * ({@code :1138-1163}), so they belong to {@code CardUpdateController}. None comes from
     * {@link SystemMessages}.
     *
     * @return the item at exactly {@value #INFOMSGO_LENGTH} characters, untrimmed
     */
    @Size(max = INFOMSGO_LENGTH)
    public String getInfomsgo() {
        return payload.get(INFOMSG);
    }

    /**
     * Moves a value into {@code INFOMSGO OF CCRDUPAO}.
     *
     * @param value the sending value; padded or truncated on the right to
     *              {@value #INFOMSGO_LENGTH} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void setInfomsgo(String value) {
        setOutputItem(INFOMSG, value);
    }

    /**
     * {@code ERRMSGO PIC X(80)} - {@code DFHMDF ERRMSG}, {@code app/cpy-bms/COCRDUP.CPY:212}. The
     * error line, moved from {@code WS-RETURN-MSG PIC X(75)} at
     * {@code app/cbl/COCRDUPC.cbl:1163}.
     *
     * <p>That move is <strong>not</strong> exact-width: a 75-character sender into an 80-character
     * receiver pads five spaces on the right. Assigning the string directly would leave the item
     * five characters short and every byte after it displaced, which is precisely the class of defect
     * practice <strong>B11</strong> exists to prevent - hence {@link #setErrmsgo(String)} routes
     * through the {@code PIC X} rule.
     *
     * @return the item at exactly {@value #ERRMSGO_LENGTH} characters, untrimmed
     */
    @Size(max = ERRMSGO_LENGTH)
    public String getErrmsgo() {
        return payload.get(ERRMSG);
    }

    /**
     * Moves a value into {@code ERRMSGO OF CCRDUPAO}.
     *
     * @param value the sending value, typically the 75-character {@code WS-RETURN-MSG}; padded or
     *              truncated on the right to {@value #ERRMSGO_LENGTH} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void setErrmsgo(String value) {
        setOutputItem(ERRMSG, value);
    }

    /**
     * {@code FKEYSO PIC X(21)} - {@code DFHMDF FKEYS}, {@code app/cpy-bms/COCRDUP.CPY:218}. The first
     * function-key line, whose mapset literal is {@code 'ENTER=Process F3=Exit'} - exactly
     * {@value #FKEYSO_LENGTH} characters ({@code app/bms/COCRDUP.bms:162}).
     *
     * <p>Its colour item is named {@code FKEYSC}, which is <em>also</em> the name of the next field.
     * Reach this field's quad with {@code attributesOf(FKEYS)} and the other field's with
     * {@code attributesOf(FKEYSC)}; the two are unrelated.
     *
     * @return the item at exactly {@value #FKEYSO_LENGTH} characters, untrimmed
     */
    @Size(max = FKEYSO_LENGTH)
    public String getFkeyso() {
        return payload.get(FKEYS);
    }

    /**
     * Moves a value into {@code FKEYSO OF CCRDUPAO}.
     *
     * @param value the sending value; padded or truncated on the right to
     *              {@value #FKEYSO_LENGTH} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void setFkeyso(String value) {
        setOutputItem(FKEYS, value);
    }

    /**
     * {@code FKEYSCO PIC X(18)} - {@code DFHMDF FKEYSC}, {@code app/cpy-bms/COCRDUP.CPY:224}. The
     * second function-key line, whose mapset literal is {@code 'F5=Save F12=Cancel'} - exactly
     * {@value #FKEYSCO_LENGTH} characters ({@code app/bms/COCRDUP.bms:167}).
     *
     * <p>This is the payload of the field <em>named</em> {@code FKEYSC}, 18 bytes wide, and it is not
     * to be confused with {@code FKEYSC PICTURE X} at {@code app/cpy-bms/COCRDUP.CPY:214}, which is
     * one byte and belongs to {@link #getFkeyso()}. This field's own colour item is
     * {@code FKEYSCC} at {@code app/cpy-bms/COCRDUP.CPY:220}.
     *
     * @return the item at exactly {@value #FKEYSCO_LENGTH} characters, untrimmed
     */
    @Size(max = FKEYSCO_LENGTH)
    public String getFkeysco() {
        return payload.get(FKEYSC);
    }

    /**
     * Moves a value into {@code FKEYSCO OF CCRDUPAO}.
     *
     * @param value the sending value; padded or truncated on the right to
     *              {@value #FKEYSCO_LENGTH} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void setFkeysco(String value) {
        setOutputItem(FKEYSC, value);
    }

    // =================================================================================================
    // Name-addressed access. This is what makes the FKEYSC / FKEYSCC collision safe: a field is always
    // reached by the DFHMDF label the source declares, never by stripping a suffix off an item name.
    // =================================================================================================

    /**
     * The 17 field descriptors in copybook declaration order.
     *
     * @return an unmodifiable list; the same shared constant every instance sees
     */
    @JsonIgnore
    public static List<ScreenField> namedFields() {
        return FIELDS;
    }

    /**
     * The 17 {@code DFHMDF} labels in copybook declaration order:
     * {@code TRNNAME TITLE01 CURDATE PGMNAME TITLE02 CURTIME ACCTSID CARDSID CRDNAME CRDSTCD EXPMON
     * EXPYEAR EXPDAY INFOMSG ERRMSG FKEYS FKEYSC}.
     *
     * @return an unmodifiable list of the labels, in order
     */
    @JsonIgnore
    public static List<String> namedFieldPrefixes() {
        List<String> names = new ArrayList<>(FIELDS.size());
        for (ScreenField field : FIELDS) {
            names.add(field.name());
        }
        return Collections.unmodifiableList(names);
    }

    /**
     * The descriptor of one field, by {@code DFHMDF} label.
     *
     * @param screenFieldName the label, for example {@link #FKEYSC}
     * @return the descriptor; never {@code null}
     * @throws NullPointerException     if {@code screenFieldName} is {@code null}
     * @throws IllegalArgumentException if no field carries that label
     */
    @JsonIgnore
    public static ScreenField fieldOf(String screenFieldName) {
        return requireField(screenFieldName);
    }

    /**
     * Whether a label names one of the 17 fields.
     *
     * @param screenFieldName the label to test; may be {@code null}, which is simply not a label
     * @return {@code true} when the label is declared by {@code app/bms/COCRDUP.bms}
     */
    public static boolean declaresField(String screenFieldName) {
        return screenFieldName != null && FIELDS_BY_NAME.containsKey(screenFieldName);
    }

    /**
     * The live attribute quad of one field, by {@code DFHMDF} label.
     *
     * <p>Returned by reference, deliberately: the caller mutates it, which is what
     * {@code MOVE DFHRED TO ACCTSIDC OF CCRDUPAO} means. {@code attributesOf(FKEYS)} reaches the byte
     * spelled {@code FKEYSC} in the copybook, and {@code attributesOf(FKEYSC)} reaches the one spelled
     * {@code FKEYSCC} - two different bytes for two different fields.
     *
     * @param screenFieldName the label, for example {@link #ACCTSID}
     * @return the mutable quad; never {@code null}
     * @throws NullPointerException     if {@code screenFieldName} is {@code null}
     * @throws IllegalArgumentException if no field carries that label
     */
    @JsonIgnore
    public FieldAttributes attributesOf(String screenFieldName) {
        return attributes.get(requireField(screenFieldName).name());
    }

    /**
     * The colour byte of one field, that is its {@code xxxC} item.
     *
     * @param screenFieldName the label
     * @return the attribute byte currently held
     * @throws NullPointerException     if {@code screenFieldName} is {@code null}
     * @throws IllegalArgumentException if no field carries that label
     */
    public byte colourOf(String screenFieldName) {
        return attributesOf(screenFieldName).getColour();
    }

    /**
     * Reproduces {@code MOVE <attribute> TO <field>C OF CCRDUPAO}.
     *
     * <p>Takes an arbitrary byte rather than an enumeration, because {@code COCRDUPC} moves three
     * different values into colour items: {@link BmsAttributes#DFHRED} at twelve sites,
     * {@link BmsAttributes#DFHDFCOL} at {@code app/cbl/COCRDUPC.cbl:1238-1240} and
     * {@link BmsAttributes#DFHBMDAR} at {@code :1284}.
     *
     * @param screenFieldName the label
     * @param colour          the attribute byte to move
     * @throws NullPointerException     if {@code screenFieldName} is {@code null}
     * @throws IllegalArgumentException if no field carries that label
     */
    public void setColour(String screenFieldName, byte colour) {
        attributesOf(screenFieldName).setColour(colour);
    }

    /**
     * The payload item of one field, that is its {@code xxxO} item, by {@code DFHMDF} label.
     *
     * @param screenFieldName the label
     * @return the item at exactly its declared width, untrimmed
     * @throws NullPointerException     if {@code screenFieldName} is {@code null}
     * @throws IllegalArgumentException if no field carries that label
     */
    public String outputItemOf(String screenFieldName) {
        return payload.get(requireField(screenFieldName).name());
    }

    /**
     * Reproduces {@code MOVE <value> TO <field>O OF CCRDUPAO}, applying the COBOL {@code PIC X} move
     * rule: padded on the right with spaces when the sender is shorter, and truncated on the
     * <strong>right</strong> when it is longer.
     *
     * <p>Right truncation is the rule for a {@code PIC X} receiver - the receiving field fills from
     * its leftmost character and the overflow is discarded - and it is the opposite of the
     * {@code PIC 9} rule. The direction is not chosen here: it is
     * {@link FixedWidthCodec#movePicX(String, int)}, the module's single reviewable implementation of
     * both directions (practice <strong>B11</strong>). A plain Java assignment would neither pad nor
     * truncate, and the resulting off-by-n is invisible at the call site.
     *
     * @param screenFieldName the label
     * @param value           the sending value; may be shorter or longer than the receiver, and may be
     *                        empty, which blanks the field exactly as {@code MOVE SPACES} does
     * @throws NullPointerException     if {@code screenFieldName} or {@code value} is {@code null}
     * @throws IllegalArgumentException if no field carries that label
     */
    public void setOutputItem(String screenFieldName, String value) {
        ScreenField field = requireField(screenFieldName);
        payload.put(field.name(), movePicX(value, field));
    }

    /**
     * Applies a {@code CSSETATY} decision to this map: {@code DFHRED} into the field's {@code xxxC}
     * item and, when the field was blank, {@code '*'} into its {@code xxxO} item.
     *
     * <p>This is the second half of the copybook, the half {@link FieldAttributeSetter} deliberately
     * leaves to the map's owner so that {@code common} depends on no domain package. The decision
     * itself - including the {@code CDEMO-PGM-REENTER} guard that gate <strong>G38</strong> asks
     * about - was already made by {@link FieldAttributeSetter#resolve}; nothing is re-decided here.
     *
     * <p>The two moves are nested in the source ({@code app/cpy/CSSETATY.cpy:21-26}) and stay nested
     * here: the asterisk is written only when the colour was written. When
     * {@link FieldHighlight#untouched()} holds, nothing at all is touched - the field keeps the colour
     * and the content the program already gave it, which is not the same as being reset to a default.
     *
     * <p>The decision's {@link FieldHighlight#screenFieldPrefix()} selects the field, so a highlight
     * resolved for {@code FKEYS} colours the byte spelled {@code FKEYSC} while one resolved for
     * {@code FKEYSC} colours the byte spelled {@code FKEYSCC}.
     *
     * @param highlight the resolved decision
     * @throws NullPointerException     if {@code highlight} is {@code null}
     * @throws IllegalArgumentException if the decision carries no field prefix, or one this map does
     *                                  not declare, or a map name other than {@link #MAP_NAME}
     */
    public void applyHighlight(FieldHighlight highlight) {
        Objects.requireNonNull(highlight, "A resolved FieldHighlight is required; call "
                + "FieldAttributeSetter.resolve(...) to obtain one");

        String prefix = highlight.screenFieldPrefix();
        if (prefix.isEmpty()) {
            throw new IllegalArgumentException("The highlight carries no (SCRNVAR2) field prefix, so "
                    + "there is no way to tell which of the " + NAMED_FIELD_COUNT + " fields of "
                    + OUTPUT_GROUP_NAME + " it applies to; resolve it with the four-argument "
                    + "FieldAttributeSetter.resolve(state, reenter, screenFieldPrefix, mapName)");
        }
        String mapName = highlight.mapName();
        if (!mapName.isEmpty() && !MAP_NAME.equals(mapName)) {
            throw new IllegalArgumentException("The highlight was resolved for map '" + mapName
                    + "' but this payload projects '" + MAP_NAME + "' (" + OUTPUT_GROUP_NAME + "); "
                    + "the three card screens declare different widths for the same field names, so a "
                    + "highlight must not be carried across them");
        }
        ScreenField field = requireField(prefix);

        if (highlight.colourItemAssigned()) {
            attributes.get(field.name()).setColour(highlight.colourItemValue());
        }
        if (highlight.outputItemAssigned()) {
            setOutputItem(field.name(), highlight.outputItemValue());
        }
    }

    /**
     * Resolves the {@code CSSETATY} rule for one field of this map and applies it in one step.
     *
     * <p>A convenience over {@link #applyHighlight(FieldHighlight)} that cannot pass the wrong map
     * name, since it supplies {@link #MAP_NAME} itself. The decision is still made entirely by
     * {@link FieldAttributeSetter#resolve(FieldValidationState, boolean, String, String)} - this
     * method contains no branch of its own beyond delegating.
     *
     * @param screenFieldName the {@code DFHMDF} label of the field being edited
     * @param state           the field's validation state, the {@code (TESTVAR1)} analogue
     * @param reenter         {@code true} when {@code CDEMO-PGM-REENTER} holds, that is when
     *                        {@code CDEMO-PGM-CONTEXT} is 1; the guard at
     *                        {@code app/cpy/CSSETATY.cpy:20}
     * @return the decision that was applied, so a caller or a test can assert on it
     * @throws NullPointerException     if {@code screenFieldName} or {@code state} is {@code null}
     * @throws IllegalArgumentException if no field carries that label
     */
    public FieldHighlight applyHighlight(String screenFieldName, FieldValidationState state,
            boolean reenter) {
        ScreenField field = requireField(screenFieldName);
        FieldHighlight highlight =
                FieldAttributeSetter.resolve(state, reenter, field.name(), MAP_NAME);
        applyHighlight(highlight);
        return highlight;
    }

    // =================================================================================================
    // The echoed conversational state. All of it travels in the payload; none of it is held server
    // side (rule R6, gate G37).
    // =================================================================================================

    /**
     * The {@value CommArea#RECORD_LENGTH}-byte {@code WS-THIS-PROGCOMMAREA} the client must send
     * back on its next call ({@code app/cbl/COCRDUPC.cbl:274-321}).
     *
     * @return the commarea; never {@code null}
     */
    public CommArea getCommArea() {
        return commArea;
    }

    /**
     * Replaces the echoed program commarea.
     *
     * @param commArea the commarea to echo
     * @throws NullPointerException if {@code commArea} is {@code null}; COBOL has no absent
     *                              commarea, and {@link CommArea#initialised()} is the empty state
     */
    public void setCommArea(CommArea commArea) {
        this.commArea = Objects.requireNonNull(commArea, "A program commarea is required; "
                + "CommArea.initialised() is the INITIALIZE WS-THIS-PROGCOMMAREA state");
    }

    /**
     * The {@code CVCRD01Y} card work area - the AID, the next-target trio, the two message lines and
     * the three identifier fields ({@code COPY CVCRD01Y} at {@code app/cbl/COCRDUPC.cbl:268}).
     *
     * @return the work area; never {@code null}
     */
    public CardScreenState getCardScreenState() {
        return cardScreenState;
    }

    /**
     * Replaces the echoed work area.
     *
     * @param cardScreenState the work area to echo
     * @throws NullPointerException if {@code cardScreenState} is {@code null}
     */
    public void setCardScreenState(CardScreenState cardScreenState) {
        this.cardScreenState = Objects.requireNonNull(cardScreenState,
                "A CVCRD01Y work area is required; new CardScreenState() is the INITIALIZE state");
    }

    /**
     * The {@code COCOM01Y} application commarea - the from and to transaction and program, the user
     * identity and type, the {@code ENTER}/{@code REENTER} context and the carried identifiers
     * ({@code COPY COCOM01Y} at {@code app/cbl/COCRDUPC.cbl:272}).
     *
     * @return the commarea; never {@code null}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    /**
     * Replaces the echoed application commarea.
     *
     * @param navigationContext the commarea to echo
     * @throws NullPointerException if {@code navigationContext} is {@code null};
     *                              {@link NavigationContext#empty()} is the empty state
     */
    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = Objects.requireNonNull(navigationContext,
                "A COCOM01Y commarea is required; NavigationContext.empty() is the empty state");
    }

    /**
     * The program the client should call next - the {@code XCTL} target of
     * {@code app/cbl/COCRDUPC.cbl:473-476}, where the COBOL transfers control itself.
     *
     * <p>An opaque eight-character token, the width of {@code CDEMO-TO-PROGRAM} and of
     * {@link CardScreenState#CCARD_NEXT_PROG_LENGTH}. It is not checked against a list of known
     * programs, not upper-cased and not trimmed, because the COBOL passes whatever the commarea holds
     * straight to CICS.
     *
     * @return the token at exactly {@link CardScreenState#CCARD_NEXT_PROG_LENGTH} characters
     */
    @Size(max = CardScreenState.CCARD_NEXT_PROG_LENGTH)
    public String getNextProgram() {
        return nextProgram;
    }

    /**
     * Names the next program, applying the {@code PIC X} move rule to the declared width.
     *
     * @param nextProgram the target program name
     * @throws NullPointerException if {@code nextProgram} is {@code null}
     */
    public void setNextProgram(String nextProgram) {
        this.nextProgram = movePicX(nextProgram, CardScreenState.CCARD_NEXT_PROG_LENGTH,
                "CDEMO-TO-PROGRAM");
    }

    /**
     * The mapset the client should send next - seven characters, the width of
     * {@link CardScreenState#CCARD_NEXT_MAPSET_LENGTH}. Opaque, like {@link #getNextProgram()}.
     *
     * @return the token at exactly {@link CardScreenState#CCARD_NEXT_MAPSET_LENGTH} characters
     */
    @Size(max = CardScreenState.CCARD_NEXT_MAPSET_LENGTH)
    public String getNextMapset() {
        return nextMapset;
    }

    /**
     * Names the next mapset, applying the {@code PIC X} move rule to the declared width.
     *
     * @param nextMapset the target mapset name, for example {@link #MAPSET_NAME}
     * @throws NullPointerException if {@code nextMapset} is {@code null}
     */
    public void setNextMapset(String nextMapset) {
        this.nextMapset = movePicX(nextMapset, CardScreenState.CCARD_NEXT_MAPSET_LENGTH,
                "CCARD-NEXT-MAPSET");
    }

    /**
     * The map the client should send next - seven characters, because a symbolic group name is a
     * seven-character map name plus an {@code I} or {@code O} suffix, as {@link #MAP_NAME} yields
     * {@link #INPUT_GROUP_NAME} and {@link #OUTPUT_GROUP_NAME}. Opaque, like
     * {@link #getNextProgram()}.
     *
     * @return the token at exactly {@link CardScreenState#CCARD_NEXT_MAP_LENGTH} characters
     */
    @Size(max = CardScreenState.CCARD_NEXT_MAP_LENGTH)
    public String getNextMap() {
        return nextMap;
    }

    /**
     * Names the next map, applying the {@code PIC X} move rule to the declared width.
     *
     * @param nextMap the target map name, for example {@link #MAP_NAME}
     * @throws NullPointerException if {@code nextMap} is {@code null}
     */
    public void setNextMap(String nextMap) {
        this.nextMap = movePicX(nextMap, CardScreenState.CCARD_NEXT_MAP_LENGTH, "CCARD-NEXT-MAP");
    }

    /**
     * Names all three {@code XCTL} targets at once, the substitution gate <strong>G40</strong>
     * describes for {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)}
     * ({@code app/cbl/COCRDUPC.cbl:473-476}).
     *
     * @param program the target program, 8 characters
     * @param mapset  the target mapset, 7 characters
     * @param map     the target map, 7 characters
     * @throws NullPointerException if any argument is {@code null}
     */
    public void transferTo(String program, String mapset, String map) {
        setNextProgram(program);
        setNextMapset(mapset);
        setNextMap(map);
    }

    // =================================================================================================
    // The 484-byte symbolic group image. Used by the parity harness to compare against a captured
    // CCRDUPAO, and by nothing else: a REST client sees JSON, not a 3270 data stream.
    // =================================================================================================

    /**
     * The byte the COBOL figurative constant {@code LOW-VALUES} occupies, {@code 0x00} under both code
     * pages this system uses.
     *
     * <p>This is the byte {@code MOVE LOW-VALUES TO CCRDUPAO} ({@code app/cbl/COCRDUPC.cbl:1053})
     * writes across all {@value #GROUP_LENGTH} bytes of the group, {@code FILLER} included.
     */
    private static final byte LOW_VALUE_BYTE = (byte) 0x00;

    /**
     * Renders this payload as the {@value #GROUP_LENGTH}-byte {@code 01 CCRDUPAO} image.
     *
     * <p>Every byte is written explicitly: the {@value #TIOAPFX_LENGTH}-byte {@code TIOAPFX} prefix,
     * then per field its {@value #FIELD_PREFIX_FILLER_LENGTH}-byte {@code FILLER}, its four attribute
     * bytes and its {@code xxxO} payload.
     *
     * <p><strong>The {@code FILLER} spans of this group are {@code LOW-VALUES}, not spaces</strong>,
     * and that is deliberate. The general convention in this module - and what gate
     * <strong>G21</strong> asks of a persisted copybook record - is that {@code FILLER} is emitted as
     * spaces; {@link CardUpdateRecord#encode(Charset)} follows it exactly. A BMS symbolic map group is
     * different storage with a different initialiser: the program sets the whole group with
     * {@code MOVE LOW-VALUES TO CCRDUPAO} ({@code app/cbl/COCRDUPC.cbl:1053}) before moving anything
     * into it, and the first twelve bytes belong to CICS under {@code TIOAPFX=YES}
     * ({@code app/bms/COCRDUP.bms:23}) rather than to the program at all. Emitting spaces here would
     * therefore misreport the program's own state. What both conventions share, and what the gate is
     * really about, is that the {@code FILLER} bytes are <em>present</em>: the image is
     * {@value #GROUP_LENGTH} bytes wide, which is only true if all 18 {@code FILLER} spans were
     * written.
     *
     * @param charset the code page of the image, named explicitly by the caller - {@code IBM037} for a
     *                mainframe capture, {@code US-ASCII} for a text fixture
     * @return a new array of exactly {@value #GROUP_LENGTH} bytes
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not single-byte for the space and zero
     *                                  characters
     */
    public byte[] toFixedWidth(Charset charset) {
        Objects.requireNonNull(charset, "A charset is required: a symbolic map image is bytes in a "
                + "specific code page, and this type is bound to neither IBM037 nor US-ASCII");
        FixedWidthRecord record = new FixedWidthRecord(GROUP_LENGTH, charset);
        writeInto(record);
        return record.toByteArray();
    }

    /**
     * Writes this payload into a caller-supplied {@value #GROUP_LENGTH}-byte record area, so a caller
     * that is assembling a larger buffer does not have to allocate twice.
     *
     * @param record the area to write into; its declared length must be {@value #GROUP_LENGTH}
     * @throws NullPointerException     if {@code record} is {@code null}
     * @throws IllegalArgumentException if the area is not exactly {@value #GROUP_LENGTH} bytes wide
     */
    public void writeInto(FixedWidthRecord record) {
        requireGroupWidth(record);

        record.fill(0, TIOAPFX_LENGTH, LOW_VALUE_BYTE);
        for (ScreenField field : FIELDS) {
            FieldAttributes quad = attributes.get(field.name());
            record.fill(field.prefixFillerOffset(), FIELD_PREFIX_FILLER_LENGTH, LOW_VALUE_BYTE);
            record.writeSpanBytes(field.colourItemSpan(), new byte[] {quad.getColour()});
            record.writeSpanBytes(field.psItemSpan(), new byte[] {quad.getPs()});
            record.writeSpanBytes(field.hilightItemSpan(), new byte[] {quad.getHilight()});
            record.writeSpanBytes(field.validnItemSpan(), new byte[] {quad.getValidn()});
            record.writeSpan(field.outputItemSpan(), payload.get(field.name()));
        }
    }

    /**
     * Decodes a captured {@value #GROUP_LENGTH}-byte {@code 01 CCRDUPAO} image into a payload,
     * recovering all 17 items and all 68 attribute bytes.
     *
     * <p>The {@code FILLER} spans carry no information and are not read. The state carriers - the
     * program commarea, the work area, the navigation commarea and the {@code XCTL} targets - are
     * separate storage in the COBOL and are left in their initial state here; a caller that has them
     * sets them explicitly.
     *
     * @param image   the captured bytes; exactly {@value #GROUP_LENGTH} of them
     * @param charset the code page of those bytes, named explicitly
     * @return the decoded payload
     * @throws NullPointerException     if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly {@value #GROUP_LENGTH} bytes
     */
    public static CardUpdateResponse fromFixedWidth(byte[] image, Charset charset) {
        Objects.requireNonNull(image, "An image is required to decode a symbolic map group");
        Objects.requireNonNull(charset, "A charset is required to decode a symbolic map group");
        if (image.length != GROUP_LENGTH) {
            throw new IllegalArgumentException("01 " + OUTPUT_GROUP_NAME + " is " + GROUP_LENGTH
                    + " byte(s) wide but " + image.length + " byte(s) were supplied; the group is "
                    + TIOAPFX_LENGTH + " + " + NAMED_FIELD_COUNT + " x " + ITEM_OVERHEAD_LENGTH
                    + " + " + PAYLOAD_LENGTH + " bytes");
        }
        return readFrom(FixedWidthRecord.copyOf(image, GROUP_LENGTH, charset));
    }

    /**
     * Decodes a payload from a record area already holding a {@code 01 CCRDUPAO} image.
     *
     * @param record the area to read; its declared length must be {@value #GROUP_LENGTH}
     * @return the decoded payload
     * @throws NullPointerException     if {@code record} is {@code null}
     * @throws IllegalArgumentException if the area is not exactly {@value #GROUP_LENGTH} bytes wide
     */
    public static CardUpdateResponse readFrom(FixedWidthRecord record) {
        requireGroupWidth(record);

        CardUpdateResponse decoded = new CardUpdateResponse();
        for (ScreenField field : FIELDS) {
            decoded.payload.put(field.name(), record.readSpan(field.outputItemSpan()));
            FieldAttributes quad = decoded.attributes.get(field.name());
            quad.setColour(singleByteOf(record, field.colourItemSpan()));
            quad.setPs(singleByteOf(record, field.psItemSpan()));
            quad.setHilight(singleByteOf(record, field.hilightItemSpan()));
            quad.setValidn(singleByteOf(record, field.validnItemSpan()));
        }
        return decoded;
    }

    /**
     * The 17 {@code xxxO} items keyed by their copybook item names - {@code TRNNAMEO},
     * {@code TITLE01O}, ..., {@code FKEYSO}, {@code FKEYSCO} - in declaration order.
     *
     * <p>This is the shape the parity differ compares: named fields rather than one concatenated
     * string, so a difference is reported against the item that carries it.
     *
     * @return an unmodifiable insertion-ordered map of 17 entries
     */
    @JsonIgnore
    public Map<String, String> fieldImages() {
        Map<String, String> images = new LinkedHashMap<>(FIELDS.size() * 2);
        for (ScreenField field : FIELDS) {
            images.put(field.outputItemName(), payload.get(field.name()));
        }
        return Collections.unmodifiableMap(images);
    }

    /**
     * The 68 attribute bytes keyed by their copybook item names - {@code TRNNAMEC},
     * {@code TRNNAMEP}, {@code TRNNAMEH}, {@code TRNNAMEV}, ..., {@code FKEYSCC}, {@code FKEYSCP},
     * {@code FKEYSCH}, {@code FKEYSCV} - each rendered by {@link BmsAttributes#toHex(byte)} in the
     * {@code X'hh'} notation the copybooks use, in declaration order.
     *
     * <p>Rendered as hex rather than as text because an attribute byte such as {@code X'F2'} is not a
     * character in either code page and decoding it would destroy it. Note that {@code FKEYSC} appears
     * in this map as the colour item of {@code FKEYS}, while the field named {@code FKEYSC}
     * contributes {@code FKEYSCC}, {@code FKEYSCP}, {@code FKEYSCH} and {@code FKEYSCV} - both are
     * present and neither shadows the other.
     *
     * @return an unmodifiable insertion-ordered map of 68 entries
     */
    @JsonIgnore
    public Map<String, String> attributeImages() {
        Map<String, String> images = new LinkedHashMap<>(FIELDS.size() * 8);
        for (ScreenField field : FIELDS) {
            FieldAttributes quad = attributes.get(field.name());
            images.put(field.colourItemName(), BmsAttributes.toHex(quad.getColour()));
            images.put(field.psItemName(), BmsAttributes.toHex(quad.getPs()));
            images.put(field.hilightItemName(), BmsAttributes.toHex(quad.getHilight()));
            images.put(field.validnItemName(), BmsAttributes.toHex(quad.getValidn()));
        }
        return Collections.unmodifiableMap(images);
    }

    // =================================================================================================
    // Private helpers.
    // =================================================================================================

    /**
     * Resolves a {@code DFHMDF} label to its descriptor, refusing an unknown one with a message that
     * lists what is declared.
     *
     * @param screenFieldName the label
     * @return the descriptor
     * @throws NullPointerException     if {@code screenFieldName} is {@code null}
     * @throws IllegalArgumentException if the label is not one of the 17
     */
    private static ScreenField requireField(String screenFieldName) {
        Objects.requireNonNull(screenFieldName, "A DFHMDF label is required to address a field of "
                + OUTPUT_GROUP_NAME);
        ScreenField field = FIELDS_BY_NAME.get(screenFieldName);
        if (field == null) {
            throw new IllegalArgumentException("'" + screenFieldName + "' is not one of the "
                    + NAMED_FIELD_COUNT + " name-labelled DFHMDF fields of " + MAPSET_NAME + "; they "
                    + "are " + FIELDS_BY_NAME.keySet() + ". Note that an item name is not a field "
                    + "name: 'FKEYSO' and 'FKEYSC OF " + OUTPUT_GROUP_NAME + "' both belong to the "
                    + "field 'FKEYS', and 'FKEYSC' is a separate 18-byte field");
        }
        return field;
    }

    /**
     * Applies the {@code PIC X} move rule for a field of this map.
     *
     * @param value the sending value
     * @param field the receiving field
     * @return the value at exactly the field's declared width
     * @throws NullPointerException if {@code value} is {@code null}
     */
    private static String movePicX(String value, ScreenField field) {
        return movePicX(value, field.length(), field.outputItemName());
    }

    /**
     * Applies the {@code PIC X} move rule for a named receiver of the given width.
     *
     * @param value     the sending value
     * @param length    the receiver's declared width
     * @param cobolName the receiver's copybook name, for the diagnostic
     * @return the value at exactly {@code length} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    private static String movePicX(String value, int length, String cobolName) {
        Objects.requireNonNull(value, "A value is required for " + cobolName + ": COBOL has no null, "
                + "so pass an empty string or CardScreenState.spaces(" + length + ") for SPACES, or "
                + "CardScreenState.lowValues(" + length + ") for LOW-VALUES");
        return PICTURE_RULES.movePicX(value, length);
    }

    /**
     * Reads a one-byte span as a raw byte.
     *
     * @param record the area to read
     * @param span   the one-byte span
     * @return the byte it holds
     */
    private static byte singleByteOf(FixedWidthRecord record, FieldSpan span) {
        return record.readSpanBytes(span)[0];
    }

    /**
     * Rejects a record area that is not exactly the width {@code 01 CCRDUPAO} declares.
     *
     * @param record the area to check
     * @throws NullPointerException     if {@code record} is {@code null}
     * @throws IllegalArgumentException if the area is not {@value #GROUP_LENGTH} bytes wide
     */
    private static void requireGroupWidth(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A record area is required to read or write a symbolic map "
                + "group image");
        if (record.recordLength() != GROUP_LENGTH) {
            throw new IllegalArgumentException("01 " + OUTPUT_GROUP_NAME + " is " + GROUP_LENGTH
                    + " byte(s) wide but the record area is " + record.recordLength() + "; the group "
                    + "is " + TIOAPFX_LENGTH + " + " + NAMED_FIELD_COUNT + " x "
                    + ITEM_OVERHEAD_LENGTH + " + " + PAYLOAD_LENGTH + " bytes");
        }
    }

    // =================================================================================================
    // Object contract.
    // =================================================================================================

    /**
     * Field-for-field equality over all 17 payload items, all 68 attribute bytes, the program
     * commarea, the work area, the navigation commarea and the three {@code XCTL} targets.
     *
     * @param other the object to compare with
     * @return {@code true} when every one of those is equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CardUpdateResponse response)) {
            return false;
        }
        return payload.equals(response.payload)
                && attributes.equals(response.attributes)
                && commArea.equals(response.commArea)
                && cardScreenState.equals(response.cardScreenState)
                && navigationContext.equals(response.navigationContext)
                && nextProgram.equals(response.nextProgram)
                && nextMapset.equals(response.nextMapset)
                && nextMap.equals(response.nextMap);
    }

    @Override
    public int hashCode() {
        return Objects.hash(payload, attributes, commArea, cardScreenState, navigationContext,
                nextProgram, nextMapset, nextMap);
    }

    /**
     * A structural summary: which screen this is, how wide its group is, which change action it
     * carries and where it points next.
     *
     * <p>It deliberately reports <em>structure</em> rather than content. The payload getters expose
     * the card number and the commarea exposes the CVV verbatim, exactly as the COBOL does, and
     * nothing here masks or redacts them (practice <strong>B6</strong>); equally, nothing here copies
     * them into a log line that the COBOL never wrote, which would be new exposure rather than
     * preserved behaviour. Use {@link #fieldImages()} when the content itself is what is wanted.
     *
     * @return a single-line summary
     */
    @Override
    public String toString() {
        return "CardUpdateResponse[" + MAPSET_NAME + "/" + OUTPUT_GROUP_NAME + " " + GROUP_LENGTH
                + "B, txn=" + TRANSACTION_ID + ", changeAction="
                + commArea.changeAction().describe() + ", next=" + nextProgram.strip() + "/"
                + nextMapset.strip() + "/" + nextMap.strip() + "]";
    }

    // =================================================================================================
    // 01 WS-THIS-PROGCOMMAREA - app/cbl/COCRDUPC.cbl:274-321, 329 bytes, echoed on the response.
    //
    // There are deliberately NO nested record types declared below this point. The area, its two
    // 89-byte detail groups and the embedded 150-byte card record are all declared ONCE, on
    // CardUpdateRequest, and referenced here through the four imports at the top of this file:
    // CommArea, CardDetails, DetailGroup and CardUpdateRecord.
    //
    // 01 WS-THIS-PROGCOMMAREA [app/cbl/COCRDUPC.cbl:274-321] is ONE area of 329 bytes: a one-byte
    // CCUP-CHANGE-ACTION, then CCUP-OLD-DETAILS and CCUP-NEW-DETAILS at 89 bytes each, then the
    // 150-byte CARD-UPDATE-RECORD. COMMON-RETURN [COCRDUPC.cbl:549-558] appends it to WS-COMMAREA
    // behind the 160-byte CARDDEMO-COMMAREA and hands the pair back on EXEC CICS RETURN, and :396-400
    // slices the same bytes out of DFHCOMMAREA on the next invocation - so the area this response
    // carries IS the area the next request arrives with. One area in the COBOL therefore has to be one
    // type in Java.
    //
    // This class previously declared a second implementation of all four, under different names:
    // ProgCommarea against the request's CommArea, CcupDetails against CardDetails, DetailsPrefix
    // against DetailGroup, and its own CardUpdateRecord against the request's. Same 329 bytes and the
    // same four-part structure at the same offsets 0, 1, 90 and 179 - but two JSON shapes, two sets of
    // member names and two sets of constants, so the pair could not round-trip: a client echoing the
    // response's area back into a request had to rewrite it member by member, and every such
    // rewriting is a place for the two to diverge silently. Nothing was lost by keeping the request's
    // set: its ChangeAction record carries the whole CCUP-CHANGE-ACTION 88-level family that
    // ProgCommarea only delegated to as a bare PIC X(1), and its DetailGroup carries the FieldSpan
    // layout that DetailsPrefix described by name only. Nor was any COBOL-observable behaviour lost -
    // toFixedWidth/fromFixedWidth below still write and read the same 329 bytes at the same offsets.
    // =================================================================================================
}
