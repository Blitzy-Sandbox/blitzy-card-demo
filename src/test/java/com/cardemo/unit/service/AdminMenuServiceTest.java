/*
 * ******************************************************************
 * Program     : AdminMenuServiceTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Parity verification of the administrator menu. Pins
 *               the four populated option slots and the nine-subscript
 *               REDEFINES over-arity that must never bound a scan, the
 *               option-field normalisation (backward scan, right
 *               justification, space-to-zero substitution), the
 *               three-limb bounds check, the five-character DUMMY
 *               placeholder guard and its CLEAN coming-soon notice,
 *               the sign-on fall-back, the header furniture read from
 *               an injected clock, and the one-to-one correspondence
 *               between the seven source paragraphs and the seven
 *               private methods.
 * Source      : app/cbl/COADM01C.cbl, app/cpy/COADM02Y.cpy @ 7756d89
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
package com.cardemo.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.MenuResponse;
import com.cardemo.model.enums.UserType;
import com.cardemo.service.menu.AdminMenuService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * Parity verification of {@link AdminMenuService} against the frozen corpus at anchor {@code 7756d89}.
 *
 * <h2>1. What this class does</h2>
 *
 * <p>{@code AdminMenuService} replaces {@code app/cbl/COADM01C.cbl} - 268 lines, seven paragraphs - together
 * with its option table {@code app/cpy/COADM02Y.cpy}. Every expectation below is derived from a locator in
 * that corpus rather than read back off the Java constants, so a test cannot silently agree with a
 * regression in the code it guards. The locators this class asserts against are:
 *
 * <ul>
 *   <li>{@code app/cbl/COADM01C.cbl:L45-L46} - {@code WS-OPTION-X PIC X(02) JUST RIGHT} and
 *       {@code WS-OPTION PIC 9(02) VALUE 0}, the two fields the normalisation moves between.</li>
 *   <li>{@code app/cbl/COADM01C.cbl:L117-L125} - the backward scan over trailing spaces, the right
 *       justification, the {@code INSPECT ... REPLACING ALL ' ' BY '0'} substitution and the echo back to
 *       the screen field.</li>
 *   <li>{@code app/cbl/COADM01C.cbl:L127-L131} - the three-limb bounds check
 *       ({@code NOT NUMERIC}, {@code > CDEMO-ADMIN-OPT-COUNT}, {@code = ZEROS}) and the literal
 *       {@code 'Please enter a valid option number...'} it moves to {@code WS-MESSAGE}.</li>
 *   <li>{@code app/cbl/COADM01C.cbl:L138} - the placeholder guard
 *       {@code IF CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'}, on the first five characters
 *       only.</li>
 *   <li>{@code app/cbl/COADM01C.cbl:L143} - the {@code EXEC CICS XCTL}, which never returns.</li>
 *   <li>{@code app/cbl/COADM01C.cbl:L149-L152} - the coming-soon {@code STRING}, whose two option-name
 *       operands are commented out, so the notice is flat.</li>
 *   <li>{@code app/cbl/COADM01C.cbl:L226-L229} - {@code BUILD-MENU-OPTIONS} and its loop bound, which is
 *       {@code CDEMO-ADMIN-OPT-COUNT} and not the {@code OCCURS} extent.</li>
 *   <li>{@code app/cpy/COADM02Y.cpy:L19} - {@code 01 CARDDEMO-ADMIN-MENU-OPTIONS};
 *       {@code :L20} - {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4}; {@code :L22} onward - the four
 *       populated slots; {@code :L44-L48} - the {@code REDEFINES} over the same storage with
 *       {@code CDEMO-ADMIN-OPT OCCURS 9 TIMES} and its three sub-fields.</li>
 *   </ul>
 *
 * <p>Two contrast locators are asserted as well, because the administrator menu is defined as much by what
 * it lacks as by what it has. {@code app/cbl/COMEN01C.cbl:L146} carries the same placeholder guard, which
 * corrects the claim that the guard belongs to the administrator program alone; and
 * {@code app/cpy/COMEN02Y.cpy:L88-L92} declares a fourth sub-field,
 * {@code CDEMO-MENU-OPT-USRTYPE PIC X(01)}, which {@code app/cpy/COADM02Y.cpy:L46-L48} does not. That one
 * byte is the whole reason this service has no option-level user-class gate: there is no field to gate on.
 *
 * <h2>2. How to run, build and test</h2>
 *
 * <p>This class is a pure-JVM unit test: no container, no Spring context, no database and no network. It
 * reads exactly one file, the production source of the service under test, and only in order to prove that
 * every paragraph method documents its own source-line locator; the path is resolved relative to the project
 * base directory, which Surefire guarantees as the working directory. Nothing under {@code app/} is read,
 * written or otherwise touched. It is collected by <strong>Surefire 3.5.4</strong>, whose include set matches any class
 * whose simple name ends in {@code Test} while excluding the {@code integration} and {@code e2e} trees, so a
 * class placed outside {@code src/test/java/com/cardemo/unit} would be collected by neither Surefire nor
 * Failsafe and would silently never run - a green build with the class recorded as uncovered, no error and
 * no warning. Run it with:
 *
 * <pre>
 * ./mvnw -B test -Dtest=AdminMenuServiceTest
 * ./mvnw -B clean verify
 * </pre>
 *
 * <p>Test compilation is governed by {@code maven-compiler-plugin} 3.14.1 at {@code release 25} with
 * {@code -Xlint:all -Werror}, so any warning {@code javac} emits fails the build. An unused import is not
 * among them: {@code javac} 25 publishes no {@code unused} lint key, so that prohibition is review-enforced.
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <ul>
 *   <li><strong>Mockito is configured for strict stubs</strong> and is deliberately not used here.
 *       {@code AdminMenuService} has exactly two collaborators, a {@link Clock} and an immutable option
 *       list, and both are supplied as real values through the package-private construction seam. Stubbing
 *       either would replace a deterministic fact with a rehearsal of it, and an unnecessary strict stub
 *       would fail the run as unused. {@link Clock#fixed(Instant, ZoneId)} is the seam's intended input.</li>
 *   <li><strong>The option count is a parity contract, not a tunable.</strong> Four is the value of
 *       {@code CDEMO-ADMIN-OPT-COUNT} at {@code app/cpy/COADM02Y.cpy:L20}. It is not configuration, it has
 *       no property, and it may not be widened to the {@code OCCURS 9} extent at {@code :L45}.</li>
 *   <li>No ambient input reaches this class: no wall clock, no default locale, no default time zone, no
 *       random source, no environment variable, no host name and no port. Every instant used is a literal.</li>
 *   <li>The construction seam is package-private and this class sits in a different package, so it is
 *       reached reflectively through {@code MenuServiceTestSupport}, which is shared with the sibling menu
 *       test rather than duplicated here.</li>
 *   </ul>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>"warnings found and -Werror specified"</em> - one raw type, unchecked cast or dangling doc
 *       comment is enough. Every import in this file is used, which Rule 1 Clause B requires, but note that
 *       removing the last use of one produces no build failure: {@code javac} 25 has no {@code unused} lint
 *       key, so an unused import is caught at review.</li>
 *   <li><em>An option between five and nine resolves instead of being refused</em> - the bound was taken
 *       from the {@code OCCURS 9} extent instead of the count field. See {@link TheCountFieldIsTheOnlyBound}.</li>
 *   <li><em>A compilation error about a user type on an administrator option</em> - a user-class gate was
 *       invented for a table that declares no user-type byte. See {@link TheAdminOptionTableShape}.</li>
 *   <li><em>The coming-soon notice contains a caption fragment</em> - the two messages were unified. The
 *       administrator notice is flat; the main menu's is spliced. See
 *       {@link ThePlaceholderGuardAndItsCleanNotice}.</li>
 *   <li><em>Every successful selection announces "coming soon"</em> - the {@code XCTL} at
 *       {@code app/cbl/COADM01C.cbl:L143} was not modelled as a terminal transfer.</li>
 *   <li><em>An {@code IndexOutOfBoundsException} instead of a refusal</em> - the bounds check was placed
 *       after the option-record lookup rather than before it.</li>
 *   </ul>
 *
 * <h2>Findings this class pins, by severity</h2>
 *
 * <ul>
 *   <li><strong>Blocker</strong> - bounding a scan on {@code CDEMO-ADMIN-OPT OCCURS 9 TIMES}
 *       ({@code app/cpy/COADM02Y.cpy:L45}) instead of {@code CDEMO-ADMIN-OPT-COUNT ... VALUE 4}
 *       ({@code :L20}). The declared data is four 45-byte slots, 180 bytes; the redefined view is nine,
 *       405 bytes; subscripts five through nine read 225 bytes of uninitialised storage. Remediation: the
 *       count field is the only bound, and it is asserted here for subscripts five through nine
 *       individually.</li>
 *   <li><strong>High</strong> - {@code SEND-MENU-SCREEN} ({@code app/cbl/COADM01C.cbl:L172-L184}) ends at
 *       an {@code EXEC CICS SEND MAP} with no {@code EXEC CICS RETURN}, so after a bounds-check failure
 *       control falls through into the guard and dispatch region. Remediation, and a
 *       <strong>labelled deviation</strong>: Java refuses an out-of-range selection before any option-record
 *       lookup, because an uninitialised-storage read has no defined Java semantics. Held as
 *       {@code DL-DV-10} in {@code DECISION_LOG.md} - boundary (3), the menu bounds short-circuit - with a
 *       row in {@code TRACEABILITY_MATRIX.md}.</li>
 *   <li><strong>High</strong> - the coming-soon block at {@code app/cbl/COADM01C.cbl:L147-L154} sits outside
 *       the placeholder {@code IF} but inside {@code IF NOT ERR-FLG-ON}, so a transliteration that lets the
 *       {@code XCTL} return announces "coming soon" on every successful dispatch. Remediation: the transfer
 *       is terminal, asserted for all four shipped options.</li>
 *   <li><strong>Medium</strong> - the claim that the placeholder guard belongs to the administrator program
 *       alone is wrong: {@code app/cbl/COMEN01C.cbl:L146} carries it too. Remediation: the correction is
 *       recorded here and the guard is verified as a shared idiom with two different notices.</li>
 *   <li><strong>Medium</strong> - unifying the two notices. {@code app/cbl/COADM01C.cbl:L150-L151} are
 *       commented out so the administrator notice is {@code This option is coming soon ...}, whereas
 *       {@code app/cbl/COMEN01C.cbl:L161} splices a caption {@code DELIMITED BY SPACE} and loses a space.
 *       Remediation: the two literals are asserted to differ.</li>
 *   <li><strong>Low</strong> - {@code app/cbl/COMEN01C.cbl:L149-L150} carries two commented-out
 *       {@code MOVE} statements that {@code COADM01C} does not. Nothing executes in either program, so
 *       there is nothing to reproduce; it is recorded for completeness only.</li>
 *   <li><strong>Low</strong> - six of the seven paragraph labels are named verbatim in the production
 *       Javadoc; {@code RECEIVE-MENU-SCREEN} ({@code app/cbl/COADM01C.cbl:L189}) is cited by line reference
 *       instead. Remediation: name the label in the Javadoc of {@code receiveMenuScreen}. Recorded rather
 *       than corrected, because {@code src/main} is owned elsewhere, and asserted with a containment check
 *       so that the later fix strengthens the source without breaking this class.</li>
 *   </ul>
 *
 * <h2>Retained rather than deleted, with an owner</h2>
 *
 * <p>No shipped program name in {@code app/cpy/COADM02Y.cpy} begins with {@code DUMMY}, so the guard at
 * {@code app/cbl/COADM01C.cbl:L138} is never taken with shipped data. It is retained rather than deleted,
 * because deleting it would break the paragraph map the scope-coverage gate verifies. This class covers it
 * against a synthetic slot and separately proves that the shipped table triggers it for no option; no
 * {@code DUMMY}-prefixed production slot is invented.
 *
 * <h2>Division of labour</h2>
 *
 * <p>This class owns the corpus findings above. The record component names of the option types and the
 * copybook parse itself belong to {@code com.cardemo.unit.model}, and the main menu's own behaviour belongs
 * to {@code MainMenuServiceTest}: the two services are deliberately not tested together, because their
 * tables are different shapes - 45 bytes without a user-type byte against 46 bytes with one - and their
 * notices are different strings.
 *
 * @see AdminMenuService
 * @see MenuServiceTestSupport
 */
@DisplayName("AdminMenuService - app/cbl/COADM01C.cbl and app/cpy/COADM02Y.cpy, at anchor 7756d89")
class AdminMenuServiceTest {

    // Corpus constants. Every one is transcribed from a frozen artefact and carries its locator. None is
    // read back off the production class, so a regression in the code cannot make a test agree with it.

    /** {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4} at {@code app/cpy/COADM02Y.cpy:L20}. */
    private static final int POPULATED_OPTION_COUNT = 4;

    /** {@code CDEMO-ADMIN-OPT OCCURS 9 TIMES} at {@code app/cpy/COADM02Y.cpy:L45}. Never a bound. */
    private static final int OCCURS_CAPACITY = 9;

    /**
     * The width of one administrator slot: {@code PIC 9(02)} plus {@code PIC X(35)} plus {@code PIC X(08)}
     * at {@code app/cpy/COADM02Y.cpy:L46-L48}.
     */
    private static final int ADMIN_SLOT_WIDTH = 45;

    /**
     * The width of one main-menu slot: the same three sub-fields plus
     * {@code CDEMO-MENU-OPT-USRTYPE PIC X(01)} at {@code app/cpy/COMEN02Y.cpy:L92}. One byte wider, and that
     * byte is the only reason the main menu can gate an option on the signed-on user class.
     */
    private static final int MAIN_SLOT_WIDTH = 46;

    /** {@code CDEMO-ADMIN-OPT-NAME PIC X(35)} at {@code app/cpy/COADM02Y.cpy:L47}. */
    private static final int OPTION_NAME_WIDTH = 35;

    /** {@code CDEMO-ADMIN-OPT-PGMNAME PIC X(08)} at {@code app/cpy/COADM02Y.cpy:L48}. */
    private static final int PROGRAM_NAME_WIDTH = 8;

    /** {@code 02 OPTIONI PIC X(2)} at {@code app/cpy-bms/COADM01.CPY:L132}. */
    private static final int OPTION_FIELD_WIDTH = 2;

    /** {@code WS-TRANID PIC X(04) VALUE 'CA00'} at {@code app/cbl/COADM01C.cbl:L37}. */
    private static final String EXPECTED_TRANSACTION_ID = "CA00";

    /** {@code WS-PGMNAME PIC X(08) VALUE 'COADM01C'} at {@code app/cbl/COADM01C.cbl:L36}. */
    private static final String EXPECTED_PROGRAM_NAME = "COADM01C";

    /** {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM} at {@code app/cbl/COADM01C.cbl:L163}. */
    private static final String EXPECTED_SIGN_ON_PROGRAM = "COSGN00C";

    /**
     * {@code MOVE 'Please enter a valid option number...' TO WS-MESSAGE} at
     * {@code app/cbl/COADM01C.cbl:L131-L132}. Three full stops, no trailing space.
     */
    private static final String EXPECTED_INVALID_OPTION_MESSAGE = "Please enter a valid option number...";

    /**
     * The notice assembled by the {@code STRING} at {@code app/cbl/COADM01C.cbl:L149-L153}. Two operands
     * contribute, because {@code :L150-L151} carry an asterisk in column seven and so do not: the caption is
     * <strong>not</strong> spliced in. The single space is the one {@code 'This option '} already carries.
     */
    private static final String EXPECTED_COMING_SOON_MESSAGE = "This option is coming soon ...";

    /**
     * What {@code app/cbl/COMEN01C.cbl:L159-L162} produces for its first option, for contrast only. Its
     * middle operand is live and {@code DELIMITED BY SPACE}, so the caption
     * {@code 'Account View                       '} at {@code app/cpy/COMEN02Y.cpy:L26} contributes
     * {@code Account} and the result has no space before {@code is}. The administrator notice must never
     * equal this.
     */
    private static final String MAIN_MENU_SPLICED_MESSAGE = "This option Accountis coming soon ...";

    /** The literal compared at {@code app/cbl/COADM01C.cbl:L138}, and {@code (1:5)} is its whole extent. */
    private static final String PLACEHOLDER_PREFIX = "DUMMY";

    /** The reference-modification length of that comparison: {@code (1:5)}. */
    private static final int PLACEHOLDER_PREFIX_LENGTH = 5;

    /**
     * The four captions of {@code CDEMO-ADMIN-OPTIONS-DATA} at {@code app/cpy/COADM02Y.cpy:L26}, {@code :L31},
     * {@code :L36} and {@code :L41}, each carried at its declared {@code PIC X(35)} width. The trailing
     * padding lives inside the quoted literal and is part of the value, so it is reproduced here rather than
     * stripped - and these are Java string literals rather than annotation arguments precisely because
     * annotation-driven fixtures trim leading and trailing white space by default.
     */
    private static final List<String> SHIPPED_CAPTIONS = List.of(
            "User List (Security)               ",
            "User Add (Security)                ",
            "User Update (Security)             ",
            "User Delete (Security)             ");

    /**
     * The four target programs of {@code app/cpy/COADM02Y.cpy:L27}, {@code :L32}, {@code :L37} and
     * {@code :L42}. All four are user-administration screens, which is what makes a single endpoint-level
     * rule over the whole surface the correct place for authorisation.
     */
    private static final List<String> SHIPPED_PROGRAMS =
            List.of("COUSR00C", "COUSR01C", "COUSR02C", "COUSR03C");

    /**
     * The rendered option lines {@code BUILD-MENU-OPTIONS} assembles at
     * {@code app/cbl/COADM01C.cbl:L233-L236}: a {@code PIC 9(02)} number, so zero-padded to two digits, the
     * two-character literal {@code '. '}, and the whole {@code PIC X(35)} caption. Hence {@code 01.} and not
     * {@code 1.}
     *
     * <p>Each line is <strong>exactly forty characters</strong>, the width of {@code WS-ADMIN-OPT-TXT PIC
     * X(40)} at {@code app/cbl/COADM01C.cbl:L48} and of the {@code OPTN001O} screen slot it is moved into.
     * Two digits plus two separator characters plus thirty-five caption characters is thirty-nine, and the
     * fortieth is the space the {@code MOVE SPACES} at {@code :L231} left behind.
     */
    private static final List<String> EXPECTED_OPTION_LABELS = List.of(
            padToScreenSlot("01. User List (Security)"),
            padToScreenSlot("02. User Add (Security)"),
            padToScreenSlot("03. User Update (Security)"),
            padToScreenSlot("04. User Delete (Security)"));

    /**
     * Pads a rendered line to the forty-column screen slot the source's receiving field declares.
     *
     * @param line the line as its three operands compose it, never {@code null}
     * @return the line at exactly forty characters, never {@code null}
     */
    private static String padToScreenSlot(final String line) {
        return line + " ".repeat(MenuResponse.SCREEN_OPTION_SLOT_LENGTH - line.length());
    }

    /**
     * The seven paragraph labels of {@code app/cbl/COADM01C.cbl} paired with the private method each one
     * maps to, in source order: {@code MAIN-PARA} at {@code :L75}, {@code PROCESS-ENTER-KEY} at
     * {@code :L115}, {@code RETURN-TO-SIGNON-SCREEN} at {@code :L160}, {@code SEND-MENU-SCREEN} at
     * {@code :L172}, {@code RECEIVE-MENU-SCREEN} at {@code :L189}, {@code POPULATE-HEADER-INFO} at
     * {@code :L202} and {@code BUILD-MENU-OPTIONS} at {@code :L226}. Seven labels, seven methods, no
     * consolidation.
     */
    private static final List<String> PARAGRAPH_METHOD_NAMES = List.of(
            "mainPara",
            "processEnterKey",
            "returnToSignOnScreen",
            "sendMenuScreen",
            "receiveMenuScreen",
            "populateHeaderInfo",
            "buildMenuOptions");

    /** The three entry points the controller layer calls. Nothing else on this class is public. */
    private static final List<String> PUBLIC_METHOD_NAMES =
            List.of("getMenuScreen", "selectOption", "signOnProgram");

    /**
     * The seven paragraph labels themselves, in source order, exactly as {@code app/cbl/COADM01C.cbl}
     * spells them. Paired index for index with {@link #PARAGRAPH_METHOD_NAMES}.
     */
    private static final List<String> PARAGRAPH_LABELS = List.of(
            "MAIN-PARA",
            "PROCESS-ENTER-KEY",
            "RETURN-TO-SIGNON-SCREEN",
            "SEND-MENU-SCREEN",
            "RECEIVE-MENU-SCREEN",
            "POPULATE-HEADER-INFO",
            "BUILD-MENU-OPTIONS");

    /**
     * The subset of {@link #PARAGRAPH_LABELS} that the production source names verbatim. The seventh,
     * {@code RECEIVE-MENU-SCREEN}, is cited by line reference rather than by label - a <strong>Low</strong>
     * finding recorded rather than corrected here, because {@code src/main} is owned elsewhere. Remediation:
     * name the label in the Javadoc of {@code receiveMenuScreen}. This list is asserted with a containment
     * check rather than an exact match, so adding the seventh later cannot break this class.
     */
    private static final List<String> LABELS_CITED_BY_NAME = List.of(
            "MAIN-PARA",
            "PROCESS-ENTER-KEY",
            "RETURN-TO-SIGNON-SCREEN",
            "SEND-MENU-SCREEN",
            "POPULATE-HEADER-INFO",
            "BUILD-MENU-OPTIONS");

    /**
     * The production source of the service under test. Surefire runs with the project base directory as its
     * working directory, which is what makes this relative location resolvable from a test; the sibling
     * copybook oracle in {@code com.cardemo.unit.model} relies on the same guarantee.
     */
    private static final Path SERVICE_SOURCE = Path.of(
            "src", "main", "java", "com", "cardemo", "service", "menu", "AdminMenuService.java");

    /** A citation of a source line, as every paragraph method's Javadoc must carry at least one. */
    private static final Pattern SOURCE_LINE_CITATION = Pattern.compile(":L\\d+");

    /** The opening delimiter of a Javadoc block, used to bound a method's own documentation. */
    private static final String JAVADOC_OPEN = "/**";

    /**
     * The instant every clock in this class is fixed at. A literal, so no assertion can depend on the wall
     * clock, the platform time zone or the default locale.
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-06-10T19:27:53Z");

    /** The rendered header this instant must produce, field by field, in the clock's own zone. */
    private static final String EXPECTED_HEADER = "AWS Mainframe Modernization | CardDemo"
            + " | tranid=CA00 | pgmname=COADM01C | date=06/10/22 | time=19:27:53";

    /** The prefix of the one statement that carries the assembled header. */
    private static final String SEND_LOG_PREFIX = "Sending admin menu";

    /** The position of the assembled header within that statement's argument array. */
    private static final int HEADER_ARGUMENT_INDEX = 2;

    /** The name {@code ValidationException} carries for the offending screen field. */
    private static final String OPTION_FIELD_NAME = "option";

    /**
     * The four populated rows of {@code CDEMO-ADMIN-OPTIONS-DATA}, assembled from the transcribed captions
     * and programs above so that a fixture and an expectation can never drift apart. None of these rows is a
     * placeholder, which is the whole point of {@link ThePlaceholderGuardAndItsCleanNotice}.
     */
    private static final List<MenuResponse.AdminMenuOption> SHIPPED_OPTIONS = List.of(
            new MenuResponse.AdminMenuOption(1, SHIPPED_CAPTIONS.get(0), SHIPPED_PROGRAMS.get(0)),
            new MenuResponse.AdminMenuOption(2, SHIPPED_CAPTIONS.get(1), SHIPPED_PROGRAMS.get(1)),
            new MenuResponse.AdminMenuOption(3, SHIPPED_CAPTIONS.get(2), SHIPPED_PROGRAMS.get(2)),
            new MenuResponse.AdminMenuOption(4, SHIPPED_CAPTIONS.get(3), SHIPPED_PROGRAMS.get(3)));

    /**
     * A synthetic slot in the shape {@code app/cbl/COADM01C.cbl:L138} tests for. No shipped row is like
     * this, and none is invented: this fixture exists only inside this test class.
     */
    private static final MenuResponse.AdminMenuOption PLACEHOLDER_OPTION =
            new MenuResponse.AdminMenuOption(1, "Reserved Admin Function            ", "DUMMY01C");

    /** Captures the one debug statement that carries the assembled header. */
    private ListAppender<ILoggingEvent> appender;

    /** The service logger the appender is attached to. */
    private Logger serviceLogger;

    /** The level that logger carried before this test, restored afterwards. */
    private Level restoreLevel;

    /**
     * Attaches a list appender at {@code DEBUG}. {@code POPULATE-HEADER-INFO} has no payload field to write
     * into once the BMS map is gone, so the assembled header exists only as an argument of one debug
     * statement; reading it there keeps the assertion on the value the service computed rather than on a
     * re-rendering of it.
     */
    @BeforeEach
    void attachAppender() {
        this.serviceLogger = (Logger) LoggerFactory.getLogger(AdminMenuService.class);
        this.restoreLevel = this.serviceLogger.getLevel();
        this.serviceLogger.setLevel(Level.DEBUG);
        this.appender = new ListAppender<>();
        this.appender.start();
        this.serviceLogger.addAppender(this.appender);
    }

    /** Detaches the appender and restores the previous level, so no test leaks logging configuration. */
    @AfterEach
    void detachAppender() {
        this.serviceLogger.detachAppender(this.appender);
        this.appender.stop();
        this.serviceLogger.setLevel(this.restoreLevel);
    }

    // 1. The shape of app/cpy/COADM02Y.cpy, and the byte that is missing from it.

    /**
     * The administrator option table as {@code app/cpy/COADM02Y.cpy} declares it: four populated slots of
     * 45 bytes each, and no user-type byte anywhere.
     */
    @Nested
    @DisplayName("1. app/cpy/COADM02Y.cpy: four populated 45-byte slots and no user-type byte")
    class TheAdminOptionTableShape {

        @Test
        @DisplayName("the menu offers exactly the four slots the count field declares populated")
        void theMenuOffersExactlyTheFourPopulatedSlots() {
            assertThat(new AdminMenuService().getMenuScreen().menu().getOptions())
                    .as("app/cpy/COADM02Y.cpy:L20 CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE %d",
                            POPULATED_OPTION_COUNT)
                    .hasSize(POPULATED_OPTION_COUNT);
        }

        @ParameterizedTest(name = "slot {0} carries the caption and program app/cpy/COADM02Y.cpy declares")
        @ValueSource(ints = {1, 2, 3, 4})
        @DisplayName("each populated slot carries the number, caption and program of the copybook row")
        void eachPopulatedSlotCarriesTheCopybookRow(final int slot) {
            final MenuResponse.AdminMenuOption option =
                    new AdminMenuService().getMenuScreen().menu().getOptions().get(slot - 1);

            assertThat(option.optionNumber())
                    .as("CDEMO-ADMIN-OPT-NUM of row %d, in copybook declaration order", slot)
                    .isEqualTo(slot);
            assertThat(option.optionName())
                    .as("CDEMO-ADMIN-OPT-NAME of row %d, padding included", slot)
                    .isEqualTo(SHIPPED_CAPTIONS.get(slot - 1));
            assertThat(option.programName())
                    .as("CDEMO-ADMIN-OPT-PGMNAME of row %d", slot)
                    .isEqualTo(SHIPPED_PROGRAMS.get(slot - 1));
        }

        @Test
        @DisplayName("every caption is carried at its declared PIC X(35) width, unstripped")
        void everyCaptionIsCarriedAtItsDeclaredWidth() {
            for (final MenuResponse.AdminMenuOption option : servedOptions()) {
                assertThat(option.optionName())
                        .as("app/cpy/COADM02Y.cpy:L47 declares PIC X(35); the padding is part of the value")
                        .hasSize(OPTION_NAME_WIDTH);
            }
        }

        @Test
        @DisplayName("every program name fits its declared PIC X(08) width")
        void everyProgramNameFitsItsDeclaredWidth() {
            for (final MenuResponse.AdminMenuOption option : servedOptions()) {
                assertThat(option.programName())
                        .as("app/cpy/COADM02Y.cpy:L48 declares PIC X(08)")
                        .hasSizeLessThanOrEqualTo(PROGRAM_NAME_WIDTH);
            }
        }

        @Test
        @DisplayName("the administrator slot is 45 bytes wide and the main-menu slot 46, so one byte differs")
        void theAdministratorSlotIsOneByteNarrowerThanTheMainMenuSlot() {
            // 2 + 35 + 8 = 45 for app/cpy/COADM02Y.cpy:L46-L48, against 2 + 35 + 8 + 1 = 46 for
            // app/cpy/COMEN02Y.cpy:L89-L92. The extra byte is CDEMO-MENU-OPT-USRTYPE PIC X(01), and its
            // absence here is the contract rather than an omission.
            final int declaredAdminSlotWidth =
                    OPTION_FIELD_WIDTH + OPTION_NAME_WIDTH + PROGRAM_NAME_WIDTH;

            assertThat(declaredAdminSlotWidth)
                    .as("app/cpy/COADM02Y.cpy:L46-L48 declares three sub-fields and no fourth")
                    .isEqualTo(ADMIN_SLOT_WIDTH);
            assertThat(MAIN_SLOT_WIDTH - ADMIN_SLOT_WIDTH)
                    .as("app/cpy/COMEN02Y.cpy:L92 declares the one byte app/cpy/COADM02Y.cpy does not")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the administrator option type declares one component fewer than the main-menu type")
        void theAdministratorOptionTypeDeclaresOneComponentFewer() {
            // The component NAMES belong to com.cardemo.unit.model; what is asserted here is the arity
            // difference, because that is the Java expression of the 45-against-46-byte slot difference and
            // therefore of why this service cannot gate an option on a user class.
            final int adminComponents =
                    MenuResponse.AdminMenuOption.class.getRecordComponents().length;
            final int mainComponents =
                    MenuResponse.MainMenuOption.class.getRecordComponents().length;

            assertThat(mainComponents - adminComponents)
                    .as("the missing component is the user-type byte of app/cpy/COMEN02Y.cpy:L92")
                    .isEqualTo(1);
            assertThat(adminComponents)
                    .as("app/cpy/COADM02Y.cpy:L46-L48 declares exactly three sub-fields")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("no component of the administrator option type names a user type")
        void noComponentOfTheAdministratorOptionTypeNamesAUserType() {
            for (final var component : MenuResponse.AdminMenuOption.class.getRecordComponents()) {
                assertThat(component.getName())
                        .as("app/cpy/COADM02Y.cpy declares no -USRTYPE field to carry")
                        .doesNotContainIgnoringCase("usertype")
                        .doesNotContainIgnoringCase("usrtype");
            }
        }

        @Test
        @DisplayName("the redefined view over-runs the declared data by 225 bytes, which is why 9 is no bound")
        void theRedefinedViewOverRunsTheDeclaredData() {
            // app/cpy/COADM02Y.cpy:L22-L42 declares four slots; :L44-L45 redefines the same storage as nine.
            final int declaredBytes = POPULATED_OPTION_COUNT * ADMIN_SLOT_WIDTH;
            final int redefinedBytes = OCCURS_CAPACITY * ADMIN_SLOT_WIDTH;

            assertThat(declaredBytes).as("four populated slots").isEqualTo(180);
            assertThat(redefinedBytes).as("nine subscripts over the same storage").isEqualTo(405);
            assertThat(redefinedBytes - declaredBytes)
                    .as("subscripts %d through %d address uninitialised storage",
                            POPULATED_OPTION_COUNT + 1, OCCURS_CAPACITY)
                    .isEqualTo(225);
        }

        @Test
        @DisplayName("the option order is the copybook's declaration order and is stable across renders")
        void theOptionOrderIsStableAcrossRenders() {
            final AdminMenuService service = new AdminMenuService();

            assertThat(service.getMenuScreen().menu().getOptions())
                    .as("a menu rendered twice must present the same rows in the same order")
                    .isEqualTo(service.getMenuScreen().menu().getOptions())
                    .extracting(MenuResponse.AdminMenuOption::optionNumber)
                    .containsExactly(1, 2, 3, 4);
        }
    }

    // 2. Blocker: the count field is the bound. The OCCURS arity never is.

    /**
     * The bound applied to a keyed option number. {@code app/cpy/COADM02Y.cpy:L20} populates four slots and
     * {@code :L45} redefines nine, so only the count field is a legitimate bound; the source's own display
     * loop agrees, bounding at {@code app/cbl/COADM01C.cbl:L229} on {@code CDEMO-ADMIN-OPT-COUNT}.
     */
    @Nested
    @DisplayName("2. Blocker: the bound is CDEMO-ADMIN-OPT-COUNT, never the OCCURS 9 arity")
    class TheCountFieldIsTheOnlyBound {

        @ParameterizedTest(name = "subscript {0} lies in the over-redefined region and is refused")
        @ValueSource(strings = {"5", "6", "7", "8", "9"})
        @DisplayName("each subscript from 5 through 9 is refused rather than resolved from spare storage")
        void eachSubscriptBeyondTheCountIsRefused(final String subscript) {
            final AdminMenuService service = new AdminMenuService();

            MenuServiceTestSupport.assertInvalidOption(() -> service.selectOption(subscript),
                    EXPECTED_INVALID_OPTION_MESSAGE, OPTION_FIELD_NAME);
        }

        @Test
        @DisplayName("option 4 is accepted and option 5 refused, so the boundary sits exactly at the count")
        void theBoundarySitsExactlyAtTheCount() {
            final AdminMenuService service = new AdminMenuService();

            final AdminMenuService.AdminMenuSelection lastAccepted =
                    service.selectOption(String.valueOf(POPULATED_OPTION_COUNT));
            assertThat(lastAccepted.optionNumber())
                    .as("app/cbl/COADM01C.cbl:L128 refuses only what is strictly greater than the count")
                    .isEqualTo(POPULATED_OPTION_COUNT);
            assertThat(lastAccepted.targetProgram())
                    .as("the fourth populated row of app/cpy/COADM02Y.cpy")
                    .isEqualTo(SHIPPED_PROGRAMS.get(POPULATED_OPTION_COUNT - 1));

            MenuServiceTestSupport.assertInvalidOption(
                    () -> service.selectOption(String.valueOf(POPULATED_OPTION_COUNT + 1)),
                    EXPECTED_INVALID_OPTION_MESSAGE, OPTION_FIELD_NAME);
        }

        @Test
        @DisplayName("the refusal is a ValidationException with the exact literal, no cause and no target")
        void theRefusalCarriesTheExactLiteralAndNoCause() {
            // Labelled deviation. SEND-MENU-SCREEN at app/cbl/COADM01C.cbl:L172-L184 ends at
            // EXEC CICS SEND MAP with no EXEC CICS RETURN, so the source falls through a bounds-check
            // failure into the guard at :L138, which subscripts the over-redefined region. Java refuses
            // before any element access, because reading uninitialised storage has no defined semantics
            // here. Held as DL-DV-10, boundary (3), in DECISION_LOG.md.
            final AdminMenuService service = new AdminMenuService();

            final Throwable refusal = catchThrowable(() -> service.selectOption("9"));

            assertThat(refusal)
                    .as("app/cbl/COADM01C.cbl:L130-L131 sets the error flag and this message, nothing else")
                    .isExactlyInstanceOf(ValidationException.class)
                    .isInstanceOf(CardDemoException.class)
                    .hasMessage(EXPECTED_INVALID_OPTION_MESSAGE)
                    .hasNoCause();
            assertThat(((ValidationException) refusal).getFailureKind())
                    .as("a keyed value that is present but unusable is INVALID, not BLANK")
                    .isEqualTo(ValidationException.FailureKind.INVALID);
            assertThat(refusal.getMessage())
                    .as("no option name or program name may leak from the over-redefined region")
                    .doesNotContain(SHIPPED_PROGRAMS)
                    .doesNotContain(PLACEHOLDER_PREFIX);
        }

        @Test
        @DisplayName("no over-range subscript resolves a target program, so option-record resolution is never reached")
        void noOverRangeSubscriptResolvesATargetProgram() {
            final AdminMenuService service = new AdminMenuService();

            for (int subscript = POPULATED_OPTION_COUNT + 1; subscript <= OCCURS_CAPACITY; subscript++) {
                final String keyed = String.valueOf(subscript);
                final Throwable refusal = catchThrowable(() -> service.selectOption(keyed));

                assertThat(refusal)
                        .as("subscript %d must be refused, never resolved", subscript)
                        .isInstanceOf(ValidationException.class);
            }
        }

        @Test
        @DisplayName("the declared capacity is 9 and the populated count 4, and only the count bounds a keying")
        void onlyThePopulatedCountBoundsAKeying() {
            assertThat(MenuResponse.MenuType.ADMIN.getPopulatedOptionCount())
                    .as("app/cpy/COADM02Y.cpy:L20 CDEMO-ADMIN-OPT-COUNT VALUE 4")
                    .isEqualTo(POPULATED_OPTION_COUNT);
            assertThat(MenuResponse.MenuType.ADMIN.getDeclaredCapacity())
                    .as("app/cpy/COADM02Y.cpy:L45 CDEMO-ADMIN-OPT OCCURS 9 TIMES")
                    .isEqualTo(OCCURS_CAPACITY);
            assertThat(MenuResponse.MenuType.ADMIN.getDeclaredCapacity())
                    .as("the arity is larger than the bound, which is precisely the hazard")
                    .isGreaterThan(MenuResponse.MenuType.ADMIN.getPopulatedOptionCount());
        }

        @Test
        @DisplayName("the served option count is read from the count field, not inferred from a capacity")
        void theServedOptionCountIsReadFromTheCountField() {
            final MenuResponse<MenuResponse.AdminMenuOption> menu =
                    new AdminMenuService().getMenuScreen().menu();

            assertThat(menu.getOptionCount())
                    .as("app/cbl/COADM01C.cbl:L229 bounds its display loop on the count field")
                    .isEqualTo(POPULATED_OPTION_COUNT)
                    .isEqualTo(menu.getOptions().size())
                    .isNotEqualTo(OCCURS_CAPACITY);
        }

        @Test
        @DisplayName("the bound tracks the supplied table, proving it is the count and not a hardcoded four")
        void theBoundTracksTheSuppliedTable() {
            final List<MenuResponse.AdminMenuOption> twoRows =
                    List.of(SHIPPED_OPTIONS.get(0), SHIPPED_OPTIONS.get(1));
            final AdminMenuService service = construct(fixedClock(), twoRows);

            assertThat(service.selectOption("2").targetProgram())
                    .as("the second of two supplied rows remains reachable")
                    .isEqualTo(SHIPPED_PROGRAMS.get(1));
            MenuServiceTestSupport.assertInvalidOption(() -> service.selectOption("3"),
                    EXPECTED_INVALID_OPTION_MESSAGE, OPTION_FIELD_NAME);
        }

        @Test
        @DisplayName("a table longer than the populated count is refused, and the refusal denies the arity as a bound")
        void aTableLongerThanThePopulatedCountIsRefused() {
            final List<MenuResponse.AdminMenuOption> fiveRows = new ArrayList<>(SHIPPED_OPTIONS);
            fiveRows.add(PLACEHOLDER_OPTION);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the fifth subscript addresses the 225 bytes app/cpy/COADM02Y.cpy never populates")
                    .isThrownBy(() -> construct(fixedClock(), fiveRows))
                    .withMessageContaining(String.valueOf(fiveRows.size()))
                    .withMessageContaining(String.valueOf(POPULATED_OPTION_COUNT))
                    .withMessageContaining("app/cpy/COADM02Y.cpy:L20")
                    .withMessageContaining("OCCURS 9 capacity at :L45 is not a valid bound");
        }
    }

    // 3. PROCESS-ENTER-KEY: the JUST RIGHT field and the space-to-zero substitution.

    /**
     * Normalisation of the keyed selection, {@code app/cbl/COADM01C.cbl:L117-L125}: a backward scan from the
     * declared field width, a {@code PIC X(02) JUST RIGHT} move at {@code :L45}, an
     * {@code INSPECT ... REPLACING ALL ' ' BY '0'} at {@code :L123}, and a move into {@code PIC 9(02)} at
     * {@code :L46}. The idiom is character for character the one {@code COMEN01C} uses, and it is asserted
     * here against this program's own locators rather than shared with it.
     */
    @Nested
    @DisplayName("3. PROCESS-ENTER-KEY normalises the keyed field before it bounds it")
    class TheKeyedFieldNormalisation {

        @ParameterizedTest(name = "the keyed field [{0}] normalises to option 2")
        @ValueSource(strings = {"2", " 2", "02", "2 "})
        @DisplayName("bare, space-padded and zero-padded keyings all resolve to the same option")
        void everyKeyingOfTwoResolvesToOptionTwo(final String keyed) {
            // @ValueSource is used rather than a comma-separated annotation fixture precisely because the
            // latter trims leading and trailing white space by default, which would destroy [ 2] and [2 ]
            // and silently reduce this to three copies of the same case.
            final AdminMenuService.AdminMenuSelection selection =
                    new AdminMenuService().selectOption(keyed);

            assertThat(selection.optionNumber())
                    .as("app/cbl/COADM01C.cbl:L122-L125 right-justifies then substitutes zeros for spaces")
                    .isEqualTo(2);
            assertThat(selection.targetProgram())
                    .as("the second populated row of app/cpy/COADM02Y.cpy")
                    .isEqualTo(SHIPPED_PROGRAMS.get(1));
            assertThat(selection.optionName())
                    .as("the caption travels at its full declared width")
                    .isEqualTo(SHIPPED_CAPTIONS.get(1));
            assertThat(selection.message())
                    .as("app/cbl/COADM01C.cbl:L143 transfers away, so no notice is assembled")
                    .isEmpty();
            assertThat(selection.comingSoon())
                    .as("a shipped target is not a placeholder")
                    .isFalse();
        }

        @Test
        @DisplayName("a bare and a zero-padded keying are indistinguishable, which is what JUST RIGHT means")
        void aBareAndAZeroPaddedKeyingAreIndistinguishable() {
            final AdminMenuService service = new AdminMenuService();

            assertThat(service.selectOption("3"))
                    .as("app/cbl/COADM01C.cbl:L45 WS-OPTION-X PIC X(02) JUST RIGHT")
                    .isEqualTo(service.selectOption("03"));
        }

        @ParameterizedTest(name = "the all-blank keying [{0}] normalises to zero and is refused")
        @ValueSource(strings = {"", " ", "  "})
        @DisplayName("an unkeyed field becomes 00 and is refused by the ZEROS limb")
        void anUnkeyedFieldIsRefusedByTheZerosLimb(final String keyed) {
            // The scan at :L117-L121 stops at position one unconditionally, so an all-blank field yields a
            // single space, which :L123 turns into '0' and :L124 into a numeric zero. :L129 WS-OPTION =
            // ZEROS then refuses it. This is the only path on which a blank field is rejected: it never
            // reaches the count comparison.
            final AdminMenuService service = new AdminMenuService();

            MenuServiceTestSupport.assertInvalidOption(() -> service.selectOption(keyed),
                    EXPECTED_INVALID_OPTION_MESSAGE, OPTION_FIELD_NAME);
        }

        @Test
        @DisplayName("an explicit zero is refused by the same ZEROS limb as a blank field")
        void anExplicitZeroIsRefusedByTheZerosLimb() {
            final AdminMenuService service = new AdminMenuService();

            MenuServiceTestSupport.assertInvalidOption(() -> service.selectOption("0"),
                    EXPECTED_INVALID_OPTION_MESSAGE, OPTION_FIELD_NAME);
            MenuServiceTestSupport.assertInvalidOption(() -> service.selectOption("00"),
                    EXPECTED_INVALID_OPTION_MESSAGE, OPTION_FIELD_NAME);
        }

        @Test
        @DisplayName("a letter in the field is refused by the IS NOT NUMERIC limb, before any comparison")
        void aLetterInTheFieldIsRefusedByTheNotNumericLimb() {
            final AdminMenuService service = new AdminMenuService();

            // app/cbl/COADM01C.cbl:L127 is the first disjunct, so it decides before :L128 compares a
            // value that a non-numeric field does not have.
            MenuServiceTestSupport.assertInvalidOption(() -> service.selectOption("A1"),
                    EXPECTED_INVALID_OPTION_MESSAGE, OPTION_FIELD_NAME);
            MenuServiceTestSupport.assertInvalidOption(() -> service.selectOption("1A"),
                    EXPECTED_INVALID_OPTION_MESSAGE, OPTION_FIELD_NAME);
        }

        @Test
        @DisplayName("a keying above the count is refused by the count limb, not by the numeric limb")
        void aKeyingAboveTheCountIsRefusedByTheCountLimb() {
            final AdminMenuService service = new AdminMenuService();

            MenuServiceTestSupport.assertInvalidOption(() -> service.selectOption("5"),
                    EXPECTED_INVALID_OPTION_MESSAGE, OPTION_FIELD_NAME);
        }

        @Test
        @DisplayName("the refusal names the option field and classifies the failure as INVALID, not BLANK")
        void theRefusalNamesTheOptionFieldAndClassifiesItInvalid() {
            final AdminMenuService service = new AdminMenuService();

            final ValidationException refusal =
                    MenuServiceTestSupport.catchValidation(() -> service.selectOption("5"));

            assertThat(refusal.hasFieldName())
                    .as("the refusal must be attributable to a screen field")
                    .isTrue();
            assertThat(refusal.getFieldName())
                    .as("OPTIONI of app/cpy-bms/COADM01.CPY:L132 is the only field this program reads")
                    .isEqualTo(OPTION_FIELD_NAME);
            assertThat(refusal.getFailureKind())
                    .as("app/cbl/COADM01C.cbl:L131 reports an unusable value, not an absent one")
                    .isEqualTo(ValidationException.FailureKind.INVALID);
            assertThat(refusal.getMessage())
                    .as("app/cbl/COADM01C.cbl:L131-L132, three full stops and no trailing space")
                    .isEqualTo(EXPECTED_INVALID_OPTION_MESSAGE)
                    .endsWith("...")
                    .doesNotEndWith(" ");
        }

        @Test
        @DisplayName("every populated option is reachable by its own keying, in both keyed forms")
        void everyPopulatedOptionIsReachableByItsOwnKeying() {
            final AdminMenuService service = new AdminMenuService();

            for (int option = 1; option <= POPULATED_OPTION_COUNT; option++) {
                final String bare = String.valueOf(option);
                final String padded = "0" + option;

                assertThat(service.selectOption(bare).targetProgram())
                        .as("option %d keyed bare", option)
                        .isEqualTo(SHIPPED_PROGRAMS.get(option - 1));
                assertThat(service.selectOption(padded).targetProgram())
                        .as("option %d keyed zero-padded", option)
                        .isEqualTo(SHIPPED_PROGRAMS.get(option - 1));
            }
        }
    }

    // 4. Hostile input. Every value below is refused, and nothing is echoed back.

    /**
     * Untrusted input reaching {@code selectOption}. {@code OPTIONI} is a two-character screen field, so
     * anything wider, anything non-numeric and anything outside {@code 1..4} must be refused with the one
     * literal {@code app/cbl/COADM01C.cbl:L131-L132} defines, and with nothing of the keyed content in it.
     */
    @Nested
    @DisplayName("4. hostile input is refused with one literal and no echo of what was keyed")
    class HostileInputIsRefused {

        @ParameterizedTest(name = "the keyed value [{0}] is refused")
        @ValueSource(strings = {"", " ", "  ", "0", "00", "5", "9", "99", "-1", "A1", "1A", "ZZ",
            "\t2", "+1", ".1", "\u0661\u0662"})
        @DisplayName("each hostile keying is refused as a ValidationException carrying the exact literal")
        void eachHostileKeyingIsRefused(final String keyed) {
            // The last fixture is two Arabic-Indic digits, included deliberately: Character.isDigit
            // accepts them, a PIC 9(02) field cannot hold them, and app/cbl/COADM01C.cbl:L127 must
            // therefore reject them. That is why the production numeric test compares against '0' and '9'
            // rather than delegating to Character.isDigit.
            final AdminMenuService service = new AdminMenuService();

            final Throwable refusal = catchThrowable(() -> service.selectOption(keyed));

            assertThat(refusal)
                    .as("app/cbl/COADM01C.cbl:L127-L131 refuses every one of these")
                    .isExactlyInstanceOf(ValidationException.class)
                    .isInstanceOf(CardDemoException.class)
                    .hasMessage(EXPECTED_INVALID_OPTION_MESSAGE)
                    .hasNoCause();
        }

        @Test
        @DisplayName("every C0 control character embedded in the field is refused")
        void everyEmbeddedControlCharacterIsRefused() {
            // The fixtures are built here rather than declared as annotation arguments so that no control
            // character ever reaches a test display name or a report file. The space is skipped because a
            // field of two spaces is the unkeyed case, refused on the ZEROS limb instead and covered above.
            final AdminMenuService service = new AdminMenuService();

            for (char control = '\u0000'; control <= '\u001F'; control++) {
                final String leading = control + "2";
                final String trailing = "2" + control;

                assertThat(catchThrowable(() -> service.selectOption(leading)))
                        .as("a control character at position one must be refused, code point %d",
                                (int) control)
                        .isExactlyInstanceOf(ValidationException.class)
                        .hasMessage(EXPECTED_INVALID_OPTION_MESSAGE);
                assertThat(catchThrowable(() -> service.selectOption(trailing)))
                        .as("a control character at position two must be refused, code point %d",
                                (int) control)
                        .isExactlyInstanceOf(ValidationException.class)
                        .hasMessage(EXPECTED_INVALID_OPTION_MESSAGE);
            }
        }

        @Test
        @DisplayName("an absent field is treated as the spaces a terminal would have delivered, then refused")
        void anAbsentFieldIsTreatedAsSpacesAndRefused() {
            // app/cbl/COADM01C.cbl has no null: an unkeyed 3270 field arrives as spaces. Java's third way
            // of being unset is therefore mapped onto that same path rather than onto an unhandled failure.
            final AdminMenuService service = new AdminMenuService();

            final Throwable refusal = catchThrowable(() -> service.selectOption(null));

            assertThat(refusal)
                    .as("a null selection must be refused as an invalid option, never as a NullPointerException")
                    .isExactlyInstanceOf(ValidationException.class)
                    .hasMessage(EXPECTED_INVALID_OPTION_MESSAGE)
                    .hasNoCause();
        }

        @ParameterizedTest(name = "the over-width keying [{0}] is refused before normalisation")
        @ValueSource(strings = {"100", "004", "  4", "4  ", "0000000004"})
        @DisplayName("a keying wider than the declared PIC X(2) field is refused, not truncated")
        void anOverWidthKeyingIsRefusedRatherThanTruncated(final String keyed) {
            // Truncating would have made [004] resolve to option 4, admitting input the two-character
            // field could never have carried.
            final AdminMenuService service = new AdminMenuService();

            MenuServiceTestSupport.assertInvalidOption(() -> service.selectOption(keyed),
                    EXPECTED_INVALID_OPTION_MESSAGE, OPTION_FIELD_NAME);
        }

        @Test
        @DisplayName("the refusal message never echoes the keyed content back to the caller")
        void theRefusalMessageNeverEchoesTheKeyedContent() {
            final AdminMenuService service = new AdminMenuService();

            final ValidationException refusal =
                    MenuServiceTestSupport.catchValidation(() -> service.selectOption("ZZ"));

            assertThat(refusal.getMessage())
                    .as("app/cbl/COADM01C.cbl:L131 moves a fixed literal; it interpolates nothing")
                    .isEqualTo(EXPECTED_INVALID_OPTION_MESSAGE)
                    .doesNotContain("ZZ");
        }

        @Test
        @DisplayName("a refused keying leaves the menu itself unchanged, so no state carries into the next turn")
        void aRefusedKeyingLeavesTheMenuUnchanged() {
            final AdminMenuService service = construct(fixedClock(), SHIPPED_OPTIONS);
            final AdminMenuService.AdminMenuView before = service.getMenuScreen();

            MenuServiceTestSupport.assertInvalidOption(() -> service.selectOption("9"),
                    EXPECTED_INVALID_OPTION_MESSAGE, OPTION_FIELD_NAME);

            assertThat(service.getMenuScreen())
                    .as("the legacy WS-ERR-FLG lived in working storage; its Java counterpart is method-local")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("the sign-on fall-back treats every unset form of the requested program identically")
        void theSignOnFallBackTreatsEveryUnsetFormIdentically() {
            final AdminMenuService service = new AdminMenuService();

            assertThat(service.signOnProgram(null))
                    .as("app/cbl/COADM01C.cbl:L162-L163, the LOW-VALUES arm")
                    .isEqualTo(EXPECTED_SIGN_ON_PROGRAM);
            assertThat(service.signOnProgram(""))
                    .as("app/cbl/COADM01C.cbl:L162-L163, the SPACES arm at zero length")
                    .isEqualTo(EXPECTED_SIGN_ON_PROGRAM);
            assertThat(service.signOnProgram("     "))
                    .as("app/cbl/COADM01C.cbl:L162-L163, the SPACES arm at full width")
                    .isEqualTo(EXPECTED_SIGN_ON_PROGRAM);
            assertThat(service.signOnProgram("COADM01C"))
                    .as("a requested target that carries a value is returned unchanged")
                    .isEqualTo(EXPECTED_PROGRAM_NAME);
        }
    }

    // 5. The placeholder guard at :L138, and the notice at :L149-L153 which is CLEAN here.

    /**
     * The guard at {@code app/cbl/COADM01C.cbl:L138} and the notice it leads to at {@code :L149-L153}.
     *
     * <p>Two corpus facts govern this group. First, the guard exists in <strong>both</strong> menu programs -
     * {@code app/cbl/COADM01C.cbl:L138} and {@code app/cbl/COMEN01C.cbl:L146} - so attributing it to the
     * administrator program alone is wrong. Second, the notice assembled here is clean, because
     * {@code :L150-L151} carry an asterisk in column seven and contribute nothing, whereas
     * {@code app/cbl/COMEN01C.cbl:L161} splices its caption in live and {@code DELIMITED BY SPACE}. The two
     * literals are therefore different strings and are never unified.</p>
     */
    @Nested
    @DisplayName("5. the DUMMY guard, and the clean notice that is not the main menu's spliced one")
    class ThePlaceholderGuardAndItsCleanNotice {

        @Test
        @DisplayName("no shipped program name begins with the guard literal, so the guard is never taken")
        void noShippedProgramNameBeginsWithTheGuardLiteral() {
            assertThat(SHIPPED_PROGRAMS)
                    .as("app/cpy/COADM02Y.cpy:L27, :L32, :L37 and :L42 ship four real security screens")
                    .allSatisfy(program -> assertThat(program).doesNotStartWith(PLACEHOLDER_PREFIX));
            assertThat(PLACEHOLDER_PREFIX)
                    .as("the reference modification at app/cbl/COADM01C.cbl:L138 is (1:5)")
                    .hasSize(PLACEHOLDER_PREFIX_LENGTH);
        }

        @ParameterizedTest(name = "shipped option {0} dispatches with no notice")
        @ValueSource(ints = {1, 2, 3, 4})
        @DisplayName("a successful dispatch carries no notice, in the response or in the log")
        void aSuccessfulDispatchCarriesNoNotice(final int option) {
            // High. EXEC CICS XCTL at app/cbl/COADM01C.cbl:L143 never returns, so :L147-L154 is
            // unreachable from a real target. A Java call would return, so the transfer is modelled as
            // terminal; without that, every valid selection would be answered "coming soon".
            final AdminMenuService service = construct(fixedClock(), SHIPPED_OPTIONS);

            final AdminMenuService.AdminMenuSelection selection =
                    service.selectOption(String.valueOf(option));

            assertThat(selection.comingSoon())
                    .as("app/cpy/COADM02Y.cpy ships no placeholder, so this flag can never be set")
                    .isFalse();
            assertThat(selection.message())
                    .as("app/cbl/COADM01C.cbl:L147-L153 is not reached from a real target")
                    .isEmpty();
            assertThat(selection.targetProgram())
                    .as("the transfer target of app/cbl/COADM01C.cbl:L143")
                    .isEqualTo(SHIPPED_PROGRAMS.get(option - 1));
            assertThat(renderedLogMessages())
                    .as("the notice must not leak through the diagnostic path either")
                    .noneMatch(logged -> logged.contains(EXPECTED_COMING_SOON_MESSAGE));
        }

        @Test
        @DisplayName("a synthetic placeholder slot yields the clean notice, returned rather than thrown")
        void aSyntheticPlaceholderSlotYieldsTheCleanNotice() {
            // The slot is synthetic and lives only here: no DUMMY-prefixed production row is invented.
            final AdminMenuService service = construct(fixedClock(), List.of(PLACEHOLDER_OPTION));

            assertThat(catchThrowable(() -> service.selectOption("1")))
                    .as("app/cbl/COADM01C.cbl:L148 MOVE DFHGREEN TO ERRMSGC colours this informational,"
                            + " so its Java counterpart is a returned value and not a raised failure")
                    .isNull();

            final AdminMenuService.AdminMenuSelection selection = service.selectOption("1");

            assertThat(selection.message())
                    .as("app/cbl/COADM01C.cbl:L149 and :L152 are the only live STRING operands")
                    .isEqualTo(EXPECTED_COMING_SOON_MESSAGE);
            assertThat(selection.comingSoon())
                    .as("the green, informational outcome is flagged rather than signalled")
                    .isTrue();
            assertThat(selection.targetProgram())
                    .as("app/cbl/COADM01C.cbl:L147 blanks the transfer, because no transfer happens")
                    .isEmpty();
            assertThat(selection.optionNumber())
                    .as("the option the operator keyed is still reported back")
                    .isEqualTo(1);
            assertThat(selection.optionName())
                    .as("the caption travels at its declared width even on the notice path")
                    .isEqualTo(PLACEHOLDER_OPTION.optionName());
        }

        @Test
        @DisplayName("the clean notice is exactly the thirty characters two live STRING operands produce")
        void theCleanNoticeIsExactlyThirtyCharacters() {
            final AdminMenuService service = construct(fixedClock(), List.of(PLACEHOLDER_OPTION));

            assertThat(service.selectOption("1").message())
                    .as("'This option ' is twelve characters and 'is coming soon ...' eighteen")
                    .isEqualTo(EXPECTED_COMING_SOON_MESSAGE)
                    .hasSize(30)
                    .startsWith("This option ")
                    .endsWith("is coming soon ...")
                    .contains("option is");
        }

        @Test
        @DisplayName("the clean notice is not the main menu's spliced form, and the two must not be unified")
        void theCleanNoticeIsNotTheMainMenuSplicedForm() {
            // Medium. app/cbl/COMEN01C.cbl:L161 keeps its middle operand live and DELIMITED BY SPACE, so
            // 'Account View' at app/cpy/COMEN02Y.cpy:L26 contributes 'Account' and the assembled text has
            // no space before 'is'. app/cbl/COADM01C.cbl:L150-L151 are commented out, so this one does.
            final AdminMenuService service = construct(fixedClock(), List.of(PLACEHOLDER_OPTION));

            final String notice = service.selectOption("1").message();

            assertThat(notice)
                    .as("the two programs produce different strings and neither may be substituted"
                            + " for the other")
                    .isNotEqualTo(MAIN_MENU_SPLICED_MESSAGE);
            assertThat(notice)
                    .as("no caption is spliced into the administrator notice")
                    .doesNotContain(PLACEHOLDER_OPTION.optionName().strip())
                    .doesNotContain("Account");
            assertThat(MAIN_MENU_SPLICED_MESSAGE)
                    .as("for contrast: the main menu's form loses the space its delimiter consumed")
                    .doesNotContain("option is");
        }

        @Test
        @DisplayName("the guard compares five characters, case-sensitively, anchored at position one")
        void theGuardComparesFiveCharactersCaseSensitivelyAtPositionOne() {
            assertThat(noticeFor("DUMMY"))
                    .as("a name of exactly the prefix matches, because (1:5) is its whole extent")
                    .isEqualTo(EXPECTED_COMING_SOON_MESSAGE);
            assertThat(noticeFor("DUMMY01C"))
                    .as("a longer name whose first five characters are the prefix also matches")
                    .isEqualTo(EXPECTED_COMING_SOON_MESSAGE);
            assertThat(noticeFor("DUMM"))
                    .as("four characters cannot fill a five-character comparison, so it dispatches")
                    .isEmpty();
            assertThat(noticeFor("dummy01c"))
                    .as("app/cbl/COADM01C.cbl:L138 compares bytes; it folds no case")
                    .isEmpty();
            assertThat(noticeFor("XDUMMY01"))
                    .as("the comparison is anchored at position one, so an offset prefix dispatches")
                    .isEmpty();
            assertThat(noticeFor("DUMYM01C"))
                    .as("a transposition inside the first five characters does not match")
                    .isEmpty();
        }

        @Test
        @DisplayName("a mixed table routes each option independently, with no cross-contamination")
        void aMixedTableRoutesEachOptionIndependently() {
            final List<MenuResponse.AdminMenuOption> mixed = List.of(
                    SHIPPED_OPTIONS.get(0),
                    new MenuResponse.AdminMenuOption(2, "Reserved Admin Function            ", "DUMMY02C"),
                    SHIPPED_OPTIONS.get(2));
            final AdminMenuService service = construct(fixedClock(), mixed);

            assertThat(service.selectOption("1").comingSoon())
                    .as("a real target before a placeholder is unaffected by it")
                    .isFalse();
            assertThat(service.selectOption("2").message())
                    .as("the placeholder in the middle still yields the clean notice")
                    .isEqualTo(EXPECTED_COMING_SOON_MESSAGE);
            assertThat(service.selectOption("3").targetProgram())
                    .as("a real target after a placeholder is unaffected by it")
                    .isEqualTo(SHIPPED_PROGRAMS.get(2));
        }

        /**
         * Resolves the notice a synthetic target program name produces, or an empty string when the guard
         * does not take and the option dispatches instead.
         *
         * @param programName the synthetic {@code CDEMO-ADMIN-OPT-PGMNAME} to probe with
         * @return the assembled notice, or an empty string
         */
        private String noticeFor(final String programName) {
            final MenuResponse.AdminMenuOption probe =
                    new MenuResponse.AdminMenuOption(1, "Reserved Admin Function            ", programName);
            return construct(fixedClock(), List.of(probe)).selectOption("1").message();
        }
    }

    // 6. Paragraph correspondence: seven labels, seven private methods, no consolidation.

    /**
     * The structural contract. {@code app/cbl/COADM01C.cbl} is 268 lines and declares seven paragraph
     * labels, and each maps to exactly one private method. The mapping is asserted rather than assumed
     * because the scope-coverage gate reads the paragraph map, and a consolidated paragraph would make that
     * map unprovable.
     */
    @Nested
    @DisplayName("6. seven paragraph labels map to seven private methods, one for one")
    class TheParagraphMapIsComplete {

        @Test
        @DisplayName("the service declares exactly one private method per source paragraph and no eighth")
        void theServiceDeclaresExactlyOnePrivateMethodPerParagraph() {
            assertThat(declaredMethodNames(Modifier::isPrivate))
                    .as("MAIN-PARA :L75, PROCESS-ENTER-KEY :L115, RETURN-TO-SIGNON-SCREEN :L160,"
                            + " SEND-MENU-SCREEN :L172, RECEIVE-MENU-SCREEN :L189, POPULATE-HEADER-INFO"
                            + " :L202 and BUILD-MENU-OPTIONS :L226")
                    .containsExactlyInAnyOrderElementsOf(PARAGRAPH_METHOD_NAMES)
                    .hasSize(PARAGRAPH_METHOD_NAMES.size());
        }

        @Test
        @DisplayName("no paragraph method is static, so none can hold state between requests")
        void noParagraphMethodIsStatic() {
            for (final Method method : paragraphMethods()) {
                assertThat(Modifier.isStatic(method.getModifiers()))
                        .as("%s corresponds to a paragraph that read instance state, not global state",
                                method.getName())
                        .isFalse();
                assertThat(isPackagePrivate(method))
                        .as("%s is private, so it is neither package-visible nor public", method.getName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("every paragraph method documents at least one source line citation of its own")
        void everyParagraphMethodCitesASourceLine() {
            final List<String> sourceLines = serviceSourceLines();

            for (final String methodName : PARAGRAPH_METHOD_NAMES) {
                final String javadoc = javadocPreceding(sourceLines, methodName);

                assertThat(SOURCE_LINE_CITATION.matcher(javadoc).find())
                        .as("%s must cite the paragraph line it reproduces, so the paragraph map is"
                                + " provable by reading the code rather than by trusting a summary",
                                methodName)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("the production source names its originating program and six of the seven labels")
        void theProductionSourceNamesItsOriginatingProgram() {
            // Low, recorded rather than corrected because src/main is owned elsewhere: RECEIVE-MENU-SCREEN
            // is cited by line reference instead of by label. Remediation is to name the label in the
            // Javadoc of receiveMenuScreen. A containment check is used deliberately, so that adding the
            // seventh label later strengthens the source without breaking this assertion.
            final String source = String.join("\n", serviceSourceLines());

            assertThat(source)
                    .as("the Apache-2.0 banner convention names the originating COBOL artefact")
                    .contains("app/cbl/COADM01C.cbl")
                    .contains("app/cpy/COADM02Y.cpy");
            assertThat(PARAGRAPH_LABELS)
                    .as("the six labels below are named verbatim; the seventh is the Low finding above")
                    .containsAll(LABELS_CITED_BY_NAME)
                    .hasSize(PARAGRAPH_METHOD_NAMES.size());
            for (final String label : LABELS_CITED_BY_NAME) {
                assertThat(source)
                        .as("the paragraph label %s must be traceable by name", label)
                        .contains(label);
            }
        }

        @Test
        @DisplayName("the public surface is exactly the three entry points the controller layer calls")
        void thePublicSurfaceIsExactlyThreeEntryPoints() {
            assertThat(declaredMethodNames(Modifier::isPublic))
                    .as("getMenuScreen for the first send, selectOption for PROCESS-ENTER-KEY and"
                            + " signOnProgram for RETURN-TO-SIGNON-SCREEN; nothing else is exposed")
                    .containsExactlyInAnyOrderElementsOf(PUBLIC_METHOD_NAMES);
        }

        @Test
        @DisplayName("no method is protected, so no subclass can reach a paragraph")
        void noMethodIsProtected() {
            assertThat(declaredMethodNames(Modifier::isProtected))
                    .as("the paragraph map is an implementation detail, not an extension point")
                    .isEmpty();
        }

        @Test
        @DisplayName("the construction seam is package-private, reachable by a test and by nothing else")
        void theConstructionSeamIsPackagePrivate() {
            final Constructor<?> seam = MenuServiceTestSupport.seamConstructor(AdminMenuService.class,
                    "the placeholder branch at app/cbl/COADM01C.cbl:L146-L154 becomes unreachable and"
                            + " therefore unassertable");

            assertThat(isPackagePrivate(seam))
                    .as("a public seam would widen the production surface; a private one would be"
                            + " unreachable without breaking encapsulation")
                    .isTrue();
            assertThat(seam.getParameterTypes())
                    .as("a clock for POPULATE-HEADER-INFO and an option table for BUILD-MENU-OPTIONS")
                    .containsExactly(Clock.class, List.class);
        }

        @Test
        @DisplayName("every instance field is final, so the legacy working-storage flags cannot come back")
        void everyInstanceFieldIsFinal() {
            for (final Field field : AdminMenuService.class.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("%s must be final; WS-ERR-FLG and WS-OPTION are method-local in Java",
                                field.getName())
                        .isTrue();
                assertThat(Modifier.isPublic(field.getModifiers()))
                        .as("%s must not be publicly readable", field.getName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("no static field is mutable, so two requests cannot interfere through class state")
        void noStaticFieldIsMutable() {
            for (final Field field : AdminMenuService.class.getDeclaredFields()) {
                if (field.isSynthetic() || !Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("%s is static and must therefore be final", field.getName())
                        .isTrue();
            }
        }
    }

    // 7. Least privilege. The table has no user-type byte, so authorisation lives at the endpoint.

    /**
     * Where authorisation for this surface lives. {@code app/cpy/COADM02Y.cpy} declares no
     * {@code -USRTYPE} field, so unlike {@code app/cbl/COMEN01C.cbl:L137} this program has nothing to gate
     * an individual option on. Every shipped target is a user-administration screen, which makes a single
     * rule over the whole surface both sufficient and correct.
     *
     * <p><strong>Not available in this tier.</strong> That the surface is restricted to the administrator
     * role and that the session policy is stateless are properties of the security filter chain, not of
     * this bean; they are owned by the web-layer configuration and its own tests. Asserting them requires a
     * Spring application context, which this pure-JVM tier deliberately does not build. What is asserted
     * here is the half that is assertable: that this service holds no authorisation surface at all, so
     * nothing can silently duplicate or contradict the endpoint rule.</p>
     */
    @Nested
    @DisplayName("7. least privilege: the service holds no authorisation surface, by construction")
    class TheServiceHoldsNoAuthorisationSurface {

        @Test
        @DisplayName("no member of the service mentions a user type, because the copybook has no such field")
        void noMemberOfTheServiceMentionsAUserType() {
            for (final Method method : AdminMenuService.class.getDeclaredMethods()) {
                if (method.isSynthetic()) {
                    continue;
                }
                assertThat(method.getReturnType())
                        .as("%s must not return a user classification", method.getName())
                        .isNotEqualTo(UserType.class);
                assertThat(method.getParameterTypes())
                        .as("%s must not accept a user classification", method.getName())
                        .doesNotContain(UserType.class);
            }
            for (final Field field : AdminMenuService.class.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                assertThat(field.getType())
                        .as("%s must not hold a user classification", field.getName())
                        .isNotEqualTo(UserType.class);
            }
        }

        @Test
        @DisplayName("the main-menu option carries a user type and the administrator option cannot")
        void theMainMenuOptionCarriesAUserTypeAndTheAdministratorOptionCannot() {
            // The contrast is the evidence: app/cpy/COMEN02Y.cpy:L92 declares the byte that makes the gate
            // at app/cbl/COMEN01C.cbl:L137 possible, and app/cpy/COADM02Y.cpy declares no counterpart.
            final UserType mainMenuFirstRowUserType = MenuResponse.MAIN_MENU_OPTIONS.get(0).userType();

            assertThat(mainMenuFirstRowUserType)
                    .as("app/cpy/COMEN02Y.cpy:L92 CDEMO-MENU-OPT-USRTYPE PIC X(01) resolves to a classification")
                    .isIn(UserType.ADMIN, UserType.USER);
            assertThat(MenuResponse.AdminMenuOption.class.getRecordComponents())
                    .as("no component of the administrator option can carry that classification")
                    .noneMatch(component -> component.getType().equals(UserType.class));
        }

        @Test
        @DisplayName("no member of the service touches a security framework type")
        void noMemberOfTheServiceTouchesASecurityFrameworkType() {
            for (final Method method : AdminMenuService.class.getDeclaredMethods()) {
                if (method.isSynthetic()) {
                    continue;
                }
                assertThat(namesOf(method.getParameterTypes()))
                        .as("%s must take no authorisation argument", method.getName())
                        .noneMatch(TheServiceHoldsNoAuthorisationSurface::isSecurityFrameworkType);
                assertThat(isSecurityFrameworkType(method.getReturnType().getName()))
                        .as("%s must return no authorisation value", method.getName())
                        .isFalse();
            }
            for (final Field field : AdminMenuService.class.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                assertThat(isSecurityFrameworkType(field.getType().getName()))
                        .as("%s must not hold an authorisation value", field.getName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("selectOption takes only the keyed field, so no caller identity can change its outcome")
        void selectOptionTakesOnlyTheKeyedField() {
            final List<Method> selectOption = declaredMethodsNamed("selectOption");

            assertThat(selectOption)
                    .as("one overload only, so there is no identity-carrying variant")
                    .hasSize(1);
            assertThat(selectOption.get(0).getParameterTypes())
                    .as("OPTIONI of app/cpy-bms/COADM01.CPY:L132 is the whole input")
                    .containsExactly(String.class);
        }

        @Test
        @DisplayName("every shipped target is a user-administration screen, so one rule covers the surface")
        void everyShippedTargetIsAUserAdministrationScreen() {
            assertThat(SHIPPED_PROGRAMS)
                    .as("COUSR00C, COUSR01C, COUSR02C and COUSR03C are the four security screens")
                    .allSatisfy(program -> assertThat(program).startsWith("COUSR"))
                    .hasSize(POPULATED_OPTION_COUNT);
        }

        @Test
        @DisplayName("the same keying yields the same outcome from two independent instances")
        void theSameKeyingYieldsTheSameOutcomeFromTwoInstances() {
            // There is no per-caller state to differ on, which is the practical meaning of "no
            // option-level authorisation in the service".
            final AdminMenuService first = construct(fixedClock(), SHIPPED_OPTIONS);
            final AdminMenuService second = construct(fixedClock(), SHIPPED_OPTIONS);

            assertThat(first.selectOption("4"))
                    .as("the outcome is a function of the keyed field and the table, nothing else")
                    .isEqualTo(second.selectOption("4"));
        }

        @Test
        @DisplayName("no constant on the service carries anything credential-shaped")
        void noConstantOnTheServiceCarriesAnythingCredentialShaped() {
            for (final Field field : AdminMenuService.class.getDeclaredFields()) {
                if (field.isSynthetic() || !Modifier.isStatic(field.getModifiers())
                        || !Modifier.isPublic(field.getModifiers())) {
                    continue;
                }
                assertThat(field.getName())
                        .as("a publicly readable constant must not name a credential")
                        .doesNotContainIgnoringCase("secret")
                        .doesNotContainIgnoringCase("password")
                        .doesNotContainIgnoringCase("token")
                        .doesNotContainIgnoringCase("credential");
            }
        }

        /**
         * Reports whether a type name belongs to a security framework, so that a leak of authorisation
         * concern into this bean would be caught by name rather than by inspection.
         *
         * @param typeName the fully qualified type name to classify
         * @return {@code true} when the type belongs to a security framework package
         */
        private static boolean isSecurityFrameworkType(final String typeName) {
            return typeName.startsWith("org.springframework.security")
                    || typeName.startsWith("jakarta.security")
                    || typeName.startsWith("javax.security");
        }
    }

    // 8. Determinism: POPULATE-HEADER-INFO reads the injected clock once, in the clock's own zone.

    /**
     * {@code POPULATE-HEADER-INFO} at {@code app/cbl/COADM01C.cbl:L202-L221} and
     * {@code BUILD-MENU-OPTIONS} at {@code :L226-L262}. The source reads
     * {@code FUNCTION CURRENT-DATE} once at {@code :L204} and derives both the date and the time from that
     * one read, so a single Java clock read is the faithful translation as well as the deterministic one.
     * Every clock here is fixed at a literal instant: nothing in this class consults the wall clock, the
     * platform zone or the platform locale.
     */
    @Nested
    @DisplayName("8. the header is rendered from one read of the injected clock, in the clock's own zone")
    class TheHeaderRenderingIsDeterministic {

        @Test
        @DisplayName("the header carries the two titles, the transaction, the program, the date and the time")
        void theHeaderCarriesEveryFieldPopulateHeaderInfoMoves() {
            construct(fixedClock(), SHIPPED_OPTIONS).getMenuScreen();

            assertThat(renderedHeader())
                    .as("app/cbl/COADM01C.cbl:L206-L221 moves six header fields")
                    .isEqualTo(EXPECTED_HEADER)
                    .contains("tranid=" + EXPECTED_TRANSACTION_ID)
                    .contains("pgmname=" + EXPECTED_PROGRAM_NAME)
                    .contains("date=06/10/22")
                    .contains("time=19:27:53");
        }

        @ParameterizedTest(name = "a clock in {0} renders date {1} and time {2}")
        @CsvSource({
            "UTC, 06/10/22, 19:27:53",
            "Asia/Tokyo, 06/11/22, 04:27:53",
            "America/New_York, 06/10/22, 15:27:53"})
        @DisplayName("the rendering follows the clock's own zone, never the platform default")
        void theRenderingFollowsTheClocksOwnZone(final String zoneName, final String expectedDate,
                final String expectedTime) {
            // The Tokyo row crosses a date boundary from the same instant, which is exactly what a
            // platform-default zone would have silently changed from one machine to another.
            construct(fixedClock(ZoneId.of(zoneName)), SHIPPED_OPTIONS).getMenuScreen();

            assertThat(renderedHeader())
                    .as("one instant, three zones, three renderings")
                    .isEqualTo(expectedHeader(expectedDate, expectedTime));
        }

        @Test
        @DisplayName("the UTC rendering and the transcribed header constant are the same string")
        void theUtcRenderingAndTheTranscribedConstantAgree() {
            assertThat(expectedHeader("06/10/22", "19:27:53"))
                    .as("the assembled expectation and the transcribed one must not drift apart")
                    .isEqualTo(EXPECTED_HEADER);
        }

        @Test
        @DisplayName("the date and the time come from one clock read, so neither can straddle a boundary")
        void theDateAndTheTimeComeFromOneClockRead() {
            // The clock below advances by a second on every read, so a second read would put the time a
            // second ahead of the date it is reported with. app/cbl/COADM01C.cbl:L204 reads once.
            final CountingClock countingClock =
                    new CountingClock(Instant.parse("2022-06-10T23:59:59Z"), ZoneOffset.UTC);

            construct(countingClock, SHIPPED_OPTIONS).getMenuScreen();

            assertThat(countingClock.instantReads())
                    .as("one MOVE FUNCTION CURRENT-DATE at app/cbl/COADM01C.cbl:L204, so one read")
                    .isEqualTo(1);
            assertThat(renderedHeader())
                    .as("the last second of the day must be reported against that same day")
                    .isEqualTo(expectedHeader("06/10/22", "23:59:59"));
        }

        @Test
        @DisplayName("one send produces exactly one header event, so the screen is not sent twice")
        void oneSendProducesExactlyOneHeaderEvent() {
            construct(fixedClock(), SHIPPED_OPTIONS).getMenuScreen();

            final ILoggingEvent event = MenuServiceTestSupport.singleHeaderEvent(
                    MenuServiceTestSupport.headerEvents(AdminMenuServiceTest.this.appender, SEND_LOG_PREFIX),
                    "app/cbl/COADM01C.cbl:L174 performs POPULATE-HEADER-INFO once per SEND MAP");

            assertThat(event.getLevel())
                    .as("screen furniture is diagnostic, so it is logged at DEBUG and not above")
                    .isEqualTo(Level.DEBUG);
        }

        @Test
        @DisplayName("the header statement is parameterised, so no value is concatenated into the message")
        void theHeaderStatementIsParameterised() {
            construct(fixedClock(), SHIPPED_OPTIONS).getMenuScreen();

            final ILoggingEvent event = MenuServiceTestSupport.singleHeaderEvent(
                    MenuServiceTestSupport.headerEvents(AdminMenuServiceTest.this.appender, SEND_LOG_PREFIX),
                    "exactly one send is expected before the arguments are read");
            final Object[] arguments = MenuServiceTestSupport.argumentsOf(event,
                    "the header exists only as an argument of this statement");

            assertThat(arguments)
                    .as("the transaction identifier, the option count and the assembled header")
                    .hasSizeGreaterThan(HEADER_ARGUMENT_INDEX);
            assertThat(arguments[0])
                    .as("app/cbl/COADM01C.cbl:L37 WS-TRANID")
                    .isEqualTo(EXPECTED_TRANSACTION_ID);
            assertThat(arguments[1])
                    .as("app/cpy/COADM02Y.cpy:L20 populates four options")
                    .isEqualTo(POPULATED_OPTION_COUNT);
            assertThat(event.getMessage())
                    .as("a parameterised statement keeps its placeholders in the raw message")
                    .contains("{}");
        }

        @Test
        @DisplayName("two sends from the same clock render the identical header")
        void twoSendsFromTheSameClockRenderTheIdenticalHeader() {
            final AdminMenuService service = construct(fixedClock(), SHIPPED_OPTIONS);

            service.getMenuScreen();
            service.getMenuScreen();

            final List<ILoggingEvent> events = MenuServiceTestSupport.headerEvents(
                    AdminMenuServiceTest.this.appender, SEND_LOG_PREFIX);

            assertThat(events)
                    .as("two sends, two header events")
                    .hasSize(2);
            assertThat(headerOf(events.get(0)))
                    .as("a fixed clock cannot render two different headers")
                    .isEqualTo(headerOf(events.get(1)))
                    .isEqualTo(EXPECTED_HEADER);
        }

        @Test
        @DisplayName("the rendered option lines follow the copybook's declaration order exactly")
        void theRenderedOptionLinesFollowTheCopybookOrder() {
            // The order matters as much as the content: app/cbl/COADM01C.cbl:L238-L261 assigns line one to
            // OPTN001O and so on, so a reordering would repaint the screen wrongly even with the same set.
            final AdminMenuService.AdminMenuView view =
                    construct(fixedClock(), SHIPPED_OPTIONS).getMenuScreen();

            assertThat(view.optionLabels())
                    .as("app/cbl/COADM01C.cbl:L233-L236, a PIC 9(02) number so 01. and not 1.")
                    .containsExactlyElementsOf(EXPECTED_OPTION_LABELS);
        }

        @Test
        @DisplayName("the first send carries no message, because MAIN-PARA clears it before sending")
        void theFirstSendCarriesNoMessage() {
            final AdminMenuService.AdminMenuView view =
                    construct(fixedClock(), SHIPPED_OPTIONS).getMenuScreen();

            assertThat(view.message())
                    .as("app/cbl/COADM01C.cbl:L79-L80 moves SPACES to WS-MESSAGE before the send")
                    .isEmpty();
            assertThat(view.menu().getMenuType())
                    .as("the view describes the administrator screen, not the main one")
                    .isEqualTo(MenuResponse.MenuType.ADMIN);
        }
    }

    // Helpers. Every one is a pure function of its arguments or of the fixed fixtures above.

    /**
     * Returns the options the canonically constructed service serves.
     *
     * @return the served options, in menu order
     */
    private static List<MenuResponse.AdminMenuOption> servedOptions() {
        return new AdminMenuService().getMenuScreen().menu().getOptions();
    }

    /**
     * Returns a clock fixed at {@link #FIXED_INSTANT} in {@link ZoneOffset#UTC}.
     *
     * @return a fixed clock, never {@code null}
     */
    private static Clock fixedClock() {
        return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    }

    /**
     * Returns a clock fixed at {@link #FIXED_INSTANT} in a caller-chosen zone.
     *
     * @param zone the zone the clock reports; must not be {@code null}
     * @return a fixed clock, never {@code null}
     */
    private static Clock fixedClock(final ZoneId zone) {
        return Clock.fixed(FIXED_INSTANT, zone);
    }

    /**
     * Builds the header the service must render for a given date and time.
     *
     * @param date the expected {@code MM/dd/yy} rendering
     * @param time the expected {@code HH:mm:ss} rendering
     * @return the whole expected header line
     */
    private static String expectedHeader(final String date, final String time) {
        return "AWS Mainframe Modernization | CardDemo | tranid=" + EXPECTED_TRANSACTION_ID
                + " | pgmname=" + EXPECTED_PROGRAM_NAME + " | date=" + date + " | time=" + time;
    }

    /**
     * Constructs a service over the package-private {@code (Clock, List)} seam.
     *
     * @param clock   the clock to inject
     * @param options the option table to inject
     * @return the constructed service
     */
    private static AdminMenuService construct(final Clock clock,
            final List<MenuResponse.AdminMenuOption> options) {
        return MenuServiceTestSupport.construct(AdminMenuService.class,
                MenuServiceTestSupport.seamConstructor(AdminMenuService.class,
                        "the option table cannot be varied and the placeholder branch at"
                                + " app/cbl/COADM01C.cbl:L146-L154 cannot be reached"),
                clock, options);
    }

    /**
     * Returns the single rendered header captured since the appender was attached.
     *
     * @return the assembled header line
     */
    private String renderedHeader() {
        return headerOf(MenuServiceTestSupport.singleHeaderEvent(
                MenuServiceTestSupport.headerEvents(this.appender, SEND_LOG_PREFIX),
                "app/cbl/COADM01C.cbl:L174 performs POPULATE-HEADER-INFO once per send"));
    }

    /**
     * Extracts the assembled header from one send event.
     *
     * @param event the send event to read; must carry an argument array
     * @return the assembled header line
     */
    private static String headerOf(final ILoggingEvent event) {
        return String.valueOf(MenuServiceTestSupport.argumentsOf(event,
                "the assembled header is carried as an argument, not concatenated into the message")
                [HEADER_ARGUMENT_INDEX]);
    }

    /**
     * Returns every captured log message with its arguments interpolated.
     *
     * @return the rendered messages in logged order
     */
    private List<String> renderedLogMessages() {
        return this.appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Returns the names of the service's own declared methods whose modifiers satisfy a predicate,
     * excluding compiler- and coverage-agent-generated members.
     *
     * @param modifierTest the modifier predicate to apply; must not be {@code null}
     * @return the matching method names
     */
    private static List<String> declaredMethodNames(final IntPredicate modifierTest) {
        final List<String> names = new ArrayList<>();
        for (final Method method : AdminMenuService.class.getDeclaredMethods()) {
            if (!method.isSynthetic() && modifierTest.test(method.getModifiers())) {
                names.add(method.getName());
            }
        }
        return List.copyOf(names);
    }

    /**
     * Returns the service's private methods, which are its paragraph counterparts.
     *
     * @return the private declared methods
     */
    private static List<Method> paragraphMethods() {
        final List<Method> methods = new ArrayList<>();
        for (final Method method : AdminMenuService.class.getDeclaredMethods()) {
            if (!method.isSynthetic() && Modifier.isPrivate(method.getModifiers())) {
                methods.add(method);
            }
        }
        return List.copyOf(methods);
    }

    /**
     * Returns the service's declared methods carrying a given name.
     *
     * @param name the method name to select; must not be {@code null}
     * @return the matching declared methods
     */
    private static List<Method> declaredMethodsNamed(final String name) {
        final List<Method> methods = new ArrayList<>();
        for (final Method method : AdminMenuService.class.getDeclaredMethods()) {
            if (!method.isSynthetic() && name.equals(method.getName())) {
                methods.add(method);
            }
        }
        return List.copyOf(methods);
    }

    /**
     * Reports whether an executable member carries package-private visibility.
     *
     * @param executable the member to classify; must not be {@code null}
     * @return {@code true} when the member is neither public, protected nor private
     */
    private static boolean isPackagePrivate(final Executable executable) {
        final int modifiers = executable.getModifiers();
        return !Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers)
                && !Modifier.isPrivate(modifiers);
    }

    /**
     * Reads the production source of the service under test.
     *
     * @return its lines, in file order
     * @throws UncheckedIOException if the source cannot be read from the project base directory
     */
    private static List<String> serviceSourceLines() {
        try {
            return Files.readAllLines(SERVICE_SOURCE, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("could not read " + SERVICE_SOURCE.toAbsolutePath()
                    + "; Surefire is expected to run with the project base directory as its working"
                    + " directory", unreadable);
        }
    }

    /**
     * Returns the Javadoc block immediately preceding a private method declaration.
     *
     * @param sourceLines the production source lines; must not be {@code null}
     * @param methodName  the private method whose documentation is wanted; must not be {@code null}
     * @return the text of that method's own Javadoc block
     * @throws AssertionError if the method is not declared, or carries no Javadoc block
     */
    private static String javadocPreceding(final List<String> sourceLines, final String methodName) {
        final String declaration = " " + methodName + "(";
        int declarationIndex = -1;
        for (int index = 0; index < sourceLines.size(); index++) {
            final String line = sourceLines.get(index);
            if (line.contains("private ") && line.contains(declaration)) {
                declarationIndex = index;
                break;
            }
        }
        assertThat(declarationIndex)
                .as("%s must be declared as a private method in %s", methodName, SERVICE_SOURCE)
                .isNotNegative();

        int javadocIndex = declarationIndex - 1;
        while (javadocIndex >= 0 && !sourceLines.get(javadocIndex).contains(JAVADOC_OPEN)) {
            javadocIndex--;
        }
        assertThat(javadocIndex)
                .as("%s must carry its own Javadoc block", methodName)
                .isNotNegative();

        return String.join("\n", sourceLines.subList(javadocIndex, declarationIndex));
    }

    /**
     * Returns the fully qualified names of a member's types.
     *
     * @param types the types to name; must not be {@code null}
     * @return the type names in declaration order
     */
    private static List<String> namesOf(final Class<?>[] types) {
        final List<String> names = new ArrayList<>(types.length);
        for (final Class<?> type : types) {
            names.add(type.getName());
        }
        return List.copyOf(names);
    }

    /**
     * A clock that counts its reads and advances a second on each one.
     *
     * <p>Its only purpose is to prove that the header is derived from a single read. A clock that returned
     * the same instant every time could not distinguish one read from two, and the single read is the
     * property {@code app/cbl/COADM01C.cbl:L204} actually has.</p>
     */
    private static final class CountingClock extends Clock {

        /** The instant the first read reports. */
        private final Instant base;

        /** The zone this clock reports. */
        private final ZoneId zone;

        /** How many times {@link #instant()} has been called. */
        private int reads;

        /**
         * Creates a counting clock.
         *
         * @param base the instant the first read reports
         * @param zone the zone this clock reports
         */
        CountingClock(final Instant base, final ZoneId zone) {
            this.base = base;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return this.zone;
        }

        @Override
        public Clock withZone(final ZoneId replacement) {
            return new CountingClock(this.base, replacement);
        }

        @Override
        public Instant instant() {
            this.reads++;
            return this.base.plusSeconds(this.reads - 1L);
        }

        /**
         * Returns how many times this clock has been read.
         *
         * @return the read count
         */
        int instantReads() {
            return this.reads;
        }
    }
}
