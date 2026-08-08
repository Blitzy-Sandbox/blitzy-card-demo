package com.vsergeychik.carddemo.card.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.vsergeychik.carddemo.common.BmsAttributes;
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
 * {@code WS-THIS-PROGCOMMAREA} as {@link #getProgCommarea()}, the {@code CVCRD01Y} work area as
 * {@link #getScreenState()} and the {@code COCOM01Y} commarea as {@link #getNavigation()}. This
 * class holds no {@code HttpSession}, no {@code @SessionAttributes}, no server-side cache, no static
 * map and no {@code ThreadLocal}, and it never will.
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
 * <p>{@link ProgCommarea#LAYOUT} declares every one of those bytes, {@code FILLER} included, and
 * {@link RecordLayout} refuses to exist unless the spans are contiguous from offset 0 and sum to
 * exactly {@value ProgCommarea#COMMAREA_LENGTH}. The arithmetic above is therefore machine-checked
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
     * The echoed {@value ProgCommarea#COMMAREA_LENGTH}-byte {@code WS-THIS-PROGCOMMAREA}
     * ({@code app/cbl/COCRDUPC.cbl:274-321}).
     */
    private ProgCommarea progCommarea;

    /** The echoed {@code CVCRD01Y} work area ({@code COPY CVCRD01Y} at {@code COCRDUPC.cbl:268}). */
    private CardScreenState screenState;

    /** The echoed {@code COCOM01Y} commarea ({@code COPY COCOM01Y} at {@code COCRDUPC.cbl:272}). */
    private NavigationContext navigation;

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
     * {@link ProgCommarea#initialised()}, the work area is a fresh {@link CardScreenState}, the
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
        this.progCommarea = ProgCommarea.initialised();
        this.screenState = new CardScreenState();
        this.navigation = NavigationContext.empty();
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
        this.progCommarea = other.progCommarea;
        this.screenState = new CardScreenState(other.screenState);
        this.navigation = other.navigation;
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
     * The {@value ProgCommarea#COMMAREA_LENGTH}-byte {@code WS-THIS-PROGCOMMAREA} the client must send
     * back on its next call ({@code app/cbl/COCRDUPC.cbl:274-321}).
     *
     * @return the commarea; never {@code null}
     */
    public ProgCommarea getProgCommarea() {
        return progCommarea;
    }

    /**
     * Replaces the echoed program commarea.
     *
     * @param progCommarea the commarea to echo
     * @throws NullPointerException if {@code progCommarea} is {@code null}; COBOL has no absent
     *                              commarea, and {@link ProgCommarea#initialised()} is the empty state
     */
    public void setProgCommarea(ProgCommarea progCommarea) {
        this.progCommarea = Objects.requireNonNull(progCommarea, "A program commarea is required; "
                + "ProgCommarea.initialised() is the INITIALIZE WS-THIS-PROGCOMMAREA state");
    }

    /**
     * The {@code CVCRD01Y} card work area - the AID, the next-target trio, the two message lines and
     * the three identifier fields ({@code COPY CVCRD01Y} at {@code app/cbl/COCRDUPC.cbl:268}).
     *
     * @return the work area; never {@code null}
     */
    public CardScreenState getScreenState() {
        return screenState;
    }

    /**
     * Replaces the echoed work area.
     *
     * @param screenState the work area to echo
     * @throws NullPointerException if {@code screenState} is {@code null}
     */
    public void setScreenState(CardScreenState screenState) {
        this.screenState = Objects.requireNonNull(screenState,
                "A CVCRD01Y work area is required; new CardScreenState() is the INITIALIZE state");
    }

    /**
     * The {@code COCOM01Y} application commarea - the from and to transaction and program, the user
     * identity and type, the {@code ENTER}/{@code REENTER} context and the carried identifiers
     * ({@code COPY COCOM01Y} at {@code app/cbl/COCRDUPC.cbl:272}).
     *
     * @return the commarea; never {@code null}
     */
    public NavigationContext getNavigation() {
        return navigation;
    }

    /**
     * Replaces the echoed application commarea.
     *
     * @param navigation the commarea to echo
     * @throws NullPointerException if {@code navigation} is {@code null};
     *                              {@link NavigationContext#empty()} is the empty state
     */
    public void setNavigation(NavigationContext navigation) {
        this.navigation = Objects.requireNonNull(navigation,
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
                && progCommarea.equals(response.progCommarea)
                && screenState.equals(response.screenState)
                && navigation.equals(response.navigation)
                && nextProgram.equals(response.nextProgram)
                && nextMapset.equals(response.nextMapset)
                && nextMap.equals(response.nextMap);
    }

    @Override
    public int hashCode() {
        return Objects.hash(payload, attributes, progCommarea, screenState, navigation, nextProgram,
                nextMapset, nextMap);
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
                + progCommarea.describeChangeAction() + ", next=" + nextProgram.strip() + "/"
                + nextMapset.strip() + "/" + nextMap.strip() + "]";
    }

    // =================================================================================================
    // 01 WS-THIS-PROGCOMMAREA - app/cbl/COCRDUPC.cbl:274-321, 329 bytes, echoed on the response.
    // =================================================================================================

    /**
     * The program's own conversational commarea, {@code 01 WS-THIS-PROGCOMMAREA}
     * ({@code app/cbl/COCRDUPC.cbl:274-321}): the change action, the card details as read, the card
     * details as typed, and the record staged for the file.
     *
     * <p>Immutable, and echoed on the response so the client can return it unchanged on its next call.
     * That is the whole of rule <strong>R6</strong> for this transaction: CICS kept this storage
     * across pseudo-conversational turns, and the Java form keeps it in the payload instead of on the
     * server.
     *
     * <h2>The change action and its nine condition names</h2>
     *
     * <p>{@code CCUP-CHANGE-ACTION PIC X(1) VALUE LOW-VALUES} ({@code :276-277}) carries the entire
     * state of the conversation in one byte, and {@code :278-290} declare nine {@code 88}-levels over
     * it. Two of them - {@code CCUP-CHANGES-MADE} and {@code CCUP-CHANGES-FAILED} - are multi-valued
     * and <strong>deliberately overlap</strong> the single-valued ones:
     *
     * <pre>
     *   byte          88-level                             kind          source line
     *   ------------  -----------------------------------  ------------  -----------
     *   LOW-VALUES    CCUP-DETAILS-NOT-FETCHED             two values     L278-L280
     *   SPACES        CCUP-DETAILS-NOT-FETCHED             two values     L278-L280
     *   'S'           CCUP-SHOW-DETAILS                    one value      L281
     *   'E'           CCUP-CHANGES-MADE + CHANGES-NOT-OK   overlapping    L282-L285
     *   'N'           CCUP-CHANGES-MADE + OK-NOT-CONFIRMED overlapping    L282-L286
     *   'C'           CCUP-CHANGES-MADE + OKAYED-AND-DONE  overlapping    L282-L287
     *   'L'           CHANGES-MADE + FAILED + LOCK-ERROR   overlapping    L282-L289
     *   'F'           CHANGES-MADE + FAILED + BUT-FAILED   overlapping    L282-L290
     * </pre>
     *
     * <p>The overlap is preserved exactly, because {@code 2000-DECIDE-ACTION}
     * ({@code app/cbl/COCRDUPC.cbl:948-1030}) is an ordered {@code EVALUATE TRUE} whose arms test
     * these names in a specific sequence, and {@code 3200-SETUP-SCREEN-VARS} ({@code :1113}) and
     * {@code 1000-PROCESS-INPUTS} ({@code :518}) test the grouping names rather than the individual
     * ones. Collapsing the nine into a flat enumeration of six bytes would lose the grouping and
     * break that dispatch (gate <strong>G30</strong>).
     *
     * <p>{@code LOW-VALUES} and {@code SPACES} are two <em>different</em> byte states that one
     * {@code 88}-level happens to cover jointly, and neither is Java {@code null}. The distinction is
     * visible: {@code SET CCUP-DETAILS-NOT-FETCHED TO TRUE} ({@code :394}, {@code :510}, {@code :527})
     * stores the <em>first</em> value of the {@code VALUES} list, which is {@code LOW-VALUES}, so
     * {@link #detailsNotFetched()} stores {@code LOW-VALUES} while
     * {@link #withChangeAction(String)} can still carry a space.
     *
     * <p>Only seven of the nine are ever {@code SET} in the source. {@code CCUP-CHANGES-MADE} and
     * {@code CCUP-CHANGES-FAILED} are tested but never set, so this type offers no factory for them:
     * a factory would manufacture a state transition the program cannot make. They remain fully
     * testable through {@link #isCcupChangesMade()} and {@link #isCcupChangesFailed()}.
     *
     * @param ccupChangeAction   {@code CCUP-CHANGE-ACTION PIC X(1)}, {@code :276}; exactly one
     *                           character, which may be {@code U+0000} for {@code LOW-VALUES}
     * @param ccupOldDetails     {@code CCUP-OLD-DETAILS}, {@code :291-301}; the details as read from
     *                           the file, 89 bytes
     * @param ccupNewDetails     {@code CCUP-NEW-DETAILS}, {@code :303-313}; the details as typed by
     *                           the user, 89 bytes
     * @param cardUpdateRecord   {@code CARD-UPDATE-RECORD}, {@code :314-321}; the record staged for
     *                           {@code REWRITE}, 150 bytes
     */
    public record ProgCommarea(String ccupChangeAction,
                               CcupDetails ccupOldDetails,
                               CcupDetails ccupNewDetails,
                               CardUpdateRecord cardUpdateRecord) {

        /** {@code CCUP-CHANGE-ACTION PIC X(1)} - one byte, {@code app/cbl/COCRDUPC.cbl:276}. */
        public static final int CHANGE_ACTION_LENGTH = 1;

        /** Absolute offset of {@code CARD-UPDATE-SCREEN-DATA}, {@code app/cbl/COCRDUPC.cbl:275}. */
        public static final int CHANGE_ACTION_OFFSET = 0;

        /** Absolute offset of {@code CCUP-OLD-DETAILS}, {@code app/cbl/COCRDUPC.cbl:291}. */
        public static final int OLD_DETAILS_OFFSET = CHANGE_ACTION_OFFSET + CHANGE_ACTION_LENGTH;

        /** Absolute offset of {@code CCUP-NEW-DETAILS}, {@code app/cbl/COCRDUPC.cbl:303}. */
        public static final int NEW_DETAILS_OFFSET = OLD_DETAILS_OFFSET + CcupDetails.DETAILS_LENGTH;

        /** Absolute offset of {@code CARD-UPDATE-RECORD}, {@code app/cbl/COCRDUPC.cbl:314}. */
        public static final int CARD_UPDATE_RECORD_OFFSET =
                NEW_DETAILS_OFFSET + CcupDetails.DETAILS_LENGTH;

        /**
         * The declared total width: <strong>329</strong> bytes = 1 + 89 + 89 + 150.
         *
         * <p>Proved mechanically by {@link #LAYOUT}, not asserted in prose.
         */
        public static final int COMMAREA_LENGTH =
                CARD_UPDATE_RECORD_OFFSET + CardUpdateRecord.RECORD_LENGTH;

        /** The copybook name of {@link #ccupChangeAction()}, carried verbatim. */
        public static final String CHANGE_ACTION_FIELD = "CCUP-CHANGE-ACTION";

        /** {@code CCUP-SHOW-DETAILS VALUE 'S'}, {@code app/cbl/COCRDUPC.cbl:281}. */
        public static final String SHOW_DETAILS = "S";

        /** {@code CCUP-CHANGES-NOT-OK VALUE 'E'}, {@code app/cbl/COCRDUPC.cbl:285}. */
        public static final String CHANGES_NOT_OK = "E";

        /** {@code CCUP-CHANGES-OK-NOT-CONFIRMED VALUE 'N'}, {@code app/cbl/COCRDUPC.cbl:286}. */
        public static final String CHANGES_OK_NOT_CONFIRMED = "N";

        /** {@code CCUP-CHANGES-OKAYED-AND-DONE VALUE 'C'}, {@code app/cbl/COCRDUPC.cbl:287}. */
        public static final String CHANGES_OKAYED_AND_DONE = "C";

        /** {@code CCUP-CHANGES-OKAYED-LOCK-ERROR VALUE 'L'}, {@code app/cbl/COCRDUPC.cbl:289}. */
        public static final String CHANGES_OKAYED_LOCK_ERROR = "L";

        /** {@code CCUP-CHANGES-OKAYED-BUT-FAILED VALUE 'F'}, {@code app/cbl/COCRDUPC.cbl:290}. */
        public static final String CHANGES_OKAYED_BUT_FAILED = "F";

        /** The {@code LOW-VALUES} state of the change action - the field's declared {@code VALUE}. */
        public static final String DETAILS_NOT_FETCHED_LOW_VALUES = "\u0000";

        /** The {@code SPACES} state of the change action - the second value of the same 88-level. */
        public static final String DETAILS_NOT_FETCHED_SPACES = " ";

        /**
         * The five bytes {@code CCUP-CHANGES-MADE} covers, in the order
         * {@code app/cbl/COCRDUPC.cbl:282-284} lists them: {@code 'E'}, {@code 'N'}, {@code 'C'},
         * {@code 'L'}, {@code 'F'}.
         */
        public static final List<String> CHANGES_MADE_VALUES = List.of(CHANGES_NOT_OK,
                CHANGES_OK_NOT_CONFIRMED, CHANGES_OKAYED_AND_DONE, CHANGES_OKAYED_LOCK_ERROR,
                CHANGES_OKAYED_BUT_FAILED);

        /**
         * The two bytes {@code CCUP-CHANGES-FAILED} covers, {@code app/cbl/COCRDUPC.cbl:288}:
         * {@code 'L'} and {@code 'F'}.
         */
        public static final List<String> CHANGES_FAILED_VALUES =
                List.of(CHANGES_OKAYED_LOCK_ERROR, CHANGES_OKAYED_BUT_FAILED);

        /**
         * The complete {@value #COMMAREA_LENGTH}-byte geometry: the change action, the two 89-byte
         * details groups with their own field names, and the 150-byte staged record including its
         * trailing {@code FILLER}.
         *
         * <p>{@link RecordLayout} refuses to exist unless these spans are contiguous from offset 0 and
         * sum to exactly {@value #COMMAREA_LENGTH}, so the 1 + 89 + 89 + 150 arithmetic is checked at
         * class-initialisation time.
         *
         * <p>The intermediate group levels {@code CARD-UPDATE-SCREEN-DATA} ({@code :275}),
         * {@code CCUP-OLD-CARDDATA} / {@code CCUP-NEW-CARDDATA} ({@code :295}, {@code :307}) and
         * {@code CCUP-OLD-EXPIRAION-DATE} / {@code CCUP-NEW-EXPIRAION-DATE} ({@code :297},
         * {@code :309}) are groups rather than elementary items, so they occupy no span of their own in
         * a flattened layout. They are still addressable as values - see {@link CcupDetails#carddata()}
         * and {@link CcupDetails#expiraionDate()} - because the program compares one of them as a
         * group.
         */
        public static final RecordLayout LAYOUT = declareCommareaLayout();

        /**
         * Normalises the change action to exactly one character and rejects a missing group.
         *
         * @throws NullPointerException     if any component is {@code null}
         * @throws IllegalArgumentException if {@code ccupChangeAction} is longer than one character
         */
        public ProgCommarea {
            Objects.requireNonNull(ccupChangeAction, "A change action is required for "
                    + CHANGE_ACTION_FIELD + "; its declared VALUE is LOW-VALUES, which is "
                    + "ProgCommarea.DETAILS_NOT_FETCHED_LOW_VALUES and not null");
            if (ccupChangeAction.length() > CHANGE_ACTION_LENGTH) {
                throw new IllegalArgumentException(CHANGE_ACTION_FIELD + " is PIC X("
                        + CHANGE_ACTION_LENGTH + ") but " + ccupChangeAction.length()
                        + " character(s) were supplied; the field carries a single state byte");
            }
            ccupChangeAction = PICTURE_RULES.movePicX(ccupChangeAction, CHANGE_ACTION_LENGTH);
            Objects.requireNonNull(ccupOldDetails, "CCUP-OLD-DETAILS is required; "
                    + "CcupDetails.initialised() is the INITIALIZE state");
            Objects.requireNonNull(ccupNewDetails, "CCUP-NEW-DETAILS is required; "
                    + "CcupDetails.initialised() is the INITIALIZE state");
            Objects.requireNonNull(cardUpdateRecord, "CARD-UPDATE-RECORD is required; "
                    + "CardUpdateRecord.initialised() is the INITIALIZE state");
        }

        /**
         * The state {@code INITIALIZE WS-THIS-PROGCOMMAREA} leaves behind
         * ({@code app/cbl/COCRDUPC.cbl:506}, {@code :519}): the change action at its declared
         * {@code LOW-VALUES}, both details groups space-filled and the staged record space-filled.
         *
         * <p>The change action is {@code LOW-VALUES} rather than a space because that is its declared
         * {@code VALUE} ({@code :277}) and because the program follows every
         * {@code INITIALIZE WS-THIS-PROGCOMMAREA} with an explicit
         * {@code SET CCUP-DETAILS-NOT-FETCHED TO TRUE} ({@code :510}, {@code :527}), which stores the
         * same byte.
         *
         * @return the initialised commarea; never {@code null}
         */
        public static ProgCommarea initialised() {
            return new ProgCommarea(DETAILS_NOT_FETCHED_LOW_VALUES, CcupDetails.initialised(),
                    CcupDetails.initialised(), CardUpdateRecord.initialised());
        }

        /**
         * Replaces the change action with an arbitrary byte.
         *
         * <p>Accepts any single character, including one that satisfies none of the nine
         * {@code 88}-levels. That state is reachable and is not an error here: it is what drives
         * {@code 2000-DECIDE-ACTION}'s {@code WHEN OTHER} arm to abend with
         * {@code 'UNEXPECTED DATA SCENARIO'} ({@code app/cbl/COCRDUPC.cbl:1019-1027}), and a parity
         * case has to be able to construct it.
         *
         * @param changeAction the state byte; one character, or empty for spaces
         * @return a copy carrying that state
         * @throws NullPointerException     if {@code changeAction} is {@code null}
         * @throws IllegalArgumentException if {@code changeAction} is longer than one character
         */
        public ProgCommarea withChangeAction(String changeAction) {
            return new ProgCommarea(changeAction, ccupOldDetails, ccupNewDetails, cardUpdateRecord);
        }

        /**
         * Reproduces {@code SET CCUP-DETAILS-NOT-FETCHED TO TRUE} ({@code app/cbl/COCRDUPC.cbl:394},
         * {@code :510}, {@code :527}), storing {@code LOW-VALUES} - the first of the two values the
         * {@code 88}-level lists, which is the value a COBOL {@code SET} on a multi-valued condition
         * name stores.
         *
         * @return a copy in the not-fetched state
         */
        public ProgCommarea detailsNotFetched() {
            return withChangeAction(DETAILS_NOT_FETCHED_LOW_VALUES);
        }

        /**
         * Reproduces {@code SET CCUP-SHOW-DETAILS TO TRUE} ({@code app/cbl/COCRDUPC.cbl:494},
         * {@code :964}, {@code :998}, {@code :1012}).
         *
         * <p>{@code :998} is the third of {@code CardUpdateService}'s outcomes: the record was changed
         * by someone else between the read and the rewrite, so the details are shown again rather than
         * reported as a failure.
         *
         * @return a copy in the show-details state
         */
        public ProgCommarea showDetails() {
            return withChangeAction(SHOW_DETAILS);
        }

        /**
         * Reproduces {@code SET CCUP-CHANGES-NOT-OK TO TRUE} ({@code app/cbl/COCRDUPC.cbl:696}) - the
         * edits found a problem in what the user typed.
         *
         * @return a copy in the changes-not-ok state
         */
        public ProgCommarea changesNotOk() {
            return withChangeAction(CHANGES_NOT_OK);
        }

        /**
         * Reproduces {@code SET CCUP-CHANGES-OK-NOT-CONFIRMED TO TRUE}
         * ({@code app/cbl/COCRDUPC.cbl:713}, {@code :976}) - the edits passed and the user is being
         * asked to confirm with F5.
         *
         * @return a copy in the awaiting-confirmation state
         */
        public ProgCommarea changesOkNotConfirmed() {
            return withChangeAction(CHANGES_OK_NOT_CONFIRMED);
        }

        /**
         * Reproduces {@code SET CCUP-CHANGES-OKAYED-AND-DONE TO TRUE}
         * ({@code app/cbl/COCRDUPC.cbl:1000}) - the rewrite succeeded.
         *
         * @return a copy in the committed state
         */
        public ProgCommarea changesOkayedAndDone() {
            return withChangeAction(CHANGES_OKAYED_AND_DONE);
        }

        /**
         * Reproduces {@code SET CCUP-CHANGES-OKAYED-LOCK-ERROR TO TRUE}
         * ({@code app/cbl/COCRDUPC.cbl:994}) - the first of {@code CardUpdateService}'s failure
         * outcomes, {@code COULD-NOT-LOCK-FOR-UPDATE}.
         *
         * @return a copy in the could-not-lock state
         */
        public ProgCommarea changesOkayedLockError() {
            return withChangeAction(CHANGES_OKAYED_LOCK_ERROR);
        }

        /**
         * Reproduces {@code SET CCUP-CHANGES-OKAYED-BUT-FAILED TO TRUE}
         * ({@code app/cbl/COCRDUPC.cbl:996}) - the second failure outcome,
         * {@code LOCKED-BUT-UPDATE-FAILED}.
         *
         * @return a copy in the locked-but-failed state
         */
        public ProgCommarea changesOkayedButFailed() {
            return withChangeAction(CHANGES_OKAYED_BUT_FAILED);
        }

        /**
         * Replaces {@code CCUP-OLD-DETAILS} - the details as read from the file, which
         * {@code 9300-CHECK-CHANGE-IN-REC} compares against the record on disk before rewriting.
         *
         * @param details the details as read
         * @return a copy carrying them
         * @throws NullPointerException if {@code details} is {@code null}
         */
        public ProgCommarea withOldDetails(CcupDetails details) {
            return new ProgCommarea(ccupChangeAction, details, ccupNewDetails, cardUpdateRecord);
        }

        /**
         * Replaces {@code CCUP-NEW-DETAILS} - the details as typed by the user.
         *
         * @param details the details as typed
         * @return a copy carrying them
         * @throws NullPointerException if {@code details} is {@code null}
         */
        public ProgCommarea withNewDetails(CcupDetails details) {
            return new ProgCommarea(ccupChangeAction, ccupOldDetails, details, cardUpdateRecord);
        }

        /**
         * Replaces {@code CARD-UPDATE-RECORD} - the 150-byte image staged for the file.
         *
         * @param record the staged record
         * @return a copy carrying it
         * @throws NullPointerException if {@code record} is {@code null}
         */
        public ProgCommarea withCardUpdateRecord(CardUpdateRecord record) {
            return new ProgCommarea(ccupChangeAction, ccupOldDetails, ccupNewDetails, record);
        }

        /**
         * {@code 88 CCUP-DETAILS-NOT-FETCHED VALUES LOW-VALUES, SPACES}
         * ({@code app/cbl/COCRDUPC.cbl:278-280}).
         *
         * <p>True for both byte states, which are genuinely two states and not one: a space is
         * {@code 0x40} under EBCDIC and {@code 0x20} under ASCII, while {@code LOW-VALUES} is
         * {@code 0x00} under both.
         *
         * @return {@code true} when the change action is {@code LOW-VALUES} or a space
         */
        public boolean isCcupDetailsNotFetched() {
            return DETAILS_NOT_FETCHED_LOW_VALUES.equals(ccupChangeAction)
                    || DETAILS_NOT_FETCHED_SPACES.equals(ccupChangeAction);
        }

        /**
         * {@code 88 CCUP-SHOW-DETAILS VALUE 'S'} ({@code app/cbl/COCRDUPC.cbl:281}).
         *
         * @return {@code true} when the change action is {@code 'S'}
         */
        public boolean isCcupShowDetails() {
            return SHOW_DETAILS.equals(ccupChangeAction);
        }

        /**
         * {@code 88 CCUP-CHANGES-MADE VALUES 'E', 'N', 'C', 'L', 'F'}
         * ({@code app/cbl/COCRDUPC.cbl:282-284}) - a grouping level that overlaps five single-valued
         * ones. Tested at {@code :1113} and never {@code SET}.
         *
         * @return {@code true} when the change action is any of the five
         */
        public boolean isCcupChangesMade() {
            return CHANGES_MADE_VALUES.contains(ccupChangeAction);
        }

        /**
         * {@code 88 CCUP-CHANGES-NOT-OK VALUE 'E'} ({@code app/cbl/COCRDUPC.cbl:285}).
         *
         * @return {@code true} when the change action is {@code 'E'}
         */
        public boolean isCcupChangesNotOk() {
            return CHANGES_NOT_OK.equals(ccupChangeAction);
        }

        /**
         * {@code 88 CCUP-CHANGES-OK-NOT-CONFIRMED VALUE 'N'} ({@code app/cbl/COCRDUPC.cbl:286}).
         *
         * @return {@code true} when the change action is {@code 'N'}
         */
        public boolean isCcupChangesOkNotConfirmed() {
            return CHANGES_OK_NOT_CONFIRMED.equals(ccupChangeAction);
        }

        /**
         * {@code 88 CCUP-CHANGES-OKAYED-AND-DONE VALUE 'C'} ({@code app/cbl/COCRDUPC.cbl:287}).
         *
         * @return {@code true} when the change action is {@code 'C'}
         */
        public boolean isCcupChangesOkayedAndDone() {
            return CHANGES_OKAYED_AND_DONE.equals(ccupChangeAction);
        }

        /**
         * {@code 88 CCUP-CHANGES-FAILED VALUES 'L', 'F'} ({@code app/cbl/COCRDUPC.cbl:288}) - the
         * second grouping level, true for the two failure bytes and for nothing else. Tested at
         * {@code :518} and never {@code SET}.
         *
         * @return {@code true} when the change action is {@code 'L'} or {@code 'F'}
         */
        public boolean isCcupChangesFailed() {
            return CHANGES_FAILED_VALUES.contains(ccupChangeAction);
        }

        /**
         * {@code 88 CCUP-CHANGES-OKAYED-LOCK-ERROR VALUE 'L'} ({@code app/cbl/COCRDUPC.cbl:289}).
         *
         * @return {@code true} when the change action is {@code 'L'}
         */
        public boolean isCcupChangesOkayedLockError() {
            return CHANGES_OKAYED_LOCK_ERROR.equals(ccupChangeAction);
        }

        /**
         * {@code 88 CCUP-CHANGES-OKAYED-BUT-FAILED VALUE 'F'} ({@code app/cbl/COCRDUPC.cbl:290}).
         *
         * @return {@code true} when the change action is {@code 'F'}
         */
        public boolean isCcupChangesOkayedButFailed() {
            return CHANGES_OKAYED_BUT_FAILED.equals(ccupChangeAction);
        }

        /**
         * Names the change action for a log line or an assertion message, without deciding anything.
         *
         * @return the condition name that holds, for example {@code "CCUP-CHANGES-OKAYED-AND-DONE"},
         *         or {@code "UNRECOGNISED"} plus the byte in hex for a state no {@code 88}-level
         *         covers - the state that reaches {@code 2000-DECIDE-ACTION}'s {@code WHEN OTHER}
         */
        public String describeChangeAction() {
            if (isCcupDetailsNotFetched()) {
                return "CCUP-DETAILS-NOT-FETCHED";
            }
            if (isCcupShowDetails()) {
                return "CCUP-SHOW-DETAILS";
            }
            if (isCcupChangesNotOk()) {
                return "CCUP-CHANGES-NOT-OK";
            }
            if (isCcupChangesOkNotConfirmed()) {
                return "CCUP-CHANGES-OK-NOT-CONFIRMED";
            }
            if (isCcupChangesOkayedAndDone()) {
                return "CCUP-CHANGES-OKAYED-AND-DONE";
            }
            if (isCcupChangesOkayedLockError()) {
                return "CCUP-CHANGES-OKAYED-LOCK-ERROR";
            }
            if (isCcupChangesOkayedButFailed()) {
                return "CCUP-CHANGES-OKAYED-BUT-FAILED";
            }
            return "UNRECOGNISED(" + BmsAttributes.toHex((byte) ccupChangeAction.charAt(0)) + ")";
        }

        /**
         * Renders the commarea as its {@value #COMMAREA_LENGTH}-byte image.
         *
         * @param charset the code page, named explicitly by the caller
         * @return a new array of exactly {@value #COMMAREA_LENGTH} bytes
         * @throws NullPointerException if {@code charset} is {@code null}
         */
        public byte[] encode(Charset charset) {
            Objects.requireNonNull(charset, "A charset is required to encode WS-THIS-PROGCOMMAREA");
            FixedWidthRecord record = FixedWidthRecord.forLayout(LAYOUT, charset);
            record.writeSpan(changeActionSpan(), ccupChangeAction);
            ccupOldDetails.writeInto(record, DetailsPrefix.OLD, OLD_DETAILS_OFFSET);
            ccupNewDetails.writeInto(record, DetailsPrefix.NEW, NEW_DETAILS_OFFSET);
            cardUpdateRecord.writeInto(record, CARD_UPDATE_RECORD_OFFSET);
            return record.toByteArray();
        }

        /**
         * Decodes a {@value #COMMAREA_LENGTH}-byte image.
         *
         * @param image   the stored bytes; exactly {@value #COMMAREA_LENGTH} of them
         * @param charset the code page, named explicitly
         * @return the decoded commarea
         * @throws NullPointerException     if {@code image} or {@code charset} is {@code null}
         * @throws IllegalArgumentException if {@code image} is not exactly
         *                                  {@value #COMMAREA_LENGTH} bytes
         */
        public static ProgCommarea decode(byte[] image, Charset charset) {
            Objects.requireNonNull(image, "An image is required to decode WS-THIS-PROGCOMMAREA");
            Objects.requireNonNull(charset, "A charset is required to decode WS-THIS-PROGCOMMAREA");
            FixedWidthRecord record = FixedWidthRecord.copyOf(image, COMMAREA_LENGTH, charset);
            return new ProgCommarea(record.readSpan(changeActionSpan()),
                    CcupDetails.readFrom(record, DetailsPrefix.OLD, OLD_DETAILS_OFFSET),
                    CcupDetails.readFrom(record, DetailsPrefix.NEW, NEW_DETAILS_OFFSET),
                    CardUpdateRecord.readFrom(record, CARD_UPDATE_RECORD_OFFSET));
        }

        /**
         * Every elementary item of the commarea keyed by its copybook name, for the parity differ.
         *
         * <p>The two details groups contribute their own prefixed names, so
         * {@code CCUP-OLD-ACCTID} and {@code CCUP-NEW-ACCTID} are distinct keys and a difference is
         * always attributed to the right group.
         *
         * @return an unmodifiable insertion-ordered map of 1 + 8 + 8 + 6 = 23 entries
         */
        public Map<String, String> fieldImages() {
            Map<String, String> images = new LinkedHashMap<>(48);
            images.put(CHANGE_ACTION_FIELD, ccupChangeAction);
            images.putAll(ccupOldDetails.fieldImages(DetailsPrefix.OLD));
            images.putAll(ccupNewDetails.fieldImages(DetailsPrefix.NEW));
            images.putAll(cardUpdateRecord.fieldImages());
            return Collections.unmodifiableMap(images);
        }

        /** @return the one-byte {@code CCUP-CHANGE-ACTION} span */
        private static FieldSpan changeActionSpan() {
            return FieldSpan.alphanumeric(CHANGE_ACTION_FIELD, CHANGE_ACTION_OFFSET,
                    CHANGE_ACTION_LENGTH);
        }

        /**
         * Assembles the {@value #COMMAREA_LENGTH}-byte layout from the change action and the three
         * groups.
         *
         * @return the validated layout
         */
        private static RecordLayout declareCommareaLayout() {
            List<FieldSpan> spans = new ArrayList<>(23);
            spans.add(changeActionSpan());
            spans.addAll(CcupDetails.spansAt(DetailsPrefix.OLD, OLD_DETAILS_OFFSET));
            spans.addAll(CcupDetails.spansAt(DetailsPrefix.NEW, NEW_DETAILS_OFFSET));
            spans.addAll(CardUpdateRecord.spansAt(CARD_UPDATE_RECORD_OFFSET));
            return new RecordLayout(COMMAREA_LENGTH, spans);
        }
    }

    // =================================================================================================
    // CCUP-OLD-DETAILS and CCUP-NEW-DETAILS - identical 89-byte shapes under two different name
    // prefixes, app/cbl/COCRDUPC.cbl:291-301 and :303-313.
    // =================================================================================================

    /**
     * Which of the two 89-byte details groups a set of names belongs to.
     *
     * <p>The two groups are declared separately in the source with identical shapes and different
     * name prefixes. The prefix is modelled here, alongside the values, rather than <em>inside</em>
     * {@link CcupDetails} - and that is a deliberate choice with a specific consequence: because the
     * prefix is not a component of the record, {@code oldDetails.equals(newDetails)} is a genuine
     * field-for-field comparison of the two groups' contents. That comparison is exactly what
     * {@code COCRDUPC} performs to decide whether anything changed
     * ({@code app/cbl/COCRDUPC.cbl:680-681}), so making the prefix part of the value would have
     * quietly made every such comparison false.
     */
    public enum DetailsPrefix {

        /** {@code CCUP-OLD-DETAILS} - the details as read from the card file. */
        OLD("CCUP-OLD-", "CCUP-OLD-DETAILS"),

        /** {@code CCUP-NEW-DETAILS} - the details as typed by the user. */
        NEW("CCUP-NEW-", "CCUP-NEW-DETAILS");

        /** The item-name prefix, hyphen included. */
        private final String prefix;

        /** The {@code 05}-level group name. */
        private final String groupName;

        DetailsPrefix(String prefix, String groupName) {
            this.prefix = prefix;
            this.groupName = groupName;
        }

        /**
         * The item-name prefix this group qualifies every field name with.
         *
         * @return the prefix, hyphen included, for example {@code "CCUP-OLD-"}
         */
        public String prefix() {
            return prefix;
        }

        /**
         * The {@code 05}-level group name as the source declares it.
         *
         * @return the group name, for example {@code "CCUP-OLD-DETAILS"}
         */
        public String groupName() {
            return groupName;
        }

        /**
         * Qualifies an item suffix with this group's prefix.
         *
         * @param suffix the item suffix as the copybook spells it, for example {@code "CVV-CD"}
         * @return the full item name, for example {@code "CCUP-OLD-CVV-CD"}
         */
        public String item(String suffix) {
            return prefix + suffix;
        }

        /**
         * The {@code 10}-level group name of the 59-byte span the program compares as a whole at
         * {@code app/cbl/COCRDUPC.cbl:680-681}.
         *
         * @return {@code CCUP-OLD-CARDDATA} or {@code CCUP-NEW-CARDDATA}
         *         ({@code app/cbl/COCRDUPC.cbl:295}, {@code :307})
         */
        public String carddataName() {
            return item(CcupDetails.CARDDATA_SUFFIX);
        }

        /**
         * The {@code 20}-level group name of the eight-byte expiry span, misspelled in the source and
         * carried verbatim.
         *
         * @return {@code CCUP-OLD-EXPIRAION-DATE} or {@code CCUP-NEW-EXPIRAION-DATE}
         *         ({@code app/cbl/COCRDUPC.cbl:297}, {@code :309})
         */
        public String expiraionDateName() {
            return item(CcupDetails.EXPIRAION_DATE_SUFFIX);
        }
    }

    /**
     * One 89-byte card-details group: {@code CCUP-OLD-DETAILS}
     * ({@code app/cbl/COCRDUPC.cbl:291-301}) or {@code CCUP-NEW-DETAILS} ({@code :303-313}). The two
     * are declared separately with identical shapes, so one immutable type serves both and
     * {@link DetailsPrefix} supplies the names.
     *
     * <pre>
     *   offset  bytes  PICTURE  item (OLD group)              source line
     *   ------  -----  -------  ----------------------------  -----------
     *        0     11  X(11)    CCUP-OLD-ACCTID                  L292
     *       11     16  X(16)    CCUP-OLD-CARDID                  L293
     *       27      3  X(3)     CCUP-OLD-CVV-CD                  L294
     *       30     50  X(50)    CCUP-OLD-CRDNAME                 L296
     *       80      4  X(4)     CCUP-OLD-EXPYEAR                 L298
     *       84      2  X(2)     CCUP-OLD-EXPMON                  L299
     *       86      2  X(2)     CCUP-OLD-EXPDAY                  L300
     *       88      1  X(1)     CCUP-OLD-CRDSTCD                 L301
     *   ------  -----
     *       89     89  = 11 + 16 + 3 + 50 + 4 + 2 + 2 + 1
     * </pre>
     *
     * <h2>Two group levels that carry no bytes of their own</h2>
     *
     * <p>{@code CCUP-OLD-CARDDATA} ({@code :295}) spans the last four items - 50 + 8 + 1 =
     * {@value #CARDDATA_LENGTH} bytes - and {@code CCUP-OLD-EXPIRAION-DATE} ({@code :297}) spans three
     * of those - {@value #EXPIRAION_DATE_LENGTH} bytes. Being groups they contribute no span to a
     * flattened layout, but they are addressable as values and one of them is load-bearing:
     * {@code app/cbl/COCRDUPC.cbl:680-681} compares
     * {@code FUNCTION UPPER-CASE(CCUP-NEW-CARDDATA)} against
     * {@code FUNCTION UPPER-CASE(CCUP-OLD-CARDDATA)} as whole groups to decide whether the user
     * changed anything, and {@code :653} moves {@code LOW-VALUES} into
     * {@code CCUP-NEW-CARDDATA} as a whole group. {@link #carddata()} is that value.
     *
     * <h2>Deliberate inconsistencies, all preserved</h2>
     *
     * <ul>
     *   <li>{@code EXPIRAION} is misspelled at {@code :297} and {@code :309}, mirroring
     *       {@code app/cpy/CVACT02Y.cpy:9}. Carried verbatim (<strong>I1</strong>).</li>
     *   <li>The expiry date here is year + month + day with <strong>no separators</strong>, 8 bytes,
     *       whereas {@link CardUpdateRecord#cardUpdateExpiraionDate()} is a 10-byte
     *       {@code YYYY-MM-DD}. Two shapes in one commarea, and they are not unified.</li>
     *   <li>{@code CCUP-OLD-CVV-CD} and {@code CCUP-NEW-CVV-CD} are {@code PIC X(3)} - alphanumeric -
     *       whereas {@code CARD-UPDATE-CVV-CD} is {@code PIC 9(03)} - numeric. So the CVV is a
     *       {@link String} here and an {@code int} there, exactly as declared. Neither is masked, in
     *       keeping with practice <strong>B6</strong>.</li>
     * </ul>
     *
     * @param acctid  {@code ...-ACCTID PIC X(11)}, L292 / L304
     * @param cardid  {@code ...-CARDID PIC X(16)}, L293 / L305
     * @param cvvCd   {@code ...-CVV-CD PIC X(3)}, L294 / L306 - alphanumeric, not numeric
     * @param crdname {@code ...-CRDNAME PIC X(50)}, L296 / L308
     * @param expyear {@code ...-EXPYEAR PIC X(4)}, L298 / L310
     * @param expmon  {@code ...-EXPMON PIC X(2)}, L299 / L311
     * @param expday  {@code ...-EXPDAY PIC X(2)}, L300 / L312
     * @param crdstcd {@code ...-CRDSTCD PIC X(1)}, L301 / L313
     */
    public record CcupDetails(String acctid,
                              String cardid,
                              String cvvCd,
                              String crdname,
                              String expyear,
                              String expmon,
                              String expday,
                              String crdstcd) {

        /** Item suffix {@code ACCTID}. */
        public static final String ACCTID_SUFFIX = "ACCTID";

        /** Item suffix {@code CARDID}. */
        public static final String CARDID_SUFFIX = "CARDID";

        /** Item suffix {@code CVV-CD}, hyphen included exactly as the source spells it. */
        public static final String CVV_CD_SUFFIX = "CVV-CD";

        /** Item suffix {@code CRDNAME}. */
        public static final String CRDNAME_SUFFIX = "CRDNAME";

        /** Item suffix {@code EXPYEAR}. */
        public static final String EXPYEAR_SUFFIX = "EXPYEAR";

        /** Item suffix {@code EXPMON}. */
        public static final String EXPMON_SUFFIX = "EXPMON";

        /** Item suffix {@code EXPDAY}. */
        public static final String EXPDAY_SUFFIX = "EXPDAY";

        /** Item suffix {@code CRDSTCD}. */
        public static final String CRDSTCD_SUFFIX = "CRDSTCD";

        /** Group suffix {@code CARDDATA} ({@code app/cbl/COCRDUPC.cbl:295}, {@code :307}). */
        public static final String CARDDATA_SUFFIX = "CARDDATA";

        /**
         * Group suffix {@code EXPIRAION-DATE} ({@code app/cbl/COCRDUPC.cbl:297}, {@code :309}) -
         * misspelled in the source and carried verbatim.
         */
        public static final String EXPIRAION_DATE_SUFFIX = "EXPIRAION-DATE";

        /** {@code ...-ACCTID PIC X(11)} - 11 bytes. */
        public static final int ACCTID_LENGTH = 11;

        /** {@code ...-CARDID PIC X(16)} - 16 bytes. */
        public static final int CARDID_LENGTH = 16;

        /** {@code ...-CVV-CD PIC X(3)} - 3 bytes, alphanumeric. */
        public static final int CVV_CD_LENGTH = 3;

        /** {@code ...-CRDNAME PIC X(50)} - 50 bytes. */
        public static final int CRDNAME_LENGTH = 50;

        /** {@code ...-EXPYEAR PIC X(4)} - 4 bytes. */
        public static final int EXPYEAR_LENGTH = 4;

        /** {@code ...-EXPMON PIC X(2)} - 2 bytes. */
        public static final int EXPMON_LENGTH = 2;

        /** {@code ...-EXPDAY PIC X(2)} - 2 bytes. */
        public static final int EXPDAY_LENGTH = 2;

        /** {@code ...-CRDSTCD PIC X(1)} - 1 byte. */
        public static final int CRDSTCD_LENGTH = 1;

        /** Relative offset of {@code ...-ACCTID} within the group. */
        public static final int ACCTID_OFFSET = 0;

        /** Relative offset of {@code ...-CARDID}. */
        public static final int CARDID_OFFSET = ACCTID_OFFSET + ACCTID_LENGTH;

        /** Relative offset of {@code ...-CVV-CD}. */
        public static final int CVV_CD_OFFSET = CARDID_OFFSET + CARDID_LENGTH;

        /** Relative offset of the {@code ...-CARDDATA} group, and of {@code ...-CRDNAME} within it. */
        public static final int CARDDATA_OFFSET = CVV_CD_OFFSET + CVV_CD_LENGTH;

        /** Relative offset of {@code ...-CRDNAME}. */
        public static final int CRDNAME_OFFSET = CARDDATA_OFFSET;

        /** Relative offset of the {@code ...-EXPIRAION-DATE} group, and of {@code ...-EXPYEAR}. */
        public static final int EXPIRAION_DATE_OFFSET = CRDNAME_OFFSET + CRDNAME_LENGTH;

        /** Relative offset of {@code ...-EXPYEAR}. */
        public static final int EXPYEAR_OFFSET = EXPIRAION_DATE_OFFSET;

        /** Relative offset of {@code ...-EXPMON}. */
        public static final int EXPMON_OFFSET = EXPYEAR_OFFSET + EXPYEAR_LENGTH;

        /** Relative offset of {@code ...-EXPDAY}. */
        public static final int EXPDAY_OFFSET = EXPMON_OFFSET + EXPMON_LENGTH;

        /** Relative offset of {@code ...-CRDSTCD}. */
        public static final int CRDSTCD_OFFSET = EXPDAY_OFFSET + EXPDAY_LENGTH;

        /**
         * The {@code ...-EXPIRAION-DATE} group width: <strong>8</strong> bytes = 4 + 2 + 2, year then
         * month then day, with no separators.
         */
        public static final int EXPIRAION_DATE_LENGTH = EXPYEAR_LENGTH + EXPMON_LENGTH
                + EXPDAY_LENGTH;

        /**
         * The {@code ...-CARDDATA} group width: <strong>59</strong> bytes = 50 + 8 + 1. This is the
         * span {@code app/cbl/COCRDUPC.cbl:680-681} compares as a whole.
         */
        public static final int CARDDATA_LENGTH = CRDNAME_LENGTH + EXPIRAION_DATE_LENGTH
                + CRDSTCD_LENGTH;

        /**
         * The declared group width: <strong>89</strong> bytes = 11 + 16 + 3 + 59.
         */
        public static final int DETAILS_LENGTH = ACCTID_LENGTH + CARDID_LENGTH + CVV_CD_LENGTH
                + CARDDATA_LENGTH;

        /**
         * Pads every item on the right to its declared width, rejecting an over-wide one.
         *
         * <p>Padding is the lossless half of a COBOL alphanumeric {@code MOVE} and is applied here so
         * an instance can never hold a short item. Truncation is <strong>not</strong> applied, because
         * these values are commarea contents rather than screen items: the program moves them between
         * equal-width fields, so an over-wide value is a caller defect and not a {@code MOVE} that
         * needs a direction. A caller that genuinely wants the screen's truncating move performs it
         * with {@link FixedWidthCodec#movePicX(String, int)} first.
         *
         * @throws NullPointerException     if any component is {@code null}
         * @throws IllegalArgumentException if any component is wider than its declared width
         */
        public CcupDetails {
            acctid = padded(acctid, ACCTID_LENGTH, ACCTID_SUFFIX);
            cardid = padded(cardid, CARDID_LENGTH, CARDID_SUFFIX);
            cvvCd = padded(cvvCd, CVV_CD_LENGTH, CVV_CD_SUFFIX);
            crdname = padded(crdname, CRDNAME_LENGTH, CRDNAME_SUFFIX);
            expyear = padded(expyear, EXPYEAR_LENGTH, EXPYEAR_SUFFIX);
            expmon = padded(expmon, EXPMON_LENGTH, EXPMON_SUFFIX);
            expday = padded(expday, EXPDAY_LENGTH, EXPDAY_SUFFIX);
            crdstcd = padded(crdstcd, CRDSTCD_LENGTH, CRDSTCD_SUFFIX);
        }

        /**
         * The state {@code INITIALIZE CCUP-OLD-DETAILS} ({@code app/cbl/COCRDUPC.cbl:1345}) and
         * {@code INITIALIZE CCUP-NEW-DETAILS} ({@code :586}) leave behind: every item space-filled to
         * its declared width, since a COBOL {@code INITIALIZE} without {@code REPLACING} sets every
         * alphanumeric item to spaces and every item of this group is {@code PIC X}.
         *
         * @return the initialised group; never {@code null}
         */
        public static CcupDetails initialised() {
            return new CcupDetails(spaces(ACCTID_LENGTH), spaces(CARDID_LENGTH),
                    spaces(CVV_CD_LENGTH), spaces(CRDNAME_LENGTH), spaces(EXPYEAR_LENGTH),
                    spaces(EXPMON_LENGTH), spaces(EXPDAY_LENGTH), spaces(CRDSTCD_LENGTH));
        }

        /**
         * The {@code ...-CARDDATA} group value: the embossed name, the eight-character expiry date and
         * the status byte concatenated, {@value #CARDDATA_LENGTH} characters.
         *
         * <p>This is the value {@code app/cbl/COCRDUPC.cbl:680-681} compares between the two groups,
         * upper-cased on both sides, to decide whether the user changed anything. It is offered as a
         * value only; the comparison itself belongs to {@code CardUpdateService}, which owns the
         * {@code 9300-CHECK-CHANGE-IN-REC} logic.
         *
         * @return exactly {@value #CARDDATA_LENGTH} characters
         */
        public String carddata() {
            return crdname + expiraionDate() + crdstcd;
        }

        /**
         * The {@code ...-EXPIRAION-DATE} group value: year, month, day concatenated with no
         * separators, {@value #EXPIRAION_DATE_LENGTH} characters.
         *
         * <p>Not to be confused with {@link CardUpdateRecord#cardUpdateExpiraionDate()}, which is ten
         * characters and carries the {@code YYYY-MM-DD} separators. The commarea holds both shapes and
         * they are deliberately different.
         *
         * @return exactly {@value #EXPIRAION_DATE_LENGTH} characters
         */
        public String expiraionDate() {
            return expyear + expmon + expday;
        }

        /**
         * Replaces the account identifier.
         *
         * @param replacement the new value, no wider than {@value #ACCTID_LENGTH}
         * @return a copy carrying it
         */
        public CcupDetails withAcctid(String replacement) {
            return new CcupDetails(replacement, cardid, cvvCd, crdname, expyear, expmon, expday,
                    crdstcd);
        }

        /**
         * Replaces the card identifier.
         *
         * @param replacement the new value, no wider than {@value #CARDID_LENGTH}
         * @return a copy carrying it
         */
        public CcupDetails withCardid(String replacement) {
            return new CcupDetails(acctid, replacement, cvvCd, crdname, expyear, expmon, expday,
                    crdstcd);
        }

        /**
         * Replaces the CVV.
         *
         * @param replacement the new value, no wider than {@value #CVV_CD_LENGTH}
         * @return a copy carrying it
         */
        public CcupDetails withCvvCd(String replacement) {
            return new CcupDetails(acctid, cardid, replacement, crdname, expyear, expmon, expday,
                    crdstcd);
        }

        /**
         * Replaces the embossed name.
         *
         * @param replacement the new value, no wider than {@value #CRDNAME_LENGTH}
         * @return a copy carrying it
         */
        public CcupDetails withCrdname(String replacement) {
            return new CcupDetails(acctid, cardid, cvvCd, replacement, expyear, expmon, expday,
                    crdstcd);
        }

        /**
         * Replaces the expiry year.
         *
         * @param replacement the new value, no wider than {@value #EXPYEAR_LENGTH}
         * @return a copy carrying it
         */
        public CcupDetails withExpyear(String replacement) {
            return new CcupDetails(acctid, cardid, cvvCd, crdname, replacement, expmon, expday,
                    crdstcd);
        }

        /**
         * Replaces the expiry month.
         *
         * @param replacement the new value, no wider than {@value #EXPMON_LENGTH}
         * @return a copy carrying it
         */
        public CcupDetails withExpmon(String replacement) {
            return new CcupDetails(acctid, cardid, cvvCd, crdname, expyear, replacement, expday,
                    crdstcd);
        }

        /**
         * Replaces the expiry day.
         *
         * @param replacement the new value, no wider than {@value #EXPDAY_LENGTH}
         * @return a copy carrying it
         */
        public CcupDetails withExpday(String replacement) {
            return new CcupDetails(acctid, cardid, cvvCd, crdname, expyear, expmon, replacement,
                    crdstcd);
        }

        /**
         * Replaces the active status.
         *
         * @param replacement the new value, no wider than {@value #CRDSTCD_LENGTH}
         * @return a copy carrying it
         */
        public CcupDetails withCrdstcd(String replacement) {
            return new CcupDetails(acctid, cardid, cvvCd, crdname, expyear, expmon, expday,
                    replacement);
        }

        /**
         * The eight elementary items keyed by their fully qualified copybook names under the given
         * prefix, for the parity differ.
         *
         * @param prefix which group these names belong to
         * @return an unmodifiable insertion-ordered map of eight entries, for example keyed
         *         {@code CCUP-OLD-ACCTID} through {@code CCUP-OLD-CRDSTCD}
         * @throws NullPointerException if {@code prefix} is {@code null}
         */
        public Map<String, String> fieldImages(DetailsPrefix prefix) {
            Objects.requireNonNull(prefix, "A DetailsPrefix is required: the two groups differ only "
                    + "in their item names, so a nameless image could not be attributed to either");
            Map<String, String> images = new LinkedHashMap<>(16);
            images.put(prefix.item(ACCTID_SUFFIX), acctid);
            images.put(prefix.item(CARDID_SUFFIX), cardid);
            images.put(prefix.item(CVV_CD_SUFFIX), cvvCd);
            images.put(prefix.item(CRDNAME_SUFFIX), crdname);
            images.put(prefix.item(EXPYEAR_SUFFIX), expyear);
            images.put(prefix.item(EXPMON_SUFFIX), expmon);
            images.put(prefix.item(EXPDAY_SUFFIX), expday);
            images.put(prefix.item(CRDSTCD_SUFFIX), crdstcd);
            return Collections.unmodifiableMap(images);
        }

        /**
         * The eight spans of this group at an absolute base offset, named for the given prefix.
         *
         * @param prefix     which group's names to use
         * @param baseOffset the absolute offset of the group
         * @return the eight spans in declaration order
         * @throws NullPointerException if {@code prefix} is {@code null}
         */
        public static List<FieldSpan> spansAt(DetailsPrefix prefix, int baseOffset) {
            Objects.requireNonNull(prefix, "A DetailsPrefix is required to name the spans");
            return List.of(
                    FieldSpan.alphanumeric(prefix.item(ACCTID_SUFFIX), baseOffset + ACCTID_OFFSET,
                            ACCTID_LENGTH),
                    FieldSpan.alphanumeric(prefix.item(CARDID_SUFFIX), baseOffset + CARDID_OFFSET,
                            CARDID_LENGTH),
                    FieldSpan.alphanumeric(prefix.item(CVV_CD_SUFFIX), baseOffset + CVV_CD_OFFSET,
                            CVV_CD_LENGTH),
                    FieldSpan.alphanumeric(prefix.item(CRDNAME_SUFFIX), baseOffset + CRDNAME_OFFSET,
                            CRDNAME_LENGTH),
                    FieldSpan.alphanumeric(prefix.item(EXPYEAR_SUFFIX), baseOffset + EXPYEAR_OFFSET,
                            EXPYEAR_LENGTH),
                    FieldSpan.alphanumeric(prefix.item(EXPMON_SUFFIX), baseOffset + EXPMON_OFFSET,
                            EXPMON_LENGTH),
                    FieldSpan.alphanumeric(prefix.item(EXPDAY_SUFFIX), baseOffset + EXPDAY_OFFSET,
                            EXPDAY_LENGTH),
                    FieldSpan.alphanumeric(prefix.item(CRDSTCD_SUFFIX), baseOffset + CRDSTCD_OFFSET,
                            CRDSTCD_LENGTH));
        }

        /**
         * Writes this group into a record area at an absolute base offset.
         *
         * @param record     the area to write into
         * @param prefix     which group's names to use
         * @param baseOffset the absolute offset of the group
         * @throws NullPointerException if any argument is {@code null}
         */
        public void writeInto(FixedWidthRecord record, DetailsPrefix prefix, int baseOffset) {
            Objects.requireNonNull(record, "A record area is required to write a details group");
            List<FieldSpan> spans = spansAt(prefix, baseOffset);
            List<String> values = List.of(acctid, cardid, cvvCd, crdname, expyear, expmon, expday,
                    crdstcd);
            for (int index = 0; index < spans.size(); index++) {
                record.writeSpan(spans.get(index), values.get(index));
            }
        }

        /**
         * Reads a group from a record area at an absolute base offset.
         *
         * @param record     the area to read
         * @param prefix     which group's names to use
         * @param baseOffset the absolute offset of the group
         * @return the decoded group
         * @throws NullPointerException if {@code record} or {@code prefix} is {@code null}
         */
        public static CcupDetails readFrom(FixedWidthRecord record, DetailsPrefix prefix,
                int baseOffset) {
            Objects.requireNonNull(record, "A record area is required to read a details group");
            List<FieldSpan> spans = spansAt(prefix, baseOffset);
            return new CcupDetails(record.readSpan(spans.get(0)), record.readSpan(spans.get(1)),
                    record.readSpan(spans.get(2)), record.readSpan(spans.get(3)),
                    record.readSpan(spans.get(4)), record.readSpan(spans.get(5)),
                    record.readSpan(spans.get(6)), record.readSpan(spans.get(7)));
        }

        /**
         * Right-pads a {@code PIC X} value to its declared width, rejecting an over-wide one.
         *
         * @param value  the value
         * @param width  the declared width
         * @param suffix the item suffix, for the diagnostic
         * @return the value at exactly {@code width} characters
         */
        private static String padded(String value, int width, String suffix) {
            Objects.requireNonNull(value, "A value is required for ...-" + suffix + "; COBOL has no "
                    + "null, so pass an empty string for SPACES");
            if (value.length() > width) {
                throw new IllegalArgumentException("...-" + suffix + " is PIC X(" + width + ") but "
                        + value.length() + " character(s) were supplied. This constructor never "
                        + "truncates, because the direction differs by PICTURE; apply "
                        + "FixedWidthCodec.movePicX(value, " + width + ") first if COBOL MOVE "
                        + "semantics are what is wanted");
            }
            return PICTURE_RULES.movePicX(value, width);
        }

        /** @return a string of {@code length} spaces */
        private static String spaces(int length) {
            return CardScreenState.spaces(length);
        }
    }

    // =================================================================================================
    // CARD-UPDATE-RECORD - app/cbl/COCRDUPC.cbl:314-321, 150 bytes, modelled INLINE and deliberately
    // NOT reusing card/model/CardRecord.
    // =================================================================================================

    /**
     * The 150-byte card image the program stages for the file, {@code CARD-UPDATE-RECORD}
     * ({@code app/cbl/COCRDUPC.cbl:314-321}). Written by
     * {@code EXEC CICS REWRITE ... FROM(CARD-UPDATE-RECORD) LENGTH(LENGTH OF CARD-UPDATE-RECORD)}
     * ({@code :1479-1480}) and initialised at {@code :1461}.
     *
     * <pre>
     *   offset  bytes  PICTURE  item                            source line
     *   ------  -----  -------  ------------------------------  -----------
     *        0     16  X(16)    CARD-UPDATE-NUM                    L315
     *       16     11  9(11)    CARD-UPDATE-ACCT-ID                L316
     *       27      3  9(03)    CARD-UPDATE-CVV-CD                 L317
     *       30     50  X(50)    CARD-UPDATE-EMBOSSED-NAME          L318
     *       80     10  X(10)    CARD-UPDATE-EXPIRAION-DATE         L319
     *       90      1  X(01)    CARD-UPDATE-ACTIVE-STATUS          L320
     *       91     59  X(59)    FILLER                             L321
     *   ------  -----
     *      150    150  = 16 + 11 + 3 + 50 + 10 + 1 + 59
     * </pre>
     *
     * <h2>Why this is modelled here and not taken from {@code card/model/CardRecord}</h2>
     *
     * <p>The geometry is byte-identical to {@code CARD-RECORD} in {@code app/cpy/CVACT02Y.cpy} -
     * 16/11/3/50/10/1/59, the same 150 - but every field <em>name</em> differs:
     * {@code CARD-UPDATE-NUM} against {@code CARD-NUM}, {@code CARD-UPDATE-ACCT-ID} against
     * {@code CARD-ACCT-ID}, and so on. Under AAP &sect;0.1.4 <strong>I1</strong> a field name is part
     * of the contract that field-for-field diffing compares, so reusing {@code CardRecord} would
     * report every difference under the wrong name. The AAP sets this precedent itself by keeping
     * {@code app/cpy/CUSTREC.cpy} separate from {@code app/cpy/CVCUS01Y.cpy} over a single differing
     * field name; two structures with seven differing names are a stronger case, not a weaker one.
     *
     * <h2>{@code FILLER X(59)} is emitted, as spaces</h2>
     *
     * <p>{@code FILLER} is a first-class span of {@link #LAYOUT}, written as spaces by
     * {@link #encode(Charset)} - gate <strong>G21</strong>. Dropping it would make the image 91 bytes
     * and the {@code REWRITE} would fail its length check, which is exactly why the immediate
     * verification is that the image is 150 bytes wide.
     *
     * <h2>Two numeric items, and no floating point anywhere</h2>
     *
     * <p>{@code CARD-UPDATE-ACCT-ID PIC 9(11)} and {@code CARD-UPDATE-CVV-CD PIC 9(03)} are
     * scale-free unsigned pictures, so rule <strong>R4</strong> maps them to {@code long} and
     * {@code int}. There is no {@code PIC 9...V...} field in this record, hence no
     * {@code BigDecimal}, no scale and no rounding - and no {@code double} or {@code float}, ever
     * (gates <strong>G22</strong> and <strong>G24</strong>). Their digits are read and written by
     * {@link FixedWidthCodec}'s {@code PIC 9} helpers, which zero-fill and truncate on the
     * <em>left</em>, and never by {@code Integer.parseInt} or {@code Long.parseLong} over a
     * fixed-width span (practice <strong>B11</strong>).
     *
     * @param cardUpdateNum            {@code CARD-UPDATE-NUM PIC X(16)}, L315 - alphanumeric, so
     *                                 leading zeros survive
     * @param cardUpdateAcctId         {@code CARD-UPDATE-ACCT-ID PIC 9(11)}, L316
     * @param cardUpdateCvvCd          {@code CARD-UPDATE-CVV-CD PIC 9(03)}, L317 - numeric here,
     *                                 whereas {@code CCUP-OLD-CVV-CD} and {@code CCUP-NEW-CVV-CD} are
     *                                 {@code PIC X(3)}; the difference is declared and is preserved
     * @param cardUpdateEmbossedName   {@code CARD-UPDATE-EMBOSSED-NAME PIC X(50)}, L318
     * @param cardUpdateExpiraionDate  {@code CARD-UPDATE-EXPIRAION-DATE PIC X(10)}, L319 - misspelled
     *                                 in the source, carried verbatim, and ten characters
     *                                 ({@code YYYY-MM-DD}) rather than the eight of
     *                                 {@link CcupDetails#expiraionDate()}
     * @param cardUpdateActiveStatus   {@code CARD-UPDATE-ACTIVE-STATUS PIC X(01)}, L320
     */
    public record CardUpdateRecord(String cardUpdateNum,
                                   long cardUpdateAcctId,
                                   int cardUpdateCvvCd,
                                   String cardUpdateEmbossedName,
                                   String cardUpdateExpiraionDate,
                                   String cardUpdateActiveStatus) {

        /** Copybook name of {@link #cardUpdateNum()}, carried verbatim. */
        public static final String NUM_FIELD = "CARD-UPDATE-NUM";

        /** Copybook name of {@link #cardUpdateAcctId()}. */
        public static final String ACCT_ID_FIELD = "CARD-UPDATE-ACCT-ID";

        /** Copybook name of {@link #cardUpdateCvvCd()}. */
        public static final String CVV_CD_FIELD = "CARD-UPDATE-CVV-CD";

        /** Copybook name of {@link #cardUpdateEmbossedName()}. */
        public static final String EMBOSSED_NAME_FIELD = "CARD-UPDATE-EMBOSSED-NAME";

        /**
         * Copybook name of {@link #cardUpdateExpiraionDate()} - <strong>misspelled in the source</strong>
         * at {@code app/cbl/COCRDUPC.cbl:319}, mirroring {@code app/cpy/CVACT02Y.cpy:9}, and carried
         * exactly as declared.
         */
        public static final String EXPIRAION_DATE_FIELD = "CARD-UPDATE-EXPIRAION-DATE";

        /** Copybook name of {@link #cardUpdateActiveStatus()}. */
        public static final String ACTIVE_STATUS_FIELD = "CARD-UPDATE-ACTIVE-STATUS";

        /** {@code CARD-UPDATE-NUM PIC X(16)} - 16 bytes. */
        public static final int NUM_LENGTH = 16;

        /** {@code CARD-UPDATE-ACCT-ID PIC 9(11)} - 11 digits, one byte each. */
        public static final int ACCT_ID_LENGTH = 11;

        /** {@code CARD-UPDATE-CVV-CD PIC 9(03)} - 3 digits. */
        public static final int CVV_CD_LENGTH = 3;

        /** {@code CARD-UPDATE-EMBOSSED-NAME PIC X(50)} - 50 bytes. */
        public static final int EMBOSSED_NAME_LENGTH = 50;

        /** {@code CARD-UPDATE-EXPIRAION-DATE PIC X(10)} - 10 bytes, {@code YYYY-MM-DD}. */
        public static final int EXPIRAION_DATE_LENGTH = 10;

        /** {@code CARD-UPDATE-ACTIVE-STATUS PIC X(01)} - 1 byte. */
        public static final int ACTIVE_STATUS_LENGTH = 1;

        /** {@code FILLER PIC X(59)} - 59 bytes, present and space-filled (gate G21). */
        public static final int FILLER_LENGTH = 59;

        /** Relative offset of {@code CARD-UPDATE-NUM}. */
        public static final int NUM_OFFSET = 0;

        /** Relative offset of {@code CARD-UPDATE-ACCT-ID}. */
        public static final int ACCT_ID_OFFSET = NUM_OFFSET + NUM_LENGTH;

        /** Relative offset of {@code CARD-UPDATE-CVV-CD}. */
        public static final int CVV_CD_OFFSET = ACCT_ID_OFFSET + ACCT_ID_LENGTH;

        /** Relative offset of {@code CARD-UPDATE-EMBOSSED-NAME}. */
        public static final int EMBOSSED_NAME_OFFSET = CVV_CD_OFFSET + CVV_CD_LENGTH;

        /** Relative offset of {@code CARD-UPDATE-EXPIRAION-DATE}. */
        public static final int EXPIRAION_DATE_OFFSET = EMBOSSED_NAME_OFFSET + EMBOSSED_NAME_LENGTH;

        /** Relative offset of {@code CARD-UPDATE-ACTIVE-STATUS}. */
        public static final int ACTIVE_STATUS_OFFSET = EXPIRAION_DATE_OFFSET + EXPIRAION_DATE_LENGTH;

        /** Relative offset of the trailing {@code FILLER}. */
        public static final int FILLER_OFFSET = ACTIVE_STATUS_OFFSET + ACTIVE_STATUS_LENGTH;

        /**
         * The declared total width: <strong>150</strong> bytes = 16 + 11 + 3 + 50 + 10 + 1 + 59.
         *
         * <p>The same 150 that {@code app/cpy/CVACT02Y.cpy:2} states for {@code CARD-RECORD}, which is
         * the point: identical geometry, different names.
         */
        public static final int RECORD_LENGTH = FILLER_OFFSET + FILLER_LENGTH;

        /** One past the largest value {@code PIC 9(11)} can hold. */
        public static final long ACCT_ID_EXCLUSIVE_LIMIT = 100_000_000_000L;

        /** One past the largest value {@code PIC 9(03)} can hold. */
        public static final int CVV_CD_EXCLUSIVE_LIMIT = 1_000;

        /**
         * The complete {@value #RECORD_LENGTH}-byte geometry, {@code FILLER} included.
         *
         * <p>{@link RecordLayout} verifies that the seven spans are contiguous from offset 0 and sum to
         * exactly {@value #RECORD_LENGTH}, so omitting the {@code FILLER} would fail here rather than
         * producing a 91-byte image.
         */
        public static final RecordLayout LAYOUT = new RecordLayout(RECORD_LENGTH, spansAt(0));

        /**
         * Pads each character item to its declared width and checks each numeric item fits its digit
         * count, so an instance can never exist in a shape the record cannot hold.
         *
         * @throws NullPointerException     if any character component is {@code null}
         * @throws IllegalArgumentException if a character component is wider than its declared width,
         *                                  or a numeric component is negative or too large for its
         *                                  digit count
         */
        public CardUpdateRecord {
            cardUpdateNum = padded(cardUpdateNum, NUM_LENGTH, NUM_FIELD);
            requireUnsigned(cardUpdateAcctId, ACCT_ID_FIELD, ACCT_ID_LENGTH,
                    ACCT_ID_EXCLUSIVE_LIMIT);
            requireUnsigned(cardUpdateCvvCd, CVV_CD_FIELD, CVV_CD_LENGTH, CVV_CD_EXCLUSIVE_LIMIT);
            cardUpdateEmbossedName = padded(cardUpdateEmbossedName, EMBOSSED_NAME_LENGTH,
                    EMBOSSED_NAME_FIELD);
            cardUpdateExpiraionDate = padded(cardUpdateExpiraionDate, EXPIRAION_DATE_LENGTH,
                    EXPIRAION_DATE_FIELD);
            cardUpdateActiveStatus = padded(cardUpdateActiveStatus, ACTIVE_STATUS_LENGTH,
                    ACTIVE_STATUS_FIELD);
        }

        /**
         * The state {@code INITIALIZE CARD-UPDATE-RECORD} ({@code app/cbl/COCRDUPC.cbl:1461}) leaves
         * behind: the four {@code PIC X} items space-filled and the two {@code PIC 9} items zero, which
         * is what a COBOL {@code INITIALIZE} without {@code REPLACING} does to each category.
         *
         * @return the initialised record; never {@code null}
         */
        public static CardUpdateRecord initialised() {
            return new CardUpdateRecord(CardScreenState.spaces(NUM_LENGTH), 0L, 0,
                    CardScreenState.spaces(EMBOSSED_NAME_LENGTH),
                    CardScreenState.spaces(EXPIRAION_DATE_LENGTH),
                    CardScreenState.spaces(ACTIVE_STATUS_LENGTH));
        }

        /**
         * Renders the record as its {@value #RECORD_LENGTH}-byte image, with the trailing
         * {@code FILLER} present and space-filled.
         *
         * @param charset the code page, named explicitly by the caller
         * @return a new array of exactly {@value #RECORD_LENGTH} bytes
         * @throws NullPointerException if {@code charset} is {@code null}
         */
        public byte[] encode(Charset charset) {
            Objects.requireNonNull(charset, "A charset is required to encode " + NUM_FIELD
                    + "'s record: fixed-width data is bytes in a specific code page");
            FixedWidthRecord record = FixedWidthRecord.forLayout(LAYOUT, charset);
            writeInto(record, 0);
            return record.toByteArray();
        }

        /**
         * Decodes a {@value #RECORD_LENGTH}-byte image.
         *
         * @param image   the stored bytes; exactly {@value #RECORD_LENGTH} of them
         * @param charset the code page, named explicitly
         * @return the decoded record
         * @throws NullPointerException     if {@code image} or {@code charset} is {@code null}
         * @throws IllegalArgumentException if {@code image} is not exactly {@value #RECORD_LENGTH}
         *                                  bytes, or a numeric span does not hold digits
         */
        public static CardUpdateRecord decode(byte[] image, Charset charset) {
            Objects.requireNonNull(image, "An image is required to decode a CARD-UPDATE-RECORD");
            Objects.requireNonNull(charset, "A charset is required to decode a CARD-UPDATE-RECORD");
            return readFrom(FixedWidthRecord.copyOf(image, RECORD_LENGTH, charset), 0);
        }

        /**
         * Writes this record into a record area at an absolute base offset.
         *
         * <p>The {@code FILLER} span is filled with the area's space byte, which is {@code 0x40} under
         * EBCDIC and {@code 0x20} under ASCII - the code page decides, never this method.
         *
         * @param record     the area to write into
         * @param baseOffset the absolute offset of the record
         * @throws NullPointerException if {@code record} is {@code null}
         */
        public void writeInto(FixedWidthRecord record, int baseOffset) {
            Objects.requireNonNull(record, "A record area is required to write a CARD-UPDATE-RECORD");
            List<FieldSpan> spans = spansAt(baseOffset);
            record.writeSpan(spans.get(0), cardUpdateNum);
            PICTURE_RULES.writePic9(record, spans.get(1), cardUpdateAcctId);
            PICTURE_RULES.writePic9(record, spans.get(2), cardUpdateCvvCd);
            record.writeSpan(spans.get(3), cardUpdateEmbossedName);
            record.writeSpan(spans.get(4), cardUpdateExpiraionDate);
            record.writeSpan(spans.get(5), cardUpdateActiveStatus);
            record.fill(baseOffset + FILLER_OFFSET, FILLER_LENGTH, record.spacePadByte());
        }

        /**
         * Reads a record from a record area at an absolute base offset.
         *
         * @param record     the area to read
         * @param baseOffset the absolute offset of the record
         * @return the decoded record
         * @throws NullPointerException     if {@code record} is {@code null}
         * @throws IllegalArgumentException if a numeric span does not hold digits
         */
        public static CardUpdateRecord readFrom(FixedWidthRecord record, int baseOffset) {
            Objects.requireNonNull(record, "A record area is required to read a CARD-UPDATE-RECORD");
            List<FieldSpan> spans = spansAt(baseOffset);
            return new CardUpdateRecord(record.readSpan(spans.get(0)),
                    PICTURE_RULES.readPic9(record, spans.get(1)),
                    PICTURE_RULES.readPic9AsInt(record, spans.get(2)),
                    record.readSpan(spans.get(3)),
                    record.readSpan(spans.get(4)),
                    record.readSpan(spans.get(5)));
        }

        /**
         * The seven spans of this record at an absolute base offset, {@code FILLER} last.
         *
         * @param baseOffset the absolute offset of the record
         * @return the seven spans in declaration order
         */
        public static List<FieldSpan> spansAt(int baseOffset) {
            return List.of(
                    FieldSpan.alphanumeric(NUM_FIELD, baseOffset + NUM_OFFSET, NUM_LENGTH),
                    FieldSpan.unsignedNumeric(ACCT_ID_FIELD, baseOffset + ACCT_ID_OFFSET,
                            ACCT_ID_LENGTH),
                    FieldSpan.unsignedNumeric(CVV_CD_FIELD, baseOffset + CVV_CD_OFFSET,
                            CVV_CD_LENGTH),
                    FieldSpan.alphanumeric(EMBOSSED_NAME_FIELD, baseOffset + EMBOSSED_NAME_OFFSET,
                            EMBOSSED_NAME_LENGTH),
                    FieldSpan.alphanumeric(EXPIRAION_DATE_FIELD, baseOffset + EXPIRAION_DATE_OFFSET,
                            EXPIRAION_DATE_LENGTH),
                    FieldSpan.alphanumeric(ACTIVE_STATUS_FIELD, baseOffset + ACTIVE_STATUS_OFFSET,
                            ACTIVE_STATUS_LENGTH),
                    FieldSpan.filler(baseOffset + FILLER_OFFSET, FILLER_LENGTH));
        }

        /**
         * The six elementary items keyed by their copybook names, for the parity differ. The two
         * numeric items are rendered as their zero-filled digit images, which is how they appear in the
         * record, rather than as decimal text.
         *
         * @return an unmodifiable insertion-ordered map of six entries
         */
        public Map<String, String> fieldImages() {
            Map<String, String> images = new LinkedHashMap<>(12);
            images.put(NUM_FIELD, cardUpdateNum);
            images.put(ACCT_ID_FIELD, acctIdImage());
            images.put(CVV_CD_FIELD, cvvCdImage());
            images.put(EMBOSSED_NAME_FIELD, cardUpdateEmbossedName);
            images.put(EXPIRAION_DATE_FIELD, cardUpdateExpiraionDate);
            images.put(ACTIVE_STATUS_FIELD, cardUpdateActiveStatus);
            return Collections.unmodifiableMap(images);
        }

        /**
         * {@code CARD-UPDATE-ACCT-ID} as it is stored: {@value #ACCT_ID_LENGTH} digits, zero-filled on
         * the left.
         *
         * @return exactly {@value #ACCT_ID_LENGTH} digits
         */
        public String acctIdImage() {
            return PICTURE_RULES.movePic9(cardUpdateAcctId, ACCT_ID_LENGTH);
        }

        /**
         * {@code CARD-UPDATE-CVV-CD} as it is stored: {@value #CVV_CD_LENGTH} digits, zero-filled on
         * the left. Not masked, exactly as the COBOL stores it (practice <strong>B6</strong>).
         *
         * @return exactly {@value #CVV_CD_LENGTH} digits
         */
        public String cvvCdImage() {
            return PICTURE_RULES.movePic9(cardUpdateCvvCd, CVV_CD_LENGTH);
        }

        /**
         * Replaces the card number.
         *
         * @param replacement the new value, no wider than {@value #NUM_LENGTH}
         * @return a copy carrying it
         */
        public CardUpdateRecord withCardUpdateNum(String replacement) {
            return new CardUpdateRecord(replacement, cardUpdateAcctId, cardUpdateCvvCd,
                    cardUpdateEmbossedName, cardUpdateExpiraionDate, cardUpdateActiveStatus);
        }

        /**
         * Replaces the account identifier.
         *
         * @param replacement the new value, below {@value #ACCT_ID_EXCLUSIVE_LIMIT}
         * @return a copy carrying it
         */
        public CardUpdateRecord withCardUpdateAcctId(long replacement) {
            return new CardUpdateRecord(cardUpdateNum, replacement, cardUpdateCvvCd,
                    cardUpdateEmbossedName, cardUpdateExpiraionDate, cardUpdateActiveStatus);
        }

        /**
         * Replaces the CVV.
         *
         * @param replacement the new value, below {@value #CVV_CD_EXCLUSIVE_LIMIT}
         * @return a copy carrying it
         */
        public CardUpdateRecord withCardUpdateCvvCd(int replacement) {
            return new CardUpdateRecord(cardUpdateNum, cardUpdateAcctId, replacement,
                    cardUpdateEmbossedName, cardUpdateExpiraionDate, cardUpdateActiveStatus);
        }

        /**
         * Replaces the embossed name.
         *
         * @param replacement the new value, no wider than {@value #EMBOSSED_NAME_LENGTH}
         * @return a copy carrying it
         */
        public CardUpdateRecord withCardUpdateEmbossedName(String replacement) {
            return new CardUpdateRecord(cardUpdateNum, cardUpdateAcctId, cardUpdateCvvCd,
                    replacement, cardUpdateExpiraionDate, cardUpdateActiveStatus);
        }

        /**
         * Replaces the expiry date, the {@value #EXPIRAION_DATE_LENGTH}-character {@code YYYY-MM-DD}
         * form.
         *
         * @param replacement the new value, no wider than {@value #EXPIRAION_DATE_LENGTH}
         * @return a copy carrying it
         */
        public CardUpdateRecord withCardUpdateExpiraionDate(String replacement) {
            return new CardUpdateRecord(cardUpdateNum, cardUpdateAcctId, cardUpdateCvvCd,
                    cardUpdateEmbossedName, replacement, cardUpdateActiveStatus);
        }

        /**
         * Replaces the active status.
         *
         * @param replacement the new value, no wider than {@value #ACTIVE_STATUS_LENGTH}
         * @return a copy carrying it
         */
        public CardUpdateRecord withCardUpdateActiveStatus(String replacement) {
            return new CardUpdateRecord(cardUpdateNum, cardUpdateAcctId, cardUpdateCvvCd,
                    cardUpdateEmbossedName, cardUpdateExpiraionDate, replacement);
        }

        /**
         * Right-pads a {@code PIC X} item to its declared width, rejecting an over-wide value.
         *
         * @param value     the value
         * @param width     the declared width
         * @param cobolName the item name, for the diagnostic
         * @return the value at exactly {@code width} characters
         */
        private static String padded(String value, int width, String cobolName) {
            Objects.requireNonNull(value, "A value is required for " + cobolName + "; COBOL has no "
                    + "null, so pass an empty string for SPACES");
            if (value.length() > width) {
                throw new IllegalArgumentException(cobolName + " is PIC X(" + width + ") but "
                        + value.length() + " character(s) were supplied. This constructor never "
                        + "truncates, because COBOL truncates PIC X on the right and PIC 9 on the "
                        + "left; apply FixedWidthCodec.movePicX(value, " + width + ") first if that "
                        + "is what is intended");
            }
            return PICTURE_RULES.movePicX(value, width);
        }

        /**
         * Checks that a value fits an unsigned {@code PIC 9(n)} item.
         *
         * @param value          the value
         * @param cobolName      the item name
         * @param digits         the declared digit count
         * @param exclusiveLimit one past the largest value the item can hold
         * @throws IllegalArgumentException if {@code value} is negative or at least
         *                                  {@code exclusiveLimit}
         */
        private static void requireUnsigned(long value, String cobolName, int digits,
                long exclusiveLimit) {
            if (value < 0) {
                throw new IllegalArgumentException("Cannot store " + value + " in " + cobolName
                        + ": app/cbl/COCRDUPC.cbl declares it PIC 9(" + digits + "), an unsigned "
                        + "picture with no sign position, so a negative value has no representation "
                        + "in it");
            }
            if (value >= exclusiveLimit) {
                throw new IllegalArgumentException("Cannot store " + value + " in " + cobolName
                        + ": it declares only " + digits + " digit(s), so the value must be below "
                        + exclusiveLimit + ". COBOL would keep the low-order digits, and that "
                        + "truncation has to be requested deliberately through "
                        + "FixedWidthCodec.movePic9");
            }
        }
    }
}
