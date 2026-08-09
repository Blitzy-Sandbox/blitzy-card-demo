package com.vsergeychik.carddemo.transaction.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The outbound REST payload of {@code POST /api/reports} - CSD transaction {@code CR00}, program
 * {@code app/cbl/CORPT00C.cbl} - projected field for field from the {@code xxxO} items of
 * {@code 01 CORPT0AO REDEFINES CORPT0AI} in {@code app/cpy-bms/CORPT00.CPY} (line 121) and their
 * name-labelled {@code DFHMDF} definitions in {@code app/bms/CORPT00.bms}.
 *
 * <p>This is a like-for-like migration. Nothing here is added, removed, renamed, reordered or
 * "improved" relative to those two files.
 *
 * <h2>The symbolic map, transcribed</h2>
 *
 * <p>{@code CORPT00.CPY} declares two {@code 01} groups over one span of storage. The first,
 * {@code CORPT0AI} (lines 17-120), is the inbound view; the second, {@code CORPT0AO} (lines 121-224),
 * <strong>redefines</strong> it and is the outbound view this type projects. Both views spend exactly
 * <strong>seven</strong> prefix bytes per screen field:
 *
 * <pre>
 *   CORPT0AI, per field           CORPT0AO, per field
 *   ---------------------------   -----------------------------
 *   xxxL  COMP PIC S9(4)    2     FILLER    PIC     X(3)     3
 *   xxxF       PICTURE X    1     xxxC      PICTURE X        1   &lt;- COLOR
 *   FILLER     PICTURE X(4) 4     xxxP      PICTURE X        1   &lt;- PS
 *                          ---    xxxH      PICTURE X        1   &lt;- HILIGHT
 *                           7     xxxV      PICTURE X        1   &lt;- VALIDN
 *                                                           ---
 *                                                            7
 * </pre>
 *
 * <p>Because the two prefixes are the same width, every field's {@code I} item and {@code O} item sit
 * at the <strong>identical offset</strong>. They are storage aliases, not distinct fields, and
 * {@code CORPT00C} genuinely writes through both - it stores normalised date parts back into the
 * {@code ...AI} alias (lines 307, 311, 315, 319, 323, 327) while sending from {@code CORPT0AO}
 * (lines 566, 574). The Request/Response split is therefore a <em>directional projection
 * convention</em>, not a read/write split: {@code xxxO} is never write-only, and round-tripping this
 * type must be lossless.
 *
 * <h2>Byte geometry</h2>
 *
 * <p>Seventeen screen fields, each preceded by its seven-byte attribute prefix, after a twelve-byte
 * {@code TIOAPFX=YES} prefix (line 122):
 *
 * <pre>
 *   attr  xxxO  bytes  PICTURE  item        BMS LENGTH  COLOR    .CPY line
 *   ----  ----  -----  -------  ----------  ----------  -------  ---------
 *     12    19      4  X(4)     TRNNAMEO             4  BLUE           128
 *     23    30     40  X(40)    TITLE01O            40  YELLOW         134
 *     70    77      8  X(8)     CURDATEO             8  BLUE           140
 *     85    92      8  X(8)     PGMNAMEO             8  BLUE           146
 *    100   107     40  X(40)    TITLE02O            40  YELLOW         152
 *    147   154      8  X(8)     CURTIMEO             8  BLUE           158
 *    162   169      1  X(1)     MONTHLYO             1  GREEN          164
 *    170   177      1  X(1)     YEARLYO              1  GREEN          170
 *    178   185      1  X(1)     CUSTOMO              1  GREEN          176
 *    186   193      2  X(2)     SDTMMO               2  GREEN          182
 *    195   202      2  X(2)     SDTDDO               2  GREEN          188
 *    204   211      4  X(4)     SDTYYYYO             4  GREEN          194
 *    215   222      2  X(2)     EDTMMO               2  GREEN          200
 *    224   231      2  X(2)     EDTDDO               2  GREEN          206
 *    233   240      4  X(4)     EDTYYYYO             4  GREEN          212
 *    244   251      1  X(1)     CONFIRMO             1  GREEN          218
 *    252   259     78  X(78)    ERRMSGO             78  RED            224
 *               -----
 *                 206  = 4+40+8+8+40+8+1+1+1+2+2+4+2+2+4+1+78
 *
 *   12 (TIOAPFX) + 17 x 7 (attribute prefixes) + 206 (payload) = 337 bytes
 * </pre>
 *
 * <p>That 337 is not merely asserted in prose. {@link #LAYOUT} declares every span the group
 * contains - the twelve-byte prefix, seventeen three-byte {@code FILLER}s, sixty-eight single-byte
 * attribute items and seventeen payload items - and {@link RecordLayout} refuses to be constructed
 * unless the spans are contiguous, non-overlapping and sum to exactly {@link #RECORD_LENGTH}. A
 * transcription slip therefore fails at class-initialisation time instead of silently shifting every
 * byte after it.
 *
 * <p>The counts were verified three independent ways against the sources: {@code app/bms/CORPT00.bms}
 * holds 42 {@code DFHMDF} entries of which exactly <strong>17 are name-labelled</strong>, the
 * copybook holds exactly <strong>17 {@code xxxI}</strong> items and exactly
 * <strong>17 {@code xxxO}</strong> items, and every BMS {@code LENGTH=} equals its {@code PIC X(n)}.
 * Only labelled fields become payload members; the 25 unlabelled ones are screen literals such as
 * {@code 'Tran:'} and {@code '(MM/DD/YYYY)'} that CICS never transmits.
 *
 * <h2>Payload versus metadata</h2>
 *
 * <p>Per the migration plan's symbolic-map rules, the {@code xxxC}/{@code xxxP}/{@code xxxH}/
 * {@code xxxV} quad is <strong>highlight metadata and never a JSON payload member</strong>. All four
 * are modelled here, because they occupy real bytes of the 337-byte image and because
 * {@code CORPT00C.cbl:448} writes one of them directly - {@code MOVE DFHGREEN TO ERRMSGC OF
 * CORPT0AO} - but they are reached through {@link JsonIgnore}-annotated accessors and are absent from
 * the serialised body.
 *
 * <p>The seventeen {@code xxxO} items carry no {@code JsonIgnore}, no masking and no redaction: they
 * are data, and suppressing any of them would change what the migrated screen emits.
 *
 * <h2>What this type deliberately does not do</h2>
 *
 * <ul>
 *   <li><strong>It does not decide highlighting.</strong> {@code app/cpy/CSSETATY.cpy} lines 18-27
 *       applies {@code DFHRED} to the colour item, and {@code '*'} to the output item, only when the
 *       field's flag is {@code NOT-OK} or {@code BLANK} <em>and</em> the program is in
 *       {@code CDEMO-PGM-REENTER}; the {@code '*'} only in the {@code BLANK} case. That decision
 *       lives in {@link FieldAttributeSetter}, which takes the re-enter state as an explicit boolean.
 *       This type only <em>applies</em> the resulting {@link FieldHighlight}, through
 *       {@link #applyHighlight(ScreenField, FieldHighlight)}. Highlighting is therefore reachable in
 *       re-enter state and unreachable on first entry, exactly as the copybook has it.</li>
 *   <li><strong>It holds no conversation state.</strong> {@code CORPT00C.cbl:549}
 *       {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)} is a COMMAREA-driven transfer, so
 *       {@link #getNextProgram()} is echoed from {@link NavigationContext#toProgram()} and the client
 *       issues the follow-up call. There is no {@code HttpSession}, no {@code SessionAttributes}, no
 *       server-side forward, no redirect chain and no static cache.</li>
 *   <li><strong>It performs no arithmetic and declares no floating-point type.</strong> All
 *       seventeen payload members are {@code String} at their declared width. {@code CORPT00C} treats
 *       them alphanumerically throughout - it normalises the date parts back into their own
 *       {@code X(2)}/{@code X(4)} items with {@code FUNCTION NUMVAL-C} (lines 305-327) and then tests
 *       {@code IS NOT NUMERIC} and {@code > '12'} as string comparisons (lines 329-377). The date
 *       intrinsics at line 229 and the two {@code CSUTLDTC} calls at lines 392 and 412 belong to the
 *       controller and to the date utility service, not here.</li>
 *   <li><strong>It reaches no dataset.</strong> {@code CORPT00C} accesses none. Its 80-byte JCL
 *       skeleton and the transient-data-queue write to {@code QUEUE('JOBS')} belong to the
 *       controller.</li>
 * </ul>
 *
 * <h2>Width discipline</h2>
 *
 * <p>Every setter routes through {@link FixedWidthCodec#movePicX(String, int)}, so a value wider than
 * its {@code PICTURE} keeps its <em>leading</em> characters and a shorter one is padded on the
 * <em>right</em> with spaces - COBOL's alphanumeric {@code MOVE}, in the one reviewable
 * implementation the module already owns. Nothing is trimmed on the way in or the way out, so a
 * 78-character space-padded {@code ERRMSGO} survives a JSON round trip byte for byte and compares
 * equal.
 *
 * <p>Instances are mutable and are not thread safe; a response object belongs to the request that
 * built it. There is no static mutable state: the only static fields are immutable constants, an
 * immutable {@link RecordLayout} and one immutable {@link FixedWidthCodec} that supplies the
 * {@code PICTURE} rules.
 *
 * @see FieldAttributeSetter
 * @see NavigationContext
 */
public final class ReportRequestResponse {

    // =================================================================================================
    // Provenance. Every one of these names is read off a source file rather than chosen here, so the
    // response can name its own screen without a caller having to retype a literal.
    // =================================================================================================

    /**
     * The CSD transaction that reaches this screen: {@code CR00}, defined at
     * {@code app/csd/CARDDEMO.CSD:409-410} as {@code TRANSACTION(CR00) ... PROGRAM(CORPT00C)}. This is
     * also the value {@code CORPT00C.cbl:38} holds in {@code WS-TRANID} and moves to
     * {@code TRNNAMEO} at line 615.
     */
    public static final String TRANSACTION_ID = "CR00";

    /**
     * The COBOL program this screen belongs to, {@code CORPT00C.cbl:37}
     * {@code WS-PGMNAME PIC X(08) VALUE 'CORPT00C'}, moved to {@code PGMNAMEO} at line 616.
     */
    public static final String PROGRAM_NAME = "CORPT00C";

    /**
     * The BMS mapset, {@code app/bms/CORPT00.bms:19} {@code CORPT00 DFHMSD}. Seven characters, which
     * is why {@link NavigationContext#lastMapset()} is {@code PIC X(7)} and not {@code X(8)}.
     */
    public static final String MAPSET_NAME = "CORPT00";

    /**
     * The BMS map, {@code app/bms/CORPT00.bms:26} {@code CORPT0A DFHMDI}. Seven characters: the
     * symbolic-map group names are formed by suffixing it, giving {@code CORPT0AI} and
     * {@code CORPT0AO}. Both {@code SEND MAP} sites, {@code CORPT00C.cbl:566} and line 574, name it.
     */
    public static final String MAP_NAME = "CORPT0A";

    /**
     * The outbound symbolic-map group this type projects, {@code app/cpy-bms/CORPT00.CPY:121}
     * {@code 01 CORPT0AO REDEFINES CORPT0AI}.
     */
    public static final String SYMBOLIC_MAP_GROUP = "CORPT0AO";

    /**
     * The program {@code CORPT00C.cbl:542-543} falls back to when {@code CDEMO-TO-PROGRAM} arrives as
     * {@code LOW-VALUES} or {@code SPACES}: {@code IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES / MOVE
     * 'COSGN00C' TO CDEMO-TO-PROGRAM}. Exposed as a constant so the controller reproduces the
     * fallback with the same literal rather than a retyped one.
     */
    public static final String DEFAULT_NEXT_PROGRAM = "COSGN00C";

    /**
     * The program {@code CORPT00C.cbl:190-191} transfers to on {@code DFHPF3}:
     * {@code WHEN DFHPF3 / MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM}.
     */
    public static final String PF3_NEXT_PROGRAM = "COMEN01C";

    // =================================================================================================
    // Byte geometry of 01 CORPT0AO. Every width is stated explicitly and every one is diffable against
    // app/cpy-bms/CORPT00.CPY. RECORD_LENGTH is computed from the parts rather than typed as 337, so
    // the arithmetic in the class documentation is executable rather than asserted.
    // =================================================================================================

    /** Screen fields with a {@code DFHMDF} label, and therefore payload members: seventeen. */
    public static final int SCREEN_FIELD_COUNT = 17;

    /**
     * The unnamed {@code 02 FILLER PIC X(12)} that opens both groups,
     * {@code app/cpy-bms/CORPT00.CPY:122}. It exists because the mapset declares
     * {@code TIOAPFX=YES} ({@code app/bms/CORPT00.bms:24}), which reserves twelve bytes ahead of the
     * first field for the terminal I/O area prefix. It carries no data and must still be emitted, or
     * every offset after it is wrong.
     */
    public static final int TIOAPFX_PREFIX_LENGTH = 12;

    /** The {@code 02 FILLER PICTURE X(3)} that opens each field's outbound prefix. */
    public static final int ATTRIBUTE_FILLER_LENGTH = 3;

    /** The {@code xxxC} colour item: {@code PICTURE X}, one byte. */
    public static final int COLOUR_ITEM_LENGTH = 1;

    /** The {@code xxxP} programmed-symbols item: {@code PICTURE X}, one byte. */
    public static final int PS_ITEM_LENGTH = 1;

    /** The {@code xxxH} highlight item: {@code PICTURE X}, one byte. */
    public static final int HILIGHT_ITEM_LENGTH = 1;

    /** The {@code xxxV} validation item: {@code PICTURE X}, one byte. */
    public static final int VALIDN_ITEM_LENGTH = 1;

    /**
     * The whole outbound prefix that precedes each {@code xxxO} item: seven bytes, being the
     * three-byte {@code FILLER} plus the four single-byte attribute items. The inbound view spends the
     * same seven bytes as {@code xxxL} (2) + {@code xxxF} (1) + {@code FILLER X(4)}, which is why the
     * two views alias exactly.
     */
    public static final int FIELD_PREFIX_LENGTH =
            ATTRIBUTE_FILLER_LENGTH + COLOUR_ITEM_LENGTH + PS_ITEM_LENGTH + HILIGHT_ITEM_LENGTH
                    + VALIDN_ITEM_LENGTH;

    /** Total bytes of all seventeen attribute prefixes: {@code 17 x 7 = 119}. */
    public static final int ATTRIBUTE_PREFIX_TOTAL = SCREEN_FIELD_COUNT * FIELD_PREFIX_LENGTH;

    /**
     * Total bytes of the seventeen {@code xxxO} payload items:
     * {@code 4+40+8+8+40+8+1+1+1+2+2+4+2+2+4+1+78 = 206}.
     *
     * <p>This is the one figure in the geometry stated as a literal rather than derived, and it is
     * cross-checked rather than trusted. {@link #RECORD_LENGTH} is built from it, while {@link #LAYOUT}
     * is built from the seventeen {@link ScreenField} widths; if the two ever disagree the span total
     * stops matching the record length and {@link RecordLayout} refuses to construct. A wrong value
     * here is therefore a class-initialisation failure, not a silent one.
     */
    public static final int PAYLOAD_WIDTH_TOTAL = 206;

    /**
     * The whole {@code 01 CORPT0AO} group image: {@code 12 + 119 + 206 = 337} bytes. Computed, not
     * typed, and independently enforced by {@link #LAYOUT}.
     */
    public static final int RECORD_LENGTH =
            TIOAPFX_PREFIX_LENGTH + ATTRIBUTE_PREFIX_TOTAL + PAYLOAD_WIDTH_TOTAL;

    /**
     * The one byte a BMS attribute item holds when the program has not set it. {@code CORPT00C.cbl:179}
     * does {@code MOVE LOW-VALUES TO CORPT0AO}, which sets every byte of the group - attribute items
     * included - to {@code X'00'}. That is the same value as {@link BmsAttributes#DFHDFCOL} and
     * {@link BmsAttributes#DFHDFHI}, so "untouched" and "device default" are one and the same byte
     * here, exactly as they are in CICS.
     */
    public static final byte ATTRIBUTE_UNSET = BmsAttributes.DFHDFCOL;

    /**
     * The {@code PICTURE} rules: alphanumeric {@code MOVE}, truncate-left-survivors and space-pad. A
     * {@link FixedWidthCodec} is immutable and holds only its {@link Charset}, so one shared instance
     * is thread safe and is a constant rather than static mutable state. Its charset is irrelevant to
     * width arithmetic - {@link FixedWidthCodec#movePicX(String, int)} is pure string work - and
     * rendering to bytes always takes the charset from the caller, never from this field and never
     * from the platform default.
     */
    private static final FixedWidthCodec PICTURE_RULES =
            new FixedWidthCodec(StandardCharsets.US_ASCII);

    /** One space, the {@code PIC X} pad character, used to build {@code SPACES} of a given width. */
    private static final String SPACE = " ";

    /** {@code X'00'} as a Java character, the {@code LOW-VALUES} fill. */
    private static final String LOW_VALUE = "\u0000";

    // =================================================================================================
    // The seventeen screen fields, in copybook declaration order, which is also BMS declaration order.
    //
    // This enum is the single transcription of the copybook table, and it is the reason the file has
    // one place to diff rather than seventeen. Each constant states its verbatim base name, its
    // PICTURE width and the absolute offset of the FILLER X(3) that opens its seven-byte prefix. The
    // offsets are absolute, per the plan's fixed-width rule, and they are proved by LAYOUT rather than
    // trusted: RecordLayout rejects any gap, any overlap and any total other than RECORD_LENGTH, so a
    // wrong offset cannot compile through.
    // =================================================================================================

    /**
     * One of the seventeen name-labelled {@code DFHMDF} fields of mapset {@code CORPT00}, carrying the
     * geometry of both its payload item and its four attribute items.
     *
     * <p>The constants are declared in the order {@code app/cpy-bms/CORPT00.CPY} declares them, which
     * is the order {@code app/bms/CORPT00.bms} declares them, which is the order their bytes appear in
     * the group image. Declaration order is therefore load-bearing and must not be tidied into
     * alphabetical order.
     *
     * <p>Every constant is named for the {@code DFHMDF} label, not for the {@code xxxO} item, because
     * the label is what the four suffixed items share. {@link #payloadItemName()} reconstructs the
     * copybook item name when one is needed for diagnostics or for a field-by-field diff.
     */
    public enum ScreenField {

        /** {@code TRNNAMEO PIC X(4)}, .CPY line 128; BMS {@code LENGTH=4 POS=(1,7) COLOR=BLUE}. */
        TRNNAME("TRNNAME", 4, TIOAPFX_PREFIX_LENGTH),

        /** {@code TITLE01O PIC X(40)}, .CPY line 134; BMS {@code LENGTH=40 POS=(1,21) COLOR=YELLOW}. */
        TITLE01("TITLE01", 40, 23),

        /**
         * {@code CURDATEO PIC X(8)}, .CPY line 140; BMS {@code LENGTH=8 POS=(1,71) COLOR=BLUE
         * INITIAL='mm/dd/yy'}.
         */
        CURDATE("CURDATE", 8, 70),

        /** {@code PGMNAMEO PIC X(8)}, .CPY line 146; BMS {@code LENGTH=8 POS=(2,7) COLOR=BLUE}. */
        PGMNAME("PGMNAME", 8, 85),

        /** {@code TITLE02O PIC X(40)}, .CPY line 152; BMS {@code LENGTH=40 POS=(2,21) COLOR=YELLOW}. */
        TITLE02("TITLE02", 40, 100),

        /**
         * {@code CURTIMEO PIC X(8)}, .CPY line 158; BMS {@code LENGTH=8 POS=(2,71) COLOR=BLUE
         * INITIAL='hh:mm:ss'}.
         */
        CURTIME("CURTIME", 8, 147),

        /**
         * {@code MONTHLYO PIC X(1)}, .CPY line 164; BMS {@code LENGTH=1 POS=(7,10) COLOR=GREEN
         * HILIGHT=UNDERLINE ATTRB=(FSET,IC,NORM,UNPROT)}. The only field carrying {@code IC}, so it is
         * where the cursor lands - which is why {@code CORPT00C} moves {@code -1} to {@code MONTHLYL}
         * at lines 180, 192, 441, 453, 533, 635 and 646.
         */
        MONTHLY("MONTHLY", 1, 162),

        /**
         * {@code YEARLYO PIC X(1)}, .CPY line 170; BMS {@code LENGTH=1 POS=(9,10) COLOR=GREEN
         * HILIGHT=UNDERLINE}.
         */
        YEARLY("YEARLY", 1, 170),

        /**
         * {@code CUSTOMO PIC X(1)}, .CPY line 176; BMS {@code LENGTH=1 POS=(11,10) COLOR=GREEN
         * HILIGHT=UNDERLINE}.
         */
        CUSTOM("CUSTOM", 1, 178),

        /**
         * {@code SDTMMO PIC X(2)}, .CPY line 182; BMS {@code LENGTH=2 POS=(13,29) COLOR=GREEN
         * ATTRB=(FSET,NORM,NUM,UNPROT)}. Start-date month.
         */
        SDTMM("SDTMM", 2, 186),

        /** {@code SDTDDO PIC X(2)}, .CPY line 188; BMS {@code LENGTH=2 POS=(13,34)}. Start-date day. */
        SDTDD("SDTDD", 2, 195),

        /**
         * {@code SDTYYYYO PIC X(4)}, .CPY line 194; BMS {@code LENGTH=4 POS=(13,39)}. Start-date year.
         */
        SDTYYYY("SDTYYYY", 4, 204),

        /** {@code EDTMMO PIC X(2)}, .CPY line 200; BMS {@code LENGTH=2 POS=(14,29)}. End-date month. */
        EDTMM("EDTMM", 2, 215),

        /** {@code EDTDDO PIC X(2)}, .CPY line 206; BMS {@code LENGTH=2 POS=(14,34)}. End-date day. */
        EDTDD("EDTDD", 2, 224),

        /** {@code EDTYYYYO PIC X(4)}, .CPY line 212; BMS {@code LENGTH=4 POS=(14,39)}. End-date year. */
        EDTYYYY("EDTYYYY", 4, 233),

        /**
         * {@code CONFIRMO PIC X(1)}, .CPY line 218; BMS {@code LENGTH=1 POS=(19,66) COLOR=GREEN
         * HILIGHT=UNDERLINE}. The {@code (Y/N)} answer {@code CORPT00C} tests at lines 478 and 480.
         */
        CONFIRM("CONFIRM", 1, 244),

        /**
         * {@code ERRMSGO PIC X(78)}, .CPY line 224; BMS {@code LENGTH=78 POS=(23,1)
         * ATTRB=(ASKIP,BRT,FSET) COLOR=RED}. The error line. {@code CORPT00C.cbl:560} fills it with
         * {@code WS-MESSAGE}, which is {@code PIC X(80)} (line 39), so that {@code MOVE} discards the
         * two trailing bytes - reproduced faithfully because every setter here truncates on the right.
         */
        ERRMSG("ERRMSG", 78, 252);

        /** The {@code DFHMDF} label, verbatim, without the {@code I}/{@code O}/{@code C} suffix. */
        private final String baseName;

        /** The {@code xxxO} {@code PICTURE} width in bytes. */
        private final int payloadLength;

        /** Absolute 0-based offset of the {@code FILLER PICTURE X(3)} that opens the prefix. */
        private final int attributePrefixOffset;

        private final FieldSpan attributeFillerSpan;
        private final FieldSpan colourSpan;
        private final FieldSpan psSpan;
        private final FieldSpan hilightSpan;
        private final FieldSpan validnSpan;
        private final FieldSpan payloadSpan;

        ScreenField(String baseName, int payloadLength, int attributePrefixOffset) {
            this.baseName = baseName;
            this.payloadLength = payloadLength;
            this.attributePrefixOffset = attributePrefixOffset;

            // The prefix, laid out in the copybook's own order. Each item's offset is derived from the
            // prefix offset rather than retyped, so the seven-byte stride is stated once.
            int cursor = attributePrefixOffset;
            this.attributeFillerSpan = FieldSpan.filler(cursor, ATTRIBUTE_FILLER_LENGTH);
            cursor += ATTRIBUTE_FILLER_LENGTH;
            this.colourSpan = FieldSpan.alphanumeric(baseName + "C", cursor, COLOUR_ITEM_LENGTH);
            cursor += COLOUR_ITEM_LENGTH;
            this.psSpan = FieldSpan.alphanumeric(baseName + "P", cursor, PS_ITEM_LENGTH);
            cursor += PS_ITEM_LENGTH;
            this.hilightSpan = FieldSpan.alphanumeric(baseName + "H", cursor, HILIGHT_ITEM_LENGTH);
            cursor += HILIGHT_ITEM_LENGTH;
            this.validnSpan = FieldSpan.alphanumeric(baseName + "V", cursor, VALIDN_ITEM_LENGTH);
            cursor += VALIDN_ITEM_LENGTH;
            this.payloadSpan = FieldSpan.alphanumeric(baseName + "O", cursor, payloadLength);
        }

        /**
         * The {@code DFHMDF} label this field is known by, for example {@code "ERRMSG"}. This is the
         * {@code (SCRNVAR2)} token {@code app/cpy/CSSETATY.cpy} substitutes, so it is what
         * {@link FieldAttributeSetter} should be told the field is called.
         *
         * @return the label, verbatim and never {@code null}
         */
        public String baseName() {
            return baseName;
        }

        /**
         * The copybook name of the payload item, for example {@code "ERRMSGO"}. Reconstructed rather
         * than stored, because the suffix rule is the copybook's and stating it once keeps the two in
         * step.
         *
         * @return the {@code xxxO} item name
         */
        public String payloadItemName() {
            return baseName + FieldAttributeSetter.OUTPUT_ITEM_SUFFIX;
        }

        /**
         * The copybook name of the colour item, for example {@code "ERRMSGC"} - the item
         * {@code CORPT00C.cbl:448} writes {@code DFHGREEN} into and the item {@code CSSETATY} writes
         * {@code DFHRED} into.
         *
         * @return the {@code xxxC} item name
         */
        public String colourItemName() {
            return baseName + FieldAttributeSetter.COLOUR_ITEM_SUFFIX;
        }

        /**
         * The declared {@code PICTURE} width of the payload item, in bytes.
         *
         * @return the width, always at least 1
         */
        public int payloadLength() {
            return payloadLength;
        }

        /**
         * Absolute 0-based offset of the payload item within the 337-byte group image.
         *
         * @return the offset
         */
        public int payloadOffset() {
            return payloadSpan.offset();
        }

        /**
         * Absolute 0-based offset of the seven-byte attribute prefix within the group image.
         *
         * @return the offset
         */
        public int attributePrefixOffset() {
            return attributePrefixOffset;
        }

        /**
         * The payload item's span, for reading and writing the field by absolute offset.
         *
         * @return the {@code xxxO} span
         */
        public FieldSpan payloadSpan() {
            return payloadSpan;
        }

        /**
         * The colour item's span - the {@code (SCRNVAR2)C} target of {@code CSSETATY} line 22.
         *
         * @return the {@code xxxC} span
         */
        public FieldSpan colourSpan() {
            return colourSpan;
        }

        /**
         * The programmed-symbols item's span.
         *
         * @return the {@code xxxP} span
         */
        public FieldSpan psSpan() {
            return psSpan;
        }

        /**
         * The highlight item's span. {@code app/bms/CORPT00.bms} declares
         * {@code HILIGHT=UNDERLINE} on the eleven unprotected fields, which is
         * {@link BmsAttributes#DFHUNDLN}.
         *
         * @return the {@code xxxH} span
         */
        public FieldSpan hilightSpan() {
            return hilightSpan;
        }

        /**
         * The validation item's span.
         *
         * @return the {@code xxxV} span
         */
        public FieldSpan validnSpan() {
            return validnSpan;
        }

        /**
         * The unnamed three-byte {@code FILLER} that opens this field's prefix. Reserved storage that
         * carries no value and must still be emitted.
         *
         * @return the {@code FILLER} span
         */
        public FieldSpan attributeFillerSpan() {
            return attributeFillerSpan;
        }

        /**
         * All six spans this field owns, in copybook order: {@code FILLER}, {@code xxxC},
         * {@code xxxP}, {@code xxxH}, {@code xxxV}, {@code xxxO}.
         *
         * @return an immutable list of six spans
         */
        public List<FieldSpan> spans() {
            return List.of(attributeFillerSpan, colourSpan, psSpan, hilightSpan, validnSpan,
                    payloadSpan);
        }
    }

    /**
     * The unnamed twelve-byte {@code FILLER} at {@code app/cpy-bms/CORPT00.CPY:122}, reserved by
     * {@code TIOAPFX=YES}.
     */
    public static final FieldSpan TIOAPFX_PREFIX_SPAN = FieldSpan.filler(0, TIOAPFX_PREFIX_LENGTH);

    /**
     * Every span of {@code 01 CORPT0AO}, in copybook order: the twelve-byte {@code TIOAPFX} prefix,
     * then for each of the seventeen fields its three-byte {@code FILLER}, its {@code xxxC},
     * {@code xxxP}, {@code xxxH} and {@code xxxV} attribute items and its {@code xxxO} payload item.
     * That is {@code 1 + 17 x 6 = 103} spans.
     *
     * <p>This constant is the executable form of the byte table in the class documentation.
     * {@link RecordLayout} verifies on construction that the spans are contiguous from byte 0, that
     * none overlaps another, that no non-{@code FILLER} name repeats and that the total is exactly
     * {@link #RECORD_LENGTH}. A mistyped offset or a dropped {@code FILLER} therefore raises an
     * {@link IllegalArgumentException} the first time this class is touched, rather than corrupting
     * every field after the error.
     */
    public static final RecordLayout LAYOUT = buildLayout();

    /**
     * Assembles {@link #LAYOUT} from {@link ScreenField}, so the span list cannot drift out of step
     * with the field table it is built from.
     *
     * @return the verified 337-byte layout
     */
    private static RecordLayout buildLayout() {
        List<FieldSpan> spans = new ArrayList<>(1 + SCREEN_FIELD_COUNT * 6);
        spans.add(TIOAPFX_PREFIX_SPAN);
        for (ScreenField field : ScreenField.values()) {
            spans.addAll(field.spans());
        }
        return new RecordLayout(RECORD_LENGTH, spans);
    }

    // =================================================================================================
    // The four attribute items of one screen field, modelled as metadata and never as payload.
    // =================================================================================================

    /**
     * The {@code xxxC}/{@code xxxP}/{@code xxxH}/{@code xxxV} quad of one screen field: the colour,
     * programmed-symbols, highlight and validation bytes BMS transmits ahead of the field's data.
     *
     * <p>Each is a single {@code PICTURE X} byte, held here as a Java {@code byte} rather than as a
     * character. That is deliberate: a BMS attribute value is a raw code point, and several of them
     * collide with printable EBCDIC characters - {@link BmsAttributes#DFHRED} is {@code X'F2'}, which
     * is also EBCDIC {@code '2'}. Passing such a value through a charset conversion would silently
     * change it, so these bytes are written to and read from the record image untranslated.
     *
     * <p>The type is immutable; {@link ReportRequestResponse} replaces the whole quad when one item
     * changes. That keeps the response's metadata free of shared mutable substructure while leaving
     * each item individually settable.
     *
     * @param colour  the {@code xxxC} item - the {@code CSSETATY} and {@code CORPT00C.cbl:448} target
     * @param ps      the {@code xxxP} programmed-symbols item
     * @param hilight the {@code xxxH} highlight item, for example {@link BmsAttributes#DFHUNDLN}
     * @param validn  the {@code xxxV} validation item
     */
    public record FieldAttributes(byte colour, byte ps, byte hilight, byte validn) {

        /**
         * The quad as {@code MOVE LOW-VALUES TO CORPT0AO} ({@code CORPT00C.cbl:179}) leaves it: all
         * four bytes {@code X'00'}, which CICS reads as "use the attribute the map already declares".
         */
        public static final FieldAttributes UNSET =
                new FieldAttributes(ATTRIBUTE_UNSET, ATTRIBUTE_UNSET, ATTRIBUTE_UNSET,
                        ATTRIBUTE_UNSET);

        /**
         * This quad with a different colour item, leaving the other three untouched. This is the
         * single-item replacement {@code MOVE DFHRED TO (SCRNVAR2)C} and
         * {@code MOVE DFHGREEN TO ERRMSGC} both need.
         *
         * @param value the new {@code xxxC} byte
         * @return a new quad
         */
        public FieldAttributes withColour(byte value) {
            return new FieldAttributes(value, ps, hilight, validn);
        }

        /**
         * This quad with a different programmed-symbols item.
         *
         * @param value the new {@code xxxP} byte
         * @return a new quad
         */
        public FieldAttributes withPs(byte value) {
            return new FieldAttributes(colour, value, hilight, validn);
        }

        /**
         * This quad with a different highlight item.
         *
         * @param value the new {@code xxxH} byte
         * @return a new quad
         */
        public FieldAttributes withHilight(byte value) {
            return new FieldAttributes(colour, ps, value, validn);
        }

        /**
         * This quad with a different validation item.
         *
         * @param value the new {@code xxxV} byte
         * @return a new quad
         */
        public FieldAttributes withValidn(byte value) {
            return new FieldAttributes(colour, ps, hilight, value);
        }

        /**
         * Whether all four items are still {@link #ATTRIBUTE_UNSET}, that is whether the program has
         * left this field's attributes as {@code LOW-VALUES}.
         *
         * @return {@code true} when nothing has been set
         */
        public boolean allUnset() {
            return colour == ATTRIBUTE_UNSET && ps == ATTRIBUTE_UNSET && hilight == ATTRIBUTE_UNSET
                    && validn == ATTRIBUTE_UNSET;
        }

        /**
         * A diagnostic rendering that names each byte by its CICS mnemonic where one exists, so a
         * failing comparison reads as {@code DFHRED} rather than as {@code -14}.
         *
         * @return a human-readable description; never {@code null}
         */
        public String describe() {
            return "C=" + BmsAttributes.colourMnemonic(colour)
                    + " P=" + BmsAttributes.toHex(ps)
                    + " H=" + BmsAttributes.highlightMnemonic(hilight)
                    + " V=" + BmsAttributes.toHex(validn);
        }
    }

    // =================================================================================================
    // Instance state.
    //
    // The seventeen xxxO items are the JSON payload. The attribute quads are metadata and are excluded
    // from it. The navigation trio and the commarea echo are what replace CICS conversation state, and
    // they are payload because a stateless server has nowhere else to put them.
    // =================================================================================================

    /** {@code TRNNAMEO PIC X(4)}, .CPY line 128. */
    private String trnnameo;

    /** {@code TITLE01O PIC X(40)}, .CPY line 134. */
    private String title01o;

    /** {@code CURDATEO PIC X(8)}, .CPY line 140. */
    private String curdateo;

    /** {@code PGMNAMEO PIC X(8)}, .CPY line 146. */
    private String pgmnameo;

    /** {@code TITLE02O PIC X(40)}, .CPY line 152. */
    private String title02o;

    /** {@code CURTIMEO PIC X(8)}, .CPY line 158. */
    private String curtimeo;

    /** {@code MONTHLYO PIC X(1)}, .CPY line 164. */
    private String monthlyo;

    /** {@code YEARLYO PIC X(1)}, .CPY line 170. */
    private String yearlyo;

    /** {@code CUSTOMO PIC X(1)}, .CPY line 176. */
    private String customo;

    /** {@code SDTMMO PIC X(2)}, .CPY line 182. */
    private String sdtmmo;

    /** {@code SDTDDO PIC X(2)}, .CPY line 188. */
    private String sdtddo;

    /** {@code SDTYYYYO PIC X(4)}, .CPY line 194. */
    private String sdtyyyyo;

    /** {@code EDTMMO PIC X(2)}, .CPY line 200. */
    private String edtmmo;

    /** {@code EDTDDO PIC X(2)}, .CPY line 206. */
    private String edtddo;

    /** {@code EDTYYYYO PIC X(4)}, .CPY line 212. */
    private String edtyyyyo;

    /** {@code CONFIRMO PIC X(1)}, .CPY line 218. */
    private String confirmo;

    /** {@code ERRMSGO PIC X(78)}, .CPY line 224. */
    private String errmsgo;

    /**
     * The four attribute bytes of each of the seventeen fields. An {@link EnumMap} keyed by
     * {@link ScreenField} rather than sixty-eight separate fields, because the copybook itself
     * declares the quad as one repeating shape and one table diffs against it far more readably than
     * sixty-eight scattered declarations would.
     *
     * <p>Never {@code null} and always fully populated - every key is present from construction - so
     * lookups need no absent-value branch.
     */
    private final EnumMap<ScreenField, FieldAttributes> attributes =
            new EnumMap<>(ScreenField.class);

    /**
     * Where the client should go next, replacing {@code CORPT00C.cbl:549}
     * {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)}. Eight bytes, the width of
     * {@code CDEMO-TO-PROGRAM PIC X(08)} at {@code app/cpy/COCOM01Y.cpy:24}.
     */
    private String nextProgram;

    /**
     * The mapset the next screen belongs to. Seven bytes, the width of
     * {@code CDEMO-LAST-MAPSET PIC X(7)} at {@code app/cpy/COCOM01Y.cpy:44} - not eight, because a BMS
     * mapset name is seven characters.
     */
    private String nextMapset;

    /**
     * The map the next screen uses. Seven bytes, the width of {@code CDEMO-LAST-MAP PIC X(7)} at
     * {@code app/cpy/COCOM01Y.cpy:43}.
     */
    private String nextMap;

    /**
     * The commarea, echoed back so the client can return it on the next call. Exactly
     * {@link NavigationContext#COMMAREA_LENGTH} bytes.
     *
     * <p>{@code CORPT00C} copies {@code COCOM01Y} at line 138 and {@code CORPT00} at line 140 with
     * nothing between them, so it declares <strong>no</strong> commarea extension: its commarea is the
     * plain 160-byte {@code CARDDEMO-COMMAREA}, unlike the {@code COTRN*} programs which add a group
     * and reach 218. There is consequently no pagination cursor on this screen and none is invented
     * here, and {@link NavigationContext} is echoed at its shared width rather than widened.
     */
    private NavigationContext navigationContext;

    // =================================================================================================
    // Construction.
    // =================================================================================================

    /**
     * A response with every field at its declared width, filled with spaces, every attribute quad
     * {@link FieldAttributes#UNSET}, and an empty commarea.
     *
     * <p>Spaces rather than {@code LOW-VALUES} is the resting state chosen for a fresh object, because
     * a response is a transport object that must serialise cleanly, and {@code X'00'} inside a JSON
     * string is legal but hostile. The COBOL group initialiser is not lost: it is available verbatim
     * as {@link #moveLowValuesToMapGroup()}, which is what {@code CORPT00C.cbl:179} performs.
     */
    public ReportRequestResponse() {
        for (ScreenField field : ScreenField.values()) {
            attributes.put(field, FieldAttributes.UNSET);
        }
        moveSpacesToAllFields();

        // No transfer has been decided yet, so the program target starts blank; the map and mapset
        // start as this screen's own, which is what the pseudo-conversational re-display path implies
        // and what CORPT00C.cbl:566 and 574 name on every SEND.
        this.nextProgram = spaces(NavigationContext.TO_PROGRAM_LENGTH);
        this.nextMapset = MAPSET_NAME;
        this.nextMap = MAP_NAME;
        this.navigationContext = NavigationContext.empty();
    }

    /**
     * A deep-enough copy of another response: every payload string, every attribute quad and the
     * navigation state are carried across. {@link NavigationContext} and {@link FieldAttributes} are
     * both immutable, so sharing their references is safe and copying them would achieve nothing.
     *
     * @param other the response to copy; never {@code null}
     * @throws NullPointerException if {@code other} is {@code null}
     */
    public ReportRequestResponse(ReportRequestResponse other) {
        Objects.requireNonNull(other, "A response is required to copy from");
        this.trnnameo = other.trnnameo;
        this.title01o = other.title01o;
        this.curdateo = other.curdateo;
        this.pgmnameo = other.pgmnameo;
        this.title02o = other.title02o;
        this.curtimeo = other.curtimeo;
        this.monthlyo = other.monthlyo;
        this.yearlyo = other.yearlyo;
        this.customo = other.customo;
        this.sdtmmo = other.sdtmmo;
        this.sdtddo = other.sdtddo;
        this.sdtyyyyo = other.sdtyyyyo;
        this.edtmmo = other.edtmmo;
        this.edtddo = other.edtddo;
        this.edtyyyyo = other.edtyyyyo;
        this.confirmo = other.confirmo;
        this.errmsgo = other.errmsgo;
        this.attributes.putAll(other.attributes);
        this.nextProgram = other.nextProgram;
        this.nextMapset = other.nextMapset;
        this.nextMap = other.nextMap;
        this.navigationContext = other.navigationContext;
    }

    // =================================================================================================
    // Figurative constants. COBOL has SPACES and LOW-VALUES; Java has neither, so they are named here
    // rather than spelled out as string literals at every call site.
    // =================================================================================================

    /**
     * {@code SPACES} at a given width - the {@code PIC X} fill character repeated.
     *
     * @param length how many spaces; must be at least 1
     * @return a string of exactly {@code length} spaces
     * @throws IllegalArgumentException if {@code length} is less than 1
     */
    public static String spaces(int length) {
        requireDeclaredWidth(length, "SPACES");
        return SPACE.repeat(length);
    }

    /**
     * {@code LOW-VALUES} at a given width - {@code X'00'} repeated. This is what
     * {@code CORPT00C.cbl:179} fills the whole group with on first entry, and what lines 213, 239, 256
     * and 259-299 compare the inbound items against.
     *
     * @param length how many bytes; must be at least 1
     * @return a string of exactly {@code length} {@code X'00'} characters
     * @throws IllegalArgumentException if {@code length} is less than 1
     */
    public static String lowValues(int length) {
        requireDeclaredWidth(length, "LOW-VALUES");
        return LOW_VALUE.repeat(length);
    }

    // =================================================================================================
    // Group-level moves, each named after the COBOL statement it reproduces.
    // =================================================================================================

    /**
     * Fills all seventeen payload items with {@code SPACES} at their declared widths, leaving the
     * attribute quads alone. This is the resting state a fresh response starts in.
     */
    public void moveSpacesToAllFields() {
        for (ScreenField field : ScreenField.values()) {
            setPayloadValue(field, spaces(field.payloadLength()));
        }
    }

    /**
     * Reproduces {@code CORPT00C.cbl:179} {@code MOVE LOW-VALUES TO CORPT0AO}.
     *
     * <p>A group {@code MOVE} of a figurative constant reaches <strong>every</strong> byte of the
     * group, so this clears the seventeen payload items <em>and</em> resets all sixty-eight attribute
     * items to {@code X'00'}. Clearing only the payload would be the commonest way to get this wrong:
     * the program relies on the group move to discard a {@code DFHGREEN} or {@code DFHRED} left over
     * from a previous send.
     */
    public void moveLowValuesToMapGroup() {
        for (ScreenField field : ScreenField.values()) {
            setPayloadValue(field, lowValues(field.payloadLength()));
            attributes.put(field, FieldAttributes.UNSET);
        }
    }

    /**
     * Reproduces the {@code INITIALIZE-ALL-FIELDS} paragraph, {@code CORPT00C.cbl:636-645}.
     *
     * <p>The paragraph names ten items - {@code MONTHLYI}, {@code YEARLYI}, {@code CUSTOMI},
     * {@code SDTMMI}, {@code SDTDDI}, {@code SDTYYYYI}, {@code EDTMMI}, {@code EDTDDI},
     * {@code EDTYYYYI} and {@code CONFIRMI} - and deliberately leaves the six header fields and the
     * error line untouched. It names the {@code I} items, but those alias the {@code O} items byte for
     * byte, so clearing them here is the same storage operation.
     *
     * <p>COBOL's {@code INITIALIZE} sets an alphanumeric item to {@code SPACES}, not to
     * {@code LOW-VALUES}; the two are different bytes and the distinction is preserved.
     */
    public void initializeAllFields() {
        setPayloadValue(ScreenField.MONTHLY, spaces(ScreenField.MONTHLY.payloadLength()));
        setPayloadValue(ScreenField.YEARLY, spaces(ScreenField.YEARLY.payloadLength()));
        setPayloadValue(ScreenField.CUSTOM, spaces(ScreenField.CUSTOM.payloadLength()));
        setPayloadValue(ScreenField.SDTMM, spaces(ScreenField.SDTMM.payloadLength()));
        setPayloadValue(ScreenField.SDTDD, spaces(ScreenField.SDTDD.payloadLength()));
        setPayloadValue(ScreenField.SDTYYYY, spaces(ScreenField.SDTYYYY.payloadLength()));
        setPayloadValue(ScreenField.EDTMM, spaces(ScreenField.EDTMM.payloadLength()));
        setPayloadValue(ScreenField.EDTDD, spaces(ScreenField.EDTDD.payloadLength()));
        setPayloadValue(ScreenField.EDTYYYY, spaces(ScreenField.EDTYYYY.payloadLength()));
        setPayloadValue(ScreenField.CONFIRM, spaces(ScreenField.CONFIRM.payloadLength()));
    }

    // =================================================================================================
    // Access by ScreenField. These carry a parameter, so they are not bean properties and Jackson does
    // not treat them as such; the annotation states that rather than relying on it.
    // =================================================================================================

    /**
     * Reads one payload item by field, exactly as stored: never trimmed, never normalised.
     *
     * @param field which screen field; never {@code null}
     * @return the item at its declared width
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public String payloadValue(ScreenField field) {
        requireField(field);
        return switch (field) {
            case TRNNAME -> trnnameo;
            case TITLE01 -> title01o;
            case CURDATE -> curdateo;
            case PGMNAME -> pgmnameo;
            case TITLE02 -> title02o;
            case CURTIME -> curtimeo;
            case MONTHLY -> monthlyo;
            case YEARLY -> yearlyo;
            case CUSTOM -> customo;
            case SDTMM -> sdtmmo;
            case SDTDD -> sdtddo;
            case SDTYYYY -> sdtyyyyo;
            case EDTMM -> edtmmo;
            case EDTDD -> edtddo;
            case EDTYYYY -> edtyyyyo;
            case CONFIRM -> confirmo;
            case ERRMSG -> errmsgo;
        };
    }

    /**
     * Writes one payload item by field, applying the alphanumeric {@code MOVE} rules for that field's
     * declared width.
     *
     * @param field which screen field; never {@code null}
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code field} or {@code value} is {@code null}
     */
    @JsonIgnore
    public void setPayloadValue(ScreenField field, String value) {
        requireField(field);
        String fitted = movePicX(value, field.payloadLength(), field.payloadItemName());
        switch (field) {
            case TRNNAME -> trnnameo = fitted;
            case TITLE01 -> title01o = fitted;
            case CURDATE -> curdateo = fitted;
            case PGMNAME -> pgmnameo = fitted;
            case TITLE02 -> title02o = fitted;
            case CURTIME -> curtimeo = fitted;
            case MONTHLY -> monthlyo = fitted;
            case YEARLY -> yearlyo = fitted;
            case CUSTOM -> customo = fitted;
            case SDTMM -> sdtmmo = fitted;
            case SDTDD -> sdtddo = fitted;
            case SDTYYYY -> sdtyyyyo = fitted;
            case EDTMM -> edtmmo = fitted;
            case EDTDD -> edtddo = fitted;
            case EDTYYYY -> edtyyyyo = fitted;
            case CONFIRM -> confirmo = fitted;
            case ERRMSG -> errmsgo = fitted;
        }
    }

    // =================================================================================================
    // Attribute metadata. Every accessor in this section is @JsonIgnore: the xxxC/xxxP/xxxH/xxxV quad
    // occupies real bytes of the group image but is never a member of the serialised body.
    // =================================================================================================

    /**
     * The whole attribute quad of one field.
     *
     * @param field which screen field; never {@code null}
     * @return the quad; never {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public FieldAttributes attributesOf(ScreenField field) {
        requireField(field);
        return attributes.get(field);
    }

    /**
     * Replaces the whole attribute quad of one field.
     *
     * @param field    which screen field; never {@code null}
     * @param quad     the replacement quad; never {@code null}
     * @throws NullPointerException if {@code field} or {@code quad} is {@code null}
     */
    @JsonIgnore
    public void setAttributes(ScreenField field, FieldAttributes quad) {
        requireField(field);
        Objects.requireNonNull(quad, "An attribute quad is required for "
                + field.baseName() + "; pass FieldAttributes.UNSET to clear it");
        attributes.put(field, quad);
    }

    /**
     * The {@code xxxC} colour byte of one field - the item {@code app/cpy/CSSETATY.cpy:21-22} moves
     * {@link BmsAttributes#DFHRED} into and {@code CORPT00C.cbl:448} moves
     * {@link BmsAttributes#DFHGREEN} into.
     *
     * @param field which screen field; never {@code null}
     * @return the colour byte, {@link #ATTRIBUTE_UNSET} when never set
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public byte colourAttribute(ScreenField field) {
        return attributesOf(field).colour();
    }

    /**
     * Sets the {@code xxxC} colour byte of one field, leaving the other three items of the quad as
     * they are.
     *
     * @param field  which screen field; never {@code null}
     * @param colour the colour byte, for example {@link BmsAttributes#DFHRED}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public void setColourAttribute(ScreenField field, byte colour) {
        setAttributes(field, attributesOf(field).withColour(colour));
    }

    /**
     * The {@code xxxP} programmed-symbols byte of one field.
     *
     * @param field which screen field; never {@code null}
     * @return the byte, {@link #ATTRIBUTE_UNSET} when never set
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public byte psAttribute(ScreenField field) {
        return attributesOf(field).ps();
    }

    /**
     * Sets the {@code xxxP} programmed-symbols byte of one field.
     *
     * @param field which screen field; never {@code null}
     * @param ps    the byte to set
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public void setPsAttribute(ScreenField field, byte ps) {
        setAttributes(field, attributesOf(field).withPs(ps));
    }

    /**
     * The {@code xxxH} highlight byte of one field. The eleven unprotected fields of this mapset are
     * declared {@code HILIGHT=UNDERLINE}, which is {@link BmsAttributes#DFHUNDLN}.
     *
     * @param field which screen field; never {@code null}
     * @return the byte, {@link #ATTRIBUTE_UNSET} when never set
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public byte hilightAttribute(ScreenField field) {
        return attributesOf(field).hilight();
    }

    /**
     * Sets the {@code xxxH} highlight byte of one field.
     *
     * @param field   which screen field; never {@code null}
     * @param hilight the byte to set
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public void setHilightAttribute(ScreenField field, byte hilight) {
        setAttributes(field, attributesOf(field).withHilight(hilight));
    }

    /**
     * The {@code xxxV} validation byte of one field.
     *
     * @param field which screen field; never {@code null}
     * @return the byte, {@link #ATTRIBUTE_UNSET} when never set
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public byte validnAttribute(ScreenField field) {
        return attributesOf(field).validn();
    }

    /**
     * Sets the {@code xxxV} validation byte of one field.
     *
     * @param field  which screen field; never {@code null}
     * @param validn the byte to set
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public void setValidnAttribute(ScreenField field, byte validn) {
        setAttributes(field, attributesOf(field).withValidn(validn));
    }

    /**
     * The {@code ERRMSGC} byte, named because it is the one attribute item {@code CORPT00C} writes
     * directly.
     *
     * @return the colour byte of the error line
     */
    @JsonIgnore
    public byte getErrmsgc() {
        return colourAttribute(ScreenField.ERRMSG);
    }

    /**
     * Sets the {@code ERRMSGC} byte.
     *
     * @param colour the colour byte to set
     */
    @JsonIgnore
    public void setErrmsgc(byte colour) {
        setColourAttribute(ScreenField.ERRMSG, colour);
    }

    /**
     * Reproduces {@code CORPT00C.cbl:448} {@code MOVE DFHGREEN TO ERRMSGC OF CORPT0AO}, which the
     * program performs on the success path - after {@code INITIALIZE-ALL-FIELDS} and before composing
     * the {@code ' report submitted for printing ...'} text - so that a confirmation reads green on a
     * line the mapset declares {@code COLOR=RED}.
     */
    public void moveDfhgreenToErrmsgc() {
        setErrmsgc(BmsAttributes.DFHGREEN);
    }

    /**
     * Applies a highlight decision taken by {@link FieldAttributeSetter} to one field.
     *
     * <p>This method deliberately contains <strong>no</strong> part of the decision. Whether a field is
     * highlighted at all depends on the field's validation flags and on
     * {@code CDEMO-PGM-REENTER}, and {@code app/cpy/CSSETATY.cpy:18-27} is reproduced once, in
     * {@link FieldAttributeSetter#resolve(FieldAttributeSetter.FieldValidationState, boolean, String,
     * String)}. What happens here is only the pair of {@code MOVE}s the copybook performs when its
     * outer {@code IF} is taken:
     *
     * <pre>{@code
     *     MOVE DFHRED TO (SCRNVAR2)C OF (MAPNAME3)O
     *     IF  FLG-(TESTVAR1)-BLANK
     *         MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O
     *     END-IF
     * }</pre>
     *
     * <p>Because {@code FieldAttributeSetter} yields an untouched highlight whenever the program is on
     * first entry, a caller that honours the returned value cannot highlight in {@code ENTER} state and
     * will highlight in {@code REENTER} state - which is the whole of the behaviour under test.
     *
     * <p>When the highlight carries a field prefix or a map name, they are checked against this
     * response rather than ignored, so a highlight resolved for the wrong field or the wrong screen is
     * rejected instead of silently landing on the wrong bytes.
     *
     * @param field     the field to highlight; never {@code null}
     * @param highlight the decision to apply; never {@code null}
     * @return {@code true} if anything was written, {@code false} if the decision was to leave the
     *         field alone
     * @throws NullPointerException     if {@code field} or {@code highlight} is {@code null}
     * @throws IllegalArgumentException if the highlight names a different field or a different map
     */
    public boolean applyHighlight(ScreenField field, FieldHighlight highlight) {
        requireField(field);
        Objects.requireNonNull(highlight, "A highlight decision is required; call "
                + "FieldAttributeSetter.resolve(...) and pass its result, or "
                + "FieldHighlight.none(...) to leave the field alone");

        if (!highlight.screenFieldPrefix().isEmpty()
                && !highlight.screenFieldPrefix().equals(field.baseName())) {
            throw new IllegalArgumentException("Highlight was resolved for field '"
                    + highlight.screenFieldPrefix() + "' but is being applied to '" + field.baseName()
                    + "'; CSSETATY substitutes one (SCRNVAR2) per expansion, so the two must agree");
        }
        if (!highlight.mapName().isEmpty() && !highlight.mapName().equals(MAP_NAME)) {
            throw new IllegalArgumentException("Highlight was resolved for map '"
                    + highlight.mapName() + "' but this response projects " + SYMBOLIC_MAP_GROUP
                    + "; expected map name '" + MAP_NAME + "'");
        }

        // CSSETATY.cpy:27 END-IF - the outer test failed, so neither item is touched and the field
        // keeps the colour and the content the program had already given it.
        if (highlight.untouched()) {
            return false;
        }

        // CSSETATY.cpy:21-22, applied unconditionally once the outer test passes.
        setColourAttribute(field, highlight.colourItemValue());

        // CSSETATY.cpy:23-26, the inner refinement: the '*' only in the BLANK case.
        if (highlight.outputItemAssigned()) {
            setPayloadValue(field, highlight.outputItemValue());
        }
        return true;
    }

    // =================================================================================================
    // The seventeen payload members, one per name-labelled DFHMDF, in copybook declaration order.
    //
    // These are the JSON payload. Not one of them carries @JsonIgnore, and not one is masked, redacted
    // or narrowed: they are the data the migrated screen emits, and suppressing any of them would change
    // observable behaviour. Every setter fits its value to the field's declared PICTURE width.
    // =================================================================================================

    /**
     * {@code TRNNAMEO PIC X(4)} - the transaction identifier shown against the {@code 'Tran:'} label at
     * row 1 column 7. {@code CORPT00C.cbl:615} fills it from {@code WS-TRANID}, whose value is
     * {@link #TRANSACTION_ID}.
     *
     * @return four characters, space-padded, never trimmed
     */
    public String getTrnnameo() {
        return trnnameo;
    }

    /**
     * Sets {@code TRNNAMEO}.
     *
     * @param trnnameo the value; never {@code null}
     * @throws NullPointerException if {@code trnnameo} is {@code null}
     */
    public void setTrnnameo(String trnnameo) {
        setPayloadValue(ScreenField.TRNNAME, trnnameo);
    }

    /**
     * {@code TITLE01O PIC X(40)} - the first title line, {@code COLOR=YELLOW} at row 1 column 21.
     * {@code CORPT00C.cbl:613} fills it from {@code CCDA-TITLE01}, which is
     * {@link ScreenTitles#CCDA_TITLE01}.
     *
     * @return forty characters
     */
    public String getTitle01o() {
        return title01o;
    }

    /**
     * Sets {@code TITLE01O}.
     *
     * @param title01o the value; never {@code null}
     * @throws NullPointerException if {@code title01o} is {@code null}
     */
    public void setTitle01o(String title01o) {
        setPayloadValue(ScreenField.TITLE01, title01o);
    }

    /**
     * {@code CURDATEO PIC X(8)} - the current date against the {@code 'Date:'} label at row 1 column
     * 71, declared {@code INITIAL='mm/dd/yy'}. {@code CORPT00C.cbl:622} fills it from
     * {@code WS-CURDATE-MM-DD-YY}, which is {@link DateHeader#wsCurdateMmDdYy()}.
     *
     * @return eight characters
     */
    public String getCurdateo() {
        return curdateo;
    }

    /**
     * Sets {@code CURDATEO}.
     *
     * @param curdateo the value; never {@code null}
     * @throws NullPointerException if {@code curdateo} is {@code null}
     */
    public void setCurdateo(String curdateo) {
        setPayloadValue(ScreenField.CURDATE, curdateo);
    }

    /**
     * {@code PGMNAMEO PIC X(8)} - the program name against the {@code 'Prog:'} label at row 2 column 7.
     * {@code CORPT00C.cbl:616} fills it from {@code WS-PGMNAME}, whose value is {@link #PROGRAM_NAME}.
     *
     * @return eight characters
     */
    public String getPgmnameo() {
        return pgmnameo;
    }

    /**
     * Sets {@code PGMNAMEO}.
     *
     * @param pgmnameo the value; never {@code null}
     * @throws NullPointerException if {@code pgmnameo} is {@code null}
     */
    public void setPgmnameo(String pgmnameo) {
        setPayloadValue(ScreenField.PGMNAME, pgmnameo);
    }

    /**
     * {@code TITLE02O PIC X(40)} - the second title line, {@code COLOR=YELLOW} at row 2 column 21.
     * {@code CORPT00C.cbl:614} fills it from {@code CCDA-TITLE02}, which is
     * {@link ScreenTitles#CCDA_TITLE02}.
     *
     * @return forty characters
     */
    public String getTitle02o() {
        return title02o;
    }

    /**
     * Sets {@code TITLE02O}.
     *
     * @param title02o the value; never {@code null}
     * @throws NullPointerException if {@code title02o} is {@code null}
     */
    public void setTitle02o(String title02o) {
        setPayloadValue(ScreenField.TITLE02, title02o);
    }

    /**
     * {@code CURTIMEO PIC X(8)} - the current time against the {@code 'Time:'} label at row 2 column
     * 71, declared {@code INITIAL='hh:mm:ss'}. {@code CORPT00C.cbl:628} fills it from
     * {@code WS-CURTIME-HH-MM-SS}, which is {@link DateHeader#wsCurtimeHhMmSs()}.
     *
     * @return eight characters
     */
    public String getCurtimeo() {
        return curtimeo;
    }

    /**
     * Sets {@code CURTIMEO}.
     *
     * @param curtimeo the value; never {@code null}
     * @throws NullPointerException if {@code curtimeo} is {@code null}
     */
    public void setCurtimeo(String curtimeo) {
        setPayloadValue(ScreenField.CURTIME, curtimeo);
    }

    /**
     * {@code MONTHLYO PIC X(1)} - the {@code 'Monthly (Current Month)'} selector at row 7 column 10.
     * {@code CORPT00C.cbl:213} treats any value that is neither {@code SPACES} nor {@code LOW-VALUES}
     * as a selection.
     *
     * @return one character
     */
    public String getMonthlyo() {
        return monthlyo;
    }

    /**
     * Sets {@code MONTHLYO}.
     *
     * @param monthlyo the value; never {@code null}
     * @throws NullPointerException if {@code monthlyo} is {@code null}
     */
    public void setMonthlyo(String monthlyo) {
        setPayloadValue(ScreenField.MONTHLY, monthlyo);
    }

    /**
     * {@code YEARLYO PIC X(1)} - the {@code 'Yearly (Current Year)'} selector at row 9 column 10,
     * tested at {@code CORPT00C.cbl:239}.
     *
     * @return one character
     */
    public String getYearlyo() {
        return yearlyo;
    }

    /**
     * Sets {@code YEARLYO}.
     *
     * @param yearlyo the value; never {@code null}
     * @throws NullPointerException if {@code yearlyo} is {@code null}
     */
    public void setYearlyo(String yearlyo) {
        setPayloadValue(ScreenField.YEARLY, yearlyo);
    }

    /**
     * {@code CUSTOMO PIC X(1)} - the {@code 'Custom (Date Range)'} selector at row 11 column 10, tested
     * at {@code CORPT00C.cbl:256}. When selected, the six date items below are edited.
     *
     * @return one character
     */
    public String getCustomo() {
        return customo;
    }

    /**
     * Sets {@code CUSTOMO}.
     *
     * @param customo the value; never {@code null}
     * @throws NullPointerException if {@code customo} is {@code null}
     */
    public void setCustomo(String customo) {
        setPayloadValue(ScreenField.CUSTOM, customo);
    }

    /**
     * {@code SDTMMO PIC X(2)} - start-date month at row 13 column 29. Alphanumeric, not numeric:
     * {@code CORPT00C.cbl:329-330} tests it with {@code IS NOT NUMERIC} and with the string comparison
     * {@code > '12'}.
     *
     * @return two characters
     */
    public String getSdtmmo() {
        return sdtmmo;
    }

    /**
     * Sets {@code SDTMMO}.
     *
     * @param sdtmmo the value; never {@code null}
     * @throws NullPointerException if {@code sdtmmo} is {@code null}
     */
    public void setSdtmmo(String sdtmmo) {
        setPayloadValue(ScreenField.SDTMM, sdtmmo);
    }

    /**
     * {@code SDTDDO PIC X(2)} - start-date day at row 13 column 34, tested at
     * {@code CORPT00C.cbl:338-339} against {@code '31'} as a string.
     *
     * @return two characters
     */
    public String getSdtddo() {
        return sdtddo;
    }

    /**
     * Sets {@code SDTDDO}.
     *
     * @param sdtddo the value; never {@code null}
     * @throws NullPointerException if {@code sdtddo} is {@code null}
     */
    public void setSdtddo(String sdtddo) {
        setPayloadValue(ScreenField.SDTDD, sdtddo);
    }

    /**
     * {@code SDTYYYYO PIC X(4)} - start-date year at row 13 column 39, tested at
     * {@code CORPT00C.cbl:347}.
     *
     * @return four characters
     */
    public String getSdtyyyyo() {
        return sdtyyyyo;
    }

    /**
     * Sets {@code SDTYYYYO}.
     *
     * @param sdtyyyyo the value; never {@code null}
     * @throws NullPointerException if {@code sdtyyyyo} is {@code null}
     */
    public void setSdtyyyyo(String sdtyyyyo) {
        setPayloadValue(ScreenField.SDTYYYY, sdtyyyyo);
    }

    /**
     * {@code EDTMMO PIC X(2)} - end-date month at row 14 column 29, tested at
     * {@code CORPT00C.cbl:355-356}.
     *
     * @return two characters
     */
    public String getEdtmmo() {
        return edtmmo;
    }

    /**
     * Sets {@code EDTMMO}.
     *
     * @param edtmmo the value; never {@code null}
     * @throws NullPointerException if {@code edtmmo} is {@code null}
     */
    public void setEdtmmo(String edtmmo) {
        setPayloadValue(ScreenField.EDTMM, edtmmo);
    }

    /**
     * {@code EDTDDO PIC X(2)} - end-date day at row 14 column 34, tested at
     * {@code CORPT00C.cbl:364-365}.
     *
     * @return two characters
     */
    public String getEdtddo() {
        return edtddo;
    }

    /**
     * Sets {@code EDTDDO}.
     *
     * @param edtddo the value; never {@code null}
     * @throws NullPointerException if {@code edtddo} is {@code null}
     */
    public void setEdtddo(String edtddo) {
        setPayloadValue(ScreenField.EDTDD, edtddo);
    }

    /**
     * {@code EDTYYYYO PIC X(4)} - end-date year at row 14 column 39, tested at
     * {@code CORPT00C.cbl:373}.
     *
     * @return four characters
     */
    public String getEdtyyyyo() {
        return edtyyyyo;
    }

    /**
     * Sets {@code EDTYYYYO}.
     *
     * @param edtyyyyo the value; never {@code null}
     * @throws NullPointerException if {@code edtyyyyo} is {@code null}
     */
    public void setEdtyyyyo(String edtyyyyo) {
        setPayloadValue(ScreenField.EDTYYYY, edtyyyyo);
    }

    /**
     * {@code CONFIRMO PIC X(1)} - the {@code (Y/N)} answer at row 19 column 66, tested at
     * {@code CORPT00C.cbl:478} and line 480 against {@code 'Y'}, {@code 'y'}, {@code 'N'} and
     * {@code 'n'}.
     *
     * @return one character
     */
    public String getConfirmo() {
        return confirmo;
    }

    /**
     * Sets {@code CONFIRMO}.
     *
     * @param confirmo the value; never {@code null}
     * @throws NullPointerException if {@code confirmo} is {@code null}
     */
    public void setConfirmo(String confirmo) {
        setPayloadValue(ScreenField.CONFIRM, confirmo);
    }

    /**
     * {@code ERRMSGO PIC X(78)} - the error line at row 23, {@code ATTRB=(ASKIP,BRT,FSET)} and
     * {@code COLOR=RED}. {@code CORPT00C.cbl:560} fills it with {@code WS-MESSAGE}, which is
     * {@code PIC X(80)}, so the last two bytes of the message are discarded on the way in.
     *
     * @return seventy-eight characters, space-padded and never trimmed, so a JSON round trip returns an
     *         equal value
     */
    public String getErrmsgo() {
        return errmsgo;
    }

    /**
     * Sets {@code ERRMSGO}.
     *
     * @param errmsgo the value; never {@code null}
     * @throws NullPointerException if {@code errmsgo} is {@code null}
     */
    public void setErrmsgo(String errmsgo) {
        setPayloadValue(ScreenField.ERRMSG, errmsgo);
    }

    /**
     * Reproduces {@code CORPT00C.cbl:169-170} {@code MOVE SPACES TO WS-MESSAGE ERRMSGO OF CORPT0AO} -
     * the first thing the program does on every invocation, clearing any message left over from the
     * previous send.
     */
    public void moveSpacesToErrmsgo() {
        setErrmsgo(spaces(ScreenField.ERRMSG.payloadLength()));
    }

    /**
     * Reproduces the invalid-key path, {@code CORPT00C.cbl:193}
     * {@code MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE} followed by line 560's move into
     * {@code ERRMSGO}. The text is {@link SystemMessages#CCDA_MSG_INVALID_KEY}, taken from
     * {@code app/cpy/CSMSG01Y.cpy} rather than retyped, so the bytes cannot drift.
     *
     * <p>The message is 50 characters and the field is 78, so it lands left-justified and space-padded -
     * which is what the two chained COBOL {@code MOVE}s produce.
     */
    public void moveInvalidKeyMessageToErrmsgo() {
        setErrmsgo(SystemMessages.CCDA_MSG_INVALID_KEY);
    }

    // =================================================================================================
    // POPULATE-HEADER-INFO, CORPT00C.cbl:610-628.
    // =================================================================================================

    /**
     * Reproduces the {@code POPULATE-HEADER-INFO} paragraph, {@code CORPT00C.cbl:613-628}, which
     * {@code SEND-TRNRPT-SCREEN} performs before every send.
     *
     * <p>The six moves are made in the paragraph's own order - titles, then transaction, then program,
     * then date, then time - because {@code TITLE01O} and {@code TITLE02O} are 40 bytes while the title
     * literals are exactly 40, and reordering would only obscure the correspondence:
     *
     * <pre>{@code
     *     MOVE CCDA-TITLE01        TO TITLE01O OF CORPT0AO    line 613
     *     MOVE CCDA-TITLE02        TO TITLE02O OF CORPT0AO    line 614
     *     MOVE WS-TRANID           TO TRNNAMEO OF CORPT0AO    line 615
     *     MOVE WS-PGMNAME          TO PGMNAMEO OF CORPT0AO    line 616
     *     MOVE WS-CURDATE-MM-DD-YY TO CURDATEO OF CORPT0AO    line 622
     *     MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO OF CORPT0AO    line 628
     * }</pre>
     *
     * <p>The date and time come from the supplied {@link DateHeader} rather than from a clock read
     * inside this method, so a caller - a parity case in particular - can pin
     * {@code FUNCTION CURRENT-DATE} (line 611) to a fixed instant and get a byte-identical response.
     * The literals come from {@link ScreenTitles}, which transcribes {@code app/cpy/COTTL01Y.cpy}, so
     * they are never retyped here.
     *
     * @param dateHeader the captured date and time, standing in for {@code WS-CURDATE-DATA}; never
     *                   {@code null}
     * @throws NullPointerException if {@code dateHeader} is {@code null}
     */
    public void populateHeaderInfo(DateHeader dateHeader) {
        Objects.requireNonNull(dateHeader, "A DateHeader is required to populate the screen header: "
                + "CORPT00C.cbl:611 reads FUNCTION CURRENT-DATE, and pinning that instant is what "
                + "makes a response reproducible");
        setTitle01o(ScreenTitles.CCDA_TITLE01);
        setTitle02o(ScreenTitles.CCDA_TITLE02);
        setTrnnameo(TRANSACTION_ID);
        setPgmnameo(PROGRAM_NAME);
        setCurdateo(dateHeader.wsCurdateMmDdYy());
        setCurtimeo(dateHeader.wsCurtimeHhMmSs());
    }

    // =================================================================================================
    // Navigation. These replace EXEC CICS XCTL; the server keeps no conversation state and performs no
    // forward, so the client reads these fields and issues the follow-up call itself.
    // =================================================================================================

    /**
     * The program the client should invoke next - the stateless replacement for
     * {@code CORPT00C.cbl:549} {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)}.
     *
     * <p>That {@code XCTL} names a COMMAREA field, not a literal, so this value is echoed from
     * {@link NavigationContext#toProgram()} by {@link #echoNavigation(NavigationContext)}. Blank means
     * no transfer was decided and the same screen is being re-displayed.
     *
     * @return eight characters
     */
    public String getNextProgram() {
        return nextProgram;
    }

    /**
     * Sets the next program.
     *
     * @param nextProgram the target program name; never {@code null}
     * @throws NullPointerException if {@code nextProgram} is {@code null}
     */
    public void setNextProgram(String nextProgram) {
        this.nextProgram = movePicX(nextProgram, NavigationContext.TO_PROGRAM_LENGTH,
                NavigationContext.TO_PROGRAM_FIELD);
    }

    /**
     * The mapset of the screen this response describes, {@value #MAPSET_NAME}. Seven bytes, matching
     * {@code CDEMO-LAST-MAPSET PIC X(7)}.
     *
     * @return seven characters
     */
    public String getNextMapset() {
        return nextMapset;
    }

    /**
     * Sets the next mapset.
     *
     * @param nextMapset the mapset name; never {@code null}
     * @throws NullPointerException if {@code nextMapset} is {@code null}
     */
    public void setNextMapset(String nextMapset) {
        this.nextMapset = movePicX(nextMapset, NavigationContext.LAST_MAPSET_LENGTH,
                NavigationContext.LAST_MAPSET_FIELD);
    }

    /**
     * The map of the screen this response describes, {@value #MAP_NAME}. Seven bytes, matching
     * {@code CDEMO-LAST-MAP PIC X(7)} - seven and not eight, because the symbolic-map group names are
     * formed by suffixing it.
     *
     * @return seven characters
     */
    public String getNextMap() {
        return nextMap;
    }

    /**
     * Sets the next map.
     *
     * @param nextMap the map name; never {@code null}
     * @throws NullPointerException if {@code nextMap} is {@code null}
     */
    public void setNextMap(String nextMap) {
        this.nextMap = movePicX(nextMap, NavigationContext.LAST_MAP_LENGTH,
                NavigationContext.LAST_MAP_FIELD);
    }

    /**
     * The commarea, echoed so the client can send it back unchanged on the next call. This is the whole
     * of the conversation state: there is no {@code HttpSession}, no {@code SessionAttributes} and no
     * server-side cache anywhere in this type.
     *
     * @return the context; never {@code null}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    /**
     * Sets the commarea echo without touching the navigation trio.
     *
     * @param navigationContext the context; never {@code null}
     * @throws NullPointerException if {@code navigationContext} is {@code null}
     */
    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = Objects.requireNonNull(navigationContext,
                "A NavigationContext is required; pass NavigationContext.empty() for a blank "
                        + "commarea rather than null, because COBOL has no null");
    }

    /**
     * Echoes a commarea into this response and derives the navigation trio from it for a
     * <strong>transfer</strong>: the program from {@link NavigationContext#toProgram()} - the field
     * {@code CORPT00C.cbl:549} passes to {@code XCTL} - and the mapset and map blank.
     *
     * <h4>Why the two map names are blanked</h4>
     * This method is reached only from {@code RETURN-TO-PREV-SCREEN}, and an {@code XCTL} hands control
     * to another program: which map that program will paint is its decision, made after this one has
     * ended. {@code CORPT00C} names no map in the {@code XCTL} at {@code :548-551}, and the
     * {@code CDEMO-LAST-MAPSET} and {@code CDEMO-LAST-MAP} items it passes are the caller's, not the
     * target's. Publishing this screen's own {@value #MAPSET_NAME} and {@value #MAP_NAME} here would
     * tell the client to paint the screen it is leaving, which is the one answer that is certainly
     * wrong. Blank means "not stated here": the client follows {@link #getNextProgram()}, and the
     * target's own reply names its map. A {@code SEND} publishes this screen's names, and
     * {@link #ReportRequestResponse()} still initialises them to exactly that.
     *
     * <p>The commarea is echoed exactly as supplied. The {@code LOW-VALUES}-or-{@code SPACES} fallback
     * to {@link #DEFAULT_NEXT_PROGRAM} at {@code CORPT00C.cbl:542-543} runs <em>before</em> the transfer
     * and is the controller's decision, not this type's; {@link #isSpacesOrLowValues(String)} is
     * exposed so the controller can make it against the same rule this type uses.
     *
     * @param context the commarea to echo; never {@code null}
     * @throws NullPointerException if {@code context} is {@code null}
     */
    public void echoNavigation(NavigationContext context) {
        setNavigationContext(context);
        setNextProgram(context.toProgram());
        setNextMapset(spaces(NavigationContext.LAST_MAPSET_LENGTH));
        setNextMap(spaces(NavigationContext.LAST_MAP_LENGTH));
    }

    /**
     * Whether a fixed-width value satisfies COBOL's {@code = LOW-VALUES OR SPACES} test - the guard at
     * {@code CORPT00C.cbl:542} and, on the inbound items, at lines 213, 239, 256 and 259-299.
     *
     * <p>A group comparison against a figurative constant asks whether <strong>every</strong> byte
     * matches, so a value mixing spaces and {@code X'00'} satisfies neither test. An empty string is
     * treated as satisfying it, because a zero-width item has no byte that could differ.
     *
     * @param value the value to test; may be {@code null}, which is treated as blank
     * @return {@code true} when every byte is a space, or every byte is {@code X'00'}, or the value is
     *         {@code null} or empty
     */
    public static boolean isSpacesOrLowValues(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        boolean allSpaces = true;
        boolean allLowValues = true;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != ' ') {
                allSpaces = false;
            }
            if (character != '\u0000') {
                allLowValues = false;
            }
        }
        return allSpaces || allLowValues;
    }

    // =================================================================================================
    // The 337-byte group image. This is what a CICS SEND MAP would transmit and what a field-by-field
    // parity diff compares, so it is rendered by absolute offset from LAYOUT and never by concatenation.
    // =================================================================================================

    /**
     * Renders this response as the {@link #RECORD_LENGTH}-byte {@code 01 CORPT0AO} image.
     *
     * <p>Every {@code FILLER} span - the twelve-byte {@code TIOAPFX} prefix and the seventeen
     * three-byte attribute fillers - is emitted as spaces, because {@link FixedWidthRecord#initialise}
     * fills a {@code FILLER} span with the pad byte and a {@code FILLER} is not numeric. Dropping them
     * would shorten the image and shift every field after the gap.
     *
     * @param charset the code page to render in - {@code IBM037} for a mainframe image, {@code US-ASCII}
     *                for a fixture. Stated explicitly and never taken from the platform default; never
     *                {@code null}
     * @return exactly {@link #RECORD_LENGTH} bytes
     * @throws NullPointerException if {@code charset} is {@code null}
     */
    public byte[] toFixedWidth(Charset charset) {
        Objects.requireNonNull(charset, "A charset is required to render " + SYMBOLIC_MAP_GROUP
                + " as bytes: a fixed-width image is bytes in a specific code page, so the code page "
                + "must be stated explicitly and is never taken from the platform");
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        FixedWidthRecord record = codec.newRecord(LAYOUT);
        writeInto(record, codec);
        return record.toByteArray();
    }

    /**
     * Writes this response into an existing record area at the layout's absolute offsets.
     *
     * @param record the target area, which must be exactly {@link #RECORD_LENGTH} bytes wide; never
     *               {@code null}
     * @throws NullPointerException     if {@code record} is {@code null}
     * @throws IllegalArgumentException if the record is not {@link #RECORD_LENGTH} bytes wide
     */
    public void writeInto(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A record area is required to write " + SYMBOLIC_MAP_GROUP
                + " into");
        writeInto(record, new FixedWidthCodec(record.charset()));
    }

    /**
     * The single implementation of the outbound write: seventeen payload items through the codec's
     * {@code PIC X} rules, and sixty-eight attribute items as raw bytes.
     *
     * <p>The attribute items bypass the charset deliberately. A BMS attribute is a code point, not a
     * character: {@link BmsAttributes#DFHRED} is {@code X'F2'}, which is also EBCDIC {@code '2'}, so
     * encoding it as text would round-trip correctly under {@code IBM037} and corrupt it under any
     * other code page. Writing the byte itself is correct under all of them.
     *
     * @param record the target area
     * @param codec  the codec supplying the {@code PIC X} rules
     */
    private void writeInto(FixedWidthRecord record, FixedWidthCodec codec) {
        requireGroupWidth(record);
        for (ScreenField field : ScreenField.values()) {
            codec.writePicX(record, field.payloadSpan(), payloadValue(field));
            FieldAttributes quad = attributesOf(field);
            record.writeSpanBytes(field.colourSpan(), new byte[] {quad.colour()});
            record.writeSpanBytes(field.psSpan(), new byte[] {quad.ps()});
            record.writeSpanBytes(field.hilightSpan(), new byte[] {quad.hilight()});
            record.writeSpanBytes(field.validnSpan(), new byte[] {quad.validn()});
        }
    }

    /**
     * Rebuilds a response from a {@link #RECORD_LENGTH}-byte group image.
     *
     * <p>This exists because {@code xxxO} is <strong>not</strong> write-only. {@code CORPT0AO}
     * redefines {@code CORPT0AI}, so the outbound items alias the inbound ones and reading back what
     * was written is a real operation, not a test convenience. Nothing is trimmed, so a value that was
     * space-padded on the way in comes back space-padded and compares equal.
     *
     * @param image   exactly {@link #RECORD_LENGTH} bytes; never {@code null}
     * @param charset the code page the image is in; never {@code null}
     * @return a response carrying every payload item and every attribute byte from the image
     * @throws NullPointerException     if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not {@link #RECORD_LENGTH} bytes long
     */
    public static ReportRequestResponse fromFixedWidth(byte[] image, Charset charset) {
        Objects.requireNonNull(image, "Stored bytes are required to read " + SYMBOLIC_MAP_GROUP
                + " from");
        Objects.requireNonNull(charset, "A charset is required to read " + SYMBOLIC_MAP_GROUP
                + " from bytes; it is never taken from the platform");
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return readFrom(codec.wrap(image, LAYOUT), codec);
    }

    /**
     * Reads a response out of an existing record area.
     *
     * @param record the source area, exactly {@link #RECORD_LENGTH} bytes wide; never {@code null}
     * @return a response carrying every payload item and every attribute byte
     * @throws NullPointerException     if {@code record} is {@code null}
     * @throws IllegalArgumentException if the record is not {@link #RECORD_LENGTH} bytes wide
     */
    public static ReportRequestResponse readFrom(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A record area is required to read " + SYMBOLIC_MAP_GROUP
                + " from");
        return readFrom(record, new FixedWidthCodec(record.charset()));
    }

    /**
     * The single implementation of the inbound read, mirroring {@link #writeInto} item for item.
     *
     * @param record the source area
     * @param codec  the codec supplying the {@code PIC X} rules
     * @return the populated response
     */
    private static ReportRequestResponse readFrom(FixedWidthRecord record, FixedWidthCodec codec) {
        requireGroupWidth(record);
        ReportRequestResponse response = new ReportRequestResponse();
        for (ScreenField field : ScreenField.values()) {
            response.setPayloadValue(field, codec.readPicX(record, field.payloadSpan()));
            response.setAttributes(field, new FieldAttributes(
                    record.readSpanBytes(field.colourSpan())[0],
                    record.readSpanBytes(field.psSpan())[0],
                    record.readSpanBytes(field.hilightSpan())[0],
                    record.readSpanBytes(field.validnSpan())[0]));
        }
        return response;
    }

    /**
     * The seventeen payload items keyed by their verbatim copybook names - {@code TRNNAMEO},
     * {@code TITLE01O} and so on - in copybook order, at their declared widths and untrimmed.
     *
     * <p>This is the shape a field-by-field parity diff consumes: comparing named items catches a value
     * that landed in the wrong field, which comparing whole 337-byte strings would report only as a
     * single opaque mismatch.
     *
     * <p>Attribute items are absent by design. They are metadata, they are not part of the diffed
     * payload, and {@link #attributeImages()} exposes them separately for the cases that do need them.
     *
     * @return a new, mutable, insertion-ordered map of seventeen entries
     */
    @JsonIgnore
    public Map<String, String> fieldImages() {
        Map<String, String> images = new LinkedHashMap<>();
        for (ScreenField field : ScreenField.values()) {
            images.put(field.payloadItemName(), payloadValue(field));
        }
        return images;
    }

    /**
     * The sixty-eight attribute items keyed by their verbatim copybook names - {@code TRNNAMEC},
     * {@code TRNNAMEP}, {@code TRNNAMEH}, {@code TRNNAMEV} and so on - rendered as two-character hex,
     * in copybook order.
     *
     * <p>Hex rather than raw bytes so the map is printable and diffable, and because
     * {@link BmsAttributes#toHex(byte)} is the module's one rendering of an attribute byte.
     *
     * @return a new, mutable, insertion-ordered map of sixty-eight entries
     */
    @JsonIgnore
    public Map<String, String> attributeImages() {
        Map<String, String> images = new LinkedHashMap<>();
        for (ScreenField field : ScreenField.values()) {
            FieldAttributes quad = attributesOf(field);
            String base = field.baseName();
            images.put(base + "C", BmsAttributes.toHex(quad.colour()));
            images.put(base + "P", BmsAttributes.toHex(quad.ps()));
            images.put(base + "H", BmsAttributes.toHex(quad.hilight()));
            images.put(base + "V", BmsAttributes.toHex(quad.validn()));
        }
        return images;
    }

    // =================================================================================================
    // Object contract. Equality covers everything that is transmitted: the seventeen payload items, all
    // sixty-eight attribute bytes and the navigation state. Two responses that would send identical
    // bytes and identical navigation are equal, and no more than that.
    // =================================================================================================

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReportRequestResponse that)) {
            return false;
        }
        return trnnameo.equals(that.trnnameo)
                && title01o.equals(that.title01o)
                && curdateo.equals(that.curdateo)
                && pgmnameo.equals(that.pgmnameo)
                && title02o.equals(that.title02o)
                && curtimeo.equals(that.curtimeo)
                && monthlyo.equals(that.monthlyo)
                && yearlyo.equals(that.yearlyo)
                && customo.equals(that.customo)
                && sdtmmo.equals(that.sdtmmo)
                && sdtddo.equals(that.sdtddo)
                && sdtyyyyo.equals(that.sdtyyyyo)
                && edtmmo.equals(that.edtmmo)
                && edtddo.equals(that.edtddo)
                && edtyyyyo.equals(that.edtyyyyo)
                && confirmo.equals(that.confirmo)
                && errmsgo.equals(that.errmsgo)
                && attributes.equals(that.attributes)
                && nextProgram.equals(that.nextProgram)
                && nextMapset.equals(that.nextMapset)
                && nextMap.equals(that.nextMap)
                && navigationContext.equals(that.navigationContext);
    }

    @Override
    public int hashCode() {
        return Objects.hash(trnnameo, title01o, curdateo, pgmnameo, title02o, curtimeo, monthlyo,
                yearlyo, customo, sdtmmo, sdtddo, sdtyyyyo, edtmmo, edtddo, edtyyyyo, confirmo,
                errmsgo, attributes, nextProgram, nextMapset, nextMap, navigationContext);
    }

    /**
     * A diagnostic rendering naming every item by its copybook name, so a failed assertion reads as
     * {@code ERRMSGO='...'} rather than as an anonymous tuple. The error line is quoted in full,
     * padding included, because trailing spaces are exactly what a width bug destroys.
     *
     * @return a human-readable description; never {@code null}
     */
    @Override
    public String toString() {
        StringBuilder rendered = new StringBuilder(512)
                .append("ReportRequestResponse[")
                .append(SYMBOLIC_MAP_GROUP)
                .append(' ')
                .append(RECORD_LENGTH)
                .append(" bytes");
        for (ScreenField field : ScreenField.values()) {
            rendered.append(", ")
                    .append(field.payloadItemName())
                    .append("='")
                    .append(payloadValue(field))
                    .append('\'');
            FieldAttributes quad = attributesOf(field);
            if (!quad.allUnset()) {
                rendered.append(" (").append(quad.describe()).append(')');
            }
        }
        return rendered.append(", nextProgram='").append(nextProgram)
                .append("', nextMapset='").append(nextMapset)
                .append("', nextMap='").append(nextMap)
                .append("', ").append(NavigationContext.TO_PROGRAM_FIELD).append("='")
                .append(navigationContext.toProgram())
                .append("']")
                .toString();
    }

    // =================================================================================================
    // Private helpers. Each is one rule, named after the construct it enforces.
    // =================================================================================================

    /**
     * The alphanumeric {@code MOVE}, with a message that explains what to pass instead of {@code null}.
     *
     * @param value     the sending value
     * @param length    the receiver's declared {@code PICTURE} width
     * @param cobolName the receiver's copybook name, for the message
     * @return the value fitted to {@code length}
     */
    private static String movePicX(String value, int length, String cobolName) {
        Objects.requireNonNull(value, "A value is required for " + cobolName + ": COBOL has no null, "
                + "so pass spaces(" + length + ") or lowValues(" + length + ") to state which "
                + "figurative constant is meant");
        return PICTURE_RULES.movePicX(value, length);
    }

    /**
     * Rejects a {@code null} field selector with a message that says what the selector is for.
     *
     * @param field the selector to check
     */
    private static void requireField(ScreenField field) {
        Objects.requireNonNull(field, "A ScreenField is required to select one of the "
                + SCREEN_FIELD_COUNT + " name-labelled DFHMDF fields of mapset " + MAPSET_NAME);
    }

    /**
     * Rejects a width that no copybook item could have.
     *
     * @param length              the width offered
     * @param figurativeConstant  the constant being built, for the message
     */
    private static void requireDeclaredWidth(int length, String figurativeConstant) {
        if (length < 1) {
            throw new IllegalArgumentException(figurativeConstant + " was requested at width "
                    + length + "; every copybook item occupies at least 1 byte");
        }
    }

    /**
     * Rejects a record area that is not the group's declared width, so a width mistake surfaces as a
     * named failure instead of as an out-of-range write deep inside the codec.
     *
     * @param record the area to check
     */
    private static void requireGroupWidth(FixedWidthRecord record) {
        if (record.recordLength() != RECORD_LENGTH) {
            throw new IllegalArgumentException("A " + SYMBOLIC_MAP_GROUP + " image is "
                    + RECORD_LENGTH + " byte(s) - " + TIOAPFX_PREFIX_LENGTH + " (TIOAPFX) + "
                    + SCREEN_FIELD_COUNT + " x " + FIELD_PREFIX_LENGTH + " (attribute prefixes) + "
                    + PAYLOAD_WIDTH_TOTAL + " (payload) - but the record area is "
                    + record.recordLength() + " byte(s) wide");
        }
    }
}
