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
 * The card screen work area: a field-for-field translation of {@code app/cpy/CVCRD01Y.cpy}, the copybook
 * that carries the conversational state of the three CICS card transactions.
 *
 * <p>The class is deliberately mutable - a COBOL work area is, and the controllers assign
 * {@code CCARD-NEXT-PROG}, {@code CCARD-NEXT-MAPSET} and {@code CCARD-NEXT-MAP} part-way through
 * processing, for example at {@code app/cbl/COCRDLIC.cbl:526-529}.
 */
public final class CardScreenState {
    /**
     * The total width of {@code CC-WORK-AREA} in bytes: {@code 5 + 8 + 7 + 7 + 75 + 75 + 11 + 16 + 9}.
     */
    public static final int RECORD_LENGTH = 213;

    /**
     * Width of {@code CCARD-AID PIC X(5)}, {@code app/cpy/CVCRD01Y.cpy} line 3.
     */
    public static final int CCARD_AID_LENGTH = 5;

    /**
     * Width of {@code CCARD-NEXT-PROG PIC X(8)}, line 21.
     */
    public static final int CCARD_NEXT_PROG_LENGTH = 8;

    /**
     * Width of {@code CCARD-NEXT-MAPSET PIC X(7)}, line 23.
     */
    public static final int CCARD_NEXT_MAPSET_LENGTH = 7;

    /**
     * Width of {@code CCARD-NEXT-MAP PIC X(7)}, line 24.
     */
    public static final int CCARD_NEXT_MAP_LENGTH = 7;

    /**
     * Width of {@code CCARD-ERROR-MSG PIC X(75)}, line 28.
     */
    public static final int CCARD_ERROR_MSG_LENGTH = 75;

    /**
     * Width of {@code CCARD-RETURN-MSG PIC X(75)}, line 29.
     */
    public static final int CCARD_RETURN_MSG_LENGTH = 75;

    /**
     * Width of {@code CC-ACCT-ID PIC X(11)} and of its overlay {@code CC-ACCT-ID-N PIC 9(11)}.
     */
    public static final int CC_ACCT_ID_LENGTH = 11;

    /**
     * Width of {@code CC-CARD-NUM PIC X(16)} and of its overlay {@code CC-CARD-NUM-N PIC 9(16)}.
     */
    public static final int CC_CARD_NUM_LENGTH = 16;

    /**
     * Width of {@code CC-CUST-ID PIC X(09)} and of its overlay {@code CC-CUST-ID-N PIC 9(9)}.
     */
    public static final int CC_CUST_ID_LENGTH = 9;

    /**
     * Absolute 0-based offset of {@code CCARD-AID}; the group starts here.
     */
    public static final int CCARD_AID_OFFSET = 0;

    /**
     * Absolute 0-based offset of {@code CCARD-NEXT-PROG}.
     */
    public static final int CCARD_NEXT_PROG_OFFSET = CCARD_AID_OFFSET + CCARD_AID_LENGTH;

    /**
     * Absolute 0-based offset of {@code CCARD-NEXT-MAPSET}.
     */
    public static final int CCARD_NEXT_MAPSET_OFFSET =
            CCARD_NEXT_PROG_OFFSET + CCARD_NEXT_PROG_LENGTH;

    /**
     * Absolute 0-based offset of {@code CCARD-NEXT-MAP}.
     */
    public static final int CCARD_NEXT_MAP_OFFSET =
            CCARD_NEXT_MAPSET_OFFSET + CCARD_NEXT_MAPSET_LENGTH;

    /**
     * Absolute 0-based offset of {@code CCARD-ERROR-MSG}.
     */
    public static final int CCARD_ERROR_MSG_OFFSET =
            CCARD_NEXT_MAP_OFFSET + CCARD_NEXT_MAP_LENGTH;

    /**
     * Absolute 0-based offset of {@code CCARD-RETURN-MSG}.
     */
    public static final int CCARD_RETURN_MSG_OFFSET =
            CCARD_ERROR_MSG_OFFSET + CCARD_ERROR_MSG_LENGTH;

    /**
     * Absolute 0-based offset of {@code CC-ACCT-ID}, shared with {@code CC-ACCT-ID-N}.
     */
    public static final int CC_ACCT_ID_OFFSET =
            CCARD_RETURN_MSG_OFFSET + CCARD_RETURN_MSG_LENGTH;

    /**
     * Absolute 0-based offset of {@code CC-CARD-NUM}, shared with {@code CC-CARD-NUM-N}.
     */
    public static final int CC_CARD_NUM_OFFSET = CC_ACCT_ID_OFFSET + CC_ACCT_ID_LENGTH;

    /**
     * Absolute 0-based offset of {@code CC-CUST-ID}, shared with {@code CC-CUST-ID-N}.
     */
    public static final int CC_CUST_ID_OFFSET = CC_CARD_NUM_OFFSET + CC_CARD_NUM_LENGTH;

    /**
     * {@code 10 CCARD-AID PIC X(5)}, {@code app/cpy/CVCRD01Y.cpy} line 3.
     */
    public static final FieldSpan CCARD_AID_SPAN =
            FieldSpan.alphanumeric("CCARD-AID", CCARD_AID_OFFSET, CCARD_AID_LENGTH);

    /**
     * {@code 10 CCARD-NEXT-PROG PIC X(8)}, line 21.
     */
    public static final FieldSpan CCARD_NEXT_PROG_SPAN = FieldSpan.alphanumeric(
            "CCARD-NEXT-PROG", CCARD_NEXT_PROG_OFFSET, CCARD_NEXT_PROG_LENGTH);

    /**
     * {@code 10 CCARD-NEXT-MAPSET PIC X(7)}, line 23.
     */
    public static final FieldSpan CCARD_NEXT_MAPSET_SPAN = FieldSpan.alphanumeric(
            "CCARD-NEXT-MAPSET", CCARD_NEXT_MAPSET_OFFSET, CCARD_NEXT_MAPSET_LENGTH);

    /**
     * {@code 10 CCARD-NEXT-MAP PIC X(7)}, line 24.
     */
    public static final FieldSpan CCARD_NEXT_MAP_SPAN = FieldSpan.alphanumeric(
            "CCARD-NEXT-MAP", CCARD_NEXT_MAP_OFFSET, CCARD_NEXT_MAP_LENGTH);

    /**
     * {@code 10 CCARD-ERROR-MSG PIC X(75)}, line 28.
     */
    public static final FieldSpan CCARD_ERROR_MSG_SPAN = FieldSpan.alphanumeric(
            "CCARD-ERROR-MSG", CCARD_ERROR_MSG_OFFSET, CCARD_ERROR_MSG_LENGTH);

    /**
     * {@code 10 CCARD-RETURN-MSG PIC X(75)}, line 29.
     */
    public static final FieldSpan CCARD_RETURN_MSG_SPAN = FieldSpan.alphanumeric(
            "CCARD-RETURN-MSG", CCARD_RETURN_MSG_OFFSET, CCARD_RETURN_MSG_LENGTH);

    /**
     * {@code 10 CC-ACCT-ID PIC X(11) VALUE SPACES}, lines 34 to 35.
     */
    public static final FieldSpan CC_ACCT_ID_SPAN = FieldSpan
            .alphanumeric("CC-ACCT-ID", CC_ACCT_ID_OFFSET, CC_ACCT_ID_LENGTH)
            .withInitialValue(spaces(CC_ACCT_ID_LENGTH));

    /**
     * {@code 10 CC-ACCT-ID-N REDEFINES CC-ACCT-ID PIC 9(11)}, line 36.
     */
    public static final FieldSpan CC_ACCT_ID_N_SPAN =
            CC_ACCT_ID_SPAN.redefinedAs("CC-ACCT-ID-N", PictureKind.UNSIGNED_NUMERIC);

    /**
     * {@code 10 CC-CARD-NUM PIC X(16) VALUE SPACES}, lines 37 to 38.
     */
    public static final FieldSpan CC_CARD_NUM_SPAN = FieldSpan
            .alphanumeric("CC-CARD-NUM", CC_CARD_NUM_OFFSET, CC_CARD_NUM_LENGTH)
            .withInitialValue(spaces(CC_CARD_NUM_LENGTH));

    /**
     * {@code 10 CC-CARD-NUM-N REDEFINES CC-CARD-NUM PIC 9(16)}, line 39.
     */
    public static final FieldSpan CC_CARD_NUM_N_SPAN =
            CC_CARD_NUM_SPAN.redefinedAs("CC-CARD-NUM-N", PictureKind.UNSIGNED_NUMERIC);

    /**
     * {@code 10 CC-CUST-ID PIC X(09) VALUE SPACES}, lines 40 to 41.
     */
    public static final FieldSpan CC_CUST_ID_SPAN = FieldSpan
            .alphanumeric("CC-CUST-ID", CC_CUST_ID_OFFSET, CC_CUST_ID_LENGTH)
            .withInitialValue(spaces(CC_CUST_ID_LENGTH));

    /**
     * {@code 10 CC-CUST-ID-N REDEFINES CC-CUST-ID PIC 9(9)}, line 42.
     */
    public static final FieldSpan CC_CUST_ID_N_SPAN =
            CC_CUST_ID_SPAN.redefinedAs("CC-CUST-ID-N", PictureKind.UNSIGNED_NUMERIC);

    /**
     * The complete layout of {@code CC-WORK-AREA}: twelve spans in copybook declaration order, each overlay
     * immediately after the item it redefines.
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

    private static final FixedWidthCodec PICTURE_RULES =
            new FixedWidthCodec(StandardCharsets.US_ASCII);

    /**
     * What {@link #toString()} prints in place of the three identifier fields, and what the storage guard
     * prints in place of a rejected value.
     */
    public static final String REDACTED = "[REDACTED]";

    /**
     * {@code 88 CCARD-AID-ENTER VALUE 'ENTER'}, line 4.
     */
    public static final String CCARD_AID_ENTER = AidKey.ENTER.token();

    /**
     * {@code 88 CCARD-AID-CLEAR VALUE 'CLEAR'}, line 5.
     */
    public static final String CCARD_AID_CLEAR = AidKey.CLEAR.token();

    /**
     * {@code 88 CCARD-AID-PA1 VALUE 'PA1 '}, line 6 - three characters and two trailing spaces, filling the
     * five-byte field.
     */
    public static final String CCARD_AID_PA1 = AidKey.PA1.token();

    /**
     * {@code 88 CCARD-AID-PA2 VALUE 'PA2 '}, line 7 - again with two trailing spaces.
     */
    public static final String CCARD_AID_PA2 = AidKey.PA2.token();

    /**
     * {@code 88 CCARD-AID-PFK01 VALUE 'PFK01'}, line 8.
     */
    public static final String CCARD_AID_PFK01 = AidKey.PFK01.token();

    /**
     * {@code 88 CCARD-AID-PFK02 VALUE 'PFK02'}, line 9.
     */
    public static final String CCARD_AID_PFK02 = AidKey.PFK02.token();

    /**
     * {@code 88 CCARD-AID-PFK03 VALUE 'PFK03'}, line 10.
     */
    public static final String CCARD_AID_PFK03 = AidKey.PFK03.token();

    /**
     * {@code 88 CCARD-AID-PFK04 VALUE 'PFK04'}, line 11.
     */
    public static final String CCARD_AID_PFK04 = AidKey.PFK04.token();

    /**
     * {@code 88 CCARD-AID-PFK05 VALUE 'PFK05'}, line 12.
     */
    public static final String CCARD_AID_PFK05 = AidKey.PFK05.token();

    /**
     * {@code 88 CCARD-AID-PFK06 VALUE 'PFK06'}, line 13.
     */
    public static final String CCARD_AID_PFK06 = AidKey.PFK06.token();

    /**
     * {@code 88 CCARD-AID-PFK07 VALUE 'PFK07'}, line 14.
     */
    public static final String CCARD_AID_PFK07 = AidKey.PFK07.token();

    /**
     * {@code 88 CCARD-AID-PFK08 VALUE 'PFK08'}, line 15.
     */
    public static final String CCARD_AID_PFK08 = AidKey.PFK08.token();

    /**
     * {@code 88 CCARD-AID-PFK09 VALUE 'PFK09'}, line 16.
     */
    public static final String CCARD_AID_PFK09 = AidKey.PFK09.token();

    /**
     * {@code 88 CCARD-AID-PFK10 VALUE 'PFK10'}, line 17.
     */
    public static final String CCARD_AID_PFK10 = AidKey.PFK10.token();

    /**
     * {@code 88 CCARD-AID-PFK11 VALUE 'PFK11'}, line 18.
     */
    public static final String CCARD_AID_PFK11 = AidKey.PFK11.token();

    /**
     * {@code 88 CCARD-AID-PFK12 VALUE 'PFK12'}, line 19.
     */
    public static final String CCARD_AID_PFK12 = AidKey.PFK12.token();

    // Every field is held at exactly its declared PICTURE width at all times.

    private String ccardAid;

    private String ccardNextProg;

    private String ccardNextMapset;

    private String ccardNextMap;

    private String ccardErrorMsg;

    private String ccardReturnMsg;

    private String ccAcctId;

    private String ccCardNum;

    private String ccCustId;

    /**
     * Creates a work area in its declared initial state: all nine fields space-filled to their declared
     * widths.
     */
    public CardScreenState() {
        initializeWorkArea();
    }

    /**
     * Creates a work area with every field supplied, each stored verbatim through the same guard the
     * setters use: a value wider than its {@code PICTURE} clause is refused, and a shorter one is kept as
     * it is rather than padded.
     *
     * @param ccardAid {@code CCARD-AID}, {@code PIC X(5)}
     * @param ccardNextProg {@code CCARD-NEXT-PROG}, {@code PIC X(8)}
     * @param ccardNextMapset {@code CCARD-NEXT-MAPSET}, {@code PIC X(7)}
     * @param ccardNextMap {@code CCARD-NEXT-MAP}, {@code PIC X(7)}
     * @param ccardErrorMsg {@code CCARD-ERROR-MSG}, {@code PIC X(75)}
     * @param ccardReturnMsg {@code CCARD-RETURN-MSG}, {@code PIC X(75)}
     * @param ccAcctId {@code CC-ACCT-ID}, {@code PIC X(11)}
     * @param ccCardNum {@code CC-CARD-NUM}, {@code PIC X(16)}
     * @param ccCustId {@code CC-CUST-ID}, {@code PIC X(09)}
     * @throws NullPointerException if any argument is {@code null}; COBOL has no absent state, so the
     *     caller must say whether it means spaces or {@code LOW-VALUES}
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
     * This work area with the {@code PIC X} move rule applied to every field: the explicit, named step at
     * which a value that arrived shorter than its screen field becomes the fixed-width item
     * {@code CC-WORK-AREA} actually holds.
     *
     * <p>Every COBOL-semantic predicate and numeric overlay on this class reads the moved image too, so
     * {@code IF CC-ACCT-ID = SPACES} and {@code IF CC-ACCT-ID IS NUMERIC} answer exactly what they answered
     * before, whatever width the value was stored at.
     *
     * @return a new work area with all nine fields at their declared widths; this instance is unchanged
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
     * Reproduces {@code INITIALIZE CC-WORK-AREA}, restoring every field to spaces at its declared width.
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

    // SPACES, LOW-VALUES and Java null are three different things and this class never conflates them:
    // spaces are 0x20 under US-ASCII and 0x40 under IBM037, LOW-VALUES is 0x00 whichever code page is in
    // play, and null is not a COBOL state at all and is rejected wherever a value is expected.

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
     * character {@code U+0000} repeated, which is the byte {@code 0x00} repeated under both code pages this
     * system uses.
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

    /**
     * {@code CCARD-AID} - the AID token, untrimmed and never padded here.
     *
     * @return the token as stored: at most {@link #CCARD_AID_LENGTH} characters, and exactly that many
     *     whenever the value came from a fixed-width image, a figurative constant or {@link #asWorkArea()}
     */
    public String getCcardAid() {
        return ccardAid;
    }

    /**
     * Stores {@code CCARD-AID} without transforming it - up to 5 characters, verbatim.
     *
     * @param ccardAid the token; at most {@link #CCARD_AID_LENGTH} characters, stored verbatim
     * @throws NullPointerException if {@code ccardAid} is {@code null}
     * @throws IllegalArgumentException if it is wider than {@link #CCARD_AID_LENGTH}
     */
    public void setCcardAid(String ccardAid) {
        this.ccardAid = requirePicX(ccardAid, CCARD_AID_LENGTH, "CCARD-AID");
    }

    /**
     * Reproduces {@code SET CCARD-AID-xxx TO TRUE} - for example {@code app/cbl/COCRDLIC.cbl:379},
     * {@code app/cbl/COCRDSLC.cbl:298} and {@code app/cbl/COCRDUPC.cbl:423}, which all set the
     * {@code ENTER} condition - by storing the condition's declared literal.
     *
     * <p>Taking the enum rather than a string makes this the only way to set the field that cannot misspell
     * a token, and it is the form {@code app/cpy/CSSTRPFY.cpy}'s resolver produces.
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
     * @return the matching condition, or {@link Optional#empty()} when the token matches none of the
     *     sixteen
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
     * {@code 88 CCARD-AID-PA1} - matches the literal {@code 'PA1 '}, trailing spaces included.
     *
     * @return {@code true} when {@code CCARD-AID} holds the {@code CCARD-AID-PA1} literal
     */
    @JsonIgnore
    public boolean isCcardAidPa1() {
        return CCARD_AID_PA1.equals(ccardAid);
    }

    /**
     * {@code 88 CCARD-AID-PA2} - matches the literal {@code 'PA2 '}, trailing spaces included.
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
     * {@code 88 CCARD-AID-PFK03} - PF3 and PF15; the exit key, tested at {@code app/cbl/COCRDLIC.cbl:384}
     * and {@code app/cbl/COCRDUPC.cbl:435}.
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

    /**
     * {@code CCARD-NEXT-PROG} - the eight-character name of the program the client should invoke next.
     *
     * @return the name, untrimmed and never padded here: at most {@link #CCARD_NEXT_PROG_LENGTH}
     *     characters, and exactly that many whenever the value came from a fixed-width image
     */
    public String getCcardNextProg() {
        return ccardNextProg;
    }

    /**
     * Stores {@code CCARD-NEXT-PROG} without transforming it - up to 8 characters, verbatim.
     *
     * @param ccardNextProg the program name; at most its declared width, stored verbatim
     * @throws NullPointerException if {@code ccardNextProg} is {@code null}
     * @throws IllegalArgumentException if it is wider than the eight characters the picture declares
     */
    public void setCcardNextProg(String ccardNextProg) {
        this.ccardNextProg = requirePicX(ccardNextProg, CCARD_NEXT_PROG_LENGTH, "CCARD-NEXT-PROG");
    }

    /**
     * {@code CCARD-NEXT-MAPSET} - the seven-character BMS mapset of the next screen.
     *
     * @return the mapset name as stored: at most {@link #CCARD_NEXT_MAPSET_LENGTH} characters, and exactly
     *     that many once {@link #asWorkArea()} or a fixed-width image has supplied it
     */
    public String getCcardNextMapset() {
        return ccardNextMapset;
    }

    /**
     * Stores {@code CCARD-NEXT-MAPSET} without transforming it - up to 7 characters, verbatim - seven,
     * because a BMS map name is seven characters plus the {@code I} or {@code O} suffix of its symbolic
     * group.
     *
     * @param ccardNextMapset the mapset name; at most its declared width, stored verbatim
     * @throws NullPointerException if {@code ccardNextMapset} is {@code null}
     * @throws IllegalArgumentException if it is wider than the seven characters the picture declares
     */
    public void setCcardNextMapset(String ccardNextMapset) {
        this.ccardNextMapset =
                requirePicX(ccardNextMapset, CCARD_NEXT_MAPSET_LENGTH, "CCARD-NEXT-MAPSET");
    }

    /**
     * {@code CCARD-NEXT-MAP} - the seven-character BMS map of the next screen.
     *
     * @return the map name as stored: at most {@link #CCARD_NEXT_MAP_LENGTH} characters, and exactly that
     *     many once {@link #asWorkArea()} or a fixed-width image has supplied it
     */
    public String getCcardNextMap() {
        return ccardNextMap;
    }

    /**
     * Stores {@code CCARD-NEXT-MAP} without transforming it - up to 7 characters, verbatim.
     *
     * @param ccardNextMap the map name; at most its declared width, stored verbatim
     * @throws NullPointerException if {@code ccardNextMap} is {@code null}
     * @throws IllegalArgumentException if it is wider than the seven characters the picture declares
     */
    public void setCcardNextMap(String ccardNextMap) {
        this.ccardNextMap = requirePicX(ccardNextMap, CCARD_NEXT_MAP_LENGTH, "CCARD-NEXT-MAP");
    }

    /**
     * {@code CCARD-ERROR-MSG PIC X(75)} - the error line, written by
     * {@code MOVE WS-ERROR-MSG TO CCARD-ERROR-MSG} at {@code app/cbl/COCRDLIC.cbl:423} and {@code :587},
     * and by {@code MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG} at {@code app/cbl/COCRDSLC.cbl:387},
     * {@code :395}, {@code :587} and {@code app/cbl/COCRDUPC.cbl:547}, {@code :569}.
     *
     * @return the message, untrimmed and never padded here: at most {@link #CCARD_ERROR_MSG_LENGTH}
     *     characters, and exactly that many whenever the value came from a fixed-width image
     */
    public String getCcardErrorMsg() {
        return ccardErrorMsg;
    }

    /**
     * Stores {@code CCARD-ERROR-MSG} without transforming it - up to 75 characters, verbatim.
     *
     * @param ccardErrorMsg the message; at most its declared width, stored verbatim
     * @throws NullPointerException if {@code ccardErrorMsg} is {@code null}
     * @throws IllegalArgumentException if it is wider than the 75 characters the picture declares
     */
    public void setCcardErrorMsg(String ccardErrorMsg) {
        this.ccardErrorMsg = requirePicX(ccardErrorMsg, CCARD_ERROR_MSG_LENGTH, "CCARD-ERROR-MSG");
    }

    /**
     * {@code CCARD-RETURN-MSG PIC X(75)} - the return line.
     *
     * @return the message, untrimmed and never padded here: at most {@link #CCARD_RETURN_MSG_LENGTH}
     *     characters, and exactly that many whenever the value came from a fixed-width image
     */
    public String getCcardReturnMsg() {
        return ccardReturnMsg;
    }

    /**
     * Stores {@code CCARD-RETURN-MSG} without transforming it - up to 75 characters, verbatim.
     *
     * @param ccardReturnMsg the message; at most its declared width, stored verbatim
     * @throws NullPointerException if {@code ccardReturnMsg} is {@code null}
     * @throws IllegalArgumentException if it is wider than the 75 characters the picture declares
     */
    public void setCcardReturnMsg(String ccardReturnMsg) {
        this.ccardReturnMsg = requirePicX(ccardReturnMsg, CCARD_RETURN_MSG_LENGTH, "CCARD-RETURN-MSG");
    }

    /**
     * Reproduces {@code MOVE LOW-VALUES TO CCARD-RETURN-MSG}, putting the field into the exact state that
     * makes {@link #isCcardReturnMsgOff()} true: 75 bytes of {@code 0x00}.
     */
    @JsonIgnore
    public void setCcardReturnMsgToLowValues() {
        this.ccardReturnMsg = lowValues(CCARD_RETURN_MSG_LENGTH);
    }

    /**
     * {@code 88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES}, {@code app/cpy/CVCRD01Y.cpy} line 30 - the only
     * condition name the copybook declares on a field other than {@code CCARD-AID}.
     *
     * @return {@code true} only when every one of the 75 characters is {@code U+0000}
     */
    @JsonIgnore
    public boolean isCcardReturnMsgOff() {
        return isEvery(moved(ccardReturnMsg, CCARD_RETURN_MSG_LENGTH), '\u0000');
    }

    // Garbage never reaches the strict path by accident, because the COBOL guards it first. Every element
    // of that chain is exposed below as its own predicate, so a controller reproduces the source's order
    // exactly and reaches the numeric view only where the COBOL does.

    /**
     * {@code CC-ACCT-ID PIC X(11)} - the account identifier as characters.
     *
     * @return the identifier, untrimmed and never padded here: at most {@link #CC_ACCT_ID_LENGTH}
     *     characters, and exactly that many whenever the value came from a fixed-width image
     */
    public String getCcAcctId() {
        return ccAcctId;
    }

    /**
     * Stores {@code CC-ACCT-ID} without transforming it - up to 11 characters, verbatim, which is also
     * visible through {@link #getCcAcctIdN()}.
     *
     * @param ccAcctId the identifier; at most its declared width, stored verbatim
     * @throws NullPointerException if {@code ccAcctId} is {@code null}
     * @throws IllegalArgumentException if it is wider than the eleven characters the picture declares
     */
    public void setCcAcctId(String ccAcctId) {
        this.ccAcctId = requirePicX(ccAcctId, CC_ACCT_ID_LENGTH, "CC-ACCT-ID");
    }

    /**
     * {@code CC-ACCT-ID-N PIC 9(11)} - the same eleven bytes seen as an unsigned integer, the view
     * {@code MOVE CC-ACCT-ID-N TO CARD-UPDATE-ACCT-ID} uses at {@code app/cbl/COCRDUPC.cbl:1463}.
     *
     * @return {@code 0} when the span holds {@code LOW-VALUES}, spaces or zeros, otherwise the value its
     *     digits denote
     * @throws IllegalArgumentException if the span holds neither digits nor one of those three states - the
     *     codec's deliberate policy, which the {@link #isCcAcctIdNumeric()} guard exists to keep unreached
     */
    @JsonIgnore
    public long getCcAcctIdN() {
        return numericView(moved(ccAcctId, CC_ACCT_ID_LENGTH));
    }

    /**
     * Stores {@code CC-ACCT-ID-N} through the {@code PIC 9(11)} move rule - zero-filled on the left and
     * truncated on the left, so the low-order digits survive - which is also visible through
     * {@link #getCcAcctId()}.
     *
     * @param ccAcctIdN the identifier; must not be negative, because {@code PIC 9} is unsigned and has no
     *     sign position to store one in
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
     * @return {@code true} when every character occupies a zero digit position - that is when the span is
     *     {@code LOW-VALUES}, all spaces or all zeros
     */
    @JsonIgnore
    public boolean isCcAcctIdNZeros() {
        return isZeroValued(moved(ccAcctId, CC_ACCT_ID_LENGTH));
    }

    /**
     * {@code IF CC-ACCT-ID IS NUMERIC} - the COBOL class test that guards every numeric use, applied at
     * {@code app/cbl/COCRDLIC.cbl:1017}, {@code app/cbl/COCRDSLC.cbl:665} and
     * {@code app/cbl/COCRDUPC.cbl:740} (each written as {@code IS NOT NUMERIC}).
     *
     * @return {@code true} only when every one of the eleven characters is a digit
     */
    @JsonIgnore
    public boolean isCcAcctIdNumeric() {
        return isEveryDigit(moved(ccAcctId, CC_ACCT_ID_LENGTH));
    }

    public String getCcCardNum() {
        return ccCardNum;
    }

    /**
     * Stores {@code CC-CARD-NUM} without transforming it - up to 16 characters, verbatim, which is also
     * visible through {@link #getCcCardNumN()}.
     *
     * @param ccCardNum the card number; at most its declared width, stored verbatim
     * @throws NullPointerException if {@code ccCardNum} is {@code null}
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
     * @return {@code 0} when the span holds {@code LOW-VALUES}, spaces or zeros, otherwise the value its
     *     digits denote
     * @throws IllegalArgumentException if the span holds neither digits nor one of those three states
     */
    @JsonIgnore
    public long getCcCardNumN() {
        return numericView(moved(ccCardNum, CC_CARD_NUM_LENGTH));
    }

    /**
     * Stores {@code CC-CARD-NUM-N} through the {@code PIC 9(16)} move rule, which is also visible through
     * {@link #getCcCardNum()}.
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
     * @return the identifier, untrimmed and never padded here: at most {@link #CC_CUST_ID_LENGTH}
     *     characters, and exactly that many whenever the value came from a fixed-width image
     */
    public String getCcCustId() {
        return ccCustId;
    }

    /**
     * Stores {@code CC-CUST-ID} without transforming it - up to 09 characters, verbatim, which is also
     * visible through {@link #getCcCustIdN()}.
     *
     * @param ccCustId the identifier; at most its declared width, stored verbatim
     * @throws NullPointerException if {@code ccCustId} is {@code null}
     * @throws IllegalArgumentException if it is wider than the nine characters the picture declares
     */
    public void setCcCustId(String ccCustId) {
        this.ccCustId = requirePicX(ccCustId, CC_CUST_ID_LENGTH, "CC-CUST-ID");
    }

    /**
     * {@code CC-CUST-ID-N PIC 9(9)} - the same nine bytes seen as an unsigned integer.
     *
     * @return {@code 0} when the span holds {@code LOW-VALUES}, spaces or zeros, otherwise the value its
     *     digits denote
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

    /**
     * Renders this work area as its {@link #RECORD_LENGTH}-byte fixed-width image.
     *
     * @param charset the code page to encode into, named explicitly by the caller
     * @return a fresh array of exactly {@link #RECORD_LENGTH} bytes
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} does not encode every digit, sign overpunch
     *     character and the space to exactly one byte, since a fixed-width span is addressed by absolute byte
     *     offset
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
     * @param record a record area of exactly {@link #RECORD_LENGTH} bytes
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalArgumentException if the record's declared length is not {@link #RECORD_LENGTH}
     */
    public void writeInto(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A record area is required to write CC-WORK-AREA into");
        writeInto(record, new FixedWidthCodec(record.charset()));
    }

    /**
     * Rebuilds a work area from its fixed-width image.
     *
     * @param bytes the image, exactly {@link #RECORD_LENGTH} bytes
     * @param charset the code page the image is encoded in, named explicitly by the caller
     * @return a work area holding the nine fields the image carries
     * @throws NullPointerException if {@code bytes} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code bytes.length} is not {@link #RECORD_LENGTH}, or if
     *     {@code charset} is not single-byte for the digits and the space
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
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalArgumentException if the record's declared length is not {@link #RECORD_LENGTH}
     */
    public static CardScreenState readFrom(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A record area is required to read CC-WORK-AREA from");
        return readFrom(record, new FixedWidthCodec(record.charset()));
    }

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

    /**
     * Two work areas are equal when all nine fields are equal as {@code CC-WORK-AREA} holds them - that is,
     * after the {@code PIC X} move rule has been applied to each.
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
     * message fields - render verbatim, because they are what a navigation or validation parity failure is
     * diagnosed from.
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
     * Applies the {@code PIC X} move rule to a stored field, producing the image {@code CC-WORK-AREA}
     * actually holds: the value padded on the right to its declared width, or truncated there if it somehow
     * exceeded it.
     *
     * @param value the stored value
     * @param length the field's declared width
     * @return the value as exactly {@code length} characters
     */
    private static String moved(String value, int length) {
        return PICTURE_RULES.movePicX(value, length);
    }

    private static long numericView(String image) {
        if (isZeroValued(image)) {
            return 0L;
        }
        return PICTURE_RULES.decodePic9(image);
    }

    private static boolean isZeroValued(String image) {
        for (int index = 0; index < image.length(); index++) {
            char character = image.charAt(index);
            if (character != '\u0000' && character != ' ' && character != '0') {
                return false;
            }
        }
        return true;
    }

    private static boolean isEvery(String image, char expected) {
        for (int index = 0; index < image.length(); index++) {
            if (image.charAt(index) != expected) {
                return false;
            }
        }
        return true;
    }

    private static boolean isEveryDigit(String image) {
        for (int index = 0; index < image.length(); index++) {
            char character = image.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

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
