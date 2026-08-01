/*
 * ******************************************************************
 * Program     : MenuResponseTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM, no container, no Spring
 *               context, no database
 * Function    : Pins the option-table contract of
 *               com.cardemo.model.dto.MenuResponse against the two
 *               legacy CardDemo menu copybooks, which are DIFFERENT
 *               SHAPES. Asserts the populated count - never the OCCURS
 *               arity - as the only iteration bound, the 46 versus 45
 *               byte entry asymmetry, the full declared field widths
 *               that DELIMITED BY SIZE emits, the JUST RIGHT
 *               space-to-zero option parse, the three bounds-check
 *               rejection conditions, the byte-exact rejection literals
 *               including the trailing space in the admin-gate message,
 *               the five-character DUMMY prefix guard, the absent /
 *               blank / LOW-VALUES tri-state, insertion-ordered and
 *               unmodifiable options, every input boundary, and the
 *               absence of server-side session state, of a shared
 *               screen-header abstraction and of any live dispatch key.
 * Source      : app/cpy/COMEN02Y.cpy (count 10 @ L21, OCCURS 12 @ L88,
 *               46-byte slot) +
 *               app/cpy/COADM02Y.cpy (count 4 @ L20, OCCURS 9 @ L45,
 *               45-byte slot) +
 *               app/cbl/COMEN01C.cbl:L45-L46,L117-L125,L127-L131,
 *               L136-L140,L146,L153,L160,L236-L245 +
 *               app/cbl/COADM01C.cbl:L117-L131,L138,L143,L150,
 *               L226-L235 @ 7756d89
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
package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cardemo.model.dto.MenuResponse;
import com.cardemo.model.dto.MenuResponse.AdminMenuOption;
import com.cardemo.model.dto.MenuResponse.MainMenuOption;
import com.cardemo.model.dto.MenuResponse.MenuOption;
import com.cardemo.model.dto.MenuResponse.MenuScreen;
import com.cardemo.model.dto.MenuResponse.MenuType;
import com.cardemo.model.enums.UserType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MenuResponse}, the payload that carries the option table of either CardDemo menu
 * screen.
 *
 * <h2>1. What this test class does</h2>
 *
 * <p>It pins one central, easily-lost fact and every consequence of it: <strong>the two legacy menu option
 * tables are different shapes, and neither one may be iterated to its declared {@code OCCURS} arity.</strong>
 * A translation that misses either half produces code that compiles, passes a naive test and reads storage
 * the source never populated.</p>
 *
 * <table>
 *   <caption>The two source tables, as declared</caption>
 *   <tr><th>&nbsp;</th><th>main - {@code app/cpy/COMEN02Y.cpy}</th><th>admin - {@code app/cpy/COADM02Y.cpy}</th></tr>
 *   <tr><td>count field</td>
 *       <td>{@code CDEMO-MENU-OPT-COUNT} {@code VALUE 10} at {@code :L21}</td>
 *       <td>{@code CDEMO-ADMIN-OPT-COUNT} {@code VALUE 4} at {@code :L20}</td></tr>
 *   <tr><td>populated blocks</td>
 *       <td>ten blocks of four {@code FILLER}s, {@code :L25-L84}</td>
 *       <td>four blocks of three {@code FILLER}s, {@code :L24-L42}</td></tr>
 *   <tr><td>{@code REDEFINES}</td><td>{@code :L87}</td><td>{@code :L44}</td></tr>
 *   <tr><td>{@code OCCURS} arity</td><td>{@code OCCURS 12} at {@code :L88}</td>
 *       <td>{@code OCCURS 9} at {@code :L45}</td></tr>
 *   <tr><td>sub-fields</td>
 *       <td>{@code :L89-L92} - {@code -NUM PIC 9(02)}, {@code -NAME PIC X(35)}, {@code -PGMNAME PIC X(08)},
 *           {@code -USRTYPE PIC X(01)}</td>
 *       <td>{@code :L46-L48} - {@code -NUM PIC 9(02)}, {@code -NAME PIC X(35)},
 *           {@code -PGMNAME PIC X(08)}; <strong>no {@code -USRTYPE}</strong></td></tr>
 *   <tr><td>slot width</td><td>2 + 35 + 8 + 1 = <strong>46 bytes</strong></td>
 *       <td>2 + 35 + 8 = <strong>45 bytes</strong></td></tr>
 * </table>
 *
 * <p>Both halves are asserted here. The shape asymmetry is asserted structurally, over the record components
 * of {@link MainMenuOption} and {@link AdminMenuOption} and over the members of the sealed
 * {@link MenuOption} supertype, so imposing the four-field main shape on the three-field admin table cannot
 * pass. The bound is asserted over {@link MenuType#getPopulatedOptionCount()} against
 * {@link MenuType#getDeclaredCapacity()}, and over the fact that every index at or beyond the count is
 * unreachable through {@link MenuResponse#getOptions()}.</p>
 *
 * <h3>1.1 Findings this class records, classified by severity</h3>
 *
 * <ul>
 *   <li><strong>Blocker</strong> - using the {@code OCCURS} arity as an iteration bound. The overlay is
 *       wider than the data it redefines: main is 10 &times; 46 = 460 populated bytes under a 12 &times; 46
 *       = 552 byte overlay, a <strong>92 byte over-run</strong> making subscripts 11 and 12 uninitialised;
 *       admin is 4 &times; 45 = 180 under 9 &times; 45 = 405, a <strong>225 byte over-run</strong> making
 *       subscripts 5 through 9 uninitialised. Remediation: bound every loop by the count field. Both
 *       programs already do - {@code app/cbl/COMEN01C.cbl:L238-L239} and
 *       {@code app/cbl/COADM01C.cbl:L228-L229} stop when the index passes the count, and
 *       {@code :L128} in each validates a selection against the same count.</li>
 *   <li><strong>Blocker</strong> - imposing one entry shape on both tables. Remediation: two types, and a
 *       shared supertype that declares only the three genuinely shared sub-fields.</li>
 *   <li><strong>Blocker</strong> - altering a rejection literal, in particular dropping the trailing space
 *       inside {@code 'No access - Admin Only option... '} at {@code app/cbl/COMEN01C.cbl:L140}.
 *       Remediation: assert the literals byte for byte, as {@link RejectionLiterals} does.</li>
 *   <li><strong>Blocker</strong> - collapsing the absent / blank / {@code LOW-VALUES} tri-state into one
 *       state. Remediation: keep {@code null}, a space and {@code U+0000} distinguishable end to end.</li>
 *   <li><strong>High</strong> - trimming the 35 character caption, unpadding the two digit option number,
 *       comparing all eight program-name characters against {@code 'DUMMY'}, fabricating an admin user-type
 *       gate, left-justifying the option parse, leaving iteration order unspecified, publishing a mutable
 *       option list, treating the program name as a live dispatch key, abstracting a shared screen header,
 *       or modelling a gated cross-field edit as an unconditional class-level constraint.</li>
 *   <li><strong>Medium, closed</strong> - prior-generation plan prose gave a census of 460 BMS input
 *       fields. A direct count of the seventeen symbolic maps totals <strong>441</strong>, and that prose's
 *       own table summed to 440, so the three figures disagreed; the specification now publishes 441.
 *       The two maps this class touches are counted here first hand:
 *       {@code app/cpy-bms/COMEN01.CPY} and {@code app/cpy-bms/COADM01.CPY} declare
 *       <strong>20 input fields each</strong>. Remediation: recount before quoting the total.</li>
 *   <li><strong>Low</strong> - {@code app/cpy/COMEN02Y.cpy:L2} banners the member as
 *       {@code CardDemo - Admin Menu Options} although its group item at {@code :L19} is
 *       {@code CARDDEMO-MAIN-MENU-OPTIONS} and its ten captions are the main-menu captions. Cosmetic; the
 *       declarations are authoritative. Recorded, not acted on.</li>
 * </ul>
 *
 * <h3>1.2 Every locator this class relies on</h3>
 *
 * <ul>
 *   <li>{@code app/cpy/COMEN02Y.cpy:L21} the main count; {@code :L25-L84} the ten populated blocks;
 *       {@code :L68-L70} option 8, whose <strong>commented-out</strong> caption
 *       {@code 'Transaction Add (Admin Only)       '} sits on {@code :L69} and whose <strong>live</strong>
 *       caption {@code 'Transaction Add                    '} sits on {@code :L70}; {@code :L87-L92} the
 *       overlay and its four sub-fields.</li>
 *   <li>{@code app/cpy/COADM02Y.cpy:L20} the admin count; {@code :L24-L42} the four populated blocks;
 *       {@code :L44-L48} the overlay and its three sub-fields.</li>
 *   <li>{@code app/cbl/COMEN01C.cbl:L45-L46} {@code WS-OPTION-X PIC X(02) JUST RIGHT} and
 *       {@code WS-OPTION PIC 9(02) VALUE 0}; {@code :L117-L125} the parse; {@code :L127-L131} the bounds
 *       check; {@code :L136-L140} the admin gate; {@code :L146} the placeholder guard; {@code :L153} the
 *       data-driven {@code XCTL}; {@code :L160} the live caption reference, delimited by
 *       {@code SPACE} on {@code :L161}; {@code :L236-L245} the menu-line build.</li>
 *   <li>{@code app/cbl/COADM01C.cbl:L117-L131} the identical parse and bounds check against its own count;
 *       {@code :L138} the placeholder guard; {@code :L143} the {@code XCTL}; {@code :L150} the
 *       <strong>commented-out</strong> caption reference; {@code :L226-L235} the menu-line build.</li>
 *   <li>{@code app/cpy/COCOM01Y.cpy:L26-L28} the two-code user-type domain; {@code :L43-L44}
 *       {@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET}, both {@code X(7)} and both absent here.</li>
 *   <li>{@code app/cpy/CSSETATY.cpy} the {@code OK / NOT-OK / BLANK} marker template - procedural, copied
 *       into a {@code PROCEDURE DIVISION}, so it has no data layout and gets no class.</li>
 *   <li>{@code app/cbl/COACTUPC.cbl:L300-L311} the tri-state encoded in one byte per field
 *       ({@code ISVALID VALUE LOW-VALUES} / {@code NOT-OK VALUE '0'} / {@code BLANK VALUE 'B'});
 *       {@code :L505-L508} the distinct blank and not-valid messages; {@code :L1665-L1669} followed by
 *       {@code :L1670-L1675}, the cross-field edit that runs only after both single-field edits passed.</li>
 *   <li>{@code app/cpy-bms/COMEN01.CPY} and {@code app/cpy-bms/COADM01.CPY}, twenty input fields each,
 *       {@code CURTIMEI PIC X(8)} on {@code :L54} of both, against
 *       {@code app/cpy-bms/COSGN00.CPY:L54} where the same field is {@code PIC X(9)}.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl:L1-L21} the canonical banner this file opens with;
 *       {@code CONTRIBUTING.md:L33-L34}; {@code NOTICE}.</li>
 * </ul>
 *
 * <h2>2. How to run, build and test</h2>
 *
 * <p>{@code ./mvnw -B clean test} compiles the tree and runs this class. Surefire 3.5.4 selects it by the
 * {@code **}{@code /*Test.java} name pattern while excluding the {@code integration} and {@code e2e} trees,
 * so a class in this package whose name ends in {@code Test} is collected by <strong>Surefire</strong> and
 * a class outside those name patterns is collected by neither plugin - it silently never runs, with a green
 * build and no warning. Do not rename or relocate this file. {@code ./mvnw -B test-compile} is the fastest
 * check that it still satisfies the compiler settings, and
 * {@code ./mvnw -B -Dtest=MenuResponseTest surefire:test} runs it alone. Where a local toolchain is
 * unavailable the pinned image reproduces it:
 * {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q test}.</p>
 *
 * <p>This is a pure JVM tier. No container, no Spring context, no database and no cloud endpoint is started
 * here, and none may be. The frozen corpus under {@code app/}, {@code samples/} and {@code diagrams/} is
 * read as evidence and never written.</p>
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <p>There is no external configuration: every expected value below is a compile-time constant transcribed
 * from a copybook or program line cited beside it. Determinism is absolute.</p>
 *
 * <ul>
 *   <li><strong>Time.</strong> {@link MenuResponse} carries no temporal component, so no assertion here
 *       needs a clock to succeed - but the absence is itself asserted, and the invariance of the type's
 *       value identity across two <em>different</em> instants is demonstrated with clocks obtained from
 *       {@link FixedClockProvider}. The ambient clock is never read: every temporal value originates in an
 *       injected fixed clock, and this file calls no system-clock factory and no wall-clock millisecond
 *       counter anywhere.</li>
 *   <li><strong>Locale and zone.</strong> Every format and parse is explicitly {@link Locale#ROOT}, and the
 *       only zone used is {@link FixedClockProvider#CANONICAL_ZONE}. The platform default locale, charset
 *       and zone are never consulted, so digit shaping cannot vary by host.</li>
 *   <li><strong>Test doubles.</strong> None. {@code mockito-core} 5.17.0 is on the test classpath and its
 *       strict-stubs default would apply, but {@link MenuResponse} has no collaborator to double: it
 *       performs no I/O, reads no configuration and depends on exactly one other CardDemo type. Mocking a
 *       pure value type would assert the mock rather than the contract, so Mockito is deliberately unused
 *       and deliberately not imported - Clause B forbids an unused import, and because {@code javac} 25.0.3
 *       publishes no lint key for one that prohibition is honoured here by omission rather than by the
 *       compiler.</li>
 *   <li><strong>Reflection.</strong> Used only to read declared structure - field names, method names,
 *       record components, annotations, generic type names - never to invoke anything or to defeat access
 *       control. Synthetic members are filtered because the coverage agent adds {@code $jacocoData} during
 *       {@code verify}.</li>
 * </ul>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>The build fails on something trivial.</em> Compilation runs with {@code -Xlint:all},
 *       {@code -Werror} and {@code failOnWarning}, and that reaches test compilation, so one raw type or
 *       deprecation is an error rather than a warning. An unused import is not - {@code javac} 25.0.3
 *       publishes no lint key for one - so that is caught at review. Reproduce with
 *       {@code ./mvnw -B test-compile}.</li>
 *   <li><em>A count assertion fails at 12 or 9 instead of 10 or 4.</em> The {@code OCCURS} arity was used
 *       as the bound. Use {@link MenuType#getPopulatedOptionCount()}.</li>
 *   <li><em>An admin option appears to have a user type.</em> The 46 byte main shape was imposed on the 45
 *       byte admin table. {@link AdminMenuOption} has three components and must keep having three.</li>
 *   <li><em>A caption assertion fails by a handful of trailing characters.</em> The 35 character padding
 *       was trimmed. {@code DELIMITED BY SIZE} at {@code app/cbl/COMEN01C.cbl:L243-L245} emits the full
 *       declared width, padding included.</li>
 *   <li><em>An option number renders as {@code 1} rather than {@code 01}.</em> The
 *       {@code PIC 9(02)} zero padding was dropped.</li>
 *   <li><em>A rejection-message assertion fails on an invisible character.</em> The trailing space inside
 *       {@code 'No access - Admin Only option... '} was dropped. It is a source <em>sic</em> and part of
 *       the byte comparison the parity gates perform.</li>
 *   <li><em>{@code "5 "} parses as 50.</em> The {@code JUST RIGHT} right-justification was omitted before
 *       the space-to-zero substitution. See {@link #normaliseOptionEntry(String)}.</li>
 *   <li><em>{@code "XDUMMY"} is treated as a placeholder.</em> All eight characters were compared instead
 *       of the first five that {@code (1:5) NOT = 'DUMMY'} compares.</li>
 * </ul>
 *
 * <h2>5. Deliberately not asserted - "Not available"</h2>
 *
 * <ul>
 *   <li><strong>Any DDL claim.</strong> The two menu tables are <strong>copybook constants, not database
 *       tables</strong>: {@code app/cpy/COMEN02Y.cpy} and {@code app/cpy/COADM02Y.cpy} are
 *       {@code WORKING-STORAGE} literals copied into two programs, they appear in no
 *       {@code DEFINE CLUSTER} job and in no catalogue entry, and {@code V1__create_schema.sql} has no
 *       planned child for them. Their column mapping is therefore <strong>Not available</strong>, and none
 *       is invented. What would be needed to state one: a catalogued cluster or an IDCAMS definition for a
 *       menu dataset, neither of which exists.</li>
 *   <li><strong>The contents of the unpopulated overlay subscripts.</strong> Main 11 and 12, and admin 5
 *       through 9, address storage no {@code VALUE} literal reaches. Their contents are
 *       <strong>Not available</strong>; this class asserts only that they are unreachable.</li>
 *   <li><strong>The private constructor's null-menu-type guard.</strong> It is the one line of
 *       {@link MenuResponse} this class leaves uncovered, and deliberately so. The sole constructor is
 *       private and all four factories pass a {@link MenuType} constant, so no public caller can reach the
 *       branch; provoking it would need reflective invocation of a private constructor, which Rule 1
 *       clause D flags as a risky pattern and which would add coverage without adding a behavioural
 *       assertion. {@code everyFactorySuppliesAMenuType} asserts the unreachability instead.</li>
 *   <li><strong>Screen rendering.</strong> The 3270 and BMS layer is consumed as a field contract only. No
 *       attribute byte, no pseudo-conversational state and no rendered screen is asserted, because none is
 *       reimplemented.</li>
 *   <li><strong>The user-type gate outcome as a decision.</strong> {@link MenuResponse} carries the gate
 *       byte and never evaluates it; the decision belongs to the menu service derived from
 *       {@code app/cbl/COMEN01C.cbl}. What is asserted here is that the byte is faithfully
 *       <em>representable</em>, so the service can refuse access, and that no method on the payload decides
 *       access itself.</li>
 * </ul>
 */
class MenuResponseTest {

    /**
     * The main menu's populated option count, {@code CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10} at
     * {@code app/cpy/COMEN02Y.cpy:L21}. This is the iteration bound, and the only one.
     */
    private static final int MAIN_POPULATED_COUNT = 10;

    /**
     * The main menu's overlay extent, {@code CDEMO-MENU-OPT OCCURS 12 TIMES} at
     * {@code app/cpy/COMEN02Y.cpy:L88}. Never a bound: subscripts 11 and 12 are unpopulated.
     */
    private static final int MAIN_DECLARED_ARITY = 12;

    /**
     * The admin menu's populated option count, {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4} at
     * {@code app/cpy/COADM02Y.cpy:L20}.
     */
    private static final int ADMIN_POPULATED_COUNT = 4;

    /**
     * The admin menu's overlay extent, {@code CDEMO-ADMIN-OPT OCCURS 9 TIMES} at
     * {@code app/cpy/COADM02Y.cpy:L45}. Never a bound: subscripts 5 through 9 are unpopulated.
     */
    private static final int ADMIN_DECLARED_ARITY = 9;

    /**
     * Byte width of one main-menu entry: {@code 9(02) + X(35) + X(08) + X(01)} from
     * {@code app/cpy/COMEN02Y.cpy:L89-L92}.
     */
    private static final int MAIN_ENTRY_WIDTH = 46;

    /**
     * Byte width of one admin-menu entry: {@code 9(02) + X(35) + X(08)} from
     * {@code app/cpy/COADM02Y.cpy:L46-L48}. One byte narrower than a main entry, because the admin entry has no
     * user-type sub-field.
     */
    private static final int ADMIN_ENTRY_WIDTH = 45;

    /**
     * Declared width of {@code CDEMO-MENU-OPT-NUM} and {@code CDEMO-ADMIN-OPT-NUM}, {@code PIC 9(02)} at
     * {@code app/cpy/COMEN02Y.cpy:L89} and {@code app/cpy/COADM02Y.cpy:L46}. Also the width of
     * {@code OPTIONI PIC X(2)} at {@code app/cpy-bms/COMEN01.CPY:L132}, which is what the parse of
     * {@code app/cbl/COMEN01C.cbl:L117-L125} scans backwards over.
     */
    private static final int OPTION_NUMBER_WIDTH = 2;

    /**
     * The two-character literal {@code '. '} that {@code app/cbl/COMEN01C.cbl:L244} and
     * {@code app/cbl/COADM01C.cbl:L234} concatenate between the option number and the caption.
     */
    private static final String MENU_LINE_SEPARATOR = ". ";

    /**
     * Width of the rendered menu line the {@code STRING} statement produces: the two-digit number, the
     * two-character separator and the 35-character caption, all {@code DELIMITED BY SIZE}.
     */
    private static final int RENDERED_MENU_LINE_WIDTH = 39;

    /**
     * Number of input fields in {@code app/cpy-bms/COMEN01.CPY} and in {@code app/cpy-bms/COADM01.CPY}, counted
     * first hand: six common-header fields, twelve {@code OPTN0nnI PIC X(40)} caption slots,
     * {@code OPTIONI PIC X(2)} and {@code ERRMSGI PIC X(78)}.
     */
    private static final int MENU_MAP_INPUT_FIELD_COUNT = 20;

    /**
     * Number of caption slots both display maps declare, {@code OPTN001I} through {@code OPTN012I}.
     */
    private static final int MENU_MAP_CAPTION_SLOT_COUNT = 12;

    /**
     * Width of {@code CURTIMEI} on both menu maps, {@code PIC X(8)} at {@code app/cpy-bms/COMEN01.CPY:L54} and
     * {@code app/cpy-bms/COADM01.CPY:L54}.
     */
    private static final int MENU_MAP_HEADER_TIME_WIDTH = 8;

    /**
     * Width of the identically-named {@code CURTIMEI} on the sign-on map, {@code PIC X(9)} at
     * {@code app/cpy-bms/COSGN00.CPY:L54}. The one-byte divergence from {@link #MENU_MAP_HEADER_TIME_WIDTH} is
     * why no shared common-header abstraction is admissible.
     */
    private static final int SIGN_ON_HEADER_TIME_WIDTH = 9;

    /**
     * Width of {@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET}, {@code PIC X(7)} at
     * {@code app/cpy/COCOM01Y.cpy:L43-L44}. Seven, not eight - recorded because both fields are screen state
     * that must not appear on a stateless payload, and because the width is commonly misquoted.
     */
    private static final int COMMAREA_LAST_MAP_WIDTH = 7;

    /**
     * The bounds-check message, {@code app/cbl/COMEN01C.cbl:L131} and {@code app/cbl/COADM01C.cbl:L131}, byte
     * for byte. Three trailing dots and <strong>no</strong> trailing space. The same literal serves all three
     * rejection conditions and both programs.
     */
    private static final String INVALID_OPTION_MESSAGE = "Please enter a valid option number...";

    /**
     * The admin-gate message, {@code app/cbl/COMEN01C.cbl:L140}, byte for byte.
     */
    private static final String ADMIN_ONLY_MESSAGE = "No access - Admin Only option... ";

    /**
     * The placeholder sentinel of {@code IF CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'} at
     * {@code app/cbl/COMEN01C.cbl:L146} and {@code app/cbl/COADM01C.cbl:L138}.
     */
    private static final String PLACEHOLDER_SENTINEL = "DUMMY";

    /**
     * Length of the reference modification {@code (1:5)} applied to the eight-character program name before the
     * comparison with {@link #PLACEHOLDER_SENTINEL}. Five, not eight.
     */
    private static final int PLACEHOLDER_PREFIX_LENGTH = 5;

    /**
     * The gate byte all ten populated main entries carry, {@code FILLER PIC X(01) VALUE 'U'} at
     * {@code app/cpy/COMEN02Y.cpy:L29}, {@code :L35}, {@code :L41}, {@code :L47}, {@code :L53}, {@code :L59},
     * {@code :L65}, {@code :L72}, {@code :L78} and {@code :L84}.
     */
    private static final char STANDARD_USER_CODE = 'U';

    /**
     * The gate byte that would deny a standard user, compared as a bare literal by
     * {@code app/cbl/COMEN01C.cbl:L137}. No entry in either table carries it, yet the branch is live code.
     */
    private static final char ADMIN_USER_CODE = 'A';

    /**
     * A {@code LOW-VALUES} byte, the state {@code MOVE LOW-VALUES TO COMEN1AO} at
     * {@code app/cbl/COMEN01C.cbl:L89} leaves the map in before the operator types anything.
     */
    private static final char LOW_VALUE = '\u0000';

    /**
     * The commented-out caption of main option 8, {@code app/cpy/COMEN02Y.cpy:L69}. Preserved legacy residue:
     * recorded so it is provably absent from the transcribed table, never reproduced as live data.
     */
    private static final String WITHDRAWN_OPTION_8_CAPTION = "Transaction Add (Admin Only)       ";

    /**
     * The live caption of main option 8, {@code app/cpy/COMEN02Y.cpy:L70}, which the table actually holds.
     */
    private static final String LIVE_OPTION_8_CAPTION = "Transaction Add                    ";

    /**
     * The ten main-menu captions in copybook order, {@code app/cpy/COMEN02Y.cpy:L27}, {@code :L33},
     * {@code :L39}, {@code :L45}, {@code :L51}, {@code :L57}, {@code :L63}, {@code :L70}, {@code :L76} and
     * {@code :L82}, each blank-padded to the declared {@code PIC X(35)} width.
     */
    private static final List<String> EXPECTED_MAIN_CAPTIONS = List.of(
            "Account View                       ",
            "Account Update                     ",
            "Credit Card List                   ",
            "Credit Card View                   ",
            "Credit Card Update                 ",
            "Transaction List                   ",
            "Transaction View                   ",
            "Transaction Add                    ",
            "Transaction Reports                ",
            "Bill Payment                       ");

    /**
     * The ten main-menu program names in copybook order, {@code app/cpy/COMEN02Y.cpy:L28}, {@code :L34},
     * {@code :L40}, {@code :L46}, {@code :L52}, {@code :L58}, {@code :L64}, {@code :L71}, {@code :L77} and
     * {@code :L83}, each exactly the declared {@code PIC X(08)} width with no padding needed.
     */
    private static final List<String> EXPECTED_MAIN_PROGRAM_NAMES = List.of(
            "COACTVWC", "COACTUPC", "COCRDLIC", "COCRDSLC", "COCRDUPC",
            "COTRN00C", "COTRN01C", "COTRN02C", "CORPT00C", "COBIL00C");

    /**
     * The four admin-menu captions in copybook order, {@code app/cpy/COADM02Y.cpy:L26}, {@code :L31},
     * {@code :L36} and {@code :L41}, each blank-padded to {@code PIC X(35)}. The parenthesised
     * {@code (Security)} suffix is part of every one of them.
     */
    private static final List<String> EXPECTED_ADMIN_CAPTIONS = List.of(
            "User List (Security)               ",
            "User Add (Security)                ",
            "User Update (Security)             ",
            "User Delete (Security)             ");

    /**
     * The four admin-menu program names in copybook order, {@code app/cpy/COADM02Y.cpy:L27}, {@code :L32},
     * {@code :L37} and {@code :L42}.
     */
    private static final List<String> EXPECTED_ADMIN_PROGRAM_NAMES = List.of(
            "COUSR00C", "COUSR01C", "COUSR02C", "COUSR03C");

    /**
     * Member-name fragments drawn from {@code app/cpy/COCOM01Y.cpy} that encode pseudo-conversational
     * navigation or retained screen state: {@code CDEMO-FROM-TRANID} and {@code CDEMO-TO-TRANID} ({@code :L21},
     * {@code :L23}), {@code CDEMO-FROM-PROGRAM} and {@code CDEMO-TO-PROGRAM} ({@code :L22}, {@code :L24}),
     * {@code CDEMO-PGM-CONTEXT} ({@code :L29}) and {@code CDEMO-LAST-MAP} with {@code CDEMO-LAST-MAPSET}
     * ({@code :L43-L44}).
     */
    private static final List<String> FORBIDDEN_SESSION_STATE_FRAGMENTS = List.of(
            "fromtranid", "totranid", "fromprogram", "toprogram",
            "pgmcontext", "programcontext", "lastmap", "lastmapset", "commarea", "reenter");

    /**
     * Member-name fragments that would indicate the payload had taken over the transfer-of-control decision
     * that {@code EXEC CICS XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION))} performs at
     * {@code app/cbl/COMEN01C.cbl:L153} and {@code app/cbl/COADM01C.cbl:L143}.
     */
    private static final List<String> FORBIDDEN_DISPATCH_FRAGMENTS = List.of(
            "xctl", "dispatch", "route", "forward", "redirect", "invoke", "transfercontrol", "handler");

    /**
     * Member-name fragments that would indicate credential or personally identifiable material had reached a
     * menu payload. A menu option is a number, a caption, a program name and one eligibility byte; nothing on
     * this type may resemble a secret.
     */
    private static final List<String> FORBIDDEN_SENSITIVE_FRAGMENTS = List.of(
            "password", "passwd", "secret", "credential", "token", "signingkey", "apikey", "ssn");

    /**
     * Characters that cannot occur in any transcribed caption or program name.
     *
     * <p>Their absence pins the <em>transcription</em>: a stray one would mean a caption no longer matches the
     * copybook literal it was copied from. It is deliberately not offered as a security property. An earlier
     * form of this suite concluded from their absence that no canonical value could be an endpoint, and that
     * inference does not hold - a bare host name, a registered service name, a Windows UNC-style share, a JNDI
     * name or an environment-variable key are all reachable without any of these four characters, and in any
     * case a string's spelling says nothing about whether code will dereference it. Absence of a capability is
     * proved by absence of the API that provides it, which is what
     * {@link SecurityAndDeterminism#noRoutingOrInvocationApiIsReachable()} does.
     */
    private static final List<Character> FORBIDDEN_ENDPOINT_CHARACTERS = List.of(':', '/', '@', '.');

    /**
     * Internal class names and member names whose presence in a compiled class file would prove that a
     * routing, network, process or dynamic invocation capability is reachable from a menu type.
     *
     * <p>{@code "invoke"} is deliberately <em>not</em> a needle. A record's generated {@code equals},
     * {@code hashCode} and {@code toString} are implemented with {@code invokedynamic} against
     * {@code java.lang.runtime.ObjectMethods}, so every record here legitimately carries it. Note also
     * that {@code java/lang/Runtime} is matched with a capital {@code R} precisely so that it does not
     * collide with the lower-case {@code java/lang/runtime/} package those generated members use.
     */
    private static final List<String> FORBIDDEN_CAPABILITY_REFERENCES = List.of(
            "java/net/", "java/nio/channels/", "javax/naming/", "java/rmi/", "Socket",
            "HttpClient", "WebClient", "RestTemplate", "openConnection",
            "java/lang/ProcessBuilder", "java/lang/Runtime", "getRuntime", "forName",
            "java/lang/reflect/");

    /**
     * The leading literal of the not-yet-implemented notice, {@code 'This option '} at
     * {@code app/cbl/COMEN01C.cbl:L159} and {@code app/cbl/COADM01C.cbl:L149}, both {@code DELIMITED BY SIZE}
     * and both including the trailing space.
     */
    private static final String COMING_SOON_PREFIX = "This option ";

    /**
     * The trailing literal of the same notice, {@code 'is coming soon ...'} at
     * {@code app/cbl/COMEN01C.cbl:L162} and {@code app/cbl/COADM01C.cbl:L152}. It begins with no space, which
     * is what makes the divergence between the two programs observable.
     */
    private static final String COMING_SOON_SUFFIX = "is coming soon ...";

    /**
     * Reproduces the three-step option parse of {@code app/cbl/COMEN01C.cbl:L117-L123}, identical at
     * {@code app/cbl/COADM01C.cbl:L117-L123}, and returns the resulting {@code WS-OPTION-X} content.
     *
     * @param screenField the raw {@code OPTIONI} content, exactly {@link #OPTION_NUMBER_WIDTH} characters wide
     * as {@code app/cpy-bms/COMEN01.CPY:L132} declares it.
     * @return the two-character {@code WS-OPTION-X} content after substitution, never {@code null}
     * @throws IllegalArgumentException if {@code screenField} is {@code null} or is not exactly
     * {@link #OPTION_NUMBER_WIDTH} characters wide.
     */
    private static String normaliseOptionEntry(final String screenField) {
        if (screenField == null) {
            throw new IllegalArgumentException("screenField must not be null; absence is a distinct state "
                    + "from a blank entry and from an untouched LOW-VALUES entry, so it is rejected here "
                    + "rather than folded into either");
        }
        if (screenField.length() != OPTION_NUMBER_WIDTH) {
            throw new IllegalArgumentException("screenField must be exactly " + OPTION_NUMBER_WIDTH
                    + " characters because OPTIONI is PIC X(2) at app/cpy-bms/COMEN01.CPY:L132, but was "
                    + screenField.length());
        }

        // Step 1 - the backward scan of :L117-L121. The COBOL PERFORM VARYING tests before the body and
        // its body is empty, so the loop settles on the position of the last byte that is not a space, or
        // on 1 when every byte scanned was a space.
        int lastNonSpace = screenField.length();
        while (screenField.charAt(lastNonSpace - 1) == ' ' && lastNonSpace > 1) {
            lastNonSpace--;
        }

        // Step 2 - MOVE OPTIONI(1:WS-IDX) TO WS-OPTION-X, a PIC X(02) JUST RIGHT field, so the prefix is
        // right-aligned and any shortfall is blank-filled on the LEFT.
        final String prefix = screenField.substring(0, lastNonSpace);
        final StringBuilder rightJustified = new StringBuilder(OPTION_NUMBER_WIDTH);
        for (int pad = prefix.length(); pad < OPTION_NUMBER_WIDTH; pad++) {
            rightJustified.append(' ');
        }
        rightJustified.append(prefix);

        // Step 3 - INSPECT ... REPLACING ALL ' ' BY '0'. Every blank becomes a zero, not just the pad.
        return rightJustified.toString().replace(' ', '0');
    }

    /**
     * Reproduces the {@code WS-OPTION IS NOT NUMERIC} class test of {@code app/cbl/COMEN01C.cbl:L127}, inverted
     * to report validity.
     *
     * @param normalisedEntry the output of {@link #normaliseOptionEntry(String)}.
     * @return {@code true} when every character is an ASCII decimal digit
     */
    private static boolean isNumericOptionEntry(final String normalisedEntry) {
        if (normalisedEntry.isEmpty()) {
            return false;
        }
        for (int position = 0; position < normalisedEntry.length(); position++) {
            final char character = normalisedEntry.charAt(position);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reproduces {@code MOVE WS-OPTION-X TO WS-OPTION} at {@code app/cbl/COMEN01C.cbl:L124}, yielding the
     * numeric selection.
     *
     * @param normalisedEntry the output of {@link #normaliseOptionEntry(String)}
     * @return the numeric value of the two-digit field, between 0 and 99
     * @throws IllegalArgumentException if the entry is not numeric, which the caller is expected to have tested
     * first with {@link #isNumericOptionEntry(String)}.
     */
    private static int toOptionNumber(final String normalisedEntry) {
        if (!isNumericOptionEntry(normalisedEntry)) {
            throw new IllegalArgumentException("normalisedEntry '" + normalisedEntry
                    + "' is not numeric, so WS-OPTION IS NOT NUMERIC at app/cbl/COMEN01C.cbl:L127 already "
                    + "rejected the selection; test isNumericOptionEntry before converting");
        }
        return Integer.parseInt(normalisedEntry);
    }

    /**
     * Reproduces {@code MOVE WS-OPTION TO OPTIONO} at {@code app/cbl/COMEN01C.cbl:L125}, the echo of the
     * accepted selection back onto the map.
     *
     * @param optionNumber the numeric selection to render
     * @return exactly {@link #OPTION_NUMBER_WIDTH} characters
     */
    private static String renderOptionEcho(final int optionNumber) {
        return String.format(Locale.ROOT, "%02d", optionNumber);
    }

    /**
     * Reproduces the three-condition bounds check of {@code app/cbl/COMEN01C.cbl:L127-L129}, identical at
     * {@code app/cbl/COADM01C.cbl:L127-L129} with its own count field substituted.
     *
     * @param normalisedEntry the output of {@link #normaliseOptionEntry(String)}
     * @param menuType the menu whose populated count bounds the selection
     * @return {@code true} when the selection is rejected
     */
    private static boolean isRejectedSelection(final String normalisedEntry, final MenuType menuType) {
        if (!isNumericOptionEntry(normalisedEntry)) {
            return true;
        }
        final int selection = Integer.parseInt(normalisedEntry);
        return selection > menuType.getPopulatedOptionCount() || selection == 0;
    }

    /**
     * Reproduces the user-type gate of {@code app/cbl/COMEN01C.cbl:L136-L137}, which exists on the <strong>main
     * menu only</strong>.
     *
     * @param signedOnUserType the {@code CDEMO-USER-TYPE} of the signed-on operator, or {@code null} when the
     * byte carried a code outside the two-code domain
     * @param option the selected main-menu option, whose raw gate byte is compared
     * @return {@code true} when the operator is a standard user and the option is administrator-only
     */
    private static boolean isDeniedByAdminGate(final UserType signedOnUserType, final MainMenuOption option) {
        return signedOnUserType == UserType.USER && option.userTypeCode() == ADMIN_USER_CODE;
    }

    /**
     * Reproduces the placeholder guard of {@code app/cbl/COMEN01C.cbl:L146} and
     * {@code app/cbl/COADM01C.cbl:L138}, {@code IF ...-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'}.
     *
     * @param programName the option's program name.
     * @return {@code true} when the first {@link #PLACEHOLDER_PREFIX_LENGTH} characters are exactly
     * {@link #PLACEHOLDER_SENTINEL}
     */
    private static boolean isPlaceholderProgram(final String programName) {
        if (programName == null || programName.length() < PLACEHOLDER_PREFIX_LENGTH) {
            return false;
        }
        return PLACEHOLDER_SENTINEL.equals(programName.substring(0, PLACEHOLDER_PREFIX_LENGTH));
    }

    /**
     * Reproduces the menu-line build of {@code app/cbl/COMEN01C.cbl:L243-L245}, identical at
     * {@code app/cbl/COADM01C.cbl:L233-L235}.
     *
     * @param option the option to render; either entry shape serves, since the three sub-fields the statement
     * reads are exactly the three the sealed {@link MenuOption} supertype declares
     * @return the concatenated line, {@link #RENDERED_MENU_LINE_WIDTH} characters when the caption is at its
     * declared width
     */
    private static String renderMenuLine(final MenuOption option) {
        return renderOptionEcho(option.optionNumber()) + MENU_LINE_SEPARATOR + option.optionName();
    }

    /**
     * Reproduces a COBOL {@code STRING ... DELIMITED BY SPACE} transfer, which stops at the first blank.
     *
     * <p>{@code app/cbl/COMEN01C.cbl:L160-L161} references the caption {@code DELIMITED BY SPACE} rather than
     * {@code BY SIZE}, so the notice built at {@code :L159-L163} carries only its first blank delimited word.
     *
     * @param sendingField the field being transferred
     * @return the content up to, but excluding, the first blank.
     */
    private static String delimitedBySpace(final String sendingField) {
        final int firstBlank = sendingField.indexOf(' ');
        return firstBlank < 0 ? sendingField : sendingField.substring(0, firstBlank);
    }

    /**
     * Reproduces the main-menu not-yet-implemented notice of {@code app/cbl/COMEN01C.cbl:L159-L163}.
     *
     * @param option the selected main-menu option
     * @return the notice exactly as the source composes it
     */
    private static String renderMainComingSoonNotice(final MainMenuOption option) {
        return COMING_SOON_PREFIX + delimitedBySpace(option.optionName()) + COMING_SOON_SUFFIX;
    }

    /**
     * Reproduces the admin-menu not-yet-implemented notice of {@code app/cbl/COADM01C.cbl:L149-L152}.
     *
     * @return the notice exactly as the source composes it, with no option caption
     */
    private static String renderAdminComingSoonNotice() {
        return COMING_SOON_PREFIX + COMING_SOON_SUFFIX;
    }

    /**
     * Returns the names of the record components of a record type, in declaration order.
     *
     * @param type the record type to inspect
     * @return the component names in declaration order, empty when {@code type} is not a record
     */
    private static List<String> recordComponentNames(final Class<?> type) {
        final List<String> names = new ArrayList<>();
        final RecordComponent[] components = type.getRecordComponents();
        if (components != null) {
            for (final RecordComponent component : components) {
                names.add(component.getName());
            }
        }
        return names;
    }

    /**
     * Returns the declared types of the record components of a record type, in declaration order.
     *
     * @param type the record type to inspect
     * @return the component type names in declaration order, empty when {@code type} is not a record
     */
    private static List<String> recordComponentTypeNames(final Class<?> type) {
        final List<String> names = new ArrayList<>();
        final RecordComponent[] components = type.getRecordComponents();
        if (components != null) {
            for (final RecordComponent component : components) {
                names.add(component.getType().getName());
            }
        }
        return names;
    }

    /**
     * Collects every member name declared by a type and, when it is a record, by its components: a single list
     * to screen for a forbidden name fragment.
     *
     * @param type the type to inspect
     * @return declared field names, method names and record component names, in no particular order
     */
    private static List<String> declaredMemberNames(final Class<?> type) {
        final List<String> names = new ArrayList<>();
        names.addAll(ReflectionCensus.declaredFieldNames(type, false));
        names.addAll(ReflectionCensus.declaredFieldNames(type, true));
        names.addAll(ReflectionCensus.declaredMethodNames(type));
        names.addAll(recordComponentNames(type));
        return names;
    }

    /**
     * Lower-cases a member name and strips every underscore, so that a fragment screen matches regardless of
     * whether the member is named in camel case, in screaming snake case or in the copybook's own hyphenated
     * style.
     *
     * @param memberName the declared member name
     * @return the name folded for comparison
     */
    private static String foldForFragmentScreen(final String memberName) {
        return memberName.toLowerCase(Locale.ROOT).replace("_", "");
    }

    /**
     * Asserts that no member of any of the given types carries any of the forbidden name fragments.
     *
     * @param forbiddenFragments the lower-case, underscore-free fragments that must not occur
     * @param types the types whose declared members are screened
     */
    private static void assertNoMemberNameContains(final List<String> forbiddenFragments, final Class<?>... types) {
        for (final Class<?> type : types) {
            for (final String memberName : declaredMemberNames(type)) {
                final String folded = foldForFragmentScreen(memberName);
                for (final String fragment : forbiddenFragments) {
                    assertThat(folded)
                            .as("member '%s' of %s must not contain the fragment '%s'",
                                    memberName, type.getSimpleName(), fragment)
                            .doesNotContain(fragment);
                }
            }
        }
    }

    /**
     * The two option tables are {@code WORKING-STORAGE} literals, so they are compile-time constants on the
     * payload and not rows of anything.
     */
    @Test
    @DisplayName("the option tables are copybook constants, so no schema is claimed for them")
    void optionTablesAreCopybookConstantsNotDatabaseTables() {
        assertThat(ReflectionCensus.declaredFieldNames(MenuResponse.class, true))
                .as("both tables are static constants of the payload")
                .contains("MAIN_MENU_OPTIONS", "ADMIN_MENU_OPTIONS");

        for (final Field field : MenuResponse.class.getDeclaredFields()) {
            if (field.getName().endsWith("_MENU_OPTIONS")) {
                assertThat(Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers()))
                        .as("%s must be a static final constant, not mutable shared state", field.getName())
                        .isTrue();
            }
        }

        assertThat(MenuResponse.MAIN_MENU_OPTIONS).hasSize(MAIN_POPULATED_COUNT);
        assertThat(MenuResponse.ADMIN_MENU_OPTIONS).hasSize(ADMIN_POPULATED_COUNT);

        assertThat(ReflectionCensus.declaredAnnotationTypeNames(MenuResponse.class))
                .as("a payload built from copybook literals carries no persistence or mapping annotation")
                .isEmpty();
    }

    /**
     * One payload type serves both menus, and it does so without unifying their two entry shapes.
     */
    @Test
    @DisplayName("one payload serves both menus while keeping the two entry shapes separate")
    void onePayloadServesBothMenusWithoutUnifyingTheShapes() {
        final MenuResponse<MainMenuOption> main = MenuResponse.mainMenu();
        final MenuResponse<AdminMenuOption> admin = MenuResponse.adminMenu();

        assertThat(main.getMenuType()).isEqualTo(MenuType.MAIN);
        assertThat(admin.getMenuType()).isEqualTo(MenuType.ADMIN);

        assertThat(main.getOptions()).allMatch(option -> option instanceof MainMenuOption);
        assertThat(admin.getOptions()).allMatch(option -> option instanceof AdminMenuOption);

        assertThat(main).isNotEqualTo(admin);
        assertThat(MenuType.values()).containsExactly(MenuType.MAIN, MenuType.ADMIN);
    }

    /**
     * The payload carries the option table and decides nothing, which is what keeps the authorisation boundary
     * in the service.
     */
    @Test
    @DisplayName("declares no method that evaluates the bounds check, the admin gate or the DUMMY guard")
    void payloadDecidesNothing() {
        final List<String> decisionFragments = List.of(
                "isallowed", "ispermitted", "haspermission", "canaccess", "authorize", "authorise",
                "isvalidoption", "validateoption", "isadminonly", "isplaceholder", "isdummy", "isrejected");

        assertNoMemberNameContains(decisionFragments,
                MenuResponse.class, MenuType.class, MenuOption.class, MainMenuOption.class,
                AdminMenuOption.class);

        // The sharper form of the same statement: a payload that decides nothing exposes no predicate at
        // all, so no method may return boolean. Value equality is the single exception, and it answers a
        // question about two payloads rather than about a selection or an operator.
        for (final Class<?> type : List.of(MenuResponse.class, MenuType.class, MenuOption.class,
                MainMenuOption.class, AdminMenuOption.class)) {
            for (final Method method : type.getDeclaredMethods()) {
                if (!method.isSynthetic() && !method.isBridge() && !"equals".equals(method.getName())) {
                    assertThat(method.getReturnType())
                            .as("%s.%s must not be a predicate: only equals may return boolean on a payload "
                                    + "that decides nothing", type.getSimpleName(), method.getName())
                            .isNotEqualTo(boolean.class);
                }
            }
        }
    }

    @Nested
    @DisplayName("Declared surface")
    class DeclaredSurface {

        @Test
        @DisplayName("declares exactly the menu discriminator and the option list as instance state")
        void declaresExactlyTwoInstanceFields() {
            assertThat(ReflectionCensus.declaredFieldNames(MenuResponse.class, false))
                    .containsExactlyInAnyOrder("menuType", "options");
        }

        @Test
        @DisplayName("declares exactly the four width and range constants plus the two option tables")
        void declaresExactlyTheDocumentedConstants() {
            assertThat(ReflectionCensus.declaredFieldNames(MenuResponse.class, true))
                    .containsExactlyInAnyOrder(
                            "OPTION_NAME_LENGTH",
                            "PROGRAM_NAME_LENGTH",
                            "OPTION_NUMBER_MAX_VALUE",
                            "SCREEN_OPTION_SLOT_LENGTH",
                            "MAIN_MENU_OPTIONS",
                            "ADMIN_MENU_OPTIONS");
        }

        @Test
        @DisplayName("declares exactly the four factories, three accessors, three value methods and three "
                + "validation guards")
        void declaresExactlyTheDocumentedMethods() {
            assertThat(ReflectionCensus.declaredMethodNames(MenuResponse.class))
                    .containsExactlyInAnyOrder(
                            "mainMenu", "ofMainMenu", "adminMenu", "ofAdminMenu",
                            "getMenuType", "getOptions", "getOptionCount",
                            "equals", "hashCode", "toString",
                            "requireOptionNumberInRange", "requireNonNullField",
                            "requireWidthWithinLimit");
        }

        @Test
        @DisplayName("declares one guard per documented invariant, and no guard without an invariant")
        void everyValidationGuardCorrespondsToADeclaredInvariant() {
            final List<String> guards = ReflectionCensus.declaredMethodNames(MenuResponse.class).stream()
                    .filter(name -> name.startsWith("require"))
                    .sorted()
                    .toList();

            assertThat(guards)
                    .as("the three published invariants are the option-number range, the non-null caption "
                            + "and program name, and the declared width of each - so there are exactly "
                            + "three guards, and adding a fourth without a documented invariant would be "
                            + "unreachable surface")
                    .containsExactly("requireNonNullField", "requireOptionNumberInRange",
                            "requireWidthWithinLimit");

            assertThat(MenuResponse.OPTION_NUMBER_MAX_VALUE).isEqualTo(99);
            assertThat(MenuResponse.OPTION_NAME_LENGTH).isEqualTo(35);
            assertThat(MenuResponse.PROGRAM_NAME_LENGTH).isEqualTo(8);
        }

        @Test
        @DisplayName("is final, offers only a private constructor, and exposes no setter")
        void isFinalAndHasNoSetter() {
            assertThat(Modifier.isFinal(MenuResponse.class.getModifiers()))
                    .as("a response payload must not be subclassable")
                    .isTrue();

            final Constructor<?>[] constructors = MenuResponse.class.getDeclaredConstructors();
            assertThat(constructors).hasSize(1);
            assertThat(Modifier.isPrivate(constructors[0].getModifiers()))
                    .as("the only way in is a factory, so a menu type can never be paired with the wrong "
                            + "entry shape")
                    .isTrue();

            assertThat(ReflectionCensus.declaredMethodNames(MenuResponse.class))
                    .noneMatch(name -> name.startsWith("set"));
        }

        @Test
        @DisplayName("every factory supplies a menu type, so the constructor's null guard is unreachable depth")
        void everyFactorySuppliesAMenuType() {
            assertThat(MenuResponse.mainMenu().getMenuType()).isEqualTo(MenuType.MAIN);
            assertThat(MenuResponse.ofMainMenu(new ArrayList<>()).getMenuType()).isEqualTo(MenuType.MAIN);
            assertThat(MenuResponse.adminMenu().getMenuType()).isEqualTo(MenuType.ADMIN);
            assertThat(MenuResponse.ofAdminMenu(new ArrayList<>()).getMenuType()).isEqualTo(MenuType.ADMIN);

            assertThat(ReflectionCensus.declaredMethodNames(MenuResponse.class))
                    .as("these four are the only entry points, and each names its menu type as a constant")
                    .contains("mainMenu", "ofMainMenu", "adminMenu", "ofAdminMenu");

            final Constructor<?> soleConstructor = MenuResponse.class.getDeclaredConstructors()[0];
            assertThat(Modifier.isPrivate(soleConstructor.getModifiers()))
                    .as("because the sole constructor is private and all four factories pass a constant, the "
                            + "constructor's own null-menuType guard cannot be reached through the public API "
                            + "- it is defence in depth, and it is deliberately NOT provoked reflectively: "
                            + "Rule 1 clause D flags reflection-driven arbitrary invocation as a risky "
                            + "pattern, and forcing the branch open would be coverage padding rather than a "
                            + "behavioural assertion")
                    .isTrue();
        }

        @Test
        @DisplayName("carries no annotation anywhere, so no class-level constraint fires unconditionally")
        void carriesNoAnnotation() {
            assertThat(ReflectionCensus.declaredAnnotationTypeNames(MenuResponse.class)).isEmpty();
            assertThat(ReflectionCensus.declaredAnnotationTypeNames(MenuType.class)).isEmpty();
            assertThat(ReflectionCensus.declaredAnnotationTypeNames(MenuOption.class)).isEmpty();
            assertThat(ReflectionCensus.declaredAnnotationTypeNames(MainMenuOption.class)).isEmpty();
            assertThat(ReflectionCensus.declaredAnnotationTypeNames(AdminMenuOption.class)).isEmpty();
        }

        @Test
        @DisplayName("carries no COMMAREA routing or retained screen-state member")
        void carriesNoSessionState() {
            assertNoMemberNameContains(FORBIDDEN_SESSION_STATE_FRAGMENTS,
                    MenuResponse.class, MenuType.class, MenuOption.class, MainMenuOption.class,
                    AdminMenuOption.class);

            assertThat(COMMAREA_LAST_MAP_WIDTH)
                    .as("CDEMO-LAST-MAP and CDEMO-LAST-MAPSET are X(7) at app/cpy/COCOM01Y.cpy:L43-L44, "
                            + "and both are screen state that a stateless payload must not carry")
                    .isEqualTo(7);
        }

        @Test
        @DisplayName("declares the screen header inline on the map projection and shares it with nothing")
        void theScreenHeaderIsDeclaredInlineRatherThanShared() {
            assertThat(recordComponentNames(MenuScreen.class))
                    .as("the six recurring header fields of app/cpy-bms/COMEN01.CPY:L24-L54 belong on the "
                            + "map projection, because the map declares them - what must not exist is a "
                            + "type that hands them to more than one map")
                    .contains("transactionName", "title01", "currentDate", "programName", "title02",
                            "currentTime");

            assertThat(MenuScreen.class.getInterfaces())
                    .as("MenuScreen implements nothing, so it cannot be inheriting a header contract")
                    .isEmpty();
            assertThat(MenuScreen.class.getSuperclass())
                    .as("its only supertype is java.lang.Record, which carries no field of its own, so the "
                            + "six header components are declared here and nowhere else")
                    .isEqualTo(Record.class);

            assertThat(MenuScreen.TIME_WIDTH)
                    .as("CURTIMEI is X(8) at app/cpy-bms/COMEN01.CPY:L54 and X(9) at "
                            + "app/cpy-bms/COSGN00.CPY:L54, so a shared header type would have to publish "
                            + "one width the corpus does not agree on - declaring the field inline per map "
                            + "is the only faithful option")
                    .isEqualTo(MENU_MAP_HEADER_TIME_WIDTH)
                    .isNotEqualTo(SIGN_ON_HEADER_TIME_WIDTH);
        }

        @Test
        @DisplayName("carries no BMS common-header member on the option surface, whose shape is a table")
        void carriesNoSharedScreenHeader() {
            final List<String> headerFragments = List.of(
                    "trnname", "title01", "title02", "curdate", "curtime", "pgmname", "errmsg");

            for (final Class<?> type : List.of(MenuResponse.class, MenuType.class, MenuOption.class,
                    MainMenuOption.class, AdminMenuOption.class)) {
                for (final String memberName : declaredMemberNames(type)) {
                    final String folded = foldForFragmentScreen(memberName);
                    for (final String fragment : headerFragments) {
                        assertThat(folded)
                                .as("member '%s' of %s must not surface the common header field '%s'",
                                        memberName, type.getSimpleName(), fragment)
                                .isNotEqualTo(fragment);
                    }
                }
            }

            assertThat(MENU_MAP_HEADER_TIME_WIDTH)
                    .as("CURTIMEI is X(8) on both menu maps but X(9) on app/cpy-bms/COSGN00.CPY:L54, so no "
                            + "shared header base class, interface or mixin can be correct for all "
                            + "seventeen maps. The header belongs to the map projection, which declares it "
                            + "inline; the option records transcribe app/cpy/COMEN02Y.cpy and "
                            + "app/cpy/COADM02Y.cpy, which are WORKING-STORAGE tables with no header field "
                            + "at all, so a header member here would be invented rather than translated")
                    .isNotEqualTo(SIGN_ON_HEADER_TIME_WIDTH);
        }

        @Test
        @DisplayName("exposes no framework, persistence or Spring type on its surface")
        void exposesNoFrameworkType() {
            final List<String> forbiddenPackages = List.of(
                    "org.springframework", "jakarta.persistence", "jakarta.validation",
                    "com.fasterxml.jackson", "software.amazon.awssdk", "io.awspring");

            for (final Class<?> type : List.of(MenuResponse.class, MenuType.class, MenuOption.class,
                    MainMenuOption.class, AdminMenuOption.class)) {
                for (final String typeName : ReflectionCensus.declaredSurfaceTypeNames(type)) {
                    for (final String forbidden : forbiddenPackages) {
                        assertThat(typeName)
                                .as("the surface of %s must stay free of %s", type.getSimpleName(), forbidden)
                                .doesNotContain(forbidden);
                    }
                }
            }
        }

        @Test
        @DisplayName("depends on exactly one other CardDemo type, the user-type enumeration")
        void dependsOnExactlyOneCardDemoType() {
            final List<String> cardDemoTypes = new ArrayList<>();
            for (final Class<?> type : List.of(MenuResponse.class, MenuOption.class, MainMenuOption.class,
                    AdminMenuOption.class)) {
                for (final String typeName : ReflectionCensus.declaredSurfaceTypeNames(type)) {
                    if (typeName.startsWith("com.cardemo.") && !typeName.contains("MenuResponse")
                            && !cardDemoTypes.contains(typeName)) {
                        cardDemoTypes.add(typeName);
                    }
                }
            }
            assertThat(cardDemoTypes).containsExactly(UserType.class.getName());
        }
    }

    @Nested
    @DisplayName("The populated count bounds the table; the OCCURS arity never does")
    class CountVersusOccursArity {

        @Test
        @DisplayName("the two figures are different facts, and both tables over-declare their overlay")
        void countAndArityAreDifferentFigures() {
            assertThat(MenuType.MAIN.getPopulatedOptionCount()).isEqualTo(MAIN_POPULATED_COUNT);
            assertThat(MenuType.MAIN.getDeclaredCapacity()).isEqualTo(MAIN_DECLARED_ARITY);
            assertThat(MenuType.ADMIN.getPopulatedOptionCount()).isEqualTo(ADMIN_POPULATED_COUNT);
            assertThat(MenuType.ADMIN.getDeclaredCapacity()).isEqualTo(ADMIN_DECLARED_ARITY);

            assertThat(MenuType.MAIN.getPopulatedOptionCount())
                    .as("if these were equal there would be no over-run to guard against, and the "
                            + "distinction this whole group exists to protect would be vacuous")
                    .isLessThan(MenuType.MAIN.getDeclaredCapacity());
            assertThat(MenuType.ADMIN.getPopulatedOptionCount())
                    .isLessThan(MenuType.ADMIN.getDeclaredCapacity());
        }

        @Test
        @DisplayName("the overlay over-runs the populated data by 92 bytes on main and 225 on admin")
        void overlayOverRunsThePopulatedData() {
            final int mainPopulatedBytes = MAIN_POPULATED_COUNT * MAIN_ENTRY_WIDTH;
            final int mainOverlayBytes = MAIN_DECLARED_ARITY * MAIN_ENTRY_WIDTH;
            assertThat(mainPopulatedBytes).isEqualTo(460);
            assertThat(mainOverlayBytes).isEqualTo(552);
            assertThat(mainOverlayBytes - mainPopulatedBytes)
                    .as("subscripts 11 and 12 of CDEMO-MENU-OPT address these bytes, which no VALUE literal "
                            + "in app/cpy/COMEN02Y.cpy:L25-L84 ever reaches")
                    .isEqualTo(92);

            final int adminPopulatedBytes = ADMIN_POPULATED_COUNT * ADMIN_ENTRY_WIDTH;
            final int adminOverlayBytes = ADMIN_DECLARED_ARITY * ADMIN_ENTRY_WIDTH;
            assertThat(adminPopulatedBytes).isEqualTo(180);
            assertThat(adminOverlayBytes).isEqualTo(405);
            assertThat(adminOverlayBytes - adminPopulatedBytes)
                    .as("subscripts 5 through 9 of CDEMO-ADMIN-OPT address these bytes, which no VALUE "
                            + "literal in app/cpy/COADM02Y.cpy:L24-L42 ever reaches")
                    .isEqualTo(225);
        }

        @Test
        @DisplayName("the unfiltered payloads carry exactly ten and exactly four options")
        void unfilteredPayloadsCarryTheCountNotTheArity() {
            assertThat(MenuResponse.mainMenu().getOptionCount()).isEqualTo(MAIN_POPULATED_COUNT);
            assertThat(MenuResponse.mainMenu().getOptions()).hasSize(MAIN_POPULATED_COUNT);
            assertThat(MenuResponse.adminMenu().getOptionCount()).isEqualTo(ADMIN_POPULATED_COUNT);
            assertThat(MenuResponse.adminMenu().getOptions()).hasSize(ADMIN_POPULATED_COUNT);

            assertThat(MenuResponse.mainMenu().getOptionCount()).isNotEqualTo(MAIN_DECLARED_ARITY);
            assertThat(MenuResponse.adminMenu().getOptionCount()).isNotEqualTo(ADMIN_DECLARED_ARITY);
        }

        @Test
        @DisplayName("every index from the count up to the arity is unreachable on the main menu")
        void mainIndicesBeyondTheCountAreUnreachable() {
            final List<MainMenuOption> options = MenuResponse.mainMenu().getOptions();

            for (int index = MAIN_POPULATED_COUNT; index < MAIN_DECLARED_ARITY; index++) {
                final int unpopulated = index;
                assertThatExceptionOfType(IndexOutOfBoundsException.class)
                        .as("subscript %d is unpopulated overlay storage and its contents are Not available",
                                unpopulated + 1)
                        .isThrownBy(() -> options.get(unpopulated));
            }

            assertThat(options.get(MAIN_POPULATED_COUNT - 1))
                    .as("the last populated subscript is still reachable")
                    .isNotNull();
        }

        @Test
        @DisplayName("every index from the count up to the arity is unreachable on the admin menu")
        void adminIndicesBeyondTheCountAreUnreachable() {
            final List<AdminMenuOption> options = MenuResponse.adminMenu().getOptions();

            for (int index = ADMIN_POPULATED_COUNT; index < ADMIN_DECLARED_ARITY; index++) {
                final int unpopulated = index;
                assertThatExceptionOfType(IndexOutOfBoundsException.class)
                        .as("subscript %d is unpopulated overlay storage and its contents are Not available",
                                unpopulated + 1)
                        .isThrownBy(() -> options.get(unpopulated));
            }

            assertThat(options.get(ADMIN_POPULATED_COUNT - 1)).isNotNull();
        }

        @Test
        @DisplayName("a list of exactly the count is accepted and one more is refused, naming both figures")
        void constructionIsBoundedByTheCount() {
            final List<MainMenuOption> exactlyTen = new ArrayList<>(MenuResponse.MAIN_MENU_OPTIONS);
            assertThat(MenuResponse.ofMainMenu(exactlyTen).getOptionCount()).isEqualTo(MAIN_POPULATED_COUNT);

            final List<MainMenuOption> eleven = new ArrayList<>(MenuResponse.MAIN_MENU_OPTIONS);
            eleven.add(new MainMenuOption(11, EXPECTED_MAIN_CAPTIONS.get(0), "COACTVWC", STANDARD_USER_CODE));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> MenuResponse.ofMainMenu(eleven))
                    .withMessageContaining("11")
                    .withMessageContaining(String.valueOf(MAIN_POPULATED_COUNT))
                    .withMessageContaining(String.valueOf(MAIN_DECLARED_ARITY))
                    .withNoCause();

            final List<MainMenuOption> twelve = new ArrayList<>(eleven);
            twelve.add(new MainMenuOption(12, EXPECTED_MAIN_CAPTIONS.get(1), "COACTUPC", STANDARD_USER_CODE));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("filling the overlay to its arity is exactly the mistake the bound exists to stop")
                    .isThrownBy(() -> MenuResponse.ofMainMenu(twelve))
                    .withMessageContaining("12");
        }

        @Test
        @DisplayName("the admin list is bounded at four, so subscripts five through nine cannot be supplied")
        void adminConstructionIsBoundedAtFour() {
            final List<AdminMenuOption> exactlyFour = new ArrayList<>(MenuResponse.ADMIN_MENU_OPTIONS);
            assertThat(MenuResponse.ofAdminMenu(exactlyFour).getOptionCount()).isEqualTo(ADMIN_POPULATED_COUNT);

            final List<AdminMenuOption> overfilled = new ArrayList<>(MenuResponse.ADMIN_MENU_OPTIONS);
            for (int subscript = ADMIN_POPULATED_COUNT + 1; subscript <= ADMIN_DECLARED_ARITY; subscript++) {
                overfilled.add(new AdminMenuOption(subscript, EXPECTED_ADMIN_CAPTIONS.get(0), "COUSR00C"));
                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("a list of %d admin options reaches into unpopulated overlay storage",
                                overfilled.size())
                        .isThrownBy(() -> MenuResponse.ofAdminMenu(overfilled))
                        .withMessageContaining(String.valueOf(ADMIN_POPULATED_COUNT));
            }
            assertThat(overfilled).hasSize(ADMIN_DECLARED_ARITY);
        }

        @Test
        @DisplayName("the selection bounds check rejects 11 and 12 on main and 5 through 9 on admin")
        void selectionBoundsCheckUsesTheCount() {
            assertThat(isRejectedSelection(normaliseOptionEntry("10"), MenuType.MAIN))
                    .as("option 10 is the last populated main option and must be accepted")
                    .isFalse();
            assertThat(isRejectedSelection(normaliseOptionEntry("11"), MenuType.MAIN)).isTrue();
            assertThat(isRejectedSelection(normaliseOptionEntry("12"), MenuType.MAIN)).isTrue();

            assertThat(isRejectedSelection(normaliseOptionEntry(" 4"), MenuType.ADMIN))
                    .as("option 4 is the last populated admin option and must be accepted")
                    .isFalse();
            assertThat(isRejectedSelection(normaliseOptionEntry(" 5"), MenuType.ADMIN)).isTrue();
            assertThat(isRejectedSelection(normaliseOptionEntry(" 9"), MenuType.ADMIN)).isTrue();

            assertThat(isRejectedSelection(normaliseOptionEntry(" 5"), MenuType.MAIN))
                    .as("option 5 is populated on the main menu, so the same entry is accepted there and "
                            + "refused on the admin menu - the bound is per table, not global")
                    .isFalse();
        }

        @Test
        @DisplayName("the screen declares twelve caption slots on both maps, which is not an option count")
        void screenSlotCountIsNotAnOptionCount() {
            assertThat(MENU_MAP_CAPTION_SLOT_COUNT)
                    .as("app/cpy-bms/COADM01.CPY declares twelve X(40) slots while its table declares nine "
                            + "subscripts and populates four, so the slot count cannot be an option count")
                    .isNotEqualTo(ADMIN_POPULATED_COUNT)
                    .isNotEqualTo(ADMIN_DECLARED_ARITY);

            assertThat(MENU_MAP_INPUT_FIELD_COUNT)
                    .as("six header fields plus twelve caption slots plus OPTIONI plus ERRMSGI")
                    .isEqualTo(6 + MENU_MAP_CAPTION_SLOT_COUNT + 2);
        }
    }

    @Nested
    @DisplayName("The two entry shapes differ and are not unified")
    class EntryShapeAsymmetry {

        @Test
        @DisplayName("a main entry has four sub-fields and an admin entry has three")
        void mainEntryHasFourSubFieldsAndAdminThree() {
            assertThat(recordComponentNames(MainMenuOption.class))
                    .as("CDEMO-MENU-OPT sub-fields at app/cpy/COMEN02Y.cpy:L89-L92, in declaration order")
                    .containsExactly("optionNumber", "optionName", "programName", "userTypeCode");

            assertThat(recordComponentNames(AdminMenuOption.class))
                    .as("CDEMO-ADMIN-OPT sub-fields at app/cpy/COADM02Y.cpy:L46-L48; the entry ENDS at the "
                            + "program name on L48")
                    .containsExactly("optionNumber", "optionName", "programName");
        }

        @Test
        @DisplayName("the admin entry has no user-type member under any name")
        void adminEntryHasNoUserTypeMember() {
            for (final String memberName : declaredMemberNames(AdminMenuOption.class)) {
                assertThat(foldForFragmentScreen(memberName))
                        .as("member '%s' of AdminMenuOption must not model a user type: "
                                + "app/cpy/COADM02Y.cpy declares none, and inventing one is a Blocker",
                                memberName)
                        .doesNotContain("usertype")
                        .doesNotContain("usrtype");
            }

            assertThat(ReflectionCensus.declaredSurfaceTypeNames(AdminMenuOption.class))
                    .as("no admin member may even mention the user-type enumeration")
                    .doesNotContain(UserType.class.getName());
        }

        @Test
        @DisplayName("the shared supertype declares only the three genuinely shared sub-fields")
        void sharedSupertypeDeclaresOnlyTheSharedSubFields() {
            assertThat(ReflectionCensus.declaredMethodNames(MenuOption.class))
                    .as("declaring a user-type accessor here would invent one for the admin table, so the "
                            + "asymmetry is expressed as the absence of a member")
                    .containsExactlyInAnyOrder("optionNumber", "optionName", "programName");
        }

        @Test
        @DisplayName("the hierarchy is sealed to exactly the two tables the corpus declares")
        void hierarchyIsSealedToTheTwoTables() {
            assertThat(MenuOption.class.isSealed()).isTrue();

            final List<String> permitted = new ArrayList<>();
            for (final Class<?> subtype : MenuOption.class.getPermittedSubclasses()) {
                permitted.add(subtype.getSimpleName());
            }
            assertThat(permitted).containsExactlyInAnyOrder("MainMenuOption", "AdminMenuOption");
        }

        @Test
        @DisplayName("only the main entry resolves a user type, and it carries the raw byte alongside")
        void onlyMainEntryResolvesAUserType() {
            final MainMenuOption main = MenuResponse.MAIN_MENU_OPTIONS.get(0);
            assertThat(main.userTypeCode()).isEqualTo(STANDARD_USER_CODE);
            assertThat(main.userType()).isEqualTo(UserType.USER);

            assertThat(ReflectionCensus.declaredMethodNames(MainMenuOption.class)).contains("userType", "userTypeCode");
            assertThat(ReflectionCensus.declaredMethodNames(AdminMenuOption.class))
                    .doesNotContain("userType", "userTypeCode");
        }

        @Test
        @DisplayName("no admin option is silently defaulted to a user type of A or U")
        void noAdminOptionIsDefaultedToAUserType() {
            for (final AdminMenuOption option : MenuResponse.ADMIN_MENU_OPTIONS) {
                final List<String> componentValues = new ArrayList<>();
                componentValues.add(option.optionName());
                componentValues.add(option.programName());
                for (final String value : componentValues) {
                    assertThat(value)
                            .as("no admin component may smuggle a gate byte in as data")
                            .isNotEqualTo(String.valueOf(ADMIN_USER_CODE))
                            .isNotEqualTo(String.valueOf(STANDARD_USER_CODE));
                }
                assertThat(option.toString())
                        .as("the rendering of an admin option cannot report a user type it does not have")
                        .doesNotContain("userType");
            }
        }
    }

    @Nested
    @DisplayName("Field widths: DELIMITED BY SIZE emits the full declared width")
    class FieldWidths {

        @Test
        @DisplayName("the published widths are the table's, taken from the copybook sub-fields")
        void publishedWidthsMatchTheCopybook() {
            assertThat(MenuResponse.OPTION_NAME_LENGTH)
                    .as("CDEMO-MENU-OPT-NAME PIC X(35) at app/cpy/COMEN02Y.cpy:L90 and "
                            + "CDEMO-ADMIN-OPT-NAME PIC X(35) at app/cpy/COADM02Y.cpy:L47")
                    .isEqualTo(35);
            assertThat(MenuResponse.PROGRAM_NAME_LENGTH)
                    .as("CDEMO-MENU-OPT-PGMNAME PIC X(08) at app/cpy/COMEN02Y.cpy:L91 and "
                            + "CDEMO-ADMIN-OPT-PGMNAME PIC X(08) at app/cpy/COADM02Y.cpy:L48")
                    .isEqualTo(8);
            assertThat(MenuResponse.OPTION_NUMBER_MAX_VALUE)
                    .as("an unsigned PIC 9(02) display field represents 0 through 99")
                    .isEqualTo(99);
            assertThat(MenuResponse.SCREEN_OPTION_SLOT_LENGTH)
                    .as("OPTN0nnI PIC X(40) on both display maps, five bytes wider than the caption "
                            + "because the slot holds a rendered line rather than a caption")
                    .isEqualTo(40)
                    .isNotEqualTo(MenuResponse.OPTION_NAME_LENGTH);
        }

        @Test
        @DisplayName("every one of the ten main captions is exactly 35 characters with its padding intact")
        void mainCaptionsCarryTheirPadding() {
            final List<MainMenuOption> options = MenuResponse.MAIN_MENU_OPTIONS;
            for (int index = 0; index < options.size(); index++) {
                final MainMenuOption option = options.get(index);
                assertThat(option.optionName())
                        .as("main caption %d must be the VALUE literal byte for byte", index + 1)
                        .isEqualTo(EXPECTED_MAIN_CAPTIONS.get(index))
                        .hasSize(MenuResponse.OPTION_NAME_LENGTH);
                assertThat(option.optionName())
                        .as("trimming main caption %d would break the DELIMITED BY SIZE contract at "
                                + "app/cbl/COMEN01C.cbl:L245", index + 1)
                        .isNotEqualTo(option.optionName().trim());
            }
        }

        @Test
        @DisplayName("every one of the four admin captions is exactly 35 characters, (Security) suffix and all")
        void adminCaptionsCarryTheirPadding() {
            final List<AdminMenuOption> options = MenuResponse.ADMIN_MENU_OPTIONS;
            for (int index = 0; index < options.size(); index++) {
                final AdminMenuOption option = options.get(index);
                assertThat(option.optionName())
                        .isEqualTo(EXPECTED_ADMIN_CAPTIONS.get(index))
                        .hasSize(MenuResponse.OPTION_NAME_LENGTH)
                        .contains("(Security)");
                assertThat(option.optionName()).isNotEqualTo(option.optionName().trim());
            }
        }

        @Test
        @DisplayName("all fourteen program names are exactly eight characters")
        void programNamesAreExactlyEightCharacters() {
            final List<String> observed = new ArrayList<>();
            for (final MainMenuOption option : MenuResponse.MAIN_MENU_OPTIONS) {
                observed.add(option.programName());
                assertThat(option.programName()).hasSize(MenuResponse.PROGRAM_NAME_LENGTH);
            }
            assertThat(observed).containsExactlyElementsOf(EXPECTED_MAIN_PROGRAM_NAMES);

            final List<String> adminObserved = new ArrayList<>();
            for (final AdminMenuOption option : MenuResponse.ADMIN_MENU_OPTIONS) {
                adminObserved.add(option.programName());
                assertThat(option.programName()).hasSize(MenuResponse.PROGRAM_NAME_LENGTH);
            }
            assertThat(adminObserved).containsExactlyElementsOf(EXPECTED_ADMIN_PROGRAM_NAMES);
        }

        @Test
        @DisplayName("the option number renders zero-padded to two digits, so option 1 is 01 and not 1")
        void optionNumberRendersZeroPadded() {
            assertThat(renderOptionEcho(1)).isEqualTo("01").hasSize(OPTION_NUMBER_WIDTH);
            assertThat(renderOptionEcho(4)).isEqualTo("04");
            assertThat(renderOptionEcho(9)).isEqualTo("09");
            assertThat(renderOptionEcho(10)).isEqualTo("10").hasSize(OPTION_NUMBER_WIDTH);
            assertThat(renderOptionEcho(0)).isEqualTo("00");
            assertThat(renderOptionEcho(MenuResponse.OPTION_NUMBER_MAX_VALUE)).isEqualTo("99");

            for (final MainMenuOption option : MenuResponse.MAIN_MENU_OPTIONS) {
                assertThat(renderOptionEcho(option.optionNumber())).hasSize(OPTION_NUMBER_WIDTH);
            }
        }

        @Test
        @DisplayName("the rendered menu line is 39 characters inside a 40-byte slot")
        void renderedMenuLineIsThirtyNineOfForty() {
            for (final MainMenuOption option : MenuResponse.MAIN_MENU_OPTIONS) {
                final String line = renderMenuLine(option);
                assertThat(line)
                        .as("two digits, the '. ' separator and the 35-character caption")
                        .hasSize(RENDERED_MENU_LINE_WIDTH);
                assertThat(line).startsWith(renderOptionEcho(option.optionNumber()) + MENU_LINE_SEPARATOR);
                assertThat(line).endsWith(option.optionName());
            }

            for (final AdminMenuOption option : MenuResponse.ADMIN_MENU_OPTIONS) {
                assertThat(renderMenuLine(option)).hasSize(RENDERED_MENU_LINE_WIDTH);
            }

            assertThat(RENDERED_MENU_LINE_WIDTH)
                    .as("the slot is one byte wider, and that byte stays the blank left by MOVE SPACES at "
                            + "app/cbl/COMEN01C.cbl:L241")
                    .isEqualTo(MenuResponse.SCREEN_OPTION_SLOT_LENGTH - 1);

            assertThat(renderMenuLine(MenuResponse.MAIN_MENU_OPTIONS.get(0)))
                    .isEqualTo("01. Account View                       ");
            assertThat(renderMenuLine(MenuResponse.ADMIN_MENU_OPTIONS.get(3)))
                    .isEqualTo("04. User Delete (Security)             ");
        }

        @Test
        @DisplayName("option 8 carries the live caption, never the commented-out variant")
        void optionEightCarriesTheLiveCaption() {
            final MainMenuOption optionEight = MenuResponse.MAIN_MENU_OPTIONS.get(7);

            assertThat(optionEight.optionNumber()).isEqualTo(8);
            assertThat(optionEight.optionName())
                    .as("the live literal on app/cpy/COMEN02Y.cpy:L70")
                    .isEqualTo(LIVE_OPTION_8_CAPTION);
            assertThat(optionEight.optionName())
                    .as("the variant on app/cpy/COMEN02Y.cpy:L69 is a COBOL comment - preserved legacy "
                            + "residue, recorded here and never reproduced as live data")
                    .isNotEqualTo(WITHDRAWN_OPTION_8_CAPTION)
                    .doesNotContain("Admin Only");

            assertThat(WITHDRAWN_OPTION_8_CAPTION)
                    .as("both literals are 35 characters, which is why the substitution was invisible")
                    .hasSize(MenuResponse.OPTION_NAME_LENGTH);

            assertThat(optionEight.userTypeCode())
                    .as("the withdrawn caption said Admin Only, yet the live gate byte is 'U', so option 8 "
                            + "is NOT administrator-only; the caption was withdrawn, not the restriction")
                    .isEqualTo(STANDARD_USER_CODE);
        }

        @Test
        @DisplayName("a caption or program name wider than its declared width is refused, naming the field")
        void overWideTextIsRefusedRatherThanStored() {
            final String thirtySixCharacterCaption = "A".repeat(MenuResponse.OPTION_NAME_LENGTH + 1);
            final String nineCharacterProgramName = "B".repeat(MenuResponse.PROGRAM_NAME_LENGTH + 1);
            final String admissibleCaption = EXPECTED_MAIN_CAPTIONS.get(0);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("CDEMO-MENU-OPT-NAME is PIC X(35) at app/cpy/COMEN02Y.cpy:L90, so a thirty-sixth "
                            + "character is a value the source field could never have held; storing it "
                            + "would assert a contract the copybook does not have, and truncating it would "
                            + "lose data, so it is refused outright")
                    .isThrownBy(() -> new MainMenuOption(1, thirtySixCharacterCaption, "COACTVWC",
                            STANDARD_USER_CODE))
                    .withMessageContaining("optionName")
                    .withMessageContaining("PIC X(35)")
                    .withMessageContaining("35")
                    .withNoCause();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("CDEMO-MENU-OPT-PGMNAME is PIC X(08) at app/cpy/COMEN02Y.cpy:L91, and no load "
                            + "module in the corpus has a nine-character name")
                    .isThrownBy(() -> new MainMenuOption(1, admissibleCaption, nineCharacterProgramName,
                            STANDARD_USER_CODE))
                    .withMessageContaining("programName")
                    .withMessageContaining("PIC X(08)")
                    .withNoCause();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the admin record applies the identical bound, from app/cpy/COADM02Y.cpy:L47")
                    .isThrownBy(() -> new AdminMenuOption(1, thirtySixCharacterCaption, "COUSR00C"))
                    .withMessageContaining("optionName")
                    .withMessageContaining("PIC X(35)")
                    .withNoCause();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("and so does the admin program name, from app/cpy/COADM02Y.cpy:L48")
                    .isThrownBy(() -> new AdminMenuOption(1, EXPECTED_ADMIN_CAPTIONS.get(0),
                            nineCharacterProgramName))
                    .withMessageContaining("programName")
                    .withMessageContaining("PIC X(08)")
                    .withNoCause();
        }

        @Test
        @DisplayName("the width bound is an upper bound: a shorter value is stored unpadded and untrimmed")
        void widthBoundIsAnUpperBoundRatherThanAnExactRequirement() {
            final MainMenuOption shortCaption = new MainMenuOption(1, "View", "COACTVWC",
                    STANDARD_USER_CODE);

            assertThat(shortCaption.optionName())
                    .as("a caller holding an unpadded caption is describing the same option, so a shorter "
                            + "value is accepted exactly as supplied - nothing is padded out to the "
                            + "declared width and nothing is trimmed")
                    .isEqualTo("View")
                    .hasSize(4);

            assertThat(new AdminMenuOption(1, "", "COUSR00C").optionName())
                    .as("an empty caption is distinguishable from absence and is a state a caller may "
                            + "legitimately mean, so it is accepted while null is not")
                    .isEmpty();

            assertThat(MenuResponse.OPTION_NAME_LENGTH)
                    .as("the published width is the copybook's and is unaffected by any instance")
                    .isEqualTo(35);
            for (final MainMenuOption canonical : MenuResponse.MAIN_MENU_OPTIONS) {
                assertThat(canonical.optionName())
                        .as("every canonical entry still carries its full blank padding")
                        .hasSize(MenuResponse.OPTION_NAME_LENGTH);
                assertThat(canonical.programName()).hasSize(MenuResponse.PROGRAM_NAME_LENGTH);
            }
        }

        @Test
        @DisplayName("the one-byte gate field is a char, so an over-wide user type cannot be constructed")
        void gateFieldIsOneByteByConstruction() {
            assertThat(recordComponentTypeNames(MainMenuOption.class))
                    .as("CDEMO-MENU-OPT-USRTYPE is PIC X(01) at app/cpy/COMEN02Y.cpy:L92; modelling it as a "
                            + "char makes a two-character value impossible rather than merely invalid")
                    .containsExactly("int", "java.lang.String", "java.lang.String", "char");

            assertThat(recordComponentTypeNames(AdminMenuOption.class))
                    .containsExactly("int", "java.lang.String", "java.lang.String");
        }

        @Test
        @DisplayName("an option number outside the two-digit range is refused, naming the field and bound")
        void optionNumberOutsideTheTwoDigitRangeIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a three-digit value cannot be held by PIC 9(02)")
                    .isThrownBy(() -> new MainMenuOption(100, EXPECTED_MAIN_CAPTIONS.get(0), "COACTVWC",
                            STANDARD_USER_CODE))
                    .withMessageContaining("optionNumber")
                    .withMessageContaining("PIC 9(02)")
                    .withMessageContaining("100")
                    .withNoCause();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new AdminMenuOption(-1, EXPECTED_ADMIN_CAPTIONS.get(0), "COUSR00C"))
                    .withMessageContaining("optionNumber")
                    .withMessageContaining("-1")
                    .withNoCause();

            assertThat(new MainMenuOption(MenuResponse.OPTION_NUMBER_MAX_VALUE,
                    EXPECTED_MAIN_CAPTIONS.get(0), "COACTVWC", STANDARD_USER_CODE).optionNumber())
                    .as("the boundary value itself is accepted")
                    .isEqualTo(MenuResponse.OPTION_NUMBER_MAX_VALUE);
        }
    }

    @Nested
    @DisplayName("Option parse: backward scan, JUST RIGHT, then space-to-zero")
    class OptionNumberParsing {

        @Test
        @DisplayName("a bare, leading-blank and trailing-blank 5 all normalise to 05")
        void allThreeFormsOfFiveNormaliseIdentically() {
            assertThat(normaliseOptionEntry(" 5"))
                    .as("the backward scan stops on the '5' at position 2, so both bytes move across")
                    .isEqualTo("05");
            assertThat(normaliseOptionEntry("5 "))
                    .as("the scan falls back to position 1, JUST RIGHT re-aligns the '5' into byte 2, and "
                            + "the blank pad becomes a zero; omitting the justification would yield 50")
                    .isEqualTo("05");

            assertThat(toOptionNumber(normaliseOptionEntry(" 5"))).isEqualTo(5);
            assertThat(toOptionNumber(normaliseOptionEntry("5 "))).isEqualTo(5);
        }

        @Test
        @DisplayName("a trailing-blank entry never left-justifies into a tenfold value")
        void trailingBlankNeverBecomesTenfold() {
            for (int digit = 1; digit <= 9; digit++) {
                final String typedThenBlank = digit + " ";
                assertThat(toOptionNumber(normaliseOptionEntry(typedThenBlank)))
                        .as("'%s' must be %d, not %d", typedThenBlank, digit, digit * 10)
                        .isEqualTo(digit);
            }
        }

        @Test
        @DisplayName("a two-digit entry passes through unchanged")
        void twoDigitEntryPassesThrough() {
            assertThat(normaliseOptionEntry("10")).isEqualTo("10");
            assertThat(toOptionNumber(normaliseOptionEntry("10"))).isEqualTo(10);
            assertThat(normaliseOptionEntry("99")).isEqualTo("99");
            assertThat(toOptionNumber(normaliseOptionEntry("99"))).isEqualTo(99);
        }

        @Test
        @DisplayName("an explicit 00 stays 00 and converts to zero")
        void explicitDoubleZeroStaysZero() {
            assertThat(normaliseOptionEntry("00")).isEqualTo("00");
            assertThat(isNumericOptionEntry(normaliseOptionEntry("00"))).isTrue();
            assertThat(toOptionNumber(normaliseOptionEntry("00"))).isZero();
        }

        @Test
        @DisplayName("an all-blank entry becomes 00 through the substitution, and is numeric")
        void allBlankEntryBecomesDoubleZero() {
            assertThat(normaliseOptionEntry("  "))
                    .as("the scan bottoms out at position 1, one blank moves right-justified, and both "
                            + "blanks then become zeros")
                    .isEqualTo("00");
            assertThat(isNumericOptionEntry(normaliseOptionEntry("  ")))
                    .as("a blank entry reaches the bounds check as a NUMERIC zero, so it is rejected by the "
                            + "WS-OPTION = ZEROS limb and not by the class test")
                    .isTrue();
            assertThat(toOptionNumber(normaliseOptionEntry("  "))).isZero();
        }

        @Test
        @DisplayName("a non-digit entry survives the substitution and fails the class test")
        void nonDigitEntryFailsTheClassTest() {
            assertThat(normaliseOptionEntry("1x"))
                    .as("the scan stops on the 'x', both bytes move, and INSPECT replaces only blanks")
                    .isEqualTo("1x");
            assertThat(isNumericOptionEntry(normaliseOptionEntry("1x"))).isFalse();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> toOptionNumber(normaliseOptionEntry("1x")))
                    .withMessageContaining("1x")
                    .withNoCause();
        }

        @Test
        @DisplayName("the echo re-renders the accepted selection as two zero-padded digits")
        void echoReRendersTwoZeroPaddedDigits() {
            assertThat(renderOptionEcho(toOptionNumber(normaliseOptionEntry("5 ")))).isEqualTo("05");
            assertThat(renderOptionEcho(toOptionNumber(normaliseOptionEntry(" 5")))).isEqualTo("05");
            assertThat(renderOptionEcho(toOptionNumber(normaliseOptionEntry("10")))).isEqualTo("10");
            assertThat(renderOptionEcho(toOptionNumber(normaliseOptionEntry("  ")))).isEqualTo("00");

            for (final MainMenuOption option : MenuResponse.MAIN_MENU_OPTIONS) {
                final String typed = renderOptionEcho(option.optionNumber());
                assertThat(renderOptionEcho(toOptionNumber(normaliseOptionEntry(typed))))
                        .as("every populated option number round-trips through the parse and the echo")
                        .isEqualTo(typed);
            }
        }

        @Test
        @DisplayName("the parse consults no locale, so digit handling cannot vary by host")
        void parseConsultsNoLocale() {
            assertThat(renderOptionEcho(7))
                    .as("formatting is pinned to Locale.ROOT, so the digit is always ASCII '7'")
                    .isEqualTo("07")
                    .containsOnlyDigits();

            final String arabicIndicFive = "\u0665";
            assertThat(isNumericOptionEntry(normaliseOptionEntry(arabicIndicFive + " ")))
                    .as("a Unicode digit from another script is not an ASCII digit, so the COBOL class test "
                            + "rejects it; a locale-aware predicate would wrongly accept it")
                    .isFalse();
        }

        @Test
        @DisplayName("a null entry is refused outright, because absence is its own state")
        void nullEntryIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> normaliseOptionEntry(null))
                    .withMessageContaining("screenField")
                    .withNoCause();
        }

        @Test
        @DisplayName("an entry of the wrong width is refused, naming the declared width and the actual one")
        void wrongWidthEntryIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("one character under the declared X(2) width")
                    .isThrownBy(() -> normaliseOptionEntry("5"))
                    .withMessageContaining("OPTIONI")
                    .withMessageContaining("1");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("one character over it")
                    .isThrownBy(() -> normaliseOptionEntry("123"))
                    .withMessageContaining("3");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("and empty")
                    .isThrownBy(() -> normaliseOptionEntry(""))
                    .withMessageContaining("0");
        }
    }

    @Nested
    @DisplayName("Rejection literals and the three guards")
    class RejectionLiterals {

        @Test
        @DisplayName("the bounds-check message is byte-exact, with three dots and no trailing space")
        void boundsCheckMessageIsByteExact() {
            assertThat(INVALID_OPTION_MESSAGE)
                    .as("app/cbl/COMEN01C.cbl:L131 and app/cbl/COADM01C.cbl:L131, the same literal in both")
                    .isEqualTo("Please enter a valid option number...")
                    .hasSize(37)
                    .endsWith("...")
                    .doesNotEndWith(". ");

            assertThat(INVALID_OPTION_MESSAGE.charAt(INVALID_OPTION_MESSAGE.length() - 1))
                    .as("the final character is a dot, not a blank - the opposite of the admin-gate message")
                    .isEqualTo('.');
            assertThat(INVALID_OPTION_MESSAGE).isEqualTo(INVALID_OPTION_MESSAGE.trim());
        }

        @Test
        @DisplayName("the admin-gate message is byte-exact and KEEPS its trailing space after the three dots")
        void adminGateMessageKeepsItsTrailingSpace() {
            assertThat(ADMIN_ONLY_MESSAGE)
                    .as("app/cbl/COMEN01C.cbl:L140, verbatim")
                    .isEqualTo("No access - Admin Only option... ")
                    .hasSize(33);

            assertThat(ADMIN_ONLY_MESSAGE.charAt(ADMIN_ONLY_MESSAGE.length() - 1))
                    .as("the trailing blank is inside the COBOL literal. It is a source sic, and dropping it "
                            + "changes the byte comparison the parity gates perform")
                    .isEqualTo(' ');
            assertThat(ADMIN_ONLY_MESSAGE)
                    .as("trimming shortens it by exactly the one byte that must survive")
                    .isNotEqualTo(ADMIN_ONLY_MESSAGE.trim());
            assertThat(ADMIN_ONLY_MESSAGE.trim()).hasSize(ADMIN_ONLY_MESSAGE.length() - 1);
            assertThat(ADMIN_ONLY_MESSAGE.substring(0, ADMIN_ONLY_MESSAGE.length() - 1)).endsWith("...");
        }

        @Test
        @DisplayName("the two messages are distinct, and neither is a prefix or paraphrase of the other")
        void theTwoMessagesAreDistinct() {
            assertThat(ADMIN_ONLY_MESSAGE).isNotEqualTo(INVALID_OPTION_MESSAGE);
            assertThat(ADMIN_ONLY_MESSAGE).doesNotContain(INVALID_OPTION_MESSAGE);
            assertThat(INVALID_OPTION_MESSAGE).doesNotContain(ADMIN_ONLY_MESSAGE);
        }

        @Test
        @DisplayName("all three bounds-check limbs reject, and all three share the one message")
        void allThreeBoundsCheckLimbsRejectWithOneMessage() {
            assertThat(isRejectedSelection(normaliseOptionEntry("1x"), MenuType.MAIN))
                    .as("limb 1 - WS-OPTION IS NOT NUMERIC")
                    .isTrue();
            assertThat(isRejectedSelection(normaliseOptionEntry("11"), MenuType.MAIN))
                    .as("limb 2 - WS-OPTION > CDEMO-MENU-OPT-COUNT")
                    .isTrue();
            assertThat(isRejectedSelection(normaliseOptionEntry("00"), MenuType.MAIN))
                    .as("limb 3 - WS-OPTION = ZEROS")
                    .isTrue();

            assertThat(isRejectedSelection(normaliseOptionEntry("  "), MenuType.MAIN))
                    .as("an all-blank entry reaches limb 3 as a numeric zero")
                    .isTrue();

            assertThat(INVALID_OPTION_MESSAGE)
                    .as("app/cbl/COMEN01C.cbl:L130-L132 moves one literal for all three limbs; there is no "
                            + "per-limb message to distinguish them")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("option zero is refused even though PIC 9(02) can hold it")
        void optionZeroIsRefusedBySelectionThoughRepresentable() {
            assertThat(isRejectedSelection("00", MenuType.MAIN)).isTrue();
            assertThat(isRejectedSelection("00", MenuType.ADMIN)).isTrue();

            assertThat(new MainMenuOption(0, EXPECTED_MAIN_CAPTIONS.get(0), "COACTVWC",
                    STANDARD_USER_CODE).optionNumber())
                    .as("zero is a representable field value at app/cpy/COMEN02Y.cpy:L89 but never a valid "
                            + "selection at app/cbl/COMEN01C.cbl:L129 - two separate rules, and only the "
                            + "second one rejects it")
                    .isZero();
        }

        @Test
        @DisplayName("every populated option number is an accepted selection on its own menu")
        void everyPopulatedOptionIsAnAcceptedSelection() {
            for (final MainMenuOption option : MenuResponse.MAIN_MENU_OPTIONS) {
                final String typed = renderOptionEcho(option.optionNumber());
                assertThat(isRejectedSelection(normaliseOptionEntry(typed), MenuType.MAIN))
                        .as("main option %s must be selectable", typed)
                        .isFalse();
            }
            for (final AdminMenuOption option : MenuResponse.ADMIN_MENU_OPTIONS) {
                final String typed = renderOptionEcho(option.optionNumber());
                assertThat(isRejectedSelection(normaliseOptionEntry(typed), MenuType.ADMIN))
                        .as("admin option %s must be selectable", typed)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("the admin gate denies a standard user only when the option byte is exactly A")
        void adminGateDeniesOnlyOnAnExplicitAdminByte() {
            final MainMenuOption adminOnly = new MainMenuOption(1, EXPECTED_MAIN_CAPTIONS.get(0),
                    "COACTVWC", ADMIN_USER_CODE);
            final MainMenuOption standard = MenuResponse.MAIN_MENU_OPTIONS.get(0);

            assertThat(isDeniedByAdminGate(UserType.USER, adminOnly))
                    .as("both limbs of app/cbl/COMEN01C.cbl:L136-L137 hold")
                    .isTrue();
            assertThat(isDeniedByAdminGate(UserType.ADMIN, adminOnly))
                    .as("CDEMO-USRTYP-USER is false for an administrator, so the gate never fires")
                    .isFalse();
            assertThat(isDeniedByAdminGate(UserType.USER, standard))
                    .as("the option byte is 'U', so the second limb is false")
                    .isFalse();
            assertThat(isDeniedByAdminGate(UserType.ADMIN, standard)).isFalse();
        }

        @Test
        @DisplayName("a gate byte outside the two-code domain denies nothing, because it is not exactly A")
        void unknownGateByteDeniesNothing() {
            for (final char code : new char[] {'X', 'a', ' ', LOW_VALUE, '0'}) {
                final MainMenuOption option =
                        new MainMenuOption(1, EXPECTED_MAIN_CAPTIONS.get(0), "COACTVWC", code);
                assertThat(isDeniedByAdminGate(UserType.USER, option))
                        .as("app/cbl/COMEN01C.cbl:L137 compares against the bare literal 'A', so code point "
                                + "0x%s denies nothing", Integer.toHexString(code))
                        .isFalse();
            }

            final MainMenuOption lowerCaseAdmin =
                    new MainMenuOption(1, EXPECTED_MAIN_CAPTIONS.get(0), "COACTVWC", 'a');
            assertThat(lowerCaseAdmin.userType())
                    .as("the comparison is case sensitive in the source, so a lower-case byte resolves to no "
                            + "constant at all rather than being folded to ADMIN")
                    .isNull();
        }

        @Test
        @DisplayName("no option in either table carries the administrator-only gate byte")
        void noPopulatedOptionIsAdministratorOnly() {
            for (final MainMenuOption option : MenuResponse.MAIN_MENU_OPTIONS) {
                assertThat(option.userTypeCode())
                    .as("all ten entries carry 'U' across app/cpy/COMEN02Y.cpy:L25-L84, so as the table "
                            + "stands the gate excludes nothing - data, not a rule")
                    .isEqualTo(STANDARD_USER_CODE);
                assertThat(option.userType()).isEqualTo(UserType.USER);
                assertThat(isDeniedByAdminGate(UserType.USER, option)).isFalse();
            }
        }

        @Test
        @DisplayName("the admin table has no per-option user-type gate, and none is invented")
        void adminTableHasNoUserTypeGate() {
            assertThat(ReflectionCensus.declaredMethodNames(MenuOption.class))
                    .as("if the shared supertype declared a gate accessor, an admin option would be forced "
                            + "to answer a question app/cpy/COADM02Y.cpy never asks")
                    .doesNotContain("userType", "userTypeCode");

            assertThat(recordComponentNames(AdminMenuOption.class)).hasSize(3);
            assertThat(recordComponentNames(MainMenuOption.class)).hasSize(4);
            assertThat(MAIN_ENTRY_WIDTH - ADMIN_ENTRY_WIDTH)
                    .as("the missing sub-field is exactly one byte wide")
                    .isEqualTo(1);

            assertThat(ADMIN_ONLY_MESSAGE)
                    .as("the message exists only in the main-menu program; there is no admin counterpart to "
                            + "assert, and fabricating one would be an invention")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("the placeholder guard compares the first five characters only")
        void placeholderGuardComparesFiveCharacters() {
            assertThat(PLACEHOLDER_PREFIX_LENGTH)
                    .as("(1:5) at app/cbl/COMEN01C.cbl:L146 and app/cbl/COADM01C.cbl:L138")
                    .isEqualTo(5)
                    .isLessThan(MenuResponse.PROGRAM_NAME_LENGTH);

            assertThat(isPlaceholderProgram("DUMMY   "))
                    .as("blank-padded to the X(08) width - a placeholder")
                    .isTrue();
            assertThat(isPlaceholderProgram("DUMMY123"))
                    .as("suffixed rather than padded - still a placeholder, because bytes 6 to 8 are never "
                            + "compared; an eight-character comparison would wrongly dispatch to it")
                    .isTrue();
            assertThat(isPlaceholderProgram("DUMM"))
                    .as("four characters cannot equal a five-character sentinel")
                    .isFalse();
            assertThat(isPlaceholderProgram("XDUMMY"))
                    .as("the sentinel must be a prefix, not merely present")
                    .isFalse();
            assertThat(isPlaceholderProgram("dummy123"))
                    .as("the COBOL comparison is on bytes, so case matters")
                    .isFalse();
            assertThat(isPlaceholderProgram(null)).isFalse();
            assertThat(isPlaceholderProgram("")).isFalse();
        }

        @Test
        @DisplayName("no populated program name is a placeholder, so every option really transfers control")
        void noPopulatedProgramNameIsAPlaceholder() {
            for (final MainMenuOption option : MenuResponse.MAIN_MENU_OPTIONS) {
                assertThat(isPlaceholderProgram(option.programName()))
                        .as("main option %d targets %s", option.optionNumber(), option.programName())
                        .isFalse();
            }
            for (final AdminMenuOption option : MenuResponse.ADMIN_MENU_OPTIONS) {
                assertThat(isPlaceholderProgram(option.programName())).isFalse();
            }
        }

        @Test
        @DisplayName("the not-yet-implemented notice diverges between the two programs, and is not normalised")
        void comingSoonNoticeDivergesBetweenThePrograms() {
            final MainMenuOption accountView = MenuResponse.MAIN_MENU_OPTIONS.get(0);

            assertThat(delimitedBySpace(accountView.optionName()))
                    .as("app/cbl/COMEN01C.cbl:L161 delimits the caption BY SPACE, not BY SIZE, so only the "
                            + "first blank-delimited word is transferred")
                    .isEqualTo("Account");

            assertThat(renderMainComingSoonNotice(accountView))
                    .as("the truncated caption and the suffix run together, because the suffix begins with "
                            + "no blank - reproduced rather than tidied")
                    .isEqualTo("This option Accountis coming soon ...");

            assertThat(renderAdminComingSoonNotice())
                    .as("app/cbl/COADM01C.cbl:L150-L151 has the caption reference COMMENTED OUT, so the "
                            + "admin notice never names the option - a preserved legacy inconsistency")
                    .isEqualTo("This option is coming soon ...")
                    .doesNotContain("User");

            assertThat(renderAdminComingSoonNotice()).isNotEqualTo(renderMainComingSoonNotice(accountView));
        }

        @Test
        @DisplayName("the three guards form an ordered chain, so no cross-field edit fires unconditionally")
        void guardsFormAnOrderedChain() {
            final MainMenuOption adminOnlyPlaceholder =
                    new MainMenuOption(11, EXPECTED_MAIN_CAPTIONS.get(0), "DUMMY123", ADMIN_USER_CODE);

            // Limb 1 of the chain rejects on the count, and once it has rejected the later guards are never
            // reached - which is the shape of app/cbl/COACTUPC.cbl:L1665-L1669, where the cross-field edit
            // runs only after both single-field edits have already passed. A class-level constraint would
            // instead evaluate every rule at once and report a different message set.
            assertThat(isRejectedSelection(normaliseOptionEntry("11"), MenuType.MAIN)).isTrue();

            // The later guards are still individually meaningful; they simply do not run for a selection the
            // bounds check already refused.
            assertThat(isDeniedByAdminGate(UserType.USER, adminOnlyPlaceholder)).isTrue();
            assertThat(isPlaceholderProgram(adminOnlyPlaceholder.programName())).isTrue();

            assertThat(ReflectionCensus.declaredAnnotationTypeNames(MenuResponse.class))
                    .as("no class-level constraint annotation exists to fire unconditionally")
                    .isEmpty();
            assertThat(ReflectionCensus.declaredAnnotationTypeNames(MainMenuOption.class)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Ordering and immutability")
    class OrderingAndImmutability {

        @Test
        @DisplayName("the canonical tables are in copybook declaration order with contiguous numbers")
        void canonicalTablesAreInCopybookOrder() {
            final List<Integer> mainNumbers = new ArrayList<>();
            for (final MainMenuOption option : MenuResponse.mainMenu().getOptions()) {
                mainNumbers.add(option.optionNumber());
            }
            assertThat(mainNumbers)
                    .as("CDEMO-MENU-OPT-NUM runs 1 through 10 across app/cpy/COMEN02Y.cpy:L25-L84")
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

            final List<Integer> adminNumbers = new ArrayList<>();
            for (final AdminMenuOption option : MenuResponse.adminMenu().getOptions()) {
                adminNumbers.add(option.optionNumber());
            }
            assertThat(adminNumbers)
                    .as("CDEMO-ADMIN-OPT-NUM runs 1 through 4 across app/cpy/COADM02Y.cpy:L24-L42")
                    .containsExactly(1, 2, 3, 4);
        }

        @Test
        @DisplayName("the option number equals the one-based position, so a subscript selects the right entry")
        void optionNumberMatchesPosition() {
            final List<MainMenuOption> main = MenuResponse.mainMenu().getOptions();
            for (int position = 0; position < main.size(); position++) {
                assertThat(main.get(position).optionNumber())
                        .as("a WS-OPTION of %d must address the entry at index %d", position + 1, position)
                        .isEqualTo(position + 1);
                assertThat(main.get(position).optionName()).isEqualTo(EXPECTED_MAIN_CAPTIONS.get(position));
                assertThat(main.get(position).programName())
                        .isEqualTo(EXPECTED_MAIN_PROGRAM_NAMES.get(position));
            }

            final List<AdminMenuOption> admin = MenuResponse.adminMenu().getOptions();
            for (int position = 0; position < admin.size(); position++) {
                assertThat(admin.get(position).optionNumber()).isEqualTo(position + 1);
                assertThat(admin.get(position).optionName()).isEqualTo(EXPECTED_ADMIN_CAPTIONS.get(position));
                assertThat(admin.get(position).programName())
                        .isEqualTo(EXPECTED_ADMIN_PROGRAM_NAMES.get(position));
            }
        }

        @Test
        @DisplayName("iteration order is stable across repeated traversals and repeated payloads")
        void iterationOrderIsStable() {
            final MenuResponse<MainMenuOption> response = MenuResponse.mainMenu();

            final List<String> firstPass = new ArrayList<>();
            for (final MainMenuOption option : response.getOptions()) {
                firstPass.add(option.programName());
            }
            final List<String> secondPass = new ArrayList<>();
            for (final MainMenuOption option : response.getOptions()) {
                secondPass.add(option.programName());
            }
            assertThat(secondPass).containsExactlyElementsOf(firstPass);

            final List<String> freshPayload = new ArrayList<>();
            for (final MainMenuOption option : MenuResponse.mainMenu().getOptions()) {
                freshPayload.add(option.programName());
            }
            assertThat(freshPayload)
                    .as("a hash-ordered collection would give no such guarantee across instances")
                    .containsExactlyElementsOf(firstPass);
        }

        @Test
        @DisplayName("the surface carries no hash-ordered collection type")
        void surfaceCarriesNoHashOrderedCollection() {
            final List<String> unorderedTypes = List.of(
                    "java.util.HashMap", "java.util.HashSet", "java.util.Hashtable", "java.util.Set");

            for (final Class<?> type : List.of(MenuResponse.class, MenuType.class, MenuOption.class,
                    MainMenuOption.class, AdminMenuOption.class)) {
                for (final String typeName : ReflectionCensus.declaredSurfaceTypeNames(type)) {
                    for (final String unordered : unorderedTypes) {
                        assertThat(typeName)
                                .as("position is meaning here: the option number is both the selection key "
                                        + "and the display order, so %s must not appear on %s",
                                        unordered, type.getSimpleName())
                                .doesNotContain(unordered);
                    }
                }
            }
        }

        @Test
        @DisplayName("the published option list refuses every mutating operation")
        void publishedOptionListIsUnmodifiable() {
            final MenuResponse<MainMenuOption> response = MenuResponse.mainMenu();
            final List<MainMenuOption> options = response.getOptions();
            final MainMenuOption intruder =
                    new MainMenuOption(1, EXPECTED_MAIN_CAPTIONS.get(0), "COACTVWC", STANDARD_USER_CODE);

            assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> options.add(intruder));
            assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> options.remove(0));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> options.set(0, intruder));
            assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(options::clear);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> options.addAll(MenuResponse.MAIN_MENU_OPTIONS));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> options.removeIf(candidate -> true));

            final Iterator<MainMenuOption> iterator = options.iterator();
            assertThat(iterator.hasNext()).isTrue();
            iterator.next();
            assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(iterator::remove);

            assertThat(response.getOptionCount())
                    .as("no mutation attempt may have taken effect")
                    .isEqualTo(MAIN_POPULATED_COUNT);
        }

        @Test
        @DisplayName("the two canonical tables are themselves unmodifiable constants")
        void canonicalTablesAreUnmodifiable() {
            final MainMenuOption mainIntruder =
                    new MainMenuOption(1, EXPECTED_MAIN_CAPTIONS.get(0), "COACTVWC", STANDARD_USER_CODE);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> MenuResponse.MAIN_MENU_OPTIONS.add(mainIntruder));

            final AdminMenuOption adminIntruder =
                    new AdminMenuOption(1, EXPECTED_ADMIN_CAPTIONS.get(0), "COUSR00C");
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> MenuResponse.ADMIN_MENU_OPTIONS.add(adminIntruder));

            assertThat(MenuResponse.MAIN_MENU_OPTIONS).hasSize(MAIN_POPULATED_COUNT);
            assertThat(MenuResponse.ADMIN_MENU_OPTIONS).hasSize(ADMIN_POPULATED_COUNT);
        }

        @Test
        @DisplayName("the payload copies defensively, so mutating the caller's list cannot reach inside")
        void payloadCopiesDefensively() {
            final List<MainMenuOption> caller = new ArrayList<>();
            caller.add(MenuResponse.MAIN_MENU_OPTIONS.get(0));
            caller.add(MenuResponse.MAIN_MENU_OPTIONS.get(1));

            final MenuResponse<MainMenuOption> response = MenuResponse.ofMainMenu(caller);
            assertThat(response.getOptionCount()).isEqualTo(2);

            caller.clear();
            caller.add(MenuResponse.MAIN_MENU_OPTIONS.get(9));

            assertThat(response.getOptionCount())
                    .as("the payload holds its own copy, so a later mutation of the caller's list is invisible")
                    .isEqualTo(2);
            assertThat(response.getOptions().get(0).programName())
                    .isEqualTo(EXPECTED_MAIN_PROGRAM_NAMES.get(0));
            assertThat(response.getOptions().get(1).programName())
                    .isEqualTo(EXPECTED_MAIN_PROGRAM_NAMES.get(1));
        }

        @Test
        @DisplayName("a caller-supplied order is preserved exactly, never re-sorted")
        void callerSuppliedOrderIsPreserved() {
            final List<AdminMenuOption> reversed = new ArrayList<>();
            for (int index = MenuResponse.ADMIN_MENU_OPTIONS.size() - 1; index >= 0; index--) {
                reversed.add(MenuResponse.ADMIN_MENU_OPTIONS.get(index));
            }

            final List<Integer> observed = new ArrayList<>();
            for (final AdminMenuOption option : MenuResponse.ofAdminMenu(reversed).getOptions()) {
                observed.add(option.optionNumber());
            }
            assertThat(observed)
                    .as("the payload preserves what it is given; re-sorting would silently disagree with the "
                            + "caller's intent")
                    .containsExactly(4, 3, 2, 1);
        }

        @Test
        @DisplayName("equality is order-sensitive and consistent with the hash code")
        void equalityIsOrderSensitive() {
            final MenuResponse<AdminMenuOption> inOrder = MenuResponse.adminMenu();
            final MenuResponse<AdminMenuOption> sameAgain =
                    MenuResponse.ofAdminMenu(new ArrayList<>(MenuResponse.ADMIN_MENU_OPTIONS));

            assertThat(sameAgain).isEqualTo(inOrder).hasSameHashCodeAs(inOrder);

            final List<AdminMenuOption> swapped = new ArrayList<>(MenuResponse.ADMIN_MENU_OPTIONS);
            swapped.add(0, swapped.remove(1));
            assertThat(MenuResponse.ofAdminMenu(swapped))
                    .as("two payloads carrying the same options in a different order are different payloads")
                    .isNotEqualTo(inOrder);

            assertThat(inOrder).isEqualTo(inOrder).isNotEqualTo(null).isNotEqualTo("not a payload");
        }
    }

    @Nested
    @DisplayName("Absent, blank and LOW-VALUES are three distinct states")
    class TriStateAndBoundaries {

        @Test
        @DisplayName("a null option list is refused while an empty one is accepted and meaningful")
        void nullListIsRefusedAndEmptyListIsAccepted() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("absence is not the same as offering nothing, so it is rejected rather than coerced")
                    .isThrownBy(() -> MenuResponse.ofMainMenu(null))
                    .withMessageContaining("options")
                    .withNoCause();

            final MenuResponse<MainMenuOption> empty = MenuResponse.ofMainMenu(new ArrayList<>());
            assertThat(empty.getOptionCount()).isZero();
            assertThat(empty.getOptions()).isEmpty();
            assertThat(empty.getMenuType())
                    .as("an empty main menu is still a main menu - a service that gated every option away "
                            + "legitimately produces one")
                    .isEqualTo(MenuType.MAIN);

            assertThat(MenuResponse.ofAdminMenu(new ArrayList<>()).getOptionCount()).isZero();
        }

        @Test
        @DisplayName("a null element inside the list is refused, and the message names its index")
        void nullElementIsRefusedByIndex() {
            final List<MainMenuOption> withHole = new ArrayList<>();
            withHole.add(MenuResponse.MAIN_MENU_OPTIONS.get(0));
            withHole.add(null);
            withHole.add(MenuResponse.MAIN_MENU_OPTIONS.get(2));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> MenuResponse.ofMainMenu(withHole))
                    .withMessageContaining("options")
                    .withMessageContaining("1")
                    .withNoCause();
        }

        @Test
        @DisplayName("a null caption or program name is refused while an empty one is accepted")
        void nullTextIsRefusedAndEmptyTextIsAccepted() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new MainMenuOption(1, null, "COACTVWC", STANDARD_USER_CODE))
                    .withMessageContaining("optionName")
                    .withNoCause();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new MainMenuOption(1, EXPECTED_MAIN_CAPTIONS.get(0), null,
                            STANDARD_USER_CODE))
                    .withMessageContaining("programName")
                    .withNoCause();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new AdminMenuOption(1, null, "COUSR00C"))
                    .withMessageContaining("optionName");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new AdminMenuOption(1, EXPECTED_ADMIN_CAPTIONS.get(0), null))
                    .withMessageContaining("programName");

            final MainMenuOption emptyText = new MainMenuOption(1, "", "", STANDARD_USER_CODE);
            assertThat(emptyText.optionName()).isEmpty();
            assertThat(emptyText.programName()).isEmpty();
        }

        @Test
        @DisplayName("empty, blank and LOW-VALUES captions are three different values, none folded together")
        void emptyBlankAndLowValuesCaptionsStayDistinct() {
            final String blank = " ".repeat(MenuResponse.OPTION_NAME_LENGTH);
            final String lowValues = String.valueOf(LOW_VALUE).repeat(MenuResponse.OPTION_NAME_LENGTH);

            final MainMenuOption emptyCaption = new MainMenuOption(1, "", "COACTVWC", STANDARD_USER_CODE);
            final MainMenuOption blankCaption = new MainMenuOption(1, blank, "COACTVWC", STANDARD_USER_CODE);
            final MainMenuOption lowValueCaption =
                    new MainMenuOption(1, lowValues, "COACTVWC", STANDARD_USER_CODE);

            assertThat(emptyCaption.optionName()).isNotEqualTo(blankCaption.optionName());
            assertThat(blankCaption.optionName()).isNotEqualTo(lowValueCaption.optionName());
            assertThat(emptyCaption.optionName()).isNotEqualTo(lowValueCaption.optionName());

            assertThat(emptyCaption).isNotEqualTo(blankCaption);
            assertThat(blankCaption).isNotEqualTo(lowValueCaption);

            assertThat(blankCaption.optionName())
                    .as("a blank caption is still 35 bytes wide, exactly as the field is declared")
                    .hasSize(MenuResponse.OPTION_NAME_LENGTH);
            assertThat(lowValueCaption.optionName()).hasSize(MenuResponse.OPTION_NAME_LENGTH);
        }

        @Test
        @DisplayName("a blank and a LOW-VALUES gate byte both resolve to no user type, yet stay distinguishable")
        void blankAndLowValuesGateBytesStayDistinguishable() {
            final MainMenuOption blankGate =
                    new MainMenuOption(1, EXPECTED_MAIN_CAPTIONS.get(0), "COACTVWC", ' ');
            final MainMenuOption lowValueGate =
                    new MainMenuOption(1, EXPECTED_MAIN_CAPTIONS.get(0), "COACTVWC", LOW_VALUE);

            assertThat(blankGate.userType())
                    .as("a blank byte is outside the domain of app/cpy/COCOM01Y.cpy:L27-L28, so it resolves "
                            + "to null and is never defaulted to USER or ADMIN")
                    .isNull();
            assertThat(lowValueGate.userType()).isNull();

            assertThat(blankGate.userTypeCode())
                    .as("the raw byte still round-trips, which is what keeps the two states apart")
                    .isEqualTo(' ');
            assertThat(lowValueGate.userTypeCode()).isEqualTo(LOW_VALUE);
            assertThat(blankGate.userTypeCode()).isNotEqualTo(lowValueGate.userTypeCode());
            assertThat(blankGate).isNotEqualTo(lowValueGate);

            assertThat(UserType.fromCode(' ')).isEmpty();
            assertThat(UserType.fromCode(LOW_VALUE)).isEmpty();
            assertThat(UserType.fromCode(STANDARD_USER_CODE)).contains(UserType.USER);
            assertThat(UserType.fromCode(ADMIN_USER_CODE)).contains(UserType.ADMIN);
        }

        @Test
        @DisplayName("an unrecognised gate byte is reported by its code point rather than echoed")
        void unrecognisedGateByteCanBeReportedByName() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("where a recognised code is a genuine invariant, the strict lookup reports the "
                            + "code point of what it rejected rather than defaulting silently. The "
                            + "character itself is withheld: this gate byte arrives from a request body "
                            + "and from a persisted column, so a carriage return among those bytes copied "
                            + "verbatim into a message that reaches a log would let the caller forge a "
                            + "log line. The hexadecimal rendering is injective, so nothing diagnostic "
                            + "is lost")
                    .isThrownBy(() -> UserType.requireFromCode('X'))
                    .withMessageContaining("0x58")
                    .withMessageContaining("CDEMO-USER-TYPE")
                    .withNoCause();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a blank byte is invisible in a log, which is why the code point is what gets "
                            + "reported in every case rather than only for the printable ones")
                    .isThrownBy(() -> UserType.requireFromCode(' '))
                    .withMessageContaining("0x20");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserType.requireFromCode(LOW_VALUE))
                    .withMessageContaining("0x0");
        }

        @Test
        @DisplayName("a blank screen entry and an untouched LOW-VALUES entry parse differently")
        void blankAndLowValuesScreenEntriesParseDifferently() {
            final String blankEntry = "  ";
            final String untouchedEntry = String.valueOf(LOW_VALUE).repeat(OPTION_NUMBER_WIDTH);
            final String typedThenUntouched = "5" + LOW_VALUE;

            assertThat(normaliseOptionEntry(blankEntry))
                    .as("blanks are replaced by zeros, so a blank entry arrives as a numeric zero")
                    .isEqualTo("00");
            assertThat(isNumericOptionEntry(normaliseOptionEntry(blankEntry))).isTrue();

            assertThat(normaliseOptionEntry(untouchedEntry))
                    .as("INSPECT replaces blanks only, so LOW-VALUES bytes survive untouched")
                    .isEqualTo(untouchedEntry);
            assertThat(isNumericOptionEntry(normaliseOptionEntry(untouchedEntry)))
                    .as("an untouched entry therefore fails the class test rather than becoming a zero - a "
                            + "different rejection limb from the blank case, with the same message")
                    .isFalse();

            assertThat(normaliseOptionEntry(typedThenUntouched))
                    .as("the backward scan halts on the LOW-VALUES byte because it is NOT = SPACES, so no "
                            + "right-justification happens and the digit is not rescued")
                    .isEqualTo(typedThenUntouched);
            assertThat(normaliseOptionEntry("5 "))
                    .as("the same digit followed by a blank IS rescued - the exact behavioural difference "
                            + "between the second and third states")
                    .isEqualTo("05");

            assertThat(isRejectedSelection(normaliseOptionEntry(blankEntry), MenuType.MAIN)).isTrue();
            assertThat(isRejectedSelection(normaliseOptionEntry(untouchedEntry), MenuType.MAIN)).isTrue();
        }

        @Test
        @DisplayName("the marker template models exactly three states, matched by three distinct encodings")
        void markerTemplateModelsExactlyThreeStates() {
            final List<Character> triStateEncodings = List.of(LOW_VALUE, '0', 'B');
            assertThat(triStateEncodings)
                    .as("app/cbl/COACTUPC.cbl:L300-L311 encodes FLG-*-ISVALID as LOW-VALUES, FLG-*-NOT-OK as "
                            + "'0' and FLG-*-BLANK as 'B' in one PIC X(01) byte - three states, three "
                            + "encodings, corroborating the app/cpy/CSSETATY.cpy OK / NOT-OK / BLANK template")
                    .hasSize(3)
                    .doesNotHaveDuplicates();

            assertThat(triStateEncodings.get(0)).isNotEqualTo(' ');
            assertThat(triStateEncodings).doesNotContain(' ');
        }
    }

    @Nested
    @DisplayName("Security, least privilege and determinism")
    class SecurityAndDeterminism {

        @Test
        @DisplayName("no member of any menu type resembles a credential or personal datum")
        void noMemberResemblesACredential() {
            assertNoMemberNameContains(FORBIDDEN_SENSITIVE_FRAGMENTS,
                    MenuResponse.class, MenuType.class, MenuOption.class, MainMenuOption.class,
                    AdminMenuOption.class);
        }

        @Test
        @DisplayName("no canonical value carries a character the copybook alphabet excludes")
        void noCanonicalValueCarriesACharacterOutsideTheCopybookAlphabet() {
            final List<String> everyCanonicalValue = new ArrayList<>();
            everyCanonicalValue.addAll(EXPECTED_MAIN_CAPTIONS);
            everyCanonicalValue.addAll(EXPECTED_MAIN_PROGRAM_NAMES);
            everyCanonicalValue.addAll(EXPECTED_ADMIN_CAPTIONS);
            everyCanonicalValue.addAll(EXPECTED_ADMIN_PROGRAM_NAMES);
            for (final MainMenuOption option : MenuResponse.MAIN_MENU_OPTIONS) {
                everyCanonicalValue.add(option.optionName());
                everyCanonicalValue.add(option.programName());
            }
            for (final AdminMenuOption option : MenuResponse.ADMIN_MENU_OPTIONS) {
                everyCanonicalValue.add(option.optionName());
                everyCanonicalValue.add(option.programName());
            }

            assertThat(everyCanonicalValue).hasSize(2 * (MAIN_POPULATED_COUNT + ADMIN_POPULATED_COUNT) * 2);

            for (final String value : everyCanonicalValue) {
                for (final Character forbidden : FORBIDDEN_ENDPOINT_CHARACTERS) {
                    assertThat(value)
                            .as("'%s' occurs in no COMEN02Y or COADM02Y literal, so its presence would mean "
                                    + "the caption or program name is no longer a faithful transcription. This "
                                    + "is a transcription assertion, not a security one - the capability claim "
                                    + "is carried by noRoutingOrInvocationApiIsReachable()", forbidden)
                            .doesNotContain(String.valueOf(forbidden));
                }
            }
        }

        @Test
        @DisplayName("no routing, network, process or dynamic invocation API is reachable from any type")
        void noRoutingOrInvocationApiIsReachable() {
            // This is the assertion that carries the security claim, and it is made the only way a
            // capability claim CAN be made: by proving the API that provides the capability is not
            // referenced. The check reads the compiled class file rather than reflecting over signatures,
            // so a reference buried in a method body is caught too - which is exactly where a value would
            // have to be dereferenced for a routing risk to be real.
            for (final Class<?> type : List.of(MenuResponse.class, MenuType.class, MenuOption.class,
                    MainMenuOption.class, AdminMenuOption.class)) {
                final String classFile = compiledFormOf(type);
                for (final String forbidden : FORBIDDEN_CAPABILITY_REFERENCES) {
                    assertThat(classFile)
                            .as("%s references '%s', so a routing, network, process or dynamic invocation "
                                    + "capability is reachable from a menu type. The canonical values are "
                                    + "opaque legacy program names and must never be dereferenced: "
                                    + "app/cbl/COMEN01C.cbl:L153 dispatches EXEC CICS XCTL "
                                    + "PROGRAM(<table value>), and the target replaces that with static "
                                    + "URL routing decided by the controller, not by this data",
                                    type.getSimpleName(), forbidden)
                            .doesNotContain(forbidden);
                }
            }

            // The signature surface is asserted as well, so a capability cannot arrive as a parameter or
            // a return value either.
            for (final Class<?> type : List.of(MenuResponse.class, MenuType.class, MenuOption.class,
                    MainMenuOption.class, AdminMenuOption.class)) {
                for (final Method method : type.getDeclaredMethods()) {
                    final List<String> signatureTypes = new ArrayList<>();
                    signatureTypes.add(method.getReturnType().getName());
                    for (final Class<?> parameter : method.getParameterTypes()) {
                        signatureTypes.add(parameter.getName());
                    }
                    assertThat(signatureTypes)
                            .as("%s.%s must neither accept nor return a networking or invocation type",
                                    type.getSimpleName(), method.getName())
                            .allSatisfy(name -> assertThat(name)
                                    .doesNotStartWith("java.net.")
                                    .doesNotStartWith("javax.naming.")
                                    .doesNotStartWith("java.rmi.")
                                    .doesNotStartWith("java.lang.reflect."));
                }
            }
        }

        @Test
        @DisplayName("the diagnostic rendering discloses no option payload and is locale-independent")
        void renderingDisclosesNoOptionPayload() {
            final MenuResponse<MainMenuOption> main = MenuResponse.mainMenu();

            assertThat(main.toString())
                    .isEqualTo(String.format(Locale.ROOT, "MenuResponse[menuType=%s, optionCount=%d]",
                            MenuType.MAIN, MAIN_POPULATED_COUNT));
            for (final String caption : EXPECTED_MAIN_CAPTIONS) {
                assertThat(main.toString())
                        .as("the rendering summarises rather than dumping the table")
                        .doesNotContain(caption);
            }

            assertThat(MenuResponse.adminMenu().toString())
                    .isEqualTo(String.format(Locale.ROOT, "MenuResponse[menuType=%s, optionCount=%d]",
                            MenuType.ADMIN, ADMIN_POPULATED_COUNT));

            assertThat(main.toString())
                    .as("repeated rendering of the same payload is byte-identical")
                    .isEqualTo(main.toString());
        }

        @Test
        @DisplayName("an option rendering is safe to log, because no component can identify anyone")
        void optionRenderingIsSafeToLog() {
            for (final MainMenuOption option : MenuResponse.MAIN_MENU_OPTIONS) {
                assertThat(option.toString())
                        .as("a number, a caption, a program name and one eligibility byte")
                        .contains(option.programName())
                        .contains(String.valueOf(option.optionNumber()));
                for (final String fragment : FORBIDDEN_SENSITIVE_FRAGMENTS) {
                    assertThat(foldForFragmentScreen(option.toString())).doesNotContain(fragment);
                }
            }
        }

        @Test
        @DisplayName("the administrator-only state is representable, so a service can refuse it")
        void administratorOnlyStateIsRepresentable() {
            final MainMenuOption restricted =
                    new MainMenuOption(1, EXPECTED_MAIN_CAPTIONS.get(0), "COACTVWC", ADMIN_USER_CODE);

            assertThat(restricted.userTypeCode()).isEqualTo(ADMIN_USER_CODE);
            assertThat(restricted.userType())
                    .as("the payload must be able to say 'this option is administrator-only' so the menu "
                            + "service can withhold it - least privilege depends on the state being "
                            + "expressible, not on the payload enforcing it")
                    .isEqualTo(UserType.ADMIN);

            assertThat(isDeniedByAdminGate(UserType.USER, restricted))
                    .as("the decision itself lives in the service, reproduced here only to pin the contract")
                    .isTrue();

            final MenuResponse<MainMenuOption> gated =
                    MenuResponse.ofMainMenu(new ArrayList<>(List.of(restricted)));
            assertThat(gated.getOptionCount())
                    .as("the payload carries whatever the service decided to expose, without second-guessing")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the program name stays opaque legacy metadata and is never a dispatch key")
        void programNameStaysOpaqueMetadata() {
            assertNoMemberNameContains(FORBIDDEN_DISPATCH_FRAGMENTS,
                    MenuResponse.class, MenuType.class, MenuOption.class, MainMenuOption.class,
                    AdminMenuOption.class);

            for (final Method method : MenuOption.class.getDeclaredMethods()) {
                if ("programName".equals(method.getName())) {
                    assertThat(method.getReturnType())
                            .as("a plain String cannot be invoked, so replacing EXEC CICS XCTL "
                                    + "PROGRAM(<table value>) at app/cbl/COMEN01C.cbl:L153 with static URL "
                                    + "routing leaves nothing here for a client to dispatch on")
                            .isEqualTo(String.class);
                    assertThat(method.getParameterCount())
                            .as("it is an accessor, not a lookup keyed by anything")
                            .isZero();
                }
            }

            for (final Class<?> type : List.of(MenuResponse.class, MainMenuOption.class,
                    AdminMenuOption.class)) {
                for (final String typeName : ReflectionCensus.declaredSurfaceTypeNames(type)) {
                    assertThat(typeName)
                            .as("no functional or reflective type may reach the surface of %s and turn a "
                                    + "table value back into an invocation", type.getSimpleName())
                            .doesNotContain("java.lang.reflect")
                            .doesNotContain("java.util.function")
                            .doesNotContain("java.lang.invoke")
                            .doesNotContain("java.lang.Class");
                }
            }
        }

        @Test
        @DisplayName("the payload carries no temporal member, and is identical at two different instants")
        void payloadIsTimeInvariant() {
            final Clock atFixture = FixedClockProvider.canonicalClock();
            final Instant aDayLater = FixedClockProvider.CANONICAL_INSTANT.plusSeconds(86_400L);
            final Clock laterClock = FixedClockProvider.fixedClock(aDayLater, FixedClockProvider.CANONICAL_ZONE);

            assertThat(FixedClockProvider.onlineTimestamp(atFixture))
                    .as("the two injected clocks genuinely differ, so the invariance below is an observation "
                            + "rather than a tautology; the ambient clock is never read")
                    .isEqualTo(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP)
                    .isNotEqualTo(FixedClockProvider.onlineTimestamp(laterClock));

            final MenuResponse<MainMenuOption> beforeTheChange = MenuResponse.mainMenu();
            final MenuResponse<MainMenuOption> afterTheChange = MenuResponse.mainMenu();
            assertThat(afterTheChange).isEqualTo(beforeTheChange).hasSameHashCodeAs(beforeTheChange);
            assertThat(afterTheChange.toString()).isEqualTo(beforeTheChange.toString());

            final List<String> temporalTypes = List.of(
                    "java.time.Clock", "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime",
                    "java.time.LocalTime", "java.time.ZonedDateTime", "java.util.Date");
            for (final Class<?> type : List.of(MenuResponse.class, MenuType.class, MenuOption.class,
                    MainMenuOption.class, AdminMenuOption.class)) {
                for (final String typeName : ReflectionCensus.declaredSurfaceTypeNames(type)) {
                    assertThat(temporalTypes)
                            .as("a menu option table has no temporal component, so %s must expose none",
                                    type.getSimpleName())
                            .doesNotContain(typeName);
                }
            }
        }

        @Test
        @DisplayName("the enumeration is closed, so no third menu or third user class can appear")
        void enumerationsAreClosed() {
            assertThat(MenuType.values())
                    .as("the corpus declares exactly two option tables")
                    .hasSize(2);
            assertThat(UserType.values())
                    .as("app/cpy/COCOM01Y.cpy:L27-L28 declares exactly two condition names")
                    .hasSize(2);
            assertThat(UserType.ADMIN.getCode()).isEqualTo(ADMIN_USER_CODE);
            assertThat(UserType.USER.getCode()).isEqualTo(STANDARD_USER_CODE);

            assertThat(ReflectionCensus.declaredMethodNames(MenuType.class))
                    .containsExactlyInAnyOrder("values", "valueOf", "getPopulatedOptionCount",
                            "getDeclaredCapacity");
        }
    }

    /**
     * The twenty-field map projection. These assertions are about the <em>screen</em> contract rather than
     * the option tables: {@code app/cpy-bms/COMEN01.CPY} and {@code app/cpy-bms/COADM01.CPY} each declare
     * twenty input fields, and every one of them must be present, at its declared width, with absence
     * distinguishable from emptiness.
     */
    @Nested
    @DisplayName("The twenty-field menu map projection")
    class MapProjection {

        /**
         * The twenty component names in map order, which is the order of the {@code 02} declarations at
         * {@code app/cpy-bms/COMEN01.CPY}:24 through :138.
         */
        private static final List<String> MAP_ORDER = List.of(
                "transactionName", "title01", "currentDate", "programName", "title02", "currentTime",
                "optionSlot01", "optionSlot02", "optionSlot03", "optionSlot04", "optionSlot05",
                "optionSlot06", "optionSlot07", "optionSlot08", "optionSlot09", "optionSlot10",
                "optionSlot11", "optionSlot12", "selectedOption", "errorMessage");

        /**
         * The declared width of each field, positionally aligned with {@link #MAP_ORDER}.
         */
        private static final List<Integer> MAP_WIDTHS = List.of(
                4, 40, 8, 8, 40, 8,
                40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40,
                2, 78);

        /**
         * The source {@code PICTURE} clause of each field, positionally aligned with {@link #MAP_ORDER}.
         */
        private static final List<String> MAP_PICTURES = List.of(
                "PIC X(4)", "PIC X(40)", "PIC X(8)", "PIC X(8)", "PIC X(40)", "PIC X(8)",
                "PIC X(40)", "PIC X(40)", "PIC X(40)", "PIC X(40)", "PIC X(40)", "PIC X(40)",
                "PIC X(40)", "PIC X(40)", "PIC X(40)", "PIC X(40)", "PIC X(40)", "PIC X(40)",
                "PIC X(2)", "PIC X(78)");

        /**
         * Builds a projection from twenty positional values, so that a single field can be varied while the
         * other nineteen stay absent.
         *
         * @param values exactly twenty values, in {@link #MAP_ORDER}, any of which may be {@code null}
         * @return the constructed projection
         */
        private static MenuScreen screenFrom(final List<String> values) {
            return new MenuScreen(
                    values.get(0), values.get(1), values.get(2), values.get(3), values.get(4),
                    values.get(5), values.get(6), values.get(7), values.get(8), values.get(9),
                    values.get(10), values.get(11), values.get(12), values.get(13), values.get(14),
                    values.get(15), values.get(16), values.get(17), values.get(18), values.get(19));
        }

        /**
         * Returns twenty absent values with one position replaced.
         *
         * @param index the position to populate
         * @param value the value to place there
         * @return a twenty-element list, absent everywhere except {@code index}
         */
        private static List<String> onlyAt(final int index, final String value) {
            final List<String> values = new ArrayList<>();
            for (int position = 0; position < MAP_ORDER.size(); position++) {
                values.add(position == index ? value : null);
            }
            return values;
        }

        /**
         * Returns a string of {@code length} repetitions of {@code 'X'}.
         *
         * @param length the required length
         * @return the filler string
         */
        private static String filler(final int length) {
            return "X".repeat(length);
        }

        @Test
        @DisplayName("declares exactly the twenty map fields, in the order the copybook declares them")
        void declaresExactlyTheTwentyMapFieldsInMapOrder() {
            assertThat(recordComponentNames(MenuScreen.class))
                    .as("app/cpy-bms/COMEN01.CPY declares twenty 02-level input fields at :L24, :L30, "
                            + ":L36, :L42, :L48, :L54, :L60, :L66, :L72, :L78, :L84, :L90, :L96, :L102, "
                            + ":L108, :L114, :L120, :L126, :L132 and :L138, and record components preserve "
                            + "declaration order, so order is part of the assertion")
                    .containsExactlyElementsOf(MAP_ORDER);
        }

        @Test
        @DisplayName("the field-count arithmetic is derived from its parts and totals twenty")
        void theFieldCountArithmeticTotalsTwenty() {
            assertThat(MenuScreen.HEADER_FIELD_COUNT).isEqualTo(6);
            assertThat(MenuScreen.OPTION_SLOT_COUNT).isEqualTo(12);
            assertThat(MenuScreen.MAP_FIELD_COUNT)
                    .as("6 header fields + 12 caption slots + OPTIONI + ERRMSGI = 20, counted directly "
                            + "against both app/cpy-bms/COMEN01.CPY and app/cpy-bms/COADM01.CPY")
                    .isEqualTo(20)
                    .isEqualTo(MenuScreen.HEADER_FIELD_COUNT + MenuScreen.OPTION_SLOT_COUNT + 1 + 1)
                    .isEqualTo(MAP_ORDER.size());
        }

        @Test
        @DisplayName("every declared width equals the PICTURE clause of the field it transcribes")
        void everyDeclaredWidthMatchesItsPictureClause() {
            assertThat(MenuScreen.TRANSACTION_NAME_WIDTH).isEqualTo(4);
            assertThat(MenuScreen.TITLE_WIDTH).isEqualTo(40);
            assertThat(MenuScreen.DATE_WIDTH).isEqualTo(8);
            assertThat(MenuScreen.PROGRAM_NAME_WIDTH).isEqualTo(8);
            assertThat(MenuScreen.TIME_WIDTH).isEqualTo(8);
            assertThat(MenuScreen.SELECTION_WIDTH).isEqualTo(2);
            assertThat(MenuScreen.ERROR_MESSAGE_WIDTH).isEqualTo(78);
            assertThat(MenuResponse.SCREEN_OPTION_SLOT_LENGTH)
                    .as("OPTN001I through OPTN012I are X(40), which is wider than the X(35) caption the "
                            + "table holds because app/cbl/COMEN01C.cbl:L243-L246 prefixes the option "
                            + "number and a '. ' separator")
                    .isEqualTo(40);

            assertThat(MenuScreen.PROGRAM_NAME_WIDTH)
                    .as("numerically equal to the table's program-name width, but a separate contract: "
                            + "PGMNAMEI at app/cpy-bms/COMEN01.CPY:L42 against CDEMO-MENU-OPT-PGMNAME at "
                            + "app/cpy/COMEN02Y.cpy")
                    .isEqualTo(MenuResponse.PROGRAM_NAME_LENGTH);
        }

        @Test
        @DisplayName("a value exactly at its declared width is accepted, for all twenty fields")
        void exactlyAtTheDeclaredWidthIsAccepted() {
            for (int index = 0; index < MAP_ORDER.size(); index++) {
                final int width = MAP_WIDTHS.get(index);
                final MenuScreen screen = screenFrom(onlyAt(index, filler(width)));
                assertThat(screen)
                        .as("field '%s' must accept its full declared width of %d", MAP_ORDER.get(index),
                                width)
                        .isNotNull();
            }
        }

        @Test
        @DisplayName("a value one character over its declared width is refused, naming the field and clause")
        void overWideValuesAreRefusedNamingTheFieldAndClause() {
            for (int index = 0; index < MAP_ORDER.size(); index++) {
                final String fieldName = MAP_ORDER.get(index);
                final int width = MAP_WIDTHS.get(index);
                final List<String> values = onlyAt(index, filler(width + 1));

                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("field '%s' is %s, so %d characters must be refused rather than truncated",
                                fieldName, MAP_PICTURES.get(index), width + 1)
                        .isThrownBy(() -> screenFrom(values))
                        .withMessageContaining(fieldName)
                        .withMessageContaining(MAP_PICTURES.get(index))
                        .withMessageContaining(String.valueOf(width))
                        .withNoCause();
            }
        }

        @Test
        @DisplayName("absent, empty and marked remain three distinct states, with no coercion either way")
        void absentEmptyAndMarkedRemainDistinct() {
            final MenuScreen absent = screenFrom(onlyAt(0, null));
            final MenuScreen empty = screenFrom(onlyAt(0, ""));
            final MenuScreen marked = screenFrom(onlyAt(0, "    "));

            assertThat(absent.transactionName()).isNull();
            assertThat(empty.transactionName()).isEmpty();
            assertThat(marked.transactionName()).isEqualTo("    ");

            assertThat(absent)
                    .as("an absent field and an empty field are different inputs and must stay different")
                    .isNotEqualTo(empty);
            assertThat(empty).isNotEqualTo(marked);
        }

        @Test
        @DisplayName("nothing is trimmed, padded or case folded on the way in")
        void nothingIsTrimmedPaddedOrCaseFolded() {
            final MenuScreen screen = screenFrom(onlyAt(1, "  Mixed Case Title  "));

            assertThat(screen.title01())
                    .as("leading and trailing spaces are screen content, and the corpus applies no case "
                            + "function to a title line")
                    .isEqualTo("  Mixed Case Title  ")
                    .hasSize(20);
        }

        @Test
        @DisplayName("the selection field carries two raw characters, so blank, padded and non-numeric differ")
        void theSelectionFieldCarriesTwoRawCharacters() {
            final int selectionIndex = MAP_ORDER.indexOf("selectedOption");

            assertThat(screenFrom(onlyAt(selectionIndex, "  ")).selectedOption()).isEqualTo("  ");
            assertThat(screenFrom(onlyAt(selectionIndex, " 1")).selectedOption()).isEqualTo(" 1");
            assertThat(screenFrom(onlyAt(selectionIndex, "1 ")).selectedOption()).isEqualTo("1 ");
            assertThat(screenFrom(onlyAt(selectionIndex, "AB")).selectedOption())
                    .as("app/cbl/COMEN01C.cbl:L127-L129 has to be able to see a non-numeric selection, so "
                            + "the projection must not reject or normalise one")
                    .isEqualTo("AB");
            assertThat(screenFrom(onlyAt(selectionIndex, null)).selectedOption()).isNull();
        }

        @Test
        @DisplayName("the slot view preserves map order, keeps absent slots absent, and cannot be mutated")
        void theSlotViewPreservesMapOrderAndAbsentSlots() {
            final List<String> values = new ArrayList<>();
            for (int position = 0; position < MAP_ORDER.size(); position++) {
                values.add(null);
            }
            values.set(MAP_ORDER.indexOf("optionSlot01"), "1. First");
            values.set(MAP_ORDER.indexOf("optionSlot10"), "10. Tenth");

            final List<String> slots = screenFrom(values).optionSlots();

            assertThat(slots).hasSize(MenuScreen.OPTION_SLOT_COUNT);
            assertThat(slots.get(0)).isEqualTo("1. First");
            assertThat(slots.get(9)).isEqualTo("10. Tenth");
            assertThat(slots.get(10))
                    .as("the main menu paints ten options and the admin menu four, so slots eleven and "
                            + "twelve are declared because app/cpy-bms/COMEN01.CPY:L120 and :L126 declare "
                            + "them, not because either menu fills them")
                    .isNull();
            assertThat(slots.get(11)).isNull();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> slots.set(0, "tampered"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> slots.add("tampered"));
        }

        @Test
        @DisplayName("the slot view is a derivation, not a twenty-first wire field")
        void theSlotViewIsNotABeanProperty() {
            assertThat(ReflectionCensus.declaredMethodNames(MenuScreen.class))
                    .as("named without a get prefix so that it is not a bean property: the twelve captions "
                            + "are already on the payload as components, and emitting the view as well "
                            + "would put every caption on the wire twice")
                    .contains("optionSlots")
                    .doesNotContain("getOptionSlots");

            assertThat(recordComponentNames(MenuScreen.class))
                    .as("a derivation is never a record component")
                    .doesNotContain("optionSlots");
        }

        @Test
        @DisplayName("declares exactly the twenty accessors, the slot view, the guard and the value methods")
        void declaresExactlyTheDocumentedMethods() {
            final List<String> expected = new ArrayList<>(MAP_ORDER);
            expected.add("optionSlots");
            expected.add("requireScreenFieldWidth");
            expected.add("equals");
            expected.add("hashCode");
            expected.add("toString");

            assertThat(ReflectionCensus.declaredMethodNames(MenuScreen.class))
                    .containsExactlyInAnyOrderElementsOf(expected);
        }

        @Test
        @DisplayName("carries no annotation, no routing state and no retained screen state")
        void carriesNoAnnotationAndNoSessionState() {
            assertThat(ReflectionCensus.declaredAnnotationTypeNames(MenuScreen.class)).isEmpty();
            assertNoMemberNameContains(FORBIDDEN_SESSION_STATE_FRAGMENTS, MenuScreen.class);
            assertNoMemberNameContains(FORBIDDEN_DISPATCH_FRAGMENTS, MenuScreen.class);
            assertThat(ReflectionCensus.declaredMethodNames(MenuScreen.class))
                    .noneMatch(name -> name.startsWith("set"));
        }

        @Test
        @DisplayName("is a value: equal contents are equal, and its rendering discloses nothing sensitive")
        void isAValueWhoseRenderingDisclosesNothingSensitive() {
            final MenuScreen first = screenFrom(onlyAt(1, "Main Menu"));
            final MenuScreen second = screenFrom(onlyAt(1, "Main Menu"));

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first.toString())
                    .as("every component is a public caption, a header rendering, a menu selection or an "
                            + "error line, so the generated rendering carries no personal data, no account "
                            + "identifier and no credential")
                    .contains("Main Menu");
        }
    }
    /**
     * Reads a compiled class file as text so its constant pool can be screened for references.
     *
     * <p>Decoded as ISO-8859-1 because that maps every byte to exactly one character, which makes the
     * search total: no byte sequence can be lost to a decoding error, and the internal class names a
     * constant pool holds are ASCII.
     *
     * @param type the type whose compiled form is wanted
     * @return the class file's bytes as a searchable string, never {@code null}
     */
    private String compiledFormOf(final Class<?> type) {
        final String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream stream = type.getClassLoader().getResourceAsStream(resource)) {
            assertThat(stream)
                    .as("the compiled form of %s must be readable for this assertion to mean "
                            + "anything; a silently absent resource would make it vacuous", resource)
                    .isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
        } catch (final java.io.IOException failure) {
            throw new AssertionError("could not read the compiled form of " + resource, failure);
        }
    }

}
