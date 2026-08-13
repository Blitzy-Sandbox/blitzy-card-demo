package com.vsergeychik.carddemo.card.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
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
import com.vsergeychik.carddemo.common.ScreenMetadata;
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
 * The outbound payload of {@code GET /api/cards} - the Java projection of the {@code CCRDLIAO} output
 * symbolic map that CICS transaction {@code CCLI} sends.
 *
 * <p>{@code CardSelectController} deliberately preserves a source defect in which {@code LIT-CCLISTMAP} is
 * {@code 'CCRDSLA'} at {@code app/cbl/COCRDSLC.cbl:178} although this map is really {@code 'CCRDLIA'};
 * "correcting" a token here would break that preservation.
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
        "nextProgram", "nextMapset", "nextMap", "pageCursor", "cardScreenState",
        "navigationContext"})
public final class CardListResponse {
    /**
     * {@code TRNNAMEO PIC X(4)} [{@code COCRDLI.CPY:296}] - the transaction identifier.
     */
    public static final int TRNNAMEO_LENGTH = 4;

    /**
     * {@code TITLE01O PIC X(40)} [{@code COCRDLI.CPY:302}] - the upper title line.
     */
    public static final int TITLE01O_LENGTH = 40;

    /**
     * {@code CURDATEO PIC X(8)} [{@code COCRDLI.CPY:308}] - the {@code mm/dd/yy} date.
     */
    public static final int CURDATEO_LENGTH = 8;

    /**
     * {@code PGMNAMEO PIC X(8)} [{@code COCRDLI.CPY:314}] - the program name.
     */
    public static final int PGMNAMEO_LENGTH = 8;

    /**
     * {@code TITLE02O PIC X(40)} [{@code COCRDLI.CPY:320}] - the lower title line.
     */
    public static final int TITLE02O_LENGTH = 40;

    /**
     * {@code CURTIMEO PIC X(8)} [{@code COCRDLI.CPY:326}] - the {@code hh:mm:ss} time.
     */
    public static final int CURTIMEO_LENGTH = 8;

    /**
     * {@code PAGENOO PIC X(3)} [{@code COCRDLI.CPY:332}] - the page number.
     */
    public static final int PAGENOO_LENGTH = 3;

    /**
     * {@code ACCTSIDO PIC X(11)} [{@code COCRDLI.CPY:338}] - the account-number filter.
     */
    public static final int ACCTSIDO_LENGTH = 11;

    /**
     * {@code CARDSIDO PIC X(16)} [{@code COCRDLI.CPY:344}] - the card-number filter.
     */
    public static final int CARDSIDO_LENGTH = 16;

    /**
     * {@code CRDSELnO PIC X(1)} - the per-row selection character, one per row for rows 1 to 7
     * [{@code COCRDLI.CPY:350, 374, 404, 434, 464, 494, 524}].
     */
    public static final int CRDSEL_LENGTH = 1;

    /**
     * {@code CRDSTPnO PIC X(1)} - the per-row hidden selection-type character, present for rows 2 to 7 only
     * [{@code COCRDLI.CPY:380, 410, 440, 470, 500, 530}].
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
     * {@code INFOMSG DFHMDF ... LENGTH=45, POS=(20,19)} [{@code app/bms/COCRDLI.bms:324-328}]. 45, not 40.
     */
    public static final int INFOMSGO_LENGTH = 45;

    /**
     * {@code ERRMSGO PIC X(78)} [{@code COCRDLI.CPY:560}], confirmed by
     * {@code ERRMSG DFHMDF ... LENGTH=78, POS=(23,1)} [{@code app/bms/COCRDLI.bms:331-334}]. 78, not 80.
     */
    public static final int ERRMSGO_LENGTH = 78;

    /**
     * The {@code TIOAPFX=YES} prefix: {@code 02 FILLER PIC X(12)}, the first item of both groups
     * [{@code COCRDLI.CPY:290} for the output group, L18 for the input group].
     */
    public static final int TIOAPFX_LENGTH = 12;

    /**
     * The reserved span ahead of each field's {@code xxxC} item: {@code 02 FILLER PICTURE X(3)}.
     */
    public static final int RESERVED_SPAN_LENGTH = 3;

    public static final int ATTRIBUTE_ITEM_LENGTH = 1;

    public static final int ATTRIBUTE_ITEM_COUNT = 4;

    public static final int FIELD_PREFIX_LENGTH =
            RESERVED_SPAN_LENGTH + (ATTRIBUTE_ITEM_COUNT * ATTRIBUTE_ITEM_LENGTH);

    public static final int HEADER_FIELD_COUNT = 9;

    public static final int ROW_1_FIELD_COUNT = 4;

    public static final int ROW_N_FIELD_COUNT = 5;

    public static final int FOOTER_FIELD_COUNT = 2;

    /**
     * Rows the map displays, and therefore the {@code OCCURS} count of every seven-element structure here:
     * {@code WS-SCREEN-ROWS OCCURS 7 TIMES} [{@code app/cbl/COCRDLIC.cbl:255}],
     * {@code WS-EDIT-SELECT ... OCCURS 7 TIMES} [L75-76] and {@code WS-EDIT-SELECT-ERRORS OCCURS 7 TIMES}
     * [L86].
     */
    public static final int ROW_COUNT = 7;

    /**
     * Cards shown per page: 7, from {@code 05 WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7}
     * [{@code app/cbl/COCRDLIC.cbl:177-178}].
     */
    public static final int PAGE_SIZE = 7;

    /**
     * The number of name-labelled {@code DFHMDF} entries in {@code app/bms/COCRDLI.bms}, and hence the
     * number of payload members here: {@value #HEADER_FIELD_COUNT} + {@value #ROW_1_FIELD_COUNT} + 6 x
     * {@value #ROW_N_FIELD_COUNT} + {@value #FOOTER_FIELD_COUNT} = 45, out of 72 {@code DFHMDF} entries in
     * total.
     */
    public static final int PAYLOAD_FIELD_COUNT = HEADER_FIELD_COUNT
            + ROW_1_FIELD_COUNT
            + ((ROW_COUNT - 1) * ROW_N_FIELD_COUNT)
            + FOOTER_FIELD_COUNT;

    public static final int PAYLOAD_LENGTH =
            (TRNNAMEO_LENGTH + TITLE01O_LENGTH + CURDATEO_LENGTH + PGMNAMEO_LENGTH
                    + TITLE02O_LENGTH + CURTIMEO_LENGTH + PAGENOO_LENGTH + ACCTSIDO_LENGTH
                    + CARDSIDO_LENGTH)
            + (CRDSEL_LENGTH + ACCTNO_LENGTH + CRDNUM_LENGTH + CRDSTS_LENGTH)
            + ((ROW_COUNT - 1)
                    * (CRDSEL_LENGTH + CRDSTP_LENGTH + ACCTNO_LENGTH + CRDNUM_LENGTH + CRDSTS_LENGTH))
            + (INFOMSGO_LENGTH + ERRMSGO_LENGTH);

    public static final int GROUP_LENGTH =
            TIOAPFX_LENGTH + (PAYLOAD_FIELD_COUNT * FIELD_PREFIX_LENGTH) + PAYLOAD_LENGTH;

    /**
     * One element of {@code WS-SCREEN-ROWS}: {@code WS-ROW-ACCTNO PIC X(11)} +
     * {@code WS-ROW-CARD-NUM PIC X(16)} + {@code WS-ROW-CARD-STATUS PIC X(1)} = 28 bytes
     * [{@code app/cbl/COCRDLIC.cbl:258-260}].
     */
    public static final int SCREEN_ROW_LENGTH = ACCTNO_LENGTH + CRDNUM_LENGTH + CRDSTS_LENGTH;

    /**
     * {@code WS-ALL-ROWS PIC X(196)} [{@code app/cbl/COCRDLIC.cbl:253}]: {@link #SCREEN_ROW_LENGTH} x
     * {@value #ROW_COUNT} = 196.
     */
    public static final int SCREEN_ARRAY_LENGTH = SCREEN_ROW_LENGTH * ROW_COUNT;

    /**
     * {@code WS-EDIT-SELECT-FLAGS PIC X(7)} [{@code app/cbl/COCRDLIC.cbl:72}] and
     * {@code WS-EDIT-SELECT-ERROR-FLAGS PIC X(7)} [L83]: one character per row.
     */
    public static final int SELECT_FLAGS_LENGTH = ROW_COUNT;

    /**
     * {@code CCARD-NEXT-PROG PIC X(8)} [{@code app/cpy/CVCRD01Y.cpy:21}].
     */
    public static final int NEXT_PROGRAM_LENGTH = CardScreenState.CCARD_NEXT_PROG_LENGTH;

    /**
     * {@code CCARD-NEXT-MAPSET PIC X(7)} [{@code app/cpy/CVCRD01Y.cpy:23}].
     */
    public static final int NEXT_MAPSET_LENGTH = CardScreenState.CCARD_NEXT_MAPSET_LENGTH;

    /**
     * {@code CCARD-NEXT-MAP PIC X(7)} [{@code app/cpy/CVCRD01Y.cpy:24}].
     */
    public static final int NEXT_MAP_LENGTH = CardScreenState.CCARD_NEXT_MAP_LENGTH;

    /**
     * {@code LIT-THISTRANID PIC X(4) VALUE 'CCLI'} [{@code app/cbl/COCRDLIC.cbl:181-182}].
     */
    public static final String LIT_THISTRANID = "CCLI";

    /**
     * {@code LIT-THISPGM PIC X(8) VALUE 'COCRDLIC'} [{@code app/cbl/COCRDLIC.cbl:179-180}].
     */
    public static final String LIT_THISPGM = "COCRDLIC";

    /**
     * {@code LIT-THISMAPSET PIC X(7) VALUE 'COCRDLI'} [{@code app/cbl/COCRDLIC.cbl:183-184}].
     */
    public static final String LIT_THISMAPSET = "COCRDLI";

    /**
     * {@code LIT-THISMAP PIC X(7) VALUE 'CCRDLIA'} [{@code app/cbl/COCRDLIC.cbl:185-186}].
     */
    public static final String LIT_THISMAP = "CCRDLIA";

    /**
     * {@code LIT-MENUPGM PIC X(8) VALUE 'COMEN01C'} [{@code app/cbl/COCRDLIC.cbl:187-188}] - the literal
     * target of the {@code XCTL} at L402.
     */
    public static final String LIT_MENUPGM = "COMEN01C";

    /**
     * {@code 88 VIEW-REQUESTED-ON VALUE 'S'} [{@code app/cbl/COCRDLIC.cbl:78}].
     */
    public static final String SELECT_VIEW = "S";

    /**
     * {@code 88 UPDATE-REQUESTED-ON VALUE 'U'} [{@code app/cbl/COCRDLIC.cbl:79}].
     */
    public static final String SELECT_UPDATE = "U";

    /**
     * {@code 88 WS-ROW-SELECT-ERROR VALUE '1'} [{@code app/cbl/COCRDLIC.cbl:88}].
     */
    public static final String ROW_SELECT_ERROR = "1";

    /**
     * {@code 88 CA-NEXT-PAGE-EXISTS VALUE 'Y'} [{@code app/cbl/COCRDLIC.cbl:244}].
     */
    public static final String NEXT_PAGE_EXISTS = "Y";

    /**
     * {@code 88 WS-RETURN-FLAG-ON VALUE '1'} [{@code app/cbl/COCRDLIC.cbl:248}].
     */
    public static final String RETURN_FLAG_ON = "1";

    /**
     * {@code 88 CA-FIRST-PAGE VALUE 1} [{@code app/cbl/COCRDLIC.cbl:238}].
     */
    public static final int FIRST_PAGE = 1;

    /**
     * {@code 88 CA-LAST-PAGE-SHOWN VALUE 0} [{@code app/cbl/COCRDLIC.cbl:240}].
     */
    public static final int LAST_PAGE_SHOWN = 0;

    /**
     * {@code 88 CA-LAST-PAGE-NOT-SHOWN VALUE 9} [{@code app/cbl/COCRDLIC.cbl:241}].
     */
    public static final int LAST_PAGE_NOT_SHOWN = 9;

    /**
     * The single {@code LOW-VALUES} character, {@code U+0000}, which encodes to {@code 0x00} under both
     * {@code US-ASCII} and {@code IBM037}.
     */
    public static final String LOW_VALUE = "\u0000";

    /**
     * The {@code SPACE} character, kept beside {@link #LOW_VALUE} so that the difference between the two is
     * visible at every use site.
     */
    public static final String SPACE = " ";

    /**
     * {@code TRNNAMEO PIC X(4)}, {@code COCRDLI.CPY:296}; {@code DFHMDF TRNNAME}, {@code .bms:34}.
     */
    public static final String TRNNAMEO_ITEM = "TRNNAMEO";

    /**
     * {@code TITLE01O PIC X(40)}, {@code COCRDLI.CPY:302}; {@code DFHMDF TITLE01}, {@code .bms:38}.
     */
    public static final String TITLE01O_ITEM = "TITLE01O";

    /**
     * {@code CURDATEO PIC X(8)}, {@code COCRDLI.CPY:308}; {@code DFHMDF CURDATE}, {@code .bms:47}.
     */
    public static final String CURDATEO_ITEM = "CURDATEO";

    /**
     * {@code PGMNAMEO PIC X(8)}, {@code COCRDLI.CPY:314}; {@code DFHMDF PGMNAME}, {@code .bms:57}.
     */
    public static final String PGMNAMEO_ITEM = "PGMNAMEO";

    /**
     * {@code TITLE02O PIC X(40)}, {@code COCRDLI.CPY:320}; {@code DFHMDF TITLE02}, {@code .bms:61}.
     */
    public static final String TITLE02O_ITEM = "TITLE02O";

    /**
     * {@code CURTIMEO PIC X(8)}, {@code COCRDLI.CPY:326}; {@code DFHMDF CURTIME}, {@code .bms:70}.
     */
    public static final String CURTIMEO_ITEM = "CURTIMEO";

    /**
     * {@code PAGENOO PIC X(3)}, {@code COCRDLI.CPY:332}; {@code DFHMDF PAGENO}, {@code .bms:82}.
     */
    public static final String PAGENOO_ITEM = "PAGENOO";

    /**
     * {@code ACCTSIDO PIC X(11)}, {@code COCRDLI.CPY:338}; {@code DFHMDF ACCTSID}, {@code .bms:89}.
     */
    public static final String ACCTSIDO_ITEM = "ACCTSIDO";

    /**
     * {@code CARDSIDO PIC X(16)}, {@code COCRDLI.CPY:344}; {@code DFHMDF CARDSID}, {@code .bms:101}.
     */
    public static final String CARDSIDO_ITEM = "CARDSIDO";

    /**
     * {@code CRDSEL1O PIC X(1)}, {@code COCRDLI.CPY:350}; {@code DFHMDF CRDSEL1}, {@code .bms:140}.
     */
    public static final String CRDSEL1O_ITEM = "CRDSEL1O";

    /**
     * {@code ACCTNO1O PIC X(11)}, {@code COCRDLI.CPY:356}; {@code DFHMDF ACCTNO1}, {@code .bms:147}.
     */
    public static final String ACCTNO1O_ITEM = "ACCTNO1O";

    /**
     * {@code CRDNUM1O PIC X(16)}, {@code COCRDLI.CPY:362}; {@code DFHMDF CRDNUM1}, {@code .bms:152}.
     */
    public static final String CRDNUM1O_ITEM = "CRDNUM1O";

    /**
     * {@code CRDSTS1O PIC X(1)}, {@code COCRDLI.CPY:368}; {@code DFHMDF CRDSTS1}, {@code .bms:157}.
     */
    public static final String CRDSTS1O_ITEM = "CRDSTS1O";

    /**
     * {@code CRDSEL2O PIC X(1)}, {@code COCRDLI.CPY:374}; {@code DFHMDF CRDSEL2}, {@code .bms:162}.
     */
    public static final String CRDSEL2O_ITEM = "CRDSEL2O";

    /**
     * {@code CRDSTP2O PIC X(1)}, {@code COCRDLI.CPY:380}; {@code DFHMDF CRDSTP2}, {@code .bms:169}.
     */
    public static final String CRDSTP2O_ITEM = "CRDSTP2O";

    /**
     * {@code ACCTNO2O PIC X(11)}, {@code COCRDLI.CPY:386}; {@code DFHMDF ACCTNO2}, {@code .bms:174}.
     */
    public static final String ACCTNO2O_ITEM = "ACCTNO2O";

    /**
     * {@code CRDNUM2O PIC X(16)}, {@code COCRDLI.CPY:392}; {@code DFHMDF CRDNUM2}, {@code .bms:179}.
     */
    public static final String CRDNUM2O_ITEM = "CRDNUM2O";

    /**
     * {@code CRDSTS2O PIC X(1)}, {@code COCRDLI.CPY:398}; {@code DFHMDF CRDSTS2}, {@code .bms:184}.
     */
    public static final String CRDSTS2O_ITEM = "CRDSTS2O";

    /**
     * {@code CRDSEL3O PIC X(1)}, {@code COCRDLI.CPY:404}; {@code DFHMDF CRDSEL3}, {@code .bms:189}.
     */
    public static final String CRDSEL3O_ITEM = "CRDSEL3O";

    /**
     * {@code CRDSTP3O PIC X(1)}, {@code COCRDLI.CPY:410}; {@code DFHMDF CRDSTP3}, {@code .bms:196}.
     */
    public static final String CRDSTP3O_ITEM = "CRDSTP3O";

    /**
     * {@code ACCTNO3O PIC X(11)}, {@code COCRDLI.CPY:416}; {@code DFHMDF ACCTNO3}, {@code .bms:201}.
     */
    public static final String ACCTNO3O_ITEM = "ACCTNO3O";

    /**
     * {@code CRDNUM3O PIC X(16)}, {@code COCRDLI.CPY:422}; {@code DFHMDF CRDNUM3}, {@code .bms:206}.
     */
    public static final String CRDNUM3O_ITEM = "CRDNUM3O";

    /**
     * {@code CRDSTS3O PIC X(1)}, {@code COCRDLI.CPY:428}; {@code DFHMDF CRDSTS3}, {@code .bms:211}.
     */
    public static final String CRDSTS3O_ITEM = "CRDSTS3O";

    /**
     * {@code CRDSEL4O PIC X(1)}, {@code COCRDLI.CPY:434}; {@code DFHMDF CRDSEL4}, {@code .bms:216}.
     */
    public static final String CRDSEL4O_ITEM = "CRDSEL4O";

    /**
     * {@code CRDSTP4O PIC X(1)}, {@code COCRDLI.CPY:440}; {@code DFHMDF CRDSTP4}, {@code .bms:223}.
     */
    public static final String CRDSTP4O_ITEM = "CRDSTP4O";

    /**
     * {@code ACCTNO4O PIC X(11)}, {@code COCRDLI.CPY:446}; {@code DFHMDF ACCTNO4}, {@code .bms:228}.
     */
    public static final String ACCTNO4O_ITEM = "ACCTNO4O";

    /**
     * {@code CRDNUM4O PIC X(16)}, {@code COCRDLI.CPY:452}; {@code DFHMDF CRDNUM4}, {@code .bms:233}.
     */
    public static final String CRDNUM4O_ITEM = "CRDNUM4O";

    /**
     * {@code CRDSTS4O PIC X(1)}, {@code COCRDLI.CPY:458}; {@code DFHMDF CRDSTS4}, {@code .bms:238}.
     */
    public static final String CRDSTS4O_ITEM = "CRDSTS4O";

    /**
     * {@code CRDSEL5O PIC X(1)}, {@code COCRDLI.CPY:464}; {@code DFHMDF CRDSEL5}, {@code .bms:243}.
     */
    public static final String CRDSEL5O_ITEM = "CRDSEL5O";

    /**
     * {@code CRDSTP5O PIC X(1)}, {@code COCRDLI.CPY:470}; {@code DFHMDF CRDSTP5}, {@code .bms:250}.
     */
    public static final String CRDSTP5O_ITEM = "CRDSTP5O";

    /**
     * {@code ACCTNO5O PIC X(11)}, {@code COCRDLI.CPY:476}; {@code DFHMDF ACCTNO5}, {@code .bms:255}.
     */
    public static final String ACCTNO5O_ITEM = "ACCTNO5O";

    /**
     * {@code CRDNUM5O PIC X(16)}, {@code COCRDLI.CPY:482}; {@code DFHMDF CRDNUM5}, {@code .bms:260}.
     */
    public static final String CRDNUM5O_ITEM = "CRDNUM5O";

    /**
     * {@code CRDSTS5O PIC X(1)}, {@code COCRDLI.CPY:488}; {@code DFHMDF CRDSTS5}, {@code .bms:265}.
     */
    public static final String CRDSTS5O_ITEM = "CRDSTS5O";

    /**
     * {@code CRDSEL6O PIC X(1)}, {@code COCRDLI.CPY:494}; {@code DFHMDF CRDSEL6}, {@code .bms:270}.
     */
    public static final String CRDSEL6O_ITEM = "CRDSEL6O";

    /**
     * {@code CRDSTP6O PIC X(1)}, {@code COCRDLI.CPY:500}; {@code DFHMDF CRDSTP6}, {@code .bms:277}.
     */
    public static final String CRDSTP6O_ITEM = "CRDSTP6O";

    /**
     * {@code ACCTNO6O PIC X(11)}, {@code COCRDLI.CPY:506}; {@code DFHMDF ACCTNO6}, {@code .bms:282}.
     */
    public static final String ACCTNO6O_ITEM = "ACCTNO6O";

    /**
     * {@code CRDNUM6O PIC X(16)}, {@code COCRDLI.CPY:512}; {@code DFHMDF CRDNUM6}, {@code .bms:287}.
     */
    public static final String CRDNUM6O_ITEM = "CRDNUM6O";

    /**
     * {@code CRDSTS6O PIC X(1)}, {@code COCRDLI.CPY:518}; {@code DFHMDF CRDSTS6}, {@code .bms:292}.
     */
    public static final String CRDSTS6O_ITEM = "CRDSTS6O";

    /**
     * {@code CRDSEL7O PIC X(1)}, {@code COCRDLI.CPY:524}; {@code DFHMDF CRDSEL7}, {@code .bms:297}.
     */
    public static final String CRDSEL7O_ITEM = "CRDSEL7O";

    /**
     * {@code CRDSTP7O PIC X(1)}, {@code COCRDLI.CPY:530}; {@code DFHMDF CRDSTP7}, {@code .bms:304}.
     */
    public static final String CRDSTP7O_ITEM = "CRDSTP7O";

    /**
     * {@code ACCTNO7O PIC X(11)}, {@code COCRDLI.CPY:536}; {@code DFHMDF ACCTNO7}, {@code .bms:309}.
     */
    public static final String ACCTNO7O_ITEM = "ACCTNO7O";

    /**
     * {@code CRDNUM7O PIC X(16)}, {@code COCRDLI.CPY:542}; {@code DFHMDF CRDNUM7}, {@code .bms:314}.
     */
    public static final String CRDNUM7O_ITEM = "CRDNUM7O";

    /**
     * {@code CRDSTS7O PIC X(1)}, {@code COCRDLI.CPY:548}; {@code DFHMDF CRDSTS7}, {@code .bms:319}.
     */
    public static final String CRDSTS7O_ITEM = "CRDSTS7O";

    /**
     * {@code INFOMSGO PIC X(45)}, {@code COCRDLI.CPY:554}; {@code DFHMDF INFOMSG}, {@code .bms:324}.
     */
    public static final String INFOMSGO_ITEM = "INFOMSGO";

    /**
     * {@code ERRMSGO PIC X(78)}, {@code COCRDLI.CPY:560}; {@code DFHMDF ERRMSG}, {@code .bms:331}.
     */
    public static final String ERRMSGO_ITEM = "ERRMSGO";

    /**
     * One field of the {@code CCRDLIAO} output group: its item name, its declared width, the
     * {@code app/cpy-bms/COCRDLI.CPY} line that declares it, and the absolute offset at which its
     * seven-byte prefix begins.
     *
     * @param itemName the {@code xxxO} item name, verbatim from the copybook, for example
     *     {@code "CRDSTP2O"}
     * @param length {@code n} of that item's {@code PIC X(n)}
     * @param copybookLine the 1-based line of {@code app/cpy-bms/COCRDLI.CPY} that declares the
     *     {@code xxxO} item, for citation in diagnostics and in a parity report
     * @param prefixOffset the absolute 0-based offset of the field's {@code FILLER X(3)}
     */
    public record MapField(String itemName, int length, int copybookLine, int prefixOffset) {
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
         * The {@code DFHMDF} label this item belongs to - the item name without its trailing {@code O}.
         *
         * @return the screen field prefix, for example {@code "CRDSTP2"} for {@code "CRDSTP2O"}
         */
        public String screenFieldPrefix() {
            return itemName.substring(0, itemName.length()
                    - FieldAttributeSetter.OUTPUT_ITEM_SUFFIX.length());
        }

        public String colourItemName() {
            return screenFieldPrefix() + FieldAttributeSetter.COLOUR_ITEM_SUFFIX;
        }

        public String psItemName() {
            return screenFieldPrefix() + "P";
        }

        public String highlightItemName() {
            return screenFieldPrefix() + "H";
        }

        public String validnItemName() {
            return screenFieldPrefix() + "V";
        }

        public int colourOffset() {
            return prefixOffset + RESERVED_SPAN_LENGTH;
        }

        public int psOffset() {
            return colourOffset() + ATTRIBUTE_ITEM_LENGTH;
        }

        public int highlightOffset() {
            return psOffset() + ATTRIBUTE_ITEM_LENGTH;
        }

        public int validnOffset() {
            return highlightOffset() + ATTRIBUTE_ITEM_LENGTH;
        }

        public int dataOffset() {
            return prefixOffset + FIELD_PREFIX_LENGTH;
        }

        public int endOffsetExclusive() {
            return dataOffset() + length;
        }

        public FieldSpan reservedSpan() {
            return FieldSpan.filler(prefixOffset, RESERVED_SPAN_LENGTH);
        }

        public FieldSpan colourSpan() {
            return FieldSpan.alphanumeric(colourItemName(), colourOffset(), ATTRIBUTE_ITEM_LENGTH);
        }

        public FieldSpan psSpan() {
            return FieldSpan.alphanumeric(psItemName(), psOffset(), ATTRIBUTE_ITEM_LENGTH);
        }

        public FieldSpan highlightSpan() {
            return FieldSpan.alphanumeric(highlightItemName(), highlightOffset(),
                    ATTRIBUTE_ITEM_LENGTH);
        }

        public FieldSpan validnSpan() {
            return FieldSpan.alphanumeric(validnItemName(), validnOffset(), ATTRIBUTE_ITEM_LENGTH);
        }

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
     */
    public static final List<MapField> MAP_FIELDS = buildMapFields();

    private static final Map<String, MapField> MAP_FIELDS_BY_ITEM = indexByItemName();

    private static final Map<String, MapField> MAP_FIELDS_BY_PREFIX = indexByScreenFieldPrefix();

    /**
     * The complete 797-byte geometry of {@code 01 CCRDLIAO REDEFINES CCRDLIAI}
     * [{@code app/cpy-bms/COCRDLI.CPY:289}]: the {@code TIOAPFX} {@code FILLER X(12)}, then for each of the
     * 45 fields a {@code FILLER X(3)} followed by the {@code xxxC}, {@code xxxP}, {@code xxxH} and
     * {@code xxxV} items and the {@code xxxO} data item.
     */
    public static final RecordLayout GROUP_LAYOUT = buildGroupLayout();

    private static final FixedWidthCodec PIC_X_MOVE_CODEC =
            new FixedWidthCodec(StandardCharsets.US_ASCII);

    private static List<MapField> buildMapFields() {
        List<MapField> fields = new ArrayList<>(PAYLOAD_FIELD_COUNT);
        int next = TIOAPFX_LENGTH;

        next = append(fields, TRNNAMEO_ITEM, TRNNAMEO_LENGTH, 296, next);
        next = append(fields, TITLE01O_ITEM, TITLE01O_LENGTH, 302, next);
        next = append(fields, CURDATEO_ITEM, CURDATEO_LENGTH, 308, next);
        next = append(fields, PGMNAMEO_ITEM, PGMNAMEO_LENGTH, 314, next);
        next = append(fields, TITLE02O_ITEM, TITLE02O_LENGTH, 320, next);
        next = append(fields, CURTIMEO_ITEM, CURTIMEO_LENGTH, 326, next);
        next = append(fields, PAGENOO_ITEM, PAGENOO_LENGTH, 332, next);
        next = append(fields, ACCTSIDO_ITEM, ACCTSIDO_LENGTH, 338, next);
        next = append(fields, CARDSIDO_ITEM, CARDSIDO_LENGTH, 344, next);

        next = append(fields, CRDSEL1O_ITEM, CRDSEL_LENGTH, 350, next);
        next = append(fields, ACCTNO1O_ITEM, ACCTNO_LENGTH, 356, next);
        next = append(fields, CRDNUM1O_ITEM, CRDNUM_LENGTH, 362, next);
        next = append(fields, CRDSTS1O_ITEM, CRDSTS_LENGTH, 368, next);

        next = append(fields, CRDSEL2O_ITEM, CRDSEL_LENGTH, 374, next);
        next = append(fields, CRDSTP2O_ITEM, CRDSTP_LENGTH, 380, next);
        next = append(fields, ACCTNO2O_ITEM, ACCTNO_LENGTH, 386, next);
        next = append(fields, CRDNUM2O_ITEM, CRDNUM_LENGTH, 392, next);
        next = append(fields, CRDSTS2O_ITEM, CRDSTS_LENGTH, 398, next);

        next = append(fields, CRDSEL3O_ITEM, CRDSEL_LENGTH, 404, next);
        next = append(fields, CRDSTP3O_ITEM, CRDSTP_LENGTH, 410, next);
        next = append(fields, ACCTNO3O_ITEM, ACCTNO_LENGTH, 416, next);
        next = append(fields, CRDNUM3O_ITEM, CRDNUM_LENGTH, 422, next);
        next = append(fields, CRDSTS3O_ITEM, CRDSTS_LENGTH, 428, next);

        next = append(fields, CRDSEL4O_ITEM, CRDSEL_LENGTH, 434, next);
        next = append(fields, CRDSTP4O_ITEM, CRDSTP_LENGTH, 440, next);
        next = append(fields, ACCTNO4O_ITEM, ACCTNO_LENGTH, 446, next);
        next = append(fields, CRDNUM4O_ITEM, CRDNUM_LENGTH, 452, next);
        next = append(fields, CRDSTS4O_ITEM, CRDSTS_LENGTH, 458, next);

        next = append(fields, CRDSEL5O_ITEM, CRDSEL_LENGTH, 464, next);
        next = append(fields, CRDSTP5O_ITEM, CRDSTP_LENGTH, 470, next);
        next = append(fields, ACCTNO5O_ITEM, ACCTNO_LENGTH, 476, next);
        next = append(fields, CRDNUM5O_ITEM, CRDNUM_LENGTH, 482, next);
        next = append(fields, CRDSTS5O_ITEM, CRDSTS_LENGTH, 488, next);

        next = append(fields, CRDSEL6O_ITEM, CRDSEL_LENGTH, 494, next);
        next = append(fields, CRDSTP6O_ITEM, CRDSTP_LENGTH, 500, next);
        next = append(fields, ACCTNO6O_ITEM, ACCTNO_LENGTH, 506, next);
        next = append(fields, CRDNUM6O_ITEM, CRDNUM_LENGTH, 512, next);
        next = append(fields, CRDSTS6O_ITEM, CRDSTS_LENGTH, 518, next);

        next = append(fields, CRDSEL7O_ITEM, CRDSEL_LENGTH, 524, next);
        next = append(fields, CRDSTP7O_ITEM, CRDSTP_LENGTH, 530, next);
        next = append(fields, ACCTNO7O_ITEM, ACCTNO_LENGTH, 536, next);
        next = append(fields, CRDNUM7O_ITEM, CRDNUM_LENGTH, 542, next);
        next = append(fields, CRDSTS7O_ITEM, CRDSTS_LENGTH, 548, next);

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

    private static int append(List<MapField> fields, String itemName, int length, int copybookLine,
            int prefixOffset) {
        MapField field = new MapField(itemName, length, copybookLine, prefixOffset);
        fields.add(field);
        return field.endOffsetExclusive();
    }

    private static Map<String, MapField> indexByItemName() {
        Map<String, MapField> index = new LinkedHashMap<>();
        for (MapField field : MAP_FIELDS) {
            index.put(field.itemName(), field);
        }
        return Collections.unmodifiableMap(index);
    }

    private static Map<String, MapField> indexByScreenFieldPrefix() {
        Map<String, MapField> index = new LinkedHashMap<>();
        for (MapField field : MAP_FIELDS) {
            index.put(field.screenFieldPrefix(), field);
        }
        return Collections.unmodifiableMap(index);
    }

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

    /**
     * One row of the browse result: the projection of {@code WS-SCREEN-ROWS(n)}
     * [{@code app/cbl/COCRDLIC.cbl:255-260}]. 11 + 16 + 1 = {@link #SCREEN_ROW_LENGTH} bytes per element, x
     * {@value #ROW_COUNT} rows = {@link #SCREEN_ARRAY_LENGTH}, which is what {@code WS-ALL-ROWS PIC X(196)}
     * declares and what the source comment at L250 states.
     *
     * @param rowAcctno {@code WS-ROW-ACCTNO PIC X(11)} [L258], exactly {@value #ACCTNO_LENGTH} characters
     * @param rowCardNum {@code WS-ROW-CARD-NUM PIC X(16)} [L259], exactly {@value #CRDNUM_LENGTH}
     *     characters - a full card number, in the clear, exactly as the map carries it
     * @param rowCardStatus {@code WS-ROW-CARD-STATUS PIC X(1)} [L260], exactly {@value #CRDSTS_LENGTH}
     *     character
     */
    public record ScreenRow(String rowAcctno, String rowCardNum, String rowCardStatus) {
        public ScreenRow {
            requireExactWidth(rowAcctno, ACCTNO_LENGTH, "WS-ROW-ACCTNO");
            requireExactWidth(rowCardNum, CRDNUM_LENGTH, "WS-ROW-CARD-NUM");
            requireExactWidth(rowCardStatus, CRDSTS_LENGTH, "WS-ROW-CARD-STATUS");
        }

        /**
         * A row in the state {@code MOVE LOW-VALUES TO CCRDLIAO} [{@code app/cbl/COCRDLIC.cbl:643}] leaves
         * it: all {@link #SCREEN_ROW_LENGTH} bytes {@code x'00'}.
         *
         * @return an all-{@code LOW-VALUES} row; never {@code null}
         */
        public static ScreenRow lowValues() {
            return new ScreenRow(CardScreenState.lowValues(ACCTNO_LENGTH),
                    CardScreenState.lowValues(CRDNUM_LENGTH),
                    CardScreenState.lowValues(CRDSTS_LENGTH));
        }

        /**
         * A space-filled row, which is a different state from {@link #lowValues()} and is never substituted
         * for it.
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
         * @param acctno the account number; may be shorter or longer than {@value #ACCTNO_LENGTH}
         * @param cardNum the card number; may be shorter or longer than {@value #CRDNUM_LENGTH}
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
         * Rebuilds a row from its {@link #SCREEN_ROW_LENGTH}-character image, the form {@code WS-ALL-ROWS}
         * stores seven of.
         *
         * @param image exactly {@link #SCREEN_ROW_LENGTH} characters
         * @return the row the image describes; never {@code null}
         * @throws NullPointerException if {@code image} is {@code null}
         * @throws IllegalArgumentException if {@code image} is not exactly {@link #SCREEN_ROW_LENGTH}
         *     characters
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
         * {@code true} when all {@link #SCREEN_ROW_LENGTH} bytes are {@code x'00'} - the
         * {@code IF WS-EACH-CARD(n) EQUAL LOW-VALUES} test.
         *
         * <p>Space-filled is deliberately not low-values here, because the COBOL comparison is against the
         * figurative constant {@code LOW-VALUES} and nothing else.
         *
         * @return {@code true} when the row is entirely {@code LOW-VALUES}
         */
        @JsonIgnore
        public boolean isLowValues() {
            return image().equals(CardScreenState.lowValues(SCREEN_ROW_LENGTH));
        }

        /**
         * The row as the {@link #SCREEN_ROW_LENGTH}-character span {@code WS-SCREEN-ROWS(n)} occupies
         * inside {@code WS-ALL-ROWS}.
         *
         * @return the concatenation of the three components, in declaration order
         */
        public String image() {
            return rowAcctno + rowCardNum + rowCardStatus;
        }
    }

    /**
     * The four extended-attribute bytes {@code CCRDLIAO} carries for one screen field, in the byte order
     * the copybook declares them: {@code xxxC} then {@code xxxP} then {@code xxxH} then {@code xxxV}.
     */
    public static final class FieldAttributes {
        private byte colour;

        private byte ps;

        private byte highlight;

        private byte validn;

        /**
         * Creates a quad in the state {@code MOVE LOW-VALUES TO CCRDLIAO} leaves: all four bytes
         * {@code 0x00}.
         */
        public FieldAttributes() {
            reset();
        }

        /**
         * Copy constructor, used when a response is copied so that no quad is shared between two responses.
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

        /**
         * Restores all four bytes to {@code 0x00}, the {@code LOW-VALUES} state of the group.
         */
        public void reset() {
            this.colour = 0x00;
            this.ps = 0x00;
            this.highlight = 0x00;
            this.validn = 0x00;
        }

        public byte colour() {
            return colour;
        }

        public void setColour(byte value) {
            this.colour = value;
        }

        public byte ps() {
            return ps;
        }

        public void setPs(byte value) {
            this.ps = value;
        }

        public byte highlight() {
            return highlight;
        }

        public void setHighlight(byte value) {
            this.highlight = value;
        }

        public byte validn() {
            return validn;
        }

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
         * @throws NullPointerException if {@code bytes} is {@code null}
         * @throws IllegalArgumentException if {@code bytes.length} is not {@value #ATTRIBUTE_ITEM_COUNT}
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
         * Renders the quad with the BMS mnemonics its bytes stand for, so a log line is readable against
         * the copybook rather than being four hex numbers.
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

    // There is deliberately NO PageCursor record declared here. 01 WS-THIS-PROGCOMMAREA
    // [app/cbl/COCRDLIC.cbl:229-248] is ONE area. It is declared once, on CardListRequest, and referenced
    // here.

    private final Map<String, String> payload;

    private final Map<String, FieldAttributes> attributes;

    private String editSelectErrorFlags;

    private String nextProgram;

    private String nextMapset;

    private String nextMap;

    private PageCursor pageCursor;

    private CardScreenState cardScreenState;

    private NavigationContext navigationContext;

    private String cursorField;

    /**
     * Creates a response in the state {@code 1100-SCREEN-INIT} leaves the map in at its first statement:
     * {@code MOVE LOW-VALUES TO CCRDLIAO} [{@code app/cbl/COCRDLIC.cbl:643}].
     *
     * <p>A later write pads with spaces, because that is the {@code PIC X} move rule, and the two are never
     * conflated.
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
        this.cursorField = null;
    }

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
        this.cursorField = other.cursorField;
    }

    /**
     * Restores the whole response to the state {@code MOVE LOW-VALUES TO CCRDLIAO}
     * [{@code app/cbl/COCRDLIC.cbl:643}] produces: all 45 payload members and all 45 attribute quads back
     * to {@code LOW-VALUES}.
     */
    public void moveLowValuesToMap() {
        for (MapField field : MAP_FIELDS) {
            this.payload.put(field.itemName(), CardScreenState.lowValues(field.length()));
        }
        for (FieldAttributes quad : this.attributes.values()) {
            quad.reset();
        }
    }

    // Every typed accessor below delegates here, so the PIC X move rule is applied in exactly one place.

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
     * @throws NullPointerException if {@code screenFieldPrefix} is {@code null}
     * @throws IllegalArgumentException if the label names no field of this map
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
     * @throws NullPointerException if {@code itemName} is {@code null}
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
     * {@link FixedWidthCodec#movePicX(String, int)} rather than by assignment precisely so the direction is
     * never in doubt.
     *
     * @param itemName an {@code xxxO} item name
     * @param value the sending value, of any length; to blank a field, move
     *     {@link CardScreenState#spaces(int)} or {@link #LOW_VALUE} explicitly rather than passing {@code null}
     * @throws NullPointerException if {@code itemName} or {@code value} is {@code null}
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
     * @return an unmodifiable, insertion-ordered copy of the 45 items
     */
    @JsonIgnore
    public Map<String, String> fieldImages() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    /**
     * The number of payload members, which is fixed at {@link #PAYLOAD_FIELD_COUNT} for the lifetime of an
     * instance.
     *
     * @return {@link #PAYLOAD_FIELD_COUNT}
     */
    @JsonIgnore
    public int payloadFieldCount() {
        return payload.size();
    }

    /**
     * {@code TRNNAMEO PIC X(4)} [{@code COCRDLI.CPY:296}],
     * {@code DFHMDF TRNNAME COLOR=BLUE, LENGTH=4, POS=(1,7)} [{@code .bms:34-37}].
     *
     * @return the four-character transaction identifier, untrimmed
     */
    @JsonProperty("trnname")
    public String getTrnnameo() {
        return field(TRNNAMEO_ITEM);
    }

    public void setTrnnameo(String value) {
        setField(TRNNAMEO_ITEM, value);
    }

    /**
     * {@code TITLE01O PIC X(40)} [{@code COCRDLI.CPY:302}],
     * {@code DFHMDF TITLE01 COLOR=YELLOW, LENGTH=40, POS=(1,21)} [{@code .bms:38-41}].
     *
     * <p>Written by {@code MOVE CCDA-TITLE01 TO TITLE01O OF CCRDLIAO} [{@code app/cbl/COCRDLIC.cbl:647}] -
     * see {@link ScreenTitles#CCDA_TITLE01}, which is exactly 40 characters and so needs no padding.
     *
     * @return the upper title line, untrimmed
     */
    @JsonProperty("title01")
    public String getTitle01o() {
        return field(TITLE01O_ITEM);
    }

    public void setTitle01o(String value) {
        setField(TITLE01O_ITEM, value);
    }

    /**
     * {@code CURDATEO PIC X(8)} [{@code COCRDLI.CPY:308}],
     * {@code DFHMDF CURDATE COLOR=BLUE, LENGTH=8, POS=(1,71), INITIAL='mm/dd/yy'} [{@code .bms:47-51}].
     *
     * @return the {@code mm/dd/yy} date, untrimmed
     */
    @JsonProperty("curdate")
    public String getCurdateo() {
        return field(CURDATEO_ITEM);
    }

    public void setCurdateo(String value) {
        setField(CURDATEO_ITEM, value);
    }

    /**
     * {@code PGMNAMEO PIC X(8)} [{@code COCRDLI.CPY:314}],
     * {@code DFHMDF PGMNAME COLOR=BLUE, LENGTH=8, POS=(2,7)} [{@code .bms:57-60}].
     *
     * @return the eight-character program name, untrimmed
     */
    @JsonProperty("pgmname")
    public String getPgmnameo() {
        return field(PGMNAMEO_ITEM);
    }

    public void setPgmnameo(String value) {
        setField(PGMNAMEO_ITEM, value);
    }

    /**
     * {@code TITLE02O PIC X(40)} [{@code COCRDLI.CPY:320}],
     * {@code DFHMDF TITLE02 COLOR=YELLOW, LENGTH=40, POS=(2,21)} [{@code .bms:61-64}].
     *
     * @return the lower title line, untrimmed
     */
    @JsonProperty("title02")
    public String getTitle02o() {
        return field(TITLE02O_ITEM);
    }

    public void setTitle02o(String value) {
        setField(TITLE02O_ITEM, value);
    }

    /**
     * {@code CURTIMEO PIC X(8)} [{@code COCRDLI.CPY:326}],
     * {@code DFHMDF CURTIME COLOR=BLUE, LENGTH=8, POS=(2,71), INITIAL='hh:mm:ss'} [{@code .bms:70-74}].
     *
     * @return the {@code hh:mm:ss} time, untrimmed
     */
    @JsonProperty("curtime")
    public String getCurtimeo() {
        return field(CURTIMEO_ITEM);
    }

    public void setCurtimeo(String value) {
        setField(CURTIMEO_ITEM, value);
    }

    /**
     * {@code PAGENOO PIC X(3)} [{@code COCRDLI.CPY:332}], {@code DFHMDF PAGENO LENGTH=3, POS=(4,76)}
     * [{@code .bms:82-83}] - the only card map that has this field.
     *
     * @return the three-character page number image, untrimmed
     */
    @JsonProperty("pageno")
    public String getPagenoo() {
        return field(PAGENOO_ITEM);
    }

    public void setPagenoo(String value) {
        setField(PAGENOO_ITEM, value);
    }

    /**
     * {@code ACCTSIDO PIC X(11)} [{@code COCRDLI.CPY:338}],
     * {@code DFHMDF ACCTSID ATTRB=(FSET,IC,NORM,UNPROT), COLOR=GREEN, HILIGHT=UNDERLINE, LENGTH=11, POS=(6,44)}
     * [{@code .bms:89-93}] - the account-number filter echoed back to the user.
     *
     * @return the eleven-character account filter, untrimmed
     */
    @JsonProperty("acctsid")
    public String getAcctsido() {
        return field(ACCTSIDO_ITEM);
    }

    public void setAcctsido(String value) {
        setField(ACCTSIDO_ITEM, value);
    }

    /**
     * {@code CARDSIDO PIC X(16)} [{@code COCRDLI.CPY:344}],
     * {@code DFHMDF CARDSID ATTRB=(FSET,NORM,UNPROT), COLOR=GREEN, HILIGHT=UNDERLINE, LENGTH=16, POS=(7,44)}
     * [{@code .bms:101-105}] - the card-number filter echoed back to the user.
     *
     * @return the sixteen-character card filter, untrimmed
     */
    @JsonProperty("cardsid")
    public String getCardsido() {
        return field(CARDSIDO_ITEM);
    }

    public void setCardsido(String value) {
        setField(CARDSIDO_ITEM, value);
    }

    /**
     * {@code CRDSEL1O PIC X(1)} [{@code COCRDLI.CPY:350}],
     * {@code DFHMDF CRDSEL1 ATTRB=(FSET,NORM,PROT), COLOR=DEFAULT, HILIGHT=UNDERLINE, LENGTH=1, POS=(11,12)}
     * [{@code .bms:140-144}].
     *
     * @return the one-character selection value for row 1
     */
    @JsonProperty("crdsel1")
    public String getCrdsel1o() {
        return field(CRDSEL1O_ITEM);
    }

    public void setCrdsel1o(String value) {
        setField(CRDSEL1O_ITEM, value);
    }

    /**
     * {@code ACCTNO1O PIC X(11)} [{@code COCRDLI.CPY:356}],
     * {@code DFHMDF ACCTNO1 ATTRB=(NORM,PROT), COLOR=DEFAULT, HILIGHT=OFF, LENGTH=11, POS=(11,22)}
     * [{@code .bms:147-151}].
     *
     * @return the eleven-character account number of row 1
     */
    @JsonProperty("acctno1")
    public String getAcctno1o() {
        return field(ACCTNO1O_ITEM);
    }

    public void setAcctno1o(String value) {
        setField(ACCTNO1O_ITEM, value);
    }

    /**
     * {@code CRDNUM1O PIC X(16)} [{@code COCRDLI.CPY:362}],
     * {@code DFHMDF CRDNUM1 ATTRB=(NORM,PROT), COLOR=DEFAULT, HILIGHT=OFF, LENGTH=16, POS=(11,43)}
     * [{@code .bms:152-156}].
     *
     * @return the sixteen-character card number of row 1, in the clear as the map carries it
     */
    @JsonProperty("crdnum1")
    public String getCrdnum1o() {
        return field(CRDNUM1O_ITEM);
    }

    public void setCrdnum1o(String value) {
        setField(CRDNUM1O_ITEM, value);
    }

    /**
     * {@code CRDSTS1O PIC X(1)} [{@code COCRDLI.CPY:368}],
     * {@code DFHMDF CRDSTS1 ATTRB=(NORM,PROT), COLOR=DEFAULT, HILIGHT=OFF, LENGTH=1, POS=(11,67)}
     * [{@code .bms:157-161}].
     *
     * @return the one-character card status of row 1
     */
    @JsonProperty("crdsts1")
    public String getCrdsts1o() {
        return field(CRDSTS1O_ITEM);
    }

    public void setCrdsts1o(String value) {
        setField(CRDSTS1O_ITEM, value);
    }

    // Each row's accessors are written out rather than generated, because the copybook writes them out and
    // a reviewer must be able to read the two side by side.

    /**
     * {@code CRDSEL2O PIC X(1)} [{@code COCRDLI.CPY:374}], {@code DFHMDF CRDSEL2} [{@code .bms:162}];
     * written by {@code MOVE WS-EDIT-SELECT(2)} [{@code app/cbl/COCRDLIC.cbl:692}].
     *
     * @return the one-character selection value for row 2
     */
    @JsonProperty("crdsel2")
    public String getCrdsel2o() {
        return field(CRDSEL2O_ITEM);
    }

    public void setCrdsel2o(String value) {
        setField(CRDSEL2O_ITEM, value);
    }

    /**
     * {@code CRDSTP2O PIC X(1)} [{@code COCRDLI.CPY:380}],
     * {@code DFHMDF CRDSTP2 ATTRB=(ASKIP,DRK,FSET), LENGTH=1, POS=(12,14)} [{@code .bms:169-173}] - the
     * hidden selection-type field, second in the row.
     *
     * @return the one-character hidden selection type for row 2
     */
    @JsonProperty("crdstp2")
    public String getCrdstp2o() {
        return field(CRDSTP2O_ITEM);
    }

    public void setCrdstp2o(String value) {
        setField(CRDSTP2O_ITEM, value);
    }

    /**
     * {@code ACCTNO2O PIC X(11)} [{@code COCRDLI.CPY:386}], {@code DFHMDF ACCTNO2} [{@code .bms:174}];
     * written by {@code MOVE WS-ROW-ACCTNO(2)} [{@code app/cbl/COCRDLIC.cbl:693}].
     *
     * @return the eleven-character account number of row 2
     */
    @JsonProperty("acctno2")
    public String getAcctno2o() {
        return field(ACCTNO2O_ITEM);
    }

    public void setAcctno2o(String value) {
        setField(ACCTNO2O_ITEM, value);
    }

    /**
     * {@code CRDNUM2O PIC X(16)} [{@code COCRDLI.CPY:392}], {@code DFHMDF CRDNUM2} [{@code .bms:179}];
     * written by {@code MOVE WS-ROW-CARD-NUM(2)} [{@code app/cbl/COCRDLIC.cbl:694}].
     *
     * @return the sixteen-character card number of row 2, in the clear as the map carries it
     */
    @JsonProperty("crdnum2")
    public String getCrdnum2o() {
        return field(CRDNUM2O_ITEM);
    }

    public void setCrdnum2o(String value) {
        setField(CRDNUM2O_ITEM, value);
    }

    /**
     * {@code CRDSTS2O PIC X(1)} [{@code COCRDLI.CPY:398}], {@code DFHMDF CRDSTS2} [{@code .bms:184}];
     * written by {@code MOVE WS-ROW-CARD-STATUS(2)} [{@code app/cbl/COCRDLIC.cbl:695}].
     *
     * @return the one-character card status of row 2
     */
    @JsonProperty("crdsts2")
    public String getCrdsts2o() {
        return field(CRDSTS2O_ITEM);
    }

    public void setCrdsts2o(String value) {
        setField(CRDSTS2O_ITEM, value);
    }

    /**
     * {@code CRDSEL3O PIC X(1)} [{@code COCRDLI.CPY:404}], {@code DFHMDF CRDSEL3} [{@code .bms:189}];
     * written by {@code MOVE WS-EDIT-SELECT(3)} [{@code app/cbl/COCRDLIC.cbl:701}].
     *
     * @return the one-character selection value for row 3
     */
    @JsonProperty("crdsel3")
    public String getCrdsel3o() {
        return field(CRDSEL3O_ITEM);
    }

    public void setCrdsel3o(String value) {
        setField(CRDSEL3O_ITEM, value);
    }

    /**
     * {@code CRDSTP3O PIC X(1)} [{@code COCRDLI.CPY:410}], {@code DFHMDF CRDSTP3} [{@code .bms:196}] - the
     * hidden selection-type field, second in the row.
     *
     * @return the one-character hidden selection type for row 3
     */
    @JsonProperty("crdstp3")
    public String getCrdstp3o() {
        return field(CRDSTP3O_ITEM);
    }

    public void setCrdstp3o(String value) {
        setField(CRDSTP3O_ITEM, value);
    }

    /**
     * {@code ACCTNO3O PIC X(11)} [{@code COCRDLI.CPY:416}], {@code DFHMDF ACCTNO3} [{@code .bms:201}];
     * written by {@code MOVE WS-ROW-ACCTNO(3)} [{@code app/cbl/COCRDLIC.cbl:702}].
     *
     * @return the eleven-character account number of row 3
     */
    @JsonProperty("acctno3")
    public String getAcctno3o() {
        return field(ACCTNO3O_ITEM);
    }

    public void setAcctno3o(String value) {
        setField(ACCTNO3O_ITEM, value);
    }

    /**
     * {@code CRDNUM3O PIC X(16)} [{@code COCRDLI.CPY:422}], {@code DFHMDF CRDNUM3} [{@code .bms:206}];
     * written by {@code MOVE WS-ROW-CARD-NUM(3)} [{@code app/cbl/COCRDLIC.cbl:703}].
     *
     * @return the sixteen-character card number of row 3, in the clear as the map carries it
     */
    @JsonProperty("crdnum3")
    public String getCrdnum3o() {
        return field(CRDNUM3O_ITEM);
    }

    public void setCrdnum3o(String value) {
        setField(CRDNUM3O_ITEM, value);
    }

    /**
     * {@code CRDSTS3O PIC X(1)} [{@code COCRDLI.CPY:428}], {@code DFHMDF CRDSTS3} [{@code .bms:211}];
     * written by {@code MOVE WS-ROW-CARD-STATUS(3)} [{@code app/cbl/COCRDLIC.cbl:704}].
     *
     * @return the one-character card status of row 3
     */
    @JsonProperty("crdsts3")
    public String getCrdsts3o() {
        return field(CRDSTS3O_ITEM);
    }

    public void setCrdsts3o(String value) {
        setField(CRDSTS3O_ITEM, value);
    }

    /**
     * {@code CRDSEL4O PIC X(1)} [{@code COCRDLI.CPY:434}], {@code DFHMDF CRDSEL4} [{@code .bms:216}];
     * written by {@code MOVE WS-EDIT-SELECT(4)} [{@code app/cbl/COCRDLIC.cbl:710}].
     *
     * @return the one-character selection value for row 4
     */
    @JsonProperty("crdsel4")
    public String getCrdsel4o() {
        return field(CRDSEL4O_ITEM);
    }

    public void setCrdsel4o(String value) {
        setField(CRDSEL4O_ITEM, value);
    }

    /**
     * {@code CRDSTP4O PIC X(1)} [{@code COCRDLI.CPY:440}], {@code DFHMDF CRDSTP4} [{@code .bms:223}] - the
     * hidden selection-type field, second in the row.
     *
     * @return the one-character hidden selection type for row 4
     */
    @JsonProperty("crdstp4")
    public String getCrdstp4o() {
        return field(CRDSTP4O_ITEM);
    }

    public void setCrdstp4o(String value) {
        setField(CRDSTP4O_ITEM, value);
    }

    /**
     * {@code ACCTNO4O PIC X(11)} [{@code COCRDLI.CPY:446}], {@code DFHMDF ACCTNO4} [{@code .bms:228}];
     * written by {@code MOVE WS-ROW-ACCTNO(4)} [{@code app/cbl/COCRDLIC.cbl:711}].
     *
     * @return the eleven-character account number of row 4
     */
    @JsonProperty("acctno4")
    public String getAcctno4o() {
        return field(ACCTNO4O_ITEM);
    }

    public void setAcctno4o(String value) {
        setField(ACCTNO4O_ITEM, value);
    }

    /**
     * {@code CRDNUM4O PIC X(16)} [{@code COCRDLI.CPY:452}], {@code DFHMDF CRDNUM4} [{@code .bms:233}];
     * written by {@code MOVE WS-ROW-CARD-NUM(4)} [{@code app/cbl/COCRDLIC.cbl:712}].
     *
     * @return the sixteen-character card number of row 4, in the clear as the map carries it
     */
    @JsonProperty("crdnum4")
    public String getCrdnum4o() {
        return field(CRDNUM4O_ITEM);
    }

    public void setCrdnum4o(String value) {
        setField(CRDNUM4O_ITEM, value);
    }

    /**
     * {@code CRDSTS4O PIC X(1)} [{@code COCRDLI.CPY:458}], {@code DFHMDF CRDSTS4} [{@code .bms:238}];
     * written by {@code MOVE WS-ROW-CARD-STATUS(4)} [{@code app/cbl/COCRDLIC.cbl:713}].
     *
     * @return the one-character card status of row 4
     */
    @JsonProperty("crdsts4")
    public String getCrdsts4o() {
        return field(CRDSTS4O_ITEM);
    }

    public void setCrdsts4o(String value) {
        setField(CRDSTS4O_ITEM, value);
    }

    /**
     * {@code CRDSEL5O PIC X(1)} [{@code COCRDLI.CPY:464}], {@code DFHMDF CRDSEL5} [{@code .bms:243}];
     * written by {@code MOVE WS-EDIT-SELECT(5)} [{@code app/cbl/COCRDLIC.cbl:719}].
     *
     * @return the one-character selection value for row 5
     */
    @JsonProperty("crdsel5")
    public String getCrdsel5o() {
        return field(CRDSEL5O_ITEM);
    }

    public void setCrdsel5o(String value) {
        setField(CRDSEL5O_ITEM, value);
    }

    /**
     * {@code CRDSTP5O PIC X(1)} [{@code COCRDLI.CPY:470}], {@code DFHMDF CRDSTP5} [{@code .bms:250}] - the
     * hidden selection-type field, second in the row.
     *
     * @return the one-character hidden selection type for row 5
     */
    @JsonProperty("crdstp5")
    public String getCrdstp5o() {
        return field(CRDSTP5O_ITEM);
    }

    public void setCrdstp5o(String value) {
        setField(CRDSTP5O_ITEM, value);
    }

    /**
     * {@code ACCTNO5O PIC X(11)} [{@code COCRDLI.CPY:476}], {@code DFHMDF ACCTNO5} [{@code .bms:255}];
     * written by {@code MOVE WS-ROW-ACCTNO(5)} [{@code app/cbl/COCRDLIC.cbl:720}].
     *
     * @return the eleven-character account number of row 5
     */
    @JsonProperty("acctno5")
    public String getAcctno5o() {
        return field(ACCTNO5O_ITEM);
    }

    public void setAcctno5o(String value) {
        setField(ACCTNO5O_ITEM, value);
    }

    /**
     * {@code CRDNUM5O PIC X(16)} [{@code COCRDLI.CPY:482}], {@code DFHMDF CRDNUM5} [{@code .bms:260}];
     * written by {@code MOVE WS-ROW-CARD-NUM(5)} [{@code app/cbl/COCRDLIC.cbl:721}].
     *
     * @return the sixteen-character card number of row 5, in the clear as the map carries it
     */
    @JsonProperty("crdnum5")
    public String getCrdnum5o() {
        return field(CRDNUM5O_ITEM);
    }

    public void setCrdnum5o(String value) {
        setField(CRDNUM5O_ITEM, value);
    }

    /**
     * {@code CRDSTS5O PIC X(1)} [{@code COCRDLI.CPY:488}], {@code DFHMDF CRDSTS5} [{@code .bms:265}];
     * written by {@code MOVE WS-ROW-CARD-STATUS(5)} [{@code app/cbl/COCRDLIC.cbl:722}].
     *
     * @return the one-character card status of row 5
     */
    @JsonProperty("crdsts5")
    public String getCrdsts5o() {
        return field(CRDSTS5O_ITEM);
    }

    public void setCrdsts5o(String value) {
        setField(CRDSTS5O_ITEM, value);
    }

    /**
     * {@code CRDSEL6O PIC X(1)} [{@code COCRDLI.CPY:494}], {@code DFHMDF CRDSEL6} [{@code .bms:270}];
     * written by {@code MOVE WS-EDIT-SELECT(6)} [{@code app/cbl/COCRDLIC.cbl:729}].
     *
     * @return the one-character selection value for row 6
     */
    @JsonProperty("crdsel6")
    public String getCrdsel6o() {
        return field(CRDSEL6O_ITEM);
    }

    public void setCrdsel6o(String value) {
        setField(CRDSEL6O_ITEM, value);
    }

    /**
     * {@code CRDSTP6O PIC X(1)} [{@code COCRDLI.CPY:500}], {@code DFHMDF CRDSTP6} [{@code .bms:277}] - the
     * hidden selection-type field, second in the row.
     *
     * @return the one-character hidden selection type for row 6
     */
    @JsonProperty("crdstp6")
    public String getCrdstp6o() {
        return field(CRDSTP6O_ITEM);
    }

    public void setCrdstp6o(String value) {
        setField(CRDSTP6O_ITEM, value);
    }

    /**
     * {@code ACCTNO6O PIC X(11)} [{@code COCRDLI.CPY:506}], {@code DFHMDF ACCTNO6} [{@code .bms:282}];
     * written by {@code MOVE WS-ROW-ACCTNO(6)} [{@code app/cbl/COCRDLIC.cbl:730}].
     *
     * @return the eleven-character account number of row 6
     */
    @JsonProperty("acctno6")
    public String getAcctno6o() {
        return field(ACCTNO6O_ITEM);
    }

    public void setAcctno6o(String value) {
        setField(ACCTNO6O_ITEM, value);
    }

    /**
     * {@code CRDNUM6O PIC X(16)} [{@code COCRDLI.CPY:512}], {@code DFHMDF CRDNUM6} [{@code .bms:287}];
     * written by {@code MOVE WS-ROW-CARD-NUM(6)} [{@code app/cbl/COCRDLIC.cbl:731}].
     *
     * @return the sixteen-character card number of row 6, in the clear as the map carries it
     */
    @JsonProperty("crdnum6")
    public String getCrdnum6o() {
        return field(CRDNUM6O_ITEM);
    }

    public void setCrdnum6o(String value) {
        setField(CRDNUM6O_ITEM, value);
    }

    /**
     * {@code CRDSTS6O PIC X(1)} [{@code COCRDLI.CPY:518}], {@code DFHMDF CRDSTS6} [{@code .bms:292}];
     * written by {@code MOVE WS-ROW-CARD-STATUS(6)} [{@code app/cbl/COCRDLIC.cbl:732}].
     *
     * @return the one-character card status of row 6
     */
    @JsonProperty("crdsts6")
    public String getCrdsts6o() {
        return field(CRDSTS6O_ITEM);
    }

    public void setCrdsts6o(String value) {
        setField(CRDSTS6O_ITEM, value);
    }

    /**
     * {@code CRDSEL7O PIC X(1)} [{@code COCRDLI.CPY:524}], {@code DFHMDF CRDSEL7} [{@code .bms:297}];
     * written by {@code MOVE WS-EDIT-SELECT(7)} [{@code app/cbl/COCRDLIC.cbl:738}].
     *
     * @return the one-character selection value for row 7, the last row
     */
    @JsonProperty("crdsel7")
    public String getCrdsel7o() {
        return field(CRDSEL7O_ITEM);
    }

    public void setCrdsel7o(String value) {
        setField(CRDSEL7O_ITEM, value);
    }

    /**
     * {@code CRDSTP7O PIC X(1)} [{@code COCRDLI.CPY:530}], {@code DFHMDF CRDSTP7} [{@code .bms:304}] - the
     * hidden selection-type field, second in the row.
     *
     * @return the one-character hidden selection type for row 7
     */
    @JsonProperty("crdstp7")
    public String getCrdstp7o() {
        return field(CRDSTP7O_ITEM);
    }

    public void setCrdstp7o(String value) {
        setField(CRDSTP7O_ITEM, value);
    }

    /**
     * {@code ACCTNO7O PIC X(11)} [{@code COCRDLI.CPY:536}], {@code DFHMDF ACCTNO7} [{@code .bms:309}];
     * written by {@code MOVE WS-ROW-ACCTNO(7)} [{@code app/cbl/COCRDLIC.cbl:739}].
     *
     * @return the eleven-character account number of row 7
     */
    @JsonProperty("acctno7")
    public String getAcctno7o() {
        return field(ACCTNO7O_ITEM);
    }

    public void setAcctno7o(String value) {
        setField(ACCTNO7O_ITEM, value);
    }

    /**
     * {@code CRDNUM7O PIC X(16)} [{@code COCRDLI.CPY:542}], {@code DFHMDF CRDNUM7} [{@code .bms:314}];
     * written by {@code MOVE WS-ROW-CARD-NUM(7)} [{@code app/cbl/COCRDLIC.cbl:740}].
     *
     * @return the sixteen-character card number of row 7, in the clear as the map carries it
     */
    @JsonProperty("crdnum7")
    public String getCrdnum7o() {
        return field(CRDNUM7O_ITEM);
    }

    public void setCrdnum7o(String value) {
        setField(CRDNUM7O_ITEM, value);
    }

    /**
     * {@code CRDSTS7O PIC X(1)} [{@code COCRDLI.CPY:548}], {@code DFHMDF CRDSTS7} [{@code .bms:319}];
     * written by {@code MOVE WS-ROW-CARD-STATUS(7)} [{@code app/cbl/COCRDLIC.cbl:741}].
     *
     * @return the one-character card status of row 7
     */
    @JsonProperty("crdsts7")
    public String getCrdsts7o() {
        return field(CRDSTS7O_ITEM);
    }

    public void setCrdsts7o(String value) {
        setField(CRDSTS7O_ITEM, value);
    }

    /**
     * {@code INFOMSGO PIC X(45)} [{@code COCRDLI.CPY:554}],
     * {@code DFHMDF INFOMSG ATTRB=(PROT), COLOR=NEUTRAL, HILIGHT=OFF, LENGTH=45, POS=(20,19)}
     * [{@code .bms:324-328}].
     *
     * @return the forty-five-character information line, untrimmed
     */
    @JsonProperty("infomsg")
    public String getInfomsgo() {
        return field(INFOMSGO_ITEM);
    }

    public void setInfomsgo(String value) {
        setField(INFOMSGO_ITEM, value);
    }

    /**
     * {@code ERRMSGO PIC X(78)} [{@code COCRDLI.CPY:560}],
     * {@code DFHMDF ERRMSG ATTRB=(ASKIP,BRT,FSET), COLOR=RED, LENGTH=78, POS=(23,1)}
     * [{@code .bms:331-334}].
     *
     * @return the seventy-eight-character error line, untrimmed
     */
    @JsonProperty("errmsg")
    public String getErrmsgo() {
        return field(ERRMSGO_ITEM);
    }

    public void setErrmsgo(String value) {
        setField(ERRMSGO_ITEM, value);
    }

    private static final List<String> CRDSEL_ITEMS = List.of(CRDSEL1O_ITEM, CRDSEL2O_ITEM,
            CRDSEL3O_ITEM, CRDSEL4O_ITEM, CRDSEL5O_ITEM, CRDSEL6O_ITEM, CRDSEL7O_ITEM);

    private static final List<String> CRDSTP_ITEMS = List.of(CRDSTP2O_ITEM, CRDSTP3O_ITEM,
            CRDSTP4O_ITEM, CRDSTP5O_ITEM, CRDSTP6O_ITEM, CRDSTP7O_ITEM);

    private static final List<String> ACCTNO_ITEMS = List.of(ACCTNO1O_ITEM, ACCTNO2O_ITEM,
            ACCTNO3O_ITEM, ACCTNO4O_ITEM, ACCTNO5O_ITEM, ACCTNO6O_ITEM, ACCTNO7O_ITEM);

    private static final List<String> CRDNUM_ITEMS = List.of(CRDNUM1O_ITEM, CRDNUM2O_ITEM,
            CRDNUM3O_ITEM, CRDNUM4O_ITEM, CRDNUM5O_ITEM, CRDNUM6O_ITEM, CRDNUM7O_ITEM);

    private static final List<String> CRDSTS_ITEMS = List.of(CRDSTS1O_ITEM, CRDSTS2O_ITEM,
            CRDSTS3O_ITEM, CRDSTS4O_ITEM, CRDSTS5O_ITEM, CRDSTS6O_ITEM, CRDSTS7O_ITEM);

    private static final String REDACTED = "[REDACTED]";

    private static final Set<String> REDACTED_ITEMS = buildRedactedItems();

    private static Set<String> buildRedactedItems() {
        Set<String> items = new LinkedHashSet<>();
        items.add(ACCTSIDO_ITEM);
        items.add(CARDSIDO_ITEM);
        items.addAll(ACCTNO_ITEMS);
        items.addAll(CRDNUM_ITEMS);
        return Set.copyOf(items);
    }

    /**
     * The first COBOL subscript of every seven-element structure on this screen.
     */
    public static final int FIRST_ROW = 1;

    /**
     * The last COBOL subscript of every seven-element structure on this screen: {@value #ROW_COUNT}.
     */
    public static final int LAST_ROW = ROW_COUNT;

    /**
     * Converts a COBOL subscript into the Java index of the same element.
     *
     * @param cobolSubscript a subscript in {@value #FIRST_ROW}{@code ..}{@link #LAST_ROW} inclusive
     * @return the corresponding Java index in {@code 0..}{@value #ROW_COUNT}{@code -1}
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is outside the declared {@code OCCURS}
     *     range
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
     * @return the corresponding COBOL subscript in {@value #FIRST_ROW}{@code ..}{@link #LAST_ROW}
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
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@link #LAST_ROW}
     * @return {@value #ROW_1_FIELD_COUNT} or {@value #ROW_N_FIELD_COUNT}
     * @throws IndexOutOfBoundsException if {@code cobolRow} is outside the declared range
     */
    public static int rowFieldCount(int cobolRow) {
        toJavaIndex(cobolRow);
        return cobolRow == FIRST_ROW ? ROW_1_FIELD_COUNT : ROW_N_FIELD_COUNT;
    }

    public static boolean hasCrdstpItem(int cobolRow) {
        toJavaIndex(cobolRow);
        return cobolRow != FIRST_ROW;
    }

    public static String crdselItem(int cobolRow) {
        return CRDSEL_ITEMS.get(toJavaIndex(cobolRow));
    }

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

    public static String acctnoItem(int cobolRow) {
        return ACCTNO_ITEMS.get(toJavaIndex(cobolRow));
    }

    public static String crdnumItem(int cobolRow) {
        return CRDNUM_ITEMS.get(toJavaIndex(cobolRow));
    }

    public static String crdstsItem(int cobolRow) {
        return CRDSTS_ITEMS.get(toJavaIndex(cobolRow));
    }

    /**
     * One row of the browse result, as the three payload members currently hold it.
     *
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@link #LAST_ROW} - the subscript
     *     {@code WS-SCREEN-ROWS(n)} uses, not a Java index
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
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@link #LAST_ROW}
     * @param row the row to write; never {@code null}
     * @throws NullPointerException if {@code row} is {@code null}
     * @throws IndexOutOfBoundsException if {@code cobolRow} is outside the declared range
     */
    public void setScreenRow(int cobolRow, ScreenRow row) {
        Objects.requireNonNull(row, "A row is required; to blank a row move ScreenRow.lowValues()");
        setField(acctnoItem(cobolRow), row.rowAcctno());
        setField(crdnumItem(cobolRow), row.rowCardNum());
        setField(crdstsItem(cobolRow), row.rowCardStatus());
    }

    @JsonIgnore
    public List<ScreenRow> screenRows() {
        List<ScreenRow> rows = new ArrayList<>(ROW_COUNT);
        for (int cobolRow = FIRST_ROW; cobolRow <= LAST_ROW; cobolRow++) {
            rows.add(screenRow(cobolRow));
        }
        return Collections.unmodifiableList(rows);
    }

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
     * @return exactly {@link #SCREEN_ARRAY_LENGTH} characters, seven rows of {@link #SCREEN_ROW_LENGTH}
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
     * @param image exactly {@link #SCREEN_ARRAY_LENGTH} characters
     * @throws NullPointerException if {@code image} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly {@link #SCREEN_ARRAY_LENGTH}
     *     characters
     */
    public void setAllRowsImage(String image) {
        requireExactWidth(image, SCREEN_ARRAY_LENGTH, "WS-ALL-ROWS");
        for (int cobolRow = FIRST_ROW; cobolRow <= LAST_ROW; cobolRow++) {
            int start = toJavaIndex(cobolRow) * SCREEN_ROW_LENGTH;
            setScreenRow(cobolRow, ScreenRow.fromImage(image.substring(start,
                    start + SCREEN_ROW_LENGTH)));
        }
    }

    /**
     * {@code WS-EDIT-SELECT(n)} - one row's selection character.
     *
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@link #LAST_ROW}
     * @return exactly one character; {@link #LOW_VALUE} on a freshly constructed response
     * @throws IndexOutOfBoundsException if {@code cobolRow} is outside the declared range
     */
    @JsonIgnore
    public String editSelect(int cobolRow) {
        return field(crdselItem(cobolRow));
    }

    public void setEditSelect(int cobolRow, String value) {
        setField(crdselItem(cobolRow), value);
    }

    /**
     * {@code WS-EDIT-SELECT-FLAGS PIC X(7)} - the seven selection characters as one group image, the form
     * {@code INSPECT WS-EDIT-SELECT-FLAGS TALLYING ... FOR ALL 'S' ALL 'U'}
     * [{@code app/cbl/COCRDLIC.cbl:1079-1082}] counts over.
     *
     * @return exactly {@link #SELECT_FLAGS_LENGTH} characters; seven {@link #LOW_VALUE} on a freshly
     *     constructed response
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
     * @param flags exactly {@link #SELECT_FLAGS_LENGTH} characters
     * @throws NullPointerException if {@code flags} is {@code null}
     * @throws IllegalArgumentException if {@code flags} is not exactly {@link #SELECT_FLAGS_LENGTH}
     *     characters
     */
    public void setEditSelectFlags(String flags) {
        requireExactWidth(flags, SELECT_FLAGS_LENGTH, "WS-EDIT-SELECT-FLAGS");
        for (int cobolRow = FIRST_ROW; cobolRow <= LAST_ROW; cobolRow++) {
            int javaIndex = toJavaIndex(cobolRow);
            setEditSelect(cobolRow, flags.substring(javaIndex, javaIndex + CRDSEL_LENGTH));
        }
    }

    /**
     * {@code 88 SELECT-OK VALUES 'S', 'U'} [{@code app/cbl/COCRDLIC.cbl:77}] - the row requests an action,
     * whichever one.
     *
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@link #LAST_ROW}
     * @return {@code true} when the selection character is {@code 'S'} or {@code 'U'}
     * @throws IndexOutOfBoundsException if {@code cobolRow} is outside the declared range
     */
    public boolean isSelectOk(int cobolRow) {
        String value = editSelect(cobolRow);
        return SELECT_VIEW.equals(value) || SELECT_UPDATE.equals(value);
    }

    /**
     * {@code 88 VIEW-REQUESTED-ON VALUE 'S'} [{@code app/cbl/COCRDLIC.cbl:78}] - the row requests the card
     * detail view, which drives the {@code XCTL} at L538.
     *
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@link #LAST_ROW}
     * @return {@code true} when the selection character is {@code 'S'}
     * @throws IndexOutOfBoundsException if {@code cobolRow} is outside the declared range
     */
    public boolean isViewRequestedOn(int cobolRow) {
        return SELECT_VIEW.equals(editSelect(cobolRow));
    }

    /**
     * {@code 88 UPDATE-REQUESTED-ON VALUE 'U'} [{@code app/cbl/COCRDLIC.cbl:79}] - the row requests the
     * card update screen, which drives the {@code XCTL} at L566.
     *
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@link #LAST_ROW}
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
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@link #LAST_ROW}
     * @return {@code true} when the selection character is a space or {@code LOW-VALUES}
     * @throws IndexOutOfBoundsException if {@code cobolRow} is outside the declared range
     */
    public boolean isSelectBlank(int cobolRow) {
        String value = editSelect(cobolRow);
        return SPACE.equals(value) || LOW_VALUE.equals(value);
    }

    // Metadata: addressable, never on the wire. 05 WS-EDIT-SELECT-ERROR-FLAGS PIC X(7). Read by
    // 1250-SETUP-ARRAY-ATTRIBS at L755, 768, 780, 792, 803, 815 and 826 - one test per row.

    /**
     * {@code WS-ROW-CRDSELECT-ERROR(n)} - one row's error signal.
     *
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@link #LAST_ROW}
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
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@link #LAST_ROW}
     * @param value exactly one character; {@link #ROW_SELECT_ERROR} marks the row in error
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is not exactly one character
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
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@link #LAST_ROW}
     * @return {@code true} only when the signal is {@code '1'} - a space and {@code LOW-VALUES} are both
     *     "no error"
     * @throws IndexOutOfBoundsException if {@code cobolRow} is outside the declared range
     */
    public boolean isWsRowSelectError(int cobolRow) {
        return ROW_SELECT_ERROR.equals(wsRowCrdselectError(cobolRow));
    }

    /**
     * {@code WS-EDIT-SELECT-ERROR-FLAGS PIC X(7)} - the group image, the form the
     * {@code MOVE}/{@code INSPECT REPLACING} pair at {@code app/cbl/COCRDLIC.cbl:1088-1093} rewrites whole.
     *
     * @return exactly {@link #SELECT_FLAGS_LENGTH} characters
     */
    @JsonIgnore
    public String editSelectErrorFlags() {
        return editSelectErrorFlags;
    }

    /**
     * Writes the whole {@code WS-EDIT-SELECT-ERROR-FLAGS} group.
     *
     * @param flags exactly {@link #SELECT_FLAGS_LENGTH} characters
     * @throws NullPointerException if {@code flags} is {@code null}
     * @throws IllegalArgumentException if {@code flags} is not exactly {@link #SELECT_FLAGS_LENGTH}
     *     characters
     */
    public void setEditSelectErrorFlags(String flags) {
        requireExactWidth(flags, SELECT_FLAGS_LENGTH, "WS-EDIT-SELECT-ERROR-FLAGS");
        this.editSelectErrorFlags = flags;
    }

    /**
     * The live attribute quad of one screen field - the object a {@code MOVE ... TO xxxC OF CCRDLIAO}
     * writes into.
     *
     * @param screenFieldPrefix a {@code DFHMDF} label, for example {@code "ACCTSID"} or {@code "CRDSEL1"}
     * @return that field's quad; never {@code null}
     * @throws NullPointerException if {@code screenFieldPrefix} is {@code null}
     * @throws IllegalArgumentException if the label names no field of this map - {@code "CRDSTP1"} and
     *     {@code "FKEYS"} are both rejected, because neither exists here
     */
    @JsonIgnore
    public FieldAttributes fieldAttributes(String screenFieldPrefix) {
        return attributes.get(mapFieldByPrefix(screenFieldPrefix).screenFieldPrefix());
    }

    /**
     * A snapshot of all 45 quads, keyed by {@code DFHMDF} label in copybook order, for diagnostics and for
     * a parity report.
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
     * The {@code DFHMDF} label the cursor was last aimed at on this invocation, or {@code null} when it was
     * aimed nowhere.
     *
     * @return the label, or {@code null}
     */
    @JsonIgnore
    public String getCursorField() {
        return cursorField;
    }

    /**
     * Records where {@code MOVE -1 TO xxxL OF CCRDLIAI} aimed the cursor, so {@link #screenMetadata()} can
     * publish it.
     *
     * @param cursorField the {@code DFHMDF} label, or {@code null} for no request
     */
    public void setCursorField(String cursorField) {
        this.cursorField = cursorField;
    }

    /**
     * This screen's presentation metadata, projected into the shared envelope every online response
     * publishes: the 45 attribute quads keyed by {@code DFHMDF} label in copybook order, the colour of the
     * error line, and the field the cursor was aimed at.
     *
     * @return the metadata; never {@code null}
     */
    @JsonIgnore
    public ScreenMetadata screenMetadata() {
        return screenMetadata(null);
    }

    /**
     * The same metadata with each field's basic attribute byte taken from the input area, which is where
     * {@code 1000-SEND-MAP}'s attribute paragraph actually writes it.
     *
     * @param inputArea the input map area {@code CCRDLIAI} as the program left it, or {@code null}
     * @return the metadata; never {@code null}
     */
    @JsonIgnore
    public ScreenMetadata screenMetadata(CardListRequest inputArea) {
        Map<String, ScreenMetadata.FieldMetadata> quads = new LinkedHashMap<>();
        for (Map.Entry<String, FieldAttributes> entry : attributes.entrySet()) {
            FieldAttributes quad = entry.getValue();
            quads.put(entry.getKey(), ScreenMetadata.FieldMetadata.of(quad.colour(),
                    basicAttributeOf(inputArea, entry.getKey(), quad),
                    quad.highlight(),
                    quad.validn()));
        }
        FieldAttributes errorLine = attributes.get(mapField(ERRMSGO_ITEM).screenFieldPrefix());
        return ScreenMetadata.of(cursorField, errorLine.colour(), false, quads);
    }

    private static byte basicAttributeOf(CardListRequest inputArea, String label,
            FieldAttributes quad) {
        if (inputArea == null) {
            return quad.ps();
        }
        return inputArea.fieldMetadataOf(label)
                .map(metadata -> (byte) (metadata.attributeByte() & 0xFF))
                .orElseGet(quad::ps);
    }

    /**
     * Applies a {@link FieldAttributeSetter} decision to this response - the {@code app/cpy/CSSETATY.cpy}
     * moves, performed.
     *
     * @param highlight the decision to apply; never {@code null}
     * @throws NullPointerException if {@code highlight} is {@code null}
     * @throws IllegalArgumentException if the decision assigns something but names no field, or names a
     *     field this map does not have
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
     * @param state the field's validation outcome, the {@code (TESTVAR1)} analogue
     * @param reenter {@code true} when {@code CDEMO-PGM-REENTER} holds, that is when
     *     {@code CDEMO-PGM-CONTEXT} is {@value NavigationContext#PGM_CONTEXT_REENTER}
     * @param screenFieldPrefix the {@code DFHMDF} label to highlight
     * @return the decision that was applied, for assertion and for logging; never {@code null}
     * @throws NullPointerException if {@code state} or {@code screenFieldPrefix} is {@code null}
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
     * <p>Two differences from {@code CSSETATY} are real and are preserved rather than smoothed over: There
     * is no {@code CDEMO-PGM-REENTER} guard.
     *
     * @param cobolRow a row subscript in {@value #FIRST_ROW}{@code ..}{@link #LAST_ROW}
     * @param protectSelectRows {@code true} when {@code FLG-PROTECT-SELECT-ROWS-YES} holds
     * @return {@code true} when the row's colour item was reddened, {@code false} when the row was left
     *     untouched
     * @throws IndexOutOfBoundsException if {@code cobolRow} is outside the declared range
     */
    public boolean applyRowSelectHighlight(int cobolRow, boolean protectSelectRows) {
        String selectionItem = crdselItem(cobolRow);
        // Testing the map's own row is exact rather than approximate: MOVE LOW-VALUES TO CCRDLIAO [L643]
        // sets the row to LOW-VALUES, and 1200-SCREEN-ARRAY-INIT overwrites it if and only if
        // WS-EACH-CARD(n) is not LOW-VALUES - so the map row is LOW-VALUES exactly when WS-EACH-CARD(n) is.
        if (protectSelectRows || screenRow(cobolRow).isLowValues()) {
            return false;
        }
        if (!isWsRowSelectError(cobolRow)) {
            return false;
        }
        attributes.get(mapField(selectionItem).screenFieldPrefix()).setColour(BmsAttributes.DFHRED);
        if (cobolRow == FIRST_ROW && isSelectBlank(cobolRow)) {
            setField(selectionItem, FieldAttributeSetter.ASTERISK);
        }
        return true;
    }

    /**
     * {@code CCARD-NEXT-PROG} - the program the client should call next.
     *
     * @return the eight-character program name, untrimmed; spaces when no transfer is pending
     */
    public String getNextProgram() {
        return nextProgram;
    }

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
     * Sets the next mapset as an opaque token, at {@link #NEXT_MAPSET_LENGTH} characters.
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
     * Sets the next map as an opaque token, at {@link #NEXT_MAP_LENGTH} characters.
     *
     * @param value the map name; use {@link #LIT_THISMAP} to stay on this screen
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void setNextMap(String value) {
        Objects.requireNonNull(value, "A next-map token is required; to clear it move SPACES");
        this.nextMap = PIC_X_MOVE_CODEC.movePicX(value, NEXT_MAP_LENGTH);
    }

    /**
     * Sets all three navigation fields in one step, as each {@code XCTL} site's surrounding {@code MOVE}s
     * do.
     *
     * @param program the target program, for example {@link #LIT_MENUPGM}
     * @param mapset the target mapset
     * @param map the target map
     * @throws NullPointerException if any argument is {@code null}
     */
    public void setNextTarget(String program, String mapset, String map) {
        setNextProgram(program);
        setNextMapset(mapset);
        setNextMap(map);
    }

    public PageCursor getPageCursor() {
        return pageCursor;
    }

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

    public void setNavigationContext(NavigationContext value) {
        this.navigationContext = Objects.requireNonNull(value, "A CARDDEMO-COMMAREA is required; the "
                + "empty state is NavigationContext.empty()");
    }

    /**
     * Moves the two screen titles from {@link ScreenTitles} -
     * {@code MOVE CCDA-TITLE01 TO TITLE01O OF CCRDLIAO} and
     * {@code MOVE CCDA-TITLE02 TO TITLE02O OF CCRDLIAO} [{@code app/cbl/COCRDLIC.cbl:647-648}].
     */
    public void applyScreenTitles() {
        setTitle01o(ScreenTitles.CCDA_TITLE01);
        setTitle02o(ScreenTitles.CCDA_TITLE02);
    }

    /**
     * Moves this program's own identity into the header - {@code MOVE LIT-THISTRANID TO TRNNAMEO} and
     * {@code MOVE LIT-THISPGM TO PGMNAMEO} [{@code app/cbl/COCRDLIC.cbl:649-650}].
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
     * @param dateHeader the captured date and time; never {@code null}
     * @throws NullPointerException if {@code dateHeader} is {@code null}
     */
    public void applyDateHeader(DateHeader dateHeader) {
        Objects.requireNonNull(dateHeader, "A DateHeader is required to fill CURDATEO and CURTIMEO");
        setCurdateo(dateHeader.wsCurdateMmDdYy());
        setCurtimeo(dateHeader.wsCurtimeHhMmSs());
    }

    /**
     * Moves a page number into {@code PAGENOO} the way {@code MOVE WS-CA-SCREEN-NUM TO PAGENOO OF CCRDLIAO}
     * [{@code app/cbl/COCRDLIC.cbl:667}] does.
     *
     * <p>Applying the {@code PIC 9} rule here instead would produce {@code "001"} and be wrong on the
     * screen and in a parity diff.
     *
     * @param screenNum the page number, {@code 0..}{@value PageCursor#MAX_SINGLE_DIGIT}, as
     *     {@code PIC 9(1)} can hold
     * @throws IllegalArgumentException if {@code screenNum} is outside the range a {@code PIC 9(1)} item
     *     can hold
     */
    public void setPagenooFromScreenNum(int screenNum) {
        requireUnsignedRange(screenNum, PageCursor.MAX_SINGLE_DIGIT, "WS-CA-SCREEN-NUM",
                CardListRequest.SCREEN_NUM_LENGTH);
        setPagenoo(Integer.toString(screenNum));
    }

    /**
     * Moves the current cursor's page number into {@code PAGENOO}, which is what {@code 1100-SCREEN-INIT}
     * does with the cursor it was handed.
     */
    public void applyPageNumberFromCursor() {
        setPagenooFromScreenNum(pageCursor.screenNum());
    }

    /**
     * Moves {@code CCDA-MSG-THANK-YOU} into {@code INFOMSGO}.
     *
     * <p>The message is {@value SystemMessages#MESSAGE_LENGTH} characters and {@code INFOMSGO} is
     * {@code PIC X(45)}, so the {@code PIC X} rule truncates the last five characters on the right -
     * exactly what a COBOL {@code MOVE} of a {@code PIC X(50)} item into a {@code PIC X(45)} item does.
     */
    public void setInfomsgoThankYou() {
        setInfomsgo(SystemMessages.CCDA_MSG_THANK_YOU);
    }

    /**
     * Moves {@code CCDA-MSG-INVALID-KEY} into {@code ERRMSGO}.
     */
    public void setErrmsgoInvalidKey() {
        setErrmsgo(SystemMessages.CCDA_MSG_INVALID_KEY);
    }

    /**
     * Reproduces {@code 1100-SCREEN-INIT} [{@code app/cbl/COCRDLIC.cbl:642-672}] in its source order.
     *
     * @param dateHeader the captured date and time; never {@code null}
     * @param infoMessage the {@code WS-INFO-MSG} text to move into {@code INFOMSGO}; pass
     *     {@link CardScreenState#spaces(int)} for the no-message state that
     *     {@code SET WS-NO-INFO-MESSAGE TO TRUE} [L669] produces
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
     * Reproduces the {@code 1400-SETUP-MESSAGE} tail [{@code app/cbl/COCRDLIC.cbl:924-930}]: the error line
     * is written unconditionally, and when there is an information message to show it is written and its
     * colour item is set to {@link BmsAttributes#DFHNEUTR}.
     *
     * @param errorMessage the {@code WS-ERROR-MSG} text, moved into {@code ERRMSGO} [L924]; pass spaces
     *     when there is no error
     * @param infoMessage the {@code WS-INFO-MSG} text, moved into {@code INFOMSGO} [L928], or {@code null}
     *     when {@code WS-NO-INFO-MESSAGE} holds and the guarded block at L926-930 is skipped entirely
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

    // The form EXEC CICS SEND MAP(LIT-THISMAP) MAPSET(LIT-THISMAPSET) FROM(CCRDLIAO)
    // [app/cbl/COCRDLIC.cbl:938-941] transmits, and the form a parity test compares byte for byte.

    /**
     * Renders this response as the {@link #GROUP_LENGTH}-byte {@code CCRDLIAO} image.
     *
     * <p>The {@code TIOAPFX} {@code FILLER X(12)} and the 45 {@code FILLER X(3)} spans are therefore
     * {@code LOW-VALUES}, which is what the program leaves them as - they are never omitted, because
     * omitting a {@code FILLER} would move every offset after it.
     *
     * @param charset the code page to encode the text items into, named explicitly by the caller
     * @return a fresh array of exactly {@link #GROUP_LENGTH} bytes
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a single-byte code page for the space and
     *     the digits
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
     * Writes this response into an existing record area of exactly {@link #GROUP_LENGTH} bytes, using that
     * area's own code page.
     *
     * <p>The area is wiped to {@code LOW-VALUES} first, exactly as L643 does, so the result does not depend
     * on what the caller left in it.
     *
     * @param record a record area whose declared length is {@link #GROUP_LENGTH}
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalArgumentException if the area's declared length is not {@link #GROUP_LENGTH}
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
     * Rebuilds the map half of a response from a {@link #GROUP_LENGTH}-byte {@code CCRDLIAO} image.
     *
     * @param bytes exactly {@link #GROUP_LENGTH} bytes
     * @param charset the code page the text items are encoded in, named explicitly by the caller
     * @return a response carrying the image's 45 items and 45 quads; never {@code null}
     * @throws NullPointerException if {@code bytes} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code bytes.length} is not {@link #GROUP_LENGTH}
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
     * @param record a record area whose declared length is {@link #GROUP_LENGTH}
     * @return a response carrying the area's 45 items and 45 quads; never {@code null}
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalArgumentException if the area's declared length is not {@link #GROUP_LENGTH}
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

    /**
     * Equal when every payload item, every attribute quad, the row error flags, the navigation triple, the
     * cursor, the work area and the commarea are equal.
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
     * A diagnostic rendering of all 45 payload items, each delimited so its declared width and any padding
     * are visible, followed by the navigation triple and the cursor.
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

    private static final byte LOW_VALUE_BYTE = 0x00;

    /**
     * Rejects a value that is not exactly the width its {@code PICTURE} declares.
     *
     * @param value the value being checked
     * @param width the declared width
     * @param cobolName the copybook name of the item, for the failure message
     * @throws NullPointerException if {@code value} is {@code null}; {@code null} is not a COBOL state - a
     *     field is spaces, or {@code LOW-VALUES}, or it holds data
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
     * @param value the value being checked
     * @param maximum the largest value the item can hold - {@code n} nines
     * @param cobolName the copybook name of the item, for the failure message
     * @param digits {@code n}, for the failure message
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

    private static void requireGroupWidth(int recordLength) {
        if (recordLength != GROUP_LENGTH) {
            throw new IllegalArgumentException("A CCRDLIAO area is " + GROUP_LENGTH + " byte(s) - "
                    + TIOAPFX_LENGTH + " of TIOAPFX prefix, " + PAYLOAD_FIELD_COUNT + " x "
                    + FIELD_PREFIX_LENGTH + " of per-field prefix and " + PAYLOAD_LENGTH + " of data - "
                    + "but " + recordLength + " byte(s) were supplied");
        }
    }

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
