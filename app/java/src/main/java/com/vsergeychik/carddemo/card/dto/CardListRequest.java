package com.vsergeychik.carddemo.card.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ResponseOnlyMembers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Inbound REST payload for {@code GET /api/cards} - the credit card list screen.
 *
 * <p>The {@code xxxL}, {@code xxxF} and {@code xxxA} items are validation and highlight metadata, never
 * JSON payload members: {@code xxxL} is the input length CICS reports and {@code xxxA} is the attribute
 * view that {@code common/FieldAttributeSetter} addresses.
 */
@JsonPropertyOrder({
        "trnname", "title01", "curdate", "pgmname", "title02", "curtime", "pageno", "acctsid",
        "cardsid",
        "crdsel1", "acctno1", "crdnum1", "crdsts1",
        "crdsel2", "crdstp2", "acctno2", "crdnum2", "crdsts2",
        "crdsel3", "crdstp3", "acctno3", "crdnum3", "crdsts3",
        "crdsel4", "crdstp4", "acctno4", "crdnum4", "crdsts4",
        "crdsel5", "crdstp5", "acctno5", "crdnum5", "crdsts5",
        "crdsel6", "crdstp6", "acctno6", "crdnum6", "crdsts6",
        "crdsel7", "crdstp7", "acctno7", "crdnum7", "crdsts7",
        "infomsg", "errmsg",
        "pageCursor", "selectionFlags", "cardScreenState", "navigationContext"})
@JsonIgnoreProperties({
        ResponseOnlyMembers.NEXT_PROGRAM,
        ResponseOnlyMembers.NEXT_MAPSET,
        ResponseOnlyMembers.NEXT_MAP,
        ResponseOnlyMembers.SCREEN_METADATA})
public class CardListRequest {
    /**
     * {@code TRNNAME} - {@code 02 TRNNAMEI PIC X(4).}, {@code COCRDLI.CPY:24}.
     */
    public static final int TRNNAME_LENGTH = 4;

    /**
     * {@code TITLE01} - {@code 02 TITLE01I PIC X(40).}, {@code COCRDLI.CPY:30}.
     */
    public static final int TITLE01_LENGTH = 40;

    /**
     * {@code CURDATE} - {@code 02 CURDATEI PIC X(8).}, {@code COCRDLI.CPY:36}.
     */
    public static final int CURDATE_LENGTH = 8;

    /**
     * {@code PGMNAME} - {@code 02 PGMNAMEI PIC X(8).}, {@code COCRDLI.CPY:42}.
     */
    public static final int PGMNAME_LENGTH = 8;

    /**
     * {@code TITLE02} - {@code 02 TITLE02I PIC X(40).}, {@code COCRDLI.CPY:48}.
     */
    public static final int TITLE02_LENGTH = 40;

    /**
     * {@code CURTIME} - {@code 02 CURTIMEI PIC X(8).}, {@code COCRDLI.CPY:54}.
     */
    public static final int CURTIME_LENGTH = 8;

    /**
     * {@code PAGENO} - {@code 02 PAGENOI PIC X(3).}, {@code COCRDLI.CPY:60}.
     */
    public static final int PAGENO_LENGTH = 3;

    /**
     * {@code ACCTSID} - {@code 02 ACCTSIDI PIC X(11).}, {@code COCRDLI.CPY:66}.
     */
    public static final int ACCTSID_LENGTH = 11;

    /**
     * {@code CARDSID} - {@code 02 CARDSIDI PIC X(16).}, {@code COCRDLI.CPY:72}.
     */
    public static final int CARDSID_LENGTH = 16;

    /**
     * {@code CRDSELn} - {@code 02 CRDSELnI PIC X(1).}; row 1 at {@code COCRDLI.CPY:78}, rows 2-7 at lines
     * 102, 132, 162, 192, 222 and 252.
     */
    public static final int CRDSEL_LENGTH = 1;

    /**
     * {@code CRDSTPn} - {@code 02 CRDSTPnI PIC X(1).}, the row attribute stopper at column 14.
     */
    public static final int CRDSTP_LENGTH = 1;

    /**
     * {@code ACCTNOn} - {@code 02 ACCTNOnI PIC X(11).}; row 1 at {@code COCRDLI.CPY:84}, rows 2-7 at lines
     * 114, 144, 174, 204, 234 and 264.
     */
    public static final int ACCTNO_LENGTH = 11;

    /**
     * {@code CRDNUMn} - {@code 02 CRDNUMnI PIC X(16).}; row 1 at {@code COCRDLI.CPY:90}, rows 2-7 at lines
     * 120, 150, 180, 210, 240 and 270.
     */
    public static final int CRDNUM_LENGTH = 16;

    /**
     * {@code CRDSTSn} - {@code 02 CRDSTSnI PIC X(1).}; row 1 at {@code COCRDLI.CPY:96}, rows 2-7 at lines
     * 126, 156, 186, 216, 246 and 276.
     */
    public static final int CRDSTS_LENGTH = 1;

    /**
     * {@code INFOMSG} - {@code 02 INFOMSGI PIC X(45).}, {@code COCRDLI.CPY:282}.
     */
    public static final int INFOMSG_LENGTH = 45;

    /**
     * {@code ERRMSG} - {@code 02 ERRMSGI PIC X(78).}, {@code COCRDLI.CPY:288}.
     */
    public static final int ERRMSG_LENGTH = 78;

    /**
     * Rows the card list shows per page: exactly seven.
     */
    public static final int PAGE_SIZE = 7;

    /**
     * Detail rows the screen declares, which is one page: {@value #PAGE_SIZE}.
     */
    public static final int SCREEN_ROW_COUNT = PAGE_SIZE;

    /**
     * The lowest valid COBOL subscript for any of the three seven-element tables.
     */
    public static final int FIRST_ROW_NUMBER = 1;

    /**
     * The highest valid COBOL subscript for any of the three seven-element tables.
     */
    public static final int LAST_ROW_NUMBER = SCREEN_ROW_COUNT;

    /**
     * The {@code 02 FILLER PIC X(12).} that opens {@code 01 CCRDLIAI.} at
     * {@code app/cpy-bms/COCRDLI.CPY:18} - the {@code TIOAPFX=YES} terminal input/output area prefix.
     */
    public static final int TIOAPFX_LENGTH = 12;

    /**
     * The {@code 02 xxxL COMP PIC S9(4).} length item: a two-byte binary halfword carrying the input length
     * CICS reports for the field.
     */
    public static final int LENGTH_ITEM_LENGTH = 2;

    /**
     * The {@code 02 xxxF PICTURE X.} flag item: one byte.
     */
    public static final int FLAG_ITEM_LENGTH = 1;

    /**
     * The {@code 03 xxxA PICTURE X.} attribute item.
     */
    public static final int ATTRIBUTE_ITEM_LENGTH = 0;

    /**
     * The {@code 02 FILLER PICTURE X(4).} that precedes each {@code xxxI} item.
     */
    public static final int RESERVED_FILLER_LENGTH = 4;

    public static final int FIELD_OVERHEAD_LENGTH =
            LENGTH_ITEM_LENGTH + FLAG_ITEM_LENGTH + ATTRIBUTE_ITEM_LENGTH + RESERVED_FILLER_LENGTH;

    public static final int HEADER_FIELD_COUNT = 9;

    public static final int FIRST_ROW_FIELD_COUNT = 4;

    public static final int STOPPER_ROW_FIELD_COUNT = 5;

    public static final int FOOTER_FIELD_COUNT = 2;

    /**
     * The total payload member count, {@code 9 + 4 + 6 x 5 + 2 =}45, matching the 45 {@code xxxI} items in
     * {@code app/cpy-bms/COCRDLI.CPY} and the 45 name-labelled {@code DFHMDF} entries of the 72 in
     * {@code app/bms/COCRDLI.bms}.
     */
    public static final int FIELD_COUNT = HEADER_FIELD_COUNT
            + FIRST_ROW_FIELD_COUNT
            + STOPPER_ROW_FIELD_COUNT * (SCREEN_ROW_COUNT - 1)
            + FOOTER_FIELD_COUNT;

    public static final int HEADER_DATA_LENGTH = TRNNAME_LENGTH
            + TITLE01_LENGTH
            + CURDATE_LENGTH
            + PGMNAME_LENGTH
            + TITLE02_LENGTH
            + CURTIME_LENGTH
            + PAGENO_LENGTH
            + ACCTSID_LENGTH
            + CARDSID_LENGTH;

    public static final int FIRST_ROW_DATA_LENGTH =
            CRDSEL_LENGTH + ACCTNO_LENGTH + CRDNUM_LENGTH + CRDSTS_LENGTH;

    public static final int STOPPER_ROW_DATA_LENGTH =
            CRDSEL_LENGTH + CRDSTP_LENGTH + ACCTNO_LENGTH + CRDNUM_LENGTH + CRDSTS_LENGTH;

    public static final int STOPPER_ROWS_DATA_LENGTH =
            STOPPER_ROW_DATA_LENGTH * (SCREEN_ROW_COUNT - 1);

    public static final int FOOTER_DATA_LENGTH = INFOMSG_LENGTH + ERRMSG_LENGTH;

    public static final int PAYLOAD_DATA_LENGTH = HEADER_DATA_LENGTH
            + FIRST_ROW_DATA_LENGTH
            + STOPPER_ROWS_DATA_LENGTH
            + FOOTER_DATA_LENGTH;

    public static final int GROUP_LENGTH =
            TIOAPFX_LENGTH + FIELD_COUNT * FIELD_OVERHEAD_LENGTH + PAYLOAD_DATA_LENGTH;

    /**
     * {@code 15 WS-CA-LAST-CARD-NUM PIC X(16).} / {@code 15 WS-CA-FIRST-CARD-NUM PIC X(16).}.
     */
    public static final int CURSOR_CARD_NUM_LENGTH = 16;

    /**
     * {@code 15 WS-CA-LAST-CARD-ACCT-ID PIC 9(11).} / {@code 15 WS-CA-FIRST-CARD-ACCT-ID PIC 9(11).}.
     */
    public static final int CURSOR_ACCT_ID_LENGTH = 11;

    /**
     * One key group - {@code 10 WS-CA-LAST-CARDKEY.} at {@code COCRDLIC.cbl:230} and
     * {@code 10 WS-CA-FIRST-CARDKEY.} at {@code :233} - is {@code 16 + 11 = 27} bytes.
     */
    public static final int CARD_KEY_LENGTH = CURSOR_CARD_NUM_LENGTH + CURSOR_ACCT_ID_LENGTH;

    /**
     * {@code 10 WS-CA-SCREEN-NUM PIC 9(1).}, {@code COCRDLIC.cbl:237}.
     */
    public static final int SCREEN_NUM_LENGTH = 1;

    /**
     * {@code 10 WS-CA-LAST-PAGE-DISPLAYED PIC 9(1).}, {@code COCRDLIC.cbl:239}.
     */
    public static final int LAST_PAGE_DISPLAYED_LENGTH = 1;

    /**
     * {@code 10 WS-CA-NEXT-PAGE-IND PIC X(1).}, {@code COCRDLIC.cbl:242}.
     */
    public static final int NEXT_PAGE_IND_LENGTH = 1;

    /**
     * {@code 10 WS-RETURN-FLAG PIC X(1).}, {@code COCRDLIC.cbl:246}.
     */
    public static final int RETURN_FLAG_LENGTH = 1;

    /**
     * The paging cursor proper: {@code 27 + 27 + 1 + 1 + 1 + 1 =}58 bytes, spanning
     * {@code app/cbl/COCRDLIC.cbl:229-248}.
     */
    public static final int CURSOR_LENGTH = CARD_KEY_LENGTH
            + CARD_KEY_LENGTH
            + SCREEN_NUM_LENGTH
            + LAST_PAGE_DISPLAYED_LENGTH
            + NEXT_PAGE_IND_LENGTH
            + RETURN_FLAG_LENGTH;

    /**
     * {@code 30 WS-ROW-ACCTNO PIC X(11).}, {@code COCRDLIC.cbl:258}.
     */
    public static final int SCREEN_ROW_ACCTNO_LENGTH = 11;

    /**
     * {@code 30 WS-ROW-CARD-NUM PIC X(16).}, {@code COCRDLIC.cbl:259}.
     */
    public static final int SCREEN_ROW_CARD_NUM_LENGTH = 16;

    /**
     * {@code 30 WS-ROW-CARD-STATUS PIC X(1).}, {@code COCRDLIC.cbl:260}.
     */
    public static final int SCREEN_ROW_CARD_STATUS_LENGTH = 1;

    /**
     * One element of {@code 15 WS-SCREEN-ROWS OCCURS 7 TIMES.}: {@code 11 + 16 + 1 = 28} bytes.
     */
    public static final int SCREEN_ROW_LENGTH = SCREEN_ROW_ACCTNO_LENGTH
            + SCREEN_ROW_CARD_NUM_LENGTH
            + SCREEN_ROW_CARD_STATUS_LENGTH;

    /**
     * {@code 10 WS-ALL-ROWS PIC X(196).}, {@code COCRDLIC.cbl:253}: {@code 28 x 7 =}196 bytes, redefined at
     * {@code :254-260} as the seven-element row table.
     */
    public static final int SCREEN_DATA_LENGTH = SCREEN_ROW_LENGTH * SCREEN_ROW_COUNT;

    /**
     * The full {@code 01 WS-THIS-PROGCOMMAREA} group image: {@code 58 + 196 =}254 bytes.
     */
    public static final int PROG_COMMAREA_LENGTH = CURSOR_LENGTH + SCREEN_DATA_LENGTH;

    /**
     * {@code WS-EDIT-SELECT-FLAGS PIC X(7)} and {@code WS-EDIT-SELECT-ERROR-FLAGS PIC X(7)} -
     * {@code COCRDLIC.cbl:72} and {@code :83}.
     */
    public static final int SELECT_FLAGS_LENGTH = SCREEN_ROW_COUNT;

    /**
     * The one-character rendering of {@code LOW-VALUES}: binary zero.
     */
    public static final char LOW_VALUE = '\u0000';

    /**
     * A single space, the other value {@code 88 SELECT-BLANK} accepts.
     */
    public static final char SPACE = ' ';

    /**
     * {@code 88 VIEW-REQUESTED-ON VALUE 'S'.}, {@code app/cbl/COCRDLIC.cbl:78}.
     */
    public static final char SELECT_VIEW = 'S';

    /**
     * {@code 88 UPDATE-REQUESTED-ON VALUE 'U'.}, {@code app/cbl/COCRDLIC.cbl:79}.
     */
    public static final char SELECT_UPDATE = 'U';

    /**
     * {@code 88 WS-ROW-SELECT-ERROR VALUE '1'.}, {@code app/cbl/COCRDLIC.cbl:88}.
     */
    public static final char ROW_SELECT_ERROR = '1';

    /**
     * {@code 88 CA-FIRST-PAGE VALUE 1.}, {@code app/cbl/COCRDLIC.cbl:238}.
     */
    public static final int FIRST_PAGE_SCREEN_NUM = 1;

    /**
     * {@code 88 CA-LAST-PAGE-SHOWN VALUE 0.}, {@code app/cbl/COCRDLIC.cbl:240}.
     */
    public static final int LAST_PAGE_SHOWN = 0;

    /**
     * {@code 88 CA-LAST-PAGE-NOT-SHOWN VALUE 9.}, {@code app/cbl/COCRDLIC.cbl:241}.
     */
    public static final int LAST_PAGE_NOT_SHOWN = 9;

    /**
     * {@code 88 CA-NEXT-PAGE-EXISTS VALUE 'Y'.}, {@code app/cbl/COCRDLIC.cbl:244}.
     */
    public static final char NEXT_PAGE_EXISTS = 'Y';

    /**
     * {@code 88 WS-RETURN-FLAG-ON VALUE '1'.}, {@code app/cbl/COCRDLIC.cbl:248}.
     */
    public static final char RETURN_FLAG_ON = '1';

    /**
     * The value {@link SelectionFlags#selectedRowNumber()} reports when no row carries a {@code SELECT-OK}
     * action.
     */
    public static final int NO_ROW_SELECTED = 0;

    /**
     * Transaction identifier this screen runs under - {@code LIT-THISTRANID}, {@code COCRDLIC.cbl:181-182}.
     */
    public static final String TRANSACTION_ID = "CCLI";

    /**
     * Program this screen belongs to - {@code LIT-THISPGM}, {@code COCRDLIC.cbl:179-180}.
     */
    public static final String PROGRAM_NAME = "COCRDLIC";

    /**
     * Mapset this screen belongs to - {@code LIT-THISMAPSET}, {@code COCRDLIC.cbl:183-184}.
     */
    public static final String MAPSET_NAME = "COCRDLI";

    /**
     * Map this screen belongs to - {@code LIT-THISMAP}, {@code COCRDLIC.cbl:185-186}.
     */
    public static final String MAP_NAME = "CCRDLIA";

    public sealed interface ListRow permits FirstListRow, StopperListRow {
        String crdSel();

        String acctNo();

        String crdNum();

        String crdSts();

        @JsonIgnore
        int memberCount();

        @JsonIgnore
        int dataLength();

        @JsonIgnore
        boolean hasStopper();

        /**
         * Re-renders every member at its declared width using the codec's {@code PIC X} move, so a value
         * that arrived short or long is padded or truncated exactly as a COBOL alphanumeric {@code MOVE}
         * would do it - on the right, with spaces.
         *
         * @param codec the fixed-width codec whose charset governs the pad byte; never {@code null}
         * @return a row of the same shape with every member exactly its declared width
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        ListRow normalised(FixedWidthCodec codec);

        // They are typed, they are named exactly as COCRDLI.CPY:78-276 names them, and an unknown row key
        // now reaches the mapper's own unknown-property handling instead of a permissive map.
    }

    /**
     * Row 1 of the card list: {@code CRDSEL1}, {@code ACCTNO1}, {@code CRDNUM1}, {@code CRDSTS1}.
     *
     * <p>{@code app/cpy-bms/COCRDLI.CPY:78} declares {@code 02 CRDSEL1I PIC X(1).} and line 79 goes
     * straight on to {@code 02 ACCTNO1L COMP PIC S9(4).}; the mapset's row-1 attribute stopper at
     * {@code POS=(11,14)} is unnamed ({@code app/bms/COCRDLI.bms:145-146}) and so never reaches the
     * symbolic map.
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
         * A row 1 whose every member is space-filled to its declared width - the state the map is in before
         * any card is written into it.
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
     * One of the two 27-byte browse keys of the paging cursor - {@code 10 WS-CA-LAST-CARDKEY.} at
     * {@code app/cbl/COCRDLIC.cbl:230} and {@code 10 WS-CA-FIRST-CARDKEY.} at {@code :233}.
     *
     * <p>Rendering to and from the eleven-byte zoned image goes through the codec, never through
     * {@code Long.parseLong}.
     *
     * @param cardNum {@code WS-CA-*-CARD-NUM}, {@code PIC X(16)}; never {@code null}
     * @param acctId {@code WS-CA-*-CARD-ACCT-ID}, {@code PIC 9(11)}; never negative, and never above eleven
     *     digits
     */
    public record CardKey(@Size(max = CURSOR_CARD_NUM_LENGTH) String cardNum, long acctId) {
        /**
         * The largest value {@code PIC 9(11)} can hold: eleven nines.
         */
        public static final long MAX_ACCT_ID = 99_999_999_999L;

        /**
         * Validates the key at construction: an unsigned zoned field cannot hold a negative value and
         * cannot hold more digits than it declares, so either would silently corrupt the image.
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
         * {@code app/cbl/COCRDLIC.cbl:317} and {@code :338}.
         *
         * @return the initialised key, never {@code null}
         */
        public static CardKey initialised() {
            return new CardKey(spaces(CURSOR_CARD_NUM_LENGTH), 0L);
        }

        /**
         * The key state produced by {@code MOVE LOW-VALUES} into the card number span.
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
         * The card number rendered at its declared sixteen-byte width through the codec's {@code PIC X}
         * move.
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
         * {@code PIC 9} move - the same right-justified, zero-padded placement a COBOL numeric {@code MOVE}
         * performs.
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
         * Rebuilds a key from its two fixed-width spans, decoding the account identifier through the codec
         * rather than through {@code Long.parseLong} so that a zoned image with an overpunched sign or
         * embedded pad bytes is handled by the one component that knows the encoding.
         *
         * @param codec the fixed-width codec; never {@code null}
         * @param cardNumImage the sixteen-byte card number span; never {@code null}
         * @param acctIdImage the eleven-byte zoned account identifier span; never {@code null}
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

        @JsonIgnore
        public int declaredLength() {
            return CARD_KEY_LENGTH;
        }
    }

    /**
     * The 58-byte paging cursor - {@code 01 WS-THIS-PROGCOMMAREA.}, {@code app/cbl/COCRDLIC.cbl:229-248}.
     *
     * @param lastCardKey {@code WS-CA-LAST-CARDKEY}, the high-water browse key of the page just shown;
     *     never {@code null}
     * @param firstCardKey {@code WS-CA-FIRST-CARDKEY}, the low-water browse key of the page just shown;
     *     never {@code null}
     * @param screenNum {@code WS-CA-SCREEN-NUM PIC 9(1)}, the 1-based page number rendered into
     *     {@code PAGENO} by {@code MOVE WS-CA-SCREEN-NUM TO PAGENOO} at {@code COCRDLIC.cbl:667}
     * @param lastPageDisplayed {@code WS-CA-LAST-PAGE-DISPLAYED PIC 9(1)}, 0 when the final page has been
     *     shown and 9 when it has not
     * @param nextPageInd {@code WS-CA-NEXT-PAGE-IND PIC X(1)}, {@code LOW-VALUES} for none or {@code 'Y'};
     *     never {@code null}
     * @param returnFlag {@code WS-RETURN-FLAG PIC X(1)}, {@code LOW-VALUES} for off or {@code '1'} for on;
     *     never {@code null}
     */
    public record PageCursor(@Valid CardKey lastCardKey,
                             @Valid CardKey firstCardKey,
                             int screenNum,
                             int lastPageDisplayed,
                             @Size(max = NEXT_PAGE_IND_LENGTH) String nextPageInd,
                             @Size(max = RETURN_FLAG_LENGTH) String returnFlag) {
        /**
         * The largest value a {@code PIC 9(1)} item can hold.
         */
        public static final int MAX_SINGLE_DIGIT = 9;

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
         * {@code app/cbl/COCRDLIC.cbl:317} and {@code :338}.
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
         * {@code SET CA-FIRST-PAGE TO TRUE} and {@code SET CA-LAST-PAGE-NOT-SHOWN TO TRUE}.
         *
         * @return the first-page cursor, never {@code null}
         */
        public static PageCursor firstPage() {
            return initialised()
                    .withScreenNum(FIRST_PAGE_SCREEN_NUM)
                    .withLastPageDisplayed(LAST_PAGE_NOT_SHOWN);
        }

        /**
         * {@code 88 CA-FIRST-PAGE VALUE 1.} - {@code app/cbl/COCRDLIC.cbl:238}.
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
         * @return {@code true} when the value is exactly 9
         */
        @JsonIgnore
        public boolean isLastPageNotShown() {
            return lastPageDisplayed == LAST_PAGE_NOT_SHOWN;
        }

        /**
         * {@code 88 CA-NEXT-PAGE-NOT-EXISTS VALUE LOW-VALUES.} - {@code app/cbl/COCRDLIC.cbl:243}, set at
         * {@code :1216} and {@code :1235}.
         *
         * @return {@code true} only when the byte is binary zero - never for a space and never for an empty
         *     string
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
         * Reproduces {@code SET CA-NEXT-PAGE-NOT-EXISTS TO TRUE} - {@code app/cbl/COCRDLIC.cbl:1216},
         * {@code :1235} - which writes {@code LOW-VALUES} and not a space.
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

        @JsonIgnore
        public int declaredLength() {
            return CURSOR_LENGTH;
        }

        // This is a parity artefact and it lives here, on the one cursor type, because there is one area:
        // COCRDLIC.cbl:1078-1082 returns it as the trailing part of the communication area and :331-334
        // reads it back on the next invocation, so the bytes the response writes are the bytes the request
        // receives.

        /**
         * {@code 15 WS-CA-LAST-CARD-NUM PIC X(16).}, {@code app/cbl/COCRDLIC.cbl:231}.
         */
        public static final FieldSpan LAST_CARD_NUM_SPAN = FieldSpan.alphanumeric(
                "WS-CA-LAST-CARD-NUM", 0, CURSOR_CARD_NUM_LENGTH);

        /**
         * {@code 15 WS-CA-LAST-CARD-ACCT-ID PIC 9(11).}, {@code app/cbl/COCRDLIC.cbl:232}.
         */
        public static final FieldSpan LAST_CARD_ACCT_ID_SPAN = FieldSpan.unsignedNumeric(
                "WS-CA-LAST-CARD-ACCT-ID", CURSOR_CARD_NUM_LENGTH, CURSOR_ACCT_ID_LENGTH);

        /**
         * {@code 15 WS-CA-FIRST-CARD-NUM PIC X(16).}, {@code app/cbl/COCRDLIC.cbl:234}.
         */
        public static final FieldSpan FIRST_CARD_NUM_SPAN = FieldSpan.alphanumeric(
                "WS-CA-FIRST-CARD-NUM", CARD_KEY_LENGTH, CURSOR_CARD_NUM_LENGTH);

        /**
         * {@code 15 WS-CA-FIRST-CARD-ACCT-ID PIC 9(11).}, {@code app/cbl/COCRDLIC.cbl:235}.
         */
        public static final FieldSpan FIRST_CARD_ACCT_ID_SPAN = FieldSpan.unsignedNumeric(
                "WS-CA-FIRST-CARD-ACCT-ID", CARD_KEY_LENGTH + CURSOR_CARD_NUM_LENGTH,
                CURSOR_ACCT_ID_LENGTH);

        /**
         * {@code 10 WS-CA-SCREEN-NUM PIC 9(1).}, {@code app/cbl/COCRDLIC.cbl:237}.
         */
        public static final FieldSpan SCREEN_NUM_SPAN = FieldSpan.unsignedNumeric(
                "WS-CA-SCREEN-NUM", CARD_KEY_LENGTH + CARD_KEY_LENGTH, SCREEN_NUM_LENGTH);

        /**
         * {@code 10 WS-CA-LAST-PAGE-DISPLAYED PIC 9(1).}, {@code app/cbl/COCRDLIC.cbl:239}.
         */
        public static final FieldSpan LAST_PAGE_DISPLAYED_SPAN = FieldSpan.unsignedNumeric(
                "WS-CA-LAST-PAGE-DISPLAYED", CARD_KEY_LENGTH + CARD_KEY_LENGTH + SCREEN_NUM_LENGTH,
                LAST_PAGE_DISPLAYED_LENGTH);

        /**
         * {@code 10 WS-CA-NEXT-PAGE-IND PIC X(1).}, {@code app/cbl/COCRDLIC.cbl:242}.
         */
        public static final FieldSpan NEXT_PAGE_IND_SPAN = FieldSpan.alphanumeric(
                "WS-CA-NEXT-PAGE-IND",
                CARD_KEY_LENGTH + CARD_KEY_LENGTH + SCREEN_NUM_LENGTH + LAST_PAGE_DISPLAYED_LENGTH,
                NEXT_PAGE_IND_LENGTH);

        /**
         * {@code 10 WS-RETURN-FLAG PIC X(1).}, {@code app/cbl/COCRDLIC.cbl:246}.
         */
        public static final FieldSpan RETURN_FLAG_SPAN = FieldSpan.alphanumeric("WS-RETURN-FLAG",
                CARD_KEY_LENGTH + CARD_KEY_LENGTH + SCREEN_NUM_LENGTH + LAST_PAGE_DISPLAYED_LENGTH
                        + NEXT_PAGE_IND_LENGTH,
                RETURN_FLAG_LENGTH);

        public static final RecordLayout LAYOUT = RecordLayout.of(CURSOR_LENGTH,
                LAST_CARD_NUM_SPAN,
                LAST_CARD_ACCT_ID_SPAN,
                FIRST_CARD_NUM_SPAN,
                FIRST_CARD_ACCT_ID_SPAN,
                SCREEN_NUM_SPAN,
                LAST_PAGE_DISPLAYED_SPAN,
                NEXT_PAGE_IND_SPAN,
                RETURN_FLAG_SPAN);

        /**
         * Renders the area as its {@value CardListRequest#CURSOR_LENGTH}-byte fixed-width image.
         *
         * @param charset the code page to encode into, named explicitly by the caller
         * @return a fresh array of exactly {@value CardListRequest#CURSOR_LENGTH} bytes
         * @throws NullPointerException if {@code charset} is {@code null}
         */
        public byte[] toFixedWidth(Charset charset) {
            Objects.requireNonNull(charset, "A charset is required to render WS-THIS-PROGCOMMAREA as "
                    + "bytes; the code page is never taken from the platform");
            requireKeyFits(lastCardKey, "WS-CA-LAST-CARD-NUM");
            requireKeyFits(firstCardKey, "WS-CA-FIRST-CARD-NUM");
            FixedWidthCodec codec = new FixedWidthCodec(charset);
            FixedWidthRecord record = new FixedWidthRecord(CURSOR_LENGTH, charset);
            codec.writePicX(record, LAST_CARD_NUM_SPAN, lastCardKey.cardNum());
            codec.writePic9(record, LAST_CARD_ACCT_ID_SPAN, lastCardKey.acctId());
            codec.writePicX(record, FIRST_CARD_NUM_SPAN, firstCardKey.cardNum());
            codec.writePic9(record, FIRST_CARD_ACCT_ID_SPAN, firstCardKey.acctId());
            codec.writePic9(record, SCREEN_NUM_SPAN, screenNum);
            codec.writePic9(record, LAST_PAGE_DISPLAYED_SPAN, lastPageDisplayed);
            codec.writePicX(record, NEXT_PAGE_IND_SPAN, nextPageInd);
            codec.writePicX(record, RETURN_FLAG_SPAN, returnFlag);
            return record.toByteArray();
        }

        /**
         * Rebuilds the area from its {@value CardListRequest#CURSOR_LENGTH}-byte image.
         *
         * <p>An image whose numeric spans are {@code LOW-VALUES} - which no image produced by
         * {@link #toFixedWidth(Charset)} ever is, because the zeros are encoded as digits - is rejected
         * rather than read as zero, since tolerating it would hide a truncated or misaligned payload.
         *
         * @param bytes exactly {@value CardListRequest#CURSOR_LENGTH} bytes
         * @param charset the code page the image is encoded in, named explicitly by the caller
         * @return the cursor the image describes; never {@code null}
         * @throws NullPointerException if {@code bytes} or {@code charset} is {@code null}
         * @throws IllegalArgumentException if {@code bytes.length} is not
         *     {@value CardListRequest#CURSOR_LENGTH}, or a numeric span does not hold digits
         */
        public static PageCursor fromFixedWidth(byte[] bytes, Charset charset) {
            Objects.requireNonNull(bytes, "An image is required to rebuild WS-THIS-PROGCOMMAREA");
            Objects.requireNonNull(charset, "A charset is required to decode a "
                    + "WS-THIS-PROGCOMMAREA image; the code page is never taken from the platform");
            FixedWidthCodec codec = new FixedWidthCodec(charset);
            FixedWidthRecord record = codec.wrap(bytes, LAYOUT);
            return new PageCursor(
                    new CardKey(record.readSpan(LAST_CARD_NUM_SPAN),
                            codec.readPic9(record, LAST_CARD_ACCT_ID_SPAN)),
                    new CardKey(record.readSpan(FIRST_CARD_NUM_SPAN),
                            codec.readPic9(record, FIRST_CARD_ACCT_ID_SPAN)),
                    codec.readPic9AsInt(record, SCREEN_NUM_SPAN),
                    codec.readPic9AsInt(record, LAST_PAGE_DISPLAYED_SPAN),
                    record.readSpan(NEXT_PAGE_IND_SPAN),
                    record.readSpan(RETURN_FLAG_SPAN));
        }

        private static void requireKeyFits(CardKey key, String cobolName) {
            if (key.cardNum().length() > CURSOR_CARD_NUM_LENGTH) {
                throw new IllegalArgumentException(cobolName + " is PIC X("
                        + CURSOR_CARD_NUM_LENGTH + ") and cannot hold " + key.cardNum().length()
                        + " characters; the value is refused rather than truncated, because a "
                        + "shortened card number in the paging cursor would browse from the wrong key");
            }
        }
    }

    /**
     * One element of the 196-byte browse result table - {@code 20 WS-EACH-ROW.} / {@code 25 WS-EACH-CARD.}
     * at {@code app/cbl/COCRDLIC.cbl:256-260}.
     *
     * @param acctNo {@code WS-ROW-ACCTNO PIC X(11)}; never {@code null}
     * @param cardNum {@code WS-ROW-CARD-NUM PIC X(16)}; never {@code null}
     * @param cardStatus {@code WS-ROW-CARD-STATUS PIC X(1)}; never {@code null}
     */
    public record ScreenRow(@Size(max = SCREEN_ROW_ACCTNO_LENGTH) String acctNo,
                            @Size(max = SCREEN_ROW_CARD_NUM_LENGTH) String cardNum,
                            @Size(max = SCREEN_ROW_CARD_STATUS_LENGTH) String cardStatus) {
        /**
         * Rejects {@code null} in any member: a cleared row holds {@code LOW-VALUES} bytes, which is a
         * value and not an absence.
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
         * before each browse.
         *
         * @return the cleared row, never {@code null}
         */
        public static ScreenRow lowValues() {
            return new ScreenRow(CardScreenState.lowValues(SCREEN_ROW_ACCTNO_LENGTH),
                    CardScreenState.lowValues(SCREEN_ROW_CARD_NUM_LENGTH),
                    CardScreenState.lowValues(SCREEN_ROW_CARD_STATUS_LENGTH));
        }

        /**
         * Whether every byte of every member is binary zero, that is whether this row is still in the state
         * {@code MOVE LOW-VALUES TO WS-ALL-ROWS} left it in.
         *
         * @return {@code true} when the row has not been written to since it was cleared
         */
        @JsonIgnore
        public boolean isCleared() {
            return isEvery(acctNo, LOW_VALUE, SCREEN_ROW_ACCTNO_LENGTH)
                    && isEvery(cardNum, LOW_VALUE, SCREEN_ROW_CARD_NUM_LENGTH)
                    && isEvery(cardStatus, LOW_VALUE, SCREEN_ROW_CARD_STATUS_LENGTH);
        }

        @JsonIgnore
        public int declaredLength() {
            return SCREEN_ROW_LENGTH;
        }
    }

    /**
     * The seven-element browse result table - {@code 10 WS-ALL-ROWS PIC X(196).} redefined as
     * {@code 15 WS-SCREEN-ROWS OCCURS 7 TIMES.} at {@code app/cbl/COCRDLIC.cbl:253-260}.
     *
     * <p>Byte offsets come from {@link FixedWidthRecord#occursElementOffsetOneBased} and are never computed
     * inline, and {@link #row(int)} takes the COBOL subscript, 1 through 7.
     *
     * @param rows exactly {@value CardListRequest#SCREEN_ROW_COUNT} elements, in screen order; copied
     *     defensively, so the list this record holds can never be reached from outside
     */
    public record ScreenRowTable(List<ScreenRow> rows) {
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
         * Addresses one element by its COBOL subscript.
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
         * @param replacement the row to place there; never {@code null}
         * @return a new table with that one element replaced
         * @throws NullPointerException if {@code replacement} is {@code null}
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
         * rather than open-coded.
         *
         * @param cobolRowNumber the subscript, 1 through {@value CardListRequest#SCREEN_ROW_COUNT}
         * @return the element's byte offset within {@code WS-ALL-ROWS}
         * @throws IndexOutOfBoundsException if {@code cobolRowNumber} is outside 1 to 7
         */
        public static int rowOffset(int cobolRowNumber) {
            return FixedWidthRecord.occursElementOffsetOneBased(0, SCREEN_ROW_LENGTH,
                    SCREEN_ROW_COUNT, cobolRowNumber);
        }

        @JsonIgnore
        public int declaredLength() {
            return SCREEN_DATA_LENGTH;
        }
    }

    /**
     * The seven row action codes - {@code app/cbl/COCRDLIC.cbl:72-82}.
     *
     * <p>{@code SELECT-OK} is the union of {@code 'S'} and {@code 'U'} and is what {@code 2250-EDIT-ARRAY}
     * tests first; {@code SELECT-BLANK} accepts both a space and {@code LOW-VALUES}, which is why an
     * operator who cleared a field and an operator who never touched it are treated alike.
     *
     * @param flags exactly {@value CardListRequest#SELECT_FLAGS_LENGTH} characters, one per row, in row
     *     order
     */
    public record SelectionFlags(@Size(max = SELECT_FLAGS_LENGTH) String flags) {
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

        public static SelectionFlags spacesFilled() {
            return new SelectionFlags(spaces(SELECT_FLAGS_LENGTH));
        }

        /**
         * Builds the table from the seven {@code CRDSELn} payload fields, reproducing
         * {@code MOVE CRDSELnI OF CCRDLIAI TO WS-EDIT-SELECT(n)} at {@code app/cbl/COCRDLIC.cbl:972-978}.
         *
         * @param rows the seven detail rows in screen order; never {@code null}, exactly seven elements
         * @return the populated table
         * @throws NullPointerException if {@code rows} is {@code null} or holds {@code null}
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
         * {@code 88 SELECT-OK VALUES 'S', 'U'.} - {@code app/cbl/COCRDLIC.cbl:77}.
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
         * {@code 88 SELECT-BLANK VALUES ' ', LOW-VALUES.} - {@code app/cbl/COCRDLIC.cbl:80-82}.
         *
         * @param cobolRowNumber the subscript, 1 through 7
         * @return {@code true} when the row carries a space or binary zero
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
         * @param value the replacement action character
         * @return a new table with that one character replaced
         * @throws IndexOutOfBoundsException if {@code cobolRowNumber} is outside 1 to 7
         */
        public SelectionFlags withSelection(int cobolRowNumber, char value) {
            char[] updated = flags.toCharArray();
            updated[javaIndexOf(cobolRowNumber)] = value;
            return new SelectionFlags(new String(updated));
        }

        /**
         * {@code I-SELECTED} as {@code 2250-EDIT-ARRAY} leaves it - {@code app/cbl/COCRDLIC.cbl:1097-1115}.
         *
         * @return the 1-based subscript of the last selected row, or
         *     {@value CardListRequest#NO_ROW_SELECTED} when no row is selected
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
         * {@code 88 DETAIL-WAS-REQUESTED VALUES 1 THRU 7.} evaluated over an arbitrary {@code I-SELECTED}
         * value - {@code app/cbl/COCRDLIC.cbl:92-94}.
         *
         * @param iSelected the {@code I-SELECTED} value to test; any {@code int}, including 0 and values
         *     above 7
         * @return {@code true} when {@code iSelected} lies in the inclusive range 1 to 7
         */
        public static boolean isDetailRequested(int iSelected) {
            return iSelected >= FIRST_ROW_NUMBER && iSelected <= LAST_ROW_NUMBER;
        }

        /**
         * How many rows carry a {@code SELECT-OK} action.
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

        @JsonIgnore
        public int declaredLength() {
            return SELECT_FLAGS_LENGTH;
        }
    }

    /**
     * The seven per-row highlight flags - {@code app/cbl/COCRDLIC.cbl:83-88}.
     *
     * @param flags exactly {@value CardListRequest#SELECT_FLAGS_LENGTH} characters, one per row
     */
    public record SelectionErrorFlags(@Size(max = SELECT_FLAGS_LENGTH) String flags) {
        public SelectionErrorFlags {
            Objects.requireNonNull(flags, "WS-EDIT-SELECT-ERROR-FLAGS is required");
            if (flags.length() != SELECT_FLAGS_LENGTH) {
                throw new IllegalArgumentException("WS-EDIT-SELECT-ERROR-FLAGS is PIC X(7) redefined "
                        + "as OCCURS " + SELECT_FLAGS_LENGTH + " TIMES and cannot hold "
                        + flags.length() + " character(s)");
            }
        }

        public static SelectionErrorFlags none() {
            return new SelectionErrorFlags(spaces(SELECT_FLAGS_LENGTH));
        }

        /**
         * {@code 88 WS-ROW-SELECT-ERROR VALUE '1'.} - {@code app/cbl/COCRDLIC.cbl:88}, tested per row at
         * {@code :755}, {@code :768}, {@code :780}, {@code :792}, {@code :803}, {@code :815} and
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
         * Reproduces {@code MOVE '1' TO WS-ROW-CRDSELECT-ERROR(I)} - {@code app/cbl/COCRDLIC.cbl:1104} and
         * {@code :1110}.
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
         * {@code INSPECT WS-EDIT-SELECT-ERROR-FLAGS REPLACING ALL 'S' BY '1' ALL 'U' BY '1' CHARACTERS BY '0'}
         * - {@code app/cbl/COCRDLIC.cbl:1088-1093}, the branch taken when the operator marked more than one
         * row.
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

        @JsonIgnore
        public int declaredLength() {
            return SELECT_FLAGS_LENGTH;
        }
    }

    /**
     * The {@code xxxL}, {@code xxxF} and {@code xxxA} items of one screen field, projected as metadata.
     *
     * @param dfhmdfName the field's {@code DFHMDF} label, verbatim - {@code TRNNAME}, {@code CRDSEL3},
     *     {@code ERRMSG} and so on
     * @param inputLength the {@code xxxL} value; never negative
     * @param attributeByte the {@code xxxF} byte, viewed through {@code xxxA}
     */
    public record FieldMetadata(String dfhmdfName, int inputLength, char attributeByte) {
        /**
         * Validates the descriptor at construction, so metadata that cannot be traced back to a
         * name-labelled {@code DFHMDF} entry is rejected where it is written.
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
         * @throws NullPointerException if {@code dfhmdfName} is {@code null}
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

    @Size(max = TRNNAME_LENGTH)
    private String trnname;

    @Size(max = TITLE01_LENGTH)
    private String title01;

    @Size(max = CURDATE_LENGTH)
    private String curdate;

    @Size(max = PGMNAME_LENGTH)
    private String pgmname;

    @Size(max = TITLE02_LENGTH)
    private String title02;

    @Size(max = CURTIME_LENGTH)
    private String curtime;

    @Size(max = PAGENO_LENGTH)
    private String pageno;

    @Size(max = ACCTSID_LENGTH)
    private String acctsid;

    @Size(max = CARDSID_LENGTH)
    private String cardsid;

    @Valid
    private List<ListRow> rows;

    @Size(max = INFOMSG_LENGTH)
    private String infomsg;

    @Size(max = ERRMSG_LENGTH)
    private String errmsg;

    @Valid
    private PageCursor pageCursor;

    @Valid
    private SelectionFlags selectionFlags;

    private CardScreenState cardScreenState;

    @Valid
    private NavigationContext navigationContext;

    @JsonIgnore
    private ScreenRowTable screenRowTable;

    @JsonIgnore
    private SelectionErrorFlags selectionErrorFlags;

    @JsonIgnore
    private Map<String, FieldMetadata> fieldMetadata;

    /**
     * A blank request in the state the screen is first painted in: every map field space-filled to its
     * declared width, seven blank rows of the correct shapes, a first-page cursor, a low-value selection
     * table, a fresh {@link CardScreenState} and an empty {@link NavigationContext}.
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
        this.navigationContext = null;
        this.screenRowTable = ScreenRowTable.lowValues();
        this.selectionErrorFlags = SelectionErrorFlags.none();
        this.fieldMetadata = new LinkedHashMap<>();
    }

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

    public String getTrnname() {
        return trnname;
    }

    public void setTrnname(String trnname) {
        this.trnname = requireField(trnname, "TRNNAME");
    }

    public String getTitle01() {
        return title01;
    }

    public void setTitle01(String title01) {
        this.title01 = requireField(title01, "TITLE01");
    }

    public String getCurdate() {
        return curdate;
    }

    public void setCurdate(String curdate) {
        this.curdate = requireField(curdate, "CURDATE");
    }

    public String getPgmname() {
        return pgmname;
    }

    public void setPgmname(String pgmname) {
        this.pgmname = requireField(pgmname, "PGMNAME");
    }

    public String getTitle02() {
        return title02;
    }

    public void setTitle02(String title02) {
        this.title02 = requireField(title02, "TITLE02");
    }

    public String getCurtime() {
        return curtime;
    }

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

    public void setCardsid(String cardsid) {
        this.cardsid = requireField(cardsid, "CARDSID");
    }

    // app/cpy-bms/COCRDLI.CPY:78 declares 02 CRDSEL1I PIC X(1). and line 79 goes straight on to 02 ACCTNO1L
    // COMP PIC S9(4).; the mapset's row-1 stopper at POS=(11,14) is unnamed (app/bms/COCRDLI.bms:145-146)
    // and so never reaches the symbolic map.

    /**
     * {@code CRDSEL1} - {@code 02 CRDSEL1I PIC X(1).} at {@code app/cpy-bms/COCRDLI.CPY:78}.
     *
     * @return {@code CRDSEL1}, at most {@value #CRDSEL_LENGTH} characters; never {@code null}
     */
    public String getCrdsel1() {
        return firstRow().crdSel();
    }

    /**
     * Replaces {@code CRDSEL1}, rebuilding row 1's {@link FirstListRow} around the new value.
     *
     * @param value {@code CRDSEL1}, {@code PIC X(1)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setCrdsel1(String value) {
        FirstListRow row = firstRow();
        setRow(1, new FirstListRow(value, row.acctNo(), row.crdNum(), row.crdSts()));
    }

    /**
     * {@code ACCTNO1} - {@code 02 ACCTNO1I PIC X(11).} at {@code app/cpy-bms/COCRDLI.CPY:84}.
     *
     * @return {@code ACCTNO1}, at most {@value #ACCTNO_LENGTH} characters; never {@code null}
     */
    public String getAcctno1() {
        return firstRow().acctNo();
    }

    /**
     * Replaces {@code ACCTNO1}, rebuilding row 1's {@link FirstListRow} around the new value.
     *
     * @param value {@code ACCTNO1}, {@code PIC X(11)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setAcctno1(String value) {
        FirstListRow row = firstRow();
        setRow(1, new FirstListRow(row.crdSel(), value, row.crdNum(), row.crdSts()));
    }

    /**
     * {@code CRDNUM1} - {@code 02 CRDNUM1I PIC X(16).} at {@code app/cpy-bms/COCRDLI.CPY:90}.
     *
     * @return {@code CRDNUM1}, at most {@value #CRDNUM_LENGTH} characters; never {@code null}
     */
    public String getCrdnum1() {
        return firstRow().crdNum();
    }

    /**
     * Replaces {@code CRDNUM1}, rebuilding row 1's {@link FirstListRow} around the new value.
     *
     * @param value {@code CRDNUM1}, {@code PIC X(16)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setCrdnum1(String value) {
        FirstListRow row = firstRow();
        setRow(1, new FirstListRow(row.crdSel(), row.acctNo(), value, row.crdSts()));
    }

    /**
     * {@code CRDSTS1} - {@code 02 CRDSTS1I PIC X(1).} at {@code app/cpy-bms/COCRDLI.CPY:96}.
     *
     * @return {@code CRDSTS1}, at most {@value #CRDSTS_LENGTH} characters; never {@code null}
     */
    public String getCrdsts1() {
        return firstRow().crdSts();
    }

    /**
     * Replaces {@code CRDSTS1}, rebuilding row 1's {@link FirstListRow} around the new value.
     *
     * @param value {@code CRDSTS1}, {@code PIC X(1)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setCrdsts1(String value) {
        FirstListRow row = firstRow();
        setRow(1, new FirstListRow(row.crdSel(), row.acctNo(), row.crdNum(), value));
    }

    /**
     * {@code CRDSEL2} - {@code 02 CRDSEL2I PIC X(1).} at {@code app/cpy-bms/COCRDLI.CPY:102}.
     *
     * @return {@code CRDSEL2}, at most {@value #CRDSEL_LENGTH} characters; never {@code null}
     */
    public String getCrdsel2() {
        return stopperRow(2).crdSel();
    }

    /**
     * Replaces {@code CRDSEL2}, rebuilding row 2's {@link StopperListRow} around the new value.
     *
     * @param value {@code CRDSEL2}, {@code PIC X(1)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setCrdsel2(String value) {
        StopperListRow row = stopperRow(2);
        setRow(2, new StopperListRow(value, row.crdStp(), row.acctNo(), row.crdNum(), row.crdSts()));
    }

    /**
     * {@code CRDSTP2} - {@code 02 CRDSTP2I PIC X(1).} at {@code app/cpy-bms/COCRDLI.CPY:108}.
     *
     * @return {@code CRDSTP2}, at most {@value #CRDSTP_LENGTH} characters; never {@code null}
     */
    public String getCrdstp2() {
        return stopperRow(2).crdStp();
    }

    /**
     * Replaces {@code CRDSTP2}, rebuilding row 2's {@link StopperListRow} around the new value.
     *
     * @param value {@code CRDSTP2}, {@code PIC X(1)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setCrdstp2(String value) {
        StopperListRow row = stopperRow(2);
        setRow(2, new StopperListRow(row.crdSel(), value, row.acctNo(), row.crdNum(), row.crdSts()));
    }

    /**
     * {@code ACCTNO2} - {@code 02 ACCTNO2I PIC X(11).} at {@code app/cpy-bms/COCRDLI.CPY:114}.
     *
     * @return {@code ACCTNO2}, at most {@value #ACCTNO_LENGTH} characters; never {@code null}
     */
    public String getAcctno2() {
        return stopperRow(2).acctNo();
    }

    /**
     * Replaces {@code ACCTNO2}, rebuilding row 2's {@link StopperListRow} around the new value.
     *
     * @param value {@code ACCTNO2}, {@code PIC X(11)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setAcctno2(String value) {
        StopperListRow row = stopperRow(2);
        setRow(2, new StopperListRow(row.crdSel(), row.crdStp(), value, row.crdNum(), row.crdSts()));
    }

    /**
     * {@code CRDNUM2} - {@code 02 CRDNUM2I PIC X(16).} at {@code app/cpy-bms/COCRDLI.CPY:120}.
     *
     * @return {@code CRDNUM2}, at most {@value #CRDNUM_LENGTH} characters; never {@code null}
     */
    public String getCrdnum2() {
        return stopperRow(2).crdNum();
    }

    /**
     * Replaces {@code CRDNUM2}, rebuilding row 2's {@link StopperListRow} around the new value.
     *
     * @param value {@code CRDNUM2}, {@code PIC X(16)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setCrdnum2(String value) {
        StopperListRow row = stopperRow(2);
        setRow(2, new StopperListRow(row.crdSel(), row.crdStp(), row.acctNo(), value, row.crdSts()));
    }

    /**
     * {@code CRDSTS2} - {@code 02 CRDSTS2I PIC X(1).} at {@code app/cpy-bms/COCRDLI.CPY:126}.
     *
     * @return {@code CRDSTS2}, at most {@value #CRDSTS_LENGTH} characters; never {@code null}
     */
    public String getCrdsts2() {
        return stopperRow(2).crdSts();
    }

    /**
     * Replaces {@code CRDSTS2}, rebuilding row 2's {@link StopperListRow} around the new value.
     *
     * @param value {@code CRDSTS2}, {@code PIC X(1)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setCrdsts2(String value) {
        StopperListRow row = stopperRow(2);
        setRow(2, new StopperListRow(row.crdSel(), row.crdStp(), row.acctNo(), row.crdNum(), value));
    }

    /**
     * {@code CRDSEL3} - {@code 02 CRDSEL3I PIC X(1).} at {@code app/cpy-bms/COCRDLI.CPY:132}.
     *
     * @return {@code CRDSEL3}, at most {@value #CRDSEL_LENGTH} characters; never {@code null}
     */
    public String getCrdsel3() {
        return stopperRow(3).crdSel();
    }

    /**
     * Replaces {@code CRDSEL3}, rebuilding row 3's {@link StopperListRow} around the new value.
     *
     * @param value {@code CRDSEL3}, {@code PIC X(1)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setCrdsel3(String value) {
        StopperListRow row = stopperRow(3);
        setRow(3, new StopperListRow(value, row.crdStp(), row.acctNo(), row.crdNum(), row.crdSts()));
    }

    /**
     * {@code CRDSTP3} - {@code 02 CRDSTP3I PIC X(1).} at {@code app/cpy-bms/COCRDLI.CPY:138}.
     *
     * @return {@code CRDSTP3}, at most {@value #CRDSTP_LENGTH} characters; never {@code null}
     */
    public String getCrdstp3() {
        return stopperRow(3).crdStp();
    }

    /**
     * Replaces {@code CRDSTP3}, rebuilding row 3's {@link StopperListRow} around the new value.
     *
     * @param value {@code CRDSTP3}, {@code PIC X(1)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setCrdstp3(String value) {
        StopperListRow row = stopperRow(3);
        setRow(3, new StopperListRow(row.crdSel(), value, row.acctNo(), row.crdNum(), row.crdSts()));
    }

    /**
     * {@code ACCTNO3} - {@code 02 ACCTNO3I PIC X(11).} at {@code app/cpy-bms/COCRDLI.CPY:144}.
     *
     * @return {@code ACCTNO3}, at most {@value #ACCTNO_LENGTH} characters; never {@code null}
     */
    public String getAcctno3() {
        return stopperRow(3).acctNo();
    }

    /**
     * Replaces {@code ACCTNO3}, rebuilding row 3's {@link StopperListRow} around the new value.
     *
     * @param value {@code ACCTNO3}, {@code PIC X(11)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setAcctno3(String value) {
        StopperListRow row = stopperRow(3);
        setRow(3, new StopperListRow(row.crdSel(), row.crdStp(), value, row.crdNum(), row.crdSts()));
    }

    /**
     * {@code CRDNUM3} - {@code 02 CRDNUM3I PIC X(16).} at {@code app/cpy-bms/COCRDLI.CPY:150}.
     *
     * @return {@code CRDNUM3}, at most {@value #CRDNUM_LENGTH} characters; never {@code null}
     */
    public String getCrdnum3() {
        return stopperRow(3).crdNum();
    }

    /**
     * Replaces {@code CRDNUM3}, rebuilding row 3's {@link StopperListRow} around the new value.
     *
     * @param value {@code CRDNUM3}, {@code PIC X(16)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setCrdnum3(String value) {
        StopperListRow row = stopperRow(3);
        setRow(3, new StopperListRow(row.crdSel(), row.crdStp(), row.acctNo(), value, row.crdSts()));
    }

    /**
     * {@code CRDSTS3} - {@code 02 CRDSTS3I PIC X(1).} at {@code app/cpy-bms/COCRDLI.CPY:156}.
     *
     * @return {@code CRDSTS3}, at most {@value #CRDSTS_LENGTH} characters; never {@code null}
     */
    public String getCrdsts3() {
        return stopperRow(3).crdSts();
    }

    /**
     * Replaces {@code CRDSTS3}, rebuilding row 3's {@link StopperListRow} around the new value.
     *
     * @param value {@code CRDSTS3}, {@code PIC X(1)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setCrdsts3(String value) {
        StopperListRow row = stopperRow(3);
        setRow(3, new StopperListRow(row.crdSel(), row.crdStp(), row.acctNo(), row.crdNum(), value));
    }

    /**
     * {@code CRDSEL4} - {@code 02 CRDSEL4I PIC X(1).} at {@code app/cpy-bms/COCRDLI.CPY:162}.
     *
     * @return {@code CRDSEL4}, at most {@value #CRDSEL_LENGTH} characters; never {@code null}
     */
    public String getCrdsel4() {
        return stopperRow(4).crdSel();
    }

    /**
     * Replaces {@code CRDSEL4}, rebuilding row 4's {@link StopperListRow} around the new value.
     *
     * @param value {@code CRDSEL4}, {@code PIC X(1)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setCrdsel4(String value) {
        StopperListRow row = stopperRow(4);
        setRow(4, new StopperListRow(value, row.crdStp(), row.acctNo(), row.crdNum(), row.crdSts()));
    }

    /**
     * {@code CRDSTP4} - {@code 02 CRDSTP4I PIC X(1).} at {@code app/cpy-bms/COCRDLI.CPY:168}.
     *
     * @return {@code CRDSTP4}, at most {@value #CRDSTP_LENGTH} characters; never {@code null}
     */
    public String getCrdstp4() {
        return stopperRow(4).crdStp();
    }

    /**
     * Replaces {@code CRDSTP4}, rebuilding row 4's {@link StopperListRow} around the new value.
     *
     * @param value {@code CRDSTP4}, {@code PIC X(1)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setCrdstp4(String value) {
        StopperListRow row = stopperRow(4);
        setRow(4, new StopperListRow(row.crdSel(), value, row.acctNo(), row.crdNum(), row.crdSts()));
    }

    /**
     * {@code ACCTNO4} - {@code 02 ACCTNO4I PIC X(11).} at {@code app/cpy-bms/COCRDLI.CPY:174}.
     *
     * @return {@code ACCTNO4}, at most {@value #ACCTNO_LENGTH} characters; never {@code null}
     */
    public String getAcctno4() {
        return stopperRow(4).acctNo();
    }

    /**
     * Replaces {@code ACCTNO4}, rebuilding row 4's {@link StopperListRow} around the new value.
     *
     * @param value {@code ACCTNO4}, {@code PIC X(11)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setAcctno4(String value) {
        StopperListRow row = stopperRow(4);
        setRow(4, new StopperListRow(row.crdSel(), row.crdStp(), value, row.crdNum(), row.crdSts()));
    }

    /**
     * {@code CRDNUM4} - {@code 02 CRDNUM4I PIC X(16).} at {@code app/cpy-bms/COCRDLI.CPY:180}.
     *
     * @return {@code CRDNUM4}, at most {@value #CRDNUM_LENGTH} characters; never {@code null}
     */
    public String getCrdnum4() {
        return stopperRow(4).crdNum();
    }

    /**
     * Replaces {@code CRDNUM4}, rebuilding row 4's {@link StopperListRow} around the new value.
     *
     * @param value {@code CRDNUM4}, {@code PIC X(16)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setCrdnum4(String value) {
        StopperListRow row = stopperRow(4);
        setRow(4, new StopperListRow(row.crdSel(), row.crdStp(), row.acctNo(), value, row.crdSts()));
    }

    /**
     * {@code CRDSTS4} - {@code 02 CRDSTS4I PIC X(1).} at {@code app/cpy-bms/COCRDLI.CPY:186}.
     *
     * @return {@code CRDSTS4}, at most {@value #CRDSTS_LENGTH} characters; never {@code null}
     */
    public String getCrdsts4() {
        return stopperRow(4).crdSts();
    }

    /**
     * Replaces {@code CRDSTS4}, rebuilding row 4's {@link StopperListRow} around the new value.
     *
     * @param value {@code CRDSTS4}, {@code PIC X(1)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setCrdsts4(String value) {
        StopperListRow row = stopperRow(4);
        setRow(4, new StopperListRow(row.crdSel(), row.crdStp(), row.acctNo(), row.crdNum(), value));
    }

    /**
     * {@code CRDSEL5} - {@code 02 CRDSEL5I PIC X(1).} at {@code app/cpy-bms/COCRDLI.CPY:192}.
     *
     * @return {@code CRDSEL5}, at most {@value #CRDSEL_LENGTH} characters; never {@code null}
     */
    public String getCrdsel5() {
        return stopperRow(5).crdSel();
    }

    /**
     * Replaces {@code CRDSEL5}, rebuilding row 5's {@link StopperListRow} around the new value.
     *
     * @param value {@code CRDSEL5}, {@code PIC X(1)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setCrdsel5(String value) {
        StopperListRow row = stopperRow(5);
        setRow(5, new StopperListRow(value, row.crdStp(), row.acctNo(), row.crdNum(), row.crdSts()));
    }

    /**
     * {@code CRDSTP5} - {@code 02 CRDSTP5I PIC X(1).} at {@code app/cpy-bms/COCRDLI.CPY:198}.
     *
     * @return {@code CRDSTP5}, at most {@value #CRDSTP_LENGTH} characters; never {@code null}
     */
    public String getCrdstp5() {
        return stopperRow(5).crdStp();
    }

    /**
     * Replaces {@code CRDSTP5}, rebuilding row 5's {@link StopperListRow} around the new value.
     *
     * @param value {@code CRDSTP5}, {@code PIC X(1)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setCrdstp5(String value) {
        StopperListRow row = stopperRow(5);
        setRow(5, new StopperListRow(row.crdSel(), value, row.acctNo(), row.crdNum(), row.crdSts()));
    }

    /**
     * {@code ACCTNO5} - {@code 02 ACCTNO5I PIC X(11).} at {@code app/cpy-bms/COCRDLI.CPY:204}.
     *
     * @return {@code ACCTNO5}, at most {@value #ACCTNO_LENGTH} characters; never {@code null}
     */
    public String getAcctno5() {
        return stopperRow(5).acctNo();
    }

    /**
     * Replaces {@code ACCTNO5}, rebuilding row 5's {@link StopperListRow} around the new value.
     *
     * @param value {@code ACCTNO5}, {@code PIC X(11)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setAcctno5(String value) {
        StopperListRow row = stopperRow(5);
        setRow(5, new StopperListRow(row.crdSel(), row.crdStp(), value, row.crdNum(), row.crdSts()));
    }

    /**
     * {@code CRDNUM5} - {@code 02 CRDNUM5I PIC X(16).} at {@code app/cpy-bms/COCRDLI.CPY:210}.
     *
     * @return {@code CRDNUM5}, at most {@value #CRDNUM_LENGTH} characters; never {@code null}
     */
    public String getCrdnum5() {
        return stopperRow(5).crdNum();
    }

    /**
     * Replaces {@code CRDNUM5}, rebuilding row 5's {@link StopperListRow} around the new value.
     *
     * @param value {@code CRDNUM5}, {@code PIC X(16)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setCrdnum5(String value) {
        StopperListRow row = stopperRow(5);
        setRow(5, new StopperListRow(row.crdSel(), row.crdStp(), row.acctNo(), value, row.crdSts()));
    }

    /**
     * {@code CRDSTS5} - {@code 02 CRDSTS5I PIC X(1).} at {@code app/cpy-bms/COCRDLI.CPY:216}.
     *
     * @return {@code CRDSTS5}, at most {@value #CRDSTS_LENGTH} characters; never {@code null}
     */
    public String getCrdsts5() {
        return stopperRow(5).crdSts();
    }

    /**
     * Replaces {@code CRDSTS5}, rebuilding row 5's {@link StopperListRow} around the new value.
     *
     * @param value {@code CRDSTS5}, {@code PIC X(1)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setCrdsts5(String value) {
        StopperListRow row = stopperRow(5);
        setRow(5, new StopperListRow(row.crdSel(), row.crdStp(), row.acctNo(), row.crdNum(), value));
    }

    /**
     * {@code CRDSEL6} - {@code 02 CRDSEL6I PIC X(1).} at {@code app/cpy-bms/COCRDLI.CPY:222}.
     *
     * @return {@code CRDSEL6}, at most {@value #CRDSEL_LENGTH} characters; never {@code null}
     */
    public String getCrdsel6() {
        return stopperRow(6).crdSel();
    }

    /**
     * Replaces {@code CRDSEL6}, rebuilding row 6's {@link StopperListRow} around the new value.
     *
     * @param value {@code CRDSEL6}, {@code PIC X(1)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setCrdsel6(String value) {
        StopperListRow row = stopperRow(6);
        setRow(6, new StopperListRow(value, row.crdStp(), row.acctNo(), row.crdNum(), row.crdSts()));
    }

    /**
     * {@code CRDSTP6} - {@code 02 CRDSTP6I PIC X(1).} at {@code app/cpy-bms/COCRDLI.CPY:228}.
     *
     * @return {@code CRDSTP6}, at most {@value #CRDSTP_LENGTH} characters; never {@code null}
     */
    public String getCrdstp6() {
        return stopperRow(6).crdStp();
    }

    /**
     * Replaces {@code CRDSTP6}, rebuilding row 6's {@link StopperListRow} around the new value.
     *
     * @param value {@code CRDSTP6}, {@code PIC X(1)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setCrdstp6(String value) {
        StopperListRow row = stopperRow(6);
        setRow(6, new StopperListRow(row.crdSel(), value, row.acctNo(), row.crdNum(), row.crdSts()));
    }

    /**
     * {@code ACCTNO6} - {@code 02 ACCTNO6I PIC X(11).} at {@code app/cpy-bms/COCRDLI.CPY:234}.
     *
     * @return {@code ACCTNO6}, at most {@value #ACCTNO_LENGTH} characters; never {@code null}
     */
    public String getAcctno6() {
        return stopperRow(6).acctNo();
    }

    /**
     * Replaces {@code ACCTNO6}, rebuilding row 6's {@link StopperListRow} around the new value.
     *
     * @param value {@code ACCTNO6}, {@code PIC X(11)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setAcctno6(String value) {
        StopperListRow row = stopperRow(6);
        setRow(6, new StopperListRow(row.crdSel(), row.crdStp(), value, row.crdNum(), row.crdSts()));
    }

    /**
     * {@code CRDNUM6} - {@code 02 CRDNUM6I PIC X(16).} at {@code app/cpy-bms/COCRDLI.CPY:240}.
     *
     * @return {@code CRDNUM6}, at most {@value #CRDNUM_LENGTH} characters; never {@code null}
     */
    public String getCrdnum6() {
        return stopperRow(6).crdNum();
    }

    /**
     * Replaces {@code CRDNUM6}, rebuilding row 6's {@link StopperListRow} around the new value.
     *
     * @param value {@code CRDNUM6}, {@code PIC X(16)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setCrdnum6(String value) {
        StopperListRow row = stopperRow(6);
        setRow(6, new StopperListRow(row.crdSel(), row.crdStp(), row.acctNo(), value, row.crdSts()));
    }

    /**
     * {@code CRDSTS6} - {@code 02 CRDSTS6I PIC X(1).} at {@code app/cpy-bms/COCRDLI.CPY:246}.
     *
     * @return {@code CRDSTS6}, at most {@value #CRDSTS_LENGTH} characters; never {@code null}
     */
    public String getCrdsts6() {
        return stopperRow(6).crdSts();
    }

    /**
     * Replaces {@code CRDSTS6}, rebuilding row 6's {@link StopperListRow} around the new value.
     *
     * @param value {@code CRDSTS6}, {@code PIC X(1)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setCrdsts6(String value) {
        StopperListRow row = stopperRow(6);
        setRow(6, new StopperListRow(row.crdSel(), row.crdStp(), row.acctNo(), row.crdNum(), value));
    }

    /**
     * {@code CRDSEL7} - {@code 02 CRDSEL7I PIC X(1).} at {@code app/cpy-bms/COCRDLI.CPY:252}.
     *
     * @return {@code CRDSEL7}, at most {@value #CRDSEL_LENGTH} characters; never {@code null}
     */
    public String getCrdsel7() {
        return stopperRow(7).crdSel();
    }

    /**
     * Replaces {@code CRDSEL7}, rebuilding row 7's {@link StopperListRow} around the new value.
     *
     * @param value {@code CRDSEL7}, {@code PIC X(1)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setCrdsel7(String value) {
        StopperListRow row = stopperRow(7);
        setRow(7, new StopperListRow(value, row.crdStp(), row.acctNo(), row.crdNum(), row.crdSts()));
    }

    /**
     * {@code CRDSTP7} - {@code 02 CRDSTP7I PIC X(1).} at {@code app/cpy-bms/COCRDLI.CPY:258}.
     *
     * @return {@code CRDSTP7}, at most {@value #CRDSTP_LENGTH} characters; never {@code null}
     */
    public String getCrdstp7() {
        return stopperRow(7).crdStp();
    }

    /**
     * Replaces {@code CRDSTP7}, rebuilding row 7's {@link StopperListRow} around the new value.
     *
     * @param value {@code CRDSTP7}, {@code PIC X(1)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setCrdstp7(String value) {
        StopperListRow row = stopperRow(7);
        setRow(7, new StopperListRow(row.crdSel(), value, row.acctNo(), row.crdNum(), row.crdSts()));
    }

    /**
     * {@code ACCTNO7} - {@code 02 ACCTNO7I PIC X(11).} at {@code app/cpy-bms/COCRDLI.CPY:264}.
     *
     * @return {@code ACCTNO7}, at most {@value #ACCTNO_LENGTH} characters; never {@code null}
     */
    public String getAcctno7() {
        return stopperRow(7).acctNo();
    }

    /**
     * Replaces {@code ACCTNO7}, rebuilding row 7's {@link StopperListRow} around the new value.
     *
     * @param value {@code ACCTNO7}, {@code PIC X(11)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setAcctno7(String value) {
        StopperListRow row = stopperRow(7);
        setRow(7, new StopperListRow(row.crdSel(), row.crdStp(), value, row.crdNum(), row.crdSts()));
    }

    /**
     * {@code CRDNUM7} - {@code 02 CRDNUM7I PIC X(16).} at {@code app/cpy-bms/COCRDLI.CPY:270}.
     *
     * @return {@code CRDNUM7}, at most {@value #CRDNUM_LENGTH} characters; never {@code null}
     */
    public String getCrdnum7() {
        return stopperRow(7).crdNum();
    }

    /**
     * Replaces {@code CRDNUM7}, rebuilding row 7's {@link StopperListRow} around the new value.
     *
     * @param value {@code CRDNUM7}, {@code PIC X(16)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setCrdnum7(String value) {
        StopperListRow row = stopperRow(7);
        setRow(7, new StopperListRow(row.crdSel(), row.crdStp(), row.acctNo(), value, row.crdSts()));
    }

    /**
     * {@code CRDSTS7} - {@code 02 CRDSTS7I PIC X(1).} at {@code app/cpy-bms/COCRDLI.CPY:276}.
     *
     * @return {@code CRDSTS7}, at most {@value #CRDSTS_LENGTH} characters; never {@code null}
     */
    public String getCrdsts7() {
        return stopperRow(7).crdSts();
    }

    /**
     * Replaces {@code CRDSTS7}, rebuilding row 7's {@link StopperListRow} around the new value.
     *
     * @param value {@code CRDSTS7}, {@code PIC X(1)}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null} - a blank screen field holds spaces or
     *     {@code LOW-VALUES}, never {@code null}
     */
    public void setCrdsts7(String value) {
        StopperListRow row = stopperRow(7);
        setRow(7, new StopperListRow(row.crdSel(), row.crdStp(), row.acctNo(), row.crdNum(), value));
    }

    /**
     * The {@code INFOMSG} payload member - the informational line.
     *
     * @return {@code INFOMSG}, {@code PIC X(45)} - 45 here, 40 on the sibling card maps; never {@code null}
     */
    public String getInfomsg() {
        return infomsg;
    }

    public void setInfomsg(String infomsg) {
        this.infomsg = requireField(infomsg, "INFOMSG");
    }

    public String getErrmsg() {
        return errmsg;
    }

    public void setErrmsg(String errmsg) {
        this.errmsg = requireField(errmsg, "ERRMSG");
    }

    /**
     * The seven detail rows in screen order, as an unmodifiable snapshot.
     *
     * @return an unmodifiable list of exactly {@link #SCREEN_ROW_COUNT} rows; never {@code null}
     */
    @JsonIgnore
    public List<ListRow> getRows() {
        return List.copyOf(rows);
    }

    /**
     * Replaces all seven rows, enforcing the shape invariant.
     *
     * @param rows exactly {@link #SCREEN_ROW_COUNT} rows in screen order; never {@code null}
     * @throws NullPointerException if {@code rows} is {@code null} or holds {@code null}
     * @throws IllegalArgumentException if the list is not exactly seven long, or if any row has the wrong
     *     shape for its position
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
     * One detail row, addressed by its COBOL subscript.
     *
     * @param cobolRowNumber the subscript, 1 through {@link #SCREEN_ROW_COUNT}
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
     * @param cobolRowNumber the subscript, 1 through {@link #SCREEN_ROW_COUNT}
     * @param row the replacement; a {@link FirstListRow} for subscript 1 and a {@link StopperListRow} for
     *     subscripts 2 through 7
     * @throws NullPointerException if {@code row} is {@code null}
     * @throws IllegalArgumentException if {@code row} has the wrong shape for {@code cobolRowNumber}
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

    @JsonIgnore
    public StopperListRow lastRow() {
        return (StopperListRow) rows.get(javaIndexOf(LAST_ROW_NUMBER));
    }

    /**
     * Rows 2 through 7 at their exact type, so the numbered {@code CRDSTPn} accessors can read and rebuild
     * the five-member shape without casting at each of the thirty call sites.
     *
     * @param cobolRowNumber the subscript, 2 through {@link #SCREEN_ROW_COUNT}
     * @return the addressed row at its five-member type; never {@code null}
     * @throws IllegalArgumentException if {@code cobolRowNumber} is {@value #FIRST_ROW_NUMBER}
     * @throws IndexOutOfBoundsException if {@code cobolRowNumber} is outside 1 to 7
     */
    @JsonIgnore
    public StopperListRow stopperRow(int cobolRowNumber) {
        if (cobolRowNumber == FIRST_ROW_NUMBER) {
            throw new IllegalArgumentException("Row " + FIRST_ROW_NUMBER + " is a FirstListRow with 4 "
                    + "members and has no CRDSTP1 - app/cpy-bms/COCRDLI.CPY:78 is followed directly "
                    + "by :79. Use firstRow() for it");
        }
        return (StopperListRow) rows.get(javaIndexOf(cobolRowNumber));
    }

    /**
     * The named attribute stopper of one row, or an empty {@link Optional} for row 1.
     *
     * @param cobolRowNumber the subscript, 1 through {@link #SCREEN_ROW_COUNT}
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
     * The number of payload members the seven rows contribute together: {@code 4 + 6 x 5 = 34}.
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
     * {@code 9 header + 34 rows + 2 footer =}{@link #FIELD_COUNT}, matching the 45 {@code xxxI} items of
     * {@code app/cpy-bms/COCRDLI.CPY} and the 45 name-labelled {@code DFHMDF} entries of
     * {@code app/bms/COCRDLI.bms}.
     *
     * @return 45 for a correctly shaped request
     */
    @JsonIgnore
    public int payloadFieldCount() {
        return HEADER_FIELD_COUNT + rowFieldCount() + FOOTER_FIELD_COUNT;
    }

    /**
     * The data bytes the seven rows contribute to the group image: {@code 29 + 180 = 209}.
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

    /**
     * The paging cursor, carried in the payload rather than in server-side state.
     *
     * @return the 58-byte paging cursor; never {@code null}
     */
    public PageCursor getPageCursor() {
        return pageCursor;
    }

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
     * A defensive copy of the {@code CC-WORK-AREA} carrier, so mutating what you get back cannot reach this
     * request's own state.
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
     * Replaces the {@code CARDDEMO-COMMAREA} carrier, storing {@code null} verbatim.
     *
     * @param navigationContext the {@code CARDDEMO-COMMAREA} carrier, or {@code null} where none travelled
     *     with the request
     */
    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = navigationContext;
    }

    /**
     * Whether a communication area travelled with this request - the Java reading of {@code EIBCALEN} being
     * non-zero at {@code app/cbl/COCRDLIC.cbl:315}.
     *
     * @return {@code true} when {@link #getNavigationContext()} is present
     */
    @JsonIgnore
    public boolean hasNavigationContext() {
        return navigationContext != null;
    }

    /**
     * The length CICS would report in {@code EIBCALEN}: {@value NavigationContext#COMMAREA_LENGTH} when a
     * communication area travelled with this request and {@code 0} when none did.
     *
     * @return {@value NavigationContext#COMMAREA_LENGTH} or {@code 0}
     */
    @JsonIgnore
    public int commareaLength() {
        return hasNavigationContext() ? NavigationContext.COMMAREA_LENGTH : 0;
    }

    /**
     * {@code CDEMO-PGM-CONTEXT PIC 9(01)} as carried by the communication area, or
     * {@value NavigationContext#PGM_CONTEXT_ENTER} where no area travelled.
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
     * {@code 88 CDEMO-PGM-ENTER VALUE 0.} - first entry, so the screen is painted rather than validated.
     *
     * @return {@code true} when a communication area travelled and its {@code CDEMO-PGM-CONTEXT} is
     *     {@value NavigationContext#PGM_CONTEXT_ENTER}
     */
    @JsonIgnore
    public boolean isEnter() {
        return hasNavigationContext() && navigationContext.isEnter();
    }

    /**
     * {@code 88 CDEMO-PGM-REENTER VALUE 1.} - re-entry, so what the operator typed is validated and, on
     * failure, highlighted.
     *
     * @return {@code true} when a communication area travelled and its {@code CDEMO-PGM-CONTEXT} is
     *     {@value NavigationContext#PGM_CONTEXT_REENTER}
     */
    @JsonIgnore
    public boolean isReenter() {
        return hasNavigationContext() && navigationContext.isReenter();
    }

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
     * An unmodifiable snapshot of the per-field {@code xxxL}/{@code xxxF}/{@code xxxA} metadata, keyed by
     * {@code DFHMDF} label and in insertion order.
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
     *     holding {@code null}
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
     *     {@code null}
     * @throws NullPointerException if {@code metadata} is {@code null}
     */
    public void putFieldMetadata(FieldMetadata metadata) {
        Objects.requireNonNull(metadata, "Field metadata is required");
        fieldMetadata.put(metadata.dfhmdfName(), metadata);
    }

    public Optional<FieldMetadata> fieldMetadataOf(String dfhmdfName) {
        Objects.requireNonNull(dfhmdfName, "A DFHMDF label is required to look up field metadata");
        return Optional.ofNullable(fieldMetadata.get(dfhmdfName));
    }

    /**
     * A copy of this request with all forty-five payload members re-rendered at their declared widths.
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

    /**
     * Converts a 1-based COBOL row subscript into the 0-based Java index of the same element.
     *
     * @param cobolRowNumber the COBOL subscript, 1 through {@link #SCREEN_ROW_COUNT} inclusive
     * @return the corresponding 0-based Java index
     * @throws IndexOutOfBoundsException if {@code cobolRowNumber} is outside 1 to {@link #SCREEN_ROW_COUNT}
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
     * Converts a 0-based Java index back into the 1-based COBOL subscript of the same element - the inverse
     * of {@link #javaIndexOf(int)}, provided so that a diagnostic or a test can report the subscript the
     * COBOL would use.
     *
     * @param javaIndex the Java index, 0 through {@code SCREEN_ROW_COUNT - 1} inclusive
     * @return the corresponding 1-based COBOL subscript
     * @throws IndexOutOfBoundsException if {@code javaIndex} is outside 0 to {@code SCREEN_ROW_COUNT - 1}
     */
    public static int cobolRowNumberOf(int javaIndex) {
        if (javaIndex < 0 || javaIndex >= SCREEN_ROW_COUNT) {
            throw new IndexOutOfBoundsException("Java index " + javaIndex + " is outside 0.."
                    + (SCREEN_ROW_COUNT - 1) + "; the card list holds exactly " + SCREEN_ROW_COUNT
                    + " rows");
        }
        return javaIndex + FIRST_ROW_NUMBER;
    }

    private static List<ListRow> blankRows() {
        List<ListRow> blank = new ArrayList<>(SCREEN_ROW_COUNT);
        blank.add(FirstListRow.blank());
        for (int rowNumber = FIRST_ROW_NUMBER + 1; rowNumber <= LAST_ROW_NUMBER; rowNumber++) {
            blank.add(StopperListRow.blank());
        }
        return blank;
    }

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

    private static String requireField(String value, String dfhmdfName) {
        return Objects.requireNonNull(value, dfhmdfName + " is required; a blank screen field holds "
                + "spaces or LOW-VALUES, never null");
    }

    /**
     * A run of spaces of the given length - the state {@code INITIALIZE} and a {@code MOVE SPACES} leave an
     * alphanumeric item in.
     *
     * <p>Deliberately distinct from {@link CardScreenState#lowValues(int)}: spaces are 0x20 under US-ASCII
     * and 0x40 under IBM037, whereas {@code LOW-VALUES} is 0x00 under both, and this file never treats the
     * two as the same thing.
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
     * Whether a span is exactly its declared width and holds nothing but the given character - the test a
     * COBOL {@code 88}-level against a figurative constant performs.
     *
     * @param span the value to test
     * @param expected the character every byte must equal
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

    /**
     * Value equality across every payload member, every carrier and every metadata table.
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
                && Objects.equals(navigationContext, request.navigationContext)
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
     * A diagnostic summary of the request's shape: the transaction, the map, the payload member count, the
     * group width and the row shapes.
     *
     * @return for example
     *     {@code CardListRequest[tranid=CCLI, map=CCRDLIA, payloadFields=45, groupLength=797, rows=4+6x5, page=1]}
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
