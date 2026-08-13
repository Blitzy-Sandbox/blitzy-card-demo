package com.vsergeychik.carddemo.card.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.ResponseOnlyMembers;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.constraints.Size;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The inbound payload of {@code GET /api/cards/{cardNum}} - the credit-card detail screen, CSD
 * transaction {@code CCDL}, backed by {@code app/cbl/COCRDSLC.cbl} (887 lines).
 *
 * <p>This type is a 1:1 projection of the <em>input</em> half of one BMS mapset. Its two authoritative
 * sources are {@code app/cpy-bms/COCRDSL.CPY} (200 lines), the symbolic map, and
 * {@code app/bms/COCRDSL.bms} (157 lines), the mapset that generated it. Neither is ever written: the
 * legacy tree is the only oracle a parity migration has, so everything under {@code app/cpy-bms/},
 * {@code app/bms/}, {@code app/cpy/} and {@code app/cbl/} is read-only here (practice
 * <strong>B3</strong>).
 *
 * <h2>Fifteen fields, and why exactly fifteen</h2>
 *
 * <p>{@code app/bms/COCRDSL.bms} declares <strong>31</strong> {@code DFHMDF} entries of which
 * <strong>15 carry a name</strong>. The other sixteen are unnamed literal entries - screen furniture
 * such as the {@code INITIAL='Tran:'} label at line 29 - which occupy screen positions but hold no
 * data, generate no symbolic-map item, and therefore get no Java field. The fifteen named entries are
 * exactly:
 *
 * <pre>
 *   #  DFHMDF    .bms line  LENGTH  POS       xxxI item     PICTURE  .CPY line
 *   -  --------  ---------  ------  --------  ------------  -------  ---------
 *   1  TRNNAME        L34        4  (1, 7)    TRNNAMEI      X(4)        L24
 *   2  TITLE01        L38       40  (1,21)    TITLE01I      X(40)       L30
 *   3  CURDATE        L47        8  (1,71)    CURDATEI      X(8)        L36
 *   4  PGMNAME        L57        8  (2, 7)    PGMNAMEI      X(8)        L42
 *   5  TITLE02        L61       40  (2,21)    TITLE02I      X(40)       L48
 *   6  CURTIME        L70        8  (2,71)    CURTIMEI      X(8)        L54
 *   7  ACCTSID        L84       11  (7,45)    ACCTSIDI      X(11)       L60
 *   8  CARDSID        L96       16  (8,45)    CARDSIDI      X(16)       L66
 *   9  CRDNAME       L107       50  (11,25)   CRDNAMEI      X(50)       L72
 *  10  CRDSTCD       L116        1  (13,25)   CRDSTCDI      X(1)        L78
 *  11  EXPMON        L126        2  (15,25)   EXPMONI       X(2)        L84
 *  12  EXPYEAR       L133        4  (15,30)   EXPYEARI      X(4)        L90
 *  13  INFOMSG       L139       40  (20,25)   INFOMSGI      X(40)       L96
 *  14  ERRMSG        L144       80  (23, 1)   ERRMSGI       X(80)      L102
 *  15  FKEYS         L148       75  (24, 1)   FKEYSI        X(75)      L108
 *   -  --------             ------                                   ---------
 *                              387  = the sum of both columns, which agree
 * </pre>
 *
 * <p>The two width columns are independent transcriptions of the same contract - one from the
 * mapset's {@code LENGTH=} operands, one from the symbolic map's {@code PICTURE} clauses - and they
 * agree field for field and in total. Every payload member below therefore traces to a name-labelled
 * {@code DFHMDF} entry and every width to a symbolic-map {@code PICTURE} clause, which is the whole of
 * gate <strong>G9</strong>.
 *
 * <h2>Input items only: the {@code xxxI} rule</h2>
 *
 * <p>A symbolic map declares two groups over the same storage. {@code 01 CCRDSLAI.} at
 * {@code COCRDSL.CPY:17} is the input group and {@code 01 CCRDSLAO REDEFINES CCRDSLAI.} at line 109
 * is the output group. Both open with {@code 02 FILLER PIC X(12)} - the {@code TIOAPFX=YES} prefix -
 * and then repeat a per-field pattern whose stride is identical, because a {@code REDEFINES} is
 * obliged to describe the same bytes:
 *
 * <pre>
 *   input  (CCRDSLAI)                       bytes   output (CCRDSLAO)              bytes
 *   -------------------------------------   -----   ----------------------------   -----
 *   02 xxxL COMP PIC S9(4)                      2   02 FILLER PICTURE X(3)             3
 *   02 xxxF PICTURE X                           1
 *   02 FILLER REDEFINES xxxF                    0   (an overlay adds no storage)
 *      03 xxxA PICTURE X
 *   02 FILLER PICTURE X(4)                      4   02 xxxC PICTURE X                  1
 *                                                   02 xxxP PICTURE X                  1
 *                                                   02 xxxH PICTURE X                  1
 *                                                   02 xxxV PICTURE X                  1
 *   02 xxxI PIC X(n)                            n   02 xxxO PIC X(n)                   n
 *   -------------------------------------   -----   ----------------------------   -----
 *                                             7+n                                    7+n
 * </pre>
 *
 * <p>The correspondence is exact and worth reading off: the output group's leading 3-byte
 * {@code FILLER} overlays the input's {@code xxxL} (2) plus {@code xxxF} (1), and its
 * {@code xxxC}/{@code xxxP}/{@code xxxH}/{@code xxxV} quad overlays the input's {@code FILLER X(4)}.
 * That quad is the extended-attribute set the mapset declares, in byte order, at
 * {@code COCRDSL.bms:26-27} - {@code DSATTS=(COLOR,HILIGHT,PS,VALIDN)} and
 * {@code MAPATTS=(COLOR,HILIGHT,PS,VALIDN)} - so C is COLOR, P is PS, H is HILIGHT and V is VALIDN.
 * The letters are not in the order the operands are written; they are in the order BMS lays the bytes
 * down.
 *
 * <p><strong>This class carries the fifteen {@code xxxI} items and nothing else.</strong> The fifteen
 * {@code xxxO} items, and the colour and highlight items that sit beside them, belong to
 * {@code CardSelectResponse}. Getting that split wrong is the one mistake that would empty the
 * presentation contract of meaning, because a request carrying output items no longer describes what
 * the terminal sent.
 *
 * <h2>The 504-byte group image</h2>
 *
 * <p>The whole input group is exactly {@value #GROUP_LENGTH} bytes:
 * {@value #TIOAPFX_LENGTH} for the prefix, plus {@value #FIELD_COUNT} fields each carrying
 * {@value #FIELD_OVERHEAD} bytes of length, flag and extended-attribute storage, plus the
 * {@value #PAYLOAD_LENGTH} bytes of field data - that is
 * 12 + 15 &times; 7 + 387 = 504. {@link #toGroupImage(FixedWidthCodec)} renders it and
 * {@link #fromGroupImage(byte[], FixedWidthCodec)} reads it back. Neither number is asserted only in
 * this prose: {@link #PAYLOAD_LENGTH} and {@link #GROUP_LENGTH} are computed from the fifteen width
 * constants rather than written down, and the static initialiser below walks the {@link ScreenField}
 * strides and refuses to load the class unless the walk lands exactly on {@value #GROUP_LENGTH}. A
 * transcription slip therefore fails on first use rather than silently shifting every byte after it.
 *
 * <h2>{@code xxxL}, {@code xxxF} and {@code xxxA} are metadata, not payload</h2>
 *
 * <p>Three of the five items in each input group are not data, and they are deliberately kept off the
 * JSON wire - see {@link ScreenFieldMetadata} and {@link #metadata(ScreenField)}, both
 * {@link JsonIgnore}d. They are not decoration: {@code COCRDSLC} writes both of them into the
 * <em>input</em> group in paragraph {@code 1300-SETUP-SCREEN-ATTRS}, so they must exist somewhere.
 *
 * <ul>
 *   <li>{@code xxxL COMP PIC S9(4)} is the length CICS reports for a received field, which is how the
 *       program answers "was this field entered?", and is also the cursor-positioning channel:
 *       {@code app/cbl/COCRDSLC.cbl:518}, {@code :521} and {@code :523} each
 *       {@code MOVE -1 TO ACCTSIDL OF CCRDSLAI} or {@code CARDSIDL OF CCRDSLAI} to park the cursor on
 *       the field the operator has to fix.</li>
 *   <li>{@code xxxA}, the {@code REDEFINES} view of the flag byte {@code xxxF}, is the attribute the
 *       program assigns: {@code app/cbl/COCRDSLC.cbl:507-508} moves {@code DFHBMPRF} onto
 *       {@code ACCTSIDA} and {@code CARDSIDA} when the screen was reached from the card list, and
 *       {@code :510-511} moves {@code DFHBMFSE} onto them otherwise.</li>
 * </ul>
 *
 * <p>Promoting either onto the payload would add members that trace to no {@code DFHMDF} field and
 * break gate <strong>G9</strong>; dropping them would lose behaviour the COBOL performs. Holding them
 * as non-serialised metadata is the only reading that keeps both true.
 *
 * <h2>Conversation state travels in the payload</h2>
 *
 * <p>{@code COCRDSLC} copies {@code CVCRD01Y} at line 194 and {@code COCOM01Y} at line 198, and both
 * are conversational state. CICS is pseudo-conversational, so this class carries them as ordinary
 * payload members - {@link #getCardScreenState()} and {@link #getNavigationContext()} - and holds no
 * servlet session, no session-scoped attribute, no cache, no thread-bound storage and no static
 * mutable state. Nothing here can outlive the request that created it, which is gate
 * <strong>G37</strong>. The property is structural rather than a matter of trust, so it is
 * mechanically checkable: a search of this file for any such construct finds none.
 *
 * <p>{@link NavigationContext#pgmContext()} is the flag that matters most on an inbound request. Its
 * {@code 88}-levels separate {@code CDEMO-PGM-ENTER} ({@value NavigationContext#PGM_CONTEXT_ENTER},
 * first entry, paint the screen) from {@code CDEMO-PGM-REENTER}
 * ({@value NavigationContext#PGM_CONTEXT_REENTER}, re-entry, validate what was typed), and re-entry is
 * the conjunct that lets a field be highlighted at all:
 * {@code app/cbl/COCRDSLC.cbl:543-544} and {@code :549-550} apply {@code '*'} and {@code DFHRED} only
 * under {@code IF FLG-ACCTFILTER-BLANK AND CDEMO-PGM-REENTER}. {@link #isEnter()} and
 * {@link #isReenter()} make the distinction explicit on this type, which is gate
 * <strong>G38</strong>.
 *
 * <h2>A pair shared by two programs, asymmetrically</h2>
 *
 * <p>This Request/Response pair serves two programs, and the sharing is lopsided in a way that is easy
 * to mistake for a bug. Practice <strong>B4</strong> requires the conflict to be recorded rather than
 * quietly repaired, so it is recorded here exactly as the source reads:
 *
 * <ul>
 *   <li>{@code app/cbl/COCRDSLC.cbl:215} reads {@code COPY COCRDSL.} - <strong>live</strong>.
 *       {@code COCRDSLC} owns this map and populates every one of its fields.</li>
 *   <li>{@code app/cbl/COCRDLIC.cbl:274} reads {@code *COPY COCRDSL.} - <strong>commented
 *       out</strong> - while {@code COPY COCRDLI.} on line 276 is live. {@code COCRDLIC} therefore
 *       has no {@code COCRDSL} data area at all and never populates one. It still reaches this screen:
 *       the card-select map is the card list's navigation target, and {@code CardListController}
 *       consequently depends on this pair without ever filling it in.</li>
 * </ul>
 *
 * <p>The consequence for this type is that it must serve {@code COCRDSLC}'s full field population and
 * {@code CardListController}'s navigation-target reference without either needing the other's
 * behaviour. So there is no {@code COCRDLIC}-specific member here, and the commented-out {@code COPY}
 * stays commented out - it is a verified source fact, not an oversight to repair.
 *
 * <h2>Two further facts, verified against the source rather than restated</h2>
 *
 * <ol>
 *   <li><strong>The mapset's attribute operands are not where the migration plan places them.</strong>
 *       The plan's summary has every mapset declaring
 *       {@code DFHMSD CTRL=(ALARM,FREEKB) EXTATT=YES}. Read directly,
 *       {@code app/bms/COCRDSL.bms:20-24} declares
 *       {@code COCRDSL DFHMSD LANG=COBOL, MODE=INOUT, STORAGE=AUTO, TIOAPFX=YES, TYPE=&&SYSPARM} -
 *       <strong>no {@code CTRL=} and no {@code EXTATT=}</strong> - and it is
 *       {@code app/bms/COCRDSL.bms:25-28},
 *       {@code CCRDSLA DFHMDI CTRL=(FREEKB), DSATTS=(COLOR,HILIGHT,PS,VALIDN),
 *       MAPATTS=(COLOR,HILIGHT,PS,VALIDN), SIZE=(24,80)}, that carries them. The {@code CTRL} operand
 *       is {@code (FREEKB)} alone, without {@code ALARM}. {@code SIZE=(24,80)} is confirmed, and the
 *       mapset ends with {@code DFHMSD TYPE=FINAL} on line 153.</li>
 *   <li><strong>Two fields that exist on sibling card screens do not exist here.</strong> There is no
 *       {@code EXPDAY} - only {@code COCRDUP} declares one - and no {@code PAGENO} - only
 *       {@code COCRDLI} declares one. Neither is added for symmetry, because practice
 *       <strong>B5</strong> forbids supplying what the source does not have.</li>
 * </ol>
 *
 * <p>For the same reason the three card maps are not factored into a common header. They disagree, and
 * the disagreement <em>is</em> the contract: {@code INFOMSG} and {@code ERRMSG} are
 * {@value #INFOMSG_LENGTH} and {@value #ERRMSG_LENGTH} here but 45 and 78 on {@code COCRDLI}, and
 * {@code FKEYS} is {@value #FKEYS_LENGTH} here, 21 on {@code COCRDUP} and absent from
 * {@code COCRDLI}. A shared base class would collapse those numbers into one and quietly break gate
 * <strong>G9</strong> on two screens.
 *
 * <h2>Widths are applied deliberately, never by assignment</h2>
 *
 * <p>Every field is {@code PIC X(n)}, so every field is a {@link String}. A COBOL {@code PIC X}
 * receiver is filled from its leftmost position, padded on the right with spaces when the sending
 * value is short and truncated on the right when it is long. A plain Java assignment does neither, and
 * the resulting parity defect is invisible at the call site - so the setters here store what they are
 * given <em>unaltered</em>, and the {@code MOVE} itself is performed only where it is asked for, by
 * {@link FixedWidthCodec#movePicX(String, int)}, through {@link #image(ScreenField, FixedWidthCodec)},
 * {@link #normalize(FixedWidthCodec)} and {@link #toGroupImage(FixedWidthCodec)} (practice
 * <strong>B11</strong>). Reads are never trimmed; a {@code PIC X} field's trailing spaces are part of
 * its value and the parity differ compares them.
 *
 * <p>Validation is {@link Size} and nothing else, with every {@code max} taken from a
 * {@code PICTURE} width. No {@code NotBlank}, no {@code Pattern}, no digit or range check:
 * {@code COCRDSLC} performs its own edits in its own paragraphs - {@code app/cbl/COCRDSLC.cbl:615-626}
 * tests {@code IF ACCTSIDI OF CCRDSLAI = '*' OR = SPACES} before moving the field into
 * {@code CC-ACCT-ID}, and does the same for {@code CARDSIDI} - and pre-empting those edits would
 * change which message the operator sees and in what order. That is a parity violation, not a
 * hardening.
 *
 * <p>Nothing is masked either. {@link #getCardsid()} carries a full sixteen-digit card number and
 * {@link #getAcctsid()} an account identifier, in the clear, exactly as the symbolic map does. Adding
 * redaction or a sanitising {@link #toString()} would be as much of a behaviour change as removing a
 * check (practice <strong>B6</strong>), so neither is done - and no new exposure is introduced either.
 *
 * <h2>Construction and thread safety</h2>
 *
 * <p>The class is deliberately mutable, because a BMS map area is: {@code COCRDSLC} assigns into it
 * part-way through processing. An instance is consequently <strong>not</strong> thread safe and must
 * stay confined to the request that owns it, exactly as a CICS map area is confined to its task. It
 * needs no Spring context and no framework to build - {@link #CardSelectRequest()} plus the setters,
 * or {@link #CardSelectRequest(CardSelectRequest)}, are enough for a controller test, a
 * {@code MockMvc} test and the {@code COCRDSLC} parity case alike (practice <strong>B10</strong>).
 *
 * @see CardScreenState the {@code CVCRD01Y} work area this request carries
 * @see NavigationContext the {@code COCOM01Y} communication area this request carries
 * @see FixedWidthCodec the sole home of the {@code PIC X} move rule
 *
 * <h2>Members this request tolerates without declaring</h2>
 *
 * <p>The {@code @JsonIgnoreProperties} below names the members the paired response carries that this
 * request does not declare. They are tolerated so a client can send the body it was just handed straight
 * back: rule R6 and gate G37 put the whole conversation in the payload, which makes the next request the
 * previous response. {@code ignoreUnknown} stays at its default of {@code false}, so every <em>other</em>
 * unrecognised name is still refused with the offending field named in the error envelope. Each tolerated
 * member is recomputed by the server on every path, so the value that arrives here is discarded and
 * cannot steer a branch. The names live in {@link com.vsergeychik.carddemo.common.ResponseOnlyMembers},
 * which explains each one.
 */
@JsonIgnoreProperties({
        ResponseOnlyMembers.NEXT_PROGRAM,
        ResponseOnlyMembers.NEXT_MAPSET,
        ResponseOnlyMembers.NEXT_MAP,
        ResponseOnlyMembers.SCREEN_METADATA})
public final class CardSelectRequest {

    // =================================================================================================
    // Field widths, transcribed one by one from the xxxI PICTURE clauses of app/cpy-bms/COCRDSL.CPY and
    // cross-checked against the LENGTH= operands of app/bms/COCRDSL.bms. Every width in this file comes
    // from exactly one of these constants; no width is written twice and none is written inline
    // (practice B8).
    // =================================================================================================

    /** Width of {@code TRNNAMEI PIC X(4)}, {@code COCRDSL.CPY:24}; {@code TRNNAME LENGTH=4}. */
    public static final int TRNNAME_LENGTH = 4;

    /** Width of {@code TITLE01I PIC X(40)}, {@code COCRDSL.CPY:30}; {@code TITLE01 LENGTH=40}. */
    public static final int TITLE01_LENGTH = 40;

    /** Width of {@code CURDATEI PIC X(8)}, {@code COCRDSL.CPY:36}; {@code CURDATE LENGTH=8}. */
    public static final int CURDATE_LENGTH = 8;

    /** Width of {@code PGMNAMEI PIC X(8)}, {@code COCRDSL.CPY:42}; {@code PGMNAME LENGTH=8}. */
    public static final int PGMNAME_LENGTH = 8;

    /** Width of {@code TITLE02I PIC X(40)}, {@code COCRDSL.CPY:48}; {@code TITLE02 LENGTH=40}. */
    public static final int TITLE02_LENGTH = 40;

    /** Width of {@code CURTIMEI PIC X(8)}, {@code COCRDSL.CPY:54}; {@code CURTIME LENGTH=8}. */
    public static final int CURTIME_LENGTH = 8;

    /** Width of {@code ACCTSIDI PIC X(11)}, {@code COCRDSL.CPY:60}; {@code ACCTSID LENGTH=11}. */
    public static final int ACCTSID_LENGTH = 11;

    /** Width of {@code CARDSIDI PIC X(16)}, {@code COCRDSL.CPY:66}; {@code CARDSID LENGTH=16}. */
    public static final int CARDSID_LENGTH = 16;

    /** Width of {@code CRDNAMEI PIC X(50)}, {@code COCRDSL.CPY:72}; {@code CRDNAME LENGTH=50}. */
    public static final int CRDNAME_LENGTH = 50;

    /** Width of {@code CRDSTCDI PIC X(1)}, {@code COCRDSL.CPY:78}; {@code CRDSTCD LENGTH=1}. */
    public static final int CRDSTCD_LENGTH = 1;

    /** Width of {@code EXPMONI PIC X(2)}, {@code COCRDSL.CPY:84}; {@code EXPMON LENGTH=2}. */
    public static final int EXPMON_LENGTH = 2;

    /** Width of {@code EXPYEARI PIC X(4)}, {@code COCRDSL.CPY:90}; {@code EXPYEAR LENGTH=4}. */
    public static final int EXPYEAR_LENGTH = 4;

    /**
     * Width of {@code INFOMSGI PIC X(40)}, {@code COCRDSL.CPY:96}; {@code INFOMSG LENGTH=40}.
     *
     * <p>Forty here and forty-five on {@code COCRDLI}. The two are not reconciled.
     */
    public static final int INFOMSG_LENGTH = 40;

    /**
     * Width of {@code ERRMSGI PIC X(80)}, {@code COCRDSL.CPY:102}; {@code ERRMSG LENGTH=80}.
     *
     * <p>Eighty here and seventy-eight on {@code COCRDLI}. The two are not reconciled.
     */
    public static final int ERRMSG_LENGTH = 80;

    /**
     * Width of {@code FKEYSI PIC X(75)}, {@code COCRDSL.CPY:108}; {@code FKEYS LENGTH=75}.
     *
     * <p>Seventy-five here, twenty-one on {@code COCRDUP}, and absent from {@code COCRDLI}. The three
     * are not reconciled.
     */
    public static final int FKEYS_LENGTH = 75;

    // =================================================================================================
    // The shape of the group as a whole. Every one of these is computed from the constants above rather
    // than written down, so the arithmetic in the class documentation cannot drift away from the code.
    // =================================================================================================

    /**
     * Bytes in the {@code TIOAPFX=YES} prefix - {@code 02 FILLER PIC X(12)} at
     * {@code COCRDSL.CPY:18}, repeated at line 110 as the first item of the output group.
     *
     * <p>The prefix exists because {@code app/bms/COCRDSL.bms:23} declares {@code TIOAPFX=YES}, which
     * reserves room at the front of the map area for the terminal input/output area header. It carries
     * no application data, so it is never exposed as a field; it is only ever written and skipped.
     */
    public static final int TIOAPFX_LENGTH = 12;

    /** Bytes in one {@code xxxL COMP PIC S9(4)} item: a binary halfword, two bytes, big-endian. */
    public static final int LENGTH_ITEM_LENGTH = 2;

    /**
     * Bytes in one {@code xxxF PICTURE X} flag item.
     *
     * <p>{@code 02 FILLER REDEFINES xxxF} / {@code 03 xxxA PICTURE X} is an overlay of this single
     * byte and so adds nothing to the total - which is exactly why {@link ScreenFieldMetadata} holds
     * one attribute value rather than two.
     */
    public static final int FLAG_ITEM_LENGTH = 1;

    /**
     * Bytes in one {@code 02 FILLER PICTURE X(4)} extended-attribute item.
     *
     * <p>Unnamed and unused on input. The output group names the same four bytes {@code xxxC},
     * {@code xxxP}, {@code xxxH} and {@code xxxV} - the
     * {@code DSATTS}/{@code MAPATTS=(COLOR,HILIGHT,PS,VALIDN)} set of
     * {@code app/bms/COCRDSL.bms:26-27} - and they are {@code CardSelectResponse}'s concern, not this
     * type's.
     */
    public static final int EXTENDED_ATTRIBUTE_ITEM_LENGTH = 4;

    /**
     * Bytes each field costs before its data begins:
     * {@value #LENGTH_ITEM_LENGTH} + {@value #FLAG_ITEM_LENGTH} +
     * {@value #EXTENDED_ATTRIBUTE_ITEM_LENGTH} = 7, so one field occupies {@code 7 + n} bytes.
     */
    public static final int FIELD_OVERHEAD =
            LENGTH_ITEM_LENGTH + FLAG_ITEM_LENGTH + EXTENDED_ATTRIBUTE_ITEM_LENGTH;

    /** Name-labelled {@code DFHMDF} entries in {@code app/bms/COCRDSL.bms}, of 31 entries in all. */
    public static final int FIELD_COUNT = 15;

    /**
     * Bytes of field data in the group: 4 + 40 + 8 + 8 + 40 + 8 + 11 + 16 + 50 + 1 + 2 + 4 + 40 + 80 +
     * 75 = <strong>387</strong>.
     *
     * <p>Written as the sum of the fifteen width constants, not as the literal 387, so a corrected
     * width propagates here instead of leaving two numbers to disagree. The static initialiser below
     * still checks the total, because a width that is wrong in the same direction as this sum would
     * otherwise go unnoticed.
     */
    public static final int PAYLOAD_LENGTH = TRNNAME_LENGTH + TITLE01_LENGTH + CURDATE_LENGTH
            + PGMNAME_LENGTH + TITLE02_LENGTH + CURTIME_LENGTH + ACCTSID_LENGTH + CARDSID_LENGTH
            + CRDNAME_LENGTH + CRDSTCD_LENGTH + EXPMON_LENGTH + EXPYEAR_LENGTH + INFOMSG_LENGTH
            + ERRMSG_LENGTH + FKEYS_LENGTH;

    /**
     * Bytes in the whole {@code CCRDSLAI} group:
     * {@value #TIOAPFX_LENGTH} + {@value #FIELD_COUNT} &times; {@value #FIELD_OVERHEAD} +
     * {@value #PAYLOAD_LENGTH} = <strong>504</strong>.
     *
     * <p>{@code CCRDSLAO} redefines {@code CCRDSLAI}, so the output group is the same 504 bytes. That
     * is the storage {@link #toGroupImage(FixedWidthCodec)} produces and
     * {@link #fromGroupImage(byte[], FixedWidthCodec)} consumes.
     */
    public static final int GROUP_LENGTH =
            TIOAPFX_LENGTH + FIELD_COUNT * FIELD_OVERHEAD + PAYLOAD_LENGTH;

    /**
     * The single space character, the pad and fill character of every {@code PIC X} item here.
     *
     * <p>Held as a {@code char} rather than a byte because its encoding depends on the code page -
     * {@code 0x20} in US-ASCII and {@code 0x40} in IBM037 - and the encoding is
     * {@link FixedWidthCodec}'s business, resolved from {@link FixedWidthCodec#charset()} at the point
     * of use. Nothing in this file assumes a platform default charset.
     */
    private static final char SPACE = ' ';

    /**
     * The {@code LOW-VALUES} byte: binary zero.
     *
     * <p>{@code LOW-VALUES} is the lowest character of the collating sequence, and that is
     * {@code X'00'} in both ASCII and EBCDIC, so this one byte is correct on either code page without
     * consulting the charset. It is the unset value of a flag byte and of an extended-attribute byte in
     * a freshly initialised map area, which is the state {@code MOVE LOW-VALUES TO CCRDSLAO} produces
     * at {@code app/cbl/COCRDSLC.cbl:428} and the state CICS leaves the input group's unused
     * attribute bytes in.
     */
    private static final byte LOW_VALUE_BYTE = 0x00;

    static {
        // Fail before the first byte comparison rather than after it. Every number stated in the class
        // documentation is checked here against the constants, and then the field strides are walked
        // from the end of the prefix to confirm the last field ends exactly on GROUP_LENGTH.
        //
        // The three comparisons below and the four inside verifyFieldStrides() are checked at different
        // times, and the difference is worth knowing rather than glossing:
        //
        //   * FIELD_OVERHEAD, PAYLOAD_LENGTH and GROUP_LENGTH are compile-time constant expressions, so
        //     each comparison is decided by javac. While the arithmetic holds, the condition is
        //     constantly false and the compiler elides the whole statement - these three cost nothing at
        //     run time and appear in no bytecode. Change a width so the sum no longer reaches 387 and
        //     the condition becomes constantly true, javac emits the throw, and the class fails to
        //     initialise on first use. They are compile-time assertions that turn into runtime failures
        //     exactly when they need to.
        //   * verifyFieldStrides() compares values read from ScreenField at run time, which are not
        //     constants, so that check is live in every build. Its four guards are the ones that
        //     validate the transcribed data offsets, and they cannot be folded away.
        if (FIELD_OVERHEAD != 7) {
            throw new IllegalStateException("Each COCRDSL.CPY input field carries 2 + 1 + 4 = 7 bytes "
                    + "of length, flag and extended-attribute storage before its data, but "
                    + "FIELD_OVERHEAD computes to " + FIELD_OVERHEAD);
        }
        if (PAYLOAD_LENGTH != 387) {
            throw new IllegalStateException("The fifteen xxxI PICTURE widths of app/cpy-bms/"
                    + "COCRDSL.CPY sum to 387, which is also the sum of the fifteen LENGTH= operands "
                    + "of app/bms/COCRDSL.bms, but the width constants sum to " + PAYLOAD_LENGTH);
        }
        if (GROUP_LENGTH != 504) {
            throw new IllegalStateException("The CCRDSLAI group is 12 + 15 * 7 + 387 = 504 bytes, but "
                    + "the constants compute " + GROUP_LENGTH);
        }
        verifyFieldStrides();
    }

    /**
     * Walks the fifteen {@link ScreenField} constants in declaration order and confirms that each one
     * begins where its predecessor ended, that the first begins immediately after the
     * {@value #TIOAPFX_LENGTH}-byte prefix, and that the last ends exactly on {@value #GROUP_LENGTH}.
     *
     * <p>This is the check that makes the transcribed data offsets safe. Each offset is written out
     * explicitly on its enum constant so a reviewer can compare it with the copybook without doing
     * arithmetic, and this walk is what stops an explicit number from being explicitly wrong.
     *
     * @throws IllegalStateException if any field's storage does not abut its neighbours, if the count
     *                               of constants is not {@value #FIELD_COUNT}, or if the widths do not
     *                               sum to {@value #PAYLOAD_LENGTH}
     */
    private static void verifyFieldStrides() {
        ScreenField[] fields = ScreenField.values();
        if (fields.length != FIELD_COUNT) {
            throw new IllegalStateException("app/bms/COCRDSL.bms carries " + FIELD_COUNT
                    + " name-labelled DFHMDF entries, so ScreenField must declare " + FIELD_COUNT
                    + " constants, but it declares " + fields.length);
        }
        int cursor = TIOAPFX_LENGTH;
        int widths = 0;
        for (ScreenField field : fields) {
            if (field.lengthItemOffset() != cursor) {
                throw new IllegalStateException("Field " + field.label() + " declares its data at "
                        + "offset " + field.dataOffset() + ", which places its xxxL item at "
                        + field.lengthItemOffset() + "; the preceding storage ends at " + cursor
                        + ", so the CCRDSLAI group would have a gap or an overlap there");
            }
            cursor = field.endOffsetExclusive();
            widths += field.length();
        }
        if (widths != PAYLOAD_LENGTH) {
            throw new IllegalStateException("The fifteen ScreenField widths sum to " + widths
                    + " but the width constants sum to " + PAYLOAD_LENGTH);
        }
        if (cursor != GROUP_LENGTH) {
            throw new IllegalStateException("Walking the fifteen field strides from the end of the "
                    + TIOAPFX_LENGTH + "-byte TIOAPFX prefix ends at " + cursor
                    + ", not at the declared group length of " + GROUP_LENGTH);
        }
    }

    // =================================================================================================
    // The fifteen payload members, declared in the order app/cpy-bms/COCRDSL.CPY lays their storage
    // down. Every one is PIC X(n) and therefore a String. Not one is numeric, so this file declares no
    // scaled decimal, no binary approximation of one, and no rounding mode at all (gates G22 and G24) -
    // there is nothing on this screen for a rounding decision to be made about.
    //
    // @Size is the only constraint, and every max comes from a PICTURE width. No @NotBlank and no
    // @Pattern: COCRDSLC edits its own fields in its own paragraphs, and pre-empting an edit here would
    // change which message the operator sees and in what order.
    //
    // @JsonProperty pins each wire name to the DFHMDF label in lower case, so a JSON naming strategy
    // configured later cannot rename a field out from under the presentation contract.
    //
    // Every @Size declares PUBLIC_LENGTH_MESSAGE. The copybook item, its PICTURE clause and the line
    // it was read from stay in the Javadoc on each member, which is where a maintainer looks for them;
    // they are not constraint-violation text, because that text is written for a caller.
    // =================================================================================================

    /**
     * The message every width constraint below declares, and the only text a rejected field publishes.
     *
     * <p>{@code {max}} is the constraint's own declared bound, interpolated by the validator, so the
     * sentence states the width without restating the number - and the width of a field a caller sends
     * is already part of the published contract, so naming it discloses nothing.
     *
     * <p><strong>What it deliberately does not say.</strong> Each of these messages used to read
     * {@code "CARDSID is CARDSIDI PIC X(16) at app/cpy-bms/COCRDSL.CPY:66 and holds at most 16
     * characters"} - the symbolic-map item, its {@code PICTURE} clause, the copybook path and the line
     * number. That is provenance written for the engineer maintaining the field, and its place is the
     * Javadoc on the member, where it remains in full. Handed to an unauthenticated caller one
     * rejected field at a time it becomes an inventory of this module's copybooks and their line
     * numbers - a description of the estate behind the API rather than a correction the caller can act
     * on.
     *
     * <p>{@code config/WebConfig}'s error advice does not forward a validator message at all - it maps
     * the constraint's code and bound onto its own fixed sentence, so nothing declared here can reach a
     * caller by accident. This constant matches that sentence exactly, so the two agree if a future
     * consumer of Bean Validation does surface a message directly.
     */
    public static final String PUBLIC_LENGTH_MESSAGE = "must be at most {max} characters";

    /** {@code TRNNAME}: {@code TRNNAMEI PIC X(4)}, {@code app/cpy-bms/COCRDSL.CPY:24}. */
    @JsonProperty("trnname")
    @Size(max = TRNNAME_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String trnname;

    /** {@code TITLE01}: {@code TITLE01I PIC X(40)}, {@code app/cpy-bms/COCRDSL.CPY:30}. */
    @JsonProperty("title01")
    @Size(max = TITLE01_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String title01;

    /** {@code CURDATE}: {@code CURDATEI PIC X(8)}, {@code app/cpy-bms/COCRDSL.CPY:36}. */
    @JsonProperty("curdate")
    @Size(max = CURDATE_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String curdate;

    /** {@code PGMNAME}: {@code PGMNAMEI PIC X(8)}, {@code app/cpy-bms/COCRDSL.CPY:42}. */
    @JsonProperty("pgmname")
    @Size(max = PGMNAME_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String pgmname;

    /** {@code TITLE02}: {@code TITLE02I PIC X(40)}, {@code app/cpy-bms/COCRDSL.CPY:48}. */
    @JsonProperty("title02")
    @Size(max = TITLE02_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String title02;

    /** {@code CURTIME}: {@code CURTIMEI PIC X(8)}, {@code app/cpy-bms/COCRDSL.CPY:54}. */
    @JsonProperty("curtime")
    @Size(max = CURTIME_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String curtime;

    /**
     * {@code ACCTSID}: {@code ACCTSIDI PIC X(11)}, {@code app/cpy-bms/COCRDSL.CPY:60}.
     *
     * <p>One of the two fields the operator types. {@code app/cbl/COCRDSLC.cbl:615-619} reads it,
     * treating {@code '*'} and {@code SPACES} alike as "no criterion", then moves it to
     * {@code CC-ACCT-ID}.
     */
    @JsonProperty("acctsid")
    @Size(max = ACCTSID_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String acctsid;

    /**
     * {@code CARDSID}: {@code CARDSIDI PIC X(16)}, {@code app/cpy-bms/COCRDSL.CPY:66}.
     *
     * <p>The other typed field. {@code app/cbl/COCRDSLC.cbl:622-626} reads it the same way and moves it
     * to {@code CC-CARD-NUM}. Sixteen digits of card number, carried in the clear exactly as the
     * symbolic map declares them - unmasked, unredacted and unabbreviated, because the COBOL is
     * (practice <strong>B6</strong>).
     */
    @JsonProperty("cardsid")
    @Size(max = CARDSID_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String cardsid;

    /** {@code CRDNAME}: {@code CRDNAMEI PIC X(50)}, {@code app/cpy-bms/COCRDSL.CPY:72}. */
    @JsonProperty("crdname")
    @Size(max = CRDNAME_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String crdname;

    /** {@code CRDSTCD}: {@code CRDSTCDI PIC X(1)}, {@code app/cpy-bms/COCRDSL.CPY:78}. */
    @JsonProperty("crdstcd")
    @Size(max = CRDSTCD_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String crdstcd;

    /** {@code EXPMON}: {@code EXPMONI PIC X(2)}, {@code app/cpy-bms/COCRDSL.CPY:84}. */
    @JsonProperty("expmon")
    @Size(max = EXPMON_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String expmon;

    /** {@code EXPYEAR}: {@code EXPYEARI PIC X(4)}, {@code app/cpy-bms/COCRDSL.CPY:90}. */
    @JsonProperty("expyear")
    @Size(max = EXPYEAR_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String expyear;

    /** {@code INFOMSG}: {@code INFOMSGI PIC X(40)}, {@code app/cpy-bms/COCRDSL.CPY:96}. */
    @JsonProperty("infomsg")
    @Size(max = INFOMSG_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String infomsg;

    /** {@code ERRMSG}: {@code ERRMSGI PIC X(80)}, {@code app/cpy-bms/COCRDSL.CPY:102}. */
    @JsonProperty("errmsg")
    @Size(max = ERRMSG_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String errmsg;

    /** {@code FKEYS}: {@code FKEYSI PIC X(75)}, {@code app/cpy-bms/COCRDSL.CPY:108}. */
    @JsonProperty("fkeys")
    @Size(max = FKEYS_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String fkeys;

    // =================================================================================================
    // The two conversation-state carriers. CICS is pseudo-conversational, so the state that a session
    // would otherwise hold rides in the payload instead (rule R6, gate G37).
    // =================================================================================================

    /**
     * The {@code CVCRD01Y} work area, copied by {@code app/cbl/COCRDSLC.cbl:194}.
     *
     * <p>{@code COCRDSLC} begins with {@code INITIALIZE CC-WORK-AREA} at line 254, so a request that
     * carries nothing carries a freshly initialised area rather than a null.
     */
    @JsonProperty("cardScreenState")
    private CardScreenState cardScreenState;

    /**
     * The {@code CARDDEMO-COMMAREA} of {@code COCOM01Y}, copied by {@code app/cbl/COCRDSLC.cbl:198} -
     * {@value NavigationContext#COMMAREA_LENGTH} bytes, carrying among much else
     * {@code CDEMO-PGM-CONTEXT}, on which {@link #isEnter()} and {@link #isReenter()} turn.
     *
     * <p><strong>{@code null} is meaningful here, and it is not an empty area.</strong>
     * {@code app/cbl/COCRDSLC.cbl:268-272} tests
     * {@code IF EIBCALEN IS EQUAL TO 0 OR (CDEMO-FROM-PROGRAM = LIT-MENUPGM AND NOT
     * CDEMO-PGM-REENTER)} and answers it by {@code INITIALIZE}-ing {@code CARDDEMO-COMMAREA} and
     * {@code WS-THIS-PROGCOMMAREA}; the {@code ELSE} at {@code :273-278} instead moves both areas out
     * of {@code DFHCOMMAREA}. The first disjunct is the cold start - no communication area at all -
     * and it is the one an initialised {@link NavigationContext} cannot express, because an
     * initialised area reports {@code EIBCALEN} as {@value NavigationContext#COMMAREA_LENGTH} and so
     * takes the {@code ELSE}. The second disjunct is a decision about a <em>present</em> area and
     * belongs to {@code CardSelectController}. This member therefore stays nullable, carries no
     * presence constraint, and {@link #hasNavigationContext()} is the discriminator.
     */
    @JsonProperty("navigationContext")
    private NavigationContext navigationContext;

    /**
     * {@code WS-THIS-PROGCOMMAREA} - the twelve bytes this program appends to
     * {@code CARDDEMO-COMMAREA} when it returns, and restores from what the caller passed.
     *
     * <p>Never {@code null}: {@link ThisProgCommarea#initialized()} is the state
     * {@code app/cbl/COCRDSLC.cbl:272} leaves it in, and a payload that omits it is a payload whose
     * trailer is spaces - which is a value, not an absence. Its presence is <strong>not</strong> what
     * distinguishes a cold start; {@link #hasNavigationContext()} is, because {@code :268} tests
     * {@code EIBCALEN} against zero and the trailer has no separate presence of its own.
     *
     * @see ThisProgCommarea for why the twelve bytes have to travel in both directions
     */
    @JsonProperty("thisProgCommarea")
    private ThisProgCommarea thisProgCommarea = ThisProgCommarea.initialized();

    /**
     * The {@code xxxL} and {@code xxxA} items of all fifteen fields, one holder per field.
     *
     * <p>An {@link EnumMap} keyed by {@link ScreenField}: iteration follows the enum's declaration
     * order, which is the order the copybook lays the storage down, so
     * {@link #toGroupImage(FixedWidthCodec)} walks the group front to back without sorting anything.
     * The map itself is {@code final} and every key is populated at construction, so
     * {@link #metadata(ScreenField)} never returns {@code null} and never has to create anything.
     *
     * <p>Instance state, not static state: two requests never share a holder, which is what
     * {@link #CardSelectRequest(CardSelectRequest)} copies each holder for.
     */
    private final EnumMap<ScreenField, ScreenFieldMetadata> metadata =
            new EnumMap<>(ScreenField.class);

    /**
     * What {@link #toString()} substitutes for a redacted value: {@value}.
     *
     * <p>The same marker every other payload in this module uses, so a log line reads consistently
     * whichever screen produced it.
     */
    private static final String REDACTED_VALUE = "[REDACTED]";

    /**
     * The three fields {@link #toString()} redacts: the card number, the account identifier and the
     * cardholder's name.
     *
     * <p>Scoped to those three and no others. {@code CRDSTCD} is a one-character status,
     * {@code EXPMON} and {@code EXPYEAR} are an expiry that identifies nobody on its own, and the rest
     * are screen furniture, titles, messages and the function-key line. Redacting them would cost
     * diagnostic value for no gain. This set is read only by {@link #toString()}; the JSON body, the
     * group image and every accessor carry all three in the clear.
     */
    private static final Set<ScreenField> REDACTED_FIELDS =
            Collections.unmodifiableSet(EnumSet.of(ScreenField.ACCTSID, ScreenField.CARDSID,
                    ScreenField.CRDNAME));

    // =================================================================================================
    // Construction.
    // =================================================================================================

    /**
     * A freshly initialised map area: every one of the fifteen fields a run of spaces of its declared
     * width, every metadata holder unset, a new {@link CardScreenState} work area and an
     * {@link NavigationContext#empty()} communication area.
     *
     * <p>This is also the constructor a JSON binder uses, which is why the fields start as spaces
     * rather than as null: a payload that omits a field leaves it at the value COBOL would have there.
     * There is no null in a COBOL record.
     */
    public CardSelectRequest() {
        for (ScreenField field : ScreenField.values()) {
            metadata.put(field, new ScreenFieldMetadata());
        }
        initializeState();
    }

    /**
     * A deep copy of another request.
     *
     * <p>Deep in the one place it has to be: each {@link ScreenFieldMetadata} holder is copied rather
     * than shared, so the two requests cannot move each other's cursor. The fifteen field values are
     * {@link String}s and the {@link NavigationContext} is a record, both immutable and so safe to
     * share; the {@link CardScreenState} is mutable and is copied through its own copy constructor.
     *
     * @param other the request to copy
     * @throws NullPointerException if {@code other} is {@code null}
     */
    public CardSelectRequest(CardSelectRequest other) {
        Objects.requireNonNull(other, "A request is required to copy it");
        this.trnname = other.trnname;
        this.title01 = other.title01;
        this.curdate = other.curdate;
        this.pgmname = other.pgmname;
        this.title02 = other.title02;
        this.curtime = other.curtime;
        this.acctsid = other.acctsid;
        this.cardsid = other.cardsid;
        this.crdname = other.crdname;
        this.crdstcd = other.crdstcd;
        this.expmon = other.expmon;
        this.expyear = other.expyear;
        this.infomsg = other.infomsg;
        this.errmsg = other.errmsg;
        this.fkeys = other.fkeys;
        this.cardScreenState = new CardScreenState(other.cardScreenState);
        this.navigationContext = other.navigationContext;
        // A record, so sharing it is safe - and it must be carried, or a copy would return a trailer of
        // spaces where the original returned the caller's twelve bytes.
        this.thisProgCommarea = other.thisProgCommarea;
        for (ScreenField field : ScreenField.values()) {
            metadata.put(field, new ScreenFieldMetadata(other.metadata.get(field)));
        }
    }

    /**
     * A request carrying only the two fields {@code COCRDSLC} actually reads from the input group.
     *
     * <p>{@code app/cbl/COCRDSLC.cbl} takes exactly two values off this map -
     * {@code ACCTSIDI OF CCRDSLAI} at lines 615 to 619 and {@code CARDSIDI OF CCRDSLAI} at lines 622 to
     * 626 - and derives everything else. The other thirteen fields are titles, the date and time
     * header, the record it displays and the two message lines, all of which the program writes rather
     * than reads. A test or a parity case that wants to exercise the search path therefore needs only
     * these two, and this factory says so out loud instead of leaving thirteen setter calls to prove it.
     *
     * @param acctsid the {@code ACCTSID} search value; {@code null} is taken as spaces
     * @param cardsid the {@code CARDSID} search value; {@code null} is taken as spaces
     * @return a request whose remaining fields are at their initialised values, never {@code null}
     */
    public static CardSelectRequest withSearchCriteria(String acctsid, String cardsid) {
        CardSelectRequest request = new CardSelectRequest();
        request.setAcctsid(acctsid);
        request.setCardsid(cardsid);
        return request;
    }

    /**
     * Returns every field, every metadata holder and both carriers to their initialised state - the
     * {@code INITIALIZE} a CICS program performs before it starts populating a map.
     *
     * <p>Named after {@code CardScreenState.initializeWorkArea()}, which it also calls, and equivalent
     * to discarding the instance and constructing a new one. It exists because a controller under test
     * often wants to reuse one request across cases without carrying a value over from the last.
     */
    public void initializeMapArea() {
        initializeState();
        for (ScreenFieldMetadata holder : metadata.values()) {
            holder.reset();
        }
    }

    /**
     * The field-and-carrier half of initialisation, shared by {@link #CardSelectRequest()} and
     * {@link #initializeMapArea()}.
     *
     * <p>Private and non-overridable, and it touches nothing but this instance's own fields, so it is
     * safe to call from a constructor.
     */
    private void initializeState() {
        this.trnname = spaces(TRNNAME_LENGTH);
        this.title01 = spaces(TITLE01_LENGTH);
        this.curdate = spaces(CURDATE_LENGTH);
        this.pgmname = spaces(PGMNAME_LENGTH);
        this.title02 = spaces(TITLE02_LENGTH);
        this.curtime = spaces(CURTIME_LENGTH);
        this.acctsid = spaces(ACCTSID_LENGTH);
        this.cardsid = spaces(CARDSID_LENGTH);
        this.crdname = spaces(CRDNAME_LENGTH);
        this.crdstcd = spaces(CRDSTCD_LENGTH);
        this.expmon = spaces(EXPMON_LENGTH);
        this.expyear = spaces(EXPYEAR_LENGTH);
        this.infomsg = spaces(INFOMSG_LENGTH);
        this.errmsg = spaces(ERRMSG_LENGTH);
        this.fkeys = spaces(FKEYS_LENGTH);
        this.cardScreenState = new CardScreenState();
        // Absence, not an initialised area: EIBCALEN = 0 is the first disjunct of COCRDSLC.cbl:268,
        // and a request nobody has passed a communication area to has not been passed one.
        this.navigationContext = null;
        // The trailer, unlike the communication area, has no absent state of its own: :272 INITIALIZEs
        // it to spaces and :276-278 restores it from the passed area, so spaces is what a request that
        // states nothing states.
        this.thisProgCommarea = ThisProgCommarea.initialized();
    }

    /**
     * COBOL's {@code SPACES} figurative constant, repeated to a width.
     *
     * <p>An unconditional fill, and deliberately <strong>not</strong> the alphanumeric {@code MOVE}
     * rule: a {@code MOVE} pads a short sending value and truncates a long one, and that rule lives
     * solely in {@link FixedWidthCodec#movePicX(String, int)} and is never reimplemented here.
     *
     * @param length how many spaces; zero yields an empty string
     * @return a string of exactly {@code length} spaces, never {@code null}
     * @throws IllegalArgumentException if {@code length} is negative
     */
    public static String spaces(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("A COCRDSL field cannot be " + length + " characters "
                    + "wide; every one of the fifteen is declared PIC X(n) with n of at least 1");
        }
        return String.valueOf(SPACE).repeat(length);
    }

    /**
     * Substitutes spaces for an absent value, at the field's declared width.
     *
     * <p>Every setter routes {@code null} through here rather than storing it. A COBOL record has no
     * null - an unset {@code PIC X} field holds spaces - and {@code COCRDSLC} relies on exactly that
     * when it tests {@code IF ACCTSIDI OF CCRDSLAI = '*' OR = SPACES} at
     * {@code app/cbl/COCRDSLC.cbl:615-616}. A field left null would make that comparison impossible to
     * reproduce and would put a {@link NullPointerException} between the request and the first edit.
     *
     * <p>The substitution loses the distinction between "the client omitted this field" and "the client
     * sent spaces" - but so does CICS, which is why a symbolic map carries an {@code xxxL} item at all.
     * That distinction lives in {@link ScreenFieldMetadata#isLengthUnset()}, where the copybook puts it.
     *
     * @param value the value supplied, possibly {@code null}
     * @param width the field's declared width
     * @return {@code value} unchanged, or spaces of the declared width when it is {@code null}
     */
    private static String orSpaces(String value, int width) {
        return value == null ? spaces(width) : value;
    }

    // =================================================================================================
    // Accessors. Fifteen pairs, hand-written - no Lombok, so what a reviewer reads is what runs.
    //
    // Every setter stores its value UNALTERED. It does not pad and it does not truncate, because a
    // setter is not a COBOL MOVE: the MOVE rule is applied only where it is asked for, by
    // FixedWidthCodec.movePicX, through image(), normalize() and toGroupImage(). A setter that quietly
    // resized its value would hide the one decision this migration most needs to keep visible.
    //
    // Every getter returns the stored value UNTRIMMED. A PIC X field's trailing spaces are part of its
    // value and a field-by-field parity diff compares them.
    // =================================================================================================

    /**
     * {@code TRNNAMEI}, the transaction identifier the screen was reached under.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTrnname() {
        return trnname;
    }

    /**
     * Stores {@code TRNNAMEI} verbatim.
     *
     * @param trnname the value; {@code null} is taken as {@value #TRNNAME_LENGTH} spaces
     */
    public void setTrnname(String trnname) {
        this.trnname = orSpaces(trnname, TRNNAME_LENGTH);
    }

    /**
     * {@code TITLE01I}, the first title line.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTitle01() {
        return title01;
    }

    /**
     * Stores {@code TITLE01I} verbatim.
     *
     * @param title01 the value; {@code null} is taken as {@value #TITLE01_LENGTH} spaces
     */
    public void setTitle01(String title01) {
        this.title01 = orSpaces(title01, TITLE01_LENGTH);
    }

    /**
     * {@code CURDATEI}, the current date as the header carries it.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getCurdate() {
        return curdate;
    }

    /**
     * Stores {@code CURDATEI} verbatim.
     *
     * @param curdate the value; {@code null} is taken as {@value #CURDATE_LENGTH} spaces
     */
    public void setCurdate(String curdate) {
        this.curdate = orSpaces(curdate, CURDATE_LENGTH);
    }

    /**
     * {@code PGMNAMEI}, the program name the header carries.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getPgmname() {
        return pgmname;
    }

    /**
     * Stores {@code PGMNAMEI} verbatim.
     *
     * @param pgmname the value; {@code null} is taken as {@value #PGMNAME_LENGTH} spaces
     */
    public void setPgmname(String pgmname) {
        this.pgmname = orSpaces(pgmname, PGMNAME_LENGTH);
    }

    /**
     * {@code TITLE02I}, the second title line.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTitle02() {
        return title02;
    }

    /**
     * Stores {@code TITLE02I} verbatim.
     *
     * @param title02 the value; {@code null} is taken as {@value #TITLE02_LENGTH} spaces
     */
    public void setTitle02(String title02) {
        this.title02 = orSpaces(title02, TITLE02_LENGTH);
    }

    /**
     * {@code CURTIMEI}, the current time as the header carries it.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getCurtime() {
        return curtime;
    }

    /**
     * Stores {@code CURTIMEI} verbatim.
     *
     * @param curtime the value; {@code null} is taken as {@value #CURTIME_LENGTH} spaces
     */
    public void setCurtime(String curtime) {
        this.curtime = orSpaces(curtime, CURTIME_LENGTH);
    }

    /**
     * {@code ACCTSIDI}, the account-number search criterion - one of the two fields the operator types
     * and {@code app/cbl/COCRDSLC.cbl:615-619} reads.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAcctsid() {
        return acctsid;
    }

    /**
     * Stores {@code ACCTSIDI} verbatim.
     *
     * @param acctsid the value; {@code null} is taken as {@value #ACCTSID_LENGTH} spaces
     */
    public void setAcctsid(String acctsid) {
        this.acctsid = orSpaces(acctsid, ACCTSID_LENGTH);
    }

    /**
     * {@code CARDSIDI}, the card-number search criterion - the other typed field, read at
     * {@code app/cbl/COCRDSLC.cbl:622-626}.
     *
     * <p>Returned exactly as stored: a full sixteen-digit card number, unmasked, because the symbolic
     * map carries it that way and masking it would be a behaviour change (practice
     * <strong>B6</strong>).
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getCardsid() {
        return cardsid;
    }

    /**
     * Stores {@code CARDSIDI} verbatim.
     *
     * @param cardsid the value; {@code null} is taken as {@value #CARDSID_LENGTH} spaces
     */
    public void setCardsid(String cardsid) {
        this.cardsid = orSpaces(cardsid, CARDSID_LENGTH);
    }

    /**
     * {@code CRDNAMEI}, the embossed name.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getCrdname() {
        return crdname;
    }

    /**
     * Stores {@code CRDNAMEI} verbatim.
     *
     * @param crdname the value; {@code null} is taken as {@value #CRDNAME_LENGTH} spaces
     */
    public void setCrdname(String crdname) {
        this.crdname = orSpaces(crdname, CRDNAME_LENGTH);
    }

    /**
     * {@code CRDSTCDI}, the single-character active-status code.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getCrdstcd() {
        return crdstcd;
    }

    /**
     * Stores {@code CRDSTCDI} verbatim.
     *
     * @param crdstcd the value; {@code null} is taken as {@value #CRDSTCD_LENGTH} space
     */
    public void setCrdstcd(String crdstcd) {
        this.crdstcd = orSpaces(crdstcd, CRDSTCD_LENGTH);
    }

    /**
     * {@code EXPMONI}, the expiry month.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getExpmon() {
        return expmon;
    }

    /**
     * Stores {@code EXPMONI} verbatim.
     *
     * @param expmon the value; {@code null} is taken as {@value #EXPMON_LENGTH} spaces
     */
    public void setExpmon(String expmon) {
        this.expmon = orSpaces(expmon, EXPMON_LENGTH);
    }

    /**
     * {@code EXPYEARI}, the expiry year.
     *
     * <p>There is no {@code EXPDAY} beside it. {@code COCRDSL} declares only the month and the year;
     * only {@code COCRDUP} carries a day, and it is not borrowed for symmetry (practice
     * <strong>B5</strong>).
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getExpyear() {
        return expyear;
    }

    /**
     * Stores {@code EXPYEARI} verbatim.
     *
     * @param expyear the value; {@code null} is taken as {@value #EXPYEAR_LENGTH} spaces
     */
    public void setExpyear(String expyear) {
        this.expyear = orSpaces(expyear, EXPYEAR_LENGTH);
    }

    /**
     * {@code INFOMSGI}, the informational message line - {@value #INFOMSG_LENGTH} characters here,
     * where {@code COCRDLI} declares 45.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getInfomsg() {
        return infomsg;
    }

    /**
     * Stores {@code INFOMSGI} verbatim.
     *
     * @param infomsg the value; {@code null} is taken as {@value #INFOMSG_LENGTH} spaces
     */
    public void setInfomsg(String infomsg) {
        this.infomsg = orSpaces(infomsg, INFOMSG_LENGTH);
    }

    /**
     * {@code ERRMSGI}, the error message line - {@value #ERRMSG_LENGTH} characters here, where
     * {@code COCRDLI} declares 78.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getErrmsg() {
        return errmsg;
    }

    /**
     * Stores {@code ERRMSGI} verbatim.
     *
     * @param errmsg the value; {@code null} is taken as {@value #ERRMSG_LENGTH} spaces
     */
    public void setErrmsg(String errmsg) {
        this.errmsg = orSpaces(errmsg, ERRMSG_LENGTH);
    }

    /**
     * {@code FKEYSI}, the function-key legend - {@value #FKEYS_LENGTH} characters here, 21 on
     * {@code COCRDUP}, and absent from {@code COCRDLI}.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getFkeys() {
        return fkeys;
    }

    /**
     * Stores {@code FKEYSI} verbatim.
     *
     * @param fkeys the value; {@code null} is taken as {@value #FKEYS_LENGTH} spaces
     */
    public void setFkeys(String fkeys) {
        this.fkeys = orSpaces(fkeys, FKEYS_LENGTH);
    }

    // =================================================================================================
    // Addressing a field by its enumeration constant. A parity differ, a validation loop and the group
    // image codec all need to walk the fifteen fields uniformly; these two methods let them do it
    // without reflection and without a string key that can be misspelled.
    // =================================================================================================

    /**
     * The value of one field, chosen by its {@link ScreenField} constant.
     *
     * @param field which field to read
     * @return the stored value, untrimmed; never {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public String value(ScreenField field) {
        Objects.requireNonNull(field, "A ScreenField is required to read a COCRDSL field by name");
        return switch (field) {
            case TRNNAME -> trnname;
            case TITLE01 -> title01;
            case CURDATE -> curdate;
            case PGMNAME -> pgmname;
            case TITLE02 -> title02;
            case CURTIME -> curtime;
            case ACCTSID -> acctsid;
            case CARDSID -> cardsid;
            case CRDNAME -> crdname;
            case CRDSTCD -> crdstcd;
            case EXPMON -> expmon;
            case EXPYEAR -> expyear;
            case INFOMSG -> infomsg;
            case ERRMSG -> errmsg;
            case FKEYS -> fkeys;
        };
    }

    /**
     * Stores one field verbatim, chosen by its {@link ScreenField} constant. Delegates to the field's
     * own setter, so {@code null} handling is identical whichever route a caller takes.
     *
     * @param field which field to write
     * @param value the value; {@code null} is taken as spaces of the field's declared width
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public void setValue(ScreenField field, String value) {
        Objects.requireNonNull(field, "A ScreenField is required to write a COCRDSL field by name");
        switch (field) {
            case TRNNAME -> setTrnname(value);
            case TITLE01 -> setTitle01(value);
            case CURDATE -> setCurdate(value);
            case PGMNAME -> setPgmname(value);
            case TITLE02 -> setTitle02(value);
            case CURTIME -> setCurtime(value);
            case ACCTSID -> setAcctsid(value);
            case CARDSID -> setCardsid(value);
            case CRDNAME -> setCrdname(value);
            case CRDSTCD -> setCrdstcd(value);
            case EXPMON -> setExpmon(value);
            case EXPYEAR -> setExpyear(value);
            case INFOMSG -> setInfomsg(value);
            case ERRMSG -> setErrmsg(value);
            case FKEYS -> setFkeys(value);
        }
    }

    // =================================================================================================
    // The metadata half: xxxL and xxxA. Both accessors are @JsonIgnore'd, so no ScreenFieldMetadata
    // instance reaches the wire and the payload stays exactly the fifteen DFHMDF fields plus the two
    // conversation-state carriers (gate G9).
    // =================================================================================================

    /**
     * The {@code xxxL} and {@code xxxA} items of one field.
     *
     * <p>The holder is returned live, not copied, because {@code COCRDSLC} writes into these items in
     * place: {@code MOVE -1 TO ACCTSIDL OF CCRDSLAI} at {@code app/cbl/COCRDSLC.cbl:518} and
     * {@code MOVE DFHBMPRF TO ACCTSIDA OF CCRDSLAI} at {@code :507} are assignments into the map area,
     * and a defensive copy here would discard them.
     *
     * @param field which field's metadata to address
     * @return that field's holder, never {@code null} - every one of the fifteen is populated at
     *         construction
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public ScreenFieldMetadata metadata(ScreenField field) {
        Objects.requireNonNull(field, "A ScreenField is required to address a field's xxxL and xxxA "
                + "items");
        return metadata.get(field);
    }

    /**
     * All fifteen metadata holders, keyed by field and iterating in copybook storage order.
     *
     * <p>The map is unmodifiable, so no caller can add a sixteenth field or remove one of the fifteen.
     * The <em>holders</em> remain mutable, deliberately, for the reason
     * {@link #metadata(ScreenField)} explains - so this is a guard on the shape of the group, not on its
     * contents.
     *
     * @return an unmodifiable view over the fifteen live holders, never {@code null}
     */
    @JsonIgnore
    public Map<ScreenField, ScreenFieldMetadata> metadata() {
        return Collections.unmodifiableMap(metadata);
    }

    // =================================================================================================
    // The conversation-state carriers.
    // =================================================================================================

    /**
     * The {@code CVCRD01Y} work area this request carries.
     *
     * @return the work area, never {@code null}
     */
    public CardScreenState getCardScreenState() {
        return cardScreenState;
    }

    /**
     * Replaces the {@code CVCRD01Y} work area.
     *
     * @param cardScreenState the work area; {@code null} is taken as a freshly initialised one, which
     *                        is the {@code INITIALIZE CC-WORK-AREA} of
     *                        {@code app/cbl/COCRDSLC.cbl:254} and the only reading consistent with
     *                        there being no null in a COBOL record
     */
    public void setCardScreenState(CardScreenState cardScreenState) {
        this.cardScreenState = cardScreenState == null ? new CardScreenState() : cardScreenState;
    }

    /**
     * The {@code CARDDEMO-COMMAREA} this request carries.
     *
     * @return the communication area, never {@code null}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    /**
     * Replaces the {@code CARDDEMO-COMMAREA}, storing {@code null} verbatim.
     *
     * <p>{@code null} is <strong>not</strong> taken as {@link NavigationContext#empty()}. The two are
     * different states of {@code EIBCALEN} - {@code 0} against
     * {@value NavigationContext#COMMAREA_LENGTH} - and {@code app/cbl/COCRDSLC.cbl:268} branches on
     * exactly that difference. Substituting one for the other deletes the cold-start path.
     *
     * @param navigationContext the communication area, or {@code null} where none travelled with the
     *                          request
     */
    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = navigationContext;
    }

    /**
     * {@code WS-THIS-PROGCOMMAREA} as it arrived - the twelve bytes {@code :276-278} restores.
     *
     * @return the trailer; never {@code null}, and {@link ThisProgCommarea#initialized()} when the
     *         payload stated none
     */
    public ThisProgCommarea getThisProgCommarea() {
        return thisProgCommarea;
    }

    /**
     * Sets {@code WS-THIS-PROGCOMMAREA}.
     *
     * <p>{@code null} is normalised to {@link ThisProgCommarea#initialized()} rather than stored,
     * because a COBOL group item has no absent state and every reader of this member would otherwise
     * have to defend against one.
     *
     * @param thisProgCommarea the trailer, or {@code null} for the initialised twelve spaces
     */
    public void setThisProgCommarea(ThisProgCommarea thisProgCommarea) {
        this.thisProgCommarea =
                thisProgCommarea == null ? ThisProgCommarea.initialized() : thisProgCommarea;
    }

    /**
     * Whether a communication area travelled with this request - the Java reading of {@code EIBCALEN}
     * being non-zero at {@code app/cbl/COCRDSLC.cbl:268}.
     *
     * <p>Not a JSON property: it is derived from {@link #getNavigationContext()}, which is already on
     * the wire as {@code null} or as an object, and a second member could contradict it.
     *
     * @return {@code true} when {@link #getNavigationContext()} is present
     */
    @JsonIgnore
    public boolean hasNavigationContext() {
        return navigationContext != null;
    }

    /**
     * The length CICS would report in {@code EIBCALEN}: {@value #PASSED_COMMAREA_LENGTH} when a
     * communication area travelled with this request and {@code 0} when none did.
     *
     * <p>{@value #PASSED_COMMAREA_LENGTH} and not {@value NavigationContext#COMMAREA_LENGTH}, because
     * what {@code COCRDSLC} passes and receives is {@code CARDDEMO-COMMAREA} <em>followed by</em>
     * {@code WS-THIS-PROGCOMMAREA}: {@code app/cbl/COCRDSLC.cbl:397-400} composes the two into
     * {@code WS-COMMAREA} before the {@code EXEC CICS RETURN} at {@code :402-406}, and {@code :274-278}
     * splits an inbound area back into both. A caller that reported 160 here would be describing an
     * area this program never sends.
     *
     * @return {@value #PASSED_COMMAREA_LENGTH} or {@code 0}
     */
    @JsonIgnore
    public int commareaLength() {
        return hasNavigationContext() ? PASSED_COMMAREA_LENGTH : 0;
    }

    /**
     * The length of the area this screen passes and receives: {@code CARDDEMO-COMMAREA} plus
     * {@code WS-THIS-PROGCOMMAREA}, {@value NavigationContext#COMMAREA_LENGTH} +
     * {@value ThisProgCommarea#RECORD_LENGTH} = {@value #PASSED_COMMAREA_LENGTH} bytes.
     */
    public static final int PASSED_COMMAREA_LENGTH =
            NavigationContext.COMMAREA_LENGTH + ThisProgCommarea.RECORD_LENGTH;

    /**
     * {@code CDEMO-PGM-CONTEXT PIC 9(01)} as carried by the communication area, or
     * {@value NavigationContext#PGM_CONTEXT_ENTER} where no area travelled.
     *
     * <p>The fallback is the arm an uninitialised area takes, and it is not observable in the COBOL:
     * the cold-start branch at {@code app/cbl/COCRDSLC.cbl:271-272} {@code INITIALIZE}s the area
     * before anything reads the context out of it. A caller that must tell an absent area from a
     * present one holding {@value NavigationContext#PGM_CONTEXT_ENTER} asks
     * {@link #hasNavigationContext()}.
     *
     * @return the program context, or {@value NavigationContext#PGM_CONTEXT_ENTER} when absent
     */
    @JsonIgnore
    public int getPgmContext() {
        return hasNavigationContext()
                ? navigationContext.pgmContext()
                : NavigationContext.PGM_CONTEXT_ENTER;
    }

    /**
     * Whether this is first entry - {@code 88 CDEMO-PGM-ENTER VALUE 0}.
     *
     * <p>Delegates to {@link NavigationContext#isEnter()}. Present on the request because the
     * distinction governs what a controller does next: on first entry it paints the screen, on re-entry
     * it validates what was typed.
     *
     * <p>{@link JsonIgnore}d: the value is derived from
     * {@link NavigationContext#pgmContext()}, which is already on the wire inside the carrier, so
     * publishing it again would let one payload carry the same fact twice and disagree with itself.
     *
     * <p>False when no communication area travelled: the condition name is a test over a field, and
     * there is no field to test. With {@link #isReenter()} that gives three states, not two - which is
     * what the cold-start disjunct at {@code app/cbl/COCRDSLC.cbl:268} needs.
     *
     * @return {@code true} when a communication area travelled and its carried program context is
     *         {@value NavigationContext#PGM_CONTEXT_ENTER}
     */
    @JsonIgnore
    public boolean isEnter() {
        return hasNavigationContext() && navigationContext.isEnter();
    }

    /**
     * Whether this is re-entry - {@code 88 CDEMO-PGM-REENTER VALUE 1}.
     *
     * <p>Delegates to {@link NavigationContext#isReenter()}, and is the conjunct that lets a field be
     * highlighted at all: {@code app/cbl/COCRDSLC.cbl:543-544} and {@code :549-550} apply {@code '*'}
     * and {@code DFHRED} only under {@code IF FLG-ACCTFILTER-BLANK AND CDEMO-PGM-REENTER}. That is the
     * value {@code common/FieldAttributeSetter} takes as its re-entry argument.
     *
     * <p>Deliberately not written as the negation of {@link #isEnter()}: {@code CDEMO-PGM-CONTEXT} is
     * {@code PIC 9(01)} and can hold any digit, so a context of 9 satisfies neither condition - and
     * neither does an absent communication area.
     *
     * <p>{@link JsonIgnore}d for the same reason as {@link #isEnter()}.
     *
     * @return {@code true} when a communication area travelled and its carried program context is
     *         {@value NavigationContext#PGM_CONTEXT_REENTER}
     */
    @JsonIgnore
    public boolean isReenter() {
        return hasNavigationContext() && navigationContext.isReenter();
    }

    // =================================================================================================
    // Fixed-width rendering. This is the only place a field's declared width is imposed on its value,
    // and it is imposed by FixedWidthCodec.movePicX and by nothing else (practice B11).
    // =================================================================================================

    /**
     * One field as a {@code PIC X} image of exactly its declared width: padded on the right with spaces
     * when the stored value is short, truncated on the right when it is long.
     *
     * <p>The truncation direction is the COBOL rule for an alphanumeric receiver - filled from the
     * leftmost character position, with the overflow discarded - and it is
     * {@link FixedWidthCodec#movePicX(String, int)} that applies it. Nothing in this class pads or
     * truncates by hand, so there is exactly one place in the system where that rule can be wrong.
     *
     * @param field which field to render
     * @param codec the codec whose {@code PIC X} move rule and charset apply
     * @return an image of exactly {@code field.length()} characters, never {@code null}
     * @throws NullPointerException if {@code field} or {@code codec} is {@code null}
     */
    public String image(ScreenField field, FixedWidthCodec codec) {
        Objects.requireNonNull(field, "A ScreenField is required to render a field image");
        Objects.requireNonNull(codec, "A FixedWidthCodec is required: the PIC X move rule lives there "
                + "and is never reimplemented here");
        return codec.movePicX(value(field), field.length());
    }

    /**
     * Applies the {@code PIC X} move rule to all fifteen fields in place, leaving every one at exactly
     * its declared width.
     *
     * <p>The explicit equivalent of a COBOL {@code MOVE} into each {@code xxxI} receiver. A controller
     * calls it once, deliberately, when it wants the request to hold map-shaped values - for example
     * before a field-by-field parity comparison, where a short value and its space-padded image are
     * different byte strings and must not be confused.
     *
     * @param codec the codec whose {@code PIC X} move rule applies
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public void normalize(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to normalise the fifteen COCRDSL "
                + "fields to their declared widths");
        for (ScreenField field : ScreenField.values()) {
            setValue(field, image(field, codec));
        }
    }

    /**
     * The whole {@code CCRDSLAI} input group as exactly {@value #GROUP_LENGTH} bytes.
     *
     * <p>Laid out precisely as {@code app/cpy-bms/COCRDSL.CPY:17-108} declares it:
     *
     * <ol>
     *   <li>{@value #TIOAPFX_LENGTH} bytes of {@code TIOAPFX} prefix, space-filled in the codec's
     *       charset. The prefix carries no application data; it is written so the group is the width the
     *       copybook declares.</li>
     *   <li>then, for each of the fifteen fields in copybook order: the {@code xxxL} halfword as two
     *       big-endian bytes, the {@code xxxA} attribute byte raw, {@value #EXTENDED_ATTRIBUTE_ITEM_LENGTH}
     *       bytes of extended-attribute {@code FILLER} left at {@code LOW-VALUES}, and the field's
     *       {@code PIC X} image at its declared width.</li>
     * </ol>
     *
     * <p>The halfword is written big-endian, which is how a mainframe halfword is laid out, so
     * {@value ScreenFieldMetadata#CURSOR_HERE} renders as {@code 0xFFFF} - two's complement, exactly as
     * {@code COMP PIC S9(4)} storage holds it.
     *
     * <p>The character data is encoded through {@link FixedWidthCodec#charset()} and never through a
     * platform default, so a space is {@code 0x40} under IBM037 and {@code 0x20} under US-ASCII without
     * this method knowing which. If a charset encodes any field's image to a number of bytes other than
     * its declared width - as a multi-byte code page would - the method fails loudly rather than
     * returning a group of the wrong length.
     *
     * <p>The attribute byte is <em>not</em> encoded through the charset. It is a 3270 bit pattern rather
     * than text, and {@code common/BmsAttributes} declares every constant as a {@code byte} for exactly
     * that reason.
     *
     * @param codec the codec supplying the {@code PIC X} move rule and the charset
     * @return a new array of exactly {@value #GROUP_LENGTH} bytes, never {@code null}
     * @throws NullPointerException     if {@code codec} is {@code null}
     * @throws IllegalArgumentException if the codec's charset does not encode this data one byte per
     *                                  character
     */
    public byte[] toGroupImage(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to render the CCRDSLAI group "
                + "image: it supplies both the PIC X move rule and the charset");
        Charset charset = codec.charset();
        byte[] group = new byte[GROUP_LENGTH];
        writeCharacters(group, 0, spaces(TIOAPFX_LENGTH), TIOAPFX_LENGTH, charset,
                "the " + TIOAPFX_LENGTH + "-byte TIOAPFX prefix");
        for (ScreenField field : ScreenField.values()) {
            ScreenFieldMetadata holder = metadata.get(field);
            int lengthItem = holder.getLength();
            group[field.lengthItemOffset()] = (byte) ((lengthItem >> 8) & 0xFF);
            group[field.lengthItemOffset() + 1] = (byte) (lengthItem & 0xFF);
            group[field.flagItemOffset()] = holder.getAttribute();
            // Written rather than left to Java's zero-initialisation, so that "these four bytes are
            // LOW-VALUES on input" is a statement the code makes instead of an accident of the language.
            Arrays.fill(group,
                    field.extendedAttributeItemOffset(),
                    field.extendedAttributeItemOffset() + EXTENDED_ATTRIBUTE_ITEM_LENGTH,
                    LOW_VALUE_BYTE);
            writeCharacters(group, field.dataOffset(), image(field, codec), field.length(), charset,
                    field.describe());
        }
        return group;
    }

    /**
     * Reads a {@value #GROUP_LENGTH}-byte {@code CCRDSLAI} image back into a request.
     *
     * <p>Recovers the fifteen {@code xxxI} values at their full declared widths - untrimmed, because a
     * {@code PIC X} field's trailing spaces are part of its value - together with each field's
     * {@code xxxL} halfword and {@code xxxA} attribute byte.
     *
     * <p>Three parts of the image are deliberately <strong>not</strong> recovered, because none of them
     * is data:
     *
     * <ul>
     *   <li>the {@value #TIOAPFX_LENGTH}-byte {@code TIOAPFX} prefix, which belongs to the terminal
     *       input/output area and not to the application;</li>
     *   <li>the {@value #EXTENDED_ATTRIBUTE_ITEM_LENGTH} extended-attribute {@code FILLER} bytes per
     *       field, unnamed and unused on input - the output group names them {@code xxxC}, {@code xxxP},
     *       {@code xxxH} and {@code xxxV}, and they are {@code CardSelectResponse}'s concern;</li>
     *   <li>the two conversation-state carriers, which are separate storage entirely: the
     *       {@code CVCRD01Y} work area and the {@code CARDDEMO-COMMAREA} are not part of the BMS map and
     *       travel on their own. The returned request carries both at their initialised state.</li>
     * </ul>
     *
     * <p>So the round trip is exact where it can be: for a request whose carriers are at their
     * initialised state, {@code fromGroupImage(x.toGroupImage(codec), codec)} equals {@code x} whenever
     * {@code x}'s fifteen values are already at their declared widths. For a request holding
     * off-width values, normalise it first - {@link #normalize(FixedWidthCodec)} - since the image can
     * only hold the widths the copybook declares.
     *
     * @param groupImage the {@value #GROUP_LENGTH}-byte input group; read, never retained
     * @param codec      the codec supplying the charset
     * @return a request carrying the image's fifteen fields and their metadata, never {@code null}
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code groupImage} is not exactly {@value #GROUP_LENGTH}
     *                                  bytes, or holds a length item outside the range
     *                                  {@code COMP PIC S9(4)} can represent
     */
    public static CardSelectRequest fromGroupImage(byte[] groupImage, FixedWidthCodec codec) {
        Objects.requireNonNull(groupImage, "A group image is required to read a CCRDSLAI area");
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to read a CCRDSLAI area: it "
                + "supplies the charset the field data is encoded in");
        if (groupImage.length != GROUP_LENGTH) {
            throw new IllegalArgumentException("The CCRDSLAI group of app/cpy-bms/COCRDSL.CPY is "
                    + GROUP_LENGTH + " bytes - " + TIOAPFX_LENGTH + " of TIOAPFX prefix, plus "
                    + FIELD_COUNT + " fields at " + FIELD_OVERHEAD + " bytes of overhead each, plus "
                    + PAYLOAD_LENGTH + " bytes of data - but this image is " + groupImage.length
                    + " byte(s)");
        }
        Charset charset = codec.charset();
        CardSelectRequest request = new CardSelectRequest();
        for (ScreenField field : ScreenField.values()) {
            int lengthItem = decodeHalfword(groupImage, field);
            ScreenFieldMetadata holder = request.metadata.get(field);
            holder.setLength(lengthItem);
            holder.setAttribute(groupImage[field.flagItemOffset()]);
            request.setValue(field, new FixedWidthRecord.Transcoder(charset).decode(groupImage,
                    field.dataOffset(), field.length(), "the CCRDSLAI item " + field.name()));
        }
        return request;
    }

    /**
     * Reads one field's {@code xxxL COMP PIC S9(4)} item from a group image as a big-endian, signed
     * halfword, and rejects a value the {@code PICTURE} cannot represent.
     *
     * <p>The cast to {@code short} is what makes the sign work: the two bytes are combined as an
     * {@code int} and then narrowed, so {@code 0xFFFF} reads back as {@value ScreenFieldMetadata#CURSOR_HERE}
     * rather than as 65535.
     *
     * <p>Storage capacity and declared capacity differ here, and the declaration wins. A halfword holds
     * &plusmn;32767 but {@code S9(4)} holds only {@value ScreenFieldMetadata#LENGTH_ITEM_MIN} to
     * {@value ScreenFieldMetadata#LENGTH_ITEM_MAX}, so a byte pair outside that range means the image
     * and the copybook disagree - and accepting it quietly is exactly how a plausible-looking parity
     * defect gets in.
     *
     * @param groupImage the group image to read from
     * @param field      which field's length item to read
     * @return the halfword value
     * @throws IllegalArgumentException if the value is outside the {@code PIC S9(4)} range
     */
    private static int decodeHalfword(byte[] groupImage, ScreenField field) {
        int offset = field.lengthItemOffset();
        int halfword = (short) ((groupImage[offset] << 8) | (groupImage[offset + 1] & 0xFF));
        if (halfword < ScreenFieldMetadata.LENGTH_ITEM_MIN
                || halfword > ScreenFieldMetadata.LENGTH_ITEM_MAX) {
            throw new IllegalArgumentException("The xxxL item of " + field.describe() + " reads "
                    + halfword + " at offset " + offset + ", which COMP PIC S9(4) cannot represent: it "
                    + "holds " + ScreenFieldMetadata.LENGTH_ITEM_MIN + " to "
                    + ScreenFieldMetadata.LENGTH_ITEM_MAX);
        }
        return halfword;
    }

    /**
     * Encodes a {@code PIC X} image into a group image at a given offset, insisting that it occupy
     * exactly the declared number of bytes.
     *
     * <p>The insistence is the point. A {@code PIC X(n)} item is n <em>bytes</em>, and every code page
     * this system reads - IBM037 for the EBCDIC datasets, US-ASCII for the ASCII fixtures - is
     * single-byte, so n characters must encode to n bytes. Were a multi-byte charset ever configured,
     * the group would silently overflow its declared width and every offset after it would shift; this
     * check turns that into an immediate, named failure instead.
     *
     * @param group         the group image being built
     * @param offset        where the item begins
     * @param image         the item's value, already at its declared character width
     * @param declaredWidth the item's declared width in bytes
     * @param charset       the charset to encode with, from {@link FixedWidthCodec#charset()}
     * @param what          how to describe the item in a failure message
     * @throws IllegalArgumentException if the encoding is not exactly {@code declaredWidth} bytes
     */
    private static void writeCharacters(byte[] group,
                                        int offset,
                                        String image,
                                        int declaredWidth,
                                        Charset charset,
                                        String what) {
        byte[] encoded = FixedWidthRecord.encodeText(image, charset, what);
        if (encoded.length != declaredWidth) {
            throw new IllegalArgumentException("Charset " + charset.name() + " encodes " + what
                    + " to " + encoded.length + " byte(s) where the copybook declares "
                    + declaredWidth + "; a PIC X(n) item is n bytes, so the CCRDSLAI group can only be "
                    + "rendered with a single-byte code page such as IBM037 or US-ASCII");
        }
        System.arraycopy(encoded, 0, group, offset, declaredWidth);
    }

    // =================================================================================================
    // Value semantics. Everything the instance holds participates: the fifteen fields, both carriers and
    // all fifteen metadata holders. The metadata is included because it is behaviour - a request with
    // the cursor on ACCTSID is not the same request as one with the cursor on CARDSID - even though it
    // never reaches the wire.
    // =================================================================================================

    /**
     * Value equality over the fifteen fields, both conversation-state carriers and all fifteen metadata
     * holders.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a request holding the same values throughout
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CardSelectRequest that)) {
            return false;
        }
        return trnname.equals(that.trnname)
                && title01.equals(that.title01)
                && curdate.equals(that.curdate)
                && pgmname.equals(that.pgmname)
                && title02.equals(that.title02)
                && curtime.equals(that.curtime)
                && acctsid.equals(that.acctsid)
                && cardsid.equals(that.cardsid)
                && crdname.equals(that.crdname)
                && crdstcd.equals(that.crdstcd)
                && expmon.equals(that.expmon)
                && expyear.equals(that.expyear)
                && infomsg.equals(that.infomsg)
                && errmsg.equals(that.errmsg)
                && fkeys.equals(that.fkeys)
                && cardScreenState.equals(that.cardScreenState)
                && Objects.equals(navigationContext, that.navigationContext)
                && metadata.equals(that.metadata);
    }

    /**
     * A hash consistent with {@link #equals(Object)}.
     *
     * @return the combined hash of every value this request holds
     */
    @Override
    public int hashCode() {
        return Objects.hash(trnname, title01, curdate, pgmname, title02, curtime, acctsid, cardsid,
                crdname, crdstcd, expmon, expyear, infomsg, errmsg, fkeys, cardScreenState,
                navigationContext, metadata);
    }

    /**
     * A diagnostic rendering naming every field by its {@code DFHMDF} label.
     *
     * <p><strong>{@code CARDSID}, {@code ACCTSID} and {@code CRDNAME} are redacted here, and only
     * here.</strong> They are a sixteen-digit card number, an account identifier and a cardholder's
     * name, and a diagnostic is the one place they reach somewhere nobody chose to put them: a log
     * file, a test failure message, an exception trail. Each is replaced with
     * {@value #REDACTED_VALUE} followed by its actual length, so the rendering still answers "was the
     * field populated, and at what width", which is what a failure message is read for.
     *
     * <p>This is <strong>not</strong> a behaviour change to the screen, and it is not the COBOL being
     * sanitised. The JSON body carries all fifteen fields exactly as the symbolic map declares them,
     * {@link #toGroupImage(FixedWidthCodec)} writes the same bytes it always wrote, and
     * {@link #value(ScreenField)} returns the value in the clear - the 3270 shows it in the clear.
     * Only this method changes, and it is documented as being for diagnostics and never a wire format.
     *
     * <p>Every other field renders verbatim, and values are quoted so that the trailing spaces of a
     * {@code PIC X} field, which are part of its value, stay visible in a failure message.
     *
     * @return a single-line rendering of every field, both carriers and all fifteen metadata holders
     */
    @Override
    public String toString() {
        StringBuilder rendered = new StringBuilder("CardSelectRequest[");
        for (ScreenField field : ScreenField.values()) {
            rendered.append(field.label())
                    .append("='")
                    .append(SensitiveDiagnostics.render(disclosureOf(field), value(field)))
                    .append("' ")
                    .append(metadata.get(field))
                    .append(", ");
        }
        return rendered.append("cardScreenState=")
                .append(cardScreenState)
                .append(", navigationContext=")
                .append(navigationContext)
                .append(']')
                .toString();
    }

    // =================================================================================================
    // The fifteen fields, as an enumeration. This exists so that every place needing to address a field
    // - the group-image codec, the metadata holder, a parity differ - names it in a way the compiler
    // checks, instead of passing a string that can be misspelled. Each constant carries the whole of
    // its provenance: the DFHMDF label, the symbolic-map item, the width, the two source line numbers
    // and the screen position, so gate G9 traceability is readable from one place.
    // =================================================================================================

    /**
     * One of the fifteen name-labelled {@code DFHMDF} fields of {@code app/bms/COCRDSL.bms},
     * in the order the mapset declares them - which is also the order
     * {@code app/cpy-bms/COCRDSL.CPY} lays their storage down, and the order they appear on the
     * 24&nbsp;&times;&nbsp;80 screen reading top to bottom.
     *
     * <p>The sixteen unnamed {@code DFHMDF} entries of the mapset have no constant here. An unnamed
     * entry is a screen literal - {@code INITIAL='Tran:'}, {@code INITIAL='Account Number:'} and the
     * like - which BMS paints but never reports back, so it generates no symbolic-map item and holds no
     * value this class could carry.
     *
     * <p>{@code EXPDAY} and {@code PAGENO} are absent because {@code COCRDSL} does not declare them:
     * the first belongs to {@code COCRDUP} and the second to {@code COCRDLI}.
     */
    public enum ScreenField {

        /** {@code TRNNAME} - the transaction identifier, {@code TRNNAMEI PIC X(4)}. */
        TRNNAME("TRNNAME", "TRNNAMEI", TRNNAME_LENGTH, 24, 34, 1, 7, 19),

        /** {@code TITLE01} - the first title line, {@code TITLE01I PIC X(40)}. */
        TITLE01("TITLE01", "TITLE01I", TITLE01_LENGTH, 30, 38, 1, 21, 30),

        /** {@code CURDATE} - the current date, {@code CURDATEI PIC X(8)}. */
        CURDATE("CURDATE", "CURDATEI", CURDATE_LENGTH, 36, 47, 1, 71, 77),

        /** {@code PGMNAME} - the program name, {@code PGMNAMEI PIC X(8)}. */
        PGMNAME("PGMNAME", "PGMNAMEI", PGMNAME_LENGTH, 42, 57, 2, 7, 92),

        /** {@code TITLE02} - the second title line, {@code TITLE02I PIC X(40)}. */
        TITLE02("TITLE02", "TITLE02I", TITLE02_LENGTH, 48, 61, 2, 21, 107),

        /** {@code CURTIME} - the current time, {@code CURTIMEI PIC X(8)}. */
        CURTIME("CURTIME", "CURTIMEI", CURTIME_LENGTH, 54, 70, 2, 71, 154),

        /**
         * {@code ACCTSID} - the account-number search field, {@code ACCTSIDI PIC X(11)}.
         *
         * <p>One of the two fields the operator can type into: the mapset declares it
         * {@code ATTRB=(FSET,IC,NORM,UNPROT)} at {@code app/bms/COCRDSL.bms:84}, and {@code IC} puts
         * the initial cursor here. {@code app/cbl/COCRDSLC.cbl:615-619} reads it.
         */
        ACCTSID("ACCTSID", "ACCTSIDI", ACCTSID_LENGTH, 60, 84, 7, 45, 169),

        /**
         * {@code CARDSID} - the card-number search field, {@code CARDSIDI PIC X(16)}.
         *
         * <p>The other typed field, {@code ATTRB=(FSET,NORM,UNPROT)} at
         * {@code app/bms/COCRDSL.bms:96}. {@code app/cbl/COCRDSLC.cbl:622-626} reads it. Sixteen
         * digits of card number, carried in the clear exactly as the map declares them.
         */
        CARDSID("CARDSID", "CARDSIDI", CARDSID_LENGTH, 66, 96, 8, 45, 187),

        /** {@code CRDNAME} - the embossed name, {@code CRDNAMEI PIC X(50)}. */
        CRDNAME("CRDNAME", "CRDNAMEI", CRDNAME_LENGTH, 72, 107, 11, 25, 210),

        /** {@code CRDSTCD} - the active-status code, {@code CRDSTCDI PIC X(1)}. */
        CRDSTCD("CRDSTCD", "CRDSTCDI", CRDSTCD_LENGTH, 78, 116, 13, 25, 267),

        /** {@code EXPMON} - the expiry month, {@code EXPMONI PIC X(2)}. */
        EXPMON("EXPMON", "EXPMONI", EXPMON_LENGTH, 84, 126, 15, 25, 275),

        /** {@code EXPYEAR} - the expiry year, {@code EXPYEARI PIC X(4)}. */
        EXPYEAR("EXPYEAR", "EXPYEARI", EXPYEAR_LENGTH, 90, 133, 15, 30, 284),

        /** {@code INFOMSG} - the informational message line, {@code INFOMSGI PIC X(40)}. */
        INFOMSG("INFOMSG", "INFOMSGI", INFOMSG_LENGTH, 96, 139, 20, 25, 295),

        /** {@code ERRMSG} - the error message line, {@code ERRMSGI PIC X(80)}. */
        ERRMSG("ERRMSG", "ERRMSGI", ERRMSG_LENGTH, 102, 144, 23, 1, 342),

        /**
         * {@code FKEYS} - the function-key legend, {@code FKEYSI PIC X(75)}.
         *
         * <p>The mapset gives it {@code INITIAL='ENTER=Search Cards  F3=Exit'} at
         * {@code app/bms/COCRDSL.bms:149}, and it is the last field of the group: its data ends on
         * byte {@value CardSelectRequest#GROUP_LENGTH}.
         */
        FKEYS("FKEYS", "FKEYSI", FKEYS_LENGTH, 108, 148, 24, 1, 429);

        /** Backs {@link #label()}: the {@code DFHMDF} label, verbatim and upper case. */
        private final String label;

        /** Backs {@link #symbolicItemName()}: the label with the input group's {@code I} suffix. */
        private final String symbolicItemName;

        /** Backs {@link #length()}: the declared width, from the {@code xxxI PICTURE} clause. */
        private final int length;

        /** Backs {@link #copybookLine()}: the {@code app/cpy-bms/COCRDSL.CPY} line. */
        private final int copybookLine;

        /** Backs {@link #mapsetLine()}: the {@code app/bms/COCRDSL.bms} line. */
        private final int mapsetLine;

        /** Backs {@link #screenRow()}: the first operand of {@code POS=}. */
        private final int screenRow;

        /** Backs {@link #screenColumn()}: the second operand of {@code POS=}. */
        private final int screenColumn;

        /**
         * Backs {@link #dataOffset()}: where the {@code xxxI} data begins in the group image.
         *
         * <p>The one transcribed number per constant from which
         * {@link #lengthItemOffset()}, {@link #flagItemOffset()} and
         * {@link #extendedAttributeItemOffset()} are all derived, and which
         * {@link CardSelectRequest#verifyFieldStrides()} checks against a walk of the strides.
         */
        private final int dataOffset;

        /**
         * Records one field's provenance. Every argument is transcribed from
         * {@code app/cpy-bms/COCRDSL.CPY} or {@code app/bms/COCRDSL.bms}; none is computed, so a
         * reviewer can compare each against the source without doing arithmetic.
         *
         * @param label            the {@code DFHMDF} label
         * @param symbolicItemName the input group's {@code xxxI} item name
         * @param length           the declared width in characters
         * @param copybookLine     the {@code COCRDSL.CPY} line declaring the {@code xxxI} item
         * @param mapsetLine       the {@code COCRDSL.bms} line opening the {@code DFHMDF} entry
         * @param screenRow        the {@code POS=} row, 1 to 24
         * @param screenColumn     the {@code POS=} column, 1 to 80
         * @param dataOffset       where the field's data begins in the group image
         */
        ScreenField(String label,
                    String symbolicItemName,
                    int length,
                    int copybookLine,
                    int mapsetLine,
                    int screenRow,
                    int screenColumn,
                    int dataOffset) {
            this.label = label;
            this.symbolicItemName = symbolicItemName;
            this.length = length;
            this.copybookLine = copybookLine;
            this.mapsetLine = mapsetLine;
            this.screenRow = screenRow;
            this.screenColumn = screenColumn;
            this.dataOffset = dataOffset;
        }

        /**
         * The field's {@code DFHMDF} label, spelled exactly as {@code app/bms/COCRDSL.bms} spells it.
         *
         * <p>Carried verbatim, in upper case and without decoration, because this is the name a
         * field-by-field parity diff reports and a "tidied" name would make a real difference
         * unrecognisable.
         *
         * @return the label, for example {@code CARDSID}; never {@code null}
         */
        public String label() {
            return label;
        }

        /**
         * The input item's name in {@code app/cpy-bms/COCRDSL.CPY} - the label with the {@code I}
         * suffix BMS appends for the input group.
         *
         * @return the symbolic-map item name, for example {@code CARDSIDI}; never {@code null}
         */
        public String symbolicItemName() {
            return symbolicItemName;
        }

        /**
         * The field's declared width in characters, from its {@code xxxI PICTURE} clause and equally
         * from its {@code DFHMDF LENGTH=} operand.
         *
         * @return the width, at least 1
         */
        public int length() {
            return length;
        }

        /**
         * The line of {@code app/cpy-bms/COCRDSL.CPY} declaring this field's {@code xxxI} item.
         *
         * @return a one-based line number
         */
        public int copybookLine() {
            return copybookLine;
        }

        /**
         * The line of {@code app/bms/COCRDSL.bms} opening this field's {@code DFHMDF} entry.
         *
         * @return a one-based line number
         */
        public int mapsetLine() {
            return mapsetLine;
        }

        /**
         * The field's screen row, from the first operand of its {@code POS=} clause.
         *
         * @return a one-based row between 1 and 24, the screen being {@code SIZE=(24,80)}
         */
        public int screenRow() {
            return screenRow;
        }

        /**
         * The field's screen column, from the second operand of its {@code POS=} clause.
         *
         * @return a one-based column between 1 and 80
         */
        public int screenColumn() {
            return screenColumn;
        }

        /**
         * Offset of this field's {@code xxxI} data within the {@value CardSelectRequest#GROUP_LENGTH}
         * byte group image.
         *
         * @return a zero-based offset
         */
        public int dataOffset() {
            return dataOffset;
        }

        /**
         * Offset of this field's {@code xxxL COMP PIC S9(4)} length halfword, which is where the
         * field's storage begins.
         *
         * @return a zero-based offset, {@value CardSelectRequest#FIELD_OVERHEAD} bytes before
         *         {@link #dataOffset()}
         */
        public int lengthItemOffset() {
            return dataOffset - FIELD_OVERHEAD;
        }

        /**
         * Offset of this field's {@code xxxF} flag byte, which {@code 03 xxxA PICTURE X} redefines.
         *
         * @return a zero-based offset
         */
        public int flagItemOffset() {
            return lengthItemOffset() + LENGTH_ITEM_LENGTH;
        }

        /**
         * Offset of this field's {@code 02 FILLER PICTURE X(4)} extended-attribute item - the four
         * bytes the output group names {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV}.
         *
         * @return a zero-based offset
         */
        public int extendedAttributeItemOffset() {
            return flagItemOffset() + FLAG_ITEM_LENGTH;
        }

        /**
         * The offset one past this field's last data byte, which is where the next field's storage
         * begins - and, for {@link #FKEYS}, the group length itself.
         *
         * @return a zero-based exclusive end offset
         */
        public int endOffsetExclusive() {
            return dataOffset + length;
        }

        /**
         * A one-line, reviewable summary of this field's provenance, for a diagnostic message or a
         * failing parity assertion.
         *
         * @return for example
         *         {@code CARDSID CARDSIDI PIC X(16) COCRDSL.CPY:66 COCRDSL.bms:96 POS=(8,45) offset 187..203}
         */
        public String describe() {
            return label + " " + symbolicItemName + " PIC X(" + length + ") COCRDSL.CPY:"
                    + copybookLine + " COCRDSL.bms:" + mapsetLine + " POS=(" + screenRow + ","
                    + screenColumn + ") offset " + dataOffset + ".." + endOffsetExclusive();
        }

        /**
         * The field carrying a given {@code DFHMDF} label.
         *
         * <p>Matching is exact and case-sensitive, because {@code app/bms/COCRDSL.bms} spells every
         * label in upper case and a lenient match would let a misspelling resolve to the wrong field.
         *
         * @param label the {@code DFHMDF} label to look up, for example {@code CARDSID}
         * @return the matching field, never {@code null}
         * @throws NullPointerException     if {@code label} is {@code null}
         * @throws IllegalArgumentException if no field carries that label
         */
        public static ScreenField byLabel(String label) {
            Objects.requireNonNull(label, "A DFHMDF label is required to look up a COCRDSL field");
            for (ScreenField candidate : values()) {
                if (candidate.label.equals(label)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("app/bms/COCRDSL.bms declares no name-labelled DFHMDF "
                    + "field called '" + label + "'; the fifteen it declares are TRNNAME TITLE01 "
                    + "CURDATE PGMNAME TITLE02 CURTIME ACCTSID CARDSID CRDNAME CRDSTCD EXPMON EXPYEAR "
                    + "INFOMSG ERRMSG FKEYS");
        }
    }

    // =================================================================================================
    // The non-payload half of each field: the xxxL length halfword and the xxxA attribute byte. These
    // are storage the copybook declares and the program writes, so they exist here - but they are not
    // data the screen carries, so they are kept off the JSON wire.
    // =================================================================================================

    /**
     * The {@code xxxL} and {@code xxxA} items of one input field: the two pieces of per-field metadata
     * a BMS symbolic map declares beside the data, and the two {@code COCRDSLC} actually writes.
     *
     * <p>Never serialised. {@link CardSelectRequest#metadata(ScreenField)} and
     * {@link CardSelectRequest#metadata()} are both {@link JsonIgnore}d, so no instance of this class
     * reaches the wire. Publishing it would add payload members tracing to no {@code DFHMDF} field and
     * break gate <strong>G9</strong>; omitting it altogether would lose the cursor positioning and the
     * field protection {@code COCRDSLC} performs. Holding it here, off the wire, keeps both true.
     *
     * <h2>The length item</h2>
     *
     * <p>{@code 02 xxxL COMP PIC S9(4)} is a signed binary halfword. On a {@code RECEIVE MAP} CICS sets
     * it to the number of characters the operator typed, so zero means the field was not entered - the
     * only mechanism a symbolic map offers for telling "left blank" from "blanked out". On a
     * {@code SEND MAP} the value {@value #CURSOR_HERE} has a second meaning: it asks CICS to place the
     * cursor on that field. {@code app/cbl/COCRDSLC.cbl} uses exactly that, three times, in
     * {@code 1300-SETUP-SCREEN-ATTRS}:
     *
     * <pre>
     *   EVALUATE TRUE
     *      WHEN FLG-ACCTFILTER-NOT-OK
     *      WHEN FLG-ACCTFILTER-BLANK
     *           MOVE -1             TO ACCTSIDL OF CCRDSLAI      *&gt; line 518
     *      WHEN FLG-CARDFILTER-NOT-OK
     *      WHEN FLG-CARDFILTER-BLANK
     *           MOVE -1             TO CARDSIDL OF CCRDSLAI      *&gt; line 521
     *      WHEN OTHER
     *           MOVE -1             TO ACCTSIDL OF CCRDSLAI      *&gt; line 523
     *   END-EVALUATE
     * </pre>
     *
     * <p>The declared {@code PICTURE} is the constraint, not the storage: a halfword holds
     * &plusmn;32767, but {@code S9(4)} holds only {@value #LENGTH_ITEM_MIN} to
     * {@value #LENGTH_ITEM_MAX}, and {@link #setLength(int)} enforces the {@code PICTURE}. Rejecting a
     * value the copybook cannot represent is the point: silently storing 30000 in a field declared
     * {@code S9(4)} is precisely the kind of divergence a parity migration exists to prevent.
     *
     * <h2>The attribute item</h2>
     *
     * <p>{@code 02 xxxF PICTURE X} is the flag byte and {@code 03 xxxA PICTURE X}, declared under
     * {@code 02 FILLER REDEFINES xxxF}, is a second view of that same single byte. There is therefore
     * one value here, not two - {@link #getAttribute()} is the {@code xxxA} view and
     * {@link #getFlag()} the {@code xxxF} view of the identical byte, which is what a
     * {@code REDEFINES} means.
     *
     * <p>{@code COCRDSLC} writes it through the {@code xxxA} view at
     * {@code app/cbl/COCRDSLC.cbl:507-511}: {@code DFHBMPRF} - protected - onto {@code ACCTSIDA} and
     * {@code CARDSIDA} when the screen was reached from the card list, and {@code DFHBMFSE} -
     * unprotected with the modified-data tag forced on - otherwise. The constants themselves live in
     * {@code common/BmsAttributes}, sourced from IBM CICS documentation because {@code DFHBMSCA} is not
     * present in this repository; this class stores whichever byte it is handed and interprets none of
     * them, so it needs no knowledge of the attribute alphabet.
     *
     * <p>It is held as a {@code byte} rather than as a character, matching
     * {@code common/BmsAttributes}, which declares every constant as one - {@code DFHBMPRF} is
     * {@code (byte) 0x61} and {@code DFHBMFSE} is {@code (byte) 0xC1} - and matching
     * {@code common/FieldAttributeSetter}, whose colour-item accessor returns one. A 3270 attribute is a
     * bit pattern, not text: it is not a character in any code page's printable range, and passing it
     * through a character decoder would corrupt it. {@code 0xC1} is {@code DFHBMFSE} whether the data
     * around it is EBCDIC or ASCII, so the byte is written and read raw and no charset is consulted for
     * it.
     *
     * <p>The unset value is {@code LOW-VALUES}, a binary zero: a freshly initialised map area holds it,
     * and CICS leaves the byte alone on input when nothing was modified.
     *
     * <p>Mutable by design, like the map area it models, and therefore not thread safe. An instance
     * belongs to one {@link CardSelectRequest}, which belongs to one request.
     */
    public static final class ScreenFieldMetadata {

        /**
         * Lowest value {@code xxxL COMP PIC S9(4)} can represent: four digits and a sign.
         */
        public static final int LENGTH_ITEM_MIN = -9999;

        /**
         * Highest value {@code xxxL COMP PIC S9(4)} can represent.
         */
        public static final int LENGTH_ITEM_MAX = 9999;

        /**
         * The length item of a field CICS reports as not entered, and the value a freshly initialised
         * map area holds.
         */
        public static final int LENGTH_UNSET = 0;

        /**
         * The length item that asks CICS to place the cursor on this field - the
         * {@code MOVE -1 TO xxxL} convention {@code app/cbl/COCRDSLC.cbl} uses at lines 518, 521 and
         * 523.
         */
        public static final int CURSOR_HERE = -1;

        /**
         * The unset attribute byte: {@code LOW-VALUES}, which is binary zero on every code page because
         * it is by definition the lowest character of the collating sequence.
         */
        public static final byte ATTRIBUTE_UNSET = LOW_VALUE_BYTE;

        /**
         * The {@code xxxL COMP PIC S9(4)} halfword, held as an {@code int} and constrained to the
         * {@code PICTURE} range by {@link #setLength(int)} rather than to the halfword's wider capacity.
         */
        private int length;

        /**
         * The single byte declared as {@code xxxF PICTURE X} and redefined as {@code xxxA PICTURE X} -
         * one field, because a {@code REDEFINES} over one position is one position.
         */
        private byte attribute;

        /**
         * A freshly initialised pair: {@link #getLength()} is {@value #LENGTH_UNSET} and
         * {@link #getAttribute()} is {@code LOW-VALUES}.
         *
         * <p>That is the state a map area holds before CICS or the program touches it, so it is the
         * state every field of a new {@link CardSelectRequest} starts in.
         */
        public ScreenFieldMetadata() {
            this.length = LENGTH_UNSET;
            this.attribute = ATTRIBUTE_UNSET;
        }

        /**
         * A pair carrying explicit values.
         *
         * @param length    the {@code xxxL} halfword, between {@value #LENGTH_ITEM_MIN} and
         *                  {@value #LENGTH_ITEM_MAX}
         * @param attribute the {@code xxxA} attribute byte, for example one of the
         *                  {@code common/BmsAttributes} constants
         * @throws IllegalArgumentException if {@code length} is outside the {@code PIC S9(4)} range
         */
        public ScreenFieldMetadata(int length, byte attribute) {
            setLength(length);
            setAttribute(attribute);
        }

        /**
         * A copy of another pair, so that copying a request cannot leave two requests sharing one
         * mutable metadata holder.
         *
         * @param other the pair to copy
         * @throws NullPointerException if {@code other} is {@code null}
         */
        public ScreenFieldMetadata(ScreenFieldMetadata other) {
            Objects.requireNonNull(other, "A metadata pair is required to copy it");
            this.length = other.length;
            this.attribute = other.attribute;
        }

        /**
         * Returns this pair to its initialised state - {@value #LENGTH_UNSET} and {@code LOW-VALUES}.
         *
         * <p>The per-field half of {@link CardSelectRequest#initializeMapArea()}.
         */
        public void reset() {
            this.length = LENGTH_UNSET;
            this.attribute = ATTRIBUTE_UNSET;
        }

        /**
         * The {@code xxxL COMP PIC S9(4)} halfword.
         *
         * @return the stored value, between {@value #LENGTH_ITEM_MIN} and {@value #LENGTH_ITEM_MAX}
         */
        public int getLength() {
            return length;
        }

        /**
         * Stores the {@code xxxL} halfword.
         *
         * @param length the value to store
         * @throws IllegalArgumentException if {@code length} is outside the range
         *                                  {@code PIC S9(4)} can represent
         */
        public void setLength(int length) {
            if (length < LENGTH_ITEM_MIN || length > LENGTH_ITEM_MAX) {
                throw new IllegalArgumentException("A COCRDSL xxxL item is declared COMP PIC S9(4) "
                        + "and so holds " + LENGTH_ITEM_MIN + " to " + LENGTH_ITEM_MAX + "; " + length
                        + " does not fit, and storing it would put a value in the group image that the "
                        + "copybook cannot represent");
            }
            this.length = length;
        }

        /**
         * Whether the field was reported as not entered - {@link #getLength()} is
         * {@value #LENGTH_UNSET}.
         *
         * <p>Deliberately not written as "is blank": a field can be entered as spaces, in which case
         * CICS reports a non-zero length for a value that is nonetheless blank. The two are different
         * questions and {@code COCRDSLC} asks the second one itself, comparing the data against
         * {@code SPACES} at {@code app/cbl/COCRDSLC.cbl:616} and {@code :623}.
         *
         * @return {@code true} when the length item is {@value #LENGTH_UNSET}
         */
        public boolean isLengthUnset() {
            return length == LENGTH_UNSET;
        }

        /**
         * Whether the cursor is directed at this field - {@link #getLength()} is
         * {@value #CURSOR_HERE}.
         *
         * @return {@code true} when the length item is {@value #CURSOR_HERE}
         */
        public boolean isCursorHere() {
            return length == CURSOR_HERE;
        }

        /**
         * Directs the cursor at this field, the {@code MOVE -1 TO xxxL OF CCRDSLAI} of
         * {@code app/cbl/COCRDSLC.cbl:518}, {@code :521} and {@code :523}.
         */
        public void positionCursorHere() {
            this.length = CURSOR_HERE;
        }

        /**
         * The {@code xxxA PICTURE X} attribute byte.
         *
         * @return the stored byte; {@link #ATTRIBUTE_UNSET} when the program has assigned nothing
         */
        public byte getAttribute() {
            return attribute;
        }

        /**
         * The same byte read through its {@code xxxF PICTURE X} declaration rather than its
         * {@code xxxA} redefinition.
         *
         * <p>{@code 02 FILLER REDEFINES xxxF} / {@code 03 xxxA PICTURE X} is one byte described twice,
         * so this returns exactly what {@link #getAttribute()} returns. Both views are offered because
         * the copybook offers both and a reader looking for {@code xxxF} should find it, but there is
         * only one value and no way for the two to disagree - which is the whole meaning of a
         * {@code REDEFINES} over a single byte.
         *
         * @return the stored byte, identical to {@link #getAttribute()}
         */
        public byte getFlag() {
            return attribute;
        }

        /**
         * Stores the attribute byte, the {@code MOVE DFHBMPRF TO xxxA OF CCRDSLAI} of
         * {@code app/cbl/COCRDSLC.cbl:507-508} and the {@code MOVE DFHBMFSE} of {@code :510-511}.
         *
         * <p>Every one of the 256 possible values is accepted, because a 3270 attribute is a bit
         * pattern and this class does not interpret it. Validating it against a list of known constants
         * would reject a legitimate combination and would duplicate knowledge that belongs in
         * {@code common/BmsAttributes}.
         *
         * @param attribute the byte to store, for example one of the {@code common/BmsAttributes}
         *                  constants
         */
        public void setAttribute(byte attribute) {
            this.attribute = attribute;
        }

        /**
         * Whether the attribute byte is still {@code LOW-VALUES} - that is, whether the program has
         * assigned an attribute to this field at all.
         *
         * @return {@code true} when the attribute byte is {@link #ATTRIBUTE_UNSET}
         */
        public boolean isAttributeUnset() {
            return attribute == ATTRIBUTE_UNSET;
        }

        /**
         * Value equality over both items.
         *
         * @param other the object to compare with
         * @return {@code true} when {@code other} is a pair carrying the same length and attribute
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ScreenFieldMetadata that)) {
                return false;
            }
            return length == that.length && attribute == that.attribute;
        }

        /**
         * A hash consistent with {@link #equals(Object)}.
         *
         * @return the combined hash of both items
         */
        @Override
        public int hashCode() {
            return Objects.hash(length, attribute);
        }

        /**
         * A diagnostic rendering. The attribute byte is shown as two hexadecimal digits rather than as
         * a character, because a 3270 attribute is a bit pattern and is rarely printable -
         * {@code DFHBMFSE} is {@code 0xC1} and {@code LOW-VALUES} is {@code 0x00}.
         *
         * @return for example {@code ScreenFieldMetadata[length=-1, attribute=0xC1]}
         */
        @Override
        public String toString() {
            return "ScreenFieldMetadata[length=" + length + ", attribute=0x"
                    + String.format("%02X", attribute) + "]";
        }
    }

    /**
     * How much of each screen field a diagnostic rendering may disclose.
     *
     * <p>Named per field rather than pattern-matched, because a symbolic map is a closed set taken
     * straight from {@code app/cpy-bms/} and can therefore be enumerated exactly. Anything not named here
     * is screen furniture - a title, a date, a status code, a message - and renders as stored, which is
     * what a parity failure has to be read from.
     *
     * @param field the screen field
     * @return its classification, never {@code null};
     *         {@link SensitiveDiagnostics.Disclosure#REDACTED_VALUE} for {@code null}
     */
    static SensitiveDiagnostics.Disclosure disclosureOf(ScreenField field) {
        // Fails closed. A null field is unreachable from this class's own iteration over
        // ScreenField.values(), but a disclosure decision that throws instead of withholding is a
        // decision that can be reached by accident and answered wrongly, so the safe answer is stated
        // rather than left to the switch. Asserted directly by NoSensitiveDisclosureTest.
        if (field == null) {
            return SensitiveDiagnostics.Disclosure.REDACTED_VALUE;
        }
        return switch (field) {
            case ACCTSID -> SensitiveDiagnostics.Disclosure.IDENTIFIER;
            case CARDSID -> SensitiveDiagnostics.Disclosure.PAN;
            case CRDNAME -> SensitiveDiagnostics.Disclosure.TEXT;
            default -> SensitiveDiagnostics.Disclosure.PLAIN;
        };
    }

    // =================================================================================================
    // WS-THIS-PROGCOMMAREA - app/cbl/COCRDSLC.cbl:200-203.
    // =================================================================================================

    /**
     * {@code WS-THIS-PROGCOMMAREA} - the twelve bytes {@code COCRDSLC} appends to
     * {@code CARDDEMO-COMMAREA} when it returns.
     *
     * <pre>
     * 01 WS-THIS-PROGCOMMAREA.
     *    05 CA-CALL-CONTEXT.
     *       10 CA-FROM-PROGRAM  PIC X(08).
     *       10 CA-FROM-TRANID   PIC X(04).
     * </pre>
     *
     * <p>The program never reads or writes either field - it restores the area at {@code :276-278} and
     * returns it at {@code :398-400}, so the twelve bytes travel out exactly as they travelled in. That
     * pass-through is behaviour, and dropping it shortens the returned area from
     * {@value CardSelectRequest#PASSED_COMMAREA_LENGTH} bytes to
     * {@value NavigationContext#COMMAREA_LENGTH} and changes what the next program in a chain receives.
     *
     * <h4>Why it is declared here, and separately from the communication area</h4>
     * It is a member of this request and of the paired response because it is <em>payload</em>: a
     * carrier that only existed inside the controller could be initialised and returned but never
     * received, so {@code :276-278} would restore twelve spaces on every turn no matter what the caller
     * passed - which is what this migration did before this type moved here.
     *
     * <p>It is <strong>not</strong> folded into {@link NavigationContext}. That type is
     * {@code COCOM01Y}'s {@code 01 CARDDEMO-COMMAREA}, exactly
     * {@value NavigationContext#COMMAREA_LENGTH} bytes, shared verbatim by all seventeen screens;
     * widening it by twelve bytes for one program's private trailer would change the width every other
     * screen sends. The two areas are contiguous on the wire and distinct in declaration, which is
     * precisely how the COBOL has them - and it is the same arrangement the {@code CT01} screen uses for
     * {@code CDEMO-CT01-INFO}.
     *
     * @param caFromProgram {@code CA-FROM-PROGRAM PIC X(08)}, {@code app/cbl/COCRDSLC.cbl:202}
     * @param caFromTranid  {@code CA-FROM-TRANID PIC X(04)}, {@code :203}
     */
    public record ThisProgCommarea(@JsonProperty("caFromProgram") String caFromProgram,
                                   @JsonProperty("caFromTranid") String caFromTranid) {

        /** Declared width of {@code CA-FROM-PROGRAM}: {@code PIC X(08)}. */
        public static final int CA_FROM_PROGRAM_LENGTH = 8;

        /** Declared width of {@code CA-FROM-TRANID}: {@code PIC X(04)}. */
        public static final int CA_FROM_TRANID_LENGTH = 4;

        /** The whole area: {@value #CA_FROM_PROGRAM_LENGTH} + {@value #CA_FROM_TRANID_LENGTH} bytes. */
        public static final int RECORD_LENGTH = CA_FROM_PROGRAM_LENGTH + CA_FROM_TRANID_LENGTH;

        /**
         * Rejects {@code null} in either component. A COBOL alphanumeric field is never absent; the
         * empty state is a run of spaces.
         *
         * <p>Widths are deliberately not enforced, because a group item can legitimately be observed
         * mid-{@code MOVE}. {@link #toImage(FixedWidthCodec)} applies them.
         *
         * @throws NullPointerException if either component is {@code null}
         */
        public ThisProgCommarea {
            Objects.requireNonNull(caFromProgram, "caFromProgram (CA-FROM-PROGRAM) must not be null: "
                    + "COBOL has no absent state, so an empty value is a run of spaces");
            Objects.requireNonNull(caFromTranid, "caFromTranid (CA-FROM-TRANID) must not be null: "
                    + "COBOL has no absent state, so an empty value is a run of spaces");
        }

        /**
         * {@code INITIALIZE WS-THIS-PROGCOMMAREA} - {@code app/cbl/COCRDSLC.cbl:272}: both alphanumeric
         * items to spaces at their declared widths.
         *
         * @return the initialised area; never {@code null}
         */
        public static ThisProgCommarea initialized() {
            return new ThisProgCommarea(spaces(CA_FROM_PROGRAM_LENGTH),
                    spaces(CA_FROM_TRANID_LENGTH));
        }

        /**
         * Renders the area as its twelve-character fixed-width image, applying the {@code PIC X} move to
         * each component.
         *
         * @param codec the codec owning the {@code PIC X} move rule
         * @return exactly {@value #RECORD_LENGTH} characters
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public String toImage(FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A codec is required to apply the PIC X move");
            return codec.movePicX(caFromProgram, CA_FROM_PROGRAM_LENGTH)
                    + codec.movePicX(caFromTranid, CA_FROM_TRANID_LENGTH);
        }

        /**
         * Reads the area back from a twelve-character image, splitting at the copybook's offsets.
         *
         * @param image a {@value #RECORD_LENGTH}-character image
         * @return the decoded area; never {@code null}
         * @throws NullPointerException     if {@code image} is {@code null}
         * @throws IllegalArgumentException if {@code image} is not exactly {@value #RECORD_LENGTH}
         *                                  characters
         */
        public static ThisProgCommarea fromImage(String image) {
            Objects.requireNonNull(image, "An image is required to decode WS-THIS-PROGCOMMAREA");
            if (image.length() != RECORD_LENGTH) {
                throw new IllegalArgumentException("WS-THIS-PROGCOMMAREA is " + RECORD_LENGTH
                        + " characters, but the image supplied is " + image.length());
            }
            return new ThisProgCommarea(image.substring(0, CA_FROM_PROGRAM_LENGTH),
                    image.substring(CA_FROM_PROGRAM_LENGTH));
        }
    }
}
