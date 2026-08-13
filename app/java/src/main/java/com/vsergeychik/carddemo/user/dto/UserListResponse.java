package com.vsergeychik.carddemo.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import java.util.List;
import java.util.Objects;

/**
 * The outbound payload of {@code GET /api/users} - CICS transaction {@code CU00}, program
 * {@code app/cbl/COUSR00C.cbl} - as an immutable value.
 *
 * <p>It is a field-for-field projection of the {@code xxxO} items of {@code 01 COUSR0AO} in
 * {@code app/cpy-bms/COUSR00.CPY} (line 373 onwards), carrying {@value #MAP_FIELD_COUNT}
 * map-derived members, plus the {@code CU00} paging context appended to the communication area by
 * {@code app/cbl/COUSR00C.cbl} lines 65-75 and the stateless navigation contract that replaces
 * {@code EXEC CICS XCTL}.
 *
 * <h2>The name says menu; the source is a paginated list</h2>
 *
 * The mandated controller for {@code COUSR00C} is {@code UserMenuController}, but the program lists
 * users a page at a time - {@code README.md} lines 213-231 document {@code CU00} as "List Users" -
 * so the payload pair is named {@code UserList*}. The migration rule is that the name comes from the
 * build prompt and the behaviour comes from the source; nothing here is reshaped to fit the name.
 *
 * <h2>Why exactly {@value #MAP_FIELD_COUNT} map-derived members</h2>
 *
 * Three independent measurements of this screen agree, and that agreement is the whole basis for the
 * count:
 *
 * <ol>
 *   <li>{@code app/bms/COUSR00.bms} declares 89 {@code DFHMDF} fields, of which exactly
 *       {@value #MAP_FIELD_COUNT} carry a name label. The other 30 are unlabelled literal
 *       {@code INITIAL} furniture - {@code 'Tran:'}, {@code 'Date:'}, the column headings, the
 *       function-key legend - which CICS paints and no program ever addresses, so they are not
 *       fields and must not become members.</li>
 *   <li>{@code app/cpy-bms/COUSR00.CPY} declares {@value #MAP_FIELD_COUNT} {@code xxxI} items under
 *       {@code 01 COUSR0AI} and {@value #MAP_FIELD_COUNT} {@code xxxO} items under
 *       {@code 01 COUSR0AO}.</li>
 *   <li>Field by field, the labelled {@code DFHMDF} names and the copybook names match one for one,
 *       in the same order, and every {@code DFHMDF LENGTH} equals the corresponding
 *       {@code PICTURE} width. There is no field in one artefact that is missing from the other and
 *       no width disagreement anywhere.</li>
 * </ol>
 *
 * {@link #FIELD_NAMES} holds those {@value #MAP_FIELD_COUNT} names as literals in map order, so the
 * count and the spelling are assertable rather than merely asserted in prose.
 *
 * <h2>Geometry of the symbolic map</h2>
 *
 * Both views are {@value #SYMBOLIC_MAP_LENGTH} bytes wide, and they are the same
 * {@value #SYMBOLIC_MAP_LENGTH} bytes, because {@code COUSR0AO REDEFINES COUSR0AI} at an identical
 * stride:
 *
 * <table border="1">
 *   <caption>How {@value #SYMBOLIC_MAP_LENGTH} is reached</caption>
 *   <tr><th>Span</th><th>Per field</th><th>Bytes</th></tr>
 *   <tr><td>{@code TIOAPFX} prefix - {@code 02 FILLER PIC X(12)}, once</td><td>-</td>
 *       <td>12</td></tr>
 *   <tr><td>Input view control bytes: {@code xxxL COMP PIC S9(4)} (2) + {@code xxxF PICTURE X} (1),
 *       redefined as {@code xxxA}, + {@code FILLER PICTURE X(4)}</td><td>7</td>
 *       <td>7 x {@value #MAP_FIELD_COUNT} = 413</td></tr>
 *   <tr><td>Output view control bytes: {@code FILLER PICTURE X(3)} + {@code xxxC} + {@code xxxP}
 *       + {@code xxxH} + {@code xxxV}, one byte each</td><td>7</td>
 *       <td>7 x {@value #MAP_FIELD_COUNT} = 413</td></tr>
 *   <tr><td>Data: 124 header + 500 rows (10 x 50) + 78 message</td><td>-</td><td>702</td></tr>
 *   <tr><td><strong>Total</strong></td><td></td>
 *       <td><strong>{@value #SYMBOLIC_MAP_LENGTH}</strong></td></tr>
 * </table>
 *
 * The two seven-byte control groups are the reason the redefinition is legal, and the reason the
 * quirk described next has no observable consequence.
 *
 * <h2>The program writes the input view; that is not a defect to correct</h2>
 *
 * {@code COUSR00C} moves its <em>output</em> values into the {@code I} items rather than the
 * {@code O} items - {@code MOVE SEC-USR-ID TO USRID01I OF COUSR0AI} at line 388,
 * {@code MOVE CDEMO-CU00-PAGE-NUM TO PAGENUMI OF COUSR0AI} at line 325, and the whole of
 * {@code INITIALIZE-USER-DATA} from line 446 - and then sends {@code FROM(COUSR0AO)} at line 528.
 * Because the two views redefine the same storage at the same stride, writing {@code USRID01I} and
 * writing {@code USRID01O} put the same bytes in the same place, so the field <em>set</em> is
 * identical whichever view is named. Only three fields are written through the {@code O} view
 * explicitly: {@code TITLE01O}, {@code TITLE02O}, {@code TRNNAMEO}, {@code PGMNAMEO} and
 * {@code CURDATEO} in {@code POPULATE-HEADER-INFO}, {@code USRIDINO} at lines 232 and 326, and
 * {@code ERRMSGO} at line 526. The projection is taken from the {@code O} items because those are
 * the outbound names; the quirk is recorded here rather than tidied away.
 *
 * <h2>The header and paging block - 8 members</h2>
 *
 * <table border="1">
 *   <caption>Header fields, in map order</caption>
 *   <tr><th>Map field</th><th>Output item</th><th>Picture</th><th>Member</th><th>Filled from</th></tr>
 *   <tr><td>{@code TRNNAME}</td><td>{@code TRNNAMEO}</td><td>{@code X(4)}</td>
 *       <td>{@link #trnName()}</td><td>{@code WS-TRANID} = {@value #TRANSACTION_ID}</td></tr>
 *   <tr><td>{@code TITLE01}</td><td>{@code TITLE01O}</td><td>{@code X(40)}</td>
 *       <td>{@link #title01()}</td><td>{@code CCDA-TITLE01} of {@code COTTL01Y}</td></tr>
 *   <tr><td>{@code CURDATE}</td><td>{@code CURDATEO}</td><td>{@code X(8)}</td>
 *       <td>{@link #curDate()}</td><td>{@code WS-CURDATE-MM-DD-YY}</td></tr>
 *   <tr><td>{@code PGMNAME}</td><td>{@code PGMNAMEO}</td><td>{@code X(8)}</td>
 *       <td>{@link #pgmName()}</td><td>{@code WS-PGMNAME} = {@value #PROGRAM_NAME}</td></tr>
 *   <tr><td>{@code TITLE02}</td><td>{@code TITLE02O}</td><td>{@code X(40)}</td>
 *       <td>{@link #title02()}</td><td>{@code CCDA-TITLE02} of {@code COTTL01Y}</td></tr>
 *   <tr><td>{@code CURTIME}</td><td>{@code CURTIMEO}</td><td>{@code X(8)}</td>
 *       <td>{@link #curTime()}</td><td>{@code WS-CURTIME-HH-MM-SS}</td></tr>
 *   <tr><td>{@code PAGENUM}</td><td>{@code PAGENUMO}</td><td>{@code X(8)}</td>
 *       <td>{@link #pageNum()}</td><td>{@code CDEMO-CU00-PAGE-NUM}, line 325</td></tr>
 *   <tr><td>{@code USRIDIN}</td><td>{@code USRIDINO}</td><td>{@code X(8)}</td>
 *       <td>{@link #usrIdIn()}</td><td>{@code SPACE} after a successful page, lines 232 and 326</td></tr>
 * </table>
 *
 * <h2>The ten repeating rows - 50 members, flat and never a variable-length list</h2>
 *
 * The screen is ten rows of five cells, declared in the map as
 * {@code SEL000n}, {@code USRIDnn}, {@code FNAMEnn}, {@code LNAMEnn}, {@code UTYPEnn} for
 * {@code nn} = {@code 01}..{@code 10}. They are modelled as 50 flat members so that every cell
 * appears in the payload under its own map-derived name, and so that the one-based-to-zero-based
 * conversion that a list would introduce cannot silently shift a row. The four data cells are
 * filled from the 80-byte {@code SEC-USER-DATA} record of {@code app/cpy/CSUSR01Y.cpy}:
 * {@code SEC-USR-ID X(08)}, {@code SEC-USR-FNAME X(20)}, {@code SEC-USR-LNAME X(20)} and
 * {@code SEC-USR-TYPE X(01)}, by {@code POPULATE-USER-DATA} at lines 388, 391, 392 and 393.
 *
 * <p><strong>The selection column is spelled with four digits.</strong> The map declares
 * {@code SEL0001} through {@code SEL0010} while the other four columns are {@code USRID01} through
 * {@code UTYPE10} with two. That asymmetry is how the screen is defined and it is preserved
 * verbatim; harmonising the spelling would break the field-for-field trace to
 * {@code app/bms/COUSR00.bms}.
 *
 * <p><strong>{@code SEL000n} is never written by the program.</strong> It is echoed user input.
 * {@code COUSR00C} only ever reads it - lines 141-184 copy the ticked row's
 * {@code SEL000nI} into {@code CDEMO-CU00-USR-SEL-FLG} and its {@code USRIDnnI} into
 * {@code CDEMO-CU00-USR-SELECTED}.
 *
 * <p><strong>A short final page still carries ten rows.</strong>
 * {@code PROCESS-PAGE-FORWARD} runs {@code INITIALIZE-USER-DATA} for {@code WS-IDX} 1 through 10
 * before reading, moving {@code SPACES} into {@code USRIDnnI}, {@code FNAMEnnI}, {@code LNAMEnnI}
 * and {@code UTYPEnnI}; the rows the browse does not reach keep those spaces. A row is therefore
 * <em>blanked</em>, never dropped, and a member is never {@code null} where the screen holds
 * spaces. {@link Builder#blankRow(int)} reproduces that exactly - including the detail that
 * {@code INITIALIZE-USER-DATA} leaves the selection cell alone, because it blanks only the four
 * data cells.
 *
 * <h2>The message line is 78 bytes, and the truncation happens elsewhere</h2>
 *
 * {@code ERRMSG} is {@code PIC X(78)} in both the map and the copybook, while
 * {@code app/cbl/COUSR00C.cbl} line 38 declares {@code WS-MESSAGE PIC X(80)} and line 526 does
 * {@code MOVE WS-MESSAGE TO ERRMSGO OF COUSR0AO}. A COBOL alphanumeric {@code MOVE} into a shorter
 * receiver truncates on the right, so the last two characters of an 80-byte message are lost on the
 * way to the screen. This type declares the receiver at its real width of
 * {@value #ERRMSG_LENGTH} and performs no truncation of its own: shortening a message is the
 * controller's decision, taken through the shared codec, and a value that does not fit is rejected
 * here rather than silently trimmed.
 *
 * <h2>The {@code CU00} paging context lives here, not in the shared communication area</h2>
 *
 * {@code app/cbl/COUSR00C.cbl} lines 65-75 append a further group under the same
 * {@code 01 CARDDEMO-COMMAREA}, immediately after {@code COPY COCOM01Y.}:
 *
 * <pre>
 *  05 CDEMO-CU00-INFO.
 *     10 CDEMO-CU00-USRID-FIRST     PIC X(08).
 *     10 CDEMO-CU00-USRID-LAST      PIC X(08).
 *     10 CDEMO-CU00-PAGE-NUM        PIC 9(08).
 *     10 CDEMO-CU00-NEXT-PAGE-FLG   PIC X(01) VALUE 'N'.
 *        88 NEXT-PAGE-YES                     VALUE 'Y'.
 *        88 NEXT-PAGE-NO                      VALUE 'N'.
 *     10 CDEMO-CU00-USR-SEL-FLG     PIC X(01).
 *     10 CDEMO-CU00-USR-SELECTED    PIC X(08).
 * </pre>
 *
 * 8 + 8 + 8 + 1 + 1 + 8 = {@value #CU00_INFO_LENGTH} bytes, so the communication area this one
 * transaction passes is {@value #CU00_COMMAREA_LENGTH} bytes, not the
 * {@value NavigationContext#COMMAREA_LENGTH} that {@link NavigationContext} models.
 *
 * <p>The extension belongs to {@code CU00} alone and is therefore declared here, on the payload of
 * the screen that owns it. {@link NavigationContext} is <strong>referenced, never widened</strong>:
 * it is exactly {@value NavigationContext#COMMAREA_LENGTH} bytes and is copied by all seventeen
 * online programs, so a field added there would change the byte image of every other screen.
 *
 * <p>{@code CDEMO-CU00-PAGE-NUM} is {@code PIC 9(08)} - unsigned, no {@code V}, no scale - so it is
 * an {@code int}. It is not, and must not be confused with, the {@code PAGENUM PIC X(8)} screen
 * text field above it: line 325 moves the numeric field into the character field, which renders it
 * as eight digit characters such as {@code 00000001}. Both are carried, because both are part of
 * what the transaction hands back.
 *
 * <p>{@code CDEMO-CU00-USRID-FIRST} and {@code CDEMO-CU00-USRID-LAST} are the browse cursors.
 * {@code POPULATE-USER-DATA} sets the first from row 1 in a single {@code MOVE} with two receivers
 * at lines 388-389 and the last from row 10 at lines 435-436; {@code PROCESS-PF7-KEY} and
 * {@code PROCESS-PF8-KEY} start the next browse from them. The client returns them unchanged on the
 * following call, which is what makes paging work without server-side state.
 *
 * <h2>Statelessness, and how {@code XCTL} became a response field</h2>
 *
 * CICS is pseudo-conversational: the transaction paints the screen, ends, and is re-entered from the
 * beginning. Every piece of conversation state therefore travels in the payload - this response
 * carries the communication area, the {@code CU00} extension and the screen's own field values -
 * and none of it is kept on the server. There is deliberately no {@code HttpSession}, no
 * {@code @SessionAttributes}, no {@code @SessionScope}, no {@code ThreadLocal}, no static cache and
 * no mutable static field anywhere in this type.
 *
 * <p>{@code COUSR00C} lines 186-215 guard on both selection fields being neither spaces nor
 * low-values and then transfer control with {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)}:
 *
 * <table border="1">
 *   <caption>Selection routing, taken verbatim from the source</caption>
 *   <tr><th>Selection</th><th>Source</th><th>Target</th><th>Screen</th></tr>
 *   <tr><td>{@code 'U'} or {@code 'u'}</td><td>line 192</td>
 *       <td>{@value #NEXT_PROGRAM_USER_UPDATE}</td><td>Update User</td></tr>
 *   <tr><td>{@code 'D'} or {@code 'd'}</td><td>line 202</td>
 *       <td>{@value #NEXT_PROGRAM_USER_DELETE}</td><td>Delete User</td></tr>
 *   <tr><td>anything else</td><td>lines 210-214</td><td>no transfer</td>
 *       <td>the message {@code 'Invalid selection. Valid values are U and D'} and the cursor forced
 *           back to {@code USRIDIN}</td></tr>
 *   <tr><td>{@code PF3}, with no prior screen</td><td>lines 508-510</td>
 *       <td>{@value #NEXT_PROGRAM_SIGNON}</td><td>Sign On</td></tr>
 * </table>
 *
 * <strong>The selection is case-insensitive</strong> - the {@code EVALUATE} has a separate
 * {@code WHEN} for the lower-case letter in each case, so {@code 'u'} routes exactly as {@code 'U'}
 * does and {@code 'd'} exactly as {@code 'D'}. Deciding which target applies is
 * {@code UserMenuController}'s work; this type only <em>carries</em> the resolved value in
 * {@link #nextProgram()}, {@link #nextMapset()} and {@link #nextMap()}, and the constants above
 * document the mapping so that neither side has to guess it. This type contains no routing, no
 * paging arithmetic, no browse and no message composition: it is a payload contract.
 *
 * <p>{@link #nextProgram()} is eight characters because {@code CDEMO-TO-PROGRAM} is
 * {@code PIC X(08)}, while {@link #nextMapset()} and {@link #nextMap()} are
 * <strong>seven</strong>, matching {@code CDEMO-LAST-MAPSET} and {@code CDEMO-LAST-MAP} of
 * {@code app/cpy/COCOM01Y.cpy} lines 43-44. Seven is correct rather than sloppy: a BMS symbolic-map
 * group item is a seven-character map name plus a one-character direction suffix, which is why the
 * names actually sent at line 528 are {@code MAP('COUSR0A')} and {@code MAPSET('COUSR00')} - both
 * seven characters.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <ul>
 *   <li><strong>No control-byte member.</strong> The {@code xxxL} input length and cursor signal,
 *       the {@code xxxF} flag byte and its {@code xxxA} attribute redefinition, and the output
 *       view's {@code xxxC} colour, {@code xxxP} programmed-symbol, {@code xxxH} highlight and
 *       {@code xxxV} validation bytes are presentation and terminal mechanics, not payload. With
 *       {@value #MAP_FIELD_COUNT} fields there are {@value #MAP_FIELD_COUNT} of each; none is
 *       exposed. Field highlighting and attribute selection belong to the shared attribute
 *       helpers.</li>
 *   <li><strong>No {@code FILLER}.</strong> Neither the 12-byte {@code TIOAPFX} prefix nor the
 *       {@code FILLER PICTURE X(3)} span that opens each output field group is addressable, so
 *       neither is a member. They are accounted for in the geometry table above, which is where
 *       they matter.</li>
 *   <li><strong>No projection of {@code WS-USER-DATA}.</strong> Lines 56-63 declare a
 *       {@code USER-REC OCCURS 10 TIMES} staging area in working storage whose cells -
 *       {@code USER-SEL X(01)}, {@code USER-ID X(08)}, {@code USER-NAME X(25)},
 *       {@code USER-TYPE X(08)} and three {@code FILLER X(02)} spans - have different widths from
 *       the map's row cells. It is not the map and it is not modelled.</li>
 *   <li><strong>No page size.</strong> Ten rows is the screen, not a setting: the map declares ten
 *       and {@code PROCESS-PAGE-FORWARD} loops {@code UNTIL WS-IDX &gt;= 11}. {@link #ROW_COUNT} is a
 *       constant that records that fact; there is no configurable limit and no settable row
 *       count.</li>
 *   <li><strong>No serialisation or validation annotations.</strong> Field names are carried
 *       through untransformed and space padding is significant, so there is no renaming, no naming
 *       strategy, no null or empty exclusion, no custom serialiser and no trimming. Blank is a
 *       meaningful value on this screen - a blank {@link #usrIdIn()} means "start at the beginning"
 *       per lines 216-220 - so no member is annotated as required or non-blank either. The declared
 *       widths are enforced by the constructor, which gives a better diagnostic than an annotation
 *       would.</li>
 *   <li><strong>No {@code double} and no {@code float}.</strong> The only numeric member is an
 *       unsigned integer picture. There is no monetary value on this screen, and binary floating
 *       point is never used for a COBOL numeric field anywhere in this migration.</li>
 *   <li><strong>No persistence mapping.</strong> This is a screen payload; it carries no entity,
 *       table, identifier or column annotation and no schema of any kind.</li>
 *   <li><strong>No generated accessors.</strong> The record's own accessors are the API. No
 *       annotation processor stands between the copybook and this file, so every width visible here
 *       is a declared literal that can be read straight off the {@code PICTURE} clause.</li>
 * </ul>
 *
 * <h2>Constructing one</h2>
 *
 * The canonical constructor takes all {@value #COMPONENT_COUNT} components and is the single point
 * where widths are enforced, which makes it exact but unwieldy to call. {@link #blank()} produces
 * the initial screen - every character field spaces at its declared width, the page number zero,
 * the next-page flag at its copybook {@code VALUE 'N'} - and {@link #builder()} and
 * {@link #toBuilder()} give a hand-written fluent assembler whose {@link Builder#populateRow} and
 * {@link Builder#blankRow} mirror {@code POPULATE-USER-DATA} and {@code INITIALIZE-USER-DATA} with
 * their one-based row numbers intact.
 *
 * @param trnName              {@code TRNNAMEO PIC X(4)}: the transaction identifier on the screen
 * @param title01              {@code TITLE01O PIC X(40)}: the first title line
 * @param curDate              {@code CURDATEO PIC X(8)}: the current date, {@code mm/dd/yy}
 * @param pgmName              {@code PGMNAMEO PIC X(8)}: the program name on the screen
 * @param title02              {@code TITLE02O PIC X(40)}: the second title line
 * @param curTime              {@code CURTIMEO PIC X(8)}: the current time, {@code hh:mm:ss}
 * @param pageNum              {@code PAGENUMO PIC X(8)}: the displayed page number, as characters
 * @param usrIdIn              {@code USRIDINO PIC X(8)}: the browse-start user identifier
 * @param sel0001              {@code SEL0001O PIC X(1)}: row 1 selection, echoed input
 * @param usrId01              {@code USRID01O PIC X(8)}: row 1 {@code SEC-USR-ID}
 * @param fname01              {@code FNAME01O PIC X(20)}: row 1 {@code SEC-USR-FNAME}
 * @param lname01              {@code LNAME01O PIC X(20)}: row 1 {@code SEC-USR-LNAME}
 * @param utype01              {@code UTYPE01O PIC X(1)}: row 1 {@code SEC-USR-TYPE}
 * @param sel0002              {@code SEL0002O PIC X(1)}: row 2 selection, echoed input
 * @param usrId02              {@code USRID02O PIC X(8)}: row 2 {@code SEC-USR-ID}
 * @param fname02              {@code FNAME02O PIC X(20)}: row 2 {@code SEC-USR-FNAME}
 * @param lname02              {@code LNAME02O PIC X(20)}: row 2 {@code SEC-USR-LNAME}
 * @param utype02              {@code UTYPE02O PIC X(1)}: row 2 {@code SEC-USR-TYPE}
 * @param sel0003              {@code SEL0003O PIC X(1)}: row 3 selection, echoed input
 * @param usrId03              {@code USRID03O PIC X(8)}: row 3 {@code SEC-USR-ID}
 * @param fname03              {@code FNAME03O PIC X(20)}: row 3 {@code SEC-USR-FNAME}
 * @param lname03              {@code LNAME03O PIC X(20)}: row 3 {@code SEC-USR-LNAME}
 * @param utype03              {@code UTYPE03O PIC X(1)}: row 3 {@code SEC-USR-TYPE}
 * @param sel0004              {@code SEL0004O PIC X(1)}: row 4 selection, echoed input
 * @param usrId04              {@code USRID04O PIC X(8)}: row 4 {@code SEC-USR-ID}
 * @param fname04              {@code FNAME04O PIC X(20)}: row 4 {@code SEC-USR-FNAME}
 * @param lname04              {@code LNAME04O PIC X(20)}: row 4 {@code SEC-USR-LNAME}
 * @param utype04              {@code UTYPE04O PIC X(1)}: row 4 {@code SEC-USR-TYPE}
 * @param sel0005              {@code SEL0005O PIC X(1)}: row 5 selection, echoed input
 * @param usrId05              {@code USRID05O PIC X(8)}: row 5 {@code SEC-USR-ID}
 * @param fname05              {@code FNAME05O PIC X(20)}: row 5 {@code SEC-USR-FNAME}
 * @param lname05              {@code LNAME05O PIC X(20)}: row 5 {@code SEC-USR-LNAME}
 * @param utype05              {@code UTYPE05O PIC X(1)}: row 5 {@code SEC-USR-TYPE}
 * @param sel0006              {@code SEL0006O PIC X(1)}: row 6 selection, echoed input
 * @param usrId06              {@code USRID06O PIC X(8)}: row 6 {@code SEC-USR-ID}
 * @param fname06              {@code FNAME06O PIC X(20)}: row 6 {@code SEC-USR-FNAME}
 * @param lname06              {@code LNAME06O PIC X(20)}: row 6 {@code SEC-USR-LNAME}
 * @param utype06              {@code UTYPE06O PIC X(1)}: row 6 {@code SEC-USR-TYPE}
 * @param sel0007              {@code SEL0007O PIC X(1)}: row 7 selection, echoed input
 * @param usrId07              {@code USRID07O PIC X(8)}: row 7 {@code SEC-USR-ID}
 * @param fname07              {@code FNAME07O PIC X(20)}: row 7 {@code SEC-USR-FNAME}
 * @param lname07              {@code LNAME07O PIC X(20)}: row 7 {@code SEC-USR-LNAME}
 * @param utype07              {@code UTYPE07O PIC X(1)}: row 7 {@code SEC-USR-TYPE}
 * @param sel0008              {@code SEL0008O PIC X(1)}: row 8 selection, echoed input
 * @param usrId08              {@code USRID08O PIC X(8)}: row 8 {@code SEC-USR-ID}
 * @param fname08              {@code FNAME08O PIC X(20)}: row 8 {@code SEC-USR-FNAME}
 * @param lname08              {@code LNAME08O PIC X(20)}: row 8 {@code SEC-USR-LNAME}
 * @param utype08              {@code UTYPE08O PIC X(1)}: row 8 {@code SEC-USR-TYPE}
 * @param sel0009              {@code SEL0009O PIC X(1)}: row 9 selection, echoed input
 * @param usrId09              {@code USRID09O PIC X(8)}: row 9 {@code SEC-USR-ID}
 * @param fname09              {@code FNAME09O PIC X(20)}: row 9 {@code SEC-USR-FNAME}
 * @param lname09              {@code LNAME09O PIC X(20)}: row 9 {@code SEC-USR-LNAME}
 * @param utype09              {@code UTYPE09O PIC X(1)}: row 9 {@code SEC-USR-TYPE}
 * @param sel0010              {@code SEL0010O PIC X(1)}: row 10 selection, echoed input; note the
 *                             four-digit spelling
 * @param usrId10              {@code USRID10O PIC X(8)}: row 10 {@code SEC-USR-ID}
 * @param fname10              {@code FNAME10O PIC X(20)}: row 10 {@code SEC-USR-FNAME}
 * @param lname10              {@code LNAME10O PIC X(20)}: row 10 {@code SEC-USR-LNAME}
 * @param utype10              {@code UTYPE10O PIC X(1)}: row 10 {@code SEC-USR-TYPE}
 * @param errMsg               {@code ERRMSGO PIC X(78)}: the message line - 78, not 80
 * @param cdemoCu00UsrIdFirst  {@code CDEMO-CU00-USRID-FIRST PIC X(08)}: backward browse cursor
 * @param cdemoCu00UsrIdLast   {@code CDEMO-CU00-USRID-LAST PIC X(08)}: forward browse cursor
 * @param cdemoCu00PageNum     {@code CDEMO-CU00-PAGE-NUM PIC 9(08)}: the page number as a number
 * @param cdemoCu00NextPageFlg {@code CDEMO-CU00-NEXT-PAGE-FLG PIC X(01)}: {@code 'Y'} or
 *                             {@code 'N'}, default {@code 'N'}
 * @param cdemoCu00UsrSelFlg   {@code CDEMO-CU00-USR-SEL-FLG PIC X(01)}: the ticked row's selection
 * @param cdemoCu00UsrSelected {@code CDEMO-CU00-USR-SELECTED PIC X(08)}: the ticked row's user
 * @param nextProgram          the {@code XCTL} target, {@code CDEMO-TO-PROGRAM PIC X(08)}
 * @param nextMapset           the mapset to display next, {@code PIC X(7)}
 * @param nextMap              the map to display next, {@code PIC X(7)}
 * @param navigationContext    the {@value NavigationContext#COMMAREA_LENGTH}-byte
 *                             {@code CARDDEMO-COMMAREA}, referenced and never widened
 */
public record UserListResponse(@JsonProperty("trnname") String trnName,
                               String title01,
                               @JsonProperty("curdate") String curDate,
                               @JsonProperty("pgmname") String pgmName,
                               String title02,
                               @JsonProperty("curtime") String curTime,
                               @JsonProperty("pagenum") String pageNum,
                               @JsonProperty("usridin") String usrIdIn,
                               String sel0001,
                               @JsonProperty("usrid01") String usrId01,
                               String fname01,
                               String lname01,
                               String utype01,
                               String sel0002,
                               @JsonProperty("usrid02") String usrId02,
                               String fname02,
                               String lname02,
                               String utype02,
                               String sel0003,
                               @JsonProperty("usrid03") String usrId03,
                               String fname03,
                               String lname03,
                               String utype03,
                               String sel0004,
                               @JsonProperty("usrid04") String usrId04,
                               String fname04,
                               String lname04,
                               String utype04,
                               String sel0005,
                               @JsonProperty("usrid05") String usrId05,
                               String fname05,
                               String lname05,
                               String utype05,
                               String sel0006,
                               @JsonProperty("usrid06") String usrId06,
                               String fname06,
                               String lname06,
                               String utype06,
                               String sel0007,
                               @JsonProperty("usrid07") String usrId07,
                               String fname07,
                               String lname07,
                               String utype07,
                               String sel0008,
                               @JsonProperty("usrid08") String usrId08,
                               String fname08,
                               String lname08,
                               String utype08,
                               String sel0009,
                               @JsonProperty("usrid09") String usrId09,
                               String fname09,
                               String lname09,
                               String utype09,
                               String sel0010,
                               @JsonProperty("usrid10") String usrId10,
                               String fname10,
                               String lname10,
                               String utype10,
                               @JsonProperty("errmsg") String errMsg,
                               String cdemoCu00UsrIdFirst,
                               String cdemoCu00UsrIdLast,
                               int cdemoCu00PageNum,
                               String cdemoCu00NextPageFlg,
                               String cdemoCu00UsrSelFlg,
                               String cdemoCu00UsrSelected,
                               String nextProgram,
                               String nextMapset,
                               String nextMap,
                               NavigationContext navigationContext) {

    // =============================================================================================
    // Identity of the screen, taken from app/cbl/COUSR00C.cbl and app/bms/COUSR00.bms.
    // =============================================================================================

    /** {@code WS-TRANID} of {@code app/cbl/COUSR00C.cbl} line 37: the CICS transaction identifier. */
    public static final String TRANSACTION_ID = "CU00";

    /** {@code WS-PGMNAME} of {@code app/cbl/COUSR00C.cbl} line 36: the COBOL program migrated here. */
    public static final String PROGRAM_NAME = "COUSR00C";

    /** {@code MAPSET('COUSR00')} of {@code app/cbl/COUSR00C.cbl} line 529 - seven characters. */
    public static final String MAPSET_NAME = "COUSR00";

    /** {@code MAP('COUSR0A')} of {@code app/cbl/COUSR00C.cbl} line 528 - seven characters. */
    public static final String MAP_NAME = "COUSR0A";

    // =============================================================================================
    // The XCTL targets of app/cbl/COUSR00C.cbl. Recorded as constants so the controller and the
    // client agree on the value this payload carries; the routing decision itself is not made here.
    // =============================================================================================

    /**
     * {@code MOVE 'COUSR02C' TO CDEMO-TO-PROGRAM} at {@code app/cbl/COUSR00C.cbl} line 192, reached
     * from {@code WHEN 'U'} and {@code WHEN 'u'} - the selection is case-insensitive.
     */
    public static final String NEXT_PROGRAM_USER_UPDATE = "COUSR02C";

    /**
     * {@code MOVE 'COUSR03C' TO CDEMO-TO-PROGRAM} at {@code app/cbl/COUSR00C.cbl} line 202, reached
     * from {@code WHEN 'D'} and {@code WHEN 'd'} - the selection is case-insensitive.
     */
    public static final String NEXT_PROGRAM_USER_DELETE = "COUSR03C";

    /**
     * {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM} at {@code app/cbl/COUSR00C.cbl} line 509, the
     * fallback when {@code PF3} is pressed and no prior screen was recorded.
     */
    public static final String NEXT_PROGRAM_SIGNON = "COSGN00C";

    /**
     * The message of {@code WHEN OTHER} at {@code app/cbl/COUSR00C.cbl} lines 210-213, carried
     * verbatim including its spacing, because message text is part of observable behaviour.
     */
    public static final String INVALID_SELECTION_MESSAGE =
            "Invalid selection. Valid values are U and D";

    // =============================================================================================
    // Geometry. Every width below is a literal read straight off a PICTURE clause of
    // app/cpy-bms/COUSR00.CPY and cross-checked against the DFHMDF LENGTH of app/bms/COUSR00.bms.
    // None is derived from a Java field, and none is computed from another width.
    // =============================================================================================

    /** Map-derived members: 8 header + 50 row + 1 message. Matches the labelled {@code DFHMDF} count. */
    public static final int MAP_FIELD_COUNT = 59;

    /** Components of the canonical constructor: {@value #MAP_FIELD_COUNT} map-derived + 6 + 3 + 1. */
    public static final int COMPONENT_COUNT = 69;

    /**
     * Rows on the screen. Ten is <strong>behaviour</strong>: the map declares ten row groups and
     * {@code PROCESS-PAGE-FORWARD} of {@code app/cbl/COUSR00C.cbl} line 300 loops
     * {@code UNTIL WS-IDX &gt;= 11}. It is not configurable and there is no page-size member.
     */
    public static final int ROW_COUNT = 10;

    /** Cells in one row: selection, user identifier, first name, last name, user type. */
    public static final int ROW_FIELD_COUNT = 5;

    /**
     * Width of {@code 01 COUSR0AI} and of {@code 01 COUSR0AO}, which redefines it: the 12-byte
     * {@code TIOAPFX} prefix, seven control bytes per field and 702 bytes of data.
     */
    public static final int SYMBOLIC_MAP_LENGTH = 1127;

    /** {@code TRNNAME}: {@code PIC X(4)}, {@code DFHMDF LENGTH=4} at {@code POS=(1,7)}. */
    public static final int TRNNAME_LENGTH = 4;

    /** {@code TITLE01}: {@code PIC X(40)}, {@code DFHMDF LENGTH=40} at {@code POS=(1,21)}. */
    public static final int TITLE01_LENGTH = 40;

    /** {@code CURDATE}: {@code PIC X(8)}, {@code DFHMDF LENGTH=8} at {@code POS=(1,71)}. */
    public static final int CURDATE_LENGTH = 8;

    /** {@code PGMNAME}: {@code PIC X(8)}, {@code DFHMDF LENGTH=8} at {@code POS=(2,7)}. */
    public static final int PGMNAME_LENGTH = 8;

    /** {@code TITLE02}: {@code PIC X(40)}, {@code DFHMDF LENGTH=40} at {@code POS=(2,21)}. */
    public static final int TITLE02_LENGTH = 40;

    /**
     * {@code CURTIME}: {@code PIC X(8)}, {@code DFHMDF LENGTH=8} at {@code POS=(2,71)}. Eight, not
     * nine: only {@code COSGN00} widens its time field, and this screen is not that screen.
     */
    public static final int CURTIME_LENGTH = 8;

    /**
     * {@code PAGENUM}: {@code PIC X(8)}, {@code DFHMDF LENGTH=8} at {@code POS=(4,71)}. A character
     * field, distinct from the numeric {@code CDEMO-CU00-PAGE-NUM}.
     */
    public static final int PAGENUM_LENGTH = 8;

    /**
     * {@code USRIDIN}: {@code PIC X(8)}, {@code DFHMDF LENGTH=8}, {@code ATTRB=(FSET,NORM,UNPROT)}.
     * The one unprotected header field - the browse-start identifier the user may type.
     */
    public static final int USRIDIN_LENGTH = 8;

    /**
     * {@code SEL000n}: {@code PIC X(1)}, {@code DFHMDF LENGTH=1},
     * {@code ATTRB=(FSET,NORM,UNPROT)}. All ten selection cells share this width.
     */
    public static final int SEL_LENGTH = 1;

    /** {@code USRIDnn}: {@code PIC X(8)}, matching {@code SEC-USR-ID PIC X(08)}. */
    public static final int USRID_LENGTH = 8;

    /** {@code FNAMEnn}: {@code PIC X(20)}, matching {@code SEC-USR-FNAME PIC X(20)}. */
    public static final int FNAME_LENGTH = 20;

    /** {@code LNAMEnn}: {@code PIC X(20)}, matching {@code SEC-USR-LNAME PIC X(20)}. */
    public static final int LNAME_LENGTH = 20;

    /** {@code UTYPEnn}: {@code PIC X(1)}, matching {@code SEC-USR-TYPE PIC X(01)}. */
    public static final int UTYPE_LENGTH = 1;

    /**
     * {@code ERRMSG}: {@code PIC X(78)}, {@code DFHMDF LENGTH=78} at {@code POS=(23,1)}. Seventy
     * eight, while {@code WS-MESSAGE} is {@code PIC X(80)}; the two-character loss happens in the
     * controller's {@code MOVE}, never here.
     */
    public static final int ERRMSG_LENGTH = 78;

    // =============================================================================================
    // The CU00 extension of the communication area: app/cbl/COUSR00C.cbl lines 65-75.
    // =============================================================================================

    /** {@code CDEMO-CU00-USRID-FIRST PIC X(08)}. */
    public static final int CU00_USRID_FIRST_LENGTH = 8;

    /** {@code CDEMO-CU00-USRID-LAST PIC X(08)}. */
    public static final int CU00_USRID_LAST_LENGTH = 8;

    /** {@code CDEMO-CU00-PAGE-NUM PIC 9(08)}: eight unsigned digits, no {@code V}, no scale. */
    public static final int CU00_PAGE_NUM_DIGITS = 8;

    /** {@code CDEMO-CU00-NEXT-PAGE-FLG PIC X(01)}. */
    public static final int CU00_NEXT_PAGE_FLG_LENGTH = 1;

    /** {@code CDEMO-CU00-USR-SEL-FLG PIC X(01)}. */
    public static final int CU00_USR_SEL_FLG_LENGTH = 1;

    /** {@code CDEMO-CU00-USR-SELECTED PIC X(08)}. */
    public static final int CU00_USR_SELECTED_LENGTH = 8;

    /** {@code 05 CDEMO-CU00-INFO}: 8 + 8 + 8 + 1 + 1 + 8 bytes. */
    public static final int CU00_INFO_LENGTH = 34;

    /**
     * The communication area {@code CU00} actually passes:
     * {@value NavigationContext#COMMAREA_LENGTH} bytes of {@code CARDDEMO-COMMAREA} plus
     * {@value #CU00_INFO_LENGTH} bytes of extension. Deliberately expressed against
     * {@link NavigationContext#COMMAREA_LENGTH} rather than as the bare literal 194, so that the
     * arithmetic is visible and any change to the shared area shows up here.
     */
    public static final int CU00_COMMAREA_LENGTH =
            NavigationContext.COMMAREA_LENGTH + CU00_INFO_LENGTH;

    /** {@code 88 NEXT-PAGE-YES VALUE 'Y'} of {@code app/cbl/COUSR00C.cbl} line 71. */
    public static final String NEXT_PAGE_YES = "Y";

    /**
     * {@code 88 NEXT-PAGE-NO VALUE 'N'} of {@code app/cbl/COUSR00C.cbl} line 72, and the
     * {@code VALUE 'N'} the field is initialised to at line 70.
     */
    public static final String NEXT_PAGE_NO = "N";

    // =============================================================================================
    // The navigation contract that replaces EXEC CICS XCTL.
    // =============================================================================================

    /** {@code CDEMO-TO-PROGRAM PIC X(08)} of {@code app/cpy/COCOM01Y.cpy} line 24. */
    public static final int NEXT_PROGRAM_LENGTH = NavigationContext.TO_PROGRAM_LENGTH;

    /** {@code CDEMO-LAST-MAPSET PIC X(7)} of {@code app/cpy/COCOM01Y.cpy} line 44 - seven. */
    public static final int NEXT_MAPSET_LENGTH = NavigationContext.LAST_MAPSET_LENGTH;

    /** {@code CDEMO-LAST-MAP PIC X(7)} of {@code app/cpy/COCOM01Y.cpy} line 43 - seven. */
    public static final int NEXT_MAP_LENGTH = NavigationContext.LAST_MAP_LENGTH;

    // =============================================================================================
    // COBOL field names, spelled exactly as the map spells them. These are the keys the parity
    // differ compares by, so a tidied name would make a real difference invisible. Note that the
    // selection column carries FOUR digits while the other four columns carry two.
    // =============================================================================================

    /** Copybook and map name of {@link #trnName()}. */
    public static final String TRNNAME_FIELD = "TRNNAME";

    /** Copybook and map name of {@link #title01()}. */
    public static final String TITLE01_FIELD = "TITLE01";

    /** Copybook and map name of {@link #curDate()}. */
    public static final String CURDATE_FIELD = "CURDATE";

    /** Copybook and map name of {@link #pgmName()}. */
    public static final String PGMNAME_FIELD = "PGMNAME";

    /** Copybook and map name of {@link #title02()}. */
    public static final String TITLE02_FIELD = "TITLE02";

    /** Copybook and map name of {@link #curTime()}. */
    public static final String CURTIME_FIELD = "CURTIME";

    /** Copybook and map name of {@link #pageNum()}. */
    public static final String PAGENUM_FIELD = "PAGENUM";

    /**
     * Copybook and map name of {@link #usrIdIn()}. {@code COUSR01} spells its equivalent field
     * {@code USERID}; the two are not harmonised, because each traces to its own mapset.
     */
    public static final String USRIDIN_FIELD = "USRIDIN";

    /** Copybook and map name of {@link #errMsg()}. */
    public static final String ERRMSG_FIELD = "ERRMSG";

    /** Copybook name of {@link #cdemoCu00UsrIdFirst()}. */
    public static final String CU00_USRID_FIRST_FIELD = "CDEMO-CU00-USRID-FIRST";

    /** Copybook name of {@link #cdemoCu00UsrIdLast()}. */
    public static final String CU00_USRID_LAST_FIELD = "CDEMO-CU00-USRID-LAST";

    /** Copybook name of {@link #cdemoCu00PageNum()}. */
    public static final String CU00_PAGE_NUM_FIELD = "CDEMO-CU00-PAGE-NUM";

    /** Copybook name of {@link #cdemoCu00NextPageFlg()}. */
    public static final String CU00_NEXT_PAGE_FLG_FIELD = "CDEMO-CU00-NEXT-PAGE-FLG";

    /** Copybook name of {@link #cdemoCu00UsrSelFlg()}. */
    public static final String CU00_USR_SEL_FLG_FIELD = "CDEMO-CU00-USR-SEL-FLG";

    /** Copybook name of {@link #cdemoCu00UsrSelected()}. */
    public static final String CU00_USR_SELECTED_FIELD = "CDEMO-CU00-USR-SELECTED";

    /**
     * The {@value #MAP_FIELD_COUNT} map-derived field names, as literals, in the order
     * {@code app/cpy-bms/COUSR00.CPY} declares them and {@code app/bms/COUSR00.bms} labels them.
     *
     * <p>The list exists so the field inventory of this screen is data rather than prose: its size
     * is the labelled {@code DFHMDF} count, its contents are the labels themselves, and the
     * four-digit selection spelling sitting beside the two-digit data columns is visible at a
     * glance. It is immutable, so exposing it introduces no shared mutable state.
     */
    public static final List<String> FIELD_NAMES = List.of(
            TRNNAME_FIELD, TITLE01_FIELD, CURDATE_FIELD, PGMNAME_FIELD, TITLE02_FIELD,
            CURTIME_FIELD, PAGENUM_FIELD, USRIDIN_FIELD,
            "SEL0001", "USRID01", "FNAME01", "LNAME01", "UTYPE01",
            "SEL0002", "USRID02", "FNAME02", "LNAME02", "UTYPE02",
            "SEL0003", "USRID03", "FNAME03", "LNAME03", "UTYPE03",
            "SEL0004", "USRID04", "FNAME04", "LNAME04", "UTYPE04",
            "SEL0005", "USRID05", "FNAME05", "LNAME05", "UTYPE05",
            "SEL0006", "USRID06", "FNAME06", "LNAME06", "UTYPE06",
            "SEL0007", "USRID07", "FNAME07", "LNAME07", "UTYPE07",
            "SEL0008", "USRID08", "FNAME08", "LNAME08", "UTYPE08",
            "SEL0009", "USRID09", "FNAME09", "LNAME09", "UTYPE09",
            "SEL0010", "USRID10", "FNAME10", "LNAME10", "UTYPE10",
            ERRMSG_FIELD);

    /** The single space this type pads with; {@code PIC X} fields are space filled, never null. */
    private static final String SPACE = " ";

    // =============================================================================================
    // The canonical constructor: the one place a declared width is enforced.
    // =============================================================================================

    /**
     * Validates every component against the width its {@code PICTURE} clause declares.
     *
     * <p>Two rules apply, and they are the same two the shared communication area applies, so the
     * whole module behaves consistently:
     *
     * <ul>
     *   <li><strong>No component may be {@code null}.</strong> There is no null in a COBOL record -
     *       an unfilled field holds spaces, which is exactly why a short final page still carries
     *       ten space-filled rows. Rejecting null here is what makes "never emit null in place of
     *       spaces" structural rather than a hope: a caller that forgets a row gets a diagnostic
     *       naming the field instead of a payload with a hole in it. Start from {@link #blank()} and
     *       fill in what the transaction produced.</li>
     *   <li><strong>No component may exceed its declared width.</strong> A value that does not fit
     *       is an error, not something to shorten quietly: the 80-to-78 narrowing of the message
     *       line is a decision {@code app/cbl/COUSR00C.cbl} line 526 takes with a {@code MOVE}, and
     *       it belongs to the controller and the shared codec. A shorter value is accepted, because
     *       padding a field out to its declared width is likewise the codec's job and not a reason
     *       to reject a value that is otherwise correct.</li>
     * </ul>
     *
     * <p>{@link #cdemoCu00PageNum()} is treated differently because it is a numeric picture rather than
     * a character one. {@code PIC 9(08)} is unsigned and has no scale, so a negative value has nowhere to
     * record its sign and is refused. A value of more than eight digits is <strong>stored, not
     * refused</strong>: a numeric receiver discards the high-order digits that do not fit, and
     * {@code ADD 1 TO CDEMO-CU00-PAGE-NUM} at {@code app/cbl/COUSR00C.cbl:320} is such a store - so a
     * ninth digit is dropped exactly as the receiver drops it, and 100000000 becomes 0.
     *
     * @throws NullPointerException     if any component is {@code null}
     * @throws IllegalArgumentException if a character component is wider than its {@code PICTURE}, or if
     *                                  the page number is negative
     */
    public UserListResponse {
        trnName = requireWidth(trnName, TRNNAME_LENGTH, TRNNAME_FIELD);
        title01 = requireWidth(title01, TITLE01_LENGTH, TITLE01_FIELD);
        curDate = requireWidth(curDate, CURDATE_LENGTH, CURDATE_FIELD);
        pgmName = requireWidth(pgmName, PGMNAME_LENGTH, PGMNAME_FIELD);
        title02 = requireWidth(title02, TITLE02_LENGTH, TITLE02_FIELD);
        curTime = requireWidth(curTime, CURTIME_LENGTH, CURTIME_FIELD);
        pageNum = requireWidth(pageNum, PAGENUM_LENGTH, PAGENUM_FIELD);
        usrIdIn = requireWidth(usrIdIn, USRIDIN_LENGTH, USRIDIN_FIELD);

        sel0001 = requireWidth(sel0001, SEL_LENGTH, "SEL0001");
        usrId01 = requireWidth(usrId01, USRID_LENGTH, "USRID01");
        fname01 = requireWidth(fname01, FNAME_LENGTH, "FNAME01");
        lname01 = requireWidth(lname01, LNAME_LENGTH, "LNAME01");
        utype01 = requireWidth(utype01, UTYPE_LENGTH, "UTYPE01");

        sel0002 = requireWidth(sel0002, SEL_LENGTH, "SEL0002");
        usrId02 = requireWidth(usrId02, USRID_LENGTH, "USRID02");
        fname02 = requireWidth(fname02, FNAME_LENGTH, "FNAME02");
        lname02 = requireWidth(lname02, LNAME_LENGTH, "LNAME02");
        utype02 = requireWidth(utype02, UTYPE_LENGTH, "UTYPE02");

        sel0003 = requireWidth(sel0003, SEL_LENGTH, "SEL0003");
        usrId03 = requireWidth(usrId03, USRID_LENGTH, "USRID03");
        fname03 = requireWidth(fname03, FNAME_LENGTH, "FNAME03");
        lname03 = requireWidth(lname03, LNAME_LENGTH, "LNAME03");
        utype03 = requireWidth(utype03, UTYPE_LENGTH, "UTYPE03");

        sel0004 = requireWidth(sel0004, SEL_LENGTH, "SEL0004");
        usrId04 = requireWidth(usrId04, USRID_LENGTH, "USRID04");
        fname04 = requireWidth(fname04, FNAME_LENGTH, "FNAME04");
        lname04 = requireWidth(lname04, LNAME_LENGTH, "LNAME04");
        utype04 = requireWidth(utype04, UTYPE_LENGTH, "UTYPE04");

        sel0005 = requireWidth(sel0005, SEL_LENGTH, "SEL0005");
        usrId05 = requireWidth(usrId05, USRID_LENGTH, "USRID05");
        fname05 = requireWidth(fname05, FNAME_LENGTH, "FNAME05");
        lname05 = requireWidth(lname05, LNAME_LENGTH, "LNAME05");
        utype05 = requireWidth(utype05, UTYPE_LENGTH, "UTYPE05");

        sel0006 = requireWidth(sel0006, SEL_LENGTH, "SEL0006");
        usrId06 = requireWidth(usrId06, USRID_LENGTH, "USRID06");
        fname06 = requireWidth(fname06, FNAME_LENGTH, "FNAME06");
        lname06 = requireWidth(lname06, LNAME_LENGTH, "LNAME06");
        utype06 = requireWidth(utype06, UTYPE_LENGTH, "UTYPE06");

        sel0007 = requireWidth(sel0007, SEL_LENGTH, "SEL0007");
        usrId07 = requireWidth(usrId07, USRID_LENGTH, "USRID07");
        fname07 = requireWidth(fname07, FNAME_LENGTH, "FNAME07");
        lname07 = requireWidth(lname07, LNAME_LENGTH, "LNAME07");
        utype07 = requireWidth(utype07, UTYPE_LENGTH, "UTYPE07");

        sel0008 = requireWidth(sel0008, SEL_LENGTH, "SEL0008");
        usrId08 = requireWidth(usrId08, USRID_LENGTH, "USRID08");
        fname08 = requireWidth(fname08, FNAME_LENGTH, "FNAME08");
        lname08 = requireWidth(lname08, LNAME_LENGTH, "LNAME08");
        utype08 = requireWidth(utype08, UTYPE_LENGTH, "UTYPE08");

        sel0009 = requireWidth(sel0009, SEL_LENGTH, "SEL0009");
        usrId09 = requireWidth(usrId09, USRID_LENGTH, "USRID09");
        fname09 = requireWidth(fname09, FNAME_LENGTH, "FNAME09");
        lname09 = requireWidth(lname09, LNAME_LENGTH, "LNAME09");
        utype09 = requireWidth(utype09, UTYPE_LENGTH, "UTYPE09");

        sel0010 = requireWidth(sel0010, SEL_LENGTH, "SEL0010");
        usrId10 = requireWidth(usrId10, USRID_LENGTH, "USRID10");
        fname10 = requireWidth(fname10, FNAME_LENGTH, "FNAME10");
        lname10 = requireWidth(lname10, LNAME_LENGTH, "LNAME10");
        utype10 = requireWidth(utype10, UTYPE_LENGTH, "UTYPE10");

        errMsg = requireWidth(errMsg, ERRMSG_LENGTH, ERRMSG_FIELD);

        cdemoCu00UsrIdFirst = requireWidth(cdemoCu00UsrIdFirst, CU00_USRID_FIRST_LENGTH,
                CU00_USRID_FIRST_FIELD);
        cdemoCu00UsrIdLast = requireWidth(cdemoCu00UsrIdLast, CU00_USRID_LAST_LENGTH,
                CU00_USRID_LAST_FIELD);
        cdemoCu00PageNum = storeUnsignedDigits(cdemoCu00PageNum, CU00_PAGE_NUM_DIGITS,
                CU00_PAGE_NUM_FIELD);
        cdemoCu00NextPageFlg = requireWidth(cdemoCu00NextPageFlg, CU00_NEXT_PAGE_FLG_LENGTH,
                CU00_NEXT_PAGE_FLG_FIELD);
        cdemoCu00UsrSelFlg = requireWidth(cdemoCu00UsrSelFlg, CU00_USR_SEL_FLG_LENGTH,
                CU00_USR_SEL_FLG_FIELD);
        cdemoCu00UsrSelected = requireWidth(cdemoCu00UsrSelected, CU00_USR_SELECTED_LENGTH,
                CU00_USR_SELECTED_FIELD);

        nextProgram = requireWidth(nextProgram, NEXT_PROGRAM_LENGTH,
                NavigationContext.TO_PROGRAM_FIELD);
        nextMapset = requireWidth(nextMapset, NEXT_MAPSET_LENGTH,
                NavigationContext.LAST_MAPSET_FIELD);
        nextMap = requireWidth(nextMap, NEXT_MAP_LENGTH, NavigationContext.LAST_MAP_FIELD);

        navigationContext = Objects.requireNonNull(navigationContext,
                "The CARDDEMO-COMMAREA is not optional; every online transaction is handed one. "
                        + "Use NavigationContext.empty() for the initial state");
    }

    // =============================================================================================
    // Width and value checks. Each is a single shared helper rather than an inline test per field,
    // so the rule lives in exactly one place and reads identically for all 65 fixed-width members.
    // =============================================================================================

    /**
     * Rejects {@code null} and anything wider than the field's {@code PICTURE} clause, returning the
     * value unchanged when it fits.
     *
     * @param value         the value offered for the field
     * @param declaredWidth the width the {@code PICTURE} clause declares
     * @param cobolName     the COBOL field name, so a failure says which field is wrong
     * @return {@code value}, unchanged - this helper never pads and never truncates
     */
    private static String requireWidth(String value, int declaredWidth, String cobolName) {
        Objects.requireNonNull(value, "Field " + cobolName + " requires a value; a COBOL screen "
                + "field holds spaces when it is empty, never null, so move SPACES explicitly or "
                + "start from UserListResponse.blank()");
        if (value.length() > declaredWidth) {
            throw new IllegalArgumentException("Field " + cobolName + " is declared PIC X("
                    + declaredWidth + ") on map " + MAP_NAME + " of mapset " + MAPSET_NAME
                    + " but was given " + value.length() + " character(s): '" + value + "'. The "
                    + "surplus has nowhere to go on a " + SYMBOLIC_MAP_LENGTH + "-byte symbolic "
                    + "map. To shorten it deliberately, truncate on the right as a COBOL "
                    + "alphanumeric MOVE does before offering the value here");
        }
        return value;
    }

    /**
     * Rejects a negative value and one needing more digits than the numeric {@code PICTURE} allows.
     *
     * <p>{@code CDEMO-CU00-PAGE-NUM PIC 9(08)} is unsigned and unscaled, so it is an {@code int} -
     * never a {@code BigDecimal}, and never binary floating point, which cannot represent a decimal
     * value exactly and has no place anywhere near a COBOL numeric field.
     *
     * @param value          the page number offered
     * @param declaredDigits the digit count the {@code PICTURE} clause declares
     * @param cobolName      the COBOL field name, so a failure says which field is wrong
     * @return {@code value}, unchanged
     */
    private static int storeUnsignedDigits(int value, int declaredDigits, String cobolName) {
        if (value < 0) {
            throw new IllegalArgumentException("Field " + cobolName + " is declared PIC 9("
                    + declaredDigits + "), an unsigned picture with no sign position, so it cannot "
                    + "hold " + value);
        }
        // COBOL stores into a numeric receiver by aligning on the implied decimal point and discarding
        // what does not fit - and for a PIC 9 item that discard is on the LEFT, the high-order digits
        // (AAP 0.3.7). ADD 1 TO CDEMO-CU00-PAGE-NUM at COUSR00C:320 is exactly such a store, so a
        // ninth digit is dropped by the receiver rather than refused: 100000000 stores as 00000000.
        // The modulus is built by repeated multiplication rather than Math.pow, because no monetary or
        // picture-derived value in this codebase is ever routed through a double (rule R4).
        int modulus = 1;
        for (int digit = 0; digit < declaredDigits; digit++) {
            modulus = modulus * 10;
        }
        return value % modulus;
    }

    /**
     * A run of spaces, the COBOL fill character for a {@code PIC X} field.
     *
     * @param width how many spaces
     * @return a string of exactly {@code width} spaces
     */
    private static String spaces(int width) {
        return SPACE.repeat(width);
    }

    // =============================================================================================
    // The initial screen.
    // =============================================================================================

    /**
     * The screen as {@code COUSR00C} finds it before it has produced anything: every screen field
     * carrying the unpainted image at its declared width, the page number zero, and the next-page flag
     * at the {@code VALUE 'N'} its declaration gives it at {@code app/cbl/COUSR00C.cbl} line 70.
     *
     * <p>The unpainted image is {@code LOW-VALUES} - {@code X'00'} at the declared width - which is
     * what {@code MOVE LOW-VALUES TO COUSR0AO} at line 117 moves. {@link ScreenFieldImage} records
     * that decision once for all seventeen screens.
     *
     * <p>All ten rows are present and unpainted, which is the state {@code INITIALIZE-USER-DATA} leaves
     * them in. That is the point of starting here: a page that finds fewer than ten users emits ten
     * rows regardless, the unreached ones still unpainted.
     *
     * @return a fully unpainted response, ready to be filled in through {@link #toBuilder()}
     */
    public static UserListResponse blank() {
        return new UserListResponse(ScreenFieldImage.unpainted(TRNNAME_LENGTH),
                ScreenFieldImage.unpainted(TITLE01_LENGTH),
                ScreenFieldImage.unpainted(CURDATE_LENGTH),
                ScreenFieldImage.unpainted(PGMNAME_LENGTH),
                ScreenFieldImage.unpainted(TITLE02_LENGTH),
                ScreenFieldImage.unpainted(CURTIME_LENGTH),
                ScreenFieldImage.unpainted(PAGENUM_LENGTH),
                ScreenFieldImage.unpainted(USRIDIN_LENGTH),
                ScreenFieldImage.unpainted(SEL_LENGTH), ScreenFieldImage.unpainted(USRID_LENGTH), ScreenFieldImage.unpainted(FNAME_LENGTH),
                ScreenFieldImage.unpainted(LNAME_LENGTH), ScreenFieldImage.unpainted(UTYPE_LENGTH),
                ScreenFieldImage.unpainted(SEL_LENGTH), ScreenFieldImage.unpainted(USRID_LENGTH), ScreenFieldImage.unpainted(FNAME_LENGTH),
                ScreenFieldImage.unpainted(LNAME_LENGTH), ScreenFieldImage.unpainted(UTYPE_LENGTH),
                ScreenFieldImage.unpainted(SEL_LENGTH), ScreenFieldImage.unpainted(USRID_LENGTH), ScreenFieldImage.unpainted(FNAME_LENGTH),
                ScreenFieldImage.unpainted(LNAME_LENGTH), ScreenFieldImage.unpainted(UTYPE_LENGTH),
                ScreenFieldImage.unpainted(SEL_LENGTH), ScreenFieldImage.unpainted(USRID_LENGTH), ScreenFieldImage.unpainted(FNAME_LENGTH),
                ScreenFieldImage.unpainted(LNAME_LENGTH), ScreenFieldImage.unpainted(UTYPE_LENGTH),
                ScreenFieldImage.unpainted(SEL_LENGTH), ScreenFieldImage.unpainted(USRID_LENGTH), ScreenFieldImage.unpainted(FNAME_LENGTH),
                ScreenFieldImage.unpainted(LNAME_LENGTH), ScreenFieldImage.unpainted(UTYPE_LENGTH),
                ScreenFieldImage.unpainted(SEL_LENGTH), ScreenFieldImage.unpainted(USRID_LENGTH), ScreenFieldImage.unpainted(FNAME_LENGTH),
                ScreenFieldImage.unpainted(LNAME_LENGTH), ScreenFieldImage.unpainted(UTYPE_LENGTH),
                ScreenFieldImage.unpainted(SEL_LENGTH), ScreenFieldImage.unpainted(USRID_LENGTH), ScreenFieldImage.unpainted(FNAME_LENGTH),
                ScreenFieldImage.unpainted(LNAME_LENGTH), ScreenFieldImage.unpainted(UTYPE_LENGTH),
                ScreenFieldImage.unpainted(SEL_LENGTH), ScreenFieldImage.unpainted(USRID_LENGTH), ScreenFieldImage.unpainted(FNAME_LENGTH),
                ScreenFieldImage.unpainted(LNAME_LENGTH), ScreenFieldImage.unpainted(UTYPE_LENGTH),
                ScreenFieldImage.unpainted(SEL_LENGTH), ScreenFieldImage.unpainted(USRID_LENGTH), ScreenFieldImage.unpainted(FNAME_LENGTH),
                ScreenFieldImage.unpainted(LNAME_LENGTH), ScreenFieldImage.unpainted(UTYPE_LENGTH),
                ScreenFieldImage.unpainted(SEL_LENGTH), ScreenFieldImage.unpainted(USRID_LENGTH), ScreenFieldImage.unpainted(FNAME_LENGTH),
                ScreenFieldImage.unpainted(LNAME_LENGTH), ScreenFieldImage.unpainted(UTYPE_LENGTH),
                ScreenFieldImage.unpainted(ERRMSG_LENGTH),
                spaces(CU00_USRID_FIRST_LENGTH),
                spaces(CU00_USRID_LAST_LENGTH),
                0,
                NEXT_PAGE_NO,
                spaces(CU00_USR_SEL_FLG_LENGTH),
                spaces(CU00_USR_SELECTED_LENGTH),
                spaces(NEXT_PROGRAM_LENGTH),
                spaces(NEXT_MAPSET_LENGTH),
                spaces(NEXT_MAP_LENGTH),
                NavigationContext.empty());
    }

    // =============================================================================================
    // The two 88-level conditions declared over CDEMO-CU00-NEXT-PAGE-FLG. Each reads the stored
    // field rather than duplicating its state, so there is exactly one source of truth, and neither
    // is the negation of the other because the field can hold a third value: it is PIC X(01), and a
    // fresh commarea arriving from a client that omitted it holds a space, which is neither 'Y' nor
    // 'N'. Both are deliberately named without a get or is prefix, so they stay behaviour rather
    // than becoming payload members that the map does not declare.
    // =============================================================================================

    /**
     * {@code 88 NEXT-PAGE-YES VALUE 'Y'}: a further page exists beyond the one being displayed.
     *
     * <p>{@code PROCESS-PAGE-FORWARD} sets it by reading one record past the tenth row - see
     * {@code app/cbl/COUSR00C.cbl} lines 310-316 - and {@code PROCESS-PF8-KEY} tests it at line 269
     * to decide between paging forward and reporting "You are already at the bottom of the page...".
     *
     * @return {@code true} when the flag holds exactly {@value #NEXT_PAGE_YES}
     */
    public boolean nextPageYes() {
        return NEXT_PAGE_YES.equals(cdemoCu00NextPageFlg);
    }

    /**
     * {@code 88 NEXT-PAGE-NO VALUE 'N'}: the page being displayed is the last one.
     *
     * @return {@code true} when the flag holds exactly {@value #NEXT_PAGE_NO}
     */
    public boolean nextPageNo() {
        return NEXT_PAGE_NO.equals(cdemoCu00NextPageFlg);
    }

    // =============================================================================================
    // Reading the ten rows by their COBOL row number. The 50 row cells are flat members and remain
    // the payload; these views exist so a caller can walk the rows without naming fifty accessors,
    // and so the one-based-to-zero-based conversion lives in exactly one expression.
    // =============================================================================================

    /**
     * One row of the list as {@code POPULATE-USER-DATA} fills it.
     *
     * <p>{@link #rowNumber()} is the COBOL row number, {@code 1} through {@value #ROW_COUNT},
     * matching {@code WS-IDX} in {@code EVALUATE WS-IDX WHEN 1} at {@code app/cbl/COUSR00C.cbl}
     * line 386 - the {@code WHEN 1} branch is the one that writes {@code USRID01}. It is not a Java
     * index.
     *
     * @param rowNumber the COBOL row number, 1-based
     * @param selection {@code SEL000n PIC X(1)}: echoed user input, never written by the program
     * @param userId    {@code USRIDnn PIC X(8)} from {@code SEC-USR-ID}
     * @param firstName {@code FNAMEnn PIC X(20)} from {@code SEC-USR-FNAME}
     * @param lastName  {@code LNAMEnn PIC X(20)} from {@code SEC-USR-LNAME}
     * @param userType  {@code UTYPEnn PIC X(1)} from {@code SEC-USR-TYPE}
     */
    public record Row(int rowNumber,
                      String selection,
                      String userId,
                      String firstName,
                      String lastName,
                      String userType) {

        /**
         * A diagnostic rendering that withholds the personal name, per {@link SensitiveDiagnostics}.
         *
         * <p>{@code FNAMEnn} and {@code LNAMEnn} are the listed user's given and family names. Their
         * length is reported and their content is not. The selection cell, the user id and the user type
         * render as stored: the id is an eight-character operator id, and all three are what a
         * pagination or selection parity failure is read from.
         *
         * @return a rendering safe to log, never {@code null}
         */
        @Override
        public String toString() {
            return "Row[" + rowNumber
                    + ", selection='" + selection
                    + "', userId='" + userId
                    + "', firstName=" + SensitiveDiagnostics.describeText(firstName)
                    + ", lastName=" + SensitiveDiagnostics.describeText(lastName)
                    + ", userType='" + userType
                    + "']";
        }

        /**
         * {@code true} when all four data cells carry no user - which covers both of the two ways a row
         * can hold nothing, because the program produces both.
         *
         * <p>The two are different bytes and both occur here:
         *
         * <ul>
         *   <li><strong>Spaces.</strong> {@code INITIALIZE-USER-DATA} at
         *       {@code app/cbl/COUSR00C.cbl} line 446 onwards moves {@code SPACES} into
         *       {@code USRIDnnI}, {@code FNAMEnnI}, {@code LNAMEnnI} and {@code UTYPEnnI}. A row the
         *       browse reached past goes through here.</li>
         *   <li><strong>{@code LOW-VALUES}.</strong> {@code MOVE LOW-VALUES TO COUSR0AO} at {@code :117}
         *       clears the map before the first paint, so a row on a screen that
         *       {@code INITIALIZE-USER-DATA} never ran for holds {@code X'00'}.</li>
         * </ul>
         *
         * <p>So the test is the COBOL's own {@code = SPACES OR LOW-VALUES}, not a Java blankness test:
         * {@link String#isBlank()} reports a run of {@code X'00'} as <em>not</em> blank and would answer
         * {@code false} for a row that plainly holds no user. See
         * {@link ScreenFieldImage#isSpacesOrLowValues(String)}.
         *
         * <p>The selection cell is excluded on purpose: {@code INITIALIZE-USER-DATA} blanks the four
         * data cells only, so a row with no user on it may still carry a character the user typed in its
         * selection cell.
         *
         * @return {@code true} when all four data cells are spaces or all {@code LOW-VALUES}
         */
        public boolean blankRow() {
            return ScreenFieldImage.isSpacesOrLowValues(userId)
                    && ScreenFieldImage.isSpacesOrLowValues(firstName)
                    && ScreenFieldImage.isSpacesOrLowValues(lastName)
                    && ScreenFieldImage.isSpacesOrLowValues(userType);
        }
    }

    /**
     * The row bearing a given COBOL row number.
     *
     * @param rowNumber the COBOL row number, {@code 1} through {@value #ROW_COUNT}, exactly as
     *                  {@code WS-IDX} counts
     * @return that row's five cells
     * @throws IllegalArgumentException if {@code rowNumber} is outside {@code 1..}{@value #ROW_COUNT}
     */
    public Row row(int rowNumber) {
        return switch (rowNumber) {
            case 1 -> new Row(1, sel0001, usrId01, fname01, lname01, utype01);
            case 2 -> new Row(2, sel0002, usrId02, fname02, lname02, utype02);
            case 3 -> new Row(3, sel0003, usrId03, fname03, lname03, utype03);
            case 4 -> new Row(4, sel0004, usrId04, fname04, lname04, utype04);
            case 5 -> new Row(5, sel0005, usrId05, fname05, lname05, utype05);
            case 6 -> new Row(6, sel0006, usrId06, fname06, lname06, utype06);
            case 7 -> new Row(7, sel0007, usrId07, fname07, lname07, utype07);
            case 8 -> new Row(8, sel0008, usrId08, fname08, lname08, utype08);
            case 9 -> new Row(9, sel0009, usrId09, fname09, lname09, utype09);
            case 10 -> new Row(10, sel0010, usrId10, fname10, lname10, utype10);
            default -> throw new IllegalArgumentException("Row " + rowNumber + " does not exist; "
                    + MAP_NAME + " declares exactly " + ROW_COUNT + " rows, numbered 1 to "
                    + ROW_COUNT + " as WS-IDX counts them. Ten rows is the screen, not a setting");
        };
    }

    /**
     * All {@value #ROW_COUNT} rows, in screen order, always {@value #ROW_COUNT} of them.
     *
     * <p><strong>COBOL counts from one and Java from zero.</strong> Element {@code 0} of the
     * returned list is row {@code 1} - the row {@code EVALUATE WS-IDX WHEN 1} writes - and element
     * {@code 9} is row {@value #ROW_COUNT}. Each element carries its own {@link Row#rowNumber()} so
     * the COBOL number survives the conversion and a caller never has to add or subtract one
     * itself. That single {@code -1} is the whole of the conversion and it lives nowhere else,
     * which is what keeps the off-by-one that this shape invites out of the payload.
     *
     * <p>The list is never shorter than {@value #ROW_COUNT}. A page that found fewer users returns
     * blank rows, because that is what the screen shows.
     *
     * @return an immutable list of exactly {@value #ROW_COUNT} rows, blanks included
     */
    public List<Row> rows() {
        return List.of(row(1), row(2), row(3), row(4), row(5),
                row(6), row(7), row(8), row(9), row(10));
    }

    /**
     * The {@value #MAP_FIELD_COUNT} map-derived field names in map order, as an instance-level
     * convenience over {@link #FIELD_NAMES}.
     *
     * @return the immutable field-name list; the same list {@link #FIELD_NAMES} exposes
     */
    public List<String> fieldNames() {
        return FIELD_NAMES;
    }

    // =============================================================================================
    // Assembly. The canonical constructor takes 69 components, which is exact but no way to build a
    // screen by hand, and 69 withXxx methods would each have to restate all 69. So there is one
    // hand-written builder instead - hand-written because annotation-driven builders are excluded
    // from this migration, and because a generated one could not carry the two row methods below,
    // which are the whole reason the builder is worth having.
    // =============================================================================================

    /**
     * A builder seeded from {@link #blank()} - the state {@code COUSR00C} starts every page from.
     *
     * @return a new builder holding a fully space-filled screen
     */
    public static Builder builder() {
        return new Builder(blank());
    }

    /**
     * A builder seeded from this response, for producing a modified copy.
     *
     * <p>This is how a caller "changes" a field: nothing here is mutable, so an edit produces a new
     * value and a response already handed to a collaborator cannot be altered underneath it.
     *
     * @return a new builder holding this response's values
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Assembles a {@link UserListResponse} field by field.
     *
     * <p>The builder is an ordinary short-lived object: it is created, filled and discarded by one
     * caller, holds nothing static, and shares nothing. Its row cells live in five arrays of
     * {@value #ROW_COUNT} elements purely so that {@link #populateRow} and {@link #blankRow} can be
     * written once each instead of ten times; {@link #build()} copies them out into the immutable
     * record, so no array ever escapes.
     *
     * <p>Every width is checked by the canonical constructor when {@link #build()} runs, which means
     * a builder can be filled in any order and a mistake is reported once, against the field that
     * caused it.
     */
    public static final class Builder {

        private String trnName;
        private String title01;
        private String curDate;
        private String pgmName;
        private String title02;
        private String curTime;
        private String pageNum;
        private String usrIdIn;

        /** Row selection cells, index {@code 0} holding row {@code 1}. */
        private final String[] selection = new String[ROW_COUNT];

        /** Row user-identifier cells, index {@code 0} holding row {@code 1}. */
        private final String[] userId = new String[ROW_COUNT];

        /** Row first-name cells, index {@code 0} holding row {@code 1}. */
        private final String[] firstName = new String[ROW_COUNT];

        /** Row last-name cells, index {@code 0} holding row {@code 1}. */
        private final String[] lastName = new String[ROW_COUNT];

        /** Row user-type cells, index {@code 0} holding row {@code 1}. */
        private final String[] userType = new String[ROW_COUNT];

        private String errMsg;
        private String cdemoCu00UsrIdFirst;
        private String cdemoCu00UsrIdLast;
        private int cdemoCu00PageNum;
        private String cdemoCu00NextPageFlg;
        private String cdemoCu00UsrSelFlg;
        private String cdemoCu00UsrSelected;
        private String nextProgram;
        private String nextMapset;
        private String nextMap;
        private NavigationContext navigationContext;

        /**
         * Seeds the builder from an existing response.
         *
         * @param seed the response whose values the builder starts from; never {@code null},
         *             because both entry points supply one
         */
        private Builder(UserListResponse seed) {
            trnName = seed.trnName();
            title01 = seed.title01();
            curDate = seed.curDate();
            pgmName = seed.pgmName();
            title02 = seed.title02();
            curTime = seed.curTime();
            pageNum = seed.pageNum();
            usrIdIn = seed.usrIdIn();
            for (Row seedRow : seed.rows()) {
                int index = seedRow.rowNumber() - 1;
                selection[index] = seedRow.selection();
                userId[index] = seedRow.userId();
                firstName[index] = seedRow.firstName();
                lastName[index] = seedRow.lastName();
                userType[index] = seedRow.userType();
            }
            errMsg = seed.errMsg();
            cdemoCu00UsrIdFirst = seed.cdemoCu00UsrIdFirst();
            cdemoCu00UsrIdLast = seed.cdemoCu00UsrIdLast();
            cdemoCu00PageNum = seed.cdemoCu00PageNum();
            cdemoCu00NextPageFlg = seed.cdemoCu00NextPageFlg();
            cdemoCu00UsrSelFlg = seed.cdemoCu00UsrSelFlg();
            cdemoCu00UsrSelected = seed.cdemoCu00UsrSelected();
            nextProgram = seed.nextProgram();
            nextMapset = seed.nextMapset();
            nextMap = seed.nextMap();
            navigationContext = seed.navigationContext();
        }

        /**
         * Sets {@code TRNNAMEO}, the transaction identifier shown on line 1.
         *
         * @param value at most {@value #TRNNAME_LENGTH} characters
         * @return this builder
         */
        public Builder trnName(String value) {
            this.trnName = value;
            return this;
        }

        /**
         * Sets {@code TITLE01O}, the first title line.
         *
         * @param value at most {@value #TITLE01_LENGTH} characters
         * @return this builder
         */
        public Builder title01(String value) {
            this.title01 = value;
            return this;
        }

        /**
         * Sets {@code CURDATEO}, the date shown on line 1.
         *
         * @param value at most {@value #CURDATE_LENGTH} characters
         * @return this builder
         */
        public Builder curDate(String value) {
            this.curDate = value;
            return this;
        }

        /**
         * Sets {@code PGMNAMEO}, the program name shown on line 2.
         *
         * @param value at most {@value #PGMNAME_LENGTH} characters
         * @return this builder
         */
        public Builder pgmName(String value) {
            this.pgmName = value;
            return this;
        }

        /**
         * Sets {@code TITLE02O}, the second title line.
         *
         * @param value at most {@value #TITLE02_LENGTH} characters
         * @return this builder
         */
        public Builder title02(String value) {
            this.title02 = value;
            return this;
        }

        /**
         * Sets {@code CURTIMEO}, the time shown on line 2 - {@value #CURTIME_LENGTH} characters,
         * not nine.
         *
         * @param value at most {@value #CURTIME_LENGTH} characters
         * @return this builder
         */
        public Builder curTime(String value) {
            this.curTime = value;
            return this;
        }

        /**
         * Sets {@code PAGENUMO}, the page number as it appears on the screen.
         *
         * <p>This is the character field. {@code app/cbl/COUSR00C.cbl} line 325 fills it with
         * {@code MOVE CDEMO-CU00-PAGE-NUM TO PAGENUMI}, which renders the numeric field as
         * {@value #PAGENUM_LENGTH} digit characters such as {@code 00000001}. Setting it does not
         * set {@link #cdemoCu00PageNum(int)}, and setting that does not set this one; the program
         * carries both and so does the payload.
         *
         * @param value at most {@value #PAGENUM_LENGTH} characters
         * @return this builder
         */
        public Builder pageNum(String value) {
            this.pageNum = value;
            return this;
        }

        /**
         * Sets {@code USRIDINO}, the browse-start identifier.
         *
         * <p>{@code app/cbl/COUSR00C.cbl} moves a single {@code SPACE} here at lines 232 and 326
         * once a page has been sent successfully, so the field arrives back empty; a blank value
         * means "start at the beginning" when the next request carries it.
         *
         * @param value at most {@value #USRIDIN_LENGTH} characters
         * @return this builder
         */
        public Builder usrIdIn(String value) {
            this.usrIdIn = value;
            return this;
        }

        /**
         * Fills one row's four data cells, exactly as {@code POPULATE-USER-DATA} does.
         *
         * <p>The selection cell is deliberately left alone, because
         * {@code app/cbl/COUSR00C.cbl} lines 386-441 write only {@code USRIDnnI},
         * {@code FNAMEnnI}, {@code LNAMEnnI} and {@code UTYPEnnI} from
         * {@code SEC-USER-DATA}. Use {@link #selection(int, String)} for the cell the user types.
         *
         * @param rowNumber the COBOL row number, {@code 1} through {@value #ROW_COUNT} - the same
         *                  {@code WS-IDX} the {@code EVALUATE} switches on, not a Java index
         * @param userId    {@code SEC-USR-ID}, at most {@value #USRID_LENGTH} characters
         * @param firstName {@code SEC-USR-FNAME}, at most {@value #FNAME_LENGTH} characters
         * @param lastName  {@code SEC-USR-LNAME}, at most {@value #LNAME_LENGTH} characters
         * @param userType  {@code SEC-USR-TYPE}, at most {@value #UTYPE_LENGTH} characters
         * @return this builder
         * @throws IllegalArgumentException if {@code rowNumber} is outside
         *                                  {@code 1..}{@value #ROW_COUNT}
         */
        public Builder populateRow(int rowNumber,
                                   String userId,
                                   String firstName,
                                   String lastName,
                                   String userType) {
            int index = rowIndex(rowNumber);
            this.userId[index] = userId;
            this.firstName[index] = firstName;
            this.lastName[index] = lastName;
            this.userType[index] = userType;
            return this;
        }

        /**
         * Blanks one row's four data cells, exactly as {@code INITIALIZE-USER-DATA} does.
         *
         * <p>{@code app/cbl/COUSR00C.cbl} line 446 onwards moves {@code SPACES} into
         * {@code USRIDnnI}, {@code FNAMEnnI}, {@code LNAMEnnI} and {@code UTYPEnnI} and touches
         * nothing else - so the selection cell keeps whatever it held, and the row is blanked rather
         * than removed. A page that found fewer than {@value #ROW_COUNT} users still shows
         * {@value #ROW_COUNT} rows.
         *
         * @param rowNumber the COBOL row number, {@code 1} through {@value #ROW_COUNT}
         * @return this builder
         * @throws IllegalArgumentException if {@code rowNumber} is outside
         *                                  {@code 1..}{@value #ROW_COUNT}
         */
        public Builder blankRow(int rowNumber) {
            return populateRow(rowNumber,
                    spaces(USRID_LENGTH),
                    spaces(FNAME_LENGTH),
                    spaces(LNAME_LENGTH),
                    spaces(UTYPE_LENGTH));
        }

        /**
         * Sets one row's selection cell, {@code SEL000n} - the four-digit column.
         *
         * <p>The program never writes this cell; it reads it, at
         * {@code app/cbl/COUSR00C.cbl} lines 141-184, to learn which row was ticked. A response sets
         * it only to echo back what the user typed.
         *
         * @param rowNumber the COBOL row number, {@code 1} through {@value #ROW_COUNT}
         * @param value     at most {@value #SEL_LENGTH} character
         * @return this builder
         * @throws IllegalArgumentException if {@code rowNumber} is outside
         *                                  {@code 1..}{@value #ROW_COUNT}
         */
        public Builder selection(int rowNumber, String value) {
            this.selection[rowIndex(rowNumber)] = value;
            return this;
        }

        /**
         * Sets {@code ERRMSGO}, the message line.
         *
         * <p>The field is {@value #ERRMSG_LENGTH} characters while {@code WS-MESSAGE} is 80. An
         * over-long message is rejected rather than trimmed: narrowing it is the controller's
         * decision, taken with a {@code MOVE} at {@code app/cbl/COUSR00C.cbl} line 526.
         *
         * @param value at most {@value #ERRMSG_LENGTH} characters
         * @return this builder
         */
        public Builder errMsg(String value) {
            this.errMsg = value;
            return this;
        }

        /**
         * Sets {@code CDEMO-CU00-USRID-FIRST}, the backward browse cursor.
         *
         * <p>{@code POPULATE-USER-DATA} sets it from row 1 in one {@code MOVE} with two receivers at
         * {@code app/cbl/COUSR00C.cbl} lines 388-389, and {@code PROCESS-PF7-KEY} starts the
         * backward browse from it at lines 239-243.
         *
         * @param value at most {@value #CU00_USRID_FIRST_LENGTH} characters
         * @return this builder
         */
        public Builder cdemoCu00UsrIdFirst(String value) {
            this.cdemoCu00UsrIdFirst = value;
            return this;
        }

        /**
         * Sets {@code CDEMO-CU00-USRID-LAST}, the forward browse cursor.
         *
         * <p>{@code POPULATE-USER-DATA} sets it from row {@value #ROW_COUNT} at
         * {@code app/cbl/COUSR00C.cbl} lines 435-436, and {@code PROCESS-PF8-KEY} starts the forward
         * browse from it at lines 259-263.
         *
         * @param value at most {@value #CU00_USRID_LAST_LENGTH} characters
         * @return this builder
         */
        public Builder cdemoCu00UsrIdLast(String value) {
            this.cdemoCu00UsrIdLast = value;
            return this;
        }

        /**
         * Sets {@code CDEMO-CU00-PAGE-NUM}, the page number as a number.
         *
         * @param value an unsigned value of at most {@value #CU00_PAGE_NUM_DIGITS} digits, because
         *              the picture is {@code 9(08)}
         * @return this builder
         */
        public Builder cdemoCu00PageNum(int value) {
            this.cdemoCu00PageNum = value;
            return this;
        }

        /**
         * Sets {@code CDEMO-CU00-NEXT-PAGE-FLG} directly.
         *
         * @param value at most {@value #CU00_NEXT_PAGE_FLG_LENGTH} character; normally
         *              {@value #NEXT_PAGE_YES} or {@value #NEXT_PAGE_NO}
         * @return this builder
         */
        public Builder cdemoCu00NextPageFlg(String value) {
            this.cdemoCu00NextPageFlg = value;
            return this;
        }

        /**
         * {@code SET NEXT-PAGE-YES TO TRUE}: records that a further page exists.
         *
         * @return this builder
         */
        public Builder nextPageYes() {
            return cdemoCu00NextPageFlg(NEXT_PAGE_YES);
        }

        /**
         * {@code SET NEXT-PAGE-NO TO TRUE}: records that this is the last page.
         *
         * @return this builder
         */
        public Builder nextPageNo() {
            return cdemoCu00NextPageFlg(NEXT_PAGE_NO);
        }

        /**
         * Sets {@code CDEMO-CU00-USR-SEL-FLG}, the selection character of the ticked row.
         *
         * <p>{@code app/cbl/COUSR00C.cbl} lines 189-209 accept {@code 'U'} and {@code 'u'} for the
         * update screen and {@code 'D'} and {@code 'd'} for the delete screen - the selection is
         * case-insensitive - and anything else produces
         * {@value #INVALID_SELECTION_MESSAGE}. The character is carried here exactly as typed; the
         * decision it drives belongs to the controller.
         *
         * @param value at most {@value #CU00_USR_SEL_FLG_LENGTH} character
         * @return this builder
         */
        public Builder cdemoCu00UsrSelFlg(String value) {
            this.cdemoCu00UsrSelFlg = value;
            return this;
        }

        /**
         * Sets {@code CDEMO-CU00-USR-SELECTED}, the user identifier of the ticked row.
         *
         * @param value at most {@value #CU00_USR_SELECTED_LENGTH} characters
         * @return this builder
         */
        public Builder cdemoCu00UsrSelected(String value) {
            this.cdemoCu00UsrSelected = value;
            return this;
        }

        /**
         * Sets the {@code XCTL} target the client should call next.
         *
         * @param value at most {@value #NEXT_PROGRAM_LENGTH} characters; the documented targets are
         *              {@value #NEXT_PROGRAM_USER_UPDATE}, {@value #NEXT_PROGRAM_USER_DELETE} and
         *              {@value #NEXT_PROGRAM_SIGNON}
         * @return this builder
         */
        public Builder nextProgram(String value) {
            this.nextProgram = value;
            return this;
        }

        /**
         * Sets the mapset to display next - {@value #NEXT_MAPSET_LENGTH} characters, not eight.
         *
         * @param value at most {@value #NEXT_MAPSET_LENGTH} characters
         * @return this builder
         */
        public Builder nextMapset(String value) {
            this.nextMapset = value;
            return this;
        }

        /**
         * Sets the map to display next - {@value #NEXT_MAP_LENGTH} characters, not eight.
         *
         * @param value at most {@value #NEXT_MAP_LENGTH} characters
         * @return this builder
         */
        public Builder nextMap(String value) {
            this.nextMap = value;
            return this;
        }

        /**
         * Sets the {@code CARDDEMO-COMMAREA} this response hands back.
         *
         * @param value the communication area; referenced as it is, never widened
         * @return this builder
         */
        public Builder navigationContext(NavigationContext value) {
            this.navigationContext = value;
            return this;
        }

        /**
         * Builds the response, validating every width in the canonical constructor.
         *
         * @return the assembled response
         * @throws NullPointerException     if a field was cleared to {@code null}
         * @throws IllegalArgumentException if a value exceeds its declared width
         */
        public UserListResponse build() {
            return new UserListResponse(trnName, title01, curDate, pgmName, title02, curTime,
                    pageNum, usrIdIn,
                    selection[0], userId[0], firstName[0], lastName[0], userType[0],
                    selection[1], userId[1], firstName[1], lastName[1], userType[1],
                    selection[2], userId[2], firstName[2], lastName[2], userType[2],
                    selection[3], userId[3], firstName[3], lastName[3], userType[3],
                    selection[4], userId[4], firstName[4], lastName[4], userType[4],
                    selection[5], userId[5], firstName[5], lastName[5], userType[5],
                    selection[6], userId[6], firstName[6], lastName[6], userType[6],
                    selection[7], userId[7], firstName[7], lastName[7], userType[7],
                    selection[8], userId[8], firstName[8], lastName[8], userType[8],
                    selection[9], userId[9], firstName[9], lastName[9], userType[9],
                    errMsg, cdemoCu00UsrIdFirst, cdemoCu00UsrIdLast, cdemoCu00PageNum,
                    cdemoCu00NextPageFlg, cdemoCu00UsrSelFlg, cdemoCu00UsrSelected,
                    nextProgram, nextMapset, nextMap, navigationContext);
        }

        /**
         * Converts a COBOL row number into the Java index of the row arrays.
         *
         * <p><strong>This subtraction is the only place the one-based-to-zero-based conversion
         * happens.</strong> COBOL's {@code WS-IDX} runs {@code 1} through {@value #ROW_COUNT} and
         * {@code EVALUATE WS-IDX WHEN 1} writes row {@code 01}, so row {@code 1} is index
         * {@code 0}. Confining the arithmetic to one expression is what keeps the off-by-one this
         * shape invites from reaching the payload.
         *
         * @param rowNumber the COBOL row number
         * @return the array index, {@code rowNumber - 1}
         * @throws IllegalArgumentException if {@code rowNumber} is outside
         *                                  {@code 1..}{@value #ROW_COUNT}
         */
        private static int rowIndex(int rowNumber) {
            if (rowNumber < 1 || rowNumber > ROW_COUNT) {
                throw new IllegalArgumentException("Row " + rowNumber + " does not exist; "
                        + MAP_NAME + " declares exactly " + ROW_COUNT + " rows, numbered 1 to "
                        + ROW_COUNT + " as WS-IDX counts them, so the valid range is 1 to "
                        + ROW_COUNT + " inclusive");
            }
            return rowNumber - 1;
        }
    }

    /**
     * A diagnostic rendering that withholds the twenty personal names this screen carries, per
     * {@link SensitiveDiagnostics}.
     *
     * <p>The override exists because this is a {@code record} with more than sixty components, ten of
     * which are given names and ten family names - {@code FNAME01} through {@code LNAME10} of
     * {@code app/cpy-bms/COUSR00.CPY}. The generated {@code toString} rendered all twenty, so a single
     * log line disclosed a page of the user directory.
     *
     * <p>The rows are rendered through {@link Row}, which is the structure this type already publishes
     * and which masks the names itself. Writing sixty concatenations by hand instead would be one
     * missed field away from re-opening the same hole, and the row form reads better besides.
     *
     * @return a rendering safe to log, never {@code null}
     */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder(512);
        text.append("UserListResponse[trnName=").append(trnName)
                .append(", title01=").append(title01)
                .append(", curDate=").append(curDate)
                .append(", pgmName=").append(pgmName)
                .append(", title02=").append(title02)
                .append(", curTime=").append(curTime)
                .append(", pageNum=").append(pageNum)
                .append(", usrIdIn=").append(usrIdIn);
        for (Row row : rows()) {
            text.append(", ").append(row);
        }
        return text.append(", errMsg=").append(errMsg)
                .append(", cdemoCu00UsrIdFirst=").append(cdemoCu00UsrIdFirst)
                .append(", cdemoCu00UsrIdLast=").append(cdemoCu00UsrIdLast)
                .append(", cdemoCu00PageNum=").append(cdemoCu00PageNum)
                .append(", cdemoCu00NextPageFlg=").append(cdemoCu00NextPageFlg)
                .append(", cdemoCu00UsrSelFlg=").append(cdemoCu00UsrSelFlg)
                .append(", cdemoCu00UsrSelected=").append(cdemoCu00UsrSelected)
                .append(", nextProgram=").append(nextProgram)
                .append(", nextMapset=").append(nextMapset)
                .append(", nextMap=").append(nextMap)
                .append(", navigationContext=").append(navigationContext)
                .append(']').toString();
    }

}
