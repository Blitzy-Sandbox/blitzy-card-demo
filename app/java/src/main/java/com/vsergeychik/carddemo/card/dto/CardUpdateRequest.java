package com.vsergeychik.carddemo.card.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
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
 * Inbound REST payload for {@code PUT /api/cards/{cardNum}} - the request half of CICS transaction
 * {@code CCUP}, whose backing program is {@code app/cbl/COCRDUPC.cbl} (1,560 lines).
 *
 * <h2>What this type is a projection of</h2>
 *
 * <p>This is a like-for-like COBOL to Java translation, so nothing here is designed: every member is
 * a projection of a declaration in the legacy sources, and the sources are the contract.
 *
 * <ul>
 *   <li>{@code app/cpy-bms/COCRDUP.CPY} (224 lines) - the symbolic map. Its input group
 *       {@code 01 CCRDUPAI.} at line 17 supplies this type's screen fields.</li>
 *   <li>{@code app/bms/COCRDUP.bms} (172 lines) - the mapset. It declares <strong>34</strong>
 *       {@code DFHMDF} entries of which exactly <strong>17 are name-labelled</strong>; the other 17
 *       are unnamed literal {@code INITIAL} entries - screen furniture - and get no Java field.</li>
 *   <li>{@code app/cbl/COCRDUPC.cbl} lines 274-321 - {@code 01 WS-THIS-PROGCOMMAREA}, the program
 *       commarea, modelled here as the nested {@link CommArea}.</li>
 *   <li>{@code app/cpy/CVCRD01Y.cpy} (copied at {@code COCRDUPC.cbl:268}) - carried as
 *       {@link CardScreenState}.</li>
 *   <li>{@code app/cpy/COCOM01Y.cpy} (copied at {@code COCRDUPC.cbl:272}) - carried as
 *       {@link NavigationContext}.</li>
 * </ul>
 *
 * <h2>The symbolic-map projection rule</h2>
 *
 * <p>Each screen field occupies a five-item chain in {@code CCRDUPAI}:
 * {@code 02 xxxL COMP PIC S9(4)} (a 2-byte binary halfword), {@code 02 xxxF PICTURE X} (1 byte),
 * {@code 02 FILLER REDEFINES xxxF} carrying {@code 03 xxxA PICTURE X} (an overlay of the same byte,
 * so 0 extra), {@code 02 FILLER PICTURE X(4)} (4 bytes) and finally {@code 02 xxxI PIC X(n)}. The
 * stride is therefore {@code 7 + n}.
 *
 * <p><strong>Payload fields come from the {@code xxxI} items and nothing else.</strong> The
 * {@code xxxO} items of the output group {@code 01 CCRDUPAO REDEFINES CCRDUPAI.} at line 121 belong
 * to the response type, not here.
 *
 * <p>{@code xxxL}, {@code xxxF} and {@code xxxA} are <strong>validation and highlight metadata and
 * are never JSON payload members</strong>. They are modelled by {@link FieldMetadata}, reachable
 * through {@link #fieldMetadata()}, which is annotated {@link JsonIgnore} so the serialised surface
 * stays at exactly the 17 screen fields plus the three state carriers. Evidence that they are
 * outbound control rather than inbound data: {@code COCRDUPC} writes {@code MOVE -1 TO CRDNAMEL OF
 * CCRDUPAI} and five siblings at lines 1214-1234 - the CICS convention for placing the cursor - and
 * {@code MOVE DFHBMBRY TO FKEYSCA OF CCRDUPAI} at line 1316.
 *
 * <h2>Geometry, checked by arithmetic rather than asserted</h2>
 *
 * <p>The 17 declared widths sum to {@value #NAMED_FIELD_TOTAL_LENGTH}
 * ({@code 4+40+8+8+40+8+11+16+50+1+2+4+2+40+80+21+18}), so the whole input group is
 * {@value #GROUP_LENGTH} bytes: {@value #TIOAPFX_LENGTH} for the {@code TIOAPFX=YES} prefix, plus
 * {@value #NAMED_FIELD_COUNT} chains of {@value #FIELD_METADATA_LENGTH} metadata bytes each, plus
 * {@value #NAMED_FIELD_TOTAL_LENGTH} of field data.
 *
 * <p>The commarea is {@value CommArea#RECORD_LENGTH} bytes and decomposes as
 * {@code 1 + 89 + 89 + 150}: one byte of {@link ChangeAction}, then the
 * {@value CardDetails#RECORD_LENGTH}-byte {@code CCUP-OLD-DETAILS} and {@code CCUP-NEW-DETAILS}
 * snapshots, then the {@value CardUpdateRecord#RECORD_LENGTH}-byte {@code CARD-UPDATE-RECORD}. Each
 * of those totals is enforced by a {@link RecordLayout}, which cannot be constructed unless its
 * spans are contiguous from zero and sum to exactly the declared length - {@code FILLER} included.
 *
 * <h2>The {@code FKEYSC} trap</h2>
 *
 * <p>{@code FKEYSC} is a <strong>genuine {@code DFHMDF} field name</strong>, not "{@code FKEYS} plus
 * the colour suffix {@code C}". It is the trap a reasonable engineer falls into, so it is recorded
 * here rather than absorbed:
 *
 * <ul>
 *   <li>On the input side {@code FKEYS} owns the chain {@code FKEYSL} / {@code FKEYSF} /
 *       {@code FKEYSA} / {@code FKEYSI PIC X(21)} at {@code COCRDUP.CPY} lines 109-114, and
 *       {@code FKEYSC} owns its own chain {@code FKEYSCL} / {@code FKEYSCF} / {@code FKEYSCA} /
 *       {@code FKEYSCI PIC X(18)} at lines 115-120.</li>
 *   <li>On the output side the names genuinely collide: {@code 02 FKEYSC PICTURE X.} at
 *       <strong>line 214</strong> is the colour metadata byte of field {@code FKEYS} (its payload is
 *       {@code FKEYSO PIC X(21)} at line 218), whereas field {@code FKEYSC}'s own colour byte is
 *       {@code 02 FKEYSCC PICTURE X.} at <strong>line 220</strong>, followed by {@code FKEYSCP},
 *       {@code FKEYSCH}, {@code FKEYSCV} and the payload {@code FKEYSCO PIC X(18)} at line 224.</li>
 *   <li>{@code app/bms/COCRDUP.bms} settles it: {@code FKEYS DFHMDF ATTRB=(ASKIP,NORM) LENGTH=21
 *       POS=(24,1)} and {@code FKEYSC DFHMDF ATTRB=(ASKIP,DRK) LENGTH=18 POS=(24,23)} are two
 *       separate screen fields at two separate positions.</li>
 * </ul>
 *
 * <p>A suffix-stripping mapper would collapse {@code FKEYSC} into {@code FKEYS}, silently deleting
 * an 18-byte field, and would bind the colour byte to the wrong field. This type therefore keeps
 * {@link #getFkeys()} at {@value #FKEYS_LENGTH} bytes and {@link #getFkeysc()} at
 * {@value #FKEYSC_LENGTH} bytes as two distinct members.
 *
 * <h2>Widths are per-map and are deliberately not unified</h2>
 *
 * <p>{@code INFOMSG} and {@code ERRMSG} are 40 and 80 here and in {@code COCRDSL}, but 45 and 78 in
 * {@code COCRDLI}. {@code FKEYS} is 21 here, 75 in {@code COCRDSL} and absent from {@code COCRDLI}.
 * {@code EXPDAY} exists only here. There is consequently no shared base type across the three card
 * screens: a common superclass could only hold one set of widths, and the wrong width is a parity
 * defect that no test of this screen alone would catch.
 *
 * <h2>Three misspellings that must survive</h2>
 *
 * <p>The commarea spells the expiry-date group items {@code CCUP-OLD-EXPIRAION-DATE} (line 297),
 * {@code CCUP-NEW-EXPIRAION-DATE} (line 309) and {@code CARD-UPDATE-EXPIRAION-DATE} (line 319), all
 * mirroring {@code app/cpy/CVACT02Y.cpy:9}'s {@code CARD-EXPIRAION-DATE}. Field names are part of
 * the contract and field-for-field diffing keys on them, so all three are reproduced verbatim and
 * the correctly spelled form appears nowhere in this file.
 *
 * <h2>Two date shapes and two CVV pictures, also not unified</h2>
 *
 * <p>{@code CCUP-OLD-EXPIRAION-DATE} and {@code CCUP-NEW-EXPIRAION-DATE} are {@code EXPYEAR X(4)} +
 * {@code EXPMON X(2)} + {@code EXPDAY X(2)} - eight bytes with <strong>no separators</strong> -
 * while {@code CARD-UPDATE-EXPIRAION-DATE} is a single {@code X(10)} in {@code YYYY-MM-DD} form,
 * composed at {@code COCRDUPC.cbl:1467-1474} by {@code STRING … DELIMITED BY SIZE} with explicit
 * {@code '-'} literals. Paragraph {@code 9300-CHECK-CHANGE-IN-REC} (lines 1498-1521) then compares
 * the {@code X(10)} form back against the 4/2/2 snapshot using 1-based reference modification
 * {@code (1:4)}, {@code (6:2)} and {@code (9:2)}, which is why {@link CardDetails} exposes the year,
 * month and day items individually instead of only as a composed string.
 *
 * <p>{@code CCUP-OLD-CVV-CD} and {@code CCUP-NEW-CVV-CD} are {@code PIC X(3)} - alphanumeric - while
 * {@code CARD-UPDATE-CVV-CD} is {@code PIC 9(03)} - numeric. The COBOL bridges them through a
 * {@code REDEFINES} pair at {@code COCRDUPC.cbl:1464-1465}
 * ({@code MOVE CCUP-NEW-CVV-CD TO CARD-CVV-CD-X} then {@code MOVE CARD-CVV-CD-N TO
 * CARD-UPDATE-CVV-CD}). The distinction is preserved: {@code String} for the {@code X(3)} pair, and
 * an {@code int} for the {@code 9(03)} item decoded through {@link FixedWidthCodec}. Likewise
 * {@code CARD-UPDATE-ACCT-ID PIC 9(11)} is a {@code long}. No {@code double} and no {@code float}
 * appears anywhere in this file, and there is no scaled or monetary field on this screen or in this
 * commarea at all - so there is no {@code BigDecimal} and no rounding of any kind either.
 *
 * <h2>The screen has no CVV field, although the commarea does</h2>
 *
 * <p>None of the 17 name-labelled {@code DFHMDF} entries is a CVV field, yet
 * {@code CCUP-OLD-CVV-CD}, {@code CCUP-NEW-CVV-CD} and {@code CARD-UPDATE-CVV-CD} all exist in the
 * commarea. That asymmetry is real - the CVV travels in program state and is never painted - and it
 * is not "fixed" here by inventing an eighteenth screen field.
 *
 * <h2>Statelessness</h2>
 *
 * <p>CICS is pseudo-conversational, so the whole conversation state travels in the payload: the
 * commarea, the {@code CVCRD01Y} work area including which AID was pressed, and the
 * {@code CDEMO-PGM-CONTEXT} flag that distinguishes first entry from re-entry and gates the error
 * highlight. There is no {@code HttpSession}, no {@code @SessionAttributes}, no server-side cache,
 * no static map and no {@code ThreadLocal}: nothing in this type outlives the request that carried
 * it.
 *
 * <h2>A correction to the summarised mapset header</h2>
 *
 * <p>The migration plan summarises all 17 mapsets as declaring {@code CTRL=(ALARM,FREEKB)} and
 * {@code EXTATT=YES} on {@code DFHMSD}. For this mapset that is not where those operands sit, and
 * the discrepancy is recorded rather than restated. Verbatim from {@code app/bms/COCRDUP.bms}:
 *
 * <ul>
 *   <li>Lines 20-24 - {@code COCRDUP DFHMSD LANG=COBOL, MODE=INOUT, STORAGE=AUTO, TIOAPFX=YES,
 *       TYPE=&amp;&amp;SYSPARM}: no {@code CTRL=} and no {@code EXTATT=}.</li>
 *   <li>Lines 25-28 - {@code CCRDUPA DFHMDI CTRL=(FREEKB), DSATTS=(COLOR,HILIGHT,PS,VALIDN),
 *       MAPATTS=(COLOR,HILIGHT,PS,VALIDN), SIZE=(24,80)}: this is where they sit, and the alarm
 *       operand is absent altogether.</li>
 * </ul>
 *
 * <p>{@code SIZE=(24,80)} is confirmed, hence {@value #SCREEN_ROWS} rows by
 * {@value #SCREEN_COLUMNS} columns.
 *
 * <h2>Validation, and what is deliberately not validated</h2>
 *
 * <p>The only constraint on any screen field is {@link Size} with a {@code max} taken from the
 * {@code xxxI} {@code PICTURE} width. There is no {@code @NotBlank}, no {@code @Pattern} and no
 * digit or range check, because {@code COCRDUPC} performs its own edits in
 * {@code 1210-EDIT-ACCOUNT}, {@code 1220-EDIT-CARD}, {@code 1230-EDIT-NAME},
 * {@code 1240-EDIT-CARDSTATUS}, {@code 1250-EDIT-EXPIRY-MON} and {@code 1260-EDIT-EXPIRY-YEAR}.
 * Pre-empting them would change which message the user sees and in which order, which is a parity
 * violation even when the field really is invalid.
 *
 * <p>Screen values are stored exactly as supplied so that {@link Size} can report an over-wide one
 * instead of it being silently truncated; the fixed-width projection - right space padding and
 * right truncation, the COBOL {@code PIC X} move rule - is applied on demand by
 * {@link #fieldImage(String, FixedWidthCodec)} and {@link #fieldImages(FixedWidthCodec)}, which
 * delegate to {@link FixedWidthCodec#movePicX(String, int)} rather than doing it by hand. A
 * {@code null} is normalised to that field's declared width in spaces, because COBOL has no
 * {@code null} and an untransmitted field reads as {@code SPACES} or {@code LOW-VALUES} - which is
 * exactly what the COBOL edits test for.
 *
 * <h2>Security posture</h2>
 *
 * <p>This payload carries a full card number, and its commarea carries the CVV in the clear, exactly
 * as the COBOL commarea does. <strong>On the wire nothing is masked</strong>: no
 * {@code @JsonIgnore} on a card number or a CVV, no truncated accessor and no altered fixed-width
 * image. Masking any of those would be an unrequested behaviour change, and no new exposure has been
 * added either. The {@link JsonIgnore} annotations in this file are purely about keeping the
 * serialised surface equal to the screen contract; not one of them hides a value that the COBOL
 * commarea already carries.
 *
 * <p>{@link #toString()} is the one exception, and it is not a wire format. It substitutes
 * {@code [REDACTED]} for {@code ACCTSID}, {@code CARDSID} and {@code CRDNAME}, because a diagnostic
 * string is the one place a card number reaches somewhere nobody chose to put it - a log file, a test
 * failure message, an exception trail. The 3270 shows those fields in the clear and so does every
 * accessor, every JSON body and {@link #fieldImage(String, FixedWidthCodec)}; only the diagnostic
 * rendering differs, which changes no COBOL-observable behaviour.
 *
 * <h2>Threading and mutability</h2>
 *
 * <p>This class is a mutable request-scoped bean and is <strong>not</strong> thread-safe, which is
 * the normal contract for a deserialisation target: one instance belongs to one request. Every
 * nested type is an immutable {@code record} instead, so the commarea a collaborator has been handed
 * can never change underneath it. There is no static mutable state of any kind: every constant is
 * {@code static final} and every collection handed out is unmodifiable.
 *
 * @see CardScreenState the {@code CVCRD01Y} work area carried on every card screen
 * @see NavigationContext the 160-byte {@code CARDDEMO-COMMAREA}
 */
public final class CardUpdateRequest {

    // =================================================================================================
    // Screen identity. The CSD transaction, its backing program, and the mapset and map names as the
    // BMS source spells them. Held as constants because a controller that echoes "which screen am I"
    // back to a stateless client must not hard-code a literal at the call site.
    // =================================================================================================

    /** CSD transaction identifier of this screen: {@code CCUP}. */
    public static final String TRANSACTION_ID = "CCUP";

    /** COBOL program this type is translated from: {@code app/cbl/COCRDUPC.cbl}. */
    public static final String PROGRAM_NAME = "COCRDUPC";

    /** BMS mapset name, from {@code COCRDUP DFHMSD} at {@code app/bms/COCRDUP.bms:20}. */
    public static final String MAPSET_NAME = "COCRDUP";

    /** BMS map name, from {@code CCRDUPA DFHMDI} at {@code app/bms/COCRDUP.bms:25}. */
    public static final String MAP_NAME = "CCRDUPA";

    /** Row count from {@code SIZE=(24,80)} on {@code CCRDUPA DFHMDI}. */
    public static final int SCREEN_ROWS = 24;

    /** Column count from {@code SIZE=(24,80)} on {@code CCRDUPA DFHMDI}. */
    public static final int SCREEN_COLUMNS = 80;

    // =================================================================================================
    // DFHMDF field labels, carried VERBATIM. These are the names the parity differ compares field by
    // field and the keys of every map this type hands out, so a "tidied" name would make a real
    // difference invisible.
    // =================================================================================================

    /** {@code DFHMDF} label of field 1, at {@code app/bms/COCRDUP.bms} {@code POS=(1,7)}. */
    public static final String TRNNAME_FIELD = "TRNNAME";

    /** {@code DFHMDF} label of field 2, at {@code POS=(1,21)}. */
    public static final String TITLE01_FIELD = "TITLE01";

    /** {@code DFHMDF} label of field 3, at {@code POS=(1,71)}. */
    public static final String CURDATE_FIELD = "CURDATE";

    /** {@code DFHMDF} label of field 4, at {@code POS=(2,7)}. */
    public static final String PGMNAME_FIELD = "PGMNAME";

    /** {@code DFHMDF} label of field 5, at {@code POS=(2,21)}. */
    public static final String TITLE02_FIELD = "TITLE02";

    /** {@code DFHMDF} label of field 6, at {@code POS=(2,71)}. */
    public static final String CURTIME_FIELD = "CURTIME";

    /** {@code DFHMDF} label of field 7, at {@code POS=(7,45)}. */
    public static final String ACCTSID_FIELD = "ACCTSID";

    /** {@code DFHMDF} label of field 8, at {@code POS=(8,45)}. */
    public static final String CARDSID_FIELD = "CARDSID";

    /** {@code DFHMDF} label of field 9, at {@code POS=(11,25)}. */
    public static final String CRDNAME_FIELD = "CRDNAME";

    /** {@code DFHMDF} label of field 10, at {@code POS=(13,25)}. */
    public static final String CRDSTCD_FIELD = "CRDSTCD";

    /** {@code DFHMDF} label of field 11, at {@code POS=(15,25)}. */
    public static final String EXPMON_FIELD = "EXPMON";

    /** {@code DFHMDF} label of field 12, at {@code POS=(15,30)}. */
    public static final String EXPYEAR_FIELD = "EXPYEAR";

    /**
     * {@code DFHMDF} label of field 13, at {@code POS=(15,36)}. Unique to this mapset: neither
     * {@code COCRDLI} nor {@code COCRDSL} declares it.
     */
    public static final String EXPDAY_FIELD = "EXPDAY";

    /** {@code DFHMDF} label of field 14, at {@code POS=(20,25)}. */
    public static final String INFOMSG_FIELD = "INFOMSG";

    /** {@code DFHMDF} label of field 15, at {@code POS=(23,1)}. */
    public static final String ERRMSG_FIELD = "ERRMSG";

    /**
     * {@code DFHMDF} label of field 16, at {@code POS=(24,1)} with {@code LENGTH=21}. Distinct from
     * {@link #FKEYSC_FIELD} - see the class documentation.
     */
    public static final String FKEYS_FIELD = "FKEYS";

    /**
     * {@code DFHMDF} label of field 17, at {@code POS=(24,23)} with {@code LENGTH=18}. A genuine
     * field name in its own right, <strong>not</strong> {@link #FKEYS_FIELD} plus a colour suffix.
     */
    public static final String FKEYSC_FIELD = "FKEYSC";

    // =================================================================================================
    // Declared widths, one per field, each taken from its xxxI PICTURE clause in
    // app/cpy-bms/COCRDUP.CPY and independently confirmed against the DFHMDF LENGTH= operand in
    // app/bms/COCRDUP.bms. No width is ever written as a bare number at a call site.
    // =================================================================================================

    /** {@code TRNNAMEI PIC X(4)}, {@code app/cpy-bms/COCRDUP.CPY:24}. */
    public static final int TRNNAME_LENGTH = 4;

    /** {@code TITLE01I PIC X(40)}, {@code app/cpy-bms/COCRDUP.CPY:30}. */
    public static final int TITLE01_LENGTH = 40;

    /** {@code CURDATEI PIC X(8)}, {@code app/cpy-bms/COCRDUP.CPY:36}. */
    public static final int CURDATE_LENGTH = 8;

    /** {@code PGMNAMEI PIC X(8)}, {@code app/cpy-bms/COCRDUP.CPY:42}. */
    public static final int PGMNAME_LENGTH = 8;

    /** {@code TITLE02I PIC X(40)}, {@code app/cpy-bms/COCRDUP.CPY:48}. */
    public static final int TITLE02_LENGTH = 40;

    /** {@code CURTIMEI PIC X(8)}, {@code app/cpy-bms/COCRDUP.CPY:54}. */
    public static final int CURTIME_LENGTH = 8;

    /** {@code ACCTSIDI PIC X(11)}, {@code app/cpy-bms/COCRDUP.CPY:60}. */
    public static final int ACCTSID_LENGTH = 11;

    /** {@code CARDSIDI PIC X(16)}, {@code app/cpy-bms/COCRDUP.CPY:66}. */
    public static final int CARDSID_LENGTH = 16;

    /** {@code CRDNAMEI PIC X(50)}, {@code app/cpy-bms/COCRDUP.CPY:72}. */
    public static final int CRDNAME_LENGTH = 50;

    /** {@code CRDSTCDI PIC X(1)}, {@code app/cpy-bms/COCRDUP.CPY:78}. */
    public static final int CRDSTCD_LENGTH = 1;

    /** {@code EXPMONI PIC X(2)}, {@code app/cpy-bms/COCRDUP.CPY:84}. */
    public static final int EXPMON_LENGTH = 2;

    /** {@code EXPYEARI PIC X(4)}, {@code app/cpy-bms/COCRDUP.CPY:90}. */
    public static final int EXPYEAR_LENGTH = 4;

    /** {@code EXPDAYI PIC X(2)}, {@code app/cpy-bms/COCRDUP.CPY:96}. */
    public static final int EXPDAY_LENGTH = 2;

    /**
     * {@code INFOMSGI PIC X(40)}, {@code app/cpy-bms/COCRDUP.CPY:102}. 40 here and in
     * {@code COCRDSL}, but 45 in {@code COCRDLI} - the widths are per-map and are not unified.
     */
    public static final int INFOMSG_LENGTH = 40;

    /**
     * {@code ERRMSGI PIC X(80)}, {@code app/cpy-bms/COCRDUP.CPY:108}. 80 here and in
     * {@code COCRDSL}, but 78 in {@code COCRDLI}.
     */
    public static final int ERRMSG_LENGTH = 80;

    /**
     * {@code FKEYSI PIC X(21)}, {@code app/cpy-bms/COCRDUP.CPY:114}. 21 here, 75 in
     * {@code COCRDSL}, and the field is absent from {@code COCRDLI} entirely.
     */
    public static final int FKEYS_LENGTH = 21;

    /**
     * {@code FKEYSCI PIC X(18)}, {@code app/cpy-bms/COCRDUP.CPY:120}. An 18-byte field of its own -
     * collapsing it into {@link #FKEYS_LENGTH} would delete 18 bytes of screen.
     */
    public static final int FKEYSC_LENGTH = 18;

    // =================================================================================================
    // Group geometry of 01 CCRDUPAI. Every number below is derived, and the derivation is stated, so
    // the 484-byte total can be re-checked against the copybook without running anything.
    // =================================================================================================

    /**
     * Bytes the {@code TIOAPFX=YES} prefix reserves at the head of the group:
     * {@code 02 FILLER PIC X(12)} at {@code app/cpy-bms/COCRDUP.CPY:18}. Present in the input group
     * and again in the redefining output group at line 122.
     */
    public static final int TIOAPFX_LENGTH = 12;

    /**
     * Metadata bytes preceding every field's data: {@code xxxL COMP PIC S9(4)} contributes 2,
     * {@code xxxF PICTURE X} contributes 1, the {@code xxxA} overlay of {@code xxxF} contributes 0
     * because it redefines that same byte, and {@code FILLER PICTURE X(4)} contributes 4. Total 7,
     * so each field's stride through the group is {@code 7 + n}.
     */
    public static final int FIELD_METADATA_LENGTH = 7;

    /** Name-labelled {@code DFHMDF} entries, and therefore screen fields on this type. */
    public static final int NAMED_FIELD_COUNT = 17;

    /**
     * All {@code DFHMDF} entries in {@code app/bms/COCRDUP.bms}, named and unnamed. The
     * {@code 34 - 17 = 17} unnamed entries are literal {@code INITIAL} captions and get no Java
     * field, because an unnamed field has no symbolic-map item to project from.
     */
    public static final int TOTAL_DFHMDF_COUNT = 34;

    /**
     * Sum of the {@value #NAMED_FIELD_COUNT} declared widths:
     * {@code 4+40+8+8+40+8+11+16+50+1+2+4+2+40+80+21+18 = 353}.
     */
    public static final int NAMED_FIELD_TOTAL_LENGTH = 353;

    /**
     * Total length of {@code 01 CCRDUPAI}:
     * {@value #TIOAPFX_LENGTH} + {@value #NAMED_FIELD_COUNT} x {@value #FIELD_METADATA_LENGTH} +
     * {@value #NAMED_FIELD_TOTAL_LENGTH} = {@code 12 + 119 + 353 = 484}.
     *
     * <p>Recorded as a constant rather than as a {@link RecordLayout}, deliberately: the
     * {@code xxxL COMP PIC S9(4)} items are 2-byte <em>binary</em> halfwords, and
     * {@link FixedWidthCodec} handles zoned {@code DISPLAY} data only. Declaring a layout would
     * mean declaring a picture category the codec cannot honour, so the group image is described
     * arithmetically and the codec is used only where it is correct - on the commarea, which is
     * entirely {@code PIC X} and {@code PIC 9 DISPLAY}.
     */
    public static final int GROUP_LENGTH = 484;

    // =================================================================================================
    // Field catalogue. FIELD_NAMES is in DFHMDF declaration order - the order the screen is painted
    // and the order a field-by-field diff walks - and FIELD_LENGTHS maps each label to its width.
    // Both are immutable: List.of is inherently so, and the map is an unmodifiable view over a
    // LinkedHashMap, which is what preserves the declaration order that Map.of would discard.
    // =================================================================================================

    /** The {@value #NAMED_FIELD_COUNT} {@code DFHMDF} labels, in declaration order. */
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

    /** Each {@code DFHMDF} label mapped to its {@code xxxI} declared width, in declaration order. */
    public static final Map<String, Integer> FIELD_LENGTHS = buildFieldLengths();

    /** The character COBOL pads a {@code PIC X} field with, on the right. */
    private static final String PIC_X_PAD = " ";

    /** Separator used by {@link #toString()} between rendered members. */
    private static final String TO_STRING_SEPARATOR = ", ";


    // =================================================================================================
    // The 17 screen fields, in DFHMDF declaration order. Each is the projection of exactly one xxxI
    // item, each carries @Size with the max taken from that item's PICTURE clause and nothing else,
    // and each is stored exactly as supplied so an over-wide value is reported rather than truncated.
    // =================================================================================================

    /**
     * {@code TRNNAME} - {@code TRNNAMEI PIC X(4)}, {@code app/cpy-bms/COCRDUP.CPY:24}.
     * {@code ATTRB=(ASKIP,FSET,NORM)}. Carries the transaction identifier painted at row 1.
     */
    @Size(max = TRNNAME_LENGTH)
    private String trnname;

    /**
     * {@code TITLE01} - {@code TITLE01I PIC X(40)}, {@code app/cpy-bms/COCRDUP.CPY:30}.
     * {@code ATTRB=(ASKIP,NORM)}. The first title line, sourced from {@code app/cpy/COTTL01Y.cpy}.
     */
    @Size(max = TITLE01_LENGTH)
    private String title01;

    /**
     * {@code CURDATE} - {@code CURDATEI PIC X(8)}, {@code app/cpy-bms/COCRDUP.CPY:36}.
     * {@code ATTRB=(ASKIP,NORM)}. The {@code mm/dd/yy} header date from {@code app/cpy/CSDAT01Y.cpy}.
     */
    @Size(max = CURDATE_LENGTH)
    private String curdate;

    /**
     * {@code PGMNAME} - {@code PGMNAMEI PIC X(8)}, {@code app/cpy-bms/COCRDUP.CPY:42}.
     * {@code ATTRB=(ASKIP,NORM)}. The program name painted at row 2, {@value #PROGRAM_NAME}.
     */
    @Size(max = PGMNAME_LENGTH)
    private String pgmname;

    /**
     * {@code TITLE02} - {@code TITLE02I PIC X(40)}, {@code app/cpy-bms/COCRDUP.CPY:48}.
     * {@code ATTRB=(ASKIP,NORM)}. The second title line.
     */
    @Size(max = TITLE02_LENGTH)
    private String title02;

    /**
     * {@code CURTIME} - {@code CURTIMEI PIC X(8)}, {@code app/cpy-bms/COCRDUP.CPY:54}.
     * {@code ATTRB=(ASKIP,NORM)}. The {@code hh:mm:ss} header time.
     */
    @Size(max = CURTIME_LENGTH)
    private String curtime;

    /**
     * {@code ACCTSID} - {@code ACCTSIDI PIC X(11)}, {@code app/cpy-bms/COCRDUP.CPY:60}.
     * {@code ATTRB=(FSET,IC,NORM,PROT)}, so this is the field the cursor lands in on entry
     * ({@code IC}). Edited by {@code 1210-EDIT-ACCOUNT}; no digit check is applied here.
     */
    @Size(max = ACCTSID_LENGTH)
    private String acctsid;

    /**
     * {@code CARDSID} - {@code CARDSIDI PIC X(16)}, {@code app/cpy-bms/COCRDUP.CPY:66}.
     * {@code ATTRB=(FSET,NORM,UNPROT)}. The 16-digit card number, carried in the clear exactly as
     * the COBOL symbolic map carries it. Edited by {@code 1220-EDIT-CARD}.
     */
    @Size(max = CARDSID_LENGTH)
    private String cardsid;

    /**
     * {@code CRDNAME} - {@code CRDNAMEI PIC X(50)}, {@code app/cpy-bms/COCRDUP.CPY:72}.
     * {@code ATTRB=(UNPROT)}. The embossed name. Edited by {@code 1230-EDIT-NAME}, which also
     * upper-cases it via {@code INSPECT … CONVERTING}; that conversion belongs to the service, not
     * to this payload.
     */
    @Size(max = CRDNAME_LENGTH)
    private String crdname;

    /**
     * {@code CRDSTCD} - {@code CRDSTCDI PIC X(1)}, {@code app/cpy-bms/COCRDUP.CPY:78}.
     * {@code ATTRB=(UNPROT)}. The active status. Edited by {@code 1240-EDIT-CARDSTATUS}.
     */
    @Size(max = CRDSTCD_LENGTH)
    private String crdstcd;

    /**
     * {@code EXPMON} - {@code EXPMONI PIC X(2)}, {@code app/cpy-bms/COCRDUP.CPY:84}.
     * {@code ATTRB=(UNPROT)}. Edited by {@code 1250-EDIT-EXPIRY-MON}.
     */
    @Size(max = EXPMON_LENGTH)
    private String expmon;

    /**
     * {@code EXPYEAR} - {@code EXPYEARI PIC X(4)}, {@code app/cpy-bms/COCRDUP.CPY:90}.
     * {@code ATTRB=(UNPROT)}. Edited by {@code 1260-EDIT-EXPIRY-YEAR}.
     */
    @Size(max = EXPYEAR_LENGTH)
    private String expyear;

    /**
     * {@code EXPDAY} - {@code EXPDAYI PIC X(2)}, {@code app/cpy-bms/COCRDUP.CPY:96}.
     * {@code ATTRB=(DRK,FSET,PROT)}, so it is transmitted but never displayed. Unique to this
     * mapset, which is one reason the three card screens share no base type.
     */
    @Size(max = EXPDAY_LENGTH)
    private String expday;

    /**
     * {@code INFOMSG} - {@code INFOMSGI PIC X(40)}, {@code app/cpy-bms/COCRDUP.CPY:102}.
     * {@code ATTRB=(PROT)}. The informational prompt line.
     */
    @Size(max = INFOMSG_LENGTH)
    private String infomsg;

    /**
     * {@code ERRMSG} - {@code ERRMSGI PIC X(80)}, {@code app/cpy-bms/COCRDUP.CPY:108}.
     * {@code ATTRB=(ASKIP,BRT,FSET)}. The error line, painted bright.
     */
    @Size(max = ERRMSG_LENGTH)
    private String errmsg;

    /**
     * {@code FKEYS} - {@code FKEYSI PIC X(21)}, {@code app/cpy-bms/COCRDUP.CPY:114}.
     * {@code ATTRB=(ASKIP,NORM)} at {@code POS=(24,1)}. The first function-key legend.
     */
    @Size(max = FKEYS_LENGTH)
    private String fkeys;

    /**
     * {@code FKEYSC} - {@code FKEYSCI PIC X(18)}, {@code app/cpy-bms/COCRDUP.CPY:120}.
     * {@code ATTRB=(ASKIP,DRK)} at {@code POS=(24,23)}. The second function-key legend, dark until
     * {@code 3300-SETUP-SCREEN-ATTRS} brightens it with {@code MOVE DFHBMBRY TO FKEYSCA OF CCRDUPAI}
     * at {@code app/cbl/COCRDUPC.cbl:1316}.
     *
     * <p>This is a field in its own right and <strong>not</strong> {@link #fkeys} viewed through a
     * colour byte. See the class documentation for the full collision analysis.
     */
    @Size(max = FKEYSC_LENGTH)
    private String fkeysc;

    // =================================================================================================
    // Conversation state. All three carriers travel in the payload; none of them is ever held server
    // side. Together they are the whole of what CICS would have kept in the COMMAREA and the TIOA.
    // =================================================================================================

    /**
     * {@code 01 WS-THIS-PROGCOMMAREA}, {@code app/cbl/COCRDUPC.cbl:274-321} - the
     * {@value CommArea#RECORD_LENGTH}-byte program commarea, holding the change-action state, the
     * before and after snapshots and the record staged for rewrite.
     */
    private CommArea commArea;

    /**
     * {@code app/cpy/CVCRD01Y.cpy}, copied at {@code app/cbl/COCRDUPC.cbl:268} - the card work area,
     * carrying {@code CCARD-AID} (which key was pressed), the next program, mapset and map, and the
     * error and return messages.
     */
    private CardScreenState cardScreenState;

    /**
     * {@code app/cpy/COCOM01Y.cpy}, copied at {@code app/cbl/COCRDUPC.cbl:272} - the 160-byte
     * {@code CARDDEMO-COMMAREA}, including {@code CDEMO-PGM-CONTEXT} whose {@code 88}-levels
     * distinguish {@code ENTER} (0) from {@code REENTER} (1).
     *
     * <p><strong>{@code null} is meaningful here, and it is not an empty area.</strong>
     * {@code app/cbl/COCRDUPC.cbl:388-394} tests
     * {@code IF EIBCALEN IS EQUAL TO 0 OR (CDEMO-FROM-PROGRAM = LIT-MENUPGM AND NOT
     * CDEMO-PGM-REENTER)} and answers it by {@code INITIALIZE}-ing {@code CARDDEMO-COMMAREA} and
     * {@code WS-THIS-PROGCOMMAREA}, then {@code SET CDEMO-PGM-ENTER TO TRUE} and
     * {@code SET CCUP-DETAILS-NOT-FETCHED TO TRUE}; the {@code ELSE} at {@code :395-400} instead
     * moves both areas out of {@code DFHCOMMAREA}. The first disjunct is the cold start - no
     * communication area at all - and an initialised {@link NavigationContext} cannot express it,
     * because an initialised area reports {@code EIBCALEN} as
     * {@value NavigationContext#COMMAREA_LENGTH} and so takes the {@code ELSE}. The second disjunct
     * is a decision about a <em>present</em> area and belongs to {@code CardUpdateController}. This
     * member therefore stays nullable and {@link #hasNavigationContext()} is the discriminator.
     */
    private NavigationContext navigationContext;

    /**
     * The {@code xxxL} / {@code xxxF} / {@code xxxA} metadata, one entry per screen field, keyed by
     * {@code DFHMDF} label in declaration order.
     *
     * <p>Mutable because a controller records what CICS reported for each field, but never exposed
     * mutably: {@link #fieldMetadata()} returns an unmodifiable view and {@link JsonIgnore} keeps the
     * whole structure off the wire, so the serialised payload stays equal to the screen contract.
     */
    private final Map<String, FieldMetadata> fieldMetadata;

    // =================================================================================================
    // Construction. Three forms, all usable with no Spring context and no Jackson: a no-argument form
    // for deserialisation and for a freshly painted screen, a full form for tests and controllers, and
    // a copy form for taking a snapshot before mutation.
    // =================================================================================================

    /**
     * Creates a request in which every screen field holds its declared width in spaces, the commarea
     * is in its initial state, the work area is initialised and the navigation context is empty.
     *
     * <p>This is the shape of a first entry to the screen: {@code CCUP-CHANGE-ACTION} is
     * {@code LOW-VALUES} so {@code CCUP-DETAILS-NOT-FETCHED} is true, which is the first
     * {@code WHEN} of {@code 2000-DECIDE-ACTION}. Spaces rather than {@code null} because COBOL has
     * no {@code null}, and an untransmitted field reads as {@code SPACES}.
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
        this.cardScreenState = new CardScreenState();
        // Absence, not an initialised area: EIBCALEN = 0 is the first disjunct of COCRDUPC.cbl:388,
        // and a request nobody has passed a communication area to has not been passed one. The other
        // two carriers are the program's own storage and always exist.
        this.navigationContext = null;
        this.fieldMetadata = defaultFieldMetadata();
    }

    /**
     * Creates a fully populated request. Every argument is a screen field except the last three,
     * which are the state carriers.
     *
     * <p>A {@code null} screen field is normalised to that field's declared width in spaces, and a
     * {@code null} {@code commArea} or {@code cardScreenState} to its own initial form, because all
     * three are the program's own storage and always exist. A {@code null}
     * {@code navigationContext} is stored <strong>verbatim</strong>: it is the only one a caller can
     * fail to pass, so its absence is the {@code EIBCALEN = 0} cold start of
     * {@code app/cbl/COCRDUPC.cbl:388} and completing it would erase that state. Nothing is truncated
     * here either, so an over-wide value survives to be reported by {@link Size}.
     *
     * @param trnname           {@code TRNNAMEI PIC X(4)}
     * @param title01           {@code TITLE01I PIC X(40)}
     * @param curdate           {@code CURDATEI PIC X(8)}
     * @param pgmname           {@code PGMNAMEI PIC X(8)}
     * @param title02           {@code TITLE02I PIC X(40)}
     * @param curtime           {@code CURTIMEI PIC X(8)}
     * @param acctsid           {@code ACCTSIDI PIC X(11)}
     * @param cardsid           {@code CARDSIDI PIC X(16)}
     * @param crdname           {@code CRDNAMEI PIC X(50)}
     * @param crdstcd           {@code CRDSTCDI PIC X(1)}
     * @param expmon            {@code EXPMONI PIC X(2)}
     * @param expyear           {@code EXPYEARI PIC X(4)}
     * @param expday            {@code EXPDAYI PIC X(2)}
     * @param infomsg           {@code INFOMSGI PIC X(40)}
     * @param errmsg            {@code ERRMSGI PIC X(80)}
     * @param fkeys             {@code FKEYSI PIC X(21)}
     * @param fkeysc            {@code FKEYSCI PIC X(18)} - a distinct field, not a colour byte
     * @param commArea          the {@value CommArea#RECORD_LENGTH}-byte program commarea
     * @param cardScreenState   the {@code CVCRD01Y} work area
     * @param navigationContext the {@value NavigationContext#COMMAREA_LENGTH}-byte
     *                          {@code CARDDEMO-COMMAREA}, or {@code null} for the cold start
     */
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
        this.cardScreenState =
                cardScreenState == null ? new CardScreenState() : cardScreenState;
        this.navigationContext = navigationContext;
        this.fieldMetadata = defaultFieldMetadata();
    }

    /**
     * Copies a request, so a caller can snapshot one before mutating it.
     *
     * <p>The commarea and the navigation context are immutable records and are shared; the work area
     * is mutable, so it is deep-copied through {@link CardScreenState}'s copy constructor. The
     * metadata map is copied entry by entry, and {@link FieldMetadata} is itself immutable.
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
        this.cardScreenState = new CardScreenState(other.cardScreenState);
        this.navigationContext = other.navigationContext;
        this.fieldMetadata = new LinkedHashMap<>(other.fieldMetadata);
    }


    // =================================================================================================
    // Screen field accessors. Hand-written, one pair per field, because there is no annotation
    // processor in the closed dependency set and generated accessors would obscure the byte-exact
    // field mapping that parity depends on. Each setter normalises null and nothing else.
    // =================================================================================================

    /**
     * Returns the {@code TRNNAME} screen field exactly as it was supplied.
     *
     * @return {@code TRNNAME}, the {@code TRNNAMEI PIC X(4)} value as supplied
     */
    public String getTrnname() {
        return trnname;
    }

    /**
     * Replaces the {@code TRNNAME} screen field.
     *
     * @param trnname {@code TRNNAME}; {@code null} becomes {@value #TRNNAME_LENGTH} spaces
     */
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

    /**
     * Replaces the {@code TITLE01} screen field.
     *
     * @param title01 {@code TITLE01}; {@code null} becomes {@value #TITLE01_LENGTH} spaces
     */
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

    /**
     * Replaces the {@code CURDATE} screen field.
     *
     * @param curdate {@code CURDATE}; {@code null} becomes {@value #CURDATE_LENGTH} spaces
     */
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

    /**
     * Replaces the {@code PGMNAME} screen field.
     *
     * @param pgmname {@code PGMNAME}; {@code null} becomes {@value #PGMNAME_LENGTH} spaces
     */
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

    /**
     * Replaces the {@code TITLE02} screen field.
     *
     * @param title02 {@code TITLE02}; {@code null} becomes {@value #TITLE02_LENGTH} spaces
     */
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

    /**
     * Replaces the {@code CURTIME} screen field.
     *
     * @param curtime {@code CURTIME}; {@code null} becomes {@value #CURTIME_LENGTH} spaces
     */
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

    /**
     * Replaces the {@code ACCTSID} screen field.
     *
     * @param acctsid {@code ACCTSID}; {@code null} becomes {@value #ACCTSID_LENGTH} spaces
     */
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

    /**
     * Replaces the {@code CARDSID} screen field.
     *
     * @param cardsid {@code CARDSID}; {@code null} becomes {@value #CARDSID_LENGTH} spaces
     */
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

    /**
     * Replaces the {@code CRDNAME} screen field.
     *
     * @param crdname {@code CRDNAME}; {@code null} becomes {@value #CRDNAME_LENGTH} spaces
     */
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

    /**
     * Replaces the {@code CRDSTCD} screen field.
     *
     * @param crdstcd {@code CRDSTCD}; {@code null} becomes {@value #CRDSTCD_LENGTH} space
     */
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

    /**
     * Replaces the {@code EXPMON} screen field.
     *
     * @param expmon {@code EXPMON}; {@code null} becomes {@value #EXPMON_LENGTH} spaces
     */
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

    /**
     * Replaces the {@code EXPYEAR} screen field.
     *
     * @param expyear {@code EXPYEAR}; {@code null} becomes {@value #EXPYEAR_LENGTH} spaces
     */
    public void setExpyear(String expyear) {
        this.expyear = orSpaces(expyear, EXPYEAR_LENGTH);
    }

    /**
     * Returns the {@code EXPDAY} screen field exactly as it was supplied.
     *
     * @return {@code EXPDAY}, the {@code EXPDAYI PIC X(2)} value as supplied. Unique to this mapset
     */
    public String getExpday() {
        return expday;
    }

    /**
     * Replaces the {@code EXPDAY} screen field.
     *
     * @param expday {@code EXPDAY}; {@code null} becomes {@value #EXPDAY_LENGTH} spaces
     */
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

    /**
     * Replaces the {@code INFOMSG} screen field.
     *
     * @param infomsg {@code INFOMSG}; {@code null} becomes {@value #INFOMSG_LENGTH} spaces
     */
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

    /**
     * Replaces the {@code ERRMSG} screen field.
     *
     * @param errmsg {@code ERRMSG}; {@code null} becomes {@value #ERRMSG_LENGTH} spaces
     */
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

    /**
     * Replaces the {@code FKEYS} screen field.
     *
     * @param fkeys {@code FKEYS}; {@code null} becomes {@value #FKEYS_LENGTH} spaces
     */
    public void setFkeys(String fkeys) {
        this.fkeys = orSpaces(fkeys, FKEYS_LENGTH);
    }

    /**
     * {@code FKEYSC}, the {@code FKEYSCI PIC X(18)} value as supplied. A distinct 18-byte screen
     * field at {@code POS=(24,23)}, not {@link #getFkeys()} seen through a colour byte.
     *
     * @return the value as supplied - 18 bytes, never 21
     */
    public String getFkeysc() {
        return fkeysc;
    }

    /**
     * Replaces the {@code FKEYSC} screen field.
     *
     * @param fkeysc {@code FKEYSC}; {@code null} becomes {@value #FKEYSC_LENGTH} spaces
     */
    public void setFkeysc(String fkeysc) {
        this.fkeysc = orSpaces(fkeysc, FKEYSC_LENGTH);
    }

    // =================================================================================================
    // State carrier accessors. Each carrier is a payload member: that is what makes the endpoint
    // stateless, and it is why there is no session, no cache and no ThreadLocal anywhere here.
    // =================================================================================================

    /**
     * Returns the program commarea this request carries.
     *
     * @return the {@value CommArea#RECORD_LENGTH}-byte program commarea, never {@code null}
     */
    public CommArea getCommArea() {
        return commArea;
    }

    /**
     * Replaces the program commarea this request carries.
     *
     * @param commArea the program commarea; {@code null} becomes {@link CommArea#initialised()}
     */
    public void setCommArea(CommArea commArea) {
        this.commArea = commArea == null ? CommArea.initialised() : commArea;
    }

    /**
     * The {@code CVCRD01Y} work area, returned live rather than copied.
     *
     * <p>Deliberate: in the COBOL this is {@code WORKING-STORAGE} that the program mutates freely
     * through the request - {@code MOVE … TO CCARD-NEXT-PROG}, {@code SET CCARD-AID-PFK05 TO TRUE} -
     * and copying on read would silently discard those mutations. The instance lives only as long as
     * the request that carries it, so nothing escapes the request boundary. Use
     * {@link #CardUpdateRequest(CardUpdateRequest)} to take an explicit snapshot.
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
     *         {@code null} where none travelled with the request - the cold start
     *         {@code app/cbl/COCRDUPC.cbl:388} tests as {@code EIBCALEN = 0}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    /**
     * Whether a communication area travelled with this request - the Java reading of {@code EIBCALEN}
     * being non-zero at {@code app/cbl/COCRDUPC.cbl:388}.
     *
     * <p>Not a JSON property: it is derived from {@link #getNavigationContext()}, which is already on
     * the wire as {@code null} or as an object, and a second member could contradict it.
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
     * <p>Note that this is the shared area only. This program's own
     * {@value CommArea#RECORD_LENGTH}-byte {@code WS-THIS-PROGCOMMAREA} rides behind it in
     * {@code WS-COMMAREA} on the {@code EXEC CICS RETURN} at {@code app/cbl/COCRDUPC.cbl:549-558},
     * and is carried separately by {@link #getCommArea()}, which always exists because it is the
     * program's own storage rather than something a caller passes in.
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
     * <p>The fallback is the arm an uninitialised area takes, and it is not observable in the COBOL:
     * the cold-start branch at {@code app/cbl/COCRDUPC.cbl:391-393} {@code INITIALIZE}s the area and
     * then {@code SET CDEMO-PGM-ENTER TO TRUE} itself. A caller that must tell an absent area from a
     * present one holding {@value NavigationContext#PGM_CONTEXT_ENTER} asks
     * {@link #hasNavigationContext()}.
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
     * @param navigationContext the commarea, or {@code null} where none travelled with the request -
     *                          stored verbatim, because absence is the state
     *                          {@code app/cbl/COCRDUPC.cbl:388} branches on and an empty area is a
     *                          different one
     */
    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = navigationContext;
    }

    // =================================================================================================
    // ENTER versus REENTER. CDEMO-PGM-CONTEXT is the branch that decides whether the screen is being
    // painted for the first time or validated after typing, and it is what gates the error highlight
    // that CSSETATY applies. Delegated rather than duplicated, so there is one source of truth.
    // =================================================================================================

    /**
     * Whether {@code CDEMO-PGM-CONTEXT} holds {@code 0}, satisfying the {@code 88}-level
     * {@code CDEMO-PGM-ENTER}: the screen is being entered for the first time and is to be painted,
     * not validated.
     *
     * <p>False when no communication area travelled: the condition name is a test over a field and
     * there is no field to test. With {@link #isReenter()} that gives three states, not two, which is
     * what the cold-start disjunct at {@code app/cbl/COCRDUPC.cbl:388} requires.
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
     * {@code PIC 9(01)} with two condition names over it rather than an enumeration, so a third digit
     * makes both false - and so does an absent communication area.
     *
     * @return {@code true} when a communication area travelled and reports re-entry
     */
    @JsonIgnore
    public boolean isReenter() {
        return hasNavigationContext() && navigationContext.isReenter();
    }

    // =================================================================================================
    // The xxxL / xxxF / xxxA metadata. Modelled, because COCRDUPC really does use these items, but
    // never serialised, because they are not screen data.
    // =================================================================================================

    /**
     * The per-field metadata, keyed by {@code DFHMDF} label in declaration order.
     *
     * <p>Annotated {@link JsonIgnore} so the serialised payload stays at exactly the
     * {@value #NAMED_FIELD_COUNT} screen fields plus the three carriers. This is a
     * serialised-surface decision and not a security one: no card number and no CVV is withheld from
     * the wire anywhere in this type.
     *
     * @return an unmodifiable view over {@value #NAMED_FIELD_COUNT} entries
     */
    @JsonIgnore
    public Map<String, FieldMetadata> fieldMetadata() {
        return Collections.unmodifiableMap(fieldMetadata);
    }

    /**
     * The metadata of one field.
     *
     * @param dfhmdfName the {@code DFHMDF} label, for example {@link #FKEYSC_FIELD}
     * @return that field's metadata, never {@code null}
     * @throws NullPointerException     if {@code dfhmdfName} is {@code null}
     * @throws IllegalArgumentException if {@code dfhmdfName} is not one of the
     *                                  {@value #NAMED_FIELD_COUNT} labels
     */
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
     * <p>The supplied metadata must name the same field and declare the same width, because the width
     * comes from the copybook and is not a caller's choice.
     *
     * @param metadata the replacement metadata
     * @throws NullPointerException     if {@code metadata} is {@code null}
     * @throws IllegalArgumentException if it names a field this mapset does not declare, or declares
     *                                  a width other than that field's {@code xxxI} width
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

    // =================================================================================================
    // Field-by-field projections. fieldValues hands out what was supplied; fieldImages hands out the
    // fixed-width images, and delegates the pad and truncate rule to the codec so that rule lives in
    // exactly one reviewable place. These two are what a field-by-field differ consumes.
    // =================================================================================================

    /**
     * The {@value #NAMED_FIELD_COUNT} screen values exactly as supplied, keyed by {@code DFHMDF}
     * label in declaration order.
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
     * <p>Each image is produced by {@link FixedWidthCodec#movePicX(String, int)}, which applies the
     * COBOL {@code PIC X} receiver rule: pad on the right with spaces when the value is short,
     * <strong>truncate on the right</strong> when it is long. Truncation is reachable only when Bean
     * Validation has been bypassed, because {@link Size} rejects an over-wide value first.
     *
     * @param codec the codec, carrying the code page explicitly
     * @return an unmodifiable, insertion-ordered map of {@value #NAMED_FIELD_COUNT} images whose
     *         lengths sum to {@value #NAMED_FIELD_TOTAL_LENGTH}
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
     * @param codec      the codec, carrying the code page explicitly
     * @return an image of exactly {@code declaredLength(dfhmdfName)} characters
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code dfhmdfName} is not one of the
     *                                  {@value #NAMED_FIELD_COUNT} labels
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
     * @throws NullPointerException     if {@code dfhmdfName} is {@code null}
     * @throws IllegalArgumentException if {@code dfhmdfName} is not one of the
     *                                  {@value #NAMED_FIELD_COUNT} labels
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
     * <p>{@code SPACES}, {@code LOW-VALUES} and Java {@code null} are three different things and this
     * type never conflates them: a space is {@code 0x20} under {@code US-ASCII} and {@code 0x40}
     * under {@code IBM037}, {@code LOW-VALUES} is {@code 0x00} under both, and {@code null} is not a
     * COBOL state at all.
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

    /**
     * Renders the request for diagnostics.
     *
     * <p><strong>{@code ACCTSID}, {@code CARDSID} and {@code CRDNAME} are withheld here, and only
     * here.</strong> They are an account identifier, a sixteen-digit card number and a cardholder's
     * name. A diagnostic rendering is the one place they reach somewhere nobody chose to put them - a
     * log file, a test failure message, an exception trail - so each shows as
     * {@value #REDACTED_VALUE} with its actual length, which is what a failure message is read for.
     *
     * <p>This changes no behaviour of the screen and does not sanitise the COBOL. The JSON body
     * carries all {@value #NAMED_FIELD_COUNT} fields exactly as the symbolic map declares them,
     * {@link #fieldValues()} and {@link #fieldImage(String, FixedWidthCodec)} return them in the
     * clear, and the 3270 displays them in the clear. Nor is any new exposure added: the commarea's
     * CVV is rendered by {@link CommArea}'s own {@code toString()}, unchanged, and the shared
     * communication area by {@link NavigationContext}'s, which redacts what it holds.
     *
     * @return a single-line rendering naming the map, every screen field and the three carriers
     */
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

    /**
     * What {@link #toString()} substitutes for a withheld value: {@value}.
     *
     * <p>The same marker every other payload in this module uses, so a log line reads consistently
     * whichever screen produced it.
     */
    private static final String REDACTED_VALUE = "[REDACTED]";

    /**
     * The three {@code DFHMDF} labels {@link #toString()} withholds: the account identifier, the card
     * number and the cardholder's name.
     *
     * <p>Scoped to those three. {@code CRDSTCD} is a one-character status,
     * {@code EXPMON}/{@code EXPYEAR}/{@code EXPDAY} are an expiry that identifies nobody alone, and
     * the rest are titles, messages and the two function-key lines. Redacting them would cost
     * diagnostic value for no gain. Read only by {@link #toString()}.
     */
    private static final Set<String> REDACTED_FIELDS =
            Set.of(ACCTSID_FIELD, CARDSID_FIELD, CRDNAME_FIELD);

    // =================================================================================================
    // Private helpers.
    // =================================================================================================

    /**
     * Normalises a supplied screen value: {@code null} becomes the field's declared width in spaces,
     * and anything else is kept verbatim so {@link Size} can report it.
     *
     * @param value          the supplied value, possibly {@code null}
     * @param declaredLength the field's declared width, used only when {@code value} is {@code null}
     * @return the value itself, or that many spaces
     */
    private static String orSpaces(String value, int declaredLength) {
        return value == null ? spaces(declaredLength) : value;
    }

    /**
     * Builds the label-to-width catalogue once, preserving {@code DFHMDF} declaration order, which
     * {@code Map.of} would discard. Wrapped unmodifiable, so the constant cannot be mutated.
     *
     * @return an unmodifiable, insertion-ordered map of the 17 labels to their declared widths
     */
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

    /**
     * Builds the initial metadata map: one entry per field, each declaring that field's copybook
     * width, reporting no transmitted length and carrying the unmodified flag byte.
     *
     * @return a mutable, insertion-ordered map of 17 entries, owned solely by one request
     */
    private static Map<String, FieldMetadata> defaultFieldMetadata() {
        Map<String, FieldMetadata> metadata = new LinkedHashMap<>();
        for (String fieldName : FIELD_NAMES) {
            metadata.put(fieldName, FieldMetadata.notTransmitted(fieldName));
        }
        return metadata;
    }


    // =================================================================================================
    // Nested type 1 of 6 - FieldMetadata: the xxxL / xxxF / xxxA chain of one screen field.
    // =================================================================================================

    /**
     * The metadata items of one screen field: {@code xxxL}, {@code xxxF} and its {@code xxxA}
     * overlay. Deliberately <strong>not</strong> a payload member - see {@link #fieldMetadata()}.
     *
     * <p>{@code xxxA} is declared as {@code 02 FILLER REDEFINES xxxF.} carrying
     * {@code 03 xxxA PICTURE X.}, so it is not a second byte: it is the <em>same</em> byte viewed as
     * an attribute rather than as a flag. {@link #attributeItem()} therefore returns exactly what
     * {@link #flagItem()} returns, and that identity is the point - two separate Java fields would
     * misrepresent the copybook and could drift apart.
     *
     * @param fieldName      the {@code DFHMDF} label this metadata belongs to, verbatim
     * @param declaredLength the field's {@code xxxI} {@code PICTURE} width, which the copybook fixes
     * @param lengthItem     {@code xxxL COMP PIC S9(4)} - the length CICS reports for the field.
     *                       Zero when the terminal did not transmit it, positive when it did, and
     *                       {@value #CURSOR_LENGTH_ITEM} when the program is asking CICS to place the
     *                       cursor there, as {@code COCRDUPC} does at lines 1214-1234
     * @param flagItem       {@code xxxF PICTURE X} - one character. {@code x'00'} means the field was
     *                       not modified
     */
    public record FieldMetadata(String fieldName, int declaredLength, int lengthItem,
                                String flagItem) {

        /**
         * The value {@code COCRDUPC} moves into an {@code xxxL} item to request the cursor:
         * {@code MOVE -1 TO CRDNAMEL OF CCRDUPAI} and five siblings at
         * {@code app/cbl/COCRDUPC.cbl:1214-1234}.
         */
        public static final int CURSOR_LENGTH_ITEM = -1;

        /** The {@code xxxL} value for a field the terminal did not transmit. */
        public static final int LENGTH_ITEM_NOT_TRANSMITTED = 0;

        /** The {@code xxxF} flag byte for an unmodified field: {@code LOW-VALUES}, {@code x'00'}. */
        public static final String FLAG_ITEM_NOT_MODIFIED = "\u0000";

        /** Declared width of {@code xxxF}, and therefore of its {@code xxxA} overlay. */
        public static final int FLAG_ITEM_LENGTH = 1;

        /**
         * Validates the chain.
         *
         * <p>{@code lengthItem} is checked against the field's declared width rather than only
         * against zero, because {@code xxxL} is a {@code COMP PIC S9(4)} halfword that CICS fills
         * with a count of transmitted bytes and that count cannot exceed the field. The one negative
         * value permitted is {@value #CURSOR_LENGTH_ITEM}, which is not a count at all.
         *
         * @throws NullPointerException     if {@code fieldName} or {@code flagItem} is {@code null}
         * @throws IllegalArgumentException if {@code declaredLength} is below 1, if
         *                                  {@code flagItem} is not exactly one character, or if
         *                                  {@code lengthItem} is neither
         *                                  {@value #CURSOR_LENGTH_ITEM} nor in
         *                                  {@code 0 .. declaredLength}
         */
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
         * Metadata for a field the terminal did not transmit: no reported length, and the unmodified
         * flag byte.
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
         * Metadata requesting the cursor in this field, which is what
         * {@code MOVE -1 TO xxxL OF CCRDUPAI} expresses.
         *
         * @param dfhmdfName the {@code DFHMDF} label
         * @return the metadata, with {@code lengthItem} of {@value #CURSOR_LENGTH_ITEM}
         * @throws IllegalArgumentException if {@code dfhmdfName} is not a label of this mapset
         */
        public static FieldMetadata cursorAt(String dfhmdfName) {
            return new FieldMetadata(dfhmdfName, CardUpdateRequest.declaredLength(dfhmdfName),
                    CURSOR_LENGTH_ITEM,
                    FLAG_ITEM_NOT_MODIFIED);
        }

        /**
         * The {@code xxxA} view of the flag byte. Identical to {@link #flagItem()} by construction,
         * because {@code xxxA} redefines {@code xxxF} rather than following it.
         *
         * @return the same one character {@link #flagItem()} returns
         */
        public String attributeItem() {
            return flagItem;
        }

        /**
         * Whether this field is asking CICS to place the cursor in it.
         *
         * @return {@code true} when {@code xxxL} holds {@value #CURSOR_LENGTH_ITEM}, the CICS
         *         cursor request
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
         * @return {@code true} when {@code xxxF} still holds {@value #FLAG_ITEM_NOT_MODIFIED}, so
         *         the field was not modified at the terminal
         */
        public boolean fieldUnmodified() {
            return FLAG_ITEM_NOT_MODIFIED.equals(flagItem);
        }

        /**
         * Returns a copy carrying a different attribute byte, which is how
         * {@code MOVE DFHBMBRY TO FKEYSCA OF CCRDUPAI} at {@code app/cbl/COCRDUPC.cbl:1316} is
         * expressed. The same byte is the flag item, so both views change together, exactly as the
         * {@code REDEFINES} requires.
         *
         * @param replacement the replacement attribute byte, exactly one character
         * @return a copy with the new byte
         * @throws NullPointerException     if {@code replacement} is {@code null}
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

    // =================================================================================================
    // Nested type 2 of 6 - ChangeAction: CCUP-CHANGE-ACTION PIC X(1) and its NINE 88-levels.
    // =================================================================================================

    /**
     * {@code 10 CCUP-CHANGE-ACTION PIC X(1) VALUE LOW-VALUES}, the single byte of
     * {@code 05 CARD-UPDATE-SCREEN-DATA} at {@code app/cbl/COCRDUPC.cbl:276-290}, together with all
     * <strong>nine</strong> of its {@code 88}-level condition names.
     *
     * <p>Two of the nine group several values and therefore <strong>overlap</strong> the
     * single-valued ones: {@code CCUP-CHANGES-MADE} covers {@code 'E'}, {@code 'N'}, {@code 'C'},
     * {@code 'L'} and {@code 'F'}, and {@code CCUP-CHANGES-FAILED} covers {@code 'L'} and
     * {@code 'F'}. The overlap is preserved rather than resolved, because
     * {@code 2000-DECIDE-ACTION} at {@code app/cbl/COCRDUPC.cbl:948-1030} is an ordered
     * {@code EVALUATE TRUE} whose first matching {@code WHEN} wins - so which conditions are true
     * simultaneously is exactly what decides where control goes.
     *
     * <p>Both {@code LOW-VALUES} and {@code SPACES} satisfy {@code CCUP-DETAILS-NOT-FETCHED}, and
     * they are two distinct byte states - {@code x'00'} and {@code x'20'} or {@code x'40'} depending
     * on the code page - not one. Neither is Java {@code null}, which is not a COBOL state at all
     * and is rejected here.
     *
     * <p>An unrecognised byte is accepted deliberately. {@code 2000-DECIDE-ACTION} ends
     * {@code WHEN OTHER} by abending with code {@code '0001'} and the message
     * {@code 'UNEXPECTED DATA SCENARIO'}; rejecting an unknown byte in this constructor would make
     * that path unreachable and would delete a real behaviour of the program.
     *
     * @param value the stored byte, exactly one character
     */
    public record ChangeAction(String value) {

        /** {@code VALUE LOW-VALUES}, the declared initial state: {@code x'00'}. */
        public static final String LOW_VALUES = "\u0000";

        /** {@code SPACES}, the other byte satisfying {@code CCUP-DETAILS-NOT-FETCHED}. */
        public static final String SPACES = " ";

        /** {@code 88 CCUP-SHOW-DETAILS VALUE 'S'}, {@code app/cbl/COCRDUPC.cbl:281}. */
        public static final String SHOW_DETAILS = "S";

        /** {@code 88 CCUP-CHANGES-NOT-OK VALUE 'E'}, {@code app/cbl/COCRDUPC.cbl:285}. */
        public static final String CHANGES_NOT_OK = "E";

        /**
         * {@code 88 CCUP-CHANGES-OK-NOT-CONFIRMED VALUE 'N'},
         * {@code app/cbl/COCRDUPC.cbl:286}.
         */
        public static final String CHANGES_OK_NOT_CONFIRMED = "N";

        /**
         * {@code 88 CCUP-CHANGES-OKAYED-AND-DONE VALUE 'C'},
         * {@code app/cbl/COCRDUPC.cbl:287}.
         */
        public static final String CHANGES_OKAYED_AND_DONE = "C";

        /**
         * {@code 88 CCUP-CHANGES-OKAYED-LOCK-ERROR VALUE 'L'},
         * {@code app/cbl/COCRDUPC.cbl:289}.
         */
        public static final String CHANGES_OKAYED_LOCK_ERROR = "L";

        /**
         * {@code 88 CCUP-CHANGES-OKAYED-BUT-FAILED VALUE 'F'},
         * {@code app/cbl/COCRDUPC.cbl:290}.
         */
        public static final String CHANGES_OKAYED_BUT_FAILED = "F";

        /** Declared width of {@code CCUP-CHANGE-ACTION PIC X(1)}. */
        public static final int RECORD_LENGTH = 1;

        /** Copybook name of the field, verbatim. */
        public static final String FIELD_NAME = "CCUP-CHANGE-ACTION";

        /**
         * The five values {@code 88 CCUP-CHANGES-MADE} covers, {@code app/cbl/COCRDUPC.cbl:282-284}.
         * A {@link List#of} list, so the constant is immutable.
         */
        public static final List<String> CHANGES_MADE_VALUES = List.of(CHANGES_NOT_OK,
                CHANGES_OK_NOT_CONFIRMED,
                CHANGES_OKAYED_AND_DONE,
                CHANGES_OKAYED_LOCK_ERROR,
                CHANGES_OKAYED_BUT_FAILED);

        /**
         * The two values {@code 88 CCUP-CHANGES-FAILED} covers,
         * {@code app/cbl/COCRDUPC.cbl:288}.
         */
        public static final List<String> CHANGES_FAILED_VALUES =
                List.of(CHANGES_OKAYED_LOCK_ERROR, CHANGES_OKAYED_BUT_FAILED);

        /**
         * The two values {@code 88 CCUP-DETAILS-NOT-FETCHED} covers,
         * {@code app/cbl/COCRDUPC.cbl:278-280}.
         */
        public static final List<String> DETAILS_NOT_FETCHED_VALUES = List.of(LOW_VALUES, SPACES);

        /**
         * Validates the byte.
         *
         * @throws NullPointerException     if {@code value} is {@code null}
         * @throws IllegalArgumentException if {@code value} is not exactly one character
         */
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
         * The other state satisfying {@code CCUP-DETAILS-NOT-FETCHED}: {@code SPACES}. Distinct from
         * {@link #initial()} at the byte level, and both are kept reachable because a field-by-field
         * diff can tell them apart.
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
         * Returns the {@code 'N'} state, which is what {@code SET CCUP-CHANGES-OK-NOT-CONFIRMED TO TRUE} sets.
         *
         * @return {@code SET CCUP-CHANGES-OK-NOT-CONFIRMED TO TRUE}
         */
        public static ChangeAction changesOkNotConfirmed() {
            return new ChangeAction(CHANGES_OK_NOT_CONFIRMED);
        }

        /**
         * Returns the {@code 'C'} state, which is what {@code SET CCUP-CHANGES-OKAYED-AND-DONE TO TRUE} sets.
         *
         * @return {@code SET CCUP-CHANGES-OKAYED-AND-DONE TO TRUE}
         */
        public static ChangeAction changesOkayedAndDone() {
            return new ChangeAction(CHANGES_OKAYED_AND_DONE);
        }

        /**
         * Returns the {@code 'L'} state, which is what {@code SET CCUP-CHANGES-OKAYED-LOCK-ERROR TO TRUE} sets.
         *
         * @return {@code SET CCUP-CHANGES-OKAYED-LOCK-ERROR TO TRUE}
         */
        public static ChangeAction changesOkayedLockError() {
            return new ChangeAction(CHANGES_OKAYED_LOCK_ERROR);
        }

        /**
         * Returns the {@code 'F'} state, which is what {@code SET CCUP-CHANGES-OKAYED-BUT-FAILED TO TRUE} sets.
         *
         * @return {@code SET CCUP-CHANGES-OKAYED-BUT-FAILED TO TRUE}
         */
        public static ChangeAction changesOkayedButFailed() {
            return new ChangeAction(CHANGES_OKAYED_BUT_FAILED);
        }

        /**
         * Wraps an arbitrary byte, including one no {@code 88}-level covers, so the
         * {@code WHEN OTHER} abend path of {@code 2000-DECIDE-ACTION} stays reachable.
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
         * {@code 88 CCUP-DETAILS-NOT-FETCHED VALUES LOW-VALUES, SPACES} - condition 1 of 9,
         * {@code app/cbl/COCRDUPC.cbl:278-280}. The first {@code WHEN} of
         * {@code 2000-DECIDE-ACTION}.
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
         * {@code 88 CCUP-CHANGES-MADE VALUES 'E','N','C','L','F'} - condition 3 of 9, and one of the
         * two grouping levels. It overlaps {@link #isChangesNotOk()},
         * {@link #isChangesOkNotConfirmed()}, {@link #isChangesOkayedAndDone()},
         * {@link #isChangesOkayedLockError()} and {@link #isChangesOkayedButFailed()} by design.
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
         * {@code 88 CCUP-CHANGES-OK-NOT-CONFIRMED VALUE 'N'} - condition 5 of 9. Paired with
         * {@code CCARD-AID-PFK05} it is the {@code WHEN} that performs
         * {@code 9200-WRITE-PROCESSING}.
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
         * {@code 88 CCUP-CHANGES-FAILED VALUES 'L','F'} - condition 7 of 9, and the second grouping
         * level. It overlaps {@link #isChangesOkayedLockError()} and
         * {@link #isChangesOkayedButFailed()} by design, and is a strict subset of
         * {@link #isChangesMade()}.
         *
         * @return {@code true} for {@code 'L'} and {@code 'F'} only
         */
        @JsonIgnore
        public boolean isChangesFailed() {
            return CHANGES_FAILED_VALUES.contains(value);
        }

        /**
         * {@code 88 CCUP-CHANGES-OKAYED-LOCK-ERROR VALUE 'L'} - condition 8 of 9. Set when
         * {@code 9200-WRITE-PROCESSING} reports {@code COULD-NOT-LOCK-FOR-UPDATE}.
         *
         * @return {@code true} for {@code 'L'}
         */
        @JsonIgnore
        public boolean isChangesOkayedLockError() {
            return CHANGES_OKAYED_LOCK_ERROR.equals(value);
        }

        /**
         * {@code 88 CCUP-CHANGES-OKAYED-BUT-FAILED VALUE 'F'} - condition 9 of 9. Set when
         * {@code 9200-WRITE-PROCESSING} reports {@code LOCKED-BUT-UPDATE-FAILED}.
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
         * <p>Not itself an {@code 88}-level: it is the guard that identifies the
         * {@code WHEN OTHER} case of {@code 2000-DECIDE-ACTION}, which abends with
         * {@code 'UNEXPECTED DATA SCENARIO'}.
         *
         * @return {@code false} exactly when the byte would fall through to {@code WHEN OTHER}
         */
        @JsonIgnore
        public boolean isRecognised() {
            return isDetailsNotFetched() || isShowDetails() || isChangesMade();
        }

        /**
         * @return the stored byte rendered for diagnostics, with {@code LOW-VALUES} named rather
         *         than emitted as an unprintable character
         */
        @Override
        public String toString() {
            return FIELD_NAME + "=" + (LOW_VALUES.equals(value)
                    ? "LOW-VALUES"
                    : "'" + DiagnosticText.singleLine(value) + "'");
        }

        /**
         * Names the {@code 88}-level that holds, for a log line or an assertion message, without
         * deciding anything.
         *
         * <p>{@code app/cbl/COCRDUPC.cbl:278-290} declares <strong>nine</strong> condition names over
         * one byte, and two of them - {@code CCUP-CHANGES-MADE} ({@code 'E'}, {@code 'N'},
         * {@code 'C'}, {@code 'L'}, {@code 'F'}) at {@code :282} and {@code CCUP-CHANGES-FAILED}
         * ({@code 'L'}, {@code 'F'}) at {@code :288} - are umbrellas that hold at the same time as one
         * of the five specific names. This method reports the <strong>most specific</strong> name that
         * holds and never an umbrella, because {@code CCUP-CHANGES-OKAYED-LOCK-ERROR} tells a reader
         * everything {@code CCUP-CHANGES-MADE} would and more. Nothing is lost: both umbrellas are
         * exposed as their own predicates, {@link #isChangesMade()} and {@link #isChangesFailed()}, and
         * they are what a caller tests when the umbrella really is the question.
         *
         * <p>No parity rests on the string. {@code 2000-DECIDE-ACTION} evaluates the condition names
         * itself and never a description, so this is a diagnostic only. A byte no condition name covers
         * is reported as unrecognised rather than guessed at: that is the state which reaches
         * {@code 2000-DECIDE-ACTION}'s {@code WHEN OTHER}, and naming it wrongly would hide it.
         *
         * @return the most specific condition name that holds, for example
         *         {@code "CCUP-CHANGES-OKAYED-AND-DONE"}, or {@code "UNRECOGNISED('x')"} for a state
         *         no {@code 88}-level covers
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


    // =================================================================================================
    // Nested type 3 of 6 - DetailGroup: which of the two 89-byte snapshots a CardDetails is, and the
    // verbatim COBOL name of every one of its items. The two groups have IDENTICAL shape and
    // DIFFERENT names, and names are the contract, so the group travels with the value.
    // =================================================================================================

    /**
     * The two {@code 05}-level snapshot groups of the commarea:
     * {@code 05 CCUP-OLD-DETAILS} at {@code app/cbl/COCRDUPC.cbl:291-301} and
     * {@code 05 CCUP-NEW-DETAILS} at lines 303-313.
     *
     * <p>Their layouts are byte-identical; only the item names differ, by prefix. Rather than
     * duplicate eleven declarations twice, each constant supplies its own prefix and builds its own
     * {@link RecordLayout} with every span named verbatim - so
     * {@code DetailGroup.OLD.acctidSpan().name()} is {@code "CCUP-OLD-ACCTID"} and the {@code NEW}
     * equivalent is {@code "CCUP-NEW-ACCTID"}, and a field-by-field diff can never confuse the
     * before image with the after image.
     *
     * <p>Each layout also declares the two {@code 20}-level group items as {@code REDEFINES}
     * overlays, which is what {@link FieldSpan#redefining(String, int, int, PictureKind)} exists for:
     * {@code CCUP-xxx-CARDDATA} spans the 59 bytes of name, expiry and status, and
     * {@code CCUP-xxx-EXPIRAION-DATE} - misspelled in the source and reproduced verbatim - spans the
     * eight bytes of year, month and day. An overlay contributes no storage, so the group still
     * totals exactly {@value CardDetails#RECORD_LENGTH} bytes.
     */
    public enum DetailGroup {

        /**
         * {@code CCUP-OLD-DETAILS} - the snapshot the screen was painted from, and the one
         * {@code 9300-CHECK-CHANGE-IN-REC} compares the freshly read record against before allowing
         * a rewrite.
         */
        OLD("CCUP-OLD-"),

        /**
         * {@code CCUP-NEW-DETAILS} - what the user typed, and the source of every value
         * {@code 9200-WRITE-PROCESSING} moves into {@code CARD-UPDATE-RECORD}.
         */
        NEW("CCUP-NEW-");

        /** The name prefix every item of this group carries, verbatim. */
        private final String prefix;

        /** {@code CCUP-xxx-ACCTID PIC X(11)}. */
        private final FieldSpan acctidSpan;

        /** {@code CCUP-xxx-CARDID PIC X(16)}. */
        private final FieldSpan cardidSpan;

        /** {@code CCUP-xxx-CVV-CD PIC X(3)} - alphanumeric, unlike its numeric record counterpart. */
        private final FieldSpan cvvCdSpan;

        /** {@code CCUP-xxx-CRDNAME PIC X(50)}. */
        private final FieldSpan crdnameSpan;

        /** {@code CCUP-xxx-EXPYEAR PIC X(4)}. */
        private final FieldSpan expyearSpan;

        /** {@code CCUP-xxx-EXPMON PIC X(2)}. */
        private final FieldSpan expmonSpan;

        /** {@code CCUP-xxx-EXPDAY PIC X(2)}. */
        private final FieldSpan expdaySpan;

        /** {@code CCUP-xxx-CRDSTCD PIC X(1)}. */
        private final FieldSpan crdstcdSpan;

        /** {@code CCUP-xxx-CARDDATA}, the 59-byte {@code 20}-level group, as an overlay. */
        private final FieldSpan carddataSpan;

        /** {@code CCUP-xxx-EXPIRAION-DATE}, the 8-byte {@code 20}-level group, as an overlay. */
        private final FieldSpan expiraionDateSpan;

        /** The group's {@value CardDetails#RECORD_LENGTH}-byte layout. */
        private final RecordLayout layout;

        /**
         * Builds one group's item descriptors and its layout from the geometry constants of
         * {@link CardDetails}, so every span name carries this group's prefix verbatim.
         *
         * @param prefix the item-name prefix, {@code "CCUP-OLD-"} or {@code "CCUP-NEW-"}
         */
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
         *         {@value CardDetails#CARDDATA_OFFSET}
         */
        public FieldSpan carddataSpan() {
            return carddataSpan;
        }

        /**
         * Returns the descriptor of this group's {@code EXPIRAION-DATE} item.
         *
         * @return the {@code CCUP-xxx-EXPIRAION-DATE} group overlay, 8 bytes at offset
         *         {@value CardDetails#EXPIRAION_DATE_OFFSET}, name misspelled exactly as the source
         *         spells it
         */
        public FieldSpan expiraionDateSpan() {
            return expiraionDateSpan;
        }

        /**
         * Returns this group's record layout, transcribed from the commarea.
         *
         * @return the group's layout, whose spans are contiguous from zero and sum to exactly
         *         {@value CardDetails#RECORD_LENGTH} bytes
         */
        public RecordLayout layout() {
            return layout;
        }
    }

    // =================================================================================================
    // Nested type 4 of 6 - CardDetails: one 89-byte snapshot, CCUP-OLD-DETAILS or CCUP-NEW-DETAILS.
    // =================================================================================================

    /**
     * One {@value #RECORD_LENGTH}-byte snapshot group of the commarea - either
     * {@code CCUP-OLD-DETAILS} or {@code CCUP-NEW-DETAILS}, as {@link #group()} says.
     *
     * <p>Every item is {@code PIC X}, so every component is a {@code String} carried at exactly its
     * declared width. The width total is
     * {@code 11 + 16 + 3 + 50 + (4 + 2 + 2) + 1 = }{@value #RECORD_LENGTH}, and
     * {@link DetailGroup#layout()} enforces it.
     *
     * <p>The year, month and day are exposed as three separate components rather than as one
     * composed string, because {@code 9300-CHECK-CHANGE-IN-REC} at
     * {@code app/cbl/COCRDUPC.cbl:1498-1521} compares them individually against slices of the
     * record's {@code X(10)} date - {@code CARD-EXPIRAION-DATE(1:4)}, {@code (6:2)} and
     * {@code (9:2)}. {@link #ccupExpiraionDate()} does compose them, but with <strong>no
     * separators</strong>: this group's date is eight bytes, not ten, and the two shapes are
     * deliberately not unified.
     *
     * <p>{@link #cvvCd()} is the {@code PIC X(3)} form. Its counterpart in
     * {@link CardUpdateRecord#cardUpdateCvvCd()} is {@code PIC 9(03)} and is an {@code int}; the
     * COBOL bridges the two through a {@code REDEFINES} pair, and the asymmetry is preserved here
     * rather than smoothed away.
     *
     * @param group    which of the two groups this is, and therefore what every item is named
     * @param acctid   {@code CCUP-xxx-ACCTID PIC X(11)}
     * @param cardid   {@code CCUP-xxx-CARDID PIC X(16)}
     * @param cvvCd    {@code CCUP-xxx-CVV-CD PIC X(3)} - alphanumeric
     * @param crdname  {@code CCUP-xxx-CRDNAME PIC X(50)}
     * @param expyear  {@code CCUP-xxx-EXPYEAR PIC X(4)}
     * @param expmon   {@code CCUP-xxx-EXPMON PIC X(2)}
     * @param expday   {@code CCUP-xxx-EXPDAY PIC X(2)}
     * @param crdstcd  {@code CCUP-xxx-CRDSTCD PIC X(1)}
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

        /** Total width of the group: {@code 11+16+3+50+4+2+2+1}. */
        public static final int RECORD_LENGTH = 89;

        /** Absolute 0-based offset of {@code CCUP-xxx-ACCTID}. */
        public static final int ACCTID_OFFSET = 0;

        /** Declared width of {@code CCUP-xxx-ACCTID PIC X(11)}. */
        public static final int ACCTID_LENGTH = 11;

        /** Absolute 0-based offset of {@code CCUP-xxx-CARDID}. */
        public static final int CARDID_OFFSET = 11;

        /** Declared width of {@code CCUP-xxx-CARDID PIC X(16)}. */
        public static final int CARDID_LENGTH = 16;

        /** Absolute 0-based offset of {@code CCUP-xxx-CVV-CD}. */
        public static final int CVV_CD_OFFSET = 27;

        /** Declared width of {@code CCUP-xxx-CVV-CD PIC X(3)} - alphanumeric, not numeric. */
        public static final int CVV_CD_LENGTH = 3;

        /** Absolute 0-based offset of {@code CCUP-xxx-CRDNAME}, and of the {@code CARDDATA} group. */
        public static final int CRDNAME_OFFSET = 30;

        /** Declared width of {@code CCUP-xxx-CRDNAME PIC X(50)}. */
        public static final int CRDNAME_LENGTH = 50;

        /** Absolute 0-based offset of {@code CCUP-xxx-EXPYEAR}. */
        public static final int EXPYEAR_OFFSET = 80;

        /** Declared width of {@code CCUP-xxx-EXPYEAR PIC X(4)}. */
        public static final int EXPYEAR_LENGTH = 4;

        /** Absolute 0-based offset of {@code CCUP-xxx-EXPMON}. */
        public static final int EXPMON_OFFSET = 84;

        /** Declared width of {@code CCUP-xxx-EXPMON PIC X(2)}. */
        public static final int EXPMON_LENGTH = 2;

        /** Absolute 0-based offset of {@code CCUP-xxx-EXPDAY}. */
        public static final int EXPDAY_OFFSET = 86;

        /** Declared width of {@code CCUP-xxx-EXPDAY PIC X(2)}. */
        public static final int EXPDAY_LENGTH = 2;

        /** Absolute 0-based offset of {@code CCUP-xxx-CRDSTCD}. */
        public static final int CRDSTCD_OFFSET = 88;

        /** Declared width of {@code CCUP-xxx-CRDSTCD PIC X(1)}. */
        public static final int CRDSTCD_LENGTH = 1;

        /**
         * Absolute 0-based offset of the {@code 20}-level group {@code CCUP-xxx-CARDDATA}, which
         * begins at the embossed name.
         */
        public static final int CARDDATA_OFFSET = 30;

        /**
         * Width of {@code CCUP-xxx-CARDDATA}: the name, the eight-byte date and the status,
         * {@code 50 + 8 + 1 = 59}.
         */
        public static final int CARDDATA_LENGTH = 59;

        /**
         * Absolute 0-based offset of the {@code 20}-level group
         * {@code CCUP-xxx-EXPIRAION-DATE} - spelled exactly as the source spells it.
         */
        public static final int EXPIRAION_DATE_OFFSET = 80;

        /**
         * Width of {@code CCUP-xxx-EXPIRAION-DATE}: {@code 4 + 2 + 2 = 8}, with <strong>no
         * separators</strong>. The record's counterpart is ten bytes and does carry them.
         */
        public static final int EXPIRAION_DATE_LENGTH = 8;

        /**
         * Normalises and checks every component, so a snapshot can never exist in a shape the
         * commarea cannot hold.
         *
         * <p>A {@code null} becomes that item's declared width in spaces, because COBOL has no
         * {@code null}. A short value is padded on the right, which is the lossless half of a COBOL
         * alphanumeric {@code MOVE}. An over-wide value is <strong>rejected</strong> rather than
         * truncated: silent truncation inside the commarea would corrupt
         * {@code 9300-CHECK-CHANGE-IN-REC}'s comparison, and the caller is better served by being
         * told which item does not fit.
         *
         * @throws NullPointerException     if {@code group} is {@code null}
         * @throws IllegalArgumentException if any value exceeds its declared width
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
         * A snapshot in which every item holds its declared width in spaces - the state
         * {@code INITIALIZE} would leave an all-{@code PIC X} group in.
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
         * <p>Values come back <strong>raw and untrimmed</strong>, each at exactly its declared width,
         * which is what makes the round trip byte-identical.
         *
         * @param image exactly {@value #RECORD_LENGTH} bytes
         * @param group which group the bytes are
         * @param codec the codec, carrying the code page explicitly
         * @return the snapshot
         * @throws NullPointerException     if any argument is {@code null}
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
         * @param area  the record area, of the group's layout
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

        /**
         * Builds the record area, span by span. Every write goes through the codec's {@code PIC X}
         * move rule; this method never pads or truncates by hand.
         *
         * @param codec the codec, carrying the code page explicitly
         * @return an area of exactly {@value #RECORD_LENGTH} bytes
         * @throws NullPointerException if {@code codec} is {@code null}
         */
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
         * The {@code CCUP-xxx-EXPIRAION-DATE} group item: year, month and day concatenated with
         * <strong>no separator</strong>, exactly {@value #EXPIRAION_DATE_LENGTH} bytes.
         *
         * <p>Not to be confused with {@link CardUpdateRecord#cardUpdateExpiraionDate()}, which is ten
         * bytes and does carry {@code '-'} separators. Keeping both shapes is what lets
         * {@code 9300-CHECK-CHANGE-IN-REC} compare one against slices of the other.
         *
         * @return exactly {@value #EXPIRAION_DATE_LENGTH} characters
         */
        public String ccupExpiraionDate() {
            return expyear + expmon + expday;
        }

        /**
         * The {@code CCUP-xxx-CARDDATA} group item: the embossed name, the eight-byte date and the
         * status, exactly {@value #CARDDATA_LENGTH} bytes.
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
         * Every item of this snapshot keyed by its verbatim COBOL name, in declaration order,
         * followed by the two group items. This is the map a field-by-field diff consumes.
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
         * {@code MOVE CCUP-NEW-DETAILS TO CCUP-OLD-DETAILS} would be expressed: same bytes, other
         * name set.
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

        /**
         * Pads a value to its declared width, or rejects it when it is too wide to fit. Padding a
         * short value is lossless; truncating a long one is not, and would be invisible at the call
         * site.
         *
         * @param group         the group whose prefix names the offending item in any message
         * @param suffix        the item suffix, for example {@code "CVV-CD"}
         * @param value         the supplied value, possibly {@code null}
         * @param declaredWidth the item's declared width in characters
         * @return an image of exactly {@code declaredWidth} characters
         * @throws IllegalArgumentException if {@code value} is wider than {@code declaredWidth}
         */
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
         * <p>The override exists because this is a nested {@code record}, and a nested record's generated
         * {@code toString} is reached through the enclosing type's rendering just as readily as through its
         * own. These components are the whole of a card credential: the account it belongs to, the card
         * number, the verification value and the embossed name. The CVV is withheld outright - a
         * three-digit value has no safely-revealable part - and the expiry parts and status code stay
         * legible, because they are what a card-update validation parity failure is read from.
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


    // =================================================================================================
    // Nested type 5 of 6 - CardUpdateRecord: CARD-UPDATE-RECORD, 150 bytes, modelled INLINE.
    //
    // Its layout is byte-identical to app/cpy/CVACT02Y.cpy's CARD-RECORD (16/11/3/50/10/1/59), and
    // card/model/CardRecord already models that. It is still declared here rather than reused, because
    // every field NAME differs - CARD-UPDATE-NUM against CARD-NUM, and so on - and field names are
    // part of the contract that field-for-field diffing keys on. The migration plan sets the precedent
    // itself by keeping CUSTREC separate from CVCUS01Y over a single differing name.
    // =================================================================================================

    /**
     * {@code 05 CARD-UPDATE-RECORD}, {@code app/cbl/COCRDUPC.cbl:314-321} - the
     * {@value #RECORD_LENGTH}-byte image {@code 9200-WRITE-PROCESSING} stages and then hands to
     * {@code EXEC CICS REWRITE FILE(LIT-CARDFILENAME) FROM(CARD-UPDATE-RECORD)}.
     *
     * <p>Widths total {@code 16 + 11 + 3 + 50 + 10 + 1 + 59 = }{@value #RECORD_LENGTH}. The trailing
     * {@code FILLER PIC X(59)} is a first-class span of {@link #LAYOUT} and is emitted as spaces:
     * omitting it would make the record 91 bytes and break every offset downstream of it, and
     * {@link RecordLayout} refuses to exist unless its spans sum to exactly
     * {@value #RECORD_LENGTH}.
     *
     * <p>{@code CARD-UPDATE-ACCT-ID} is {@code PIC 9(11)} and {@code CARD-UPDATE-CVV-CD} is
     * {@code PIC 9(03)}: scale-free zoned {@code DISPLAY} integers, so a {@code long} and an
     * {@code int} respectively. Their images are produced and consumed by {@link FixedWidthCodec},
     * never by {@code Long.parseLong} or {@code Integer.parseInt} on a raw span - the codec owns what
     * a non-digit byte means and reports the offending character, where a bare parse would either
     * throw an unhelpful exception or return a plausible zero.
     *
     * <p>{@code CARD-UPDATE-EXPIRAION-DATE} is misspelled in the source and is reproduced verbatim.
     * It is {@code PIC X(10)} in {@code YYYY-MM-DD} form, composed at
     * {@code app/cbl/COCRDUPC.cbl:1467-1474} by {@code STRING CCUP-NEW-EXPYEAR '-' CCUP-NEW-EXPMON
     * '-' CCUP-NEW-EXPDAY DELIMITED BY SIZE}. {@link #compose(CardDetails, FixedWidthCodec)}
     * reproduces that composition through the codec, and
     * {@link #cardUpdateExpiraionDateYear()} and its two siblings reproduce the
     * {@code (1:4)} / {@code (6:2)} / {@code (9:2)} slices that
     * {@code 9300-CHECK-CHANGE-IN-REC} compares.
     *
     * <p>This is a byte-layout value and not a persistence entity: there is no {@code @Entity}, no
     * {@code @Table}, no {@code @Id} and no {@code @Version}. The optimistic-concurrency check for
     * this screen is the COBOL's own field-by-field comparison in
     * {@code 9300-CHECK-CHANGE-IN-REC}, never a version column.
     *
     * @param cardUpdateNum          {@code CARD-UPDATE-NUM PIC X(16)}
     * @param cardUpdateAcctId       {@code CARD-UPDATE-ACCT-ID PIC 9(11)}
     * @param cardUpdateCvvCd        {@code CARD-UPDATE-CVV-CD PIC 9(03)} - numeric, unlike the
     *                               {@code X(3)} form in {@link CardDetails#cvvCd()}
     * @param cardUpdateEmbossedName {@code CARD-UPDATE-EMBOSSED-NAME PIC X(50)}
     * @param cardUpdateExpiraionDate {@code CARD-UPDATE-EXPIRAION-DATE PIC X(10)}, misspelling
     *                               preserved
     * @param cardUpdateActiveStatus {@code CARD-UPDATE-ACTIVE-STATUS PIC X(01)}
     */
    public record CardUpdateRecord(String cardUpdateNum,
                                   long cardUpdateAcctId,
                                   int cardUpdateCvvCd,
                                   String cardUpdateEmbossedName,
                                   String cardUpdateExpiraionDate,
                                   String cardUpdateActiveStatus) {

        /** Total width: {@code 16+11+3+50+10+1+59}, the same 150 the card file record occupies. */
        public static final int RECORD_LENGTH = 150;

        /** Absolute 0-based offset of {@code CARD-UPDATE-NUM}. */
        public static final int CARD_UPDATE_NUM_OFFSET = 0;

        /** Declared width of {@code CARD-UPDATE-NUM PIC X(16)}. */
        public static final int CARD_UPDATE_NUM_LENGTH = 16;

        /** Absolute 0-based offset of {@code CARD-UPDATE-ACCT-ID}. */
        public static final int CARD_UPDATE_ACCT_ID_OFFSET = 16;

        /** Declared digit count of {@code CARD-UPDATE-ACCT-ID PIC 9(11)}, one digit per byte. */
        public static final int CARD_UPDATE_ACCT_ID_LENGTH = 11;

        /** Absolute 0-based offset of {@code CARD-UPDATE-CVV-CD}. */
        public static final int CARD_UPDATE_CVV_CD_OFFSET = 27;

        /** Declared digit count of {@code CARD-UPDATE-CVV-CD PIC 9(03)}, one digit per byte. */
        public static final int CARD_UPDATE_CVV_CD_LENGTH = 3;

        /** Absolute 0-based offset of {@code CARD-UPDATE-EMBOSSED-NAME}. */
        public static final int CARD_UPDATE_EMBOSSED_NAME_OFFSET = 30;

        /** Declared width of {@code CARD-UPDATE-EMBOSSED-NAME PIC X(50)}. */
        public static final int CARD_UPDATE_EMBOSSED_NAME_LENGTH = 50;

        /**
         * Absolute 0-based offset of {@code CARD-UPDATE-EXPIRAION-DATE} - the source's spelling, kept.
         */
        public static final int CARD_UPDATE_EXPIRAION_DATE_OFFSET = 80;

        /**
         * Declared width of {@code CARD-UPDATE-EXPIRAION-DATE PIC X(10)}: ten bytes, because this
         * form carries the two {@code '-'} separators the eight-byte commarea form does not.
         */
        public static final int CARD_UPDATE_EXPIRAION_DATE_LENGTH = 10;

        /** Absolute 0-based offset of {@code CARD-UPDATE-ACTIVE-STATUS}. */
        public static final int CARD_UPDATE_ACTIVE_STATUS_OFFSET = 90;

        /** Declared width of {@code CARD-UPDATE-ACTIVE-STATUS PIC X(01)}. */
        public static final int CARD_UPDATE_ACTIVE_STATUS_LENGTH = 1;

        /**
         * Absolute 0-based offset of the trailing {@code FILLER PIC X(59)},
         * {@code app/cbl/COCRDUPC.cbl:321}.
         */
        public static final int FILLER_OFFSET = 91;

        /**
         * Declared width of the trailing {@code FILLER PIC X(59)}. It must be emitted: without it the
         * record is 91 bytes and every downstream offset is wrong.
         */
        public static final int FILLER_LENGTH = 59;

        /** One past the largest value {@code PIC 9(11)} can hold. */
        public static final long CARD_UPDATE_ACCT_ID_EXCLUSIVE_LIMIT = 100_000_000_000L;

        /** One past the largest value {@code PIC 9(03)} can hold: {@code 1000}. */
        public static final int CARD_UPDATE_CVV_CD_EXCLUSIVE_LIMIT = 1_000;

        /** The separator {@code STRING … DELIMITED BY SIZE} places between the date parts. */
        public static final String EXPIRAION_DATE_SEPARATOR = "-";

        /** 0-based begin index of the year within the {@code X(10)} date - COBOL {@code (1:4)}. */
        public static final int EXPIRAION_YEAR_BEGIN_INDEX = 0;

        /** 0-based end index, exclusive, of the year within the {@code X(10)} date. */
        public static final int EXPIRAION_YEAR_END_INDEX = 4;

        /** 0-based begin index of the month within the {@code X(10)} date - COBOL {@code (6:2)}. */
        public static final int EXPIRAION_MONTH_BEGIN_INDEX = 5;

        /** 0-based end index, exclusive, of the month within the {@code X(10)} date. */
        public static final int EXPIRAION_MONTH_END_INDEX = 7;

        /** 0-based begin index of the day within the {@code X(10)} date - COBOL {@code (9:2)}. */
        public static final int EXPIRAION_DAY_BEGIN_INDEX = 8;

        /** 0-based end index, exclusive, of the day within the {@code X(10)} date. */
        public static final int EXPIRAION_DAY_END_INDEX = 10;

        /** {@code CARD-UPDATE-NUM PIC X(16)} at offset {@value #CARD_UPDATE_NUM_OFFSET}. */
        public static final FieldSpan CARD_UPDATE_NUM = FieldSpan.alphanumeric("CARD-UPDATE-NUM",
                CARD_UPDATE_NUM_OFFSET, CARD_UPDATE_NUM_LENGTH);

        /** {@code CARD-UPDATE-ACCT-ID PIC 9(11)} at offset {@value #CARD_UPDATE_ACCT_ID_OFFSET}. */
        public static final FieldSpan CARD_UPDATE_ACCT_ID = FieldSpan.unsignedNumeric(
                "CARD-UPDATE-ACCT-ID", CARD_UPDATE_ACCT_ID_OFFSET, CARD_UPDATE_ACCT_ID_LENGTH);

        /** {@code CARD-UPDATE-CVV-CD PIC 9(03)} at offset {@value #CARD_UPDATE_CVV_CD_OFFSET}. */
        public static final FieldSpan CARD_UPDATE_CVV_CD = FieldSpan.unsignedNumeric(
                "CARD-UPDATE-CVV-CD", CARD_UPDATE_CVV_CD_OFFSET, CARD_UPDATE_CVV_CD_LENGTH);

        /**
         * {@code CARD-UPDATE-EMBOSSED-NAME PIC X(50)} at offset
         * {@value #CARD_UPDATE_EMBOSSED_NAME_OFFSET}.
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
         * {@code CARD-UPDATE-ACTIVE-STATUS PIC X(01)} at offset
         * {@value #CARD_UPDATE_ACTIVE_STATUS_OFFSET}.
         */
        public static final FieldSpan CARD_UPDATE_ACTIVE_STATUS = FieldSpan.alphanumeric(
                "CARD-UPDATE-ACTIVE-STATUS", CARD_UPDATE_ACTIVE_STATUS_OFFSET,
                CARD_UPDATE_ACTIVE_STATUS_LENGTH);

        /** The trailing {@code FILLER PIC X(59)} at offset {@value #FILLER_OFFSET}. */
        public static final FieldSpan FILLER = FieldSpan.filler(FILLER_OFFSET, FILLER_LENGTH);

        /**
         * The record's layout. Seven spans, contiguous from zero, summing to exactly
         * {@value #RECORD_LENGTH} - which is why a dropped {@code FILLER} cannot compile.
         */
        public static final RecordLayout LAYOUT = RecordLayout.of(RECORD_LENGTH,
                CARD_UPDATE_NUM,
                CARD_UPDATE_ACCT_ID,
                CARD_UPDATE_CVV_CD,
                CARD_UPDATE_EMBOSSED_NAME,
                CARD_UPDATE_EXPIRAION_DATE,
                CARD_UPDATE_ACTIVE_STATUS,
                FILLER);

        /**
         * Normalises and checks every component.
         *
         * <p>Each {@code PIC X} value is padded on the right with spaces to its declared width, and a
         * {@code null} becomes all spaces. An over-wide value is rejected rather than truncated. Each
         * {@code PIC 9} value must fit its declared digit count and must not be negative, because
         * {@code PIC 9} has no sign position at all.
         *
         * @throws IllegalArgumentException if a character value is too wide, or a numeric value is
         *                                  negative or too large for its digit count
         */
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
         * The state {@code INITIALIZE CARD-UPDATE-RECORD} leaves at
         * {@code app/cbl/COCRDUPC.cbl:1461}: every {@code PIC X} item all spaces and every
         * {@code PIC 9} item zero.
         *
         * @return the initialised record
         */
        public static CardUpdateRecord initialised() {
            return new CardUpdateRecord(null, 0L, 0, null, null, null);
        }

        /**
         * Stages the record from a {@code CCUP-NEW-DETAILS} snapshot, reproducing
         * {@code 9200-WRITE-PROCESSING} at {@code app/cbl/COCRDUPC.cbl:1461-1476} field for field:
         * the card number and the embossed name move across unchanged, the alphanumeric
         * {@code PIC X(3)} CVV is reinterpreted as the numeric {@code PIC 9(03)} item exactly as the
         * COBOL's {@code CARD-CVV-CD-X} / {@code CARD-CVV-CD-N} {@code REDEFINES} pair does, and the
         * eight-byte date is composed into the ten-byte form with explicit {@code '-'} separators
         * through {@link FixedWidthCodec#concatenateDelimitedBySize(String...)}.
         *
         * <p>The account identifier is supplied separately because the COBOL takes it from
         * {@code CC-ACCT-ID-N} - the numeric view of the work area's account identifier - and not from
         * the snapshot's {@code PIC X(11)} item.
         *
         * @param details the {@code CCUP-NEW-DETAILS} snapshot; its group must be
         *                {@link DetailGroup#NEW}
         * @param acctId  the value of {@code CC-ACCT-ID-N}
         * @param codec   the codec, which owns the numeric decode and the delimited concatenation
         * @return the staged record
         * @throws NullPointerException     if {@code details} or {@code codec} is {@code null}
         * @throws IllegalArgumentException if {@code details} is not the {@link DetailGroup#NEW}
         *                                  snapshot, if the CVV item holds a non-digit, or if
         *                                  {@code acctId} does not fit {@code PIC 9(11)}
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
         * {@code STRING CCUP-NEW-EXPYEAR '-' CCUP-NEW-EXPMON '-' CCUP-NEW-EXPDAY DELIMITED BY SIZE}
         * at {@code app/cbl/COCRDUPC.cbl:1467-1474}.
         *
         * @param details the snapshot supplying year, month and day
         * @param codec   the codec, which owns {@code DELIMITED BY SIZE} concatenation
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
         * @throws NullPointerException     if either argument is {@code null}
         * @throws IllegalArgumentException if {@code image} is not {@value #RECORD_LENGTH} bytes, or a
         *                                  numeric span holds a non-digit
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
         * @param image   exactly {@value #RECORD_LENGTH} bytes
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
         * @param area  the record area, of {@link #LAYOUT}
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
         * @return exactly {@value #RECORD_LENGTH} bytes, the trailing
         *         {@value #FILLER_LENGTH} of which are spaces
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
         * <p>The re-fill is redundant - {@link FixedWidthCodec#newRecord(RecordLayout)} has already
         * initialised every {@code FILLER} to the code page's space byte - and it is kept anyway,
         * because the one span with no accessor of its own is the one a reader is most likely to
         * assume was forgotten.
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
         * The zoned image of {@code CARD-UPDATE-ACCT-ID}: {@value #CARD_UPDATE_ACCT_ID_LENGTH}
         * characters, zero-filled on the left.
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
         * The zoned image of {@code CARD-UPDATE-CVV-CD}: {@value #CARD_UPDATE_CVV_CD_LENGTH}
         * characters, zero-filled on the left. This is the numeric form; the commarea snapshots hold
         * the alphanumeric {@code PIC X(3)} form instead.
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
         * The year slice of the ten-byte date - COBOL {@code CARD-EXPIRAION-DATE(1:4)}, translated
         * from 1-based reference modification to 0-based indices. A view over the stored span, never
         * separate state.
         *
         * @return four characters
         */
        public String cardUpdateExpiraionDateYear() {
            return cardUpdateExpiraionDate.substring(EXPIRAION_YEAR_BEGIN_INDEX,
                    EXPIRAION_YEAR_END_INDEX);
        }

        /**
         * The month slice - COBOL {@code (6:2)}, so 0-based indices 5 to 7. Index 4 is the
         * {@code '-'} separator and is deliberately skipped.
         *
         * @return two characters
         */
        public String cardUpdateExpiraionDateMonth() {
            return cardUpdateExpiraionDate.substring(EXPIRAION_MONTH_BEGIN_INDEX,
                    EXPIRAION_MONTH_END_INDEX);
        }

        /**
         * The day slice - COBOL {@code (9:2)}, so 0-based indices 8 to 10. Index 7 is the second
         * {@code '-'} separator.
         *
         * @return two characters
         */
        public String cardUpdateExpiraionDateDay() {
            return cardUpdateExpiraionDate.substring(EXPIRAION_DAY_BEGIN_INDEX,
                    EXPIRAION_DAY_END_INDEX);
        }

        /**
         * Whether the stored record still matches the snapshot the screen was painted from, which is
         * the whole of {@code 9300-CHECK-CHANGE-IN-REC}'s test at
         * {@code app/cbl/COCRDUPC.cbl:1503-1509}: the CVV, the embossed name, the three date slices
         * and the active status, in that order, all compared field by field.
         *
         * <p>The card number and the account identifier are deliberately <strong>not</strong> part of
         * the comparison, because the COBOL does not compare them. There is no version column and no
         * timestamp: the comparison <em>is</em> the concurrency control.
         *
         * @param snapshot the {@code CCUP-OLD-DETAILS} snapshot to compare against
         * @param codec    the codec, which renders this record's numeric CVV into the snapshot's
         *                 three-character alphanumeric form so like is compared with like
         * @return {@code true} when every compared item matches, so the rewrite may proceed
         * @throws NullPointerException     if either argument is {@code null}
         * @throws IllegalArgumentException if {@code snapshot} is not the {@link DetailGroup#OLD}
         *                                  snapshot
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
         * Every item of the record keyed by its verbatim COBOL name, in declaration order, with the
         * numeric items rendered as their zoned images so a field-by-field diff compares bytes.
         * {@code FILLER} is excluded, exactly as {@link FixedWidthCodec#deserialise(RecordLayout,
         * byte[])} excludes it.
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
         * @throws IllegalArgumentException if it exceeds
         *                                  {@value #CARD_UPDATE_EMBOSSED_NAME_LENGTH} characters
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
         * @throws IllegalArgumentException if it exceeds
         *                                  {@value #CARD_UPDATE_EXPIRAION_DATE_LENGTH} characters
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
         * @throws IllegalArgumentException if it exceeds
         *                                  {@value #CARD_UPDATE_ACTIVE_STATUS_LENGTH} characters
         */
        public CardUpdateRecord withCardUpdateActiveStatus(String replacement) {
            return new CardUpdateRecord(cardUpdateNum, cardUpdateAcctId, cardUpdateCvvCd,
                    cardUpdateEmbossedName, cardUpdateExpiraionDate, replacement);
        }

        /**
         * Pads a {@code PIC X} value to its span width, rejecting one too wide to fit.
         *
         * @param span  the receiving span, which supplies the declared width and the message text
         * @param value the supplied value, possibly {@code null}
         * @return an image of exactly {@code span.length()} characters
         * @throws IllegalArgumentException if {@code value} is wider than the span
         */
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

        /**
         * Rejects a {@code PIC 9} value that is negative or wider than its declared digit count.
         *
         * @param span           the receiving span, named in any message
         * @param value          the supplied value
         * @param exclusiveLimit one past the largest value the span's digit count can hold
         * @throws IllegalArgumentException if {@code value} is negative or at least
         *                                  {@code exclusiveLimit}
         */
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
         * <p>The override exists because this is a nested {@code record}, and a nested record's generated
         * {@code toString} is reached through the enclosing type's rendering just as readily as through its
         * own. These six components are the whole of a card credential as {@code COCRDUPC} receives it:
         * the card number, the account it belongs to, the verification value and the embossed name. The
         * CVV is withheld outright - a three-digit value has no safely-revealable part - while the expiry
         * date and status code stay legible, because they are what a card-update validation parity failure
         * is read from.
         *
         * <p>Only this rendering is masked. {@link #cardUpdateNum()}, {@link #cardUpdateCvvCd()} and every
         * other accessor still answer the stored value verbatim, and {@link #encode(java.nio.charset.Charset)}
         * still emits the exact 150 bytes {@code CARD-UPDATE-RECORD} occupies, so nothing about the
         * migrated program's behaviour changes - COBOL has no {@code toString}.
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


    // =================================================================================================
    // Nested type 6 of 6 - CommArea: the whole of 01 WS-THIS-PROGCOMMAREA, 329 bytes.
    // =================================================================================================

    /**
     * {@code 01 WS-THIS-PROGCOMMAREA}, {@code app/cbl/COCRDUPC.cbl:274-321} - the program's own
     * commarea, {@value #RECORD_LENGTH} bytes, carried in the payload because CICS is
     * pseudo-conversational and this translation keeps no server-side state.
     *
     * <p>It decomposes as {@code 1 + 89 + 89 + 150}:
     *
     * <ol>
     *   <li>{@code 05 CARD-UPDATE-SCREEN-DATA} - one byte, {@link ChangeAction}, at offset
     *       {@value #CHANGE_ACTION_OFFSET}.</li>
     *   <li>{@code 05 CCUP-OLD-DETAILS} - {@value CardDetails#RECORD_LENGTH} bytes at offset
     *       {@value #OLD_DETAILS_OFFSET}: what the screen was painted from.</li>
     *   <li>{@code 05 CCUP-NEW-DETAILS} - {@value CardDetails#RECORD_LENGTH} bytes at offset
     *       {@value #NEW_DETAILS_OFFSET}: what the user typed.</li>
     *   <li>{@code 05 CARD-UPDATE-RECORD} - {@value CardUpdateRecord#RECORD_LENGTH} bytes at offset
     *       {@value #CARD_UPDATE_RECORD_OFFSET}: the image staged for rewrite.</li>
     * </ol>
     *
     * <p>{@link #LAYOUT} is assembled from the three sub-layouts with their offsets shifted to their
     * places in the commarea, and then the four {@code 05}-level group names are appended as
     * {@code REDEFINES} overlays so every name in the source has a descriptor. Because
     * {@link RecordLayout} verifies that its storage spans are contiguous from zero and sum to
     * exactly the declared length, the {@code 1 + 89 + 89 + 150} arithmetic is checked at class
     * initialisation rather than merely asserted in a comment - and the trailing
     * {@value CardUpdateRecord#FILLER_LENGTH}-byte {@code FILLER} of the record cannot be dropped
     * without the layout refusing to exist.
     *
     * @param changeAction     {@code CCUP-CHANGE-ACTION} and its nine {@code 88}-levels
     * @param oldDetails       {@code CCUP-OLD-DETAILS}; its group must be {@link DetailGroup#OLD}
     * @param newDetails       {@code CCUP-NEW-DETAILS}; its group must be {@link DetailGroup#NEW}
     * @param cardUpdateRecord {@code CARD-UPDATE-RECORD}
     */
    public record CommArea(ChangeAction changeAction,
                           CardDetails oldDetails,
                           CardDetails newDetails,
                           CardUpdateRecord cardUpdateRecord) {

        /**
         * Total width of the commarea:
         * {@code 1 + 89 + 89 + 150}.
         */
        public static final int RECORD_LENGTH = 329;

        /** Absolute 0-based offset of {@code CARD-UPDATE-SCREEN-DATA} and its single byte. */
        public static final int CHANGE_ACTION_OFFSET = 0;

        /** Absolute 0-based offset of {@code CCUP-OLD-DETAILS}: {@code 0 + 1}. */
        public static final int OLD_DETAILS_OFFSET = 1;

        /** Absolute 0-based offset of {@code CCUP-NEW-DETAILS}: {@code 1 + 89}. */
        public static final int NEW_DETAILS_OFFSET = 90;

        /** Absolute 0-based offset of {@code CARD-UPDATE-RECORD}: {@code 90 + 89}. */
        public static final int CARD_UPDATE_RECORD_OFFSET = 179;

        /** Verbatim COBOL name of the {@code 05}-level group holding the change-action byte. */
        public static final String SCREEN_DATA_GROUP_NAME = "CARD-UPDATE-SCREEN-DATA";

        /** Verbatim COBOL name of the {@code 05}-level record group. */
        public static final String CARD_UPDATE_RECORD_GROUP_NAME = "CARD-UPDATE-RECORD";

        /** {@code CCUP-CHANGE-ACTION PIC X(1)} at offset {@value #CHANGE_ACTION_OFFSET}. */
        public static final FieldSpan CCUP_CHANGE_ACTION = FieldSpan.alphanumeric(
                ChangeAction.FIELD_NAME, CHANGE_ACTION_OFFSET, ChangeAction.RECORD_LENGTH);

        /**
         * The commarea's layout: the change-action byte, then the two snapshots and the record with
         * their offsets shifted into place, then the four {@code 05}-level group overlays.
         */
        public static final RecordLayout LAYOUT = buildLayout();

        /**
         * Checks that both snapshots are the groups they are supposed to be, and that nothing is
         * {@code null}.
         *
         * <p>The group check matters because {@link CardDetails} carries its own name set: an
         * {@code OLD} snapshot stored in the {@code NEW} position would serialise to the right bytes
         * but present the wrong item names to a field-by-field diff, which is precisely the class of
         * defect this translation exists to avoid.
         *
         * @throws NullPointerException     if any component is {@code null}
         * @throws IllegalArgumentException if a snapshot is in the wrong position
         */
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
         * <p>This is the state in which {@code CCUP-DETAILS-NOT-FETCHED} is true, which is the first
         * {@code WHEN} of {@code 2000-DECIDE-ACTION}.
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
         * @throws NullPointerException     if either argument is {@code null}
         * @throws IllegalArgumentException if {@code image} is not {@value #RECORD_LENGTH} bytes, or a
         *                                  numeric span of the record holds a non-digit
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
         * @param image   exactly {@value #RECORD_LENGTH} bytes
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
         * Builds the record area by writing the change-action byte and then placing each sub-image at
         * its own offset.
         *
         * <p>Composing from the sub-images rather than field by field is deliberate: each sub-type
         * already renders itself to exactly its declared width through the codec, so the commarea
         * cannot disagree with its own parts about where a field starts.
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
         * {@code MOVE … TO CCUP-OLD-DETAILS}, which is what
         * {@code 9300-CHECK-CHANGE-IN-REC} does field by field when it detects that the stored record
         * changed under the screen.
         *
         * @param replacement the replacement snapshot; must be the {@link DetailGroup#OLD} group
         * @return a copy carrying it
         * @throws NullPointerException     if {@code replacement} is {@code null}
         * @throws IllegalArgumentException if it is not the {@link DetailGroup#OLD} snapshot
         */
        public CommArea withOldDetails(CardDetails replacement) {
            return new CommArea(changeAction, replacement, newDetails, cardUpdateRecord);
        }

        /**
         * {@code MOVE … TO CCUP-NEW-DETAILS}, which is what the receive-map paragraphs do with what
         * the user typed.
         *
         * @param replacement the replacement snapshot; must be the {@link DetailGroup#NEW} group
         * @return a copy carrying it
         * @throws NullPointerException     if {@code replacement} is {@code null}
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

        /**
         * Assembles {@link #LAYOUT}: the change-action span, the three sub-layouts shifted to their
         * commarea offsets, and the four {@code 05}-level group overlays appended last.
         *
         * <p>The overlays are appended last on purpose. {@link RecordLayout} requires a
         * {@code REDEFINES} overlay to fall entirely inside storage already declared ahead of it, so
         * an overlay covering the whole commarea can only be declared once the whole commarea has
         * been.
         *
         * @return the layout, whose storage spans sum to exactly {@value #RECORD_LENGTH} bytes
         */
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

        /**
         * Copies a sub-layout's spans with every offset moved by {@code base}, preserving each span's
         * name, width, picture category, declared {@code VALUE} and {@code REDEFINES} flag - so a
         * {@code FILLER} stays a {@code FILLER} and an overlay stays an overlay.
         *
         * @param layout the sub-layout whose spans are 0-based within their own group
         * @param base   the commarea offset at which that group begins
         * @return the shifted spans, in the sub-layout's declaration order
         */
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

    /**
     * How much of one symbolic-map field a diagnostic rendering may disclose.
     *
     * <p>Named per field, because {@code app/cpy-bms/COCRDUP.CPY} is a closed set. The card number is
     * masked, the account identifier is masked and the embossed name reports only its length; the status
     * code, expiry month and year and the screen furniture render as stored, which is what a
     * card-update validation parity failure is read from.
     *
     * <p>Package-private rather than private so that the safe default - an unnamed field is withheld
     * rather than published - is asserted directly by test. A field name reaching here is never
     * {@code null} in practice, because the map is built from this class's own constants, and an
     * unprovable guard on a disclosure decision is worth less than a proven one.
     *
     * @param fieldName the symbolic-map item name, as the copybook spells it
     * @return its classification, never {@code null}
     */
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
