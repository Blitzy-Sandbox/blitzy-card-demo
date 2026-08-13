package com.vsergeychik.carddemo.card.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.ResponseOnlyMembers;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.constraints.Size;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The inbound payload of {@code GET /api/cards/&#123;cardNum&#125;} - the credit-card detail screen, CSD
 * transaction {@code CCDL}, backed by {@code app/cbl/COCRDSLC.cbl} (887 lines).
 *
 * <p>The fifteen named entries are exactly: The two width columns are independent transcriptions of the
 * same contract - one from the mapset's {@code LENGTH=} operands, one from the symbolic map's
 * {@code PICTURE} clauses - and they agree field for field and in total.
 */
@JsonIgnoreProperties({
        ResponseOnlyMembers.NEXT_PROGRAM,
        ResponseOnlyMembers.NEXT_MAPSET,
        ResponseOnlyMembers.NEXT_MAP,
        ResponseOnlyMembers.SCREEN_METADATA})
public final class CardSelectRequest {
    /**
     * Width of {@code TRNNAMEI PIC X(4)}, {@code COCRDSL.CPY:24}; {@code TRNNAME LENGTH=4}.
     */
    public static final int TRNNAME_LENGTH = 4;

    /**
     * Width of {@code TITLE01I PIC X(40)}, {@code COCRDSL.CPY:30}; {@code TITLE01 LENGTH=40}.
     */
    public static final int TITLE01_LENGTH = 40;

    /**
     * Width of {@code CURDATEI PIC X(8)}, {@code COCRDSL.CPY:36}; {@code CURDATE LENGTH=8}.
     */
    public static final int CURDATE_LENGTH = 8;

    /**
     * Width of {@code PGMNAMEI PIC X(8)}, {@code COCRDSL.CPY:42}; {@code PGMNAME LENGTH=8}.
     */
    public static final int PGMNAME_LENGTH = 8;

    /**
     * Width of {@code TITLE02I PIC X(40)}, {@code COCRDSL.CPY:48}; {@code TITLE02 LENGTH=40}.
     */
    public static final int TITLE02_LENGTH = 40;

    /**
     * Width of {@code CURTIMEI PIC X(8)}, {@code COCRDSL.CPY:54}; {@code CURTIME LENGTH=8}.
     */
    public static final int CURTIME_LENGTH = 8;

    /**
     * Width of {@code ACCTSIDI PIC X(11)}, {@code COCRDSL.CPY:60}; {@code ACCTSID LENGTH=11}.
     */
    public static final int ACCTSID_LENGTH = 11;

    /**
     * Width of {@code CARDSIDI PIC X(16)}, {@code COCRDSL.CPY:66}; {@code CARDSID LENGTH=16}.
     */
    public static final int CARDSID_LENGTH = 16;

    /**
     * Width of {@code CRDNAMEI PIC X(50)}, {@code COCRDSL.CPY:72}; {@code CRDNAME LENGTH=50}.
     */
    public static final int CRDNAME_LENGTH = 50;

    /**
     * Width of {@code CRDSTCDI PIC X(1)}, {@code COCRDSL.CPY:78}; {@code CRDSTCD LENGTH=1}.
     */
    public static final int CRDSTCD_LENGTH = 1;

    /**
     * Width of {@code EXPMONI PIC X(2)}, {@code COCRDSL.CPY:84}; {@code EXPMON LENGTH=2}.
     */
    public static final int EXPMON_LENGTH = 2;

    /**
     * Width of {@code EXPYEARI PIC X(4)}, {@code COCRDSL.CPY:90}; {@code EXPYEAR LENGTH=4}.
     */
    public static final int EXPYEAR_LENGTH = 4;

    /**
     * Width of {@code INFOMSGI PIC X(40)}, {@code COCRDSL.CPY:96}; {@code INFOMSG LENGTH=40}.
     */
    public static final int INFOMSG_LENGTH = 40;

    /**
     * Width of {@code ERRMSGI PIC X(80)}, {@code COCRDSL.CPY:102}; {@code ERRMSG LENGTH=80}.
     */
    public static final int ERRMSG_LENGTH = 80;

    /**
     * Width of {@code FKEYSI PIC X(75)}, {@code COCRDSL.CPY:108}; {@code FKEYS LENGTH=75}.
     */
    public static final int FKEYS_LENGTH = 75;

    /**
     * Bytes in the {@code TIOAPFX=YES} prefix - {@code 02 FILLER PIC X(12)} at {@code COCRDSL.CPY:18},
     * repeated at line 110 as the first item of the output group.
     */
    public static final int TIOAPFX_LENGTH = 12;

    /**
     * Bytes in one {@code xxxL COMP PIC S9(4)} item: a binary halfword, two bytes, big-endian.
     */
    public static final int LENGTH_ITEM_LENGTH = 2;

    /**
     * Bytes in one {@code xxxF PICTURE X} flag item.
     */
    public static final int FLAG_ITEM_LENGTH = 1;

    /**
     * Bytes in one {@code 02 FILLER PICTURE X(4)} extended-attribute item.
     */
    public static final int EXTENDED_ATTRIBUTE_ITEM_LENGTH = 4;

    public static final int FIELD_OVERHEAD =
            LENGTH_ITEM_LENGTH + FLAG_ITEM_LENGTH + EXTENDED_ATTRIBUTE_ITEM_LENGTH;

    /**
     * Name-labelled {@code DFHMDF} entries in {@code app/bms/COCRDSL.bms}, of 31 entries in all.
     */
    public static final int FIELD_COUNT = 15;

    public static final int PAYLOAD_LENGTH = TRNNAME_LENGTH + TITLE01_LENGTH + CURDATE_LENGTH
            + PGMNAME_LENGTH + TITLE02_LENGTH + CURTIME_LENGTH + ACCTSID_LENGTH + CARDSID_LENGTH
            + CRDNAME_LENGTH + CRDSTCD_LENGTH + EXPMON_LENGTH + EXPYEAR_LENGTH + INFOMSG_LENGTH
            + ERRMSG_LENGTH + FKEYS_LENGTH;

    public static final int GROUP_LENGTH =
            TIOAPFX_LENGTH + FIELD_COUNT * FIELD_OVERHEAD + PAYLOAD_LENGTH;

    private static final char SPACE = ' ';

    private static final byte LOW_VALUE_BYTE = 0x00;

    static {
        if (FIELD_OVERHEAD != 7) {
            throw new IllegalStateException("Each COCRDSL.CPY input field carries 2 + 1 + 4 = 7 bytes "
                    + "of length, flag and extended-attribute storage before its data, but "
                    + "FIELD_OVERHEAD computes to " + FIELD_OVERHEAD);
        }
        if (PAYLOAD_LENGTH != 387) {
            throw new IllegalStateException("The fifteen xxxI PICTURE widths of app/cpy-bms/"
                    + "COCRDSL.CPY sum to 387, which is also the sum of the fifteen LENGTH= operands "
                    + "of app/bms/COCRDSL.bms, but the width constants sum to " + PAYLOAD_LENGTH);
        }
        if (GROUP_LENGTH != 504) {
            throw new IllegalStateException("The CCRDSLAI group is 12 + 15 * 7 + 387 = 504 bytes, but "
                    + "the constants compute " + GROUP_LENGTH);
        }
        verifyFieldStrides();
    }

    private static void verifyFieldStrides() {
        ScreenField[] fields = ScreenField.values();
        if (fields.length != FIELD_COUNT) {
            throw new IllegalStateException("app/bms/COCRDSL.bms carries " + FIELD_COUNT
                    + " name-labelled DFHMDF entries, so ScreenField must declare " + FIELD_COUNT
                    + " constants, but it declares " + fields.length);
        }
        int cursor = TIOAPFX_LENGTH;
        int widths = 0;
        for (ScreenField field : fields) {
            if (field.lengthItemOffset() != cursor) {
                throw new IllegalStateException("Field " + field.label() + " declares its data at "
                        + "offset " + field.dataOffset() + ", which places its xxxL item at "
                        + field.lengthItemOffset() + "; the preceding storage ends at " + cursor
                        + ", so the CCRDSLAI group would have a gap or an overlap there");
            }
            cursor = field.endOffsetExclusive();
            widths += field.length();
        }
        if (widths != PAYLOAD_LENGTH) {
            throw new IllegalStateException("The fifteen ScreenField widths sum to " + widths
                    + " but the width constants sum to " + PAYLOAD_LENGTH);
        }
        if (cursor != GROUP_LENGTH) {
            throw new IllegalStateException("Walking the fifteen field strides from the end of the "
                    + TIOAPFX_LENGTH + "-byte TIOAPFX prefix ends at " + cursor
                    + ", not at the declared group length of " + GROUP_LENGTH);
        }
    }

    /**
     * The message every width constraint below declares, and the only text a rejected field publishes.
     */
    public static final String PUBLIC_LENGTH_MESSAGE = "must be at most {max} characters";

    @JsonProperty("trnname")
    @Size(max = TRNNAME_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String trnname;

    @JsonProperty("title01")
    @Size(max = TITLE01_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String title01;

    @JsonProperty("curdate")
    @Size(max = CURDATE_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String curdate;

    @JsonProperty("pgmname")
    @Size(max = PGMNAME_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String pgmname;

    @JsonProperty("title02")
    @Size(max = TITLE02_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String title02;

    @JsonProperty("curtime")
    @Size(max = CURTIME_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String curtime;

    @JsonProperty("acctsid")
    @Size(max = ACCTSID_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String acctsid;

    @JsonProperty("cardsid")
    @Size(max = CARDSID_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String cardsid;

    @JsonProperty("crdname")
    @Size(max = CRDNAME_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String crdname;

    @JsonProperty("crdstcd")
    @Size(max = CRDSTCD_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String crdstcd;

    @JsonProperty("expmon")
    @Size(max = EXPMON_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String expmon;

    @JsonProperty("expyear")
    @Size(max = EXPYEAR_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String expyear;

    @JsonProperty("infomsg")
    @Size(max = INFOMSG_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String infomsg;

    @JsonProperty("errmsg")
    @Size(max = ERRMSG_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String errmsg;

    @JsonProperty("fkeys")
    @Size(max = FKEYS_LENGTH, message = PUBLIC_LENGTH_MESSAGE)
    private String fkeys;

    @JsonProperty("cardScreenState")
    private CardScreenState cardScreenState;

    @JsonProperty("navigationContext")
    private NavigationContext navigationContext;

    @JsonProperty("thisProgCommarea")
    private ThisProgCommarea thisProgCommarea = ThisProgCommarea.initialized();

    private final EnumMap<ScreenField, ScreenFieldMetadata> metadata =
            new EnumMap<>(ScreenField.class);

    private static final String REDACTED_VALUE = "[REDACTED]";

    private static final Set<ScreenField> REDACTED_FIELDS =
            Collections.unmodifiableSet(EnumSet.of(ScreenField.ACCTSID, ScreenField.CARDSID,
                    ScreenField.CRDNAME));

    /**
     * A freshly initialised map area: every one of the fifteen fields a run of spaces of its declared
     * width, every metadata holder unset, a new {@link CardScreenState} work area and an
     * {@link NavigationContext#empty()} communication area.
     */
    public CardSelectRequest() {
        for (ScreenField field : ScreenField.values()) {
            metadata.put(field, new ScreenFieldMetadata());
        }
        initializeState();
    }

    public CardSelectRequest(CardSelectRequest other) {
        Objects.requireNonNull(other, "A request is required to copy it");
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
        this.infomsg = other.infomsg;
        this.errmsg = other.errmsg;
        this.fkeys = other.fkeys;
        this.cardScreenState = new CardScreenState(other.cardScreenState);
        this.navigationContext = other.navigationContext;
        this.thisProgCommarea = other.thisProgCommarea;
        for (ScreenField field : ScreenField.values()) {
            metadata.put(field, new ScreenFieldMetadata(other.metadata.get(field)));
        }
    }

    /**
     * A request carrying only the two fields {@code COCRDSLC} actually reads from the input group.
     *
     * <p>{@code app/cbl/COCRDSLC.cbl} takes exactly two values off this map - {@code ACCTSIDI OF CCRDSLAI}
     * at lines 615 to 619 and {@code CARDSIDI OF CCRDSLAI} at lines 622 to 626 - and derives everything
     * else.
     *
     * @param acctsid the {@code ACCTSID} search value; {@code null} is taken as spaces
     * @param cardsid the {@code CARDSID} search value; {@code null} is taken as spaces
     * @return a request whose remaining fields are at their initialised values, never {@code null}
     */
    public static CardSelectRequest withSearchCriteria(String acctsid, String cardsid) {
        CardSelectRequest request = new CardSelectRequest();
        request.setAcctsid(acctsid);
        request.setCardsid(cardsid);
        return request;
    }

    /**
     * Returns every field, every metadata holder and both carriers to their initialised state - the
     * {@code INITIALIZE} a CICS program performs before it starts populating a map.
     */
    public void initializeMapArea() {
        initializeState();
        for (ScreenFieldMetadata holder : metadata.values()) {
            holder.reset();
        }
    }

    private void initializeState() {
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
        this.infomsg = spaces(INFOMSG_LENGTH);
        this.errmsg = spaces(ERRMSG_LENGTH);
        this.fkeys = spaces(FKEYS_LENGTH);
        this.cardScreenState = new CardScreenState();
        this.navigationContext = null;
        this.thisProgCommarea = ThisProgCommarea.initialized();
    }

    /**
     * COBOL's {@code SPACES} figurative constant, repeated to a width.
     *
     * @param length how many spaces; zero yields an empty string
     * @return a string of exactly {@code length} spaces, never {@code null}
     * @throws IllegalArgumentException if {@code length} is negative
     */
    public static String spaces(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("A COCRDSL field cannot be " + length + " characters "
                    + "wide; every one of the fifteen is declared PIC X(n) with n of at least 1");
        }
        return String.valueOf(SPACE).repeat(length);
    }

    private static String orSpaces(String value, int width) {
        return value == null ? spaces(width) : value;
    }

    // It does not pad and it does not truncate, because a setter is not a COBOL MOVE: the MOVE rule is
    // applied only where it is asked for, by FixedWidthCodec.movePicX, through image(), normalize() and
    // toGroupImage(). A PIC X field's trailing spaces are part of its value and a field-by-field parity
    // diff compares them.

    /**
     * {@code TRNNAMEI}, the transaction identifier the screen was reached under.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTrnname() {
        return trnname;
    }

    public void setTrnname(String trnname) {
        this.trnname = orSpaces(trnname, TRNNAME_LENGTH);
    }

    public String getTitle01() {
        return title01;
    }

    public void setTitle01(String title01) {
        this.title01 = orSpaces(title01, TITLE01_LENGTH);
    }

    /**
     * {@code CURDATEI}, the current date as the header carries it.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getCurdate() {
        return curdate;
    }

    public void setCurdate(String curdate) {
        this.curdate = orSpaces(curdate, CURDATE_LENGTH);
    }

    /**
     * {@code PGMNAMEI}, the program name the header carries.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getPgmname() {
        return pgmname;
    }

    public void setPgmname(String pgmname) {
        this.pgmname = orSpaces(pgmname, PGMNAME_LENGTH);
    }

    public String getTitle02() {
        return title02;
    }

    public void setTitle02(String title02) {
        this.title02 = orSpaces(title02, TITLE02_LENGTH);
    }

    /**
     * {@code CURTIMEI}, the current time as the header carries it.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getCurtime() {
        return curtime;
    }

    public void setCurtime(String curtime) {
        this.curtime = orSpaces(curtime, CURTIME_LENGTH);
    }

    /**
     * {@code ACCTSIDI}, the account-number search criterion - one of the two fields the operator types and
     * {@code app/cbl/COCRDSLC.cbl:615-619} reads.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAcctsid() {
        return acctsid;
    }

    public void setAcctsid(String acctsid) {
        this.acctsid = orSpaces(acctsid, ACCTSID_LENGTH);
    }

    /**
     * {@code CARDSIDI}, the card-number search criterion - the other typed field, read at
     * {@code app/cbl/COCRDSLC.cbl:622-626}.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getCardsid() {
        return cardsid;
    }

    public void setCardsid(String cardsid) {
        this.cardsid = orSpaces(cardsid, CARDSID_LENGTH);
    }

    public String getCrdname() {
        return crdname;
    }

    public void setCrdname(String crdname) {
        this.crdname = orSpaces(crdname, CRDNAME_LENGTH);
    }

    /**
     * {@code CRDSTCDI}, the single-character active-status code.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getCrdstcd() {
        return crdstcd;
    }

    public void setCrdstcd(String crdstcd) {
        this.crdstcd = orSpaces(crdstcd, CRDSTCD_LENGTH);
    }

    public String getExpmon() {
        return expmon;
    }

    public void setExpmon(String expmon) {
        this.expmon = orSpaces(expmon, EXPMON_LENGTH);
    }

    public String getExpyear() {
        return expyear;
    }

    public void setExpyear(String expyear) {
        this.expyear = orSpaces(expyear, EXPYEAR_LENGTH);
    }

    /**
     * {@code INFOMSGI}, the informational message line - {@value #INFOMSG_LENGTH} characters here, where
     * {@code COCRDLI} declares 45.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getInfomsg() {
        return infomsg;
    }

    public void setInfomsg(String infomsg) {
        this.infomsg = orSpaces(infomsg, INFOMSG_LENGTH);
    }

    /**
     * {@code ERRMSGI}, the error message line - {@value #ERRMSG_LENGTH} characters here, where
     * {@code COCRDLI} declares 78.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getErrmsg() {
        return errmsg;
    }

    public void setErrmsg(String errmsg) {
        this.errmsg = orSpaces(errmsg, ERRMSG_LENGTH);
    }

    /**
     * {@code FKEYSI}, the function-key legend - {@value #FKEYS_LENGTH} characters here, 21 on
     * {@code COCRDUP}, and absent from {@code COCRDLI}.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getFkeys() {
        return fkeys;
    }

    public void setFkeys(String fkeys) {
        this.fkeys = orSpaces(fkeys, FKEYS_LENGTH);
    }

    /**
     * The value of one field, chosen by its {@link ScreenField} constant.
     *
     * @param field which field to read
     * @return the stored value, untrimmed; never {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public String value(ScreenField field) {
        Objects.requireNonNull(field, "A ScreenField is required to read a COCRDSL field by name");
        return switch (field) {
            case TRNNAME -> trnname;
            case TITLE01 -> title01;
            case CURDATE -> curdate;
            case PGMNAME -> pgmname;
            case TITLE02 -> title02;
            case CURTIME -> curtime;
            case ACCTSID -> acctsid;
            case CARDSID -> cardsid;
            case CRDNAME -> crdname;
            case CRDSTCD -> crdstcd;
            case EXPMON -> expmon;
            case EXPYEAR -> expyear;
            case INFOMSG -> infomsg;
            case ERRMSG -> errmsg;
            case FKEYS -> fkeys;
        };
    }

    /**
     * Stores one field verbatim, chosen by its {@link ScreenField} constant.
     *
     * @param field which field to write
     * @param value the value; {@code null} is taken as spaces of the field's declared width
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public void setValue(ScreenField field, String value) {
        Objects.requireNonNull(field, "A ScreenField is required to write a COCRDSL field by name");
        switch (field) {
            case TRNNAME -> setTrnname(value);
            case TITLE01 -> setTitle01(value);
            case CURDATE -> setCurdate(value);
            case PGMNAME -> setPgmname(value);
            case TITLE02 -> setTitle02(value);
            case CURTIME -> setCurtime(value);
            case ACCTSID -> setAcctsid(value);
            case CARDSID -> setCardsid(value);
            case CRDNAME -> setCrdname(value);
            case CRDSTCD -> setCrdstcd(value);
            case EXPMON -> setExpmon(value);
            case EXPYEAR -> setExpyear(value);
            case INFOMSG -> setInfomsg(value);
            case ERRMSG -> setErrmsg(value);
            case FKEYS -> setFkeys(value);
        }
    }

    @JsonIgnore
    public ScreenFieldMetadata metadata(ScreenField field) {
        Objects.requireNonNull(field, "A ScreenField is required to address a field's xxxL and xxxA "
                + "items");
        return metadata.get(field);
    }

    /**
     * All fifteen metadata holders, keyed by field and iterating in copybook storage order.
     *
     * @return an unmodifiable view over the fifteen live holders, never {@code null}
     */
    @JsonIgnore
    public Map<ScreenField, ScreenFieldMetadata> metadata() {
        return Collections.unmodifiableMap(metadata);
    }

    public CardScreenState getCardScreenState() {
        return cardScreenState;
    }

    public void setCardScreenState(CardScreenState cardScreenState) {
        this.cardScreenState = cardScreenState == null ? new CardScreenState() : cardScreenState;
    }

    /**
     * The {@code CARDDEMO-COMMAREA} this request carries.
     *
     * @return the communication area, never {@code null}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    /**
     * Replaces the {@code CARDDEMO-COMMAREA}, storing {@code null} verbatim.
     *
     * @param navigationContext the communication area, or {@code null} where none travelled with the
     *     request
     */
    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = navigationContext;
    }

    /**
     * {@code WS-THIS-PROGCOMMAREA} as it arrived - the twelve bytes {@code :276-278} restores.
     *
     * @return the trailer; never {@code null}, and {@link ThisProgCommarea#initialized()} when the payload
     *     stated none
     */
    public ThisProgCommarea getThisProgCommarea() {
        return thisProgCommarea;
    }

    /**
     * Sets {@code WS-THIS-PROGCOMMAREA}.
     *
     * @param thisProgCommarea the trailer, or {@code null} for the initialised twelve spaces
     */
    public void setThisProgCommarea(ThisProgCommarea thisProgCommarea) {
        this.thisProgCommarea =
                thisProgCommarea == null ? ThisProgCommarea.initialized() : thisProgCommarea;
    }

    /**
     * Whether a communication area travelled with this request - the Java reading of {@code EIBCALEN} being
     * non-zero at {@code app/cbl/COCRDSLC.cbl:268}.
     *
     * @return {@code true} when {@link #getNavigationContext()} is present
     */
    @JsonIgnore
    public boolean hasNavigationContext() {
        return navigationContext != null;
    }

    /**
     * The length CICS would report in {@code EIBCALEN}: {@link #PASSED_COMMAREA_LENGTH} when a
     * communication area travelled with this request and {@code 0} when none did.
     *
     * @return {@link #PASSED_COMMAREA_LENGTH} or {@code 0}
     */
    @JsonIgnore
    public int commareaLength() {
        return hasNavigationContext() ? PASSED_COMMAREA_LENGTH : 0;
    }

    /**
     * The length of the area this screen passes and receives: {@code CARDDEMO-COMMAREA} plus
     * {@code WS-THIS-PROGCOMMAREA}, {@value NavigationContext#COMMAREA_LENGTH} +
     * {@value ThisProgCommarea#RECORD_LENGTH} = {@link #PASSED_COMMAREA_LENGTH} bytes.
     */
    public static final int PASSED_COMMAREA_LENGTH =
            NavigationContext.COMMAREA_LENGTH + ThisProgCommarea.RECORD_LENGTH;

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
     * Whether this is first entry - {@code 88 CDEMO-PGM-ENTER VALUE 0}.
     *
     * @return {@code true} when a communication area travelled and its carried program context is
     *     {@value NavigationContext#PGM_CONTEXT_ENTER}
     */
    @JsonIgnore
    public boolean isEnter() {
        return hasNavigationContext() && navigationContext.isEnter();
    }

    /**
     * Whether this is re-entry - {@code 88 CDEMO-PGM-REENTER VALUE 1}.
     *
     * <p>Deliberately not written as the negation of {@link #isEnter()}: {@code CDEMO-PGM-CONTEXT} is
     * {@code PIC 9(01)} and can hold any digit, so a context of 9 satisfies neither condition - and neither
     * does an absent communication area.
     *
     * @return {@code true} when a communication area travelled and its carried program context is
     *     {@value NavigationContext#PGM_CONTEXT_REENTER}
     */
    @JsonIgnore
    public boolean isReenter() {
        return hasNavigationContext() && navigationContext.isReenter();
    }

    /**
     * One field as a {@code PIC X} image of exactly its declared width: padded on the right with spaces
     * when the stored value is short, truncated on the right when it is long.
     *
     * <p>The truncation direction is the COBOL rule for an alphanumeric receiver - filled from the leftmost
     * character position, with the overflow discarded - and it is
     * {@link FixedWidthCodec#movePicX(String, int)} that applies it.
     *
     * @param field which field to render
     * @param codec the codec whose {@code PIC X} move rule and charset apply
     * @return an image of exactly {@code field.length()} characters, never {@code null}
     * @throws NullPointerException if {@code field} or {@code codec} is {@code null}
     */
    public String image(ScreenField field, FixedWidthCodec codec) {
        Objects.requireNonNull(field, "A ScreenField is required to render a field image");
        Objects.requireNonNull(codec, "A FixedWidthCodec is required: the PIC X move rule lives there "
                + "and is never reimplemented here");
        return codec.movePicX(value(field), field.length());
    }

    /**
     * Applies the {@code PIC X} move rule to all fifteen fields in place, leaving every one at exactly its
     * declared width.
     *
     * @param codec the codec whose {@code PIC X} move rule applies
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public void normalize(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to normalise the fifteen COCRDSL "
                + "fields to their declared widths");
        for (ScreenField field : ScreenField.values()) {
            setValue(field, image(field, codec));
        }
    }

    /**
     * The whole {@code CCRDSLAI} input group as exactly {@link #GROUP_LENGTH} bytes.
     *
     * <p>The halfword is written big-endian, which is how a mainframe halfword is laid out, so
     * {@value ScreenFieldMetadata#CURSOR_HERE} renders as {@code 0xFFFF} - two's complement, exactly as
     * {@code COMP PIC S9(4)} storage holds it.
     *
     * @param codec the codec supplying the {@code PIC X} move rule and the charset
     * @return a new array of exactly {@link #GROUP_LENGTH} bytes, never {@code null}
     * @throws NullPointerException if {@code codec} is {@code null}
     * @throws IllegalArgumentException if the codec's charset does not encode this data one byte per
     *     character
     */
    public byte[] toGroupImage(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to render the CCRDSLAI group "
                + "image: it supplies both the PIC X move rule and the charset");
        Charset charset = codec.charset();
        byte[] group = new byte[GROUP_LENGTH];
        writeCharacters(group, 0, spaces(TIOAPFX_LENGTH), TIOAPFX_LENGTH, charset,
                "the " + TIOAPFX_LENGTH + "-byte TIOAPFX prefix");
        for (ScreenField field : ScreenField.values()) {
            ScreenFieldMetadata holder = metadata.get(field);
            int lengthItem = holder.getLength();
            group[field.lengthItemOffset()] = (byte) ((lengthItem >> 8) & 0xFF);
            group[field.lengthItemOffset() + 1] = (byte) (lengthItem & 0xFF);
            group[field.flagItemOffset()] = holder.getAttribute();
            Arrays.fill(group,
                    field.extendedAttributeItemOffset(),
                    field.extendedAttributeItemOffset() + EXTENDED_ATTRIBUTE_ITEM_LENGTH,
                    LOW_VALUE_BYTE);
            writeCharacters(group, field.dataOffset(), image(field, codec), field.length(), charset,
                    field.describe());
        }
        return group;
    }

    /**
     * Reads a {@link #GROUP_LENGTH}-byte {@code CCRDSLAI} image back into a request.
     *
     * @param groupImage the {@link #GROUP_LENGTH}-byte input group; read, never retained
     * @param codec the codec supplying the charset
     * @return a request carrying the image's fifteen fields and their metadata, never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if {@code groupImage} is not exactly {@link #GROUP_LENGTH} bytes, or
     *     holds a length item outside the range {@code COMP PIC S9(4)} can represent
     */
    public static CardSelectRequest fromGroupImage(byte[] groupImage, FixedWidthCodec codec) {
        Objects.requireNonNull(groupImage, "A group image is required to read a CCRDSLAI area");
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to read a CCRDSLAI area: it "
                + "supplies the charset the field data is encoded in");
        if (groupImage.length != GROUP_LENGTH) {
            throw new IllegalArgumentException("The CCRDSLAI group of app/cpy-bms/COCRDSL.CPY is "
                    + GROUP_LENGTH + " bytes - " + TIOAPFX_LENGTH + " of TIOAPFX prefix, plus "
                    + FIELD_COUNT + " fields at " + FIELD_OVERHEAD + " bytes of overhead each, plus "
                    + PAYLOAD_LENGTH + " bytes of data - but this image is " + groupImage.length
                    + " byte(s)");
        }
        Charset charset = codec.charset();
        CardSelectRequest request = new CardSelectRequest();
        for (ScreenField field : ScreenField.values()) {
            int lengthItem = decodeHalfword(groupImage, field);
            ScreenFieldMetadata holder = request.metadata.get(field);
            holder.setLength(lengthItem);
            holder.setAttribute(groupImage[field.flagItemOffset()]);
            request.setValue(field, new FixedWidthRecord.Transcoder(charset).decode(groupImage,
                    field.dataOffset(), field.length(), "the CCRDSLAI item " + field.name()));
        }
        return request;
    }

    /**
     * Reads one field's {@code xxxL COMP PIC S9(4)} item from a group image as a big-endian, signed
     * halfword, and rejects a value the {@code PICTURE} cannot represent.
     *
     * @param groupImage the group image to read from
     * @param field which field's length item to read
     * @return the halfword value
     * @throws IllegalArgumentException if the value is outside the {@code PIC S9(4)} range
     */
    private static int decodeHalfword(byte[] groupImage, ScreenField field) {
        int offset = field.lengthItemOffset();
        int halfword = (short) ((groupImage[offset] << 8) | (groupImage[offset + 1] & 0xFF));
        if (halfword < ScreenFieldMetadata.LENGTH_ITEM_MIN
                || halfword > ScreenFieldMetadata.LENGTH_ITEM_MAX) {
            throw new IllegalArgumentException("The xxxL item of " + field.describe() + " reads "
                    + halfword + " at offset " + offset + ", which COMP PIC S9(4) cannot represent: it "
                    + "holds " + ScreenFieldMetadata.LENGTH_ITEM_MIN + " to "
                    + ScreenFieldMetadata.LENGTH_ITEM_MAX);
        }
        return halfword;
    }

    /**
     * Encodes a {@code PIC X} image into a group image at a given offset, insisting that it occupy exactly
     * the declared number of bytes.
     *
     * @param group the group image being built
     * @param offset where the item begins
     * @param image the item's value, already at its declared character width
     * @param declaredWidth the item's declared width in bytes
     * @param charset the charset to encode with, from {@link FixedWidthCodec#charset()}
     * @param what how to describe the item in a failure message
     * @throws IllegalArgumentException if the encoding is not exactly {@code declaredWidth} bytes
     */
    private static void writeCharacters(byte[] group,
                                        int offset,
                                        String image,
                                        int declaredWidth,
                                        Charset charset,
                                        String what) {
        byte[] encoded = FixedWidthRecord.encodeText(image, charset, what);
        if (encoded.length != declaredWidth) {
            throw new IllegalArgumentException("Charset " + charset.name() + " encodes " + what
                    + " to " + encoded.length + " byte(s) where the copybook declares "
                    + declaredWidth + "; a PIC X(n) item is n bytes, so the CCRDSLAI group can only be "
                    + "rendered with a single-byte code page such as IBM037 or US-ASCII");
        }
        System.arraycopy(encoded, 0, group, offset, declaredWidth);
    }

    /**
     * Value equality over the fifteen fields, both conversation-state carriers and all fifteen metadata
     * holders.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a request holding the same values throughout
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CardSelectRequest that)) {
            return false;
        }
        return trnname.equals(that.trnname)
                && title01.equals(that.title01)
                && curdate.equals(that.curdate)
                && pgmname.equals(that.pgmname)
                && title02.equals(that.title02)
                && curtime.equals(that.curtime)
                && acctsid.equals(that.acctsid)
                && cardsid.equals(that.cardsid)
                && crdname.equals(that.crdname)
                && crdstcd.equals(that.crdstcd)
                && expmon.equals(that.expmon)
                && expyear.equals(that.expyear)
                && infomsg.equals(that.infomsg)
                && errmsg.equals(that.errmsg)
                && fkeys.equals(that.fkeys)
                && cardScreenState.equals(that.cardScreenState)
                && Objects.equals(navigationContext, that.navigationContext)
                && metadata.equals(that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(trnname, title01, curdate, pgmname, title02, curtime, acctsid, cardsid,
                crdname, crdstcd, expmon, expyear, infomsg, errmsg, fkeys, cardScreenState,
                navigationContext, metadata);
    }

    /**
     * A diagnostic rendering naming every field by its {@code DFHMDF} label.
     *
     * @return a single-line rendering of every field, both carriers and all fifteen metadata holders
     */
    @Override
    public String toString() {
        StringBuilder rendered = new StringBuilder("CardSelectRequest[");
        for (ScreenField field : ScreenField.values()) {
            rendered.append(field.label())
                    .append("='")
                    .append(SensitiveDiagnostics.render(disclosureOf(field), value(field)))
                    .append("' ")
                    .append(metadata.get(field))
                    .append(", ");
        }
        return rendered.append("cardScreenState=")
                .append(cardScreenState)
                .append(", navigationContext=")
                .append(navigationContext)
                .append(']')
                .toString();
    }

    /**
     * One of the fifteen name-labelled {@code DFHMDF} fields of {@code app/bms/COCRDSL.bms}, in the order
     * the mapset declares them - which is also the order {@code app/cpy-bms/COCRDSL.CPY} lays their storage
     * down, and the order they appear on the 24 x 80 screen reading top to bottom.
     */
    public enum ScreenField {
        /**
         * {@code TRNNAME} - the transaction identifier, {@code TRNNAMEI PIC X(4)}.
         */
        TRNNAME("TRNNAME", "TRNNAMEI", TRNNAME_LENGTH, 24, 34, 1, 7, 19),

        /**
         * {@code TITLE01} - the first title line, {@code TITLE01I PIC X(40)}.
         */
        TITLE01("TITLE01", "TITLE01I", TITLE01_LENGTH, 30, 38, 1, 21, 30),

        /**
         * {@code CURDATE} - the current date, {@code CURDATEI PIC X(8)}.
         */
        CURDATE("CURDATE", "CURDATEI", CURDATE_LENGTH, 36, 47, 1, 71, 77),

        /**
         * {@code PGMNAME} - the program name, {@code PGMNAMEI PIC X(8)}.
         */
        PGMNAME("PGMNAME", "PGMNAMEI", PGMNAME_LENGTH, 42, 57, 2, 7, 92),

        /**
         * {@code TITLE02} - the second title line, {@code TITLE02I PIC X(40)}.
         */
        TITLE02("TITLE02", "TITLE02I", TITLE02_LENGTH, 48, 61, 2, 21, 107),

        /**
         * {@code CURTIME} - the current time, {@code CURTIMEI PIC X(8)}.
         */
        CURTIME("CURTIME", "CURTIMEI", CURTIME_LENGTH, 54, 70, 2, 71, 154),

        /**
         * {@code ACCTSID} - the account-number search field, {@code ACCTSIDI PIC X(11)}.
         */
        ACCTSID("ACCTSID", "ACCTSIDI", ACCTSID_LENGTH, 60, 84, 7, 45, 169),

        /**
         * {@code CARDSID} - the card-number search field, {@code CARDSIDI PIC X(16)}.
         */
        CARDSID("CARDSID", "CARDSIDI", CARDSID_LENGTH, 66, 96, 8, 45, 187),

        /**
         * {@code CRDNAME} - the embossed name, {@code CRDNAMEI PIC X(50)}.
         */
        CRDNAME("CRDNAME", "CRDNAMEI", CRDNAME_LENGTH, 72, 107, 11, 25, 210),

        /**
         * {@code CRDSTCD} - the active-status code, {@code CRDSTCDI PIC X(1)}.
         */
        CRDSTCD("CRDSTCD", "CRDSTCDI", CRDSTCD_LENGTH, 78, 116, 13, 25, 267),

        /**
         * {@code EXPMON} - the expiry month, {@code EXPMONI PIC X(2)}.
         */
        EXPMON("EXPMON", "EXPMONI", EXPMON_LENGTH, 84, 126, 15, 25, 275),

        /**
         * {@code EXPYEAR} - the expiry year, {@code EXPYEARI PIC X(4)}.
         */
        EXPYEAR("EXPYEAR", "EXPYEARI", EXPYEAR_LENGTH, 90, 133, 15, 30, 284),

        /**
         * {@code INFOMSG} - the informational message line, {@code INFOMSGI PIC X(40)}.
         */
        INFOMSG("INFOMSG", "INFOMSGI", INFOMSG_LENGTH, 96, 139, 20, 25, 295),

        /**
         * {@code ERRMSG} - the error message line, {@code ERRMSGI PIC X(80)}.
         */
        ERRMSG("ERRMSG", "ERRMSGI", ERRMSG_LENGTH, 102, 144, 23, 1, 342),

        /**
         * {@code FKEYS} - the function-key legend, {@code FKEYSI PIC X(75)}.
         */
        FKEYS("FKEYS", "FKEYSI", FKEYS_LENGTH, 108, 148, 24, 1, 429);

        private final String label;

        private final String symbolicItemName;

        private final int length;

        private final int copybookLine;

        private final int mapsetLine;

        private final int screenRow;

        private final int screenColumn;

        private final int dataOffset;

        ScreenField(String label,
                    String symbolicItemName,
                    int length,
                    int copybookLine,
                    int mapsetLine,
                    int screenRow,
                    int screenColumn,
                    int dataOffset) {
            this.label = label;
            this.symbolicItemName = symbolicItemName;
            this.length = length;
            this.copybookLine = copybookLine;
            this.mapsetLine = mapsetLine;
            this.screenRow = screenRow;
            this.screenColumn = screenColumn;
            this.dataOffset = dataOffset;
        }

        /**
         * The field's {@code DFHMDF} label, spelled exactly as {@code app/bms/COCRDSL.bms} spells it.
         *
         * @return the label, for example {@code CARDSID}; never {@code null}
         */
        public String label() {
            return label;
        }

        /**
         * The input item's name in {@code app/cpy-bms/COCRDSL.CPY} - the label with the {@code I} suffix
         * BMS appends for the input group.
         *
         * @return the symbolic-map item name, for example {@code CARDSIDI}; never {@code null}
         */
        public String symbolicItemName() {
            return symbolicItemName;
        }

        /**
         * The field's declared width in characters, from its {@code xxxI PICTURE} clause and equally from
         * its {@code DFHMDF LENGTH=} operand.
         *
         * @return the width, at least 1
         */
        public int length() {
            return length;
        }

        /**
         * The line of {@code app/cpy-bms/COCRDSL.CPY} declaring this field's {@code xxxI} item.
         *
         * @return a one-based line number
         */
        public int copybookLine() {
            return copybookLine;
        }

        /**
         * The line of {@code app/bms/COCRDSL.bms} opening this field's {@code DFHMDF} entry.
         *
         * @return a one-based line number
         */
        public int mapsetLine() {
            return mapsetLine;
        }

        /**
         * The field's screen row, from the first operand of its {@code POS=} clause.
         *
         * @return a one-based row between 1 and 24, the screen being {@code SIZE=(24,80)}
         */
        public int screenRow() {
            return screenRow;
        }

        /**
         * The field's screen column, from the second operand of its {@code POS=} clause.
         *
         * @return a one-based column between 1 and 80
         */
        public int screenColumn() {
            return screenColumn;
        }

        /**
         * Offset of this field's {@code xxxI} data within the {@value CardSelectRequest#GROUP_LENGTH} byte
         * group image.
         *
         * @return a zero-based offset
         */
        public int dataOffset() {
            return dataOffset;
        }

        /**
         * Offset of this field's {@code xxxL COMP PIC S9(4)} length halfword, which is where the field's
         * storage begins.
         *
         * @return a zero-based offset, {@value CardSelectRequest#FIELD_OVERHEAD} bytes before
         *     {@link #dataOffset()}
         */
        public int lengthItemOffset() {
            return dataOffset - FIELD_OVERHEAD;
        }

        /**
         * Offset of this field's {@code xxxF} flag byte, which {@code 03 xxxA PICTURE X} redefines.
         *
         * @return a zero-based offset
         */
        public int flagItemOffset() {
            return lengthItemOffset() + LENGTH_ITEM_LENGTH;
        }

        /**
         * Offset of this field's {@code 02 FILLER PICTURE X(4)} extended-attribute item - the four bytes
         * the output group names {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV}.
         *
         * @return a zero-based offset
         */
        public int extendedAttributeItemOffset() {
            return flagItemOffset() + FLAG_ITEM_LENGTH;
        }

        /**
         * The offset one past this field's last data byte, which is where the next field's storage begins -
         * and, for {@link #FKEYS}, the group length itself.
         *
         * @return a zero-based exclusive end offset
         */
        public int endOffsetExclusive() {
            return dataOffset + length;
        }

        /**
         * A one-line, reviewable summary of this field's provenance, for a diagnostic message or a failing
         * parity assertion.
         *
         * @return for example
         *     {@code CARDSID CARDSIDI PIC X(16) COCRDSL.CPY:66 COCRDSL.bms:96 POS=(8,45) offset 187..203}
         */
        public String describe() {
            return label + " " + symbolicItemName + " PIC X(" + length + ") COCRDSL.CPY:"
                    + copybookLine + " COCRDSL.bms:" + mapsetLine + " POS=(" + screenRow + ","
                    + screenColumn + ") offset " + dataOffset + ".." + endOffsetExclusive();
        }

        /**
         * The field carrying a given {@code DFHMDF} label.
         *
         * @param label the {@code DFHMDF} label to look up, for example {@code CARDSID}
         * @return the matching field, never {@code null}
         * @throws NullPointerException if {@code label} is {@code null}
         * @throws IllegalArgumentException if no field carries that label
         */
        public static ScreenField byLabel(String label) {
            Objects.requireNonNull(label, "A DFHMDF label is required to look up a COCRDSL field");
            for (ScreenField candidate : values()) {
                if (candidate.label.equals(label)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("app/bms/COCRDSL.bms declares no name-labelled DFHMDF "
                    + "field called '" + label + "'; the fifteen it declares are TRNNAME TITLE01 "
                    + "CURDATE PGMNAME TITLE02 CURTIME ACCTSID CARDSID CRDNAME CRDSTCD EXPMON EXPYEAR "
                    + "INFOMSG ERRMSG FKEYS");
        }
    }

    /**
     * The {@code xxxL} and {@code xxxA} items of one input field: the two pieces of per-field metadata a
     * BMS symbolic map declares beside the data, and the two {@code COCRDSLC} actually writes.
     *
     * <p>Rejecting a value the copybook cannot represent is the point: silently storing 30000 in a field
     * declared {@code S9(4)} is precisely the kind of divergence a parity migration exists to prevent.
     */
    public static final class ScreenFieldMetadata {
        /**
         * Lowest value {@code xxxL COMP PIC S9(4)} can represent: four digits and a sign.
         */
        public static final int LENGTH_ITEM_MIN = -9999;

        /**
         * Highest value {@code xxxL COMP PIC S9(4)} can represent.
         */
        public static final int LENGTH_ITEM_MAX = 9999;

        /**
         * The length item of a field CICS reports as not entered, and the value a freshly initialised map
         * area holds.
         */
        public static final int LENGTH_UNSET = 0;

        /**
         * The length item that asks CICS to place the cursor on this field - the {@code MOVE -1 TO xxxL}
         * convention {@code app/cbl/COCRDSLC.cbl} uses at lines 518, 521 and 523.
         */
        public static final int CURSOR_HERE = -1;

        /**
         * The unset attribute byte: {@code LOW-VALUES}, which is binary zero on every code page because it
         * is by definition the lowest character of the collating sequence.
         */
        public static final byte ATTRIBUTE_UNSET = LOW_VALUE_BYTE;

        private int length;

        private byte attribute;

        /**
         * A freshly initialised pair: {@link #getLength()} is {@value #LENGTH_UNSET} and
         * {@link #getAttribute()} is {@code LOW-VALUES}.
         */
        public ScreenFieldMetadata() {
            this.length = LENGTH_UNSET;
            this.attribute = ATTRIBUTE_UNSET;
        }

        public ScreenFieldMetadata(int length, byte attribute) {
            setLength(length);
            setAttribute(attribute);
        }

        /**
         * A copy of another pair, so that copying a request cannot leave two requests sharing one mutable
         * metadata holder.
         *
         * @param other the pair to copy
         * @throws NullPointerException if {@code other} is {@code null}
         */
        public ScreenFieldMetadata(ScreenFieldMetadata other) {
            Objects.requireNonNull(other, "A metadata pair is required to copy it");
            this.length = other.length;
            this.attribute = other.attribute;
        }

        /**
         * Returns this pair to its initialised state - {@value #LENGTH_UNSET} and {@code LOW-VALUES}.
         */
        public void reset() {
            this.length = LENGTH_UNSET;
            this.attribute = ATTRIBUTE_UNSET;
        }

        /**
         * The {@code xxxL COMP PIC S9(4)} halfword.
         *
         * @return the stored value, between {@link #LENGTH_ITEM_MIN} and {@value #LENGTH_ITEM_MAX}
         */
        public int getLength() {
            return length;
        }

        public void setLength(int length) {
            if (length < LENGTH_ITEM_MIN || length > LENGTH_ITEM_MAX) {
                throw new IllegalArgumentException("A COCRDSL xxxL item is declared COMP PIC S9(4) "
                        + "and so holds " + LENGTH_ITEM_MIN + " to " + LENGTH_ITEM_MAX + "; " + length
                        + " does not fit, and storing it would put a value in the group image that the "
                        + "copybook cannot represent");
            }
            this.length = length;
        }

        /**
         * Whether the field was reported as not entered - {@link #getLength()} is {@value #LENGTH_UNSET}.
         *
         * <p>Deliberately not written as "is blank": a field can be entered as spaces, in which case CICS
         * reports a non-zero length for a value that is nonetheless blank.
         *
         * @return {@code true} when the length item is {@value #LENGTH_UNSET}
         */
        public boolean isLengthUnset() {
            return length == LENGTH_UNSET;
        }

        /**
         * Whether the cursor is directed at this field - {@link #getLength()} is {@link #CURSOR_HERE}.
         *
         * @return {@code true} when the length item is {@link #CURSOR_HERE}
         */
        public boolean isCursorHere() {
            return length == CURSOR_HERE;
        }

        /**
         * Directs the cursor at this field, the {@code MOVE -1 TO xxxL OF CCRDSLAI} of
         * {@code app/cbl/COCRDSLC.cbl:518}, {@code :521} and {@code :523}.
         */
        public void positionCursorHere() {
            this.length = CURSOR_HERE;
        }

        /**
         * The {@code xxxA PICTURE X} attribute byte.
         *
         * @return the stored byte; {@link #ATTRIBUTE_UNSET} when the program has assigned nothing
         */
        public byte getAttribute() {
            return attribute;
        }

        /**
         * The same byte read through its {@code xxxF PICTURE X} declaration rather than its {@code xxxA}
         * redefinition.
         *
         * <p>{@code 02 FILLER REDEFINES xxxF} / {@code 03 xxxA PICTURE X} is one byte described twice, so
         * this returns exactly what {@link #getAttribute()} returns.
         *
         * @return the stored byte, identical to {@link #getAttribute()}
         */
        public byte getFlag() {
            return attribute;
        }

        /**
         * Stores the attribute byte, the {@code MOVE DFHBMPRF TO xxxA OF CCRDSLAI} of
         * {@code app/cbl/COCRDSLC.cbl:507-508} and the {@code MOVE DFHBMFSE} of {@code :510-511}.
         *
         * @param attribute the byte to store, for example one of the {@code common/BmsAttributes} constants
         */
        public void setAttribute(byte attribute) {
            this.attribute = attribute;
        }

        /**
         * Whether the attribute byte is still {@code LOW-VALUES} - that is, whether the program has
         * assigned an attribute to this field at all.
         *
         * @return {@code true} when the attribute byte is {@link #ATTRIBUTE_UNSET}
         */
        public boolean isAttributeUnset() {
            return attribute == ATTRIBUTE_UNSET;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ScreenFieldMetadata that)) {
                return false;
            }
            return length == that.length && attribute == that.attribute;
        }

        @Override
        public int hashCode() {
            return Objects.hash(length, attribute);
        }

        @Override
        public String toString() {
            return "ScreenFieldMetadata[length=" + length + ", attribute=0x"
                    + String.format("%02X", attribute) + "]";
        }
    }

    static SensitiveDiagnostics.Disclosure disclosureOf(ScreenField field) {
        if (field == null) {
            return SensitiveDiagnostics.Disclosure.REDACTED_VALUE;
        }
        return switch (field) {
            case ACCTSID -> SensitiveDiagnostics.Disclosure.IDENTIFIER;
            case CARDSID -> SensitiveDiagnostics.Disclosure.PAN;
            case CRDNAME -> SensitiveDiagnostics.Disclosure.TEXT;
            default -> SensitiveDiagnostics.Disclosure.PLAIN;
        };
    }

    /**
     * {@code WS-THIS-PROGCOMMAREA} - the twelve bytes {@code COCRDSLC} appends to {@code CARDDEMO-COMMAREA}
     * when it returns.
     *
     * @param caFromProgram {@code CA-FROM-PROGRAM PIC X(08)}, {@code app/cbl/COCRDSLC.cbl:202}
     * @param caFromTranid {@code CA-FROM-TRANID PIC X(04)}, {@code :203}
     */
    public record ThisProgCommarea(@JsonProperty("caFromProgram") String caFromProgram,
                                   @JsonProperty("caFromTranid") String caFromTranid) {
        /**
         * Declared width of {@code CA-FROM-PROGRAM}: {@code PIC X(08)}.
         */
        public static final int CA_FROM_PROGRAM_LENGTH = 8;

        /**
         * Declared width of {@code CA-FROM-TRANID}: {@code PIC X(04)}.
         */
        public static final int CA_FROM_TRANID_LENGTH = 4;

        public static final int RECORD_LENGTH = CA_FROM_PROGRAM_LENGTH + CA_FROM_TRANID_LENGTH;

        public ThisProgCommarea {
            Objects.requireNonNull(caFromProgram, "caFromProgram (CA-FROM-PROGRAM) must not be null: "
                    + "COBOL has no absent state, so an empty value is a run of spaces");
            Objects.requireNonNull(caFromTranid, "caFromTranid (CA-FROM-TRANID) must not be null: "
                    + "COBOL has no absent state, so an empty value is a run of spaces");
        }

        /**
         * {@code INITIALIZE WS-THIS-PROGCOMMAREA} - {@code app/cbl/COCRDSLC.cbl:272}: both alphanumeric
         * items to spaces at their declared widths.
         *
         * @return the initialised area; never {@code null}
         */
        public static ThisProgCommarea initialized() {
            return new ThisProgCommarea(spaces(CA_FROM_PROGRAM_LENGTH),
                    spaces(CA_FROM_TRANID_LENGTH));
        }

        /**
         * Renders the area as its twelve-character fixed-width image, applying the {@code PIC X} move to
         * each component.
         *
         * @param codec the codec owning the {@code PIC X} move rule
         * @return exactly {@link #RECORD_LENGTH} characters
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public String toImage(FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A codec is required to apply the PIC X move");
            return codec.movePicX(caFromProgram, CA_FROM_PROGRAM_LENGTH)
                    + codec.movePicX(caFromTranid, CA_FROM_TRANID_LENGTH);
        }

        /**
         * Reads the area back from a twelve-character image, splitting at the copybook's offsets.
         *
         * @param image a {@link #RECORD_LENGTH}-character image
         * @return the decoded area; never {@code null}
         * @throws NullPointerException if {@code image} is {@code null}
         * @throws IllegalArgumentException if {@code image} is not exactly {@link #RECORD_LENGTH}
         *     characters
         */
        public static ThisProgCommarea fromImage(String image) {
            Objects.requireNonNull(image, "An image is required to decode WS-THIS-PROGCOMMAREA");
            if (image.length() != RECORD_LENGTH) {
                throw new IllegalArgumentException("WS-THIS-PROGCOMMAREA is " + RECORD_LENGTH
                        + " characters, but the image supplied is " + image.length());
            }
            return new ThisProgCommarea(image.substring(0, CA_FROM_PROGRAM_LENGTH),
                    image.substring(CA_FROM_PROGRAM_LENGTH));
        }
    }
}
