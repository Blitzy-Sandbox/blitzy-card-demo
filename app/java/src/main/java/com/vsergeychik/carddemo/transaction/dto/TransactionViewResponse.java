package com.vsergeychik.carddemo.transaction.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
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
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The outbound REST payload for CSD transaction {@code CT02}, program
 * {@code app/cbl/COTRN02C.cbl}, projected field for field from the {@code xxxO} items of
 * {@code 01 COTRN2AO REDEFINES COTRN2AI} in {@code app/cpy-bms/COTRN02.CPY} (line 145) and their
 * name-labelled {@code DFHMDF} definitions in {@code app/bms/COTRN02.bms}.
 *
 * <h2>Risk R-B: this class's name contradicts its source. Read this first.</h2>
 * The migration prompt mandates the name <strong>{@code TransactionViewResponse}</strong>, but the
 * program this type projects <strong>adds</strong> a transaction. The evidence is unanimous and was
 * re-verified against the checkout rather than taken on trust:
 * <ul>
 *   <li>{@code app/cbl/COTRN02C.cbl} declares
 *       {@code Function    : Add a new Transaction to TRANSACT file}.</li>
 *   <li>{@code app/bms/COTRN02.bms} paints the literal {@code INITIAL='Add Transaction'}.</li>
 *   <li>{@code README.md} lines 213 to 231 independently document {@code CT02} as
 *       "Transaction Add".</li>
 *   <li>This map carries {@code ACTIDIN X(11)}, {@code CARDNIN X(16)} and {@code CONFIRM X(1)},
 *       has <strong>no</strong> {@code TRNIDIN} and no {@code TRNID}, and <strong>14 of its
 *       21</strong> fields are {@code UNPROT} - an input form, not a display.</li>
 *   <li>The sibling {@code COTRN01} map is the real view screen: it <em>does</em> declare
 *       {@code TRNIDIN}, paints {@code INITIAL='View Transaction'}, has no {@code CONFIRM}, and
 *       has exactly <strong>one</strong> {@code UNPROT} field. {@code COTRN01C} declares
 *       {@code Function    : View a Transaction from TRANSACT file}.</li>
 * </ul>
 *
 * <p>This is Conflict Set 1 of the Agent Action Plan section 0.1.8, which calls it the highest-risk
 * naming ambiguity in the plan, and it is logged as risk <strong>R-B</strong> in section 0.9.12.
 *
 * <p>Rule <strong>R1</strong> resolves it: <em>the name comes from the prompt, the field set and the
 * behaviour come from the paired copybook and program.</em> So the prompt's name is honoured
 * verbatim and the source's 21 fields are projected verbatim. Concretely, and deliberately:
 * <ul>
 *   <li>The input and confirmation fields are <strong>not</strong> stripped to make the type look
 *       like a read-only view.</li>
 *   <li>{@code TRNIDINO} and {@code TRNIDO} are <strong>not</strong> added. They belong to
 *       {@code COTRN01} and do not exist in this map. {@code COTRN02C} derives the new transaction
 *       identifier by browsing {@code TRANSACT} backward ({@code STARTBR} / {@code READPREV} /
 *       {@code ENDBR}); it is never a screen field.</li>
 * </ul>
 * Per practices <strong>B4</strong> and <strong>B12</strong> the conflict is documented here rather
 * than silently corrected, so that no reader is misled by the class name and no later change
 * "fixes" the field set to match it.
 *
 * <h2>{@code xxxI} and {@code xxxO} are the same bytes</h2>
 * {@code COTRN2AO REDEFINES COTRN2AI}, and the two views spend an identical seven prefix bytes per
 * field - {@code AI} as {@code xxxL COMP PIC S9(4)} plus {@code xxxF PICTURE X} plus
 * {@code FILLER X(4)}, {@code AO} as {@code FILLER X(3)} plus {@code xxxC}, {@code xxxP},
 * {@code xxxH} and {@code xxxV} - so each field's {@code I} and {@code O} items sit at the
 * <em>identical</em> offset. They are storage aliases, not distinct fields, which is why this type
 * and its request sibling are field-identical: the split is a directional projection convention,
 * not a difference in shape. {@code COTRN02C} writes through both aliases - for example
 * {@code :386} and {@code :485} store the edited amount into the {@code ...AI} alias while
 * preparing to send, and {@code :113}, {@code :122} and {@code :520} write the {@code ...AO} alias.
 * An {@code xxxO} item is therefore <strong>never</strong> write-only, and the round trip through
 * this type must be lossless.
 *
 * <h2>Statelessness</h2>
 * All conversation state travels in the payload: the 160-byte {@link NavigationContext} commarea,
 * the 58-byte {@link Ct02Info} pagination cursor, and the screen's own field values. There is no
 * {@code HttpSession}, no {@code @SessionAttributes}, no server-side conversation state and no
 * static cache anywhere in this type - rule <strong>R6</strong>, gate <strong>G37</strong>.
 *
 * <h2>What is metadata and what is payload</h2>
 * Only the 21 {@code xxxO} items are JSON payload members. The {@code xxxC}, {@code xxxP},
 * {@code xxxH} and {@code xxxV} quad per field is highlight metadata per Agent Action Plan
 * section 0.6.3 and is excluded from the payload - see {@link FieldMetadata} and
 * {@link #applyHighlight(FieldHighlight)}.
 *
 * <h2>Numeric typing</h2>
 * Every one of the 21 payload members is a {@code String} at its declared width. This type contains
 * no {@code double}, no {@code float}, no {@code int} and no {@code BigDecimal} standing for a
 * {@code PIC 9...V...} value, and it performs no arithmetic at all, which satisfies gates
 * <strong>G22</strong>, <strong>G23</strong> and <strong>G24</strong> by construction. In
 * particular {@code TRNAMTO} carries the <em>edited</em> 12-character mask and not the record's
 * value - see {@link #getTrnamto()}.
 *
 * <p>No numeric convenience accessor is offered. One would have to route through
 * {@code common/CobolDecimal} to honour scale 2 and {@code RoundingMode.DOWN}, and that class is
 * not among this file's declared dependencies; adding it would widen the dependency set for a
 * convenience this type does not need, and a second view of the amount risks a second JSON member
 * and with it gate <strong>G9</strong>. Callers that need the amount as a number parse
 * {@link #getTrnamto()} where the COBOL does, in the controller, exactly as
 * {@code app/cbl/COTRN02C.cbl:383} and {@code :456} apply {@code FUNCTION NUMVAL-C}.
 *
 * <h2>Security posture</h2>
 * {@code CARDNINO X(16)}, {@code ACTIDINO X(11)} and {@code MIDO X(9)} are carried at full declared
 * width, unmasked, untruncated and unredacted, and no data member is annotated
 * {@code @JsonIgnore}. The legacy screen displays these values in clear and this migration is
 * behaviour-preserving, so masking them here would be a behaviour change - practice
 * <strong>B6</strong>, gate <strong>G41</strong>.
 *
 * <h2>Threading</h2>
 * Instances are mutable and are <strong>not</strong> thread safe, which is the normal and correct
 * contract for a per-request DTO: one instance belongs to one request. All shared state in the type
 * is {@code static final} and deeply immutable, so there is no static mutable state anywhere -
 * practice <strong>B9</strong>, gate <strong>G53</strong>.
 *
 * <p>The inbound projection of these same bytes through the {@code xxxI} alias is
 * {@code TransactionViewRequest} in this package. Its 21 base names and widths are necessarily
 * identical to this type's, because the two aliases describe one region of storage; a divergence
 * between them means one of the two is wrong.
 */
public final class TransactionViewResponse {

    // =================================================================================================
    // Identity. The transaction, program, mapset and map this payload belongs to, taken from
    // app/csd/CARDDEMO.CSD and app/cbl/COTRN02C.cbl rather than retyped from memory.
    // =================================================================================================

    /**
     * {@code CT02} - the CICS transaction identifier.
     * {@code app/csd/CARDDEMO.CSD:439} defines {@code TRANSACTION(CT02)} with
     * {@code PROGRAM(COTRN02C)}, and {@code app/cbl/COTRN02C.cbl:37} declares
     * {@code WS-TRANID PIC X(04) VALUE 'CT02'}.
     */
    public static final String TRANSACTION_ID = "CT02";

    /**
     * {@code COTRN02C} - the backing program.
     * {@code app/cbl/COTRN02C.cbl:36} declares {@code WS-PGMNAME PIC X(08) VALUE 'COTRN02C'} and
     * {@code app/csd/CARDDEMO.CSD:271} defines {@code PROGRAM(COTRN02C) LANGUAGE(COBOL)}.
     */
    public static final String PROGRAM_ID = "COTRN02C";

    /**
     * {@code COTRN02} - the mapset, seven characters.
     * {@code app/bms/COTRN02.bms:19} declares {@code COTRN02 DFHMSD} and
     * {@code app/csd/CARDDEMO.CSD:153} defines {@code MAPSET(COTRN02)}. It is exactly the width of
     * {@code CDEMO-LAST-MAPSET PIC X(7)}, which is why {@link #getNextMapset()} is seven and not
     * eight characters wide.
     */
    public static final String MAPSET_NAME = "COTRN02";

    /**
     * {@code COTRN2A} - the map, seven characters.
     * {@code app/bms/COTRN02.bms:26} declares {@code COTRN2A DFHMDI COLUMN=1 LINE=1 SIZE=(24,80)},
     * and the symbolic groups are named after it: {@code COTRN2AI} and {@code COTRN2AO}.
     * {@code app/cbl/COTRN02C.cbl:523} and {@code :542} name it in the {@code SEND} and
     * {@code RECEIVE}.
     */
    public static final String MAP_NAME = "COTRN2A";

    // =================================================================================================
    // Geometry of the COTRN2AO group image.
    //
    // 01 COTRN2AO REDEFINES COTRN2AI, app/cpy-bms/COTRN02.CPY line 145:
    //
    //     02 FILLER PIC X(12).          <- the TIOAPFX prefix, once, at the head of the group
    //     ... then, once per screen field, in declaration order:
    //     02 FILLER PICTURE X(3).       <- aliases the AI view's xxxL COMP PIC S9(4) plus xxxF X
    //     02 xxxC   PICTURE X.          <- COLOR      metadata
    //     02 xxxP   PICTURE X.          <- PS         metadata
    //     02 xxxH   PICTURE X.          <- HILIGHT    metadata
    //     02 xxxV   PICTURE X.          <- VALIDN     metadata
    //     02 xxxO   PIC X(n).           <- PAYLOAD
    //
    // so the stride is 7 + n and the group is 12 + 21*7 + 396 = 555 bytes.
    // =================================================================================================

    /** The 12-byte {@code TIOAPFX=YES} prefix at {@code app/cpy-bms/COTRN02.CPY:146}. */
    public static final int TIOAPFX_LENGTH = 12;

    /**
     * The {@code FILLER PICTURE X(3)} that opens every field's prefix in the {@code AO} view. It
     * aliases the {@code AI} view's two-byte {@code xxxL COMP PIC S9(4)} plus its one-byte
     * {@code xxxF PICTURE X}, which is exactly why both views have a seven-byte stride.
     */
    public static final int ATTRIBUTE_PREFIX_FILLER_LENGTH = 3;

    /** The {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV} items are one byte each. */
    public static final int ATTRIBUTE_ITEM_LENGTH = 1;

    /** Four attribute items per field: {@code xxxC}, {@code xxxP}, {@code xxxH}, {@code xxxV}. */
    public static final int ATTRIBUTE_ITEM_COUNT = 4;

    /**
     * Seven bytes of prefix ahead of every {@code xxxO} item:
     * {@code 3 + 4 * 1}. Identical to the {@code AI} view's {@code 2 + 1 + 4}, which is what makes
     * the two views byte-for-byte aliases.
     */
    public static final int PER_FIELD_PREFIX_LENGTH =
            ATTRIBUTE_PREFIX_FILLER_LENGTH + ATTRIBUTE_ITEM_COUNT * ATTRIBUTE_ITEM_LENGTH;

    /**
     * 21 - the number of name-labelled {@code DFHMDF} definitions in
     * {@code app/bms/COTRN02.bms}, of {@code xxxI} items and of {@code xxxO} items in
     * {@code app/cpy-bms/COTRN02.CPY}. The three counts agree, which is the cross-check that the
     * projection is complete (practice <strong>B5</strong>, gate <strong>G9</strong>). The map
     * declares 61 {@code DFHMDF} entries in total; the 40 unlabelled ones are captions and rules
     * that CICS cannot address, so they are not payload.
     */
    public static final int FIELD_COUNT = 21;

    // -------------------------------------------------------------------------------------------------
    // The 21 declared widths, in copybook declaration order. Each is the PIC X(n) of the xxxO item and
    // equals the LENGTH= of the matching DFHMDF - verified field by field for all 21 (gate G9).
    // -------------------------------------------------------------------------------------------------

    /** {@code 02 TRNNAMEO PIC X(4)}, {@code COTRN02.CPY:152}; {@code TRNNAME DFHMDF LENGTH=4}. */
    public static final int TRNNAMEO_LENGTH = 4;

    /** {@code 02 TITLE01O PIC X(40)}, {@code COTRN02.CPY:158}; {@code LENGTH=40}. */
    public static final int TITLE01O_LENGTH = 40;

    /** {@code 02 CURDATEO PIC X(8)}, {@code COTRN02.CPY:164}; {@code LENGTH=8}. */
    public static final int CURDATEO_LENGTH = 8;

    /** {@code 02 PGMNAMEO PIC X(8)}, {@code COTRN02.CPY:170}; {@code LENGTH=8}. */
    public static final int PGMNAMEO_LENGTH = 8;

    /** {@code 02 TITLE02O PIC X(40)}, {@code COTRN02.CPY:176}; {@code LENGTH=40}. */
    public static final int TITLE02O_LENGTH = 40;

    /** {@code 02 CURTIMEO PIC X(8)}, {@code COTRN02.CPY:182}; {@code LENGTH=8}. */
    public static final int CURTIMEO_LENGTH = 8;

    /**
     * {@code 02 ACTIDINO PIC X(11)}, {@code COTRN02.CPY:188}; {@code ACTIDIN DFHMDF LENGTH=11}.
     *
     * <p><strong>Eleven, not sixteen.</strong> This is the account identifier and it is the width of
     * {@code ACCT-ID PIC 9(11)}; the sixteen belongs to {@link #CARDNINO_LENGTH}. The two sit side
     * by side on line 6 of the screen separated by an {@code (or)} caption, which makes them easy to
     * transpose.
     */
    public static final int ACTIDINO_LENGTH = 11;

    /** {@code 02 CARDNINO PIC X(16)}, {@code COTRN02.CPY:194}; {@code CARDNIN DFHMDF LENGTH=16}. */
    public static final int CARDNINO_LENGTH = 16;

    /** {@code 02 TTYPCDO PIC X(2)}, {@code COTRN02.CPY:200}; {@code LENGTH=2}. */
    public static final int TTYPCDO_LENGTH = 2;

    /** {@code 02 TCATCDO PIC X(4)}, {@code COTRN02.CPY:206}; {@code LENGTH=4}. */
    public static final int TCATCDO_LENGTH = 4;

    /** {@code 02 TRNSRCO PIC X(10)}, {@code COTRN02.CPY:212}; {@code LENGTH=10}. */
    public static final int TRNSRCO_LENGTH = 10;

    /**
     * {@code 02 TDESCO PIC X(60)}, {@code COTRN02.CPY:218}; {@code LENGTH=60}.
     *
     * <p>Sixty on the screen while {@code TRAN-DESC} in {@code app/cpy/CVTRA05Y.cpy} is
     * {@code PIC X(100)}: {@code app/cbl/COTRN02C.cbl:486} moves the record field onto the screen
     * and truncates 100 to 60 on the right, and {@code :455} moves it back, padding 60 to 100. Both
     * directions are ordinary {@code PIC X} moves and both are reproduced by the accessors here.
     */
    public static final int TDESCO_LENGTH = 60;

    /**
     * {@code 02 TRNAMTO PIC X(12)}, {@code COTRN02.CPY:224}; {@code TRNAMT DFHMDF LENGTH=12}.
     *
     * <p>Twelve characters because the screen carries an <em>edited</em> amount:
     * {@code app/cbl/COTRN02C.cbl:53} and {@code :59} declare {@code PIC +99999999.99}, which is
     * one sign, eight integer digits, a literal point and two fraction digits. See
     * {@link #getTrnamto()} for why the field is not widened to hold the record's ninth digit.
     */
    public static final int TRNAMTO_LENGTH = 12;

    /** {@code 02 TORIGDTO PIC X(10)}, {@code COTRN02.CPY:230}; {@code LENGTH=10}. */
    public static final int TORIGDTO_LENGTH = 10;

    /** {@code 02 TPROCDTO PIC X(10)}, {@code COTRN02.CPY:236}; {@code LENGTH=10}. */
    public static final int TPROCDTO_LENGTH = 10;

    /** {@code 02 MIDO PIC X(9)}, {@code COTRN02.CPY:242}; {@code MID DFHMDF LENGTH=9}. */
    public static final int MIDO_LENGTH = 9;

    /** {@code 02 MNAMEO PIC X(30)}, {@code COTRN02.CPY:248}; {@code LENGTH=30}. */
    public static final int MNAMEO_LENGTH = 30;

    /** {@code 02 MCITYO PIC X(25)}, {@code COTRN02.CPY:254}; {@code LENGTH=25}. */
    public static final int MCITYO_LENGTH = 25;

    /** {@code 02 MZIPO PIC X(10)}, {@code COTRN02.CPY:260}; {@code LENGTH=10}. */
    public static final int MZIPO_LENGTH = 10;

    /**
     * {@code 02 CONFIRMO PIC X(1)}, {@code COTRN02.CPY:266}; {@code CONFIRM DFHMDF LENGTH=1}.
     *
     * <p>The add-confirmation flag. Its presence is part of the proof that this is the add screen
     * and not the view screen - {@code COTRN01} has no such field. See the risk R-B note on this
     * class.
     */
    public static final int CONFIRMO_LENGTH = 1;

    /**
     * {@code 02 ERRMSGO PIC X(78)}, {@code COTRN02.CPY:272};
     * {@code ERRMSG DFHMDF ATTRB=(ASKIP,BRT,FSET) COLOR=RED LENGTH=78 POS=(23,1)}.
     *
     * <p>Seventy-eight while {@code WS-MESSAGE} at {@code app/cbl/COTRN02C.cbl:38} is
     * {@code PIC X(80)}, so {@code :520}'s {@code MOVE WS-MESSAGE TO ERRMSGO OF COTRN2AO} discards
     * the last two characters. {@link #setErrmsgo(String)} reproduces that right truncation.
     */
    public static final int ERRMSGO_LENGTH = 78;

    /**
     * 396 - the sum of the 21 declared widths, and independently the sum of the 21
     * {@code DFHMDF LENGTH=} values.
     *
     * <p>Written as the explicit sum rather than the literal so that changing any one width above
     * cannot leave this constant stale, and so that the addition itself is reviewable against the
     * copybook.
     */
    public static final int PAYLOAD_LENGTH =
            TRNNAMEO_LENGTH + TITLE01O_LENGTH + CURDATEO_LENGTH + PGMNAMEO_LENGTH
                    + TITLE02O_LENGTH + CURTIMEO_LENGTH + ACTIDINO_LENGTH + CARDNINO_LENGTH
                    + TTYPCDO_LENGTH + TCATCDO_LENGTH + TRNSRCO_LENGTH + TDESCO_LENGTH
                    + TRNAMTO_LENGTH + TORIGDTO_LENGTH + TPROCDTO_LENGTH + MIDO_LENGTH
                    + MNAMEO_LENGTH + MCITYO_LENGTH + MZIPO_LENGTH + CONFIRMO_LENGTH
                    + ERRMSGO_LENGTH;

    /**
     * 555 - the length of the whole {@code COTRN2AO} group image:
     * {@code 12 + 21 * 7 + 396}.
     *
     * <p>This is not merely documented, it is <em>enforced</em>: {@link #LAYOUT} is declared at this
     * length and {@link RecordLayout} verifies in its own constructor that its storage spans sum to
     * the declared length with no gap and no overlap. A mistranscribed width or offset therefore
     * fails class initialisation and names the offending descriptor, instead of quietly producing a
     * group image of the wrong size.
     */
    public static final int SYMBOLIC_MAP_LENGTH =
            TIOAPFX_LENGTH + FIELD_COUNT * PER_FIELD_PREFIX_LENGTH + PAYLOAD_LENGTH;

    // =================================================================================================
    // The 21 screen fields as an ordered enumeration.
    //
    // This exists so that the copybook's field list is stated once, in declaration order, with each
    // name and width verbatim - and so that "every input field has a reachable colour item" (gate G38)
    // is something a test can iterate and prove rather than something a reviewer has to count. It also
    // lets the group layout below be derived by walking the fields in order, which removes 127
    // hand-transcribed offsets from the file; the offsets are still explicit, they are just computed
    // from the declared widths by the one rule the copybook itself follows (practice B8).
    // =================================================================================================

    /**
     * The 21 name-labelled screen fields of map {@code COTRN2A}, in {@code app/bms/COTRN02.bms} and
     * {@code app/cpy-bms/COTRN02.CPY} declaration order.
     *
     * <p>Each constant carries the BMS field label - the {@code xxx} stem shared by the
     * {@code xxxL} / {@code xxxF} / {@code xxxI} items of the {@code AI} view and the {@code xxxC} /
     * {@code xxxP} / {@code xxxH} / {@code xxxV} / {@code xxxO} items of the {@code AO} view - its
     * declared width, and whether the {@code DFHMDF} declares it {@code UNPROT}.
     *
     * <p>The {@code UNPROT} flag is the fact behind gate <strong>G38</strong>: 14 of the 21 fields are
     * unprotected input, and those are the fields {@code app/cpy/CSSETATY.cpy} can highlight. It is
     * read from the map, not guessed: the seven protected fields are the six header fields and the
     * error line.
     */
    public enum ScreenField {

        /** {@code TRNNAME}, {@code ATTRB=(ASKIP,FSET,NORM) COLOR=BLUE POS=(1,7)}; the transaction id. */
        TRNNAME("TRNNAME", TRNNAMEO_LENGTH, false),

        /** {@code TITLE01}, {@code ATTRB=(ASKIP,FSET,NORM) COLOR=YELLOW POS=(1,21)}. */
        TITLE01("TITLE01", TITLE01O_LENGTH, false),

        /** {@code CURDATE}, {@code COLOR=BLUE POS=(1,71) INITIAL='mm/dd/yy'}. */
        CURDATE("CURDATE", CURDATEO_LENGTH, false),

        /** {@code PGMNAME}, {@code COLOR=BLUE POS=(2,7)}; the program name. */
        PGMNAME("PGMNAME", PGMNAMEO_LENGTH, false),

        /** {@code TITLE02}, {@code COLOR=YELLOW POS=(2,21)}. */
        TITLE02("TITLE02", TITLE02O_LENGTH, false),

        /** {@code CURTIME}, {@code COLOR=BLUE POS=(2,71) INITIAL='hh:mm:ss'}. */
        CURTIME("CURTIME", CURTIMEO_LENGTH, false),

        /**
         * {@code ACTIDIN}, {@code ATTRB=(FSET,IC,NORM,UNPROT) COLOR=GREEN LENGTH=11 POS=(6,21)}.
         * {@code IC} marks it the initial-cursor field, which is why
         * {@code app/cbl/COTRN02C.cbl:123} positions the cursor here on first entry.
         */
        ACTIDIN("ACTIDIN", ACTIDINO_LENGTH, true),

        /** {@code CARDNIN}, {@code ATTRB=(FSET,NORM,UNPROT) COLOR=GREEN LENGTH=16 POS=(6,55)}. */
        CARDNIN("CARDNIN", CARDNINO_LENGTH, true),

        /** {@code TTYPCD}, {@code ATTRB=(FSET,NORM,UNPROT) COLOR=GREEN POS=(10,15)}. */
        TTYPCD("TTYPCD", TTYPCDO_LENGTH, true),

        /** {@code TCATCD}, {@code ATTRB=(FSET,NORM,UNPROT) COLOR=GREEN POS=(10,36)}. */
        TCATCD("TCATCD", TCATCDO_LENGTH, true),

        /** {@code TRNSRC}, {@code ATTRB=(FSET,NORM,UNPROT) COLOR=GREEN POS=(10,54)}. */
        TRNSRC("TRNSRC", TRNSRCO_LENGTH, true),

        /** {@code TDESC}, {@code ATTRB=(FSET,NORM,UNPROT) COLOR=GREEN POS=(12,19)}. */
        TDESC("TDESC", TDESCO_LENGTH, true),

        /** {@code TRNAMT}, {@code ATTRB=(FSET,NORM,UNPROT) COLOR=GREEN LENGTH=12 POS=(14,14)}. */
        TRNAMT("TRNAMT", TRNAMTO_LENGTH, true),

        /** {@code TORIGDT}, {@code ATTRB=(FSET,NORM,UNPROT) COLOR=GREEN POS=(14,42)}. */
        TORIGDT("TORIGDT", TORIGDTO_LENGTH, true),

        /** {@code TPROCDT}, {@code ATTRB=(FSET,NORM,UNPROT) COLOR=GREEN POS=(14,68)}. */
        TPROCDT("TPROCDT", TPROCDTO_LENGTH, true),

        /** {@code MID}, {@code ATTRB=(FSET,NORM,UNPROT) COLOR=GREEN POS=(16,19)}. */
        MID("MID", MIDO_LENGTH, true),

        /** {@code MNAME}, {@code ATTRB=(FSET,NORM,UNPROT) COLOR=GREEN POS=(16,48)}. */
        MNAME("MNAME", MNAMEO_LENGTH, true),

        /** {@code MCITY}, {@code ATTRB=(FSET,NORM,UNPROT) COLOR=GREEN POS=(18,21)}. */
        MCITY("MCITY", MCITYO_LENGTH, true),

        /** {@code MZIP}, {@code ATTRB=(FSET,NORM,UNPROT) COLOR=GREEN POS=(18,67)}. */
        MZIP("MZIP", MZIPO_LENGTH, true),

        /** {@code CONFIRM}, {@code ATTRB=(FSET,NORM,UNPROT) COLOR=GREEN LENGTH=1 POS=(21,63)}. */
        CONFIRM("CONFIRM", CONFIRMO_LENGTH, true),

        /**
         * {@code ERRMSG}, {@code ATTRB=(ASKIP,BRT,FSET) COLOR=RED LENGTH=78 POS=(23,1)} - the error
         * line. It is {@code ASKIP} and therefore not an input field, so it is never a highlight
         * target: it is where the message explaining a highlight is written.
         */
        ERRMSG("ERRMSG", ERRMSGO_LENGTH, false);

        private final String label;
        private final int width;
        private final boolean input;

        ScreenField(String label, int width, boolean input) {
            this.label = label;
            this.width = width;
            this.input = input;
        }

        /**
         * The BMS field label - the {@code xxx} stem, exactly as {@code app/bms/COTRN02.bms} labels
         * the {@code DFHMDF}.
         *
         * @return the label, for example {@code TRNAMT}
         */
        public String label() {
            return label;
        }

        /**
         * The declared width of this field's {@code xxxO} item, which equals its {@code DFHMDF}
         * {@code LENGTH=}.
         *
         * @return the width in characters, always 1 or more
         */
        public int width() {
            return width;
        }

        /**
         * Whether the {@code DFHMDF} declares this field {@code UNPROT} - that is, whether the
         * terminal operator can type into it.
         *
         * <p>True for exactly 14 of the 21 fields. Only these can be a highlight target under
         * {@code app/cpy/CSSETATY.cpy}, which is the substance of gate <strong>G38</strong>.
         *
         * @return {@code true} for an unprotected input field
         */
        public boolean input() {
            return input;
        }

        /**
         * The name of this field's payload item in the {@code AO} view - the label plus {@code O}.
         *
         * @return for example {@code TRNAMTO}
         */
        public String outputItemName() {
            return label + FieldAttributeSetter.OUTPUT_ITEM_SUFFIX;
        }

        /**
         * The name of this field's colour item - the label plus {@code C}. This is the item
         * {@code app/cpy/CSSETATY.cpy} moves {@code DFHRED} into.
         *
         * @return for example {@code TRNAMTC}
         */
        public String colourItemName() {
            return label + FieldAttributeSetter.COLOUR_ITEM_SUFFIX;
        }

        /**
         * The name of this field's programmed-symbols item - the label plus {@code P}.
         *
         * @return for example {@code TRNAMTP}
         */
        public String psItemName() {
            return label + "P";
        }

        /**
         * The name of this field's highlight item - the label plus {@code H}.
         *
         * @return for example {@code TRNAMTH}
         */
        public String highlightItemName() {
            return label + "H";
        }

        /**
         * The name of this field's validation item - the label plus {@code V}.
         *
         * @return for example {@code TRNAMTV}
         */
        public String validnItemName() {
            return label + "V";
        }

        /**
         * Resolves a field from its BMS label, as carried by
         * {@link FieldHighlight#screenFieldPrefix()}.
         *
         * @param label the BMS label, for example {@code TRNAMT}; compared exactly, after trimming
         *     trailing spaces so a space-padded prefix still resolves
         * @return the matching field
         * @throws NullPointerException if {@code label} is {@code null}
         * @throws IllegalArgumentException if no field of map {@code COTRN2A} carries that label
         */
        public static ScreenField ofLabel(String label) {
            Objects.requireNonNull(label, "A screen field label is required");
            String wanted = label.stripTrailing();
            for (ScreenField field : values()) {
                if (field.label.equals(wanted)) {
                    return field;
                }
            }
            throw new IllegalArgumentException("Map " + MAP_NAME + " declares no field labelled '"
                    + wanted + "'; the 21 labelled DFHMDF fields of app/bms/COTRN02.bms are "
                    + LABELS + ". Note that TRNID belongs to COTRN01, not to this map.");
        }
    }

    /**
     * The 21 labels in declaration order, used to make {@link ScreenField#ofLabel(String)}'s failure
     * message name the legal values instead of leaving the caller to guess.
     *
     * <p>{@link List#copyOf} returns an unmodifiable list of an immutable element type, so this is a
     * deeply immutable constant and not static mutable state (practice <strong>B9</strong>).
     */
    private static final List<String> LABELS = buildLabels();

    private static List<String> buildLabels() {
        List<String> labels = new ArrayList<>(FIELD_COUNT);
        for (ScreenField field : ScreenField.values()) {
            labels.add(field.label());
        }
        return List.copyOf(labels);
    }

    // =================================================================================================
    // The COTRN2AO group layout: 127 spans - one leading FILLER plus six per field - summing to 555.
    //
    // FILLER is a first-class span here, exactly as FixedWidthRecord requires. Omitting the leading
    // X(12) or any of the 21 X(3) prefixes would shift every offset after it and silently corrupt the
    // group image, so they are declared and written, not inferred (gate G21 in spirit).
    //
    // FieldSpan and RecordLayout are immutable records and RecordLayout copies its span list
    // defensively, so every constant below is deeply immutable (practice B9).
    // =================================================================================================

    /**
     * The five spans of one screen field in the {@code AO} view: the four one-byte attribute items
     * and the payload item. The leading {@code FILLER X(3)} is not held here because it is never
     * addressed by name.
     *
     * <p>A record, so it is immutable and safe to publish in a constant map.
     *
     * @param colour the {@code xxxC} colour item, one byte
     * @param ps the {@code xxxP} programmed-symbols item, one byte
     * @param highlight the {@code xxxH} highlight item, one byte
     * @param validn the {@code xxxV} validation item, one byte
     * @param output the {@code xxxO} payload item, the field's declared width
     */
    public record FieldSpans(FieldSpan colour, FieldSpan ps, FieldSpan highlight, FieldSpan validn,
            FieldSpan output) {

        /**
         * Validates that all five descriptors are present.
         *
         * @throws NullPointerException if any descriptor is {@code null}
         */
        public FieldSpans {
            Objects.requireNonNull(colour, "A colour item descriptor is required");
            Objects.requireNonNull(ps, "A programmed-symbols item descriptor is required");
            Objects.requireNonNull(highlight, "A highlight item descriptor is required");
            Objects.requireNonNull(validn, "A validation item descriptor is required");
            Objects.requireNonNull(output, "An output item descriptor is required");
        }
    }

    /** Holder for the two derived layout constants, so the walk that builds them runs once. */
    private record LayoutBundle(Map<ScreenField, FieldSpans> spans, RecordLayout layout) {
    }

    private static final LayoutBundle LAYOUT_BUNDLE = buildLayout();

    /**
     * The five addressable spans of each screen field, keyed by field.
     *
     * <p>Exposed so a parity test can assert an individual offset - for example that
     * {@code ERRMSGO} begins at 477 and ends at 554 - without this class having to publish 105
     * separate constants.
     *
     * <p>Unmodifiable, over an immutable key and an immutable value type.
     */
    public static final Map<ScreenField, FieldSpans> FIELD_SPANS = LAYOUT_BUNDLE.spans();

    /**
     * The complete layout of {@code 01 COTRN2AO REDEFINES COTRN2AI}: 127 spans in copybook
     * declaration order, declared at {@link #SYMBOLIC_MAP_LENGTH}.
     *
     * <p>Constructing this constant <strong>is</strong> the byte-total self-check.
     * {@link RecordLayout} runs its geometry verification in its own constructor and rejects a layout
     * whose storage spans do not sum to the declared record length, that overlaps, that leaves a gap,
     * or that declares a referable name twice. {@code FILLER} is exempt from the duplicate-name rule
     * precisely because a copybook may declare it many times and it can never be referenced, which is
     * what lets all 22 {@code FILLER} spans coexist here.
     *
     * <p>So if any width above were mistranscribed, or the seven-byte stride were wrong, this class
     * would fail to initialise and say which descriptor was at fault - a far cheaper failure than a
     * group image that is quietly the wrong size.
     */
    public static final RecordLayout LAYOUT = LAYOUT_BUNDLE.layout();

    /**
     * Walks the 21 fields in declaration order, emitting the leading {@code TIOAPFX} filler and then
     * each field's {@code FILLER X(3)}, {@code xxxC}, {@code xxxP}, {@code xxxH}, {@code xxxV} and
     * {@code xxxO} spans at consecutive offsets.
     *
     * <p>This is the copybook's own rule expressed once, which is why no offset appears as a literal
     * anywhere in this class.
     *
     * @return the per-field span map and the verified layout
     */
    private static LayoutBundle buildLayout() {
        Map<ScreenField, FieldSpans> spans = new EnumMap<>(ScreenField.class);
        List<FieldSpan> ordered = new ArrayList<>(1 + FIELD_COUNT * 6);

        int offset = 0;

        // 02 FILLER PIC X(12) - app/cpy-bms/COTRN02.CPY:146, the TIOAPFX=YES prefix.
        ordered.add(FieldSpan.filler(offset, TIOAPFX_LENGTH));
        offset += TIOAPFX_LENGTH;

        for (ScreenField field : ScreenField.values()) {
            // 02 FILLER PICTURE X(3) - aliases the AI view's xxxL COMP PIC S9(4) plus xxxF PICTURE X.
            ordered.add(FieldSpan.filler(offset, ATTRIBUTE_PREFIX_FILLER_LENGTH));
            offset += ATTRIBUTE_PREFIX_FILLER_LENGTH;

            FieldSpan colour =
                    FieldSpan.alphanumeric(field.colourItemName(), offset, ATTRIBUTE_ITEM_LENGTH);
            offset += ATTRIBUTE_ITEM_LENGTH;

            FieldSpan ps = FieldSpan.alphanumeric(field.psItemName(), offset, ATTRIBUTE_ITEM_LENGTH);
            offset += ATTRIBUTE_ITEM_LENGTH;

            FieldSpan highlight =
                    FieldSpan.alphanumeric(field.highlightItemName(), offset, ATTRIBUTE_ITEM_LENGTH);
            offset += ATTRIBUTE_ITEM_LENGTH;

            FieldSpan validn =
                    FieldSpan.alphanumeric(field.validnItemName(), offset, ATTRIBUTE_ITEM_LENGTH);
            offset += ATTRIBUTE_ITEM_LENGTH;

            FieldSpan output =
                    FieldSpan.alphanumeric(field.outputItemName(), offset, field.width());
            offset += field.width();

            ordered.add(colour);
            ordered.add(ps);
            ordered.add(highlight);
            ordered.add(validn);
            ordered.add(output);

            spans.put(field, new FieldSpans(colour, ps, highlight, validn, output));
        }

        // RecordLayout.of re-verifies this independently; asserting it here as well means the failure
        // names the arithmetic rather than the last span, which is the more useful diagnosis.
        if (offset != SYMBOLIC_MAP_LENGTH) {
            throw new IllegalStateException("The COTRN2AO group walk produced " + offset
                    + " bytes but 01 COTRN2AO REDEFINES COTRN2AI is " + SYMBOLIC_MAP_LENGTH
                    + " bytes (" + TIOAPFX_LENGTH + " + " + FIELD_COUNT + " * "
                    + PER_FIELD_PREFIX_LENGTH + " + " + PAYLOAD_LENGTH + "); a declared width in "
                    + "app/cpy-bms/COTRN02.CPY has been mistranscribed");
        }

        RecordLayout layout =
                RecordLayout.of(SYMBOLIC_MAP_LENGTH, ordered.toArray(new FieldSpan[0]));
        return new LayoutBundle(Collections.unmodifiableMap(spans), layout);
    }

    // =================================================================================================
    // The PICTURE rule engine and the figurative constants.
    // =================================================================================================

    /**
     * The single implementation of the {@code PIC X} move rule used by every setter in this class.
     *
     * <p>The only operation taken from it here is {@link FixedWidthCodec#movePicX(String, int)},
     * which is a <em>character-level</em> pad-or-truncate that converts nothing to bytes, so the code
     * page this instance was built for takes no part in the result. It is named
     * {@link StandardCharsets#US_ASCII} explicitly and never derived from the platform default
     * (practice <strong>B8</strong>), and it is the code page of the authoritative fixtures under
     * {@code app/data/ASCII}.
     *
     * <p>This instance is <strong>never</strong> used to encode or decode bytes. Every byte boundary -
     * {@link #toFixedWidth(Charset)}, {@link #writeInto(FixedWidthRecord)},
     * {@link #fromFixedWidth(byte[], Charset)} and {@link #readFrom(FixedWidthRecord)} - takes its
     * code page from the caller, because a symbolic map image is bytes in a specific code page and
     * this type is bound to neither.
     *
     * <p>{@code FixedWidthCodec} is immutable and holds only its {@link Charset}, so one shared
     * instance is thread safe and is a constant rather than static mutable state (practice
     * <strong>B9</strong>). Holding it here rather than reimplementing the pad and truncate rules
     * keeps one reviewable implementation of them in the module (practice <strong>B11</strong>).
     */
    private static final FixedWidthCodec PICTURE_RULES =
            new FixedWidthCodec(StandardCharsets.US_ASCII);

    /**
     * The COBOL figurative constant {@code SPACES} rendered for a field of the given width.
     *
     * <p>COBOL has no null. A {@code PIC X} field always holds exactly its declared width in
     * characters, and an "empty" one holds spaces, so this is how a caller says empty.
     *
     * @param length the field width in characters
     * @return a string of exactly {@code length} spaces
     * @throws IllegalArgumentException if {@code length} is below 1
     */
    public static String spaces(int length) {
        requireDeclaredWidth(length, "SPACES");
        return " ".repeat(length);
    }

    /**
     * The COBOL figurative constant {@code LOW-VALUES} rendered for a field of the given width: the
     * character {@code U+0000} repeated, which is the byte {@code 0x00} under both code pages this
     * system uses.
     *
     * <p>This is a genuine third state, distinct from {@link #spaces(int)} and from {@code null}.
     * {@code app/cbl/COTRN02C.cbl:122} sets the entire output group to {@code LOW-VALUES} on the
     * transition into {@code REENTER}, and {@code :124} and {@code :137} test fields against
     * {@code SPACES AND LOW-VALUES} as two separate values - so the distinction is observable and
     * has to survive.
     *
     * @param length the field width in characters
     * @return a string of exactly {@code length} {@code U+0000} characters
     * @throws IllegalArgumentException if {@code length} is below 1
     */
    public static String lowValues(int length) {
        // One implementation of the LOW-VALUES image, in common.ScreenFieldImage, so the choice cannot
        // drift back apart across screens. Any width validation above is this method's own contract.
        requireDeclaredWidth(length, "LOW-VALUES");
        return ScreenFieldImage.unpainted(length);
    }

    private static void requireDeclaredWidth(int length, String figurativeConstant) {
        if (length < 1) {
            throw new IllegalArgumentException("Cannot render " + figurativeConstant + " for a field "
                    + "of " + length + " character(s); every item of COTRN2AO is at least 1 byte "
                    + "wide");
        }
    }

    /**
     * Applies the COBOL {@code PIC X} move rule: pad on the right with spaces when the value is
     * shorter than the field, truncate on the right when it is longer.
     *
     * <p>Right truncation is not incidental, it is the semantics being reproduced. It is what makes
     * {@code app/cbl/COTRN02C.cbl:520}'s {@code MOVE WS-MESSAGE TO ERRMSGO} drop the last two of
     * {@code WS-MESSAGE}'s 80 characters, and {@code :486}'s {@code MOVE TRAN-DESC TO TDESCI} drop
     * the last 40 of {@code TRAN-DESC}'s 100.
     *
     * @param value the value to store
     * @param length the declared field width
     * @param cobolName the item name, for the diagnostic
     * @return the value at exactly {@code length} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    private static String movePicX(String value, int length, String cobolName) {
        Objects.requireNonNull(value, "A value is required for " + cobolName + ": COBOL has no null, "
                + "so pass spaces(" + length + ") or lowValues(" + length + ") to state which "
                + "figurative constant is meant");
        return PICTURE_RULES.movePicX(value, length);
    }

    // =================================================================================================
    // Highlight metadata: the xxxC / xxxP / xxxH / xxxV quad, per Agent Action Plan section 0.6.3.
    //
    // These are NEVER JSON payload members. Every accessor that reaches them is annotated @JsonIgnore,
    // and the four items are held in one @JsonIgnore-d map rather than as 84 loose fields, so there is
    // exactly one place where "is this payload or metadata?" is answered.
    // =================================================================================================

    /**
     * The four one-byte BMS attribute items of a single screen field - the {@code xxxC} colour,
     * {@code xxxP} programmed symbols, {@code xxxH} highlight and {@code xxxV} validation items of
     * {@code 01 COTRN2AO}.
     *
     * <p>Mutable, because {@code app/cpy/CSSETATY.cpy} writes into the colour item of an already
     * built output group. Each item defaults to {@link BmsAttributes#DFHDFCOL}, the
     * "default / no attribute" byte {@code X'00'}, which is what a {@code LOW-VALUES} output group
     * holds and therefore what CICS reads as "leave the map's declared attribute alone".
     *
     * <p>These are held as {@code byte}, not {@code char} or {@code String}, because BMS attribute
     * values are code points such as {@link BmsAttributes#DFHRED} ({@code X'F2'}) that are not
     * printable characters in either code page. Treating them as text would corrupt them on the
     * first round trip.
     */
    public static final class FieldMetadata {

        private byte colour = BmsAttributes.DFHDFCOL;
        private byte programmedSymbols = BmsAttributes.DFHDFCOL;
        private byte highlight = BmsAttributes.DFHDFCOL;
        private byte validation = BmsAttributes.DFHDFCOL;

        /** Creates a quad with all four items at {@link BmsAttributes#DFHDFCOL}. */
        public FieldMetadata() {
            // Fields carry their declared defaults; an explicit constructor documents that this is
            // the LOW-VALUES / "no attribute stated" starting state rather than an oversight.
        }

        /**
         * The {@code xxxC} colour item - the byte {@code app/cpy/CSSETATY.cpy} moves
         * {@link BmsAttributes#DFHRED} into when a field is in error during {@code REENTER}.
         *
         * @return the colour attribute byte
         */
        public byte getColour() {
            return colour;
        }

        /**
         * Stores the {@code xxxC} colour item.
         *
         * <p>Any byte is accepted. The value is not restricted to the declared colour mnemonics
         * because the item is one byte of an attribute stream, and a value CICS does not recognise
         * has to survive the round trip rather than be rejected here.
         *
         * @param colour the colour attribute byte, for example {@link BmsAttributes#DFHRED}
         */
        public void setColour(byte colour) {
            this.colour = colour;
        }

        /**
         * The {@code xxxP} programmed-symbols item.
         *
         * @return the programmed-symbols attribute byte
         */
        public byte getProgrammedSymbols() {
            return programmedSymbols;
        }

        /**
         * Stores the {@code xxxP} programmed-symbols item.
         *
         * @param programmedSymbols the programmed-symbols attribute byte
         */
        public void setProgrammedSymbols(byte programmedSymbols) {
            this.programmedSymbols = programmedSymbols;
        }

        /**
         * The {@code xxxH} highlight item.
         *
         * @return the highlight attribute byte
         */
        public byte getHighlight() {
            return highlight;
        }

        /**
         * Stores the {@code xxxH} highlight item.
         *
         * @param highlight the highlight attribute byte
         */
        public void setHighlight(byte highlight) {
            this.highlight = highlight;
        }

        /**
         * The {@code xxxV} validation item.
         *
         * @return the validation attribute byte
         */
        public byte getValidation() {
            return validation;
        }

        /**
         * Stores the {@code xxxV} validation item.
         *
         * @param validation the validation attribute byte
         */
        public void setValidation(byte validation) {
            this.validation = validation;
        }

        /**
         * Whether all four items are still at {@link BmsAttributes#DFHDFCOL}, meaning no attribute
         * has been stated for this field.
         *
         * @return {@code true} when the quad is untouched
         */
        @JsonIgnore
        public boolean isDefault() {
            return colour == BmsAttributes.DFHDFCOL
                    && programmedSymbols == BmsAttributes.DFHDFCOL
                    && highlight == BmsAttributes.DFHDFCOL
                    && validation == BmsAttributes.DFHDFCOL;
        }

        /** Restores all four items to {@link BmsAttributes#DFHDFCOL}. */
        public void reset() {
            colour = BmsAttributes.DFHDFCOL;
            programmedSymbols = BmsAttributes.DFHDFCOL;
            highlight = BmsAttributes.DFHDFCOL;
            validation = BmsAttributes.DFHDFCOL;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FieldMetadata that)) {
                return false;
            }
            return colour == that.colour
                    && programmedSymbols == that.programmedSymbols
                    && highlight == that.highlight
                    && validation == that.validation;
        }

        @Override
        public int hashCode() {
            return Objects.hash(colour, programmedSymbols, highlight, validation);
        }

        @Override
        public String toString() {
            return "FieldMetadata[C=" + BmsAttributes.toHex(colour)
                    + ", P=" + BmsAttributes.toHex(programmedSymbols)
                    + ", H=" + BmsAttributes.toHex(highlight)
                    + ", V=" + BmsAttributes.toHex(validation) + "]";
        }
    }

    // =================================================================================================
    // CDEMO-CT02-INFO - the 58-byte commarea extension, app/cbl/COTRN02C.cbl lines 72 to 80.
    //
    // COTRN02C copies COCOM01Y at line 71 and then extends the commarea IN PLACE at 72 to 80, so the
    // area this program passes is 160 + 58 = 218 bytes. NavigationContext stays at exactly 160 and is
    // NOT widened - it is shared by all 17 controllers.
    //
    // This type is deliberately NOT hoisted into a shared class. The three sibling programs name their
    // extensions CDEMO-CT00-*, CDEMO-CT01-* and CDEMO-CT02-*; the names differ, so the areas are not
    // interchangeable, and field-for-field diffing depends on the distinct names being preserved.
    // =================================================================================================

    /**
     * {@code 05 CDEMO-CT02-INFO} - the 58-byte pagination and selection cursor that
     * {@code app/cbl/COTRN02C.cbl} appends to the shared commarea at lines 72 to 80:
     *
     * <pre>
     * 05 CDEMO-CT02-INFO.
     *    10 CDEMO-CT02-TRNID-FIRST     PIC X(16).
     *    10 CDEMO-CT02-TRNID-LAST      PIC X(16).
     *    10 CDEMO-CT02-PAGE-NUM        PIC 9(08).
     *    10 CDEMO-CT02-NEXT-PAGE-FLG   PIC X(01) VALUE 'N'.
     *       88 NEXT-PAGE-YES                     VALUE 'Y'.
     *       88 NEXT-PAGE-NO                      VALUE 'N'.
     *    10 CDEMO-CT02-TRN-SEL-FLG     PIC X(01).
     *    10 CDEMO-CT02-TRN-SELECTED    PIC X(16).
     * </pre>
     *
     * <p>{@code 16 + 16 + 8 + 1 + 1 + 16 = 58}, asserted by {@link #LENGTH}.
     *
     * <p>It is echoed in the response so the client can send it back on the next call, which is how
     * a pseudo-conversational cursor survives without server-side state (rule <strong>R6</strong>,
     * gate <strong>G37</strong>). {@code app/cbl/COTRN02C.cbl:124} and {@code :126} read
     * {@code CDEMO-CT02-TRN-SELECTED} on first entry to pre-load the card number, which is only
     * possible because the field arrived in the commarea.
     *
     * <p>{@code CDEMO-CT02-PAGE-NUM} is {@code PIC 9(08)} - an unsigned integer with no {@code V},
     * so it is an {@code int} and not a {@code BigDecimal}. It is not monetary and carries no scale,
     * so gates <strong>G22</strong> to <strong>G24</strong> are not engaged by it.
     */
    public static final class Ct02Info {

        /** {@code 10 CDEMO-CT02-TRNID-FIRST PIC X(16)}, {@code app/cbl/COTRN02C.cbl:73}. */
        public static final int TRNID_FIRST_LENGTH = 16;

        /** {@code 10 CDEMO-CT02-TRNID-LAST PIC X(16)}, line 74. */
        public static final int TRNID_LAST_LENGTH = 16;

        /** {@code 10 CDEMO-CT02-PAGE-NUM PIC 9(08)}, line 75. */
        public static final int PAGE_NUM_LENGTH = 8;

        /** {@code 10 CDEMO-CT02-NEXT-PAGE-FLG PIC X(01)}, line 76. */
        public static final int NEXT_PAGE_FLG_LENGTH = 1;

        /** {@code 10 CDEMO-CT02-TRN-SEL-FLG PIC X(01)}, line 79. */
        public static final int TRN_SEL_FLG_LENGTH = 1;

        /** {@code 10 CDEMO-CT02-TRN-SELECTED PIC X(16)}, line 80. */
        public static final int TRN_SELECTED_LENGTH = 16;

        /**
         * 58 - the length of {@code CDEMO-CT02-INFO}, written as the explicit sum of its six items so
         * the addition is reviewable against lines 73 to 80.
         */
        public static final int LENGTH = TRNID_FIRST_LENGTH + TRNID_LAST_LENGTH + PAGE_NUM_LENGTH
                + NEXT_PAGE_FLG_LENGTH + TRN_SEL_FLG_LENGTH + TRN_SELECTED_LENGTH;

        /**
         * {@code 88 NEXT-PAGE-YES VALUE 'Y'}, {@code app/cbl/COTRN02C.cbl:77}.
         */
        public static final String NEXT_PAGE_YES_VALUE = "Y";

        /**
         * {@code 88 NEXT-PAGE-NO VALUE 'N'}, line 78. Also the item's own {@code VALUE 'N'} clause on
         * line 76, which is why it is this class's initial state.
         */
        public static final String NEXT_PAGE_NO_VALUE = "N";

        /**
         * The total commarea this program passes: {@code 160 + 58 = 218} bytes.
         * {@code app/cbl/COTRN02C.cbl:119} moves {@code DFHCOMMAREA(1:EIBCALEN)} into
         * {@code CARDDEMO-COMMAREA}, so the extension travels with the base area rather than beside
         * it.
         */
        public static final int PASSED_COMMAREA_LENGTH = NavigationContext.COMMAREA_LENGTH + LENGTH;

        private String trnidFirst = spaces(TRNID_FIRST_LENGTH);
        private String trnidLast = spaces(TRNID_LAST_LENGTH);
        private int pageNum;
        private String nextPageFlg = NEXT_PAGE_NO_VALUE;
        private String trnSelFlg = spaces(TRN_SEL_FLG_LENGTH);
        private String trnSelected = spaces(TRN_SELECTED_LENGTH);

        /**
         * Creates the cursor in its declared initial state: the two identifiers and both flags at
         * spaces, the page number at zero, and {@code CDEMO-CT02-NEXT-PAGE-FLG} at {@code 'N'} per
         * its {@code VALUE 'N'} clause on line 76 - so {@link #isNextPageNo()} is true and
         * {@link #isNextPageYes()} is false on a fresh instance.
         */
        public Ct02Info() {
            // Fields carry their declared VALUE clauses; see the Javadoc for why 'N' is the default.
        }

        /**
         * {@code CDEMO-CT02-TRNID-FIRST} - the first transaction identifier on the current page,
         * untrimmed and exactly 16 characters.
         *
         * @return the identifier, or spaces
         */
        public String getTrnidFirst() {
            return trnidFirst;
        }

        /**
         * Stores {@code CDEMO-CT02-TRNID-FIRST} through the {@code PIC X(16)} move rule.
         *
         * @param trnidFirst the identifier; padded or truncated on the right to 16 characters
         * @throws NullPointerException if {@code trnidFirst} is {@code null}
         */
        public void setTrnidFirst(String trnidFirst) {
            this.trnidFirst =
                    movePicX(trnidFirst, TRNID_FIRST_LENGTH, "CDEMO-CT02-TRNID-FIRST");
        }

        /**
         * {@code CDEMO-CT02-TRNID-LAST} - the last transaction identifier on the current page,
         * untrimmed and exactly 16 characters.
         *
         * @return the identifier, or spaces
         */
        public String getTrnidLast() {
            return trnidLast;
        }

        /**
         * Stores {@code CDEMO-CT02-TRNID-LAST} through the {@code PIC X(16)} move rule.
         *
         * @param trnidLast the identifier; padded or truncated on the right to 16 characters
         * @throws NullPointerException if {@code trnidLast} is {@code null}
         */
        public void setTrnidLast(String trnidLast) {
            this.trnidLast = movePicX(trnidLast, TRNID_LAST_LENGTH, "CDEMO-CT02-TRNID-LAST");
        }

        /**
         * {@code CDEMO-CT02-PAGE-NUM PIC 9(08)} - the current page number.
         *
         * @return the page number, 0 to 99999999
         */
        public int getPageNum() {
            return pageNum;
        }

        /**
         * Stores {@code CDEMO-CT02-PAGE-NUM}.
         *
         * @param pageNum the page number; must fit the eight declared digits
         * @throws IllegalArgumentException if {@code pageNum} is negative or exceeds eight digits,
         *     because {@code PIC 9(08)} is unsigned and eight digits wide and a value outside that
         *     range could not have been stored by the COBOL either
         */
        public void setPageNum(int pageNum) {
            if (pageNum < 0) {
                throw new IllegalArgumentException("CDEMO-CT02-PAGE-NUM is PIC 9(08), which is "
                        + "unsigned, so it cannot hold " + pageNum);
            }
            if (pageNum > MAX_PAGE_NUM) {
                throw new IllegalArgumentException("CDEMO-CT02-PAGE-NUM is PIC 9(08), so it cannot "
                        + "hold " + pageNum + "; the largest value it can carry is " + MAX_PAGE_NUM);
            }
            this.pageNum = pageNum;
        }

        private static final int MAX_PAGE_NUM = 99_999_999;

        /**
         * {@code CDEMO-CT02-NEXT-PAGE-FLG} - the one-character next-page flag.
         *
         * @return {@code "Y"}, {@code "N"} or any other single character the caller stored
         */
        public String getNextPageFlg() {
            return nextPageFlg;
        }

        /**
         * Stores {@code CDEMO-CT02-NEXT-PAGE-FLG} through the {@code PIC X(01)} move rule.
         *
         * <p>Any single character is accepted rather than only {@code 'Y'} and {@code 'N'}. The two
         * {@code 88}-levels are <em>tests</em> on the item, not a constraint over it: COBOL would
         * happily hold a third value and report false for both conditions, and that state has to
         * survive the round trip.
         *
         * @param nextPageFlg the flag; padded or truncated on the right to one character
         * @throws NullPointerException if {@code nextPageFlg} is {@code null}
         */
        public void setNextPageFlg(String nextPageFlg) {
            this.nextPageFlg =
                    movePicX(nextPageFlg, NEXT_PAGE_FLG_LENGTH, "CDEMO-CT02-NEXT-PAGE-FLG");
        }

        /**
         * {@code 88 NEXT-PAGE-YES VALUE 'Y'}, {@code app/cbl/COTRN02C.cbl:77}.
         *
         * @return {@code true} when the flag holds {@code 'Y'}
         */
        @JsonIgnore
        public boolean isNextPageYes() {
            return NEXT_PAGE_YES_VALUE.equals(nextPageFlg);
        }

        /**
         * {@code 88 NEXT-PAGE-NO VALUE 'N'}, line 78. True on a fresh instance, because the item
         * declares {@code VALUE 'N'}.
         *
         * @return {@code true} when the flag holds {@code 'N'}
         */
        @JsonIgnore
        public boolean isNextPageNo() {
            return NEXT_PAGE_NO_VALUE.equals(nextPageFlg);
        }

        /**
         * Reproduces {@code SET NEXT-PAGE-YES TO TRUE} by storing the condition's declared literal.
         */
        @JsonIgnore
        public void setNextPageYes() {
            this.nextPageFlg = NEXT_PAGE_YES_VALUE;
        }

        /**
         * Reproduces {@code SET NEXT-PAGE-NO TO TRUE} by storing the condition's declared literal.
         */
        @JsonIgnore
        public void setNextPageNo() {
            this.nextPageFlg = NEXT_PAGE_NO_VALUE;
        }

        /**
         * {@code CDEMO-CT02-TRN-SEL-FLG} - the one-character selection flag.
         *
         * @return the flag, or a space
         */
        public String getTrnSelFlg() {
            return trnSelFlg;
        }

        /**
         * Stores {@code CDEMO-CT02-TRN-SEL-FLG} through the {@code PIC X(01)} move rule.
         *
         * @param trnSelFlg the flag; padded or truncated on the right to one character
         * @throws NullPointerException if {@code trnSelFlg} is {@code null}
         */
        public void setTrnSelFlg(String trnSelFlg) {
            this.trnSelFlg = movePicX(trnSelFlg, TRN_SEL_FLG_LENGTH, "CDEMO-CT02-TRN-SEL-FLG");
        }

        /**
         * {@code CDEMO-CT02-TRN-SELECTED} - the identifier the operator selected on the calling
         * screen, untrimmed and exactly 16 characters.
         *
         * <p>{@code app/cbl/COTRN02C.cbl:124} tests this against {@code SPACES AND LOW-VALUES} and
         * {@code :126} moves it into {@code CARDNINI} when set, so both figurative constants are
         * meaningful here and {@link #lowValues(int)} exists to express the second.
         *
         * @return the selected identifier, or spaces
         */
        public String getTrnSelected() {
            return trnSelected;
        }

        /**
         * Stores {@code CDEMO-CT02-TRN-SELECTED} through the {@code PIC X(16)} move rule.
         *
         * @param trnSelected the identifier; padded or truncated on the right to 16 characters
         * @throws NullPointerException if {@code trnSelected} is {@code null}
         */
        public void setTrnSelected(String trnSelected) {
            this.trnSelected =
                    movePicX(trnSelected, TRN_SELECTED_LENGTH, "CDEMO-CT02-TRN-SELECTED");
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Ct02Info that)) {
                return false;
            }
            return pageNum == that.pageNum
                    && trnidFirst.equals(that.trnidFirst)
                    && trnidLast.equals(that.trnidLast)
                    && nextPageFlg.equals(that.nextPageFlg)
                    && trnSelFlg.equals(that.trnSelFlg)
                    && trnSelected.equals(that.trnSelected);
        }

        @Override
        public int hashCode() {
            return Objects.hash(trnidFirst, trnidLast, pageNum, nextPageFlg, trnSelFlg,
                    trnSelected);
        }

        @Override
        public String toString() {
            return "Ct02Info[CDEMO-CT02-TRNID-FIRST='" + trnidFirst
                    + "', CDEMO-CT02-TRNID-LAST='" + trnidLast
                    + "', CDEMO-CT02-PAGE-NUM=" + pageNum
                    + ", CDEMO-CT02-NEXT-PAGE-FLG='" + nextPageFlg
                    + "', CDEMO-CT02-TRN-SEL-FLG='" + trnSelFlg
                    + "', CDEMO-CT02-TRN-SELECTED='" + trnSelected + "']";
        }
    }

    // =================================================================================================
    // Instance state.
    //
    // The 21 payload items, each at its declared width and initialised to spaces so that an instance is
    // always a valid, fully formed 555-byte group image and never a partially populated one.
    // =================================================================================================

    private String trnnameo = ScreenFieldImage.unpainted(TRNNAMEO_LENGTH);
    private String title01o = ScreenFieldImage.unpainted(TITLE01O_LENGTH);
    private String curdateo = ScreenFieldImage.unpainted(CURDATEO_LENGTH);
    private String pgmnameo = ScreenFieldImage.unpainted(PGMNAMEO_LENGTH);
    private String title02o = ScreenFieldImage.unpainted(TITLE02O_LENGTH);
    private String curtimeo = ScreenFieldImage.unpainted(CURTIMEO_LENGTH);
    private String actidino = ScreenFieldImage.unpainted(ACTIDINO_LENGTH);
    private String cardnino = ScreenFieldImage.unpainted(CARDNINO_LENGTH);
    private String ttypcdo = ScreenFieldImage.unpainted(TTYPCDO_LENGTH);
    private String tcatcdo = ScreenFieldImage.unpainted(TCATCDO_LENGTH);
    private String trnsrco = ScreenFieldImage.unpainted(TRNSRCO_LENGTH);
    private String tdesco = ScreenFieldImage.unpainted(TDESCO_LENGTH);
    private String trnamto = ScreenFieldImage.unpainted(TRNAMTO_LENGTH);
    private String torigdto = ScreenFieldImage.unpainted(TORIGDTO_LENGTH);
    private String tprocdto = ScreenFieldImage.unpainted(TPROCDTO_LENGTH);
    private String mido = ScreenFieldImage.unpainted(MIDO_LENGTH);
    private String mnameo = ScreenFieldImage.unpainted(MNAMEO_LENGTH);
    private String mcityo = ScreenFieldImage.unpainted(MCITYO_LENGTH);
    private String mzipo = ScreenFieldImage.unpainted(MZIPO_LENGTH);
    private String confirmo = ScreenFieldImage.unpainted(CONFIRMO_LENGTH);
    private String errmsgo = ScreenFieldImage.unpainted(ERRMSGO_LENGTH);

    /**
     * The {@code xxxC} / {@code xxxP} / {@code xxxH} / {@code xxxV} quad of each of the 21 fields.
     * Every field has an entry from construction, so a highlight target is always reachable and
     * gate <strong>G38</strong> never depends on lazy creation.
     */
    private final Map<ScreenField, FieldMetadata> metadata = newMetadataMap();

    private String nextProgram = spaces(NavigationContext.TO_PROGRAM_LENGTH);
    private String nextMapset = MAPSET_NAME;
    private String nextMap = MAP_NAME;

    private NavigationContext navigationContext = NavigationContext.empty();
    private Ct02Info ct02Info = new Ct02Info();

    /**
     * Creates a response whose 21 payload items are all spaces, whose 84 attribute items are all
     * {@link BmsAttributes#DFHDFCOL}, whose navigation targets name this screen, and whose commarea
     * and cursor are in their declared initial states.
     *
     * <p>Constructible with no arguments and with no Spring context, which is what lets the parity
     * tests assert against it directly (practice <strong>B10</strong>).
     */
    public TransactionViewResponse() {
        // Every field carries its declared initial value; see the field declarations above.
    }

    private static Map<ScreenField, FieldMetadata> newMetadataMap() {
        Map<ScreenField, FieldMetadata> created = new EnumMap<>(ScreenField.class);
        for (ScreenField field : ScreenField.values()) {
            created.put(field, new FieldMetadata());
        }
        return created;
    }

    // =================================================================================================
    // The 21 payload accessors, in copybook declaration order.
    //
    // JavaBean getters and setters so Jackson binds them as the 21 payload members, and so a
    // space-padded value survives serialise-then-deserialise untrimmed: the setter pads and truncates
    // to the declared width but never trims, and there is no @JsonInclude or naming strategy declared
    // here - config/WebConfig owns Jackson configuration module-wide (practice B8).
    //
    // Each getter carries @JsonProperty naming its xxxI item in lower case. The identifier keeps the
    // xxxO suffix because that is the map view this type projects; the WIRE name drops it, because AAP
    // 0.6.3 derives payload names from the xxxI items only and because the paired request has to be
    // able to accept this response back field for field.
    // =================================================================================================

    /**
     * {@code TRNNAMEO PIC X(4)} - the transaction identifier shown in the header.
     * {@code app/cbl/COTRN02C.cbl:558} moves {@code WS-TRANID} here.
     *
     * @return four characters, untrimmed
     */
    @JsonProperty("trnname")
    public String getTrnnameo() {
        return trnnameo;
    }

    /**
     * Stores {@code TRNNAMEO} through the {@code PIC X(4)} move rule.
     *
     * @param trnnameo the transaction identifier; padded or truncated on the right to 4 characters
     * @throws NullPointerException if {@code trnnameo} is {@code null}
     */
    public void setTrnnameo(String trnnameo) {
        this.trnnameo = movePicX(trnnameo, TRNNAMEO_LENGTH, "TRNNAMEO");
    }

    /**
     * {@code TITLE01O PIC X(40)} - the first title line.
     * {@code app/cbl/COTRN02C.cbl:556} moves {@code CCDA-TITLE01} here; see
     * {@link ScreenTitles#CCDA_TITLE01}.
     *
     * @return forty characters, untrimmed
     */
    @JsonProperty("title01")
    public String getTitle01o() {
        return title01o;
    }

    /**
     * Stores {@code TITLE01O} through the {@code PIC X(40)} move rule.
     *
     * @param title01o the title; padded or truncated on the right to 40 characters
     * @throws NullPointerException if {@code title01o} is {@code null}
     */
    public void setTitle01o(String title01o) {
        this.title01o = movePicX(title01o, TITLE01O_LENGTH, "TITLE01O");
    }

    /**
     * {@code CURDATEO PIC X(8)} - the current date as {@code mm/dd/yy}, matching the map's
     * {@code INITIAL='mm/dd/yy'}. {@code app/cbl/COTRN02C.cbl:565} moves
     * {@code WS-CURDATE-MM-DD-YY} here.
     *
     * @return eight characters, untrimmed
     */
    @JsonProperty("curdate")
    public String getCurdateo() {
        return curdateo;
    }

    /**
     * Stores {@code CURDATEO} through the {@code PIC X(8)} move rule.
     *
     * @param curdateo the date image; padded or truncated on the right to 8 characters
     * @throws NullPointerException if {@code curdateo} is {@code null}
     */
    public void setCurdateo(String curdateo) {
        this.curdateo = movePicX(curdateo, CURDATEO_LENGTH, "CURDATEO");
    }

    /**
     * {@code PGMNAMEO PIC X(8)} - the program name shown in the header.
     * {@code app/cbl/COTRN02C.cbl:559} moves {@code WS-PGMNAME} here.
     *
     * @return eight characters, untrimmed
     */
    @JsonProperty("pgmname")
    public String getPgmnameo() {
        return pgmnameo;
    }

    /**
     * Stores {@code PGMNAMEO} through the {@code PIC X(8)} move rule.
     *
     * @param pgmnameo the program name; padded or truncated on the right to 8 characters
     * @throws NullPointerException if {@code pgmnameo} is {@code null}
     */
    public void setPgmnameo(String pgmnameo) {
        this.pgmnameo = movePicX(pgmnameo, PGMNAMEO_LENGTH, "PGMNAMEO");
    }

    /**
     * {@code TITLE02O PIC X(40)} - the second title line.
     * {@code app/cbl/COTRN02C.cbl:557} moves {@code CCDA-TITLE02} here; see
     * {@link ScreenTitles#CCDA_TITLE02}.
     *
     * @return forty characters, untrimmed
     */
    @JsonProperty("title02")
    public String getTitle02o() {
        return title02o;
    }

    /**
     * Stores {@code TITLE02O} through the {@code PIC X(40)} move rule.
     *
     * @param title02o the title; padded or truncated on the right to 40 characters
     * @throws NullPointerException if {@code title02o} is {@code null}
     */
    public void setTitle02o(String title02o) {
        this.title02o = movePicX(title02o, TITLE02O_LENGTH, "TITLE02O");
    }

    /**
     * {@code CURTIMEO PIC X(8)} - the current time as {@code hh:mm:ss}, matching the map's
     * {@code INITIAL='hh:mm:ss'}. {@code app/cbl/COTRN02C.cbl:571} moves
     * {@code WS-CURTIME-HH-MM-SS} here.
     *
     * @return eight characters, untrimmed
     */
    @JsonProperty("curtime")
    public String getCurtimeo() {
        return curtimeo;
    }

    /**
     * Stores {@code CURTIMEO} through the {@code PIC X(8)} move rule.
     *
     * @param curtimeo the time image; padded or truncated on the right to 8 characters
     * @throws NullPointerException if {@code curtimeo} is {@code null}
     */
    public void setCurtimeo(String curtimeo) {
        this.curtimeo = movePicX(curtimeo, CURTIMEO_LENGTH, "CURTIMEO");
    }

    /**
     * {@code ACTIDINO PIC X(11)} - the account identifier echoed back to the screen.
     *
     * <p>Eleven characters, carried in full and unmasked (practice <strong>B6</strong>, gate
     * <strong>G41</strong>). It stays a {@code String}: {@code app/cbl/COTRN02C.cbl:204} applies
     * {@code FUNCTION NUMVAL} to it, but that is the controller's parsing step and is not a reason to
     * type the screen field as a number - the screen holds the characters the operator typed,
     * including any that are not numeric, and those have to survive so that the validation branch can
     * reject them.
     *
     * @return eleven characters, untrimmed
     */
    @JsonProperty("actidin")
    public String getActidino() {
        return actidino;
    }

    /**
     * Stores {@code ACTIDINO} through the {@code PIC X(11)} move rule.
     *
     * @param actidino the account identifier; padded or truncated on the right to 11 characters
     * @throws NullPointerException if {@code actidino} is {@code null}
     */
    public void setActidino(String actidino) {
        this.actidino = movePicX(actidino, ACTIDINO_LENGTH, "ACTIDINO");
    }

    /**
     * {@code CARDNINO PIC X(16)} - the card number echoed back to the screen.
     *
     * <p>Sixteen characters, carried in full and <strong>unmasked</strong>. The legacy screen
     * displays the card number in clear, so masking or truncating it here would be a behaviour
     * change, which this migration forbids (practice <strong>B6</strong>, gate <strong>G41</strong>).
     * It stays a {@code String} for the same reason as {@link #getActidino()}, with
     * {@code FUNCTION NUMVAL} applied by the controller at {@code app/cbl/COTRN02C.cbl:218}.
     *
     * @return sixteen characters, untrimmed
     */
    @JsonProperty("cardnin")
    public String getCardnino() {
        return cardnino;
    }

    /**
     * Stores {@code CARDNINO} through the {@code PIC X(16)} move rule.
     *
     * @param cardnino the card number; padded or truncated on the right to 16 characters
     * @throws NullPointerException if {@code cardnino} is {@code null}
     */
    public void setCardnino(String cardnino) {
        this.cardnino = movePicX(cardnino, CARDNINO_LENGTH, "CARDNINO");
    }

    /**
     * {@code TTYPCDO PIC X(2)} - the transaction type code.
     * {@code app/cbl/COTRN02C.cbl:482} moves {@code TRAN-TYPE-CD PIC X(02)} here.
     *
     * @return two characters, untrimmed
     */
    @JsonProperty("ttypcd")
    public String getTtypcdo() {
        return ttypcdo;
    }

    /**
     * Stores {@code TTYPCDO} through the {@code PIC X(2)} move rule.
     *
     * @param ttypcdo the type code; padded or truncated on the right to 2 characters
     * @throws NullPointerException if {@code ttypcdo} is {@code null}
     */
    public void setTtypcdo(String ttypcdo) {
        this.ttypcdo = movePicX(ttypcdo, TTYPCDO_LENGTH, "TTYPCDO");
    }

    /**
     * {@code TCATCDO PIC X(4)} - the transaction category code.
     * {@code app/cbl/COTRN02C.cbl:483} moves {@code TRAN-CAT-CD PIC 9(04)} here; the screen item is
     * alphanumeric even though the record item is numeric, because the operator may type
     * non-numeric characters that {@code :329} then rejects.
     *
     * @return four characters, untrimmed
     */
    @JsonProperty("tcatcd")
    public String getTcatcdo() {
        return tcatcdo;
    }

    /**
     * Stores {@code TCATCDO} through the {@code PIC X(4)} move rule.
     *
     * @param tcatcdo the category code; padded or truncated on the right to 4 characters
     * @throws NullPointerException if {@code tcatcdo} is {@code null}
     */
    public void setTcatcdo(String tcatcdo) {
        this.tcatcdo = movePicX(tcatcdo, TCATCDO_LENGTH, "TCATCDO");
    }

    /**
     * {@code TRNSRCO PIC X(10)} - the transaction source.
     * {@code app/cbl/COTRN02C.cbl:484} moves {@code TRAN-SOURCE PIC X(10)} here.
     *
     * @return ten characters, untrimmed
     */
    @JsonProperty("trnsrc")
    public String getTrnsrco() {
        return trnsrco;
    }

    /**
     * Stores {@code TRNSRCO} through the {@code PIC X(10)} move rule.
     *
     * @param trnsrco the source; padded or truncated on the right to 10 characters
     * @throws NullPointerException if {@code trnsrco} is {@code null}
     */
    public void setTrnsrco(String trnsrco) {
        this.trnsrco = movePicX(trnsrco, TRNSRCO_LENGTH, "TRNSRCO");
    }

    /**
     * {@code TDESCO PIC X(60)} - the transaction description.
     *
     * <p>Sixty characters on the screen against {@code TRAN-DESC PIC X(100)} in the record, so
     * {@code app/cbl/COTRN02C.cbl:486} truncates 100 to 60 on the way out and {@code :455} pads 60
     * to 100 on the way back. Both are ordinary {@code PIC X} moves and both are reproduced here.
     *
     * @return sixty characters, untrimmed
     */
    @JsonProperty("tdesc")
    public String getTdesco() {
        return tdesco;
    }

    /**
     * Stores {@code TDESCO} through the {@code PIC X(60)} move rule.
     *
     * @param tdesco the description; padded or truncated on the right to 60 characters
     * @throws NullPointerException if {@code tdesco} is {@code null}
     */
    public void setTdesco(String tdesco) {
        this.tdesco = movePicX(tdesco, TDESCO_LENGTH, "TDESCO");
    }

    /**
     * {@code TRNAMTO PIC X(12)} - the transaction amount in its <strong>edited</strong> form.
     *
     * <p>This field carries the twelve-character mask {@code +99999999.99} declared at
     * {@code app/cbl/COTRN02C.cbl:53} and {@code :59}: one sign character, eight integer digits, a
     * literal decimal point, then two fraction digits. The program validates it positionally at
     * {@code :340} to {@code :343} - character 1 must be {@code '-'} or {@code '+'}, characters 2 to
     * 9 numeric, character 10 a {@code '.'}, characters 11 and 12 numeric - which is what fixes the
     * layout to exactly those twelve positions.
     *
     * <p><strong>The eight integer digits are deliberate and must not be widened.</strong>
     * {@code TRAN-AMT} in {@code app/cpy/CVTRA05Y.cpy} is {@code PIC S9(09)V99} and so holds
     * <em>nine</em> integer digits, which means {@code :481}'s
     * {@code MOVE TRAN-AMT TO WS-TRAN-AMT-E} followed by {@code :485}'s
     * {@code MOVE WS-TRAN-AMT-E TO TRNAMTI} genuinely <em>left-truncates</em> the ninth digit for any
     * amount of a hundred million or more. That is the legacy behaviour, and reproducing it is the
     * point; widening this field to thirteen characters to "fix" it would be a behaviour change and a
     * parity failure.
     *
     * <p>It is a {@code String} and not a {@code BigDecimal} because a mask is text: it carries a
     * sign character and a literal point, and it must round trip byte for byte. See this class's
     * Javadoc for why no numeric accessor is offered alongside it.
     *
     * @return twelve characters, untrimmed
     */
    @JsonProperty("trnamt")
    public String getTrnamto() {
        return trnamto;
    }

    /**
     * Stores {@code TRNAMTO} through the {@code PIC X(12)} move rule.
     *
     * <p>The mask is not validated here. {@code app/cbl/COTRN02C.cbl:340} to {@code :343} is a
     * validation step that runs in the program and reports a message on failure; if this setter
     * rejected a malformed amount, the controller could never store the operator's bad input in order
     * to redisplay it, and the error path would be unreachable.
     *
     * @param trnamto the edited amount; padded or truncated on the right to 12 characters
     * @throws NullPointerException if {@code trnamto} is {@code null}
     */
    public void setTrnamto(String trnamto) {
        this.trnamto = movePicX(trnamto, TRNAMTO_LENGTH, "TRNAMTO");
    }

    /**
     * {@code TORIGDTO PIC X(10)} - the origination date as {@code YYYY-MM-DD}.
     *
     * <p>Validated by the program in two stages: positionally at {@code app/cbl/COTRN02C.cbl:354} to
     * {@code :358}, then by {@code CALL 'CSUTLDTC'} at {@code :393} with the
     * {@code 'YYYY-MM-DD'} mask, accepted when the severity is {@code '0000'} or, failing that, when
     * the message number is {@code '2513'} ({@code :397} to {@code :407}). That logic belongs to the
     * controller and the date utility; here the field is simply ten characters.
     *
     * @return ten characters, untrimmed
     */
    @JsonProperty("torigdt")
    public String getTorigdto() {
        return torigdto;
    }

    /**
     * Stores {@code TORIGDTO} through the {@code PIC X(10)} move rule.
     *
     * @param torigdto the date image; padded or truncated on the right to 10 characters
     * @throws NullPointerException if {@code torigdto} is {@code null}
     */
    public void setTorigdto(String torigdto) {
        this.torigdto = movePicX(torigdto, TORIGDTO_LENGTH, "TORIGDTO");
    }

    /**
     * {@code TPROCDTO PIC X(10)} - the processing date as {@code YYYY-MM-DD}, validated the same way
     * as {@link #getTorigdto()} at {@code app/cbl/COTRN02C.cbl:369} to {@code :373} and by
     * {@code CALL 'CSUTLDTC'} at {@code :413}.
     *
     * @return ten characters, untrimmed
     */
    @JsonProperty("tprocdt")
    public String getTprocdto() {
        return tprocdto;
    }

    /**
     * Stores {@code TPROCDTO} through the {@code PIC X(10)} move rule.
     *
     * @param tprocdto the date image; padded or truncated on the right to 10 characters
     * @throws NullPointerException if {@code tprocdto} is {@code null}
     */
    public void setTprocdto(String tprocdto) {
        this.tprocdto = movePicX(tprocdto, TPROCDTO_LENGTH, "TPROCDTO");
    }

    /**
     * {@code MIDO PIC X(9)} - the merchant identifier.
     *
     * <p>Nine characters, carried in full and unmasked (practice <strong>B6</strong>, gate
     * <strong>G41</strong>). {@code app/cbl/COTRN02C.cbl:489} moves
     * {@code TRAN-MERCHANT-ID PIC 9(09)} here.
     *
     * @return nine characters, untrimmed
     */
    @JsonProperty("mid")
    public String getMido() {
        return mido;
    }

    /**
     * Stores {@code MIDO} through the {@code PIC X(9)} move rule.
     *
     * @param mido the merchant identifier; padded or truncated on the right to 9 characters
     * @throws NullPointerException if {@code mido} is {@code null}
     */
    public void setMido(String mido) {
        this.mido = movePicX(mido, MIDO_LENGTH, "MIDO");
    }

    /**
     * {@code MNAMEO PIC X(30)} - the merchant name. Thirty characters on the screen against
     * {@code TRAN-MERCHANT-NAME PIC X(50)} in the record, so
     * {@code app/cbl/COTRN02C.cbl:490} truncates 50 to 30 on the right.
     *
     * @return thirty characters, untrimmed
     */
    @JsonProperty("mname")
    public String getMnameo() {
        return mnameo;
    }

    /**
     * Stores {@code MNAMEO} through the {@code PIC X(30)} move rule.
     *
     * @param mnameo the merchant name; padded or truncated on the right to 30 characters
     * @throws NullPointerException if {@code mnameo} is {@code null}
     */
    public void setMnameo(String mnameo) {
        this.mnameo = movePicX(mnameo, MNAMEO_LENGTH, "MNAMEO");
    }

    /**
     * {@code MCITYO PIC X(25)} - the merchant city. Twenty-five characters against
     * {@code TRAN-MERCHANT-CITY PIC X(50)}, truncated on the right at
     * {@code app/cbl/COTRN02C.cbl:491}.
     *
     * @return twenty-five characters, untrimmed
     */
    @JsonProperty("mcity")
    public String getMcityo() {
        return mcityo;
    }

    /**
     * Stores {@code MCITYO} through the {@code PIC X(25)} move rule.
     *
     * @param mcityo the merchant city; padded or truncated on the right to 25 characters
     * @throws NullPointerException if {@code mcityo} is {@code null}
     */
    public void setMcityo(String mcityo) {
        this.mcityo = movePicX(mcityo, MCITYO_LENGTH, "MCITYO");
    }

    /**
     * {@code MZIPO PIC X(10)} - the merchant postal code, the same width as
     * {@code TRAN-MERCHANT-ZIP PIC X(10)}; moved at {@code app/cbl/COTRN02C.cbl:492}.
     *
     * @return ten characters, untrimmed
     */
    @JsonProperty("mzip")
    public String getMzipo() {
        return mzipo;
    }

    /**
     * Stores {@code MZIPO} through the {@code PIC X(10)} move rule.
     *
     * @param mzipo the postal code; padded or truncated on the right to 10 characters
     * @throws NullPointerException if {@code mzipo} is {@code null}
     */
    public void setMzipo(String mzipo) {
        this.mzipo = movePicX(mzipo, MZIPO_LENGTH, "MZIPO");
    }

    /**
     * {@code CONFIRMO PIC X(1)} - the add-confirmation flag echoed back to the screen.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:169} to {@code :180} evaluates the operator's answer, accepting
     * {@code 'Y'} and {@code 'y'} to add the transaction, {@code 'N'}, {@code 'n'} and
     * {@code SPACES} to decline, and reporting an error otherwise. Because a rejected value must be
     * redisplayed, this field is not constrained to the accepted set.
     *
     * <p>The existence of this field is part of the evidence that this is the add screen; see the
     * risk R-B note on this class.
     *
     * @return one character, untrimmed
     */
    @JsonProperty("confirm")
    public String getConfirmo() {
        return confirmo;
    }

    /**
     * Stores {@code CONFIRMO} through the {@code PIC X(1)} move rule.
     *
     * @param confirmo the confirmation flag; padded or truncated on the right to 1 character
     * @throws NullPointerException if {@code confirmo} is {@code null}
     */
    public void setConfirmo(String confirmo) {
        this.confirmo = movePicX(confirmo, CONFIRMO_LENGTH, "CONFIRMO");
    }

    /**
     * {@code ERRMSGO PIC X(78)} - the error line on row 23, painted
     * {@code ATTRB=(ASKIP,BRT,FSET) COLOR=RED}.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:520} moves {@code WS-MESSAGE} here immediately before every
     * {@code SEND}, and {@code :113} clears it to spaces at the top of the program.
     *
     * @return seventy-eight characters, untrimmed - a message shorter than the field is space-padded
     *     and stays that way through a JSON round trip
     */
    @JsonProperty("errmsg")
    public String getErrmsgo() {
        return errmsgo;
    }

    /**
     * Stores {@code ERRMSGO} through the {@code PIC X(78)} move rule.
     *
     * <p>Reproduces {@code app/cbl/COTRN02C.cbl:520}
     * {@code MOVE WS-MESSAGE TO ERRMSGO OF COTRN2AO}. {@code WS-MESSAGE} is {@code PIC X(80)} at
     * {@code :38}, so the move discards its last two characters; passing an 80-character message here
     * reproduces that truncation exactly.
     *
     * @param errmsgo the message; padded or truncated on the right to 78 characters
     * @throws NullPointerException if {@code errmsgo} is {@code null}
     */
    public void setErrmsgo(String errmsgo) {
        this.errmsgo = movePicX(errmsgo, ERRMSGO_LENGTH, "ERRMSGO");
    }

    // =================================================================================================
    // Generic access to the 21 payload items by field, so a caller that already holds a ScreenField -
    // notably the highlight application below and the fixed-width rendering - does not need a
    // 21-way switch of its own duplicated in three places.
    // =================================================================================================

    /**
     * The current value of a field's {@code xxxO} payload item.
     *
     * @param field the field to read
     * @return the value at exactly {@link ScreenField#width()} characters, untrimmed
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public String getOutputItem(ScreenField field) {
        Objects.requireNonNull(field, "A screen field is required");
        return switch (field) {
            case TRNNAME -> trnnameo;
            case TITLE01 -> title01o;
            case CURDATE -> curdateo;
            case PGMNAME -> pgmnameo;
            case TITLE02 -> title02o;
            case CURTIME -> curtimeo;
            case ACTIDIN -> actidino;
            case CARDNIN -> cardnino;
            case TTYPCD -> ttypcdo;
            case TCATCD -> tcatcdo;
            case TRNSRC -> trnsrco;
            case TDESC -> tdesco;
            case TRNAMT -> trnamto;
            case TORIGDT -> torigdto;
            case TPROCDT -> tprocdto;
            case MID -> mido;
            case MNAME -> mnameo;
            case MCITY -> mcityo;
            case MZIP -> mzipo;
            case CONFIRM -> confirmo;
            case ERRMSG -> errmsgo;
        };
    }

    /**
     * Stores a field's {@code xxxO} payload item through the {@code PIC X} move rule for its declared
     * width.
     *
     * @param field the field to write
     * @param value the value; padded or truncated on the right to the field's declared width
     * @throws NullPointerException if {@code field} or {@code value} is {@code null}
     */
    @JsonIgnore
    public void setOutputItem(ScreenField field, String value) {
        Objects.requireNonNull(field, "A screen field is required");
        switch (field) {
            case TRNNAME -> setTrnnameo(value);
            case TITLE01 -> setTitle01o(value);
            case CURDATE -> setCurdateo(value);
            case PGMNAME -> setPgmnameo(value);
            case TITLE02 -> setTitle02o(value);
            case CURTIME -> setCurtimeo(value);
            case ACTIDIN -> setActidino(value);
            case CARDNIN -> setCardnino(value);
            case TTYPCD -> setTtypcdo(value);
            case TCATCD -> setTcatcdo(value);
            case TRNSRC -> setTrnsrco(value);
            case TDESC -> setTdesco(value);
            case TRNAMT -> setTrnamto(value);
            case TORIGDT -> setTorigdto(value);
            case TPROCDT -> setTprocdto(value);
            case MID -> setMido(value);
            case MNAME -> setMnameo(value);
            case MCITY -> setMcityo(value);
            case MZIP -> setMzipo(value);
            case CONFIRM -> setConfirmo(value);
            case ERRMSG -> setErrmsgo(value);
        }
    }

    // =================================================================================================
    // Highlight metadata access and application.
    //
    // The DECISION - whether a field is highlighted at all - belongs to common/FieldAttributeSetter,
    // which owns app/cpy/CSSETATY.cpy's rule and takes REENTER as an explicit boolean. This class only
    // exposes the two items that rule writes into, and applies a decision it is handed. Keeping the two
    // apart is what makes gate G38 testable: a caller can resolve a highlight for ENTER and for REENTER
    // and observe that only the REENTER one changes this object.
    // =================================================================================================

    /**
     * The {@code xxxC} / {@code xxxP} / {@code xxxH} / {@code xxxV} quad of one field.
     *
     * <p>Returned live, not copied, because {@code app/cpy/CSSETATY.cpy} writes into the colour item
     * of an existing output group and this is the item it writes into.
     *
     * <p>Excluded from the JSON payload: these are attribute bytes, not screen data, per Agent Action
     * Plan section 0.6.3.
     *
     * @param field the field whose attribute items are wanted
     * @return the live quad, never {@code null} - every field has one from construction
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public FieldMetadata getMetadata(ScreenField field) {
        Objects.requireNonNull(field, "A screen field is required");
        return metadata.get(field);
    }

    /**
     * All 21 attribute quads, keyed by field.
     *
     * <p>The map itself is unmodifiable - a field cannot be added or removed, because the map is fixed
     * by the copybook - while the {@link FieldMetadata} values remain live and mutable, which is what
     * a highlight needs.
     *
     * @return an unmodifiable view over the 21 live quads
     */
    @JsonIgnore
    public Map<ScreenField, FieldMetadata> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    /**
     * Applies a highlight decision produced by {@link FieldAttributeSetter}, reproducing
     * {@code app/cpy/CSSETATY.cpy}:
     *
     * <pre>
     * IF (FLG-(TESTVAR1)-NOT-OK OR FLG-(TESTVAR1)-BLANK) AND CDEMO-PGM-REENTER
     *     MOVE DFHRED TO (SCRNVAR2)C OF (MAPNAME3)O
     *     IF FLG-(TESTVAR1)-BLANK
     *         MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O
     *     END-IF
     * END-IF
     * </pre>
     *
     * <p>The two writes are applied only when the decision says they were made, so an
     * {@link FieldHighlight#untouched()} decision - which is what
     * {@link FieldAttributeSetter#resolve} returns for a valid field, or for <em>any</em> field while
     * the program is in {@code ENTER} context - leaves this object completely unchanged. That is gate
     * <strong>G38</strong>: the highlight is reachable in {@code REENTER} and unreachable in
     * {@code ENTER}, and the reason it is unreachable lives in one place rather than being re-decided
     * here.
     *
     * <p>The field is resolved from {@link FieldHighlight#screenFieldPrefix()}, so a decision built
     * for a field of a different map is rejected rather than silently applied to the wrong item.
     *
     * @param highlight the resolved decision
     * @return {@code true} if anything was written, {@code false} for an untouched decision
     * @throws NullPointerException if {@code highlight} is {@code null}
     * @throws IllegalArgumentException if the decision names a field this map does not declare
     */
    public boolean applyHighlight(FieldHighlight highlight) {
        Objects.requireNonNull(highlight, "A resolved FieldHighlight is required; "
                + "FieldAttributeSetter.resolve(...) owns the decision, this method only applies it");
        if (highlight.untouched()) {
            return false;
        }
        ScreenField field = ScreenField.ofLabel(highlight.screenFieldPrefix());
        boolean written = false;
        if (highlight.colourItemAssigned()) {
            getMetadata(field).setColour(highlight.colourItemValue());
            written = true;
        }
        if (highlight.outputItemAssigned()) {
            setOutputItem(field, highlight.outputItemValue());
            written = true;
        }
        return written;
    }

    /**
     * Restores all 21 attribute quads to {@link BmsAttributes#DFHDFCOL}, discarding every highlight.
     *
     * <p>This is the attribute half of {@code app/cbl/COTRN02C.cbl:122}
     * {@code MOVE LOW-VALUES TO COTRN2AO}; see {@link #moveLowValuesToOutputMap()}, which does both
     * halves.
     */
    public void resetMetadata() {
        for (FieldMetadata quad : metadata.values()) {
            quad.reset();
        }
    }

    // =================================================================================================
    // Navigation - what replaces EXEC CICS XCTL (gate G40).
    //
    // app/cbl/COTRN02C.cbl:509 is XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA) inside
    // RETURN-TO-PREV-SCREEN. It is COMMAREA-driven rather than a literal, so nextProgram is echoed from
    // NavigationContext's CDEMO-TO-PROGRAM and the CLIENT issues the follow-up call. There is no
    // server-side forward and no redirect chain (rule R6, gate G37).
    //
    // These are plain fixed-width strings. No sibling DTO is imported to express a navigation target,
    // because a target is a name, not a payload.
    // =================================================================================================

    /**
     * The program the client should call next - what {@code app/cbl/COTRN02C.cbl:509}'s
     * {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} transferred to.
     *
     * <p>Eight characters, the width of {@code CDEMO-TO-PROGRAM PIC X(8)}. Spaces mean "stay on this
     * screen": the program only sets a target when it is leaving, and {@code :502} to {@code :504}
     * default it to {@code 'COSGN00C'} when it is spaces or low values at that point.
     *
     * @return eight characters, untrimmed
     */
    public String getNextProgram() {
        return nextProgram;
    }

    /**
     * Stores the next program through the {@code PIC X(8)} move rule.
     *
     * @param nextProgram the program name; padded or truncated on the right to 8 characters
     * @throws NullPointerException if {@code nextProgram} is {@code null}
     */
    public void setNextProgram(String nextProgram) {
        this.nextProgram =
                movePicX(nextProgram, NavigationContext.TO_PROGRAM_LENGTH, "CDEMO-TO-PROGRAM");
    }

    /**
     * The mapset of the next screen - {@link #MAPSET_NAME} while the conversation stays here.
     *
     * <p><strong>Seven characters, not eight.</strong> The width is that of
     * {@code CDEMO-LAST-MAPSET PIC X(7)} in {@code app/cpy/COCOM01Y.cpy}, confirmed by
     * {@link NavigationContext#LAST_MAPSET_LENGTH}. {@code COTRN02} is exactly seven characters, so
     * nothing is lost, but an eight-character field here would put a trailing space into every
     * comparison.
     *
     * @return seven characters, untrimmed
     */
    public String getNextMapset() {
        return nextMapset;
    }

    /**
     * Stores the next mapset through the {@code PIC X(7)} move rule.
     *
     * @param nextMapset the mapset name; padded or truncated on the right to 7 characters
     * @throws NullPointerException if {@code nextMapset} is {@code null}
     */
    public void setNextMapset(String nextMapset) {
        this.nextMapset =
                movePicX(nextMapset, NavigationContext.LAST_MAPSET_LENGTH, "CDEMO-LAST-MAPSET");
    }

    /**
     * The map of the next screen - {@link #MAP_NAME} while the conversation stays here.
     *
     * <p>Seven characters, the width of {@code CDEMO-LAST-MAP PIC X(7)}, confirmed by
     * {@link NavigationContext#LAST_MAP_LENGTH}. {@code COTRN2A} is exactly seven characters, which is
     * also why the symbolic groups are named {@code COTRN2AI} and {@code COTRN2AO}.
     *
     * @return seven characters, untrimmed
     */
    public String getNextMap() {
        return nextMap;
    }

    /**
     * Stores the next map through the {@code PIC X(7)} move rule.
     *
     * @param nextMap the map name; padded or truncated on the right to 7 characters
     * @throws NullPointerException if {@code nextMap} is {@code null}
     */
    public void setNextMap(String nextMap) {
        this.nextMap = movePicX(nextMap, NavigationContext.LAST_MAP_LENGTH, "CDEMO-LAST-MAP");
    }

    // =================================================================================================
    // The echoed conversation state: the 160-byte commarea and the 58-byte cursor.
    // =================================================================================================

    /**
     * {@code CARDDEMO-COMMAREA} - the 160-byte shared commarea from {@code app/cpy/COCOM01Y.cpy},
     * echoed so the client can send it back on the next call.
     *
     * <p>Exactly {@link NavigationContext#COMMAREA_LENGTH} bytes and <strong>not widened</strong>:
     * this type is shared by all 17 controllers, and this program's own 58-byte extension is carried
     * separately by {@link #getCt02Info()} rather than bolted onto it.
     *
     * @return the commarea, never {@code null}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    /**
     * Stores the commarea to echo.
     *
     * @param navigationContext the commarea
     * @throws NullPointerException if {@code navigationContext} is {@code null}
     */
    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext =
                Objects.requireNonNull(navigationContext, "A NavigationContext is required; use "
                        + "NavigationContext.empty() for the initial state rather than null, because "
                        + "COBOL has no null commarea");
    }

    /**
     * {@code CDEMO-CT02-INFO} - this program's 58-byte commarea extension, echoed so the pagination
     * and selection cursor survives without server-side state.
     *
     * @return the cursor, never {@code null}
     */
    public Ct02Info getCt02Info() {
        return ct02Info;
    }

    /**
     * Stores the cursor to echo.
     *
     * @param ct02Info the cursor
     * @throws NullPointerException if {@code ct02Info} is {@code null}
     */
    public void setCt02Info(Ct02Info ct02Info) {
        this.ct02Info = Objects.requireNonNull(ct02Info, "A Ct02Info is required; use "
                + "new Ct02Info() for the initial state rather than null");
    }

    // =================================================================================================
    // The send-path behaviour of app/cbl/COTRN02C.cbl, reproduced move for move.
    //
    // These are the only places this class writes more than one item at a time, and each corresponds to
    // one named paragraph or one cited line, so the correspondence stays checkable.
    // =================================================================================================

    /**
     * Reproduces {@code POPULATE-HEADER-INFO}, {@code app/cbl/COTRN02C.cbl:552} to {@code :571}, which
     * is performed at the top of {@code SEND-TRNADD-SCREEN} before every {@code SEND}:
     *
     * <pre>
     * MOVE CCDA-TITLE01        TO TITLE01O OF COTRN2AO   :556
     * MOVE CCDA-TITLE02        TO TITLE02O OF COTRN2AO   :557
     * MOVE WS-TRANID           TO TRNNAMEO OF COTRN2AO   :558
     * MOVE WS-PGMNAME          TO PGMNAMEO OF COTRN2AO   :559
     * MOVE WS-CURDATE-MM-DD-YY TO CURDATEO OF COTRN2AO   :565
     * MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO OF COTRN2AO   :571
     * </pre>
     *
     * <p>The titles come from {@link ScreenTitles}, which carries {@code app/cpy/COTTL01Y.cpy}'s
     * literals byte for byte at exactly the 40 characters {@code TITLE01O} and {@code TITLE02O}
     * declare. The transaction and program names are this screen's own {@link #TRANSACTION_ID} and
     * {@link #PROGRAM_ID}, matching {@code WS-TRANID} at {@code :37} and {@code WS-PGMNAME} at
     * {@code :36}.
     *
     * <p>The date and time come from the supplied {@link DateHeader} rather than from a clock read
     * inside this method, so a parity case can pin them and the result stays deterministic.
     * {@code app/cbl/COTRN02C.cbl:554} performs the corresponding
     * {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA}, and the two derived images it then builds
     * are exactly {@code DateHeader}'s {@code mm/dd/yy} and {@code hh:mm:ss} views, both eight
     * characters wide.
     *
     * @param dateHeader the date and time header to take {@code mm/dd/yy} and {@code hh:mm:ss} from
     * @throws NullPointerException if {@code dateHeader} is {@code null}
     */
    public void populateHeaderInfo(DateHeader dateHeader) {
        Objects.requireNonNull(dateHeader, "A DateHeader is required; COTRN02C:554 performs "
                + "MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA, so the caller supplies the "
                + "already-captured date and time rather than this method reading a clock");
        setTitle01o(ScreenTitles.CCDA_TITLE01);
        setTitle02o(ScreenTitles.CCDA_TITLE02);
        setTrnnameo(TRANSACTION_ID);
        setPgmnameo(PROGRAM_ID);
        setCurdateo(dateHeader.wsCurdateMmDdYy());
        setCurtimeo(dateHeader.wsCurtimeHhMmSs());
    }

    /**
     * Reproduces {@code app/cbl/COTRN02C.cbl:112} to {@code :113}
     * {@code MOVE SPACES TO WS-MESSAGE, ERRMSGO OF COTRN2AO} - the clearing of the error line at the
     * top of {@code MAIN-PARA}.
     */
    public void clearErrmsgo() {
        setErrmsgo(spaces(ERRMSGO_LENGTH));
    }

    /**
     * Reproduces the {@code WHEN OTHER} branch of {@code app/cbl/COTRN02C.cbl:148} to {@code :151} -
     * an unrecognised AID - by placing {@code CCDA-MSG-INVALID-KEY} on the error line:
     *
     * <pre>
     * WHEN OTHER
     *     MOVE 'Y'                  TO WS-ERR-FLG    :149
     *     MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE    :150
     *     PERFORM SEND-TRNADD-SCREEN                 :151  -> :520 MOVE WS-MESSAGE TO ERRMSGO
     * </pre>
     *
     * <p>{@code CCDA-MSG-INVALID-KEY} is {@code PIC X(50)} in {@code app/cpy/CSMSG01Y.cpy}, so
     * routing it through the {@code PIC X(78)} move rule space-pads it to 78 - exactly as the
     * two-step move through {@code WS-MESSAGE PIC X(80)} does.
     */
    public void setErrmsgoInvalidKey() {
        setErrmsgo(SystemMessages.CCDA_MSG_INVALID_KEY);
    }

    /**
     * Reproduces {@code app/cbl/COTRN02C.cbl:122} {@code MOVE LOW-VALUES TO COTRN2AO} - the whole
     * output group set to low values on the transition from {@code ENTER} into {@code REENTER}.
     *
     * <p>{@code MOVE LOW-VALUES} to a group item fills every byte of it, so this sets all 21 payload
     * items to {@link #lowValues(int)} and all 84 attribute items back to
     * {@link BmsAttributes#DFHDFCOL}, whose value {@code X'00'} is the same byte. The distinction
     * from spaces is real and is tested by the program itself at {@code :124} and {@code :137}, which
     * compare fields against {@code SPACES AND LOW-VALUES} as two separate values.
     */
    public void moveLowValuesToOutputMap() {
        for (ScreenField field : ScreenField.values()) {
            setOutputItem(field, lowValues(field.width()));
        }
        resetMetadata();
    }

    /**
     * Sets all 21 payload items to spaces, leaving the attribute items untouched.
     *
     * <p>This is the {@code MOVE SPACES} counterpart to {@link #moveLowValuesToOutputMap()} and the
     * shape {@code CLEAR-CURRENT-SCREEN} ({@code app/cbl/COTRN02C.cbl:145}) needs when it repaints an
     * empty form.
     */
    public void moveSpacesToOutputMap() {
        for (ScreenField field : ScreenField.values()) {
            setOutputItem(field, spaces(field.width()));
        }
    }

    // =================================================================================================
    // Fixed-width rendering of the 555-byte COTRN2AO group image (practice B11).
    //
    // The charset is ALWAYS supplied by the caller and NEVER defaulted from the platform (practice B8),
    // because a symbolic map image is bytes in a specific code page and this type is bound to neither.
    //
    // The payload items go through writeSpan / readSpan as text; the 84 attribute items go through
    // writeSpanBytes / readSpanBytes as raw bytes, because an attribute value such as DFHRED (X'F2') is
    // not a printable character and text conversion would corrupt it.
    // =================================================================================================

    /**
     * Renders this response as the 555-byte {@code COTRN2AO} group image.
     *
     * @param charset the code page to encode in; named explicitly by the caller, never defaulted
     * @return a new array of exactly {@link #SYMBOLIC_MAP_LENGTH} bytes
     * @throws NullPointerException if {@code charset} is {@code null}
     */
    public byte[] toFixedWidth(Charset charset) {
        Objects.requireNonNull(charset, "A charset is required; a COTRN2AO image is bytes in a "
                + "specific code page and must never be encoded with the platform default");
        FixedWidthRecord record = FixedWidthRecord.forLayout(LAYOUT, charset);
        writeInto(record);
        return record.toByteArray();
    }

    /**
     * Writes all 105 addressable items of this response into an existing record laid out by
     * {@link #LAYOUT}.
     *
     * <p>The 22 {@code FILLER} spans are not written here and do not need to be:
     * {@link FixedWidthRecord#forLayout} initialises them, and {@link #fromFixedWidth} preserves
     * whatever bytes they already hold. What matters for parity is that they are <em>present</em> and
     * accounted for in the geometry, which {@link #LAYOUT} guarantees.
     *
     * @param record the record to write into; must be laid out by {@link #LAYOUT}
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalArgumentException if {@code record} is not {@link #SYMBOLIC_MAP_LENGTH} bytes
     */
    public void writeInto(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A FixedWidthRecord is required");
        requireGroupLength(record.recordLength());
        for (ScreenField field : ScreenField.values()) {
            FieldSpans spans = FIELD_SPANS.get(field);
            FieldMetadata quad = metadata.get(field);
            record.writeSpanBytes(spans.colour(), new byte[] {quad.getColour()});
            record.writeSpanBytes(spans.ps(), new byte[] {quad.getProgrammedSymbols()});
            record.writeSpanBytes(spans.highlight(), new byte[] {quad.getHighlight()});
            record.writeSpanBytes(spans.validn(), new byte[] {quad.getValidation()});
            record.writeSpan(spans.output(), getOutputItem(field));
        }
    }

    /**
     * Rebuilds a response from a 555-byte {@code COTRN2AO} group image.
     *
     * <p>The round trip is lossless for all 105 addressable items, which matters because
     * {@code COTRN2AO} is not write-only: it aliases {@code COTRN2AI}, and {@code COTRN02C} reads and
     * writes through both views.
     *
     * <p>Navigation targets, the commarea and the cursor are <em>not</em> part of the group image and
     * are therefore left at their initial values - they travel in the JSON payload, not in the
     * symbolic map.
     *
     * @param image exactly {@link #SYMBOLIC_MAP_LENGTH} bytes
     * @param charset the code page {@code image} is encoded in; named explicitly, never defaulted
     * @return a new response carrying the image's 21 payload items and 84 attribute items
     * @throws NullPointerException if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not {@link #SYMBOLIC_MAP_LENGTH} bytes
     */
    public static TransactionViewResponse fromFixedWidth(byte[] image, Charset charset) {
        Objects.requireNonNull(image, "A COTRN2AO group image is required");
        Objects.requireNonNull(charset, "A charset is required; a COTRN2AO image is bytes in a "
                + "specific code page and must never be decoded with the platform default");
        requireGroupLength(image.length);
        FixedWidthRecord record =
                FixedWidthRecord.copyOf(image, SYMBOLIC_MAP_LENGTH, charset);
        TransactionViewResponse response = new TransactionViewResponse();
        response.readFrom(record);
        return response;
    }

    /**
     * Replaces this response's 21 payload items and 84 attribute items with those of a record laid
     * out by {@link #LAYOUT}.
     *
     * @param record the record to read; must be laid out by {@link #LAYOUT}
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalArgumentException if {@code record} is not {@link #SYMBOLIC_MAP_LENGTH} bytes
     */
    public void readFrom(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A FixedWidthRecord is required");
        requireGroupLength(record.recordLength());
        for (ScreenField field : ScreenField.values()) {
            FieldSpans spans = FIELD_SPANS.get(field);
            FieldMetadata quad = metadata.get(field);
            quad.setColour(record.readSpanBytes(spans.colour())[0]);
            quad.setProgrammedSymbols(record.readSpanBytes(spans.ps())[0]);
            quad.setHighlight(record.readSpanBytes(spans.highlight())[0]);
            quad.setValidation(record.readSpanBytes(spans.validn())[0]);
            setOutputItem(field, record.readSpan(spans.output()));
        }
    }

    private static void requireGroupLength(int length) {
        if (length != SYMBOLIC_MAP_LENGTH) {
            throw new IllegalArgumentException("01 COTRN2AO REDEFINES COTRN2AI is "
                    + SYMBOLIC_MAP_LENGTH + " bytes (" + TIOAPFX_LENGTH + " TIOAPFX + "
                    + FIELD_COUNT + " * " + PER_FIELD_PREFIX_LENGTH + " attribute prefix + "
                    + PAYLOAD_LENGTH + " payload) but " + length + " byte(s) were supplied");
        }
    }

    // =================================================================================================
    // Value semantics. All 21 payload items, all 84 attribute items and all echoed state participate,
    // so a parity comparison of two responses is a comparison of everything they carry.
    // =================================================================================================

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TransactionViewResponse that)) {
            return false;
        }
        return trnnameo.equals(that.trnnameo)
                && title01o.equals(that.title01o)
                && curdateo.equals(that.curdateo)
                && pgmnameo.equals(that.pgmnameo)
                && title02o.equals(that.title02o)
                && curtimeo.equals(that.curtimeo)
                && actidino.equals(that.actidino)
                && cardnino.equals(that.cardnino)
                && ttypcdo.equals(that.ttypcdo)
                && tcatcdo.equals(that.tcatcdo)
                && trnsrco.equals(that.trnsrco)
                && tdesco.equals(that.tdesco)
                && trnamto.equals(that.trnamto)
                && torigdto.equals(that.torigdto)
                && tprocdto.equals(that.tprocdto)
                && mido.equals(that.mido)
                && mnameo.equals(that.mnameo)
                && mcityo.equals(that.mcityo)
                && mzipo.equals(that.mzipo)
                && confirmo.equals(that.confirmo)
                && errmsgo.equals(that.errmsgo)
                && metadata.equals(that.metadata)
                && nextProgram.equals(that.nextProgram)
                && nextMapset.equals(that.nextMapset)
                && nextMap.equals(that.nextMap)
                && navigationContext.equals(that.navigationContext)
                && ct02Info.equals(that.ct02Info);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(trnnameo, title01o, curdateo, pgmnameo, title02o, curtimeo,
                actidino, cardnino, ttypcdo, tcatcdo, trnsrco, tdesco);
        result = 31 * result + Objects.hash(trnamto, torigdto, tprocdto, mido, mnameo, mcityo,
                mzipo, confirmo, errmsgo);
        return 31 * result + Objects.hash(metadata, nextProgram, nextMapset, nextMap,
                navigationContext, ct02Info);
    }

    /**
     * A diagnostic rendering that quotes every payload item so trailing spaces are visible, because a
     * width defect is otherwise invisible in a log.
     *
     * @return a single-line description; not a wire format and not parsed by anything
     */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder(512);
        text.append("TransactionViewResponse[").append(TRANSACTION_ID).append('/')
                .append(PROGRAM_ID).append(' ').append(MAPSET_NAME).append('.').append(MAP_NAME);
        for (ScreenField field : ScreenField.values()) {
            text.append(", ").append(field.outputItemName()).append("='")
                    .append(SensitiveDiagnostics.render(disclosureOf(field), getOutputItem(field)))
                    .append('\'');
        }
        text.append(", nextProgram='").append(nextProgram)
                .append("', nextMapset='").append(nextMapset)
                .append("', nextMap='").append(nextMap)
                .append("', ").append(ct02Info)
                .append(']');
        return text.toString();
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
     * @return its classification, never {@code null}
     */
    private static SensitiveDiagnostics.Disclosure disclosureOf(ScreenField field) {
        return switch (field) {
            case ACTIDIN -> SensitiveDiagnostics.Disclosure.IDENTIFIER;
            case CARDNIN -> SensitiveDiagnostics.Disclosure.PAN;
            default -> SensitiveDiagnostics.Disclosure.PLAIN;
        };
    }

}
