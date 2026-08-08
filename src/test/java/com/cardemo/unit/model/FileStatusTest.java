/*
 * ******************************************************************
 * Program     : FileStatusTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies com.cardemo.model.enums.FileStatus - the typed
 *               COBOL FILE STATUS vocabulary, its strict classification
 *               contract, and the byte exact four character
 *               IO-STATUS-04 rendering of 9910-DISPLAY-IO-STATUS.
 * Source      : app/cbl/CBTRN02C.cbl:L131-L144,L714-L727 @ 7756d89
 *               app/cbl/CBSTM03A.CBL:L736,L748 @ 7756d89
 *               app/cbl/CBACT04C.cbl:L422,L436 @ 7756d89
 *               app/cbl/COUSR01C.cbl:L260-L261 @ 7756d89
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
import static org.assertj.core.api.Assertions.catchThrowable;

import com.cardemo.model.enums.FileStatus;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link FileStatus}, the typed vocabulary of COBOL {@code FILE STATUS} values and the
 * four character {@code IO-STATUS-04} rendering that the frozen CardDemo batch corpus writes to SYSOUT
 * whenever an input or output guard fails.
 *
 * <h2>What this test class does</h2>
 *
 * <p>It pins three things that the migration cannot afford to get wrong, and it pins them against the
 * frozen corpus rather than against the production class's own documentation. Every locator below was
 * re-verified by direct inspection at commit {@code 7756d89} before the corresponding assertion was
 * written; none was copied from prose.
 *
 * <ol>
 *   <li><strong>The vocabulary.</strong> Seven constants: the six exact two character codes and the one
 *       {@code '9x'} family. The declaration order is asserted, because {@code values()} and the ordinals
 *       derived from it are observable.</li>
 *   <li><strong>The classification contract.</strong> {@code matches}, {@code fromCode},
 *       {@code tryClassify} and {@code classify} are strict: anything that is not exactly two characters,
 *       {@code null} included, is a miss rather than a value to be coerced into shape. That strictness is
 *       asserted with hostile input, not assumed.</li>
 *   <li><strong>The rendering.</strong> Both branches of {@code 9910-DISPLAY-IO-STATUS} at
 *       {@code app/cbl/CBTRN02C.cbl:L714-L727}, byte for byte, including the branch A expansion of the
 *       second status byte into three decimal digits and the preserved {@code NNNN} placeholder quirk.
 *       The end to end parity gate compares emitted log lines against the legacy baseline, so a rendering
 *       that differs by one character is a gate failure.</li>
 *   </ol>
 *
 * <h2>What this test class deliberately does not do</h2>
 *
 * <p>It asserts that {@link FileStatus} <strong>classifies and does not decide</strong>. There is no
 * assertion here about which exception a status becomes, because {@link FileStatus} does not make that
 * decision: status to exception translation lives exactly once, in
 * {@code com.cardemo.service.shared.FileStatusMapper}, and is tested with that class. The tests in
 * {@code SeparationOfConcerns} prove the absence of any such member by reflecting over the declared
 * surface, which is the only way to assert that something is not there.
 *
 * <p>Cross package types are named with {@code @code} rather than {@code @link} throughout, matching the
 * convention the production class states, because those types are authored by sibling units of this same
 * migration and an unresolved link would fail a documentation build rather than merely warn.
 *
 * <h2>Provenance of every value asserted, with the gaps stated plainly</h2>
 *
 * <p>Counts are literal occurrence counts over {@code app/cbl}, matching case insensitively so that the
 * two uppercase {@code .CBL} members are not silently dropped.
 *
 * <table border="1">
 *   <caption>Status values, meanings and the evidence for each</caption>
 *   <tr><th>Value</th><th>Meaning</th><th>Evidence</th></tr>
 *   <tr><td>{@code '00'}</td><td>Success</td>
 *       <td>88 literal occurrences; canonical {@code app/cbl/CBTRN02C.cbl:L239}</td></tr>
 *   <tr><td>{@code '04'}</td><td>Accepted secondary success</td>
 *       <td>9 occurrences, all in {@code app/cbl/CBSTM03A.CBL}; canonical L736 and L748</td></tr>
 *   <tr><td>{@code '10'}</td><td>End of file, loop termination and not an error</td>
 *       <td>11 occurrences; canonical {@code app/cbl/CBTRN02C.cbl:L351}</td></tr>
 *   <tr><td>{@code '22'}</td><td>Duplicate key</td>
 *       <td><strong>Zero</strong> literal occurrences; grounded only through
 *           {@code DFHRESP(DUPREC)} at 7 sites and {@code DFHRESP(DUPKEY)} at 3;
 *           canonical {@code app/cbl/COUSR01C.cbl:L260-L261}</td></tr>
 *   <tr><td>{@code '23'}</td><td>Record not found</td>
 *       <td>Exactly 3 literal occurrences: {@code app/cbl/CBTRN02C.cbl:L481},
 *           {@code app/cbl/CBACT04C.cbl:L422} and {@code app/cbl/CBACT04C.cbl:L436}</td></tr>
 *   <tr><td>{@code '35'}</td><td>File unavailable</td>
 *       <td><strong>No source grounding.</strong> Zero literal occurrences, and
 *           {@code DFHRESP(NOTOPEN)} confirmed absent from the whole of {@code app/}</td></tr>
 *   <tr><td>{@code '9x'}</td><td>Physical or logical input or output error</td>
 *       <td>Family, not a value: the guard {@code IO-STAT1 = '9'} at
 *           {@code app/cbl/CBTRN02C.cbl:L716}, replicated at 8 sites</td></tr>
 * </table>
 *
 * <p><strong>{@code '35'} has no source grounding, and none is invented.</strong> Verified twice
 * over the frozen tree: zero literal {@code '35'} comparisons anywhere under {@code app/}, and zero
 * occurrences of {@code NOTOPEN} in any file under {@code app/}. The full response code census over
 * {@code app/cbl} is {@code DFHRESP(NORMAL)} 43, {@code DFHRESP(NOTFND)} 23, {@code DFHRESP(ENDFILE)} 8,
 * {@code DFHRESP(DUPREC)} 7, {@code DFHRESP(DUPKEY)} 3 and {@code DFHRESP(NOTOPEN)} 0. Closing that gap
 * would take one of exactly two artefacts, neither of which the corpus contains: a literal
 * {@code '35'} comparison in a COBOL program, or a {@code DFHRESP(NOTOPEN)} handler in an online program.
 * Until one exists the constant is asserted as a specification derived member of the taxonomy and
 * no {@code app/} line citation is offered for it, because inventing one would be a false citation.
 *
 * <p><strong>{@code '22'} has no literal occurrence either.</strong> It is grounded
 * only through the transaction monitor response codes listed in the table. The tests therefore assert its
 * membership in the taxonomy and its rendering, and cite {@code DFHRESP} rather than a literal.
 *
 * <p><strong>No schema detail is asserted here.</strong> No column
 * type, width or constraint in {@code src/main/resources/db/migration/V1__create_schema.sql} is a
 * counterpart of {@link FileStatus} - a file status is a runtime I/O
 * outcome, not a stored value - so nothing here asserts one, and that is by design rather than by
 * omission.
 *
 * <h2>The three sites where a record not found or a secondary status is success</h2>
 *
 * <p>Everywhere else a not found status reaches the abend guard. At exactly three sites it does not, and
 * the tests assert that {@link FileStatus} stays neutral about all three so that the decision remains
 * with the caller:
 *
 * <ol>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L481} reads {@code IF TCATBALF-STATUS = '00'  OR '23'}, the read
 *       guard of the transaction category balance upsert {@code 2700-UPDATE-TCATBAL}. On
 *       {@code INVALID KEY} the read displays {@code 'TCATBAL record not found for key : '} and
 *       {@code '.. Creating.'} at L476 and L477 and sets the create flag at L478. <strong>The leniency is
 *       scoped to the read guard alone</strong>: the {@code WRITE} verification at L512 and the
 *       {@code REWRITE} verification at L530 accept {@code '00'} and nothing else.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl:L422} reads {@code IF DISCGRP-STATUS  = '00'  OR '23'} for the
 *       disclosure group rate lookup, and L436 reads {@code IF DISCGRP-STATUS  = '23'}, which substitutes
 *       the literal {@code 'DEFAULT'} group at L437 and retries through
 *       {@code 1200-A-GET-DEFAULT-INT-RATE} at L438. That retry's guard at L446 accepts {@code '00'}
 *       only, so a missing default row abends the job.</li>
 *   <li>{@code app/cbl/CBSTM03A.CBL:L736} (open) and {@code app/cbl/CBSTM03A.CBL:L748} (read) read
 *       {@code IF WS-M03B-RC = '00' OR '04'}; the else branch at L739 to L741 displays
 *       {@code 'ERROR OPENING TRNXFILE'} and abends. The same acceptance recurs at L771, L789, L807,
 *       L862, L879, L895 and L911, nine sites in total, while four {@code EVALUATE WS-M03B-RC} sites in
 *       the same program at L353, L379, L403 and L837 accept {@code '00'} alone. The literal
 *       {@code '04'} does not occur even once in the callee {@code app/cbl/CBSTM03B.CBL}, so the
 *       tolerance is defensive.</li>
 *   </ol>
 *
 * <p>Because the tolerance is site conditional, {@code SeparationOfConcerns} asserts that no acceptance
 * or tolerance predicate exists on the type at all. A single {@code isSuccess} would have to be wrong at
 * one of these sites.
 *
 * <h2>The abend contract, documented and deliberately not implemented</h2>
 *
 * <p>{@code 9999-ABEND-PROGRAM} at {@code app/cbl/CBTRN02C.cbl:L707-L711} displays
 * {@code 'ABENDING PROGRAM'} at L708, moves 0 into the timing field at L709, moves <strong>999</strong>
 * into the abend code at L710 and calls {@code 'CEE3ABD'} at L711; the process return code is 12. That
 * behaviour belongs to {@code com.cardemo.exception.FatalProcessingException} and to the batch layer.
 * Nothing here implements it. It is asserted only where the corpus itself makes it observable, namely
 * inside the rejection message of {@code classify}, which cites abend code 999, return code 12 and the
 * paragraph's line range.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Compile with {@code ./mvnw -B clean test-compile} and run with {@code ./mvnw -B clean test}; gate coverage
 * with {@code ./mvnw -B verify}, which enforces an eighty percent line floor through JaCoCo with no
 * exclusions. Where no Java toolchain is on the path, the same commands run inside a container, for
 * example {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q test}.
 *
 * <p><strong>This tier is bound to Surefire, and the binding is positional.</strong> The root
 * {@code pom.xml} configures {@code maven-surefire-plugin} 3.5.4 to include {@code **}{@code /*Test.java}
 * while excluding {@code **}{@code /integration/**} and {@code **}{@code /e2e/**}, and configures
 * {@code maven-failsafe-plugin} to include only those two excluded trees. A class placed outside
 * {@code src/test/java/com/cardemo/unit} whose name still ends in {@code Test} is therefore collected by
 * neither plugin: the build stays green, both plugins report success, JaCoCo records the class as
 * uncovered and nothing warns. Do not move, rename or repackage this file. After any change, confirm the
 * class really ran by checking that
 * {@code target/surefire-reports/com.cardemo.unit.model.FileStatusTest.txt} exists and reports a non zero
 * test count.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>None is required, and the two the tier makes available are deliberately unused here.
 *
 * <ul>
 *   <li><strong>Fixed clock.</strong> {@code FixedClockProvider}, in this same package, is the only
 *       sanctioned source of time for the unit tier, and every test that observes a generated timestamp
 *       must take its instant from there rather than from the ambient clock. {@link FileStatus} reads no
 *       clock, holds no date field and has no time dependent behaviour, so no clock is injected here.
 *       Wiring one in would add a dependency that no assertion needs. Nothing in this file calls
 *       {@code Instant.now}, {@code LocalDate.now} or {@code System.currentTimeMillis}.</li>
 *   <li><strong>Mockito strictness.</strong> The tier inherits Mockito's default
 *       {@code Strictness.STRICT_STUBS} through {@code spring-boot-starter-test}, under which an unused
 *       stub fails the test. No mock, spy or stub appears in this file, because {@link FileStatus} is a
 *       pure enumeration with no collaborator for a double to stand in for; every method on it is a
 *       function of its arguments alone.</li>
 *   <li><strong>Locale, charset and time zone.</strong> Never read and never mutated. Mutating a JVM
 *       default would be global mutable state and would make results depend on test execution order, so
 *       instead the tests assert that the three digit expansion is composed of ASCII digits, which is
 *       what the production {@code Locale.ROOT} formatting guarantees. Non ASCII inputs are written as
 *       Unicode escapes so this source file itself stays pure ASCII.</li>
 *   </ul>
 *
 * <h2>Common failure modes and how to troubleshoot them</h2>
 *
 * <ul>
 *   <li><strong>The build fails at test compilation with a warning, not an error.</strong> The compiler
 *       runs with {@code -Xlint:all -Werror} and {@code failOnWarning}, and that configuration does reach
 *       test sources: this was verified by deliberately introducing a raw type into this file, which
 *       failed the build with {@code warnings found and -Werror specified}. One raw type, one unchecked
 *       conversion, one deprecated call or one {@code this}-escape is therefore a build failure. Remove
 *       the offending construct; never widen the lint configuration to accommodate it.</li>
 *   <li><strong>An unused import is <em>not</em> caught by the build, and that is a trap.</strong>
 *       Verified by the same method: adding a spurious import to this file still produced
 *       {@code BUILD SUCCESS}, because {@code javac} publishes no lint category for unused imports and
 *       this project configures no Checkstyle, Spotless or PMD plugin that would supply one. Rule 1
 *       clause B nevertheless forbids them, so the guarantee here is manual, not mechanical: every
 *       import above was confirmed to be referenced by the body before this file was committed. Re-run
 *       that audit by eye after any edit that deletes an assertion, because deleting the last use of a
 *       type is exactly how a silent unused import appears.</li>
 *   <li><strong>A rendering assertion fails by exactly one character.</strong> Suspect the branch
 *       predicate first. It is an inclusive or between the pair not being numeric and the first byte
 *       being {@code '9'}, so the entirely numeric {@code "90"} renders as {@code 9048} through branch A
 *       and not as {@code 0090} through branch B.</li>
 *   <li><strong>A display line assertion fails with the placeholder missing.</strong> The legacy line is
 *       the prefix immediately followed by the rendering with no separator, so status {@code '23'} emits
 *       {@code FILE STATUS IS: NNNN0023}. The tidier looking {@code FILE STATUS IS: 0023} is a parity
 *       diff. Preserve the placeholder.</li>
 *   <li><strong>A reflection assertion fails after an unrelated change.</strong> The declared surface
 *       assertions filter out members generated by the compiler and by the coverage agent, which are
 *       {@code $VALUES}, {@code $values}, {@code $jacocoData} and {@code $jacocoInit}. A newly reported
 *       member that is not one of those is a real change to the public surface and needs a decision, not
 *       a widened filter.</li>
 *   <li><strong>A fixture cannot be found.</strong> The daily transaction fixture is
 *       {@code app/data/ASCII/dailytran.txt}, spelled in full. The mainframe dataset and DD name are
 *       {@code DALYTRAN}, so {@code dalytran.txt} is the trap; that path does not exist and never did.
 *       No test in this file reads a fixture, and the note is recorded because the mistake is otherwise
 *       made once per tier.</li>
 *   </ul>
 *
 * @see FileStatus
 */
final class FileStatusTest {

    /**
     * The twenty character display literal, transcribed independently from {@code app/cbl/CBTRN02C.cbl:L721}
     * and {@code app/cbl/CBTRN02C.cbl:L725} so that the assertion is a golden value rather than a restatement
     * of the production constant.
     */
    private static final String LEGACY_DISPLAY_LITERAL = "FILE STATUS IS: NNNN";

    /**
     * Width of {@code IO-STATUS}, two {@code PIC X} items grouped at {@code app/cbl/CBTRN02C.cbl:L131-L133},
     * and independently the width of {@code LK-M03B-RC PIC X(02)} at {@code app/cbl/CBSTM03B.CBL:L109}.
     */
    private static final int LEGACY_STATUS_FIELD_WIDTH = 2;

    /**
     * Width of {@code IO-STATUS-04}, a {@code PIC 9} followed by a {@code PIC 999} at
     * {@code app/cbl/CBTRN02C.cbl:L138-L140}, which is one digit plus three digits and so exactly four
     * characters.
     */
    private static final int LEGACY_RENDERED_WIDTH = 4;

    /**
     * The first byte the corpus tests to recognise the input or output error family, taken from
     * {@code IO-STAT1 = '9'} at {@code app/cbl/CBTRN02C.cbl:L716}.
     */
    private static final char LEGACY_FAMILY_FIRST_BYTE = '9';

    /**
     * Width of the DB2 format timestamp whose redefinition at {@code app/cbl/CBTRN02C.cbl:L159-L175} sums to
     * exactly twenty six: four year digits, a separator, two month digits, a separator, two day digits, a
     * separator, two hour digits, a separator, two minute digits, a separator, two second digits, a separator,
     * two hundredth digits and a four byte remainder.
     */
    private static final int LEGACY_DB2_TIMESTAMP_WIDTH = 26;

    /**
     * Method names that would each amount to {@link FileStatus} deciding rather than classifying. None may
     * exist on the type: whether a not found or a secondary status is tolerated depends on the call site -
     * {@code app/cbl/CBTRN02C.cbl}:L481 and {@code app/cbl/CBACT04C.cbl}:L422 accept {@code '23'} while every
     * other read treats it as an error, and {@code app/cbl/CBSTM03A.CBL}:L736 accepts {@code '04'} - so any
     * single answer given here would be wrong somewhere.
     */
    private static final List<String> DECISION_VOCABULARY = List.of(
            "isSuccess", "isSuccessful", "isError", "isFailure", "isFatal", "isAcceptable", "isTolerated",
            "isLenient", "isAbend", "toException", "asException", "exception", "throwIfNotOk", "abend",
            "raise", "orThrow");

    /**
     * Lower case fragments of type names that would betray logging or metrics instrumentation on a type that
     * must have neither. The corpus's only instrumentation is {@code DISPLAY} to SYSOUT, and the replacement
     * for it lives in {@code com.cardemo.observability}, never in a model enumeration.
     */
    private static final List<String> INSTRUMENTATION_TOKENS = List.of(
            "logger", "logging", "log4j", "slf4j", "logback", "appender", "micrometer", "meterregistry",
            "counter", "timer", "tracer", "span", "metric");

    /**
     * Composes the rejection message the enumeration is required to raise, verbatim.
     *
     * @param offendingValue the value that was rejected, rendered as {@code null} when it was null
     * @return the complete expected message, never {@code null}
     */
    private static String expectedRejectionMessage(String offendingValue) {
        return "Unrecognised COBOL FILE STATUS (IO-STATUS PIC X(02)): [" + offendingValue
                + "]. Recognised values are the exact codes '00', '04', '10', '22', '23' and '35',"
                + " and the '9x' family identified by a first byte of '9'. The frozen corpus answers"
                + " an unrecognised status by abending with code 999 and process return code 12; see"
                + " app/cbl/CBTRN02C.cbl:L707-L711.";
    }

    /**
     * Reports whether a declared member was generated rather than written, which covers the compiler's
     * {@code $VALUES} field and {@code $values} method for every enumeration and the coverage agent's
     * {@code $jacocoData} field and {@code $jacocoInit} method, added by the JaCoCo java agent that the
     * Surefire {@code argLine} installs. Both sets are marked synthetic; the name test is a second, independent
     * guard so that the surface assertions cannot be broken by an agent that forgets the flag.
     *
     * @param member a declared field or method of the type under test, never {@code null}
     * @return {@code true} when the member was generated and must not be counted as public surface
     */
    private static boolean isGenerated(Member member) {
        return member.isSynthetic() || member.getName().startsWith("$");
    }

    /**
     * Collects the names of every non generated public method declared on {@link FileStatus}, sorted so that
     * the assertion cannot depend on the order in which reflection happens to report them. Duplicates are
     * retained deliberately, so that an accidental overload is visible.
     *
     * @return the sorted public method names, never {@code null}
     */
    private static List<String> publicMethodNames() {
        return Arrays.stream(FileStatus.class.getDeclaredMethods())
                .filter(method -> !isGenerated(method))
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .sorted()
                .toList();
    }

    /**
     * Collects the names of every non generated public field declared on {@link FileStatus}, which is the seven
     * enumeration constants plus the four named constants, sorted for the same reason.
     *
     * @return the sorted public field names, never {@code null}
     */
    private static List<String> publicFieldNames() {
        return Arrays.stream(FileStatus.class.getDeclaredFields())
                .filter(field -> !isGenerated(field))
                .filter(field -> Modifier.isPublic(field.getModifiers()))
                .map(Field::getName)
                .sorted()
                .toList();
    }

    /**
     * Collects the distinct declared field types of {@link FileStatus} by fully qualified name, public and
     * private alike, sorted. This is the assertion that proves the absence of a collaborator: a logger, a meter
     * registry or an injected service would necessarily appear here.
     *
     * @return the sorted distinct field type names, never {@code null}
     */
    private static List<String> declaredFieldTypeNames() {
        return Arrays.stream(FileStatus.class.getDeclaredFields())
                .filter(field -> !isGenerated(field))
                .map(Field::getType)
                .map(Class::getName)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Collects the values of every non generated public {@code int} constant declared on {@link FileStatus}, so
     * that the width constants the type exposes can be asserted as a closed set rather than one at a time.
     *
     * @return the sorted constant values, never {@code null}
     * @throws IllegalStateException if a public constant cannot be read, which on a public static field of an
     * accessible public type can only mean the class file no longer matches its source
     */
    private static List<Integer> publicIntConstantValues() {
        return Arrays.stream(FileStatus.class.getDeclaredFields())
                .filter(field -> !isGenerated(field))
                .filter(field -> Modifier.isPublic(field.getModifiers()))
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .filter(field -> field.getType() == int.class)
                .map(FileStatusTest::readIntConstant)
                .sorted()
                .toList();
    }

    /**
     * Reads one public {@code int} constant, translating the checked reflective failure into an unchecked one
     * that preserves the root cause, because a test helper that swallowed it would hide the only information
     * available about why the read failed.
     *
     * @param field a public static {@code int} field of {@link FileStatus}, never {@code null}
     * @return the field's value
     * @throws IllegalStateException wrapping the original {@link IllegalAccessException}
     */
    private static int readIntConstant(Field field) {
        try {
            return field.getInt(null);
        } catch (IllegalAccessException cause) {
            throw new IllegalStateException(
                    "Unable to read the public constant " + field.getName() + " declared on "
                            + FileStatus.class.getName(), cause);
        }
    }

    /**
     * The enumeration classifies; it does not decide. These tests assert the absence of every member that would
     * make it decide, and absence can only be asserted reflectively.
     */
    @Nested
    @DisplayName("Separation of concerns: it classifies, it does not decide")
    class SeparationOfConcerns {

        @Test
        @DisplayName("the public method surface is exactly the twelve documented members and nothing else")
        void publicMethodSurfaceIsExactlyTheDocumentedClassificationApi() {
            assertThat(publicMethodNames())
                    .as("an unexpected public method is a widening of the contract, not a detail")
                    .containsExactly("classify", "code", "escapeForDiagnostics", "fromCode", "isExactValue",
                            "isFamily", "matches", "renderIoStatus04", "renderIoStatus04ForDiagnostics",
                            "tryClassify", "valueOf", "values");
        }

        @Test
        @DisplayName("the two renderings are separate methods, so neither can be reached through a flag")
        void keepsTheParityAndDiagnosticRenderingsAsSeparateMethods() {
            List<String> renderers = publicMethodNames().stream()
                    .filter(name -> name.startsWith("render"))
                    .toList();

            assertThat(renderers)
                    .as("a boolean or enum switch between them would let one call site silently choose the "
                            + "wrong one; two names cannot be confused by a mistyped argument")
                    .containsExactly("renderIoStatus04", "renderIoStatus04ForDiagnostics");

            assertThat(Arrays.stream(FileStatus.class.getDeclaredMethods())
                    .filter(method -> method.getName().startsWith("render"))
                    .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                    .map(Class::getName)
                    .distinct()
                    .toList())
                    .as("each renderer takes the raw status and nothing else - no mode parameter")
                    .containsExactly("java.lang.String");
        }

        @Test
        @DisplayName("no method translates a status into an exception - that is FileStatusMapper's job")
        void declaresNoMethodThatTranslatesAStatusIntoAnException() {
            List<String> offenders = Arrays.stream(FileStatus.class.getDeclaredMethods())
                    .filter(method -> !isGenerated(method))
                    .filter(method -> Throwable.class.isAssignableFrom(method.getReturnType())
                            || Arrays.stream(method.getParameterTypes())
                                    .anyMatch(Throwable.class::isAssignableFrom))
                    .map(Method::getName)
                    .sorted()
                    .toList();

            assertThat(offenders)
                    .as("status to exception translation lives once, in FileStatusMapper")
                    .isEmpty();
        }

        @Test
        @DisplayName("no acceptance or tolerance predicate exists, because leniency is site conditional")
        void exposesNoAcceptanceOrTolerancePredicateBecauseLeniencyIsSiteConditional() {
            assertThat(publicMethodNames())
                    .as("'23' is success at CBTRN02C:L481 and an abend elsewhere; one answer cannot serve")
                    .doesNotContainAnyElementsOf(DECISION_VOCABULARY);
        }

        @Test
        @DisplayName("no field is a logger, a meter registry or any other collaborator")
        void declaresNoLoggerMeterOrOtherCollaboratorField() {
            assertThat(declaredFieldTypeNames())
                    .as("a status vocabulary needs the JDK and itself; anything else is a collaborator")
                    .containsExactly("char", FileStatus.class.getName(), "int", "java.lang.String",
                            "java.util.Map");
        }

        @Test
        @DisplayName("no field type name betrays logging or metrics instrumentation")
        void noFieldTypeNameBetraysInstrumentation() {
            List<String> offenders = declaredFieldTypeNames().stream()
                    .filter(typeName -> INSTRUMENTATION_TOKENS.stream()
                            .anyMatch(token -> typeName.toLowerCase(Locale.ROOT).contains(token)))
                    .toList();

            assertThat(offenders)
                    .as("the corpus instruments with DISPLAY only; the replacement is in observability")
                    .isEmpty();
        }

        @Test
        @DisplayName("the public constant surface is the seven statuses plus four named constants")
        void publicConstantSurfaceIsExactlyTheSevenStatusesAndTheFourNamedConstants() {
            assertThat(publicFieldNames())
                    .as("eleven public fields: seven constants, three widths or bytes, one literal")
                    .containsExactly("DISPLAY_MESSAGE_PREFIX", "DUPLICATE_KEY", "END_OF_FILE",
                            "FILE_UNAVAILABLE", "IO_ERROR", "IO_ERROR_FIRST_BYTE", "RECORD_NOT_FOUND",
                            "RENDERED_STATUS_LENGTH", "STATUS_CODE_LENGTH", "SUCCESS",
                            "SUCCESS_SECONDARY");
        }

        @Test
        @DisplayName("every declared field is final, so the type holds no global mutable state")
        void everyDeclaredFieldIsFinalSoThereIsNoGlobalMutableState() {
            List<String> mutable = Arrays.stream(FileStatus.class.getDeclaredFields())
                    .filter(field -> !isGenerated(field))
                    .filter(field -> !Modifier.isFinal(field.getModifiers()))
                    .map(Field::getName)
                    .sorted()
                    .toList();

            assertThat(mutable).as("a non final field here would be shared mutable state").isEmpty();
        }

        @Test
        @DisplayName("the type declares no interface, so it is not serializable by declaration")
        void declaresNoInterfaceSoItIsNotSerializableByDeclaration() {
            assertThat(FileStatus.class.getInterfaces())
                    .as("enum constants already serialize by name; declaring Serializable adds only an "
                            + "obligation")
                    .isEmpty();
        }

        @Test
        @DisplayName("the enumeration is final and privately constructed, so no subclass can add decisions")
        void isFinalAndPrivatelyConstructedSoNoSubclassCanAddDecisions() {
            assertThat(Modifier.isFinal(FileStatus.class.getModifiers()))
                    .as("no constant carries a body, so the enumeration is final")
                    .isTrue();

            List<String> visibleConstructors = Arrays.stream(FileStatus.class.getDeclaredConstructors())
                    .filter(constructor -> !Modifier.isPrivate(constructor.getModifiers()))
                    .map(constructor -> constructor.toGenericString())
                    .toList();

            assertThat(visibleConstructors)
                    .as("only the enumeration itself may create its constants")
                    .isEmpty();
        }
    }

    /**
     * The vocabulary itself: seven constants, six of them exact two character codes and one of them the
     * {@code '9x'} family that the corpus matches on its first byte alone.
     */
    @Nested
    @DisplayName("Status vocabulary: six exact codes and one family")
    class StatusVocabulary {

        @Test
        @DisplayName("exactly seven constants are declared, in the documented order")
        void declaresExactlySevenConstantsInTheDocumentedOrder() {
            assertThat(FileStatus.values())
                    .as("values() order is observable through ordinal(), so the order is contractual")
                    .containsExactly(FileStatus.SUCCESS, FileStatus.SUCCESS_SECONDARY,
                            FileStatus.END_OF_FILE, FileStatus.DUPLICATE_KEY, FileStatus.RECORD_NOT_FOUND,
                            FileStatus.FILE_UNAVAILABLE, FileStatus.IO_ERROR);
        }

        @ParameterizedTest(name = "{0} carries the exact code ''{1}''")
        @CsvSource({"SUCCESS,00", "SUCCESS_SECONDARY,04", "END_OF_FILE,10", "DUPLICATE_KEY,22",
                "RECORD_NOT_FOUND,23", "FILE_UNAVAILABLE,35"})
        @DisplayName("each exact constant carries its own two character code")
        void exactConstantsCarryTheirTwoCharacterCode(String constantName, String expectedCode) {
            assertThat(FileStatus.valueOf(constantName).code()).contains(expectedCode);
        }

        @Test
        @DisplayName("the six exact codes are '00', '04', '10', '22', '23' and '35'")
        void theSixExactCodesAreTheDocumentedValues() {
            List<String> codes = Arrays.stream(FileStatus.values())
                    .filter(FileStatus::isExactValue)
                    .map(status -> status.code().orElseThrow())
                    .toList();

            assertThat(codes).containsExactly("00", "04", "10", "22", "23", "35");
        }

        @Test
        @DisplayName("the family constant has no code, because no literal '9x' pair exists in the corpus")
        void familyConstantHasNoCodeBecauseTheCorpusNeverWritesOne() {
            assertThat(FileStatus.IO_ERROR.code())
                    .as("the corpus tests IO-STAT1 = '9' at CBTRN02C:L716, never a whole pair")
                    .isEmpty();
            assertThat(FileStatus.IO_ERROR.isFamily()).isTrue();
            assertThat(FileStatus.IO_ERROR.isExactValue()).isFalse();
        }

        @ParameterizedTest(name = "{0} reports itself as an exact value and not as a family")
        @ValueSource(strings = {"SUCCESS", "SUCCESS_SECONDARY", "END_OF_FILE", "DUPLICATE_KEY",
                "RECORD_NOT_FOUND", "FILE_UNAVAILABLE"})
        void exactConstantsReportThemselvesAsExactValues(String constantName) {
            FileStatus status = FileStatus.valueOf(constantName);

            assertThat(status.isExactValue()).isTrue();
            assertThat(status.isFamily()).isFalse();
        }

        @Test
        @DisplayName("exactly one of the seven constants is a family")
        void exactlyOneConstantIsAFamily() {
            List<FileStatus> families = Arrays.stream(FileStatus.values())
                    .filter(FileStatus::isFamily)
                    .toList();

            assertThat(families).containsExactly(FileStatus.IO_ERROR);
        }

        @Test
        @DisplayName("every exact code is two characters wide and no two constants share a code")
        void everyExactCodeIsTwoCharactersWideAndUnique() {
            List<String> codes = Arrays.stream(FileStatus.values())
                    .filter(FileStatus::isExactValue)
                    .map(status -> status.code().orElseThrow())
                    .toList();

            assertThat(codes).hasSize(6).doesNotHaveDuplicates();
            assertThat(codes.stream().map(String::length).distinct().toList())
                    .as("IO-STATUS is a group of two PIC X items at CBTRN02C:L131-L133")
                    .containsExactly(LEGACY_STATUS_FIELD_WIDTH);
        }

        @Test
        @DisplayName("'00' is SUCCESS - 88 literal occurrences, canonical CBTRN02C.cbl:L239")
        void successIsTheMostFrequentlyTestedLiteralInTheCorpus() {
            assertThat(FileStatus.classify("00")).isEqualTo(FileStatus.SUCCESS);
            assertThat(FileStatus.SUCCESS.matches("00")).isTrue();
        }

        @Test
        @DisplayName("'10' is END_OF_FILE, loop termination and not an error - CBTRN02C.cbl:L351")
        void endOfFileIsLoopTerminationAndNotAnError() {
            assertThat(FileStatus.classify("10")).isEqualTo(FileStatus.END_OF_FILE);
            assertThat(FileStatus.END_OF_FILE.matches("10")).isTrue();
        }

        @Test
        @DisplayName("'04' is SUCCESS_SECONDARY and stays distinct from '00' - CBSTM03A.CBL:L736,L748")
        void secondarySuccessIsDistinctFromSuccessBecauseToleranceIsSiteConditional() {
            assertThat(FileStatus.classify("04"))
                    .as("nine IF sites accept '00' OR '04' while four EVALUATE sites accept '00' alone")
                    .isEqualTo(FileStatus.SUCCESS_SECONDARY)
                    .isNotEqualTo(FileStatus.SUCCESS);
        }

        @Test
        @DisplayName("'23' is RECORD_NOT_FOUND and the type stays neutral about the three lenient sites")
        void recordNotFoundIsClassifiedWithoutJudgingWhetherItIsAcceptable() {
            assertThat(FileStatus.classify("23")).isEqualTo(FileStatus.RECORD_NOT_FOUND);
            assertThat(FileStatus.RECORD_NOT_FOUND.code()).contains("23");
        }

        @Test
        @DisplayName("'22' is DUPLICATE_KEY, grounded only through DFHRESP(DUPREC) and DFHRESP(DUPKEY)")
        void duplicateKeyIsGroundedThroughResponseCodesRatherThanALiteral() {
            assertThat(FileStatus.classify("22"))
                    .as("zero literal '22' in app/cbl; canonical grounding COUSR01C.cbl:L260-L261")
                    .isEqualTo(FileStatus.DUPLICATE_KEY);
            assertThat(FileStatus.DUPLICATE_KEY.code()).contains("22");
        }

        @Test
        @DisplayName("'35' is FILE_UNAVAILABLE although its source grounding is Not available")
        void fileUnavailableIsSpecificationDerivedBecauseItsGroundingIsNotAvailable() {
            assertThat(FileStatus.classify("35"))
                    .as("zero literal '35' and zero DFHRESP(NOTOPEN) anywhere under app/; Medium finding")
                    .isEqualTo(FileStatus.FILE_UNAVAILABLE);
            assertThat(FileStatus.FILE_UNAVAILABLE.code()).contains("35");
        }
    }

    /**
     * {@code matches} is the per constant membership test. It is strict on purpose: an untrusted value is a
     * miss rather than something to be reshaped, because reshaping before classifying is how a malformed status
     * ends up misread as a success.
     */
    @Nested
    @DisplayName("Membership: matches is strict and never coerces its argument")
    class MembershipTest {

        @ParameterizedTest(name = "{0} matches ''{1}'' and no other exact code")
        @CsvSource({"SUCCESS,00", "SUCCESS_SECONDARY,04", "END_OF_FILE,10", "DUPLICATE_KEY,22",
                "RECORD_NOT_FOUND,23", "FILE_UNAVAILABLE,35"})
        void constantMatchesItsOwnCodeAndNoOtherExactCode(String constantName, String ownCode) {
            FileStatus status = FileStatus.valueOf(constantName);
            assertThat(status.matches(ownCode)).isTrue();

            List<String> alsoMatched = Arrays.stream(FileStatus.values())
                    .filter(FileStatus::isExactValue)
                    .map(candidate -> candidate.code().orElseThrow())
                    .filter(code -> !code.equals(ownCode))
                    .filter(status::matches)
                    .toList();

            assertThat(alsoMatched).isEmpty();
        }

        @ParameterizedTest(name = "IO_ERROR matches ''{0}'' on its first byte alone")
        @ValueSource(strings = {"90", "91", "99", "9A", "9-", "9 "})
        @DisplayName("the family matches any second byte, exactly as IO-STAT1 = '9' does")
        void familyMatchesAnySecondByteAfterTheFamilyFirstByte(String ioStatus) {
            assertThat(FileStatus.IO_ERROR.matches(ioStatus)).isTrue();
        }

        @ParameterizedTest(name = "IO_ERROR does not match ''{0}''")
        @ValueSource(strings = {"09", "89", "19", "00", "  "})
        @DisplayName("the family does not match a status whose first byte is not '9'")
        void familyDoesNotMatchAStatusWhoseFirstByteIsNotNine(String ioStatus) {
            assertThat(FileStatus.IO_ERROR.matches(ioStatus)).isFalse();
        }

        @Test
        @DisplayName("no constant matches null, because null is a miss and not a value to coerce")
        void noConstantMatchesNull() {
            List<FileStatus> matching = Arrays.stream(FileStatus.values())
                    .filter(status -> status.matches(null))
                    .toList();

            assertThat(matching).isEmpty();
        }

        @ParameterizedTest(name = "no constant matches the malformed value ''{0}''")
        @ValueSource(strings = {"", "0", "9", "000", "9AB", "  9"})
        @DisplayName("no constant matches a value that is not exactly two characters")
        void noConstantMatchesAValueThatIsNotExactlyTwoCharacters(String malformed) {
            List<FileStatus> matching = Arrays.stream(FileStatus.values())
                    .filter(status -> status.matches(malformed))
                    .toList();

            assertThat(matching)
                    .as("silently reshaping an untrusted status is how a failure becomes a success")
                    .isEmpty();
        }
    }

    /**
     * {@code fromCode} is an exact value lookup and nothing more. It never returns the family constant, because
     * a family has no code to index.
     */
    @Nested
    @DisplayName("Exact code lookup: fromCode never returns the family")
    class ExactCodeLookup {

        @ParameterizedTest(name = "fromCode(''{1}'') finds {0}")
        @CsvSource({"SUCCESS,00", "SUCCESS_SECONDARY,04", "END_OF_FILE,10", "DUPLICATE_KEY,22",
                "RECORD_NOT_FOUND,23", "FILE_UNAVAILABLE,35"})
        void findsEachExactConstantByItsCode(String constantName, String code) {
            assertThat(FileStatus.fromCode(code)).contains(FileStatus.valueOf(constantName));
        }

        @ParameterizedTest(name = "fromCode(''{0}'') is empty even though the value is a family member")
        @ValueSource(strings = {"90", "99", "9A"})
        @DisplayName("the family constant is never returned, because it has no code to match")
        void neverReturnsTheFamilyConstantEvenForAFamilyValue(String ioStatus) {
            assertThat(FileStatus.fromCode(ioStatus))
                    .as("use tryClassify or classify when family matching is wanted")
                    .isEmpty();
        }

        @Test
        @DisplayName("fromCode(null) is an empty result")
        void returnsEmptyForNull() {
            assertThat(FileStatus.fromCode(null)).isEmpty();
        }

        @ParameterizedTest(name = "fromCode(''{0}'') is empty because the length is not two")
        @ValueSource(strings = {"", "0", "000", "00000", " 0 "})
        void returnsEmptyForAnyLengthOtherThanTwo(String malformed) {
            assertThat(FileStatus.fromCode(malformed)).isEmpty();
        }

        @ParameterizedTest(name = "fromCode(''{0}'') is empty because the code is unmapped")
        @ValueSource(strings = {"01", "46", "89", "AB", "  ", "0a"})
        void returnsEmptyForAnUnmappedTwoCharacterCode(String unmapped) {
            assertThat(FileStatus.fromCode(unmapped)).isEmpty();
        }
    }

    /**
     * {@code tryClassify} matches the six exact codes first and then the family. An empty result is meaningful
     * rather than an error signal: it is the corpus's own else branch, which the legacy programs answer by
     * abending.
     */
    @Nested
    @DisplayName("Lenient classification: tryClassify reports rather than throws")
    class LenientClassification {

        @ParameterizedTest(name = "tryClassify(''{1}'') is {0}")
        @CsvSource({"SUCCESS,00", "SUCCESS_SECONDARY,04", "END_OF_FILE,10", "DUPLICATE_KEY,22",
                "RECORD_NOT_FOUND,23", "FILE_UNAVAILABLE,35"})
        void classifiesEachExactCode(String constantName, String code) {
            assertThat(FileStatus.tryClassify(code)).contains(FileStatus.valueOf(constantName));
        }

        @ParameterizedTest(name = "tryClassify(''{0}'') is IO_ERROR")
        @ValueSource(strings = {"90", "91", "99", "9A", "9-", "9 "})
        @DisplayName("the family is recognised by its first byte alone")
        void classifiesTheFamilyByItsFirstByteAlone(String ioStatus) {
            assertThat(FileStatus.tryClassify(ioStatus)).contains(FileStatus.IO_ERROR);
        }

        @ParameterizedTest(name = "tryClassify(''{0}'') is an empty result, the corpus's else branch")
        @ValueSource(strings = {"01", "46", "89", "AB", "  "})
        void reportsAnUnrecognisedStatusAsAnEmptyResultRatherThanThrowing(String unrecognised) {
            assertThat(FileStatus.tryClassify(unrecognised)).isEmpty();
        }

        @Test
        @DisplayName("tryClassify(null) is an empty result and not an exception")
        void reportsNullAsAnEmptyResult() {
            assertThat(FileStatus.tryClassify(null)).isEmpty();
        }

        @ParameterizedTest(name = "tryClassify(''{0}'') is empty because the length is not two")
        @ValueSource(strings = {"", "0", "9", "000", "9AB"})
        void reportsMalformedLengthAsAnEmptyResult(String malformed) {
            assertThat(FileStatus.tryClassify(malformed)).isEmpty();
        }

        @Test
        @DisplayName("tryClassify agrees with fromCode on exact codes and diverges only on the family")
        void agreesWithFromCodeOnExactCodesAndDivergesOnlyOnTheFamily() {
            assertThat(FileStatus.tryClassify("23")).isEqualTo(FileStatus.fromCode("23"));
            assertThat(FileStatus.tryClassify("90")).contains(FileStatus.IO_ERROR);
            assertThat(FileStatus.fromCode("90"))
                    .as("the two methods differ by design, and only here")
                    .isEmpty();
        }
    }

    /**
     * {@code classify} is the strict form: it refuses what it cannot classify rather than absorbing it. The
     * refusal carries the offending value and cites the corpus's abend contract, and it wraps nothing, because
     * nothing was caught.
     */
    @Nested
    @DisplayName("Strict classification: classify refuses what it cannot classify")
    class StrictClassification {

        @ParameterizedTest(name = "classify(''{1}'') is {0}")
        @CsvSource({"SUCCESS,00", "SUCCESS_SECONDARY,04", "END_OF_FILE,10", "DUPLICATE_KEY,22",
                "RECORD_NOT_FOUND,23", "FILE_UNAVAILABLE,35"})
        void classifiesEachExactCode(String constantName, String code) {
            assertThat(FileStatus.classify(code)).isEqualTo(FileStatus.valueOf(constantName));
        }

        @ParameterizedTest(name = "classify(''{0}'') is IO_ERROR")
        @ValueSource(strings = {"90", "91", "99", "9A", "9-", "9 "})
        void classifiesTheFamilyByItsFirstByteAlone(String ioStatus) {
            assertThat(FileStatus.classify(ioStatus)).isEqualTo(FileStatus.IO_ERROR);
        }

        @Test
        @DisplayName("a numeric pair beginning with '9' is the family, because the predicate is an "
                + "inclusive or")
        void numericPairBeginningWithNineIsTheFamilyAndNotAnUnrecognisedValue() {
            assertThat(FileStatus.classify("90"))
                    .as("CBTRN02C.cbl:L715-L716 ors NOT NUMERIC with IO-STAT1 = '9'")
                    .isEqualTo(FileStatus.IO_ERROR);
        }

        @Test
        @DisplayName("classify(null) is refused with the documented message and no wrapped cause")
        void rejectsNullWithTheDocumentedMessageAndNoWrappedCause() {
            Throwable thrown = catchThrowable(() -> FileStatus.classify(null));

            assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasNoCause();
            assertThat(thrown.getMessage()).isEqualTo(expectedRejectionMessage("null"));
        }

        @ParameterizedTest(name = "classify(''{0}'') is refused with the documented message")
        @ValueSource(strings = {"01", "46", "89", "AB", "  ", "0a"})
        @DisplayName("an unmapped two character code is refused, quoting the offending value")
        void rejectsAnUnmappedCodeWithTheDocumentedMessageAndNoWrappedCause(String unmapped) {
            Throwable thrown = catchThrowable(() -> FileStatus.classify(unmapped));

            assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasNoCause();
            assertThat(thrown.getMessage()).isEqualTo(expectedRejectionMessage(unmapped));
        }

        @ParameterizedTest(name = "classify(''{0}'') is refused because the length is not two")
        @ValueSource(strings = {"", "0", "9", "000", "9AB", "00000"})
        void rejectsAnyLengthOtherThanTwoWithoutCoercingIt(String malformed) {
            Throwable thrown = catchThrowable(() -> FileStatus.classify(malformed));

            assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasNoCause();
            assertThat(thrown.getMessage()).isEqualTo(expectedRejectionMessage(malformed));
        }

        @Test
        @DisplayName("the refusal names the abend contract of the frozen corpus without implementing it")
        void rejectionMessageNamesTheAbendContractOfTheFrozenCorpus() {
            Throwable thrown = catchThrowable(() -> FileStatus.classify("01"));

            assertThat(thrown.getMessage())
                    .as("9999-ABEND-PROGRAM moves 999 into ABCODE at CBTRN02C.cbl:L710")
                    .contains("abending with code 999")
                    .contains("process return code 12")
                    .contains("app/cbl/CBTRN02C.cbl:L707-L711");
        }

        @Test
        @DisplayName("the refusal quotes the offending value in brackets, whitespace included")
        void rejectionMessageQuotesTheOffendingValueInBrackets() {
            Throwable thrown = catchThrowable(() -> FileStatus.classify("  "));

            assertThat(thrown.getMessage())
                    .as("a trimmed or normalised value in the message would hide the real input")
                    .contains("[  ]");
        }

        @Test
        @DisplayName("the refusal lists every recognised form so the caller can correct the input")
        void rejectionMessageListsEveryRecognisedForm() {
            Throwable thrown = catchThrowable(() -> FileStatus.classify("01"));

            assertThat(thrown.getMessage())
                    .contains("'00', '04', '10', '22', '23' and '35'")
                    .contains("the '9x' family identified by a first byte of '9'");
        }
    }

    /**
     * The four character {@code IO-STATUS-04} rendering, which reproduces {@code 9910-DISPLAY-IO-STATUS} at
     * {@code app/cbl/CBTRN02C.cbl:L714-L727} byte for byte. Both branches are asserted, because the parity gate
     * compares emitted lines against the legacy baseline and a one character difference is a gate failure.
     */
    @Nested
    @DisplayName("Four character rendering: 9910-DISPLAY-IO-STATUS, both branches")
    class FourCharacterRendering {

        @ParameterizedTest(name = "branch B renders ''{0}'' as ''{1}''")
        @CsvSource({"00,0000", "04,0004", "10,0010", "22,0022", "23,0023", "35,0035"})
        @DisplayName("branch B renders the six exact statuses as four zero padded characters")
        void branchBRendersTheSixExactStatusesAsFourZeroPaddedCharacters(String ioStatus, String expected) {
            assertThat(FileStatus.renderIoStatus04(ioStatus)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "branch B renders the numeric pair ''{0}'' as ''{1}''")
        @CsvSource({"01,0001", "11,0011", "46,0046", "80,0080", "89,0089"})
        @DisplayName("branch B renders any numeric pair whose first byte is not '9'")
        void branchBRendersAnyNumericPairNotBeginningWithNine(String ioStatus, String expected) {
            assertThat(FileStatus.renderIoStatus04(ioStatus)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "branch B output for ''{0}'' begins with two zeros and ends with it")
        @ValueSource(strings = {"00", "04", "10", "22", "23", "35", "01", "89"})
        @DisplayName("branch B sets '0000' and then overwrites characters three and four")
        void branchBAlwaysBeginsWithTwoZeros(String ioStatus) {
            assertThat(FileStatus.renderIoStatus04(ioStatus))
                    .as("MOVE '0000' then MOVE IO-STATUS TO IO-STATUS-04(3:2) at L723-L724")
                    .startsWith("00")
                    .endsWith(ioStatus)
                    .hasSize(LEGACY_RENDERED_WIDTH);
        }

        @ParameterizedTest(name = "branch A renders ''{0}'' as ''{1}''")
        @CsvSource({"90,9048", "91,9049", "99,9057", "9A,9065"})
        @DisplayName("branch A copies the first byte through and expands the second to three digits")
        void branchARendersTheFamilyAsFirstByteThenThreeDigitByteValue(String ioStatus, String expected) {
            assertThat(FileStatus.renderIoStatus04(ioStatus)).isEqualTo(expected);
        }

        @Test
        @DisplayName("branch A is taken for the numeric pair '90', so the result is 9048 and not 0090")
        void branchAIsTakenForANumericPairBeginningWithNine() {
            assertThat(FileStatus.renderIoStatus04("90"))
                    .as("the predicate at L715-L716 is an inclusive or, so the family byte wins")
                    .isEqualTo("9048")
                    .isNotEqualTo("0090");
        }

        @ParameterizedTest(name = "branch A renders the non numeric pair ''{0}'' as ''{1}''")
        @CsvSource({"A1,A049", "AB,A066", "0A,0065", "-1,-049"})
        @DisplayName("branch A copies a non digit first byte through unchanged, PIC 9 notwithstanding")
        void branchARendersANonNumericPairByCopyingTheFirstByteThrough(String ioStatus, String expected) {
            assertThat(FileStatus.renderIoStatus04(ioStatus)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "a second byte of value {0} expands to ''{1}''")
        @CsvSource({"0,9000", "1,9001", "9,9009", "10,9010", "99,9099", "100,9100", "255,9255"})
        @DisplayName("the second byte is expanded as an unsigned byte value across its whole range")
        void branchAExpandsTheSecondByteAsAnUnsignedByteValue(int byteValue, String expected) {
            String ioStatus = LEGACY_FAMILY_FIRST_BYTE + String.valueOf((char) byteValue);

            assertThat(FileStatus.renderIoStatus04(ioStatus))
                    .as("MOVE 0 TO TWO-BYTES-BINARY then MOVE IO-STAT2 TO TWO-BYTES-RIGHT at L718-L719")
                    .isEqualTo(expected);
        }

        @ParameterizedTest(name = "a second character of code point {0} is masked, rendering ''{1}''")
        @CsvSource({"256,9000", "20013,9045"})
        @DisplayName("the expansion masks the second character to its low order eight bits")
        void branchAMasksTheSecondByteToItsLowOrderEightBits(int codePoint, String expected) {
            String ioStatus = LEGACY_FAMILY_FIRST_BYTE + String.valueOf((char) codePoint);

            assertThat(FileStatus.renderIoStatus04(ioStatus))
                    .as("TWO-BYTES-RIGHT is the low order byte of a halfword, so PIC 999 always fits")
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("a non ASCII decimal digit takes branch A, which Character.isDigit would not")
        void nonAsciiDecimalDigitsTakeBranchA() {
            String arabicIndicZeroPair = "\u0660\u0660";

            assertThat(Character.isDigit(arabicIndicZeroPair.charAt(0)))
                    .as("the JDK calls U+0660 a digit, which is exactly why the class must not ask it")
                    .isTrue();
            assertThat(FileStatus.renderIoStatus04(arabicIndicZeroPair))
                    .as("the COBOL NUMERIC class test admits ASCII digits only")
                    .isEqualTo("\u0660096");
        }

        @Test
        @DisplayName("two spaces render through branch A, because spaces are not numeric")
        void rendersTwoSpacesAsTheSpaceByteExpansion() {
            assertThat(FileStatus.renderIoStatus04("  ")).isEqualTo(" 032");
        }

        @Test
        @DisplayName("null renders like an uninitialised IO-STATUS, which holds two spaces")
        void rendersNullAsTheUninitialisedStatusField() {
            assertThat(FileStatus.renderIoStatus04(null))
                    .as("a null sender has no COBOL counterpart, so it is the empty sender, padded")
                    .isEqualTo(" 032")
                    .hasSize(LEGACY_RENDERED_WIDTH);
        }

        @Test
        @DisplayName("the empty string renders like an uninitialised IO-STATUS")
        void rendersTheEmptyStringLikeAnUninitialisedStatusField() {
            assertThat(FileStatus.renderIoStatus04("")).isEqualTo(" 032");
        }

        @ParameterizedTest(name = "the short sender ''{0}'' is padded on the right, rendering ''{1}''")
        @CsvSource({"1,1032", "9,9032", "A,A032"})
        @DisplayName("a short sender is padded on the right with spaces, as MOVE to PIC X(02) does")
        void padsAShortSenderOnTheRightWithSpaces(String ioStatus, String expected) {
            assertThat(FileStatus.renderIoStatus04(ioStatus)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "the long sender ''{0}'' is truncated on the right, rendering ''{1}''")
        @CsvSource({"023,0002", "2301,0023", "9AB,9065", "00000,0000"})
        @DisplayName("a long sender is truncated on the right, as MOVE to PIC X(02) does")
        void truncatesALongSenderOnTheRight(String ioStatus, String expected) {
            assertThat(FileStatus.renderIoStatus04(ioStatus)).isEqualTo(expected);
        }

        @Test
        @DisplayName("control characters render without throwing, expanding the second byte")
        void rendersControlCharactersByExpandingTheSecondByte() {
            assertThat(FileStatus.renderIoStatus04("\t\n"))
                    .as("a diagnostic path that threw would destroy the root cause it exists to report")
                    .isEqualTo("\t010")
                    .hasSize(LEGACY_RENDERED_WIDTH);
        }

        @Test
        @DisplayName("a high bit pair renders with the first byte copied through and 255 expanded")
        void rendersHighBitBytesByCopyingTheFirstThroughAndExpandingTheSecond() {
            assertThat(FileStatus.renderIoStatus04("\u00ff\u00ff")).isEqualTo("\u00ff255");
        }

        @ParameterizedTest(name = "''{0}'' renders as exactly four characters")
        @ValueSource(strings = {"", " ", "  ", "0", "9", "00", "9A", "A1", "023", "00000", "-1", "%%",
                "  9", "\u00ff\u00ff", "\u0660\u0660", "\u4e2d\u6587"})
        @DisplayName("the rendering is always exactly four characters, whatever the input")
        void isAlwaysExactlyFourCharacters(String hostileValue) {
            assertThat(FileStatus.renderIoStatus04(hostileValue))
                    .as("IO-STATUS-04 is PIC 9 followed by PIC 999; any other width is a parity diff")
                    .hasSize(LEGACY_RENDERED_WIDTH);
        }

        @ParameterizedTest(name = "the expansion of ''{0}'' is composed of ASCII digits only")
        @ValueSource(strings = {"9A", "A1", "  ", "\u00ff\u00ff", "\u0660\u0660"})
        @DisplayName("the expansion uses ASCII digits, which is what Locale.ROOT formatting guarantees")
        void expandsToAsciiDigitsSoTheRenderingCannotVaryWithTheDefaultLocale(String ioStatus) {
            String expansion = FileStatus.renderIoStatus04(ioStatus).substring(1);

            assertThat(expansion).hasSize(3);
            assertThat(expansion.chars().allMatch(character -> character >= '0' && character <= '9'))
                    .as("a locale sensitive format could emit another numbering system here")
                    .isTrue();
        }

        @Test
        @DisplayName("the rendering is a pure function, returning the same value for repeated calls")
        void isAPureFunctionThatReturnsTheSameValueForRepeatedCalls() {
            String first = FileStatus.renderIoStatus04("9A");
            String second = FileStatus.renderIoStatus04("9A");

            assertThat(second).isEqualTo(first).isEqualTo("9065");
        }

        @Test
        @DisplayName("every exact constant's own code renders through branch B")
        void everyExactConstantCodeRendersThroughBranchB() {
            List<String> rendered = Arrays.stream(FileStatus.values())
                    .filter(FileStatus::isExactValue)
                    .map(status -> status.code().orElseThrow())
                    .map(FileStatus::renderIoStatus04)
                    .toList();

            assertThat(rendered).containsExactly("0000", "0004", "0010", "0022", "0023", "0035");
        }
    }

    /**
     * The log safe rendering, asserted <strong>separately</strong> from the byte parity rendering above.
     *
     * <p>The separation is the point of this class. The tests in
     * {@code FourCharacterRendering} pin the legacy behaviour: a malformed status has its first byte copied
     * through unaltered, so {@code "\t\n"} really must render as a raw tab followed by {@code 010}. That is
     * a parity contract measured byte for byte against the baseline and it is deliberately left exactly as
     * it was. The tests here pin the different guarantee that a diagnostic record needs - that nothing but
     * printable ASCII reaches it - and the two guarantees are proved against two different methods rather
     * than being traded off inside one.
     *
     * <p>The most important assertion in this class is
     * {@link #rendersEveryCorpusStatusIdenticallyToTheParityRenderer()}: for every status the corpus
     * actually produces the two renderings are character for character identical, so choosing the safe one
     * at a diagnostic site costs nothing and hides nothing.
     */
    @Nested
    @DisplayName("Log safe rendering: encoded for diagnostics, never traded against parity")
    class LogSafeRendering {

        @Test
        @DisplayName("every corpus status renders identically through both renderers, so safety is free")
        void rendersEveryCorpusStatusIdenticallyToTheParityRenderer() {
            List<String> corpusStatuses = List.of("00", "04", "10", "22", "23", "35", "90", "99", "9A");

            assertThat(corpusStatuses)
                    .as("if these ever diverged, the encoding would be changing legitimate diagnostics")
                    .allSatisfy(status -> assertThat(FileStatus.renderIoStatus04ForDiagnostics(status))
                            .isEqualTo(FileStatus.renderIoStatus04(status))
                            .hasSize(LEGACY_RENDERED_WIDTH));
        }

        @Test
        @DisplayName("a null status renders identically through both renderers")
        void rendersNullIdenticallyToTheParityRenderer() {
            assertThat(FileStatus.renderIoStatus04ForDiagnostics(null))
                    .isEqualTo(FileStatus.renderIoStatus04(null))
                    .isEqualTo(" 032");
        }

        @ParameterizedTest(name = "the malformed status ''{0}'' renders without any control character")
        @ValueSource(strings = {"\t\n", "\r\n", "\u0000\u0000", "\u001b[", "9\u0000", "\u00ff\u00ff",
                "\u007f\u007f", "\u0085\u0085", "\u2028\u2029", "\u4e2d\u6587"})
        @DisplayName("no malformed status can put a control or non-ASCII byte into a diagnostic")
        void encodesEveryNonPrintableByteSoNoDiagnosticCanBeForged(String malformedStatus) {
            String rendered = FileStatus.renderIoStatus04ForDiagnostics(malformedStatus);

            assertThat(rendered.chars().filter(character -> character < 0x20 || character > 0x7e).count())
                    .as("one raw control byte is enough to truncate a log record or begin a forged one")
                    .isZero();
            assertThat(rendered.lines()).as("a diagnostic must remain a single record").hasSize(1);
        }

        @ParameterizedTest(name = "the parity renderer keeps the raw byte that ''{0}'' carries")
        @ValueSource(strings = {"\t\n", "\r\n", "\u00ff\u00ff", "\u001b["})
        @DisplayName("the parity renderer is left untouched, which is why a separate one was needed")
        void leavesTheParityRendererCarryingTheRawByte(String malformedStatus) {
            assertThat(FileStatus.renderIoStatus04(malformedStatus)
                    .chars().filter(character -> character < 0x20 || character > 0x7e).count())
                    .as("this is the legacy behaviour and it is deliberately preserved, not repaired")
                    .isPositive();
        }

        @ParameterizedTest(name = "''{0}'' encodes to ''{1}''")
        @CsvSource({"'\t\n', '\\u0009010'", "'\r\n', '\\u000d010'", "'\u00ff\u00ff', '\\u00ff255'"})
        @DisplayName("the encoding is the four hexadecimal digit form, and the expansion is unchanged")
        void encodesTheFirstByteAndLeavesTheExpansionAlone(String malformedStatus, String expected) {
            assertThat(FileStatus.renderIoStatus04ForDiagnostics(malformedStatus)).isEqualTo(expected);
        }

        @Test
        @DisplayName("the encoding is injective: an encoded control byte cannot be forged by typing it")
        void encodesTheBackslashSoTheEncodingCannotBeForged() {
            String encodedRealTab = FileStatus.escapeForDiagnostics("\t");
            String encodedLiteralText = FileStatus.escapeForDiagnostics("\\u0009");

            assertThat(encodedRealTab).isEqualTo("\\u0009");
            assertThat(encodedLiteralText)
                    .as("without doubling the backslash a forged record would look like a sanitised one")
                    .isEqualTo("\\\\u0009")
                    .isNotEqualTo(encodedRealTab);
        }

        @ParameterizedTest(name = "''{0}'' passes through the encoder unaltered")
        @ValueSource(strings = {"00", "0000", "9048", " 032", "FILE STATUS IS: NNNN", "DALYTRAN", "OPEN",
                "~", " ", "!\"#$%&'()*+,-./0123456789:;<=>?@", "[]^_`{|}"})
        @DisplayName("printable ASCII is passed through unaltered, so legitimate text is never mangled")
        void passesPrintableAsciiThroughUnaltered(String printableValue) {
            assertThat(FileStatus.escapeForDiagnostics(printableValue)).isEqualTo(printableValue);
        }

        @Test
        @DisplayName("the encoder is total: null yields the empty string, never the four characters null")
        void encodesNullAsTheEmptyStringRatherThanTheWordNull() {
            assertThat(FileStatus.escapeForDiagnostics(null))
                    .as("the text 'null' would be indistinguishable from a status that read 'nu'")
                    .isEmpty();
            assertThat(FileStatus.escapeForDiagnostics("")).isEmpty();
        }

        @Test
        @DisplayName("the encoding loses no information, so no root cause is obscured")
        void losesNoInformationSoNoRootCauseIsObscured() {
            assertThat(FileStatus.escapeForDiagnostics("a\tb\nc"))
                    .as("an encoding, not a filter: every byte remains determinable from the output")
                    .isEqualTo("a\\u0009b\\u000ac")
                    .doesNotContain("\t")
                    .doesNotContain("\n");
        }

        @Test
        @DisplayName("neither renderer nor encoder throws, whatever it is given")
        void neitherRendererNorEncoderEverThrows() {
            List<String> hostileValues = Arrays.asList(null, "", " ", "\u0000", "\uffff",
                    "\ud83d\ude00", "0".repeat(1024));

            assertThat(hostileValues).allSatisfy(hostileValue -> {
                assertThat(catchThrowable(() -> FileStatus.renderIoStatus04ForDiagnostics(hostileValue)))
                        .as("a diagnostic path that threw would destroy the root cause it reports")
                        .isNull();
                assertThat(catchThrowable(() -> FileStatus.escapeForDiagnostics(hostileValue))).isNull();
            });
        }
    }

    /**
     * The preserved legacy quirk. {@code DISPLAY 'literal' identifier} concatenates its operands with no
     * separator, and the literal at {@code app/cbl/CBTRN02C.cbl:L721} and {@code app/cbl/CBTRN02C.cbl:L725}
     * already contains the placeholder text {@code NNNN}, so the real four characters are appended after it
     * rather than substituted into it. The tidier looking form is a parity diff and must never be produced.
     */
    @Nested
    @DisplayName("Preserved legacy quirk: the NNNN placeholder stays and nothing separates it")
    class PreservedDisplayQuirk {

        @Test
        @DisplayName("the prefix is the twenty character legacy literal with its placeholder intact")
        void displayMessagePrefixIsTheTwentyCharacterLegacyLiteralWithItsPlaceholderIntact() {
            assertThat(FileStatus.DISPLAY_MESSAGE_PREFIX)
                    .as("transcribed from CBTRN02C.cbl:L721 and L725")
                    .isEqualTo(LEGACY_DISPLAY_LITERAL)
                    .hasSize(20)
                    .endsWith("NNNN")
                    .contains("IS: NNNN")
                    .doesNotContain("IS:  ");
        }

        @Test
        @DisplayName("the line for '23' is FILE STATUS IS: NNNN0023 and not the repaired form")
        void theLegacyLineForRecordNotFoundKeepsThePlaceholderAndAppendsTheRendering() {
            String line = FileStatus.DISPLAY_MESSAGE_PREFIX + FileStatus.renderIoStatus04("23");

            assertThat(line)
                    .as("removing the placeholder or substituting into it is a byte level diff")
                    .isEqualTo("FILE STATUS IS: NNNN0023")
                    .isNotEqualTo("FILE STATUS IS: 0023");
        }

        @Test
        @DisplayName("the rendering carries no message text, so a caller cannot double prefix it")
        void theRenderingCarriesNoMessageTextSoItCannotDoublePrefix() {
            String rendered = FileStatus.renderIoStatus04("23");

            assertThat(rendered).isEqualTo("0023")
                    .doesNotContain("FILE STATUS")
                    .doesNotContain("NNNN")
                    .doesNotContain(":");

            assertThat(FileStatus.DISPLAY_MESSAGE_PREFIX + rendered)
                    .as("the whole of the wording lives in the prefix, exactly once")
                    .containsOnlyOnce("FILE STATUS")
                    .containsOnlyOnce("NNNN");
        }

        @Test
        @DisplayName("nothing separates the prefix from the rendering")
        void thereIsNoSeparatorBetweenThePrefixAndTheRendering() {
            String line = FileStatus.DISPLAY_MESSAGE_PREFIX + FileStatus.renderIoStatus04("23");

            assertThat(line.charAt(FileStatus.DISPLAY_MESSAGE_PREFIX.length()))
                    .as("the first rendered character follows the placeholder immediately")
                    .isEqualTo('0');
            assertThat(line).doesNotContain("NNNN ").doesNotContain("NNNN:");
        }

        @ParameterizedTest(name = "the line for ''{0}'' is ''{1}''")
        @CsvSource({"00,FILE STATUS IS: NNNN0000", "04,FILE STATUS IS: NNNN0004",
                "10,FILE STATUS IS: NNNN0010", "22,FILE STATUS IS: NNNN0022",
                "23,FILE STATUS IS: NNNN0023", "35,FILE STATUS IS: NNNN0035"})
        @DisplayName("every exact status produces a twenty four character line of the same shape")
        void theLegacyLineForEveryExactStatusFollowsTheSameShape(String ioStatus, String expectedLine) {
            String line = FileStatus.DISPLAY_MESSAGE_PREFIX + FileStatus.renderIoStatus04(ioStatus);

            assertThat(line).isEqualTo(expectedLine).hasSize(24);
        }

        @Test
        @DisplayName("the line for a '9x' status expands the second byte inside the same shape")
        void theLegacyLineForAFamilyStatusExpandsTheSecondByte() {
            String line = FileStatus.DISPLAY_MESSAGE_PREFIX + FileStatus.renderIoStatus04("9A");

            assertThat(line).isEqualTo("FILE STATUS IS: NNNN9065").hasSize(24);
        }
    }

    /**
     * The width constants the type exposes, each asserted against the {@code PIC} clause that fixes it, and the
     * widths it deliberately does not expose.
     */
    @Nested
    @DisplayName("Width constants: two and four, and no timestamp geometry")
    class WidthConstants {

        @Test
        @DisplayName("STATUS_CODE_LENGTH is two, as the IO-STATUS group declares")
        void statusCodeLengthIsTwoAsTheIoStatusGroupDeclares() {
            assertThat(FileStatus.STATUS_CODE_LENGTH)
                    .as("two PIC X items at CBTRN02C.cbl:L131-L133, and LK-M03B-RC PIC X(02)")
                    .isEqualTo(LEGACY_STATUS_FIELD_WIDTH);
        }

        @Test
        @DisplayName("RENDERED_STATUS_LENGTH is four, as PIC 9 followed by PIC 999 declares")
        void renderedStatusLengthIsFourAsPicNinePlusPicNineNineNineDeclares() {
            assertThat(FileStatus.RENDERED_STATUS_LENGTH)
                    .as("IO-STATUS-04 is PIC 9 plus PIC 999 at CBTRN02C.cbl:L138-L140")
                    .isEqualTo(LEGACY_RENDERED_WIDTH);
        }

        @Test
        @DisplayName("IO_ERROR_FIRST_BYTE is the '9' of the guard predicate")
        void familyFirstByteIsTheNineOfTheGuardPredicate() {
            assertThat(FileStatus.IO_ERROR_FIRST_BYTE)
                    .as("IO-STAT1 = '9' at CBTRN02C.cbl:L716, replicated at eight sites")
                    .isEqualTo(LEGACY_FAMILY_FIRST_BYTE);
        }

        @Test
        @DisplayName("the only public integer constants are the two widths")
        void theOnlyPublicIntegerConstantsAreTheTwoWidths() {
            assertThat(publicIntConstantValues())
                    .as("a third width here would mean another artefact's geometry had leaked in")
                    .containsExactly(LEGACY_STATUS_FIELD_WIDTH, LEGACY_RENDERED_WIDTH);
        }

        @Test
        @DisplayName("no constant carries the twenty six byte timestamp width, which is another concern")
        void exposesNoTimestampWidthBecauseTheTwentySixByteGeometryIsNotThisTypesConcern() {
            assertThat(publicIntConstantValues())
                    .as("the DB2 redefinition at CBTRN02C.cbl:L159-L175 sums to 26 and belongs elsewhere")
                    .doesNotContain(LEGACY_DB2_TIMESTAMP_WIDTH);
        }

        @Test
        @DisplayName("every exact code is exactly STATUS_CODE_LENGTH characters wide")
        void everyExactCodeIsAsWideAsTheConstantSays() {
            List<Integer> widths = Arrays.stream(FileStatus.values())
                    .filter(FileStatus::isExactValue)
                    .map(status -> status.code().orElseThrow().length())
                    .distinct()
                    .toList();

            assertThat(widths).containsExactly(FileStatus.STATUS_CODE_LENGTH);
        }
    }

}
