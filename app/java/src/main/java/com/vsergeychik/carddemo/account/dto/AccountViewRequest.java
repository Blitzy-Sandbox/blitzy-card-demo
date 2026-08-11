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
 * The inbound payload of {@code GET /api/accounts/{acctId}} - the account detail screen, CSD
 * transaction {@code CAVW}, backed by {@code app/cbl/COACTVWC.cbl} (941 lines).
 *
 * <p>This type is a 1:1 projection of the <em>input</em> half of one BMS mapset. Two source files are
 * its only authorities and neither is ever written - the legacy tree is the sole oracle a parity
 * migration has, so everything under {@code app/cbl/}, {@code app/cpy/}, {@code app/cpy-bms/} and
 * {@code app/bms/} is read-only here (practice <strong>B3</strong>):
 *
 * <ul>
 *   <li>{@code app/cpy-bms/COACTVW.CPY} (464 lines) - the symbolic map. Group {@code 01 CACTVWAI.}
 *       opens at line 17 and its {@code xxxI} items run from line 24 to line 240;
 *       {@code 01 CACTVWAO REDEFINES CACTVWAI.} follows at line 241.</li>
 *   <li>{@code app/bms/COACTVW.bms} (378 lines) - the mapset that generated it, ending with
 *       {@code DFHMSD TYPE=FINAL} at line 374.</li>
 * </ul>
 *
 * <h2>Thirty-seven fields, and why exactly thirty-seven</h2>
 *
 * <p>{@code app/bms/COACTVW.bms} declares <strong>100</strong> {@code DFHMDF} entries of which
 * <strong>37 carry a name label in column 1</strong>. The other 63 are unnamed literal entries - screen
 * furniture such as the {@code INITIAL='Tran:'} label at line 29 - which BMS paints but never reports
 * back. An unnamed entry generates no symbolic-map item and holds no value, so it gets no Java member.
 *
 * <pre>
 *   #  DFHMDF   .bms  LENGTH  POS        xxxI item     PICTURE        .CPY  data offset
 *   -  -------  ----  ------  ---------  ------------  -------------  ----  -----------
 *   1  TRNNAME    34       4  (1, 7)     TRNNAMEI      X(4)             24       19
 *   2  TITLE01    38      40  (1,21)     TITLE01I      X(40)            30       30
 *   3  CURDATE    47       8  (1,71)     CURDATEI      X(8)             36       77
 *   4  PGMNAME    57       8  (2, 7)     PGMNAMEI      X(8)             42       92
 *   5  TITLE02    61      40  (2,21)     TITLE02I      X(40)            48      107
 *   6  CURTIME    70       8  (2,71)     CURTIMEI      X(8)             54      154
 *   7  ACCTSID    84      11  (5,38)     ACCTSIDI      99999999999      60      169
 *   8  ACSTTUS    97       1  (5,70)     ACSTTUSI      X(1)             66      187
 *   9  ADTOPEN   107      10  (6,17)     ADTOPENI      X(10)            72      195
 *  10  ACRDLIM   117      15  (6,61)     ACRDLIMI      X(15)            78      212
 *  11  AEXPDT    128      10  (7,17)     AEXPDTI       X(10)            84      234
 *  12  ACSHLIM   138      15  (7,61)     ACSHLIMI      X(15)            90      251
 *  13  AREISDT   149      10  (8,17)     AREISDTI      X(10)            96      273
 *  14  ACURBAL   159      15  (8,61)     ACURBALI      X(15)           102      290
 *  15  ACRCYCR   171      15  (9,61)     ACRCYCRI      X(15)           108      312
 *  16  AADDGRP   182      10  (10,23)    AADDGRPI      X(10)           114      334
 *  17  ACRCYDB   192      15  (10,61)    ACRCYDBI      X(15)           120      351
 *  18  ACSTNUM   207       9  (12,23)    ACSTNUMI      X(9)            126      373
 *  19  ACSTSSN   216      12  (12,54)    ACSTSSNI      X(12)           132      389
 *  20  ACSTDOB   225      10  (13,23)    ACSTDOBI      X(10)           138      408
 *  21  ACSTFCO   234       3  (13,61)    ACSTFCOI      X(3)            144      425
 *  22  ACSFNAM   251      25  (15, 1)    ACSFNAMI      X(25)           150      435
 *  23  ACSMNAM   256      25  (15,28)    ACSMNAMI      X(25)           156      467
 *  24  ACSLNAM   261      25  (15,55)    ACSLNAMI      X(25)           162      499
 *  25  ACSADL1   268      50  (16,10)    ACSADL1I      X(50)           168      531
 *  26  ACSSTTE   277       2  (16,73)    ACSSTTEI      X(2)            174      588
 *  27  ACSADL2   282      50  (17,10)    ACSADL2I      X(50)           180      597
 *  28  ACSZIPC   291       5  (17,73)    ACSZIPCI      X(5)            186      654
 *  29  ACSCITY   301      50  (18,10)    ACSCITYI      X(50)           192      666
 *  30  ACSCTRY   310       3  (18,73)    ACSCTRYI      X(3)            198      723
 *  31  ACSPHN1   319      13  (19,10)    ACSPHN1I      X(13)           204      733
 *  32  ACSGOVT   326      20  (19,58)    ACSGOVTI      X(20)           210      753
 *  33  ACSPHN2   335      13  (20,10)    ACSPHN2I      X(13)           216      780
 *  34  ACSEFTC   342      10  (20,41)    ACSEFTCI      X(10)           222      800
 *  35  ACSPFLG   351       1  (20,78)    ACSPFLGI      X(1)            228      817
 *  36  INFOMSG   356      45  (22,23)    INFOMSGI      X(45)           234      825
 *  37  ERRMSG    365      78  (23, 1)    ERRMSGI       X(78)           240      877
 *   -  -------             ------                                            -----------
 *                             684  = the sum of both width columns, which agree      955
 * </pre>
 *
 * <p>The two width columns are independent transcriptions of one contract - one from the mapset's
 * {@code LENGTH=} operands, one from the symbolic map's {@code PICTURE} clauses - and they agree field
 * for field and in total. Every payload member below therefore traces to a name-labelled
 * {@code DFHMDF} entry and every width to a symbolic-map {@code PICTURE} clause, which is the whole of
 * gate <strong>G9</strong>.
 *
 * <p><strong>Declaration order is the copybook's, not a tidied one.</strong> Reading rows 9 to 17
 * gives {@code ADTOPEN, ACRDLIM, AEXPDT, ACSHLIM, AREISDT, ACURBAL, ACRCYCR, AADDGRP, ACRCYDB} -
 * the screen's left column and right column interleaved, row by row, because that is the order BMS
 * lays the storage down. Sorting them into a "logical" grouping would move every byte after the first
 * move and silently invalidate {@link #toGroupImage(FixedWidthCodec)}.
 *
 * <p>Thirty-seven is also a ceiling. {@code COACTVW} declares no {@code FKEYS}, no {@code FKEY05}, no
 * {@code FKEY12} and no {@code PAGENO}; those belong to sibling mapsets. Adding one for symmetry would
 * introduce a member tracing to no {@code DFHMDF} entry and fail gate <strong>G9</strong>.
 *
 * <h2>Input items only: the {@code xxxI} rule</h2>
 *
 * <p>A symbolic map declares two groups over the same storage, and a {@code REDEFINES} is obliged to
 * describe the same bytes, so both strides are identical:
 *
 * <pre>
 *   input  (CACTVWAI)                       bytes   output (CACTVWAO)              bytes
 *   -------------------------------------   -----   ----------------------------   -----
 *   02 xxxL COMP PIC S9(4)                      2   02 FILLER PICTURE X(3)             3
 *   02 xxxF PICTURE X                           1
 *   02 FILLER REDEFINES xxxF                    0   (an overlay adds no storage)
 *      03 xxxA PICTURE X
 *   02 FILLER PICTURE X(4)                      4   02 xxxC PICTURE X                  1
 *                                                   02 xxxP PICTURE X                  1
 *                                                   02 xxxH PICTURE X                  1
 *                                                   02 xxxV PICTURE X                  1
 *   02 xxxI PIC ...                             n   02 xxxO PIC X(n)                   n
 *   -------------------------------------   -----   ----------------------------   -----
 *                                             7+n                                    7+n
 * </pre>
 *
 * <p>The output group's leading 3-byte {@code FILLER} overlays the input's {@code xxxL} (2) plus
 * {@code xxxF} (1), and its {@code xxxC}/{@code xxxP}/{@code xxxH}/{@code xxxV} quad overlays the
 * input's {@code FILLER X(4)}. That quad exists because {@code app/bms/COACTVW.bms:26-27} declares
 * {@code DSATTS=(COLOR,HILIGHT,PS,VALIDN)} and {@code MAPATTS=(COLOR,HILIGHT,PS,VALIDN)}, so C is
 * COLOR, P is PS, H is HILIGHT and V is VALIDN - the letters are in the order BMS lays the bytes down,
 * not the order the operands are written.
 *
 * <p><strong>This class carries the 37 {@code xxxI} items and nothing else.</strong> The 37
 * {@code xxxO} items and the colour, highlight, programmed-symbol and validation items beside them
 * belong to {@code AccountViewResponse}. The split is not cosmetic: {@code COACTVWC} writes its
 * display values and its colours into {@code CACTVWAO} - {@code app/cbl/COACTVWC.cbl:436-439},
 * {@code :447}, {@code :453}, {@code :532}, {@code :534}, {@code :555}, {@code :558}, {@code :563-564}
 * all name the {@code O} group - and reads the operator's typing out of {@code CACTVWAI} at
 * {@code :628-632}. A request carrying output items would no longer describe what the terminal sent.
 *
 * <h2>The 955-byte group image</h2>
 *
 * <p>The whole input group is exactly {@value #GROUP_LENGTH} bytes: {@value #TIOAPFX_LENGTH} for the
 * prefix, plus {@value #FIELD_COUNT} fields each carrying {@value #FIELD_OVERHEAD} bytes of length,
 * flag and extended-attribute storage, plus {@value #PAYLOAD_LENGTH} bytes of field data - that is
 * 12 + 37 &times; 7 + 684 = 955. {@link #toGroupImage(FixedWidthCodec)} renders it and
 * {@link #fromGroupImage(byte[], FixedWidthCodec)} reads it back, so {@code parity/ParityHarness} can
 * fingerprint the area a {@code RECEIVE MAP} would have filled.
 *
 * <p>None of those numbers is asserted only in this prose. {@link #PAYLOAD_LENGTH} and
 * {@link #GROUP_LENGTH} are computed from the 37 width constants rather than written down, and the
 * static initialiser below walks the {@link ScreenField} strides and refuses to load the class unless
 * the walk lands exactly on {@value #GROUP_LENGTH}. A transcription slip therefore fails on first use
 * instead of shifting every byte after it.
 *
 * <h2>{@code ACCTSID} is a {@code String}, and it is the only field an operator types</h2>
 *
 * <p>{@code app/cpy-bms/COACTVW.CPY:60} declares {@code 02 ACCTSIDI PIC 99999999999.} - eleven
 * {@code 9}s, the one item of the 37 whose {@code xxxI} {@code PICTURE} is not {@code X}. It comes from
 * {@code app/bms/COACTVW.bms:88} {@code PICIN='99999999999'}, and the same entry is the only one on the
 * screen declared {@code ATTRB=(FSET,IC,NORM,UNPROT)} with {@code VALIDN=(MUSTFILL)}: the insertion
 * cursor starts there and it is the sole unprotected field. The other 36 are display-only, which is why
 * {@link #withAccountFilter(String)} needs one argument.
 *
 * <p>It is nonetheless an eleven-character {@link String} here, never an {@code int} or a {@code long},
 * and the program says why:
 *
 * <pre>
 *   IF  ACCTSIDI OF CACTVWAI = '*'                       *&gt; COACTVWC.cbl:628
 *   OR  ACCTSIDI OF CACTVWAI = SPACES                    *&gt; :629
 *       MOVE LOW-VALUES           TO  CC-ACCT-ID         *&gt; :630
 *   ELSE
 *       MOVE ACCTSIDI OF CACTVWAI TO  CC-ACCT-ID         *&gt; :632
 *   END-IF
 * </pre>
 *
 * <p>A COBOL display-numeric item is eleven raw characters in storage, so comparing it with
 * {@code '*'} and with {@code SPACES} is legal and the program depends on both. Neither value fits a
 * Java integral type, so binding this field to one would make the {@code :628-629} branch
 * unreproducible - and it is a live branch, reached whenever the operator clears the field or types the
 * wildcard. The numeric {@code PICIN} and {@code VALIDN=(MUSTFILL)} are consequently carried as
 * <em>metadata</em> - documented here and on {@link ScreenField#ACCTSID} - and never as a Java type or
 * a digits-only constraint.
 *
 * <p>The asymmetry is real and deliberate: the output twin {@code ACCTSIDO} at
 * {@code app/cpy-bms/COACTVW.CPY:284} is plain {@code PIC X(11)}, which is what lets
 * {@code app/cbl/COACTVWC.cbl:563} move {@code '*'} into it. And the neighbouring update screen does
 * not share this input contract at all: {@code app/bms/COACTUP.bms:84} declares
 * {@code ACCTSID DFHMDF ATTRB=(IC,UNPROT), HILIGHT=UNDERLINE, LENGTH=11, POS=(5,38)} - the same width
 * and position, but <strong>no</strong> {@code PICIN}, <strong>no</strong> {@code VALIDN=(MUSTFILL)},
 * no {@code COLOR} and no {@code FSET}. The two screens are not interchangeable.
 *
 * <h2>{@code xxxL}, {@code xxxF} and {@code xxxA} are metadata, not payload</h2>
 *
 * <p>Three of the five items in each input field are not data, and they are deliberately kept off the
 * JSON wire - see {@link ScreenFieldMetadata}, {@link #metadata(ScreenField)} and {@link #metadata()},
 * all {@link JsonIgnore}d. They are not decoration either: {@code COACTVWC} writes both of them into
 * the <em>input</em> group in paragraph {@code 1300-SETUP-SCREEN-ATTRS}, so they have to exist
 * somewhere.
 *
 * <ul>
 *   <li>{@code xxxL COMP PIC S9(4)} is the length CICS reports for a received field, which is how a
 *       program answers "was this field entered?", and is also the cursor-positioning channel.
 *       {@code app/cbl/COACTVWC.cbl:549} and {@code :551} both
 *       {@code MOVE -1 TO ACCTSIDL OF CACTVWAI}. Being signed matters: an unsigned length cannot hold
 *       {@value ScreenFieldMetadata#CURSOR_HERE}.</li>
 *   <li>{@code xxxA}, the {@code REDEFINES} view of the flag byte {@code xxxF}, is the attribute the
 *       program assigns: {@code app/cbl/COACTVWC.cbl:543} is
 *       {@code MOVE DFHBMFSE TO ACCTSIDA OF CACTVWAI}. It is written by the server onto the input
 *       group, so {@link ScreenFieldMetadata#setAttribute(byte)} exists and the holder is returned
 *       live rather than copied.</li>
 * </ul>
 *
 * <p>Promoting either onto the payload would add members tracing to no {@code DFHMDF} field and break
 * gate <strong>G9</strong>; dropping them would lose behaviour the COBOL performs. Holding them as
 * non-serialised metadata is the only reading that keeps both true.
 *
 * <h2>Conversation state travels in the payload</h2>
 *
 * <p>{@code COACTVWC} copies {@code CVCRD01Y} at line 207 and {@code COCOM01Y} at line 211, and both
 * are conversational state. CICS is pseudo-conversational, so this class carries them as ordinary
 * payload members - {@link #getCardScreenState()} and {@link #getNavigationContext()} - and holds no
 * servlet session, no session-scoped attribute, no cache, no thread-bound storage and no static
 * mutable state. Nothing here can outlive the request that created it, which is gate
 * <strong>G37</strong> and rule <strong>R6</strong>. The property is structural rather than a matter of
 * trust: a search of this file for any such construct finds none.
 *
 * <p>{@link NavigationContext#pgmContext()} is the flag that matters most on an inbound request, and on
 * this screen it is the spine of the program. {@code app/cbl/COACTVWC.cbl:323-383} is one
 * {@code EVALUATE TRUE} over exactly four arms - {@code WHEN CCARD-AID-PFK03} transfers control,
 * {@code WHEN CDEMO-PGM-ENTER} paints the screen and returns,
 * {@code WHEN CDEMO-PGM-REENTER} validates what was typed and then reads the account, and
 * {@code WHEN OTHER} abends with {@code 'UNEXPECTED DATA SCENARIO'}. {@link #isEnter()} and
 * {@link #isReenter()} make the middle two reachable from this type, which is gate
 * <strong>G38</strong>; re-entry is also the conjunct that lets a field be highlighted at all, since
 * {@code :561-565} applies {@code '*'} and {@code DFHRED} only under
 * {@code IF FLG-ACCTFILTER-BLANK AND CDEMO-PGM-REENTER}.
 *
 * <h2>Facts recorded rather than repaired</h2>
 *
 * <p>Practice <strong>B4</strong> requires a conflict to be documented, not quietly fixed. Four are
 * recorded here exactly as the sources read.
 *
 * <ol>
 *   <li><strong>The mapset's declaration is not where the migration plan places it.</strong> The plan
 *       summarises all seventeen mapsets as declaring
 *       {@code DFHMSD CTRL=(ALARM,FREEKB) EXTATT=YES}. Read directly,
 *       {@code app/bms/COACTVW.bms:20-24} declares
 *       {@code COACTVW DFHMSD LANG=COBOL, MODE=INOUT, STORAGE=AUTO, TIOAPFX=YES, TYPE=&&SYSPARM} -
 *       <strong>no {@code CTRL=} and no {@code EXTATT=}</strong> - and it is
 *       {@code app/bms/COACTVW.bms:25-28},
 *       {@code CACTVWA DFHMDI CTRL=(FREEKB), DSATTS=(COLOR,HILIGHT,PS,VALIDN),
 *       MAPATTS=(COLOR,HILIGHT,PS,VALIDN), SIZE=(24,80)}, that carries them. The {@code CTRL} operand
 *       is {@code (FREEKB)} alone, without {@code ALARM}. It is {@code DSATTS}/{@code MAPATTS} that
 *       generate the output {@code xxxC}/{@code xxxP}/{@code xxxH}/{@code xxxV} quad, and
 *       {@code TIOAPFX=YES} that generates the {@value #TIOAPFX_LENGTH}-byte prefix.
 *       {@code SIZE=(24,80)} is confirmed.</li>
 *   <li><strong>The group name drops a letter.</strong> The mapset is {@code COACTVW} and the map is
 *       {@code CACTVWA}, but the symbolic-map groups are {@code CACTVWAI} and {@code CACTVWAO} - the
 *       leading {@code O} of {@code COACTVW} is gone, because BMS builds the group name from the
 *       seven-character map name plus an {@code I} or {@code O} suffix. Anyone searching the copybook
 *       for {@code COACTVWI} finds nothing.</li>
 *   <li><strong>Two message fields are wider here than the working-storage items that feed them.</strong>
 *       {@code app/cbl/COACTVWC.cbl:110} declares {@code WS-INFO-MSG PIC X(40)} and {@code :117}
 *       declares {@code WS-RETURN-MSG PIC X(75)}, and {@code :534} and {@code :532} move them into
 *       {@code INFOMSGO} and {@code ERRMSGO}, which are {@value #INFOMSG_LENGTH} and
 *       {@value #ERRMSG_LENGTH}. COBOL right-pads an alphanumeric receiver, so the map widths are 45
 *       and 78 and the sending widths are 40 and 75. The <em>map</em> widths are the contract; 40 and
 *       75 must never appear here.</li>
 *   <li><strong>This type does not use the module's diagnostic-redaction helper.</strong> Sibling
 *       payloads route {@link #toString()} through {@code common/SensitiveDiagnostics}. This one
 *       renders every value as stored, for two reasons. Its specification requires it: the COBOL shows
 *       {@code ACSTSSN}, {@code ACSTDOB} and {@code ACSGOVT} on a 3270 in the clear, and adding
 *       masking, redaction, a serialisation filter or a {@link JsonIgnore} to a payload member would be
 *       an unrequested behaviour change (practice <strong>B6</strong>) - as would weakening anything.
 *       And {@code common/SensitiveDiagnostics} is outside this file's declared dependency set, so
 *       importing it is not available in the first place. The divergence is deliberate and recorded
 *       here rather than left to be discovered.</li>
 * </ol>
 *
 * <p>For the same reason the account screens are not factored into a shared header type. They disagree,
 * and the disagreement <em>is</em> the contract: {@code COACTVW} has 37 fields and {@code COACTUP} has
 * 54, and their {@code ACCTSID} entries differ in {@code ATTRB}, {@code PICIN} and {@code VALIDN}. A
 * common base class would collapse those differences into one and quietly break gate
 * <strong>G9</strong> on both screens.
 *
 * <h2>Widths are applied deliberately, never by assignment</h2>
 *
 * <p>Every field is a {@link String}, and a COBOL alphanumeric receiver is filled from its leftmost
 * position, padded on the right with spaces when the sending value is short and truncated on the right
 * when it is long. A plain Java assignment does neither, and the resulting parity defect is invisible
 * at the call site - so the setters here store what they are given <em>unaltered</em>, and the
 * {@code MOVE} itself is performed only where it is asked for, by
 * {@link FixedWidthCodec#movePicX(String, int)}, through {@link #image(ScreenField, FixedWidthCodec)},
 * {@link #normalize(FixedWidthCodec)} and {@link #toGroupImage(FixedWidthCodec)} (practice
 * <strong>B11</strong>). Reads are never trimmed: a {@code PIC X} field's trailing spaces are part of
 * its value and the parity differ compares them.
 *
 * <p>The five money fields - {@code ACRDLIM}, {@code ACSHLIM}, {@code ACURBAL}, {@code ACRCYCR} and
 * {@code ACRCYDB} - are {@code PIC X(15)} on the input side and are held as {@link String}. Their
 * {@code PICOUT='+ZZZ,ZZZ,ZZZ.99'} edit mask applies to the {@code xxxO} twins and is
 * {@code AccountViewResponse}'s concern. Nothing here parses them into a number, so this file declares
 * no scaled decimal, no {@code double}, no {@code float} and no rounding mode at all - gates
 * <strong>G22</strong> and <strong>G24</strong> have nothing on this screen to decide about.
 *
 * <p>Validation is {@link Size} and nothing else, with every {@code max} taken from a {@code PICTURE}
 * width. No {@code NotBlank}, no {@code Pattern}, no digit or range check - not even on
 * {@code ACCTSID}, whose {@code PICIN} is numeric - because {@code COACTVWC} performs its own edits in
 * its own paragraphs and a constraint rejecting {@code '*'} or spaces would break
 * {@code app/cbl/COACTVWC.cbl:628-629} outright. Pre-empting an edit changes which message the operator
 * sees and in what order, which is a parity violation rather than a hardening; cross-field rules stay
 * in the controller and service, which is gate <strong>G51</strong>.
 *
 * <h2>Construction and thread safety</h2>
 *
 * <p>The class is deliberately mutable, because a BMS map area is: {@code COACTVWC} assigns into
 * {@code CACTVWAI} at {@code app/cbl/COACTVWC.cbl:543}, {@code :549} and {@code :551} part-way through
 * processing. An instance is consequently <strong>not</strong> thread safe and must stay confined to
 * the request that owns it, exactly as a CICS map area is confined to its task. It needs no Spring
 * context and no framework to build - {@link #AccountViewRequest()} plus the setters,
 * {@link #withAccountFilter(String)}, or {@link #AccountViewRequest(AccountViewRequest)} are enough for
 * a plain JUnit 5 test, a {@code MockMvc} test and the {@code COACTVWC} parity case alike (practice
 * <strong>B10</strong>).
 *
 * @see CardScreenState the {@code CVCRD01Y} work area this request carries
 * @see NavigationContext the {@code COCOM01Y} communication area this request carries
 * @see FixedWidthCodec the sole home of the {@code PIC X} move rule
 *
 * <h2>Members this request tolerates without declaring</h2>
 *
 * <p>The {@code @JsonIgnoreProperties} below names the members the paired response carries that this
 * request does not declare. They are tolerated so a client can send the body it was just handed straight
 * back: rule R6 and gate G37 put the whole conversation in the payload, which makes the next request the
 * previous response. {@code ignoreUnknown} stays at its default of {@code false}, so every <em>other</em>
 * unrecognised name is still refused with the offending field named in the error envelope. Each tolerated
 * member is recomputed by the server on every path, so the value that arrives here is discarded and
 * cannot steer a branch. The names live in {@link com.vsergeychik.carddemo.common.ResponseOnlyMembers},
 * which explains each one.
 */
@JsonIgnoreProperties({
        ResponseOnlyMembers.NEXT_PROGRAM,
        ResponseOnlyMembers.NEXT_MAPSET,
        ResponseOnlyMembers.NEXT_MAP,
        ResponseOnlyMembers.SCREEN_METADATA})
public final class AccountViewRequest {

    // =================================================================================================
    // Field widths, transcribed one by one from the xxxI PICTURE clauses of app/cpy-bms/COACTVW.CPY and
    // cross-checked against the LENGTH= operands of app/bms/COACTVW.bms. All 37 pairs agree. Every width
    // in this file comes from exactly one of these constants; no width is written twice and none is
    // written inline (practice B8).
    //
    // The order below is the copybook's storage order, which is the mapset's declaration order, which is
    // the order the fields appear on the 24 x 80 screen reading left column then right column, row by
    // row. It is not sorted, grouped or otherwise improved.
    // =================================================================================================

    /** Width of {@code TRNNAMEI PIC X(4)}, {@code COACTVW.CPY:24}; {@code TRNNAME LENGTH=4}. */
    public static final int TRNNAME_LENGTH = 4;

    /** Width of {@code TITLE01I PIC X(40)}, {@code COACTVW.CPY:30}; {@code TITLE01 LENGTH=40}. */
    public static final int TITLE01_LENGTH = 40;

    /** Width of {@code CURDATEI PIC X(8)}, {@code COACTVW.CPY:36}; {@code CURDATE LENGTH=8}. */
    public static final int CURDATE_LENGTH = 8;

    /** Width of {@code PGMNAMEI PIC X(8)}, {@code COACTVW.CPY:42}; {@code PGMNAME LENGTH=8}. */
    public static final int PGMNAME_LENGTH = 8;

    /** Width of {@code TITLE02I PIC X(40)}, {@code COACTVW.CPY:48}; {@code TITLE02 LENGTH=40}. */
    public static final int TITLE02_LENGTH = 40;

    /** Width of {@code CURTIMEI PIC X(8)}, {@code COACTVW.CPY:54}; {@code CURTIME LENGTH=8}. */
    public static final int CURTIME_LENGTH = 8;

    /**
     * Width of {@code ACCTSIDI PIC 99999999999}, {@code COACTVW.CPY:60}; {@code ACCTSID LENGTH=11}.
     *
     * <p>Eleven {@code 9}s rather than {@code X(11)} - the only one of the 37 whose {@code xxxI}
     * {@code PICTURE} is not alphanumeric, from {@code app/bms/COACTVW.bms:88} {@code PICIN='99999999999'}.
     * A display-numeric item still occupies eleven characters of storage, so the width is 11 either way
     * and {@link #getAcctsid()} is a {@link String}. The output twin {@code ACCTSIDO} at
     * {@code COACTVW.CPY:284} is plain {@code PIC X(11)}.
     */
    public static final int ACCTSID_LENGTH = 11;

    /** Width of {@code ACSTTUSI PIC X(1)}, {@code COACTVW.CPY:66}; {@code ACSTTUS LENGTH=1}. */
    public static final int ACSTTUS_LENGTH = 1;

    /** Width of {@code ADTOPENI PIC X(10)}, {@code COACTVW.CPY:72}; {@code ADTOPEN LENGTH=10}. */
    public static final int ADTOPEN_LENGTH = 10;

    /**
     * Width of {@code ACRDLIMI PIC X(15)}, {@code COACTVW.CPY:78}; {@code ACRDLIM LENGTH=15}.
     *
     * <p>One of the five money fields. Alphanumeric on input; the {@code PICOUT='+ZZZ,ZZZ,ZZZ.99'} edit
     * mask of {@code app/bms/COACTVW.bms:117} applies to the {@code xxxO} twin, not here.
     */
    public static final int ACRDLIM_LENGTH = 15;

    /** Width of {@code AEXPDTI PIC X(10)}, {@code COACTVW.CPY:84}; {@code AEXPDT LENGTH=10}. */
    public static final int AEXPDT_LENGTH = 10;

    /** Width of {@code ACSHLIMI PIC X(15)}, {@code COACTVW.CPY:90}; {@code ACSHLIM LENGTH=15}. */
    public static final int ACSHLIM_LENGTH = 15;

    /** Width of {@code AREISDTI PIC X(10)}, {@code COACTVW.CPY:96}; {@code AREISDT LENGTH=10}. */
    public static final int AREISDT_LENGTH = 10;

    /** Width of {@code ACURBALI PIC X(15)}, {@code COACTVW.CPY:102}; {@code ACURBAL LENGTH=15}. */
    public static final int ACURBAL_LENGTH = 15;

    /** Width of {@code ACRCYCRI PIC X(15)}, {@code COACTVW.CPY:108}; {@code ACRCYCR LENGTH=15}. */
    public static final int ACRCYCR_LENGTH = 15;

    /** Width of {@code AADDGRPI PIC X(10)}, {@code COACTVW.CPY:114}; {@code AADDGRP LENGTH=10}. */
    public static final int AADDGRP_LENGTH = 10;

    /** Width of {@code ACRCYDBI PIC X(15)}, {@code COACTVW.CPY:120}; {@code ACRCYDB LENGTH=15}. */
    public static final int ACRCYDB_LENGTH = 15;

    /** Width of {@code ACSTNUMI PIC X(9)}, {@code COACTVW.CPY:126}; {@code ACSTNUM LENGTH=9}. */
    public static final int ACSTNUM_LENGTH = 9;

    /** Width of {@code ACSTSSNI PIC X(12)}, {@code COACTVW.CPY:132}; {@code ACSTSSN LENGTH=12}. */
    public static final int ACSTSSN_LENGTH = 12;

    /** Width of {@code ACSTDOBI PIC X(10)}, {@code COACTVW.CPY:138}; {@code ACSTDOB LENGTH=10}. */
    public static final int ACSTDOB_LENGTH = 10;

    /** Width of {@code ACSTFCOI PIC X(3)}, {@code COACTVW.CPY:144}; {@code ACSTFCO LENGTH=3}. */
    public static final int ACSTFCO_LENGTH = 3;

    /** Width of {@code ACSFNAMI PIC X(25)}, {@code COACTVW.CPY:150}; {@code ACSFNAM LENGTH=25}. */
    public static final int ACSFNAM_LENGTH = 25;

    /** Width of {@code ACSMNAMI PIC X(25)}, {@code COACTVW.CPY:156}; {@code ACSMNAM LENGTH=25}. */
    public static final int ACSMNAM_LENGTH = 25;

    /** Width of {@code ACSLNAMI PIC X(25)}, {@code COACTVW.CPY:162}; {@code ACSLNAM LENGTH=25}. */
    public static final int ACSLNAM_LENGTH = 25;

    /** Width of {@code ACSADL1I PIC X(50)}, {@code COACTVW.CPY:168}; {@code ACSADL1 LENGTH=50}. */
    public static final int ACSADL1_LENGTH = 50;

    /** Width of {@code ACSSTTEI PIC X(2)}, {@code COACTVW.CPY:174}; {@code ACSSTTE LENGTH=2}. */
    public static final int ACSSTTE_LENGTH = 2;

    /** Width of {@code ACSADL2I PIC X(50)}, {@code COACTVW.CPY:180}; {@code ACSADL2 LENGTH=50}. */
    public static final int ACSADL2_LENGTH = 50;

    /** Width of {@code ACSZIPCI PIC X(5)}, {@code COACTVW.CPY:186}; {@code ACSZIPC LENGTH=5}. */
    public static final int ACSZIPC_LENGTH = 5;

    /** Width of {@code ACSCITYI PIC X(50)}, {@code COACTVW.CPY:192}; {@code ACSCITY LENGTH=50}. */
    public static final int ACSCITY_LENGTH = 50;

    /** Width of {@code ACSCTRYI PIC X(3)}, {@code COACTVW.CPY:198}; {@code ACSCTRY LENGTH=3}. */
    public static final int ACSCTRY_LENGTH = 3;

    /** Width of {@code ACSPHN1I PIC X(13)}, {@code COACTVW.CPY:204}; {@code ACSPHN1 LENGTH=13}. */
    public static final int ACSPHN1_LENGTH = 13;

    /** Width of {@code ACSGOVTI PIC X(20)}, {@code COACTVW.CPY:210}; {@code ACSGOVT LENGTH=20}. */
    public static final int ACSGOVT_LENGTH = 20;

    /** Width of {@code ACSPHN2I PIC X(13)}, {@code COACTVW.CPY:216}; {@code ACSPHN2 LENGTH=13}. */
    public static final int ACSPHN2_LENGTH = 13;

    /** Width of {@code ACSEFTCI PIC X(10)}, {@code COACTVW.CPY:222}; {@code ACSEFTC LENGTH=10}. */
    public static final int ACSEFTC_LENGTH = 10;

    /** Width of {@code ACSPFLGI PIC X(1)}, {@code COACTVW.CPY:228}; {@code ACSPFLG LENGTH=1}. */
    public static final int ACSPFLG_LENGTH = 1;

    /**
     * Width of {@code INFOMSGI PIC X(45)}, {@code COACTVW.CPY:234}; {@code INFOMSG LENGTH=45}.
     *
     * <p>Forty-five, not forty. {@code app/cbl/COACTVWC.cbl:110} declares {@code WS-INFO-MSG PIC X(40)}
     * and {@code :534} moves it here, so COBOL right-pads five spaces. The map width is the contract.
     */
    public static final int INFOMSG_LENGTH = 45;

    /**
     * Width of {@code ERRMSGI PIC X(78)}, {@code COACTVW.CPY:240}; {@code ERRMSG LENGTH=78}.
     *
     * <p>Seventy-eight, not seventy-five. {@code app/cbl/COACTVWC.cbl:117} declares
     * {@code WS-RETURN-MSG PIC X(75)} and {@code :532} moves it here, so COBOL right-pads three spaces.
     * The map width is the contract.
     */
    public static final int ERRMSG_LENGTH = 78;

    // =================================================================================================
    // The shape of the group as a whole. Every one of these is computed from the constants above rather
    // than written down, so the arithmetic in the class documentation cannot drift away from the code.
    // =================================================================================================

    /**
     * Bytes in the {@code TIOAPFX=YES} prefix - {@code 02 FILLER PIC X(12)} at
     * {@code app/cpy-bms/COACTVW.CPY:18}, repeated at line 242 as the first item of the output group.
     *
     * <p>The prefix exists because {@code app/bms/COACTVW.bms:23} declares {@code TIOAPFX=YES}, which
     * reserves room at the front of the map area for the terminal input/output area header. It carries
     * no application data, so it is never exposed as a field; it is only ever written and skipped.
     */
    public static final int TIOAPFX_LENGTH = 12;

    /** Bytes in one {@code xxxL COMP PIC S9(4)} item: a binary halfword, two bytes, big-endian. */
    public static final int LENGTH_ITEM_LENGTH = 2;

    /**
     * Bytes in one {@code xxxF PICTURE X} flag item.
     *
     * <p>{@code 02 FILLER REDEFINES xxxF} / {@code 03 xxxA PICTURE X} is an overlay of this single byte
     * and so adds nothing to the total - which is exactly why {@link ScreenFieldMetadata} holds one
     * attribute value rather than two.
     */
    public static final int FLAG_ITEM_LENGTH = 1;

    /**
     * Bytes in one {@code 02 FILLER PICTURE X(4)} extended-attribute item.
     *
     * <p>Unnamed and unused on input. The output group names the same four bytes {@code xxxC},
     * {@code xxxP}, {@code xxxH} and {@code xxxV} - the
     * {@code DSATTS}/{@code MAPATTS=(COLOR,HILIGHT,PS,VALIDN)} set of
     * {@code app/bms/COACTVW.bms:26-27} - and they are {@code AccountViewResponse}'s concern, not this
     * type's.
     */
    public static final int EXTENDED_ATTRIBUTE_ITEM_LENGTH = 4;

    /**
     * Bytes each field costs before its data begins: {@value #LENGTH_ITEM_LENGTH} +
     * {@value #FLAG_ITEM_LENGTH} + {@value #EXTENDED_ATTRIBUTE_ITEM_LENGTH} = 7, so one field occupies
     * {@code 7 + n} bytes.
     */
    public static final int FIELD_OVERHEAD =
            LENGTH_ITEM_LENGTH + FLAG_ITEM_LENGTH + EXTENDED_ATTRIBUTE_ITEM_LENGTH;

    /** Name-labelled {@code DFHMDF} entries in {@code app/bms/COACTVW.bms}, of 100 entries in all. */
    public static final int FIELD_COUNT = 37;

    /**
     * Bytes of field data in the group - <strong>684</strong>.
     *
     * <p>Written as the sum of the 37 width constants, not as the literal 684, so a corrected width
     * propagates here instead of leaving two numbers to disagree. The static initialiser below still
     * checks the total, because a width wrong in the same direction as this sum would otherwise go
     * unnoticed.
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
     * Bytes in the whole {@code CACTVWAI} group: {@value #TIOAPFX_LENGTH} +
     * {@value #FIELD_COUNT} &times; {@value #FIELD_OVERHEAD} + {@value #PAYLOAD_LENGTH} =
     * <strong>955</strong>.
     *
     * <p>{@code CACTVWAO REDEFINES CACTVWAI}, so the output group is the same 955 bytes. That is the
     * storage {@link #toGroupImage(FixedWidthCodec)} produces and
     * {@link #fromGroupImage(byte[], FixedWidthCodec)} consumes.
     */
    public static final int GROUP_LENGTH =
            TIOAPFX_LENGTH + FIELD_COUNT * FIELD_OVERHEAD + PAYLOAD_LENGTH;

    /**
     * The single space character, the pad and fill character of every field here.
     *
     * <p>Held as a {@code char} rather than a byte because its encoding depends on the code page -
     * {@code 0x20} in US-ASCII and {@code 0x40} in IBM037 - and the encoding is
     * {@link FixedWidthCodec}'s business, resolved from {@link FixedWidthCodec#charset()} at the point of
     * use. Nothing in this file assumes a platform default charset.
     */
    private static final char SPACE = ' ';

    /**
     * The {@code LOW-VALUES} byte: binary zero.
     *
     * <p>{@code LOW-VALUES} is by definition the lowest character of the collating sequence, which is
     * {@code X'00'} in both ASCII and EBCDIC, so this one byte is correct on either code page without
     * consulting the charset. It is the unset value of a flag byte and of an extended-attribute byte in
     * a freshly initialised map area - the state {@code MOVE LOW-VALUES TO CACTVWAO} produces at
     * {@code app/cbl/COACTVWC.cbl:432}, and the state CICS leaves the input group's unused attribute
     * bytes in.
     */
    private static final byte LOW_VALUE_BYTE = 0x00;

    static {
        // Fail before the first byte comparison rather than after it. Every number stated in the class
        // documentation is checked here against the constants, and then the field strides are walked
        // from the end of the prefix to confirm the last field ends exactly on GROUP_LENGTH.
        //
        // The three comparisons below and the four inside verifyFieldStrides() are decided at different
        // times, and the difference is worth knowing rather than glossing:
        //
        //   * FIELD_OVERHEAD, PAYLOAD_LENGTH and GROUP_LENGTH are compile-time constant expressions, so
        //     javac decides each comparison. While the arithmetic holds, the condition is constantly
        //     false and the whole statement is elided - these three cost nothing at run time and appear
        //     in no bytecode. Change a width so the sum no longer reaches 684 and the condition becomes
        //     constantly true, javac emits the throw, and the class fails to initialise on first use.
        //   * verifyFieldStrides() compares values read from ScreenField at run time, which are not
        //     constants, so that check is live in every build. Its four guards validate the transcribed
        //     data offsets and cannot be folded away.
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

    /**
     * Walks the 37 {@link ScreenField} constants in declaration order and confirms that each one begins
     * where its predecessor ended, that the first begins immediately after the
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

    // =================================================================================================
    // The 37 payload members, declared in the order app/cpy-bms/COACTVW.CPY lays their storage down.
    //
    // Naming is mechanical: the Java member and its JSON name are the DFHMDF label lower-cased, with no
    // re-spelling, no expansion of an abbreviation and no exception. TRNNAME becomes trnname, ACSADL1
    // becomes acsadl1, ACSTTUS becomes acsttus. One rule applied 37 times means a reviewer can go from a
    // parity diff naming ACSGOVT to the member holding it without a lookup table, and the DFHMDF label
    // itself is carried verbatim on ScreenField.label() as well as in each member's Javadoc, so the
    // gate G9 trace is readable from either end.
    //
    // Every member is a String. 36 are PIC X(n); ACCTSID is PIC 99999999999 and is a String too, for the
    // reason its own Javadoc gives. None is numeric, so this file declares no scaled decimal, no double,
    // no float and no rounding mode (gates G22 and G24).
    //
    // @Size is the only constraint and every max comes from a PICTURE width. No @NotBlank and no
    // @Pattern: COACTVWC edits its own fields in its own paragraphs, and pre-empting an edit here would
    // change which message the operator sees and in what order.
    //
    // @JsonProperty pins each wire name to the lower-cased DFHMDF label, so a JSON naming strategy
    // configured later cannot rename a field out from under the presentation contract.
    // =================================================================================================

    /**
     * {@code TRNNAME}: {@code TRNNAMEI PIC X(4)}, {@code app/cpy-bms/COACTVW.CPY:24}.
     *
     * <p>The transaction identifier. {@code app/cbl/COACTVWC.cbl:438} writes {@code LIT-THISTRANID},
     * {@code PIC X(4) VALUE 'CAVW'} at {@code :146-147}, into the output twin - the widths agree exactly.
     */
    @JsonProperty("trnname")
    @Size(max = TRNNAME_LENGTH,
            message = "TRNNAME is TRNNAMEI PIC X(4) at app/cpy-bms/COACTVW.CPY:24 and holds at most 4 "
                    + "characters")
    private String trnname;

    /**
     * {@code TITLE01}: {@code TITLE01I PIC X(40)}, {@code app/cpy-bms/COACTVW.CPY:30}.
     *
     * <p>The first title line, fed from {@code CCDA-TITLE01} of {@code COTTL01Y} at
     * {@code app/cbl/COACTVWC.cbl:436}.
     */
    @JsonProperty("title01")
    @Size(max = TITLE01_LENGTH,
            message = "TITLE01 is TITLE01I PIC X(40) at app/cpy-bms/COACTVW.CPY:30 and holds at most 40 "
                    + "characters")
    private String title01;

    /**
     * {@code CURDATE}: {@code CURDATEI PIC X(8)}, {@code app/cpy-bms/COACTVW.CPY:36}.
     *
     * <p>Eight characters of {@code MM/DD/YY}, as {@code app/bms/COACTVW.bms:47} advertises with
     * {@code INITIAL='mm/dd/yy'} and {@code app/cbl/COACTVWC.cbl:447} supplies from
     * {@code WS-CURDATE-MM-DD-YY}. A width, not a date type: the field is eight characters of screen and
     * is compared as such.
     */
    @JsonProperty("curdate")
    @Size(max = CURDATE_LENGTH,
            message = "CURDATE is CURDATEI PIC X(8) at app/cpy-bms/COACTVW.CPY:36 and holds at most 8 "
                    + "characters")
    private String curdate;

    /**
     * {@code PGMNAME}: {@code PGMNAMEI PIC X(8)}, {@code app/cpy-bms/COACTVW.CPY:42}.
     *
     * <p>Fed from {@code LIT-THISPGM}, {@code PIC X(8) VALUE 'COACTVWC'} at
     * {@code app/cbl/COACTVWC.cbl:143-144}, by {@code :439}.
     */
    @JsonProperty("pgmname")
    @Size(max = PGMNAME_LENGTH,
            message = "PGMNAME is PGMNAMEI PIC X(8) at app/cpy-bms/COACTVW.CPY:42 and holds at most 8 "
                    + "characters")
    private String pgmname;

    /**
     * {@code TITLE02}: {@code TITLE02I PIC X(40)}, {@code app/cpy-bms/COACTVW.CPY:48}.
     *
     * <p>The second title line, fed from {@code CCDA-TITLE02} at {@code app/cbl/COACTVWC.cbl:437}.
     */
    @JsonProperty("title02")
    @Size(max = TITLE02_LENGTH,
            message = "TITLE02 is TITLE02I PIC X(40) at app/cpy-bms/COACTVW.CPY:48 and holds at most 40 "
                    + "characters")
    private String title02;

    /**
     * {@code CURTIME}: {@code CURTIMEI PIC X(8)}, {@code app/cpy-bms/COACTVW.CPY:54}.
     *
     * <p>Eight characters of {@code HH:MM:SS}, {@code INITIAL='hh:mm:ss'} at
     * {@code app/bms/COACTVW.bms:70} and supplied from {@code WS-CURTIME-HH-MM-SS} at
     * {@code app/cbl/COACTVWC.cbl:453}.
     */
    @JsonProperty("curtime")
    @Size(max = CURTIME_LENGTH,
            message = "CURTIME is CURTIMEI PIC X(8) at app/cpy-bms/COACTVW.CPY:54 and holds at most 8 "
                    + "characters")
    private String curtime;

    /**
     * {@code ACCTSID}: {@code ACCTSIDI PIC 99999999999}, {@code app/cpy-bms/COACTVW.CPY:60}.
     *
     * <p><strong>The only field an operator types on this screen, and the only one of the 37 whose
     * {@code xxxI PICTURE} is not {@code X}.</strong> {@code app/bms/COACTVW.bms:84-95} declares it
     * {@code ATTRB=(FSET,IC,NORM,UNPROT) COLOR=GREEN HILIGHT=UNDERLINE PICIN='99999999999'
     * POS=(5,38) VALIDN=(MUSTFILL)} - the insertion cursor starts here and every other field is
     * protected or auto-skip.
     *
     * <p>It is a {@link String} of at most {@value #ACCTSID_LENGTH} characters, never an {@code int} or a
     * {@code long}. {@code app/cbl/COACTVWC.cbl:628-629} tests it against {@code '*'} and against
     * {@code SPACES} before {@code :630} moves {@code LOW-VALUES} to {@code CC-ACCT-ID} or {@code :632}
     * moves the field itself; a display-numeric item is eleven raw characters of storage, so both
     * comparisons are legal in COBOL and neither value fits a Java integral type. Binding this to a
     * number would make a live branch unreproducible.
     *
     * <p>Which is also why the constraint here is {@link Size} alone. {@code PICIN='99999999999'} and
     * {@code VALIDN=(MUSTFILL)} are the terminal's input contract and are carried as metadata - see
     * {@link ScreenField#ACCTSID} - not as a {@code @Pattern}. A digits-only pattern would reject
     * {@code '*'} and reject spaces, and the program requires both.
     */
    @JsonProperty("acctsid")
    @Size(max = ACCTSID_LENGTH,
            message = "ACCTSID is ACCTSIDI PIC 99999999999 at app/cpy-bms/COACTVW.CPY:60 and holds at "
                    + "most 11 characters; '*' and spaces are valid values, tested at "
                    + "app/cbl/COACTVWC.cbl:628-629")
    private String acctsid;

    /** {@code ACSTTUS}: {@code ACSTTUSI PIC X(1)}, {@code app/cpy-bms/COACTVW.CPY:66}. */
    @JsonProperty("acsttus")
    @Size(max = ACSTTUS_LENGTH,
            message = "ACSTTUS is ACSTTUSI PIC X(1) at app/cpy-bms/COACTVW.CPY:66 and holds at most 1 "
                    + "character")
    private String acsttus;

    /** {@code ADTOPEN}: {@code ADTOPENI PIC X(10)}, {@code app/cpy-bms/COACTVW.CPY:72}. */
    @JsonProperty("adtopen")
    @Size(max = ADTOPEN_LENGTH,
            message = "ADTOPEN is ADTOPENI PIC X(10) at app/cpy-bms/COACTVW.CPY:72 and holds at most 10 "
                    + "characters")
    private String adtopen;

    /**
     * {@code ACRDLIM}: {@code ACRDLIMI PIC X(15)}, {@code app/cpy-bms/COACTVW.CPY:78}.
     *
     * <p>A money field, and alphanumeric on input. Nothing here parses it: the
     * {@code PICOUT='+ZZZ,ZZZ,ZZZ.99'} mask of {@code app/bms/COACTVW.bms:117} shapes the {@code xxxO}
     * twin, so the edited rendering is {@code AccountViewResponse}'s problem.
     */
    @JsonProperty("acrdlim")
    @Size(max = ACRDLIM_LENGTH,
            message = "ACRDLIM is ACRDLIMI PIC X(15) at app/cpy-bms/COACTVW.CPY:78 and holds at most 15 "
                    + "characters")
    private String acrdlim;

    /** {@code AEXPDT}: {@code AEXPDTI PIC X(10)}, {@code app/cpy-bms/COACTVW.CPY:84}. */
    @JsonProperty("aexpdt")
    @Size(max = AEXPDT_LENGTH,
            message = "AEXPDT is AEXPDTI PIC X(10) at app/cpy-bms/COACTVW.CPY:84 and holds at most 10 "
                    + "characters")
    private String aexpdt;

    /** {@code ACSHLIM}: {@code ACSHLIMI PIC X(15)}, {@code app/cpy-bms/COACTVW.CPY:90}. A money field. */
    @JsonProperty("acshlim")
    @Size(max = ACSHLIM_LENGTH,
            message = "ACSHLIM is ACSHLIMI PIC X(15) at app/cpy-bms/COACTVW.CPY:90 and holds at most 15 "
                    + "characters")
    private String acshlim;

    /** {@code AREISDT}: {@code AREISDTI PIC X(10)}, {@code app/cpy-bms/COACTVW.CPY:96}. */
    @JsonProperty("areisdt")
    @Size(max = AREISDT_LENGTH,
            message = "AREISDT is AREISDTI PIC X(10) at app/cpy-bms/COACTVW.CPY:96 and holds at most 10 "
                    + "characters")
    private String areisdt;

    /** {@code ACURBAL}: {@code ACURBALI PIC X(15)}, {@code app/cpy-bms/COACTVW.CPY:102}. A money field. */
    @JsonProperty("acurbal")
    @Size(max = ACURBAL_LENGTH,
            message = "ACURBAL is ACURBALI PIC X(15) at app/cpy-bms/COACTVW.CPY:102 and holds at most 15 "
                    + "characters")
    private String acurbal;

    /** {@code ACRCYCR}: {@code ACRCYCRI PIC X(15)}, {@code app/cpy-bms/COACTVW.CPY:108}. A money field. */
    @JsonProperty("acrcycr")
    @Size(max = ACRCYCR_LENGTH,
            message = "ACRCYCR is ACRCYCRI PIC X(15) at app/cpy-bms/COACTVW.CPY:108 and holds at most 15 "
                    + "characters")
    private String acrcycr;

    /** {@code AADDGRP}: {@code AADDGRPI PIC X(10)}, {@code app/cpy-bms/COACTVW.CPY:114}. */
    @JsonProperty("aaddgrp")
    @Size(max = AADDGRP_LENGTH,
            message = "AADDGRP is AADDGRPI PIC X(10) at app/cpy-bms/COACTVW.CPY:114 and holds at most 10 "
                    + "characters")
    private String aaddgrp;

    /** {@code ACRCYDB}: {@code ACRCYDBI PIC X(15)}, {@code app/cpy-bms/COACTVW.CPY:120}. A money field. */
    @JsonProperty("acrcydb")
    @Size(max = ACRCYDB_LENGTH,
            message = "ACRCYDB is ACRCYDBI PIC X(15) at app/cpy-bms/COACTVW.CPY:120 and holds at most 15 "
                    + "characters")
    private String acrcydb;

    /** {@code ACSTNUM}: {@code ACSTNUMI PIC X(9)}, {@code app/cpy-bms/COACTVW.CPY:126}. */
    @JsonProperty("acstnum")
    @Size(max = ACSTNUM_LENGTH,
            message = "ACSTNUM is ACSTNUMI PIC X(9) at app/cpy-bms/COACTVW.CPY:126 and holds at most 9 "
                    + "characters")
    private String acstnum;

    /**
     * {@code ACSTSSN}: {@code ACSTSSNI PIC X(12)}, {@code app/cpy-bms/COACTVW.CPY:132}.
     *
     * <p>A social security number, carried in the clear exactly as the symbolic map declares it - the
     * 3270 displays it in the clear, so it is neither masked nor redacted nor withheld from the JSON
     * body (practice <strong>B6</strong>). Nothing is weakened either: no new exposure is introduced
     * beyond the one the screen already has.
     */
    @JsonProperty("acstssn")
    @Size(max = ACSTSSN_LENGTH,
            message = "ACSTSSN is ACSTSSNI PIC X(12) at app/cpy-bms/COACTVW.CPY:132 and holds at most 12 "
                    + "characters")
    private String acstssn;

    /**
     * {@code ACSTDOB}: {@code ACSTDOBI PIC X(10)}, {@code app/cpy-bms/COACTVW.CPY:138}.
     *
     * <p>A date of birth, ten characters wide and held as text for the same reason {@link #curdate} is:
     * the contract is a screen width, not a date type. Carried in the clear, as above.
     */
    @JsonProperty("acstdob")
    @Size(max = ACSTDOB_LENGTH,
            message = "ACSTDOB is ACSTDOBI PIC X(10) at app/cpy-bms/COACTVW.CPY:138 and holds at most 10 "
                    + "characters")
    private String acstdob;

    /** {@code ACSTFCO}: {@code ACSTFCOI PIC X(3)}, {@code app/cpy-bms/COACTVW.CPY:144}. */
    @JsonProperty("acstfco")
    @Size(max = ACSTFCO_LENGTH,
            message = "ACSTFCO is ACSTFCOI PIC X(3) at app/cpy-bms/COACTVW.CPY:144 and holds at most 3 "
                    + "characters")
    private String acstfco;

    /** {@code ACSFNAM}: {@code ACSFNAMI PIC X(25)}, {@code app/cpy-bms/COACTVW.CPY:150}. */
    @JsonProperty("acsfnam")
    @Size(max = ACSFNAM_LENGTH,
            message = "ACSFNAM is ACSFNAMI PIC X(25) at app/cpy-bms/COACTVW.CPY:150 and holds at most 25 "
                    + "characters")
    private String acsfnam;

    /** {@code ACSMNAM}: {@code ACSMNAMI PIC X(25)}, {@code app/cpy-bms/COACTVW.CPY:156}. */
    @JsonProperty("acsmnam")
    @Size(max = ACSMNAM_LENGTH,
            message = "ACSMNAM is ACSMNAMI PIC X(25) at app/cpy-bms/COACTVW.CPY:156 and holds at most 25 "
                    + "characters")
    private String acsmnam;

    /** {@code ACSLNAM}: {@code ACSLNAMI PIC X(25)}, {@code app/cpy-bms/COACTVW.CPY:162}. */
    @JsonProperty("acslnam")
    @Size(max = ACSLNAM_LENGTH,
            message = "ACSLNAM is ACSLNAMI PIC X(25) at app/cpy-bms/COACTVW.CPY:162 and holds at most 25 "
                    + "characters")
    private String acslnam;

    /** {@code ACSADL1}: {@code ACSADL1I PIC X(50)}, {@code app/cpy-bms/COACTVW.CPY:168}. */
    @JsonProperty("acsadl1")
    @Size(max = ACSADL1_LENGTH,
            message = "ACSADL1 is ACSADL1I PIC X(50) at app/cpy-bms/COACTVW.CPY:168 and holds at most 50 "
                    + "characters")
    private String acsadl1;

    /** {@code ACSSTTE}: {@code ACSSTTEI PIC X(2)}, {@code app/cpy-bms/COACTVW.CPY:174}. */
    @JsonProperty("acsstte")
    @Size(max = ACSSTTE_LENGTH,
            message = "ACSSTTE is ACSSTTEI PIC X(2) at app/cpy-bms/COACTVW.CPY:174 and holds at most 2 "
                    + "characters")
    private String acsstte;

    /** {@code ACSADL2}: {@code ACSADL2I PIC X(50)}, {@code app/cpy-bms/COACTVW.CPY:180}. */
    @JsonProperty("acsadl2")
    @Size(max = ACSADL2_LENGTH,
            message = "ACSADL2 is ACSADL2I PIC X(50) at app/cpy-bms/COACTVW.CPY:180 and holds at most 50 "
                    + "characters")
    private String acsadl2;

    /**
     * {@code ACSZIPC}: {@code ACSZIPCI PIC X(5)}, {@code app/cpy-bms/COACTVW.CPY:186}.
     *
     * <p>{@code app/bms/COACTVW.bms:291-296} declares {@code JUSTIFY=(RIGHT)}, which governs how the
     * terminal aligns what is keyed. The stored value is still the five characters the map carries, and
     * this class neither re-justifies nor trims them.
     */
    @JsonProperty("acszipc")
    @Size(max = ACSZIPC_LENGTH,
            message = "ACSZIPC is ACSZIPCI PIC X(5) at app/cpy-bms/COACTVW.CPY:186 and holds at most 5 "
                    + "characters")
    private String acszipc;

    /** {@code ACSCITY}: {@code ACSCITYI PIC X(50)}, {@code app/cpy-bms/COACTVW.CPY:192}. */
    @JsonProperty("acscity")
    @Size(max = ACSCITY_LENGTH,
            message = "ACSCITY is ACSCITYI PIC X(50) at app/cpy-bms/COACTVW.CPY:192 and holds at most 50 "
                    + "characters")
    private String acscity;

    /** {@code ACSCTRY}: {@code ACSCTRYI PIC X(3)}, {@code app/cpy-bms/COACTVW.CPY:198}. */
    @JsonProperty("acsctry")
    @Size(max = ACSCTRY_LENGTH,
            message = "ACSCTRY is ACSCTRYI PIC X(3) at app/cpy-bms/COACTVW.CPY:198 and holds at most 3 "
                    + "characters")
    private String acsctry;

    /** {@code ACSPHN1}: {@code ACSPHN1I PIC X(13)}, {@code app/cpy-bms/COACTVW.CPY:204}. */
    @JsonProperty("acsphn1")
    @Size(max = ACSPHN1_LENGTH,
            message = "ACSPHN1 is ACSPHN1I PIC X(13) at app/cpy-bms/COACTVW.CPY:204 and holds at most 13 "
                    + "characters")
    private String acsphn1;

    /**
     * {@code ACSGOVT}: {@code ACSGOVTI PIC X(20)}, {@code app/cpy-bms/COACTVW.CPY:210}.
     *
     * <p>A government-issued identifier, carried in the clear exactly as the symbolic map declares it,
     * for the reason {@link #acstssn} gives.
     */
    @JsonProperty("acsgovt")
    @Size(max = ACSGOVT_LENGTH,
            message = "ACSGOVT is ACSGOVTI PIC X(20) at app/cpy-bms/COACTVW.CPY:210 and holds at most 20 "
                    + "characters")
    private String acsgovt;

    /** {@code ACSPHN2}: {@code ACSPHN2I PIC X(13)}, {@code app/cpy-bms/COACTVW.CPY:216}. */
    @JsonProperty("acsphn2")
    @Size(max = ACSPHN2_LENGTH,
            message = "ACSPHN2 is ACSPHN2I PIC X(13) at app/cpy-bms/COACTVW.CPY:216 and holds at most 13 "
                    + "characters")
    private String acsphn2;

    /** {@code ACSEFTC}: {@code ACSEFTCI PIC X(10)}, {@code app/cpy-bms/COACTVW.CPY:222}. */
    @JsonProperty("acseftc")
    @Size(max = ACSEFTC_LENGTH,
            message = "ACSEFTC is ACSEFTCI PIC X(10) at app/cpy-bms/COACTVW.CPY:222 and holds at most 10 "
                    + "characters")
    private String acseftc;

    /** {@code ACSPFLG}: {@code ACSPFLGI PIC X(1)}, {@code app/cpy-bms/COACTVW.CPY:228}. */
    @JsonProperty("acspflg")
    @Size(max = ACSPFLG_LENGTH,
            message = "ACSPFLG is ACSPFLGI PIC X(1) at app/cpy-bms/COACTVW.CPY:228 and holds at most 1 "
                    + "character")
    private String acspflg;

    /**
     * {@code INFOMSG}: {@code INFOMSGI PIC X(45)}, {@code app/cpy-bms/COACTVW.CPY:234}.
     *
     * <p>Forty-five characters, fed from a forty-character {@code WS-INFO-MSG} at
     * {@code app/cbl/COACTVWC.cbl:534}. The map width wins.
     */
    @JsonProperty("infomsg")
    @Size(max = INFOMSG_LENGTH,
            message = "INFOMSG is INFOMSGI PIC X(45) at app/cpy-bms/COACTVW.CPY:234 and holds at most 45 "
                    + "characters")
    private String infomsg;

    /**
     * {@code ERRMSG}: {@code ERRMSGI PIC X(78)}, {@code app/cpy-bms/COACTVW.CPY:240}.
     *
     * <p>Seventy-eight characters, fed from a seventy-five-character {@code WS-RETURN-MSG} at
     * {@code app/cbl/COACTVWC.cbl:532}. The map width wins.
     */
    @JsonProperty("errmsg")
    @Size(max = ERRMSG_LENGTH,
            message = "ERRMSG is ERRMSGI PIC X(78) at app/cpy-bms/COACTVW.CPY:240 and holds at most 78 "
                    + "characters")
    private String errmsg;

    // =================================================================================================
    // The two conversation-state carriers. CICS is pseudo-conversational, so the state a session would
    // otherwise hold rides in the payload instead (rule R6, gate G37).
    // =================================================================================================

    /**
     * The {@code CVCRD01Y} work area, copied by {@code app/cbl/COACTVWC.cbl:207}.
     *
     * <p>{@value CardScreenState#RECORD_LENGTH} bytes carrying {@code CCARD-AID X(5)} and its condition
     * names, the next program, mapset and map, the error and return messages, and the
     * {@code CC-ACCT-ID} / {@code CC-ACCT-ID-N} redefinition pair that {@code :630} and {@code :632}
     * write into. It lives in {@code card/dto} rather than here because {@code CVCRD01Y} has five
     * consumers and the one-type-per-copybook rule gives it a single home; this screen is one of the
     * five.
     *
     * <p>Never {@code null}: {@code COACTVWC} performs {@code INITIALIZE CC-WORK-AREA} before it reads
     * anything out of the area, so a request that carries nothing carries a freshly initialised area.
     */
    @JsonProperty("cardScreenState")
    private CardScreenState cardScreenState;

    /**
     * The {@code CARDDEMO-COMMAREA} of {@code COCOM01Y}, copied by {@code app/cbl/COACTVWC.cbl:211} -
     * {@value NavigationContext#COMMAREA_LENGTH} bytes, carrying among much else
     * {@code CDEMO-PGM-CONTEXT}, on which {@link #isEnter()} and {@link #isReenter()} turn.
     *
     * <p><strong>{@code null} is meaningful here, and it is not an empty area.</strong> An absent
     * communication area is CICS reporting {@code EIBCALEN} as zero - a cold start - and an initialised
     * {@link NavigationContext} cannot express that, because an initialised area is
     * {@value NavigationContext#COMMAREA_LENGTH} bytes present and reports a program context of
     * {@value NavigationContext#PGM_CONTEXT_ENTER}. The two are different facts, so this member stays
     * nullable, carries no presence constraint, and {@link #hasNavigationContext()} is the
     * discriminator. Deciding what a cold start means is the controller's business, not this type's
     * (gate <strong>G51</strong>).
     */
    @JsonProperty("navigationContext")
    private NavigationContext navigationContext;

    /**
     * The {@code xxxL} and {@code xxxA} items of all 37 fields, one holder per field.
     *
     * <p>An {@link EnumMap} keyed by {@link ScreenField}: iteration follows the enum's declaration
     * order, which is the order the copybook lays the storage down, so
     * {@link #toGroupImage(FixedWidthCodec)} walks the group front to back without sorting anything. The
     * map itself is {@code final} and every key is populated at construction, so
     * {@link #metadata(ScreenField)} never returns {@code null} and never has to create anything.
     *
     * <p>Instance state, not static state (gate <strong>G53</strong>): two requests never share a
     * holder, which is what {@link #AccountViewRequest(AccountViewRequest)} copies each holder for.
     */
    private final EnumMap<ScreenField, ScreenFieldMetadata> metadata =
            new EnumMap<>(ScreenField.class);

    // =================================================================================================
    // Construction. Nothing here needs a Spring context, a servlet container or a JSON binder to run,
    // which is what lets a plain JUnit 5 test and parity/ParityHarness build an instance directly
    // (practice B10).
    // =================================================================================================

    /**
     * A freshly initialised map area: every one of the 37 fields a run of spaces of its declared width,
     * every metadata holder unset, a new {@link CardScreenState} work area, and no communication area.
     *
     * <p>This is also the constructor a JSON binder uses, which is why the fields start as spaces rather
     * than as {@code null}: a payload that omits a field leaves it holding what COBOL would have there.
     * There is no null in a COBOL record - an unset {@code PIC X} item holds spaces - and
     * {@code app/cbl/COACTVWC.cbl:629} depends on exactly that when it compares {@code ACCTSIDI} with
     * {@code SPACES}.
     *
     * <p>{@link #getNavigationContext()} is deliberately {@code null} rather than
     * {@link NavigationContext#empty()}: absence and an initialised area are different facts, as
     * {@link #navigationContext} explains.
     */
    public AccountViewRequest() {
        for (ScreenField field : ScreenField.values()) {
            metadata.put(field, new ScreenFieldMetadata());
        }
        initializeState();
    }

    /**
     * A deep copy of another request.
     *
     * <p>Deep in the one place it has to be: each {@link ScreenFieldMetadata} holder is copied rather
     * than shared, so the two requests cannot move each other's cursor or overwrite each other's
     * attribute byte. The 37 field values are {@link String}s and {@link NavigationContext} is a record,
     * both immutable and so safe to share; {@link CardScreenState} is mutable and is copied through its
     * own copy constructor.
     *
     * @param other the request to copy
     * @throws NullPointerException if {@code other} is {@code null}
     */
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
     * <p>{@code app/cbl/COACTVWC.cbl} takes exactly one value off this map - {@code ACCTSIDI OF
     * CACTVWAI} at lines 628 to 632 - and derives everything else. That is not an abbreviation of the
     * screen but a property of it: {@code app/bms/COACTVW.bms} declares exactly one
     * {@code ATTRB=(...,UNPROT)} entry, {@code ACCTSID} at line 84, and the other 36 fields are titles,
     * the date and time header, the account and customer record it displays, and the two message lines -
     * all of which the program writes rather than reads.
     *
     * <p>So a test or a parity case exercising the account-lookup path needs only this one value, and
     * this factory says so out loud instead of leaving 36 setter calls to prove it. Both {@code "*"} and
     * a blank string are legitimate arguments; the program treats each as "no criterion" at
     * {@code :628-630}.
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
     * {@code INITIALIZE} a CICS program performs before it starts populating a map, and the counterpart
     * of {@code MOVE LOW-VALUES TO CACTVWAO} at {@code app/cbl/COACTVWC.cbl:432}.
     *
     * <p>Equivalent to discarding the instance and constructing a new one. It exists because a
     * controller or a parity case often wants to reuse one request across scenarios without carrying a
     * value over from the last.
     */
    public void initializeMapArea() {
        initializeState();
        for (ScreenFieldMetadata holder : metadata.values()) {
            holder.reset();
        }
    }

    /**
     * The field-and-carrier half of initialisation, shared by {@link #AccountViewRequest()} and
     * {@link #initializeMapArea()}.
     *
     * <p>Private and non-overridable, and it touches nothing but this instance's own fields, so it is
     * safe to call from a constructor.
     */
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
        // Absence, not an initialised area: a request nobody has passed a communication area to has not
        // been passed one, and CICS would report EIBCALEN as zero.
        this.navigationContext = null;
    }

    /**
     * COBOL's {@code SPACES} figurative constant, repeated to a width.
     *
     * <p>An unconditional fill, and deliberately <strong>not</strong> the alphanumeric {@code MOVE}
     * rule: a {@code MOVE} pads a short sending value and truncates a long one, and that rule lives
     * solely in {@link FixedWidthCodec#movePicX(String, int)} and is never reimplemented here.
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

    /**
     * Substitutes spaces for an absent value, at the field's declared width.
     *
     * <p>Every setter routes {@code null} through here rather than storing it. A COBOL record has no
     * null, and {@code app/cbl/COACTVWC.cbl:628-629} relies on that when it tests
     * {@code IF ACCTSIDI OF CACTVWAI = '*' OR = SPACES}: a field left null would make that comparison
     * impossible to reproduce and would put a {@link NullPointerException} between the request and the
     * first edit.
     *
     * <p>The substitution loses the distinction between "the client omitted this field" and "the client
     * sent spaces" - but so does CICS, which is why a symbolic map carries an {@code xxxL} item at all.
     * That distinction lives in {@link ScreenFieldMetadata#isLengthUnset()}, where the copybook puts it.
     *
     * @param value the value supplied, possibly {@code null}
     * @param width the field's declared width
     * @return {@code value} unchanged, or spaces of the declared width when it is {@code null}
     */
    private static String orSpaces(String value, int width) {
        return value == null ? spaces(width) : value;
    }

    // =================================================================================================
    // Accessors. Thirty-seven pairs, hand-written - no Lombok and no MapStruct, so what a reviewer reads
    // is what runs, and the byte-exact field mapping parity depends on stays visible.
    //
    // Every setter stores its value UNALTERED. It does not pad and it does not truncate, because a setter
    // is not a COBOL MOVE: the MOVE rule is applied only where it is asked for, by
    // FixedWidthCodec.movePicX, through image(), normalize() and toGroupImage(). A setter that quietly
    // resized its value would hide the one decision this migration most needs to keep visible. The only
    // thing a setter substitutes is spaces for null, because a COBOL record has no null.
    //
    // Every getter returns the stored value untrimmed. A PIC X field's trailing spaces are part of its
    // value and the parity differ compares them.
    // =================================================================================================

    /**
     * {@code TRNNAME}, the transaction identifier.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTrnname() {
        return trnname;
    }

    /**
     * Stores {@code TRNNAME} verbatim.
     *
     * @param trnname the value; {@code null} is taken as {@value #TRNNAME_LENGTH} spaces
     */
    public void setTrnname(String trnname) {
        this.trnname = orSpaces(trnname, TRNNAME_LENGTH);
    }

    /**
     * {@code TITLE01}, the first title line.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTitle01() {
        return title01;
    }

    /**
     * Stores {@code TITLE01} verbatim.
     *
     * @param title01 the value; {@code null} is taken as {@value #TITLE01_LENGTH} spaces
     */
    public void setTitle01(String title01) {
        this.title01 = orSpaces(title01, TITLE01_LENGTH);
    }

    /**
     * {@code CURDATE}, the current date as {@code MM/DD/YY}.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getCurdate() {
        return curdate;
    }

    /**
     * Stores {@code CURDATE} verbatim.
     *
     * @param curdate the value; {@code null} is taken as {@value #CURDATE_LENGTH} spaces
     */
    public void setCurdate(String curdate) {
        this.curdate = orSpaces(curdate, CURDATE_LENGTH);
    }

    /**
     * {@code PGMNAME}, the program name.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getPgmname() {
        return pgmname;
    }

    /**
     * Stores {@code PGMNAME} verbatim.
     *
     * @param pgmname the value; {@code null} is taken as {@value #PGMNAME_LENGTH} spaces
     */
    public void setPgmname(String pgmname) {
        this.pgmname = orSpaces(pgmname, PGMNAME_LENGTH);
    }

    /**
     * {@code TITLE02}, the second title line.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTitle02() {
        return title02;
    }

    /**
     * Stores {@code TITLE02} verbatim.
     *
     * @param title02 the value; {@code null} is taken as {@value #TITLE02_LENGTH} spaces
     */
    public void setTitle02(String title02) {
        this.title02 = orSpaces(title02, TITLE02_LENGTH);
    }

    /**
     * {@code CURTIME}, the current time as {@code HH:MM:SS}.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getCurtime() {
        return curtime;
    }

    /**
     * Stores {@code CURTIME} verbatim.
     *
     * @param curtime the value; {@code null} is taken as {@value #CURTIME_LENGTH} spaces
     */
    public void setCurtime(String curtime) {
        this.curtime = orSpaces(curtime, CURTIME_LENGTH);
    }

    /**
     * {@code ACCTSID}, the account identifier the operator types - the only unprotected field on this
     * screen.
     *
     * <p>Returned as stored and untrimmed, which includes the two values the program tests for at
     * {@code app/cbl/COACTVWC.cbl:628-629}: {@code "*"} and all spaces. Callers must handle both.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAcctsid() {
        return acctsid;
    }

    /**
     * Stores {@code ACCTSID} verbatim, including {@code "*"} and any blank value.
     *
     * <p>No digit check is applied despite {@code PICIN='99999999999'}: the program's own edits decide
     * what is acceptable, and rejecting {@code "*"} or spaces here would break a live branch.
     *
     * @param acctsid the value; {@code null} is taken as {@value #ACCTSID_LENGTH} spaces
     */
    public void setAcctsid(String acctsid) {
        this.acctsid = orSpaces(acctsid, ACCTSID_LENGTH);
    }

    /**
     * {@code ACSTTUS}, the account status.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAcsttus() {
        return acsttus;
    }

    /**
     * Stores {@code ACSTTUS} verbatim.
     *
     * @param acsttus the value; {@code null} is taken as {@value #ACSTTUS_LENGTH} spaces
     */
    public void setAcsttus(String acsttus) {
        this.acsttus = orSpaces(acsttus, ACSTTUS_LENGTH);
    }

    /**
     * {@code ADTOPEN}, the account open date.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAdtopen() {
        return adtopen;
    }

    /**
     * Stores {@code ADTOPEN} verbatim.
     *
     * @param adtopen the value; {@code null} is taken as {@value #ADTOPEN_LENGTH} spaces
     */
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

    /**
     * {@code AEXPDT}, the account expiry date.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAexpdt() {
        return aexpdt;
    }

    /**
     * Stores {@code AEXPDT} verbatim.
     *
     * @param aexpdt the value; {@code null} is taken as {@value #AEXPDT_LENGTH} spaces
     */
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

    /**
     * {@code AREISDT}, the account reissue date.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAreisdt() {
        return areisdt;
    }

    /**
     * Stores {@code AREISDT} verbatim.
     *
     * @param areisdt the value; {@code null} is taken as {@value #AREISDT_LENGTH} spaces
     */
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

    /**
     * {@code AADDGRP}, the account group identifier.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAaddgrp() {
        return aaddgrp;
    }

    /**
     * Stores {@code AADDGRP} verbatim.
     *
     * @param aaddgrp the value; {@code null} is taken as {@value #AADDGRP_LENGTH} spaces
     */
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

    /**
     * {@code ACSTNUM}, the customer number.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAcstnum() {
        return acstnum;
    }

    /**
     * Stores {@code ACSTNUM} verbatim.
     *
     * @param acstnum the value; {@code null} is taken as {@value #ACSTNUM_LENGTH} spaces
     */
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

    /**
     * Stores {@code ACSTSSN} verbatim.
     *
     * @param acstssn the value; {@code null} is taken as {@value #ACSTSSN_LENGTH} spaces
     */
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

    /**
     * Stores {@code ACSTDOB} verbatim.
     *
     * @param acstdob the value; {@code null} is taken as {@value #ACSTDOB_LENGTH} spaces
     */
    public void setAcstdob(String acstdob) {
        this.acstdob = orSpaces(acstdob, ACSTDOB_LENGTH);
    }

    /**
     * {@code ACSTFCO}, the customer FICO credit score.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAcstfco() {
        return acstfco;
    }

    /**
     * Stores {@code ACSTFCO} verbatim.
     *
     * @param acstfco the value; {@code null} is taken as {@value #ACSTFCO_LENGTH} spaces
     */
    public void setAcstfco(String acstfco) {
        this.acstfco = orSpaces(acstfco, ACSTFCO_LENGTH);
    }

    /**
     * {@code ACSFNAM}, the customer's given name.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAcsfnam() {
        return acsfnam;
    }

    /**
     * Stores {@code ACSFNAM} verbatim.
     *
     * @param acsfnam the value; {@code null} is taken as {@value #ACSFNAM_LENGTH} spaces
     */
    public void setAcsfnam(String acsfnam) {
        this.acsfnam = orSpaces(acsfnam, ACSFNAM_LENGTH);
    }

    /**
     * {@code ACSMNAM}, the customer's middle name.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAcsmnam() {
        return acsmnam;
    }

    /**
     * Stores {@code ACSMNAM} verbatim.
     *
     * @param acsmnam the value; {@code null} is taken as {@value #ACSMNAM_LENGTH} spaces
     */
    public void setAcsmnam(String acsmnam) {
        this.acsmnam = orSpaces(acsmnam, ACSMNAM_LENGTH);
    }

    /**
     * {@code ACSLNAM}, the customer's surname.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAcslnam() {
        return acslnam;
    }

    /**
     * Stores {@code ACSLNAM} verbatim.
     *
     * @param acslnam the value; {@code null} is taken as {@value #ACSLNAM_LENGTH} spaces
     */
    public void setAcslnam(String acslnam) {
        this.acslnam = orSpaces(acslnam, ACSLNAM_LENGTH);
    }

    /**
     * {@code ACSADL1}, the first address line.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAcsadl1() {
        return acsadl1;
    }

    /**
     * Stores {@code ACSADL1} verbatim.
     *
     * @param acsadl1 the value; {@code null} is taken as {@value #ACSADL1_LENGTH} spaces
     */
    public void setAcsadl1(String acsadl1) {
        this.acsadl1 = orSpaces(acsadl1, ACSADL1_LENGTH);
    }

    /**
     * {@code ACSSTTE}, the two-character state code.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAcsstte() {
        return acsstte;
    }

    /**
     * Stores {@code ACSSTTE} verbatim.
     *
     * @param acsstte the value; {@code null} is taken as {@value #ACSSTTE_LENGTH} spaces
     */
    public void setAcsstte(String acsstte) {
        this.acsstte = orSpaces(acsstte, ACSSTTE_LENGTH);
    }

    /**
     * {@code ACSADL2}, the second address line.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAcsadl2() {
        return acsadl2;
    }

    /**
     * Stores {@code ACSADL2} verbatim.
     *
     * @param acsadl2 the value; {@code null} is taken as {@value #ACSADL2_LENGTH} spaces
     */
    public void setAcsadl2(String acsadl2) {
        this.acsadl2 = orSpaces(acsadl2, ACSADL2_LENGTH);
    }

    /**
     * {@code ACSZIPC}, the postal code.
     *
     * @return the stored value, untrimmed and un-rejustified; never {@code null}
     */
    public String getAcszipc() {
        return acszipc;
    }

    /**
     * Stores {@code ACSZIPC} verbatim.
     *
     * <p>{@code JUSTIFY=(RIGHT)} is the terminal's alignment rule for what is keyed, not a
     * transformation this setter applies.
     *
     * @param acszipc the value; {@code null} is taken as {@value #ACSZIPC_LENGTH} spaces
     */
    public void setAcszipc(String acszipc) {
        this.acszipc = orSpaces(acszipc, ACSZIPC_LENGTH);
    }

    /**
     * {@code ACSCITY}, the city.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAcscity() {
        return acscity;
    }

    /**
     * Stores {@code ACSCITY} verbatim.
     *
     * @param acscity the value; {@code null} is taken as {@value #ACSCITY_LENGTH} spaces
     */
    public void setAcscity(String acscity) {
        this.acscity = orSpaces(acscity, ACSCITY_LENGTH);
    }

    /**
     * {@code ACSCTRY}, the three-character country code.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAcsctry() {
        return acsctry;
    }

    /**
     * Stores {@code ACSCTRY} verbatim.
     *
     * @param acsctry the value; {@code null} is taken as {@value #ACSCTRY_LENGTH} spaces
     */
    public void setAcsctry(String acsctry) {
        this.acsctry = orSpaces(acsctry, ACSCTRY_LENGTH);
    }

    /**
     * {@code ACSPHN1}, the first telephone number.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAcsphn1() {
        return acsphn1;
    }

    /**
     * Stores {@code ACSPHN1} verbatim.
     *
     * @param acsphn1 the value; {@code null} is taken as {@value #ACSPHN1_LENGTH} spaces
     */
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

    /**
     * Stores {@code ACSGOVT} verbatim.
     *
     * @param acsgovt the value; {@code null} is taken as {@value #ACSGOVT_LENGTH} spaces
     */
    public void setAcsgovt(String acsgovt) {
        this.acsgovt = orSpaces(acsgovt, ACSGOVT_LENGTH);
    }

    /**
     * {@code ACSPHN2}, the second telephone number.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAcsphn2() {
        return acsphn2;
    }

    /**
     * Stores {@code ACSPHN2} verbatim.
     *
     * @param acsphn2 the value; {@code null} is taken as {@value #ACSPHN2_LENGTH} spaces
     */
    public void setAcsphn2(String acsphn2) {
        this.acsphn2 = orSpaces(acsphn2, ACSPHN2_LENGTH);
    }

    /**
     * {@code ACSEFTC}, the EFT account code.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAcseftc() {
        return acseftc;
    }

    /**
     * Stores {@code ACSEFTC} verbatim.
     *
     * @param acseftc the value; {@code null} is taken as {@value #ACSEFTC_LENGTH} spaces
     */
    public void setAcseftc(String acseftc) {
        this.acseftc = orSpaces(acseftc, ACSEFTC_LENGTH);
    }

    /**
     * {@code ACSPFLG}, the primary cardholder flag.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getAcspflg() {
        return acspflg;
    }

    /**
     * Stores {@code ACSPFLG} verbatim.
     *
     * @param acspflg the value; {@code null} is taken as {@value #ACSPFLG_LENGTH} spaces
     */
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

    /**
     * Stores {@code INFOMSG} verbatim.
     *
     * @param infomsg the value; {@code null} is taken as {@value #INFOMSG_LENGTH} spaces
     */
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

    /**
     * Stores {@code ERRMSG} verbatim.
     *
     * @param errmsg the value; {@code null} is taken as {@value #ERRMSG_LENGTH} spaces
     */
    public void setErrmsg(String errmsg) {
        this.errmsg = orSpaces(errmsg, ERRMSG_LENGTH);
    }

    // =================================================================================================
    // Addressing a field by its enumeration constant. A parity differ, a validation loop and the group
    // image codec all need to walk the 37 fields uniformly; these two methods let them do it without
    // reflection and without a string key that can be misspelled. Both switches are exhaustive over
    // ScreenField and carry no default arm, so adding a constant without handling it here is a
    // compile-time error rather than a silent gap.
    // =================================================================================================

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
     * Stores one field verbatim, chosen by its {@link ScreenField} constant. Delegates to the field's own
     * setter, so {@code null} handling is identical whichever route a caller takes.
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

    // =================================================================================================
    // The metadata half: xxxL and xxxA. Both accessors are @JsonIgnore'd, so no ScreenFieldMetadata
    // instance reaches the wire and the payload stays exactly the 37 DFHMDF fields plus the two
    // conversation-state carriers (gate G9).
    // =================================================================================================

    /**
     * The {@code xxxL} and {@code xxxA} items of one field.
     *
     * <p>The holder is returned live, not copied, because {@code COACTVWC} writes into these items in
     * place: {@code MOVE DFHBMFSE TO ACCTSIDA OF CACTVWAI} at {@code app/cbl/COACTVWC.cbl:543} and
     * {@code MOVE -1 TO ACCTSIDL OF CACTVWAI} at {@code :549} and {@code :551} are assignments into the
     * map area, and a defensive copy here would discard them.
     *
     * @param field which field's metadata to address
     * @return that field's holder, never {@code null} - all 37 are populated at construction
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public ScreenFieldMetadata metadata(ScreenField field) {
        Objects.requireNonNull(field, "A ScreenField is required to address a field's xxxL and xxxA "
                + "items");
        return metadata.get(field);
    }

    /**
     * All 37 metadata holders, keyed by field and iterating in copybook storage order.
     *
     * <p>The map is unmodifiable, so no caller can add a thirty-eighth field or remove one of the 37.
     * The <em>holders</em> remain mutable, deliberately, for the reason
     * {@link #metadata(ScreenField)} explains - so this is a guard on the shape of the group, not on its
     * contents.
     *
     * @return an unmodifiable view over the 37 live holders, never {@code null}
     */
    @JsonIgnore
    public Map<ScreenField, ScreenFieldMetadata> metadata() {
        return Collections.unmodifiableMap(metadata);
    }

    // =================================================================================================
    // The conversation-state carriers, and the ENTER-versus-REENTER question they answer.
    // =================================================================================================

    /**
     * The {@code CVCRD01Y} work area this request carries.
     *
     * @return the work area, never {@code null}
     */
    public CardScreenState getCardScreenState() {
        return cardScreenState;
    }

    /**
     * Replaces the {@code CVCRD01Y} work area.
     *
     * @param cardScreenState the work area; {@code null} is taken as a freshly initialised area, because
     *                        {@code COACTVWC} performs {@code INITIALIZE CC-WORK-AREA} before reading it
     */
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
     * <p>{@code null} is stored as {@code null} and is <strong>not</strong> substituted with
     * {@link NavigationContext#empty()}: an absent area is CICS reporting {@code EIBCALEN} as zero, which
     * an initialised area cannot express. See {@link #navigationContext}.
     *
     * @param navigationContext the communication area, or {@code null} to record that none travelled
     */
    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = navigationContext;
    }

    /**
     * Whether a communication area travelled with this request - the Java reading of {@code EIBCALEN}
     * being non-zero.
     *
     * <p>Not a JSON property: it is derived from {@link #getNavigationContext()}, which is already on the
     * wire as {@code null} or as an object, and a second member could contradict it.
     *
     * @return {@code true} when {@link #getNavigationContext()} is present
     */
    @JsonIgnore
    public boolean hasNavigationContext() {
        return navigationContext != null;
    }

    /**
     * The length CICS would report in {@code EIBCALEN}:
     * {@value NavigationContext#COMMAREA_LENGTH} when a communication area travelled with this request
     * and {@code 0} when none did.
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
     * <p>A caller that must tell an absent area from a present one holding
     * {@value NavigationContext#PGM_CONTEXT_ENTER} asks {@link #hasNavigationContext()}.
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
     * <p>The second arm of {@code app/cbl/COACTVWC.cbl:353}: on first entry {@code COACTVWC} performs
     * {@code 1000-SEND-MAP} and returns, painting the screen without reading anything. Delegates to
     * {@link NavigationContext#isEnter()}.
     *
     * <p>{@link JsonIgnore}d: the value is derived from {@link NavigationContext#pgmContext()}, which is
     * already on the wire inside the carrier, so publishing it again would let one payload carry the same
     * fact twice and disagree with itself.
     *
     * <p>False when no communication area travelled: a condition name is a test over a field, and there
     * is no field to test. With {@link #isReenter()} that gives three states rather than two.
     *
     * @return {@code true} when a communication area travelled and its carried program context is
     *         {@value NavigationContext#PGM_CONTEXT_ENTER}
     */
    @JsonIgnore
    public boolean isEnter() {
        return hasNavigationContext() && navigationContext.isEnter();
    }

    /**
     * Whether this is re-entry - {@code 88 CDEMO-PGM-REENTER VALUE 1}.
     *
     * <p>The third arm of {@code app/cbl/COACTVWC.cbl:361}: on re-entry {@code COACTVWC} performs
     * {@code 2000-PROCESS-INPUTS} and then either re-sends the map on error or reads the account and
     * sends it. It is also the conjunct that lets a field be highlighted at all - {@code :561-565}
     * applies {@code '*'} and {@code DFHRED} only under
     * {@code IF FLG-ACCTFILTER-BLANK AND CDEMO-PGM-REENTER} - so this is the value
     * {@code common/FieldAttributeSetter} takes as its re-entry argument (gate <strong>G38</strong>).
     *
     * <p>Deliberately not written as the negation of {@link #isEnter()}: {@code CDEMO-PGM-CONTEXT} is
     * {@code PIC 9(01)} and can hold any digit, so a context of 9 satisfies neither condition - and
     * neither does an absent communication area, which is the {@code WHEN OTHER} arm at {@code :377}.
     *
     * @return {@code true} when a communication area travelled and its carried program context is
     *         {@value NavigationContext#PGM_CONTEXT_REENTER}
     */
    @JsonIgnore
    public boolean isReenter() {
        return hasNavigationContext() && navigationContext.isReenter();
    }

    // =================================================================================================
    // Fixed-width rendering. This is the only place a field's declared width is imposed on its value, and
    // it is imposed by FixedWidthCodec and by nothing else (practice B11). The charset is always the
    // codec's, never a platform default (practice B8).
    // =================================================================================================

    /**
     * One field as an image of exactly its declared width: padded on the right with spaces when the
     * stored value is short, truncated on the right when it is long.
     *
     * <p>The truncation direction is the COBOL rule for an alphanumeric receiver - filled from the
     * leftmost character position, with the overflow discarded - and it is
     * {@link FixedWidthCodec#movePicX(String, int)} that applies it. Nothing in this class pads or
     * truncates by hand, so there is exactly one place in the system where that rule can be wrong.
     *
     * <p>{@link ScreenField#ACCTSID} is rendered by the same rule as the other 36. Its {@code PICIN} is
     * numeric, but the item is eleven characters of storage and the values the program tests for -
     * {@code "*"} and spaces - are alphanumeric, so a zero-filling numeric move would be the wrong rule
     * here.
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
     * <p>The explicit equivalent of a COBOL {@code MOVE} into each {@code xxxI} receiver. A controller or
     * a parity case calls it once, deliberately, when it wants the request to hold map-shaped values -
     * before a field-by-field comparison, for instance, where a short value and its space-padded image
     * are different byte strings and must not be confused.
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
     * The whole {@code CACTVWAI} input group as exactly {@value #GROUP_LENGTH} bytes.
     *
     * <p>Laid out precisely as {@code app/cpy-bms/COACTVW.CPY:17-240} declares it:
     *
     * <ol>
     *   <li>{@value #TIOAPFX_LENGTH} bytes of {@code TIOAPFX} prefix, space-filled in the codec's
     *       charset. The prefix carries no application data; it is written so the group is the width the
     *       copybook declares.</li>
     *   <li>then, for each of the 37 fields in copybook order: the {@code xxxL} halfword as two
     *       big-endian bytes, the {@code xxxA} attribute byte raw,
     *       {@value #EXTENDED_ATTRIBUTE_ITEM_LENGTH} bytes of extended-attribute {@code FILLER} left at
     *       {@code LOW-VALUES}, and the field's image at its declared width.</li>
     * </ol>
     *
     * <p>The halfword is written big-endian, which is how a mainframe halfword is laid out, so
     * {@value ScreenFieldMetadata#CURSOR_HERE} renders as {@code 0xFFFF} - two's complement, exactly as
     * {@code COMP PIC S9(4)} storage holds it.
     *
     * <p>The character data is encoded through {@link FixedWidthCodec#charset()} and never through a
     * platform default, so a space is {@code 0x40} under IBM037 and {@code 0x20} under US-ASCII without
     * this method knowing which. If a charset encodes any field's image to a number of bytes other than
     * its declared width - as a multi-byte code page would - the method fails loudly rather than
     * returning a group of the wrong length.
     *
     * <p>The attribute byte is <em>not</em> encoded through the charset. A 3270 attribute is a bit pattern
     * rather than text, is not a character in any code page's printable range, and passing it through a
     * character encoder would corrupt it. {@code DFHBMFSE} is the same byte whether the data around it is
     * EBCDIC or ASCII, so it is written and read raw.
     *
     * @param codec the codec supplying the move rule and the charset
     * @return a new array of exactly {@value #GROUP_LENGTH} bytes, never {@code null}
     * @throws NullPointerException     if {@code codec} is {@code null}
     * @throws IllegalArgumentException if the codec's charset does not encode this data one byte per
     *                                  character
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
            // Written rather than left to Java's zero-initialisation, so that "these four bytes are
            // LOW-VALUES on input" is a statement the code makes instead of an accident of the language.
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
     * Reads a {@value #GROUP_LENGTH}-byte {@code CACTVWAI} image back into a request.
     *
     * <p>Recovers the 37 {@code xxxI} values at their full declared widths - untrimmed, because a
     * {@code PIC X} field's trailing spaces are part of its value - together with each field's
     * {@code xxxL} halfword and {@code xxxA} attribute byte.
     *
     * <p>Three parts of the image are deliberately <strong>not</strong> recovered, because none of them is
     * data:
     *
     * <ul>
     *   <li>the {@value #TIOAPFX_LENGTH}-byte {@code TIOAPFX} prefix, which belongs to the terminal
     *       input/output area and not to the application;</li>
     *   <li>the {@value #EXTENDED_ATTRIBUTE_ITEM_LENGTH} extended-attribute {@code FILLER} bytes per
     *       field, unnamed and unused on input - the output group names them {@code xxxC}, {@code xxxP},
     *       {@code xxxH} and {@code xxxV}, and they are {@code AccountViewResponse}'s concern;</li>
     *   <li>the two conversation-state carriers, which are separate storage entirely: the
     *       {@code CVCRD01Y} work area and the {@code CARDDEMO-COMMAREA} are not part of the BMS map and
     *       travel on their own. The returned request carries a freshly initialised work area and no
     *       communication area.</li>
     * </ul>
     *
     * <p>So the round trip is exact where it can be: for a request whose carriers are at their initialised
     * state, {@code fromGroupImage(x.toGroupImage(codec), codec)} equals {@code x} whenever {@code x}'s 37
     * values are already at their declared widths. For a request holding off-width values, normalise it
     * first - {@link #normalize(FixedWidthCodec)} - since the image can only hold the widths the copybook
     * declares.
     *
     * @param groupImage the {@value #GROUP_LENGTH}-byte input group; read, never retained
     * @param codec      the codec supplying the charset
     * @return a request carrying the image's 37 fields and their metadata, never {@code null}
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code groupImage} is not exactly {@value #GROUP_LENGTH} bytes,
     *                                  or holds a length item outside the range {@code COMP PIC S9(4)}
     *                                  can represent
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
     * <p>The cast to {@code short} is what makes the sign work: the two bytes are combined as an
     * {@code int} and then narrowed, so {@code 0xFFFF} reads back as
     * {@value ScreenFieldMetadata#CURSOR_HERE} rather than as 65535.
     *
     * <p>Storage capacity and declared capacity differ here, and the declaration wins. A halfword holds
     * &plusmn;32767 but {@code S9(4)} holds only {@value ScreenFieldMetadata#LENGTH_ITEM_MIN} to
     * {@value ScreenFieldMetadata#LENGTH_ITEM_MAX}, so a byte pair outside that range means the image and
     * the copybook disagree - and accepting it quietly is exactly how a plausible-looking parity defect
     * gets in.
     *
     * @param groupImage the group image to read from
     * @param field      which field's length item to read
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
     * Decodes one field's data span from a group image under the codec's code page.
     *
     * <p>The span is copied out before decoding because {@link FixedWidthCodec#decodeImage(byte[], String)}
     * decodes a whole array; copying the exact declared width is what keeps the decode bounded to this
     * field rather than reaching into its neighbour.
     *
     * @param groupImage the group image to read from
     * @param field      which field's data to read
     * @param codec      the codec supplying the charset
     * @return the field's value at its full declared width, untrimmed
     * @throws IllegalStateException if a stored byte is not valid data in the codec's code page
     */
    private static String readCharacters(byte[] groupImage, ScreenField field, FixedWidthCodec codec) {
        byte[] span = Arrays.copyOfRange(groupImage, field.dataOffset(), field.endOffsetExclusive());
        return codec.decodeImage(span, "the CACTVWAI item " + field.symbolicItemName());
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
     * <p>Two guards stand between that and the group image, and only one of them lives here.
     * {@link FixedWidthCodec#encodeImage(String, String)} is the outer one: it enforces one byte per
     * character itself and reports a multi-byte code page - naming this item through {@code what} -
     * before returning, so the charset route never reaches the comparison below. What the comparison
     * catches is the other way of being wrong: an {@code image} that is not already at
     * {@code declaredWidth} characters. Every caller here obtains {@code image} from
     * {@link #image(ScreenField, FixedWidthCodec)} or from {@link #spaces(int)}, both of which produce
     * exactly the declared width, so it cannot fire today - which is precisely why it is worth keeping.
     * It is the assertion that a future edit passing an unnormalised value fails immediately and by name,
     * rather than writing a short field and shifting every offset after it. Being unreachable is the
     * property being asserted, not a gap in the tests.
     *
     * @param group         the group image being built
     * @param offset        where the item begins
     * @param image         the item's value, already at its declared character width
     * @param declaredWidth the item's declared width in bytes
     * @param codec         the codec whose charset encodes the text
     * @param what          how to describe the item in a failure message
     * @throws IllegalArgumentException if the codec's charset is not single-byte over this data, or if
     *                                  the encoding is not exactly {@code declaredWidth} bytes
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
                    + "; a PIC X(n) item is n bytes, so the CACTVWAI group can only be rendered from an "
                    + "image already at its declared width under a single-byte code page such as IBM037 "
                    + "or US-ASCII");
        }
        System.arraycopy(encoded, 0, group, offset, declaredWidth);
    }

    // =================================================================================================
    // Value semantics. Everything the instance holds participates: the 37 fields, both carriers and all 37
    // metadata holders. The metadata is included because it is behaviour - a request with the cursor on
    // ACCTSID is not the same request as one without it - even though it never reaches the wire.
    // =================================================================================================

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

    /**
     * Whether all 37 screen values match, compared through {@link #value(ScreenField)} so that the
     * comparison walks the same 37 members {@link #equals(Object)} promises and cannot omit one.
     *
     * <p>Written as a loop rather than 37 conjuncts precisely because a hand-written chain of 37 is where
     * a field gets forgotten, and a forgotten field makes two different requests compare equal.
     *
     * @param that the request to compare with
     * @return {@code true} when every field holds an equal value
     */
    private boolean sameFieldValues(AccountViewRequest that) {
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
     * <p>Built by walking {@link ScreenField} in declaration order and then folding in both carriers and
     * the metadata map, so it covers exactly what {@link #equals(Object)} compares.
     *
     * @return the combined hash of every value this request holds
     */
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
     * A diagnostic rendering naming every field by its {@code DFHMDF} label.
     *
     * <p><strong>Nothing is masked, redacted or abbreviated.</strong> {@code ACSTSSN}, {@code ACSTDOB} and
     * {@code ACSGOVT} render exactly as stored, because the COBOL displays them on a 3270 in the clear and
     * hiding them here would be an unrequested behaviour change (practice <strong>B6</strong>). This is a
     * deliberate divergence from the sibling payloads, which route their diagnostics through
     * {@code common/SensitiveDiagnostics}; it is recorded in this type's own documentation rather than
     * left to be discovered, and it is reinforced by that helper being outside this file's declared
     * dependency set. Nothing is weakened either - no value is exposed here that the screen does not
     * already show.
     *
     * <p>Values are quoted so that the trailing spaces of a fixed-width field, which are part of its
     * value, stay visible in a failure message, and each field's metadata is appended so the cursor
     * position and attribute byte are readable too.
     *
     * @return a single-line rendering of every field, both carriers and all 37 metadata holders
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

    // =================================================================================================
    // The 37 fields, as an enumeration. This exists so that every place needing to address a field - the
    // group-image codec, the metadata holder, a parity differ - names it in a way the compiler checks,
    // instead of passing a string that can be misspelled. Each constant carries the whole of its
    // provenance: the DFHMDF label, the symbolic-map item name, the PICTURE as written, the width, both
    // source line numbers, the screen position and the data offset, so the gate G9 trace is readable from
    // one place and verifiable against the copybook without arithmetic.
    // =================================================================================================

    /**
     * One of the 37 name-labelled {@code DFHMDF} fields of {@code app/bms/COACTVW.bms}, in the order the
     * mapset declares them - which is also the order {@code app/cpy-bms/COACTVW.CPY} lays their storage
     * down.
     *
     * <p>That order interleaves the screen's two columns: {@code ADTOPEN} at {@code POS=(6,17)} is
     * followed by {@code ACRDLIM} at {@code POS=(6,61)}, then {@code AEXPDT} at {@code POS=(7,17)} by
     * {@code ACSHLIM} at {@code POS=(7,61)}, and so on. It is the copybook's order and it is not sorted.
     *
     * <p>The 63 unnamed {@code DFHMDF} entries of the mapset have no constant here. An unnamed entry is a
     * screen literal - {@code INITIAL='Tran:'}, {@code INITIAL='Account Number:'} and the like - which BMS
     * paints but never reports back, so it generates no symbolic-map item and holds no value this class
     * could carry.
     *
     * <p>{@code FKEYS}, {@code FKEY05}, {@code FKEY12} and {@code PAGENO} are absent because
     * {@code COACTVW} declares none of them; they belong to sibling mapsets and are not added for
     * symmetry.
     */
    public enum ScreenField {

        /** {@code TRNNAME} - the transaction identifier, {@code TRNNAMEI PIC X(4)}. */
        TRNNAME("TRNNAME", "TRNNAMEI", "X(4)", TRNNAME_LENGTH, 24, 34, 1, 7, 19),

        /** {@code TITLE01} - the first title line, {@code TITLE01I PIC X(40)}. */
        TITLE01("TITLE01", "TITLE01I", "X(40)", TITLE01_LENGTH, 30, 38, 1, 21, 30),

        /** {@code CURDATE} - the current date, {@code CURDATEI PIC X(8)}. */
        CURDATE("CURDATE", "CURDATEI", "X(8)", CURDATE_LENGTH, 36, 47, 1, 71, 77),

        /** {@code PGMNAME} - the program name, {@code PGMNAMEI PIC X(8)}. */
        PGMNAME("PGMNAME", "PGMNAMEI", "X(8)", PGMNAME_LENGTH, 42, 57, 2, 7, 92),

        /** {@code TITLE02} - the second title line, {@code TITLE02I PIC X(40)}. */
        TITLE02("TITLE02", "TITLE02I", "X(40)", TITLE02_LENGTH, 48, 61, 2, 21, 107),

        /** {@code CURTIME} - the current time, {@code CURTIMEI PIC X(8)}. */
        CURTIME("CURTIME", "CURTIMEI", "X(8)", CURTIME_LENGTH, 54, 70, 2, 71, 154),

        /**
         * {@code ACCTSID} - the account identifier, {@code ACCTSIDI PIC 99999999999}.
         *
         * <p>The one constant here whose {@link #picture()} is not alphanumeric, and the one field an
         * operator types: {@code app/bms/COACTVW.bms:84-95} declares
         * {@code ATTRB=(FSET,IC,NORM,UNPROT) COLOR=GREEN HILIGHT=UNDERLINE PICIN='99999999999'
         * VALIDN=(MUSTFILL)}. {@code IC} places the insertion cursor here and {@code UNPROT} makes it the
         * only writable field on the screen; {@code MUSTFILL} is the terminal's own "all eleven positions
         * or none" rule.
         *
         * <p>Both are carried as documentation rather than enforced as a Java type or a
         * {@code @Pattern}, because {@code app/cbl/COACTVWC.cbl:628-629} tests the received value against
         * {@code '*'} and against {@code SPACES}. See {@link AccountViewRequest#getAcctsid()}.
         */
        ACCTSID("ACCTSID", "ACCTSIDI", "99999999999", ACCTSID_LENGTH, 60, 84, 5, 38, 169),

        /** {@code ACSTTUS} - the account status, {@code ACSTTUSI PIC X(1)}. */
        ACSTTUS("ACSTTUS", "ACSTTUSI", "X(1)", ACSTTUS_LENGTH, 66, 97, 5, 70, 187),

        /** {@code ADTOPEN} - the account open date, {@code ADTOPENI PIC X(10)}. */
        ADTOPEN("ADTOPEN", "ADTOPENI", "X(10)", ADTOPEN_LENGTH, 72, 107, 6, 17, 195),

        /**
         * {@code ACRDLIM} - the credit limit, {@code ACRDLIMI PIC X(15)}.
         *
         * <p>The first of five money fields. {@code app/bms/COACTVW.bms:117-124} adds
         * {@code JUSTIFY=(RIGHT)} and {@code PICOUT='+ZZZ,ZZZ,ZZZ.99'}; the edit mask shapes the
         * {@code xxxO} twin and belongs to {@code AccountViewResponse}.
         */
        ACRDLIM("ACRDLIM", "ACRDLIMI", "X(15)", ACRDLIM_LENGTH, 78, 117, 6, 61, 212),

        /** {@code AEXPDT} - the account expiry date, {@code AEXPDTI PIC X(10)}. */
        AEXPDT("AEXPDT", "AEXPDTI", "X(10)", AEXPDT_LENGTH, 84, 128, 7, 17, 234),

        /** {@code ACSHLIM} - the cash credit limit, {@code ACSHLIMI PIC X(15)}. A money field. */
        ACSHLIM("ACSHLIM", "ACSHLIMI", "X(15)", ACSHLIM_LENGTH, 90, 138, 7, 61, 251),

        /** {@code AREISDT} - the account reissue date, {@code AREISDTI PIC X(10)}. */
        AREISDT("AREISDT", "AREISDTI", "X(10)", AREISDT_LENGTH, 96, 149, 8, 17, 273),

        /** {@code ACURBAL} - the current balance, {@code ACURBALI PIC X(15)}. A money field. */
        ACURBAL("ACURBAL", "ACURBALI", "X(15)", ACURBAL_LENGTH, 102, 159, 8, 61, 290),

        /** {@code ACRCYCR} - the current cycle credit, {@code ACRCYCRI PIC X(15)}. A money field. */
        ACRCYCR("ACRCYCR", "ACRCYCRI", "X(15)", ACRCYCR_LENGTH, 108, 171, 9, 61, 312),

        /** {@code AADDGRP} - the account group identifier, {@code AADDGRPI PIC X(10)}. */
        AADDGRP("AADDGRP", "AADDGRPI", "X(10)", AADDGRP_LENGTH, 114, 182, 10, 23, 334),

        /** {@code ACRCYDB} - the current cycle debit, {@code ACRCYDBI PIC X(15)}. A money field. */
        ACRCYDB("ACRCYDB", "ACRCYDBI", "X(15)", ACRCYDB_LENGTH, 120, 192, 10, 61, 351),

        /** {@code ACSTNUM} - the customer number, {@code ACSTNUMI PIC X(9)}. */
        ACSTNUM("ACSTNUM", "ACSTNUMI", "X(9)", ACSTNUM_LENGTH, 126, 207, 12, 23, 373),

        /** {@code ACSTSSN} - the customer social security number, {@code ACSTSSNI PIC X(12)}. */
        ACSTSSN("ACSTSSN", "ACSTSSNI", "X(12)", ACSTSSN_LENGTH, 132, 216, 12, 54, 389),

        /** {@code ACSTDOB} - the customer date of birth, {@code ACSTDOBI PIC X(10)}. */
        ACSTDOB("ACSTDOB", "ACSTDOBI", "X(10)", ACSTDOB_LENGTH, 138, 225, 13, 23, 408),

        /** {@code ACSTFCO} - the customer FICO score, {@code ACSTFCOI PIC X(3)}. */
        ACSTFCO("ACSTFCO", "ACSTFCOI", "X(3)", ACSTFCO_LENGTH, 144, 234, 13, 61, 425),

        /** {@code ACSFNAM} - the customer's given name, {@code ACSFNAMI PIC X(25)}. */
        ACSFNAM("ACSFNAM", "ACSFNAMI", "X(25)", ACSFNAM_LENGTH, 150, 251, 15, 1, 435),

        /** {@code ACSMNAM} - the customer's middle name, {@code ACSMNAMI PIC X(25)}. */
        ACSMNAM("ACSMNAM", "ACSMNAMI", "X(25)", ACSMNAM_LENGTH, 156, 256, 15, 28, 467),

        /** {@code ACSLNAM} - the customer's surname, {@code ACSLNAMI PIC X(25)}. */
        ACSLNAM("ACSLNAM", "ACSLNAMI", "X(25)", ACSLNAM_LENGTH, 162, 261, 15, 55, 499),

        /** {@code ACSADL1} - the first address line, {@code ACSADL1I PIC X(50)}. */
        ACSADL1("ACSADL1", "ACSADL1I", "X(50)", ACSADL1_LENGTH, 168, 268, 16, 10, 531),

        /** {@code ACSSTTE} - the state code, {@code ACSSTTEI PIC X(2)}. */
        ACSSTTE("ACSSTTE", "ACSSTTEI", "X(2)", ACSSTTE_LENGTH, 174, 277, 16, 73, 588),

        /** {@code ACSADL2} - the second address line, {@code ACSADL2I PIC X(50)}. */
        ACSADL2("ACSADL2", "ACSADL2I", "X(50)", ACSADL2_LENGTH, 180, 282, 17, 10, 597),

        /**
         * {@code ACSZIPC} - the postal code, {@code ACSZIPCI PIC X(5)}.
         *
         * <p>{@code app/bms/COACTVW.bms:291-296} declares {@code JUSTIFY=(RIGHT)}, a terminal alignment
         * rule rather than a stored transformation.
         */
        ACSZIPC("ACSZIPC", "ACSZIPCI", "X(5)", ACSZIPC_LENGTH, 186, 291, 17, 73, 654),

        /** {@code ACSCITY} - the city, {@code ACSCITYI PIC X(50)}. */
        ACSCITY("ACSCITY", "ACSCITYI", "X(50)", ACSCITY_LENGTH, 192, 301, 18, 10, 666),

        /** {@code ACSCTRY} - the country code, {@code ACSCTRYI PIC X(3)}. */
        ACSCTRY("ACSCTRY", "ACSCTRYI", "X(3)", ACSCTRY_LENGTH, 198, 310, 18, 73, 723),

        /** {@code ACSPHN1} - the first telephone number, {@code ACSPHN1I PIC X(13)}. */
        ACSPHN1("ACSPHN1", "ACSPHN1I", "X(13)", ACSPHN1_LENGTH, 204, 319, 19, 10, 733),

        /** {@code ACSGOVT} - the government-issued identifier, {@code ACSGOVTI PIC X(20)}. */
        ACSGOVT("ACSGOVT", "ACSGOVTI", "X(20)", ACSGOVT_LENGTH, 210, 326, 19, 58, 753),

        /** {@code ACSPHN2} - the second telephone number, {@code ACSPHN2I PIC X(13)}. */
        ACSPHN2("ACSPHN2", "ACSPHN2I", "X(13)", ACSPHN2_LENGTH, 216, 335, 20, 10, 780),

        /** {@code ACSEFTC} - the EFT account code, {@code ACSEFTCI PIC X(10)}. */
        ACSEFTC("ACSEFTC", "ACSEFTCI", "X(10)", ACSEFTC_LENGTH, 222, 342, 20, 41, 800),

        /** {@code ACSPFLG} - the primary cardholder flag, {@code ACSPFLGI PIC X(1)}. */
        ACSPFLG("ACSPFLG", "ACSPFLGI", "X(1)", ACSPFLG_LENGTH, 228, 351, 20, 78, 817),

        /**
         * {@code INFOMSG} - the informational message line, {@code INFOMSGI PIC X(45)}.
         *
         * <p>{@code app/bms/COACTVW.bms:356-363} declares {@code ATTRB=(PROT) COLOR=NEUTRAL HILIGHT=OFF}.
         * Forty-five characters, fed from a forty-character working-storage item.
         */
        INFOMSG("INFOMSG", "INFOMSGI", "X(45)", INFOMSG_LENGTH, 234, 356, 22, 23, 825),

        /**
         * {@code ERRMSG} - the error message line, {@code ERRMSGI PIC X(78)}.
         *
         * <p>{@code app/bms/COACTVW.bms:365-372} declares {@code ATTRB=(ASKIP,BRT,FSET) COLOR=RED}.
         * Seventy-eight characters, fed from a seventy-five-character working-storage item, and the last
         * field in the group - its storage ends exactly on
         * {@value AccountViewRequest#GROUP_LENGTH}.
         */
        ERRMSG("ERRMSG", "ERRMSGI", "X(78)", ERRMSG_LENGTH, 240, 365, 23, 1, 877);

        /** The {@code DFHMDF} label, verbatim. */
        private final String label;

        /** The input group's {@code xxxI} item name. */
        private final String symbolicItemName;

        /** The {@code xxxI} {@code PICTURE} as the copybook writes it. */
        private final String picture;

        /** The declared width in characters. */
        private final int length;

        /** The {@code app/cpy-bms/COACTVW.CPY} line declaring the {@code xxxI} item. */
        private final int copybookLine;

        /** The {@code app/bms/COACTVW.bms} line opening the {@code DFHMDF} entry. */
        private final int mapsetLine;

        /** The {@code POS=} row. */
        private final int screenRow;

        /** The {@code POS=} column. */
        private final int screenColumn;

        /** Where the field's data begins in the group image. */
        private final int dataOffset;

        /**
         * Binds one constant to the whole of its provenance, so that every fact a reviewer needs in order
         * to check it against {@code app/cpy-bms/COACTVW.CPY} and {@code app/bms/COACTVW.bms} sits on the
         * constant itself rather than being derived somewhere else.
         *
         * @param label            the {@code DFHMDF} label
         * @param symbolicItemName the input group's {@code xxxI} item name
         * @param picture          the {@code xxxI} {@code PICTURE} as written - {@code X(n)} for 36 of the
         *                         37 and {@code 99999999999} for {@code ACCTSID}
         * @param length           the declared width in characters
         * @param copybookLine     the {@code COACTVW.CPY} line declaring the {@code xxxI} item
         * @param mapsetLine       the {@code COACTVW.bms} line opening the {@code DFHMDF} entry
         * @param screenRow        the {@code POS=} row, 1 to 24
         * @param screenColumn     the {@code POS=} column, 1 to 80
         * @param dataOffset       where the field's data begins in the group image
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
         * The field's {@code DFHMDF} label, spelled exactly as {@code app/bms/COACTVW.bms} spells it.
         *
         * <p>Carried verbatim, in upper case and without decoration, because this is the name a
         * field-by-field parity diff reports and a "tidied" name would make a real difference
         * unrecognisable.
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
         * <p>{@code X(n)} for 36 of the 37 fields and {@code 99999999999} for {@link #ACCTSID}. Carried
         * rather than derived from {@link #length()} precisely because of that exception: a derived
         * {@code X(11)} would erase the one place the two groups disagree.
         *
         * @return the {@code PICTURE} text, never {@code null}
         */
        public String picture() {
            return picture;
        }

        /**
         * Whether this field's {@code xxxI} {@code PICTURE} is alphanumeric.
         *
         * <p>True for 36 of the 37. {@link #ACCTSID} is the exception, and it is still carried as a
         * {@link String} - see {@link AccountViewRequest#getAcctsid()} for why.
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
         * Offset of this field's {@code xxxI} data within the
         * {@value AccountViewRequest#GROUP_LENGTH}-byte group image.
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
         *         {@link #dataOffset()}
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
         *         {@code ACSGOVT ACSGOVTI PIC X(20) COACTVW.CPY:210 COACTVW.bms:326 POS=(19,58) offset 753..773}
         */
        public String describe() {
            return label + " " + symbolicItemName + " PIC " + picture + " COACTVW.CPY:" + copybookLine
                    + " COACTVW.bms:" + mapsetLine + " POS=(" + screenRow + "," + screenColumn
                    + ") offset " + dataOffset + ".." + endOffsetExclusive();
        }

        /**
         * The field carrying a given {@code DFHMDF} label.
         *
         * <p>Matching is exact and case-sensitive, because {@code app/bms/COACTVW.bms} spells every label
         * in upper case and a lenient match would let a misspelling resolve to the wrong field.
         *
         * @param label the {@code DFHMDF} label to look up, for example {@code ACSGOVT}
         * @return the matching field, never {@code null}
         * @throws NullPointerException     if {@code label} is {@code null}
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

    // =================================================================================================
    // The non-payload half of each field: the xxxL length halfword and the xxxA attribute byte. These are
    // storage the copybook declares and the program writes, so they exist here - but they are not data the
    // screen carries, so they are kept off the JSON wire.
    // =================================================================================================

    /**
     * The {@code xxxL} and {@code xxxA} items of one input field: the two pieces of per-field metadata a
     * BMS symbolic map declares beside the data, and the two {@code COACTVWC} actually writes.
     *
     * <p>Never serialised. {@link AccountViewRequest#metadata(ScreenField)} and
     * {@link AccountViewRequest#metadata()} are both {@link JsonIgnore}d, so no instance of this class
     * reaches the wire. Publishing it would add payload members tracing to no {@code DFHMDF} field and
     * break gate <strong>G9</strong>; omitting it altogether would lose the cursor positioning and the
     * field protection {@code COACTVWC} performs. Holding it here, off the wire, keeps both true.
     *
     * <h2>The length item</h2>
     *
     * <p>{@code 02 xxxL COMP PIC S9(4)} is a signed binary halfword. On a {@code RECEIVE MAP} CICS sets it
     * to the number of characters the operator typed, so zero means the field was not entered - the only
     * mechanism a symbolic map offers for telling "left blank" from "blanked out". On a {@code SEND MAP}
     * the value {@value #CURSOR_HERE} has a second meaning: it asks CICS to place the cursor on that
     * field. {@code app/cbl/COACTVWC.cbl} uses exactly that, in {@code 1300-SETUP-SCREEN-ATTRS}:
     *
     * <pre>
     *   EVALUATE TRUE
     *      WHEN FLG-ACCTFILTER-NOT-OK
     *      WHEN FLG-ACCTFILTER-BLANK
     *           MOVE -1             TO ACCTSIDL OF CACTVWAI      *&gt; line 549
     *      WHEN OTHER
     *           MOVE -1             TO ACCTSIDL OF CACTVWAI      *&gt; line 551
     *   END-EVALUATE
     * </pre>
     *
     * <p>Both arms are identical, so the {@code EVALUATE} has no observable effect: whichever way it goes,
     * the cursor lands on {@code ACCTSID} - which is the only unprotected field on the screen, so there is
     * nowhere else for it to go. That redundancy is recorded rather than removed (practice
     * <strong>B5</strong>): it is what the source says, a Java translation of the paragraph must keep both
     * arms, and quietly collapsing it would erase the evidence that the two conditions were once expected
     * to differ.
     *
     * <p>The declared {@code PICTURE} is the constraint, not the storage: a halfword holds &plusmn;32767,
     * but {@code S9(4)} holds only {@value #LENGTH_ITEM_MIN} to {@value #LENGTH_ITEM_MAX}, and
     * {@link #setLength(int)} enforces the {@code PICTURE}. Rejecting a value the copybook cannot represent
     * is the point: silently storing 30000 in a field declared {@code S9(4)} is precisely the kind of
     * divergence a parity migration exists to prevent.
     *
     * <h2>The attribute item</h2>
     *
     * <p>{@code 02 xxxF PICTURE X} is the flag byte and {@code 03 xxxA PICTURE X}, declared under
     * {@code 02 FILLER REDEFINES xxxF}, is a second view of that same single byte. There is therefore one
     * value here, not two - {@link #getAttribute()} is the {@code xxxA} view and {@link #getFlag()} the
     * {@code xxxF} view of the identical byte, which is what a {@code REDEFINES} means.
     *
     * <p>{@code COACTVWC} writes it through the {@code xxxA} view at
     * {@code app/cbl/COACTVWC.cbl:543}: {@code MOVE DFHBMFSE TO ACCTSIDA OF CACTVWAI} - unprotected, with
     * the modified-data tag forced on, so the field is returned on the next {@code RECEIVE} whether the
     * operator retypes it or not. That the write lands on the <em>input</em> group is why this holder is
     * mutable and why {@link AccountViewRequest#metadata(ScreenField)} hands it back live rather than
     * copying it.
     *
     * <p>The constant itself lives in {@code common/BmsAttributes}, sourced from IBM CICS documentation
     * because {@code DFHBMSCA} is absent from this repository. This class stores whichever byte it is
     * handed and interprets none of them, so it needs no knowledge of the attribute alphabet and takes no
     * dependency on that type.
     *
     * <p>It is held as a {@code byte} rather than as a character because a 3270 attribute is a bit pattern,
     * not text: it is not a character in any code page's printable range, and passing it through a
     * character decoder would corrupt it. The same byte means the same thing whether the data around it is
     * EBCDIC or ASCII, so it is written and read raw and no charset is consulted for it.
     *
     * <p>The unset value is {@code LOW-VALUES}, a binary zero: a freshly initialised map area holds it, and
     * CICS leaves the byte alone on input when nothing was modified.
     *
     * <p>Mutable by design, like the map area it models, and therefore not thread safe. An instance belongs
     * to one {@link AccountViewRequest}, which belongs to one request.
     */
    public static final class ScreenFieldMetadata {

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
         * convention {@code app/cbl/COACTVWC.cbl} uses at lines 549 and 551.
         */
        public static final int CURSOR_HERE = -1;

        /**
         * The unset attribute byte: {@code LOW-VALUES}, which is binary zero on every code page because it
         * is by definition the lowest character of the collating sequence.
         */
        public static final byte ATTRIBUTE_UNSET = LOW_VALUE_BYTE;

        /**
         * The {@code xxxL COMP PIC S9(4)} halfword, held as an {@code int} and constrained to the
         * {@code PICTURE} range by {@link #setLength(int)} rather than to the halfword's wider capacity.
         */
        private int length;

        /**
         * The single byte declared as {@code xxxF PICTURE X} and redefined as {@code xxxA PICTURE X} - one
         * field, because a {@code REDEFINES} over one position is one position.
         */
        private byte attribute;

        /**
         * A freshly initialised pair: {@link #getLength()} is {@value #LENGTH_UNSET} and
         * {@link #getAttribute()} is {@code LOW-VALUES}.
         *
         * <p>That is the state a map area holds before CICS or the program touches it, so it is the state
         * every field of a new {@link AccountViewRequest} starts in.
         */
        public ScreenFieldMetadata() {
            this.length = LENGTH_UNSET;
            this.attribute = ATTRIBUTE_UNSET;
        }

        /**
         * A pair carrying explicit values.
         *
         * @param length    the {@code xxxL} halfword, between {@value #LENGTH_ITEM_MIN} and
         *                  {@value #LENGTH_ITEM_MAX}
         * @param attribute the {@code xxxA} attribute byte, for example one of the
         *                  {@code common/BmsAttributes} constants
         * @throws IllegalArgumentException if {@code length} is outside the {@code PIC S9(4)} range
         */
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
         *
         * <p>The per-field half of {@link AccountViewRequest#initializeMapArea()}.
         */
        public void reset() {
            this.length = LENGTH_UNSET;
            this.attribute = ATTRIBUTE_UNSET;
        }

        /**
         * The {@code xxxL COMP PIC S9(4)} halfword.
         *
         * @return the stored value, between {@value #LENGTH_ITEM_MIN} and {@value #LENGTH_ITEM_MAX}
         */
        public int getLength() {
            return length;
        }

        /**
         * Stores the {@code xxxL} halfword.
         *
         * @param length the value to store
         * @throws IllegalArgumentException if {@code length} is outside the range {@code PIC S9(4)} can
         *                                  represent
         */
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
         * reports a non-zero length for a value that is nonetheless blank. The two are different questions
         * and {@code COACTVWC} asks the second one itself, comparing the data against {@code SPACES} at
         * {@code app/cbl/COACTVWC.cbl:629}.
         *
         * @return {@code true} when the length item is {@value #LENGTH_UNSET}
         */
        public boolean isLengthUnset() {
            return length == LENGTH_UNSET;
        }

        /**
         * Whether the cursor is directed at this field - {@link #getLength()} is {@value #CURSOR_HERE}.
         *
         * @return {@code true} when the length item is {@value #CURSOR_HERE}
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
         * this returns exactly what {@link #getAttribute()} returns. Both views are offered because the
         * copybook offers both and a reader looking for {@code xxxF} should find it, but there is only one
         * value and no way for the two to disagree - which is the whole meaning of a {@code REDEFINES} over
         * a single byte.
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
         * <p>Every one of the 256 possible values is accepted, because a 3270 attribute is a bit pattern
         * and this class does not interpret it. Validating it against a list of known constants would
         * reject a legitimate combination and would duplicate knowledge that belongs in
         * {@code common/BmsAttributes}.
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

        /**
         * Value equality over both items.
         *
         * @param other the object to compare with
         * @return {@code true} when {@code other} is a pair carrying the same length and attribute
         */
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

        /**
         * A hash consistent with {@link #equals(Object)}.
         *
         * @return the combined hash of both items
         */
        @Override
        public int hashCode() {
            return Objects.hash(length, attribute);
        }

        /**
         * A diagnostic rendering. The attribute byte is shown as two hexadecimal digits rather than as a
         * character, because a 3270 attribute is a bit pattern and is rarely printable - {@code LOW-VALUES}
         * is {@code 0x00}.
         *
         * @return for example {@code ScreenFieldMetadata[length=-1, attribute=0xC1]}
         */
        @Override
        public String toString() {
            return "ScreenFieldMetadata[length=" + length + ", attribute=0x"
                    + String.format("%02X", attribute) + "]";
        }
    }
}
