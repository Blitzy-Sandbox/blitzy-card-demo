package com.vsergeychik.carddemo.account.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The inbound payload of {@code PUT /api/accounts/{acctId}} - the account update screen, CSD
 * transaction {@code CAUP}, backed by {@code app/cbl/COACTUPC.cbl} (4,236 lines, the largest program
 * in the system).
 *
 * <p>This type is a 1:1 projection of the <em>input</em> half of one BMS mapset, plus the private work
 * area {@code COACTUPC} carries across a pseudo-conversational turn. Its authorities are read-only -
 * the legacy tree is the only oracle a parity migration has, so nothing under {@code app/cbl/},
 * {@code app/cpy/}, {@code app/cpy-bms/} or {@code app/bms/} is ever written (practice
 * <strong>B3</strong>):
 *
 * <ul>
 *   <li>{@code app/cpy-bms/COACTUP.CPY} - the symbolic map. Group {@code 01 CACTUPAI.} opens at line
 *       17 and its {@code xxxI} items run from line 24 to line 342.</li>
 *   <li>{@code app/bms/COACTUP.bms} - the mapset that generated it, ending with
 *       {@code DFHMSD TYPE=FINAL}.</li>
 *   <li>{@code app/cbl/COACTUPC.cbl:652-849} - {@code 01 WS-THIS-PROGCOMMAREA}, the
 *       {@value CommArea#RECORD_LENGTH}-byte work area appended after the communication area.</li>
 * </ul>
 *
 * <p>The group names drop a letter, and it is worth knowing before searching for them: the mapset is
 * {@code COACTUP} but its map is {@code CACTUPA}, so the symbolic groups are {@code CACTUPAI} and
 * {@code CACTUPAO} - not {@code COACTUPI}. A BMS map name is seven characters, so the leading
 * {@code O} of {@code COACTUP} is what gets dropped.
 *
 * <h2>Fifty-four fields, and why exactly fifty-four</h2>
 *
 * <p>{@code app/bms/COACTUP.bms} declares <strong>{@value #DFHMDF_ENTRY_COUNT}</strong> {@code DFHMDF}
 * entries of which <strong>{@value #FIELD_COUNT} carry a name label in column 1</strong>. The other 74
 * are unnamed literal entries - screen furniture such as {@code INITIAL='Tran:'} and
 * {@code INITIAL='Credit Limit        :'} - which BMS paints but never reports back. An unnamed entry
 * generates no symbolic-map item and holds no value, so it gets no Java member.
 *
 * <pre>
 *   #  DFHMDF   .bms  LENGTH  POS       xxxI item  .CPY  data offset
 *   -  -------  ----  ------  --------  ---------  ----  -----------
 *   1  TRNNAME    34       4  ( 1, 7)   TRNNAMEI     24           19
 *   2  TITLE01    38      40  ( 1,21)   TITLE01I     30           30
 *   3  CURDATE    47       8  ( 1,71)   CURDATEI     36           77
 *   4  PGMNAME    57       8  ( 2, 7)   PGMNAMEI     42           92
 *   5  TITLE02    61      40  ( 2,21)   TITLE02I     48          107
 *   6  CURTIME    70       8  ( 2,71)   CURTIMEI     54          154
 *   7  ACCTSID    84      11  ( 5,38)   ACCTSIDI     60          169
 *   8  ACSTTUS    94       1  ( 5,70)   ACSTTUSI     66          187
 *   9  OPNYEAR   104       4  ( 6,17)   OPNYEARI     72          195
 *  10  OPNMON    112       2  ( 6,24)   OPNMONI      78          206
 *  11  OPNDAY    120       2  ( 6,29)   OPNDAYI      84          215
 *  12  ACRDLIM   132      15  ( 6,61)   ACRDLIMI     90          224
 *  13  EXPYEAR   142       4  ( 7,17)   EXPYEARI     96          246
 *  14  EXPMON    150       2  ( 7,24)   EXPMONI     102          257
 *  15  EXPDAY    158       2  ( 7,29)   EXPDAYI     108          266
 *  16  ACSHLIM   170      15  ( 7,61)   ACSHLIMI    114          275
 *  17  RISYEAR   180       4  ( 8,17)   RISYEARI    120          297
 *  18  RISMON    188       2  ( 8,24)   RISMONI     126          308
 *  19  RISDAY    196       2  ( 8,29)   RISDAYI     132          317
 *  20  ACURBAL   208      15  ( 8,61)   ACURBALI    138          326
 *  21  ACRCYCR   219      15  ( 9,61)   ACRCYCRI    144          348
 *  22  AADDGRP   229      10  (10,23)   AADDGRPI    150          370
 *  23  ACRCYDB   240      15  (10,61)   ACRCYDBI    156          387
 *  24  ACSTNUM   254       9  (12,23)   ACSTNUMI    162          409
 *  25  ACTSSN1   264       3  (12,55)   ACTSSN1I    168          425
 *  26  ACTSSN2   272       2  (12,61)   ACTSSN2I    174          435
 *  27  ACTSSN3   280       4  (12,66)   ACTSSN3I    180          444
 *  28  DOBYEAR   291       4  (13,23)   DOBYEARI    186          455
 *  29  DOBMON    299       2  (13,30)   DOBMONI     192          466
 *  30  DOBDAY    307       2  (13,35)   DOBDAYI     198          475
 *  31  ACSTFCO   318       3  (13,62)   ACSTFCOI    204          484
 *  32  ACSFNAM   336      25  (15, 1)   ACSFNAMI    210          494
 *  33  ACSMNAM   342      25  (15,28)   ACSMNAMI    216          526
 *  34  ACSLNAM   348      25  (15,55)   ACSLNAMI    222          558
 *  35  ACSADL1   356      50  (16,10)   ACSADL1I    228          590
 *  36  ACSSTTE   366       2  (16,73)   ACSSTTEI    234          647
 *  37  ACSADL2   372      50  (17,10)   ACSADL2I    240          656
 *  38  ACSZIPC   382       5  (17,73)   ACSZIPCI    246          713
 *  39  ACSCITY   392      50  (18,10)   ACSCITYI    252          725
 *  40  ACSCTRY   402       3  (18,73)   ACSCTRYI    258          782
 *  41  ACSPH1A   412       3  (19,10)   ACSPH1AI    264          792
 *  42  ACSPH1B   417       3  (19,14)   ACSPH1BI    270          802
 *  43  ACSPH1C   422       4  (19,18)   ACSPH1CI    276          812
 *  44  ACSGOVT   433      20  (19,58)   ACSGOVTI    282          823
 *  45  ACSPH2A   443       3  (20,10)   ACSPH2AI    288          850
 *  46  ACSPH2B   448       3  (20,14)   ACSPH2BI    294          860
 *  47  ACSPH2C   453       4  (20,18)   ACSPH2CI    300          870
 *  48  ACSEFTC   464      10  (20,41)   ACSEFTCI    306          881
 *  49  ACSPFLG   474       1  (20,78)   ACSPFLGI    312          898
 *  50  INFOMSG   480      45  (22,23)   INFOMSGI    318          906
 *  51  ERRMSG    489      78  (23, 1)   ERRMSGI     324          958
 *  52  FKEYS     493      21  (24, 1)   FKEYSI      330         1043
 *  53  FKEY05    498       7  (24,23)   FKEY05I     336         1071
 *  54  FKEY12    503      10  (24,31)   FKEY12I     342         1085
 *   -  -------          ------                                  ----
 *                          705  = the sum of both width columns  1095
 * </pre>
 *
 * <p>The two width columns are independent transcriptions of one contract - one from the mapset's
 * {@code LENGTH=} operands, one from the symbolic map's {@code PICTURE} clauses - and they agree field
 * for field and in total. Every payload member below therefore traces to a name-labelled
 * {@code DFHMDF} entry and every width to a symbolic-map {@code PICTURE} clause, which is the whole of
 * gate <strong>G9</strong>.
 *
 * <p><strong>Every one of the 54 items is {@code PIC X(n)}.</strong>
 * {@code grep -cE "PICIN|PICOUT" app/bms/COACTUP.bms} returns <strong>0</strong>: this mapset declares
 * no {@code PICIN} and no {@code PICOUT}, so neither the 54 {@code xxxI} items nor the 54
 * {@code xxxO} items carries a numeric or an edited picture. That is the single largest difference from
 * the neighbouring view screen, whose {@code ACCTSIDI} is {@code PIC 99999999999} and whose five money
 * items carry a {@code +ZZZ,ZZZ,ZZZ.99} edit mask. None of that complexity belongs here.
 *
 * <p><strong>Declaration order is the copybook's, not a tidied one.</strong> Reading rows 9 to 23
 * gives {@code OPNYEAR, OPNMON, OPNDAY, ACRDLIM, EXPYEAR, EXPMON, EXPDAY, ACSHLIM, ...} - the screen's
 * left column and right column interleaved, row by row, because that is the order BMS lays the storage
 * down. Sorting them into a "logical" grouping would move every byte after the first move and silently
 * invalidate {@link #toGroupImage(FixedWidthCodec)}.
 *
 * <p>Fifty-four is also a ceiling and a floor. {@code COACTUP} declares no {@code PAGENO}, and none of
 * the neighbouring view screen's undivided {@code ADTOPEN}, {@code AEXPDT}, {@code AREISDT},
 * {@code ACSTSSN} or {@code ACSTDOB} - this screen splits all five. Conversely {@code FKEYS},
 * {@code FKEY05} and {@code FKEY12} are genuine fields <em>here</em> and the view screen has none of
 * them. Adding a field for symmetry with a sibling screen would introduce a member tracing to no
 * {@code DFHMDF} entry and fail gate <strong>G9</strong>.
 *
 * <h2>Input items only: the {@code xxxI} rule</h2>
 *
 * <p>Each input field occupies five items in {@code CACTUPAI}, of which exactly one is data:
 *
 * <pre>
 *   02 xxxL COMP PIC S9(4)                     2 bytes   the length halfword    -&gt; metadata
 *   02 xxxF PICTURE X                          1 byte    the flag byte          -&gt; metadata
 *   02 FILLER REDEFINES xxxF                   0 bytes   an overlay adds none
 *      03 xxxA PICTURE X                                 the attribute view     -&gt; metadata
 *   02 FILLER PICTURE X(4)                     4 bytes   extended attributes
 *   02 xxxI PIC X(n)                           n bytes   THE PAYLOAD
 *   ----------------------------------------  -------
 *                                                 7 + n
 * </pre>
 *
 * <p>So one field costs {@value #FIELD_OVERHEAD} bytes before its data, the group opens with
 * {@code 02 FILLER PIC X(12)} because {@code app/bms/COACTUP.bms:23} declares {@code TIOAPFX=YES}, and
 * the whole input group is {@value #TIOAPFX_LENGTH} + {@value #FIELD_COUNT} &times;
 * {@value #FIELD_OVERHEAD} + {@value #PAYLOAD_LENGTH} = <strong>{@value #GROUP_LENGTH}</strong> bytes.
 *
 * <p>None of those numbers is asserted only in this prose. {@link #PAYLOAD_LENGTH} and
 * {@link #GROUP_LENGTH} are computed from the 54 width constants rather than written down, and the
 * static initialiser below walks the {@link ScreenField} strides and refuses to load the class unless
 * the walk lands exactly on {@value #GROUP_LENGTH}. A transcription slip therefore fails on first use
 * instead of shifting every byte after it.
 *
 * <p><strong>This class carries the 54 {@code xxxI} items and nothing else.</strong> The 54
 * {@code xxxO} items and the colour, highlight, programmed-symbol and validation items beside them
 * belong to {@code AccountUpdateResponse}: {@code app/cbl/COACTUPC.cbl:2878}, {@code :2886},
 * {@code :2894} and {@code :2901} all name {@code CACTUPAO}, while {@code :1042} receives into
 * {@code CACTUPAI} and {@code :1051-1058} reads the operator's typing out of it.
 *
 * <h2>Every composite split is preserved on the wire</h2>
 *
 * <p>This screen deliberately decomposes seven values into twenty fields, and merging any of them
 * would break two separate downstream mechanisms:
 *
 * <ul>
 *   <li>{@code OPNYEAR}/{@code OPNMON}/{@code OPNDAY} - the account open date</li>
 *   <li>{@code EXPYEAR}/{@code EXPMON}/{@code EXPDAY} - the expiry date</li>
 *   <li>{@code RISYEAR}/{@code RISMON}/{@code RISDAY} - the reissue date</li>
 *   <li>{@code DOBYEAR}/{@code DOBMON}/{@code DOBDAY} - the date of birth</li>
 *   <li>{@code ACTSSN1}/{@code ACTSSN2}/{@code ACTSSN3} - the social security number</li>
 *   <li>{@code ACSPH1A}/{@code ACSPH1B}/{@code ACSPH1C} - the first telephone number</li>
 *   <li>{@code ACSPH2A}/{@code ACSPH2B}/{@code ACSPH2C} - the second telephone number</li>
 * </ul>
 *
 * <p><strong>Mechanism one - recomposition.</strong> The program reassembles each date itself, with
 * {@code STRING ... DELIMITED BY SIZE} into a ten-byte receiver, at
 * {@code app/cbl/COACTUPC.cbl:1809}, {@code :1842}, {@code :1870}, {@code :1887}, {@code :1916},
 * {@code :1942}, {@code :1973} and {@code :2000}. The program inserts the separators, so the wire has
 * to supply the parts. Doing the recomposition here would move business logic out of
 * {@code AccountUpdateService} and breach gate <strong>G51</strong>; this type transports the parts and
 * nothing more.
 *
 * <p><strong>Mechanism two - the optimistic-concurrency comparison, and it is asymmetric.</strong>
 * Paragraph {@code 9700-CHECK-CHANGE-IN-REC} ({@code app/cbl/COACTUPC.cbl:4109-4193}) re-reads the
 * record and compares it, field by field, against the snapshot the screen was painted from. The three
 * account dates compare part by part on <em>both</em> sides:
 *
 * <pre>
 *   ACCT-OPEN-DATE(1:4)       EQUAL ACUP-OLD-OPEN-YEAR         *&gt; :4127
 *   ACCT-OPEN-DATE(6:2)       EQUAL ACUP-OLD-OPEN-MON          *&gt; :4128
 *   ACCT-OPEN-DATE(9:2)       EQUAL ACUP-OLD-OPEN-DAY          *&gt; :4129
 *   ACCT-EXPIRAION-DATE(1:4)  EQUAL ACUP-OLD-EXP-YEAR          *&gt; :4131
 *   ACCT-EXPIRAION-DATE(6:2)  EQUAL ACUP-OLD-EXP-MON           *&gt; :4132
 *   ACCT-EXPIRAION-DATE(9:2)  EQUAL ACUP-OLD-EXP-DAY           *&gt; :4133
 *   ACCT-REISSUE-DATE(1:4)    EQUAL ACUP-OLD-REISSUE-YEAR      *&gt; :4135
 *   ACCT-REISSUE-DATE(6:2)    EQUAL ACUP-OLD-REISSUE-MON       *&gt; :4136
 *   ACCT-REISSUE-DATE(9:2)    EQUAL ACUP-OLD-REISSUE-DAY       *&gt; :4137
 * </pre>
 *
 * <p>but the date of birth compares with <strong>different offsets on each side</strong>:
 *
 * <pre>
 *   CUST-DOB-YYYY-MM-DD(1:4)  EQUAL ACUP-OLD-CUST-DOB-YYYY-MM-DD(1:4)   *&gt; :4174-4175
 *   CUST-DOB-YYYY-MM-DD(6:2)  EQUAL ACUP-OLD-CUST-DOB-YYYY-MM-DD(5:2)   *&gt; :4176-4177
 *   CUST-DOB-YYYY-MM-DD(9:2)  EQUAL ACUP-OLD-CUST-DOB-YYYY-MM-DD(7:2)   *&gt; :4178-4179
 * </pre>
 *
 * <p>The reason is provable from the declarations rather than guessed. The live customer field is
 * {@code CUST-DOB-YYYY-MM-DD PIC X(10)} - <em>separated</em>, so the hyphens occupy positions 5 and 8
 * of the data as well as appearing in the name - while the snapshot field
 * {@code ACUP-OLD-CUST-DOB-YYYY-MM-DD} is {@code PIC X(08)}: eight bytes, <strong>unseparated</strong>,
 * with a {@code REDEFINES} into {@code X(4)}/{@code X(2)}/{@code X(2)} parts
 * ({@code app/cbl/COACTUPC.cbl:746-751}). {@code ACUP-OLD-OPEN-DATE},
 * {@code ACUP-OLD-EXPIRAION-DATE} and {@code ACUP-OLD-REISSUE-DATE} are the same eight unseparated
 * bytes. Anyone "normalising" a snapshot date to ten bytes breaks all four comparisons at once, and the
 * date-of-birth one silently rather than loudly.
 *
 * <h2>The {@value CommArea#RECORD_LENGTH}-byte work area travels in the payload</h2>
 *
 * <p>{@code COACTUPC} is pseudo-conversational and keeps {@code 01 WS-THIS-PROGCOMMAREA}
 * ({@code app/cbl/COACTUPC.cbl:652}) across turns by appending it after the communication area:
 *
 * <pre>
 *   MOVE DFHCOMMAREA(LENGTH OF CARDDEMO-COMMAREA + 1:
 *                    LENGTH OF WS-THIS-PROGCOMMAREA) TO WS-THIS-PROGCOMMAREA   *&gt; :890-892
 *   ...
 *   MOVE WS-THIS-PROGCOMMAREA TO
 *        WS-COMMAREA(LENGTH OF CARDDEMO-COMMAREA + 1:
 *                    LENGTH OF WS-THIS-PROGCOMMAREA)                           *&gt; :1011-1013
 *   EXEC CICS RETURN TRANSID(LIT-THISTRANID) COMMAREA(WS-COMMAREA)
 *             LENGTH(LENGTH OF WS-COMMAREA)                                    *&gt; :1015-1019
 * </pre>
 *
 * <p>with {@code 01 WS-COMMAREA PIC X(2000)} at {@code :850}, and
 * {@code INITIALIZE WS-THIS-PROGCOMMAREA} at {@code :884}, {@code :968} and {@code :981}. Rule
 * <strong>R6</strong> and gate <strong>G37</strong> forbid turning that into server-side state, so it
 * travels here as {@link CommArea} - {@value CommArea#RECORD_LENGTH} bytes, which with the
 * {@value NavigationContext#COMMAREA_LENGTH}-byte {@code CARDDEMO-COMMAREA} in front uses
 * {@value #TOTAL_COMMAREA_LENGTH} of the 2000 available.
 *
 * <h2>{@code xxxL}, {@code xxxF} and {@code xxxA} are metadata, not payload</h2>
 *
 * <p>Three of the five items in each field are not data, and they are kept off the JSON wire - see
 * {@link FieldMetadata}, {@link #metadata(ScreenField)} and {@link #metadata()}, all
 * {@link JsonIgnore}d. They are not decoration either, because {@code COACTUPC} writes both of them
 * into the <em>input</em> group:
 *
 * <ul>
 *   <li>{@code xxxL COMP PIC S9(4)} is signed, and {@value FieldMetadata#CURSOR_HERE} means "put the
 *       cursor here". {@code COACTUPC} does {@code MOVE -1 TO ...L OF CACTUPAI} at 41 sites, among them
 *       {@code :3015} and {@code :3166}, and the {@code EVALUATE TRUE} of
 *       {@code 3300-SETUP-SCREEN-ATTRS} walks candidate fields in screen-location order. An unsigned
 *       length cannot hold {@value FieldMetadata#CURSOR_HERE}, so this is a signed carrier.</li>
 *   <li>{@code xxxA}, the {@code REDEFINES} view of the flag byte {@code xxxF}, is the attribute the
 *       program assigns, at 75 sites. {@code 3310-PROTECT-ALL-ATTRS} moves {@code DFHBMPRF} into
 *       {@code ACCTSIDA}, {@code ACSTTUSA}, {@code ACRDLIMA} and the rest from {@code :3442} onward;
 *       {@code 3320-UNPROTECT-FEW-ATTRS} and the {@code EVALUATE TRUE} above it move {@code DFHBMFSE}
 *       selectively, at {@code :2996} and {@code :3005} among others.</li>
 * </ul>
 *
 * <p>{@code FKEY05} and {@code FKEY12} make the point that this metadata is behaviour rather than
 * bookkeeping. Both are declared {@code ATTRB=(ASKIP,DRK)} - hidden - and
 * {@code app/cbl/COACTUPC.cbl:3575} and {@code :3579-3580} move {@code DFHBMASB} into
 * {@code FKEY12A} and {@code FKEY05A OF CACTUPAI} to reveal them. Whether the operator sees
 * "{@code F5=Save}" and "{@code F12=Cancel}" is carried entirely by an attribute byte.
 *
 * <p>Every attribute constant comes from {@code common/BmsAttributes}, reproduced there from IBM CICS
 * documentation because {@code DFHBMSCA}, {@code DFHATTR} and {@code DFHAID} are absent from this
 * repository (risk <strong>R-D</strong>). No raw byte is written inline anywhere in this file.
 *
 * <h2>Conversation state travels in the payload too</h2>
 *
 * <p>{@code COACTUPC} copies {@code CVCRD01Y} and {@code COCOM01Y}, and both are conversational state,
 * so this class carries them as ordinary payload members - {@link #getCardScreenState()} and
 * {@link #getNavigationContext()}. It holds no servlet session, no session-scoped attribute, no cache,
 * no thread-bound storage and no mutable static state; nothing here can outlive the request that
 * created it, which is gate <strong>G37</strong>. {@code app/cbl/COACTUPC.cbl:944-949} stamps
 * {@code CDEMO-FROM-TRANID}, {@code CDEMO-FROM-PROGRAM} and {@code CDEMO-LAST-MAPSET} from
 * {@code LIT-THISTRANID}, {@code LIT-THISPGM} and {@code LIT-THISMAPSET}, whose declared widths at
 * {@code :533-539} are {@code X(4)}, {@code X(8)}, {@code X(8)} and - for {@code LIT-THISMAP} -
 * {@code X(7)}, matching {@link NavigationContext}'s own map-name widths.
 *
 * <p>{@link NavigationContext#pgmContext()} is the flag that matters most on an inbound request:
 * {@code CDEMO-PGM-ENTER} is 0 and {@code CDEMO-PGM-REENTER} is 1, and the
 * {@code EVALUATE TRUE} at {@code app/cbl/COACTUPC.cbl:921-1004} branches on it in three of its four
 * arms. {@link #isEnter()} and {@link #isReenter()} make both states reachable from this type, which is
 * gate <strong>G38</strong>; re-entry is also what lets a field be highlighted at all, since
 * {@code app/cpy/CSSETATY.cpy} gates every one of {@code COACTUPC}'s 39 highlight sites - from
 * {@code :3208}, {@code :3214}, {@code :3220}, {@code :3226}, {@code :3232}, {@code :3238} onward - on
 * {@code CDEMO-PGM-REENTER}. Making the state reachable is this type's job; deciding on it belongs to
 * {@code common/FieldAttributeSetter} (gate <strong>G51</strong>).
 *
 * <h2>Validation is {@link Size} and nothing else</h2>
 *
 * <p>Every {@code max} is a width constant taken from a {@code PICTURE} clause. There is no
 * {@code NotBlank}, no {@code Pattern}, no digit check and no range check - <strong>not even on
 * {@code acctSid}, {@code opnYear} or {@code actSsn1}</strong> - because {@code COACTUPC} performs its
 * own edits in its own paragraphs and a constraint rejecting {@code '*'} or spaces would break
 * {@code app/cbl/COACTUPC.cbl:1051-1058} outright:
 *
 * <pre>
 *   IF  ACCTSIDI OF CACTUPAI = '*'                                 *&gt; :1051
 *   OR  ACCTSIDI OF CACTUPAI = SPACES                              *&gt; :1052
 *       MOVE LOW-VALUES           TO CC-ACCT-ID ACUP-NEW-ACCT-ID-X *&gt; :1053-1054
 *   ELSE
 *       MOVE ACCTSIDI OF CACTUPAI TO CC-ACCT-ID ACUP-NEW-ACCT-ID-X *&gt; :1056-1057
 *   END-IF
 * </pre>
 *
 * <p>{@code app/cpy/CSSETATY.cpy} writes a bare {@code '*'} into an output field as well, and
 * {@code ACTSSN1}, {@code ACTSSN2} and {@code ACTSSN3} carry {@code INITIAL='999'}, {@code '99'} and
 * {@code '9999'} placeholder masks that arrive back untouched whenever the operator does not type over
 * them. All of those are values this payload must be able to hold. The eighteen field-edit paragraphs of
 * {@code 1200-EDIT-MAP-INPUTS} - mandatory, yes/no, alpha, alphanumeric, numeric, signed 9V2, the US
 * telephone chain with its {@code EDIT-AREA-CODE}, {@code EDIT-US-PHONE-PREFIX} and
 * {@code EDIT-US-PHONE-LINENUM} stages, SSN, state code, FICO and the state-plus-zip combination -
 * belong to {@code AccountUpdateController} and {@code AccountUpdateService} with
 * {@code account/AreaCodeLookup} and {@code account/AccountDateValidator}. Pre-empting an edit here
 * would change which message the operator sees and in what order, which is a parity violation rather
 * than a hardening.
 *
 * <h2>Widths are applied deliberately, never by assignment</h2>
 *
 * <p>A COBOL alphanumeric receiver is filled from its leftmost position, padded on the right when the
 * sending value is short and truncated on the right when it is long. A plain Java assignment does
 * neither. Values are therefore stored <em>unaltered</em>, and the {@code MOVE} itself happens only
 * where it is asked for, through {@link FixedWidthCodec#movePicX(String, int)} by way of
 * {@link #image(ScreenField, FixedWidthCodec)}, {@link #normalize(FixedWidthCodec)} and
 * {@link #toGroupImage(FixedWidthCodec)} (practice <strong>B11</strong>). Reads are never trimmed: a
 * {@code PIC X} field's trailing spaces are part of its value and the parity differ compares them.
 *
 * <p>The five money items on the <em>screen</em> - {@code ACRDLIM}, {@code ACSHLIM}, {@code ACURBAL},
 * {@code ACRCYCR} and {@code ACRCYDB} - are {@code PIC X(15)} and are held as {@link String}, exactly
 * as the mapset declares them; {@code app/cbl/COACTUPC.cbl:1073-1136} moves each into a
 * {@code PIC X(15)} working-storage buffer and tests it with {@code FUNCTION TEST-NUMVAL-C} before any
 * number exists. Inside the <em>work area</em> the same five values are {@code PIC X(12)} with a
 * {@code PIC S9(10)V99} {@code REDEFINES}, and those numeric views go through
 * {@link CobolDecimal#MONETARY_SCALE} and {@link java.math.RoundingMode#DOWN} - never
 * {@code HALF_UP}, never {@code HALF_EVEN}, and never {@code double} or {@code float} (gates
 * <strong>G22</strong> and <strong>G24</strong>). {@code ROUNDED} appears zero times in all 28
 * programs, so truncation is the only faithful policy.
 *
 * <h2>Construction, immutability and thread safety</h2>
 *
 * <p>The class is <strong>immutable</strong>: every member is {@code final}, there is no setter, the
 * mutable {@link CardScreenState} is copied on the way in and on the way out, and the metadata map is
 * an unmodifiable {@link EnumMap} of immutable {@link FieldMetadata} records. Change is expressed by
 * returning a new instance - {@link #withValue(ScreenField, String)},
 * {@link #withMetadata(ScreenField, FieldMetadata)}, {@link #withCursorOn(ScreenField)},
 * {@link #withAttribute(ScreenField, byte)}, {@link #withCommArea(CommArea)},
 * {@link #withCardScreenState(CardScreenState)}, {@link #withNavigationContext(NavigationContext)} - or
 * by {@link #toBuilder()}. Instances are consequently safe to share and safe to hold as an expected
 * value in a parity case.
 *
 * <p>Nothing here needs Spring, a servlet container or Jackson to build: {@link #builder()},
 * {@link #initial()} and {@link #withAccountFilter(String)} are enough for a plain JUnit 5 test and for
 * {@code parity/ParityHarness} alike (practice <strong>B10</strong>). Jackson binds inbound JSON
 * through {@link Builder}, which is what {@link JsonDeserialize} on this type declares.
 *
 * <h2>Facts recorded rather than repaired</h2>
 *
 * <p>Practice <strong>B4</strong> requires a conflict to be documented, not quietly fixed. Five are
 * recorded here exactly as the sources read.
 *
 * <ol>
 *   <li><strong>The optimistic-concurrency paragraph is {@code 9700}, not {@code 9300}.</strong> The
 *       migration plan names {@code COACTUPC}'s check {@code 9300-CHECK-CHANGE-IN-REC}. Read directly,
 *       the label is {@code 9700-CHECK-CHANGE-IN-REC} at {@code app/cbl/COACTUPC.cbl:4109}, performed
 *       from {@code :3947-3948}, with its exit at {@code :4193}. {@code 9300} is
 *       {@code app/cbl/COCRDUPC.cbl}'s label for the same idea on the card screen.</li>
 *   <li><strong>The mapset's declaration is not where the plan places it.</strong> The plan summarises
 *       all seventeen mapsets as declaring {@code DFHMSD CTRL=(ALARM,FREEKB) EXTATT=YES}. Read
 *       directly, {@code app/bms/COACTUP.bms:20-24} declares
 *       {@code COACTUP DFHMSD LANG=COBOL, MODE=INOUT, STORAGE=AUTO, TIOAPFX=YES, TYPE=&&SYSPARM} -
 *       <strong>no {@code CTRL=} and no {@code EXTATT=}</strong> - and it is
 *       {@code app/bms/COACTUP.bms:25-28},
 *       {@code CACTUPA DFHMDI CTRL=(FREEKB), DSATTS=(COLOR,HILIGHT,PS,VALIDN),
 *       MAPATTS=(COLOR,HILIGHT,PS,VALIDN), SIZE=(24,80)}, that carries them. {@code CTRL} is
 *       {@code (FREEKB)} alone, without {@code ALARM}.</li>
 *   <li><strong>{@code 88 ACUP-CHANGES-MADE} covers five values, not two.</strong>
 *       {@code app/cbl/COACTUPC.cbl:660-662} reads
 *       {@code VALUES 'E', 'N', 'C', 'L', 'F'} across three continuation lines. A summary naming only
 *       {@code 'E'} and {@code 'N'} would make {@link ChangeAction#isChangesMade()} false for three
 *       states the program treats as "changes made", so the source wins - see
 *       {@link ChangeAction#CHANGES_MADE_VALUES}.</li>
 *   <li><strong>The two detail groups are not structurally identical.</strong> They share a geometry -
 *       {@value Details#RECORD_LENGTH} bytes each - but not a shape. See {@link DetailGroup} for the
 *       two differences and why a width check cannot catch either.</li>
 *   <li><strong>This type does not use the module's diagnostic-redaction helper.</strong> Sibling
 *       payloads route {@link #toString()} through {@code common/SensitiveDiagnostics}; this one renders
 *       every value as stored, exactly as {@code AccountViewRequest} does. Its specification requires
 *       it: {@code COACTUPC} shows {@code ACTSSN1}/{@code ACTSSN2}/{@code ACTSSN3},
 *       {@code DOBYEAR}/{@code DOBMON}/{@code DOBDAY} and {@code ACSGOVT} on a 3270 in the clear, and
 *       adding masking, redaction, a serialisation filter or a {@link JsonIgnore} to a payload member
 *       would be an unrequested behaviour change (practice <strong>B6</strong>) - as would weakening
 *       anything. {@code common/SensitiveDiagnostics} is also outside this file's declared dependency
 *       set, so importing it is not available in the first place.</li>
 * </ol>
 *
 * <p>For the same reason the two account screens are not factored into a shared header type. They
 * disagree, and the disagreement <em>is</em> the contract: the view screen has 37 fields and this one
 * has {@value #FIELD_COUNT}, and their {@code ACCTSID} entries differ in {@code ATTRB},
 * {@code PICIN} and {@code VALIDN} - {@code app/bms/COACTUP.bms:84} declares
 * {@code ATTRB=(IC,UNPROT), HILIGHT=UNDERLINE, LENGTH=11, POS=(5,38)} with no {@code PICIN}, no
 * {@code VALIDN=(MUSTFILL)}, no {@code COLOR} and no {@code FSET}. A common base class would collapse
 * those differences into one and quietly break gate <strong>G9</strong> on both screens.
 *
 * <p><strong>No user-specified rule governs this file.</strong> {@code review_rules} returns exactly
 * one line, "No user rules provided", and that one line is the whole document. Their absence is not
 * permission to lower the bar: the enterprise practices <strong>B1</strong>-<strong>B11</strong> and
 * the gates cited throughout this documentation bind in their place.
 *
 * @see AccountViewRequest the sibling view screen, whose 37 fields and numeric {@code ACCTSID} this
 *      screen deliberately does not share
 * @see CardScreenState the {@code CVCRD01Y} work area this request carries
 * @see NavigationContext the {@code COCOM01Y} communication area this request carries
 * @see FixedWidthCodec the sole home of the {@code PIC X} move rule and of the zoned-decimal alphabet
 * @see CobolDecimal the sole home of the scale-2, {@code RoundingMode.DOWN} store policy
 * @see BmsAttributes the reproduced {@code DFHBMSCA} / {@code DFHATTR} attribute constants
 */
@JsonDeserialize(builder = AccountUpdateRequest.Builder.class)
public final class AccountUpdateRequest {

    // =================================================================================================
    // Screen identity. The CSD transaction, its backing program, and the mapset and map names as the BMS
    // source spells them. Held as constants because a controller that echoes "which screen am I" back to
    // a stateless client must not hard-code a literal at the call site.
    // =================================================================================================

    /**
     * CSD transaction identifier of this screen: {@code CAUP}, from
     * {@code 05 LIT-THISTRANID PIC X(4) VALUE 'CAUP'} at {@code app/cbl/COACTUPC.cbl:535-536}.
     */
    public static final String TRANSACTION_ID = "CAUP";

    /**
     * COBOL program this type is translated from: {@code COACTUPC}, from
     * {@code 05 LIT-THISPGM PIC X(8) VALUE 'COACTUPC'} at {@code app/cbl/COACTUPC.cbl:533-534}.
     */
    public static final String PROGRAM_NAME = "COACTUPC";

    /**
     * BMS mapset name, from {@code COACTUP DFHMSD} at {@code app/bms/COACTUP.bms:20}.
     *
     * <p>{@code 05 LIT-THISMAPSET PIC X(8) VALUE 'COACTUP '} at
     * {@code app/cbl/COACTUPC.cbl:537-538} declares it eight characters wide, so the literal the
     * program moves carries one trailing space. This constant holds the name itself; padding to a
     * receiver's width is {@link FixedWidthCodec#movePicX(String, int)}'s business.
     */
    public static final String MAPSET_NAME = "COACTUP";

    /**
     * BMS map name, from {@code CACTUPA DFHMDI} at {@code app/bms/COACTUP.bms:25} and
     * {@code 05 LIT-THISMAP PIC X(7) VALUE 'CACTUPA'} at {@code app/cbl/COACTUPC.cbl:539-540}.
     *
     * <p>Seven characters, which is why the symbolic groups are {@code CACTUPAI} and {@code CACTUPAO}
     * rather than {@code COACTUPI} and {@code COACTUPO}.
     */
    public static final String MAP_NAME = "CACTUPA";

    /** Name of the input symbolic group this type projects: {@code app/cpy-bms/COACTUP.CPY:17}. */
    public static final String INPUT_GROUP_NAME = "CACTUPAI";

    /** Row count from {@code SIZE=(24,80)} on {@code CACTUPA DFHMDI}, {@code app/bms/COACTUP.bms:28}. */
    public static final int SCREEN_ROWS = 24;

    /** Column count from {@code SIZE=(24,80)} on {@code CACTUPA DFHMDI}. */
    public static final int SCREEN_COLUMNS = 80;

    /**
     * {@code DFHMDF} entries in {@code app/bms/COACTUP.bms}, of which {@value #FIELD_COUNT} carry a name
     * label. The other 74 are unnamed screen literals and hold no value this type could carry.
     */
    public static final int DFHMDF_ENTRY_COUNT = 128;

    // =================================================================================================
    // Field widths, transcribed one by one from the xxxI PICTURE clauses of app/cpy-bms/COACTUP.CPY and
    // cross-checked against the LENGTH= operands of app/bms/COACTUP.bms. All 54 pairs agree. Every width
    // in this file comes from exactly one of these constants; no width is written twice and none is
    // written inline (practice B8).
    //
    // The order below is the copybook's storage order, which is the mapset's declaration order, which is
    // the order the fields appear on the 24 x 80 screen reading left column then right column, row by
    // row. It is not sorted, grouped or otherwise improved.
    // =================================================================================================

    /** Width of {@code TRNNAMEI PIC X(4)}, {@code COACTUP.CPY:24}; {@code TRNNAME LENGTH=4}. */
    public static final int TRNNAME_LENGTH = 4;

    /** Width of {@code TITLE01I PIC X(40)}, {@code COACTUP.CPY:30}; {@code TITLE01 LENGTH=40}. */
    public static final int TITLE01_LENGTH = 40;

    /**
     * Width of {@code CURDATEI PIC X(8)}, {@code COACTUP.CPY:36}; {@code CURDATE LENGTH=8} with
     * {@code INITIAL='mm/dd/yy'} - eight characters, so the placeholder exactly fills the field.
     */
    public static final int CURDATE_LENGTH = 8;

    /** Width of {@code PGMNAMEI PIC X(8)}, {@code COACTUP.CPY:42}; {@code PGMNAME LENGTH=8}. */
    public static final int PGMNAME_LENGTH = 8;

    /** Width of {@code TITLE02I PIC X(40)}, {@code COACTUP.CPY:48}; {@code TITLE02 LENGTH=40}. */
    public static final int TITLE02_LENGTH = 40;

    /**
     * Width of {@code CURTIMEI PIC X(8)}, {@code COACTUP.CPY:54}; {@code CURTIME LENGTH=8} with
     * {@code INITIAL='hh:mm:ss'}.
     */
    public static final int CURTIME_LENGTH = 8;

    /**
     * Width of {@code ACCTSIDI PIC X(11)}, {@code COACTUP.CPY:60}; {@code ACCTSID LENGTH=11}.
     *
     * <p>Alphanumeric, and deliberately so. {@code app/bms/COACTUP.bms:84} declares
     * {@code ATTRB=(IC,UNPROT), HILIGHT=UNDERLINE, LENGTH=11, POS=(5,38)} and <strong>no
     * {@code PICIN}</strong> and <strong>no {@code VALIDN=(MUSTFILL)}</strong>, unlike the view screen's
     * entry of the same name and width. The program depends on the difference:
     * {@code app/cbl/COACTUPC.cbl:1051-1052} tests the item against {@code '*'} and against
     * {@code SPACES}, and {@code :1056-1057} moves it into an {@code X(11)} receiver. Binding this to an
     * integral type, or constraining it to digits, would make that live branch unreproducible.
     */
    public static final int ACCTSID_LENGTH = 11;

    /** Width of {@code ACSTTUSI PIC X(1)}, {@code COACTUP.CPY:66}; {@code ACSTTUS LENGTH=1}. */
    public static final int ACSTTUS_LENGTH = 1;

    /**
     * Width of {@code OPNYEARI PIC X(4)}, {@code COACTUP.CPY:72}; {@code OPNYEAR LENGTH=4}.
     *
     * <p>Part one of three of the account open date. The program reassembles it with
     * {@code STRING ... DELIMITED BY SIZE} at {@code app/cbl/COACTUPC.cbl:1809}; the separators are the
     * program's, so the wire supplies the parts.
     */
    public static final int OPNYEAR_LENGTH = 4;

    /** Width of {@code OPNMONI PIC X(2)}, {@code COACTUP.CPY:78}; {@code OPNMON LENGTH=2}. */
    public static final int OPNMON_LENGTH = 2;

    /** Width of {@code OPNDAYI PIC X(2)}, {@code COACTUP.CPY:84}; {@code OPNDAY LENGTH=2}. */
    public static final int OPNDAY_LENGTH = 2;

    /**
     * Width of {@code ACRDLIMI PIC X(15)}, {@code COACTUP.CPY:90}; {@code ACRDLIM LENGTH=15}.
     *
     * <p>One of the five money items on the screen, and alphanumeric on both sides of this mapset -
     * there is no {@code PICIN} and no {@code PICOUT} anywhere in {@code app/bms/COACTUP.bms}.
     * {@code app/cbl/COACTUPC.cbl:1077-1078} moves it into {@code ACUP-NEW-CREDIT-LIMIT-X PIC X(15)}
     * ({@code :412}) and tests it with {@code FUNCTION TEST-NUMVAL-C} before any number exists.
     */
    public static final int ACRDLIM_LENGTH = 15;

    /** Width of {@code EXPYEARI PIC X(4)}, {@code COACTUP.CPY:96}; {@code EXPYEAR LENGTH=4}. */
    public static final int EXPYEAR_LENGTH = 4;

    /** Width of {@code EXPMONI PIC X(2)}, {@code COACTUP.CPY:102}; {@code EXPMON LENGTH=2}. */
    public static final int EXPMON_LENGTH = 2;

    /** Width of {@code EXPDAYI PIC X(2)}, {@code COACTUP.CPY:108}; {@code EXPDAY LENGTH=2}. */
    public static final int EXPDAY_LENGTH = 2;

    /** Width of {@code ACSHLIMI PIC X(15)}, {@code COACTUP.CPY:114}; {@code ACSHLIM LENGTH=15}. */
    public static final int ACSHLIM_LENGTH = 15;

    /** Width of {@code RISYEARI PIC X(4)}, {@code COACTUP.CPY:120}; {@code RISYEAR LENGTH=4}. */
    public static final int RISYEAR_LENGTH = 4;

    /** Width of {@code RISMONI PIC X(2)}, {@code COACTUP.CPY:126}; {@code RISMON LENGTH=2}. */
    public static final int RISMON_LENGTH = 2;

    /** Width of {@code RISDAYI PIC X(2)}, {@code COACTUP.CPY:132}; {@code RISDAY LENGTH=2}. */
    public static final int RISDAY_LENGTH = 2;

    /** Width of {@code ACURBALI PIC X(15)}, {@code COACTUP.CPY:138}; {@code ACURBAL LENGTH=15}. */
    public static final int ACURBAL_LENGTH = 15;

    /** Width of {@code ACRCYCRI PIC X(15)}, {@code COACTUP.CPY:144}; {@code ACRCYCR LENGTH=15}. */
    public static final int ACRCYCR_LENGTH = 15;

    /** Width of {@code AADDGRPI PIC X(10)}, {@code COACTUP.CPY:150}; {@code AADDGRP LENGTH=10}. */
    public static final int AADDGRP_LENGTH = 10;

    /** Width of {@code ACRCYDBI PIC X(15)}, {@code COACTUP.CPY:156}; {@code ACRCYDB LENGTH=15}. */
    public static final int ACRCYDB_LENGTH = 15;

    /** Width of {@code ACSTNUMI PIC X(9)}, {@code COACTUP.CPY:162}; {@code ACSTNUM LENGTH=9}. */
    public static final int ACSTNUM_LENGTH = 9;

    /**
     * Width of {@code ACTSSN1I PIC X(3)}, {@code COACTUP.CPY:168}; {@code ACTSSN1 LENGTH=3} with
     * {@code INITIAL='999'}.
     *
     * <p>Part one of three of the social security number, which is exactly why this screen splits it:
     * the three parts map straight onto {@code ACUP-NEW-CUST-SSN-1}, {@code -2} and {@code -3}
     * ({@code app/cbl/COACTUPC.cbl:830-833}). The {@code INITIAL} literal is screen furniture - a
     * placeholder mask the operator types over - and not a validation rule, so a request carrying
     * {@code "999"} is well formed and must not be rejected.
     */
    public static final int ACTSSN1_LENGTH = 3;

    /**
     * Width of {@code ACTSSN2I PIC X(2)}, {@code COACTUP.CPY:174}; {@code ACTSSN2 LENGTH=2} with
     * {@code INITIAL='99'}.
     */
    public static final int ACTSSN2_LENGTH = 2;

    /**
     * Width of {@code ACTSSN3I PIC X(4)}, {@code COACTUP.CPY:180}; {@code ACTSSN3 LENGTH=4} with
     * {@code INITIAL='9999'}.
     */
    public static final int ACTSSN3_LENGTH = 4;

    /** Width of {@code DOBYEARI PIC X(4)}, {@code COACTUP.CPY:186}; {@code DOBYEAR LENGTH=4}. */
    public static final int DOBYEAR_LENGTH = 4;

    /** Width of {@code DOBMONI PIC X(2)}, {@code COACTUP.CPY:192}; {@code DOBMON LENGTH=2}. */
    public static final int DOBMON_LENGTH = 2;

    /** Width of {@code DOBDAYI PIC X(2)}, {@code COACTUP.CPY:198}; {@code DOBDAY LENGTH=2}. */
    public static final int DOBDAY_LENGTH = 2;

    /** Width of {@code ACSTFCOI PIC X(3)}, {@code COACTUP.CPY:204}; {@code ACSTFCO LENGTH=3}. */
    public static final int ACSTFCO_LENGTH = 3;

    /** Width of {@code ACSFNAMI PIC X(25)}, {@code COACTUP.CPY:210}; {@code ACSFNAM LENGTH=25}. */
    public static final int ACSFNAM_LENGTH = 25;

    /** Width of {@code ACSMNAMI PIC X(25)}, {@code COACTUP.CPY:216}; {@code ACSMNAM LENGTH=25}. */
    public static final int ACSMNAM_LENGTH = 25;

    /** Width of {@code ACSLNAMI PIC X(25)}, {@code COACTUP.CPY:222}; {@code ACSLNAM LENGTH=25}. */
    public static final int ACSLNAM_LENGTH = 25;

    /** Width of {@code ACSADL1I PIC X(50)}, {@code COACTUP.CPY:228}; {@code ACSADL1 LENGTH=50}. */
    public static final int ACSADL1_LENGTH = 50;

    /** Width of {@code ACSSTTEI PIC X(2)}, {@code COACTUP.CPY:234}; {@code ACSSTTE LENGTH=2}. */
    public static final int ACSSTTE_LENGTH = 2;

    /** Width of {@code ACSADL2I PIC X(50)}, {@code COACTUP.CPY:240}; {@code ACSADL2 LENGTH=50}. */
    public static final int ACSADL2_LENGTH = 50;

    /**
     * Width of {@code ACSZIPCI PIC X(5)}, {@code COACTUP.CPY:246}; {@code ACSZIPC LENGTH=5}.
     *
     * <p>Five on the screen, while the work area's {@code ACUP-xxx-CUST-ADDR-ZIP} is {@code PIC X(10)}
     * ({@code app/cbl/COACTUPC.cbl:721}). The two widths are both correct and neither is derived from
     * the other, so both are declared where they belong.
     */
    public static final int ACSZIPC_LENGTH = 5;

    /** Width of {@code ACSCITYI PIC X(50)}, {@code COACTUP.CPY:252}; {@code ACSCITY LENGTH=50}. */
    public static final int ACSCITY_LENGTH = 50;

    /** Width of {@code ACSCTRYI PIC X(3)}, {@code COACTUP.CPY:258}; {@code ACSCTRY LENGTH=3}. */
    public static final int ACSCTRY_LENGTH = 3;

    /**
     * Width of {@code ACSPH1AI PIC X(3)}, {@code COACTUP.CPY:264}; {@code ACSPH1A LENGTH=3}.
     *
     * <p>The area code of the first telephone number, validated by {@code EDIT-AREA-CODE} against
     * {@code account/AreaCodeLookup}. Its counterpart inside the work area is
     * {@code ACUP-xxx-CUST-PHONE-NUM-1A}, a {@code REDEFINES} part of a {@code PIC X(15)} span whose
     * {@code FILLER} positions hold the punctuation ({@code app/cbl/COACTUPC.cbl:723-731}).
     */
    public static final int ACSPH1A_LENGTH = 3;

    /** Width of {@code ACSPH1BI PIC X(3)}, {@code COACTUP.CPY:270}; {@code ACSPH1B LENGTH=3}. */
    public static final int ACSPH1B_LENGTH = 3;

    /** Width of {@code ACSPH1CI PIC X(4)}, {@code COACTUP.CPY:276}; {@code ACSPH1C LENGTH=4}. */
    public static final int ACSPH1C_LENGTH = 4;

    /** Width of {@code ACSGOVTI PIC X(20)}, {@code COACTUP.CPY:282}; {@code ACSGOVT LENGTH=20}. */
    public static final int ACSGOVT_LENGTH = 20;

    /** Width of {@code ACSPH2AI PIC X(3)}, {@code COACTUP.CPY:288}; {@code ACSPH2A LENGTH=3}. */
    public static final int ACSPH2A_LENGTH = 3;

    /** Width of {@code ACSPH2BI PIC X(3)}, {@code COACTUP.CPY:294}; {@code ACSPH2B LENGTH=3}. */
    public static final int ACSPH2B_LENGTH = 3;

    /** Width of {@code ACSPH2CI PIC X(4)}, {@code COACTUP.CPY:300}; {@code ACSPH2C LENGTH=4}. */
    public static final int ACSPH2C_LENGTH = 4;

    /** Width of {@code ACSEFTCI PIC X(10)}, {@code COACTUP.CPY:306}; {@code ACSEFTC LENGTH=10}. */
    public static final int ACSEFTC_LENGTH = 10;

    /** Width of {@code ACSPFLGI PIC X(1)}, {@code COACTUP.CPY:312}; {@code ACSPFLG LENGTH=1}. */
    public static final int ACSPFLG_LENGTH = 1;

    /**
     * Width of {@code INFOMSGI PIC X(45)}, {@code COACTUP.CPY:318}; {@code INFOMSG LENGTH=45}.
     *
     * <p>The <em>map</em> width, and the map width is the contract. The program's working-storage
     * message items are narrower and COBOL right-pads an alphanumeric receiver across the move, so a
     * narrower number must never appear here.
     */
    public static final int INFOMSG_LENGTH = 45;

    /** Width of {@code ERRMSGI PIC X(78)}, {@code COACTUP.CPY:324}; {@code ERRMSG LENGTH=78}. */
    public static final int ERRMSG_LENGTH = 78;

    /**
     * Width of {@code FKEYSI PIC X(21)}, {@code COACTUP.CPY:330}; {@code FKEYS LENGTH=21} with
     * {@code INITIAL='ENTER=Process F3=Exit'}, which is exactly twenty-one characters - a useful
     * independent signal that the width was read correctly.
     */
    public static final int FKEYS_LENGTH = 21;

    /**
     * Width of {@code FKEY05I PIC X(7)}, {@code COACTUP.CPY:336}; {@code FKEY05 LENGTH=7} with
     * {@code INITIAL='F5=Save'}, again exactly the declared width.
     *
     * <p>Declared {@code ATTRB=(ASKIP,DRK)} - hidden - at {@code app/bms/COACTUP.bms:498}, and revealed
     * only when {@code app/cbl/COACTUPC.cbl:3579-3580} moves {@code DFHBMASB} into
     * {@code FKEY05A OF CACTUPAI}. Its visibility therefore lives in the field's attribute metadata, not
     * in its value.
     */
    public static final int FKEY05_LENGTH = 7;

    /**
     * Width of {@code FKEY12I PIC X(10)}, {@code COACTUP.CPY:342}; {@code FKEY12 LENGTH=10} with
     * {@code INITIAL='F12=Cancel'}.
     *
     * <p>Also {@code ATTRB=(ASKIP,DRK)} ({@code app/bms/COACTUP.bms:503}), revealed by
     * {@code app/cbl/COACTUPC.cbl:3575}.
     */
    public static final int FKEY12_LENGTH = 10;

    // =================================================================================================
    // Group geometry. Each number is derived rather than written down wherever it can be, and the static
    // initialiser below checks the three that a reader would otherwise have to take on trust.
    // =================================================================================================

    /**
     * Bytes of {@code 02 FILLER PIC X(12)} at the head of the group, {@code app/cpy-bms/COACTUP.CPY:18}.
     *
     * <p>Generated because {@code app/bms/COACTUP.bms:23} declares {@code TIOAPFX=YES}: the terminal
     * input/output area prefix belongs to CICS, carries no application data, and is written as spaces.
     */
    public static final int TIOAPFX_LENGTH = 12;

    /**
     * Bytes of {@code 02 xxxL COMP PIC S9(4)}, the signed binary halfword CICS reports a received field's
     * length in - and the channel {@code MOVE -1 TO ...L} uses to position the cursor.
     */
    public static final int LENGTH_ITEM_LENGTH = 2;

    /** Bytes of {@code 02 xxxF PICTURE X}, the flag byte that {@code 03 xxxA PICTURE X} redefines. */
    public static final int FLAG_ITEM_LENGTH = 1;

    /**
     * Bytes of the unnamed {@code 02 FILLER PICTURE X(4)} between the flag byte and the data.
     *
     * <p>Unnamed and unused on input. The output group names the same four bytes {@code xxxC},
     * {@code xxxP}, {@code xxxH} and {@code xxxV} - the
     * {@code DSATTS}/{@code MAPATTS=(COLOR,HILIGHT,PS,VALIDN)} set of
     * {@code app/bms/COACTUP.bms:26-27} - and they are {@code AccountUpdateResponse}'s concern.
     */
    public static final int EXTENDED_ATTRIBUTE_ITEM_LENGTH = 4;

    /**
     * Bytes each field costs before its data begins: {@value #LENGTH_ITEM_LENGTH} +
     * {@value #FLAG_ITEM_LENGTH} + {@value #EXTENDED_ATTRIBUTE_ITEM_LENGTH} = 7, so one field occupies
     * {@code 7 + n} bytes.
     */
    public static final int FIELD_OVERHEAD =
            LENGTH_ITEM_LENGTH + FLAG_ITEM_LENGTH + EXTENDED_ATTRIBUTE_ITEM_LENGTH;

    /**
     * Name-labelled {@code DFHMDF} entries in {@code app/bms/COACTUP.bms}, of
     * {@value #DFHMDF_ENTRY_COUNT} entries in all.
     */
    public static final int FIELD_COUNT = 54;

    /**
     * Bytes of field data in the group - <strong>705</strong>.
     *
     * <p>Written as the sum of the 54 width constants, not as the literal 705, so a corrected width
     * propagates here instead of leaving two numbers to disagree. The static initialiser still checks the
     * total, because a width wrong in the same direction as this sum would otherwise go unnoticed.
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
     * Bytes in the whole {@code CACTUPAI} group: {@value #TIOAPFX_LENGTH} + {@value #FIELD_COUNT}
     * &times; {@value #FIELD_OVERHEAD} + {@value #PAYLOAD_LENGTH} = <strong>1095</strong>.
     *
     * <p>{@code CACTUPAO REDEFINES CACTUPAI}, so the output group is the same 1095 bytes. That is the
     * storage {@link #toGroupImage(FixedWidthCodec)} produces and
     * {@link #fromGroupImage(byte[], FixedWidthCodec)} consumes, and the area
     * {@code parity/ParityHarness} fingerprints.
     */
    public static final int GROUP_LENGTH =
            TIOAPFX_LENGTH + FIELD_COUNT * FIELD_OVERHEAD + PAYLOAD_LENGTH;

    /**
     * Bytes {@code COACTUPC} actually uses of {@code 01 WS-COMMAREA PIC X(2000)}
     * ({@code app/cbl/COACTUPC.cbl:850}): the {@value NavigationContext#COMMAREA_LENGTH}-byte
     * {@code CARDDEMO-COMMAREA} followed by the {@value CommArea#RECORD_LENGTH}-byte
     * {@code WS-THIS-PROGCOMMAREA}.
     *
     * <p>The program nonetheless returns the full 2000 - {@code LENGTH(LENGTH OF WS-COMMAREA)} at
     * {@code :1018} - so this number is what is <em>meaningful</em>, not what is transmitted.
     */
    public static final int TOTAL_COMMAREA_LENGTH =
            NavigationContext.COMMAREA_LENGTH + CommArea.RECORD_LENGTH;

    /**
     * Declared capacity of {@code 01 WS-COMMAREA PIC X(2000)}, {@code app/cbl/COACTUPC.cbl:850}.
     *
     * <p>Recorded because the headroom is real and load-bearing: {@value #TOTAL_COMMAREA_LENGTH} of 2000
     * are used, so a future field can be added to the work area without the {@code RETURN} length
     * changing. It is not a limit this type enforces.
     */
    public static final int COMMAREA_CAPACITY = 2000;

    /**
     * The single space character, the pad and fill character of every field here.
     *
     * <p>Held as a {@code char} rather than a byte because its encoding depends on the code page -
     * {@code 0x20} in US-ASCII and {@code 0x40} in IBM037 - and the encoding is
     * {@link FixedWidthCodec}'s business, resolved from {@link FixedWidthCodec#charset()} at the point of
     * use. Nothing in this file assumes a platform default charset (practice <strong>B8</strong>).
     */
    private static final char SPACE = ' ';

    /**
     * The {@code LOW-VALUES} character: {@code U+0000}.
     *
     * <p>{@code LOW-VALUES} is by definition the lowest character of the collating sequence, which is
     * {@code X'00'} in both ASCII and EBCDIC, so this is correct on either code page without consulting a
     * charset. It is the declared initial value of {@code ACUP-CHANGE-ACTION}
     * ({@code app/cbl/COACTUPC.cbl:654-655}) and what {@code app/cbl/COACTUPC.cbl:1053} moves into
     * {@code ACUP-NEW-ACCT-ID-X} when the operator clears the account filter.
     */
    private static final char LOW_VALUE = '\u0000';

    /** The {@code LOW-VALUES} byte: binary zero, the unset state of a flag and attribute byte. */
    private static final byte LOW_VALUE_BYTE = 0x00;

    /**
     * The character-level {@code PICTURE} rules: the {@code PIC X} and {@code PIC 9} move alignment, and
     * the zoned-decimal digit and sign alphabet used by the {@code REDEFINES} views of
     * {@link AcctSnapshot} and {@link CustSnapshot}.
     *
     * <p>US-ASCII rather than a configured code page, and that is a deliberate, bounded choice made for
     * exactly the same reason {@code card/dto/CardScreenState} makes it. Everything routed through this
     * instance is decided on <em>characters</em>: how far to pad, which end to truncate, whether a
     * position holds a digit, and which zone a sign overpunch carries. Those answers are identical under
     * IBM037 and US-ASCII, and no byte produced here reaches a dataset. Every entry point that does
     * produce or consume bytes - {@link #toGroupImage(FixedWidthCodec)},
     * {@link #fromGroupImage(byte[], FixedWidthCodec)}, {@link CommArea#encode(FixedWidthCodec)},
     * {@link CommArea#decode(byte[], FixedWidthCodec)} - takes its codec, and therefore its code page,
     * explicitly from the caller.
     */
    private static final FixedWidthCodec PICTURE_RULES =
            new FixedWidthCodec(StandardCharsets.US_ASCII);

    static {
        // Fail before the first byte comparison rather than after it. The three numbers stated in the
        // class documentation are checked here against the constants, and then the field strides are
        // walked from the end of the prefix to confirm the last field ends exactly on GROUP_LENGTH.
        //
        // FIELD_OVERHEAD, PAYLOAD_LENGTH and GROUP_LENGTH are compile-time constant expressions, so javac
        // decides each comparison: while the arithmetic holds the condition is constantly false, the
        // statement is elided and these three cost nothing at run time. Break a width and the condition
        // becomes constantly true, javac emits the throw, and the class fails to initialise on first use.
        // verifyFieldStrides() compares values read from ScreenField, which are not constants, so that
        // check is live in every build.
        if (FIELD_OVERHEAD != 7) {
            throw new IllegalStateException("Each COACTUP.CPY input field carries 2 + 1 + 4 = 7 bytes "
                    + "of length, flag and extended-attribute storage before its data, but "
                    + "FIELD_OVERHEAD computes to " + FIELD_OVERHEAD);
        }
        if (PAYLOAD_LENGTH != 705) {
            throw new IllegalStateException("The 54 xxxI PICTURE widths of app/cpy-bms/COACTUP.CPY sum "
                    + "to 705, which is also the sum of the 54 LENGTH= operands of "
                    + "app/bms/COACTUP.bms, but the width constants sum to " + PAYLOAD_LENGTH);
        }
        if (GROUP_LENGTH != 1095) {
            throw new IllegalStateException("The CACTUPAI group is 12 + 54 * 7 + 705 = 1095 bytes, but "
                    + "the constants compute " + GROUP_LENGTH);
        }
        // A PIC S9(10)V99 item occupies p + s characters with the sign overpunched into the trailing
        // byte, so the declared twelve-byte width and the digit counts have to agree. They are stated
        // separately - the width because the group's byte geometry depends on it, the digit counts
        // because the decode does - and a disagreement between them would put a phantom sign position
        // into one of the five money spans of each group while leaving the group's total width intact,
        // which no size check could then detect.
        if (AcctSnapshot.MONEY_INTEGER_DIGITS + CobolDecimal.MONETARY_SCALE
                != AcctSnapshot.MONEY_LENGTH) {
            throw new IllegalStateException("ACUP-xxx-CURR-BAL-N is PIC S9(10)V99, so its "
                    + AcctSnapshot.MONEY_INTEGER_DIGITS + " integer digits plus its "
                    + CobolDecimal.MONETARY_SCALE + " fraction digits must occupy the "
                    + AcctSnapshot.MONEY_LENGTH + " bytes ACUP-xxx-CURR-BAL declares, but they sum to "
                    + (AcctSnapshot.MONEY_INTEGER_DIGITS + CobolDecimal.MONETARY_SCALE));
        }
        verifyFieldStrides();
    }

    /**
     * Walks the {@value #FIELD_COUNT} {@link ScreenField} constants in declaration order and confirms
     * that each one begins where its predecessor ended, that the first begins immediately after the
     * {@value #TIOAPFX_LENGTH}-byte prefix, and that the last ends exactly on {@value #GROUP_LENGTH}.
     *
     * <p>This is the check that makes the transcribed data offsets safe. Each offset is written out
     * explicitly on its enum constant so a reviewer can compare it with the copybook without doing
     * arithmetic, and this walk is what stops an explicit number from being explicitly wrong.
     *
     * @throws IllegalStateException if any field's storage does not abut its neighbours, if the count of
     *                               constants is not {@value #FIELD_COUNT}, or if the widths do not sum
     *                               to {@value #PAYLOAD_LENGTH}
     */
    private static void verifyFieldStrides() {
        ScreenField[] fields = ScreenField.values();
        if (fields.length != FIELD_COUNT) {
            throw new IllegalStateException("app/bms/COACTUP.bms carries " + FIELD_COUNT
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
                        + ", so the CACTUPAI group would have a gap or an overlap there");
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

    // =================================================================================================
    // The 54 payload members, declared in the order app/cpy-bms/COACTUP.CPY lays their storage down.
    //
    // Naming is mechanical: the Java member and its JSON name are the DFHMDF label lower-cased, with no
    // re-spelling, no expansion of an abbreviation and no exception. TRNNAME becomes trnname, ACSADL1
    // becomes acsadl1, ACTSSN1 becomes actssn1. One rule applied 54 times means a reviewer can go from a
    // parity diff naming ACSGOVT to the member holding it without a lookup table, and the DFHMDF label
    // itself is carried verbatim on ScreenField.label() as well as in each member's Javadoc, so the gate
    // G9 trace is readable from either end.
    //
    // Every member is a final String, because every one of the 54 xxxI items is PIC X(n) - this mapset
    // declares no PICIN at all. None is numeric, so no scaled decimal, no double, no float and no
    // rounding mode appears among them (gates G22 and G24).
    //
    // Validation is @Size and nothing else, with every max taken from a PICTURE width. Nothing rejects
    // '*', spaces or LOW-VALUES, all three of which are values COACTUPC both writes and reads.
    // =================================================================================================

    /** {@code TRNNAMEI PIC X(4)} - the transaction identifier echoed at {@code POS=(1,7)}. */
    @Size(max = TRNNAME_LENGTH)
    private final String trnname;

    /** {@code TITLE01I PIC X(40)} - the first title line, {@code POS=(1,21)}, {@code COLOR=YELLOW}. */
    @Size(max = TITLE01_LENGTH)
    private final String title01;

    /** {@code CURDATEI PIC X(8)} - the current date, {@code POS=(1,71)}, {@code INITIAL='mm/dd/yy'}. */
    @Size(max = CURDATE_LENGTH)
    private final String curdate;

    /** {@code PGMNAMEI PIC X(8)} - the program name echoed at {@code POS=(2,7)}. */
    @Size(max = PGMNAME_LENGTH)
    private final String pgmname;

    /** {@code TITLE02I PIC X(40)} - the second title line, {@code POS=(2,21)}. */
    @Size(max = TITLE02_LENGTH)
    private final String title02;

    /** {@code CURTIMEI PIC X(8)} - the current time, {@code POS=(2,71)}, {@code INITIAL='hh:mm:ss'}. */
    @Size(max = CURTIME_LENGTH)
    private final String curtime;

    /**
     * {@code ACCTSIDI PIC X(11)} - the account filter, {@code POS=(5,38)}, {@code ATTRB=(IC,UNPROT)}.
     *
     * <p>The insertion cursor starts here. Alphanumeric with no digit constraint, because
     * {@code app/cbl/COACTUPC.cbl:1051-1052} tests it against {@code '*'} and {@code SPACES}.
     */
    @Size(max = ACCTSID_LENGTH)
    private final String acctsid;

    /**
     * {@code ACSTTUSI PIC X(1)} - the active status, {@code POS=(5,70)}, {@code ATTRB=(UNPROT)}.
     *
     * <p>Read at {@code app/cbl/COACTUPC.cbl:1065-1070}, which again tests {@code '*'} and
     * {@code SPACES} before moving the value into {@code ACUP-NEW-ACTIVE-STATUS}.
     */
    @Size(max = ACSTTUS_LENGTH)
    private final String acsttus;

    /** {@code OPNYEARI PIC X(4)} - open date year, {@code POS=(6,17)}, {@code JUSTIFY=(RIGHT)}. */
    @Size(max = OPNYEAR_LENGTH)
    private final String opnyear;

    /** {@code OPNMONI PIC X(2)} - open date month, {@code POS=(6,24)}, {@code JUSTIFY=(RIGHT)}. */
    @Size(max = OPNMON_LENGTH)
    private final String opnmon;

    /** {@code OPNDAYI PIC X(2)} - open date day, {@code POS=(6,29)}, {@code JUSTIFY=(RIGHT)}. */
    @Size(max = OPNDAY_LENGTH)
    private final String opnday;

    /** {@code ACRDLIMI PIC X(15)} - credit limit, {@code POS=(6,61)}, {@code ATTRB=(FSET,UNPROT)}. */
    @Size(max = ACRDLIM_LENGTH)
    private final String acrdlim;

    /** {@code EXPYEARI PIC X(4)} - expiry date year, {@code POS=(7,17)}, {@code JUSTIFY=(RIGHT)}. */
    @Size(max = EXPYEAR_LENGTH)
    private final String expyear;

    /** {@code EXPMONI PIC X(2)} - expiry date month, {@code POS=(7,24)}, {@code JUSTIFY=(RIGHT)}. */
    @Size(max = EXPMON_LENGTH)
    private final String expmon;

    /** {@code EXPDAYI PIC X(2)} - expiry date day, {@code POS=(7,29)}, {@code JUSTIFY=(RIGHT)}. */
    @Size(max = EXPDAY_LENGTH)
    private final String expday;

    /** {@code ACSHLIMI PIC X(15)} - cash credit limit, {@code POS=(7,61)}. */
    @Size(max = ACSHLIM_LENGTH)
    private final String acshlim;

    /** {@code RISYEARI PIC X(4)} - reissue date year, {@code POS=(8,17)}, {@code JUSTIFY=(RIGHT)}. */
    @Size(max = RISYEAR_LENGTH)
    private final String risyear;

    /** {@code RISMONI PIC X(2)} - reissue date month, {@code POS=(8,24)}, {@code JUSTIFY=(RIGHT)}. */
    @Size(max = RISMON_LENGTH)
    private final String rismon;

    /** {@code RISDAYI PIC X(2)} - reissue date day, {@code POS=(8,29)}, {@code JUSTIFY=(RIGHT)}. */
    @Size(max = RISDAY_LENGTH)
    private final String risday;

    /** {@code ACURBALI PIC X(15)} - current balance, {@code POS=(8,61)}. */
    @Size(max = ACURBAL_LENGTH)
    private final String acurbal;

    /** {@code ACRCYCRI PIC X(15)} - current cycle credit, {@code POS=(9,61)}. */
    @Size(max = ACRCYCR_LENGTH)
    private final String acrcycr;

    /** {@code AADDGRPI PIC X(10)} - the account group identifier, {@code POS=(10,23)}. */
    @Size(max = AADDGRP_LENGTH)
    private final String aaddgrp;

    /** {@code ACRCYDBI PIC X(15)} - current cycle debit, {@code POS=(10,61)}. */
    @Size(max = ACRCYDB_LENGTH)
    private final String acrcydb;

    /** {@code ACSTNUMI PIC X(9)} - the customer number, {@code POS=(12,23)}. */
    @Size(max = ACSTNUM_LENGTH)
    private final String acstnum;

    /** {@code ACTSSN1I PIC X(3)} - SSN part 1, {@code POS=(12,55)}, {@code INITIAL='999'}. */
    @Size(max = ACTSSN1_LENGTH)
    private final String actssn1;

    /** {@code ACTSSN2I PIC X(2)} - SSN part 2, {@code POS=(12,61)}, {@code INITIAL='99'}. */
    @Size(max = ACTSSN2_LENGTH)
    private final String actssn2;

    /** {@code ACTSSN3I PIC X(4)} - SSN part 3, {@code POS=(12,66)}, {@code INITIAL='9999'}. */
    @Size(max = ACTSSN3_LENGTH)
    private final String actssn3;

    /** {@code DOBYEARI PIC X(4)} - date of birth year, {@code POS=(13,23)}. */
    @Size(max = DOBYEAR_LENGTH)
    private final String dobyear;

    /** {@code DOBMONI PIC X(2)} - date of birth month, {@code POS=(13,30)}. */
    @Size(max = DOBMON_LENGTH)
    private final String dobmon;

    /** {@code DOBDAYI PIC X(2)} - date of birth day, {@code POS=(13,35)}. */
    @Size(max = DOBDAY_LENGTH)
    private final String dobday;

    /** {@code ACSTFCOI PIC X(3)} - the FICO credit score, {@code POS=(13,62)}. */
    @Size(max = ACSTFCO_LENGTH)
    private final String acstfco;

    /** {@code ACSFNAMI PIC X(25)} - first name, {@code POS=(15,1)}. */
    @Size(max = ACSFNAM_LENGTH)
    private final String acsfnam;

    /** {@code ACSMNAMI PIC X(25)} - middle name, {@code POS=(15,28)}. */
    @Size(max = ACSMNAM_LENGTH)
    private final String acsmnam;

    /** {@code ACSLNAMI PIC X(25)} - last name, {@code POS=(15,55)}. */
    @Size(max = ACSLNAM_LENGTH)
    private final String acslnam;

    /** {@code ACSADL1I PIC X(50)} - address line 1, {@code POS=(16,10)}. */
    @Size(max = ACSADL1_LENGTH)
    private final String acsadl1;

    /** {@code ACSSTTEI PIC X(2)} - the state code, {@code POS=(16,73)}. */
    @Size(max = ACSSTTE_LENGTH)
    private final String acsstte;

    /** {@code ACSADL2I PIC X(50)} - address line 2, {@code POS=(17,10)}. */
    @Size(max = ACSADL2_LENGTH)
    private final String acsadl2;

    /** {@code ACSZIPCI PIC X(5)} - the postal code, {@code POS=(17,73)}. */
    @Size(max = ACSZIPC_LENGTH)
    private final String acszipc;

    /** {@code ACSCITYI PIC X(50)} - the city, which is address line 3 in the record, {@code POS=(18,10)}. */
    @Size(max = ACSCITY_LENGTH)
    private final String acscity;

    /** {@code ACSCTRYI PIC X(3)} - the country code, {@code POS=(18,73)}. */
    @Size(max = ACSCTRY_LENGTH)
    private final String acsctry;

    /** {@code ACSPH1AI PIC X(3)} - telephone 1 area code, {@code POS=(19,10)}. */
    @Size(max = ACSPH1A_LENGTH)
    private final String acsph1a;

    /** {@code ACSPH1BI PIC X(3)} - telephone 1 prefix, {@code POS=(19,14)}. */
    @Size(max = ACSPH1B_LENGTH)
    private final String acsph1b;

    /** {@code ACSPH1CI PIC X(4)} - telephone 1 line number, {@code POS=(19,18)}. */
    @Size(max = ACSPH1C_LENGTH)
    private final String acsph1c;

    /** {@code ACSGOVTI PIC X(20)} - the government-issued identifier reference, {@code POS=(19,58)}. */
    @Size(max = ACSGOVT_LENGTH)
    private final String acsgovt;

    /** {@code ACSPH2AI PIC X(3)} - telephone 2 area code, {@code POS=(20,10)}. */
    @Size(max = ACSPH2A_LENGTH)
    private final String acsph2a;

    /** {@code ACSPH2BI PIC X(3)} - telephone 2 prefix, {@code POS=(20,14)}. */
    @Size(max = ACSPH2B_LENGTH)
    private final String acsph2b;

    /** {@code ACSPH2CI PIC X(4)} - telephone 2 line number, {@code POS=(20,18)}. */
    @Size(max = ACSPH2C_LENGTH)
    private final String acsph2c;

    /** {@code ACSEFTCI PIC X(10)} - the electronic funds transfer account identifier, {@code POS=(20,41)}. */
    @Size(max = ACSEFTC_LENGTH)
    private final String acseftc;

    /** {@code ACSPFLGI PIC X(1)} - the primary card holder indicator, {@code POS=(20,78)}. */
    @Size(max = ACSPFLG_LENGTH)
    private final String acspflg;

    /** {@code INFOMSGI PIC X(45)} - the informational message line, {@code POS=(22,23)}. */
    @Size(max = INFOMSG_LENGTH)
    private final String infomsg;

    /** {@code ERRMSGI PIC X(78)} - the error message line, {@code POS=(23,1)}, {@code COLOR=RED}. */
    @Size(max = ERRMSG_LENGTH)
    private final String errmsg;

    /** {@code FKEYSI PIC X(21)} - the always-visible function-key legend, {@code POS=(24,1)}. */
    @Size(max = FKEYS_LENGTH)
    private final String fkeys;

    /** {@code FKEY05I PIC X(7)} - the conditionally revealed "{@code F5=Save}" legend, {@code POS=(24,23)}. */
    @Size(max = FKEY05_LENGTH)
    private final String fkey05;

    /** {@code FKEY12I PIC X(10)} - the conditionally revealed "{@code F12=Cancel}" legend, {@code POS=(24,31)}. */
    @Size(max = FKEY12_LENGTH)
    private final String fkey12;

    // =================================================================================================
    // Conversation state. All three carriers travel in the payload; none is ever held server side.
    // Together they are the whole of what CICS would have kept in the COMMAREA and the TIOA.
    // =================================================================================================

    /**
     * {@code 01 WS-THIS-PROGCOMMAREA}, {@code app/cbl/COACTUPC.cbl:652-849} - the
     * {@value CommArea#RECORD_LENGTH}-byte program work area holding the change-action state and the
     * before and after snapshots.
     *
     * <p>Never {@code null}: it is the program's own storage and always exists, so a request that omits
     * it gets {@link CommArea#initialised()}, which is the state
     * {@code INITIALIZE WS-THIS-PROGCOMMAREA} produces.
     */
    @Valid
    private final CommArea commArea;

    /**
     * {@code app/cpy/CVCRD01Y.cpy} - the card work area, carrying {@code CCARD-AID} (which key was
     * pressed), the next program, mapset and map, and the error and return messages.
     *
     * <p>{@link CardScreenState} is mutable, so it is copied on the way in and on the way out; this
     * type's immutability does not depend on a caller's restraint.
     */
    private final CardScreenState cardScreenState;

    /**
     * {@code app/cpy/COCOM01Y.cpy} - the {@value NavigationContext#COMMAREA_LENGTH}-byte
     * {@code CARDDEMO-COMMAREA}, including {@code CDEMO-PGM-CONTEXT} whose {@code 88}-levels distinguish
     * {@code ENTER} (0) from {@code REENTER} (1).
     *
     * <p><strong>{@code null} is meaningful here, and it is not an empty area.</strong>
     * {@code app/cbl/COACTUPC.cbl:880-882} tests
     * {@code IF EIBCALEN IS EQUAL TO 0 OR (CDEMO-FROM-PROGRAM = LIT-MENUPGM AND NOT CDEMO-PGM-REENTER)}
     * and answers it by {@code INITIALIZE}-ing both areas, then
     * {@code SET CDEMO-PGM-ENTER TO TRUE} and {@code SET ACUP-DETAILS-NOT-FETCHED TO TRUE}
     * ({@code :883-886}); the {@code ELSE} at {@code :888-892} instead moves both areas out of
     * {@code DFHCOMMAREA}. The first disjunct is the cold start - no communication area at all - and an
     * initialised {@link NavigationContext} cannot express it, because an initialised area reports
     * {@code EIBCALEN} as {@value NavigationContext#COMMAREA_LENGTH} and so takes the {@code ELSE}. The
     * second disjunct is a decision about a <em>present</em> area and belongs to
     * {@code AccountUpdateController}. This member therefore stays nullable and
     * {@link #hasNavigationContext()} is the discriminator.
     */
    private final NavigationContext navigationContext;

    /**
     * The {@code xxxL} / {@code xxxF} / {@code xxxA} metadata, one immutable entry per screen field.
     *
     * <p>An unmodifiable {@link EnumMap} of immutable records, kept off the wire by {@link JsonIgnore} on
     * {@link #metadata()} and {@link #metadata(ScreenField)}. The server "writes" it by asking for a new
     * request - {@link #withMetadata(ScreenField, FieldMetadata)}, {@link #withCursorOn(ScreenField)},
     * {@link #withAttribute(ScreenField, byte)} - which is how an immutable type carries storage a
     * program assigns into.
     */
    private final Map<ScreenField, FieldMetadata> metadata;

    // =================================================================================================
    // Construction. One canonical constructor, fed by the builder, plus three named starting points. All
    // of them work with no Spring context, no servlet container and no Jackson (practice B10).
    // =================================================================================================

    /**
     * The canonical constructor, and the only one: every instance is built from a {@link Builder}, so
     * there is exactly one place where a member can be assigned and exactly one place where a
     * {@code null} is normalised.
     *
     * <p>A {@code null} screen field becomes that field's declared width in spaces, because COBOL has no
     * {@code null} and an untransmitted field reads as {@code SPACES}. A {@code null} {@code commArea} or
     * {@code cardScreenState} becomes its own initial form, because both are the program's own storage
     * and always exist. A {@code null} {@code navigationContext} is stored <strong>verbatim</strong>: it
     * is the only carrier a caller can fail to pass, so its absence is the {@code EIBCALEN = 0} cold
     * start of {@code app/cbl/COACTUPC.cbl:880}, and completing it would erase that state.
     *
     * <p>Nothing is truncated. An over-wide value survives to be reported by {@link Size}, and the
     * {@code MOVE} that would shorten it happens only where it is asked for.
     *
     * @param builder the populated builder; never {@code null}
     */
    private AccountUpdateRequest(Builder builder) {
        this.trnname = orSpaces(builder.trnname, TRNNAME_LENGTH);
        this.title01 = orSpaces(builder.title01, TITLE01_LENGTH);
        this.curdate = orSpaces(builder.curdate, CURDATE_LENGTH);
        this.pgmname = orSpaces(builder.pgmname, PGMNAME_LENGTH);
        this.title02 = orSpaces(builder.title02, TITLE02_LENGTH);
        this.curtime = orSpaces(builder.curtime, CURTIME_LENGTH);
        this.acctsid = orSpaces(builder.acctsid, ACCTSID_LENGTH);
        this.acsttus = orSpaces(builder.acsttus, ACSTTUS_LENGTH);
        this.opnyear = orSpaces(builder.opnyear, OPNYEAR_LENGTH);
        this.opnmon = orSpaces(builder.opnmon, OPNMON_LENGTH);
        this.opnday = orSpaces(builder.opnday, OPNDAY_LENGTH);
        this.acrdlim = orSpaces(builder.acrdlim, ACRDLIM_LENGTH);
        this.expyear = orSpaces(builder.expyear, EXPYEAR_LENGTH);
        this.expmon = orSpaces(builder.expmon, EXPMON_LENGTH);
        this.expday = orSpaces(builder.expday, EXPDAY_LENGTH);
        this.acshlim = orSpaces(builder.acshlim, ACSHLIM_LENGTH);
        this.risyear = orSpaces(builder.risyear, RISYEAR_LENGTH);
        this.rismon = orSpaces(builder.rismon, RISMON_LENGTH);
        this.risday = orSpaces(builder.risday, RISDAY_LENGTH);
        this.acurbal = orSpaces(builder.acurbal, ACURBAL_LENGTH);
        this.acrcycr = orSpaces(builder.acrcycr, ACRCYCR_LENGTH);
        this.aaddgrp = orSpaces(builder.aaddgrp, AADDGRP_LENGTH);
        this.acrcydb = orSpaces(builder.acrcydb, ACRCYDB_LENGTH);
        this.acstnum = orSpaces(builder.acstnum, ACSTNUM_LENGTH);
        this.actssn1 = orSpaces(builder.actssn1, ACTSSN1_LENGTH);
        this.actssn2 = orSpaces(builder.actssn2, ACTSSN2_LENGTH);
        this.actssn3 = orSpaces(builder.actssn3, ACTSSN3_LENGTH);
        this.dobyear = orSpaces(builder.dobyear, DOBYEAR_LENGTH);
        this.dobmon = orSpaces(builder.dobmon, DOBMON_LENGTH);
        this.dobday = orSpaces(builder.dobday, DOBDAY_LENGTH);
        this.acstfco = orSpaces(builder.acstfco, ACSTFCO_LENGTH);
        this.acsfnam = orSpaces(builder.acsfnam, ACSFNAM_LENGTH);
        this.acsmnam = orSpaces(builder.acsmnam, ACSMNAM_LENGTH);
        this.acslnam = orSpaces(builder.acslnam, ACSLNAM_LENGTH);
        this.acsadl1 = orSpaces(builder.acsadl1, ACSADL1_LENGTH);
        this.acsstte = orSpaces(builder.acsstte, ACSSTTE_LENGTH);
        this.acsadl2 = orSpaces(builder.acsadl2, ACSADL2_LENGTH);
        this.acszipc = orSpaces(builder.acszipc, ACSZIPC_LENGTH);
        this.acscity = orSpaces(builder.acscity, ACSCITY_LENGTH);
        this.acsctry = orSpaces(builder.acsctry, ACSCTRY_LENGTH);
        this.acsph1a = orSpaces(builder.acsph1a, ACSPH1A_LENGTH);
        this.acsph1b = orSpaces(builder.acsph1b, ACSPH1B_LENGTH);
        this.acsph1c = orSpaces(builder.acsph1c, ACSPH1C_LENGTH);
        this.acsgovt = orSpaces(builder.acsgovt, ACSGOVT_LENGTH);
        this.acsph2a = orSpaces(builder.acsph2a, ACSPH2A_LENGTH);
        this.acsph2b = orSpaces(builder.acsph2b, ACSPH2B_LENGTH);
        this.acsph2c = orSpaces(builder.acsph2c, ACSPH2C_LENGTH);
        this.acseftc = orSpaces(builder.acseftc, ACSEFTC_LENGTH);
        this.acspflg = orSpaces(builder.acspflg, ACSPFLG_LENGTH);
        this.infomsg = orSpaces(builder.infomsg, INFOMSG_LENGTH);
        this.errmsg = orSpaces(builder.errmsg, ERRMSG_LENGTH);
        this.fkeys = orSpaces(builder.fkeys, FKEYS_LENGTH);
        this.fkey05 = orSpaces(builder.fkey05, FKEY05_LENGTH);
        this.fkey12 = orSpaces(builder.fkey12, FKEY12_LENGTH);
        this.commArea = builder.commArea == null ? CommArea.initialised() : builder.commArea;
        this.cardScreenState = builder.cardScreenState == null
                ? new CardScreenState()
                : new CardScreenState(builder.cardScreenState);
        this.navigationContext = builder.navigationContext;
        this.metadata = unmodifiableMetadata(builder.metadata);
    }

    /**
     * A builder in which every screen field is unset, the work area is initialised and the navigation
     * context is absent.
     *
     * @return a fresh builder, never {@code null}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * A request in the shape of a first entry to the screen: every field holds its declared width in
     * spaces, the work area is at {@link CommArea#initialised()} - so
     * {@link ChangeAction#isDetailsNotFetched()} is true, the state
     * {@code SET ACUP-DETAILS-NOT-FETCHED TO TRUE} at {@code app/cbl/COACTUPC.cbl:886} produces - the
     * card work area is initialised and there is no communication area.
     *
     * @return the initial request, never {@code null}
     */
    public static AccountUpdateRequest initial() {
        return builder().build();
    }

    /**
     * A first-entry request carrying only an account filter, which is the single value an operator has to
     * supply to reach any account.
     *
     * <p>{@code ACCTSID} is the field the insertion cursor starts on
     * ({@code app/bms/COACTUP.bms:84}, {@code ATTRB=(IC,UNPROT)}), and this factory additionally records
     * the cursor there, which is what {@code MOVE -1 TO ACCTSIDL OF CACTUPAI} does.
     *
     * @param acctsid the account filter, stored verbatim - {@code null} becomes
     *                {@value #ACCTSID_LENGTH} spaces, and {@code "*"} or spaces are legitimate values
     *                that {@code app/cbl/COACTUPC.cbl:1051-1052} tests for by name
     * @return the request, never {@code null}
     */
    public static AccountUpdateRequest withAccountFilter(String acctsid) {
        return builder().acctsid(acctsid).build().withCursorOn(ScreenField.ACCTSID);
    }

    /**
     * A builder pre-loaded with everything this request holds, so a caller can derive a modified copy
     * without restating the other 53 fields.
     *
     * @return a builder equal to this request, never {@code null}
     */
    public Builder toBuilder() {
        Builder builder = new Builder();
        for (ScreenField field : ScreenField.values()) {
            builder.value(field, value(field));
            builder.metadata(field, metadata.get(field));
        }
        builder.commArea = commArea;
        builder.cardScreenState = cardScreenState;
        builder.navigationContext = navigationContext;
        return builder;
    }

    /**
     * A string of {@code length} spaces - the value of an untransmitted {@code PIC X} field, and the fill
     * of every field in {@link #initial()}.
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
     * A string of {@code length} {@code LOW-VALUES} characters, the figurative constant
     * {@code app/cbl/COACTUPC.cbl:1053} moves into {@code ACUP-NEW-ACCT-ID-X} when the operator clears
     * or wildcards the account filter.
     *
     * <p>Distinct from {@link #spaces(int)} at the byte level, and both are kept reachable because a
     * field-by-field diff tells them apart: {@code ACUP-DETAILS-NOT-FETCHED} is true for either, but the
     * stored bytes are not the same.
     *
     * @param length how many characters; never negative
     * @return exactly {@code length} {@code U+0000} characters
     * @throws IllegalArgumentException if {@code length} is negative
     */
    public static String lowValues(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("A field cannot be " + length + " characters wide, so "
                    + "there is no such thing as " + length + " LOW-VALUES characters");
        }
        return String.valueOf(LOW_VALUE).repeat(length);
    }

    /**
     * Substitutes a field's declared width in spaces for a {@code null}, and otherwise returns the value
     * untouched - no padding of a short value and no truncation of a long one, because both of those are
     * {@code MOVE} semantics and belong where a {@code MOVE} is asked for.
     *
     * @param value the value, possibly {@code null}
     * @param width the field's declared width
     * @return the value, or {@code width} spaces
     */
    private static String orSpaces(String value, int width) {
        return value == null ? spaces(width) : value;
    }

    /**
     * Completes a metadata map and makes it unmodifiable, so every field has an entry and no caller can
     * add, remove or replace one.
     *
     * <p>{@code source} is never {@code null}: the only caller is this class's own constructor, and it
     * passes {@link Builder#metadata}, which is a {@code final} field initialised at its declaration. No
     * guard is written for a state that cannot arise - an unreachable check would read as though it could,
     * and would leave a branch no test can ever drive.
     *
     * @param source the entries a builder collected, possibly incomplete but never {@code null}
     * @return an unmodifiable {@link EnumMap} with an entry for all {@value #FIELD_COUNT} fields
     */
    private static Map<ScreenField, FieldMetadata> unmodifiableMetadata(
            Map<ScreenField, FieldMetadata> source) {
        Map<ScreenField, FieldMetadata> complete = new EnumMap<>(ScreenField.class);
        for (ScreenField field : ScreenField.values()) {
            FieldMetadata supplied = source.get(field);
            complete.put(field, supplied == null ? FieldMetadata.unset() : supplied);
        }
        return Collections.unmodifiableMap(complete);
    }

    // =================================================================================================
    // The 54 accessors, in declaration order. Each returns exactly what was stored - untrimmed, because a
    // PIC X field's trailing spaces are part of its value and the parity differ compares them.
    // =================================================================================================

    /** @return {@code TRNNAMEI PIC X(4)}, as stored */
    public String getTrnname() {
        return trnname;
    }

    /** @return {@code TITLE01I PIC X(40)}, as stored */
    public String getTitle01() {
        return title01;
    }

    /** @return {@code CURDATEI PIC X(8)}, as stored */
    public String getCurdate() {
        return curdate;
    }

    /** @return {@code PGMNAMEI PIC X(8)}, as stored */
    public String getPgmname() {
        return pgmname;
    }

    /** @return {@code TITLE02I PIC X(40)}, as stored */
    public String getTitle02() {
        return title02;
    }

    /** @return {@code CURTIMEI PIC X(8)}, as stored */
    public String getCurtime() {
        return curtime;
    }

    /**
     * @return {@code ACCTSIDI PIC X(11)}, as stored - which may legitimately be {@code "*"}, spaces or
     *         {@code LOW-VALUES}, all three of which {@code app/cbl/COACTUPC.cbl:1051-1053} handles by
     *         name
     */
    public String getAcctsid() {
        return acctsid;
    }

    /** @return {@code ACSTTUSI PIC X(1)}, as stored */
    public String getAcsttus() {
        return acsttus;
    }

    /** @return {@code OPNYEARI PIC X(4)}, as stored - one third of the open date, never recomposed here */
    public String getOpnyear() {
        return opnyear;
    }

    /** @return {@code OPNMONI PIC X(2)}, as stored */
    public String getOpnmon() {
        return opnmon;
    }

    /** @return {@code OPNDAYI PIC X(2)}, as stored */
    public String getOpnday() {
        return opnday;
    }

    /** @return {@code ACRDLIMI PIC X(15)}, as stored - characters, never parsed here */
    public String getAcrdlim() {
        return acrdlim;
    }

    /** @return {@code EXPYEARI PIC X(4)}, as stored */
    public String getExpyear() {
        return expyear;
    }

    /** @return {@code EXPMONI PIC X(2)}, as stored */
    public String getExpmon() {
        return expmon;
    }

    /** @return {@code EXPDAYI PIC X(2)}, as stored */
    public String getExpday() {
        return expday;
    }

    /** @return {@code ACSHLIMI PIC X(15)}, as stored */
    public String getAcshlim() {
        return acshlim;
    }

    /** @return {@code RISYEARI PIC X(4)}, as stored */
    public String getRisyear() {
        return risyear;
    }

    /** @return {@code RISMONI PIC X(2)}, as stored */
    public String getRismon() {
        return rismon;
    }

    /** @return {@code RISDAYI PIC X(2)}, as stored */
    public String getRisday() {
        return risday;
    }

    /** @return {@code ACURBALI PIC X(15)}, as stored */
    public String getAcurbal() {
        return acurbal;
    }

    /** @return {@code ACRCYCRI PIC X(15)}, as stored */
    public String getAcrcycr() {
        return acrcycr;
    }

    /** @return {@code AADDGRPI PIC X(10)}, as stored */
    public String getAaddgrp() {
        return aaddgrp;
    }

    /** @return {@code ACRCYDBI PIC X(15)}, as stored */
    public String getAcrcydb() {
        return acrcydb;
    }

    /** @return {@code ACSTNUMI PIC X(9)}, as stored */
    public String getAcstnum() {
        return acstnum;
    }

    /** @return {@code ACTSSN1I PIC X(3)}, as stored - which may be the {@code '999'} placeholder mask */
    public String getActssn1() {
        return actssn1;
    }

    /** @return {@code ACTSSN2I PIC X(2)}, as stored */
    public String getActssn2() {
        return actssn2;
    }

    /** @return {@code ACTSSN3I PIC X(4)}, as stored */
    public String getActssn3() {
        return actssn3;
    }

    /** @return {@code DOBYEARI PIC X(4)}, as stored */
    public String getDobyear() {
        return dobyear;
    }

    /** @return {@code DOBMONI PIC X(2)}, as stored */
    public String getDobmon() {
        return dobmon;
    }

    /** @return {@code DOBDAYI PIC X(2)}, as stored */
    public String getDobday() {
        return dobday;
    }

    /** @return {@code ACSTFCOI PIC X(3)}, as stored */
    public String getAcstfco() {
        return acstfco;
    }

    /** @return {@code ACSFNAMI PIC X(25)}, as stored */
    public String getAcsfnam() {
        return acsfnam;
    }

    /** @return {@code ACSMNAMI PIC X(25)}, as stored */
    public String getAcsmnam() {
        return acsmnam;
    }

    /** @return {@code ACSLNAMI PIC X(25)}, as stored */
    public String getAcslnam() {
        return acslnam;
    }

    /** @return {@code ACSADL1I PIC X(50)}, as stored */
    public String getAcsadl1() {
        return acsadl1;
    }

    /** @return {@code ACSSTTEI PIC X(2)}, as stored */
    public String getAcsstte() {
        return acsstte;
    }

    /** @return {@code ACSADL2I PIC X(50)}, as stored */
    public String getAcsadl2() {
        return acsadl2;
    }

    /** @return {@code ACSZIPCI PIC X(5)}, as stored */
    public String getAcszipc() {
        return acszipc;
    }

    /** @return {@code ACSCITYI PIC X(50)}, as stored */
    public String getAcscity() {
        return acscity;
    }

    /** @return {@code ACSCTRYI PIC X(3)}, as stored */
    public String getAcsctry() {
        return acsctry;
    }

    /** @return {@code ACSPH1AI PIC X(3)}, as stored */
    public String getAcsph1a() {
        return acsph1a;
    }

    /** @return {@code ACSPH1BI PIC X(3)}, as stored */
    public String getAcsph1b() {
        return acsph1b;
    }

    /** @return {@code ACSPH1CI PIC X(4)}, as stored */
    public String getAcsph1c() {
        return acsph1c;
    }

    /** @return {@code ACSGOVTI PIC X(20)}, as stored - in the clear, exactly as the 3270 shows it */
    public String getAcsgovt() {
        return acsgovt;
    }

    /** @return {@code ACSPH2AI PIC X(3)}, as stored */
    public String getAcsph2a() {
        return acsph2a;
    }

    /** @return {@code ACSPH2BI PIC X(3)}, as stored */
    public String getAcsph2b() {
        return acsph2b;
    }

    /** @return {@code ACSPH2CI PIC X(4)}, as stored */
    public String getAcsph2c() {
        return acsph2c;
    }

    /** @return {@code ACSEFTCI PIC X(10)}, as stored */
    public String getAcseftc() {
        return acseftc;
    }

    /** @return {@code ACSPFLGI PIC X(1)}, as stored */
    public String getAcspflg() {
        return acspflg;
    }

    /** @return {@code INFOMSGI PIC X(45)}, as stored */
    public String getInfomsg() {
        return infomsg;
    }

    /** @return {@code ERRMSGI PIC X(78)}, as stored */
    public String getErrmsg() {
        return errmsg;
    }

    /** @return {@code FKEYSI PIC X(21)}, as stored */
    public String getFkeys() {
        return fkeys;
    }

    /** @return {@code FKEY05I PIC X(7)}, as stored */
    public String getFkey05() {
        return fkey05;
    }

    /** @return {@code FKEY12I PIC X(10)}, as stored */
    public String getFkey12() {
        return fkey12;
    }

    // =================================================================================================
    // Field access by enumeration. This is what the group-image codec, a parity differ and a highlight
    // routine use, so that addressing a field is checked by the compiler instead of by a spelling.
    // =================================================================================================

    /**
     * The value of one field, as stored.
     *
     * @param field which field
     * @return the field's value, untrimmed and never {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public String value(ScreenField field) {
        Objects.requireNonNull(field, "A ScreenField is required to read a field's value");
        return switch (field) {
            case TRNNAME -> trnname;
            case TITLE01 -> title01;
            case CURDATE -> curdate;
            case PGMNAME -> pgmname;
            case TITLE02 -> title02;
            case CURTIME -> curtime;
            case ACCTSID -> acctsid;
            case ACSTTUS -> acsttus;
            case OPNYEAR -> opnyear;
            case OPNMON -> opnmon;
            case OPNDAY -> opnday;
            case ACRDLIM -> acrdlim;
            case EXPYEAR -> expyear;
            case EXPMON -> expmon;
            case EXPDAY -> expday;
            case ACSHLIM -> acshlim;
            case RISYEAR -> risyear;
            case RISMON -> rismon;
            case RISDAY -> risday;
            case ACURBAL -> acurbal;
            case ACRCYCR -> acrcycr;
            case AADDGRP -> aaddgrp;
            case ACRCYDB -> acrcydb;
            case ACSTNUM -> acstnum;
            case ACTSSN1 -> actssn1;
            case ACTSSN2 -> actssn2;
            case ACTSSN3 -> actssn3;
            case DOBYEAR -> dobyear;
            case DOBMON -> dobmon;
            case DOBDAY -> dobday;
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
            case ACSPH1A -> acsph1a;
            case ACSPH1B -> acsph1b;
            case ACSPH1C -> acsph1c;
            case ACSGOVT -> acsgovt;
            case ACSPH2A -> acsph2a;
            case ACSPH2B -> acsph2b;
            case ACSPH2C -> acsph2c;
            case ACSEFTC -> acseftc;
            case ACSPFLG -> acspflg;
            case INFOMSG -> infomsg;
            case ERRMSG -> errmsg;
            case FKEYS -> fkeys;
            case FKEY05 -> fkey05;
            case FKEY12 -> fkey12;
        };
    }

    /**
     * A copy of this request with one field replaced and everything else unchanged.
     *
     * @param field which field
     * @param value the new value, stored verbatim; {@code null} becomes the field's declared width in
     *              spaces
     * @return a new request, never {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public AccountUpdateRequest withValue(ScreenField field, String value) {
        Objects.requireNonNull(field, "A ScreenField is required to replace a field's value");
        return toBuilder().value(field, value).build();
    }

    /**
     * Every field's value, keyed by {@code DFHMDF} label in declaration order.
     *
     * <p>Keyed by label rather than by {@link ScreenField} because this is the shape a field-by-field
     * differ compares and a failure message prints: the key is the name the copybook and the mapset both
     * use.
     *
     * @return an unmodifiable, insertion-ordered map of all {@value #FIELD_COUNT} fields
     */
    public Map<String, String> fieldValues() {
        Map<ScreenField, String> byField = new EnumMap<>(ScreenField.class);
        for (ScreenField field : ScreenField.values()) {
            byField.put(field, value(field));
        }
        Map<String, String> byLabel = new LinkedHashMap<>(byField.size());
        byField.forEach((field, value) -> byLabel.put(field.label(), value));
        return Collections.unmodifiableMap(byLabel);
    }

    /**
     * The declared width of one field.
     *
     * @param field which field
     * @return the {@code xxxI} {@code PICTURE} width, which is also the {@code DFHMDF LENGTH=} operand
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public static int declaredLength(ScreenField field) {
        Objects.requireNonNull(field, "A ScreenField is required to report a declared length");
        return field.length();
    }

    // =================================================================================================
    // The xxxL / xxxF / xxxA metadata. Present because COACTUPC writes it into the input group, and off
    // the wire because it traces to no DFHMDF data item (AAP 0.6.3, gate G9).
    // =================================================================================================

    /**
     * One field's metadata pair.
     *
     * @param field which field
     * @return its immutable {@code xxxL} / {@code xxxF} / {@code xxxA} carrier, never {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public FieldMetadata metadata(ScreenField field) {
        Objects.requireNonNull(field, "A ScreenField is required to read a field's metadata");
        return metadata.get(field);
    }

    /**
     * Every field's metadata, keyed by {@link ScreenField} in declaration order.
     *
     * @return an unmodifiable map with an entry for all {@value #FIELD_COUNT} fields
     */
    @JsonIgnore
    public Map<ScreenField, FieldMetadata> metadata() {
        return metadata;
    }

    /**
     * A copy of this request with one field's metadata replaced.
     *
     * <p>This is the immutable equivalent of {@code MOVE DFHBMPRF TO ACCTSIDA OF CACTUPAI}: the write
     * lands on the <em>input</em> group, which is why the carrier exists at all, and returning a new
     * request rather than mutating one is what keeps this type shareable.
     *
     * @param field    which field
     * @param replacement the new carrier
     * @return a new request, never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public AccountUpdateRequest withMetadata(ScreenField field, FieldMetadata replacement) {
        Objects.requireNonNull(field, "A ScreenField is required to replace a field's metadata");
        Objects.requireNonNull(replacement, "A FieldMetadata is required; every field has one, and its "
                + "unset state is FieldMetadata.unset() rather than a Java null");
        return toBuilder().metadata(field, replacement).build();
    }

    /**
     * A copy of this request with the cursor asked onto one field - {@code MOVE -1 TO xxxL OF CACTUPAI},
     * which {@code COACTUPC} performs at 41 sites including {@code :3015} and {@code :3166}.
     *
     * @param field which field the cursor should land on
     * @return a new request whose {@code field} reports {@link FieldMetadata#isCursorHere()}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public AccountUpdateRequest withCursorOn(ScreenField field) {
        Objects.requireNonNull(field, "A ScreenField is required to position the cursor");
        return withMetadata(field, metadata(field).withCursorHere());
    }

    /**
     * A copy of this request with one field's attribute byte replaced - the {@code xxxA} view of
     * {@code xxxF}, written by {@code 3310-PROTECT-ALL-ATTRS} and {@code 3320-UNPROTECT-FEW-ATTRS}.
     *
     * @param field     which field
     * @param attribute the attribute byte, which must come from {@code common/BmsAttributes} rather than
     *                  being written as a literal
     * @return a new request, never {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public AccountUpdateRequest withAttribute(ScreenField field, byte attribute) {
        Objects.requireNonNull(field, "A ScreenField is required to set a field's attribute byte");
        return withMetadata(field, metadata(field).withAttribute(attribute));
    }

    /**
     * Whether {@code FKEY05} is revealed - that is, whether its attribute byte is
     * {@link BmsAttributes#DFHBMASB}, which is what {@code app/cbl/COACTUPC.cbl:3579-3580} moves into
     * {@code FKEY05A OF CACTUPAI} to make "{@code F5=Save}" visible.
     *
     * <p>The mapset declares the field {@code ATTRB=(ASKIP,DRK)} - hidden - so this is genuinely a
     * behavioural signal and not presentation trivia: it is how a stateless client learns whether saving
     * is currently offered.
     *
     * @return {@code true} when the save legend has been revealed
     */
    @JsonIgnore
    public boolean isSaveLegendRevealed() {
        return metadata(ScreenField.FKEY05).isBright();
    }

    /**
     * Whether {@code FKEY12} is revealed - {@code app/cbl/COACTUPC.cbl:3575} moves
     * {@link BmsAttributes#DFHBMASB} into {@code FKEY12A OF CACTUPAI} to make "{@code F12=Cancel}"
     * visible.
     *
     * @return {@code true} when the cancel legend has been revealed
     */
    @JsonIgnore
    public boolean isCancelLegendRevealed() {
        return metadata(ScreenField.FKEY12).isBright();
    }

    // =================================================================================================
    // Conversation state accessors. Nothing here reads or writes a session, a cache or a static field.
    // =================================================================================================

    /**
     * The {@value CommArea#RECORD_LENGTH}-byte program work area.
     *
     * @return the work area, never {@code null}
     */
    public CommArea getCommArea() {
        return commArea;
    }

    /**
     * A copy of this request carrying a different work area.
     *
     * @param replacement the new work area; {@code null} becomes {@link CommArea#initialised()}
     * @return a new request, never {@code null}
     */
    public AccountUpdateRequest withCommArea(CommArea replacement) {
        Builder builder = toBuilder();
        builder.commArea = replacement;
        return builder.build();
    }

    /**
     * The {@code CVCRD01Y} work area.
     *
     * @return a copy, so that mutating the returned object cannot alter this request
     */
    public CardScreenState getCardScreenState() {
        return new CardScreenState(cardScreenState);
    }

    /**
     * A copy of this request carrying a different card work area.
     *
     * @param replacement the new work area; {@code null} becomes a freshly constructed one, and a
     *                    non-{@code null} one is copied
     * @return a new request, never {@code null}
     */
    public AccountUpdateRequest withCardScreenState(CardScreenState replacement) {
        Builder builder = toBuilder();
        builder.cardScreenState = replacement;
        return builder.build();
    }

    /**
     * The {@value NavigationContext#COMMAREA_LENGTH}-byte {@code CARDDEMO-COMMAREA}.
     *
     * @return the communication area, or {@code null} for the {@code EIBCALEN = 0} cold start of
     *         {@code app/cbl/COACTUPC.cbl:880}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    /**
     * A copy of this request carrying a different communication area.
     *
     * @param replacement the new communication area, or {@code null} to express the cold start
     * @return a new request, never {@code null}
     */
    public AccountUpdateRequest withNavigationContext(NavigationContext replacement) {
        Builder builder = toBuilder();
        builder.navigationContext = replacement;
        return builder.build();
    }

    /**
     * Whether a communication area was passed at all - the discriminator for the first disjunct of
     * {@code IF EIBCALEN IS EQUAL TO 0 OR ...} at {@code app/cbl/COACTUPC.cbl:880}.
     *
     * @return {@code true} when {@link #getNavigationContext()} is present
     */
    @JsonIgnore
    public boolean hasNavigationContext() {
        return navigationContext != null;
    }

    /**
     * The number of bytes {@code EIBCALEN} would report for this request.
     *
     * @return {@code 0} for the cold start, otherwise {@value #TOTAL_COMMAREA_LENGTH} - the
     *         {@value NavigationContext#COMMAREA_LENGTH}-byte communication area followed by the
     *         {@value CommArea#RECORD_LENGTH}-byte work area
     */
    @JsonIgnore
    public int commareaLength() {
        return hasNavigationContext() ? TOTAL_COMMAREA_LENGTH : 0;
    }

    /**
     * {@code CDEMO-PGM-CONTEXT}, the {@code ENTER} / {@code REENTER} flag.
     *
     * @return the raw context value, or {@code 0} - the {@code ENTER} state - when no communication area
     *         was passed, which is exactly the state {@code SET CDEMO-PGM-ENTER TO TRUE} at
     *         {@code app/cbl/COACTUPC.cbl:885} establishes for the cold start
     */
    @JsonIgnore
    public int getPgmContext() {
        return hasNavigationContext() ? navigationContext.pgmContext() : 0;
    }

    /**
     * {@code 88 CDEMO-PGM-ENTER VALUE 0} - first entry, so the program paints the screen and returns.
     *
     * <p>True for the cold start too, because {@code app/cbl/COACTUPC.cbl:883-886} initialises the area
     * and sets this state in the same breath.
     *
     * @return {@code true} when this turn is a first entry
     */
    @JsonIgnore
    public boolean isEnter() {
        return !hasNavigationContext() || navigationContext.isEnter();
    }

    /**
     * {@code 88 CDEMO-PGM-REENTER VALUE 1} - the operator has been shown the screen and typed into it, so
     * the program validates the input.
     *
     * <p>This is also the conjunct {@code app/cpy/CSSETATY.cpy} gates every one of {@code COACTUPC}'s 39
     * highlight sites on, so making it reachable from an inbound request is what allows the error
     * highlight to be exercised at all (gate <strong>G38</strong>).
     *
     * @return {@code true} when this turn is a re-entry
     */
    @JsonIgnore
    public boolean isReenter() {
        return hasNavigationContext() && navigationContext.isReenter();
    }

    // =================================================================================================
    // Fixed-width rendering. This is where a MOVE finally happens, and it happens through
    // FixedWidthCodec so that one implementation owns the PIC X pad-and-truncate rule (practice B11).
    // =================================================================================================

    /**
     * One field's value at exactly its declared width, through the {@code PIC X} move rule: padded on the
     * right with spaces when short, truncated on the right when long.
     *
     * @param field which field
     * @param codec the codec supplying the move rule
     * @return exactly {@link ScreenField#length()} characters
     * @throws NullPointerException if either argument is {@code null}
     */
    public String image(ScreenField field, FixedWidthCodec codec) {
        Objects.requireNonNull(field, "A ScreenField is required to render a field image");
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to render a field image: it owns "
                + "the PIC X move rule, which pads on the right and truncates on the right");
        return codec.movePicX(value(field), field.length());
    }

    /**
     * A copy of this request in which every field holds exactly its declared width, as though each had
     * been {@code MOVE}d into its {@code CACTUPAI} item.
     *
     * <p>The metadata and all three carriers are preserved unchanged; only the 54 values are normalised.
     * This is the operation that makes {@link #fromGroupImage(byte[], FixedWidthCodec)} the exact inverse
     * of {@link #toGroupImage(FixedWidthCodec)}, because an image can only hold declared widths.
     *
     * @param codec the codec supplying the move rule
     * @return a new request whose 54 values are all at their declared widths
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public AccountUpdateRequest normalize(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to normalise a request");
        Builder builder = toBuilder();
        for (ScreenField field : ScreenField.values()) {
            builder.value(field, image(field, codec));
        }
        return builder.build();
    }

    /**
     * Renders the whole {@value #GROUP_LENGTH}-byte {@code CACTUPAI} group - the storage a
     * {@code RECEIVE MAP INTO(CACTUPAI)} ({@code app/cbl/COACTUPC.cbl:1040-1045}) would have filled.
     *
     * <p>Laid out exactly as {@code app/cpy-bms/COACTUP.CPY} declares it:
     *
     * <ul>
     *   <li>{@value #TIOAPFX_LENGTH} bytes of {@code TIOAPFX} prefix, written as spaces;</li>
     *   <li>for each of the {@value #FIELD_COUNT} fields, its {@code xxxL} halfword big-endian, its
     *       {@code xxxF} / {@code xxxA} byte, {@value #EXTENDED_ATTRIBUTE_ITEM_LENGTH} bytes of
     *       {@code LOW-VALUES} for the unnamed extended-attribute {@code FILLER}, then the data at its
     *       declared width.</li>
     * </ul>
     *
     * <p>The {@code FILLER} spans are written rather than left to Java's zero-initialisation, so that
     * "these bytes are {@code LOW-VALUES} on input" is a statement the code makes instead of an accident
     * of the language (gate <strong>G21</strong>). The attribute byte is written raw and no charset is
     * consulted for it: a 3270 attribute is a bit pattern, not a character in any code page's printable
     * range, and it means the same thing whether the surrounding data is EBCDIC or ASCII.
     *
     * @param codec the codec supplying both the move rule and the charset
     * @return a new array of exactly {@value #GROUP_LENGTH} bytes, never {@code null}
     * @throws NullPointerException     if {@code codec} is {@code null}
     * @throws IllegalArgumentException if the codec's charset does not encode this data one byte per
     *                                  character
     */
    public byte[] toGroupImage(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to render the CACTUPAI group "
                + "image: it supplies both the PIC X move rule and the charset");
        byte[] group = new byte[GROUP_LENGTH];
        writeCharacters(group, 0, spaces(TIOAPFX_LENGTH), TIOAPFX_LENGTH, codec,
                "the " + TIOAPFX_LENGTH + "-byte TIOAPFX prefix");
        for (ScreenField field : ScreenField.values()) {
            FieldMetadata holder = metadata.get(field);
            int lengthItem = holder.lengthItem();
            group[field.lengthItemOffset()] = (byte) ((lengthItem >> 8) & 0xFF);
            group[field.lengthItemOffset() + 1] = (byte) (lengthItem & 0xFF);
            group[field.flagItemOffset()] = holder.attribute();
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
     * Reads a {@value #GROUP_LENGTH}-byte {@code CACTUPAI} image back into a request.
     *
     * <p>Recovers the {@value #FIELD_COUNT} {@code xxxI} values at their full declared widths -
     * untrimmed, because a {@code PIC X} field's trailing spaces are part of its value - together with
     * each field's {@code xxxL} halfword and {@code xxxA} attribute byte.
     *
     * <p>Three parts of the image are deliberately <strong>not</strong> recovered, because none of them is
     * data:
     *
     * <ul>
     *   <li>the {@value #TIOAPFX_LENGTH}-byte {@code TIOAPFX} prefix, which belongs to the terminal
     *       input/output area and not to the application;</li>
     *   <li>the {@value #EXTENDED_ATTRIBUTE_ITEM_LENGTH} extended-attribute {@code FILLER} bytes per
     *       field, unnamed and unused on input - the output group names them {@code xxxC}, {@code xxxP},
     *       {@code xxxH} and {@code xxxV}, and they are {@code AccountUpdateResponse}'s concern;</li>
     *   <li>the three conversation-state carriers, which are separate storage entirely and travel on
     *       their own. The returned request carries an initialised work area, an initialised card work
     *       area and no communication area.</li>
     * </ul>
     *
     * <p>So the round trip is exact where it can be: for a request whose carriers are at their initialised
     * state, {@code fromGroupImage(x.toGroupImage(codec), codec)} equals
     * {@code x.normalize(codec)}.
     *
     * @param groupImage the {@value #GROUP_LENGTH}-byte input group; read, never retained
     * @param codec      the codec supplying the charset
     * @return a request carrying the image's {@value #FIELD_COUNT} fields and their metadata
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code groupImage} is not exactly {@value #GROUP_LENGTH} bytes,
     *                                  or holds a length item outside the range {@code COMP PIC S9(4)}
     *                                  can represent
     */
    public static AccountUpdateRequest fromGroupImage(byte[] groupImage, FixedWidthCodec codec) {
        Objects.requireNonNull(groupImage, "A group image is required to read a CACTUPAI area");
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to read a CACTUPAI area: it "
                + "supplies the charset the field data is encoded in");
        if (groupImage.length != GROUP_LENGTH) {
            throw new IllegalArgumentException("The CACTUPAI group of app/cpy-bms/COACTUP.CPY is "
                    + GROUP_LENGTH + " bytes - " + TIOAPFX_LENGTH + " of TIOAPFX prefix, plus "
                    + FIELD_COUNT + " fields at " + FIELD_OVERHEAD + " bytes of overhead each, plus "
                    + PAYLOAD_LENGTH + " bytes of data - but this image is " + groupImage.length
                    + " byte(s)");
        }
        Builder builder = new Builder();
        for (ScreenField field : ScreenField.values()) {
            builder.metadata(field, new FieldMetadata(decodeHalfword(groupImage, field),
                    groupImage[field.flagItemOffset()]));
            builder.value(field, readCharacters(groupImage, field, codec));
        }
        return builder.build();
    }

    /**
     * Reads one field's {@code xxxL COMP PIC S9(4)} item from a group image as a big-endian, signed
     * halfword, and rejects a value the {@code PICTURE} cannot represent.
     *
     * <p>The cast to {@code short} is what makes the sign work: the two bytes are combined as an
     * {@code int} and then narrowed, so {@code 0xFFFF} reads back as {@value FieldMetadata#CURSOR_HERE}
     * rather than as 65535.
     *
     * <p>Storage capacity and declared capacity differ here, and the declaration wins. A halfword holds
     * &plusmn;32767 but {@code S9(4)} holds only {@value FieldMetadata#LENGTH_ITEM_MIN} to
     * {@value FieldMetadata#LENGTH_ITEM_MAX}, so a byte pair outside that range means the image and the
     * copybook disagree - and accepting it quietly is exactly how a plausible-looking parity defect gets
     * in.
     *
     * @param groupImage the group image to read from
     * @param field      which field's length item to read
     * @return the halfword value
     * @throws IllegalArgumentException if the value is outside the {@code PIC S9(4)} range
     */
    private static int decodeHalfword(byte[] groupImage, ScreenField field) {
        int offset = field.lengthItemOffset();
        int halfword = (short) ((groupImage[offset] << 8) | (groupImage[offset + 1] & 0xFF));
        if (halfword < FieldMetadata.LENGTH_ITEM_MIN || halfword > FieldMetadata.LENGTH_ITEM_MAX) {
            throw new IllegalArgumentException("The xxxL item of " + field.describe() + " reads "
                    + halfword + " at offset " + offset + ", which COMP PIC S9(4) cannot represent: it "
                    + "holds " + FieldMetadata.LENGTH_ITEM_MIN + " to " + FieldMetadata.LENGTH_ITEM_MAX);
        }
        return halfword;
    }

    /**
     * Decodes one field's data span from a group image under the codec's code page.
     *
     * @param groupImage the group image to read from
     * @param field      which field's data to read
     * @param codec      the codec supplying the charset
     * @return the field's value at its full declared width, untrimmed
     */
    private static String readCharacters(byte[] groupImage, ScreenField field, FixedWidthCodec codec) {
        byte[] span = Arrays.copyOfRange(groupImage, field.dataOffset(), field.endOffsetExclusive());
        return codec.decodeImage(span, "the CACTUPAI item " + field.symbolicItemName());
    }

    /**
     * Encodes an image into a group image at a given offset, insisting that it occupy exactly the declared
     * number of bytes.
     *
     * <p>The insistence is the point. A {@code PIC X(n)} item is n <em>bytes</em>, and every code page
     * this system reads - IBM037 for the EBCDIC datasets, US-ASCII for the ASCII fixtures - is
     * single-byte, so n characters must encode to n bytes. Were a multi-byte charset ever configured, the
     * group would overflow its declared width and every offset after it would shift.
     *
     * <p>This is <strong>defence in depth</strong>, and deliberately so: today
     * {@link FixedWidthCodec#encodeImage(String, String)} refuses a non-single-byte encoding first, and
     * refuses it with a better message, so this check is not expected to fire. It stays because the
     * consequence of it being absent - a silently short encoding shifting every later offset in a
     * 1095-byte group - is a parity defect that no field-level assertion would localise, and the cost of
     * keeping it is one comparison per field.
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
                    + "; a PIC X(n) item is n bytes, so the CACTUPAI group can only be rendered from an "
                    + "image already at its declared width under a single-byte code page such as IBM037 "
                    + "or US-ASCII");
        }
        System.arraycopy(encoded, 0, group, offset, declaredWidth);
    }

    // =================================================================================================
    // Value semantics. Everything the instance holds participates: the 54 fields, all three carriers and
    // all 54 metadata pairs. The metadata is included because it is behaviour - a request with the cursor
    // on ACCTSID is not the same request as one without it - even though it never reaches the wire.
    // =================================================================================================

    /**
     * Value equality over the {@value #FIELD_COUNT} fields, all three carriers and all
     * {@value #FIELD_COUNT} metadata pairs.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a request holding the same values throughout
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AccountUpdateRequest that)) {
            return false;
        }
        return sameFieldValues(that)
                && metadata.equals(that.metadata)
                && commArea.equals(that.commArea)
                && cardScreenState.equals(that.cardScreenState)
                && Objects.equals(navigationContext, that.navigationContext);
    }

    /**
     * Whether all {@value #FIELD_COUNT} field values agree, compared through {@link ScreenField} so that
     * adding a field cannot leave it out of the comparison.
     *
     * @param that the request to compare with
     * @return {@code true} when every field matches
     */
    private boolean sameFieldValues(AccountUpdateRequest that) {
        for (ScreenField field : ScreenField.values()) {
            if (!value(field).equals(that.value(field))) {
                return false;
            }
        }
        return true;
    }

    /**
     * A hash consistent with {@link #equals(Object)}.
     *
     * @return the hash of every field, every metadata pair and all three carriers
     */
    @Override
    public int hashCode() {
        int result = 1;
        for (ScreenField field : ScreenField.values()) {
            result = 31 * result + value(field).hashCode();
        }
        result = 31 * result + metadata.hashCode();
        result = 31 * result + commArea.hashCode();
        result = 31 * result + cardScreenState.hashCode();
        return 31 * result + Objects.hashCode(navigationContext);
    }

    /**
     * A single-line rendering of every field, every metadata pair and all three carriers.
     *
     * <p>Values are rendered <strong>as stored</strong> and quoted so that the trailing spaces of a
     * fixed-width field, which are part of its value, stay visible in a failure message. Nothing is
     * masked, redacted or omitted - not the SSN parts, not the date of birth and not the
     * government-issued identifier - because the COBOL displays all of them on a 3270 in the clear and
     * hiding them here would be an unrequested behaviour change (practice <strong>B6</strong>). That
     * decision is recorded in this type's own documentation rather than left implicit.
     *
     * @return a diagnostic rendering of the whole request
     */
    @Override
    public String toString() {
        StringBuilder rendered = new StringBuilder("AccountUpdateRequest[");
        for (ScreenField field : ScreenField.values()) {
            rendered.append(field.label())
                    .append("='")
                    .append(value(field))
                    .append("' ")
                    .append(metadata.get(field))
                    .append(", ");
        }
        return rendered.append("commArea=")
                .append(commArea)
                .append(", cardScreenState=")
                .append(cardScreenState)
                .append(", navigationContext=")
                .append(navigationContext)
                .append(']')
                .toString();
    }

    // =================================================================================================
    // The 54 fields, as an enumeration. This exists so that every place needing to address a field - the
    // group-image codec, the metadata carrier, a parity differ, a highlight routine - names it in a way
    // the compiler checks, instead of passing a string that can be misspelled. Each constant carries the
    // whole of its provenance: the DFHMDF label, the symbolic-map item name, the PICTURE as written, the
    // width, both source line numbers, the screen position and the data offset, so the gate G9 trace is
    // readable from one place and verifiable against the copybook without arithmetic.
    // =================================================================================================

    /**
     * One of the {@value AccountUpdateRequest#FIELD_COUNT} name-labelled {@code DFHMDF} fields of
     * {@code app/bms/COACTUP.bms}, in the order the mapset declares them - which is also the order
     * {@code app/cpy-bms/COACTUP.CPY} lays their storage down.
     *
     * <p>That order interleaves the screen's two columns: {@code OPNYEAR}/{@code OPNMON}/{@code OPNDAY}
     * at {@code POS=(6,17)}, {@code (6,24)} and {@code (6,29)} are followed by {@code ACRDLIM} at
     * {@code POS=(6,61)}, then the expiry triple on row 7 by {@code ACSHLIM} at {@code POS=(7,61)}, and
     * so on. It is the copybook's order and it is not sorted.
     *
     * <p>The 74 unnamed {@code DFHMDF} entries of the mapset have no constant here. An unnamed entry is a
     * screen literal - {@code INITIAL='Tran:'}, {@code INITIAL='Credit Limit        :'} and the like -
     * which BMS paints but never reports back, so it generates no symbolic-map item and holds no value
     * this class could carry.
     *
     * <p>Note what is <em>not</em> here and must not be added: no {@code PAGENO}, and none of the
     * sibling view screen's undivided {@code ADTOPEN}, {@code AEXPDT}, {@code AREISDT}, {@code ACSTSSN}
     * or {@code ACSTDOB}. This screen splits all five into the twenty part-fields below, and that split
     * is what {@code 9700-CHECK-CHANGE-IN-REC} and the {@code STRING ... DELIMITED BY SIZE} sites depend
     * on.
     */
    public enum ScreenField {

        /**
         * {@code TRNNAME} - the transaction identifier, {@code TRNNAMEI PIC X(4)} at
         * {@code POS=(1,7)}. Declared at {@code app/bms/COACTUP.bms:34}
         * and {@code app/cpy-bms/COACTUP.CPY:24}, with its data at group offset 19.
         */
        TRNNAME("TRNNAME", "TRNNAMEI", "X(4)", TRNNAME_LENGTH, 24, 34, 1, 7, 19),

        /**
         * {@code TITLE01} - the first title line, {@code TITLE01I PIC X(40)} at
         * {@code POS=(1,21)}. Declared at {@code app/bms/COACTUP.bms:38}
         * and {@code app/cpy-bms/COACTUP.CPY:30}, with its data at group offset 30.
         */
        TITLE01("TITLE01", "TITLE01I", "X(40)", TITLE01_LENGTH, 30, 38, 1, 21, 30),

        /**
         * {@code CURDATE} - the current date, {@code CURDATEI PIC X(8)} at
         * {@code POS=(1,71)}, {@code INITIAL='mm/dd/yy'}. Declared at {@code app/bms/COACTUP.bms:47}
         * and {@code app/cpy-bms/COACTUP.CPY:36}, with its data at group offset 77.
         */
        CURDATE("CURDATE", "CURDATEI", "X(8)", CURDATE_LENGTH, 36, 47, 1, 71, 77),

        /**
         * {@code PGMNAME} - the program name, {@code PGMNAMEI PIC X(8)} at
         * {@code POS=(2,7)}. Declared at {@code app/bms/COACTUP.bms:57}
         * and {@code app/cpy-bms/COACTUP.CPY:42}, with its data at group offset 92.
         */
        PGMNAME("PGMNAME", "PGMNAMEI", "X(8)", PGMNAME_LENGTH, 42, 57, 2, 7, 92),

        /**
         * {@code TITLE02} - the second title line, {@code TITLE02I PIC X(40)} at
         * {@code POS=(2,21)}. Declared at {@code app/bms/COACTUP.bms:61}
         * and {@code app/cpy-bms/COACTUP.CPY:48}, with its data at group offset 107.
         */
        TITLE02("TITLE02", "TITLE02I", "X(40)", TITLE02_LENGTH, 48, 61, 2, 21, 107),

        /**
         * {@code CURTIME} - the current time, {@code CURTIMEI PIC X(8)} at
         * {@code POS=(2,71)}, {@code INITIAL='hh:mm:ss'}. Declared at {@code app/bms/COACTUP.bms:70}
         * and {@code app/cpy-bms/COACTUP.CPY:54}, with its data at group offset 154.
         */
        CURTIME("CURTIME", "CURTIMEI", "X(8)", CURTIME_LENGTH, 54, 70, 2, 71, 154),

        /**
         * {@code ACCTSID} - the account filter, and the field the insertion cursor starts on, {@code ACCTSIDI PIC X(11)} at
         * {@code POS=(5,38)}. Declared at {@code app/bms/COACTUP.bms:84}
         * and {@code app/cpy-bms/COACTUP.CPY:60}, with its data at group offset 169.
         */
        ACCTSID("ACCTSID", "ACCTSIDI", "X(11)", ACCTSID_LENGTH, 60, 84, 5, 38, 169),

        /**
         * {@code ACSTTUS} - the active status, {@code ACSTTUSI PIC X(1)} at
         * {@code POS=(5,70)}. Declared at {@code app/bms/COACTUP.bms:94}
         * and {@code app/cpy-bms/COACTUP.CPY:66}, with its data at group offset 187.
         */
        ACSTTUS("ACSTTUS", "ACSTTUSI", "X(1)", ACSTTUS_LENGTH, 66, 94, 5, 70, 187),

        /**
         * {@code OPNYEAR} - the open date year, {@code OPNYEARI PIC X(4)} at
         * {@code POS=(6,17)}. Declared at {@code app/bms/COACTUP.bms:104}
         * and {@code app/cpy-bms/COACTUP.CPY:72}, with its data at group offset 195.
         */
        OPNYEAR("OPNYEAR", "OPNYEARI", "X(4)", OPNYEAR_LENGTH, 72, 104, 6, 17, 195),

        /**
         * {@code OPNMON} - the open date month, {@code OPNMONI PIC X(2)} at
         * {@code POS=(6,24)}. Declared at {@code app/bms/COACTUP.bms:112}
         * and {@code app/cpy-bms/COACTUP.CPY:78}, with its data at group offset 206.
         */
        OPNMON("OPNMON", "OPNMONI", "X(2)", OPNMON_LENGTH, 78, 112, 6, 24, 206),

        /**
         * {@code OPNDAY} - the open date day, {@code OPNDAYI PIC X(2)} at
         * {@code POS=(6,29)}. Declared at {@code app/bms/COACTUP.bms:120}
         * and {@code app/cpy-bms/COACTUP.CPY:84}, with its data at group offset 215.
         */
        OPNDAY("OPNDAY", "OPNDAYI", "X(2)", OPNDAY_LENGTH, 84, 120, 6, 29, 215),

        /**
         * {@code ACRDLIM} - the credit limit, {@code ACRDLIMI PIC X(15)} at
         * {@code POS=(6,61)}. Declared at {@code app/bms/COACTUP.bms:132}
         * and {@code app/cpy-bms/COACTUP.CPY:90}, with its data at group offset 224.
         */
        ACRDLIM("ACRDLIM", "ACRDLIMI", "X(15)", ACRDLIM_LENGTH, 90, 132, 6, 61, 224),

        /**
         * {@code EXPYEAR} - the expiry date year, {@code EXPYEARI PIC X(4)} at
         * {@code POS=(7,17)}. Declared at {@code app/bms/COACTUP.bms:142}
         * and {@code app/cpy-bms/COACTUP.CPY:96}, with its data at group offset 246.
         */
        EXPYEAR("EXPYEAR", "EXPYEARI", "X(4)", EXPYEAR_LENGTH, 96, 142, 7, 17, 246),

        /**
         * {@code EXPMON} - the expiry date month, {@code EXPMONI PIC X(2)} at
         * {@code POS=(7,24)}. Declared at {@code app/bms/COACTUP.bms:150}
         * and {@code app/cpy-bms/COACTUP.CPY:102}, with its data at group offset 257.
         */
        EXPMON("EXPMON", "EXPMONI", "X(2)", EXPMON_LENGTH, 102, 150, 7, 24, 257),

        /**
         * {@code EXPDAY} - the expiry date day, {@code EXPDAYI PIC X(2)} at
         * {@code POS=(7,29)}. Declared at {@code app/bms/COACTUP.bms:158}
         * and {@code app/cpy-bms/COACTUP.CPY:108}, with its data at group offset 266.
         */
        EXPDAY("EXPDAY", "EXPDAYI", "X(2)", EXPDAY_LENGTH, 108, 158, 7, 29, 266),

        /**
         * {@code ACSHLIM} - the cash credit limit, {@code ACSHLIMI PIC X(15)} at
         * {@code POS=(7,61)}. Declared at {@code app/bms/COACTUP.bms:170}
         * and {@code app/cpy-bms/COACTUP.CPY:114}, with its data at group offset 275.
         */
        ACSHLIM("ACSHLIM", "ACSHLIMI", "X(15)", ACSHLIM_LENGTH, 114, 170, 7, 61, 275),

        /**
         * {@code RISYEAR} - the reissue date year, {@code RISYEARI PIC X(4)} at
         * {@code POS=(8,17)}. Declared at {@code app/bms/COACTUP.bms:180}
         * and {@code app/cpy-bms/COACTUP.CPY:120}, with its data at group offset 297.
         */
        RISYEAR("RISYEAR", "RISYEARI", "X(4)", RISYEAR_LENGTH, 120, 180, 8, 17, 297),

        /**
         * {@code RISMON} - the reissue date month, {@code RISMONI PIC X(2)} at
         * {@code POS=(8,24)}. Declared at {@code app/bms/COACTUP.bms:188}
         * and {@code app/cpy-bms/COACTUP.CPY:126}, with its data at group offset 308.
         */
        RISMON("RISMON", "RISMONI", "X(2)", RISMON_LENGTH, 126, 188, 8, 24, 308),

        /**
         * {@code RISDAY} - the reissue date day, {@code RISDAYI PIC X(2)} at
         * {@code POS=(8,29)}. Declared at {@code app/bms/COACTUP.bms:196}
         * and {@code app/cpy-bms/COACTUP.CPY:132}, with its data at group offset 317.
         */
        RISDAY("RISDAY", "RISDAYI", "X(2)", RISDAY_LENGTH, 132, 196, 8, 29, 317),

        /**
         * {@code ACURBAL} - the current balance, {@code ACURBALI PIC X(15)} at
         * {@code POS=(8,61)}. Declared at {@code app/bms/COACTUP.bms:208}
         * and {@code app/cpy-bms/COACTUP.CPY:138}, with its data at group offset 326.
         */
        ACURBAL("ACURBAL", "ACURBALI", "X(15)", ACURBAL_LENGTH, 138, 208, 8, 61, 326),

        /**
         * {@code ACRCYCR} - the current cycle credit, {@code ACRCYCRI PIC X(15)} at
         * {@code POS=(9,61)}. Declared at {@code app/bms/COACTUP.bms:219}
         * and {@code app/cpy-bms/COACTUP.CPY:144}, with its data at group offset 348.
         */
        ACRCYCR("ACRCYCR", "ACRCYCRI", "X(15)", ACRCYCR_LENGTH, 144, 219, 9, 61, 348),

        /**
         * {@code AADDGRP} - the account group identifier, {@code AADDGRPI PIC X(10)} at
         * {@code POS=(10,23)}. Declared at {@code app/bms/COACTUP.bms:229}
         * and {@code app/cpy-bms/COACTUP.CPY:150}, with its data at group offset 370.
         */
        AADDGRP("AADDGRP", "AADDGRPI", "X(10)", AADDGRP_LENGTH, 150, 229, 10, 23, 370),

        /**
         * {@code ACRCYDB} - the current cycle debit, {@code ACRCYDBI PIC X(15)} at
         * {@code POS=(10,61)}. Declared at {@code app/bms/COACTUP.bms:240}
         * and {@code app/cpy-bms/COACTUP.CPY:156}, with its data at group offset 387.
         */
        ACRCYDB("ACRCYDB", "ACRCYDBI", "X(15)", ACRCYDB_LENGTH, 156, 240, 10, 61, 387),

        /**
         * {@code ACSTNUM} - the customer number, {@code ACSTNUMI PIC X(9)} at
         * {@code POS=(12,23)}. Declared at {@code app/bms/COACTUP.bms:254}
         * and {@code app/cpy-bms/COACTUP.CPY:162}, with its data at group offset 409.
         */
        ACSTNUM("ACSTNUM", "ACSTNUMI", "X(9)", ACSTNUM_LENGTH, 162, 254, 12, 23, 409),

        /**
         * {@code ACTSSN1} - social security number part one, {@code ACTSSN1I PIC X(3)} at
         * {@code POS=(12,55)}, {@code INITIAL='999'}. Declared at {@code app/bms/COACTUP.bms:264}
         * and {@code app/cpy-bms/COACTUP.CPY:168}, with its data at group offset 425.
         */
        ACTSSN1("ACTSSN1", "ACTSSN1I", "X(3)", ACTSSN1_LENGTH, 168, 264, 12, 55, 425),

        /**
         * {@code ACTSSN2} - social security number part two, {@code ACTSSN2I PIC X(2)} at
         * {@code POS=(12,61)}, {@code INITIAL='99'}. Declared at {@code app/bms/COACTUP.bms:272}
         * and {@code app/cpy-bms/COACTUP.CPY:174}, with its data at group offset 435.
         */
        ACTSSN2("ACTSSN2", "ACTSSN2I", "X(2)", ACTSSN2_LENGTH, 174, 272, 12, 61, 435),

        /**
         * {@code ACTSSN3} - social security number part three, {@code ACTSSN3I PIC X(4)} at
         * {@code POS=(12,66)}, {@code INITIAL='9999'}. Declared at {@code app/bms/COACTUP.bms:280}
         * and {@code app/cpy-bms/COACTUP.CPY:180}, with its data at group offset 444.
         */
        ACTSSN3("ACTSSN3", "ACTSSN3I", "X(4)", ACTSSN3_LENGTH, 180, 280, 12, 66, 444),

        /**
         * {@code DOBYEAR} - the date of birth year, {@code DOBYEARI PIC X(4)} at
         * {@code POS=(13,23)}. Declared at {@code app/bms/COACTUP.bms:291}
         * and {@code app/cpy-bms/COACTUP.CPY:186}, with its data at group offset 455.
         */
        DOBYEAR("DOBYEAR", "DOBYEARI", "X(4)", DOBYEAR_LENGTH, 186, 291, 13, 23, 455),

        /**
         * {@code DOBMON} - the date of birth month, {@code DOBMONI PIC X(2)} at
         * {@code POS=(13,30)}. Declared at {@code app/bms/COACTUP.bms:299}
         * and {@code app/cpy-bms/COACTUP.CPY:192}, with its data at group offset 466.
         */
        DOBMON("DOBMON", "DOBMONI", "X(2)", DOBMON_LENGTH, 192, 299, 13, 30, 466),

        /**
         * {@code DOBDAY} - the date of birth day, {@code DOBDAYI PIC X(2)} at
         * {@code POS=(13,35)}. Declared at {@code app/bms/COACTUP.bms:307}
         * and {@code app/cpy-bms/COACTUP.CPY:198}, with its data at group offset 475.
         */
        DOBDAY("DOBDAY", "DOBDAYI", "X(2)", DOBDAY_LENGTH, 198, 307, 13, 35, 475),

        /**
         * {@code ACSTFCO} - the FICO credit score, {@code ACSTFCOI PIC X(3)} at
         * {@code POS=(13,62)}. Declared at {@code app/bms/COACTUP.bms:318}
         * and {@code app/cpy-bms/COACTUP.CPY:204}, with its data at group offset 484.
         */
        ACSTFCO("ACSTFCO", "ACSTFCOI", "X(3)", ACSTFCO_LENGTH, 204, 318, 13, 62, 484),

        /**
         * {@code ACSFNAM} - the first name, {@code ACSFNAMI PIC X(25)} at
         * {@code POS=(15,1)}. Declared at {@code app/bms/COACTUP.bms:336}
         * and {@code app/cpy-bms/COACTUP.CPY:210}, with its data at group offset 494.
         */
        ACSFNAM("ACSFNAM", "ACSFNAMI", "X(25)", ACSFNAM_LENGTH, 210, 336, 15, 1, 494),

        /**
         * {@code ACSMNAM} - the middle name, {@code ACSMNAMI PIC X(25)} at
         * {@code POS=(15,28)}. Declared at {@code app/bms/COACTUP.bms:342}
         * and {@code app/cpy-bms/COACTUP.CPY:216}, with its data at group offset 526.
         */
        ACSMNAM("ACSMNAM", "ACSMNAMI", "X(25)", ACSMNAM_LENGTH, 216, 342, 15, 28, 526),

        /**
         * {@code ACSLNAM} - the last name, {@code ACSLNAMI PIC X(25)} at
         * {@code POS=(15,55)}. Declared at {@code app/bms/COACTUP.bms:348}
         * and {@code app/cpy-bms/COACTUP.CPY:222}, with its data at group offset 558.
         */
        ACSLNAM("ACSLNAM", "ACSLNAMI", "X(25)", ACSLNAM_LENGTH, 222, 348, 15, 55, 558),

        /**
         * {@code ACSADL1} - address line one, {@code ACSADL1I PIC X(50)} at
         * {@code POS=(16,10)}. Declared at {@code app/bms/COACTUP.bms:356}
         * and {@code app/cpy-bms/COACTUP.CPY:228}, with its data at group offset 590.
         */
        ACSADL1("ACSADL1", "ACSADL1I", "X(50)", ACSADL1_LENGTH, 228, 356, 16, 10, 590),

        /**
         * {@code ACSSTTE} - the state code, {@code ACSSTTEI PIC X(2)} at
         * {@code POS=(16,73)}. Declared at {@code app/bms/COACTUP.bms:366}
         * and {@code app/cpy-bms/COACTUP.CPY:234}, with its data at group offset 647.
         */
        ACSSTTE("ACSSTTE", "ACSSTTEI", "X(2)", ACSSTTE_LENGTH, 234, 366, 16, 73, 647),

        /**
         * {@code ACSADL2} - address line two, {@code ACSADL2I PIC X(50)} at
         * {@code POS=(17,10)}. Declared at {@code app/bms/COACTUP.bms:372}
         * and {@code app/cpy-bms/COACTUP.CPY:240}, with its data at group offset 656.
         */
        ACSADL2("ACSADL2", "ACSADL2I", "X(50)", ACSADL2_LENGTH, 240, 372, 17, 10, 656),

        /**
         * {@code ACSZIPC} - the postal code, {@code ACSZIPCI PIC X(5)} at
         * {@code POS=(17,73)}. Declared at {@code app/bms/COACTUP.bms:382}
         * and {@code app/cpy-bms/COACTUP.CPY:246}, with its data at group offset 713.
         */
        ACSZIPC("ACSZIPC", "ACSZIPCI", "X(5)", ACSZIPC_LENGTH, 246, 382, 17, 73, 713),

        /**
         * {@code ACSCITY} - the city, {@code ACSCITYI PIC X(50)} at
         * {@code POS=(18,10)}. Declared at {@code app/bms/COACTUP.bms:392}
         * and {@code app/cpy-bms/COACTUP.CPY:252}, with its data at group offset 725.
         */
        ACSCITY("ACSCITY", "ACSCITYI", "X(50)", ACSCITY_LENGTH, 252, 392, 18, 10, 725),

        /**
         * {@code ACSCTRY} - the country code, {@code ACSCTRYI PIC X(3)} at
         * {@code POS=(18,73)}. Declared at {@code app/bms/COACTUP.bms:402}
         * and {@code app/cpy-bms/COACTUP.CPY:258}, with its data at group offset 782.
         */
        ACSCTRY("ACSCTRY", "ACSCTRYI", "X(3)", ACSCTRY_LENGTH, 258, 402, 18, 73, 782),

        /**
         * {@code ACSPH1A} - telephone one area code, {@code ACSPH1AI PIC X(3)} at
         * {@code POS=(19,10)}. Declared at {@code app/bms/COACTUP.bms:412}
         * and {@code app/cpy-bms/COACTUP.CPY:264}, with its data at group offset 792.
         */
        ACSPH1A("ACSPH1A", "ACSPH1AI", "X(3)", ACSPH1A_LENGTH, 264, 412, 19, 10, 792),

        /**
         * {@code ACSPH1B} - telephone one prefix, {@code ACSPH1BI PIC X(3)} at
         * {@code POS=(19,14)}. Declared at {@code app/bms/COACTUP.bms:417}
         * and {@code app/cpy-bms/COACTUP.CPY:270}, with its data at group offset 802.
         */
        ACSPH1B("ACSPH1B", "ACSPH1BI", "X(3)", ACSPH1B_LENGTH, 270, 417, 19, 14, 802),

        /**
         * {@code ACSPH1C} - telephone one line number, {@code ACSPH1CI PIC X(4)} at
         * {@code POS=(19,18)}. Declared at {@code app/bms/COACTUP.bms:422}
         * and {@code app/cpy-bms/COACTUP.CPY:276}, with its data at group offset 812.
         */
        ACSPH1C("ACSPH1C", "ACSPH1CI", "X(4)", ACSPH1C_LENGTH, 276, 422, 19, 18, 812),

        /**
         * {@code ACSGOVT} - the government-issued identifier reference, {@code ACSGOVTI PIC X(20)} at
         * {@code POS=(19,58)}. Declared at {@code app/bms/COACTUP.bms:433}
         * and {@code app/cpy-bms/COACTUP.CPY:282}, with its data at group offset 823.
         */
        ACSGOVT("ACSGOVT", "ACSGOVTI", "X(20)", ACSGOVT_LENGTH, 282, 433, 19, 58, 823),

        /**
         * {@code ACSPH2A} - telephone two area code, {@code ACSPH2AI PIC X(3)} at
         * {@code POS=(20,10)}. Declared at {@code app/bms/COACTUP.bms:443}
         * and {@code app/cpy-bms/COACTUP.CPY:288}, with its data at group offset 850.
         */
        ACSPH2A("ACSPH2A", "ACSPH2AI", "X(3)", ACSPH2A_LENGTH, 288, 443, 20, 10, 850),

        /**
         * {@code ACSPH2B} - telephone two prefix, {@code ACSPH2BI PIC X(3)} at
         * {@code POS=(20,14)}. Declared at {@code app/bms/COACTUP.bms:448}
         * and {@code app/cpy-bms/COACTUP.CPY:294}, with its data at group offset 860.
         */
        ACSPH2B("ACSPH2B", "ACSPH2BI", "X(3)", ACSPH2B_LENGTH, 294, 448, 20, 14, 860),

        /**
         * {@code ACSPH2C} - telephone two line number, {@code ACSPH2CI PIC X(4)} at
         * {@code POS=(20,18)}. Declared at {@code app/bms/COACTUP.bms:453}
         * and {@code app/cpy-bms/COACTUP.CPY:300}, with its data at group offset 870.
         */
        ACSPH2C("ACSPH2C", "ACSPH2CI", "X(4)", ACSPH2C_LENGTH, 300, 453, 20, 18, 870),

        /**
         * {@code ACSEFTC} - the electronic funds transfer account identifier, {@code ACSEFTCI PIC X(10)} at
         * {@code POS=(20,41)}. Declared at {@code app/bms/COACTUP.bms:464}
         * and {@code app/cpy-bms/COACTUP.CPY:306}, with its data at group offset 881.
         */
        ACSEFTC("ACSEFTC", "ACSEFTCI", "X(10)", ACSEFTC_LENGTH, 306, 464, 20, 41, 881),

        /**
         * {@code ACSPFLG} - the primary card holder indicator, {@code ACSPFLGI PIC X(1)} at
         * {@code POS=(20,78)}. Declared at {@code app/bms/COACTUP.bms:474}
         * and {@code app/cpy-bms/COACTUP.CPY:312}, with its data at group offset 898.
         */
        ACSPFLG("ACSPFLG", "ACSPFLGI", "X(1)", ACSPFLG_LENGTH, 312, 474, 20, 78, 898),

        /**
         * {@code INFOMSG} - the informational message line, {@code INFOMSGI PIC X(45)} at
         * {@code POS=(22,23)}. Declared at {@code app/bms/COACTUP.bms:480}
         * and {@code app/cpy-bms/COACTUP.CPY:318}, with its data at group offset 906.
         */
        INFOMSG("INFOMSG", "INFOMSGI", "X(45)", INFOMSG_LENGTH, 318, 480, 22, 23, 906),

        /**
         * {@code ERRMSG} - the error message line, {@code ERRMSGI PIC X(78)} at
         * {@code POS=(23,1)}. Declared at {@code app/bms/COACTUP.bms:489}
         * and {@code app/cpy-bms/COACTUP.CPY:324}, with its data at group offset 958.
         */
        ERRMSG("ERRMSG", "ERRMSGI", "X(78)", ERRMSG_LENGTH, 324, 489, 23, 1, 958),

        /**
         * {@code FKEYS} - the always-visible function-key legend, {@code FKEYSI PIC X(21)} at
         * {@code POS=(24,1)}, {@code INITIAL='ENTER=Process F3=Exit'}. Declared at {@code app/bms/COACTUP.bms:493}
         * and {@code app/cpy-bms/COACTUP.CPY:330}, with its data at group offset 1043.
         */
        FKEYS("FKEYS", "FKEYSI", "X(21)", FKEYS_LENGTH, 330, 493, 24, 1, 1043),

        /**
         * {@code FKEY05} - the conditionally revealed save legend, {@code FKEY05I PIC X(7)} at
         * {@code POS=(24,23)}, {@code INITIAL='F5=Save'}. Declared at {@code app/bms/COACTUP.bms:498}
         * and {@code app/cpy-bms/COACTUP.CPY:336}, with its data at group offset 1071.
         */
        FKEY05("FKEY05", "FKEY05I", "X(7)", FKEY05_LENGTH, 336, 498, 24, 23, 1071),

        /**
         * {@code FKEY12} - the conditionally revealed cancel legend, {@code FKEY12I PIC X(10)} at
         * {@code POS=(24,31)}, {@code INITIAL='F12=Cancel'}. Declared at {@code app/bms/COACTUP.bms:503}
         * and {@code app/cpy-bms/COACTUP.CPY:342}, with its data at group offset 1085.
         */
        FKEY12("FKEY12", "FKEY12I", "X(10)", FKEY12_LENGTH, 342, 503, 24, 31, 1085);

        /** The {@code DFHMDF} label, verbatim. */
        private final String label;

        /** The symbolic-map input item name - the label with an {@code I} suffix. */
        private final String symbolicItemName;

        /** The {@code PICTURE} as {@code app/cpy-bms/COACTUP.CPY} writes it. */
        private final String picture;

        /** The declared width in characters, which is also the {@code DFHMDF LENGTH=} operand. */
        private final int length;

        /** The line of {@code app/cpy-bms/COACTUP.CPY} declaring the {@code xxxI} item. */
        private final int copybookLine;

        /** The line of {@code app/bms/COACTUP.bms} carrying the name-labelled {@code DFHMDF}. */
        private final int mapsetLine;

        /** The screen row from {@code POS=(row,column)}, 1-based as BMS writes it. */
        private final int screenRow;

        /** The screen column from {@code POS=(row,column)}, 1-based as BMS writes it. */
        private final int screenColumn;

        /** The absolute 0-based offset of the {@code xxxI} data within the group image. */
        private final int dataOffset;

        /**
         * Records one field's provenance.
         *
         * @param label            the {@code DFHMDF} label
         * @param symbolicItemName the {@code xxxI} item name
         * @param picture          the {@code PICTURE} as written
         * @param length           the declared width
         * @param copybookLine     the {@code app/cpy-bms/COACTUP.CPY} line
         * @param mapsetLine       the {@code app/bms/COACTUP.bms} line
         * @param screenRow        the {@code POS} row
         * @param screenColumn     the {@code POS} column
         * @param dataOffset       the absolute 0-based data offset in the group image
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
         * The symbolic-map input item name, as {@code app/cpy-bms/COACTUP.CPY} declares it.
         *
         * @return the {@code xxxI} item name
         */
        public String symbolicItemName() {
            return symbolicItemName;
        }

        /**
         * The {@code PICTURE} as written. Every one of the {@value AccountUpdateRequest#FIELD_COUNT}
         * fields is {@code X(n)}, because {@code app/bms/COACTUP.bms} declares no {@code PICIN}.
         *
         * @return the picture string
         */
        public String picture() {
            return picture;
        }

        /**
         * Whether this field's {@code PICTURE} is alphanumeric.
         *
         * @return {@code true} for every field of this mapset, which is the property being asserted
         */
        public boolean isAlphanumeric() {
            return picture.startsWith("X(");
        }

        /**
         * The declared width in characters.
         *
         * @return the width, from the {@code xxxI} {@code PICTURE} and the {@code DFHMDF LENGTH=} operand,
         *         which agree
         */
        public int length() {
            return length;
        }

        /**
         * The declaring line of the symbolic map.
         *
         * @return the {@code app/cpy-bms/COACTUP.CPY} line number
         */
        public int copybookLine() {
            return copybookLine;
        }

        /**
         * The declaring line of the mapset.
         *
         * @return the {@code app/bms/COACTUP.bms} line number
         */
        public int mapsetLine() {
            return mapsetLine;
        }

        /**
         * The 1-based screen row from {@code POS}.
         *
         * @return the row, between 1 and {@value AccountUpdateRequest#SCREEN_ROWS}
         */
        public int screenRow() {
            return screenRow;
        }

        /**
         * The 1-based screen column from {@code POS}.
         *
         * @return the column, between 1 and {@value AccountUpdateRequest#SCREEN_COLUMNS}
         */
        public int screenColumn() {
            return screenColumn;
        }

        /**
         * The absolute 0-based offset of this field's data within the {@value #GROUP_LENGTH}-byte group
         * image.
         *
         * @return the data offset
         */
        public int dataOffset() {
            return dataOffset;
        }

        /**
         * The absolute 0-based offset of this field's {@code xxxL COMP PIC S9(4)} halfword - seven bytes
         * ahead of the data, because that is what the field's five items cost.
         *
         * @return the length item's offset
         */
        public int lengthItemOffset() {
            return dataOffset - FIELD_OVERHEAD;
        }

        /**
         * The absolute 0-based offset of this field's {@code xxxF PICTURE X} flag byte, which
         * {@code 03 xxxA PICTURE X} redefines. One byte, one offset, two names.
         *
         * @return the flag and attribute byte's offset
         */
        public int flagItemOffset() {
            return lengthItemOffset() + LENGTH_ITEM_LENGTH;
        }

        /**
         * The absolute 0-based offset of this field's unnamed {@code 02 FILLER PICTURE X(4)}
         * extended-attribute span.
         *
         * @return the extended-attribute {@code FILLER}'s offset
         */
        public int extendedAttributeItemOffset() {
            return flagItemOffset() + FLAG_ITEM_LENGTH;
        }

        /**
         * One past this field's last data byte, which is also the next field's
         * {@link #lengthItemOffset()}.
         *
         * @return the exclusive end offset of the data span
         */
        public int endOffsetExclusive() {
            return dataOffset + length;
        }

        /**
         * A one-line description naming everything a failure message needs.
         *
         * @return the label, item name, picture, screen position and both source lines
         */
        public String describe() {
            return label + " (" + symbolicItemName + " PIC " + picture + " at POS=(" + screenRow + ","
                    + screenColumn + "), app/bms/COACTUP.bms:" + mapsetLine
                    + ", app/cpy-bms/COACTUP.CPY:" + copybookLine + ")";
        }

        /**
         * Looks a field up by its {@code DFHMDF} label, for a differ or a case fixture that carries the
         * copybook name rather than an enum constant.
         *
         * @param label the {@code DFHMDF} label, matched exactly
         * @return the field
         * @throws NullPointerException     if {@code label} is {@code null}
         * @throws IllegalArgumentException if no field carries that label
         */
        public static ScreenField ofLabel(String label) {
            Objects.requireNonNull(label, "A DFHMDF label is required to look a field up");
            for (ScreenField field : values()) {
                if (field.label.equals(label)) {
                    return field;
                }
            }
            throw new IllegalArgumentException("app/bms/COACTUP.bms declares no name-labelled DFHMDF "
                    + "field called '" + label + "'; it declares " + FIELD_COUNT + ", from "
                    + TRNNAME.label + " to " + FKEY12.label);
        }
    }

    // =================================================================================================
    // The non-payload half of each field: the xxxL length halfword and the xxxA attribute byte. These are
    // storage the copybook declares and the program writes, so they exist here - but they are not data the
    // screen carries, so they are kept off the JSON wire.
    // =================================================================================================

    /**
     * The {@code xxxL} and {@code xxxA} items of one input field: the two pieces of per-field metadata a
     * BMS symbolic map declares beside the data, and the two {@code COACTUPC} actually writes.
     *
     * <p>Never serialised - {@link AccountUpdateRequest#metadata()} and
     * {@link AccountUpdateRequest#metadata(ScreenField)} are both {@link JsonIgnore}d, so no instance
     * reaches the wire. Publishing it would add payload members tracing to no {@code DFHMDF} data item and
     * break gate <strong>G9</strong>; omitting it altogether would lose the cursor positioning and the
     * field protection {@code COACTUPC} performs at 41 and 75 sites respectively. Holding it here, off the
     * wire, keeps both true.
     *
     * <h2>The length item</h2>
     *
     * <p>{@code 02 xxxL COMP PIC S9(4)} is a signed binary halfword. On a {@code RECEIVE MAP} CICS sets it
     * to the number of characters the operator typed, so {@value #LENGTH_UNSET} means the field was not
     * entered - the only mechanism a symbolic map offers for telling "left blank" from "blanked out". On a
     * {@code SEND MAP} the value {@value #CURSOR_HERE} has a second meaning: it asks CICS to place the
     * cursor on that field. {@code COACTUPC} relies on exactly that, and the {@code EVALUATE TRUE} inside
     * {@code 3300-SETUP-SCREEN-ATTRS} chooses between candidates in screen-location order - {@code
     * ACSTTUSL} when the account data was found and no change was detected, {@code ACCTSIDL} when the
     * account filter is blank or invalid, and so on down the screen.
     *
     * <p>The declared {@code PICTURE} is the constraint, not the storage: a halfword holds &plusmn;32767,
     * but {@code S9(4)} holds only {@value #LENGTH_ITEM_MIN} to {@value #LENGTH_ITEM_MAX}, and the
     * constructor enforces the {@code PICTURE}. Rejecting a value the copybook cannot represent is the
     * point - silently storing 30000 in a field declared {@code S9(4)} is precisely the kind of divergence
     * a parity migration exists to prevent. It is also why this carrier is signed: an unsigned length
     * cannot hold {@value #CURSOR_HERE} at all.
     *
     * <h2>The attribute item</h2>
     *
     * <p>{@code 02 xxxF PICTURE X} is the flag byte and {@code 03 xxxA PICTURE X}, declared under
     * {@code 02 FILLER REDEFINES xxxF}, is a second view of that same single byte. There is therefore one
     * value here, not two: {@link #attribute()} is the {@code xxxA} view and {@link #flag()} the
     * {@code xxxF} view of the identical byte, which is what a {@code REDEFINES} means.
     *
     * <p>{@code COACTUPC} writes it through the {@code xxxA} view onto the <em>input</em> group.
     * {@code 3310-PROTECT-ALL-ATTRS} moves {@link BmsAttributes#DFHBMPRF} into {@code ACCTSIDA},
     * {@code ACSTTUSA}, {@code ACRDLIMA}, {@code ACSHLIMA}, {@code ACURBALA}, {@code ACRCYCRA},
     * {@code ACRCYDBA}, {@code OPNYEARA}, {@code EXPYEARA}, {@code RISYEARA}, {@code AADDGRPA},
     * {@code ACSTNUMA}, {@code ACTSSN1A} and the rest {@code OF CACTUPAI} from
     * {@code app/cbl/COACTUPC.cbl:3442} onward; {@code 3320-UNPROTECT-FEW-ATTRS} and the
     * {@code EVALUATE TRUE} above it move {@link BmsAttributes#DFHBMFSE} selectively, at {@code :2996}
     * and {@code :3005} among others; and {@code :3575} and {@code :3579-3580} move
     * {@link BmsAttributes#DFHBMASB} into {@code FKEY12A} and {@code FKEY05A} to reveal the two hidden
     * legends. Because this type is immutable the write is expressed as
     * {@link AccountUpdateRequest#withAttribute(ScreenField, byte)}, which returns a new request.
     *
     * <p>Every constant comes from {@code common/BmsAttributes}, reproduced there from IBM CICS
     * documentation because {@code DFHBMSCA} and {@code DFHATTR} are absent from this repository. This
     * carrier stores whichever byte it is handed and interprets it only through
     * {@link BmsAttributes}' own predicates and mnemonics, so no raw byte is written as a literal here.
     *
     * <p>Held as a {@code byte} rather than as a character because a 3270 attribute is a bit pattern, not
     * text: it is not a character in any code page's printable range, and passing it through a character
     * decoder would corrupt it. The same byte means the same thing whether the data around it is EBCDIC or
     * ASCII, so it is written and read raw and no charset is consulted for it.
     *
     * @param lengthItem the {@code xxxL COMP PIC S9(4)} halfword; {@value #LENGTH_UNSET} for a field CICS
     *                   reports as not entered, {@value #CURSOR_HERE} to request the cursor
     * @param attribute  the single byte declared {@code xxxF PICTURE X} and redefined
     *                   {@code xxxA PICTURE X}; {@code LOW-VALUES} when untouched
     */
    public record FieldMetadata(int lengthItem, byte attribute) {

        /** Lowest value {@code xxxL COMP PIC S9(4)} can represent: four digits and a sign. */
        public static final int LENGTH_ITEM_MIN = -9999;

        /** Highest value {@code xxxL COMP PIC S9(4)} can represent. */
        public static final int LENGTH_ITEM_MAX = 9999;

        /**
         * The length item of a field CICS reports as not entered, and the value a freshly initialised map
         * area holds.
         */
        public static final int LENGTH_UNSET = 0;

        /**
         * The length item that asks CICS to place the cursor on this field - the {@code MOVE -1 TO xxxL}
         * convention {@code COACTUPC} uses at 41 sites, {@code app/cbl/COACTUPC.cbl:3015} and
         * {@code :3166} among them.
         */
        public static final int CURSOR_HERE = -1;

        /**
         * The unset attribute byte: {@code LOW-VALUES}, which is binary zero on every code page because it
         * is by definition the lowest character of the collating sequence.
         */
        public static final byte ATTRIBUTE_UNSET = LOW_VALUE_BYTE;

        /**
         * Enforces the declared {@code PICTURE} rather than the halfword's wider capacity.
         *
         * @throws IllegalArgumentException if {@code lengthItem} is outside {@value #LENGTH_ITEM_MIN} to
         *                                  {@value #LENGTH_ITEM_MAX}
         */
        public FieldMetadata {
            if (lengthItem < LENGTH_ITEM_MIN || lengthItem > LENGTH_ITEM_MAX) {
                throw new IllegalArgumentException("An xxxL item is COMP PIC S9(4) and holds "
                        + LENGTH_ITEM_MIN + " to " + LENGTH_ITEM_MAX + ", but was given " + lengthItem
                        + "; the PICTURE is the constraint, not the halfword's wider capacity");
            }
        }

        /**
         * The state a map area holds before CICS or the program touches it: no length reported and a
         * {@code LOW-VALUES} attribute byte.
         *
         * @return the unset pair
         */
        public static FieldMetadata unset() {
            return new FieldMetadata(LENGTH_UNSET, ATTRIBUTE_UNSET);
        }

        /**
         * A pair with the cursor requested on this field and no attribute assigned.
         *
         * @return a pair reporting {@link #isCursorHere()}
         */
        public static FieldMetadata cursorHere() {
            return new FieldMetadata(CURSOR_HERE, ATTRIBUTE_UNSET);
        }

        /**
         * A pair with a given attribute byte and no length reported.
         *
         * @param attribute the attribute byte, from {@code common/BmsAttributes}
         * @return the pair
         */
        public static FieldMetadata withAttributeOnly(byte attribute) {
            return new FieldMetadata(LENGTH_UNSET, attribute);
        }

        /**
         * The {@code xxxF} view of the single byte this pair holds. Identical to {@link #attribute()},
         * which is the {@code xxxA} view, because a {@code REDEFINES} over one position is one position -
         * having both names is what makes the aliasing visible instead of implied.
         *
         * @return the same byte {@link #attribute()} returns
         */
        @JsonIgnore
        public byte flag() {
            return attribute;
        }

        /**
         * This pair with a different length item.
         *
         * @param replacement the new {@code xxxL} value
         * @return a new pair
         * @throws IllegalArgumentException if {@code replacement} is outside the {@code PIC S9(4)} range
         */
        public FieldMetadata withLengthItem(int replacement) {
            return new FieldMetadata(replacement, attribute);
        }

        /**
         * This pair with the cursor requested - the immutable form of {@code MOVE -1 TO xxxL}.
         *
         * @return a new pair reporting {@link #isCursorHere()}
         */
        public FieldMetadata withCursorHere() {
            return new FieldMetadata(CURSOR_HERE, attribute);
        }

        /**
         * This pair with a different attribute byte - the immutable form of {@code MOVE DFHxxx TO xxxA}.
         *
         * @param replacement the new attribute byte, from {@code common/BmsAttributes}
         * @return a new pair
         */
        public FieldMetadata withAttribute(byte replacement) {
            return new FieldMetadata(lengthItem, replacement);
        }

        /**
         * Whether this field is asking for the cursor.
         *
         * @return {@code true} when the length item is {@value #CURSOR_HERE}
         */
        @JsonIgnore
        public boolean isCursorHere() {
            return lengthItem == CURSOR_HERE;
        }

        /**
         * Whether CICS reported any characters for this field - the "was this field entered?" question a
         * symbolic map answers with its length item.
         *
         * @return {@code true} when the length item is above {@value #LENGTH_UNSET}
         */
        @JsonIgnore
        public boolean isEntered() {
            return lengthItem > LENGTH_UNSET;
        }

        /**
         * Whether the attribute byte is still {@code LOW-VALUES}, meaning neither CICS nor the program has
         * assigned one.
         *
         * @return {@code true} when the attribute byte is unset
         */
        @JsonIgnore
        public boolean isAttributeUnset() {
            return attribute == ATTRIBUTE_UNSET;
        }

        /**
         * Whether the attribute byte protects the field from typing, per
         * {@link BmsAttributes#isProtected(byte)}. {@link BmsAttributes#DFHBMPRF}, which
         * {@code 3310-PROTECT-ALL-ATTRS} moves into every updatable field, is such a byte.
         *
         * @return {@code true} when the field is protected
         */
        @JsonIgnore
        public boolean isProtectedField() {
            return BmsAttributes.isProtected(attribute);
        }

        /**
         * Whether the attribute byte is {@link BmsAttributes#DFHBMASB} - autoskip, bright - which is what
         * {@code app/cbl/COACTUPC.cbl:3575} and {@code :3579-3580} move into {@code FKEY12A} and
         * {@code FKEY05A} to reveal a legend declared {@code ATTRB=(ASKIP,DRK)}.
         *
         * @return {@code true} when the field has been made bright and skipped over
         */
        @JsonIgnore
        public boolean isBright() {
            return attribute == BmsAttributes.DFHBMASB;
        }

        /**
         * A rendering naming the length item and the attribute byte by mnemonic, so a failure message
         * reads {@code DFHBMPRF} rather than {@code 0x61}.
         *
         * @return a compact description of the pair
         */
        @Override
        public String toString() {
            return "{xxxL=" + lengthItem + (isCursorHere() ? " (cursor here)" : "")
                    + ", xxxA=" + BmsAttributes.toHex(attribute)
                    + " " + BmsAttributes.fieldAttributeMnemonic(attribute) + "}";
        }
    }

    // =================================================================================================
    // The work area, translated group by group from app/cbl/COACTUPC.cbl:652-849.
    // =================================================================================================

    /**
     * {@code 10 ACUP-CHANGE-ACTION PIC X(1) VALUE LOW-VALUES}, the single byte of
     * {@code 05 ACCT-UPDATE-SCREEN-DATA} - {@code app/cbl/COACTUPC.cbl:653-668}.
     *
     * <p>One byte carrying <strong>nine</strong> {@code 88}-level condition names, two of which are
     * groupings over several values. All nine are exposed as predicates and none is dropped, because the
     * {@code EVALUATE TRUE} at {@code app/cbl/COACTUPC.cbl:921-1004} and the decision logic of
     * {@code 2000-DECIDE-ACTION} branch on them:
     *
     * <pre>
     *   88 ACUP-DETAILS-NOT-FETCHED        VALUES LOW-VALUES, SPACES   *&gt; :656-658
     *   88 ACUP-SHOW-DETAILS               VALUE  'S'                  *&gt; :659
     *   88 ACUP-CHANGES-MADE               VALUES 'E','N','C','L','F'  *&gt; :660-662
     *   88 ACUP-CHANGES-NOT-OK             VALUE  'E'                  *&gt; :663
     *   88 ACUP-CHANGES-OK-NOT-CONFIRMED   VALUE  'N'                  *&gt; :664
     *   88 ACUP-CHANGES-OKAYED-AND-DONE    VALUE  'C'                  *&gt; :665
     *   88 ACUP-CHANGES-FAILED             VALUES 'L','F'              *&gt; :666
     *   88 ACUP-CHANGES-OKAYED-LOCK-ERROR  VALUE  'L'                  *&gt; :667
     *   88 ACUP-CHANGES-OKAYED-BUT-FAILED  VALUE  'F'                  *&gt; :668
     * </pre>
     *
     * <p><strong>{@code ACUP-CHANGES-MADE} covers five values, not two.</strong> The migration plan's
     * summary names only {@code 'E'} and {@code 'N'}; the source spreads
     * {@code VALUES 'E', 'N', 'C', 'L', 'F'} across three continuation lines at {@code :660-662}. Taking
     * the summary would make {@link #isChangesMade()} report false for three states the program treats as
     * "changes made", so the source wins and the divergence is recorded rather than silently adopted
     * (practice <strong>B4</strong>). {@code app/cbl/COCRDUPC.cbl:282-284} declares the same five for the
     * card screen, which corroborates the reading.
     *
     * <p>The groupings overlap the singular conditions deliberately, and that overlap is the contract: a
     * byte of {@code 'L'} satisfies {@code ACUP-CHANGES-MADE}, {@code ACUP-CHANGES-FAILED} and
     * {@code ACUP-CHANGES-OKAYED-LOCK-ERROR} at once, which is exactly how the {@code WHEN}s at
     * {@code :979-980} - {@code WHEN ACUP-CHANGES-OKAYED-AND-DONE} followed by
     * {@code WHEN ACUP-CHANGES-FAILED} - are able to share one arm.
     *
     * <p>{@link #of(String)} accepts a byte no condition covers, so that a caller can construct the state
     * that falls through to {@code WHEN OTHER}. Deciding what to do about it is
     * {@code AccountUpdateService}'s work (gate <strong>G51</strong>); being able to represent it is this
     * type's.
     *
     * @param value exactly one character - the byte itself, never a decoded meaning
     */
    public record ChangeAction(String value) {

        /** {@code VALUE LOW-VALUES}, the declared initial state: {@code x'00'}. */
        public static final String LOW_VALUES = "\u0000";

        /** {@code SPACES}, the other byte satisfying {@code ACUP-DETAILS-NOT-FETCHED}. */
        public static final String SPACES = " ";

        /** {@code 88 ACUP-SHOW-DETAILS VALUE 'S'}, {@code app/cbl/COACTUPC.cbl:659}. */
        public static final String SHOW_DETAILS = "S";

        /** {@code 88 ACUP-CHANGES-NOT-OK VALUE 'E'}, {@code app/cbl/COACTUPC.cbl:663}. */
        public static final String CHANGES_NOT_OK = "E";

        /**
         * {@code 88 ACUP-CHANGES-OK-NOT-CONFIRMED VALUE 'N'}, {@code app/cbl/COACTUPC.cbl:664}.
         *
         * <p>The state in which {@code F5} becomes meaningful: {@code app/cbl/COACTUPC.cbl:908} admits
         * {@code CCARD-AID-PFK05} only when {@code ACUP-CHANGES-OK-NOT-CONFIRMED} holds, which is why
         * {@code FKEY05} is revealed in this state and hidden otherwise.
         */
        public static final String CHANGES_OK_NOT_CONFIRMED = "N";

        /** {@code 88 ACUP-CHANGES-OKAYED-AND-DONE VALUE 'C'}, {@code app/cbl/COACTUPC.cbl:665}. */
        public static final String CHANGES_OKAYED_AND_DONE = "C";

        /** {@code 88 ACUP-CHANGES-OKAYED-LOCK-ERROR VALUE 'L'}, {@code app/cbl/COACTUPC.cbl:667}. */
        public static final String CHANGES_OKAYED_LOCK_ERROR = "L";

        /** {@code 88 ACUP-CHANGES-OKAYED-BUT-FAILED VALUE 'F'}, {@code app/cbl/COACTUPC.cbl:668}. */
        public static final String CHANGES_OKAYED_BUT_FAILED = "F";

        /** Declared width of {@code ACUP-CHANGE-ACTION PIC X(1)}. */
        public static final int RECORD_LENGTH = 1;

        /** Copybook name of the field, verbatim. */
        public static final String FIELD_NAME = "ACUP-CHANGE-ACTION";

        /** Verbatim COBOL name of the {@code 05}-level group holding the byte. */
        public static final String GROUP_NAME = "ACCT-UPDATE-SCREEN-DATA";

        /**
         * The two values {@code 88 ACUP-DETAILS-NOT-FETCHED} covers, verbatim from
         * {@code app/cbl/COACTUPC.cbl:656-658}. A {@link List#of} list, so the constant is immutable.
         */
        public static final List<String> DETAILS_NOT_FETCHED_VALUES = List.of(LOW_VALUES, SPACES);

        /**
         * The <strong>five</strong> values {@code 88 ACUP-CHANGES-MADE} covers, verbatim from
         * {@code app/cbl/COACTUPC.cbl:660-662}.
         */
        public static final List<String> CHANGES_MADE_VALUES = List.of(CHANGES_NOT_OK,
                CHANGES_OK_NOT_CONFIRMED,
                CHANGES_OKAYED_AND_DONE,
                CHANGES_OKAYED_LOCK_ERROR,
                CHANGES_OKAYED_BUT_FAILED);

        /**
         * The two values {@code 88 ACUP-CHANGES-FAILED} covers, verbatim from
         * {@code app/cbl/COACTUPC.cbl:666}.
         */
        public static final List<String> CHANGES_FAILED_VALUES =
                List.of(CHANGES_OKAYED_LOCK_ERROR, CHANGES_OKAYED_BUT_FAILED);

        /**
         * Validates the byte.
         *
         * @throws NullPointerException     if {@code value} is {@code null}
         * @throws IllegalArgumentException if {@code value} is not exactly one character
         */
        public ChangeAction {
            Objects.requireNonNull(value, "A change-action byte is required; ACUP-CHANGE-ACTION is PIC "
                    + "X(1) with VALUE LOW-VALUES, so its unset state is x'00' and never a Java null");
            if (value.length() != RECORD_LENGTH) {
                throw new IllegalArgumentException("ACUP-CHANGE-ACTION is PIC X(1) and holds exactly one "
                        + "character, but was given " + value.length());
            }
        }

        /**
         * The declared initial state, {@code VALUE LOW-VALUES} - what
         * {@code INITIALIZE WS-THIS-PROGCOMMAREA} and
         * {@code SET ACUP-DETAILS-NOT-FETCHED TO TRUE} ({@code app/cbl/COACTUPC.cbl:886}) leave behind.
         *
         * @return a state satisfying {@link #isDetailsNotFetched()}
         */
        public static ChangeAction initial() {
            return new ChangeAction(LOW_VALUES);
        }

        /**
         * The other state satisfying {@code ACUP-DETAILS-NOT-FETCHED}: {@code SPACES}. Distinct from
         * {@link #initial()} at the byte level, and both are kept reachable because a field-by-field diff
         * tells them apart.
         *
         * @return a state satisfying {@link #isDetailsNotFetched()}
         */
        public static ChangeAction spacesState() {
            return new ChangeAction(SPACES);
        }

        /**
         * The {@code 'S'} state, which is what {@code SET ACUP-SHOW-DETAILS TO TRUE} sets.
         *
         * @return the show-details state
         */
        public static ChangeAction showDetails() {
            return new ChangeAction(SHOW_DETAILS);
        }

        /**
         * The {@code 'E'} state, which is what {@code SET ACUP-CHANGES-NOT-OK TO TRUE} sets.
         *
         * @return the changes-not-ok state
         */
        public static ChangeAction changesNotOk() {
            return new ChangeAction(CHANGES_NOT_OK);
        }

        /**
         * The {@code 'N'} state, which is what {@code SET ACUP-CHANGES-OK-NOT-CONFIRMED TO TRUE} sets.
         *
         * @return the changes-ok-not-confirmed state
         */
        public static ChangeAction changesOkNotConfirmed() {
            return new ChangeAction(CHANGES_OK_NOT_CONFIRMED);
        }

        /**
         * The {@code 'C'} state, which is what {@code SET ACUP-CHANGES-OKAYED-AND-DONE TO TRUE} sets.
         *
         * @return the changes-okayed-and-done state
         */
        public static ChangeAction changesOkayedAndDone() {
            return new ChangeAction(CHANGES_OKAYED_AND_DONE);
        }

        /**
         * The {@code 'L'} state, which is what {@code SET ACUP-CHANGES-OKAYED-LOCK-ERROR TO TRUE} sets.
         *
         * @return the lock-error state
         */
        public static ChangeAction changesOkayedLockError() {
            return new ChangeAction(CHANGES_OKAYED_LOCK_ERROR);
        }

        /**
         * The {@code 'F'} state, which is what {@code SET ACUP-CHANGES-OKAYED-BUT-FAILED TO TRUE} sets.
         *
         * @return the okayed-but-failed state
         */
        public static ChangeAction changesOkayedButFailed() {
            return new ChangeAction(CHANGES_OKAYED_BUT_FAILED);
        }

        /**
         * Wraps an arbitrary byte, including one no {@code 88}-level covers, so the {@code WHEN OTHER}
         * path of the decision logic stays reachable.
         *
         * @param value exactly one character
         * @return the state
         * @throws NullPointerException     if {@code value} is {@code null}
         * @throws IllegalArgumentException if {@code value} is not exactly one character
         */
        public static ChangeAction of(String value) {
            return new ChangeAction(value);
        }

        /**
         * Wraps an arbitrary byte given as a {@code char}.
         *
         * @param value the byte
         * @return the state
         */
        public static ChangeAction of(char value) {
            return new ChangeAction(String.valueOf(value));
        }

        /**
         * {@code 88 ACUP-DETAILS-NOT-FETCHED VALUES LOW-VALUES, SPACES} - condition 1 of 9.
         *
         * @return {@code true} for {@code x'00'} and for a space, and for nothing else
         */
        @JsonIgnore
        public boolean isDetailsNotFetched() {
            return DETAILS_NOT_FETCHED_VALUES.contains(value);
        }

        /**
         * {@code 88 ACUP-SHOW-DETAILS VALUE 'S'} - condition 2 of 9.
         *
         * @return {@code true} for {@code 'S'}
         */
        @JsonIgnore
        public boolean isShowDetails() {
            return SHOW_DETAILS.equals(value);
        }

        /**
         * {@code 88 ACUP-CHANGES-MADE VALUES 'E','N','C','L','F'} - condition 3 of 9, and the first of the
         * two grouping levels. It overlaps {@link #isChangesNotOk()},
         * {@link #isChangesOkNotConfirmed()}, {@link #isChangesOkayedAndDone()},
         * {@link #isChangesOkayedLockError()} and {@link #isChangesOkayedButFailed()} by design, so a
         * caller must test the specific conditions in the source's order rather than assuming they are
         * disjoint.
         *
         * @return {@code true} for any of the five values
         */
        @JsonIgnore
        public boolean isChangesMade() {
            return CHANGES_MADE_VALUES.contains(value);
        }

        /**
         * {@code 88 ACUP-CHANGES-NOT-OK VALUE 'E'} - condition 4 of 9.
         *
         * @return {@code true} for {@code 'E'}
         */
        @JsonIgnore
        public boolean isChangesNotOk() {
            return CHANGES_NOT_OK.equals(value);
        }

        /**
         * {@code 88 ACUP-CHANGES-OK-NOT-CONFIRMED VALUE 'N'} - condition 5 of 9, and the state in which
         * {@code app/cbl/COACTUPC.cbl:908} admits {@code F5}.
         *
         * @return {@code true} for {@code 'N'}
         */
        @JsonIgnore
        public boolean isChangesOkNotConfirmed() {
            return CHANGES_OK_NOT_CONFIRMED.equals(value);
        }

        /**
         * {@code 88 ACUP-CHANGES-OKAYED-AND-DONE VALUE 'C'} - condition 6 of 9, and the first arm of the
         * pair at {@code app/cbl/COACTUPC.cbl:979-980} that resets the work area for a fresh search.
         *
         * @return {@code true} for {@code 'C'}
         */
        @JsonIgnore
        public boolean isChangesOkayedAndDone() {
            return CHANGES_OKAYED_AND_DONE.equals(value);
        }

        /**
         * {@code 88 ACUP-CHANGES-FAILED VALUES 'L','F'} - condition 7 of 9, the second grouping level, and
         * the second arm of that pair.
         *
         * @return {@code true} for {@code 'L'} and for {@code 'F'}
         */
        @JsonIgnore
        public boolean isChangesFailed() {
            return CHANGES_FAILED_VALUES.contains(value);
        }

        /**
         * {@code 88 ACUP-CHANGES-OKAYED-LOCK-ERROR VALUE 'L'} - condition 8 of 9: the rewrite was
         * authorised but the record had changed underneath it.
         *
         * @return {@code true} for {@code 'L'}
         */
        @JsonIgnore
        public boolean isChangesOkayedLockError() {
            return CHANGES_OKAYED_LOCK_ERROR.equals(value);
        }

        /**
         * {@code 88 ACUP-CHANGES-OKAYED-BUT-FAILED VALUE 'F'} - condition 9 of 9.
         *
         * @return {@code true} for {@code 'F'}
         */
        @JsonIgnore
        public boolean isChangesOkayedButFailed() {
            return CHANGES_OKAYED_BUT_FAILED.equals(value);
        }

        /**
         * Whether this byte satisfies none of the nine conditions, which is the {@code WHEN OTHER} case.
         *
         * @return {@code true} when no {@code 88}-level covers the byte
         */
        @JsonIgnore
        public boolean isUnrecognised() {
            return !isDetailsNotFetched() && !isShowDetails() && !isChangesMade();
        }

        /**
         * A rendering that names the byte readably, since {@code LOW-VALUES} and a space are both
         * invisible in a failure message.
         *
         * @return the condition name where one applies, otherwise the quoted byte
         */
        @Override
        public String toString() {
            String named = switch (value) {
                case LOW_VALUES -> "LOW-VALUES";
                case SPACES -> "SPACES";
                case SHOW_DETAILS -> "ACUP-SHOW-DETAILS";
                case CHANGES_NOT_OK -> "ACUP-CHANGES-NOT-OK";
                case CHANGES_OK_NOT_CONFIRMED -> "ACUP-CHANGES-OK-NOT-CONFIRMED";
                case CHANGES_OKAYED_AND_DONE -> "ACUP-CHANGES-OKAYED-AND-DONE";
                case CHANGES_OKAYED_LOCK_ERROR -> "ACUP-CHANGES-OKAYED-LOCK-ERROR";
                case CHANGES_OKAYED_BUT_FAILED -> "ACUP-CHANGES-OKAYED-BUT-FAILED";
                default -> "'" + value + "'";
            };
            return FIELD_NAME + "=" + named;
        }
    }

    /**
     * {@code 10 ACUP-xxx-ACCT-DATA} - the {@value #RECORD_LENGTH}-byte account half of a detail group,
     * {@code app/cbl/COACTUPC.cbl:670-708} for {@code OLD} and {@code :758-796} for {@code NEW}.
     *
     * <p>Eleven items of storage and nine {@code REDEFINES} overlays over them. Every overlay is exposed as
     * a <strong>second typed accessor over the same span</strong> rather than as a second field, which is
     * what a {@code REDEFINES} means and what gate <strong>G34</strong> asks for: writing through one view
     * is visible through the other because there is only one set of bytes.
     *
     * <pre>
     *   offset  width  item                                    overlay
     *   ------  -----  --------------------------------------  ----------------------------------------
     *        0     11  ACUP-xxx-ACCT-ID-X          PIC X(11)   ACUP-xxx-ACCT-ID          PIC 9(11)
     *       11      1  ACUP-xxx-ACTIVE-STATUS      PIC X(01)   -
     *       12     12  ACUP-xxx-CURR-BAL           PIC X(12)   ACUP-xxx-CURR-BAL-N       PIC S9(10)V99
     *       24     12  ACUP-xxx-CREDIT-LIMIT       PIC X(12)   ACUP-xxx-CREDIT-LIMIT-N   PIC S9(10)V99
     *       36     12  ACUP-xxx-CASH-CREDIT-LIMIT  PIC X(12)   ...-CASH-CREDIT-LIMIT-N   PIC S9(10)V99
     *       48      8  ACUP-xxx-OPEN-DATE          PIC X(08)   ...-OPEN-DATE-PARTS  4/2/2
     *       56      8  ACUP-xxx-EXPIRAION-DATE     PIC X(08)   ...-EXPIRAION-DATE-PARTS  4/2/2
     *       64      8  ACUP-xxx-REISSUE-DATE       PIC X(08)   ...-REISSUE-DATE-PARTS    4/2/2
     *       72     12  ACUP-xxx-CURR-CYC-CREDIT    PIC X(12)   ...-CURR-CYC-CREDIT-N     PIC S9(10)V99
     *       84     12  ACUP-xxx-CURR-CYC-DEBIT     PIC X(12)   ...-CURR-CYC-DEBIT-N      PIC S9(10)V99
     *       96     10  ACUP-xxx-GROUP-ID           PIC X(10)   -
     *   ------  -----
     *              106  = {@value #RECORD_LENGTH}
     * </pre>
     *
     * <p><strong>{@code EXPIRAION} is spelled exactly as the source spells it.</strong>
     * {@code app/cbl/COACTUPC.cbl:690} declares {@code ACUP-OLD-EXPIRAION-DATE} and {@code :778} declares
     * {@code ACUP-NEW-EXPIRAION-DATE}, mirroring the misspelled {@code ACCT-EXPIRAION-DATE} of
     * {@code app/cpy/CVACT01Y.cpy}. Correcting it here would rename an item a field-by-field diff compares
     * by name and make every expiry-date comparison silently miss (practice <strong>B5</strong>).
     *
     * <p><strong>The three dates are eight bytes, unseparated.</strong> Their record counterparts are ten
     * and do carry separators, which is why {@code 9700-CHECK-CHANGE-IN-REC} compares
     * {@code ACCT-OPEN-DATE(1:4)}, {@code (6:2)} and {@code (9:2)} against
     * {@code ACUP-OLD-OPEN-YEAR}, {@code -MON} and {@code -DAY}: the offsets differ because the widths do.
     * Normalising a snapshot date to ten bytes breaks the comparison.
     *
     * <p>The five money items are {@code PIC X(12)} with a {@code PIC S9(10)V99} overlay, so twelve bytes
     * carry ten integer digits and two fraction digits with the sign <strong>overpunched into the trailing
     * byte</strong> - no byte is reserved for it, which is what makes twelve the right width. The numeric
     * views go through {@link CobolDecimal#MONETARY_SCALE} and truncate, never round
     * (gates <strong>G22</strong> and <strong>G24</strong>).
     *
     * @param acctIdX         {@code ACUP-xxx-ACCT-ID-X PIC X(11)}
     * @param activeStatus    {@code ACUP-xxx-ACTIVE-STATUS PIC X(01)}
     * @param currBal         {@code ACUP-xxx-CURR-BAL PIC X(12)}
     * @param creditLimit     {@code ACUP-xxx-CREDIT-LIMIT PIC X(12)}
     * @param cashCreditLimit {@code ACUP-xxx-CASH-CREDIT-LIMIT PIC X(12)}
     * @param openDate        {@code ACUP-xxx-OPEN-DATE PIC X(08)}, unseparated
     * @param expiraionDate   {@code ACUP-xxx-EXPIRAION-DATE PIC X(08)}, unseparated, misspelling intact
     * @param reissueDate     {@code ACUP-xxx-REISSUE-DATE PIC X(08)}, unseparated
     * @param currCycCredit   {@code ACUP-xxx-CURR-CYC-CREDIT PIC X(12)}
     * @param currCycDebit    {@code ACUP-xxx-CURR-CYC-DEBIT PIC X(12)}
     * @param groupId         {@code ACUP-xxx-GROUP-ID PIC X(10)}
     */
    public record AcctSnapshot(String acctIdX,
                               String activeStatus,
                               String currBal,
                               String creditLimit,
                               String cashCreditLimit,
                               String openDate,
                               String expiraionDate,
                               String reissueDate,
                               String currCycCredit,
                               String currCycDebit,
                               String groupId) {

        /** Total width: {@code 11+1+12+12+12+8+8+8+12+12+10}. */
        public static final int RECORD_LENGTH = 106;

        /** Absolute 0-based offset of {@code ACUP-xxx-ACCT-ID-X} within the group. */
        public static final int ACCT_ID_OFFSET = 0;

        /** Declared width of {@code ACUP-xxx-ACCT-ID-X PIC X(11)} and of its {@code PIC 9(11)} overlay. */
        public static final int ACCT_ID_LENGTH = 11;

        /** Absolute 0-based offset of {@code ACUP-xxx-ACTIVE-STATUS}. */
        public static final int ACTIVE_STATUS_OFFSET = 11;

        /** Declared width of {@code ACUP-xxx-ACTIVE-STATUS PIC X(01)}. */
        public static final int ACTIVE_STATUS_LENGTH = 1;

        /** Absolute 0-based offset of {@code ACUP-xxx-CURR-BAL}. */
        public static final int CURR_BAL_OFFSET = 12;

        /** Absolute 0-based offset of {@code ACUP-xxx-CREDIT-LIMIT}. */
        public static final int CREDIT_LIMIT_OFFSET = 24;

        /** Absolute 0-based offset of {@code ACUP-xxx-CASH-CREDIT-LIMIT}. */
        public static final int CASH_CREDIT_LIMIT_OFFSET = 36;

        /** Absolute 0-based offset of {@code ACUP-xxx-OPEN-DATE}. */
        public static final int OPEN_DATE_OFFSET = 48;

        /** Absolute 0-based offset of {@code ACUP-xxx-EXPIRAION-DATE}, spelled as the source spells it. */
        public static final int EXPIRAION_DATE_OFFSET = 56;

        /** Absolute 0-based offset of {@code ACUP-xxx-REISSUE-DATE}. */
        public static final int REISSUE_DATE_OFFSET = 64;

        /** Absolute 0-based offset of {@code ACUP-xxx-CURR-CYC-CREDIT}. */
        public static final int CURR_CYC_CREDIT_OFFSET = 72;

        /** Absolute 0-based offset of {@code ACUP-xxx-CURR-CYC-DEBIT}. */
        public static final int CURR_CYC_DEBIT_OFFSET = 84;

        /** Absolute 0-based offset of {@code ACUP-xxx-GROUP-ID}. */
        public static final int GROUP_ID_OFFSET = 96;

        /** Declared width of {@code ACUP-xxx-GROUP-ID PIC X(10)}. */
        public static final int GROUP_ID_LENGTH = 10;

        /**
         * Declared width of each of the five money items: {@code PIC X(12)}, and equally the twelve bytes
         * a {@code PIC S9(10)V99} overlay occupies once the sign is overpunched rather than given a byte.
         */
        public static final int MONEY_LENGTH = 12;

        /** Integer digit positions of the {@code PIC S9(10)V99} overlays. */
        public static final int MONEY_INTEGER_DIGITS = 10;

        /**
         * Declared width of each of the three dates: {@code PIC X(08)}, <strong>unseparated</strong>,
         * against a ten-byte separated counterpart in the account record.
         */
        public static final int DATE_LENGTH = 8;

        /** Width of the year part of a date overlay. */
        public static final int DATE_YEAR_LENGTH = 4;

        /** Width of the month part of a date overlay. */
        public static final int DATE_MONTH_LENGTH = 2;

        /** Width of the day part of a date overlay. */
        public static final int DATE_DAY_LENGTH = 2;

        /**
         * Normalises and checks every item, so a snapshot can never exist in a shape the work area cannot
         * hold.
         *
         * <p>Every item ends up at exactly its declared width. A {@code null} becomes that item's width in
         * spaces, because COBOL has no {@code null}; a short value is space-padded, because a COBOL group
         * item occupies its declared bytes at every instant and {@code MOVE 'DEFAULT' TO
         * ACUP-xxx-GROUP-ID} stores {@code 'DEFAULT   '}; and an over-wide value is
         * <strong>rejected</strong> rather than truncated, because silent truncation inside the work area
         * would corrupt {@code 9700-CHECK-CHANGE-IN-REC}'s comparison and the caller is better served by
         * being told which item does not fit.
         *
         * <p>That the width is normalised here rather than at the encode is what makes
         * {@link Details#decode(byte[], DetailGroup, FixedWidthCodec)} of an
         * {@link Details#encode(FixedWidthCodec)} equal the original, and what stops
         * {@code AccountUpdateService} from having to pad before every comparison.
         *
         * @throws IllegalArgumentException if any value exceeds its declared width
         */
        public AcctSnapshot {
            acctIdX = fitAcct("ACCT-ID-X", acctIdX, ACCT_ID_LENGTH);
            activeStatus = fitAcct("ACTIVE-STATUS", activeStatus, ACTIVE_STATUS_LENGTH);
            currBal = fitAcct("CURR-BAL", currBal, MONEY_LENGTH);
            creditLimit = fitAcct("CREDIT-LIMIT", creditLimit, MONEY_LENGTH);
            cashCreditLimit = fitAcct("CASH-CREDIT-LIMIT", cashCreditLimit, MONEY_LENGTH);
            openDate = fitAcct("OPEN-DATE", openDate, DATE_LENGTH);
            expiraionDate = fitAcct("EXPIRAION-DATE", expiraionDate, DATE_LENGTH);
            reissueDate = fitAcct("REISSUE-DATE", reissueDate, DATE_LENGTH);
            currCycCredit = fitAcct("CURR-CYC-CREDIT", currCycCredit, MONEY_LENGTH);
            currCycDebit = fitAcct("CURR-CYC-DEBIT", currCycDebit, MONEY_LENGTH);
            groupId = fitAcct("GROUP-ID", groupId, GROUP_ID_LENGTH);
        }

        /**
         * A snapshot in which every item holds its declared width in spaces - the state
         * {@code INITIALIZE} leaves an all-{@code PIC X} group in.
         *
         * @return the all-spaces snapshot
         */
        public static AcctSnapshot initialised() {
            return new AcctSnapshot(null, null, null, null, null, null, null, null, null, null, null);
        }

        /**
         * {@code ACUP-xxx-ACCT-ID REDEFINES ACUP-xxx-ACCT-ID-X PIC 9(11)} - the same eleven bytes seen as
         * an unsigned integer.
         *
         * <p>A byte reinterpretation, not a parse: the span is first brought to its declared width by the
         * {@code PIC X} move rule and then decoded by {@link FixedWidthCodec#decodePic9(String)}, which
         * owns the zoned-digit alphabet. {@code long} rather than {@code int} because eleven digits
         * overflow an {@code int}, and the picture carries neither scale nor sign so no fixed-point type
         * takes part.
         *
         * @return {@code 0} when the span holds {@code LOW-VALUES}, spaces or zeros - all three of which
         *         {@code app/cbl/COACTUPC.cbl:1053} can put there - and otherwise the value its digits
         *         denote
         * @throws IllegalArgumentException if the span holds neither digits nor one of those three states
         */
        @JsonIgnore
        public long acctId() {
            return numericView(acctIdX, ACCT_ID_LENGTH);
        }

        /**
         * {@code ACUP-xxx-CURR-BAL-N REDEFINES ACUP-xxx-CURR-BAL PIC S9(10)V99} - the same twelve bytes
         * seen as a signed decimal of scale {@link CobolDecimal#MONETARY_SCALE}.
         *
         * @return the balance at scale 2, truncated never rounded; {@code 0.00} for an unset span
         */
        @JsonIgnore
        public BigDecimal currBalN() {
            return monetaryView(currBal);
        }

        /**
         * {@code ACUP-xxx-CREDIT-LIMIT-N REDEFINES ACUP-xxx-CREDIT-LIMIT PIC S9(10)V99}.
         *
         * @return the credit limit at scale 2
         */
        @JsonIgnore
        public BigDecimal creditLimitN() {
            return monetaryView(creditLimit);
        }

        /**
         * {@code ACUP-xxx-CASH-CREDIT-LIMIT-N REDEFINES ACUP-xxx-CASH-CREDIT-LIMIT PIC S9(10)V99}.
         *
         * @return the cash credit limit at scale 2
         */
        @JsonIgnore
        public BigDecimal cashCreditLimitN() {
            return monetaryView(cashCreditLimit);
        }

        /**
         * {@code ACUP-xxx-CURR-CYC-CREDIT-N REDEFINES ACUP-xxx-CURR-CYC-CREDIT PIC S9(10)V99}.
         *
         * @return the current cycle credit at scale 2
         */
        @JsonIgnore
        public BigDecimal currCycCreditN() {
            return monetaryView(currCycCredit);
        }

        /**
         * {@code ACUP-xxx-CURR-CYC-DEBIT-N REDEFINES ACUP-xxx-CURR-CYC-DEBIT PIC S9(10)V99}.
         *
         * @return the current cycle debit at scale 2
         */
        @JsonIgnore
        public BigDecimal currCycDebitN() {
            return monetaryView(currCycDebit);
        }

        /**
         * {@code ACUP-xxx-OPEN-YEAR PIC X(4)}, the first part of
         * {@code ACUP-xxx-OPEN-DATE-PARTS REDEFINES ACUP-xxx-OPEN-DATE}. Compared against
         * {@code ACCT-OPEN-DATE(1:4)} at {@code app/cbl/COACTUPC.cbl:4127}.
         *
         * @return four characters of the open date
         */
        @JsonIgnore
        public String openYear() {
            return datePart(openDate, 0, DATE_YEAR_LENGTH);
        }

        /**
         * {@code ACUP-xxx-OPEN-MON PIC X(2)}. Compared against {@code ACCT-OPEN-DATE(6:2)} at
         * {@code :4128} - position 6 in the ten-byte separated record field, position 5 in this eight-byte
         * unseparated one.
         *
         * @return two characters of the open date
         */
        @JsonIgnore
        public String openMon() {
            return datePart(openDate, DATE_YEAR_LENGTH, DATE_MONTH_LENGTH);
        }

        /**
         * {@code ACUP-xxx-OPEN-DAY PIC X(2)}. Compared against {@code ACCT-OPEN-DATE(9:2)} at
         * {@code :4129}.
         *
         * @return two characters of the open date
         */
        @JsonIgnore
        public String openDay() {
            return datePart(openDate, DATE_YEAR_LENGTH + DATE_MONTH_LENGTH, DATE_DAY_LENGTH);
        }

        /**
         * {@code ACUP-xxx-EXP-YEAR PIC X(4)}, the first part of
         * {@code ACUP-xxx-EXPIRAION-DATE-PARTS} - note that the parts drop to {@code EXP-} while the group
         * keeps the misspelled {@code EXPIRAION}. Compared against {@code ACCT-EXPIRAION-DATE(1:4)} at
         * {@code app/cbl/COACTUPC.cbl:4131}.
         *
         * @return four characters of the expiry date
         */
        @JsonIgnore
        public String expYear() {
            return datePart(expiraionDate, 0, DATE_YEAR_LENGTH);
        }

        /**
         * {@code ACUP-xxx-EXP-MON PIC X(2)}, compared against {@code ACCT-EXPIRAION-DATE(6:2)} at
         * {@code :4132}.
         *
         * @return two characters of the expiry date
         */
        @JsonIgnore
        public String expMon() {
            return datePart(expiraionDate, DATE_YEAR_LENGTH, DATE_MONTH_LENGTH);
        }

        /**
         * {@code ACUP-xxx-EXP-DAY PIC X(2)}, compared against {@code ACCT-EXPIRAION-DATE(9:2)} at
         * {@code :4133}.
         *
         * @return two characters of the expiry date
         */
        @JsonIgnore
        public String expDay() {
            return datePart(expiraionDate, DATE_YEAR_LENGTH + DATE_MONTH_LENGTH, DATE_DAY_LENGTH);
        }

        /**
         * {@code ACUP-xxx-REISSUE-YEAR PIC X(4)}, compared against {@code ACCT-REISSUE-DATE(1:4)} at
         * {@code app/cbl/COACTUPC.cbl:4135}.
         *
         * @return four characters of the reissue date
         */
        @JsonIgnore
        public String reissueYear() {
            return datePart(reissueDate, 0, DATE_YEAR_LENGTH);
        }

        /**
         * {@code ACUP-xxx-REISSUE-MON PIC X(2)}, compared against {@code ACCT-REISSUE-DATE(6:2)} at
         * {@code :4136}.
         *
         * @return two characters of the reissue date
         */
        @JsonIgnore
        public String reissueMon() {
            return datePart(reissueDate, DATE_YEAR_LENGTH, DATE_MONTH_LENGTH);
        }

        /**
         * {@code ACUP-xxx-REISSUE-DAY PIC X(2)}, compared against {@code ACCT-REISSUE-DATE(9:2)} at
         * {@code :4137}.
         *
         * @return two characters of the reissue date
         */
        @JsonIgnore
        public String reissueDay() {
            return datePart(reissueDate, DATE_YEAR_LENGTH + DATE_MONTH_LENGTH, DATE_DAY_LENGTH);
        }

        /**
         * Writes this snapshot into a record area.
         *
         * <p>Each write goes through {@link FixedWidthRecord#writeString(int, int, String)}, which applies
         * the {@code PIC X} alignment for the span it is given, so a short value is padded on the right
         * exactly as a COBOL alphanumeric {@code MOVE} pads it. Which group's item names these bytes carry
         * is settled by the layout the caller wrapped the area with; the geometry is identical either way,
         * which is why one writer serves both groups.
         *
         * @param area the {@value Details#RECORD_LENGTH}-byte detail area, or a
         *             {@value #RECORD_LENGTH}-byte area of this group alone
         * @param base the offset within {@code area} at which this group begins
         */
        private void writeInto(FixedWidthRecord area, int base) {
            area.writeString(base + ACCT_ID_OFFSET, ACCT_ID_LENGTH, acctIdX);
            area.writeString(base + ACTIVE_STATUS_OFFSET, ACTIVE_STATUS_LENGTH, activeStatus);
            area.writeString(base + CURR_BAL_OFFSET, MONEY_LENGTH, currBal);
            area.writeString(base + CREDIT_LIMIT_OFFSET, MONEY_LENGTH, creditLimit);
            area.writeString(base + CASH_CREDIT_LIMIT_OFFSET, MONEY_LENGTH, cashCreditLimit);
            area.writeString(base + OPEN_DATE_OFFSET, DATE_LENGTH, openDate);
            area.writeString(base + EXPIRAION_DATE_OFFSET, DATE_LENGTH, expiraionDate);
            area.writeString(base + REISSUE_DATE_OFFSET, DATE_LENGTH, reissueDate);
            area.writeString(base + CURR_CYC_CREDIT_OFFSET, MONEY_LENGTH, currCycCredit);
            area.writeString(base + CURR_CYC_DEBIT_OFFSET, MONEY_LENGTH, currCycDebit);
            area.writeString(base + GROUP_ID_OFFSET, GROUP_ID_LENGTH, groupId);
        }

        /**
         * Reads a group's account half out of a record area.
         *
         * @param area  the record area
         * @param base  the offset within {@code area} at which the group begins
         * @return the snapshot, values raw and untrimmed
         */
        private static AcctSnapshot readFrom(FixedWidthRecord area, int base) {
            return new AcctSnapshot(area.readString(base + ACCT_ID_OFFSET, ACCT_ID_LENGTH),
                    area.readString(base + ACTIVE_STATUS_OFFSET, ACTIVE_STATUS_LENGTH),
                    area.readString(base + CURR_BAL_OFFSET, MONEY_LENGTH),
                    area.readString(base + CREDIT_LIMIT_OFFSET, MONEY_LENGTH),
                    area.readString(base + CASH_CREDIT_LIMIT_OFFSET, MONEY_LENGTH),
                    area.readString(base + OPEN_DATE_OFFSET, DATE_LENGTH),
                    area.readString(base + EXPIRAION_DATE_OFFSET, DATE_LENGTH),
                    area.readString(base + REISSUE_DATE_OFFSET, DATE_LENGTH),
                    area.readString(base + CURR_CYC_CREDIT_OFFSET, MONEY_LENGTH),
                    area.readString(base + CURR_CYC_DEBIT_OFFSET, MONEY_LENGTH),
                    area.readString(base + GROUP_ID_OFFSET, GROUP_ID_LENGTH));
        }
    }

    /**
     * {@code 10 ACUP-xxx-CUST-DATA} - the {@value #RECORD_LENGTH}-byte customer half of a detail group,
     * {@code app/cbl/COACTUPC.cbl:709-756} for {@code OLD} and {@code :797-849} for {@code NEW}.
     *
     * <p>Eighteen items of storage and, depending on the group, seven or nine overlays over them:
     *
     * <pre>
     *   offset  width  item                                       overlay
     *   ------  -----  -----------------------------------------  -------------------------------------
     *        0      9  ACUP-xxx-CUST-ID-X            PIC X(09)    ACUP-xxx-CUST-ID       PIC 9(09)
     *        9     25  ACUP-xxx-CUST-FIRST-NAME      PIC X(25)    -
     *       34     25  ACUP-xxx-CUST-MIDDLE-NAME     PIC X(25)    -
     *       59     25  ACUP-xxx-CUST-LAST-NAME       PIC X(25)    -
     *       84     50  ACUP-xxx-CUST-ADDR-LINE-1     PIC X(50)    -
     *      134     50  ACUP-xxx-CUST-ADDR-LINE-2     PIC X(50)    -
     *      184     50  ACUP-xxx-CUST-ADDR-LINE-3     PIC X(50)    -
     *      234      2  ACUP-xxx-CUST-ADDR-STATE-CD   PIC X(02)    -
     *      236      3  ACUP-xxx-CUST-ADDR-COUNTRY-CD PIC X(03)    -
     *      239     10  ACUP-xxx-CUST-ADDR-ZIP        PIC X(10)    -
     *      249     15  ACUP-xxx-CUST-PHONE-NUM-1     PIC X(15)    ...-PHONE-NUM-1-X, 7 sub-items
     *      264     15  ACUP-xxx-CUST-PHONE-NUM-2     PIC X(15)    ...-PHONE-NUM-2-X, 7 sub-items
     *      279      9  ACUP-xxx-CUST-SSN-X           see below    ACUP-xxx-CUST-SSN      PIC 9(09)
     *      288     20  ACUP-xxx-CUST-GOVT-ISSUED-ID  PIC X(20)    -
     *      308      8  ACUP-xxx-CUST-DOB-YYYY-MM-DD  PIC X(08)    ...-CUST-DOB-PARTS  4/2/2
     *      316     10  ACUP-xxx-CUST-EFT-ACCOUNT-ID  PIC X(10)    -
     *      326      1  ACUP-xxx-CUST-PRI-HOLDER-IND  PIC X(01)    -
     *      327      3  ACUP-xxx-CUST-FICO-SCORE-X    PIC X(03)    ...-CUST-FICO-SCORE    PIC 9(03)
     *   ------  -----
     *              330  = {@value #RECORD_LENGTH}
     * </pre>
     *
     * <h2>The telephone overlays keep their {@code FILLER}</h2>
     *
     * <p>{@code ACUP-xxx-CUST-PHONE-NUM-1-X REDEFINES ACUP-xxx-CUST-PHONE-NUM-1}
     * ({@code app/cbl/COACTUPC.cbl:723-731}) divides fifteen bytes into seven sub-items, four of which are
     * {@code FILLER}:
     *
     * <pre>
     *   FILLER                        PIC X(1)   offset 249   the opening punctuation
     *   ACUP-xxx-CUST-PHONE-NUM-1A    PIC X(3)   offset 250   the area code
     *   FILLER                        PIC X(1)   offset 253
     *   ACUP-xxx-CUST-PHONE-NUM-1B    PIC X(3)   offset 254   the prefix
     *   FILLER                        PIC X(1)   offset 257
     *   ACUP-xxx-CUST-PHONE-NUM-1C    PIC X(4)   offset 258   the line number
     *   FILLER                        PIC X(2)   offset 262
     * </pre>
     *
     * <p>1 + 3 + 1 + 3 + 1 + 4 + 2 = 15, and every one of those {@code FILLER} spans is declared as a
     * first-class span in {@link DetailGroup}'s layout rather than skipped (gate <strong>G21</strong>).
     * Dropping any one of them would shift the three named parts and, through them, every item after the
     * telephone. The second telephone repeats the shape exactly with {@code 2A}, {@code 2B} and {@code 2C}
     * ({@code :733-741}).
     *
     * <h2>The date of birth is eight bytes, and its comparison is asymmetric</h2>
     *
     * <p>{@code ACUP-xxx-CUST-DOB-YYYY-MM-DD} is {@code PIC X(08)}, unseparated, while the record's
     * {@code CUST-DOB-YYYY-MM-DD} is {@code PIC X(10)} and carries hyphens in the data as well as in the
     * name. That is why {@code 9700-CHECK-CHANGE-IN-REC} compares {@code (1:4)} with {@code (1:4)},
     * {@code (6:2)} with {@code (5:2)} and {@code (9:2)} with {@code (7:2)}
     * ({@code app/cbl/COACTUPC.cbl:4174-4179}) - the only asymmetric comparison in the paragraph, and
     * asymmetric precisely because the two widths differ.
     *
     * @param custIdX       {@code ACUP-xxx-CUST-ID-X PIC X(09)}
     * @param firstName     {@code ACUP-xxx-CUST-FIRST-NAME PIC X(25)}
     * @param middleName    {@code ACUP-xxx-CUST-MIDDLE-NAME PIC X(25)}
     * @param lastName      {@code ACUP-xxx-CUST-LAST-NAME PIC X(25)}
     * @param addrLine1     {@code ACUP-xxx-CUST-ADDR-LINE-1 PIC X(50)}
     * @param addrLine2     {@code ACUP-xxx-CUST-ADDR-LINE-2 PIC X(50)}
     * @param addrLine3     {@code ACUP-xxx-CUST-ADDR-LINE-3 PIC X(50)} - the city on the screen
     * @param addrStateCd   {@code ACUP-xxx-CUST-ADDR-STATE-CD PIC X(02)}
     * @param addrCountryCd {@code ACUP-xxx-CUST-ADDR-COUNTRY-CD PIC X(03)}
     * @param addrZip       {@code ACUP-xxx-CUST-ADDR-ZIP PIC X(10)} - ten here, five on the screen
     * @param phoneNum1     {@code ACUP-xxx-CUST-PHONE-NUM-1 PIC X(15)}, punctuation included
     * @param phoneNum2     {@code ACUP-xxx-CUST-PHONE-NUM-2 PIC X(15)}, punctuation included
     * @param ssnX          {@code ACUP-xxx-CUST-SSN-X}, nine bytes - flat in {@code OLD}, a three-part
     *                      group in {@code NEW}
     * @param govtIssuedId  {@code ACUP-xxx-CUST-GOVT-ISSUED-ID PIC X(20)}
     * @param dobYyyyMmDd   {@code ACUP-xxx-CUST-DOB-YYYY-MM-DD PIC X(08)}, unseparated
     * @param eftAccountId  {@code ACUP-xxx-CUST-EFT-ACCOUNT-ID PIC X(10)}
     * @param priHolderInd  {@code ACUP-xxx-CUST-PRI-HOLDER-IND PIC X(01)}
     * @param ficoScoreX    {@code ACUP-xxx-CUST-FICO-SCORE-X PIC X(03)}
     */
    public record CustSnapshot(String custIdX,
                               String firstName,
                               String middleName,
                               String lastName,
                               String addrLine1,
                               String addrLine2,
                               String addrLine3,
                               String addrStateCd,
                               String addrCountryCd,
                               String addrZip,
                               String phoneNum1,
                               String phoneNum2,
                               String ssnX,
                               String govtIssuedId,
                               String dobYyyyMmDd,
                               String eftAccountId,
                               String priHolderInd,
                               String ficoScoreX) {

        /** Total width: {@code 9+25+25+25+50+50+50+2+3+10+15+15+9+20+8+10+1+3}. */
        public static final int RECORD_LENGTH = 330;

        /** Absolute 0-based offset of {@code ACUP-xxx-CUST-ID-X} within the customer group. */
        public static final int CUST_ID_OFFSET = 0;

        /** Declared width of {@code ACUP-xxx-CUST-ID-X PIC X(09)} and of its {@code PIC 9(09)} overlay. */
        public static final int CUST_ID_LENGTH = 9;

        /** Absolute 0-based offset of {@code ACUP-xxx-CUST-FIRST-NAME}. */
        public static final int FIRST_NAME_OFFSET = 9;

        /** Absolute 0-based offset of {@code ACUP-xxx-CUST-MIDDLE-NAME}. */
        public static final int MIDDLE_NAME_OFFSET = 34;

        /** Absolute 0-based offset of {@code ACUP-xxx-CUST-LAST-NAME}. */
        public static final int LAST_NAME_OFFSET = 59;

        /** Declared width of each of the three name items: {@code PIC X(25)}. */
        public static final int NAME_LENGTH = 25;

        /** Absolute 0-based offset of {@code ACUP-xxx-CUST-ADDR-LINE-1}. */
        public static final int ADDR_LINE_1_OFFSET = 84;

        /** Absolute 0-based offset of {@code ACUP-xxx-CUST-ADDR-LINE-2}. */
        public static final int ADDR_LINE_2_OFFSET = 134;

        /** Absolute 0-based offset of {@code ACUP-xxx-CUST-ADDR-LINE-3}. */
        public static final int ADDR_LINE_3_OFFSET = 184;

        /** Declared width of each of the three address lines: {@code PIC X(50)}. */
        public static final int ADDR_LINE_LENGTH = 50;

        /** Absolute 0-based offset of {@code ACUP-xxx-CUST-ADDR-STATE-CD}. */
        public static final int ADDR_STATE_CD_OFFSET = 234;

        /** Declared width of {@code ACUP-xxx-CUST-ADDR-STATE-CD PIC X(02)}. */
        public static final int ADDR_STATE_CD_LENGTH = 2;

        /** Absolute 0-based offset of {@code ACUP-xxx-CUST-ADDR-COUNTRY-CD}. */
        public static final int ADDR_COUNTRY_CD_OFFSET = 236;

        /** Declared width of {@code ACUP-xxx-CUST-ADDR-COUNTRY-CD PIC X(03)}. */
        public static final int ADDR_COUNTRY_CD_LENGTH = 3;

        /** Absolute 0-based offset of {@code ACUP-xxx-CUST-ADDR-ZIP}. */
        public static final int ADDR_ZIP_OFFSET = 239;

        /**
         * Declared width of {@code ACUP-xxx-CUST-ADDR-ZIP PIC X(10)}.
         *
         * <p>Ten, where the screen's {@code ACSZIPC} is {@value AccountUpdateRequest#ACSZIPC_LENGTH}. Both
         * are correct and neither is derived from the other.
         */
        public static final int ADDR_ZIP_LENGTH = 10;

        /** Absolute 0-based offset of {@code ACUP-xxx-CUST-PHONE-NUM-1}. */
        public static final int PHONE_NUM_1_OFFSET = 249;

        /** Absolute 0-based offset of {@code ACUP-xxx-CUST-PHONE-NUM-2}. */
        public static final int PHONE_NUM_2_OFFSET = 264;

        /** Declared width of each telephone item: {@code PIC X(15)}, punctuation included. */
        public static final int PHONE_NUM_LENGTH = 15;

        /** Offset of the area-code part within a telephone span: one {@code FILLER} byte in. */
        public static final int PHONE_AREA_CODE_RELATIVE_OFFSET = 1;

        /** Width of the area-code part, {@code ...-PHONE-NUM-nA PIC X(3)}. */
        public static final int PHONE_AREA_CODE_LENGTH = 3;

        /** Offset of the prefix part within a telephone span. */
        public static final int PHONE_PREFIX_RELATIVE_OFFSET = 5;

        /** Width of the prefix part, {@code ...-PHONE-NUM-nB PIC X(3)}. */
        public static final int PHONE_PREFIX_LENGTH = 3;

        /** Offset of the line-number part within a telephone span. */
        public static final int PHONE_LINE_NUMBER_RELATIVE_OFFSET = 9;

        /** Width of the line-number part, {@code ...-PHONE-NUM-nC PIC X(4)}. */
        public static final int PHONE_LINE_NUMBER_LENGTH = 4;

        /** Width of the leading {@code FILLER} of a telephone overlay, at relative offset 0. */
        public static final int PHONE_LEADING_FILLER_LENGTH = 1;

        /** Width of each of the two inner {@code FILLER} spans, at relative offsets 4 and 8. */
        public static final int PHONE_INNER_FILLER_LENGTH = 1;

        /** Width of the trailing {@code FILLER} of a telephone overlay, at relative offset 13. */
        public static final int PHONE_TRAILING_FILLER_LENGTH = 2;

        /** Absolute 0-based offset of {@code ACUP-xxx-CUST-SSN-X} and of its {@code PIC 9(09)} overlay. */
        public static final int SSN_OFFSET = 279;

        /** Total width of the social security number, flat or split: nine bytes either way. */
        public static final int SSN_LENGTH = 9;

        /** Width of {@code ACUP-NEW-CUST-SSN-1 PIC X(03)}, the {@code NEW} group's first SSN part. */
        public static final int SSN_PART_1_LENGTH = 3;

        /** Width of {@code ACUP-NEW-CUST-SSN-2 PIC X(02)}. */
        public static final int SSN_PART_2_LENGTH = 2;

        /** Width of {@code ACUP-NEW-CUST-SSN-3 PIC X(04)}. */
        public static final int SSN_PART_3_LENGTH = 4;

        /** Absolute 0-based offset of {@code ACUP-xxx-CUST-GOVT-ISSUED-ID}. */
        public static final int GOVT_ISSUED_ID_OFFSET = 288;

        /** Declared width of {@code ACUP-xxx-CUST-GOVT-ISSUED-ID PIC X(20)}. */
        public static final int GOVT_ISSUED_ID_LENGTH = 20;

        /** Absolute 0-based offset of {@code ACUP-xxx-CUST-DOB-YYYY-MM-DD}. */
        public static final int DOB_OFFSET = 308;

        /**
         * Declared width of {@code ACUP-xxx-CUST-DOB-YYYY-MM-DD PIC X(08)} - eight bytes,
         * <strong>unseparated</strong>, against the record's separated ten.
         */
        public static final int DOB_LENGTH = 8;

        /** Absolute 0-based offset of {@code ACUP-xxx-CUST-EFT-ACCOUNT-ID}. */
        public static final int EFT_ACCOUNT_ID_OFFSET = 316;

        /** Declared width of {@code ACUP-xxx-CUST-EFT-ACCOUNT-ID PIC X(10)}. */
        public static final int EFT_ACCOUNT_ID_LENGTH = 10;

        /** Absolute 0-based offset of {@code ACUP-xxx-CUST-PRI-HOLDER-IND}. */
        public static final int PRI_HOLDER_IND_OFFSET = 326;

        /** Declared width of {@code ACUP-xxx-CUST-PRI-HOLDER-IND PIC X(01)}. */
        public static final int PRI_HOLDER_IND_LENGTH = 1;

        /** Absolute 0-based offset of {@code ACUP-xxx-CUST-FICO-SCORE-X}. */
        public static final int FICO_SCORE_OFFSET = 327;

        /** Declared width of {@code ACUP-xxx-CUST-FICO-SCORE-X PIC X(03)} and its {@code 9(03)} overlay. */
        public static final int FICO_SCORE_LENGTH = 3;

        /**
         * Lowest score {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850} accepts,
         * {@code app/cbl/COACTUPC.cbl:848-849}.
         *
         * <p>Declared on {@code ACUP-NEW-CUST-FICO-SCORE} only. The {@code OLD} group's identical field
         * carries no such condition, which is one of the two structural asymmetries recorded on
         * {@link DetailGroup}.
         */
        public static final int FICO_RANGE_MINIMUM = 300;

        /** Highest score {@code 88 FICO-RANGE-IS-VALID} accepts. */
        public static final int FICO_RANGE_MAXIMUM = 850;

        /**
         * Normalises and checks every item.
         *
         * <p>A {@code null} becomes that item's declared width in spaces and a short value is
         * space-padded to it, so every component holds exactly its declared bytes; an over-wide value is
         * rejected rather than truncated, for the reason {@link AcctSnapshot} gives.
         *
         * @throws IllegalArgumentException if any value exceeds its declared width
         */
        public CustSnapshot {
            custIdX = fitCust("CUST-ID-X", custIdX, CUST_ID_LENGTH);
            firstName = fitCust("CUST-FIRST-NAME", firstName, NAME_LENGTH);
            middleName = fitCust("CUST-MIDDLE-NAME", middleName, NAME_LENGTH);
            lastName = fitCust("CUST-LAST-NAME", lastName, NAME_LENGTH);
            addrLine1 = fitCust("CUST-ADDR-LINE-1", addrLine1, ADDR_LINE_LENGTH);
            addrLine2 = fitCust("CUST-ADDR-LINE-2", addrLine2, ADDR_LINE_LENGTH);
            addrLine3 = fitCust("CUST-ADDR-LINE-3", addrLine3, ADDR_LINE_LENGTH);
            addrStateCd = fitCust("CUST-ADDR-STATE-CD", addrStateCd, ADDR_STATE_CD_LENGTH);
            addrCountryCd = fitCust("CUST-ADDR-COUNTRY-CD", addrCountryCd, ADDR_COUNTRY_CD_LENGTH);
            addrZip = fitCust("CUST-ADDR-ZIP", addrZip, ADDR_ZIP_LENGTH);
            phoneNum1 = fitCust("CUST-PHONE-NUM-1", phoneNum1, PHONE_NUM_LENGTH);
            phoneNum2 = fitCust("CUST-PHONE-NUM-2", phoneNum2, PHONE_NUM_LENGTH);
            ssnX = fitCust("CUST-SSN-X", ssnX, SSN_LENGTH);
            govtIssuedId = fitCust("CUST-GOVT-ISSUED-ID", govtIssuedId, GOVT_ISSUED_ID_LENGTH);
            dobYyyyMmDd = fitCust("CUST-DOB-YYYY-MM-DD", dobYyyyMmDd, DOB_LENGTH);
            eftAccountId = fitCust("CUST-EFT-ACCOUNT-ID", eftAccountId, EFT_ACCOUNT_ID_LENGTH);
            priHolderInd = fitCust("CUST-PRI-HOLDER-IND", priHolderInd, PRI_HOLDER_IND_LENGTH);
            ficoScoreX = fitCust("CUST-FICO-SCORE-X", ficoScoreX, FICO_SCORE_LENGTH);
        }

        /**
         * A snapshot in which every item holds its declared width in spaces.
         *
         * @return the all-spaces snapshot
         */
        public static CustSnapshot initialised() {
            return new CustSnapshot(null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null);
        }

        /**
         * {@code ACUP-xxx-CUST-ID REDEFINES ACUP-xxx-CUST-ID-X PIC 9(09)} - the same nine bytes as an
         * unsigned integer, a byte reinterpretation rather than a parse.
         *
         * @return {@code 0} for an unset span, otherwise the value its digits denote
         * @throws IllegalArgumentException if the span holds neither digits nor an unset state
         */
        @JsonIgnore
        public long custId() {
            return numericView(custIdX, CUST_ID_LENGTH);
        }

        /**
         * {@code ACUP-xxx-CUST-PHONE-NUM-1A PIC X(3)} - the area code, at relative offset
         * {@value #PHONE_AREA_CODE_RELATIVE_OFFSET} inside {@link #phoneNum1()} because a one-byte
         * {@code FILLER} precedes it.
         *
         * @return three characters of the first telephone number
         */
        @JsonIgnore
        public String phoneNum1A() {
            return phonePart(phoneNum1, PHONE_AREA_CODE_RELATIVE_OFFSET, PHONE_AREA_CODE_LENGTH);
        }

        /**
         * {@code ACUP-xxx-CUST-PHONE-NUM-1B PIC X(3)} - the prefix.
         *
         * @return three characters of the first telephone number
         */
        @JsonIgnore
        public String phoneNum1B() {
            return phonePart(phoneNum1, PHONE_PREFIX_RELATIVE_OFFSET, PHONE_PREFIX_LENGTH);
        }

        /**
         * {@code ACUP-xxx-CUST-PHONE-NUM-1C PIC X(4)} - the line number.
         *
         * @return four characters of the first telephone number
         */
        @JsonIgnore
        public String phoneNum1C() {
            return phonePart(phoneNum1, PHONE_LINE_NUMBER_RELATIVE_OFFSET, PHONE_LINE_NUMBER_LENGTH);
        }

        /**
         * {@code ACUP-xxx-CUST-PHONE-NUM-2A PIC X(3)} - the second telephone's area code.
         *
         * @return three characters of the second telephone number
         */
        @JsonIgnore
        public String phoneNum2A() {
            return phonePart(phoneNum2, PHONE_AREA_CODE_RELATIVE_OFFSET, PHONE_AREA_CODE_LENGTH);
        }

        /**
         * {@code ACUP-xxx-CUST-PHONE-NUM-2B PIC X(3)}.
         *
         * @return three characters of the second telephone number
         */
        @JsonIgnore
        public String phoneNum2B() {
            return phonePart(phoneNum2, PHONE_PREFIX_RELATIVE_OFFSET, PHONE_PREFIX_LENGTH);
        }

        /**
         * {@code ACUP-xxx-CUST-PHONE-NUM-2C PIC X(4)}.
         *
         * @return four characters of the second telephone number
         */
        @JsonIgnore
        public String phoneNum2C() {
            return phonePart(phoneNum2, PHONE_LINE_NUMBER_RELATIVE_OFFSET, PHONE_LINE_NUMBER_LENGTH);
        }

        /**
         * {@code ACUP-NEW-CUST-SSN-1 PIC X(03)} - the first of the three sub-items the {@code NEW} group
         * declares under {@code 15 ACUP-NEW-CUST-SSN-X} ({@code app/cbl/COACTUPC.cbl:830-833}).
         *
         * <p>Readable from either group, because the bytes are in the same place either way; whether the
         * <em>item names</em> exist is what differs, and {@link DetailGroup#declaresSsnParts()} answers
         * that. The three parts map one-to-one onto the screen's {@code ACTSSN1}, {@code ACTSSN2} and
         * {@code ACTSSN3}, which is exactly why the screen splits the number.
         *
         * @return three characters of the social security number
         */
        @JsonIgnore
        public String ssn1() {
            return substring(ssnX, SSN_LENGTH, 0, SSN_PART_1_LENGTH);
        }

        /**
         * {@code ACUP-NEW-CUST-SSN-2 PIC X(02)}.
         *
         * @return two characters of the social security number
         */
        @JsonIgnore
        public String ssn2() {
            return substring(ssnX, SSN_LENGTH, SSN_PART_1_LENGTH, SSN_PART_2_LENGTH);
        }

        /**
         * {@code ACUP-NEW-CUST-SSN-3 PIC X(04)}.
         *
         * @return four characters of the social security number
         */
        @JsonIgnore
        public String ssn3() {
            return substring(ssnX, SSN_LENGTH, SSN_PART_1_LENGTH + SSN_PART_2_LENGTH, SSN_PART_3_LENGTH);
        }

        /**
         * {@code ACUP-xxx-CUST-SSN REDEFINES ACUP-xxx-CUST-SSN-X PIC 9(09)} - the same nine bytes as an
         * unsigned integer. Declared in both groups, over a flat item in {@code OLD} and over a three-part
         * group in {@code NEW}.
         *
         * @return {@code 0} for an unset span, otherwise the value its digits denote
         * @throws IllegalArgumentException if the span holds neither digits nor an unset state
         */
        @JsonIgnore
        public long ssn() {
            return numericView(ssnX, SSN_LENGTH);
        }

        /**
         * {@code ACUP-xxx-CUST-DOB-YEAR PIC X(4)}, the first part of
         * {@code ACUP-xxx-CUST-DOB-PARTS REDEFINES ACUP-xxx-CUST-DOB-YYYY-MM-DD}. Compared against
         * {@code CUST-DOB-YYYY-MM-DD(1:4)} at {@code app/cbl/COACTUPC.cbl:4174-4175}.
         *
         * @return four characters of the date of birth
         */
        @JsonIgnore
        public String dobYear() {
            return substring(dobYyyyMmDd, DOB_LENGTH, 0, AcctSnapshot.DATE_YEAR_LENGTH);
        }

        /**
         * {@code ACUP-xxx-CUST-DOB-MON PIC X(2)}, at position 5 of this eight-byte span - compared against
         * position 6 of the record's ten-byte separated field, at
         * {@code app/cbl/COACTUPC.cbl:4176-4177}.
         *
         * @return two characters of the date of birth
         */
        @JsonIgnore
        public String dobMon() {
            return substring(dobYyyyMmDd, DOB_LENGTH, AcctSnapshot.DATE_YEAR_LENGTH,
                    AcctSnapshot.DATE_MONTH_LENGTH);
        }

        /**
         * {@code ACUP-xxx-CUST-DOB-DAY PIC X(2)}, at position 7 of this span against position 9 of the
         * record's, at {@code app/cbl/COACTUPC.cbl:4178-4179}.
         *
         * @return two characters of the date of birth
         */
        @JsonIgnore
        public String dobDay() {
            return substring(dobYyyyMmDd, DOB_LENGTH,
                    AcctSnapshot.DATE_YEAR_LENGTH + AcctSnapshot.DATE_MONTH_LENGTH,
                    AcctSnapshot.DATE_DAY_LENGTH);
        }

        /**
         * {@code ACUP-xxx-CUST-FICO-SCORE REDEFINES ACUP-xxx-CUST-FICO-SCORE-X PIC 9(03)}.
         *
         * @return {@code 0} for an unset span, otherwise the score its digits denote
         * @throws IllegalArgumentException if the span holds neither digits nor an unset state
         */
        @JsonIgnore
        public int ficoScore() {
            return (int) numericView(ficoScoreX, FICO_SCORE_LENGTH);
        }

        /**
         * {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850}, declared on
         * {@code ACUP-NEW-CUST-FICO-SCORE} at {@code app/cbl/COACTUPC.cbl:848-849}.
         *
         * <p>Evaluable from either group because the bytes are the same; that the condition <em>name</em>
         * exists only in the {@code NEW} group is recorded by
         * {@link DetailGroup#declaresFicoRangeCondition()}, and the {@code OLD} group's field genuinely
         * carries no such condition.
         *
         * @return {@code true} when the score is between {@value #FICO_RANGE_MINIMUM} and
         *         {@value #FICO_RANGE_MAXIMUM} inclusive
         */
        @JsonIgnore
        public boolean ficoRangeIsValid() {
            int score = ficoScore();
            return score >= FICO_RANGE_MINIMUM && score <= FICO_RANGE_MAXIMUM;
        }

        /**
         * Writes this snapshot into a record area.
         *
         * @param area the detail area
         * @param base the offset within {@code area} at which the customer group begins
         */
        private void writeInto(FixedWidthRecord area, int base) {
            area.writeString(base + CUST_ID_OFFSET, CUST_ID_LENGTH, custIdX);
            area.writeString(base + FIRST_NAME_OFFSET, NAME_LENGTH, firstName);
            area.writeString(base + MIDDLE_NAME_OFFSET, NAME_LENGTH, middleName);
            area.writeString(base + LAST_NAME_OFFSET, NAME_LENGTH, lastName);
            area.writeString(base + ADDR_LINE_1_OFFSET, ADDR_LINE_LENGTH, addrLine1);
            area.writeString(base + ADDR_LINE_2_OFFSET, ADDR_LINE_LENGTH, addrLine2);
            area.writeString(base + ADDR_LINE_3_OFFSET, ADDR_LINE_LENGTH, addrLine3);
            area.writeString(base + ADDR_STATE_CD_OFFSET, ADDR_STATE_CD_LENGTH, addrStateCd);
            area.writeString(base + ADDR_COUNTRY_CD_OFFSET, ADDR_COUNTRY_CD_LENGTH, addrCountryCd);
            area.writeString(base + ADDR_ZIP_OFFSET, ADDR_ZIP_LENGTH, addrZip);
            area.writeString(base + PHONE_NUM_1_OFFSET, PHONE_NUM_LENGTH, phoneNum1);
            area.writeString(base + PHONE_NUM_2_OFFSET, PHONE_NUM_LENGTH, phoneNum2);
            area.writeString(base + SSN_OFFSET, SSN_LENGTH, ssnX);
            area.writeString(base + GOVT_ISSUED_ID_OFFSET, GOVT_ISSUED_ID_LENGTH, govtIssuedId);
            area.writeString(base + DOB_OFFSET, DOB_LENGTH, dobYyyyMmDd);
            area.writeString(base + EFT_ACCOUNT_ID_OFFSET, EFT_ACCOUNT_ID_LENGTH, eftAccountId);
            area.writeString(base + PRI_HOLDER_IND_OFFSET, PRI_HOLDER_IND_LENGTH, priHolderInd);
            area.writeString(base + FICO_SCORE_OFFSET, FICO_SCORE_LENGTH, ficoScoreX);
        }

        /**
         * Reads a group's customer half out of a record area.
         *
         * @param area the record area
         * @param base the offset within {@code area} at which the customer group begins
         * @return the snapshot, values raw and untrimmed
         */
        private static CustSnapshot readFrom(FixedWidthRecord area, int base) {
            return new CustSnapshot(area.readString(base + CUST_ID_OFFSET, CUST_ID_LENGTH),
                    area.readString(base + FIRST_NAME_OFFSET, NAME_LENGTH),
                    area.readString(base + MIDDLE_NAME_OFFSET, NAME_LENGTH),
                    area.readString(base + LAST_NAME_OFFSET, NAME_LENGTH),
                    area.readString(base + ADDR_LINE_1_OFFSET, ADDR_LINE_LENGTH),
                    area.readString(base + ADDR_LINE_2_OFFSET, ADDR_LINE_LENGTH),
                    area.readString(base + ADDR_LINE_3_OFFSET, ADDR_LINE_LENGTH),
                    area.readString(base + ADDR_STATE_CD_OFFSET, ADDR_STATE_CD_LENGTH),
                    area.readString(base + ADDR_COUNTRY_CD_OFFSET, ADDR_COUNTRY_CD_LENGTH),
                    area.readString(base + ADDR_ZIP_OFFSET, ADDR_ZIP_LENGTH),
                    area.readString(base + PHONE_NUM_1_OFFSET, PHONE_NUM_LENGTH),
                    area.readString(base + PHONE_NUM_2_OFFSET, PHONE_NUM_LENGTH),
                    area.readString(base + SSN_OFFSET, SSN_LENGTH),
                    area.readString(base + GOVT_ISSUED_ID_OFFSET, GOVT_ISSUED_ID_LENGTH),
                    area.readString(base + DOB_OFFSET, DOB_LENGTH),
                    area.readString(base + EFT_ACCOUNT_ID_OFFSET, EFT_ACCOUNT_ID_LENGTH),
                    area.readString(base + PRI_HOLDER_IND_OFFSET, PRI_HOLDER_IND_LENGTH),
                    area.readString(base + FICO_SCORE_OFFSET, FICO_SCORE_LENGTH));
        }
    }

    // =================================================================================================
    // Shared item helpers for the two snapshot records. One implementation of "does this fit", one of the
    // PIC 9 view and one of the PIC S9V99 view, so a REDEFINES cannot be decoded two different ways.
    // =================================================================================================

    /**
     * Normalises one {@link AcctSnapshot} item: a {@code null} becomes the item's declared width in
     * spaces, and an over-wide value is rejected by name.
     *
     * @param suffix the item's name after the group prefix, for reporting
     * @param value  the value, possibly {@code null}
     * @param width  the item's declared width
     * @return the value, or {@code width} spaces
     * @throws IllegalArgumentException if {@code value} is wider than {@code width}
     */
    private static String fitAcct(String suffix, String value, int width) {
        return fitItem("ACUP-xxx-" + suffix, value, width);
    }

    /**
     * Normalises one {@link CustSnapshot} item, on the same terms as {@link #fitAcct(String, String, int)}.
     *
     * @param suffix the item's name after the group prefix, for reporting
     * @param value  the value, possibly {@code null}
     * @param width  the item's declared width
     * @return the value, or {@code width} spaces
     * @throws IllegalArgumentException if {@code value} is wider than {@code width}
     */
    private static String fitCust(String suffix, String value, int width) {
        return fitItem("ACUP-xxx-" + suffix, value, width);
    }

    /**
     * Normalises one work-area item to exactly its declared width - space-padding a short value and
     * rejecting one too wide to fit.
     *
     * <p><strong>Padding is not a convenience, it is the data model.</strong> A COBOL work-area item
     * declared {@code PIC X(10)} occupies ten bytes at every instant of the program's life: a
     * {@code MOVE 'DEFAULT' TO ACUP-OLD-GROUP-ID} stores {@code 'DEFAULT   '}, never seven bytes. Holding
     * a shorter string would make {@code 9700-CHECK-CHANGE-IN-REC} compare {@code "DEFAULT"} against the
     * {@code "DEFAULT   "} that comes back from storage, report them unequal, and so raise a concurrency
     * conflict that the COBOL never raises. It would also break the byte round trip, because encoding pads
     * and decoding returns the full width. Every component therefore arrives here and leaves at its
     * declared width, which is what makes {@code decode(encode(x))} equal {@code x}.
     *
     * <p>Rejecting an over-wide value rather than truncating it is the other half of the same argument. A
     * silently shortened item would corrupt the same comparison in the opposite direction - turning a
     * genuine conflict into a false match - and a caller is far better served by being told which item
     * does not fit. This matches {@code card/dto/CardUpdateRequest}, which pads and rejects identically
     * for {@code COCRDUPC}'s work area.
     *
     * @param name  the item's name for the failure message
     * @param value the value, possibly {@code null}
     * @param width the item's declared width
     * @return an image of exactly {@code width} characters
     * @throws IllegalArgumentException if {@code value} is wider than {@code width}
     */
    private static String fitItem(String name, String value, int width) {
        if (value == null) {
            return spaces(width);
        }
        if (value.length() > width) {
            throw new IllegalArgumentException(name + " is declared " + width + " character(s) wide but "
                    + "was given " + value.length() + "; the work area cannot hold it, and truncating it "
                    + "here would corrupt the 9700-CHECK-CHANGE-IN-REC comparison rather than report the "
                    + "problem");
        }
        return value.length() == width ? value : value + spaces(width - value.length());
    }

    /**
     * The {@code PIC 9(n)} view of a span - a byte reinterpretation of the same storage the
     * {@code PIC X(n)} view reads, which is what a {@code REDEFINES} is.
     *
     * <p>The span is first brought to its declared width by the {@code PIC X} move rule, because an
     * overlay describes the full width whatever the character view happens to hold, and is then decoded by
     * {@link FixedWidthCodec#decodePic9(String)} so that one implementation owns the zoned-digit alphabet.
     * A span occupying only zero digit positions - {@code LOW-VALUES}, all spaces or all zeros, each of
     * which carries a zero in its digit position under both code pages in play - reads as {@code 0},
     * because that is what COBOL's {@code EQUAL ZEROS} reports true for and
     * {@code app/cbl/COACTUPC.cbl:1053} puts {@code LOW-VALUES} there routinely.
     *
     * @param value the span's characters
     * @param width the span's declared width
     * @return the value the digits denote, or {@code 0} for a zero-valued span
     * @throws IllegalArgumentException if the span holds neither digits nor a zero-valued state
     */
    private static long numericView(String value, int width) {
        String image = PICTURE_RULES.movePicX(value, width);
        if (isZeroValued(image)) {
            return 0L;
        }
        return PICTURE_RULES.decodePic9(image);
    }

    /**
     * The {@code PIC S9(10)V99} view of a twelve-byte span - the second typed accessor over the same
     * storage the {@code PIC X(12)} view reads.
     *
     * <p>Decoded by {@link FixedWidthCodec#decodeSignedScaled(String, int)} at
     * {@link CobolDecimal#MONETARY_SCALE}, which reads the sign from the overpunch in the trailing byte
     * and returns a value at exactly scale 2 through {@link CobolDecimal}'s
     * {@code RoundingMode.DOWN} store. Truncation, never rounding: {@code ROUNDED} appears zero times in
     * all 28 programs, so {@code HALF_UP} and {@code HALF_EVEN} would both be wrong
     * (gate <strong>G24</strong>).
     *
     * <p>A zero-valued span reads as {@link CobolDecimal#monetaryZero()} rather than being handed to the
     * decoder, for the same reason {@link #numericView(String, int)} guards: an initialised work area holds
     * spaces, and spaces are not digits.
     *
     * @param value the span's characters
     * @return the value at scale {@link CobolDecimal#MONETARY_SCALE}
     * @throws IllegalArgumentException if the span holds neither a signed zoned image nor a zero-valued
     *                                  state
     */
    private static BigDecimal monetaryView(String value) {
        String image = PICTURE_RULES.movePicX(value, AcctSnapshot.MONEY_LENGTH);
        if (isZeroValued(image)) {
            return CobolDecimal.monetaryZero();
        }
        return PICTURE_RULES.decodeSignedScaled(image, CobolDecimal.MONETARY_SCALE);
    }

    /**
     * Whether every character of a span occupies a zero digit position, which is the state COBOL's
     * {@code EQUAL ZEROS} reports true for.
     *
     * <p>That is exactly {@code LOW-VALUES}, all spaces or all zeros: {@code U+0000} encodes to
     * {@code 0x00}, the space to {@code 0x40} under EBCDIC and {@code 0x20} under ASCII, and {@code '0'}
     * to {@code 0xF0} and {@code 0x30} respectively - every one of which carries a zero in its digit
     * position under both code pages.
     *
     * @param image the span's characters
     * @return {@code true} when the span is zero valued
     */
    private static boolean isZeroValued(String image) {
        for (int index = 0; index < image.length(); index++) {
            char character = image.charAt(index);
            if (character != LOW_VALUE && character != SPACE && character != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * One part of an {@link AcctSnapshot} date overlay.
     *
     * @param date   the eight-character, unseparated date span
     * @param offset the part's offset within the span
     * @param width  the part's width
     * @return the part, at exactly {@code width} characters
     */
    private static String datePart(String date, int offset, int width) {
        return substring(date, AcctSnapshot.DATE_LENGTH, offset, width);
    }

    /**
     * One named part of a telephone overlay, skipping the {@code FILLER} positions that carry the
     * punctuation.
     *
     * @param phone  the fifteen-character telephone span
     * @param offset the part's offset within the span
     * @param width  the part's width
     * @return the part, at exactly {@code width} characters
     */
    private static String phonePart(String phone, int offset, int width) {
        return substring(phone, CustSnapshot.PHONE_NUM_LENGTH, offset, width);
    }

    /**
     * A sub-span of an overlay, read from the same bytes the whole-span accessor reads.
     *
     * <p>The span is brought to its declared width first, so a short stored value yields spaces for the
     * parts beyond it rather than an out-of-bounds failure - which is what reading a {@code REDEFINES}
     * part of a partly filled COBOL item gives.
     *
     * @param value       the whole span's characters
     * @param spanWidth   the whole span's declared width
     * @param offset      the part's offset within the span
     * @param partWidth   the part's width
     * @return the part, at exactly {@code partWidth} characters
     */
    private static String substring(String value, int spanWidth, int offset, int partWidth) {
        String image = PICTURE_RULES.movePicX(value, spanWidth);
        return image.substring(offset, offset + partWidth);
    }

    /**
     * Which of the two {@code 05}-level detail groups a {@link Details} snapshot is -
     * {@code ACUP-OLD-DETAILS} ({@code app/cbl/COACTUPC.cbl:669}) or {@code ACUP-NEW-DETAILS}
     * ({@code :757}).
     *
     * <p>They share a <em>geometry</em> - {@value Details#RECORD_LENGTH} bytes each, item for item at the
     * same offsets - but not a <em>shape</em>, and not a single item name. The names matter because a
     * field-by-field diff compares by name, so a snapshot stored in the wrong position would serialise to
     * the right bytes and present the wrong names. That is why each group owns its own
     * {@link #layout()} rather than sharing one.
     *
     * <h2>Two deliberate structural differences, neither catchable by a width check</h2>
     *
     * <ol>
     *   <li><strong>The social security number is flat in {@code OLD} and a group in {@code NEW}.</strong>
     *       {@code app/cbl/COACTUPC.cbl:742} declares {@code 15 ACUP-OLD-CUST-SSN-X PIC X(09)} - one
     *       elementary item. {@code :830-833} declares {@code 15 ACUP-NEW-CUST-SSN-X.} containing
     *       {@code 20 ACUP-NEW-CUST-SSN-1 PIC X(03)}, {@code 20 ACUP-NEW-CUST-SSN-2 PIC X(02)} and
     *       {@code 20 ACUP-NEW-CUST-SSN-3 PIC X(04)}, with {@code ACUP-NEW-CUST-SSN REDEFINES
     *       ACUP-NEW-CUST-SSN-X PIC 9(09)} over them at {@code :834-835}. <strong>Both forms are nine
     *       bytes</strong>, so no size check can tell them apart - which is precisely why the three parts
     *       are declared as named spans in the {@code NEW} layout and not in the {@code OLD} one, and why
     *       {@link #declaresSsnParts()} exists. The three parts are also what the screen's
     *       {@code ACTSSN1}, {@code ACTSSN2} and {@code ACTSSN3} move into, which is the whole reason the
     *       screen splits the number.</li>
     *   <li><strong>Only {@code NEW}'s FICO field carries a range condition.</strong>
     *       {@code app/cbl/COACTUPC.cbl:848-849} declares
     *       {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850} on {@code ACUP-NEW-CUST-FICO-SCORE};
     *       {@code ACUP-OLD-CUST-FICO-SCORE} at {@code :755-756} declares none.
     *       {@link #declaresFicoRangeCondition()} records the difference instead of harmonising it
     *       (practice <strong>B5</strong>).</li>
     * </ol>
     *
     * <p>Each group's layout declares every byte: the eleven account items, the eighteen customer items,
     * every {@code REDEFINES} overlay, both {@code 10}-level group items and - critically - all four
     * {@code FILLER} spans inside each telephone overlay. A group item over elementary items is declared as
     * an overlay rather than as storage, because its bytes are already accounted for by the items it
     * contains; declaring it as storage would double-count them and
     * {@link RecordLayout}'s own geometry check would reject the layout.
     */
    public enum DetailGroup {

        /**
         * {@code ACUP-OLD-DETAILS} - the snapshot the screen was painted from, and the one
         * {@code 9700-CHECK-CHANGE-IN-REC} ({@code app/cbl/COACTUPC.cbl:4109-4193}) compares the freshly
         * read record against before allowing a rewrite. Its social security number is a single flat
         * {@code PIC X(09)} item.
         */
        OLD("ACUP-OLD-", false),

        /**
         * {@code ACUP-NEW-DETAILS} - what the operator typed, rebuilt by
         * {@code INITIALIZE ACUP-NEW-DETAILS} at {@code app/cbl/COACTUPC.cbl:1047} and then filled field by
         * field from {@code CACTUPAI}. Its social security number is a three-part group and its FICO field
         * carries the range condition.
         */
        NEW("ACUP-NEW-", true);

        /** The name prefix every item of this group carries, verbatim. */
        private final String prefix;

        /**
         * Whether this group declares {@code CUST-SSN-1}, {@code -2} and {@code -3} as named sub-items, and
         * whether its FICO field carries {@code 88 FICO-RANGE-IS-VALID}. Both are true of {@code NEW} only,
         * and they travel together because they are the two differences between the groups.
         */
        private final boolean newShape;

        /** This group's {@value Details#RECORD_LENGTH}-byte layout, every byte declared. */
        private final RecordLayout layout;

        /** This group's {@value AcctSnapshot#RECORD_LENGTH}-byte account half, as a standalone layout. */
        private final RecordLayout acctLayout;

        /** This group's {@value CustSnapshot#RECORD_LENGTH}-byte customer half, as a standalone layout. */
        private final RecordLayout custLayout;

        /**
         * Builds one group's spans and layouts from the geometry constants of {@link AcctSnapshot} and
         * {@link CustSnapshot}, so every span name carries this group's prefix verbatim.
         *
         * @param prefix   the item-name prefix, {@code "ACUP-OLD-"} or {@code "ACUP-NEW-"}
         * @param newShape whether this is the {@code NEW} group, with the split SSN and the FICO condition
         */
        DetailGroup(String prefix, boolean newShape) {
            this.prefix = prefix;
            this.newShape = newShape;
            this.acctLayout = RecordLayout.of(AcctSnapshot.RECORD_LENGTH,
                    acctSpans(prefix, 0).toArray(FieldSpan[]::new));
            this.custLayout = RecordLayout.of(CustSnapshot.RECORD_LENGTH,
                    custSpans(prefix, newShape, 0).toArray(FieldSpan[]::new));
            List<FieldSpan> spans = new ArrayList<>();
            spans.addAll(acctSpans(prefix, Details.ACCT_DATA_OFFSET));
            spans.addAll(custSpans(prefix, newShape, Details.CUST_DATA_OFFSET));
            // The two 10-level group items last, as overlays: their bytes belong to the elementary items
            // above and a RecordLayout counts storage exactly once.
            spans.add(FieldSpan.redefining(prefix + "ACCT-DATA", Details.ACCT_DATA_OFFSET,
                    AcctSnapshot.RECORD_LENGTH, PictureKind.ALPHANUMERIC));
            spans.add(FieldSpan.redefining(prefix + "CUST-DATA", Details.CUST_DATA_OFFSET,
                    CustSnapshot.RECORD_LENGTH, PictureKind.ALPHANUMERIC));
            this.layout = RecordLayout.of(Details.RECORD_LENGTH, spans.toArray(FieldSpan[]::new));
        }

        /**
         * The eleven account items and their nine overlays, at a given base offset.
         *
         * @param prefix the group's item-name prefix
         * @param base   where the account group begins
         * @return the spans in declaration order, storage before each overlay
         */
        private static List<FieldSpan> acctSpans(String prefix, int base) {
            List<FieldSpan> spans = new ArrayList<>();
            FieldSpan acctId = FieldSpan.alphanumeric(prefix + "ACCT-ID-X",
                    base + AcctSnapshot.ACCT_ID_OFFSET, AcctSnapshot.ACCT_ID_LENGTH);
            spans.add(acctId);
            spans.add(acctId.redefinedAs(prefix + "ACCT-ID", PictureKind.UNSIGNED_NUMERIC));
            spans.add(FieldSpan.alphanumeric(prefix + "ACTIVE-STATUS",
                    base + AcctSnapshot.ACTIVE_STATUS_OFFSET, AcctSnapshot.ACTIVE_STATUS_LENGTH));
            spans.addAll(moneySpans(prefix, "CURR-BAL", base + AcctSnapshot.CURR_BAL_OFFSET));
            spans.addAll(moneySpans(prefix, "CREDIT-LIMIT", base + AcctSnapshot.CREDIT_LIMIT_OFFSET));
            spans.addAll(moneySpans(prefix, "CASH-CREDIT-LIMIT",
                    base + AcctSnapshot.CASH_CREDIT_LIMIT_OFFSET));
            spans.addAll(dateSpans(prefix, "OPEN-DATE", "OPEN", base + AcctSnapshot.OPEN_DATE_OFFSET));
            spans.addAll(dateSpans(prefix, "EXPIRAION-DATE", "EXP",
                    base + AcctSnapshot.EXPIRAION_DATE_OFFSET));
            spans.addAll(dateSpans(prefix, "REISSUE-DATE", "REISSUE",
                    base + AcctSnapshot.REISSUE_DATE_OFFSET));
            spans.addAll(moneySpans(prefix, "CURR-CYC-CREDIT",
                    base + AcctSnapshot.CURR_CYC_CREDIT_OFFSET));
            spans.addAll(moneySpans(prefix, "CURR-CYC-DEBIT", base + AcctSnapshot.CURR_CYC_DEBIT_OFFSET));
            spans.add(FieldSpan.alphanumeric(prefix + "GROUP-ID", base + AcctSnapshot.GROUP_ID_OFFSET,
                    AcctSnapshot.GROUP_ID_LENGTH));
            return spans;
        }

        /**
         * One money item and its {@code PIC S9(10)V99} overlay - twelve bytes seen two ways.
         *
         * <p>The {@code -N} form is declared with {@link FieldSpan#redefinedAs(String, PictureKind)} over
         * the character span rather than as a span of its own, because {@code REDEFINES} re-describes bytes
         * that already exist. Declaring it with
         * {@link FieldSpan#signedScaled(String, int, int, int)} would make it a second <em>storage</em>
         * span at the same offset, and the geometry check of
         * {@link FixedWidthRecord.RecordLayout} rejects that outright - as it should, because a layout
         * that counted these twelve bytes twice would report the group as 166 bytes rather than
         * {@value AcctSnapshot#RECORD_LENGTH}.
         *
         * <p>The width therefore comes from {@link AcctSnapshot#MONEY_LENGTH}, and
         * {@link AcctSnapshot#MONEY_INTEGER_DIGITS} plus {@link CobolDecimal#MONETARY_SCALE} is asserted
         * to agree with it: ten integer digits plus two fraction digits is twelve bytes, with the sign
         * overpunched into the trailing byte and no phantom sign position reserved.
         *
         * @param prefix the group's item-name prefix
         * @param suffix the item's name after the prefix
         * @param offset the item's absolute offset
         * @return the character span followed by its numeric overlay
         */
        private static List<FieldSpan> moneySpans(String prefix, String suffix, int offset) {
            FieldSpan characters =
                    FieldSpan.alphanumeric(prefix + suffix, offset, AcctSnapshot.MONEY_LENGTH);
            return List.of(characters,
                    characters.redefinedAs(prefix + suffix + "-N", PictureKind.SIGNED_SCALED));
        }

        /**
         * One eight-byte date item, its {@code -PARTS} group overlay and the three part overlays inside it.
         *
         * <p>The part names do not always follow the item name: {@code ACUP-xxx-EXPIRAION-DATE-PARTS}
         * contains {@code ACUP-xxx-EXP-YEAR}, {@code -EXP-MON} and {@code -EXP-DAY}, which is why the part
         * stem is a separate argument rather than derived from the item name.
         *
         * @param prefix   the group's item-name prefix
         * @param suffix   the date item's name after the prefix, for example {@code "EXPIRAION-DATE"}
         * @param partStem the part names' stem, for example {@code "EXP"}
         * @param offset   the date item's absolute offset
         * @return the character span, the parts group overlay and the three part overlays
         */
        private static List<FieldSpan> dateSpans(String prefix, String suffix, String partStem,
                                                 int offset) {
            return List.of(
                    FieldSpan.alphanumeric(prefix + suffix, offset, AcctSnapshot.DATE_LENGTH),
                    FieldSpan.redefining(prefix + suffix + "-PARTS", offset, AcctSnapshot.DATE_LENGTH,
                            PictureKind.ALPHANUMERIC),
                    FieldSpan.redefining(prefix + partStem + "-YEAR", offset,
                            AcctSnapshot.DATE_YEAR_LENGTH, PictureKind.ALPHANUMERIC),
                    FieldSpan.redefining(prefix + partStem + "-MON",
                            offset + AcctSnapshot.DATE_YEAR_LENGTH, AcctSnapshot.DATE_MONTH_LENGTH,
                            PictureKind.ALPHANUMERIC),
                    FieldSpan.redefining(prefix + partStem + "-DAY",
                            offset + AcctSnapshot.DATE_YEAR_LENGTH + AcctSnapshot.DATE_MONTH_LENGTH,
                            AcctSnapshot.DATE_DAY_LENGTH, PictureKind.ALPHANUMERIC));
        }

        /**
         * The eighteen customer items and their overlays, at a given base offset.
         *
         * @param prefix   the group's item-name prefix
         * @param newShape whether to declare the three SSN sub-items, which only {@code NEW} does
         * @param base     where the customer group begins
         * @return the spans in declaration order
         */
        private static List<FieldSpan> custSpans(String prefix, boolean newShape, int base) {
            List<FieldSpan> spans = new ArrayList<>();
            FieldSpan custId = FieldSpan.alphanumeric(prefix + "CUST-ID-X",
                    base + CustSnapshot.CUST_ID_OFFSET, CustSnapshot.CUST_ID_LENGTH);
            spans.add(custId);
            spans.add(custId.redefinedAs(prefix + "CUST-ID", PictureKind.UNSIGNED_NUMERIC));
            spans.add(FieldSpan.alphanumeric(prefix + "CUST-FIRST-NAME",
                    base + CustSnapshot.FIRST_NAME_OFFSET, CustSnapshot.NAME_LENGTH));
            spans.add(FieldSpan.alphanumeric(prefix + "CUST-MIDDLE-NAME",
                    base + CustSnapshot.MIDDLE_NAME_OFFSET, CustSnapshot.NAME_LENGTH));
            spans.add(FieldSpan.alphanumeric(prefix + "CUST-LAST-NAME",
                    base + CustSnapshot.LAST_NAME_OFFSET, CustSnapshot.NAME_LENGTH));
            spans.add(FieldSpan.alphanumeric(prefix + "CUST-ADDR-LINE-1",
                    base + CustSnapshot.ADDR_LINE_1_OFFSET, CustSnapshot.ADDR_LINE_LENGTH));
            spans.add(FieldSpan.alphanumeric(prefix + "CUST-ADDR-LINE-2",
                    base + CustSnapshot.ADDR_LINE_2_OFFSET, CustSnapshot.ADDR_LINE_LENGTH));
            spans.add(FieldSpan.alphanumeric(prefix + "CUST-ADDR-LINE-3",
                    base + CustSnapshot.ADDR_LINE_3_OFFSET, CustSnapshot.ADDR_LINE_LENGTH));
            spans.add(FieldSpan.alphanumeric(prefix + "CUST-ADDR-STATE-CD",
                    base + CustSnapshot.ADDR_STATE_CD_OFFSET, CustSnapshot.ADDR_STATE_CD_LENGTH));
            spans.add(FieldSpan.alphanumeric(prefix + "CUST-ADDR-COUNTRY-CD",
                    base + CustSnapshot.ADDR_COUNTRY_CD_OFFSET, CustSnapshot.ADDR_COUNTRY_CD_LENGTH));
            spans.add(FieldSpan.alphanumeric(prefix + "CUST-ADDR-ZIP",
                    base + CustSnapshot.ADDR_ZIP_OFFSET, CustSnapshot.ADDR_ZIP_LENGTH));
            spans.addAll(phoneSpans(prefix, "1", base + CustSnapshot.PHONE_NUM_1_OFFSET));
            spans.addAll(phoneSpans(prefix, "2", base + CustSnapshot.PHONE_NUM_2_OFFSET));
            spans.addAll(ssnSpans(prefix, newShape, base + CustSnapshot.SSN_OFFSET));
            spans.add(FieldSpan.alphanumeric(prefix + "CUST-GOVT-ISSUED-ID",
                    base + CustSnapshot.GOVT_ISSUED_ID_OFFSET, CustSnapshot.GOVT_ISSUED_ID_LENGTH));
            spans.addAll(dobSpans(prefix, base + CustSnapshot.DOB_OFFSET));
            spans.add(FieldSpan.alphanumeric(prefix + "CUST-EFT-ACCOUNT-ID",
                    base + CustSnapshot.EFT_ACCOUNT_ID_OFFSET, CustSnapshot.EFT_ACCOUNT_ID_LENGTH));
            spans.add(FieldSpan.alphanumeric(prefix + "CUST-PRI-HOLDER-IND",
                    base + CustSnapshot.PRI_HOLDER_IND_OFFSET, CustSnapshot.PRI_HOLDER_IND_LENGTH));
            FieldSpan fico = FieldSpan.alphanumeric(prefix + "CUST-FICO-SCORE-X",
                    base + CustSnapshot.FICO_SCORE_OFFSET, CustSnapshot.FICO_SCORE_LENGTH);
            spans.add(fico);
            spans.add(fico.redefinedAs(prefix + "CUST-FICO-SCORE", PictureKind.UNSIGNED_NUMERIC));
            return spans;
        }

        /**
         * One fifteen-byte telephone item, its {@code -X} group overlay, its three named parts and all four
         * of its {@code FILLER} spans.
         *
         * <p>Every {@code FILLER} is declared (gate <strong>G21</strong>). They are overlay spans, not
         * storage, because the fifteen bytes they subdivide are already accounted for by the item they
         * redefine - and declaring them is what makes the three named parts land on 250, 254 and 258 rather
         * than on 249, 252 and 255.
         *
         * @param prefix the group's item-name prefix
         * @param which  {@code "1"} or {@code "2"}
         * @param offset the telephone item's absolute offset
         * @return the character span, the group overlay, the four {@code FILLER} spans and the three parts
         */
        private static List<FieldSpan> phoneSpans(String prefix, String which, int offset) {
            String item = prefix + "CUST-PHONE-NUM-" + which;
            return List.of(
                    FieldSpan.alphanumeric(item, offset, CustSnapshot.PHONE_NUM_LENGTH),
                    FieldSpan.redefining(item + "-X", offset, CustSnapshot.PHONE_NUM_LENGTH,
                            PictureKind.ALPHANUMERIC),
                    FieldSpan.redefining(FILLER_ITEM, offset, CustSnapshot.PHONE_LEADING_FILLER_LENGTH,
                            PictureKind.FILLER),
                    FieldSpan.redefining(item + "A",
                            offset + CustSnapshot.PHONE_AREA_CODE_RELATIVE_OFFSET,
                            CustSnapshot.PHONE_AREA_CODE_LENGTH, PictureKind.ALPHANUMERIC),
                    FieldSpan.redefining(FILLER_ITEM,
                            offset + CustSnapshot.PHONE_AREA_CODE_RELATIVE_OFFSET
                                    + CustSnapshot.PHONE_AREA_CODE_LENGTH,
                            CustSnapshot.PHONE_INNER_FILLER_LENGTH, PictureKind.FILLER),
                    FieldSpan.redefining(item + "B", offset + CustSnapshot.PHONE_PREFIX_RELATIVE_OFFSET,
                            CustSnapshot.PHONE_PREFIX_LENGTH, PictureKind.ALPHANUMERIC),
                    FieldSpan.redefining(FILLER_ITEM,
                            offset + CustSnapshot.PHONE_PREFIX_RELATIVE_OFFSET
                                    + CustSnapshot.PHONE_PREFIX_LENGTH,
                            CustSnapshot.PHONE_INNER_FILLER_LENGTH, PictureKind.FILLER),
                    FieldSpan.redefining(item + "C",
                            offset + CustSnapshot.PHONE_LINE_NUMBER_RELATIVE_OFFSET,
                            CustSnapshot.PHONE_LINE_NUMBER_LENGTH, PictureKind.ALPHANUMERIC),
                    FieldSpan.redefining(FILLER_ITEM,
                            offset + CustSnapshot.PHONE_LINE_NUMBER_RELATIVE_OFFSET
                                    + CustSnapshot.PHONE_LINE_NUMBER_LENGTH,
                            CustSnapshot.PHONE_TRAILING_FILLER_LENGTH, PictureKind.FILLER));
        }

        /**
         * The social security number's spans - <strong>the one place the two groups genuinely differ in
         * shape.</strong>
         *
         * <p>{@code OLD} declares one flat storage span, {@code ACUP-OLD-CUST-SSN-X PIC X(09)}, with the
         * {@code PIC 9(09)} overlay on top. {@code NEW} declares three storage spans -
         * {@code ACUP-NEW-CUST-SSN-1}, {@code -2} and {@code -3} - then the group item
         * {@code ACUP-NEW-CUST-SSN-X} as an overlay over them, then the {@code PIC 9(09)} overlay. Nine
         * bytes either way, which is why the difference has to be asserted by name rather than by width.
         *
         * @param prefix   the group's item-name prefix
         * @param newShape whether to declare the three sub-items
         * @param offset   the SSN's absolute offset
         * @return the spans in declaration order
         */
        private static List<FieldSpan> ssnSpans(String prefix, boolean newShape, int offset) {
            if (!newShape) {
                FieldSpan flat = FieldSpan.alphanumeric(prefix + "CUST-SSN-X", offset,
                        CustSnapshot.SSN_LENGTH);
                return List.of(flat, flat.redefinedAs(prefix + "CUST-SSN", PictureKind.UNSIGNED_NUMERIC));
            }
            return List.of(
                    FieldSpan.alphanumeric(prefix + "CUST-SSN-1", offset, CustSnapshot.SSN_PART_1_LENGTH),
                    FieldSpan.alphanumeric(prefix + "CUST-SSN-2", offset + CustSnapshot.SSN_PART_1_LENGTH,
                            CustSnapshot.SSN_PART_2_LENGTH),
                    FieldSpan.alphanumeric(prefix + "CUST-SSN-3",
                            offset + CustSnapshot.SSN_PART_1_LENGTH + CustSnapshot.SSN_PART_2_LENGTH,
                            CustSnapshot.SSN_PART_3_LENGTH),
                    FieldSpan.redefining(prefix + "CUST-SSN-X", offset, CustSnapshot.SSN_LENGTH,
                            PictureKind.ALPHANUMERIC),
                    FieldSpan.redefining(prefix + "CUST-SSN", offset, CustSnapshot.SSN_LENGTH,
                            PictureKind.UNSIGNED_NUMERIC));
        }

        /**
         * The eight-byte date of birth, its {@code CUST-DOB-PARTS} overlay and its three part overlays.
         *
         * <p>The parts group is named {@code ACUP-xxx-CUST-DOB-PARTS}, not
         * {@code ...-CUST-DOB-YYYY-MM-DD-PARTS} ({@code app/cbl/COACTUPC.cbl:747-751}), so the name is
         * written out rather than derived.
         *
         * @param prefix the group's item-name prefix
         * @param offset the date of birth's absolute offset
         * @return the character span, the parts overlay and the three part overlays
         */
        private static List<FieldSpan> dobSpans(String prefix, int offset) {
            return List.of(
                    FieldSpan.alphanumeric(prefix + "CUST-DOB-YYYY-MM-DD", offset,
                            CustSnapshot.DOB_LENGTH),
                    FieldSpan.redefining(prefix + "CUST-DOB-PARTS", offset, CustSnapshot.DOB_LENGTH,
                            PictureKind.ALPHANUMERIC),
                    FieldSpan.redefining(prefix + "CUST-DOB-YEAR", offset,
                            AcctSnapshot.DATE_YEAR_LENGTH, PictureKind.ALPHANUMERIC),
                    FieldSpan.redefining(prefix + "CUST-DOB-MON", offset + AcctSnapshot.DATE_YEAR_LENGTH,
                            AcctSnapshot.DATE_MONTH_LENGTH, PictureKind.ALPHANUMERIC),
                    FieldSpan.redefining(prefix + "CUST-DOB-DAY",
                            offset + AcctSnapshot.DATE_YEAR_LENGTH + AcctSnapshot.DATE_MONTH_LENGTH,
                            AcctSnapshot.DATE_DAY_LENGTH, PictureKind.ALPHANUMERIC));
        }

        /**
         * The item-name prefix every item of this group carries.
         *
         * @return {@code "ACUP-OLD-"} or {@code "ACUP-NEW-"}
         */
        public String prefix() {
            return prefix;
        }

        /**
         * The verbatim COBOL name of the {@code 05}-level group itself.
         *
         * @return {@code "ACUP-OLD-DETAILS"} or {@code "ACUP-NEW-DETAILS"}
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
         * Whether this group declares {@code CUST-SSN-1}, {@code -2} and {@code -3} as named sub-items -
         * true of {@code NEW} only, per {@code app/cbl/COACTUPC.cbl:830-833} against {@code :742}.
         *
         * @return {@code true} for {@link #NEW}
         */
        public boolean declaresSsnParts() {
            return newShape;
        }

        /**
         * Whether this group's FICO field carries {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850} -
         * true of {@code NEW} only, per {@code app/cbl/COACTUPC.cbl:848-849}.
         *
         * @return {@code true} for {@link #NEW}
         */
        public boolean declaresFicoRangeCondition() {
            return newShape;
        }

        /**
         * This group's whole {@value Details#RECORD_LENGTH}-byte layout.
         *
         * @return the layout, with every byte declared and every overlay named under this group's prefix
         */
        public RecordLayout layout() {
            return layout;
        }

        /**
         * This group's account half as a standalone {@value AcctSnapshot#RECORD_LENGTH}-byte layout.
         *
         * @return the account layout
         */
        public RecordLayout acctLayout() {
            return acctLayout;
        }

        /**
         * This group's customer half as a standalone {@value CustSnapshot#RECORD_LENGTH}-byte layout.
         *
         * @return the customer layout
         */
        public RecordLayout custLayout() {
            return custLayout;
        }
    }

    /** The name every reserved span carries, matching COBOL's own unnamed {@code FILLER}. */
    private static final String FILLER_ITEM = "FILLER";

    /**
     * One {@code 05}-level detail group - {@value #RECORD_LENGTH} bytes made of an account half and a
     * customer half, labelled with which of the two groups it is.
     *
     * <p>{@code app/cbl/COACTUPC.cbl:669-756} declares {@code ACUP-OLD-DETAILS} and {@code :757-849}
     * declares {@code ACUP-NEW-DETAILS}; both contain a {@code 10 ACUP-xxx-ACCT-DATA} of
     * {@value AcctSnapshot#RECORD_LENGTH} bytes followed by a {@code 10 ACUP-xxx-CUST-DATA} of
     * {@value CustSnapshot#RECORD_LENGTH}, and {@code 106 + 330 = }{@value #RECORD_LENGTH}.
     *
     * <p>The {@link DetailGroup} label is carried rather than inferred because the two groups share a
     * geometry but not a single item name, and names are what a field-by-field diff compares. Storing an
     * {@code OLD} snapshot in the {@code NEW} position would serialise to plausible bytes under the wrong
     * names, which {@link CommArea} refuses.
     *
     * @param group which of the two groups this is
     * @param acct  the {@value AcctSnapshot#RECORD_LENGTH}-byte account half
     * @param cust  the {@value CustSnapshot#RECORD_LENGTH}-byte customer half
     */
    public record Details(DetailGroup group, AcctSnapshot acct, CustSnapshot cust) {

        /** Total width: {@value AcctSnapshot#RECORD_LENGTH} + {@value CustSnapshot#RECORD_LENGTH}. */
        public static final int RECORD_LENGTH = AcctSnapshot.RECORD_LENGTH + CustSnapshot.RECORD_LENGTH;

        /** Absolute 0-based offset of {@code 10 ACUP-xxx-ACCT-DATA} within the group. */
        public static final int ACCT_DATA_OFFSET = 0;

        /** Absolute 0-based offset of {@code 10 ACUP-xxx-CUST-DATA}: {@value AcctSnapshot#RECORD_LENGTH}. */
        public static final int CUST_DATA_OFFSET = AcctSnapshot.RECORD_LENGTH;

        /**
         * Checks that nothing is {@code null} and substitutes an initialised half for an omitted one.
         *
         * @throws NullPointerException if {@code group} is {@code null}
         */
        public Details {
            Objects.requireNonNull(group, "A DetailGroup is required: ACUP-OLD-DETAILS and "
                    + "ACUP-NEW-DETAILS share a layout but not a single item name, and the names are what "
                    + "a field-by-field diff compares");
            acct = acct == null ? AcctSnapshot.initialised() : acct;
            cust = cust == null ? CustSnapshot.initialised() : cust;
        }

        /**
         * A group in which every item holds its declared width in spaces - the state
         * {@code INITIALIZE ACUP-NEW-DETAILS} ({@code app/cbl/COACTUPC.cbl:1047}) leaves behind.
         *
         * @param group which of the two groups to build
         * @return the all-spaces group
         * @throws NullPointerException if {@code group} is {@code null}
         */
        public static Details initialised(DetailGroup group) {
            return new Details(group, AcctSnapshot.initialised(), CustSnapshot.initialised());
        }

        /**
         * The same bytes relabelled as the other group, for the {@code MOVE ACUP-NEW-DETAILS TO
         * ACUP-OLD-DETAILS} shape of operation - a move that changes which names the bytes answer to
         * without changing the bytes.
         *
         * @param target which group to relabel as
         * @return this group when already labelled {@code target}, otherwise a relabelled copy
         * @throws NullPointerException if {@code target} is {@code null}
         */
        public Details asGroup(DetailGroup target) {
            Objects.requireNonNull(target, "A target DetailGroup is required to relabel a snapshot");
            return target == group ? this : new Details(target, acct, cust);
        }

        /**
         * The verbatim COBOL name of this group.
         *
         * @return {@code "ACUP-OLD-DETAILS"} or {@code "ACUP-NEW-DETAILS"}
         */
        @JsonIgnore
        public String groupName() {
            return group.groupName();
        }

        /**
         * Renders this group as exactly {@value #RECORD_LENGTH} bytes.
         *
         * @param codec the codec, carrying the code page explicitly
         * @return exactly {@value #RECORD_LENGTH} bytes
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public byte[] encode(FixedWidthCodec codec) {
            return toFixedWidthRecord(codec).toByteArray();
        }

        /**
         * Renders this group as exactly {@value #RECORD_LENGTH} bytes in a named code page.
         *
         * @param charset the code page, stated explicitly and never defaulted
         * @return exactly {@value #RECORD_LENGTH} bytes
         * @throws NullPointerException if {@code charset} is {@code null}
         */
        public byte[] encode(Charset charset) {
            return encode(new FixedWidthCodec(charset));
        }

        /**
         * This group as a record area under its own layout, so a caller can read any span by name -
         * including a {@code REDEFINES} overlay and a {@code FILLER}.
         *
         * @param codec the codec, carrying the code page explicitly
         * @return a {@value #RECORD_LENGTH}-byte area
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public FixedWidthRecord toFixedWidthRecord(FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A codec carrying the code page is required to render "
                    + "a detail group; the platform default is never assumed");
            FixedWidthRecord area = codec.newRecord(group.layout());
            acct.writeInto(area, ACCT_DATA_OFFSET);
            cust.writeInto(area, CUST_DATA_OFFSET);
            return area;
        }

        /**
         * Reads a {@value #RECORD_LENGTH}-byte image back into a group.
         *
         * <p>Values come back <strong>raw and untrimmed</strong>, each at exactly its declared width, which
         * is what makes the round trip byte-identical.
         *
         * @param image exactly {@value #RECORD_LENGTH} bytes
         * @param group which group the bytes are
         * @param codec the codec, carrying the code page explicitly
         * @return the group
         * @throws NullPointerException     if any argument is {@code null}
         * @throws IllegalArgumentException if {@code image} is not {@value #RECORD_LENGTH} bytes
         */
        public static Details decode(byte[] image, DetailGroup group, FixedWidthCodec codec) {
            Objects.requireNonNull(group, "A DetailGroup is required to decode a snapshot");
            Objects.requireNonNull(codec, "A codec carrying the image's code page is required to decode "
                    + "a detail group; the platform default is never assumed");
            Objects.requireNonNull(image, "An image is required to decode a detail group");
            if (image.length != RECORD_LENGTH) {
                throw new IllegalArgumentException(group.groupName() + " is " + RECORD_LENGTH + " bytes - "
                        + AcctSnapshot.RECORD_LENGTH + " of account data plus "
                        + CustSnapshot.RECORD_LENGTH + " of customer data - but this image is "
                        + image.length + " byte(s)");
            }
            return readFrom(codec.wrap(image, group.layout()), group, ACCT_DATA_OFFSET);
        }

        /**
         * Reads a {@value #RECORD_LENGTH}-byte image back into a group, in a named code page.
         *
         * @param image   exactly {@value #RECORD_LENGTH} bytes
         * @param group   which group the bytes are
         * @param charset the code page, stated explicitly
         * @return the group
         * @throws NullPointerException if any argument is {@code null}
         */
        public static Details decode(byte[] image, DetailGroup group, Charset charset) {
            return decode(image, group, new FixedWidthCodec(charset));
        }

        /**
         * Reads a group out of an already-wrapped area, at a given base offset.
         *
         * @param area  the record area
         * @param group which group the bytes are
         * @param base  where this group begins within {@code area}
         * @return the group
         */
        private static Details readFrom(FixedWidthRecord area, DetailGroup group, int base) {
            return new Details(group,
                    AcctSnapshot.readFrom(area, base + ACCT_DATA_OFFSET),
                    CustSnapshot.readFrom(area, base + CUST_DATA_OFFSET));
        }
    }

    /**
     * {@code 01 WS-THIS-PROGCOMMAREA} - the {@value #RECORD_LENGTH}-byte work area {@code COACTUPC} keeps
     * across a pseudo-conversational turn, {@code app/cbl/COACTUPC.cbl:652-849}.
     *
     * <pre>
     *   offset  width  group
     *   ------  -----  ------------------------------------------------------------
     *        0      1  05 ACCT-UPDATE-SCREEN-DATA / 10 ACUP-CHANGE-ACTION PIC X(1)
     *        1    436  05 ACUP-OLD-DETAILS
     *      437    436  05 ACUP-NEW-DETAILS
     *   ------  -----
     *              873  = {@value #RECORD_LENGTH}
     * </pre>
     *
     * <p>Appended after the {@value NavigationContext#COMMAREA_LENGTH}-byte {@code CARDDEMO-COMMAREA} on
     * entry ({@code :890-892}) and on return ({@code :1011-1013}), so the pair occupies
     * {@value AccountUpdateRequest#TOTAL_COMMAREA_LENGTH} of
     * {@code 01 WS-COMMAREA PIC X(2000)}. Rule <strong>R6</strong> forbids keeping it server side, which is
     * why it is a member of this request rather than a session attribute.
     *
     * <p>This type <strong>transports</strong> the two snapshots and does not compare them.
     * {@code 9700-CHECK-CHANGE-IN-REC} is {@code AccountUpdateService}'s to implement (gates
     * <strong>G43</strong> and <strong>G51</strong>), and no version column, timestamp or generated key is
     * introduced anywhere - the optimistic check is the field-by-field comparison the COBOL already
     * performs (gate <strong>G44</strong>).
     *
     * @param changeAction the single-byte state, with its nine {@code 88}-level conditions
     * @param oldDetails   the {@code ACUP-OLD-DETAILS} snapshot, labelled {@link DetailGroup#OLD}
     * @param newDetails   the {@code ACUP-NEW-DETAILS} snapshot, labelled {@link DetailGroup#NEW}
     */
    public record CommArea(ChangeAction changeAction, Details oldDetails, Details newDetails) {

        /** Total width: {@code 1 + 436 + 436}. */
        public static final int RECORD_LENGTH =
                ChangeAction.RECORD_LENGTH + Details.RECORD_LENGTH + Details.RECORD_LENGTH;

        /** Absolute 0-based offset of {@code ACCT-UPDATE-SCREEN-DATA} and its single byte. */
        public static final int CHANGE_ACTION_OFFSET = 0;

        /** Absolute 0-based offset of {@code ACUP-OLD-DETAILS}: {@code 0 + 1}. */
        public static final int OLD_DETAILS_OFFSET = ChangeAction.RECORD_LENGTH;

        /** Absolute 0-based offset of {@code ACUP-NEW-DETAILS}: {@code 1 + 436}. */
        public static final int NEW_DETAILS_OFFSET = OLD_DETAILS_OFFSET + Details.RECORD_LENGTH;

        /** {@code ACUP-CHANGE-ACTION PIC X(1)} at offset {@value #CHANGE_ACTION_OFFSET}. */
        public static final FieldSpan ACUP_CHANGE_ACTION = FieldSpan.alphanumeric(
                ChangeAction.FIELD_NAME, CHANGE_ACTION_OFFSET, ChangeAction.RECORD_LENGTH);

        /**
         * The work area's whole layout: the change-action byte, then both snapshots' spans shifted into
         * place, then the three {@code 05}-level group overlays.
         *
         * <p>Built once and validated by {@link RecordLayout}'s own geometry check, which is the total-width
         * self-check that makes a mis-transcribed span fail immediately rather than shift every byte after
         * it: the storage spans must be contiguous from zero, every overlay must fall inside storage already
         * declared, and the storage must sum to exactly {@value #RECORD_LENGTH}.
         */
        public static final RecordLayout LAYOUT = buildLayout();

        /**
         * Checks that both snapshots are the groups they are supposed to be, and that nothing is
         * {@code null}.
         *
         * <p>The group check matters because {@link Details} carries its own name set: an {@code OLD}
         * snapshot stored in the {@code NEW} position would serialise to the right bytes but present the
         * wrong item names to a field-by-field diff, which is precisely the class of defect this translation
         * exists to avoid.
         *
         * @throws NullPointerException     if {@code changeAction} is {@code null}
         * @throws IllegalArgumentException if a snapshot is in the wrong position
         */
        public CommArea {
            Objects.requireNonNull(changeAction, "ACUP-CHANGE-ACTION is required; its unset state is "
                    + "LOW-VALUES, which is a byte and not a Java null");
            oldDetails = oldDetails == null ? Details.initialised(DetailGroup.OLD) : oldDetails;
            newDetails = newDetails == null ? Details.initialised(DetailGroup.NEW) : newDetails;
            if (oldDetails.group() != DetailGroup.OLD) {
                throw new IllegalArgumentException("The snapshot in the ACUP-OLD-DETAILS position is "
                        + "labelled " + oldDetails.groupName() + "; relabel it with "
                        + "asGroup(DetailGroup.OLD) rather than storing it under the wrong names");
            }
            if (newDetails.group() != DetailGroup.NEW) {
                throw new IllegalArgumentException("The snapshot in the ACUP-NEW-DETAILS position is "
                        + "labelled " + newDetails.groupName() + "; relabel it with "
                        + "asGroup(DetailGroup.NEW) rather than storing it under the wrong names");
            }
        }

        /**
         * The work area as the program starts it: {@code ACUP-CHANGE-ACTION} at its declared
         * {@code VALUE LOW-VALUES} and both snapshots all spaces.
         *
         * <p>This is the state in which {@link ChangeAction#isDetailsNotFetched()} is true, which
         * {@code INITIALIZE WS-THIS-PROGCOMMAREA} at {@code app/cbl/COACTUPC.cbl:884}, {@code :968} and
         * {@code :981} produces and {@code SET ACUP-DETAILS-NOT-FETCHED TO TRUE} at {@code :886} confirms.
         *
         * @return the initial work area
         */
        public static CommArea initialised() {
            return new CommArea(ChangeAction.initial(),
                    Details.initialised(DetailGroup.OLD),
                    Details.initialised(DetailGroup.NEW));
        }

        /**
         * This work area with a different change-action byte - the immutable form of
         * {@code SET ACUP-xxx TO TRUE}.
         *
         * @param replacement the new state
         * @return a new work area
         * @throws NullPointerException if {@code replacement} is {@code null}
         */
        public CommArea withChangeAction(ChangeAction replacement) {
            Objects.requireNonNull(replacement, "A change-action byte is required");
            return new CommArea(replacement, oldDetails, newDetails);
        }

        /**
         * This work area with a different {@code ACUP-OLD-DETAILS} snapshot.
         *
         * @param replacement the new snapshot, which must be labelled {@link DetailGroup#OLD}
         * @return a new work area
         * @throws IllegalArgumentException if {@code replacement} is labelled {@link DetailGroup#NEW}
         */
        public CommArea withOldDetails(Details replacement) {
            return new CommArea(changeAction, replacement, newDetails);
        }

        /**
         * This work area with a different {@code ACUP-NEW-DETAILS} snapshot.
         *
         * @param replacement the new snapshot, which must be labelled {@link DetailGroup#NEW}
         * @return a new work area
         * @throws IllegalArgumentException if {@code replacement} is labelled {@link DetailGroup#OLD}
         */
        public CommArea withNewDetails(Details replacement) {
            return new CommArea(changeAction, oldDetails, replacement);
        }

        /**
         * Renders the work area as exactly {@value #RECORD_LENGTH} bytes.
         *
         * @param codec the codec, carrying the code page explicitly
         * @return exactly {@value #RECORD_LENGTH} bytes
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public byte[] encode(FixedWidthCodec codec) {
            return toFixedWidthRecord(codec).toByteArray();
        }

        /**
         * Renders the work area as exactly {@value #RECORD_LENGTH} bytes in a named code page.
         *
         * @param charset the code page, stated explicitly and never defaulted
         * @return exactly {@value #RECORD_LENGTH} bytes
         * @throws NullPointerException if {@code charset} is {@code null}
         */
        public byte[] encode(Charset charset) {
            return encode(new FixedWidthCodec(charset));
        }

        /**
         * The work area as a record area under {@link #LAYOUT}, so a caller can read any span by name.
         *
         * @param codec the codec, carrying the code page explicitly
         * @return a {@value #RECORD_LENGTH}-byte area
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public FixedWidthRecord toFixedWidthRecord(FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A codec carrying the code page is required to render "
                    + "WS-THIS-PROGCOMMAREA; the platform default is never assumed");
            FixedWidthRecord area = codec.newRecord(LAYOUT);
            codec.writePicX(area, ACUP_CHANGE_ACTION, changeAction.value());
            oldDetails.acct().writeInto(area, OLD_DETAILS_OFFSET + Details.ACCT_DATA_OFFSET);
            oldDetails.cust().writeInto(area, OLD_DETAILS_OFFSET + Details.CUST_DATA_OFFSET);
            newDetails.acct().writeInto(area, NEW_DETAILS_OFFSET + Details.ACCT_DATA_OFFSET);
            newDetails.cust().writeInto(area, NEW_DETAILS_OFFSET + Details.CUST_DATA_OFFSET);
            return area;
        }

        /**
         * Reads a {@value #RECORD_LENGTH}-byte image back into a work area.
         *
         * @param image exactly {@value #RECORD_LENGTH} bytes
         * @param codec the codec, carrying the code page explicitly
         * @return the work area
         * @throws NullPointerException     if either argument is {@code null}
         * @throws IllegalArgumentException if {@code image} is not {@value #RECORD_LENGTH} bytes
         */
        public static CommArea decode(byte[] image, FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A codec carrying the image's code page is required to decode "
                    + "WS-THIS-PROGCOMMAREA; the platform default is never assumed");
            Objects.requireNonNull(image, "An image is required to decode WS-THIS-PROGCOMMAREA");
            if (image.length != RECORD_LENGTH) {
                throw new IllegalArgumentException("WS-THIS-PROGCOMMAREA is " + RECORD_LENGTH + " bytes - "
                        + ChangeAction.RECORD_LENGTH + " of change action plus two detail groups of "
                        + Details.RECORD_LENGTH + " - but this image is " + image.length + " byte(s)");
            }
            FixedWidthRecord area = codec.wrap(image, LAYOUT);
            return new CommArea(ChangeAction.of(codec.readPicX(area, ACUP_CHANGE_ACTION)),
                    Details.readFrom(area, DetailGroup.OLD, OLD_DETAILS_OFFSET),
                    Details.readFrom(area, DetailGroup.NEW, NEW_DETAILS_OFFSET));
        }

        /**
         * Reads a {@value #RECORD_LENGTH}-byte image back into a work area, in a named code page.
         *
         * @param image   exactly {@value #RECORD_LENGTH} bytes
         * @param charset the code page, stated explicitly
         * @return the work area
         * @throws NullPointerException if either argument is {@code null}
         */
        public static CommArea decode(byte[] image, Charset charset) {
            return decode(image, new FixedWidthCodec(charset));
        }

        /**
         * Assembles {@link #LAYOUT} from the change-action span, both groups' spans shifted into place and
         * the three {@code 05}-level group overlays.
         *
         * @return the validated layout
         */
        private static RecordLayout buildLayout() {
            List<FieldSpan> spans = new ArrayList<>();
            spans.add(ACUP_CHANGE_ACTION);
            spans.add(FieldSpan.redefining(ChangeAction.GROUP_NAME, CHANGE_ACTION_OFFSET,
                    ChangeAction.RECORD_LENGTH, PictureKind.ALPHANUMERIC));
            spans.addAll(shifted(DetailGroup.OLD, OLD_DETAILS_OFFSET));
            spans.add(FieldSpan.redefining(DetailGroup.OLD.groupName(), OLD_DETAILS_OFFSET,
                    Details.RECORD_LENGTH, PictureKind.ALPHANUMERIC));
            spans.addAll(shifted(DetailGroup.NEW, NEW_DETAILS_OFFSET));
            spans.add(FieldSpan.redefining(DetailGroup.NEW.groupName(), NEW_DETAILS_OFFSET,
                    Details.RECORD_LENGTH, PictureKind.ALPHANUMERIC));
            return RecordLayout.of(RECORD_LENGTH, spans.toArray(FieldSpan[]::new));
        }

        /**
         * One group's spans, moved from group-relative offsets to work-area-absolute ones.
         *
         * @param group  which group
         * @param offset where the group begins in the work area
         * @return the shifted spans, in declaration order
         */
        private static List<FieldSpan> shifted(DetailGroup group, int offset) {
            List<FieldSpan> shifted = new ArrayList<>();
            for (FieldSpan span : group.layout().spans()) {
                shifted.add(new FieldSpan(span.name(), span.offset() + offset, span.length(), span.kind(),
                        span.initialValue(), span.redefinition()));
            }
            return shifted;
        }
    }

    // =================================================================================================
    // The builder. It exists because the request is immutable and has 54 fields plus three carriers plus
    // 54 metadata pairs: a 111-argument constructor would be unusable and unreviewable, and a mutable bean
    // would give up the immutability this type's specification requires.
    //
    // It is also how Jackson binds inbound JSON, per @JsonDeserialize on the enclosing type. The property
    // names are the DFHMDF labels lower-cased, and @JsonPOJOBuilder(withPrefix = "") is what makes the
    // builder methods carry those names directly instead of a "with" prefix.
    // =================================================================================================

    /**
     * Collects the {@value AccountUpdateRequest#FIELD_COUNT} screen fields, the three conversation-state
     * carriers and the per-field metadata, and produces an immutable {@link AccountUpdateRequest}.
     *
     * <p>Every setter stores its argument <strong>verbatim</strong>. Nothing is padded, nothing is
     * truncated and nothing is rejected here: a value wider than its field survives to be reported by
     * {@link Size}, a {@code null} is completed to the field's declared width in spaces by
     * {@link #build()}, and {@code '*'}, spaces and {@code LOW-VALUES} are all legitimate values that
     * {@code app/cbl/COACTUPC.cbl:1051-1058} handles by name.
     *
     * <p>A builder is not thread safe and is not meant to be shared; the request it produces is both.
     */
    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {

        /** {@code TRNNAMEI}. */
        private String trnname;

        /** {@code TITLE01I}. */
        private String title01;

        /** {@code CURDATEI}. */
        private String curdate;

        /** {@code PGMNAMEI}. */
        private String pgmname;

        /** {@code TITLE02I}. */
        private String title02;

        /** {@code CURTIMEI}. */
        private String curtime;

        /** {@code ACCTSIDI}. */
        private String acctsid;

        /** {@code ACSTTUSI}. */
        private String acsttus;

        /** {@code OPNYEARI}. */
        private String opnyear;

        /** {@code OPNMONI}. */
        private String opnmon;

        /** {@code OPNDAYI}. */
        private String opnday;

        /** {@code ACRDLIMI}. */
        private String acrdlim;

        /** {@code EXPYEARI}. */
        private String expyear;

        /** {@code EXPMONI}. */
        private String expmon;

        /** {@code EXPDAYI}. */
        private String expday;

        /** {@code ACSHLIMI}. */
        private String acshlim;

        /** {@code RISYEARI}. */
        private String risyear;

        /** {@code RISMONI}. */
        private String rismon;

        /** {@code RISDAYI}. */
        private String risday;

        /** {@code ACURBALI}. */
        private String acurbal;

        /** {@code ACRCYCRI}. */
        private String acrcycr;

        /** {@code AADDGRPI}. */
        private String aaddgrp;

        /** {@code ACRCYDBI}. */
        private String acrcydb;

        /** {@code ACSTNUMI}. */
        private String acstnum;

        /** {@code ACTSSN1I}. */
        private String actssn1;

        /** {@code ACTSSN2I}. */
        private String actssn2;

        /** {@code ACTSSN3I}. */
        private String actssn3;

        /** {@code DOBYEARI}. */
        private String dobyear;

        /** {@code DOBMONI}. */
        private String dobmon;

        /** {@code DOBDAYI}. */
        private String dobday;

        /** {@code ACSTFCOI}. */
        private String acstfco;

        /** {@code ACSFNAMI}. */
        private String acsfnam;

        /** {@code ACSMNAMI}. */
        private String acsmnam;

        /** {@code ACSLNAMI}. */
        private String acslnam;

        /** {@code ACSADL1I}. */
        private String acsadl1;

        /** {@code ACSSTTEI}. */
        private String acsstte;

        /** {@code ACSADL2I}. */
        private String acsadl2;

        /** {@code ACSZIPCI}. */
        private String acszipc;

        /** {@code ACSCITYI}. */
        private String acscity;

        /** {@code ACSCTRYI}. */
        private String acsctry;

        /** {@code ACSPH1AI}. */
        private String acsph1a;

        /** {@code ACSPH1BI}. */
        private String acsph1b;

        /** {@code ACSPH1CI}. */
        private String acsph1c;

        /** {@code ACSGOVTI}. */
        private String acsgovt;

        /** {@code ACSPH2AI}. */
        private String acsph2a;

        /** {@code ACSPH2BI}. */
        private String acsph2b;

        /** {@code ACSPH2CI}. */
        private String acsph2c;

        /** {@code ACSEFTCI}. */
        private String acseftc;

        /** {@code ACSPFLGI}. */
        private String acspflg;

        /** {@code INFOMSGI}. */
        private String infomsg;

        /** {@code ERRMSGI}. */
        private String errmsg;

        /** {@code FKEYSI}. */
        private String fkeys;

        /** {@code FKEY05I}. */
        private String fkey05;

        /** {@code FKEY12I}. */
        private String fkey12;

        /** {@code WS-THIS-PROGCOMMAREA}; {@code null} becomes {@link CommArea#initialised()}. */
        private CommArea commArea;

        /** The {@code CVCRD01Y} work area; {@code null} becomes a freshly constructed one. */
        private CardScreenState cardScreenState;

        /** The {@code CARDDEMO-COMMAREA}; {@code null} is kept, and means the cold start. */
        private NavigationContext navigationContext;

        /** Per-field metadata; any field left out gets {@link FieldMetadata#unset()}. */
        private final Map<ScreenField, FieldMetadata> metadata = new EnumMap<>(ScreenField.class);

        /** Creates an empty builder. Public only through {@link AccountUpdateRequest#builder()}. */
        private Builder() {
        }

        /**
         * Sets {@code TRNNAMEI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder trnname(String value) {
            this.trnname = value;
            return this;
        }

        /**
         * Sets {@code TITLE01I}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder title01(String value) {
            this.title01 = value;
            return this;
        }

        /**
         * Sets {@code CURDATEI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder curdate(String value) {
            this.curdate = value;
            return this;
        }

        /**
         * Sets {@code PGMNAMEI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder pgmname(String value) {
            this.pgmname = value;
            return this;
        }

        /**
         * Sets {@code TITLE02I}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder title02(String value) {
            this.title02 = value;
            return this;
        }

        /**
         * Sets {@code CURTIMEI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder curtime(String value) {
            this.curtime = value;
            return this;
        }

        /**
         * Sets {@code ACCTSIDI}. {@code "*"}, spaces and {@code LOW-VALUES} are all legitimate.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder acctsid(String value) {
            this.acctsid = value;
            return this;
        }

        /**
         * Sets {@code ACSTTUSI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder acsttus(String value) {
            this.acsttus = value;
            return this;
        }

        /**
         * Sets {@code OPNYEARI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder opnyear(String value) {
            this.opnyear = value;
            return this;
        }

        /**
         * Sets {@code OPNMONI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder opnmon(String value) {
            this.opnmon = value;
            return this;
        }

        /**
         * Sets {@code OPNDAYI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder opnday(String value) {
            this.opnday = value;
            return this;
        }

        /**
         * Sets {@code ACRDLIMI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder acrdlim(String value) {
            this.acrdlim = value;
            return this;
        }

        /**
         * Sets {@code EXPYEARI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder expyear(String value) {
            this.expyear = value;
            return this;
        }

        /**
         * Sets {@code EXPMONI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder expmon(String value) {
            this.expmon = value;
            return this;
        }

        /**
         * Sets {@code EXPDAYI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder expday(String value) {
            this.expday = value;
            return this;
        }

        /**
         * Sets {@code ACSHLIMI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder acshlim(String value) {
            this.acshlim = value;
            return this;
        }

        /**
         * Sets {@code RISYEARI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder risyear(String value) {
            this.risyear = value;
            return this;
        }

        /**
         * Sets {@code RISMONI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder rismon(String value) {
            this.rismon = value;
            return this;
        }

        /**
         * Sets {@code RISDAYI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder risday(String value) {
            this.risday = value;
            return this;
        }

        /**
         * Sets {@code ACURBALI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder acurbal(String value) {
            this.acurbal = value;
            return this;
        }

        /**
         * Sets {@code ACRCYCRI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder acrcycr(String value) {
            this.acrcycr = value;
            return this;
        }

        /**
         * Sets {@code AADDGRPI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder aaddgrp(String value) {
            this.aaddgrp = value;
            return this;
        }

        /**
         * Sets {@code ACRCYDBI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder acrcydb(String value) {
            this.acrcydb = value;
            return this;
        }

        /**
         * Sets {@code ACSTNUMI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder acstnum(String value) {
            this.acstnum = value;
            return this;
        }

        /**
         * Sets {@code ACTSSN1I}. The {@code '999'} placeholder mask is a legitimate value.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder actssn1(String value) {
            this.actssn1 = value;
            return this;
        }

        /**
         * Sets {@code ACTSSN2I}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder actssn2(String value) {
            this.actssn2 = value;
            return this;
        }

        /**
         * Sets {@code ACTSSN3I}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder actssn3(String value) {
            this.actssn3 = value;
            return this;
        }

        /**
         * Sets {@code DOBYEARI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder dobyear(String value) {
            this.dobyear = value;
            return this;
        }

        /**
         * Sets {@code DOBMONI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder dobmon(String value) {
            this.dobmon = value;
            return this;
        }

        /**
         * Sets {@code DOBDAYI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder dobday(String value) {
            this.dobday = value;
            return this;
        }

        /**
         * Sets {@code ACSTFCOI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder acstfco(String value) {
            this.acstfco = value;
            return this;
        }

        /**
         * Sets {@code ACSFNAMI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder acsfnam(String value) {
            this.acsfnam = value;
            return this;
        }

        /**
         * Sets {@code ACSMNAMI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder acsmnam(String value) {
            this.acsmnam = value;
            return this;
        }

        /**
         * Sets {@code ACSLNAMI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder acslnam(String value) {
            this.acslnam = value;
            return this;
        }

        /**
         * Sets {@code ACSADL1I}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder acsadl1(String value) {
            this.acsadl1 = value;
            return this;
        }

        /**
         * Sets {@code ACSSTTEI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder acsstte(String value) {
            this.acsstte = value;
            return this;
        }

        /**
         * Sets {@code ACSADL2I}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder acsadl2(String value) {
            this.acsadl2 = value;
            return this;
        }

        /**
         * Sets {@code ACSZIPCI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder acszipc(String value) {
            this.acszipc = value;
            return this;
        }

        /**
         * Sets {@code ACSCITYI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder acscity(String value) {
            this.acscity = value;
            return this;
        }

        /**
         * Sets {@code ACSCTRYI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder acsctry(String value) {
            this.acsctry = value;
            return this;
        }

        /**
         * Sets {@code ACSPH1AI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder acsph1a(String value) {
            this.acsph1a = value;
            return this;
        }

        /**
         * Sets {@code ACSPH1BI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder acsph1b(String value) {
            this.acsph1b = value;
            return this;
        }

        /**
         * Sets {@code ACSPH1CI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder acsph1c(String value) {
            this.acsph1c = value;
            return this;
        }

        /**
         * Sets {@code ACSGOVTI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder acsgovt(String value) {
            this.acsgovt = value;
            return this;
        }

        /**
         * Sets {@code ACSPH2AI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder acsph2a(String value) {
            this.acsph2a = value;
            return this;
        }

        /**
         * Sets {@code ACSPH2BI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder acsph2b(String value) {
            this.acsph2b = value;
            return this;
        }

        /**
         * Sets {@code ACSPH2CI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder acsph2c(String value) {
            this.acsph2c = value;
            return this;
        }

        /**
         * Sets {@code ACSEFTCI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder acseftc(String value) {
            this.acseftc = value;
            return this;
        }

        /**
         * Sets {@code ACSPFLGI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder acspflg(String value) {
            this.acspflg = value;
            return this;
        }

        /**
         * Sets {@code INFOMSGI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder infomsg(String value) {
            this.infomsg = value;
            return this;
        }

        /**
         * Sets {@code ERRMSGI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder errmsg(String value) {
            this.errmsg = value;
            return this;
        }

        /**
         * Sets {@code FKEYSI}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder fkeys(String value) {
            this.fkeys = value;
            return this;
        }

        /**
         * Sets {@code FKEY05I}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder fkey05(String value) {
            this.fkey05 = value;
            return this;
        }

        /**
         * Sets {@code FKEY12I}.
         *
         * @param value the value, stored verbatim
         * @return this builder
         */
        public Builder fkey12(String value) {
            this.fkey12 = value;
            return this;
        }

        /**
         * Sets {@code WS-THIS-PROGCOMMAREA}.
         *
         * @param value the work area; {@code null} becomes {@link CommArea#initialised()}
         * @return this builder
         */
        public Builder commArea(CommArea value) {
            this.commArea = value;
            return this;
        }

        /**
         * Sets the {@code CVCRD01Y} work area.
         *
         * @param value the work area; {@code null} becomes a freshly constructed one, and a
         *              non-{@code null} one is copied by {@link #build()}
         * @return this builder
         */
        public Builder cardScreenState(CardScreenState value) {
            this.cardScreenState = value;
            return this;
        }

        /**
         * Sets the {@code CARDDEMO-COMMAREA}.
         *
         * @param value the communication area, or {@code null} for the {@code EIBCALEN = 0} cold start,
         *              which is kept rather than completed
         * @return this builder
         */
        public Builder navigationContext(NavigationContext value) {
            this.navigationContext = value;
            return this;
        }

        /**
         * Sets one field by enumeration, for a caller that addresses fields generically - a differ, a
         * fixture loader, {@link AccountUpdateRequest#normalize(FixedWidthCodec)}.
         *
         * @param field which field
         * @param value the value, stored verbatim
         * @return this builder
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public Builder value(ScreenField field, String value) {
            Objects.requireNonNull(field, "A ScreenField is required to set a field's value");
            switch (field) {
                case TRNNAME -> trnname = value;
                case TITLE01 -> title01 = value;
                case CURDATE -> curdate = value;
                case PGMNAME -> pgmname = value;
                case TITLE02 -> title02 = value;
                case CURTIME -> curtime = value;
                case ACCTSID -> acctsid = value;
                case ACSTTUS -> acsttus = value;
                case OPNYEAR -> opnyear = value;
                case OPNMON -> opnmon = value;
                case OPNDAY -> opnday = value;
                case ACRDLIM -> acrdlim = value;
                case EXPYEAR -> expyear = value;
                case EXPMON -> expmon = value;
                case EXPDAY -> expday = value;
                case ACSHLIM -> acshlim = value;
                case RISYEAR -> risyear = value;
                case RISMON -> rismon = value;
                case RISDAY -> risday = value;
                case ACURBAL -> acurbal = value;
                case ACRCYCR -> acrcycr = value;
                case AADDGRP -> aaddgrp = value;
                case ACRCYDB -> acrcydb = value;
                case ACSTNUM -> acstnum = value;
                case ACTSSN1 -> actssn1 = value;
                case ACTSSN2 -> actssn2 = value;
                case ACTSSN3 -> actssn3 = value;
                case DOBYEAR -> dobyear = value;
                case DOBMON -> dobmon = value;
                case DOBDAY -> dobday = value;
                case ACSTFCO -> acstfco = value;
                case ACSFNAM -> acsfnam = value;
                case ACSMNAM -> acsmnam = value;
                case ACSLNAM -> acslnam = value;
                case ACSADL1 -> acsadl1 = value;
                case ACSSTTE -> acsstte = value;
                case ACSADL2 -> acsadl2 = value;
                case ACSZIPC -> acszipc = value;
                case ACSCITY -> acscity = value;
                case ACSCTRY -> acsctry = value;
                case ACSPH1A -> acsph1a = value;
                case ACSPH1B -> acsph1b = value;
                case ACSPH1C -> acsph1c = value;
                case ACSGOVT -> acsgovt = value;
                case ACSPH2A -> acsph2a = value;
                case ACSPH2B -> acsph2b = value;
                case ACSPH2C -> acsph2c = value;
                case ACSEFTC -> acseftc = value;
                case ACSPFLG -> acspflg = value;
                case INFOMSG -> infomsg = value;
                case ERRMSG -> errmsg = value;
                case FKEYS -> fkeys = value;
                case FKEY05 -> fkey05 = value;
                case FKEY12 -> fkey12 = value;
            }
            return this;
        }

        /**
         * Sets one field's metadata pair.
         *
         * @param field       which field
         * @param replacement the pair, or {@code null} to leave the field at {@link FieldMetadata#unset()}
         * @return this builder
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public Builder metadata(ScreenField field, FieldMetadata replacement) {
            Objects.requireNonNull(field, "A ScreenField is required to set a field's metadata");
            if (replacement == null) {
                metadata.remove(field);
            } else {
                metadata.put(field, replacement);
            }
            return this;
        }

        /**
         * Builds the immutable request.
         *
         * @return a new {@link AccountUpdateRequest}, never {@code null}
         */
        public AccountUpdateRequest build() {
            return new AccountUpdateRequest(this);
        }
    }
}
