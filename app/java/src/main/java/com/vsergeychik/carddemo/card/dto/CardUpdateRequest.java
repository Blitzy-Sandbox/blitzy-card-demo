package com.vsergeychik.carddemo.card.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.vsergeychik.carddemo.common.ConversationStateSeal;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.ResponseOnlyMembers;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.constraints.Size;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Inbound REST payload for {@code PUT /api/cards/&#123;cardNum&#125;} - the request half of CICS
 * transaction {@code CCUP}, whose backing program is {@code app/cbl/COCRDUPC.cbl} (1,560 lines).
 *
 * <p>A {@code null} is normalised to that field's declared width in spaces, because COBOL has no
 * {@code null} and an untransmitted field reads as {@code SPACES} or {@code LOW-VALUES} - which is exactly
 * what the COBOL edits test for.
 */
@JsonIgnoreProperties({
        ResponseOnlyMembers.NEXT_PROGRAM,
        ResponseOnlyMembers.NEXT_MAPSET,
        ResponseOnlyMembers.NEXT_MAP,
        ResponseOnlyMembers.SCREEN_METADATA})
public final class CardUpdateRequest {
    public static final String TRANSACTION_ID = "CCUP";

    /**
     * COBOL program this type is translated from: {@code app/cbl/COCRDUPC.cbl}.
     */
    public static final String PROGRAM_NAME = "COCRDUPC";

    /**
     * BMS mapset name, from {@code COCRDUP DFHMSD} at {@code app/bms/COCRDUP.bms:20}.
     */
    public static final String MAPSET_NAME = "COCRDUP";

    /**
     * BMS map name, from {@code CCRDUPA DFHMDI} at {@code app/bms/COCRDUP.bms:25}.
     */
    public static final String MAP_NAME = "CCRDUPA";

    /**
     * Row count from {@code SIZE=(24,80)} on {@code CCRDUPA DFHMDI}.
     */
    public static final int SCREEN_ROWS = 24;

    /**
     * Column count from {@code SIZE=(24,80)} on {@code CCRDUPA DFHMDI}.
     */
    public static final int SCREEN_COLUMNS = 80;

    /**
     * {@code DFHMDF} label of field 1, at {@code app/bms/COCRDUP.bms} {@code POS=(1,7)}.
     */
    public static final String TRNNAME_FIELD = "TRNNAME";

    /**
     * {@code DFHMDF} label of field 2, at {@code POS=(1,21)}.
     */
    public static final String TITLE01_FIELD = "TITLE01";

    /**
     * {@code DFHMDF} label of field 3, at {@code POS=(1,71)}.
     */
    public static final String CURDATE_FIELD = "CURDATE";

    /**
     * {@code DFHMDF} label of field 4, at {@code POS=(2,7)}.
     */
    public static final String PGMNAME_FIELD = "PGMNAME";

    /**
     * {@code DFHMDF} label of field 5, at {@code POS=(2,21)}.
     */
    public static final String TITLE02_FIELD = "TITLE02";

    /**
     * {@code DFHMDF} label of field 6, at {@code POS=(2,71)}.
     */
    public static final String CURTIME_FIELD = "CURTIME";

    /**
     * {@code DFHMDF} label of field 7, at {@code POS=(7,45)}.
     */
    public static final String ACCTSID_FIELD = "ACCTSID";

    /**
     * {@code DFHMDF} label of field 8, at {@code POS=(8,45)}.
     */
    public static final String CARDSID_FIELD = "CARDSID";

    /**
     * {@code DFHMDF} label of field 9, at {@code POS=(11,25)}.
     */
    public static final String CRDNAME_FIELD = "CRDNAME";

    /**
     * {@code DFHMDF} label of field 10, at {@code POS=(13,25)}.
     */
    public static final String CRDSTCD_FIELD = "CRDSTCD";

    /**
     * {@code DFHMDF} label of field 11, at {@code POS=(15,25)}.
     */
    public static final String EXPMON_FIELD = "EXPMON";

    /**
     * {@code DFHMDF} label of field 12, at {@code POS=(15,30)}.
     */
    public static final String EXPYEAR_FIELD = "EXPYEAR";

    /**
     * {@code DFHMDF} label of field 13, at {@code POS=(15,36)}.
     */
    public static final String EXPDAY_FIELD = "EXPDAY";

    /**
     * {@code DFHMDF} label of field 14, at {@code POS=(20,25)}.
     */
    public static final String INFOMSG_FIELD = "INFOMSG";

    /**
     * {@code DFHMDF} label of field 15, at {@code POS=(23,1)}.
     */
    public static final String ERRMSG_FIELD = "ERRMSG";

    /**
     * {@code DFHMDF} label of field 16, at {@code POS=(24,1)} with {@code LENGTH=21}.
     */
    public static final String FKEYS_FIELD = "FKEYS";

    /**
     * {@code DFHMDF} label of field 17, at {@code POS=(24,23)} with {@code LENGTH=18}.
     */
    public static final String FKEYSC_FIELD = "FKEYSC";

    /**
     * {@code TRNNAMEI PIC X(4)}, {@code app/cpy-bms/COCRDUP.CPY:24}.
     */
    public static final int TRNNAME_LENGTH = 4;

    /**
     * {@code TITLE01I PIC X(40)}, {@code app/cpy-bms/COCRDUP.CPY:30}.
     */
    public static final int TITLE01_LENGTH = 40;

    /**
     * {@code CURDATEI PIC X(8)}, {@code app/cpy-bms/COCRDUP.CPY:36}.
     */
    public static final int CURDATE_LENGTH = 8;

    /**
     * {@code PGMNAMEI PIC X(8)}, {@code app/cpy-bms/COCRDUP.CPY:42}.
     */
    public static final int PGMNAME_LENGTH = 8;

    /**
     * {@code TITLE02I PIC X(40)}, {@code app/cpy-bms/COCRDUP.CPY:48}.
     */
    public static final int TITLE02_LENGTH = 40;

    /**
     * {@code CURTIMEI PIC X(8)}, {@code app/cpy-bms/COCRDUP.CPY:54}.
     */
    public static final int CURTIME_LENGTH = 8;

    /**
     * {@code ACCTSIDI PIC X(11)}, {@code app/cpy-bms/COCRDUP.CPY:60}.
     */
    public static final int ACCTSID_LENGTH = 11;

    /**
     * {@code CARDSIDI PIC X(16)}, {@code app/cpy-bms/COCRDUP.CPY:66}.
     */
    public static final int CARDSID_LENGTH = 16;

    /**
     * {@code CRDNAMEI PIC X(50)}, {@code app/cpy-bms/COCRDUP.CPY:72}.
     */
    public static final int CRDNAME_LENGTH = 50;

    /**
     * {@code CRDSTCDI PIC X(1)}, {@code app/cpy-bms/COCRDUP.CPY:78}.
     */
    public static final int CRDSTCD_LENGTH = 1;

    /**
     * {@code EXPMONI PIC X(2)}, {@code app/cpy-bms/COCRDUP.CPY:84}.
     */
    public static final int EXPMON_LENGTH = 2;

    /**
     * {@code EXPYEARI PIC X(4)}, {@code app/cpy-bms/COCRDUP.CPY:90}.
     */
    public static final int EXPYEAR_LENGTH = 4;

    /**
     * {@code EXPDAYI PIC X(2)}, {@code app/cpy-bms/COCRDUP.CPY:96}.
     */
    public static final int EXPDAY_LENGTH = 2;

    /**
     * {@code INFOMSGI PIC X(40)}, {@code app/cpy-bms/COCRDUP.CPY:102}. 40 here and in {@code COCRDSL}, but
     * 45 in {@code COCRDLI} - the widths are per-map and are not unified.
     */
    public static final int INFOMSG_LENGTH = 40;

    /**
     * {@code ERRMSGI PIC X(80)}, {@code app/cpy-bms/COCRDUP.CPY:108}. 80 here and in {@code COCRDSL}, but
     * 78 in {@code COCRDLI}.
     */
    public static final int ERRMSG_LENGTH = 80;

    /**
     * {@code FKEYSI PIC X(21)}, {@code app/cpy-bms/COCRDUP.CPY:114}. 21 here, 75 in {@code COCRDSL}, and
     * the field is absent from {@code COCRDLI} entirely.
     */
    public static final int FKEYS_LENGTH = 21;

    /**
     * {@code FKEYSCI PIC X(18)}, {@code app/cpy-bms/COCRDUP.CPY:120}.
     */
    public static final int FKEYSC_LENGTH = 18;

    /**
     * Bytes the {@code TIOAPFX=YES} prefix reserves at the head of the group: {@code 02 FILLER PIC X(12)}
     * at {@code app/cpy-bms/COCRDUP.CPY:18}.
     */
    public static final int TIOAPFX_LENGTH = 12;

    /**
     * Metadata bytes preceding every field's data: {@code xxxL COMP PIC S9(4)} contributes 2,
     * {@code xxxF PICTURE X} contributes 1, the {@code xxxA} overlay of {@code xxxF} contributes 0 because
     * it redefines that same byte, and {@code FILLER PICTURE X(4)} contributes 4.
     */
    public static final int FIELD_METADATA_LENGTH = 7;

    /**
     * Name-labelled {@code DFHMDF} entries, and therefore screen fields on this type.
     */
    public static final int NAMED_FIELD_COUNT = 17;

    /**
     * All {@code DFHMDF} entries in {@code app/bms/COCRDUP.bms}, named and unnamed.
     */
    public static final int TOTAL_DFHMDF_COUNT = 34;

    public static final int NAMED_FIELD_TOTAL_LENGTH = 353;

    /**
     * Total length of {@code 01 CCRDUPAI}: {@value #TIOAPFX_LENGTH} + {@value #NAMED_FIELD_COUNT} x
     * {@value #FIELD_METADATA_LENGTH} + {@value #NAMED_FIELD_TOTAL_LENGTH} = {@code 12 + 119 + 353 = 484}.
     *
     * <p>Recorded as a constant rather than as a {@link RecordLayout}, deliberately: the
     * {@code xxxL COMP PIC S9(4)} items are 2-byte binary halfwords, and {@link FixedWidthCodec} handles
     * zoned {@code DISPLAY} data only.
     */
    public static final int GROUP_LENGTH = 484;

    /**
     * The {@value #NAMED_FIELD_COUNT} {@code DFHMDF} labels, in declaration order.
     */
    public static final List<String> FIELD_NAMES = List.of(TRNNAME_FIELD,
            TITLE01_FIELD,
            CURDATE_FIELD,
            PGMNAME_FIELD,
            TITLE02_FIELD,
            CURTIME_FIELD,
            ACCTSID_FIELD,
            CARDSID_FIELD,
            CRDNAME_FIELD,
            CRDSTCD_FIELD,
            EXPMON_FIELD,
            EXPYEAR_FIELD,
            EXPDAY_FIELD,
            INFOMSG_FIELD,
            ERRMSG_FIELD,
            FKEYS_FIELD,
            FKEYSC_FIELD);

    /**
     * Each {@code DFHMDF} label mapped to its {@code xxxI} declared width, in declaration order.
     */
    public static final Map<String, Integer> FIELD_LENGTHS = buildFieldLengths();

    private static final String PIC_X_PAD = " ";

    private static final String TO_STRING_SEPARATOR = ", ";

    // Each is the projection of exactly one xxxI item, each carries @Size with the max taken from that
    // item's PICTURE clause and nothing else, and each is stored exactly as supplied so an over-wide value
    // is reported rather than truncated.

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

    @Size(max = ACCTSID_LENGTH)
    private String acctsid;

    @Size(max = CARDSID_LENGTH)
    private String cardsid;

    @Size(max = CRDNAME_LENGTH)
    private String crdname;

    @Size(max = CRDSTCD_LENGTH)
    private String crdstcd;

    @Size(max = EXPMON_LENGTH)
    private String expmon;

    @Size(max = EXPYEAR_LENGTH)
    private String expyear;

    @Size(max = EXPDAY_LENGTH)
    private String expday;

    @Size(max = INFOMSG_LENGTH)
    private String infomsg;

    @Size(max = ERRMSG_LENGTH)
    private String errmsg;

    @Size(max = FKEYS_LENGTH)
    private String fkeys;

    @Size(max = FKEYSC_LENGTH)
    private String fkeysc;

    private CommArea commArea;

    /**
     * The sealed form of {@link #commArea}, which is the only form that crosses the wire.
     *
     * <p>{@code WS-THIS-PROGCOMMAREA} is not screen data. It is the program's own storage, and it carries
     * two things a caller must not be able to write. Its first byte, {@code CCUP-CHANGE-ACTION}, records
     * that this screen's four edits already passed - {@code app/cbl/COCRDUPC.cbl:685-693} skips every one
     * of them when it reads {@code CCUP-CHANGES-OK-NOT-CONFIRMED}, and {@code :988-1001} then writes on
     * {@code PF5}. And {@code CCUP-OLD-DETAILS} and {@code CARD-UPDATE-RECORD} carry
     * {@code CARD-CVV-CD}, which appears on <strong>no</strong> {@code DFHMDF} field of
     * {@code app/bms/COCRDUP.bms} and which a terminal is therefore never shown.
     *
     * <p>On a 3270 both are safe, because CICS passes the area and the terminal never sees it. Published as
     * structured JSON neither is: a caller could compose the confirmation and reach persistence with values
     * nothing validated, and could read a stored card verification value out of a response. So the area
     * travels as one opaque token issued by {@link ConversationStateSeal}, whose contents are exactly the
     * {@value CommArea#RECORD_LENGTH} bytes the COBOL area holds - {@link #getCommArea()} still answers the
     * structured value to the program flow and to a parity case.
     *
     * <p>Empty means no token arrived. Never {@code null}.
     */
    private String stateToken;

    /**
     * {@code app/cpy/CVCRD01Y.cpy}, copied at {@code app/cbl/COCRDUPC.cbl:268} - the card work area,
     * carrying {@code CCARD-AID} (which key was pressed), the next program, mapset and map, and the
     * error and return messages.
     */
    private CardScreenState cardScreenState;

    private NavigationContext navigationContext;

    private final Map<String, FieldMetadata> fieldMetadata;

    /**
     * Creates a request in which every screen field holds its declared width in spaces, the commarea is in
     * its initial state, the work area is initialised and the navigation context is empty.
     */
    public CardUpdateRequest() {
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
        this.expday = spaces(EXPDAY_LENGTH);
        this.infomsg = spaces(INFOMSG_LENGTH);
        this.errmsg = spaces(ERRMSG_LENGTH);
        this.fkeys = spaces(FKEYS_LENGTH);
        this.fkeysc = spaces(FKEYSC_LENGTH);
        this.commArea = CommArea.initialised();
        this.stateToken = "";
        this.cardScreenState = new CardScreenState();
        this.navigationContext = null;
        this.fieldMetadata = defaultFieldMetadata();
    }

    public CardUpdateRequest(String trnname,
                             String title01,
                             String curdate,
                             String pgmname,
                             String title02,
                             String curtime,
                             String acctsid,
                             String cardsid,
                             String crdname,
                             String crdstcd,
                             String expmon,
                             String expyear,
                             String expday,
                             String infomsg,
                             String errmsg,
                             String fkeys,
                             String fkeysc,
                             CommArea commArea,
                             CardScreenState cardScreenState,
                             NavigationContext navigationContext) {
        this.trnname = orSpaces(trnname, TRNNAME_LENGTH);
        this.title01 = orSpaces(title01, TITLE01_LENGTH);
        this.curdate = orSpaces(curdate, CURDATE_LENGTH);
        this.pgmname = orSpaces(pgmname, PGMNAME_LENGTH);
        this.title02 = orSpaces(title02, TITLE02_LENGTH);
        this.curtime = orSpaces(curtime, CURTIME_LENGTH);
        this.acctsid = orSpaces(acctsid, ACCTSID_LENGTH);
        this.cardsid = orSpaces(cardsid, CARDSID_LENGTH);
        this.crdname = orSpaces(crdname, CRDNAME_LENGTH);
        this.crdstcd = orSpaces(crdstcd, CRDSTCD_LENGTH);
        this.expmon = orSpaces(expmon, EXPMON_LENGTH);
        this.expyear = orSpaces(expyear, EXPYEAR_LENGTH);
        this.expday = orSpaces(expday, EXPDAY_LENGTH);
        this.infomsg = orSpaces(infomsg, INFOMSG_LENGTH);
        this.errmsg = orSpaces(errmsg, ERRMSG_LENGTH);
        this.fkeys = orSpaces(fkeys, FKEYS_LENGTH);
        this.fkeysc = orSpaces(fkeysc, FKEYSC_LENGTH);
        this.commArea = commArea == null ? CommArea.initialised() : commArea;
        this.stateToken = "";
        this.cardScreenState =
                cardScreenState == null ? new CardScreenState() : cardScreenState;
        this.navigationContext = navigationContext;
        this.fieldMetadata = defaultFieldMetadata();
    }

    /**
     * Copies a request, so a caller can snapshot one before mutating it.
     *
     * @param other the request to copy
     * @throws NullPointerException if {@code other} is {@code null}
     */
    public CardUpdateRequest(CardUpdateRequest other) {
        Objects.requireNonNull(other, "A source request is required to copy one");
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
        this.expday = other.expday;
        this.infomsg = other.infomsg;
        this.errmsg = other.errmsg;
        this.fkeys = other.fkeys;
        this.fkeysc = other.fkeysc;
        this.commArea = other.commArea;
        this.stateToken = other.stateToken;
        this.cardScreenState = new CardScreenState(other.cardScreenState);
        this.navigationContext = other.navigationContext;
        this.fieldMetadata = new LinkedHashMap<>(other.fieldMetadata);
    }

    /**
     * Returns the {@code TRNNAME} screen field exactly as it was supplied.
     *
     * @return {@code TRNNAME}, the {@code TRNNAMEI PIC X(4)} value as supplied
     */
    public String getTrnname() {
        return trnname;
    }

    public void setTrnname(String trnname) {
        this.trnname = orSpaces(trnname, TRNNAME_LENGTH);
    }

    /**
     * Returns the {@code TITLE01} screen field exactly as it was supplied.
     *
     * @return {@code TITLE01}, the {@code TITLE01I PIC X(40)} value as supplied
     */
    public String getTitle01() {
        return title01;
    }

    public void setTitle01(String title01) {
        this.title01 = orSpaces(title01, TITLE01_LENGTH);
    }

    /**
     * Returns the {@code CURDATE} screen field exactly as it was supplied.
     *
     * @return {@code CURDATE}, the {@code CURDATEI PIC X(8)} value as supplied
     */
    public String getCurdate() {
        return curdate;
    }

    public void setCurdate(String curdate) {
        this.curdate = orSpaces(curdate, CURDATE_LENGTH);
    }

    /**
     * Returns the {@code PGMNAME} screen field exactly as it was supplied.
     *
     * @return {@code PGMNAME}, the {@code PGMNAMEI PIC X(8)} value as supplied
     */
    public String getPgmname() {
        return pgmname;
    }

    public void setPgmname(String pgmname) {
        this.pgmname = orSpaces(pgmname, PGMNAME_LENGTH);
    }

    /**
     * Returns the {@code TITLE02} screen field exactly as it was supplied.
     *
     * @return {@code TITLE02}, the {@code TITLE02I PIC X(40)} value as supplied
     */
    public String getTitle02() {
        return title02;
    }

    public void setTitle02(String title02) {
        this.title02 = orSpaces(title02, TITLE02_LENGTH);
    }

    /**
     * Returns the {@code CURTIME} screen field exactly as it was supplied.
     *
     * @return {@code CURTIME}, the {@code CURTIMEI PIC X(8)} value as supplied
     */
    public String getCurtime() {
        return curtime;
    }

    public void setCurtime(String curtime) {
        this.curtime = orSpaces(curtime, CURTIME_LENGTH);
    }

    /**
     * Returns the {@code ACCTSID} screen field exactly as it was supplied.
     *
     * @return {@code ACCTSID}, the {@code ACCTSIDI PIC X(11)} value as supplied
     */
    public String getAcctsid() {
        return acctsid;
    }

    public void setAcctsid(String acctsid) {
        this.acctsid = orSpaces(acctsid, ACCTSID_LENGTH);
    }

    /**
     * Returns the {@code CARDSID} screen field exactly as it was supplied.
     *
     * @return {@code CARDSID}, the {@code CARDSIDI PIC X(16)} value as supplied
     */
    public String getCardsid() {
        return cardsid;
    }

    public void setCardsid(String cardsid) {
        this.cardsid = orSpaces(cardsid, CARDSID_LENGTH);
    }

    /**
     * Returns the {@code CRDNAME} screen field exactly as it was supplied.
     *
     * @return {@code CRDNAME}, the {@code CRDNAMEI PIC X(50)} value as supplied
     */
    public String getCrdname() {
        return crdname;
    }

    public void setCrdname(String crdname) {
        this.crdname = orSpaces(crdname, CRDNAME_LENGTH);
    }

    /**
     * Returns the {@code CRDSTCD} screen field exactly as it was supplied.
     *
     * @return {@code CRDSTCD}, the {@code CRDSTCDI PIC X(1)} value as supplied
     */
    public String getCrdstcd() {
        return crdstcd;
    }

    public void setCrdstcd(String crdstcd) {
        this.crdstcd = orSpaces(crdstcd, CRDSTCD_LENGTH);
    }

    /**
     * Returns the {@code EXPMON} screen field exactly as it was supplied.
     *
     * @return {@code EXPMON}, the {@code EXPMONI PIC X(2)} value as supplied
     */
    public String getExpmon() {
        return expmon;
    }

    public void setExpmon(String expmon) {
        this.expmon = orSpaces(expmon, EXPMON_LENGTH);
    }

    /**
     * Returns the {@code EXPYEAR} screen field exactly as it was supplied.
     *
     * @return {@code EXPYEAR}, the {@code EXPYEARI PIC X(4)} value as supplied
     */
    public String getExpyear() {
        return expyear;
    }

    public void setExpyear(String expyear) {
        this.expyear = orSpaces(expyear, EXPYEAR_LENGTH);
    }

    /**
     * Returns the {@code EXPDAY} screen field exactly as it was supplied.
     *
     * @return {@code EXPDAY}, the {@code EXPDAYI PIC X(2)} value as supplied
     */
    public String getExpday() {
        return expday;
    }

    public void setExpday(String expday) {
        this.expday = orSpaces(expday, EXPDAY_LENGTH);
    }

    /**
     * Returns the {@code INFOMSG} screen field exactly as it was supplied.
     *
     * @return {@code INFOMSG}, the {@code INFOMSGI PIC X(40)} value as supplied
     */
    public String getInfomsg() {
        return infomsg;
    }

    public void setInfomsg(String infomsg) {
        this.infomsg = orSpaces(infomsg, INFOMSG_LENGTH);
    }

    /**
     * Returns the {@code ERRMSG} screen field exactly as it was supplied.
     *
     * @return {@code ERRMSG}, the {@code ERRMSGI PIC X(80)} value as supplied
     */
    public String getErrmsg() {
        return errmsg;
    }

    public void setErrmsg(String errmsg) {
        this.errmsg = orSpaces(errmsg, ERRMSG_LENGTH);
    }

    /**
     * Returns the {@code FKEYS} screen field exactly as it was supplied.
     *
     * @return {@code FKEYS}, the {@code FKEYSI PIC X(21)} value as supplied - 21 bytes, never 18
     */
    public String getFkeys() {
        return fkeys;
    }

    public void setFkeys(String fkeys) {
        this.fkeys = orSpaces(fkeys, FKEYS_LENGTH);
    }

    /**
     * {@code FKEYSC}, the {@code FKEYSCI PIC X(18)} value as supplied.
     *
     * @return the value as supplied - 18 bytes, never 21
     */
    public String getFkeysc() {
        return fkeysc;
    }

    public void setFkeysc(String fkeysc) {
        this.fkeysc = orSpaces(fkeysc, FKEYSC_LENGTH);
    }

    /**
     * Returns the program commarea this request carries.
     *
     * <p>{@code @JsonIgnore}: this is the one member of this request that is <strong>not</strong> part of
     * the wire contract. It reaches the program flow, the service and a parity case as the structured value
     * it has always been, but a caller cannot supply it - {@link #getStateToken()} is what crosses the
     * boundary, and {@code CardUpdateController} is what turns one into the other. A body that names
     * {@code commArea} has it ignored rather than honoured.
     *
     * @return the {@value CommArea#RECORD_LENGTH}-byte program commarea, never {@code null}
     */
    @JsonIgnore
    public CommArea getCommArea() {
        return commArea;
    }

    /**
     * Replaces the program commarea this request carries.
     *
     * <p>{@code @JsonIgnore} for the same reason the getter carries it: the area is state this screen
     * issues, and a caller that could write it could claim the confirmation its four edits are proof of and
     * could name the CVV the concurrency check compares.
     *
     * @param commArea the program commarea; {@code null} becomes {@link CommArea#initialised()}
     */
    @JsonIgnore
    public void setCommArea(CommArea commArea) {
        this.commArea = commArea == null ? CommArea.initialised() : commArea;
    }

    /**
     * Returns the sealed program commarea, as it crosses the wire.
     *
     * @return the token, empty when none arrived; never {@code null}
     */
    public String getStateToken() {
        return stateToken;
    }

    /**
     * Replaces the sealed program commarea.
     *
     * @param stateToken the token as the caller sent it; {@code null} becomes empty
     */
    public void setStateToken(String stateToken) {
        this.stateToken = stateToken == null ? "" : stateToken;
    }

    /**
     * The {@code CVCRD01Y} work area, returned live rather than copied.
     *
     * @return the work area, never {@code null}
     */
    public CardScreenState getCardScreenState() {
        return cardScreenState;
    }

    /**
     * Replaces the {@code CVCRD01Y} work area this request carries.
     *
     * @param cardScreenState the work area; {@code null} becomes a freshly initialised one
     */
    public void setCardScreenState(CardScreenState cardScreenState) {
        this.cardScreenState =
                cardScreenState == null ? new CardScreenState() : cardScreenState;
    }

    /**
     * Returns the {@code CARDDEMO-COMMAREA} this request carries.
     *
     * @return the {@value NavigationContext#COMMAREA_LENGTH}-byte {@code CARDDEMO-COMMAREA}, or
     *     {@code null} where none travelled with the request
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    /**
     * Whether a communication area travelled with this request - the Java reading of {@code EIBCALEN} being
     * non-zero at {@code app/cbl/COCRDUPC.cbl:388}.
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
     * Replaces the {@code CARDDEMO-COMMAREA} this request carries.
     *
     * @param navigationContext the commarea, or {@code null} where none travelled with the request - stored
     *     verbatim, because absence is the state {@code app/cbl/COCRDUPC.cbl:388} branches on and an empty area
     *     is a different one
     */
    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = navigationContext;
    }

    /**
     * Whether {@code CDEMO-PGM-CONTEXT} holds {@code 0}, satisfying the {@code 88}-level
     * {@code CDEMO-PGM-ENTER}: the screen is being entered for the first time and is to be painted, not
     * validated.
     *
     * <p>With {@link #isReenter()} that gives three states, not two, which is what the cold-start disjunct
     * at {@code app/cbl/COCRDUPC.cbl:388} requires.
     *
     * @return {@code true} when a communication area travelled and reports first entry
     */
    @JsonIgnore
    public boolean isEnter() {
        return hasNavigationContext() && navigationContext.isEnter();
    }

    /**
     * Whether {@code CDEMO-PGM-CONTEXT} holds {@code 1}, satisfying the {@code 88}-level
     * {@code CDEMO-PGM-REENTER}: what the user typed is to be validated, and the {@code DFHRED} plus
     * {@code '*'} highlight of {@code app/cpy/CSSETATY.cpy} may be applied to an offending field.
     *
     * <p>Deliberately not the negation of {@link #isEnter()}: {@code CDEMO-PGM-CONTEXT} is
     * {@code PIC 9(01)} with two condition names over it rather than an enumeration, so a third digit makes
     * both false - and so does an absent communication area.
     *
     * @return {@code true} when a communication area travelled and reports re-entry
     */
    @JsonIgnore
    public boolean isReenter() {
        return hasNavigationContext() && navigationContext.isReenter();
    }

    /**
     * The per-field metadata, keyed by {@code DFHMDF} label in declaration order.
     *
     * @return an unmodifiable view over {@value #NAMED_FIELD_COUNT} entries
     */
    @JsonIgnore
    public Map<String, FieldMetadata> fieldMetadata() {
        return Collections.unmodifiableMap(fieldMetadata);
    }

    @JsonIgnore
    public FieldMetadata metadataFor(String dfhmdfName) {
        Objects.requireNonNull(dfhmdfName, "A DFHMDF field label is required to look up metadata");
        FieldMetadata metadata = fieldMetadata.get(dfhmdfName);
        if (metadata == null) {
            throw new IllegalArgumentException("'" + dfhmdfName + "' is not a name-labelled DFHMDF "
                    + "field of mapset " + MAPSET_NAME + "; the " + NAMED_FIELD_COUNT
                    + " labels are " + FIELD_NAMES);
        }
        return metadata;
    }

    /**
     * Records what CICS reported for one field, replacing that field's metadata.
     *
     * <p>The supplied metadata must name the same field and declare the same width, because the width comes
     * from the copybook and is not a caller's choice.
     *
     * @param metadata the replacement metadata
     * @throws NullPointerException if {@code metadata} is {@code null}
     * @throws IllegalArgumentException if it names a field this mapset does not declare, or declares a
     *     width other than that field's {@code xxxI} width
     */
    public void putFieldMetadata(FieldMetadata metadata) {
        Objects.requireNonNull(metadata, "Field metadata is required");
        FieldMetadata current = metadataFor(metadata.fieldName());
        if (metadata.declaredLength() != current.declaredLength()) {
            throw new IllegalArgumentException("Field '" + metadata.fieldName() + "' is declared "
                    + current.declaredLength() + " byte(s) wide by its xxxI PICTURE clause, so "
                    + "metadata declaring " + metadata.declaredLength() + " cannot be correct");
        }
        fieldMetadata.put(metadata.fieldName(), metadata);
    }

    /**
     * The {@value #NAMED_FIELD_COUNT} screen values exactly as supplied, keyed by {@code DFHMDF} label in
     * declaration order.
     *
     * @return an unmodifiable, insertion-ordered map of {@value #NAMED_FIELD_COUNT} entries
     */
    @JsonIgnore
    public Map<String, String> fieldValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(TRNNAME_FIELD, trnname);
        values.put(TITLE01_FIELD, title01);
        values.put(CURDATE_FIELD, curdate);
        values.put(PGMNAME_FIELD, pgmname);
        values.put(TITLE02_FIELD, title02);
        values.put(CURTIME_FIELD, curtime);
        values.put(ACCTSID_FIELD, acctsid);
        values.put(CARDSID_FIELD, cardsid);
        values.put(CRDNAME_FIELD, crdname);
        values.put(CRDSTCD_FIELD, crdstcd);
        values.put(EXPMON_FIELD, expmon);
        values.put(EXPYEAR_FIELD, expyear);
        values.put(EXPDAY_FIELD, expday);
        values.put(INFOMSG_FIELD, infomsg);
        values.put(ERRMSG_FIELD, errmsg);
        values.put(FKEYS_FIELD, fkeys);
        values.put(FKEYSC_FIELD, fkeysc);
        return Collections.unmodifiableMap(values);
    }

    /**
     * Every screen value as a fixed-width image of exactly its declared length.
     *
     * @param codec the codec, carrying the code page explicitly
     * @return an unmodifiable, insertion-ordered map of {@value #NAMED_FIELD_COUNT} images whose lengths
     *     sum to {@value #NAMED_FIELD_TOTAL_LENGTH}
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    @JsonIgnore
    public Map<String, String> fieldImages(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to render the field images of "
                + "map " + MAP_NAME + "; the pad and truncate rule is the codec's, never this "
                + "type's");
        Map<String, String> images = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fieldValues().entrySet()) {
            images.put(entry.getKey(),
                    codec.movePicX(entry.getValue(), declaredLength(entry.getKey())));
        }
        return Collections.unmodifiableMap(images);
    }

    /**
     * One screen value as a fixed-width image of exactly its declared length.
     *
     * @param dfhmdfName the {@code DFHMDF} label
     * @param codec the codec, carrying the code page explicitly
     * @return an image of exactly {@code declaredLength(dfhmdfName)} characters
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if {@code dfhmdfName} is not one of the {@value #NAMED_FIELD_COUNT}
     *     labels
     */
    @JsonIgnore
    public String fieldImage(String dfhmdfName, FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to render a field image");
        String value = fieldValues().get(Objects.requireNonNull(dfhmdfName,
                "A DFHMDF field label is required to render one field"));
        if (value == null) {
            throw new IllegalArgumentException("'" + dfhmdfName + "' is not a name-labelled DFHMDF "
                    + "field of mapset " + MAPSET_NAME + "; the " + NAMED_FIELD_COUNT
                    + " labels are " + FIELD_NAMES);
        }
        return codec.movePicX(value, declaredLength(dfhmdfName));
    }

    /**
     * The declared width of one field, from its {@code xxxI} {@code PICTURE} clause.
     *
     * @param dfhmdfName the {@code DFHMDF} label
     * @return the declared width in characters
     * @throws NullPointerException if {@code dfhmdfName} is {@code null}
     * @throws IllegalArgumentException if {@code dfhmdfName} is not one of the {@value #NAMED_FIELD_COUNT}
     *     labels
     */
    public static int declaredLength(String dfhmdfName) {
        Objects.requireNonNull(dfhmdfName, "A DFHMDF field label is required to look up a width");
        Integer length = FIELD_LENGTHS.get(dfhmdfName);
        if (length == null) {
            throw new IllegalArgumentException("'" + dfhmdfName + "' is not a name-labelled DFHMDF "
                    + "field of mapset " + MAPSET_NAME + "; the " + NAMED_FIELD_COUNT
                    + " labels are " + FIELD_NAMES);
        }
        return length;
    }

    /**
     * A run of spaces, the COBOL {@code SPACES} figurative constant at a declared width.
     *
     * <p>{@code SPACES}, {@code LOW-VALUES} and Java {@code null} are three different things and this type
     * never conflates them: a space is {@code 0x20} under {@code US-ASCII} and {@code 0x40} under
     * {@code IBM037}, {@code LOW-VALUES} is {@code 0x00} under both, and {@code null} is not a COBOL state
     * at all.
     *
     * @param length the declared width, at least 1
     * @return exactly {@code length} spaces
     * @throws IllegalArgumentException if {@code length} is below 1
     */
    public static String spaces(int length) {
        if (length < 1) {
            throw new IllegalArgumentException("Declared width " + length + " is not valid for "
                    + "SPACES; every screen field occupies at least one character position");
        }
        return PIC_X_PAD.repeat(length);
    }

    @Override
    public String toString() {
        StringBuilder rendered = new StringBuilder(MAP_NAME).append('[');
        for (Map.Entry<String, String> entry : fieldValues().entrySet()) {
            rendered.append(entry.getKey()).append('=').append('\'')
                    .append(SensitiveDiagnostics.render(disclosureOf(entry.getKey()), entry.getValue()))
                    .append('\'').append(TO_STRING_SEPARATOR);
        }
        return rendered.append("commArea=").append(commArea).append(TO_STRING_SEPARATOR)
                .append("cardScreenState=").append(cardScreenState).append(TO_STRING_SEPARATOR)
                .append("navigationContext=").append(navigationContext).append(']').toString();
    }

    private static final String REDACTED_VALUE = "[REDACTED]";

    private static final Set<String> REDACTED_FIELDS =
            Set.of(ACCTSID_FIELD, CARDSID_FIELD, CRDNAME_FIELD);

    private static String orSpaces(String value, int declaredLength) {
        return value == null ? spaces(declaredLength) : value;
    }

    private static Map<String, Integer> buildFieldLengths() {
        Map<String, Integer> lengths = new LinkedHashMap<>();
        lengths.put(TRNNAME_FIELD, TRNNAME_LENGTH);
        lengths.put(TITLE01_FIELD, TITLE01_LENGTH);
        lengths.put(CURDATE_FIELD, CURDATE_LENGTH);
        lengths.put(PGMNAME_FIELD, PGMNAME_LENGTH);
        lengths.put(TITLE02_FIELD, TITLE02_LENGTH);
        lengths.put(CURTIME_FIELD, CURTIME_LENGTH);
        lengths.put(ACCTSID_FIELD, ACCTSID_LENGTH);
        lengths.put(CARDSID_FIELD, CARDSID_LENGTH);
        lengths.put(CRDNAME_FIELD, CRDNAME_LENGTH);
        lengths.put(CRDSTCD_FIELD, CRDSTCD_LENGTH);
        lengths.put(EXPMON_FIELD, EXPMON_LENGTH);
        lengths.put(EXPYEAR_FIELD, EXPYEAR_LENGTH);
        lengths.put(EXPDAY_FIELD, EXPDAY_LENGTH);
        lengths.put(INFOMSG_FIELD, INFOMSG_LENGTH);
        lengths.put(ERRMSG_FIELD, ERRMSG_LENGTH);
        lengths.put(FKEYS_FIELD, FKEYS_LENGTH);
        lengths.put(FKEYSC_FIELD, FKEYSC_LENGTH);
        return Collections.unmodifiableMap(lengths);
    }

    private static Map<String, FieldMetadata> defaultFieldMetadata() {
        Map<String, FieldMetadata> metadata = new LinkedHashMap<>();
        for (String fieldName : FIELD_NAMES) {
            metadata.put(fieldName, FieldMetadata.notTransmitted(fieldName));
        }
        return metadata;
    }

    /**
     * The metadata items of one screen field: {@code xxxL}, {@code xxxF} and its {@code xxxA} overlay.
     *
     * <p>{@link #attributeItem()} therefore returns exactly what {@link #flagItem()} returns, and that
     * identity is the point - two separate Java fields would misrepresent the copybook and could drift
     * apart.
     *
     * @param fieldName the {@code DFHMDF} label this metadata belongs to, verbatim
     * @param declaredLength the field's {@code xxxI} {@code PICTURE} width, which the copybook fixes
     * @param lengthItem {@code xxxL COMP PIC S9(4)} - the length CICS reports for the field
     * @param flagItem {@code xxxF PICTURE X} - one character
     */
    public record FieldMetadata(String fieldName, int declaredLength, int lengthItem,
                                String flagItem) {
        /**
         * The value {@code COCRDUPC} moves into an {@code xxxL} item to request the cursor:
         * {@code MOVE -1 TO CRDNAMEL OF CCRDUPAI} and five siblings at
         * {@code app/cbl/COCRDUPC.cbl:1214-1234}.
         */
        public static final int CURSOR_LENGTH_ITEM = -1;

        public static final int LENGTH_ITEM_NOT_TRANSMITTED = 0;

        /**
         * The {@code xxxF} flag byte for an unmodified field: {@code LOW-VALUES}, {@code x'00'}.
         */
        public static final String FLAG_ITEM_NOT_MODIFIED = "\u0000";

        public static final int FLAG_ITEM_LENGTH = 1;

        public FieldMetadata {
            Objects.requireNonNull(fieldName, "A DFHMDF field label is required; metadata that "
                    + "cannot name its field cannot be checked against the copybook");
            Objects.requireNonNull(flagItem, "An xxxF flag byte is required; COBOL has no null, and "
                    + "an unmodified field carries LOW-VALUES rather than nothing");
            if (declaredLength < 1) {
                throw new IllegalArgumentException("Field '" + fieldName + "' cannot declare width "
                        + declaredLength + "; every screen field occupies at least one character");
            }
            if (flagItem.length() != FLAG_ITEM_LENGTH) {
                throw new IllegalArgumentException("The xxxF item of '" + fieldName + "' is "
                        + flagItem.length() + " character(s); it is declared PICTURE X and holds "
                        + "exactly one");
            }
            if (lengthItem != CURSOR_LENGTH_ITEM
                    && (lengthItem < LENGTH_ITEM_NOT_TRANSMITTED || lengthItem > declaredLength)) {
                throw new IllegalArgumentException("The xxxL item of '" + fieldName + "' is "
                        + lengthItem + "; it holds a transmitted byte count between 0 and the "
                        + "field's declared width of " + declaredLength + ", or "
                        + CURSOR_LENGTH_ITEM + " to request the cursor");
            }
        }

        /**
         * Metadata for a field the terminal did not transmit: no reported length, and the unmodified flag
         * byte.
         *
         * @param dfhmdfName the {@code DFHMDF} label
         * @return the metadata, with the width taken from the copybook
         * @throws IllegalArgumentException if {@code dfhmdfName} is not a label of this mapset
         */
        public static FieldMetadata notTransmitted(String dfhmdfName) {
            return new FieldMetadata(dfhmdfName, CardUpdateRequest.declaredLength(dfhmdfName),
                    LENGTH_ITEM_NOT_TRANSMITTED, FLAG_ITEM_NOT_MODIFIED);
        }

        /**
         * Metadata for a field the terminal transmitted, with the length CICS reported.
         *
         * @param dfhmdfName the {@code DFHMDF} label
         * @param lengthItem the reported length, from 0 to the field's declared width
         * @return the metadata
         * @throws IllegalArgumentException if the label is unknown or the length is out of range
         */
        public static FieldMetadata transmitted(String dfhmdfName, int lengthItem) {
            return new FieldMetadata(dfhmdfName, CardUpdateRequest.declaredLength(dfhmdfName),
                    lengthItem,
                    FLAG_ITEM_NOT_MODIFIED);
        }

        /**
         * Metadata requesting the cursor in this field, which is what {@code MOVE -1 TO xxxL OF CCRDUPAI}
         * expresses.
         *
         * @param dfhmdfName the {@code DFHMDF} label
         * @return the metadata, with {@code lengthItem} of {@link #CURSOR_LENGTH_ITEM}
         * @throws IllegalArgumentException if {@code dfhmdfName} is not a label of this mapset
         */
        public static FieldMetadata cursorAt(String dfhmdfName) {
            return new FieldMetadata(dfhmdfName, CardUpdateRequest.declaredLength(dfhmdfName),
                    CURSOR_LENGTH_ITEM,
                    FLAG_ITEM_NOT_MODIFIED);
        }

        public String attributeItem() {
            return flagItem;
        }

        /**
         * Whether this field is asking CICS to place the cursor in it.
         *
         * @return {@code true} when {@code xxxL} holds {@link #CURSOR_LENGTH_ITEM}, the CICS cursor request
         */
        public boolean cursorRequested() {
            return lengthItem == CURSOR_LENGTH_ITEM;
        }

        /**
         * Whether the terminal transmitted any byte of this field.
         *
         * @return {@code true} when {@code xxxL} reports at least one transmitted byte
         */
        public boolean fieldTransmitted() {
            return lengthItem > LENGTH_ITEM_NOT_TRANSMITTED;
        }

        /**
         * Whether this field was left unmodified at the terminal.
         *
         * @return {@code true} when {@code xxxF} still holds {@link #FLAG_ITEM_NOT_MODIFIED}, so the field
         *     was not modified at the terminal
         */
        public boolean fieldUnmodified() {
            return FLAG_ITEM_NOT_MODIFIED.equals(flagItem);
        }

        /**
         * Returns a copy carrying a different attribute byte, which is how
         * {@code MOVE DFHBMBRY TO FKEYSCA OF CCRDUPAI} at {@code app/cbl/COCRDUPC.cbl:1316} is expressed.
         *
         * @param replacement the replacement attribute byte, exactly one character
         * @return a copy with the new byte
         * @throws NullPointerException if {@code replacement} is {@code null}
         * @throws IllegalArgumentException if {@code replacement} is not exactly one character
         */
        public FieldMetadata withAttributeItem(String replacement) {
            return new FieldMetadata(fieldName, declaredLength, lengthItem, replacement);
        }

        /**
         * Returns a copy reporting a different transmitted length.
         *
         * @param replacement the replacement {@code xxxL} value
         * @return a copy with the new length item
         * @throws IllegalArgumentException if {@code replacement} is out of range
         */
        public FieldMetadata withLengthItem(int replacement) {
            return new FieldMetadata(fieldName, declaredLength, replacement, flagItem);
        }
    }

    /**
     * {@code 10 CCUP-CHANGE-ACTION PIC X(1) VALUE LOW-VALUES}, the single byte of
     * {@code 05 CARD-UPDATE-SCREEN-DATA} at {@code app/cbl/COCRDUPC.cbl:276-290}, together with all nine of
     * its {@code 88}-level condition names.
     *
     * <p>The overlap is preserved rather than resolved, because {@code 2000-DECIDE-ACTION} at
     * {@code app/cbl/COCRDUPC.cbl:948-1030} is an ordered {@code EVALUATE TRUE} whose first matching
     * {@code WHEN} wins - so which conditions are true simultaneously is exactly what decides where control
     * goes.
     *
     * @param value the stored byte, exactly one character
     */
    public record ChangeAction(String value) {
        /**
         * {@code VALUE LOW-VALUES}, the declared initial state: {@code x'00'}.
         */
        public static final String LOW_VALUES = "\u0000";

        /**
         * {@code SPACES}, the other byte satisfying {@code CCUP-DETAILS-NOT-FETCHED}.
         */
        public static final String SPACES = " ";

        /**
         * {@code 88 CCUP-SHOW-DETAILS VALUE 'S'}, {@code app/cbl/COCRDUPC.cbl:281}.
         */
        public static final String SHOW_DETAILS = "S";

        /**
         * {@code 88 CCUP-CHANGES-NOT-OK VALUE 'E'}, {@code app/cbl/COCRDUPC.cbl:285}.
         */
        public static final String CHANGES_NOT_OK = "E";

        /**
         * {@code 88 CCUP-CHANGES-OK-NOT-CONFIRMED VALUE 'N'}, {@code app/cbl/COCRDUPC.cbl:286}.
         */
        public static final String CHANGES_OK_NOT_CONFIRMED = "N";

        /**
         * {@code 88 CCUP-CHANGES-OKAYED-AND-DONE VALUE 'C'}, {@code app/cbl/COCRDUPC.cbl:287}.
         */
        public static final String CHANGES_OKAYED_AND_DONE = "C";

        /**
         * {@code 88 CCUP-CHANGES-OKAYED-LOCK-ERROR VALUE 'L'}, {@code app/cbl/COCRDUPC.cbl:289}.
         */
        public static final String CHANGES_OKAYED_LOCK_ERROR = "L";

        /**
         * {@code 88 CCUP-CHANGES-OKAYED-BUT-FAILED VALUE 'F'}, {@code app/cbl/COCRDUPC.cbl:290}.
         */
        public static final String CHANGES_OKAYED_BUT_FAILED = "F";

        /**
         * Declared width of {@code CCUP-CHANGE-ACTION PIC X(1)}.
         */
        public static final int RECORD_LENGTH = 1;

        public static final String FIELD_NAME = "CCUP-CHANGE-ACTION";

        /**
         * The five values {@code 88 CCUP-CHANGES-MADE} covers, {@code app/cbl/COCRDUPC.cbl:282-284}.
         */
        public static final List<String> CHANGES_MADE_VALUES = List.of(CHANGES_NOT_OK,
                CHANGES_OK_NOT_CONFIRMED,
                CHANGES_OKAYED_AND_DONE,
                CHANGES_OKAYED_LOCK_ERROR,
                CHANGES_OKAYED_BUT_FAILED);

        /**
         * The two values {@code 88 CCUP-CHANGES-FAILED} covers, {@code app/cbl/COCRDUPC.cbl:288}.
         */
        public static final List<String> CHANGES_FAILED_VALUES =
                List.of(CHANGES_OKAYED_LOCK_ERROR, CHANGES_OKAYED_BUT_FAILED);

        /**
         * The two values {@code 88 CCUP-DETAILS-NOT-FETCHED} covers, {@code app/cbl/COCRDUPC.cbl:278-280}.
         */
        public static final List<String> DETAILS_NOT_FETCHED_VALUES = List.of(LOW_VALUES, SPACES);

        public ChangeAction {
            Objects.requireNonNull(value, "A change-action byte is required; CCUP-CHANGE-ACTION is "
                    + "PIC X(1) with VALUE LOW-VALUES, so its unset state is x'00' and never null");
            if (value.length() != RECORD_LENGTH) {
                throw new IllegalArgumentException("CCUP-CHANGE-ACTION is PIC X(1) and holds exactly "
                        + "one character, but was given " + value.length());
            }
        }

        /**
         * The declared initial state, {@code VALUE LOW-VALUES}.
         *
         * @return a state satisfying {@link #isDetailsNotFetched()}
         */
        public static ChangeAction initial() {
            return new ChangeAction(LOW_VALUES);
        }

        /**
         * The other state satisfying {@code CCUP-DETAILS-NOT-FETCHED}: {@code SPACES}.
         *
         * @return a state satisfying {@link #isDetailsNotFetched()}
         */
        public static ChangeAction spacesState() {
            return new ChangeAction(SPACES);
        }

        /**
         * Returns the {@code 'S'} state, which is what {@code SET CCUP-SHOW-DETAILS TO TRUE} sets.
         *
         * @return {@code SET CCUP-SHOW-DETAILS TO TRUE}
         */
        public static ChangeAction showDetails() {
            return new ChangeAction(SHOW_DETAILS);
        }

        /**
         * Returns the {@code 'E'} state, which is what {@code SET CCUP-CHANGES-NOT-OK TO TRUE} sets.
         *
         * @return {@code SET CCUP-CHANGES-NOT-OK TO TRUE}
         */
        public static ChangeAction changesNotOk() {
            return new ChangeAction(CHANGES_NOT_OK);
        }

        /**
         * Returns the {@code 'N'} state, which is what {@code SET CCUP-CHANGES-OK-NOT-CONFIRMED TO TRUE}
         * sets.
         *
         * @return {@code SET CCUP-CHANGES-OK-NOT-CONFIRMED TO TRUE}
         */
        public static ChangeAction changesOkNotConfirmed() {
            return new ChangeAction(CHANGES_OK_NOT_CONFIRMED);
        }

        /**
         * Returns the {@code 'C'} state, which is what {@code SET CCUP-CHANGES-OKAYED-AND-DONE TO TRUE}
         * sets.
         *
         * @return {@code SET CCUP-CHANGES-OKAYED-AND-DONE TO TRUE}
         */
        public static ChangeAction changesOkayedAndDone() {
            return new ChangeAction(CHANGES_OKAYED_AND_DONE);
        }

        /**
         * Returns the {@code 'L'} state, which is what {@code SET CCUP-CHANGES-OKAYED-LOCK-ERROR TO TRUE}
         * sets.
         *
         * @return {@code SET CCUP-CHANGES-OKAYED-LOCK-ERROR TO TRUE}
         */
        public static ChangeAction changesOkayedLockError() {
            return new ChangeAction(CHANGES_OKAYED_LOCK_ERROR);
        }

        /**
         * Returns the {@code 'F'} state, which is what {@code SET CCUP-CHANGES-OKAYED-BUT-FAILED TO TRUE}
         * sets.
         *
         * @return {@code SET CCUP-CHANGES-OKAYED-BUT-FAILED TO TRUE}
         */
        public static ChangeAction changesOkayedButFailed() {
            return new ChangeAction(CHANGES_OKAYED_BUT_FAILED);
        }

        /**
         * Wraps an arbitrary byte, including one no {@code 88}-level covers, so the {@code WHEN OTHER}
         * abend path of {@code 2000-DECIDE-ACTION} stays reachable.
         *
         * @param value exactly one character
         * @return the state
         * @throws NullPointerException if {@code value} is {@code null}
         * @throws IllegalArgumentException if {@code value} is not exactly one character
         */
        public static ChangeAction of(String value) {
            return new ChangeAction(value);
        }

        public static ChangeAction of(char value) {
            return new ChangeAction(String.valueOf(value));
        }

        /**
         * {@code 88 CCUP-DETAILS-NOT-FETCHED VALUES LOW-VALUES, SPACES} - condition 1 of 9,
         * {@code app/cbl/COCRDUPC.cbl:278-280}.
         *
         * @return {@code true} for {@code x'00'} and for a space, and for nothing else
         */
        @JsonIgnore
        public boolean isDetailsNotFetched() {
            return DETAILS_NOT_FETCHED_VALUES.contains(value);
        }

        /**
         * {@code 88 CCUP-SHOW-DETAILS VALUE 'S'} - condition 2 of 9.
         *
         * @return {@code true} for {@code 'S'}
         */
        @JsonIgnore
        public boolean isShowDetails() {
            return SHOW_DETAILS.equals(value);
        }

        /**
         * {@code 88 CCUP-CHANGES-MADE VALUES 'E','N','C','L','F'} - condition 3 of 9, and one of the two
         * grouping levels.
         *
         * @return {@code true} for any of the five characters
         */
        @JsonIgnore
        public boolean isChangesMade() {
            return CHANGES_MADE_VALUES.contains(value);
        }

        /**
         * {@code 88 CCUP-CHANGES-NOT-OK VALUE 'E'} - condition 4 of 9.
         *
         * @return {@code true} for {@code 'E'}
         */
        @JsonIgnore
        public boolean isChangesNotOk() {
            return CHANGES_NOT_OK.equals(value);
        }

        /**
         * {@code 88 CCUP-CHANGES-OK-NOT-CONFIRMED VALUE 'N'} - condition 5 of 9.
         *
         * @return {@code true} for {@code 'N'}
         */
        @JsonIgnore
        public boolean isChangesOkNotConfirmed() {
            return CHANGES_OK_NOT_CONFIRMED.equals(value);
        }

        /**
         * {@code 88 CCUP-CHANGES-OKAYED-AND-DONE VALUE 'C'} - condition 6 of 9.
         *
         * @return {@code true} for {@code 'C'}
         */
        @JsonIgnore
        public boolean isChangesOkayedAndDone() {
            return CHANGES_OKAYED_AND_DONE.equals(value);
        }

        /**
         * {@code 88 CCUP-CHANGES-FAILED VALUES 'L','F'} - condition 7 of 9, and the second grouping level.
         *
         * @return {@code true} for {@code 'L'} and {@code 'F'} only
         */
        @JsonIgnore
        public boolean isChangesFailed() {
            return CHANGES_FAILED_VALUES.contains(value);
        }

        /**
         * {@code 88 CCUP-CHANGES-OKAYED-LOCK-ERROR VALUE 'L'} - condition 8 of 9.
         *
         * @return {@code true} for {@code 'L'}
         */
        @JsonIgnore
        public boolean isChangesOkayedLockError() {
            return CHANGES_OKAYED_LOCK_ERROR.equals(value);
        }

        /**
         * {@code 88 CCUP-CHANGES-OKAYED-BUT-FAILED VALUE 'F'} - condition 9 of 9.
         *
         * @return {@code true} for {@code 'F'}
         */
        @JsonIgnore
        public boolean isChangesOkayedButFailed() {
            return CHANGES_OKAYED_BUT_FAILED.equals(value);
        }

        /**
         * Whether any of the nine condition names covers the stored byte.
         *
         * @return {@code false} exactly when the byte would fall through to {@code WHEN OTHER}
         */
        @JsonIgnore
        public boolean isRecognised() {
            return isDetailsNotFetched() || isShowDetails() || isChangesMade();
        }

        @Override
        public String toString() {
            return FIELD_NAME + "=" + (LOW_VALUES.equals(value)
                    ? "LOW-VALUES"
                    : "'" + DiagnosticText.singleLine(value) + "'");
        }

        /**
         * Names the {@code 88}-level that holds, for a log line or an assertion message, without deciding
         * anything.
         *
         * <p>{@code 2000-DECIDE-ACTION} evaluates the condition names itself and never a description, so
         * this is a diagnostic only.
         *
         * @return the most specific condition name that holds, for example
         *     {@code "CCUP-CHANGES-OKAYED-AND-DONE"}, or {@code "UNRECOGNISED('x')"} for a state no
         *     {@code 88}-level covers
         */
        public String describe() {
            if (isDetailsNotFetched()) {
                return "CCUP-DETAILS-NOT-FETCHED";
            }
            if (isShowDetails()) {
                return "CCUP-SHOW-DETAILS";
            }
            if (isChangesNotOk()) {
                return "CCUP-CHANGES-NOT-OK";
            }
            if (isChangesOkNotConfirmed()) {
                return "CCUP-CHANGES-OK-NOT-CONFIRMED";
            }
            if (isChangesOkayedAndDone()) {
                return "CCUP-CHANGES-OKAYED-AND-DONE";
            }
            if (isChangesOkayedLockError()) {
                return "CCUP-CHANGES-OKAYED-LOCK-ERROR";
            }
            if (isChangesOkayedButFailed()) {
                return "CCUP-CHANGES-OKAYED-BUT-FAILED";
            }
            return "UNRECOGNISED('" + value + "')";
        }
    }

    /**
     * The two {@code 05}-level snapshot groups of the commarea: {@code 05 CCUP-OLD-DETAILS} at
     * {@code app/cbl/COCRDUPC.cbl:291-301} and {@code 05 CCUP-NEW-DETAILS} at lines 303-313.
     */
    public enum DetailGroup {
        /**
         * {@code CCUP-OLD-DETAILS} - the snapshot the screen was painted from, and the one
         * {@code 9300-CHECK-CHANGE-IN-REC} compares the freshly read record against before allowing a
         * rewrite.
         */
        OLD("CCUP-OLD-"),

        /**
         * {@code CCUP-NEW-DETAILS} - what the user typed, and the source of every value
         * {@code 9200-WRITE-PROCESSING} moves into {@code CARD-UPDATE-RECORD}.
         */
        NEW("CCUP-NEW-");

        private final String prefix;

        private final FieldSpan acctidSpan;

        private final FieldSpan cardidSpan;

        private final FieldSpan cvvCdSpan;

        private final FieldSpan crdnameSpan;

        private final FieldSpan expyearSpan;

        private final FieldSpan expmonSpan;

        private final FieldSpan expdaySpan;

        private final FieldSpan crdstcdSpan;

        private final FieldSpan carddataSpan;

        private final FieldSpan expiraionDateSpan;

        private final RecordLayout layout;

        DetailGroup(String prefix) {
            this.prefix = prefix;
            this.acctidSpan = FieldSpan.alphanumeric(prefix + "ACCTID",
                    CardDetails.ACCTID_OFFSET, CardDetails.ACCTID_LENGTH);
            this.cardidSpan = FieldSpan.alphanumeric(prefix + "CARDID",
                    CardDetails.CARDID_OFFSET, CardDetails.CARDID_LENGTH);
            this.cvvCdSpan = FieldSpan.alphanumeric(prefix + "CVV-CD",
                    CardDetails.CVV_CD_OFFSET, CardDetails.CVV_CD_LENGTH);
            this.crdnameSpan = FieldSpan.alphanumeric(prefix + "CRDNAME",
                    CardDetails.CRDNAME_OFFSET, CardDetails.CRDNAME_LENGTH);
            this.expyearSpan = FieldSpan.alphanumeric(prefix + "EXPYEAR",
                    CardDetails.EXPYEAR_OFFSET, CardDetails.EXPYEAR_LENGTH);
            this.expmonSpan = FieldSpan.alphanumeric(prefix + "EXPMON",
                    CardDetails.EXPMON_OFFSET, CardDetails.EXPMON_LENGTH);
            this.expdaySpan = FieldSpan.alphanumeric(prefix + "EXPDAY",
                    CardDetails.EXPDAY_OFFSET, CardDetails.EXPDAY_LENGTH);
            this.crdstcdSpan = FieldSpan.alphanumeric(prefix + "CRDSTCD",
                    CardDetails.CRDSTCD_OFFSET, CardDetails.CRDSTCD_LENGTH);
            this.carddataSpan = FieldSpan.redefining(prefix + "CARDDATA",
                    CardDetails.CARDDATA_OFFSET, CardDetails.CARDDATA_LENGTH,
                    PictureKind.ALPHANUMERIC);
            this.expiraionDateSpan = FieldSpan.redefining(prefix + "EXPIRAION-DATE",
                    CardDetails.EXPIRAION_DATE_OFFSET, CardDetails.EXPIRAION_DATE_LENGTH,
                    PictureKind.ALPHANUMERIC);
            this.layout = RecordLayout.of(CardDetails.RECORD_LENGTH,
                    acctidSpan,
                    cardidSpan,
                    cvvCdSpan,
                    crdnameSpan,
                    expyearSpan,
                    expmonSpan,
                    expdaySpan,
                    crdstcdSpan,
                    carddataSpan,
                    expiraionDateSpan);
        }

        /**
         * Returns the item-name prefix every item of this group carries.
         *
         * @return the name prefix, {@code "CCUP-OLD-"} or {@code "CCUP-NEW-"}
         */
        public String prefix() {
            return prefix;
        }

        /**
         * The verbatim COBOL name of the {@code 05}-level group itself.
         *
         * @return {@code "CCUP-OLD-DETAILS"} or {@code "CCUP-NEW-DETAILS"}
         */
        public String groupName() {
            return prefix + "DETAILS";
        }

        /**
         * Qualifies an item suffix with this group's prefix.
         *
         * @param suffix the item suffix, for example {@code "EXPIRAION-DATE"}
         * @return the verbatim COBOL item name
         * @throws NullPointerException if {@code suffix} is {@code null}
         */
        public String qualify(String suffix) {
            Objects.requireNonNull(suffix, "An item suffix is required to qualify a group item name");
            return prefix + suffix;
        }

        /**
         * Returns the descriptor of this group's {@code ACCTID} item.
         *
         * @return the {@code CCUP-xxx-ACCTID PIC X(11)} descriptor
         */
        public FieldSpan acctidSpan() {
            return acctidSpan;
        }

        /**
         * Returns the descriptor of this group's {@code CARDID} item.
         *
         * @return the {@code CCUP-xxx-CARDID PIC X(16)} descriptor
         */
        public FieldSpan cardidSpan() {
            return cardidSpan;
        }

        /**
         * Returns the descriptor of this group's {@code CVV-CD} item.
         *
         * @return the {@code CCUP-xxx-CVV-CD PIC X(3)} descriptor - alphanumeric, not numeric
         */
        public FieldSpan cvvCdSpan() {
            return cvvCdSpan;
        }

        /**
         * Returns the descriptor of this group's {@code CRDNAME} item.
         *
         * @return the {@code CCUP-xxx-CRDNAME PIC X(50)} descriptor
         */
        public FieldSpan crdnameSpan() {
            return crdnameSpan;
        }

        /**
         * Returns the descriptor of this group's {@code EXPYEAR} item.
         *
         * @return the {@code CCUP-xxx-EXPYEAR PIC X(4)} descriptor
         */
        public FieldSpan expyearSpan() {
            return expyearSpan;
        }

        /**
         * Returns the descriptor of this group's {@code EXPMON} item.
         *
         * @return the {@code CCUP-xxx-EXPMON PIC X(2)} descriptor
         */
        public FieldSpan expmonSpan() {
            return expmonSpan;
        }

        /**
         * Returns the descriptor of this group's {@code EXPDAY} item.
         *
         * @return the {@code CCUP-xxx-EXPDAY PIC X(2)} descriptor
         */
        public FieldSpan expdaySpan() {
            return expdaySpan;
        }

        /**
         * Returns the descriptor of this group's {@code CRDSTCD} item.
         *
         * @return the {@code CCUP-xxx-CRDSTCD PIC X(1)} descriptor
         */
        public FieldSpan crdstcdSpan() {
            return crdstcdSpan;
        }

        /**
         * Returns the descriptor of this group's {@code CARDDATA} item.
         *
         * @return the {@code CCUP-xxx-CARDDATA} group overlay, 59 bytes at offset
         *     {@value CardDetails#CARDDATA_OFFSET}
         */
        public FieldSpan carddataSpan() {
            return carddataSpan;
        }

        /**
         * Returns the descriptor of this group's {@code EXPIRAION-DATE} item.
         *
         * @return the {@code CCUP-xxx-EXPIRAION-DATE} group overlay, 8 bytes at offset
         *     {@value CardDetails#EXPIRAION_DATE_OFFSET}, name misspelled exactly as the source spells it
         */
        public FieldSpan expiraionDateSpan() {
            return expiraionDateSpan;
        }

        /**
         * Returns this group's record layout, transcribed from the commarea.
         *
         * @return the group's layout, whose spans are contiguous from zero and sum to exactly
         *     {@value CardDetails#RECORD_LENGTH} bytes
         */
        public RecordLayout layout() {
            return layout;
        }
    }

    /**
     * One {@value #RECORD_LENGTH}-byte snapshot group of the commarea - either {@code CCUP-OLD-DETAILS} or
     * {@code CCUP-NEW-DETAILS}, as {@link #group()} says.
     *
     * <p>Every item is {@code PIC X}, so every component is a {@code String} carried at exactly its
     * declared width.
     *
     * @param group which of the two groups this is, and therefore what every item is named
     * @param acctid {@code CCUP-xxx-ACCTID PIC X(11)}
     * @param cardid {@code CCUP-xxx-CARDID PIC X(16)}
     * @param cvvCd {@code CCUP-xxx-CVV-CD PIC X(3)} - alphanumeric
     * @param crdname {@code CCUP-xxx-CRDNAME PIC X(50)}
     * @param expyear {@code CCUP-xxx-EXPYEAR PIC X(4)}
     * @param expmon {@code CCUP-xxx-EXPMON PIC X(2)}
     * @param expday {@code CCUP-xxx-EXPDAY PIC X(2)}
     * @param crdstcd {@code CCUP-xxx-CRDSTCD PIC X(1)}
     */
    public record CardDetails(DetailGroup group,
                              String acctid,
                              String cardid,
                              String cvvCd,
                              String crdname,
                              String expyear,
                              String expmon,
                              String expday,
                              String crdstcd) {
        public static final int RECORD_LENGTH = 89;

        public static final int ACCTID_OFFSET = 0;

        /**
         * Declared width of {@code CCUP-xxx-ACCTID PIC X(11)}.
         */
        public static final int ACCTID_LENGTH = 11;

        public static final int CARDID_OFFSET = 11;

        /**
         * Declared width of {@code CCUP-xxx-CARDID PIC X(16)}.
         */
        public static final int CARDID_LENGTH = 16;

        /**
         * Absolute 0-based offset of {@code CCUP-xxx-CVV-CD}.
         */
        public static final int CVV_CD_OFFSET = 27;

        /**
         * Declared width of {@code CCUP-xxx-CVV-CD PIC X(3)} - alphanumeric, not numeric.
         */
        public static final int CVV_CD_LENGTH = 3;

        public static final int CRDNAME_OFFSET = 30;

        /**
         * Declared width of {@code CCUP-xxx-CRDNAME PIC X(50)}.
         */
        public static final int CRDNAME_LENGTH = 50;

        public static final int EXPYEAR_OFFSET = 80;

        /**
         * Declared width of {@code CCUP-xxx-EXPYEAR PIC X(4)}.
         */
        public static final int EXPYEAR_LENGTH = 4;

        public static final int EXPMON_OFFSET = 84;

        /**
         * Declared width of {@code CCUP-xxx-EXPMON PIC X(2)}.
         */
        public static final int EXPMON_LENGTH = 2;

        public static final int EXPDAY_OFFSET = 86;

        /**
         * Declared width of {@code CCUP-xxx-EXPDAY PIC X(2)}.
         */
        public static final int EXPDAY_LENGTH = 2;

        public static final int CRDSTCD_OFFSET = 88;

        /**
         * Declared width of {@code CCUP-xxx-CRDSTCD PIC X(1)}.
         */
        public static final int CRDSTCD_LENGTH = 1;

        public static final int CARDDATA_OFFSET = 30;

        public static final int CARDDATA_LENGTH = 59;

        public static final int EXPIRAION_DATE_OFFSET = 80;

        public static final int EXPIRAION_DATE_LENGTH = 8;

        /**
         * Normalises and checks every component, so a snapshot can never exist in a shape the commarea
         * cannot hold.
         */
        public CardDetails {
            Objects.requireNonNull(group, "A DetailGroup is required: CCUP-OLD-DETAILS and "
                    + "CCUP-NEW-DETAILS share a layout but not a single item name, and the names are "
                    + "what a field-by-field diff compares");
            acctid = fit(group, "ACCTID", acctid, ACCTID_LENGTH);
            cardid = fit(group, "CARDID", cardid, CARDID_LENGTH);
            cvvCd = fit(group, "CVV-CD", cvvCd, CVV_CD_LENGTH);
            crdname = fit(group, "CRDNAME", crdname, CRDNAME_LENGTH);
            expyear = fit(group, "EXPYEAR", expyear, EXPYEAR_LENGTH);
            expmon = fit(group, "EXPMON", expmon, EXPMON_LENGTH);
            expday = fit(group, "EXPDAY", expday, EXPDAY_LENGTH);
            crdstcd = fit(group, "CRDSTCD", crdstcd, CRDSTCD_LENGTH);
        }

        /**
         * A snapshot in which every item holds its declared width in spaces - the state {@code INITIALIZE}
         * would leave an all-{@code PIC X} group in.
         *
         * @param group which of the two groups to build
         * @return the all-spaces snapshot
         * @throws NullPointerException if {@code group} is {@code null}
         */
        public static CardDetails initialised(DetailGroup group) {
            return new CardDetails(group, null, null, null, null, null, null, null, null);
        }

        /**
         * Reads a {@value #RECORD_LENGTH}-byte image back into a snapshot.
         *
         * @param image exactly {@value #RECORD_LENGTH} bytes
         * @param group which group the bytes are
         * @param codec the codec, carrying the code page explicitly
         * @return the snapshot
         * @throws NullPointerException if any argument is {@code null}
         * @throws IllegalArgumentException if {@code image} is not {@value #RECORD_LENGTH} bytes
         */
        public static CardDetails decode(byte[] image, DetailGroup group, FixedWidthCodec codec) {
            Objects.requireNonNull(group, "A DetailGroup is required to decode a snapshot");
            Objects.requireNonNull(codec, "A codec carrying the image's code page is required to "
                    + "decode " + group.groupName() + "; the platform default is never assumed");
            Objects.requireNonNull(image, "An image is required to decode " + group.groupName());
            FixedWidthRecord area = codec.wrap(image, group.layout());
            return decode(area, group, codec);
        }

        /**
         * Reads an already-wrapped record area back into a snapshot.
         *
         * @param area the record area, of the group's layout
         * @param group which group the bytes are
         * @param codec the codec, carrying the code page explicitly
         * @return the snapshot
         * @throws NullPointerException if any argument is {@code null}
         */
        public static CardDetails decode(FixedWidthRecord area, DetailGroup group,
                                         FixedWidthCodec codec) {
            Objects.requireNonNull(group, "A DetailGroup is required to decode a snapshot");
            Objects.requireNonNull(area, "A record area is required to decode " + group.groupName());
            Objects.requireNonNull(codec, "A codec carrying the area's code page is required to "
                    + "decode " + group.groupName());
            return new CardDetails(group,
                    codec.readPicX(area, group.acctidSpan()),
                    codec.readPicX(area, group.cardidSpan()),
                    codec.readPicX(area, group.cvvCdSpan()),
                    codec.readPicX(area, group.crdnameSpan()),
                    codec.readPicX(area, group.expyearSpan()),
                    codec.readPicX(area, group.expmonSpan()),
                    codec.readPicX(area, group.expdaySpan()),
                    codec.readPicX(area, group.crdstcdSpan()));
        }

        /**
         * Renders the snapshot as exactly {@value #RECORD_LENGTH} bytes.
         *
         * @param codec the codec, carrying the code page explicitly
         * @return exactly {@value #RECORD_LENGTH} bytes
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public byte[] encode(FixedWidthCodec codec) {
            return toFixedWidthRecord(codec).toByteArray();
        }

        /**
         * Renders the snapshot as exactly {@value #RECORD_LENGTH} bytes in a named code page.
         *
         * @param charset the code page, stated explicitly and never defaulted
         * @return exactly {@value #RECORD_LENGTH} bytes
         * @throws NullPointerException if {@code charset} is {@code null}
         */
        public byte[] encode(Charset charset) {
            return encode(new FixedWidthCodec(charset));
        }

        public FixedWidthRecord toFixedWidthRecord(FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A codec carrying the target code page is required to "
                    + "serialise " + group.groupName() + "; the platform default is never assumed");
            FixedWidthRecord area = codec.newRecord(group.layout());
            codec.writePicX(area, group.acctidSpan(), acctid);
            codec.writePicX(area, group.cardidSpan(), cardid);
            codec.writePicX(area, group.cvvCdSpan(), cvvCd);
            codec.writePicX(area, group.crdnameSpan(), crdname);
            codec.writePicX(area, group.expyearSpan(), expyear);
            codec.writePicX(area, group.expmonSpan(), expmon);
            codec.writePicX(area, group.expdaySpan(), expday);
            codec.writePicX(area, group.crdstcdSpan(), crdstcd);
            return area;
        }

        /**
         * The {@code CCUP-xxx-EXPIRAION-DATE} group item: year, month and day concatenated with no
         * separator, exactly {@value #EXPIRAION_DATE_LENGTH} bytes.
         *
         * @return exactly {@value #EXPIRAION_DATE_LENGTH} characters
         */
        public String ccupExpiraionDate() {
            return expyear + expmon + expday;
        }

        /**
         * The {@code CCUP-xxx-CARDDATA} group item: the embossed name, the eight-byte date and the status,
         * exactly {@value #CARDDATA_LENGTH} bytes.
         *
         * @return exactly {@value #CARDDATA_LENGTH} characters
         */
        public String ccupCarddata() {
            return crdname + ccupExpiraionDate() + crdstcd;
        }

        /**
         * The verbatim COBOL name of one item of this snapshot.
         *
         * @param suffix the item suffix, for example {@code "CVV-CD"}
         * @return the qualified name, for example {@code "CCUP-OLD-CVV-CD"}
         * @throws NullPointerException if {@code suffix} is {@code null}
         */
        public String itemName(String suffix) {
            return group.qualify(suffix);
        }

        /**
         * Every item of this snapshot keyed by its verbatim COBOL name, in declaration order, followed by
         * the two group items.
         *
         * @return an unmodifiable, insertion-ordered map of ten entries
         */
        public Map<String, String> itemValues() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put(group.acctidSpan().name(), acctid);
            values.put(group.cardidSpan().name(), cardid);
            values.put(group.cvvCdSpan().name(), cvvCd);
            values.put(group.crdnameSpan().name(), crdname);
            values.put(group.expyearSpan().name(), expyear);
            values.put(group.expmonSpan().name(), expmon);
            values.put(group.expdaySpan().name(), expday);
            values.put(group.crdstcdSpan().name(), crdstcd);
            values.put(group.carddataSpan().name(), ccupCarddata());
            values.put(group.expiraionDateSpan().name(), ccupExpiraionDate());
            return Collections.unmodifiableMap(values);
        }

        /**
         * Returns this snapshot relabelled as the other group, which is how
         * {@code MOVE CCUP-NEW-DETAILS TO CCUP-OLD-DETAILS} would be expressed: same bytes, other name set.
         *
         * @param target the group to relabel as
         * @return this instance when {@code target} is already this group, otherwise a relabelled copy
         * @throws NullPointerException if {@code target} is {@code null}
         */
        public CardDetails asGroup(DetailGroup target) {
            Objects.requireNonNull(target, "A target DetailGroup is required to relabel a snapshot");
            if (target == group) {
                return this;
            }
            return new CardDetails(target, acctid, cardid, cvvCd, crdname, expyear, expmon, expday,
                    crdstcd);
        }

        /**
         * Returns a copy carrying a different account identifier.
         *
         * @param replacement the replacement {@code CCUP-xxx-ACCTID}
         * @return a copy carrying it
         * @throws IllegalArgumentException if it exceeds {@value #ACCTID_LENGTH} characters
         */
        public CardDetails withAcctid(String replacement) {
            return new CardDetails(group, replacement, cardid, cvvCd, crdname, expyear, expmon,
                    expday, crdstcd);
        }

        /**
         * Returns a copy carrying a different card number.
         *
         * @param replacement the replacement {@code CCUP-xxx-CARDID}
         * @return a copy carrying it
         * @throws IllegalArgumentException if it exceeds {@value #CARDID_LENGTH} characters
         */
        public CardDetails withCardid(String replacement) {
            return new CardDetails(group, acctid, replacement, cvvCd, crdname, expyear, expmon,
                    expday, crdstcd);
        }

        /**
         * One of the six items {@code 9300-CHECK-CHANGE-IN-REC} re-snapshots when it detects that the
         * stored record changed under the screen: {@code MOVE CARD-CVV-CD TO CCUP-OLD-CVV-CD}.
         *
         * @param replacement the replacement {@code CCUP-xxx-CVV-CD}, alphanumeric
         * @return a copy carrying it
         * @throws IllegalArgumentException if it exceeds {@value #CVV_CD_LENGTH} characters
         */
        public CardDetails withCvvCd(String replacement) {
            return new CardDetails(group, acctid, cardid, replacement, crdname, expyear, expmon,
                    expday, crdstcd);
        }

        /**
         * {@code MOVE CARD-EMBOSSED-NAME TO CCUP-OLD-CRDNAME}.
         *
         * @param replacement the replacement {@code CCUP-xxx-CRDNAME}
         * @return a copy carrying it
         * @throws IllegalArgumentException if it exceeds {@value #CRDNAME_LENGTH} characters
         */
        public CardDetails withCrdname(String replacement) {
            return new CardDetails(group, acctid, cardid, cvvCd, replacement, expyear, expmon,
                    expday, crdstcd);
        }

        /**
         * {@code MOVE CARD-EXPIRAION-DATE(1:4) TO CCUP-OLD-EXPYEAR}.
         *
         * @param replacement the replacement {@code CCUP-xxx-EXPYEAR}
         * @return a copy carrying it
         * @throws IllegalArgumentException if it exceeds {@value #EXPYEAR_LENGTH} characters
         */
        public CardDetails withExpyear(String replacement) {
            return new CardDetails(group, acctid, cardid, cvvCd, crdname, replacement, expmon,
                    expday, crdstcd);
        }

        /**
         * {@code MOVE CARD-EXPIRAION-DATE(6:2) TO CCUP-OLD-EXPMON}.
         *
         * @param replacement the replacement {@code CCUP-xxx-EXPMON}
         * @return a copy carrying it
         * @throws IllegalArgumentException if it exceeds {@value #EXPMON_LENGTH} characters
         */
        public CardDetails withExpmon(String replacement) {
            return new CardDetails(group, acctid, cardid, cvvCd, crdname, expyear, replacement,
                    expday, crdstcd);
        }

        /**
         * {@code MOVE CARD-EXPIRAION-DATE(9:2) TO CCUP-OLD-EXPDAY}.
         *
         * @param replacement the replacement {@code CCUP-xxx-EXPDAY}
         * @return a copy carrying it
         * @throws IllegalArgumentException if it exceeds {@value #EXPDAY_LENGTH} characters
         */
        public CardDetails withExpday(String replacement) {
            return new CardDetails(group, acctid, cardid, cvvCd, crdname, expyear, expmon,
                    replacement, crdstcd);
        }

        /**
         * {@code MOVE CARD-ACTIVE-STATUS TO CCUP-OLD-CRDSTCD}.
         *
         * @param replacement the replacement {@code CCUP-xxx-CRDSTCD}
         * @return a copy carrying it
         * @throws IllegalArgumentException if it exceeds {@value #CRDSTCD_LENGTH} characters
         */
        public CardDetails withCrdstcd(String replacement) {
            return new CardDetails(group, acctid, cardid, cvvCd, crdname, expyear, expmon, expday,
                    replacement);
        }

        private static String fit(DetailGroup group, String suffix, String value, int declaredWidth) {
            if (value == null) {
                return spaces(declaredWidth);
            }
            if (value.length() > declaredWidth) {
                throw new IllegalArgumentException("A value of " + value.length()
                        + " character(s) exceeds " + group.qualify(suffix) + ", which is declared "
                        + "PIC X(" + declaredWidth + "); a commarea item is never silently "
                        + "truncated, because 9300-CHECK-CHANGE-IN-REC compares these bytes to "
                        + "decide whether the stored record changed under the screen");
            }
            return value.length() == declaredWidth
                    ? value
                    : value + spaces(declaredWidth - value.length());
        }

        /**
         * A diagnostic rendering that withholds the payment data, per {@link SensitiveDiagnostics}.
         *
         * @return a rendering safe to log, never {@code null}
         */
        @Override
        public String toString() {
            return "CardDetails[group=" + group + ", acctid=" + SensitiveDiagnostics.maskIdentifier(acctid)
                    + ", cardid=" + SensitiveDiagnostics.maskPan(cardid)
                    + ", cvvCd=" + SensitiveDiagnostics.redacted()
                    + ", crdname=" + SensitiveDiagnostics.describeText(crdname)
                    + ", expyear='" + expyear
                    + "', expmon='" + expmon
                    + "', expday='" + expday
                    + "', crdstcd='" + crdstcd
                    + "']";
        }
}

    /**
     * {@code 05 CARD-UPDATE-RECORD}, {@code app/cbl/COCRDUPC.cbl:314-321} - the
     * {@value #RECORD_LENGTH}-byte image {@code 9200-WRITE-PROCESSING} stages and then hands to
     * {@code EXEC CICS REWRITE FILE(LIT-CARDFILENAME) FROM(CARD-UPDATE-RECORD)}.
     *
     * <p>The trailing {@code FILLER PIC X(59)} is a first-class span of {@link #LAYOUT} and is emitted as
     * spaces: omitting it would make the record 91 bytes and break every offset downstream of it, and
     * {@link RecordLayout} refuses to exist unless its spans sum to exactly {@value #RECORD_LENGTH}.
     *
     * @param cardUpdateNum {@code CARD-UPDATE-NUM PIC X(16)}
     * @param cardUpdateAcctId {@code CARD-UPDATE-ACCT-ID PIC 9(11)}
     * @param cardUpdateCvvCd {@code CARD-UPDATE-CVV-CD PIC 9(03)} - numeric, unlike the {@code X(3)} form
     *     in {@link CardDetails#cvvCd()}
     * @param cardUpdateEmbossedName {@code CARD-UPDATE-EMBOSSED-NAME PIC X(50)}
     * @param cardUpdateExpiraionDate {@code CARD-UPDATE-EXPIRAION-DATE PIC X(10)}, misspelling preserved
     * @param cardUpdateActiveStatus {@code CARD-UPDATE-ACTIVE-STATUS PIC X(01)}
     */
    public record CardUpdateRecord(String cardUpdateNum,
                                   long cardUpdateAcctId,
                                   int cardUpdateCvvCd,
                                   String cardUpdateEmbossedName,
                                   String cardUpdateExpiraionDate,
                                   String cardUpdateActiveStatus) {
        public static final int RECORD_LENGTH = 150;

        /**
         * Absolute 0-based offset of {@code CARD-UPDATE-NUM}.
         */
        public static final int CARD_UPDATE_NUM_OFFSET = 0;

        /**
         * Declared width of {@code CARD-UPDATE-NUM PIC X(16)}.
         */
        public static final int CARD_UPDATE_NUM_LENGTH = 16;

        /**
         * Absolute 0-based offset of {@code CARD-UPDATE-ACCT-ID}.
         */
        public static final int CARD_UPDATE_ACCT_ID_OFFSET = 16;

        /**
         * Declared digit count of {@code CARD-UPDATE-ACCT-ID PIC 9(11)}, one digit per byte.
         */
        public static final int CARD_UPDATE_ACCT_ID_LENGTH = 11;

        /**
         * Absolute 0-based offset of {@code CARD-UPDATE-CVV-CD}.
         */
        public static final int CARD_UPDATE_CVV_CD_OFFSET = 27;

        /**
         * Declared digit count of {@code CARD-UPDATE-CVV-CD PIC 9(03)}, one digit per byte.
         */
        public static final int CARD_UPDATE_CVV_CD_LENGTH = 3;

        /**
         * Absolute 0-based offset of {@code CARD-UPDATE-EMBOSSED-NAME}.
         */
        public static final int CARD_UPDATE_EMBOSSED_NAME_OFFSET = 30;

        /**
         * Declared width of {@code CARD-UPDATE-EMBOSSED-NAME PIC X(50)}.
         */
        public static final int CARD_UPDATE_EMBOSSED_NAME_LENGTH = 50;

        /**
         * Absolute 0-based offset of {@code CARD-UPDATE-EXPIRAION-DATE} - the source's spelling, kept.
         */
        public static final int CARD_UPDATE_EXPIRAION_DATE_OFFSET = 80;

        /**
         * Declared width of {@code CARD-UPDATE-EXPIRAION-DATE PIC X(10)}: ten bytes, because this form
         * carries the two {@code '-'} separators the eight-byte commarea form does not.
         */
        public static final int CARD_UPDATE_EXPIRAION_DATE_LENGTH = 10;

        /**
         * Absolute 0-based offset of {@code CARD-UPDATE-ACTIVE-STATUS}.
         */
        public static final int CARD_UPDATE_ACTIVE_STATUS_OFFSET = 90;

        /**
         * Declared width of {@code CARD-UPDATE-ACTIVE-STATUS PIC X(01)}.
         */
        public static final int CARD_UPDATE_ACTIVE_STATUS_LENGTH = 1;

        /**
         * Absolute 0-based offset of the trailing {@code FILLER PIC X(59)},
         * {@code app/cbl/COCRDUPC.cbl:321}.
         */
        public static final int FILLER_OFFSET = 91;

        /**
         * Declared width of the trailing {@code FILLER PIC X(59)}.
         */
        public static final int FILLER_LENGTH = 59;

        /**
         * One past the largest value {@code PIC 9(11)} can hold.
         */
        public static final long CARD_UPDATE_ACCT_ID_EXCLUSIVE_LIMIT = 100_000_000_000L;

        /**
         * One past the largest value {@code PIC 9(03)} can hold: {@code 1000}.
         */
        public static final int CARD_UPDATE_CVV_CD_EXCLUSIVE_LIMIT = 1_000;

        public static final String EXPIRAION_DATE_SEPARATOR = "-";

        /**
         * 0-based begin index of the year within the {@code X(10)} date - COBOL {@code (1:4)}.
         */
        public static final int EXPIRAION_YEAR_BEGIN_INDEX = 0;

        public static final int EXPIRAION_YEAR_END_INDEX = 4;

        /**
         * 0-based begin index of the month within the {@code X(10)} date - COBOL {@code (6:2)}.
         */
        public static final int EXPIRAION_MONTH_BEGIN_INDEX = 5;

        public static final int EXPIRAION_MONTH_END_INDEX = 7;

        /**
         * 0-based begin index of the day within the {@code X(10)} date - COBOL {@code (9:2)}.
         */
        public static final int EXPIRAION_DAY_BEGIN_INDEX = 8;

        public static final int EXPIRAION_DAY_END_INDEX = 10;

        /**
         * {@code CARD-UPDATE-NUM PIC X(16)} at offset {@value #CARD_UPDATE_NUM_OFFSET}.
         */
        public static final FieldSpan CARD_UPDATE_NUM = FieldSpan.alphanumeric("CARD-UPDATE-NUM",
                CARD_UPDATE_NUM_OFFSET, CARD_UPDATE_NUM_LENGTH);

        /**
         * {@code CARD-UPDATE-ACCT-ID PIC 9(11)} at offset {@value #CARD_UPDATE_ACCT_ID_OFFSET}.
         */
        public static final FieldSpan CARD_UPDATE_ACCT_ID = FieldSpan.unsignedNumeric(
                "CARD-UPDATE-ACCT-ID", CARD_UPDATE_ACCT_ID_OFFSET, CARD_UPDATE_ACCT_ID_LENGTH);

        /**
         * {@code CARD-UPDATE-CVV-CD PIC 9(03)} at offset {@value #CARD_UPDATE_CVV_CD_OFFSET}.
         */
        public static final FieldSpan CARD_UPDATE_CVV_CD = FieldSpan.unsignedNumeric(
                "CARD-UPDATE-CVV-CD", CARD_UPDATE_CVV_CD_OFFSET, CARD_UPDATE_CVV_CD_LENGTH);

        /**
         * {@code CARD-UPDATE-EMBOSSED-NAME PIC X(50)} at offset {@value #CARD_UPDATE_EMBOSSED_NAME_OFFSET}.
         */
        public static final FieldSpan CARD_UPDATE_EMBOSSED_NAME = FieldSpan.alphanumeric(
                "CARD-UPDATE-EMBOSSED-NAME", CARD_UPDATE_EMBOSSED_NAME_OFFSET,
                CARD_UPDATE_EMBOSSED_NAME_LENGTH);

        /**
         * {@code CARD-UPDATE-EXPIRAION-DATE PIC X(10)} at offset
         * {@value #CARD_UPDATE_EXPIRAION_DATE_OFFSET}, name carried with the source's misspelling.
         */
        public static final FieldSpan CARD_UPDATE_EXPIRAION_DATE = FieldSpan.alphanumeric(
                "CARD-UPDATE-EXPIRAION-DATE", CARD_UPDATE_EXPIRAION_DATE_OFFSET,
                CARD_UPDATE_EXPIRAION_DATE_LENGTH);

        /**
         * {@code CARD-UPDATE-ACTIVE-STATUS PIC X(01)} at offset {@value #CARD_UPDATE_ACTIVE_STATUS_OFFSET}.
         */
        public static final FieldSpan CARD_UPDATE_ACTIVE_STATUS = FieldSpan.alphanumeric(
                "CARD-UPDATE-ACTIVE-STATUS", CARD_UPDATE_ACTIVE_STATUS_OFFSET,
                CARD_UPDATE_ACTIVE_STATUS_LENGTH);

        /**
         * The trailing {@code FILLER PIC X(59)} at offset {@value #FILLER_OFFSET}.
         */
        public static final FieldSpan FILLER = FieldSpan.filler(FILLER_OFFSET, FILLER_LENGTH);

        public static final RecordLayout LAYOUT = RecordLayout.of(RECORD_LENGTH,
                CARD_UPDATE_NUM,
                CARD_UPDATE_ACCT_ID,
                CARD_UPDATE_CVV_CD,
                CARD_UPDATE_EMBOSSED_NAME,
                CARD_UPDATE_EXPIRAION_DATE,
                CARD_UPDATE_ACTIVE_STATUS,
                FILLER);

        public CardUpdateRecord {
            cardUpdateNum = fit(CARD_UPDATE_NUM, cardUpdateNum);
            cardUpdateEmbossedName = fit(CARD_UPDATE_EMBOSSED_NAME, cardUpdateEmbossedName);
            cardUpdateExpiraionDate = fit(CARD_UPDATE_EXPIRAION_DATE, cardUpdateExpiraionDate);
            cardUpdateActiveStatus = fit(CARD_UPDATE_ACTIVE_STATUS, cardUpdateActiveStatus);
            requireUnsigned(CARD_UPDATE_ACCT_ID, cardUpdateAcctId,
                    CARD_UPDATE_ACCT_ID_EXCLUSIVE_LIMIT);
            requireUnsigned(CARD_UPDATE_CVV_CD, cardUpdateCvvCd,
                    CARD_UPDATE_CVV_CD_EXCLUSIVE_LIMIT);
        }

        /**
         * The state {@code INITIALIZE CARD-UPDATE-RECORD} leaves at {@code app/cbl/COCRDUPC.cbl:1461}:
         * every {@code PIC X} item all spaces and every {@code PIC 9} item zero.
         *
         * @return the initialised record
         */
        public static CardUpdateRecord initialised() {
            return new CardUpdateRecord(null, 0L, 0, null, null, null);
        }

        /**
         * Stages the record from a {@code CCUP-NEW-DETAILS} snapshot, reproducing
         * {@code 9200-WRITE-PROCESSING} at {@code app/cbl/COCRDUPC.cbl:1461-1476} field for field: the card
         * number and the embossed name move across unchanged, the alphanumeric {@code PIC X(3)} CVV is
         * reinterpreted as the numeric {@code PIC 9(03)} item exactly as the COBOL's {@code CARD-CVV-CD-X}
         * / {@code CARD-CVV-CD-N}
         *
         * @param details the {@code CCUP-NEW-DETAILS} snapshot; its group must be {@link DetailGroup#NEW}
         * @param acctId the value of {@code CC-ACCT-ID-N}
         * @param codec the codec, which owns the numeric decode and the delimited concatenation
         * @return the staged record
         * @throws NullPointerException if {@code details} or {@code codec} is {@code null}
         * @throws IllegalArgumentException if {@code details} is not the {@link DetailGroup#NEW} snapshot,
         *     if the CVV item holds a non-digit, or if {@code acctId} does not fit {@code PIC 9(11)}
         */
        public static CardUpdateRecord staging(CardDetails details, long acctId,
                                              FixedWidthCodec codec) {
            Objects.requireNonNull(details, "A CCUP-NEW-DETAILS snapshot is required to stage "
                    + "CARD-UPDATE-RECORD");
            Objects.requireNonNull(codec, "A codec is required to stage CARD-UPDATE-RECORD: the "
                    + "PIC X(3) to PIC 9(03) CVV reinterpretation and the delimited date "
                    + "concatenation are both the codec's rules, never this type's");
            if (details.group() != DetailGroup.NEW) {
                throw new IllegalArgumentException("9200-WRITE-PROCESSING stages the record from "
                        + DetailGroup.NEW.groupName() + ", but the supplied snapshot is "
                        + details.group().groupName());
            }
            return new CardUpdateRecord(details.cardid(),
                    acctId,
                    codec.decodePic9AsInt(details.cvvCd()),
                    details.crdname(),
                    compose(details, codec),
                    details.crdstcd());
        }

        /**
         * Composes the {@code PIC X(10)} date from a snapshot's four-two-two items, reproducing
         * {@code STRING CCUP-NEW-EXPYEAR '-' CCUP-NEW-EXPMON '-' CCUP-NEW-EXPDAY DELIMITED BY SIZE} at
         * {@code app/cbl/COCRDUPC.cbl:1467-1474}.
         *
         * @param details the snapshot supplying year, month and day
         * @param codec the codec, which owns {@code DELIMITED BY SIZE} concatenation
         * @return exactly {@value #CARD_UPDATE_EXPIRAION_DATE_LENGTH} characters
         * @throws NullPointerException if either argument is {@code null}
         */
        public static String compose(CardDetails details, FixedWidthCodec codec) {
            Objects.requireNonNull(details, "A snapshot is required to compose the ten-byte date");
            Objects.requireNonNull(codec, "A codec is required to compose the ten-byte date");
            return codec.concatenateDelimitedBySize(details.expyear(),
                    EXPIRAION_DATE_SEPARATOR,
                    details.expmon(),
                    EXPIRAION_DATE_SEPARATOR,
                    details.expday());
        }

        /**
         * Reads a {@value #RECORD_LENGTH}-byte image back into a record.
         *
         * @param image exactly {@value #RECORD_LENGTH} bytes
         * @param codec the codec, carrying the code page explicitly
         * @return the record
         * @throws NullPointerException if either argument is {@code null}
         * @throws IllegalArgumentException if {@code image} is not {@value #RECORD_LENGTH} bytes, or a
         *     numeric span holds a non-digit
         */
        public static CardUpdateRecord decode(byte[] image, FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A codec carrying the image's code page is required to "
                    + "decode CARD-UPDATE-RECORD; the platform default is never assumed");
            Objects.requireNonNull(image, "An image is required to decode CARD-UPDATE-RECORD");
            return decode(codec.wrap(image, LAYOUT), codec);
        }

        /**
         * Reads a {@value #RECORD_LENGTH}-byte image back into a record, in a named code page.
         *
         * @param image exactly {@value #RECORD_LENGTH} bytes
         * @param charset the code page, stated explicitly
         * @return the record
         * @throws NullPointerException if either argument is {@code null}
         */
        public static CardUpdateRecord decode(byte[] image, Charset charset) {
            return decode(image, new FixedWidthCodec(charset));
        }

        /**
         * Reads an already-wrapped record area back into a record.
         *
         * @param area the record area, of {@link #LAYOUT}
         * @param codec the codec, carrying the area's code page
         * @return the record
         * @throws NullPointerException if either argument is {@code null}
         */
        public static CardUpdateRecord decode(FixedWidthRecord area, FixedWidthCodec codec) {
            Objects.requireNonNull(area, "A record area is required to decode CARD-UPDATE-RECORD");
            Objects.requireNonNull(codec, "A codec carrying the area's code page is required to "
                    + "decode CARD-UPDATE-RECORD");
            return new CardUpdateRecord(codec.readPicX(area, CARD_UPDATE_NUM),
                    codec.readPic9(area, CARD_UPDATE_ACCT_ID),
                    codec.readPic9AsInt(area, CARD_UPDATE_CVV_CD),
                    codec.readPicX(area, CARD_UPDATE_EMBOSSED_NAME),
                    codec.readPicX(area, CARD_UPDATE_EXPIRAION_DATE),
                    codec.readPicX(area, CARD_UPDATE_ACTIVE_STATUS));
        }

        /**
         * Renders the record as exactly {@value #RECORD_LENGTH} bytes.
         *
         * @param codec the codec, carrying the code page explicitly
         * @return exactly {@value #RECORD_LENGTH} bytes, the trailing {@value #FILLER_LENGTH} of which are
         *     spaces
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public byte[] encode(FixedWidthCodec codec) {
            return toFixedWidthRecord(codec).toByteArray();
        }

        /**
         * Renders the record as exactly {@value #RECORD_LENGTH} bytes in a named code page.
         *
         * @param charset the code page, stated explicitly and never defaulted
         * @return exactly {@value #RECORD_LENGTH} bytes
         * @throws NullPointerException if {@code charset} is {@code null}
         */
        public byte[] encode(Charset charset) {
            return encode(new FixedWidthCodec(charset));
        }

        /**
         * Builds the record area, span by span, then re-fills the {@code FILLER} explicitly.
         *
         * @param codec the codec, carrying the code page explicitly
         * @return an area of exactly {@value #RECORD_LENGTH} bytes
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public FixedWidthRecord toFixedWidthRecord(FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A codec carrying the target code page is required to "
                    + "serialise CARD-UPDATE-RECORD; the platform default is never assumed");
            FixedWidthRecord area = codec.newRecord(LAYOUT);
            codec.writePicX(area, CARD_UPDATE_NUM, cardUpdateNum);
            codec.writePic9(area, CARD_UPDATE_ACCT_ID, cardUpdateAcctId);
            codec.writePic9(area, CARD_UPDATE_CVV_CD, cardUpdateCvvCd);
            codec.writePicX(area, CARD_UPDATE_EMBOSSED_NAME, cardUpdateEmbossedName);
            codec.writePicX(area, CARD_UPDATE_EXPIRAION_DATE, cardUpdateExpiraionDate);
            codec.writePicX(area, CARD_UPDATE_ACTIVE_STATUS, cardUpdateActiveStatus);
            area.fill(FILLER_OFFSET, FILLER_LENGTH, area.spacePadByte());
            return area;
        }

        /**
         * The zoned image of {@code CARD-UPDATE-ACCT-ID}: {@value #CARD_UPDATE_ACCT_ID_LENGTH} characters,
         * zero-filled on the left.
         *
         * @param codec the codec, which owns the {@code PIC 9} move rule
         * @return exactly {@value #CARD_UPDATE_ACCT_ID_LENGTH} digits
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public String cardUpdateAcctIdImage(FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A codec is required to render a PIC 9 image");
            return codec.movePic9(cardUpdateAcctId, CARD_UPDATE_ACCT_ID_LENGTH);
        }

        /**
         * The zoned image of {@code CARD-UPDATE-CVV-CD}: {@value #CARD_UPDATE_CVV_CD_LENGTH} characters,
         * zero-filled on the left.
         *
         * @param codec the codec, which owns the {@code PIC 9} move rule
         * @return exactly {@value #CARD_UPDATE_CVV_CD_LENGTH} digits
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public String cardUpdateCvvCdImage(FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A codec is required to render a PIC 9 image");
            return codec.movePic9(cardUpdateCvvCd, CARD_UPDATE_CVV_CD_LENGTH);
        }

        /**
         * The year slice of the ten-byte date - COBOL {@code CARD-EXPIRAION-DATE(1:4)}, translated from
         * 1-based reference modification to 0-based indices.
         *
         * @return four characters
         */
        public String cardUpdateExpiraionDateYear() {
            return cardUpdateExpiraionDate.substring(EXPIRAION_YEAR_BEGIN_INDEX,
                    EXPIRAION_YEAR_END_INDEX);
        }

        /**
         * The month slice - COBOL {@code (6:2)}, so 0-based indices 5 to 7.
         *
         * @return two characters
         */
        public String cardUpdateExpiraionDateMonth() {
            return cardUpdateExpiraionDate.substring(EXPIRAION_MONTH_BEGIN_INDEX,
                    EXPIRAION_MONTH_END_INDEX);
        }

        /**
         * The day slice - COBOL {@code (9:2)}, so 0-based indices 8 to 10.
         *
         * @return two characters
         */
        public String cardUpdateExpiraionDateDay() {
            return cardUpdateExpiraionDate.substring(EXPIRAION_DAY_BEGIN_INDEX,
                    EXPIRAION_DAY_END_INDEX);
        }

        /**
         * Whether the stored record still matches the snapshot the screen was painted from, which is the
         * whole of {@code 9300-CHECK-CHANGE-IN-REC}'s test at {@code app/cbl/COCRDUPC.cbl:1503-1509}: the
         * CVV, the embossed name, the three date slices and the active status, in that order, all compared
         * field by field.
         *
         * <p>The card number and the account identifier are deliberately not part of the comparison,
         * because the COBOL does not compare them.
         *
         * @param snapshot the {@code CCUP-OLD-DETAILS} snapshot to compare against
         * @param codec the codec, which renders this record's numeric CVV into the snapshot's
         *     three-character alphanumeric form so like is compared with like
         * @return {@code true} when every compared item matches, so the rewrite may proceed
         * @throws NullPointerException if either argument is {@code null}
         * @throws IllegalArgumentException if {@code snapshot} is not the {@link DetailGroup#OLD} snapshot
         */
        public boolean matchesSnapshot(CardDetails snapshot, FixedWidthCodec codec) {
            Objects.requireNonNull(snapshot, "A CCUP-OLD-DETAILS snapshot is required to check "
                    + "whether the stored record changed under the screen");
            Objects.requireNonNull(codec, "A codec is required: the record's CVV is PIC 9(03) and "
                    + "the snapshot's is PIC X(3), so one has to be rendered into the other's form");
            if (snapshot.group() != DetailGroup.OLD) {
                throw new IllegalArgumentException("9300-CHECK-CHANGE-IN-REC compares against "
                        + DetailGroup.OLD.groupName() + ", but the supplied snapshot is "
                        + snapshot.group().groupName());
            }
            return cardUpdateCvvCdImage(codec).equals(snapshot.cvvCd())
                    && cardUpdateEmbossedName.equals(snapshot.crdname())
                    && cardUpdateExpiraionDateYear().equals(snapshot.expyear())
                    && cardUpdateExpiraionDateMonth().equals(snapshot.expmon())
                    && cardUpdateExpiraionDateDay().equals(snapshot.expday())
                    && cardUpdateActiveStatus.equals(snapshot.crdstcd());
        }

        /**
         * Every item of the record keyed by its verbatim COBOL name, in declaration order, with the numeric
         * items rendered as their zoned images so a field-by-field diff compares bytes.
         *
         * @param codec the codec, which renders the two numeric images
         * @return an unmodifiable, insertion-ordered map of six entries
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public Map<String, String> itemValues(FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A codec is required to render the numeric items");
            Map<String, String> values = new LinkedHashMap<>();
            values.put(CARD_UPDATE_NUM.name(), cardUpdateNum);
            values.put(CARD_UPDATE_ACCT_ID.name(), cardUpdateAcctIdImage(codec));
            values.put(CARD_UPDATE_CVV_CD.name(), cardUpdateCvvCdImage(codec));
            values.put(CARD_UPDATE_EMBOSSED_NAME.name(), cardUpdateEmbossedName);
            values.put(CARD_UPDATE_EXPIRAION_DATE.name(), cardUpdateExpiraionDate);
            values.put(CARD_UPDATE_ACTIVE_STATUS.name(), cardUpdateActiveStatus);
            return Collections.unmodifiableMap(values);
        }

        /**
         * Returns a copy carrying a different {@code CARD-UPDATE-NUM}.
         *
         * @param replacement the replacement {@code CARD-UPDATE-NUM}
         * @return a copy carrying it
         * @throws IllegalArgumentException if it exceeds {@value #CARD_UPDATE_NUM_LENGTH} characters
         */
        public CardUpdateRecord withCardUpdateNum(String replacement) {
            return new CardUpdateRecord(replacement, cardUpdateAcctId, cardUpdateCvvCd,
                    cardUpdateEmbossedName, cardUpdateExpiraionDate, cardUpdateActiveStatus);
        }

        /**
         * Returns a copy carrying a different {@code CARD-UPDATE-ACCT-ID}.
         *
         * @param replacement the replacement {@code CARD-UPDATE-ACCT-ID}
         * @return a copy carrying it
         * @throws IllegalArgumentException if it is negative or does not fit {@code PIC 9(11)}
         */
        public CardUpdateRecord withCardUpdateAcctId(long replacement) {
            return new CardUpdateRecord(cardUpdateNum, replacement, cardUpdateCvvCd,
                    cardUpdateEmbossedName, cardUpdateExpiraionDate, cardUpdateActiveStatus);
        }

        /**
         * Returns a copy carrying a different {@code CARD-UPDATE-CVV-CD}.
         *
         * @param replacement the replacement {@code CARD-UPDATE-CVV-CD}
         * @return a copy carrying it
         * @throws IllegalArgumentException if it is negative or does not fit {@code PIC 9(03)}
         */
        public CardUpdateRecord withCardUpdateCvvCd(int replacement) {
            return new CardUpdateRecord(cardUpdateNum, cardUpdateAcctId, replacement,
                    cardUpdateEmbossedName, cardUpdateExpiraionDate, cardUpdateActiveStatus);
        }

        /**
         * Returns a copy carrying a different {@code CARD-UPDATE-EMBOSSED-NAME}.
         *
         * @param replacement the replacement {@code CARD-UPDATE-EMBOSSED-NAME}
         * @return a copy carrying it
         * @throws IllegalArgumentException if it exceeds {@value #CARD_UPDATE_EMBOSSED_NAME_LENGTH}
         *     characters
         */
        public CardUpdateRecord withCardUpdateEmbossedName(String replacement) {
            return new CardUpdateRecord(cardUpdateNum, cardUpdateAcctId, cardUpdateCvvCd,
                    replacement, cardUpdateExpiraionDate, cardUpdateActiveStatus);
        }

        /**
         * Returns a copy carrying a different {@code CARD-UPDATE-EXPIRAION-DATE}.
         *
         * @param replacement the replacement {@code CARD-UPDATE-EXPIRAION-DATE}, misspelling and all
         * @return a copy carrying it
         * @throws IllegalArgumentException if it exceeds {@value #CARD_UPDATE_EXPIRAION_DATE_LENGTH}
         *     characters
         */
        public CardUpdateRecord withCardUpdateExpiraionDate(String replacement) {
            return new CardUpdateRecord(cardUpdateNum, cardUpdateAcctId, cardUpdateCvvCd,
                    cardUpdateEmbossedName, replacement, cardUpdateActiveStatus);
        }

        /**
         * Returns a copy carrying a different {@code CARD-UPDATE-ACTIVE-STATUS}.
         *
         * @param replacement the replacement {@code CARD-UPDATE-ACTIVE-STATUS}
         * @return a copy carrying it
         * @throws IllegalArgumentException if it exceeds {@value #CARD_UPDATE_ACTIVE_STATUS_LENGTH}
         *     characters
         */
        public CardUpdateRecord withCardUpdateActiveStatus(String replacement) {
            return new CardUpdateRecord(cardUpdateNum, cardUpdateAcctId, cardUpdateCvvCd,
                    cardUpdateEmbossedName, cardUpdateExpiraionDate, replacement);
        }

        private static String fit(FieldSpan span, String value) {
            if (value == null) {
                return spaces(span.length());
            }
            if (value.length() > span.length()) {
                throw new IllegalArgumentException("A value of " + value.length()
                        + " character(s) exceeds " + span.describe() + ", which holds "
                        + span.length() + "; a record item is never silently truncated");
            }
            return value.length() == span.length()
                    ? value
                    : value + spaces(span.length() - value.length());
        }

        private static void requireUnsigned(FieldSpan span, long value, long exclusiveLimit) {
            if (value < 0) {
                throw new IllegalArgumentException("A negative value cannot be stored in "
                        + span.describe() + "; PIC 9 declares no sign position, so a negative value "
                        + "has nowhere to put its sign");
            }
            if (value >= exclusiveLimit) {
                throw new IllegalArgumentException("A value needing more than "
                        + span.length() + " digit(s) cannot be stored in " + span.describe()
                        + "; a COBOL numeric MOVE would silently keep only the low-order digits");
            }
        }

        /**
         * A diagnostic rendering that withholds the payment data, per {@link SensitiveDiagnostics}.
         *
         * @return a rendering safe to log, never {@code null}
         */
        @Override
        public String toString() {
            return "CardUpdateRecord[cardUpdateNum=" + SensitiveDiagnostics.maskPan(cardUpdateNum)
                    + ", cardUpdateAcctId="
                    + SensitiveDiagnostics.maskIdentifier(cardUpdateAcctId, CARD_UPDATE_ACCT_ID_LENGTH)
                    + ", cardUpdateCvvCd=" + SensitiveDiagnostics.redacted()
                    + ", cardUpdateEmbossedName="
                    + SensitiveDiagnostics.describeText(cardUpdateEmbossedName)
                    + ", cardUpdateExpiraionDate='" + cardUpdateExpiraionDate
                    + "', cardUpdateActiveStatus='" + cardUpdateActiveStatus
                    + "']";
        }
    }

    /**
     * {@code 01 WS-THIS-PROGCOMMAREA}, {@code app/cbl/COCRDUPC.cbl:274-321} - the program's own commarea,
     * {@value #RECORD_LENGTH} bytes, carried in the payload because CICS is pseudo-conversational and this
     * translation keeps no server-side state.
     *
     * @param changeAction {@code CCUP-CHANGE-ACTION} and its nine {@code 88}-levels
     * @param oldDetails {@code CCUP-OLD-DETAILS}; its group must be {@link DetailGroup#OLD}
     * @param newDetails {@code CCUP-NEW-DETAILS}; its group must be {@link DetailGroup#NEW}
     * @param cardUpdateRecord {@code CARD-UPDATE-RECORD}
     */
    public record CommArea(ChangeAction changeAction,
                           CardDetails oldDetails,
                           CardDetails newDetails,
                           CardUpdateRecord cardUpdateRecord) {
        public static final int RECORD_LENGTH = 329;

        /**
         * Absolute 0-based offset of {@code CARD-UPDATE-SCREEN-DATA} and its single byte.
         */
        public static final int CHANGE_ACTION_OFFSET = 0;

        /**
         * Absolute 0-based offset of {@code CCUP-OLD-DETAILS}: {@code 0 + 1}.
         */
        public static final int OLD_DETAILS_OFFSET = 1;

        /**
         * Absolute 0-based offset of {@code CCUP-NEW-DETAILS}: {@code 1 + 89}.
         */
        public static final int NEW_DETAILS_OFFSET = 90;

        /**
         * Absolute 0-based offset of {@code CARD-UPDATE-RECORD}: {@code 90 + 89}.
         */
        public static final int CARD_UPDATE_RECORD_OFFSET = 179;

        /**
         * Verbatim COBOL name of the {@code 05}-level group holding the change-action byte.
         */
        public static final String SCREEN_DATA_GROUP_NAME = "CARD-UPDATE-SCREEN-DATA";

        /**
         * Verbatim COBOL name of the {@code 05}-level record group.
         */
        public static final String CARD_UPDATE_RECORD_GROUP_NAME = "CARD-UPDATE-RECORD";

        /**
         * {@code CCUP-CHANGE-ACTION PIC X(1)} at offset {@value #CHANGE_ACTION_OFFSET}.
         */
        public static final FieldSpan CCUP_CHANGE_ACTION = FieldSpan.alphanumeric(
                ChangeAction.FIELD_NAME, CHANGE_ACTION_OFFSET, ChangeAction.RECORD_LENGTH);

        public static final RecordLayout LAYOUT = buildLayout();

        public CommArea {
            Objects.requireNonNull(changeAction, "CCUP-CHANGE-ACTION is required; its unset state is "
                    + "LOW-VALUES, which is a byte and not a Java null");
            Objects.requireNonNull(oldDetails, "CCUP-OLD-DETAILS is required");
            Objects.requireNonNull(newDetails, "CCUP-NEW-DETAILS is required");
            Objects.requireNonNull(cardUpdateRecord, "CARD-UPDATE-RECORD is required");
            if (oldDetails.group() != DetailGroup.OLD) {
                throw new IllegalArgumentException("The snapshot in the CCUP-OLD-DETAILS position is "
                        + "labelled " + oldDetails.group().groupName() + "; relabel it with "
                        + "asGroup(DetailGroup.OLD) rather than storing it under the wrong names");
            }
            if (newDetails.group() != DetailGroup.NEW) {
                throw new IllegalArgumentException("The snapshot in the CCUP-NEW-DETAILS position is "
                        + "labelled " + newDetails.group().groupName() + "; relabel it with "
                        + "asGroup(DetailGroup.NEW) rather than storing it under the wrong names");
            }
        }

        /**
         * The commarea as the program starts: {@code CCUP-CHANGE-ACTION} at its declared
         * {@code VALUE LOW-VALUES}, both snapshots all spaces, and the record initialised.
         *
         * @return the initial commarea
         */
        public static CommArea initialised() {
            return new CommArea(ChangeAction.initial(),
                    CardDetails.initialised(DetailGroup.OLD),
                    CardDetails.initialised(DetailGroup.NEW),
                    CardUpdateRecord.initialised());
        }

        /**
         * Reads a {@value #RECORD_LENGTH}-byte image back into a commarea.
         *
         * @param image exactly {@value #RECORD_LENGTH} bytes
         * @param codec the codec, carrying the code page explicitly
         * @return the commarea
         * @throws NullPointerException if either argument is {@code null}
         * @throws IllegalArgumentException if {@code image} is not {@value #RECORD_LENGTH} bytes, or a
         *     numeric span of the record holds a non-digit
         */
        public static CommArea decode(byte[] image, FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A codec carrying the image's code page is required to "
                    + "decode WS-THIS-PROGCOMMAREA; the platform default is never assumed");
            Objects.requireNonNull(image, "An image is required to decode WS-THIS-PROGCOMMAREA");
            FixedWidthRecord area = codec.wrap(image, LAYOUT);
            return new CommArea(ChangeAction.of(codec.readPicX(area, CCUP_CHANGE_ACTION)),
                    CardDetails.decode(area.readBytes(OLD_DETAILS_OFFSET,
                            CardDetails.RECORD_LENGTH), DetailGroup.OLD, codec),
                    CardDetails.decode(area.readBytes(NEW_DETAILS_OFFSET,
                            CardDetails.RECORD_LENGTH), DetailGroup.NEW, codec),
                    CardUpdateRecord.decode(area.readBytes(CARD_UPDATE_RECORD_OFFSET,
                            CardUpdateRecord.RECORD_LENGTH), codec));
        }

        /**
         * Reads a {@value #RECORD_LENGTH}-byte image back into a commarea, in a named code page.
         *
         * @param image exactly {@value #RECORD_LENGTH} bytes
         * @param charset the code page, stated explicitly
         * @return the commarea
         * @throws NullPointerException if either argument is {@code null}
         */
        public static CommArea decode(byte[] image, Charset charset) {
            return decode(image, new FixedWidthCodec(charset));
        }

        /**
         * Renders the commarea as exactly {@value #RECORD_LENGTH} bytes.
         *
         * @param codec the codec, carrying the code page explicitly
         * @return exactly {@value #RECORD_LENGTH} bytes
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public byte[] encode(FixedWidthCodec codec) {
            return toFixedWidthRecord(codec).toByteArray();
        }

        /**
         * Renders the commarea as exactly {@value #RECORD_LENGTH} bytes in a named code page.
         *
         * @param charset the code page, stated explicitly and never defaulted
         * @return exactly {@value #RECORD_LENGTH} bytes
         * @throws NullPointerException if {@code charset} is {@code null}
         */
        public byte[] encode(Charset charset) {
            return encode(new FixedWidthCodec(charset));
        }

        /**
         * Builds the record area by writing the change-action byte and then placing each sub-image at its
         * own offset.
         *
         * @param codec the codec, carrying the code page explicitly
         * @return an area of exactly {@value #RECORD_LENGTH} bytes
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public FixedWidthRecord toFixedWidthRecord(FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A codec carrying the target code page is required to "
                    + "serialise WS-THIS-PROGCOMMAREA; the platform default is never assumed");
            FixedWidthRecord area = codec.newRecord(LAYOUT);
            codec.writePicX(area, CCUP_CHANGE_ACTION, changeAction.value());
            area.writeBytes(OLD_DETAILS_OFFSET, oldDetails.encode(codec));
            area.writeBytes(NEW_DETAILS_OFFSET, newDetails.encode(codec));
            area.writeBytes(CARD_UPDATE_RECORD_OFFSET, cardUpdateRecord.encode(codec));
            return area;
        }

        /**
         * Every item of the commarea keyed by its verbatim COBOL name, in declaration order: the
         * change-action byte, then the two snapshots with their own names, then the record.
         *
         * @param codec the codec, which renders the record's two numeric items as zoned images
         * @return an unmodifiable, insertion-ordered map
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public Map<String, String> itemValues(FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A codec is required to render the commarea's items");
            Map<String, String> values = new LinkedHashMap<>();
            values.put(ChangeAction.FIELD_NAME, changeAction.value());
            values.putAll(oldDetails.itemValues());
            values.putAll(newDetails.itemValues());
            values.putAll(cardUpdateRecord.itemValues(codec));
            return Collections.unmodifiableMap(values);
        }

        /**
         * {@code SET <88-level> TO TRUE} on {@code CCUP-CHANGE-ACTION}.
         *
         * @param replacement the replacement state
         * @return a copy carrying it
         * @throws NullPointerException if {@code replacement} is {@code null}
         */
        public CommArea withChangeAction(ChangeAction replacement) {
            return new CommArea(replacement, oldDetails, newDetails, cardUpdateRecord);
        }

        /**
         * {@code MOVE … TO CCUP-OLD-DETAILS}, which is what {@code 9300-CHECK-CHANGE-IN-REC} does field by
         * field when it detects that the stored record changed under the screen.
         *
         * @param replacement the replacement snapshot; must be the {@link DetailGroup#OLD} group
         * @return a copy carrying it
         * @throws NullPointerException if {@code replacement} is {@code null}
         * @throws IllegalArgumentException if it is not the {@link DetailGroup#OLD} snapshot
         */
        public CommArea withOldDetails(CardDetails replacement) {
            return new CommArea(changeAction, replacement, newDetails, cardUpdateRecord);
        }

        /**
         * {@code MOVE … TO CCUP-NEW-DETAILS}, which is what the receive-map paragraphs do with what the
         * user typed.
         *
         * @param replacement the replacement snapshot; must be the {@link DetailGroup#NEW} group
         * @return a copy carrying it
         * @throws NullPointerException if {@code replacement} is {@code null}
         * @throws IllegalArgumentException if it is not the {@link DetailGroup#NEW} snapshot
         */
        public CommArea withNewDetails(CardDetails replacement) {
            return new CommArea(changeAction, oldDetails, replacement, cardUpdateRecord);
        }

        /**
         * Replaces the staged record, as {@code 9200-WRITE-PROCESSING} does immediately before
         * {@code EXEC CICS REWRITE}.
         *
         * @param replacement the replacement record
         * @return a copy carrying it
         * @throws NullPointerException if {@code replacement} is {@code null}
         */
        public CommArea withCardUpdateRecord(CardUpdateRecord replacement) {
            return new CommArea(changeAction, oldDetails, newDetails, replacement);
        }

        private static RecordLayout buildLayout() {
            List<FieldSpan> spans = new ArrayList<>();
            spans.add(CCUP_CHANGE_ACTION);
            spans.addAll(shifted(DetailGroup.OLD.layout(), OLD_DETAILS_OFFSET));
            spans.addAll(shifted(DetailGroup.NEW.layout(), NEW_DETAILS_OFFSET));
            spans.addAll(shifted(CardUpdateRecord.LAYOUT, CARD_UPDATE_RECORD_OFFSET));
            spans.add(FieldSpan.redefining(SCREEN_DATA_GROUP_NAME, CHANGE_ACTION_OFFSET,
                    ChangeAction.RECORD_LENGTH, PictureKind.ALPHANUMERIC));
            spans.add(FieldSpan.redefining(DetailGroup.OLD.groupName(), OLD_DETAILS_OFFSET,
                    CardDetails.RECORD_LENGTH, PictureKind.ALPHANUMERIC));
            spans.add(FieldSpan.redefining(DetailGroup.NEW.groupName(), NEW_DETAILS_OFFSET,
                    CardDetails.RECORD_LENGTH, PictureKind.ALPHANUMERIC));
            spans.add(FieldSpan.redefining(CARD_UPDATE_RECORD_GROUP_NAME,
                    CARD_UPDATE_RECORD_OFFSET, CardUpdateRecord.RECORD_LENGTH,
                    PictureKind.ALPHANUMERIC));
            return new RecordLayout(RECORD_LENGTH, spans);
        }

        private static List<FieldSpan> shifted(RecordLayout layout, int base) {
            List<FieldSpan> spans = new ArrayList<>(layout.spans().size());
            for (FieldSpan span : layout.spans()) {
                spans.add(new FieldSpan(span.name(),
                        span.offset() + base,
                        span.length(),
                        span.kind(),
                        span.initialValue(),
                        span.redefinition()));
            }
            return spans;
        }
    }

    static SensitiveDiagnostics.Disclosure disclosureOf(String fieldName) {
        if (fieldName == null) {
            return SensitiveDiagnostics.Disclosure.REDACTED_VALUE;
        }
        return switch (fieldName) {
            case "CARDSID" -> SensitiveDiagnostics.Disclosure.PAN;
            case "ACCTSID" -> SensitiveDiagnostics.Disclosure.IDENTIFIER;
            case "CRDNAME" -> SensitiveDiagnostics.Disclosure.TEXT;
            default -> SensitiveDiagnostics.Disclosure.PLAIN;
        };
    }

}
