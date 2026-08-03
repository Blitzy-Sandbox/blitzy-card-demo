/*
 * ******************************************************************
 * Program     : MainMenuService.java
 * Application : CardDemo
 * Type        : Spring Service (migrated from CICS COBOL Program)
 * Function    : Main Menu for the Regular users
 * Source      : app/cbl/COMEN01C.cbl (282 lines, 7 paragraphs)
 *               + app/cpy/COMEN02Y.cpy @ 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.service.menu;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.MenuResponse;
import com.cardemo.model.enums.UserType;

/**
 * The main menu of the CardDemo application: the Java replacement for CICS transaction {@code CM00} and the
 * program it fronts, {@code app/cbl/COMEN01C.cbl}.
 *
 * <p>Every legacy claim below cites a path and a line or line range in the frozen corpus, and all of
 * them are keyed to the traceability anchor commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, short {@code 7756d89}, exactly as the {@code Source}
 * lines in the file header record. The anchor is stated once here rather than repeated on every
 * citation.</p>
 *
 * <h2>1. What it does</h2>
 *
 * <p>{@code app/csd/CARDDEMO.CSD:399-400} declares
 * {@code DEFINE TRANSACTION(CM00) ... PROGRAM(COMEN01C)}, and {@code :235} declares
 * {@code DEFINE PROGRAM(COMEN01C)}. That transaction rendered the regular user's menu on a 3270
 * terminal, read the two-character option the operator typed, and transferred control to the program
 * the chosen option names. This bean reproduces the two halves of that conversation as two stateless
 * operations:</p>
 *
 * <ul>
 *   <li>{@link #getMainMenu(UserType)} yields the menu itself - the option payload, the rendered option
 *       lines and the route the operator leaves by. It is the counterpart of the first-entry branch of
 *       {@code MAIN-PARA} at {@code app/cbl/COMEN01C.cbl:87-90}, which sends the map when the
 *       pseudo-conversational re-entry flag is not yet set.</li>
 *   <li>{@link #selectOption(String, UserType)} resolves a typed option into either a navigation target
 *       or the legacy "coming soon" notice, and rejects an unusable or forbidden option. It is the
 *       counterpart of the re-entry branch at {@code app/cbl/COMEN01C.cbl:92-95}, which receives the map
 *       and performs {@code PROCESS-ENTER-KEY} when the operator pressed Enter.</li>
 *   </ul>
 *
 * <p>The ten options come from {@code app/cpy/COMEN02Y.cpy} and are held once, in
 * {@code MenuResponse.MAIN_MENU_OPTIONS}, so that exactly one transcription of that table exists in the
 * Java tree. This bean does not re-transcribe it; see section 6.</p>
 *
 * <p><strong>Side effects: none.</strong> No I/O, no persistence, no messaging, no HTTP, no mutation of
 * any injected or static state. The bean is immutable after construction and therefore thread safe - its
 * two fields are an unmodifiable option table and a {@link java.time.Clock}, both immutable, and the clock
 * is read for the header furniture only. The only observable effect other than the returned value is log
 * output, at {@code DEBUG} level only.</p>
 *
 * <h2>2. How to build, run and test</h2>
 *
 * <p>Build with the repository's pinned Maven wrapper, which resolves Maven 3.9.11 and compiles at
 * release 25 under {@code -Xlint:all -Werror} with {@code failOnWarning} enabled:</p>
 *
 * <pre>
 * ./mvnw -B clean compile
 * ./mvnw -B test        # Surefire 3.5.4, unit tree only
 * ./mvnw -B verify      # adds Failsafe 3.5.4 and the JaCoCo 80% line gate
 * </pre>
 *
 * <p>Where a JDK is not installed on the host, the identical build runs in the pinned container image;
 * Docker Engine and {@code docker compose} are available in the supported environments:</p>
 *
 * <pre>
 * docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -B -DskipTests compile
 * </pre>
 *
 * <p>Running it means running the application: this is an ordinary singleton bean discovered by
 * component scanning, consumed by {@code com.cardemo.controller.MenuController}. It needs no database,
 * no queue and no cloud emulator, so it is exercised most cheaply by a plain constructor call in a unit
 * test - no Spring context required. Unit tests belong in {@code src/test/java/com/cardemo/unit/service}
 * and, because the two retained parity branches described in section 5 cannot fire against the frozen
 * table, they must use the package-private test seam {@link #MainMenuService(Clock, List)}. That seam is
 * also how the header furniture is made assertable: it takes the {@link java.time.Clock} the header date
 * and time are read from, so a test supplies a fixed clock rather than racing the wall clock.</p>
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <p><strong>There is none.</strong> This bean reads no property, no profile and no classpath resource.
 * The option table is a compile-time constant, the option count is fixed at ten by
 * {@code CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10} at {@code app/cpy/COMEN02Y.cpy:21}, and every emitted
 * literal is transcribed byte for byte from the source. Nothing here is tunable, so nothing here can be
 * misconfigured.</p>
 *
 * <p>There is exactly one environmental dependence, and it is required for parity rather than
 * configurable: {@link #MainMenuService()} supplies {@link java.time.Clock#systemDefaultZone()}, whose
 * zone comes from the process default, because {@code FUNCTION CURRENT-DATE} at
 * {@code app/cbl/COMEN01C.cbl:214} returns <em>local</em> date and time. It affects only the header
 * diagnostics of {@link #populateHeaderInfo()}, never a returned value or a persisted one. Change it for
 * the process - by the platform time zone or {@code user.timezone} - not for this bean; there is no
 * property here that overrides it.</p>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <table>
 *   <caption>Outcomes a caller must handle</caption>
 *   <tr><th>Symptom</th><th>Cause and remedy</th></tr>
 *   <tr>
 *     <td>{@code ValidationException} with message {@code Please enter a valid option number...}</td>
 *     <td>The option was blank, zero, non-numeric, larger than the option count, or too long for the
 *         two-byte input field. This is the byte-exact literal of
 *         {@code app/cbl/COMEN01C.cbl:131}. Send a value in the inclusive range 1 to 10.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code ValidationException} with message {@code No access - Admin Only option... }</td>
 *     <td>A standard user selected an option whose table entry is gated to administrators. The byte-exact
 *         literal of {@code app/cbl/COMEN01C.cbl:140}, trailing space included. It cannot occur against
 *         the frozen table, in which all ten entries carry {@code 'U'}; see section 5.</td>
 *   </tr>
 *   <tr>
 *     <td>A {@link MenuSelection} whose {@link MenuSelection#message()} reads
 *         {@code This option Accountis coming soon ...}</td>
 *     <td>Not a fault, and the missing space is not a typo. The selected option is a placeholder whose
 *         target program name begins {@code DUMMY}, and the legacy message is assembled by
 *         {@code STRING ... DELIMITED BY SPACE} at {@code app/cbl/COMEN01C.cbl:159-163}, which truncates
 *         the option caption at its first space and inserts no separator. See
 *         {@link #truncateAtFirstSpace(String)}.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code IllegalArgumentException} naming {@code userType}</td>
 *     <td>The caller reached this bean without a resolved identity. Identity is established upstream from
 *         JWT claims - {@code CDEMO-USER-TYPE PIC X(01)} at {@code app/cpy/COCOM01Y.cpy:26} becomes a role
 *         claim - so a null here is a wiring defect, not operator input. The legacy analogue is the
 *         {@code IF EIBCALEN = 0} branch at {@code app/cbl/COMEN01C.cbl:82-84}, which returned the
 *         terminal to sign-on because no COMMAREA had been passed.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code IllegalArgumentException} naming {@code menuOptions}</td>
 *     <td>Only reachable through the package-private test seam. The supplied table was null, held a null
 *         element, or held more entries than the ten {@code app/cpy/COMEN02Y.cpy:21} populates.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code IllegalArgumentException} with message {@code clock must not be null}</td>
 *     <td>Only reachable through the package-private test seam, which requires an explicit clock. The
 *         production constructor cannot raise it; it supplies
 *         {@link java.time.Clock#systemDefaultZone()} itself.</td>
 *   </tr>
 *   <tr>
 *     <td>A header diagnostic whose {@code date=} or {@code time=} differs from what a test expected</td>
 *     <td>The test constructed the bean through {@link #MainMenuService()} and is therefore racing the
 *         wall clock. Construct it through the seam with a fixed clock instead; the unit tier's
 *         {@code FixedClockProvider} supplies one. A rendering that is off by whole hours instead is a
 *         zone difference, not a clock defect - see section 3.</td>
 *   </tr>
 * </table>
 *
 * <p>If a caller sees {@code This option ...is coming soon ...} for an option that should navigate, the
 * defect is a mis-implemented placeholder guard, not a data problem: consult
 * {@link #processEnterKey(String, UserType)} and the reasoning recorded there under
 * "the transfer never returns".</p>
 *
 * <h2>5. Paragraph map</h2>
 *
 * <p>{@code app/cbl/COMEN01C.cbl} contains exactly seven paragraph labels and each maps to exactly one
 * private method here. None is consolidated with another, none is deleted, and the methods that have no
 * counterpart for part of their body say so explicitly rather than quietly dropping it:</p>
 *
 * <table>
 *   <caption>The seven labels of app/cbl/COMEN01C.cbl and their Java counterparts</caption>
 *   <tr><th>Label</th><th>Line</th><th>Method</th></tr>
 *   <tr><td>{@code MAIN-PARA}</td><td>75</td><td>{@link #mainPara(UserType)}</td></tr>
 *   <tr><td>{@code PROCESS-ENTER-KEY}</td><td>115</td>
 *       <td>{@link #processEnterKey(String, UserType)}</td></tr>
 *   <tr><td>{@code RETURN-TO-SIGNON-SCREEN}</td><td>170</td><td>{@link #returnToSignonScreen()}</td></tr>
 *   <tr><td>{@code SEND-MENU-SCREEN}</td><td>182</td><td>{@link #sendMenuScreen(UserType)}</td></tr>
 *   <tr><td>{@code RECEIVE-MENU-SCREEN}</td><td>199</td>
 *       <td>{@link #receiveMenuScreen(String)}</td></tr>
 *   <tr><td>{@code POPULATE-HEADER-INFO}</td><td>212</td><td>{@link #populateHeaderInfo()}</td></tr>
 *   <tr><td>{@code BUILD-MENU-OPTIONS}</td><td>236</td><td>{@link #buildMenuOptions(List)}</td></tr>
 * </table>
 *
 * <p>Five further private members - {@link #requireResolvedUserType(UserType)},
 * {@link #isAdminOnlyOption(MenuResponse.MainMenuOption)}, {@link #isTwoDigitNumber(String)},
 * {@link #truncateAtFirstSpace(String)} and {@link #padOptionName(String)} - are <strong>not</strong>
 * paragraph counterparts; each is one named statement lifted out of the paragraph that owns it, so that
 * two sites cannot drift apart or a single parity rule be stated twice. Each says so in its own
 * documentation, so the seven-to-seven map above stays unambiguous.</p>
 *
 * <h2>6. Deviations from the source, with severities</h2>
 *
 * <p>Owed an entry in the planned {@code DECISION_LOG.md} under the entries named below. Nothing in this list is a
 * silent improvement; every item is either forced by the target language or required by Rule 1, and every one is
 * observationally inert against the frozen table unless stated otherwise.</p>
 *
 * <ul>
 *   <li><strong>Blocker class if mis-implemented - the transfer never returns.</strong>
 *       {@code EXEC CICS XCTL} at {@code app/cbl/COMEN01C.cbl:152-155} transfers control and does not
 *       come back, so the "coming soon" block at {@code :157-164} is unreachable whenever a real target
 *       resolves. A Java call does return, so {@link #processEnterKey(String, UserType)} returns
 *       immediately after resolving a real target. Decision log entry: <em>XCTL fall-through
 *       guard</em>.</li>
 *   <li><strong>Medium - bounds failure short-circuits before the gate subscript.</strong>
 *       {@code app/cbl/COMEN01C.cbl:127-134} has no early exit, so control falls through into
 *       {@code :136-137} and subscripts the option table with the value that just failed validation.
 *       Java is memory safe and would throw {@code IndexOutOfBoundsException} instead of reading
 *       adjacent storage, so the overread is unreproducible and the rejection is raised at once. The
 *       observable outcome is unchanged: in the source both paths perform {@code SEND-MENU-SCREEN} and
 *       the operator sees the message either way. Decision log entry: <em>bounds short-circuit</em>.</li>
 *   <li><strong>Medium - the option list is filtered for a standard user, not merely gated on
 *       selection.</strong> {@code BUILD-MENU-OPTIONS} at {@code app/cbl/COMEN01C.cbl:238-239} renders
 *       every option up to the count with no eligibility test, so the legacy screen showed a standard
 *       user an administrator-only option and refused it only on selection.
 *       {@link #sendMenuScreen(UserType)} withholds it instead, because Rule 1 Clause D requires least
 *       privilege. Against the frozen table this is a <strong>no-op</strong>: all ten entries of
 *       {@code app/cpy/COMEN02Y.cpy:25-84} carry {@code 'U'}, so nothing is ever withheld and the
 *       rendered lines are byte identical to the legacy screen. The selection gate at {@code :136-137}
 *       is retained unchanged in addition. Decision log entry: <em>menu display filter</em>.</li>
 *   <li><strong>Low - the option table is referenced, not re-transcribed.</strong> Rule 1 Clause C
 *       forbids duplication, and a second copy of the ten literals could drift from the first. The
 *       single transcription lives in {@code MenuResponse.MAIN_MENU_OPTIONS}; this bean binds to it.
 *       Decision log entry: <em>single option-table transcription</em>.</li>
 *   <li><strong>Low - {@code REDEFINES} overreach; rows 11 and 12 are not modelled.</strong>
 *       {@code app/cpy/COMEN02Y.cpy:88} declares {@code OCCURS 12 TIMES} over 46-byte entries, 552
 *       bytes, laid across a populated area of only ten entries, 460 bytes. Subscripts 11 and 12
 *       therefore overlay unrelated {@code WORKING-STORAGE} and what they contain is
 *       <strong>not available</strong>. Nothing is invented for them. Decision log entry:
 *       <em>option table capacity versus count</em>.</li>
 *   <li><strong>Low - no file-status collaborator.</strong> The complete {@code EXEC CICS} verb
 *       inventory of the program is {@code RETURN} at {@code :107}, {@code XCTL} at {@code :152} and
 *       {@code :175}, {@code SEND} at {@code :189} and {@code RECEIVE} at {@code :201}. There is no
 *       {@code READ}, {@code WRITE}, {@code REWRITE}, {@code DELETE}, {@code STARTBR},
 *       {@code READNEXT}, {@code READPREV} or {@code ENDBR} anywhere in it, so there is no
 *       {@code FILE STATUS} path to translate and no status-mapping collaborator is injected. The
 *       corroborating artefact is {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '} at {@code :39},
 *       declared and never referenced. Decision log entry: <em>menu programs perform no
 *       I/O</em>.</li>
 *   <li><strong>Low - the user-type gate is retained although it cannot fire.</strong> See
 *       {@link #processEnterKey(String, UserType)}. Rule 1 Clause B forbids <em>untracked</em> dead
 *       code; this branch is real, reachable code that the frozen data never triggers, and it is
 *       tracked here and in the decision log. No coverage exclusion is added; the test seam covers it.
 *       Decision log entry: <em>retained user-type gate</em>.</li>
 *   <li><strong>Low - the placeholder guard is always true in production.</strong> No program name in
 *       the frozen table begins {@code DUMMY}, so the "coming soon" path cannot be reached with the
 *       shipped data. Retained and covered through the test seam for the same reason as above.
 *       Decision log entry: <em>retained placeholder guard</em>.</li>
 *   <li><strong>Low - two menu beans, no shared base.</strong> This bean and
 *       {@code com.cardemo.service.menu.AdminMenuService} are deliberately independent, because their
 *       source programs are independent and their option tables have different shapes: the admin entry
 *       of {@code app/cpy/COADM02Y.cpy} has no user-type sub-field at all. An abstract base would have
 *       to invent one. Decision log entry: <em>two menu beans retained</em>.</li>
 *   <li><strong>Low - the same caption field is rendered two different ways.</strong>
 *       {@code CDEMO-MENU-OPT-NAME} is transferred {@code DELIMITED BY SPACE} at
 *       {@code app/cbl/COMEN01C.cbl:161}, truncating it, and {@code DELIMITED BY SIZE} at {@code :245},
 *       keeping it whole. Both are reproduced exactly and neither is generalised to the other site.
 *       Decision log entry: <em>DELIMITED BY SPACE versus SIZE</em>.</li>
 *   <li><strong>Low - captured-but-untested response codes.</strong>
 *       {@code RECEIVE-MENU-SCREEN} captures {@code RESP} and {@code RESP2} at
 *       {@code app/cbl/COMEN01C.cbl:205-206} into {@code WS-RESP-CD} and {@code WS-REAS-CD} declared at
 *       {@code :43-44}, and nothing anywhere in the program ever tests them. That absent guard is
 *       preserved rather than improved. Decision log entry: <em>unguarded RECEIVE</em>.</li>
 *   </ul>
 *
 * <h2>7. What this bean deliberately does not do</h2>
 *
 * <ul>
 *   <li>It does not resolve a program name into code. {@code XCTL PROGRAM(...)} at
 *       {@code app/cbl/COMEN01C.cbl:153} dispatched by eight-character program name; here that name is
 *       <strong>inert data</strong> carried out as a route label. There is no reflection, no
 *       {@code Class.forName}, no bean lookup by name, no dynamic proxy and no process execution
 *       anywhere in this file, which is both what Rule 1 Clause D requires and what keeps navigation
 *       URL based as the target architecture specifies.</li>
 *   <li>It holds no session, re-entry or routing state. {@code CDEMO-FROM-TRANID},
 *       {@code CDEMO-FROM-PROGRAM}, {@code CDEMO-TO-TRANID}, {@code CDEMO-TO-PROGRAM},
 *       {@code CDEMO-PGM-CONTEXT} with its {@code CDEMO-PGM-ENTER} and {@code CDEMO-PGM-REENTER}
 *       condition names, {@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET} - declared at
 *       {@code app/cpy/COCOM01Y.cpy:21-24}, {@code :29-31} and {@code :43-44} - have no equivalent.
 *       Neither do the two identity moves at {@code app/cbl/COMEN01C.cbl:149-150}, which are comment
 *       lines in the source; what {@code WS-USER-ID} held is <strong>not available</strong>, since the
 *       name occurs nowhere else in the program.</li>
 *   <li>It renders no screen. The action-identifier dispatch of {@code MAIN-PARA} at
 *       {@code app/cbl/COMEN01C.cbl:93-103} and the {@code EXEC CICS SEND MAP} at {@code :189-194} have
 *       no counterpart; a controller serialises {@link MainMenuScreen} instead. The invalid-key message
 *       {@code CCDA-MSG-INVALID-KEY} of {@code app/cpy/CSMSG01Y.cpy:20-21} therefore has no counterpart
 *       either, because HTTP has no attention identifier to be invalid.</li>
 *   <li>It registers no metric and starts no span. The four counters the target architecture defines
 *       are batch and authentication counters owned by {@code com.cardemo.observability.MetricsConfig};
 *       none is a menu counter. Trace and correlation identifiers reach the log through the mapped
 *       diagnostic context set by {@code com.cardemo.observability.CorrelationIdFilter}, and are not
 *       set here.</li>
 *   <li>It logs no credential, token, personally identifiable value or card number. It never receives
 *       one: its whole input is a two-character option string and a resolved
 *       {@code UserType}.</li>
 *   </ul>
 *
 * @see #getMainMenu(UserType)
 * @see #selectOption(String, UserType)
 */
@Service
public class MainMenuService {

    /**
     * Structured logger for this bean. Every statement uses parameterised placeholders rather than
     * concatenation, so no option value is interpolated into a log line eagerly.
     */
    private static final Logger LOG = LoggerFactory.getLogger(MainMenuService.class);

    /**
     * {@code WS-PGMNAME PIC X(08) VALUE 'COMEN01C'} at {@code app/cbl/COMEN01C.cbl:36}.
     */
    static final String PROGRAM_NAME = "COMEN01C";

    /**
     * {@code WS-TRANID PIC X(04) VALUE 'CM00'} at {@code app/cbl/COMEN01C.cbl:37}, the CICS transaction
     * identifier that {@code app/csd/CARDDEMO.CSD:399-400} binds to {@link #PROGRAM_NAME}.
     */
    static final String TRANSACTION_ID = "CM00";

    /**
     * {@code 'COSGN00C'}, the sign-on program the menu returns to.
     */
    static final String SIGN_ON_PROGRAM = "COSGN00C";

    /**
     * The rejection text for an unusable option, byte exact from {@code app/cbl/COMEN01C.cbl:131}: thirty-seven
     * characters, three trailing full stops and no trailing space.
     */
    static final String INVALID_OPTION_MESSAGE = "Please enter a valid option number...";

    /**
     * The rejection text for an administrator-only option selected by a standard user, byte exact from
     * {@code app/cbl/COMEN01C.cbl:140}: thirty-three characters, and <strong>the trailing space is inside the
     * literal</strong>, after the three full stops. It is not trimmed.
     */
    static final String ADMIN_ONLY_MESSAGE = "No access - Admin Only option... ";

    /**
     * The opening fragment of the placeholder notice, {@code 'This option '} at
     * {@code app/cbl/COMEN01C.cbl:159}, transferred {@code DELIMITED BY SIZE}. Its trailing space is the only
     * separator in the assembled message.
     */
    static final String COMING_SOON_PREFIX = "This option ";

    /**
     * The closing fragment of the placeholder notice, {@code 'is coming soon ...'} at
     * {@code app/cbl/COMEN01C.cbl:162}, transferred {@code DELIMITED BY SIZE}. It carries no leading space,
     * which is why the assembled message has none; see {@link #truncateAtFirstSpace(String)}.
     */
    static final String COMING_SOON_SUFFIX = "is coming soon ...";

    /**
     * The literal {@code '. '} that separates the option number from its caption on a rendered menu line,
     * transferred {@code DELIMITED BY SIZE} at {@code app/cbl/COMEN01C.cbl:244}. Two characters, a full stop
     * and a space.
     */
    static final String OPTION_NUMBER_SEPARATOR = ". ";

    /**
     * The five-character placeholder marker of the guard
     * {@code IF CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'} at {@code app/cbl/COMEN01C.cbl:146}. The
     * comparison is on the first five bytes of the eight-byte program name, not on the whole name, and it is
     * case sensitive.
     */
    static final String PLACEHOLDER_PROGRAM_PREFIX = "DUMMY";

    /**
     * The administrator-only gate byte, {@code 'A'}, from the test
     * {@code CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'} at {@code app/cbl/COMEN01C.cbl:137}. It is the same
     * one-byte domain as {@code CDEMO-USRTYP-ADMIN VALUE 'A'} at {@code app/cpy/COCOM01Y.cpy:27}, and the
     * comparison is exact: no case folding occurs anywhere in the source or here.
     */
    static final char ADMIN_ONLY_OPTION_CODE = 'A';

    /**
     * The declared width of the option input field, {@code OPTIONI PIC X(2)} at
     * {@code app/cpy-bms/COMEN01.CPY:132}, which is also the width of {@code WS-OPTION-X PIC X(02) JUST RIGHT}
     * at {@code app/cbl/COMEN01C.cbl:45} and of {@code WS-OPTION PIC 9(02)} at {@code :46}.
     */
    static final int OPTION_INPUT_LENGTH = 2;

    /**
     * The identifier reported as the offending input on every rejection this bean raises, chosen to match the
     * source field name {@code OPTIONI} of {@code app/cpy-bms/COMEN01.CPY:132} in lower case.
     */
    private static final String OPTION_FIELD_NAME = "option";

    /**
     * {@code CCDA-TITLE01 PIC X(40)} of {@code app/cpy/COTTL01Y.cpy:18-19}, moved into the screen header at
     * {@code app/cbl/COMEN01C.cbl:216}. Carried at its declared forty-character width, leading and trailing
     * padding included, because the padding is what centred it on the 3270 screen.
     */
    private static final String SCREEN_TITLE_01 = "      AWS Mainframe Modernization       ";

    /**
     * {@code CCDA-TITLE02 PIC X(40)} of {@code app/cpy/COTTL01Y.cpy:20-22}, moved into the screen header at
     * {@code app/cbl/COMEN01C.cbl:217}.
     */
    private static final String SCREEN_TITLE_02 = "              CardDemo                  ";

    /**
     * Renders the header date exactly as {@code WS-CURDATE-MM-DD-YY} does. {@code CURDATEI PIC X(8)} at
     * {@code app/cpy-bms/COMEN01.CPY:36}.
     */
    private static final DateTimeFormatter HEADER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);

    /**
     * Renders the header time exactly as {@code WS-CURTIME-HH-MM-SS} does: {@code WS-CURTIME-HH PIC 9(02)},
     * {@code FILLER VALUE ':'}, {@code WS-CURTIME-MM PIC 9(02)}, {@code FILLER VALUE ':'},
     * {@code WS-CURTIME-SS PIC 9(02)} in {@code app/cpy/CSDAT01Y.cpy} - eight bytes, matching
     * {@code CURTIMEI PIC X(8)} at {@code app/cpy-bms/COMEN01.CPY:54}.
     */
    private static final DateTimeFormatter HEADER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /**
     * The ten main-menu options of {@code CDEMO-MENU-OPTIONS-DATA} at {@code app/cpy/COMEN02Y.cpy:25-84}, in
     * copybook order, as the production table.
     */
    private static final List<MenuResponse.MainMenuOption> CANONICAL_MENU_OPTIONS =
            MenuResponse.MAIN_MENU_OPTIONS;

    /**
     * The clock the header furniture is read from, standing in for
     * {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at {@code app/cbl/COMEN01C.cbl:214}.
     *
     * <p>{@code FUNCTION CURRENT-DATE} returns the <em>local</em> date and time of the system the program
     * runs on, so a clock in the system default zone is the parity-preserving default - see
     * {@link #MainMenuService()}. It is held as an injected collaborator rather than read through a static
     * {@code now()} call so that the dependence on the ambient zone is explicit at one place instead of
     * implicit at the point of use, and so that the header rendering of {@link #populateHeaderInfo()} is
     * deterministic under test. Immutable, never null, and never reassigned; a {@code Clock} is itself
     * immutable and thread safe, so sharing this bean between request threads remains safe.</p>
     */
    private final Clock clock;

    /**
     * The option table this instance resolves selections against: the analogue of
     * {@code CDEMO-MENU-OPTIONS} at {@code app/cpy/COMEN02Y.cpy:87-92}.
     *
     * <p>Always {@link #CANONICAL_MENU_OPTIONS} in production. Immutable, never null, and never mutated
     * after construction, so this bean is safe to share between request threads. Its size is the
     * analogue of {@code CDEMO-MENU-OPT-COUNT} and is the bound the option validation of
     * {@code app/cbl/COMEN01C.cbl:128} compares against.</p>
     */
    private final List<MenuResponse.MainMenuOption> menuOptions;

    /**
     * Creates the production bean: the ten canonical options of {@code app/cpy/COMEN02Y.cpy:25-84} and a
     * clock in the system default zone.
     *
     * <p>This is the constructor Spring uses. It takes <strong>no argument</strong> because the bean has
     * <strong>nothing to inject</strong>: the source program performs no I/O - its complete
     * {@code EXEC CICS} verb inventory is {@code RETURN}, {@code XCTL}, {@code SEND} and {@code RECEIVE} -
     * so there is no repository, no file-status mapper and no other service to contribute, and its option
     * table is a compile-time constant rather than configuration. Because no constructor here is annotated
     * for injection and a no-argument constructor exists, the container deterministically selects this
     * one, and the bean can never fail to wire for want of a contributed dependency.</p>
     *
     * <p><strong>The one collaborator is defaulted here rather than contributed.</strong>
     * {@link Clock#systemDefaultZone()} is chosen, not {@link Clock#systemUTC()}, because
     * {@code FUNCTION CURRENT-DATE} at {@code app/cbl/COMEN01C.cbl:214} returns the local date and time of
     * the system the program runs on. Reading it in any other zone would render a header the source could
     * not have rendered, so the system default zone is the parity-preserving choice; deployments that need
     * a different zone set it for the process rather than for this bean.</p>
     *
     * <p>Side effects: none. It performs no I/O and calls no overridable instance method, so no
     * partially initialised reference can escape.</p>
     */
    public MainMenuService() {
        this(Clock.systemDefaultZone(), CANONICAL_MENU_OPTIONS);
    }

    /**
     * Test seam. Creates the bean over a caller-supplied clock and option table.
     *
     * <p><strong>This constructor exists solely so that unit tests can reach what production cannot, and
     * production never uses it.</strong> It is package-private for that reason and is not dead code:</p>
     *
     * <ul>
     *   <li><strong>The two retained parity branches.</strong> All ten entries of
     *       {@code app/cpy/COMEN02Y.cpy:25-84} carry the gate byte {@code 'U'} and no program name in that
     *       table begins {@code DUMMY}, so the administrator-only rejection of
     *       {@code app/cbl/COMEN01C.cbl:136-143} and the placeholder notice of {@code :157-164} are
     *       unreachable against the canonical data. Both are real, reachable code that must keep working,
     *       Rule 1 Clause B forbids untracked dead code rather than tracked parity code, and no coverage
     *       exclusion is permitted - so a same-package test supplies a table containing an
     *       {@code 'A'}-gated entry and an entry whose program name begins {@code DUMMY}, and exercises
     *       them through this seam.</li>
     *   <li><strong>A fixed clock,</strong> which makes the header furniture of
     *       {@link #populateHeaderInfo()} assertable instead of varying with wall-clock time. The unit
     *       tier's {@code FixedClockProvider} fixture supplies one; a moving clock would make the rendered
     *       date and time unassertable and would cluster failures on second, day and month boundaries.</li>
     * </ul>
     *
     * <p>Validation is deliberate and immediate, and every message names the offending argument. The
     * upper bound is the ten options {@code app/cpy/COMEN02Y.cpy:21} populates, which is also the bound
     * {@code MenuResponse.ofMainMenu(List)} enforces; failing here rather than later keeps the
     * diagnostic at the point of the mistake. An empty table is permitted and means a menu offering
     * nothing, in which case every selection is rejected by the option validation.</p>
     *
     * <p>Side effects: none. The supplied list is copied, so a later mutation by the caller cannot reach
     * this instance. Nothing is logged and the clock is not read during construction.</p>
     *
     * @param clock       the clock the header date and time are read from; must not be null. Production
     *                    passes {@link Clock#systemDefaultZone()} through {@link #MainMenuService()},
     *                    because {@code FUNCTION CURRENT-DATE} at {@code app/cbl/COMEN01C.cbl:214} reads
     *                    local time; a test passes a fixed clock
     * @param menuOptions the option table to resolve selections against, in menu order; must not be
     *                    null, must not contain a null element, and must hold no more than the ten
     *                    entries {@code app/cpy/COMEN02Y.cpy:21} populates
     * @throws IllegalArgumentException if {@code clock} is null, or if {@code menuOptions} is null,
     *                                  contains a null element, or holds more than ten entries
     */
    MainMenuService(final Clock clock, final List<MenuResponse.MainMenuOption> menuOptions) {

        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        if (menuOptions == null) {
            throw new IllegalArgumentException("menuOptions must not be null; supply an empty list to "
                    + "represent a menu offering no options");
        }

        final int populatedOptionCount = MenuResponse.MenuType.MAIN.getPopulatedOptionCount();
        if (menuOptions.size() > populatedOptionCount) {
            throw new IllegalArgumentException("menuOptions holds " + menuOptions.size()
                    + " entries, which exceeds the " + populatedOptionCount
                    + " options populated by app/cpy/COMEN02Y.cpy:21; the OCCURS capacity of "
                    + MenuResponse.MenuType.MAIN.getDeclaredCapacity()
                    + " is not a valid bound because its spare subscripts are unpopulated");
        }

        // Pre-sized to the exact final length: the check above proves the length is bounded by the ten
        // options the copybook populates, so the reservation cannot exceed the source table.
        final List<MenuResponse.MainMenuOption> defensiveCopy = new ArrayList<>(menuOptions.size());
        for (final MenuResponse.MainMenuOption option : menuOptions) {
            if (option == null) {
                // defensiveCopy.size() is the index within menuOptions of the element being rejected.
                throw new IllegalArgumentException("menuOptions must not contain a null element, but "
                        + "index " + defensiveCopy.size() + " was null");
            }
            defensiveCopy.add(option);
        }

        this.clock = clock;
        this.menuOptions = List.copyOf(defensiveCopy);
    }

    /**
     * Returns the main menu a signed-on user may see.
     *
     * <p>Counterpart of the first-entry branch of {@code MAIN-PARA} at
     * {@code app/cbl/COMEN01C.cbl:87-90}: when the pseudo-conversational re-entry flag was not yet set,
     * the source set it, cleared the output map and performed {@code SEND-MENU-SCREEN}. A stateless
     * request is always a first entry, so this operation always sends.</p>
     *
     * <p>The returned value carries the option payload, the rendered option lines exactly as
     * {@code BUILD-MENU-OPTIONS} composed them, and the route the operator leaves the menu by. It
     * carries no header furniture; see {@link #populateHeaderInfo()} for why.</p>
     *
     * <p>Side effects: none. No I/O, no persistence, no messaging, no mutation of this bean or of any
     * static state. Repeated calls with the same argument return equal values, except that the header
     * diagnostics logged at {@code DEBUG} level carry the date and time read from the injected
     * {@link Clock} - which is why that clock is a constructor argument rather than an ambient
     * {@code now()} call, and why those diagnostics are reproducible under a fixed clock.</p>
     *
     * @param userType the signed-on user's class, resolved upstream from the role claim that replaces
     * {@code CDEMO-USER-TYPE PIC X(01)} of {@code app/cpy/COCOM01Y.cpy:26}.
     * @return the menu screen for {@code userType}.
     * @throws IllegalArgumentException if {@code userType} is null, which means the caller reached this bean
     * without a resolved identity
     */
    public MainMenuScreen getMainMenu(final UserType userType) {

        final UserType resolvedUserType = mainPara(userType);

        // app/cbl/COMEN01C.cbl:87-89 IF NOT CDEMO-PGM-REENTER / SET CDEMO-PGM-REENTER TO TRUE / MOVE
        // LOW-VALUES TO COMEN1AO. Neither has a counterpart: CDEMO-PGM-CONTEXT and its two condition
        // names at app/cpy/COCOM01Y.cpy:29-31 are pseudo-conversational session state, which a stateless
        // request does not carry, and there is no output map to blank. Every call is a first entry.
        return sendMenuScreen(resolvedUserType);
    }

    /**
     * Resolves the option an operator typed into either a navigation target or the legacy placeholder notice,
     * and rejects an option that is unusable or forbidden.
     *
     * @param optionInput the option exactly as received, which the source read into {@code OPTIONI PIC X(2)} of
     * {@code app/cpy-bms/COMEN01.CPY:132}.
     * @param userType the signed-on user's class.
     * @return the resolved selection: for a real target, the option and its route with an empty message.
     * @throws ValidationException with the byte-exact message of {@code app/cbl/COMEN01C.cbl:131} when the
     * option is non-numeric, greater than the option count, zero, or too long for the two-byte field.
     * @throws IllegalArgumentException if {@code userType} is null
     */
    public MenuSelection selectOption(final String optionInput, final UserType userType) {

        final UserType resolvedUserType = mainPara(userType);

        // app/cbl/COMEN01C.cbl:92 PERFORM RECEIVE-MENU-SCREEN, then :94-95 WHEN DFHENTER PERFORM
        // PROCESS-ENTER-KEY. The order is the source's own and is preserved.
        final String receivedOption = receiveMenuScreen(optionInput);

        return processEnterKey(receivedOption, resolvedUserType);
    }

    /**
     * {@code MAIN-PARA} of {@code app/cbl/COMEN01C.cbl:75}, lines 75 to 110: establishes the request context
     * that the rest of the transaction runs in.
     *
     * @param userType the user class supplied by the caller, which may be null
     * @return the same value, once it is known to be resolved, so that callers use the validated reference
     * rather than the argument
     * @throws IllegalArgumentException if {@code userType} is null
     */
    private UserType mainPara(final UserType userType) {

        requireResolvedUserType(userType);

        LOG.debug("Accepted main menu request for transaction {} program {} with user type {}",
                TRANSACTION_ID, PROGRAM_NAME, userType);

        return userType;
    }

    /**
     * {@code PROCESS-ENTER-KEY} of {@code app/cbl/COMEN01C.cbl:115}, lines 115 to 165: normalises the typed
     * option, validates it, applies the eligibility gate, and resolves the selection.
     *
     * <p><strong>Normalisation, {@code :117-124}.</strong> The source scans {@code OPTIONI} backwards
     * from its last byte for the last non-space, moves that prefix into
     * {@code WS-OPTION-X PIC X(02) JUST RIGHT} - which right-justifies it within two bytes - replaces
     * every remaining space with {@code '0'} by {@code INSPECT ... REPLACING}, and moves the result into
     * {@code WS-OPTION PIC 9(02)}. So a blank field becomes {@code "00"}, {@code "5"} becomes
     * {@code "05"} and {@code "12"} stays {@code "12"}. <strong>That chain is the explicit null and empty
     * handling for this input</strong>: an absent option arrives as spaces from
     * {@link #receiveMenuScreen(String)}, normalises to {@code "00"} and is then rejected by the
     * {@code = ZEROS} disjunct below. No separate blank test is added ahead of it, because adding one
     * would report a different outcome than the source does.</p>
     *
     * <p>{@code :125 MOVE WS-OPTION TO OPTIONO OF COMEN1AO} echoed the normalised value back to the
     * screen. Its counterpart is {@link MenuSelection#optionNumber()}.</p>
     *
     * <p><strong>Validation, {@code :127-134}.</strong> Three disjuncts in the source's order:
     * {@code WS-OPTION IS NOT NUMERIC}, then {@code WS-OPTION &gt; CDEMO-MENU-OPT-COUNT}, then
     * {@code WS-OPTION = ZEROS}. Any of them yields the byte-exact literal of {@code :131}. All three
     * report the same text because the source reports the same text; unlike the account-update program,
     * this one has no separate marker for a blank field.</p>
     *
     * <p>A fourth condition has no source counterpart and is stated as such: an option string longer
     * than the two bytes {@code OPTIONI PIC X(2)} declares. A 3270 field cannot deliver more characters
     * than it declares, so <strong>what the source would do with a longer value is not
     * available</strong>. Silently keeping the first two characters would accept input the source cannot
     * represent - {@code "105"} would become the valid option {@code "10"} and dispatch to bill payment -
     * so an over-long value is rejected with the same literal the source uses for every other unusable
     * option. No new outcome is introduced.</p>
     *
     * <p><strong>Deviation, severity Medium - the rejection short-circuits.</strong> The source has no
     * early exit here: {@code :134} ends the {@code IF} and control falls straight through the blank
     * {@code :135} into the gate at {@code :136-137}, which subscripts
     * {@code CDEMO-MENU-OPT-USRTYPE(WS-OPTION)} with the value that just failed - zero, eleven, twelve,
     * or a non-numeric byte. Because {@code app/cpy/COMEN02Y.cpy:88} overlays twelve entries on ten, that
     * read reaches unrelated {@code WORKING-STORAGE}. Java is memory safe, so the overread cannot be
     * reproduced: an out-of-range index throws instead of returning adjacent storage. The rejection is
     * therefore raised before the gate can evaluate the subscript. <strong>The observable outcome is
     * unchanged</strong>, because both source paths perform {@code SEND-MENU-SCREEN} and the operator
     * sees the message either way. This hazard is unique to this program: the corresponding subscript in
     * {@code app/cbl/COADM01C.cbl:138} sits inside {@code IF NOT ERR-FLG-ON} at {@code :137} and is
     * structurally safe. Decision log entry: <em>bounds short-circuit</em>.</p>
     *
     * <p><strong>Eligibility gate, {@code :136-143}.</strong>
     * {@code IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'} yields the byte-exact
     * literal of {@code :140}, whose trailing space is inside the literal. The redundant
     * {@code MOVE SPACES TO WS-MESSAGE} at {@code :139} immediately before that move clears a field the
     * next statement overwrites; it has no counterpart and the sequence is not otherwise rearranged. The
     * gate is evaluated against the <strong>whole</strong> option table, exactly as the source subscripts
     * the whole table, so an option withheld from the displayed list by
     * {@link #sendMenuScreen(UserType)} is still rejected here by name.</p>
     *
     * <p><strong>Retained although it cannot fire.</strong> All ten entries of
     * {@code app/cpy/COMEN02Y.cpy:25-84} carry {@code 'U'}, so no selection can satisfy the second
     * conjunct against the frozen table. The branch is nonetheless real, reachable code and is kept
     * verbatim, with the test seam {@link #MainMenuService(Clock, List)} covering it. Decision log entry:
     * <em>retained user-type gate</em>.</p>
     *
     * <p><strong>Dispatch and the placeholder guard, {@code :145-165}.</strong> {@code :145 IF NOT
     * ERR-FLG-ON} is satisfied by construction here, because both rejections above have already thrown.
     * {@code :146} then compares the first five bytes of the eight-byte program name against
     * {@code 'DUMMY'}; a real target is dispatched by {@code EXEC CICS XCTL} at {@code :152-155} and a
     * placeholder is not.</p>
     *
     * <p><strong>Deviation, Blocker class if mis-implemented - the transfer never returns.</strong> The
     * inner {@code END-IF} closes at {@code :156}, so the notice assembly at {@code :157-163} and the
     * {@code SEND} at {@code :164} sit <em>after</em> the dispatch. Under CICS that is unreachable
     * whenever a real target resolves, because {@code XCTL} transfers control and never comes back. A
     * Java call does come back, so this method <strong>returns immediately</strong> once a real target is
     * resolved. Without that guard every valid selection would answer "coming soon". Decision log entry:
     * <em>XCTL fall-through guard</em>.</p>
     *
     * <p>{@code :147-148} stamp {@code CDEMO-FROM-TRANID} and {@code CDEMO-FROM-PROGRAM} and {@code :151}
     * zeroes {@code CDEMO-PGM-CONTEXT}; none has a counterpart, all three being routing state. Lines
     * {@code :149-150} are comment lines in the source and are not translated; what {@code WS-USER-ID}
     * held is <strong>not available</strong>, as that name occurs nowhere else in the program.
     * {@code :158 MOVE DFHGREEN TO ERRMSGC} has no counterpart either, but it is the evidence that the
     * notice is informational rather than an error.</p>
     *
     * @param receivedOption the option as returned by {@link #receiveMenuScreen(String)}: never null,
     *                       possibly empty, possibly space padded
     * @param userType       the resolved user class, never null
     * @return the resolved selection, never null
     * @throws ValidationException if the option is unusable or is gated to administrators
     */
    private MenuSelection processEnterKey(final String receivedOption, final UserType userType) {

        // app/cbl/COMEN01C.cbl:117-124. A 3270 cannot deliver more bytes than OPTIONI PIC X(2) declares,
        // so a longer value has no source behaviour at all and is rejected with the same literal every
        // other unusable option produces. Keeping only the first two characters would accept "105" as
        // option 10, which the source can never do.
        if (receivedOption.length() > OPTION_INPUT_LENGTH) {
            throw ValidationException.invalidField(OPTION_FIELD_NAME, INVALID_OPTION_MESSAGE);
        }

        // The two-byte receiving field itself: MOVE into OPTIONI PIC X(2) is left justified and space
        // filled, so a shorter value occupies the leading bytes and the remainder holds spaces.
        final StringBuilder optionField = new StringBuilder(OPTION_INPUT_LENGTH);
        optionField.append(receivedOption);
        while (optionField.length() < OPTION_INPUT_LENGTH) {
            optionField.append(' ');
        }

        // :117-121 PERFORM VARYING WS-IDX FROM LENGTH OF OPTIONI BY -1 UNTIL the byte at WS-IDX is not a
        // space OR WS-IDX = 1. The body is empty; the loop exists only to leave WS-IDX on the last
        // non-space byte, or on byte one when the field is entirely spaces.
        int wsIdx = OPTION_INPUT_LENGTH;
        while (optionField.charAt(wsIdx - 1) == ' ' && wsIdx != 1) {
            wsIdx = wsIdx - 1;
        }

        // :122 MOVE OPTIONI(1:WS-IDX) TO WS-OPTION-X, whose PIC X(02) JUST RIGHT right-justifies the
        // prefix within two bytes, then :123 INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'.
        final String typedPrefix = optionField.substring(0, wsIdx);
        final StringBuilder wsOptionX = new StringBuilder(OPTION_INPUT_LENGTH);
        while (wsOptionX.length() + typedPrefix.length() < OPTION_INPUT_LENGTH) {
            wsOptionX.append('0');
        }
        for (int position = 0; position < typedPrefix.length(); position++) {
            final char typed = typedPrefix.charAt(position);
            wsOptionX.append(typed == ' ' ? '0' : typed);
        }

        // :124 MOVE WS-OPTION-X TO WS-OPTION PIC 9(02). A byte that is not a digit survives the INSPECT
        // and is what the IS NOT NUMERIC test at :127 detects.
        final String wsOption = wsOptionX.toString();
        final boolean numeric = isTwoDigitNumber(wsOption);
        final int selectedOptionNumber = numeric ? Integer.parseInt(wsOption) : 0;

        // :127-134 the three disjuncts, in the source's order, all reporting the literal of :131.
        // :132-133 PERFORM SEND-MENU-SCREEN - the operator saw the menu again carrying the message; the
        // thrown exception carries the same message to the caller.
        final int menuOptionCount = this.menuOptions.size();
        if (!numeric || selectedOptionNumber > menuOptionCount || selectedOptionNumber == 0) {
            // Throwing here replaces the source's fall-through into the gate subscript at :136-137, which
            // would subscript CDEMO-MENU-OPT-USRTYPE with the value that just failed. Java is memory safe,
            // so that overread cannot be reproduced; both paths reach SEND-MENU-SCREEN, so the operator
            // sees the same message either way.
            throw ValidationException.invalidField(OPTION_FIELD_NAME, INVALID_OPTION_MESSAGE);
        }

        // :136-137 the subscript, now provably in range because every rejecting condition has thrown.
        final MenuResponse.MainMenuOption selectedOption = this.menuOptions.get(selectedOptionNumber - 1);

        // :136-143 retained verbatim although the frozen table can never satisfy the second conjunct:
        // all ten entries carry 'U'. Kept because it is real, reachable code and because a table value
        // of 'A' must behave exactly as the source made it behave. Owed an entry in the planned DECISION_LOG.md under
        // "retained user-type gate"; covered through the package-private test seam, never by a coverage
        // exclusion. The comparison is exact - CDEMO-USRTYP-USER is the byte 'U' and the gate byte is
        // compared with 'A' - so no case folding is performed on either side.
        if (userType == UserType.USER && isAdminOnlyOption(selectedOption)) {
            throw ValidationException.invalidField(OPTION_FIELD_NAME, ADMIN_ONLY_MESSAGE);
        }

        // :146 IF CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'. The eight-byte program name is
        // inert data: it is carried out as the target route label and is never resolved into code.
        final String targetProgram = selectedOption.programName();
        final boolean placeholder = targetProgram.startsWith(PLACEHOLDER_PROGRAM_PREFIX);

        if (!placeholder) {
            // :152-155 EXEC CICS XCTL transfers control and never returns, so :157-164 is unreachable on this path.
            // Returning here is what reproduces that. Removing this return would answer "coming soon" for every valid
            // selection. the planned DECISION_LOG.md entry \"XCTL fall-through guard".
            LOG.debug("Main menu option {} of {} resolved to target program {}",
                    selectedOptionNumber, menuOptionCount, targetProgram);
            return new MenuSelection(selectedOption.optionNumber(), selectedOption.optionName(),
                    targetProgram, false, "");
        }

        // :157-163 MOVE SPACES TO WS-MESSAGE, then STRING 'This option ' DELIMITED BY SIZE,
        // CDEMO-MENU-OPT-NAME(WS-OPTION) DELIMITED BY SPACE, 'is coming soon ...' DELIMITED BY SIZE.
        // The middle transfer stops at the caption's first space and no separator is inserted, which is
        // why the assembled text reads "This option Accountis coming soon ...". :164 PERFORM
        // SEND-MENU-SCREEN then redisplayed the menu carrying it.
        final String comingSoonMessage = COMING_SOON_PREFIX
                + truncateAtFirstSpace(selectedOption.optionName())
                + COMING_SOON_SUFFIX;

        LOG.debug("Main menu option {} of {} is a placeholder targeting {}; returning the coming soon "
                + "notice", selectedOptionNumber, menuOptionCount, targetProgram);

        return new MenuSelection(selectedOption.optionNumber(), selectedOption.optionName(),
                targetProgram, true, comingSoonMessage);
    }

    /**
     * {@code RETURN-TO-SIGNON-SCREEN} of {@code app/cbl/COMEN01C.cbl:170}, lines 170 to 177: resolves the
     * program the menu hands control back to.
     *
     * @return {@code "COSGN00C"}, never null
     */
    private String returnToSignonScreen() {
        return SIGN_ON_PROGRAM;
    }

    /**
     * {@code SEND-MENU-SCREEN} of {@code app/cbl/COMEN01C.cbl:182}, lines 182 to 194: composes the menu the
     * operator sees.
     *
     * @param userType the resolved user class, never null
     * @return the composed screen, never null
     */
    private MainMenuScreen sendMenuScreen(final UserType userType) {

        // The Clause D least-privilege filter documented above. Pre-sized to the full table, which is the
        // largest the result can be.
        final List<MenuResponse.MainMenuOption> permittedOptions =
                new ArrayList<>(this.menuOptions.size());
        for (final MenuResponse.MainMenuOption option : this.menuOptions) {
            if (userType == UserType.USER && isAdminOnlyOption(option)) {
                continue;
            }
            permittedOptions.add(option);
        }

        // :184 PERFORM POPULATE-HEADER-INFO, then :185 PERFORM BUILD-MENU-OPTIONS. Order preserved.
        populateHeaderInfo();
        final List<String> optionLines = buildMenuOptions(permittedOptions);

        // :189-194 EXEC CICS SEND MAP - no counterpart; the composed value is returned instead.
        return new MainMenuScreen(MenuResponse.ofMainMenu(permittedOptions), optionLines,
                returnToSignonScreen());
    }

    /**
     * {@code RECEIVE-MENU-SCREEN} of {@code app/cbl/COMEN01C.cbl:199}, lines 199 to 207: takes the option the
     * operator typed.
     *
     * @param optionInput the option exactly as received, which may be null
     * @return the option as the terminal input area would have held it.
     */
    private String receiveMenuScreen(final String optionInput) {
        return optionInput == null ? "" : optionInput;
    }

    /**
     * {@code POPULATE-HEADER-INFO} of {@code app/cbl/COMEN01C.cbl:212}, lines 212 to 231: composes the six
     * fields of the screen header.
     *
     * <p>Retained one to one. The source takes {@code FUNCTION CURRENT-DATE} into
     * {@code WS-CURDATE-DATA} at {@code :214}, moves {@code CCDA-TITLE01} and {@code CCDA-TITLE02} of
     * {@code app/cpy/COTTL01Y.cpy:18-22} into the two title fields at {@code :216-217}, moves
     * {@code WS-TRANID} and {@code WS-PGMNAME} into the transaction and program fields at
     * {@code :218-219}, assembles {@code MM/DD/YY} from the month, the day and
     * {@code WS-CURDATE-YEAR(3:2)} at {@code :221-225}, and assembles {@code HH:MM:SS} from the hours,
     * minutes and seconds at {@code :227-231}.</p>
     *
     * <p><strong>All six header fields are screen furniture with no counterpart in the returned
     * value.</strong> {@code TRNNAMEI X(4)}, {@code TITLE01I X(40)}, {@code CURDATEI X(8)},
     * {@code PGMNAMEI X(8)}, {@code TITLE02I X(40)} and {@code CURTIMEI X(8)} - declared at
     * {@code app/cpy-bms/COMEN01.CPY:24}, {@code :30}, {@code :36}, {@code :42}, {@code :48} and
     * {@code :54} - identified the running transaction on a 3270 screen. An HTTP response identifies
     * itself by its route and its own timestamp, so none of the six is published. The paragraph is
     * nonetheless retained rather than stubbed out, and its computed values are emitted at {@code DEBUG}
     * level: that keeps the header observable for support without inventing a payload field, and it is
     * new capability of the kind Rule 1 Clause A asks for, the source having had no instrumentation
     * beyond {@code DISPLAY}.</p>
     *
     * <p>The rendered date and time are byte exact. The separators are an oblique stroke and a colon, as
     * the {@code FILLER} items of {@code app/cpy/CSDAT01Y.cpy} declare - {@code MM-DD-YY} and
     * {@code HH-MM-SS} are the COBOL data names, not the rendered forms - and each renders to the eight
     * characters its screen field declares. The two formatters pass {@code Locale.ROOT} so that neither
     * the digits nor the separators can vary with the platform default locale.</p>
     *
     * <p><strong>The time source is the injected {@link Clock}, never an ambient {@code now()}.</strong>
     * {@code FUNCTION CURRENT-DATE} at {@code :214} reads the local date and time of the system the program
     * runs on, so production supplies {@link Clock#systemDefaultZone()} and the rendering is byte for byte
     * what the source would have produced. Taking the clock as a collaborator rather than calling
     * {@code LocalDateTime.now()} is what makes this paragraph deterministic: a test drives it from a fixed
     * clock and asserts the eight rendered characters of each field, which is impossible against the wall
     * clock and would otherwise fail intermittently on second, day and month boundaries. It also puts the
     * dependence on the ambient zone in one declared place - the field - instead of leaving it implicit
     * here. Rule 1 Clause A, determinism and measurable behaviour.</p>
     *
     * <p>Side effects: log output only. Nothing is stored and nothing is returned.</p>
     */
    private void populateHeaderInfo() {

        // :214 MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA. One reading of the injected clock feeds both
        // fields, exactly as the single intrinsic call fed both groups, so the date and the time can never
        // straddle midnight relative to one another. Read through this.clock rather than LocalDateTime.now()
        // so the rendering is deterministic under test; the clock's zone carries the local-time semantics
        // of FUNCTION CURRENT-DATE.
        final LocalDateTime headerTimestamp = LocalDateTime.now(this.clock);

        // :221-225 WS-CURDATE-MM-DD-YY and :227-231 WS-CURTIME-HH-MM-SS. Formatted unconditionally rather
        // than behind an isDebugEnabled test: two formatter calls are negligible beside an HTTP round
        // trip, and the unconditional form keeps this paragraph's execution independent of the configured
        // log level.
        final String headerDate = HEADER_DATE_FORMAT.format(headerTimestamp);
        final String headerTime = HEADER_TIME_FORMAT.format(headerTimestamp);

        // :216-219 the four literal header fields, and the two assembled ones.
        LOG.debug("Main menu header: transaction={} program={} title01={} title02={} date={} time={}",
                TRANSACTION_ID, PROGRAM_NAME, SCREEN_TITLE_01, SCREEN_TITLE_02, headerDate, headerTime);
    }

    /**
     * {@code BUILD-MENU-OPTIONS} of {@code app/cbl/COMEN01C.cbl:236}, lines 236 to 277: renders one display
     * line per option.
     *
     * @param options the options to render, in menu order.
     * @return one line per option, each exactly forty characters wide, in the order supplied.
     */
    private List<String> buildMenuOptions(final List<MenuResponse.MainMenuOption> options) {

        final List<String> optionLines = new ArrayList<>(options.size());

        // :238-239 PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > CDEMO-MENU-OPT-COUNT.
        for (final MenuResponse.MainMenuOption option : options) {

            // :241 MOVE SPACES TO WS-MENU-OPT-TXT. A fresh buffer per line reproduces the blanking, so no
            // residue of the previous line can survive into this one.
            final StringBuilder menuOptTxt =
                    new StringBuilder(MenuResponse.SCREEN_OPTION_SLOT_LENGTH);

            // :243 CDEMO-MENU-OPT-NUM(WS-IDX) DELIMITED BY SIZE, PIC 9(02) and therefore zero padded.
            // Locale.ROOT keeps the digits Latin whatever the platform default locale is.
            menuOptTxt.append(String.format(Locale.ROOT, "%02d", option.optionNumber()));

            // :244 '. ' DELIMITED BY SIZE, and :245 the whole PIC X(35) caption, padding included.
            menuOptTxt.append(OPTION_NUMBER_SEPARATOR);
            menuOptTxt.append(padOptionName(option.optionName()));

            // :246 INTO WS-MENU-OPT-TXT PIC X(40): the blanking at :241 leaves any byte the STRING did not
            // reach as a space, and STRING stops when the receiving field is full. Padding then trimming
            // to the declared slot width reproduces both halves of that in one place.
            while (menuOptTxt.length() < MenuResponse.SCREEN_OPTION_SLOT_LENGTH) {
                menuOptTxt.append(' ');
            }
            menuOptTxt.setLength(MenuResponse.SCREEN_OPTION_SLOT_LENGTH);

            optionLines.add(menuOptTxt.toString());
        }

        return List.copyOf(optionLines);
    }

    /**
     * Rejects an unresolved identity. <strong>Not a paragraph counterpart</strong>: it is the Java expression
     * of the precondition that {@code IF EIBCALEN = 0} at {@code app/cbl/COMEN01C.cbl:82} tested, extracted so
     * that both public operations state it once and identically.
     *
     * @param userType the value to check, which may be null
     * @throws IllegalArgumentException if {@code userType} is null
     */
    private static void requireResolvedUserType(final UserType userType) {
        if (userType == null) {
            throw new IllegalArgumentException("userType must not be null; the signed-on user class is "
                    + "resolved from the role claim that replaces CDEMO-USER-TYPE of "
                    + "app/cpy/COCOM01Y.cpy:26 and must be supplied by the caller");
        }
    }

    /**
     * Reports whether an option is gated to administrators. <strong>Not a paragraph counterpart</strong>: it is
     * the second conjunct of {@code app/cbl/COMEN01C.cbl:137}, {@code CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'},
     * named once so that the selection gate and the display filter can never drift apart.
     *
     * @param option the option whose gate byte is to be tested.
     * @return true when the option's gate byte is exactly {@code 'A'}
     */
    private static boolean isAdminOnlyOption(final MenuResponse.MainMenuOption option) {
        return option.userTypeCode() == ADMIN_ONLY_OPTION_CODE;
    }

    /**
     * Reports whether a normalised option is two decimal digits. <strong>Not a paragraph counterpart</strong>:
     * it is the {@code WS-OPTION IS NOT NUMERIC} test of {@code app/cbl/COMEN01C.cbl:127}, expressed
     * positively.
     *
     * @param normalisedOption the two-character normalised option.
     * @return true when every character is an ASCII digit and the length is exactly two
     */
    private static boolean isTwoDigitNumber(final String normalisedOption) {

        if (normalisedOption.length() != OPTION_INPUT_LENGTH) {
            return false;
        }
        for (int position = 0; position < normalisedOption.length(); position++) {
            final char candidate = normalisedOption.charAt(position);
            if (candidate < '0' || candidate > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Truncates a caption at its first space, reproducing COBOL {@code DELIMITED BY SPACE}. <strong>Not a
     * paragraph counterpart</strong>: it is the middle transfer of the {@code STRING} statement at
     * {@code app/cbl/COMEN01C.cbl:159-163}.
     *
     * @param optionName the caption to truncate; never null
     * @return the caption up to but excluding its first space, or the whole caption when it holds none.
     */
    private static String truncateAtFirstSpace(final String optionName) {
        final int firstSpace = optionName.indexOf(' ');
        return firstSpace < 0 ? optionName : optionName.substring(0, firstSpace);
    }

    /**
     * Space fills a caption to its declared width. <strong>Not a paragraph counterpart</strong>: it makes the
     * {@code DELIMITED BY SIZE} transfer at {@code app/cbl/COMEN01C.cbl:245} exact for any caption.
     *
     * @param optionName the caption to pad; never null
     * @return the caption at no less than its declared thirty-five-character width.
     */
    private static String padOptionName(final String optionName) {

        final StringBuilder padded = new StringBuilder(MenuResponse.OPTION_NAME_LENGTH);
        padded.append(optionName);
        while (padded.length() < MenuResponse.OPTION_NAME_LENGTH) {
            padded.append(' ');
        }
        return padded.toString();
    }

    /**
     * What {@code SEND-MENU-SCREEN} of {@code app/cbl/COMEN01C.cbl:182} composed, carried out as a value
     * instead of transmitted as a 3270 map.
     *
     * @param menu the options this user may select, in the copybook order of
     * {@code app/cpy/COMEN02Y.cpy:25-84}, already reduced by the least-privilege filter of
     * {@link #sendMenuScreen(UserType)}.
     * @param optionLabels the rendered menu lines exactly as {@code BUILD-MENU-OPTIONS} composed them at
     * {@code :242-246} - a zero-padded two-digit number, a full stop, a space, then the caption at its declared
     * width - each padded to the forty characters of {@code OPTN001I PIC X(40)}.
     * @param signOnTarget the program the operator leaves the menu by, which {@code RETURN-TO-SIGNON-SCREEN}
     * resolved at {@code :172-174} and reached through {@code EXEC CICS XCTL}.
     */
    public record MainMenuScreen(
            MenuResponse<MenuResponse.MainMenuOption> menu,
            List<String> optionLabels,
            String signOnTarget) {

        /**
         * Validates the three parts, proves they agree with one another, and takes a defensive copy of the
         * labels.
         *
         * @param menu the option payload; must not be null
         * @param optionLabels the rendered lines; must not be null, must hold no null element, and must hold
         * exactly one entry per option of {@code menu}
         * @param signOnTarget the sign-on route label; must not be null or blank
         * @throws IllegalArgumentException if any part is null, if a label is null, if the label count differs
         * from the option count, or if the sign-on target is blank.
         */
        public MainMenuScreen {

            if (menu == null) {
                throw new IllegalArgumentException("menu must not be null; supply MenuResponse.ofMainMenu"
                        + " over an empty list to represent a menu offering no options");
            }
            if (optionLabels == null) {
                throw new IllegalArgumentException("optionLabels must not be null; supply an empty list to"
                        + " represent a menu offering no options");
            }
            if (signOnTarget == null || signOnTarget.isBlank()) {
                throw new IllegalArgumentException("signOnTarget must name a program; app/cbl/COMEN01C.cbl"
                        + ":172-174 always resolves one, defaulting to " + SIGN_ON_PROGRAM);
            }

            // Pre-sized to the exact final length, which the option count bounds at ten.
            final List<String> copiedLabels = new ArrayList<>(optionLabels.size());
            for (final String label : optionLabels) {
                if (label == null) {
                    // copiedLabels.size() is the index within optionLabels of the element being rejected.
                    throw new IllegalArgumentException("optionLabels must not contain a null element, but "
                            + "index " + copiedLabels.size() + " was null");
                }
                copiedLabels.add(label);
            }
            if (copiedLabels.size() != menu.getOptionCount()) {
                throw new IllegalArgumentException("optionLabels holds " + copiedLabels.size()
                        + " lines but menu carries " + menu.getOptionCount() + " options; "
                        + "app/cbl/COMEN01C.cbl:238-239 renders exactly one line per option");
            }

            optionLabels = List.copyOf(copiedLabels);
        }
    }

    /**
     * The outcome of a selection that {@code PROCESS-ENTER-KEY} of {@code app/cbl/COMEN01C.cbl:115} accepted:
     * which option was chosen, where it leads, and the notice the source displayed when it led nowhere yet.
     *
     * @param optionNumber the selected option's own number, {@code CDEMO-MENU-OPT-NUM PIC 9(02)} of
     * {@code app/cpy/COMEN02Y.cpy:89}, as stored in the table entry and not the subscript that reached it.
     * @param optionName the selected option's caption, {@code CDEMO-MENU-OPT-NAME PIC X(35)} of
     * {@code app/cpy/COMEN02Y.cpy:90}, at its declared width.
     * @param targetProgram the eight-character program name held in {@code CDEMO-MENU-OPT-PGMNAME PIC X(08)} of
     * {@code app/cpy/COMEN02Y.cpy:91}, which {@code :153} passed to {@code EXEC CICS XCTL PROGRAM(...)}.
     * @param placeholder whether the target is one of the placeholders the source detected with
     * {@code IF CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'} at {@code :146}, comparing the first five
     * characters only.
     * @param message the placeholder notice of {@code :159-163} when {@code placeholder} is true, and the empty
     * string when it is false.
     */
    public record MenuSelection(
            int optionNumber,
            String optionName,
            String targetProgram,
            boolean placeholder,
            String message) {

        /**
         * Validates the five parts and enforces the placeholder invariant.
         *
         * @param optionNumber the option's stored number.
         * @param optionName the option's caption; must not be null
         * @param targetProgram the target program name; must not be null or blank
         * @param placeholder whether the target is a {@code DUMMY} placeholder
         * @param message the placeholder notice; must not be null, must be empty when {@code placeholder} is
         * false, and must be non-empty when it is true
         * @throws IllegalArgumentException if the option number is out of range, if a reference part is null,
         * if the target program is blank, or if the message and the placeholder flag disagree.
         */
        public MenuSelection {

            if (optionNumber < 1 || optionNumber > MenuResponse.OPTION_NUMBER_MAX_VALUE) {
                throw new IllegalArgumentException("optionNumber must be between 1 and "
                        + MenuResponse.OPTION_NUMBER_MAX_VALUE + " because CDEMO-MENU-OPT-NUM is PIC 9(02)"
                        + " and app/cbl/COMEN01C.cbl:133 rejects zero, but was " + optionNumber);
            }
            if (optionName == null) {
                throw new IllegalArgumentException(
                        "optionName must not be null; every populated table entry carries a caption");
            }
            if (targetProgram == null || targetProgram.isBlank()) {
                throw new IllegalArgumentException("targetProgram must name a program; every populated "
                        + "entry of app/cpy/COMEN02Y.cpy:25-84 carries an eight-character name");
            }
            if (message == null) {
                throw new IllegalArgumentException(
                        "message must not be null; supply the empty string when there is no notice");
            }

            // The XCTL fall-through invariant. A real target carries no notice, because under CICS the notice at
            // app/cbl/COMEN01C.cbl:157-164 was unreachable once XCTL had transferred control; a placeholder target
            // always carries one, because :159-163 always assembles a non-empty string. the planned DECISION_LOG.md
            // entry \"XCTL fall-through guard".
            if (placeholder == message.isEmpty()) {
                throw new IllegalArgumentException("message must be empty for a real target and non-empty "
                        + "for a placeholder target, but placeholder was " + placeholder
                        + " and the message was " + (message.isEmpty() ? "empty" : "non-empty"));
            }
        }
    }
}
