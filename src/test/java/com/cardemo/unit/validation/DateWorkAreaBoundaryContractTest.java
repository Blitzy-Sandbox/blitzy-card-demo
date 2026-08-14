/*
 * ******************************************************************
 * Program     : DateWorkAreaBoundaryContractTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/validation
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Asserts the declarative boundary contract of the date
 *               working-storage copybook - the eight 88-level
 *               condition sets proved by exhaustive membership AND
 *               exhaustive non-membership sweeps, the tri-state flag
 *               alphabet with its two group-level conditions and the
 *               twenty-five combinations that satisfy neither, the
 *               eight-byte edit-date geometry with its adjacent binary
 *               field, and the eighty-byte result-area declaration
 *               ladder together with its byte-identical twin in the
 *               called program and the four divergences between them.
 * Source      : app/cpy/CSUTLDWY.cpy:L4-L88 - the whole copybook: the
 *               REDEFINES ladder (L4-L42), the eight 88-level sets
 *               (L9, L10, L19-L20, L21-L23, L24, L28-L29, L30, L31,
 *               L32, L33-L34), the three-byte flag group (L43-L57),
 *               the copybook-path mask (L58-L59) and the eighty-byte
 *               result area (L60-L85) @ 7756d89
 * Source      : app/cpy/CSUTLDPY.cpy:L18-L20, L66-L68, L91, L111,
 *               L126-L129, L150, L213-L226, L228-L241, L243-L272,
 *               L274, L290-L296, L327 and L343-L350 - the consumer
 *               that gives those declarations their meaning @ 7756d89
 * Source      : app/cbl/CSUTLDTC.cbl:L42-L57 (the WS-MESSAGE twin),
 *               L84-L86 (LS-DATE X(10), LS-RESULT X(80)) and
 *               L105-L106 (the unconditional LENGTH OF move that
 *               overreads the eight-byte argument) @ 7756d89
 * Source      : app/cbl/CBACT04C.cbl:L1-L21 - the canonical banner
 *               this header reproduces @ 7756d89
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
package com.cardemo.unit.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.DateValidationService.EditFlag;
import com.cardemo.service.shared.DateValidationService.EditOutcome;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.IntFunction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The declarative boundary contract of {@code app/cpy/CSUTLDWY.cpy}, the working-storage copybook that
 * declares every date edit in the legacy corpus.
 *
 * <h2>What it does</h2>
 *
 * <p>{@code app/cpy/CSUTLDWY.cpy} is 89 lines of pure declaration with no executable statement anywhere. It
 * contributes four things to the system, and this class asserts all four:
 *
 * <ul>
 *   <li><strong>The eight 88-level condition sets.</strong> {@code THIS-CENTURY} at {@code :L9},
 *       {@code LAST-CENTURY} at {@code :L10}, {@code WS-VALID-MONTH} at {@code :L19-L20},
 *       {@code WS-31-DAY-MONTH} at {@code :L21-L23}, {@code WS-FEBRUARY} at {@code :L24},
 *       {@code WS-VALID-DAY} at {@code :L28-L29}, the three singletons {@code WS-DAY-31},
 *       {@code WS-DAY-30} and {@code WS-DAY-29} at {@code :L30}, {@code :L31} and {@code :L32}, and
 *       {@code WS-VALID-FEB-DAY} at {@code :L33-L34}. Each is proved by an <em>exhaustive</em> sweep of
 *       its whole two-digit domain, so that exact membership and exact non-membership are both
 *       established. A spot check would pass against a range where the copybook declares a set.</li>
 *   <li><strong>The tri-state flag alphabet and its two group-level conditions</strong> at
 *       {@code :L43-L57}: three one-byte flags, each holding {@code LOW-VALUES}, {@code '0'} or
 *       {@code 'B'}, beneath the group conditions {@code WS-EDIT-DATE-IS-VALID} ({@code :L44}) and
 *       {@code WS-EDIT-DATE-IS-INVALID} ({@code :L45}).</li>
 *   <li><strong>The eight-byte edit-date geometry</strong> at {@code :L4-L42}: four two-byte components,
 *       each with an alphanumeric view and a redefining numeric view, then the whole-group numeric
 *       redefinition and the adjacent {@code WS-EDIT-DATE-BINARY}.</li>
 *   <li><strong>The masks and the eighty-byte result area</strong> at {@code :L58-L59} and
 *       {@code :L60-L85}, together with the byte-identical twin declared as {@code WS-MESSAGE} at
 *       {@code app/cbl/CSUTLDTC.cbl:L42-L57} and the four divergences between the two.</li>
 *   </ul>
 *
 * <p>The subject under assertion is {@link DateValidationService}, the Java replacement for the statically
 * called {@code CSUTLDTC} and for the two work-area copybooks {@code CSUTLDPY} and {@code CSUTLDWY}. The
 * century, month and day limits are private inside that class, so the condition sets are reachable only
 * through {@link DateValidationService#editDate(String, String)}; the geometry constants are public and are
 * asserted directly.
 *
 * <h2>Scope boundary - what this class deliberately does NOT assert</h2>
 *
 * <p>Rule 1 Clause C forbids duplication, and three sibling classes in this package already own adjacent
 * ground. {@code DateValidationServiceTest} asserts the <em>rendered</em> result area at hard-coded offsets,
 * the nine {@code CEEDAYS} feedback conditions and the individual edit messages;
 * {@code DateValidationServiceSweepTest} asserts the documented {@code CEEDAYS} input domain;
 * {@code DateValidationServiceGuardPathTest} asserts the defensive branches including the eighty-character
 * result-area length guard. This class asserts the <em>declaration</em> instead: the width ladder, the
 * offsets <em>derived</em> from it, the closed-form condition sets and the flag alphabet. One deliberate
 * cross-check ties the derived offsets back to a rendered area, because an unanchored declaration table
 * would assert nothing but itself.
 *
 * <h2>How to run, build and test</h2>
 *
 * <pre>
 * ./mvnw test                                              # the whole unit tier
 * ./mvnw test -Dtest=DateWorkAreaBoundaryContractTest      # this class alone
 * </pre>
 *
 * <p><strong>Blocker - build-path contract.</strong> The root {@code pom.xml} binds Surefire 3.5.4 to
 * {@code **}{@code /*Test.java} and excludes only the {@code integration} and {@code e2e} package trees, so
 * a class must live under {@code src/test/java/com/cardemo/unit/**} and end in {@code Test} to be collected
 * at all. A class outside that intersection is collected by neither Surefire nor Failsafe and silently never
 * runs: the build stays green, both plugins report success and coverage records the class as untouched.
 * <em>Remedy: never rename or relocate this file, and confirm it appears in
 * {@code target/surefire-reports/} rather than trusting the exit code.</em>
 *
 * <h2>Key configs and defaults</h2>
 *
 * <p>No fixture is read, no file is opened, no process is spawned, no network address is named and no
 * reflection is used. {@code FixtureLoader} and {@code FixedClockProvider} in the sibling
 * {@code com.cardemo.unit.model} package are deliberately <em>not</em> imported: this class needs neither,
 * and an unused import is fatal here.
 *
 * <p><strong>No wall clock is read.</strong> {@link DateValidationService} takes a {@link Clock} by
 * constructor injection, so an instance cannot be built without one, but
 * {@link DateValidationService#editDate(String, String)} never consults it - only the date-of-birth
 * reasonableness check at {@code app/cpy/CSUTLDPY.cpy:L343} does, and that path belongs to a sibling class.
 * A constant {@link Clock#fixed(Instant, java.time.ZoneId)} is therefore used, matching the convention
 * already established in this package. Nothing here calls {@code now()}, reads a default locale, zone or
 * charset, or draws a random number, and no assertion depends on hash iteration order: every swept
 * expectation is an ascending {@link List}, and the sets are asserted by membership only.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The build fails on a warning rather than an error.</strong> The compiler runs with
 *       {@code -Xlint:all -Werror} and {@code failOnWarning}, and that configuration reaches test
 *       compilation. A single raw type, unchecked cast or deprecated call fails the whole build. An unused
 *       import does not - {@code javac} 25 publishes no {@code unused} lint key - so it is caught at review
 *       instead. <em>Remedy: import only what is used and prune on every edit.</em></li>
 *   <li><strong>The tri-state flags collapsed into a boolean.</strong> Three states cannot survive in one
 *       bit, and the state that disappears is the reachable one: see
 *       {@code EditFlagGroupTriStateAlphabet} below. <em>Remedy: keep the three-valued enum.</em></li>
 *   <li><strong>{@code WS-VALID-FEB-DAY} implemented as {@code 1 THROUGH 29}.</strong> The copybook stops
 *       at 28 and routes the 29th through the leap-year arithmetic at
 *       {@code app/cpy/CSUTLDPY.cpy:L243-L272}. Widening the range to 29 bypasses that branch entirely and
 *       accepts 29 February in every year. <em>Remedy: keep the upper bound at 28 and let the leap branch
 *       admit the 29th.</em></li>
 *   <li><strong>{@code WS-31-DAY-MONTH} implemented as a range.</strong> {@code 1, 3, 5, 7, 8, 10, 12} is a
 *       set with five gaps. <em>Remedy: keep the set, and sweep the whole domain rather than sampling
 *       it.</em></li>
 *   <li><strong>The two masks conflated.</strong> The copybook path uses {@code PIC X(08) VALUE 'YYYYMMDD'}
 *       and the calling programs use {@code PIC X(10) VALUE 'YYYY-MM-DD'}; passing one where the other is
 *       expected changes what is parsed. <em>Remedy: keep two named constants of eight and ten
 *       characters.</em></li>
 *   <li><strong>A fixture name typed from memory.</strong> Elsewhere in this suite the daily transaction
 *       fixture is {@code dailytran.txt} and never {@code dalytran.txt}, even though the mainframe dataset
 *       and DD name are {@code DALYTRAN}. This class reads no fixture, so it cannot be bitten - but the
 *       trap is recorded because the sibling tiers can.</li>
 *   </ul>
 *
 * <h2>Findings carried into this class, by severity</h2>
 *
 * <ul>
 *   <li><strong>Blocker</strong> - the Surefire build-path contract described above.</li>
 *   <li><strong>High</strong> - collapsing the tri-state flags into a boolean; widening
 *       {@code WS-VALID-FEB-DAY} past 28; conflating the eight-character and ten-character masks; confusing
 *       the flag byte {@code WS-EDIT-MONTH} ({@code :L50}) with the data field {@code WS-EDIT-DATE-MM}
 *       ({@code :L16}) or with the paragraph label {@code EDIT-MONTH}
 *       ({@code app/cpy/CSUTLDPY.cpy:L91}), and likewise {@code WS-EDIT-DAY} ({@code :L54}) with
 *       {@code WS-EDIT-DATE-DD} ({@code :L25}) and with {@code EDIT-DAY}
 *       ({@code app/cpy/CSUTLDPY.cpy:L150}); and the buffer overread evidenced by the adjacency at
 *       {@code :L37}.</li>
 *   <li><strong>Medium</strong> - the level number of the flag sub-fields is {@code 20} at {@code :L46},
 *       {@code :L50} and {@code :L54}, not {@code 15} as a downstream citation renders it, and the source
 *       governs; and {@code WS-DATE} at {@code :L76} carries no {@code VALUE SPACES} clause where its twin
 *       at {@code app/cbl/CSUTLDTC.cbl:L52} does.</li>
 *   <li><strong>Low</strong> - only the year flag carries a {@code -FLG} suffix ({@code :L46}) while the
 *       month and day flags do not ({@code :L50}, {@code :L54}); {@code Pic 9(4)} at {@code :L68} is mixed
 *       case where every other clause in the file is upper case; {@code :L60} and {@code :L80} each carry a
 *       stray space before the period; the level-10 items at {@code :L58} and {@code :L60} are indented one
 *       column less than those at {@code :L4}, {@code :L35}, {@code :L37}, {@code :L38} and {@code :L43};
 *       and {@code app/cpy/CSUTLDPY.cpy:L5} names its companion work area {@code CSUTLDTR}, a member that
 *       exists nowhere in the repository - the real companion is this class's subject,
 *       {@code CSUTLDWY.cpy}. The version stamp at {@code :L88} reads
 *       {@code Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:15:59 CDT}, second-for-second identical
 *       to {@code app/cpy/CSUTLDPY.cpy:L374} and different from {@code app/cbl/CSUTLDTC.cbl:L156}
 *       ({@code 23:12:35}), which corroborates that the two copybooks were committed as a pair while the
 *       program was committed separately.</li>
 *   </ul>
 *
 * <p><strong>These assertions are the tracking reference for the preserved oddities.</strong> The missing
 * {@code VALUE SPACES}, the mixed-case {@code Pic}, the stray pre-period spaces and the asymmetric
 * {@code -FLG} suffix are recorded here <em>because</em> Rule 1 Clause B forbids untracked artefacts, not in
 * spite of it. No downstream change may tidy the production model to make these read more sensibly: the
 * copybook is frozen, it is the parity oracle, and a tidier Java shape would diverge from it.
 *
 * <h2>Labelled deviation - the buffer overread is evidenced, not reproduced</h2>
 *
 * <p>{@code app/cpy/CSUTLDPY.cpy:L294} passes the eight-byte group {@code WS-EDIT-DATE-CCYYMMDD} by
 * reference into {@code LS-DATE PIC X(10)} at {@code app/cbl/CSUTLDTC.cbl:L84}, and
 * {@code app/cbl/CSUTLDTC.cbl:L105-L106} then moves {@code LENGTH OF LS-DATE}, which is unconditionally 10,
 * into the varying-string length. The Language Environment date service therefore reads two bytes past the
 * end of the argument - and {@code :L37} declares {@code WS-EDIT-DATE-BINARY} immediately after the group,
 * so those two bytes are its first two. This class asserts the <em>adjacency and the two-byte shortfall</em>
 * as the evidence for that defect. It does <strong>not</strong> simulate the overread: a Java reference
 * cannot read past an object, so the behaviour is unreproducible rather than merely unimplemented.
 * <em>Remedy: none is applied, because correcting the length would change what the date service is asked to
 * parse and parity is the contract.</em>
 *
 * <h2>Not available</h2>
 *
 * <ul>
 *   <li>Any rationale for restricting the century to 19 and 20 beyond the source's own comment at
 *       {@code app/cpy/CSUTLDPY.cpy:L66-L68}. <em>Needed: a design note or change record from the original
 *       authors.</em></li>
 *   <li>Any reason the year flag carries a {@code -FLG} suffix while the month and day flags do not.
 *       <em>Needed: the same.</em></li>
 *   <li>Any reason {@code WS-DATE} at {@code :L76} omits {@code VALUE SPACES} where its twin at
 *       {@code app/cbl/CSUTLDTC.cbl:L52} includes it. <em>Needed: the same.</em></li>
 *   <li>Any latency or throughput objective for this tier. None exists anywhere in the source; the
 *       migration records a measured baseline and never invents a target. <em>Needed: a published service
 *       level, of which the corpus has none.</em></li>
 *   </ul>
 */
@DisplayName("CSUTLDWY.cpy - the 88-level boundary alphabet and the buffer geometry it declares")
final class DateWorkAreaBoundaryContractTest {

    /**
     * The instant behind the fixed clock. Chosen only because the constructor requires a clock; no assertion
     * in this class depends on its value, because {@code editDate} never reads it.
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2024-06-15T12:00:00Z");

    /** The field label echoed into every edit message, held once so no assertion repeats the literal. */
    private static final String FIELD_LABEL = "openDate";

    /**
     * The source's own rationale for the century restriction, quoted verbatim from
     * {@code app/cpy/CSUTLDPY.cpy:L66-L68}. It is the only documented justification that exists.
     */
    private static final String CENTURY_RATIONALE =
            "app/cpy/CSUTLDPY.cpy:L66-L68 states the rationale verbatim: 'Not having learnt our lesson "
                    + "from history and Y2K / And being unable to imagine COBOL in the 2100s / We code only "
                    + "19 and 20 as valid century values'";

    /** {@code THIS-CENTURY}, {@code app/cpy/CSUTLDWY.cpy:L9}. */
    private static final int THIS_CENTURY = 20;

    /** {@code LAST-CENTURY}, {@code app/cpy/CSUTLDWY.cpy:L10}. */
    private static final int LAST_CENTURY = 19;

    /**
     * {@code WS-31-DAY-MONTH}, {@code app/cpy/CSUTLDWY.cpy:L21-L23}, in the ascending order the copybook
     * writes it. Held as an ordered immutable list rather than a hash set so that no assertion can acquire a
     * dependence on iteration order.
     */
    private static final List<Integer> THIRTY_ONE_DAY_MONTHS = List.of(1, 3, 5, 7, 8, 10, 12);

    /**
     * The exact complement of {@link #THIRTY_ONE_DAY_MONTHS} within the twelve valid months. These five are
     * the months for which {@code app/cpy/CSUTLDPY.cpy:L213-L226} rejects a 31st day.
     */
    private static final List<Integer> SHORTER_MONTHS = List.of(2, 4, 6, 9, 11);

    /** {@code WS-FEBRUARY}, {@code app/cpy/CSUTLDWY.cpy:L24}. */
    private static final int FEBRUARY = 2;

    /** A leap year whose century is accepted, for the {@code WS-DAY-29} branch. */
    private static final int LEAP_YEAR = 2024;

    /** A non-leap year whose century is accepted, for the same branch's failing side. */
    private static final int NON_LEAP_YEAR = 2023;

    /** The highest value a two-digit zoned field can hold, and therefore the end of every sweep. */
    private static final int TWO_DIGIT_MAXIMUM = 99;

    /** The declared width of every date component, {@code PIC X(2)} at {@code :L6}, {@code :L11},
     * {@code :L16} and {@code :L25}. */
    private static final int COMPONENT_WIDTH = 2;

    /** The declared width of {@code WS-EDIT-DATE-CCYY}, {@code app/cpy/CSUTLDWY.cpy:L5}. */
    private static final int CENTURY_YEAR_WIDTH = 4;

    /** The storage a {@code PIC S9(9) BINARY} item occupies, {@code :L37} and {@code :L42}. */
    private static final int BINARY_WIDTH = 4;

    /** The declared width of the flag group {@code WS-EDIT-DATE-FLGS}, {@code :L43-L57}. */
    private static final int FLAG_GROUP_WIDTH = 3;

    /**
     * Builds the subject. Each test takes its own instance, so no state is shared between tests and no
     * static mutable field exists anywhere in this class.
     *
     * @return a service backed by a constant clock that {@code editDate} never reads
     */
    private static DateValidationService service() {
        return new DateValidationService(Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
    }

    /**
     * Edits an eight-character {@code CCYYMMDD} value through the composite edit.
     *
     * @param ccyymmdd the value under edit, which may be {@code null} to exercise the absent-input path
     * @return the three component flags, the error indicator and the first message
     */
    private static EditOutcome edit(final String ccyymmdd) {
        return service().editDate(ccyymmdd, FIELD_LABEL);
    }

    /**
     * Composes an eight-character date from a four-digit year and two two-digit components, without
     * validating any of them - the point is to feed the edits values they must reject.
     *
     * @param year the four-digit year, century included
     * @param month the month as an integer, rendered as exactly two digits
     * @param day the day as an integer, rendered as exactly two digits
     * @return the eight-character {@code CCYYMMDD} text
     */
    private static String date(final int year, final int month, final int day) {
        return "%04d%02d%02d".formatted(year, month, day);
    }

    /**
     * Sweeps a two-digit domain and collects, in ascending order, every value the whole-date edit accepts.
     *
     * @param builder renders the candidate date for a given two-digit probe
     * @return the accepted probes, ascending, so that no expectation depends on iteration order
     */
    private static List<Integer> acceptedOver(final IntFunction<String> builder) {
        final List<Integer> accepted = new ArrayList<>();
        for (int probe = 0; probe <= TWO_DIGIT_MAXIMUM; probe++) {
            if (edit(builder.apply(probe)).valid()) {
                accepted.add(probe);
            }
        }
        return accepted;
    }

    /**
     * The ascending integers from {@code from} to {@code to} inclusive, used to state a swept expectation as
     * a closed interval when the copybook declares one with {@code THROUGH}.
     *
     * @param from the inclusive lower bound
     * @param to the inclusive upper bound
     * @return the closed interval, ascending
     */
    private static List<Integer> closedRange(final int from, final int to) {
        final List<Integer> range = new ArrayList<>();
        for (int value = from; value <= to; value++) {
            range.add(value);
        }
        return List.copyOf(range);
    }

    /**
     * One elementary or group item as the copybook declares it, carrying enough of the declaration to assert
     * geometry, level numbers, picture spelling and the presence or absence of a {@code VALUE} clause.
     *
     * @param name the data name exactly as spelled in the source, or {@code FILLER}
     * @param level the level number exactly as written, which is part of the twin comparison
     * @param picture the picture clause exactly as spelled, mixed case included
     * @param width the bytes the item occupies, zero for a group whose children are listed separately
     * @param redefines whether the item redefines a preceding one and therefore adds no storage
     * @param valueClause the {@code VALUE} clause exactly as written, empty when none is declared
     */
    private record Declaration(String name, int level, String picture, int width, boolean redefines,
                               Optional<String> valueClause) {

        /**
         * An item with no {@code VALUE} clause.
         *
         * @param name the data name
         * @param level the level number
         * @param picture the picture clause as spelled
         * @param width the bytes occupied
         * @return the declaration
         */
        static Declaration of(final String name, final int level, final String picture, final int width) {
            return new Declaration(name, level, picture, width, false, Optional.empty());
        }

        /**
         * A redefining item, which occupies no additional storage.
         *
         * @param name the data name
         * @param level the level number
         * @param picture the picture clause as spelled
         * @param width the bytes it reinterprets
         * @return the declaration
         */
        static Declaration redefining(final String name, final int level, final String picture,
                                      final int width) {
            return new Declaration(name, level, picture, width, true, Optional.empty());
        }

        /**
         * An item with a {@code VALUE} clause.
         *
         * @param name the data name, or {@code FILLER}
         * @param level the level number
         * @param picture the picture clause as spelled
         * @param width the bytes occupied
         * @param value the {@code VALUE} clause as written
         * @return the declaration
         */
        static Declaration valued(final String name, final int level, final String picture, final int width,
                                  final String value) {
            return new Declaration(name, level, picture, width, false, Optional.of(value));
        }

        /**
         * The storage this item contributes to its parent group.
         *
         * @return the width, or zero when the item merely redefines earlier storage
         */
        int contributedWidth() {
            return redefines ? 0 : width;
        }
    }

    /**
     * Sums the storage a ladder of declarations occupies, excluding redefinitions.
     *
     * @param ladder the declarations in source order
     * @return the total bytes occupied
     */
    private static int occupiedWidth(final List<Declaration> ladder) {
        int total = 0;
        for (final Declaration declaration : ladder) {
            total += declaration.contributedWidth();
        }
        return total;
    }

    /**
     * Sums a ladder while wrongly counting redefinitions as storage, which is the mistake the geometry
     * assertions exist to catch.
     *
     * @param ladder the declarations in source order
     * @return the total that results from treating every declaration as occupying its own bytes
     */
    private static int widthCountingRedefinitions(final List<Declaration> ladder) {
        int total = 0;
        for (final Declaration declaration : ladder) {
            total += declaration.width();
        }
        return total;
    }

    /**
     * The elementary items of {@code WS-EDIT-DATE-CCYYMMDD}, {@code app/cpy/CSUTLDWY.cpy:L4-L34}, in source
     * order. The group items {@code :L4} and {@code :L5} carry no picture of their own and are represented
     * by their children.
     */
    private static final List<Declaration> EDIT_DATE_GROUP = List.of(
            Declaration.of("WS-EDIT-DATE-CC", 25, "PIC X(2)", COMPONENT_WIDTH),
            Declaration.redefining("WS-EDIT-DATE-CC-N", 25, "PIC 9(2)", COMPONENT_WIDTH),
            Declaration.of("WS-EDIT-DATE-YY", 25, "PIC X(2)", COMPONENT_WIDTH),
            Declaration.redefining("WS-EDIT-DATE-YY-N", 25, "PIC 9(2)", COMPONENT_WIDTH),
            Declaration.redefining("WS-EDIT-DATE-CCYY-N", 20, "PIC 9(4)", CENTURY_YEAR_WIDTH),
            Declaration.of("WS-EDIT-DATE-MM", 20, "PIC X(2)", COMPONENT_WIDTH),
            Declaration.redefining("WS-EDIT-DATE-MM-N", 20, "PIC 9(2)", COMPONENT_WIDTH),
            Declaration.of("WS-EDIT-DATE-DD", 20, "PIC X(2)", COMPONENT_WIDTH),
            Declaration.redefining("WS-EDIT-DATE-DD-N", 20, "PIC 9(2)", COMPONENT_WIDTH));

    /**
     * The level-10 items that follow the edit-date group, {@code app/cpy/CSUTLDWY.cpy:L35-L37}, in source
     * order. The adjacency of the second to the first is the evidence for the overread.
     */
    private static final List<Declaration> EDIT_DATE_TAIL = List.of(
            Declaration.redefining("WS-EDIT-DATE-CCYYMMDD-N", 10, "PIC 9(8)",
                    DateValidationService.CCYYMMDD_LENGTH),
            Declaration.of("WS-EDIT-DATE-BINARY", 10, "PIC S9(9) BINARY", BINARY_WIDTH));

    /**
     * {@code WS-CURRENT-DATE}, {@code app/cpy/CSUTLDWY.cpy:L38-L42}: the same eight-byte-plus-binary shape as
     * the edit date, which is what makes the date-of-birth comparison at
     * {@code app/cpy/CSUTLDPY.cpy:L345-L350} a binary-to-binary comparison.
     */
    private static final List<Declaration> CURRENT_DATE_GROUP = List.of(
            Declaration.of("WS-CURRENT-DATE-YYYYMMDD", 20, "PIC X(8)",
                    DateValidationService.CCYYMMDD_LENGTH),
            Declaration.redefining("WS-CURRENT-DATE-YYYYMMDD-N", 20, "PIC 9(8)",
                    DateValidationService.CCYYMMDD_LENGTH),
            Declaration.of("WS-CURRENT-DATE-BINARY", 20, "PIC S9(9) BINARY", BINARY_WIDTH));

    /**
     * {@code WS-EDIT-DATE-FLGS}, {@code app/cpy/CSUTLDWY.cpy:L43-L57}: three one-byte flags at level
     * <strong>20</strong>. Note the names - only the first carries a {@code -FLG} suffix, and the second and
     * third are one character away from the data fields {@code WS-EDIT-DATE-MM} and {@code WS-EDIT-DATE-DD}
     * and from the paragraph labels {@code EDIT-MONTH} and {@code EDIT-DAY}.
     */
    private static final List<Declaration> FLAG_GROUP = List.of(
            Declaration.of("WS-EDIT-YEAR-FLG", 20, "PIC X(01)", 1),
            Declaration.of("WS-EDIT-MONTH", 20, "PIC X(01)", 1),
            Declaration.of("WS-EDIT-DAY", 20, "PIC X(01)", 1));

    /**
     * {@code WS-DATE-VALIDATION-RESULT}, {@code app/cpy/CSUTLDWY.cpy:L60-L85}: fifteen declarations of which
     * thirteen occupy storage, summing to exactly eighty bytes. Pictures are spelled exactly as the source
     * spells them, which is why one of them reads {@code Pic 9(4)}.
     */
    private static final List<Declaration> RESULT_AREA = List.of(
            Declaration.of("WS-SEVERITY", 20, "PIC X(04)", 4),
            Declaration.redefining("WS-SEVERITY-N", 20, "PIC 9(4)", 4),
            Declaration.valued("FILLER", 20, "PIC X(11)", 11, "'Mesg Code:'"),
            Declaration.of("WS-MSG-NO", 20, "PIC X(04)", 4),
            Declaration.redefining("WS-MSG-NO-N", 20, "Pic 9(4)", 4),
            Declaration.valued("FILLER", 20, "PIC X(01)", 1, "SPACE"),
            Declaration.of("WS-RESULT", 20, "PIC X(15)", 15),
            Declaration.valued("FILLER", 20, "PIC X(01)", 1, "SPACE"),
            Declaration.valued("FILLER", 20, "PIC X(09)", 9, "'TstDate:'"),
            Declaration.of("WS-DATE", 20, "PIC X(10)", 10),
            Declaration.valued("FILLER", 20, "PIC X(01)", 1, "SPACE"),
            Declaration.valued("FILLER", 20, "PIC X(10)", 10, "'Mask used:'"),
            Declaration.of("WS-DATE-FMT", 20, "PIC X(10)", 10),
            Declaration.valued("FILLER", 20, "PIC X(01)", 1, "SPACE"),
            Declaration.valued("FILLER", 20, "PIC X(03)", 3, "SPACES"));

    /**
     * {@code WS-MESSAGE}, {@code app/cbl/CSUTLDTC.cbl:L42-L57}: the twin of {@link #RESULT_AREA}, declared
     * in the called program at level {@code 02}. Field order, widths and literals are identical; the four
     * divergences are the level number, the picture spelling at {@code app/cpy/CSUTLDWY.cpy:L68}, the
     * {@code VALUE SPACES} clause on {@code WS-DATE} that only this side declares, and the stray pre-period
     * spaces that only the copybook side carries.
     */
    private static final List<Declaration> TWIN_RESULT_AREA = List.of(
            Declaration.of("WS-SEVERITY", 2, "PIC X(04)", 4),
            Declaration.redefining("WS-SEVERITY-N", 2, "PIC 9(4)", 4),
            Declaration.valued("FILLER", 2, "PIC X(11)", 11, "'Mesg Code:'"),
            Declaration.of("WS-MSG-NO", 2, "PIC X(04)", 4),
            Declaration.redefining("WS-MSG-NO-N", 2, "PIC 9(4)", 4),
            Declaration.valued("FILLER", 2, "PIC X(01)", 1, "SPACE"),
            Declaration.of("WS-RESULT", 2, "PIC X(15)", 15),
            Declaration.valued("FILLER", 2, "PIC X(01)", 1, "SPACE"),
            Declaration.valued("FILLER", 2, "PIC X(09)", 9, "'TstDate:'"),
            Declaration.valued("WS-DATE", 2, "PIC X(10)", 10, "SPACES"),
            Declaration.valued("FILLER", 2, "PIC X(01)", 1, "SPACE"),
            Declaration.valued("FILLER", 2, "PIC X(10)", 10, "'Mask used:'"),
            Declaration.of("WS-DATE-FMT", 2, "PIC X(10)", 10),
            Declaration.valued("FILLER", 2, "PIC X(01)", 1, "SPACE"),
            Declaration.valued("FILLER", 2, "PIC X(03)", 3, "SPACES"));

    /**
     * The eight-byte edit-date geometry and its REDEFINES ladder, {@code app/cpy/CSUTLDWY.cpy:L4-L42}.
     */
    @Nested
    @DisplayName("the eight-byte edit-date geometry and its REDEFINES ladder, CSUTLDWY.cpy:L4-L42")
    class EditDateGeometry {

        @Test
        @DisplayName("the group is exactly eight bytes, the sum of its four two-byte components")
        void theGroupIsExactlyEightBytes() {
            assertThat(occupiedWidth(EDIT_DATE_GROUP))
                    .as("app/cpy/CSUTLDWY.cpy:L4 WS-EDIT-DATE-CCYYMMDD is CC(2) + YY(2) + MM(2) + DD(2), "
                            + "and the four redefinitions in the same group add no storage")
                    .isEqualTo(COMPONENT_WIDTH * 4)
                    .isEqualTo(DateValidationService.CCYYMMDD_LENGTH)
                    .isEqualTo(8);

            assertThat(widthCountingRedefinitions(EDIT_DATE_GROUP))
                    .as("counting the five redefinitions as storage would give 20 bytes, which is the "
                            + "mistake this arithmetic exists to catch")
                    .isEqualTo(20)
                    .isNotEqualTo(DateValidationService.CCYYMMDD_LENGTH);
        }

        @Test
        @DisplayName("the century-and-year sub-group is exactly four bytes and is redefined as one PIC 9(4)")
        void theCenturyAndYearSubGroupIsExactlyFourBytes() {
            final List<Declaration> subGroup = EDIT_DATE_GROUP.subList(0, 4);

            assertThat(occupiedWidth(subGroup))
                    .as("app/cpy/CSUTLDWY.cpy:L5 WS-EDIT-DATE-CCYY is CC(2) + YY(2)")
                    .isEqualTo(CENTURY_YEAR_WIDTH);

            final Declaration wholeYear = EDIT_DATE_GROUP.get(4);
            assertThat(wholeYear.name())
                    .as("app/cpy/CSUTLDWY.cpy:L14-L15 redefines the sub-group as a single numeric field, "
                            + "which is what the leap-year DIVIDE at app/cpy/CSUTLDPY.cpy:L251-L254 divides")
                    .isEqualTo("WS-EDIT-DATE-CCYY-N");
            assertThat(wholeYear.picture()).isEqualTo("PIC 9(4)");
            assertThat(wholeYear.redefines()).isTrue();
            assertThat(wholeYear.width())
                    .as("the redefinition covers exactly the four bytes of the sub-group it renames")
                    .isEqualTo(CENTURY_YEAR_WIDTH);
        }

        @Test
        @DisplayName("every component carries both an alphanumeric and a redefining numeric view")
        void everyComponentCarriesBothViews() {
            final List<String> alphanumeric = new ArrayList<>();
            final List<String> numeric = new ArrayList<>();
            for (final Declaration declaration : EDIT_DATE_GROUP) {
                if (declaration.width() != COMPONENT_WIDTH) {
                    continue;
                }
                if (declaration.redefines()) {
                    numeric.add(declaration.name());
                } else {
                    alphanumeric.add(declaration.name());
                }
            }

            assertThat(alphanumeric)
                    .as("app/cpy/CSUTLDWY.cpy:L6, :L11, :L16 and :L25 declare the PIC X(2) views the blank "
                            + "and IS NOT NUMERIC tests read")
                    .containsExactly("WS-EDIT-DATE-CC", "WS-EDIT-DATE-YY", "WS-EDIT-DATE-MM",
                            "WS-EDIT-DATE-DD");
            assertThat(numeric)
                    .as("app/cpy/CSUTLDWY.cpy:L7-L8, :L12-L13, :L17-L18 and :L26-L27 declare the PIC 9(2) "
                            + "views the 88-level range conditions read - one for one, never a subset")
                    .containsExactly("WS-EDIT-DATE-CC-N", "WS-EDIT-DATE-YY-N", "WS-EDIT-DATE-MM-N",
                            "WS-EDIT-DATE-DD-N");
            assertThat(numeric).hasSameSizeAs(alphanumeric);
        }

        @Test
        @DisplayName("the numeric view reinterprets the same bytes rather than validating them")
        void theNumericViewReinterpretsRatherThanValidates() {
            final EditOutcome nonNumericMonth = edit("2024AB15");

            assertThat(nonNumericMonth.monthFlag())
                    .as("app/cpy/CSUTLDWY.cpy:L35-L36 redefines eight alphanumeric bytes as PIC 9(8), and "
                            + "app/cpy/CSUTLDPY.cpy:L111 tests WS-VALID-MONTH through that numeric view "
                            + "BEFORE NUMVAL populates it at :L126-L129. The range condition must therefore "
                            + "be able to reject bytes that are not digits at all, which is only possible if "
                            + "the numeric view exists as a reinterpretation")
                    .isEqualTo(EditFlag.NOT_OK);
            assertThat(nonNumericMonth.yearFlag())
                    .as("and the reinterpretation is per component, not per group: the year bytes of "
                            + "'2024AB15' are sound and stay sound")
                    .isEqualTo(EditFlag.ISVALID);
            assertThat(nonNumericMonth.dayFlag())
                    .as("as do the day bytes, which proves each PIC 9(2) redefinition covers only its own "
                            + "two bytes")
                    .isEqualTo(EditFlag.ISVALID);

            assertThat(edit("202406AB").dayFlag())
                    .as("app/cpy/CSUTLDWY.cpy:L26-L27 WS-EDIT-DATE-DD-N over non-digit day bytes")
                    .isEqualTo(EditFlag.NOT_OK);
            assertThat(edit("AB240615").yearFlag())
                    .as("app/cpy/CSUTLDWY.cpy:L7-L8 WS-EDIT-DATE-CC-N over non-digit century bytes")
                    .isEqualTo(EditFlag.NOT_OK);
        }

        @Test
        @DisplayName("the blank test is on the whole four-byte year group, not on either half")
        void theBlankTestIsOnTheWholeYearGroup() {
            final EditOutcome halfBlankYear = edit("  240615");

            assertThat(halfBlankYear.yearFlag())
                    .as("app/cpy/CSUTLDPY.cpy:L30-L31 tests WS-EDIT-DATE-CCYY - the four-byte group at "
                            + "app/cpy/CSUTLDWY.cpy:L5 - against LOW-VALUES and SPACES. '  24' is neither, "
                            + "so the blank branch is skipped and the value falls through to the NOT "
                            + "NUMERIC branch at :L48 instead")
                    .isEqualTo(EditFlag.NOT_OK);
            assertThat(halfBlankYear.yearFlag())
                    .as("a per-half blank test would have reported BLANK here, which is the divergence this "
                            + "assertion exists to prevent")
                    .isNotEqualTo(EditFlag.BLANK);
            assertThat(halfBlankYear.returnMessage().stripTrailing())
                    .isEqualTo(FIELD_LABEL + " must be 4 digit number.");
        }

        @Test
        @DisplayName("the binary field is declared immediately after the group, which is what CEEDAYS "
                + "overreads")
        void theBinaryFieldIsDeclaredImmediatelyAfterTheGroup() {
            assertThat(EDIT_DATE_TAIL.get(0).name())
                    .as("app/cpy/CSUTLDWY.cpy:L35-L36 is the whole-group numeric redefinition")
                    .isEqualTo("WS-EDIT-DATE-CCYYMMDD-N");
            assertThat(EDIT_DATE_TAIL.get(0).redefines())
                    .as("it renames the eight bytes rather than adding any")
                    .isTrue();
            assertThat(EDIT_DATE_TAIL.get(1).name())
                    .as("app/cpy/CSUTLDWY.cpy:L37 declares WS-EDIT-DATE-BINARY as the very next level-10 "
                            + "item, so its first two bytes are the two immediately after the group")
                    .isEqualTo("WS-EDIT-DATE-BINARY");
            assertThat(EDIT_DATE_TAIL.get(1).picture()).isEqualTo("PIC S9(9) BINARY");

            assertThat(DateValidationService.LS_DATE_LENGTH - DateValidationService.CCYYMMDD_LENGTH)
                    .as("HIGH, LABELLED DEVIATION, EVIDENCED NOT REPRODUCED. app/cpy/CSUTLDPY.cpy:L294 "
                            + "passes the eight-byte group into LS-DATE PIC X(10) at "
                            + "app/cbl/CSUTLDTC.cbl:L84, and :L105-L106 then moves LENGTH OF LS-DATE - "
                            + "unconditionally 10 - so the date service reads exactly this many bytes past "
                            + "the end of its argument, namely the first bytes of WS-EDIT-DATE-BINARY. A "
                            + "Java reference cannot read past an object, so the overread is unreproducible "
                            + "and only its evidence is asserted")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the current-date group repeats the same eight-byte-plus-binary shape")
        void theCurrentDateGroupRepeatsTheSameShape() {
            assertThat(occupiedWidth(CURRENT_DATE_GROUP))
                    .as("app/cpy/CSUTLDWY.cpy:L38-L42 declares PIC X(8) plus a PIC 9(8) redefinition plus a "
                            + "PIC S9(9) BINARY, exactly as :L4-L37 does for the edit date")
                    .isEqualTo(DateValidationService.CCYYMMDD_LENGTH + BINARY_WIDTH);

            assertThat(CURRENT_DATE_GROUP.get(0).width())
                    .as("app/cpy/CSUTLDWY.cpy:L39 WS-CURRENT-DATE-YYYYMMDD is the same eight bytes as the "
                            + "edit date group")
                    .isEqualTo(DateValidationService.CCYYMMDD_LENGTH);
            assertThat(CURRENT_DATE_GROUP.get(1).redefines())
                    .as("app/cpy/CSUTLDWY.cpy:L40-L41 is a redefinition, so the group stays eight bytes wide")
                    .isTrue();
            assertThat(CURRENT_DATE_GROUP.get(2).picture())
                    .as("app/cpy/CSUTLDWY.cpy:L42 WS-CURRENT-DATE-BINARY matches :L37 in kind, which is "
                            + "what makes the comparison at app/cpy/CSUTLDPY.cpy:L350 binary against binary "
                            + "rather than text against text")
                    .isEqualTo(CURRENT_DATE_GROUP.get(2).picture())
                    .isEqualTo("PIC S9(9) BINARY");
            assertThat(EDIT_DATE_TAIL.get(1).picture())
                    .as("both binary fields are declared identically")
                    .isEqualTo(CURRENT_DATE_GROUP.get(2).picture());
        }

        @Test
        @DisplayName("a value wider than the declared eight bytes is truncated into the field")
        void aWiderValueIsTruncated() {
            assertThat(edit("202406151234").valid())
                    .as("a MOVE into the PIC X(8) group at app/cpy/CSUTLDWY.cpy:L4 keeps the leftmost eight "
                            + "bytes and discards the rest, so the four trailing characters cannot affect "
                            + "the verdict")
                    .isTrue();
            assertThat(edit("202406151234"))
                    .as("and the outcome is indistinguishable from the eight-byte value it truncates to")
                    .isEqualTo(edit("20240615"));
        }

        @Test
        @DisplayName("a value narrower than eight bytes is right-padded with spaces into the field")
        void aNarrowerValueIsRightPadded() {
            final EditOutcome shortValue = edit("20240");

            assertThat(shortValue.yearFlag())
                    .as("a MOVE of '20240' into the PIC X(8) group at app/cpy/CSUTLDWY.cpy:L4 pads on the "
                            + "RIGHT, giving '20240   ', so WS-EDIT-DATE-CC at :L6 and WS-EDIT-DATE-YY at "
                            + ":L11 hold '20' and '24' and the century-and-year group at :L5 is sound")
                    .isEqualTo(EditFlag.ISVALID);
            assertThat(shortValue.monthFlag())
                    .as("WS-EDIT-DATE-MM at app/cpy/CSUTLDWY.cpy:L16 becomes '0 ', which is neither blank "
                            + "nor numeric, so it fails the PIC 9(2) view at :L17-L18")
                    .isEqualTo(EditFlag.NOT_OK);
            assertThat(shortValue.dayFlag())
                    .as("the day bytes become two spaces, which app/cpy/CSUTLDPY.cpy:L154-L155 reports as "
                            + "not supplied rather than as wrong")
                    .isEqualTo(EditFlag.BLANK);
        }

        @Test
        @DisplayName("null, empty, all spaces and all LOW-VALUES all reduce to the not-supplied path")
        void absentInputReducesToTheNotSuppliedPath() {
            final List<String> absent = new ArrayList<>();
            absent.add(null);
            absent.add("");
            absent.add(" ".repeat(DateValidationService.CCYYMMDD_LENGTH));
            absent.add("\u0000".repeat(DateValidationService.CCYYMMDD_LENGTH));

            for (final String candidate : absent) {
                final EditOutcome outcome = edit(candidate);
                assertThat(outcome.yearFlag())
                        .as("app/cpy/CSUTLDPY.cpy:L30-L31 tests LOW-VALUES OR SPACES, and a MOVE of an "
                                + "absent value into PIC X(8) yields spaces, so every one of the four "
                                + "absent shapes reaches FLG-YEAR-BLANK at app/cpy/CSUTLDWY.cpy:L49")
                        .isEqualTo(EditFlag.BLANK);
                assertThat(outcome.monthFlag()).isEqualTo(EditFlag.BLANK);
                assertThat(outcome.dayFlag()).isEqualTo(EditFlag.BLANK);
                assertThat(outcome.inputError()).isTrue();
                assertThat(outcome.valid()).isFalse();
                assertThat(outcome.returnMessage().stripTrailing())
                        .isEqualTo(FIELD_LABEL + " : Year must be supplied.");
            }
        }

        @Test
        @DisplayName("wholly non-numeric input reaches the all-NOT-OK state through the source's own path")
        void whollyNonNumericInputReachesTheAllNotOkState() {
            for (final String hostile : List.of("ABCDEFGH", "++++++++")) {
                final EditOutcome outcome = edit(hostile);

                assertThat(List.of(outcome.yearFlag(), outcome.monthFlag(), outcome.dayFlag()))
                        .as("every one of the three PIC 9(n) redefinitions at app/cpy/CSUTLDWY.cpy:L7-L8, "
                                + ":L17-L18 and :L26-L27 reinterprets bytes that are not digits, so all "
                                + "three flag bytes of :L43-L57 take FLG-*-NOT-OK for %s", hostile)
                        .containsExactly(EditFlag.NOT_OK, EditFlag.NOT_OK, EditFlag.NOT_OK);
                assertThat(outcome.allComponentsNotOk())
                        .as("which makes this the all-NOT-OK state, reached here through the real edit path "
                                + "rather than assembled by hand - the group is '000' and therefore "
                                + "satisfies WS-EDIT-DATE-IS-INVALID at app/cpy/CSUTLDWY.cpy:L45")
                        .isTrue();
                assertThat(outcome.valid())
                        .as("and it cannot also satisfy WS-EDIT-DATE-IS-VALID at :L44, which requires all "
                                + "three bytes to be LOW-VALUES")
                        .isFalse();
                assertThat(outcome.inputError()).isTrue();
                assertThat(outcome.returnMessage().stripTrailing())
                        .as("app/cpy/CSUTLDPY.cpy:L48-L58 reports the century-and-year group first, because "
                                + "the year is edited before the month and the day")
                        .isEqualTo(FIELD_LABEL + " must be 4 digit number.");
            }
        }

        @Test
        @DisplayName("HIGH: sign-bearing and separator-bearing input is rejected component by component")
        void signAndSeparatorBearingInputIsRejectedComponentWise() {
            assertThat(edit("-1240615").yearFlag())
                    .as("a PIC 9(2) view over app/cpy/CSUTLDWY.cpy:L6 cannot hold a sign, so a leading "
                            + "minus in the century bytes is simply not two digits")
                    .isEqualTo(EditFlag.NOT_OK);
            assertThat(edit("2024061-").dayFlag())
                    .as("and a trailing minus in the day bytes fails app/cpy/CSUTLDWY.cpy:L26-L27 the same "
                            + "way, which is why no negative component is representable at all")
                    .isEqualTo(EditFlag.NOT_OK);

            final EditOutcome separatorShaped = edit("2024-06-");

            assertThat(separatorShaped.valid())
                    .as("HIGH. This is the eight leftmost bytes of a 'YYYY-MM-DD' value moved into the "
                            + "PIC X(08) field that app/cpy/CSUTLDWY.cpy:L58-L59 masks as 'YYYYMMDD'. The "
                            + "two masks are different widths and different spellings, so feeding the "
                            + "ten-character shape to the eight-character field cannot validate. Remedy: "
                            + "keep MASK_YYYYMMDD and MASK_YYYY_MM_DD as two distinct constants and never "
                            + "let a caller substitute one for the other")
                    .isFalse();
            assertThat(separatorShaped.monthFlag())
                    .as("the month bytes become '-0' and the day bytes '6-', so both components fail while "
                            + "the year bytes '2024' stay sound")
                    .isEqualTo(EditFlag.NOT_OK);
            assertThat(separatorShaped.dayFlag()).isEqualTo(EditFlag.NOT_OK);
            assertThat(separatorShaped.yearFlag()).isEqualTo(EditFlag.ISVALID);
            assertThat(separatorShaped.returnMessage().stripTrailing())
                    .as("and the message is the MONTH RANGE message, not a numeric one, because "
                            + "app/cpy/CSUTLDPY.cpy:L111 tests WS-VALID-MONTH through the numeric "
                            + "reinterpretation BEFORE NUMVAL runs at :L126-L129")
                    .isEqualTo(FIELD_LABEL + ": Month must be a number between 1 and 12.");
        }
    }

    /**
     * {@code THIS-CENTURY} and {@code LAST-CENTURY}, {@code app/cpy/CSUTLDWY.cpy:L9-L10}.
     */
    @Nested
    @DisplayName("the two century condition names, CSUTLDWY.cpy:L9-L10")
    class CenturyConditionNames {

        @ParameterizedTest
        @ValueSource(ints = {LAST_CENTURY, THIS_CENTURY})
        @DisplayName("only centuries 19 and 20 are accepted")
        void onlyNineteenAndTwentyAreAccepted(final int century) {
            assertThat(edit(date(century * 100 + 24, 6, 15)).yearFlag())
                    .as("app/cpy/CSUTLDWY.cpy:L9 THIS-CENTURY VALUE 20 and :L10 LAST-CENTURY VALUE 19 are "
                            + "the whole accepted domain, tested at app/cpy/CSUTLDPY.cpy:L70-L71. %s",
                            CENTURY_RATIONALE)
                    .isEqualTo(EditFlag.ISVALID);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 18, 21, 30, 99})
        @DisplayName("every other century is rejected, naming the century rather than the year")
        void everyOtherCenturyIsRejected(final int century) {
            final EditOutcome outcome = edit(date(century * 100 + 24, 6, 15));

            assertThat(outcome.yearFlag())
                    .as("app/cpy/CSUTLDPY.cpy:L74-L75 sets FLG-YEAR-NOT-OK - app/cpy/CSUTLDWY.cpy:L48 - "
                            + "for century %d, which is neither 19 nor 20", century)
                    .isEqualTo(EditFlag.NOT_OK);
            assertThat(outcome.returnMessage().stripTrailing())
                    .as("app/cpy/CSUTLDPY.cpy:L79 declares the literal; it names the century, not the year, "
                            + "and carries a space before its colon")
                    .isEqualTo(FIELD_LABEL + " : Century is not valid.");
            assertThat(outcome.valid()).isFalse();
        }

        @Test
        @DisplayName("swept exhaustively over all one hundred two-digit centuries, exactly two are accepted")
        void exactlyTwoCenturiesAreAcceptedOverTheWholeDomain() {
            final List<Integer> accepted = new ArrayList<>();
            for (int century = 0; century <= TWO_DIGIT_MAXIMUM; century++) {
                if (edit(date(century * 100 + 24, 6, 15)).yearFlag() == EditFlag.ISVALID) {
                    accepted.add(century);
                }
            }

            assertThat(accepted)
                    .as("the two conditions at app/cpy/CSUTLDWY.cpy:L9-L10 are mutually exclusive and "
                            + "jointly exhaustive over the accepted domain: no third century is admitted "
                            + "anywhere in the two-digit range, and neither condition admits the other's "
                            + "value. %s", CENTURY_RATIONALE)
                    .containsExactly(LAST_CENTURY, THIS_CENTURY);
        }

        @Test
        @DisplayName("the two condition names never hold together and never both fail on an accepted value")
        void theTwoConditionsAreMutuallyExclusive() {
            assertThat(LAST_CENTURY)
                    .as("app/cpy/CSUTLDWY.cpy:L9 and :L10 declare two distinct single values, so no century "
                            + "can satisfy both")
                    .isNotEqualTo(THIS_CENTURY);
            assertThat(THIS_CENTURY - LAST_CENTURY)
                    .as("and they are adjacent, which is why 18 and 21 are the informative rejections")
                    .isOne();
            assertThat(edit(date(1899, 12, 31)).yearFlag())
                    .as("18 sits immediately below the accepted pair and is still rejected")
                    .isEqualTo(EditFlag.NOT_OK);
            assertThat(edit(date(2199, 12, 31)).yearFlag())
                    .as("21 sits immediately above it and is rejected on the same guard")
                    .isEqualTo(EditFlag.NOT_OK);
            assertThat(edit(date(1999, 12, 31)).valid())
                    .as("while the last day of the last century is accepted in full")
                    .isTrue();
            assertThat(edit(date(2024, 6, 15)).valid())
                    .as("as is an ordinary date in this one")
                    .isTrue();
        }
    }

    /**
     * {@code WS-VALID-MONTH}, {@code WS-31-DAY-MONTH} and {@code WS-FEBRUARY},
     * {@code app/cpy/CSUTLDWY.cpy:L19-L24}.
     */
    @Nested
    @DisplayName("the three month condition names, CSUTLDWY.cpy:L19-L24")
    class MonthConditionNames {

        @ParameterizedTest
        @ValueSource(ints = {1, 2, 6, 11, 12})
        @DisplayName("months inside the declared range are accepted, both bounds included")
        void monthsInsideTheRangeAreAccepted(final int month) {
            assertThat(edit(date(2024, month, 15)).monthFlag())
                    .as("app/cpy/CSUTLDWY.cpy:L19-L20 WS-VALID-MONTH VALUES 1 THROUGH 12 is inclusive at "
                            + "both ends, so month %d passes", month)
                    .isEqualTo(EditFlag.ISVALID);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 13, 99})
        @DisplayName("months outside the declared range are rejected, zero and thirteen included")
        void monthsOutsideTheRangeAreRejected(final int month) {
            final EditOutcome outcome = edit(date(2024, month, 15));

            assertThat(outcome.monthFlag())
                    .as("app/cpy/CSUTLDPY.cpy:L111 tests WS-VALID-MONTH and :L115 sets FLG-MONTH-NOT-OK - "
                            + "app/cpy/CSUTLDWY.cpy:L52 - for month %d. Zero and thirteen are the two "
                            + "boundary probes an inclusive range must reject", month)
                    .isEqualTo(EditFlag.NOT_OK);
            assertThat(outcome.returnMessage().stripTrailing())
                    .as("app/cpy/CSUTLDPY.cpy:L119 declares the literal with a bare colon and a capital M")
                    .isEqualTo(FIELD_LABEL + ": Month must be a number between 1 and 12.");
        }

        @Test
        @DisplayName("swept exhaustively over all one hundred two-digit months, exactly 1 through 12 pass")
        void exactlyOneThroughTwelveAreAcceptedOverTheWholeDomain() {
            final List<Integer> accepted = new ArrayList<>();
            for (int month = 0; month <= TWO_DIGIT_MAXIMUM; month++) {
                if (edit(date(2024, month, 15)).monthFlag() == EditFlag.ISVALID) {
                    accepted.add(month);
                }
            }

            assertThat(accepted)
                    .as("app/cpy/CSUTLDWY.cpy:L19-L20 declares a closed interval, and sweeping the whole "
                            + "two-digit domain proves it is exactly that interval - neither wider nor "
                            + "narrower, and with no gaps inside it")
                    .containsExactlyElementsOf(closedRange(1, 12));
        }

        @Test
        @DisplayName("HIGH: the thirty-one-day months are a SET of seven, swept exhaustively over 0 to 13")
        void theThirtyOneDayMonthsAreASetOfSeven() {
            final List<Integer> acceptTheThirtyFirst = new ArrayList<>();
            final List<Integer> rejectTheThirtyFirst = new ArrayList<>();
            for (int month = 0; month <= 13; month++) {
                if (edit(date(2024, month, 31)).valid()) {
                    acceptTheThirtyFirst.add(month);
                } else {
                    rejectTheThirtyFirst.add(month);
                }
            }

            assertThat(acceptTheThirtyFirst)
                    .as("app/cpy/CSUTLDWY.cpy:L21-L23 declares WS-31-DAY-MONTH VALUES 1, 3, 5, 7, 8, 10, 12 "
                            + "- a SET WITH FIVE INTERIOR GAPS, not a range. HIGH: an implementation that "
                            + "used a range would pass any spot check and fail this sweep")
                    .containsExactlyElementsOf(THIRTY_ONE_DAY_MONTHS)
                    .hasSize(7);

            assertThat(rejectTheThirtyFirst)
                    .as("and the complement is exact: the five shorter months of app/cpy/CSUTLDPY.cpy:"
                            + "L213-L226 plus the two out-of-range probes that never reach that check")
                    .containsExactly(0, 2, 4, 6, 9, 11, 13);
            assertThat(rejectTheThirtyFirst).containsAll(SHORTER_MONTHS);
            assertThat(THIRTY_ONE_DAY_MONTHS)
                    .as("the set and its complement partition the twelve valid months with no overlap")
                    .doesNotContainAnyElementsOf(SHORTER_MONTHS);
            assertThat(THIRTY_ONE_DAY_MONTHS.size() + SHORTER_MONTHS.size()).isEqualTo(12);
        }

        @Test
        @DisplayName("a thirty-first day in a shorter month implicates the month AND the day, not just one")
        void aThirtyFirstDayInAShorterMonthImplicatesBoth() {
            for (final int month : SHORTER_MONTHS) {
                final EditOutcome outcome = edit(date(2024, month, 31));

                assertThat(outcome.monthFlag())
                        .as("app/cpy/CSUTLDPY.cpy:L216-L217 sets FLG-DAY-NOT-OK AND FLG-MONTH-NOT-OK "
                                + "together, because neither component is wrong on its own - month %d",
                                month)
                        .isEqualTo(EditFlag.NOT_OK);
                assertThat(outcome.dayFlag()).isEqualTo(EditFlag.NOT_OK);
                assertThat(outcome.yearFlag())
                        .as("the year is untouched by the combination check, so this is NOT the "
                                + "all-components state")
                        .isEqualTo(EditFlag.ISVALID);
                assertThat(outcome.allComponentsNotOk())
                        .as("which is exactly why WS-EDIT-DATE-IS-INVALID at app/cpy/CSUTLDWY.cpy:L45 stays "
                                + "false here")
                        .isFalse();
                assertThat(outcome.returnMessage().stripTrailing())
                        .as("app/cpy/CSUTLDPY.cpy:L221 declares the literal")
                        .isEqualTo(FIELD_LABEL + ":Cannot have 31 days in this month.");
            }
        }

        @Test
        @DisplayName("an out-of-range month reports the range failure, not the thirty-one-day set failure")
        void anOutOfRangeMonthReportsTheRangeFailure() {
            for (final int month : List.of(0, 13)) {
                assertThat(edit(date(2024, month, 31)).returnMessage().stripTrailing())
                        .as("app/cpy/CSUTLDPY.cpy:L111 runs before :L213, so the range verdict is the one "
                                + "latched into the message for month %d even though the day is also 31",
                                month)
                        .isEqualTo(FIELD_LABEL + ": Month must be a number between 1 and 12.");
            }
        }

        @Test
        @DisplayName("WS-FEBRUARY is the single value 2, and only it opens the February branches")
        void februaryIsTheSingleValueTwo() {
            assertThat(FEBRUARY)
                    .as("app/cpy/CSUTLDWY.cpy:L24 declares WS-FEBRUARY VALUE 2 - one value, not a range")
                    .isEqualTo(2);

            assertThat(edit(date(NON_LEAP_YEAR, FEBRUARY, 30)).returnMessage().stripTrailing())
                    .as("app/cpy/CSUTLDPY.cpy:L228-L229 gates the thirty-day rejection on WS-FEBRUARY AND "
                            + "WS-DAY-30 together")
                    .isEqualTo(FIELD_LABEL + ":Cannot have 30 days in this month.");
            assertThat(edit(date(NON_LEAP_YEAR, FEBRUARY, 29)).returnMessage().stripTrailing())
                    .as("app/cpy/CSUTLDPY.cpy:L266 declares the leap-year literal, which runs two sentences "
                            + "together with no space after the full stop")
                    .isEqualTo(FIELD_LABEL + ":Not a leap year.Cannot have 29 days in this month.");

            for (final int otherMonth : List.of(1, 3, 4, 12)) {
                assertThat(edit(date(NON_LEAP_YEAR, otherMonth, 29)).valid())
                        .as("month %d is not WS-FEBRUARY, so the leap-year branch never opens and the 29th "
                                + "is accepted with no year arithmetic at all", otherMonth)
                        .isTrue();
            }
            assertThat(edit(date(NON_LEAP_YEAR, 4, 30)).valid())
                    .as("and April accepts a thirtieth, so the thirty-day rejection really is February-only")
                    .isTrue();
        }
    }

    /**
     * {@code WS-VALID-DAY}, the three day singletons and {@code WS-VALID-FEB-DAY},
     * {@code app/cpy/CSUTLDWY.cpy:L28-L34}.
     */
    @Nested
    @DisplayName("the five day condition names, CSUTLDWY.cpy:L28-L34")
    class DayConditionNames {

        /** A month with thirty-one days, so that the day sweep is bounded only by {@code WS-VALID-DAY}. */
        private static final int LONG_MONTH = 1;

        @ParameterizedTest
        @ValueSource(ints = {1, 28, 29, 30, 31})
        @DisplayName("days inside the declared range are accepted, both bounds included")
        void daysInsideTheRangeAreAccepted(final int day) {
            assertThat(edit(date(2024, LONG_MONTH, day)).dayFlag())
                    .as("app/cpy/CSUTLDWY.cpy:L28-L29 WS-VALID-DAY VALUES 1 THROUGH 31 is inclusive at both "
                            + "ends, so day %d passes in a thirty-one-day month", day)
                    .isEqualTo(EditFlag.ISVALID);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 32, 99})
        @DisplayName("days outside the declared range are rejected, zero and thirty-two included")
        void daysOutsideTheRangeAreRejected(final int day) {
            final EditOutcome outcome = edit(date(2024, LONG_MONTH, day));

            assertThat(outcome.dayFlag())
                    .as("app/cpy/CSUTLDPY.cpy:L187 tests WS-VALID-DAY and :L191 sets FLG-DAY-NOT-OK - "
                            + "app/cpy/CSUTLDWY.cpy:L56 - for day %d. Zero and thirty-two are the two "
                            + "boundary probes an inclusive range must reject", day)
                    .isEqualTo(EditFlag.NOT_OK);
            assertThat(outcome.returnMessage().stripTrailing())
                    .as("PRESERVED INCONSISTENCY: app/cpy/CSUTLDPY.cpy:L195 declares the literal with a bare "
                            + "colon and a LOWERCASE d, unlike either of the other two edit messages. "
                            + "Normalising it would change text the screens display")
                    .isEqualTo(FIELD_LABEL + ":day must be a number between 1 and 31.");
        }

        @Test
        @DisplayName("swept exhaustively over all one hundred two-digit days, exactly 1 through 31 pass")
        void exactlyOneThroughThirtyOneAreAcceptedInALongMonth() {
            assertThat(acceptedOver(day -> date(2024, LONG_MONTH, day)))
                    .as("app/cpy/CSUTLDWY.cpy:L28-L29 declares a closed interval, and in a "
                            + "thirty-one-day month it is the only bound that applies, so the sweep must "
                            + "return exactly that interval with no gaps")
                    .containsExactlyElementsOf(closedRange(1, 31));
        }

        @Test
        @DisplayName("swept in a thirty-day month, the range is narrowed by the combination check to 1 to 30")
        void aThirtyDayMonthIsNarrowedToThirty() {
            assertThat(acceptedOver(day -> date(2024, 4, day)))
                    .as("WS-VALID-DAY still admits 31 at app/cpy/CSUTLDWY.cpy:L28-L29; it is the "
                            + "combination check at app/cpy/CSUTLDPY.cpy:L213-L226 that removes it, which is "
                            + "why the two conditions are declared separately")
                    .containsExactlyElementsOf(closedRange(1, 30));
        }

        @Test
        @DisplayName("the three day singletons each hold for exactly one value and for no other")
        void theThreeDaySingletonsHoldForExactlyOneValue() {
            assertThat(edit(date(NON_LEAP_YEAR, FEBRUARY, 31)).returnMessage().stripTrailing())
                    .as("app/cpy/CSUTLDWY.cpy:L30 WS-DAY-31 VALUE 31 is the only day that opens the "
                            + "thirty-one-day rejection at app/cpy/CSUTLDPY.cpy:L214")
                    .isEqualTo(FIELD_LABEL + ":Cannot have 31 days in this month.");
            assertThat(edit(date(NON_LEAP_YEAR, FEBRUARY, 30)).returnMessage().stripTrailing())
                    .as("app/cpy/CSUTLDWY.cpy:L31 WS-DAY-30 VALUE 30 opens a different rejection at "
                            + "app/cpy/CSUTLDPY.cpy:L229, so the two singletons are not interchangeable")
                    .isEqualTo(FIELD_LABEL + ":Cannot have 30 days in this month.");
            assertThat(edit(date(NON_LEAP_YEAR, FEBRUARY, 29)).returnMessage().stripTrailing())
                    .as("app/cpy/CSUTLDWY.cpy:L32 WS-DAY-29 VALUE 29 opens the leap-year branch at "
                            + "app/cpy/CSUTLDPY.cpy:L244, which is a third and distinct outcome")
                    .isEqualTo(FIELD_LABEL + ":Not a leap year.Cannot have 29 days in this month.");
            assertThat(edit(date(NON_LEAP_YEAR, FEBRUARY, 28)).valid())
                    .as("and 28 satisfies none of the three singletons, so no branch opens at all")
                    .isTrue();

            assertThat(List.of(31, 30, 29))
                    .as("the three declared values are pairwise distinct, so no day can satisfy two of them")
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("HIGH: WS-VALID-FEB-DAY stops at 28, and the 29th reaches February only through the "
                + "leap-year branch")
        void validFebruaryDayStopsAtTwentyEight() {
            assertThat(DateValidationService.VALID_FEBRUARY_DAY_MAXIMUM)
                    .as("app/cpy/CSUTLDWY.cpy:L33-L34 declares WS-VALID-FEB-DAY VALUES 1 THROUGH 28. HIGH: "
                            + "widening it to 29 would bypass the leap-year arithmetic at "
                            + "app/cpy/CSUTLDPY.cpy:L243-L272 entirely and accept 29 February in every year")
                    .isEqualTo(28)
                    .isNotEqualTo(29);

            assertThat(acceptedOver(day -> date(NON_LEAP_YEAR, FEBRUARY, day)))
                    .as("swept over the whole two-digit domain in a NON-leap year, February accepts exactly "
                            + "the declared 1 THROUGH 28 - the 29th is refused because the divisor "
                            + "arithmetic at app/cpy/CSUTLDPY.cpy:L251-L256 leaves a remainder")
                    .containsExactlyElementsOf(closedRange(1, DateValidationService
                            .VALID_FEBRUARY_DAY_MAXIMUM));

            assertThat(acceptedOver(day -> date(LEAP_YEAR, FEBRUARY, day)))
                    .as("and swept in a LEAP year it accepts exactly one more day. That single extra day is "
                            + "the whole proof: it is admitted by the leap branch and NOT by "
                            + "WS-VALID-FEB-DAY, so the declared upper bound of 28 is doing real work")
                    .containsExactlyElementsOf(closedRange(1, 29))
                    .hasSize(DateValidationService.VALID_FEBRUARY_DAY_MAXIMUM + 1);
        }

        @Test
        @DisplayName("the leap-year divisor switches to four hundred when the two year digits are zero")
        void theLeapDivisorSwitchesOnAZeroYear() {
            assertThat(edit(date(2000, FEBRUARY, 29)).valid())
                    .as("app/cpy/CSUTLDPY.cpy:L245-L249 moves 400 into the divisor when WS-EDIT-DATE-YY-N - "
                            + "app/cpy/CSUTLDWY.cpy:L12-L13 - is zero, and 2000 divides by 400 exactly")
                    .isTrue();

            final EditOutcome nineteenHundred = edit(date(1900, FEBRUARY, 29));
            assertThat(nineteenHundred.valid())
                    .as("while 1900 has the same zero year digits, an accepted century and a remainder of "
                            + "300, so the same branch refuses it")
                    .isFalse();
            assertThat(nineteenHundred.allComponentsNotOk())
                    .as("app/cpy/CSUTLDPY.cpy:L260-L262 sets all three flags, because the verdict depends "
                            + "on the day, the month and the year read together")
                    .isTrue();
        }
    }

    /**
     * {@code WS-EDIT-DATE-FLGS}, {@code app/cpy/CSUTLDWY.cpy:L43-L57}: a three-byte group whose two
     * group-level condition names read all three bytes at once.
     */
    @Nested
    @DisplayName("the three-byte tri-state flag group and its group-level conditions, CSUTLDWY.cpy:L43-L57")
    class EditFlagGroupTriStateAlphabet {

        /** A blank return message of the width the source declares, used to build outcomes directly. */
        private static final String NO_MESSAGE = " ".repeat(DateValidationService.RETURN_MESSAGE_LENGTH);

        /**
         * Builds an outcome from three flags, so that the group-level conditions can be examined over the
         * whole alphabet rather than only over the states an edit happens to produce.
         *
         * @param year the year flag
         * @param month the month flag
         * @param day the day flag
         * @return the outcome carrying exactly those three flags and no message
         */
        private static EditOutcome outcome(final EditFlag year, final EditFlag month, final EditFlag day) {
            return new EditOutcome(year, month, day, false, NO_MESSAGE);
        }

        @Test
        @DisplayName("the group is exactly three bytes, one for each component")
        void theGroupIsExactlyThreeBytes() {
            assertThat(occupiedWidth(FLAG_GROUP))
                    .as("app/cpy/CSUTLDWY.cpy:L43-L57 declares three PIC X(01) items beneath one group, "
                            + "which is why the group condition at :L45 can be written as the "
                            + "three-character literal '000'")
                    .isEqualTo(FLAG_GROUP_WIDTH)
                    .isEqualTo(3);
            assertThat(FLAG_GROUP).hasSize(3);
            assertThat(FLAG_GROUP)
                    .as("every sub-field is exactly one byte, so no component can carry a two-character "
                            + "state")
                    .allSatisfy(declaration -> assertThat(declaration.width()).isEqualTo(1));
        }

        @Test
        @DisplayName("MEDIUM: the three sub-fields are declared at level 20, and the source governs")
        void theSubFieldsAreDeclaredAtLevelTwenty() {
            assertThat(FLAG_GROUP)
                    .as("MEDIUM, CITATION DRIFT. app/cpy/CSUTLDWY.cpy:L46, :L50 and :L54 read level 20. A "
                            + "downstream citation in this migration renders them as level 15; the source is "
                            + "the parity oracle and governs. Remedy: cite the copybook, not the summary")
                    .allSatisfy(declaration -> assertThat(declaration.level()).isEqualTo(20));
        }

        @ParameterizedTest
        @EnumSource(EditFlag.class)
        @DisplayName("every one of the three declared states is distinguishable on every component")
        void everyStateIsDistinguishableOnEveryComponent(final EditFlag state) {
            assertThat(outcome(state, EditFlag.ISVALID, EditFlag.ISVALID).yearFlag())
                    .as("app/cpy/CSUTLDWY.cpy:L47-L49 declares three condition names over one byte, so the "
                            + "year flag must retain %s distinctly", state)
                    .isEqualTo(state);
            assertThat(outcome(EditFlag.ISVALID, state, EditFlag.ISVALID).monthFlag())
                    .as("app/cpy/CSUTLDWY.cpy:L51-L53 declares the same three over the month byte")
                    .isEqualTo(state);
            assertThat(outcome(EditFlag.ISVALID, EditFlag.ISVALID, state).dayFlag())
                    .as("app/cpy/CSUTLDWY.cpy:L55-L57 declares the same three over the day byte")
                    .isEqualTo(state);
        }

        @Test
        @DisplayName("HIGH: the alphabet has three states, so no boolean can carry it")
        void theAlphabetHasThreeStates() {
            assertThat(EditFlag.values())
                    .as("app/cpy/CSUTLDWY.cpy:L47-L49 declares LOW-VALUES for valid, '0' for not ok and 'B' "
                            + "for blank. HIGH: a boolean carries two states, so collapsing the alphabet "
                            + "loses one - and the one it loses is reachable, as the neither-state "
                            + "assertions below show. Remedy: keep the three-valued enum")
                    .hasSize(3)
                    .containsOnlyOnce(EditFlag.ISVALID, EditFlag.NOT_OK, EditFlag.BLANK);
        }

        @Test
        @DisplayName("all nine component-and-state combinations are reachable through a real edit")
        void allNineComponentStateCombinationsAreReachable() {
            assertThat(edit(date(2024, 6, 15)).yearFlag()).isEqualTo(EditFlag.ISVALID);
            assertThat(edit(date(1824, 6, 15)).yearFlag())
                    .as("a rejected century reaches FLG-YEAR-NOT-OK, app/cpy/CSUTLDWY.cpy:L48")
                    .isEqualTo(EditFlag.NOT_OK);
            assertThat(edit("    0615").yearFlag())
                    .as("an unsupplied year reaches FLG-YEAR-BLANK, app/cpy/CSUTLDWY.cpy:L49")
                    .isEqualTo(EditFlag.BLANK);

            assertThat(edit(date(2024, 6, 15)).monthFlag()).isEqualTo(EditFlag.ISVALID);
            assertThat(edit(date(2024, 0, 15)).monthFlag())
                    .as("an out-of-range month reaches FLG-MONTH-NOT-OK, app/cpy/CSUTLDWY.cpy:L52")
                    .isEqualTo(EditFlag.NOT_OK);
            assertThat(edit("2024  15").monthFlag())
                    .as("an unsupplied month reaches FLG-MONTH-BLANK, app/cpy/CSUTLDWY.cpy:L53")
                    .isEqualTo(EditFlag.BLANK);

            assertThat(edit(date(2024, 6, 15)).dayFlag()).isEqualTo(EditFlag.ISVALID);
            assertThat(edit(date(2024, 6, 32)).dayFlag())
                    .as("an out-of-range day reaches FLG-DAY-NOT-OK, app/cpy/CSUTLDWY.cpy:L56")
                    .isEqualTo(EditFlag.NOT_OK);
            assertThat(edit("202406  ").dayFlag())
                    .as("an unsupplied day reaches FLG-DAY-BLANK, app/cpy/CSUTLDWY.cpy:L57")
                    .isEqualTo(EditFlag.BLANK);
        }

        @Test
        @DisplayName("the group-level valid condition is a three-way conjunction of LOW-VALUES bytes")
        void theGroupValidConditionIsAThreeWayConjunction() {
            assertThat(outcome(EditFlag.ISVALID, EditFlag.ISVALID, EditFlag.ISVALID).valid())
                    .as("app/cpy/CSUTLDWY.cpy:L44 declares WS-EDIT-DATE-IS-VALID VALUE LOW-VALUES over the "
                            + "GROUP, so it holds only when all three bytes are LOW-VALUES. This is the gate "
                            + "at app/cpy/CSUTLDPY.cpy:L274 that decides whether the Language Environment "
                            + "layer runs at all, and app/cpy/CSUTLDPY.cpy:L327 is what sets it")
                    .isTrue();

            for (final EditFlag dirty : List.of(EditFlag.NOT_OK, EditFlag.BLANK)) {
                assertThat(outcome(dirty, EditFlag.ISVALID, EditFlag.ISVALID).valid())
                        .as("a single dirty YEAR byte of %s breaks the conjunction", dirty)
                        .isFalse();
                assertThat(outcome(EditFlag.ISVALID, dirty, EditFlag.ISVALID).valid())
                        .as("a single dirty MONTH byte of %s breaks it too", dirty)
                        .isFalse();
                assertThat(outcome(EditFlag.ISVALID, EditFlag.ISVALID, dirty).valid())
                        .as("as does a single dirty DAY byte of %s", dirty)
                        .isFalse();
            }

            assertThat(outcome(EditFlag.NOT_OK, EditFlag.BLANK, EditFlag.NOT_OK).valid())
                    .as("and a multi-byte dirty group is no closer to satisfying it")
                    .isFalse();
        }

        @Test
        @DisplayName("the group-level invalid condition requires all three bytes to be the '0' state")
        void theGroupInvalidConditionRequiresAllThreeBytes() {
            assertThat(outcome(EditFlag.NOT_OK, EditFlag.NOT_OK, EditFlag.NOT_OK).allComponentsNotOk())
                    .as("app/cpy/CSUTLDWY.cpy:L45 declares WS-EDIT-DATE-IS-INVALID VALUE '000' over the "
                            + "GROUP, so writing it sets all three bytes to '0' - which is exactly what the "
                            + "single statement at app/cpy/CSUTLDPY.cpy:L18-L20 does to seed the group "
                            + "pessimistically on entry")
                    .isTrue();

            assertThat(outcome(EditFlag.ISVALID, EditFlag.NOT_OK, EditFlag.NOT_OK).allComponentsNotOk())
                    .as("two of three is not '000'")
                    .isFalse();
            assertThat(outcome(EditFlag.BLANK, EditFlag.NOT_OK, EditFlag.NOT_OK).allComponentsNotOk())
                    .as("and a 'B' in any position is not a '0' either, however invalid the date is")
                    .isFalse();
        }

        @Test
        @DisplayName("HIGH: the two group conditions are not complements - twenty-five states satisfy "
                + "neither")
        void theTwoGroupConditionsAreNotComplements() {
            final List<String> neither = new ArrayList<>();
            int validStates = 0;
            int invalidStates = 0;
            for (final EditFlag year : EditFlag.values()) {
                for (final EditFlag month : EditFlag.values()) {
                    for (final EditFlag day : EditFlag.values()) {
                        final EditOutcome candidate = outcome(year, month, day);
                        if (candidate.valid()) {
                            validStates++;
                        }
                        if (candidate.allComponentsNotOk()) {
                            invalidStates++;
                        }
                        if (!candidate.valid() && !candidate.allComponentsNotOk()) {
                            neither.add(year + "/" + month + "/" + day);
                        }
                    }
                }
            }

            assertThat(validStates)
                    .as("of the twenty-seven states the three-byte alphabet at app/cpy/CSUTLDWY.cpy:L43-L57 "
                            + "admits, exactly one is LOW-VALUES throughout and so satisfies :L44")
                    .isOne();
            assertThat(invalidStates)
                    .as("and exactly one is '000' throughout and so satisfies :L45")
                    .isOne();
            assertThat(neither)
                    .as("HIGH: which leaves twenty-five reachable states that satisfy NEITHER condition. "
                            + "The pair is therefore not a logical negation, and an implementation that "
                            + "modelled it as one boolean would have to invent a verdict for every one of "
                            + "these. Remedy: keep the two conditions independent")
                    .hasSize(25);
            assertThat(validStates + invalidStates + neither.size()).isEqualTo(27);
        }

        @Test
        @DisplayName("HIGH: the prompt's own example, year '0' month 'B' day '0', satisfies neither")
        void theCanonicalNeitherStateSatisfiesNeitherCondition() {
            final EditOutcome mixed = outcome(EditFlag.NOT_OK, EditFlag.BLANK, EditFlag.NOT_OK);

            assertThat(mixed.valid())
                    .as("the bytes are '0', 'B', '0', which is not LOW-VALUES, so app/cpy/CSUTLDWY.cpy:L44 "
                            + "does not hold")
                    .isFalse();
            assertThat(mixed.allComponentsNotOk())
                    .as("and it is not the literal '000' either, because the middle byte is 'B', so "
                            + "app/cpy/CSUTLDWY.cpy:L45 does not hold. HIGH: this state exists, it is "
                            + "reachable, and it belongs to neither condition")
                    .isFalse();
        }

        @Test
        @DisplayName("the source reaches a neither-state on its own blank-month path")
        void theSourceReachesANeitherStateOnItsBlankMonthPath() {
            final EditOutcome blankMonth = edit("2024  15");

            assertThat(blankMonth.monthFlag())
                    .as("app/cpy/CSUTLDPY.cpy:L94-L97 sets FLG-MONTH-BLANK when the month is not supplied, "
                            + "leaving the sound year and day flags untouched")
                    .isEqualTo(EditFlag.BLANK);
            assertThat(blankMonth.yearFlag()).isEqualTo(EditFlag.ISVALID);
            assertThat(blankMonth.dayFlag()).isEqualTo(EditFlag.ISVALID);
            assertThat(blankMonth.valid())
                    .as("so the group is not LOW-VALUES throughout")
                    .isFalse();
            assertThat(blankMonth.allComponentsNotOk())
                    .as("and it is not '000' throughout either - the neither-state is not a theoretical "
                            + "corner, it is what the source produces every time a month is left blank")
                    .isFalse();
        }

        @Test
        @DisplayName("HIGH: the flag bytes are distinct from the data fields and from the paragraph labels")
        void theFlagBytesAreDistinctFromTheDataFieldsAndParagraphLabels() {
            final List<String> flagNames = new ArrayList<>();
            for (final Declaration declaration : FLAG_GROUP) {
                flagNames.add(declaration.name());
            }
            final List<String> dataNames = new ArrayList<>();
            for (final Declaration declaration : EDIT_DATE_GROUP) {
                dataNames.add(declaration.name());
            }

            assertThat(flagNames)
                    .as("app/cpy/CSUTLDWY.cpy:L46, :L50 and :L54 name the three flag bytes")
                    .containsExactly("WS-EDIT-YEAR-FLG", "WS-EDIT-MONTH", "WS-EDIT-DAY");
            assertThat(dataNames)
                    .as("HIGH: WS-EDIT-MONTH at :L50 is a FLAG BYTE while WS-EDIT-DATE-MM at :L16 is the "
                            + "DATA FIELD, and WS-EDIT-DAY at :L54 likewise against WS-EDIT-DATE-DD at :L25. "
                            + "The two ladders share no name at all, and a reader who conflates them will "
                            + "set the wrong thing. Remedy: never abbreviate either name when citing it")
                    .doesNotContainAnyElementsOf(flagNames);
            assertThat(dataNames).contains("WS-EDIT-DATE-MM", "WS-EDIT-DATE-DD");

            assertThat(flagNames)
                    .as("HIGH: EDIT-MONTH at app/cpy/CSUTLDPY.cpy:L91 and EDIT-DAY at :L150 are PARAGRAPH "
                            + "LABELS, one WS- prefix away from the flag bytes that those very paragraphs "
                            + "set. Remedy: cite paragraphs with their file and line, never by bare name")
                    .doesNotContain("EDIT-MONTH", "EDIT-DAY");
            assertThat(flagNames)
                    .as("and all three flag names are distinct from one another")
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("LOW: only the year flag carries a -FLG suffix, which is recorded and not corrected")
        void onlyTheYearFlagCarriesTheFlgSuffix() {
            assertThat(FLAG_GROUP.get(0).name())
                    .as("app/cpy/CSUTLDWY.cpy:L46 spells the year flag with a -FLG suffix")
                    .endsWith("-FLG");
            assertThat(FLAG_GROUP.get(1).name())
                    .as("LOW, PRESERVED INCONSISTENCY: app/cpy/CSUTLDWY.cpy:L50 does not. Remedy: none - "
                            + "the copybook is frozen and is the parity oracle, so this assertion is the "
                            + "tracking reference rather than an invitation to rename anything")
                    .doesNotEndWith("-FLG");
            assertThat(FLAG_GROUP.get(2).name())
                    .as("nor does app/cpy/CSUTLDWY.cpy:L54")
                    .doesNotEndWith("-FLG");
        }

        @Test
        @DisplayName("no component of the outcome may be absent, and each refusal names its own component")
        void noComponentOfTheOutcomeMayBeAbsent() {
            assertThatNullPointerException()
                    .as("WS-EDIT-YEAR-FLG at app/cpy/CSUTLDWY.cpy:L46 is one declared byte that always "
                            + "holds one of its three 88-level states at :L47-L49, so an ABSENT year flag "
                            + "is a wiring defect rather than a date that failed an edit, and it is refused "
                            + "at construction rather than silently defaulted")
                    .isThrownBy(() -> new EditOutcome(null, EditFlag.ISVALID, EditFlag.ISVALID, false,
                            NO_MESSAGE))
                    .withMessage("yearFlag must not be null")
                    .withNoCause();
            assertThatNullPointerException()
                    .isThrownBy(() -> new EditOutcome(EditFlag.ISVALID, null, EditFlag.ISVALID, false,
                            NO_MESSAGE))
                    .withMessage("monthFlag must not be null")
                    .withNoCause();
            assertThatNullPointerException()
                    .isThrownBy(() -> new EditOutcome(EditFlag.ISVALID, EditFlag.ISVALID, null, false,
                            NO_MESSAGE))
                    .withMessage("dayFlag must not be null")
                    .withNoCause();
            assertThatNullPointerException()
                    .as("and an absent message is refused too, because app/cpy/CSUTLDPY.cpy holds it at a "
                            + "fixed width of spaces rather than as nothing at all")
                    .isThrownBy(() -> new EditOutcome(EditFlag.ISVALID, EditFlag.ISVALID, EditFlag.ISVALID,
                            false, null))
                    .withMessage("returnMessage must not be null")
                    .withNoCause();
        }

        @Test
        @DisplayName("an empty message is the no-message state, which is distinct from an absent one")
        void anEmptyMessageIsTheNoMessageState() {
            assertThat(outcome(EditFlag.ISVALID, EditFlag.ISVALID, EditFlag.ISVALID).hasReturnMessage())
                    .as("a group of spaces at the declared width carries no message, which is the state "
                            + "app/cpy/CSUTLDPY.cpy:L327 leaves behind when it sets WS-EDIT-DATE-IS-VALID "
                            + "at app/cpy/CSUTLDWY.cpy:L44 after a clean edit")
                    .isFalse();
            assertThat(new EditOutcome(EditFlag.ISVALID, EditFlag.ISVALID, EditFlag.ISVALID, false, "")
                    .hasReturnMessage())
                    .as("and an empty string is treated as the same no-message state rather than as a "
                            + "zero-length message")
                    .isFalse();
            assertThat(edit(date(1824, 6, 15)).hasReturnMessage())
                    .as("while a real rejection does carry one")
                    .isTrue();
        }
    }

    /**
     * The two masks and the eighty-byte result-area declaration, {@code app/cpy/CSUTLDWY.cpy:L58-L85}, and
     * the twin that {@code app/cbl/CSUTLDTC.cbl:L42-L57} declares of the same layout.
     *
     * <p>This group asserts the <em>declaration</em>: the width ladder, the offsets derived from it, the
     * literal-against-width padding arithmetic and the twin comparison. The rendered content at literal
     * offsets is owned by the sibling {@code DateValidationServiceTest} and is not re-asserted here beyond
     * the single cross-check that anchors the derived offsets to reality.
     */
    @Nested
    @DisplayName("the masks and the eighty-byte result-area declaration, CSUTLDWY.cpy:L58-L85")
    class MaskAndResultAreaDeclaration {

        /** The declared width of the {@code WS-DATE-FMT} field the mask is rendered into, {@code :L81}. */
        private static final int MASK_FIELD_WIDTH = 10;

        /**
         * The offset at which the ladder item at {@code index} begins, computed from the widths that
         * precede it rather than written down, so that the ladder and the offsets cannot drift apart.
         *
         * <p>The item is addressed by position and its name is asserted rather than searched for. Searching
         * by name would be the wrong mechanism here: the ladder declares eight items named {@code FILLER},
         * three of which are the single spaces at {@code app/cpy/CSUTLDWY.cpy:L69-L70}, {@code :L72-L73}
         * and {@code :L77-L78}, so an ordinal count of {@code FILLER} occurrences puts
         * {@code 'TstDate:'} fourth and {@code 'Mask used:'} sixth. Addressing by position and asserting
         * the name keeps that miscount impossible.
         *
         * @param ladder the declarations in source order
         * @param index the position of the item in the ladder
         * @param expectedName the data name the item at that position must carry
         * @return the zero-based byte offset of that item
         */
        private static int offsetOf(final List<Declaration> ladder, final int index,
                                    final String expectedName) {
            assertThat(ladder.get(index).name())
                    .as("item %d of the result-area ladder must still be %s, or the ladder no longer "
                            + "models app/cpy/CSUTLDWY.cpy:L60-L85", index, expectedName)
                    .isEqualTo(expectedName);
            assertThat(ladder.get(index).redefines())
                    .as("and it must occupy storage of its own rather than redefine an earlier item, or it "
                            + "has no offset to derive")
                    .isFalse();
            return occupiedWidth(ladder.subList(0, index));
        }

        @Test
        @DisplayName("HIGH: the copybook mask is eight characters and the program mask is ten - never one "
                + "constant")
        void theTwoMasksAreDistinctInWidthAndSpelling() {
            assertThat(DateValidationService.MASK_YYYYMMDD)
                    .as("app/cpy/CSUTLDWY.cpy:L58-L59 declares WS-DATE-FORMAT PIC X(08) VALUE 'YYYYMMDD', "
                            + "which is the mask app/cpy/CSUTLDPY.cpy:L291 moves in on the copybook path")
                    .isEqualTo("YYYYMMDD")
                    .hasSize(8)
                    .doesNotContain("-");
            assertThat(DateValidationService.MASK_YYYY_MM_DD)
                    .as("while app/cbl/CORPT00C.cbl:L72 and app/cbl/COTRN02C.cbl:L60 each declare a "
                            + "PIC X(10) VALUE 'YYYY-MM-DD' of their own for the program path")
                    .isEqualTo("YYYY-MM-DD")
                    .hasSize(10)
                    .contains("-");

            assertThat(DateValidationService.MASK_YYYYMMDD.length())
                    .as("HIGH: eight against ten. Conflating the two changes what the date service is asked "
                            + "to parse, and a ten-character mask over an eight-character field is exactly "
                            + "the shape that produces the overread. Remedy: keep two named constants")
                    .isNotEqualTo(DateValidationService.MASK_YYYY_MM_DD.length());
            assertThat(DateValidationService.MASK_YYYYMMDD)
                    .isNotEqualTo(DateValidationService.MASK_YYYY_MM_DD);
        }

        @Test
        @DisplayName("both masks fit the ten-byte mask field the result area declares")
        void bothMasksFitTheDeclaredMaskField() {
            final int declared = RESULT_AREA.get(12).width();

            assertThat(RESULT_AREA.get(12).name())
                    .as("app/cpy/CSUTLDWY.cpy:L81 declares WS-DATE-FMT, the field the mask is echoed into")
                    .isEqualTo("WS-DATE-FMT");
            assertThat(declared)
                    .as("app/cpy/CSUTLDWY.cpy:L81 declares it PIC X(10), matching LS-DATE-FORMAT PIC X(10) "
                            + "at app/cbl/CSUTLDTC.cbl:L85")
                    .isEqualTo(MASK_FIELD_WIDTH)
                    .isEqualTo(DateValidationService.LS_DATE_FORMAT_LENGTH);
            assertThat(DateValidationService.MASK_YYYYMMDD.length()).isLessThanOrEqualTo(declared);
            assertThat(DateValidationService.MASK_YYYY_MM_DD.length())
                    .as("the wider mask fits exactly, which is why the field is ten and not eight")
                    .isEqualTo(declared);
        }

        @Test
        @DisplayName("the ladder is fifteen declarations of which thirteen occupy storage")
        void theLadderIsFifteenDeclarationsOfWhichThirteenOccupyStorage() {
            assertThat(RESULT_AREA)
                    .as("app/cpy/CSUTLDWY.cpy:L60-L85 declares fifteen items beneath WS-DATE-VALIDATION-"
                            + "RESULT")
                    .hasSize(15);
            assertThat(RESULT_AREA.stream().filter(Declaration::redefines).count())
                    .as("two of them - WS-SEVERITY-N at :L62-L63 and WS-MSG-NO-N at :L67-L68 - are "
                            + "redefinitions and occupy no storage of their own")
                    .isEqualTo(2L);
            assertThat(RESULT_AREA.stream().filter(declaration -> !declaration.redefines()).count())
                    .as("leaving thirteen that do")
                    .isEqualTo(13L);
        }

        @Test
        @DisplayName("the declared widths sum to exactly eighty bytes, which is the arithmetic and not just "
                + "the field list")
        void theDeclaredWidthsSumToExactlyEighty() {
            assertThat(occupiedWidth(RESULT_AREA))
                    .as("app/cpy/CSUTLDWY.cpy:L60-L85 sums as 4 + 11 + 4 + 1 + 15 + 1 + 9 + 10 + 1 + 10 + "
                            + "10 + 1 + 3, and every one of those thirteen terms has to be right for the "
                            + "total to be right")
                    .isEqualTo(4 + 11 + 4 + 1 + 15 + 1 + 9 + 10 + 1 + 10 + 10 + 1 + 3)
                    .isEqualTo(80)
                    .isEqualTo(DateValidationService.LS_RESULT_LENGTH);

            assertThat(widthCountingRedefinitions(RESULT_AREA))
                    .as("counting the two redefinitions as storage would give 88, which is the mistake that "
                            + "makes a REDEFINES ladder look eight bytes too long")
                    .isEqualTo(88)
                    .isNotEqualTo(DateValidationService.LS_RESULT_LENGTH);

            assertThat(service().validate("20240615", DateValidationService.MASK_YYYYMMDD).result())
                    .as("and the rendered area is that same width, which is what ties this declaration "
                            + "ladder to reality: LS-RESULT is PIC X(80) at app/cbl/CSUTLDTC.cbl:L86 and "
                            + "app/cbl/CSUTLDTC.cbl:L97 moves the whole eighty-byte group into it")
                    .hasSize(occupiedWidth(RESULT_AREA));
        }

        @Test
        @DisplayName("the caller's four-field view reconciles with the producer's thirteen through a "
                + "sixty-one-byte tail")
        void theCallersFourFieldViewReconciles() {
            final int tail = occupiedWidth(RESULT_AREA.subList(5, RESULT_AREA.size()));

            assertThat(tail)
                    .as("the run from the first PIC X(01) FILLER at app/cpy/CSUTLDWY.cpy:L69-L70 to the last "
                            + "at :L84-L85 is 1 + 15 + 1 + 9 + 10 + 1 + 10 + 10 + 1 + 3")
                    .isEqualTo(61)
                    .isEqualTo(DateValidationService.MESSAGE_TEXT_LENGTH);
            assertThat(4 + 11 + 4 + tail)
                    .as("so the caller's coarser view of severity, literal, message number and one long "
                            + "message text sums to the same eighty bytes as the producer's thirteen fields "
                            + "- which is why the message text is derived from the composed area rather than "
                            + "stored a second time")
                    .isEqualTo(DateValidationService.LS_RESULT_LENGTH);
        }

        @Test
        @DisplayName("the result text field is fifteen bytes, wide enough for every declared verdict")
        void theResultTextFieldIsFifteenBytes() {
            assertThat(RESULT_AREA.get(6).name())
                    .as("app/cpy/CSUTLDWY.cpy:L71 declares WS-RESULT")
                    .isEqualTo("WS-RESULT");
            assertThat(RESULT_AREA.get(6).width())
                    .as("app/cpy/CSUTLDWY.cpy:L71 declares it PIC X(15)")
                    .isEqualTo(15)
                    .isEqualTo(DateValidationService.RESULT_TEXT_LENGTH);
            assertThat(DateValidationService.UNRECOGNISED_RESULT_TEXT.length())
                    .as("and the fallback verdict fits inside it, so no verdict can be truncated by the "
                            + "field that carries it")
                    .isLessThanOrEqualTo(RESULT_AREA.get(6).width());
        }

        @Test
        @DisplayName("the three literal FILLERs carry exactly the padding their declared widths imply")
        void theThreeLiteralFillersCarryTheirDeclaredPadding() {
            assertLiteralPadding(2, "'Mesg Code:'", 11, 1,
                    "app/cpy/CSUTLDWY.cpy:L64-L65 puts a ten-character literal in a PIC X(11) FILLER, so "
                            + "ONE TRAILING SPACE follows it in the rendered area");
            assertLiteralPadding(8, "'TstDate:'", 9, 1,
                    "app/cpy/CSUTLDWY.cpy:L74-L75 puts an eight-character literal in a PIC X(09) FILLER, so "
                            + "ONE TRAILING SPACE follows it too");
            assertLiteralPadding(11, "'Mask used:'", 10, 0,
                    "app/cpy/CSUTLDWY.cpy:L79-L80 puts a ten-character literal in a PIC X(10) FILLER, so it "
                            + "is an EXACT FIT WITH NO PADDING - the one of the three that has none");
        }

        /**
         * Asserts that a literal FILLER's declared width is its literal's length plus the stated padding.
         *
         * @param index the position of the FILLER in the result-area ladder
         * @param literal the literal exactly as the copybook writes it, quotes included
         * @param declaredWidth the width the picture clause declares
         * @param padding the trailing spaces the difference implies
         * @param evidence the citation carried into the failure message
         */
        private static void assertLiteralPadding(final int index, final String literal,
                                                 final int declaredWidth, final int padding,
                                                 final String evidence) {
            final Declaration filler = RESULT_AREA.get(index);
            final int literalLength = literal.length() - 2;

            assertThat(filler.name()).as("%s", evidence).isEqualTo("FILLER");
            assertThat(filler.valueClause()).as("%s", evidence).contains(literal);
            assertThat(filler.width()).as("%s", evidence).isEqualTo(declaredWidth);
            assertThat(declaredWidth - literalLength)
                    .as("%s. The padding is part of the eighty rendered bytes and is easy to lose, so it is "
                            + "asserted as arithmetic rather than left implied", evidence)
                    .isEqualTo(padding);
        }

        @Test
        @DisplayName("the offsets derived from the declared widths are the offsets the renderer uses")
        void theDerivedOffsetsAreTheOffsetsTheRendererUses() {
            final int mesgCodeOffset = offsetOf(RESULT_AREA, 2, "FILLER");
            final int resultOffset = offsetOf(RESULT_AREA, 6, "WS-RESULT");
            final int tstDateOffset = offsetOf(RESULT_AREA, 8, "FILLER");
            final int dateOffset = offsetOf(RESULT_AREA, 9, "WS-DATE");
            final int maskUsedOffset = offsetOf(RESULT_AREA, 11, "FILLER");

            assertThat(List.of(mesgCodeOffset, resultOffset, tstDateOffset, dateOffset, maskUsedOffset))
                    .as("every offset follows from the widths that precede it, so nothing here is written "
                            + "down twice and the ladder cannot drift away from the offsets it implies. "
                            + "Note that app/cpy/CSUTLDWY.cpy:L74-L75 'TstDate:' is the FOURTH FILLER and "
                            + ":L79-L80 'Mask used:' the SIXTH, because the single spaces at :L69-L70, "
                            + ":L72-L73 and :L77-L78 are FILLERs too - which is why these items are "
                            + "addressed by position and never by an ordinal count of the name")
                    .containsExactly(4, 20, 36, 45, 56);

            final List<Integer> everyOffset = new ArrayList<>();
            for (int index = 0; index < RESULT_AREA.size(); index++) {
                if (!RESULT_AREA.get(index).redefines()) {
                    everyOffset.add(offsetOf(RESULT_AREA, index, RESULT_AREA.get(index).name()));
                }
            }

            assertThat(everyOffset)
                    .as("and the whole ladder derives one offset per occupying item, in ascending order, "
                            + "ending at the last PIC X(03) FILLER of app/cpy/CSUTLDWY.cpy:L84-L85 whose "
                            + "three bytes close the group at eighty")
                    .containsExactly(0, 4, 15, 19, 20, 35, 36, 45, 55, 56, 66, 76, 77);
            assertThat(everyOffset.getLast() + RESULT_AREA.getLast().width())
                    .as("so the final item ends exactly on the eighty-byte boundary that "
                            + "app/cbl/CSUTLDTC.cbl:L86 declares as LS-RESULT PIC X(80)")
                    .isEqualTo(DateValidationService.LS_RESULT_LENGTH);

            final String area = service().validate("20240615", DateValidationService.MASK_YYYYMMDD).result();

            assertThat(area.substring(mesgCodeOffset, mesgCodeOffset + RESULT_AREA.get(2).width()))
                    .as("and slicing a real area at the DERIVED offset yields the padded literal, which is "
                            + "the single cross-check that anchors this declaration table to reality")
                    .isEqualTo("Mesg Code: ");
            assertThat(area.substring(tstDateOffset, tstDateOffset + RESULT_AREA.get(8).width()))
                    .isEqualTo("TstDate: ");
            assertThat(area.substring(maskUsedOffset, maskUsedOffset + RESULT_AREA.get(11).width()))
                    .as("the third literal is an exact fit, so its slice carries no trailing space")
                    .isEqualTo("Mask used:");
        }

        @Test
        @DisplayName("the copybook ladder is field for field identical to the twin in the called program")
        void theCopybookLadderIsFieldForFieldIdenticalToTheTwin() {
            assertThat(TWIN_RESULT_AREA)
                    .as("app/cbl/CSUTLDTC.cbl:L42-L57 declares WS-MESSAGE with the same number of items as "
                            + "app/cpy/CSUTLDWY.cpy:L60-L85")
                    .hasSameSizeAs(RESULT_AREA);
            assertThat(occupiedWidth(TWIN_RESULT_AREA))
                    .as("and the same eighty-byte total, which is what allows the copybook's group to be "
                            + "passed straight into LS-RESULT PIC X(80) at app/cbl/CSUTLDTC.cbl:L86")
                    .isEqualTo(occupiedWidth(RESULT_AREA))
                    .isEqualTo(DateValidationService.LS_RESULT_LENGTH);

            for (int index = 0; index < RESULT_AREA.size(); index++) {
                final Declaration copybook = RESULT_AREA.get(index);
                final Declaration twin = TWIN_RESULT_AREA.get(index);

                assertThat(twin.name())
                        .as("item %d must be the same field in both declarations", index)
                        .isEqualTo(copybook.name());
                assertThat(twin.width())
                        .as("item %d, %s, must be the same width in both", index, copybook.name())
                        .isEqualTo(copybook.width());
                assertThat(twin.redefines())
                        .as("item %d, %s, must redefine in both or in neither", index, copybook.name())
                        .isEqualTo(copybook.redefines());
            }
        }

        @Test
        @DisplayName("MEDIUM: the twin's four divergences are recorded rather than normalised")
        void theTwinsFourDivergencesAreRecorded() {
            assertThat(RESULT_AREA)
                    .as("DIVERGENCE 1, LOW. app/cpy/CSUTLDWY.cpy declares the items at level 20 because the "
                            + "copybook is designed to sit beneath an 01 or 05 in the including program")
                    .allSatisfy(declaration -> assertThat(declaration.level()).isEqualTo(20));
            assertThat(TWIN_RESULT_AREA)
                    .as("while app/cbl/CSUTLDTC.cbl:L42-L57 declares its own 01 group and uses level 02")
                    .allSatisfy(declaration -> assertThat(declaration.level()).isEqualTo(2));

            assertThat(RESULT_AREA.get(4).picture())
                    .as("DIVERGENCE 2, LOW. app/cpy/CSUTLDWY.cpy:L68 spells the picture keyword in mixed "
                            + "case where every other clause in the file is upper case. Remedy: none - the "
                            + "copybook is frozen, and this assertion is the tracking reference")
                    .isEqualTo("Pic 9(4)");
            assertThat(TWIN_RESULT_AREA.get(4).picture())
                    .as("the twin at app/cbl/CSUTLDTC.cbl:L47 spells the same clause in upper case")
                    .isEqualTo("PIC 9(4)");
            assertThat(RESULT_AREA.get(4).picture().toUpperCase(Locale.ROOT))
                    .as("the two differ only in case, so the meaning is identical and only the spelling "
                            + "diverges")
                    .isEqualTo(TWIN_RESULT_AREA.get(4).picture());

            assertThat(RESULT_AREA.get(9).name()).isEqualTo("WS-DATE");
            assertThat(RESULT_AREA.get(9).valueClause())
                    .as("DIVERGENCE 3, MEDIUM. app/cpy/CSUTLDWY.cpy:L76 declares WS-DATE with NO VALUE "
                            + "clause at all")
                    .isEmpty();
            assertThat(TWIN_RESULT_AREA.get(9).valueClause())
                    .as("where its twin at app/cbl/CSUTLDTC.cbl:L52 declares VALUE SPACES. Not available: "
                            + "any reason for the omission")
                    .contains("SPACES");
        }

        @Test
        @DisplayName("MEDIUM: the missing VALUE SPACES converges only because both sides initialise the area")
        void theMissingValueClauseConvergesOnlyBecauseBothSidesInitialise() {
            final DateValidationService service = service();
            final String populated = service.validate("20240615", DateValidationService.MASK_YYYYMMDD)
                    .result();
            final int dateOffset = offsetOf(RESULT_AREA, 9, "WS-DATE");
            final int dateWidth = RESULT_AREA.get(9).width();

            final String shorter = service.validate("2024", DateValidationService.MASK_YYYYMMDD).result();
            assertThat(shorter.substring(dateOffset, dateOffset + dateWidth))
                    .as("MEDIUM. Because app/cpy/CSUTLDWY.cpy:L76 omits VALUE SPACES, the area would carry "
                            + "residue were it not cleared - and it IS cleared, twice: "
                            + "app/cpy/CSUTLDPY.cpy:L290 issues INITIALIZE before the call and "
                            + "app/cbl/CSUTLDTC.cbl:L90-L91 issues INITIALIZE then MOVE SPACES on entry. So "
                            + "a shorter date must leave no byte of the longer one behind")
                    .doesNotContain("0615")
                    .hasSize(dateWidth);
            assertThat(populated.substring(dateOffset, dateOffset + dateWidth))
                    .as("the earlier call really did populate those bytes, so the isolation just asserted "
                            + "is a genuine clearing rather than an empty field either way")
                    .contains("20240615");

            final String absent = service.validate(null, DateValidationService.MASK_YYYYMMDD).result();
            assertThat(absent.substring(dateOffset, dateOffset + dateWidth))
                    .as("and an absent date leaves the field clear of every preceding value, which is the "
                            + "observable outcome the two INITIALIZE statements guarantee in place of the "
                            + "VALUE clause the copybook never declared")
                    .doesNotContain("2024")
                    .hasSize(dateWidth);
        }

        @Test
        @DisplayName("LOW: the copybook's stray pre-period spaces are recorded as source hygiene")
        void theStrayPrePeriodSpacesAreRecorded() {
            assertThat(RESULT_AREA.get(11).valueClause())
                    .as("DIVERGENCE 4, LOW. app/cpy/CSUTLDWY.cpy:L80 reads VALUE 'Mask used:' with a space "
                            + "before its period, and :L60 reads WS-DATE-VALIDATION-RESULT with one too. "
                            + "Neither changes a single rendered byte, because a COBOL separator period may "
                            + "be preceded by space")
                    .contains("'Mask used:'");
            assertThat(RESULT_AREA.get(11).width())
                    .as("and the proof that the stray space changes nothing is that the field is still the "
                            + "declared ten bytes wide")
                    .isEqualTo(10);
            assertThat(occupiedWidth(RESULT_AREA))
                    .as("and the group is still the declared eighty. Remedy: none - the copybook is frozen "
                            + "and this assertion is the tracking reference")
                    .isEqualTo(DateValidationService.LS_RESULT_LENGTH);
        }
    }
}
