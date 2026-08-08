package com.vsergeychik.carddemo.card.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Inbound REST payload for {@code GET /api/cards} - the credit card list screen.
 *
 * <p><strong>Provenance.</strong> This type is the 1:1 projection of one BMS screen and nothing
 * else:
 *
 * <ul>
 *   <li>CSD transaction {@code CCLI}, program {@code COCRDLIC} - {@code app/cbl/COCRDLIC.cbl},
 *       1,459 lines.</li>
 *   <li>Symbolic map {@code app/cpy-bms/COCRDLI.CPY}, 560 lines - the authority for every payload
 *       field <em>name</em> and every payload field <em>length</em>.</li>
 *   <li>Mapset {@code app/bms/COCRDLI.bms}, 344 lines - the authority for which fields exist at
 *       all.</li>
 * </ul>
 *
 * <p>There is no design system and no component library in this project, so the BMS layer <em>is</em>
 * the presentation contract and it binds with the same force a design system would: every payload
 * member below traces to a name-labelled {@code DFHMDF} definition, and every declared width traces
 * to an {@code xxxI} {@code PICTURE} clause. That traceability is what gate G9 asserts, and every
 * field's Javadoc names its {@code DFHMDF} label, its {@code xxxI} item and its {@code .CPY} line so
 * a reviewer can check each one against the map directly.
 *
 * <h2>The projection rule</h2>
 *
 * <p>The input group {@code 01 CCRDLIAI.} opens at {@code COCRDLI.CPY:17} with
 * {@code 02 FILLER PIC X(12)} - the {@code TIOAPFX=YES} prefix - and then repeats, per screen field:
 *
 * <pre>
 *   02  xxxL    COMP  PIC  S9(4).      2 bytes, a binary halfword
 *   02  xxxF    PICTURE X.             1 byte
 *   02  FILLER REDEFINES xxxF.         overlay, 0 additional bytes
 *     03 xxxA   PICTURE X.
 *   02  FILLER  PICTURE X(4).          4 bytes
 *   02  xxxI    PIC X(n).              n bytes - THE PAYLOAD FIELD
 * </pre>
 *
 * <p>Stride is therefore {@code 7 + n}. Only the {@code xxxI} items become payload members of
 * <em>this</em> type; the mirrored {@code xxxO} items of {@code 01 CCRDLIAO REDEFINES CCRDLIAI.}
 * ({@code COCRDLI.CPY:289}, also 45 of them) are the response's concern. The {@code xxxL},
 * {@code xxxF} and {@code xxxA} items are validation and highlight metadata, never JSON payload
 * members: {@code xxxL} is the input length CICS reports and {@code xxxA} is the attribute view that
 * {@code common/FieldAttributeSetter} addresses. Both are carried here as deliberately
 * non-serialised {@link FieldMetadata}.
 *
 * <p>{@code app/bms/COCRDLI.bms} declares <strong>72</strong> {@code DFHMDF} entries of which
 * <strong>45 are name-labelled</strong>. The remaining 27 are unnamed - literal {@code INITIAL}
 * screen furniture such as {@code INITIAL='Tran:'} and zero-length attribute stoppers - and get no
 * Java field.
 *
 * <h2>The row-1 asymmetry</h2>
 *
 * <p><strong>Row 1 carries four fields; rows 2 through 7 carry five.</strong> This is the single
 * most likely thing to be silently "tidied" into a uniform 7x5 array, so it is enforced
 * structurally here rather than left to a comment: row 1 is a {@link FirstListRow}, which has no
 * {@code crdStp} accessor at all, and rows 2 to 7 are {@link StopperListRow}, whose {@code crdStp}
 * component sits <em>second</em>.
 *
 * <p>Verified twice against the source:
 *
 * <ol>
 *   <li>{@code 02 CRDSEL1I PIC X(1).} at {@code app/cpy-bms/COCRDLI.CPY:78} is followed
 *       <em>immediately</em> by {@code 02 ACCTNO1L COMP PIC S9(4).} at {@code :79}. There is no
 *       {@code CRDSTP1}. {@code CRDSTP2} through {@code CRDSTP7} all exist, each immediately after
 *       its own {@code CRDSELn} - lines 108, 138, 168, 198, 228 and 258 respectively.</li>
 *   <li>A search for {@code CRDSTP1} returns zero hits in both {@code app/cpy-bms/COCRDLI.CPY} and
 *       {@code app/bms/COCRDLI.bms}.</li>
 * </ol>
 *
 * <p>The mapset shows why. Every row places an attribute stopper at column 14. For rows 2 to 7 that
 * stopper is a <em>named</em> field, {@code CRDSTPn DFHMDF ATTRB=(ASKIP,DRK,FSET) LENGTH=1
 * POS=(line,14)}. For row 1 the same stopper is <em>unnamed</em> -
 * {@code DFHMDF LENGTH=0, POS=(11,14)} at {@code app/bms/COCRDLI.bms:145-146} - so it never reaches
 * the symbolic map.
 *
 * <p>The 45-field count only reconciles with the asymmetry present:
 * <strong>9 header + 4 (row 1) + 6 x 5 (rows 2-7) + 2 footer = 45</strong>. A model that produces 46
 * or 44 has the asymmetry wrong.
 *
 * <h2>Geometry</h2>
 *
 * <p>Data bytes: header {@code 4+40+8+8+40+8+3+11+16 = 138}; row 1 {@code 1+11+16+1 = 29}; rows 2-7
 * {@code (1+1+11+16+1) x 6 = 180}; footer {@code 45+78 = 123}; total {@code 470}. The whole input
 * group image is therefore {@code 12 + 45 x 7 + 470 = }<strong>797</strong> bytes - see
 * {@link #GROUP_LENGTH}, which is expressed as that computed sum so the arithmetic cannot drift.
 *
 * <p>No {@code FixedWidthRecord.RecordLayout} is declared for the group. A layout would have to
 * describe each {@code xxxL} as a two-byte binary halfword, and {@code FixedWidthRecord.PictureKind}
 * deliberately models only zoned {@code DISPLAY} and character categories. Declaring a binary
 * halfword as though it were character or zoned data would put a false statement in the layout, so
 * the geometry is proved by computed constants and asserted by the unit test instead. The one
 * genuinely zoned, genuinely fixed-width table on this screen - the 196-byte row array - does use
 * {@link FixedWidthRecord#occursElementOffsetOneBased} for its offsets.
 *
 * <h2>Page size and the paging cursor</h2>
 *
 * <p>Page size is exactly {@value #PAGE_SIZE} and is behaviour, not configuration.
 * {@code app/cbl/COCRDLIC.cbl:177-178} declares
 * {@code 05 WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7}. {@link #PAGE_SIZE} is a compile-time
 * constant with no configuration path: no {@code @Value}, no {@code application.yml} key, no system
 * property and no request parameter can change it.
 *
 * <p>The cursor travels in the payload, never in server-side state.
 * {@code app/cbl/COCRDLIC.cbl:229-248} declares {@code 01 WS-THIS-PROGCOMMAREA.} as two 27-byte key
 * groups plus four one-byte indicators - <strong>58 bytes</strong> - and {@link PageCursor} is its
 * exact projection. The program transports it by appending it to the CICS communication area behind
 * {@code CARDDEMO-COMMAREA} ({@code COCRDLIC.cbl:327-331} inbound, {@code :610-612} outbound), which
 * is precisely why a stateless REST projection has to carry it in the request body.
 *
 * <p>One verified subtlety, recorded rather than reconciled away. {@code COCRDLIC.cbl:252} declares
 * {@code 05 WS-SCREEN-DATA.} - level 05, which is <em>superior</em> to the level 10 items above it,
 * so by COBOL level-number rules it is a direct subordinate of {@code 01 WS-THIS-PROGCOMMAREA} and
 * not a separate record. {@code LENGTH OF WS-THIS-PROGCOMMAREA} is consequently
 * {@code 58 + 196 = }{@value #PROG_COMMAREA_LENGTH} bytes: the paging cursor proper followed by the
 * 196-byte screen row array. Both are modelled here - {@link PageCursor} and
 * {@link ScreenRowTable} - and {@link #PROG_COMMAREA_LENGTH} records the combined width.
 *
 * <h2>OCCURS is 1-based</h2>
 *
 * <p>Three separate seven-element COBOL tables stand behind this one screen, and all three are
 * 1-based:
 *
 * <ol>
 *   <li>{@code WS-SCREEN-ROWS OCCURS 7 TIMES} - {@code COCRDLIC.cbl:252-260}, 28 bytes per row,
 *       196 in total. Modelled as {@link ScreenRowTable}.</li>
 *   <li>{@code WS-EDIT-SELECT PIC X(1) OCCURS 7 TIMES} - {@code COCRDLIC.cbl:72-82}, whose backing
 *       {@code WS-EDIT-SELECT-FLAGS PIC X(7)} carries {@code VALUE LOW-VALUES}. Modelled as
 *       {@link SelectionFlags}.</li>
 *   <li>{@code WS-EDIT-SELECT-ERRORS OCCURS 7 TIMES} - {@code COCRDLIC.cbl:83-88}, per-row highlight
 *       metadata. Modelled as {@link SelectionErrorFlags} and, like {@code xxxA}, kept off the
 *       wire.</li>
 * </ol>
 *
 * <p>Every 1-based to 0-based conversion in this file goes through a named helper -
 * {@link #javaIndexOf(int)} or {@link FixedWidthRecord#occursElementOffsetOneBased} - and is never
 * written inline. COBOL index 1 and COBOL index 7 are both addressable and both correct through
 * every accessor exposed here.
 *
 * <h2>Fields this screen does not have</h2>
 *
 * <p>There is <strong>no {@code FKEYS} field</strong> anywhere in {@code COCRDLI}. Its sibling card
 * maps do have one - 75 bytes in {@code COCRDSL}, 21 in {@code COCRDUP} - but here the function-key
 * legend is an unnamed {@code DFHMDF ... POS=(24,1) INITIAL='  F3=Exit F7=Backward  F8=Forward'}, so
 * it is screen furniture and gets no field.
 *
 * <p>{@code INFOMSG} and {@code ERRMSG} are <strong>45 and 78</strong> bytes here where both
 * {@code COCRDSL} and {@code COCRDUP} declare 40 and 80, and {@code PAGENO X(3)} exists only on this
 * map, sitting between {@code CURTIME} and {@code ACCTSID}. Those divergences are the contract. They
 * are the reason this folder declares no shared header base class: any such base would collapse them
 * and break gate G9.
 *
 * <h2>Two source facts recorded, not corrected</h2>
 *
 * <ol>
 *   <li>{@code COCRDLI DFHMSD LANG=COBOL, MODE=INOUT, STORAGE=AUTO, TIOAPFX=YES, TYPE=&amp;&amp;SYSPARM}
 *       ({@code app/bms/COCRDLI.bms:20-24}) carries <em>no</em> {@code CTRL=} and <em>no</em>
 *       {@code EXTATT=}. It is {@code CCRDLIA DFHMDI CTRL=(FREEKB),
 *       DSATTS=(COLOR,HILIGHT,PS,VALIDN), MAPATTS=(COLOR,HILIGHT,PS,VALIDN), SIZE=(24,80)}
 *       ({@code :25-28}) that carries them. {@code SIZE=(24,80)} is confirmed. The summary that
 *       places {@code CTRL=(ALARM,FREEKB)} and {@code EXTATT=YES} on the {@code DFHMSD} of all
 *       seventeen mapsets does not hold for this one.</li>
 *   <li>{@code app/cbl/COCRDLIC.cbl:274} reads {@code *COPY COCRDSL.} - commented out - while
 *       {@code COPY COCRDLI.} at {@code :276} is live. {@code COCRDLIC} therefore has no
 *       {@code COCRDSL} data area, even though the card-select screen is one of its navigation
 *       targets. Documented; not "restored".</li>
 * </ol>
 *
 * <h2>Deliberate omissions</h2>
 *
 * <ul>
 *   <li><strong>No filter validation beyond declared width.</strong> Every constraint here is a
 *       {@code @Size(max = ...)} taken from an {@code xxxI} {@code PICTURE} clause. No
 *       {@code @NotBlank}, no {@code @Pattern}, no digit or range check. {@code COCRDLIC} runs its
 *       own filter edits through {@code WS-EDIT-ACCT-FLAG} and {@code WS-EDIT-CARD-FLAG}
 *       ({@code :61-68}), three-state flags whose {@code NOT-OK} / {@code ISVALID} / {@code BLANK}
 *       levels decide which message appears and in what order. Pre-empting them in the framework
 *       would change that, which is a parity violation.</li>
 *   <li><strong>No masking and no redaction.</strong> This payload carries up to seven full 16-digit
 *       card numbers and seven account identifiers in the clear, exactly as the symbolic map does.
 *       Adding masking would be an unrequested behaviour change, and so would adding any new
 *       exposure. Every {@code @JsonIgnore} in this file falls into one of exactly two categories, and
 *       neither withholds a screen field: the three non-map members the COBOL declares but the map
 *       does not carry - {@link #getScreenRowTable()}, {@link #getSelectionErrorFlags()} and
 *       {@link #getFieldMetadata()} - and derived predicates and counts such as {@link #isReenter()}
 *       or {@link #payloadFieldCount()}, which are conclusions about the payload rather than part of
 *       it. <strong>Not one of the forty-five payload members is annotated</strong>, and no value
 *       anywhere is masked, truncated or substituted.</li>
 *   <li><strong>No server-side state.</strong> No {@code HttpSession}, no
 *       {@code @SessionAttributes}, no cache, no static mutable field, no {@code ThreadLocal}.
 *       Nothing here outlives the request; carrying the whole cursor in the body is what makes
 *       stateless paging possible.</li>
 *   <li><strong>No persistence and no arithmetic.</strong> There is no JPA annotation and no DDL,
 *       and no field on this screen is scaled or monetary, so there is no {@code BigDecimal} and no
 *       rounding mode anywhere in this file.</li>
 * </ul>
 *
 * <p>Instances are mutable and constructible with no Spring context, so controller tests,
 * {@code MockMvc} tests and the {@code COCRDLIC} parity cases can all build one directly and assert
 * the first and the last row. This class is not thread-safe; a request-scoped payload has no need to
 * be, and making it so would hide the sharing bugs that defensive copying is here to prevent.
 */
public class CardListRequest {

    // =================================================================================================
    // DECLARED WIDTHS - one named constant per name-labelled DFHMDF field, in symbolic-map source
    // order, each taken from its xxxI PICTURE clause in app/cpy-bms/COCRDLI.CPY. These are the only
    // authority for a payload field's length, and they are named rather than inlined so that a width
    // can be checked against the map in one place (practice B8).
    // =================================================================================================

    /** {@code TRNNAME} - {@code 02 TRNNAMEI PIC X(4).}, {@code COCRDLI.CPY:24}. */
    public static final int TRNNAME_LENGTH = 4;

    /** {@code TITLE01} - {@code 02 TITLE01I PIC X(40).}, {@code COCRDLI.CPY:30}. */
    public static final int TITLE01_LENGTH = 40;

    /** {@code CURDATE} - {@code 02 CURDATEI PIC X(8).}, {@code COCRDLI.CPY:36}. */
    public static final int CURDATE_LENGTH = 8;

    /** {@code PGMNAME} - {@code 02 PGMNAMEI PIC X(8).}, {@code COCRDLI.CPY:42}. */
    public static final int PGMNAME_LENGTH = 8;

    /** {@code TITLE02} - {@code 02 TITLE02I PIC X(40).}, {@code COCRDLI.CPY:48}. */
    public static final int TITLE02_LENGTH = 40;

    /** {@code CURTIME} - {@code 02 CURTIMEI PIC X(8).}, {@code COCRDLI.CPY:54}. */
    public static final int CURTIME_LENGTH = 8;

    /**
     * {@code PAGENO} - {@code 02 PAGENOI PIC X(3).}, {@code COCRDLI.CPY:60}. Unique to this mapset,
     * and positioned between {@code CURTIME} and {@code ACCTSID}.
     */
    public static final int PAGENO_LENGTH = 3;

    /** {@code ACCTSID} - {@code 02 ACCTSIDI PIC X(11).}, {@code COCRDLI.CPY:66}. */
    public static final int ACCTSID_LENGTH = 11;

    /** {@code CARDSID} - {@code 02 CARDSIDI PIC X(16).}, {@code COCRDLI.CPY:72}. */
    public static final int CARDSID_LENGTH = 16;

    /**
     * {@code CRDSELn} - {@code 02 CRDSELnI PIC X(1).}; row 1 at {@code COCRDLI.CPY:78}, rows 2-7 at
     * lines 102, 132, 162, 192, 222 and 252.
     */
    public static final int CRDSEL_LENGTH = 1;

    /**
     * {@code CRDSTPn} - {@code 02 CRDSTPnI PIC X(1).}, the row attribute stopper at column 14.
     * Present for rows 2-7 only, at {@code COCRDLI.CPY} lines 108, 138, 168, 198, 228 and 258.
     * <strong>There is no {@code CRDSTP1}.</strong>
     */
    public static final int CRDSTP_LENGTH = 1;

    /**
     * {@code ACCTNOn} - {@code 02 ACCTNOnI PIC X(11).}; row 1 at {@code COCRDLI.CPY:84}, rows 2-7 at
     * lines 114, 144, 174, 204, 234 and 264.
     */
    public static final int ACCTNO_LENGTH = 11;

    /**
     * {@code CRDNUMn} - {@code 02 CRDNUMnI PIC X(16).}; row 1 at {@code COCRDLI.CPY:90}, rows 2-7 at
     * lines 120, 150, 180, 210, 240 and 270.
     */
    public static final int CRDNUM_LENGTH = 16;

    /**
     * {@code CRDSTSn} - {@code 02 CRDSTSnI PIC X(1).}; row 1 at {@code COCRDLI.CPY:96}, rows 2-7 at
     * lines 126, 156, 186, 216, 246 and 276.
     */
    public static final int CRDSTS_LENGTH = 1;

    /**
     * {@code INFOMSG} - {@code 02 INFOMSGI PIC X(45).}, {@code COCRDLI.CPY:282}. Forty-five bytes
     * here; {@code COCRDSL} and {@code COCRDUP} both declare forty.
     */
    public static final int INFOMSG_LENGTH = 45;

    /**
     * {@code ERRMSG} - {@code 02 ERRMSGI PIC X(78).}, {@code COCRDLI.CPY:288}. Seventy-eight bytes
     * here; {@code COCRDSL} and {@code COCRDUP} both declare eighty.
     */
    public static final int ERRMSG_LENGTH = 78;

    // =================================================================================================
    // PAGE SIZE - behaviour, not configuration (gate G39). Declared before the geometry constants
    // because they are expressed in terms of it: the screen holds exactly one page of rows, so the row
    // band's width and the field count both derive from this one value.
    // =================================================================================================

    /**
     * Rows the card list shows per page: exactly seven.
     *
     * <p>{@code app/cbl/COCRDLIC.cbl:177-178} declares
     * {@code 05 WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7}, and the mapset places the seven detail
     * rows on screen lines 11 through 17. The value is behaviour rather than a tuning knob: changing
     * it would change which records a given page returns and would therefore break parity. It is a
     * compile-time constant with no configuration path - no {@code @Value}, no
     * {@code application.yml} key, no system property and no request parameter reaches it.
     *
     * <p>The sibling screens use different, equally fixed page sizes: ten for the transaction list
     * ({@code COTRN00C}) and ten for the user list ({@code COUSR00C}). Seven is specific to this one.
     */
    public static final int PAGE_SIZE = 7;

    /**
     * Detail rows the screen declares, which is one page: {@value #PAGE_SIZE}. Named separately from
     * {@link #PAGE_SIZE} because it is the {@code OCCURS 7 TIMES} count of the three tables behind the
     * screen ({@code COCRDLIC.cbl:76}, {@code :86} and {@code :255}) rather than a paging policy, even
     * though the two values are necessarily equal.
     */
    public static final int SCREEN_ROW_COUNT = PAGE_SIZE;

    /**
     * The lowest valid COBOL subscript for any of the three seven-element tables. COBOL subscripts
     * start at 1; there is no index 0.
     */
    public static final int FIRST_ROW_NUMBER = 1;

    /** The highest valid COBOL subscript for any of the three seven-element tables. */
    public static final int LAST_ROW_NUMBER = SCREEN_ROW_COUNT;

    // =================================================================================================
    // GROUP GEOMETRY - the shape of 01 CCRDLIAI. Every composite below is written as the sum of its
    // parts rather than as a literal, so a mistyped width fails the arithmetic instead of quietly
    // agreeing with a hard-coded total.
    // =================================================================================================

    /**
     * The {@code 02 FILLER PIC X(12).} that opens {@code 01 CCRDLIAI.} at
     * {@code app/cpy-bms/COCRDLI.CPY:18} - the {@code TIOAPFX=YES} terminal input/output area prefix.
     */
    public static final int TIOAPFX_LENGTH = 12;

    /**
     * The {@code 02 xxxL COMP PIC S9(4).} length item: a two-byte binary halfword carrying the input
     * length CICS reports for the field. Metadata, never a payload member.
     */
    public static final int LENGTH_ITEM_LENGTH = 2;

    /** The {@code 02 xxxF PICTURE X.} flag item: one byte. Metadata, never a payload member. */
    public static final int FLAG_ITEM_LENGTH = 1;

    /**
     * The {@code 03 xxxA PICTURE X.} attribute item. It sits inside
     * {@code 02 FILLER REDEFINES xxxF.} and so consumes <strong>no</strong> additional storage - it
     * is an overlay of the flag byte, which is why the per-field stride is 7 and not 8.
     */
    public static final int ATTRIBUTE_ITEM_LENGTH = 0;

    /** The {@code 02 FILLER PICTURE X(4).} that precedes each {@code xxxI} item. */
    public static final int RESERVED_FILLER_LENGTH = 4;

    /**
     * Bytes each field costs on top of its own declared width: {@code 2 + 1 + 0 + 4 = 7}. The full
     * per-field stride in the group image is {@code FIELD_OVERHEAD_LENGTH + n}.
     */
    public static final int FIELD_OVERHEAD_LENGTH =
            LENGTH_ITEM_LENGTH + FLAG_ITEM_LENGTH + ATTRIBUTE_ITEM_LENGTH + RESERVED_FILLER_LENGTH;

    /** Payload members contributed by the header band: {@code TRNNAME} through {@code CARDSID}. */
    public static final int HEADER_FIELD_COUNT = 9;

    /**
     * Payload members contributed by row 1: {@code CRDSEL1}, {@code ACCTNO1}, {@code CRDNUM1} and
     * {@code CRDSTS1}. <strong>Four</strong> - there is no {@code CRDSTP1}.
     */
    public static final int FIRST_ROW_FIELD_COUNT = 4;

    /**
     * Payload members contributed by each of rows 2 through 7: {@code CRDSELn}, {@code CRDSTPn},
     * {@code ACCTNOn}, {@code CRDNUMn} and {@code CRDSTSn}. <strong>Five</strong>.
     */
    public static final int STOPPER_ROW_FIELD_COUNT = 5;

    /** Payload members contributed by the footer band: {@code INFOMSG} and {@code ERRMSG}. */
    public static final int FOOTER_FIELD_COUNT = 2;

    /**
     * The total payload member count, {@code 9 + 4 + 6 x 5 + 2 = }<strong>45</strong>, matching the
     * 45 {@code xxxI} items in {@code app/cpy-bms/COCRDLI.CPY} and the 45 name-labelled
     * {@code DFHMDF} entries of the 72 in {@code app/bms/COCRDLI.bms}. The count only reconciles with
     * the row-1 asymmetry present.
     */
    public static final int FIELD_COUNT = HEADER_FIELD_COUNT
            + FIRST_ROW_FIELD_COUNT
            + STOPPER_ROW_FIELD_COUNT * (SCREEN_ROW_COUNT - 1)
            + FOOTER_FIELD_COUNT;

    /** Header data bytes: {@code 4 + 40 + 8 + 8 + 40 + 8 + 3 + 11 + 16 = 138}. */
    public static final int HEADER_DATA_LENGTH = TRNNAME_LENGTH
            + TITLE01_LENGTH
            + CURDATE_LENGTH
            + PGMNAME_LENGTH
            + TITLE02_LENGTH
            + CURTIME_LENGTH
            + PAGENO_LENGTH
            + ACCTSID_LENGTH
            + CARDSID_LENGTH;

    /** Row 1 data bytes: {@code 1 + 11 + 16 + 1 = 29}. No {@code CRDSTP} term. */
    public static final int FIRST_ROW_DATA_LENGTH =
            CRDSEL_LENGTH + ACCTNO_LENGTH + CRDNUM_LENGTH + CRDSTS_LENGTH;

    /** Data bytes for one of rows 2 through 7: {@code 1 + 1 + 11 + 16 + 1 = 30}. */
    public static final int STOPPER_ROW_DATA_LENGTH =
            CRDSEL_LENGTH + CRDSTP_LENGTH + ACCTNO_LENGTH + CRDNUM_LENGTH + CRDSTS_LENGTH;

    /** Data bytes for rows 2 through 7 together: {@code 30 x 6 = 180}. */
    public static final int STOPPER_ROWS_DATA_LENGTH =
            STOPPER_ROW_DATA_LENGTH * (SCREEN_ROW_COUNT - 1);

    /** Footer data bytes: {@code 45 + 78 = 123}. */
    public static final int FOOTER_DATA_LENGTH = INFOMSG_LENGTH + ERRMSG_LENGTH;

    /** All payload data bytes: {@code 138 + 29 + 180 + 123 = 470}. */
    public static final int PAYLOAD_DATA_LENGTH = HEADER_DATA_LENGTH
            + FIRST_ROW_DATA_LENGTH
            + STOPPER_ROWS_DATA_LENGTH
            + FOOTER_DATA_LENGTH;

    /**
     * The whole {@code 01 CCRDLIAI.} group image: {@code 12 + 45 x 7 + 470 = }<strong>797</strong>
     * bytes. {@code 01 CCRDLIAO REDEFINES CCRDLIAI.} occupies the same 797 bytes, which is why its
     * per-field overhead - {@code FILLER X(3)} plus four one-byte attribute items - also sums to 7.
     */
    public static final int GROUP_LENGTH =
            TIOAPFX_LENGTH + FIELD_COUNT * FIELD_OVERHEAD_LENGTH + PAYLOAD_DATA_LENGTH;

    // =================================================================================================
    // CURSOR AND TABLE GEOMETRY - 01 WS-THIS-PROGCOMMAREA, app/cbl/COCRDLIC.cbl:229-260.
    // =================================================================================================

    /** {@code 15 WS-CA-LAST-CARD-NUM PIC X(16).} / {@code 15 WS-CA-FIRST-CARD-NUM PIC X(16).}. */
    public static final int CURSOR_CARD_NUM_LENGTH = 16;

    /**
     * {@code 15 WS-CA-LAST-CARD-ACCT-ID PIC 9(11).} /
     * {@code 15 WS-CA-FIRST-CARD-ACCT-ID PIC 9(11).}. Scale-free unsigned zoned digits, so the Java
     * carrier is an integral type and never a floating-point one.
     */
    public static final int CURSOR_ACCT_ID_LENGTH = 11;

    /**
     * One key group - {@code 10 WS-CA-LAST-CARDKEY.} at {@code COCRDLIC.cbl:230} and
     * {@code 10 WS-CA-FIRST-CARDKEY.} at {@code :233} - is {@code 16 + 11 = 27} bytes. The groups are
     * modelled as groups because {@code COCRDLIC.cbl:1268} moves one onto the other wholesale:
     * {@code MOVE WS-CA-FIRST-CARDKEY TO WS-CA-LAST-CARDKEY}.
     */
    public static final int CARD_KEY_LENGTH = CURSOR_CARD_NUM_LENGTH + CURSOR_ACCT_ID_LENGTH;

    /** {@code 10 WS-CA-SCREEN-NUM PIC 9(1).}, {@code COCRDLIC.cbl:237}. */
    public static final int SCREEN_NUM_LENGTH = 1;

    /** {@code 10 WS-CA-LAST-PAGE-DISPLAYED PIC 9(1).}, {@code COCRDLIC.cbl:239}. */
    public static final int LAST_PAGE_DISPLAYED_LENGTH = 1;

    /** {@code 10 WS-CA-NEXT-PAGE-IND PIC X(1).}, {@code COCRDLIC.cbl:242}. */
    public static final int NEXT_PAGE_IND_LENGTH = 1;

    /** {@code 10 WS-RETURN-FLAG PIC X(1).}, {@code COCRDLIC.cbl:246}. */
    public static final int RETURN_FLAG_LENGTH = 1;

    /**
     * The paging cursor proper: {@code 27 + 27 + 1 + 1 + 1 + 1 = }<strong>58</strong> bytes, spanning
     * {@code app/cbl/COCRDLIC.cbl:229-248}.
     */
    public static final int CURSOR_LENGTH = CARD_KEY_LENGTH
            + CARD_KEY_LENGTH
            + SCREEN_NUM_LENGTH
            + LAST_PAGE_DISPLAYED_LENGTH
            + NEXT_PAGE_IND_LENGTH
            + RETURN_FLAG_LENGTH;

    /** {@code 30 WS-ROW-ACCTNO PIC X(11).}, {@code COCRDLIC.cbl:258}. */
    public static final int SCREEN_ROW_ACCTNO_LENGTH = 11;

    /** {@code 30 WS-ROW-CARD-NUM PIC X(16).}, {@code COCRDLIC.cbl:259}. */
    public static final int SCREEN_ROW_CARD_NUM_LENGTH = 16;

    /** {@code 30 WS-ROW-CARD-STATUS PIC X(1).}, {@code COCRDLIC.cbl:260}. */
    public static final int SCREEN_ROW_CARD_STATUS_LENGTH = 1;

    /**
     * One element of {@code 15 WS-SCREEN-ROWS OCCURS 7 TIMES.}: {@code 11 + 16 + 1 = 28} bytes. The
     * source states the arithmetic itself in the comment at {@code COCRDLIC.cbl:250} -
     * "28 CHARS X 7 ROWS = 196".
     */
    public static final int SCREEN_ROW_LENGTH = SCREEN_ROW_ACCTNO_LENGTH
            + SCREEN_ROW_CARD_NUM_LENGTH
            + SCREEN_ROW_CARD_STATUS_LENGTH;

    /**
     * {@code 10 WS-ALL-ROWS PIC X(196).}, {@code COCRDLIC.cbl:253}: {@code 28 x 7 = }<strong>196</strong>
     * bytes, redefined at {@code :254-260} as the seven-element row table.
     */
    public static final int SCREEN_DATA_LENGTH = SCREEN_ROW_LENGTH * SCREEN_ROW_COUNT;

    /**
     * The full {@code 01 WS-THIS-PROGCOMMAREA} group image: {@code 58 + 196 = }<strong>254</strong>
     * bytes.
     *
     * <p>{@code app/cbl/COCRDLIC.cbl:252} declares {@code 05 WS-SCREEN-DATA.} at level 05, which is
     * superior to the level 10 items declared above it, so by COBOL level-number rules it is a direct
     * subordinate of {@code 01 WS-THIS-PROGCOMMAREA} rather than a record of its own. Both
     * {@code MOVE DFHCOMMAREA(LENGTH OF CARDDEMO-COMMAREA + 1: LENGTH OF WS-THIS-PROGCOMMAREA)} at
     * {@code :329-331} and the mirroring outbound move at {@code :610-612} therefore transport 254
     * bytes, not 58.
     *
     * <p>Recorded here because the difference is real and load-bearing: the cursor is what the client
     * must round-trip, and the 196-byte row table is re-derived from the file on every pass -
     * {@code MOVE LOW-VALUES TO WS-ALL-ROWS} at {@code :1124} and {@code :1266} clears it before each
     * browse.
     */
    public static final int PROG_COMMAREA_LENGTH = CURSOR_LENGTH + SCREEN_DATA_LENGTH;

    /**
     * {@code WS-EDIT-SELECT-FLAGS PIC X(7)} and {@code WS-EDIT-SELECT-ERROR-FLAGS PIC X(7)} -
     * {@code COCRDLIC.cbl:72} and {@code :83}. Seven bytes, one per screen row.
     */
    public static final int SELECT_FLAGS_LENGTH = SCREEN_ROW_COUNT;

    // =================================================================================================
    // COBOL FIGURATIVE CONSTANTS AND 88-LEVEL LITERALS.
    //
    // SPACES, LOW-VALUES and Java null are three different things and nothing in this file conflates
    // them. LOW-VALUES is binary zero - U+0000 - whichever code page is in force, and it is the
    // declared "off" state of WS-EDIT-SELECT-FLAGS, WS-CA-NEXT-PAGE-IND and WS-RETURN-FLAG.
    // =================================================================================================

    /** The one-character rendering of {@code LOW-VALUES}: binary zero. */
    public static final char LOW_VALUE = '\u0000';

    /** A single space, the other value {@code 88 SELECT-BLANK} accepts. */
    public static final char SPACE = ' ';

    /** {@code 88 VIEW-REQUESTED-ON VALUE 'S'.}, {@code app/cbl/COCRDLIC.cbl:78}. */
    public static final char SELECT_VIEW = 'S';

    /** {@code 88 UPDATE-REQUESTED-ON VALUE 'U'.}, {@code app/cbl/COCRDLIC.cbl:79}. */
    public static final char SELECT_UPDATE = 'U';

    /** {@code 88 WS-ROW-SELECT-ERROR VALUE '1'.}, {@code app/cbl/COCRDLIC.cbl:88}. */
    public static final char ROW_SELECT_ERROR = '1';

    /** {@code 88 CA-FIRST-PAGE VALUE 1.}, {@code app/cbl/COCRDLIC.cbl:238}. */
    public static final int FIRST_PAGE_SCREEN_NUM = 1;

    /** {@code 88 CA-LAST-PAGE-SHOWN VALUE 0.}, {@code app/cbl/COCRDLIC.cbl:240}. */
    public static final int LAST_PAGE_SHOWN = 0;

    /** {@code 88 CA-LAST-PAGE-NOT-SHOWN VALUE 9.}, {@code app/cbl/COCRDLIC.cbl:241}. */
    public static final int LAST_PAGE_NOT_SHOWN = 9;

    /** {@code 88 CA-NEXT-PAGE-EXISTS VALUE 'Y'.}, {@code app/cbl/COCRDLIC.cbl:244}. */
    public static final char NEXT_PAGE_EXISTS = 'Y';

    /** {@code 88 WS-RETURN-FLAG-ON VALUE '1'.}, {@code app/cbl/COCRDLIC.cbl:248}. */
    public static final char RETURN_FLAG_ON = '1';

    /**
     * The value {@link SelectionFlags#selectedRowNumber()} reports when no row carries a
     * {@code SELECT-OK} action. {@code 10 I-SELECTED PIC S9(4) COMP VALUE 0.} at
     * {@code app/cbl/COCRDLIC.cbl:92-93}, reset by {@code MOVE ZERO TO I-SELECTED} at {@code :1097},
     * and {@code 88 DETAIL-WAS-REQUESTED VALUES 1 THRU 7.} at {@code :94} deliberately excludes it.
     */
    public static final int NO_ROW_SELECTED = 0;

    /** Transaction identifier this screen runs under - {@code LIT-THISTRANID}, {@code COCRDLIC.cbl:181-182}. */
    public static final String TRANSACTION_ID = "CCLI";

    /** Program this screen belongs to - {@code LIT-THISPGM}, {@code COCRDLIC.cbl:179-180}. */
    public static final String PROGRAM_NAME = "COCRDLIC";

    /** Mapset this screen belongs to - {@code LIT-THISMAPSET}, {@code COCRDLIC.cbl:183-184}. */
    public static final String MAPSET_NAME = "COCRDLI";

    /** Map this screen belongs to - {@code LIT-THISMAP}, {@code COCRDLIC.cbl:185-186}. */
    public static final String MAP_NAME = "CCRDLIA";

    // =================================================================================================
    // NESTED TYPES
    //
    // Everything the screen needs is declared here rather than in sibling files: the row element, the
    // paging cursor, the row table and the three metadata carriers. Records are used for the value
    // types because they are immutable and their component list IS the field list, which is exactly the
    // property the row-1 asymmetry needs. The outer type stays a normal class because a request payload
    // is populated field by field.
    // =================================================================================================

    /**
     * One detail row of the card list.
     *
     * <p>The hierarchy is sealed and has exactly two permitted implementations because the screen has
     * exactly two row shapes. Row 1 is a {@link FirstListRow} with four members; rows 2 through 7 are
     * {@link StopperListRow} with five. {@code FirstListRow} has no {@code crdStp} accessor of any
     * kind - not an empty one, not a {@code null}-returning one, none - so row 1's absent
     * {@code CRDSTP1} is a fact about the type system here and not a convention someone has to
     * remember.
     *
     * <p>To read the stopper without knowing which shape you hold, use
     * {@link CardListRequest#stopperOf(int)}, which pattern-matches over the two permitted types and
     * returns an empty {@link Optional} for row 1.
     *
     * @see CardListRequest#FIRST_ROW_FIELD_COUNT
     * @see CardListRequest#STOPPER_ROW_FIELD_COUNT
     */
    public sealed interface ListRow permits FirstListRow, StopperListRow {

        /**
         * {@code CRDSELn} - the row action code the operator typed. {@code PIC X(1)}.
         *
         * @return the selection character, space-padded to one byte; never {@code null}
         */
        String crdSel();

        /**
         * {@code ACCTNOn} - the account number displayed on the row. {@code PIC X(11)}.
         *
         * @return the account number, space-padded to eleven bytes; never {@code null}
         */
        String acctNo();

        /**
         * {@code CRDNUMn} - the card number displayed on the row. {@code PIC X(16)}.
         *
         * @return the card number, space-padded to sixteen bytes; never {@code null}
         */
        String crdNum();

        /**
         * {@code CRDSTSn} - the active status displayed on the row. {@code PIC X(1)}.
         *
         * @return the status character, space-padded to one byte; never {@code null}
         */
        String crdSts();

        /**
         * How many payload members this row shape contributes: four for row 1, five for rows 2
         * through 7. Exposed so the asymmetry is assertable rather than merely documented.
         *
         * @return {@value CardListRequest#FIRST_ROW_FIELD_COUNT} or
         *         {@value CardListRequest#STOPPER_ROW_FIELD_COUNT}
         */
        @JsonIgnore
        int memberCount();

        /**
         * How many data bytes this row shape occupies in the {@code 01 CCRDLIAI.} group image: 29 for
         * row 1, 30 for rows 2 through 7.
         *
         * @return {@value CardListRequest#FIRST_ROW_DATA_LENGTH} or
         *         {@value CardListRequest#STOPPER_ROW_DATA_LENGTH}
         */
        @JsonIgnore
        int dataLength();

        /**
         * Whether this row declares the named attribute stopper {@code CRDSTPn}.
         *
         * @return {@code false} for row 1 only
         */
        @JsonIgnore
        boolean hasStopper();

        /**
         * Re-renders every member at its declared width using the codec's {@code PIC X} move, so a
         * value that arrived short or long is padded or truncated exactly as a COBOL alphanumeric
         * {@code MOVE} would do it - on the right, with spaces.
         *
         * @param codec the fixed-width codec whose charset governs the pad byte; never {@code null}
         * @return a row of the same shape with every member exactly its declared width
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        ListRow normalised(FixedWidthCodec codec);

        /**
         * Reads a row from its JSON members, choosing the shape by the presence of {@code crdStp}.
         *
         * <p>This is deliberately explicit rather than relying on a type discriminator or on Jackson's
         * subtype deduction. A discriminator would add a wire member with no name-labelled
         * {@code DFHMDF} behind it, breaking the forty-five-field contract; deduction would have to
         * choose between two shapes whose property sets are a subset and a superset of one another.
         * Presence of {@code crdStp} <em>is</em> the discriminator the screen itself uses - it is exactly
         * what distinguishes rows 2 through 7 from row 1 - so the rule is stated here in one place where
         * a reviewer can check it.
         *
         * <p>An absent or {@code null} member reads as an empty value, which
         * {@link #normalised(FixedWidthCodec)} then space-pads to the declared width. That is the same
         * outcome CICS produces for a field the operator never touched, and it is why the records
         * themselves still refuse {@code null}: absence is resolved here, at the boundary, rather than
         * being carried inwards.
         *
         * @param members the row's JSON members; never {@code null}
         * @return a {@link StopperListRow} when {@code crdStp} is present, otherwise a
         *         {@link FirstListRow}
         * @throws NullPointerException if {@code members} is {@code null}
         */
        @JsonCreator
        static ListRow fromJsonMembers(Map<String, String> members) {
            Objects.requireNonNull(members, "A detail row's JSON members are required");
            String crdSel = memberOrEmpty(members, "crdSel");
            String acctNo = memberOrEmpty(members, "acctNo");
            String crdNum = memberOrEmpty(members, "crdNum");
            String crdSts = memberOrEmpty(members, "crdSts");
            if (members.containsKey("crdStp")) {
                return new StopperListRow(crdSel, memberOrEmpty(members, "crdStp"), acctNo, crdNum,
                        crdSts);
            }
            return new FirstListRow(crdSel, acctNo, crdNum, crdSts);
        }

        /**
         * One JSON member, with both an absent key and an explicit {@code null} value read as empty.
         *
         * @param members the row's JSON members
         * @param name    the member name
         * @return the value, or the empty string when absent or {@code null}
         */
        private static String memberOrEmpty(Map<String, String> members, String name) {
            String value = members.get(name);
            return value == null ? "" : value;
        }
    }

    /**
     * Row 1 of the card list: {@code CRDSEL1}, {@code ACCTNO1}, {@code CRDNUM1}, {@code CRDSTS1}.
     *
     * <p><strong>Four components, and deliberately no {@code crdStp}.</strong>
     * {@code app/cpy-bms/COCRDLI.CPY:78} declares {@code 02 CRDSEL1I PIC X(1).} and line 79 goes
     * straight on to {@code 02 ACCTNO1L COMP PIC S9(4).}; the mapset's row-1 attribute stopper at
     * {@code POS=(11,14)} is unnamed ({@code app/bms/COCRDLI.bms:145-146}) and so never reaches the
     * symbolic map. Serialised, this row therefore has four JSON members where every other row has
     * five - which is what the screen actually sends.
     *
     * @param crdSel {@code CRDSEL1} - {@code 02 CRDSEL1I PIC X(1).}, {@code COCRDLI.CPY:78}
     * @param acctNo {@code ACCTNO1} - {@code 02 ACCTNO1I PIC X(11).}, {@code COCRDLI.CPY:84}
     * @param crdNum {@code CRDNUM1} - {@code 02 CRDNUM1I PIC X(16).}, {@code COCRDLI.CPY:90}
     * @param crdSts {@code CRDSTS1} - {@code 02 CRDSTS1I PIC X(1).}, {@code COCRDLI.CPY:96}
     */
    public record FirstListRow(@Size(max = CRDSEL_LENGTH) String crdSel,
                               @Size(max = ACCTNO_LENGTH) String acctNo,
                               @Size(max = CRDNUM_LENGTH) String crdNum,
                               @Size(max = CRDSTS_LENGTH) String crdSts) implements ListRow {

        /**
         * Rejects {@code null} in any member. A screen field is always present in the transmitted
         * map - it may hold spaces or {@code LOW-VALUES}, but it is never absent - so {@code null}
         * here is a transcription error rather than an empty field, and letting it through would turn
         * a missing value into a {@link NullPointerException} much further downstream.
         *
         * @throws NullPointerException if any member is {@code null}
         */
        public FirstListRow {
            Objects.requireNonNull(crdSel, "CRDSEL1 is required; a blank row field holds spaces or "
                    + "LOW-VALUES, never null");
            Objects.requireNonNull(acctNo, "ACCTNO1 is required; a blank row field holds spaces or "
                    + "LOW-VALUES, never null");
            Objects.requireNonNull(crdNum, "CRDNUM1 is required; a blank row field holds spaces or "
                    + "LOW-VALUES, never null");
            Objects.requireNonNull(crdSts, "CRDSTS1 is required; a blank row field holds spaces or "
                    + "LOW-VALUES, never null");
        }

        /**
         * A row 1 whose every member is space-filled to its declared width - the state the map is in
         * before any card is written into it.
         *
         * @return the blank row, never {@code null}
         */
        public static FirstListRow blank() {
            return new FirstListRow(spaces(CRDSEL_LENGTH),
                    spaces(ACCTNO_LENGTH),
                    spaces(CRDNUM_LENGTH),
                    spaces(CRDSTS_LENGTH));
        }

        @Override
        @JsonIgnore
        public int memberCount() {
            return FIRST_ROW_FIELD_COUNT;
        }

        @Override
        @JsonIgnore
        public int dataLength() {
            return FIRST_ROW_DATA_LENGTH;
        }

        /**
         * {@inheritDoc}
         *
         * @return always {@code false}: row 1 has no {@code CRDSTP1}
         */
        @Override
        @JsonIgnore
        public boolean hasStopper() {
            return false;
        }

        @Override
        public FirstListRow normalised(FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A FixedWidthCodec is required to normalise a row; the "
                    + "charset decides the pad byte and must never be the platform default");
            return new FirstListRow(codec.movePicX(crdSel, CRDSEL_LENGTH),
                    codec.movePicX(acctNo, ACCTNO_LENGTH),
                    codec.movePicX(crdNum, CRDNUM_LENGTH),
                    codec.movePicX(crdSts, CRDSTS_LENGTH));
        }
    }

    /**
     * Rows 2 through 7 of the card list: {@code CRDSELn}, {@code CRDSTPn}, {@code ACCTNOn},
     * {@code CRDNUMn}, {@code CRDSTSn}.
     *
     * <p><strong>Five components, with {@code crdStp} second - not last.</strong> The declaration
     * order in the symbolic map is {@code CRDSELn} then {@code CRDSTPn} then {@code ACCTNOn}, verified
     * at {@code app/cpy-bms/COCRDLI.CPY} lines 102/108/114 for row 2 and repeating every 30 lines
     * through row 7 at 252/258/264. Every byte offset in the group image depends on that position, so
     * placing {@code crdStp} last would be as wrong as omitting it.
     *
     * <p>{@code CRDSTPn} is the row's attribute stopper at screen column 14, declared
     * {@code DFHMDF ATTRB=(ASKIP,DRK,FSET) LENGTH=1 POS=(line,14)}. It is a real transmitted field
     * because it is name-labelled, which is exactly what distinguishes it from row 1's unnamed
     * equivalent.
     *
     * @param crdSel {@code CRDSELn} - {@code 02 CRDSELnI PIC X(1).}
     * @param crdStp {@code CRDSTPn} - {@code 02 CRDSTPnI PIC X(1).}, second in the row
     * @param acctNo {@code ACCTNOn} - {@code 02 ACCTNOnI PIC X(11).}
     * @param crdNum {@code CRDNUMn} - {@code 02 CRDNUMnI PIC X(16).}
     * @param crdSts {@code CRDSTSn} - {@code 02 CRDSTSnI PIC X(1).}
     */
    public record StopperListRow(@Size(max = CRDSEL_LENGTH) String crdSel,
                                 @Size(max = CRDSTP_LENGTH) String crdStp,
                                 @Size(max = ACCTNO_LENGTH) String acctNo,
                                 @Size(max = CRDNUM_LENGTH) String crdNum,
                                 @Size(max = CRDSTS_LENGTH) String crdSts) implements ListRow {

        /**
         * Rejects {@code null} in any member, for the same reason {@link FirstListRow} does.
         *
         * @throws NullPointerException if any member is {@code null}
         */
        public StopperListRow {
            Objects.requireNonNull(crdSel, "CRDSELn is required; a blank row field holds spaces or "
                    + "LOW-VALUES, never null");
            Objects.requireNonNull(crdStp, "CRDSTPn is required for rows 2 through 7; row 1 has no "
                    + "CRDSTP1 and is modelled by FirstListRow instead");
            Objects.requireNonNull(acctNo, "ACCTNOn is required; a blank row field holds spaces or "
                    + "LOW-VALUES, never null");
            Objects.requireNonNull(crdNum, "CRDNUMn is required; a blank row field holds spaces or "
                    + "LOW-VALUES, never null");
            Objects.requireNonNull(crdSts, "CRDSTSn is required; a blank row field holds spaces or "
                    + "LOW-VALUES, never null");
        }

        /**
         * A row whose every member is space-filled to its declared width.
         *
         * @return the blank row, never {@code null}
         */
        public static StopperListRow blank() {
            return new StopperListRow(spaces(CRDSEL_LENGTH),
                    spaces(CRDSTP_LENGTH),
                    spaces(ACCTNO_LENGTH),
                    spaces(CRDNUM_LENGTH),
                    spaces(CRDSTS_LENGTH));
        }

        @Override
        @JsonIgnore
        public int memberCount() {
            return STOPPER_ROW_FIELD_COUNT;
        }

        @Override
        @JsonIgnore
        public int dataLength() {
            return STOPPER_ROW_DATA_LENGTH;
        }

        /**
         * {@inheritDoc}
         *
         * @return always {@code true}: rows 2 through 7 each declare a named {@code CRDSTPn}
         */
        @Override
        @JsonIgnore
        public boolean hasStopper() {
            return true;
        }

        @Override
        public StopperListRow normalised(FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A FixedWidthCodec is required to normalise a row; the "
                    + "charset decides the pad byte and must never be the platform default");
            return new StopperListRow(codec.movePicX(crdSel, CRDSEL_LENGTH),
                    codec.movePicX(crdStp, CRDSTP_LENGTH),
                    codec.movePicX(acctNo, ACCTNO_LENGTH),
                    codec.movePicX(crdNum, CRDNUM_LENGTH),
                    codec.movePicX(crdSts, CRDSTS_LENGTH));
        }
    }

    /**
     * One of the two 27-byte browse keys of the paging cursor -
     * {@code 10 WS-CA-LAST-CARDKEY.} at {@code app/cbl/COCRDLIC.cbl:230} and
     * {@code 10 WS-CA-FIRST-CARDKEY.} at {@code :233}.
     *
     * <pre>
     *   15  WS-CA-xxxx-CARD-NUM      PIC X(16).
     *   15  WS-CA-xxxx-CARD-ACCT-ID  PIC 9(11).
     * </pre>
     *
     * <p>Modelled as a group rather than as two loose fields because the program moves one key onto
     * the other wholesale: {@code MOVE WS-CA-FIRST-CARDKEY TO WS-CA-LAST-CARDKEY} at
     * {@code COCRDLIC.cbl:1268}. With the group present that is one assignment; flattened, it would be
     * two that could drift apart.
     *
     * <p>{@code acctId} is {@code PIC 9(11)} - scale-free unsigned zoned digits - so it is carried as
     * a {@code long}. There is no {@code double}, no {@code float} and no {@code BigDecimal} anywhere
     * on this screen, because no field on it is scaled. Rendering to and from the eleven-byte zoned
     * image goes through the codec, never through {@code Long.parseLong}.
     *
     * @param cardNum {@code WS-CA-*-CARD-NUM}, {@code PIC X(16)}; never {@code null}
     * @param acctId  {@code WS-CA-*-CARD-ACCT-ID}, {@code PIC 9(11)}; never negative, and never above
     *                eleven digits
     */
    public record CardKey(@Size(max = CURSOR_CARD_NUM_LENGTH) String cardNum, long acctId) {

        /** The largest value {@code PIC 9(11)} can hold: eleven nines. */
        public static final long MAX_ACCT_ID = 99_999_999_999L;

        /**
         * Validates the key at construction: an unsigned zoned field cannot hold a negative value and
         * cannot hold more digits than it declares, so either would silently corrupt the image.
         *
         * @throws NullPointerException     if {@code cardNum} is {@code null}
         * @throws IllegalArgumentException if {@code acctId} is negative or exceeds eleven digits
         */
        public CardKey {
            Objects.requireNonNull(cardNum, "WS-CA-*-CARD-NUM is required; a blank key holds spaces "
                    + "or LOW-VALUES, never null");
            if (acctId < 0L) {
                throw new IllegalArgumentException("WS-CA-*-CARD-ACCT-ID is PIC 9(11), an unsigned "
                        + "zoned field, and cannot hold the negative value " + acctId);
            }
            if (acctId > MAX_ACCT_ID) {
                throw new IllegalArgumentException("WS-CA-*-CARD-ACCT-ID is PIC 9(11) and cannot "
                        + "hold " + acctId + ", which needs more than " + CURSOR_ACCT_ID_LENGTH
                        + " digits");
            }
        }

        /**
         * The key state produced by {@code INITIALIZE WS-THIS-PROGCOMMAREA} -
         * {@code app/cbl/COCRDLIC.cbl:317} and {@code :338}. COBOL's {@code INITIALIZE} sets
         * alphanumeric items to spaces and numeric items to zero, so the card number is sixteen spaces
         * and the account identifier is zero.
         *
         * @return the initialised key, never {@code null}
         */
        public static CardKey initialised() {
            return new CardKey(spaces(CURSOR_CARD_NUM_LENGTH), 0L);
        }

        /**
         * The key state produced by {@code MOVE LOW-VALUES} into the card number span. Distinct from
         * {@link #initialised()}, which yields spaces: the two are different byte images and this class
         * never treats them as interchangeable.
         *
         * @return the low-value key, never {@code null}
         */
        public static CardKey lowValues() {
            return new CardKey(CardScreenState.lowValues(CURSOR_CARD_NUM_LENGTH), 0L);
        }

        /**
         * Whether the card number span holds {@code LOW-VALUES} throughout.
         *
         * @return {@code true} only when every one of the sixteen bytes is binary zero
         */
        @JsonIgnore
        public boolean isCardNumLowValues() {
            return isEvery(cardNum, LOW_VALUE, CURSOR_CARD_NUM_LENGTH);
        }

        /**
         * The card number rendered at its declared sixteen-byte width through the codec's
         * {@code PIC X} move.
         *
         * @param codec the fixed-width codec; never {@code null}
         * @return the sixteen-byte image
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public String cardNumImage(FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A FixedWidthCodec is required to render WS-CA-*-CARD-NUM "
                    + "at its declared width");
            return codec.movePicX(cardNum, CURSOR_CARD_NUM_LENGTH);
        }

        /**
         * The account identifier rendered as eleven zero-filled zoned digits through the codec's
         * {@code PIC 9} move - the same right-justified, zero-padded placement a COBOL numeric
         * {@code MOVE} performs.
         *
         * @param codec the fixed-width codec; never {@code null}
         * @return the eleven-byte image
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public String acctIdImage(FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A FixedWidthCodec is required to render "
                    + "WS-CA-*-CARD-ACCT-ID at its declared width");
            return codec.movePic9(acctId, CURSOR_ACCT_ID_LENGTH);
        }

        /**
         * Rebuilds a key from its two fixed-width spans, decoding the account identifier through the
         * codec rather than through {@code Long.parseLong} so that a zoned image with an overpunched
         * sign or embedded pad bytes is handled by the one component that knows the encoding.
         *
         * @param codec        the fixed-width codec; never {@code null}
         * @param cardNumImage the sixteen-byte card number span; never {@code null}
         * @param acctIdImage  the eleven-byte zoned account identifier span; never {@code null}
         * @return the decoded key
         * @throws NullPointerException if any argument is {@code null}
         */
        public static CardKey decode(FixedWidthCodec codec, String cardNumImage, String acctIdImage) {
            Objects.requireNonNull(codec, "A FixedWidthCodec is required to decode a card key; the "
                    + "zoned digits must never be read with Long.parseLong");
            Objects.requireNonNull(cardNumImage, "The WS-CA-*-CARD-NUM span is required");
            Objects.requireNonNull(acctIdImage, "The WS-CA-*-CARD-ACCT-ID span is required");
            return new CardKey(codec.movePicX(cardNumImage, CURSOR_CARD_NUM_LENGTH),
                    codec.decodePic9(acctIdImage));
        }

        /**
         * The declared width of this group.
         *
         * @return {@value CardListRequest#CARD_KEY_LENGTH}
         */
        @JsonIgnore
        public int declaredLength() {
            return CARD_KEY_LENGTH;
        }
    }

    /**
     * The 58-byte paging cursor - {@code 01 WS-THIS-PROGCOMMAREA.},
     * {@code app/cbl/COCRDLIC.cbl:229-248}.
     *
     * <pre>
     *   10 WS-CA-LAST-CARDKEY.                                     27
     *      15  WS-CA-LAST-CARD-NUM        PIC X(16).
     *      15  WS-CA-LAST-CARD-ACCT-ID    PIC 9(11).
     *   10 WS-CA-FIRST-CARDKEY.                                    27
     *      15  WS-CA-FIRST-CARD-NUM       PIC X(16).
     *      15  WS-CA-FIRST-CARD-ACCT-ID   PIC 9(11).
     *   10 WS-CA-SCREEN-NUM               PIC 9(1).                 1
     *      88 CA-FIRST-PAGE                  VALUE 1.
     *   10 WS-CA-LAST-PAGE-DISPLAYED      PIC 9(1).                 1
     *      88 CA-LAST-PAGE-SHOWN             VALUE 0.
     *      88 CA-LAST-PAGE-NOT-SHOWN         VALUE 9.
     *   10 WS-CA-NEXT-PAGE-IND            PIC X(1).                 1
     *      88 CA-NEXT-PAGE-NOT-EXISTS        VALUE LOW-VALUES.
     *      88 CA-NEXT-PAGE-EXISTS            VALUE 'Y'.
     *   10 WS-RETURN-FLAG                 PIC X(1).                 1
     *      88 WS-RETURN-FLAG-OFF             VALUE LOW-VALUES.
     *      88 WS-RETURN-FLAG-ON              VALUE '1'.
     *                                                              --
     *                                                              58
     * </pre>
     *
     * <p>This is the whole of what makes paging stateless. CICS carries it by appending it to the
     * communication area behind {@code CARDDEMO-COMMAREA} ({@code COCRDLIC.cbl:327-331} on the way in,
     * {@code :610-612} on the way out), and the REST projection carries it in the request body for the
     * same reason: nothing about a page position may live on the server between calls.
     *
     * <p><strong>{@code LOW-VALUES} is not a space and is not {@code null}.</strong> Both
     * {@code CA-NEXT-PAGE-NOT-EXISTS} and {@code WS-RETURN-FLAG-OFF} test {@code LOW-VALUES}
     * specifically, so {@link #isNextPageNotExists()} and {@link #isReturnFlagOff()} are true only for
     * binary zero. That has a consequence worth stating plainly: after
     * {@code INITIALIZE WS-THIS-PROGCOMMAREA} ({@code :317}, {@code :338}) those two bytes hold a
     * <em>space</em>, because that is what COBOL's {@code INITIALIZE} writes into an alphanumeric item -
     * so immediately after initialisation neither the "off" nor the "on" condition holds for either
     * flag. {@link #initialised()} reproduces exactly that state. It is
     * {@code SET CA-NEXT-PAGE-NOT-EXISTS TO TRUE} at {@code :1216} and {@code :1235} that actually
     * writes {@code LOW-VALUES}.
     *
     * <p>{@code WS-RETURN-FLAG} deserves one note. {@code app/cpy/CVCRD01Y.cpy:25-27} declares a field
     * of identical shape, {@code CCARD-RETURN-FLAG} with the same two {@code 88}-levels - but it is
     * <strong>commented out</strong> there. The live field is this one, declared in {@code COCRDLIC}
     * itself, and it belongs on this cursor. The commented-out copybook field stays commented out.
     *
     * @param lastCardKey        {@code WS-CA-LAST-CARDKEY}, the high-water browse key of the page just
     *                           shown; never {@code null}
     * @param firstCardKey       {@code WS-CA-FIRST-CARDKEY}, the low-water browse key of the page just
     *                           shown; never {@code null}
     * @param screenNum          {@code WS-CA-SCREEN-NUM PIC 9(1)}, the 1-based page number rendered
     *                           into {@code PAGENO} by {@code MOVE WS-CA-SCREEN-NUM TO PAGENOO} at
     *                           {@code COCRDLIC.cbl:667}. Zero is a legitimate value - {@code :1177}
     *                           tests {@code IF WS-CA-SCREEN-NUM = 0} before
     *                           {@code ADD +1 TO WS-CA-SCREEN-NUM}
     * @param lastPageDisplayed  {@code WS-CA-LAST-PAGE-DISPLAYED PIC 9(1)}, 0 when the final page has
     *                           been shown and 9 when it has not
     * @param nextPageInd        {@code WS-CA-NEXT-PAGE-IND PIC X(1)}, {@code LOW-VALUES} for none or
     *                           {@code 'Y'}; never {@code null}
     * @param returnFlag         {@code WS-RETURN-FLAG PIC X(1)}, {@code LOW-VALUES} for off or
     *                           {@code '1'} for on; never {@code null}
     */
    public record PageCursor(@Valid CardKey lastCardKey,
                             @Valid CardKey firstCardKey,
                             int screenNum,
                             int lastPageDisplayed,
                             @Size(max = NEXT_PAGE_IND_LENGTH) String nextPageInd,
                             @Size(max = RETURN_FLAG_LENGTH) String returnFlag) {

        /** The largest value a {@code PIC 9(1)} item can hold. */
        public static final int MAX_SINGLE_DIGIT = 9;

        /**
         * Validates the cursor at construction. The two {@code PIC 9(1)} items physically cannot hold
         * a negative value or a value above nine, and a one-byte indicator cannot hold more than one
         * character, so any of those would mean the image is already wrong.
         *
         * @throws NullPointerException     if any reference member is {@code null}
         * @throws IllegalArgumentException if either single-digit item is outside 0 to 9, or if either
         *                                  indicator is longer than one character
         */
        public PageCursor {
            Objects.requireNonNull(lastCardKey, "WS-CA-LAST-CARDKEY is required; use "
                    + "CardKey.initialised() for the post-INITIALIZE state");
            Objects.requireNonNull(firstCardKey, "WS-CA-FIRST-CARDKEY is required; use "
                    + "CardKey.initialised() for the post-INITIALIZE state");
            Objects.requireNonNull(nextPageInd, "WS-CA-NEXT-PAGE-IND is required; its off state is "
                    + "LOW-VALUES, which is a byte and not null");
            Objects.requireNonNull(returnFlag, "WS-RETURN-FLAG is required; its off state is "
                    + "LOW-VALUES, which is a byte and not null");
            if (screenNum < 0 || screenNum > MAX_SINGLE_DIGIT) {
                throw new IllegalArgumentException("WS-CA-SCREEN-NUM is PIC 9(1) and cannot hold "
                        + screenNum + "; the only valid values are 0 through 9");
            }
            if (lastPageDisplayed < 0 || lastPageDisplayed > MAX_SINGLE_DIGIT) {
                throw new IllegalArgumentException("WS-CA-LAST-PAGE-DISPLAYED is PIC 9(1) and cannot "
                        + "hold " + lastPageDisplayed + "; the only valid values are 0 through 9");
            }
            if (nextPageInd.length() > NEXT_PAGE_IND_LENGTH) {
                throw new IllegalArgumentException("WS-CA-NEXT-PAGE-IND is PIC X(1) and cannot hold "
                        + nextPageInd.length() + " character(s)");
            }
            if (returnFlag.length() > RETURN_FLAG_LENGTH) {
                throw new IllegalArgumentException("WS-RETURN-FLAG is PIC X(1) and cannot hold "
                        + returnFlag.length() + " character(s)");
            }
        }

        /**
         * The state produced by {@code INITIALIZE WS-THIS-PROGCOMMAREA} on its own -
         * {@code app/cbl/COCRDLIC.cbl:317} and {@code :338}. Spaces in the alphanumeric spans, zeros in
         * the numeric ones, and therefore <em>neither</em> {@code 88}-level true for either one-byte
         * indicator.
         *
         * @return the initialised cursor, never {@code null}
         */
        public static PageCursor initialised() {
            return new PageCursor(CardKey.initialised(),
                    CardKey.initialised(),
                    0,
                    0,
                    String.valueOf(SPACE),
                    String.valueOf(SPACE));
        }

        /**
         * The state the program actually enters the screen in: {@code INITIALIZE} followed by
         * {@code SET CA-FIRST-PAGE TO TRUE} and {@code SET CA-LAST-PAGE-NOT-SHOWN TO TRUE}. That pair
         * appears twice - at {@code app/cbl/COCRDLIC.cbl:324-325} for a cold start
         * ({@code EIBCALEN = 0}) and again at {@code :341-342} when control arrives from the menu -
         * and both times it means page 1 with the final page not yet reached.
         *
         * @return the first-page cursor, never {@code null}
         */
        public static PageCursor firstPage() {
            return initialised()
                    .withScreenNum(FIRST_PAGE_SCREEN_NUM)
                    .withLastPageDisplayed(LAST_PAGE_NOT_SHOWN);
        }

        /**
         * {@code 88 CA-FIRST-PAGE VALUE 1.} - {@code app/cbl/COCRDLIC.cbl:238}. Tested at {@code :440},
         * {@code :445}, {@code :502} (negated) and {@code :902}.
         *
         * @return {@code true} when the page number is exactly 1
         */
        @JsonIgnore
        public boolean isFirstPage() {
            return screenNum == FIRST_PAGE_SCREEN_NUM;
        }

        /**
         * {@code 88 CA-LAST-PAGE-SHOWN VALUE 0.} - {@code app/cbl/COCRDLIC.cbl:240}.
         *
         * @return {@code true} when the final page has been displayed
         */
        @JsonIgnore
        public boolean isLastPageShown() {
            return lastPageDisplayed == LAST_PAGE_SHOWN;
        }

        /**
         * {@code 88 CA-LAST-PAGE-NOT-SHOWN VALUE 9.} - {@code app/cbl/COCRDLIC.cbl:241}.
         *
         * <p>Note that this is not the negation of {@link #isLastPageShown()}: the item is
         * {@code PIC 9(1)} and the two condition names claim only the values 0 and 9, so any of 1
         * through 8 satisfies neither. The predicates are kept independent for that reason.
         *
         * @return {@code true} when the value is exactly 9
         */
        @JsonIgnore
        public boolean isLastPageNotShown() {
            return lastPageDisplayed == LAST_PAGE_NOT_SHOWN;
        }

        /**
         * {@code 88 CA-NEXT-PAGE-NOT-EXISTS VALUE LOW-VALUES.} -
         * {@code app/cbl/COCRDLIC.cbl:243}, set at {@code :1216} and {@code :1235}.
         *
         * @return {@code true} only when the byte is binary zero - never for a space and never for an
         *         empty string
         */
        @JsonIgnore
        public boolean isNextPageNotExists() {
            return isEvery(nextPageInd, LOW_VALUE, NEXT_PAGE_IND_LENGTH);
        }

        /**
         * {@code 88 CA-NEXT-PAGE-EXISTS VALUE 'Y'.} - {@code app/cbl/COCRDLIC.cbl:244}, set at
         * {@code :1141}, {@code :1210} and {@code :1287}.
         *
         * @return {@code true} only when the byte is {@code 'Y'}
         */
        @JsonIgnore
        public boolean isNextPageExists() {
            return isEvery(nextPageInd, NEXT_PAGE_EXISTS, NEXT_PAGE_IND_LENGTH);
        }

        /**
         * {@code 88 WS-RETURN-FLAG-OFF VALUE LOW-VALUES.} - {@code app/cbl/COCRDLIC.cbl:247}.
         *
         * @return {@code true} only when the byte is binary zero
         */
        @JsonIgnore
        public boolean isReturnFlagOff() {
            return isEvery(returnFlag, LOW_VALUE, RETURN_FLAG_LENGTH);
        }

        /**
         * {@code 88 WS-RETURN-FLAG-ON VALUE '1'.} - {@code app/cbl/COCRDLIC.cbl:248}.
         *
         * @return {@code true} only when the byte is {@code '1'}
         */
        @JsonIgnore
        public boolean isReturnFlagOn() {
            return isEvery(returnFlag, RETURN_FLAG_ON, RETURN_FLAG_LENGTH);
        }

        /**
         * {@code WS-CA-LAST-CARD-NUM PIC X(16)} - the flattened accessor, for callers that want the
         * elementary item rather than the group.
         *
         * @return the sixteen-byte card number of the last key
         */
        @JsonIgnore
        public String lastCardNum() {
            return lastCardKey.cardNum();
        }

        /**
         * {@code WS-CA-LAST-CARD-ACCT-ID PIC 9(11)} - the flattened accessor.
         *
         * @return the account identifier of the last key
         */
        @JsonIgnore
        public long lastCardAcctId() {
            return lastCardKey.acctId();
        }

        /**
         * {@code WS-CA-FIRST-CARD-NUM PIC X(16)} - the flattened accessor.
         *
         * @return the sixteen-byte card number of the first key
         */
        @JsonIgnore
        public String firstCardNum() {
            return firstCardKey.cardNum();
        }

        /**
         * {@code WS-CA-FIRST-CARD-ACCT-ID PIC 9(11)} - the flattened accessor.
         *
         * @return the account identifier of the first key
         */
        @JsonIgnore
        public long firstCardAcctId() {
            return firstCardKey.acctId();
        }

        /**
         * Reproduces {@code MOVE WS-CA-FIRST-CARDKEY TO WS-CA-LAST-CARDKEY} -
         * {@code app/cbl/COCRDLIC.cbl:1268}, a single 27-byte group move.
         *
         * @return a cursor whose last key is a copy of its first key
         */
        public PageCursor withLastCardKeyFromFirst() {
            return new PageCursor(firstCardKey, firstCardKey, screenNum, lastPageDisplayed,
                    nextPageInd, returnFlag);
        }

        /**
         * Replaces {@code WS-CA-LAST-CARDKEY}, the high-water browse key.
         *
         * @param newLastCardKey the replacement high-water key; never {@code null}
         * @return a cursor with {@code WS-CA-LAST-CARDKEY} replaced
         * @throws NullPointerException if {@code newLastCardKey} is {@code null}
         */
        public PageCursor withLastCardKey(CardKey newLastCardKey) {
            return new PageCursor(newLastCardKey, firstCardKey, screenNum, lastPageDisplayed,
                    nextPageInd, returnFlag);
        }

        /**
         * Replaces {@code WS-CA-FIRST-CARDKEY}, the low-water browse key.
         *
         * @param newFirstCardKey the replacement low-water key; never {@code null}
         * @return a cursor with {@code WS-CA-FIRST-CARDKEY} replaced
         * @throws NullPointerException if {@code newFirstCardKey} is {@code null}
         */
        public PageCursor withFirstCardKey(CardKey newFirstCardKey) {
            return new PageCursor(lastCardKey, newFirstCardKey, screenNum, lastPageDisplayed,
                    nextPageInd, returnFlag);
        }

        /**
         * Replaces {@code WS-CA-SCREEN-NUM}, the page number.
         *
         * @param newScreenNum the replacement page number, 0 through 9
         * @return a cursor with {@code WS-CA-SCREEN-NUM} replaced
         * @throws IllegalArgumentException if {@code newScreenNum} is outside 0 to 9
         */
        public PageCursor withScreenNum(int newScreenNum) {
            return new PageCursor(lastCardKey, firstCardKey, newScreenNum, lastPageDisplayed,
                    nextPageInd, returnFlag);
        }

        /**
         * Replaces {@code WS-CA-LAST-PAGE-DISPLAYED}, the final-page indicator.
         *
         * @param newLastPageDisplayed the replacement value, 0 through 9
         * @return a cursor with {@code WS-CA-LAST-PAGE-DISPLAYED} replaced
         * @throws IllegalArgumentException if {@code newLastPageDisplayed} is outside 0 to 9
         */
        public PageCursor withLastPageDisplayed(int newLastPageDisplayed) {
            return new PageCursor(lastCardKey, firstCardKey, screenNum, newLastPageDisplayed,
                    nextPageInd, returnFlag);
        }

        /**
         * Reproduces {@code SET CA-NEXT-PAGE-EXISTS TO TRUE} - {@code app/cbl/COCRDLIC.cbl:1141},
         * {@code :1210}, {@code :1287}.
         *
         * @return a cursor whose next-page indicator is {@code 'Y'}
         */
        public PageCursor withNextPageExists() {
            return new PageCursor(lastCardKey, firstCardKey, screenNum, lastPageDisplayed,
                    String.valueOf(NEXT_PAGE_EXISTS), returnFlag);
        }

        /**
         * Reproduces {@code SET CA-NEXT-PAGE-NOT-EXISTS TO TRUE} -
         * {@code app/cbl/COCRDLIC.cbl:1216}, {@code :1235} - which writes {@code LOW-VALUES} and not a
         * space.
         *
         * @return a cursor whose next-page indicator is binary zero
         */
        public PageCursor withNextPageNotExists() {
            return new PageCursor(lastCardKey, firstCardKey, screenNum, lastPageDisplayed,
                    CardScreenState.lowValues(NEXT_PAGE_IND_LENGTH), returnFlag);
        }

        /**
         * Reproduces {@code SET WS-RETURN-FLAG-ON TO TRUE}, writing {@code '1'}.
         *
         * @return a cursor whose return flag is on
         */
        public PageCursor withReturnFlagOn() {
            return new PageCursor(lastCardKey, firstCardKey, screenNum, lastPageDisplayed,
                    nextPageInd, String.valueOf(RETURN_FLAG_ON));
        }

        /**
         * Reproduces {@code SET WS-RETURN-FLAG-OFF TO TRUE}, writing {@code LOW-VALUES}.
         *
         * @return a cursor whose return flag is off
         */
        public PageCursor withReturnFlagOff() {
            return new PageCursor(lastCardKey, firstCardKey, screenNum, lastPageDisplayed,
                    nextPageInd, CardScreenState.lowValues(RETURN_FLAG_LENGTH));
        }

        /**
         * The declared width of the cursor.
         *
         * @return {@value CardListRequest#CURSOR_LENGTH}
         */
        @JsonIgnore
        public int declaredLength() {
            return CURSOR_LENGTH;
        }
    }

    /**
     * One element of the 196-byte browse result table - {@code 20 WS-EACH-ROW.} /
     * {@code 25 WS-EACH-CARD.} at {@code app/cbl/COCRDLIC.cbl:256-260}.
     *
     * <pre>
     *   30 WS-ROW-ACCTNO        PIC X(11).
     *   30 WS-ROW-CARD-NUM      PIC X(16).
     *   30 WS-ROW-CARD-STATUS   PIC X(1).
     *                                        --
     *                                        28
     * </pre>
     *
     * <p>The source states the arithmetic in its own comment at {@code :250}: "28 CHARS X 7 ROWS = 196".
     *
     * @param acctNo     {@code WS-ROW-ACCTNO PIC X(11)}; never {@code null}
     * @param cardNum    {@code WS-ROW-CARD-NUM PIC X(16)}; never {@code null}
     * @param cardStatus {@code WS-ROW-CARD-STATUS PIC X(1)}; never {@code null}
     */
    public record ScreenRow(@Size(max = SCREEN_ROW_ACCTNO_LENGTH) String acctNo,
                            @Size(max = SCREEN_ROW_CARD_NUM_LENGTH) String cardNum,
                            @Size(max = SCREEN_ROW_CARD_STATUS_LENGTH) String cardStatus) {

        /**
         * Rejects {@code null} in any member: a cleared row holds {@code LOW-VALUES}
         * bytes, which is a value and not an absence.
         *
         * @throws NullPointerException if any member is {@code null}
         */
        public ScreenRow {
            Objects.requireNonNull(acctNo, "WS-ROW-ACCTNO is required; a cleared row holds "
                    + "LOW-VALUES, never null");
            Objects.requireNonNull(cardNum, "WS-ROW-CARD-NUM is required; a cleared row holds "
                    + "LOW-VALUES, never null");
            Objects.requireNonNull(cardStatus, "WS-ROW-CARD-STATUS is required; a cleared row holds "
                    + "LOW-VALUES, never null");
        }

        /**
         * The row state produced by {@code MOVE LOW-VALUES TO WS-ALL-ROWS} -
         * {@code app/cbl/COCRDLIC.cbl:1124} and {@code :1266}, which the program performs immediately
         * before each browse. Binary zero throughout, not spaces.
         *
         * @return the cleared row, never {@code null}
         */
        public static ScreenRow lowValues() {
            return new ScreenRow(CardScreenState.lowValues(SCREEN_ROW_ACCTNO_LENGTH),
                    CardScreenState.lowValues(SCREEN_ROW_CARD_NUM_LENGTH),
                    CardScreenState.lowValues(SCREEN_ROW_CARD_STATUS_LENGTH));
        }

        /**
         * Whether every byte of every member is binary zero, that is whether this row is still in the
         * state {@code MOVE LOW-VALUES TO WS-ALL-ROWS} left it in.
         *
         * @return {@code true} when the row has not been written to since it was cleared
         */
        @JsonIgnore
        public boolean isCleared() {
            return isEvery(acctNo, LOW_VALUE, SCREEN_ROW_ACCTNO_LENGTH)
                    && isEvery(cardNum, LOW_VALUE, SCREEN_ROW_CARD_NUM_LENGTH)
                    && isEvery(cardStatus, LOW_VALUE, SCREEN_ROW_CARD_STATUS_LENGTH);
        }

        /**
         * The declared width of one element.
         *
         * @return {@value CardListRequest#SCREEN_ROW_LENGTH}
         */
        @JsonIgnore
        public int declaredLength() {
            return SCREEN_ROW_LENGTH;
        }
    }

    /**
     * The seven-element browse result table - {@code 10 WS-ALL-ROWS PIC X(196).} redefined as
     * {@code 15 WS-SCREEN-ROWS OCCURS 7 TIMES.} at {@code app/cbl/COCRDLIC.cbl:253-260}.
     *
     * <p>This is the first of the three seven-element 1-based tables behind the screen. It is the one
     * that carries the record data the browse read, and the program clears it with
     * {@code MOVE LOW-VALUES TO WS-ALL-ROWS} before every pass ({@code :1124}, {@code :1266}) - so it
     * is derived state, re-read from the card file on each request, and is therefore deliberately kept
     * off the JSON wire. What must round-trip is the {@link PageCursor}; the rows are recomputed from
     * it.
     *
     * <p>Byte offsets come from {@link FixedWidthRecord#occursElementOffsetOneBased} and are never
     * computed inline, and {@link #row(int)} takes the COBOL subscript, 1 through 7. There is no index
     * 0 and there is no index 8.
     *
     * @param rows exactly {@value CardListRequest#SCREEN_ROW_COUNT} elements, in screen order; copied
     *             defensively, so the list this record holds can never be reached from outside
     */
    public record ScreenRowTable(List<ScreenRow> rows) {

        /**
         * Copies the element list defensively and checks the occurrence count, because a table of the
         * wrong length would skew every offset derived from it.
         *
         * @throws NullPointerException     if {@code rows} is {@code null} or holds {@code null}
         * @throws IllegalArgumentException if {@code rows} does not hold exactly seven elements
         */
        public ScreenRowTable {
            Objects.requireNonNull(rows, "WS-SCREEN-ROWS requires its element list");
            if (rows.size() != SCREEN_ROW_COUNT) {
                throw new IllegalArgumentException("WS-SCREEN-ROWS is declared OCCURS "
                        + SCREEN_ROW_COUNT + " TIMES and cannot hold " + rows.size()
                        + " element(s); the table width " + SCREEN_DATA_LENGTH
                        + " depends on the count");
            }
            rows = List.copyOf(rows);
        }

        /**
         * The table state produced by {@code MOVE LOW-VALUES TO WS-ALL-ROWS}: seven cleared rows.
         *
         * @return the cleared table, never {@code null}
         */
        public static ScreenRowTable lowValues() {
            List<ScreenRow> cleared = new ArrayList<>(SCREEN_ROW_COUNT);
            for (int rowNumber = FIRST_ROW_NUMBER; rowNumber <= LAST_ROW_NUMBER; rowNumber++) {
                cleared.add(ScreenRow.lowValues());
            }
            return new ScreenRowTable(cleared);
        }

        /**
         * Addresses one element by its <strong>COBOL</strong> subscript.
         *
         * @param cobolRowNumber the subscript, 1 through {@value CardListRequest#SCREEN_ROW_COUNT}
         * @return the addressed row, never {@code null}
         * @throws IndexOutOfBoundsException if {@code cobolRowNumber} is outside 1 to 7
         */
        public ScreenRow row(int cobolRowNumber) {
            return rows.get(javaIndexOf(cobolRowNumber));
        }

        /**
         * Replaces one element, addressed by its COBOL subscript, returning a new table.
         *
         * @param cobolRowNumber the subscript, 1 through {@value CardListRequest#SCREEN_ROW_COUNT}
         * @param replacement    the row to place there; never {@code null}
         * @return a new table with that one element replaced
         * @throws NullPointerException      if {@code replacement} is {@code null}
         * @throws IndexOutOfBoundsException if {@code cobolRowNumber} is outside 1 to 7
         */
        public ScreenRowTable withRow(int cobolRowNumber, ScreenRow replacement) {
            Objects.requireNonNull(replacement, "A replacement WS-SCREEN-ROWS element is required");
            List<ScreenRow> updated = new ArrayList<>(rows);
            updated.set(javaIndexOf(cobolRowNumber), replacement);
            return new ScreenRowTable(updated);
        }

        /**
         * The absolute 0-based byte offset of one element within the 196-byte table, computed by
         * {@link FixedWidthRecord#occursElementOffsetOneBased} so the 1-based to 0-based shift is named
         * rather than open-coded. Subscript 1 yields 0 and subscript 7 yields 168.
         *
         * @param cobolRowNumber the subscript, 1 through {@value CardListRequest#SCREEN_ROW_COUNT}
         * @return the element's byte offset within {@code WS-ALL-ROWS}
         * @throws IndexOutOfBoundsException if {@code cobolRowNumber} is outside 1 to 7
         */
        public static int rowOffset(int cobolRowNumber) {
            return FixedWidthRecord.occursElementOffsetOneBased(0, SCREEN_ROW_LENGTH,
                    SCREEN_ROW_COUNT, cobolRowNumber);
        }

        /**
         * The declared width of the whole table.
         *
         * @return {@value CardListRequest#SCREEN_DATA_LENGTH}
         */
        @JsonIgnore
        public int declaredLength() {
            return SCREEN_DATA_LENGTH;
        }
    }

    /**
     * The seven row action codes - {@code app/cbl/COCRDLIC.cbl:72-82}.
     *
     * <pre>
     *   05 WS-EDIT-SELECT-FLAGS                PIC X(7) VALUE LOW-VALUES.
     *   05 WS-EDIT-SELECT-ARRAY REDEFINES WS-EDIT-SELECT-FLAGS.
     *      10 WS-EDIT-SELECT                   PIC X(1) OCCURS 7 TIMES.
     *         88 SELECT-OK                     VALUES 'S', 'U'.
     *         88 VIEW-REQUESTED-ON             VALUE 'S'.
     *         88 UPDATE-REQUESTED-ON           VALUE 'U'.
     *         88 SELECT-BLANK                  VALUES ' ', LOW-VALUES.
     * </pre>
     *
     * <p>All <strong>four</strong> condition names are modelled, not just the two that name a single
     * action. {@code SELECT-OK} is the union of {@code 'S'} and {@code 'U'} and is what
     * {@code 2250-EDIT-ARRAY} tests first; {@code SELECT-BLANK} accepts <em>both</em> a space
     * <em>and</em> {@code LOW-VALUES}, which is why an operator who cleared a field and an operator who
     * never touched it are treated alike.
     *
     * <p>The backing item carries {@code VALUE LOW-VALUES}, so a freshly built table holds seven binary
     * zero bytes - not seven spaces, and certainly not {@code null}. {@link #lowValues()} is that state.
     *
     * <p>The table is populated straight from the payload: {@code MOVE CRDSELnI OF CCRDLIAI TO
     * WS-EDIT-SELECT(n)} for n = 1 through 7 at {@code app/cbl/COCRDLIC.cbl:972-978}. It is retained as
     * its own carrier because the program then evaluates it independently of the map fields, and
     * because the outbound {@code MOVE WS-EDIT-SELECT(n) TO CRDSELnO} at {@code :683-738} shows the same
     * table driving the response.
     *
     * @param flags exactly {@value CardListRequest#SELECT_FLAGS_LENGTH} characters, one per row, in row
     *              order
     */
    public record SelectionFlags(@Size(max = SELECT_FLAGS_LENGTH) String flags) {

        /**
         * Validates the table at construction. The backing item is {@code PIC X(7)}
         * redefined as {@code OCCURS 7 TIMES}, so a table of any other length would skew
         * every subscript derived from it.
         *
         * @throws NullPointerException     if {@code flags} is {@code null}
         * @throws IllegalArgumentException if {@code flags} is not exactly seven characters long
         */
        public SelectionFlags {
            Objects.requireNonNull(flags, "WS-EDIT-SELECT-FLAGS is required; its declared VALUE is "
                    + "LOW-VALUES, which is seven bytes and not null");
            if (flags.length() != SELECT_FLAGS_LENGTH) {
                throw new IllegalArgumentException("WS-EDIT-SELECT-FLAGS is PIC X(7) redefined as "
                        + "OCCURS " + SELECT_FLAGS_LENGTH + " TIMES and cannot hold " + flags.length()
                        + " character(s)");
            }
        }

        /**
         * The declared initial state, {@code VALUE LOW-VALUES} - seven bytes of binary zero.
         *
         * @return the low-value table, never {@code null}
         */
        public static SelectionFlags lowValues() {
            return new SelectionFlags(CardScreenState.lowValues(SELECT_FLAGS_LENGTH));
        }

        /**
         * Seven spaces. Offered alongside {@link #lowValues()} precisely because the two are different
         * byte images that {@code SELECT-BLANK} happens to treat alike - a similarity that must never
         * become a conflation.
         *
         * @return the space-filled table, never {@code null}
         */
        public static SelectionFlags spacesFilled() {
            return new SelectionFlags(spaces(SELECT_FLAGS_LENGTH));
        }

        /**
         * Builds the table from the seven {@code CRDSELn} payload fields, reproducing
         * {@code MOVE CRDSELnI OF CCRDLIAI TO WS-EDIT-SELECT(n)} at
         * {@code app/cbl/COCRDLIC.cbl:972-978}. An empty {@code CRDSELn} contributes a space, which is
         * how a one-byte alphanumeric {@code MOVE} from an empty source pads.
         *
         * @param rows the seven detail rows in screen order; never {@code null}, exactly seven elements
         * @return the populated table
         * @throws NullPointerException     if {@code rows} is {@code null} or holds {@code null}
         * @throws IllegalArgumentException if {@code rows} does not hold exactly seven elements
         */
        public static SelectionFlags fromRows(List<ListRow> rows) {
            Objects.requireNonNull(rows, "The seven detail rows are required to build "
                    + "WS-EDIT-SELECT-FLAGS");
            if (rows.size() != SELECT_FLAGS_LENGTH) {
                throw new IllegalArgumentException("WS-EDIT-SELECT-FLAGS is fed from exactly "
                        + SELECT_FLAGS_LENGTH + " rows but " + rows.size() + " were supplied");
            }
            StringBuilder built = new StringBuilder(SELECT_FLAGS_LENGTH);
            for (ListRow row : rows) {
                Objects.requireNonNull(row, "A detail row is required for every one of the "
                        + SELECT_FLAGS_LENGTH + " screen lines");
                String selection = row.crdSel();
                built.append(selection.isEmpty() ? SPACE : selection.charAt(0));
            }
            return new SelectionFlags(built.toString());
        }

        /**
         * The action code of one row, addressed by its COBOL subscript.
         *
         * @param cobolRowNumber the subscript, 1 through {@value CardListRequest#SELECT_FLAGS_LENGTH}
         * @return the one action character
         * @throws IndexOutOfBoundsException if {@code cobolRowNumber} is outside 1 to 7
         */
        public char at(int cobolRowNumber) {
            return flags.charAt(javaIndexOf(cobolRowNumber));
        }

        /**
         * {@code 88 SELECT-OK VALUES 'S', 'U'.} - {@code app/cbl/COCRDLIC.cbl:77}. The first arm of the
         * {@code EVALUATE TRUE} at {@code :1100-1114}.
         *
         * @param cobolRowNumber the subscript, 1 through 7
         * @return {@code true} when the row carries {@code 'S'} or {@code 'U'}
         * @throws IndexOutOfBoundsException if {@code cobolRowNumber} is outside 1 to 7
         */
        public boolean isSelectOk(int cobolRowNumber) {
            char value = at(cobolRowNumber);
            return value == SELECT_VIEW || value == SELECT_UPDATE;
        }

        /**
         * {@code 88 VIEW-REQUESTED-ON VALUE 'S'.} - {@code app/cbl/COCRDLIC.cbl:78}.
         *
         * @param cobolRowNumber the subscript, 1 through 7
         * @return {@code true} when the row carries {@code 'S'}
         * @throws IndexOutOfBoundsException if {@code cobolRowNumber} is outside 1 to 7
         */
        public boolean isViewRequestedOn(int cobolRowNumber) {
            return at(cobolRowNumber) == SELECT_VIEW;
        }

        /**
         * {@code 88 UPDATE-REQUESTED-ON VALUE 'U'.} - {@code app/cbl/COCRDLIC.cbl:79}.
         *
         * @param cobolRowNumber the subscript, 1 through 7
         * @return {@code true} when the row carries {@code 'U'}
         * @throws IndexOutOfBoundsException if {@code cobolRowNumber} is outside 1 to 7
         */
        public boolean isUpdateRequestedOn(int cobolRowNumber) {
            return at(cobolRowNumber) == SELECT_UPDATE;
        }

        /**
         * {@code 88 SELECT-BLANK VALUES ' ', LOW-VALUES.} - {@code app/cbl/COCRDLIC.cbl:80-82}. The
         * second arm of the {@code EVALUATE TRUE} at {@code :1100-1114}, and the reason an untouched
         * row is not an error.
         *
         * @param cobolRowNumber the subscript, 1 through 7
         * @return {@code true} when the row carries a space <em>or</em> binary zero
         * @throws IndexOutOfBoundsException if {@code cobolRowNumber} is outside 1 to 7
         */
        public boolean isSelectBlank(int cobolRowNumber) {
            char value = at(cobolRowNumber);
            return value == SPACE || value == LOW_VALUE;
        }

        /**
         * Replaces one row's action code, addressed by its COBOL subscript.
         *
         * @param cobolRowNumber the subscript, 1 through 7
         * @param value          the replacement action character
         * @return a new table with that one character replaced
         * @throws IndexOutOfBoundsException if {@code cobolRowNumber} is outside 1 to 7
         */
        public SelectionFlags withSelection(int cobolRowNumber, char value) {
            char[] updated = flags.toCharArray();
            updated[javaIndexOf(cobolRowNumber)] = value;
            return new SelectionFlags(new String(updated));
        }

        /**
         * {@code I-SELECTED} as {@code 2250-EDIT-ARRAY} leaves it -
         * {@code app/cbl/COCRDLIC.cbl:1097-1115}.
         *
         * <p>The COBOL is {@code MOVE ZERO TO I-SELECTED} followed by
         * {@code PERFORM VARYING I FROM 1 BY 1 UNTIL I > 7} with
         * {@code EVALUATE TRUE / WHEN SELECT-OK(I) / MOVE I TO I-SELECTED}. The assignment is
         * unconditional within that arm and the loop does not stop, so the value that survives is the
         * <em>last</em> qualifying subscript, and it is zero when none qualifies.
         *
         * <p>This reproduces that one assignment and nothing else. Deciding that more than one action
         * is an error, setting {@code WS-ROW-CRDSELECT-ERROR}, and choosing the message are the
         * service's work, not a payload's.
         *
         * @return the 1-based subscript of the last selected row, or
         *         {@value CardListRequest#NO_ROW_SELECTED} when no row is selected
         */
        @JsonIgnore
        public int selectedRowNumber() {
            int selected = NO_ROW_SELECTED;
            for (int rowNumber = FIRST_ROW_NUMBER; rowNumber <= LAST_ROW_NUMBER; rowNumber++) {
                if (isSelectOk(rowNumber)) {
                    selected = rowNumber;
                }
            }
            return selected;
        }

        /**
         * {@code 88 DETAIL-WAS-REQUESTED VALUES 1 THRU 7.} - {@code app/cbl/COCRDLIC.cbl:94}.
         *
         * @return {@code true} when {@link #selectedRowNumber()} is between 1 and 7 inclusive
         */
        @JsonIgnore
        public boolean isDetailWasRequested() {
            return isDetailRequested(selectedRowNumber());
        }

        /**
         * {@code 88 DETAIL-WAS-REQUESTED VALUES 1 THRU 7.} evaluated over an arbitrary
         * {@code I-SELECTED} value - {@code app/cbl/COCRDLIC.cbl:92-94}.
         *
         * <p>The condition is declared on {@code 10 I-SELECTED PIC S9(4) COMP}, a signed four-digit
         * binary halfword, so its domain is far wider than 1 to 7 and the range test is a genuine
         * two-sided check rather than a formality. Exposing it over the full domain keeps that faithful
         * and keeps the COBOL numbering on the wire: a caller carries the subscript as 1 through 7 and
         * converts to a Java index only at the array boundary, in {@link #javaIndexOf(int)}.
         *
         * @param iSelected the {@code I-SELECTED} value to test; any {@code int}, including 0 and
         *                  values above 7
         * @return {@code true} when {@code iSelected} lies in the inclusive range 1 to 7
         */
        public static boolean isDetailRequested(int iSelected) {
            return iSelected >= FIRST_ROW_NUMBER && iSelected <= LAST_ROW_NUMBER;
        }

        /**
         * How many rows carry a {@code SELECT-OK} action. {@code 2250-EDIT-ARRAY} derives the same
         * count with {@code INSPECT ... TALLYING} before deciding whether more than one action was
         * requested.
         *
         * @return the count, 0 through 7
         */
        @JsonIgnore
        public int selectedRowCount() {
            int count = 0;
            for (int rowNumber = FIRST_ROW_NUMBER; rowNumber <= LAST_ROW_NUMBER; rowNumber++) {
                if (isSelectOk(rowNumber)) {
                    count++;
                }
            }
            return count;
        }

        /**
         * The absolute 0-based byte offset of one element within the seven-byte table, via
         * {@link FixedWidthRecord#occursElementOffsetOneBased}. Subscript 1 yields 0 and subscript 7
         * yields 6.
         *
         * @param cobolRowNumber the subscript, 1 through 7
         * @return the element's byte offset
         * @throws IndexOutOfBoundsException if {@code cobolRowNumber} is outside 1 to 7
         */
        public static int flagOffset(int cobolRowNumber) {
            return FixedWidthRecord.occursElementOffsetOneBased(0, CRDSEL_LENGTH,
                    SELECT_FLAGS_LENGTH, cobolRowNumber);
        }

        /**
         * The declared width of the table.
         *
         * @return {@value CardListRequest#SELECT_FLAGS_LENGTH}
         */
        @JsonIgnore
        public int declaredLength() {
            return SELECT_FLAGS_LENGTH;
        }
    }

    /**
     * The seven per-row highlight flags - {@code app/cbl/COCRDLIC.cbl:83-88}.
     *
     * <pre>
     *   05 WS-EDIT-SELECT-ERROR-FLAGS          PIC X(7).
     *   05 WS-EDIT-SELECT-ERROR-FLAGX REDEFINES WS-EDIT-SELECT-ERROR-FLAGS.
     *      10 WS-EDIT-SELECT-ERRORS OCCURS 7 TIMES.
     *         20 WS-ROW-CRDSELECT-ERROR        PIC X(1).
     *            88 WS-ROW-SELECT-ERROR        VALUE '1'.
     * </pre>
     *
     * <p>The third of the three seven-element tables, and the one that decides which row gets
     * highlighted. The program sets it in two places: as a whole, by copying the action codes and
     * translating them with {@code INSPECT ... REPLACING ALL 'S' BY '1' ALL 'U' BY '1' CHARACTERS BY
     * '0'} when more than one action was requested ({@code :1088-1093}); and element by element with
     * {@code MOVE '1' TO WS-ROW-CRDSELECT-ERROR(I)} at {@code :1104} and {@code :1110}. It is then read
     * at {@code :755-826}, once per row, to decide the outbound attribute.
     *
     * <p>Because it feeds {@code common/FieldAttributeSetter} and never the map, it is highlight
     * metadata and is kept off the JSON wire in exactly the way the {@code xxxA} attribute items are.
     *
     * @param flags exactly {@value CardListRequest#SELECT_FLAGS_LENGTH} characters, one per row
     */
    public record SelectionErrorFlags(@Size(max = SELECT_FLAGS_LENGTH) String flags) {

        /**
         * Validates the table at construction, for the same reason
         * {@link SelectionFlags} does: seven bytes, one per screen row.
         *
         * @throws NullPointerException     if {@code flags} is {@code null}
         * @throws IllegalArgumentException if {@code flags} is not exactly seven characters long
         */
        public SelectionErrorFlags {
            Objects.requireNonNull(flags, "WS-EDIT-SELECT-ERROR-FLAGS is required");
            if (flags.length() != SELECT_FLAGS_LENGTH) {
                throw new IllegalArgumentException("WS-EDIT-SELECT-ERROR-FLAGS is PIC X(7) redefined "
                        + "as OCCURS " + SELECT_FLAGS_LENGTH + " TIMES and cannot hold "
                        + flags.length() + " character(s)");
            }
        }

        /**
         * Seven spaces. The item declares no {@code VALUE} clause, so unlike
         * {@code WS-EDIT-SELECT-FLAGS} its content is whatever the program puts there; spaces are the
         * neutral starting point, and no row is highlighted until a {@code '1'} is written.
         *
         * @return the space-filled table, never {@code null}
         */
        public static SelectionErrorFlags none() {
            return new SelectionErrorFlags(spaces(SELECT_FLAGS_LENGTH));
        }

        /**
         * {@code 88 WS-ROW-SELECT-ERROR VALUE '1'.} - {@code app/cbl/COCRDLIC.cbl:88}, tested per row
         * at {@code :755}, {@code :768}, {@code :780}, {@code :792}, {@code :803}, {@code :815} and
         * {@code :826}.
         *
         * @param cobolRowNumber the subscript, 1 through {@value CardListRequest#SELECT_FLAGS_LENGTH}
         * @return {@code true} when the row is flagged for highlighting
         * @throws IndexOutOfBoundsException if {@code cobolRowNumber} is outside 1 to 7
         */
        public boolean isRowSelectError(int cobolRowNumber) {
            return flags.charAt(javaIndexOf(cobolRowNumber)) == ROW_SELECT_ERROR;
        }

        /**
         * Reproduces {@code MOVE '1' TO WS-ROW-CRDSELECT-ERROR(I)} -
         * {@code app/cbl/COCRDLIC.cbl:1104} and {@code :1110}.
         *
         * @param cobolRowNumber the subscript, 1 through 7
         * @return a new table with that row flagged
         * @throws IndexOutOfBoundsException if {@code cobolRowNumber} is outside 1 to 7
         */
        public SelectionErrorFlags withRowInError(int cobolRowNumber) {
            char[] updated = flags.toCharArray();
            updated[javaIndexOf(cobolRowNumber)] = ROW_SELECT_ERROR;
            return new SelectionErrorFlags(new String(updated));
        }

        /**
         * Reproduces {@code MOVE WS-EDIT-SELECT-FLAGS TO WS-EDIT-SELECT-ERROR-FLAGS} followed by
         * {@code INSPECT WS-EDIT-SELECT-ERROR-FLAGS REPLACING ALL 'S' BY '1' ALL 'U' BY '1'
         * CHARACTERS BY '0'} - {@code app/cbl/COCRDLIC.cbl:1088-1093}, the branch taken when the
         * operator marked more than one row.
         *
         * <p>The {@code INSPECT} order matters and is preserved: the two {@code REPLACING ALL} clauses
         * are applied to {@code 'S'} and {@code 'U'} first, and only the characters they did not
         * replace fall through to {@code CHARACTERS BY '0'}. Every byte therefore ends as {@code '1'}
         * or {@code '0'} and none is left as it was.
         *
         * @param selectionFlags the action codes to translate; never {@code null}
         * @return the translated highlight table
         * @throws NullPointerException if {@code selectionFlags} is {@code null}
         */
        public static SelectionErrorFlags fromSelectionFlags(SelectionFlags selectionFlags) {
            Objects.requireNonNull(selectionFlags, "WS-EDIT-SELECT-FLAGS is required to derive "
                    + "WS-EDIT-SELECT-ERROR-FLAGS");
            char[] translated = new char[SELECT_FLAGS_LENGTH];
            for (int rowNumber = FIRST_ROW_NUMBER; rowNumber <= LAST_ROW_NUMBER; rowNumber++) {
                char action = selectionFlags.at(rowNumber);
                boolean replaced = action == SELECT_VIEW || action == SELECT_UPDATE;
                translated[javaIndexOf(rowNumber)] = replaced ? ROW_SELECT_ERROR : '0';
            }
            return new SelectionErrorFlags(new String(translated));
        }

        /**
         * The absolute 0-based byte offset of one element within the seven-byte table, via
         * {@link FixedWidthRecord#occursElementOffsetOneBased}.
         *
         * @param cobolRowNumber the subscript, 1 through 7
         * @return the element's byte offset
         * @throws IndexOutOfBoundsException if {@code cobolRowNumber} is outside 1 to 7
         */
        public static int flagOffset(int cobolRowNumber) {
            return FixedWidthRecord.occursElementOffsetOneBased(0, CRDSEL_LENGTH,
                    SELECT_FLAGS_LENGTH, cobolRowNumber);
        }

        /**
         * The declared width of the table.
         *
         * @return {@value CardListRequest#SELECT_FLAGS_LENGTH}
         */
        @JsonIgnore
        public int declaredLength() {
            return SELECT_FLAGS_LENGTH;
        }
    }

    /**
     * The {@code xxxL}, {@code xxxF} and {@code xxxA} items of one screen field, projected as
     * metadata.
     *
     * <p>For every field the symbolic map declares three items besides the data item itself:
     *
     * <ul>
     *   <li>{@code 02 xxxL COMP PIC S9(4).} - the input length CICS reports. Zero means the operator
     *       left the field untouched, which is the presence signal the program's filter edits key
     *       off.</li>
     *   <li>{@code 02 xxxF PICTURE X.} - the flag byte.</li>
     *   <li>{@code 03 xxxA PICTURE X.} - a {@code REDEFINES} view of that same byte, consuming no
     *       additional storage. This is the item {@code common/FieldAttributeSetter} writes when it
     *       applies the error highlight.</li>
     * </ul>
     *
     * <p>None of the three is a payload member. They are validation and highlight metadata, and they
     * are held here deliberately non-serialised - promoting any of them to a JSON member would add a
     * field with no name-labelled {@code DFHMDF} behind it and break gate G9.
     *
     * @param dfhmdfName     the field's {@code DFHMDF} label, verbatim - {@code TRNNAME},
     *                       {@code CRDSEL3}, {@code ERRMSG} and so on
     * @param inputLength    the {@code xxxL} value; never negative
     * @param attributeByte  the {@code xxxF} byte, viewed through {@code xxxA}
     */
    public record FieldMetadata(String dfhmdfName, int inputLength, char attributeByte) {

        /**
         * Validates the descriptor at construction, so metadata that cannot be traced
         * back to a name-labelled {@code DFHMDF} entry is rejected where it is written.
         *
         * @throws NullPointerException     if {@code dfhmdfName} is {@code null}
         * @throws IllegalArgumentException if {@code dfhmdfName} is blank or {@code inputLength} is
         *                                  negative
         */
        public FieldMetadata {
            Objects.requireNonNull(dfhmdfName, "The DFHMDF label is required; metadata with no field "
                    + "to attach to cannot be traced back to the mapset");
            if (dfhmdfName.isBlank()) {
                throw new IllegalArgumentException("The DFHMDF label must not be blank; only "
                        + "name-labelled fields carry metadata, and the 27 unnamed DFHMDF entries of "
                        + "app/bms/COCRDLI.bms carry none");
            }
            if (inputLength < 0) {
                throw new IllegalArgumentException("The xxxL length item for '" + dfhmdfName
                        + "' cannot be negative; CICS reports 0 for a field the operator did not "
                        + "touch");
            }
        }

        /**
         * Metadata for a field the operator did not touch: length zero and a space attribute byte.
         *
         * @param dfhmdfName the field's {@code DFHMDF} label; never {@code null} or blank
         * @return the untouched-field metadata
         * @throws NullPointerException     if {@code dfhmdfName} is {@code null}
         * @throws IllegalArgumentException if {@code dfhmdfName} is blank
         */
        public static FieldMetadata untouched(String dfhmdfName) {
            return new FieldMetadata(dfhmdfName, 0, SPACE);
        }

        /**
         * Whether CICS reported a zero input length, meaning the operator left the field alone.
         *
         * @return {@code true} when {@link #inputLength()} is zero
         */
        @JsonIgnore
        public boolean isUntouched() {
            return inputLength == 0;
        }
    }

    // =================================================================================================
    // PAYLOAD - the header band, 9 of the 45 members. Each is PIC X(n) and therefore a String, held at
    // or below its declared width and space-padded to it on the way out; nothing is trimmed on the way
    // in, because the COBOL does not trim.
    //
    // Every constraint is a @Size(max = ...) taken from the field's own xxxI PICTURE clause, and there
    // is nothing else: no @NotBlank, no @Pattern, no digit check. COCRDLIC owns its filter edits.
    // =================================================================================================

    /** {@code TRNNAME} - {@code 02 TRNNAMEI PIC X(4).}, {@code app/cpy-bms/COCRDLI.CPY:24}. */
    @Size(max = TRNNAME_LENGTH)
    private String trnname;

    /** {@code TITLE01} - {@code 02 TITLE01I PIC X(40).}, {@code app/cpy-bms/COCRDLI.CPY:30}. */
    @Size(max = TITLE01_LENGTH)
    private String title01;

    /**
     * {@code CURDATE} - {@code 02 CURDATEI PIC X(8).}, {@code app/cpy-bms/COCRDLI.CPY:36}. The mapset
     * seeds it with {@code INITIAL='mm/dd/yy'}.
     */
    @Size(max = CURDATE_LENGTH)
    private String curdate;

    /** {@code PGMNAME} - {@code 02 PGMNAMEI PIC X(8).}, {@code app/cpy-bms/COCRDLI.CPY:42}. */
    @Size(max = PGMNAME_LENGTH)
    private String pgmname;

    /** {@code TITLE02} - {@code 02 TITLE02I PIC X(40).}, {@code app/cpy-bms/COCRDLI.CPY:48}. */
    @Size(max = TITLE02_LENGTH)
    private String title02;

    /**
     * {@code CURTIME} - {@code 02 CURTIMEI PIC X(8).}, {@code app/cpy-bms/COCRDLI.CPY:54}. The mapset
     * seeds it with {@code INITIAL='hh:mm:ss'}.
     */
    @Size(max = CURTIME_LENGTH)
    private String curtime;

    /**
     * {@code PAGENO} - {@code 02 PAGENOI PIC X(3).}, {@code app/cpy-bms/COCRDLI.CPY:60}.
     *
     * <p>Unique to this mapset among the three card screens, and positioned between {@code CURTIME} and
     * {@code ACCTSID}; that position is part of the contract. The outbound value is the cursor's page
     * number - {@code MOVE WS-CA-SCREEN-NUM TO PAGENOO OF CCRDLIAO} at {@code app/cbl/COCRDLIC.cbl:667}.
     */
    @Size(max = PAGENO_LENGTH)
    private String pageno;

    /**
     * {@code ACCTSID} - {@code 02 ACCTSIDI PIC X(11).}, {@code app/cpy-bms/COCRDLI.CPY:66}. The account
     * filter, declared {@code ATTRB=(FSET,IC,NORM,UNPROT)} so it is operator-enterable and holds the
     * cursor. {@code app/cbl/COCRDLIC.cbl:969} moves it into {@code CC-ACCT-ID} of the
     * {@link CardScreenState} work area.
     */
    @Size(max = ACCTSID_LENGTH)
    private String acctsid;

    /**
     * {@code CARDSID} - {@code 02 CARDSIDI PIC X(16).}, {@code app/cpy-bms/COCRDLI.CPY:72}. The card
     * filter, declared {@code ATTRB=(FSET,NORM,UNPROT)}. {@code app/cbl/COCRDLIC.cbl:970} moves it into
     * {@code CC-CARD-NUM} of the {@link CardScreenState} work area.
     */
    @Size(max = CARDSID_LENGTH)
    private String cardsid;

    // =================================================================================================
    // PAYLOAD - the detail rows, 34 of the 45 members: 4 for row 1 and 5 for each of rows 2 through 7.
    // =================================================================================================

    /**
     * The seven detail rows in screen order, occupying screen lines 11 through 17.
     *
     * <p>Element 0 is always a {@link FirstListRow} and elements 1 through 6 are always
     * {@link StopperListRow}; {@link #setRows(List)} and {@link #setRow(int, ListRow)} both enforce
     * that, so the row-1 asymmetry cannot be broken by a caller. Address rows by their COBOL subscript
     * through {@link #row(int)}.
     */
    @Valid
    private List<ListRow> rows;

    // =================================================================================================
    // PAYLOAD - the footer band, the last 2 of the 45 members.
    // =================================================================================================

    /**
     * {@code INFOMSG} - {@code 02 INFOMSGI PIC X(45).}, {@code app/cpy-bms/COCRDLI.CPY:282}.
     * Forty-five bytes on this map where {@code COCRDSL} and {@code COCRDUP} declare forty; the
     * divergence is the contract. Populated outbound by {@code MOVE WS-INFO-MSG TO INFOMSGO} at
     * {@code app/cbl/COCRDLIC.cbl:670} and {@code :928}.
     */
    @Size(max = INFOMSG_LENGTH)
    private String infomsg;

    /**
     * {@code ERRMSG} - {@code 02 ERRMSGI PIC X(78).}, {@code app/cpy-bms/COCRDLI.CPY:288}.
     * Seventy-eight bytes on this map where {@code COCRDSL} and {@code COCRDUP} declare eighty.
     * Declared {@code ATTRB=(ASKIP,BRT,FSET) COLOR=RED} at {@code app/bms/COCRDLI.bms:331}.
     */
    @Size(max = ERRMSG_LENGTH)
    private String errmsg;

    // =================================================================================================
    // CARRIERS - the four members that are not map fields but must still travel in the payload, because
    // CICS carried them in the communication area and a stateless server has nowhere else to put them
    // (rule R6, gate G37).
    // =================================================================================================

    /**
     * The 58-byte paging cursor, {@code 01 WS-THIS-PROGCOMMAREA} of
     * {@code app/cbl/COCRDLIC.cbl:229-248}. Never {@code null}.
     */
    @Valid
    private PageCursor pageCursor;

    /**
     * The seven row action codes, {@code WS-EDIT-SELECT OCCURS 7 TIMES} of
     * {@code app/cbl/COCRDLIC.cbl:75-82}. Never {@code null}.
     */
    @Valid
    private SelectionFlags selectionFlags;

    /**
     * The {@code CC-WORK-AREA} of {@code app/cpy/CVCRD01Y.cpy}, copied by
     * {@code app/cbl/COCRDLIC.cbl:221}: the attention identifier, the next program, mapset and map, the
     * two 75-byte message fields and the account, card and customer work keys. Never {@code null}.
     */
    private CardScreenState cardScreenState;

    /**
     * The {@code CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy}, copied by
     * {@code app/cbl/COCRDLIC.cbl:227}. Its {@code CDEMO-PGM-CONTEXT} distinguishes {@code ENTER} from
     * {@code REENTER}, which is what gates the {@code CSSETATY} error highlight - see
     * {@link #isReenter()}. Never {@code null}.
     */
    @Valid
    private NavigationContext navigationContext;

    // =================================================================================================
    // NON-SERIALISED METADATA - modelled because the COBOL declares it, kept off the wire because it is
    // not a map field. Promoting any of it to a JSON member would add a payload field with no
    // name-labelled DFHMDF behind it.
    // =================================================================================================

    /**
     * The 196-byte browse result table, {@code WS-SCREEN-ROWS OCCURS 7 TIMES} of
     * {@code app/cbl/COCRDLIC.cbl:253-260}.
     *
     * <p>Off the wire because it is derived: {@code MOVE LOW-VALUES TO WS-ALL-ROWS} clears it before
     * each browse ({@code :1124}, {@code :1266}) and the browse then refills it from the card file. The
     * client round-trips the {@link PageCursor}; the rows are recomputed from it.
     */
    @JsonIgnore
    private ScreenRowTable screenRowTable;

    /**
     * The seven per-row highlight flags, {@code WS-EDIT-SELECT-ERRORS OCCURS 7 TIMES} of
     * {@code app/cbl/COCRDLIC.cbl:83-88}. Off the wire for the same reason the {@code xxxA} attribute
     * items are: it feeds {@code common/FieldAttributeSetter}, not the map.
     */
    @JsonIgnore
    private SelectionErrorFlags selectionErrorFlags;

    /**
     * The {@code xxxL} length items and {@code xxxF}/{@code xxxA} attribute items, keyed by
     * {@code DFHMDF} label. Off the wire; see {@link FieldMetadata}.
     */
    @JsonIgnore
    private Map<String, FieldMetadata> fieldMetadata;

    // =================================================================================================
    // CONSTRUCTION - no Spring context, no builder, no generated accessors. A parity test, a controller
    // test and a MockMvc test all build one of these the same way (practice B10).
    // =================================================================================================

    /**
     * A blank request in the state the screen is first painted in: every map field space-filled to its
     * declared width, seven blank rows of the correct shapes, a first-page cursor, a low-value selection
     * table, a fresh {@link CardScreenState} and an empty {@link NavigationContext}.
     *
     * <p>Note the two different notions of "empty" in play, kept apart on purpose. The map fields are
     * <em>spaces</em>. The selection table is {@code LOW-VALUES}, because
     * {@code WS-EDIT-SELECT-FLAGS PIC X(7) VALUE LOW-VALUES} says so. The row table is also
     * {@code LOW-VALUES}, because {@code MOVE LOW-VALUES TO WS-ALL-ROWS} says so.
     */
    public CardListRequest() {
        this.trnname = spaces(TRNNAME_LENGTH);
        this.title01 = spaces(TITLE01_LENGTH);
        this.curdate = spaces(CURDATE_LENGTH);
        this.pgmname = spaces(PGMNAME_LENGTH);
        this.title02 = spaces(TITLE02_LENGTH);
        this.curtime = spaces(CURTIME_LENGTH);
        this.pageno = spaces(PAGENO_LENGTH);
        this.acctsid = spaces(ACCTSID_LENGTH);
        this.cardsid = spaces(CARDSID_LENGTH);
        this.rows = blankRows();
        this.infomsg = spaces(INFOMSG_LENGTH);
        this.errmsg = spaces(ERRMSG_LENGTH);
        this.pageCursor = PageCursor.firstPage();
        this.selectionFlags = SelectionFlags.lowValues();
        this.cardScreenState = new CardScreenState();
        this.navigationContext = NavigationContext.empty();
        this.screenRowTable = ScreenRowTable.lowValues();
        this.selectionErrorFlags = SelectionErrorFlags.none();
        this.fieldMetadata = new LinkedHashMap<>();
    }

    /**
     * Copy constructor. Every mutable part is copied rather than shared: the row list is rebuilt, the
     * metadata map is rebuilt, and {@link CardScreenState} - the one mutable carrier - is copied through
     * its own copy constructor. The record-valued members are immutable and are shared safely.
     *
     * @param other the request to copy; never {@code null}
     * @throws NullPointerException if {@code other} is {@code null}
     */
    public CardListRequest(CardListRequest other) {
        Objects.requireNonNull(other, "A CardListRequest is required to copy from");
        this.trnname = other.trnname;
        this.title01 = other.title01;
        this.curdate = other.curdate;
        this.pgmname = other.pgmname;
        this.title02 = other.title02;
        this.curtime = other.curtime;
        this.pageno = other.pageno;
        this.acctsid = other.acctsid;
        this.cardsid = other.cardsid;
        this.rows = new ArrayList<>(other.rows);
        this.infomsg = other.infomsg;
        this.errmsg = other.errmsg;
        this.pageCursor = other.pageCursor;
        this.selectionFlags = other.selectionFlags;
        this.cardScreenState = new CardScreenState(other.cardScreenState);
        this.navigationContext = other.navigationContext;
        this.screenRowTable = other.screenRowTable;
        this.selectionErrorFlags = other.selectionErrorFlags;
        this.fieldMetadata = new LinkedHashMap<>(other.fieldMetadata);
    }

    // =================================================================================================
    // HEADER BAND ACCESSORS - 9 of the 45 payload members, hand-written so the copybook-to-field
    // correspondence stays visible at the point of use (no Lombok, AAP 0.5.6).
    // =================================================================================================

    /**
     * The {@code TRNNAME} payload member.
     *
     * @return {@code TRNNAME}, {@code PIC X(4)}; never {@code null}
     */
    public String getTrnname() {
        return trnname;
    }

    /**
     * Replaces the {@code TRNNAME} payload member.
     *
     * @param trnname {@code TRNNAME}, {@code PIC X(4)}; never {@code null}
     * @throws NullPointerException if {@code trnname} is {@code null}
     */
    public void setTrnname(String trnname) {
        this.trnname = requireField(trnname, "TRNNAME");
    }

    /**
     * The {@code TITLE01} payload member.
     *
     * @return {@code TITLE01}, {@code PIC X(40)}; never {@code null}
     */
    public String getTitle01() {
        return title01;
    }

    /**
     * Replaces the {@code TITLE01} payload member.
     *
     * @param title01 {@code TITLE01}, {@code PIC X(40)}; never {@code null}
     * @throws NullPointerException if {@code title01} is {@code null}
     */
    public void setTitle01(String title01) {
        this.title01 = requireField(title01, "TITLE01");
    }

    /**
     * The {@code CURDATE} payload member.
     *
     * @return {@code CURDATE}, {@code PIC X(8)}; never {@code null}
     */
    public String getCurdate() {
        return curdate;
    }

    /**
     * Replaces the {@code CURDATE} payload member.
     *
     * @param curdate {@code CURDATE}, {@code PIC X(8)}; never {@code null}
     * @throws NullPointerException if {@code curdate} is {@code null}
     */
    public void setCurdate(String curdate) {
        this.curdate = requireField(curdate, "CURDATE");
    }

    /**
     * The {@code PGMNAME} payload member.
     *
     * @return {@code PGMNAME}, {@code PIC X(8)}; never {@code null}
     */
    public String getPgmname() {
        return pgmname;
    }

    /**
     * Replaces the {@code PGMNAME} payload member.
     *
     * @param pgmname {@code PGMNAME}, {@code PIC X(8)}; never {@code null}
     * @throws NullPointerException if {@code pgmname} is {@code null}
     */
    public void setPgmname(String pgmname) {
        this.pgmname = requireField(pgmname, "PGMNAME");
    }

    /**
     * The {@code TITLE02} payload member.
     *
     * @return {@code TITLE02}, {@code PIC X(40)}; never {@code null}
     */
    public String getTitle02() {
        return title02;
    }

    /**
     * Replaces the {@code TITLE02} payload member.
     *
     * @param title02 {@code TITLE02}, {@code PIC X(40)}; never {@code null}
     * @throws NullPointerException if {@code title02} is {@code null}
     */
    public void setTitle02(String title02) {
        this.title02 = requireField(title02, "TITLE02");
    }

    /**
     * The {@code CURTIME} payload member.
     *
     * @return {@code CURTIME}, {@code PIC X(8)}; never {@code null}
     */
    public String getCurtime() {
        return curtime;
    }

    /**
     * Replaces the {@code CURTIME} payload member.
     *
     * @param curtime {@code CURTIME}, {@code PIC X(8)}; never {@code null}
     * @throws NullPointerException if {@code curtime} is {@code null}
     */
    public void setCurtime(String curtime) {
        this.curtime = requireField(curtime, "CURTIME");
    }

    /**
     * {@code PAGENO} - the only one of the three card maps to declare it, and it sits between
     * {@code CURTIME} and {@code ACCTSID}.
     *
     * @return {@code PAGENO}, {@code PIC X(3)}; never {@code null}
     */
    public String getPageno() {
        return pageno;
    }

    /**
     * Replaces the {@code PAGENO} payload member.
     *
     * @param pageno {@code PAGENO}, {@code PIC X(3)}; never {@code null}
     * @throws NullPointerException if {@code pageno} is {@code null}
     */
    public void setPageno(String pageno) {
        this.pageno = requireField(pageno, "PAGENO");
    }

    /**
     * The {@code ACCTSID} payload member - the account filter the operator typed.
     *
     * @return {@code ACCTSID}, {@code PIC X(11)} - the account filter; never {@code null}
     */
    public String getAcctsid() {
        return acctsid;
    }

    /**
     * Replaces the {@code ACCTSID} payload member.
     *
     * @param acctsid {@code ACCTSID}, {@code PIC X(11)}; never {@code null}
     * @throws NullPointerException if {@code acctsid} is {@code null}
     */
    public void setAcctsid(String acctsid) {
        this.acctsid = requireField(acctsid, "ACCTSID");
    }

    /**
     * The {@code CARDSID} payload member - the card filter the operator typed.
     *
     * @return {@code CARDSID}, {@code PIC X(16)} - the card filter; never {@code null}
     */
    public String getCardsid() {
        return cardsid;
    }

    /**
     * Replaces the {@code CARDSID} payload member.
     *
     * @param cardsid {@code CARDSID}, {@code PIC X(16)}; never {@code null}
     * @throws NullPointerException if {@code cardsid} is {@code null}
     */
    public void setCardsid(String cardsid) {
        this.cardsid = requireField(cardsid, "CARDSID");
    }

    // =================================================================================================
    // FOOTER BAND ACCESSORS - the last 2 of the 45 payload members.
    // =================================================================================================

    /**
     * The {@code INFOMSG} payload member - the informational line.
     *
     * @return {@code INFOMSG}, {@code PIC X(45)} - 45 here, 40 on the sibling card maps; never
     *         {@code null}
     */
    public String getInfomsg() {
        return infomsg;
    }

    /**
     * Replaces the {@code INFOMSG} payload member.
     *
     * @param infomsg {@code INFOMSG}, {@code PIC X(45)}; never {@code null}
     * @throws NullPointerException if {@code infomsg} is {@code null}
     */
    public void setInfomsg(String infomsg) {
        this.infomsg = requireField(infomsg, "INFOMSG");
    }

    /**
     * The {@code ERRMSG} payload member - the error line.
     *
     * @return {@code ERRMSG}, {@code PIC X(78)} - 78 here, 80 on the sibling card maps; never
     *         {@code null}
     */
    public String getErrmsg() {
        return errmsg;
    }

    /**
     * Replaces the {@code ERRMSG} payload member.
     *
     * @param errmsg {@code ERRMSG}, {@code PIC X(78)}; never {@code null}
     * @throws NullPointerException if {@code errmsg} is {@code null}
     */
    public void setErrmsg(String errmsg) {
        this.errmsg = requireField(errmsg, "ERRMSG");
    }

    // =================================================================================================
    // DETAIL ROW ACCESSORS - the remaining 34 payload members, and the place the asymmetry is enforced.
    // =================================================================================================

    /**
     * The seven detail rows in screen order, as an unmodifiable snapshot. Row 1 is at index 0 and is a
     * {@link FirstListRow}; rows 2 through 7 are at indices 1 to 6 and are {@link StopperListRow}.
     *
     * <p>The returned list cannot be modified and is not backed by this request's own list, so no caller
     * can reach the internal state through it.
     *
     * @return an unmodifiable list of exactly {@value #SCREEN_ROW_COUNT} rows; never {@code null}
     */
    public List<ListRow> getRows() {
        return List.copyOf(rows);
    }

    /**
     * Replaces all seven rows, enforcing the shape invariant.
     *
     * <p>The check is the whole point of this method: index 0 must be a {@link FirstListRow} and
     * indices 1 through 6 must be {@link StopperListRow}. A uniform seven-row list of one shape is
     * rejected, which is what stops the row-1 asymmetry from being "tidied away" by a caller.
     *
     * @param rows exactly {@value #SCREEN_ROW_COUNT} rows in screen order; never {@code null}
     * @throws NullPointerException     if {@code rows} is {@code null} or holds {@code null}
     * @throws IllegalArgumentException if the list is not exactly seven long, or if any row has the
     *                                  wrong shape for its position
     */
    public void setRows(List<ListRow> rows) {
        Objects.requireNonNull(rows, "The detail row list is required; use CardListRequest() for the "
                + "blank seven-row state");
        if (rows.size() != SCREEN_ROW_COUNT) {
            throw new IllegalArgumentException("The card list screen declares exactly "
                    + SCREEN_ROW_COUNT + " detail rows (WS-MAX-SCREEN-LINES VALUE 7, "
                    + "app/cbl/COCRDLIC.cbl:177) and cannot hold " + rows.size());
        }
        List<ListRow> validated = new ArrayList<>(SCREEN_ROW_COUNT);
        for (int rowNumber = FIRST_ROW_NUMBER; rowNumber <= LAST_ROW_NUMBER; rowNumber++) {
            validated.add(requireCorrectShape(rowNumber, rows.get(javaIndexOf(rowNumber))));
        }
        this.rows = validated;
    }

    /**
     * One detail row, addressed by its <strong>COBOL</strong> subscript. Subscript 1 is row 1 and
     * subscript 7 is row 7; there is no subscript 0.
     *
     * @param cobolRowNumber the subscript, 1 through {@value #SCREEN_ROW_COUNT}
     * @return the addressed row; never {@code null}
     * @throws IndexOutOfBoundsException if {@code cobolRowNumber} is outside 1 to 7
     */
    public ListRow row(int cobolRowNumber) {
        return rows.get(javaIndexOf(cobolRowNumber));
    }

    /**
     * Replaces one detail row, addressed by its COBOL subscript, enforcing the shape invariant for that
     * position.
     *
     * @param cobolRowNumber the subscript, 1 through {@value #SCREEN_ROW_COUNT}
     * @param row            the replacement; a {@link FirstListRow} for subscript 1 and a
     *                       {@link StopperListRow} for subscripts 2 through 7
     * @throws NullPointerException      if {@code row} is {@code null}
     * @throws IllegalArgumentException  if {@code row} has the wrong shape for {@code cobolRowNumber}
     * @throws IndexOutOfBoundsException if {@code cobolRowNumber} is outside 1 to 7
     */
    public void setRow(int cobolRowNumber, ListRow row) {
        int javaIndex = javaIndexOf(cobolRowNumber);
        rows.set(javaIndex, requireCorrectShape(cobolRowNumber, row));
    }

    /**
     * Row 1, at its exact type, so callers that specifically want the four-member shape do not have to
     * cast.
     *
     * @return row 1; never {@code null}
     */
    @JsonIgnore
    public FirstListRow firstRow() {
        return (FirstListRow) rows.get(javaIndexOf(FIRST_ROW_NUMBER));
    }

    /**
     * Row 7, at its exact type. Provided alongside {@link #firstRow()} because gate G33 requires the
     * first and the last element of every seven-element table to be independently assertable.
     *
     * @return row 7; never {@code null}
     */
    @JsonIgnore
    public StopperListRow lastRow() {
        return (StopperListRow) rows.get(javaIndexOf(LAST_ROW_NUMBER));
    }

    /**
     * The named attribute stopper of one row, or an empty {@link Optional} for row 1.
     *
     * <p>This is the safe way to read {@code CRDSTPn} without knowing the row's shape. It is an
     * exhaustive pattern switch over the sealed hierarchy, so there is no {@code default} arm and no
     * possibility of a third shape appearing unnoticed: if a row type were ever added, this method would
     * stop compiling.
     *
     * @param cobolRowNumber the subscript, 1 through {@value #SCREEN_ROW_COUNT}
     * @return the one-byte stopper for rows 2 through 7, or {@link Optional#empty()} for row 1
     * @throws IndexOutOfBoundsException if {@code cobolRowNumber} is outside 1 to 7
     */
    public Optional<String> stopperOf(int cobolRowNumber) {
        return switch (row(cobolRowNumber)) {
            case StopperListRow stopperRow -> Optional.of(stopperRow.crdStp());
            case FirstListRow ignoredFirstRow -> Optional.empty();
        };
    }

    /**
     * The number of payload members the seven rows contribute together:
     * {@code 4 + 6 x 5 = 34}. Derived by asking each row for its own member count rather than by
     * arithmetic on a constant, so the assertion tests the model and not a restatement of it.
     *
     * @return 34 for a correctly shaped request
     */
    @JsonIgnore
    public int rowFieldCount() {
        int total = 0;
        for (ListRow row : rows) {
            total += row.memberCount();
        }
        return total;
    }

    /**
     * The total number of payload members this request carries:
     * {@code 9 header + 34 rows + 2 footer = }{@value #FIELD_COUNT}, matching the 45 {@code xxxI} items
     * of {@code app/cpy-bms/COCRDLI.CPY} and the 45 name-labelled {@code DFHMDF} entries of
     * {@code app/bms/COCRDLI.bms}.
     *
     * @return 45 for a correctly shaped request
     */
    @JsonIgnore
    public int payloadFieldCount() {
        return HEADER_FIELD_COUNT + rowFieldCount() + FOOTER_FIELD_COUNT;
    }

    /**
     * The data bytes the seven rows contribute to the group image: {@code 29 + 180 = 209}. Derived from
     * the rows themselves for the same reason {@link #rowFieldCount()} is.
     *
     * @return 209 for a correctly shaped request
     */
    @JsonIgnore
    public int rowDataLength() {
        int total = 0;
        for (ListRow row : rows) {
            total += row.dataLength();
        }
        return total;
    }

    // =================================================================================================
    // CARRIER ACCESSORS.
    // =================================================================================================

    /**
     * The paging cursor, carried in the payload rather than in server-side state.
     *
     * @return the 58-byte paging cursor; never {@code null}
     */
    public PageCursor getPageCursor() {
        return pageCursor;
    }

    /**
     * Replaces the paging cursor.
     *
     * @param pageCursor the paging cursor; never {@code null}
     * @throws NullPointerException if {@code pageCursor} is {@code null}
     */
    public void setPageCursor(PageCursor pageCursor) {
        this.pageCursor = Objects.requireNonNull(pageCursor, "The paging cursor is required; use "
                + "PageCursor.firstPage() for the state COCRDLIC enters the screen in");
    }

    /**
     * The {@code WS-EDIT-SELECT} table of row action codes.
     *
     * @return the seven row action codes; never {@code null}
     */
    public SelectionFlags getSelectionFlags() {
        return selectionFlags;
    }

    /**
     * Replaces the {@code WS-EDIT-SELECT} table of row action codes.
     *
     * @param selectionFlags the seven row action codes; never {@code null}
     * @throws NullPointerException if {@code selectionFlags} is {@code null}
     */
    public void setSelectionFlags(SelectionFlags selectionFlags) {
        this.selectionFlags = Objects.requireNonNull(selectionFlags, "WS-EDIT-SELECT-FLAGS is "
                + "required; its declared VALUE is LOW-VALUES, so use SelectionFlags.lowValues()");
    }

    /**
     * Rebuilds the selection table from the seven {@code CRDSELn} payload fields, reproducing
     * {@code MOVE CRDSELnI OF CCRDLIAI TO WS-EDIT-SELECT(n)} for n = 1 through 7 -
     * {@code app/cbl/COCRDLIC.cbl:972-978}.
     */
    public void refreshSelectionFlagsFromRows() {
        this.selectionFlags = SelectionFlags.fromRows(getRows());
    }

    /**
     * A defensive copy of the {@code CC-WORK-AREA} carrier, so mutating what you get back cannot reach
     * this request's own state. {@link CardScreenState} is the one mutable carrier here, which is
     * exactly why it is copied on the way out as well as on the way in.
     *
     * @return a copy of the card screen state; never {@code null}
     */
    public CardScreenState getCardScreenState() {
        return new CardScreenState(cardScreenState);
    }

    /**
     * Stores a defensive copy of the supplied {@code CC-WORK-AREA} carrier.
     *
     * @param cardScreenState the card screen state; never {@code null}
     * @throws NullPointerException if {@code cardScreenState} is {@code null}
     */
    public void setCardScreenState(CardScreenState cardScreenState) {
        Objects.requireNonNull(cardScreenState, "The CC-WORK-AREA carrier is required; use "
                + "new CardScreenState() for the initial state");
        this.cardScreenState = new CardScreenState(cardScreenState);
    }

    /**
     * The {@code CARDDEMO-COMMAREA} carrier.
     *
     * @return the {@code CARDDEMO-COMMAREA} carrier; never {@code null}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    /**
     * Replaces the {@code CARDDEMO-COMMAREA} carrier.
     *
     * @param navigationContext the {@code CARDDEMO-COMMAREA} carrier; never {@code null}
     * @throws NullPointerException if {@code navigationContext} is {@code null}
     */
    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = Objects.requireNonNull(navigationContext, "The CARDDEMO-COMMAREA "
                + "carrier is required; use NavigationContext.empty() for the initial state");
    }

    /**
     * {@code 88 CDEMO-PGM-ENTER VALUE 0.} - first entry, so the screen is painted rather than
     * validated.
     *
     * @return {@code true} when {@code CDEMO-PGM-CONTEXT} is
     *         {@value NavigationContext#PGM_CONTEXT_ENTER}
     */
    @JsonIgnore
    public boolean isEnter() {
        return navigationContext.isEnter();
    }

    /**
     * {@code 88 CDEMO-PGM-REENTER VALUE 1.} - re-entry, so what the operator typed is validated and,
     * on failure, highlighted.
     *
     * <p>This is the flag that gates the {@code CSSETATY} error highlight, which is why it is explicit
     * on the request rather than inferred from whether any field happens to be populated.
     *
     * @return {@code true} when {@code CDEMO-PGM-CONTEXT} is
     *         {@value NavigationContext#PGM_CONTEXT_REENTER}
     */
    @JsonIgnore
    public boolean isReenter() {
        return navigationContext.isReenter();
    }

    // =================================================================================================
    // NON-SERIALISED METADATA ACCESSORS.
    // =================================================================================================

    /**
     * The {@code WS-SCREEN-ROWS} browse result table, deliberately off the wire.
     *
     * @return the 196-byte browse result table; never {@code null}
     */
    @JsonIgnore
    public ScreenRowTable getScreenRowTable() {
        return screenRowTable;
    }

    /**
     * Replaces the {@code WS-SCREEN-ROWS} browse result table.
     *
     * @param screenRowTable the browse result table; never {@code null}
     * @throws NullPointerException if {@code screenRowTable} is {@code null}
     */
    public void setScreenRowTable(ScreenRowTable screenRowTable) {
        this.screenRowTable = Objects.requireNonNull(screenRowTable, "WS-SCREEN-ROWS is required; use "
                + "ScreenRowTable.lowValues() for the state MOVE LOW-VALUES TO WS-ALL-ROWS leaves");
    }

    /**
     * The {@code WS-EDIT-SELECT-ERRORS} highlight table, deliberately off the wire.
     *
     * @return the seven per-row highlight flags; never {@code null}
     */
    @JsonIgnore
    public SelectionErrorFlags getSelectionErrorFlags() {
        return selectionErrorFlags;
    }

    /**
     * Replaces the {@code WS-EDIT-SELECT-ERRORS} highlight table.
     *
     * @param selectionErrorFlags the per-row highlight flags; never {@code null}
     * @throws NullPointerException if {@code selectionErrorFlags} is {@code null}
     */
    public void setSelectionErrorFlags(SelectionErrorFlags selectionErrorFlags) {
        this.selectionErrorFlags = Objects.requireNonNull(selectionErrorFlags,
                "WS-EDIT-SELECT-ERROR-FLAGS is required; use SelectionErrorFlags.none()");
    }

    /**
     * An unmodifiable snapshot of the per-field {@code xxxL}/{@code xxxF}/{@code xxxA} metadata, keyed
     * by {@code DFHMDF} label and in insertion order.
     *
     * @return the metadata map; never {@code null}, possibly empty
     */
    @JsonIgnore
    public Map<String, FieldMetadata> getFieldMetadata() {
        return Map.copyOf(fieldMetadata);
    }

    /**
     * Replaces the whole metadata map with a defensive copy.
     *
     * @param fieldMetadata the metadata, keyed by {@code DFHMDF} label; never {@code null} and never
     *                      holding {@code null}
     * @throws NullPointerException if {@code fieldMetadata} is {@code null} or holds {@code null}
     */
    public void setFieldMetadata(Map<String, FieldMetadata> fieldMetadata) {
        Objects.requireNonNull(fieldMetadata, "The per-field metadata map is required; pass an empty "
                + "map rather than null when no field metadata is available");
        Map<String, FieldMetadata> copied = new LinkedHashMap<>();
        for (Map.Entry<String, FieldMetadata> entry : fieldMetadata.entrySet()) {
            String label = Objects.requireNonNull(entry.getKey(), "A DFHMDF label is required as the "
                    + "metadata key");
            copied.put(label, Objects.requireNonNull(entry.getValue(), "Field metadata is required "
                    + "for label '" + label + "'"));
        }
        this.fieldMetadata = copied;
    }

    /**
     * Records the {@code xxxL}/{@code xxxF}/{@code xxxA} metadata for one field.
     *
     * @param metadata the metadata, whose {@link FieldMetadata#dfhmdfName()} is used as the key; never
     *                 {@code null}
     * @throws NullPointerException if {@code metadata} is {@code null}
     */
    public void putFieldMetadata(FieldMetadata metadata) {
        Objects.requireNonNull(metadata, "Field metadata is required");
        fieldMetadata.put(metadata.dfhmdfName(), metadata);
    }

    /**
     * The metadata recorded for one field, if any.
     *
     * @param dfhmdfName the field's {@code DFHMDF} label; never {@code null}
     * @return the metadata, or {@link Optional#empty()} when none was recorded
     * @throws NullPointerException if {@code dfhmdfName} is {@code null}
     */
    public Optional<FieldMetadata> fieldMetadataOf(String dfhmdfName) {
        Objects.requireNonNull(dfhmdfName, "A DFHMDF label is required to look up field metadata");
        return Optional.ofNullable(fieldMetadata.get(dfhmdfName));
    }

    // =================================================================================================
    // FIXED-WIDTH NORMALISATION - every cross-width move goes through the codec's explicit PIC X
    // helper, never through plain Java assignment, so the direction of truncation and the pad byte are
    // chosen deliberately per target PICTURE rather than inherited from the platform (practice B11).
    // =================================================================================================

    /**
     * A copy of this request with all forty-five payload members re-rendered at their declared widths.
     *
     * <p>Each field goes through {@link FixedWidthCodec#movePicX(String, int)}, which pads on the right
     * with the charset's space byte and truncates on the right - exactly what a COBOL alphanumeric
     * {@code MOVE} into a narrower {@code PIC X} item does. No field is trimmed, because the COBOL does
     * not trim.
     *
     * <p>The carriers and the non-serialised metadata are copied across unchanged; each of them owns its
     * own widths.
     *
     * @param codec the fixed-width codec whose charset governs the pad byte; never {@code null}
     * @return a normalised copy, never {@code null}
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public CardListRequest normalised(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to normalise a request; the "
                + "charset decides the pad byte and must never be the platform default");
        CardListRequest normalised = new CardListRequest(this);
        normalised.trnname = codec.movePicX(trnname, TRNNAME_LENGTH);
        normalised.title01 = codec.movePicX(title01, TITLE01_LENGTH);
        normalised.curdate = codec.movePicX(curdate, CURDATE_LENGTH);
        normalised.pgmname = codec.movePicX(pgmname, PGMNAME_LENGTH);
        normalised.title02 = codec.movePicX(title02, TITLE02_LENGTH);
        normalised.curtime = codec.movePicX(curtime, CURTIME_LENGTH);
        normalised.pageno = codec.movePicX(pageno, PAGENO_LENGTH);
        normalised.acctsid = codec.movePicX(acctsid, ACCTSID_LENGTH);
        normalised.cardsid = codec.movePicX(cardsid, CARDSID_LENGTH);
        normalised.infomsg = codec.movePicX(infomsg, INFOMSG_LENGTH);
        normalised.errmsg = codec.movePicX(errmsg, ERRMSG_LENGTH);
        List<ListRow> normalisedRows = new ArrayList<>(SCREEN_ROW_COUNT);
        for (ListRow row : rows) {
            normalisedRows.add(row.normalised(codec));
        }
        normalised.rows = normalisedRows;
        return normalised;
    }

    // =================================================================================================
    // 1-BASED TO 0-BASED CONVERSION - the single place the shift happens, named for what it is. AAP
    // 0.7.2 calls OCCURS indexing the top defect risk of the whole migration, and the reason it is
    // named rather than inlined is that "rows.get(n - 1)" scattered through a file is exactly how the
    // off-by-one gets in.
    // =================================================================================================

    /**
     * Converts a 1-based COBOL row subscript into the 0-based Java index of the same element.
     *
     * <p>Subscript 1 maps to index 0 and subscript {@value #SCREEN_ROW_COUNT} maps to index
     * {@code SCREEN_ROW_COUNT - 1}. There is no subscript 0 and there is no subscript 8; both are
     * rejected rather than clamped, because a silently clamped subscript would read the wrong row and
     * report success.
     *
     * @param cobolRowNumber the COBOL subscript, 1 through {@value #SCREEN_ROW_COUNT} inclusive
     * @return the corresponding 0-based Java index
     * @throws IndexOutOfBoundsException if {@code cobolRowNumber} is outside 1 to
     *                                   {@value #SCREEN_ROW_COUNT}
     */
    public static int javaIndexOf(int cobolRowNumber) {
        if (cobolRowNumber < FIRST_ROW_NUMBER || cobolRowNumber > LAST_ROW_NUMBER) {
            throw new IndexOutOfBoundsException("Row subscript " + cobolRowNumber + " is outside "
                    + FIRST_ROW_NUMBER + ".." + LAST_ROW_NUMBER + "; the card list declares OCCURS "
                    + SCREEN_ROW_COUNT + " TIMES, COBOL subscripts are 1-based, and there is no "
                    + "subscript 0");
        }
        return cobolRowNumber - FIRST_ROW_NUMBER;
    }

    /**
     * Converts a 0-based Java index back into the 1-based COBOL subscript of the same element - the
     * inverse of {@link #javaIndexOf(int)}, provided so that a diagnostic or a test can report the
     * subscript the COBOL would use.
     *
     * @param javaIndex the Java index, 0 through {@code SCREEN_ROW_COUNT - 1} inclusive
     * @return the corresponding 1-based COBOL subscript
     * @throws IndexOutOfBoundsException if {@code javaIndex} is outside 0 to
     *                                   {@code SCREEN_ROW_COUNT - 1}
     */
    public static int cobolRowNumberOf(int javaIndex) {
        if (javaIndex < 0 || javaIndex >= SCREEN_ROW_COUNT) {
            throw new IndexOutOfBoundsException("Java index " + javaIndex + " is outside 0.."
                    + (SCREEN_ROW_COUNT - 1) + "; the card list holds exactly " + SCREEN_ROW_COUNT
                    + " rows");
        }
        return javaIndex + FIRST_ROW_NUMBER;
    }

    // =================================================================================================
    // INTERNAL HELPERS.
    // =================================================================================================

    /**
     * The seven blank rows of the correct shapes: one {@link FirstListRow} followed by six
     * {@link StopperListRow}.
     *
     * @return a mutable list of exactly seven correctly shaped blank rows
     */
    private static List<ListRow> blankRows() {
        List<ListRow> blank = new ArrayList<>(SCREEN_ROW_COUNT);
        blank.add(FirstListRow.blank());
        for (int rowNumber = FIRST_ROW_NUMBER + 1; rowNumber <= LAST_ROW_NUMBER; rowNumber++) {
            blank.add(StopperListRow.blank());
        }
        return blank;
    }

    /**
     * Checks that a row has the shape its position requires, and returns it so the check can be used
     * inline.
     *
     * @param cobolRowNumber the position, 1 through {@value #SCREEN_ROW_COUNT}
     * @param row            the candidate row
     * @return {@code row}, unchanged
     * @throws NullPointerException     if {@code row} is {@code null}
     * @throws IllegalArgumentException if a {@link StopperListRow} is offered for row 1 or a
     *                                 {@link FirstListRow} for rows 2 through 7
     */
    private static ListRow requireCorrectShape(int cobolRowNumber, ListRow row) {
        Objects.requireNonNull(row, "A detail row is required for screen row " + cobolRowNumber);
        boolean stopperExpected = cobolRowNumber != FIRST_ROW_NUMBER;
        if (row.hasStopper() != stopperExpected) {
            throw new IllegalArgumentException("Screen row " + cobolRowNumber + " requires a "
                    + (stopperExpected ? "StopperListRow with 5 members" : "FirstListRow with 4 members")
                    + " but a " + row.getClass().getSimpleName() + " with " + row.memberCount()
                    + " was supplied. COCRDLI declares no CRDSTP1 - app/cpy-bms/COCRDLI.CPY:78 is "
                    + "followed directly by :79 - so row 1 carries 4 fields and rows 2 through 7 "
                    + "carry 5; the field count only reconciles to " + FIELD_COUNT + " with that "
                    + "asymmetry intact");
        }
        return row;
    }

    /**
     * Rejects {@code null} for a map field, naming the {@code DFHMDF} label in the message.
     *
     * @param value      the candidate value
     * @param dfhmdfName the field's {@code DFHMDF} label
     * @return {@code value}, unchanged
     * @throws NullPointerException if {@code value} is {@code null}
     */
    private static String requireField(String value, String dfhmdfName) {
        return Objects.requireNonNull(value, dfhmdfName + " is required; a blank screen field holds "
                + "spaces or LOW-VALUES, never null");
    }

    /**
     * A run of spaces of the given length - the state {@code INITIALIZE} and a
     * {@code MOVE SPACES} leave an alphanumeric item in. Deliberately distinct from
     * {@link CardScreenState#lowValues(int)}: spaces are 0x20 under US-ASCII and 0x40 under IBM037,
     * whereas {@code LOW-VALUES} is 0x00 under both, and this file never treats the two as the same
     * thing.
     *
     * @param length the declared width; at least 1
     * @return a string of exactly {@code length} spaces
     * @throws IllegalArgumentException if {@code length} is below 1
     */
    public static String spaces(int length) {
        if (length < 1) {
            throw new IllegalArgumentException("A declared width of " + length + " is not usable; "
                    + "every screen field occupies at least 1 byte");
        }
        return String.valueOf(SPACE).repeat(length);
    }

    /**
     * Whether a span is exactly its declared width and holds nothing but the given character - the test
     * a COBOL {@code 88}-level against a figurative constant performs.
     *
     * <p>The width check is deliberate. A COBOL field is always exactly as wide as it is declared, so a
     * shorter Java string is not a partially filled field, it is a wrong one, and reporting
     * {@code LOW-VALUES} for it would be a false positive.
     *
     * @param span           the value to test
     * @param expected       the character every byte must equal
     * @param declaredLength the field's declared width
     * @return {@code true} only when the span is exactly {@code declaredLength} long and uniform
     */
    private static boolean isEvery(String span, char expected, int declaredLength) {
        if (span.length() != declaredLength) {
            return false;
        }
        for (int position = 0; position < declaredLength; position++) {
            if (span.charAt(position) != expected) {
                return false;
            }
        }
        return true;
    }

    // =================================================================================================
    // VALUE SEMANTICS - so that a parity case can compare a decoded request against an expected one in
    // a single assertion, field by field, rather than forty-five at a time.
    // =================================================================================================

    /**
     * Value equality across every payload member, every carrier and every metadata table. Two requests
     * are equal when they would produce byte-identical group images and carry identical state.
     *
     * @param other the object to compare against
     * @return {@code true} when {@code other} is a {@code CardListRequest} with identical state
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CardListRequest request)) {
            return false;
        }
        return trnname.equals(request.trnname)
                && title01.equals(request.title01)
                && curdate.equals(request.curdate)
                && pgmname.equals(request.pgmname)
                && title02.equals(request.title02)
                && curtime.equals(request.curtime)
                && pageno.equals(request.pageno)
                && acctsid.equals(request.acctsid)
                && cardsid.equals(request.cardsid)
                && rows.equals(request.rows)
                && infomsg.equals(request.infomsg)
                && errmsg.equals(request.errmsg)
                && pageCursor.equals(request.pageCursor)
                && selectionFlags.equals(request.selectionFlags)
                && cardScreenState.equals(request.cardScreenState)
                && navigationContext.equals(request.navigationContext)
                && screenRowTable.equals(request.screenRowTable)
                && selectionErrorFlags.equals(request.selectionErrorFlags)
                && fieldMetadata.equals(request.fieldMetadata);
    }

    /**
     * Consistent with {@link #equals(Object)} across the same state.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(trnname, title01, curdate, pgmname, title02, curtime, pageno, acctsid,
                cardsid, rows, infomsg, errmsg, pageCursor, selectionFlags, cardScreenState,
                navigationContext, screenRowTable, selectionErrorFlags, fieldMetadata);
    }

    /**
     * A diagnostic summary of the request's <em>shape</em>: the transaction, the map, the payload member
     * count, the group width and the row shapes.
     *
     * <p>Field <em>content</em> is not included, and that is not masking - no value is altered,
     * abbreviated or substituted anywhere in this class, and the JSON projection carries all forty-five
     * fields exactly as the symbolic map declares them. It is simply that this screen's payload holds up
     * to seven full card numbers and seven account identifiers, and a value that lands in a log because
     * a diagnostic was interpolated somewhere is a value nobody chose to log. The sibling
     * {@link CardScreenState} in this package takes the same position for the same reason.
     *
     * @return for example
     *         {@code CardListRequest[tranid=CCLI, map=CCRDLIA, payloadFields=45, groupLength=797,
     *         rows=4+6x5, page=1]}
     */
    @Override
    public String toString() {
        return "CardListRequest[tranid=" + TRANSACTION_ID
                + ", map=" + MAP_NAME
                + ", payloadFields=" + payloadFieldCount()
                + ", groupLength=" + GROUP_LENGTH
                + ", rows=" + firstRow().memberCount() + "+" + (SCREEN_ROW_COUNT - 1) + "x"
                + STOPPER_ROW_FIELD_COUNT
                + ", page=" + pageCursor.screenNum()
                + "]";
    }
}
