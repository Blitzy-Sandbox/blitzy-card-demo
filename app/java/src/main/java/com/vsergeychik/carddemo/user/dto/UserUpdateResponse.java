package com.vsergeychik.carddemo.user.dto;

import com.vsergeychik.carddemo.common.NavigationContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The outbound payload of {@code PUT /api/users/{userId}} - CICS transaction {@code CU02}, program
 * {@code COUSR02C}, mapset {@code COUSR02}, map {@code COUSR2A} - as an immutable value.
 *
 * <p>It is a field-for-field projection of the twelve {@code xxxO} items of
 * {@code 01 COUSR2AO REDEFINES COUSR2AI} in {@code app/cpy-bms/COUSR02.CPY}, plus the four members
 * that carry the stateless navigation contract. Nothing else. This type holds <strong>no
 * logic</strong>: no change detection, no message composition, no colour decision and no
 * truncation. Those belong to {@code user.UserUpdateController}, which is the only place they may
 * live.
 *
 * <h2>Provenance</h2>
 *
 * <table border="1">
 *   <caption>The authoritative sources, all read-only</caption>
 *   <tr><th>Artefact</th><th>Supplies</th></tr>
 *   <tr><td>{@code app/cpy-bms/COUSR02.CPY} lines 91-164</td>
 *       <td>The twelve {@code xxxO} items, each one's name and its authoritative
 *           {@code PICTURE} width</td></tr>
 *   <tr><td>{@code app/bms/COUSR02.bms}</td>
 *       <td>29 {@code DFHMDF} definitions of which exactly <strong>12 are name-labelled</strong>.
 *           The other 17 are literal {@code INITIAL} screen furniture - {@code 'Tran:'},
 *           {@code 'Date:'}, {@code 'Prog:'}, {@code 'Time:'}, {@code 'Update User'},
 *           {@code 'Enter User ID:'}, {@code 'First Name:'}, {@code 'Last Name:'},
 *           {@code 'Password:'}, {@code '(8 Char)'}, {@code 'User Type: '},
 *           {@code '(A=Admin, U=User)'}, the 70-character rule of asterisks, the function-key
 *           legend and three {@code LENGTH=0} field stoppers - and none of them is a field</td></tr>
 *   <tr><td>{@code app/cbl/COUSR02C.cbl}</td>
 *       <td>Which fields are written, when, and from what</td></tr>
 *   <tr><td>{@code app/cpy/CSUSR01Y.cpy}</td>
 *       <td>The 80-byte {@code SEC-USER-DATA} whose {@code SEC-USR-FNAME X(20)},
 *           {@code SEC-USR-LNAME X(20)}, {@code SEC-USR-PWD X(08)} and {@code SEC-USR-TYPE X(01)}
 *           are the four values the read path projects onto the screen</td></tr>
 *   <tr><td>{@code app/cpy/COCOM01Y.cpy}</td>
 *       <td>The 160-byte {@code CARDDEMO-COMMAREA} modelled by {@link NavigationContext}</td></tr>
 * </table>
 *
 * <p>The three independent measurements agree: the {@code xxxI} count, the {@code xxxO} count and
 * the name-labelled {@code DFHMDF} count are all {@value #MAP_FIELD_COUNT}. That agreement is the
 * completeness check, and {@link #MAP_FIELD_NAMES} is its executable form.
 *
 * <h2>Why the response carries the password, and why removing it would be the defect</h2>
 *
 * A blanket instruction circulates for this package: a response should never echo the password,
 * because {@code SEND-SIGNON-SCREEN} does not send it back. That statement is accurate - <em>for
 * {@code COSGN00} only</em>, which is the program it was derived from. {@code COUSR02C} behaves
 * differently, and the difference was verified directly in the source rather than inferred:
 *
 * <pre>
 * app/cbl/COUSR02C.cbl, lines 166-172
 *
 *     IF NOT ERR-FLG-ON
 *         MOVE SEC-USR-FNAME      TO FNAMEI    OF COUSR2AI
 *         MOVE SEC-USR-LNAME      TO LNAMEI    OF COUSR2AI
 *         MOVE SEC-USR-PWD        TO PASSWDI   OF COUSR2AI      &lt;-- line 169
 *         MOVE SEC-USR-TYPE       TO USRTYPEI  OF COUSR2AI
 *         PERFORM SEND-USRUPD-SCREEN
 *     END-IF.
 * </pre>
 *
 * <ol>
 *   <li><strong>{@code app/cbl/COUSR02C.cbl} line 169 puts the stored plaintext password on the
 *       screen</strong>, and line 171 sends it. Lines 155-158 blank {@code FNAMEI},
 *       {@code LNAMEI}, {@code PASSWDI} and {@code USRTYPEI} <em>before</em> the read at line 163,
 *       then lines 167-170 repopulate all four after it - so a failed lookup leaves them blank and
 *       a successful one fills them, the password included. Echoing it is therefore not an
 *       accident of one path; it is the read path's defined outcome.</li>
 *   <li><strong>{@code app/bms/COUSR02.bms} line 130 masks the field at the terminal</strong> with
 *       {@code PASSWD DFHMDF ATTRB=(DRK,FSET,UNPROT)}. {@code DRK} is the non-display attribute:
 *       3270 hardware receives the eight characters and renders them invisibly. So the value
 *       genuinely crosses the wire and is genuinely hidden from the operator's eye - two separate
 *       facts, and only the first of them is this payload's concern.</li>
 *   <li><strong>The {@link #passwd()} member is therefore present deliberately.</strong> Dropping
 *       it would delete observable behaviour, which the migration's own terms forbid exactly as
 *       firmly as they forbid adding behaviour: this is a like-for-like translation with no changed
 *       business rules, behaviour is preserved including defects, and a class name never authorises
 *       altering logic. It would also break the projection: this mapset has
 *       {@value #MAP_FIELD_COUNT} name-labelled fields and the request/response pair must project
 *       {@value #MAP_FIELD_COUNT} of {@value #MAP_FIELD_COUNT}.</li>
 *   <li><strong>Plaintext credential handling is an inherited property of the legacy design and an
 *       explicit non-goal of this migration.</strong> It is recorded here, in the open, rather than
 *       buried in generated code, so the characteristic stays visible to anyone reading the type.
 *       What is prohibited is <em>changing</em> it in either direction: no hashing, no password
 *       encoder, no encoding, no token and no authentication-framework type may appear in this
 *       file - and equally, the echoed member may not be removed. Removing it would be
 *       <em>strengthening</em> the posture, which is as much a behaviour change as weakening
 *       it.</li>
 * </ol>
 *
 * <h2>Four screens, four different password behaviours, each verified separately</h2>
 *
 * Recorded so that nobody generalises from one screen to the others - in either direction:
 *
 * <table border="1">
 *   <caption>Password handling across the four user-facing screens</caption>
 *   <tr><th>Program</th><th>What the source does</th><th>Consequence for its response type</th></tr>
 *   <tr><td>{@code COSGN00C}</td>
 *       <td>Never writes {@code PASSWDO} or {@code PASSWDI} at all; {@code SEND-SIGNON-SCREEN} does
 *           not send the password back</td>
 *       <td>{@code SignOnResponse} correctly has <strong>no</strong> password member</td></tr>
 *   <tr><td>{@code COUSR01C}</td>
 *       <td>Only reads {@code PASSWDI} and blanks it</td>
 *       <td>{@code UserAddResponse}'s member exists but never carries a stored value</td></tr>
 *   <tr><td><strong>{@code COUSR02C}</strong></td>
 *       <td><strong>Line 169 moves the stored {@code SEC-USR-PWD} onto the screen and sends
 *           it</strong></td>
 *       <td><strong>This type declares {@link #passwd()} and it does carry the stored
 *           value</strong></td></tr>
 *   <tr><td>{@code COUSR03C}</td>
 *       <td>Has no {@code PASSWD} field on its map whatsoever</td>
 *       <td>{@code UserDeleteResponse} has 11 members, not 12 - and that contrast is precisely why
 *           this one has 12</td></tr>
 * </table>
 *
 * <p>The conflict above is documented rather than resolved by silently deleting a member, because
 * this migration's practice is to record a conflict and honour the source. A reviewer who arrives
 * with the general rule in mind will find the specific evidence here before acting on it.
 *
 * <h2>The twelve map-derived members, in map order</h2>
 *
 * The order is this screen's own, and it is <strong>not</strong> {@code COUSR01}'s: here the user id
 * comes <em>before</em> the names.
 *
 * <table border="1">
 *   <caption>Every member traced to its {@code DFHMDF} definition and {@code xxxO} item</caption>
 *   <tr><th>#</th><th>{@code DFHMDF}</th><th>{@code xxxO} item</th><th>Width</th>
 *       <th>Accessor</th><th>Written by</th></tr>
 *   <tr><td>1</td><td>{@code TRNNAME}</td><td>{@code TRNNAMEO}</td><td>{@code X(4)}</td>
 *       <td>{@link #trnName()}</td><td>Line 302, from {@code WS-TRANID} = {@code 'CU02'}</td></tr>
 *   <tr><td>2</td><td>{@code TITLE01}</td><td>{@code TITLE01O}</td><td>{@code X(40)}</td>
 *       <td>{@link #title01()}</td><td>Line 300, from {@code CCDA-TITLE01} - the literal owned by
 *           {@code common.ScreenTitles}</td></tr>
 *   <tr><td>3</td><td>{@code CURDATE}</td><td>{@code CURDATEO}</td><td>{@code X(8)}</td>
 *       <td>{@link #curDate()}</td><td>Line 309, {@code MM/DD/YY} - the header owned by
 *           {@code common.DateHeader}</td></tr>
 *   <tr><td>4</td><td>{@code PGMNAME}</td><td>{@code PGMNAMEO}</td><td>{@code X(8)}</td>
 *       <td>{@link #pgmName()}</td><td>Line 303, from {@code WS-PGMNAME} =
 *           {@code 'COUSR02C'}</td></tr>
 *   <tr><td>5</td><td>{@code TITLE02}</td><td>{@code TITLE02O}</td><td>{@code X(40)}</td>
 *       <td>{@link #title02()}</td><td>Line 301, from {@code CCDA-TITLE02}</td></tr>
 *   <tr><td>6</td><td>{@code CURTIME}</td><td>{@code CURTIMEO}</td>
 *       <td>{@code X(8)} - <strong>8 here</strong></td>
 *       <td>{@link #curTime()}</td><td>Line 315, {@code HH:MM:SS}. Only {@code COSGN00} declares
 *           nine; this map declares eight at copybook line 128 and eight is what is used</td></tr>
 *   <tr><td>7</td><td><strong>{@code USRIDIN}</strong></td><td>{@code USRIDINO}</td>
 *       <td>{@code X(8)}</td>
 *       <td>{@link #usrIdIn()}</td><td>The key the operator types, or the row selection handed over
 *           by the user list at lines 99-102</td></tr>
 *   <tr><td>8</td><td>{@code FNAME}</td><td>{@code FNAMEO}</td><td>{@code X(20)}</td>
 *       <td>{@link #fName()}</td><td>Line 167, from {@code SEC-USR-FNAME}</td></tr>
 *   <tr><td>9</td><td>{@code LNAME}</td><td>{@code LNAMEO}</td><td>{@code X(20)}</td>
 *       <td>{@link #lName()}</td><td>Line 168, from {@code SEC-USR-LNAME}</td></tr>
 *   <tr><td>10</td><td><strong>{@code PASSWD}</strong></td><td>{@code PASSWDO}</td>
 *       <td>{@code X(8)}</td>
 *       <td>{@link #passwd()}</td><td><strong>Line 169, from {@code SEC-USR-PWD}</strong></td></tr>
 *   <tr><td>11</td><td>{@code USRTYPE}</td><td>{@code USRTYPEO}</td><td>{@code X(1)}</td>
 *       <td>{@link #usrType()}</td><td>Line 170, from {@code SEC-USR-TYPE}</td></tr>
 *   <tr><td>12</td><td>{@code ERRMSG}</td><td>{@code ERRMSGO}</td>
 *       <td>{@code X(78)} - <strong>78, not 80</strong></td>
 *       <td>{@link #errMsg()}</td><td>Lines 88 and 270</td></tr>
 * </table>
 *
 * <p>Every one of the twelve is {@code PIC X(n)}, so every one is a {@link String}. There is no
 * numeric member, and therefore no binary approximation type anywhere in this file - a monetary or
 * scaled value would have had to be a {@code BigDecimal}, and there is none here.
 *
 * <h3>{@code USRIDIN} keeps its own name</h3>
 *
 * {@code COUSR01}'s equivalent field is labelled {@code USERID}; this one is labelled
 * {@code USRIDIN}, at copybook line 134 and {@code app/bms/COUSR02.bms} line 85. The accessor is
 * named for the field this screen actually declares. Harmonising the two would tidy away a real
 * difference between two mapsets, and nothing in this migration is tidied.
 *
 * <h3>{@code ERRMSG} is 78 wide, and the truncation to it is not performed here</h3>
 *
 * {@code COUSR02C} line 38 declares {@code WS-MESSAGE PIC X(80)} while {@code ERRMSGO} is
 * {@code PIC X(78)} at copybook line 164, so line 270's
 * {@code MOVE WS-MESSAGE TO ERRMSGO OF COUSR2AO} discards the final two characters - a COBOL
 * alphanumeric {@code MOVE} truncates on the <em>right</em>. This type declares the receiving width
 * of {@value #ERR_MSG_LENGTH} and performs no truncation at all: an over-wide value is
 * <strong>rejected</strong>, naming the field, so that shortening is always a deliberate act
 * performed by the controller through {@code common.FixedWidthCodec.movePicX}. Silently discarding
 * two characters inside a payload constructor would make the loss invisible at the call site, which
 * is the precise failure mode that keeping every move rule in one seam exists to prevent.
 *
 * <h2>What is deliberately <em>not</em> a member</h2>
 *
 * <ul>
 *   <li><strong>No {@code xxxC}, {@code xxxP}, {@code xxxH} or {@code xxxV} item.</strong> These
 *       are the four attribute bytes that precede each {@code xxxO} value in the {@code AO} view.
 *       {@code xxxC} is the <em>colour</em> byte, and {@code COUSR02C} drives it three different
 *       ways - {@code MOVE DFHRED TO ERRMSGC OF COUSR2AO} at line 241 for "no change was made",
 *       {@code DFHNEUTR} at line 338 for the "press PF5 to save" prompt and {@code DFHGREEN} at
 *       line 371 on a successful update. {@code xxxH} is highlighting; {@code xxxP} and
 *       {@code xxxV} are the programmed-symbol and validation bytes. All four are presentation
 *       <em>metadata</em>, owned by {@code common.BmsAttributes} and
 *       {@code common.FieldAttributeSetter}, and a controller that needs the colour byte carries it
 *       as clearly-named metadata rather than smuggling it into the payload.</li>
 *   <li><strong>No {@code xxxL}, {@code xxxF} or {@code xxxA} item.</strong> On the {@code AI} side
 *       {@code xxxL} is the input length CICS reports and also serves as the cursor signal - which is
 *       all {@code MOVE -1 TO PASSWDL} at line 202 and {@code MOVE -1 TO USRIDINL} at line 404 are
 *       doing - while {@code xxxF} and its {@code xxxA} redefinition are the attribute byte. They
 *       are validation and highlight metadata, never payload.</li>
 *   <li><strong>No {@code FILLER}.</strong> Neither the 12-byte {@code TIOAPFX} prefix at copybook
 *       line 92 nor the eleven {@code FILLER PICTURE X(3)} spans that separate the fields are
 *       exposed. They are reserved storage.</li>
 *   <li><strong>No {@code CDEMO-CU02-USR-SELECTED}.</strong> {@code COUSR02C} line 58 declares it
 *       {@code PIC X(08)} in the program's <em>own</em> working storage, in the extension group at
 *       lines 52-58, and <strong>not</strong> in {@code app/cpy/COCOM01Y.cpy}. It is inbound state
 *       - the row the user list selected, consumed at lines 99-102 - so it belongs to the request
 *       side and to that program's local area. {@link NavigationContext} is exactly 160 bytes and
 *       is shared by all seventeen controllers; it is referenced here and never widened.</li>
 *   <li><strong>No concurrency-token member of any kind</strong> - no revision counter, no entity
 *       tag, no last-modified stamp. {@code COUSR02C} has no {@code 9300-CHECK-CHANGE-IN-REC}
 *       paragraph; that optimistic concurrency check exists only in {@code COACTUPC} and
 *       {@code COCRDUPC}, and the requirement is scoped to the account and card packages. This
 *       program re-reads the record at line 216 and compares the four fields at lines 219-234 to
 *       decide whether anything changed <em>at all</em>; it never guards against a competing
 *       writer. Adding a token would be both a behaviour change and a schema change.</li>
 *   <li><strong>No persistence and no validation annotation.</strong> No object-relational mapping
 *       annotation appears - no entity, table, identifier or column declaration - because no schema
 *       is created or altered anywhere in this migration. No bean-validation presence constraint
 *       appears either: a response is not validated inbound, and in any case {@code COUSR02C}
 *       answers each blank field with a <em>specific message</em> at lines 148, 182, 188, 194, 200
 *       and 206 rather than rejecting the request, so a validation rejection would replace a screen
 *       message with an HTTP error and change behaviour.</li>
 * </ul>
 *
 * <h2>The navigation contract, and why these four members are the one exception</h2>
 *
 * Every other member of this type traces to a name-labelled {@code DFHMDF} definition. The four
 * navigation members do not, and are the explicitly mandated exception, because CICS
 * pseudo-conversational state has to travel somewhere and a stateless server is the requirement:
 *
 * <ul>
 *   <li>{@link #navigationContext()} carries the 160-byte {@code CARDDEMO-COMMAREA} that
 *       {@code COUSR02C} receives at line 94 and hands back at line 260's
 *       {@code COMMAREA(CARDDEMO-COMMAREA)}.</li>
 *   <li>{@link #nextProgram()}, {@link #nextMapset()} and {@link #nextMap()} turn a CICS program
 *       transfer into client-driven navigation. Line 259 is
 *       {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)}, reached from three places: {@code PF3}, which
 *       <em>saves before exiting</em> at lines 111-119 and falls back to {@code 'COADM01C'} at line
 *       114 when {@code CDEMO-FROM-PROGRAM} is blank; {@code PF12}, which cancels to
 *       {@code 'COADM01C'} at line 125; and the {@code EIBCALEN = 0} cold start at line 90, which
 *       returns to {@code 'COSGN00C'}. The client reads the target and issues the next call, so
 *       there is no server-side forward, no redirect chain and no session affinity.</li>
 * </ul>
 *
 * <p>{@link #nextProgram()} is {@value #NEXT_PROGRAM_LENGTH} wide, matching
 * {@code CDEMO-TO-PROGRAM PIC X(08)} at {@code app/cpy/COCOM01Y.cpy} line 24.
 * {@link #nextMapset()} and {@link #nextMap()} are {@value #NEXT_MAPSET_LENGTH} wide, matching
 * {@code CDEMO-LAST-MAPSET} and {@code CDEMO-LAST-MAP}, both {@code PIC X(7)} at lines 43-44 -
 * <strong>seven, not eight</strong>. That asymmetry is real: a BMS symbolic-map group item is a
 * seven-character map name plus a one-character direction suffix, which is why this screen's own
 * names, {@code COUSR02} and {@code COUSR2A}, are exactly seven characters each while program names
 * such as {@code COUSR02C} and {@code COADM01C} are eight.
 *
 * <p>Statelessness is structural here, not merely intended. This type holds no servlet session
 * handle, no session-scoped attribute binding, no session-scoped bean, no thread-local holder, no
 * server-side cache keyed by user or terminal, and no static mutable field of any kind. A static
 * holder would be a session by another name and would break request isolation and test determinism
 * as well.
 *
 * <h2>Serialisation</h2>
 *
 * A record's components are its JSON properties, so the payload is exactly these sixteen members
 * and nothing else. <strong>No serialisation annotation appears in this file at all</strong>: no
 * property rename, no naming strategy, no inclusion filter, no custom serialiser, and -
 * specifically - nothing that suppresses {@link #passwd()} from the payload, which would defeat the
 * very behaviour documented above. The type deliberately declares no {@code getXxx} or
 * {@code isXxx} accessor either, so there is no derived property for a JSON mapper to auto-detect
 * and therefore nothing that would ever need suppressing.
 *
 * <p>Values are never trimmed and trailing spaces always survive a round trip. That is not a
 * stylistic preference: lines 219-234 compare the values that came back from the screen against the
 * space-padded values held in the record to decide whether the user was modified at all, so the
 * padding is semantically significant and trimming it would silently change which updates are
 * detected.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * // The screen as SEND ... ERASE leaves it: every field its declared width in spaces.
 * UserUpdateResponse response = UserUpdateResponse.blank();
 *
 * // The header, exactly as POPULATE-HEADER-INFO at lines 296-315 fills it.
 * response = response.withTrnName(UserUpdateResponse.TRANSACTION_ID)
 *                    .withPgmName(UserUpdateResponse.PROGRAM_NAME)
 *                    .withCurDate("01/31/25")
 *                    .withCurTime("14:22:07");
 *
 * // A successful lookup, reproducing lines 167-170 - the password included, per line 169.
 * response = response.withUsrIdIn("USER0001")
 *                    .withFName("John")
 *                    .withLName("Doe")
 *                    .withPasswd("PASSW0RD")
 *                    .withUsrType("U");
 *
 * // PF3 saves and leaves; the client reads the target and makes the next call.
 * response = response.withNextProgram("COADM01C");
 * </pre>
 *
 * @param trnName           {@code TRNNAMEO PIC X(4)} - the transaction name, {@code 'CU02'};
 *                          written at {@code COUSR02C} line 302
 * @param title01           {@code TITLE01O PIC X(40)} - the first title line, written at line 300
 * @param curDate           {@code CURDATEO PIC X(8)} - the current date as {@code MM/DD/YY},
 *                          written at line 309
 * @param pgmName           {@code PGMNAMEO PIC X(8)} - the program name, {@code 'COUSR02C'};
 *                          written at line 303
 * @param title02           {@code TITLE02O PIC X(40)} - the second title line, written at line 301
 * @param curTime           {@code CURTIMEO PIC X(8)} - the current time as {@code HH:MM:SS},
 *                          written at line 315. Eight characters, not nine
 * @param usrIdIn           {@code USRIDINO PIC X(8)} - the user id being updated. Named for
 *                          {@code USRIDIN} as this map declares it, and declared before the names
 *                          because that is this screen's field order
 * @param fName             {@code FNAMEO PIC X(20)} - the first name, from {@code SEC-USR-FNAME} at
 *                          line 167
 * @param lName             {@code LNAMEO PIC X(20)} - the last name, from {@code SEC-USR-LNAME} at
 *                          line 168
 * @param passwd            {@code PASSWDO PIC X(8)} - the stored plaintext password, from
 *                          {@code SEC-USR-PWD} at <strong>line 169</strong>. Present deliberately;
 *                          see the discussion above before altering it
 * @param usrType           {@code USRTYPEO PIC X(1)} - the user type, {@code 'A'} for an
 *                          administrator or {@code 'U'} for a regular user, from
 *                          {@code SEC-USR-TYPE} at line 170
 * @param errMsg            {@code ERRMSGO PIC X(78)} - the message line. 78 characters, receiving
 *                          an 80-character {@code WS-MESSAGE} that line 270 truncates on the right
 * @param navigationContext the 160-byte {@code CARDDEMO-COMMAREA}. Never {@code null}: a
 *                          {@code null} is replaced by {@link NavigationContext#empty()}, the
 *                          initialised area a cold start sees
 * @param nextProgram       the {@code XCTL} target of line 259, projected as response data so the
 *                          client navigates. Eight characters, as {@code CDEMO-TO-PROGRAM}
 * @param nextMapset        the mapset to display next. Seven characters, as
 *                          {@code CDEMO-LAST-MAPSET}
 * @param nextMap           the map to display next. Seven characters, as {@code CDEMO-LAST-MAP}
 */
public record UserUpdateResponse(String trnName,
                                 String title01,
                                 String curDate,
                                 String pgmName,
                                 String title02,
                                 String curTime,
                                 String usrIdIn,
                                 String fName,
                                 String lName,
                                 String passwd,
                                 String usrType,
                                 String errMsg,
                                 NavigationContext navigationContext,
                                 String nextProgram,
                                 String nextMapset,
                                 String nextMap) {

    // =============================================================================================
    // Screen identity. These four literals are the ones COUSR02C itself holds, so they are stated
    // once here rather than repeated at every call site.
    // =============================================================================================

    /**
     * The BMS map name, {@code COUSR2A}, as {@code app/cbl/COUSR02C.cbl} line 273 names it in
     * {@code MAP('COUSR2A')}. Seven characters, which is why {@link #nextMap()} is
     * {@value #NEXT_MAP_LENGTH} wide.
     */
    public static final String MAP_NAME = "COUSR2A";

    /**
     * The BMS mapset name, {@code COUSR02}, as line 274 names it in {@code MAPSET('COUSR02')}.
     * Seven characters, matching {@link #NEXT_MAPSET_LENGTH}.
     */
    public static final String MAPSET_NAME = "COUSR02";

    /**
     * The CICS transaction id, {@code CU02}, declared as
     * {@code WS-TRANID PIC X(04) VALUE 'CU02'} at {@code app/cbl/COUSR02C.cbl} line 37 and moved to
     * {@code TRNNAMEO} at line 302. Exactly {@value #TRN_NAME_LENGTH} characters, so it fills
     * {@link #trnName()} without padding.
     */
    public static final String TRANSACTION_ID = "CU02";

    /**
     * The program name, {@code COUSR02C}, declared as
     * {@code WS-PGMNAME PIC X(08) VALUE 'COUSR02C'} at line 36 and moved to {@code PGMNAMEO} at
     * line 303. Exactly {@value #PGM_NAME_LENGTH} characters.
     */
    public static final String PROGRAM_NAME = "COUSR02C";

    /**
     * The name of the symbolic-map group item this type projects,
     * {@code 01 COUSR2AO REDEFINES COUSR2AI} at {@code app/cpy-bms/COUSR02.CPY} line 91. Used to
     * name the group in diagnostics so a message identifies the copybook it came from.
     */
    public static final String GROUP_NAME = "COUSR2AO";

    /**
     * The number of name-labelled {@code DFHMDF} fields on this mapset, and therefore the number of
     * map-derived members this type declares: <strong>12</strong>.
     *
     * <p>Three independent counts agree on it - the {@code xxxI} items of {@code COUSR2AI}, the
     * {@code xxxO} items of {@code COUSR2AO}, and the name-labelled {@code DFHMDF} definitions in
     * {@code app/bms/COUSR02.bms} (12 of 29, the other 17 being literal furniture). The count
     * <em>includes</em> {@code PASSWD}; {@code COUSR03} has eleven precisely because it has no
     * password field, and that contrast is the point.
     *
     * <p>The four navigation members are not counted here, because they are not map-derived.
     */
    public static final int MAP_FIELD_COUNT = 12;

    // =============================================================================================
    // Field names, spelled exactly as app/cpy-bms/COUSR02.CPY spells the xxxO items. These are the
    // names a field-by-field comparison keys on, so a "tidied" spelling would hide a real
    // difference. Each constant records the copybook line it was read from.
    // =============================================================================================

    /** {@code TRNNAMEO}, copybook line 98. */
    public static final String TRN_NAME_FIELD = "TRNNAMEO";

    /** {@code TITLE01O}, copybook line 104. */
    public static final String TITLE01_FIELD = "TITLE01O";

    /** {@code CURDATEO}, copybook line 110. */
    public static final String CUR_DATE_FIELD = "CURDATEO";

    /** {@code PGMNAMEO}, copybook line 116. */
    public static final String PGM_NAME_FIELD = "PGMNAMEO";

    /** {@code TITLE02O}, copybook line 122. */
    public static final String TITLE02_FIELD = "TITLE02O";

    /** {@code CURTIMEO}, copybook line 128. */
    public static final String CUR_TIME_FIELD = "CURTIMEO";

    /**
     * {@code USRIDINO}, copybook line 134. Spelled {@code USRIDIN}, not {@code USERID}:
     * {@code COUSR01} labels its equivalent field {@code USERID} and the two are deliberately not
     * harmonised.
     */
    public static final String USR_ID_IN_FIELD = "USRIDINO";

    /** {@code FNAMEO}, copybook line 140. */
    public static final String FNAME_FIELD = "FNAMEO";

    /** {@code LNAMEO}, copybook line 146. */
    public static final String LNAME_FIELD = "LNAMEO";

    /**
     * {@code PASSWDO}, copybook line 152. Carries the stored plaintext password that
     * {@code app/cbl/COUSR02C.cbl} line 169 moves onto the screen.
     */
    public static final String PASSWD_FIELD = "PASSWDO";

    /** {@code USRTYPEO}, copybook line 158. */
    public static final String USR_TYPE_FIELD = "USRTYPEO";

    /** {@code ERRMSGO}, copybook line 164. */
    public static final String ERR_MSG_FIELD = "ERRMSGO";

    // ---------------------------------------------------------------------------------------------
    // The three navigation field names. These are COMMAREA field names rather than map field names,
    // because that is where the values come from.
    // ---------------------------------------------------------------------------------------------

    /** {@code CDEMO-TO-PROGRAM}, {@code app/cpy/COCOM01Y.cpy} line 24 - the {@code XCTL} target. */
    public static final String NEXT_PROGRAM_FIELD = "CDEMO-TO-PROGRAM";

    /** {@code CDEMO-LAST-MAPSET}, {@code app/cpy/COCOM01Y.cpy} line 44. */
    public static final String NEXT_MAPSET_FIELD = "CDEMO-LAST-MAPSET";

    /** {@code CDEMO-LAST-MAP}, {@code app/cpy/COCOM01Y.cpy} line 43. */
    public static final String NEXT_MAP_FIELD = "CDEMO-LAST-MAP";

    // =============================================================================================
    // Declared widths. Every one is a literal read straight off an xxxO PICTURE clause - never
    // inferred from a sample value, never derived from another constant, and never shared between
    // two fields that merely happen to be the same width today.
    // =============================================================================================

    /** {@code TRNNAMEO PIC X(4)}, copybook line 98. */
    public static final int TRN_NAME_LENGTH = 4;

    /** {@code TITLE01O PIC X(40)}, copybook line 104. */
    public static final int TITLE01_LENGTH = 40;

    /** {@code CURDATEO PIC X(8)}, copybook line 110 - {@code MM/DD/YY}. */
    public static final int CUR_DATE_LENGTH = 8;

    /** {@code PGMNAMEO PIC X(8)}, copybook line 116. */
    public static final int PGM_NAME_LENGTH = 8;

    /** {@code TITLE02O PIC X(40)}, copybook line 122. */
    public static final int TITLE02_LENGTH = 40;

    /**
     * {@code CURTIMEO PIC X(8)}, copybook line 128 - {@code HH:MM:SS}.
     *
     * <p><strong>Eight, not nine.</strong> {@code COSGN00} is the only mapset in the application
     * that declares a nine-character time field. Copying that width here would shift the rendered
     * header and would not match what line 315 writes.
     */
    public static final int CUR_TIME_LENGTH = 8;

    /** {@code USRIDINO PIC X(8)}, copybook line 134 - and {@code SEC-USR-ID PIC X(08)}. */
    public static final int USR_ID_IN_LENGTH = 8;

    /** {@code FNAMEO PIC X(20)}, copybook line 140 - and {@code SEC-USR-FNAME PIC X(20)}. */
    public static final int FNAME_LENGTH = 20;

    /** {@code LNAMEO PIC X(20)}, copybook line 146 - and {@code SEC-USR-LNAME PIC X(20)}. */
    public static final int LNAME_LENGTH = 20;

    /**
     * {@code PASSWDO PIC X(8)}, copybook line 152 - and {@code SEC-USR-PWD PIC X(08)} in
     * {@code app/cpy/CSUSR01Y.cpy} line 21, the field line 169 moves from. The two widths agree, so
     * the move at line 169 neither pads nor truncates.
     */
    public static final int PASSWD_LENGTH = 8;

    /** {@code USRTYPEO PIC X(1)}, copybook line 158 - and {@code SEC-USR-TYPE PIC X(01)}. */
    public static final int USR_TYPE_LENGTH = 1;

    /**
     * {@code ERRMSGO PIC X(78)}, copybook line 164.
     *
     * <p><strong>78, not 80.</strong> {@code WS-MESSAGE} is {@code PIC X(80)} at
     * {@code app/cbl/COUSR02C.cbl} line 38, so line 270's move into this field discards the last two
     * characters. The receiving width is declared here; the truncation is not performed here.
     */
    public static final int ERR_MSG_LENGTH = 78;

    /**
     * {@code CDEMO-TO-PROGRAM PIC X(08)}, {@code app/cpy/COCOM01Y.cpy} line 24. Eight, because
     * program names in this application really are eight characters - {@code COUSR02C},
     * {@code COADM01C}, {@code COSGN00C}.
     */
    public static final int NEXT_PROGRAM_LENGTH = 8;

    /**
     * {@code CDEMO-LAST-MAPSET PIC X(7)}, {@code app/cpy/COCOM01Y.cpy} line 44 - <strong>seven, not
     * eight</strong>. {@link #MAPSET_NAME} is exactly seven characters, which is the corroboration.
     */
    public static final int NEXT_MAPSET_LENGTH = 7;

    /**
     * {@code CDEMO-LAST-MAP PIC X(7)}, {@code app/cpy/COCOM01Y.cpy} line 43 - <strong>seven, not
     * eight</strong>. A symbolic-map group item is a seven-character map name plus a one-character
     * direction suffix, so {@link #MAP_NAME} plus {@code 'O'} gives {@link #GROUP_NAME}.
     */
    public static final int NEXT_MAP_LENGTH = 7;

    /**
     * The twelve map-derived field names in <strong>screen order</strong>, which is the order
     * {@code grep -E '^[A-Z0-9]+ +DFHMDF' app/bms/COUSR02.bms} reports and therefore the order a
     * field-by-field comparison walks.
     *
     * <p>Note where {@link #USR_ID_IN_FIELD} sits: seventh, <em>before</em> the two name fields.
     * That is this screen's order and it differs from {@code COUSR01}'s.
     *
     * <p>Immutable and safe to publish: {@link List#of} is used for the value, so the constant
     * cannot be reassigned and the list it holds cannot be modified. Its size is
     * {@value #MAP_FIELD_COUNT}.
     */
    public static final List<String> MAP_FIELD_NAMES = List.of(TRN_NAME_FIELD,
            TITLE01_FIELD,
            CUR_DATE_FIELD,
            PGM_NAME_FIELD,
            TITLE02_FIELD,
            CUR_TIME_FIELD,
            USR_ID_IN_FIELD,
            FNAME_FIELD,
            LNAME_FIELD,
            PASSWD_FIELD,
            USR_TYPE_FIELD,
            ERR_MSG_FIELD);

    /**
     * What {@link #toString()} prints in place of the password.
     *
     * <p>It is chosen so that it can never be mistaken for a value: it contains characters no
     * {@code PIC X(8)} screen field would carry and it is not eight characters long.
     */
    private static final String PASSWORD_PLACEHOLDER = "****(withheld)";

    /** A single space, repeated to build a blank field of any declared width. */
    private static final String SPACE = " ";

    // =============================================================================================
    // Construction.
    // =============================================================================================

    /**
     * Checks every character member against the width its {@code xxxO} item declares, and supplies
     * an initialised communication area in place of a {@code null} one.
     *
     * <p>Two rules, and the asymmetry between them is deliberate:
     *
     * <ul>
     *   <li><strong>A value longer than its declared width is rejected.</strong> Accepting it would
     *       require discarding the surplus, and a payload constructor is the wrong place for that:
     *       the loss would be invisible at the call site. COBOL's alphanumeric {@code MOVE}
     *       truncates on the <em>right</em>, and where the program relies on that - line 270 moving
     *       an 80-character {@code WS-MESSAGE} into a 78-character {@code ERRMSGO} - the controller
     *       performs it deliberately through {@code common.FixedWidthCodec.movePicX}, whose name
     *       states the direction. The exception message names the field, both widths and the
     *       offending value, and points at that method.</li>
     *   <li><strong>A value shorter than its declared width is accepted unchanged.</strong> Nothing
     *       is lost, and the field's rendering to its full width is the codec's job at the point the
     *       fixed-width image is produced. Padding here would duplicate a move rule that is
     *       deliberately kept in one seam.</li>
     * </ul>
     *
     * <p>{@code null} is rejected for every character member, because a COBOL {@code PIC X} field is
     * never absent - an unset one holds spaces, which is exactly what {@link #blank()} produces.
     * {@link #navigationContext} is the one member treated differently: a {@code null} there is
     * replaced by {@link NavigationContext#empty()}, the initialised 160-byte area a cold start
     * sees, so that a payload arriving without a communication area behaves like a first entry
     * rather than failing. That mirrors {@code app/cbl/COUSR02C.cbl} line 90, where an
     * {@code EIBCALEN} of zero is a recognised state and not an error.
     *
     * <p>All fifteen character checks are delegated to one shared guard, so the rule is stated once
     * and every member is held to the identical standard.
     *
     * @throws NullPointerException     if any character member is {@code null}
     * @throws IllegalArgumentException if any character member is longer than the width its
     *                                  {@code PICTURE} clause declares
     */
    public UserUpdateResponse {
        trnName = requireWidth(trnName, TRN_NAME_LENGTH, TRN_NAME_FIELD);
        title01 = requireWidth(title01, TITLE01_LENGTH, TITLE01_FIELD);
        curDate = requireWidth(curDate, CUR_DATE_LENGTH, CUR_DATE_FIELD);
        pgmName = requireWidth(pgmName, PGM_NAME_LENGTH, PGM_NAME_FIELD);
        title02 = requireWidth(title02, TITLE02_LENGTH, TITLE02_FIELD);
        curTime = requireWidth(curTime, CUR_TIME_LENGTH, CUR_TIME_FIELD);
        usrIdIn = requireWidth(usrIdIn, USR_ID_IN_LENGTH, USR_ID_IN_FIELD);
        fName = requireWidth(fName, FNAME_LENGTH, FNAME_FIELD);
        lName = requireWidth(lName, LNAME_LENGTH, LNAME_FIELD);
        passwd = requireWidth(passwd, PASSWD_LENGTH, PASSWD_FIELD);
        usrType = requireWidth(usrType, USR_TYPE_LENGTH, USR_TYPE_FIELD);
        errMsg = requireWidth(errMsg, ERR_MSG_LENGTH, ERR_MSG_FIELD);
        // A COMMAREA that was not supplied is an initialised COMMAREA, not an error: COUSR02C
        // line 90 treats EIBCALEN = 0 as a recognised cold start.
        navigationContext = navigationContext == null ? NavigationContext.empty() : navigationContext;
        nextProgram = requireWidth(nextProgram, NEXT_PROGRAM_LENGTH, NEXT_PROGRAM_FIELD);
        nextMapset = requireWidth(nextMapset, NEXT_MAPSET_LENGTH, NEXT_MAPSET_FIELD);
        nextMap = requireWidth(nextMap, NEXT_MAP_LENGTH, NEXT_MAP_FIELD);
    }

    /**
     * The screen as {@code EXEC CICS SEND ... ERASE} leaves it: every one of the twelve map fields a
     * run of spaces of its declared width, no navigation target set, and an initialised
     * communication area.
     *
     * <p>This is the starting point for building a response, and the reason no member ever needs to
     * be {@code null}. It corresponds to {@code app/cbl/COUSR02C.cbl} line 97's
     * {@code MOVE LOW-VALUES TO COUSR2AO} followed by the {@code ERASE} on the send at lines
     * 271-278, and to {@code INITIALIZE-ALL-FIELDS} at lines 403-411 which blanks
     * {@code USRIDINI}, {@code FNAMEI}, {@code LNAMEI}, {@code PASSWDI}, {@code USRTYPEI} and
     * {@code WS-MESSAGE} together.
     *
     * <p>The password field is blank here, which is the state a <em>failed</em> lookup leaves it in:
     * lines 155-158 blank it before the read at line 163, and only line 169 fills it again once the
     * read has succeeded.
     *
     * @return a blank response, never {@code null}
     */
    public static UserUpdateResponse blank() {
        return new UserUpdateResponse(spaces(TRN_NAME_LENGTH),
                spaces(TITLE01_LENGTH),
                spaces(CUR_DATE_LENGTH),
                spaces(PGM_NAME_LENGTH),
                spaces(TITLE02_LENGTH),
                spaces(CUR_TIME_LENGTH),
                spaces(USR_ID_IN_LENGTH),
                spaces(FNAME_LENGTH),
                spaces(LNAME_LENGTH),
                spaces(PASSWD_LENGTH),
                spaces(USR_TYPE_LENGTH),
                spaces(ERR_MSG_LENGTH),
                NavigationContext.empty(),
                spaces(NEXT_PROGRAM_LENGTH),
                spaces(NEXT_MAPSET_LENGTH),
                spaces(NEXT_MAP_LENGTH));
    }

    // =============================================================================================
    // Derivation. Each method returns a new instance, because the type is immutable: a response that
    // has already been handed to a collaborator cannot be changed underneath it.
    // =============================================================================================

    /**
     * A copy with {@code TRNNAMEO} replaced - the transaction name written at line 302.
     *
     * @param newTrnName the transaction name, normally {@link #TRANSACTION_ID}; at most
     *                   {@value #TRN_NAME_LENGTH} characters
     * @return a new response
     * @throws NullPointerException     if {@code newTrnName} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #TRN_NAME_LENGTH} characters
     */
    public UserUpdateResponse withTrnName(String newTrnName) {
        return new UserUpdateResponse(newTrnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, passwd, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap);
    }

    /**
     * A copy with {@code TITLE01O} replaced - the first title line written at line 300 from
     * {@code CCDA-TITLE01}, the literal owned by {@code common.ScreenTitles}.
     *
     * @param newTitle01 the title text; at most {@value #TITLE01_LENGTH} characters
     * @return a new response
     * @throws NullPointerException     if {@code newTitle01} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #TITLE01_LENGTH} characters
     */
    public UserUpdateResponse withTitle01(String newTitle01) {
        return new UserUpdateResponse(trnName, newTitle01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, passwd, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap);
    }

    /**
     * A copy with {@code CURDATEO} replaced - the {@code MM/DD/YY} date written at line 309 from the
     * header owned by {@code common.DateHeader}.
     *
     * @param newCurDate the date text; at most {@value #CUR_DATE_LENGTH} characters
     * @return a new response
     * @throws NullPointerException     if {@code newCurDate} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #CUR_DATE_LENGTH} characters
     */
    public UserUpdateResponse withCurDate(String newCurDate) {
        return new UserUpdateResponse(trnName, title01, newCurDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, passwd, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap);
    }

    /**
     * A copy with {@code PGMNAMEO} replaced - the program name written at line 303.
     *
     * @param newPgmName the program name, normally {@link #PROGRAM_NAME}; at most
     *                   {@value #PGM_NAME_LENGTH} characters
     * @return a new response
     * @throws NullPointerException     if {@code newPgmName} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #PGM_NAME_LENGTH} characters
     */
    public UserUpdateResponse withPgmName(String newPgmName) {
        return new UserUpdateResponse(trnName, title01, curDate, newPgmName, title02, curTime,
                usrIdIn, fName, lName, passwd, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap);
    }

    /**
     * A copy with {@code TITLE02O} replaced - the second title line written at line 301.
     *
     * @param newTitle02 the title text; at most {@value #TITLE02_LENGTH} characters
     * @return a new response
     * @throws NullPointerException     if {@code newTitle02} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #TITLE02_LENGTH} characters
     */
    public UserUpdateResponse withTitle02(String newTitle02) {
        return new UserUpdateResponse(trnName, title01, curDate, pgmName, newTitle02, curTime,
                usrIdIn, fName, lName, passwd, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap);
    }

    /**
     * A copy with {@code CURTIMEO} replaced - the {@code HH:MM:SS} time written at line 315.
     *
     * @param newCurTime the time text; at most {@value #CUR_TIME_LENGTH} characters, which is eight
     *                   and not nine
     * @return a new response
     * @throws NullPointerException     if {@code newCurTime} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #CUR_TIME_LENGTH} characters
     */
    public UserUpdateResponse withCurTime(String newCurTime) {
        return new UserUpdateResponse(trnName, title01, curDate, pgmName, title02, newCurTime,
                usrIdIn, fName, lName, passwd, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap);
    }

    /**
     * A copy with {@code USRIDINO} replaced - the user id being updated.
     *
     * <p>Named for {@code USRIDIN} as this map declares it at copybook line 134. The value arrives
     * either from what the operator typed or from the row the user list selected, which
     * {@code app/cbl/COUSR02C.cbl} lines 99-102 move into {@code USRIDINI}.
     *
     * @param newUsrIdIn the user id; at most {@value #USR_ID_IN_LENGTH} characters
     * @return a new response
     * @throws NullPointerException     if {@code newUsrIdIn} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #USR_ID_IN_LENGTH} characters
     */
    public UserUpdateResponse withUsrIdIn(String newUsrIdIn) {
        return new UserUpdateResponse(trnName, title01, curDate, pgmName, title02, curTime,
                newUsrIdIn, fName, lName, passwd, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap);
    }

    /**
     * A copy with {@code FNAMEO} replaced - the first name, which line 167 moves from
     * {@code SEC-USR-FNAME}.
     *
     * @param newFName the first name; at most {@value #FNAME_LENGTH} characters
     * @return a new response
     * @throws NullPointerException     if {@code newFName} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #FNAME_LENGTH} characters
     */
    public UserUpdateResponse withFName(String newFName) {
        return new UserUpdateResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, newFName, lName, passwd, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap);
    }

    /**
     * A copy with {@code LNAMEO} replaced - the last name, which line 168 moves from
     * {@code SEC-USR-LNAME}.
     *
     * @param newLName the last name; at most {@value #LNAME_LENGTH} characters
     * @return a new response
     * @throws NullPointerException     if {@code newLName} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #LNAME_LENGTH} characters
     */
    public UserUpdateResponse withLName(String newLName) {
        return new UserUpdateResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, newLName, passwd, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap);
    }

    /**
     * A copy with {@code PASSWDO} replaced - the stored plaintext password that
     * <strong>{@code app/cbl/COUSR02C.cbl} line 169</strong> moves from {@code SEC-USR-PWD} onto the
     * screen.
     *
     * <p>This method exists because that line exists. The value is stored and returned exactly as
     * given: not hashed, not encoded and not masked, because any of those would change observable
     * behaviour and none of them is what the legacy program does. The terminal hides the characters
     * by way of the {@code DRK} attribute on {@code app/bms/COUSR02.bms} line 130, which is a
     * rendering concern and not this payload's. Only {@link #toString()} withholds the value, and it
     * does so because a diagnostic rendering is the one place a credential leaks by accident.
     *
     * @param newPasswd the plaintext password; at most {@value #PASSWD_LENGTH} characters, matching
     *                  {@code SEC-USR-PWD PIC X(08)}
     * @return a new response
     * @throws NullPointerException     if {@code newPasswd} is {@code null}; pass spaces to blank
     *                                  the field as lines 155-158 do
     * @throws IllegalArgumentException if it exceeds {@value #PASSWD_LENGTH} characters
     */
    public UserUpdateResponse withPasswd(String newPasswd) {
        return new UserUpdateResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, newPasswd, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap);
    }

    /**
     * A copy with {@code USRTYPEO} replaced - the user type, which line 170 moves from
     * {@code SEC-USR-TYPE}.
     *
     * @param newUsrType the user type, {@code 'A'} for an administrator or {@code 'U'} for a regular
     *                   user as the screen's own {@code '(A=Admin, U=User)'} legend states; at most
     *                   {@value #USR_TYPE_LENGTH} character
     * @return a new response
     * @throws NullPointerException     if {@code newUsrType} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #USR_TYPE_LENGTH} character
     */
    public UserUpdateResponse withUsrType(String newUsrType) {
        return new UserUpdateResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, passwd, newUsrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap);
    }

    /**
     * A copy with {@code ERRMSGO} replaced - the message line written at lines 88 and 270.
     *
     * <p>The receiving width is {@value #ERR_MSG_LENGTH}. An over-long message is rejected rather
     * than trimmed to fit, so the 80-to-78 narrowing that line 270 performs stays an explicit act
     * carried out by the controller through {@code common.FixedWidthCodec.movePicX}.
     *
     * @param newErrMsg the message text; at most {@value #ERR_MSG_LENGTH} characters
     * @return a new response
     * @throws NullPointerException     if {@code newErrMsg} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #ERR_MSG_LENGTH} characters
     */
    public UserUpdateResponse withErrMsg(String newErrMsg) {
        return new UserUpdateResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, passwd, usrType, newErrMsg, navigationContext, nextProgram,
                nextMapset, nextMap);
    }

    /**
     * A copy carrying a different communication area - the 160-byte {@code CARDDEMO-COMMAREA} that
     * line 260 hands back on the {@code XCTL}.
     *
     * @param newNavigationContext the communication area; {@code null} is replaced by
     *                             {@link NavigationContext#empty()}
     * @return a new response
     */
    public UserUpdateResponse withNavigationContext(NavigationContext newNavigationContext) {
        return new UserUpdateResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, passwd, usrType, errMsg, newNavigationContext, nextProgram,
                nextMapset, nextMap);
    }

    /**
     * A copy naming a different transfer target - the {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} of line
     * 259, projected as response data so the client performs the navigation.
     *
     * <p>The values the program itself uses are {@code 'COADM01C'} on {@code PF3} with a blank
     * {@code CDEMO-FROM-PROGRAM} (line 114) and on {@code PF12} (line 125), {@code 'COSGN00C'} on
     * the cold start (line 91), and otherwise whatever {@code CDEMO-FROM-PROGRAM} holds.
     *
     * @param newNextProgram the program to transfer to; at most {@value #NEXT_PROGRAM_LENGTH}
     *                       characters
     * @return a new response
     * @throws NullPointerException     if {@code newNextProgram} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #NEXT_PROGRAM_LENGTH} characters
     */
    public UserUpdateResponse withNextProgram(String newNextProgram) {
        return new UserUpdateResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, passwd, usrType, errMsg, navigationContext, newNextProgram,
                nextMapset, nextMap);
    }

    /**
     * A copy naming a different mapset to display next.
     *
     * @param newNextMapset the mapset name, {@link #MAPSET_NAME} for this screen; at most
     *                      {@value #NEXT_MAPSET_LENGTH} characters - seven, not eight
     * @return a new response
     * @throws NullPointerException     if {@code newNextMapset} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #NEXT_MAPSET_LENGTH} characters
     */
    public UserUpdateResponse withNextMapset(String newNextMapset) {
        return new UserUpdateResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, passwd, usrType, errMsg, navigationContext, nextProgram,
                newNextMapset, nextMap);
    }

    /**
     * A copy naming a different map to display next.
     *
     * @param newNextMap the map name, {@link #MAP_NAME} for this screen; at most
     *                   {@value #NEXT_MAP_LENGTH} characters - seven, not eight
     * @return a new response
     * @throws NullPointerException     if {@code newNextMap} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #NEXT_MAP_LENGTH} characters
     */
    public UserUpdateResponse withNextMap(String newNextMap) {
        return new UserUpdateResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, passwd, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, newNextMap);
    }

    // =============================================================================================
    // Field projection, for field-by-field comparison and for tracing every member back to its
    // DFHMDF definition.
    // =============================================================================================

    /**
     * The twelve map-derived values keyed by the {@code xxxO} name the copybook spells, in screen
     * order.
     *
     * <p>The map has exactly {@value #MAP_FIELD_COUNT} entries and its key sequence is
     * {@link #MAP_FIELD_NAMES}, so a field-by-field comparison walks the fields in the order the
     * screen declares them rather than in an arbitrary one. The password is
     * <strong>included</strong>: omitting it would hide a real difference from a comparison whose
     * whole purpose is to find differences. It is {@link #toString()} that withholds it, because a
     * diagnostic rendering is what a log line picks up by accident.
     *
     * <p>The four navigation members are deliberately absent. They are not part of
     * {@code COUSR2AO} and have no {@code DFHMDF} definition, so including them would corrupt a
     * comparison against the symbolic map. {@link #navigationContext()},
     * {@link #nextProgram()}, {@link #nextMapset()} and {@link #nextMap()} expose them directly.
     *
     * <p>Values are returned exactly as held, which means a value shorter than its declared width is
     * returned short rather than padded. Rendering each field to the full width its {@code PICTURE}
     * clause declares - available from the {@code *_LENGTH} constants - is the codec's job at the
     * point the fixed-width image is produced, and is not duplicated here.
     *
     * @return an unmodifiable, screen-ordered map of all {@value #MAP_FIELD_COUNT} field names to
     *         their values, never {@code null}
     */
    public Map<String, String> fieldValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(TRN_NAME_FIELD, trnName);
        values.put(TITLE01_FIELD, title01);
        values.put(CUR_DATE_FIELD, curDate);
        values.put(PGM_NAME_FIELD, pgmName);
        values.put(TITLE02_FIELD, title02);
        values.put(CUR_TIME_FIELD, curTime);
        values.put(USR_ID_IN_FIELD, usrIdIn);
        values.put(FNAME_FIELD, fName);
        values.put(LNAME_FIELD, lName);
        values.put(PASSWD_FIELD, passwd);
        values.put(USR_TYPE_FIELD, usrType);
        values.put(ERR_MSG_FIELD, errMsg);
        // Collections.unmodifiableMap over a LinkedHashMap, deliberately NOT Map.copyOf: the copy
        // factory returns an unordered map, which would scramble screen declaration order and leave
        // a field-by-field comparison reporting its differences in an arbitrary sequence.
        return Collections.unmodifiableMap(values);
    }

    /**
     * One map-derived value, looked up by the {@code xxxO} name the copybook spells.
     *
     * <p>An unrecognised name is rejected rather than answered with {@code null}, so a misspelling -
     * {@code "USERIDO"} for {@code "USRIDINO"}, say, which is exactly the mistake the
     * {@code COUSR01} naming difference invites - fails at the point of the mistake instead of
     * comparing a {@code null} against an expectation and reporting a puzzling difference.
     *
     * @param cobolName the {@code xxxO} field name, one of {@link #MAP_FIELD_NAMES}
     * @return that field's value, exactly as held
     * @throws NullPointerException     if {@code cobolName} is {@code null}
     * @throws IllegalArgumentException if {@code cobolName} is not one of the
     *                                  {@value #MAP_FIELD_COUNT} field names, with the names that
     *                                  are valid listed
     */
    public String value(String cobolName) {
        Objects.requireNonNull(cobolName, "A field name is required to look up a value of "
                + GROUP_NAME + "; the " + MAP_FIELD_COUNT + " valid names are " + MAP_FIELD_NAMES);
        String found = fieldValues().get(cobolName);
        if (found == null) {
            throw new IllegalArgumentException("'" + cobolName + "' is not a field of " + GROUP_NAME
                    + ". The " + MAP_FIELD_COUNT + " fields of this map, in screen order, are "
                    + MAP_FIELD_NAMES + ". Note that this map spells its user id field "
                    + USR_ID_IN_FIELD + ", which COUSR01 spells USERIDO; the two are deliberately "
                    + "not harmonised. The attribute items - the xxxC colour byte, xxxP, xxxH, xxxV, "
                    + "and the xxxL length and xxxF flag of the input view - are presentation "
                    + "metadata and are not fields of this payload");
        }
        return found;
    }

    // =============================================================================================
    // Diagnostics.
    // =============================================================================================

    /**
     * A single-line description that <strong>never</strong> includes the password.
     *
     * <p>A record's generated rendering would print all sixteen components, the plaintext password
     * among them, and would then leak it into any log line, exception message or debugger dump that
     * happened to touch a response. This override substitutes {@code ****(withheld)} for that one
     * component and leaves the others as they are held, padding included, since the padding is part
     * of each value.
     *
     * <p>This is a <em>logging</em> concern and not a payload concern, and the distinction is the
     * whole point. The JSON member is untouched: {@link #passwd()} returns the value in full, and
     * {@link #fieldValues()} includes it, because {@code app/cbl/COUSR02C.cbl} line 169 sends it to
     * the screen and a like-for-like migration reproduces that. Only this rendering withholds it -
     * masking here removes an accidental disclosure without removing any observable behaviour.
     *
     * <p>{@link #equals(Object)} and {@link #hashCode()} are the record's generated implementations
     * and do include the password, so two responses that differ only in that field are correctly
     * unequal.
     *
     * @return a description of this response with the password masked, never {@code null}
     */
    @Override
    public String toString() {
        return GROUP_NAME + "["
                + TRN_NAME_FIELD + "='" + trnName + "', "
                + TITLE01_FIELD + "='" + title01 + "', "
                + CUR_DATE_FIELD + "='" + curDate + "', "
                + PGM_NAME_FIELD + "='" + pgmName + "', "
                + TITLE02_FIELD + "='" + title02 + "', "
                + CUR_TIME_FIELD + "='" + curTime + "', "
                + USR_ID_IN_FIELD + "='" + usrIdIn + "', "
                + FNAME_FIELD + "='" + fName + "', "
                + LNAME_FIELD + "='" + lName + "', "
                + PASSWD_FIELD + "=" + PASSWORD_PLACEHOLDER + ", "
                + USR_TYPE_FIELD + "='" + usrType + "', "
                + ERR_MSG_FIELD + "='" + errMsg + "', "
                + NEXT_PROGRAM_FIELD + "='" + nextProgram + "', "
                + NEXT_MAPSET_FIELD + "='" + nextMapset + "', "
                + NEXT_MAP_FIELD + "='" + nextMap + "'"
                + "]";
    }

    // =============================================================================================
    // Private helpers. One guard, stated once, applied to all fifteen character members.
    // =============================================================================================

    /**
     * What a width failure prints in place of the value it rejected.
     *
     * <p>This guard covers all fifteen character members, and one of them is {@link #passwd()} -
     * {@code PASSWDO}, which {@code app/cbl/COUSR02C.cbl:169} fills with the stored plaintext
     * {@code SEC-USR-PWD}. An exception message is a diagnostic: it reaches a log file, a stack trace
     * and, before {@code config.WebConfig.CobolErrorHandler} answers, an exception handler's own
     * rendering. Interpolating the rejected value would therefore write a password - or any other
     * field's contents - to all three. The failure names the field, its declared width and the length
     * that arrived, which is everything a caller needs in order to correct the call, and nothing more.
     */
    private static final String REJECTED_VALUE_REDACTED = "[REDACTED]";

    /**
     * Rejects a {@code null} value and one wider than its {@code PICTURE} clause declares, and
     * returns the value unchanged when it fits.
     *
     * <p>A shorter value is accepted deliberately: no information is lost, and padding it to the
     * declared width is the codec's job when the fixed-width image is rendered. An over-wide value
     * is refused rather than truncated, because discarding characters inside a payload constructor
     * would hide the loss from the call site - and COBOL's right-truncating alphanumeric
     * {@code MOVE} is available explicitly through {@code common.FixedWidthCodec.movePicX}, whose
     * name states the direction.
     *
     * <p>The failure message reports the field, its declared width and the actual length, and
     * <strong>never the value</strong> - see {@link #REJECTED_VALUE_REDACTED}.
     *
     * @param value        the value offered for the field
     * @param declaredWidth the width the field's {@code PICTURE} clause declares
     * @param cobolName    the field's COBOL name, used to identify it in any failure
     * @return {@code value} unchanged
     */
    private static String requireWidth(String value, int declaredWidth, String cobolName) {
        Objects.requireNonNull(value, "Field " + cobolName + " of " + GROUP_NAME + " requires a "
                + "value; there is no null in a COBOL record, so move SPACES explicitly or start "
                + "from UserUpdateResponse.blank()");
        if (value.length() > declaredWidth) {
            throw new IllegalArgumentException("Field " + cobolName + " of " + GROUP_NAME
                    + " is declared PIC X(" + declaredWidth + ") but was given " + value.length()
                    + " character(s) (value " + REJECTED_VALUE_REDACTED + "). This payload never "
                    + "truncates, so that the loss of a character is always a deliberate act rather "
                    + "than a silent one. To shorten the value, pass it through "
                    + "FixedWidthCodec.movePicX(value, " + declaredWidth + "), which truncates on "
                    + "the right as a COBOL alphanumeric MOVE does");
        }
        return value;
    }

    /**
     * A blank field of the given declared width: the {@code SPACES} figurative constant repeated.
     *
     * <p>This is COBOL's unconditional space fill, which is not the alphanumeric {@code MOVE} rule.
     * The {@code MOVE} rule, which pads a shorter sending value and truncates a longer one, lives
     * solely in {@code common.FixedWidthCodec} and is never reimplemented here.
     *
     * @param width the field's declared width
     * @return a string of exactly {@code width} spaces
     */
    private static String spaces(int width) {
        return SPACE.repeat(width);
    }
}
