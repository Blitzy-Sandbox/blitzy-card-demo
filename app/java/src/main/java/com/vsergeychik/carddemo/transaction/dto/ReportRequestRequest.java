package com.vsergeychik.carddemo.transaction.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The inbound REST payload of {@code POST /api/reports} - CSD transaction {@value #TRANSACTION_ID},
 * program {@value #PROGRAM_NAME} - projected field for field from the {@code xxxI} items of
 * {@code 01 CORPT0AI} in {@code app/cpy-bms/CORPT00.CPY} and from the name-labelled {@code DFHMDF}
 * definitions of {@code app/bms/CORPT00.bms}.
 *
 * <p>This is a like-for-like migration of the transaction-report request screen. Nothing here is
 * added, removed, renamed, reordered, improved or corrected relative to those two sources.
 *
 * <h2>Provenance</h2>
 *
 * <table border="1">
 *   <caption>Where every part of this type comes from</caption>
 *   <tr><th>Artefact</th><th>Supplies</th></tr>
 *   <tr><td>{@code app/cpy-bms/CORPT00.CPY} lines 17-120</td>
 *       <td>{@code 01 CORPT0AI}: the seventeen {@code xxxI} payload items and their
 *           {@code PICTURE} clauses, the seventeen {@code xxxL} / {@code xxxF} / {@code xxxA}
 *           metadata items, and every {@code FILLER} span</td></tr>
 *   <tr><td>{@code app/cpy-bms/CORPT00.CPY} lines 121-224</td>
 *       <td>{@code 01 CORPT0AO REDEFINES CORPT0AI}: proof that {@code xxxI} and {@code xxxO} are
 *           storage aliases - see the aliasing note below</td></tr>
 *   <tr><td>{@code app/bms/CORPT00.bms}</td>
 *       <td>The mapset {@value #MAPSET_NAME} and map {@value #MAP_NAME}, and each field's
 *           {@code ATTRB}, {@code COLOR}, {@code LENGTH}, {@code POS} and {@code INITIAL}</td></tr>
 *   <tr><td>{@code app/cbl/CORPT00C.cbl}</td>
 *       <td>How the program reads and writes those items: the numeric-typing evidence, the
 *           twenty-two cursor moves, and the absence of a communication-area extension</td></tr>
 *   <tr><td>{@code app/cpy/COCOM01Y.cpy}</td>
 *       <td>{@code 01 CARDDEMO-COMMAREA}, modelled by {@link NavigationContext}</td></tr>
 *   <tr><td>{@code app/csd/CARDDEMO.CSD} lines 242 and 409-410</td>
 *       <td>{@code DEFINE PROGRAM(CORPT00C)} and
 *           {@code DEFINE TRANSACTION(CR00) PROGRAM(CORPT00C)}</td></tr>
 * </table>
 *
 * <h2>The symbolic map, and why a field costs seven bytes before its data</h2>
 *
 * {@code app/bms/CORPT00.bms} declares {@code TIOAPFX=YES}, so the generated symbolic map opens
 * with a twelve-byte prefix and then repeats one four-item pattern per screen field. The copybook
 * writes the pattern at level {@code 02}, with only the attribute view nested at level {@code 03}:
 *
 * <pre>{@code
 *  01  CORPT0AI.
 *      02  FILLER PIC X(12).            <- the TIOAPFX prefix, once
 *      02  xxxL    COMP  PIC  S9(4).    <- METADATA: the length item, 2 bytes (COMP halfword)
 *      02  xxxF    PICTURE X.           <- METADATA: the flag byte, 1 byte
 *      02  FILLER REDEFINES xxxF.
 *        03 xxxA   PICTURE X.           <- METADATA: the attribute view of that same byte
 *      02  FILLER   PICTURE X(4).       <- reserved span, 4 bytes
 *      02  xxxI  PIC X(n).              <- PAYLOAD                       stride = 7 + n
 * }</pre>
 *
 * {@code xxxL} is {@code COMP}, so it is a two-byte binary halfword rather than four bytes of
 * zoned digits, and {@code xxxA} redefines {@code xxxF} rather than following it. Those two facts
 * are what make the per-field prefix {@code 2 + 1 + 4 = 7} bytes and not eleven, and the total
 * therefore {@value #SYMBOLIC_MAP_LENGTH} rather than a larger number.
 *
 * <h2>The seventeen fields, verbatim from the two sources</h2>
 *
 * {@code app/bms/CORPT00.bms} contains forty-two {@code DFHMDF} entries, of which seventeen carry a
 * name label. Only the labelled ones become payload members; the twenty-five unlabelled literal
 * fields - {@code 'Tran:'}, {@code 'Transaction Reports'}, {@code '(MM/DD/YYYY)'} and the rest -
 * are screen furniture with no symbolic-map item and no place in a payload. Every BMS
 * {@code LENGTH=} equals its symbolic-map {@code PIC X(n)}, checked field by field.
 *
 * <table border="1">
 *   <caption>Declaration order, copybook items, width and screen attributes</caption>
 *   <tr><th>#</th><th>{@code DFHMDF}</th><th>{@code xxxI} item</th><th>{@code PIC}</th>
 *       <th>{@code ATTRB}</th><th>{@code COLOR}</th><th>{@code POS}</th><th>{@code INITIAL}</th></tr>
 *   <tr><td>1</td><td>{@code TRNNAME}</td><td>{@code TRNNAMEI}</td><td>{@code X(4)}</td>
 *       <td>ASKIP,FSET,NORM</td><td>BLUE</td><td>(1,7)</td><td>-</td></tr>
 *   <tr><td>2</td><td>{@code TITLE01}</td><td>{@code TITLE01I}</td><td>{@code X(40)}</td>
 *       <td>ASKIP,FSET,NORM</td><td>YELLOW</td><td>(1,21)</td><td>-</td></tr>
 *   <tr><td>3</td><td>{@code CURDATE}</td><td>{@code CURDATEI}</td><td>{@code X(8)}</td>
 *       <td>ASKIP,FSET,NORM</td><td>BLUE</td><td>(1,71)</td><td>{@code 'mm/dd/yy'}</td></tr>
 *   <tr><td>4</td><td>{@code PGMNAME}</td><td>{@code PGMNAMEI}</td><td>{@code X(8)}</td>
 *       <td>ASKIP,FSET,NORM</td><td>BLUE</td><td>(2,7)</td><td>-</td></tr>
 *   <tr><td>5</td><td>{@code TITLE02}</td><td>{@code TITLE02I}</td><td>{@code X(40)}</td>
 *       <td>ASKIP,FSET,NORM</td><td>YELLOW</td><td>(2,21)</td><td>-</td></tr>
 *   <tr><td>6</td><td>{@code CURTIME}</td><td>{@code CURTIMEI}</td><td>{@code X(8)}</td>
 *       <td>ASKIP,FSET,NORM</td><td>BLUE</td><td>(2,71)</td><td>{@code 'hh:mm:ss'}</td></tr>
 *   <tr><td>7</td><td>{@code MONTHLY}</td><td>{@code MONTHLYI}</td><td>{@code X(1)}</td>
 *       <td>FSET,IC,NORM,UNPROT</td><td>GREEN, HILIGHT=UNDERLINE</td><td>(7,10)</td>
 *       <td>{@code ' '}</td></tr>
 *   <tr><td>8</td><td>{@code YEARLY}</td><td>{@code YEARLYI}</td><td>{@code X(1)}</td>
 *       <td>FSET,NORM,UNPROT</td><td>GREEN, HILIGHT=UNDERLINE</td><td>(9,10)</td>
 *       <td>{@code ' '}</td></tr>
 *   <tr><td>9</td><td>{@code CUSTOM}</td><td>{@code CUSTOMI}</td><td>{@code X(1)}</td>
 *       <td>FSET,NORM,UNPROT</td><td>GREEN, HILIGHT=UNDERLINE</td><td>(11,10)</td>
 *       <td>{@code ' '}</td></tr>
 *   <tr><td>10</td><td>{@code SDTMM}</td><td>{@code SDTMMI}</td><td>{@code X(2)}</td>
 *       <td>FSET,NORM,NUM,UNPROT</td><td>GREEN, HILIGHT=UNDERLINE</td><td>(13,29)</td>
 *       <td>{@code '  '}</td></tr>
 *   <tr><td>11</td><td>{@code SDTDD}</td><td>{@code SDTDDI}</td><td>{@code X(2)}</td>
 *       <td>FSET,NORM,NUM,UNPROT</td><td>GREEN, HILIGHT=UNDERLINE</td><td>(13,34)</td>
 *       <td>{@code '  '}</td></tr>
 *   <tr><td>12</td><td>{@code SDTYYYY}</td><td>{@code SDTYYYYI}</td><td>{@code X(4)}</td>
 *       <td>FSET,NORM,NUM,UNPROT</td><td>GREEN, HILIGHT=UNDERLINE</td><td>(13,39)</td>
 *       <td>{@code '    '}</td></tr>
 *   <tr><td>13</td><td>{@code EDTMM}</td><td>{@code EDTMMI}</td><td>{@code X(2)}</td>
 *       <td>FSET,NORM,NUM,UNPROT</td><td>GREEN, HILIGHT=UNDERLINE</td><td>(14,29)</td>
 *       <td>{@code '  '}</td></tr>
 *   <tr><td>14</td><td>{@code EDTDD}</td><td>{@code EDTDDI}</td><td>{@code X(2)}</td>
 *       <td>FSET,NORM,NUM,UNPROT</td><td>GREEN, HILIGHT=UNDERLINE</td><td>(14,34)</td>
 *       <td>{@code '  '}</td></tr>
 *   <tr><td>15</td><td>{@code EDTYYYY}</td><td>{@code EDTYYYYI}</td><td>{@code X(4)}</td>
 *       <td>FSET,NORM,NUM,UNPROT</td><td>GREEN, HILIGHT=UNDERLINE</td><td>(14,39)</td>
 *       <td>{@code '    '}</td></tr>
 *   <tr><td>16</td><td>{@code CONFIRM}</td><td>{@code CONFIRMI}</td><td>{@code X(1)}</td>
 *       <td>FSET,NORM,UNPROT</td><td>GREEN, HILIGHT=UNDERLINE</td><td>(19,66)</td><td>-</td></tr>
 *   <tr><td>17</td><td>{@code ERRMSG}</td><td>{@code ERRMSGI}</td><td>{@code X(78)}</td>
 *       <td>ASKIP,BRT,FSET</td><td>RED</td><td>(23,1)</td><td>-</td></tr>
 *   <tr><td></td><td colspan="2"><strong>Sum of the seventeen widths</strong></td>
 *       <td colspan="5"><strong>{@value #PAYLOAD_WIDTH_TOTAL}</strong></td></tr>
 * </table>
 *
 * {@value #TIOAPFX_PREFIX_LENGTH} + {@value #FIELD_COUNT} times {@value #FIELD_PREFIX_LENGTH} +
 * {@value #PAYLOAD_WIDTH_TOTAL} = <strong>{@value #SYMBOLIC_MAP_LENGTH}</strong>. That total is not
 * merely asserted in prose. {@link #SYMBOLIC_MAP_LENGTH} is <em>computed</em> from the declared
 * widths rather than written as a literal, and {@link #LAYOUT} hands it to
 * {@link RecordLayout}, whose constructor self-check refuses to build a layout whose storage spans
 * are not contiguous from offset zero and do not sum to exactly that. A mistyped width or offset
 * therefore fails at class initialisation, naming the offending span, instead of silently shifting
 * every field after it.
 *
 * <h2>Why the seven {@code ASKIP} fields belong in a <em>request</em></h2>
 *
 * {@code TRNNAME}, {@code TITLE01}, {@code CURDATE}, {@code PGMNAME}, {@code TITLE02},
 * {@code CURTIME} and {@code ERRMSG} are {@code ASKIP} - the operator cannot type into them - yet
 * every one carries {@code FSET}. {@code FSET} presets the modified-data tag, so CICS returns the
 * field in the inbound datastream on the next {@code RECEIVE MAP} whether or not the operator
 * touched it. That is precisely why the symbolic map declares an {@code xxxI} item for all
 * seventeen fields and not only for the ten unprotected ones, and it is why all seventeen are
 * modelled here. Dropping a field for looking output-oriented would lose bytes the screen really
 * does send back.
 *
 * <h2>{@code xxxI} and {@code xxxO} are the same bytes</h2>
 *
 * {@code 01 CORPT0AO REDEFINES CORPT0AI} at line 121 spends exactly seven prefix bytes per field
 * too - {@code FILLER X(3)} plus {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV}, one
 * byte each - so each field's {@code I} item and {@code O} item begin at the <em>identical</em>
 * offset. They are storage aliases, not distinct fields, and {@code CORPT00C} writes through both
 * views: seventy-three references qualify {@code OF CORPT0AI} and nine qualify
 * {@code OF CORPT0AO}, including {@code MOVE SPACES TO ERRMSGO OF CORPT0AO} at line 170 and
 * {@code MOVE WS-MESSAGE TO ERRMSGO OF CORPT0AO} at line 560.
 *
 * <p>Two consequences follow, and both are honoured here. First, this request and its
 * {@code ReportRequestResponse} sibling carry the <strong>same seventeen field names at the same
 * widths</strong>: the request-versus-response split is a directional projection convention over
 * one COBOL buffer, not two different buffers. Second, an {@code xxxI} item is <strong>not
 * read-only</strong>. {@code CORPT00C} assigns into six of them at lines 307, 311, 315, 319, 323
 * and 327, so this type provides a wither for every field and
 * {@link #toSymbolicMap(FixedWidthCodec)} round-trips losslessly through
 * {@link #fromSymbolicMap(FixedWidthCodec, byte[])}.
 *
 * <h2>Every member is a {@code String}: there is no {@code BigDecimal}, {@code int},
 * {@code double} or {@code float} in this file</h2>
 *
 * The six date parts look numeric and are not. {@code CORPT00C} normalises each one
 * <em>back into its own alphanumeric item</em> - at lines 305-307, 309-311, 313-315, 317-319,
 * 321-323 and 325-327 it computes {@code FUNCTION NUMVAL-C} of the item into {@code WS-NUM-99}
 * ({@code PIC 99}) or {@code WS-NUM-9999} ({@code PIC 9999}) and immediately moves the result
 * back:
 *
 * <pre>{@code
 *  COMPUTE WS-NUM-99 = FUNCTION NUMVAL-C
 *                        (SDTMMI OF CORPT0AI)
 *  MOVE WS-NUM-99      TO SDTMMI OF CORPT0AI
 * }</pre>
 *
 * and then applies an <em>alphanumeric</em> class test and a <em>string</em> comparison at lines
 * 329-330: {@code IF SDTMMI OF CORPT0AI IS NOT NUMERIC OR SDTMMI OF CORPT0AI &gt; '12'}. Both
 * tests depend on the item being {@code PIC X}. A field of spaces fails {@code IS NUMERIC} and is
 * rejected with {@code 'Start Date - Not a valid Month...'}; converting the member to {@code int}
 * would make that state unrepresentable, and comparing {@code '13'} as a number rather than as
 * characters would change which rejection message the operator sees. The day fields are compared
 * against {@code '31'} at lines 339 and 365 in the same way, and the two year fields are class
 * tested only, at lines 347 and 373.
 *
 * <p>So the members stay {@code PIC X(2)} and {@code PIC X(4)} strings, and gates G22, G23 and
 * G24 are satisfied trivially: this type performs no arithmetic and declares no floating-point or
 * fixed-point member at all. {@code FUNCTION NUMVAL-C} parsing,
 * {@code FUNCTION DATE-OF-INTEGER} / {@code INTEGER-OF-DATE} (line 229) and the two
 * {@code CALL 'CSUTLDTC'} sites (lines 392 and 412) belong to the controller and to the date
 * utility service, never to this payload.
 *
 * <h2>Statelessness: the conversation travels in the payload</h2>
 *
 * CICS is pseudo-conversational, and this migration preserves that shape exactly rather than
 * reintroducing a server-side conversation. The conversation is exactly three things, and all three
 * are payload members here:
 *
 * <ul>
 *   <li>the communication area, as {@link #navigationContext()}, carrying the enter-versus-re-enter
 *       context and the identifiers the previous screen passed on;</li>
 *   <li>the key the operator pressed, as {@link #aid()} - the {@code EIBAID} that line 183
 *       evaluates, without which two of this program's three arms could not be selected at all;</li>
 *   <li>the seventeen screen field values themselves.</li>
 * </ul>
 *
 * <p>The first two are the only members that are not {@code DFHMDF} fields, and they are the two
 * mandated exceptions to that rule. This type is deliberately free of {@code HttpSession},
 * {@code @SessionAttributes}, {@code @SessionScope}, {@code ThreadLocal}, any server-side cache and
 * any static mutable holder - a static holder would be a session by another name and would
 * additionally break request isolation.
 *
 * <p>{@code CORPT00C} has <strong>no</strong> communication-area extension. Line 138 is
 * {@code COPY COCOM01Y.} and line 140 goes straight to {@code COPY CORPT00.}; there is no
 * {@code 05 CDEMO-CR00-INFO} group of the kind the {@code COTRN} programs add. Its passed
 * communication area is therefore exactly {@value NavigationContext#COMMAREA_LENGTH} bytes and no
 * pagination cursor is invented here. {@code CORPT00C} likewise does not
 * {@code COPY CVCRD01Y}, so the card screen-state carrier is deliberately not referenced.
 *
 * <h2>Metadata is modelled, and is not payload</h2>
 *
 * {@code xxxL}, {@code xxxF} and {@code xxxA} are validation and highlight metadata rather than
 * payload. They are modelled by {@link FieldMetadata} and {@link SymbolicMapMetadata}, which are
 * <em>not</em> components of this record and therefore cannot appear on the wire at all - a
 * stronger and simpler exclusion than annotating them away, and one that keeps the JSON round trip
 * provably lossless. {@link #toSymbolicMap(FixedWidthCodec, SymbolicMapMetadata)} accepts them so
 * the full {@value #SYMBOLIC_MAP_LENGTH}-byte image can still be produced.
 *
 * <h2>Two wire formats</h2>
 *
 * <ul>
 *   <li><strong>JSON</strong> is the REST format. A record's components are its JSON properties, so
 *       the payload is exactly the seventeen screen fields plus the communication area, and no
 *       custom serialiser, mix-in or converter is needed. Nothing is trimmed, so a
 *       {@value #ERRMSG_LENGTH}-character run of spaces in {@link #errmsg()} survives a
 *       serialise-then-deserialise round trip byte for byte. Naming strategy, inclusion policy and
 *       mapper configuration belong to the module's web configuration and are deliberately not
 *       restated here.</li>
 *   <li><strong>The {@value #SYMBOLIC_MAP_LENGTH}-byte fixed-width image</strong> of
 *       {@code 01 CORPT0AI}, produced by
 *       {@link #toSymbolicMap(FixedWidthCodec, SymbolicMapMetadata)} and read back by
 *       {@link #fromSymbolicMap(FixedWidthCodec, byte[])}. Every pad and every truncation in it is
 *       performed by {@link FixedWidthCodec} and by nothing else, so the direction of a truncation
 *       is reviewable rather than accidental, and the code page is always stated explicitly rather
 *       than defaulted to the platform's.</li>
 * </ul>
 *
 * @param trnname   {@code TRNNAMEI PIC X(4)}: the transaction identifier shown at (1,7)
 * @param title01   {@code TITLE01I PIC X(40)}: the first title line at (1,21)
 * @param curdate   {@code CURDATEI PIC X(8)}: the current date at (1,71), {@code mm/dd/yy}
 * @param pgmname   {@code PGMNAMEI PIC X(8)}: the program name shown at (2,7)
 * @param title02   {@code TITLE02I PIC X(40)}: the second title line at (2,21)
 * @param curtime   {@code CURTIMEI PIC X(8)}: the current time at (2,71), {@code hh:mm:ss}
 * @param monthly   {@code MONTHLYI PIC X(1)}: select the current-month report; tested against
 *                  {@code SPACES AND LOW-VALUES} at line 213
 * @param yearly    {@code YEARLYI PIC X(1)}: select the current-year report; tested at line 239
 * @param custom    {@code CUSTOMI PIC X(1)}: select the custom date-range report
 * @param sdtmm     {@code SDTMMI PIC X(2)}: start-date month, compared against {@code '12'}
 * @param sdtdd     {@code SDTDDI PIC X(2)}: start-date day, compared against {@code '31'}
 * @param sdtyyyy   {@code SDTYYYYI PIC X(4)}: start-date year, class tested only
 * @param edtmm     {@code EDTMMI PIC X(2)}: end-date month, compared against {@code '12'}
 * @param edtdd     {@code EDTDDI PIC X(2)}: end-date day, compared against {@code '31'}
 * @param edtyyyy   {@code EDTYYYYI PIC X(4)}: end-date year, class tested only
 * @param confirm   {@code CONFIRMI PIC X(1)}: {@code 'Y'}, {@code 'y'}, {@code 'N'} or
 *                  {@code 'n'}; anything else is rejected at line 484
 * @param errmsg    {@code ERRMSGI PIC X(78)}: the error line at (23,1), returned because the field
 *                  carries {@code FSET}
 * @param navigationContext {@code 01 CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy}, exactly
 *                  {@value NavigationContext#COMMAREA_LENGTH} bytes, or {@code null} when no
 *                  communication area was passed - the {@code EIBCALEN = 0} cold start of line 172
 * @param aid       the resolved {@code EIBAID} key indication as a token, at most
 *                  {@value #AID_LENGTH} characters - {@code 'ENTER'} or {@code 'PFK03'} for the two
 *                  arms this screen acts on, anything else being the {@code WHEN OTHER} arm.
 *                  <strong>Not a map field</strong>: the second of the two mandated exceptions, and
 *                  absent from the symbolic-map image. {@code null} becomes spaces, which is the
 *                  no-key-resolved state
 */
public record ReportRequestRequest(
        @Size(max = TRNNAME_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String trnname,
        @Size(max = TITLE01_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String title01,
        @Size(max = CURDATE_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String curdate,
        @Size(max = PGMNAME_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String pgmname,
        @Size(max = TITLE02_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String title02,
        @Size(max = CURTIME_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String curtime,
        @Size(max = MONTHLY_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String monthly,
        @Size(max = YEARLY_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String yearly,
        @Size(max = CUSTOM_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String custom,
        @Size(max = SDTMM_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String sdtmm,
        @Size(max = SDTDD_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String sdtdd,
        @Size(max = SDTYYYY_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String sdtyyyy,
        @Size(max = EDTMM_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String edtmm,
        @Size(max = EDTDD_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String edtdd,
        @Size(max = EDTYYYY_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String edtyyyy,
        @Size(max = CONFIRM_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String confirm,
        @Size(max = ERRMSG_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String errmsg,
        NavigationContext navigationContext,
        @Size(max = AID_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String aid) {

    /**
     * The message every width constraint above declares, and the only text a rejected field publishes.
     *
     * <p>{@code {max}} is the constraint's own declared bound, interpolated by the validator, so the
     * sentence states the width without restating the number - and the width of a field a caller sends
     * is already part of the published contract, so naming it discloses nothing.
     *
     * <p><strong>What it deliberately does not say.</strong> These messages used to name the
     * symbolic-map item and its {@code PICTURE} clause - {@code "SDTYYYYI is declared PIC X(4)"} - and
     * the estate's other request DTOs went further and named the copybook path and line the width was
     * read from. That is provenance written for the engineer maintaining the field, and its place is
     * the Javadoc above, where it remains in full. It is not text to hand an unauthenticated caller
     * one rejected field at a time: it publishes the module's copybook inventory, its line numbers and
     * its internal naming conventions, which together describe the shape of the estate behind the API.
     *
     * <p>{@code config/WebConfig}'s error advice does not forward a validator message at all - it maps
     * the constraint's code and bound onto its own fixed sentence, so nothing declared here can reach a
     * caller by accident. This constant matches that sentence exactly, so the two agree if a future
     * consumer of Bean Validation does surface a message directly.
     */
    public static final String PUBLIC_LENGTH_MESSAGE = "must be at most {max} characters";

    // =================================================================================================
    // Identity of the screen this payload projects. Every literal is verbatim: the mapset and map
    // names from app/bms/CORPT00.bms lines 19 and 26, the symbolic-map group name from
    // app/cpy-bms/CORPT00.CPY line 17, and the transaction and program names from
    // app/csd/CARDDEMO.CSD lines 409-410 and 242, which agree with WS-TRANID and WS-PGMNAME at
    // app/cbl/CORPT00C.cbl lines 38 and 37.
    // =================================================================================================

    /** The BMS mapset: {@code CORPT00 DFHMSD} at {@code app/bms/CORPT00.bms:19}. */
    public static final String MAPSET_NAME = "CORPT00";

    /**
     * The BMS map: {@code CORPT0A DFHMDI} at {@code app/bms/CORPT00.bms:26}. Seven characters, which
     * is exactly what {@code CDEMO-LAST-MAP PIC X(7)} holds - the eighth character of a symbolic-map
     * group name is the direction suffix, not part of the map name.
     */
    public static final String MAP_NAME = "CORPT0A";

    /** The symbolic-map input group: {@code 01 CORPT0AI} at {@code app/cpy-bms/CORPT00.CPY:17}. */
    public static final String SYMBOLIC_MAP_INPUT_GROUP = "CORPT0AI";

    /**
     * The CSD transaction that reaches {@value #PROGRAM_NAME}:
     * {@code DEFINE TRANSACTION(CR00) PROGRAM(CORPT00C)} at {@code app/csd/CARDDEMO.CSD:409-410},
     * matching {@code WS-TRANID PIC X(04) VALUE 'CR00'} at {@code app/cbl/CORPT00C.cbl:38}.
     */
    public static final String TRANSACTION_ID = "CR00";

    /**
     * The COBOL program this payload is projected from: {@code PROGRAM-ID. CORPT00C} at
     * {@code app/cbl/CORPT00C.cbl:24}, matching {@code WS-PGMNAME PIC X(08) VALUE 'CORPT00C'} at
     * line 37.
     */
    public static final String PROGRAM_NAME = "CORPT00C";

    // =================================================================================================
    // Symbolic-map geometry. Each constant states one declared width from app/cpy-bms/CORPT00.CPY;
    // nothing below is a guess and nothing is a round number chosen for convenience.
    // =================================================================================================

    /** Screen fields carrying a {@code DFHMDF} name label, and therefore payload members: 17. */
    public static final int FIELD_COUNT = 17;

    /**
     * The leading {@code 02 FILLER PIC X(12)} of {@code app/cpy-bms/CORPT00.CPY:18}. BMS generates
     * this prefix because the mapset declares {@code TIOAPFX=YES}; it belongs to the terminal
     * input/output area, carries no application data, and must still be emitted or every subsequent
     * offset is wrong by twelve.
     */
    public static final int TIOAPFX_PREFIX_LENGTH = 12;

    /**
     * {@code 02 xxxL COMP PIC S9(4)} occupies two bytes, not four. {@code COMP} is binary, so the
     * item is a signed halfword; a zoned {@code DISPLAY PIC S9(4)} would have been four bytes and
     * would have made the per-field prefix nine rather than {@value #FIELD_PREFIX_LENGTH}.
     */
    public static final int LENGTH_ITEM_LENGTH = 2;

    /** {@code 02 xxxF PICTURE X} - one byte, redefined by {@code 03 xxxA PICTURE X}. */
    public static final int FLAG_ITEM_LENGTH = 1;

    /** {@code 02 FILLER PICTURE X(4)} - the reserved span between the flag byte and the data. */
    public static final int RESERVED_FILLER_LENGTH = 4;

    /**
     * Bytes every field spends before its data: {@value #LENGTH_ITEM_LENGTH} +
     * {@value #FLAG_ITEM_LENGTH} + {@value #RESERVED_FILLER_LENGTH} =
     * {@value #FIELD_PREFIX_LENGTH}. The attribute view contributes nothing because it
     * {@code REDEFINES} the flag byte rather than following it.
     */
    public static final int FIELD_PREFIX_LENGTH =
            LENGTH_ITEM_LENGTH + FLAG_ITEM_LENGTH + RESERVED_FILLER_LENGTH;

    /** {@code TRNNAMEI PIC X(4)}; {@code TRNNAME DFHMDF LENGTH=4} at {@code CORPT00.bms:34-37}. */
    public static final int TRNNAME_LENGTH = 4;

    /** {@code TITLE01I PIC X(40)}; {@code TITLE01 DFHMDF LENGTH=40} at {@code CORPT00.bms:38-41}. */
    public static final int TITLE01_LENGTH = 40;

    /** {@code CURDATEI PIC X(8)}; {@code CURDATE DFHMDF LENGTH=8} at {@code CORPT00.bms:47-51}. */
    public static final int CURDATE_LENGTH = 8;

    /** {@code PGMNAMEI PIC X(8)}; {@code PGMNAME DFHMDF LENGTH=8} at {@code CORPT00.bms:57-60}. */
    public static final int PGMNAME_LENGTH = 8;

    /** {@code TITLE02I PIC X(40)}; {@code TITLE02 DFHMDF LENGTH=40} at {@code CORPT00.bms:61-64}. */
    public static final int TITLE02_LENGTH = 40;

    /** {@code CURTIMEI PIC X(8)}; {@code CURTIME DFHMDF LENGTH=8} at {@code CORPT00.bms:70-74}. */
    public static final int CURTIME_LENGTH = 8;

    /** {@code MONTHLYI PIC X(1)}; {@code MONTHLY DFHMDF LENGTH=1} at {@code CORPT00.bms:80-85}. */
    public static final int MONTHLY_LENGTH = 1;

    /** {@code YEARLYI PIC X(1)}; {@code YEARLY DFHMDF LENGTH=1} at {@code CORPT00.bms:94-99}. */
    public static final int YEARLY_LENGTH = 1;

    /** {@code CUSTOMI PIC X(1)}; {@code CUSTOM DFHMDF LENGTH=1} at {@code CORPT00.bms:108-113}. */
    public static final int CUSTOM_LENGTH = 1;

    /** {@code SDTMMI PIC X(2)}; {@code SDTMM DFHMDF LENGTH=2} at {@code CORPT00.bms:127-132}. */
    public static final int SDTMM_LENGTH = 2;

    /** {@code SDTDDI PIC X(2)}; {@code SDTDD DFHMDF LENGTH=2} at {@code CORPT00.bms:138-143}. */
    public static final int SDTDD_LENGTH = 2;

    /** {@code SDTYYYYI PIC X(4)}; {@code SDTYYYY DFHMDF LENGTH=4} at {@code CORPT00.bms:149-154}. */
    public static final int SDTYYYY_LENGTH = 4;

    /** {@code EDTMMI PIC X(2)}; {@code EDTMM DFHMDF LENGTH=2} at {@code CORPT00.bms:166-171}. */
    public static final int EDTMM_LENGTH = 2;

    /** {@code EDTDDI PIC X(2)}; {@code EDTDD DFHMDF LENGTH=2} at {@code CORPT00.bms:177-182}. */
    public static final int EDTDD_LENGTH = 2;

    /** {@code EDTYYYYI PIC X(4)}; {@code EDTYYYY DFHMDF LENGTH=4} at {@code CORPT00.bms:188-193}. */
    public static final int EDTYYYY_LENGTH = 4;

    /** {@code CONFIRMI PIC X(1)}; {@code CONFIRM DFHMDF LENGTH=1} at {@code CORPT00.bms:206-210}. */
    public static final int CONFIRM_LENGTH = 1;

    /** {@code ERRMSGI PIC X(78)}; {@code ERRMSG DFHMDF LENGTH=78} at {@code CORPT00.bms:218-221}. */
    public static final int ERRMSG_LENGTH = 78;

    /**
     * Characters in the {@code EIBAID} token carried by {@link #aid()}: five.
     *
     * <p><strong>Not a screen field.</strong> It is absent from {@link ScreenField}, from
     * {@link #FIELD_COUNT}, from {@link #PAYLOAD_WIDTH_TOTAL} and from the
     * {@value #SYMBOLIC_MAP_LENGTH}-byte image, because {@code EIBAID} is not part of
     * {@code 01 CORPT0AI} at all - CICS reports it in the exec interface block, alongside the map
     * rather than inside it. It is one of the two mandated exceptions to the one-member-per-
     * {@code DFHMDF} rule, the other being {@link #navigationContext()}.
     *
     * <p>It has to be here because {@code app/cbl/CORPT00C.cbl:183-195} branches on it and on nothing
     * else: {@code EVALUATE EIBAID} with {@code WHEN DFHENTER} performing {@code PROCESS-ENTER-KEY},
     * {@code WHEN DFHPF3} moving {@code 'COMEN01C'} into {@code CDEMO-TO-PROGRAM} and returning to the
     * previous screen, and {@code WHEN OTHER} raising {@code CCDA-MSG-INVALID-KEY}. With no member for
     * the key there is no way for a caller to select any of the three, so two of this program's arms
     * would be unreachable through the API and the invalid-key message unprovokable. A server-side
     * record of the last key pressed is the one thing rule R6 forbids, so the key travels in the
     * payload.
     *
     * <p>The width is the module's convention rather than this program's copybook - {@code CORPT00C}
     * copies neither {@code CVCRD01Y} nor {@code CSSTRPFY} and tests the raw {@code EIBAID} byte
     * inline. Five characters matches {@code 10 CCARD-AID PIC X(5)} of {@code app/cpy/CVCRD01Y.cpy}
     * and the width {@code common.PfKeyResolver.AID_TOKEN_LENGTH} publishes. The two tokens this
     * screen acts on are {@code ENTER} and {@code PFK03}; anything else, this one included when it
     * arrives as spaces, is the {@code WHEN OTHER} arm. Note that
     * {@code common.PfKeyResolver.AidKey#token()} space-pads the shorter mnemonics to this width, so a
     * caller must not trim what it produces.
     */
    public static final int AID_LENGTH = 5;

    /** Name of the pseudo-conversational key indication, the CICS {@code EIBAID} field. */
    public static final String AID_FIELD = "EIBAID";

    /**
     * The seventeen declared widths added up: {@value #PAYLOAD_WIDTH_TOTAL}. Written as a sum of the
     * individual constants rather than as a literal, so the arithmetic is visible and a corrected
     * width cannot leave a stale total behind.
     */
    public static final int PAYLOAD_WIDTH_TOTAL = TRNNAME_LENGTH + TITLE01_LENGTH + CURDATE_LENGTH
            + PGMNAME_LENGTH + TITLE02_LENGTH + CURTIME_LENGTH + MONTHLY_LENGTH + YEARLY_LENGTH
            + CUSTOM_LENGTH + SDTMM_LENGTH + SDTDD_LENGTH + SDTYYYY_LENGTH + EDTMM_LENGTH
            + EDTDD_LENGTH + EDTYYYY_LENGTH + CONFIRM_LENGTH + ERRMSG_LENGTH;

    /**
     * The full width of {@code 01 CORPT0AI}: {@value #TIOAPFX_PREFIX_LENGTH} +
     * {@value #FIELD_COUNT} times {@value #FIELD_PREFIX_LENGTH} +
     * {@value #PAYLOAD_WIDTH_TOTAL} = <strong>{@value #SYMBOLIC_MAP_LENGTH}</strong> bytes.
     *
     * <p>Derived, never asserted by hand, and then checked a second and independent way by
     * {@link #LAYOUT}, whose span offsets must run contiguously from zero and end exactly here.
     */
    public static final int SYMBOLIC_MAP_LENGTH =
            TIOAPFX_PREFIX_LENGTH + FIELD_COUNT * FIELD_PREFIX_LENGTH + PAYLOAD_WIDTH_TOTAL;

    /**
     * The {@code 02 FILLER PIC X(12)} TIOAPFX prefix span at offset zero, emitted as spaces.
     * Declared explicitly because {@link RecordLayout} requires every byte of the record to be
     * accounted for, {@code FILLER} included.
     */
    public static final FieldSpan TIOAPFX_PREFIX_SPAN = FieldSpan.filler(0, TIOAPFX_PREFIX_LENGTH);

    // =================================================================================================
    // Private figurative constants. COBOL SPACES and LOW-VALUES, sized per field by ScreenField.
    // =================================================================================================

    /** The COBOL {@code SPACES} figurative constant, one character of it. */
    private static final String SPACE = " ";

    /**
     * The COBOL {@code LOW-VALUES} figurative constant, one character of it: {@code X'00'}.
     * {@code CORPT00C} sets the whole map to it at line 179 ({@code MOVE LOW-VALUES TO CORPT0AO})
     * and then tests fields against {@code SPACES OR LOW-VALUES} at lines 213, 239, 258 and 464, so
     * the two states are distinguishable in storage even though every test treats them alike.
     */
    private static final String LOW_VALUE = "\u0000";

    /** Suffix BMS appends to a field name to form the symbolic-map length item, as in {@code TRNNAMEL}. */
    private static final String LENGTH_ITEM_SUFFIX = "L";

    /** Suffix forming the flag item, as in {@code TRNNAMEF}. */
    private static final String FLAG_ITEM_SUFFIX = "F";

    /** Suffix forming the attribute view that redefines the flag item, as in {@code TRNNAMEA}. */
    private static final String ATTRIBUTE_ITEM_SUFFIX = "A";

    /** Suffix forming the input data item, as in {@code TRNNAMEI}. */
    private static final String INPUT_ITEM_SUFFIX = "I";

    /**
     * The seventeen name-labelled {@code DFHMDF} fields of {@value #MAPSET_NAME}, in symbolic-map
     * declaration order, each carrying its four copybook item names, its declared width, its
     * absolute offsets within the {@value #SYMBOLIC_MAP_LENGTH}-byte image and the four
     * {@link FieldSpan} descriptors that address it.
     *
     * <h2>The enum, not a loop counter, is what keeps the seventeen in step</h2>
     *
     * Ordinal position is declaration position, so {@link #ordinal()} indexes
     * {@link ReportRequestRequest#fieldValues()} directly and the request needs no seventeen-way
     * {@code switch} to read or replace a field by name. That matters for a like-for-like migration:
     * a {@code switch} would be seventeen independent chances to wire a field to the wrong member,
     * whereas one ordered enum cannot disagree with itself.
     *
     * <h2>Offsets are stated, then proved</h2>
     *
     * Each constant states the absolute offset of its {@code xxxL} item explicitly rather than
     * accumulating one from its predecessor. Accumulation would need a mutable counter, which is
     * forbidden here, and an explicit number can be diffed straight against the copybook. The
     * numbers are then <em>proved</em> rather than trusted:
     * {@link ReportRequestRequest#LAYOUT} feeds all eighty-six spans to {@link RecordLayout}, whose
     * self-check rejects any gap, any overlap and any total other than
     * {@value ReportRequestRequest#SYMBOLIC_MAP_LENGTH}. A mistyped offset therefore fails at class
     * initialisation naming the offending span.
     *
     * <table border="1">
     *   <caption>Absolute offsets, 0-based, within {@code 01 CORPT0AI}</caption>
     *   <tr><th>Field</th><th>{@code xxxL}</th><th>{@code xxxF} / {@code xxxA}</th>
     *       <th>reserved {@code FILLER}</th><th>{@code xxxI}</th><th>end</th></tr>
     *   <tr><td>{@code TRNNAME}</td><td>12</td><td>14</td><td>15</td><td>19</td><td>23</td></tr>
     *   <tr><td>{@code TITLE01}</td><td>23</td><td>25</td><td>26</td><td>30</td><td>70</td></tr>
     *   <tr><td>{@code CURDATE}</td><td>70</td><td>72</td><td>73</td><td>77</td><td>85</td></tr>
     *   <tr><td>{@code PGMNAME}</td><td>85</td><td>87</td><td>88</td><td>92</td><td>100</td></tr>
     *   <tr><td>{@code TITLE02}</td><td>100</td><td>102</td><td>103</td><td>107</td><td>147</td></tr>
     *   <tr><td>{@code CURTIME}</td><td>147</td><td>149</td><td>150</td><td>154</td><td>162</td></tr>
     *   <tr><td>{@code MONTHLY}</td><td>162</td><td>164</td><td>165</td><td>169</td><td>170</td></tr>
     *   <tr><td>{@code YEARLY}</td><td>170</td><td>172</td><td>173</td><td>177</td><td>178</td></tr>
     *   <tr><td>{@code CUSTOM}</td><td>178</td><td>180</td><td>181</td><td>185</td><td>186</td></tr>
     *   <tr><td>{@code SDTMM}</td><td>186</td><td>188</td><td>189</td><td>193</td><td>195</td></tr>
     *   <tr><td>{@code SDTDD}</td><td>195</td><td>197</td><td>198</td><td>202</td><td>204</td></tr>
     *   <tr><td>{@code SDTYYYY}</td><td>204</td><td>206</td><td>207</td><td>211</td><td>215</td></tr>
     *   <tr><td>{@code EDTMM}</td><td>215</td><td>217</td><td>218</td><td>222</td><td>224</td></tr>
     *   <tr><td>{@code EDTDD}</td><td>224</td><td>226</td><td>227</td><td>231</td><td>233</td></tr>
     *   <tr><td>{@code EDTYYYY}</td><td>233</td><td>235</td><td>236</td><td>240</td><td>244</td></tr>
     *   <tr><td>{@code CONFIRM}</td><td>244</td><td>246</td><td>247</td><td>251</td><td>252</td></tr>
     *   <tr><td>{@code ERRMSG}</td><td>252</td><td>254</td><td>255</td><td>259</td><td>337</td></tr>
     * </table>
     *
     * <h2>{@code unprotected} and {@code numeric} are descriptive, never enforced</h2>
     *
     * Both flags are read straight off the field's {@code DFHMDF ATTRB} list. They describe the
     * 3270 screen and are deliberately <strong>not</strong> turned into validation:
     * {@code CORPT00C} performs its own field editing - the {@code IS NOT NUMERIC} class tests at
     * lines 329, 338, 347, 355, 364 and 373 - and rejecting a value here that the COBOL would have
     * accepted, or accepting one it would have rejected, is a parity break either way. What
     * {@link #unprotected()} does explain is <em>which</em> fields can receive the cursor: all ten
     * unprotected fields and no others are the targets of the twenty-two
     * {@code MOVE -1 TO <field>L} sites in {@code CORPT00C}.
     */
    public enum ScreenField {

        /** {@code TRNNAME} at {@code POS=(1,7)}, {@code ASKIP,FSET,NORM}, {@code COLOR=BLUE}. */
        TRNNAME("TRNNAME", 12, TRNNAME_LENGTH, false, false),

        /** {@code TITLE01} at {@code POS=(1,21)}, {@code ASKIP,FSET,NORM}, {@code COLOR=YELLOW}. */
        TITLE01("TITLE01", 23, TITLE01_LENGTH, false, false),

        /**
         * {@code CURDATE} at {@code POS=(1,71)}, {@code ASKIP,FSET,NORM}, {@code COLOR=BLUE},
         * {@code INITIAL='mm/dd/yy'}.
         */
        CURDATE("CURDATE", 70, CURDATE_LENGTH, false, false),

        /** {@code PGMNAME} at {@code POS=(2,7)}, {@code ASKIP,FSET,NORM}, {@code COLOR=BLUE}. */
        PGMNAME("PGMNAME", 85, PGMNAME_LENGTH, false, false),

        /** {@code TITLE02} at {@code POS=(2,21)}, {@code ASKIP,FSET,NORM}, {@code COLOR=YELLOW}. */
        TITLE02("TITLE02", 100, TITLE02_LENGTH, false, false),

        /**
         * {@code CURTIME} at {@code POS=(2,71)}, {@code ASKIP,FSET,NORM}, {@code COLOR=BLUE},
         * {@code INITIAL='hh:mm:ss'}.
         */
        CURTIME("CURTIME", 147, CURTIME_LENGTH, false, false),

        /**
         * {@code MONTHLY} at {@code POS=(7,10)}, {@code FSET,IC,NORM,UNPROT}, {@code COLOR=GREEN},
         * {@code HILIGHT=UNDERLINE}, {@code INITIAL=' '}. The only field carrying {@code IC}, so it
         * holds the initial cursor - which is why sixteen of the twenty-two cursor moves in
         * {@code CORPT00C} target {@code MONTHLYL}.
         */
        MONTHLY("MONTHLY", 162, MONTHLY_LENGTH, true, false),

        /**
         * {@code YEARLY} at {@code POS=(9,10)}, {@code FSET,NORM,UNPROT}, {@code COLOR=GREEN},
         * {@code HILIGHT=UNDERLINE}, {@code INITIAL=' '}.
         */
        YEARLY("YEARLY", 170, YEARLY_LENGTH, true, false),

        /**
         * {@code CUSTOM} at {@code POS=(11,10)}, {@code FSET,NORM,UNPROT}, {@code COLOR=GREEN},
         * {@code HILIGHT=UNDERLINE}, {@code INITIAL=' '}.
         */
        CUSTOM("CUSTOM", 178, CUSTOM_LENGTH, true, false),

        /**
         * {@code SDTMM} at {@code POS=(13,29)}, {@code FSET,NORM,NUM,UNPROT}, {@code COLOR=GREEN},
         * {@code HILIGHT=UNDERLINE}, {@code INITIAL='  '}.
         */
        SDTMM("SDTMM", 186, SDTMM_LENGTH, true, true),

        /**
         * {@code SDTDD} at {@code POS=(13,34)}, {@code FSET,NORM,NUM,UNPROT}, {@code COLOR=GREEN},
         * {@code HILIGHT=UNDERLINE}, {@code INITIAL='  '}.
         */
        SDTDD("SDTDD", 195, SDTDD_LENGTH, true, true),

        /**
         * {@code SDTYYYY} at {@code POS=(13,39)}, {@code FSET,NORM,NUM,UNPROT}, {@code COLOR=GREEN},
         * {@code HILIGHT=UNDERLINE}, {@code INITIAL='    '}.
         */
        SDTYYYY("SDTYYYY", 204, SDTYYYY_LENGTH, true, true),

        /**
         * {@code EDTMM} at {@code POS=(14,29)}, {@code FSET,NORM,NUM,UNPROT}, {@code COLOR=GREEN},
         * {@code HILIGHT=UNDERLINE}, {@code INITIAL='  '}.
         */
        EDTMM("EDTMM", 215, EDTMM_LENGTH, true, true),

        /**
         * {@code EDTDD} at {@code POS=(14,34)}, {@code FSET,NORM,NUM,UNPROT}, {@code COLOR=GREEN},
         * {@code HILIGHT=UNDERLINE}, {@code INITIAL='  '}.
         */
        EDTDD("EDTDD", 224, EDTDD_LENGTH, true, true),

        /**
         * {@code EDTYYYY} at {@code POS=(14,39)}, {@code FSET,NORM,NUM,UNPROT}, {@code COLOR=GREEN},
         * {@code HILIGHT=UNDERLINE}, {@code INITIAL='    '}.
         */
        EDTYYYY("EDTYYYY", 233, EDTYYYY_LENGTH, true, true),

        /**
         * {@code CONFIRM} at {@code POS=(19,66)}, {@code FSET,NORM,UNPROT}, {@code COLOR=GREEN},
         * {@code HILIGHT=UNDERLINE}. The only unprotected field declaring no {@code INITIAL}.
         */
        CONFIRM("CONFIRM", 244, CONFIRM_LENGTH, true, false),

        /**
         * {@code ERRMSG} at {@code POS=(23,1)}, {@code ASKIP,BRT,FSET}, {@code COLOR=RED}. Protected
         * and yet part of the request, because {@code FSET} makes the terminal return it.
         */
        ERRMSG("ERRMSG", 252, ERRMSG_LENGTH, false, false);

        private final String bmsName;
        private final String lengthItem;
        private final String flagItem;
        private final String attributeItem;
        private final String inputItem;
        private final int declaredLength;
        private final boolean unprotected;
        private final boolean numeric;
        private final FieldSpan lengthSpan;
        private final FieldSpan flagSpan;
        private final FieldSpan attributeSpan;
        private final FieldSpan reservedFillerSpan;
        private final FieldSpan inputSpan;

        /**
         * Derives the four copybook item names and the five spans from the field's name, offset and
         * width.
         *
         * <p>The item names are formed the way BMS itself forms them - the field name plus a
         * one-character direction suffix - rather than transcribed as four more literals per field.
         * Derivation cannot disagree with the copybook, whereas sixty-eight hand-copied literals
         * could, and the resulting spellings ({@code TRNNAMEL}, {@code TRNNAMEF},
         * {@code TRNNAMEA}, {@code TRNNAMEI} and so on for all seventeen) are asserted against
         * {@code app/cpy-bms/CORPT00.CPY} by this type's unit test. Nothing is validated here on
         * purpose: a guard inside an enum constructor can never be exercised, because an invalid
         * constant cannot be constructed, and an unreachable branch is worse than no branch.
         *
         * @param bmsName          the {@code DFHMDF} name label, verbatim
         * @param lengthItemOffset the absolute 0-based offset of the {@code xxxL} item
         * @param declaredLength   {@code n} of the {@code xxxI} item's {@code PIC X(n)}
         * @param unprotected      whether {@code ATTRB} includes {@code UNPROT}; descriptive only
         * @param numeric          whether {@code ATTRB} includes {@code NUM}; descriptive only
         */
        ScreenField(String bmsName,
                    int lengthItemOffset,
                    int declaredLength,
                    boolean unprotected,
                    boolean numeric) {
            this.bmsName = bmsName;
            this.lengthItem = bmsName + LENGTH_ITEM_SUFFIX;
            this.flagItem = bmsName + FLAG_ITEM_SUFFIX;
            this.attributeItem = bmsName + ATTRIBUTE_ITEM_SUFFIX;
            this.inputItem = bmsName + INPUT_ITEM_SUFFIX;
            this.declaredLength = declaredLength;
            this.unprotected = unprotected;
            this.numeric = numeric;
            // The COMP halfword has no PictureKind of its own: the enumeration models PICTURE
            // categories - character, unsigned zoned DISPLAY, signed zoned DISPLAY and FILLER - and a
            // binary halfword is none of them. The span is declared as a positioned two-byte
            // character reservation so the geometry is exact, and its CONTENT is only ever moved as
            // raw bytes through readSpanBytes and writeSpanBytes, never through a character or a
            // numeric MOVE. See ReportRequestRequest.toSymbolicMap and metadataFrom.
            this.lengthSpan = FieldSpan.alphanumeric(lengthItem, lengthItemOffset,
                    LENGTH_ITEM_LENGTH);
            this.flagSpan = FieldSpan.alphanumeric(flagItem, lengthItemOffset + LENGTH_ITEM_LENGTH,
                    FLAG_ITEM_LENGTH);
            // 02 FILLER REDEFINES xxxF. / 03 xxxA PICTURE X. - one byte viewed twice, so the overlay
            // is taken from the flag span itself and cannot be given a wrong offset.
            this.attributeSpan = flagSpan.redefinedAs(attributeItem, PictureKind.ALPHANUMERIC);
            this.reservedFillerSpan = FieldSpan.filler(
                    lengthItemOffset + LENGTH_ITEM_LENGTH + FLAG_ITEM_LENGTH,
                    RESERVED_FILLER_LENGTH);
            this.inputSpan = FieldSpan.alphanumeric(inputItem,
                    lengthItemOffset + FIELD_PREFIX_LENGTH, declaredLength);
        }

        /**
         * The {@code DFHMDF} name label, verbatim - {@code TRNNAME}, {@code SDTYYYY} and so on.
         *
         * @return the screen field's BMS name, never {@code null}
         */
        public String bmsName() {
            return bmsName;
        }

        /**
         * The symbolic-map length item, as in {@code TRNNAMEL}. This is the
         * {@code COMP PIC S9(4)} halfword that {@code CORPT00C} moves {@code -1} into to position
         * the cursor.
         *
         * @return the {@code xxxL} item name, verbatim
         */
        public String lengthItem() {
            return lengthItem;
        }

        /**
         * The symbolic-map flag item, as in {@code TRNNAMEF}.
         *
         * @return the {@code xxxF} item name, verbatim
         */
        public String flagItem() {
            return flagItem;
        }

        /**
         * The attribute view that redefines the flag item, as in {@code TRNNAMEA}. Same byte,
         * different name; this is the view a program writes an attribute byte through.
         *
         * @return the {@code xxxA} item name, verbatim
         */
        public String attributeItem() {
            return attributeItem;
        }

        /**
         * The symbolic-map input data item, as in {@code TRNNAMEI} - the payload item this field
         * projects to.
         *
         * @return the {@code xxxI} item name, verbatim
         */
        public String inputItem() {
            return inputItem;
        }

        /**
         * {@code n} of the input item's {@code PIC X(n)}, which the mapset repeats as its
         * {@code DFHMDF LENGTH=}.
         *
         * @return the declared character width, at least 1
         */
        public int declaredLength() {
            return declaredLength;
        }

        /**
         * Whether the field's {@code DFHMDF ATTRB} list includes {@code UNPROT}. Ten of the
         * seventeen fields do. Descriptive only - see this enum's documentation.
         *
         * @return {@code true} for an operator-writable field
         */
        public boolean unprotected() {
            return unprotected;
        }

        /**
         * Whether the field's {@code DFHMDF ATTRB} list includes {@code NUM}. The six date parts do.
         * Descriptive only: the numeric editing itself stays in the program, which class tests the
         * item with {@code IS NOT NUMERIC}.
         *
         * @return {@code true} for a numeric-shifted field
         */
        public boolean numeric() {
            return numeric;
        }

        /**
         * The two-byte {@code xxxL} span. Its content is a binary halfword and is read and written
         * only as raw bytes.
         *
         * @return the length item's descriptor
         */
        public FieldSpan lengthSpan() {
            return lengthSpan;
        }

        /**
         * The one-byte {@code xxxF} span.
         *
         * @return the flag item's descriptor
         */
        public FieldSpan flagSpan() {
            return flagSpan;
        }

        /**
         * The {@code REDEFINES} overlay {@code xxxA}, addressing the same byte as
         * {@link #flagSpan()}.
         *
         * @return the attribute view's descriptor
         */
        public FieldSpan attributeSpan() {
            return attributeSpan;
        }

        /**
         * The {@value ReportRequestRequest#RESERVED_FILLER_LENGTH}-byte reserved {@code FILLER}
         * between the flag byte and the data. Emitted as spaces; omitting it would shift every
         * following offset.
         *
         * @return the reserved span's descriptor
         */
        public FieldSpan reservedFillerSpan() {
            return reservedFillerSpan;
        }

        /**
         * The {@code xxxI} payload span. Because {@code CORPT0AO REDEFINES CORPT0AI} spends the same
         * seven prefix bytes, this is also the offset and width of the corresponding {@code xxxO}
         * item.
         *
         * @return the input item's descriptor
         */
        public FieldSpan inputSpan() {
            return inputSpan;
        }

        /**
         * The COBOL {@code SPACES} figurative constant sized to this field - the value
         * {@code MOVE SPACES} leaves in it.
         *
         * @return exactly {@link #declaredLength()} spaces
         */
        public String spaces() {
            return SPACE.repeat(declaredLength);
        }

        /**
         * The COBOL {@code LOW-VALUES} figurative constant sized to this field, as
         * {@code MOVE LOW-VALUES TO CORPT0AO} at {@code app/cbl/CORPT00C.cbl:179} leaves it.
         *
         * @return exactly {@link #declaredLength()} {@code X'00'} characters
         */
        public String lowValues() {
            return LOW_VALUE.repeat(declaredLength);
        }
    }

    // =================================================================================================
    // The layout. Eighty-six spans - one TIOAPFX prefix plus five per field - of which sixty-nine
    // occupy storage and seventeen are REDEFINES overlays. Constructing it is what PROVES the
    // SYMBOLIC_MAP_LENGTH arithmetic, because RecordLayout refuses to exist when the spans do not run
    // contiguously from zero and end exactly there.
    // =================================================================================================

    /**
     * The complete {@value #SYMBOLIC_MAP_LENGTH}-byte geometry of {@code 01 CORPT0AI}, in copybook
     * declaration order.
     *
     * <p>Every byte is declared, {@code FILLER} included: the twelve-byte TIOAPFX prefix, then per
     * field the two-byte {@code xxxL} halfword, the one-byte {@code xxxF} flag, the {@code xxxA}
     * overlay of that same byte, the four-byte reserved {@code FILLER} and the {@code xxxI} data.
     * Omitting a {@code FILLER} would leave the layout short and fail here rather than silently
     * shifting every following field.
     *
     * <p>The layout is deeply immutable - a record whose span list is copied through
     * {@code List.copyOf} and whose elements are themselves immutable records - so publishing it as a
     * constant introduces no mutable static state. It is public because the parity harness and the
     * field differ need the geometry in order to compare an image field by field.
     */
    public static final RecordLayout LAYOUT = buildLayout();

    /**
     * Assembles {@link #LAYOUT}. Kept as a method rather than an inline expression so the
     * per-field repetition is written once: eighty-six hand-written span arguments would be
     * eighty-six chances to mistype one.
     */
    private static RecordLayout buildLayout() {
        List<FieldSpan> spans = new ArrayList<>(1 + FIELD_COUNT * 5);
        spans.add(TIOAPFX_PREFIX_SPAN);
        for (ScreenField field : ScreenField.values()) {
            spans.add(field.lengthSpan());
            spans.add(field.flagSpan());
            spans.add(field.attributeSpan());
            spans.add(field.reservedFillerSpan());
            spans.add(field.inputSpan());
        }
        return new RecordLayout(SYMBOLIC_MAP_LENGTH, spans);
    }

    // =================================================================================================
    // Construction.
    // =================================================================================================

    /**
     * Normalises every screen field at construction time, so an instance either exists and fits the
     * {@value #SYMBOLIC_MAP_LENGTH}-byte symbolic map or does not exist at all.
     *
     * <p>Two rules, both delegated to a single shared guard so that all seventeen fields are held to
     * an identical standard:
     *
     * <ul>
     *   <li><strong>{@code null} becomes the field's {@code SPACES}.</strong> There is no null in a
     *       COBOL record - an unset {@code PIC X} item holds spaces - and an absent JSON property is
     *       an unset field, so a partial payload is completed rather than rejected. This is a
     *       deliberate divergence from {@link NavigationContext}, which refuses {@code null}: that
     *       type is assembled in code, where a null is a caller defect worth a stack trace, whereas
     *       this one is deserialised from a wire payload, where an omitted property is ordinary.</li>
     *   <li><strong>An over-long value is refused.</strong> The symbolic map has nowhere to put the
     *       surplus. Refusing keeps {@link #toSymbolicMap(FixedWidthCodec)} a lossless projection -
     *       what goes in comes out - and it is not stricter than the COBOL in any reachable sense,
     *       because a 3270 {@code RECEIVE MAP} cannot deliver more bytes than a field is wide, so
     *       {@code CORPT00C} never sees the case. A caller that genuinely wants COBOL's alphanumeric
     *       {@code MOVE} behaviour asks for it explicitly through
     *       {@link FixedWidthCodec#movePicX(String, int)}, which truncates on the right, so the
     *       direction of the loss is chosen deliberately and is visible at the call site.</li>
     * </ul>
     *
     * <p>{@code navigationContext} is left exactly as supplied, {@code null} included, because
     * {@code null} carries meaning: it is {@code EIBCALEN = 0}, the cold start of
     * {@code app/cbl/CORPT00C.cbl:172}, which the program distinguishes from a communication area
     * that merely happens to be initialised. Collapsing the two would erase a real branch.
     *
     * <p>{@code aid} is held to the same two rules as the screen fields - {@code null} becomes spaces,
     * an over-long token is refused - even though it is not a screen field. Spaces is the honest
     * no-key-resolved state, and an unrecognised or blank token is exactly what
     * {@code app/cbl/CORPT00C.cbl:190} answers with {@code WHEN OTHER}, so nothing is lost by filling
     * it in.
     *
     * @throws IllegalArgumentException if any screen field is longer than its declared
     *                                  {@code PIC X(n)}, or if {@code aid} is longer than
     *                                  {@value #AID_LENGTH} characters
     */
    public ReportRequestRequest {
        trnname = normalise(trnname, ScreenField.TRNNAME);
        title01 = normalise(title01, ScreenField.TITLE01);
        curdate = normalise(curdate, ScreenField.CURDATE);
        pgmname = normalise(pgmname, ScreenField.PGMNAME);
        title02 = normalise(title02, ScreenField.TITLE02);
        curtime = normalise(curtime, ScreenField.CURTIME);
        monthly = normalise(monthly, ScreenField.MONTHLY);
        yearly = normalise(yearly, ScreenField.YEARLY);
        custom = normalise(custom, ScreenField.CUSTOM);
        sdtmm = normalise(sdtmm, ScreenField.SDTMM);
        sdtdd = normalise(sdtdd, ScreenField.SDTDD);
        sdtyyyy = normalise(sdtyyyy, ScreenField.SDTYYYY);
        edtmm = normalise(edtmm, ScreenField.EDTMM);
        edtdd = normalise(edtdd, ScreenField.EDTDD);
        edtyyyy = normalise(edtyyyy, ScreenField.EDTYYYY);
        confirm = normalise(confirm, ScreenField.CONFIRM);
        errmsg = normalise(errmsg, ScreenField.ERRMSG);
        aid = normaliseAid(aid);
    }

    /**
     * Applies the two field rules to the {@code EIBAID} token, which has no {@link ScreenField} to
     * carry its width because it is not part of {@code 01 CORPT0AI}.
     *
     * @param value the token offered, {@code null} meaning no key has been resolved
     * @return the token unchanged, or {@value #AID_LENGTH} spaces when it was {@code null}
     * @throws IllegalArgumentException if longer than {@value #AID_LENGTH} characters
     */
    private static String normaliseAid(String value) {
        if (value == null) {
            return SPACE.repeat(AID_LENGTH);
        }
        if (value.length() > AID_LENGTH) {
            throw new IllegalArgumentException(AID_FIELD + " is carried as a PIC X(" + AID_LENGTH
                    + ") token, matching common.PfKeyResolver.AID_TOKEN_LENGTH, but was given "
                    + value.length() + " character(s). AidKey.token() already space-pads to that "
                    + "width, so a resolved token never overflows it");
        }
        return value;
    }

    /**
     * A freshly initialised request: every screen field a run of spaces of its declared width, paired
     * with a freshly initialised communication area.
     *
     * <p>This is the COBOL {@code MOVE SPACES} shape of {@code 01 CORPT0AI}. The communication area
     * is {@link NavigationContext#empty()}, which is {@code CDEMO-PGM-ENTER} - the state a
     * transaction is in on first entry - so {@link #isEnter()} is true. Use
     * {@link #withoutNavigationContext()} to model {@code EIBCALEN = 0} instead.
     *
     * @return an initialised request, never {@code null}
     */
    public static ReportRequestRequest empty() {
        return filled(SPACE);
    }

    /**
     * A request whose screen fields are all {@code LOW-VALUES}, as
     * {@code MOVE LOW-VALUES TO CORPT0AO} at {@code app/cbl/CORPT00C.cbl:179} leaves the map on
     * first entry, paired with a freshly initialised communication area.
     *
     * <p>{@code CORPT00C} tests its selection fields against {@code SPACES OR LOW-VALUES} - lines
     * 213, 239, 258 and 464 - so the two initial states are behaviourally equivalent to the program
     * while remaining distinct in storage. Both are modelled so a parity case can seed either.
     *
     * @return a low-values request, never {@code null}
     */
    public static ReportRequestRequest lowValues() {
        return filled(LOW_VALUE);
    }

    /**
     * Fills every screen field with a repetition of one figurative character, sized per field.
     *
     * <p>This is COBOL's unconditional fill, the {@code MOVE SPACES} and
     * {@code MOVE LOW-VALUES} shape, and deliberately not the alphanumeric {@code MOVE} rule - that
     * rule, which pads a shorter sending value and truncates a longer one, belongs to
     * {@link FixedWidthCodec} alone and is never reimplemented here.
     */
    private static ReportRequestRequest filled(String fillCharacter) {
        List<String> values = new ArrayList<>(FIELD_COUNT);
        for (ScreenField field : ScreenField.values()) {
            values.add(fillCharacter.repeat(field.declaredLength()));
        }
        // The AID is filled with spaces in both the SPACES and the LOW-VALUES shape, because it is not
        // part of 01 CORPT0AI: MOVE LOW-VALUES TO CORPT0AO at CORPT00C.cbl:179 reaches the map and
        // nothing outside it, and no key has been pressed on a screen that has only just been painted.
        return fromFieldValues(values, NavigationContext.empty(), SPACE.repeat(AID_LENGTH));
    }

    /**
     * Rebuilds a request from seventeen values in {@link ScreenField} declaration order, plus the two
     * members that are not screen fields.
     *
     * <p>Positional, because a record's canonical constructor is positional; the ordering contract is
     * {@link ScreenField#ordinal()} and is shared with {@link #fieldValues()}, so the two cannot
     * drift apart.
     */
    private static ReportRequestRequest fromFieldValues(List<String> values,
                                                        NavigationContext context,
                                                        String aid) {
        return new ReportRequestRequest(values.get(ScreenField.TRNNAME.ordinal()),
                values.get(ScreenField.TITLE01.ordinal()),
                values.get(ScreenField.CURDATE.ordinal()),
                values.get(ScreenField.PGMNAME.ordinal()),
                values.get(ScreenField.TITLE02.ordinal()),
                values.get(ScreenField.CURTIME.ordinal()),
                values.get(ScreenField.MONTHLY.ordinal()),
                values.get(ScreenField.YEARLY.ordinal()),
                values.get(ScreenField.CUSTOM.ordinal()),
                values.get(ScreenField.SDTMM.ordinal()),
                values.get(ScreenField.SDTDD.ordinal()),
                values.get(ScreenField.SDTYYYY.ordinal()),
                values.get(ScreenField.EDTMM.ordinal()),
                values.get(ScreenField.EDTDD.ordinal()),
                values.get(ScreenField.EDTYYYY.ordinal()),
                values.get(ScreenField.CONFIRM.ordinal()),
                values.get(ScreenField.ERRMSG.ordinal()),
                context,
                aid);
    }

    // =================================================================================================
    // Reading the seventeen fields generically. CORPT00C edits its six date parts with the same six
    // statements repeated, so a by-field accessor lets the controller express that as one loop without
    // a seventeen-way switch that could wire a field to the wrong member.
    // =================================================================================================

    /**
     * The seventeen screen values in {@link ScreenField} declaration order.
     *
     * <p>Not a JSON property: it is a different view of members that are already on the wire, and
     * emitting it would duplicate every field. The returned list is immutable.
     *
     * @return an immutable list of exactly {@value #FIELD_COUNT} values, none {@code null}
     */
    @JsonIgnore
    public List<String> fieldValues() {
        return List.of(trnname, title01, curdate, pgmname, title02, curtime, monthly, yearly, custom,
                sdtmm, sdtdd, sdtyyyy, edtmm, edtdd, edtyyyy, confirm, errmsg);
    }

    /**
     * The value of one screen field, untrimmed and exactly as it arrived.
     *
     * @param field the screen field to read
     * @return the field's value, never {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public String value(ScreenField field) {
        Objects.requireNonNull(field, "A ScreenField is required to read a value from "
                + SYMBOLIC_MAP_INPUT_GROUP);
        return fieldValues().get(field.ordinal());
    }

    /**
     * A copy of this request with one screen field replaced.
     *
     * <p>{@code xxxI} items are <strong>not</strong> read-only: {@code CORPT00C} writes back into six
     * of them at lines 307, 311, 315, 319, 323 and 327, normalising each date part with
     * {@code FUNCTION NUMVAL-C} and then re-testing the item it just rewrote. This method is how that
     * is expressed without mutating shared state.
     *
     * @param field the screen field to replace
     * @param value the new value; {@code null} becomes the field's {@code SPACES}
     * @return a new request, never {@code null}
     * @throws NullPointerException     if {@code field} is {@code null}
     * @throws IllegalArgumentException if {@code value} is longer than the field's declared width
     */
    public ReportRequestRequest withValue(ScreenField field, String value) {
        Objects.requireNonNull(field, "A ScreenField is required to replace a value in "
                + SYMBOLIC_MAP_INPUT_GROUP);
        List<String> next = new ArrayList<>(fieldValues());
        next.set(field.ordinal(), normalise(value, field));
        return fromFieldValues(next, navigationContext, aid);
    }

    // =================================================================================================
    // The named withers, one per field, plus the communication area. Seventeen explicit methods rather
    // than only the generic withValue, because a controller reproducing CORPT00C names the field it is
    // rewriting and a named call site is checked by the compiler.
    // =================================================================================================

    /**
     * A copy carrying a new {@code TRNNAMEI}.
     *
     * @param newTrnname the new transaction identifier; {@code null} becomes spaces
     * @return a new request
     */
    public ReportRequestRequest withTrnname(String newTrnname) {
        return withValue(ScreenField.TRNNAME, newTrnname);
    }

    /**
     * A copy carrying a new {@code TITLE01I}.
     *
     * @param newTitle01 the new first title line; {@code null} becomes spaces
     * @return a new request
     */
    public ReportRequestRequest withTitle01(String newTitle01) {
        return withValue(ScreenField.TITLE01, newTitle01);
    }

    /**
     * A copy carrying a new {@code CURDATEI}.
     *
     * @param newCurdate the new current date; {@code null} becomes spaces
     * @return a new request
     */
    public ReportRequestRequest withCurdate(String newCurdate) {
        return withValue(ScreenField.CURDATE, newCurdate);
    }

    /**
     * A copy carrying a new {@code PGMNAMEI}.
     *
     * @param newPgmname the new program name; {@code null} becomes spaces
     * @return a new request
     */
    public ReportRequestRequest withPgmname(String newPgmname) {
        return withValue(ScreenField.PGMNAME, newPgmname);
    }

    /**
     * A copy carrying a new {@code TITLE02I}.
     *
     * @param newTitle02 the new second title line; {@code null} becomes spaces
     * @return a new request
     */
    public ReportRequestRequest withTitle02(String newTitle02) {
        return withValue(ScreenField.TITLE02, newTitle02);
    }

    /**
     * A copy carrying a new {@code CURTIMEI}.
     *
     * @param newCurtime the new current time; {@code null} becomes spaces
     * @return a new request
     */
    public ReportRequestRequest withCurtime(String newCurtime) {
        return withValue(ScreenField.CURTIME, newCurtime);
    }

    /**
     * A copy carrying a new {@code MONTHLYI} - the current-month report selector tested at
     * {@code app/cbl/CORPT00C.cbl:213}.
     *
     * @param newMonthly the new selector; {@code null} becomes a space
     * @return a new request
     */
    public ReportRequestRequest withMonthly(String newMonthly) {
        return withValue(ScreenField.MONTHLY, newMonthly);
    }

    /**
     * A copy carrying a new {@code YEARLYI} - the current-year report selector tested at
     * {@code app/cbl/CORPT00C.cbl:239}.
     *
     * @param newYearly the new selector; {@code null} becomes a space
     * @return a new request
     */
    public ReportRequestRequest withYearly(String newYearly) {
        return withValue(ScreenField.YEARLY, newYearly);
    }

    /**
     * A copy carrying a new {@code CUSTOMI} - the custom date-range selector tested at
     * {@code app/cbl/CORPT00C.cbl:258}.
     *
     * @param newCustom the new selector; {@code null} becomes a space
     * @return a new request
     */
    public ReportRequestRequest withCustom(String newCustom) {
        return withValue(ScreenField.CUSTOM, newCustom);
    }

    /**
     * A copy carrying a new {@code SDTMMI}, the start-date month compared against {@code '12'} at
     * {@code app/cbl/CORPT00C.cbl:330}.
     *
     * @param newSdtmm the new month characters; {@code null} becomes spaces
     * @return a new request
     */
    public ReportRequestRequest withSdtmm(String newSdtmm) {
        return withValue(ScreenField.SDTMM, newSdtmm);
    }

    /**
     * A copy carrying a new {@code SDTDDI}, the start-date day compared against {@code '31'} at
     * {@code app/cbl/CORPT00C.cbl:339}.
     *
     * @param newSdtdd the new day characters; {@code null} becomes spaces
     * @return a new request
     */
    public ReportRequestRequest withSdtdd(String newSdtdd) {
        return withValue(ScreenField.SDTDD, newSdtdd);
    }

    /**
     * A copy carrying a new {@code SDTYYYYI}, the start-date year class tested at
     * {@code app/cbl/CORPT00C.cbl:347}.
     *
     * @param newSdtyyyy the new year characters; {@code null} becomes spaces
     * @return a new request
     */
    public ReportRequestRequest withSdtyyyy(String newSdtyyyy) {
        return withValue(ScreenField.SDTYYYY, newSdtyyyy);
    }

    /**
     * A copy carrying a new {@code EDTMMI}, the end-date month compared against {@code '12'} at
     * {@code app/cbl/CORPT00C.cbl:356}.
     *
     * @param newEdtmm the new month characters; {@code null} becomes spaces
     * @return a new request
     */
    public ReportRequestRequest withEdtmm(String newEdtmm) {
        return withValue(ScreenField.EDTMM, newEdtmm);
    }

    /**
     * A copy carrying a new {@code EDTDDI}, the end-date day compared against {@code '31'} at
     * {@code app/cbl/CORPT00C.cbl:365}.
     *
     * @param newEdtdd the new day characters; {@code null} becomes spaces
     * @return a new request
     */
    public ReportRequestRequest withEdtdd(String newEdtdd) {
        return withValue(ScreenField.EDTDD, newEdtdd);
    }

    /**
     * A copy carrying a new {@code EDTYYYYI}, the end-date year class tested at
     * {@code app/cbl/CORPT00C.cbl:373}.
     *
     * @param newEdtyyyy the new year characters; {@code null} becomes spaces
     * @return a new request
     */
    public ReportRequestRequest withEdtyyyy(String newEdtyyyy) {
        return withValue(ScreenField.EDTYYYY, newEdtyyyy);
    }

    /**
     * A copy carrying a new {@code CONFIRMI}. {@code CORPT00C} accepts {@code 'Y'}, {@code 'y'},
     * {@code 'N'} and {@code 'n'} at lines 478 and 480 and rejects everything else at line 484; the
     * acceptance itself belongs to the controller, not here.
     *
     * @param newConfirm the new confirmation character; {@code null} becomes a space
     * @return a new request
     */
    public ReportRequestRequest withConfirm(String newConfirm) {
        return withValue(ScreenField.CONFIRM, newConfirm);
    }

    /**
     * A copy carrying a new {@code ERRMSGI}. Present on the request because the field carries
     * {@code FSET} and is therefore returned by the terminal.
     *
     * @param newErrmsg the new error line; {@code null} becomes spaces
     * @return a new request
     */
    public ReportRequestRequest withErrmsg(String newErrmsg) {
        return withValue(ScreenField.ERRMSG, newErrmsg);
    }

    /**
     * A copy carrying a different communication area.
     *
     * @param newNavigationContext the communication area to carry, or {@code null} for
     *                             {@code EIBCALEN = 0}
     * @return a new request
     */
    public ReportRequestRequest withNavigationContext(NavigationContext newNavigationContext) {
        return fromFieldValues(fieldValues(), newNavigationContext, aid);
    }

    /**
     * A copy carrying no communication area at all - the {@code EIBCALEN = 0} state that
     * {@code app/cbl/CORPT00C.cbl:172} tests before anything else, and on which it moves
     * {@code 'COSGN00C'} into {@code CDEMO-TO-PROGRAM} and returns to the previous screen.
     *
     * @return a new request whose {@link #navigationContext()} is {@code null}
     */
    public ReportRequestRequest withoutNavigationContext() {
        return fromFieldValues(fieldValues(), null, aid);
    }

    /**
     * A copy carrying a new resolved {@code EIBAID} token - the key the operator pressed, which
     * {@code app/cbl/CORPT00C.cbl:183} evaluates.
     *
     * <p>Pass the token {@code common.PfKeyResolver.AidKey#token()} produces, already space-padded to
     * {@value #AID_LENGTH}. The two tokens this screen acts on are {@code 'ENTER'} and
     * {@code 'PFK03'}; every other value, spaces included, is the {@code WHEN OTHER} arm and its
     * {@code CCDA-MSG-INVALID-KEY} message.
     *
     * @param newAid the resolved key token; {@code null} becomes spaces, meaning no key resolved
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if longer than {@value #AID_LENGTH} characters
     */
    public ReportRequestRequest withAid(String newAid) {
        return fromFieldValues(fieldValues(), navigationContext, normaliseAid(newAid));
    }

    // =================================================================================================
    // The conversation, read from the payload and from nowhere else. There is no session here.
    // =================================================================================================

    /**
     * Whether a communication area travelled with this request - the Java reading of
     * {@code EIBCALEN} being non-zero.
     *
     * <p>Not a JSON property: it is derived from {@link #navigationContext()}, which is already on
     * the wire. Emitting it would let a payload assert a presence that contradicts the member it
     * travels with.
     *
     * @return {@code true} when {@link #navigationContext()} is present
     */
    @JsonIgnore
    public boolean hasNavigationContext() {
        return navigationContext != null;
    }

    /**
     * The length CICS would report in {@code EIBCALEN}: {@value NavigationContext#COMMAREA_LENGTH}
     * when a communication area travelled with the request, and {@code 0} when none did.
     *
     * <p>{@code CORPT00C} has no communication-area extension - line 138 copies
     * {@code COCOM01Y} and line 140 goes straight to the map - so the non-zero case is always
     * exactly {@value NavigationContext#COMMAREA_LENGTH}, never the 218 bytes the {@code COTRN}
     * programs pass.
     *
     * @return {@value NavigationContext#COMMAREA_LENGTH} or {@code 0}
     */
    @JsonIgnore
    public int commareaLength() {
        return hasNavigationContext() ? NavigationContext.COMMAREA_LENGTH : 0;
    }

    /**
     * {@code CDEMO-PGM-CONTEXT} of the carried communication area, or
     * {@value NavigationContext#PGM_CONTEXT_ENTER} when none was carried.
     *
     * <p>Reading an absent communication area as first entry is the faithful choice rather than a
     * convenience. A cold start cannot be a re-entry - there is no previous invocation of this
     * transaction to re-enter from - and {@code CORPT00C}'s own return path agrees: line 547 moves
     * {@code ZEROS} into {@code CDEMO-PGM-CONTEXT} immediately before transferring control.
     *
     * <p>The value is read from the communication area rather than stored a second time, so it cannot
     * drift out of step with the sixteen fields it belongs to.
     *
     * @return {@value NavigationContext#PGM_CONTEXT_ENTER},
     *         {@value NavigationContext#PGM_CONTEXT_REENTER}, or whatever other digit the carried
     *         {@code PIC 9(01)} holds
     */
    @JsonIgnore
    public int pgmContext() {
        return hasNavigationContext()
                ? navigationContext.pgmContext()
                : NavigationContext.PGM_CONTEXT_ENTER;
    }

    /**
     * Whether {@code 88 CDEMO-PGM-ENTER VALUE 0} holds - first entry, so the screen is painted and
     * nothing is validated.
     *
     * <p>This is the {@code IF NOT CDEMO-PGM-REENTER} branch of {@code app/cbl/CORPT00C.cbl:177},
     * which sets the re-enter flag, moves {@code LOW-VALUES} to the map, positions the cursor on
     * {@code MONTHLYL} and sends the screen without editing a single field.
     *
     * @return {@code true} when {@link #pgmContext()} is {@value NavigationContext#PGM_CONTEXT_ENTER}
     */
    @JsonIgnore
    public boolean isEnter() {
        return pgmContext() == NavigationContext.PGM_CONTEXT_ENTER;
    }

    /**
     * Whether {@code 88 CDEMO-PGM-REENTER VALUE 1} holds - re-entry, so what was typed is validated.
     *
     * <p>This is the {@code ELSE} branch of {@code app/cbl/CORPT00C.cbl:182}, which receives the map
     * and evaluates {@code EIBAID}. It is also the state in which the error highlight applies, so it
     * has to be readable from the request in order to be honoured on the response.
     *
     * <p>Deliberately not the negation of {@link #isEnter()}: {@code CDEMO-PGM-CONTEXT} is
     * {@code PIC 9(01)} and may hold any digit, so for a value of, say, {@code 9} both predicates are
     * false. Defining one as the other's complement would invent a state the copybook does not
     * describe.
     *
     * @return {@code true} when {@link #pgmContext()} is
     *         {@value NavigationContext#PGM_CONTEXT_REENTER}
     */
    @JsonIgnore
    public boolean isReenter() {
        return pgmContext() == NavigationContext.PGM_CONTEXT_REENTER;
    }

    // =================================================================================================
    // The fixed-width projection. Every pad, every truncation and every code-page decision belongs to
    // FixedWidthCodec; this type states the geometry and moves the values, and implements no move rule
    // of its own.
    // =================================================================================================

    /**
     * Renders the {@value #SYMBOLIC_MAP_LENGTH}-byte image of {@code 01 CORPT0AI} with freshly
     * initialised metadata - every {@code xxxL} zero and every {@code xxxF} {@code LOW-VALUES}.
     *
     * @param codec the fixed-width codec, carrying the code page explicitly
     * @return exactly {@value #SYMBOLIC_MAP_LENGTH} bytes
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public byte[] toSymbolicMap(FixedWidthCodec codec) {
        return toSymbolicMap(codec, SymbolicMapMetadata.initial());
    }

    /**
     * Renders the {@value #SYMBOLIC_MAP_LENGTH}-byte image of {@code 01 CORPT0AI}.
     *
     * <p>What is written where:
     *
     * <ul>
     *   <li>The twelve-byte TIOAPFX prefix and the seventeen four-byte reserved {@code FILLER} spans
     *       are space-filled by {@link FixedWidthCodec#newRecord(RecordLayout)}, which initialises
     *       every declared span before a single field is touched. They are emitted, never skipped.</li>
     *   <li>Each {@code xxxL} receives its signed halfword as <strong>raw bytes</strong>, big-endian
     *       two's complement, because {@code COMP} is binary and no {@code PICTURE} category
     *       describes it. That is what lets {@code -1} - the CICS cursor-positioning value that
     *       {@code CORPT00C} moves at twenty-two sites - be represented at all; a zoned
     *       {@code DISPLAY} write would have had nowhere to put the sign.</li>
     *   <li>Each {@code xxxF} receives its flag byte as character data.</li>
     *   <li>Each {@code xxxI} receives its value through
     *       {@link FixedWidthCodec#movePicX(String, int)}, so a shorter value is padded on the right
     *       with spaces exactly as a COBOL {@code MOVE} into a wider {@code PIC X} receiver pads.
     *       Values are never over-long, because the constructor refused those.</li>
     *   <li>The seventeen {@code xxxA} overlays are not written separately: they redefine the flag
     *       byte, so writing them again would overwrite storage a second time and make the result
     *       depend on declaration order.</li>
     * </ul>
     *
     * @param codec    the fixed-width codec, carrying the code page explicitly - {@code US-ASCII} for
     *                 the text fixtures, {@code IBM037} for EBCDIC data. Never defaulted
     * @param metadata the {@code xxxL} and {@code xxxF} values to emit
     * @return exactly {@value #SYMBOLIC_MAP_LENGTH} bytes, in the codec's code page
     * @throws NullPointerException if {@code codec} or {@code metadata} is {@code null}
     */
    public byte[] toSymbolicMap(FixedWidthCodec codec, SymbolicMapMetadata metadata) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to render "
                + SYMBOLIC_MAP_INPUT_GROUP + ": the code page of a fixed-width image must be stated "
                + "explicitly and is never derived from the platform");
        Objects.requireNonNull(metadata, "Symbolic-map metadata is required to render "
                + SYMBOLIC_MAP_INPUT_GROUP + "; call SymbolicMapMetadata.initial() for a freshly "
                + "initialised map");
        FixedWidthRecord record = codec.newRecord(LAYOUT);
        List<String> payload = fieldValues();
        for (ScreenField field : ScreenField.values()) {
            record.writeSpanBytes(field.lengthSpan(), halfword(metadata.length(field)));
            record.writeSpan(field.flagSpan(), metadata.flag(field));
            record.writeSpan(field.inputSpan(),
                    codec.movePicX(payload.get(field.ordinal()), field.declaredLength()));
        }
        return record.toByteArray();
    }

    /**
     * Reads a {@value #SYMBOLIC_MAP_LENGTH}-byte image of {@code 01 CORPT0AI} back into a request.
     *
     * <p>Every {@code xxxI} span is taken <strong>raw and untrimmed</strong>, so each field comes back
     * at exactly its declared width with its trailing spaces intact. That is what makes the round trip
     * byte-identical: rendering the result reproduces the image it was read from, because nothing was
     * silently dropped on the way in.
     *
     * <p>The result carries <strong>no communication area</strong> and <strong>no resolved key</strong>.
     * Neither is an omission: neither is part of the map image. CICS passes the communication area
     * separately through {@code DFHCOMMAREA}, and reports {@code EIBAID} in the exec interface block,
     * so the honest reading of a map image alone is {@code EIBCALEN = 0} with no key resolved - which
     * is {@code null} for the one and {@value #AID_LENGTH} spaces for the other. Pair the result with
     * {@link #withNavigationContext(NavigationContext)} and {@link #withAid(String)} when either is
     * known. The metadata spans are likewise not consulted here; read them with
     * {@link #metadataFrom(FixedWidthCodec, byte[])}.
     *
     * @param codec the fixed-width codec, carrying the code page explicitly
     * @param image exactly {@value #SYMBOLIC_MAP_LENGTH} bytes
     * @return the request the image denotes, never {@code null}
     * @throws NullPointerException     if {@code codec} or {@code image} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly
     *                                  {@value #SYMBOLIC_MAP_LENGTH} bytes long
     */
    public static ReportRequestRequest fromSymbolicMap(FixedWidthCodec codec, byte[] image) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to read "
                + SYMBOLIC_MAP_INPUT_GROUP + ": the code page of a fixed-width image must be stated "
                + "explicitly and is never derived from the platform");
        Objects.requireNonNull(image, "A " + SYMBOLIC_MAP_LENGTH + "-byte image is required to read "
                + SYMBOLIC_MAP_INPUT_GROUP + "; call empty() for a freshly initialised map");
        FixedWidthRecord record = codec.wrap(image, LAYOUT);
        List<String> values = new ArrayList<>(FIELD_COUNT);
        for (ScreenField field : ScreenField.values()) {
            values.add(codec.readPicX(record, field.inputSpan()));
        }
        return fromFieldValues(values, null, SPACE.repeat(AID_LENGTH));
    }

    /**
     * Reads the {@code xxxL} and {@code xxxF} metadata out of a
     * {@value #SYMBOLIC_MAP_LENGTH}-byte image of {@code 01 CORPT0AI}.
     *
     * <p>Separate from {@link #fromSymbolicMap(FixedWidthCodec, byte[])} because metadata is not
     * payload: keeping the two apart is what stops a length item or an attribute byte from ever
     * becoming a JSON property. Each halfword is decoded from its raw bytes, so a stored {@code -1}
     * reads back as {@code -1}.
     *
     * @param codec the fixed-width codec, carrying the code page explicitly
     * @param image exactly {@value #SYMBOLIC_MAP_LENGTH} bytes
     * @return the metadata the image carries, complete for all {@value #FIELD_COUNT} fields
     * @throws NullPointerException     if {@code codec} or {@code image} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly
     *                                  {@value #SYMBOLIC_MAP_LENGTH} bytes long
     */
    public static SymbolicMapMetadata metadataFrom(FixedWidthCodec codec, byte[] image) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to read the metadata items of "
                + SYMBOLIC_MAP_INPUT_GROUP + "; the code page is never defaulted");
        Objects.requireNonNull(image, "A " + SYMBOLIC_MAP_LENGTH + "-byte image is required to read "
                + "the metadata items of " + SYMBOLIC_MAP_INPUT_GROUP);
        FixedWidthRecord record = codec.wrap(image, LAYOUT);
        Map<ScreenField, FieldMetadata> entries = new EnumMap<>(ScreenField.class);
        for (ScreenField field : ScreenField.values()) {
            entries.put(field, new FieldMetadata(
                    halfwordValue(record.readSpanBytes(field.lengthSpan())),
                    record.readSpan(field.flagSpan())));
        }
        return new SymbolicMapMetadata(entries);
    }

    // =================================================================================================
    // Private helpers. Each rule is stated once and shared by every field it governs, so that all
    // seventeen are held to an identical standard and every guard is individually reachable from a test.
    // =================================================================================================

    /**
     * Completes a {@code null} screen value with the field's {@code SPACES} and refuses one wider than
     * the copybook declares.
     *
     * <p>A shorter value is accepted and is padded on the right by the codec when the image is
     * rendered, exactly as a COBOL {@code MOVE} into a wider {@code PIC X} receiver pads. Only an
     * over-long value is refused.
     */
    private static String normalise(String value, ScreenField field) {
        if (value == null) {
            return field.spaces();
        }
        if (value.length() > field.declaredLength()) {
            throw new IllegalArgumentException("Field " + field.inputItem() + " is declared PIC X("
                    + field.declaredLength() + ") but was given " + value.length()
                    + " character(s): '" + value + "'. " + SYMBOLIC_MAP_INPUT_GROUP + " is "
                    + SYMBOLIC_MAP_LENGTH + " bytes and cannot hold the surplus, and a 3270 RECEIVE "
                    + "MAP cannot deliver it. To shorten the value deliberately, pass it through "
                    + "FixedWidthCodec.movePicX(value, " + field.declaredLength() + "), which "
                    + "truncates on the right as a COBOL alphanumeric MOVE does");
        }
        return value;
    }

    /**
     * Encodes a {@code COMP PIC S9(4)} halfword: two bytes, big-endian, two's complement, exactly as
     * z/Architecture stores a binary halfword. {@code -1} becomes {@code X'FFFF'}.
     *
     * <p>Hand-written and deliberately not delegated, because {@link FixedWidthCodec} models
     * {@code PICTURE} categories - character data and zoned {@code DISPLAY} digits - and a binary
     * halfword is neither. The conversion is two shifts wide and is diffable against the copybook's
     * {@code COMP} usage, which is the whole point of keeping such things hand-written.
     */
    private static byte[] halfword(short value) {
        return new byte[] {(byte) (value >> 8), (byte) value};
    }

    /**
     * Decodes a {@code COMP PIC S9(4)} halfword from two big-endian bytes. The cast to {@code short}
     * is what restores the sign, so {@code X'FFFF'} reads back as {@code -1} rather than as 65535.
     */
    private static short halfwordValue(byte[] image) {
        return (short) (((image[0] & 0xFF) << 8) | (image[1] & 0xFF));
    }

    // =================================================================================================
    // Metadata carriers. Validation and highlight metadata - xxxL, xxxF and xxxA - modelled as real
    // types and kept off the wire by not being components of the request.
    // =================================================================================================

    /**
     * The {@code xxxL} length item and the {@code xxxF} / {@code xxxA} attribute byte of one screen
     * field: metadata, never payload.
     *
     * <h2>{@code xxxL} is signed, and is written as often as it is read</h2>
     *
     * {@code 02 xxxL COMP PIC S9(4)} is a <strong>signed</strong> binary halfword, so it is modelled
     * as a {@code short}: a sixteen-bit two's-complement integer, which is what {@code COMP S9(4)}
     * actually is. Making it unsigned, or clamping it at zero, would break the field's primary use.
     * On input CICS reports the number of bytes the terminal sent, but on output the item is the
     * cursor-positioning device: {@code CORPT00C} performs {@code MOVE -1 TO <field>L} at
     * <strong>twenty-two</strong> sites - lines 180, 192, 264, 271, 278, 285, 292, 299, 334, 343, 351,
     * 360, 369, 377, 403, 423, 441, 453, 472, 492, 533 and 635 - to put the cursor on the field the
     * operator must correct. {@value #CURSOR_POSITION} therefore has to be representable, and
     * {@link #cursorRequested()} names the state rather than leaving {@code -1} as a bare magic
     * number at every call site.
     *
     * <h2>{@code xxxA} is not a second byte</h2>
     *
     * The copybook writes {@code 02 FILLER REDEFINES xxxF.} with {@code 03 xxxA PICTURE X.} nested
     * inside it, so {@code xxxF} and {@code xxxA} are one byte under two names - a flag view and an
     * attribute view. {@link #attribute()} is consequently an alias of {@link #flag()} rather than a
     * separate component: modelling them as two fields would double the byte and let the two views
     * disagree, which the storage cannot do.
     *
     * @param length the {@code xxxL} halfword: the received length on input,
     *               {@value #CURSOR_POSITION} to request the cursor on output
     * @param flag   the {@code xxxF} byte, exactly one character; {@code null} becomes
     *               {@code LOW-VALUES}
     */
    public record FieldMetadata(short length, String flag) {

        /**
         * The CICS cursor-positioning value. Moving {@value #CURSOR_POSITION} into a length item asks
         * BMS to leave the cursor in that field when the map is sent.
         */
        public static final short CURSOR_POSITION = -1;

        /**
         * The length of an untouched field: zero. This is what a freshly initialised symbolic map
         * holds and what CICS reports for a field the operator did not modify.
         */
        public static final short UNSET_LENGTH = 0;

        /** The {@code LOW-VALUES} flag byte, {@code X'00'} - an untouched, unattributed field. */
        public static final String LOW_VALUE_FLAG = LOW_VALUE;

        /** Width of the {@code xxxF} byte: {@value #FLAG_WIDTH}. */
        public static final int FLAG_WIDTH = FLAG_ITEM_LENGTH;

        /**
         * Normalises the flag byte, so an instance either exists with exactly one character or does
         * not exist at all.
         *
         * @throws IllegalArgumentException if {@code flag} is not exactly {@value #FLAG_WIDTH}
         *                                  character long
         */
        public FieldMetadata {
            flag = normaliseFlag(flag);
        }

        /**
         * Freshly initialised metadata: length {@value #UNSET_LENGTH}, flag {@code LOW-VALUES}.
         *
         * @return the initial metadata of one field, never {@code null}
         */
        public static FieldMetadata initial() {
            return new FieldMetadata(UNSET_LENGTH, LOW_VALUE_FLAG);
        }

        /**
         * Metadata asking for the cursor: length {@value #CURSOR_POSITION}, flag {@code LOW-VALUES}.
         * This is the {@code MOVE -1 TO <field>L} shape.
         *
         * @return cursor-requesting metadata, never {@code null}
         */
        public static FieldMetadata cursor() {
            return new FieldMetadata(CURSOR_POSITION, LOW_VALUE_FLAG);
        }

        /**
         * The {@code xxxA} view of the flag byte - the same byte {@link #flag()} returns, under the
         * name a program uses when it writes an attribute through it.
         *
         * @return the attribute byte, exactly {@value #FLAG_WIDTH} character
         */
        public String attribute() {
            return flag;
        }

        /**
         * Whether this field is asking for the cursor, that is whether {@link #length()} is
         * {@value #CURSOR_POSITION}.
         *
         * @return {@code true} when the cursor is requested on this field
         */
        public boolean cursorRequested() {
            return length == CURSOR_POSITION;
        }

        /**
         * A copy carrying a different length.
         *
         * @param newLength the new {@code xxxL} halfword
         * @return new metadata, never {@code null}
         */
        public FieldMetadata withLength(short newLength) {
            return new FieldMetadata(newLength, flag);
        }

        /**
         * A copy carrying a different flag byte.
         *
         * @param newFlag the new {@code xxxF} byte; {@code null} becomes {@code LOW-VALUES}
         * @return new metadata, never {@code null}
         * @throws IllegalArgumentException if {@code newFlag} is not exactly one character
         */
        public FieldMetadata withFlag(String newFlag) {
            return new FieldMetadata(length, newFlag);
        }

        /**
         * A copy carrying a different attribute byte. Identical to
         * {@link #withFlag(String)} because {@code xxxA} redefines {@code xxxF}; both names exist so a
         * call site can say which view it means.
         *
         * @param newAttribute the new {@code xxxA} byte; {@code null} becomes {@code LOW-VALUES}
         * @return new metadata, never {@code null}
         * @throws IllegalArgumentException if {@code newAttribute} is not exactly one character
         */
        public FieldMetadata withAttribute(String newAttribute) {
            return withFlag(newAttribute);
        }

        /** Completes a {@code null} flag with {@code LOW-VALUES} and refuses any other width. */
        private static String normaliseFlag(String value) {
            if (value == null) {
                return LOW_VALUE_FLAG;
            }
            if (value.length() != FLAG_WIDTH) {
                throw new IllegalArgumentException("A symbolic-map flag item is declared PICTURE X, "
                        + "so it holds exactly " + FLAG_WIDTH + " character, but was given "
                        + value.length() + ": '" + value + "'");
            }
            return value;
        }
    }

    /**
     * The {@code xxxL} and {@code xxxF} / {@code xxxA} metadata of all {@value #FIELD_COUNT} screen
     * fields of {@value #SYMBOLIC_MAP_INPUT_GROUP}.
     *
     * <h2>Why this is not a component of the request</h2>
     *
     * A record's components are its JSON properties. Metadata is emphatically not payload - the
     * migration's field-mapping rule is that the {@code xxxL}, {@code xxxF} and {@code xxxA} items
     * become validation and highlight metadata while only the {@code xxxI} items become payload - so
     * the metadata is modelled as a companion type instead of a component. Keeping it out of the
     * record excludes it from the wire by construction rather than by annotation, and it keeps the
     * JSON round trip provably lossless: there is no property that serialisation drops and
     * deserialisation cannot restore. {@link ReportRequestRequest#toSymbolicMap(FixedWidthCodec,
     * SymbolicMapMetadata)} takes it as an argument so the complete
     * {@value #SYMBOLIC_MAP_LENGTH}-byte image can still be produced, and
     * {@link ReportRequestRequest#metadataFrom(FixedWidthCodec, byte[])} reads it back.
     *
     * <h2>Complete or absent, never partial</h2>
     *
     * The symbolic map is storage: all {@value #FIELD_COUNT} length items and all
     * {@value #FIELD_COUNT} flag bytes exist whether or not a program has assigned to them. A partial
     * map therefore has no meaning in COBOL, and the constructor refuses one rather than letting a
     * missing entry surface later as a null. That is also why every accessor here can return a value
     * without a null check.
     *
     * @param entries one {@link FieldMetadata} per {@link ScreenField}; defensively copied into an
     *                immutable {@code EnumMap}, so the carrier cannot be mutated after construction
     *                and iterates in symbolic-map declaration order
     */
    public record SymbolicMapMetadata(Map<ScreenField, FieldMetadata> entries) {

        /**
         * Copies the supplied entries defensively and verifies that every field is present.
         *
         * @throws NullPointerException     if {@code entries} is {@code null}, or contains a
         *                                  {@code null} key or value
         * @throws IllegalArgumentException if any of the {@value #FIELD_COUNT} fields is missing
         */
        public SymbolicMapMetadata {
            entries = defensiveCopy(entries);
        }

        /**
         * Freshly initialised metadata for every field: length {@value FieldMetadata#UNSET_LENGTH},
         * flag {@code LOW-VALUES}. This is the state {@link FixedWidthCodec#newRecord(RecordLayout)}
         * would leave the metadata spans in, expressed as values.
         *
         * @return complete initial metadata, never {@code null}
         */
        public static SymbolicMapMetadata initial() {
            Map<ScreenField, FieldMetadata> entries = new EnumMap<>(ScreenField.class);
            for (ScreenField field : ScreenField.values()) {
                entries.put(field, FieldMetadata.initial());
            }
            return new SymbolicMapMetadata(entries);
        }

        /**
         * The metadata of one field.
         *
         * @param field the screen field
         * @return that field's metadata, never {@code null}
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public FieldMetadata metadata(ScreenField field) {
            Objects.requireNonNull(field, "A ScreenField is required to read symbolic-map metadata");
            return entries.get(field);
        }

        /**
         * The {@code xxxL} halfword of one field.
         *
         * @param field the screen field
         * @return the length item's value, {@value FieldMetadata#CURSOR_POSITION} included
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public short length(ScreenField field) {
            return metadata(field).length();
        }

        /**
         * The {@code xxxF} byte of one field.
         *
         * @param field the screen field
         * @return the flag byte, exactly one character
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public String flag(ScreenField field) {
            return metadata(field).flag();
        }

        /**
         * A copy with one field's metadata replaced.
         *
         * @param field    the screen field to replace
         * @param metadata its new metadata
         * @return new metadata for the whole map, never {@code null}
         * @throws NullPointerException if either argument is {@code null}
         */
        public SymbolicMapMetadata with(ScreenField field, FieldMetadata metadata) {
            Objects.requireNonNull(field, "A ScreenField is required to replace symbolic-map "
                    + "metadata");
            Objects.requireNonNull(metadata, "FieldMetadata is required; a symbolic map has a length "
                    + "item and a flag byte for every one of its " + FIELD_COUNT + " fields");
            Map<ScreenField, FieldMetadata> next = new EnumMap<>(ScreenField.class);
            next.putAll(entries);
            next.put(field, metadata);
            return new SymbolicMapMetadata(next);
        }

        /**
         * A copy that asks for the cursor on one field, the {@code MOVE -1 TO <field>L} idiom.
         *
         * <p>The field's flag byte is left as it is, because the two items are independent: the COBOL
         * moves {@code -1} into the length item without touching the attribute.
         *
         * @param field the screen field to place the cursor on
         * @return new metadata for the whole map, never {@code null}
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public SymbolicMapMetadata withCursorAt(ScreenField field) {
            return with(field, metadata(field).withLength(FieldMetadata.CURSOR_POSITION));
        }

        /**
         * A copy carrying a different {@code xxxL} halfword for one field.
         *
         * @param field     the screen field
         * @param newLength the new length value
         * @return new metadata for the whole map, never {@code null}
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public SymbolicMapMetadata withLength(ScreenField field, short newLength) {
            return with(field, metadata(field).withLength(newLength));
        }

        /**
         * A copy carrying a different {@code xxxF} byte for one field.
         *
         * @param field   the screen field
         * @param newFlag the new flag byte; {@code null} becomes {@code LOW-VALUES}
         * @return new metadata for the whole map, never {@code null}
         * @throws NullPointerException     if {@code field} is {@code null}
         * @throws IllegalArgumentException if {@code newFlag} is not exactly one character
         */
        public SymbolicMapMetadata withFlag(ScreenField field, String newFlag) {
            return with(field, metadata(field).withFlag(newFlag));
        }

        /**
         * A copy carrying a different {@code xxxA} byte for one field. Identical to
         * {@link #withFlag(ScreenField, String)}, because the attribute view redefines the flag byte.
         *
         * @param field        the screen field
         * @param newAttribute the new attribute byte; {@code null} becomes {@code LOW-VALUES}
         * @return new metadata for the whole map, never {@code null}
         * @throws NullPointerException     if {@code field} is {@code null}
         * @throws IllegalArgumentException if {@code newAttribute} is not exactly one character
         */
        public SymbolicMapMetadata withAttribute(ScreenField field, String newAttribute) {
            return withFlag(field, newAttribute);
        }

        /**
         * Copies into a fresh {@code EnumMap} and then wraps it unmodifiable, which gives deep
         * immutability because {@link FieldMetadata} is itself an immutable record. An
         * {@code EnumMap} is used rather than {@code Map.copyOf} so iteration follows symbolic-map
         * declaration order, and it is populated with {@code putAll} rather than the copy constructor
         * because {@code EnumMap}'s copy constructor rejects an empty non-enum map instead of
         * reporting the real problem, which is incompleteness.
         */
        private static Map<ScreenField, FieldMetadata> defensiveCopy(
                Map<ScreenField, FieldMetadata> source) {
            Objects.requireNonNull(source, "Metadata entries are required; call "
                    + "SymbolicMapMetadata.initial() for a freshly initialised symbolic map");
            Map<ScreenField, FieldMetadata> copy = new EnumMap<>(ScreenField.class);
            copy.putAll(source);
            if (copy.size() != FIELD_COUNT) {
                throw new IllegalArgumentException(SYMBOLIC_MAP_INPUT_GROUP + " declares a length "
                        + "item and a flag byte for every one of its " + FIELD_COUNT + " fields, so "
                        + "metadata must be complete, but only " + copy.size() + " field(s) were "
                        + "supplied. Start from SymbolicMapMetadata.initial() and replace what you "
                        + "need");
            }
            return Collections.unmodifiableMap(copy);
        }
    }
}
