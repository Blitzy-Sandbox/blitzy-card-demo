package com.vsergeychik.carddemo.account.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The outbound payload of {@code GET /api/accounts/{acctId}} - CICS transaction {@code CAVW}, COBOL
 * program {@code app/cbl/COACTVWC.cbl}, BMS mapset {@code app/bms/COACTVW.bms}.
 *
 * <h2>What this type is a projection of</h2>
 *
 * <p>It is the Java form of the <em>output</em> symbolic map,
 * {@code 01 CACTVWAO REDEFINES CACTVWAI.} at {@code app/cpy-bms/COACTVW.CPY:241} - the storage
 * {@code app/cbl/COACTVWC.cbl:911} transmits with
 * {@code EXEC CICS SEND MAP(CCARD-NEXT-MAP) MAPSET(CCARD-NEXT-MAPSET) FROM(CACTVWAO)}. Every one of
 * its {@value #FIELD_COUNT} payload members corresponds to exactly one {@code xxxO} item of that
 * group and to exactly one name-labelled {@code DFHMDF} entry of the mapset, so the gate <b>G9</b>
 * trace - REST field back to screen field - is one hop in each direction and is recorded on every
 * member and on every {@link ScreenField} constant.
 *
 * <p>{@code app/bms/COACTVW.bms} declares 100 {@code DFHMDF} entries of which exactly <b>37</b> carry
 * a name. The other 63 are screen literals - {@code INITIAL='Tran:'},
 * {@code INITIAL='Account Number:'} and the like - which BMS paints but never reports, so they
 * generate no symbolic-map item and hold no value this type could carry.
 *
 * <h2>The 955-byte group</h2>
 *
 * <p>{@value #TIOAPFX_LENGTH} bytes of {@code TIOAPFX} prefix, then {@value #FIELD_COUNT} fields each
 * preceded by {@value #FIELD_OVERHEAD} bytes of overhead, then {@value #PAYLOAD_LENGTH} bytes of
 * field data: {@value #TIOAPFX_LENGTH} + {@value #FIELD_COUNT} &times; {@value #FIELD_OVERHEAD} +
 * {@value #PAYLOAD_LENGTH} = <b>{@value #GROUP_LENGTH}</b>. {@link #toGroupImage(FixedWidthCodec)}
 * renders exactly that, and {@link #fromGroupImage(byte[], FixedWidthCodec)} reads it back.
 *
 * <p>The output group's {@value #FIELD_OVERHEAD} bytes are composed differently from the input
 * group's, and the coincidence of the totals is what makes {@code REDEFINES} legal:
 *
 * <table border="1">
 *   <caption>Per-field overhead, input group against output group</caption>
 *   <tr><th>{@code CACTVWAI} (input)</th><th>{@code CACTVWAO} (output)</th></tr>
 *   <tr><td>{@code xxxL COMP PIC S9(4)} - 2 bytes</td>
 *       <td>{@code FILLER PICTURE X(3)} - {@value #FILLER_LENGTH} bytes</td></tr>
 *   <tr><td>{@code xxxF PICTURE X} / {@code xxxA REDEFINES} - 1 byte</td>
 *       <td>{@code xxxC PICTURE X} - colour</td></tr>
 *   <tr><td>{@code FILLER PICTURE X(4)} - 4 bytes</td>
 *       <td>{@code xxxP PICTURE X} - programmed symbols</td></tr>
 *   <tr><td></td><td>{@code xxxH PICTURE X} - highlight</td></tr>
 *   <tr><td></td><td>{@code xxxV PICTURE X} - validation</td></tr>
 *   <tr><td><b>7 bytes</b></td><td><b>{@value #FIELD_OVERHEAD} bytes</b></td></tr>
 * </table>
 *
 * <p>So each field's data offset is identical on both sides, and {@link AccountViewRequest} and this
 * type agree field for field on label, order, width and offset. The only two differences are stated
 * where they belong: {@link ScreenField#ACCTSID}'s {@code PICTURE} asymmetry, and the five
 * numeric-edited money items below.
 *
 * <p>The quad exists because {@code app/bms/COACTVW.bms:23-26} declares
 * {@code CACTVWA DFHMDI ... DSATTS=(COLOR,HILIGHT,PS,VALIDN), MAPATTS=(COLOR,HILIGHT,PS,VALIDN)}. It
 * is <em>not</em> a consequence of {@code EXTATT}; see the divergence note below.
 *
 * <h2>The five numeric-edited money items</h2>
 *
 * <p>{@code ACRDLIMO}, {@code ACSHLIMO}, {@code ACURBALO}, {@code ACRCYCRO} and {@code ACRCYDBO} are
 * declared {@code PIC +ZZZ,ZZZ,ZZZ.99} at {@code app/cpy-bms/COACTVW.CPY:302}, {@code :314},
 * {@code :326}, {@code :332} and {@code :344}, and the mapset declares the same mask as
 * {@code PICOUT='+ZZZ,ZZZ,ZZZ.99'} with {@code LENGTH=15, JUSTIFY=(RIGHT)} at
 * {@code app/bms/COACTVW.bms:120}, {@code :141}, {@code :162}, {@code :174} and {@code :195}. Their
 * <em>input</em> twins are plain {@code PIC X(15)}, so the mask is an output-side concern only.
 * {@link #editAmount(BigDecimal)} reproduces it byte for byte and documents every rule it applies.
 *
 * <h2>No session state</h2>
 *
 * <p>Rule <b>R6</b>, gates <b>G37</b> and <b>G40</b>. {@code app/cbl/COACTVWC.cbl:349} transfers
 * control with {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)}; here the
 * target travels back to the client as {@link #getNextProgram()}, {@link #getNextMapset()} and
 * {@link #getNextMap()}, and the conversation state travels as the two carriers
 * {@link #getCardScreenState()} and {@link #getNavigationContext()}. There is no {@code HttpSession},
 * no {@code @SessionAttributes}, no server-side cache, no static map and no thread local anywhere in
 * this file.
 *
 * <h2>Immutability, and the one deliberate exception</h2>
 *
 * <p>The {@value #FIELD_COUNT} payload members, the next-screen triple and both carriers are
 * {@code final} and are set once, through {@link Builder}. A derived payload is obtained with
 * {@link #toBuilder()} or {@link #withHighlight(ScreenField, FieldHighlight)}, never by mutation.
 *
 * <p>The exception is the per-field attribute quad. {@code app/cbl/COACTVWC.cbl:555-571} writes
 * {@code xxxC} bytes after the map has been populated, and {@link FieldAttributeSetter} is specified
 * to target two destinations - the colour item and the output item - so those attribute bytes are
 * writable in place through {@link #attributes(ScreenField)}. They are excluded from JSON: per the
 * symbolic-map contract the {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV} items are
 * presentation metadata, never payload members.
 *
 * <h2>Divergences recorded rather than corrected</h2>
 *
 * <ul>
 *   <li><b>The mapset header.</b> {@code app/bms/COACTVW.bms:18-22} declares
 *       {@code COACTVW DFHMSD LANG=COBOL, MODE=INOUT, STORAGE=AUTO, TIOAPFX=YES, TYPE=&amp;&amp;SYSPARM}
 *       - with <em>no</em> {@code CTRL=} and <em>no</em> {@code EXTATT=} - and {@code :23-26} puts
 *       {@code CTRL=(FREEKB)} on the {@code DFHMDI}, <em>without</em> {@code ALARM}. A blanket claim
 *       that every mapset carries {@code DFHMSD CTRL=(ALARM,FREEKB) EXTATT=YES} does not hold for
 *       this one. Recorded, not corrected (practice <b>B4</b>).</li>
 *   <li><b>The group name.</b> {@code CACTVWAI} and {@code CACTVWAO} drop the leading {@code O} of
 *       {@code COACTVW}; the map is {@code CACTVWA}, not {@code COACTVWA}.</li>
 *   <li><b>Two source oddities left alone</b> (practice <b>B5</b>): the commented-out
 *       {@code MOVE CUST-SSN TO ACSTSSNO} at {@code app/cbl/COACTVWC.cbl:495}, superseded by the
 *       hyphenating {@code STRING} beneath it; and the two textually identical
 *       {@code MOVE -1 TO ACCTSIDL} arms of the {@code EVALUATE} at {@code :549} and {@code :551}.
 *       Neither is tidied.</li>
 *   <li><b>A misspelling preserved</b> (implicit requirement <b>I1</b>): {@link #getAexpdt()} is fed
 *       from {@code ACCT-EXPIRAION-DATE}, spelled exactly that way in
 *       {@code app/cpy/CVACT01Y.cpy}.</li>
 * </ul>
 *
 * <h2>Cardholder data is not masked here</h2>
 *
 * <p>Practice <b>B6</b>. {@code ACSTSSN}, {@code ACSTDOB} and {@code ACSGOVT} are carried, serialised
 * and rendered exactly as {@code COACTVWC} puts them on the 3270. No {@code @JsonIgnore}, no
 * masking, no redaction and no serialisation filter is applied to any of the {@value #FIELD_COUNT}
 * fields, because doing so would change what the screen shows and is a behaviour change nobody asked
 * for. This is the same deliberate divergence {@link AccountViewRequest} records: the sibling
 * payloads route their diagnostics through {@code common/SensitiveDiagnostics}, which is outside this
 * file's declared dependency set. Nothing is weakened either - this type exposes no value the screen
 * does not already display.
 *
 * <h2>Thread safety</h2>
 *
 * <p>The payload is immutable and safe to publish. The attribute quads are not: an instance whose
 * quads are being written must not be read concurrently. That mirrors the program, which paints the
 * attributes on one task before sending the map.
 *
 * @see AccountViewRequest
 * @see ScreenField
 * @see FieldAttributes
 * @see Builder
 * @see #editAmount(BigDecimal)
 */
@JsonDeserialize(builder = AccountViewResponse.Builder.class)
public final class AccountViewResponse {

    // =================================================================================================
    // Screen identity. Four literals, transcribed from app/cbl/COACTVWC.cbl:143-149 including the
    // trailing space LIT-THISMAPSET carries, because that space is what the X(8) to X(7) move discards.
    // =================================================================================================

    /**
     * {@code LIT-THISPGM PIC X(8) VALUE 'COACTVWC'}, {@code app/cbl/COACTVWC.cbl:143}.
     *
     * <p>Written into {@code PGMNAMEO} at {@code :439} and into {@code CDEMO-FROM-PROGRAM} at
     * {@code :345}. Exactly {@value #PGMNAME_LENGTH} characters, which is what both receivers declare.
     */
    public static final String THIS_PROGRAM = "COACTVWC";

    /**
     * {@code LIT-THISTRANID PIC X(4) VALUE 'CAVW'}, {@code app/cbl/COACTVWC.cbl:146-147}.
     *
     * <p>Written into {@code TRNNAMEO} at {@code :438}. The CSD transaction identifier of this screen.
     */
    public static final String THIS_TRANID = "CAVW";

    /**
     * {@code LIT-THISMAPSET PIC X(8) VALUE 'COACTVW '}, {@code app/cbl/COACTVWC.cbl:148}.
     *
     * <p><strong>The trailing space is part of the literal</strong> and is transcribed here. It matters:
     * {@code app/cbl/COACTVWC.cbl:906} moves this {@code X(8)} value into
     * {@code CCARD-NEXT-MAPSET PIC X(7)}, so the alphanumeric move discards the eighth character on the
     * right and the transmitted mapset name is the seven-character {@code "COACTVW"}. That is why
     * {@link #getNextMapset()} is {@value #NEXT_MAPSET_LENGTH} wide and not 8.
     */
    public static final String THIS_MAPSET = "COACTVW ";

    /**
     * {@code LIT-THISMAP PIC X(7) VALUE 'CACTVWA'}, {@code app/cbl/COACTVWC.cbl:149}.
     *
     * <p>The map name, written into {@code CCARD-NEXT-MAP} at {@code app/cbl/COACTVWC.cbl:907}. It is
     * also the {@code (MAPNAME3)} token {@code app/cpy/CSSETATY.cpy} substitutes, and is what
     * {@link #withHighlight(ScreenField, FieldValidationState, boolean)} passes to
     * {@link FieldAttributeSetter}.
     */
    public static final String MAP_NAME = "CACTVWA";

    // =================================================================================================
    // The 37 field widths, in the order app/cpy-bms/COACTVW.CPY lays their storage down.
    //
    // Each is the xxxO item's PICTURE width and the mapset's LENGTH= operand at the same time; the two
    // sources agree on all 37 with no exception, so a single constant states both. Naming mirrors
    // AccountViewRequest exactly - <DFHMDF LABEL>_LENGTH, with no O suffix - so the two halves of the
    // CAVW contract can be diffed constant for constant.
    //
    // The five money items are 15 because the mask +ZZZ,ZZZ,ZZZ.99 occupies 15 character positions:
    // 1 sign + 3 digits + 1 comma + 3 digits + 1 comma + 3 digits + 1 point + 2 digits.
    // =================================================================================================

    /** {@code TRNNAMEO PIC X(4)}, {@code app/cpy-bms/COACTVW.CPY:248}; {@code LENGTH=4}. */
    public static final int TRNNAME_LENGTH = 4;

    /** {@code TITLE01O PIC X(40)}, {@code app/cpy-bms/COACTVW.CPY:254}; {@code LENGTH=40}. */
    public static final int TITLE01_LENGTH = 40;

    /** {@code CURDATEO PIC X(8)}, {@code app/cpy-bms/COACTVW.CPY:260}; {@code LENGTH=8}. */
    public static final int CURDATE_LENGTH = 8;

    /** {@code PGMNAMEO PIC X(8)}, {@code app/cpy-bms/COACTVW.CPY:266}; {@code LENGTH=8}. */
    public static final int PGMNAME_LENGTH = 8;

    /** {@code TITLE02O PIC X(40)}, {@code app/cpy-bms/COACTVW.CPY:272}; {@code LENGTH=40}. */
    public static final int TITLE02_LENGTH = 40;

    /** {@code CURTIMEO PIC X(8)}, {@code app/cpy-bms/COACTVW.CPY:278}; {@code LENGTH=8}. */
    public static final int CURTIME_LENGTH = 8;

    /**
     * {@code ACCTSIDO PIC X(11)}, {@code app/cpy-bms/COACTVW.CPY:284}; {@code LENGTH=11}.
     *
     * <p>The width agrees with the input twin; the {@code PICTURE} does not. See
     * {@link ScreenField#ACCTSID}.
     */
    public static final int ACCTSID_LENGTH = 11;

    /** {@code ACSTTUSO PIC X(1)}, {@code app/cpy-bms/COACTVW.CPY:290}; {@code LENGTH=1}. */
    public static final int ACSTTUS_LENGTH = 1;

    /** {@code ADTOPENO PIC X(10)}, {@code app/cpy-bms/COACTVW.CPY:296}; {@code LENGTH=10}. */
    public static final int ADTOPEN_LENGTH = 10;

    /**
     * {@code ACRDLIMO PIC +ZZZ,ZZZ,ZZZ.99}, {@code app/cpy-bms/COACTVW.CPY:302}; {@code LENGTH=15},
     * {@code PICOUT='+ZZZ,ZZZ,ZZZ.99'} at {@code app/bms/COACTVW.bms:120}.
     */
    public static final int ACRDLIM_LENGTH = 15;

    /** {@code AEXPDTO PIC X(10)}, {@code app/cpy-bms/COACTVW.CPY:308}; {@code LENGTH=10}. */
    public static final int AEXPDT_LENGTH = 10;

    /**
     * {@code ACSHLIMO PIC +ZZZ,ZZZ,ZZZ.99}, {@code app/cpy-bms/COACTVW.CPY:314}; {@code LENGTH=15},
     * {@code PICOUT='+ZZZ,ZZZ,ZZZ.99'} at {@code app/bms/COACTVW.bms:141}.
     */
    public static final int ACSHLIM_LENGTH = 15;

    /** {@code AREISDTO PIC X(10)}, {@code app/cpy-bms/COACTVW.CPY:320}; {@code LENGTH=10}. */
    public static final int AREISDT_LENGTH = 10;

    /**
     * {@code ACURBALO PIC +ZZZ,ZZZ,ZZZ.99}, {@code app/cpy-bms/COACTVW.CPY:326}; {@code LENGTH=15},
     * {@code PICOUT='+ZZZ,ZZZ,ZZZ.99'} at {@code app/bms/COACTVW.bms:162}.
     */
    public static final int ACURBAL_LENGTH = 15;

    /**
     * {@code ACRCYCRO PIC +ZZZ,ZZZ,ZZZ.99}, {@code app/cpy-bms/COACTVW.CPY:332}; {@code LENGTH=15},
     * {@code PICOUT='+ZZZ,ZZZ,ZZZ.99'} at {@code app/bms/COACTVW.bms:174}.
     */
    public static final int ACRCYCR_LENGTH = 15;

    /** {@code AADDGRPO PIC X(10)}, {@code app/cpy-bms/COACTVW.CPY:338}; {@code LENGTH=10}. */
    public static final int AADDGRP_LENGTH = 10;

    /**
     * {@code ACRCYDBO PIC +ZZZ,ZZZ,ZZZ.99}, {@code app/cpy-bms/COACTVW.CPY:344}; {@code LENGTH=15},
     * {@code PICOUT='+ZZZ,ZZZ,ZZZ.99'} at {@code app/bms/COACTVW.bms:195}.
     */
    public static final int ACRCYDB_LENGTH = 15;

    /** {@code ACSTNUMO PIC X(9)}, {@code app/cpy-bms/COACTVW.CPY:350}; {@code LENGTH=9}. */
    public static final int ACSTNUM_LENGTH = 9;

    /**
     * {@code ACSTSSNO PIC X(12)}, {@code app/cpy-bms/COACTVW.CPY:356}; {@code LENGTH=12}.
     *
     * <p>Twelve bytes of storage that the program fills only eleven of; see
     * {@link #ACSTSSN_STRING_LENGTH}.
     */
    public static final int ACSTSSN_LENGTH = 12;

    /** {@code ACSTDOBO PIC X(10)}, {@code app/cpy-bms/COACTVW.CPY:362}; {@code LENGTH=10}. */
    public static final int ACSTDOB_LENGTH = 10;

    /** {@code ACSTFCOO PIC X(3)}, {@code app/cpy-bms/COACTVW.CPY:368}; {@code LENGTH=3}. */
    public static final int ACSTFCO_LENGTH = 3;

    /** {@code ACSFNAMO PIC X(25)}, {@code app/cpy-bms/COACTVW.CPY:374}; {@code LENGTH=25}. */
    public static final int ACSFNAM_LENGTH = 25;

    /** {@code ACSMNAMO PIC X(25)}, {@code app/cpy-bms/COACTVW.CPY:380}; {@code LENGTH=25}. */
    public static final int ACSMNAM_LENGTH = 25;

    /** {@code ACSLNAMO PIC X(25)}, {@code app/cpy-bms/COACTVW.CPY:386}; {@code LENGTH=25}. */
    public static final int ACSLNAM_LENGTH = 25;

    /** {@code ACSADL1O PIC X(50)}, {@code app/cpy-bms/COACTVW.CPY:392}; {@code LENGTH=50}. */
    public static final int ACSADL1_LENGTH = 50;

    /** {@code ACSSTTEO PIC X(2)}, {@code app/cpy-bms/COACTVW.CPY:398}; {@code LENGTH=2}. */
    public static final int ACSSTTE_LENGTH = 2;

    /** {@code ACSADL2O PIC X(50)}, {@code app/cpy-bms/COACTVW.CPY:404}; {@code LENGTH=50}. */
    public static final int ACSADL2_LENGTH = 50;

    /**
     * {@code ACSZIPCO PIC X(5)}, {@code app/cpy-bms/COACTVW.CPY:410}; {@code LENGTH=5},
     * {@code JUSTIFY=(RIGHT)} at {@code app/bms/COACTVW.bms:291}.
     *
     * <p>Narrower than its source: {@code app/cbl/COACTVWC.cbl:514} moves
     * {@code CUST-ADDR-ZIP PIC X(10)} in, and the alphanumeric move discards the five rightmost
     * characters.
     */
    public static final int ACSZIPC_LENGTH = 5;

    /** {@code ACSCITYO PIC X(50)}, {@code app/cpy-bms/COACTVW.CPY:416}; {@code LENGTH=50}. */
    public static final int ACSCITY_LENGTH = 50;

    /** {@code ACSCTRYO PIC X(3)}, {@code app/cpy-bms/COACTVW.CPY:422}; {@code LENGTH=3}. */
    public static final int ACSCTRY_LENGTH = 3;

    /**
     * {@code ACSPHN1O PIC X(13)}, {@code app/cpy-bms/COACTVW.CPY:428}; {@code LENGTH=13}.
     *
     * <p>Narrower than its source {@code CUST-PHONE-NUM-1 PIC X(15)}, so the move at
     * {@code app/cbl/COACTVWC.cbl:518} discards the two rightmost characters.
     */
    public static final int ACSPHN1_LENGTH = 13;

    /** {@code ACSGOVTO PIC X(20)}, {@code app/cpy-bms/COACTVW.CPY:434}; {@code LENGTH=20}. */
    public static final int ACSGOVT_LENGTH = 20;

    /**
     * {@code ACSPHN2O PIC X(13)}, {@code app/cpy-bms/COACTVW.CPY:440}; {@code LENGTH=13}.
     *
     * <p>Narrower than its source {@code CUST-PHONE-NUM-2 PIC X(15)}, exactly as {@code ACSPHN1O} is.
     */
    public static final int ACSPHN2_LENGTH = 13;

    /** {@code ACSEFTCO PIC X(10)}, {@code app/cpy-bms/COACTVW.CPY:446}; {@code LENGTH=10}. */
    public static final int ACSEFTC_LENGTH = 10;

    /** {@code ACSPFLGO PIC X(1)}, {@code app/cpy-bms/COACTVW.CPY:452}; {@code LENGTH=1}. */
    public static final int ACSPFLG_LENGTH = 1;

    /**
     * {@code INFOMSGO PIC X(45)}, {@code app/cpy-bms/COACTVW.CPY:458}; {@code LENGTH=45},
     * {@code ATTRB=(PROT), COLOR=NEUTRAL, HILIGHT=OFF} at {@code app/bms/COACTVW.bms:356}.
     *
     * <p><strong>45, the map width</strong> - not the 40 of {@code WS-INFO-MSG}. See
     * {@link #INFOMSG_INFO_MESSAGE_PADDING}.
     */
    public static final int INFOMSG_LENGTH = 45;

    /**
     * {@code ERRMSGO PIC X(78)}, {@code app/cpy-bms/COACTVW.CPY:464}; {@code LENGTH=78},
     * {@code ATTRB=(ASKIP,BRT,FSET), COLOR=RED} at {@code app/bms/COACTVW.bms:365}.
     *
     * <p><strong>78, the map width</strong> - not the 75 of {@code WS-RETURN-MSG}. See
     * {@link #ERRMSG_RETURN_MESSAGE_PADDING}.
     */
    public static final int ERRMSG_LENGTH = 78;


    // =================================================================================================
    // Group geometry. Every number here is derived from the ones above rather than restated, so a width
    // corrected in one place cannot leave two numbers disagreeing. The static initialiser then checks the
    // derived totals against the figures this file's documentation states.
    // =================================================================================================

    /** The {@value #FIELD_COUNT} name-labelled {@code DFHMDF} entries of {@code app/bms/COACTVW.bms}. */
    public static final int FIELD_COUNT = 37;

    /**
     * The {@code 02 FILLER PIC X(12)} that opens the group at {@code app/cpy-bms/COACTVW.CPY:242}.
     *
     * <p>The terminal input/output area prefix {@code DFHMSD TIOAPFX=YES} asks BMS to generate. It carries
     * no application data; it is written so the group is the width the copybook declares.
     */
    public static final int TIOAPFX_LENGTH = 12;

    /** The {@code 02 FILLER PICTURE X(3)} that opens every field's overhead in {@code CACTVWAO}. */
    public static final int FILLER_LENGTH = 3;

    /** One attribute item - {@code xxxC}, {@code xxxP}, {@code xxxH} or {@code xxxV} - is one byte. */
    public static final int ATTRIBUTE_ITEM_LENGTH = 1;

    /**
     * Four attribute items per field, one for each keyword of
     * {@code DSATTS=(COLOR,HILIGHT,PS,VALIDN)}.
     */
    public static final int ATTRIBUTE_ITEMS_PER_FIELD = 4;

    /**
     * The suffix of a programmed-symbols item name, as {@code app/cpy-bms/COACTVW.CPY} spells it.
     *
     * <p>Declared here rather than taken from {@link FieldAttributeSetter} because that class knows only
     * the two items {@code CSSETATY} writes - the colour item {@code C} and the output item {@code O} -
     * while {@code DSATTS=(COLOR,HILIGHT,PS,VALIDN)} generates four.
     */
    public static final String PS_ITEM_SUFFIX = "P";

    /** The suffix of a highlight item name, from the {@code HILIGHT} keyword of {@code DSATTS}. */
    public static final String HILIGHT_ITEM_SUFFIX = "H";

    /** The suffix of a validation item name, from the {@code VALIDN} keyword of {@code DSATTS}. */
    public static final String VALIDN_ITEM_SUFFIX = "V";

    /**
     * Bytes of overhead before each field's data: {@value #FILLER_LENGTH} +
     * {@value #ATTRIBUTE_ITEMS_PER_FIELD} &times; {@value #ATTRIBUTE_ITEM_LENGTH} = <b>7</b>.
     *
     * <p>Numerically equal to the input group's {@code 2 + 1 + 4}, which is what lets
     * {@code CACTVWAO REDEFINES CACTVWAI} line up field for field.
     */
    public static final int FIELD_OVERHEAD =
            FILLER_LENGTH + ATTRIBUTE_ITEMS_PER_FIELD * ATTRIBUTE_ITEM_LENGTH;

    /**
     * Bytes of field data in the group - <b>684</b>.
     *
     * <p>Written as the sum of the {@value #FIELD_COUNT} width constants rather than as the literal, so a
     * corrected width propagates here. The static initialiser still checks the total, because a width
     * wrong in the same direction as this sum would otherwise go unnoticed.
     */
    public static final int PAYLOAD_LENGTH = TRNNAME_LENGTH + TITLE01_LENGTH + CURDATE_LENGTH
            + PGMNAME_LENGTH + TITLE02_LENGTH + CURTIME_LENGTH + ACCTSID_LENGTH + ACSTTUS_LENGTH
            + ADTOPEN_LENGTH + ACRDLIM_LENGTH + AEXPDT_LENGTH + ACSHLIM_LENGTH + AREISDT_LENGTH
            + ACURBAL_LENGTH + ACRCYCR_LENGTH + AADDGRP_LENGTH + ACRCYDB_LENGTH + ACSTNUM_LENGTH
            + ACSTSSN_LENGTH + ACSTDOB_LENGTH + ACSTFCO_LENGTH + ACSFNAM_LENGTH + ACSMNAM_LENGTH
            + ACSLNAM_LENGTH + ACSADL1_LENGTH + ACSSTTE_LENGTH + ACSADL2_LENGTH + ACSZIPC_LENGTH
            + ACSCITY_LENGTH + ACSCTRY_LENGTH + ACSPHN1_LENGTH + ACSGOVT_LENGTH + ACSPHN2_LENGTH
            + ACSEFTC_LENGTH + ACSPFLG_LENGTH + INFOMSG_LENGTH + ERRMSG_LENGTH;

    /**
     * Bytes in the whole {@code CACTVWAO} group: {@value #TIOAPFX_LENGTH} + {@value #FIELD_COUNT} &times;
     * {@value #FIELD_OVERHEAD} + {@value #PAYLOAD_LENGTH} = <b>955</b>.
     */
    public static final int GROUP_LENGTH =
            TIOAPFX_LENGTH + FIELD_COUNT * FIELD_OVERHEAD + PAYLOAD_LENGTH;

    // =================================================================================================
    // The next-screen triple. Widths are taken from CardScreenState rather than restated, because these
    // three values are exactly what CVCRD01Y's CCARD-NEXT-PROG, CCARD-NEXT-MAPSET and CCARD-NEXT-MAP
    // hold, and one declaration of a width is always better than two that can drift apart.
    // =================================================================================================

    /** {@code CCARD-NEXT-PROG PIC X(8)} - the {@code XCTL} target program name. */
    public static final int NEXT_PROGRAM_LENGTH = CardScreenState.CCARD_NEXT_PROG_LENGTH;

    /** {@code CCARD-NEXT-MAPSET PIC X(7)} - see {@link #THIS_MAPSET} for why this is 7 and not 8. */
    public static final int NEXT_MAPSET_LENGTH = CardScreenState.CCARD_NEXT_MAPSET_LENGTH;

    /** {@code CCARD-NEXT-MAP PIC X(7)} - the map name, {@value #MAP_NAME} for this screen. */
    public static final int NEXT_MAP_LENGTH = CardScreenState.CCARD_NEXT_MAP_LENGTH;

    // =================================================================================================
    // The cross-width MOVEs into the two message lines, stated as constants so the widening is a number a
    // reviewer can check rather than a claim in prose.
    // =================================================================================================

    /**
     * {@code WS-RETURN-MSG PIC X(75)}, {@code app/cbl/COACTVWC.cbl:117}.
     *
     * <p>Taken from {@link CardScreenState#CCARD_ERROR_MSG_LENGTH} because the two are the same width for
     * the same reason: {@code COMMON-RETURN} at {@code app/cbl/COACTVWC.cbl:388} and {@code :395} moves
     * this value into {@code CCARD-ERROR-MSG}, so the error text reaches the client through the work area
     * as well as through {@code ERRMSGO}. Both routes are kept; they are not deduplicated.
     */
    public static final int WS_RETURN_MSG_LENGTH = CardScreenState.CCARD_ERROR_MSG_LENGTH;

    /**
     * {@code WS-INFO-MSG PIC X(40)}, {@code app/cbl/COACTVWC.cbl:110}, with
     * {@code 88 WS-NO-INFO-MESSAGE VALUES SPACES LOW-VALUES}.
     */
    public static final int WS_INFO_MSG_LENGTH = 40;

    /**
     * Spaces {@code WS-RETURN-MSG} gains on the right when moved into {@code ERRMSGO}:
     * {@value #ERRMSG_LENGTH} - {@value #WS_RETURN_MSG_LENGTH} = 3.
     */
    public static final int ERRMSG_RETURN_MESSAGE_PADDING = ERRMSG_LENGTH - WS_RETURN_MSG_LENGTH;

    /**
     * Spaces {@code WS-INFO-MSG} gains on the right when moved into {@code INFOMSGO}:
     * {@value #INFOMSG_LENGTH} - {@value #WS_INFO_MSG_LENGTH} = 5.
     */
    public static final int INFOMSG_INFO_MESSAGE_PADDING = INFOMSG_LENGTH - WS_INFO_MSG_LENGTH;

    /**
     * Spaces a standard {@link SystemMessages} text gains on the right in {@code ERRMSGO}:
     * {@value #ERRMSG_LENGTH} - 50 = 28.
     */
    public static final int ERRMSG_STANDARD_MESSAGE_PADDING =
            ERRMSG_LENGTH - SystemMessages.MESSAGE_LENGTH;

    /**
     * Characters a standard {@link SystemMessages} text loses on the right in {@code INFOMSGO}:
     * 50 - {@value #INFOMSG_LENGTH} = 5.
     *
     * <p>{@code INFOMSGO} is the one receiver on this screen narrower than a standard message, which is
     * why the direction is stated here rather than assumed to be padding.
     */
    public static final int INFOMSG_STANDARD_MESSAGE_TRUNCATION =
            SystemMessages.MESSAGE_LENGTH - INFOMSG_LENGTH;

    /**
     * Characters the hyphenating {@code STRING} writes into {@code ACSTSSNO}: 3 + 1 + 2 + 1 + 4 = 11, of
     * {@value #ACSTSSN_LENGTH} declared.
     *
     * <p>{@code app/cbl/COACTVWC.cbl:496-504} composes the value with
     * {@code STRING CUST-SSN(1:3) '-' CUST-SSN(4:2) '-' CUST-SSN(6:4) DELIMITED BY SIZE INTO ACSTSSNO}.
     * A COBOL {@code STRING} <strong>does not blank the receiver's tail</strong>, so the twelfth byte
     * keeps whatever it already held - {@code LOW-VALUES}, after
     * {@code MOVE LOW-VALUES TO CACTVWAO} at {@code :432}. That is why
     * {@link Builder#acstssnFromSsn(String)} exists and why it does not space-pad.
     */
    public static final int ACSTSSN_STRING_LENGTH = 11;

    /** The separator the {@code STRING} at {@code app/cbl/COACTVWC.cbl:497-503} inserts twice. */
    public static final String SSN_GROUP_SEPARATOR = "-";

    /**
     * {@code CUST-SSN PIC 9(09)} of {@code app/cpy/CVCUS01Y.cpy} - <b>9</b> characters.
     *
     * <p>The {@code STRING}'s three reference modifications - {@code (1:3)}, {@code (4:2)} and
     * {@code (6:4)} - consume exactly these nine and nothing more, which is why
     * {@link Builder#acstssnFromSsn(String)} insists on the width.
     */
    public static final int SSN_LENGTH = 9;

    /**
     * The whole-field marker {@code app/cbl/COACTVWC.cbl:563} moves into {@code ACCTSIDO} when the filter
     * is blank on re-entry: {@link FieldAttributeSetter#ASTERISK}.
     *
     * <p>Referenced rather than restated so there is one definition of {@code CSSETATY}'s marker.
     */
    public static final String BLANK_FIELD_MARKER = FieldAttributeSetter.ASTERISK;

    // =================================================================================================
    // The PIC +ZZZ,ZZZ,ZZZ.99 edit mask. Geometry only; the rules live on editAmount(BigDecimal).
    //
    //   index  0  1  2  3  4  5  6  7  8  9 10 11 12 13 14
    //   mask   +  Z  Z  Z  ,  Z  Z  Z  ,  Z  Z  Z  .  9  9
    //
    // integer digit i (0..8) -> mask index 1 + (i / 3) * 4 + (i % 3)  =>  1 2 3 5 6 7 9 10 11
    // group separator g (0,1) -> mask index 1 + (g + 1) * 4 - 1       =>  4 8
    // =================================================================================================

    /** The mask exactly as the copybook and the mapset write it. */
    public static final String AMOUNT_PICTURE = "+ZZZ,ZZZ,ZZZ.99";

    /** Character positions the mask occupies: 1 + 3 + 1 + 3 + 1 + 3 + 1 + 2 = <b>15</b>. */
    public static final int AMOUNT_MASK_WIDTH = 15;

    /**
     * Integer digit positions the mask provides: <b>9</b>.
     *
     * <p>Every source field is {@code PIC S9(10)V99} - <em>ten</em> integer digits - so a value with a
     * tenth integer digit loses it. See {@link #editAmount(BigDecimal)}.
     */
    public static final int AMOUNT_INTEGER_POSITIONS = 9;

    /** Fraction digit positions the mask provides, all forced: <b>2</b>. */
    public static final int AMOUNT_FRACTION_DIGITS = 2;

    /** Digits between group separators: <b>3</b>. */
    public static final int AMOUNT_GROUP_SIZE = 3;

    /**
     * Digits the mask can carry in total: {@value #AMOUNT_INTEGER_POSITIONS} +
     * {@value #AMOUNT_FRACTION_DIGITS} = 11.
     */
    public static final int AMOUNT_DIGIT_POSITIONS = AMOUNT_INTEGER_POSITIONS + AMOUNT_FRACTION_DIGITS;

    /**
     * Integer digits each source field declares: {@code PIC S9(10)V99} - <b>10</b>.
     *
     * <p>One more than the mask provides, which is the whole reason left truncation is reachable.
     */
    public static final int SOURCE_INTEGER_DIGITS = 10;

    /** How many of the {@value #FIELD_COUNT} fields carry the mask: <b>5</b>. */
    public static final int AMOUNT_MASK_FIELD_COUNT = 5;


    // =================================================================================================
    // The only static references on this class. Every one is final and immutable, so none of them is
    // mutable static state (practice B9, gate G53).
    // =================================================================================================

    /**
     * The {@code PIC X} move rule: right-pad with spaces when short, truncate on the right when long.
     *
     * <p>{@link FixedWidthCodec} is documented immutable and thread safe - it holds nothing but a
     * {@link java.nio.charset.Charset} - so a {@code static final} reference to one is a constant.
     *
     * <p>Only {@link FixedWidthCodec#movePicX(String, int)} is taken from it, and that operation is
     * charset independent: it pads and truncates <em>characters</em> and never encodes a byte. US-ASCII
     * is named explicitly because the constructor demands a charset and a platform default is never
     * acceptable in this system (practice <b>B8</b>); the choice cannot influence any value this class
     * produces. Turning a rendered image into real bytes is
     * {@link #toGroupImage(FixedWidthCodec)}'s business, and there the code page is the caller's
     * deliberate decision.
     */
    private static final FixedWidthCodec PIC_X_CODEC = new FixedWidthCodec(StandardCharsets.US_ASCII);

    /**
     * The {@code LOW-VALUES} byte: binary zero.
     *
     * <p>{@code LOW-VALUES} is the lowest character of the collating sequence, which is {@code X'00'} in
     * both ASCII and EBCDIC, so this one byte is correct on either code page without consulting a
     * charset. It is the state {@code MOVE LOW-VALUES TO CACTVWAO} at {@code app/cbl/COACTVWC.cbl:432}
     * leaves the entire group in, and therefore the initial value of every attribute item.
     *
     * <p>It is the same byte as {@link BmsAttributes#DFHDFCOL} and {@link BmsAttributes#DFHDFHI}, so
     * "not yet written" and "default colour, default highlight" are indistinguishable in the transmitted
     * map. That is the 3270 convention, and it is why one {@code MOVE LOW-VALUES} suffices as
     * initialisation.
     */
    private static final byte LOW_VALUES_BYTE = 0x00;

    /** The sign the mask emits for zero and for a positive value. It is never suppressed. */
    private static final char AMOUNT_SIGN_POSITIVE = '+';

    /** The sign the mask emits for a negative value. */
    private static final char AMOUNT_SIGN_NEGATIVE = '-';

    /** The mask's group separator, literally a comma and never a locale's choice of one. */
    private static final char AMOUNT_GROUP_SEPARATOR = ',';

    /** The mask's decimal point, literally a full stop and never a locale's choice of one. */
    private static final char AMOUNT_DECIMAL_POINT = '.';

    /** What a suppressed {@code Z} position and a suppressed comma both become. */
    private static final char AMOUNT_SUPPRESSION_CHARACTER = ' ';

    /** The digit a short magnitude is left-padded with before the mask is applied. */
    private static final char ZERO_DIGIT = '0';

    static {
        // Fail on first use rather than after the first byte comparison. The three comparisons below are
        // compile-time constant expressions, so javac decides each one: while the arithmetic holds the
        // condition is constantly false, the statement is elided and it costs nothing at run time. Break
        // a width and the condition becomes constantly true, javac emits the throw, and the class fails
        // to initialise. verifyFieldStrides() reads ScreenField at run time and is live in every build.
        if (FIELD_OVERHEAD != 7) {
            throw new IllegalStateException("Each CACTVWAO field carries 3 + 4 = 7 bytes of FILLER and "
                    + "attribute storage before its data - the same 7 as CACTVWAI's 2 + 1 + 4, which is "
                    + "what makes the REDEFINES line up - but FIELD_OVERHEAD computes to "
                    + FIELD_OVERHEAD);
        }
        if (PAYLOAD_LENGTH != 684) {
            throw new IllegalStateException("The 37 xxxO PICTURE widths of app/cpy-bms/COACTVW.CPY sum "
                    + "to 684, which is also the sum of the 37 LENGTH= operands of "
                    + "app/bms/COACTVW.bms, but the width constants sum to " + PAYLOAD_LENGTH);
        }
        if (GROUP_LENGTH != 955) {
            throw new IllegalStateException("The CACTVWAO group is 12 + 37 * 7 + 684 = 955 bytes, but "
                    + "the constants compute " + GROUP_LENGTH);
        }
        if (AMOUNT_MASK_WIDTH != 1 + AMOUNT_INTEGER_POSITIONS + 2 + 1 + AMOUNT_FRACTION_DIGITS) {
            throw new IllegalStateException("PIC " + AMOUNT_PICTURE + " occupies a sign, "
                    + AMOUNT_INTEGER_POSITIONS + " digit positions, 2 group separators, a decimal point "
                    + "and " + AMOUNT_FRACTION_DIGITS + " forced digits, which is "
                    + (1 + AMOUNT_INTEGER_POSITIONS + 2 + 1 + AMOUNT_FRACTION_DIGITS)
                    + " characters, not the declared " + AMOUNT_MASK_WIDTH);
        }
        verifyFieldStrides();
        verifyMaskedFieldWidths();
    }

    /**
     * Walks the {@value #FIELD_COUNT} {@link ScreenField} constants in declaration order and confirms
     * that each begins where its predecessor ended, that the first begins immediately after the
     * {@value #TIOAPFX_LENGTH}-byte prefix, and that the last ends exactly on {@value #GROUP_LENGTH}.
     *
     * <p>This is what makes the transcribed data offsets safe. Each offset is written out explicitly on
     * its constant so a reviewer can compare it with the copybook without doing arithmetic, and this walk
     * is what stops an explicit number from being explicitly wrong.
     *
     * @throws IllegalStateException if any field's storage does not abut its neighbours, if the count of
     *                               constants is not {@value #FIELD_COUNT}, or if the widths do not sum
     *                               to {@value #PAYLOAD_LENGTH}
     */
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
            if (field.fillerOffset() != cursor) {
                throw new IllegalStateException("Field " + field.label() + " declares its data at offset "
                        + field.dataOffset() + ", which places its FILLER at " + field.fillerOffset()
                        + "; the preceding storage ends at " + cursor + ", so the CACTVWAO group would "
                        + "have a gap or an overlap there");
            }
            cursor = field.endOffsetExclusive();
            widths += field.length();
        }
        if (widths != PAYLOAD_LENGTH) {
            throw new IllegalStateException("The " + FIELD_COUNT + " ScreenField widths sum to " + widths
                    + " but the width constants sum to " + PAYLOAD_LENGTH);
        }
        if (cursor != GROUP_LENGTH) {
            throw new IllegalStateException("Walking the " + FIELD_COUNT + " field strides from the end "
                    + "of the " + TIOAPFX_LENGTH + "-byte TIOAPFX prefix ends at " + cursor
                    + ", not at the declared group length of " + GROUP_LENGTH);
        }
    }

    /**
     * Confirms that exactly {@value #AMOUNT_MASK_FIELD_COUNT} fields are numeric-edited and that each of
     * them is {@value #AMOUNT_MASK_WIDTH} characters wide.
     *
     * <p>Two independent facts, checked together because a wrong answer to either would silently corrupt
     * the same five money items: the mapset declares {@code PICOUT} on five entries, and the copybook
     * declares each of those five at the mask's own width.
     *
     * @throws IllegalStateException if the count or any width disagrees with the sources
     */
    private static void verifyMaskedFieldWidths() {
        int masked = 0;
        for (ScreenField field : ScreenField.values()) {
            if (!field.isNumericEdited()) {
                continue;
            }
            masked++;
            if (field.length() != AMOUNT_MASK_WIDTH) {
                throw new IllegalStateException("Field " + field.label() + " is declared PIC "
                        + field.picture() + ", which occupies " + AMOUNT_MASK_WIDTH
                        + " characters, but its width constant says " + field.length());
            }
        }
        if (masked != AMOUNT_MASK_FIELD_COUNT) {
            throw new IllegalStateException("app/cpy-bms/COACTVW.CPY declares PIC " + AMOUNT_PICTURE
                    + " on exactly " + AMOUNT_MASK_FIELD_COUNT + " items and app/bms/COACTVW.bms "
                    + "declares PICOUT on exactly " + AMOUNT_MASK_FIELD_COUNT + " entries, but "
                    + masked + " ScreenField constants report themselves numeric-edited");
        }
    }


    // =================================================================================================
    // The 37 payload members, in the order app/cpy-bms/COACTVW.CPY lays their storage down.
    //
    // Naming is mechanical and identical to AccountViewRequest's: the Java member and its JSON name are
    // the DFHMDF label lower-cased, with no re-spelling, no expansion of an abbreviation and no
    // exception. TRNNAME becomes trnname, ACSADL1 becomes acsadl1. One rule applied 37 times means the
    // request and the response can be diffed member for member, which is exactly how a reviewer checks
    // that the two halves of the CAVW contract agree.
    //
    // Every member is a final String holding an image at its declared width - Builder.build() applies the
    // PIC X move rule once, on store, because a COBOL MOVE into an xxxO output item pads a short sending
    // value and truncates a long one at the moment of the move. So a getter here returns the bytes the
    // map would transmit, untrimmed, and toGroupImage() has nothing left to decide.
    //
    // None is numeric. The five money items hold the rendered 15-character mask, because that is what the
    // group transmits and what a parity differ compares; the BigDecimal that produced it enters through
    // Builder and is converted by editAmount(BigDecimal). This file declares no double and no float
    // anywhere (gates G22 and G24).
    //
    // @Size is the only constraint and every max comes from a PICTURE width. Deliberately absent:
    // @NotBlank, @Pattern, @Digits and any custom constraint - a field on this screen may legitimately
    // hold LOW-VALUES (the whole group starts there), all spaces, or the single character '*', and a
    // constraint rejecting any of those would make the program's own states unrepresentable.
    // =================================================================================================

    /**
     * {@code TRNNAME}: {@code TRNNAMEO PIC X(4)}, {@code app/cpy-bms/COACTVW.CPY:248}.
     *
     * <p>{@code MOVE LIT-THISTRANID TO TRNNAMEO} at {@code app/cbl/COACTVWC.cbl:438} - the four
     * characters {@value #THIS_TRANID}.
     */
    @JsonProperty("trnname")
    @Size(max = TRNNAME_LENGTH, message = "TRNNAME is TRNNAMEO PIC X(4) at "
            + "app/cpy-bms/COACTVW.CPY:248 and holds at most 4 characters")
    private final String trnname;

    /**
     * {@code TITLE01}: {@code TITLE01O PIC X(40)}, {@code app/cpy-bms/COACTVW.CPY:254}.
     *
     * <p>{@code MOVE CCDA-TITLE01 TO TITLE01O} at {@code app/cbl/COACTVWC.cbl:436}, from
     * {@link ScreenTitles#CCDA_TITLE01}. The widths agree exactly - {@code COTTL01Y} declares
     * {@code PIC X(40)} and so does the map.
     */
    @JsonProperty("title01")
    @Size(max = TITLE01_LENGTH, message = "TITLE01 is TITLE01O PIC X(40) at "
            + "app/cpy-bms/COACTVW.CPY:254 and holds at most 40 characters")
    private final String title01;

    /**
     * {@code CURDATE}: {@code CURDATEO PIC X(8)}, {@code app/cpy-bms/COACTVW.CPY:260}.
     *
     * <p>{@code MOVE WS-CURDATE-MM-DD-YY TO CURDATEO} at {@code app/cbl/COACTVWC.cbl:447}, which
     * {@link DateHeader#wsCurdateMmDdYy()} supplies as eight characters of {@code mm/dd/yy} - exactly
     * what {@code app/bms/COACTVW.bms:47} advertises with {@code INITIAL='mm/dd/yy'}. A width, not a date
     * type.
     */
    @JsonProperty("curdate")
    @Size(max = CURDATE_LENGTH, message = "CURDATE is CURDATEO PIC X(8) at "
            + "app/cpy-bms/COACTVW.CPY:260 and holds at most 8 characters")
    private final String curdate;

    /**
     * {@code PGMNAME}: {@code PGMNAMEO PIC X(8)}, {@code app/cpy-bms/COACTVW.CPY:266}.
     *
     * <p>{@code MOVE LIT-THISPGM TO PGMNAMEO} at {@code app/cbl/COACTVWC.cbl:439} - the eight characters
     * {@value #THIS_PROGRAM}.
     */
    @JsonProperty("pgmname")
    @Size(max = PGMNAME_LENGTH, message = "PGMNAME is PGMNAMEO PIC X(8) at "
            + "app/cpy-bms/COACTVW.CPY:266 and holds at most 8 characters")
    private final String pgmname;

    /**
     * {@code TITLE02}: {@code TITLE02O PIC X(40)}, {@code app/cpy-bms/COACTVW.CPY:272}.
     *
     * <p>{@code MOVE CCDA-TITLE02 TO TITLE02O} at {@code app/cbl/COACTVWC.cbl:437}, from
     * {@link ScreenTitles#CCDA_TITLE02}.
     */
    @JsonProperty("title02")
    @Size(max = TITLE02_LENGTH, message = "TITLE02 is TITLE02O PIC X(40) at "
            + "app/cpy-bms/COACTVW.CPY:272 and holds at most 40 characters")
    private final String title02;

    /**
     * {@code CURTIME}: {@code CURTIMEO PIC X(8)}, {@code app/cpy-bms/COACTVW.CPY:278}.
     *
     * <p>{@code MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO} at {@code app/cbl/COACTVWC.cbl:453}, which
     * {@link DateHeader#wsCurtimeHhMmSs()} supplies as {@code hh:mm:ss}.
     */
    @JsonProperty("curtime")
    @Size(max = CURTIME_LENGTH, message = "CURTIME is CURTIMEO PIC X(8) at "
            + "app/cpy-bms/COACTVW.CPY:278 and holds at most 8 characters")
    private final String curtime;

    /**
     * {@code ACCTSID}: {@code ACCTSIDO PIC X(11)}, {@code app/cpy-bms/COACTVW.CPY:284}.
     *
     * <p>The account number the screen is showing, and the field that carries three genuinely different
     * kinds of value:
     *
     * <ul>
     *   <li>{@code MOVE CC-ACCT-ID TO ACCTSIDO} at {@code app/cbl/COACTVWC.cbl:468} - the eleven digits
     *       of an account identifier;</li>
     *   <li>{@code MOVE LOW-VALUES TO ACCTSIDO} at {@code :466} when {@code FLG-ACCTFILTER-BLANK} -
     *       eleven {@code X'00'} bytes, which is neither spaces nor an absent value;</li>
     *   <li>{@code MOVE '*' TO ACCTSIDO} at {@code :563} when the filter is blank <em>and</em>
     *       {@code CDEMO-PGM-REENTER} - an asterisk and ten spaces.</li>
     * </ul>
     *
     * <p><strong>The {@code PICTURE} asymmetry.</strong> The output item is plain {@code PIC X(11)} while
     * the input twin is {@code PIC 99999999999} at {@code app/cpy-bms/COACTVW.CPY:60}, and the mapset
     * declares {@code PICIN='99999999999'} with no {@code PICOUT} at {@code app/bms/COACTVW.bms:90}. The
     * width is the same on both sides; only the class differs. Alphanumeric is the correct model here -
     * two of the three values above are not numeric at all - and it is also what
     * {@link AccountViewRequest#getAcctsid()} does, for the same reason.
     */
    @JsonProperty("acctsid")
    @Size(max = ACCTSID_LENGTH, message = "ACCTSID is ACCTSIDO PIC X(11) at "
            + "app/cpy-bms/COACTVW.CPY:284 and holds at most 11 characters")
    private final String acctsid;

    /**
     * {@code ACSTTUS}: {@code ACSTTUSO PIC X(1)}, {@code app/cpy-bms/COACTVW.CPY:290}.
     *
     * <p>{@code MOVE ACCT-ACTIVE-STATUS TO ACSTTUSO} at {@code app/cbl/COACTVWC.cbl:473}, from
     * {@code ACCT-ACTIVE-STATUS PIC X(01)} of {@code app/cpy/CVACT01Y.cpy}.
     */
    @JsonProperty("acsttus")
    @Size(max = ACSTTUS_LENGTH, message = "ACSTTUS is ACSTTUSO PIC X(1) at "
            + "app/cpy-bms/COACTVW.CPY:290 and holds at most 1 character")
    private final String acsttus;

    /**
     * {@code ADTOPEN}: {@code ADTOPENO PIC X(10)}, {@code app/cpy-bms/COACTVW.CPY:296}.
     *
     * <p>{@code MOVE ACCT-OPEN-DATE TO ADTOPENO} at {@code app/cbl/COACTVWC.cbl:487}, from
     * {@code ACCT-OPEN-DATE PIC X(10)}.
     */
    @JsonProperty("adtopen")
    @Size(max = ADTOPEN_LENGTH, message = "ADTOPEN is ADTOPENO PIC X(10) at "
            + "app/cpy-bms/COACTVW.CPY:296 and holds at most 10 characters")
    private final String adtopen;

    /**
     * {@code ACRDLIM}: {@code ACRDLIMO PIC +ZZZ,ZZZ,ZZZ.99}, {@code app/cpy-bms/COACTVW.CPY:302}.
     *
     * <p>{@code MOVE ACCT-CREDIT-LIMIT TO ACRDLIMO} at {@code app/cbl/COACTVWC.cbl:477}, from
     * {@code ACCT-CREDIT-LIMIT PIC S9(10)V99}. Holds the rendered {@value #AMOUNT_MASK_WIDTH}-character
     * image; {@link Builder#acrdlimAmount(BigDecimal)} produces it through
     * {@link #editAmount(BigDecimal)}.
     */
    @JsonProperty("acrdlim")
    @Size(max = ACRDLIM_LENGTH, message = "ACRDLIM is ACRDLIMO PIC +ZZZ,ZZZ,ZZZ.99 at "
            + "app/cpy-bms/COACTVW.CPY:302 and holds at most 15 characters")
    private final String acrdlim;

    /**
     * {@code AEXPDT}: {@code AEXPDTO PIC X(10)}, {@code app/cpy-bms/COACTVW.CPY:308}.
     *
     * <p>{@code MOVE ACCT-EXPIRAION-DATE TO AEXPDTO} at {@code app/cbl/COACTVWC.cbl:488}.
     *
     * <p><strong>The source field name is misspelled in the copybook</strong> -
     * {@code ACCT-EXPIRAION-DATE}, not {@code ACCT-EXPIRATION-DATE} - and
     * {@code app/cpy/CVACT01Y.cpy} is the contract, so the misspelling is reproduced verbatim wherever
     * that name is quoted (implicit requirement <b>I1</b>). Correcting it would break field-for-field
     * diffing.
     */
    @JsonProperty("aexpdt")
    @Size(max = AEXPDT_LENGTH, message = "AEXPDT is AEXPDTO PIC X(10) at "
            + "app/cpy-bms/COACTVW.CPY:308 and holds at most 10 characters")
    private final String aexpdt;

    /**
     * {@code ACSHLIM}: {@code ACSHLIMO PIC +ZZZ,ZZZ,ZZZ.99}, {@code app/cpy-bms/COACTVW.CPY:314}.
     *
     * <p>{@code MOVE ACCT-CASH-CREDIT-LIMIT TO ACSHLIMO} at {@code app/cbl/COACTVWC.cbl:479-480}, from
     * {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99}.
     */
    @JsonProperty("acshlim")
    @Size(max = ACSHLIM_LENGTH, message = "ACSHLIM is ACSHLIMO PIC +ZZZ,ZZZ,ZZZ.99 at "
            + "app/cpy-bms/COACTVW.CPY:314 and holds at most 15 characters")
    private final String acshlim;

    /**
     * {@code AREISDT}: {@code AREISDTO PIC X(10)}, {@code app/cpy-bms/COACTVW.CPY:320}.
     *
     * <p>{@code MOVE ACCT-REISSUE-DATE TO AREISDTO} at {@code app/cbl/COACTVWC.cbl:489}.
     */
    @JsonProperty("areisdt")
    @Size(max = AREISDT_LENGTH, message = "AREISDT is AREISDTO PIC X(10) at "
            + "app/cpy-bms/COACTVW.CPY:320 and holds at most 10 characters")
    private final String areisdt;

    /**
     * {@code ACURBAL}: {@code ACURBALO PIC +ZZZ,ZZZ,ZZZ.99}, {@code app/cpy-bms/COACTVW.CPY:326}.
     *
     * <p>{@code MOVE ACCT-CURR-BAL TO ACURBALO} at {@code app/cbl/COACTVWC.cbl:475}, from
     * {@code ACCT-CURR-BAL PIC S9(10)V99}. The first of the five money moves the program makes and the
     * only one whose source another program writes back to.
     */
    @JsonProperty("acurbal")
    @Size(max = ACURBAL_LENGTH, message = "ACURBAL is ACURBALO PIC +ZZZ,ZZZ,ZZZ.99 at "
            + "app/cpy-bms/COACTVW.CPY:326 and holds at most 15 characters")
    private final String acurbal;

    /**
     * {@code ACRCYCR}: {@code ACRCYCRO PIC +ZZZ,ZZZ,ZZZ.99}, {@code app/cpy-bms/COACTVW.CPY:332}.
     *
     * <p>{@code MOVE ACCT-CURR-CYC-CREDIT TO ACRCYCRO} at {@code app/cbl/COACTVWC.cbl:482-483}, from
     * {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99}.
     */
    @JsonProperty("acrcycr")
    @Size(max = ACRCYCR_LENGTH, message = "ACRCYCR is ACRCYCRO PIC +ZZZ,ZZZ,ZZZ.99 at "
            + "app/cpy-bms/COACTVW.CPY:332 and holds at most 15 characters")
    private final String acrcycr;

    /**
     * {@code AADDGRP}: {@code AADDGRPO PIC X(10)}, {@code app/cpy-bms/COACTVW.CPY:338}.
     *
     * <p>{@code MOVE ACCT-GROUP-ID TO AADDGRPO} at {@code app/cbl/COACTVWC.cbl:490}. The disclosure group
     * whose interest rate {@code CBACT04C} later reads from {@code app/cpy/CVTRA02Y.cpy}.
     */
    @JsonProperty("aaddgrp")
    @Size(max = AADDGRP_LENGTH, message = "AADDGRP is AADDGRPO PIC X(10) at "
            + "app/cpy-bms/COACTVW.CPY:338 and holds at most 10 characters")
    private final String aaddgrp;

    /**
     * {@code ACRCYDB}: {@code ACRCYDBO PIC +ZZZ,ZZZ,ZZZ.99}, {@code app/cpy-bms/COACTVW.CPY:344}.
     *
     * <p>{@code MOVE ACCT-CURR-CYC-DEBIT TO ACRCYDBO} at {@code app/cbl/COACTVWC.cbl:485}, from
     * {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99}.
     */
    @JsonProperty("acrcydb")
    @Size(max = ACRCYDB_LENGTH, message = "ACRCYDB is ACRCYDBO PIC +ZZZ,ZZZ,ZZZ.99 at "
            + "app/cpy-bms/COACTVW.CPY:344 and holds at most 15 characters")
    private final String acrcydb;

    /**
     * {@code ACSTNUM}: {@code ACSTNUMO PIC X(9)}, {@code app/cpy-bms/COACTVW.CPY:350}.
     *
     * <p>{@code MOVE CUST-ID TO ACSTNUMO} at {@code app/cbl/COACTVWC.cbl:494}, from
     * {@code CUST-ID PIC 9(09)} of {@code app/cpy/CVCUS01Y.cpy}.
     */
    @JsonProperty("acstnum")
    @Size(max = ACSTNUM_LENGTH, message = "ACSTNUM is ACSTNUMO PIC X(9) at "
            + "app/cpy-bms/COACTVW.CPY:350 and holds at most 9 characters")
    private final String acstnum;

    /**
     * {@code ACSTSSN}: {@code ACSTSSNO PIC X(12)}, {@code app/cpy-bms/COACTVW.CPY:356}.
     *
     * <p>The hyphenated social security number, composed at {@code app/cbl/COACTVWC.cbl:496-504}:
     *
     * <pre>{@code
     * STRING CUST-SSN(1:3) '-' CUST-SSN(4:2) '-' CUST-SSN(6:4)
     *     DELIMITED BY SIZE INTO ACSTSSNO OF CACTVWAO
     * END-STRING
     * }</pre>
     *
     * <p>That writes {@value #ACSTSSN_STRING_LENGTH} characters into {@value #ACSTSSN_LENGTH} bytes and
     * leaves the twelfth alone, because a COBOL {@code STRING} does not blank its receiver's tail. Use
     * {@link Builder#acstssnFromSsn(String)} to reproduce that exactly.
     *
     * <p>{@code MOVE CUST-SSN TO ACSTSSNO} sits commented out immediately above the {@code STRING}, at
     * {@code :495}. The author switched deliberately to the hyphenated form and the comment is left as
     * found (practice <b>B5</b>).
     *
     * <p>Carried in the clear, exactly as the screen shows it - see this type's documentation on practice
     * <b>B6</b>.
     */
    @JsonProperty("acstssn")
    @Size(max = ACSTSSN_LENGTH, message = "ACSTSSN is ACSTSSNO PIC X(12) at "
            + "app/cpy-bms/COACTVW.CPY:356 and holds at most 12 characters")
    private final String acstssn;

    /**
     * {@code ACSTDOB}: {@code ACSTDOBO PIC X(10)}, {@code app/cpy-bms/COACTVW.CPY:362}.
     *
     * <p>{@code MOVE CUST-DOB-YYYY-MM-DD TO ACSTDOBO} at {@code app/cbl/COACTVWC.cbl:508}. The hyphens
     * in {@code CUST-DOB-YYYY-MM-DD} are part of the <em>field name</em>, not of a format string; the
     * field is {@code PIC X(10)} and this receiver is the same width.
     *
     * <p>Carried in the clear, per practice <b>B6</b>.
     */
    @JsonProperty("acstdob")
    @Size(max = ACSTDOB_LENGTH, message = "ACSTDOB is ACSTDOBO PIC X(10) at "
            + "app/cpy-bms/COACTVW.CPY:362 and holds at most 10 characters")
    private final String acstdob;

    /**
     * {@code ACSTFCO}: {@code ACSTFCOO PIC X(3)}, {@code app/cpy-bms/COACTVW.CPY:368}.
     *
     * <p>{@code MOVE CUST-FICO-CREDIT-SCORE TO ACSTFCOO} at {@code app/cbl/COACTVWC.cbl:506}. The source
     * is {@code PIC 9(03)} and the receiver alphanumeric, so a three-digit score arrives as three
     * characters with no reformatting.
     */
    @JsonProperty("acstfco")
    @Size(max = ACSTFCO_LENGTH, message = "ACSTFCO is ACSTFCOO PIC X(3) at "
            + "app/cpy-bms/COACTVW.CPY:368 and holds at most 3 characters")
    private final String acstfco;

    /**
     * {@code ACSFNAM}: {@code ACSFNAMO PIC X(25)}, {@code app/cpy-bms/COACTVW.CPY:374}.
     *
     * <p>{@code MOVE CUST-FIRST-NAME TO ACSFNAMO} at {@code app/cbl/COACTVWC.cbl:509}; widths agree.
     */
    @JsonProperty("acsfnam")
    @Size(max = ACSFNAM_LENGTH, message = "ACSFNAM is ACSFNAMO PIC X(25) at "
            + "app/cpy-bms/COACTVW.CPY:374 and holds at most 25 characters")
    private final String acsfnam;

    /**
     * {@code ACSMNAM}: {@code ACSMNAMO PIC X(25)}, {@code app/cpy-bms/COACTVW.CPY:380}.
     *
     * <p>{@code MOVE CUST-MIDDLE-NAME TO ACSMNAMO} at {@code app/cbl/COACTVWC.cbl:510}.
     */
    @JsonProperty("acsmnam")
    @Size(max = ACSMNAM_LENGTH, message = "ACSMNAM is ACSMNAMO PIC X(25) at "
            + "app/cpy-bms/COACTVW.CPY:380 and holds at most 25 characters")
    private final String acsmnam;

    /**
     * {@code ACSLNAM}: {@code ACSLNAMO PIC X(25)}, {@code app/cpy-bms/COACTVW.CPY:386}.
     *
     * <p>{@code MOVE CUST-LAST-NAME TO ACSLNAMO} at {@code app/cbl/COACTVWC.cbl:511}.
     */
    @JsonProperty("acslnam")
    @Size(max = ACSLNAM_LENGTH, message = "ACSLNAM is ACSLNAMO PIC X(25) at "
            + "app/cpy-bms/COACTVW.CPY:386 and holds at most 25 characters")
    private final String acslnam;

    /**
     * {@code ACSADL1}: {@code ACSADL1O PIC X(50)}, {@code app/cpy-bms/COACTVW.CPY:392}.
     *
     * <p>{@code MOVE CUST-ADDR-LINE-1 TO ACSADL1O} at {@code app/cbl/COACTVWC.cbl:512}.
     */
    @JsonProperty("acsadl1")
    @Size(max = ACSADL1_LENGTH, message = "ACSADL1 is ACSADL1O PIC X(50) at "
            + "app/cpy-bms/COACTVW.CPY:392 and holds at most 50 characters")
    private final String acsadl1;

    /**
     * {@code ACSSTTE}: {@code ACSSTTEO PIC X(2)}, {@code app/cpy-bms/COACTVW.CPY:398}.
     *
     * <p>{@code MOVE CUST-ADDR-STATE-CD TO ACSSTTEO} at {@code app/cbl/COACTVWC.cbl:516}.
     */
    @JsonProperty("acsstte")
    @Size(max = ACSSTTE_LENGTH, message = "ACSSTTE is ACSSTTEO PIC X(2) at "
            + "app/cpy-bms/COACTVW.CPY:398 and holds at most 2 characters")
    private final String acsstte;

    /**
     * {@code ACSADL2}: {@code ACSADL2O PIC X(50)}, {@code app/cpy-bms/COACTVW.CPY:404}.
     *
     * <p>{@code MOVE CUST-ADDR-LINE-2 TO ACSADL2O} at {@code app/cbl/COACTVWC.cbl:513}.
     */
    @JsonProperty("acsadl2")
    @Size(max = ACSADL2_LENGTH, message = "ACSADL2 is ACSADL2O PIC X(50) at "
            + "app/cpy-bms/COACTVW.CPY:404 and holds at most 50 characters")
    private final String acsadl2;

    /**
     * {@code ACSZIPC}: {@code ACSZIPCO PIC X(5)}, {@code app/cpy-bms/COACTVW.CPY:410}.
     *
     * <p>{@code MOVE CUST-ADDR-ZIP TO ACSZIPCO} at {@code app/cbl/COACTVWC.cbl:514}, narrowing
     * {@code PIC X(10)} to five characters. The alphanumeric move discards on the <em>right</em>, so a
     * ten-character postal code keeps its first five - which is what the screen shows.
     */
    @JsonProperty("acszipc")
    @Size(max = ACSZIPC_LENGTH, message = "ACSZIPC is ACSZIPCO PIC X(5) at "
            + "app/cpy-bms/COACTVW.CPY:410 and holds at most 5 characters")
    private final String acszipc;

    /**
     * {@code ACSCITY}: {@code ACSCITYO PIC X(50)}, {@code app/cpy-bms/COACTVW.CPY:416}.
     *
     * <p><strong>Fed from address line 3, not from a city field.</strong>
     * {@code MOVE CUST-ADDR-LINE-3 TO ACSCITYO} at {@code app/cbl/COACTVWC.cbl:515}, and
     * {@code app/cpy/CVCUS01Y.cpy} has no city item at all - it declares
     * {@code CUST-ADDR-LINE-1}, {@code -2} and {@code -3}. Surprising, and correct; the mapping is not
     * "tidied" to a field that does not exist.
     */
    @JsonProperty("acscity")
    @Size(max = ACSCITY_LENGTH, message = "ACSCITY is ACSCITYO PIC X(50) at "
            + "app/cpy-bms/COACTVW.CPY:416 and holds at most 50 characters")
    private final String acscity;

    /**
     * {@code ACSCTRY}: {@code ACSCTRYO PIC X(3)}, {@code app/cpy-bms/COACTVW.CPY:422}.
     *
     * <p>{@code MOVE CUST-ADDR-COUNTRY-CD TO ACSCTRYO} at {@code app/cbl/COACTVWC.cbl:517}; widths
     * agree.
     */
    @JsonProperty("acsctry")
    @Size(max = ACSCTRY_LENGTH, message = "ACSCTRY is ACSCTRYO PIC X(3) at "
            + "app/cpy-bms/COACTVW.CPY:422 and holds at most 3 characters")
    private final String acsctry;

    /**
     * {@code ACSPHN1}: {@code ACSPHN1O PIC X(13)}, {@code app/cpy-bms/COACTVW.CPY:428}.
     *
     * <p>{@code MOVE CUST-PHONE-NUM-1 TO ACSPHN1O} at {@code app/cbl/COACTVWC.cbl:518}, narrowing
     * {@code PIC X(15)} to thirteen characters on the right.
     */
    @JsonProperty("acsphn1")
    @Size(max = ACSPHN1_LENGTH, message = "ACSPHN1 is ACSPHN1O PIC X(13) at "
            + "app/cpy-bms/COACTVW.CPY:428 and holds at most 13 characters")
    private final String acsphn1;

    /**
     * {@code ACSGOVT}: {@code ACSGOVTO PIC X(20)}, {@code app/cpy-bms/COACTVW.CPY:434}.
     *
     * <p>{@code MOVE CUST-GOVT-ISSUED-ID TO ACSGOVTO} at {@code app/cbl/COACTVWC.cbl:520}; widths agree.
     *
     * <p>Carried in the clear, per practice <b>B6</b>.
     */
    @JsonProperty("acsgovt")
    @Size(max = ACSGOVT_LENGTH, message = "ACSGOVT is ACSGOVTO PIC X(20) at "
            + "app/cpy-bms/COACTVW.CPY:434 and holds at most 20 characters")
    private final String acsgovt;

    /**
     * {@code ACSPHN2}: {@code ACSPHN2O PIC X(13)}, {@code app/cpy-bms/COACTVW.CPY:440}.
     *
     * <p>{@code MOVE CUST-PHONE-NUM-2 TO ACSPHN2O} at {@code app/cbl/COACTVWC.cbl:519}, narrowing
     * {@code PIC X(15)} exactly as {@code ACSPHN1O} does.
     */
    @JsonProperty("acsphn2")
    @Size(max = ACSPHN2_LENGTH, message = "ACSPHN2 is ACSPHN2O PIC X(13) at "
            + "app/cpy-bms/COACTVW.CPY:440 and holds at most 13 characters")
    private final String acsphn2;

    /**
     * {@code ACSEFTC}: {@code ACSEFTCO PIC X(10)}, {@code app/cpy-bms/COACTVW.CPY:446}.
     *
     * <p>{@code MOVE CUST-EFT-ACCOUNT-ID TO ACSEFTCO} at {@code app/cbl/COACTVWC.cbl:521}; widths agree.
     */
    @JsonProperty("acseftc")
    @Size(max = ACSEFTC_LENGTH, message = "ACSEFTC is ACSEFTCO PIC X(10) at "
            + "app/cpy-bms/COACTVW.CPY:446 and holds at most 10 characters")
    private final String acseftc;

    /**
     * {@code ACSPFLG}: {@code ACSPFLGO PIC X(1)}, {@code app/cpy-bms/COACTVW.CPY:452}.
     *
     * <p>{@code MOVE CUST-PRI-CARD-HOLDER-IND TO ACSPFLGO} at {@code app/cbl/COACTVWC.cbl:522}.
     */
    @JsonProperty("acspflg")
    @Size(max = ACSPFLG_LENGTH, message = "ACSPFLG is ACSPFLGO PIC X(1) at "
            + "app/cpy-bms/COACTVW.CPY:452 and holds at most 1 character")
    private final String acspflg;

    /**
     * {@code INFOMSG}: {@code INFOMSGO PIC X(45)}, {@code app/cpy-bms/COACTVW.CPY:458}.
     *
     * <p>{@code MOVE WS-INFO-MSG TO INFOMSGO} at {@code app/cbl/COACTVWC.cbl:534}, widening
     * {@code PIC X(40)} by {@value #INFOMSG_INFO_MESSAGE_PADDING} spaces on the right. The two texts
     * {@code WS-INFO-MSG} can hold are its own {@code 88}-levels:
     * {@code 'Enter or update id of account to display'} and
     * {@code 'Displaying details of given Account'}.
     *
     * <p>Its visibility is carried in the attribute quad rather than in this value: at
     * {@code app/cbl/COACTVWC.cbl:568} {@code WS-NO-INFO-MESSAGE} puts
     * {@link BmsAttributes#DFHBMDAR} - the non-display attribute - into {@code INFOMSGC}, and otherwise
     * {@code :570} puts {@link BmsAttributes#DFHNEUTR} there.
     */
    @JsonProperty("infomsg")
    @Size(max = INFOMSG_LENGTH, message = "INFOMSG is INFOMSGO PIC X(45) at "
            + "app/cpy-bms/COACTVW.CPY:458 and holds at most 45 characters")
    private final String infomsg;

    /**
     * {@code ERRMSG}: {@code ERRMSGO PIC X(78)}, {@code app/cpy-bms/COACTVW.CPY:464}.
     *
     * <p>{@code MOVE WS-RETURN-MSG TO ERRMSGO} at {@code app/cbl/COACTVWC.cbl:532}, widening
     * {@code PIC X(75)} by {@value #ERRMSG_RETURN_MESSAGE_PADDING} spaces on the right.
     *
     * <p>The same text also reaches the client through
     * {@link CardScreenState#getCcardErrorMsg()}, because {@code COMMON-RETURN} at
     * {@code app/cbl/COACTVWC.cbl:388} and {@code :395} performs
     * {@code MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG} as well. Both routes are reproduced; they are
     * deliberately <strong>not</strong> deduplicated, because the program populates both and the widths
     * differ.
     */
    @JsonProperty("errmsg")
    @Size(max = ERRMSG_LENGTH, message = "ERRMSG is ERRMSGO PIC X(78) at "
            + "app/cpy-bms/COACTVW.CPY:464 and holds at most 78 characters")
    private final String errmsg;

    // =================================================================================================
    // The next-screen triple. Rule R6, gates G37 and G40: EXEC CICS XCTL becomes response data the client
    // acts on, so navigation is resolved client-side and the server keeps no conversation.
    // =================================================================================================

    /**
     * The program the client should call next - the {@code XCTL} target of
     * {@code app/cbl/COACTVWC.cbl:349}, {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)}.
     *
     * <p>{@code COACTVWC} takes it from {@code CDEMO-TO-PROGRAM}, which the PF3 path at {@code :344-348}
     * fills from {@code CDEMO-FROM-PROGRAM} when the caller left none, so the value is echoed from the
     * incoming {@link NavigationContext} rather than decided here.
     */
    @JsonProperty("nextProgram")
    @Size(max = NEXT_PROGRAM_LENGTH, message = "nextProgram is CCARD-NEXT-PROG PIC X(8) and holds at "
            + "most 8 characters")
    private final String nextProgram;

    /**
     * The mapset the next screen belongs to - {@code CCARD-NEXT-MAPSET}, written at
     * {@code app/cbl/COACTVWC.cbl:906}.
     *
     * <p>{@value #NEXT_MAPSET_LENGTH} characters, not 8: see {@link #THIS_MAPSET}.
     */
    @JsonProperty("nextMapset")
    @Size(max = NEXT_MAPSET_LENGTH, message = "nextMapset is CCARD-NEXT-MAPSET PIC X(7) and holds at "
            + "most 7 characters")
    private final String nextMapset;

    /**
     * The map the next screen sends - {@code CCARD-NEXT-MAP}, written at
     * {@code app/cbl/COACTVWC.cbl:907} and then used as the {@code SEND MAP} operand at {@code :911}.
     */
    @JsonProperty("nextMap")
    @Size(max = NEXT_MAP_LENGTH, message = "nextMap is CCARD-NEXT-MAP PIC X(7) and holds at most 7 "
            + "characters")
    private final String nextMap;

    // =================================================================================================
    // The conversation carriers. Imported, never re-declared: CVCRD01Y is owned by card/dto as
    // CardScreenState and CARDDEMO-COMMAREA by common as NavigationContext, one Java type per copybook.
    // =================================================================================================

    /**
     * The {@code CVCRD01Y} work area {@code COACTVWC} copies at {@code app/cbl/COACTVWC.cbl:913} -
     * {@code CCARD-AID}, the next-screen triple, the two message lines and the three search identifiers.
     */
    @JsonProperty("cardScreenState")
    private final CardScreenState cardScreenState;

    /**
     * The {@value NavigationContext#COMMAREA_LENGTH}-byte {@code CARDDEMO-COMMAREA} of
     * {@code app/cpy/COCOM01Y.cpy}, which {@code app/cbl/COACTVWC.cbl:349} passes on the {@code XCTL} and
     * {@code :917} passes on the {@code RETURN}.
     *
     * <p>A record, so it is shared safely and needs no copy.
     */
    @JsonProperty("navigationContext")
    private final NavigationContext navigationContext;

    /**
     * The {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV} items, one holder per field.
     *
     * <p>Excluded from JSON: the symbolic map's attribute items are presentation metadata, never payload
     * members. Mutable in place, which is the one exception to this type's immutability and is justified
     * in the class documentation.
     */
    @JsonIgnore
    private final Map<ScreenField, FieldAttributes> attributes;


    // =================================================================================================
    // Construction. The only route in is Builder, so there is exactly one place where a value can be
    // stored and exactly one place where the PIC X move rule is applied on store.
    // =================================================================================================

    /**
     * Builds the payload from a builder whose values are already at their declared widths.
     *
     * @param builder the source of every value; {@link Builder#build()} has normalised each one
     */
    private AccountViewResponse(Builder builder) {
        this.trnname = builder.trnname;
        this.title01 = builder.title01;
        this.curdate = builder.curdate;
        this.pgmname = builder.pgmname;
        this.title02 = builder.title02;
        this.curtime = builder.curtime;
        this.acctsid = builder.acctsid;
        this.acsttus = builder.acsttus;
        this.adtopen = builder.adtopen;
        this.acrdlim = builder.acrdlim;
        this.aexpdt = builder.aexpdt;
        this.acshlim = builder.acshlim;
        this.areisdt = builder.areisdt;
        this.acurbal = builder.acurbal;
        this.acrcycr = builder.acrcycr;
        this.aaddgrp = builder.aaddgrp;
        this.acrcydb = builder.acrcydb;
        this.acstnum = builder.acstnum;
        this.acstssn = builder.acstssn;
        this.acstdob = builder.acstdob;
        this.acstfco = builder.acstfco;
        this.acsfnam = builder.acsfnam;
        this.acsmnam = builder.acsmnam;
        this.acslnam = builder.acslnam;
        this.acsadl1 = builder.acsadl1;
        this.acsstte = builder.acsstte;
        this.acsadl2 = builder.acsadl2;
        this.acszipc = builder.acszipc;
        this.acscity = builder.acscity;
        this.acsctry = builder.acsctry;
        this.acsphn1 = builder.acsphn1;
        this.acsgovt = builder.acsgovt;
        this.acsphn2 = builder.acsphn2;
        this.acseftc = builder.acseftc;
        this.acspflg = builder.acspflg;
        this.infomsg = builder.infomsg;
        this.errmsg = builder.errmsg;
        this.nextProgram = builder.nextProgram;
        this.nextMapset = builder.nextMapset;
        this.nextMap = builder.nextMap;
        // Copied because CardScreenState is mutable: a payload that shared a caller's work area could be
        // changed after it was built, which is exactly what immutability here is meant to prevent.
        this.cardScreenState = new CardScreenState(builder.cardScreenState);
        this.navigationContext = builder.navigationContext;
        this.attributes = new EnumMap<>(ScreenField.class);
        for (ScreenField field : ScreenField.values()) {
            this.attributes.put(field, new FieldAttributes(builder.attributes.get(field)));
        }
    }

    /**
     * A new builder in the state {@code MOVE LOW-VALUES TO CACTVWAO} leaves the group in.
     *
     * @return a builder whose {@value #FIELD_COUNT} fields are {@code LOW-VALUES} at their declared
     *         widths; never {@code null}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The payload exactly as {@code 1100-SCREEN-INIT} leaves it: the whole group at {@code LOW-VALUES}.
     *
     * <p>{@code app/cbl/COACTVWC.cbl:432} is a single {@code MOVE LOW-VALUES TO CACTVWAO}, so every one of
     * the {@value #FIELD_COUNT} fields and all {@value #ATTRIBUTE_ITEMS_PER_FIELD} attribute items per
     * field start at {@code X'00'} - <strong>not</strong> at spaces, and not at any absent value. This is
     * the starting point every parity case for {@code CAVW} builds from, so that a field the program never
     * writes is compared as {@code LOW-VALUES} rather than as something plausible.
     *
     * @return a payload holding {@code LOW-VALUES} throughout, with a freshly initialised work area and
     *         an empty commarea; never {@code null}
     */
    public static AccountViewResponse initialGroup() {
        return builder().build();
    }

    /**
     * A builder pre-loaded with everything this payload holds, for deriving a changed copy.
     *
     * <p>The attribute quads are copied too, so a derived payload keeps the colours the program painted.
     *
     * @return a builder that would rebuild an equal payload; never {@code null}
     */
    public Builder toBuilder() {
        Builder builder = new Builder();
        for (ScreenField field : ScreenField.values()) {
            builder.value(field, value(field));
            builder.attributes.put(field, new FieldAttributes(attributes.get(field)));
        }
        return builder.nextProgram(nextProgram)
                .nextMapset(nextMapset)
                .nextMap(nextMap)
                .cardScreenState(cardScreenState)
                .navigationContext(navigationContext);
    }

    // =================================================================================================
    // PIC +ZZZ,ZZZ,ZZZ.99. The whole of the numeric-edited contract, hand written (practice B11).
    // =================================================================================================

    /**
     * Renders an amount exactly as {@code MOVE <source> TO <xxxO>} renders it into a
     * {@code PIC +ZZZ,ZZZ,ZZZ.99} receiver: {@value #AMOUNT_MASK_WIDTH} characters, always.
     *
     * <h2>The mask, position by position</h2>
     *
     * <pre>
     *   index  0  1  2  3  4  5  6  7  8  9 10 11 12 13 14
     *   mask   +  Z  Z  Z  ,  Z  Z  Z  ,  Z  Z  Z  .  9  9
     * </pre>
     *
     * <h2>Every rule this method applies</h2>
     *
     * <ol>
     *   <li><b>The sign is fixed, not floating.</b> Position 0 always emits a character:
     *       {@code '+'} for zero and for a positive value, {@code '-'} for a negative one. It is never
     *       suppressed and it never moves.</li>
     *   <li><b>{@code Z} suppresses to a space.</b> A leading zero in a {@code Z} position becomes a
     *       space.</li>
     *   <li><b>A comma whose left-hand group was entirely suppressed is suppressed too</b>, to a
     *       space.</li>
     *   <li><b>Suppression stops at the first significant digit or at the decimal point, whichever comes
     *       first.</b> This mask has <em>no</em> {@code 9} in its integer part, so for a zero integer part
     *       suppression runs all the way to the point and every one of the
     *       {@value #AMOUNT_INTEGER_POSITIONS} integer positions blanks. A mask written {@code ZZZ9} or
     *       {@code ZZ,ZZ9} would keep a {@code 0} in its units position; this one does not.</li>
     *   <li><b>{@code .99} forces both decimal digits.</b> They are {@code 9}s, not {@code Z}s, so they
     *       are never suppressed: {@code 0.00} renders its fraction as {@code 00}. This also means the
     *       "all numeric positions are suppression symbols, so a zero value blanks the entire item" rule
     *       does <em>not</em> apply here, unlike the {@code -ZZZ,ZZZ,ZZZ.ZZ} report masks of
     *       {@code app/cpy/CVTRA07Y.cpy}, where it does.</li>
     *   <li><b>Ten integer digits into nine positions truncates on the left.</b> Every source is
     *       {@code PIC S9(10)V99} - {@value #SOURCE_INTEGER_DIGITS} integer digits - and the mask provides
     *       {@value #AMOUNT_INTEGER_POSITIONS}, so a value with a tenth integer digit loses that
     *       high-order digit. That is what a COBOL numeric {@code MOVE} does, and it is reproduced rather
     *       than guarded against: nothing is thrown, nothing is widened, and nothing overflows into the
     *       sign position.</li>
     *   <li><b>Truncation happens before editing, and that is observable.</b> Zero suppression looks only
     *       at the digits that reach the {@code Z} positions, so once the tenth integer digit has been
     *       discarded a value such as {@code 1000000000.00} presents nine leading zeros and blanks
     *       entirely: it renders identically to {@code 0.00}. The sign is unaffected, being a fixed
     *       insertion position rather than a digit position, so {@code -1000000000.00} still renders with
     *       a leading {@code '-'}.</li>
     * </ol>
     *
     * <p>So {@code 0.00} renders as {@code "+           .00"}, {@code 123.45} as
     * {@code "+        123.45"}, {@code 1234567.89} as {@code "+  1,234,567.89"},
     * {@code -0.01} as {@code "-           .01"}, and {@code 1234567890.12} as
     * {@code "+234,567,890.12"} with the leading {@code 1} discarded.
     *
     * <h2>What this method deliberately does not use</h2>
     *
     * <p>No {@code java.text.DecimalFormat}, no {@code NumberFormat}, no {@code String.format("%,.2f")}
     * and no third-party formatter (practice <b>B11</b>). The separators are literally {@code ','} and
     * {@code '.'} whatever the default {@code Locale} is, and every suppression decision is a line a
     * reviewer can read against the copybook. No {@code double} and no {@code float} appears anywhere in
     * the computation (gate <b>G22</b>).
     *
     * <p>The amount is stored at scale {@value CobolDecimal#MONETARY_SCALE} through
     * {@link CobolDecimal#storeMonetary(BigDecimal)}, which truncates with
     * {@link java.math.RoundingMode#DOWN} because the keyword {@code ROUNDED} appears zero times in all 28
     * COBOL programs (rule <b>R2</b>, gate <b>G24</b>). A third decimal digit is therefore dropped, not
     * rounded: {@code 1.239} and {@code 1.231} both render with a fraction of {@code 23}, never
     * {@code 24}.
     *
     * @param amount the sending value, at any scale; scaled to {@value CobolDecimal#MONETARY_SCALE} here
     * @return exactly {@value #AMOUNT_MASK_WIDTH} characters, never {@code null}
     * @throws NullPointerException if {@code amount} is {@code null}; COBOL has no absent numeric, and a
     *                              field the program never wrote holds {@code LOW-VALUES}, which is set
     *                              through {@link Builder#acurbal(String)} rather than through here
     */
    public static String editAmount(BigDecimal amount) {
        Objects.requireNonNull(amount, "An amount is required to render PIC " + AMOUNT_PICTURE
                + "; a money field the program never wrote holds LOW-VALUES, which is stored as an image "
                + "rather than rendered from a number");

        BigDecimal stored = CobolDecimal.storeMonetary(amount);
        boolean negative = stored.signum() < 0;
        String digits = alignToMask(stored.abs().unscaledValue().toString());
        String integerDigits = digits.substring(0, AMOUNT_INTEGER_POSITIONS);
        String fractionDigits = digits.substring(AMOUNT_INTEGER_POSITIONS);

        int firstSignificant = AMOUNT_INTEGER_POSITIONS;
        for (int position = 0; position < AMOUNT_INTEGER_POSITIONS; position++) {
            if (integerDigits.charAt(position) != ZERO_DIGIT) {
                firstSignificant = position;
                break;
            }
        }

        StringBuilder edited = new StringBuilder(AMOUNT_MASK_WIDTH);
        edited.append(negative ? AMOUNT_SIGN_NEGATIVE : AMOUNT_SIGN_POSITIVE);
        for (int position = 0; position < AMOUNT_INTEGER_POSITIONS; position++) {
            if (position > 0 && position % AMOUNT_GROUP_SIZE == 0) {
                // The comma sits immediately left of this digit, so it is suppressed exactly when the
                // first significant digit is this position or later.
                edited.append(firstSignificant >= position
                        ? AMOUNT_SUPPRESSION_CHARACTER
                        : AMOUNT_GROUP_SEPARATOR);
            }
            edited.append(position < firstSignificant
                    ? AMOUNT_SUPPRESSION_CHARACTER
                    : integerDigits.charAt(position));
        }
        return edited.append(AMOUNT_DECIMAL_POINT).append(fractionDigits).toString();
    }

    /**
     * Left-pads or left-truncates a magnitude's digits to the {@value #AMOUNT_DIGIT_POSITIONS} the mask
     * can carry.
     *
     * <p>Both directions are the COBOL numeric rule and both are reachable from real data: a small amount
     * needs padding, and a {@code PIC S9(10)V99} value of ten integer digits needs truncation. The
     * direction is the same one {@link FixedWidthCodec#movePic9(String, int)} documents for a numeric
     * receiver - fill and discard on the <em>left</em> - and it is stated here rather than delegated
     * because what is being aligned is the digit string behind an edit mask, not a {@code PIC 9(n)} item.
     *
     * @param magnitude the unscaled digits of a non-negative amount at scale
     *                  {@value CobolDecimal#MONETARY_SCALE}; at least one character, all digits
     * @return exactly {@value #AMOUNT_DIGIT_POSITIONS} digit characters
     */
    private static String alignToMask(String magnitude) {
        int carried = magnitude.length();
        if (carried < AMOUNT_DIGIT_POSITIONS) {
            return String.valueOf(ZERO_DIGIT).repeat(AMOUNT_DIGIT_POSITIONS - carried) + magnitude;
        }
        if (carried > AMOUNT_DIGIT_POSITIONS) {
            return magnitude.substring(carried - AMOUNT_DIGIT_POSITIONS);
        }
        return magnitude;
    }


    // =================================================================================================
    // Accessors. Thirty-seven, hand written - no Lombok and no MapStruct, so what a reviewer reads is what
    // runs and the byte-exact field mapping parity depends on stays visible.
    //
    // Every one returns the stored image UNTRIMMED. A PIC X field's trailing spaces are part of its value
    // and the parity differ compares them, so trimming here would discard bytes the gate exists to check.
    // A value may legitimately be LOW-VALUES, all spaces, or the single character '*'.
    // =================================================================================================

    /**
     * {@code TRNNAMEO} - the transaction identifier.
     *
     * @return {@value #TRNNAME_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getTrnname() {
        return trnname;
    }

    /**
     * {@code TITLE01O} - the first title line.
     *
     * @return {@value #TITLE01_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getTitle01() {
        return title01;
    }

    /**
     * {@code CURDATEO} - {@code mm/dd/yy}.
     *
     * @return {@value #CURDATE_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getCurdate() {
        return curdate;
    }

    /**
     * {@code PGMNAMEO} - the program name.
     *
     * @return {@value #PGMNAME_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getPgmname() {
        return pgmname;
    }

    /**
     * {@code TITLE02O} - the second title line.
     *
     * @return {@value #TITLE02_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getTitle02() {
        return title02;
    }

    /**
     * {@code CURTIMEO} - {@code hh:mm:ss}.
     *
     * @return {@value #CURTIME_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getCurtime() {
        return curtime;
    }

    /**
     * {@code ACCTSIDO} - the account number, or {@code LOW-VALUES}, or {@code '*'} and ten spaces.
     *
     * @return {@value #ACCTSID_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcctsid() {
        return acctsid;
    }

    /**
     * {@code ACSTTUSO} - {@code ACCT-ACTIVE-STATUS}.
     *
     * @return {@value #ACSTTUS_LENGTH} character, untrimmed; never {@code null}
     */
    public String getAcsttus() {
        return acsttus;
    }

    /**
     * {@code ADTOPENO} - {@code ACCT-OPEN-DATE}.
     *
     * @return {@value #ADTOPEN_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAdtopen() {
        return adtopen;
    }

    /**
     * {@code ACRDLIMO} - {@code ACCT-CREDIT-LIMIT} rendered through {@link #editAmount(BigDecimal)}.
     *
     * @return {@value #ACRDLIM_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcrdlim() {
        return acrdlim;
    }

    /**
     * {@code AEXPDTO} - {@code ACCT-EXPIRAION-DATE}, spelled as the copybook spells it.
     *
     * @return {@value #AEXPDT_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAexpdt() {
        return aexpdt;
    }

    /**
     * {@code ACSHLIMO} - {@code ACCT-CASH-CREDIT-LIMIT} rendered through {@link #editAmount(BigDecimal)}.
     *
     * @return {@value #ACSHLIM_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcshlim() {
        return acshlim;
    }

    /**
     * {@code AREISDTO} - {@code ACCT-REISSUE-DATE}.
     *
     * @return {@value #AREISDT_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAreisdt() {
        return areisdt;
    }

    /**
     * {@code ACURBALO} - {@code ACCT-CURR-BAL} rendered through {@link #editAmount(BigDecimal)}.
     *
     * @return {@value #ACURBAL_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcurbal() {
        return acurbal;
    }

    /**
     * {@code ACRCYCRO} - {@code ACCT-CURR-CYC-CREDIT} rendered through {@link #editAmount(BigDecimal)}.
     *
     * @return {@value #ACRCYCR_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcrcycr() {
        return acrcycr;
    }

    /**
     * {@code AADDGRPO} - {@code ACCT-GROUP-ID}.
     *
     * @return {@value #AADDGRP_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAaddgrp() {
        return aaddgrp;
    }

    /**
     * {@code ACRCYDBO} - {@code ACCT-CURR-CYC-DEBIT} rendered through {@link #editAmount(BigDecimal)}.
     *
     * @return {@value #ACRCYDB_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcrcydb() {
        return acrcydb;
    }

    /**
     * {@code ACSTNUMO} - {@code CUST-ID}.
     *
     * @return {@value #ACSTNUM_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcstnum() {
        return acstnum;
    }

    /**
     * {@code ACSTSSNO} - the hyphenated social security number, unmasked per practice <b>B6</b>.
     *
     * @return {@value #ACSTSSN_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcstssn() {
        return acstssn;
    }

    /**
     * {@code ACSTDOBO} - {@code CUST-DOB-YYYY-MM-DD}, unmasked per practice <b>B6</b>.
     *
     * @return {@value #ACSTDOB_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcstdob() {
        return acstdob;
    }

    /**
     * {@code ACSTFCOO} - {@code CUST-FICO-CREDIT-SCORE}.
     *
     * @return {@value #ACSTFCO_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcstfco() {
        return acstfco;
    }

    /**
     * {@code ACSFNAMO} - {@code CUST-FIRST-NAME}.
     *
     * @return {@value #ACSFNAM_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcsfnam() {
        return acsfnam;
    }

    /**
     * {@code ACSMNAMO} - {@code CUST-MIDDLE-NAME}.
     *
     * @return {@value #ACSMNAM_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcsmnam() {
        return acsmnam;
    }

    /**
     * {@code ACSLNAMO} - {@code CUST-LAST-NAME}.
     *
     * @return {@value #ACSLNAM_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcslnam() {
        return acslnam;
    }

    /**
     * {@code ACSADL1O} - {@code CUST-ADDR-LINE-1}.
     *
     * @return {@value #ACSADL1_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcsadl1() {
        return acsadl1;
    }

    /**
     * {@code ACSSTTEO} - {@code CUST-ADDR-STATE-CD}.
     *
     * @return {@value #ACSSTTE_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcsstte() {
        return acsstte;
    }

    /**
     * {@code ACSADL2O} - {@code CUST-ADDR-LINE-2}.
     *
     * @return {@value #ACSADL2_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcsadl2() {
        return acsadl2;
    }

    /**
     * {@code ACSZIPCO} - the first five characters of {@code CUST-ADDR-ZIP}.
     *
     * @return {@value #ACSZIPC_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcszipc() {
        return acszipc;
    }

    /**
     * {@code ACSCITYO} - {@code CUST-ADDR-LINE-3}, not a city field; see the member's documentation.
     *
     * @return {@value #ACSCITY_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcscity() {
        return acscity;
    }

    /**
     * {@code ACSCTRYO} - {@code CUST-ADDR-COUNTRY-CD}.
     *
     * @return {@value #ACSCTRY_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcsctry() {
        return acsctry;
    }

    /**
     * {@code ACSPHN1O} - the first thirteen characters of {@code CUST-PHONE-NUM-1}.
     *
     * @return {@value #ACSPHN1_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcsphn1() {
        return acsphn1;
    }

    /**
     * {@code ACSGOVTO} - {@code CUST-GOVT-ISSUED-ID}, unmasked per practice <b>B6</b>.
     *
     * @return {@value #ACSGOVT_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcsgovt() {
        return acsgovt;
    }

    /**
     * {@code ACSPHN2O} - the first thirteen characters of {@code CUST-PHONE-NUM-2}.
     *
     * @return {@value #ACSPHN2_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcsphn2() {
        return acsphn2;
    }

    /**
     * {@code ACSEFTCO} - {@code CUST-EFT-ACCOUNT-ID}.
     *
     * @return {@value #ACSEFTC_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcseftc() {
        return acseftc;
    }

    /**
     * {@code ACSPFLGO} - {@code CUST-PRI-CARD-HOLDER-IND}.
     *
     * @return {@value #ACSPFLG_LENGTH} character, untrimmed; never {@code null}
     */
    public String getAcspflg() {
        return acspflg;
    }

    /**
     * {@code INFOMSGO} - the information line; its visibility lives in {@code INFOMSGC}.
     *
     * @return {@value #INFOMSG_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getInfomsg() {
        return infomsg;
    }

    /**
     * {@code ERRMSGO} - the error line, also carried on the work area.
     *
     * @return {@value #ERRMSG_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getErrmsg() {
        return errmsg;
    }

    /**
     * The {@code XCTL} target program the client should call next.
     *
     * @return {@value #NEXT_PROGRAM_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getNextProgram() {
        return nextProgram;
    }

    /**
     * The mapset of the next screen.
     *
     * @return {@value #NEXT_MAPSET_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getNextMapset() {
        return nextMapset;
    }

    /**
     * The map of the next screen.
     *
     * @return {@value #NEXT_MAP_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getNextMap() {
        return nextMap;
    }

    /**
     * The {@code CVCRD01Y} work area, as an independent copy.
     *
     * <p>A copy, not the held instance, because {@link CardScreenState} is mutable and this payload is
     * not: handing out the instance would let a caller change a built response. To change the work area,
     * mutate the copy and rebuild - {@code response.toBuilder().cardScreenState(copy).build()}.
     *
     * @return a copy of the work area; never {@code null}
     */
    public CardScreenState getCardScreenState() {
        return new CardScreenState(cardScreenState);
    }

    /**
     * The {@code CARDDEMO-COMMAREA} the client must send back.
     *
     * @return the commarea; never {@code null}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    // =================================================================================================
    // Addressing a field by its ScreenField rather than by name. This is what the group codec, the parity
    // differ and the highlight seam all use, so a field is named in a way the compiler checks.
    // =================================================================================================

    /**
     * The stored image of one field.
     *
     * @param field which field to read
     * @return the image at its declared width, untrimmed; never {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public String value(ScreenField field) {
        Objects.requireNonNull(field, "A ScreenField is required to read a field value");
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
     * One field's attribute quad, writable in place.
     *
     * <p>The deliberate exception to this type's immutability. {@code app/cbl/COACTVWC.cbl:555-571} writes
     * {@code xxxC} bytes after the map has been populated, so the colour byte of a built payload has to be
     * settable: {@code response.attributes(ScreenField.INFOMSG).setColour(BmsAttributes.DFHBMDAR)}
     * reproduces {@code :568} exactly.
     *
     * @param field which field's quad
     * @return the live holder; never {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public FieldAttributes attributes(ScreenField field) {
        Objects.requireNonNull(field, "A ScreenField is required to read an attribute quad");
        return attributes.get(field);
    }

    /**
     * All {@value #FIELD_COUNT} quads, keyed by field.
     *
     * @return an unmodifiable view over the live holders, in copybook order; never {@code null}
     */
    @JsonIgnore
    public Map<ScreenField, FieldAttributes> attributeQuads() {
        return Collections.unmodifiableMap(attributes);
    }

    /**
     * The {@value #FIELD_COUNT} &times; {@value #ATTRIBUTE_ITEMS_PER_FIELD} attribute items, keyed by
     * their copybook names - {@code TRNNAMEC}, {@code TRNNAMEP}, {@code TRNNAMEH}, {@code TRNNAMEV} and so
     * on.
     *
     * <p>The shape a parity case asserts attribute bytes in: every item the output group declares, named
     * as {@code app/cpy-bms/COACTVW.CPY} names it.
     *
     * @return an unmodifiable, insertion-ordered snapshot of 148 entries; never {@code null}
     */
    @JsonIgnore
    public Map<String, Byte> attributeItems() {
        Map<String, Byte> items = new LinkedHashMap<>();
        for (ScreenField field : ScreenField.values()) {
            FieldAttributes quad = attributes.get(field);
            items.put(field.colourItemName(), quad.getColour());
            items.put(field.psItemName(), quad.getPs());
            items.put(field.hilightItemName(), quad.getHilight());
            items.put(field.validnItemName(), quad.getValidn());
        }
        return Collections.unmodifiableMap(items);
    }

    /**
     * The {@value #FIELD_COUNT} field images, keyed by their {@code xxxO} copybook names.
     *
     * <p>This is the fingerprint the parity harness compares field by field. Values are untrimmed at their
     * declared widths, because the padding is part of what a {@code PIC X} field holds.
     *
     * @return an unmodifiable, insertion-ordered snapshot of {@value #FIELD_COUNT} entries; never
     *         {@code null}
     */
    @JsonIgnore
    public Map<String, String> fieldImages() {
        Map<String, String> images = new LinkedHashMap<>();
        for (ScreenField field : ScreenField.values()) {
            images.put(field.symbolicItemName(), value(field));
        }
        return Collections.unmodifiableMap(images);
    }

    /**
     * Whether the conversation is on its first entry - {@code CDEMO-PGM-ENTER}, context
     * {@value NavigationContext#PGM_CONTEXT_ENTER}.
     *
     * @return {@code true} when the carried commarea says {@code ENTER}
     */
    @JsonIgnore
    public boolean isEnter() {
        return navigationContext.pgmContext() == NavigationContext.PGM_CONTEXT_ENTER;
    }

    /**
     * Whether the conversation is a re-entry - {@code CDEMO-PGM-REENTER}, context
     * {@value NavigationContext#PGM_CONTEXT_REENTER}.
     *
     * <p>{@code app/cbl/COACTVWC.cbl:908} sets this before sending the map, and it is the guard on the
     * {@code CSSETATY} highlight; see {@link #withHighlight(ScreenField, FieldValidationState, boolean)}.
     *
     * @return {@code true} when the carried commarea says {@code REENTER}
     */
    @JsonIgnore
    public boolean isReenter() {
        return navigationContext.pgmContext() == NavigationContext.PGM_CONTEXT_REENTER;
    }


    // =================================================================================================
    // CSSETATY, applied. The DECISION - including the AND CDEMO-PGM-REENTER guard - belongs to
    // FieldAttributeSetter and is not re-implemented here (gates G38 and G51). This type supplies the two
    // destinations the copybook names: the xxxC colour byte and the xxxO output item.
    // =================================================================================================

    /**
     * Applies a highlight decision to one field: {@link BmsAttributes#DFHRED} into its {@code xxxC} item
     * and, when the decision says so, {@code '*'} into its {@code xxxO} item.
     *
     * <p>This is the second half of {@code app/cpy/CSSETATY.cpy:17-27}, which {@code COACTVWC} spells out
     * inline at {@code app/cbl/COACTVWC.cbl:557-565} rather than copying. The two moves are performed in
     * the copybook's order, and the nesting is respected automatically because {@link FieldHighlight}
     * cannot report an assigned output item without an assigned colour item.
     *
     * <p>The asterisk goes through the ordinary {@code PIC X} store, so it arrives space-padded to the
     * receiver's width: {@code "*"} into {@code ACCTSIDO PIC X(11)} becomes an asterisk and ten spaces,
     * which is exactly what {@code MOVE '*' TO ACCTSIDO} produces.
     *
     * <p>Because the payload is immutable, a <em>new</em> payload is returned. A decision that assigns
     * nothing - {@link FieldHighlight#untouched()} - returns an equal payload rather than the same
     * instance, so callers need not special-case it.
     *
     * @param field     the field to repaint
     * @param highlight the decision to apply
     * @return a payload carrying the decision; never {@code null}
     * @throws NullPointerException     if {@code field} or {@code highlight} is {@code null}
     * @throws IllegalArgumentException if the decision names a different field, which would mean it was
     *                                  resolved for one field and applied to another
     */
    public AccountViewResponse withHighlight(ScreenField field, FieldHighlight highlight) {
        Objects.requireNonNull(field, "A screen field is required to apply a highlight");
        Objects.requireNonNull(highlight, "A highlight decision is required; use "
                + "FieldHighlight.none(field, map) to express 'change nothing'");

        String decidedFor = highlight.screenFieldPrefix();
        if (!decidedFor.isEmpty() && !decidedFor.equals(field.label())) {
            throw new IllegalArgumentException("The highlight was resolved for " + decidedFor
                    + " but is being applied to " + field.label() + "; CSSETATY qualifies both of its "
                    + "moves with one field, so a decision cannot be carried across fields");
        }

        Builder builder = toBuilder();
        // CSSETATY.cpy:21-22 - MOVE DFHRED TO (SCRNVAR2)C OF (MAPNAME3)O
        if (highlight.colourItemAssigned()) {
            builder.attributes.get(field).setColour(highlight.colourItemValue());
        }
        // CSSETATY.cpy:24-25 - MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O, nested inside the move above
        if (highlight.outputItemAssigned()) {
            builder.value(field, highlight.outputItemValue());
        }
        return builder.build();
    }

    /**
     * Resolves the highlight for one field and applies it, in one call.
     *
     * <p>The map name handed to {@link FieldAttributeSetter} is {@value #MAP_NAME} and the field name is
     * the field's {@code DFHMDF} label - the two tokens {@code CSSETATY} substitutes as
     * {@code (MAPNAME3)} and {@code (SCRNVAR2)}. Because the decision carries the
     * {@code CDEMO-PGM-REENTER} guard, a failing field on first entry is left exactly as the program
     * leaves it: not red, and not marked with an asterisk (gate <b>G38</b>).
     *
     * @param field   the field whose edit outcome is being reported
     * @param state   the field's validation state - {@code OK}, {@code NOT_OK} or {@code BLANK}
     * @param reenter {@code true} when {@code CDEMO-PGM-REENTER} holds, that is when
     *                {@code CDEMO-PGM-CONTEXT} is {@value NavigationContext#PGM_CONTEXT_REENTER}
     * @return a payload carrying the resolved decision; never {@code null}
     * @throws NullPointerException if {@code field} or {@code state} is {@code null}
     */
    public AccountViewResponse withHighlight(ScreenField field, FieldValidationState state,
            boolean reenter) {
        Objects.requireNonNull(field, "A screen field is required to resolve a highlight");
        Objects.requireNonNull(state, "A validation state is required; use "
                + "FieldValidationState.of(notOk, blank) to derive one from the two 88-levels");
        return withHighlight(field,
                FieldAttributeSetter.resolve(state, reenter, field.label(), MAP_NAME));
    }

    // =================================================================================================
    // The 955-byte group image.
    // =================================================================================================

    /**
     * The whole {@code CACTVWAO} output group as exactly {@value #GROUP_LENGTH} bytes.
     *
     * <p>Laid out precisely as {@code app/cpy-bms/COACTVW.CPY:241-464} declares it:
     *
     * <ol>
     *   <li>{@value #TIOAPFX_LENGTH} bytes of {@code TIOAPFX} prefix at {@code LOW-VALUES}, because
     *       {@code MOVE LOW-VALUES TO CACTVWAO} at {@code app/cbl/COACTVWC.cbl:432} covers the whole group
     *       including its prefix, and nothing in the program writes it afterwards;</li>
     *   <li>then, for each of the {@value #FIELD_COUNT} fields in copybook order:
     *       {@value #FILLER_LENGTH} bytes of {@code FILLER} at {@code LOW-VALUES}, the {@code xxxC},
     *       {@code xxxP}, {@code xxxH} and {@code xxxV} bytes raw, and the field's image at its declared
     *       width.</li>
     * </ol>
     *
     * <p>The {@code FILLER} is written rather than left to Java's zero-initialisation, so that "these
     * three bytes are {@code LOW-VALUES}" is a statement the code makes instead of an accident of the
     * language. Omitting {@code FILLER} would shift every offset after it and change the group's total
     * width, which is why gate <b>G21</b> is checked by the width of the result.
     *
     * <p>Character data is encoded through {@link FixedWidthCodec#charset()} and never through a platform
     * default, so a space is {@code 0x40} under IBM037 and {@code 0x20} under US-ASCII without this method
     * knowing which. {@code LOW-VALUES} survives either code page, being {@code U+0000} to {@code 0x00} in
     * both.
     *
     * <p>An attribute byte is <em>not</em> encoded through the charset. A 3270 attribute is a bit pattern
     * rather than text and is not a character in any code page's printable range;
     * {@link BmsAttributes#DFHRED} is the same byte whether the data around it is EBCDIC or ASCII, so the
     * four quad bytes are written and read raw.
     *
     * @param codec the codec supplying the charset
     * @return a new array of exactly {@value #GROUP_LENGTH} bytes; never {@code null}
     * @throws NullPointerException     if {@code codec} is {@code null}
     * @throws IllegalArgumentException if the codec's charset does not encode this data one byte per
     *                                  character
     */
    public byte[] toGroupImage(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to render the CACTVWAO group "
                + "image: it supplies the charset the field data is encoded in");
        byte[] group = new byte[GROUP_LENGTH];
        Arrays.fill(group, 0, TIOAPFX_LENGTH, LOW_VALUES_BYTE);
        for (ScreenField field : ScreenField.values()) {
            Arrays.fill(group, field.fillerOffset(), field.fillerOffset() + FILLER_LENGTH,
                    LOW_VALUES_BYTE);
            FieldAttributes quad = attributes.get(field);
            group[field.colourOffset()] = quad.getColour();
            group[field.psOffset()] = quad.getPs();
            group[field.hilightOffset()] = quad.getHilight();
            group[field.validnOffset()] = quad.getValidn();
            writeCharacters(group, field.dataOffset(), value(field), field.length(), codec,
                    field.describe());
        }
        return group;
    }

    /**
     * Reads a {@value #GROUP_LENGTH}-byte {@code CACTVWAO} image back into a payload.
     *
     * <p>Recovers the {@value #FIELD_COUNT} {@code xxxO} values at their full declared widths - untrimmed,
     * because a {@code PIC X} field's trailing spaces are part of its value - together with all four
     * attribute bytes per field.
     *
     * <p>Three parts of the image are deliberately <strong>not</strong> recovered, because none of them is
     * data: the {@value #TIOAPFX_LENGTH}-byte {@code TIOAPFX} prefix, which belongs to the terminal
     * input/output area; the {@value #FILLER_LENGTH} {@code FILLER} bytes per field, which are unnamed;
     * and the two conversation carriers, which are separate storage entirely - the {@code CVCRD01Y} work
     * area and the {@code CARDDEMO-COMMAREA} are not part of the BMS map and travel on their own. The
     * returned payload therefore carries a freshly initialised work area and an empty commarea.
     *
     * <p>So the round trip is exact where it can be: for a payload whose carriers and next-screen triple
     * are at their initial state, {@code fromGroupImage(x.toGroupImage(codec), codec)} equals {@code x}.
     *
     * @param groupImage the {@value #GROUP_LENGTH}-byte output group; read, never retained
     * @param codec      the codec supplying the charset
     * @return a payload carrying the image's fields and attribute bytes; never {@code null}
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code groupImage} is not exactly {@value #GROUP_LENGTH} bytes
     */
    public static AccountViewResponse fromGroupImage(byte[] groupImage, FixedWidthCodec codec) {
        Objects.requireNonNull(groupImage, "A group image is required to read a CACTVWAO area");
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to read a CACTVWAO area: it "
                + "supplies the charset the field data is encoded in");
        if (groupImage.length != GROUP_LENGTH) {
            throw new IllegalArgumentException("The CACTVWAO group of app/cpy-bms/COACTVW.CPY is "
                    + GROUP_LENGTH + " bytes - " + TIOAPFX_LENGTH + " of TIOAPFX prefix, plus "
                    + FIELD_COUNT + " fields at " + FIELD_OVERHEAD + " bytes of overhead each, plus "
                    + PAYLOAD_LENGTH + " bytes of data - but this image is " + groupImage.length
                    + " byte(s)");
        }
        Builder builder = new Builder();
        for (ScreenField field : ScreenField.values()) {
            builder.value(field, readCharacters(groupImage, field, codec));
            FieldAttributes quad = builder.attributes.get(field);
            quad.setColour(groupImage[field.colourOffset()]);
            quad.setPs(groupImage[field.psOffset()]);
            quad.setHilight(groupImage[field.hilightOffset()]);
            quad.setValidn(groupImage[field.validnOffset()]);
        }
        return builder.build();
    }

    /**
     * Decodes one field's data span from a group image under the codec's code page.
     *
     * <p>The span is copied out before decoding because
     * {@link FixedWidthCodec#decodeImage(byte[], String)} decodes a whole array; copying exactly the
     * declared width is what keeps the decode bounded to this field rather than reaching into its
     * neighbour.
     *
     * @param groupImage the group image to read from
     * @param field      which field's data to read
     * @param codec      the codec supplying the charset
     * @return the field's value at its full declared width, untrimmed
     */
    private static String readCharacters(byte[] groupImage, ScreenField field, FixedWidthCodec codec) {
        byte[] span = Arrays.copyOfRange(groupImage, field.dataOffset(), field.endOffsetExclusive());
        return codec.decodeImage(span, "the CACTVWAO item " + field.symbolicItemName());
    }

    /**
     * Encodes an image into a group image at a given offset, insisting it occupy exactly the declared
     * number of bytes.
     *
     * <p>The insistence is the point. A {@code PIC X(n)} item is n <em>bytes</em>, and every code page
     * this system reads - IBM037 for the EBCDIC datasets, US-ASCII for the ASCII fixtures - is
     * single-byte, so n characters must encode to n bytes. Under a multi-byte charset the group would
     * overflow its declared width and every offset after it would shift.
     *
     * <p>{@link FixedWidthCodec#encodeImage(String, String)} is the outer guard and reports a multi-byte
     * code page itself, naming the item. What the comparison below catches is the other way of being
     * wrong: an {@code image} that is not already at {@code declaredWidth} characters. Every value stored
     * on this type has been through {@link Builder#build()}, which normalises, so it cannot fire today -
     * which is exactly why it is worth keeping. Being unreachable is the property being asserted.
     *
     * @param group         the group image being built
     * @param offset        where the item begins
     * @param image         the item's value, already at its declared character width
     * @param declaredWidth the item's declared width in bytes
     * @param codec         the codec whose charset encodes the text
     * @param what          how to describe the item in a failure message
     * @throws IllegalArgumentException if the encoding is not exactly {@code declaredWidth} bytes
     */
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
                    + "; a PIC X(n) item is n bytes, so the CACTVWAO group can only be rendered from an "
                    + "image already at its declared width under a single-byte code page such as IBM037 "
                    + "or US-ASCII");
        }
        System.arraycopy(encoded, 0, group, offset, declaredWidth);
    }

    // =================================================================================================
    // Value semantics. Everything the payload holds participates: the 37 fields, the next-screen triple,
    // both carriers and all 37 attribute quads. The quads are included because they are behaviour - a
    // payload whose ACCTSIDC is DFHRED is not the same screen as one whose ACCTSIDC is DFHDFCOL - even
    // though they never reach the wire.
    // =================================================================================================

    /**
     * Value equality over every member.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a payload holding the same values throughout
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AccountViewResponse response)) {
            return false;
        }
        return sameFieldValues(response)
                && nextProgram.equals(response.nextProgram)
                && nextMapset.equals(response.nextMapset)
                && nextMap.equals(response.nextMap)
                && cardScreenState.equals(response.cardScreenState)
                && navigationContext.equals(response.navigationContext)
                && attributes.equals(response.attributes);
    }

    /**
     * Compares the {@value #FIELD_COUNT} field images by walking {@link ScreenField}.
     *
     * <p>Walked rather than written out member by member so that adding a field cannot leave the comparison
     * silently incomplete.
     *
     * @param that the payload to compare against
     * @return {@code true} when all {@value #FIELD_COUNT} images match
     */
    private boolean sameFieldValues(AccountViewResponse that) {
        for (ScreenField field : ScreenField.values()) {
            if (!value(field).equals(that.value(field))) {
                return false;
            }
        }
        return true;
    }

    /**
     * A hash consistent with {@link #equals(Object)} over the same members.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        int result = 1;
        for (ScreenField field : ScreenField.values()) {
            result = 31 * result + value(field).hashCode();
        }
        result = 31 * result + nextProgram.hashCode();
        result = 31 * result + nextMapset.hashCode();
        result = 31 * result + nextMap.hashCode();
        result = 31 * result + cardScreenState.hashCode();
        result = 31 * result + navigationContext.hashCode();
        return 31 * result + attributes.hashCode();
    }

    /**
     * A single-line rendering naming the map, the next-screen triple and the two message lines.
     *
     * <p>Deliberately short: the whole {@value #FIELD_COUNT}-field dump is {@link #fieldImages()} and the
     * whole attribute dump is {@link #attributeItems()}, and a log line wants neither.
     *
     * @return a description of the payload's identity and outcome; never {@code null}
     */
    @JsonIgnore
    public String describe() {
        return "CACTVWAO[map=" + MAP_NAME
                + ", tran=" + THIS_TRANID
                + ", next=" + nextProgram.trim() + '/' + nextMapset.trim() + '/' + nextMap.trim()
                + ", infomsg='" + infomsg.trim()
                + "', errmsg='" + errmsg.trim()
                + "']";
    }

    /**
     * A diagnostic rendering naming every field by its {@code DFHMDF} label.
     *
     * <p><strong>Nothing is masked, redacted or abbreviated.</strong> {@code ACSTSSN}, {@code ACSTDOB} and
     * {@code ACSGOVT} render exactly as stored, because {@code COACTVWC} displays them on a 3270 in the
     * clear and hiding them here would be an unrequested behaviour change (practice <b>B6</b>). This is
     * the same deliberate divergence {@link AccountViewRequest} records: the sibling payloads route their
     * diagnostics through {@code common/SensitiveDiagnostics}, which is outside this file's declared
     * dependency set. It is recorded here rather than left to be discovered, and nothing is weakened
     * either - no value is exposed that the screen does not already show.
     *
     * <p>Values are quoted so that the trailing spaces of a fixed-width field, which are part of its
     * value, stay visible in a failure message, and each field's attribute quad is appended so the colour
     * and highlight bytes are readable too.
     *
     * @return a single-line rendering of every field, the next-screen triple and both carriers; never
     *         {@code null}
     */
    /**
     * A diagnostic rendering that discloses nothing a log must not hold.
     *
     * <p>Every field goes through {@link DiagnosticText#screenField(String, String)}, which decides from
     * the {@code DFHMDF} label itself: an account, card or customer identifier is masked to its last four
     * characters, a credential or personal item - {@code ACSTSSN}, {@code ACSGOVT}, {@code ACSEFT},
     * {@code ACSTDOB}, the name fields - is withheld entirely, and everything else is escaped to a single
     * line. Deciding from the label rather than from a list held here means the next field added to the
     * mapset is treated correctly without anyone remembering to classify it.
     *
     * <p><strong>Nothing about the parity surface changes.</strong> The JSON payload, every accessor and
     * every fixed-width image method are untouched; this is a Java-only rendering with no COBOL
     * counterpart, so withholding from it costs no observable behaviour. What it prevents is a log line
     * carrying a customer's social security number (CWE-532) or a field value whose embedded CR or LF
     * forges a second log line (CWE-117).
     *
     * @return the rendering; never {@code null}
     */
    @Override
    public String toString() {
        StringBuilder rendered = new StringBuilder("AccountViewResponse[");
        for (ScreenField field : ScreenField.values()) {
            rendered.append(field.label())
                    .append("='")
                    .append(DiagnosticText.screenField(field.label(), value(field)))
                    .append("' ")
                    .append(attributes.get(field))
                    .append(", ");
        }
        return rendered.append("nextProgram='").append(nextProgram)
                .append("', nextMapset='").append(nextMapset)
                .append("', nextMap='").append(nextMap)
                .append("', cardScreenState=").append(cardScreenState)
                .append(", navigationContext=").append(navigationContext)
                .append(']')
                .toString();
    }


    // =================================================================================================
    // The 37 fields, as an enumeration. Every place that needs to address a field - the group codec, the
    // attribute quads, the highlight seam, a parity differ - names it in a way the compiler checks instead
    // of passing a string that can be misspelled. Each constant carries the whole of its provenance: the
    // DFHMDF label, the xxxO PICTURE as written, the width, both source line numbers, the screen position
    // and the data offset, so the gate G9 trace is readable from one place and verifiable against the
    // copybook without arithmetic.
    // =================================================================================================

    /**
     * One of the {@value #FIELD_COUNT} name-labelled {@code DFHMDF} fields of
     * {@code app/bms/COACTVW.bms}, in the order the mapset declares them - which is also the order
     * {@code app/cpy-bms/COACTVW.CPY} lays their storage down.
     *
     * <p>That order interleaves the screen's two columns: {@code ADTOPEN} at {@code POS=(6,17)} is
     * followed by {@code ACRDLIM} at {@code POS=(6,61)}, then {@code AEXPDT} at {@code POS=(7,17)} by
     * {@code ACSHLIM} at {@code POS=(7,61)}, and so on. It is the copybook's order and it is not sorted.
     *
     * <p>The 63 unnamed {@code DFHMDF} entries have no constant here. An unnamed entry is a screen
     * literal - {@code INITIAL='Tran:'}, {@code INITIAL='Account Number:'} and the like - which BMS paints
     * but never reports, so it generates no symbolic-map item and holds no value this type could carry.
     *
     * <p>{@code FKEYS}, {@code FKEY05}, {@code FKEY12} and {@code PAGENO} are absent because
     * {@code COACTVW} declares none of them; they belong to sibling mapsets and are not added for
     * symmetry.
     *
     * <p>The order and the widths are identical to {@link AccountViewRequest.ScreenField}'s. The two
     * differences are stated on the constants that carry them: {@link #ACCTSID}'s {@code PICTURE}, and the
     * five constants that report {@link #isNumericEdited()}.
     */
    public enum ScreenField {

        /** {@code TRNNAME} - the transaction identifier, {@code TRNNAMEO PIC X(4)}. */
        TRNNAME("TRNNAME", "X(4)", TRNNAME_LENGTH, 248, 34, 1, 7, 19, false),

        /** {@code TITLE01} - the first title line, {@code TITLE01O PIC X(40)}. */
        TITLE01("TITLE01", "X(40)", TITLE01_LENGTH, 254, 38, 1, 21, 30, false),

        /** {@code CURDATE} - {@code mm/dd/yy}, {@code CURDATEO PIC X(8)}. */
        CURDATE("CURDATE", "X(8)", CURDATE_LENGTH, 260, 47, 1, 71, 77, false),

        /** {@code PGMNAME} - the program name, {@code PGMNAMEO PIC X(8)}. */
        PGMNAME("PGMNAME", "X(8)", PGMNAME_LENGTH, 266, 57, 2, 7, 92, false),

        /** {@code TITLE02} - the second title line, {@code TITLE02O PIC X(40)}. */
        TITLE02("TITLE02", "X(40)", TITLE02_LENGTH, 272, 61, 2, 21, 107, false),

        /** {@code CURTIME} - {@code hh:mm:ss}, {@code CURTIMEO PIC X(8)}. */
        CURTIME("CURTIME", "X(8)", CURTIME_LENGTH, 278, 70, 2, 71, 154, false),

        /**
         * {@code ACCTSID} - the account number, {@code ACCTSIDO PIC X(11)}.
         *
         * <p>The one field whose {@code PICTURE} differs between the two groups: the input twin is
         * {@code PIC 99999999999} at {@code app/cpy-bms/COACTVW.CPY:60} and the mapset declares
         * {@code PICIN='99999999999'} with no {@code PICOUT}. The width is 11 on both sides.
         *
         * <p>It is also the only field the program writes three different kinds of value into - a
         * numeric identifier, {@code LOW-VALUES}, and the single character {@code '*'} - which is why it
         * is modelled alphanumeric.
         */
        ACCTSID("ACCTSID", "X(11)", ACCTSID_LENGTH, 284, 84, 5, 38, 169, false),

        /** {@code ACSTTUS} - the account status, {@code ACSTTUSO PIC X(1)}. */
        ACSTTUS("ACSTTUS", "X(1)", ACSTTUS_LENGTH, 290, 97, 5, 70, 187, false),

        /** {@code ADTOPEN} - the open date, {@code ADTOPENO PIC X(10)}. */
        ADTOPEN("ADTOPEN", "X(10)", ADTOPEN_LENGTH, 296, 107, 6, 17, 195, false),

        /**
         * {@code ACRDLIM} - the credit limit, {@code ACRDLIMO PIC +ZZZ,ZZZ,ZZZ.99}.
         *
         * <p>Numeric-edited; {@code app/bms/COACTVW.bms:120} declares the same mask as
         * {@code PICOUT='+ZZZ,ZZZ,ZZZ.99'} with {@code JUSTIFY=(RIGHT)}.
         */
        ACRDLIM("ACRDLIM", AMOUNT_PICTURE, ACRDLIM_LENGTH, 302, 117, 6, 61, 212, true),

        /**
         * {@code AEXPDT} - the expiry date, {@code AEXPDTO PIC X(10)}.
         *
         * <p>Fed from {@code ACCT-EXPIRAION-DATE}, misspelled exactly so in
         * {@code app/cpy/CVACT01Y.cpy} and reproduced verbatim.
         */
        AEXPDT("AEXPDT", "X(10)", AEXPDT_LENGTH, 308, 128, 7, 17, 234, false),

        /** {@code ACSHLIM} - the cash credit limit, {@code ACSHLIMO PIC +ZZZ,ZZZ,ZZZ.99}. */
        ACSHLIM("ACSHLIM", AMOUNT_PICTURE, ACSHLIM_LENGTH, 314, 138, 7, 61, 251, true),

        /** {@code AREISDT} - the reissue date, {@code AREISDTO PIC X(10)}. */
        AREISDT("AREISDT", "X(10)", AREISDT_LENGTH, 320, 149, 8, 17, 273, false),

        /** {@code ACURBAL} - the current balance, {@code ACURBALO PIC +ZZZ,ZZZ,ZZZ.99}. */
        ACURBAL("ACURBAL", AMOUNT_PICTURE, ACURBAL_LENGTH, 326, 159, 8, 61, 290, true),

        /** {@code ACRCYCR} - the current cycle credit, {@code ACRCYCRO PIC +ZZZ,ZZZ,ZZZ.99}. */
        ACRCYCR("ACRCYCR", AMOUNT_PICTURE, ACRCYCR_LENGTH, 332, 171, 9, 61, 312, true),

        /** {@code AADDGRP} - the disclosure group, {@code AADDGRPO PIC X(10)}. */
        AADDGRP("AADDGRP", "X(10)", AADDGRP_LENGTH, 338, 182, 10, 23, 334, false),

        /** {@code ACRCYDB} - the current cycle debit, {@code ACRCYDBO PIC +ZZZ,ZZZ,ZZZ.99}. */
        ACRCYDB("ACRCYDB", AMOUNT_PICTURE, ACRCYDB_LENGTH, 344, 192, 10, 61, 351, true),

        /** {@code ACSTNUM} - the customer identifier, {@code ACSTNUMO PIC X(9)}. */
        ACSTNUM("ACSTNUM", "X(9)", ACSTNUM_LENGTH, 350, 207, 12, 23, 373, false),

        /**
         * {@code ACSTSSN} - the hyphenated social security number, {@code ACSTSSNO PIC X(12)}.
         *
         * <p>The only field the program fills with a {@code STRING} rather than a {@code MOVE}, and
         * therefore the only one whose last byte the program never writes.
         */
        ACSTSSN("ACSTSSN", "X(12)", ACSTSSN_LENGTH, 356, 216, 12, 54, 389, false),

        /** {@code ACSTDOB} - the date of birth, {@code ACSTDOBO PIC X(10)}. */
        ACSTDOB("ACSTDOB", "X(10)", ACSTDOB_LENGTH, 362, 225, 13, 23, 408, false),

        /** {@code ACSTFCO} - the FICO score, {@code ACSTFCOO PIC X(3)}. */
        ACSTFCO("ACSTFCO", "X(3)", ACSTFCO_LENGTH, 368, 234, 13, 61, 425, false),

        /** {@code ACSFNAM} - the given name, {@code ACSFNAMO PIC X(25)}. */
        ACSFNAM("ACSFNAM", "X(25)", ACSFNAM_LENGTH, 374, 251, 15, 1, 435, false),

        /** {@code ACSMNAM} - the middle name, {@code ACSMNAMO PIC X(25)}. */
        ACSMNAM("ACSMNAM", "X(25)", ACSMNAM_LENGTH, 380, 256, 15, 28, 467, false),

        /** {@code ACSLNAM} - the family name, {@code ACSLNAMO PIC X(25)}. */
        ACSLNAM("ACSLNAM", "X(25)", ACSLNAM_LENGTH, 386, 261, 15, 55, 499, false),

        /** {@code ACSADL1} - address line 1, {@code ACSADL1O PIC X(50)}. */
        ACSADL1("ACSADL1", "X(50)", ACSADL1_LENGTH, 392, 268, 16, 10, 531, false),

        /** {@code ACSSTTE} - the state code, {@code ACSSTTEO PIC X(2)}. */
        ACSSTTE("ACSSTTE", "X(2)", ACSSTTE_LENGTH, 398, 277, 16, 73, 588, false),

        /** {@code ACSADL2} - address line 2, {@code ACSADL2O PIC X(50)}. */
        ACSADL2("ACSADL2", "X(50)", ACSADL2_LENGTH, 404, 282, 17, 10, 597, false),

        /** {@code ACSZIPC} - the postal code, {@code ACSZIPCO PIC X(5)}, right-justified on the screen. */
        ACSZIPC("ACSZIPC", "X(5)", ACSZIPC_LENGTH, 410, 291, 17, 73, 654, false),

        /**
         * {@code ACSCITY} - {@code ACSCITYO PIC X(50)}, fed from {@code CUST-ADDR-LINE-3}.
         *
         * <p>The screen labels it a city; {@code app/cpy/CVCUS01Y.cpy} has no city item.
         */
        ACSCITY("ACSCITY", "X(50)", ACSCITY_LENGTH, 416, 301, 18, 10, 666, false),

        /** {@code ACSCTRY} - the country code, {@code ACSCTRYO PIC X(3)}. */
        ACSCTRY("ACSCTRY", "X(3)", ACSCTRY_LENGTH, 422, 310, 18, 73, 723, false),

        /** {@code ACSPHN1} - the first telephone number, {@code ACSPHN1O PIC X(13)}. */
        ACSPHN1("ACSPHN1", "X(13)", ACSPHN1_LENGTH, 428, 319, 19, 10, 733, false),

        /** {@code ACSGOVT} - the government-issued identifier, {@code ACSGOVTO PIC X(20)}. */
        ACSGOVT("ACSGOVT", "X(20)", ACSGOVT_LENGTH, 434, 326, 19, 58, 753, false),

        /** {@code ACSPHN2} - the second telephone number, {@code ACSPHN2O PIC X(13)}. */
        ACSPHN2("ACSPHN2", "X(13)", ACSPHN2_LENGTH, 440, 335, 20, 10, 780, false),

        /** {@code ACSEFTC} - the EFT account, {@code ACSEFTCO PIC X(10)}. */
        ACSEFTC("ACSEFTC", "X(10)", ACSEFTC_LENGTH, 446, 342, 20, 41, 800, false),

        /** {@code ACSPFLG} - the primary card holder indicator, {@code ACSPFLGO PIC X(1)}. */
        ACSPFLG("ACSPFLG", "X(1)", ACSPFLG_LENGTH, 452, 351, 20, 78, 817, false),

        /**
         * {@code INFOMSG} - the information line, {@code INFOMSGO PIC X(45)}.
         *
         * <p>The only field whose {@code xxxC} byte is a visibility switch rather than a colour:
         * {@link BmsAttributes#DFHBMDAR} hides the line, {@link BmsAttributes#DFHNEUTR} shows it.
         */
        INFOMSG("INFOMSG", "X(45)", INFOMSG_LENGTH, 458, 356, 22, 23, 825, false),

        /** {@code ERRMSG} - the error line, {@code ERRMSGO PIC X(78)}. */
        ERRMSG("ERRMSG", "X(78)", ERRMSG_LENGTH, 464, 365, 23, 1, 877, false);

        /** The {@code DFHMDF} label, verbatim. */
        private final String label;

        /** The {@code xxxO} item's {@code PICTURE}, verbatim. */
        private final String picture;

        /** The item's declared width in bytes. */
        private final int length;

        /** The 1-based line of the {@code xxxO} item in {@code app/cpy-bms/COACTVW.CPY}. */
        private final int copybookLine;

        /** The 1-based line where the field's {@code DFHMDF} begins in {@code app/bms/COACTVW.bms}. */
        private final int mapsetLine;

        /** The screen row from {@code POS=(row,column)}, 1 to 24. */
        private final int screenRow;

        /** The screen column from {@code POS=(row,column)}, 1 to 80. */
        private final int screenColumn;

        /** Where the item's data begins in the {@value #GROUP_LENGTH}-byte group, 0-based. */
        private final int dataOffset;

        /** Whether the {@code PICTURE} is the {@value #AMOUNT_PICTURE} edit mask. */
        private final boolean numericEdited;

        /**
         * @param label         the {@code DFHMDF} label
         * @param picture       the {@code xxxO} {@code PICTURE} as written
         * @param length        the declared width in bytes
         * @param copybookLine  the {@code xxxO} item's line in {@code app/cpy-bms/COACTVW.CPY}
         * @param mapsetLine    the {@code DFHMDF}'s first line in {@code app/bms/COACTVW.bms}
         * @param screenRow     the row from {@code POS=}
         * @param screenColumn  the column from {@code POS=}
         * @param dataOffset    where the data begins in the group, 0-based
         * @param numericEdited whether the {@code PICTURE} is the edit mask
         */
        ScreenField(String label,
                    String picture,
                    int length,
                    int copybookLine,
                    int mapsetLine,
                    int screenRow,
                    int screenColumn,
                    int dataOffset,
                    boolean numericEdited) {
            this.label = label;
            this.picture = picture;
            this.length = length;
            this.copybookLine = copybookLine;
            this.mapsetLine = mapsetLine;
            this.screenRow = screenRow;
            this.screenColumn = screenColumn;
            this.dataOffset = dataOffset;
            this.numericEdited = numericEdited;
        }

        /**
         * The {@code DFHMDF} label - also the {@code (SCRNVAR2)} token {@code CSSETATY} substitutes.
         *
         * @return the label, for example {@code "ACCTSID"}; never {@code null}
         */
        public String label() {
            return label;
        }

        /**
         * The output data item's name: the label followed by
         * {@link FieldAttributeSetter#OUTPUT_ITEM_SUFFIX}.
         *
         * @return for example {@code "ACCTSIDO"}; never {@code null}
         */
        public String symbolicItemName() {
            return label + FieldAttributeSetter.OUTPUT_ITEM_SUFFIX;
        }

        /**
         * The colour item's name: the label followed by
         * {@link FieldAttributeSetter#COLOUR_ITEM_SUFFIX}. Present because
         * {@code DSATTS} names {@code COLOR}.
         *
         * @return for example {@code "ACCTSIDC"}; never {@code null}
         */
        public String colourItemName() {
            return label + FieldAttributeSetter.COLOUR_ITEM_SUFFIX;
        }

        /**
         * The programmed-symbols item's name. Present because {@code DSATTS} names {@code PS}.
         *
         * @return for example {@code "ACCTSIDP"}; never {@code null}
         */
        public String psItemName() {
            return label + PS_ITEM_SUFFIX;
        }

        /**
         * The highlight item's name. Present because {@code DSATTS} names {@code HILIGHT}.
         *
         * @return for example {@code "ACCTSIDH"}; never {@code null}
         */
        public String hilightItemName() {
            return label + HILIGHT_ITEM_SUFFIX;
        }

        /**
         * The validation item's name. Present because {@code DSATTS} names {@code VALIDN}.
         *
         * @return for example {@code "ACCTSIDV"}; never {@code null}
         */
        public String validnItemName() {
            return label + VALIDN_ITEM_SUFFIX;
        }

        /**
         * The {@code PICTURE} as {@code app/cpy-bms/COACTVW.CPY} writes it.
         *
         * @return {@code "X(n)"} for 32 fields, {@value #AMOUNT_PICTURE} for the other five; never
         *         {@code null}
         */
        public String picture() {
            return picture;
        }

        /**
         * Whether this field carries the {@value #AMOUNT_PICTURE} edit mask.
         *
         * @return {@code true} for the {@value #AMOUNT_MASK_FIELD_COUNT} money items
         */
        public boolean isNumericEdited() {
            return numericEdited;
        }

        /**
         * The declared width in bytes, which is both the {@code PICTURE} width and the mapset's
         * {@code LENGTH=} operand.
         *
         * @return the width, at least 1
         */
        public int length() {
            return length;
        }

        /**
         * The {@code xxxO} item's line in {@code app/cpy-bms/COACTVW.CPY}.
         *
         * @return a 1-based line number between 248 and 464
         */
        public int copybookLine() {
            return copybookLine;
        }

        /**
         * The field's {@code DFHMDF} first line in {@code app/bms/COACTVW.bms}.
         *
         * @return a 1-based line number between 34 and 365
         */
        public int mapsetLine() {
            return mapsetLine;
        }

        /**
         * The screen row from {@code POS=(row,column)}.
         *
         * @return 1 to 24, the {@code SIZE=(24,80)} of {@code app/bms/COACTVW.bms:26}
         */
        public int screenRow() {
            return screenRow;
        }

        /**
         * The screen column from {@code POS=(row,column)}.
         *
         * @return 1 to 80
         */
        public int screenColumn() {
            return screenColumn;
        }

        /**
         * Where the field's {@code FILLER PICTURE X(3)} begins - the start of its overhead.
         *
         * @return {@code dataOffset - }{@value #FIELD_OVERHEAD}
         */
        public int fillerOffset() {
            return dataOffset - FIELD_OVERHEAD;
        }

        /**
         * Where the field's {@code xxxC} colour byte sits.
         *
         * @return the offset of the colour item
         */
        public int colourOffset() {
            return fillerOffset() + FILLER_LENGTH;
        }

        /**
         * Where the field's {@code xxxP} programmed-symbols byte sits.
         *
         * @return the offset of the PS item
         */
        public int psOffset() {
            return colourOffset() + ATTRIBUTE_ITEM_LENGTH;
        }

        /**
         * Where the field's {@code xxxH} highlight byte sits.
         *
         * @return the offset of the highlight item
         */
        public int hilightOffset() {
            return psOffset() + ATTRIBUTE_ITEM_LENGTH;
        }

        /**
         * Where the field's {@code xxxV} validation byte sits.
         *
         * @return the offset of the validation item
         */
        public int validnOffset() {
            return hilightOffset() + ATTRIBUTE_ITEM_LENGTH;
        }

        /**
         * Where the field's data begins in the {@value #GROUP_LENGTH}-byte group.
         *
         * @return a 0-based offset between 19 and 877
         */
        public int dataOffset() {
            return dataOffset;
        }

        /**
         * One past the field's last data byte.
         *
         * @return {@code dataOffset() + length()}
         */
        public int endOffsetExclusive() {
            return dataOffset + length;
        }

        /**
         * A description naming the item, its {@code PICTURE}, both source lines and its screen position.
         *
         * @return for example
         *         {@code "ACCTSIDO PIC X(11) at app/cpy-bms/COACTVW.CPY:284, DFHMDF ACCTSID at
         *         app/bms/COACTVW.bms:84, POS=(5,38)"}; never {@code null}
         */
        public String describe() {
            return symbolicItemName() + " PIC " + picture + " at app/cpy-bms/COACTVW.CPY:" + copybookLine
                    + ", DFHMDF " + label + " at app/bms/COACTVW.bms:" + mapsetLine + ", POS=("
                    + screenRow + ',' + screenColumn + ')';
        }

        /**
         * The constant whose {@code DFHMDF} label is {@code label}.
         *
         * @param label the label to look up, case sensitive as the mapset writes it
         * @return the matching constant; never {@code null}
         * @throws NullPointerException     if {@code label} is {@code null}
         * @throws IllegalArgumentException if no field carries that label
         */
        public static ScreenField byLabel(String label) {
            Objects.requireNonNull(label, "A DFHMDF label is required to look up a ScreenField");
            for (ScreenField field : values()) {
                if (field.label.equals(label)) {
                    return field;
                }
            }
            throw new IllegalArgumentException("app/bms/COACTVW.bms declares no name-labelled DFHMDF "
                    + "called '" + label + "'; the " + FIELD_COUNT + " it does declare are "
                    + Arrays.toString(values()));
        }
    }


    /**
     * One field's {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV} bytes.
     *
     * <p>These four items exist because {@code app/bms/COACTVW.bms:24-25} declares
     * {@code DSATTS=(COLOR,HILIGHT,PS,VALIDN)} and {@code MAPATTS=(COLOR,HILIGHT,PS,VALIDN)} on the
     * {@code DFHMDI} - not because of {@code EXTATT}, which this mapset does not use at all. The mapping
     * is positional and fixed by the copybook: {@code C} is colour, {@code P} programmed symbols,
     * {@code H} highlight, {@code V} validation.
     *
     * <p>Per the symbolic-map contract these are presentation <em>metadata</em>, never payload members,
     * so the holder is excluded from JSON wherever it is exposed. It is mutable because
     * {@code app/cbl/COACTVWC.cbl:555-571} writes colour bytes after the map has been populated; that is
     * the single, documented exception to {@link AccountViewResponse}'s immutability.
     *
     * <p>All four start at {@code LOW-VALUES}, which is the state
     * {@code MOVE LOW-VALUES TO CACTVWAO} at {@code app/cbl/COACTVWC.cbl:432} leaves them in. Note that
     * {@code X'00'} is also {@link BmsAttributes#DFHDFCOL} and {@link BmsAttributes#DFHDFHI}, so
     * "unwritten" and "default colour, default highlight" are the same byte on the wire - the 3270
     * convention, and the reason one {@code MOVE LOW-VALUES} suffices as initialisation.
     *
     * <p>Not thread safe, by design.
     */
    public static final class FieldAttributes {

        /** The unwritten value of every item: {@code LOW-VALUES}. */
        public static final byte UNSET = LOW_VALUES_BYTE;

        /** {@code xxxC} - the extended colour byte. */
        private byte colour;

        /** {@code xxxP} - the programmed-symbols byte. */
        private byte ps;

        /** {@code xxxH} - the extended highlight byte. */
        private byte hilight;

        /** {@code xxxV} - the validation byte. */
        private byte validn;

        /** A quad at {@code LOW-VALUES}, as map initialisation leaves it. */
        public FieldAttributes() {
            resetToLowValues();
        }

        /**
         * A quad with all four items given.
         *
         * @param colour  the {@code xxxC} byte
         * @param ps      the {@code xxxP} byte
         * @param hilight the {@code xxxH} byte
         * @param validn  the {@code xxxV} byte
         */
        public FieldAttributes(byte colour, byte ps, byte hilight, byte validn) {
            this.colour = colour;
            this.ps = ps;
            this.hilight = hilight;
            this.validn = validn;
        }

        /**
         * An independent copy.
         *
         * @param other the quad to copy
         * @throws NullPointerException if {@code other} is {@code null}
         */
        public FieldAttributes(FieldAttributes other) {
            Objects.requireNonNull(other, "A quad is required to copy one");
            this.colour = other.colour;
            this.ps = other.ps;
            this.hilight = other.hilight;
            this.validn = other.validn;
        }

        /**
         * The {@code xxxC} colour byte.
         *
         * @return the byte, {@value #UNSET} when unwritten
         */
        public byte getColour() {
            return colour;
        }

        /**
         * Writes the {@code xxxC} colour byte - {@code MOVE DFHDFCOL TO ACCTSIDC} at
         * {@code app/cbl/COACTVWC.cbl:555} and {@code MOVE DFHRED TO ACCTSIDC} at {@code :558}.
         *
         * @param colour a {@link BmsAttributes} colour constant
         */
        public void setColour(byte colour) {
            this.colour = colour;
        }

        /**
         * The {@code xxxP} programmed-symbols byte.
         *
         * @return the byte, {@value #UNSET} when unwritten - which it always is, because
         *         {@code COACTVWC} writes no {@code xxxP} item
         */
        public byte getPs() {
            return ps;
        }

        /**
         * Writes the {@code xxxP} programmed-symbols byte.
         *
         * @param ps the byte to store
         */
        public void setPs(byte ps) {
            this.ps = ps;
        }

        /**
         * The {@code xxxH} highlight byte.
         *
         * @return the byte, {@value #UNSET} when unwritten
         */
        public byte getHilight() {
            return hilight;
        }

        /**
         * Writes the {@code xxxH} highlight byte.
         *
         * @param hilight a {@link BmsAttributes} highlight constant
         */
        public void setHilight(byte hilight) {
            this.hilight = hilight;
        }

        /**
         * The {@code xxxV} validation byte.
         *
         * @return the byte, {@value #UNSET} when unwritten
         */
        public byte getValidn() {
            return validn;
        }

        /**
         * Writes the {@code xxxV} validation byte.
         *
         * @param validn the byte to store
         */
        public void setValidn(byte validn) {
            this.validn = validn;
        }

        /** Returns all four items to {@code LOW-VALUES}, reproducing {@code MOVE LOW-VALUES}. */
        public void resetToLowValues() {
            this.colour = UNSET;
            this.ps = UNSET;
            this.hilight = UNSET;
            this.validn = UNSET;
        }

        /**
         * Whether the colour item holds {@link BmsAttributes#DFHRED} - the {@code CSSETATY} error colour.
         *
         * @return {@code true} when the field is painted red
         */
        public boolean isRedHighlighted() {
            return colour == BmsAttributes.DFHRED;
        }

        /**
         * Whether the colour item holds {@link BmsAttributes#DFHDFCOL}, the default.
         *
         * <p>Indistinguishable from unwritten, and deliberately so; see this class's documentation.
         *
         * @return {@code true} when the field carries the default colour
         */
        public boolean isDefaultColour() {
            return colour == BmsAttributes.DFHDFCOL;
        }

        /**
         * Whether the colour item holds {@link BmsAttributes#DFHBMDAR}, the non-display attribute
         * {@code app/cbl/COACTVWC.cbl:568} moves into {@code INFOMSGC} to hide the information line.
         *
         * @return {@code true} when the field is suppressed from display
         */
        public boolean isNonDisplay() {
            return colour == BmsAttributes.DFHBMDAR;
        }

        /**
         * Whether the colour item holds {@link BmsAttributes#DFHNEUTR}, which
         * {@code app/cbl/COACTVWC.cbl:570} moves into {@code INFOMSGC} to show the information line.
         *
         * @return {@code true} when the field is shown in the neutral colour
         */
        public boolean isNeutral() {
            return colour == BmsAttributes.DFHNEUTR;
        }

        /**
         * Value equality over all four bytes.
         *
         * @param other the object to compare with
         * @return {@code true} when {@code other} is a quad holding the same four bytes
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FieldAttributes quad)) {
                return false;
            }
            return colour == quad.colour && ps == quad.ps && hilight == quad.hilight
                    && validn == quad.validn;
        }

        /**
         * A hash consistent with {@link #equals(Object)}.
         *
         * @return the hash code
         */
        @Override
        public int hashCode() {
            return Objects.hash(colour, ps, hilight, validn);
        }

        /**
         * The four bytes in hexadecimal, with the colour's mnemonic where it has one.
         *
         * @return for example {@code "{C=0xF2 DFHRED, P=0x00, H=0x00, V=0x00}"}; never {@code null}
         */
        @Override
        public String toString() {
            return "{C=" + BmsAttributes.toHex(colour) + ' ' + BmsAttributes.colourMnemonic(colour)
                    + ", P=" + BmsAttributes.toHex(ps)
                    + ", H=" + BmsAttributes.toHex(hilight)
                    + ", V=" + BmsAttributes.toHex(validn)
                    + '}';
        }
    }


    /**
     * Builds an {@link AccountViewResponse}.
     *
     * <p>The only way to create a payload, so there is exactly one place where a value can be stored and
     * exactly one place where the {@code PIC X} move rule is applied. A fresh builder is in the state
     * {@code MOVE LOW-VALUES TO CACTVWAO} leaves the group in: every field {@code LOW-VALUES} at its
     * declared width, every attribute item {@code LOW-VALUES}, the next-screen triple spaces as
     * {@code INITIALIZE CC-WORK-AREA} leaves it, a fresh work area and an empty commarea.
     *
     * <p>Usable with no Spring context and with no mocking - a plain JUnit 5 test or
     * {@code parity/ParityHarness} constructs one directly (practice <b>B10</b>).
     *
     * <p>{@link #build()} applies {@link FixedWidthCodec#movePicX(String, int)} to every field, because a
     * COBOL {@code MOVE} into an {@code xxxO} receiver pads a short sending value on the right and
     * truncates a long one on the right at the moment of the move. A builder method therefore stores what
     * it is given, and the width is settled once, at {@code build()}.
     *
     * <p>Jackson uses this builder to deserialise the payload, via
     * {@code @JsonDeserialize(builder = Builder.class)} and {@code @JsonPOJOBuilder(withPrefix = "")}: a
     * JSON member named {@code acsadl1} maps to {@link #acsadl1(String)}. That is what keeps the type
     * immutable and round-trippable at the same time.
     *
     * <p>Not thread safe; build on one thread and publish the result.
     */
    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {

        private String trnname;
        private String title01;
        private String curdate;
        private String pgmname;
        private String title02;
        private String curtime;
        private String acctsid;
        private String acsttus;
        private String adtopen;
        private String acrdlim;
        private String aexpdt;
        private String acshlim;
        private String areisdt;
        private String acurbal;
        private String acrcycr;
        private String aaddgrp;
        private String acrcydb;
        private String acstnum;
        private String acstssn;
        private String acstdob;
        private String acstfco;
        private String acsfnam;
        private String acsmnam;
        private String acslnam;
        private String acsadl1;
        private String acsstte;
        private String acsadl2;
        private String acszipc;
        private String acscity;
        private String acsctry;
        private String acsphn1;
        private String acsgovt;
        private String acsphn2;
        private String acseftc;
        private String acspflg;
        private String infomsg;
        private String errmsg;
        private String nextProgram;
        private String nextMapset;
        private String nextMap;
        private CardScreenState cardScreenState;
        private NavigationContext navigationContext;
        private final Map<ScreenField, FieldAttributes> attributes = new EnumMap<>(ScreenField.class);

        /** A builder in the {@code MOVE LOW-VALUES TO CACTVWAO} state. */
        public Builder() {
            for (ScreenField field : ScreenField.values()) {
                value(field, CardScreenState.lowValues(field.length()));
                attributes.put(field, new FieldAttributes());
            }
            this.nextProgram = CardScreenState.spaces(NEXT_PROGRAM_LENGTH);
            this.nextMapset = CardScreenState.spaces(NEXT_MAPSET_LENGTH);
            this.nextMap = CardScreenState.spaces(NEXT_MAP_LENGTH);
            this.cardScreenState = new CardScreenState();
            this.navigationContext = NavigationContext.empty();
        }

        /**
         * {@code TRNNAMEO}.
         *
         * @param trnname the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder trnname(String trnname) {
            this.trnname = orLowValues(trnname, TRNNAME_LENGTH);
            return this;
        }

        /**
         * {@code TITLE01O}.
         *
         * @param title01 the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder title01(String title01) {
            this.title01 = orLowValues(title01, TITLE01_LENGTH);
            return this;
        }

        /**
         * {@code CURDATEO}.
         *
         * @param curdate the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder curdate(String curdate) {
            this.curdate = orLowValues(curdate, CURDATE_LENGTH);
            return this;
        }

        /**
         * {@code PGMNAMEO}.
         *
         * @param pgmname the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder pgmname(String pgmname) {
            this.pgmname = orLowValues(pgmname, PGMNAME_LENGTH);
            return this;
        }

        /**
         * {@code TITLE02O}.
         *
         * @param title02 the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder title02(String title02) {
            this.title02 = orLowValues(title02, TITLE02_LENGTH);
            return this;
        }

        /**
         * {@code CURTIMEO}.
         *
         * @param curtime the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder curtime(String curtime) {
            this.curtime = orLowValues(curtime, CURTIME_LENGTH);
            return this;
        }

        /**
         * {@code ACCTSIDO} - an account identifier, {@code LOW-VALUES}, or
         * {@value AccountViewResponse#BLANK_FIELD_MARKER}.
         *
         * @param acctsid the value; {@code null} means {@code LOW-VALUES}, which is also what
         *                {@code app/cbl/COACTVWC.cbl:466} moves when the filter is blank
         * @return this builder
         */
        public Builder acctsid(String acctsid) {
            this.acctsid = orLowValues(acctsid, ACCTSID_LENGTH);
            return this;
        }

        /**
         * {@code ACSTTUSO}.
         *
         * @param acsttus the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acsttus(String acsttus) {
            this.acsttus = orLowValues(acsttus, ACSTTUS_LENGTH);
            return this;
        }

        /**
         * {@code ADTOPENO}.
         *
         * @param adtopen the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder adtopen(String adtopen) {
            this.adtopen = orLowValues(adtopen, ADTOPEN_LENGTH);
            return this;
        }

        /**
         * {@code ACRDLIMO} as an already-rendered image - the route for {@code LOW-VALUES}.
         *
         * @param acrdlim the image; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acrdlim(String acrdlim) {
            this.acrdlim = orLowValues(acrdlim, ACRDLIM_LENGTH);
            return this;
        }

        /**
         * {@code MOVE ACCT-CREDIT-LIMIT TO ACRDLIMO}, {@code app/cbl/COACTVWC.cbl:477}.
         *
         * @param amount {@code ACCT-CREDIT-LIMIT}, a {@code PIC S9(10)V99} value
         * @return this builder
         * @throws NullPointerException if {@code amount} is {@code null}
         */
        public Builder acrdlimAmount(BigDecimal amount) {
            return acrdlim(editAmount(amount));
        }

        /**
         * {@code AEXPDTO} - fed from {@code ACCT-EXPIRAION-DATE}, spelled as the copybook spells it.
         *
         * @param aexpdt the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder aexpdt(String aexpdt) {
            this.aexpdt = orLowValues(aexpdt, AEXPDT_LENGTH);
            return this;
        }

        /**
         * {@code ACSHLIMO} as an already-rendered image.
         *
         * @param acshlim the image; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acshlim(String acshlim) {
            this.acshlim = orLowValues(acshlim, ACSHLIM_LENGTH);
            return this;
        }

        /**
         * {@code MOVE ACCT-CASH-CREDIT-LIMIT TO ACSHLIMO}, {@code app/cbl/COACTVWC.cbl:479-480}.
         *
         * @param amount {@code ACCT-CASH-CREDIT-LIMIT}, a {@code PIC S9(10)V99} value
         * @return this builder
         * @throws NullPointerException if {@code amount} is {@code null}
         */
        public Builder acshlimAmount(BigDecimal amount) {
            return acshlim(editAmount(amount));
        }

        /**
         * {@code AREISDTO}.
         *
         * @param areisdt the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder areisdt(String areisdt) {
            this.areisdt = orLowValues(areisdt, AREISDT_LENGTH);
            return this;
        }

        /**
         * {@code ACURBALO} as an already-rendered image.
         *
         * @param acurbal the image; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acurbal(String acurbal) {
            this.acurbal = orLowValues(acurbal, ACURBAL_LENGTH);
            return this;
        }

        /**
         * {@code MOVE ACCT-CURR-BAL TO ACURBALO}, {@code app/cbl/COACTVWC.cbl:475}.
         *
         * @param amount {@code ACCT-CURR-BAL}, a {@code PIC S9(10)V99} value
         * @return this builder
         * @throws NullPointerException if {@code amount} is {@code null}
         */
        public Builder acurbalAmount(BigDecimal amount) {
            return acurbal(editAmount(amount));
        }

        /**
         * {@code ACRCYCRO} as an already-rendered image.
         *
         * @param acrcycr the image; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acrcycr(String acrcycr) {
            this.acrcycr = orLowValues(acrcycr, ACRCYCR_LENGTH);
            return this;
        }

        /**
         * {@code MOVE ACCT-CURR-CYC-CREDIT TO ACRCYCRO}, {@code app/cbl/COACTVWC.cbl:482-483}.
         *
         * @param amount {@code ACCT-CURR-CYC-CREDIT}, a {@code PIC S9(10)V99} value
         * @return this builder
         * @throws NullPointerException if {@code amount} is {@code null}
         */
        public Builder acrcycrAmount(BigDecimal amount) {
            return acrcycr(editAmount(amount));
        }

        /**
         * {@code AADDGRPO}.
         *
         * @param aaddgrp the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder aaddgrp(String aaddgrp) {
            this.aaddgrp = orLowValues(aaddgrp, AADDGRP_LENGTH);
            return this;
        }

        /**
         * {@code ACRCYDBO} as an already-rendered image.
         *
         * @param acrcydb the image; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acrcydb(String acrcydb) {
            this.acrcydb = orLowValues(acrcydb, ACRCYDB_LENGTH);
            return this;
        }

        /**
         * {@code MOVE ACCT-CURR-CYC-DEBIT TO ACRCYDBO}, {@code app/cbl/COACTVWC.cbl:485}.
         *
         * @param amount {@code ACCT-CURR-CYC-DEBIT}, a {@code PIC S9(10)V99} value
         * @return this builder
         * @throws NullPointerException if {@code amount} is {@code null}
         */
        public Builder acrcydbAmount(BigDecimal amount) {
            return acrcydb(editAmount(amount));
        }

        /**
         * {@code ACSTNUMO}.
         *
         * @param acstnum the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acstnum(String acstnum) {
            this.acstnum = orLowValues(acstnum, ACSTNUM_LENGTH);
            return this;
        }

        /**
         * {@code ACSTSSNO} as an already-composed image.
         *
         * @param acstssn the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acstssn(String acstssn) {
            this.acstssn = orLowValues(acstssn, ACSTSSN_LENGTH);
            return this;
        }

        /**
         * Reproduces the {@code STRING} at {@code app/cbl/COACTVWC.cbl:496-504} exactly:
         *
         * <pre>{@code
         * STRING CUST-SSN(1:3) '-' CUST-SSN(4:2) '-' CUST-SSN(6:4)
         *     DELIMITED BY SIZE INTO ACSTSSNO OF CACTVWAO
         * END-STRING
         * }</pre>
         *
         * <p>Three subtleties, all reproduced:
         *
         * <ul>
         *   <li>The reference modifications are <strong>1-based</strong>, so {@code (1:3)} is characters
         *       1 to 3, {@code (4:2)} is 4 to 5 and {@code (6:4)} is 6 to 9 - which together consume all
         *       nine characters of {@code CUST-SSN PIC 9(09)} and nothing more.</li>
         *   <li>The composed value is {@value AccountViewResponse#ACSTSSN_STRING_LENGTH} characters into a
         *       {@value AccountViewResponse#ACSTSSN_LENGTH}-byte receiver.</li>
         *   <li>A COBOL {@code STRING} <strong>does not blank the receiver's tail</strong>, so the twelfth
         *       byte keeps whatever it already held. This method therefore preserves the twelfth character
         *       of the value currently in the builder - {@code LOW-VALUES} on a fresh builder, which is
         *       what {@code MOVE LOW-VALUES TO CACTVWAO} at {@code :432} put there. It is emphatically
         *       <em>not</em> space-padded.</li>
         * </ul>
         *
         * @param custSsn {@code CUST-SSN}, exactly 9 characters
         * @return this builder
         * @throws NullPointerException     if {@code custSsn} is {@code null}
         * @throws IllegalArgumentException if {@code custSsn} is not exactly 9 characters, because
         *                                  {@code CUST-SSN(6:4)} would then reference storage the field
         *                                  does not have
         */
        public Builder acstssnFromSsn(String custSsn) {
            Objects.requireNonNull(custSsn, "CUST-SSN is required to compose ACSTSSNO; it is "
                    + "PIC 9(09) in app/cpy/CVCUS01Y.cpy and has no absent state");
            if (custSsn.length() != SSN_LENGTH) {
                throw new IllegalArgumentException("CUST-SSN is PIC 9(09) in app/cpy/CVCUS01Y.cpy, so "
                        + "the STRING at app/cbl/COACTVWC.cbl:496-504 needs exactly " + SSN_LENGTH
                        + " characters to reference (1:3), (4:2) and (6:4); this value is "
                        + custSsn.length());
            }
            String composed = custSsn.substring(0, 3)
                    + SSN_GROUP_SEPARATOR
                    + custSsn.substring(3, 5)
                    + SSN_GROUP_SEPARATOR
                    + custSsn.substring(5, 9);
            // The one byte STRING leaves alone. Read from the value already in the builder, which is what
            // "the receiver's tail is untouched" means.
            char untouchedTail = this.acstssn.charAt(ACSTSSN_STRING_LENGTH);
            this.acstssn = composed + untouchedTail;
            return this;
        }

        /**
         * {@code ACSTDOBO}.
         *
         * @param acstdob the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acstdob(String acstdob) {
            this.acstdob = orLowValues(acstdob, ACSTDOB_LENGTH);
            return this;
        }

        /**
         * {@code ACSTFCOO}.
         *
         * @param acstfco the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acstfco(String acstfco) {
            this.acstfco = orLowValues(acstfco, ACSTFCO_LENGTH);
            return this;
        }

        /**
         * {@code ACSFNAMO}.
         *
         * @param acsfnam the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acsfnam(String acsfnam) {
            this.acsfnam = orLowValues(acsfnam, ACSFNAM_LENGTH);
            return this;
        }

        /**
         * {@code ACSMNAMO}.
         *
         * @param acsmnam the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acsmnam(String acsmnam) {
            this.acsmnam = orLowValues(acsmnam, ACSMNAM_LENGTH);
            return this;
        }

        /**
         * {@code ACSLNAMO}.
         *
         * @param acslnam the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acslnam(String acslnam) {
            this.acslnam = orLowValues(acslnam, ACSLNAM_LENGTH);
            return this;
        }

        /**
         * {@code ACSADL1O}.
         *
         * @param acsadl1 the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acsadl1(String acsadl1) {
            this.acsadl1 = orLowValues(acsadl1, ACSADL1_LENGTH);
            return this;
        }

        /**
         * {@code ACSSTTEO}.
         *
         * @param acsstte the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acsstte(String acsstte) {
            this.acsstte = orLowValues(acsstte, ACSSTTE_LENGTH);
            return this;
        }

        /**
         * {@code ACSADL2O}.
         *
         * @param acsadl2 the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acsadl2(String acsadl2) {
            this.acsadl2 = orLowValues(acsadl2, ACSADL2_LENGTH);
            return this;
        }

        /**
         * {@code ACSZIPCO} - narrowed from {@code CUST-ADDR-ZIP PIC X(10)} at {@link #build()}.
         *
         * @param acszipc the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acszipc(String acszipc) {
            this.acszipc = orLowValues(acszipc, ACSZIPC_LENGTH);
            return this;
        }

        /**
         * {@code ACSCITYO} - fed from {@code CUST-ADDR-LINE-3}, not from a city field.
         *
         * @param acscity the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acscity(String acscity) {
            this.acscity = orLowValues(acscity, ACSCITY_LENGTH);
            return this;
        }

        /**
         * {@code ACSCTRYO}.
         *
         * @param acsctry the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acsctry(String acsctry) {
            this.acsctry = orLowValues(acsctry, ACSCTRY_LENGTH);
            return this;
        }

        /**
         * {@code ACSPHN1O} - narrowed from {@code CUST-PHONE-NUM-1 PIC X(15)} at {@link #build()}.
         *
         * @param acsphn1 the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acsphn1(String acsphn1) {
            this.acsphn1 = orLowValues(acsphn1, ACSPHN1_LENGTH);
            return this;
        }

        /**
         * {@code ACSGOVTO}.
         *
         * @param acsgovt the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acsgovt(String acsgovt) {
            this.acsgovt = orLowValues(acsgovt, ACSGOVT_LENGTH);
            return this;
        }

        /**
         * {@code ACSPHN2O} - narrowed from {@code CUST-PHONE-NUM-2 PIC X(15)} at {@link #build()}.
         *
         * @param acsphn2 the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acsphn2(String acsphn2) {
            this.acsphn2 = orLowValues(acsphn2, ACSPHN2_LENGTH);
            return this;
        }

        /**
         * {@code ACSEFTCO}.
         *
         * @param acseftc the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acseftc(String acseftc) {
            this.acseftc = orLowValues(acseftc, ACSEFTC_LENGTH);
            return this;
        }

        /**
         * {@code ACSPFLGO}.
         *
         * @param acspflg the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acspflg(String acspflg) {
            this.acspflg = orLowValues(acspflg, ACSPFLG_LENGTH);
            return this;
        }

        /**
         * {@code INFOMSGO} - widened by {@value AccountViewResponse#INFOMSG_INFO_MESSAGE_PADDING} spaces
         * when fed from {@code WS-INFO-MSG PIC X(40)}.
         *
         * @param infomsg the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder infomsg(String infomsg) {
            this.infomsg = orLowValues(infomsg, INFOMSG_LENGTH);
            return this;
        }

        /**
         * {@code ERRMSGO} - widened by {@value AccountViewResponse#ERRMSG_RETURN_MESSAGE_PADDING} spaces
         * when fed from {@code WS-RETURN-MSG PIC X(75)}.
         *
         * @param errmsg the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder errmsg(String errmsg) {
            this.errmsg = orLowValues(errmsg, ERRMSG_LENGTH);
            return this;
        }

        /**
         * The {@code XCTL} target program.
         *
         * @param nextProgram the program name; {@code null} means spaces
         * @return this builder
         */
        public Builder nextProgram(String nextProgram) {
            this.nextProgram = orSpaces(nextProgram, NEXT_PROGRAM_LENGTH);
            return this;
        }

        /**
         * The next screen's mapset.
         *
         * @param nextMapset the mapset name; {@code null} means spaces
         * @return this builder
         */
        public Builder nextMapset(String nextMapset) {
            this.nextMapset = orSpaces(nextMapset, NEXT_MAPSET_LENGTH);
            return this;
        }

        /**
         * The next screen's map.
         *
         * @param nextMap the map name; {@code null} means spaces
         * @return this builder
         */
        public Builder nextMap(String nextMap) {
            this.nextMap = orSpaces(nextMap, NEXT_MAP_LENGTH);
            return this;
        }

        /**
         * The {@code CVCRD01Y} work area, copied on store.
         *
         * @param cardScreenState the work area
         * @return this builder
         * @throws NullPointerException if {@code cardScreenState} is {@code null}; use
         *                              {@code new CardScreenState()} for a freshly initialised area
         */
        public Builder cardScreenState(CardScreenState cardScreenState) {
            Objects.requireNonNull(cardScreenState, "A work area is required; use new CardScreenState() "
                    + "for the state INITIALIZE CC-WORK-AREA produces");
            this.cardScreenState = new CardScreenState(cardScreenState);
            return this;
        }

        /**
         * The {@code CARDDEMO-COMMAREA}.
         *
         * @param navigationContext the commarea
         * @return this builder
         * @throws NullPointerException if {@code navigationContext} is {@code null}; use
         *                              {@link NavigationContext#empty()} for the {@code EIBCALEN = 0} case
         */
        public Builder navigationContext(NavigationContext navigationContext) {
            Objects.requireNonNull(navigationContext, "A commarea is required; use "
                    + "NavigationContext.empty() when no communication area was passed");
            this.navigationContext = navigationContext;
            return this;
        }

        /**
         * Reproduces {@code app/cbl/COACTVWC.cbl:436-437}: {@code MOVE CCDA-TITLE01 TO TITLE01O} and
         * {@code MOVE CCDA-TITLE02 TO TITLE02O}.
         *
         * <p>The literals come from {@link ScreenTitles}, which transcribes {@code app/cpy/COTTL01Y.cpy}
         * character for character including the spaces that make each exactly
         * {@value AccountViewResponse#TITLE01_LENGTH} wide. They are not restated here, so there is one
         * place in the system where a heading's bytes are defined.
         *
         * @return this builder
         */
        public Builder screenTitles() {
            return title01(ScreenTitles.CCDA_TITLE01).title02(ScreenTitles.CCDA_TITLE02);
        }

        /**
         * Reproduces {@code app/cbl/COACTVWC.cbl:438-439}: {@code MOVE LIT-THISTRANID TO TRNNAMEO} and
         * {@code MOVE LIT-THISPGM TO PGMNAMEO}.
         *
         * @return this builder
         */
        public Builder screenIdentity() {
            return trnname(THIS_TRANID).pgmname(THIS_PROGRAM);
        }

        /**
         * Reproduces {@code app/cbl/COACTVWC.cbl:447} and {@code :453}:
         * {@code MOVE WS-CURDATE-MM-DD-YY TO CURDATEO} and {@code MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO}.
         *
         * <p>Both values are read from the supplied header rather than from a clock, so a parity case can
         * fix the instant and get a byte-identical screen. Each is already eight characters -
         * {@code mm/dd/yy} and {@code hh:mm:ss} - which is exactly what the two receivers declare.
         *
         * @param dateHeader the {@code CSDAT01Y} header {@code COACTVWC} copies at
         *                   {@code app/cbl/COACTVWC.cbl:164}
         * @return this builder
         * @throws NullPointerException if {@code dateHeader} is {@code null}
         */
        public Builder dateHeader(DateHeader dateHeader) {
            Objects.requireNonNull(dateHeader, "A date header is required; this payload never reads a "
                    + "clock of its own, so the instant must be supplied by the caller");
            return curdate(dateHeader.wsCurdateMmDdYy()).curtime(dateHeader.wsCurtimeHhMmSs());
        }

        /**
         * Reproduces {@code app/cbl/COACTVWC.cbl:906-907}: {@code MOVE LIT-THISMAPSET TO CCARD-NEXT-MAPSET}
         * and {@code MOVE LIT-THISMAP TO CCARD-NEXT-MAP}, which {@code 1400-SEND-SCREEN} performs so the
         * screen re-displays itself.
         *
         * <p>{@link #THIS_MAPSET} is {@code PIC X(8)} and the receiver {@code PIC X(7)}, so
         * {@link #build()} discards its trailing space - which is what the program does.
         *
         * @return this builder
         */
        public Builder thisScreenAsNextTarget() {
            return nextProgram(THIS_PROGRAM).nextMapset(THIS_MAPSET).nextMap(MAP_NAME);
        }

        /**
         * The whole next-screen triple, for the {@code XCTL} path at {@code app/cbl/COACTVWC.cbl:349}.
         *
         * @param nextProgram the target program; {@code null} means spaces
         * @param nextMapset  the target mapset; {@code null} means spaces
         * @param nextMap     the target map; {@code null} means spaces
         * @return this builder
         */
        public Builder nextTarget(String nextProgram, String nextMapset, String nextMap) {
            return nextProgram(nextProgram).nextMapset(nextMapset).nextMap(nextMap);
        }

        /**
         * Stores one field addressed by its {@link ScreenField}.
         *
         * @param field which field
         * @param value the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public Builder value(ScreenField field, String value) {
            Objects.requireNonNull(field, "A ScreenField is required to store a field value");
            return switch (field) {
                case TRNNAME -> trnname(value);
                case TITLE01 -> title01(value);
                case CURDATE -> curdate(value);
                case PGMNAME -> pgmname(value);
                case TITLE02 -> title02(value);
                case CURTIME -> curtime(value);
                case ACCTSID -> acctsid(value);
                case ACSTTUS -> acsttus(value);
                case ADTOPEN -> adtopen(value);
                case ACRDLIM -> acrdlim(value);
                case AEXPDT -> aexpdt(value);
                case ACSHLIM -> acshlim(value);
                case AREISDT -> areisdt(value);
                case ACURBAL -> acurbal(value);
                case ACRCYCR -> acrcycr(value);
                case AADDGRP -> aaddgrp(value);
                case ACRCYDB -> acrcydb(value);
                case ACSTNUM -> acstnum(value);
                case ACSTSSN -> acstssn(value);
                case ACSTDOB -> acstdob(value);
                case ACSTFCO -> acstfco(value);
                case ACSFNAM -> acsfnam(value);
                case ACSMNAM -> acsmnam(value);
                case ACSLNAM -> acslnam(value);
                case ACSADL1 -> acsadl1(value);
                case ACSSTTE -> acsstte(value);
                case ACSADL2 -> acsadl2(value);
                case ACSZIPC -> acszipc(value);
                case ACSCITY -> acscity(value);
                case ACSCTRY -> acsctry(value);
                case ACSPHN1 -> acsphn1(value);
                case ACSGOVT -> acsgovt(value);
                case ACSPHN2 -> acsphn2(value);
                case ACSEFTC -> acseftc(value);
                case ACSPFLG -> acspflg(value);
                case INFOMSG -> infomsg(value);
                case ERRMSG -> errmsg(value);
            };
        }

        /**
         * One field's attribute quad, writable before the payload is built.
         *
         * <p>Excluded from JSON deserialisation: the attribute items are metadata and never arrive on the
         * wire, and this accessor returns a holder rather than the builder so it is not a mutator.
         *
         * @param field which field's quad
         * @return the live holder; never {@code null}
         * @throws NullPointerException if {@code field} is {@code null}
         */
        @JsonIgnore
        public FieldAttributes attributes(ScreenField field) {
            Objects.requireNonNull(field, "A ScreenField is required to read an attribute quad");
            return attributes.get(field);
        }

        /**
         * Builds the payload, applying the {@code PIC X} move rule to every field and to the next-screen
         * triple.
         *
         * <p>This is the one place a width is settled. {@link FixedWidthCodec#movePicX(String, int)}
         * pads on the right when the value is short and truncates on the right when it is long, which is
         * the COBOL rule for an alphanumeric receiver; it is not reimplemented here, so there is exactly
         * one place in the system where that rule can be wrong.
         *
         * @return the payload; never {@code null}
         */
        public AccountViewResponse build() {
            for (ScreenField field : ScreenField.values()) {
                value(field, PIC_X_CODEC.movePicX(valueOf(field), field.length()));
            }
            this.nextProgram = PIC_X_CODEC.movePicX(nextProgram, NEXT_PROGRAM_LENGTH);
            this.nextMapset = PIC_X_CODEC.movePicX(nextMapset, NEXT_MAPSET_LENGTH);
            this.nextMap = PIC_X_CODEC.movePicX(nextMap, NEXT_MAP_LENGTH);
            return new AccountViewResponse(this);
        }

        /**
         * The value currently held for one field, before normalisation.
         *
         * @param field which field
         * @return the stored value; never {@code null}
         */
        private String valueOf(ScreenField field) {
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
         * Substitutes {@code LOW-VALUES} for an absent value, at the field's declared width.
         *
         * <p>{@code LOW-VALUES} rather than spaces because this is an <em>output</em> group and
         * {@code MOVE LOW-VALUES TO CACTVWAO} at {@code app/cbl/COACTVWC.cbl:432} is its initial state: a
         * field the program never writes holds {@code X'00'}, not blanks. A COBOL record has no null, so
         * absence has to become one of the two, and this is the one the program produces.
         *
         * <p>The substitution loses the distinction between "the caller omitted this field" and "the
         * caller sent {@code LOW-VALUES}" - which the map loses too, since both are the same twelve or
         * forty-five bytes on the wire.
         *
         * @param value the value supplied, possibly {@code null}
         * @param width the field's declared width
         * @return {@code value} unchanged, or {@code LOW-VALUES} of the declared width when it is
         *         {@code null}
         */
        private static String orLowValues(String value, int width) {
            return value == null ? CardScreenState.lowValues(width) : value;
        }

        /**
         * Substitutes spaces for an absent next-screen value, at its declared width.
         *
         * <p>Spaces rather than {@code LOW-VALUES} because the triple lives in the {@code CVCRD01Y} work
         * area, which {@code INITIALIZE CC-WORK-AREA} leaves blank, not in the map.
         *
         * @param value the value supplied, possibly {@code null}
         * @param width the item's declared width
         * @return {@code value} unchanged, or spaces of the declared width when it is {@code null}
         */
        private static String orSpaces(String value, int width) {
            return value == null ? CardScreenState.spaces(width) : value;
        }
    }

}
