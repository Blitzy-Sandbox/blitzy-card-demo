package com.vsergeychik.carddemo.account.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.AcctSnapshot;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.ChangeAction;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.CommArea;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.CustSnapshot;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.DetailGroup;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.Details;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import jakarta.validation.constraints.Size;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The outbound REST payload of {@code PUT /api/accounts/{acctId}} - CSD transaction {@code CAUP}.
 *
 * <h2>What this type is a projection of</h2>
 * <table border="1">
 *   <caption>Provenance</caption>
 *   <tr><th>Aspect</th><th>Authority</th></tr>
 *   <tr><td>Behaviour</td><td>{@code app/cbl/COACTUPC.cbl} - 4,236 lines, the largest program in the
 *       migration</td></tr>
 *   <tr><td>Screen geometry</td><td>{@code app/bms/COACTUP.bms} - 128 {@code DFHMDF} entries, of which
 *       exactly {@value #FIELD_COUNT} are name-labelled</td></tr>
 *   <tr><td>Storage layout</td><td>{@code app/cpy-bms/COACTUP.CPY} - group
 *       {@code 01 CACTUPAO REDEFINES CACTUPAI.} opens at line 343 and runs to line 668</td></tr>
 *   <tr><td>Conversation state</td><td>{@code app/cpy/COCOM01Y.cpy} (160 bytes) and
 *       {@code WS-THIS-PROGCOMMAREA} at {@code app/cbl/COACTUPC.cbl:652} (873 bytes)</td></tr>
 *   <tr><td>Error highlighting</td><td>{@code app/cpy/CSSETATY.cpy}, of which {@code COACTUPC} is the
 *       only consumer, with 39 textual occurrences</td></tr>
 * </table>
 *
 * <p>There is <strong>no design system, no component library, no design token and no Figma attachment
 * anywhere in this project</strong>. The BMS layer alone is the presentation contract, and it is a
 * byte-level one: position, length, attribute and colour are all declared explicitly, so every payload
 * field below traces to exactly one {@code DFHMDF} definition and takes its width from exactly one
 * symbolic-map {@code PICTURE} clause. That is what gate <strong>G9</strong> asks for, and
 * {@link ScreenField} carries the trace so it can be checked without arithmetic.
 *
 * <h2>Note the group-name quirk</h2>
 * BMS truncates the mapset name to seven characters and appends the direction letter, so the mapset
 * {@code COACTUP} generates {@code CACTUPAI} and {@code CACTUPAO} - the leading {@code O} of
 * {@code COACTUP} is <em>dropped</em>, and the {@code A} comes from the {@code CACTUPA DFHMDI} name.
 * Neither group is called {@code COACTUPO}.
 *
 * <h2>Geometry, written out</h2>
 * The output group's per-field stride is {@code 02 FILLER PICTURE X(3)}, then the four extended-attribute
 * items {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV} at one byte each, then
 * {@code 02 xxxO PIC X(n)} - which is {@value #FIELD_OVERHEAD} bytes of overhead plus {@code n} bytes of
 * data. That is byte-for-byte the stride of the input group, whose {@code xxxL COMP PIC S9(4)} halfword
 * plus {@code xxxF PICTURE X} plus {@code 02 FILLER PICTURE X(4)} also sum to {@value #FIELD_OVERHEAD}.
 * The identical stride is precisely why {@code CACTUPAO REDEFINES CACTUPAI} is legal COBOL, and it makes
 * the data offset of every field the same on both sides - a free cross-check against
 * {@link AccountUpdateRequest}.
 *
 * <pre>
 *   {@value #TIOAPFX_LENGTH} (TIOAPFX)  +  {@value #FIELD_COUNT} x {@value #FIELD_OVERHEAD} (overhead)
 *       +  {@value #PAYLOAD_LENGTH} (data)  =  {@value #GROUP_LENGTH} bytes
 * </pre>
 *
 * <h2>Every one of the {@value #FIELD_COUNT} fields is {@code PIC X(n)}</h2>
 * {@code app/bms/COACTUP.bms} declares <strong>zero</strong> {@code PICIN} and <strong>zero</strong>
 * {@code PICOUT} clauses, so there is no numeric item and no numeric-edited item anywhere on this screen,
 * on either side of the {@code REDEFINES}. In particular the five monetary fields - {@code ACRDLIM},
 * {@code ACSHLIM}, {@code ACURBAL}, {@code ACRCYCR} and {@code ACRCYDB} - are plain {@code X(15)}
 * character strings here, because {@code COACTUP} is an editable form rather than a display. The sibling
 * view screen's {@code +ZZZ,ZZZ,ZZZ.99} numeric-edited items and its {@code ACCTSID} {@code PICTURE}
 * asymmetry belong to {@link AccountViewResponse} and are deliberately not reproduced.
 *
 * <p>What <em>is</em> true, and worth recording so nobody rediscovers it: the fifteen characters
 * {@code COACTUPC} writes into those five fields are composed in working storage by
 * {@code WS-EDIT-CURRENCY-9-2-F PIC +ZZZ,ZZZ,ZZZ.99} [{@code app/cbl/COACTUPC.cbl:371}] and then moved
 * across as {@code PIC X} [{@code :2797-2811} for the OLD snapshot, {@code :2875-2908} for the NEW one].
 * Composing that mask is a projection decision and therefore
 * {@code AccountUpdateController}/{@code AccountUpdateService}' work, not this payload's - gate
 * <strong>G51</strong>. This type carries the fifteen characters and does not format them.
 *
 * <h2>The baseline of this group is LOW-VALUES, not spaces</h2>
 * {@code 3100-SCREEN-INIT} opens with {@code MOVE LOW-VALUES TO CACTUPAO}
 * [{@code app/cbl/COACTUPC.cbl:2668}], so every one of the {@value #GROUP_LENGTH} bytes - the
 * {@code TIOAPFX} prefix, all {@value #FIELD_COUNT} three-byte {@code FILLER} spans, all four attribute
 * items per field and every field the program does not go on to populate - starts as binary zero. That is
 * why {@link #initial()} sets each field to {@link #lowValues(int)} at its declared width rather than to
 * spaces, and why {@link #toGroupImage(FixedWidthCodec)} lays a LOW-VALUES baseline down first. See the
 * divergence register below for how this squares with gate <strong>G21</strong>.
 *
 * <h2>A field may legitimately hold exactly {@code "*"}</h2>
 * {@code CSSETATY} writes into <strong>two</strong> destinations, not one:
 *
 * <pre>
 *   IF (FLG-(TESTVAR1)-NOT-OK OR FLG-(TESTVAR1)-BLANK)
 *   AND CDEMO-PGM-REENTER
 *       MOVE DFHRED  TO (SCRNVAR2)C OF (MAPNAME3)O
 *       IF  FLG-(TESTVAR1)-BLANK
 *           MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O
 *       END-IF
 *   END-IF
 * </pre>
 *
 * {@code DFHRED} goes into the {@code xxxC} colour byte, and a bare asterisk goes into the {@code xxxO}
 * <em>payload field itself</em>. Two consequences bind every consumer of this type:
 *
 * <ul>
 *   <li>the attribute quad has to be writable after the payload is built - see
 *       {@link #attributes(ScreenField)}, which is this type's one deliberate departure from
 *       immutability;</li>
 *   <li><strong>no constraint may reject {@code "*"}</strong>, an all-spaces value or a LOW-VALUES value
 *       on any field. Only {@link Size} appears below; there is no {@code @Pattern}, no {@code @Digits}
 *       and no custom validator, and no consumer may assume a field holds real data.</li>
 * </ul>
 *
 * <p>The highlight <em>decision</em> is not taken here. {@link FieldAttributeSetter} owns it and takes the
 * REENTER state as an explicit boolean; this type only supplies the two destinations that decision writes
 * to, through {@link #applyHighlight(ScreenField, FieldValidationState, boolean)}. The highlight applies
 * <strong>only</strong> in REENTER context, and both the ENTER and the REENTER path are exercisable
 * through this response - gate <strong>G38</strong>.
 *
 * <h2>Statelessness</h2>
 * {@code app/cbl/COACTUPC.cbl:956-958} issues {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)}. Under
 * rule <strong>R6</strong> and gates <strong>G37</strong> and <strong>G40</strong> that becomes response
 * data the client resolves: {@link #getNextProgram()}, {@link #getNextMapset()} and
 * {@link #getNextMap()}. All conversation state travels in the payload - the 160-byte
 * {@link NavigationContext}, the 213-byte {@link CardScreenState} and the 873-byte
 * {@link CommArea} - and none of it is held server-side. There is no {@code HttpSession}, no
 * {@code @SessionAttributes}, no session scope, no static holder and no cache anywhere in this file
 * (gates <strong>G37</strong> and <strong>G53</strong>).
 *
 * <h2>What this type does not do</h2>
 * It does not perform the {@code 9700-CHECK-CHANGE-IN-REC} comparison
 * [{@code app/cbl/COACTUPC.cbl:4109-4193}]; it transports the before-image and reports the outcome state
 * through {@link CommArea#changeAction()}. There is no version column, no optimistic-locking annotation
 * and no {@code @Entity}, {@code @Table}, {@code @Id} or DDL of any kind - gates <strong>G43</strong> and
 * <strong>G44</strong>.
 *
 * <h2>Divergences recorded rather than silently corrected (practice B4)</h2>
 * <ol>
 *   <li>The Agent Action Plan section 0.4.4 names the optimistic-concurrency paragraph
 *       {@code 9300-CHECK-CHANGE-IN-REC}. The label {@code COACTUPC} actually declares is
 *       <strong>{@code 9700-CHECK-CHANGE-IN-REC}</strong>, at {@code app/cbl/COACTUPC.cbl:4109}, spanning
 *       {@code :4109-:4193}. {@code 9300} is {@code COCRDUPC}'s label for the same idea.</li>
 *   <li>The Agent Action Plan section 0.6.2 states that every mapset declares
 *       {@code DFHMSD CTRL=(ALARM,FREEKB) EXTATT=YES}. {@code app/bms/COACTUP.bms:20-28} actually
 *       declares {@code COACTUP DFHMSD LANG=COBOL, MODE=INOUT, STORAGE=AUTO, TIOAPFX=YES,
 *       TYPE=&amp;&amp;SYSPARM} with no {@code CTRL} and no {@code EXTATT}, followed by
 *       {@code CACTUPA DFHMDI CTRL=(FREEKB), DSATTS=(COLOR,HILIGHT,PS,VALIDN),
 *       MAPATTS=(COLOR,HILIGHT,PS,VALIDN), SIZE=(24,80)}. The attribute quad therefore comes from
 *       {@code DSATTS}/{@code MAPATTS}, not from {@code EXTATT}.</li>
 *   <li>This file's own specification records {@code 88 ACUP-CHANGES-MADE} as covering two values,
 *       {@code 'E'} and {@code 'N'}. The source at {@code app/cbl/COACTUPC.cbl:660-662} covers
 *       <strong>five</strong>: {@code VALUES 'E', 'N', 'C', 'L', 'F'}. Rule <strong>R1</strong> takes
 *       behaviour from the source, so the five-value grouping already carried by
 *       {@link ChangeAction#CHANGES_MADE_VALUES} is the one honoured. The narrower reading would have
 *       made {@link #isChangesMade()} false for the completed, lock-error and failed states, and
 *       {@code 3390-SETUP-INFOMSG-ATTRS} at {@code :3573-3576} depends on it being true for them.</li>
 *   <li>Gate <strong>G21</strong> asks that {@code FILLER} spans be present and space-filled. Every
 *       {@code FILLER} of this group <em>is</em> present as a first-class declared span in
 *       {@link #OUTPUT_GROUP_LAYOUT} - which is what makes the {@value #GROUP_LENGTH}-byte width
 *       verifiable, and what the gate is protecting - but the fill byte is LOW-VALUES rather than a
 *       space, because {@code app/cbl/COACTUPC.cbl:2668} says {@code MOVE LOW-VALUES TO CACTUPAO}. The
 *       space rule of Agent Action Plan section 0.3.7 governs {@code PIC X} {@code FILLER} in stored
 *       <em>data records</em>; a BMS output group is initialised by its own program, and that program
 *       initialises it to binary zero.</li>
 * </ol>
 *
 * <h2>Immutability, and the one exception</h2>
 * The {@value #FIELD_COUNT} field values and all three state carriers are immutable: build with
 * {@link #builder()}, derive with {@link #withValue(ScreenField, String)} and the other {@code withX}
 * methods. The single exception is the attribute quad reached through {@link #attributes(ScreenField)},
 * which is mutable in place because {@code 3300-SETUP-SCREEN-ATTRS} and
 * {@code 3390-SETUP-INFOMSG-ATTRS} write attribute bytes <em>after</em> the map has been populated. No
 * static field of this class is non-final and no collection it hands out is modifiable (practice
 * <strong>B9</strong>). Every instance is constructible in a plain JUnit 5 test and by
 * {@code parity/ParityHarness} with no Spring context at all (practice <strong>B10</strong>).
 *
 * <h2>Nothing is masked</h2>
 * This payload carries {@code ACTSSN1}/{@code ACTSSN2}/{@code ACTSSN3},
 * {@code DOBYEAR}/{@code DOBMON}/{@code DOBDAY} and {@code ACSGOVT}, and the before- and after-images
 * carry the same data again. {@code COACTUPC} puts all of it on a 3270 in the clear. There is therefore
 * no {@code @JsonIgnore} on any payload field, no masking, no redaction, no serialisation filter and no
 * hiding {@code toString()}: withholding any of it would be an unrequested behaviour change and a parity
 * violation (practice <strong>B6</strong>). Equally, nothing is weakened. The {@code @JsonIgnore}
 * annotations that do appear are on the attribute quad and on derived views, which are metadata rather
 * than payload.
 *
 * @see AccountUpdateRequest
 * @see ScreenField
 * @see FieldAttributes
 * @see Builder
 * @see FieldAttributeSetter
 */
@JsonDeserialize(builder = AccountUpdateResponse.Builder.class)
public final class AccountUpdateResponse {

    // =================================================================================================
    // Screen identity. Transcribed from app/cbl/COACTUPC.cbl:533-540 including the trailing space
    // LIT-THISMAPSET carries, because that space is exactly what an X(8) to X(7) move discards.
    // =================================================================================================

    /** {@code LIT-THISPGM PIC X(8) VALUE 'COACTUPC'}, {@code app/cbl/COACTUPC.cbl:533-534}. */
    public static final String THIS_PROGRAM = "COACTUPC";

    /** {@code LIT-THISTRANID PIC X(4) VALUE 'CAUP'}, {@code app/cbl/COACTUPC.cbl:535-536}. */
    public static final String THIS_TRANSACTION = "CAUP";

    /**
     * {@code LIT-THISMAPSET PIC X(8) VALUE 'COACTUP '},
     * {@code app/cbl/COACTUPC.cbl:537-538} - eight characters, the eighth a space.
     *
     * <p>{@code :949} moves it into {@code CDEMO-LAST-MAPSET}, which {@code COCOM01Y} declares
     * {@code PIC X(7)}, so the trailing space is discarded on the way. {@link #MAPSET_NAME} is the
     * seven-character form that actually arrives.
     */
    public static final String THIS_MAPSET_LITERAL = "COACTUP ";

    /** The mapset as {@code app/bms/COACTUP.bms} names it - seven characters, no trailing space. */
    public static final String MAPSET_NAME = "COACTUP";

    /** {@code LIT-THISMAP PIC X(7) VALUE 'CACTUPA'}, {@code app/cbl/COACTUPC.cbl:539-540}. */
    public static final String MAP_NAME = "CACTUPA";

    /** The symbolic input group, {@code app/cpy-bms/COACTUP.CPY:23}. Not this type's concern. */
    public static final String INPUT_GROUP_NAME = "CACTUPAI";

    /**
     * The symbolic output group this type projects, {@code app/cpy-bms/COACTUP.CPY:343}.
     *
     * <p>{@code 3400-SEND-SCREEN} sends {@code FROM(CACTUPAO)} at {@code app/cbl/COACTUPC.cbl:3596}.
     */
    public static final String OUTPUT_GROUP_NAME = "CACTUPAO";

    /** {@code CACTUPA DFHMDI SIZE=(24,80)} - rows. */
    public static final int SCREEN_ROWS = 24;

    /** {@code CACTUPA DFHMDI SIZE=(24,80)} - columns. */
    public static final int SCREEN_COLUMNS = 80;

    /**
     * Every {@code DFHMDF} entry in {@code app/bms/COACTUP.bms}, labelled or not.
     *
     * <p>{@value #FIELD_COUNT} carry a name and appear in {@link ScreenField}; the remaining 74 are
     * screen literals - {@code INITIAL='Tran:'} and the like - which BMS paints but which generate no
     * symbolic-map item and hold no value this type could carry.
     */
    public static final int DFHMDF_ENTRY_COUNT = 128;

    // =================================================================================================
    // The 54 declared widths, in symbolic-map order. Each is the xxxO item's PICTURE length in
    // app/cpy-bms/COACTUP.CPY, which agrees with its DFHMDF LENGTH= in app/bms/COACTUP.bms at every
    // one of the 54 fields - there is not one disagreement in the source. Named constants rather than
    // literals so that a width is stated once and referenced everywhere (practice B8), and identical in
    // name and value to AccountUpdateRequest's so the pair diffs cleanly.
    // =================================================================================================

    /** {@code TRNNAMEO PIC X(4)}, {@code app/cpy-bms/COACTUP.CPY:350}. */
    public static final int TRNNAME_LENGTH = 4;

    /** {@code TITLE01O PIC X(40)}, {@code app/cpy-bms/COACTUP.CPY:356}. */
    public static final int TITLE01_LENGTH = 40;

    /** {@code CURDATEO PIC X(8)}, {@code app/cpy-bms/COACTUP.CPY:362}. */
    public static final int CURDATE_LENGTH = 8;

    /** {@code PGMNAMEO PIC X(8)}, {@code app/cpy-bms/COACTUP.CPY:368}. */
    public static final int PGMNAME_LENGTH = 8;

    /** {@code TITLE02O PIC X(40)}, {@code app/cpy-bms/COACTUP.CPY:374}. */
    public static final int TITLE02_LENGTH = 40;

    /** {@code CURTIMEO PIC X(8)}, {@code app/cpy-bms/COACTUP.CPY:380}. */
    public static final int CURTIME_LENGTH = 8;

    /**
     * {@code ACCTSIDO PIC X(11)}, {@code app/cpy-bms/COACTUP.CPY:386}.
     *
     * <p>Eleven characters on <strong>both</strong> sides of this mapset, so - unlike the view screen -
     * there is no {@code PICTURE} asymmetry to reproduce and the field is a plain {@link String}.
     */
    public static final int ACCTSID_LENGTH = 11;

    /** {@code ACSTTUSO PIC X(1)}, {@code app/cpy-bms/COACTUP.CPY:392}. */
    public static final int ACSTTUS_LENGTH = 1;

    /** {@code OPNYEARO PIC X(4)}, {@code app/cpy-bms/COACTUP.CPY:398}. */
    public static final int OPNYEAR_LENGTH = 4;

    /** {@code OPNMONO PIC X(2)}, {@code app/cpy-bms/COACTUP.CPY:404}. */
    public static final int OPNMON_LENGTH = 2;

    /** {@code OPNDAYO PIC X(2)}, {@code app/cpy-bms/COACTUP.CPY:410}. */
    public static final int OPNDAY_LENGTH = 2;

    /** {@code ACRDLIMO PIC X(15)}, {@code app/cpy-bms/COACTUP.CPY:416}. */
    public static final int ACRDLIM_LENGTH = 15;

    /** {@code EXPYEARO PIC X(4)}, {@code app/cpy-bms/COACTUP.CPY:422}. */
    public static final int EXPYEAR_LENGTH = 4;

    /** {@code EXPMONO PIC X(2)}, {@code app/cpy-bms/COACTUP.CPY:428}. */
    public static final int EXPMON_LENGTH = 2;

    /** {@code EXPDAYO PIC X(2)}, {@code app/cpy-bms/COACTUP.CPY:434}. */
    public static final int EXPDAY_LENGTH = 2;

    /** {@code ACSHLIMO PIC X(15)}, {@code app/cpy-bms/COACTUP.CPY:440}. */
    public static final int ACSHLIM_LENGTH = 15;

    /** {@code RISYEARO PIC X(4)}, {@code app/cpy-bms/COACTUP.CPY:446}. */
    public static final int RISYEAR_LENGTH = 4;

    /** {@code RISMONO PIC X(2)}, {@code app/cpy-bms/COACTUP.CPY:452}. */
    public static final int RISMON_LENGTH = 2;

    /** {@code RISDAYO PIC X(2)}, {@code app/cpy-bms/COACTUP.CPY:458}. */
    public static final int RISDAY_LENGTH = 2;

    /** {@code ACURBALO PIC X(15)}, {@code app/cpy-bms/COACTUP.CPY:464}. */
    public static final int ACURBAL_LENGTH = 15;

    /** {@code ACRCYCRO PIC X(15)}, {@code app/cpy-bms/COACTUP.CPY:470}. */
    public static final int ACRCYCR_LENGTH = 15;

    /** {@code AADDGRPO PIC X(10)}, {@code app/cpy-bms/COACTUP.CPY:476}. */
    public static final int AADDGRP_LENGTH = 10;

    /** {@code ACRCYDBO PIC X(15)}, {@code app/cpy-bms/COACTUP.CPY:482}. */
    public static final int ACRCYDB_LENGTH = 15;

    /** {@code ACSTNUMO PIC X(9)}, {@code app/cpy-bms/COACTUP.CPY:488}. */
    public static final int ACSTNUM_LENGTH = 9;

    /**
     * {@code ACTSSN1O PIC X(3)}, {@code app/cpy-bms/COACTUP.CPY:494}.
     *
     * <p>The first of the three social-security parts. They stay three separate fields: merging them
     * would breach gate <strong>G9</strong> and break {@code 9700-CHECK-CHANGE-IN-REC}, which compares
     * part by part.
     */
    public static final int ACTSSN1_LENGTH = 3;

    /** {@code ACTSSN2O PIC X(2)}, {@code app/cpy-bms/COACTUP.CPY:500}. */
    public static final int ACTSSN2_LENGTH = 2;

    /** {@code ACTSSN3O PIC X(4)}, {@code app/cpy-bms/COACTUP.CPY:506}. */
    public static final int ACTSSN3_LENGTH = 4;

    /** {@code DOBYEARO PIC X(4)}, {@code app/cpy-bms/COACTUP.CPY:512}. */
    public static final int DOBYEAR_LENGTH = 4;

    /** {@code DOBMONO PIC X(2)}, {@code app/cpy-bms/COACTUP.CPY:518}. */
    public static final int DOBMON_LENGTH = 2;

    /** {@code DOBDAYO PIC X(2)}, {@code app/cpy-bms/COACTUP.CPY:524}. */
    public static final int DOBDAY_LENGTH = 2;

    /** {@code ACSTFCOO PIC X(3)}, {@code app/cpy-bms/COACTUP.CPY:530}. */
    public static final int ACSTFCO_LENGTH = 3;

    /** {@code ACSFNAMO PIC X(25)}, {@code app/cpy-bms/COACTUP.CPY:536}. */
    public static final int ACSFNAM_LENGTH = 25;

    /** {@code ACSMNAMO PIC X(25)}, {@code app/cpy-bms/COACTUP.CPY:542}. */
    public static final int ACSMNAM_LENGTH = 25;

    /** {@code ACSLNAMO PIC X(25)}, {@code app/cpy-bms/COACTUP.CPY:548}. */
    public static final int ACSLNAM_LENGTH = 25;

    /** {@code ACSADL1O PIC X(50)}, {@code app/cpy-bms/COACTUP.CPY:554}. */
    public static final int ACSADL1_LENGTH = 50;

    /** {@code ACSSTTEO PIC X(2)}, {@code app/cpy-bms/COACTUP.CPY:560}. */
    public static final int ACSSTTE_LENGTH = 2;

    /** {@code ACSADL2O PIC X(50)}, {@code app/cpy-bms/COACTUP.CPY:566}. */
    public static final int ACSADL2_LENGTH = 50;

    /** {@code ACSZIPCO PIC X(5)}, {@code app/cpy-bms/COACTUP.CPY:572}. */
    public static final int ACSZIPC_LENGTH = 5;

    /** {@code ACSCITYO PIC X(50)}, {@code app/cpy-bms/COACTUP.CPY:578}. */
    public static final int ACSCITY_LENGTH = 50;

    /** {@code ACSCTRYO PIC X(3)}, {@code app/cpy-bms/COACTUP.CPY:584}. */
    public static final int ACSCTRY_LENGTH = 3;

    /** {@code ACSPH1AO PIC X(3)}, {@code app/cpy-bms/COACTUP.CPY:590} - phone 1 area code. */
    public static final int ACSPH1A_LENGTH = 3;

    /** {@code ACSPH1BO PIC X(3)}, {@code app/cpy-bms/COACTUP.CPY:596} - phone 1 prefix. */
    public static final int ACSPH1B_LENGTH = 3;

    /** {@code ACSPH1CO PIC X(4)}, {@code app/cpy-bms/COACTUP.CPY:602} - phone 1 line number. */
    public static final int ACSPH1C_LENGTH = 4;

    /** {@code ACSGOVTO PIC X(20)}, {@code app/cpy-bms/COACTUP.CPY:608}. */
    public static final int ACSGOVT_LENGTH = 20;

    /** {@code ACSPH2AO PIC X(3)}, {@code app/cpy-bms/COACTUP.CPY:614} - phone 2 area code. */
    public static final int ACSPH2A_LENGTH = 3;

    /** {@code ACSPH2BO PIC X(3)}, {@code app/cpy-bms/COACTUP.CPY:620} - phone 2 prefix. */
    public static final int ACSPH2B_LENGTH = 3;

    /** {@code ACSPH2CO PIC X(4)}, {@code app/cpy-bms/COACTUP.CPY:626} - phone 2 line number. */
    public static final int ACSPH2C_LENGTH = 4;

    /** {@code ACSEFTCO PIC X(10)}, {@code app/cpy-bms/COACTUP.CPY:632}. */
    public static final int ACSEFTC_LENGTH = 10;

    /** {@code ACSPFLGO PIC X(1)}, {@code app/cpy-bms/COACTUP.CPY:638}. */
    public static final int ACSPFLG_LENGTH = 1;

    /**
     * {@code INFOMSGO PIC X(45)}, {@code app/cpy-bms/COACTUP.CPY:644}.
     *
     * <p>The <strong>map</strong> width. The program's own message fields are narrower and are
     * right-space-padded across a cross-width {@code PIC X} move; the working-storage width must never be
     * substituted here. See {@link #CSMSG01Y_MESSAGE_LENGTH}.
     */
    public static final int INFOMSG_LENGTH = 45;

    /**
     * {@code ERRMSGO PIC X(78)}, {@code app/cpy-bms/COACTUP.CPY:650}.
     *
     * <p>The map width again, and wider than either the {@code CSMSG01Y} messages or
     * {@code CCARD-ERROR-MSG PIC X(75)}, so a message arriving from either is space-padded on the right.
     */
    public static final int ERRMSG_LENGTH = 78;

    /** {@code FKEYSO PIC X(21)}, {@code app/cpy-bms/COACTUP.CPY:656}. */
    public static final int FKEYS_LENGTH = 21;

    /** {@code FKEY05O PIC X(7)}, {@code app/cpy-bms/COACTUP.CPY:662}. */
    public static final int FKEY05_LENGTH = 7;

    /** {@code FKEY12O PIC X(10)}, {@code app/cpy-bms/COACTUP.CPY:668}. */
    public static final int FKEY12_LENGTH = 10;

    // =================================================================================================
    // Group geometry. Every term is a named constant and every total is arithmetic over those constants,
    // so the 1095 is derived rather than asserted and a transcription error in one width shows up as a
    // width mismatch rather than as a silently shifted offset (practices B8 and B11).
    // =================================================================================================

    /**
     * {@code 02 FILLER PIC X(12)}, {@code app/cpy-bms/COACTUP.CPY:344} - the {@code TIOAPFX=YES} prefix
     * that {@code app/bms/COACTUP.bms:23} asks BMS to generate.
     *
     * <p>It belongs to the terminal input/output area rather than to the application, so it carries no
     * value this type exposes; it is nonetheless a real span of the group and is declared as one.
     */
    public static final int TIOAPFX_LENGTH = 12;

    /**
     * {@code 02 FILLER PICTURE X(3)}, the unnamed span that opens each field's stride.
     *
     * <p>It overlays the input group's {@code xxxL COMP PIC S9(4)} halfword and {@code xxxF PICTURE X}
     * flag byte. CICS does not read it on output, and {@code MOVE LOW-VALUES TO CACTUPAO} leaves it
     * binary zero.
     */
    public static final int FIELD_PREFIX_FILLER_LENGTH = 3;

    /** {@code 02 xxxC PICTURE X} - the COLOR item, one byte. */
    public static final int COLOUR_ITEM_LENGTH = 1;

    /** {@code 02 xxxP PICTURE X} - the PS (programmed symbols) item, one byte. */
    public static final int PS_ITEM_LENGTH = 1;

    /** {@code 02 xxxH PICTURE X} - the HILIGHT item, one byte. */
    public static final int HILIGHT_ITEM_LENGTH = 1;

    /** {@code 02 xxxV PICTURE X} - the VALIDN item, one byte. */
    public static final int VALIDN_ITEM_LENGTH = 1;

    /**
     * The four extended-attribute items together.
     *
     * <p>They exist because {@code CACTUPA DFHMDI} names {@code DSATTS=(COLOR,HILIGHT,PS,VALIDN)} and
     * {@code MAPATTS=(COLOR,HILIGHT,PS,VALIDN)} at {@code app/bms/COACTUP.bms:26-27} - not because of
     * {@code EXTATT}, which this mapset does not declare.
     */
    public static final int ATTRIBUTE_QUAD_LENGTH =
            COLOUR_ITEM_LENGTH + PS_ITEM_LENGTH + HILIGHT_ITEM_LENGTH + VALIDN_ITEM_LENGTH;

    /**
     * The non-data bytes each field contributes: {@value #FIELD_PREFIX_FILLER_LENGTH} of {@code FILLER}
     * plus {@value #ATTRIBUTE_QUAD_LENGTH} of attribute items.
     *
     * <p>The input group's overhead is the same seven bytes - a halfword, a flag byte and a four-byte
     * {@code FILLER} - which is why {@code CACTUPAO REDEFINES CACTUPAI} compiles and why every field's
     * data offset matches on both sides.
     */
    public static final int FIELD_OVERHEAD = FIELD_PREFIX_FILLER_LENGTH + ATTRIBUTE_QUAD_LENGTH;

    /** The name-labelled {@code DFHMDF} fields of {@code app/bms/COACTUP.bms}. */
    public static final int FIELD_COUNT = 54;

    /**
     * The data bytes of the group: the sum of the {@value #FIELD_COUNT} declared widths.
     *
     * <p>Written as the sum rather than as {@code 705} so that the constant cannot drift away from the
     * widths it is the sum of.
     */
    public static final int PAYLOAD_LENGTH = TRNNAME_LENGTH + TITLE01_LENGTH + CURDATE_LENGTH
            + PGMNAME_LENGTH + TITLE02_LENGTH + CURTIME_LENGTH + ACCTSID_LENGTH + ACSTTUS_LENGTH
            + OPNYEAR_LENGTH + OPNMON_LENGTH + OPNDAY_LENGTH + ACRDLIM_LENGTH + EXPYEAR_LENGTH
            + EXPMON_LENGTH + EXPDAY_LENGTH + ACSHLIM_LENGTH + RISYEAR_LENGTH + RISMON_LENGTH
            + RISDAY_LENGTH + ACURBAL_LENGTH + ACRCYCR_LENGTH + AADDGRP_LENGTH + ACRCYDB_LENGTH
            + ACSTNUM_LENGTH + ACTSSN1_LENGTH + ACTSSN2_LENGTH + ACTSSN3_LENGTH + DOBYEAR_LENGTH
            + DOBMON_LENGTH + DOBDAY_LENGTH + ACSTFCO_LENGTH + ACSFNAM_LENGTH + ACSMNAM_LENGTH
            + ACSLNAM_LENGTH + ACSADL1_LENGTH + ACSSTTE_LENGTH + ACSADL2_LENGTH + ACSZIPC_LENGTH
            + ACSCITY_LENGTH + ACSCTRY_LENGTH + ACSPH1A_LENGTH + ACSPH1B_LENGTH + ACSPH1C_LENGTH
            + ACSGOVT_LENGTH + ACSPH2A_LENGTH + ACSPH2B_LENGTH + ACSPH2C_LENGTH + ACSEFTC_LENGTH
            + ACSPFLG_LENGTH + INFOMSG_LENGTH + ERRMSG_LENGTH + FKEYS_LENGTH + FKEY05_LENGTH
            + FKEY12_LENGTH;

    /**
     * The whole {@code CACTUPAO} group: {@value #TIOAPFX_LENGTH} + {@value #FIELD_COUNT} x
     * {@value #FIELD_OVERHEAD} + {@value #PAYLOAD_LENGTH} = {@value #GROUP_LENGTH} bytes.
     */
    public static final int GROUP_LENGTH =
            TIOAPFX_LENGTH + FIELD_COUNT * FIELD_OVERHEAD + PAYLOAD_LENGTH;

    // =================================================================================================
    // Navigation widths. Taken from the program's own literals at app/cbl/COACTUPC.cbl:533-540 and
    // reconciled with the receivers COCOM01Y declares.
    // =================================================================================================

    /** {@code LIT-THISPGM PIC X(8)} - the width of a program name, and of {@code CDEMO-TO-PROGRAM}. */
    public static final int NEXT_PROGRAM_LENGTH = NavigationContext.TO_PROGRAM_LENGTH;

    /**
     * The width a mapset name arrives at: {@code CDEMO-LAST-MAPSET PIC X(7)}.
     *
     * <p>{@code LIT-THISMAPSET} is {@code PIC X(8)} and carries a trailing space; moving it into the
     * seven-character receiver at {@code app/cbl/COACTUPC.cbl:949} discards that space, so seven is the
     * width that actually travels.
     */
    public static final int NEXT_MAPSET_LENGTH = NavigationContext.LAST_MAPSET_LENGTH;

    /** {@code LIT-THISMAP PIC X(7)} and {@code CDEMO-LAST-MAP PIC X(7)} - the same seven characters. */
    public static final int NEXT_MAP_LENGTH = NavigationContext.LAST_MAP_LENGTH;

    /** The eight-character mapset literal {@code app/cbl/COACTUPC.cbl:537-538} declares. */
    public static final int THIS_MAPSET_LITERAL_LENGTH = 8;

    // =================================================================================================
    // Commarea geometry. The two areas that travel together in the 2000-byte WS-COMMAREA.
    // =================================================================================================

    /**
     * The bytes {@code COMMON-RETURN} actually fills of {@code WS-COMMAREA PIC X(2000)}
     * [{@code app/cbl/COACTUPC.cbl:850}]: the {@value NavigationContext#COMMAREA_LENGTH}-byte
     * {@code CARDDEMO-COMMAREA} followed by the {@value CommArea#RECORD_LENGTH}-byte
     * {@code WS-THIS-PROGCOMMAREA}, written at {@code :1010-1013}.
     */
    public static final int TOTAL_COMMAREA_LENGTH =
            NavigationContext.COMMAREA_LENGTH + CommArea.RECORD_LENGTH;

    /**
     * {@code WS-COMMAREA PIC X(2000)}, {@code app/cbl/COACTUPC.cbl:850}.
     *
     * <p>{@code EXEC CICS RETURN} passes the full 2000 bytes at {@code :1015-1019} even though only
     * {@value #TOTAL_COMMAREA_LENGTH} of them are filled, so the declared capacity is part of the
     * contract and not a rounding of it.
     */
    public static final int COMMAREA_CAPACITY = 2000;

    // =================================================================================================
    // Cross-width message facts, and the fixed-point policy. Both are recorded as constants rather than
    // as prose so that a test can pin them (gates G22 and G24).
    // =================================================================================================

    /**
     * {@code CSMSG01Y}'s standard message width, {@link SystemMessages#MESSAGE_LENGTH}.
     *
     * <p>Recorded here for one reason: it is <strong>neither</strong> of this screen's two message
     * widths. {@value #INFOMSG_LENGTH} is narrower, so a standard message moved into {@code INFOMSGO}
     * loses its tail; {@value #ERRMSG_LENGTH} is wider, so one moved into {@code ERRMSGO} gains trailing
     * spaces. Substituting the working-storage width for either map width would corrupt both directions,
     * which is why the map widths above are the only ones this type uses.
     */
    public static final int CSMSG01Y_MESSAGE_LENGTH = SystemMessages.MESSAGE_LENGTH;

    /**
     * The scale every monetary value this screen renders is held at, {@link CobolDecimal#MONETARY_SCALE}.
     *
     * <p>The five monetary fields are {@code X(15)} character strings here, but the values behind them -
     * {@code ACUP-xxx-CURR-BAL}, {@code -CREDIT-LIMIT}, {@code -CASH-CREDIT-LIMIT}, {@code -CURR-CYC-CREDIT}
     * and {@code -CURR-CYC-DEBIT} - are all {@code PIC S9(10)V99}, so scale is exactly two. Reachable
     * from this type through {@link #oldAcct()} and {@link #newAcct()}, whose {@code BigDecimal}
     * accessors are governed by this scale. There is no {@code double} and no {@code float} anywhere in
     * this file (gate <strong>G22</strong>).
     */
    public static final int MONETARY_SCALE = CobolDecimal.MONETARY_SCALE;

    /**
     * The only faithful rounding mode, {@link CobolDecimal#COBOL_ROUNDING}.
     *
     * <p>{@code ROUNDED} appears <strong>zero</strong> times across all 28 COBOL programs, so COBOL
     * truncates excess fractional digits on store and {@link RoundingMode#DOWN} is the only correct
     * choice. Neither {@code HALF_UP} nor {@code HALF_EVEN} appears in this file (gate
     * <strong>G24</strong>).
     */
    public static final RoundingMode MONETARY_ROUNDING = CobolDecimal.COBOL_ROUNDING;

    // =================================================================================================
    // Symbolic-map item suffixes, and the mapset's INITIAL legends.
    // =================================================================================================

    /** The suffix of a payload item: {@code 02 xxxO PIC X(n)}. */
    public static final String OUTPUT_ITEM_SUFFIX = "O";

    /** The suffix of the COLOR item: {@code 02 xxxC PICTURE X}. */
    public static final String COLOUR_ITEM_SUFFIX = "C";

    /** The suffix of the PS item: {@code 02 xxxP PICTURE X}. */
    public static final String PS_ITEM_SUFFIX = "P";

    /** The suffix of the HILIGHT item: {@code 02 xxxH PICTURE X}. */
    public static final String HILIGHT_ITEM_SUFFIX = "H";

    /** The suffix of the VALIDN item: {@code 02 xxxV PICTURE X}. */
    public static final String VALIDN_ITEM_SUFFIX = "V";

    /**
     * The asterisk {@code CSSETATY} moves into a blank field's payload item,
     * {@link FieldAttributeSetter#ASTERISK}.
     *
     * <p>Present so that a consumer can recognise the marker rather than hard-code {@code "*"}, and so
     * that the {@code "*"}-acceptance property has a name in tests.
     */
    public static final String BLANK_FIELD_MARKER = FieldAttributeSetter.ASTERISK;

    /**
     * {@code FKEYS DFHMDF LENGTH=21 INITIAL='ENTER=Process F3=Exit'},
     * {@code app/bms/COACTUP.bms:493} - exactly {@value #FKEYS_LENGTH} characters, so the legend fills
     * its field with no padding.
     */
    public static final String FKEYS_LEGEND = "ENTER=Process F3=Exit";

    /**
     * {@code FKEY05 DFHMDF LENGTH=7 INITIAL='F5=Save'}, {@code app/bms/COACTUP.bms:498}.
     *
     * <p>Declared {@code ATTRB=(ASKIP,DRK)} - hidden. {@code 3390-SETUP-INFOMSG-ATTRS} reveals it by
     * moving {@code DFHBMASB} into its attribute byte at {@code app/cbl/COACTUPC.cbl:3579}, and only when
     * {@code PROMPT-FOR-CONFIRMATION} holds.
     */
    public static final String FKEY05_LEGEND = "F5=Save";

    /**
     * {@code FKEY12 DFHMDF LENGTH=10 INITIAL='F12=Cancel'}, {@code app/bms/COACTUP.bms:503}.
     *
     * <p>Also {@code ATTRB=(ASKIP,DRK)}. Revealed at {@code app/cbl/COACTUPC.cbl:3575} when changes have
     * been made but are not yet done, and again at {@code :3580} when confirmation is being prompted for.
     */
    public static final String FKEY12_LEGEND = "F12=Cancel";

    /** The space character a {@code PIC X} field pads with. */
    private static final char SPACE = ' ';

    /** The {@code LOW-VALUES} character: the lowest of the collating sequence, binary zero. */
    private static final char LOW_VALUE = '\u0000';

    /** {@code LOW-VALUES} as a byte, which is binary zero on every code page by definition. */
    private static final byte LOW_VALUE_BYTE = (byte) 0x00;

    // =================================================================================================
    // The 54 fields, as an enumeration. Every place that needs to address a field - the group-image
    // codec, the attribute quad, a parity differ, a highlight routine - names it in a way the compiler
    // checks rather than passing a string that can be misspelled. Each constant carries its whole
    // provenance: the DFHMDF label, the xxxO item name, the PICTURE as written, the width, both source
    // line numbers, the screen position and the data offset, so the gate G9 trace reads from one place
    // and is verifiable against the copybook without arithmetic.
    // =================================================================================================

    /**
     * One of the {@value #FIELD_COUNT} name-labelled {@code DFHMDF} fields of
     * {@code app/bms/COACTUP.bms}, in the order the mapset declares them - which is also the order
     * {@code app/cpy-bms/COACTUP.CPY} lays their storage down.
     *
     * <p>That order interleaves the screen's two columns rather than reading down one of them:
     * {@code OPNYEAR}/{@code OPNMON}/{@code OPNDAY} at {@code POS=(6,17)}, {@code (6,24)} and
     * {@code (6,29)} are followed by {@code ACRDLIM} at {@code POS=(6,61)}, then the expiry triple on row
     * 7 by {@code ACSHLIM} at {@code POS=(7,61)}, and so on. It is the copybook's order; it is not sorted
     * and must not be.
     *
     * <p>Note what is deliberately <em>absent</em> and must not be added: no {@code PAGENO}, and none of
     * the sibling view screen's undivided {@code ADTOPEN}, {@code AEXPDT}, {@code AREISDT},
     * {@code ACSTSSN} or {@code ACSTDOB}. This screen splits all five into the twenty part-fields below,
     * and that split is what {@code 9700-CHECK-CHANGE-IN-REC} compares against and what the
     * {@code STRING ... DELIMITED BY SIZE} recomposition sites depend on.
     *
     * <p>The {@code copybookLine} of each constant is its {@code xxxO} line, which is where a reviewer
     * checking this type should look - the corresponding {@code xxxI} lines belong to
     * {@link AccountUpdateRequest}.
     */
    public enum ScreenField {

        /** {@code TRNNAME} - transaction identifier. Fed from {@code LIT-THISTRANID} at {@code :2675}. */
        TRNNAME("TRNNAME", "TRNNAMEO", "X(4)", TRNNAME_LENGTH, 350, 34, 1, 7, 19),

        /** {@code TITLE01} - from {@link ScreenTitles#CCDA_TITLE01}, moved at {@code :2673}. */
        TITLE01("TITLE01", "TITLE01O", "X(40)", TITLE01_LENGTH, 356, 38, 1, 21, 30),

        /** {@code CURDATE} - {@code INITIAL='mm/dd/yy'}; fed from {@code WS-CURDATE-MM-DD-YY} at {@code :2684}. */
        CURDATE("CURDATE", "CURDATEO", "X(8)", CURDATE_LENGTH, 362, 47, 1, 71, 77),

        /** {@code PGMNAME} - from {@code LIT-THISPGM}, moved at {@code :2676}. */
        PGMNAME("PGMNAME", "PGMNAMEO", "X(8)", PGMNAME_LENGTH, 368, 57, 2, 7, 92),

        /** {@code TITLE02} - from {@link ScreenTitles#CCDA_TITLE02}, moved at {@code :2674}. */
        TITLE02("TITLE02", "TITLE02O", "X(40)", TITLE02_LENGTH, 374, 61, 2, 21, 107),

        /** {@code CURTIME} - {@code INITIAL='hh:mm:ss'}; fed from {@code WS-CURTIME-HH-MM-SS} at {@code :2690}. */
        CURTIME("CURTIME", "CURTIMEO", "X(8)", CURTIME_LENGTH, 380, 70, 2, 71, 154),

        /** {@code ACCTSID} - the account key. {@code ATTRB=(IC,UNPROT)}: the cursor lands here. */
        ACCTSID("ACCTSID", "ACCTSIDO", "X(11)", ACCTSID_LENGTH, 386, 84, 5, 38, 169),

        /** {@code ACSTTUS} - {@code ACCT-ACTIVE-STATUS}, one character. */
        ACSTTUS("ACSTTUS", "ACSTTUSO", "X(1)", ACSTTUS_LENGTH, 392, 94, 5, 70, 187),

        /** {@code OPNYEAR} - open-date year part. {@code ATTRB=(FSET,UNPROT)}. */
        OPNYEAR("OPNYEAR", "OPNYEARO", "X(4)", OPNYEAR_LENGTH, 398, 104, 6, 17, 195),

        /** {@code OPNMON} - open-date month part. */
        OPNMON("OPNMON", "OPNMONO", "X(2)", OPNMON_LENGTH, 404, 112, 6, 24, 206),

        /** {@code OPNDAY} - open-date day part. */
        OPNDAY("OPNDAY", "OPNDAYO", "X(2)", OPNDAY_LENGTH, 410, 120, 6, 29, 215),

        /** {@code ACRDLIM} - credit limit, rendered by {@code WS-EDIT-CURRENCY-9-2-F} into {@code X(15)}. */
        ACRDLIM("ACRDLIM", "ACRDLIMO", "X(15)", ACRDLIM_LENGTH, 416, 132, 6, 61, 224),

        /** {@code EXPYEAR} - expiry-date year part. */
        EXPYEAR("EXPYEAR", "EXPYEARO", "X(4)", EXPYEAR_LENGTH, 422, 142, 7, 17, 246),

        /** {@code EXPMON} - expiry-date month part. */
        EXPMON("EXPMON", "EXPMONO", "X(2)", EXPMON_LENGTH, 428, 150, 7, 24, 257),

        /** {@code EXPDAY} - expiry-date day part. */
        EXPDAY("EXPDAY", "EXPDAYO", "X(2)", EXPDAY_LENGTH, 434, 158, 7, 29, 266),

        /** {@code ACSHLIM} - cash credit limit, {@code X(15)}. */
        ACSHLIM("ACSHLIM", "ACSHLIMO", "X(15)", ACSHLIM_LENGTH, 440, 170, 7, 61, 275),

        /** {@code RISYEAR} - reissue-date year part. */
        RISYEAR("RISYEAR", "RISYEARO", "X(4)", RISYEAR_LENGTH, 446, 180, 8, 17, 297),

        /** {@code RISMON} - reissue-date month part. */
        RISMON("RISMON", "RISMONO", "X(2)", RISMON_LENGTH, 452, 188, 8, 24, 308),

        /** {@code RISDAY} - reissue-date day part. */
        RISDAY("RISDAY", "RISDAYO", "X(2)", RISDAY_LENGTH, 458, 196, 8, 29, 317),

        /** {@code ACURBAL} - current balance, {@code X(15)}. */
        ACURBAL("ACURBAL", "ACURBALO", "X(15)", ACURBAL_LENGTH, 464, 208, 8, 61, 326),

        /** {@code ACRCYCR} - current cycle credit, {@code X(15)}. */
        ACRCYCR("ACRCYCR", "ACRCYCRO", "X(15)", ACRCYCR_LENGTH, 470, 219, 9, 61, 348),

        /** {@code AADDGRP} - the account group identifier, {@code ACCT-GROUP-ID PIC X(10)}. */
        AADDGRP("AADDGRP", "AADDGRPO", "X(10)", AADDGRP_LENGTH, 476, 229, 10, 23, 370),

        /** {@code ACRCYDB} - current cycle debit, {@code X(15)}. */
        ACRCYDB("ACRCYDB", "ACRCYDBO", "X(15)", ACRCYDB_LENGTH, 482, 240, 10, 61, 387),

        /** {@code ACSTNUM} - the customer identifier, nine characters. */
        ACSTNUM("ACSTNUM", "ACSTNUMO", "X(9)", ACSTNUM_LENGTH, 488, 254, 12, 23, 409),

        /** {@code ACTSSN1} - social-security part one. {@code INITIAL='999'}. */
        ACTSSN1("ACTSSN1", "ACTSSN1O", "X(3)", ACTSSN1_LENGTH, 494, 264, 12, 55, 425),

        /** {@code ACTSSN2} - social-security part two. {@code INITIAL='99'}. */
        ACTSSN2("ACTSSN2", "ACTSSN2O", "X(2)", ACTSSN2_LENGTH, 500, 272, 12, 61, 435),

        /** {@code ACTSSN3} - social-security part three. {@code INITIAL='9999'}. */
        ACTSSN3("ACTSSN3", "ACTSSN3O", "X(4)", ACTSSN3_LENGTH, 506, 280, 12, 66, 444),

        /** {@code DOBYEAR} - date-of-birth year part. */
        DOBYEAR("DOBYEAR", "DOBYEARO", "X(4)", DOBYEAR_LENGTH, 512, 291, 13, 23, 455),

        /** {@code DOBMON} - date-of-birth month part. */
        DOBMON("DOBMON", "DOBMONO", "X(2)", DOBMON_LENGTH, 518, 299, 13, 30, 466),

        /** {@code DOBDAY} - date-of-birth day part. */
        DOBDAY("DOBDAY", "DOBDAYO", "X(2)", DOBDAY_LENGTH, 524, 307, 13, 35, 475),

        /** {@code ACSTFCO} - the FICO credit score, three characters. */
        ACSTFCO("ACSTFCO", "ACSTFCOO", "X(3)", ACSTFCO_LENGTH, 530, 318, 13, 62, 484),

        /** {@code ACSFNAM} - customer given name. */
        ACSFNAM("ACSFNAM", "ACSFNAMO", "X(25)", ACSFNAM_LENGTH, 536, 336, 15, 1, 494),

        /** {@code ACSMNAM} - customer middle name. */
        ACSMNAM("ACSMNAM", "ACSMNAMO", "X(25)", ACSMNAM_LENGTH, 542, 342, 15, 28, 526),

        /** {@code ACSLNAM} - customer family name. */
        ACSLNAM("ACSLNAM", "ACSLNAMO", "X(25)", ACSLNAM_LENGTH, 548, 348, 15, 55, 558),

        /** {@code ACSADL1} - address line one. */
        ACSADL1("ACSADL1", "ACSADL1O", "X(50)", ACSADL1_LENGTH, 554, 356, 16, 10, 590),

        /** {@code ACSSTTE} - state code. Validated against {@code CSLKPCDY}'s table by the service. */
        ACSSTTE("ACSSTTE", "ACSSTTEO", "X(2)", ACSSTTE_LENGTH, 560, 366, 16, 73, 647),

        /** {@code ACSADL2} - address line two. */
        ACSADL2("ACSADL2", "ACSADL2O", "X(50)", ACSADL2_LENGTH, 566, 372, 17, 10, 656),

        /**
         * {@code ACSZIPC} - the postal code as the screen shows it, five characters.
         *
         * <p>Narrower than {@code CUST-ADDR-ZIP PIC X(10)} and than the snapshot's matching
         * {@code X(10)}, so the five characters are the screen's view of a wider stored field.
         */
        ACSZIPC("ACSZIPC", "ACSZIPCO", "X(5)", ACSZIPC_LENGTH, 572, 382, 17, 73, 713),

        /** {@code ACSCITY} - address line three on the record; the city on the screen. */
        ACSCITY("ACSCITY", "ACSCITYO", "X(50)", ACSCITY_LENGTH, 578, 392, 18, 10, 725),

        /** {@code ACSCTRY} - country code. */
        ACSCTRY("ACSCTRY", "ACSCTRYO", "X(3)", ACSCTRY_LENGTH, 584, 402, 18, 73, 782),

        /** {@code ACSPH1A} - phone one, area code. Checked against {@code AreaCodeLookup} by the service. */
        ACSPH1A("ACSPH1A", "ACSPH1AO", "X(3)", ACSPH1A_LENGTH, 590, 412, 19, 10, 792),

        /** {@code ACSPH1B} - phone one, prefix. */
        ACSPH1B("ACSPH1B", "ACSPH1BO", "X(3)", ACSPH1B_LENGTH, 596, 417, 19, 14, 802),

        /** {@code ACSPH1C} - phone one, line number. */
        ACSPH1C("ACSPH1C", "ACSPH1CO", "X(4)", ACSPH1C_LENGTH, 602, 422, 19, 18, 812),

        /** {@code ACSGOVT} - the government-issued identifier, twenty characters, in the clear. */
        ACSGOVT("ACSGOVT", "ACSGOVTO", "X(20)", ACSGOVT_LENGTH, 608, 433, 19, 58, 823),

        /** {@code ACSPH2A} - phone two, area code. */
        ACSPH2A("ACSPH2A", "ACSPH2AO", "X(3)", ACSPH2A_LENGTH, 614, 443, 20, 10, 850),

        /** {@code ACSPH2B} - phone two, prefix. */
        ACSPH2B("ACSPH2B", "ACSPH2BO", "X(3)", ACSPH2B_LENGTH, 620, 448, 20, 14, 860),

        /** {@code ACSPH2C} - phone two, line number. */
        ACSPH2C("ACSPH2C", "ACSPH2CO", "X(4)", ACSPH2C_LENGTH, 626, 453, 20, 18, 870),

        /** {@code ACSEFTC} - the electronic funds transfer account identifier. */
        ACSEFTC("ACSEFTC", "ACSEFTCO", "X(10)", ACSEFTC_LENGTH, 632, 464, 20, 41, 881),

        /** {@code ACSPFLG} - the primary card holder indicator, one character. */
        ACSPFLG("ACSPFLG", "ACSPFLGO", "X(1)", ACSPFLG_LENGTH, 638, 474, 20, 78, 898),

        /**
         * {@code INFOMSG} - the informational message line, {@code ATTRB=(ASKIP)}.
         *
         * <p>Its visibility is carried by its attribute byte: {@code 3390-SETUP-INFOMSG-ATTRS} moves
         * {@code DFHBMDAR} in when there is no message and {@code DFHBMASB} in when there is
         * [{@code app/cbl/COACTUPC.cbl:3567-3571}].
         */
        INFOMSG("INFOMSG", "INFOMSGO", "X(45)", INFOMSG_LENGTH, 644, 480, 22, 23, 906),

        /** {@code ERRMSG} - the error line, {@code ATTRB=(ASKIP,BRT,FSET)}: bright and always modified. */
        ERRMSG("ERRMSG", "ERRMSGO", "X(78)", ERRMSG_LENGTH, 650, 489, 23, 1, 958),

        /** {@code FKEYS} - the always-visible legend, {@code ATTRB=(ASKIP,NORM)}. */
        FKEYS("FKEYS", "FKEYSO", "X(21)", FKEYS_LENGTH, 656, 493, 24, 1, 1043),

        /** {@code FKEY05} - the save legend. {@code ATTRB=(ASKIP,DRK)}: hidden until revealed. */
        FKEY05("FKEY05", "FKEY05O", "X(7)", FKEY05_LENGTH, 662, 498, 24, 23, 1071),

        /** {@code FKEY12} - the cancel legend. {@code ATTRB=(ASKIP,DRK)}: hidden until revealed. */
        FKEY12("FKEY12", "FKEY12O", "X(10)", FKEY12_LENGTH, 668, 503, 24, 31, 1085);

        /** The {@code DFHMDF} label, which is this field's identity in every diagnostic and diff. */
        private final String label;

        /** The symbolic-map payload item name, {@code xxxO}. */
        private final String symbolicItemName;

        /** The {@code PICTURE} exactly as the copybook writes it. */
        private final String picture;

        /** The declared width in bytes. */
        private final int length;

        /** The 1-based line of the {@code xxxO} item in {@code app/cpy-bms/COACTUP.CPY}. */
        private final int copybookLine;

        /** The 1-based line of the {@code DFHMDF} entry in {@code app/bms/COACTUP.bms}. */
        private final int mapsetLine;

        /** The 1-based screen row from {@code POS=(row,column)}. */
        private final int screenRow;

        /** The 1-based screen column from {@code POS=(row,column)}. */
        private final int screenColumn;

        /** The absolute 0-based offset of the {@code xxxO} item within the group. */
        private final int dataOffset;

        /**
         * Records one field's provenance.
         *
         * @param label            the {@code DFHMDF} label
         * @param symbolicItemName the {@code xxxO} item name
         * @param picture          the {@code PICTURE} as written
         * @param length           the declared width
         * @param copybookLine     the {@code xxxO} line in {@code app/cpy-bms/COACTUP.CPY}
         * @param mapsetLine       the {@code DFHMDF} line in {@code app/bms/COACTUP.bms}
         * @param screenRow        the 1-based row from {@code POS}
         * @param screenColumn     the 1-based column from {@code POS}
         * @param dataOffset       the 0-based group offset of the payload item
         */
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
         * The {@code DFHMDF} label, which is also this field's key in {@link #fieldValues()} and the name
         * a field-by-field diff reports.
         *
         * @return the label, verbatim
         */
        public String label() {
            return label;
        }

        /**
         * The symbolic-map payload item name as {@code app/cpy-bms/COACTUP.CPY} declares it - the
         * {@code xxxO} form, which is what makes this the output projection.
         *
         * @return the {@code xxxO} item name
         */
        public String symbolicItemName() {
            return symbolicItemName;
        }

        /**
         * The {@code PICTURE} as written. Every one of the {@value AccountUpdateResponse#FIELD_COUNT}
         * fields is {@code X(n)}, because {@code app/bms/COACTUP.bms} declares no {@code PICOUT}.
         *
         * @return the picture string
         */
        public String picture() {
            return picture;
        }

        /**
         * Whether this field is alphanumeric. True for all {@value AccountUpdateResponse#FIELD_COUNT},
         * and asserted rather than assumed so that adding a numeric item would be caught.
         *
         * @return {@code true} when the picture is an {@code X} picture
         */
        public boolean isAlphanumeric() {
            return picture.startsWith("X(");
        }

        /**
         * The declared width, which is both the {@code xxxO} {@code PICTURE} length and the
         * {@code DFHMDF LENGTH=}. The two agree at every field in the source.
         *
         * @return the width in bytes
         */
        public int length() {
            return length;
        }

        /**
         * The line of the {@code xxxO} item in {@code app/cpy-bms/COACTUP.CPY}.
         *
         * @return the 1-based copybook line
         */
        public int copybookLine() {
            return copybookLine;
        }

        /**
         * The line of the {@code DFHMDF} entry in {@code app/bms/COACTUP.bms}.
         *
         * @return the 1-based mapset line
         */
        public int mapsetLine() {
            return mapsetLine;
        }

        /**
         * The screen row from {@code POS=(row,column)}, between 1 and
         * {@value AccountUpdateResponse#SCREEN_ROWS}.
         *
         * @return the 1-based row
         */
        public int screenRow() {
            return screenRow;
        }

        /**
         * The screen column from {@code POS=(row,column)}, between 1 and
         * {@value AccountUpdateResponse#SCREEN_COLUMNS}.
         *
         * @return the 1-based column
         */
        public int screenColumn() {
            return screenColumn;
        }

        /**
         * The absolute 0-based offset of the {@code xxxO} payload item within the
         * {@value AccountUpdateResponse#GROUP_LENGTH}-byte group.
         *
         * <p>Identical to the offset of the matching {@code xxxI} item in the input group, because the
         * two strides are the same {@value AccountUpdateResponse#FIELD_OVERHEAD} bytes plus {@code n}.
         *
         * @return the data offset
         */
        public int dataOffset() {
            return dataOffset;
        }

        /**
         * The 0-based offset of this field's {@code 02 FILLER PICTURE X(3)} prefix span.
         *
         * @return the prefix {@code FILLER}'s offset
         */
        public int prefixFillerOffset() {
            return dataOffset - FIELD_OVERHEAD;
        }

        /**
         * The 0-based offset of this field's {@code 02 xxxC PICTURE X} COLOR item - the byte
         * {@code CSSETATY} moves {@code DFHRED} into.
         *
         * @return the colour item's offset
         */
        public int colourItemOffset() {
            return prefixFillerOffset() + FIELD_PREFIX_FILLER_LENGTH;
        }

        /**
         * The 0-based offset of this field's {@code 02 xxxP PICTURE X} PS item.
         *
         * @return the programmed-symbols item's offset
         */
        public int psItemOffset() {
            return colourItemOffset() + COLOUR_ITEM_LENGTH;
        }

        /**
         * The 0-based offset of this field's {@code 02 xxxH PICTURE X} HILIGHT item.
         *
         * @return the highlight item's offset
         */
        public int hilightItemOffset() {
            return psItemOffset() + PS_ITEM_LENGTH;
        }

        /**
         * The 0-based offset of this field's {@code 02 xxxV PICTURE X} VALIDN item.
         *
         * @return the validation item's offset
         */
        public int validnItemOffset() {
            return hilightItemOffset() + HILIGHT_ITEM_LENGTH;
        }

        /**
         * The exclusive end offset of this field's payload item.
         *
         * @return {@code dataOffset() + length()}
         */
        public int endOffsetExclusive() {
            return dataOffset + length;
        }

        /** @return the {@code xxxC} item name, for a diagnostic that names the destination. */
        public String colourItemName() {
            return label + COLOUR_ITEM_SUFFIX;
        }

        /** @return the {@code xxxP} item name. */
        public String psItemName() {
            return label + PS_ITEM_SUFFIX;
        }

        /** @return the {@code xxxH} item name. */
        public String hilightItemName() {
            return label + HILIGHT_ITEM_SUFFIX;
        }

        /** @return the {@code xxxV} item name. */
        public String validnItemName() {
            return label + VALIDN_ITEM_SUFFIX;
        }

        /**
         * The {@code 02 FILLER PICTURE X(3)} descriptor that opens this field's stride.
         *
         * @return a {@code FILLER} span, never {@code null}
         */
        public FieldSpan prefixFillerSpan() {
            return FieldSpan.filler(prefixFillerOffset(), FIELD_PREFIX_FILLER_LENGTH);
        }

        /**
         * The {@code 02 xxxC PICTURE X} descriptor.
         *
         * @return an alphanumeric span named {@code xxxC}
         */
        public FieldSpan colourItemSpan() {
            return FieldSpan.alphanumeric(colourItemName(), colourItemOffset(), COLOUR_ITEM_LENGTH);
        }

        /**
         * The {@code 02 xxxP PICTURE X} descriptor.
         *
         * @return an alphanumeric span named {@code xxxP}
         */
        public FieldSpan psItemSpan() {
            return FieldSpan.alphanumeric(psItemName(), psItemOffset(), PS_ITEM_LENGTH);
        }

        /**
         * The {@code 02 xxxH PICTURE X} descriptor.
         *
         * @return an alphanumeric span named {@code xxxH}
         */
        public FieldSpan hilightItemSpan() {
            return FieldSpan.alphanumeric(hilightItemName(), hilightItemOffset(), HILIGHT_ITEM_LENGTH);
        }

        /**
         * The {@code 02 xxxV PICTURE X} descriptor.
         *
         * @return an alphanumeric span named {@code xxxV}
         */
        public FieldSpan validnItemSpan() {
            return FieldSpan.alphanumeric(validnItemName(), validnItemOffset(), VALIDN_ITEM_LENGTH);
        }

        /**
         * The {@code 02 xxxO PIC X(n)} payload descriptor.
         *
         * @return an alphanumeric span named {@code xxxO}
         */
        public FieldSpan outputItemSpan() {
            return FieldSpan.alphanumeric(symbolicItemName, dataOffset, length);
        }

        /**
         * A one-line description naming every element of this field's provenance, for a failure message
         * that a reviewer can act on without opening the copybook.
         *
         * @return the description
         */
        public String describe() {
            return label + " (" + symbolicItemName + " " + picture + " at "
                    + "app/cpy-bms/COACTUP.CPY:" + copybookLine + ", app/bms/COACTUP.bms:" + mapsetLine
                    + ", POS=(" + screenRow + "," + screenColumn + "), group offset " + dataOffset + ")";
        }

        /**
         * The field carrying a {@code DFHMDF} label.
         *
         * @param label the label to look up; compared exactly, because {@code DFHMDF} labels are
         *              upper-case by construction
         * @return the matching field
         * @throws NullPointerException     if {@code label} is {@code null}
         * @throws IllegalArgumentException if no field carries that label
         */
        public static ScreenField ofLabel(String label) {
            Objects.requireNonNull(label, "A DFHMDF label is required to look a field up");
            for (ScreenField candidate : values()) {
                if (candidate.label.equals(label)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("'" + label + "' is not one of the " + FIELD_COUNT
                    + " name-labelled DFHMDF fields of app/bms/COACTUP.bms. The 74 unnamed entries are "
                    + "screen literals and generate no symbolic-map item.");
        }
    }

    /**
     * The {@value #FIELD_COUNT} fields in symbolic-map order, unmodifiable.
     *
     * <p>Handed out rather than {@code ScreenField.values()} so that callers cannot be handed a fresh
     * mutable array on every call, and so that "in copybook order" is a property of a named constant
     * (practice <strong>B9</strong>).
     */
    public static final List<ScreenField> FIELDS =
            Collections.unmodifiableList(new ArrayList<>(List.of(ScreenField.values())));

    /**
     * The {@value #GROUP_LENGTH}-byte {@code CACTUPAO} layout, declared span by span.
     *
     * <p>Built by hand from the copybook, not by a parser: {@code 02 FILLER PIC X(12)} first, then, per
     * field in copybook order, {@code 02 FILLER PICTURE X(3)}, {@code 02 xxxC}, {@code 02 xxxP},
     * {@code 02 xxxH}, {@code 02 xxxV} and {@code 02 xxxO PIC X(n)} - six spans each, so
     * {@code 1 + 54 x 6 = 325} spans in all (practice <strong>B11</strong>).
     *
     * <p>Every {@code FILLER} is a first-class span rather than an implied gap. {@link RecordLayout}
     * verifies on construction that the spans are contiguous from offset zero and that they sum to the
     * declared length, so omitting one would fail here at class-initialisation time rather than as a
     * silently shifted offset later - which is what gate <strong>G21</strong> is protecting.
     */
    public static final RecordLayout OUTPUT_GROUP_LAYOUT = declareOutputGroupLayout();

    /**
     * Declares the output group's {@code 1 + 54 * 6} spans in copybook order.
     *
     * @return the verified layout
     */
    private static RecordLayout declareOutputGroupLayout() {
        List<FieldSpan> spans = new ArrayList<>(1 + FIELD_COUNT * 6);
        spans.add(FieldSpan.filler(0, TIOAPFX_LENGTH));
        for (ScreenField field : ScreenField.values()) {
            spans.add(field.prefixFillerSpan());
            spans.add(field.colourItemSpan());
            spans.add(field.psItemSpan());
            spans.add(field.hilightItemSpan());
            spans.add(field.validnItemSpan());
            spans.add(field.outputItemSpan());
        }
        return new RecordLayout(GROUP_LENGTH, spans);
    }

    // =================================================================================================
    // The attribute quad. Metadata, never a payload member - but functional and server-written, which is
    // why it is mutable in place.
    // =================================================================================================

    /**
     * One field's four extended-attribute items: {@code xxxC}, {@code xxxP}, {@code xxxH}, {@code xxxV}.
     *
     * <p>Held as {@code byte} rather than {@code char} because a 3270 attribute is a raw byte with no
     * character meaning - {@link BmsAttributes#DFHRED} is {@code 0xF2}, which is not a printable character
     * in any code page this system reads - and because {@link FieldHighlight#colourItemValue()} produces a
     * {@code byte}. Reproducing them from IBM CICS documentation was necessary because
     * {@code DFHBMSCA}, {@code DFHATTR} and {@code DFHAID} are <strong>absent from this repository</strong>
     * despite being referenced by 17, 2 and 17 programs respectively; {@link BmsAttributes} is where they
     * live, and no raw byte is written literally here.
     *
     * <p><strong>Deliberately mutable, and the only mutable state this type has.</strong>
     * {@code 3300-SETUP-SCREEN-ATTRS} and {@code 3390-SETUP-INFOMSG-ATTRS} write attribute bytes
     * <em>after</em> the map has been populated - {@code app/cbl/COACTUPC.cbl:3568}, {@code :3570},
     * {@code :3575}, {@code :3579-3580} and the 39 {@code CSSETATY} expansions - so a built response has
     * to be able to accept them. Excluded from JSON: it is highlight metadata, not screen data.
     *
     * <p>Its initial state is {@link #UNSET}, which is LOW-VALUES, because
     * {@code MOVE LOW-VALUES TO CACTUPAO} at {@code app/cbl/COACTUPC.cbl:2668} zeroes the whole group
     * before anything is written into it.
     */
    public static final class FieldAttributes {

        /**
         * The unset attribute byte: LOW-VALUES, binary zero.
         *
         * <p>Note that {@link BmsAttributes#DFHDFCOL} (default colour) and
         * {@link BmsAttributes#DFHDFHI} (default highlight) are also {@code 0x00}, so an unset item and
         * an explicitly-defaulted one are indistinguishable in the bytes - which is true on the mainframe
         * too, and is therefore preserved rather than papered over.
         */
        public static final byte UNSET = LOW_VALUE_BYTE;

        /** {@code xxxC} - the COLOR item. */
        private byte colour;

        /** {@code xxxP} - the PS (programmed symbols) item. */
        private byte ps;

        /** {@code xxxH} - the HILIGHT item. */
        private byte hilight;

        /** {@code xxxV} - the VALIDN item. */
        private byte validn;

        /** A quad at its post-{@code MOVE LOW-VALUES} state: all four items binary zero. */
        public FieldAttributes() {
            this(UNSET, UNSET, UNSET, UNSET);
        }

        /**
         * A quad at stated values.
         *
         * @param colour  the {@code xxxC} COLOR byte
         * @param ps      the {@code xxxP} PS byte
         * @param hilight the {@code xxxH} HILIGHT byte
         * @param validn  the {@code xxxV} VALIDN byte
         */
        public FieldAttributes(byte colour, byte ps, byte hilight, byte validn) {
            this.colour = colour;
            this.ps = ps;
            this.hilight = hilight;
            this.validn = validn;
        }

        /**
         * A copy, so that deriving one response from another does not alias the other's quads.
         *
         * @param other the quad to copy
         * @throws NullPointerException if {@code other} is {@code null}
         */
        public FieldAttributes(FieldAttributes other) {
            Objects.requireNonNull(other, "A quad to copy is required");
            this.colour = other.colour;
            this.ps = other.ps;
            this.hilight = other.hilight;
            this.validn = other.validn;
        }

        /** @return the {@code xxxC} COLOR byte */
        public byte getColour() {
            return colour;
        }

        /**
         * Sets the COLOR byte - the destination {@code CSSETATY} moves {@link BmsAttributes#DFHRED} to.
         *
         * @param colour the colour byte
         */
        public void setColour(byte colour) {
            this.colour = colour;
        }

        /** @return the {@code xxxP} PS byte */
        public byte getPs() {
            return ps;
        }

        /**
         * Sets the PS byte.
         *
         * @param ps the programmed-symbols byte
         */
        public void setPs(byte ps) {
            this.ps = ps;
        }

        /** @return the {@code xxxH} HILIGHT byte */
        public byte getHilight() {
            return hilight;
        }

        /**
         * Sets the HILIGHT byte - {@link BmsAttributes#DFHBLINK}, {@link BmsAttributes#DFHREVRS} and
         * {@link BmsAttributes#DFHUNDLN} are the values a 3270 recognises.
         *
         * @param hilight the highlight byte
         */
        public void setHilight(byte hilight) {
            this.hilight = hilight;
        }

        /** @return the {@code xxxV} VALIDN byte */
        public byte getValidn() {
            return validn;
        }

        /**
         * Sets the VALIDN byte.
         *
         * @param validn the validation byte
         */
        public void setValidn(byte validn) {
            this.validn = validn;
        }

        /**
         * Returns all four items to LOW-VALUES, which is what {@code MOVE LOW-VALUES TO CACTUPAO}
         * [{@code app/cbl/COACTUPC.cbl:2668}] does to them at the top of every send.
         */
        public void resetToLowValues() {
            this.colour = UNSET;
            this.ps = UNSET;
            this.hilight = UNSET;
            this.validn = UNSET;
        }

        /**
         * Whether the COLOR byte carries {@link BmsAttributes#DFHRED} - that is, whether
         * {@code CSSETATY} has flagged this field as in error or blank while in REENTER context.
         *
         * @return {@code true} when the colour item is red
         */
        public boolean isRedHighlighted() {
            return colour == BmsAttributes.DFHRED;
        }

        /**
         * Whether the COLOR byte is the default, {@link BmsAttributes#DFHDFCOL}. Indistinguishable from
         * {@link #UNSET} in the bytes, as on the mainframe.
         *
         * @return {@code true} when the colour item is the default colour
         */
        public boolean isDefaultColour() {
            return colour == BmsAttributes.DFHDFCOL;
        }

        /**
         * Whether the HILIGHT byte asks for a visible emphasis rather than the default.
         *
         * @return {@code true} when the highlight item is not the default
         */
        public boolean isHighlighted() {
            return hilight != BmsAttributes.DFHDFHI;
        }

        /**
         * A rendering of all four items as mnemonics where {@link BmsAttributes} knows one and as hex
         * where it does not. No value is withheld: an attribute byte carries no personal data.
         *
         * @return the rendering; never {@code null}
         */
        @Override
        public String toString() {
            return "[C=" + BmsAttributes.colourMnemonic(colour)
                    + " P=" + BmsAttributes.toHex(ps)
                    + " H=" + BmsAttributes.highlightMnemonic(hilight)
                    + " V=" + BmsAttributes.toHex(validn) + ']';
        }

        /**
         * Value equality over all four bytes.
         *
         * @param other the object to compare with
         * @return {@code true} when all four items agree
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FieldAttributes that)) {
                return false;
            }
            return colour == that.colour && ps == that.ps
                    && hilight == that.hilight && validn == that.validn;
        }

        /**
         * A hash consistent with {@link #equals(Object)}.
         *
         * @return the hash of the four bytes
         */
        @Override
        public int hashCode() {
            return Objects.hash(colour, ps, hilight, validn);
        }
    }

    // =================================================================================================
    // Instance state.
    //
    // The 54 values are held in one EnumMap keyed by ScreenField rather than in 54 separate fields. The
    // public surface is unchanged - there is still one named accessor per field, in copybook order, with
    // the same names AccountUpdateRequest uses so the pair diffs cleanly - but every operation that has
    // to touch all 54 (the group-image codec, fieldValues, equality, the diff view) becomes one traversal
    // of ScreenField.values() instead of 54 hand-written arms. That matters for correctness, not brevity:
    // a field added to the mapset cannot then be left out of one of those operations by omission.
    // =================================================================================================

    /** The {@value #FIELD_COUNT} payload values, never {@code null} and never a short value. */
    private final Map<ScreenField, String> values;

    /** The {@value #FIELD_COUNT} attribute quads. Mutable in place; see {@link FieldAttributes}. */
    private final Map<ScreenField, FieldAttributes> attributes;

    /** {@code CDEMO-TO-PROGRAM} - the {@code XCTL} target, resolved client-side. */
    private final String nextProgram;

    /** The mapset the client should ask for next, seven characters. */
    private final String nextMapset;

    /** The map the client should ask for next, seven characters. */
    private final String nextMap;

    /** The 873-byte {@code WS-THIS-PROGCOMMAREA}, echoed so the client can send it back. */
    private final CommArea commArea;

    /** The 213-byte {@code CVCRD01Y} work area. */
    private final CardScreenState cardScreenState;

    /** The 160-byte {@code CARDDEMO-COMMAREA}, or {@code null} when none travelled. */
    private final NavigationContext navigationContext;

    /**
     * Assembles a response from a builder.
     *
     * <p>A {@code null} value becomes that field's declared width in LOW-VALUES, not spaces, because
     * {@code MOVE LOW-VALUES TO CACTUPAO} [{@code app/cbl/COACTUPC.cbl:2668}] is the state of every byte
     * of this group before the program writes anything into it. A value that is present is stored
     * <strong>exactly as given</strong> - not padded, not truncated and not trimmed, because padding and
     * truncating are {@code MOVE} semantics and belong where a {@code MOVE} is asked for, namely
     * {@link #normalize(FixedWidthCodec)} and {@link #toGroupImage(FixedWidthCodec)}.
     *
     * @param builder the builder; its maps are copied, not aliased
     */
    private AccountUpdateResponse(Builder builder) {
        Map<ScreenField, String> collected = new EnumMap<>(ScreenField.class);
        Map<ScreenField, FieldAttributes> quads = new EnumMap<>(ScreenField.class);
        for (ScreenField field : ScreenField.values()) {
            String supplied = builder.values.get(field);
            collected.put(field, supplied == null ? lowValues(field.length()) : supplied);
            FieldAttributes quad = builder.attributes.get(field);
            quads.put(field, quad == null ? new FieldAttributes() : new FieldAttributes(quad));
        }
        this.values = collected;
        this.attributes = quads;
        this.nextProgram = builder.nextProgram == null
                ? lowValues(NEXT_PROGRAM_LENGTH) : builder.nextProgram;
        this.nextMapset = builder.nextMapset == null
                ? lowValues(NEXT_MAPSET_LENGTH) : builder.nextMapset;
        this.nextMap = builder.nextMap == null ? lowValues(NEXT_MAP_LENGTH) : builder.nextMap;
        this.commArea = builder.commArea == null ? CommArea.initialised() : builder.commArea;
        this.cardScreenState = builder.cardScreenState == null
                ? new CardScreenState() : new CardScreenState(builder.cardScreenState);
        this.navigationContext = builder.navigationContext;
    }

    // =================================================================================================
    // Construction.
    // =================================================================================================

    /**
     * A fresh builder, every field unset.
     *
     * @return a builder; never {@code null}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The response as it stands immediately after {@code MOVE LOW-VALUES TO CACTUPAO}
     * [{@code app/cbl/COACTUPC.cbl:2668}]: all {@value #FIELD_COUNT} fields at their declared widths in
     * LOW-VALUES, all {@value #FIELD_COUNT} attribute quads unset, no navigation target, an initialised
     * communication area and an initialised card work area.
     *
     * @return the initial response; never {@code null}
     */
    public static AccountUpdateResponse initial() {
        return new Builder().build();
    }

    /**
     * The response {@code 3100-SCREEN-INIT} produces: the LOW-VALUES baseline, then the two titles, the
     * transaction identifier, the program name, the date and the time
     * [{@code app/cbl/COACTUPC.cbl:2668-2690}], then the three function-key legends the mapset declares
     * as {@code INITIAL} values.
     *
     * @param dateHeader the captured date and time, supplying the {@code MM/DD/YY} and {@code HH:MM:SS}
     *                   renderings
     * @return the initialised response
     * @throws NullPointerException if {@code dateHeader} is {@code null}
     */
    public static AccountUpdateResponse screenInit(DateHeader dateHeader) {
        Objects.requireNonNull(dateHeader, "A DateHeader is required for 3100-SCREEN-INIT: it supplies "
                + "the WS-CURDATE-MM-DD-YY and WS-CURTIME-HH-MM-SS renderings moved at "
                + "app/cbl/COACTUPC.cbl:2684 and :2690");
        return initial()
                .withScreenTitles()
                .withDateTimeHeader(dateHeader)
                .withFunctionKeyLegends();
    }

    /**
     * A builder pre-loaded with this response, for deriving a variant.
     *
     * @return a builder carrying every field, quad and carrier of this response
     */
    public Builder toBuilder() {
        Builder builder = new Builder();
        for (ScreenField field : ScreenField.values()) {
            builder.value(field, values.get(field));
            builder.attributes(field, new FieldAttributes(attributes.get(field)));
        }
        return builder.nextProgram(nextProgram)
                .nextMapset(nextMapset)
                .nextMap(nextMap)
                .commArea(commArea)
                .cardScreenState(cardScreenState)
                .navigationContext(navigationContext);
    }

    /**
     * A string of {@code length} spaces - what a {@code PIC X} field pads with.
     *
     * @param length how many spaces; never negative
     * @return exactly {@code length} spaces
     * @throws IllegalArgumentException if {@code length} is negative
     */
    public static String spaces(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("A field cannot be " + length + " characters wide, so "
                    + "there is no such thing as " + length + " spaces");
        }
        return String.valueOf(SPACE).repeat(length);
    }

    /**
     * A string of {@code length} LOW-VALUES characters - the state
     * {@code MOVE LOW-VALUES TO CACTUPAO} leaves a field in.
     *
     * <p>Distinct from {@link #spaces(int)} at the byte level, and both are kept reachable because a
     * field-by-field diff tells them apart. Distinct from {@code null} too: {@code null} means "not
     * supplied", LOW-VALUES means "binary zero to the declared width", and the two are not
     * interchangeable - the convention {@link CardScreenState} set for {@code CCARD-RETURN-MSG}.
     *
     * @param length how many characters; never negative
     * @return exactly {@code length} {@code U+0000} characters
     * @throws IllegalArgumentException if {@code length} is negative
     */
    public static String lowValues(int length) {
        // One implementation of the LOW-VALUES image, in common.ScreenFieldImage, so the choice cannot
        // drift back apart across screens. Any width validation above is this method's own contract.
        if (length < 0) {
            throw new IllegalArgumentException("A field cannot be " + length + " characters wide, so "
                    + "there is no such thing as " + length + " LOW-VALUES characters");
        }
        return ScreenFieldImage.unpainted(length);
    }

    /**
     * The declared width of a field, without needing an instance.
     *
     * @param field the field
     * @return its {@code xxxO} {@code PICTURE} width
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public static int declaredLength(ScreenField field) {
        Objects.requireNonNull(field, "A field is required to report a declared length");
        return field.length();
    }

    // =================================================================================================
    // The 54 payload accessors, in symbolic-map order. One per name-labelled DFHMDF field, named exactly
    // as AccountUpdateRequest names its counterpart so that a reviewer diffing the pair sees the same 54
    // names in the same order at the same widths - the only permitted structural difference being the
    // metadata carriers (xxxL/xxxF/xxxA there, xxxC/xxxP/xxxH/xxxV here) and the navigation trio below.
    //
    // @Size(max = ...) is the ONLY constraint on any of them. There is no @Pattern, no @Digits and no
    // custom validator, because CSSETATY writes a bare '*' into a blank field's payload item at 39 sites
    // and a value of "*" must round-trip through this type untouched. An all-spaces value and a
    // LOW-VALUES value must likewise be accepted.
    // =================================================================================================
    /**
     * {@code TRNNAMEO PIC X({@value #TRNNAME_LENGTH})} - see {@link ScreenField#TRNNAME} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = TRNNAME_LENGTH)
    public String getTrnname() {
        return values.get(ScreenField.TRNNAME);
    }
    /**
     * {@code TITLE01O PIC X({@value #TITLE01_LENGTH})} - see {@link ScreenField#TITLE01} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = TITLE01_LENGTH)
    public String getTitle01() {
        return values.get(ScreenField.TITLE01);
    }
    /**
     * {@code CURDATEO PIC X({@value #CURDATE_LENGTH})} - see {@link ScreenField#CURDATE} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = CURDATE_LENGTH)
    public String getCurdate() {
        return values.get(ScreenField.CURDATE);
    }
    /**
     * {@code PGMNAMEO PIC X({@value #PGMNAME_LENGTH})} - see {@link ScreenField#PGMNAME} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = PGMNAME_LENGTH)
    public String getPgmname() {
        return values.get(ScreenField.PGMNAME);
    }
    /**
     * {@code TITLE02O PIC X({@value #TITLE02_LENGTH})} - see {@link ScreenField#TITLE02} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = TITLE02_LENGTH)
    public String getTitle02() {
        return values.get(ScreenField.TITLE02);
    }
    /**
     * {@code CURTIMEO PIC X({@value #CURTIME_LENGTH})} - see {@link ScreenField#CURTIME} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = CURTIME_LENGTH)
    public String getCurtime() {
        return values.get(ScreenField.CURTIME);
    }
    /**
     * {@code ACCTSIDO PIC X({@value #ACCTSID_LENGTH})} - see {@link ScreenField#ACCTSID} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACCTSID_LENGTH)
    public String getAcctsid() {
        return values.get(ScreenField.ACCTSID);
    }
    /**
     * {@code ACSTTUSO PIC X({@value #ACSTTUS_LENGTH})} - see {@link ScreenField#ACSTTUS} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSTTUS_LENGTH)
    public String getAcsttus() {
        return values.get(ScreenField.ACSTTUS);
    }
    /**
     * {@code OPNYEARO PIC X({@value #OPNYEAR_LENGTH})} - see {@link ScreenField#OPNYEAR} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = OPNYEAR_LENGTH)
    public String getOpnyear() {
        return values.get(ScreenField.OPNYEAR);
    }
    /**
     * {@code OPNMONO PIC X({@value #OPNMON_LENGTH})} - see {@link ScreenField#OPNMON} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = OPNMON_LENGTH)
    public String getOpnmon() {
        return values.get(ScreenField.OPNMON);
    }
    /**
     * {@code OPNDAYO PIC X({@value #OPNDAY_LENGTH})} - see {@link ScreenField#OPNDAY} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = OPNDAY_LENGTH)
    public String getOpnday() {
        return values.get(ScreenField.OPNDAY);
    }
    /**
     * {@code ACRDLIMO PIC X({@value #ACRDLIM_LENGTH})} - see {@link ScreenField#ACRDLIM} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACRDLIM_LENGTH)
    public String getAcrdlim() {
        return values.get(ScreenField.ACRDLIM);
    }
    /**
     * {@code EXPYEARO PIC X({@value #EXPYEAR_LENGTH})} - see {@link ScreenField#EXPYEAR} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = EXPYEAR_LENGTH)
    public String getExpyear() {
        return values.get(ScreenField.EXPYEAR);
    }
    /**
     * {@code EXPMONO PIC X({@value #EXPMON_LENGTH})} - see {@link ScreenField#EXPMON} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = EXPMON_LENGTH)
    public String getExpmon() {
        return values.get(ScreenField.EXPMON);
    }
    /**
     * {@code EXPDAYO PIC X({@value #EXPDAY_LENGTH})} - see {@link ScreenField#EXPDAY} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = EXPDAY_LENGTH)
    public String getExpday() {
        return values.get(ScreenField.EXPDAY);
    }
    /**
     * {@code ACSHLIMO PIC X({@value #ACSHLIM_LENGTH})} - see {@link ScreenField#ACSHLIM} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSHLIM_LENGTH)
    public String getAcshlim() {
        return values.get(ScreenField.ACSHLIM);
    }
    /**
     * {@code RISYEARO PIC X({@value #RISYEAR_LENGTH})} - see {@link ScreenField#RISYEAR} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = RISYEAR_LENGTH)
    public String getRisyear() {
        return values.get(ScreenField.RISYEAR);
    }
    /**
     * {@code RISMONO PIC X({@value #RISMON_LENGTH})} - see {@link ScreenField#RISMON} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = RISMON_LENGTH)
    public String getRismon() {
        return values.get(ScreenField.RISMON);
    }
    /**
     * {@code RISDAYO PIC X({@value #RISDAY_LENGTH})} - see {@link ScreenField#RISDAY} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = RISDAY_LENGTH)
    public String getRisday() {
        return values.get(ScreenField.RISDAY);
    }
    /**
     * {@code ACURBALO PIC X({@value #ACURBAL_LENGTH})} - see {@link ScreenField#ACURBAL} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACURBAL_LENGTH)
    public String getAcurbal() {
        return values.get(ScreenField.ACURBAL);
    }
    /**
     * {@code ACRCYCRO PIC X({@value #ACRCYCR_LENGTH})} - see {@link ScreenField#ACRCYCR} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACRCYCR_LENGTH)
    public String getAcrcycr() {
        return values.get(ScreenField.ACRCYCR);
    }
    /**
     * {@code AADDGRPO PIC X({@value #AADDGRP_LENGTH})} - see {@link ScreenField#AADDGRP} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = AADDGRP_LENGTH)
    public String getAaddgrp() {
        return values.get(ScreenField.AADDGRP);
    }
    /**
     * {@code ACRCYDBO PIC X({@value #ACRCYDB_LENGTH})} - see {@link ScreenField#ACRCYDB} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACRCYDB_LENGTH)
    public String getAcrcydb() {
        return values.get(ScreenField.ACRCYDB);
    }
    /**
     * {@code ACSTNUMO PIC X({@value #ACSTNUM_LENGTH})} - see {@link ScreenField#ACSTNUM} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSTNUM_LENGTH)
    public String getAcstnum() {
        return values.get(ScreenField.ACSTNUM);
    }
    /**
     * {@code ACTSSN1O PIC X({@value #ACTSSN1_LENGTH})} - see {@link ScreenField#ACTSSN1} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACTSSN1_LENGTH)
    public String getActssn1() {
        return values.get(ScreenField.ACTSSN1);
    }
    /**
     * {@code ACTSSN2O PIC X({@value #ACTSSN2_LENGTH})} - see {@link ScreenField#ACTSSN2} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACTSSN2_LENGTH)
    public String getActssn2() {
        return values.get(ScreenField.ACTSSN2);
    }
    /**
     * {@code ACTSSN3O PIC X({@value #ACTSSN3_LENGTH})} - see {@link ScreenField#ACTSSN3} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACTSSN3_LENGTH)
    public String getActssn3() {
        return values.get(ScreenField.ACTSSN3);
    }
    /**
     * {@code DOBYEARO PIC X({@value #DOBYEAR_LENGTH})} - see {@link ScreenField#DOBYEAR} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = DOBYEAR_LENGTH)
    public String getDobyear() {
        return values.get(ScreenField.DOBYEAR);
    }
    /**
     * {@code DOBMONO PIC X({@value #DOBMON_LENGTH})} - see {@link ScreenField#DOBMON} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = DOBMON_LENGTH)
    public String getDobmon() {
        return values.get(ScreenField.DOBMON);
    }
    /**
     * {@code DOBDAYO PIC X({@value #DOBDAY_LENGTH})} - see {@link ScreenField#DOBDAY} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = DOBDAY_LENGTH)
    public String getDobday() {
        return values.get(ScreenField.DOBDAY);
    }
    /**
     * {@code ACSTFCOO PIC X({@value #ACSTFCO_LENGTH})} - see {@link ScreenField#ACSTFCO} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSTFCO_LENGTH)
    public String getAcstfco() {
        return values.get(ScreenField.ACSTFCO);
    }
    /**
     * {@code ACSFNAMO PIC X({@value #ACSFNAM_LENGTH})} - see {@link ScreenField#ACSFNAM} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSFNAM_LENGTH)
    public String getAcsfnam() {
        return values.get(ScreenField.ACSFNAM);
    }
    /**
     * {@code ACSMNAMO PIC X({@value #ACSMNAM_LENGTH})} - see {@link ScreenField#ACSMNAM} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSMNAM_LENGTH)
    public String getAcsmnam() {
        return values.get(ScreenField.ACSMNAM);
    }
    /**
     * {@code ACSLNAMO PIC X({@value #ACSLNAM_LENGTH})} - see {@link ScreenField#ACSLNAM} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSLNAM_LENGTH)
    public String getAcslnam() {
        return values.get(ScreenField.ACSLNAM);
    }
    /**
     * {@code ACSADL1O PIC X({@value #ACSADL1_LENGTH})} - see {@link ScreenField#ACSADL1} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSADL1_LENGTH)
    public String getAcsadl1() {
        return values.get(ScreenField.ACSADL1);
    }
    /**
     * {@code ACSSTTEO PIC X({@value #ACSSTTE_LENGTH})} - see {@link ScreenField#ACSSTTE} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSSTTE_LENGTH)
    public String getAcsstte() {
        return values.get(ScreenField.ACSSTTE);
    }
    /**
     * {@code ACSADL2O PIC X({@value #ACSADL2_LENGTH})} - see {@link ScreenField#ACSADL2} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSADL2_LENGTH)
    public String getAcsadl2() {
        return values.get(ScreenField.ACSADL2);
    }
    /**
     * {@code ACSZIPCO PIC X({@value #ACSZIPC_LENGTH})} - see {@link ScreenField#ACSZIPC} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSZIPC_LENGTH)
    public String getAcszipc() {
        return values.get(ScreenField.ACSZIPC);
    }
    /**
     * {@code ACSCITYO PIC X({@value #ACSCITY_LENGTH})} - see {@link ScreenField#ACSCITY} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSCITY_LENGTH)
    public String getAcscity() {
        return values.get(ScreenField.ACSCITY);
    }
    /**
     * {@code ACSCTRYO PIC X({@value #ACSCTRY_LENGTH})} - see {@link ScreenField#ACSCTRY} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSCTRY_LENGTH)
    public String getAcsctry() {
        return values.get(ScreenField.ACSCTRY);
    }
    /**
     * {@code ACSPH1AO PIC X({@value #ACSPH1A_LENGTH})} - see {@link ScreenField#ACSPH1A} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSPH1A_LENGTH)
    public String getAcsph1a() {
        return values.get(ScreenField.ACSPH1A);
    }
    /**
     * {@code ACSPH1BO PIC X({@value #ACSPH1B_LENGTH})} - see {@link ScreenField#ACSPH1B} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSPH1B_LENGTH)
    public String getAcsph1b() {
        return values.get(ScreenField.ACSPH1B);
    }
    /**
     * {@code ACSPH1CO PIC X({@value #ACSPH1C_LENGTH})} - see {@link ScreenField#ACSPH1C} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSPH1C_LENGTH)
    public String getAcsph1c() {
        return values.get(ScreenField.ACSPH1C);
    }
    /**
     * {@code ACSGOVTO PIC X({@value #ACSGOVT_LENGTH})} - see {@link ScreenField#ACSGOVT} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSGOVT_LENGTH)
    public String getAcsgovt() {
        return values.get(ScreenField.ACSGOVT);
    }
    /**
     * {@code ACSPH2AO PIC X({@value #ACSPH2A_LENGTH})} - see {@link ScreenField#ACSPH2A} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSPH2A_LENGTH)
    public String getAcsph2a() {
        return values.get(ScreenField.ACSPH2A);
    }
    /**
     * {@code ACSPH2BO PIC X({@value #ACSPH2B_LENGTH})} - see {@link ScreenField#ACSPH2B} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSPH2B_LENGTH)
    public String getAcsph2b() {
        return values.get(ScreenField.ACSPH2B);
    }
    /**
     * {@code ACSPH2CO PIC X({@value #ACSPH2C_LENGTH})} - see {@link ScreenField#ACSPH2C} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSPH2C_LENGTH)
    public String getAcsph2c() {
        return values.get(ScreenField.ACSPH2C);
    }
    /**
     * {@code ACSEFTCO PIC X({@value #ACSEFTC_LENGTH})} - see {@link ScreenField#ACSEFTC} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSEFTC_LENGTH)
    public String getAcseftc() {
        return values.get(ScreenField.ACSEFTC);
    }
    /**
     * {@code ACSPFLGO PIC X({@value #ACSPFLG_LENGTH})} - see {@link ScreenField#ACSPFLG} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSPFLG_LENGTH)
    public String getAcspflg() {
        return values.get(ScreenField.ACSPFLG);
    }
    /**
     * {@code INFOMSGO PIC X({@value #INFOMSG_LENGTH})} - see {@link ScreenField#INFOMSG} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = INFOMSG_LENGTH)
    public String getInfomsg() {
        return values.get(ScreenField.INFOMSG);
    }
    /**
     * {@code ERRMSGO PIC X({@value #ERRMSG_LENGTH})} - see {@link ScreenField#ERRMSG} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ERRMSG_LENGTH)
    public String getErrmsg() {
        return values.get(ScreenField.ERRMSG);
    }
    /**
     * {@code FKEYSO PIC X({@value #FKEYS_LENGTH})} - see {@link ScreenField#FKEYS} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = FKEYS_LENGTH)
    public String getFkeys() {
        return values.get(ScreenField.FKEYS);
    }
    /**
     * {@code FKEY05O PIC X({@value #FKEY05_LENGTH})} - see {@link ScreenField#FKEY05} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = FKEY05_LENGTH)
    public String getFkey05() {
        return values.get(ScreenField.FKEY05);
    }
    /**
     * {@code FKEY12O PIC X({@value #FKEY12_LENGTH})} - see {@link ScreenField#FKEY12} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = FKEY12_LENGTH)
    public String getFkey12() {
        return values.get(ScreenField.FKEY12);
    }

    // =================================================================================================
    // Field addressing. The compiler-checked way to reach a field, for the group-image codec, a parity
    // differ, a highlight routine and anything else that iterates.
    // =================================================================================================

    /**
     * One field's value, as stored.
     *
     * @param field the field
     * @return the value; never {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public String value(ScreenField field) {
        Objects.requireNonNull(field, "A field is required to read a value");
        return values.get(field);
    }

    /**
     * This response with one field replaced, everything else carried over.
     *
     * <p>This is how {@code CSSETATY}'s second destination is written: a blank field in REENTER context
     * receives {@link #BLANK_FIELD_MARKER}, which is a perfectly ordinary value as far as this type is
     * concerned. See {@link #applyHighlight(ScreenField, FieldValidationState, boolean)}, which writes
     * both destinations together.
     *
     * @param field the field to replace
     * @param value the new value; {@code null} restores the field's declared width in LOW-VALUES
     * @return a new response; this one is unchanged
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public AccountUpdateResponse withValue(ScreenField field, String value) {
        Objects.requireNonNull(field, "A field is required to replace a value");
        return toBuilder().value(field, value).build();
    }

    /**
     * All {@value #FIELD_COUNT} values keyed by {@code DFHMDF} label, in copybook order.
     *
     * <p>Ordered and label-keyed because this is what a field-by-field differ consumes: the key is the
     * name a diff reports, and the order is the one a reviewer reads the copybook in.
     *
     * @return an unmodifiable, insertion-ordered map
     */
    @JsonIgnore
    public Map<String, String> fieldValues() {
        Map<String, String> rendered = new LinkedHashMap<>();
        for (ScreenField field : ScreenField.values()) {
            rendered.put(field.label(), values.get(field));
        }
        return Collections.unmodifiableMap(rendered);
    }

    /**
     * One field's live attribute quad.
     *
     * <p><strong>The returned quad is mutable and is this response's own.</strong> That is deliberate and
     * it is the one departure from this type's immutability: {@code app/cbl/COACTUPC.cbl:3568},
     * {@code :3570}, {@code :3575} and {@code :3579-3580} all write attribute bytes after the map has been
     * populated, so
     * {@code response.attributes(ScreenField.INFOMSG).setColour(BmsAttributes.DFHBMDAR)} has to work on a
     * built payload. Excluded from JSON because a quad is highlight metadata, not screen data.
     *
     * @param field the field
     * @return the live quad; never {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public FieldAttributes attributes(ScreenField field) {
        Objects.requireNonNull(field, "A field is required to read an attribute quad");
        return attributes.get(field);
    }

    /**
     * All {@value #FIELD_COUNT} live quads, keyed by field.
     *
     * <p>The map itself is unmodifiable - a quad cannot be swapped for another - but each quad in it is
     * live and writable, for the reason given on {@link #attributes(ScreenField)}.
     *
     * @return an unmodifiable map over the live quads
     */
    @JsonIgnore
    public Map<ScreenField, FieldAttributes> attributeQuads() {
        return Collections.unmodifiableMap(attributes);
    }

    /**
     * Returns every quad to LOW-VALUES, which is what {@code MOVE LOW-VALUES TO CACTUPAO}
     * [{@code app/cbl/COACTUPC.cbl:2668}] does to all {@value #FIELD_COUNT} of them.
     *
     * <p>Acts in place, on the quads only. The {@value #FIELD_COUNT} field values are untouched; use
     * {@link #initial()} for the full move.
     */
    public void resetAttributeQuads() {
        for (ScreenField field : ScreenField.values()) {
            attributes.get(field).resetToLowValues();
        }
    }

    // =================================================================================================
    // Error highlighting - CSSETATY's two destinations, written together.
    // =================================================================================================

    /**
     * Applies {@code CSSETATY} to one field: the decision is
     * {@link FieldAttributeSetter}'s, the two destinations are this type's.
     *
     * <p>The copybook, verbatim from {@code app/cpy/CSSETATY.cpy:18-27}:
     *
     * <pre>
     *   IF (FLG-(TESTVAR1)-NOT-OK OR FLG-(TESTVAR1)-BLANK)
     *   AND CDEMO-PGM-REENTER
     *       MOVE DFHRED  TO (SCRNVAR2)C OF (MAPNAME3)O
     *       IF  FLG-(TESTVAR1)-BLANK
     *           MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O
     *       END-IF
     *   END-IF
     * </pre>
     *
     * <p>So there are two writes, not one: {@link BmsAttributes#DFHRED} into the {@code xxxC} colour byte
     * whenever the field is not OK <em>or</em> blank, and a bare asterisk into the {@code xxxO} payload
     * item only when it is blank. Both are conditional on REENTER context - which is gate
     * <strong>G38</strong>, and why {@code reenter} is an explicit parameter rather than something
     * inferred here.
     *
     * <p>The condition is <strong>not</strong> re-implemented in this file: it is delegated to
     * {@link FieldAttributeSetter#resolve(FieldValidationState, boolean)}, whose 39 expansion sites in
     * {@code COACTUPC} it exists to represent (gate <strong>G51</strong>).
     *
     * <p>Because the asterisk lands in the payload, this returns a new response rather than acting in
     * place; the colour byte is written into this response's own live quad, matching the COBOL, where both
     * writes target the same map area.
     *
     * @param field   the field being edited
     * @param state   its validation state, from the program's {@code FLG-xxx} condition names
     * @param reenter whether {@code CDEMO-PGM-REENTER} holds
     * @return a response with the asterisk applied where the copybook applies it; the same values
     *         otherwise
     * @throws NullPointerException if {@code field} or {@code state} is {@code null}
     */
    public AccountUpdateResponse applyHighlight(ScreenField field,
                                                FieldValidationState state,
                                                boolean reenter) {
        Objects.requireNonNull(field, "A field is required to apply CSSETATY");
        Objects.requireNonNull(state, "A validation state is required to apply CSSETATY; it is what the "
                + "program's FLG-xxx-NOT-OK and FLG-xxx-BLANK condition names carry");
        FieldHighlight highlight =
                FieldAttributeSetter.resolve(state, reenter, field.label(), MAP_NAME);
        if (highlight.colourItemAssigned()) {
            attributes.get(field).setColour(highlight.colourItemValue());
        }
        if (highlight.outputItemAssigned()) {
            return withValue(field, highlight.outputItemValue());
        }
        return this;
    }

    // =================================================================================================
    // 3100-SCREEN-INIT's value projection. Recorded here because these six moves are unconditional and
    // identical on every send; every other field's value is decided by 3200-SETUP-SCREEN-VARS and is the
    // controller's and service's business (gate G51).
    // =================================================================================================

    /**
     * The two title moves of {@code 3100-SCREEN-INIT}: {@link ScreenTitles#CCDA_TITLE01} into
     * {@code TITLE01O} [{@code app/cbl/COACTUPC.cbl:2673}] and {@link ScreenTitles#CCDA_TITLE02} into
     * {@code TITLE02O} [{@code :2674}], plus {@code LIT-THISTRANID} into {@code TRNNAMEO} [{@code :2675}]
     * and {@code LIT-THISPGM} into {@code PGMNAMEO} [{@code :2676}].
     *
     * <p>All four are exact width matches - the titles are {@link ScreenTitles#TITLE_LENGTH} characters
     * against {@value #TITLE01_LENGTH}, the transaction identifier four against {@value #TRNNAME_LENGTH},
     * the program name eight against {@value #PGMNAME_LENGTH} - so no move truncates or pads.
     *
     * @return a response carrying the four header values
     */
    public AccountUpdateResponse withScreenTitles() {
        return toBuilder()
                .title01(ScreenTitles.CCDA_TITLE01)
                .title02(ScreenTitles.CCDA_TITLE02)
                .trnname(THIS_TRANSACTION)
                .pgmname(THIS_PROGRAM)
                .build();
    }

    /**
     * The date and time moves of {@code 3100-SCREEN-INIT}: {@code WS-CURDATE-MM-DD-YY} into
     * {@code CURDATEO} [{@code app/cbl/COACTUPC.cbl:2684}] and {@code WS-CURTIME-HH-MM-SS} into
     * {@code CURTIMEO} [{@code :2690}].
     *
     * <p>Both renderings are eight characters, which is exactly {@value #CURDATE_LENGTH} and
     * {@value #CURTIME_LENGTH}, and both replace the mapset's {@code INITIAL='mm/dd/yy'} and
     * {@code INITIAL='hh:mm:ss'} placeholders.
     *
     * @param dateHeader the captured date and time
     * @return a response carrying the date and time header
     * @throws NullPointerException if {@code dateHeader} is {@code null}
     */
    public AccountUpdateResponse withDateTimeHeader(DateHeader dateHeader) {
        Objects.requireNonNull(dateHeader, "A DateHeader is required: it supplies the MM/DD/YY and "
                + "HH:MM:SS renderings moved at app/cbl/COACTUPC.cbl:2684 and :2690");
        return toBuilder()
                .curdate(dateHeader.wsCurdateMmDdYy())
                .curtime(dateHeader.wsCurtimeHhMmSs())
                .build();
    }

    /**
     * The three function-key legends the mapset declares as {@code INITIAL} values.
     *
     * <p>{@code FKEYS} is {@code ATTRB=(ASKIP,NORM)} and always visible; {@code FKEY05} and
     * {@code FKEY12} are {@code ATTRB=(ASKIP,DRK)} and start hidden. Setting a legend's <em>value</em> does
     * not make it visible - visibility is carried by its attribute byte, which
     * {@code 3390-SETUP-INFOMSG-ATTRS} sets by moving {@link BmsAttributes#DFHBMASB} in at
     * {@code app/cbl/COACTUPC.cbl:3575} and {@code :3579-3580}. See {@link #revealSaveLegend()} and
     * {@link #revealCancelLegend()}.
     *
     * @return a response carrying the three legends at their mapset-declared values
     */
    public AccountUpdateResponse withFunctionKeyLegends() {
        return toBuilder()
                .fkeys(FKEYS_LEGEND)
                .fkey05(FKEY05_LEGEND)
                .fkey12(FKEY12_LEGEND)
                .build();
    }

    /**
     * Reveals the {@code F5=Save} legend by moving {@link BmsAttributes#DFHBMASB} into
     * {@code FKEY05}'s highlight item - {@code app/cbl/COACTUPC.cbl:3579}, taken when
     * {@code PROMPT-FOR-CONFIRMATION} holds.
     *
     * <p>Acts in place on the quad, because the COBOL writes an attribute byte and not a value.
     */
    public void revealSaveLegend() {
        attributes.get(ScreenField.FKEY05).setHilight(BmsAttributes.DFHBMASB);
    }

    /**
     * Reveals the {@code F12=Cancel} legend - {@code app/cbl/COACTUPC.cbl:3575}, taken when changes have
     * been made but are not yet done, and again at {@code :3580} when confirmation is prompted for.
     */
    public void revealCancelLegend() {
        attributes.get(ScreenField.FKEY12).setHilight(BmsAttributes.DFHBMASB);
    }

    /**
     * Whether the save legend has been revealed.
     *
     * @return {@code true} when {@code FKEY05}'s highlight item carries
     *         {@link BmsAttributes#DFHBMASB}
     */
    @JsonIgnore
    public boolean isSaveLegendRevealed() {
        return attributes.get(ScreenField.FKEY05).getHilight() == BmsAttributes.DFHBMASB;
    }

    /**
     * Whether the cancel legend has been revealed.
     *
     * @return {@code true} when {@code FKEY12}'s highlight item carries
     *         {@link BmsAttributes#DFHBMASB}
     */
    @JsonIgnore
    public boolean isCancelLegendRevealed() {
        return attributes.get(ScreenField.FKEY12).getHilight() == BmsAttributes.DFHBMASB;
    }

    /**
     * Sets the informational-message line's visibility the way
     * {@code 3390-SETUP-INFOMSG-ATTRS} does: {@link BmsAttributes#DFHBMDAR} when there is no message and
     * {@link BmsAttributes#DFHBMASB} when there is [{@code app/cbl/COACTUPC.cbl:3567-3571}].
     *
     * <p>Acts in place on {@code INFOMSG}'s quad. The decision - whether {@code WS-NO-INFO-MESSAGE} holds
     * - stays with the caller, because it is a program state and not a property of this payload.
     *
     * @param hasInfoMessage {@code true} when a message is present, so the line should be visible
     */
    public void applyInfoMessageVisibility(boolean hasInfoMessage) {
        attributes.get(ScreenField.INFOMSG)
                .setHilight(hasInfoMessage ? BmsAttributes.DFHBMASB : BmsAttributes.DFHBMDAR);
    }

    // =================================================================================================
    // Navigation - what EXEC CICS XCTL becomes when the server keeps no state (rule R6, gates G37/G40).
    // =================================================================================================

    /**
     * {@code CDEMO-TO-PROGRAM} - the program {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)}
     * [{@code app/cbl/COACTUPC.cbl:956-958}] would have transferred to.
     *
     * <p>The server performs no forward, issues no redirect and holds no affinity; the client reads this
     * and makes the next call. {@code app/cbl/COACTUPC.cbl:937-942} shows how the value is chosen -
     * {@code CDEMO-FROM-PROGRAM} when one arrived, otherwise {@code LIT-MENUPGM}.
     *
     * @return eight characters; never {@code null}
     */
    @Size(max = NEXT_PROGRAM_LENGTH)
    public String getNextProgram() {
        return nextProgram;
    }

    /**
     * The mapset the client should ask for next.
     *
     * <p>Seven characters, because {@code CDEMO-LAST-MAPSET} is {@code PIC X(7)} and the move from the
     * eight-character {@code LIT-THISMAPSET} at {@code app/cbl/COACTUPC.cbl:949} discards the trailing
     * space.
     *
     * @return seven characters; never {@code null}
     */
    @Size(max = NEXT_MAPSET_LENGTH)
    public String getNextMapset() {
        return nextMapset;
    }

    /**
     * The map the client should ask for next - {@code CDEMO-LAST-MAP PIC X(7)}, moved at
     * {@code app/cbl/COACTUPC.cbl:950}, and the same seven characters {@code 3400-SEND-SCREEN} puts in
     * {@code CCARD-NEXT-MAP} at {@code :3592}.
     *
     * @return seven characters; never {@code null}
     */
    @Size(max = NEXT_MAP_LENGTH)
    public String getNextMap() {
        return nextMap;
    }

    /**
     * This response naming a different {@code XCTL} target.
     *
     * @param program the next program; {@code null} clears it to LOW-VALUES
     * @param mapset  the next mapset; {@code null} clears it to LOW-VALUES
     * @param map     the next map; {@code null} clears it to LOW-VALUES
     * @return a new response
     */
    public AccountUpdateResponse withNextTarget(String program, String mapset, String map) {
        return toBuilder().nextProgram(program).nextMapset(mapset).nextMap(map).build();
    }

    /**
     * This response naming <em>this</em> screen as the next target - what
     * {@code 3400-SEND-SCREEN} does at {@code app/cbl/COACTUPC.cbl:3591-3592} when it re-paints rather
     * than transferring.
     *
     * <p>The mapset is {@link #MAPSET_NAME}, the seven-character form, not the eight-character
     * {@link #THIS_MAPSET_LITERAL} - the same truncation the COBOL move performs.
     *
     * @return a new response pointing back at {@code CACTUPA}
     */
    public AccountUpdateResponse withSelfAsNextTarget() {
        return withNextTarget(THIS_PROGRAM, MAPSET_NAME, MAP_NAME);
    }

    // =================================================================================================
    // The three state carriers. All echoed in the payload; none held server-side (gates G37, G53).
    // =================================================================================================

    /**
     * The {@value CommArea#RECORD_LENGTH}-byte {@code WS-THIS-PROGCOMMAREA}
     * [{@code app/cbl/COACTUPC.cbl:652}], echoed so the client can send it back on the next turn.
     *
     * <p>{@code COMMON-RETURN} writes it into {@code WS-COMMAREA} past the
     * {@value NavigationContext#COMMAREA_LENGTH}-byte {@code CARDDEMO-COMMAREA} at {@code :1010-1013},
     * and the next invocation reads it back out of {@code DFHCOMMAREA} at the same offset at
     * {@code :888-892}. That round trip is the whole reason this field exists.
     *
     * <p>It is {@link AccountUpdateRequest}'s nested {@link CommArea}, imported rather than re-declared:
     * one Java type per copybook, so the before- and after-images the client sends and the ones the server
     * returns are the same type at the same offsets. Its {@link CommArea#changeAction()} is how
     * {@code AccountUpdateService} reports its outcome, including the
     * {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE} and {@code COULD-NOT-LOCK-CUST-FOR-UPDATE} paths
     * ({@code 'L'}) and the {@code DATA-WAS-CHANGED-BEFORE-UPDATE} result of
     * {@code 9700-CHECK-CHANGE-IN-REC} ({@code 'F'}).
     *
     * @return the communication area; never {@code null}
     */
    public CommArea getCommArea() {
        return commArea;
    }

    /**
     * This response carrying a different communication area.
     *
     * @param replacement the area; {@code null} restores {@link CommArea#initialised()}, which is what
     *                    {@code INITIALIZE WS-THIS-PROGCOMMAREA} does at {@code app/cbl/COACTUPC.cbl:968}
     *                    and {@code :981}
     * @return a new response
     */
    public AccountUpdateResponse withCommArea(CommArea replacement) {
        return toBuilder().commArea(replacement).build();
    }

    /**
     * The {@value CardScreenState#RECORD_LENGTH}-byte {@code CVCRD01Y} work area.
     *
     * <p>A defensive copy: {@link CardScreenState} is mutable by design, so handing out the instance
     * itself would let a caller reach inside this response.
     *
     * @return a copy of the card work area; never {@code null}
     */
    public CardScreenState getCardScreenState() {
        return new CardScreenState(cardScreenState);
    }

    /**
     * This response carrying a different card work area.
     *
     * @param replacement the work area; {@code null} restores a fresh one
     * @return a new response
     */
    public AccountUpdateResponse withCardScreenState(CardScreenState replacement) {
        return toBuilder().cardScreenState(replacement).build();
    }

    /**
     * The {@value NavigationContext#COMMAREA_LENGTH}-byte {@code CARDDEMO-COMMAREA}, or {@code null} when
     * none travelled - which is the {@code EIBCALEN IS EQUAL TO 0} case at
     * {@code app/cbl/COACTUPC.cbl:880}.
     *
     * <p>{@code app/cbl/COACTUPC.cbl:944-950} stamps {@code CDEMO-FROM-TRANID},
     * {@code CDEMO-FROM-PROGRAM}, {@code CDEMO-LAST-MAPSET} and {@code CDEMO-LAST-MAP} into it before
     * returning, so what comes back is not what arrived.
     *
     * @return the navigation context, or {@code null}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    /**
     * This response carrying a different navigation context.
     *
     * @param replacement the context, or {@code null} for none
     * @return a new response
     */
    public AccountUpdateResponse withNavigationContext(NavigationContext replacement) {
        return toBuilder().navigationContext(replacement).build();
    }

    /**
     * Whether a navigation context travelled.
     *
     * @return {@code true} when one is present
     */
    @JsonIgnore
    public boolean hasNavigationContext() {
        return navigationContext != null;
    }

    /**
     * The bytes this response would fill of {@code WS-COMMAREA PIC X(2000)}: the communication area
     * always, plus the navigation context when one travelled.
     *
     * @return {@value #TOTAL_COMMAREA_LENGTH} when a context is present, otherwise
     *         {@value CommArea#RECORD_LENGTH}
     */
    @JsonIgnore
    public int commareaLength() {
        return hasNavigationContext()
                ? TOTAL_COMMAREA_LENGTH
                : CommArea.RECORD_LENGTH;
    }

    /**
     * Whether {@code CDEMO-PGM-ENTER} holds - the first-entry path, on which the screen is painted and no
     * highlight is applied.
     *
     * <p>False when no navigation context travelled, because {@code CDEMO-PGM-CONTEXT} has nowhere to live
     * then; {@code app/cbl/COACTUPC.cbl:885} sets ENTER explicitly in that case, so a caller with no
     * context should treat the state as ENTER by construction rather than read it from here.
     *
     * @return {@code true} when a context is present and reports ENTER
     */
    @JsonIgnore
    public boolean isEnter() {
        return navigationContext != null && navigationContext.isEnter();
    }

    /**
     * Whether {@code CDEMO-PGM-REENTER} holds - the re-entry path, on which {@code CSSETATY} applies its
     * highlight. This is the boolean gate <strong>G38</strong> asks to see driven both ways.
     *
     * @return {@code true} when a context is present and reports REENTER
     */
    @JsonIgnore
    public boolean isReenter() {
        return navigationContext != null && navigationContext.isReenter();
    }

    // =================================================================================================
    // The before- and after-images, reached through the reused snapshot types. Convenience only: the
    // 9700-CHECK-CHANGE-IN-REC comparison itself is AccountUpdateService's (gates G43, G51).
    // =================================================================================================

    /**
     * The {@value Details#RECORD_LENGTH}-byte {@code ACUP-OLD-DETAILS} before-image.
     *
     * @return the OLD details; never {@code null}
     */
    @JsonIgnore
    public Details oldDetails() {
        return commArea.oldDetails();
    }

    /**
     * The {@value Details#RECORD_LENGTH}-byte {@code ACUP-NEW-DETAILS} after-image.
     *
     * <p>Structurally the same as the OLD image with two deliberate exceptions, both preserved rather than
     * harmonised (practice <strong>B5</strong>): {@code ACUP-NEW-CUST-SSN-X} is a group of {@code X(03)},
     * {@code X(02)} and {@code X(04)} fed from {@code ACTSSN1}/{@code ACTSSN2}/{@code ACTSSN3}, where OLD
     * is a flat {@code X(09)}; and NEW's FICO item carries an extra
     * {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850} [{@code app/cbl/COACTUPC.cbl:848-849}] that
     * OLD lacks. Both SSN forms occupy nine bytes, so a size check cannot tell a collapsed group from an
     * intact one - {@link CustSnapshot#ssn1()}, {@link CustSnapshot#ssn2()} and
     * {@link CustSnapshot#ssn3()} have to be asserted by name.
     *
     * @return the NEW details; never {@code null}
     */
    @JsonIgnore
    public Details newDetails() {
        return commArea.newDetails();
    }

    /**
     * The {@value AcctSnapshot#RECORD_LENGTH}-byte account half of the before-image.
     *
     * <p>Its {@code X(12)} / {@code PIC S9(10)V99} {@code REDEFINES} pairs - current balance, credit
     * limit, cash credit limit, cycle credit and cycle debit - are reachable as {@code BigDecimal} through
     * {@link AcctSnapshot#currBalN()} and its siblings, at scale {@value #MONETARY_SCALE} with
     * {@link #MONETARY_ROUNDING}. Two typed accessors over one backing span, which is what gate
     * <strong>G34</strong> asks for; byte reinterpretation, never a parse.
     *
     * <p>The {@code EXPIRAION} misspelling of {@code ACUP-OLD-EXPIRAION-DATE}
     * [{@code app/cbl/COACTUPC.cbl:690}] is preserved verbatim in
     * {@link AcctSnapshot#expiraionDate()}. Correcting it would break field-for-field diffing.
     *
     * @return the OLD account snapshot; never {@code null}
     */
    @JsonIgnore
    public AcctSnapshot oldAcct() {
        return commArea.oldDetails().acct();
    }

    /**
     * The {@value CustSnapshot#RECORD_LENGTH}-byte customer half of the before-image.
     *
     * <p>Its date of birth is stored <strong>unseparated</strong> at {@code X(08)} while the live
     * {@code CUST-DOB-YYYY-MM-DD} is {@code X(10)} separated. That asymmetry is exactly what
     * {@code 9700-CHECK-CHANGE-IN-REC} depends on: {@code app/cbl/COACTUPC.cbl:4174-4179} compares the
     * live field at {@code (1:4)}, {@code (6:2)} and {@code (9:2)} against the snapshot at {@code (1:4)},
     * {@code (5:2)} and {@code (7:2)}. Normalising the snapshot to ten bytes would silently break it.
     *
     * @return the OLD customer snapshot; never {@code null}
     */
    @JsonIgnore
    public CustSnapshot oldCust() {
        return commArea.oldDetails().cust();
    }

    /**
     * The account half of the after-image.
     *
     * @return the NEW account snapshot; never {@code null}
     */
    @JsonIgnore
    public AcctSnapshot newAcct() {
        return commArea.newDetails().acct();
    }

    /**
     * The customer half of the after-image, whose SSN is the three-part group.
     *
     * @return the NEW customer snapshot; never {@code null}
     */
    @JsonIgnore
    public CustSnapshot newCust() {
        return commArea.newDetails().cust();
    }

    /**
     * Which of the two prefixes the before-image declares - {@link DetailGroup#OLD}, always.
     *
     * <p>Present so that a caller pairing an image with its {@code ACUP-OLD-} or {@code ACUP-NEW-} item
     * names can read the prefix off the image rather than assume it.
     *
     * @return the OLD group marker
     */
    @JsonIgnore
    public DetailGroup oldDetailGroup() {
        return commArea.oldDetails().group();
    }

    /**
     * Which of the two prefixes the after-image declares - {@link DetailGroup#NEW}, always.
     *
     * @return the NEW group marker
     */
    @JsonIgnore
    public DetailGroup newDetailGroup() {
        return commArea.newDetails().group();
    }

    // =================================================================================================
    // ACUP-CHANGE-ACTION, app/cbl/COACTUPC.cbl:654-668. One byte, nine 88-level condition names, all nine
    // reachable from here so that a caller never has to test the raw character - and so that gate G50 can
    // drive both the true and the false state of each. The two GROUPINGS matter as much as the singletons:
    // ACUP-CHANGES-MADE covers five values and ACUP-CHANGES-FAILED covers two.
    // =================================================================================================

    /**
     * The one-byte {@code ACUP-CHANGE-ACTION} through which {@code AccountUpdateService} signals its
     * outcome.
     *
     * @return the change action; never {@code null}
     */
    @JsonIgnore
    public ChangeAction changeAction() {
        return commArea.changeAction();
    }

    /**
     * This response reporting a different outcome.
     *
     * @param replacement the new change action
     * @return a new response
     * @throws NullPointerException if {@code replacement} is {@code null}
     */
    public AccountUpdateResponse withChangeAction(ChangeAction replacement) {
        Objects.requireNonNull(replacement, "A change action is required; ACUP-CHANGE-ACTION is PIC X(1) "
                + "and always holds one of the nine states, LOW-VALUES included");
        return withCommArea(commArea.withChangeAction(replacement));
    }

    /**
     * {@code 88 ACUP-DETAILS-NOT-FETCHED VALUES LOW-VALUES, SPACES}
     * [{@code app/cbl/COACTUPC.cbl:656-658}] - nothing has been read yet.
     *
     * @return {@code true} in the not-fetched state
     */
    @JsonIgnore
    public boolean isDetailsNotFetched() {
        return changeAction().isDetailsNotFetched();
    }

    /**
     * {@code 88 ACUP-SHOW-DETAILS VALUE 'S'} [{@code app/cbl/COACTUPC.cbl:659}].
     *
     * @return {@code true} in the show-details state
     */
    @JsonIgnore
    public boolean isShowDetails() {
        return changeAction().isShowDetails();
    }

    /**
     * {@code 88 ACUP-CHANGES-MADE VALUES 'E', 'N', 'C', 'L', 'F'}
     * [{@code app/cbl/COACTUPC.cbl:660-662}].
     *
     * <p><strong>Five</strong> values, not two. This file's own specification records two; the source
     * records five, and rule <strong>R1</strong> takes behaviour from the source.
     * {@code 3390-SETUP-INFOMSG-ATTRS} at {@code :3573-3576} tests
     * {@code IF ACUP-CHANGES-MADE AND NOT ACUP-CHANGES-OKAYED-AND-DONE}, which only makes sense if
     * {@code 'C'} is one of the five - the {@code AND NOT} would otherwise be dead.
     *
     * @return {@code true} when changes have been made, in any of the five senses
     */
    @JsonIgnore
    public boolean isChangesMade() {
        return changeAction().isChangesMade();
    }

    /**
     * {@code 88 ACUP-CHANGES-NOT-OK VALUE 'E'} [{@code app/cbl/COACTUPC.cbl:663}] - the edits rejected
     * the input.
     *
     * @return {@code true} in the changes-not-OK state
     */
    @JsonIgnore
    public boolean isChangesNotOk() {
        return changeAction().isChangesNotOk();
    }

    /**
     * {@code 88 ACUP-CHANGES-OK-NOT-CONFIRMED VALUE 'N'} [{@code app/cbl/COACTUPC.cbl:664}] - the state
     * in which F5 becomes valid, per the AID guard at {@code :908}.
     *
     * @return {@code true} in the OK-but-unconfirmed state
     */
    @JsonIgnore
    public boolean isChangesOkNotConfirmed() {
        return changeAction().isChangesOkNotConfirmed();
    }

    /**
     * {@code 88 ACUP-CHANGES-OKAYED-AND-DONE VALUE 'C'} [{@code app/cbl/COACTUPC.cbl:665}] - written
     * successfully. {@code :979-989} resets the screen on this state.
     *
     * @return {@code true} in the done state
     */
    @JsonIgnore
    public boolean isChangesOkayedAndDone() {
        return changeAction().isChangesOkayedAndDone();
    }

    /**
     * {@code 88 ACUP-CHANGES-FAILED VALUES 'L', 'F'} [{@code app/cbl/COACTUPC.cbl:666}] - the second
     * grouping. {@code :980} resets the screen on it as well.
     *
     * @return {@code true} in either failure state
     */
    @JsonIgnore
    public boolean isChangesFailed() {
        return changeAction().isChangesFailed();
    }

    /**
     * {@code 88 ACUP-CHANGES-OKAYED-LOCK-ERROR VALUE 'L'} [{@code app/cbl/COACTUPC.cbl:667}] - the
     * {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE} and {@code COULD-NOT-LOCK-CUST-FOR-UPDATE} paths.
     *
     * @return {@code true} in the lock-error state
     */
    @JsonIgnore
    public boolean isChangesOkayedLockError() {
        return changeAction().isChangesOkayedLockError();
    }

    /**
     * {@code 88 ACUP-CHANGES-OKAYED-BUT-FAILED VALUE 'F'} [{@code app/cbl/COACTUPC.cbl:668}] - the
     * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} outcome of {@code 9700-CHECK-CHANGE-IN-REC}
     * [{@code :4143}, {@code :4189}]. This is the optimistic-concurrency failure, reported and not
     * re-decided here (gates <strong>G43</strong> and <strong>G51</strong>).
     *
     * @return {@code true} in the changed-before-update state
     */
    @JsonIgnore
    public boolean isChangesOkayedButFailed() {
        return changeAction().isChangesOkayedButFailed();
    }

    // =================================================================================================
    // The group image. Hand-written and byte-explicit: every offset comes from a named span of
    // OUTPUT_GROUP_LAYOUT, the charset is always the codec's and never the platform default, and no
    // third-party copybook parser is involved (practices B8 and B11).
    // =================================================================================================

    /**
     * One field's value as it would be stored: the {@code PIC X} move to its declared width.
     *
     * <p>Padded on the right with spaces if short and truncated on the right if long, which is what a COBOL
     * alphanumeric {@code MOVE} does - the opposite of the numeric case, and the reason this goes through
     * {@link FixedWidthCodec#movePicX(String, int)} rather than through Java string arithmetic.
     *
     * @param field the field
     * @param codec the codec supplying the move rule
     * @return exactly {@code field.length()} characters
     * @throws NullPointerException if either argument is {@code null}
     */
    public String image(ScreenField field, FixedWidthCodec codec) {
        Objects.requireNonNull(field, "A field is required to render its stored image");
        Objects.requireNonNull(codec, "A FixedWidthCodec is required: it supplies the PIC X move rule");
        return codec.movePicX(values.get(field), field.length());
    }

    /**
     * This response with every field moved to its declared width.
     *
     * <p>Idempotent, and the fixed point of {@link #toGroupImage(FixedWidthCodec)} followed by
     * {@link #fromGroupImage(byte[], FixedWidthCodec)}: a normalised response survives that round trip
     * unchanged.
     *
     * @param codec the codec supplying the move rule and the charset
     * @return a response whose {@value #FIELD_COUNT} values are each exactly their declared width
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public AccountUpdateResponse normalize(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to normalise field widths");
        Builder builder = toBuilder();
        for (ScreenField field : ScreenField.values()) {
            builder.value(field, image(field, codec));
        }
        return builder.build();
    }

    /**
     * Renders the {@value #GROUP_LENGTH}-byte {@code CACTUPAO} image {@code 3400-SEND-SCREEN} sends
     * {@code FROM} at {@code app/cbl/COACTUPC.cbl:3596}.
     *
     * <p>Built in the order the program builds it:
     *
     * <ol>
     *   <li>the whole area to LOW-VALUES - {@code MOVE LOW-VALUES TO CACTUPAO}
     *       [{@code app/cbl/COACTUPC.cbl:2668}]. That single step accounts for the
     *       {@value #TIOAPFX_LENGTH}-byte {@code TIOAPFX} prefix and all {@value #FIELD_COUNT} three-byte
     *       {@code FILLER} spans, which is why neither needs a write of its own below;</li>
     *   <li>each field's four attribute items, from its quad;</li>
     *   <li>each field's payload item, moved to its declared width as {@code PIC X}.</li>
     * </ol>
     *
     * <p>Every byte of the result is accounted for by a declared span of {@link #OUTPUT_GROUP_LAYOUT}, so
     * a missing {@code FILLER} would have failed at class initialisation rather than shifted an offset
     * here (gate <strong>G21</strong>).
     *
     * @param codec the codec supplying the charset and the move rule
     * @return a fresh {@value #GROUP_LENGTH}-byte array
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public byte[] toGroupImage(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to render the CACTUPAO group image: "
                + "it supplies both the PIC X move rule and the charset");
        FixedWidthRecord area = codec.newRecord(OUTPUT_GROUP_LAYOUT);
        area.fill(0, GROUP_LENGTH, LOW_VALUE_BYTE);
        for (ScreenField field : ScreenField.values()) {
            FieldAttributes quad = attributes.get(field);
            area.writeSpanBytes(field.colourItemSpan(), new byte[] {quad.getColour()});
            area.writeSpanBytes(field.psItemSpan(), new byte[] {quad.getPs()});
            area.writeSpanBytes(field.hilightItemSpan(), new byte[] {quad.getHilight()});
            area.writeSpanBytes(field.validnItemSpan(), new byte[] {quad.getValidn()});
            area.writeSpanBytes(field.outputItemSpan(),
                    FixedWidthRecord.encodeText(image(field, codec), codec.charset(),
                            field.describe()));
        }
        return area.toByteArray();
    }

    /**
     * Reads a {@value #GROUP_LENGTH}-byte {@code CACTUPAO} image back into a response.
     *
     * <p>Recovers the {@value #FIELD_COUNT} payload values at their full declared widths - untrimmed,
     * because a {@code PIC X} field's trailing spaces are part of its value - together with each field's
     * four attribute bytes.
     *
     * <p>Three things are deliberately not recovered, because none of them is data: the
     * {@value #TIOAPFX_LENGTH}-byte {@code TIOAPFX} prefix, which belongs to the terminal area; the
     * {@value #FIELD_PREFIX_FILLER_LENGTH}-byte {@code FILLER} per field, which is unnamed and overlays the
     * input group's length and flag items; and the three conversation-state carriers, which are separate
     * storage entirely and travel on their own. The returned response therefore carries no navigation
     * target, an initialised communication area and a fresh card work area.
     *
     * <p>So the round trip is exact where it can be: for a response whose carriers are at their initialised
     * state, {@code fromGroupImage(x.toGroupImage(codec), codec)} equals {@code x.normalize(codec)}.
     *
     * @param groupImage the {@value #GROUP_LENGTH}-byte output group; read, never retained
     * @param codec      the codec supplying the charset
     * @return a response carrying the image's values and attribute quads
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code groupImage} is not exactly {@value #GROUP_LENGTH} bytes
     */
    public static AccountUpdateResponse fromGroupImage(byte[] groupImage, FixedWidthCodec codec) {
        Objects.requireNonNull(groupImage, "A group image is required to read a CACTUPAO area");
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to read a CACTUPAO area: it "
                + "supplies the charset the field data is encoded in");
        if (groupImage.length != GROUP_LENGTH) {
            throw new IllegalArgumentException("The CACTUPAO group of app/cpy-bms/COACTUP.CPY is "
                    + GROUP_LENGTH + " bytes - " + TIOAPFX_LENGTH + " of TIOAPFX prefix, plus "
                    + FIELD_COUNT + " fields at " + FIELD_OVERHEAD + " bytes of overhead each, plus "
                    + PAYLOAD_LENGTH + " bytes of data - but this image is " + groupImage.length
                    + " byte(s)");
        }
        FixedWidthRecord area = codec.wrap(groupImage, OUTPUT_GROUP_LAYOUT);
        Builder builder = new Builder();
        for (ScreenField field : ScreenField.values()) {
            builder.attributes(field, new FieldAttributes(
                    singleByte(area, field.colourItemSpan()),
                    singleByte(area, field.psItemSpan()),
                    singleByte(area, field.hilightItemSpan()),
                    singleByte(area, field.validnItemSpan())));
            builder.value(field, FixedWidthRecord.decodeText(
                    area.readSpanBytes(field.outputItemSpan()), codec.charset(), field.describe()));
        }
        return builder.build();
    }

    /**
     * Reads one attribute item as a raw byte.
     *
     * <p>Read as a byte rather than decoded as text on purpose: an attribute value such as
     * {@link BmsAttributes#DFHRED} ({@code 0xF2}) is not a character in the charset the field data is
     * encoded in, so decoding it would substitute a replacement character and lose the value.
     *
     * @param area the wrapped group
     * @param span the one-byte span to read
     * @return the byte
     */
    private static byte singleByte(FixedWidthRecord area, FieldSpan span) {
        return area.readSpanBytes(span)[0];
    }

    // =================================================================================================
    // Equality and diagnostics.
    // =================================================================================================

    /**
     * Value equality over all {@value #FIELD_COUNT} fields, all {@value #FIELD_COUNT} attribute quads, the
     * navigation trio and all three carriers.
     *
     * @param other the object to compare with
     * @return {@code true} when every part agrees
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AccountUpdateResponse that)) {
            return false;
        }
        return values.equals(that.values)
                && attributes.equals(that.attributes)
                && nextProgram.equals(that.nextProgram)
                && nextMapset.equals(that.nextMapset)
                && nextMap.equals(that.nextMap)
                && commArea.equals(that.commArea)
                && cardScreenState.equals(that.cardScreenState)
                && Objects.equals(navigationContext, that.navigationContext);
    }

    /**
     * A hash consistent with {@link #equals(Object)}.
     *
     * @return the hash of every field, every quad, the navigation trio and the three carriers
     */
    @Override
    public int hashCode() {
        return Objects.hash(values, attributes, nextProgram, nextMapset, nextMap,
                commArea, cardScreenState, navigationContext);
    }

    /**
     * A single-line rendering of every field, every quad, the navigation trio and the carriers.
     *
     * <p>Values are rendered <strong>as stored</strong> and quoted, so that the trailing spaces of a
     * fixed-width field - which are part of its value - stay visible in a failure message, and so that a
     * field holding nothing but {@link #BLANK_FIELD_MARKER} is distinguishable from one holding a real
     * value.
     *
     * <p>Nothing is masked, redacted or omitted: not the three SSN parts, not the date of birth, not the
     * government-issued identifier and not the electronic funds transfer account. {@code COACTUPC} paints
     * every one of them on a 3270 in the clear, and withholding any of them here would be an unrequested
     * behaviour change and a parity violation (practice <strong>B6</strong>). That decision is recorded in
     * this type's own documentation rather than left implicit, and it cuts both ways - nothing is weakened
     * either.
     *
     * @return the rendering; never {@code null}
     */
    @Override
    public String toString() {
        StringBuilder rendered = new StringBuilder("AccountUpdateResponse[");
        for (ScreenField field : ScreenField.values()) {
            rendered.append(field.label())
                    .append("='")
                    .append(values.get(field))
                    .append("' ")
                    .append(attributes.get(field))
                    .append(", ");
        }
        return rendered.append("nextProgram='").append(nextProgram)
                .append("', nextMapset='").append(nextMapset)
                .append("', nextMap='").append(nextMap)
                .append("', commArea=").append(commArea)
                .append(", cardScreenState=").append(cardScreenState)
                .append(", navigationContext=").append(navigationContext)
                .append(']')
                .toString();
    }

    // =================================================================================================
    // The builder. Also the Jackson deserialisation target, so that the response can be immutable and
    // still round-trip through JSON without a setter anywhere on the payload.
    // =================================================================================================

    /**
     * Assembles an {@link AccountUpdateResponse}.
     *
     * <p>One method per {@code DFHMDF} field, named as {@link AccountUpdateRequest}'s builder names its
     * counterpart, plus the navigation trio and the three carriers. Every method returns {@code this}, and
     * an unset field becomes its declared width in LOW-VALUES when {@link #build()} is called - the state
     * {@code MOVE LOW-VALUES TO CACTUPAO} leaves it in.
     *
     * <p>Annotated {@code @JsonPOJOBuilder(withPrefix = "")} so that Jackson calls {@code trnname(..)}
     * rather than looking for {@code withTrnname(..)}; the class itself carries
     * {@code @JsonDeserialize(builder = Builder.class)}. No Lombok and no MapStruct is involved: generated
     * accessors would obscure exactly the byte-for-byte field mapping this migration's parity gate rests
     * on.
     */
    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {

        /** Values supplied so far. Absent means "not supplied", which becomes LOW-VALUES on build. */
        private final Map<ScreenField, String> values = new EnumMap<>(ScreenField.class);

        /** Quads supplied so far. Absent means an unset quad on build. */
        private final Map<ScreenField, FieldAttributes> attributes = new EnumMap<>(ScreenField.class);

        /** The {@code XCTL} target program, or {@code null} for none. */
        private String nextProgram;

        /** The next mapset, or {@code null} for none. */
        private String nextMapset;

        /** The next map, or {@code null} for none. */
        private String nextMap;

        /** The communication area, or {@code null} for an initialised one. */
        private CommArea commArea;

        /** The card work area, or {@code null} for a fresh one. */
        private CardScreenState cardScreenState;

        /** The navigation context, or {@code null} for none. */
        private NavigationContext navigationContext;

        /** A builder with nothing supplied. */
        public Builder() {
            // Every field is optional; build() substitutes the LOW-VALUES baseline for whatever is absent.
        }

        /**
         * Sets one field by name, for a caller that iterates rather than one that knows the field.
         *
         * @param field the field
         * @param value the value; {@code null} leaves it at the LOW-VALUES baseline
         * @return this builder
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public Builder value(ScreenField field, String value) {
            Objects.requireNonNull(field, "A field is required to set a value");
            if (value == null) {
                values.remove(field);
            } else {
                values.put(field, value);
            }
            return this;
        }

        /**
         * Sets one field's attribute quad.
         *
         * @param field the field
         * @param quad  the quad; {@code null} leaves it unset
         * @return this builder
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public Builder attributes(ScreenField field, FieldAttributes quad) {
            Objects.requireNonNull(field, "A field is required to set an attribute quad");
            if (quad == null) {
                attributes.remove(field);
            } else {
                attributes.put(field, quad);
            }
            return this;
        }

        /**
         * Sets the {@code XCTL} target program.
         *
         * @param value eight characters, or {@code null}
         * @return this builder
         */
        public Builder nextProgram(String value) {
            this.nextProgram = value;
            return this;
        }

        /**
         * Sets the next mapset.
         *
         * @param value seven characters, or {@code null}
         * @return this builder
         */
        public Builder nextMapset(String value) {
            this.nextMapset = value;
            return this;
        }

        /**
         * Sets the next map.
         *
         * @param value seven characters, or {@code null}
         * @return this builder
         */
        public Builder nextMap(String value) {
            this.nextMap = value;
            return this;
        }

        /**
         * Sets the {@value CommArea#RECORD_LENGTH}-byte communication area.
         *
         * @param value the area, or {@code null} for {@link CommArea#initialised()}
         * @return this builder
         */
        public Builder commArea(CommArea value) {
            this.commArea = value;
            return this;
        }

        /**
         * Sets the {@value CardScreenState#RECORD_LENGTH}-byte card work area. Copied on build.
         *
         * @param value the work area, or {@code null} for a fresh one
         * @return this builder
         */
        public Builder cardScreenState(CardScreenState value) {
            this.cardScreenState = value;
            return this;
        }

        /**
         * Sets the {@value NavigationContext#COMMAREA_LENGTH}-byte navigation context.
         *
         * @param value the context, or {@code null} for none - the {@code EIBCALEN IS EQUAL TO 0} case
         * @return this builder
         */
        public Builder navigationContext(NavigationContext value) {
            this.navigationContext = value;
            return this;
        }


        // -----------------------------------------------------------------------------------------
        // The 54 named field methods, in symbolic-map order. Each is the Jackson property binding for
        // its DFHMDF field as well as the fluent setter, which is why the names match the accessors'
        // (get<Name>() pairs with <name>(..)) and AccountUpdateRequest's builder exactly.
        // -----------------------------------------------------------------------------------------
        /**
         * Sets {@code TRNNAMEO PIC X({@value AccountUpdateResponse#TRNNAME_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#TRNNAME_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder trnname(String value) {
            return value(ScreenField.TRNNAME, value);
        }
        /**
         * Sets {@code TITLE01O PIC X({@value AccountUpdateResponse#TITLE01_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#TITLE01_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder title01(String value) {
            return value(ScreenField.TITLE01, value);
        }
        /**
         * Sets {@code CURDATEO PIC X({@value AccountUpdateResponse#CURDATE_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#CURDATE_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder curdate(String value) {
            return value(ScreenField.CURDATE, value);
        }
        /**
         * Sets {@code PGMNAMEO PIC X({@value AccountUpdateResponse#PGMNAME_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#PGMNAME_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder pgmname(String value) {
            return value(ScreenField.PGMNAME, value);
        }
        /**
         * Sets {@code TITLE02O PIC X({@value AccountUpdateResponse#TITLE02_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#TITLE02_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder title02(String value) {
            return value(ScreenField.TITLE02, value);
        }
        /**
         * Sets {@code CURTIMEO PIC X({@value AccountUpdateResponse#CURTIME_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#CURTIME_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder curtime(String value) {
            return value(ScreenField.CURTIME, value);
        }
        /**
         * Sets {@code ACCTSIDO PIC X({@value AccountUpdateResponse#ACCTSID_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACCTSID_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acctsid(String value) {
            return value(ScreenField.ACCTSID, value);
        }
        /**
         * Sets {@code ACSTTUSO PIC X({@value AccountUpdateResponse#ACSTTUS_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSTTUS_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsttus(String value) {
            return value(ScreenField.ACSTTUS, value);
        }
        /**
         * Sets {@code OPNYEARO PIC X({@value AccountUpdateResponse#OPNYEAR_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#OPNYEAR_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder opnyear(String value) {
            return value(ScreenField.OPNYEAR, value);
        }
        /**
         * Sets {@code OPNMONO PIC X({@value AccountUpdateResponse#OPNMON_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#OPNMON_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder opnmon(String value) {
            return value(ScreenField.OPNMON, value);
        }
        /**
         * Sets {@code OPNDAYO PIC X({@value AccountUpdateResponse#OPNDAY_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#OPNDAY_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder opnday(String value) {
            return value(ScreenField.OPNDAY, value);
        }
        /**
         * Sets {@code ACRDLIMO PIC X({@value AccountUpdateResponse#ACRDLIM_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACRDLIM_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acrdlim(String value) {
            return value(ScreenField.ACRDLIM, value);
        }
        /**
         * Sets {@code EXPYEARO PIC X({@value AccountUpdateResponse#EXPYEAR_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#EXPYEAR_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder expyear(String value) {
            return value(ScreenField.EXPYEAR, value);
        }
        /**
         * Sets {@code EXPMONO PIC X({@value AccountUpdateResponse#EXPMON_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#EXPMON_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder expmon(String value) {
            return value(ScreenField.EXPMON, value);
        }
        /**
         * Sets {@code EXPDAYO PIC X({@value AccountUpdateResponse#EXPDAY_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#EXPDAY_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder expday(String value) {
            return value(ScreenField.EXPDAY, value);
        }
        /**
         * Sets {@code ACSHLIMO PIC X({@value AccountUpdateResponse#ACSHLIM_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSHLIM_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acshlim(String value) {
            return value(ScreenField.ACSHLIM, value);
        }
        /**
         * Sets {@code RISYEARO PIC X({@value AccountUpdateResponse#RISYEAR_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#RISYEAR_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder risyear(String value) {
            return value(ScreenField.RISYEAR, value);
        }
        /**
         * Sets {@code RISMONO PIC X({@value AccountUpdateResponse#RISMON_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#RISMON_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder rismon(String value) {
            return value(ScreenField.RISMON, value);
        }
        /**
         * Sets {@code RISDAYO PIC X({@value AccountUpdateResponse#RISDAY_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#RISDAY_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder risday(String value) {
            return value(ScreenField.RISDAY, value);
        }
        /**
         * Sets {@code ACURBALO PIC X({@value AccountUpdateResponse#ACURBAL_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACURBAL_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acurbal(String value) {
            return value(ScreenField.ACURBAL, value);
        }
        /**
         * Sets {@code ACRCYCRO PIC X({@value AccountUpdateResponse#ACRCYCR_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACRCYCR_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acrcycr(String value) {
            return value(ScreenField.ACRCYCR, value);
        }
        /**
         * Sets {@code AADDGRPO PIC X({@value AccountUpdateResponse#AADDGRP_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#AADDGRP_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder aaddgrp(String value) {
            return value(ScreenField.AADDGRP, value);
        }
        /**
         * Sets {@code ACRCYDBO PIC X({@value AccountUpdateResponse#ACRCYDB_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACRCYDB_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acrcydb(String value) {
            return value(ScreenField.ACRCYDB, value);
        }
        /**
         * Sets {@code ACSTNUMO PIC X({@value AccountUpdateResponse#ACSTNUM_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSTNUM_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acstnum(String value) {
            return value(ScreenField.ACSTNUM, value);
        }
        /**
         * Sets {@code ACTSSN1O PIC X({@value AccountUpdateResponse#ACTSSN1_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACTSSN1_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder actssn1(String value) {
            return value(ScreenField.ACTSSN1, value);
        }
        /**
         * Sets {@code ACTSSN2O PIC X({@value AccountUpdateResponse#ACTSSN2_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACTSSN2_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder actssn2(String value) {
            return value(ScreenField.ACTSSN2, value);
        }
        /**
         * Sets {@code ACTSSN3O PIC X({@value AccountUpdateResponse#ACTSSN3_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACTSSN3_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder actssn3(String value) {
            return value(ScreenField.ACTSSN3, value);
        }
        /**
         * Sets {@code DOBYEARO PIC X({@value AccountUpdateResponse#DOBYEAR_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#DOBYEAR_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder dobyear(String value) {
            return value(ScreenField.DOBYEAR, value);
        }
        /**
         * Sets {@code DOBMONO PIC X({@value AccountUpdateResponse#DOBMON_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#DOBMON_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder dobmon(String value) {
            return value(ScreenField.DOBMON, value);
        }
        /**
         * Sets {@code DOBDAYO PIC X({@value AccountUpdateResponse#DOBDAY_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#DOBDAY_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder dobday(String value) {
            return value(ScreenField.DOBDAY, value);
        }
        /**
         * Sets {@code ACSTFCOO PIC X({@value AccountUpdateResponse#ACSTFCO_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSTFCO_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acstfco(String value) {
            return value(ScreenField.ACSTFCO, value);
        }
        /**
         * Sets {@code ACSFNAMO PIC X({@value AccountUpdateResponse#ACSFNAM_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSFNAM_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsfnam(String value) {
            return value(ScreenField.ACSFNAM, value);
        }
        /**
         * Sets {@code ACSMNAMO PIC X({@value AccountUpdateResponse#ACSMNAM_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSMNAM_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsmnam(String value) {
            return value(ScreenField.ACSMNAM, value);
        }
        /**
         * Sets {@code ACSLNAMO PIC X({@value AccountUpdateResponse#ACSLNAM_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSLNAM_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acslnam(String value) {
            return value(ScreenField.ACSLNAM, value);
        }
        /**
         * Sets {@code ACSADL1O PIC X({@value AccountUpdateResponse#ACSADL1_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSADL1_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsadl1(String value) {
            return value(ScreenField.ACSADL1, value);
        }
        /**
         * Sets {@code ACSSTTEO PIC X({@value AccountUpdateResponse#ACSSTTE_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSSTTE_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsstte(String value) {
            return value(ScreenField.ACSSTTE, value);
        }
        /**
         * Sets {@code ACSADL2O PIC X({@value AccountUpdateResponse#ACSADL2_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSADL2_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsadl2(String value) {
            return value(ScreenField.ACSADL2, value);
        }
        /**
         * Sets {@code ACSZIPCO PIC X({@value AccountUpdateResponse#ACSZIPC_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSZIPC_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acszipc(String value) {
            return value(ScreenField.ACSZIPC, value);
        }
        /**
         * Sets {@code ACSCITYO PIC X({@value AccountUpdateResponse#ACSCITY_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSCITY_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acscity(String value) {
            return value(ScreenField.ACSCITY, value);
        }
        /**
         * Sets {@code ACSCTRYO PIC X({@value AccountUpdateResponse#ACSCTRY_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSCTRY_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsctry(String value) {
            return value(ScreenField.ACSCTRY, value);
        }
        /**
         * Sets {@code ACSPH1AO PIC X({@value AccountUpdateResponse#ACSPH1A_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSPH1A_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsph1a(String value) {
            return value(ScreenField.ACSPH1A, value);
        }
        /**
         * Sets {@code ACSPH1BO PIC X({@value AccountUpdateResponse#ACSPH1B_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSPH1B_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsph1b(String value) {
            return value(ScreenField.ACSPH1B, value);
        }
        /**
         * Sets {@code ACSPH1CO PIC X({@value AccountUpdateResponse#ACSPH1C_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSPH1C_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsph1c(String value) {
            return value(ScreenField.ACSPH1C, value);
        }
        /**
         * Sets {@code ACSGOVTO PIC X({@value AccountUpdateResponse#ACSGOVT_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSGOVT_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsgovt(String value) {
            return value(ScreenField.ACSGOVT, value);
        }
        /**
         * Sets {@code ACSPH2AO PIC X({@value AccountUpdateResponse#ACSPH2A_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSPH2A_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsph2a(String value) {
            return value(ScreenField.ACSPH2A, value);
        }
        /**
         * Sets {@code ACSPH2BO PIC X({@value AccountUpdateResponse#ACSPH2B_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSPH2B_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsph2b(String value) {
            return value(ScreenField.ACSPH2B, value);
        }
        /**
         * Sets {@code ACSPH2CO PIC X({@value AccountUpdateResponse#ACSPH2C_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSPH2C_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsph2c(String value) {
            return value(ScreenField.ACSPH2C, value);
        }
        /**
         * Sets {@code ACSEFTCO PIC X({@value AccountUpdateResponse#ACSEFTC_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSEFTC_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acseftc(String value) {
            return value(ScreenField.ACSEFTC, value);
        }
        /**
         * Sets {@code ACSPFLGO PIC X({@value AccountUpdateResponse#ACSPFLG_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSPFLG_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acspflg(String value) {
            return value(ScreenField.ACSPFLG, value);
        }
        /**
         * Sets {@code INFOMSGO PIC X({@value AccountUpdateResponse#INFOMSG_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#INFOMSG_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder infomsg(String value) {
            return value(ScreenField.INFOMSG, value);
        }
        /**
         * Sets {@code ERRMSGO PIC X({@value AccountUpdateResponse#ERRMSG_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ERRMSG_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder errmsg(String value) {
            return value(ScreenField.ERRMSG, value);
        }
        /**
         * Sets {@code FKEYSO PIC X({@value AccountUpdateResponse#FKEYS_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#FKEYS_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder fkeys(String value) {
            return value(ScreenField.FKEYS, value);
        }
        /**
         * Sets {@code FKEY05O PIC X({@value AccountUpdateResponse#FKEY05_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#FKEY05_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder fkey05(String value) {
            return value(ScreenField.FKEY05, value);
        }
        /**
         * Sets {@code FKEY12O PIC X({@value AccountUpdateResponse#FKEY12_LENGTH})}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#FKEY12_LENGTH} characters; may be
         *              {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder fkey12(String value) {
            return value(ScreenField.FKEY12, value);
        }
        /**
         * Builds the response.
         *
         * @return an immutable response; never {@code null}
         */
        public AccountUpdateResponse build() {
            return new AccountUpdateResponse(this);
        }
    }
}
