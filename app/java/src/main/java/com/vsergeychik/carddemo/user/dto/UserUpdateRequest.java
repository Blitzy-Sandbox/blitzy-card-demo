package com.vsergeychik.carddemo.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ResponseOnlyMembers;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The inbound payload of {@code PUT /api/users/{userId}} - CICS transaction {@code CU02}, program
 * {@code app/cbl/COUSR02C.cbl}, mapset {@code COUSR02}, map {@code COUSR2A}.
 *
 * <p>It is a field-for-field projection of the twelve {@code xxxI} items of {@code 01 COUSR2AI} in
 * {@code app/cpy-bms/COUSR02.CPY}, plus the three conversation-state members that CICS carried
 * outside the map. Nothing else. This type holds <strong>no logic</strong>: no change detection, no
 * validation method, no repository call, no message text. It is a screen contract transcribed into
 * Java, and every one of its payload members traces to a name-labelled {@code DFHMDF} line of
 * {@code app/bms/COUSR02.bms}.
 *
 * <h2>The twelve payload members, in the map's own order</h2>
 *
 * The order below is the declaration order of {@code 01 COUSR2AI} and, independently, the order of
 * the name-labelled {@code DFHMDF} definitions in the mapset. The two agree, and so do the widths:
 * every {@code LENGTH=} in {@code app/bms/COUSR02.bms} equals the corresponding {@code xxxI}
 * {@code PICTURE} length in {@code app/cpy-bms/COUSR02.CPY}. Each width below is therefore a
 * measured fact with two independent sources, not an inference.
 *
 * <table border="1">
 *   <caption>{@code COUSR2A} screen fields and their Java members</caption>
 *   <tr><th>#</th><th>{@code DFHMDF}</th><th>{@code xxxI} item</th><th>Picture</th>
 *       <th>Member</th><th>Provenance</th></tr>
 *   <tr><td>1</td><td>{@code TRNNAME}</td><td>{@code TRNNAMEI}</td><td>{@code PIC X(4)}</td>
 *       <td>{@link #trnName()}</td>
 *       <td>{@code WS-TRANID VALUE 'CU02'}, {@code COUSR02C.cbl:37}, sent at
 *           {@code COUSR02C.cbl:302}</td></tr>
 *   <tr><td>2</td><td>{@code TITLE01}</td><td>{@code TITLE01I}</td><td>{@code PIC X(40)}</td>
 *       <td>{@link #title01()}</td>
 *       <td>{@code CCDA-TITLE01} of {@code app/cpy/COTTL01Y.cpy}, sent at
 *           {@code COUSR02C.cbl:300}</td></tr>
 *   <tr><td>3</td><td>{@code CURDATE}</td><td>{@code CURDATEI}</td><td>{@code PIC X(8)}</td>
 *       <td>{@link #curDate()}</td>
 *       <td>{@code WS-CURDATE-MM-DD-YY}, {@code mm/dd/yy}, sent at
 *           {@code COUSR02C.cbl:309}</td></tr>
 *   <tr><td>4</td><td>{@code PGMNAME}</td><td>{@code PGMNAMEI}</td><td>{@code PIC X(8)}</td>
 *       <td>{@link #pgmName()}</td>
 *       <td>{@code WS-PGMNAME VALUE 'COUSR02C'}, {@code COUSR02C.cbl:36}, sent at
 *           {@code COUSR02C.cbl:303}</td></tr>
 *   <tr><td>5</td><td>{@code TITLE02}</td><td>{@code TITLE02I}</td><td>{@code PIC X(40)}</td>
 *       <td>{@link #title02()}</td>
 *       <td>{@code CCDA-TITLE02} of {@code app/cpy/COTTL01Y.cpy}, sent at
 *           {@code COUSR02C.cbl:301}</td></tr>
 *   <tr><td>6</td><td>{@code CURTIME}</td><td>{@code CURTIMEI}</td><td>{@code PIC X(8)}</td>
 *       <td>{@link #curTime()}</td>
 *       <td>{@code WS-CURTIME-HH-MM-SS}, {@code hh:mm:ss}, sent at
 *           {@code COUSR02C.cbl:315}. <strong>Eight, not nine</strong></td></tr>
 *   <tr><td>7</td><td>{@code USRIDIN}</td><td>{@code USRIDINI}</td><td>{@code PIC X(8)}</td>
 *       <td>{@link #usrIdIn()}</td>
 *       <td>Keys {@code SEC-USR-ID PIC X(08)} at {@code COUSR02C.cbl:162} and
 *           {@code :216}. <strong>{@code USRIDIN}, not {@code USERID}</strong></td></tr>
 *   <tr><td>8</td><td>{@code FNAME}</td><td>{@code FNAMEI}</td><td>{@code PIC X(20)}</td>
 *       <td>{@link #fName()}</td>
 *       <td>Compared with {@code SEC-USR-FNAME PIC X(20)} at
 *           {@code COUSR02C.cbl:219}</td></tr>
 *   <tr><td>9</td><td>{@code LNAME}</td><td>{@code LNAMEI}</td><td>{@code PIC X(20)}</td>
 *       <td>{@link #lName()}</td>
 *       <td>Compared with {@code SEC-USR-LNAME PIC X(20)} at
 *           {@code COUSR02C.cbl:223}</td></tr>
 *   <tr><td>10</td><td>{@code PASSWD}</td><td>{@code PASSWDI}</td><td>{@code PIC X(8)}</td>
 *       <td>{@link #passwd()}</td>
 *       <td>Compared with {@code SEC-USR-PWD PIC X(08)} at {@code COUSR02C.cbl:227} and stored
 *           verbatim at {@code :228}. Plaintext - see below</td></tr>
 *   <tr><td>11</td><td>{@code USRTYPE}</td><td>{@code USRTYPEI}</td><td>{@code PIC X(1)}</td>
 *       <td>{@link #usrType()}</td>
 *       <td>Compared with {@code SEC-USR-TYPE PIC X(01)} at {@code COUSR02C.cbl:231}.
 *           {@code 'A'} administrator, {@code 'U'} regular user</td></tr>
 *   <tr><td>12</td><td>{@code ERRMSG}</td><td>{@code ERRMSGI}</td><td>{@code PIC X(78)}</td>
 *       <td>{@link #errMsg()}</td>
 *       <td>Receives {@code WS-MESSAGE} at {@code COUSR02C.cbl:270}.
 *           <strong>Seventy-eight, not eighty</strong></td></tr>
 * </table>
 *
 * The mapset declares <strong>twenty-nine</strong> {@code DFHMDF} definitions of which exactly
 * <strong>twelve</strong> carry a name. The other seventeen are literal screen furniture -
 * {@code 'Tran:'}, {@code 'Date:'}, {@code 'Prog:'}, {@code 'Time:'}, {@code 'Update User'},
 * {@code 'Enter User ID:'}, the seventy-character rule of asterisks, {@code 'First Name:'},
 * {@code 'Last Name:'}, {@code 'Password:'}, {@code '(8 Char)'}, {@code 'User Type: '},
 * {@code '(A=Admin, U=User)'}, three zero-length skip stops and the key legend
 * {@code 'ENTER=Fetch  F3=Save&&Exit  F4=Clear  F5=Save  F12=Cancel'}. A literal with no name has no
 * symbolic-map item and therefore cannot be sent or received, so none of them is a field here. The
 * three counts agree independently: twelve name-labelled {@code DFHMDF}, twelve {@code xxxI} items
 * and twelve {@code xxxO} items. {@link #MAP_FIELD_COUNT} pins that number and
 * {@link #MAP_FIELD_NAMES} pins the names and their order.
 *
 * <h2>Four members are individually addressable on purpose</h2>
 *
 * {@link #fName()}, {@link #lName()}, {@link #passwd()} and {@link #usrType()} are exactly the four
 * values {@code UPDATE-USER-INFO} compares against the stored record at
 * {@code app/cbl/COUSR02C.cbl:219-234}. That block is <em>four separate</em> {@code IF ... NOT = ...}
 * statements, each independently setting {@code USR-MODIFIED-YES}, not one combined condition:
 *
 * <pre>
 *  IF FNAMEI   OF COUSR2AI NOT = SEC-USR-FNAME  ... SET USR-MODIFIED-YES TO TRUE  END-IF
 *  IF LNAMEI   OF COUSR2AI NOT = SEC-USR-LNAME  ... SET USR-MODIFIED-YES TO TRUE  END-IF
 *  IF PASSWDI  OF COUSR2AI NOT = SEC-USR-PWD    ... SET USR-MODIFIED-YES TO TRUE  END-IF
 *  IF USRTYPEI OF COUSR2AI NOT = SEC-USR-TYPE   ... SET USR-MODIFIED-YES TO TRUE  END-IF
 * </pre>
 *
 * Each surviving as its own member is what lets {@code UserUpdateController} reproduce the four tests
 * one for one. Collapsing them into a single "changes" object, a map or a bit set would make the
 * per-field {@code MOVE} that follows each test unreachable and would lose the ordering the source
 * establishes. The comparison itself belongs to the controller and is deliberately absent here.
 *
 * <h2>Nothing is tidied</h2>
 *
 * Four properties of this screen look like mistakes and are not. Each was checked against a sibling
 * mapset, and each divergence is real:
 *
 * <ul>
 *   <li><strong>The identifier is {@code USRIDIN}, not {@code USERID}.</strong>
 *       {@code app/bms/COUSR01.bms} names its identifier field {@code USERID}; this mapset and
 *       {@code app/bms/COUSR03.bms} both name it {@code USRIDIN}. The member is
 *       {@link #usrIdIn()} accordingly.</li>
 *   <li><strong>The identifier comes before the names.</strong> {@code COUSR01}'s field order is
 *       {@code ... CURTIME FNAME LNAME USERID PASSWD USRTYPE ERRMSG} - identifier after the names.
 *       This screen's is {@code ... CURTIME USRIDIN FNAME LNAME PASSWD USRTYPE ERRMSG} - identifier
 *       before them, which is why {@code USRIDIN} is at {@code POS=(6,21)} and the names at line 11.
 *       The member order follows this screen, not its sibling.</li>
 *   <li><strong>{@code CURTIME} is {@code X(8)}.</strong> {@code app/cpy-bms/COSGN00.CPY} declares
 *       {@code CURTIMEI PIC X(9)}; every other mapset, this one included, declares
 *       {@code PIC X(8)}. Only the sign-on screen uses nine.</li>
 *   <li><strong>{@code ERRMSG} is {@code X(78)} while the message that fills it is
 *       {@code X(80)}.</strong> {@code COUSR02C.cbl:38} declares {@code WS-MESSAGE PIC X(80)} and
 *       {@code COUSR02C.cbl:270} does {@code MOVE WS-MESSAGE TO ERRMSGO OF COUSR2AO}, so COBOL
 *       truncates two characters off the right on every send. The receiving width is 78 and that is
 *       what is declared here. <strong>The truncation is not performed in this file.</strong> It
 *       belongs to the controller, through the alphanumeric move rule of
 *       {@code common.FixedWidthCodec}, so that the direction of the loss stays visible at the one
 *       place it happens.</li>
 * </ul>
 *
 * Regularising any of the four would silently change a byte the parity differ compares field by
 * field, which is why the divergences are recorded here instead.
 *
 * <h2>There is no server-side session</h2>
 *
 * CICS is pseudo-conversational: {@code COUSR02C} ends after painting a screen and is re-entered
 * from {@code MAIN-PARA} when a key is pressed, so the only state that survives a turn is what the
 * program handed back. This migration keeps that shape exactly. All three items of conversation
 * state travel in the payload:
 *
 * <ul>
 *   <li>{@link #navigationContext()} - the 160-byte {@code CARDDEMO-COMMAREA} of
 *       {@code app/cpy/COCOM01Y.cpy}, which {@code COUSR02C.cbl:94} loads from
 *       {@code DFHCOMMAREA}.</li>
 *   <li>{@link #aid()} - the resolved attention identifier, the {@code EIBAID} that
 *       {@code COUSR02C.cbl:108} evaluates.</li>
 *   <li>The enter-versus-re-enter context, read <em>through</em> the communication area by
 *       {@link #contextIsEnter()} and {@link #contextIsReenter()} rather than stored again.</li>
 * </ul>
 *
 * Those three are the only members that do not trace to a {@code DFHMDF} line, and they are the
 * explicitly mandated exception: without them the program's branches are unreachable, because CICS
 * supplied them from the communication area and the exec interface block rather than from the map.
 * Each is marked as such at its declaration.
 *
 * <p>This type is consequently free of every server-side state mechanism: no servlet session
 * handle, no session-scoped or session-attribute binding, no thread-local, no static cache and no
 * static "current request" accessor. A static holder would be a session by another name and would
 * additionally break request isolation, so none exists: every field is final and every constant is
 * immutable.
 *
 * <h2>Credentials travel in the clear, deliberately</h2>
 *
 * {@link #passwd()} carries a plaintext eight-character password. {@code COUSR02C.cbl:227} compares
 * it byte for byte against {@code SEC-USR-PWD PIC X(08)} and {@code :228} stores it verbatim, and
 * {@code COUSR02C.cbl:169} moves the stored value straight back onto the screen. There is
 * <strong>no</strong> hashing, <strong>no</strong> password encoder, <strong>no</strong> digest,
 * <strong>no</strong> encoding and <strong>no</strong> security-framework type anywhere near this
 * class.
 *
 * <p>That is not an oversight and it is not an endorsement. Plaintext credential handling is an
 * <strong>inherited property of the legacy design</strong> and an <strong>explicit non-goal of this
 * migration</strong>: this is a like-for-like translation, so introducing a digest would change
 * observable behaviour - two different plaintexts that hash alike would stop being distinguished by
 * the comparison at {@code :227}, and the value echoed at {@code :169} would no longer round trip -
 * and it would pull in an authentication framework that is out of scope. The characteristic is
 * documented here so it stays visible to whoever reads this class rather than being quietly buried
 * in generated code. {@link #toString()} masks the value regardless, so the property does not become
 * a logging leak.
 *
 * <p>The mapset masks the field on the terminal instead: {@code app/bms/COUSR02.bms:130} declares
 * {@code PASSWD} as {@code ATTRB=(DRK,FSET,UNPROT)} with {@code HILIGHT=UNDERLINE}, and {@code DRK}
 * is non-display. That is a presentation attribute, so it is metadata rather than a reason to alter,
 * omit or transform this member.
 *
 * <h2>Validation stops at length, on purpose</h2>
 *
 * Each member carries {@code @Size(max = n)} taken from its {@code xxxI} {@code PICTURE} length, and
 * <strong>nothing else</strong>. In particular <strong>no presence constraint of any kind</strong> -
 * nothing that rejects a blank value and nothing that rejects a null one - because
 * {@code COUSR02C} <em>accepts</em> blank input and answers it with a message rather than refusing
 * the interaction. It does so twice over, once on the lookup path and again on the save path:
 *
 * <table border="1">
 *   <caption>The blank-field message chain, verbatim</caption>
 *   <tr><th>Site</th><th>Guard</th><th>Message</th></tr>
 *   <tr><td>{@code COUSR02C.cbl:146-148}</td><td>{@code USRIDINI = SPACES OR LOW-VALUES}</td>
 *       <td>{@code 'User ID can NOT be empty...'} (lookup)</td></tr>
 *   <tr><td>{@code COUSR02C.cbl:180-182}</td><td>{@code USRIDINI = SPACES OR LOW-VALUES}</td>
 *       <td>{@code 'User ID can NOT be empty...'} (save)</td></tr>
 *   <tr><td>{@code COUSR02C.cbl:186-188}</td><td>{@code FNAMEI = SPACES OR LOW-VALUES}</td>
 *       <td>{@code 'First Name can NOT be empty...'}</td></tr>
 *   <tr><td>{@code COUSR02C.cbl:192-194}</td><td>{@code LNAMEI = SPACES OR LOW-VALUES}</td>
 *       <td>{@code 'Last Name can NOT be empty...'}</td></tr>
 *   <tr><td>{@code COUSR02C.cbl:198-200}</td><td>{@code PASSWDI = SPACES OR LOW-VALUES}</td>
 *       <td>{@code 'Password can NOT be empty...'}</td></tr>
 *   <tr><td>{@code COUSR02C.cbl:204-206}</td><td>{@code USRTYPEI = SPACES OR LOW-VALUES}</td>
 *       <td>{@code 'User Type can NOT be empty...'}</td></tr>
 * </table>
 *
 * Rejecting a blank field with an HTTP 400 would replace six specific messages with one generic
 * failure and would skip the {@code MOVE -1 TO xxxL} cursor placement that accompanies each, so no
 * blank-rejecting or null-rejecting constraint may appear on any member. For the same reason there is
 * no format constraint on {@link #usrType()} - {@code COUSR02C} never tests it against {@code 'A'}
 * or {@code 'U'}, it only tests it for blankness at {@code :204} and for change at {@code :231} -
 * and no regular-expression, e-mail, custom or normalising constraint anywhere, because the program
 * performs no such check and no case folding.
 *
 * <p>Every character member accepts {@code null}, and the distinction is meaningful rather than
 * merely tolerated. The six guards above all test {@code = SPACES OR LOW-VALUES}, one condition over
 * two representations: a field the terminal did not transmit arrives as {@code LOW-VALUES}, which
 * {@code null} models, and one transmitted empty arrives as {@code SPACES}, which {@code ""} or a
 * run of spaces models. COBOL treats the two identically at every guard, so this type must be able
 * to carry both and must reject neither.
 *
 * <h2>Why the constructor validates nothing</h2>
 *
 * The canonical constructor is the one the record generates: it stores its arguments and checks
 * nothing. That is deliberate, and it is the one place where this type differs by design from
 * {@link NavigationContext} and {@code user.model.SecUserRecord}, whose constructors reject a null or
 * an over-wide value outright.
 *
 * <p>Those two are internal models reached only from Java, so a bad value there is a programming
 * error and failing fast is right. This type is an <em>inbound REST payload</em> deserialised from
 * whatever a client sends. Throwing from the constructor would abort deserialisation before Bean
 * Validation ever ran, which would make the {@code @Size} constraints unreachable and replace their
 * well-formed constraint violations with a deserialisation failure. Boundary enforcement therefore
 * belongs to {@code @Size} at the {@code @Valid @RequestBody} boundary, and the constructor stays
 * total: it accepts every payload the legacy screen could produce, including absent and blank
 * fields.
 *
 * <p>Over-width is not a state the legacy system could reach at all - a 3270 field accepts at most
 * its {@code LENGTH=} and {@code EXEC CICS RECEIVE MAP} cannot deliver more than the
 * {@code xxxI PICTURE} holds - so declaring it a constraint violation adds no behaviour that COBOL
 * had; it names a payload the screen could never have sent.
 *
 * <h2>There is no concurrency token, because the program has none</h2>
 *
 * {@code COUSR02C} has no {@code 9300-CHECK-CHANGE-IN-REC} paragraph. Its update sequence is a
 * keyed {@code EXEC CICS READ ... UPDATE} at {@code app/cbl/COUSR02C.cbl:322-331} followed by
 * {@code EXEC CICS REWRITE} at {@code :360-366}, and the record it rewrites is the one that read
 * returned. The four comparisons at {@code :219-234} are <em>change detection</em> - they decide
 * whether a rewrite is needed at all, and produce {@code 'Please modify to update ...'} when it is
 * not - and they are not a stale-read check: they compare the screen against the freshly re-read
 * record, not the freshly re-read record against the one the screen was painted from.
 *
 * <p>This payload therefore declares <strong>no concurrency token of any kind</strong>: no
 * generation counter, no record sequence number, no entity-tag field and no modification timestamp.
 * {@code AccountUpdateService} and {@code CardUpdateService} do reproduce a genuine optimistic
 * check, because {@code COACTUPC} and {@code COCRDUPC} each contain
 * {@code 9300-CHECK-CHANGE-IN-REC}; this screen does not, and inventing one here would add a
 * behaviour the legacy system never had.
 *
 * <h2>Serialisation</h2>
 *
 * There is no Jackson annotation on this class. Names are the member names, untransformed; nothing
 * is renamed, nothing is excluded when null or empty, and no value is trimmed. Trimming would be
 * actively wrong here: the change tests at {@code COUSR02C.cbl:219-234} compare a screen value
 * against a space-padded stored value, so trailing spaces are semantically significant and a round
 * trip has to preserve them byte for byte.
 *
 * <p>{@link #hasNavigationContext()}, {@link #contextIsEnter()} and {@link #contextIsReenter()} are
 * derived rather than stored, and their names are deliberately not bean-accessor shaped. A record
 * method named {@code isXxx()} would be collected as an extra JSON property, putting a value on the
 * wire that the canonical constructor cannot accept back and breaking a serialise-then-deserialise
 * round trip. Choosing names outside the {@code get}/{@code is} prefixes keeps them off the wire
 * without needing a Jackson ignore annotation, and that behaviour is asserted by test rather than
 * assumed. Their placement here rather than at the call site also keeps a null communication area
 * from becoming a null dereference in seventeen controllers.
 *
 * <h2>Its response twin</h2>
 *
 * {@code UserUpdateResponse} carries the same twelve names so a client can round trip a screen
 * without translating anything. It carries a password too, unlike the sign-on response, because
 * {@code COUSR02C.cbl:169} verifiably places the stored value back on the screen with
 * {@code MOVE SEC-USR-PWD TO PASSWDI OF COUSR2AI}.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * // First entry with no communication area: EIBCALEN = 0, COUSR02C.cbl:90.
 * UserUpdateRequest cold = new UserUpdateRequest(
 *         null, null, null, null, null, null,   // header fields, painted by the response
 *         null, null, null, null, null, null,   // USRIDIN..ERRMSG, nothing typed yet
 *         null,                                 // no commarea
 *         "ENTER",
 *         null);                                // no extension either; initial() is substituted
 * cold.hasNavigationContext();   // false - take the COUSR02C.cbl:90-92 branch
 *
 * // Re-entry: the client hands back the context it was given and presses PF5 to save.
 * // typedPassword is whatever the operator keyed into the DRK field; no literal is shown here,
 * // because a credential does not belong in source even as an illustration.
 * UserUpdateRequest save = new UserUpdateRequest(
 *         "CU02", title01, "07/19/22", "COUSR02C", title02, "23:12:34",
 *         "USER0001", "LAWRENCE            ", "THOMAS              ",
 *         typedPassword, "U", null,
 *         context.withPgmReenter(),
 *         "PFK05",
 *         cu02Info);                            // the 34-byte extension, handed back as received
 * save.contextIsReenter();       // true - COUSR02C.cbl:95 takes its ELSE arm
 * </pre>
 *
 * @param trnName           {@code TRNNAMEI PIC X(4)}: the transaction identifier shown top left,
 *                          {@value #TRANSACTION_ID} for this screen
 * @param title01           {@code TITLE01I PIC X(40)}: the first title line
 * @param curDate           {@code CURDATEI PIC X(8)}: the current date as {@code mm/dd/yy}
 * @param pgmName           {@code PGMNAMEI PIC X(8)}: the program name shown second line left,
 *                          {@value #PROGRAM_NAME} for this screen
 * @param title02           {@code TITLE02I PIC X(40)}: the second title line
 * @param curTime           {@code CURTIMEI PIC X(8)}: the current time as {@code hh:mm:ss} - eight
 *                          characters on this screen, not nine
 * @param usrIdIn           {@code USRIDINI PIC X(8)}: the user to look up or update, keying
 *                          {@code SEC-USR-ID PIC X(08)}. Named for {@code USRIDIN} because that is
 *                          what this mapset calls it
 * @param fName             {@code FNAMEI PIC X(20)}: the first name, compared with
 *                          {@code SEC-USR-FNAME}
 * @param lName             {@code LNAMEI PIC X(20)}: the last name, compared with
 *                          {@code SEC-USR-LNAME}
 * @param passwd            {@code PASSWDI PIC X(8)}: the password, plaintext and compared with
 *                          {@code SEC-USR-PWD} byte for byte
 * @param usrType           {@code USRTYPEI PIC X(1)}: the user type, compared with
 *                          {@code SEC-USR-TYPE}. {@value #USER_TYPE_ADMIN} administrator,
 *                          {@value #USER_TYPE_USER} regular user
 * @param errMsg            {@code ERRMSGI PIC X(78)}: the message line, seventy-eight characters
 * @param navigationContext the {@code CARDDEMO-COMMAREA} handed back by the previous turn, or
 *                          {@code null} when there is none - the {@code EIBCALEN = 0} state of
 *                          {@code COUSR02C.cbl:90}. Not a {@code DFHMDF} field; carried because the
 *                          service holds no session
 * @param aid               the resolved attention identifier token, at most
 *                          {@value #AID_LENGTH} characters, as {@code common.PfKeyResolver}
 *                          produces it - {@code ENTER}, {@code PFK03}, {@code PFK04},
 *                          {@code PFK05}, {@code PFK12} and the rest. Not a {@code DFHMDF} field;
 *                          carried because {@code COUSR02C.cbl:108} branches on {@code EIBAID}
 * @param cu02Info          the 34-byte {@code 05 CDEMO-CU02-INFO} extension of
 *                          {@code app/cbl/COUSR02C.cbl:50-58}, restored from {@code DFHCOMMAREA} at
 *                          line 94 behind the 160-byte communication area. Not a {@code DFHMDF}
 *                          field; {@code null} is normalised to {@link Cu02Info#initial()}
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
public record UserUpdateRequest(

        /* TRNNAMEI PIC X(4) - app/cpy-bms/COUSR02.CPY:24; TRNNAME DFHMDF LENGTH=4,
         * app/bms/COUSR02.bms:34-37. */
        @Size(max = TRNNAME_LENGTH) @JsonProperty("trnname") String trnName,

        /* TITLE01I PIC X(40) - app/cpy-bms/COUSR02.CPY:30; TITLE01 DFHMDF LENGTH=40,
         * app/bms/COUSR02.bms:38-41. */
        @Size(max = TITLE01_LENGTH) String title01,

        /* CURDATEI PIC X(8) - app/cpy-bms/COUSR02.CPY:36; CURDATE DFHMDF LENGTH=8,
         * app/bms/COUSR02.bms:47-51. */
        @Size(max = CURDATE_LENGTH) @JsonProperty("curdate") String curDate,

        /* PGMNAMEI PIC X(8) - app/cpy-bms/COUSR02.CPY:42; PGMNAME DFHMDF LENGTH=8,
         * app/bms/COUSR02.bms:57-60. */
        @Size(max = PGMNAME_LENGTH) @JsonProperty("pgmname") String pgmName,

        /* TITLE02I PIC X(40) - app/cpy-bms/COUSR02.CPY:48; TITLE02 DFHMDF LENGTH=40,
         * app/bms/COUSR02.bms:61-64. */
        @Size(max = TITLE02_LENGTH) String title02,

        /* CURTIMEI PIC X(8) - app/cpy-bms/COUSR02.CPY:54; CURTIME DFHMDF LENGTH=8,
         * app/bms/COUSR02.bms:70-74. Eight here; only COSGN00 declares nine. */
        @Size(max = CURTIME_LENGTH) @JsonProperty("curtime") String curTime,

        /* USRIDINI PIC X(8) - app/cpy-bms/COUSR02.CPY:60; USRIDIN DFHMDF LENGTH=8,
         * app/bms/COUSR02.bms:85-89. USRIDIN, not USERID, and declared before the two name
         * fields exactly as this mapset orders them. */
        @Size(max = USRIDIN_LENGTH) @JsonProperty("usridin") String usrIdIn,

        /* FNAMEI PIC X(20) - app/cpy-bms/COUSR02.CPY:66; FNAME DFHMDF LENGTH=20,
         * app/bms/COUSR02.bms:103-107. First of the four change-detected fields. */
        @Size(max = FNAME_LENGTH) @JsonProperty("fname") String fName,

        /* LNAMEI PIC X(20) - app/cpy-bms/COUSR02.CPY:72; LNAME DFHMDF LENGTH=20,
         * app/bms/COUSR02.bms:116-120. Second of the four change-detected fields. */
        @Size(max = LNAME_LENGTH) @JsonProperty("lname") String lName,

        /* PASSWDI PIC X(8) - app/cpy-bms/COUSR02.CPY:78; PASSWD DFHMDF LENGTH=8,
         * app/bms/COUSR02.bms:130-134. Third of the four change-detected fields.
         * PLAINTEXT: an inherited property of the legacy design and an explicit non-goal of this
         * migration. No hashing, no password encoder, no security framework - COUSR02C.cbl:227
         * compares it byte for byte and :228 stores it verbatim, and COUSR02C.cbl:169 echoes the
         * stored value back to the screen, so a digest here would change observable behaviour. */
        @Size(max = PASSWD_LENGTH) String passwd,

        /* USRTYPEI PIC X(1) - app/cpy-bms/COUSR02.CPY:84; USRTYPE DFHMDF LENGTH=1,
         * app/bms/COUSR02.bms:145-149. Fourth of the four change-detected fields. No format
         * constraint: COUSR02C never validates it against 'A' or 'U'. */
        @Size(max = USRTYPE_LENGTH) @JsonProperty("usrtype") String usrType,

        /* ERRMSGI PIC X(78) - app/cpy-bms/COUSR02.CPY:90; ERRMSG DFHMDF LENGTH=78,
         * app/bms/COUSR02.bms:155-158. Seventy-eight, although WS-MESSAGE is X(80): the 80-to-78
         * truncation at COUSR02C.cbl:270 is the controller's, performed through
         * common.FixedWidthCodec, and is not performed in this file. */
        @Size(max = ERRMSG_LENGTH) @JsonProperty("errmsg") String errMsg,

        /* NOT a DFHMDF field. The explicitly mandated exception: the 160-byte CARDDEMO-COMMAREA of
         * app/cpy/COCOM01Y.cpy, loaded by COUSR02C.cbl:94 from DFHCOMMAREA. Referenced, never
         * widened - the copybook is exactly 160 bytes and is shared by all seventeen controllers.
         * May be null: that is EIBCALEN = 0, the handled state at COUSR02C.cbl:90-92. */
        NavigationContext navigationContext,

        /* NOT a DFHMDF field. The explicitly mandated exception: the resolved EIBAID token that
         * COUSR02C.cbl:108-131 evaluates, as common.PfKeyResolver produces it. Carried as the
         * five-character token rather than as an enum so the wire form stays a plain string, the
         * same width as CCARD-AID PIC X(5) in app/cpy/CVCRD01Y.cpy. Without it neither entry path
         * of this program is reachable: ENTER fetches the user, PF5 saves, PF3 saves and then
         * exits, PF4 clears, PF12 cancels and anything else is an invalid key. */
        @Size(max = AID_LENGTH) String aid,

        /* NOT a DFHMDF field. The 34-byte 05 CDEMO-CU02-INFO group app/cbl/COUSR02C.cbl:50-58 appends
         * to CARDDEMO-COMMAREA, restored from DFHCOMMAREA at line 94 along with the 160 bytes in front
         * of it. It is carried as a member of its own rather than folded into NavigationContext,
         * because the copybook is exactly 160 bytes and is shared by all seventeen controllers while
         * this group belongs to this program alone. May be null: the canonical constructor normalises
         * that to Cu02Info.initial(), which is the state a cold start sees. */
        Cu02Info cu02Info) {

    /**
     * Normalises the extension carrier, which has no absent state.
     *
     * <p>{@code 05 CDEMO-CU02-INFO} is storage inside {@code 01 CARDDEMO-COMMAREA}: a cold start sees
     * it as the {@code VALUE} clauses left it - spaces, zero and {@code 'N'} - and every other entry
     * sees whatever line 94 restored. There is no third state, so a payload that names nothing is
     * given {@link Cu02Info#initial()} rather than {@code null}, and no reader has to defend against
     * one. Every other component is left exactly as the caller sent it, including its trailing spaces,
     * because the change tests at {@code COUSR02C.cbl:219-234} compare untrimmed.
     */
    public UserUpdateRequest {
        cu02Info = cu02Info == null ? Cu02Info.initial() : cu02Info;
    }


    // =================================================================================================
    // Symbolic-map item names, carried VERBATIM as app/cpy-bms/COUSR02.CPY spells them, trailing 'I'
    // and all. These are the names the parity differ compares field by field, so a name "tidied" into
    // camel case or stripped of its suffix would make a real difference invisible in a diff.
    // =================================================================================================

    /** Symbolic-map item behind {@link #trnName()}: {@code TRNNAMEI}, {@code COUSR02.CPY:24}. */
    public static final String TRNNAME_FIELD = "TRNNAMEI";

    /** Symbolic-map item behind {@link #title01()}: {@code TITLE01I}, {@code COUSR02.CPY:30}. */
    public static final String TITLE01_FIELD = "TITLE01I";

    /** Symbolic-map item behind {@link #curDate()}: {@code CURDATEI}, {@code COUSR02.CPY:36}. */
    public static final String CURDATE_FIELD = "CURDATEI";

    /** Symbolic-map item behind {@link #pgmName()}: {@code PGMNAMEI}, {@code COUSR02.CPY:42}. */
    public static final String PGMNAME_FIELD = "PGMNAMEI";

    /** Symbolic-map item behind {@link #title02()}: {@code TITLE02I}, {@code COUSR02.CPY:48}. */
    public static final String TITLE02_FIELD = "TITLE02I";

    /** Symbolic-map item behind {@link #curTime()}: {@code CURTIMEI}, {@code COUSR02.CPY:54}. */
    public static final String CURTIME_FIELD = "CURTIMEI";

    /**
     * Symbolic-map item behind {@link #usrIdIn()}: {@code USRIDINI}, {@code COUSR02.CPY:60}.
     *
     * <p>{@code USRIDINI}, not {@code USERIDI}. {@code app/cpy-bms/COUSR01.CPY} spells the
     * corresponding item {@code USERIDI}; this mapset and {@code COUSR03} spell it
     * {@code USRIDINI}.
     */
    public static final String USRIDIN_FIELD = "USRIDINI";

    /** Symbolic-map item behind {@link #fName()}: {@code FNAMEI}, {@code COUSR02.CPY:66}. */
    public static final String FNAME_FIELD = "FNAMEI";

    /** Symbolic-map item behind {@link #lName()}: {@code LNAMEI}, {@code COUSR02.CPY:72}. */
    public static final String LNAME_FIELD = "LNAMEI";

    /** Symbolic-map item behind {@link #passwd()}: {@code PASSWDI}, {@code COUSR02.CPY:78}. */
    public static final String PASSWD_FIELD = "PASSWDI";

    /** Symbolic-map item behind {@link #usrType()}: {@code USRTYPEI}, {@code COUSR02.CPY:84}. */
    public static final String USRTYPE_FIELD = "USRTYPEI";

    /** Symbolic-map item behind {@link #errMsg()}: {@code ERRMSGI}, {@code COUSR02.CPY:90}. */
    public static final String ERRMSG_FIELD = "ERRMSGI";

    // =================================================================================================
    // Declared widths. Every value below is a literal read off an xxxI PICTURE clause in
    // app/cpy-bms/COUSR02.CPY and independently confirmed by the matching LENGTH= in
    // app/bms/COUSR02.bms. None is computed, inherited or inferred from a sibling screen.
    // =================================================================================================

    /** {@code TRNNAMEI PIC X(4)}, {@code TRNNAME DFHMDF LENGTH=4}. */
    public static final int TRNNAME_LENGTH = 4;

    /** {@code TITLE01I PIC X(40)}, {@code TITLE01 DFHMDF LENGTH=40}. */
    public static final int TITLE01_LENGTH = 40;

    /** {@code CURDATEI PIC X(8)}, {@code CURDATE DFHMDF LENGTH=8}. Holds {@code mm/dd/yy}. */
    public static final int CURDATE_LENGTH = 8;

    /** {@code PGMNAMEI PIC X(8)}, {@code PGMNAME DFHMDF LENGTH=8}. */
    public static final int PGMNAME_LENGTH = 8;

    /** {@code TITLE02I PIC X(40)}, {@code TITLE02 DFHMDF LENGTH=40}. */
    public static final int TITLE02_LENGTH = 40;

    /**
     * {@code CURTIMEI PIC X(8)}, {@code CURTIME DFHMDF LENGTH=8}. Holds {@code hh:mm:ss}.
     *
     * <p><strong>Eight.</strong> {@code app/cpy-bms/COSGN00.CPY} declares {@code CURTIMEI PIC X(9)};
     * this mapset declares eight, and eight is what {@code COUSR02C.cbl:315} sends.
     */
    public static final int CURTIME_LENGTH = 8;

    /**
     * {@code USRIDINI PIC X(8)}, {@code USRIDIN DFHMDF LENGTH=8}.
     *
     * <p>Equal to the width of {@code SEC-USR-ID PIC X(08)} in {@code app/cpy/CSUSR01Y.cpy}, which is
     * also the eight-byte key length of the {@code USRSEC} dataset, so the value moved at
     * {@code COUSR02C.cbl:162} and {@code :216} needs neither padding nor truncation.
     */
    public static final int USRIDIN_LENGTH = 8;

    /**
     * {@code FNAMEI PIC X(20)}, {@code FNAME DFHMDF LENGTH=20}. Equal to
     * {@code SEC-USR-FNAME PIC X(20)}.
     */
    public static final int FNAME_LENGTH = 20;

    /**
     * {@code LNAMEI PIC X(20)}, {@code LNAME DFHMDF LENGTH=20}. Equal to
     * {@code SEC-USR-LNAME PIC X(20)}.
     */
    public static final int LNAME_LENGTH = 20;

    /**
     * {@code PASSWDI PIC X(8)}, {@code PASSWD DFHMDF LENGTH=8}. Equal to
     * {@code SEC-USR-PWD PIC X(08)}, which is why the comparison at {@code COUSR02C.cbl:227} is a
     * plain equality of equal widths. The mapset's own literal {@code '(8 Char)'} at
     * {@code app/bms/COUSR02.bms:139} tells the user the same thing.
     */
    public static final int PASSWD_LENGTH = 8;

    /**
     * {@code USRTYPEI PIC X(1)}, {@code USRTYPE DFHMDF LENGTH=1}. Equal to
     * {@code SEC-USR-TYPE PIC X(01)}.
     */
    public static final int USRTYPE_LENGTH = 1;

    /**
     * {@code ERRMSGI PIC X(78)}, {@code ERRMSG DFHMDF LENGTH=78} at {@code POS=(23,1)}.
     *
     * <p><strong>Seventy-eight, not eighty.</strong> {@code COUSR02C.cbl:38} declares
     * {@code WS-MESSAGE PIC X(80)} and {@code COUSR02C.cbl:270} moves it here, so COBOL drops two
     * characters off the right on every send. This constant is the receiving width; the move that
     * loses the two characters is the controller's.
     */
    public static final int ERRMSG_LENGTH = 78;

    /**
     * Width of {@link #aid()}: five characters.
     *
     * <p>Not a {@code DFHMDF} width - the attention identifier never appeared on the screen. Five is
     * the token width {@code common.PfKeyResolver} produces and the width of
     * {@code CCARD-AID PIC X(5)} in {@code app/cpy/CVCRD01Y.cpy}, which is the only place this
     * application declares an AID as data. It is wide enough for every token the resolver emits:
     * {@code ENTER}, {@code CLEAR}, {@code PA1}, {@code PA2} and {@code PFK01} through
     * {@code PFK12}.
     */
    public static final int AID_LENGTH = 5;

    // =================================================================================================
    // Screen identity. Immutable literals that name the contract this payload belongs to, so a caller
    // never has to hard-code them and a reader never has to guess which screen this is.
    // =================================================================================================

    /**
     * The map: {@code COUSR2A}, from {@code COUSR2A DFHMDI} at {@code app/bms/COUSR02.bms:26} and
     * from {@code MAP('COUSR2A')} at {@code COUSR02C.cbl:273} and {@code :286}.
     */
    public static final String MAP_NAME = "COUSR2A";

    /**
     * The mapset: {@code COUSR02}, from {@code COUSR02 DFHMSD} at {@code app/bms/COUSR02.bms:19} and
     * from {@code MAPSET('COUSR02')} at {@code COUSR02C.cbl:274} and {@code :287}.
     */
    public static final String MAPSET_NAME = "COUSR02";

    /**
     * The symbolic map this payload projects: {@code COUSR2AI}, the input view declared at
     * {@code app/cpy-bms/COUSR02.CPY:17} and the target of {@code INTO(COUSR2AI)} at
     * {@code COUSR02C.cbl:288}. Its output twin {@code COUSR2AO}, which redefines it from
     * {@code COUSR02.CPY:91}, belongs to {@code UserUpdateResponse}.
     */
    public static final String SYMBOLIC_MAP_INPUT = "COUSR2AI";

    /**
     * The CICS transaction: {@code CU02}, from {@code WS-TRANID PIC X(04) VALUE 'CU02'} at
     * {@code COUSR02C.cbl:37}. This is the value {@code COUSR02C.cbl:302} sends into
     * {@code TRNNAMEO}, so it is what {@link #trnName()} carries back.
     */
    public static final String TRANSACTION_ID = "CU02";

    /**
     * The COBOL program: {@code COUSR02C}, from {@code WS-PGMNAME PIC X(08) VALUE 'COUSR02C'} at
     * {@code COUSR02C.cbl:36}. This is the value {@code COUSR02C.cbl:303} sends into
     * {@code PGMNAMEO}, so it is what {@link #pgmName()} carries back.
     */
    public static final String PROGRAM_NAME = "COUSR02C";

    /**
     * The administrator user type: {@code 'A'}.
     *
     * <p>From {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} at {@code app/cpy/COCOM01Y.cpy:27}, and the
     * value the mapset's own literal {@code '(A=Admin, U=User)'} at
     * {@code app/bms/COUSR02.bms:154} advertises. Provided so a caller and a test can name the
     * value instead of repeating a character literal; {@code COUSR02C} itself never validates
     * {@link #usrType()} against it, which is why no format constraint appears on that member.
     */
    public static final String USER_TYPE_ADMIN = "A";

    /**
     * The regular user type: {@code 'U'}.
     *
     * <p>From {@code 88 CDEMO-USRTYP-USER VALUE 'U'} at {@code app/cpy/COCOM01Y.cpy:28}. See
     * {@link #USER_TYPE_ADMIN} for why it is not enforced.
     */
    public static final String USER_TYPE_USER = "U";

    // =================================================================================================
    // The field census. These two exist so the twelve-field contract is asserted by the code rather
    // than only described in prose: a test compares MAP_FIELD_COUNT and MAP_FIELD_NAMES against the
    // record's own components and against the mapset, so adding, dropping or reordering a member
    // fails immediately.
    // =================================================================================================

    /**
     * The number of members that project a screen field: <strong>twelve</strong>.
     *
     * <p>Three independent counts agree on it. {@code app/bms/COUSR02.bms} holds twenty-nine
     * {@code DFHMDF} definitions of which twelve carry a name, and {@code app/cpy-bms/COUSR02.CPY}
     * holds twelve {@code xxxI} items and twelve {@code xxxO} items.
     *
     * <p>This record has fourteen components in total: these twelve plus
     * {@link #navigationContext()} and {@link #aid()}, which are conversation state rather than
     * screen fields.
     */
    public static final int MAP_FIELD_COUNT = 12;

    /**
     * The twelve symbolic-map item names in declaration order - the screen contract as data.
     *
     * <p>This is exactly what {@code grep -E '^[A-Z0-9]+ +DFHMDF' app/bms/COUSR02.bms} yields, with
     * each name carrying its {@code xxxI} suffix: {@code TRNNAMEI}, {@code TITLE01I},
     * {@code CURDATEI}, {@code PGMNAMEI}, {@code TITLE02I}, {@code CURTIMEI}, {@code USRIDINI},
     * {@code FNAMEI}, {@code LNAMEI}, {@code PASSWDI}, {@code USRTYPEI}, {@code ERRMSGI}.
     *
     * <p>Note the position of {@code USRIDINI}: seventh, <em>before</em> {@code FNAMEI} and
     * {@code LNAMEI}. {@code COUSR01} puts its identifier ninth, after both names. The order here
     * follows this mapset.
     *
     * <p>The list is immutable - {@link List#of} produces an unmodifiable list - so this is a
     * constant and not static mutable state. Being static, it is not serialised.
     */
    public static final List<String> MAP_FIELD_NAMES = List.of(TRNNAME_FIELD,
            TITLE01_FIELD,
            CURDATE_FIELD,
            PGMNAME_FIELD,
            TITLE02_FIELD,
            CURTIME_FIELD,
            USRIDIN_FIELD,
            FNAME_FIELD,
            LNAME_FIELD,
            PASSWD_FIELD,
            USRTYPE_FIELD,
            ERRMSG_FIELD);

    /**
     * What {@link #toString()} prints in place of {@link #passwd()}.
     *
     * <p>It reveals neither the value nor its length. Private, because it is a diagnostic detail and
     * not part of the payload contract.
     */
    private static final String PASSWD_MASK = SensitiveDiagnostics.REDACTED;

    /** One space, for the {@code PIC X} items of {@link Cu02Info} that a cold start leaves blank. */
    private static final String SPACE = " ";

    /**
     * The alphanumeric and numeric {@code MOVE} rules {@link Cu02Info} renders its six items through.
     *
     * <p>The code page is named rather than defaulted (practice B8), and is the same
     * {@code US-ASCII} the controller's {@code WORKING_STORAGE_CHARSET} names, so an image built here
     * and one built there cannot differ. Neither of the two rules this constant is used for actually
     * consults the charset - the alphanumeric {@code MOVE} pads and truncates characters and the
     * numeric one zero-fills digits - but the codec requires one, and leaving it to the platform
     * default is the pitfall the AAP names explicitly. Immutable, so this is a constant and not static
     * mutable state.
     */
    private static final FixedWidthCodec PICTURE_RULES =
            new FixedWidthCodec(StandardCharsets.US_ASCII);

    // =================================================================================================
    // Conversation state, read THROUGH the communication area rather than duplicated beside it.
    //
    // None of the three is shaped like a bean accessor. That is deliberate: a record method named
    // getXxx() or isXxx() is collected by Jackson as an extra JSON property, which would put a value
    // on the wire that the canonical constructor cannot accept back and would break a round trip. The
    // names below keep these off the wire without needing any Jackson annotation at all.
    // =================================================================================================

    /**
     * Whether a communication area travelled with this request.
     *
     * <p>This is the {@code EIBCALEN} test of {@code app/cbl/COUSR02C.cbl:90}:
     *
     * <pre>
     *  IF EIBCALEN = 0
     *      MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
     *      PERFORM RETURN-TO-PREV-SCREEN
     *  ELSE
     *      MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA
     * </pre>
     *
     * A zero-length communication area is a real and handled input state, not an error: the program
     * answers it by transferring to the sign-on program. {@code false} here is that state, and it is
     * why {@link #navigationContext()} is allowed to be {@code null} rather than being rejected by
     * the constructor.
     *
     * @return {@code true} when {@link #navigationContext()} is present, {@code false} when it is
     *         {@code null} - the {@code EIBCALEN = 0} state
     */
    public boolean hasNavigationContext() {
        return navigationContext != null;
    }

    /**
     * Whether the communication area says this is a first entry - {@code 88 CDEMO-PGM-ENTER VALUE 0}
     * over {@code CDEMO-PGM-CONTEXT PIC 9(01)}, {@code app/cpy/COCOM01Y.cpy:29-30}.
     *
     * <p>Read through {@link NavigationContext#isEnter()}; the flag is never stored a second time
     * here, because a copy could drift out of step with the area it summarises.
     *
     * <p><strong>Do not use this as the first-entry branch condition.</strong>
     * {@code app/cbl/COUSR02C.cbl:95} is written in the negated form
     * {@code IF NOT CDEMO-PGM-REENTER}, and the two are not the same test:
     * {@code CDEMO-PGM-CONTEXT} is {@code PIC 9(01)} and may hold any digit, so for a value of, say,
     * {@code 9} both this method and {@link #contextIsReenter()} are false while
     * {@code NOT CDEMO-PGM-REENTER} is true. A controller reproducing that program must branch on
     * {@code !contextIsReenter()}. This method is the direct reading of the {@code 88}-level and is
     * provided for assertions and diagnostics.
     *
     * @return {@code true} only when a communication area is present and its program context is
     *         {@link NavigationContext#PGM_CONTEXT_ENTER}; {@code false} when there is no
     *         communication area at all
     */
    public boolean contextIsEnter() {
        return navigationContext != null && navigationContext.isEnter();
    }

    /**
     * Whether the communication area says this is a re-entry - {@code 88 CDEMO-PGM-REENTER VALUE 1}
     * over {@code CDEMO-PGM-CONTEXT PIC 9(01)}, {@code app/cpy/COCOM01Y.cpy:29,31}.
     *
     * <p>Read through {@link NavigationContext#isReenter()}, never stored again here.
     *
     * <p>This is the condition {@code app/cbl/COUSR02C.cbl:95} tests, and its two arms are the whole
     * shape of the transaction. When it is false the program paints the screen -
     * {@code MOVE LOW-VALUES TO COUSR2AO} at {@code :97}, {@code MOVE -1 TO USRIDINL} at {@code :98},
     * and, if {@code CDEMO-CU02-USR-SELECTED} arrived non-blank, an immediate lookup at
     * {@code :101-103}. When it is true the program receives the map at {@code :107} and dispatches
     * on {@link #aid()} at {@code :108-131}. With no communication area it is false, which is
     * harmless: the {@code EIBCALEN = 0} guard at {@code :90} has already taken control by then.
     *
     * @return {@code true} only when a communication area is present and its program context is
     *         {@link NavigationContext#PGM_CONTEXT_REENTER}; {@code false} when there is no
     *         communication area at all
     */
    public boolean contextIsReenter() {
        return navigationContext != null && navigationContext.isReenter();
    }

    // =================================================================================================
    // Diagnostics.
    // =================================================================================================

    /**
     * A diagnostic rendering with {@link #passwd()} replaced by {@link SensitiveDiagnostics#REDACTED}.
     *
     * <p>The record's generated {@code toString()} would print every component including the
     * plaintext password, so it is overridden here: a request payload reaches logs, exception
     * messages and test failure output, and the password must not travel with it. Neither the value
     * nor its length is disclosed.
     *
     * <p>Whether the password is {@code null} <em>is</em> disclosed, because that is presence rather
     * than content and it is diagnostically necessary: {@code null} models the {@code LOW-VALUES} a
     * field the terminal never transmitted arrives as, and telling that apart from a transmitted
     * value is what makes the guard at {@code app/cbl/COUSR02C.cbl:198} and the change test at
     * {@code :227} traceable.
     *
     * <p>Every other component is printed in full and untrimmed, so trailing spaces stay visible -
     * they are semantically significant to the change tests at {@code COUSR02C.cbl:219-234}. The
     * bracketed form matches what the record would have generated, so output stays familiar.
     *
     * @return a rendering of all fifteen components, never {@code null}, with the password masked
     */
    @Override
    public String toString() {
        return "UserUpdateRequest[trnName=" + trnName
                + ", title01=" + title01
                + ", curDate=" + curDate
                + ", pgmName=" + pgmName
                + ", title02=" + title02
                + ", curTime=" + curTime
                + ", usrIdIn=" + usrIdIn
                + ", fName=" + SensitiveDiagnostics.describeText(fName)
                + ", lName=" + SensitiveDiagnostics.describeText(lName)
                + ", passwd=" + (passwd == null ? null : PASSWD_MASK)
                + ", usrType=" + usrType
                + ", errMsg=" + errMsg
                + ", navigationContext=" + navigationContext
                + ", aid=" + aid
                + ", cu02Info=" + cu02Info
                + ']';
    }

    // =================================================================================================
    // CDEMO-CU02-INFO - app/cbl/COUSR02C.cbl:50-58. This program's own 34-byte commarea extension,
    // declared here rather than on the controller because it is payload: it arrives in the request
    // body and leaves in the response body, and a DTO must not depend on a controller to name its own
    // members. The same shape as CardListRequest.PageCursor and TransactionAddRequest.Ct01Info.
    // =================================================================================================

    /**
     * {@code 05 CDEMO-CU02-INFO} - the thirty-four bytes this program appends to
     * {@code CARDDEMO-COMMAREA}, declared in its own working storage at lines 50-58:
     *
     * <pre>
     * 05 CDEMO-CU02-INFO.
     *    10 CDEMO-CU02-USRID-FIRST     PIC X(08).
     *    10 CDEMO-CU02-USRID-LAST      PIC X(08).
     *    10 CDEMO-CU02-PAGE-NUM        PIC 9(08).
     *    10 CDEMO-CU02-NEXT-PAGE-FLG   PIC X(01) VALUE 'N'.
     *    10 CDEMO-CU02-USR-SEL-FLG     PIC X(01).
     *    10 CDEMO-CU02-USR-SELECTED    PIC X(08).
     * </pre>
     *
     * <p>These six items are <strong>not</strong> in {@code app/cpy/COCOM01Y.cpy}. They belong to this
     * program's local area, which is why they are here and not on {@link NavigationContext}: that type
     * is exactly {@value NavigationContext#COMMAREA_LENGTH} bytes and is shared by all seventeen
     * controllers, so widening it for one program's private extension would change the area every other
     * program receives.
     *
     * <p><strong>The program reads exactly one of them and writes none.</strong>
     * {@link #usrSelected()} is tested at lines 99-100 and consumed at 101-102; the other five are
     * restored from {@code DFHCOMMAREA} at line 94 and handed back unchanged at lines 135-138. That
     * pass-through is behaviour: dropping the group would shorten the returned area from
     * 194 bytes to
     * {@value NavigationContext#COMMAREA_LENGTH} and change what the next program in a chain receives -
     * {@code COUSR00C}, the user list, is what fills that span before transferring here: it declares its
     * own {@code 05 CDEMO-CU00-INFO} over the same thirty-four bytes [{@code app/cbl/COUSR00C.cbl:67-75}]
     * and moves the marked row's id into {@code CDEMO-CU00-USR-SELECTED} at its lines 154 to 181. The two
     * groups are separate declarations of one span, which is why the names differ program by program and
     * why each program models its own.
     *
     * <p>A record, so it is immutable and can be shared safely.
     *
     * @param usridFirst   {@code CDEMO-CU02-USRID-FIRST PIC X(08)}, line 51 - carried, never read here
     * @param usridLast    {@code CDEMO-CU02-USRID-LAST PIC X(08)}, line 52 - carried, never read here
     * @param pageNum      {@code CDEMO-CU02-PAGE-NUM PIC 9(08)}, line 53 - carried, never read here
     * @param nextPageFlg  {@code CDEMO-CU02-NEXT-PAGE-FLG PIC X(01)}, line 54, whose {@code 88}-levels
     *                     at 55-56 are {@code NEXT-PAGE-YES 'Y'} and {@code NEXT-PAGE-NO 'N'} - carried,
     *                     never read here
     * @param usrSelFlg    {@code CDEMO-CU02-USR-SEL-FLG PIC X(01)}, line 57 - carried, never read here
     * @param usrSelected  {@code CDEMO-CU02-USR-SELECTED PIC X(08)}, line 58 - <strong>the one item
     *                     this program reads</strong>, at lines 99-102
     */
    public record Cu02Info(@JsonProperty("usridFirst") String usridFirst,
                           @JsonProperty("usridLast") String usridLast,
                           @JsonProperty("pageNum") int pageNum,
                           @JsonProperty("nextPageFlg") String nextPageFlg,
                           @JsonProperty("usrSelFlg") String usrSelFlg,
                           @JsonProperty("usrSelected") String usrSelected) {

        /** Declared width of {@code CDEMO-CU02-USRID-FIRST}: {@code PIC X(08)}. */
        public static final int USRID_FIRST_LENGTH = 8;

        /** Declared width of {@code CDEMO-CU02-USRID-LAST}: {@code PIC X(08)}. */
        public static final int USRID_LAST_LENGTH = 8;

        /** Declared digits of {@code CDEMO-CU02-PAGE-NUM}: {@code PIC 9(08)}, unsigned. */
        public static final int PAGE_NUM_DIGITS = 8;

        /** Declared width of {@code CDEMO-CU02-NEXT-PAGE-FLG}: {@code PIC X(01)}. */
        public static final int NEXT_PAGE_FLG_LENGTH = 1;

        /** Declared width of {@code CDEMO-CU02-USR-SEL-FLG}: {@code PIC X(01)}. */
        public static final int USR_SEL_FLG_LENGTH = 1;

        /** Declared width of {@code CDEMO-CU02-USR-SELECTED}: {@code PIC X(08)}. */
        public static final int USR_SELECTED_LENGTH = 8;

        /**
         * The group's total width: 8 + 8 + 8 + 1 + 1 + 8 = <strong>34</strong> bytes.
         *
         * <p>Added to {@value NavigationContext#COMMAREA_LENGTH} this gives the
         * 194-byte area line 94 restores - 160 plus these 34.
         */
        public static final int LENGTH = USRID_FIRST_LENGTH + USRID_LAST_LENGTH + PAGE_NUM_DIGITS
                + NEXT_PAGE_FLG_LENGTH + USR_SEL_FLG_LENGTH + USR_SELECTED_LENGTH;

        /** {@code 88 NEXT-PAGE-YES VALUE 'Y'} - line 55. */
        public static final String NEXT_PAGE_YES = "Y";

        /** {@code 88 NEXT-PAGE-NO VALUE 'N'} - line 56, and the field's own {@code VALUE} at line 54. */
        public static final String NEXT_PAGE_NO = "N";

        /**
         * Renders every character item at its declared width and rejects a negative page number.
         *
         * <p>A {@code null} becomes that item's run of spaces, because a COBOL {@code PIC X} item has no
         * absent state, and a value of any other length is put through the alphanumeric {@code MOVE}
         * rule so the direction of any truncation is the one COBOL uses. {@code CDEMO-CU02-PAGE-NUM} is
         * {@code PIC 9(08)} - an unsigned picture with no sign position - so a negative value has no
         * representation in it and is refused rather than silently stored.
         *
         * @throws IllegalArgumentException if {@code pageNum} is negative or needs more than
         *                                  {@value #PAGE_NUM_DIGITS} digits
         */
        public Cu02Info {
            usridFirst = image(usridFirst, USRID_FIRST_LENGTH);
            usridLast = image(usridLast, USRID_LAST_LENGTH);
            nextPageFlg = image(nextPageFlg, NEXT_PAGE_FLG_LENGTH);
            usrSelFlg = image(usrSelFlg, USR_SEL_FLG_LENGTH);
            usrSelected = image(usrSelected, USR_SELECTED_LENGTH);
            if (pageNum < 0) {
                throw new IllegalArgumentException("CDEMO-CU02-PAGE-NUM is PIC 9(" + PAGE_NUM_DIGITS
                        + "), an unsigned picture with no sign position, so " + pageNum
                        + " has no representation in it");
            }
            if (pageNum >= (int) Math.pow(10, PAGE_NUM_DIGITS)) {
                throw new IllegalArgumentException("CDEMO-CU02-PAGE-NUM is PIC 9(" + PAGE_NUM_DIGITS
                        + ") and cannot hold " + pageNum + "; a numeric MOVE would drop its high-order "
                        + "digits and the result would still look plausible");
            }
        }

        /**
         * The group as a freshly initialised area: spaces in the five character items, zero in the page
         * number, and {@code 'N'} in {@code CDEMO-CU02-NEXT-PAGE-FLG}.
         *
         * <p>The {@code 'N'} is not a convention chosen here - it is the {@code VALUE 'N'} clause on
         * line 54, which is what a cold start sees before line 94 overwrites the area. The other five
         * items declare no {@code VALUE} and so begin as spaces and zero.
         *
         * @return the initial extension group, never {@code null}
         */
        public static Cu02Info initial() {
            return new Cu02Info(SPACE.repeat(USRID_FIRST_LENGTH),
                    SPACE.repeat(USRID_LAST_LENGTH),
                    0,
                    NEXT_PAGE_NO,
                    SPACE.repeat(USR_SEL_FLG_LENGTH),
                    SPACE.repeat(USR_SELECTED_LENGTH));
        }

        /**
         * The six items keyed by the name {@code app/cbl/COUSR02C.cbl:51-58} spells, in declaration
         * order.
         *
         * <p>The group is not in {@code app/cpy/COCOM01Y.cpy}, so it has no fixed-width layout of its own
         * to deserialise and these six names are reachable no other way. A field-by-field comparison of
         * the returned communication area needs them - the area this program hands back is
         * 194 bytes and 34 of them are these - so they
         * are projected here rather than left to be reassembled from six separate accessors.
         *
         * <p>{@code CDEMO-CU02-PAGE-NUM} is rendered as its {@code PIC 9(08)} image - zero-filled to eight
         * digits - because that is the form it occupies in the area, not as a decimal string.
         *
         * @return an unmodifiable, declaration-ordered map of the six item names to their images
         */
        public Map<String, String> fieldImages() {
            Map<String, String> images = new LinkedHashMap<>();
            images.put("CDEMO-CU02-USRID-FIRST", usridFirst);
            images.put("CDEMO-CU02-USRID-LAST", usridLast);
            images.put("CDEMO-CU02-PAGE-NUM", PICTURE_RULES.movePic9(pageNum, PAGE_NUM_DIGITS));
            images.put("CDEMO-CU02-NEXT-PAGE-FLG", nextPageFlg);
            images.put("CDEMO-CU02-USR-SEL-FLG", usrSelFlg);
            images.put("CDEMO-CU02-USR-SELECTED", usrSelected);
            return Collections.unmodifiableMap(images);
        }

        /**
         * Applies the alphanumeric {@code MOVE} rule, treating {@code null} as the item's spaces.
         *
         * @param value  the supplied value, or {@code null} for an item that was not supplied
         * @param length the item's declared width
         * @return an image of exactly {@code length} characters
         */
        private static String image(String value, int length) {
            return PICTURE_RULES.movePicX(value == null ? "" : value, length);
        }
    }
}
