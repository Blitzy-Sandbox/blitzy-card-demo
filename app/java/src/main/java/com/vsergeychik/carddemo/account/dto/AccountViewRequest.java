package com.vsergeychik.carddemo.account.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ResponseOnlyMembers;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * The inbound payload of {@code GET /api/accounts/&#123;acctId&#125;} - the account detail screen, CSD
 * transaction {@code CAVW}, backed by {@code app/cbl/COACTVWC.cbl} (941 lines).
 *
 * <p>Every field is a {@link String}, and a COBOL alphanumeric receiver is filled from its leftmost
 * position, padded on the right with spaces when the sending value is short and truncated on the right when
 * it is long.
 */
@JsonIgnoreProperties({
        ResponseOnlyMembers.NEXT_PROGRAM,
        ResponseOnlyMembers.NEXT_MAPSET,
        ResponseOnlyMembers.NEXT_MAP,
        ResponseOnlyMembers.SCREEN_METADATA})
public final class AccountViewRequest {
    /**
     * Width of {@code TRNNAMEI PIC X(4)}, {@code COACTVW.CPY:24}; {@code TRNNAME LENGTH=4}.
     */
    public static final int TRNNAME_LENGTH = 4;

    /**
     * Width of {@code TITLE01I PIC X(40)}, {@code COACTVW.CPY:30}; {@code TITLE01 LENGTH=40}.
     */
    public static final int TITLE01_LENGTH = 40;

    /**
     * Width of {@code CURDATEI PIC X(8)}, {@code COACTVW.CPY:36}; {@code CURDATE LENGTH=8}.
     */
    public static final int CURDATE_LENGTH = 8;

    /**
     * Width of {@code PGMNAMEI PIC X(8)}, {@code COACTVW.CPY:42}; {@code PGMNAME LENGTH=8}.
     */
    public static final int PGMNAME_LENGTH = 8;

    /**
     * Width of {@code TITLE02I PIC X(40)}, {@code COACTVW.CPY:48}; {@code TITLE02 LENGTH=40}.
     */
    public static final int TITLE02_LENGTH = 40;

    /**
     * Width of {@code CURTIMEI PIC X(8)}, {@code COACTVW.CPY:54}; {@code CURTIME LENGTH=8}.
     */
    public static final int CURTIME_LENGTH = 8;

    /**
     * Width of {@code ACCTSIDI PIC 99999999999}, {@code COACTVW.CPY:60}; {@code ACCTSID LENGTH=11}.
     */
    public static final int ACCTSID_LENGTH = 11;

    /**
     * Width of {@code ACSTTUSI PIC X(1)}, {@code COACTVW.CPY:66}; {@code ACSTTUS LENGTH=1}.
     */
    public static final int ACSTTUS_LENGTH = 1;

    /**
     * Width of {@code ADTOPENI PIC X(10)}, {@code COACTVW.CPY:72}; {@code ADTOPEN LENGTH=10}.
     */
    public static final int ADTOPEN_LENGTH = 10;

    /**
     * Width of {@code ACRDLIMI PIC X(15)}, {@code COACTVW.CPY:78}; {@code ACRDLIM LENGTH=15}.
     */
    public static final int ACRDLIM_LENGTH = 15;

    /**
     * Width of {@code AEXPDTI PIC X(10)}, {@code COACTVW.CPY:84}; {@code AEXPDT LENGTH=10}.
     */
    public static final int AEXPDT_LENGTH = 10;

    /**
     * Width of {@code ACSHLIMI PIC X(15)}, {@code COACTVW.CPY:90}; {@code ACSHLIM LENGTH=15}.
     */
    public static final int ACSHLIM_LENGTH = 15;

    /**
     * Width of {@code AREISDTI PIC X(10)}, {@code COACTVW.CPY:96}; {@code AREISDT LENGTH=10}.
     */
    public static final int AREISDT_LENGTH = 10;

    /**
     * Width of {@code ACURBALI PIC X(15)}, {@code COACTVW.CPY:102}; {@code ACURBAL LENGTH=15}.
     */
    public static final int ACURBAL_LENGTH = 15;

    /**
     * Width of {@code ACRCYCRI PIC X(15)}, {@code COACTVW.CPY:108}; {@code ACRCYCR LENGTH=15}.
     */
    public static final int ACRCYCR_LENGTH = 15;

    /**
     * Width of {@code AADDGRPI PIC X(10)}, {@code COACTVW.CPY:114}; {@code AADDGRP LENGTH=10}.
     */
    public static final int AADDGRP_LENGTH = 10;

    /**
     * Width of {@code ACRCYDBI PIC X(15)}, {@code COACTVW.CPY:120}; {@code ACRCYDB LENGTH=15}.
     */
    public static final int ACRCYDB_LENGTH = 15;

    /**
     * Width of {@code ACSTNUMI PIC X(9)}, {@code COACTVW.CPY:126}; {@code ACSTNUM LENGTH=9}.
     */
    public static final int ACSTNUM_LENGTH = 9;

    /**
     * Width of {@code ACSTSSNI PIC X(12)}, {@code COACTVW.CPY:132}; {@code ACSTSSN LENGTH=12}.
     */
    public static final int ACSTSSN_LENGTH = 12;

    /**
     * Width of {@code ACSTDOBI PIC X(10)}, {@code COACTVW.CPY:138}; {@code ACSTDOB LENGTH=10}.
     */
    public static final int ACSTDOB_LENGTH = 10;

    /**
     * Width of {@code ACSTFCOI PIC X(3)}, {@code COACTVW.CPY:144}; {@code ACSTFCO LENGTH=3}.
     */
    public static final int ACSTFCO_LENGTH = 3;

    /**
     * Width of {@code ACSFNAMI PIC X(25)}, {@code COACTVW.CPY:150}; {@code ACSFNAM LENGTH=25}.
     */
    public static final int ACSFNAM_LENGTH = 25;

    /**
     * Width of {@code ACSMNAMI PIC X(25)}, {@code COACTVW.CPY:156}; {@code ACSMNAM LENGTH=25}.
     */
    public static final int ACSMNAM_LENGTH = 25;

    /**
     * Width of {@code ACSLNAMI PIC X(25)}, {@code COACTVW.CPY:162}; {@code ACSLNAM LENGTH=25}.
     */
    public static final int ACSLNAM_LENGTH = 25;

    /**
     * Width of {@code ACSADL1I PIC X(50)}, {@code COACTVW.CPY:168}; {@code ACSADL1 LENGTH=50}.
     */
    public static final int ACSADL1_LENGTH = 50;

    /**
     * Width of {@code ACSSTTEI PIC X(2)}, {@code COACTVW.CPY:174}; {@code ACSSTTE LENGTH=2}.
     */
    public static final int ACSSTTE_LENGTH = 2;

    /**
     * Width of {@code ACSADL2I PIC X(50)}, {@code COACTVW.CPY:180}; {@code ACSADL2 LENGTH=50}.
     */
    public static final int ACSADL2_LENGTH = 50;

    /**
     * Width of {@code ACSZIPCI PIC X(5)}, {@code COACTVW.CPY:186}; {@code ACSZIPC LENGTH=5}.
     */
    public static final int ACSZIPC_LENGTH = 5;

    /**
     * Width of {@code ACSCITYI PIC X(50)}, {@code COACTVW.CPY:192}; {@code ACSCITY LENGTH=50}.
     */
    public static final int ACSCITY_LENGTH = 50;

    /**
     * Width of {@code ACSCTRYI PIC X(3)}, {@code COACTVW.CPY:198}; {@code ACSCTRY LENGTH=3}.
     */
    public static final int ACSCTRY_LENGTH = 3;

    /**
     * Width of {@code ACSPHN1I PIC X(13)}, {@code COACTVW.CPY:204}; {@code ACSPHN1 LENGTH=13}.
     */
    public static final int ACSPHN1_LENGTH = 13;

    /**
     * Width of {@code ACSGOVTI PIC X(20)}, {@code COACTVW.CPY:210}; {@code ACSGOVT LENGTH=20}.
     */
    public static final int ACSGOVT_LENGTH = 20;

    /**
     * Width of {@code ACSPHN2I PIC X(13)}, {@code COACTVW.CPY:216}; {@code ACSPHN2 LENGTH=13}.
     */
    public static final int ACSPHN2_LENGTH = 13;

    /**
     * Width of {@code ACSEFTCI PIC X(10)}, {@code COACTVW.CPY:222}; {@code ACSEFTC LENGTH=10}.
     */
    public static final int ACSEFTC_LENGTH = 10;

    /**
     * Width of {@code ACSPFLGI PIC X(1)}, {@code COACTVW.CPY:228}; {@code ACSPFLG LENGTH=1}.
     */
    public static final int ACSPFLG_LENGTH = 1;

    /**
     * Width of {@code INFOMSGI PIC X(45)}, {@code COACTVW.CPY:234}; {@code INFOMSG LENGTH=45}.
     */
    public static final int INFOMSG_LENGTH = 45;

    /**
     * Width of {@code ERRMSGI PIC X(78)}, {@code COACTVW.CPY:240}; {@code ERRMSG LENGTH=78}.
     */
    public static final int ERRMSG_LENGTH = 78;

    /**
     * Bytes in the {@code TIOAPFX=YES} prefix - {@code 02 FILLER PIC X(12)} at
     * {@code app/cpy-bms/COACTVW.CPY:18}, repeated at line 242 as the first item of the output group.
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
     * Name-labelled {@code DFHMDF} entries in {@code app/bms/COACTVW.bms}, of 100 entries in all.
     */
    public static final int FIELD_COUNT = 37;

    public static final int PAYLOAD_LENGTH = TRNNAME_LENGTH + TITLE01_LENGTH + CURDATE_LENGTH
            + PGMNAME_LENGTH + TITLE02_LENGTH + CURTIME_LENGTH + ACCTSID_LENGTH + ACSTTUS_LENGTH
            + ADTOPEN_LENGTH + ACRDLIM_LENGTH + AEXPDT_LENGTH + ACSHLIM_LENGTH + AREISDT_LENGTH
            + ACURBAL_LENGTH + ACRCYCR_LENGTH + AADDGRP_LENGTH + ACRCYDB_LENGTH + ACSTNUM_LENGTH
            + ACSTSSN_LENGTH + ACSTDOB_LENGTH + ACSTFCO_LENGTH + ACSFNAM_LENGTH + ACSMNAM_LENGTH
            + ACSLNAM_LENGTH + ACSADL1_LENGTH + ACSSTTE_LENGTH + ACSADL2_LENGTH + ACSZIPC_LENGTH
            + ACSCITY_LENGTH + ACSCTRY_LENGTH + ACSPHN1_LENGTH + ACSGOVT_LENGTH + ACSPHN2_LENGTH
            + ACSEFTC_LENGTH + ACSPFLG_LENGTH + INFOMSG_LENGTH + ERRMSG_LENGTH;

    public static final int GROUP_LENGTH =
            TIOAPFX_LENGTH + FIELD_COUNT * FIELD_OVERHEAD + PAYLOAD_LENGTH;

    private static final char SPACE = ' ';

    private static final byte LOW_VALUE_BYTE = 0x00;

    static {
        if (FIELD_OVERHEAD != 7) {
            throw new IllegalStateException("Each COACTVW.CPY input field carries 2 + 1 + 4 = 7 bytes "
                    + "of length, flag and extended-attribute storage before its data, but "
                    + "FIELD_OVERHEAD computes to " + FIELD_OVERHEAD);
        }
        if (PAYLOAD_LENGTH != 684) {
            throw new IllegalStateException("The 37 xxxI PICTURE widths of app/cpy-bms/COACTVW.CPY sum "
                    + "to 684, which is also the sum of the 37 LENGTH= operands of "
                    + "app/bms/COACTVW.bms, but the width constants sum to " + PAYLOAD_LENGTH);
        }
        if (GROUP_LENGTH != 955) {
            throw new IllegalStateException("The CACTVWAI group is 12 + 37 * 7 + 684 = 955 bytes, but "
                    + "the constants compute " + GROUP_LENGTH);
        }
        verifyFieldStrides();
    }

    private static void verifyFieldStrides() {
        ScreenField[] fields = ScreenField.values();
        if (fields.length != FIELD_COUNT) {
            throw new IllegalStateException("app/bms/COACTVW.bms carries " + FIELD_COUNT
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
                        + ", so the CACTVWAI group would have a gap or an overlap there");
            }
            cursor = field.endOffsetExclusive();
            widths += field.length();
        }
        if (widths != PAYLOAD_LENGTH) {
            throw new IllegalStateException("The 37 ScreenField widths sum to " + widths
                    + " but the width constants sum to " + PAYLOAD_LENGTH);
        }
        if (cursor != GROUP_LENGTH) {
            throw new IllegalStateException("Walking the 37 field strides from the end of the "
                    + TIOAPFX_LENGTH + "-byte TIOAPFX prefix ends at " + cursor
                    + ", not at the declared group length of " + GROUP_LENGTH);
        }
    }

    @JsonProperty("trnname")
    @Size(max = TRNNAME_LENGTH,
            message = "TRNNAME is TRNNAMEI PIC X(4) at app/cpy-bms/COACTVW.CPY:24 and holds at most 4 "
                    + "characters")
    private String trnname;

    @JsonProperty("title01")
    @Size(max = TITLE01_LENGTH,
            message = "TITLE01 is TITLE01I PIC X(40) at app/cpy-bms/COACTVW.CPY:30 and holds at most 40 "
                    + "characters")
    private String title01;

    @JsonProperty("curdate")
    @Size(max = CURDATE_LENGTH,
            message = "CURDATE is CURDATEI PIC X(8) at app/cpy-bms/COACTVW.CPY:36 and holds at most 8 "
                    + "characters")
    private String curdate;

    @JsonProperty("pgmname")
    @Size(max = PGMNAME_LENGTH,
            message = "PGMNAME is PGMNAMEI PIC X(8) at app/cpy-bms/COACTVW.CPY:42 and holds at most 8 "
                    + "characters")
    private String pgmname;

    @JsonProperty("title02")
    @Size(max = TITLE02_LENGTH,
            message = "TITLE02 is TITLE02I PIC X(40) at app/cpy-bms/COACTVW.CPY:48 and holds at most 40 "
                    + "characters")
    private String title02;

    @JsonProperty("curtime")
    @Size(max = CURTIME_LENGTH,
            message = "CURTIME is CURTIMEI PIC X(8) at app/cpy-bms/COACTVW.CPY:54 and holds at most 8 "
                    + "characters")
    private String curtime;

    @JsonProperty("acctsid")
    @Size(max = ACCTSID_LENGTH,
            message = "ACCTSID is ACCTSIDI PIC 99999999999 at app/cpy-bms/COACTVW.CPY:60 and holds at "
                    + "most 11 characters; '*' and spaces are valid values, tested at "
                    + "app/cbl/COACTVWC.cbl:628-629")
    private String acctsid;

    @JsonProperty("acsttus")
    @Size(max = ACSTTUS_LENGTH,
            message = "ACSTTUS is ACSTTUSI PIC X(1) at app/cpy-bms/COACTVW.CPY:66 and holds at most 1 "
                    + "character")
    private String acsttus;

    @JsonProperty("adtopen")
    @Size(max = ADTOPEN_LENGTH,
            message = "ADTOPEN is ADTOPENI PIC X(10) at app/cpy-bms/COACTVW.CPY:72 and holds at most 10 "
                    + "characters")
    private String adtopen;

    @JsonProperty("acrdlim")
    @Size(max = ACRDLIM_LENGTH,
            message = "ACRDLIM is ACRDLIMI PIC X(15) at app/cpy-bms/COACTVW.CPY:78 and holds at most 15 "
                    + "characters")
    private String acrdlim;

    @JsonProperty("aexpdt")
    @Size(max = AEXPDT_LENGTH,
            message = "AEXPDT is AEXPDTI PIC X(10) at app/cpy-bms/COACTVW.CPY:84 and holds at most 10 "
                    + "characters")
    private String aexpdt;

    @JsonProperty("acshlim")
    @Size(max = ACSHLIM_LENGTH,
            message = "ACSHLIM is ACSHLIMI PIC X(15) at app/cpy-bms/COACTVW.CPY:90 and holds at most 15 "
                    + "characters")
    private String acshlim;

    @JsonProperty("areisdt")
    @Size(max = AREISDT_LENGTH,
            message = "AREISDT is AREISDTI PIC X(10) at app/cpy-bms/COACTVW.CPY:96 and holds at most 10 "
                    + "characters")
    private String areisdt;

    @JsonProperty("acurbal")
    @Size(max = ACURBAL_LENGTH,
            message = "ACURBAL is ACURBALI PIC X(15) at app/cpy-bms/COACTVW.CPY:102 and holds at most 15 "
                    + "characters")
    private String acurbal;

    @JsonProperty("acrcycr")
    @Size(max = ACRCYCR_LENGTH,
            message = "ACRCYCR is ACRCYCRI PIC X(15) at app/cpy-bms/COACTVW.CPY:108 and holds at most 15 "
                    + "characters")
    private String acrcycr;

    @JsonProperty("aaddgrp")
    @Size(max = AADDGRP_LENGTH,
            message = "AADDGRP is AADDGRPI PIC X(10) at app/cpy-bms/COACTVW.CPY:114 and holds at most 10 "
                    + "characters")
    private String aaddgrp;

    @JsonProperty("acrcydb")
    @Size(max = ACRCYDB_LENGTH,
            message = "ACRCYDB is ACRCYDBI PIC X(15) at app/cpy-bms/COACTVW.CPY:120 and holds at most 15 "
                    + "characters")
    private String acrcydb;

    @JsonProperty("acstnum")
    @Size(max = ACSTNUM_LENGTH,
            message = "ACSTNUM is ACSTNUMI PIC X(9) at app/cpy-bms/COACTVW.CPY:126 and holds at most 9 "
                    + "characters")
    private String acstnum;

    @JsonProperty("acstssn")
    @Size(max = ACSTSSN_LENGTH,
            message = "ACSTSSN is ACSTSSNI PIC X(12) at app/cpy-bms/COACTVW.CPY:132 and holds at most 12 "
                    + "characters")
    private String acstssn;

    @JsonProperty("acstdob")
    @Size(max = ACSTDOB_LENGTH,
            message = "ACSTDOB is ACSTDOBI PIC X(10) at app/cpy-bms/COACTVW.CPY:138 and holds at most 10 "
                    + "characters")
    private String acstdob;

    @JsonProperty("acstfco")
    @Size(max = ACSTFCO_LENGTH,
            message = "ACSTFCO is ACSTFCOI PIC X(3) at app/cpy-bms/COACTVW.CPY:144 and holds at most 3 "
                    + "characters")
    private String acstfco;

    @JsonProperty("acsfnam")
    @Size(max = ACSFNAM_LENGTH,
            message = "ACSFNAM is ACSFNAMI PIC X(25) at app/cpy-bms/COACTVW.CPY:150 and holds at most 25 "
                    + "characters")
    private String acsfnam;

    @JsonProperty("acsmnam")
    @Size(max = ACSMNAM_LENGTH,
            message = "ACSMNAM is ACSMNAMI PIC X(25) at app/cpy-bms/COACTVW.CPY:156 and holds at most 25 "
                    + "characters")
    private String acsmnam;

    @JsonProperty("acslnam")
    @Size(max = ACSLNAM_LENGTH,
            message = "ACSLNAM is ACSLNAMI PIC X(25) at app/cpy-bms/COACTVW.CPY:162 and holds at most 25 "
                    + "characters")
    private String acslnam;

    @JsonProperty("acsadl1")
    @Size(max = ACSADL1_LENGTH,
            message = "ACSADL1 is ACSADL1I PIC X(50) at app/cpy-bms/COACTVW.CPY:168 and holds at most 50 "
                    + "characters")
    private String acsadl1;

    @JsonProperty("acsstte")
    @Size(max = ACSSTTE_LENGTH,
            message = "ACSSTTE is ACSSTTEI PIC X(2) at app/cpy-bms/COACTVW.CPY:174 and holds at most 2 "
                    + "characters")
    private String acsstte;

    @JsonProperty("acsadl2")
    @Size(max = ACSADL2_LENGTH,
            message = "ACSADL2 is ACSADL2I PIC X(50) at app/cpy-bms/COACTVW.CPY:180 and holds at most 50 "
                    + "characters")
    private String acsadl2;

    @JsonProperty("acszipc")
    @Size(max = ACSZIPC_LENGTH,
            message = "ACSZIPC is ACSZIPCI PIC X(5) at app/cpy-bms/COACTVW.CPY:186 and holds at most 5 "
                    + "characters")
    private String acszipc;

    @JsonProperty("acscity")
    @Size(max = ACSCITY_LENGTH,
            message = "ACSCITY is ACSCITYI PIC X(50) at app/cpy-bms/COACTVW.CPY:192 and holds at most 50 "
                    + "characters")
    private String acscity;

    @JsonProperty("acsctry")
    @Size(max = ACSCTRY_LENGTH,
            message = "ACSCTRY is ACSCTRYI PIC X(3) at app/cpy-bms/COACTVW.CPY:198 and holds at most 3 "
                    + "characters")
    private String acsctry;

    @JsonProperty("acsphn1")
    @Size(max = ACSPHN1_LENGTH,
            message = "ACSPHN1 is ACSPHN1I PIC X(13) at app/cpy-bms/COACTVW.CPY:204 and holds at most 13 "
                    + "characters")
    private String acsphn1;

    @JsonProperty("acsgovt")
    @Size(max = ACSGOVT_LENGTH,
            message = "ACSGOVT is ACSGOVTI PIC X(20) at app/cpy-bms/COACTVW.CPY:210 and holds at most 20 "
                    + "characters")
    private String acsgovt;

    @JsonProperty("acsphn2")
    @Size(max = ACSPHN2_LENGTH,
            message = "ACSPHN2 is ACSPHN2I PIC X(13) at app/cpy-bms/COACTVW.CPY:216 and holds at most 13 "
                    + "characters")
    private String acsphn2;

    @JsonProperty("acseftc")
    @Size(max = ACSEFTC_LENGTH,
            message = "ACSEFTC is ACSEFTCI PIC X(10) at app/cpy-bms/COACTVW.CPY:222 and holds at most 10 "
                    + "characters")
    private String acseftc;

    @JsonProperty("acspflg")
    @Size(max = ACSPFLG_LENGTH,
            message = "ACSPFLG is ACSPFLGI PIC X(1) at app/cpy-bms/COACTVW.CPY:228 and holds at most 1 "
                    + "character")
    private String acspflg;

    @JsonProperty("infomsg")
    @Size(max = INFOMSG_LENGTH,
            message = "INFOMSG is INFOMSGI PIC X(45) at app/cpy-bms/COACTVW.CPY:234 and holds at most 45 "
                    + "characters")
    private String infomsg;

    @JsonProperty("errmsg")
    @Size(max = ERRMSG_LENGTH,
            message = "ERRMSG is ERRMSGI PIC X(78) at app/cpy-bms/COACTVW.CPY:240 and holds at most 78 "
                    + "characters")
    private String errmsg;

    @JsonProperty("cardScreenState")
    private CardScreenState cardScreenState;

    @JsonProperty("navigationContext")
    private NavigationContext navigationContext;

    private final EnumMap<ScreenField, ScreenFieldMetadata> metadata =
            new EnumMap<>(ScreenField.class);

    /**
     * A freshly initialised map area: every one of the 37 fields a run of spaces of its declared width,
     * every metadata holder unset, a new {@link CardScreenState} work area, and no communication area.
     */
    public AccountViewRequest() {
        for (ScreenField field : ScreenField.values()) {
            metadata.put(field, new ScreenFieldMetadata());
        }
        initializeState();
    }

    public AccountViewRequest(AccountViewRequest other) {
        Objects.requireNonNull(other, "A request is required to copy it");
        this.trnname = other.trnname;
        this.title01 = other.title01;
        this.curdate = other.curdate;
        this.pgmname = other.pgmname;
        this.title02 = other.title02;
        this.curtime = other.curtime;
        this.acctsid = other.acctsid;
        this.acsttus = other.acsttus;
        this.adtopen = other.adtopen;
        this.acrdlim = other.acrdlim;
        this.aexpdt = other.aexpdt;
        this.acshlim = other.acshlim;
        this.areisdt = other.areisdt;
        this.acurbal = other.acurbal;
        this.acrcycr = other.acrcycr;
        this.aaddgrp = other.aaddgrp;
        this.acrcydb = other.acrcydb;
        this.acstnum = other.acstnum;
        this.acstssn = other.acstssn;
        this.acstdob = other.acstdob;
        this.acstfco = other.acstfco;
        this.acsfnam = other.acsfnam;
        this.acsmnam = other.acsmnam;
        this.acslnam = other.acslnam;
        this.acsadl1 = other.acsadl1;
        this.acsstte = other.acsstte;
        this.acsadl2 = other.acsadl2;
        this.acszipc = other.acszipc;
        this.acscity = other.acscity;
        this.acsctry = other.acsctry;
        this.acsphn1 = other.acsphn1;
        this.acsgovt = other.acsgovt;
        this.acsphn2 = other.acsphn2;
        this.acseftc = other.acseftc;
        this.acspflg = other.acspflg;
        this.infomsg = other.infomsg;
        this.errmsg = other.errmsg;
        this.cardScreenState = new CardScreenState(other.cardScreenState);
        this.navigationContext = other.navigationContext;
        for (ScreenField field : ScreenField.values()) {
            metadata.put(field, new ScreenFieldMetadata(other.metadata.get(field)));
        }
    }

    /**
     * A request carrying only the one field {@code COACTVWC} actually reads from the input group.
     *
     * <p>{@code app/cbl/COACTVWC.cbl} takes exactly one value off this map - {@code ACCTSIDI OF CACTVWAI}
     * at lines 628 to 632 - and derives everything else.
     *
     * @param acctsid the {@code ACCTSID} filter value; {@code null} is taken as spaces
     * @return a request whose remaining 36 fields are at their initialised values, never {@code null}
     */
    public static AccountViewRequest withAccountFilter(String acctsid) {
        AccountViewRequest request = new AccountViewRequest();
        request.setAcctsid(acctsid);
        return request;
    }

    /**
     * Returns every field, every metadata holder and both carriers to their initialised state - the
     * {@code INITIALIZE} a CICS program performs before it starts populating a map, and the counterpart of
     * {@code MOVE LOW-VALUES TO CACTVWAO} at {@code app/cbl/COACTVWC.cbl:432}.
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
        this.acsttus = spaces(ACSTTUS_LENGTH);
        this.adtopen = spaces(ADTOPEN_LENGTH);
        this.acrdlim = spaces(ACRDLIM_LENGTH);
        this.aexpdt = spaces(AEXPDT_LENGTH);
        this.acshlim = spaces(ACSHLIM_LENGTH);
        this.areisdt = spaces(AREISDT_LENGTH);
        this.acurbal = spaces(ACURBAL_LENGTH);
        this.acrcycr = spaces(ACRCYCR_LENGTH);
        this.aaddgrp = spaces(AADDGRP_LENGTH);
        this.acrcydb = spaces(ACRCYDB_LENGTH);
        this.acstnum = spaces(ACSTNUM_LENGTH);
        this.acstssn = spaces(ACSTSSN_LENGTH);
        this.acstdob = spaces(ACSTDOB_LENGTH);
        this.acstfco = spaces(ACSTFCO_LENGTH);
        this.acsfnam = spaces(ACSFNAM_LENGTH);
        this.acsmnam = spaces(ACSMNAM_LENGTH);
        this.acslnam = spaces(ACSLNAM_LENGTH);
        this.acsadl1 = spaces(ACSADL1_LENGTH);
        this.acsstte = spaces(ACSSTTE_LENGTH);
        this.acsadl2 = spaces(ACSADL2_LENGTH);
        this.acszipc = spaces(ACSZIPC_LENGTH);
        this.acscity = spaces(ACSCITY_LENGTH);
        this.acsctry = spaces(ACSCTRY_LENGTH);
        this.acsphn1 = spaces(ACSPHN1_LENGTH);
        this.acsgovt = spaces(ACSGOVT_LENGTH);
        this.acsphn2 = spaces(ACSPHN2_LENGTH);
        this.acseftc = spaces(ACSEFTC_LENGTH);
        this.acspflg = spaces(ACSPFLG_LENGTH);
        this.infomsg = spaces(INFOMSG_LENGTH);
        this.errmsg = spaces(ERRMSG_LENGTH);
        this.cardScreenState = new CardScreenState();
        this.navigationContext = null;
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
            throw new IllegalArgumentException("A COACTVW field cannot be " + length + " characters "
                    + "wide; every one of the 37 is declared with a width of at least 1");
        }
        return String.valueOf(SPACE).repeat(length);
    }

    private static String orSpaces(String value, int width) {
        return value == null ? spaces(width) : value;
    }

    // It does not pad and it does not truncate, because a setter is not a COBOL MOVE: the MOVE rule is
    // applied only where it is asked for, by FixedWidthCodec.movePicX, through image(), normalize() and
    // toGroupImage(). A PIC X field's trailing spaces are part of its value and the parity differ compares
    // them.

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

    public String getCurdate() {
        return curdate;
    }

    public void setCurdate(String curdate) {
        this.curdate = orSpaces(curdate, CURDATE_LENGTH);
    }

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

    public String getCurtime() {
        return curtime;
    }

    public void setCurtime(String curtime) {
        this.curtime = orSpaces(curtime, CURTIME_LENGTH);
    }

    /**
     * {@code ACCTSID}, the account identifier the operator types - the only unprotected field on this
     * screen.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAcctsid() {
        return acctsid;
    }

    /**
     * Stores {@code ACCTSID} verbatim, including {@code "*"} and any blank value.
     *
     * @param acctsid the value; {@code null} is taken as {@value #ACCTSID_LENGTH} spaces
     */
    public void setAcctsid(String acctsid) {
        this.acctsid = orSpaces(acctsid, ACCTSID_LENGTH);
    }

    public String getAcsttus() {
        return acsttus;
    }

    public void setAcsttus(String acsttus) {
        this.acsttus = orSpaces(acsttus, ACSTTUS_LENGTH);
    }

    public String getAdtopen() {
        return adtopen;
    }

    public void setAdtopen(String adtopen) {
        this.adtopen = orSpaces(adtopen, ADTOPEN_LENGTH);
    }

    /**
     * {@code ACRDLIM}, the credit limit - text, not a number.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAcrdlim() {
        return acrdlim;
    }

    /**
     * Stores {@code ACRDLIM} verbatim, with no parsing and no re-editing.
     *
     * @param acrdlim the value; {@code null} is taken as {@value #ACRDLIM_LENGTH} spaces
     */
    public void setAcrdlim(String acrdlim) {
        this.acrdlim = orSpaces(acrdlim, ACRDLIM_LENGTH);
    }

    public String getAexpdt() {
        return aexpdt;
    }

    public void setAexpdt(String aexpdt) {
        this.aexpdt = orSpaces(aexpdt, AEXPDT_LENGTH);
    }

    /**
     * {@code ACSHLIM}, the cash credit limit - text, not a number.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAcshlim() {
        return acshlim;
    }

    /**
     * Stores {@code ACSHLIM} verbatim, with no parsing and no re-editing.
     *
     * @param acshlim the value; {@code null} is taken as {@value #ACSHLIM_LENGTH} spaces
     */
    public void setAcshlim(String acshlim) {
        this.acshlim = orSpaces(acshlim, ACSHLIM_LENGTH);
    }

    public String getAreisdt() {
        return areisdt;
    }

    public void setAreisdt(String areisdt) {
        this.areisdt = orSpaces(areisdt, AREISDT_LENGTH);
    }

    /**
     * {@code ACURBAL}, the current balance - text, not a number.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAcurbal() {
        return acurbal;
    }

    /**
     * Stores {@code ACURBAL} verbatim, with no parsing and no re-editing.
     *
     * @param acurbal the value; {@code null} is taken as {@value #ACURBAL_LENGTH} spaces
     */
    public void setAcurbal(String acurbal) {
        this.acurbal = orSpaces(acurbal, ACURBAL_LENGTH);
    }

    /**
     * {@code ACRCYCR}, the current cycle credit - text, not a number.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAcrcycr() {
        return acrcycr;
    }

    /**
     * Stores {@code ACRCYCR} verbatim, with no parsing and no re-editing.
     *
     * @param acrcycr the value; {@code null} is taken as {@value #ACRCYCR_LENGTH} spaces
     */
    public void setAcrcycr(String acrcycr) {
        this.acrcycr = orSpaces(acrcycr, ACRCYCR_LENGTH);
    }

    public String getAaddgrp() {
        return aaddgrp;
    }

    public void setAaddgrp(String aaddgrp) {
        this.aaddgrp = orSpaces(aaddgrp, AADDGRP_LENGTH);
    }

    /**
     * {@code ACRCYDB}, the current cycle debit - text, not a number.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAcrcydb() {
        return acrcydb;
    }

    /**
     * Stores {@code ACRCYDB} verbatim, with no parsing and no re-editing.
     *
     * @param acrcydb the value; {@code null} is taken as {@value #ACRCYDB_LENGTH} spaces
     */
    public void setAcrcydb(String acrcydb) {
        this.acrcydb = orSpaces(acrcydb, ACRCYDB_LENGTH);
    }

    public String getAcstnum() {
        return acstnum;
    }

    public void setAcstnum(String acstnum) {
        this.acstnum = orSpaces(acstnum, ACSTNUM_LENGTH);
    }

    /**
     * {@code ACSTSSN}, the customer social security number, in the clear.
     *
     * @return the stored value, untrimmed and unmasked; never {@code null}
     */
    public String getAcstssn() {
        return acstssn;
    }

    public void setAcstssn(String acstssn) {
        this.acstssn = orSpaces(acstssn, ACSTSSN_LENGTH);
    }

    /**
     * {@code ACSTDOB}, the customer date of birth, in the clear.
     *
     * @return the stored value, untrimmed and unmasked; never {@code null}
     */
    public String getAcstdob() {
        return acstdob;
    }

    public void setAcstdob(String acstdob) {
        this.acstdob = orSpaces(acstdob, ACSTDOB_LENGTH);
    }

    public String getAcstfco() {
        return acstfco;
    }

    public void setAcstfco(String acstfco) {
        this.acstfco = orSpaces(acstfco, ACSTFCO_LENGTH);
    }

    public String getAcsfnam() {
        return acsfnam;
    }

    public void setAcsfnam(String acsfnam) {
        this.acsfnam = orSpaces(acsfnam, ACSFNAM_LENGTH);
    }

    public String getAcsmnam() {
        return acsmnam;
    }

    public void setAcsmnam(String acsmnam) {
        this.acsmnam = orSpaces(acsmnam, ACSMNAM_LENGTH);
    }

    public String getAcslnam() {
        return acslnam;
    }

    public void setAcslnam(String acslnam) {
        this.acslnam = orSpaces(acslnam, ACSLNAM_LENGTH);
    }

    public String getAcsadl1() {
        return acsadl1;
    }

    public void setAcsadl1(String acsadl1) {
        this.acsadl1 = orSpaces(acsadl1, ACSADL1_LENGTH);
    }

    public String getAcsstte() {
        return acsstte;
    }

    public void setAcsstte(String acsstte) {
        this.acsstte = orSpaces(acsstte, ACSSTTE_LENGTH);
    }

    public String getAcsadl2() {
        return acsadl2;
    }

    public void setAcsadl2(String acsadl2) {
        this.acsadl2 = orSpaces(acsadl2, ACSADL2_LENGTH);
    }

    public String getAcszipc() {
        return acszipc;
    }

    public void setAcszipc(String acszipc) {
        this.acszipc = orSpaces(acszipc, ACSZIPC_LENGTH);
    }

    public String getAcscity() {
        return acscity;
    }

    public void setAcscity(String acscity) {
        this.acscity = orSpaces(acscity, ACSCITY_LENGTH);
    }

    public String getAcsctry() {
        return acsctry;
    }

    public void setAcsctry(String acsctry) {
        this.acsctry = orSpaces(acsctry, ACSCTRY_LENGTH);
    }

    public String getAcsphn1() {
        return acsphn1;
    }

    public void setAcsphn1(String acsphn1) {
        this.acsphn1 = orSpaces(acsphn1, ACSPHN1_LENGTH);
    }

    /**
     * {@code ACSGOVT}, the government-issued identifier, in the clear.
     *
     * @return the stored value, untrimmed and unmasked; never {@code null}
     */
    public String getAcsgovt() {
        return acsgovt;
    }

    public void setAcsgovt(String acsgovt) {
        this.acsgovt = orSpaces(acsgovt, ACSGOVT_LENGTH);
    }

    public String getAcsphn2() {
        return acsphn2;
    }

    public void setAcsphn2(String acsphn2) {
        this.acsphn2 = orSpaces(acsphn2, ACSPHN2_LENGTH);
    }

    public String getAcseftc() {
        return acseftc;
    }

    public void setAcseftc(String acseftc) {
        this.acseftc = orSpaces(acseftc, ACSEFTC_LENGTH);
    }

    public String getAcspflg() {
        return acspflg;
    }

    public void setAcspflg(String acspflg) {
        this.acspflg = orSpaces(acspflg, ACSPFLG_LENGTH);
    }

    /**
     * {@code INFOMSG}, the informational message line - {@value #INFOMSG_LENGTH} characters, never 40.
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
     * {@code ERRMSG}, the error message line - {@value #ERRMSG_LENGTH} characters, never 75.
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
     * The value of one field, chosen by its {@link ScreenField} constant.
     *
     * @param field which field to read
     * @return the stored value, untrimmed; never {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public String value(ScreenField field) {
        Objects.requireNonNull(field, "A ScreenField is required to read a COACTVW field by name");
        return switch (field) {
            case TRNNAME -> trnname;
            case TITLE01 -> title01;
            case CURDATE -> curdate;
            case PGMNAME -> pgmname;
            case TITLE02 -> title02;
            case CURTIME -> curtime;
            case ACCTSID -> acctsid;
            case ACSTTUS -> acsttus;
            case ADTOPEN -> adtopen;
            case ACRDLIM -> acrdlim;
            case AEXPDT -> aexpdt;
            case ACSHLIM -> acshlim;
            case AREISDT -> areisdt;
            case ACURBAL -> acurbal;
            case ACRCYCR -> acrcycr;
            case AADDGRP -> aaddgrp;
            case ACRCYDB -> acrcydb;
            case ACSTNUM -> acstnum;
            case ACSTSSN -> acstssn;
            case ACSTDOB -> acstdob;
            case ACSTFCO -> acstfco;
            case ACSFNAM -> acsfnam;
            case ACSMNAM -> acsmnam;
            case ACSLNAM -> acslnam;
            case ACSADL1 -> acsadl1;
            case ACSSTTE -> acsstte;
            case ACSADL2 -> acsadl2;
            case ACSZIPC -> acszipc;
            case ACSCITY -> acscity;
            case ACSCTRY -> acsctry;
            case ACSPHN1 -> acsphn1;
            case ACSGOVT -> acsgovt;
            case ACSPHN2 -> acsphn2;
            case ACSEFTC -> acseftc;
            case ACSPFLG -> acspflg;
            case INFOMSG -> infomsg;
            case ERRMSG -> errmsg;
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
        Objects.requireNonNull(field, "A ScreenField is required to write a COACTVW field by name");
        switch (field) {
            case TRNNAME -> setTrnname(value);
            case TITLE01 -> setTitle01(value);
            case CURDATE -> setCurdate(value);
            case PGMNAME -> setPgmname(value);
            case TITLE02 -> setTitle02(value);
            case CURTIME -> setCurtime(value);
            case ACCTSID -> setAcctsid(value);
            case ACSTTUS -> setAcsttus(value);
            case ADTOPEN -> setAdtopen(value);
            case ACRDLIM -> setAcrdlim(value);
            case AEXPDT -> setAexpdt(value);
            case ACSHLIM -> setAcshlim(value);
            case AREISDT -> setAreisdt(value);
            case ACURBAL -> setAcurbal(value);
            case ACRCYCR -> setAcrcycr(value);
            case AADDGRP -> setAaddgrp(value);
            case ACRCYDB -> setAcrcydb(value);
            case ACSTNUM -> setAcstnum(value);
            case ACSTSSN -> setAcstssn(value);
            case ACSTDOB -> setAcstdob(value);
            case ACSTFCO -> setAcstfco(value);
            case ACSFNAM -> setAcsfnam(value);
            case ACSMNAM -> setAcsmnam(value);
            case ACSLNAM -> setAcslnam(value);
            case ACSADL1 -> setAcsadl1(value);
            case ACSSTTE -> setAcsstte(value);
            case ACSADL2 -> setAcsadl2(value);
            case ACSZIPC -> setAcszipc(value);
            case ACSCITY -> setAcscity(value);
            case ACSCTRY -> setAcsctry(value);
            case ACSPHN1 -> setAcsphn1(value);
            case ACSGOVT -> setAcsgovt(value);
            case ACSPHN2 -> setAcsphn2(value);
            case ACSEFTC -> setAcseftc(value);
            case ACSPFLG -> setAcspflg(value);
            case INFOMSG -> setInfomsg(value);
            case ERRMSG -> setErrmsg(value);
        }
    }

    @JsonIgnore
    public ScreenFieldMetadata metadata(ScreenField field) {
        Objects.requireNonNull(field, "A ScreenField is required to address a field's xxxL and xxxA "
                + "items");
        return metadata.get(field);
    }

    /**
     * All 37 metadata holders, keyed by field and iterating in copybook storage order.
     *
     * @return an unmodifiable view over the 37 live holders, never {@code null}
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
     * The {@code CARDDEMO-COMMAREA} this request carries, or {@code null} when none travelled.
     *
     * @return the communication area, or {@code null} for a cold start
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    /**
     * Replaces the {@code CARDDEMO-COMMAREA}.
     *
     * <p>{@code null} is stored as {@code null} and is not substituted with
     * {@link NavigationContext#empty()}: an absent area is CICS reporting {@code EIBCALEN} as zero, which
     * an initialised area cannot express.
     *
     * @param navigationContext the communication area, or {@code null} to record that none travelled
     */
    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = navigationContext;
    }

    /**
     * Whether a communication area travelled with this request - the Java reading of {@code EIBCALEN} being
     * non-zero.
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
     * {@value NavigationContext#PGM_CONTEXT_ENTER} where none travelled.
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
     * does an absent communication area, which is the {@code WHEN OTHER} arm at {@code :377}.
     *
     * @return {@code true} when a communication area travelled and its carried program context is
     *     {@value NavigationContext#PGM_CONTEXT_REENTER}
     */
    @JsonIgnore
    public boolean isReenter() {
        return hasNavigationContext() && navigationContext.isReenter();
    }

    /**
     * One field as an image of exactly its declared width: padded on the right with spaces when the stored
     * value is short, truncated on the right when it is long.
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
     * Applies the alphanumeric move rule to all 37 fields in place, leaving every one at exactly its
     * declared width.
     *
     * @param codec the codec whose {@code PIC X} move rule applies
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public void normalize(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to normalise the 37 COACTVW fields "
                + "to their declared widths");
        for (ScreenField field : ScreenField.values()) {
            setValue(field, image(field, codec));
        }
    }

    /**
     * The whole {@code CACTVWAI} input group as exactly {@link #GROUP_LENGTH} bytes.
     *
     * <p>The halfword is written big-endian, which is how a mainframe halfword is laid out, so
     * {@value ScreenFieldMetadata#CURSOR_HERE} renders as {@code 0xFFFF} - two's complement, exactly as
     * {@code COMP PIC S9(4)} storage holds it.
     *
     * @param codec the codec supplying the move rule and the charset
     * @return a new array of exactly {@link #GROUP_LENGTH} bytes, never {@code null}
     * @throws NullPointerException if {@code codec} is {@code null}
     * @throws IllegalArgumentException if the codec's charset does not encode this data one byte per
     *     character
     */
    public byte[] toGroupImage(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to render the CACTVWAI group "
                + "image: it supplies both the PIC X move rule and the charset");
        byte[] group = new byte[GROUP_LENGTH];
        writeCharacters(group, 0, spaces(TIOAPFX_LENGTH), TIOAPFX_LENGTH, codec,
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
            writeCharacters(group, field.dataOffset(), image(field, codec), field.length(), codec,
                    field.describe());
        }
        return group;
    }

    /**
     * Reads a {@link #GROUP_LENGTH}-byte {@code CACTVWAI} image back into a request.
     *
     * @param groupImage the {@link #GROUP_LENGTH}-byte input group; read, never retained
     * @param codec the codec supplying the charset
     * @return a request carrying the image's 37 fields and their metadata, never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if {@code groupImage} is not exactly {@link #GROUP_LENGTH} bytes, or
     *     holds a length item outside the range {@code COMP PIC S9(4)} can represent
     */
    public static AccountViewRequest fromGroupImage(byte[] groupImage, FixedWidthCodec codec) {
        Objects.requireNonNull(groupImage, "A group image is required to read a CACTVWAI area");
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to read a CACTVWAI area: it "
                + "supplies the charset the field data is encoded in");
        if (groupImage.length != GROUP_LENGTH) {
            throw new IllegalArgumentException("The CACTVWAI group of app/cpy-bms/COACTVW.CPY is "
                    + GROUP_LENGTH + " bytes - " + TIOAPFX_LENGTH + " of TIOAPFX prefix, plus "
                    + FIELD_COUNT + " fields at " + FIELD_OVERHEAD + " bytes of overhead each, plus "
                    + PAYLOAD_LENGTH + " bytes of data - but this image is " + groupImage.length
                    + " byte(s)");
        }
        AccountViewRequest request = new AccountViewRequest();
        for (ScreenField field : ScreenField.values()) {
            ScreenFieldMetadata holder = request.metadata.get(field);
            holder.setLength(decodeHalfword(groupImage, field));
            holder.setAttribute(groupImage[field.flagItemOffset()]);
            request.setValue(field, readCharacters(groupImage, field, codec));
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

    private static String readCharacters(byte[] groupImage, ScreenField field, FixedWidthCodec codec) {
        byte[] span = Arrays.copyOfRange(groupImage, field.dataOffset(), field.endOffsetExclusive());
        return codec.decodeImage(span, "the CACTVWAI item " + field.symbolicItemName());
    }

    private static void writeCharacters(byte[] group,
                                        int offset,
                                        String image,
                                        int declaredWidth,
                                        FixedWidthCodec codec,
                                        String what) {
        byte[] encoded = codec.encodeImage(image, what);
        if (encoded.length != declaredWidth) {
            throw new IllegalArgumentException("Charset " + codec.charset().name() + " encodes " + what
                    + " to " + encoded.length + " byte(s) where the copybook declares " + declaredWidth
                    + "; a PIC X(n) item is n bytes, so the CACTVWAI group can only be rendered from an "
                    + "image already at its declared width under a single-byte code page such as IBM037 "
                    + "or US-ASCII");
        }
        System.arraycopy(encoded, 0, group, offset, declaredWidth);
    }

    /**
     * Value equality over the 37 fields, both conversation-state carriers and all 37 metadata holders.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a request holding the same values throughout
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AccountViewRequest that)) {
            return false;
        }
        return sameFieldValues(that)
                && cardScreenState.equals(that.cardScreenState)
                && Objects.equals(navigationContext, that.navigationContext)
                && metadata.equals(that.metadata);
    }

    private boolean sameFieldValues(AccountViewRequest that) {
        for (ScreenField field : ScreenField.values()) {
            if (!value(field).equals(that.value(field))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 1;
        for (ScreenField field : ScreenField.values()) {
            result = 31 * result + value(field).hashCode();
        }
        result = 31 * result + cardScreenState.hashCode();
        result = 31 * result + Objects.hashCode(navigationContext);
        return 31 * result + metadata.hashCode();
    }

    /**
     * A diagnostic rendering that discloses nothing a log must not hold.
     *
     * @return the rendering; never {@code null}
     */
    @Override
    public String toString() {
        StringBuilder rendered = new StringBuilder("AccountViewRequest[");
        for (ScreenField field : ScreenField.values()) {
            rendered.append(field.label())
                    .append("='")
                    .append(DiagnosticText.screenField(field.label(), value(field)))
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
     * One of the 37 name-labelled {@code DFHMDF} fields of {@code app/bms/COACTVW.bms}, in the order the
     * mapset declares them - which is also the order {@code app/cpy-bms/COACTVW.CPY} lays their storage
     * down.
     */
    public enum ScreenField {
        /**
         * {@code TRNNAME} - the transaction identifier, {@code TRNNAMEI PIC X(4)}.
         */
        TRNNAME("TRNNAME", "TRNNAMEI", "X(4)", TRNNAME_LENGTH, 24, 34, 1, 7, 19),

        /**
         * {@code TITLE01} - the first title line, {@code TITLE01I PIC X(40)}.
         */
        TITLE01("TITLE01", "TITLE01I", "X(40)", TITLE01_LENGTH, 30, 38, 1, 21, 30),

        /**
         * {@code CURDATE} - the current date, {@code CURDATEI PIC X(8)}.
         */
        CURDATE("CURDATE", "CURDATEI", "X(8)", CURDATE_LENGTH, 36, 47, 1, 71, 77),

        /**
         * {@code PGMNAME} - the program name, {@code PGMNAMEI PIC X(8)}.
         */
        PGMNAME("PGMNAME", "PGMNAMEI", "X(8)", PGMNAME_LENGTH, 42, 57, 2, 7, 92),

        /**
         * {@code TITLE02} - the second title line, {@code TITLE02I PIC X(40)}.
         */
        TITLE02("TITLE02", "TITLE02I", "X(40)", TITLE02_LENGTH, 48, 61, 2, 21, 107),

        /**
         * {@code CURTIME} - the current time, {@code CURTIMEI PIC X(8)}.
         */
        CURTIME("CURTIME", "CURTIMEI", "X(8)", CURTIME_LENGTH, 54, 70, 2, 71, 154),

        /**
         * {@code ACCTSID} - the account identifier, {@code ACCTSIDI PIC 99999999999}.
         */
        ACCTSID("ACCTSID", "ACCTSIDI", "99999999999", ACCTSID_LENGTH, 60, 84, 5, 38, 169),

        /**
         * {@code ACSTTUS} - the account status, {@code ACSTTUSI PIC X(1)}.
         */
        ACSTTUS("ACSTTUS", "ACSTTUSI", "X(1)", ACSTTUS_LENGTH, 66, 97, 5, 70, 187),

        /**
         * {@code ADTOPEN} - the account open date, {@code ADTOPENI PIC X(10)}.
         */
        ADTOPEN("ADTOPEN", "ADTOPENI", "X(10)", ADTOPEN_LENGTH, 72, 107, 6, 17, 195),

        /**
         * {@code ACRDLIM} - the credit limit, {@code ACRDLIMI PIC X(15)}.
         */
        ACRDLIM("ACRDLIM", "ACRDLIMI", "X(15)", ACRDLIM_LENGTH, 78, 117, 6, 61, 212),

        /**
         * {@code AEXPDT} - the account expiry date, {@code AEXPDTI PIC X(10)}.
         */
        AEXPDT("AEXPDT", "AEXPDTI", "X(10)", AEXPDT_LENGTH, 84, 128, 7, 17, 234),

        /**
         * {@code ACSHLIM} - the cash credit limit, {@code ACSHLIMI PIC X(15)}.
         */
        ACSHLIM("ACSHLIM", "ACSHLIMI", "X(15)", ACSHLIM_LENGTH, 90, 138, 7, 61, 251),

        /**
         * {@code AREISDT} - the account reissue date, {@code AREISDTI PIC X(10)}.
         */
        AREISDT("AREISDT", "AREISDTI", "X(10)", AREISDT_LENGTH, 96, 149, 8, 17, 273),

        /**
         * {@code ACURBAL} - the current balance, {@code ACURBALI PIC X(15)}.
         */
        ACURBAL("ACURBAL", "ACURBALI", "X(15)", ACURBAL_LENGTH, 102, 159, 8, 61, 290),

        /**
         * {@code ACRCYCR} - the current cycle credit, {@code ACRCYCRI PIC X(15)}.
         */
        ACRCYCR("ACRCYCR", "ACRCYCRI", "X(15)", ACRCYCR_LENGTH, 108, 171, 9, 61, 312),

        /**
         * {@code AADDGRP} - the account group identifier, {@code AADDGRPI PIC X(10)}.
         */
        AADDGRP("AADDGRP", "AADDGRPI", "X(10)", AADDGRP_LENGTH, 114, 182, 10, 23, 334),

        /**
         * {@code ACRCYDB} - the current cycle debit, {@code ACRCYDBI PIC X(15)}.
         */
        ACRCYDB("ACRCYDB", "ACRCYDBI", "X(15)", ACRCYDB_LENGTH, 120, 192, 10, 61, 351),

        /**
         * {@code ACSTNUM} - the customer number, {@code ACSTNUMI PIC X(9)}.
         */
        ACSTNUM("ACSTNUM", "ACSTNUMI", "X(9)", ACSTNUM_LENGTH, 126, 207, 12, 23, 373),

        /**
         * {@code ACSTSSN} - the customer social security number, {@code ACSTSSNI PIC X(12)}.
         */
        ACSTSSN("ACSTSSN", "ACSTSSNI", "X(12)", ACSTSSN_LENGTH, 132, 216, 12, 54, 389),

        /**
         * {@code ACSTDOB} - the customer date of birth, {@code ACSTDOBI PIC X(10)}.
         */
        ACSTDOB("ACSTDOB", "ACSTDOBI", "X(10)", ACSTDOB_LENGTH, 138, 225, 13, 23, 408),

        /**
         * {@code ACSTFCO} - the customer FICO score, {@code ACSTFCOI PIC X(3)}.
         */
        ACSTFCO("ACSTFCO", "ACSTFCOI", "X(3)", ACSTFCO_LENGTH, 144, 234, 13, 61, 425),

        /**
         * {@code ACSFNAM} - the customer's given name, {@code ACSFNAMI PIC X(25)}.
         */
        ACSFNAM("ACSFNAM", "ACSFNAMI", "X(25)", ACSFNAM_LENGTH, 150, 251, 15, 1, 435),

        /**
         * {@code ACSMNAM} - the customer's middle name, {@code ACSMNAMI PIC X(25)}.
         */
        ACSMNAM("ACSMNAM", "ACSMNAMI", "X(25)", ACSMNAM_LENGTH, 156, 256, 15, 28, 467),

        /**
         * {@code ACSLNAM} - the customer's surname, {@code ACSLNAMI PIC X(25)}.
         */
        ACSLNAM("ACSLNAM", "ACSLNAMI", "X(25)", ACSLNAM_LENGTH, 162, 261, 15, 55, 499),

        /**
         * {@code ACSADL1} - the first address line, {@code ACSADL1I PIC X(50)}.
         */
        ACSADL1("ACSADL1", "ACSADL1I", "X(50)", ACSADL1_LENGTH, 168, 268, 16, 10, 531),

        /**
         * {@code ACSSTTE} - the state code, {@code ACSSTTEI PIC X(2)}.
         */
        ACSSTTE("ACSSTTE", "ACSSTTEI", "X(2)", ACSSTTE_LENGTH, 174, 277, 16, 73, 588),

        /**
         * {@code ACSADL2} - the second address line, {@code ACSADL2I PIC X(50)}.
         */
        ACSADL2("ACSADL2", "ACSADL2I", "X(50)", ACSADL2_LENGTH, 180, 282, 17, 10, 597),

        /**
         * {@code ACSZIPC} - the postal code, {@code ACSZIPCI PIC X(5)}.
         */
        ACSZIPC("ACSZIPC", "ACSZIPCI", "X(5)", ACSZIPC_LENGTH, 186, 291, 17, 73, 654),

        /**
         * {@code ACSCITY} - the city, {@code ACSCITYI PIC X(50)}.
         */
        ACSCITY("ACSCITY", "ACSCITYI", "X(50)", ACSCITY_LENGTH, 192, 301, 18, 10, 666),

        /**
         * {@code ACSCTRY} - the country code, {@code ACSCTRYI PIC X(3)}.
         */
        ACSCTRY("ACSCTRY", "ACSCTRYI", "X(3)", ACSCTRY_LENGTH, 198, 310, 18, 73, 723),

        /**
         * {@code ACSPHN1} - the first telephone number, {@code ACSPHN1I PIC X(13)}.
         */
        ACSPHN1("ACSPHN1", "ACSPHN1I", "X(13)", ACSPHN1_LENGTH, 204, 319, 19, 10, 733),

        /**
         * {@code ACSGOVT} - the government-issued identifier, {@code ACSGOVTI PIC X(20)}.
         */
        ACSGOVT("ACSGOVT", "ACSGOVTI", "X(20)", ACSGOVT_LENGTH, 210, 326, 19, 58, 753),

        /**
         * {@code ACSPHN2} - the second telephone number, {@code ACSPHN2I PIC X(13)}.
         */
        ACSPHN2("ACSPHN2", "ACSPHN2I", "X(13)", ACSPHN2_LENGTH, 216, 335, 20, 10, 780),

        /**
         * {@code ACSEFTC} - the EFT account code, {@code ACSEFTCI PIC X(10)}.
         */
        ACSEFTC("ACSEFTC", "ACSEFTCI", "X(10)", ACSEFTC_LENGTH, 222, 342, 20, 41, 800),

        /**
         * {@code ACSPFLG} - the primary cardholder flag, {@code ACSPFLGI PIC X(1)}.
         */
        ACSPFLG("ACSPFLG", "ACSPFLGI", "X(1)", ACSPFLG_LENGTH, 228, 351, 20, 78, 817),

        /**
         * {@code INFOMSG} - the informational message line, {@code INFOMSGI PIC X(45)}.
         */
        INFOMSG("INFOMSG", "INFOMSGI", "X(45)", INFOMSG_LENGTH, 234, 356, 22, 23, 825),

        /**
         * {@code ERRMSG} - the error message line, {@code ERRMSGI PIC X(78)}.
         */
        ERRMSG("ERRMSG", "ERRMSGI", "X(78)", ERRMSG_LENGTH, 240, 365, 23, 1, 877);

        private final String label;

        private final String symbolicItemName;

        private final String picture;

        private final int length;

        private final int copybookLine;

        private final int mapsetLine;

        private final int screenRow;

        private final int screenColumn;

        private final int dataOffset;

        ScreenField(String label,
                    String symbolicItemName,
                    String picture,
                    int length,
                    int copybookLine,
                    int mapsetLine,
                    int screenRow,
                    int screenColumn,
                    int dataOffset) {
            this.label = label;
            this.symbolicItemName = symbolicItemName;
            this.picture = picture;
            this.length = length;
            this.copybookLine = copybookLine;
            this.mapsetLine = mapsetLine;
            this.screenRow = screenRow;
            this.screenColumn = screenColumn;
            this.dataOffset = dataOffset;
        }

        /**
         * The field's {@code DFHMDF} label, spelled exactly as {@code app/bms/COACTVW.bms} spells it.
         *
         * @return the label, for example {@code ACSGOVT}; never {@code null}
         */
        public String label() {
            return label;
        }

        /**
         * The input item's name in {@code app/cpy-bms/COACTVW.CPY} - the label with the {@code I} suffix
         * BMS appends for the input group.
         *
         * @return the symbolic-map item name, for example {@code ACSGOVTI}; never {@code null}
         */
        public String symbolicItemName() {
            return symbolicItemName;
        }

        /**
         * The {@code xxxI} {@code PICTURE} exactly as the copybook writes it.
         *
         * @return the {@code PICTURE} text, never {@code null}
         */
        public String picture() {
            return picture;
        }

        /**
         * Whether this field's {@code xxxI} {@code PICTURE} is alphanumeric.
         *
         * @return {@code true} unless this is {@link #ACCTSID}
         */
        public boolean isAlphanumeric() {
            return picture.startsWith("X(");
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
         * The line of {@code app/cpy-bms/COACTVW.CPY} declaring this field's {@code xxxI} item.
         *
         * @return a one-based line number
         */
        public int copybookLine() {
            return copybookLine;
        }

        /**
         * The line of {@code app/bms/COACTVW.bms} opening this field's {@code DFHMDF} entry.
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
         * Offset of this field's {@code xxxI} data within the {@value AccountViewRequest#GROUP_LENGTH}-byte
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
         * @return a zero-based offset, {@value AccountViewRequest#FIELD_OVERHEAD} bytes before
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
         * and, for {@link #ERRMSG}, the group length itself.
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
         *     {@code ACSGOVT ACSGOVTI PIC X(20) COACTVW.CPY:210 COACTVW.bms:326 POS=(19,58) offset 753..773}
         */
        public String describe() {
            return label + " " + symbolicItemName + " PIC " + picture + " COACTVW.CPY:" + copybookLine
                    + " COACTVW.bms:" + mapsetLine + " POS=(" + screenRow + "," + screenColumn
                    + ") offset " + dataOffset + ".." + endOffsetExclusive();
        }

        /**
         * The field carrying a given {@code DFHMDF} label.
         *
         * @param label the {@code DFHMDF} label to look up, for example {@code ACSGOVT}
         * @return the matching field, never {@code null}
         * @throws NullPointerException if {@code label} is {@code null}
         * @throws IllegalArgumentException if no field carries that label
         */
        public static ScreenField byLabel(String label) {
            Objects.requireNonNull(label, "A DFHMDF label is required to look up a COACTVW field");
            for (ScreenField candidate : values()) {
                if (candidate.label.equals(label)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("app/bms/COACTVW.bms declares no name-labelled DFHMDF "
                    + "field called '" + label + "'; it declares " + FIELD_COUNT + ", from "
                    + TRNNAME.label + " to " + ERRMSG.label);
        }
    }

    /**
     * The {@code xxxL} and {@code xxxA} items of one input field: the two pieces of per-field metadata a
     * BMS symbolic map declares beside the data, and the two {@code COACTVWC} actually writes.
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
         * convention {@code app/cbl/COACTVWC.cbl} uses at lines 549 and 551.
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
                throw new IllegalArgumentException("A COACTVW xxxL item is declared COMP PIC S9(4) and so "
                        + "holds " + LENGTH_ITEM_MIN + " to " + LENGTH_ITEM_MAX + "; " + length
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
         * Directs the cursor at this field, the {@code MOVE -1 TO xxxL OF CACTVWAI} of
         * {@code app/cbl/COACTVWC.cbl:549} and {@code :551}.
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
         * Stores the attribute byte, the {@code MOVE DFHBMFSE TO ACCTSIDA OF CACTVWAI} of
         * {@code app/cbl/COACTVWC.cbl:543}.
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
}
