package com.vsergeychik.carddemo.card.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import java.nio.charset.Charset;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * The card screen work area: a field-for-field translation of {@code app/cpy/CVCRD01Y.cpy}, the
 * copybook that carries the conversational state of the three CICS card transactions.
 *
 * <h2>The source, transcribed</h2>
 *
 * <p>The copybook declares one group, nested three levels deep -
 * {@code 01 CC-WORK-AREAS} (line 1) &rarr; {@code 05 CC-WORK-AREA} (line 2) &rarr; nine
 * {@code 10}-level elementary items. Those nine items are the whole of the storage, and they sum to
 * exactly <strong>213 bytes</strong>:
 *
 * <pre>
 *   offset  bytes  PICTURE     item                  copybook line
 *   ------  -----  ----------  --------------------  -------------
 *        0      5  X(5)        CCARD-AID                    L3
 *        5      8  X(8)        CCARD-NEXT-PROG              L21
 *       13      7  X(7)        CCARD-NEXT-MAPSET            L23
 *       20      7  X(7)        CCARD-NEXT-MAP               L24
 *       27     75  X(75)       CCARD-ERROR-MSG              L28
 *      102     75  X(75)       CCARD-RETURN-MSG             L29
 *      177     11  X(11)       CC-ACCT-ID                L34-35
 *      188     16  X(16)       CC-CARD-NUM               L37-38
 *      204      9  X(09)       CC-CUST-ID                L40-41
 *   ------  -----
 *      213    213  = 5 + 8 + 7 + 7 + 75 + 75 + 11 + 16 + 9
 * </pre>
 *
 * <p>Three further items - {@code CC-ACCT-ID-N} (L36), {@code CC-CARD-NUM-N} (L39) and
 * {@code CC-CUST-ID-N} (L42) - are {@code REDEFINES} overlays. An overlay is a second view of
 * storage that already exists, so each contributes <strong>zero</strong> bytes and the total remains
 * 213. That arithmetic is not asserted in prose here: {@link #LAYOUT} declares all twelve spans and
 * {@link RecordLayout} refuses to be constructed unless the storage spans sum to
 * {@link #RECORD_LENGTH}, so a transcription slip fails at class-initialisation time rather than
 * silently shifting every byte after it.
 *
 * <h2>Five consuming programs</h2>
 *
 * <p>{@code CVCRD01Y} is copied by five of the seventeen CICS online programs:
 * {@code app/cbl/COACTUPC.cbl}, {@code app/cbl/COACTVWC.cbl}, {@code app/cbl/COCRDLIC.cbl} line 221,
 * {@code app/cbl/COCRDSLC.cbl} line 194 and {@code app/cbl/COCRDUPC.cbl} line 268. The three card
 * programs are the ones this type serves directly; each begins its processing with
 * {@code INITIALIZE CC-WORK-AREA} - {@code COCRDLIC.cbl:300}, {@code COCRDSLC.cbl:254},
 * {@code COCRDUPC.cbl:374} - which is reproduced by {@link #initializeWorkArea()}.
 *
 * <h2>Three corrections this file encodes</h2>
 *
 * <p>The migration plan's body understates the copybook in three places. Each was re-read directly
 * from the source and each correction is independently corroborated by a sibling class, so the
 * copybook wins (practice <strong>B4</strong>: record the conflict, never resolve it silently).
 *
 * <ol>
 *   <li><strong>{@code CCARD-AID} carries 16 condition names, not 15.</strong> Lines 4-19 declare
 *       {@code ENTER}, {@code CLEAR}, {@code PA1}, {@code PA2} and {@code PFK01} through
 *       {@code PFK12}. {@link PfKeyResolver.AidKey} independently declares the same sixteen.</li>
 *   <li><strong>There are three {@code REDEFINES} pairs, not one.</strong> The plan names only
 *       {@code CC-ACCT-ID}; {@code CC-CARD-NUM} and {@code CC-CUST-ID} carry the same
 *       construction.</li>
 *   <li><strong>{@code CCARD-NEXT-MAPSET} and {@code CCARD-NEXT-MAP} are {@code X(7)}, not
 *       {@code X(8)}.</strong> Only {@code CCARD-NEXT-PROG} is eight. A COBOL program name is eight
 *       characters, but a BMS map name is seven, because the symbolic map group is formed from the
 *       map name plus an {@code I} or {@code O} suffix - {@code CCRDLIA} yields {@code CCRDLIAI} and
 *       {@code CCRDLIAO}, {@code CCRDSLA} yields {@code CCRDSLAI} and {@code CCRDSLAO}, and
 *       {@code CCRDUPA} yields {@code CCRDUPAI} and {@code CCRDUPAO}. {@code common/NavigationContext}
 *       declares {@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET} as {@code X(7)} for the same
 *       reason.</li>
 * </ol>
 *
 * <h2>Four items deliberately absent</h2>
 *
 * <p>Four groups in the copybook are <strong>commented out</strong> at source, and practice
 * <strong>B5</strong> preserves dead code as dead: reviving one would add behaviour nothing asked
 * for. They therefore have no representation here, and are listed so their absence reads as a
 * decision rather than an oversight:
 *
 * <ul>
 *   <li>{@code CCARD-LAST-PROG PIC X(8)} - line 20</li>
 *   <li>{@code CCARD-RETURN-TO-PROG PIC X(8)} - line 22</li>
 *   <li>{@code CCARD-RETURN-FLAG PIC X(1)} with {@code 88 CCARD-RETURN-FLAG-OFF VALUE LOW-VALUES}
 *       and {@code 88 CCARD-RETURN-FLAG-ON VALUE '1'} - lines 25 to 27</li>
 *   <li>{@code CCARD-FUNCTION PIC X(1)} with {@code 88 CCARD-NO-VALUE VALUE LOW-VALUES} and
 *       {@code 88 CCARD-GET-DATA VALUE '1'} - lines 31 to 33</li>
 * </ul>
 *
 * <p>{@code COCRDLIC} does declare a <em>live</em> {@code WS-RETURN-FLAG PIC X(1)} with its own
 * {@code -OFF} and {@code -ON} conditions, but that field belongs to that program's own commarea and
 * so belongs on the card-list request and response payloads - not here, and it is emphatically not a
 * reason to revive {@code CCARD-RETURN-FLAG}.
 *
 * <h2>Statelessness: this is a payload, not a session</h2>
 *
 * <p>CICS is pseudo-conversational, so this work area is the carrier that replaces server-side
 * conversation state. An instance is a <strong>per-request value</strong> that travels inside the
 * request and response bodies of the three card endpoints. It holds no servlet session, no
 * session-scoped attribute, no cache, no thread-bound storage and no static mutable state, and the
 * class declares nothing beyond the nine fields below - so nothing here can outlive the request that
 * created it. Because the property is structural rather than a matter of trust, it can be confirmed
 * mechanically: a search of this file for any such construct is expected to find none.
 *
 * <p>The class is deliberately mutable - a COBOL work area is, and the controllers assign
 * {@code CCARD-NEXT-PROG}, {@code CCARD-NEXT-MAPSET} and {@code CCARD-NEXT-MAP} part-way through
 * processing, for example at {@code app/cbl/COCRDLIC.cbl:526-529}. An instance is consequently
 * <strong>not</strong> thread safe and must stay confined to the request that owns it, exactly as a
 * CICS working-storage area is confined to its task.
 *
 * <h2>What travels on the wire</h2>
 *
 * <p>The JSON projection is the <strong>nine storage fields and nothing else</strong>. Every
 * condition-name predicate and every {@code REDEFINES} overlay accessor is a derived view of a field
 * that is already on the wire, so each carries {@link JsonIgnore}: publishing them would add payload
 * members that trace to no copybook storage, and - for the overlays - would let one payload carry two
 * contradictory values for the same eleven, sixteen or nine bytes. Nothing is masked or abbreviated
 * by that choice. This work area holds a full sixteen-digit card number and an account identifier in
 * the clear because the COBOL does, and altering that in either direction would be a behaviour
 * change (practice <strong>B6</strong>).
 *
 * <h2>Widths are absolute, contents are opaque</h2>
 *
 * <p>Every field is held at exactly its declared {@code PICTURE} width at all times, and every setter
 * applies the COBOL {@code MOVE} rule to guarantee it. Beyond that width this class validates
 * nothing. {@code CCARD-NEXT-PROG}, {@code CCARD-NEXT-MAPSET} and {@code CCARD-NEXT-MAP} in
 * particular are opaque tokens: they are never checked against a list of known names, never
 * case-folded and never trimmed. That permissiveness is required, not lazy.
 * {@code app/cbl/COCRDSLC.cbl:178} sets its card-list map literal to {@code 'CCRDSLA'} when the card
 * list's map is really {@code 'CCRDLIA'}; that defect is preserved behaviour (practices
 * <strong>B4</strong> and <strong>B5</strong>), and a class that "corrected" or rejected map names
 * would destroy it.
 *
 * @see PfKeyResolver
 * @see FixedWidthCodec
 * @see FixedWidthRecord
 */
public final class CardScreenState {

    // =================================================================================================
    // Record geometry - the declared widths and absolute offsets of app/cpy/CVCRD01Y.cpy.
    //
    // Each offset is expressed as the previous offset plus the previous width rather than as a bare
    // number, so the chain is self-consistent by construction and a reviewer can follow it down the
    // copybook without doing arithmetic. LAYOUT then proves the chain closes at exactly 213.
    // =================================================================================================

    /**
     * The total width of {@code CC-WORK-AREA} in bytes:
     * {@code 5 + 8 + 7 + 7 + 75 + 75 + 11 + 16 + 9}. The three {@code REDEFINES} overlays add
     * nothing, because each views storage a preceding item already accounts for.
     */
    public static final int RECORD_LENGTH = 213;

    /** Width of {@code CCARD-AID PIC X(5)}, {@code app/cpy/CVCRD01Y.cpy} line 3. */
    public static final int CCARD_AID_LENGTH = 5;

    /** Width of {@code CCARD-NEXT-PROG PIC X(8)}, line 21. A COBOL program name is 8 characters. */
    public static final int CCARD_NEXT_PROG_LENGTH = 8;

    /** Width of {@code CCARD-NEXT-MAPSET PIC X(7)}, line 23. Seven, not eight. */
    public static final int CCARD_NEXT_MAPSET_LENGTH = 7;

    /** Width of {@code CCARD-NEXT-MAP PIC X(7)}, line 24. Seven, not eight. */
    public static final int CCARD_NEXT_MAP_LENGTH = 7;

    /** Width of {@code CCARD-ERROR-MSG PIC X(75)}, line 28. */
    public static final int CCARD_ERROR_MSG_LENGTH = 75;

    /** Width of {@code CCARD-RETURN-MSG PIC X(75)}, line 29. */
    public static final int CCARD_RETURN_MSG_LENGTH = 75;

    /** Width of {@code CC-ACCT-ID PIC X(11)} and of its overlay {@code CC-ACCT-ID-N PIC 9(11)}. */
    public static final int CC_ACCT_ID_LENGTH = 11;

    /** Width of {@code CC-CARD-NUM PIC X(16)} and of its overlay {@code CC-CARD-NUM-N PIC 9(16)}. */
    public static final int CC_CARD_NUM_LENGTH = 16;

    /** Width of {@code CC-CUST-ID PIC X(09)} and of its overlay {@code CC-CUST-ID-N PIC 9(9)}. */
    public static final int CC_CUST_ID_LENGTH = 9;

    /** Absolute 0-based offset of {@code CCARD-AID}; the group starts here. */
    public static final int CCARD_AID_OFFSET = 0;

    /** Absolute 0-based offset of {@code CCARD-NEXT-PROG}. */
    public static final int CCARD_NEXT_PROG_OFFSET = CCARD_AID_OFFSET + CCARD_AID_LENGTH;

    /** Absolute 0-based offset of {@code CCARD-NEXT-MAPSET}. */
    public static final int CCARD_NEXT_MAPSET_OFFSET =
            CCARD_NEXT_PROG_OFFSET + CCARD_NEXT_PROG_LENGTH;

    /** Absolute 0-based offset of {@code CCARD-NEXT-MAP}. */
    public static final int CCARD_NEXT_MAP_OFFSET =
            CCARD_NEXT_MAPSET_OFFSET + CCARD_NEXT_MAPSET_LENGTH;

    /** Absolute 0-based offset of {@code CCARD-ERROR-MSG}. */
    public static final int CCARD_ERROR_MSG_OFFSET =
            CCARD_NEXT_MAP_OFFSET + CCARD_NEXT_MAP_LENGTH;

    /** Absolute 0-based offset of {@code CCARD-RETURN-MSG}. */
    public static final int CCARD_RETURN_MSG_OFFSET =
            CCARD_ERROR_MSG_OFFSET + CCARD_ERROR_MSG_LENGTH;

    /** Absolute 0-based offset of {@code CC-ACCT-ID}, shared with {@code CC-ACCT-ID-N}. */
    public static final int CC_ACCT_ID_OFFSET =
            CCARD_RETURN_MSG_OFFSET + CCARD_RETURN_MSG_LENGTH;

    /** Absolute 0-based offset of {@code CC-CARD-NUM}, shared with {@code CC-CARD-NUM-N}. */
    public static final int CC_CARD_NUM_OFFSET = CC_ACCT_ID_OFFSET + CC_ACCT_ID_LENGTH;

    /** Absolute 0-based offset of {@code CC-CUST-ID}, shared with {@code CC-CUST-ID-N}. */
    public static final int CC_CUST_ID_OFFSET = CC_CARD_NUM_OFFSET + CC_CARD_NUM_LENGTH;

    // =================================================================================================
    // The twelve span descriptors - nine storage items and three REDEFINES overlays - and the layout
    // that binds them. Exposed so a parity test can assert an offset, and so the six sibling card DTOs
    // and the card repositories address these bytes through one shared, auditable declaration rather
    // than by repeating offsets.
    //
    // FieldSpan and RecordLayout are immutable records and RecordLayout copies its span list
    // defensively, so every constant below is deeply immutable (practice B9).
    // =================================================================================================

    /** {@code 10 CCARD-AID PIC X(5)}, {@code app/cpy/CVCRD01Y.cpy} line 3. */
    public static final FieldSpan CCARD_AID_SPAN =
            FieldSpan.alphanumeric("CCARD-AID", CCARD_AID_OFFSET, CCARD_AID_LENGTH);

    /** {@code 10 CCARD-NEXT-PROG PIC X(8)}, line 21. */
    public static final FieldSpan CCARD_NEXT_PROG_SPAN = FieldSpan.alphanumeric(
            "CCARD-NEXT-PROG", CCARD_NEXT_PROG_OFFSET, CCARD_NEXT_PROG_LENGTH);

    /** {@code 10 CCARD-NEXT-MAPSET PIC X(7)}, line 23. */
    public static final FieldSpan CCARD_NEXT_MAPSET_SPAN = FieldSpan.alphanumeric(
            "CCARD-NEXT-MAPSET", CCARD_NEXT_MAPSET_OFFSET, CCARD_NEXT_MAPSET_LENGTH);

    /** {@code 10 CCARD-NEXT-MAP PIC X(7)}, line 24. */
    public static final FieldSpan CCARD_NEXT_MAP_SPAN = FieldSpan.alphanumeric(
            "CCARD-NEXT-MAP", CCARD_NEXT_MAP_OFFSET, CCARD_NEXT_MAP_LENGTH);

    /** {@code 10 CCARD-ERROR-MSG PIC X(75)}, line 28. */
    public static final FieldSpan CCARD_ERROR_MSG_SPAN = FieldSpan.alphanumeric(
            "CCARD-ERROR-MSG", CCARD_ERROR_MSG_OFFSET, CCARD_ERROR_MSG_LENGTH);

    /** {@code 10 CCARD-RETURN-MSG PIC X(75)}, line 29. */
    public static final FieldSpan CCARD_RETURN_MSG_SPAN = FieldSpan.alphanumeric(
            "CCARD-RETURN-MSG", CCARD_RETURN_MSG_OFFSET, CCARD_RETURN_MSG_LENGTH);

    /**
     * {@code 10 CC-ACCT-ID PIC X(11) VALUE SPACES}, lines 34 to 35. The {@code VALUE} clause is
     * carried on the descriptor, so initialising a record from {@link #LAYOUT} reproduces the
     * copybook's declared initial state instead of a guess at it.
     */
    public static final FieldSpan CC_ACCT_ID_SPAN = FieldSpan
            .alphanumeric("CC-ACCT-ID", CC_ACCT_ID_OFFSET, CC_ACCT_ID_LENGTH)
            .withInitialValue(spaces(CC_ACCT_ID_LENGTH));

    /**
     * {@code 10 CC-ACCT-ID-N REDEFINES CC-ACCT-ID PIC 9(11)}, line 36. Derived from
     * {@link #CC_ACCT_ID_SPAN} rather than declared independently, so the two views cannot drift
     * apart in offset or width - they are one eleven-byte span by construction.
     */
    public static final FieldSpan CC_ACCT_ID_N_SPAN =
            CC_ACCT_ID_SPAN.redefinedAs("CC-ACCT-ID-N", PictureKind.UNSIGNED_NUMERIC);

    /** {@code 10 CC-CARD-NUM PIC X(16) VALUE SPACES}, lines 37 to 38. */
    public static final FieldSpan CC_CARD_NUM_SPAN = FieldSpan
            .alphanumeric("CC-CARD-NUM", CC_CARD_NUM_OFFSET, CC_CARD_NUM_LENGTH)
            .withInitialValue(spaces(CC_CARD_NUM_LENGTH));

    /** {@code 10 CC-CARD-NUM-N REDEFINES CC-CARD-NUM PIC 9(16)}, line 39. */
    public static final FieldSpan CC_CARD_NUM_N_SPAN =
            CC_CARD_NUM_SPAN.redefinedAs("CC-CARD-NUM-N", PictureKind.UNSIGNED_NUMERIC);

    /** {@code 10 CC-CUST-ID PIC X(09) VALUE SPACES}, lines 40 to 41. */
    public static final FieldSpan CC_CUST_ID_SPAN = FieldSpan
            .alphanumeric("CC-CUST-ID", CC_CUST_ID_OFFSET, CC_CUST_ID_LENGTH)
            .withInitialValue(spaces(CC_CUST_ID_LENGTH));

    /** {@code 10 CC-CUST-ID-N REDEFINES CC-CUST-ID PIC 9(9)}, line 42. */
    public static final FieldSpan CC_CUST_ID_N_SPAN =
            CC_CUST_ID_SPAN.redefinedAs("CC-CUST-ID-N", PictureKind.UNSIGNED_NUMERIC);

    /**
     * The complete layout of {@code CC-WORK-AREA}: twelve spans in copybook declaration order, each
     * overlay immediately after the item it redefines.
     *
     * <p>Constructing this constant <strong>is</strong> the total-width self-check.
     * {@link RecordLayout} runs its geometry verification in its own constructor and rejects a layout
     * whose storage spans do not sum to the declared record length, that overlaps, that leaves a gap,
     * that declares a name twice, or whose overlay reaches beyond the storage declared ahead of it. So
     * if any offset or width above were mistranscribed, this class would fail to initialise and say
     * which descriptor was wrong - which is a far cheaper failure than a record that is quietly the
     * wrong width.
     */
    public static final RecordLayout LAYOUT = RecordLayout.of(RECORD_LENGTH,
            CCARD_AID_SPAN,
            CCARD_NEXT_PROG_SPAN,
            CCARD_NEXT_MAPSET_SPAN,
            CCARD_NEXT_MAP_SPAN,
            CCARD_ERROR_MSG_SPAN,
            CCARD_RETURN_MSG_SPAN,
            CC_ACCT_ID_SPAN,
            CC_ACCT_ID_N_SPAN,
            CC_CARD_NUM_SPAN,
            CC_CARD_NUM_N_SPAN,
            CC_CUST_ID_SPAN,
            CC_CUST_ID_N_SPAN);

    // =================================================================================================
    // The PICTURE rule engine.
    // =================================================================================================

    /**
     * The single implementation of the {@code PIC X} and {@code PIC 9} width and decode rules used by
     * this class's setters and overlay accessors.
     *
     * <p>Every operation taken from it here - {@link FixedWidthCodec#movePicX(String, int)},
     * {@link FixedWidthCodec#movePic9(long, int)} and {@link FixedWidthCodec#decodePic9(String)} - is
     * a <em>character-level</em> operation that converts nothing to bytes, so the code page this
     * instance was built for takes no part in the result. It is named
     * {@link StandardCharsets#US_ASCII} explicitly, never derived from the platform (practice
     * <strong>B8</strong>), and it is the code page of the authoritative fixtures under
     * {@code app/data/ASCII}.
     *
     * <p>This instance is <strong>never</strong> used to encode or decode bytes. Every byte boundary -
     * {@link #toFixedWidth(Charset)}, {@link #writeInto(FixedWidthRecord)},
     * {@link #fromFixedWidth(byte[], Charset)} and {@link #readFrom(FixedWidthRecord)} - takes the
     * code page from its caller and builds a codec for it, because a fixed-width mainframe image is
     * bytes in a specific code page and this type is bound to neither.
     *
     * <p>{@code FixedWidthCodec} is immutable and holds only its {@link Charset}, so a single shared
     * instance is thread safe and is a constant rather than static mutable state (practice
     * <strong>B9</strong>). Holding it here, rather than reimplementing the pad, truncate and decode
     * rules, keeps one reviewable implementation of them in the module (practice
     * <strong>B11</strong>).
     */
    private static final FixedWidthCodec PICTURE_RULES =
            new FixedWidthCodec(StandardCharsets.US_ASCII);

    /**
     * What {@link #toString()} prints in place of the three identifier fields, and what the storage
     * guard prints in place of a rejected value.
     *
     * <p>The same literal {@code common.NavigationContext} and the sign-on payload use, so one
     * convention covers every redacted diagnostic in the module. Declared here rather than imported so
     * that this work area - which models a copybook, not a communication area - keeps its existing set
     * of dependencies.
     */
    public static final String REDACTED = "[REDACTED]";

    // =================================================================================================
    // The sixteen CCARD-AID condition names, app/cpy/CVCRD01Y.cpy lines 4 to 19.
    //
    // Each constant is taken from the matching PfKeyResolver.AidKey rather than retyped, because
    // PfKeyResolver is the class that produces these tokens from a raw EIBAID and the two must agree
    // byte for byte or every condition test below silently stops matching. Deriving them makes that
    // drift structurally impossible. The copybook literal is quoted in each Javadoc line and is
    // asserted character by character in this class's unit test, so the copybook remains the authority
    // on the value and a change made in either class is caught.
    //
    // There is no PA3 token. The copybook declares no CCARD-AID-PA3 condition and app/cpy/CSSTRPFY.cpy
    // has no DFHPA3 branch, so adding one for symmetry would invent a seventeenth condition
    // (practice B5).
    // =================================================================================================

    /** {@code 88 CCARD-AID-ENTER VALUE 'ENTER'}, line 4. */
    public static final String CCARD_AID_ENTER = AidKey.ENTER.token();

    /** {@code 88 CCARD-AID-CLEAR VALUE 'CLEAR'}, line 5. */
    public static final String CCARD_AID_CLEAR = AidKey.CLEAR.token();

    /**
     * {@code 88 CCARD-AID-PA1 VALUE 'PA1  '}, line 6 - three characters and
     * <strong>two trailing spaces</strong>, filling the five-byte field. The spaces are part of the
     * value: a three-character {@code "PA1"} would never match.
     */
    public static final String CCARD_AID_PA1 = AidKey.PA1.token();

    /**
     * {@code 88 CCARD-AID-PA2 VALUE 'PA2  '}, line 7 - again with two trailing spaces.
     */
    public static final String CCARD_AID_PA2 = AidKey.PA2.token();

    /** {@code 88 CCARD-AID-PFK01 VALUE 'PFK01'}, line 8. */
    public static final String CCARD_AID_PFK01 = AidKey.PFK01.token();

    /** {@code 88 CCARD-AID-PFK02 VALUE 'PFK02'}, line 9. */
    public static final String CCARD_AID_PFK02 = AidKey.PFK02.token();

    /** {@code 88 CCARD-AID-PFK03 VALUE 'PFK03'}, line 10. */
    public static final String CCARD_AID_PFK03 = AidKey.PFK03.token();

    /** {@code 88 CCARD-AID-PFK04 VALUE 'PFK04'}, line 11. */
    public static final String CCARD_AID_PFK04 = AidKey.PFK04.token();

    /** {@code 88 CCARD-AID-PFK05 VALUE 'PFK05'}, line 12. */
    public static final String CCARD_AID_PFK05 = AidKey.PFK05.token();

    /** {@code 88 CCARD-AID-PFK06 VALUE 'PFK06'}, line 13. */
    public static final String CCARD_AID_PFK06 = AidKey.PFK06.token();

    /** {@code 88 CCARD-AID-PFK07 VALUE 'PFK07'}, line 14. */
    public static final String CCARD_AID_PFK07 = AidKey.PFK07.token();

    /** {@code 88 CCARD-AID-PFK08 VALUE 'PFK08'}, line 15. */
    public static final String CCARD_AID_PFK08 = AidKey.PFK08.token();

    /** {@code 88 CCARD-AID-PFK09 VALUE 'PFK09'}, line 16. */
    public static final String CCARD_AID_PFK09 = AidKey.PFK09.token();

    /** {@code 88 CCARD-AID-PFK10 VALUE 'PFK10'}, line 17. */
    public static final String CCARD_AID_PFK10 = AidKey.PFK10.token();

    /** {@code 88 CCARD-AID-PFK11 VALUE 'PFK11'}, line 18. */
    public static final String CCARD_AID_PFK11 = AidKey.PFK11.token();

    /** {@code 88 CCARD-AID-PFK12 VALUE 'PFK12'}, line 19. */
    public static final String CCARD_AID_PFK12 = AidKey.PFK12.token();

    // =================================================================================================
    // Storage. One field per storage span, so a REDEFINES pair is one shared region by construction:
    // the alphanumeric and numeric accessors of a pair read and write the same field, which is what
    // makes a write through either view visible through the other.
    //
    // Every field is held at exactly its declared PICTURE width at all times. That invariant is
    // established by the constructors and maintained by every setter, so no accessor has to defend
    // against a short or over-long value and the 213-byte image can be assembled without inspection.
    // =================================================================================================

    /** {@code CCARD-AID PIC X(5)}, L3 - the AID token; see the sixteen conditions above. */
    private String ccardAid;

    /** {@code CCARD-NEXT-PROG PIC X(8)}, L21 - the program the client calls next; opaque. */
    private String ccardNextProg;

    /** {@code CCARD-NEXT-MAPSET PIC X(7)}, L23 - the next screen's mapset; opaque, seven wide. */
    private String ccardNextMapset;

    /** {@code CCARD-NEXT-MAP PIC X(7)}, L24 - the next screen's map; opaque, seven wide. */
    private String ccardNextMap;

    /** {@code CCARD-ERROR-MSG PIC X(75)}, L28 - the error line; no {@code -OFF} condition exists. */
    private String ccardErrorMsg;

    /** {@code CCARD-RETURN-MSG PIC X(75)}, L29 - the return line; {@code 88 ...-OFF} is at L30. */
    private String ccardReturnMsg;

    /** {@code CC-ACCT-ID PIC X(11) VALUE SPACES}, L34-35; redefined by {@code CC-ACCT-ID-N PIC 9(11)}, L36. */
    private String ccAcctId;

    /** {@code CC-CARD-NUM PIC X(16) VALUE SPACES}, L37-38; redefined by {@code CC-CARD-NUM-N PIC 9(16)}, L39. */
    private String ccCardNum;

    /** {@code CC-CUST-ID PIC X(09) VALUE SPACES}, L40-41; redefined by {@code CC-CUST-ID-N PIC 9(9)}, L42. */
    private String ccCustId;

    // =================================================================================================
    // Construction. No Spring context, no builder and no framework is involved, so a unit or parity
    // test constructs an instance directly (practice B10).
    // =================================================================================================

    /**
     * Creates a work area in its declared initial state: all nine fields space-filled to their
     * declared widths.
     *
     * <p>The copybook states this explicitly for the three identifier fields, which declare
     * {@code VALUE SPACES} at lines 34-35, 37-38 and 40-41. The other six declare no {@code VALUE},
     * and they are space-filled here for two reasons that agree. First, it is the convention
     * {@link RecordLayout} and {@link FixedWidthRecord#initialise(RecordLayout)} apply throughout this
     * module: a span with no declared literal receives the pad byte for its kind, and the pad byte of
     * an alphanumeric span is the space. Second, and decisively, all three consuming programs execute
     * {@code INITIALIZE CC-WORK-AREA} before they read anything -
     * {@code app/cbl/COCRDLIC.cbl:300}, {@code app/cbl/COCRDSLC.cbl:254},
     * {@code app/cbl/COCRDUPC.cbl:374} - and a COBOL {@code INITIALIZE} sets every alphanumeric item
     * to spaces. A fresh instance therefore holds exactly what those programs hold at the top of their
     * processing.
     *
     * <p>Space-filled is <strong>not</strong> the same as {@code LOW-VALUES} and neither is
     * {@code null}; see {@link #isCcardReturnMsgOff()}.
     */
    public CardScreenState() {
        initializeWorkArea();
    }

    /**
     * Creates a work area with every field supplied, each stored verbatim through the same guard the
     * setters use: a value wider than its {@code PICTURE} clause is refused, and a shorter one is kept
     * as it is rather than padded. {@link #asWorkArea()} is the step that renders them at their
     * declared widths.
     *
     * @param ccardAid        {@code CCARD-AID}, {@code PIC X(5)}
     * @param ccardNextProg   {@code CCARD-NEXT-PROG}, {@code PIC X(8)}
     * @param ccardNextMapset {@code CCARD-NEXT-MAPSET}, {@code PIC X(7)}
     * @param ccardNextMap    {@code CCARD-NEXT-MAP}, {@code PIC X(7)}
     * @param ccardErrorMsg   {@code CCARD-ERROR-MSG}, {@code PIC X(75)}
     * @param ccardReturnMsg  {@code CCARD-RETURN-MSG}, {@code PIC X(75)}
     * @param ccAcctId        {@code CC-ACCT-ID}, {@code PIC X(11)}
     * @param ccCardNum       {@code CC-CARD-NUM}, {@code PIC X(16)}
     * @param ccCustId        {@code CC-CUST-ID}, {@code PIC X(09)}
     * @throws NullPointerException     if any argument is {@code null}; COBOL has no absent state, so
     *                                  the caller must say whether it means spaces or
     *                                  {@code LOW-VALUES}
     * @throws IllegalArgumentException if any argument is wider than its declared width
     */
    public CardScreenState(String ccardAid,
                          String ccardNextProg,
                          String ccardNextMapset,
                          String ccardNextMap,
                          String ccardErrorMsg,
                          String ccardReturnMsg,
                          String ccAcctId,
                          String ccCardNum,
                          String ccCustId) {
        setCcardAid(ccardAid);
        setCcardNextProg(ccardNextProg);
        setCcardNextMapset(ccardNextMapset);
        setCcardNextMap(ccardNextMap);
        setCcardErrorMsg(ccardErrorMsg);
        setCcardReturnMsg(ccardReturnMsg);
        setCcAcctId(ccAcctId);
        setCcCardNum(ccCardNum);
        setCcCustId(ccCustId);
    }

    /**
     * Copies an existing work area field for field.
     *
     * <p>Provided because a controller echoes the state it received into the state it returns, and a
     * copy keeps the request payload's instance from being mutated while the response is assembled.
     * Every field is a {@link String}, so a field-for-field copy is a complete and independent one.
     *
     * @param other the work area to copy
     * @throws NullPointerException if {@code other} is {@code null}
     */
    public CardScreenState(CardScreenState other) {
        Objects.requireNonNull(other, "A source work area is required to copy one");
        this.ccardAid = other.ccardAid;
        this.ccardNextProg = other.ccardNextProg;
        this.ccardNextMapset = other.ccardNextMapset;
        this.ccardNextMap = other.ccardNextMap;
        this.ccardErrorMsg = other.ccardErrorMsg;
        this.ccardReturnMsg = other.ccardReturnMsg;
        this.ccAcctId = other.ccAcctId;
        this.ccCardNum = other.ccCardNum;
        this.ccCustId = other.ccCustId;
    }

    /**
     * This work area with the {@code PIC X} move rule applied to every field: the explicit, named step
     * at which a value that arrived shorter than its screen field becomes the fixed-width item
     * {@code CC-WORK-AREA} actually holds.
     *
     * <p><strong>Why this exists.</strong> Binding and moving are two different operations, and this
     * class keeps them apart. A public setter is a wire boundary: it stores exactly what arrived, so an
     * empty field stays distinguishable from a field of spaces and an over-wide value is refused rather
     * than quietly shortened. A COBOL {@code MOVE} is a program step: it pads on the right to the
     * receiving field's width. Collapsing the two - which is what applying the move inside the setter
     * did - means the wire value can never be inspected as it was sent, and a truncation happens where
     * nobody asked for one.
     *
     * <p>There are therefore exactly two places the move is applied, and both are named: this method,
     * and {@link #toFixedWidth(Charset)} together with {@link #writeInto(FixedWidthRecord)}, which
     * write through {@link FixedWidthCodec#writePicX}. Every COBOL-semantic predicate and numeric
     * overlay on this class reads the moved image too, so {@code IF CC-ACCT-ID = SPACES} and
     * {@code IF CC-ACCT-ID IS NUMERIC} answer exactly what they answered before, whatever width the
     * value was stored at.
     *
     * @return a new work area with all nine fields at their declared widths; this instance is
     *         unchanged
     */
    public CardScreenState asWorkArea() {
        CardScreenState workArea = new CardScreenState();
        workArea.ccardAid = moved(ccardAid, CCARD_AID_LENGTH);
        workArea.ccardNextProg = moved(ccardNextProg, CCARD_NEXT_PROG_LENGTH);
        workArea.ccardNextMapset = moved(ccardNextMapset, CCARD_NEXT_MAPSET_LENGTH);
        workArea.ccardNextMap = moved(ccardNextMap, CCARD_NEXT_MAP_LENGTH);
        workArea.ccardErrorMsg = moved(ccardErrorMsg, CCARD_ERROR_MSG_LENGTH);
        workArea.ccardReturnMsg = moved(ccardReturnMsg, CCARD_RETURN_MSG_LENGTH);
        workArea.ccAcctId = moved(ccAcctId, CC_ACCT_ID_LENGTH);
        workArea.ccCardNum = moved(ccCardNum, CC_CARD_NUM_LENGTH);
        workArea.ccCustId = moved(ccCustId, CC_CUST_ID_LENGTH);
        return workArea;
    }

    /**
     * Reproduces {@code INITIALIZE CC-WORK-AREA}, restoring every field to spaces at its declared
     * width.
     *
     * <p>All three card programs issue this statement as they begin processing -
     * {@code app/cbl/COCRDLIC.cbl:300}, {@code app/cbl/COCRDSLC.cbl:254},
     * {@code app/cbl/COCRDUPC.cbl:374}. A COBOL {@code INITIALIZE} without {@code REPLACING} sets
     * every alphanumeric item in the group to spaces and, notably, <em>ignores</em> the group's
     * {@code VALUE} clauses; here the two agree anyway, because all nine items are {@code PIC X} and
     * the three that declare a {@code VALUE} declare {@code SPACES}.
     */
    public void initializeWorkArea() {
        this.ccardAid = spaces(CCARD_AID_LENGTH);
        this.ccardNextProg = spaces(CCARD_NEXT_PROG_LENGTH);
        this.ccardNextMapset = spaces(CCARD_NEXT_MAPSET_LENGTH);
        this.ccardNextMap = spaces(CCARD_NEXT_MAP_LENGTH);
        this.ccardErrorMsg = spaces(CCARD_ERROR_MSG_LENGTH);
        this.ccardReturnMsg = spaces(CCARD_RETURN_MSG_LENGTH);
        this.ccAcctId = spaces(CC_ACCT_ID_LENGTH);
        this.ccCardNum = spaces(CC_CARD_NUM_LENGTH);
        this.ccCustId = spaces(CC_CUST_ID_LENGTH);
    }

    // =================================================================================================
    // The two figurative constants this work area distinguishes.
    //
    // SPACES, LOW-VALUES and Java null are three different things and this class never conflates them:
    // spaces are 0x20 under US-ASCII and 0x40 under IBM037, LOW-VALUES is 0x00 whichever code page is
    // in play, and null is not a COBOL state at all and is rejected wherever a value is expected.
    // =================================================================================================

    /**
     * The COBOL figurative constant {@code SPACES} rendered for a field of the given width.
     *
     * @param length the field's declared width in characters; at least 1
     * @return a string of exactly {@code length} spaces
     * @throws IllegalArgumentException if {@code length} is below 1
     */
    public static String spaces(int length) {
        requireDeclaredWidth(length, "SPACES");
        return " ".repeat(length);
    }

    /**
     * The COBOL figurative constant {@code LOW-VALUES} rendered for a field of the given width: the
     * character {@code U+0000} repeated, which is the byte {@code 0x00} repeated under both code pages
     * this system uses.
     *
     * <p>This is a genuine third state, distinct from {@link #spaces(int)} and from {@code null}. The
     * card programs write it deliberately - {@code MOVE LOW-VALUES TO CC-ACCT-ID} at
     * {@code app/cbl/COCRDSLC.cbl:617} and {@code app/cbl/COCRDUPC.cbl:591}, and the same for
     * {@code CC-CARD-NUM} at {@code COCRDSLC.cbl:624} and {@code COCRDUPC.cbl:600} - to mean "the user
     * supplied no filter", which their guard chains then test for separately from spaces.
     *
     * <p>{@code U+0000} maps to {@code 0x00} and back under both {@code US-ASCII} and {@code IBM037},
     * so the representation survives the round trip through either code page unchanged.
     *
     * @param length the field's declared width in characters; at least 1
     * @return a string of exactly {@code length} {@code U+0000} characters
     * @throws IllegalArgumentException if {@code length} is below 1
     */
    public static String lowValues(int length) {
        // One implementation of the LOW-VALUES image, in common.ScreenFieldImage, so the choice cannot
        // drift back apart across screens. Any width validation above is this method's own contract.
        requireDeclaredWidth(length, "LOW-VALUES");
        return ScreenFieldImage.unpainted(length);
    }

    // =================================================================================================
    // CCARD-AID PIC X(5) and its sixteen condition names.
    // =================================================================================================

    /**
     * {@code CCARD-AID} - the AID token, untrimmed and never padded here.
     *
     * @return the token as stored: at most {@link #CCARD_AID_LENGTH} characters, and exactly that many
     *         whenever the value came from a fixed-width image, a figurative constant or
     *         {@link #asWorkArea()}. One of the sixteen declared literals, or spaces when no key has
     *         been recorded
     */
    public String getCcardAid() {
        return ccardAid;
    }

    /**
     * Stores {@code CCARD-AID} without transforming it - up to 5 characters, verbatim.
     *
     * <p>Up to five characters are accepted, and stored exactly as given: a shorter value is NOT
     * padded here and an over-wide one is refused rather than truncated. The field is not validated
     * against the sixteen declared
     * literals, because the copybook's {@code 88}-levels are <em>tests</em> on the field rather than a
     * constraint over it, and {@code app/cpy/CSSTRPFY.cpy} has no {@code WHEN OTHER}: when
     * {@code EIBAID} matched nothing the field kept whatever the previous key left in it, and that
     * value has to survive the round trip.
     *
     * @param ccardAid the token; at most {@link #CCARD_AID_LENGTH} characters, stored verbatim
     * @throws NullPointerException     if {@code ccardAid} is {@code null}
     * @throws IllegalArgumentException if it is wider than {@link #CCARD_AID_LENGTH}
     */
    public void setCcardAid(String ccardAid) {
        this.ccardAid = requirePicX(ccardAid, CCARD_AID_LENGTH, "CCARD-AID");
    }

    /**
     * Reproduces {@code SET CCARD-AID-xxx TO TRUE} - for example
     * {@code app/cbl/COCRDLIC.cbl:379}, {@code app/cbl/COCRDSLC.cbl:298} and
     * {@code app/cbl/COCRDUPC.cbl:423}, which all set the {@code ENTER} condition - by storing the
     * condition's declared literal.
     *
     * <p>Taking the enum rather than a string makes this the only way to set the field that cannot
     * misspell a token, and it is the form {@code app/cpy/CSSTRPFY.cpy}'s resolver produces.
     *
     * @param aidKey the condition to make true
     * @throws NullPointerException if {@code aidKey} is {@code null}
     */
    @JsonIgnore
    public void setCcardAidCondition(AidKey aidKey) {
        Objects.requireNonNull(aidKey, "An AID condition is required; to store an unrecognised or "
                + "retained token use setCcardAid(String)");
        this.ccardAid = aidKey.token();
    }

    /**
     * The condition name currently true on {@code CCARD-AID}, if any.
     *
     * <p>An <em>empty</em> result is a real and expected outcome, not an error: the field is spaces
     * before any key is recorded, and {@code app/cpy/CSSTRPFY.cpy} leaves an unrecognised
     * {@code EIBAID} to retain the previous token, which may be a value no condition names. Nothing is
     * substituted for it.
     *
     * @return the matching condition, or {@link Optional#empty()} when the token matches none of the
     *         sixteen
     */
    public Optional<AidKey> aidKey() {
        for (AidKey candidate : AidKey.values()) {
            if (candidate.token().equals(ccardAid)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * {@code 88 CCARD-AID-ENTER} - tested at {@code COCRDLIC.cbl:371}, {@code COCRDSLC.cbl:292}.
     *
     * @return {@code true} when {@code CCARD-AID} holds the {@code CCARD-AID-ENTER} literal
     */
    @JsonIgnore
    public boolean isCcardAidEnter() {
        return CCARD_AID_ENTER.equals(ccardAid);
    }

    /**
     * {@code 88 CCARD-AID-CLEAR} - the CLEAR key.
     *
     * @return {@code true} when {@code CCARD-AID} holds the {@code CCARD-AID-CLEAR} literal
     */
    @JsonIgnore
    public boolean isCcardAidClear() {
        return CCARD_AID_CLEAR.equals(ccardAid);
    }

    /**
     * {@code 88 CCARD-AID-PA1} - matches the literal {@code 'PA1  '}, trailing spaces included.
     *
     * @return {@code true} when {@code CCARD-AID} holds the {@code CCARD-AID-PA1} literal
     */
    @JsonIgnore
    public boolean isCcardAidPa1() {
        return CCARD_AID_PA1.equals(ccardAid);
    }

    /**
     * {@code 88 CCARD-AID-PA2} - matches the literal {@code 'PA2  '}, trailing spaces included.
     *
     * @return {@code true} when {@code CCARD-AID} holds the {@code CCARD-AID-PA2} literal
     */
    @JsonIgnore
    public boolean isCcardAidPa2() {
        return CCARD_AID_PA2.equals(ccardAid);
    }

    /**
     * {@code 88 CCARD-AID-PFK01} - PF1, and PF13 which folds onto it.
     *
     * @return {@code true} when {@code CCARD-AID} holds the {@code CCARD-AID-PFK01} literal
     */
    @JsonIgnore
    public boolean isCcardAidPfk01() {
        return CCARD_AID_PFK01.equals(ccardAid);
    }

    /**
     * {@code 88 CCARD-AID-PFK02} - PF2 and PF14.
     *
     * @return {@code true} when {@code CCARD-AID} holds the {@code CCARD-AID-PFK02} literal
     */
    @JsonIgnore
    public boolean isCcardAidPfk02() {
        return CCARD_AID_PFK02.equals(ccardAid);
    }

    /**
     * {@code 88 CCARD-AID-PFK03} - PF3 and PF15; the exit key, tested at
     * {@code app/cbl/COCRDLIC.cbl:384} and {@code app/cbl/COCRDUPC.cbl:435}.
     *
     * @return {@code true} when {@code CCARD-AID} holds the {@code CCARD-AID-PFK03} literal
     */
    @JsonIgnore
    public boolean isCcardAidPfk03() {
        return CCARD_AID_PFK03.equals(ccardAid);
    }

    /**
     * {@code 88 CCARD-AID-PFK04} - PF4 and PF16.
     *
     * @return {@code true} when {@code CCARD-AID} holds the {@code CCARD-AID-PFK04} literal
     */
    @JsonIgnore
    public boolean isCcardAidPfk04() {
        return CCARD_AID_PFK04.equals(ccardAid);
    }

    /**
     * {@code 88 CCARD-AID-PFK05} - PF5 and PF17; the update-confirm key at {@code COCRDUPC.cbl:416}.
     *
     * @return {@code true} when {@code CCARD-AID} holds the {@code CCARD-AID-PFK05} literal
     */
    @JsonIgnore
    public boolean isCcardAidPfk05() {
        return CCARD_AID_PFK05.equals(ccardAid);
    }

    /**
     * {@code 88 CCARD-AID-PFK06} - PF6 and PF18.
     *
     * @return {@code true} when {@code CCARD-AID} holds the {@code CCARD-AID-PFK06} literal
     */
    @JsonIgnore
    public boolean isCcardAidPfk06() {
        return CCARD_AID_PFK06.equals(ccardAid);
    }

    /**
     * {@code 88 CCARD-AID-PFK07} - PF7 and PF19; page backward in {@code COCRDLIC.cbl:439}.
     *
     * @return {@code true} when {@code CCARD-AID} holds the {@code CCARD-AID-PFK07} literal
     */
    @JsonIgnore
    public boolean isCcardAidPfk07() {
        return CCARD_AID_PFK07.equals(ccardAid);
    }

    /**
     * {@code 88 CCARD-AID-PFK08} - PF8 and PF20; page forward in {@code COCRDLIC.cbl:410}.
     *
     * @return {@code true} when {@code CCARD-AID} holds the {@code CCARD-AID-PFK08} literal
     */
    @JsonIgnore
    public boolean isCcardAidPfk08() {
        return CCARD_AID_PFK08.equals(ccardAid);
    }

    /**
     * {@code 88 CCARD-AID-PFK09} - PF9 and PF21.
     *
     * @return {@code true} when {@code CCARD-AID} holds the {@code CCARD-AID-PFK09} literal
     */
    @JsonIgnore
    public boolean isCcardAidPfk09() {
        return CCARD_AID_PFK09.equals(ccardAid);
    }

    /**
     * {@code 88 CCARD-AID-PFK10} - PF10 and PF22.
     *
     * @return {@code true} when {@code CCARD-AID} holds the {@code CCARD-AID-PFK10} literal
     */
    @JsonIgnore
    public boolean isCcardAidPfk10() {
        return CCARD_AID_PFK10.equals(ccardAid);
    }

    /**
     * {@code 88 CCARD-AID-PFK11} - PF11 and PF23.
     *
     * @return {@code true} when {@code CCARD-AID} holds the {@code CCARD-AID-PFK11} literal
     */
    @JsonIgnore
    public boolean isCcardAidPfk11() {
        return CCARD_AID_PFK11.equals(ccardAid);
    }

    /**
     * {@code 88 CCARD-AID-PFK12} - PF12 and PF24; tested at {@code COCRDUPC.cbl:418} and {@code 484}.
     *
     * @return {@code true} when {@code CCARD-AID} holds the {@code CCARD-AID-PFK12} literal
     */
    @JsonIgnore
    public boolean isCcardAidPfk12() {
        return CCARD_AID_PFK12.equals(ccardAid);
    }

    // =================================================================================================
    // CCARD-NEXT-PROG X(8), CCARD-NEXT-MAPSET X(7), CCARD-NEXT-MAP X(7).
    //
    // These three replace EXEC CICS XCTL. The COBOL transfers control with
    // XCTL PROGRAM(CCARD-NEXT-PROG) at COCRDLIC.cbl:539 and :567, COCRDSLC.cbl:332 and
    // COCRDUPC.cbl:474, and sends the next screen with SEND MAP(CCARD-NEXT-MAP)
    // MAPSET(CCARD-NEXT-MAPSET) at COCRDSLC.cbl:569-570 and COCRDUPC.cbl:1329-1330. Under REST the
    // server stays stateless: these three travel back in the response and the client makes the next
    // call itself. There is no server-side forward and no redirect chain.
    // =================================================================================================

    /**
     * {@code CCARD-NEXT-PROG} - the eight-character name of the program the client should invoke next.
     *
     * @return the name, untrimmed and never padded here: at most
     *         {@link #CCARD_NEXT_PROG_LENGTH} characters, and exactly that many whenever the value came
     *         from a fixed-width image, a figurative constant or {@link #asWorkArea()}
     */
    public String getCcardNextProg() {
        return ccardNextProg;
    }

    /**
     * Stores {@code CCARD-NEXT-PROG} without transforming it - up to 8 characters, verbatim. The value is an opaque
     * token: it is not checked against a list of known programs, not case-folded and not trimmed.
     *
     * @param ccardNextProg the program name; at most its declared width, stored verbatim
     * @throws NullPointerException     if {@code ccardNextProg} is {@code null}
     * @throws IllegalArgumentException if it is wider than the eight characters the picture declares
     */
    public void setCcardNextProg(String ccardNextProg) {
        this.ccardNextProg = requirePicX(ccardNextProg, CCARD_NEXT_PROG_LENGTH, "CCARD-NEXT-PROG");
    }

    /**
     * {@code CCARD-NEXT-MAPSET} - the seven-character BMS mapset of the next screen.
     *
     * @return the mapset name as stored: at most {@link #CCARD_NEXT_MAPSET_LENGTH} characters, and
     *         exactly that many once {@link #asWorkArea()} or a fixed-width image has supplied it
     */
    public String getCcardNextMapset() {
        return ccardNextMapset;
    }

    /**
     * Stores {@code CCARD-NEXT-MAPSET} without transforming it - up to 7 characters, verbatim - seven, because a BMS
     * map name is seven characters plus the {@code I} or {@code O} suffix of its symbolic group.
     *
     * @param ccardNextMapset the mapset name; at most its declared width, stored verbatim
     * @throws NullPointerException     if {@code ccardNextMapset} is {@code null}
     * @throws IllegalArgumentException if it is wider than the seven characters the picture declares
     */
    public void setCcardNextMapset(String ccardNextMapset) {
        this.ccardNextMapset =
                requirePicX(ccardNextMapset, CCARD_NEXT_MAPSET_LENGTH, "CCARD-NEXT-MAPSET");
    }

    /**
     * {@code CCARD-NEXT-MAP} - the seven-character BMS map of the next screen.
     *
     * @return the map name as stored: at most {@link #CCARD_NEXT_MAP_LENGTH} characters, and exactly
     *         that many once {@link #asWorkArea()} or a fixed-width image has supplied it
     */
    public String getCcardNextMap() {
        return ccardNextMap;
    }

    /**
     * Stores {@code CCARD-NEXT-MAP} without transforming it - up to 7 characters, verbatim.
     *
     * <p>No map name is validated or corrected here, and that is deliberate:
     * {@code app/cbl/COCRDSLC.cbl:178} defines its card-list map literal as {@code 'CCRDSLA'} where
     * the card list's own map is {@code 'CCRDLIA'}. That is a defect in the source which this
     * migration preserves rather than repairs, so it has to be able to travel through this field
     * untouched.
     *
     * @param ccardNextMap the map name; at most its declared width, stored verbatim
     * @throws NullPointerException     if {@code ccardNextMap} is {@code null}
     * @throws IllegalArgumentException if it is wider than the seven characters the picture declares
     */
    public void setCcardNextMap(String ccardNextMap) {
        this.ccardNextMap = requirePicX(ccardNextMap, CCARD_NEXT_MAP_LENGTH, "CCARD-NEXT-MAP");
    }

    // =================================================================================================
    // The two PIC X(75) message fields, and the one LOW-VALUES condition the copybook declares.
    // =================================================================================================

    /**
     * {@code CCARD-ERROR-MSG PIC X(75)} - the error line, written by
     * {@code MOVE WS-ERROR-MSG TO CCARD-ERROR-MSG} at {@code app/cbl/COCRDLIC.cbl:423} and
     * {@code :587}, and by {@code MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG} at
     * {@code app/cbl/COCRDSLC.cbl:387}, {@code :395}, {@code :587} and
     * {@code app/cbl/COCRDUPC.cbl:547}, {@code :569}.
     *
     * <p>The copybook declares <strong>no</strong> {@code -OFF} condition for this field, so none is
     * offered; only {@code CCARD-RETURN-MSG} has one (practice <strong>B5</strong>).
     *
     * @return the message, untrimmed and never padded here: at most
     *         {@link #CCARD_ERROR_MSG_LENGTH} characters, and exactly that many whenever the value came
     *         from a fixed-width image, a figurative constant or {@link #asWorkArea()}
     */
    public String getCcardErrorMsg() {
        return ccardErrorMsg;
    }

    /**
     * Stores {@code CCARD-ERROR-MSG} without transforming it - up to 75 characters, verbatim.
     *
     * @param ccardErrorMsg the message; at most its declared width, stored verbatim
     * @throws NullPointerException     if {@code ccardErrorMsg} is {@code null}
     * @throws IllegalArgumentException if it is wider than the 75 characters the picture declares
     */
    public void setCcardErrorMsg(String ccardErrorMsg) {
        this.ccardErrorMsg = requirePicX(ccardErrorMsg, CCARD_ERROR_MSG_LENGTH, "CCARD-ERROR-MSG");
    }

    /**
     * {@code CCARD-RETURN-MSG PIC X(75)} - the return line.
     *
     * @return the message, untrimmed and never padded here: at most
     *         {@link #CCARD_RETURN_MSG_LENGTH} characters, and exactly that many whenever the value came
     *         from a fixed-width image, a figurative constant or {@link #asWorkArea()}
     */
    public String getCcardReturnMsg() {
        return ccardReturnMsg;
    }

    /**
     * Stores {@code CCARD-RETURN-MSG} without transforming it - up to 75 characters, verbatim.
     *
     * @param ccardReturnMsg the message; at most its declared width, stored verbatim
     * @throws NullPointerException     if {@code ccardReturnMsg} is {@code null}
     * @throws IllegalArgumentException if it is wider than the 75 characters the picture declares
     */
    public void setCcardReturnMsg(String ccardReturnMsg) {
        this.ccardReturnMsg = requirePicX(ccardReturnMsg, CCARD_RETURN_MSG_LENGTH, "CCARD-RETURN-MSG");
    }

    /**
     * Reproduces {@code MOVE LOW-VALUES TO CCARD-RETURN-MSG}, putting the field into the exact state
     * that makes {@link #isCcardReturnMsgOff()} true: 75 bytes of {@code 0x00}.
     *
     * <p>This is the only way to reach that state other than passing {@code lowValues(75)} to
     * {@link #setCcardReturnMsg(String)}, and it exists so the intent is unmistakable at the call
     * site - blanking the field with spaces would leave the condition false.
     */
    @JsonIgnore
    public void setCcardReturnMsgToLowValues() {
        this.ccardReturnMsg = lowValues(CCARD_RETURN_MSG_LENGTH);
    }

    /**
     * {@code 88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES}, {@code app/cpy/CVCRD01Y.cpy} line 30 - the
     * only condition name the copybook declares on a field other than {@code CCARD-AID}.
     *
     * <p>{@code LOW-VALUES} means the field is <strong>75 bytes of binary zero</strong>. It is
     * <em>not</em> 75 spaces and it is <em>not</em> absent, and this predicate never treats it as
     * either: a space-filled field returns {@code false}, and so does a field holding a message. The
     * distinction is load-bearing, because the consuming programs test this condition to decide
     * whether a message has already been set - see the {@code IF WS-RETURN-MSG-OFF} shape at
     * {@code app/cbl/COCRDSLC.cbl:657}.
     *
     * @return {@code true} only when every one of the 75 characters is {@code U+0000}
     */
    @JsonIgnore
    public boolean isCcardReturnMsgOff() {
        return isEvery(moved(ccardReturnMsg, CCARD_RETURN_MSG_LENGTH), '\u0000');
    }

    // =================================================================================================
    // The three REDEFINES pairs, app/cpy/CVCRD01Y.cpy lines 34 to 42.
    //
    //   CC-ACCT-ID  PIC X(11) VALUE SPACES  /  CC-ACCT-ID-N  REDEFINES ... PIC 9(11)   L34-36
    //   CC-CARD-NUM PIC X(16) VALUE SPACES  /  CC-CARD-NUM-N REDEFINES ... PIC 9(16)   L37-39
    //   CC-CUST-ID  PIC X(09) VALUE SPACES  /  CC-CUST-ID-N  REDEFINES ... PIC 9(9)    L40-42
    //
    // Each pair is ONE region of storage seen two ways, so each pair is one field here and a write
    // through either accessor is visible through the other. The COBOL relies on exactly that: it
    // stores through the numeric view with MOVE CDEMO-ACCT-ID TO CC-ACCT-ID-N
    // (COCRDSLC.cbl:342, COCRDUPC.cbl:490) and then displays the alphanumeric view with
    // MOVE CC-ACCT-ID TO ACCTSIDO (COCRDSLC.cbl:465, COCRDUPC.cbl:1090).
    //
    // ---------------------------------------------------------------------------------------------
    // A REDEFINES view is a reinterpretation, not a parse - and how the strict decode is reconciled
    // with it (practice B4: the conflict is recorded, not resolved in silence).
    // ---------------------------------------------------------------------------------------------
    // FixedWidthCodec.decodePic9 deliberately REJECTS a span that does not hold digits, on the
    // reasoning that a non-digit in a persisted numeric field is a real offset or data defect and that
    // failing loudly localises it. That policy is right for a dataset record. It is not sufficient on
    // its own here, because this is a screen work area whose numeric view is genuinely read while the
    // storage holds LOW-VALUES: COCRDUPC.cbl:591 and :600 move LOW-VALUES into these very fields, and
    // COCRDUPC.cbl:1087 and :1093 then evaluate IF CC-ACCT-ID-N = 0 and IF CC-CARD-NUM-N = 0 over
    // them. On the mainframe every one of those bytes carries a zero digit position, so the test is
    // true; an exception there would be a behaviour change, not a caught defect.
    //
    // The numeric view therefore reads as follows, and nothing else:
    //   * a span whose every character is U+0000, a space or '0' - which is precisely LOW-VALUES,
    //     SPACES and ZEROS, the three states the source writes - reads as 0;
    //   * every other span is handed to FixedWidthCodec.decodePic9, so digits are decoded by the one
    //     reviewable PIC 9 implementation in the module and genuine garbage still fails loudly there.
    // This class parses nothing itself. It invokes no string-to-number conversion of any kind, holds no
    // second decode of PIC 9 that could drift from the codec's, and - since this copybook declares no
    // scaled or signed picture - involves no decimal type and no rounding policy at all. A search of
    // this file for any of those is expected to find none.
    //
    // Garbage never reaches the strict path by accident, because the COBOL guards it first. Each card
    // program runs the same chain - "IF CC-ACCT-ID EQUAL LOW-VALUES OR CC-ACCT-ID EQUAL SPACES OR
    // CC-ACCT-ID-N EQUAL ZEROS" then "IF CC-ACCT-ID IS NOT NUMERIC" - at COCRDLIC.cbl:1007-1017 and
    // :1042-1052, COCRDSLC.cbl:651-665 and :691-706, and COCRDUPC.cbl:725-740 and :768-784. Every
    // element of that chain is exposed below as its own predicate, so a controller reproduces the
    // source's order exactly and reaches the numeric view only where the COBOL does.
    // =================================================================================================

    /**
     * {@code CC-ACCT-ID PIC X(11)} - the account identifier as characters.
     *
     * @return the identifier, untrimmed and never padded here: at most
     *         {@link #CC_ACCT_ID_LENGTH} characters, and exactly that many whenever the value came
     *         from a fixed-width image, a figurative constant or {@link #asWorkArea()}
     */
    public String getCcAcctId() {
        return ccAcctId;
    }

    /**
     * Stores {@code CC-ACCT-ID} without transforming it - up to 11 characters, verbatim, which is also visible through
     * {@link #getCcAcctIdN()}.
     *
     * @param ccAcctId the identifier; at most its declared width, stored verbatim
     * @throws NullPointerException     if {@code ccAcctId} is {@code null}
     * @throws IllegalArgumentException if it is wider than the eleven characters the picture declares
     */
    public void setCcAcctId(String ccAcctId) {
        this.ccAcctId = requirePicX(ccAcctId, CC_ACCT_ID_LENGTH, "CC-ACCT-ID");
    }

    /**
     * {@code CC-ACCT-ID-N PIC 9(11)} - the same eleven bytes seen as an unsigned integer, the view
     * {@code MOVE CC-ACCT-ID-N TO CARD-UPDATE-ACCT-ID} uses at {@code app/cbl/COCRDUPC.cbl:1463}.
     *
     * <p>{@code long} rather than {@code int}, because eleven digits overflow an {@code int}. The
     * {@code PICTURE} carries neither a scale nor a sign, so this view is a whole number and no
     * fixed-point type or rounding policy takes any part in it.
     *
     * @return {@code 0} when the span holds {@code LOW-VALUES}, spaces or zeros, otherwise the value
     *         its digits denote
     * @throws IllegalArgumentException if the span holds neither digits nor one of those three states -
     *                                  the codec's deliberate policy, which the
     *                                  {@link #isCcAcctIdNumeric()} guard exists to keep unreached
     */
    @JsonIgnore
    public long getCcAcctIdN() {
        return numericView(moved(ccAcctId, CC_ACCT_ID_LENGTH));
    }

    /**
     * Stores {@code CC-ACCT-ID-N} through the {@code PIC 9(11)} move rule - zero-filled on the left and
     * truncated on the left, so the low-order digits survive - which is also visible through
     * {@link #getCcAcctId()}. This is {@code MOVE CDEMO-ACCT-ID TO CC-ACCT-ID-N} at
     * {@code app/cbl/COCRDSLC.cbl:342} and {@code app/cbl/COCRDUPC.cbl:490}.
     *
     * @param ccAcctIdN the identifier; must not be negative, because {@code PIC 9} is unsigned and has
     *                  no sign position to store one in
     * @throws IllegalArgumentException if {@code ccAcctIdN} is negative
     */
    @JsonIgnore
    public void setCcAcctIdN(long ccAcctIdN) {
        this.ccAcctId = PICTURE_RULES.movePic9(ccAcctIdN, CC_ACCT_ID_LENGTH);
    }

    /**
     * Reproduces {@code MOVE LOW-VALUES TO CC-ACCT-ID} - {@code app/cbl/COCRDSLC.cbl:617},
     * {@code app/cbl/COCRDUPC.cbl:591} - which both programs use to record "no account filter was
     * supplied", a state their guard chains test separately from spaces.
     */
    @JsonIgnore
    public void setCcAcctIdToLowValues() {
        this.ccAcctId = lowValues(CC_ACCT_ID_LENGTH);
    }

    /**
     * {@code IF CC-ACCT-ID EQUAL LOW-VALUES} - the first arm of the guard chain at
     * {@code app/cbl/COCRDLIC.cbl:1007}, {@code app/cbl/COCRDSLC.cbl:651} and
     * {@code app/cbl/COCRDUPC.cbl:725}.
     *
     * @return {@code true} only when all eleven characters are {@code U+0000}
     */
    @JsonIgnore
    public boolean isCcAcctIdLowValues() {
        return isEvery(moved(ccAcctId, CC_ACCT_ID_LENGTH), '\u0000');
    }

    /**
     * {@code IF CC-ACCT-ID EQUAL SPACES} - the second arm of the same guard chain, at
     * {@code app/cbl/COCRDLIC.cbl:1008}, {@code app/cbl/COCRDSLC.cbl:652} and
     * {@code app/cbl/COCRDUPC.cbl:726}.
     *
     * @return {@code true} only when all eleven characters are spaces
     */
    @JsonIgnore
    public boolean isCcAcctIdSpaces() {
        return isEvery(moved(ccAcctId, CC_ACCT_ID_LENGTH), ' ');
    }

    /**
     * {@code IF CC-ACCT-ID-N EQUAL ZEROS} - the third arm of the same guard chain, at
     * {@code app/cbl/COCRDLIC.cbl:1009}, {@code app/cbl/COCRDSLC.cbl:653} and
     * {@code app/cbl/COCRDUPC.cbl:727}.
     *
     * <p>Offered as its own predicate, rather than leaving the caller to write
     * {@code getCcAcctIdN() == 0}, because the COBOL evaluates this arm over content that has not yet
     * been class-tested. This form cannot throw, which is what lets the chain be reproduced in source
     * order.
     *
     * @return {@code true} when every character occupies a zero digit position - that is when the span
     *         is {@code LOW-VALUES}, all spaces or all zeros
     */
    @JsonIgnore
    public boolean isCcAcctIdNZeros() {
        return isZeroValued(moved(ccAcctId, CC_ACCT_ID_LENGTH));
    }

    /**
     * {@code IF CC-ACCT-ID IS NUMERIC} - the COBOL class test that guards every numeric use, applied
     * at {@code app/cbl/COCRDLIC.cbl:1017}, {@code app/cbl/COCRDSLC.cbl:665} and
     * {@code app/cbl/COCRDUPC.cbl:740} (each written as {@code IS NOT NUMERIC}).
     *
     * @return {@code true} only when every one of the eleven characters is a digit
     */
    @JsonIgnore
    public boolean isCcAcctIdNumeric() {
        return isEveryDigit(moved(ccAcctId, CC_ACCT_ID_LENGTH));
    }

    /**
     * {@code CC-CARD-NUM PIC X(16)} - the card number as characters, in the clear exactly as the COBOL
     * work area holds it (practice <strong>B6</strong>: the security posture is neither weakened nor
     * strengthened here).
     *
     * @return the card number, untrimmed and never padded here: at most
     *         {@link #CC_CARD_NUM_LENGTH} characters, and exactly that many whenever the value came
     *         from a fixed-width image, a figurative constant or {@link #asWorkArea()}
     */
    public String getCcCardNum() {
        return ccCardNum;
    }

    /**
     * Stores {@code CC-CARD-NUM} without transforming it - up to 16 characters, verbatim, which is also visible through
     * {@link #getCcCardNumN()}. This is {@code MOVE CARDSIDI OF CCRDSLAI TO CC-CARD-NUM} at
     * {@code app/cbl/COCRDSLC.cbl:626}.
     *
     * @param ccCardNum the card number; at most its declared width, stored verbatim
     * @throws NullPointerException     if {@code ccCardNum} is {@code null}
     * @throws IllegalArgumentException if it is wider than the sixteen characters the picture declares
     */
    public void setCcCardNum(String ccCardNum) {
        this.ccCardNum = requirePicX(ccCardNum, CC_CARD_NUM_LENGTH, "CC-CARD-NUM");
    }

    /**
     * {@code CC-CARD-NUM-N PIC 9(16)} - the same sixteen bytes seen as an unsigned integer, the view
     * {@code MOVE CC-CARD-NUM-N TO CDEMO-CARD-NUM} uses at {@code app/cbl/COCRDLIC.cbl:1064},
     * {@code app/cbl/COCRDSLC.cbl:717} and {@code app/cbl/COCRDUPC.cbl:796}, and the one
     * {@code IF CARD-NUM = CC-CARD-NUM-N} compares at {@code app/cbl/COCRDLIC.cbl:1397}.
     *
     * @return {@code 0} when the span holds {@code LOW-VALUES}, spaces or zeros, otherwise the value
     *         its digits denote
     * @throws IllegalArgumentException if the span holds neither digits nor one of those three states
     */
    @JsonIgnore
    public long getCcCardNumN() {
        return numericView(moved(ccCardNum, CC_CARD_NUM_LENGTH));
    }

    /**
     * Stores {@code CC-CARD-NUM-N} through the {@code PIC 9(16)} move rule, which is also visible
     * through {@link #getCcCardNum()}. This is {@code MOVE CDEMO-CARD-NUM TO CC-CARD-NUM-N} at
     * {@code app/cbl/COCRDSLC.cbl:343} and {@code app/cbl/COCRDUPC.cbl:491}.
     *
     * @param ccCardNumN the card number; must not be negative
     * @throws IllegalArgumentException if {@code ccCardNumN} is negative
     */
    @JsonIgnore
    public void setCcCardNumN(long ccCardNumN) {
        this.ccCardNum = PICTURE_RULES.movePic9(ccCardNumN, CC_CARD_NUM_LENGTH);
    }

    /**
     * Reproduces {@code MOVE LOW-VALUES TO CC-CARD-NUM} - {@code app/cbl/COCRDSLC.cbl:624},
     * {@code app/cbl/COCRDUPC.cbl:600}.
     */
    @JsonIgnore
    public void setCcCardNumToLowValues() {
        this.ccCardNum = lowValues(CC_CARD_NUM_LENGTH);
    }

    /**
     * {@code IF CC-CARD-NUM EQUAL LOW-VALUES} - {@code app/cbl/COCRDLIC.cbl:1042},
     * {@code app/cbl/COCRDSLC.cbl:691}, {@code app/cbl/COCRDUPC.cbl:768}.
     *
     * @return {@code true} only when all sixteen characters are {@code U+0000}
     */
    @JsonIgnore
    public boolean isCcCardNumLowValues() {
        return isEvery(moved(ccCardNum, CC_CARD_NUM_LENGTH), '\u0000');
    }

    /**
     * {@code IF CC-CARD-NUM EQUAL SPACES} - {@code app/cbl/COCRDLIC.cbl:1043},
     * {@code app/cbl/COCRDSLC.cbl:692}, {@code app/cbl/COCRDUPC.cbl:769}.
     *
     * @return {@code true} only when all sixteen characters are spaces
     */
    @JsonIgnore
    public boolean isCcCardNumSpaces() {
        return isEvery(moved(ccCardNum, CC_CARD_NUM_LENGTH), ' ');
    }

    /**
     * {@code IF CC-CARD-NUM-N EQUAL ZEROS} - {@code app/cbl/COCRDLIC.cbl:1044},
     * {@code app/cbl/COCRDSLC.cbl:693}, {@code app/cbl/COCRDUPC.cbl:770}.
     *
     * @return {@code true} when the span is {@code LOW-VALUES}, all spaces or all zeros
     */
    @JsonIgnore
    public boolean isCcCardNumNZeros() {
        return isZeroValued(moved(ccCardNum, CC_CARD_NUM_LENGTH));
    }

    /**
     * {@code IF CC-CARD-NUM IS NUMERIC} - {@code app/cbl/COCRDLIC.cbl:1052},
     * {@code app/cbl/COCRDSLC.cbl:706}, {@code app/cbl/COCRDUPC.cbl:784} (each written as
     * {@code IS NOT NUMERIC}).
     *
     * @return {@code true} only when every one of the sixteen characters is a digit
     */
    @JsonIgnore
    public boolean isCcCardNumNumeric() {
        return isEveryDigit(moved(ccCardNum, CC_CARD_NUM_LENGTH));
    }

    /**
     * {@code CC-CUST-ID PIC X(09)} - the customer identifier as characters.
     *
     * <p>No {@code LOW-VALUES}, {@code SPACES}, {@code ZEROS} or class-test predicate is offered for
     * this field, unlike the two above, because no consuming program performs any of those tests on
     * it. Adding them would be speculative surface rather than a transcription of the source.
     *
     * @return the identifier, untrimmed and never padded here: at most
     *         {@link #CC_CUST_ID_LENGTH} characters, and exactly that many whenever the value came
     *         from a fixed-width image, a figurative constant or {@link #asWorkArea()}
     */
    public String getCcCustId() {
        return ccCustId;
    }

    /**
     * Stores {@code CC-CUST-ID} without transforming it - up to 09 characters, verbatim, which is also visible through
     * {@link #getCcCustIdN()}.
     *
     * @param ccCustId the identifier; at most its declared width, stored verbatim
     * @throws NullPointerException     if {@code ccCustId} is {@code null}
     * @throws IllegalArgumentException if it is wider than the nine characters the picture declares
     */
    public void setCcCustId(String ccCustId) {
        this.ccCustId = requirePicX(ccCustId, CC_CUST_ID_LENGTH, "CC-CUST-ID");
    }

    /**
     * {@code CC-CUST-ID-N PIC 9(9)} - the same nine bytes seen as an unsigned integer.
     *
     * <p>Returned as a {@code long} even though nine digits fit an {@code int}, so that all three
     * overlays of this work area present one type and a caller cannot pick the wrong one by habit.
     *
     * @return {@code 0} when the span holds {@code LOW-VALUES}, spaces or zeros, otherwise the value
     *         its digits denote
     * @throws IllegalArgumentException if the span holds neither digits nor one of those three states
     */
    @JsonIgnore
    public long getCcCustIdN() {
        return numericView(moved(ccCustId, CC_CUST_ID_LENGTH));
    }

    /**
     * Stores {@code CC-CUST-ID-N} through the {@code PIC 9(9)} move rule, which is also visible through
     * {@link #getCcCustId()}.
     *
     * @param ccCustIdN the identifier; must not be negative
     * @throws IllegalArgumentException if {@code ccCustIdN} is negative
     */
    @JsonIgnore
    public void setCcCustIdN(long ccCustIdN) {
        this.ccCustId = PICTURE_RULES.movePic9(ccCustIdN, CC_CUST_ID_LENGTH);
    }

    // =================================================================================================
    // The byte boundary - the only place in this class where characters become bytes or bytes become
    // characters, and the only place a Charset appears.
    //
    // The code page is always the caller's, passed in explicitly and never derived from the platform
    // (practice B8): IBM037 for the EBCDIC datasets, US-ASCII for the fixtures under app/data/ASCII.
    // Nothing is imported from the sibling configuration package that resolves those two code pages -
    // doing so would invert the module's dependency direction - so the resolved Charset arrives as an
    // argument instead.
    // =================================================================================================

    /**
     * Renders this work area as its {@link #RECORD_LENGTH}-byte fixed-width image.
     *
     * <p>Each field is written into its declared span through the {@code PIC X} move rule - one of the
     * two explicit conversion steps in this class, the other being {@link #asWorkArea()} - so a value
     * that was stored shorter than its field is padded here and the result is exactly 213 bytes with
     * every field at its declared offset and width, the form a parity test compares field by field.
     *
     * @param charset the code page to encode into, named explicitly by the caller
     * @return a fresh array of exactly {@link #RECORD_LENGTH} bytes
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} does not encode every digit, sign overpunch
     *                                  character and the space to exactly one byte, since a
     *                                  fixed-width span is addressed by absolute byte offset
     */
    public byte[] toFixedWidth(Charset charset) {
        Objects.requireNonNull(charset, "A charset is required to render CC-WORK-AREA as bytes: a "
                + "fixed-width image is bytes in a specific code page, so the code page must be "
                + "stated explicitly and is never taken from the platform");
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        FixedWidthRecord record = codec.newRecord(LAYOUT);
        writeInto(record, codec);
        return record.toByteArray();
    }

    /**
     * Writes this work area into an existing record area, using that record's own code page.
     *
     * <p>Provided for the case where {@code CC-WORK-AREA} is being placed inside a larger area the
     * caller already owns; the record carries the {@link Charset} it was allocated with, so no second
     * code page can enter through here.
     *
     * @param record a record area of exactly {@link #RECORD_LENGTH} bytes
     * @throws NullPointerException     if {@code record} is {@code null}
     * @throws IllegalArgumentException if the record's declared length is not {@link #RECORD_LENGTH}
     */
    public void writeInto(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A record area is required to write CC-WORK-AREA into");
        writeInto(record, new FixedWidthCodec(record.charset()));
    }

    /**
     * Rebuilds a work area from its fixed-width image.
     *
     * <p>The byte count must be exactly {@link #RECORD_LENGTH}; a short or over-long image is rejected
     * rather than tolerated, because silently accepting one would let every field offset after the
     * discrepancy drift.
     *
     * @param bytes   the image, exactly {@link #RECORD_LENGTH} bytes
     * @param charset the code page the image is encoded in, named explicitly by the caller
     * @return a work area holding the nine fields the image carries
     * @throws NullPointerException     if {@code bytes} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code bytes.length} is not {@link #RECORD_LENGTH}, or if
     *                                  {@code charset} is not single-byte for the digits and the space
     */
    public static CardScreenState fromFixedWidth(byte[] bytes, Charset charset) {
        Objects.requireNonNull(bytes, "An image is required to rebuild CC-WORK-AREA");
        Objects.requireNonNull(charset, "A charset is required to decode a CC-WORK-AREA image: the "
                + "code page must be stated explicitly and is never taken from the platform");
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return readFrom(codec.wrap(bytes, LAYOUT), codec);
    }

    /**
     * Reads a work area out of an existing record area, using that record's own code page.
     *
     * @param record a record area of exactly {@link #RECORD_LENGTH} bytes
     * @return a work area holding the nine fields the record carries
     * @throws NullPointerException     if {@code record} is {@code null}
     * @throws IllegalArgumentException if the record's declared length is not {@link #RECORD_LENGTH}
     */
    public static CardScreenState readFrom(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A record area is required to read CC-WORK-AREA from");
        return readFrom(record, new FixedWidthCodec(record.charset()));
    }

    /**
     * The single write path, and the place the {@code PIC X} move rule is actually applied: a field
     * stored shorter than its span - which is what a lossless setter permits - is padded on the right
     * here by {@link FixedWidthCodec#writePicX}, so that one implementation of the rule governs every
     * field in the module and no field is padded anywhere else.
     *
     * @param record the record area to write into, of exactly {@link #RECORD_LENGTH} bytes
     * @param codec  the codec for the record's code page
     */
    private void writeInto(FixedWidthRecord record, FixedWidthCodec codec) {
        requireWorkAreaWidth(record);
        codec.writePicX(record, CCARD_AID_SPAN, ccardAid);
        codec.writePicX(record, CCARD_NEXT_PROG_SPAN, ccardNextProg);
        codec.writePicX(record, CCARD_NEXT_MAPSET_SPAN, ccardNextMapset);
        codec.writePicX(record, CCARD_NEXT_MAP_SPAN, ccardNextMap);
        codec.writePicX(record, CCARD_ERROR_MSG_SPAN, ccardErrorMsg);
        codec.writePicX(record, CCARD_RETURN_MSG_SPAN, ccardReturnMsg);
        codec.writePicX(record, CC_ACCT_ID_SPAN, ccAcctId);
        codec.writePicX(record, CC_CARD_NUM_SPAN, ccCardNum);
        codec.writePicX(record, CC_CUST_ID_SPAN, ccCustId);
    }

    /**
     * The single read path. Spans are read <strong>untrimmed</strong>, because a {@code PIC X} field is
     * space-padded to its full width and that padding is part of the field's value; the overlays are
     * not read separately, since each views bytes one of the nine storage spans has already supplied.
     *
     * @param record the record area to read from, of exactly {@link #RECORD_LENGTH} bytes
     * @param codec  the codec for the record's code page
     * @return a work area holding the nine fields the record carries
     */
    private static CardScreenState readFrom(FixedWidthRecord record, FixedWidthCodec codec) {
        requireWorkAreaWidth(record);
        CardScreenState state = new CardScreenState();
        state.ccardAid = codec.readPicX(record, CCARD_AID_SPAN);
        state.ccardNextProg = codec.readPicX(record, CCARD_NEXT_PROG_SPAN);
        state.ccardNextMapset = codec.readPicX(record, CCARD_NEXT_MAPSET_SPAN);
        state.ccardNextMap = codec.readPicX(record, CCARD_NEXT_MAP_SPAN);
        state.ccardErrorMsg = codec.readPicX(record, CCARD_ERROR_MSG_SPAN);
        state.ccardReturnMsg = codec.readPicX(record, CCARD_RETURN_MSG_SPAN);
        state.ccAcctId = codec.readPicX(record, CC_ACCT_ID_SPAN);
        state.ccCardNum = codec.readPicX(record, CC_CARD_NUM_SPAN);
        state.ccCustId = codec.readPicX(record, CC_CUST_ID_SPAN);
        return state;
    }

    // =================================================================================================
    // Object contract. Value semantics over the nine storage fields, so a test can compare two work
    // areas directly and a controller can tell whether the state it is about to return differs from the
    // state it received.
    // =================================================================================================

    /**
     * Two work areas are equal when all nine fields are equal <em>as {@code CC-WORK-AREA} holds
     * them</em> - that is, after the {@code PIC X} move rule has been applied to each. The three
     * {@code REDEFINES} overlays take no part, because each is a view of a field already compared -
     * including one would compare the same bytes twice.
     *
     * <p>Comparing the moved image rather than the raw storage is what keeps this equality meaningful
     * now that a setter stores a short value verbatim: {@code "S"} and {@code "S       "} are the same
     * eight-byte {@code CCARD-NEXT-PROG} in COBOL, they produce the same byte in the 213-byte image,
     * and a controller deciding whether the state it is about to return differs from the state it
     * received must not be told that they differ.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a work area whose nine fields move to the same values
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CardScreenState that)) {
            return false;
        }
        return moved(ccardAid, CCARD_AID_LENGTH).equals(moved(that.ccardAid, CCARD_AID_LENGTH))
                && moved(ccardNextProg, CCARD_NEXT_PROG_LENGTH)
                        .equals(moved(that.ccardNextProg, CCARD_NEXT_PROG_LENGTH))
                && moved(ccardNextMapset, CCARD_NEXT_MAPSET_LENGTH)
                        .equals(moved(that.ccardNextMapset, CCARD_NEXT_MAPSET_LENGTH))
                && moved(ccardNextMap, CCARD_NEXT_MAP_LENGTH)
                        .equals(moved(that.ccardNextMap, CCARD_NEXT_MAP_LENGTH))
                && moved(ccardErrorMsg, CCARD_ERROR_MSG_LENGTH)
                        .equals(moved(that.ccardErrorMsg, CCARD_ERROR_MSG_LENGTH))
                && moved(ccardReturnMsg, CCARD_RETURN_MSG_LENGTH)
                        .equals(moved(that.ccardReturnMsg, CCARD_RETURN_MSG_LENGTH))
                && moved(ccAcctId, CC_ACCT_ID_LENGTH).equals(moved(that.ccAcctId, CC_ACCT_ID_LENGTH))
                && moved(ccCardNum, CC_CARD_NUM_LENGTH)
                        .equals(moved(that.ccCardNum, CC_CARD_NUM_LENGTH))
                && moved(ccCustId, CC_CUST_ID_LENGTH).equals(moved(that.ccCustId, CC_CUST_ID_LENGTH));
    }

    /**
     * A hash over the same nine moved field images {@link #equals(Object)} compares, so the two stay
     * consistent for a value stored shorter than its declared width.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(moved(ccardAid, CCARD_AID_LENGTH),
                moved(ccardNextProg, CCARD_NEXT_PROG_LENGTH),
                moved(ccardNextMapset, CCARD_NEXT_MAPSET_LENGTH),
                moved(ccardNextMap, CCARD_NEXT_MAP_LENGTH),
                moved(ccardErrorMsg, CCARD_ERROR_MSG_LENGTH),
                moved(ccardReturnMsg, CCARD_RETURN_MSG_LENGTH),
                moved(ccAcctId, CC_ACCT_ID_LENGTH),
                moved(ccCardNum, CC_CARD_NUM_LENGTH),
                moved(ccCustId, CC_CUST_ID_LENGTH));
    }

    /**
     * A diagnostic rendering of all nine fields in which the three identifier fields are withheld.
     *
     * <p>The screen-state fields - the attention identifier, the next program, mapset and map, and both
     * message fields - render <strong>verbatim</strong>, because they are what a navigation or
     * validation parity failure is diagnosed from. A field holding {@code LOW-VALUES} therefore renders
     * as its actual {@code U+0000} characters; {@link #isCcardReturnMsgOff()} is the way to test for
     * that state rather than reading it out of this string.
     *
     * <p>The three identifiers - {@code CC-ACCT-ID}, {@code CC-CARD-NUM} and {@code CC-CUST-ID} - are
     * masked per {@link SensitiveDiagnostics}. This is <strong>not</strong> a departure from practice
     * <strong>B6</strong>, which an earlier version of this comment cited to argue the opposite, and the
     * distinction is worth stating because it governs every masked rendering in this module.
     *
     * <p>B6 forbids changing the migrated program's security posture in either direction: the work area
     * holds these values in the clear, so the DTO holds them in the clear; {@code SEC-USR-PWD} is
     * compared as plaintext, so it stays plaintext. All of that is intact here - the components, the
     * accessors, the JSON payload and {@link #encode(Charset)} are untouched, and every one still
     * carries the real value. What is masked is only this {@code toString}, and COBOL has no
     * {@code toString}: the disclosure exists solely because the target language renders objects. Closing
     * a channel the legacy system never had is not strengthening its posture, and no parity case, no
     * screen field and no byte image can observe the difference.
     *
     * @return the rendering, safe to log; for diagnostics only, never a wire format
     */
    @Override
    public String toString() {
        return "CardScreenState[CCARD-AID='" + ccardAid
                + "', CCARD-NEXT-PROG='" + ccardNextProg
                + "', CCARD-NEXT-MAPSET='" + ccardNextMapset
                + "', CCARD-NEXT-MAP='" + ccardNextMap
                + "', CCARD-ERROR-MSG='" + ccardErrorMsg
                + "', CCARD-RETURN-MSG='" + ccardReturnMsg
                + "', CC-ACCT-ID='" + SensitiveDiagnostics.maskIdentifier(ccAcctId)
                + "', CC-CARD-NUM='" + SensitiveDiagnostics.maskPan(ccCardNum)
                + "', CC-CUST-ID='" + SensitiveDiagnostics.maskIdentifier(ccCustId)
                + "']";
    }

    // =================================================================================================
    // Private helpers. Each is one rule, named after the COBOL construct it implements.
    // =================================================================================================

    /**
     * Stores a field value losslessly: rejects {@code null}, rejects a value wider than the field's
     * {@code PICTURE} clause, and returns anything that fits <strong>unchanged</strong>.
     *
     * <p>This is the guard every public setter uses, and it is deliberately not a {@code MOVE}. A
     * setter is a JSON binding point, and a {@code MOVE} there would pad a short value and silently
     * truncate an over-wide one - so an empty field and a field of spaces would become
     * indistinguishable, and a caller who sent seventeen digits into {@code CC-CARD-NUM} would be told
     * nothing at all while sixteen of them were kept. The refusal is reported as a {@code 400} by
     * {@code config.WebConfig.CobolErrorHandler}, whose body names neither the field nor the value.
     *
     * <p>{@code null} is not a COBOL state. A field is spaces, or {@code LOW-VALUES}, or it holds data;
     * "absent" is not among the possibilities, so accepting {@code null} would force this class to
     * invent which of the three the caller meant. The two figurative constants are available as
     * {@link #spaces(int)} and {@link #lowValues(int)}.
     *
     * @param value     the value being stored
     * @param length    the receiving field's declared width
     * @param cobolName the copybook name of the receiving field, for the failure message
     * @return the value unchanged
     */
    private static String requirePicX(String value, int length, String cobolName) {
        Objects.requireNonNull(value, "A value is required for " + cobolName + ": COBOL has no null, "
                + "so pass spaces(" + length + ") or lowValues(" + length + ") to state which "
                + "figurative constant is meant");
        if (value.length() > length) {
            throw new IllegalArgumentException("Field " + cobolName + " of CC-WORK-AREA is declared "
                    + "PIC X(" + length + ") but was given " + value.length() + " character(s) "
                    + "(value " + REDACTED + "). This work area never truncates while binding, so "
                    + "that the loss of a character is always a deliberate act rather than a silent "
                    + "one. To shorten the value, pass it through "
                    + "FixedWidthCodec.movePicX(value, " + length + "), which truncates on the right "
                    + "as a COBOL alphanumeric MOVE does");
        }
        return value;
    }

    /**
     * Applies the {@code PIC X} move rule to a stored field, producing the image
     * {@code CC-WORK-AREA} actually holds: the value padded on the right to its declared width, or
     * truncated there if it somehow exceeded it.
     *
     * <p>This is the named step {@link #asWorkArea()} and every COBOL-semantic read below share. It
     * exists because storage and semantics are deliberately separated in this class: a field holds
     * exactly what the wire sent, and the {@code MOVE} that turns that into a fixed-width work-area
     * item happens here, where it can be pointed at. The rule itself is still
     * {@link FixedWidthCodec}'s and is not reimplemented.
     *
     * @param value  the stored value
     * @param length the field's declared width
     * @return the value as exactly {@code length} characters
     */
    private static String moved(String value, int length) {
        return PICTURE_RULES.movePicX(value, length);
    }

    /**
     * The {@code PIC 9} view of a span, as described at length in the {@code REDEFINES} section above:
     * a span occupying only zero digit positions reads as {@code 0}, and everything else is decoded by
     * {@link FixedWidthCodec#decodePic9(String)} so that one implementation governs the digits and the
     * codec's own policy governs genuine garbage.
     *
     * @param image the span's characters
     * @return the value the span denotes under the {@code PIC 9} view
     */
    private static long numericView(String image) {
        if (isZeroValued(image)) {
            return 0L;
        }
        return PICTURE_RULES.decodePic9(image);
    }

    /**
     * Whether every character of a span occupies a zero digit position, which is the state
     * {@code EQUAL ZEROS} reports true for. That is exactly {@code LOW-VALUES}, all spaces or all
     * zeros: {@code U+0000} encodes to {@code 0x00}, the space to {@code 0x40} under EBCDIC and
     * {@code 0x20} under ASCII, and {@code '0'} to {@code 0xF0} and {@code 0x30} respectively - every
     * one of which carries a zero in its digit position under both code pages in play.
     *
     * @param image the span's characters
     * @return {@code true} when every character occupies a zero digit position
     */
    private static boolean isZeroValued(String image) {
        for (int index = 0; index < image.length(); index++) {
            char character = image.charAt(index);
            if (character != '\u0000' && character != ' ' && character != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether every character of a span is the given one - the {@code EQUAL SPACES} shape.
     *
     * @param image    the span's characters
     * @param expected the character every position must hold
     * @return {@code true} when every character equals {@code expected}
     */
    private static boolean isEvery(String image, char expected) {
        for (int index = 0; index < image.length(); index++) {
            if (image.charAt(index) != expected) {
                return false;
            }
        }
        return true;
    }

    /**
     * The COBOL {@code IS NUMERIC} class test for a {@code PIC X} field: true only when every character
     * is one of {@code '0'} through {@code '9'}.
     *
     * <p>{@link Character#isDigit(char)} is deliberately not used, because it accepts the decimal
     * digits of every Unicode script and a COBOL class test accepts none of them.
     *
     * @param image the span's characters
     * @return {@code true} when every character is one of {@code '0'} through {@code '9'}
     */
    private static boolean isEveryDigit(String image) {
        for (int index = 0; index < image.length(); index++) {
            char character = image.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Rejects a non-positive width for a figurative constant, naming the constant asked for.
     *
     * @param length              the width requested
     * @param figurativeConstant  the constant being rendered, for the failure message
     * @throws IllegalArgumentException if {@code length} is below 1
     */
    private static void requireDeclaredWidth(int length, String figurativeConstant) {
        if (length < 1) {
            throw new IllegalArgumentException("Cannot render " + figurativeConstant + " for a field "
                    + "of " + length + " character(s); every item of CC-WORK-AREA is at least 1 byte "
                    + "wide");
        }
    }

    /**
     * Rejects a record area that is not exactly the width {@code CC-WORK-AREA} declares.
     *
     * @param record the record area to check
     * @throws IllegalArgumentException if the record's declared length is not {@link #RECORD_LENGTH}
     */
    private static void requireWorkAreaWidth(FixedWidthRecord record) {
        if (record.recordLength() != RECORD_LENGTH) {
            throw new IllegalArgumentException("CC-WORK-AREA is " + RECORD_LENGTH + " byte(s) wide "
                    + "but the record area is " + record.recordLength() + "; app/cpy/CVCRD01Y.cpy "
                    + "declares 5 + 8 + 7 + 7 + 75 + 75 + 11 + 16 + 9 bytes of storage, its three "
                    + "REDEFINES overlays adding none");
        }
    }
}
