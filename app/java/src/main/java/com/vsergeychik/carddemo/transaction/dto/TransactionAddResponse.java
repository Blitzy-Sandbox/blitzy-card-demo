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
import com.vsergeychik.carddemo.transaction.dto.TransactionAddRequest.Ct01Info;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The outbound REST payload for CSD transaction {@code CT01}, program {@code COTRN01C}: a
 * field-for-field projection of the {@code xxxO} items of {@code 01 COTRN1AO REDEFINES COTRN1AI}
 * in {@code app/cpy-bms/COTRN01.CPY} (line 145) and their name-labelled {@code DFHMDF} definitions
 * in {@code app/bms/COTRN01.bms}.
 *
 * <h2>&#9888; Risk R-B - this class name contradicts its source, deliberately</h2>
 * The name says <em>Add</em>. The program does not add anything: {@code app/cbl/COTRN01C.cbl:5}
 * declares {@code Function : View a Transaction from TRANSACT file}, its send and receive
 * paragraphs are literally named {@code SEND-TRNVIEW-SCREEN} and {@code RECEIVE-TRNVIEW-SCREEN},
 * and {@code README.md:L213-L231} independently documents {@code CT01} as "Transaction View". The
 * build prompt nevertheless mandates the name {@code TransactionAdd…}, and the Agent Action Plan
 * records the contradiction as <strong>Conflict Set 1 (AAP &sect;0.1.8)</strong>, calling it the
 * highest-risk naming ambiguity in the plan, and logs it as <strong>risk R-B
 * (AAP &sect;0.9.12)</strong>.
 *
 * <p><strong>Rule R1 resolves it: the name comes from the prompt, the field set and the behaviour
 * come from the paired copybook and program.</strong> The conflict is documented here rather than
 * silently corrected, per practices B4 and B12. Two independent structural proofs that this is the
 * view screen and not the add screen:
 *
 * <ul>
 *   <li>{@code COTRN01} carries {@code TRNIDIN} (a 16-byte lookup key) plus {@code TRNID}, and has
 *       no {@code CONFIRM} field. Exactly <strong>one</strong> of its 21 named fields is
 *       {@code UNPROT} - {@code TRNIDIN}, at {@code app/bms/COTRN01.bms:85}. A screen that adds a
 *       transaction cannot have one enterable field.</li>
 *   <li>The sibling mapset {@code COTRN02} has {@code ACTIDIN}, {@code CARDNIN} and
 *       {@code CONFIRM}, and 14 {@code UNPROT} fields. That is the real add screen.</li>
 * </ul>
 *
 * <p>Consequently this class must <strong>not</strong> grow "add"-style members to justify its
 * name. There is no {@code CONFIRMO}, no {@code ACTIDINO} and no {@code CARDNINO} here; those
 * belong to {@code COTRN02} and would break the field-for-field projection that gate G9 checks.
 *
 * <h2>Why the Request and the Response carry identical fields</h2>
 * {@code COTRN1AO} <em>redefines</em> {@code COTRN1AI}: the two groups are the same storage seen
 * two ways, not two different structures. Both views spend exactly seven prefix bytes per field -
 * the input view as {@code xxxL COMP PIC S9(4)} (2) + {@code xxxF PICTURE X} (1) +
 * {@code FILLER X(4)} (4), the output view as {@code FILLER X(3)} (3) + {@code xxxC} +
 * {@code xxxP} + {@code xxxH} + {@code xxxV} (1 each) - so every field's {@code I} item and
 * {@code O} item sit at the <strong>identical offset</strong> and share their bytes.
 *
 * <p>{@code COTRN01C} writes through both aliases: most field stores go through {@code …AI}, for
 * example {@code MOVE WS-TRAN-AMT TO TRNAMTI OF COTRN1AI} at line 183, while the header and error
 * line go through {@code …AO} in {@code POPULATE-HEADER-INFO} (lines 244-262) and at line 217.
 * The input/output split is therefore a <em>directional projection convention</em>, not a storage
 * boundary: an {@code xxxO} item is never write-only, and round-tripping this payload must be
 * lossless. The 21 base names and widths here match {@code TransactionAddRequest} exactly; a
 * divergence between the two means one of them is wrong.
 *
 * <h2>Geometry, and why the totals are worth stating</h2>
 * <table border="1">
 *   <caption>Byte arithmetic of the {@code COTRN1AO} group</caption>
 *   <tr><th>Component</th><th>Bytes</th></tr>
 *   <tr><td>Leading {@code FILLER PIC X(12)} - the {@code TIOAPFX} prefix</td><td>12</td></tr>
 *   <tr><td>21 fields &times; 7 attribute-prefix bytes</td><td>147</td></tr>
 *   <tr><td>21 payload widths</td><td>416</td></tr>
 *   <tr><td><strong>{@link #SYMBOLIC_MAP_LENGTH}</strong></td><td><strong>575</strong></td></tr>
 * </table>
 *
 * <p>These are not decorative. {@link #LAYOUT} declares all 105 spans - the leading filler, and
 * per field one {@code FILLER X(3)}, four single-byte attribute items and one payload item - and
 * {@link RecordLayout} rejects the layout outright if the spans do not tile exactly 575 bytes with
 * no gap and no overlap. A mistyped offset in the {@link ScreenField} table below is therefore a
 * loud class-initialisation failure rather than a silent parity defect.
 *
 * <h2>Naming: verbatim, lowercased, never re-interpreted</h2>
 * Each payload accessor is named after its {@code xxxO} item with the token simply lowercased -
 * {@code TRNNAMEO} becomes {@link #getTrnnameo()}, {@code TITLE01O} becomes
 * {@link #getTitle01o()}. The tokens are <strong>not</strong> re-split into idiomatic camel case,
 * because doing so would require guessing where a compressed COBOL name divides
 * ({@code TTYPCD}, {@code TCATCD}, {@code TORIGDT}) and a wrong guess silently renames a field
 * that field-for-field diffing depends on. The exact copybook spelling is additionally carried as
 * a constant on each {@link ScreenField} constant, which is the name {@link FixedWidthCodec} keys
 * its images by and the name the parity differ compares.
 *
 * <h2>Numeric typing: every payload member is a {@code String}</h2>
 * All 21 payload members are {@code String} at their declared width. There is no
 * {@code BigDecimal}, no {@code int}, no {@code double} and no {@code float} among them, and this
 * class performs no arithmetic at all - which is how it satisfies gates G22, G23 and G24 by
 * construction rather than by inspection.
 *
 * <p>{@link ScreenField#TRNAMTO} is the case that looks like an exception and is not. The record's
 * {@code TRAN-AMT} is {@code PIC S9(09)V99} (11 bytes) in {@code app/cpy/CVTRA05Y.cpy}, but the
 * screen field is {@code PIC X(12)}, because the screen carries an <em>edited</em> amount:
 * {@code app/cbl/COTRN01C.cbl:49} declares {@code 05 WS-TRAN-AMT PIC +99999999.99}, which is a
 * sign, eight integer digits, a decimal point and two fraction digits - exactly twelve characters
 * - and line 183 moves that edited value onto the screen. This class reproduces the edit form, not
 * the raw record form. Note that the mask holds only eight integer digits where the record holds
 * nine, so a record-to-screen move genuinely left-truncates the ninth digit; that truncation is
 * the controller's concern and must not be "fixed" here by widening the field beyond 12.
 *
 * <p>No {@code BigDecimal} convenience accessor is offered. Adding one would introduce a second
 * JSON member for a single {@code DFHMDF} field and break the 1:1 projection gate G9, and this
 * class deliberately holds no numeric conversion policy: {@code CobolDecimal} is the module's
 * single seam for scale and {@code RoundingMode.DOWN}, and the service layer owns it.
 *
 * <h2>Statelessness</h2>
 * Every scrap of conversation state travels in this payload: the 160-byte
 * {@link NavigationContext} (the {@code CARDDEMO-COMMAREA}) and the 58-byte
 * {@link Ct01Info} cursor, giving the 218-byte area {@code COTRN01C} passes on. There is
 * no {@code HttpSession}, no {@code @SessionAttributes}, no server-side conversation state and no
 * static cache anywhere in this class - rule R6 and gate G37.
 *
 * <p>{@link #getNextProgram()}, {@link #getNextMapset()} and {@link #getNextMap()} replace
 * {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)} at {@code app/cbl/COTRN01C.cbl:206} (gate G40).
 * That site is COMMAREA-driven, so {@code nextProgram} is echoed from the context's
 * {@code CDEMO-TO-PROGRAM}; the client issues the follow-up call itself. No sibling DTO is
 * imported to express a navigation target, because a server-side forward is exactly what
 * statelessness forbids.
 *
 * <h2>Thread safety</h2>
 * Not thread-safe, and deliberately so: this is a per-request payload with a lifetime bounded by
 * one request, mirroring a CICS task's own working storage. Instances are never shared between
 * requests, and the class holds no static mutable state (practice B9) - the only static members
 * are immutable constants and an immutable {@link FixedWidthCodec}.
 *
 * <p>Its inbound counterpart is {@code TransactionAddRequest}, the projection of these same bytes
 * through the {@code xxxI} items; the two must agree field for field.
 */
public final class TransactionAddResponse {

    // =================================================================================================
    // Identity. Taken from app/csd/CARDDEMO.CSD and from COTRN01C's own WORKING-STORAGE, so no caller
    // has to hard-code a transaction, program, mapset or map name.
    // =================================================================================================

    /**
     * The CSD transaction that reaches this screen: {@code CT01}, defined at
     * {@code app/csd/CARDDEMO.CSD:429-430} as {@code DEFINE TRANSACTION(CT01) PROGRAM(COTRN01C)}.
     * Also the literal {@code COTRN01C} moves into {@link ScreenField#TRNNAMEO} - it is
     * {@code 05 WS-TRANID PIC X(04) VALUE 'CT01'} at {@code app/cbl/COTRN01C.cbl:37}.
     */
    public static final String TRANSACTION_ID = "CT01";

    /**
     * The program this screen belongs to: {@code COTRN01C}, defined at
     * {@code app/csd/CARDDEMO.CSD:264} and declared as
     * {@code 05 WS-PGMNAME PIC X(08) VALUE 'COTRN01C'} at {@code app/cbl/COTRN01C.cbl:36}, which is
     * the value moved into {@link ScreenField#PGMNAMEO}.
     */
    public static final String PROGRAM_NAME = "COTRN01C";

    /**
     * The BMS mapset: {@code COTRN01}, defined at {@code app/csd/CARDDEMO.CSD:149} and named by
     * {@code MAPSET('COTRN01')} on the {@code SEND} and {@code RECEIVE} at
     * {@code app/cbl/COTRN01C.cbl:221} and {@code :233}. Seven characters, which is exactly the
     * width of {@code CDEMO-LAST-MAPSET}.
     */
    public static final String MAPSET_NAME = "COTRN01";

    /**
     * The BMS map: {@code COTRN1A}, named by {@code MAP('COTRN1A')} on the same two CICS commands.
     * Seven characters, matching {@code CDEMO-LAST-MAP}, and the stem of the two symbolic-map
     * groups {@code COTRN1AI} and {@code COTRN1AO}.
     */
    public static final String MAP_NAME = "COTRN1A";

    /** The input symbolic-map group name, {@code COTRN1AI} - {@code app/cpy-bms/COTRN01.CPY:17}. */
    public static final String INPUT_MAP_GROUP_NAME = MAP_NAME + "I";

    /**
     * The output symbolic-map group name, {@code COTRN1AO} - {@code app/cpy-bms/COTRN01.CPY:145}.
     * This is the group this class projects, and the group {@code FieldAttributeSetter} qualifies
     * both highlight items by.
     */
    public static final String OUTPUT_MAP_GROUP_NAME = MAP_NAME + FieldAttributeSetter.OUTPUT_MAP_SUFFIX;

    // =================================================================================================
    // Geometry. Every constant below is derived arithmetic rather than a bare literal, so the byte
    // totals the Agent Action Plan states can be read straight off the source.
    // =================================================================================================

    /**
     * The leading {@code 02 FILLER PIC X(12)} at {@code app/cpy-bms/COTRN01.CPY:146}: the
     * {@code TIOAPFX=YES} prefix CICS reserves ahead of the first field. Twelve bytes that carry no
     * application data and must still be emitted, because every offset after them depends on it.
     */
    public static final int TIOAPFX_PREFIX_LENGTH = 12;

    /** The {@code 02 FILLER PICTURE X(3)} that opens each field group in the output view. */
    public static final int FIELD_GROUP_FILLER_LENGTH = 3;

    /** Width of each of the four attribute items {@code xxxC}, {@code xxxP}, {@code xxxH}, {@code xxxV}. */
    public static final int ATTRIBUTE_ITEM_LENGTH = 1;

    /** How many attribute items each field carries: colour, programmed symbols, highlight, validation. */
    public static final int ATTRIBUTE_ITEM_COUNT = 4;

    /**
     * Bytes standing ahead of every payload item in the output view: {@code 3 + 4 &times; 1 = 7}.
     * The input view spends the same seven ({@code 2 + 1 + 4}), which is why the {@code I} and
     * {@code O} items of a field are aliases rather than neighbours.
     */
    public static final int FIELD_ATTRIBUTE_PREFIX_LENGTH =
            FIELD_GROUP_FILLER_LENGTH + ATTRIBUTE_ITEM_COUNT * ATTRIBUTE_ITEM_LENGTH;

    /**
     * The number of payload fields: <strong>21</strong>. Verified three independent ways - 21
     * name-labelled {@code DFHMDF} entries among the 56 in {@code app/bms/COTRN01.bms}, 21
     * {@code xxxI} items and 21 {@code xxxO} items in {@code app/cpy-bms/COTRN01.CPY}.
     */
    public static final int PAYLOAD_FIELD_COUNT = 21;

    /**
     * The sum of the 21 payload widths: <strong>416</strong>. Computed from {@link ScreenField}
     * rather than asserted, so the table is the single source of truth.
     */
    public static final int TOTAL_PAYLOAD_WIDTH = sumPayloadWidths();

    /**
     * The full {@code COTRN1AO} group image: {@code 12 + 21 &times; 7 + 416 = }
     * <strong>575</strong> bytes.
     */
    public static final int SYMBOLIC_MAP_LENGTH =
            TIOAPFX_PREFIX_LENGTH
                    + PAYLOAD_FIELD_COUNT * FIELD_ATTRIBUTE_PREFIX_LENGTH
                    + TOTAL_PAYLOAD_WIDTH;

    /**
     * The area {@code COTRN01C} passes on its {@code XCTL} and {@code RETURN}:
     * {@code 160 + 58 = } <strong>218</strong> bytes - the 160-byte {@code CARDDEMO-COMMAREA} from
     * {@code app/cpy/COCOM01Y.cpy} followed by the 58-byte {@code CDEMO-CT01-INFO} extension
     * declared in place at {@code app/cbl/COTRN01C.cbl:53-61}.
     */
    public static final int PASSED_COMMAREA_LENGTH =
            NavigationContext.COMMAREA_LENGTH + Ct01Info.RECORD_LENGTH;

    /**
     * Width of {@code CDEMO-TO-PROGRAM}, {@code PIC X(08)} - {@code app/cpy/COCOM01Y.cpy:24}. This
     * is the width {@link #getNextProgram()} is held at, because that is the field the value is
     * echoed from.
     */
    public static final int NEXT_PROGRAM_LENGTH = NavigationContext.TO_PROGRAM_LENGTH;

    /**
     * Width of {@code CDEMO-LAST-MAPSET}, {@code PIC X(7)} - {@code app/cpy/COCOM01Y.cpy:44}.
     * <strong>Seven, not eight.</strong> A program name is eight wide and a map or mapset name is
     * seven, and conflating them would silently pad or truncate a navigation target.
     */
    public static final int NEXT_MAPSET_LENGTH = NavigationContext.LAST_MAPSET_LENGTH;

    /**
     * Width of {@code CDEMO-LAST-MAP}, {@code PIC X(7)} - {@code app/cpy/COCOM01Y.cpy:43}. Seven,
     * for the same reason as {@link #NEXT_MAPSET_LENGTH}.
     */
    public static final int NEXT_MAP_LENGTH = NavigationContext.LAST_MAP_LENGTH;

    /**
     * Width of {@code 05 WS-MESSAGE PIC X(80)} at {@code app/cbl/COTRN01C.cbl:38}, the field whose
     * contents line 217 moves into {@link ScreenField#ERRMSGO}.
     *
     * <p>Recorded because it is <strong>wider than the screen field it feeds</strong>: 80 into 78
     * means a COBOL alphanumeric move discards the last two characters on the right. The standard
     * message texts are unaffected, since {@link SystemMessages#MESSAGE_LENGTH} is 50 and 50 fits
     * inside 78 with room to spare; only a caller composing a message longer than 78 characters
     * loses anything, and it loses it on the right exactly as the COBOL does.
     */
    public static final int WS_MESSAGE_LENGTH = 80;

    // -------------------------------------------------------------------------------------------------
    // Widths this screen shares with the module's common contracts. Each is DEFINED as the shared
    // constant rather than restated as a literal, so the two cannot drift apart: if a title literal or
    // a formatted date ever changed width, these would change with it and the accompanying assertions
    // against the ScreenField table would fail immediately instead of producing a silently padded or
    // truncated header.
    // -------------------------------------------------------------------------------------------------

    /**
     * The width of both title items, {@link ScreenField#TITLE01O} and {@link ScreenField#TITLE02O}:
     * {@link ScreenTitles#TITLE_LENGTH}, which is 40. {@code POPULATE-HEADER-INFO} moves
     * {@code CCDA-TITLE01} and {@code CCDA-TITLE02} into them at
     * {@code app/cbl/COTRN01C.cbl:247-248}, so the literals and the screen fields must agree exactly.
     */
    public static final int TITLE_ITEM_LENGTH = ScreenTitles.TITLE_LENGTH;

    /**
     * The width of {@link ScreenField#CURDATEO}: {@link DateHeader#WS_CURDATE_MM_DD_YY_LENGTH}, which
     * is 8 - the width of {@code WS-CURDATE-MM-DD-YY}, moved in at
     * {@code app/cbl/COTRN01C.cbl:258}.
     */
    public static final int CURDATE_ITEM_LENGTH = DateHeader.WS_CURDATE_MM_DD_YY_LENGTH;

    /**
     * The width of {@link ScreenField#CURTIMEO}: {@link DateHeader#WS_CURTIME_HH_MM_SS_LENGTH}, which
     * is 8 - the width of {@code WS-CURTIME-HH-MM-SS}, moved in at
     * {@code app/cbl/COTRN01C.cbl:262}.
     */
    public static final int CURTIME_ITEM_LENGTH = DateHeader.WS_CURTIME_HH_MM_SS_LENGTH;

    /**
     * The width of a standard message literal, {@link SystemMessages#MESSAGE_LENGTH}, which is 50.
     *
     * <p>Recorded next to {@link #WS_MESSAGE_LENGTH} because the relationship matters: a standard
     * text is 50 characters, {@link ScreenField#ERRMSGO} is 78, and 50 fits inside 78 - so
     * {@code MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE} at {@code app/cbl/COTRN01C.cbl:130} followed by
     * {@code MOVE WS-MESSAGE TO ERRMSGO} at line 217 loses nothing. Only a caller composing its own
     * message longer than 78 characters is truncated, and then on the right, as COBOL truncates.
     */
    public static final int STANDARD_MESSAGE_LENGTH = SystemMessages.MESSAGE_LENGTH;

    // =================================================================================================
    // The geometry table. One enum constant per name-labelled DFHMDF field, in COTRN1AO declaration
    // order, each carrying its verbatim copybook spelling, its declared width and the absolute offset
    // of its field group. This is the table to diff against app/cpy-bms/COTRN01.CPY when reviewing.
    // =================================================================================================

    /**
     * The 21 payload fields of {@code COTRN1AO}, in declaration order.
     *
     * <p>Each constant is named exactly as its {@code xxxO} item and carries four facts: the base
     * name shared by the {@code I}, {@code O} and four attribute items; the declared payload width,
     * which equals the mapset's {@code LENGTH=} for that field in every one of the 21 cases; the
     * absolute offset of the field group within the 575-byte image; and, derived from those, the
     * offsets of the payload item and of each attribute item.
     *
     * <p>The group offsets are stated explicitly rather than accumulated, and
     * {@link TransactionAddResponse#LAYOUT} then proves them: {@link RecordLayout} refuses any
     * layout whose spans leave a gap, overlap, or fail to sum to the declared 575 bytes, so a
     * transcription error here cannot survive class initialisation.
     */
    public enum ScreenField {

        /**
         * {@code TRNNAMEO PIC X(4)} - copybook line 152. Header: the transaction identifier,
         * {@code MOVE WS-TRANID TO TRNNAMEO} at {@code app/cbl/COTRN01C.cbl:249}.
         * {@code DFHMDF ATTRB=(ASKIP,FSET,NORM)} at {@code app/bms/COTRN01.bms:34}.
         */
        TRNNAMEO("TRNNAME", 12, 4),

        /**
         * {@code TITLE01O PIC X(40)} - copybook line 158. Header: the first title line,
         * {@code MOVE CCDA-TITLE01 TO TITLE01O} at line 247; the literal is
         * {@link ScreenTitles#CCDA_TITLE01}, whose {@link ScreenTitles#TITLE_LENGTH} is this same 40.
         */
        TITLE01O("TITLE01", 23, 40),

        /**
         * {@code CURDATEO PIC X(8)} - copybook line 164. Header: the current date as
         * {@code mm/dd/yy}, {@code MOVE WS-CURDATE-MM-DD-YY TO CURDATEO} at line 258; the width
         * matches {@link DateHeader#WS_CURDATE_MM_DD_YY_LENGTH}.
         */
        CURDATEO("CURDATE", 70, 8),

        /**
         * {@code PGMNAMEO PIC X(8)} - copybook line 170. Header: the program name,
         * {@code MOVE WS-PGMNAME TO PGMNAMEO} at line 250. Eight wide, like every program name.
         */
        PGMNAMEO("PGMNAME", 85, 8),

        /**
         * {@code TITLE02O PIC X(40)} - copybook line 176. Header: the second title line,
         * {@code MOVE CCDA-TITLE02 TO TITLE02O} at line 248; the literal is
         * {@link ScreenTitles#CCDA_TITLE02}.
         */
        TITLE02O("TITLE02", 100, 40),

        /**
         * {@code CURTIMEO PIC X(8)} - copybook line 182. Header: the current time as
         * {@code hh:mm:ss}, {@code MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO} at line 262; the width
         * matches {@link DateHeader#WS_CURTIME_HH_MM_SS_LENGTH}.
         */
        CURTIMEO("CURTIME", 147, 8),

        /**
         * {@code TRNIDINO PIC X(16)} - copybook line 188. The lookup key, echoed back.
         *
         * <p>The <strong>only</strong> enterable field on this screen:
         * {@code DFHMDF ATTRB=(FSET,IC,NORM,UNPROT)} at {@code app/bms/COTRN01.bms:85}, and the
         * cursor is placed on it by {@code MOVE -1 TO TRNIDINL} in {@code INITIALIZE-ALL-FIELDS}.
         * Its value becomes the record key: {@code MOVE TRNIDINI TO TRAN-ID} at line 173.
         */
        TRNIDINO("TRNIDIN", 162, 16),

        /**
         * {@code TRNIDO PIC X(16)} - copybook line 194. The transaction identifier read back from
         * the record, {@code MOVE TRAN-ID TO TRNIDI} at line 178. Protected
         * ({@code ATTRB=(ASKIP,NORM)}), as every displayed field on a view screen is.
         */
        TRNIDO("TRNID", 185, 16),

        /**
         * {@code CARDNUMO PIC X(16)} - copybook line 200. {@code MOVE TRAN-CARD-NUM TO CARDNUMI} at
         * line 179.
         *
         * <p><strong>Carried in full and unredacted</strong>, all sixteen digits, because the COBOL
         * displays all sixteen. Masking here would be a behaviour change dressed up as a security
         * improvement: practice B6 and gate G41 require the security posture to be neither weakened
         * nor unrequestedly strengthened.
         */
        CARDNUMO("CARDNUM", 208, 16),

        /** {@code TTYPCDO PIC X(2)} - copybook line 206. {@code MOVE TRAN-TYPE-CD} at line 180. */
        TTYPCDO("TTYPCD", 231, 2),

        /** {@code TCATCDO PIC X(4)} - copybook line 212. {@code MOVE TRAN-CAT-CD} at line 181. */
        TCATCDO("TCATCD", 240, 4),

        /** {@code TRNSRCO PIC X(10)} - copybook line 218. {@code MOVE TRAN-SOURCE} at line 182. */
        TRNSRCO("TRNSRC", 251, 10),

        /** {@code TDESCO PIC X(60)} - copybook line 224. {@code MOVE TRAN-DESC} at line 184. */
        TDESCO("TDESC", 268, 60),

        /**
         * {@code TRNAMTO PIC X(12)} - copybook line 230. The <strong>edited</strong> amount, mask
         * {@code +99999999.99}.
         *
         * <p>Twelve characters, not the record's eleven: {@code app/cbl/COTRN01C.cbl:49} declares
         * {@code 05 WS-TRAN-AMT PIC +99999999.99} and line 183 moves that onto the screen. See the
         * class documentation for why this stays a {@code String} and why the mask's missing ninth
         * integer digit is left truncating.
         */
        TRNAMTO("TRNAMT", 335, 12),

        /**
         * {@code TORIGDTO PIC X(10)} - copybook line 236. {@code MOVE TRAN-ORIG-TS} at line 185.
         * The record field is {@code PIC X(26)} in {@code app/cpy/CVTRA05Y.cpy}, so the move keeps
         * the leading ten characters - the date portion - and discards the rest on the right.
         */
        TORIGDTO("TORIGDT", 354, 10),

        /**
         * {@code TPROCDTO PIC X(10)} - copybook line 242. {@code MOVE TRAN-PROC-TS} at line 186;
         * the same 26-into-10 right truncation as {@link #TORIGDTO}.
         */
        TPROCDTO("TPROCDT", 371, 10),

        /**
         * {@code MIDO PIC X(9)} - copybook line 248. {@code MOVE TRAN-MERCHANT-ID} at line 187.
         * Carried at full width and unredacted, for the reason given on {@link #CARDNUMO}.
         */
        MIDO("MID", 388, 9),

        /** {@code MNAMEO PIC X(30)} - copybook line 254. {@code MOVE TRAN-MERCHANT-NAME} at line 188. */
        MNAMEO("MNAME", 404, 30),

        /** {@code MCITYO PIC X(25)} - copybook line 260. {@code MOVE TRAN-MERCHANT-CITY} at line 189. */
        MCITYO("MCITY", 441, 25),

        /** {@code MZIPO PIC X(10)} - copybook line 266. {@code MOVE TRAN-MERCHANT-ZIP} at line 190. */
        MZIPO("MZIP", 473, 10),

        /**
         * {@code ERRMSGO PIC X(78)} - copybook line 272. The error line,
         * {@code MOVE WS-MESSAGE TO ERRMSGO OF COTRN1AO} at {@code app/cbl/COTRN01C.cbl:217} - one
         * of the seven stores this program makes through the output alias.
         * {@code DFHMDF ATTRB=(ASKIP,BRT,FSET)} at {@code app/bms/COTRN01.bms:259}. See
         * {@link TransactionAddResponse#WS_MESSAGE_LENGTH} for the 80-into-78 truncation.
         */
        ERRMSGO("ERRMSG", 490, 78);

        private final String baseName;
        private final int groupOffset;
        private final int payloadLength;

        ScreenField(String baseName, int groupOffset, int payloadLength) {
            this.baseName = baseName;
            this.groupOffset = groupOffset;
            this.payloadLength = payloadLength;
        }

        /**
         * The base name the six items of this field share, for example {@code TRNNAME}, spelled
         * exactly as {@code app/cpy-bms/COTRN01.CPY} spells it and exactly as the label on the
         * field's {@code DFHMDF} in {@code app/bms/COTRN01.bms}.
         *
         * @return the verbatim base name; never {@code null} or empty
         */
        public String baseName() {
            return baseName;
        }

        /**
         * The verbatim name of the payload item: the base name followed by {@code O}, for example
         * {@code TRNNAMEO}. Identical to {@link #name()}, and stated as a derivation so the
         * relationship to the base name is explicit rather than coincidental.
         *
         * @return the {@code xxxO} item name; never {@code null}
         */
        public String outputItemName() {
            return baseName + FieldAttributeSetter.OUTPUT_ITEM_SUFFIX;
        }

        /**
         * The verbatim name of the input alias: the base name followed by {@code I}, for example
         * {@code TRNNAMEI}. The same bytes as {@link #outputItemName()}, seen through
         * {@code COTRN1AI}.
         *
         * @return the {@code xxxI} item name; never {@code null}
         */
        public String inputItemName() {
            return baseName + "I";
        }

        /**
         * The verbatim name of the colour attribute item: the base name followed by {@code C}, for
         * example {@code TRNNAMEC}. This is the item {@code CSSETATY} moves {@code DFHRED} into.
         *
         * @return the {@code xxxC} item name; never {@code null}
         */
        public String colourItemName() {
            return baseName + FieldAttributeSetter.COLOUR_ITEM_SUFFIX;
        }

        /**
         * The verbatim name of the programmed-symbols attribute item, the base name followed by
         * {@code P}.
         *
         * @return the {@code xxxP} item name; never {@code null}
         */
        public String programmedSymbolsItemName() {
            return baseName + "P";
        }

        /**
         * The verbatim name of the highlight attribute item, the base name followed by {@code H}.
         *
         * @return the {@code xxxH} item name; never {@code null}
         */
        public String highlightItemName() {
            return baseName + "H";
        }

        /**
         * The verbatim name of the validation attribute item, the base name followed by {@code V}.
         *
         * @return the {@code xxxV} item name; never {@code null}
         */
        public String validationItemName() {
            return baseName + "V";
        }

        /**
         * This field's declared payload width in bytes, from the {@code xxxO} {@code PICTURE}
         * clause. Equal to the {@code LENGTH=} on the field's {@code DFHMDF} in all 21 cases.
         *
         * @return the width, at least 1
         */
        public int payloadLength() {
            return payloadLength;
        }

        /**
         * The absolute offset of this field's group - its leading {@code FILLER X(3)} - within the
         * 575-byte image.
         *
         * @return the group offset, at least {@link #TIOAPFX_PREFIX_LENGTH}
         */
        public int groupOffset() {
            return groupOffset;
        }

        /**
         * The absolute offset of the payload item: {@link #groupOffset()} plus the seven
         * attribute-prefix bytes.
         *
         * @return the payload offset
         */
        public int payloadOffset() {
            return groupOffset + FIELD_ATTRIBUTE_PREFIX_LENGTH;
        }

        /**
         * The absolute offset of the colour attribute item, three bytes past the group filler.
         *
         * @return the {@code xxxC} offset
         */
        public int colourItemOffset() {
            return groupOffset + FIELD_GROUP_FILLER_LENGTH;
        }

        /**
         * The absolute offset of the programmed-symbols attribute item.
         *
         * @return the {@code xxxP} offset
         */
        public int programmedSymbolsItemOffset() {
            return colourItemOffset() + ATTRIBUTE_ITEM_LENGTH;
        }

        /**
         * The absolute offset of the highlight attribute item.
         *
         * @return the {@code xxxH} offset
         */
        public int highlightItemOffset() {
            return programmedSymbolsItemOffset() + ATTRIBUTE_ITEM_LENGTH;
        }

        /**
         * The absolute offset of the validation attribute item.
         *
         * @return the {@code xxxV} offset
         */
        public int validationItemOffset() {
            return highlightItemOffset() + ATTRIBUTE_ITEM_LENGTH;
        }

        /**
         * The offset one past this field group's last byte, which is the next group's offset - or
         * {@link TransactionAddResponse#SYMBOLIC_MAP_LENGTH} for the last field.
         *
         * @return the exclusive end offset of the group
         */
        public int groupEndOffsetExclusive() {
            return groupOffset + FIELD_ATTRIBUTE_PREFIX_LENGTH + payloadLength;
        }

        /**
         * The payload item as a {@link FieldSpan} carrying its verbatim copybook name, for use with
         * {@link FixedWidthCodec}. Built on demand rather than cached, because a {@code FieldSpan}
         * is an immutable record and caching it would add static state for no benefit.
         *
         * @return the payload span; never {@code null}
         */
        public FieldSpan payloadSpan() {
            return FieldSpan.alphanumeric(outputItemName(), payloadOffset(), payloadLength);
        }

        /**
         * The colour attribute item as a {@link FieldSpan} carrying its verbatim copybook name.
         *
         * @return the {@code xxxC} span; never {@code null}
         */
        public FieldSpan colourSpan() {
            return FieldSpan.alphanumeric(colourItemName(), colourItemOffset(), ATTRIBUTE_ITEM_LENGTH);
        }

        /**
         * The programmed-symbols attribute item as a {@link FieldSpan}.
         *
         * @return the {@code xxxP} span; never {@code null}
         */
        public FieldSpan programmedSymbolsSpan() {
            return FieldSpan.alphanumeric(programmedSymbolsItemName(), programmedSymbolsItemOffset(),
                    ATTRIBUTE_ITEM_LENGTH);
        }

        /**
         * The highlight attribute item as a {@link FieldSpan}.
         *
         * @return the {@code xxxH} span; never {@code null}
         */
        public FieldSpan highlightSpan() {
            return FieldSpan.alphanumeric(highlightItemName(), highlightItemOffset(),
                    ATTRIBUTE_ITEM_LENGTH);
        }

        /**
         * The validation attribute item as a {@link FieldSpan}.
         *
         * @return the {@code xxxV} span; never {@code null}
         */
        public FieldSpan validationSpan() {
            return FieldSpan.alphanumeric(validationItemName(), validationItemOffset(),
                    ATTRIBUTE_ITEM_LENGTH);
        }

        /**
         * The group's leading {@code FILLER X(3)} as a {@link FieldSpan}. Declared, never dropped:
         * omitting a filler shifts every offset that follows it.
         *
         * @return the group filler span; never {@code null}
         */
        public FieldSpan groupFillerSpan() {
            return FieldSpan.filler(groupOffset, FIELD_GROUP_FILLER_LENGTH);
        }

        /**
         * A one-line description for diagnostics and for tracing a parity difference back to the
         * copybook, for example {@code TRNNAMEO PIC X(4) at 19 (group 12)}.
         *
         * @return the description; never {@code null}
         */
        public String describe() {
            return outputItemName() + " PIC X(" + payloadLength + ") at " + payloadOffset()
                    + " (group " + groupOffset + ")";
        }
    }

    // =================================================================================================
    // The 575-byte image layout, and the character-level PICTURE rules.
    // =================================================================================================

    /**
     * The complete {@code COTRN1AO} group layout: 106 spans describing all
     * {@link #SYMBOLIC_MAP_LENGTH} bytes - the leading {@code TIOAPFX} filler, then per field its
     * {@code FILLER X(3)}, its four single-byte attribute items and its payload item, in copybook
     * declaration order.
     *
     * <p>This constant is also the geometry proof. {@link RecordLayout} refuses to build a layout
     * whose spans leave a gap, overlap one another, or fail to sum to the declared record length, so
     * if any {@link ScreenField} offset or width were mistyped this field could not initialise and
     * the error would surface immediately, naming the offending span. That is why the group offsets
     * are written out explicitly rather than accumulated silently.
     *
     * <p>Every {@code FILLER} is declared. Dropping one would not merely lose three bytes, it would
     * shift every offset after it - which is exactly the failure mode gate G21 exists to catch.
     */
    public static final RecordLayout LAYOUT = buildLayout();

    /**
     * The single implementation of the {@code PIC X} width rules used by every setter on this class.
     *
     * <p>{@link Ct01Info} carries its own equivalent, because the carrier is declared on
     * {@link TransactionAddRequest} and owns the guards for its own six fields; both resolve to
     * {@link FixedWidthCodec}, so there is still exactly one implementation of the move rules in the
     * module.
     *
     * <p>Only character-level operations are taken from it -
     * {@link FixedWidthCodec#movePicX(String, int)} and
     * {@link FixedWidthCodec#movePic9(long, int)} - and neither converts anything to bytes, so the
     * code page this instance carries takes no part in any result produced through it. It is named
     * {@link StandardCharsets#US_ASCII} explicitly and never derived from the platform (practice
     * B8), and it is the code page of the authoritative fixtures under {@code app/data/ASCII}.
     *
     * <p>Every byte boundary - {@link #toFixedWidth(Charset)}, {@link #writeInto(FixedWidthRecord)},
     * {@link #fromFixedWidth(byte[], Charset)} and {@link #readFrom(FixedWidthRecord)} - takes its
     * code page from the caller and builds its own codec, because a fixed-width image is bytes in a
     * specific code page and this type is bound to none.
     *
     * <p>{@link FixedWidthCodec} is immutable and holds only its {@link Charset}, so one shared
     * instance is thread safe and is a constant rather than static mutable state (practice B9).
     * Delegating to it, instead of reimplementing pad and truncate here, keeps a single reviewable
     * implementation of the move rules in the module (practice B11).
     */
    private static final FixedWidthCodec PICTURE_RULES = new FixedWidthCodec(StandardCharsets.US_ASCII);

    // =================================================================================================
    // Storage.
    //
    // The 21 payload values live in one EnumMap keyed by the geometry table, so the table is the only
    // place a name or a width is written down and the 21 public accessors cannot drift away from it.
    // Each value is held at exactly its declared width at all times: the constructors establish that
    // and every setter maintains it, so no accessor and no serialiser has to defend against a short
    // or over-long value.
    //
    // The map is private and never exposed as a JSON member. The 21 explicit accessor pairs below are
    // what Jackson sees, which is what keeps the payload a 1:1 projection of the 21 DFHMDF fields
    // (gate G9).
    // =================================================================================================

    /** The 21 payload items, keyed by the geometry table; every value exactly its declared width. */
    private final Map<ScreenField, String> payloadItems = new EnumMap<>(ScreenField.class);

    /**
     * The four attribute items of each field - {@code xxxC}, {@code xxxP}, {@code xxxH},
     * {@code xxxV} - one immutable quad per field. Highlight metadata, never a JSON payload member
     * (AAP &sect;0.6.3).
     */
    private final Map<ScreenField, AttributeQuad> attributeItems = new EnumMap<>(ScreenField.class);

    /** {@code CDEMO-TO-PROGRAM}, echoed so the client knows which program to call next. */
    private String nextProgram;

    /** The mapset of the next screen, seven wide like {@code CDEMO-LAST-MAPSET}. */
    private String nextMapset;

    /** The map of the next screen, seven wide like {@code CDEMO-LAST-MAP}. */
    private String nextMap;

    /** The 160-byte {@code CARDDEMO-COMMAREA}, echoed in full and never widened. */
    private NavigationContext navigationContext;

    /**
     * The 58-byte {@code CDEMO-CT01-INFO} cursor, echoed so the client can send it back.
     *
     * <p>Deliberately {@link TransactionAddRequest.Ct01Info} - the very type the paired request
     * carries, under the very same property name - and not a second implementation of the same
     * copybook group. There was one here once, spelled {@code CardDemoCt01Info} and reached through
     * {@code cardDemoCt01Info}: byte-for-byte identical in layout, differing only in what it was
     * called. That difference alone was enough to break the mechanism this member exists for, because
     * a client cannot echo state whose schema changes between the response it was sent and the request
     * it must send back - it would have to rename the property and reshape nothing, which is
     * transformation work with no purpose. One carrier, one name, both directions.
     */
    private Ct01Info ct01Info;

    // =================================================================================================
    // Construction. No Spring context, no builder and no framework: a unit or parity test constructs
    // an instance directly with new (practice B10), and Jackson binds through the no-argument
    // constructor and the setters below.
    // =================================================================================================

    /**
     * Creates a response in the state {@code COTRN01C} holds after {@code INITIALIZE-ALL-FIELDS}
     * ({@code app/cbl/COTRN01C.cbl:309-326}): every payload item space-filled to its declared width,
     * every attribute item at its {@linkplain AttributeQuad#defaults() no-change default}, the
     * navigation targets set to this screen's own mapset and map, an empty
     * {@link NavigationContext} and a fresh cursor.
     *
     * <p>Space-filled rather than {@code null}: a COBOL alphanumeric item has no absent state, and
     * {@code MOVE SPACES} is what the program actually performs. {@link #getNextProgram()} starts
     * space-filled rather than guessing a target, because the COBOL only defaults
     * {@code CDEMO-TO-PROGRAM} to {@code COSGN00C} at the moment it transfers
     * ({@code app/cbl/COTRN01C.cbl:200-201}), and that decision belongs to the controller.
     */
    public TransactionAddResponse() {
        for (ScreenField field : ScreenField.values()) {
            payloadItems.put(field, spaces(field.payloadLength()));
            attributeItems.put(field, AttributeQuad.defaults());
        }
        this.nextProgram = spaces(NEXT_PROGRAM_LENGTH);
        this.nextMapset = movePicX(MAPSET_NAME, NEXT_MAPSET_LENGTH);
        this.nextMap = movePicX(MAP_NAME, NEXT_MAP_LENGTH);
        this.navigationContext = NavigationContext.empty();
        this.ct01Info = new Ct01Info();
    }

    // =================================================================================================
    // The 21 payload accessors, in COTRN1AO declaration order. Each getter is a JSON member named
    // after its xxxO item lowercased; each setter applies the PIC X move rule, so an over-long value
    // is truncated on the RIGHT exactly as COBOL truncates it and a short one is padded on the right.
    // =================================================================================================

    /**
     * {@code TRNNAMEO PIC X(4)} - the transaction identifier in the header.
     *
     * @return the value, always exactly 4 characters
     */
    public String getTrnnameo() {
        return payloadItems.get(ScreenField.TRNNAMEO);
    }

    /**
     * Sets {@code TRNNAMEO}, applying the {@code PIC X(4)} move rule.
     *
     * @param trnnameo the sending value; never {@code null}
     * @throws NullPointerException if {@code trnnameo} is {@code null}
     */
    public void setTrnnameo(String trnnameo) {
        setPayload(ScreenField.TRNNAMEO, trnnameo);
    }

    /**
     * {@code TITLE01O PIC X(40)} - the first title line, {@link ScreenTitles#CCDA_TITLE01}.
     *
     * @return the value, always exactly 40 characters
     */
    public String getTitle01o() {
        return payloadItems.get(ScreenField.TITLE01O);
    }

    /**
     * Sets {@code TITLE01O}, applying the {@code PIC X(40)} move rule.
     *
     * @param title01o the sending value; never {@code null}
     * @throws NullPointerException if {@code title01o} is {@code null}
     */
    public void setTitle01o(String title01o) {
        setPayload(ScreenField.TITLE01O, title01o);
    }

    /**
     * {@code CURDATEO PIC X(8)} - the current date as {@code mm/dd/yy}.
     *
     * @return the value, always exactly 8 characters
     */
    public String getCurdateo() {
        return payloadItems.get(ScreenField.CURDATEO);
    }

    /**
     * Sets {@code CURDATEO}, applying the {@code PIC X(8)} move rule.
     *
     * @param curdateo the sending value; never {@code null}
     * @throws NullPointerException if {@code curdateo} is {@code null}
     */
    public void setCurdateo(String curdateo) {
        setPayload(ScreenField.CURDATEO, curdateo);
    }

    /**
     * {@code PGMNAMEO PIC X(8)} - the program name in the header.
     *
     * @return the value, always exactly 8 characters
     */
    public String getPgmnameo() {
        return payloadItems.get(ScreenField.PGMNAMEO);
    }

    /**
     * Sets {@code PGMNAMEO}, applying the {@code PIC X(8)} move rule.
     *
     * @param pgmnameo the sending value; never {@code null}
     * @throws NullPointerException if {@code pgmnameo} is {@code null}
     */
    public void setPgmnameo(String pgmnameo) {
        setPayload(ScreenField.PGMNAMEO, pgmnameo);
    }

    /**
     * {@code TITLE02O PIC X(40)} - the second title line, {@link ScreenTitles#CCDA_TITLE02}.
     *
     * @return the value, always exactly 40 characters
     */
    public String getTitle02o() {
        return payloadItems.get(ScreenField.TITLE02O);
    }

    /**
     * Sets {@code TITLE02O}, applying the {@code PIC X(40)} move rule.
     *
     * @param title02o the sending value; never {@code null}
     * @throws NullPointerException if {@code title02o} is {@code null}
     */
    public void setTitle02o(String title02o) {
        setPayload(ScreenField.TITLE02O, title02o);
    }

    /**
     * {@code CURTIMEO PIC X(8)} - the current time as {@code hh:mm:ss}.
     *
     * @return the value, always exactly 8 characters
     */
    public String getCurtimeo() {
        return payloadItems.get(ScreenField.CURTIMEO);
    }

    /**
     * Sets {@code CURTIMEO}, applying the {@code PIC X(8)} move rule.
     *
     * @param curtimeo the sending value; never {@code null}
     * @throws NullPointerException if {@code curtimeo} is {@code null}
     */
    public void setCurtimeo(String curtimeo) {
        setPayload(ScreenField.CURTIMEO, curtimeo);
    }

    /**
     * {@code TRNIDINO PIC X(16)} - the lookup key echoed back; the only enterable field on this
     * screen.
     *
     * @return the value, always exactly 16 characters
     */
    public String getTrnidino() {
        return payloadItems.get(ScreenField.TRNIDINO);
    }

    /**
     * Sets {@code TRNIDINO}, applying the {@code PIC X(16)} move rule.
     *
     * @param trnidino the sending value; never {@code null}
     * @throws NullPointerException if {@code trnidino} is {@code null}
     */
    public void setTrnidino(String trnidino) {
        setPayload(ScreenField.TRNIDINO, trnidino);
    }

    /**
     * {@code TRNIDO PIC X(16)} - the transaction identifier read back from the record.
     *
     * @return the value, always exactly 16 characters
     */
    public String getTrnido() {
        return payloadItems.get(ScreenField.TRNIDO);
    }

    /**
     * Sets {@code TRNIDO}, applying the {@code PIC X(16)} move rule.
     *
     * @param trnido the sending value; never {@code null}
     * @throws NullPointerException if {@code trnido} is {@code null}
     */
    public void setTrnido(String trnido) {
        setPayload(ScreenField.TRNIDO, trnido);
    }

    /**
     * {@code CARDNUMO PIC X(16)} - the card number, all sixteen digits, <strong>unmasked</strong>.
     *
     * <p>No redaction, no truncation, no {@code @JsonIgnore}: the COBOL displays the full number
     * and this projection reproduces that (practice B6, gate G41).
     *
     * @return the value, always exactly 16 characters
     */
    public String getCardnumo() {
        return payloadItems.get(ScreenField.CARDNUMO);
    }

    /**
     * Sets {@code CARDNUMO}, applying the {@code PIC X(16)} move rule.
     *
     * @param cardnumo the sending value; never {@code null}
     * @throws NullPointerException if {@code cardnumo} is {@code null}
     */
    public void setCardnumo(String cardnumo) {
        setPayload(ScreenField.CARDNUMO, cardnumo);
    }

    /**
     * {@code TTYPCDO PIC X(2)} - the transaction type code.
     *
     * @return the value, always exactly 2 characters
     */
    public String getTtypcdo() {
        return payloadItems.get(ScreenField.TTYPCDO);
    }

    /**
     * Sets {@code TTYPCDO}, applying the {@code PIC X(2)} move rule.
     *
     * @param ttypcdo the sending value; never {@code null}
     * @throws NullPointerException if {@code ttypcdo} is {@code null}
     */
    public void setTtypcdo(String ttypcdo) {
        setPayload(ScreenField.TTYPCDO, ttypcdo);
    }

    /**
     * {@code TCATCDO PIC X(4)} - the transaction category code.
     *
     * @return the value, always exactly 4 characters
     */
    public String getTcatcdo() {
        return payloadItems.get(ScreenField.TCATCDO);
    }

    /**
     * Sets {@code TCATCDO}, applying the {@code PIC X(4)} move rule.
     *
     * @param tcatcdo the sending value; never {@code null}
     * @throws NullPointerException if {@code tcatcdo} is {@code null}
     */
    public void setTcatcdo(String tcatcdo) {
        setPayload(ScreenField.TCATCDO, tcatcdo);
    }

    /**
     * {@code TRNSRCO PIC X(10)} - the transaction source.
     *
     * @return the value, always exactly 10 characters
     */
    public String getTrnsrco() {
        return payloadItems.get(ScreenField.TRNSRCO);
    }

    /**
     * Sets {@code TRNSRCO}, applying the {@code PIC X(10)} move rule.
     *
     * @param trnsrco the sending value; never {@code null}
     * @throws NullPointerException if {@code trnsrco} is {@code null}
     */
    public void setTrnsrco(String trnsrco) {
        setPayload(ScreenField.TRNSRCO, trnsrco);
    }

    /**
     * {@code TDESCO PIC X(60)} - the transaction description.
     *
     * @return the value, always exactly 60 characters
     */
    public String getTdesco() {
        return payloadItems.get(ScreenField.TDESCO);
    }

    /**
     * Sets {@code TDESCO}, applying the {@code PIC X(60)} move rule.
     *
     * @param tdesco the sending value; never {@code null}
     * @throws NullPointerException if {@code tdesco} is {@code null}
     */
    public void setTdesco(String tdesco) {
        setPayload(ScreenField.TDESCO, tdesco);
    }

    /**
     * {@code TRNAMTO PIC X(12)} - the <strong>edited</strong> amount, mask {@code +99999999.99}.
     *
     * <p>A {@code String}, never a {@code BigDecimal} and never a floating-point type: this is the
     * twelve-character edited image {@code WS-TRAN-AMT} produced, not the record's
     * {@code PIC S9(09)V99} value. See the class documentation.
     *
     * @return the value, always exactly 12 characters
     */
    public String getTrnamto() {
        return payloadItems.get(ScreenField.TRNAMTO);
    }

    /**
     * Sets {@code TRNAMTO}, applying the {@code PIC X(12)} move rule.
     *
     * @param trnamto the sending value, expected to carry the {@code +99999999.99} edit mask; never
     *                {@code null}
     * @throws NullPointerException if {@code trnamto} is {@code null}
     */
    public void setTrnamto(String trnamto) {
        setPayload(ScreenField.TRNAMTO, trnamto);
    }

    /**
     * {@code TORIGDTO PIC X(10)} - the origination date, the leading ten characters of the
     * record's 26-byte timestamp.
     *
     * @return the value, always exactly 10 characters
     */
    public String getTorigdto() {
        return payloadItems.get(ScreenField.TORIGDTO);
    }

    /**
     * Sets {@code TORIGDTO}, applying the {@code PIC X(10)} move rule - which is what performs the
     * 26-into-10 right truncation when a full timestamp is supplied.
     *
     * @param torigdto the sending value; never {@code null}
     * @throws NullPointerException if {@code torigdto} is {@code null}
     */
    public void setTorigdto(String torigdto) {
        setPayload(ScreenField.TORIGDTO, torigdto);
    }

    /**
     * {@code TPROCDTO PIC X(10)} - the processing date, likewise the leading ten characters of a
     * 26-byte timestamp.
     *
     * @return the value, always exactly 10 characters
     */
    public String getTprocdto() {
        return payloadItems.get(ScreenField.TPROCDTO);
    }

    /**
     * Sets {@code TPROCDTO}, applying the {@code PIC X(10)} move rule.
     *
     * @param tprocdto the sending value; never {@code null}
     * @throws NullPointerException if {@code tprocdto} is {@code null}
     */
    public void setTprocdto(String tprocdto) {
        setPayload(ScreenField.TPROCDTO, tprocdto);
    }

    /**
     * {@code MIDO PIC X(9)} - the merchant identifier, at full width and <strong>unmasked</strong>
     * for the same reason as {@link #getCardnumo()}.
     *
     * @return the value, always exactly 9 characters
     */
    public String getMido() {
        return payloadItems.get(ScreenField.MIDO);
    }

    /**
     * Sets {@code MIDO}, applying the {@code PIC X(9)} move rule.
     *
     * @param mido the sending value; never {@code null}
     * @throws NullPointerException if {@code mido} is {@code null}
     */
    public void setMido(String mido) {
        setPayload(ScreenField.MIDO, mido);
    }

    /**
     * {@code MNAMEO PIC X(30)} - the merchant name.
     *
     * @return the value, always exactly 30 characters
     */
    public String getMnameo() {
        return payloadItems.get(ScreenField.MNAMEO);
    }

    /**
     * Sets {@code MNAMEO}, applying the {@code PIC X(30)} move rule.
     *
     * @param mnameo the sending value; never {@code null}
     * @throws NullPointerException if {@code mnameo} is {@code null}
     */
    public void setMnameo(String mnameo) {
        setPayload(ScreenField.MNAMEO, mnameo);
    }

    /**
     * {@code MCITYO PIC X(25)} - the merchant city.
     *
     * @return the value, always exactly 25 characters
     */
    public String getMcityo() {
        return payloadItems.get(ScreenField.MCITYO);
    }

    /**
     * Sets {@code MCITYO}, applying the {@code PIC X(25)} move rule.
     *
     * @param mcityo the sending value; never {@code null}
     * @throws NullPointerException if {@code mcityo} is {@code null}
     */
    public void setMcityo(String mcityo) {
        setPayload(ScreenField.MCITYO, mcityo);
    }

    /**
     * {@code MZIPO PIC X(10)} - the merchant postal code.
     *
     * @return the value, always exactly 10 characters
     */
    public String getMzipo() {
        return payloadItems.get(ScreenField.MZIPO);
    }

    /**
     * Sets {@code MZIPO}, applying the {@code PIC X(10)} move rule.
     *
     * @param mzipo the sending value; never {@code null}
     * @throws NullPointerException if {@code mzipo} is {@code null}
     */
    public void setMzipo(String mzipo) {
        setPayload(ScreenField.MZIPO, mzipo);
    }

    /**
     * {@code ERRMSGO PIC X(78)} - the error line, fed by {@code WS-MESSAGE}.
     *
     * <p>Returned untrimmed at its full 78 characters: the trailing spaces are part of the field's
     * value, and the parity differ compares them.
     *
     * @return the value, always exactly 78 characters
     */
    public String getErrmsgo() {
        return payloadItems.get(ScreenField.ERRMSGO);
    }

    /**
     * Sets {@code ERRMSGO}, applying the {@code PIC X(78)} move rule.
     *
     * <p>A message longer than 78 characters loses its tail on the right, which is what a COBOL
     * move of the 80-byte {@code WS-MESSAGE} into this 78-byte field does. The standard texts in
     * {@link SystemMessages} are 50 characters and are unaffected.
     *
     * @param errmsgo the sending value; never {@code null}
     * @throws NullPointerException if {@code errmsgo} is {@code null}
     */
    public void setErrmsgo(String errmsgo) {
        setPayload(ScreenField.ERRMSGO, errmsgo);
    }

    // =================================================================================================
    // Table-driven access to the same 21 items. Not JSON members - these take an argument, so no
    // Jackson property can be derived from them - and they exist so the codec, the parity harness and
    // FieldAttributeSetter can address a field by its geometry rather than by 21 hard-coded calls.
    // =================================================================================================

    /**
     * Reads one payload item by field, untrimmed at its declared width.
     *
     * @param field which field to read; never {@code null}
     * @return the value, exactly {@code field.payloadLength()} characters
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public String payload(ScreenField field) {
        Objects.requireNonNull(field, "A field is required to read a payload item");
        return payloadItems.get(field);
    }

    /**
     * Writes one payload item by field, applying that field's {@code PIC X} move rule: padded on the
     * right when short, truncated on the right when long.
     *
     * @param field which field to write; never {@code null}
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code field} or {@code value} is {@code null}
     */
    public void setPayload(ScreenField field, String value) {
        Objects.requireNonNull(field, "A field is required to write a payload item");
        Objects.requireNonNull(value, "A sending value is required; to blank a screen field move an "
                + "empty string or spaces explicitly, because a COBOL alphanumeric item has no "
                + "absent state");
        payloadItems.put(field, movePicX(value, field.payloadLength()));
    }

    /**
     * The 21 payload items as an immutable map keyed by the geometry table, in declaration order -
     * the form the parity differ compares field by field.
     *
     * <p>Not a JSON member: the wire form is the 21 named accessors above, and exposing this map as
     * well would put every field on the wire twice and break the 1:1 projection gate G9.
     *
     * @return an unmodifiable snapshot; never {@code null}
     */
    @JsonIgnore
    public Map<ScreenField, String> payloadItems() {
        return Collections.unmodifiableMap(new EnumMap<>(payloadItems));
    }

    // =================================================================================================
    // Highlight metadata. Per AAP 0.6.3 the xxxC / xxxP / xxxH / xxxV quad is metadata and never a
    // JSON payload member, so every accessor here is @JsonIgnore'd.
    //
    // The CSSETATY DECISION IS NOT MADE HERE. FieldAttributeSetter owns it and takes REENTER as an
    // explicit boolean; this class only provides the two items that decision writes into.
    // =================================================================================================

    /**
     * The four attribute items of one field.
     *
     * @param field which field; never {@code null}
     * @return that field's quad; never {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public AttributeQuad attributes(ScreenField field) {
        Objects.requireNonNull(field, "A field is required to read its attribute items");
        return attributeItems.get(field);
    }

    /**
     * Replaces the four attribute items of one field.
     *
     * @param field      which field; never {@code null}
     * @param attributes the replacement quad; never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public void setAttributes(ScreenField field, AttributeQuad attributes) {
        Objects.requireNonNull(field, "A field is required to write its attribute items");
        Objects.requireNonNull(attributes, "An attribute quad is required");
        attributeItems.put(field, attributes);
    }

    /**
     * All 84 attribute items, as one immutable map of 21 quads in declaration order.
     *
     * @return an unmodifiable snapshot; never {@code null}
     */
    @JsonIgnore
    public Map<ScreenField, AttributeQuad> attributeItems() {
        return Collections.unmodifiableMap(new EnumMap<>(attributeItems));
    }

    /**
     * Applies a {@code CSSETATY} decision to one field: the reach {@code FieldAttributeSetter} needs
     * into both of the items the copybook writes.
     *
     * <p>Reproduces {@code app/cpy/CSSETATY.cpy:L18-L27} faithfully by <em>obeying</em> the decision
     * rather than re-deriving it. When the highlight assigns the colour item, {@code DFHRED} is moved
     * into this field's {@code xxxC} item; when it additionally assigns the output item - which
     * happens only in the {@code BLANK} case, because the copybook nests that move inside the colour
     * move - the literal {@code '*'} is moved into this field's {@code xxxO} item. When the highlight
     * assigns neither, nothing is touched and the field keeps the colour and the content the caller
     * already gave it.
     *
     * <p>Because {@link FieldAttributeSetter#resolve} returns
     * {@link FieldHighlight#none(String, String)} whenever the program is <strong>not</strong> in
     * {@code REENTER} context, a highlight is reachable on re-entry and unreachable on first entry -
     * which is precisely what gate G38 requires, and it holds here without this class ever testing
     * the context itself.
     *
     * @param field     the field the decision was made for; never {@code null}
     * @param highlight the decision, as returned by {@link FieldAttributeSetter}; never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public void applyHighlight(ScreenField field, FieldHighlight highlight) {
        Objects.requireNonNull(field, "A field is required to apply a highlight");
        Objects.requireNonNull(highlight, "A highlight decision is required; FieldAttributeSetter "
                + "returns FieldHighlight.none(..) rather than null when nothing is to be done");

        if (highlight.colourItemAssigned()) {
            // CSSETATY.cpy:L21-L22 - MOVE DFHRED TO (SCRNVAR2)C OF (MAPNAME3)O
            attributeItems.put(field, attributes(field).withColour(highlight.colourItemValue()));
        }
        if (highlight.outputItemAssigned()) {
            // CSSETATY.cpy:L24-L25 - MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O
            setPayload(field, highlight.outputItemValue());
        }
    }

    /**
     * Resolves the {@code CSSETATY} decision for one field through {@link FieldAttributeSetter} and
     * applies it, carrying this screen's own field and map identity into the decision for
     * diagnostics.
     *
     * <p>A convenience over {@link #applyHighlight(ScreenField, FieldHighlight)} that keeps the
     * decision where it belongs: the two flags and the {@code REENTER} boolean are passed straight
     * through to {@link FieldAttributeSetter#resolveFromFlags}, and nothing about the rule is
     * restated here.
     *
     * @param field   the field being validated; never {@code null}
     * @param notOk   {@code true} when {@code FLG-<field>-NOT-OK} holds
     * @param blank   {@code true} when {@code FLG-<field>-BLANK} holds
     * @param reenter {@code true} when {@code CDEMO-PGM-REENTER} holds, that is when
     *                {@code CDEMO-PGM-CONTEXT} is 1
     * @return the decision that was applied, so a caller or a test can inspect it; never {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public FieldHighlight highlightField(ScreenField field, boolean notOk, boolean blank,
            boolean reenter) {
        Objects.requireNonNull(field, "A field is required to highlight");
        FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(notOk, blank, reenter,
                field.baseName(), MAP_NAME);
        applyHighlight(field, highlight);
        return highlight;
    }

    // =================================================================================================
    // Navigation. These three replace EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) at
    // app/cbl/COTRN01C.cbl:206 (gate G40). They are plain fixed-width strings: the client reads them
    // and issues the follow-up call itself, so there is no server-side forward and no redirect chain
    // (rule R6, gate G37). No sibling DTO is imported to express a target.
    // =================================================================================================

    /**
     * The program the client should call next - {@code CDEMO-TO-PROGRAM}, echoed from the
     * {@link NavigationContext} by the controller. Eight characters.
     *
     * @return the value, always exactly {@link #NEXT_PROGRAM_LENGTH} characters
     */
    public String getNextProgram() {
        return nextProgram;
    }

    /**
     * Sets the next program, applying the {@code PIC X(8)} move rule.
     *
     * @param nextProgram the program name; never {@code null}
     * @throws NullPointerException if {@code nextProgram} is {@code null}
     */
    public void setNextProgram(String nextProgram) {
        Objects.requireNonNull(nextProgram, "A next-program value is required; move spaces "
                + "explicitly to mean 'none'");
        this.nextProgram = movePicX(nextProgram, NEXT_PROGRAM_LENGTH);
    }

    /**
     * The mapset of the next screen. Seven characters, matching {@code CDEMO-LAST-MAPSET}; defaults
     * to this screen's own {@link #MAPSET_NAME}.
     *
     * @return the value, always exactly {@link #NEXT_MAPSET_LENGTH} characters
     */
    public String getNextMapset() {
        return nextMapset;
    }

    /**
     * Sets the next mapset, applying the {@code PIC X(7)} move rule.
     *
     * @param nextMapset the mapset name; never {@code null}
     * @throws NullPointerException if {@code nextMapset} is {@code null}
     */
    public void setNextMapset(String nextMapset) {
        Objects.requireNonNull(nextMapset, "A next-mapset value is required");
        this.nextMapset = movePicX(nextMapset, NEXT_MAPSET_LENGTH);
    }

    /**
     * The map of the next screen. Seven characters, matching {@code CDEMO-LAST-MAP}; defaults to
     * this screen's own {@link #MAP_NAME}.
     *
     * @return the value, always exactly {@link #NEXT_MAP_LENGTH} characters
     */
    public String getNextMap() {
        return nextMap;
    }

    /**
     * Sets the next map, applying the {@code PIC X(7)} move rule.
     *
     * @param nextMap the map name; never {@code null}
     * @throws NullPointerException if {@code nextMap} is {@code null}
     */
    public void setNextMap(String nextMap) {
        Objects.requireNonNull(nextMap, "A next-map value is required");
        this.nextMap = movePicX(nextMap, NEXT_MAP_LENGTH);
    }

    // =================================================================================================
    // Conversation state, carried in the payload and nowhere else.
    // =================================================================================================

    /**
     * The 160-byte {@code CARDDEMO-COMMAREA}, echoed so the client can send it back on the next
     * call. Fixed at {@link NavigationContext#COMMAREA_LENGTH} and shared by all 17 controllers -
     * it is never widened to carry this screen's cursor, which is why
     * {@link Ct01Info} exists as a separate 58-byte area.
     *
     * @return the context; never {@code null}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    /**
     * Sets the echoed commarea.
     *
     * @param navigationContext the context; never {@code null}
     * @throws NullPointerException if {@code navigationContext} is {@code null}
     */
    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = Objects.requireNonNull(navigationContext,
                "A navigation context is required; use NavigationContext.empty() for an unset "
                        + "commarea rather than null");
    }

    /**
     * The 58-byte {@code CDEMO-CT01-INFO} cursor, echoed so the client can send it back.
     *
     * @return the cursor; never {@code null}
     */
    public Ct01Info getCt01Info() {
        return ct01Info;
    }

    /**
     * Sets the echoed cursor.
     *
     * @param ct01Info the cursor; never {@code null}
     * @throws NullPointerException if {@code ct01Info} is {@code null}
     */
    public void setCt01Info(Ct01Info ct01Info) {
        this.ct01Info = Objects.requireNonNull(ct01Info,
                "A CT01 cursor is required; use a fresh Ct01Info for an unset cursor rather "
                        + "than null");
    }

    // =================================================================================================
    // Fixed-width rendering. Present so the projection can be proved byte for byte against the
    // copybook, which is what makes the 575-byte total an assertion rather than a comment.
    //
    // A Charset is always an argument, never a platform default (practice B8): a fixed-width image is
    // bytes in a specific code page, and this type is bound to none.
    // =================================================================================================

    /**
     * Renders this response as the {@link #SYMBOLIC_MAP_LENGTH}-byte {@code COTRN1AO} group image.
     *
     * <p>Every payload item is written into its declared span through the {@code PIC X} move rule and
     * every attribute item as the single raw byte it is, so the result is exactly 575 bytes with each
     * field at the offset {@code app/cpy-bms/COTRN01.CPY} gives it. All fillers are emitted, spaces
     * for the alphanumeric fillers, because dropping one would shift every offset after it (gate
     * G21).
     *
     * @param charset the code page to encode into, stated explicitly by the caller
     * @return a fresh array of exactly {@link #SYMBOLIC_MAP_LENGTH} bytes
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} does not encode every digit, sign overpunch
     *                                  character and the space to exactly one byte
     */
    public byte[] toFixedWidth(Charset charset) {
        Objects.requireNonNull(charset, "A charset is required to render COTRN1AO as bytes: a "
                + "fixed-width image is bytes in a specific code page, so the code page must be "
                + "stated explicitly and is never taken from the platform");
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        FixedWidthRecord record = codec.newRecord(LAYOUT);
        writeInto(record, codec);
        return record.toByteArray();
    }

    /**
     * Writes this response into an existing record area, using that record's own code page.
     *
     * @param record a record area of exactly {@link #SYMBOLIC_MAP_LENGTH} bytes
     * @throws NullPointerException     if {@code record} is {@code null}
     * @throws IllegalArgumentException if the record's declared length is not
     *                                  {@link #SYMBOLIC_MAP_LENGTH}
     */
    public void writeInto(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A record area is required to write COTRN1AO into");
        writeInto(record, new FixedWidthCodec(record.charset()));
    }

    /**
     * Rebuilds a response from a {@code COTRN1AO} group image.
     *
     * <p>The navigation targets, the commarea and the cursor are <strong>not</strong> carried in this
     * image - they live in the 218-byte passed area, not on the screen - so they come back at their
     * constructor defaults. Round-tripping the 21 payload items and the 84 attribute items through
     * {@link #toFixedWidth(Charset)} and back is lossless, which is the property that matters: an
     * {@code xxxO} item is an alias of its {@code xxxI} item, never a write-only field.
     *
     * @param bytes   the image, exactly {@link #SYMBOLIC_MAP_LENGTH} bytes
     * @param charset the code page the image is encoded in, stated explicitly by the caller
     * @return a response holding the 21 payload items and 84 attribute items the image carries
     * @throws NullPointerException     if {@code bytes} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code bytes.length} is not {@link #SYMBOLIC_MAP_LENGTH}
     */
    public static TransactionAddResponse fromFixedWidth(byte[] bytes, Charset charset) {
        Objects.requireNonNull(bytes, "An image is required to rebuild COTRN1AO");
        Objects.requireNonNull(charset, "A charset is required to decode a COTRN1AO image: the code "
                + "page must be stated explicitly and is never taken from the platform");
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return readFrom(codec.wrap(bytes, LAYOUT), codec);
    }

    /**
     * Reads a response out of an existing record area, using that record's own code page.
     *
     * @param record a record area of exactly {@link #SYMBOLIC_MAP_LENGTH} bytes
     * @return a response holding the items the record carries
     * @throws NullPointerException     if {@code record} is {@code null}
     * @throws IllegalArgumentException if the record's declared length is not
     *                                  {@link #SYMBOLIC_MAP_LENGTH}
     */
    public static TransactionAddResponse readFrom(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A record area is required to read COTRN1AO from");
        return readFrom(record, new FixedWidthCodec(record.charset()));
    }

    /**
     * Renders the {@link #PASSED_COMMAREA_LENGTH}-byte area {@code COTRN01C} passes on its
     * {@code XCTL} and {@code RETURN}: the 160-byte {@code CARDDEMO-COMMAREA} followed immediately by
     * the 58-byte {@code CDEMO-CT01-INFO} extension, exactly as
     * {@code app/cbl/COTRN01C.cbl:52-61} lays them out.
     *
     * <p>Concatenation, not widening. {@link NavigationContext} still renders its own 160 bytes and
     * the cursor still renders its own 58; the 218-byte total is their sum, which is what proves
     * neither area was reshaped to accommodate the other.
     *
     * @param charset the code page to encode into, stated explicitly by the caller
     * @return a fresh array of exactly {@link #PASSED_COMMAREA_LENGTH} bytes
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not single-byte for the digits and the
     *                                  space
     */
    public byte[] toPassedCommarea(Charset charset) {
        Objects.requireNonNull(charset, "A charset is required to render the passed commarea");
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        byte[] commarea = navigationContext.toFixedWidth(codec);
        byte[] cursor = ct01Info.toFixedWidth(codec);
        byte[] passed = new byte[PASSED_COMMAREA_LENGTH];
        System.arraycopy(commarea, 0, passed, 0, NavigationContext.COMMAREA_LENGTH);
        System.arraycopy(cursor, 0, passed, NavigationContext.COMMAREA_LENGTH, cursor.length);
        return passed;
    }

    /**
     * Rebuilds the commarea and the cursor from a {@link #PASSED_COMMAREA_LENGTH}-byte passed area,
     * leaving every screen field untouched.
     *
     * @param passed  the image, exactly {@link #PASSED_COMMAREA_LENGTH} bytes
     * @param charset the code page the image is encoded in, stated explicitly by the caller
     * @throws NullPointerException     if {@code passed} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code passed.length} is not
     *                                  {@link #PASSED_COMMAREA_LENGTH}
     */
    public void readPassedCommarea(byte[] passed, Charset charset) {
        Objects.requireNonNull(passed, "A passed-commarea image is required");
        Objects.requireNonNull(charset, "A charset is required to decode the passed commarea");
        if (passed.length != PASSED_COMMAREA_LENGTH) {
            throw new IllegalArgumentException("A passed commarea is exactly "
                    + PASSED_COMMAREA_LENGTH + " bytes - " + NavigationContext.COMMAREA_LENGTH
                    + " of CARDDEMO-COMMAREA plus " + Ct01Info.RECORD_LENGTH
                    + " of CDEMO-CT01-INFO - but " + passed.length + " byte(s) were supplied");
        }
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        byte[] commarea = new byte[NavigationContext.COMMAREA_LENGTH];
        byte[] cursor = new byte[Ct01Info.RECORD_LENGTH];
        System.arraycopy(passed, 0, commarea, 0, commarea.length);
        System.arraycopy(passed, commarea.length, cursor, 0, cursor.length);
        this.navigationContext = NavigationContext.fromFixedWidth(codec, commarea);
        this.ct01Info = Ct01Info.fromFixedWidth(codec, cursor);
    }

    /**
     * A diagnostic rendering naming the screen, its geometry and the lookup key - enough to identify
     * which screen instance a parity difference came from without dumping 575 bytes into a log.
     *
     * <p>Deliberately does not print {@link #getCardnumo()} or {@link #getMido()}: not to redact
     * them, since both are carried in the payload in full and unmasked by design, but because a log
     * line is not the payload and a card number has no diagnostic value here.
     *
     * @return the description; never {@code null}
     */
    @Override
    public String toString() {
        return "TransactionAddResponse[" + TRANSACTION_ID + '/' + PROGRAM_NAME
                + ", map=" + MAPSET_NAME + '.' + MAP_NAME
                + ", fields=" + PAYLOAD_FIELD_COUNT
                + ", image=" + SYMBOLIC_MAP_LENGTH + "B"
                + ", " + ScreenField.TRNIDINO.outputItemName() + "='" + getTrnidino() + '\''
                + ", nextProgram='" + nextProgram + '\''
                + ']';
    }

    // =================================================================================================
    // Internals.
    // =================================================================================================

    /**
     * The single write path for the 575-byte image. Each payload item is already exactly its span's
     * width, so the move rule pads and truncates nothing here - it is applied all the same, so that
     * one implementation of the rule governs every field in the module. Attribute items are written
     * as raw bytes, because a BMS attribute is a 3270 attribute byte and not a character.
     *
     * @param record the record area to write into
     * @param codec  the codec for the record's code page
     */
    private void writeInto(FixedWidthRecord record, FixedWidthCodec codec) {
        requireSymbolicMapWidth(record);
        for (ScreenField field : ScreenField.values()) {
            codec.writePicX(record, field.payloadSpan(), payloadItems.get(field));
            AttributeQuad quad = attributeItems.get(field);
            record.writeSpanBytes(field.colourSpan(), new byte[] {quad.colour()});
            record.writeSpanBytes(field.programmedSymbolsSpan(), new byte[] {quad.programmedSymbols()});
            record.writeSpanBytes(field.highlightSpan(), new byte[] {quad.highlight()});
            record.writeSpanBytes(field.validationSpan(), new byte[] {quad.validation()});
        }
    }

    /**
     * The single read path. Payload spans are read <strong>untrimmed</strong>, because a {@code PIC X}
     * field is space-padded to its full width and that padding is part of its value; attribute spans
     * are read as the single bytes they are.
     *
     * @param record the record area to read from
     * @param codec  the codec for the record's code page
     * @return a response holding what the record carries
     */
    private static TransactionAddResponse readFrom(FixedWidthRecord record, FixedWidthCodec codec) {
        requireSymbolicMapWidth(record);
        TransactionAddResponse response = new TransactionAddResponse();
        for (ScreenField field : ScreenField.values()) {
            response.payloadItems.put(field, codec.readPicX(record, field.payloadSpan()));
            response.attributeItems.put(field, new AttributeQuad(
                    record.readSpanBytes(field.colourSpan())[0],
                    record.readSpanBytes(field.programmedSymbolsSpan())[0],
                    record.readSpanBytes(field.highlightSpan())[0],
                    record.readSpanBytes(field.validationSpan())[0]));
        }
        return response;
    }

    /**
     * Rejects a record area that is not exactly the symbolic map's width, rather than writing into
     * whatever length it happens to have.
     *
     * @param record the record area to check
     * @throws IllegalArgumentException if the declared length is not {@link #SYMBOLIC_MAP_LENGTH}
     */
    private static void requireSymbolicMapWidth(FixedWidthRecord record) {
        if (record.recordLength() != SYMBOLIC_MAP_LENGTH) {
            throw new IllegalArgumentException("The COTRN1AO group is exactly "
                    + SYMBOLIC_MAP_LENGTH + " bytes - " + TIOAPFX_PREFIX_LENGTH + " of TIOAPFX "
                    + "prefix, " + PAYLOAD_FIELD_COUNT + " x " + FIELD_ATTRIBUTE_PREFIX_LENGTH
                    + " of attribute prefixes and " + TOTAL_PAYLOAD_WIDTH + " of payload - but the "
                    + "record area declares " + record.recordLength() + " byte(s)");
        }
    }

    /**
     * Sums the 21 declared payload widths from the geometry table, so
     * {@link #TOTAL_PAYLOAD_WIDTH} is derived rather than asserted.
     *
     * @return the sum, 416 for this screen
     */
    private static int sumPayloadWidths() {
        int total = 0;
        for (ScreenField field : ScreenField.values()) {
            total += field.payloadLength();
        }
        return total;
    }

    /**
     * Builds the 575-byte layout from the geometry table, in copybook declaration order: the leading
     * {@code TIOAPFX} filler, then per field its group filler, its four attribute items and its
     * payload item.
     *
     * @return the validated layout
     * @throws IllegalArgumentException if the spans do not tile exactly
     *                                  {@link #SYMBOLIC_MAP_LENGTH} bytes, which is how a mistyped
     *                                  offset or width in {@link ScreenField} is caught
     */
    private static RecordLayout buildLayout() {
        List<FieldSpan> spans = new ArrayList<>();
        spans.add(FieldSpan.filler(0, TIOAPFX_PREFIX_LENGTH));
        for (ScreenField field : ScreenField.values()) {
            spans.add(field.groupFillerSpan());
            spans.add(field.colourSpan());
            spans.add(field.programmedSymbolsSpan());
            spans.add(field.highlightSpan());
            spans.add(field.validationSpan());
            spans.add(field.payloadSpan());
        }
        return new RecordLayout(SYMBOLIC_MAP_LENGTH, spans);
    }

    /**
     * The {@code PIC X} move rule, applied through the module's single implementation of it.
     *
     * @param value  the sending value; never {@code null}
     * @param length the receiver's declared width
     * @return an image of exactly {@code length} characters
     */
    private static String movePicX(String value, int length) {
        Objects.requireNonNull(value, "A sending value is required for an alphanumeric MOVE");
        return PICTURE_RULES.movePicX(value, length);
    }

    /**
     * A run of spaces, the state a COBOL {@code MOVE SPACES} leaves an alphanumeric item in.
     *
     * @param length how many spaces
     * @return the image, exactly {@code length} characters
     */
    private static String spaces(int length) {
        return " ".repeat(length);
    }

    // =================================================================================================
    // Nested types.
    // =================================================================================================

    /**
     * The four attribute items of one screen field: {@code xxxC} (colour), {@code xxxP} (programmed
     * symbols), {@code xxxH} (highlight) and {@code xxxV} (validation), as declared for every field
     * in {@code 01 COTRN1AO} - for example {@code TRNNAMEC}, {@code TRNNAMEP}, {@code TRNNAMEH} and
     * {@code TRNNAMEV} at {@code app/cpy-bms/COTRN01.CPY:148-151}.
     *
     * <p>Highlight metadata, never a JSON payload member (AAP &sect;0.6.3). Each item is a
     * {@code byte} rather than a character because a BMS attribute is a 3270 attribute byte -
     * {@link BmsAttributes#DFHRED} is {@code 0xF2}, not the letter {@code R} - and typing it as a
     * byte makes it impossible to move an attribute into a payload item by mistake.
     *
     * <p>Immutable, with {@code with…} methods that return a replacement. That keeps the quad free of
     * mutable state while still letting {@code FieldAttributeSetter}'s decision be applied to one
     * item without disturbing the other three.
     *
     * @param colour            the {@code xxxC} item - the extended colour; the item
     *                          {@code CSSETATY} moves {@link BmsAttributes#DFHRED} into
     * @param programmedSymbols the {@code xxxP} item - the programmed-symbol set
     * @param highlight         the {@code xxxH} item - the extended highlight
     * @param validation        the {@code xxxV} item - the validation attribute
     */
    public record AttributeQuad(byte colour, byte programmedSymbols, byte highlight, byte validation) {

        /**
         * The value every attribute item holds until a program assigns one: {@code 0x00}.
         *
         * <p>In a BMS symbolic map an unassigned attribute item is {@code LOW-VALUES}, which the
         * terminal reads as "leave this attribute as the map defined it". {@code 0x00} is also
         * {@link BmsAttributes#DFHDFCOL}, the default-colour value, so naming it through that
         * constant states the intent rather than hiding a bare zero.
         */
        public static final byte NO_CHANGE = BmsAttributes.DFHDFCOL;

        /**
         * A quad with all four items at {@link #NO_CHANGE} - the state a field's attributes are in
         * before any program touches them.
         *
         * @return the default quad; never {@code null}
         */
        public static AttributeQuad defaults() {
            return new AttributeQuad(NO_CHANGE, NO_CHANGE, NO_CHANGE, NO_CHANGE);
        }

        /**
         * This quad with a different colour item, the move {@code CSSETATY} performs at
         * {@code app/cpy/CSSETATY.cpy:L21-L22}.
         *
         * @param newColour the replacement {@code xxxC} value, {@link BmsAttributes#DFHRED} in the
         *                  error case
         * @return a new quad; never {@code null}
         */
        public AttributeQuad withColour(byte newColour) {
            return new AttributeQuad(newColour, programmedSymbols, highlight, validation);
        }

        /**
         * This quad with a different programmed-symbols item.
         *
         * @param newProgrammedSymbols the replacement {@code xxxP} value
         * @return a new quad; never {@code null}
         */
        public AttributeQuad withProgrammedSymbols(byte newProgrammedSymbols) {
            return new AttributeQuad(colour, newProgrammedSymbols, highlight, validation);
        }

        /**
         * This quad with a different highlight item.
         *
         * @param newHighlight the replacement {@code xxxH} value
         * @return a new quad; never {@code null}
         */
        public AttributeQuad withHighlight(byte newHighlight) {
            return new AttributeQuad(colour, programmedSymbols, newHighlight, validation);
        }

        /**
         * This quad with a different validation item.
         *
         * @param newValidation the replacement {@code xxxV} value
         * @return a new quad; never {@code null}
         */
        public AttributeQuad withValidation(byte newValidation) {
            return new AttributeQuad(colour, programmedSymbols, highlight, newValidation);
        }

        /**
         * Whether the colour item carries {@link BmsAttributes#DFHRED}, that is whether this field is
         * currently highlighted as being in error.
         *
         * @return {@code true} when the colour item is {@code DFHRED}
         */
        public boolean colouredRed() {
            return colour == BmsAttributes.DFHRED;
        }

        /**
         * Whether all four items are still {@link #NO_CHANGE}.
         *
         * @return {@code true} when no attribute has been assigned
         */
        public boolean unassigned() {
            return colour == NO_CHANGE && programmedSymbols == NO_CHANGE && highlight == NO_CHANGE
                    && validation == NO_CHANGE;
        }
    }
}
