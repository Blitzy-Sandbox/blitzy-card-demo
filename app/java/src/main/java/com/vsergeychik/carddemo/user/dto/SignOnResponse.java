package com.vsergeychik.carddemo.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import java.util.List;
import java.util.Objects;

/**
 * The outbound payload of {@code POST /api/signon} - CICS transaction {@code CC00}, program
 * {@code app/cbl/COSGN00C.cbl}, mapset {@code app/bms/COSGN00.bms}, map {@code COSGN0A}.
 *
 * <p>It is a field-for-field projection of all {@value #MAPSET_NAMED_FIELD_COUNT} {@code xxxO} items of
 * {@code 01 COSGN0AO} in {@code app/cpy-bms/COSGN00.CPY}, plus the stateless renderings of
 * {@code EXEC CICS XCTL} and {@code EXEC CICS SEND TEXT}. Nothing is added, nothing is renamed and
 * nothing is widened: this is a like-for-like language migration, so every width below is the width
 * its {@code PICTURE} clause declares and every name below is the name the mapset gives the field.
 *
 * <h2>The eleven map-derived members, in the order the symbolic map declares them</h2>
 *
 * Each row is traceable to three independent sources that were checked against one another: the
 * {@code xxxO} item that fixes the width, the name-labelled {@code DFHMDF} line that proves the field
 * is a real screen field, and the {@code COSGN00C} line that writes it. The {@code LENGTH=} operand of
 * every {@code DFHMDF} agrees with its {@code xxxO} {@code PICTURE} exactly.
 *
 * <table border="1">
 *   <caption>Projection of {@code 01 COSGN0AO} onto this payload</caption>
 *   <tr><th>#</th><th>Member</th><th>{@code xxxO} item</th><th>Width</th>
 *       <th>{@code DFHMDF}</th><th>Written by</th></tr>
 *   <tr><td>1</td><td>{@link #trnName()}</td><td>{@code TRNNAMEO} (CPY L92)</td>
 *       <td>{@code X(4)}</td><td>{@code TRNNAME} (bms L34)</td>
 *       <td>{@code MOVE WS-TRANID TO TRNNAMEO} (L183)</td></tr>
 *   <tr><td>2</td><td>{@link #title01()}</td><td>{@code TITLE01O} (CPY L98)</td>
 *       <td>{@code X(40)}</td><td>{@code TITLE01} (bms L38)</td>
 *       <td>{@code MOVE CCDA-TITLE01 TO TITLE01O} (L181)</td></tr>
 *   <tr><td>3</td><td>{@link #curDate()}</td><td>{@code CURDATEO} (CPY L104)</td>
 *       <td>{@code X(8)}</td><td>{@code CURDATE} (bms L47)</td>
 *       <td>{@code MOVE WS-CURDATE-MM-DD-YY TO CURDATEO} (L190)</td></tr>
 *   <tr><td>4</td><td>{@link #pgmName()}</td><td>{@code PGMNAMEO} (CPY L110)</td>
 *       <td>{@code X(8)}</td><td>{@code PGMNAME} (bms L57)</td>
 *       <td>{@code MOVE WS-PGMNAME TO PGMNAMEO} (L184)</td></tr>
 *   <tr><td>5</td><td>{@link #title02()}</td><td>{@code TITLE02O} (CPY L116)</td>
 *       <td>{@code X(40)}</td><td>{@code TITLE02} (bms L61)</td>
 *       <td>{@code MOVE CCDA-TITLE02 TO TITLE02O} (L182)</td></tr>
 *   <tr><td>6</td><td>{@link #curTime()}</td><td>{@code CURTIMEO} (CPY L122)</td>
 *       <td><strong>{@code X(9)}</strong></td><td>{@code CURTIME} (bms L70)</td>
 *       <td>{@code MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO} (L196)</td></tr>
 *   <tr><td>7</td><td>{@link #applId()}</td><td>{@code APPLIDO} (CPY L128)</td>
 *       <td>{@code X(8)}</td><td>{@code APPLID} (bms L80)</td>
 *       <td>{@code EXEC CICS ASSIGN APPLID(APPLIDO)} (L198-200)</td></tr>
 *   <tr><td>8</td><td>{@link #sysId()}</td><td>{@code SYSIDO} (CPY L134)</td>
 *       <td>{@code X(8)}</td><td>{@code SYSID} (bms L89)</td>
 *       <td>{@code EXEC CICS ASSIGN SYSID(SYSIDO)} (L202-204)</td></tr>
 *   <tr><td>9</td><td>{@link #userId()}</td><td>{@code USERIDO} (CPY L140)</td>
 *       <td>{@code X(8)}</td><td>{@code USERID} (bms L156)</td>
 *       <td>{@code EXEC CICS RECEIVE MAP} (L110-115), through the overlay - see below</td></tr>
 *   <tr><td>10</td><td>{@link #passwd()}</td><td>{@code PASSWDO} (CPY L146)</td>
 *       <td>{@code X(8)}</td><td>{@code PASSWD} (bms L175)</td>
 *       <td>{@code EXEC CICS RECEIVE MAP} (L110-115), through the overlay - see below</td></tr>
 *   <tr><td>11</td><td>{@link #errMsg()}</td><td>{@code ERRMSGO} (CPY L152)</td>
 *       <td><strong>{@code X(78)}</strong></td><td>{@code ERRMSG} (bms L197)</td>
 *       <td>{@code MOVE SPACES} (L78), {@code MOVE WS-MESSAGE} (L149)</td></tr>
 * </table>
 *
 * <h2>{@code COSGN0AO REDEFINES COSGN0AI}: the two fields the program transmits without writing</h2>
 *
 * {@code app/cpy-bms/COSGN00.CPY:85} declares the output group as a redefinition of the input group, so
 * the two are <strong>one piece of storage under two names</strong>. Both views give every field a
 * seven-byte prologue - on the input side {@code xxxL COMP PIC S9(4)}, {@code xxxF} and a four-byte
 * filler; on the output side {@code FILLER X(3)} and the {@code xxxC}, {@code xxxP}, {@code xxxH} and
 * {@code xxxV} attribute bytes - so the data items align exactly. {@code USERIDI} and {@code USERIDO}
 * are one eight-byte span at one offset. So are {@code PASSWDI} and {@code PASSWDO}.
 *
 * <p>That is why {@code grep -n 'OF COSGN0AO' app/cbl/COSGN00C.cbl} finding no write to
 * {@code USERIDO} or {@code PASSWDO} does <em>not</em> mean neither field is sent. It means the program
 * does not have to write them: {@code EXEC CICS RECEIVE MAP} at L110-115 puts the received user id and
 * password into those spans, and the {@code EXEC CICS SEND MAP ... FROM(COSGN0AO)} at L151-157 then
 * transmits whatever the spans hold. An error repaint therefore <strong>echoes the identifier the
 * operator typed</strong>, which is exactly what a 3270 operator sees when
 * {@code 'Wrong Password. Try again ...'} comes back with the user-id field still filled in - and it
 * re-transmits the password field as well.
 *
 * <p>An earlier revision of this type omitted {@code PASSWDO} altogether and left {@code USERIDO} at
 * {@code LOW-VALUES} on every path, on the strength of that grep. The reasoning was careful and the
 * conclusion was wrong, because it read the write sites without reading the {@code REDEFINES} on
 * CPY L85. Runtime review caught it. Both fields are now projected, and what they carry is a fact of
 * the execution rather than a choice of this type's:
 *
 * <ul>
 *   <li><strong>The {@code RECEIVE} ran</strong> - the ENTER arm at L86-87 and every path it reaches -
 *       so the spans hold the images it delivered and a repaint re-transmits them.
 *       {@link #withReceivedMapArea(String, String)} is that step. The images are the <em>raw</em>
 *       received values, not the upper-cased ones: {@code MOVE FUNCTION UPPER-CASE(USERIDI)} at L132
 *       writes {@code WS-USER-ID} and {@code CDEMO-USER-ID}, never back into the span, so a lower-case
 *       sign-on attempt comes back lower-case in the field and upper-cased in the communication area.
 *       Both are observable and they legitimately differ.</li>
 *   <li><strong>The {@code RECEIVE} did not run</strong> - cold start at L80-83, PF3 at L88-90, the
 *       invalid-key arm at L91-94 - so the spans hold {@code LOW-VALUES}: put there explicitly by
 *       {@code MOVE LOW-VALUES TO COSGN0AO} at L81 on the first path, and never written at all on the
 *       other two, where {@code COPY COSGN00} at L50 brings the group into {@code WORKING-STORAGE} with
 *       no {@code VALUE} clause. {@link #empty()} is that state, and no mutator is needed for it.</li>
 * </ul>
 *
 * <p>{@code PASSWD} is declared {@code ATTRB=(DRK,FSET,UNPROT)} at bms L175. {@code DRK} is
 * non-display, so the operator never sees the characters - and {@code FSET} sets the modified-data tag,
 * so the field is returned on the next receive whether or not it was retyped. Neither attribute stops
 * the value travelling in the datastream, which is why the member is here. The attributes are
 * presentation and belong to {@code common.BmsAttributes}, not to this payload.
 *
 * <p>Being transmitted is not the same as being logged: {@link #toString()} substitutes a constant
 * marker for {@link #passwd()}, exactly as {@code SignOnRequest} does for its own, so no log line,
 * exception message or debugger view can publish the credential. {@code equals} and {@code hashCode}
 * are left as the record generates them, including the password, because they are value semantics and
 * disclose nothing.
 *
 * <p>Do not generalise this either way. The rule is "mirror the program", not "hide passwords" and not
 * "echo passwords". {@code UserUpdateResponse} carries one because {@code app/cbl/COUSR02C.cbl:169}
 * moves it into an output item outright; this screen carries one because a {@code REDEFINES} puts it in
 * the span that is sent. Each screen is decided from its own source.
 *
 * <h2>{@code EXEC CICS SEND TEXT}: the one output with no screen field to travel in</h2>
 *
 * {@code SEND-PLAIN-TEXT} at L162-172 is the PF3 exit, and it is the only transmission in the program
 * that is not a map:
 *
 * <pre>
 *  EXEC CICS SEND TEXT FROM(WS-MESSAGE) LENGTH(LENGTH OF WS-MESSAGE) ERASE FREEKB END-EXEC.  :164-169
 *  EXEC CICS RETURN END-EXEC.                                                               :171-172
 * </pre>
 *
 * <p>{@code WS-MESSAGE} is {@code PIC X(80)} at L38, so the transmission is exactly
 * {@value #PLAIN_TEXT_LENGTH} bytes - <strong>not</strong> the {@value #ERRMSG_LENGTH} of
 * {@code ERRMSGO}, and not a map field at all. Projecting it into the error line would lose the two
 * rightmost characters and would claim a map was sent when none was; leaving it out would drop the
 * program's entire answer to PF3. {@link #plainText()} carries it, and
 * {@link #withPlainText(String)} is the one place it is set. On every other path the member is
 * {@value #PLAIN_TEXT_LENGTH} spaces, because nothing was transmitted.
 *
 * <p>It is a response-only member for the same reason the navigation triple is - the server is
 * stateless and an observable output has to be expressed as data - and it is registered in
 * {@code common.ResponseOnlyMembers} so {@code SignOnRequest} tolerates a client echoing it back.
 *
 * <h2>Three exits, three shapes</h2>
 *
 * <table border="1">
 *   <caption>What each exit transmits</caption>
 *   <tr><th>Exit</th><th>Map fields</th><th>{@link #plainText()}</th><th>Navigation</th></tr>
 *   <tr><td>{@code SEND-SIGNON-SCREEN} (L145-157) - cold start, either blank field, wrong password,
 *           user not found, unable to verify, invalid key</td>
 *       <td>painted: the eight header fields, {@code ERRMSGO}, and the two overlay spans</td>
 *       <td>spaces</td><td>{@code COSGN00} / {@code COSGN0A}, no program</td></tr>
 *   <tr><td>{@code SEND-PLAIN-TEXT} (L162-172) - PF3</td>
 *       <td>none but the error line, which L78 blanks on every path before the {@code EVALUATE}</td>
 *       <td>the {@value #PLAIN_TEXT_LENGTH}-byte thank-you text</td>
 *       <td>nothing - the conversation ends</td></tr>
 *   <tr><td>{@code EXEC CICS XCTL} (L231-239) - signed on</td>
 *       <td>the same: control transfers without a send</td>
 *       <td>spaces</td><td>{@code COADM01C} or {@code COMEN01C}, no map</td></tr>
 * </table>
 *
 * <p>So a client can tell all three apart from the payload alone, and none of the three claims an
 * output the program did not make.
 *
 * <h2>Authentication is plaintext, and that is inherited rather than chosen</h2>
 *
 * {@code app/cbl/COSGN00C.cbl:223} authenticates with {@code IF SEC-USR-PWD = WS-USER-PWD} - a direct
 * comparison against {@code SEC-USR-PWD PIC X(08)} of {@code app/cpy/CSUSR01Y.cpy}, read from the
 * {@code USRSEC} VSAM file at L211-219. There is no hash, no salt, no token and no expiry anywhere in
 * the legacy design. That property is preserved because changing it would change observable behaviour
 * and would require an authentication framework that is out of scope; it is documented here so it
 * stays visible instead of being buried. Nothing in this type hashes, masks, encrypts, signs or logs a
 * credential, and nothing in this type imports a security framework.
 *
 * <h2>{@code CURTIMEO} is nine characters, and that is not a typo</h2>
 *
 * {@link #curTime()} is {@value #CURTIME_LENGTH} characters. The four user screens {@code COUSR00}
 * through {@code COUSR03} declare their time field {@code X(8)}; this one declares {@code X(9)} at
 * CPY L122 and {@code LENGTH=9} at bms L70, with {@code INITIAL='Ahh:mm:ss'}. The extra character is
 * real and is preserved, not regularised - so the eight characters
 * {@code common.DateHeader.wsCurtimeHhMmSs} renders are space-padded on the right by the {@code MOVE}
 * at L196, and the padding is performed deliberately at the call site.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <ul>
 *   <li><strong>No {@code xxxC}, {@code xxxP}, {@code xxxH} or {@code xxxV} member.</strong> The
 *       {@code AO} group interleaves four single-byte attribute items and a three-byte filler ahead of
 *       every {@code xxxO} value - {@code xxxC} colour, {@code xxxP} programmed symbol, {@code xxxH}
 *       highlight, {@code xxxV} validation - giving the {@code 7 + n} stride that makes
 *       {@code REDEFINES COSGN0AI} legal. They are terminal presentation attributes, not payload:
 *       {@code common.BmsAttributes} and {@code common.FieldAttributeSetter} own that concern.</li>
 *   <li><strong>No {@code xxxL}, {@code xxxF} or {@code xxxA} member.</strong> Those belong to the
 *       {@code AI} input view - the length CICS reports and the attribute byte with its
 *       {@code REDEFINES} alias - and are validation and highlight metadata, never JSON members.</li>
 *   <li><strong>No filler.</strong> Neither the leading twelve-byte {@code TIOAPFX} span at CPY L86
 *       nor the {@code FILLER PICTURE X(3)} spans are exposed; they are reserved storage.</li>
 *   <li><strong>No fixed-width image and no record layout.</strong> Emitting the {@code AO} group as
 *       bytes would require writing the attribute bytes this payload deliberately omits, so no byte
 *       image is offered. Widths are declared and enforced; they are not serialised positionally.</li>
 *   <li><strong>No {@code withUserId} and no {@code withPasswd}.</strong> The two overlay spans are set
 *       together, by {@link #withReceivedMapArea(String, String)}, because the receive that fills them
 *       fills both at once - {@code EXEC CICS RECEIVE MAP} delivers the whole map area, not one field.
 *       Offering them separately would suggest the program writes them separately, and it writes neither.
 *       </li>
 *   <li><strong>No truncation.</strong> {@code WS-MESSAGE} is {@code PIC X(80)} at
 *       {@code app/cbl/COSGN00C.cbl:38} while {@code ERRMSGO} is {@code X(78)}, so
 *       {@code MOVE WS-MESSAGE TO ERRMSGO} at L149 loses the two rightmost characters. This type
 *       declares the width as {@value #ERRMSG_LENGTH} and <em>rejects</em> anything longer; the
 *       controller performs the shortening deliberately through
 *       {@code common.FixedWidthCodec.movePicX}, so the direction of the loss is chosen at the call
 *       site rather than happening silently here.</li>
 * </ul>
 *
 * <h2>The navigation contract: {@code XCTL} becomes response data</h2>
 *
 * {@code COSGN00C} does not return to a menu, it transfers to one. On a successful sign-on, L224-228
 * populate the communication area - {@code CDEMO-FROM-TRANID} with {@value #TRANID},
 * {@code CDEMO-FROM-PROGRAM} with {@value #PROGRAM_NAME}, {@code CDEMO-USER-ID},
 * {@code CDEMO-USER-TYPE} from {@code SEC-USR-TYPE} and {@code CDEMO-PGM-CONTEXT} with zeros - and
 * then L230-240 transfer:
 *
 * <pre>
 *  IF CDEMO-USRTYP-ADMIN
 *       EXEC CICS XCTL PROGRAM ('COADM01C') COMMAREA(CARDDEMO-COMMAREA) END-EXEC
 *  ELSE
 *       EXEC CICS XCTL PROGRAM ('COMEN01C') COMMAREA(CARDDEMO-COMMAREA) END-EXEC
 *  END-IF
 * </pre>
 *
 * There is no server-side forward in the migrated form. The response names the target and the client
 * makes the next call, so no redirect chain and no session affinity is introduced. Five members carry
 * that contract - {@link #role()}, {@link #nextProgram()}, {@link #nextMapset()}, {@link #nextMap()}
 * and {@link #navigationContext()} - and they are the one deliberate, mandated exception to the rule
 * that every member traces to a {@code DFHMDF} field: they exist because the transfer has to be
 * expressed as data once the server is stateless. Every other member is a screen field.
 *
 * <p>The {@code IF ... ELSE} is <strong>asymmetric and must stay asymmetric</strong>. The condition
 * tested is {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'}, so the {@code ELSE} branch means "not an
 * administrator", which is a strictly wider set than {@code 88 CDEMO-USRTYP-USER VALUE 'U'}. A user
 * type of {@code 'U'}, of a space - which is what a freshly initialised communication area holds - or
 * of any unexpected byte all route to {@value #NEXT_PROGRAM_USER}. {@link #resolveNextProgram(String)}
 * transcribes exactly that, and rewriting it as an equality test on {@code 'U'} would change where an
 * uninitialised or corrupt user type is sent.
 *
 * <p>{@link #nextMapset()} and {@link #nextMap()} are {@value #NEXT_MAPSET_LENGTH} characters, not
 * eight, because {@code CDEMO-LAST-MAPSET} and {@code CDEMO-LAST-MAP} of
 * {@code app/cpy/COCOM01Y.cpy} are {@code PIC X(7)}. This screen demonstrates why: a BMS symbolic-map
 * group item is a seven-character map name plus a one-character direction suffix, which is how
 * {@code COSGN0A} yields {@code COSGN0AI} for input and {@code COSGN0AO} for output. The mapset name
 * {@value #MAPSET_NAME} and the map name {@value #MAP_NAME} of
 * {@code EXEC CICS SEND MAP('COSGN0A') MAPSET('COSGN00')} at L151-157 are both exactly seven
 * characters. The two members exist on all seventeen response types for uniformity.
 *
 * <p>{@link #navigationContext()} is the {@value NavigationContext#COMMAREA_LENGTH}-byte
 * {@code CARDDEMO-COMMAREA} itself, referenced and never widened. It is shared by all seventeen
 * controllers, so a field added here to suit this screen would change the wire format of the other
 * sixteen; {@link NavigationContext} owns its own geometry and enforces it.
 *
 * <h2>Statelessness is structural, not conventional</h2>
 *
 * CICS is pseudo-conversational: a transaction paints a screen, ends, and is re-entered from the top,
 * with only the communication area surviving. The migration keeps that shape, so all conversation
 * state travels in the payload. This type is consequently free of {@code HttpSession},
 * {@code @SessionAttributes}, {@code @SessionScope}, {@code ThreadLocal}, any cache keyed by user or
 * terminal, and any static holder - a static "current response" would be a session by another name
 * and would break request isolation. It is immutable, has no setter, no mutable field and no static
 * mutable state.
 *
 * <h2>Serialisation, validation, and why every helper here is {@code static}</h2>
 *
 * Jackson policy belongs to {@code config/WebConfig.java}: trimming, empty-string-to-null coercion and
 * null or empty exclusion are all refused so that space-padded {@code PIC X(n)} values survive a round
 * trip unchanged. What this type declares is one {@code @JsonProperty} per screen field, pinning each
 * wire name to its {@code xxxI} item in lower case - the presentation contract AAP 0.6.3 fixes, and the
 * reason a caller sees {@code trnname} rather than the Java identifier {@code trnName}. It is also what
 * lets the paired request accept this response verbatim, since both sides name the same item. Nothing
 * else is declared: no {@code @JsonNaming}, no {@code @JsonInclude}, no {@code @JsonIgnore} and no
 * custom serialiser.
 *
 * <p>That has one consequence worth stating, because it explains the shape of the API below: since no
 * property may be suppressed, no derived <em>instance</em> accessor may be added. An
 * {@code isAdminRole()} method would become a JSON property that the canonical constructor cannot
 * accept back, breaking a serialise-then-deserialise round trip, and it could assert a role
 * contradicting the {@link #role()} byte it travelled with. Every derived helper here is
 * {@code static} instead: static methods are invisible to bean introspection, and a pure function is
 * not state.
 *
 * <p>There are no Bean Validation annotations either. Validation constraints belong on requests; a
 * response is never validated, so {@code @Size} here would be inert metadata implying a check that
 * never runs, and {@code @NotBlank} or {@code @NotNull} would be actively wrong - a screen field
 * legitimately holds spaces. Widths are enforced instead by the canonical constructor, which is
 * strictly stronger because it cannot be skipped.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * // The initial screen, before POPULATE-HEADER-INFO has run: every field its own width in spaces.
 * SignOnResponse response = SignOnResponse.empty();
 *
 * // POPULATE-HEADER-INFO, app/cbl/COSGN00C.cbl lines 177-204, writes exactly these eight fields.
 * response = response.withHeader(SignOnResponse.TRANID, screenTitles.title01(), "08/22/22",
 *         SignOnResponse.PROGRAM_NAME, screenTitles.title02(), "14:07:31", "CICSAPPL", "CICS");
 *
 * // A rejected sign-on: MOVE WS-MESSAGE TO ERRMSGO at line 149, and no navigation.
 * SignOnResponse rejected = response.withErrMsg("Wrong Password. Try again ...");
 *
 * // An accepted sign-on. The SERVICE decides; the response only carries what it decided.
 * String role = secUser.secUsrType();                              // MOVE SEC-USR-TYPE, line 227
 * String target = SignOnResponse.resolveNextProgram(role);         // the IF/ELSE of lines 230-240
 * NavigationContext context = NavigationContext.empty()
 *         .withFromTranid(SignOnResponse.TRANID)                   // line 224
 *         .withFromProgram(SignOnResponse.PROGRAM_NAME)            // line 225
 *         .withUserId("ADMIN001")                                  // line 226
 *         .withUserType(role)                                      // line 227
 *         .withPgmEnter();                                         // line 228, MOVE ZEROS
 * SignOnResponse accepted = response.withNavigation(role, target,
 *         SignOnResponse.MAPSET_NAME, SignOnResponse.MAP_NAME, context);
 * </pre>
 *
 * @param trnName           {@code TRNNAMEO PIC X(4)}: the transaction identifier shown as
 *                          {@code Tran :}, {@value #TRANID} here
 * @param title01           {@code TITLE01O PIC X(40)}: the first title line, from
 *                          {@code common.ScreenTitles}
 * @param curDate           {@code CURDATEO PIC X(8)}: the current date as {@code MM/DD/YY}, from
 *                          {@code common.DateHeader}
 * @param pgmName           {@code PGMNAMEO PIC X(8)}: the program name shown as {@code Prog :},
 *                          {@value #PROGRAM_NAME} here
 * @param title02           {@code TITLE02O PIC X(40)}: the second title line, from
 *                          {@code common.ScreenTitles}
 * @param curTime           {@code CURTIMEO PIC X(9)}: the current time as {@code HH:MM:SS} in a
 *                          nine-character field, one wider than the other user screens
 * @param applId            {@code APPLIDO PIC X(8)}: the CICS region's application identifier, from
 *                          {@code EXEC CICS ASSIGN} and therefore supplied by configuration here
 * @param sysId             {@code SYSIDO PIC X(8)}: the CICS system identifier, likewise from
 *                          {@code EXEC CICS ASSIGN}
 * @param userId            {@code USERIDO PIC X(8)}: the user-id field of the screen. The same span as
 *                          {@code USERIDI}, so on a repaint that follows a receive it carries the
 *                          identifier the operator typed, verbatim and not upper-cased
 * @param passwd            {@code PASSWDO PIC X(8)}: the password field of the screen, dark and
 *                          modified-data-tagged. The same span as {@code PASSWDI}, so a repaint
 *                          re-transmits the image the receive delivered. Withheld from
 *                          {@link #toString()}
 * @param errMsg            {@code ERRMSGO PIC X(78)}: the error line at row 23, red and bright
 * @param role              {@code CDEMO-USER-TYPE PIC X(01)}: {@value #ROLE_ADMIN} for an
 *                          administrator, {@value #ROLE_USER} for a regular user, a space before
 *                          sign-on, and possibly none of those
 * @param nextProgram       the {@code XCTL} target the client should call next -
 *                          {@value #NEXT_PROGRAM_ADMIN} or {@value #NEXT_PROGRAM_USER}
 * @param nextMapset        the mapset the client should render next, {@code PIC X(7)}
 * @param nextMap           the map the client should render next, {@code PIC X(7)}
 * @param plainText         the {@value #PLAIN_TEXT_LENGTH} bytes
 *                          {@code EXEC CICS SEND TEXT FROM(WS-MESSAGE)} transmitted on the PF3 path,
 *                          and {@value #PLAIN_TEXT_LENGTH} spaces on every path that sent no text
 * @param navigationContext the {@code CARDDEMO-COMMAREA} carried between calls, never {@code null}
 */
public record SignOnResponse(@JsonProperty("trnname") String trnName,
                             String title01,
                             @JsonProperty("curdate") String curDate,
                             @JsonProperty("pgmname") String pgmName,
                             String title02,
                             @JsonProperty("curtime") String curTime,
                             @JsonProperty("applid") String applId,
                             @JsonProperty("sysid") String sysId,
                             @JsonProperty("userid") String userId,
                             @JsonProperty("passwd") String passwd,
                             @JsonProperty("errmsg") String errMsg,
                             String role,
                             String nextProgram,
                             String nextMapset,
                             String nextMap,
                             String plainText,
                             NavigationContext navigationContext) {

    // =================================================================================================
    // The symbolic-map item names, carried VERBATIM as app/cpy-bms/COSGN00.CPY spells them. These are
    // the names a field-for-field diff reports, so a "tidied" name would make a real difference
    // invisible. The trailing O is the output-direction suffix and is part of the name.
    // =================================================================================================

    /** Symbolic-map item behind {@link #trnName()}: {@code TRNNAMEO}, CPY line 92. */
    public static final String TRNNAME_FIELD = "TRNNAMEO";

    /** Symbolic-map item behind {@link #title01()}: {@code TITLE01O}, CPY line 98. */
    public static final String TITLE01_FIELD = "TITLE01O";

    /** Symbolic-map item behind {@link #curDate()}: {@code CURDATEO}, CPY line 104. */
    public static final String CURDATE_FIELD = "CURDATEO";

    /** Symbolic-map item behind {@link #pgmName()}: {@code PGMNAMEO}, CPY line 110. */
    public static final String PGMNAME_FIELD = "PGMNAMEO";

    /** Symbolic-map item behind {@link #title02()}: {@code TITLE02O}, CPY line 116. */
    public static final String TITLE02_FIELD = "TITLE02O";

    /** Symbolic-map item behind {@link #curTime()}: {@code CURTIMEO}, CPY line 122. */
    public static final String CURTIME_FIELD = "CURTIMEO";

    /** Symbolic-map item behind {@link #applId()}: {@code APPLIDO}, CPY line 128. */
    public static final String APPLID_FIELD = "APPLIDO";

    /** Symbolic-map item behind {@link #sysId()}: {@code SYSIDO}, CPY line 134. */
    public static final String SYSID_FIELD = "SYSIDO";

    /** Symbolic-map item behind {@link #userId()}: {@code USERIDO}, CPY line 140. */
    public static final String USERID_FIELD = "USERIDO";

    /**
     * Symbolic-map item behind {@link #passwd()}: {@code PASSWDO}, CPY line 146 - the same eight-byte
     * span as {@code PASSWDI} at CPY line 84, because line 85 redefines the group.
     */
    public static final String PASSWD_FIELD = "PASSWDO";

    /** Symbolic-map item behind {@link #errMsg()}: {@code ERRMSGO}, CPY line 152. */
    public static final String ERRMSG_FIELD = "ERRMSGO";

    // =================================================================================================
    // Widths. Every one is the literal digit its own PICTURE clause declares, cross-checked against the
    // LENGTH= operand of the matching DFHMDF. Nothing here is computed from anything else, because a
    // derived width can silently follow a mistake in whatever it was derived from.
    // =================================================================================================

    /** {@code TRNNAMEO PIC X(4)}, and {@code TRNNAME ... LENGTH=4} at bms line 36. */
    public static final int TRNNAME_LENGTH = 4;

    /** {@code TITLE01O PIC X(40)}, and {@code TITLE01 ... LENGTH=40} at bms line 40. */
    public static final int TITLE01_LENGTH = 40;

    /** {@code CURDATEO PIC X(8)}, and {@code CURDATE ... LENGTH=8} at bms line 49. */
    public static final int CURDATE_LENGTH = 8;

    /** {@code PGMNAMEO PIC X(8)}, and {@code PGMNAME ... LENGTH=8} at bms line 59. */
    public static final int PGMNAME_LENGTH = 8;

    /** {@code TITLE02O PIC X(40)}, and {@code TITLE02 ... LENGTH=40} at bms line 63. */
    public static final int TITLE02_LENGTH = 40;

    /**
     * {@code CURTIMEO PIC X(9)}, and {@code CURTIME ... LENGTH=9} at bms line 72 with
     * {@code INITIAL='Ahh:mm:ss'}.
     *
     * <p><strong>Nine, not eight.</strong> The four other user screens - {@code COUSR00} through
     * {@code COUSR03} - declare their time field {@code X(8)}. This one is a character wider in both
     * the copybook and the mapset, so it is declared a character wider here. Regularising it to eight
     * would shorten a real field.
     */
    public static final int CURTIME_LENGTH = 9;

    /** {@code APPLIDO PIC X(8)}, and {@code APPLID ... LENGTH=8} at bms line 82. */
    public static final int APPLID_LENGTH = 8;

    /** {@code SYSIDO PIC X(8)}, and {@code SYSID ... LENGTH=8} at bms line 91. */
    public static final int SYSID_LENGTH = 8;

    /** {@code USERIDO PIC X(8)}, and {@code USERID ... LENGTH=8} at bms line 159. */
    public static final int USERID_LENGTH = 8;

    /** {@code PASSWDO PIC X(8)}, and {@code PASSWD ... LENGTH=8} at bms line 178. */
    public static final int PASSWD_LENGTH = 8;

    /**
     * {@code ERRMSGO PIC X(78)}, and {@code ERRMSG ... LENGTH=78} at bms line 199.
     *
     * <p>Seventy-eight, although the message that lands here is built in {@code WS-MESSAGE PIC X(80)}
     * at {@code app/cbl/COSGN00C.cbl:38}. The two rightmost characters are lost by
     * {@code MOVE WS-MESSAGE TO ERRMSGO} at line 149. This type declares the receiver's width and
     * refuses anything longer; the shortening is performed deliberately by the caller through
     * {@code common.FixedWidthCodec.movePicX}.
     */
    public static final int ERRMSG_LENGTH = 78;

    // =================================================================================================
    // Widths of the navigation members. These delegate to NavigationContext rather than restating a
    // number, because they ARE that structure's fields: a value that fits here must fit there, and one
    // literal cannot be updated without the other.
    // =================================================================================================

    /** Width of {@link #role()}: {@code CDEMO-USER-TYPE PIC X(01)}, COCOM01Y line 26. */
    public static final int ROLE_LENGTH = NavigationContext.USER_TYPE_LENGTH;

    /** Width of {@link #nextProgram()}: {@code CDEMO-TO-PROGRAM PIC X(08)}, COCOM01Y line 24. */
    public static final int NEXT_PROGRAM_LENGTH = NavigationContext.TO_PROGRAM_LENGTH;

    /** Width of {@link #nextMapset()}: {@code CDEMO-LAST-MAPSET PIC X(7)}, COCOM01Y line 44. */
    public static final int NEXT_MAPSET_LENGTH = NavigationContext.LAST_MAPSET_LENGTH;

    /** Width of {@link #nextMap()}: {@code CDEMO-LAST-MAP PIC X(7)}, COCOM01Y line 43. */
    public static final int NEXT_MAP_LENGTH = NavigationContext.LAST_MAP_LENGTH;

    /**
     * Width of {@link #plainText()}: {@code WS-MESSAGE PIC X(80)} at
     * {@code app/cbl/COSGN00C.cbl:38}, which is what
     * {@code SEND TEXT FROM(WS-MESSAGE) LENGTH(LENGTH OF WS-MESSAGE)} at L164-169 transmits.
     *
     * <p>Eighty, not {@value #ERRMSG_LENGTH}: the plain-text send is not a map field and is not subject
     * to the {@code MOVE WS-MESSAGE TO ERRMSGO} narrowing at L149.
     */
    public static final int PLAIN_TEXT_LENGTH = 80;

    /**
     * The name {@link #plainText()} is diagnosed under - the working-storage item the {@code SEND TEXT}
     * sends from, since the transmission has no symbolic-map item of its own.
     */
    public static final String PLAIN_TEXT_MEMBER = "WS-MESSAGE";

    /**
     * What {@link #toString()} prints in place of {@link #passwd()} - a constant, so the rendering cannot
     * disclose the credential, its length, or whether one was carried.
     */
    private static final String PASSWD_REDACTED = SensitiveDiagnostics.REDACTED;

    // =================================================================================================
    // The field census. Publishing all three counts is what makes the projection machine-checkable: the
    // mapset's named-field count, the symbolic map's item count and this payload's member count are one
    // number, and an assertion holds them equal so a dropped field cannot pass unnoticed.
    // =================================================================================================

    /** Total {@code DFHMDF} definitions in {@code app/bms/COSGN00.bms}, named and unnamed. */
    public static final int MAPSET_FIELD_COUNT = 37;

    /**
     * Name-labelled {@code DFHMDF} definitions in {@code app/bms/COSGN00.bms}, which is also the
     * number of {@code xxxI} items and of {@code xxxO} items in {@code app/cpy-bms/COSGN00.CPY}.
     */
    public static final int MAPSET_NAMED_FIELD_COUNT = 11;

    /**
     * Map-derived members of this payload - every named screen field, so this equals
     * {@value #MAPSET_NAMED_FIELD_COUNT}.
     *
     * <p>The two counts were once deliberately different: {@code PASSWDO} was omitted on the evidence
     * that no {@code MOVE} in {@code COSGN00C} writes it. {@code COSGN0AO REDEFINES COSGN0AI} at
     * {@code app/cpy-bms/COSGN00.CPY:85} makes that evidence insufficient - the receive writes the span
     * and the send transmits it - so the field is projected and the counts agree. Both are still
     * published, because equal-by-assertion is worth more than equal-by-assumption.
     */
    public static final int MAP_FIELD_COUNT = MAPSET_NAMED_FIELD_COUNT;

    /**
     * All {@value #MAPSET_NAMED_FIELD_COUNT} name-labelled {@code DFHMDF} fields of
     * {@code app/bms/COSGN00.bms}, in mapset declaration order. Immutable.
     *
     * <p>This is the output of
     * {@code grep -E '^[A-Z0-9]+ +DFHMDF' app/bms/COSGN00.bms | awk '{print $1}'}, transcribed.
     */
    public static final List<String> MAPSET_NAMED_FIELDS = List.of(
            "TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "APPLID", "SYSID", "USERID", "PASSWD", "ERRMSG");

    /**
     * The {@value #MAP_FIELD_COUNT} symbolic-map items this payload projects, in the order the
     * components are declared - which is the order {@code 01 COSGN0AO} declares them. Immutable.
     */
    public static final List<String> MAP_FIELDS = List.of(
            TRNNAME_FIELD, TITLE01_FIELD, CURDATE_FIELD, PGMNAME_FIELD, TITLE02_FIELD,
            CURTIME_FIELD, APPLID_FIELD, SYSID_FIELD, USERID_FIELD, PASSWD_FIELD, ERRMSG_FIELD);

    // =================================================================================================
    // Literals the program itself declares. Transcribed once here so the controller and the service
    // cannot drift apart on them, and so a change to any of them is visible in exactly one place.
    // =================================================================================================

    /** {@code WS-TRANID PIC X(04) VALUE 'CC00'}, {@code app/cbl/COSGN00C.cbl:37}. */
    public static final String TRANID = "CC00";

    /** {@code WS-PGMNAME PIC X(08) VALUE 'COSGN00C'}, {@code app/cbl/COSGN00C.cbl:36}. */
    public static final String PROGRAM_NAME = "COSGN00C";

    /**
     * The mapset of {@code EXEC CICS SEND MAP('COSGN0A') MAPSET('COSGN00')},
     * {@code app/cbl/COSGN00C.cbl:153}. Seven characters, which is why
     * {@link #NEXT_MAPSET_LENGTH} is seven.
     */
    public static final String MAPSET_NAME = "COSGN00";

    /**
     * The map of the same {@code SEND}, {@code app/cbl/COSGN00C.cbl:152}. Seven characters plus a
     * one-character direction suffix is what forms {@code COSGN0AI} and {@code COSGN0AO}, which is why
     * {@link #NEXT_MAP_LENGTH} is seven rather than eight.
     */
    public static final String MAP_NAME = "COSGN0A";

    // =================================================================================================
    // The role, and the two XCTL targets it selects between.
    // =================================================================================================

    /**
     * {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'}, {@code app/cpy/COCOM01Y.cpy:27}. Delegated to
     * {@link NavigationContext} so the byte is defined once for all seventeen screens.
     */
    public static final String ROLE_ADMIN = NavigationContext.USER_TYPE_ADMIN;

    /**
     * {@code 88 CDEMO-USRTYP-USER VALUE 'U'}, {@code app/cpy/COCOM01Y.cpy:28}.
     *
     * <p>Present for completeness and for reading the source, <strong>not</strong> as a routing
     * condition: {@link #resolveNextProgram(String)} never tests it. See that method for why.
     */
    public static final String ROLE_USER = NavigationContext.USER_TYPE_USER;

    /**
     * {@code EXEC CICS XCTL PROGRAM ('COADM01C')}, {@code app/cbl/COSGN00C.cbl:232} - the target when
     * {@code 88 CDEMO-USRTYP-ADMIN} holds.
     */
    public static final String NEXT_PROGRAM_ADMIN = "COADM01C";

    /**
     * {@code EXEC CICS XCTL PROGRAM ('COMEN01C')}, {@code app/cbl/COSGN00C.cbl:237} - the target of the
     * {@code ELSE}, and therefore the target for every user type that is not {@value #ROLE_ADMIN}.
     */
    public static final String NEXT_PROGRAM_USER = "COMEN01C";

    // =================================================================================================
    // The canonical constructor. It is the only way an instance comes into being, so it is where the
    // declared widths are enforced.
    // =================================================================================================

    /**
     * Validates every component against the width its {@code PICTURE} clause declares.
     *
     * <p>Two rules, applied uniformly to all sixteen character components:
     *
     * <ul>
     *   <li><strong>No character component may be {@code null}.</strong> There is no null in a COBOL
     *       record. An unset {@code PIC X} field holds spaces, which is exactly what {@link #empty()}
     *       produces, so {@code null} always indicates a caller that skipped a field rather than a
     *       field that is genuinely blank.</li>
     *   <li><strong>No character component may be longer than its declared width.</strong> A longer
     *       value is rejected instead of being quietly clipped. This matters most for
     *       {@link #errMsg()}: {@code WS-MESSAGE} is {@code PIC X(80)} and {@code ERRMSGO} is
     *       {@code X(78)}, so a message that overflows is a real event in the source, and it is far
     *       better for the caller to shorten it deliberately through
     *       {@code common.FixedWidthCodec.movePicX} - where the direction of the loss is visible - than
     *       for this constructor to guess. A <em>shorter</em> value is accepted and simply carries
     *       fewer characters, mirroring a COBOL {@code MOVE} into a wider {@code PIC X} receiver, which
     *       pads on the right.</li>
     * </ul>
     *
     * <p>{@link #navigationContext()} is required rather than defaulted. A response with no
     * communication area could not be handed back on the next call, and since there is no server-side
     * session to fall back on, a missing context would silently break the conversation. A caller that
     * has nothing to carry yet passes {@link NavigationContext#empty()}, which is a real, valid,
     * fully-formed communication area of spaces and zeros.
     *
     * <p>The width check is delegated to one shared guard so the rule is stated once and all sixteen
     * character components are held to the identical standard.
     *
     * @throws NullPointerException     if any character component or the navigation context is
     *                                  {@code null}
     * @throws IllegalArgumentException if any character component is longer than its declared width
     */
    public SignOnResponse {
        trnName = requireWidth(trnName, TRNNAME_LENGTH, TRNNAME_FIELD);
        title01 = requireWidth(title01, TITLE01_LENGTH, TITLE01_FIELD);
        curDate = requireWidth(curDate, CURDATE_LENGTH, CURDATE_FIELD);
        pgmName = requireWidth(pgmName, PGMNAME_LENGTH, PGMNAME_FIELD);
        title02 = requireWidth(title02, TITLE02_LENGTH, TITLE02_FIELD);
        curTime = requireWidth(curTime, CURTIME_LENGTH, CURTIME_FIELD);
        applId = requireWidth(applId, APPLID_LENGTH, APPLID_FIELD);
        sysId = requireWidth(sysId, SYSID_LENGTH, SYSID_FIELD);
        userId = requireWidth(userId, USERID_LENGTH, USERID_FIELD);
        passwd = requireWidth(passwd, PASSWD_LENGTH, PASSWD_FIELD);
        errMsg = requireWidth(errMsg, ERRMSG_LENGTH, ERRMSG_FIELD);
        role = requireWidth(role, ROLE_LENGTH, NavigationContext.USER_TYPE_FIELD);
        nextProgram = requireWidth(nextProgram, NEXT_PROGRAM_LENGTH, NavigationContext.TO_PROGRAM_FIELD);
        nextMapset = requireWidth(nextMapset, NEXT_MAPSET_LENGTH, NavigationContext.LAST_MAPSET_FIELD);
        nextMap = requireWidth(nextMap, NEXT_MAP_LENGTH, NavigationContext.LAST_MAP_FIELD);
        plainText = requireWidth(plainText, PLAIN_TEXT_LENGTH, PLAIN_TEXT_MEMBER);
        navigationContext = Objects.requireNonNull(navigationContext, "navigationContext");
    }

    // =================================================================================================
    // The initial screen.
    // =================================================================================================

    /**
     * The sign-on screen before anything has been written to it: every one of the
     * {@value #MAP_FIELD_COUNT} screen fields carrying the unpainted image at its own declared width,
     * and {@link NavigationContext#empty()} as the communication area.
     *
     * <p>This is the state {@code app/cbl/COSGN00C.cbl:80-83} produces when {@code EIBCALEN} is zero -
     * {@code MOVE LOW-VALUES TO COSGN0AO}, then {@code SEND-SIGNON-SCREEN}. The unpainted image is
     * therefore {@code LOW-VALUES}: {@code X'00'} at the declared width, exactly the byte line 81
     * moves. An earlier revision of this factory substituted spaces on the reasoning that a JSON
     * payload is not a 3270 buffer and has no "not transmitted" state to represent. That reasoning was
     * wrong twice over - {@code U+0000} is a perfectly ordinary JSON string character that Jackson
     * emits as the {@code \\u0000} escape, and a field-for-field diff of the returned map area reports
     * the substitution - so the byte the source moves is what this factory now produces.
     * {@link ScreenFieldImage} records that decision once, for all seventeen screens, and lists all
     * seventeen {@code MOVE LOW-VALUES} sites.
     *
     * <p>{@code ERRMSGO} is a separate case and is <em>not</em> special-cased here. Line 78 moves
     * {@code SPACES} into it unconditionally, at the head of {@code MAIN-PARA}, so on every path
     * including this one the error line is painted - with a blank value, but painted. That paint
     * happens in the controller where the source performs it, which leaves this factory free to state
     * one rule for all ten fields.
     *
     * <p>{@link #userId()} and {@link #passwd()} are unpainted here too, and on this path that is what
     * the program transmits: L81's {@code MOVE LOW-VALUES TO COSGN0AO} clears both spans and no
     * {@code RECEIVE} follows, so the {@code SEND} at L151-157 sends {@code X'00'} in each. The same
     * image is correct on the PF3 and invalid-key paths, where the spans were never written at all.
     * {@link #withReceivedMapArea(String, String)} is the only way they become anything else.
     *
     * <p>{@link #plainText()} is {@value #PLAIN_TEXT_LENGTH} spaces rather than an unpainted image,
     * because it is not a screen field: nothing was transmitted, and {@code WS-MESSAGE PIC X(80) VALUE
     * SPACES} at {@code app/cbl/COSGN00C.cbl:38} is what an untransmitted message holds.
     *
     * <p>{@link #role()} is a single space and {@link #nextProgram()} is spaces: both are
     * {@code CARDDEMO-COMMAREA} carriers rather than map fields, so {@link ScreenFieldImage} does not
     * govern them and {@link NavigationContext#empty()} documents their resting state.
     * {@link #isAdminRole(String)} is false for a space - no role is implied before sign-on - and an
     * unauthenticated response names no target, because {@code COSGN00C} transfers only from inside the
     * successful branch at line 230.
     *
     * @return the initial sign-on response, never {@code null}
     */
    public static SignOnResponse empty() {
        return new SignOnResponse(ScreenFieldImage.unpainted(TRNNAME_LENGTH),
                ScreenFieldImage.unpainted(TITLE01_LENGTH),
                ScreenFieldImage.unpainted(CURDATE_LENGTH),
                ScreenFieldImage.unpainted(PGMNAME_LENGTH),
                ScreenFieldImage.unpainted(TITLE02_LENGTH),
                ScreenFieldImage.unpainted(CURTIME_LENGTH),
                ScreenFieldImage.unpainted(APPLID_LENGTH),
                ScreenFieldImage.unpainted(SYSID_LENGTH),
                ScreenFieldImage.unpainted(USERID_LENGTH),
                ScreenFieldImage.unpainted(PASSWD_LENGTH),
                ScreenFieldImage.unpainted(ERRMSG_LENGTH),
                spaces(ROLE_LENGTH),
                spaces(NEXT_PROGRAM_LENGTH),
                spaces(NEXT_MAPSET_LENGTH),
                spaces(NEXT_MAP_LENGTH),
                spaces(PLAIN_TEXT_LENGTH),
                NavigationContext.empty());
    }

    // =================================================================================================
    // The role test and the XCTL target, both static. Static because a derived INSTANCE accessor would
    // become a JSON property that the canonical constructor cannot accept back - and the annotation that
    // would suppress it is not available to this type. A pure function is not state.
    // =================================================================================================

    /**
     * Whether {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} holds for the given user type.
     *
     * <p>This is the condition {@code app/cbl/COSGN00C.cbl:230} tests. It is an exact match on
     * {@value #ROLE_ADMIN}, and it is deliberately <em>not</em> the negation of a check for
     * {@value #ROLE_USER}: {@code CDEMO-USER-TYPE} is {@code PIC X(01)} and may hold a space - which is
     * what a freshly initialised communication area holds - or any other byte, in which case neither
     * condition name holds and this method is correctly false.
     *
     * <p>A {@code null} argument is false rather than an error, so a caller inspecting a partially
     * built payload cannot be surprised by an exception from a predicate.
     *
     * @param role the user type to test, typically {@link #role()} or
     *             {@link NavigationContext#userType()}; may be {@code null}
     * @return {@code true} if and only if {@code role} is exactly {@value #ROLE_ADMIN}
     */
    public static boolean isAdminRole(String role) {
        return ROLE_ADMIN.equals(role);
    }

    /**
     * The {@code EXEC CICS XCTL} target for the given user type, transcribed from
     * {@code app/cbl/COSGN00C.cbl:230-240}.
     *
     * <pre>
     *  IF CDEMO-USRTYP-ADMIN
     *       EXEC CICS XCTL PROGRAM ('COADM01C') ... END-EXEC
     *  ELSE
     *       EXEC CICS XCTL PROGRAM ('COMEN01C') ... END-EXEC
     *  END-IF
     * </pre>
     *
     * <p><strong>The asymmetry is the specification.</strong> The source tests one condition and takes
     * the {@code ELSE} for everything else, so the mapping is {@value #ROLE_ADMIN} to
     * {@value #NEXT_PROGRAM_ADMIN} and <em>anything at all otherwise</em> to
     * {@value #NEXT_PROGRAM_USER} - including {@value #ROLE_USER}, a space, {@code null} and any
     * unexpected byte. Written as an equality test on {@value #ROLE_USER} with a third outcome, it
     * would send an uninitialised or corrupt user type somewhere the legacy system never sends it.
     *
     * <p>This method exists so that mapping is stated exactly once and can be asserted directly. It is
     * a lookup, not a decision the payload makes: {@code user.SignOnService} calls it and the resulting
     * value is <em>carried</em> in {@link #nextProgram()}. Nothing on this type recomputes
     * {@link #nextProgram()} from {@link #role()} when the payload is read, because a response must
     * report what the service decided rather than re-deriving it.
     *
     * @param role the user type to route on, from {@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE} at
     *             line 227; may be {@code null}
     * @return {@value #NEXT_PROGRAM_ADMIN} for {@value #ROLE_ADMIN}, otherwise
     *         {@value #NEXT_PROGRAM_USER}; never {@code null}
     */
    public static String resolveNextProgram(String role) {
        return isAdminRole(role) ? NEXT_PROGRAM_ADMIN : NEXT_PROGRAM_USER;
    }

    // =================================================================================================
    // Immutable replacement. THE INVARIANT: one method per paragraph of app/cbl/COSGN00C.cbl that
    // writes this screen, and none for anything else - so a call site reads as the paragraph it stands
    // for, and no convenience exists for writing a field the program never writes. Each returns a new
    // instance, so a response already handed to a collaborator cannot change underneath it. There is no
    // setter and no mutable field.
    //
    // These take arguments, so bean introspection does not mistake any of them for a property.
    // =================================================================================================

    /**
     * The eight header fields, exactly as {@code POPULATE-HEADER-INFO} writes them -
     * {@code app/cbl/COSGN00C.cbl:177-204}.
     *
     * <p>That paragraph writes {@code TITLE01O} (line 181), {@code TITLE02O} (182), {@code TRNNAMEO}
     * (183), {@code PGMNAMEO} (184), {@code CURDATEO} (190), {@code CURTIMEO} (196), {@code APPLIDO}
     * (199) and {@code SYSIDO} (203) - and nothing else. The parameters are ordered as the components
     * are declared, which is symbolic-map order, rather than as the paragraph happens to write them.
     *
     * <p>{@link #userId()}, {@link #passwd()} and {@link #errMsg()} are untouched here on purpose. The
     * paragraph writes neither overlay span - they carry whatever the {@code RECEIVE} left in them, which
     * is {@link #withReceivedMapArea(String, String)}'s business - and {@code ERRMSGO} is written by this
     * paragraph's caller at line 149, after it has run. {@link #withErrMsg(String)} is that step.
     *
     * @param newTrnName the transaction identifier, at most {@value #TRNNAME_LENGTH} characters
     * @param newTitle01 the first title line, at most {@value #TITLE01_LENGTH} characters
     * @param newCurDate the date as {@code MM/DD/YY}, at most {@value #CURDATE_LENGTH} characters
     * @param newPgmName the program name, at most {@value #PGMNAME_LENGTH} characters
     * @param newTitle02 the second title line, at most {@value #TITLE02_LENGTH} characters
     * @param newCurTime the time as {@code HH:MM:SS}, at most {@value #CURTIME_LENGTH} characters
     * @param newApplId  the CICS application identifier, at most {@value #APPLID_LENGTH} characters
     * @param newSysId   the CICS system identifier, at most {@value #SYSID_LENGTH} characters
     * @return a new response carrying the header, never {@code null}
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if any argument exceeds its declared width
     */
    public SignOnResponse withHeader(String newTrnName,
                                     String newTitle01,
                                     String newCurDate,
                                     String newPgmName,
                                     String newTitle02,
                                     String newCurTime,
                                     String newApplId,
                                     String newSysId) {
        return new SignOnResponse(newTrnName, newTitle01, newCurDate, newPgmName, newTitle02,
                newCurTime, newApplId, newSysId, userId, passwd, errMsg,
                role, nextProgram, nextMapset, nextMap, plainText, navigationContext);
    }

    /**
     * The error line, as {@code MOVE WS-MESSAGE TO ERRMSGO OF COSGN0AO} writes it -
     * {@code app/cbl/COSGN00C.cbl:149}.
     *
     * <p>Five literals reach this field in the source, and they belong to {@code user.SignOnService},
     * which decides which one applies: {@code 'Please enter User ID ...'} (line 120),
     * {@code 'Please enter Password ...'} (125), {@code 'Wrong Password. Try again ...'} (242),
     * {@code 'User not found. Try again ...'} (249) and {@code 'Unable to verify the User ...'} (254).
     * None of them is embedded here; this type only carries whichever one arrives.
     *
     * <p>The argument must already fit {@value #ERRMSG_LENGTH} characters. The source's own
     * {@code PIC X(80)} to {@code X(78)} narrowing is performed by the caller through
     * {@code common.FixedWidthCodec.movePicX}, so an over-long message is a rejected programming error
     * here rather than a silent clip.
     *
     * @param newErrMsg the message, at most {@value #ERRMSG_LENGTH} characters
     * @return a new response carrying the message, never {@code null}
     * @throws NullPointerException     if {@code newErrMsg} is {@code null}; a cleared line is
     *                                  {@value #ERRMSG_LENGTH} spaces, which is what
     *                                  {@code MOVE SPACES TO ERRMSGO} at line 78 writes
     * @throws IllegalArgumentException if {@code newErrMsg} exceeds {@value #ERRMSG_LENGTH} characters
     */
    public SignOnResponse withErrMsg(String newErrMsg) {
        return new SignOnResponse(trnName, title01, curDate, pgmName, title02, curTime, applId, sysId,
                userId, passwd, newErrMsg, role, nextProgram, nextMapset, nextMap, plainText,
                navigationContext);
    }

    /**
     * The navigation outcome of a successful sign-on - the stateless form of
     * {@code app/cbl/COSGN00C.cbl:224-240}.
     *
     * <p>Every argument is <em>carried</em>. This method resolves nothing and validates no relationship
     * between the role and the target: {@code user.SignOnService} calls
     * {@link #resolveNextProgram(String)} and passes the result in. Cross-checking them here would put
     * a decision in a payload contract and would make it impossible for a test to construct the
     * mismatched state a defective service could produce.
     *
     * @param newRole              the user type from {@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE} at
     *                             line 227, at most {@value #ROLE_LENGTH} character
     * @param newNextProgram       the {@code XCTL} target, at most {@value #NEXT_PROGRAM_LENGTH}
     *                             characters, normally {@link #resolveNextProgram(String)} of
     *                             {@code newRole}
     * @param newNextMapset        the mapset to render next, at most {@value #NEXT_MAPSET_LENGTH}
     *                             characters
     * @param newNextMap           the map to render next, at most {@value #NEXT_MAP_LENGTH} characters
     * @param newNavigationContext the communication area populated at lines 224-228, never {@code null}
     * @return a new response carrying the navigation outcome, never {@code null}
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if any character argument exceeds its declared width
     */
    public SignOnResponse withNavigation(String newRole,
                                         String newNextProgram,
                                         String newNextMapset,
                                         String newNextMap,
                                         NavigationContext newNavigationContext) {
        return new SignOnResponse(trnName, title01, curDate, pgmName, title02, curTime, applId, sysId,
                userId, passwd, errMsg, newRole, newNextProgram, newNextMapset, newNextMap, plainText,
                newNavigationContext);
    }

    /**
     * The two overlay spans, as {@code EXEC CICS RECEIVE MAP} at {@code app/cbl/COSGN00C.cbl:110-115}
     * left them.
     *
     * <p>{@code 01 COSGN0AO REDEFINES COSGN0AI} at {@code app/cpy-bms/COSGN00.CPY:85} makes
     * {@code USERIDI} and {@code USERIDO} one span, and {@code PASSWDI} and {@code PASSWDO} another, so
     * a {@code SEND MAP ... FROM(COSGN0AO)} after a receive transmits the received images without the
     * program moving anything into an output item. This method is that transmission, and it is the only
     * mutator for either field.
     *
     * <p>Both are set together because the receive fills both together: CICS delivers the whole map
     * area, not one field at a time. Both images are the <strong>raw</strong> received values - the
     * {@code MOVE FUNCTION UPPER-CASE} at L132 and L135 writes {@code WS-USER-ID} and
     * {@code WS-USER-PWD}, never back into the spans - so what comes back on a repaint is what was
     * typed.
     *
     * <p>Call this on the paths that receive the map and not on the three that do not; on those,
     * {@link #empty()} already carries the {@code LOW-VALUES} the spans hold.
     *
     * @param newUserId the {@code USERIDI}/{@code USERIDO} image, at most {@value #USERID_LENGTH}
     *                  characters
     * @param newPasswd the {@code PASSWDI}/{@code PASSWDO} image, at most {@value #PASSWD_LENGTH}
     *                  characters
     * @return a new response carrying both spans, never {@code null}
     * @throws NullPointerException     if either argument is {@code null}; an untransmitted field is
     *                                  {@code LOW-VALUES} at its declared width, not {@code null}
     * @throws IllegalArgumentException if either argument exceeds its declared width
     */
    public SignOnResponse withReceivedMapArea(String newUserId, String newPasswd) {
        return new SignOnResponse(trnName, title01, curDate, pgmName, title02, curTime, applId, sysId,
                newUserId, newPasswd, errMsg, role, nextProgram, nextMapset, nextMap, plainText,
                navigationContext);
    }

    /**
     * The unformatted transmission of {@code SEND-PLAIN-TEXT} -
     * {@code EXEC CICS SEND TEXT FROM(WS-MESSAGE) LENGTH(LENGTH OF WS-MESSAGE) ERASE FREEKB} at
     * {@code app/cbl/COSGN00C.cbl:164-169}.
     *
     * <p>Reached from one arm only, {@code WHEN DFHPF3} at L88-90, which first moves
     * {@code CCDA-MSG-THANK-YOU} into {@code WS-MESSAGE}. The full {@value #PLAIN_TEXT_LENGTH} bytes are
     * carried: this is not a map field, so the {@code MOVE WS-MESSAGE TO ERRMSGO} narrowing at L149 does
     * not apply to it, and the two characters that {@code MOVE} would lose are part of the transmission.
     *
     * <p>No map field is written here, because the paragraph sends no map. A caller that has taken this
     * path leaves every screen field at the image {@link #empty()} gives it.
     *
     * @param newPlainText the transmitted text, at most {@value #PLAIN_TEXT_LENGTH} characters
     * @return a new response carrying the transmission, never {@code null}
     * @throws NullPointerException     if {@code newPlainText} is {@code null}; a path that sends no text
     *                                  carries {@value #PLAIN_TEXT_LENGTH} spaces
     * @throws IllegalArgumentException if {@code newPlainText} exceeds {@value #PLAIN_TEXT_LENGTH}
     *                                  characters
     */
    public SignOnResponse withPlainText(String newPlainText) {
        return new SignOnResponse(trnName, title01, curDate, pgmName, title02, curTime, applId, sysId,
                userId, passwd, errMsg, role, nextProgram, nextMapset, nextMap, newPlainText,
                navigationContext);
    }

    // =================================================================================================
    // Diagnostics. The record's generated toString would publish the PASSWDO span, so it is overridden
    // for that one substitution and nothing else.
    // =================================================================================================

    /**
     * A rendering that names every member except the password, which is replaced by a constant marker.
     *
     * <p>{@link #passwd()} carries the {@code PASSWDO} span, and that span reaches a 3270 as dark field
     * data. A log line is not a 3270. The record's generated {@code toString} includes every component,
     * so inheriting it would reproduce the plaintext credential in any log entry, exception message or
     * debugger view that rendered this object - which is CWE-532, and is a disclosure
     * {@code app/cbl/COSGN00C.cbl} never makes. Carrying the credential is required for parity;
     * broadcasting it is not, and the two are separable. The marker is a constant rather than a mask
     * derived from the value, so neither the password nor its length can be inferred from the rendering.
     *
     * <p>{@code equals} and {@code hashCode} stay exactly as the record generates them, password
     * included: they are value semantics and disclose nothing. This is the same split
     * {@code SignOnRequest} makes on the way in, through the same marker.
     *
     * @return a rendering safe to log, never {@code null}
     */
    @Override
    public String toString() {
        return "SignOnResponse[trnName=" + trnName
                + ", title01=" + title01
                + ", curDate=" + curDate
                + ", pgmName=" + pgmName
                + ", title02=" + title02
                + ", curTime=" + curTime
                + ", applId=" + applId
                + ", sysId=" + sysId
                + ", userId=" + userId
                + ", passwd=" + PASSWD_REDACTED
                + ", errMsg=" + errMsg
                + ", role=" + role
                + ", nextProgram=" + nextProgram
                + ", nextMapset=" + nextMapset
                + ", nextMap=" + nextMap
                + ", plainText=" + plainText
                + ", navigationContext=" + navigationContext
                + ']';
    }

    // =================================================================================================
    // Shared guards. Stated once each, so all sixteen character components are held to one standard.
    // =================================================================================================

    /**
     * Requires that {@code value} is present and fits {@code width} characters, and returns it
     * unchanged.
     *
     * <p>The field name is included in both failure messages because a response with sixteen character
     * members is otherwise hard to diagnose: the message names the symbolic-map or copybook item, the
     * declared width and the actual length, which is enough to locate the offending {@code MOVE}.
     *
     * @param value the candidate value
     * @param width the declared width from the item's {@code PICTURE} clause
     * @param field the copybook or symbolic-map item name, used in diagnostics
     * @return {@code value}, unchanged
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is longer than {@code width}
     */
    private static String requireWidth(String value, int width, String field) {
        if (value == null) {
            throw new NullPointerException(field + " must not be null; an unset PIC X("
                    + width + ") field holds " + width + " spaces");
        }
        if (value.length() > width) {
            throw new IllegalArgumentException(field + " is PIC X(" + width + ") but the value is "
                    + value.length() + " characters: \"" + value
                    + "\". Shorten it deliberately with FixedWidthCodec.movePicX rather than "
                    + "relying on an implicit truncation");
        }
        return value;
    }

    /**
     * A run of {@code width} spaces - the COBOL {@code SPACES} figurative constant filled to a field's
     * declared width.
     *
     * <p>This is an unconditional space fill and deliberately not the alphanumeric {@code MOVE} rule.
     * The {@code MOVE} rule, which pads a shorter sending value and truncates a longer one, lives in
     * {@code common.FixedWidthCodec} and is not reimplemented here.
     *
     * @param width the field's declared width
     * @return {@code width} space characters
     */
    private static String spaces(int width) {
        return " ".repeat(width);
    }
}
