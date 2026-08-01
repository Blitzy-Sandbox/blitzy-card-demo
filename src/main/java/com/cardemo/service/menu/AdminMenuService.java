/*
 ******************************************************************
 * Program     : AdminMenuService.java
 * Application : CardDemo
 * Type        : Spring Service (migrated from CICS COBOL Program)
 * Function    : Admin Menu for Admin users
 * Source      : app/cbl/COADM01C.cbl (268 lines, 7 paragraphs)
 *               + app/cpy/COADM02Y.cpy @ 7756d89
 ******************************************************************
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
 ******************************************************************
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

/**
 * The administrator menu of the CardDemo application: the Java counterpart of CICS transaction
 * {@code CA00}, which {@code app/csd/CARDDEMO.CSD:L327-L328} binds to program {@code COADM01C}.
 *
 * <h2>What it does</h2>
 *
 * <p>It renders the four administrative options transcribed from {@code app/cpy/COADM02Y.cpy} and it
 * resolves an operator's selection into one of exactly two outcomes: a target the caller navigates to, or
 * an informational notice that the option is not yet available. A selection that is blank, non-numeric,
 * zero or beyond the option count is rejected with the literal message the source program displays. It is
 * a stateless, side-effect-free translation of {@code app/cbl/COADM01C.cbl}, whose seven paragraphs appear
 * below as seven private methods, one for one, each citing the source label and line it reproduces.</p>
 *
 * <p><strong>Side effects: none.</strong> No I/O, no persistence, no messaging, no HTTP call, no mutation
 * of injected state and no mutable static state. That is not an accident of this translation, it is a
 * property of the source: the complete {@code EXEC CICS} inventory of {@code app/cbl/COADM01C.cbl} is
 * {@code RETURN} at {@code :L107}, {@code XCTL} at {@code :L142-L145} and {@code :L165-L167},
 * {@code SEND} at {@code :L179-L184} and {@code RECEIVE} at {@code :L191-L197}. There is no
 * {@code READ}, {@code WRITE}, {@code REWRITE}, {@code DELETE}, {@code STARTBR}, {@code READNEXT},
 * {@code READPREV} or {@code ENDBR} anywhere in the program, so there is no {@code FILE STATUS} path and
 * consequently no file-status mapper is injected here. {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '}
 * at {@code app/cbl/COADM01C.cbl:L39} is declared and never referenced, which corroborates the finding
 * from the other direction, as does the dead {@code COPY CSUSR01Y} at {@code :L58} whose
 * {@code SEC-USR-} fields the program never names.</p>
 *
 * <h2>Four verified differences from the main menu, every one deliberate</h2>
 *
 * <p>{@code app/cbl/COADM01C.cbl} and {@code app/cbl/COMEN01C.cbl} share seven paragraph names, the same
 * option-count bound, the same input normalisation and the same working-storage shape. They are
 * nevertheless <strong>not the same program</strong>, and this class is a deliberate subtraction from its
 * sibling rather than a copy of it:</p>
 *
 * <ol>
 *   <li><strong>The option table is a triple, not a quadruple.</strong> The entry at
 *       {@code app/cpy/COADM02Y.cpy:L46-L48} declares {@code CDEMO-ADMIN-OPT-NUM PIC 9(02)},
 *       {@code CDEMO-ADMIN-OPT-NAME PIC X(35)} and {@code CDEMO-ADMIN-OPT-PGMNAME PIC X(08)} and stops
 *       there. There is no user-type byte. {@code MenuResponse.AdminMenuOption} enforces this in the type
 *       system: asking it for a user type does not compile.</li>
 *   <li><strong>There is therefore no per-option user-type gate at all</strong> - see the dedicated
 *       section below.</li>
 *   <li><strong>The coming-soon notice omits the option name entirely</strong>, because the two
 *       {@code STRING} operands that would have supplied it are commented out at
 *       {@code app/cbl/COADM01C.cbl:L150-L151}.</li>
 *   <li><strong>The table subscript is safe.</strong> The only subscripted reference,
 *       {@code CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)} at {@code app/cbl/COADM01C.cbl:L138}, sits inside
 *       {@code IF NOT ERR-FLG-ON} at {@code :L137}, so a rejected selection can never reach it.</li>
 * </ol>
 *
 * <p>No abstract base, helper or utility is shared with the main-menu service. Rule 1 Clause C asks that
 * duplication be avoided and points squarely at one, but a shared base would erase all four asymmetries
 * above, which is why behavioural parity governs here. The residual structural similarity between the two
 * beans is intentional and is recorded in DECISION_LOG.md at severity Low so that it is not "fixed" by a
 * later reader. The four option literals themselves are <em>not</em> duplicated: they are taken from
 * {@code MenuResponse.ADMIN_MENU_OPTIONS}, so exactly one transcription of
 * {@code app/cpy/COADM02Y.cpy} exists in this codebase.</p>
 *
 * <h2>There is no per-option user-type gate, and that absence is verified</h2>
 *
 * <p>Between the bounds-check {@code END-IF} at {@code app/cbl/COADM01C.cbl:L134} and the dispatch
 * {@code IF NOT ERR-FLG-ON} at {@code :L137} the program contains nothing but two blank lines,
 * {@code :L135} and {@code :L136}. It performs no eligibility test of any kind, and it structurally
 * cannot: the admin option table carries no user-type byte to test. Searching the whole program for
 * {@code USRTYPE}, {@code USRTYP} or {@code SEC-USR} returns nothing.</p>
 *
 * <p><strong>No such gate is invented here.</strong> A guard the source does not have must not be added,
 * exactly as a guard the source does have must not be removed. Least privilege is still satisfied, and it
 * is satisfied where it belongs: the <em>entire</em> administrative surface is restricted to the
 * administrator role at {@code /api/admin/*} by {@code com.cardemo.config.SecurityConfig} and
 * {@code com.cardemo.controller.MenuController}. Reaching this menu is itself the authorisation decision,
 * which is precisely why the source needs no per-option byte. This class therefore neither re-implements
 * that URL rule nor accepts a user type as an argument; it never sees a token, a credential or a
 * password hash. For the record, the identity vocabulary lives in
 * {@code com.cardemo.model.enums.UserType}, whose two constants transcribe
 * {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} and {@code 88 CDEMO-USRTYP-USER VALUE 'U'} at
 * {@code app/cpy/COCOM01Y.cpy:L27-L28}; this class does not import it, because it has nothing to test it
 * against and an unused import is dead code.</p>
 *
 * <h2>Statelessness: what the COMMAREA carried and what replaces it</h2>
 *
 * <p>{@code EXEC CICS XCTL PROGRAM(...)} at {@code app/cbl/COADM01C.cbl:L143} is replaced by URL-based
 * navigation: {@link #selectOption(String)} returns the target program name as <strong>inert
 * data</strong> and the client navigates. The name is never resolved dynamically - no reflection, no
 * {@code Class.forName}, no bean lookup by name, no dynamic proxy, no process launch. Dynamic dispatch on
 * an externally influenced name is the risky pattern Rule 1 Clause D names, and it would in any case
 * invent behaviour the target architecture replaces with routing.</p>
 *
 * <p>These {@code app/cpy/COCOM01Y.cpy} fields have no counterpart and are deliberately not modelled:
 * {@code CDEMO-FROM-TRANID PIC X(04)} at {@code :L21}, {@code CDEMO-FROM-PROGRAM PIC X(08)} at
 * {@code :L22}, {@code CDEMO-TO-TRANID PIC X(04)} at {@code :L23},
 * {@code CDEMO-PGM-CONTEXT PIC 9(01)} at {@code :L29} with its {@code 88 CDEMO-PGM-ENTER VALUE 0} at
 * {@code :L30} and {@code 88 CDEMO-PGM-REENTER VALUE 1} at {@code :L31},
 * {@code CDEMO-LAST-MAP PIC X(7)} at {@code :L43} and {@code CDEMO-LAST-MAPSET PIC X(7)} at
 * {@code :L44} - note the width of seven, not eight. Nothing here retains session, re-entry, routing or
 * screen state between calls, so every method is safe for concurrent use.</p>
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>The module builds with the pinned Maven wrapper from the repository root, which needs no
 * preinstalled Maven:</p>
 *
 * <pre>
 * ./mvnw -B clean compile     # javac release 25 under -Xlint:all -Werror, warnings are errors
 * ./mvnw -B clean test        # Surefire 3.5.4 unit tests
 * ./mvnw -B clean verify      # adds Failsafe 3.5.4 and the JaCoCo 80% line-coverage gate
 * </pre>
 *
 * <p>On a host without a JDK the identical build runs in the pinned container image, so the instruction
 * holds either way:</p>
 *
 * <pre>
 * docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -B -DskipTests compile
 * </pre>
 *
 * <p>This bean needs no running service to exercise: it touches no database, no queue and no object
 * store, so its unit tests are plain constructor-and-call tests with no Spring context, no container and
 * no test double. At runtime it is a singleton {@code @Service} reached through
 * {@code com.cardemo.controller.MenuController}.</p>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p><strong>There is none.</strong> This bean reads no property, no environment variable, no profile and
 * no configuration file. Its only inputs are the option string a caller passes and, for the header
 * furniture alone, the clock. The option table is a compile-time constant transcribed from
 * {@code app/cpy/COADM02Y.cpy} and the option count is fixed at four by
 * {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4} at {@code app/cpy/COADM02Y.cpy:L20}. Nothing about its
 * behaviour can be changed without changing this file, which is the intended property: a menu whose
 * contents drift with configuration could not be compared against the legacy screen.</p>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>{@code ValidationException} carrying {@code Please enter a valid option number...}</strong>
 *       - the selection was blank, non-numeric, zero, or greater than four. This is the source literal
 *       from {@code app/cbl/COADM01C.cbl:L131}, reproduced to the character, and it deliberately does not
 *       echo the rejected value. Expected: a blank selection normalises to {@code "00"} and is rejected
 *       by the zero test, not by a separate emptiness check.</li>
 *   <li><strong>A response saying {@code This option is coming soon ...} for a valid option</strong> -
 *       correct only when the selected option's program name begins with {@code DUMMY}. With the shipped
 *       table it never does, so seeing this notice for options one to four means the fall-through guard
 *       described on {@link #selectOption(String)} has been broken. That guard is the single highest-risk
 *       item in this file.</li>
 *   <li><strong>The notice omitting the option name looks like a bug and is not.</strong> It is the
 *       verified behaviour of {@code app/cbl/COADM01C.cbl:L149-L153}, whose name operands are commented
 *       out. Restoring the name would diverge from the source.</li>
 *   <li><strong>An option appearing that a user should not see</strong> - nothing here filters by user
 *       type, by design. Investigate the URL-level rule in {@code com.cardemo.config.SecurityConfig},
 *       which is where the administrative surface is actually protected.</li>
 *   <li><strong>{@code IllegalArgumentException} from a constructor</strong> - only the package-private
 *       test seam can raise one, when handed a null, empty or over-long option table.</li>
 * </ul>
 *
 * <h2>What the source declares that has no counterpart here</h2>
 *
 * <p>Recorded so that a reviewer comparing the two files sees these were considered rather than
 * overlooked: {@code WS-RESP-CD} and {@code WS-REAS-CD} at {@code app/cbl/COADM01C.cbl:L43-L44} are
 * captured by the {@code RECEIVE} at {@code :L195-L196} and then <em>never tested anywhere</em>, so the
 * receive is unguarded - an absent guard, preserved; {@code DFHGREEN} at {@code :L148} is a screen colour
 * attribute with no Java counterpart, though it is the evidence that the coming-soon outcome is
 * informational rather than an error; the six header fields of {@code app/cpy-bms/COADM01.CPY} are screen
 * furniture with no payload counterpart; and the {@code COPY DFHAID} and {@code COPY DFHBMSCA} members at
 * {@code :L60-L61} are supplied by the transaction monitor, are absent from this repository, and take no
 * import.</p>
 *
 * @see #getMenuScreen()
 * @see #selectOption(String)
 */
@Service
public class AdminMenuService {

    /**
     * Structured logger. Every call site below uses parameterised {@code {}} placeholders rather than
     * concatenation, and no call site logs a credential, a token, a hash or personally identifiable data,
     * because this class never receives any. Trace, span and correlation identifiers are contributed by
     * {@code com.cardemo.observability.CorrelationIdFilter} through MDC and are deliberately not set here.
     */
    private static final Logger LOG = LoggerFactory.getLogger(AdminMenuService.class);

    /**
     * The name of the COBOL program this bean reproduces, from
     * {@code WS-PGMNAME PIC X(08) VALUE 'COADM01C'} at {@code app/cbl/COADM01C.cbl:L36}.
     *
     * <p>The source moves this into {@code PGMNAMEO OF COADM1AO} at {@code :L209} to label the screen and
     * into {@code CDEMO-FROM-PROGRAM} at {@code :L140} to tell the next program where control came from.
     * Neither has a counterpart in a stateless payload, so the value is published for provenance and
     * assertion only and is never stamped into a response.</p>
     */
    public static final String PROGRAM_NAME = "COADM01C";

    /**
     * The CICS transaction identifier this bean reproduces, from
     * {@code WS-TRANID PIC X(04) VALUE 'CA00'} at {@code app/cbl/COADM01C.cbl:L37}, bound to
     * {@link #PROGRAM_NAME} by {@code app/csd/CARDDEMO.CSD:L327-L328}.
     *
     * <p>Published for provenance and assertion only, for the same reason as {@link #PROGRAM_NAME}.</p>
     */
    public static final String TRANSACTION_ID = "CA00";

    /**
     * The sign-on program, from the three places the source names it:
     * {@code MOVE 'COSGN00C' TO CDEMO-FROM-PROGRAM} at {@code app/cbl/COADM01C.cbl:L83},
     * {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM} at {@code :L97}, and the default applied at
     * {@code :L163}.
     */
    public static final String SIGN_ON_PROGRAM = "COSGN00C";

    /**
     * The rejection message for an unusable selection, from
     * {@code MOVE 'Please enter a valid option number...' TO WS-MESSAGE} at
     * {@code app/cbl/COADM01C.cbl:L131-L132}.
     *
     * <p>Thirty-seven characters, three trailing full stops, no trailing space. It carries no reference
     * to the rejected value, which is what makes it safe to log and to return: an operator learns the
     * rule that was broken without the input being echoed back.</p>
     */
    public static final String INVALID_OPTION_MESSAGE = "Please enter a valid option number...";

    /**
     * The informational notice for an option whose target is a placeholder, assembled by the
     * {@code STRING} at {@code app/cbl/COADM01C.cbl:L149-L153}.
     *
     * <p>Thirty characters, and <strong>the option name is absent</strong>. The source concatenates
     * exactly two live operands, {@code 'This option '} at {@code :L149} and
     * {@code 'is coming soon ...'} at {@code :L152}; the two operands between them,
     * {@code CDEMO-ADMIN-OPT-NAME(WS-OPTION)} at {@code :L150} and its {@code DELIMITED BY SIZE} at
     * {@code :L151}, are commented out with an asterisk in column 7. The single space between
     * {@code option} and {@code is} is the trailing space inside the first literal, which is why the two
     * words are not run together.</p>
     *
     * <p>That the disabled operand specifies {@code DELIMITED BY SIZE} rather than
     * {@code DELIMITED BY SPACE} is worth recording: had it been live it would have emitted the full
     * 35-character padded caption rather than a truncated one, giving a 65-character notice. The two
     * menus were never the same design, so this omission is intentional legacy state rather than damage,
     * and it is preserved exactly. Recorded in DECISION_LOG.md at severity Low.</p>
     */
    public static final String COMING_SOON_MESSAGE = "This option is coming soon ...";

    /**
     * The placeholder prefix tested by
     * {@code IF CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'} at
     * {@code app/cbl/COADM01C.cbl:L138}.
     *
     * <p>The reference modification {@code (1:5)} compares the first five characters of the eight-character
     * program name, not the whole name, and it compares them byte for byte. No case folding is applied
     * here for the same reason none is applied there: accepting {@code dummy} would accept input the
     * source rejects.</p>
     */
    private static final String PLACEHOLDER_PROGRAM_PREFIX = "DUMMY";

    /**
     * The declared width of the selection field, from {@code OPTIONI PIC X(2)} at
     * {@code app/cpy-bms/COADM01.CPY:L132}.
     *
     * <p>This is the width the source's backward scan starts from, because
     * {@code LENGTH OF OPTIONI OF COADM1AI} at {@code app/cbl/COADM01C.cbl:L118} evaluates to it, and it
     * is also the width of {@code WS-OPTION-X PIC X(02) JUST RIGHT} at {@code :L45} that the scanned
     * prefix is right-justified into.</p>
     */
    private static final int OPTION_FIELD_LENGTH = 2;

    /**
     * The width of the rendered option line, from {@code WS-ADMIN-OPT-TXT PIC X(40)} at
     * {@code app/cbl/COADM01C.cbl:L48}, matching the twelve {@code OPTN001I} through {@code OPTN012I}
     * screen slots of {@code app/cpy-bms/COADM01.CPY:L60-L126} which are each {@code PIC X(40)}.
     *
     * <p>Note the deliberate difference from the caption width: a caption is
     * {@code CDEMO-ADMIN-OPT-NAME PIC X(35)}, and the extra five bytes of the slot hold the two-digit
     * number and the two-character separator that {@link #buildMenuOptions()} prepends, thirty-nine bytes
     * in all, leaving one byte of slack.</p>
     */
    private static final int OPTION_SLOT_LENGTH = 40;

    /**
     * The separator the source concatenates between the option number and the caption, from
     * {@code STRING ... '. ' DELIMITED BY SIZE} at {@code app/cbl/COADM01C.cbl:L234}.
     */
    private static final String OPTION_NUMBER_SEPARATOR = ". ";

    /**
     * The empty message, reproducing {@code MOVE SPACES TO WS-MESSAGE} at
     * {@code app/cbl/COADM01C.cbl:L79} and {@code :L147}.
     *
     * <p>The source clears an 80-character screen field to spaces; a JSON payload has no fixed-width
     * field to clear, so the equivalent of "no message" is the empty string. The 80-character ceiling of
     * {@code WS-MESSAGE PIC X(80)} at {@code :L38} is a screen width with no counterpart and is
     * deliberately not enforced: both emitted literals are well inside it at thirty-seven and thirty
     * characters, so truncating to eighty could only ever damage a message, never shape one.</p>
     */
    private static final String NO_MESSAGE = "";

    /**
     * The absent navigation target, used when a selection resolves no route for the client to follow.
     *
     * <p>This is the placeholder outcome of {@code app/cbl/COADM01C.cbl:L138}: when the resolved program
     * name begins with {@code DUMMY} the source skips the {@code EXEC CICS XCTL} at {@code :L142-L145}
     * entirely, so no program is ever entered and there is no route to publish.</p>
     *
     * <p>It shares the empty-string representation of {@link #NO_MESSAGE} but is deliberately a separate
     * constant, because the two occupy different slots of {@link AdminMenuSelection} and mean different
     * things: one is "no message to display", the other is "no program to navigate to". Naming them apart
     * keeps the five-argument construction sites readable and stops a later reader from transposing the
     * {@code targetProgram} and {@code message} arguments, which the compiler could not catch since both
     * components are declared {@code String}.</p>
     */
    private static final String NO_TARGET_PROGRAM = "";

    /**
     * The name reported on a rejected selection. It identifies the offending input for a structured log
     * and is a developer-chosen identifier derived from the screen field {@code OPTIONI} at
     * {@code app/cpy-bms/COADM01.CPY:L132}; it is never request data.
     */
    private static final String OPTION_FIELD_NAME = "option";

    /**
     * The four administrative options, bounded by
     * {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4} at {@code app/cpy/COADM02Y.cpy:L20}.
     *
     * <p>This is a reference to {@code MenuResponse.ADMIN_MENU_OPTIONS} rather than a second
     * transcription of the same four literals, so that exactly one copy of
     * {@code app/cpy/COADM02Y.cpy}'s data exists in this codebase and the two cannot drift apart. That
     * list is an immutable {@code List.of(...)} of four immutable records, so publishing it through a
     * {@code static final} field here adds no mutable state: the field is a genuine constant and there is
     * no mutable static state anywhere in this class.</p>
     *
     * <p>Its contents, in copybook order, are the option number, the caption blank-padded to the
     * {@code PIC X(35)} declared width with the parenthesised {@code (Security)} suffix that is part of
     * all four captions, and the eight-character program name: {@code User List (Security)} to
     * {@code COUSR00C}, {@code User Add (Security)} to {@code COUSR01C},
     * {@code User Update (Security)} to {@code COUSR02C} and {@code User Delete (Security)} to
     * {@code COUSR03C}.</p>
     *
     * <p><strong>Four entries, never nine.</strong> {@code CDEMO-ADMIN-OPT OCCURS 9 TIMES} at
     * {@code app/cpy/COADM02Y.cpy:L45} redefines a populated area of only four 45-byte entries, 180
     * bytes, with a 405-byte overlay - a 225-byte overreach - so subscripts five to nine address
     * unrelated working storage. <strong>What those five rows contain is: Not available.</strong> To
     * establish it one would need the compiler's actual storage map for the program, which this
     * repository does not contain. Nothing is invented for them and nothing here can address them,
     * because the count is the bound: the source loops to the count at
     * {@code app/cbl/COADM01C.cbl:L228-L229} and validates against the count at {@code :L128}, never to
     * the capacity. Recorded in DECISION_LOG.md at severity Low.</p>
     */
    private static final List<MenuResponse.AdminMenuOption> CANONICAL_ADMIN_OPTIONS =
            MenuResponse.ADMIN_MENU_OPTIONS;

    /**
     * The header date format, reproducing {@code WS-CURDATE-MM-DD-YY} at
     * {@code app/cpy/CSDAT01Y.cpy:L30-L35}.
     *
     * <p>The separator is a solidus, not a hyphen: {@code app/cpy/CSDAT01Y.cpy:L32} and {@code :L34}
     * declare {@code FILLER PIC X(01) VALUE '/'}. The group's name contains hyphens, its rendered value
     * does not. The two-digit year reproduces {@code MOVE WS-CURDATE-YEAR(3:2) TO WS-CURDATE-YY} at
     * {@code app/cbl/COADM01C.cbl:L213}, which takes the last two characters of the four-digit year at
     * {@code app/cpy/CSDAT01Y.cpy:L20}. {@code Locale.ROOT} is passed explicitly so the rendering cannot
     * vary with the platform default locale.</p>
     */
    private static final DateTimeFormatter HEADER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);

    /**
     * The header time format, reproducing {@code WS-CURTIME-HH-MM-SS} at
     * {@code app/cpy/CSDAT01Y.cpy:L36-L41}, whose separator is a colon per the
     * {@code FILLER PIC X(01) VALUE ':'} declarations at {@code :L38} and {@code :L40}.
     *
     * <p>Twenty-four-hour, zero-padded, seconds resolution: the source moves
     * {@code WS-CURTIME-HOURS}, {@code WS-CURTIME-MINUTE} and {@code WS-CURTIME-SECOND}, each
     * {@code PIC 9(02)}, at {@code app/cbl/COADM01C.cbl:L217-L219} and never moves
     * {@code WS-CURTIME-MILSEC}, so no sub-second component is rendered. {@code Locale.ROOT} is passed
     * explicitly for the reason given on {@link #HEADER_DATE_FORMAT}.</p>
     */
    private static final DateTimeFormatter HEADER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /**
     * The screen title, from {@code CCDA-TITLE01 PIC X(40)} at {@code app/cpy/COTTL01Y.cpy:L18-L19},
     * moved to {@code TITLE01O OF COADM1AO} at {@code app/cbl/COADM01C.cbl:L206}. Carried
     * blank-padded to the declared forty characters exactly as the {@code VALUE} literal declares it.
     */
    private static final String SCREEN_TITLE_01 = "      AWS Mainframe Modernization       ";

    /**
     * The screen subtitle, from {@code CCDA-TITLE02 PIC X(40)} at {@code app/cpy/COTTL01Y.cpy:L20-L22},
     * moved to {@code TITLE02O OF COADM1AO} at {@code app/cbl/COADM01C.cbl:L207}.
     *
     * <p>The live literal is on the third of those lines. {@code app/cpy/COTTL01Y.cpy:L21} is a COBOL
     * comment carrying the withdrawn variant {@code '  Credit Card Demo Application (CCDA)   '}, which is
     * recorded here and used nowhere.</p>
     */
    private static final String SCREEN_TITLE_02 = "              CardDemo                  ";

    /**
     * The clock the header furniture is read from, standing in for
     * {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at {@code app/cbl/COADM01C.cbl:L204}.
     *
     * <p>{@code FUNCTION CURRENT-DATE} returns the <em>local</em> date and time of the system the
     * program runs on, so a clock in the system default zone is the parity-preserving choice. It is held
     * as an injected collaborator rather than read through a static {@code now()} call so that the
     * dependence on the ambient zone is explicit at one place instead of implicit at the point of use,
     * and so that the header rendering is deterministic under test.</p>
     */
    private final Clock clock;

    /**
     * The option table this instance serves. Immutable and never reassigned; in production it is always
     * {@link #CANONICAL_ADMIN_OPTIONS}.
     */
    private final List<MenuResponse.AdminMenuOption> adminMenuOptions;

    /**
     * Creates the production bean: the four canonical options of {@code app/cpy/COADM02Y.cpy} and a
     * clock in the system default zone.
     *
     * <p>This is the constructor Spring uses. It takes no argument because this bean genuinely has no
     * collaborator to inject: the source program performs no file access, so there is nothing to read
     * from, and its option table is a compile-time constant rather than configuration. Taking no
     * argument also means the bean can never fail to wire for want of a contributed dependency.</p>
     *
     * <p>Side effects: none. No configuration is read and nothing is logged.</p>
     */
    public AdminMenuService() {
        this(Clock.systemDefaultZone(), CANONICAL_ADMIN_OPTIONS);
    }

    /**
     * Test seam. Creates an instance over a caller-supplied clock and option table.
     *
     * <p>This constructor is package-private and exists <strong>solely so that this class's own unit
     * tests can reach two things production cannot</strong>. It is not dead code and it is not an
     * extension point:</p>
     *
     * <ul>
     *   <li><strong>The placeholder branch.</strong> None of the four shipped program names begins with
     *       {@link #PLACEHOLDER_PROGRAM_PREFIX}, so the guard at {@code app/cbl/COADM01C.cbl:L138} is
     *       always true in production and the coming-soon notice is unreachable there. Those lines are
     *       retained for behavioural parity, so they must be reachable from a test rather than excluded
     *       from coverage measurement - the coverage gate is not weakened to accommodate them.</li>
     *   <li><strong>A fixed clock,</strong> which makes the header furniture assertable instead of
     *       varying with wall-clock time.</li>
     * </ul>
     *
     * <p>Production always uses {@link #AdminMenuService()} and therefore always the verbatim table.</p>
     *
     * <p>Side effects: none beyond construction. Nothing is logged and the supplied list is not
     * mutated.</p>
     *
     * @param clock            the clock the header date and time are read from; must not be
     *                         {@code null}
     * @param adminMenuOptions the option table to serve, in menu order. Must not be {@code null}, must
     *                         not be empty, must not contain a {@code null} element, and must hold no
     *                         more than the four entries {@code app/cpy/COADM02Y.cpy:L20} populates. A
     *                         defensive, unmodifiable copy is taken, so a later change to the caller's
     *                         list cannot affect this instance
     * @throws IllegalArgumentException if {@code clock} is {@code null}, or if
     *                                  {@code adminMenuOptions} is {@code null}, empty, contains a
     *                                  {@code null} element, or exceeds the populated option count
     */
    AdminMenuService(final Clock clock, final List<MenuResponse.AdminMenuOption> adminMenuOptions) {

        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        if (adminMenuOptions == null) {
            throw new IllegalArgumentException("adminMenuOptions must not be null");
        }
        if (adminMenuOptions.isEmpty()) {
            throw new IllegalArgumentException(
                    "adminMenuOptions must not be empty; app/cpy/COADM02Y.cpy:L20 populates four options");
        }

        final int populatedOptionCount = MenuResponse.MenuType.ADMIN.getPopulatedOptionCount();
        if (adminMenuOptions.size() > populatedOptionCount) {
            throw new IllegalArgumentException("adminMenuOptions holds " + adminMenuOptions.size()
                    + " entries, which exceeds the " + populatedOptionCount
                    + " populated by app/cpy/COADM02Y.cpy:L20; the OCCURS 9 capacity at :L45 is not a"
                    + " valid bound because its spare subscripts are unpopulated");
        }

        // Pre-sized to the exact final length: the bound above proves it cannot exceed the populated
        // option count, so the copy is allocated once and never resized.
        final List<MenuResponse.AdminMenuOption> defensiveCopy = new ArrayList<>(adminMenuOptions.size());
        for (final MenuResponse.AdminMenuOption option : adminMenuOptions) {
            if (option == null) {
                // defensiveCopy.size() is the index within adminMenuOptions of the rejected element.
                throw new IllegalArgumentException("adminMenuOptions must not contain a null element,"
                        + " but index " + defensiveCopy.size() + " was null");
            }
            defensiveCopy.add(option);
        }

        this.clock = clock;
        this.adminMenuOptions = List.copyOf(defensiveCopy);
    }

    /**
     * Returns the administrator menu as it is first presented, with no message.
     *
     * <p>This is the Java form of the first-entry branch of {@code MAIN-PARA}, which clears the message at
     * {@code app/cbl/COADM01C.cbl:L79-L80} and performs {@code SEND-MENU-SCREEN} at {@code :L90}. The
     * caller receives the four options, the four rendered option lines and an empty message.</p>
     *
     * <p><strong>Inputs:</strong> none. <strong>Output:</strong> a fully populated
     * {@link AdminMenuView}, never {@code null}. <strong>Side effects:</strong> none - no I/O, no
     * persistence, no messaging and no state change; the clock is read for the header furniture only.
     * <strong>Error modes:</strong> none; this method cannot fail.</p>
     *
     * @return the menu view carrying the four options, their rendered lines and an empty message
     */
    public AdminMenuView getMenuScreen() {
        return sendMenuScreen(NO_MESSAGE);
    }

    /**
     * Resolves an operator's menu selection, reproducing the contract of {@code PROCESS-ENTER-KEY} at
     * {@code app/cbl/COADM01C.cbl:L115-L155}.
     *
     * <p>The selection is normalised exactly as the source normalises it, then tested against the three
     * disjuncts of {@code app/cbl/COADM01C.cbl:L127-L129}, then dispatched. There is deliberately
     * <strong>no user-type check</strong> between those two steps, because the source has none - see the
     * class documentation.</p>
     *
     * <h4>The fall-through hazard this method exists to get right</h4>
     *
     * <p>In the source, the coming-soon block at {@code :L147-L154} sits <em>after</em> the dispatch
     * {@code END-IF} at {@code :L146}, which under CICS is unreachable whenever a real target resolves:
     * {@code EXEC CICS XCTL} at {@code :L142-L145} transfers control and never returns, so the statements
     * following it are never executed on that path. <strong>A Java call returns.</strong> Reproducing the
     * text of that paragraph literally would therefore announce "coming soon" for every valid selection,
     * inverting the program's behaviour. This method returns as soon as a real target is resolved, so the
     * notice is emitted only on the path the source could actually reach it from - the placeholder path.
     * The substitution is a mechanism change, not a behaviour change, and is recorded in DECISION_LOG.md
     * with the reasoning above.</p>
     *
     * <p><strong>Inputs:</strong> the raw content of the {@code OPTIONI PIC X(2)} screen field; may be
     * {@code null}, empty or blank. <strong>Output:</strong> an {@link AdminMenuSelection}, never
     * {@code null}. <strong>Side effects:</strong> none; the returned target program name is inert data
     * and nothing is dispatched, loaded, resolved or executed on the strength of it.</p>
     *
     * <p><strong>Error modes:</strong> exactly one. A selection that is non-numeric, greater than the
     * option count or zero raises {@code ValidationException} carrying
     * {@link #INVALID_OPTION_MESSAGE}. A blank or absent selection reaches that same rejection through
     * the zero test rather than through a separate check, because normalisation turns it into
     * {@code "00"}. Nothing else can fail, and nothing is caught here: with no I/O there is no exception
     * to wrap, which satisfies the no-swallowing requirement rather than evading it.</p>
     *
     * @param option the raw selection as keyed into the {@code OPTIONI} field of
     *               {@code app/cpy-bms/COADM01.CPY:L132}; {@code null}, empty and blank are all handled
     *               explicitly and all normalise to {@code "00"}
     * @return the resolved selection: either a real target with an empty message, or a placeholder target
     *         with {@link #COMING_SOON_MESSAGE}
     * @throws ValidationException if the normalised selection is not a usable option number
     */
    public AdminMenuSelection selectOption(final String option) {
        return mainPara(option);
    }

    /**
     * Resolves the program the client navigates to when leaving this menu for the sign-on screen,
     * reproducing {@code RETURN-TO-SIGNON-SCREEN} at {@code app/cbl/COADM01C.cbl:L160-L167}.
     *
     * <p>The source reaches this paragraph on two paths: an absent communication area at
     * {@code :L82-L84}, and the PF3 key at {@code :L96-L98}. Both have no counterpart in a stateless
     * request - there is no communication area to measure and no attention identifier to evaluate - but
     * the paragraph's own logic does: it defaults an unset target to {@link #SIGN_ON_PROGRAM}.</p>
     *
     * <p><strong>Inputs:</strong> the requested target, standing in for {@code CDEMO-TO-PROGRAM} at
     * {@code app/cpy/COCOM01Y.cpy:L24}; {@code null}, empty and blank all select the default.
     * <strong>Output:</strong> a program name, never {@code null} and never blank. <strong>Side
     * effects:</strong> none; as with {@link #selectOption(String)} the name is inert data and the client
     * navigates. <strong>Error modes:</strong> none; this method cannot fail.</p>
     *
     * @param requestedProgram the caller's requested target, or {@code null} to take the default. A
     *                         blank value is treated as unset, reproducing the source's
     *                         {@code = LOW-VALUES OR SPACES} test
     * @return {@code requestedProgram} when it carries a value, otherwise {@link #SIGN_ON_PROGRAM}
     */
    public String signOnProgram(final String requestedProgram) {
        return returnToSignOnScreen(requestedProgram);
    }

    /**
     * {@code MAIN-PARA} - {@code app/cbl/COADM01C.cbl:L75-L110}.
     *
     * <p>The source's entry paragraph clears the error flag with {@code SET ERR-FLG-OFF TO TRUE} at
     * {@code :L77} and blanks both the message and the screen's message field at {@code :L79-L80}, then
     * branches three ways. Only one of those branches survives translation, and the reasons are recorded
     * here rather than in a commit message:</p>
     *
     * <ul>
     *   <li>{@code IF EIBCALEN = 0} at {@code :L82} detects an absent communication area and returns to
     *       sign-on. A REST request has no communication area and no length to test, so the branch has no
     *       counterpart; its destination is reachable through {@link #signOnProgram(String)}.</li>
     *   <li>{@code IF NOT CDEMO-PGM-REENTER} at {@code :L87} distinguishes first entry from re-entry and,
     *       on first entry, sets the re-entry flag, blanks the output map and sends the screen at
     *       {@code :L88-L90}. The pseudo-conversational enter-versus-re-enter distinction collapses
     *       entirely into stateless request handling: first entry is a call to
     *       {@link #getMenuScreen()} and re-entry is a call to {@link #selectOption(String)}, so no flag
     *       is retained between the two and none can be observed.</li>
     *   <li>The re-entry branch performs {@code RECEIVE-MENU-SCREEN} at {@code :L92} and then evaluates
     *       {@code EIBAID} at {@code :L93-L103}: {@code DFHENTER} performs
     *       {@code PROCESS-ENTER-KEY}, {@code DFHPF3} returns to sign-on, and any other key sets the
     *       error flag and displays {@code CCDA-MSG-INVALID-KEY}, which
     *       {@code app/cpy/CSMSG01Y.cpy:L20-L21} declares as
     *       {@code PIC X(50) VALUE 'Invalid key pressed. Please see below...         '}. HTTP has no
     *       attention identifier, so only the {@code DFHENTER} arm has a counterpart and this method is
     *       that arm; the invalid-key message is consequently unreachable and is not reproduced as a
     *       constant, because a constant no path can emit would be dead code rather than parity.</li>
     * </ul>
     *
     * <p>The closing {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)} at
     * {@code :L107-L110} hands the transaction back to CICS with state attached. Returning a value to the
     * caller is its whole counterpart: no transaction identifier is re-armed and no communication area is
     * carried, per the class documentation. Retained for control-flow parity and recorded in
     * DECISION_LOG.md.</p>
     *
     * @param rawOption the unnormalised selection field content, forwarded from
     *                  {@link #selectOption(String)}
     * @return the resolved selection
     * @throws ValidationException if the selection is not a usable option number
     */
    private AdminMenuSelection mainPara(final String rawOption) {

        // :L77 SET ERR-FLG-OFF TO TRUE, and :L79-L80 MOVE SPACES TO WS-MESSAGE / ERRMSGO OF COADM1AO.
        // Neither needs a statement here. The flag is method-local to processEnterKey, which is the only
        // paragraph that sets or tests it, and the message is a return value rather than a field, so
        // neither can leak between requests the way working storage did between pseudo-conversational
        // turns. Starting cleared is the default rather than an action.
        final String optionField = receiveMenuScreen(rawOption);

        // :L93-L95 EVALUATE EIBAID WHEN DFHENTER PERFORM PROCESS-ENTER-KEY. The other two arms have no
        // counterpart, as set out above.
        return processEnterKey(optionField);
    }

    /**
     * {@code PROCESS-ENTER-KEY} - {@code app/cbl/COADM01C.cbl:L115-L155}.
     *
     * <p>The whole selection contract, in the source's own order: normalise, validate, dispatch.</p>
     *
     * <h4>Normalisation - {@code :L117-L125}</h4>
     *
     * <pre>
     * PERFORM VARYING WS-IDX
     *         FROM LENGTH OF OPTIONI OF COADM1AI BY -1 UNTIL
     *         OPTIONI OF COADM1AI(WS-IDX:1) NOT = SPACES OR
     *         WS-IDX = 1
     * END-PERFORM
     * MOVE OPTIONI OF COADM1AI(1:WS-IDX) TO WS-OPTION-X
     * INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'
     * MOVE WS-OPTION-X              TO WS-OPTION
     * MOVE WS-OPTION                TO OPTIONO OF COADM1AO
     * </pre>
     *
     * <p>Four steps, reproduced exactly. The loop scans the two-character field backwards for the last
     * non-space, stopping at position one whatever it holds. The prefix up to that position is moved into
     * {@code WS-OPTION-X PIC X(02) JUST RIGHT} at {@code :L45}, whose {@code JUST RIGHT} clause
     * right-aligns a shorter value and left-pads it with a space. {@code INSPECT} then replaces every
     * space with {@code '0'}. Finally {@code :L125} echoes the normalised value back to the screen field,
     * which is why the resolved number is carried out on {@link AdminMenuSelection#optionNumber()} rather
     * than discarded.</p>
     *
     * <p>So {@code "3"} becomes {@code " 3"} and then {@code "03"}; {@code "12"} stays {@code "12"}; and
     * a blank, empty or absent field becomes {@code "  "} and then <strong>{@code "00"}</strong>. That
     * last case is the source's own handling of empty input, and it is why no separate emptiness check is
     * added: the zero disjunct below already rejects it, and inserting a check ahead of it would create a
     * second rejection path the source does not have.</p>
     *
     * <h4>Validation - {@code :L127-L134}</h4>
     *
     * <pre>
     * IF WS-OPTION IS NOT NUMERIC OR
     *    WS-OPTION &gt; CDEMO-ADMIN-OPT-COUNT OR
     *    WS-OPTION = ZEROS
     *     MOVE 'Y'     TO WS-ERR-FLG
     *     MOVE 'Please enter a valid option number...' TO WS-MESSAGE
     *     PERFORM SEND-MENU-SCREEN
     * END-IF
     * </pre>
     *
     * <p>All three disjuncts are preserved in order. The count compared against at {@code :L128} is
     * {@code CDEMO-ADMIN-OPT-COUNT}, four, and never the {@code OCCURS 9} capacity. The source sets an
     * error flag and re-sends the screen; the stateless equivalent is to raise
     * {@code ValidationException} carrying the same literal, which the caller renders. That is not an
     * early exit invented for convenience: the source's own {@code IF NOT ERR-FLG-ON} at {@code :L137}
     * makes the remainder of the paragraph conditional on the flag being clear, so returning here
     * reproduces the same control flow by a different mechanism.</p>
     *
     * <h4>No user-type gate - {@code :L134-L137}</h4>
     *
     * <p>{@code :L135} and {@code :L136} are blank lines. Nothing stands between the validation
     * {@code END-IF} and the dispatch {@code IF}, and nothing is inserted here. Because no statement in
     * that gap can set the error flag, the subscripted table reference at {@code :L138} is reached only
     * when the selection has already been proved to be in range - which is why this program, unlike its
     * main-menu sibling, has no unguarded-subscript exposure to reason about.</p>
     *
     * <h4>Dispatch and the placeholder guard - {@code :L137-L155}</h4>
     *
     * <p>{@code :L138} compares the first five characters of the target program name against
     * {@code 'DUMMY'} through the reference modification {@code (1:5)}. A real target means the source
     * transfers control at {@code :L142-L145} and never comes back; a placeholder target means it falls
     * through to the notice at {@code :L147-L154}. See {@link #selectOption(String)} for why the Java
     * form must return early on the real-target path, and why getting that wrong would invert the
     * program's behaviour.</p>
     *
     * <p>{@code :L139-L141} populate {@code CDEMO-FROM-TRANID}, {@code CDEMO-FROM-PROGRAM} and
     * {@code CDEMO-PGM-CONTEXT} for the program about to receive control. All three have no counterpart
     * in a stateless request and are documented as such in the class documentation; retained by
     * reference, with a DECISION_LOG.md entry, rather than silently dropped. {@code :L148} moves
     * {@code DFHGREEN} into the message colour attribute, which has no Java counterpart but is the
     * evidence that this outcome is <em>informational</em>: it is returned as a value, never thrown,
     * whereas the validation failure above is an error and is thrown.</p>
     *
     * @param optionField the two-character selection field content, as materialised by
     *                    {@link #receiveMenuScreen(String)}
     * @return the resolved selection
     * @throws ValidationException if the normalised selection is not numeric, exceeds the option count,
     *                             or is zero
     */
    private AdminMenuSelection processEnterKey(final String optionField) {

        // ---- :L117-L121  PERFORM VARYING WS-IDX FROM LENGTH OF OPTIONI BY -1 UNTIL non-space OR 1.
        // The scan starts at the declared field width and walks down, so it strips trailing spaces. It
        // stops at position one unconditionally, which is what makes an all-blank field yield a
        // single-space prefix rather than an empty one.
        int workIndex = OPTION_FIELD_LENGTH;
        while (workIndex > 1 && optionField.charAt(workIndex - 1) == ' ') {
            workIndex--;
        }

        // ---- :L122  MOVE OPTIONI OF COADM1AI(1:WS-IDX) TO WS-OPTION-X, whose PIC X(02) JUST RIGHT
        // right-aligns the prefix and left-pads it with spaces.
        final String scannedPrefix = optionField.substring(0, workIndex);
        final StringBuilder justifiedRight = new StringBuilder(OPTION_FIELD_LENGTH);
        for (int pad = scannedPrefix.length(); pad < OPTION_FIELD_LENGTH; pad++) {
            justifiedRight.append(' ');
        }
        justifiedRight.append(scannedPrefix);

        // ---- :L123  INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'.
        for (int position = 0; position < justifiedRight.length(); position++) {
            if (justifiedRight.charAt(position) == ' ') {
                justifiedRight.setCharAt(position, '0');
            }
        }

        // ---- :L124-L125  MOVE WS-OPTION-X TO WS-OPTION (PIC 9(02)), then echo it to the screen field.
        final String normalisedOption = justifiedRight.toString();

        // ---- :L127-L134  The three disjuncts, in source order.
        // Disjunct one, :L127 WS-OPTION IS NOT NUMERIC. Every character of the two-character field must
        // be a digit. Character.isDigit is deliberately not used: it accepts non-ASCII decimal digits,
        // which a PIC 9(02) field cannot hold, and accepting them would admit input the source rejects.
        boolean optionIsNumeric = true;
        for (int position = 0; position < normalisedOption.length(); position++) {
            final char digit = normalisedOption.charAt(position);
            if (digit < '0' || digit > '9') {
                optionIsNumeric = false;
                break;
            }
        }

        // A non-numeric field has no numeric value; -1 stands for "not evaluated" and is never compared,
        // because the short-circuit below rejects on the numeric disjunct first, exactly as the source's
        // OR does.
        final int optionNumber = optionIsNumeric ? Integer.parseInt(normalisedOption) : -1;

        // Disjunct two, :L128 WS-OPTION > CDEMO-ADMIN-OPT-COUNT: the populated count, four, never the
        // OCCURS 9 capacity. Disjunct three, :L129 WS-OPTION = ZEROS, which is also the path a blank or
        // absent selection arrives on.
        if (!optionIsNumeric || optionNumber > this.adminMenuOptions.size() || optionNumber == 0) {
            // :L130-L133 MOVE 'Y' TO WS-ERR-FLG, move the literal, re-send the screen. The flag exists to
            // suppress the dispatch below, which returning achieves directly.
            LOG.debug("Admin menu selection rejected for transaction {}: option field did not resolve to a"
                    + " usable option number in 1..{}", TRANSACTION_ID, this.adminMenuOptions.size());
            throw ValidationException.invalidField(OPTION_FIELD_NAME, INVALID_OPTION_MESSAGE);
        }

        // ---- :L134-L137  Two blank lines. No user-type gate exists in this program and none is added.

        // ---- :L137 IF NOT ERR-FLG-ON. Control reaches here only with the flag clear, because every path
        // that would have set it has already returned.
        // ---- :L138 the only subscripted reference in the program, safe by the bounds proof above. COBOL
        // subscripts are one-based, Java indices zero-based.
        final MenuResponse.AdminMenuOption selectedOption = this.adminMenuOptions.get(optionNumber - 1);
        final String targetProgram = selectedOption.programName();

        // ---- :L138 the placeholder test itself: the first five characters only, compared byte for byte
        // with no case folding. A name shorter than five characters cannot match, which is the same
        // outcome the reference modification produces for any non-'DUMMY' prefix.
        if (!targetProgram.startsWith(PLACEHOLDER_PROGRAM_PREFIX)) {

            // ---- :L139-L145  CDEMO-FROM-TRANID, CDEMO-FROM-PROGRAM and CDEMO-PGM-CONTEXT have no
            // counterpart, and EXEC CICS XCTL becomes URL-based navigation: the name below is inert data
            // and the client navigates. It is never used to load a class or look up a bean.
            LOG.debug("Admin menu option {} of transaction {} resolved to target program {}",
                    optionNumber, TRANSACTION_ID, targetProgram);

            // ---- THE XCTL FALL-THROUGH GUARD. Under CICS, XCTL never returns, so :L147-L154 is
            // unreachable from here. In Java the call would return, so returning now is what preserves
            // the source's behaviour; falling through would announce "coming soon" for every valid
            // selection. Recorded in DECISION_LOG.md as a mechanism substitution.
            return new AdminMenuSelection(optionNumber, selectedOption.optionName(), targetProgram,
                    NO_MESSAGE, false);
        }

        // ---- :L146-L154  Reached only when the target is a placeholder. :L147 blanks the message, :L148
        // colours it green - informational, not an error - and :L149-L153 assembles the notice from two
        // live operands, the option name having been commented out at :L150-L151. :L154 re-sends the
        // screen, which is the caller's business here.
        //
        // INTENTIONAL RETENTION: none of the four shipped program names begins with 'DUMMY', so this
        // branch is unreachable in production. It is retained verbatim for behavioural parity rather than
        // deleted, and it is tracked - not an untracked leftover - by a DECISION_LOG.md entry and by the
        // package-private test seam on this class, which is how it is covered without weakening the
        // coverage gate.
        LOG.debug("Admin menu option {} of transaction {} targets placeholder program prefix {};"
                + " returning the coming-soon notice", optionNumber, TRANSACTION_ID,
                PLACEHOLDER_PROGRAM_PREFIX);

        return new AdminMenuSelection(optionNumber, selectedOption.optionName(), NO_TARGET_PROGRAM,
                COMING_SOON_MESSAGE, true);
    }

    /**
     * {@code RETURN-TO-SIGNON-SCREEN} - {@code app/cbl/COADM01C.cbl:L160-L167}.
     *
     * <pre>
     * IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES
     *     MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
     * END-IF
     * EXEC CICS
     *     XCTL PROGRAM(CDEMO-TO-PROGRAM)
     * END-EXEC.
     * </pre>
     *
     * <p>The default at {@code :L162-L164} is retained one for one. {@code LOW-VALUES} is a field of
     * binary zeros and {@code SPACES} a field of blanks - the two ways a fixed-width field expresses
     * "unset" - so the Java test covers {@code null}, empty and all-blank, which are their counterparts.
     * The transfer at {@code :L165-L167} has no counterpart: the resolved name is returned and the client
     * navigates. Retained rather than consolidated into its callers, with a DECISION_LOG.md
     * reference.</p>
     *
     * @param requestedProgram the requested target, standing in for {@code CDEMO-TO-PROGRAM}; may be
     *                         {@code null}, empty or blank
     * @return the requested program when it carries a value, otherwise {@link #SIGN_ON_PROGRAM}
     */
    private String returnToSignOnScreen(final String requestedProgram) {

        // :L162-L164 IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES -> MOVE 'COSGN00C'. isBlank() covers the
        // all-blank case and the empty case together; null is the Java-only third way to be unset.
        if (requestedProgram == null || requestedProgram.isBlank()) {
            return SIGN_ON_PROGRAM;
        }

        // :L165-L167 EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM): no counterpart, the client navigates.
        return requestedProgram;
    }

    /**
     * {@code SEND-MENU-SCREEN} - {@code app/cbl/COADM01C.cbl:L172-L184}.
     *
     * <pre>
     * PERFORM POPULATE-HEADER-INFO
     * PERFORM BUILD-MENU-OPTIONS
     * MOVE WS-MESSAGE TO ERRMSGO OF COADM1AO
     * EXEC CICS SEND
     *           MAP('COADM1A')
     *           MAPSET('COADM01')
     *           FROM(COADM1AO)
     *           ERASE
     * END-EXEC.
     * </pre>
     *
     * <p>The two {@code PERFORM} statements at {@code :L174-L175} are preserved in order, and the message
     * move at {@code :L177} becomes {@link AdminMenuView#message()}. The BMS {@code SEND} at
     * {@code :L179-L184} has no counterpart: the map and mapset names address a 3270 screen that is not
     * reimplemented, and the controller serialises the returned view instead. {@code ERASE} clears the
     * physical screen before painting, which a stateless response does implicitly by carrying the whole
     * payload. Retained one for one with a DECISION_LOG.md reference.</p>
     *
     * @param message the message to carry, either empty or one of the two source literals
     * @return the assembled view, never {@code null}
     */
    private AdminMenuView sendMenuScreen(final String message) {

        // :L174 PERFORM POPULATE-HEADER-INFO. The six header fields are screen furniture with no payload
        // counterpart, so the rendered header is surfaced through the log rather than the response. The
        // paragraph is performed here, in the source's order, rather than being folded away.
        final String headerInfo = populateHeaderInfo();

        // :L175 PERFORM BUILD-MENU-OPTIONS.
        final List<String> optionLabels = buildMenuOptions();

        LOG.debug("Sending admin menu for transaction {} with {} options; screen header: {}",
                TRANSACTION_ID, optionLabels.size(), headerInfo);

        // :L177 MOVE WS-MESSAGE TO ERRMSGO OF COADM1AO, and :L179-L184 the BMS SEND itself.
        return new AdminMenuView(MenuResponse.ofAdminMenu(this.adminMenuOptions), optionLabels, message);
    }

    /**
     * {@code RECEIVE-MENU-SCREEN} - {@code app/cbl/COADM01C.cbl:L189-L197}.
     *
     * <pre>
     * EXEC CICS RECEIVE
     *           MAP('COADM1A')
     *           MAPSET('COADM01')
     *           INTO(COADM1AI)
     *           RESP(WS-RESP-CD)
     *           RESP2(WS-REAS-CD)
     * END-EXEC.
     * </pre>
     *
     * <p>The paragraph's whole job is to materialise the input map, of which only
     * {@code OPTIONI PIC X(2)} at {@code app/cpy-bms/COADM01.CPY:L132} is read by any other paragraph.
     * Its Java counterpart is therefore to present the caller's raw value as that fixed-width field:
     * left-aligned and blank-padded to two characters, which is what a 3270 terminal delivers for a
     * partially keyed field and precisely what the backward scan in
     * {@link #processEnterKey(String)} is written to strip.</p>
     *
     * <p><strong>An absent guard, preserved.</strong> The response codes captured at {@code :L195} and
     * {@code :L196} into {@code WS-RESP-CD} and {@code WS-REAS-CD}, declared at {@code :L43-L44}, are
     * <em>never tested anywhere in the program</em>. The receive is unguarded. No check is invented here:
     * a guard the source does not have must not be added, so the two fields have no counterpart and are
     * documented rather than translated.</p>
     *
     * <p><strong>One boundary condition the source cannot have.</strong> A 3270 field of
     * {@code PIC X(2)} cannot deliver more than two characters, but an HTTP caller can send any string.
     * Such a value is out of the field's domain and is rejected with the source's own literal - the same
     * outcome, and the same message, that any unusable two-character value receives from the validation
     * in {@link #processEnterKey(String)}. Nothing is truncated, because silently discarding characters
     * would turn an invalid selection into a valid one.</p>
     *
     * @param rawOption the caller's raw selection; may be {@code null}
     * @return the two-character field content, never {@code null} and always exactly
     *         {@link #OPTION_FIELD_LENGTH} characters
     * @throws ValidationException if {@code rawOption} is longer than the declared field width
     */
    private String receiveMenuScreen(final String rawOption) {

        // A null reference is the Java counterpart of a field the operator never keyed into: the terminal
        // would have delivered spaces, so that is what is substituted.
        if (rawOption == null) {
            return " ".repeat(OPTION_FIELD_LENGTH);
        }

        if (rawOption.length() > OPTION_FIELD_LENGTH) {
            LOG.debug("Admin menu selection rejected for transaction {}: field content of {} characters"
                    + " exceeds the declared OPTIONI PIC X({}) width", TRANSACTION_ID,
                    rawOption.length(), OPTION_FIELD_LENGTH);
            throw ValidationException.invalidField(OPTION_FIELD_NAME, INVALID_OPTION_MESSAGE);
        }

        // Left-aligned and blank-padded to the declared width, as the terminal delivers it. No trimming
        // and no case folding: the source performs neither, and the scan in processEnterKey expects the
        // padding to be present.
        final StringBuilder fieldContent = new StringBuilder(OPTION_FIELD_LENGTH);
        fieldContent.append(rawOption);
        while (fieldContent.length() < OPTION_FIELD_LENGTH) {
            fieldContent.append(' ');
        }
        return fieldContent.toString();
    }

    /**
     * {@code POPULATE-HEADER-INFO} - {@code app/cbl/COADM01C.cbl:L202-L221}.
     *
     * <p>The source populates the six header fields that every CardDemo screen carries:
     * {@code CCDA-TITLE01} and {@code CCDA-TITLE02} into the two title fields at {@code :L206-L207},
     * {@code WS-TRANID} into {@code TRNNAMEO} at {@code :L208}, {@code WS-PGMNAME} into
     * {@code PGMNAMEO} at {@code :L209}, the date into {@code CURDATEO} at {@code :L215} and the time
     * into {@code CURTIMEO} at {@code :L221}. Their widths are fixed by
     * {@code app/cpy-bms/COADM01.CPY}: {@code TRNNAMEI PIC X(4)} at {@code :L24},
     * {@code TITLE01I PIC X(40)} at {@code :L30}, {@code CURDATEI PIC X(8)} at {@code :L36},
     * {@code PGMNAMEI PIC X(8)} at {@code :L42}, {@code TITLE02I PIC X(40)} at {@code :L48} and
     * {@code CURTIMEI PIC X(8)} at {@code :L54}.</p>
     *
     * <p><strong>All six are screen furniture with no payload counterpart</strong>, so none is added to
     * {@link AdminMenuView}: a REST client renders its own chrome and has no 3270 header to fill. The
     * paragraph is nevertheless retained one for one and its result is emitted to the structured log by
     * {@link #sendMenuScreen(String)}, which keeps the computation live and observable rather than
     * leaving it as an unreachable stub. Retained with a DECISION_LOG.md reference.</p>
     *
     * <p>Two details of the rendering are easy to get wrong and are taken from the copybook rather than
     * from the field names. {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at {@code :L204} reads
     * the local system date and time. The date is then assembled at {@code :L211-L215} into
     * {@code WS-CURDATE-MM-DD-YY}, whose separators are declared
     * {@code FILLER PIC X(01) VALUE '/'} at {@code app/cpy/CSDAT01Y.cpy:L32} and {@code :L34}, so it
     * renders {@code MM/DD/YY} and not {@code MM-DD-YY}; the year is the last two digits, taken by
     * {@code MOVE WS-CURDATE-YEAR(3:2) TO WS-CURDATE-YY} at {@code :L213}. The time is assembled at
     * {@code :L217-L221} into {@code WS-CURTIME-HH-MM-SS}, whose separators are
     * {@code VALUE ':'} at {@code app/cpy/CSDAT01Y.cpy:L38} and {@code :L40}, so it renders
     * {@code HH:MM:SS}. {@code WS-CURTIME-MILSEC} at {@code app/cpy/CSDAT01Y.cpy:L28} exists but is
     * never moved, so no sub-second component appears.</p>
     *
     * @return the rendered header as a single diagnostic line, never {@code null}
     */
    private String populateHeaderInfo() {

        // :L204 MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA. Read once, so the date and the time cannot
        // straddle a midnight or second boundary between two separate reads of the clock.
        final LocalDateTime currentDateTime = LocalDateTime.now(this.clock);

        // :L211-L215 the date, and :L217-L221 the time. Separators and widths per the copybook above.
        final String currentDate = HEADER_DATE_FORMAT.format(currentDateTime);
        final String currentTime = HEADER_TIME_FORMAT.format(currentDateTime);

        // :L206-L209 the titles, the transaction identifier and the program name. Assembled into one
        // diagnostic line because no payload field carries them; the titles are trimmed of the padding
        // their PIC X(40) declarations carry, which matters only on a fixed-width screen.
        return SCREEN_TITLE_01.strip() + " | " + SCREEN_TITLE_02.strip()
                + " | tranid=" + TRANSACTION_ID + " | pgmname=" + PROGRAM_NAME
                + " | date=" + currentDate + " | time=" + currentTime;
    }

    /**
     * {@code BUILD-MENU-OPTIONS} - {@code app/cbl/COADM01C.cbl:L226-L263}.
     *
     * <pre>
     * PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL
     *                 WS-IDX &gt; CDEMO-ADMIN-OPT-COUNT
     *     MOVE SPACES             TO WS-ADMIN-OPT-TXT
     *     STRING CDEMO-ADMIN-OPT-NUM(WS-IDX)  DELIMITED BY SIZE
     *            '. '                         DELIMITED BY SIZE
     *            CDEMO-ADMIN-OPT-NAME(WS-IDX) DELIMITED BY SIZE
     *       INTO WS-ADMIN-OPT-TXT
     *     EVALUATE WS-IDX ... END-EVALUATE
     * END-PERFORM.
     * </pre>
     *
     * <p>The loop bound at {@code :L228-L229} is {@code CDEMO-ADMIN-OPT-COUNT}, four, and never the
     * {@code OCCURS 9} capacity. Each line is assembled at {@code :L233-L236} from three operands, all
     * {@code DELIMITED BY SIZE}, meaning each contributes its full declared width: the two-digit
     * zero-padded number from {@code CDEMO-ADMIN-OPT-NUM PIC 9(02)}, the two-character literal
     * {@code '. '}, and <strong>the whole 35-character blank-padded caption</strong>. Thirty-nine
     * characters in all, moved into {@code WS-ADMIN-OPT-TXT PIC X(40)}, which is why option one reads
     * {@code 01. User List (Security)} and option four {@code 04. User Delete (Security)} - note
     * {@code 01.} and not {@code 1.}</p>
     *
     * <p><strong>The same caption field is treated three different ways across the two menu programs</strong>
     * and none of the three may be generalised into the others: in full here, absent from this program's
     * coming-soon notice because its operands are commented out at {@code :L150-L151}, and truncated in
     * the main-menu program's notice. This method implements the first of the three and nothing else.</p>
     *
     * <p>The trailing blank padding is removed from the returned line. On a 3270 the padding is invisible
     * inside a fixed-width slot; a JSON string has no slot, so carrying it would put presentation padding
     * into a payload. The byte facts are preserved above rather than in the value, and the value is
     * otherwise untouched - no case folding, no internal whitespace change and no truncation of the
     * caption itself.</p>
     *
     * <p>The {@code EVALUATE} at {@code :L238-L261} distributes each assembled line into one of the
     * screen slots {@code OPTN001O} through {@code OPTN010O}. It has no counterpart, because BMS screen
     * slots are not reimplemented: an ordered list carries the same information. Two capacity facts are
     * worth recording and neither is translated - the {@code EVALUATE} runs to {@code WHEN 10} at
     * {@code :L257-L258} while the table only {@code OCCURS 9} and the count is four, so its arms for
     * five through ten are dead; and the map itself declares twelve slots, {@code OPTN001I} at
     * {@code app/cpy-bms/COADM01.CPY:L60} through {@code OPTN012I} at {@code :L126}, each
     * {@code PIC X(40)}. Three different capacity tiers - twelve slots, nine subscripts, four options -
     * of which only the last is a bound. Recorded in DECISION_LOG.md at severity Low.</p>
     *
     * @return the rendered option lines in menu order, one per populated option, as an unmodifiable list
     */
    private List<String> buildMenuOptions() {

        // :L228-L229 PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > CDEMO-ADMIN-OPT-COUNT. Pre-sized
        // to the option count, so the list is allocated once.
        final List<String> optionLabels = new ArrayList<>(this.adminMenuOptions.size());

        for (final MenuResponse.AdminMenuOption option : this.adminMenuOptions) {

            // :L231 MOVE SPACES TO WS-ADMIN-OPT-TXT: the buffer is cleared before each assembly. A fresh
            // builder per iteration is the same guarantee, and it is why no state survives an iteration.
            final StringBuilder optionLine = new StringBuilder(OPTION_SLOT_LENGTH);

            // :L233 CDEMO-ADMIN-OPT-NUM(WS-IDX) DELIMITED BY SIZE, a PIC 9(02) so zero-padded to two
            // digits. Locale.ROOT is explicit so the digits cannot be localised.
            optionLine.append(String.format(Locale.ROOT, "%02d", option.optionNumber()));

            // :L234 '. ' DELIMITED BY SIZE.
            optionLine.append(OPTION_NUMBER_SEPARATOR);

            // :L235 CDEMO-ADMIN-OPT-NAME(WS-IDX) DELIMITED BY SIZE: the full declared width, padding
            // included, which is what DELIMITED BY SIZE means.
            optionLine.append(option.optionName());

            // :L236 INTO WS-ADMIN-OPT-TXT. Trailing padding stripped for the reason given above;
            // stripTrailing removes only trailing white space and is locale-independent.
            optionLabels.add(optionLine.toString().stripTrailing());
        }

        // :L238-L261 the EVALUATE into screen slots has no counterpart; order carries the same meaning.
        return List.copyOf(optionLabels);
    }

    /**
     * The administrator menu as presented, standing in for the output map {@code COADM1AO} that
     * {@code SEND-MENU-SCREEN} paints at {@code app/cbl/COADM01C.cbl:L179-L184}.
     *
     * <p>It is declared here, nested in the service, rather than as a separate data transfer object,
     * because it exists only to pair the option payload with the two things
     * {@code com.cardemo.model.dto.MenuResponse} deliberately does not carry: the rendered option lines
     * that {@code BUILD-MENU-OPTIONS} assembles, and the message field. Inventing those as fields on the
     * shared response type would have imposed this program's screen concerns on the main menu too.</p>
     *
     * <p>Instances are immutable and carry no personally identifiable value and no credential: an option
     * is a number, a public caption and a program name. Side effects: none - this is a value.</p>
     *
     * @param menu         the four options bounded by
     *                     {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4} at
     *                     {@code app/cpy/COADM02Y.cpy:L20}, with captions carried at their declared
     *                     {@code PIC X(35)} width exactly as the copybook declares them; never
     *                     {@code null}
     * @param optionLabels the rendered lines built by {@code BUILD-MENU-OPTIONS} at
     *                     {@code app/cbl/COADM01C.cbl:L226-L263}, one per option and in menu order, for
     *                     example {@code 01. User List (Security)}; unmodifiable and never {@code null}
     * @param message      the message field, standing in for
     *                     {@code MOVE WS-MESSAGE TO ERRMSGO OF COADM1AO} at
     *                     {@code app/cbl/COADM01C.cbl:L177}; empty when there is nothing to say, which
     *                     reproduces {@code MOVE SPACES TO WS-MESSAGE} at {@code :L79}. Never
     *                     {@code null}
     */
    public record AdminMenuView(
            MenuResponse<MenuResponse.AdminMenuOption> menu,
            List<String> optionLabels,
            String message) {
    }

    /**
     * The outcome of a menu selection: the Java form of the two paths {@code PROCESS-ENTER-KEY} can leave
     * by at {@code app/cbl/COADM01C.cbl:L137-L155}.
     *
     * <p>Only two outcomes exist, because the third - a rejected selection - leaves by an exception
     * instead. That asymmetry is taken from the source rather than chosen: the rejection at
     * {@code :L130-L131} sets the error flag and shows the message as an error, whereas the coming-soon
     * outcome is coloured {@code DFHGREEN} at {@code :L148}, which is informational. So a coming-soon
     * result is a value and an invalid option number is a {@code ValidationException}.</p>
     *
     * <ul>
     *   <li><strong>A real target resolved:</strong> {@link #targetProgram()} names it,
     *       {@link #message()} is empty and {@link #comingSoon()} is {@code false}.</li>
     *   <li><strong>A placeholder target:</strong> {@link #targetProgram()} is empty because the source
     *       performs no transfer on this path, {@link #message()} carries
     *       {@link AdminMenuService#COMING_SOON_MESSAGE} and {@link #comingSoon()} is {@code true}.</li>
     * </ul>
     *
     * <p>Instances are immutable and carry no personally identifiable value and no credential. Side
     * effects: none - this is a value, and in particular {@link #targetProgram()} is inert data that is
     * never resolved, loaded or executed.</p>
     *
     * @param optionNumber  the normalised selection, one through the option count, as echoed back to the
     *                      screen by {@code MOVE WS-OPTION TO OPTIONO OF COADM1AO} at
     *                      {@code app/cbl/COADM01C.cbl:L125}
     * @param optionName    the selected option's caption from
     *                      {@code CDEMO-ADMIN-OPT-NAME PIC X(35)} at {@code app/cpy/COADM02Y.cpy:L47},
     *                      carried at its declared width exactly as the copybook declares it and never
     *                      {@code null}
     * @param targetProgram the eight-character target from
     *                      {@code CDEMO-ADMIN-OPT-PGMNAME PIC X(08)} at
     *                      {@code app/cpy/COADM02Y.cpy:L48} when a real target resolved, otherwise
     *                      empty. Inert data for the client to navigate by, replacing
     *                      {@code EXEC CICS XCTL PROGRAM(...)} at
     *                      {@code app/cbl/COADM01C.cbl:L142-L145}; never {@code null}
     * @param message       {@link AdminMenuService#COMING_SOON_MESSAGE} on the placeholder path,
     *                      otherwise empty. Never {@code null}
     * @param comingSoon    {@code true} only when the target's first five characters matched
     *                      {@code 'DUMMY'} at {@code app/cbl/COADM01C.cbl:L138}. Always {@code false}
     *                      with the shipped option table
     */
    public record AdminMenuSelection(
            int optionNumber,
            String optionName,
            String targetProgram,
            String message,
            boolean comingSoon) {
    }
}
