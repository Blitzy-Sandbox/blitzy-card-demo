package com.vsergeychik.carddemo.card.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.vsergeychik.carddemo.card.dto.CardListRequest.PageCursor;
import com.vsergeychik.carddemo.common.DiagnosticText;
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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The outbound payload of {@code GET /api/cards} - the Java projection of the {@code CCRDLIAO}
 * output symbolic map that CICS transaction {@code CCLI} sends.
 *
 * <h2>Provenance</h2>
 * <table>
 *   <caption>The authorities this class is transcribed from</caption>
 *   <tr><th>Concern</th><th>Authority</th></tr>
 *   <tr><td>Behaviour</td><td>{@code app/cbl/COCRDLIC.cbl} (1,459 lines), transaction {@code CCLI},
 *       program literal {@code LIT-THISPGM = 'COCRDLIC'}</td></tr>
 *   <tr><td>Field names and widths</td><td>{@code app/cpy-bms/COCRDLI.CPY} (560 lines), group
 *       {@code 01 CCRDLIAO REDEFINES CCRDLIAI} at <strong>L289</strong></td></tr>
 *   <tr><td>Screen geometry and which fields exist</td><td>{@code app/bms/COCRDLI.bms} (344 lines),
 *       mapset {@code COCRDLI}, map {@code CCRDLIA}</td></tr>
 *   <tr><td>Error highlighting</td><td>{@code app/cpy/CSSETATY.cpy} L18-27</td></tr>
 *   <tr><td>Navigation targets</td><td>{@code app/cpy/CVCRD01Y.cpy} L21-24</td></tr>
 * </table>
 *
 * <p>This is a <strong>like-for-like migration</strong>. Nothing here is redesigned: no field is
 * renamed, no width is unified, no absent field is supplied and no present field is dropped. Where
 * the map does something surprising, this class does the same surprising thing and says so.
 *
 * <h2>Why the output group and not the input group</h2>
 * {@code CCRDLIAO} <em>redefines</em> {@code CCRDLIAI}, so both groups describe the same 797 bytes
 * through different item names. Per field the input group declares
 * {@code xxxL COMP PIC S9(4)} (2 bytes) + {@code xxxF PICTURE X} (1, with {@code xxxA} redefining
 * it) + {@code FILLER PICTURE X(4)} + {@code xxxI PIC X(n)}; the output group declares
 * {@code FILLER PICTURE X(3)} + {@code xxxC} + {@code xxxP} + {@code xxxH} + {@code xxxV} +
 * {@code xxxO PIC X(n)}. Both strides are therefore {@code 7 + n}, which is exactly what
 * {@code REDEFINES} requires.
 *
 * <p>The binding consequence: <strong>every payload member of this class comes from an
 * {@code xxxO} item.</strong> The {@code xxxI} items are the inbound half and belong to
 * {@code CardListRequest}; the field set and the widths are identical between the two, and only the
 * item source and the metadata shape differ.
 *
 * <p>The overlay also fixes what this class can and cannot address. The output group's
 * {@code FILLER X(3)} covers the input group's {@code xxxL} (bytes 0-1) and {@code xxxF}/{@code xxxA}
 * (byte 2), so the length item and the attribute item are <em>not</em> reachable through
 * {@code CCRDLIAO} and are not modelled here - {@code app/cbl/COCRDLIC.cbl} qualifies every one of
 * those writes with {@code OF CCRDLIAI} (for example {@code MOVE DFHBMFSE TO CRDSEL1A OF CCRDLIAI}
 * at L761 and {@code MOVE -1 TO CRDSEL2L OF CCRDLIAI} at L770). The four bytes this class does own,
 * {@code xxxC}/{@code xxxP}/{@code xxxH}/{@code xxxV}, overlay the input group's
 * {@code FILLER X(4)} and are the {@code DSATTS}/{@code MAPATTS} set in byte order:
 * <strong>C = COLOR, P = PS, H = HILIGHT, V = VALIDN</strong>.
 *
 * <h2>Exactly 45 payload members, and why the count only balances with the row-1 asymmetry</h2>
 * {@code app/bms/COCRDLI.bms} declares <strong>72</strong> {@code DFHMDF} entries of which only
 * <strong>45</strong> carry a name label; the other 27 are unnamed literal {@code INITIAL} entries -
 * screen furniture such as {@code 'Tran:'}, the column rules and the
 * {@code '  F3=Exit F7=Backward  F8=Forward'} legend at {@code POS=(24,1)} - and they get no Java
 * field, because a field with no name has no symbolic-map item to project.
 *
 * <p>The 45 reconcile as <strong>9 header + 4 (row 1) + 6 x 5 (rows 2-7) + 2 footer = 45</strong>.
 * <strong>Row 1 exposes four fields; rows 2 through 7 expose five.</strong> Row 1 has
 * <em>no</em> {@code CRDSTP1O}. That absence is verified twice and is the single most likely thing
 * to be silently "fixed" into a uniform 7x5 array:
 * <ol>
 *   <li>In the symbolic map, {@code app/cpy-bms/COCRDLI.CPY:78-79} runs
 *       {@code 02 CRDSEL1I PIC X(1).} straight into {@code 02 ACCTNO1L COMP PIC S9(4).} with no
 *       {@code CRDSTP1} items between them; the output group mirrors it at L350 to L356.</li>
 *   <li>In the mapset, the name-labelled {@code DFHMDF} list jumps from
 *       {@code CRDSEL1 ACCTNO1 CRDNUM1 CRDSTS1} straight to {@code CRDSEL2 CRDSTP2 ...}.</li>
 * </ol>
 * A count of 44 or 46 means the asymmetry has been got wrong. {@link #ROW_1_FIELD_COUNT} and
 * {@link #ROW_N_FIELD_COUNT} state it in the API, and {@link #MAP_FIELDS} contains no
 * {@code CRDSTP1O} descriptor at all.
 *
 * <p>In rows 2 to 7 the extra field sits <strong>second, not last</strong>:
 * {@code CRDSELnO}, {@code CRDSTPnO}, {@code ACCTNOnO}, {@code CRDNUMnO}, {@code CRDSTSnO}. Position
 * determines byte offset, so ordering this group any other way silently moves 180 bytes.
 *
 * <h2>Widths that are deliberately not unified</h2>
 * {@code INFOMSGO} is {@code X(45)} and {@code ERRMSGO} is {@code X(78)} on this map, where the two
 * sibling card maps {@code COCRDSL} and {@code COCRDUP} declare 40 and 80. {@code COCRDLI} also has
 * <strong>no {@code FKEYS} field at all</strong> (it is 75 characters wide in {@code COCRDSL} and 21
 * in {@code COCRDUP}), and it is the only card map carrying {@code PAGENOO X(3)}. Those divergences
 * are the contract, which is why no shared abstract base exists across the three card response
 * types: a base class could only hold the widths by making them wrong somewhere.
 *
 * <h2>Statelessness</h2>
 * CICS is pseudo-conversational, so no server-side state may stand behind this payload. There is no
 * {@code HttpSession}, no {@code @SessionAttributes}, no server cache, no static mutable field and
 * no {@code ThreadLocal} anywhere in this class. Everything the next request needs travels in the
 * payload:
 * <ul>
 *   <li>the 58-byte {@link PageCursor}, the projection of {@code 01 WS-THIS-PROGCOMMAREA}
 *       [{@code app/cbl/COCRDLIC.cbl:229-248}], which the client echoes back to page forward or
 *       backward;</li>
 *   <li>the {@link CardScreenState} work area, because {@code COCRDLIC} copies {@code CVCRD01Y} at
 *       L221;</li>
 *   <li>the {@link NavigationContext} commarea, because {@code COCRDLIC} copies {@code COCOM01Y} at
 *       L227;</li>
 *   <li>{@link #getNextProgram()}, {@link #getNextMapset()} and {@link #getNextMap()}, which replace
 *       the program's three {@code EXEC CICS XCTL} transfers.</li>
 * </ul>
 *
 * <h2>The three XCTL sites become three response fields</h2>
 * {@code COCRDLIC} is the only card program with three transfers in two different shapes, and all
 * three collapse onto the same stateless triple - the client reads it and issues the follow-up call.
 * There is no server-side forward, no redirect chain and no session affinity.
 * <ul>
 *   <li>{@code app/cbl/COCRDLIC.cbl:402} - {@code XCTL PROGRAM(LIT-MENUPGM)}, a <em>literal</em>
 *       target ({@code 'COMEN01C'}), taken on the exit path.</li>
 *   <li>{@code app/cbl/COCRDLIC.cbl:538} - {@code XCTL PROGRAM(CCARD-NEXT-PROG)}, a <em>field</em>
 *       target, after a row was selected with {@code 'S'} (card detail).</li>
 *   <li>{@code app/cbl/COCRDLIC.cbl:566} - {@code XCTL PROGRAM(CCARD-NEXT-PROG)} again, after a row
 *       was selected with {@code 'U'} (card update).</li>
 * </ul>
 * The three fields are <strong>opaque tokens</strong>: no validation against a known-program list,
 * no case normalisation and no trimming. {@code CardSelectController} deliberately preserves a
 * source defect in which {@code LIT-CCLISTMAP} is {@code 'CCRDSLA'} at
 * {@code app/cbl/COCRDSLC.cbl:178} although this map is really {@code 'CCRDLIA'}; "correcting" a
 * token here would break that preservation.
 *
 * <h2>Page size is behaviour, not configuration</h2>
 * {@link #PAGE_SIZE} is {@code 7}, transcribed from
 * {@code 05 WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} [{@code app/cbl/COCRDLIC.cbl:177-178}]. It
 * is a compile-time constant with no configuration path: not an {@code application.yml} key, not a
 * {@code @Value}, not a system property and not a request parameter. Making it tunable would change
 * observable behaviour, because the program's own paging arithmetic compares against it
 * ({@code IF WS-SCRN-COUNTER = WS-MAX-SCREEN-LINES} at L1191).
 *
 * <h2>Initial state is LOW-VALUES, not spaces and not null</h2>
 * {@code 1100-SCREEN-INIT} opens with {@code MOVE LOW-VALUES TO CCRDLIAO}
 * [{@code app/cbl/COCRDLIC.cbl:643}], which sets all 797 bytes - payload items, {@code FILLER}
 * spans and the {@code xxxC}/{@code xxxP}/{@code xxxH}/{@code xxxV} quads alike - to {@code x'00'}.
 * A freshly constructed instance therefore holds {@code LOW-VALUES} in every payload member, which
 * is a genuine third state distinct from {@code SPACES} and from Java {@code null}. A subsequent
 * write pads with <em>spaces</em>, because that is the {@code PIC X} move rule; the two are not the
 * same and this class never conflates them.
 *
 * <h2>Metadata that is deliberately not on the JSON wire</h2>
 * Two structures are addressable through the API but excluded from serialisation, because they are
 * 3270 presentation attributes rather than data:
 * <ul>
 *   <li>the per-field {@code xxxC}/{@code xxxP}/{@code xxxH}/{@code xxxV} quad, reached through
 *       {@link #fieldAttributes(String)}. {@link FieldAttributeSetter} needs the {@code xxxC} item as
 *       its target for {@link BmsAttributes#DFHRED} and the {@code xxxO} item as its target for
 *       {@code '*'}, so both must be reachable - see {@link #applyHighlight(FieldHighlight)};</li>
 *   <li>the seven-element {@code WS-EDIT-SELECT-ERRORS} array
 *       [{@code app/cbl/COCRDLIC.cbl:83-88}], reached through
 *       {@link #isWsRowSelectError(int)}. It drives per-row highlighting and has no symbolic-map
 *       item of its own.</li>
 * </ul>
 *
 * <h2>Two source facts recorded rather than corrected</h2>
 * <ol>
 *   <li>The plan's summary states that all seventeen mapsets declare
 *       {@code CTRL=(ALARM,FREEKB) EXTATT=YES} on {@code DFHMSD}. This one does not.
 *       {@code COCRDLI DFHMSD LANG=COBOL, MODE=INOUT, STORAGE=AUTO, TIOAPFX=YES, TYPE=&&SYSPARM}
 *       [{@code app/bms/COCRDLI.bms:20-24}] carries <strong>neither</strong> {@code CTRL=}
 *       <strong>nor</strong> {@code EXTATT=}; it is
 *       {@code CCRDLIA DFHMDI CTRL=(FREEKB), DSATTS=(COLOR,HILIGHT,PS,VALIDN),
 *       MAPATTS=(COLOR,HILIGHT,PS,VALIDN), SIZE=(24,80)} [L25-28] that carries them.
 *       {@code SIZE=(24,80)} is confirmed. The discrepancy is documented, not silently reconciled.</li>
 *   <li>{@code app/cbl/COCRDLIC.cbl:274} reads {@code *COPY COCRDSL.} - <strong>commented
 *       out</strong> - while {@code COPY COCRDLI.} at L276 is live. {@code COCRDLIC} therefore has no
 *       {@code COCRDSL} data area at all, even though the card-select map is named as this
 *       controller's navigation target. The target is reached through {@link #getNextMapset()} and
 *       {@link #getNextMap()} as opaque tokens; the commented-out {@code COPY} is left exactly as
 *       the source has it.</li>
 * </ol>
 *
 * <h2>Numeric policy</h2>
 * Every one of the 45 payload members is {@code PIC X(n)} and maps to {@code String}. This screen
 * carries no scaled or monetary field, so there is no {@code BigDecimal} here and no rounding of any
 * kind - the rounding-mode prohibition is met by absence. The only numeric items in the whole class
 * are the {@link PageCursor}'s two {@code PIC 9(11)} account identifiers ({@code long}) and its two
 * {@code PIC 9(1)} counters ({@code int}). No {@code double} and no {@code float} appears anywhere,
 * and no fixed-width numeric span is ever decoded with {@code Integer.parseInt}: every such span goes
 * through {@link FixedWidthCodec}.
 *
 * <h2>Construction and testability</h2>
 * No Spring context, no builder, no framework and no mapper is involved. A unit test, a
 * {@code MockMvc} test or the {@code COCRDLIC} parity test constructs an instance directly, sets what
 * it needs and inspects the result. The seven-element structures are exposed as unmodifiable lists or
 * as defensive copies, so no caller can retain a reference into this object's state.
 *
 * @see CardListRequest
 * @see CardScreenState
 * @see NavigationContext
 * @see FieldAttributeSetter
 */
public final class CardListResponse {

    // =================================================================================================
    // Declared widths, one named constant per symbolic-map item.
    //
    // Every value is the n of that item's PIC X(n) in app/cpy-bms/COCRDLI.CPY, and the seven row
    // groups share one constant per column because the copybook declares the same width in each row.
    // Naming them rather than inlining them is what lets a reviewer check a width against the
    // copybook without reading any logic.
    // =================================================================================================

    /** {@code TRNNAMEO PIC X(4)} [{@code COCRDLI.CPY:296}] - the transaction identifier. */
    public static final int TRNNAMEO_LENGTH = 4;

    /** {@code TITLE01O PIC X(40)} [{@code COCRDLI.CPY:302}] - the upper title line. */
    public static final int TITLE01O_LENGTH = 40;

    /** {@code CURDATEO PIC X(8)} [{@code COCRDLI.CPY:308}] - the {@code mm/dd/yy} date. */
    public static final int CURDATEO_LENGTH = 8;

    /** {@code PGMNAMEO PIC X(8)} [{@code COCRDLI.CPY:314}] - the program name. */
    public static final int PGMNAMEO_LENGTH = 8;

    /** {@code TITLE02O PIC X(40)} [{@code COCRDLI.CPY:320}] - the lower title line. */
    public static final int TITLE02O_LENGTH = 40;

    /** {@code CURTIMEO PIC X(8)} [{@code COCRDLI.CPY:326}] - the {@code hh:mm:ss} time. */
    public static final int CURTIMEO_LENGTH = 8;

    /**
     * {@code PAGENOO PIC X(3)} [{@code COCRDLI.CPY:332}] - the page number.
     *
     * <p>This item exists on {@code COCRDLI} alone among the three card maps, and it sits between
     * {@code CURTIMEO} and {@code ACCTSIDO}. Its position is part of the byte layout and must not be
     * moved to the end of the header for tidiness.
     */
    public static final int PAGENOO_LENGTH = 3;

    /** {@code ACCTSIDO PIC X(11)} [{@code COCRDLI.CPY:338}] - the account-number filter. */
    public static final int ACCTSIDO_LENGTH = 11;

    /** {@code CARDSIDO PIC X(16)} [{@code COCRDLI.CPY:344}] - the card-number filter. */
    public static final int CARDSIDO_LENGTH = 16;

    /**
     * {@code CRDSELnO PIC X(1)} - the per-row selection character, one per row for rows 1 to 7
     * [{@code COCRDLI.CPY:350, 374, 404, 434, 464, 494, 524}].
     */
    public static final int CRDSEL_LENGTH = 1;

    /**
     * {@code CRDSTPnO PIC X(1)} - the per-row hidden selection-type character, present for rows
     * <strong>2 to 7 only</strong> [{@code COCRDLI.CPY:380, 410, 440, 470, 500, 530}].
     *
     * <p>There is no {@code CRDSTP1O}. See the class documentation for the two independent
     * verifications of that absence.
     */
    public static final int CRDSTP_LENGTH = 1;

    /**
     * {@code ACCTNOnO PIC X(11)} - the per-row account number, one per row for rows 1 to 7
     * [{@code COCRDLI.CPY:356, 386, 416, 446, 476, 506, 536}].
     */
    public static final int ACCTNO_LENGTH = 11;

    /**
     * {@code CRDNUMnO PIC X(16)} - the per-row card number, one per row for rows 1 to 7
     * [{@code COCRDLI.CPY:362, 392, 422, 452, 482, 512, 542}].
     */
    public static final int CRDNUM_LENGTH = 16;

    /**
     * {@code CRDSTSnO PIC X(1)} - the per-row card status, one per row for rows 1 to 7
     * [{@code COCRDLI.CPY:368, 398, 428, 458, 488, 518, 548}].
     */
    public static final int CRDSTS_LENGTH = 1;

    /**
     * {@code INFOMSGO PIC X(45)} [{@code COCRDLI.CPY:554}], confirmed by
     * {@code INFOMSG DFHMDF ... LENGTH=45, POS=(20,19)} [{@code app/bms/COCRDLI.bms:324-328}].
     *
     * <p><strong>45, not 40.</strong> The sibling maps {@code COCRDSL} and {@code COCRDUP} declare
     * 40. Unifying the two would corrupt this map's byte layout.
     */
    public static final int INFOMSGO_LENGTH = 45;

    /**
     * {@code ERRMSGO PIC X(78)} [{@code COCRDLI.CPY:560}], confirmed by
     * {@code ERRMSG DFHMDF ... LENGTH=78, POS=(23,1)} [{@code app/bms/COCRDLI.bms:331-334}].
     *
     * <p><strong>78, not 80.</strong> The sibling maps declare 80.
     */
    public static final int ERRMSGO_LENGTH = 78;

    // =================================================================================================
    // Structural constants: the geometry of the 797-byte group image and the shape of the row table.
    //
    // Each is stated as arithmetic over the constants above rather than as a bare literal, so that a
    // mistranscribed width fails the class's own self-check at load time instead of producing a
    // plausible-looking record that is wrong by a few bytes.
    // =================================================================================================

    /**
     * The {@code TIOAPFX=YES} prefix: {@code 02 FILLER PIC X(12)}, the first item of both groups
     * [{@code COCRDLI.CPY:290} for the output group, L18 for the input group].
     *
     * <p>{@code DFHMSD ... TIOAPFX=YES} [{@code app/bms/COCRDLI.bms:23}] is what puts it there. It
     * carries no application data and is emitted as {@code LOW-VALUES} by
     * {@code MOVE LOW-VALUES TO CCRDLIAO} [{@code app/cbl/COCRDLIC.cbl:643}], but it must be present:
     * omitting it would shift every one of the 45 data offsets by 12 bytes.
     */
    public static final int TIOAPFX_LENGTH = 12;

    /**
     * The reserved span ahead of each field's {@code xxxC} item: {@code 02 FILLER PICTURE X(3)}.
     *
     * <p>It overlays the input group's {@code xxxL COMP PIC S9(4)} (2 bytes) plus its
     * {@code xxxF PICTURE X} (1 byte), which is why it is 3 and not 4.
     */
    public static final int RESERVED_SPAN_LENGTH = 3;

    /** The {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV} items are one byte each. */
    public static final int ATTRIBUTE_ITEM_LENGTH = 1;

    /** The number of attribute items per field: {@code COLOR}, {@code PS}, {@code HILIGHT}, {@code VALIDN}. */
    public static final int ATTRIBUTE_ITEM_COUNT = 4;

    /**
     * Bytes each field spends before its data: {@value #RESERVED_SPAN_LENGTH} of {@code FILLER} plus
     * {@value #ATTRIBUTE_ITEM_COUNT} attribute items of {@value #ATTRIBUTE_ITEM_LENGTH} byte, so the
     * stride of a field of width {@code n} is {@code 7 + n} in both groups.
     */
    public static final int FIELD_PREFIX_LENGTH =
            RESERVED_SPAN_LENGTH + (ATTRIBUTE_ITEM_COUNT * ATTRIBUTE_ITEM_LENGTH);

    /** Payload members in the header block, {@code TRNNAMEO} through {@code CARDSIDO}. */
    public static final int HEADER_FIELD_COUNT = 9;

    /**
     * Payload members in row 1: {@code CRDSEL1O}, {@code ACCTNO1O}, {@code CRDNUM1O},
     * {@code CRDSTS1O}. <strong>Four, because there is no {@code CRDSTP1O}.</strong>
     */
    public static final int ROW_1_FIELD_COUNT = 4;

    /**
     * Payload members in each of rows 2 to 7: {@code CRDSELnO}, {@code CRDSTPnO}, {@code ACCTNOnO},
     * {@code CRDNUMnO}, {@code CRDSTSnO} - in that order, with {@code CRDSTPnO}
     * <strong>second</strong>.
     */
    public static final int ROW_N_FIELD_COUNT = 5;

    /** Payload members in the footer block: {@code INFOMSGO} and {@code ERRMSGO}. */
    public static final int FOOTER_FIELD_COUNT = 2;

    /**
     * Rows the map displays, and therefore the {@code OCCURS} count of every seven-element structure
     * here: {@code WS-SCREEN-ROWS OCCURS 7 TIMES} [{@code app/cbl/COCRDLIC.cbl:255}],
     * {@code WS-EDIT-SELECT ... OCCURS 7 TIMES} [L75-76] and
     * {@code WS-EDIT-SELECT-ERRORS OCCURS 7 TIMES} [L86].
     */
    public static final int ROW_COUNT = 7;

    /**
     * Cards shown per page: <strong>7</strong>, from
     * {@code 05 WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} [{@code app/cbl/COCRDLIC.cbl:177-178}].
     *
     * <p>Behaviour, not configuration. It equals {@link #ROW_COUNT} because the map has exactly as
     * many rows as a page holds, and the two are stated separately because they answer different
     * questions: {@link #ROW_COUNT} is how wide the array is, this is how many records the browse
     * loop accumulates before it stops.
     */
    public static final int PAGE_SIZE = 7;

    /**
     * The number of name-labelled {@code DFHMDF} entries in {@code app/bms/COCRDLI.bms}, and hence
     * the number of payload members here: {@value #HEADER_FIELD_COUNT} + {@value #ROW_1_FIELD_COUNT}
     * + 6 x {@value #ROW_N_FIELD_COUNT} + {@value #FOOTER_FIELD_COUNT} = <strong>45</strong>, out of
     * 72 {@code DFHMDF} entries in total.
     */
    public static final int PAYLOAD_FIELD_COUNT = HEADER_FIELD_COUNT
            + ROW_1_FIELD_COUNT
            + ((ROW_COUNT - 1) * ROW_N_FIELD_COUNT)
            + FOOTER_FIELD_COUNT;

    /**
     * Bytes of application data across the 45 payload members: header 138 + row 1 29 + rows 2-7 180
     * + footer 123 = <strong>470</strong>.
     */
    public static final int PAYLOAD_LENGTH =
            (TRNNAMEO_LENGTH + TITLE01O_LENGTH + CURDATEO_LENGTH + PGMNAMEO_LENGTH
                    + TITLE02O_LENGTH + CURTIMEO_LENGTH + PAGENOO_LENGTH + ACCTSIDO_LENGTH
                    + CARDSIDO_LENGTH)
            + (CRDSEL_LENGTH + ACCTNO_LENGTH + CRDNUM_LENGTH + CRDSTS_LENGTH)
            + ((ROW_COUNT - 1)
                    * (CRDSEL_LENGTH + CRDSTP_LENGTH + ACCTNO_LENGTH + CRDNUM_LENGTH + CRDSTS_LENGTH))
            + (INFOMSGO_LENGTH + ERRMSGO_LENGTH);

    /**
     * The full symbolic-map group image:
     * {@value #TIOAPFX_LENGTH} + 45 x {@value #FIELD_PREFIX_LENGTH} + {@value #PAYLOAD_LENGTH} =
     * <strong>797</strong> bytes.
     *
     * <p>{@code CCRDLIAI} and {@code CCRDLIAO} are two views of these same 797 bytes.
     */
    public static final int GROUP_LENGTH =
            TIOAPFX_LENGTH + (PAYLOAD_FIELD_COUNT * FIELD_PREFIX_LENGTH) + PAYLOAD_LENGTH;

    /**
     * One element of {@code WS-SCREEN-ROWS}: {@code WS-ROW-ACCTNO PIC X(11)} +
     * {@code WS-ROW-CARD-NUM PIC X(16)} + {@code WS-ROW-CARD-STATUS PIC X(1)} = <strong>28</strong>
     * bytes [{@code app/cbl/COCRDLIC.cbl:258-260}].
     */
    public static final int SCREEN_ROW_LENGTH = ACCTNO_LENGTH + CRDNUM_LENGTH + CRDSTS_LENGTH;

    /**
     * {@code WS-ALL-ROWS PIC X(196)} [{@code app/cbl/COCRDLIC.cbl:253}]:
     * {@value #SCREEN_ROW_LENGTH} x {@value #ROW_COUNT} = <strong>196</strong>. The source comment at
     * L250 states the same arithmetic - {@code 28 CHARS X 7 ROWS = 196}.
     */
    public static final int SCREEN_ARRAY_LENGTH = SCREEN_ROW_LENGTH * ROW_COUNT;

    /**
     * {@code WS-EDIT-SELECT-FLAGS PIC X(7)} [{@code app/cbl/COCRDLIC.cbl:72}] and
     * {@code WS-EDIT-SELECT-ERROR-FLAGS PIC X(7)} [L83]: one character per row.
     */
    public static final int SELECT_FLAGS_LENGTH = ROW_COUNT;

    // =================================================================================================
    // The navigation triple. Widths come from CVCRD01Y through CardScreenState, which is the single
    // declaration of them in this module.
    // =================================================================================================

    /** {@code CCARD-NEXT-PROG PIC X(8)} [{@code app/cpy/CVCRD01Y.cpy:21}]. */
    public static final int NEXT_PROGRAM_LENGTH = CardScreenState.CCARD_NEXT_PROG_LENGTH;

    /** {@code CCARD-NEXT-MAPSET PIC X(7)} [{@code app/cpy/CVCRD01Y.cpy:23}]. */
    public static final int NEXT_MAPSET_LENGTH = CardScreenState.CCARD_NEXT_MAPSET_LENGTH;

    /**
     * {@code CCARD-NEXT-MAP PIC X(7)} [{@code app/cpy/CVCRD01Y.cpy:24}].
     *
     * <p>Seven, not eight: a BMS map name is seven characters precisely so that the symbolic group
     * can append an {@code I} or an {@code O} to it - {@code CCRDLIA} yields {@code CCRDLIAI} and
     * {@code CCRDLIAO}.
     */
    public static final int NEXT_MAP_LENGTH = CardScreenState.CCARD_NEXT_MAP_LENGTH;

    // =================================================================================================
    // Literal values this map's own program moves into the navigation triple, transcribed from
    // app/cbl/COCRDLIC.cbl:179-210. They are exposed because a controller and its tests both need
    // them, and retyping a program name in two places is how the two drift apart.
    // =================================================================================================

    /** {@code LIT-THISTRANID PIC X(4) VALUE 'CCLI'} [{@code app/cbl/COCRDLIC.cbl:181-182}]. */
    public static final String LIT_THISTRANID = "CCLI";

    /** {@code LIT-THISPGM PIC X(8) VALUE 'COCRDLIC'} [{@code app/cbl/COCRDLIC.cbl:179-180}]. */
    public static final String LIT_THISPGM = "COCRDLIC";

    /** {@code LIT-THISMAPSET PIC X(7) VALUE 'COCRDLI'} [{@code app/cbl/COCRDLIC.cbl:183-184}]. */
    public static final String LIT_THISMAPSET = "COCRDLI";

    /** {@code LIT-THISMAP PIC X(7) VALUE 'CCRDLIA'} [{@code app/cbl/COCRDLIC.cbl:185-186}]. */
    public static final String LIT_THISMAP = "CCRDLIA";

    /**
     * {@code LIT-MENUPGM PIC X(8) VALUE 'COMEN01C'} [{@code app/cbl/COCRDLIC.cbl:187-188}] - the
     * literal target of the {@code XCTL} at L402.
     */
    public static final String LIT_MENUPGM = "COMEN01C";

    // =================================================================================================
    // Selection characters and row-error signal - the 88-level literals, named once.
    // =================================================================================================

    /** {@code 88 VIEW-REQUESTED-ON VALUE 'S'} [{@code app/cbl/COCRDLIC.cbl:78}]. */
    public static final String SELECT_VIEW = "S";

    /** {@code 88 UPDATE-REQUESTED-ON VALUE 'U'} [{@code app/cbl/COCRDLIC.cbl:79}]. */
    public static final String SELECT_UPDATE = "U";

    /** {@code 88 WS-ROW-SELECT-ERROR VALUE '1'} [{@code app/cbl/COCRDLIC.cbl:88}]. */
    public static final String ROW_SELECT_ERROR = "1";

    /** {@code 88 CA-NEXT-PAGE-EXISTS VALUE 'Y'} [{@code app/cbl/COCRDLIC.cbl:244}]. */
    public static final String NEXT_PAGE_EXISTS = "Y";

    /** {@code 88 WS-RETURN-FLAG-ON VALUE '1'} [{@code app/cbl/COCRDLIC.cbl:248}]. */
    public static final String RETURN_FLAG_ON = "1";

    /** {@code 88 CA-FIRST-PAGE VALUE 1} [{@code app/cbl/COCRDLIC.cbl:238}]. */
    public static final int FIRST_PAGE = 1;

    /** {@code 88 CA-LAST-PAGE-SHOWN VALUE 0} [{@code app/cbl/COCRDLIC.cbl:240}]. */
    public static final int LAST_PAGE_SHOWN = 0;

    /** {@code 88 CA-LAST-PAGE-NOT-SHOWN VALUE 9} [{@code app/cbl/COCRDLIC.cbl:241}]. */
    public static final int LAST_PAGE_NOT_SHOWN = 9;

    /**
     * The single {@code LOW-VALUES} character, {@code U+0000}, which encodes to {@code 0x00} under
     * both {@code US-ASCII} and {@code IBM037}.
     *
     * <p>This is the "off" state of {@code WS-CA-NEXT-PAGE-IND} and {@code WS-RETURN-FLAG}
     * [{@code app/cbl/COCRDLIC.cbl:243, 247}] and the initial value of every payload member
     * [L643]. It is <strong>not</strong> a space and <strong>not</strong> Java {@code null}.
     */
    public static final String LOW_VALUE = "\u0000";

    /**
     * The {@code SPACE} character, kept beside {@link #LOW_VALUE} so that the difference between the
     * two is visible at every use site.
     *
     * <p>{@code 88 SELECT-BLANK VALUES ' ', LOW-VALUES} [{@code app/cbl/COCRDLIC.cbl:80-82}] accepts
     * <em>both</em>, which is the only reason the distinction can be ignored there and nowhere else.
     */
    public static final String SPACE = " ";

    // =================================================================================================
    // The 45 symbolic-map item names, carried VERBATIM as app/cpy-bms/COCRDLI.CPY spells them.
    //
    // These are the keys the payload store is addressed by, the names FieldAttributeSetter derives its
    // xxxC and xxxO targets from, and the names a parity differ compares field by field. A "tidied up"
    // name would make a real difference invisible, so none is tidied.
    // =================================================================================================

    /** {@code TRNNAMEO PIC X(4)}, {@code COCRDLI.CPY:296}; {@code DFHMDF TRNNAME}, {@code .bms:34}. */
    public static final String TRNNAMEO_ITEM = "TRNNAMEO";

    /** {@code TITLE01O PIC X(40)}, {@code COCRDLI.CPY:302}; {@code DFHMDF TITLE01}, {@code .bms:38}. */
    public static final String TITLE01O_ITEM = "TITLE01O";

    /** {@code CURDATEO PIC X(8)}, {@code COCRDLI.CPY:308}; {@code DFHMDF CURDATE}, {@code .bms:47}. */
    public static final String CURDATEO_ITEM = "CURDATEO";

    /** {@code PGMNAMEO PIC X(8)}, {@code COCRDLI.CPY:314}; {@code DFHMDF PGMNAME}, {@code .bms:57}. */
    public static final String PGMNAMEO_ITEM = "PGMNAMEO";

    /** {@code TITLE02O PIC X(40)}, {@code COCRDLI.CPY:320}; {@code DFHMDF TITLE02}, {@code .bms:61}. */
    public static final String TITLE02O_ITEM = "TITLE02O";

    /** {@code CURTIMEO PIC X(8)}, {@code COCRDLI.CPY:326}; {@code DFHMDF CURTIME}, {@code .bms:70}. */
    public static final String CURTIMEO_ITEM = "CURTIMEO";

    /** {@code PAGENOO PIC X(3)}, {@code COCRDLI.CPY:332}; {@code DFHMDF PAGENO}, {@code .bms:82}. */
    public static final String PAGENOO_ITEM = "PAGENOO";

    /** {@code ACCTSIDO PIC X(11)}, {@code COCRDLI.CPY:338}; {@code DFHMDF ACCTSID}, {@code .bms:89}. */
    public static final String ACCTSIDO_ITEM = "ACCTSIDO";

    /** {@code CARDSIDO PIC X(16)}, {@code COCRDLI.CPY:344}; {@code DFHMDF CARDSID}, {@code .bms:101}. */
    public static final String CARDSIDO_ITEM = "CARDSIDO";

    /** {@code CRDSEL1O PIC X(1)}, {@code COCRDLI.CPY:350}; {@code DFHMDF CRDSEL1}, {@code .bms:140}. */
    public static final String CRDSEL1O_ITEM = "CRDSEL1O";

    /** {@code ACCTNO1O PIC X(11)}, {@code COCRDLI.CPY:356}; {@code DFHMDF ACCTNO1}, {@code .bms:147}. */
    public static final String ACCTNO1O_ITEM = "ACCTNO1O";

    /** {@code CRDNUM1O PIC X(16)}, {@code COCRDLI.CPY:362}; {@code DFHMDF CRDNUM1}, {@code .bms:152}. */
    public static final String CRDNUM1O_ITEM = "CRDNUM1O";

    /** {@code CRDSTS1O PIC X(1)}, {@code COCRDLI.CPY:368}; {@code DFHMDF CRDSTS1}, {@code .bms:157}. */
    public static final String CRDSTS1O_ITEM = "CRDSTS1O";

    /** {@code CRDSEL2O PIC X(1)}, {@code COCRDLI.CPY:374}; {@code DFHMDF CRDSEL2}, {@code .bms:162}. */
    public static final String CRDSEL2O_ITEM = "CRDSEL2O";

    /** {@code CRDSTP2O PIC X(1)}, {@code COCRDLI.CPY:380}; {@code DFHMDF CRDSTP2}, {@code .bms:169}. */
    public static final String CRDSTP2O_ITEM = "CRDSTP2O";

    /** {@code ACCTNO2O PIC X(11)}, {@code COCRDLI.CPY:386}; {@code DFHMDF ACCTNO2}, {@code .bms:174}. */
    public static final String ACCTNO2O_ITEM = "ACCTNO2O";

    /** {@code CRDNUM2O PIC X(16)}, {@code COCRDLI.CPY:392}; {@code DFHMDF CRDNUM2}, {@code .bms:179}. */
    public static final String CRDNUM2O_ITEM = "CRDNUM2O";

    /** {@code CRDSTS2O PIC X(1)}, {@code COCRDLI.CPY:398}; {@code DFHMDF CRDSTS2}, {@code .bms:184}. */
    public static final String CRDSTS2O_ITEM = "CRDSTS2O";

    /** {@code CRDSEL3O PIC X(1)}, {@code COCRDLI.CPY:404}; {@code DFHMDF CRDSEL3}, {@code .bms:189}. */
    public static final String CRDSEL3O_ITEM = "CRDSEL3O";

    /** {@code CRDSTP3O PIC X(1)}, {@code COCRDLI.CPY:410}; {@code DFHMDF CRDSTP3}, {@code .bms:196}. */
    public static final String CRDSTP3O_ITEM = "CRDSTP3O";

    /** {@code ACCTNO3O PIC X(11)}, {@code COCRDLI.CPY:416}; {@code DFHMDF ACCTNO3}, {@code .bms:201}. */
    public static final String ACCTNO3O_ITEM = "ACCTNO3O";

    /** {@code CRDNUM3O PIC X(16)}, {@code COCRDLI.CPY:422}; {@code DFHMDF CRDNUM3}, {@code .bms:206}. */
    public static final String CRDNUM3O_ITEM = "CRDNUM3O";

    /** {@code CRDSTS3O PIC X(1)}, {@code COCRDLI.CPY:428}; {@code DFHMDF CRDSTS3}, {@code .bms:211}. */
    public static final String CRDSTS3O_ITEM = "CRDSTS3O";

    /** {@code CRDSEL4O PIC X(1)}, {@code COCRDLI.CPY:434}; {@code DFHMDF CRDSEL4}, {@code .bms:216}. */
    public static final String CRDSEL4O_ITEM = "CRDSEL4O";

    /** {@code CRDSTP4O PIC X(1)}, {@code COCRDLI.CPY:440}; {@code DFHMDF CRDSTP4}, {@code .bms:223}. */
    public static final String CRDSTP4O_ITEM = "CRDSTP4O";

    /** {@code ACCTNO4O PIC X(11)}, {@code COCRDLI.CPY:446}; {@code DFHMDF ACCTNO4}, {@code .bms:228}. */
    public static final String ACCTNO4O_ITEM = "ACCTNO4O";

    /** {@code CRDNUM4O PIC X(16)}, {@code COCRDLI.CPY:452}; {@code DFHMDF CRDNUM4}, {@code .bms:233}. */
    public static final String CRDNUM4O_ITEM = "CRDNUM4O";

    /** {@code CRDSTS4O PIC X(1)}, {@code COCRDLI.CPY:458}; {@code DFHMDF CRDSTS4}, {@code .bms:238}. */
    public static final String CRDSTS4O_ITEM = "CRDSTS4O";

    /** {@code CRDSEL5O PIC X(1)}, {@code COCRDLI.CPY:464}; {@code DFHMDF CRDSEL5}, {@code .bms:243}. */
    public static final String CRDSEL5O_ITEM = "CRDSEL5O";

    /** {@code CRDSTP5O PIC X(1)}, {@code COCRDLI.CPY:470}; {@code DFHMDF CRDSTP5}, {@code .bms:250}. */
    public static final String CRDSTP5O_ITEM = "CRDSTP5O";

    /** {@code ACCTNO5O PIC X(11)}, {@code COCRDLI.CPY:476}; {@code DFHMDF ACCTNO5}, {@code .bms:255}. */
    public static final String ACCTNO5O_ITEM = "ACCTNO5O";

    /** {@code CRDNUM5O PIC X(16)}, {@code COCRDLI.CPY:482}; {@code DFHMDF CRDNUM5}, {@code .bms:260}. */
    public static final String CRDNUM5O_ITEM = "CRDNUM5O";

    /** {@code CRDSTS5O PIC X(1)}, {@code COCRDLI.CPY:488}; {@code DFHMDF CRDSTS5}, {@code .bms:265}. */
    public static final String CRDSTS5O_ITEM = "CRDSTS5O";

    /** {@code CRDSEL6O PIC X(1)}, {@code COCRDLI.CPY:494}; {@code DFHMDF CRDSEL6}, {@code .bms:270}. */
    public static final String CRDSEL6O_ITEM = "CRDSEL6O";

    /** {@code CRDSTP6O PIC X(1)}, {@code COCRDLI.CPY:500}; {@code DFHMDF CRDSTP6}, {@code .bms:277}. */
    public static final String CRDSTP6O_ITEM = "CRDSTP6O";

    /** {@code ACCTNO6O PIC X(11)}, {@code COCRDLI.CPY:506}; {@code DFHMDF ACCTNO6}, {@code .bms:282}. */
    public static final String ACCTNO6O_ITEM = "ACCTNO6O";

    /** {@code CRDNUM6O PIC X(16)}, {@code COCRDLI.CPY:512}; {@code DFHMDF CRDNUM6}, {@code .bms:287}. */
    public static final String CRDNUM6O_ITEM = "CRDNUM6O";

    /** {@code CRDSTS6O PIC X(1)}, {@code COCRDLI.CPY:518}; {@code DFHMDF CRDSTS6}, {@code .bms:292}. */
    public static final String CRDSTS6O_ITEM = "CRDSTS6O";

    /** {@code CRDSEL7O PIC X(1)}, {@code COCRDLI.CPY:524}; {@code DFHMDF CRDSEL7}, {@code .bms:297}. */
    public static final String CRDSEL7O_ITEM = "CRDSEL7O";

    /** {@code CRDSTP7O PIC X(1)}, {@code COCRDLI.CPY:530}; {@code DFHMDF CRDSTP7}, {@code .bms:304}. */
    public static final String CRDSTP7O_ITEM = "CRDSTP7O";

    /** {@code ACCTNO7O PIC X(11)}, {@code COCRDLI.CPY:536}; {@code DFHMDF ACCTNO7}, {@code .bms:309}. */
    public static final String ACCTNO7O_ITEM = "ACCTNO7O";

    /** {@code CRDNUM7O PIC X(16)}, {@code COCRDLI.CPY:542}; {@code DFHMDF CRDNUM7}, {@code .bms:314}. */
    public static final String CRDNUM7O_ITEM = "CRDNUM7O";

    /** {@code CRDSTS7O PIC X(1)}, {@code COCRDLI.CPY:548}; {@code DFHMDF CRDSTS7}, {@code .bms:319}. */
    public static final String CRDSTS7O_ITEM = "CRDSTS7O";

    /** {@code INFOMSGO PIC X(45)}, {@code COCRDLI.CPY:554}; {@code DFHMDF INFOMSG}, {@code .bms:324}. */
    public static final String INFOMSGO_ITEM = "INFOMSGO";

    /** {@code ERRMSGO PIC X(78)}, {@code COCRDLI.CPY:560}; {@code DFHMDF ERRMSG}, {@code .bms:331}. */
    public static final String ERRMSGO_ITEM = "ERRMSGO";

    // =================================================================================================
    // The output-group descriptor table.
    //
    // One entry per name-labelled DFHMDF, in app/cpy-bms/COCRDLI.CPY declaration order. The table is
    // the single place the copybook's order, widths and line numbers are recorded, so a reviewer diffs
    // 45 lines against 45 copybook items and is done. Offsets are computed from the order rather than
    // typed, because 45 hand-typed offsets are 45 opportunities to be one byte out - and the class's
    // own load-time self-check would only tell you that something was wrong, not which line.
    //
    // Note what is NOT in this table: there is no CRDSTP1O entry, because row 1 has only four fields.
    // =================================================================================================

    /**
     * One field of the {@code CCRDLIAO} output group: its item name, its declared width, the
     * {@code app/cpy-bms/COCRDLI.CPY} line that declares it, and the absolute offset at which its
     * seven-byte prefix begins.
     *
     * <p>Everything else about the field is derived, because {@code REDEFINES} fixes the shape:
     * {@code FILLER X(3)} at {@link #prefixOffset()}, then the {@code xxxC}, {@code xxxP},
     * {@code xxxH} and {@code xxxV} items one byte each, then the {@code xxxO} data item at
     * {@link #dataOffset()}.
     *
     * @param itemName      the {@code xxxO} item name, verbatim from the copybook, for example
     *                      {@code "CRDSTP2O"}
     * @param length        {@code n} of that item's {@code PIC X(n)}
     * @param copybookLine  the 1-based line of {@code app/cpy-bms/COCRDLI.CPY} that declares the
     *                      {@code xxxO} item, for citation in diagnostics and in a parity report
     * @param prefixOffset  the absolute 0-based offset of the field's {@code FILLER X(3)}
     */
    public record MapField(String itemName, int length, int copybookLine, int prefixOffset) {

        /**
         * Validates a descriptor at class-initialisation time.
         *
         * @throws NullPointerException     if {@code itemName} is {@code null}
         * @throws IllegalArgumentException if {@code itemName} does not end in the {@code O} suffix
         *                                  every output item carries, or if any number is out of range
         */
        public MapField {
            Objects.requireNonNull(itemName, "An output item name is required");
            if (!itemName.endsWith(FieldAttributeSetter.OUTPUT_ITEM_SUFFIX)) {
                throw new IllegalArgumentException("Output group item '" + itemName + "' does not end "
                        + "in '" + FieldAttributeSetter.OUTPUT_ITEM_SUFFIX + "'; every item of "
                        + "CCRDLIAO is the DFHMDF label plus that suffix");
            }
            if (itemName.length() < 2) {
                throw new IllegalArgumentException("Output group item '" + itemName + "' leaves no "
                        + "DFHMDF label once its suffix is removed");
            }
            if (length < 1) {
                throw new IllegalArgumentException("Output item '" + itemName + "' declares width "
                        + length + "; every PIC X item is at least 1 byte wide");
            }
            if (copybookLine < 1) {
                throw new IllegalArgumentException("Output item '" + itemName + "' cites copybook "
                        + "line " + copybookLine + "; lines are 1-based");
            }
            if (prefixOffset < TIOAPFX_LENGTH) {
                throw new IllegalArgumentException("Output item '" + itemName + "' claims prefix "
                        + "offset " + prefixOffset + ", inside the " + TIOAPFX_LENGTH
                        + "-byte TIOAPFX prefix");
            }
        }

        /**
         * The {@code DFHMDF} label this item belongs to - the item name without its trailing
         * {@code O}. This is the {@code (SCRNVAR2)} token of {@code app/cpy/CSSETATY.cpy} and the key
         * the attribute quad is addressed by.
         *
         * @return the screen field prefix, for example {@code "CRDSTP2"} for {@code "CRDSTP2O"}
         */
        public String screenFieldPrefix() {
            return itemName.substring(0, itemName.length()
                    - FieldAttributeSetter.OUTPUT_ITEM_SUFFIX.length());
        }

        /** @return the {@code xxxC} colour item name, for example {@code "CRDSTP2C"} */
        public String colourItemName() {
            return screenFieldPrefix() + FieldAttributeSetter.COLOUR_ITEM_SUFFIX;
        }

        /** @return the {@code xxxP} programmed-symbols item name, for example {@code "CRDSTP2P"} */
        public String psItemName() {
            return screenFieldPrefix() + "P";
        }

        /** @return the {@code xxxH} highlight item name, for example {@code "CRDSTP2H"} */
        public String highlightItemName() {
            return screenFieldPrefix() + "H";
        }

        /** @return the {@code xxxV} validation item name, for example {@code "CRDSTP2V"} */
        public String validnItemName() {
            return screenFieldPrefix() + "V";
        }

        /** @return the absolute offset of the {@code xxxC} item, three bytes past the {@code FILLER} */
        public int colourOffset() {
            return prefixOffset + RESERVED_SPAN_LENGTH;
        }

        /** @return the absolute offset of the {@code xxxP} item */
        public int psOffset() {
            return colourOffset() + ATTRIBUTE_ITEM_LENGTH;
        }

        /** @return the absolute offset of the {@code xxxH} item */
        public int highlightOffset() {
            return psOffset() + ATTRIBUTE_ITEM_LENGTH;
        }

        /** @return the absolute offset of the {@code xxxV} item */
        public int validnOffset() {
            return highlightOffset() + ATTRIBUTE_ITEM_LENGTH;
        }

        /** @return the absolute offset of the {@code xxxO} data item */
        public int dataOffset() {
            return prefixOffset + FIELD_PREFIX_LENGTH;
        }

        /**
         * @return the offset one past this field's last byte, which is the next field's
         *         {@link #prefixOffset()}
         */
        public int endOffsetExclusive() {
            return dataOffset() + length;
        }

        /** @return the {@code FILLER X(3)} reserved span descriptor */
        public FieldSpan reservedSpan() {
            return FieldSpan.filler(prefixOffset, RESERVED_SPAN_LENGTH);
        }

        /** @return the {@code xxxC} span descriptor */
        public FieldSpan colourSpan() {
            return FieldSpan.alphanumeric(colourItemName(), colourOffset(), ATTRIBUTE_ITEM_LENGTH);
        }

        /** @return the {@code xxxP} span descriptor */
        public FieldSpan psSpan() {
            return FieldSpan.alphanumeric(psItemName(), psOffset(), ATTRIBUTE_ITEM_LENGTH);
        }

        /** @return the {@code xxxH} span descriptor */
        public FieldSpan highlightSpan() {
            return FieldSpan.alphanumeric(highlightItemName(), highlightOffset(),
                    ATTRIBUTE_ITEM_LENGTH);
        }

        /** @return the {@code xxxV} span descriptor */
        public FieldSpan validnSpan() {
            return FieldSpan.alphanumeric(validnItemName(), validnOffset(), ATTRIBUTE_ITEM_LENGTH);
        }

        /** @return the {@code xxxO} data span descriptor */
        public FieldSpan dataSpan() {
            return FieldSpan.alphanumeric(itemName, dataOffset(), length);
        }

        /**
         * Renders this descriptor the way the copybook reads, for diagnostics and parity reports.
         *
         * @return for example {@code CRDSTP2O PIC X(1) @380, bytes 340..340}
         */
        public String describe() {
            return itemName + " PIC X(" + length + ") @" + copybookLine + ", bytes " + dataOffset()
                    + ".." + (endOffsetExclusive() - 1);
        }
    }

    /**
     * The 45 output-group fields in {@code app/cpy-bms/COCRDLI.CPY} declaration order, immutable.
     *
     * <p>Read it top to bottom against the copybook: nine header items, then row 1's <em>four</em>
     * items, then rows 2 to 7 with <em>five</em> each and {@code CRDSTPnO} second, then the two footer
     * items. The list is the machine-readable form of gate G9's traceability requirement - every
     * payload member of this class has exactly one entry here, and every entry names a real
     * {@code DFHMDF} label and a real {@code PICTURE} clause.
     */
    public static final List<MapField> MAP_FIELDS = buildMapFields();

    /** {@link #MAP_FIELDS} indexed by item name, for constant-time addressing. */
    private static final Map<String, MapField> MAP_FIELDS_BY_ITEM = indexByItemName();

    /** {@link #MAP_FIELDS} indexed by {@code DFHMDF} label, for the attribute quad and highlighting. */
    private static final Map<String, MapField> MAP_FIELDS_BY_PREFIX = indexByScreenFieldPrefix();

    /**
     * The complete 797-byte geometry of {@code 01 CCRDLIAO REDEFINES CCRDLIAI}
     * [{@code app/cpy-bms/COCRDLI.CPY:289}]: the {@code TIOAPFX} {@code FILLER X(12)}, then for each
     * of the 45 fields a {@code FILLER X(3)} followed by the {@code xxxC}, {@code xxxP}, {@code xxxH}
     * and {@code xxxV} items and the {@code xxxO} data item.
     *
     * <p>{@link RecordLayout} refuses a layout with a gap, an overlap or a wrong total, so the fact
     * that this constant initialises at all is itself a proof that the 45 widths sum to
     * {@value #PAYLOAD_LENGTH} and the group to {@value #GROUP_LENGTH}.
     */
    public static final RecordLayout GROUP_LAYOUT = buildGroupLayout();

    /**
     * The codec used for the {@code PIC X} move rule alone.
     *
     * <p>{@link FixedWidthCodec#movePicX(String, int)} pads and truncates a {@code String} on the
     * right and touches no bytes, so its result does not depend on the code page; the charset named
     * here is inert and is never used to encode anything. Every operation in this class that
     * <em>does</em> touch bytes - {@link #toFixedWidth(Charset)},
     * {@link #fromFixedWidth(byte[], Charset)} and the {@link PageCursor} images - takes its charset
     * from the caller and never from the platform default.
     *
     * <p>{@code FixedWidthCodec} holds one {@code final Charset} and no other state, so a shared
     * instance is immutable and thread-safe. This is the only {@code static} field in the class that
     * is not a primitive, a string or an immutable collection, and it is {@code final}.
     */
    private static final FixedWidthCodec PIC_X_MOVE_CODEC =
            new FixedWidthCodec(StandardCharsets.US_ASCII);

    // =================================================================================================
    // Table construction and the load-time self-check.
    // =================================================================================================

    /**
     * Transcribes {@code app/cpy-bms/COCRDLI.CPY:289-560} into descriptors, in copybook order, and
     * then checks the result against the three counts and the total width declared above.
     *
     * <p>The order of the calls below <strong>is</strong> the byte layout: each one takes the offset
     * the previous field ended at. Reordering two lines silently moves data, which is why row 1's four
     * fields and each later row's five - with {@code CRDSTPnO} second - are laid out one call per line
     * and grouped by row.
     *
     * <p>The two closing guards are assertions on this method's own transcription, so in a correct
     * build their {@code throw} arms are unreachable and no test can exercise them without first
     * breaking the table. They are kept because the cost of a wrong table is a whole screen of
     * plausible-looking data at the wrong offsets, and failing at class initialisation is the cheapest
     * place to discover that.
     *
     * @return the 45 descriptors, immutable
     * @throws IllegalStateException if the transcription does not yield exactly
     *                               {@value #PAYLOAD_FIELD_COUNT} fields spanning exactly
     *                               {@value #GROUP_LENGTH} bytes
     */
    private static List<MapField> buildMapFields() {
        List<MapField> fields = new ArrayList<>(PAYLOAD_FIELD_COUNT);
        int next = TIOAPFX_LENGTH;

        // Header - 9 fields, COCRDLI.CPY:296-344. PAGENOO sits seventh, between CURTIMEO and ACCTSIDO.
        next = append(fields, TRNNAMEO_ITEM, TRNNAMEO_LENGTH, 296, next);
        next = append(fields, TITLE01O_ITEM, TITLE01O_LENGTH, 302, next);
        next = append(fields, CURDATEO_ITEM, CURDATEO_LENGTH, 308, next);
        next = append(fields, PGMNAMEO_ITEM, PGMNAMEO_LENGTH, 314, next);
        next = append(fields, TITLE02O_ITEM, TITLE02O_LENGTH, 320, next);
        next = append(fields, CURTIMEO_ITEM, CURTIMEO_LENGTH, 326, next);
        next = append(fields, PAGENOO_ITEM, PAGENOO_LENGTH, 332, next);
        next = append(fields, ACCTSIDO_ITEM, ACCTSIDO_LENGTH, 338, next);
        next = append(fields, CARDSIDO_ITEM, CARDSIDO_LENGTH, 344, next);

        // Row 1 - FOUR fields, COCRDLI.CPY:350-368. There is no CRDSTP1O: line 350 declares CRDSEL1O
        // and the very next output item, at line 356, is ACCTNO1O.
        next = append(fields, CRDSEL1O_ITEM, CRDSEL_LENGTH, 350, next);
        next = append(fields, ACCTNO1O_ITEM, ACCTNO_LENGTH, 356, next);
        next = append(fields, CRDNUM1O_ITEM, CRDNUM_LENGTH, 362, next);
        next = append(fields, CRDSTS1O_ITEM, CRDSTS_LENGTH, 368, next);

        // Row 2 - FIVE fields, COCRDLI.CPY:374-398, with CRDSTP2O SECOND.
        next = append(fields, CRDSEL2O_ITEM, CRDSEL_LENGTH, 374, next);
        next = append(fields, CRDSTP2O_ITEM, CRDSTP_LENGTH, 380, next);
        next = append(fields, ACCTNO2O_ITEM, ACCTNO_LENGTH, 386, next);
        next = append(fields, CRDNUM2O_ITEM, CRDNUM_LENGTH, 392, next);
        next = append(fields, CRDSTS2O_ITEM, CRDSTS_LENGTH, 398, next);

        // Row 3 - COCRDLI.CPY:404-428.
        next = append(fields, CRDSEL3O_ITEM, CRDSEL_LENGTH, 404, next);
        next = append(fields, CRDSTP3O_ITEM, CRDSTP_LENGTH, 410, next);
        next = append(fields, ACCTNO3O_ITEM, ACCTNO_LENGTH, 416, next);
        next = append(fields, CRDNUM3O_ITEM, CRDNUM_LENGTH, 422, next);
        next = append(fields, CRDSTS3O_ITEM, CRDSTS_LENGTH, 428, next);

        // Row 4 - COCRDLI.CPY:434-458.
        next = append(fields, CRDSEL4O_ITEM, CRDSEL_LENGTH, 434, next);
        next = append(fields, CRDSTP4O_ITEM, CRDSTP_LENGTH, 440, next);
        next = append(fields, ACCTNO4O_ITEM, ACCTNO_LENGTH, 446, next);
        next = append(fields, CRDNUM4O_ITEM, CRDNUM_LENGTH, 452, next);
        next = append(fields, CRDSTS4O_ITEM, CRDSTS_LENGTH, 458, next);

        // Row 5 - COCRDLI.CPY:464-488.
        next = append(fields, CRDSEL5O_ITEM, CRDSEL_LENGTH, 464, next);
        next = append(fields, CRDSTP5O_ITEM, CRDSTP_LENGTH, 470, next);
        next = append(fields, ACCTNO5O_ITEM, ACCTNO_LENGTH, 476, next);
        next = append(fields, CRDNUM5O_ITEM, CRDNUM_LENGTH, 482, next);
        next = append(fields, CRDSTS5O_ITEM, CRDSTS_LENGTH, 488, next);

        // Row 6 - COCRDLI.CPY:494-518.
        next = append(fields, CRDSEL6O_ITEM, CRDSEL_LENGTH, 494, next);
        next = append(fields, CRDSTP6O_ITEM, CRDSTP_LENGTH, 500, next);
        next = append(fields, ACCTNO6O_ITEM, ACCTNO_LENGTH, 506, next);
        next = append(fields, CRDNUM6O_ITEM, CRDNUM_LENGTH, 512, next);
        next = append(fields, CRDSTS6O_ITEM, CRDSTS_LENGTH, 518, next);

        // Row 7 - COCRDLI.CPY:524-548.
        next = append(fields, CRDSEL7O_ITEM, CRDSEL_LENGTH, 524, next);
        next = append(fields, CRDSTP7O_ITEM, CRDSTP_LENGTH, 530, next);
        next = append(fields, ACCTNO7O_ITEM, ACCTNO_LENGTH, 536, next);
        next = append(fields, CRDNUM7O_ITEM, CRDNUM_LENGTH, 542, next);
        next = append(fields, CRDSTS7O_ITEM, CRDSTS_LENGTH, 548, next);

        // Footer - 2 fields, COCRDLI.CPY:554-560. Widths 45 and 78, not the siblings' 40 and 80.
        next = append(fields, INFOMSGO_ITEM, INFOMSGO_LENGTH, 554, next);
        next = append(fields, ERRMSGO_ITEM, ERRMSGO_LENGTH, 560, next);

        if (fields.size() != PAYLOAD_FIELD_COUNT) {
            throw new IllegalStateException("Transcribed " + fields.size() + " output items from "
                    + "app/cpy-bms/COCRDLI.CPY but the mapset declares " + PAYLOAD_FIELD_COUNT
                    + " name-labelled DFHMDF entries; a count of "
                    + (PAYLOAD_FIELD_COUNT - 1) + " or " + (PAYLOAD_FIELD_COUNT + 1)
                    + " means the row-1 asymmetry has been got wrong");
        }
        if (next != GROUP_LENGTH) {
            throw new IllegalStateException("The transcribed output items span " + next + " byte(s) "
                    + "but 01 CCRDLIAO is " + GROUP_LENGTH + " byte(s); a mistranscribed PIC X(n) is "
                    + "the usual cause");
        }
        return List.copyOf(fields);
    }

    /**
     * Appends one descriptor at the running offset and reports where the next one starts.
     *
     * @param fields       the table being built
     * @param itemName     the {@code xxxO} item name
     * @param length       the item's declared width
     * @param copybookLine the {@code COCRDLI.CPY} line declaring it
     * @param prefixOffset the absolute offset of this field's {@code FILLER X(3)}
     * @return the offset one past this field's last byte
     */
    private static int append(List<MapField> fields, String itemName, int length, int copybookLine,
            int prefixOffset) {
        MapField field = new MapField(itemName, length, copybookLine, prefixOffset);
        fields.add(field);
        return field.endOffsetExclusive();
    }

    /**
     * Indexes {@link #MAP_FIELDS} by {@code xxxO} item name.
     *
     * @return an insertion-ordered, immutable index
     */
    private static Map<String, MapField> indexByItemName() {
        Map<String, MapField> index = new LinkedHashMap<>();
        for (MapField field : MAP_FIELDS) {
            index.put(field.itemName(), field);
        }
        return Collections.unmodifiableMap(index);
    }

    /**
     * Indexes {@link #MAP_FIELDS} by {@code DFHMDF} label, which is the key
     * {@link FieldAttributeSetter} works in.
     *
     * @return an insertion-ordered, immutable index
     */
    private static Map<String, MapField> indexByScreenFieldPrefix() {
        Map<String, MapField> index = new LinkedHashMap<>();
        for (MapField field : MAP_FIELDS) {
            index.put(field.screenFieldPrefix(), field);
        }
        return Collections.unmodifiableMap(index);
    }

    /**
     * Expands {@link #MAP_FIELDS} into the 271 spans of the 797-byte group: one {@code FILLER X(12)},
     * then six spans per field - the {@code FILLER X(3)}, the {@code xxxC}/{@code xxxP}/{@code xxxH}/
     * {@code xxxV} quad and the {@code xxxO} data item. 1 + 45 x 6 = 271.
     *
     * @return the validated layout
     */
    private static RecordLayout buildGroupLayout() {
        List<FieldSpan> spans =
                new ArrayList<>(1 + (PAYLOAD_FIELD_COUNT * (1 + ATTRIBUTE_ITEM_COUNT + 1)));
        spans.add(FieldSpan.filler(0, TIOAPFX_LENGTH));
        for (MapField field : MAP_FIELDS) {
            spans.add(field.reservedSpan());
            spans.add(field.colourSpan());
            spans.add(field.psSpan());
            spans.add(field.highlightSpan());
            spans.add(field.validnSpan());
            spans.add(field.dataSpan());
        }
        return new RecordLayout(GROUP_LENGTH, spans);
    }

    // =================================================================================================
    // Nested value type: one element of WS-SCREEN-ROWS.
    // =================================================================================================

    /**
     * One row of the browse result: the projection of {@code WS-SCREEN-ROWS(n)}
     * [{@code app/cbl/COCRDLIC.cbl:255-260}].
     *
     * <pre>
     * 05 WS-SCREEN-DATA.
     *    10 WS-ALL-ROWS                 PIC X(196).
     *    10 FILLER REDEFINES WS-ALL-ROWS.
     *       15 WS-SCREEN-ROWS OCCURS  7 TIMES.
     *          20 WS-EACH-ROW.
     *             25 WS-EACH-CARD.
     *                30 WS-ROW-ACCTNO      PIC X(11).
     *                30 WS-ROW-CARD-NUM    PIC X(16).
     *                30 WS-ROW-CARD-STATUS PIC X(1).
     * </pre>
     *
     * <p>11 + 16 + 1 = {@value #SCREEN_ROW_LENGTH} bytes per element, x
     * {@value #ROW_COUNT} rows = {@value #SCREEN_ARRAY_LENGTH}, which is what
     * {@code WS-ALL-ROWS PIC X(196)} declares and what the source comment at L250 states.
     *
     * <p>{@code 1200-SCREEN-ARRAY-INIT} [L678-742] moves these three items into {@code ACCTNOnO},
     * {@code CRDNUMnO} and {@code CRDSTSnO}, which is why {@link CardListResponse#screenRow(int)}
     * reads the row back out of those payload members rather than out of a second copy: one source of
     * truth cannot drift from itself.
     *
     * <p>All three components are {@code PIC X}, so all three are {@code String} and none is trimmed.
     *
     * @param rowAcctno     {@code WS-ROW-ACCTNO PIC X(11)} [L258], exactly {@value #ACCTNO_LENGTH}
     *                      characters
     * @param rowCardNum    {@code WS-ROW-CARD-NUM PIC X(16)} [L259], exactly {@value #CRDNUM_LENGTH}
     *                      characters - a full card number, in the clear, exactly as the map carries it
     * @param rowCardStatus {@code WS-ROW-CARD-STATUS PIC X(1)} [L260], exactly
     *                      {@value #CRDSTS_LENGTH} character
     */
    public record ScreenRow(String rowAcctno, String rowCardNum, String rowCardStatus) {

        /**
         * Validates that each component is exactly its declared width, because a fixed-width row is
         * addressed by absolute offset and a short component would shift the two after it.
         *
         * @throws NullPointerException     if any component is {@code null}; {@code null} is not a
         *                                  COBOL state, and an empty row is {@link #lowValues()}
         * @throws IllegalArgumentException if any component is not exactly its declared width
         */
        public ScreenRow {
            requireExactWidth(rowAcctno, ACCTNO_LENGTH, "WS-ROW-ACCTNO");
            requireExactWidth(rowCardNum, CRDNUM_LENGTH, "WS-ROW-CARD-NUM");
            requireExactWidth(rowCardStatus, CRDSTS_LENGTH, "WS-ROW-CARD-STATUS");
        }

        /**
         * A row in the state {@code MOVE LOW-VALUES TO CCRDLIAO}
         * [{@code app/cbl/COCRDLIC.cbl:643}] leaves it: all {@value #SCREEN_ROW_LENGTH} bytes
         * {@code x'00'}.
         *
         * <p>This is the state {@code IF WS-EACH-CARD(n) EQUAL LOW-VALUES} tests for at
         * {@code app/cbl/COCRDLIC.cbl:680, 689, 698, 707, 716, 726, 735}, and the state that makes
         * {@code 1200-SCREEN-ARRAY-INIT} skip a row.
         *
         * @return an all-{@code LOW-VALUES} row; never {@code null}
         */
        public static ScreenRow lowValues() {
            return new ScreenRow(CardScreenState.lowValues(ACCTNO_LENGTH),
                    CardScreenState.lowValues(CRDNUM_LENGTH),
                    CardScreenState.lowValues(CRDSTS_LENGTH));
        }

        /**
         * A space-filled row, which is a different state from {@link #lowValues()} and is never
         * substituted for it.
         *
         * @return an all-spaces row; never {@code null}
         */
        public static ScreenRow spaces() {
            return new ScreenRow(CardScreenState.spaces(ACCTNO_LENGTH),
                    CardScreenState.spaces(CRDNUM_LENGTH),
                    CardScreenState.spaces(CRDSTS_LENGTH));
        }

        /**
         * Builds a row from values of any length by applying the {@code PIC X} move rule to each -
         * right-padded with spaces when short, truncated on the right when long.
         *
         * @param acctno     the account number; may be shorter or longer than
         *                   {@value #ACCTNO_LENGTH}
         * @param cardNum    the card number; may be shorter or longer than {@value #CRDNUM_LENGTH}
         * @param cardStatus the card status; may be shorter or longer than {@value #CRDSTS_LENGTH}
         * @return the row with each component at its declared width; never {@code null}
         * @throws NullPointerException if any argument is {@code null}
         */
        public static ScreenRow of(String acctno, String cardNum, String cardStatus) {
            return new ScreenRow(PIC_X_MOVE_CODEC.movePicX(acctno, ACCTNO_LENGTH),
                    PIC_X_MOVE_CODEC.movePicX(cardNum, CRDNUM_LENGTH),
                    PIC_X_MOVE_CODEC.movePicX(cardStatus, CRDSTS_LENGTH));
        }

        /**
         * Rebuilds a row from its {@value #SCREEN_ROW_LENGTH}-character image, the form
         * {@code WS-ALL-ROWS} stores seven of.
         *
         * @param image exactly {@value #SCREEN_ROW_LENGTH} characters
         * @return the row the image describes; never {@code null}
         * @throws NullPointerException     if {@code image} is {@code null}
         * @throws IllegalArgumentException if {@code image} is not exactly
         *                                  {@value #SCREEN_ROW_LENGTH} characters
         */
        public static ScreenRow fromImage(String image) {
            requireExactWidth(image, SCREEN_ROW_LENGTH, "WS-SCREEN-ROWS element");
            int cardNumStart = ACCTNO_LENGTH;
            int statusStart = cardNumStart + CRDNUM_LENGTH;
            return new ScreenRow(image.substring(0, cardNumStart),
                    image.substring(cardNumStart, statusStart),
                    image.substring(statusStart, statusStart + CRDSTS_LENGTH));
        }

        /**
         * {@code true} when all {@value #SCREEN_ROW_LENGTH} bytes are {@code x'00'} - the
         * {@code IF WS-EACH-CARD(n) EQUAL LOW-VALUES} test.
         *
         * <p>Space-filled is deliberately <strong>not</strong> low-values here, because the COBOL
         * comparison is against the figurative constant {@code LOW-VALUES} and nothing else.
         *
         * @return {@code true} when the row is entirely {@code LOW-VALUES}
         */
        @JsonIgnore
        public boolean isLowValues() {
            return image().equals(CardScreenState.lowValues(SCREEN_ROW_LENGTH));
        }

        /**
         * The row as the {@value #SCREEN_ROW_LENGTH}-character span {@code WS-SCREEN-ROWS(n)}
         * occupies inside {@code WS-ALL-ROWS}.
         *
         * @return the concatenation of the three components, in declaration order
         */
        public String image() {
            return rowAcctno + rowCardNum + rowCardStatus;
        }
    }

    // =================================================================================================
    // Nested metadata type: the xxxC / xxxP / xxxH / xxxV attribute quad.
    // =================================================================================================

    /**
     * The four extended-attribute bytes {@code CCRDLIAO} carries for one screen field, in the byte
     * order the copybook declares them: {@code xxxC} then {@code xxxP} then {@code xxxH} then
     * {@code xxxV}.
     *
     * <p>They exist because {@code CCRDLIA DFHMDI ... DSATTS=(COLOR,HILIGHT,PS,VALIDN),
     * MAPATTS=(COLOR,HILIGHT,PS,VALIDN)} [{@code app/bms/COCRDLI.bms:26-27}] asks for them. Read the
     * letters, not the {@code DSATTS} order: the items are
     * <strong>C</strong>olour, <strong>P</strong>rogrammed symbols, <strong>H</strong>ighlight,
     * <strong>V</strong>alidation, whereas {@code DSATTS} lists them alphabetically by keyword.
     *
     * <p>These are 3270 presentation attributes, not data, so they are <strong>excluded from JSON</strong>
     * while remaining addressable in Java - {@link FieldAttributeSetter} needs the colour item as a
     * target. They are held as {@code byte} rather than {@code char} because a BMS attribute is a
     * byte, and typing them that way makes it impossible to move one into a data item by accident.
     *
     * <p>Every byte starts at {@code 0x00}, which is both what {@code MOVE LOW-VALUES TO CCRDLIAO}
     * [{@code app/cbl/COCRDLIC.cbl:643}] leaves and, by coincidence of the IBM encoding, the
     * "use the map default" values {@link BmsAttributes#DFHDFCOL} and {@link BmsAttributes#DFHDFHI}.
     *
     * <p>This type is mutable, which is why it is a class and not a record: the program assigns into
     * individual attribute items - {@code MOVE DFHRED TO CRDSEL1C OF CCRDLIAO} [L756],
     * {@code MOVE DFHBMDAR TO INFOMSGC OF CCRDLIAO} [L671] and {@code MOVE DFHNEUTR TO INFOMSGC OF
     * CCRDLIAO} [L929] - and the enclosing response is the owner of that state.
     */
    public static final class FieldAttributes {

        /** {@code xxxC} - the extended-colour byte, {@code COLOR} in {@code DSATTS}. */
        private byte colour;

        /** {@code xxxP} - the programmed-symbols byte, {@code PS} in {@code DSATTS}. */
        private byte ps;

        /** {@code xxxH} - the extended-highlight byte, {@code HILIGHT} in {@code DSATTS}. */
        private byte highlight;

        /** {@code xxxV} - the validation byte, {@code VALIDN} in {@code DSATTS}. */
        private byte validn;

        /**
         * Creates a quad in the state {@code MOVE LOW-VALUES TO CCRDLIAO} leaves: all four bytes
         * {@code 0x00}.
         */
        public FieldAttributes() {
            reset();
        }

        /**
         * Copy constructor, used when a response is copied so that no quad is shared between two
         * responses.
         *
         * @param other the quad to copy; never {@code null}
         * @throws NullPointerException if {@code other} is {@code null}
         */
        public FieldAttributes(FieldAttributes other) {
            Objects.requireNonNull(other, "A quad to copy is required");
            this.colour = other.colour;
            this.ps = other.ps;
            this.highlight = other.highlight;
            this.validn = other.validn;
        }

        /** Restores all four bytes to {@code 0x00}, the {@code LOW-VALUES} state of the group. */
        public void reset() {
            this.colour = 0x00;
            this.ps = 0x00;
            this.highlight = 0x00;
            this.validn = 0x00;
        }

        /** @return the {@code xxxC} colour byte */
        public byte colour() {
            return colour;
        }

        /**
         * @param value the colour byte to move into {@code xxxC}, for example
         *              {@link BmsAttributes#DFHRED}
         */
        public void setColour(byte value) {
            this.colour = value;
        }

        /** @return the {@code xxxP} programmed-symbols byte */
        public byte ps() {
            return ps;
        }

        /** @param value the byte to move into {@code xxxP} */
        public void setPs(byte value) {
            this.ps = value;
        }

        /** @return the {@code xxxH} extended-highlight byte */
        public byte highlight() {
            return highlight;
        }

        /** @param value the byte to move into {@code xxxH}, for example {@link BmsAttributes#DFHUNDLN} */
        public void setHighlight(byte value) {
            this.highlight = value;
        }

        /** @return the {@code xxxV} validation byte */
        public byte validn() {
            return validn;
        }

        /** @param value the byte to move into {@code xxxV} */
        public void setValidn(byte value) {
            this.validn = value;
        }

        /**
         * The four bytes in copybook order, as they sit in the group image.
         *
         * @return a fresh four-byte array, never a view of this object's state
         */
        public byte[] toByteArray() {
            return new byte[] {colour, ps, highlight, validn};
        }

        /**
         * Replaces all four bytes from a group-image span.
         *
         * @param bytes exactly {@value #ATTRIBUTE_ITEM_COUNT} bytes in copybook order
         * @throws NullPointerException     if {@code bytes} is {@code null}
         * @throws IllegalArgumentException if {@code bytes.length} is not
         *                                  {@value #ATTRIBUTE_ITEM_COUNT}
         */
        public void fromByteArray(byte[] bytes) {
            Objects.requireNonNull(bytes, "Attribute bytes are required");
            if (bytes.length != ATTRIBUTE_ITEM_COUNT) {
                throw new IllegalArgumentException("Supplied " + bytes.length + " attribute byte(s); "
                        + "the xxxC/xxxP/xxxH/xxxV quad is exactly " + ATTRIBUTE_ITEM_COUNT
                        + " byte(s) wide");
            }
            this.colour = bytes[0];
            this.ps = bytes[1];
            this.highlight = bytes[2];
            this.validn = bytes[3];
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FieldAttributes that)) {
                return false;
            }
            return colour == that.colour && ps == that.ps && highlight == that.highlight
                    && validn == that.validn;
        }

        @Override
        public int hashCode() {
            return Objects.hash(colour, ps, highlight, validn);
        }

        /**
         * Renders the quad with the BMS mnemonics its bytes stand for, so a log line is readable
         * against the copybook rather than being four hex numbers.
         *
         * @return for example {@code FieldAttributes[C=DFHRED, P=0x00, H=DFHDFHI, V=0x00]}
         */
        @Override
        public String toString() {
            return "FieldAttributes[C=" + BmsAttributes.colourMnemonic(colour)
                    + ", P=" + BmsAttributes.toHex(ps)
                    + ", H=" + BmsAttributes.highlightMnemonic(highlight)
                    + ", V=" + BmsAttributes.toHex(validn) + "]";
        }
    }

    // =================================================================================================
    // Nested value type: the 58-byte page cursor.
    // =================================================================================================

    // =================================================================================================
    // There is deliberately NO PageCursor record declared here.
    //
    // 01 WS-THIS-PROGCOMMAREA [app/cbl/COCRDLIC.cbl:229-248] is ONE area. COCRDLIC.cbl:1078-1082
    // returns it as the trailing part of the communication area and :331-334 reads the same bytes back
    // on the next invocation, so the cursor this response carries is the cursor the next request
    // arrives with. It is declared once, on CardListRequest, and referenced here.
    //
    // This class previously declared its own eight-component version - lastCardNum, lastCardAcctId,
    // firstCardNum, firstCardAcctId and four counters, flat - against the request's six-component one
    // whose two 27-byte keys are nested CardKey records. Same 58 bytes, two different JSON shapes, so
    // the pair could not round-trip: a client echoing the response's cursor back into a request had to
    // rewrite it, and any such rewriting is a place for the two to diverge. The nested shape is also
    // the faithful one - COCRDLIC.cbl:230 and :233 declare 10 WS-CA-LAST-CARDKEY and
    // 10 WS-CA-FIRST-CARDKEY as group items, and :1268 moves one onto the other wholesale with
    // MOVE WS-CA-FIRST-CARDKEY TO WS-CA-LAST-CARDKEY, which is a group move and not four field moves.
    //
    // The 58-byte image moved with it: PageCursor.toFixedWidth and PageCursor.fromFixedWidth, and the
    // eight FieldSpans and the RecordLayout behind them, are now declared once on the shared type.
    // =================================================================================================

    // =================================================================================================
    // Instance state.
    //
    // Nothing here is static, so two concurrent requests share nothing. Nothing here is a session or a
    // cache either: the whole of the conversation state is in these fields and travels in the payload.
    // =================================================================================================

    /**
     * The 45 payload members, keyed by {@code xxxO} item name in copybook order.
     *
     * <p>One store rather than 45 separate fields, for one reason that matters: {@code 1200-SCREEN-
     * ARRAY-INIT} and {@code CSSETATY} both address these items <em>by name</em>, and a name-addressed
     * write that has to find its way to one of 45 individually declared fields is 45 chances to reach
     * the wrong one. The 45 typed accessors below are the API and each one names its own key, so the
     * copybook-to-accessor correspondence stays one-to-one and auditable; this map is only how the
     * value gets there.
     *
     * <p>Its key set is fixed at construction to exactly {@link #MAP_FIELDS} and never grows: there is
     * no path in this class that puts a key the descriptor table does not declare. It is excluded from
     * JSON because the 45 accessors already carry every value; serialising both would put every field
     * on the wire twice.
     */
    private final Map<String, String> payload;

    /**
     * The {@code xxxC}/{@code xxxP}/{@code xxxH}/{@code xxxV} quads, keyed by {@code DFHMDF} label.
     *
     * <p>Presentation attributes, not data - excluded from JSON, addressable in Java. One quad per
     * named field, created at construction, so {@link #fieldAttributes(String)} never returns
     * {@code null} for a field the map declares.
     */
    private final Map<String, FieldAttributes> attributes;

    /**
     * {@code WS-EDIT-SELECT-ERROR-FLAGS PIC X(7)} [{@code app/cbl/COCRDLIC.cbl:83}], held as the
     * seven-character group image its {@code REDEFINES} at L84-85 slices into
     * {@code WS-EDIT-SELECT-ERRORS OCCURS 7 TIMES}.
     *
     * <p>Stored as the group rather than as seven fields because that is how the copybook declares it -
     * one {@code PIC X(7)} with an array view over it - and because
     * {@code MOVE WS-EDIT-SELECT-FLAGS TO WS-EDIT-SELECT-ERROR-FLAGS} [L1088-1089] and the
     * {@code INSPECT ... REPLACING} that follows it operate on the whole group at once.
     *
     * <p>Initialised to {@code LOW-VALUES}. The item declares no {@code VALUE} clause of its own, but
     * IBM initialises {@code WORKING-STORAGE} without a {@code VALUE} to binary zeros, and its sibling
     * {@code WS-EDIT-SELECT-FLAGS PIC X(7) VALUE LOW-VALUES} [L72-73] states the same thing
     * explicitly. Either way the observable outcome is identical, because
     * {@code 88 WS-ROW-SELECT-ERROR VALUE '1'} [L88] is false for {@code x'00'} and for a space alike.
     */
    private String editSelectErrorFlags;

    /**
     * {@code CCARD-NEXT-PROG PIC X(8)} [{@code app/cpy/CVCRD01Y.cpy:21}] - the program the client
     * should call next, replacing the {@code XCTL} transfers at
     * {@code app/cbl/COCRDLIC.cbl:402, 538, 566}.
     *
     * <p>An opaque token: stored exactly as given, at its declared width, with no validation against a
     * known-program list, no case folding and no trimming.
     */
    private String nextProgram;

    /** {@code CCARD-NEXT-MAPSET PIC X(7)} [{@code app/cpy/CVCRD01Y.cpy:23}] - an opaque token. */
    private String nextMapset;

    /** {@code CCARD-NEXT-MAP PIC X(7)} [{@code app/cpy/CVCRD01Y.cpy:24}] - an opaque token. */
    private String nextMap;

    /**
     * The 58-byte paging cursor the client echoes back, never held server-side between calls.
     *
     * <p>Immutable, so replacing it is always a whole-value assignment and a half-updated cursor cannot
     * exist.
     */
    private PageCursor pageCursor;

    /**
     * The {@code CC-WORK-AREA} carried out to the client, because {@code COCRDLIC} copies
     * {@code CVCRD01Y} at {@code app/cbl/COCRDLIC.cbl:221}.
     */
    private CardScreenState cardScreenState;

    /**
     * The {@code CARDDEMO-COMMAREA} carried out to the client, because {@code COCRDLIC} copies
     * {@code COCOM01Y} at {@code app/cbl/COCRDLIC.cbl:227}.
     *
     * <p>Immutable ({@link NavigationContext} is a {@code record}), which is what makes carrying it in
     * the payload safe.
     */
    private NavigationContext navigationContext;

    // =================================================================================================
    // Construction.
    // =================================================================================================

    /**
     * Creates a response in the state {@code 1100-SCREEN-INIT} leaves the map in at its first
     * statement: {@code MOVE LOW-VALUES TO CCRDLIAO} [{@code app/cbl/COCRDLIC.cbl:643}].
     *
     * <p>Concretely: every one of the 45 payload members holds {@code LOW-VALUES} at its declared
     * width, every attribute byte is {@code 0x00}, {@code WS-EDIT-SELECT-ERROR-FLAGS} is seven
     * {@code LOW-VALUES}, the navigation triple is spaces at its declared widths, the cursor is
     * {@link PageCursor#initialised()}, the work area is a fresh {@link CardScreenState} and the commarea
     * is {@link NavigationContext#empty()}.
     *
     * <p>{@code LOW-VALUES} is a genuine third state: it is not spaces, and it is not {@code null}. A
     * later write pads with spaces, because that is the {@code PIC X} move rule, and the two are never
     * conflated.
     *
     * <p>No Spring context and no framework is needed - a test constructs this directly.
     */
    public CardListResponse() {
        this.payload = new LinkedHashMap<>();
        this.attributes = new LinkedHashMap<>();
        for (MapField field : MAP_FIELDS) {
            this.payload.put(field.itemName(), CardScreenState.lowValues(field.length()));
            this.attributes.put(field.screenFieldPrefix(), new FieldAttributes());
        }
        this.editSelectErrorFlags = CardScreenState.lowValues(SELECT_FLAGS_LENGTH);
        this.nextProgram = CardScreenState.spaces(NEXT_PROGRAM_LENGTH);
        this.nextMapset = CardScreenState.spaces(NEXT_MAPSET_LENGTH);
        this.nextMap = CardScreenState.spaces(NEXT_MAP_LENGTH);
        this.pageCursor = PageCursor.initialised();
        this.cardScreenState = new CardScreenState();
        this.navigationContext = NavigationContext.empty();
    }

    /**
     * Deep copy constructor. Every mutable part is copied, so the two responses share nothing: a write
     * through one can never be seen through the other.
     *
     * @param other the response to copy; never {@code null}
     * @throws NullPointerException if {@code other} is {@code null}
     */
    public CardListResponse(CardListResponse other) {
        Objects.requireNonNull(other, "A response to copy is required");
        this.payload = new LinkedHashMap<>(other.payload);
        this.attributes = new LinkedHashMap<>();
        for (Map.Entry<String, FieldAttributes> entry : other.attributes.entrySet()) {
            this.attributes.put(entry.getKey(), new FieldAttributes(entry.getValue()));
        }
        this.editSelectErrorFlags = other.editSelectErrorFlags;
        this.nextProgram = other.nextProgram;
        this.nextMapset = other.nextMapset;
        this.nextMap = other.nextMap;
        this.pageCursor = other.pageCursor;
        this.cardScreenState = new CardScreenState(other.cardScreenState);
        this.navigationContext = other.navigationContext;
    }

    /**
     * Restores the whole response to the state {@code MOVE LOW-VALUES TO CCRDLIAO}
     * [{@code app/cbl/COCRDLIC.cbl:643}] produces: all 45 payload members and all 45 attribute quads
     * back to {@code LOW-VALUES}.
     *
     * <p>The navigation triple, the cursor, the work area and the commarea are left alone, because
     * L643 moves {@code LOW-VALUES} to the symbolic map only - it does not touch
     * {@code WS-THIS-PROGCOMMAREA}, {@code CC-WORK-AREA} or {@code CARDDEMO-COMMAREA}.
     */
    public void moveLowValuesToMap() {
        for (MapField field : MAP_FIELDS) {
            this.payload.put(field.itemName(), CardScreenState.lowValues(field.length()));
        }
        for (FieldAttributes quad : this.attributes.values()) {
            quad.reset();
        }
    }

    // =================================================================================================
    // Name-addressed payload access.
    //
    // The generic path CSSETATY and 1200-SCREEN-ARRAY-INIT need. Every typed accessor below delegates
    // here, so the PIC X move rule is applied in exactly one place.
    // =================================================================================================

    /**
     * The descriptor of one output item.
     *
     * @param itemName an {@code xxxO} item name, for example {@link #CRDSTP2O_ITEM}
     * @return its descriptor; never {@code null}
     * @throws NullPointerException     if {@code itemName} is {@code null}
     * @throws IllegalArgumentException if {@code itemName} names no item of {@code CCRDLIAO}. An
     *                                  unknown name is rejected rather than ignored: a silently
     *                                  dropped write would leave the field at its initialised value,
     *                                  which is the hardest kind of parity defect to trace
     */
    public static MapField mapField(String itemName) {
        Objects.requireNonNull(itemName, "An output item name is required");
        MapField field = MAP_FIELDS_BY_ITEM.get(itemName);
        if (field == null) {
            throw new IllegalArgumentException("'" + itemName + "' is not an item of 01 CCRDLIAO; "
                    + "app/cpy-bms/COCRDLI.CPY declares " + PAYLOAD_FIELD_COUNT + " output items and "
                    + "this is not one of them");
        }
        return field;
    }

    /**
     * The descriptor of the field carrying a given {@code DFHMDF} label.
     *
     * @param screenFieldPrefix a {@code DFHMDF} label, for example {@code "CRDSTP2"}
     * @return its descriptor; never {@code null}
     * @throws NullPointerException     if {@code screenFieldPrefix} is {@code null}
     * @throws IllegalArgumentException if the label names no field of this map. {@code "CRDSTP1"} is
     *                                  rejected here, because row 1 has no such field
     */
    public static MapField mapFieldByPrefix(String screenFieldPrefix) {
        Objects.requireNonNull(screenFieldPrefix, "A DFHMDF label is required");
        MapField field = MAP_FIELDS_BY_PREFIX.get(screenFieldPrefix);
        if (field == null) {
            throw new IllegalArgumentException("'" + screenFieldPrefix + "' is not a name-labelled "
                    + "DFHMDF of app/bms/COCRDLI.bms; the mapset declares " + PAYLOAD_FIELD_COUNT
                    + " labelled fields of 72 DFHMDF entries, and note that row 1 has no CRDSTP1");
        }
        return field;
    }

    /**
     * Reads one payload member by item name, untrimmed and exactly its declared width.
     *
     * @param itemName an {@code xxxO} item name
     * @return the item's current value; never {@code null}
     * @throws NullPointerException     if {@code itemName} is {@code null}
     * @throws IllegalArgumentException if {@code itemName} names no item of {@code CCRDLIAO}
     */
    @JsonIgnore
    public String field(String itemName) {
        return payload.get(mapField(itemName).itemName());
    }

    /**
     * Writes one payload member by item name, applying the {@code PIC X} move rule: right-padded with
     * spaces when the value is shorter than the item, truncated on the right when it is longer.
     *
     * <p>Right truncation is the COBOL rule for a {@code PIC X} receiver, and it is applied through
     * {@link FixedWidthCodec#movePicX(String, int)} rather than by assignment precisely so the
     * direction is never in doubt. This is the only path by which a payload member changes value.
     *
     * @param itemName an {@code xxxO} item name
     * @param value    the sending value, of any length; to blank a field, move
     *                 {@link CardScreenState#spaces(int)} or {@link #LOW_VALUE} explicitly rather than
     *                 passing {@code null}
     * @throws NullPointerException     if {@code itemName} or {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code itemName} names no item of {@code CCRDLIAO}
     */
    public void setField(String itemName, String value) {
        MapField field = mapField(itemName);
        Objects.requireNonNull(value, "A sending value is required for a MOVE to " + field.itemName()
                + "; to blank the field move SPACES or LOW-VALUES explicitly");
        payload.put(field.itemName(), PIC_X_MOVE_CODEC.movePicX(value, field.length()));
    }

    /**
     * Every payload member, keyed by item name in copybook order.
     *
     * <p>The map a parity differ walks: 45 entries, each an untrimmed image at its declared width.
     * Unmodifiable and a snapshot, so no caller can write through it.
     *
     * @return an unmodifiable, insertion-ordered copy of the 45 items
     */
    @JsonIgnore
    public Map<String, String> fieldImages() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    /**
     * The number of payload members, which is fixed at {@value #PAYLOAD_FIELD_COUNT} for the lifetime
     * of an instance.
     *
     * @return {@value #PAYLOAD_FIELD_COUNT}
     */
    @JsonIgnore
    public int payloadFieldCount() {
        return payload.size();
    }

    // =================================================================================================
    // HEADER BLOCK - 9 payload members, app/cpy-bms/COCRDLI.CPY:296-344.
    //
    // Declared in copybook order. PAGENOO is seventh, between CURTIMEO and ACCTSIDO, and stays there.
    // =================================================================================================

    /**
     * {@code TRNNAMEO PIC X(4)} [{@code COCRDLI.CPY:296}], {@code DFHMDF TRNNAME COLOR=BLUE,
     * LENGTH=4, POS=(1,7)} [{@code .bms:34-37}].
     *
     * <p>Written by {@code MOVE LIT-THISTRANID TO TRNNAMEO OF CCRDLIAO}
     * [{@code app/cbl/COCRDLIC.cbl:649}] - see {@link #LIT_THISTRANID}.
     *
     * @return the four-character transaction identifier, untrimmed
     */
    public String getTrnnameo() {
        return field(TRNNAMEO_ITEM);
    }

    /** @param value the transaction identifier; moved into {@code TRNNAMEO} by the {@code PIC X} rule */
    public void setTrnnameo(String value) {
        setField(TRNNAMEO_ITEM, value);
    }

    /**
     * {@code TITLE01O PIC X(40)} [{@code COCRDLI.CPY:302}], {@code DFHMDF TITLE01 COLOR=YELLOW,
     * LENGTH=40, POS=(1,21)} [{@code .bms:38-41}].
     *
     * <p>Written by {@code MOVE CCDA-TITLE01 TO TITLE01O OF CCRDLIAO}
     * [{@code app/cbl/COCRDLIC.cbl:647}] - see {@link ScreenTitles#CCDA_TITLE01}, which is exactly 40
     * characters and so needs no padding.
     *
     * @return the upper title line, untrimmed
     */
    public String getTitle01o() {
        return field(TITLE01O_ITEM);
    }

    /** @param value the upper title line; moved into {@code TITLE01O} by the {@code PIC X} rule */
    public void setTitle01o(String value) {
        setField(TITLE01O_ITEM, value);
    }

    /**
     * {@code CURDATEO PIC X(8)} [{@code COCRDLI.CPY:308}], {@code DFHMDF CURDATE COLOR=BLUE,
     * LENGTH=8, POS=(1,71), INITIAL='mm/dd/yy'} [{@code .bms:47-51}].
     *
     * <p>Written by {@code MOVE WS-CURDATE-MM-DD-YY TO CURDATEO OF CCRDLIAO}
     * [{@code app/cbl/COCRDLIC.cbl:658}] - see {@link #applyDateHeader(DateHeader)}.
     *
     * @return the {@code mm/dd/yy} date, untrimmed
     */
    public String getCurdateo() {
        return field(CURDATEO_ITEM);
    }

    /** @param value the {@code mm/dd/yy} date; moved into {@code CURDATEO} by the {@code PIC X} rule */
    public void setCurdateo(String value) {
        setField(CURDATEO_ITEM, value);
    }

    /**
     * {@code PGMNAMEO PIC X(8)} [{@code COCRDLI.CPY:314}], {@code DFHMDF PGMNAME COLOR=BLUE,
     * LENGTH=8, POS=(2,7)} [{@code .bms:57-60}].
     *
     * <p>Written by {@code MOVE LIT-THISPGM TO PGMNAMEO OF CCRDLIAO}
     * [{@code app/cbl/COCRDLIC.cbl:650}] - see {@link #LIT_THISPGM}.
     *
     * @return the eight-character program name, untrimmed
     */
    public String getPgmnameo() {
        return field(PGMNAMEO_ITEM);
    }

    /** @param value the program name; moved into {@code PGMNAMEO} by the {@code PIC X} rule */
    public void setPgmnameo(String value) {
        setField(PGMNAMEO_ITEM, value);
    }

    /**
     * {@code TITLE02O PIC X(40)} [{@code COCRDLI.CPY:320}], {@code DFHMDF TITLE02 COLOR=YELLOW,
     * LENGTH=40, POS=(2,21)} [{@code .bms:61-64}].
     *
     * <p>Written by {@code MOVE CCDA-TITLE02 TO TITLE02O OF CCRDLIAO}
     * [{@code app/cbl/COCRDLIC.cbl:648}] - see {@link ScreenTitles#CCDA_TITLE02}.
     *
     * @return the lower title line, untrimmed
     */
    public String getTitle02o() {
        return field(TITLE02O_ITEM);
    }

    /** @param value the lower title line; moved into {@code TITLE02O} by the {@code PIC X} rule */
    public void setTitle02o(String value) {
        setField(TITLE02O_ITEM, value);
    }

    /**
     * {@code CURTIMEO PIC X(8)} [{@code COCRDLI.CPY:326}], {@code DFHMDF CURTIME COLOR=BLUE,
     * LENGTH=8, POS=(2,71), INITIAL='hh:mm:ss'} [{@code .bms:70-74}].
     *
     * <p>Written by {@code MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO OF CCRDLIAO}
     * [{@code app/cbl/COCRDLIC.cbl:664}].
     *
     * @return the {@code hh:mm:ss} time, untrimmed
     */
    public String getCurtimeo() {
        return field(CURTIMEO_ITEM);
    }

    /** @param value the {@code hh:mm:ss} time; moved into {@code CURTIMEO} by the {@code PIC X} rule */
    public void setCurtimeo(String value) {
        setField(CURTIMEO_ITEM, value);
    }

    /**
     * {@code PAGENOO PIC X(3)} [{@code COCRDLI.CPY:332}], {@code DFHMDF PAGENO LENGTH=3, POS=(4,76)}
     * [{@code .bms:82-83}] - the only card map that has this field.
     *
     * <p>Written by {@code MOVE WS-CA-SCREEN-NUM TO PAGENOO OF CCRDLIAO}
     * [{@code app/cbl/COCRDLIC.cbl:667}]. That is a numeric sender into an alphanumeric receiver, so
     * the <em>alphanumeric</em> rule applies and the digit is left-justified with trailing spaces -
     * see {@link #setPagenooFromScreenNum(int)}, which is the accessor to use for it.
     *
     * @return the three-character page number image, untrimmed
     */
    public String getPagenoo() {
        return field(PAGENOO_ITEM);
    }

    /** @param value the page number image; moved into {@code PAGENOO} by the {@code PIC X} rule */
    public void setPagenoo(String value) {
        setField(PAGENOO_ITEM, value);
    }

    /**
     * {@code ACCTSIDO PIC X(11)} [{@code COCRDLI.CPY:338}], {@code DFHMDF ACCTSID
     * ATTRB=(FSET,IC,NORM,UNPROT), COLOR=GREEN, HILIGHT=UNDERLINE, LENGTH=11, POS=(6,44)}
     * [{@code .bms:89-93}] - the account-number filter echoed back to the user.
     *
     * <p>Written on three branches of the {@code EVALUATE} at {@code app/cbl/COCRDLIC.cbl:844-854}:
     * {@code MOVE CC-ACCT-ID} [L847] when the filter was supplied, {@code MOVE LOW-VALUES} [L850] when
     * the commarea carries no account, and {@code MOVE CDEMO-ACCT-ID} [L852] otherwise. All three are
     * reachable through this setter; {@code LOW-VALUES} is written by moving
     * {@link CardScreenState#lowValues(int)}.
     *
     * @return the eleven-character account filter, untrimmed
     */
    public String getAcctsido() {
        return field(ACCTSIDO_ITEM);
    }

    /** @param value the account filter; moved into {@code ACCTSIDO} by the {@code PIC X} rule */
    public void setAcctsido(String value) {
        setField(ACCTSIDO_ITEM, value);
    }

    /**
     * {@code CARDSIDO PIC X(16)} [{@code COCRDLI.CPY:344}], {@code DFHMDF CARDSID
     * ATTRB=(FSET,NORM,UNPROT), COLOR=GREEN, HILIGHT=UNDERLINE, LENGTH=16, POS=(7,44)}
     * [{@code .bms:101-105}] - the card-number filter echoed back to the user.
     *
     * <p>Written on the three branches of the {@code EVALUATE} at
     * {@code app/cbl/COCRDLIC.cbl:856-867}, mirroring {@link #getAcctsido()}.
     *
     * <p>This is a full sixteen-character card number in the clear, exactly as the symbolic map
     * carries it. No masking, redaction or {@code @JsonIgnore} is applied: the legacy screen shows the
     * number, and both hiding it and exposing anything further would be a behaviour change.
     *
     * @return the sixteen-character card filter, untrimmed
     */
    public String getCardsido() {
        return field(CARDSIDO_ITEM);
    }

    /** @param value the card filter; moved into {@code CARDSIDO} by the {@code PIC X} rule */
    public void setCardsido(String value) {
        setField(CARDSIDO_ITEM, value);
    }

    // =================================================================================================
    // ROW 1 - FOUR payload members, app/cpy-bms/COCRDLI.CPY:350-368.
    //
    // There is deliberately no getCrdstp1o()/setCrdstp1o(). Row 1 has no CRDSTP1O item, no CRDSTP1
    // DFHMDF and no descriptor in MAP_FIELDS. Adding one would make the payload 46 fields and put 1
    // byte of phantom data in the middle of the group, shifting the 35 fields that follow.
    // =================================================================================================

    /**
     * {@code CRDSEL1O PIC X(1)} [{@code COCRDLI.CPY:350}], {@code DFHMDF CRDSEL1
     * ATTRB=(FSET,NORM,PROT), COLOR=DEFAULT, HILIGHT=UNDERLINE, LENGTH=1, POS=(11,12)}
     * [{@code .bms:140-144}].
     *
     * <p>Written by {@code MOVE WS-EDIT-SELECT(1) TO CRDSEL1O OF CCRDLIAO}
     * [{@code app/cbl/COCRDLIC.cbl:683}], and additionally by {@code MOVE '*' TO CRDSEL1O OF CCRDLIAO}
     * [L758] when the row is in error and the selection character is blank - the row-1-only branch of
     * {@code 1250-SETUP-ARRAY-ATTRIBS}. See {@link #applyRowSelectHighlight(int, boolean)}.
     *
     * @return the one-character selection value for row 1
     */
    public String getCrdsel1o() {
        return field(CRDSEL1O_ITEM);
    }

    /** @param value the row-1 selection character; moved into {@code CRDSEL1O} by the {@code PIC X} rule */
    public void setCrdsel1o(String value) {
        setField(CRDSEL1O_ITEM, value);
    }

    /**
     * {@code ACCTNO1O PIC X(11)} [{@code COCRDLI.CPY:356}], {@code DFHMDF ACCTNO1 ATTRB=(NORM,PROT),
     * COLOR=DEFAULT, HILIGHT=OFF, LENGTH=11, POS=(11,22)} [{@code .bms:147-151}].
     *
     * <p>Written by {@code MOVE WS-ROW-ACCTNO(1) TO ACCTNO1O OF CCRDLIAO}
     * [{@code app/cbl/COCRDLIC.cbl:684}].
     *
     * @return the eleven-character account number of row 1
     */
    public String getAcctno1o() {
        return field(ACCTNO1O_ITEM);
    }

    /** @param value the row-1 account number; moved into {@code ACCTNO1O} by the {@code PIC X} rule */
    public void setAcctno1o(String value) {
        setField(ACCTNO1O_ITEM, value);
    }

    /**
     * {@code CRDNUM1O PIC X(16)} [{@code COCRDLI.CPY:362}], {@code DFHMDF CRDNUM1 ATTRB=(NORM,PROT),
     * COLOR=DEFAULT, HILIGHT=OFF, LENGTH=16, POS=(11,43)} [{@code .bms:152-156}].
     *
     * <p>Written by {@code MOVE WS-ROW-CARD-NUM(1) TO CRDNUM1O OF CCRDLIAO}
     * [{@code app/cbl/COCRDLIC.cbl:685}].
     *
     * @return the sixteen-character card number of row 1, in the clear as the map carries it
     */
    public String getCrdnum1o() {
        return field(CRDNUM1O_ITEM);
    }

    /** @param value the row-1 card number; moved into {@code CRDNUM1O} by the {@code PIC X} rule */
    public void setCrdnum1o(String value) {
        setField(CRDNUM1O_ITEM, value);
    }

    /**
     * {@code CRDSTS1O PIC X(1)} [{@code COCRDLI.CPY:368}], {@code DFHMDF CRDSTS1 ATTRB=(NORM,PROT),
     * COLOR=DEFAULT, HILIGHT=OFF, LENGTH=1, POS=(11,67)} [{@code .bms:157-161}].
     *
     * <p>Written by {@code MOVE WS-ROW-CARD-STATUS(1) TO CRDSTS1O OF CCRDLIAO}
     * [{@code app/cbl/COCRDLIC.cbl:686}].
     *
     * @return the one-character card status of row 1
     */
    public String getCrdsts1o() {
        return field(CRDSTS1O_ITEM);
    }

    /** @param value the row-1 card status; moved into {@code CRDSTS1O} by the {@code PIC X} rule */
    public void setCrdsts1o(String value) {
        setField(CRDSTS1O_ITEM, value);
    }

    // =================================================================================================
    // ROWS 2 to 7 - FIVE payload members each, app/cpy-bms/COCRDLI.CPY:374-548.
    //
    // The order within a row is CRDSELnO, CRDSTPnO, ACCTNOnO, CRDNUMnO, CRDSTSnO - with CRDSTPnO
    // SECOND, not last. It is the hidden selection-type field, declared ATTRB=(ASKIP,DRK,FSET) at the
    // same POS as the row's unnamed zero-length field, so it never appears on the screen even though it
    // occupies a byte of the map. Its position determines the offset of the three fields after it.
    //
    // Each row's accessors are written out rather than generated, because the copybook writes them out
    // and a reviewer must be able to read the two side by side.
    // =================================================================================================

    /**
     * {@code CRDSEL2O PIC X(1)} [{@code COCRDLI.CPY:374}], {@code DFHMDF CRDSEL2} [{@code .bms:162}];
     * written by {@code MOVE WS-EDIT-SELECT(2)} [{@code app/cbl/COCRDLIC.cbl:692}].
     *
     * @return the one-character selection value for row 2
     */
    public String getCrdsel2o() {
        return field(CRDSEL2O_ITEM);
    }

    /** @param value the row-2 selection character, moved by the {@code PIC X} rule */
    public void setCrdsel2o(String value) {
        setField(CRDSEL2O_ITEM, value);
    }

    /**
     * {@code CRDSTP2O PIC X(1)} [{@code COCRDLI.CPY:380}], {@code DFHMDF CRDSTP2
     * ATTRB=(ASKIP,DRK,FSET), LENGTH=1, POS=(12,14)} [{@code .bms:169-173}] - the hidden
     * selection-type field, second in the row.
     *
     * @return the one-character hidden selection type for row 2
     */
    public String getCrdstp2o() {
        return field(CRDSTP2O_ITEM);
    }

    /** @param value the row-2 hidden selection type, moved by the {@code PIC X} rule */
    public void setCrdstp2o(String value) {
        setField(CRDSTP2O_ITEM, value);
    }

    /**
     * {@code ACCTNO2O PIC X(11)} [{@code COCRDLI.CPY:386}], {@code DFHMDF ACCTNO2} [{@code .bms:174}];
     * written by {@code MOVE WS-ROW-ACCTNO(2)} [{@code app/cbl/COCRDLIC.cbl:693}].
     *
     * @return the eleven-character account number of row 2
     */
    public String getAcctno2o() {
        return field(ACCTNO2O_ITEM);
    }

    /** @param value the row-2 account number, moved by the {@code PIC X} rule */
    public void setAcctno2o(String value) {
        setField(ACCTNO2O_ITEM, value);
    }

    /**
     * {@code CRDNUM2O PIC X(16)} [{@code COCRDLI.CPY:392}], {@code DFHMDF CRDNUM2} [{@code .bms:179}];
     * written by {@code MOVE WS-ROW-CARD-NUM(2)} [{@code app/cbl/COCRDLIC.cbl:694}].
     *
     * @return the sixteen-character card number of row 2, in the clear as the map carries it
     */
    public String getCrdnum2o() {
        return field(CRDNUM2O_ITEM);
    }

    /** @param value the row-2 card number, moved by the {@code PIC X} rule */
    public void setCrdnum2o(String value) {
        setField(CRDNUM2O_ITEM, value);
    }

    /**
     * {@code CRDSTS2O PIC X(1)} [{@code COCRDLI.CPY:398}], {@code DFHMDF CRDSTS2} [{@code .bms:184}];
     * written by {@code MOVE WS-ROW-CARD-STATUS(2)} [{@code app/cbl/COCRDLIC.cbl:695}].
     *
     * @return the one-character card status of row 2
     */
    public String getCrdsts2o() {
        return field(CRDSTS2O_ITEM);
    }

    /** @param value the row-2 card status, moved by the {@code PIC X} rule */
    public void setCrdsts2o(String value) {
        setField(CRDSTS2O_ITEM, value);
    }

    /**
     * {@code CRDSEL3O PIC X(1)} [{@code COCRDLI.CPY:404}], {@code DFHMDF CRDSEL3} [{@code .bms:189}];
     * written by {@code MOVE WS-EDIT-SELECT(3)} [{@code app/cbl/COCRDLIC.cbl:701}].
     *
     * @return the one-character selection value for row 3
     */
    public String getCrdsel3o() {
        return field(CRDSEL3O_ITEM);
    }

    /** @param value the row-3 selection character, moved by the {@code PIC X} rule */
    public void setCrdsel3o(String value) {
        setField(CRDSEL3O_ITEM, value);
    }

    /**
     * {@code CRDSTP3O PIC X(1)} [{@code COCRDLI.CPY:410}], {@code DFHMDF CRDSTP3} [{@code .bms:196}] -
     * the hidden selection-type field, second in the row.
     *
     * @return the one-character hidden selection type for row 3
     */
    public String getCrdstp3o() {
        return field(CRDSTP3O_ITEM);
    }

    /** @param value the row-3 hidden selection type, moved by the {@code PIC X} rule */
    public void setCrdstp3o(String value) {
        setField(CRDSTP3O_ITEM, value);
    }

    /**
     * {@code ACCTNO3O PIC X(11)} [{@code COCRDLI.CPY:416}], {@code DFHMDF ACCTNO3} [{@code .bms:201}];
     * written by {@code MOVE WS-ROW-ACCTNO(3)} [{@code app/cbl/COCRDLIC.cbl:702}].
     *
     * @return the eleven-character account number of row 3
     */
    public String getAcctno3o() {
        return field(ACCTNO3O_ITEM);
    }

    /** @param value the row-3 account number, moved by the {@code PIC X} rule */
    public void setAcctno3o(String value) {
        setField(ACCTNO3O_ITEM, value);
    }

    /**
     * {@code CRDNUM3O PIC X(16)} [{@code COCRDLI.CPY:422}], {@code DFHMDF CRDNUM3} [{@code .bms:206}];
     * written by {@code MOVE WS-ROW-CARD-NUM(3)} [{@code app/cbl/COCRDLIC.cbl:703}].
     *
     * @return the sixteen-character card number of row 3, in the clear as the map carries it
     */
    public String getCrdnum3o() {
        return field(CRDNUM3O_ITEM);
    }

    /** @param value the row-3 card number, moved by the {@code PIC X} rule */
    public void setCrdnum3o(String value) {
        setField(CRDNUM3O_ITEM, value);
    }

    /**
     * {@code CRDSTS3O PIC X(1)} [{@code COCRDLI.CPY:428}], {@code DFHMDF CRDSTS3} [{@code .bms:211}];
     * written by {@code MOVE WS-ROW-CARD-STATUS(3)} [{@code app/cbl/COCRDLIC.cbl:704}].
     *
     * @return the one-character card status of row 3
     */
    public String getCrdsts3o() {
        return field(CRDSTS3O_ITEM);
    }

    /** @param value the row-3 card status, moved by the {@code PIC X} rule */
    public void setCrdsts3o(String value) {
        setField(CRDSTS3O_ITEM, value);
    }

    /**
     * {@code CRDSEL4O PIC X(1)} [{@code COCRDLI.CPY:434}], {@code DFHMDF CRDSEL4} [{@code .bms:216}];
     * written by {@code MOVE WS-EDIT-SELECT(4)} [{@code app/cbl/COCRDLIC.cbl:710}].
     *
     * @return the one-character selection value for row 4
     */
    public String getCrdsel4o() {
        return field(CRDSEL4O_ITEM);
    }

    /** @param value the row-4 selection character, moved by the {@code PIC X} rule */
    public void setCrdsel4o(String value) {
        setField(CRDSEL4O_ITEM, value);
    }

    /**
     * {@code CRDSTP4O PIC X(1)} [{@code COCRDLI.CPY:440}], {@code DFHMDF CRDSTP4} [{@code .bms:223}] -
     * the hidden selection-type field, second in the row.
     *
     * @return the one-character hidden selection type for row 4
     */
    public String getCrdstp4o() {
        return field(CRDSTP4O_ITEM);
    }

    /** @param value the row-4 hidden selection type, moved by the {@code PIC X} rule */
    public void setCrdstp4o(String value) {
        setField(CRDSTP4O_ITEM, value);
    }

    /**
     * {@code ACCTNO4O PIC X(11)} [{@code COCRDLI.CPY:446}], {@code DFHMDF ACCTNO4} [{@code .bms:228}];
     * written by {@code MOVE WS-ROW-ACCTNO(4)} [{@code app/cbl/COCRDLIC.cbl:711}].
     *
     * @return the eleven-character account number of row 4
     */
    public String getAcctno4o() {
        return field(ACCTNO4O_ITEM);
    }

    /** @param value the row-4 account number, moved by the {@code PIC X} rule */
    public void setAcctno4o(String value) {
        setField(ACCTNO4O_ITEM, value);
    }

    /**
     * {@code CRDNUM4O PIC X(16)} [{@code COCRDLI.CPY:452}], {@code DFHMDF CRDNUM4} [{@code .bms:233}];
     * written by {@code MOVE WS-ROW-CARD-NUM(4)} [{@code app/cbl/COCRDLIC.cbl:712}].
     *
     * @return the sixteen-character card number of row 4, in the clear as the map carries it
     */
    public String getCrdnum4o() {
        return field(CRDNUM4O_ITEM);
    }

    /** @param value the row-4 card number, moved by the {@code PIC X} rule */
    public void setCrdnum4o(String value) {
        setField(CRDNUM4O_ITEM, value);
    }

    /**
     * {@code CRDSTS4O PIC X(1)} [{@code COCRDLI.CPY:458}], {@code DFHMDF CRDSTS4} [{@code .bms:238}];
     * written by {@code MOVE WS-ROW-CARD-STATUS(4)} [{@code app/cbl/COCRDLIC.cbl:713}].
     *
     * @return the one-character card status of row 4
     */
    public String getCrdsts4o() {
        return field(CRDSTS4O_ITEM);
    }

    /** @param value the row-4 card status, moved by the {@code PIC X} rule */
    public void setCrdsts4o(String value) {
        setField(CRDSTS4O_ITEM, value);
    }

    /**
     * {@code CRDSEL5O PIC X(1)} [{@code COCRDLI.CPY:464}], {@code DFHMDF CRDSEL5} [{@code .bms:243}];
     * written by {@code MOVE WS-EDIT-SELECT(5)} [{@code app/cbl/COCRDLIC.cbl:719}].
     *
     * @return the one-character selection value for row 5
     */
    public String getCrdsel5o() {
        return field(CRDSEL5O_ITEM);
    }

    /** @param value the row-5 selection character, moved by the {@code PIC X} rule */
    public void setCrdsel5o(String value) {
        setField(CRDSEL5O_ITEM, value);
    }

    /**
     * {@code CRDSTP5O PIC X(1)} [{@code COCRDLI.CPY:470}], {@code DFHMDF CRDSTP5} [{@code .bms:250}] -
     * the hidden selection-type field, second in the row.
     *
     * @return the one-character hidden selection type for row 5
     */
    public String getCrdstp5o() {
        return field(CRDSTP5O_ITEM);
    }

    /** @param value the row-5 hidden selection type, moved by the {@code PIC X} rule */
    public void setCrdstp5o(String value) {
        setField(CRDSTP5O_ITEM, value);
    }

    /**
     * {@code ACCTNO5O PIC X(11)} [{@code COCRDLI.CPY:476}], {@code DFHMDF ACCTNO5} [{@code .bms:255}];
     * written by {@code MOVE WS-ROW-ACCTNO(5)} [{@code app/cbl/COCRDLIC.cbl:720}].
     *
     * @return the eleven-character account number of row 5
     */
    public String getAcctno5o() {
        return field(ACCTNO5O_ITEM);
    }

    /** @param value the row-5 account number, moved by the {@code PIC X} rule */
    public void setAcctno5o(String value) {
        setField(ACCTNO5O_ITEM, value);
    }

    /**
     * {@code CRDNUM5O PIC X(16)} [{@code COCRDLI.CPY:482}], {@code DFHMDF CRDNUM5} [{@code .bms:260}];
     * written by {@code MOVE WS-ROW-CARD-NUM(5)} [{@code app/cbl/COCRDLIC.cbl:721}].
     *
     * @return the sixteen-character card number of row 5, in the clear as the map carries it
     */
    public String getCrdnum5o() {
        return field(CRDNUM5O_ITEM);
    }

    /** @param value the row-5 card number, moved by the {@code PIC X} rule */
    public void setCrdnum5o(String value) {
        setField(CRDNUM5O_ITEM, value);
    }

    /**
     * {@code CRDSTS5O PIC X(1)} [{@code COCRDLI.CPY:488}], {@code DFHMDF CRDSTS5} [{@code .bms:265}];
     * written by {@code MOVE WS-ROW-CARD-STATUS(5)} [{@code app/cbl/COCRDLIC.cbl:722}].
     *
     * @return the one-character card status of row 5
     */
    public String getCrdsts5o() {
        return field(CRDSTS5O_ITEM);
    }

    /** @param value the row-5 card status, moved by the {@code PIC X} rule */
    public void setCrdsts5o(String value) {
        setField(CRDSTS5O_ITEM, value);
    }

    /**
     * {@code CRDSEL6O PIC X(1)} [{@code COCRDLI.CPY:494}], {@code DFHMDF CRDSEL6} [{@code .bms:270}];
     * written by {@code MOVE WS-EDIT-SELECT(6)} [{@code app/cbl/COCRDLIC.cbl:729}].
     *
     * @return the one-character selection value for row 6
     */
    public String getCrdsel6o() {
        return field(CRDSEL6O_ITEM);
    }

    /** @param value the row-6 selection character, moved by the {@code PIC X} rule */
    public void setCrdsel6o(String value) {
        setField(CRDSEL6O_ITEM, value);
    }

    /**
     * {@code CRDSTP6O PIC X(1)} [{@code COCRDLI.CPY:500}], {@code DFHMDF CRDSTP6} [{@code .bms:277}] -
     * the hidden selection-type field, second in the row.
     *
     * @return the one-character hidden selection type for row 6
     */
    public String getCrdstp6o() {
        return field(CRDSTP6O_ITEM);
    }

    /** @param value the row-6 hidden selection type, moved by the {@code PIC X} rule */
    public void setCrdstp6o(String value) {
        setField(CRDSTP6O_ITEM, value);
    }

    /**
     * {@code ACCTNO6O PIC X(11)} [{@code COCRDLI.CPY:506}], {@code DFHMDF ACCTNO6} [{@code .bms:282}];
     * written by {@code MOVE WS-ROW-ACCTNO(6)} [{@code app/cbl/COCRDLIC.cbl:730}].
     *
     * @return the eleven-character account number of row 6
     */
    public String getAcctno6o() {
        return field(ACCTNO6O_ITEM);
    }

    /** @param value the row-6 account number, moved by the {@code PIC X} rule */
    public void setAcctno6o(String value) {
        setField(ACCTNO6O_ITEM, value);
    }

    /**
     * {@code CRDNUM6O PIC X(16)} [{@code COCRDLI.CPY:512}], {@code DFHMDF CRDNUM6} [{@code .bms:287}];
     * written by {@code MOVE WS-ROW-CARD-NUM(6)} [{@code app/cbl/COCRDLIC.cbl:731}].
     *
     * @return the sixteen-character card number of row 6, in the clear as the map carries it
     */
    public String getCrdnum6o() {
        return field(CRDNUM6O_ITEM);
    }

    /** @param value the row-6 card number, moved by the {@code PIC X} rule */
    public void setCrdnum6o(String value) {
        setField(CRDNUM6O_ITEM, value);
    }

    /**
     * {@code CRDSTS6O PIC X(1)} [{@code COCRDLI.CPY:518}], {@code DFHMDF CRDSTS6} [{@code .bms:292}];
     * written by {@code MOVE WS-ROW-CARD-STATUS(6)} [{@code app/cbl/COCRDLIC.cbl:732}].
     *
     * @return the one-character card status of row 6
     */
    public String getCrdsts6o() {
        return field(CRDSTS6O_ITEM);
    }

    /** @param value the row-6 card status, moved by the {@code PIC X} rule */
    public void setCrdsts6o(String value) {
        setField(CRDSTS6O_ITEM, value);
    }

    /**
     * {@code CRDSEL7O PIC X(1)} [{@code COCRDLI.CPY:524}], {@code DFHMDF CRDSEL7} [{@code .bms:297}];
     * written by {@code MOVE WS-EDIT-SELECT(7)} [{@code app/cbl/COCRDLIC.cbl:738}].
     *
     * @return the one-character selection value for row 7, the last row
     */
    public String getCrdsel7o() {
        return field(CRDSEL7O_ITEM);
    }

    /** @param value the row-7 selection character, moved by the {@code PIC X} rule */
    public void setCrdsel7o(String value) {
        setField(CRDSEL7O_ITEM, value);
    }

    /**
     * {@code CRDSTP7O PIC X(1)} [{@code COCRDLI.CPY:530}], {@code DFHMDF CRDSTP7} [{@code .bms:304}] -
     * the hidden selection-type field, second in the row.
     *
     * @return the one-character hidden selection type for row 7
     */
    public String getCrdstp7o() {
        return field(CRDSTP7O_ITEM);
    }

    /** @param value the row-7 hidden selection type, moved by the {@code PIC X} rule */
    public void setCrdstp7o(String value) {
        setField(CRDSTP7O_ITEM, value);
    }

    /**
     * {@code ACCTNO7O PIC X(11)} [{@code COCRDLI.CPY:536}], {@code DFHMDF ACCTNO7} [{@code .bms:309}];
     * written by {@code MOVE WS-ROW-ACCTNO(7)} [{@code app/cbl/COCRDLIC.cbl:739}].
     *
     * @return the eleven-character account number of row 7
     */
    public String getAcctno7o() {
        return field(ACCTNO7O_ITEM);
    }

    /** @param value the row-7 account number, moved by the {@code PIC X} rule */
    public void setAcctno7o(String value) {
        setField(ACCTNO7O_ITEM, value);
    }

    /**
     * {@code CRDNUM7O PIC X(16)} [{@code COCRDLI.CPY:542}], {@code DFHMDF CRDNUM7} [{@code .bms:314}];
     * written by {@code MOVE WS-ROW-CARD-NUM(7)} [{@code app/cbl/COCRDLIC.cbl:740}].
     *
     * @return the sixteen-character card number of row 7, in the clear as the map carries it
     */
    public String getCrdnum7o() {
        return field(CRDNUM7O_ITEM);
    }

    /** @param value the row-7 card number, moved by the {@code PIC X} rule */
    public void setCrdnum7o(String value) {
        setField(CRDNUM7O_ITEM, value);
    }

    /**
     * {@code CRDSTS7O PIC X(1)} [{@code COCRDLI.CPY:548}], {@code DFHMDF CRDSTS7} [{@code .bms:319}];
     * written by {@code MOVE WS-ROW-CARD-STATUS(7)} [{@code app/cbl/COCRDLIC.cbl:741}].
     *
     * @return the one-character card status of row 7
     */
    public String getCrdsts7o() {
        return field(CRDSTS7O_ITEM);
    }

    /** @param value the row-7 card status, moved by the {@code PIC X} rule */
    public void setCrdsts7o(String value) {
        setField(CRDSTS7O_ITEM, value);
    }

    // =================================================================================================
    // FOOTER BLOCK - 2 payload members, app/cpy-bms/COCRDLI.CPY:554-560.
    //
    // 45 and 78 characters. The sibling card maps declare 40 and 80, and there is no FKEYS field here
    // at all - the F-key legend is an unnamed literal DFHMDF at POS=(24,1) [.bms:336-339], so it has no
    // symbolic-map item and gets no Java field.
    // =================================================================================================

    /**
     * {@code INFOMSGO PIC X(45)} [{@code COCRDLI.CPY:554}], {@code DFHMDF INFOMSG ATTRB=(PROT),
     * COLOR=NEUTRAL, HILIGHT=OFF, LENGTH=45, POS=(20,19)} [{@code .bms:324-328}].
     *
     * <p>Written by {@code MOVE WS-INFO-MSG TO INFOMSGO OF CCRDLIAO} at
     * {@code app/cbl/COCRDLIC.cbl:670} and again at L928. The program pairs each write with a colour:
     * {@code MOVE DFHBMDAR TO INFOMSGC} [L671] darkens the field when there is no message, and
     * {@code MOVE DFHNEUTR TO INFOMSGC} [L929] lights it when there is - see
     * {@link #fieldAttributes(String)}.
     *
     * @return the forty-five-character information line, untrimmed
     */
    public String getInfomsgo() {
        return field(INFOMSGO_ITEM);
    }

    /**
     * @param value the information line; moved into {@code INFOMSGO} by the {@code PIC X} rule, so a
     *              {@code PIC X(50)} message from {@link SystemMessages} loses its last five
     *              characters exactly as the COBOL {@code MOVE} would
     */
    public void setInfomsgo(String value) {
        setField(INFOMSGO_ITEM, value);
    }

    /**
     * {@code ERRMSGO PIC X(78)} [{@code COCRDLI.CPY:560}], {@code DFHMDF ERRMSG
     * ATTRB=(ASKIP,BRT,FSET), COLOR=RED, LENGTH=78, POS=(23,1)} [{@code .bms:331-334}].
     *
     * <p>Written by {@code MOVE WS-ERROR-MSG TO ERRMSGO OF CCRDLIAO}
     * [{@code app/cbl/COCRDLIC.cbl:924}], unconditionally - the field is set on every send, and is
     * blank when there is no error.
     *
     * @return the seventy-eight-character error line, untrimmed
     */
    public String getErrmsgo() {
        return field(ERRMSGO_ITEM);
    }

    /**
     * @param value the error line; moved into {@code ERRMSGO} by the {@code PIC X} rule, so a shorter
     *              message is right-padded with spaces to the full 78 characters
     */
    public void setErrmsgo(String value) {
        setField(ERRMSGO_ITEM, value);
    }

    // =================================================================================================
    // Row addressing: COBOL subscripts are 1-based, Java indices are 0-based.
    //
    // This is named as the top defect risk of the whole migration, so the conversion is a named,
    // bounds-checked operation rather than a "- 1" buried in an expression. Every row-addressed method
    // below takes a COBOL subscript in 1..7, because that is the vocabulary the source uses:
    // WS-ROW-ACCTNO(1) through WS-ROW-ACCTNO(7), WS-EDIT-SELECT(1) through (7).
    // =================================================================================================

    /** {@code CRDSELnO} for rows 1 to 7, in row order - all seven rows have this field. */
    private static final List<String> CRDSEL_ITEMS = List.of(CRDSEL1O_ITEM, CRDSEL2O_ITEM,
            CRDSEL3O_ITEM, CRDSEL4O_ITEM, CRDSEL5O_ITEM, CRDSEL6O_ITEM, CRDSEL7O_ITEM);

    /**
     * {@code CRDSTPnO} for rows <strong>2 to 7</strong>, in row order - <strong>six</strong> entries,
     * not seven, because row 1 has no such field. The list is deliberately short rather than padded
     * with a placeholder: a seven-element list here is exactly the mistake the asymmetry invites.
     */
    private static final List<String> CRDSTP_ITEMS = List.of(CRDSTP2O_ITEM, CRDSTP3O_ITEM,
            CRDSTP4O_ITEM, CRDSTP5O_ITEM, CRDSTP6O_ITEM, CRDSTP7O_ITEM);

    /** {@code ACCTNOnO} for rows 1 to 7, in row order. */
    private static final List<String> ACCTNO_ITEMS = List.of(ACCTNO1O_ITEM, ACCTNO2O_ITEM,
            ACCTNO3O_ITEM, ACCTNO4O_ITEM, ACCTNO5O_ITEM, ACCTNO6O_ITEM, ACCTNO7O_ITEM);

    /** {@code CRDNUMnO} for rows 1 to 7, in row order. */
    private static final List<String> CRDNUM_ITEMS = List.of(CRDNUM1O_ITEM, CRDNUM2O_ITEM,
            CRDNUM3O_ITEM, CRDNUM4O_ITEM, CRDNUM5O_ITEM, CRDNUM6O_ITEM, CRDNUM7O_ITEM);

    /** {@code CRDSTSnO} for rows 1 to 7, in row order. */
    private static final List<String> CRDSTS_ITEMS = List.of(CRDSTS1O_ITEM, CRDSTS2O_ITEM,
            CRDSTS3O_ITEM, CRDSTS4O_ITEM, CRDSTS5O_ITEM, CRDSTS6O_ITEM, CRDSTS7O_ITEM);

    /**
     * What {@link #toString()} substitutes for a redacted value: {@value}.
     *
     * <p>The same marker the other payloads in this module use, so a log line reads consistently
     * whichever screen produced it.
     */
    private static final String REDACTED = "[REDACTED]";

    /**
     * The sixteen items {@link #toString()} redacts: the card and account filters, and the seven
     * {@code CRDNUMnO} / {@code ACCTNOnO} pairs.
     *
     * <p>Scoped to card numbers and account identifiers, and to nothing else. {@code CRDSTSnO} is a
     * one-character status, {@code CRDSELnO} is a selection character and {@code CRDSTPnO} is an
     * attribute stopper; none of them identifies a person or an instrument, so redacting them would
     * cost diagnostic value for no gain. This set is used only by {@link #toString()} - the JSON body
     * and every fixed-width image carry all sixteen in the clear, exactly as the symbolic map does.
     */
    private static final Set<String> REDACTED_ITEMS = buildRedactedItems();

    private static Set<String> buildRedactedItems() {
        Set<String> items = new LinkedHashSet<>();
        items.add(ACCTSIDO_ITEM);
        items.add(CARDSIDO_ITEM);
        items.addAll(ACCTNO_ITEMS);
        items.addAll(CRDNUM_ITEMS);
        return Set.copyOf(items);
    }

    /** The first COBOL subscript of every seven-element structure on this screen. */
    public static final int FIRST_ROW = 1;

    /** The last COBOL subscript of every seven-element structure on this screen: {@value #ROW_COUNT}. */
    public static final int LAST_ROW = ROW_COUNT;

    /**
     * Converts a COBOL subscript into the Java index of the same element.
     *
     * <p>{@code WS-EDIT-SELECT(1)} is the first element and {@code WS-EDIT-SELECT(7)} the last;
     * {@code screenRows().get(0)} and {@code screenRows().get(6)} are the same two. Off-by-one here
     * would silently shift every row's data by one row, which no compiler and no round-trip test would
     * notice, so the conversion is stated once, here, and used everywhere.
     *
     * @param cobolSubscript a subscript in {@value #FIRST_ROW}{@code ..}{@value #LAST_ROW} inclusive
     * @return the corresponding Java index in {@code 0..}{@value #ROW_COUNT}{@code -1}
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is outside the declared
     *                                   {@code OCCURS} range. A subscript of {@code 0} is rejected
     *                                   too: COBOL has no element zero
     */
    public static int toJavaIndex(int cobolSubscript) {
        if (cobolSubscript < FIRST_ROW || cobolSubscript > LAST_ROW) {
            throw new IndexOutOfBoundsException("OCCURS subscript " + cobolSubscript + " is outside "
                    + FIRST_ROW + ".." + LAST_ROW + "; app/cbl/COCRDLIC.cbl declares OCCURS "
                    + ROW_COUNT + " TIMES, and COBOL subscripts start at 1 rather than 0");
        }
        return cobolSubscript - 1;
    }

    /**
     * Converts a Java index into the COBOL subscript of the same element - the inverse of
     * {@link #toJavaIndex(int)}.
     *
     * @param javaIndex an index in {@code 0..}{@value #ROW_COUNT}{@code -1} inclusive
     * @return the corresponding COBOL subscript in {@value #FIRST_ROW}{@code ..}{@value #LAST_ROW}
     * @throws IndexOutOfBoundsException if {@code javaIndex} is outside the array
     */
    public static int toCobolSubscript(int javaIndex) {
        if (javaIndex < 0 || javaIndex >= ROW_COUNT) {
            throw new IndexOutOfBoundsException("Java index " + javaIndex + " is outside 0.."
                    + (ROW_COUNT - 1) + " for a " + ROW_COUNT + "-element OCCURS table");
        }
        return javaIndex + 1;
    }

    /**
     * The number of payload members a row exposes: {@value #ROW_1_FIELD_COUNT} for row 1 and
     * {@value #ROW_N_FIELD_COUNT} for rows 2 to 7.
     *
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@value #LAST_ROW}
     * @return {@value #ROW_1_FIELD_COUNT} or {@value #ROW_N_FIELD_COUNT}
     * @throws IndexOutOfBoundsException if {@code cobolRow} is outside the declared range
     */
    public static int rowFieldCount(int cobolRow) {
        toJavaIndex(cobolRow);
        return cobolRow == FIRST_ROW ? ROW_1_FIELD_COUNT : ROW_N_FIELD_COUNT;
    }

    /**
     * Whether a row has a {@code CRDSTPnO} item.
     *
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@value #LAST_ROW}
     * @return {@code false} for row 1 and {@code true} for rows 2 to 7
     * @throws IndexOutOfBoundsException if {@code cobolRow} is outside the declared range
     */
    public static boolean hasCrdstpItem(int cobolRow) {
        toJavaIndex(cobolRow);
        return cobolRow != FIRST_ROW;
    }

    /**
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@value #LAST_ROW}
     * @return the {@code CRDSELnO} item name for that row
     * @throws IndexOutOfBoundsException if {@code cobolRow} is outside the declared range
     */
    public static String crdselItem(int cobolRow) {
        return CRDSEL_ITEMS.get(toJavaIndex(cobolRow));
    }

    /**
     * @param cobolRow a row subscript in {@code 2..}{@value #LAST_ROW}
     * @return the {@code CRDSTPnO} item name for that row
     * @throws IndexOutOfBoundsException if {@code cobolRow} is outside the declared range, or is
     *                                   {@value #FIRST_ROW} - row 1 has no {@code CRDSTP1O}, in the
     *                                   symbolic map or in the mapset
     */
    public static String crdstpItem(int cobolRow) {
        int javaIndex = toJavaIndex(cobolRow);
        if (!hasCrdstpItem(cobolRow)) {
            throw new IndexOutOfBoundsException("Row " + cobolRow + " has no CRDSTP item: "
                    + "app/cpy-bms/COCRDLI.CPY runs CRDSEL1O at line 350 straight into ACCTNO1O at "
                    + "line 356, and app/bms/COCRDLI.bms declares no CRDSTP1 label. Rows 2.."
                    + LAST_ROW + " have one; row " + FIRST_ROW + " does not");
        }
        return CRDSTP_ITEMS.get(javaIndex - 1);
    }

    /**
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@value #LAST_ROW}
     * @return the {@code ACCTNOnO} item name for that row
     * @throws IndexOutOfBoundsException if {@code cobolRow} is outside the declared range
     */
    public static String acctnoItem(int cobolRow) {
        return ACCTNO_ITEMS.get(toJavaIndex(cobolRow));
    }

    /**
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@value #LAST_ROW}
     * @return the {@code CRDNUMnO} item name for that row
     * @throws IndexOutOfBoundsException if {@code cobolRow} is outside the declared range
     */
    public static String crdnumItem(int cobolRow) {
        return CRDNUM_ITEMS.get(toJavaIndex(cobolRow));
    }

    /**
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@value #LAST_ROW}
     * @return the {@code CRDSTSnO} item name for that row
     * @throws IndexOutOfBoundsException if {@code cobolRow} is outside the declared range
     */
    public static String crdstsItem(int cobolRow) {
        return CRDSTS_ITEMS.get(toJavaIndex(cobolRow));
    }

    // =================================================================================================
    // WS-SCREEN-ROWS - the seven-element browse result, projected over the row payload members.
    //
    // 1200-SCREEN-ARRAY-INIT [app/cbl/COCRDLIC.cbl:678-742] moves WS-ROW-ACCTNO(n), WS-ROW-CARD-NUM(n)
    // and WS-ROW-CARD-STATUS(n) into ACCTNOnO, CRDNUMnO and CRDSTSnO. Those three payload members are
    // therefore the row, and a row is read back out of them rather than out of a second private copy:
    // one source of truth cannot fall out of step with itself, and a duplicate would be exactly the
    // kind of near-invisible drift a field-by-field parity diff exists to catch.
    // =================================================================================================

    /**
     * One row of the browse result, as the three payload members currently hold it.
     *
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@value #LAST_ROW} - the
     *                 subscript {@code WS-SCREEN-ROWS(n)} uses, not a Java index
     * @return the row; never {@code null}
     * @throws IndexOutOfBoundsException if {@code cobolRow} is outside the declared range
     */
    @JsonIgnore
    public ScreenRow screenRow(int cobolRow) {
        return new ScreenRow(field(acctnoItem(cobolRow)), field(crdnumItem(cobolRow)),
                field(crdstsItem(cobolRow)));
    }

    /**
     * Writes one row of the browse result into its three payload members - the
     * {@code 1200-SCREEN-ARRAY-INIT} moves for that row.
     *
     * <p>The row's selection character is <em>not</em> written here, because the COBOL moves it
     * separately from {@code WS-EDIT-SELECT(n)}; use {@link #setEditSelect(int, String)} for it.
     *
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@value #LAST_ROW}
     * @param row      the row to write; never {@code null}
     * @throws NullPointerException      if {@code row} is {@code null}
     * @throws IndexOutOfBoundsException if {@code cobolRow} is outside the declared range
     */
    public void setScreenRow(int cobolRow, ScreenRow row) {
        Objects.requireNonNull(row, "A row is required; to blank a row move ScreenRow.lowValues()");
        setField(acctnoItem(cobolRow), row.rowAcctno());
        setField(crdnumItem(cobolRow), row.rowCardNum());
        setField(crdstsItem(cobolRow), row.rowCardStatus());
    }

    /**
     * All seven rows, in row order.
     *
     * <p>Element {@code 0} is {@code WS-SCREEN-ROWS(1)} and element {@code 6} is
     * {@code WS-SCREEN-ROWS(7)} - see {@link #toJavaIndex(int)}. The list is unmodifiable and holds
     * immutable elements, so nothing can be written back through it; {@link #setScreenRow(int,
     * ScreenRow)} is the write path.
     *
     * @return an unmodifiable list of exactly {@value #ROW_COUNT} rows
     */
    @JsonIgnore
    public List<ScreenRow> screenRows() {
        List<ScreenRow> rows = new ArrayList<>(ROW_COUNT);
        for (int cobolRow = FIRST_ROW; cobolRow <= LAST_ROW; cobolRow++) {
            rows.add(screenRow(cobolRow));
        }
        return Collections.unmodifiableList(rows);
    }

    /**
     * Writes all seven rows at once.
     *
     * @param rows exactly {@value #ROW_COUNT} rows in row order, element {@code 0} being
     *             {@code WS-SCREEN-ROWS(1)}
     * @throws NullPointerException     if {@code rows} or any element is {@code null}
     * @throws IllegalArgumentException if {@code rows} does not hold exactly {@value #ROW_COUNT}
     *                                  elements; a partial write would leave the remaining rows at
     *                                  whatever they held, which is a plausible-looking screen and a
     *                                  wrong one
     */
    public void setScreenRows(List<ScreenRow> rows) {
        Objects.requireNonNull(rows, "Seven rows are required");
        if (rows.size() != ROW_COUNT) {
            throw new IllegalArgumentException("Supplied " + rows.size() + " row(s); "
                    + "WS-SCREEN-ROWS OCCURS " + ROW_COUNT + " TIMES, so a full write needs exactly "
                    + ROW_COUNT + ". To blank an unused row pass ScreenRow.lowValues()");
        }
        for (int cobolRow = FIRST_ROW; cobolRow <= LAST_ROW; cobolRow++) {
            setScreenRow(cobolRow, rows.get(toJavaIndex(cobolRow)));
        }
    }

    /**
     * {@code WS-ALL-ROWS PIC X(196)} [{@code app/cbl/COCRDLIC.cbl:253}] - the seven rows as the single
     * group image the {@code REDEFINES} at L254-255 slices.
     *
     * @return exactly {@value #SCREEN_ARRAY_LENGTH} characters, seven rows of
     *         {@value #SCREEN_ROW_LENGTH}
     */
    @JsonIgnore
    public String allRowsImage() {
        StringBuilder image = new StringBuilder(SCREEN_ARRAY_LENGTH);
        for (int cobolRow = FIRST_ROW; cobolRow <= LAST_ROW; cobolRow++) {
            image.append(screenRow(cobolRow).image());
        }
        return image.toString();
    }

    /**
     * Writes all seven rows from a {@code WS-ALL-ROWS} group image.
     *
     * @param image exactly {@value #SCREEN_ARRAY_LENGTH} characters
     * @throws NullPointerException     if {@code image} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly
     *                                  {@value #SCREEN_ARRAY_LENGTH} characters
     */
    public void setAllRowsImage(String image) {
        requireExactWidth(image, SCREEN_ARRAY_LENGTH, "WS-ALL-ROWS");
        for (int cobolRow = FIRST_ROW; cobolRow <= LAST_ROW; cobolRow++) {
            int start = toJavaIndex(cobolRow) * SCREEN_ROW_LENGTH;
            setScreenRow(cobolRow, ScreenRow.fromImage(image.substring(start,
                    start + SCREEN_ROW_LENGTH)));
        }
    }

    // =================================================================================================
    // WS-EDIT-SELECT - the seven selection characters and their FOUR 88-levels.
    //
    //    05 WS-EDIT-SELECT-FLAGS  PIC X(7) VALUE LOW-VALUES.                    [COCRDLIC.cbl:72-73]
    //    05 WS-EDIT-SELECT-ARRAY REDEFINES WS-EDIT-SELECT-FLAGS.                [L74]
    //       10 WS-EDIT-SELECT     PIC X(1) OCCURS 7 TIMES.                      [L75-76]
    //          88 SELECT-OK             VALUES 'S', 'U'.                        [L77]
    //          88 VIEW-REQUESTED-ON     VALUE 'S'.                              [L78]
    //          88 UPDATE-REQUESTED-ON   VALUE 'U'.                              [L79]
    //          88 SELECT-BLANK          VALUES ' ', LOW-VALUES.                 [L80-82]
    //
    // All four are modelled, not just the two that name an action: 2250-EDIT-ARRAY [L1099-1115] switches
    // on SELECT-OK, then SELECT-BLANK, then WHEN OTHER, and dropping SELECT-BLANK would turn a blank row
    // into an invalid action code.
    //
    // These characters are projected over CRDSELnO because 1200-SCREEN-ARRAY-INIT moves
    // WS-EDIT-SELECT(n) into it [L683, 692, 701, 710, 719, 729, 738]. The response carries them so the
    // client can echo them back on the next turn, which is what keeps paging and selection stateless.
    // =================================================================================================

    /**
     * {@code WS-EDIT-SELECT(n)} - one row's selection character.
     *
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@value #LAST_ROW}
     * @return exactly one character; {@link #LOW_VALUE} on a freshly constructed response
     * @throws IndexOutOfBoundsException if {@code cobolRow} is outside the declared range
     */
    @JsonIgnore
    public String editSelect(int cobolRow) {
        return field(crdselItem(cobolRow));
    }

    /**
     * Writes one row's selection character.
     *
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@value #LAST_ROW}
     * @param value    the selection character - {@link #SELECT_VIEW}, {@link #SELECT_UPDATE},
     *                 {@link #SPACE} or {@link #LOW_VALUE}. Any other character is stored as given,
     *                 because {@code 2250-EDIT-ARRAY}'s {@code WHEN OTHER} branch [L1108-1113] is what
     *                 rejects it and this class must be able to represent the state that branch sees
     * @throws NullPointerException      if {@code value} is {@code null}
     * @throws IndexOutOfBoundsException if {@code cobolRow} is outside the declared range
     */
    public void setEditSelect(int cobolRow, String value) {
        setField(crdselItem(cobolRow), value);
    }

    /**
     * {@code WS-EDIT-SELECT-FLAGS PIC X(7)} - the seven selection characters as one group image, the
     * form {@code INSPECT WS-EDIT-SELECT-FLAGS TALLYING ... FOR ALL 'S' ALL 'U'}
     * [{@code app/cbl/COCRDLIC.cbl:1079-1082}] counts over.
     *
     * @return exactly {@value #SELECT_FLAGS_LENGTH} characters; seven {@link #LOW_VALUE} on a freshly
     *         constructed response
     */
    @JsonIgnore
    public String editSelectFlags() {
        StringBuilder flags = new StringBuilder(SELECT_FLAGS_LENGTH);
        for (int cobolRow = FIRST_ROW; cobolRow <= LAST_ROW; cobolRow++) {
            flags.append(editSelect(cobolRow));
        }
        return flags.toString();
    }

    /**
     * Writes all seven selection characters from a group image.
     *
     * @param flags exactly {@value #SELECT_FLAGS_LENGTH} characters
     * @throws NullPointerException     if {@code flags} is {@code null}
     * @throws IllegalArgumentException if {@code flags} is not exactly
     *                                  {@value #SELECT_FLAGS_LENGTH} characters
     */
    public void setEditSelectFlags(String flags) {
        requireExactWidth(flags, SELECT_FLAGS_LENGTH, "WS-EDIT-SELECT-FLAGS");
        for (int cobolRow = FIRST_ROW; cobolRow <= LAST_ROW; cobolRow++) {
            int javaIndex = toJavaIndex(cobolRow);
            setEditSelect(cobolRow, flags.substring(javaIndex, javaIndex + CRDSEL_LENGTH));
        }
    }

    /**
     * {@code 88 SELECT-OK VALUES 'S', 'U'} [{@code app/cbl/COCRDLIC.cbl:77}] - the row requests an
     * action, whichever one.
     *
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@value #LAST_ROW}
     * @return {@code true} when the selection character is {@code 'S'} or {@code 'U'}
     * @throws IndexOutOfBoundsException if {@code cobolRow} is outside the declared range
     */
    public boolean isSelectOk(int cobolRow) {
        String value = editSelect(cobolRow);
        return SELECT_VIEW.equals(value) || SELECT_UPDATE.equals(value);
    }

    /**
     * {@code 88 VIEW-REQUESTED-ON VALUE 'S'} [{@code app/cbl/COCRDLIC.cbl:78}] - the row requests the
     * card detail view, which drives the {@code XCTL} at L538.
     *
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@value #LAST_ROW}
     * @return {@code true} when the selection character is {@code 'S'}
     * @throws IndexOutOfBoundsException if {@code cobolRow} is outside the declared range
     */
    public boolean isViewRequestedOn(int cobolRow) {
        return SELECT_VIEW.equals(editSelect(cobolRow));
    }

    /**
     * {@code 88 UPDATE-REQUESTED-ON VALUE 'U'} [{@code app/cbl/COCRDLIC.cbl:79}] - the row requests
     * the card update screen, which drives the {@code XCTL} at L566.
     *
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@value #LAST_ROW}
     * @return {@code true} when the selection character is {@code 'U'}
     * @throws IndexOutOfBoundsException if {@code cobolRow} is outside the declared range
     */
    public boolean isUpdateRequestedOn(int cobolRow) {
        return SELECT_UPDATE.equals(editSelect(cobolRow));
    }

    /**
     * {@code 88 SELECT-BLANK VALUES ' ', LOW-VALUES} [{@code app/cbl/COCRDLIC.cbl:80-82}] - the row
     * requests nothing.
     *
     * <p>This condition name lists <strong>two</strong> values and is true for <em>either</em>: a space
     * and {@code LOW-VALUES}. It is the one place on this screen where the space / {@code LOW-VALUES}
     * distinction is deliberately ignored, and it is ignored because the copybook says so - the array
     * initialises to {@code LOW-VALUES} while a user who types a space and presses {@code ENTER} sends
     * a space, and both mean "no action on this row".
     *
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@value #LAST_ROW}
     * @return {@code true} when the selection character is a space or {@code LOW-VALUES}
     * @throws IndexOutOfBoundsException if {@code cobolRow} is outside the declared range
     */
    public boolean isSelectBlank(int cobolRow) {
        String value = editSelect(cobolRow);
        return SPACE.equals(value) || LOW_VALUE.equals(value);
    }

    // =================================================================================================
    // WS-EDIT-SELECT-ERRORS - the seven row-error signals. Metadata: addressable, never on the wire.
    //
    //    05 WS-EDIT-SELECT-ERROR-FLAGS  PIC X(7).                               [COCRDLIC.cbl:83]
    //    05 WS-EDIT-SELECT-ERROR-FLAGX REDEFINES WS-EDIT-SELECT-ERROR-FLAGS.    [L84-85]
    //       10 WS-EDIT-SELECT-ERRORS OCCURS 7 TIMES.                            [L86]
    //          20 WS-ROW-CRDSELECT-ERROR  PIC X(1).                             [L87]
    //             88 WS-ROW-SELECT-ERROR  VALUE '1'.                            [L88]
    //
    // Set two ways by 2250-EDIT-ARRAY: wholesale, by MOVE WS-EDIT-SELECT-FLAGS TO
    // WS-EDIT-SELECT-ERROR-FLAGS followed by INSPECT ... REPLACING ALL 'S' BY '1' ALL 'U' BY '1'
    // CHARACTERS BY '0' [L1088-1093] when more than one row was selected; and per row, by
    // MOVE '1' TO WS-ROW-CRDSELECT-ERROR(I) [L1104, L1110].
    //
    // Read by 1250-SETUP-ARRAY-ATTRIBS at L755, 768, 780, 792, 803, 815 and 826 - one test per row.
    // =================================================================================================

    /**
     * {@code WS-ROW-CRDSELECT-ERROR(n)} - one row's error signal.
     *
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@value #LAST_ROW}
     * @return exactly one character; {@link #LOW_VALUE} on a freshly constructed response
     * @throws IndexOutOfBoundsException if {@code cobolRow} is outside the declared range
     */
    @JsonIgnore
    public String wsRowCrdselectError(int cobolRow) {
        int javaIndex = toJavaIndex(cobolRow);
        return editSelectErrorFlags.substring(javaIndex, javaIndex + CRDSEL_LENGTH);
    }

    /**
     * Writes one row's error signal - {@code MOVE '1' TO WS-ROW-CRDSELECT-ERROR(I)}.
     *
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@value #LAST_ROW}
     * @param value    exactly one character; {@link #ROW_SELECT_ERROR} marks the row in error
     * @throws NullPointerException      if {@code value} is {@code null}
     * @throws IllegalArgumentException  if {@code value} is not exactly one character
     * @throws IndexOutOfBoundsException if {@code cobolRow} is outside the declared range
     */
    public void setWsRowCrdselectError(int cobolRow, String value) {
        int javaIndex = toJavaIndex(cobolRow);
        requireExactWidth(value, CRDSEL_LENGTH, "WS-ROW-CRDSELECT-ERROR");
        this.editSelectErrorFlags = editSelectErrorFlags.substring(0, javaIndex) + value
                + editSelectErrorFlags.substring(javaIndex + CRDSEL_LENGTH);
    }

    /**
     * {@code 88 WS-ROW-SELECT-ERROR VALUE '1'} [{@code app/cbl/COCRDLIC.cbl:88}] - the test
     * {@code 1250-SETUP-ARRAY-ATTRIBS} makes once per row before it reddens that row's selection field.
     *
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@value #LAST_ROW}
     * @return {@code true} only when the signal is {@code '1'} - a space and {@code LOW-VALUES} are
     *         both "no error"
     * @throws IndexOutOfBoundsException if {@code cobolRow} is outside the declared range
     */
    public boolean isWsRowSelectError(int cobolRow) {
        return ROW_SELECT_ERROR.equals(wsRowCrdselectError(cobolRow));
    }

    /**
     * {@code WS-EDIT-SELECT-ERROR-FLAGS PIC X(7)} - the group image, the form the
     * {@code MOVE}/{@code INSPECT REPLACING} pair at {@code app/cbl/COCRDLIC.cbl:1088-1093} rewrites
     * whole.
     *
     * @return exactly {@value #SELECT_FLAGS_LENGTH} characters
     */
    @JsonIgnore
    public String editSelectErrorFlags() {
        return editSelectErrorFlags;
    }

    /**
     * Writes the whole {@code WS-EDIT-SELECT-ERROR-FLAGS} group.
     *
     * @param flags exactly {@value #SELECT_FLAGS_LENGTH} characters
     * @throws NullPointerException     if {@code flags} is {@code null}
     * @throws IllegalArgumentException if {@code flags} is not exactly
     *                                  {@value #SELECT_FLAGS_LENGTH} characters
     */
    public void setEditSelectErrorFlags(String flags) {
        requireExactWidth(flags, SELECT_FLAGS_LENGTH, "WS-EDIT-SELECT-ERROR-FLAGS");
        this.editSelectErrorFlags = flags;
    }

    // =================================================================================================
    // The xxxC / xxxP / xxxH / xxxV quads, and the two highlight paths that write into them.
    // =================================================================================================

    /**
     * The live attribute quad of one screen field - the object a
     * {@code MOVE ... TO xxxC OF CCRDLIAO} writes into.
     *
     * <p>Deliberately the live object and not a copy: {@link FieldAttributeSetter} decides that a
     * colour must be moved into a named item, and something has to be able to move it. Each response
     * owns its own 45 quads and the copy constructor deep-copies them, so a live reference can be
     * handed out without two responses ever coming to share one.
     *
     * @param screenFieldPrefix a {@code DFHMDF} label, for example {@code "ACCTSID"} or
     *                          {@code "CRDSEL1"}
     * @return that field's quad; never {@code null}
     * @throws NullPointerException     if {@code screenFieldPrefix} is {@code null}
     * @throws IllegalArgumentException if the label names no field of this map - {@code "CRDSTP1"} and
     *                                  {@code "FKEYS"} are both rejected, because neither exists here
     */
    @JsonIgnore
    public FieldAttributes fieldAttributes(String screenFieldPrefix) {
        return attributes.get(mapFieldByPrefix(screenFieldPrefix).screenFieldPrefix());
    }

    /**
     * A snapshot of all 45 quads, keyed by {@code DFHMDF} label in copybook order, for diagnostics and
     * for a parity report.
     *
     * <p>The quads are copies, so writing to one changes nothing. {@link #fieldAttributes(String)} is
     * the write path.
     *
     * @return an unmodifiable, insertion-ordered map of copies
     */
    @JsonIgnore
    public Map<String, FieldAttributes> fieldAttributesSnapshot() {
        Map<String, FieldAttributes> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, FieldAttributes> entry : attributes.entrySet()) {
            snapshot.put(entry.getKey(), new FieldAttributes(entry.getValue()));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Applies a {@link FieldAttributeSetter} decision to this response - the
     * {@code app/cpy/CSSETATY.cpy} moves, performed.
     *
     * <p>The copybook is two nested {@code MOVE}s: {@code MOVE DFHRED TO (SCRNVAR2)C OF (MAPNAME3)O}
     * [{@code CSSETATY.cpy:21-22}] and, inside it, {@code MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O}
     * [L24-25]. This method performs exactly the ones the decision says to perform and nothing else:
     * the colour goes to the {@code xxxC} item and the asterisk to the {@code xxxO} item, never the
     * other way round, which the types alone guarantee since
     * {@link FieldHighlight#colourItemValue()} is a {@code byte} and
     * {@link FieldHighlight#outputItemValue()} is a {@code String}.
     *
     * <p>The {@code CDEMO-PGM-REENTER} guard [{@code CSSETATY.cpy:20}] lives in
     * {@link FieldAttributeSetter#resolve(FieldValidationState, boolean, String, String)}, not here, so
     * a decision reached on first entry arrives {@linkplain FieldHighlight#untouched() untouched} and
     * this method leaves the field exactly as the program painted it.
     *
     * @param highlight the decision to apply; never {@code null}
     * @throws NullPointerException     if {@code highlight} is {@code null}
     * @throws IllegalArgumentException if the decision assigns something but names no field, or names a
     *                                  field this map does not have
     */
    public void applyHighlight(FieldHighlight highlight) {
        Objects.requireNonNull(highlight, "A highlight decision is required");
        if (highlight.untouched()) {
            return;
        }
        String prefix = highlight.screenFieldPrefix();
        if (prefix.isEmpty()) {
            throw new IllegalArgumentException("The highlight assigns an item but carries no field "
                    + "prefix, so there is nothing to address. Resolve it with the four-argument "
                    + "FieldAttributeSetter.resolve so the decision names its field");
        }
        MapField field = mapFieldByPrefix(prefix);
        // Two separate tests, one per MOVE, mirroring the copybook's two nesting levels. The colour
        // test is in fact always true at this point - FieldHighlight's own invariant forbids an
        // assigned output item without an assigned colour item, and untouched() has already returned
        // above for the neither case - so its false arm is unreachable. It is written out all the same,
        // because the value of this method to a reviewer is that it reads as CSSETATY reads.
        if (highlight.colourItemAssigned()) {
            attributes.get(field.screenFieldPrefix()).setColour(highlight.colourItemValue());
        }
        if (highlight.outputItemAssigned()) {
            setField(field.itemName(), highlight.outputItemValue());
        }
    }

    /**
     * Resolves the {@code CSSETATY} decision for one field of this map and applies it in a single step.
     *
     * <p>The map name passed to the resolver is {@link #LIT_THISMAP} - {@code 'CCRDLIA'}
     * [{@code app/cbl/COCRDLIC.cbl:185-186}] - so the decision records the map it was made for and
     * {@link FieldHighlight#describe()} reads as the COBOL would.
     *
     * @param state             the field's validation outcome, the {@code (TESTVAR1)} analogue
     * @param reenter           {@code true} when {@code CDEMO-PGM-REENTER} holds, that is when
     *                          {@code CDEMO-PGM-CONTEXT} is
     *                          {@value NavigationContext#PGM_CONTEXT_REENTER}. On first entry the
     *                          field is left alone
     * @param screenFieldPrefix the {@code DFHMDF} label to highlight
     * @return the decision that was applied, for assertion and for logging; never {@code null}
     * @throws NullPointerException     if {@code state} or {@code screenFieldPrefix} is {@code null}
     * @throws IllegalArgumentException if the label names no field of this map
     */
    public FieldHighlight applyHighlight(FieldValidationState state, boolean reenter,
            String screenFieldPrefix) {
        MapField field = mapFieldByPrefix(screenFieldPrefix);
        FieldHighlight highlight = FieldAttributeSetter.resolve(state, reenter,
                field.screenFieldPrefix(), LIT_THISMAP);
        applyHighlight(highlight);
        return highlight;
    }

    /**
     * Applies {@code 1250-SETUP-ARRAY-ATTRIBS} [{@code app/cbl/COCRDLIC.cbl:748-836}] to one row - the
     * output-group half of it.
     *
     * <p>{@code COCRDLIC} does <strong>not</strong> copy {@code CSSETATY} (its only copybook include of
     * that family is {@code COPY 'CSSTRPFY'} at L1416), so its row highlighting is this inline variant
     * and not the shared one. Two differences from {@code CSSETATY} are real and are preserved rather
     * than smoothed over:
     * <ol>
     *   <li>There is <strong>no {@code CDEMO-PGM-REENTER} guard</strong>. The row error signal is only
     *       ever set during input editing, so the guard is redundant in this program - but it is absent
     *       from the source, and this method does not add it. Use
     *       {@link #applyHighlight(FieldValidationState, boolean, String)} where the {@code CSSETATY}
     *       behaviour with its guard is wanted.</li>
     *   <li>The {@code '*'} move happens for <strong>row 1 only</strong>. At L755-759 row 1 reddens the
     *       colour item and then, if its selection character is blank, moves {@code '*'} into
     *       {@code CRDSEL1O}. Rows 2 to 7 redden the colour item and instead move {@code -1} into
     *       {@code CRDSELnL OF CCRDLIAI} to place the cursor - an input-group item, and so
     *       {@code CardListRequest}'s concern, not this class's. That is a second row-1 asymmetry,
     *       independent of the field-count one, and it is in the source.</li>
     * </ol>
     *
     * <p>When the row is empty or the screen is protected the COBOL writes only
     * {@code DFHBMPRF}/{@code DFHBMPRO} into {@code CRDSELnA OF CCRDLIAI} [L753, L766] and touches
     * nothing in the output group, so this method makes no change and reports {@code false}.
     *
     * @param cobolRow           a row subscript in {@value #FIRST_ROW}{@code ..}{@value #LAST_ROW}
     * @param protectSelectRows  {@code true} when {@code FLG-PROTECT-SELECT-ROWS-YES} holds
     * @return {@code true} when the row's colour item was reddened, {@code false} when the row was left
     *         untouched
     * @throws IndexOutOfBoundsException if {@code cobolRow} is outside the declared range
     */
    public boolean applyRowSelectHighlight(int cobolRow, boolean protectSelectRows) {
        String selectionItem = crdselItem(cobolRow);
        // IF WS-EACH-CARD(n) EQUAL LOW-VALUES OR FLG-PROTECT-SELECT-ROWS-YES -> input group only.
        //
        // Testing the map's own row is exact rather than approximate: MOVE LOW-VALUES TO CCRDLIAO [L643]
        // sets the row to LOW-VALUES, and 1200-SCREEN-ARRAY-INIT overwrites it if and only if
        // WS-EACH-CARD(n) is not LOW-VALUES - so the map row is LOW-VALUES exactly when WS-EACH-CARD(n)
        // is.
        if (protectSelectRows || screenRow(cobolRow).isLowValues()) {
            return false;
        }
        // IF WS-ROW-CRDSELECT-ERROR(n) = '1'
        if (!isWsRowSelectError(cobolRow)) {
            return false;
        }
        // MOVE DFHRED TO CRDSELnC OF CCRDLIAO
        attributes.get(mapField(selectionItem).screenFieldPrefix()).setColour(BmsAttributes.DFHRED);
        // Row 1 only: IF WS-EDIT-SELECT(1) = SPACE OR LOW-VALUES -> MOVE '*' TO CRDSEL1O OF CCRDLIAO
        if (cobolRow == FIRST_ROW && isSelectBlank(cobolRow)) {
            setField(selectionItem, FieldAttributeSetter.ASTERISK);
        }
        return true;
    }

    // =================================================================================================
    // Navigation - the three XCTL sites, and the two carried areas.
    // =================================================================================================

    /**
     * {@code CCARD-NEXT-PROG} - the program the client should call next.
     *
     * @return the eight-character program name, untrimmed; spaces when no transfer is pending
     */
    public String getNextProgram() {
        return nextProgram;
    }

    /**
     * Sets the next program as an opaque token.
     *
     * <p>Stored exactly as given, at {@value #NEXT_PROGRAM_LENGTH} characters, through the
     * {@code PIC X} move rule. Nothing is validated against a list of known programs, nothing is
     * upper-cased and nothing is trimmed - {@code CardSelectController} preserves a source defect in
     * which {@code LIT-CCLISTMAP} is {@code 'CCRDSLA'} at {@code app/cbl/COCRDSLC.cbl:178} although the
     * map is really {@code 'CCRDLIA'}, and a token "corrected" on the way through here would break
     * that preservation.
     *
     * @param value the program name; use {@link #LIT_MENUPGM} for the L402 transfer
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void setNextProgram(String value) {
        Objects.requireNonNull(value, "A next-program token is required; to clear it move SPACES");
        this.nextProgram = PIC_X_MOVE_CODEC.movePicX(value, NEXT_PROGRAM_LENGTH);
    }

    /**
     * {@code CCARD-NEXT-MAPSET} - the mapset the client should render next.
     *
     * @return the seven-character mapset name, untrimmed
     */
    public String getNextMapset() {
        return nextMapset;
    }

    /**
     * Sets the next mapset as an opaque token, at {@value #NEXT_MAPSET_LENGTH} characters.
     *
     * @param value the mapset name; use {@link #LIT_THISMAPSET} to stay on this screen
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void setNextMapset(String value) {
        Objects.requireNonNull(value, "A next-mapset token is required; to clear it move SPACES");
        this.nextMapset = PIC_X_MOVE_CODEC.movePicX(value, NEXT_MAPSET_LENGTH);
    }

    /**
     * {@code CCARD-NEXT-MAP} - the map the client should render next.
     *
     * @return the seven-character map name, untrimmed
     */
    public String getNextMap() {
        return nextMap;
    }

    /**
     * Sets the next map as an opaque token, at {@value #NEXT_MAP_LENGTH} characters.
     *
     * @param value the map name; use {@link #LIT_THISMAP} to stay on this screen
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void setNextMap(String value) {
        Objects.requireNonNull(value, "A next-map token is required; to clear it move SPACES");
        this.nextMap = PIC_X_MOVE_CODEC.movePicX(value, NEXT_MAP_LENGTH);
    }

    /**
     * Sets all three navigation fields in one step, as each {@code XCTL} site's surrounding
     * {@code MOVE}s do.
     *
     * @param program the target program, for example {@link #LIT_MENUPGM}
     * @param mapset  the target mapset
     * @param map     the target map
     * @throws NullPointerException if any argument is {@code null}
     */
    public void setNextTarget(String program, String mapset, String map) {
        setNextProgram(program);
        setNextMapset(mapset);
        setNextMap(map);
    }

    /**
     * The paging cursor the client must echo back.
     *
     * @return the cursor; never {@code null}
     */
    public PageCursor getPageCursor() {
        return pageCursor;
    }

    /**
     * @param value the cursor to carry out; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void setPageCursor(PageCursor value) {
        this.pageCursor = Objects.requireNonNull(value, "A page cursor is required; the initial state "
                + "is PageCursor.initialised() and the after-INITIALIZE state is PageCursor.firstPage()");
    }

    /**
     * The {@code CC-WORK-AREA} carried out to the client.
     *
     * @return the work area; never {@code null}
     */
    public CardScreenState getCardScreenState() {
        return cardScreenState;
    }

    /**
     * @param value the work area to carry out; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void setCardScreenState(CardScreenState value) {
        this.cardScreenState = Objects.requireNonNull(value, "A CC-WORK-AREA is required");
    }

    /**
     * The {@code CARDDEMO-COMMAREA} carried out to the client.
     *
     * @return the commarea; never {@code null}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    /**
     * @param value the commarea to carry out; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void setNavigationContext(NavigationContext value) {
        this.navigationContext = Objects.requireNonNull(value, "A CARDDEMO-COMMAREA is required; the "
                + "empty state is NavigationContext.empty()");
    }

    // =================================================================================================
    // Header population - 1100-SCREEN-INIT [app/cbl/COCRDLIC.cbl:642-672], and the two standard message
    // texts.
    //
    // The literals come from the shared constant classes rather than being retyped here, because a
    // 40-character title with significant leading and trailing spaces retyped in a second place is a
    // 40-character title that will eventually differ from the first.
    // =================================================================================================

    /**
     * Moves the two screen titles from {@link ScreenTitles} -
     * {@code MOVE CCDA-TITLE01 TO TITLE01O OF CCRDLIAO} and
     * {@code MOVE CCDA-TITLE02 TO TITLE02O OF CCRDLIAO} [{@code app/cbl/COCRDLIC.cbl:647-648}].
     *
     * <p>Both literals are exactly {@value ScreenTitles#TITLE_LENGTH} characters and both receivers are
     * {@code PIC X(40)}, so the move neither pads nor truncates. It still goes through the
     * {@code PIC X} rule, so that one implementation governs every field.
     */
    public void applyScreenTitles() {
        setTitle01o(ScreenTitles.CCDA_TITLE01);
        setTitle02o(ScreenTitles.CCDA_TITLE02);
    }

    /**
     * Moves this program's own identity into the header -
     * {@code MOVE LIT-THISTRANID TO TRNNAMEO} and {@code MOVE LIT-THISPGM TO PGMNAMEO}
     * [{@code app/cbl/COCRDLIC.cbl:649-650}].
     */
    public void applyProgramIdentity() {
        setTrnnameo(LIT_THISTRANID);
        setPgmnameo(LIT_THISPGM);
    }

    /**
     * Moves the formatted date and time from a {@link DateHeader} -
     * {@code MOVE WS-CURDATE-MM-DD-YY TO CURDATEO OF CCRDLIAO} [{@code app/cbl/COCRDLIC.cbl:658}] and
     * {@code MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO OF CCRDLIAO} [L664].
     *
     * <p>Both sources are exactly eight characters - {@code mm/dd/yy} and {@code hh:mm:ss} - matching
     * the two {@code PIC X(8)} receivers, and the {@code DFHMDF} {@code INITIAL} values on the map say
     * the same thing [{@code app/bms/COCRDLI.bms:51, 74}].
     *
     * @param dateHeader the captured date and time; never {@code null}
     * @throws NullPointerException if {@code dateHeader} is {@code null}
     */
    public void applyDateHeader(DateHeader dateHeader) {
        Objects.requireNonNull(dateHeader, "A DateHeader is required to fill CURDATEO and CURTIMEO");
        setCurdateo(dateHeader.wsCurdateMmDdYy());
        setCurtimeo(dateHeader.wsCurtimeHhMmSs());
    }

    /**
     * Moves a page number into {@code PAGENOO} the way
     * {@code MOVE WS-CA-SCREEN-NUM TO PAGENOO OF CCRDLIAO} [{@code app/cbl/COCRDLIC.cbl:667}] does.
     *
     * <p>This is the one move on this screen where the sender's category matters. {@code WS-CA-SCREEN-
     * NUM} is {@code PIC 9(1)} and {@code PAGENOO} is {@code PIC X(3)}: a numeric-{@code DISPLAY}
     * sender into an alphanumeric receiver takes the <strong>alphanumeric</strong> rule, so the digit is
     * <strong>left</strong>-justified and the receiver is padded on the <strong>right</strong> with
     * spaces. Page 1 becomes {@code "1  "}, not {@code "001"} and not {@code "  1"}. Applying the
     * {@code PIC 9} rule here instead would produce {@code "001"} and be wrong on the screen and in a
     * parity diff.
     *
     * @param screenNum the page number, {@code 0..}{@value PageCursor#MAX_SINGLE_DIGIT}, as
     *                  {@code PIC 9(1)} can hold
     * @throws IllegalArgumentException if {@code screenNum} is outside the range a {@code PIC 9(1)}
     *                                  item can hold
     */
    public void setPagenooFromScreenNum(int screenNum) {
        requireUnsignedRange(screenNum, PageCursor.MAX_SINGLE_DIGIT, "WS-CA-SCREEN-NUM",
                CardListRequest.SCREEN_NUM_LENGTH);
        setPagenoo(Integer.toString(screenNum));
    }

    /**
     * Moves the current cursor's page number into {@code PAGENOO}, which is what
     * {@code 1100-SCREEN-INIT} does with the cursor it was handed.
     *
     * @see #setPagenooFromScreenNum(int)
     */
    public void applyPageNumberFromCursor() {
        setPagenooFromScreenNum(pageCursor.screenNum());
    }

    /**
     * Moves {@code CCDA-MSG-THANK-YOU} into {@code INFOMSGO}.
     *
     * <p>The message is {@value SystemMessages#MESSAGE_LENGTH} characters and {@code INFOMSGO} is
     * {@code PIC X(45)}, so the {@code PIC X} rule <strong>truncates the last five characters on the
     * right</strong> - exactly what a COBOL {@code MOVE} of a {@code PIC X(50)} item into a
     * {@code PIC X(45)} item does. The five characters lost are trailing spaces, so no text is lost,
     * but the truncation is real and is performed rather than worked around.
     */
    public void setInfomsgoThankYou() {
        setInfomsgo(SystemMessages.CCDA_MSG_THANK_YOU);
    }

    /**
     * Moves {@code CCDA-MSG-INVALID-KEY} into {@code ERRMSGO}.
     *
     * <p>The message is {@value SystemMessages#MESSAGE_LENGTH} characters and {@code ERRMSGO} is
     * {@code PIC X(78)}, so the {@code PIC X} rule pads it on the right with 28 spaces.
     */
    public void setErrmsgoInvalidKey() {
        setErrmsgo(SystemMessages.CCDA_MSG_INVALID_KEY);
    }

    /**
     * Reproduces {@code 1100-SCREEN-INIT} [{@code app/cbl/COCRDLIC.cbl:642-672}] in its source order.
     *
     * <p>Step for step: {@code MOVE LOW-VALUES TO CCRDLIAO} [L643], then the two titles [L647-648],
     * the transaction identifier [L649], the program name [L650], the date [L658], the time [L664], the
     * page number [L667], the information message [L670], and finally
     * {@code MOVE DFHBMDAR TO INFOMSGC OF CCRDLIAO} [L671] - which darkens the information line so an
     * empty message shows as nothing rather than as a blank highlighted band.
     *
     * <p>The order matters and is preserved: L643 wipes the group, so every move after it must come
     * after it.
     *
     * @param dateHeader the captured date and time; never {@code null}
     * @param infoMessage the {@code WS-INFO-MSG} text to move into {@code INFOMSGO}; pass
     *                    {@link CardScreenState#spaces(int)} for the no-message state that
     *                    {@code SET WS-NO-INFO-MESSAGE TO TRUE} [L669] produces
     * @throws NullPointerException if either argument is {@code null}
     */
    public void applyScreenInit(DateHeader dateHeader, String infoMessage) {
        Objects.requireNonNull(dateHeader, "A DateHeader is required for 1100-SCREEN-INIT");
        Objects.requireNonNull(infoMessage, "An information message is required; for the no-message "
                + "state move SPACES");
        moveLowValuesToMap();
        applyScreenTitles();
        applyProgramIdentity();
        applyDateHeader(dateHeader);
        applyPageNumberFromCursor();
        setInfomsgo(infoMessage);
        fieldAttributes(mapField(INFOMSGO_ITEM).screenFieldPrefix())
                .setColour(BmsAttributes.DFHBMDAR);
    }

    /**
     * Reproduces the {@code 1400-SETUP-MESSAGE} tail [{@code app/cbl/COCRDLIC.cbl:924-930}]: the error
     * line is written unconditionally, and when there is an information message to show it is written
     * and its colour item is set to {@link BmsAttributes#DFHNEUTR}.
     *
     * @param errorMessage the {@code WS-ERROR-MSG} text, moved into {@code ERRMSGO} [L924]; pass
     *                     spaces when there is no error
     * @param infoMessage  the {@code WS-INFO-MSG} text, moved into {@code INFOMSGO} [L928], or
     *                     {@code null} when {@code WS-NO-INFO-MESSAGE} holds and the guarded block at
     *                     L926-930 is skipped entirely
     * @throws NullPointerException if {@code errorMessage} is {@code null}
     */
    public void applyMessages(String errorMessage, String infoMessage) {
        Objects.requireNonNull(errorMessage, "An error message is required; for no error move SPACES");
        setErrmsgo(errorMessage);
        if (infoMessage != null) {
            setInfomsgo(infoMessage);
            fieldAttributes(mapField(INFOMSGO_ITEM).screenFieldPrefix())
                    .setColour(BmsAttributes.DFHNEUTR);
        }
    }

    // =================================================================================================
    // The 797-byte group image.
    //
    // The form EXEC CICS SEND MAP(LIT-THISMAP) MAPSET(LIT-THISMAPSET) FROM(CCRDLIAO)
    // [app/cbl/COCRDLIC.cbl:938-941] transmits, and the form a parity test compares byte for byte.
    // =================================================================================================

    /**
     * Renders this response as the {@value #GROUP_LENGTH}-byte {@code CCRDLIAO} image.
     *
     * <p>The area is filled with {@code x'00'} first, reproducing
     * {@code MOVE LOW-VALUES TO CCRDLIAO} [{@code app/cbl/COCRDLIC.cbl:643}], and then every payload
     * item and every attribute byte is written at its declared offset. The {@code TIOAPFX}
     * {@code FILLER X(12)} and the 45 {@code FILLER X(3)} spans are therefore {@code LOW-VALUES}, which
     * is what the program leaves them as - they are never omitted, because omitting a {@code FILLER}
     * would move every offset after it.
     *
     * <p>The attribute bytes are written with
     * {@link FixedWidthRecord#writeSpanBytes(FieldSpan, byte[])} rather than as text, so a value such as
     * {@link BmsAttributes#DFHRED} ({@code X'F2'}) survives byte-exact in either code page instead of
     * being re-encoded as whatever character {@code 0xF2} happens to name.
     *
     * @param charset the code page to encode the text items into, named explicitly by the caller
     * @return a fresh array of exactly {@value #GROUP_LENGTH} bytes
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a single-byte code page for the space
     *                                  and the digits
     */
    public byte[] toFixedWidth(Charset charset) {
        Objects.requireNonNull(charset, "A charset is required to render CCRDLIAO as bytes: a "
                + "fixed-width image is bytes in a specific code page, so the code page must be stated "
                + "explicitly and is never taken from the platform");
        FixedWidthRecord record = new FixedWidthRecord(GROUP_LENGTH, charset);
        writeInto(record);
        return record.toByteArray();
    }

    /**
     * Writes this response into an existing record area of exactly {@value #GROUP_LENGTH} bytes, using
     * that area's own code page.
     *
     * <p>The area is wiped to {@code LOW-VALUES} first, exactly as L643 does, so the result does not
     * depend on what the caller left in it.
     *
     * @param record a record area whose declared length is {@value #GROUP_LENGTH}
     * @throws NullPointerException     if {@code record} is {@code null}
     * @throws IllegalArgumentException if the area's declared length is not {@value #GROUP_LENGTH}
     */
    public void writeInto(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A record area is required to write CCRDLIAO into");
        requireGroupWidth(record.recordLength());
        FixedWidthCodec codec = new FixedWidthCodec(record.charset());
        record.fill(0, GROUP_LENGTH, LOW_VALUE_BYTE);
        for (MapField field : MAP_FIELDS) {
            codec.writePicX(record, field.dataSpan(), payload.get(field.itemName()));
            FieldAttributes quad = attributes.get(field.screenFieldPrefix());
            record.writeSpanBytes(field.colourSpan(), new byte[] {quad.colour()});
            record.writeSpanBytes(field.psSpan(), new byte[] {quad.ps()});
            record.writeSpanBytes(field.highlightSpan(), new byte[] {quad.highlight()});
            record.writeSpanBytes(field.validnSpan(), new byte[] {quad.validn()});
        }
    }

    /**
     * Rebuilds the map half of a response from a {@value #GROUP_LENGTH}-byte {@code CCRDLIAO} image.
     *
     * <p>Only what the image carries is restored: the 45 payload items and the 45 attribute quads. The
     * navigation triple, the cursor, the work area and the commarea are <strong>not</strong> in the
     * symbolic map and come back at their constructed defaults, because there is nothing in the image
     * to read them from.
     *
     * @param bytes   exactly {@value #GROUP_LENGTH} bytes
     * @param charset the code page the text items are encoded in, named explicitly by the caller
     * @return a response carrying the image's 45 items and 45 quads; never {@code null}
     * @throws NullPointerException     if {@code bytes} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code bytes.length} is not {@value #GROUP_LENGTH}
     */
    public static CardListResponse fromFixedWidth(byte[] bytes, Charset charset) {
        Objects.requireNonNull(bytes, "An image is required to rebuild CCRDLIAO");
        Objects.requireNonNull(charset, "A charset is required to decode a CCRDLIAO image: the code "
                + "page must be stated explicitly and is never taken from the platform");
        return readFrom(FixedWidthRecord.copyOf(bytes, GROUP_LENGTH, charset));
    }

    /**
     * Reads the map half of a response out of an existing record area, using that area's own code page.
     *
     * @param record a record area whose declared length is {@value #GROUP_LENGTH}
     * @return a response carrying the area's 45 items and 45 quads; never {@code null}
     * @throws NullPointerException     if {@code record} is {@code null}
     * @throws IllegalArgumentException if the area's declared length is not {@value #GROUP_LENGTH}
     */
    public static CardListResponse readFrom(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A record area is required to read CCRDLIAO from");
        requireGroupWidth(record.recordLength());
        CardListResponse response = new CardListResponse();
        for (MapField field : MAP_FIELDS) {
            response.payload.put(field.itemName(), record.readSpan(field.dataSpan()));
            FieldAttributes quad = response.attributes.get(field.screenFieldPrefix());
            quad.setColour(record.readSpanBytes(field.colourSpan())[0]);
            quad.setPs(record.readSpanBytes(field.psSpan())[0]);
            quad.setHighlight(record.readSpanBytes(field.highlightSpan())[0]);
            quad.setValidn(record.readSpanBytes(field.validnSpan())[0]);
        }
        return response;
    }

    // =================================================================================================
    // Value semantics.
    // =================================================================================================

    /**
     * Equal when every payload item, every attribute quad, the row error flags, the navigation triple,
     * the cursor, the work area and the commarea are equal.
     *
     * <p>Values are compared as stored - untrimmed and at their declared widths - because a field
     * holding {@code "AB "} is not the same screen as one holding {@code "AB"} would be if it could
     * exist, and a comparison that trimmed would hide exactly the padding defects this class exists to
     * prevent.
     *
     * @param other the object to compare against
     * @return {@code true} when {@code other} is a {@code CardListResponse} in the same state
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CardListResponse that)) {
            return false;
        }
        return payload.equals(that.payload)
                && attributes.equals(that.attributes)
                && editSelectErrorFlags.equals(that.editSelectErrorFlags)
                && nextProgram.equals(that.nextProgram)
                && nextMapset.equals(that.nextMapset)
                && nextMap.equals(that.nextMap)
                && pageCursor.equals(that.pageCursor)
                && cardScreenState.equals(that.cardScreenState)
                && navigationContext.equals(that.navigationContext);
    }

    @Override
    public int hashCode() {
        return Objects.hash(payload, attributes, editSelectErrorFlags, nextProgram, nextMapset,
                nextMap, pageCursor, cardScreenState, navigationContext);
    }

    /**
     * A diagnostic rendering of all 45 payload items, each delimited so its declared width and any
     * padding are visible, followed by the navigation triple and the cursor.
     *
     * <p><strong>The card numbers and account identifiers are redacted here, and only here.</strong>
     * This screen carries up to seven full sixteen-digit card numbers and eight account identifiers -
     * {@code CARDSIDO}, {@code ACCTSIDO} and the seven {@code CRDNUMnO} / {@code ACCTNOnO} pairs - and
     * a diagnostic is the one place those values reach somewhere nobody chose to put them: a log file, a
     * test failure message, an exception trail. Each is replaced with {@value #REDACTED} and its actual
     * length, so the rendering still answers "was the field populated, and at what width", which is what
     * a diagnostic is read for.
     *
     * <p>This is <strong>not</strong> a behaviour change to the screen. The JSON body carries all 45
     * items exactly as the symbolic map declares them, {@link #toFixedWidth(Charset)} writes the same
     * bytes it always wrote, and no accessor masks anything - {@link #getCrdnum1o()} and
     * {@link #getCardsido()} return the number in the clear, because the 3270 shows it in the clear.
     * Only this method changes, and it is documented as being for diagnostics and never a wire format.
     *
     * <p>Every other item renders verbatim: a field holding {@code LOW-VALUES} shows its actual
     * {@code U+0000} characters, and the {@code 88}-level predicates remain the way to test for that
     * state rather than reading it out of this string.
     *
     * @return the rendering; for diagnostics only, never a wire format
     */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder(512);
        text.append("CardListResponse[");
        for (Map.Entry<String, String> entry : payload.entrySet()) {
            text.append(entry.getKey()).append("='")
                    .append(SensitiveDiagnostics.render(disclosureOf(entry.getKey()), entry.getValue()))
                    .append("', ");
        }
        text.append("WS-EDIT-SELECT-ERROR-FLAGS='")
                .append(DiagnosticText.singleLine(editSelectErrorFlags))
                .append("', nextProgram='").append(DiagnosticText.singleLine(nextProgram))
                .append("', nextMapset='").append(DiagnosticText.singleLine(nextMapset))
                .append("', nextMap='").append(DiagnosticText.singleLine(nextMap))
                .append("', pageCursor=").append(pageCursor)
                .append(']');
        return text.toString();
    }

    // =================================================================================================
    // Private helpers. Each is one rule, named after the thing it enforces.
    // =================================================================================================

    /**
     * The single {@code LOW-VALUES} byte, {@code 0x00}, used to wipe a group image.
     *
     * <p>Code-page independent: {@code U+0000} encodes to {@code 0x00} under both {@code US-ASCII} and
     * {@code IBM037}, which is why {@code MOVE LOW-VALUES} means the same thing on either side.
     */
    private static final byte LOW_VALUE_BYTE = 0x00;

    /**
     * Rejects a value that is not exactly the width its {@code PICTURE} declares.
     *
     * <p>Used where a fixed-width component is supplied whole - a {@link ScreenRow} element, a
     * {@link PageCursor} item, a group image. It is deliberately strict rather than padding silently:
     * those call sites are transcribing a declared layout, and a short value there means the caller's
     * idea of the layout differs from the copybook's. The <em>data</em> path, where a value of any
     * length is moved into a field, is {@link #setField(String, String)} and applies the {@code PIC X}
     * rule instead.
     *
     * @param value     the value being checked
     * @param width     the declared width
     * @param cobolName the copybook name of the item, for the failure message
     * @throws NullPointerException     if {@code value} is {@code null}; {@code null} is not a COBOL
     *                                  state - a field is spaces, or {@code LOW-VALUES}, or it holds
     *                                  data
     * @throws IllegalArgumentException if {@code value.length()} is not {@code width}
     */
    private static void requireExactWidth(String value, int width, String cobolName) {
        Objects.requireNonNull(value, cobolName + " requires a value; null is not a COBOL state - use "
                + "CardScreenState.spaces(int) or CardScreenState.lowValues(int) for a blank item");
        if (value.length() != width) {
            throw new IllegalArgumentException(cobolName + " is declared " + width + " character(s) "
                    + "wide but " + value.length() + " were supplied; a fixed-width item is addressed "
                    + "by absolute offset, so a short or long value would move every item after it");
        }
    }

    /**
     * Rejects a value an unsigned {@code PIC 9} item of the given digit count cannot hold.
     *
     * <p>{@code PIC 9(n)} has no sign position, so a negative value has no representation in it at all
     * and is rejected rather than stored as its magnitude.
     *
     * @param value     the value being checked
     * @param maximum   the largest value the item can hold - {@code n} nines
     * @param cobolName the copybook name of the item, for the failure message
     * @param digits    {@code n}, for the failure message
     * @throws IllegalArgumentException if {@code value} is negative or above {@code maximum}
     */
    private static void requireUnsignedRange(long value, long maximum, String cobolName, int digits) {
        if (value < 0) {
            throw new IllegalArgumentException(cobolName + " is PIC 9(" + digits + "), which has no "
                    + "sign position, so it cannot hold " + value + "; a signed value belongs in a "
                    + "PIC S9 item");
        }
        if (value > maximum) {
            throw new IllegalArgumentException(cobolName + " is PIC 9(" + digits + "), so it cannot "
                    + "hold " + value + "; the largest value it can carry is " + maximum);
        }
    }

    /**
     * Rejects a record area that is not exactly the width {@code 01 CCRDLIAO} declares.
     *
     * @param recordLength the area's declared length
     * @throws IllegalArgumentException if {@code recordLength} is not {@value #GROUP_LENGTH}
     */
    private static void requireGroupWidth(int recordLength) {
        if (recordLength != GROUP_LENGTH) {
            throw new IllegalArgumentException("A CCRDLIAO area is " + GROUP_LENGTH + " byte(s) - "
                    + TIOAPFX_LENGTH + " of TIOAPFX prefix, " + PAYLOAD_FIELD_COUNT + " x "
                    + FIELD_PREFIX_LENGTH + " of per-field prefix and " + PAYLOAD_LENGTH + " of data - "
                    + "but " + recordLength + " byte(s) were supplied");
        }
    }

    /**
     * How much of one symbolic-map field a diagnostic rendering may disclose.
     *
     * <p>{@code COCRDLI} is the card list, so it is the densest concentration of payment data on any
     * screen in the system: seven card numbers and seven account numbers per page, plus the filter
     * fields. All fourteen row fields and both filters are masked; the status columns and the screen
     * furniture render as stored, because a page of masked numbers with visible statuses is still exactly
     * what a pagination or filter parity failure is diagnosed from.
     *
     * <p>The row fields are matched by their documented BMS stems rather than listed one by one. That is
     * not a loose heuristic: {@value #FIELD_COUNT} is fixed by the mapset, the row count is fixed at
     * seven by {@code app/cbl/COCRDLIC.cbl}'s page size, and the stems {@code CRDNUM} and {@code ACCTNO}
     * are the mapset's own names - a new field on this screen would need a new stem, which would not
     * silently match.
     *
     * <p>Package-private rather than private so that the safe default - an unnamed field is withheld
     * rather than published - is asserted directly by test. A field name reaching here is never
     * {@code null} in practice, because the map is built from this class's own constants, and an
     * unprovable guard on a disclosure decision is worth less than a proven one.
     *
     * @param fieldName the symbolic-map output item name, as {@code app/cpy-bms/COCRDLI.CPY} spells it
     * @return its classification, never {@code null}
     */
    static SensitiveDiagnostics.Disclosure disclosureOf(String fieldName) {
        if (fieldName == null) {
            return SensitiveDiagnostics.Disclosure.REDACTED_VALUE;
        }
        if (fieldName.startsWith("CRDNUM") || fieldName.startsWith("CARDSID")) {
            return SensitiveDiagnostics.Disclosure.PAN;
        }
        if (fieldName.startsWith("ACCTNO") || fieldName.startsWith("ACCTSID")) {
            return SensitiveDiagnostics.Disclosure.IDENTIFIER;
        }
        return SensitiveDiagnostics.Disclosure.PLAIN;
    }

}
