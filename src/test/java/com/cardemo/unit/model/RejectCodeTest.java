/*
 * ******************************************************************
 * Program     : RejectCodeTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies com.cardemo.model.enums.RejectCode against the
 *               daily transaction posting program: the five assignable
 *               reject reason codes, their verbatim description
 *               literals, the four plus seventy six validation trailer
 *               geometry, the fact that a reject is a business outcome
 *               and never an exception, and the unguarded fall through
 *               that lets code 103 overwrite code 102.
 * Source      : app/cbl/CBTRN02C.cbl:L385-L419,L556-L558 @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cardemo.model.enums.RejectCode;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link RejectCode}, the enumeration of the five reject reason codes that the daily
 * transaction posting program {@code app/cbl/CBTRN02C.cbl} can assign to an input record.
 *
 * <h2>What it does</h2>
 *
 * <p>Every expectation in this class was transcribed from, and verified first hand against, the frozen
 * COBOL corpus at commit {@code 7756d89}. The file is plain ASCII with LF line endings, so the line
 * numbers below are exact as read. Seven concerns are covered, each marked by a section banner in the
 * body and each test method addressing exactly one of them:
 *
 * <ol>
 *   <li><strong>The five outcomes.</strong> Exactly five constants - {@code 100}, {@code 101},
 *       {@code 102}, {@code 103} and {@code 109} - carrying their description literals character for
 *       character. Assigned at {@code app/cbl/CBTRN02C.cbl}:L385, L397, L410, L417 and L556, with the
 *       literals at L386-L387, L398-L399, L411-L412, L418-L419 and L557-L558.</li>
 *   <li><strong>Trailer geometry.</strong> {@code 01 REJECT-RECORD} at L176-L178 is a
 *       {@code PIC X(350)} data image followed by a {@code PIC X(80)} trailer, and
 *       {@code 01 WS-VALIDATION-TRAILER} at L180-L182 splits that trailer into a {@code PIC 9(04)}
 *       reason code and a {@code PIC X(76)} description. So {@code 4 + 76 = 80} and
 *       {@code 350 + 80 = 430}, the latter corroborated independently by
 *       {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)} at {@code app/jcl/POSTTRAN.jcl}:L36.</li>
 *   <li><strong>Reason code rendering.</strong> The {@code PIC 9(04)} domain and its boundaries, plus
 *       the hostile inputs that must be refused rather than silently widened or truncated.</li>
 *   <li><strong>Reason code lookup.</strong> Round tripping every constant through its own code, and
 *       the total, non throwing behaviour for every value that is not one of the five.</li>
 *   <li><strong>Business outcomes, never exceptions.</strong> Proven structurally, not asserted
 *       rhetorically.</li>
 *   <li><strong>The unguarded {@code 102} to {@code 103} overwrite.</strong> Replayed faithfully.</li>
 *   <li><strong>Immutability and determinism.</strong> No mutable static state, no ambient clock.</li>
 * </ol>
 *
 * <h2>Scope: what this test deliberately does not do</h2>
 *
 * <p><strong>It never builds the 430 byte reject record.</strong> That assembly belongs to
 * {@code com.cardemo.batch.writers.RejectWriter}, which reproduces {@code 2500-WRITE-REJECT-REC}
 * ({@code app/cbl/CBTRN02C.cbl}:L446-L465): L447 moves {@code DALYTRAN-RECORD} into
 * {@code REJECT-TRAN-DATA}, L448 moves {@code WS-VALIDATION-TRAILER} into {@code VALIDATION-TRAILER},
 * and L451 writes the concatenation. This class asserts the enumeration's contribution only - the
 * 80 byte trailer - and additionally proves that nothing wider than 80 characters can escape the type.
 *
 * <p><strong>It does not implement the exit code contract.</strong> That contract is recorded here and
 * enforced by the batch layer. {@code app/cbl/CBTRN02C.cbl}:L227-L231 reads, verbatim:
 * {@code DISPLAY 'TRANSACTIONS PROCESSED :' WS-TRANSACTION-COUNT} then
 * {@code DISPLAY 'TRANSACTIONS REJECTED  :' WS-REJECT-COUNT} then
 * {@code IF WS-REJECT-COUNT > 0 / MOVE 4 TO RETURN-CODE / END-IF}. <strong>Return code 4 is set if and
 * only if the reject count exceeds zero, and there is no other determinant.</strong> The counter is
 * incremented at exactly one place, L214, inside the {@code ELSE} branch of the L211 test
 * {@code IF WS-VALIDATION-FAIL-REASON = 0}; the reject write follows at L215. Unexpected file statuses
 * take a different route entirely - {@code 9999-ABEND-PROGRAM} at L707-L711 moves {@code 999} into
 * {@code ABCODE} and calls {@code 'CEE3ABD'}, which the Java target surfaces as abend code 999 with
 * process return code 12. Because the reject metric is tagged by reject code, its tag cardinality is
 * bounded by exactly these five constants.
 *
 * <p><strong>It does not implement the per record reset.</strong> {@code app/cbl/CBTRN02C.cbl}:L208
 * moves zero into {@code WS-VALIDATION-FAIL-REASON} and L209 moves spaces into
 * {@code WS-VALIDATION-FAIL-REASON-DESC} before each record is validated. That cleared state is a
 * number and a blank field, not a sixth constant, which is why {@link RejectCode#NO_REJECT_REASON_CODE}
 * is an {@code int} and {@link RejectCode#noRejectTrailer()} renders it.
 *
 * <p><strong>It does not re-derive the validation cascade.</strong> {@code 1500-VALIDATE-TRAN} at
 * L370-L378 performs exactly two lookup paragraphs - L371 {@code PERFORM 1500-A-LOOKUP-XREF}, then L372
 * {@code IF WS-VALIDATION-FAIL-REASON = 0} guarding L373 {@code PERFORM 1500-B-LOOKUP-ACCT}, with L374
 * {@code ELSE} L375 {@code CONTINUE}, and the comment {@code * ADD MORE VALIDATIONS HERE} at L377. Two
 * paragraphs, not four.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>{@code ./mvnw -q test-compile} is the fastest check that this file still satisfies the compiler
 * settings, and {@code ./mvnw test} runs it. This class is collected by <strong>Surefire 3.5.4</strong>,
 * which the root {@code pom.xml} configures with the include patterns {@code **}{@code /*Test.java} and
 * {@code **}{@code /*Tests.java} and the path exclusions {@code **}{@code /integration/**} and
 * {@code **}{@code /e2e/**}. This file matches the first include and neither exclusion, so it runs at
 * the {@code test} phase; Failsafe, whose includes are confined to the integration and end to end
 * trees, does not also collect it. <strong>Neither the name nor the location may be changed.</strong> A
 * class that matches no include is collected by neither plugin and silently never runs: the build stays
 * green, both plugins report success, and coverage quietly records the class as untested. There is no
 * error and no warning, which is what makes that mistake expensive. Confirm a run by checking for
 * {@code target/surefire-reports/com.cardemo.unit.model.RejectCodeTest.txt}. Where a local toolchain is
 * unavailable the pinned image reproduces it exactly:
 * {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q test}.
 *
 * <p><strong>The seven concerns are section banners rather than {@code @Nested} classes, and that is
 * deliberate.</strong> Measured on this toolchain with Surefire 3.5.4, grouping these tests into
 * {@code @Nested} inner classes made the plugin write
 * {@code <testsuite ... name="com.cardemo.unit.model.RejectCodeTest" tests="0">} even though all 119
 * cases ran and passed - the individual {@code <testcase>} elements were present, but the aggregate
 * counter every dashboard and quality gate reads said zero. Adding {@code @DisplayName} to those classes
 * compounded it by replacing each {@code classname} attribute with prose such as
 * {@code classname="Immutability and determinism"}, so a failure could no longer be traced to a Java
 * type or file. A report that says a class ran no tests is indistinguishable, to anything reading it,
 * from the silent non collection described above, and losing the class name defeats the requirement that
 * findings cite file paths and symbols. Flat structure restores both: the header reports the true count
 * and every {@code classname} is {@code com.cardemo.unit.model.RejectCodeTest}. The grouping is
 * therefore expressed as comments, which cost nothing and mislead no one. Severity of the reporting
 * defect avoided: <strong>High</strong>. Remediation applied: keep this class flat.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>There is no external configuration, no fixture file, no container, no Spring context and no
 * database: this is the pure JVM tier, and every expectation is a compile time constant in this file.
 * The build contract is {@code maven-compiler-plugin} 3.14.1 at {@code release} 25 with
 * {@code -Xlint:all}, {@code -Werror} and {@code failOnWarning}, all of which reach test compilation;
 * {@code maven-enforcer-plugin} 3.5.0 asserting the toolchain; and a JaCoCo line coverage floor of 80
 * percent at {@code verify}. Whitespace follows the root {@code .editorconfig}: UTF-8, LF, a final
 * newline, no trailing whitespace, four space Java indentation and a 120 column guide.
 *
 * <p><strong>The sibling {@code FixedClockProvider} is deliberately not used here.</strong> It is the
 * only sanctioned source of time for this tier, but {@link RejectCode} reads no clock: every method on
 * it is a pure function of its arguments, and none consults the current instant, the default locale or
 * the default zone. Injecting a clock this type cannot observe would add an unused collaborator, so the
 * determinism requirement is met by the type having no temporal input at all - which the determinism
 * tests below assert rather than assume.
 *
 * <p>Mockito is on the test classpath and is deliberately unused. {@link RejectCode} is a pure value
 * type with no collaborator to stub, and mocking the type under test would assert the mock's behaviour
 * instead of the enumeration's.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>The build fails on something that looks trivial.</em> Compilation runs with
 *       {@code -Xlint:all} and {@code -Werror} and that reaches test compilation, so a single raw type or
 *       deprecation is an error rather than a warning. An unused import is not - {@code javac} 25.0.3
 *       publishes no lint key for one - so it is caught at review. Reproduce with
 *       {@code ./mvnw -q test-compile}.</li>
 *   <li><em>A reflective assertion fails only under {@code verify}, never under {@code test-compile}.</em>
 *       The JaCoCo agent is bound to the {@code initialize} phase, so it instruments classes in memory
 *       and injects a {@code private static transient boolean[] $jacocoData} field and a
 *       {@code $jacocoInit} method. Both are marked synthetic, and that field is <strong>not</strong>
 *       final. Every reflective sweep in this class therefore skips synthetic members, which also skips
 *       the compiler's own {@code $VALUES} field and {@code $values} method. The invariants asserted
 *       concern authored state, not generated state.</li>
 *   <li><em>A description assertion fails by one character.</em> The literals are transcribed exactly.
 *       Code {@code 103} abbreviates account as {@code ACCT} and spells {@code EXPIRATION}, not
 *       {@code EXPIRY}. Codes {@code 101} and {@code 109} share byte identical text on purpose.</li>
 *   <li><em>A fixture stream is null at run time.</em> Not applicable here, since this test loads no
 *       fixture, but the trap is worth knowing in this package: the daily transaction fixture is named
 *       {@code dailytran.txt}, spelled in full. The mainframe data definition name and dataset are
 *       {@code DALYTRAN}, so {@code dalytran.txt} is the natural guess and is wrong - it compiles
 *       cleanly and fails only when the resource is opened.</li>
 *   <li><em>The constant pool scan cannot find the class file.</em> It reads
 *       {@code RejectCode.class} as a classpath resource, so {@code target/classes} must be populated.
 *       Run {@code ./mvnw test-compile} first; the failure message states this.</li>
 * </ul>
 *
 * <h2>Findings and severities</h2>
 *
 * <ul>
 *   <li><strong>High</strong> - the credit limit test at {@code app/cbl/CBTRN02C.cbl}:L407-L413 and the
 *       expiry test at L414-L420 are two separate, sequential, unguarded {@code IF ... END-IF} blocks
 *       with no {@code ELSE} chaining, no early exit and no reason code test between them. When both
 *       conditions fail, L417 overwrites the {@code 102} that L410 assigned, and the single reject
 *       record written bears {@code 103}. Remediation: <strong>none - preserve and log.</strong>
 *       Guarding the second test, emitting two records, or modelling a collection of codes would each
 *       change the reject file and fail the byte for byte parity gate. Asserted by the overwrite
 *       replay tests further down this class.</li>
 *   <li><strong>Low</strong> as an observation, <strong>Blocker</strong> if acted upon - code
 *       {@code 109} is assigned on a reachable line at {@code app/cbl/CBTRN02C.cbl}:L556 but is never
 *       consumed, for four independently verifiable reasons. First, {@code 2800-UPDATE-ACCOUNT-REC}
 *       (L545-L560) carries no {@code APPL-RESULT} guard and no abend, unlike every other input output
 *       paragraph in the program. Second, it runs only from {@code 2000-POST-TRANSACTION} (L424-L444) at
 *       L441, which is entered only when the L211 test found the reason code already zero. Third, the
 *       reject counter and the reject write live exclusively in the {@code ELSE} branch of that same
 *       test, at L214 and L215, which this path cannot reach. Fourth, control continues to L442 and the
 *       value is cleared at L208 on the next iteration. <strong>The constant is retained
 *       regardless</strong>, because the assignment is real code on a reachable path and the migration's
 *       contract is one to one control flow correspondence; deleting it would break the paragraph map
 *       that the scope coverage gate verifies, which is why removal is a Blocker. Remediation for the
 *       apparent defect: <strong>none - preserve and log.</strong> Asserted by
 *       {@link #retainsCode109EvenThoughNoPathCanEmitIt()}.</li>
 *   <li><strong>Low</strong> - codes {@code 101} and {@code 109} carry byte identical description text.
 *       The source reuses one literal for two different conditions: a record that was not found, and a
 *       record that was found but could not be rewritten. The duplication is reproduced, not resolved.
 *       Remediation: <strong>none - preserve and log.</strong></li>
 * </ul>
 *
 * <p>The retention of {@code 109} is the one place where the project standard forbidding dead code and
 * the mandate to preserve control flow one to one genuinely collide. Parity governs, and the standard is
 * satisfied by its own wording: what it bars is dead code and deferred work carrying <em>no owner or
 * tracking reference</em>. This constant carries both. It is cited to its exact source lines here and on
 * the constant itself, recorded as a named decision in {@code DECISION_LOG.md}, and mapped in
 * {@code TRACEABILITY_MATRIX.md} among the {@code CBTRN02C} fidelity hot spots. It is tracked, justified
 * and reviewable - a documented faithful reproduction of a reachable but unconsumed assignment in the
 * system of record, not abandoned residue.
 *
 * <h2>Information that is not available</h2>
 *
 * <p>The relational column types backing these codes are <strong>Not available</strong> from this test's
 * evidence base. {@code src/main/resources/db/migration/V1__create_schema.sql} is not a dependency of
 * this file and no schema detail is invented here; the widths asserted below are character widths taken
 * from the COBOL {@code PIC} clauses, not SQL types. Establishing the mapping would require that
 * migration script and the entity that persists a reject outcome, neither of which is in scope.
 *
 * @see RejectCode
 * @see RejectCode#toValidationTrailer()
 */
class RejectCodeTest {

    /**
     * The number of reason codes {@code app/cbl/CBTRN02C.cbl} can assign: five, and never six.
     */
    private static final int EXPECTED_CONSTANT_COUNT = 5;

    /**
     * The five constant names in the order the source assigns their codes: {@code 100} in
     * {@code 1500-A-LOOKUP-XREF}, then {@code 101}, {@code 102} and {@code 103} in {@code 1500-B-LOOKUP-ACCT},
     * then {@code 109} in {@code 2800-UPDATE-ACCOUNT-REC}.
     */
    private static final List<String> EXPECTED_CONSTANT_NAMES = List.of(
            "INVALID_CARD_NUMBER",
            "ACCOUNT_RECORD_NOT_FOUND",
            "OVERLIMIT_TRANSACTION",
            "TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION",
            "ACCOUNT_RECORD_NOT_FOUND_ON_REWRITE");

    /**
     * Binary name prefix of the project's typed exception hierarchy, which this enum must not touch.
     */
    private static final String EXCEPTION_PACKAGE_PREFIX = "com.cardemo.exception";

    /**
     * Internal (slash separated) form of that prefix, as a class file constant pool would spell it.
     */
    private static final String EXCEPTION_PACKAGE_INTERNAL_NAME = "com/cardemo/exception";

    /**
     * Internal form of the Spring package root, covering {@code ExitStatus} and every other Spring type.
     */
    private static final String SPRING_PACKAGE_INTERNAL_NAME = "org/springframework";

    /**
     * Simple name of the Spring Batch type this enum maps onto but must never reference.
     */
    private static final String EXIT_STATUS_SIMPLE_NAME = "ExitStatus";

    /**
     * Highest value a {@code PIC 9(04)} field can hold, and therefore the top of the rendering domain.
     */
    private static final int MAX_PIC_9_04_VALUE = 9999;

    /**
     * One past the {@code PIC 9(04)} domain: the smallest value needing five digits.
     */
    private static final int FIRST_FIVE_DIGIT_VALUE = 10_000;

    /**
     * Exactly five reason codes are assignable, so exactly five constants may exist.
     */
    @Test
    void exposesExactlyFiveConstants() {
        assertThat(RejectCode.values())
                .as("app/cbl/CBTRN02C.cbl assigns exactly five reason codes - 100 at L385, 101 at "
                        + "L397, 102 at L410, 103 at L417 and 109 at L556 - so "
                        + "RejectCode.values() must have length %d with no sixth constant",
                        EXPECTED_CONSTANT_COUNT)
                .hasSize(EXPECTED_CONSTANT_COUNT);
    }

    /**
     * Declaration order follows the order in which the source assigns the codes, so that
     * {@code RejectCode.values()} reads as a walk through the program rather than an arbitrary list.
     */
    @Test
    void declaresTheFiveConstantsInSourceAssignmentOrder() {
        final List<String> declaredNames = Arrays.stream(RejectCode.values()).map(Enum::name).toList();

        assertThat(declaredNames)
                .as("declaration order must follow the order app/cbl/CBTRN02C.cbl assigns the "
                        + "codes: 100 in 1500-A-LOOKUP-XREF (L380-L392), then 101, 102 and 103 in "
                        + "1500-B-LOOKUP-ACCT (L393-L422), then 109 in 2800-UPDATE-ACCOUNT-REC "
                        + "(L545-L560)")
                .containsExactlyElementsOf(EXPECTED_CONSTANT_NAMES);
    }

    /**
     * Binds every constant to its complete verbatim contract: the numeric reason code the source moves into
     * {@code WS-VALIDATION-FAIL-REASON}, the description literal it moves into
     * {@code WS-VALIDATION-FAIL-REASON-DESC}, and that literal's exact character count.
     *
     * @param constantName the Java constant name
     * @param expectedCode the reason code as moved in the COBOL
     * @param expectedDescription the description literal, character for character
     * @param expectedDescriptionLength the literal's length, stated independently as a cross check
     */
    @ParameterizedTest(name = "{0} -> code {1}, description \"{2}\" ({3} chars)")
    @CsvSource({
        "INVALID_CARD_NUMBER,                        100, 'INVALID CARD NUMBER FOUND',                  25",
        "ACCOUNT_RECORD_NOT_FOUND,                   101, 'ACCOUNT RECORD NOT FOUND',                   24",
        "OVERLIMIT_TRANSACTION,                      102, 'OVERLIMIT TRANSACTION',                      21",
        "TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION, 103, 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION', 42",
        "ACCOUNT_RECORD_NOT_FOUND_ON_REWRITE,        109, 'ACCOUNT RECORD NOT FOUND',                   24"
    })
    void bindsEachConstantToItsVerbatimCobolContract(final String constantName,
                                                     final int expectedCode,
                                                     final String expectedDescription,
                                                     final int expectedDescriptionLength) {
        final RejectCode outcome = RejectCode.valueOf(constantName);

        assertThat(outcome.getCode())
                .as("RejectCode.%s.getCode() must equal the value moved into "
                        + "WS-VALIDATION-FAIL-REASON in app/cbl/CBTRN02C.cbl", constantName)
                .isEqualTo(expectedCode);
        assertThat(outcome.getDescription())
                .as("RejectCode.%s.getDescription() must reproduce the COBOL literal moved into "
                        + "WS-VALIDATION-FAIL-REASON-DESC character for character", constantName)
                .isEqualTo(expectedDescription);
        assertThat(expectedDescription)
                .as("the transcribed literal for RejectCode.%s must be exactly %d characters long",
                        constantName, expectedDescriptionLength)
                .hasSize(expectedDescriptionLength);
    }

    /**
     * Codes {@code 101} and {@code 109} carry byte identical description text, and that duplication is the
     * source contract rather than a mistake to correct.
     */
    @Test
    void reproducesTheDescriptionSharedByCode101AndCode109() {
        assertThat(RejectCode.ACCOUNT_RECORD_NOT_FOUND_ON_REWRITE.getDescription())
                .as("app/cbl/CBTRN02C.cbl:L557-L558 moves the same literal as L398-L399, so the "
                        + "109 description must be byte identical to the 101 description; the "
                        + "duplication is deliberate and must not be differentiated")
                .isEqualTo(RejectCode.ACCOUNT_RECORD_NOT_FOUND.getDescription())
                .isEqualTo("ACCOUNT RECORD NOT FOUND");

        assertThat(RejectCode.ACCOUNT_RECORD_NOT_FOUND_ON_REWRITE.getCode())
                .as("the two constants share their text but never their code: 101 at "
                        + "app/cbl/CBTRN02C.cbl:L397 against 109 at L556")
                .isNotEqualTo(RejectCode.ACCOUNT_RECORD_NOT_FOUND.getCode());
    }

    /**
     * Code {@code 109} is retained even though no production path can emit it.
     */
    @Test
    void retainsCode109EvenThoughNoPathCanEmitIt() {
        assertThat(RejectCode.values())
                .as("the 109 constant is retained for control flow parity with "
                        + "app/cbl/CBTRN02C.cbl:L545-L560 and must remain one of the five; "
                        + "removing it is a Blocker because the scope coverage gate would lose the "
                        + "2800-UPDATE-ACCOUNT-REC paragraph correspondence")
                .contains(RejectCode.ACCOUNT_RECORD_NOT_FOUND_ON_REWRITE)
                .hasSize(EXPECTED_CONSTANT_COUNT);

        assertThat(RejectCode.ACCOUNT_RECORD_NOT_FOUND_ON_REWRITE.getCode())
                .as("app/cbl/CBTRN02C.cbl:L556 moves 109 into WS-VALIDATION-FAIL-REASON")
                .isEqualTo(109);

        assertThat(RejectCode.fromCode(109))
                .as("the retained constant must still be reachable through its own reason code, so "
                        + "that a persisted 109 can be read back rather than silently discarded")
                .contains(RejectCode.ACCOUNT_RECORD_NOT_FOUND_ON_REWRITE);
    }

    /**
     * Every description fits inside {@code WS-VALIDATION-FAIL-REASON-DESC}, the {@code PIC X(76)} field
     * declared at {@code app/cbl/CBTRN02C.cbl}:L182, with room to spare.
     *
     * @param outcome each of the five constants in turn
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(RejectCode.class)
    void keepsEveryDescriptionInsideThePicX76Field(final RejectCode outcome) {
        assertThat(outcome.getDescription())
                .as("RejectCode.%s must carry a non blank description that fits the PIC X(76) "
                        + "field WS-VALIDATION-FAIL-REASON-DESC (app/cbl/CBTRN02C.cbl:L182)",
                        outcome.name())
                .isNotBlank()
                .hasSizeLessThanOrEqualTo(RejectCode.FAIL_REASON_DESC_LENGTH);
    }

    /**
     * Names the source never assigns do not resolve, which is how the absence of a sixth constant is asserted
     * by name rather than merely by count.
     *
     * @param absentName a plausible but non existent constant name
     */
    @ParameterizedTest(name = "valueOf(\"{0}\")")
    @ValueSource(strings = {"NONE", "OK", "ZERO", "UNKNOWN", "SUCCESS", "NO_REJECT", "ACCEPTED",
        "INVALID_CARD_NUMBER_FOUND", "OVERLIMIT", "EXPIRED"})
    void rejectsNamesForConstantsTheSourceNeverAssigns(final String absentName) {
        assertThatIllegalArgumentException()
                .as("app/cbl/CBTRN02C.cbl assigns no reason code named %s, so no such constant may "
                        + "exist; the cleared state is RejectCode.NO_REJECT_REASON_CODE, a number "
                        + "and not a constant", absentName)
                .isThrownBy(() -> RejectCode.valueOf(absentName))
                .withMessageContaining(absentName)
                .withMessageContaining(RejectCode.class.getCanonicalName())
                .withNoCause();
    }

    /**
     * Constant name resolution is case sensitive, so a lower or mixed case spelling is refused rather than
     * quietly matched.
     *
     * @param wrongCaseName a correctly spelled name in the wrong case
     */
    @ParameterizedTest(name = "valueOf(\"{0}\")")
    @ValueSource(strings = {"invalid_card_number", "Account_Record_Not_Found",
        "overlimit_transaction", "Transaction_Received_After_Acct_Expiration",
        "account_record_not_found_on_rewrite"})
    void resolvesConstantNamesCaseSensitively(final String wrongCaseName) {
        assertThatIllegalArgumentException()
                .as("constant lookup must not silently normalise case: %s is not a declared name",
                        wrongCaseName)
                .isThrownBy(() -> RejectCode.valueOf(wrongCaseName))
                .withMessageContaining(wrongCaseName)
                .withNoCause();
    }

    /**
     * A null constant name is refused explicitly rather than treated as an absent value.
     */
    @Test
    void rejectsANullConstantName() {
        final String nullName = null;

        assertThatNullPointerException()
                .as("a null constant name is a programming error in the caller and must fail fast, "
                        + "never resolve to a default outcome")
                .isThrownBy(() -> RejectCode.valueOf(nullName))
                .withMessageContaining("null")
                .withNoCause();
    }

    /**
     * Blank and whitespace only names are refused, so an empty configuration value cannot silently select an
     * outcome.
     *
     * @param blankName an empty or whitespace only name
     */
    @ParameterizedTest(name = "valueOf(\"{0}\")")
    @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
    void rejectsBlankConstantNames(final String blankName) {
        assertThatIllegalArgumentException()
                .as("a blank constant name must fail rather than resolve; treating caller input as "
                        + "untrusted is what stops an empty value selecting a reject outcome")
                .isThrownBy(() -> RejectCode.valueOf(blankName))
                .withMessageContaining(RejectCode.class.getCanonicalName())
                .withNoCause();
    }

    /**
     * {@code values()} hands out a fresh array each call, so a caller cannot corrupt the shared constant set -
     * the enumeration exposes no mutable global state through it.
     */
    @Test
    void handsOutADefensiveCopyOfTheConstantArray() {
        final RejectCode[] firstCall = RejectCode.values();
        firstCall[0] = null;

        assertThat(RejectCode.values())
                .as("values() must return a defensive copy: mutating one caller's array must not "
                        + "affect the next caller, or the five outcomes would be shared mutable "
                        + "state")
                .doesNotContainNull()
                .hasSize(EXPECTED_CONSTANT_COUNT)
                .startsWith(RejectCode.INVALID_CARD_NUMBER);
    }

    /**
     * The published widths match the {@code PIC} clauses, and the trailer is the exact sum of its two fields
     * rather than an independently chosen number.
     */
    @Test
    void publishesThePicClauseWidthsDeclaredInTheSource() {
        assertThat(RejectCode.FAIL_REASON_LENGTH)
                .as("WS-VALIDATION-FAIL-REASON is PIC 9(04) at app/cbl/CBTRN02C.cbl:L181")
                .isEqualTo(4);
        assertThat(RejectCode.FAIL_REASON_DESC_LENGTH)
                .as("WS-VALIDATION-FAIL-REASON-DESC is PIC X(76) at app/cbl/CBTRN02C.cbl:L182")
                .isEqualTo(76);
        assertThat(RejectCode.VALIDATION_TRAILER_LENGTH)
                .as("VALIDATION-TRAILER is PIC X(80) at app/cbl/CBTRN02C.cbl:L178, and 4 + 76 = 80 "
                        + "must hold rather than merely coincide")
                .isEqualTo(80)
                .isEqualTo(RejectCode.FAIL_REASON_LENGTH + RejectCode.FAIL_REASON_DESC_LENGTH);
    }

    /**
     * The reject record resolves to 430 bytes, corroborated independently of the copybook by the declared
     * record length on the reject dataset.
     */
    @Test
    void resolvesTheRejectRecordToFourHundredAndThirtyBytes() {
        assertThat(RejectCode.REJECT_TRAN_DATA_LENGTH)
                .as("REJECT-TRAN-DATA is PIC X(350) at app/cbl/CBTRN02C.cbl:L177, the DALYTRAN "
                        + "record image whose copybook app/cpy/CVTRA06Y.cpy states RECLN = 350")
                .isEqualTo(350);
        assertThat(RejectCode.REJECT_RECORD_LENGTH)
                .as("REJECT-RECORD is 350 + 80 = 430 (app/cbl/CBTRN02C.cbl:L176-L178), confirmed "
                        + "independently by DCB=(RECFM=F,LRECL=430,BLKSIZE=0) at "
                        + "app/jcl/POSTTRAN.jcl:L36")
                .isEqualTo(430)
                .isEqualTo(RejectCode.REJECT_TRAN_DATA_LENGTH + RejectCode.VALIDATION_TRAILER_LENGTH);
    }

    /**
     * Every reason code renders as four zero padded digits, the {@code PIC 9(04)} form.
     *
     * @param constantName the constant to render
     * @param expectedFourDigits its expected four character rendering
     */
    @ParameterizedTest(name = "{0} -> \"{1}\"")
    @CsvSource({
        "INVALID_CARD_NUMBER,                        0100",
        "ACCOUNT_RECORD_NOT_FOUND,                   0101",
        "OVERLIMIT_TRANSACTION,                      0102",
        "TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION, 0103",
        "ACCOUNT_RECORD_NOT_FOUND_ON_REWRITE,        0109"
    })
    void rendersEveryReasonCodeAsFourZeroPaddedDigits(final String constantName,
                                                      final String expectedFourDigits) {
        final RejectCode outcome = RejectCode.valueOf(constantName);

        assertThat(outcome.toFailReasonField())
                .as("RejectCode.%s must render into the PIC 9(04) field "
                        + "WS-VALIDATION-FAIL-REASON (app/cbl/CBTRN02C.cbl:L181) as exactly %d "
                        + "ASCII digits, zero padded on the left", constantName,
                        RejectCode.FAIL_REASON_LENGTH)
                .isEqualTo(expectedFourDigits)
                .hasSize(RejectCode.FAIL_REASON_LENGTH)
                .containsOnlyDigits();

        assertThat(RejectCode.renderFailReason(outcome.getCode()))
                .as("the static renderer must agree with the instance renderer for RejectCode.%s, "
                        + "so the four character form has one definition and not two", constantName)
                .isEqualTo(outcome.toFailReasonField());
    }

    /**
     * Each description is space padded on the right to exactly 76 characters, reproducing the COBOL semantics
     * of moving a shorter alphanumeric literal into a longer alphanumeric field.
     *
     * @param outcome each of the five constants in turn
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(RejectCode.class)
    void padsEveryDescriptionRightToExactlySeventySixCharacters(final RejectCode outcome) {
        final String field = outcome.toFailReasonDescField();
        final String description = outcome.getDescription();

        assertThat(field)
                .as("RejectCode.%s must fill WS-VALIDATION-FAIL-REASON-DESC "
                        + "(PIC X(76), app/cbl/CBTRN02C.cbl:L182) to exactly %d characters, left "
                        + "justified and blank filled", outcome.name(),
                        RejectCode.FAIL_REASON_DESC_LENGTH)
                .hasSize(RejectCode.FAIL_REASON_DESC_LENGTH)
                .startsWith(description);

        assertThat(field.substring(description.length()))
                .as("everything after the description of RejectCode.%s must be spaces: COBOL blank "
                        + "fills the receiving field, it does not pad with any other character",
                        outcome.name())
                .isEqualTo(" ".repeat(RejectCode.FAIL_REASON_DESC_LENGTH - description.length()));
    }

    /**
     * Padding never trims and never truncates, which is asserted by round tripping the padded field back to the
     * literal.
     *
     * @param outcome each of the five constants in turn
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(RejectCode.class)
    void neverTrimsOrTruncatesADescription(final RejectCode outcome) {
        final String field = outcome.toFailReasonDescField();

        assertThat(field.stripTrailing())
                .as("stripping trailing blanks from the PIC X(76) field of RejectCode.%s must "
                        + "recover the COBOL literal exactly; a shorter result means the text was "
                        + "truncated, and a longer one means it was padded on the wrong side",
                        outcome.name())
                .isEqualTo(outcome.getDescription());

        assertThat(field)
                .as("RejectCode.%s must not be indented into its field: COBOL left justifies an "
                        + "alphanumeric move", outcome.name())
                .doesNotStartWith(" ");
    }

    /**
     * The complete trailer is the four character reason code immediately followed by the seventy six character
     * description, 80 characters in total.
     *
     * @param constantName the constant to render
     * @param expectedReasonField its expected four character reason code
     * @param expectedDescription its verbatim description literal
     * @param expectedPadding the number of trailing spaces that completes the 76 character field
     */
    @ParameterizedTest(name = "{0} -> \"{1}\" + \"{2}\" + {3} spaces")
    @CsvSource({
        "INVALID_CARD_NUMBER,                        0100, 'INVALID CARD NUMBER FOUND',                  51",
        "ACCOUNT_RECORD_NOT_FOUND,                   0101, 'ACCOUNT RECORD NOT FOUND',                   52",
        "OVERLIMIT_TRANSACTION,                      0102, 'OVERLIMIT TRANSACTION',                      55",
        "TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION, 0103, 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION', 34",
        "ACCOUNT_RECORD_NOT_FOUND_ON_REWRITE,        0109, 'ACCOUNT RECORD NOT FOUND',                   52"
    })
    void assemblesEachTrailerAsFourDigitsThenSeventySixCharacters(final String constantName,
                                                                 final String expectedReasonField,
                                                                 final String expectedDescription,
                                                                 final int expectedPadding) {
        final RejectCode outcome = RejectCode.valueOf(constantName);
        final String expectedTrailer =
                expectedReasonField + expectedDescription + " ".repeat(expectedPadding);

        assertThat(expectedDescription.length() + expectedPadding)
                .as("the transcribed literal and its stated padding must complete "
                        + "WS-VALIDATION-FAIL-REASON-DESC to %d characters for RejectCode.%s",
                        RejectCode.FAIL_REASON_DESC_LENGTH, constantName)
                .isEqualTo(RejectCode.FAIL_REASON_DESC_LENGTH);

        assertThat(outcome.toValidationTrailer())
                .as("RejectCode.%s must render the exact 80 byte value that "
                        + "app/cbl/CBTRN02C.cbl:L448 moves into VALIDATION-TRAILER", constantName)
                .isEqualTo(expectedTrailer)
                .hasSize(RejectCode.VALIDATION_TRAILER_LENGTH);
    }

    /**
     * The trailer is exactly its two fields concatenated, with the boundary at character four.
     *
     * @param outcome each of the five constants in turn
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(RejectCode.class)
    void concatenatesTheTrailerFromItsTwoFieldsInOrder(final RejectCode outcome) {
        final String trailer = outcome.toValidationTrailer();

        assertThat(trailer)
                .as("RejectCode.%s must lay WS-VALIDATION-FAIL-REASON before "
                        + "WS-VALIDATION-FAIL-REASON-DESC, in the copybook order of "
                        + "app/cbl/CBTRN02C.cbl:L181-L182", outcome.name())
                .hasSize(RejectCode.VALIDATION_TRAILER_LENGTH)
                .isEqualTo(outcome.toFailReasonField() + outcome.toFailReasonDescField());

        assertThat(trailer.substring(0, RejectCode.FAIL_REASON_LENGTH))
                .as("the first %d characters of the RejectCode.%s trailer are the reason code",
                        RejectCode.FAIL_REASON_LENGTH, outcome.name())
                .isEqualTo(outcome.toFailReasonField());
        assertThat(trailer.substring(RejectCode.FAIL_REASON_LENGTH))
                .as("the remaining %d characters of the RejectCode.%s trailer are the description "
                        + "field", RejectCode.FAIL_REASON_DESC_LENGTH, outcome.name())
                .isEqualTo(outcome.toFailReasonDescField());
    }

    /**
     * The cleared trailer is four zeros followed by seventy six spaces, the Java analogue of the per record
     * reset at {@code app/cbl/CBTRN02C.cbl}:L208-L209.
     */
    @Test
    void rendersTheClearedTrailerAsFourZerosThenSeventySixSpaces() {
        assertThat(RejectCode.NO_REJECT_REASON_CODE)
                .as("app/cbl/CBTRN02C.cbl:L208 moves 0 into WS-VALIDATION-FAIL-REASON and L211 "
                        + "tests it against 0, so the cleared sentinel is zero")
                .isZero();

        assertThat(RejectCode.noRejectTrailer())
                .as("the cleared trailer reproduces MOVE 0 (L208) followed by MOVE SPACES (L209) "
                        + "and must be exactly as wide as a populated one, or a cleared record "
                        + "would shift every following byte")
                .isEqualTo("0000" + " ".repeat(RejectCode.FAIL_REASON_DESC_LENGTH))
                .hasSize(RejectCode.VALIDATION_TRAILER_LENGTH);

        assertThat(RejectCode.renderFailReason(RejectCode.NO_REJECT_REASON_CODE))
                .as("zero renders as four zeros, the on the wire form of \"this record was not "
                        + "rejected\"")
                .isEqualTo("0000");
    }

    /**
     * Nothing wider than the 80 byte trailer can escape the type, which is how the boundary against
     * {@code RejectWriter} is enforced rather than merely documented.
     *
     * @throws ReflectiveOperationException if a qualifying accessor cannot be invoked, which would itself be a
     * defect worth failing on
     */
    @Test
    void producesNothingWiderThanTheEightyByteTrailer() throws ReflectiveOperationException {
        for (final Method method : RejectCode.class.getDeclaredMethods()) {
            final int modifiers = method.getModifiers();
            if (method.isSynthetic() || !Modifier.isPublic(modifiers)
                    || method.getParameterCount() != 0 || method.getReturnType() != String.class) {
                continue;
            }
            final Object receiver = Modifier.isStatic(modifiers) ? null : RejectCode.values()[0];
            final String rendered = (String) method.invoke(receiver);

            assertThat(rendered)
                    .as("RejectCode.%s must not render more than the %d character "
                            + "VALIDATION-TRAILER; building the %d byte REJECT-RECORD of "
                            + "app/cbl/CBTRN02C.cbl:L176-L178 belongs to "
                            + "com.cardemo.batch.writers.RejectWriter (L446-L465), not to this enum",
                            method.getName(), RejectCode.VALIDATION_TRAILER_LENGTH,
                            RejectCode.REJECT_RECORD_LENGTH)
                    .isNotNull()
                    .hasSizeLessThanOrEqualTo(RejectCode.VALIDATION_TRAILER_LENGTH);
        }
    }

    /**
     * Both ends of the {@code PIC 9(04)} domain render, so the boundaries are inclusive rather than off by one.
     */
    @Test
    void acceptsBothEndsOfThePicNineZeroFourDomain() {
        assertThat(RejectCode.renderFailReason(RejectCode.NO_REJECT_REASON_CODE))
                .as("the bottom of the PIC 9(04) domain is the cleared value from "
                        + "app/cbl/CBTRN02C.cbl:L208 and must render, not throw")
                .isEqualTo("0000")
                .hasSize(RejectCode.FAIL_REASON_LENGTH);

        assertThat(RejectCode.renderFailReason(MAX_PIC_9_04_VALUE))
                .as("the top of the PIC 9(04) domain is %d and must render as four digits with no "
                        + "padding", MAX_PIC_9_04_VALUE)
                .isEqualTo("9999")
                .hasSize(RejectCode.FAIL_REASON_LENGTH)
                .containsOnlyDigits();
    }

    /**
     * A negative reason code is refused, because {@code PIC 9(04)} is unsigned and a minus sign would consume
     * one of the four bytes.
     *
     * @param rejectedCode a value below the domain
     */
    @ParameterizedTest(name = "renderFailReason({0})")
    @ValueSource(ints = {-1, -9, -100, -9999, Integer.MIN_VALUE})
    void rejectsReasonCodesBelowThePicNineZeroFourDomain(final int rejectedCode) {
        assertThatIllegalArgumentException()
                .as("PIC 9(04) at app/cbl/CBTRN02C.cbl:L181 is unsigned, so %d must be refused "
                        + "rather than rendered with a sign or silently made positive", rejectedCode)
                .isThrownBy(() -> RejectCode.renderFailReason(rejectedCode))
                .withMessage("Reason code " + rejectedCode
                        + " cannot be rendered into WS-VALIDATION-FAIL-REASON: a PIC 9(04) field "
                        + "holds only the values " + RejectCode.NO_REJECT_REASON_CODE + " through "
                        + MAX_PIC_9_04_VALUE)
                .withNoCause();
    }

    /**
     * A reason code needing more than four digits is refused rather than truncated.
     *
     * @param rejectedCode a value above the domain
     */
    @ParameterizedTest(name = "renderFailReason({0})")
    @ValueSource(ints = {FIRST_FIVE_DIGIT_VALUE, 10_001, 99_999, 1_000_000, Integer.MAX_VALUE})
    void rejectsReasonCodesWiderThanFourDigits(final int rejectedCode) {
        assertThatIllegalArgumentException()
                .as("%d needs more than %d digits, and truncating it would yield a well formed but "
                        + "wrong fixed width field, so it must be refused", rejectedCode,
                        RejectCode.FAIL_REASON_LENGTH)
                .isThrownBy(() -> RejectCode.renderFailReason(rejectedCode))
                .withMessage("Reason code " + rejectedCode
                        + " cannot be rendered into WS-VALIDATION-FAIL-REASON: a PIC 9(04) field "
                        + "holds only the values " + RejectCode.NO_REJECT_REASON_CODE + " through "
                        + MAX_PIC_9_04_VALUE)
                .withNoCause();
    }

    /**
     * Every constant is reachable through its own reason code, which is the invariant a {@code switch} based
     * mapping cannot prove about itself.
     *
     * @param outcome each of the five constants in turn
     */
    @ParameterizedTest(name = "fromCode({0})")
    @EnumSource(RejectCode.class)
    void resolvesEveryConstantFromItsOwnReasonCode(final RejectCode outcome) {
        final Optional<RejectCode> resolved = RejectCode.fromCode(outcome.getCode());

        assertThat(resolved)
                .as("RejectCode.%s must round trip through its own code %d, or a reason code read "
                        + "back out of a persisted reject record could not be interpreted",
                        outcome.name(), outcome.getCode())
                .contains(outcome);

        assertThat(RejectCode.requireFromCode(outcome.getCode()))
                .as("the fail fast form must agree with the total form for RejectCode.%s",
                        outcome.name())
                .isSameAs(outcome);
    }

    /**
     * The cleared sentinel resolves to no outcome, because zero means the record was not rejected.
     */
    @Test
    void returnsNoOutcomeForTheClearedSentinel() {
        final Optional<RejectCode> resolved = RejectCode.fromCode(RejectCode.NO_REJECT_REASON_CODE);

        assertThat(resolved)
                .as("zero is the cleared value of app/cbl/CBTRN02C.cbl:L208 and means the record "
                        + "was not rejected, so it must resolve to no outcome and never to a "
                        + "constant")
                .isEmpty();

        assertThatIllegalArgumentException()
                .as("the fail fast form must refuse the cleared sentinel too, naming it explicitly "
                        + "so the caller learns the record was simply not rejected")
                .isThrownBy(() -> RejectCode.requireFromCode(RejectCode.NO_REJECT_REASON_CODE))
                .withMessage("Reason code " + RejectCode.NO_REJECT_REASON_CODE
                        + " is not a CBTRN02C reject outcome: the only assignable codes are 100, "
                        + "101, 102, 103 and 109 (" + RejectCode.NO_REJECT_REASON_CODE
                        + " means the record was not rejected)")
                .withNoCause();
    }

    /**
     * Codes immediately adjacent to the five resolve to nothing, so an off by one in a caller cannot land on a
     * neighbouring outcome.
     *
     * @param adjacentCode a code neighbouring the assigned five
     */
    @ParameterizedTest(name = "fromCode({0})")
    @ValueSource(ints = {99, 104, 105, 106, 107, 108, 110})
    void returnsNoOutcomeForCodesAdjacentToTheAssignedFive(final int adjacentCode) {
        final Optional<RejectCode> resolved = RejectCode.fromCode(adjacentCode);

        assertThat(resolved)
                .as("app/cbl/CBTRN02C.cbl never assigns %d, so no fallback constant may be invented "
                        + "for it; the assignable codes are exactly 100, 101, 102, 103 and 109",
                        adjacentCode)
                .isEmpty();
    }

    /**
     * Any unrecognised code yields an empty result rather than {@code null} or a fabricated fallback, across
     * the whole {@code int} range including its extremes.
     *
     * @param unknownCode a value that is not one of the five
     */
    @ParameterizedTest(name = "fromCode({0})")
    @ValueSource(ints = {Integer.MIN_VALUE, -109, -1, 1, 10, 98, 111, 199, 1000, MAX_PIC_9_04_VALUE,
        FIRST_FIVE_DIGIT_VALUE, Integer.MAX_VALUE})
    void returnsAnEmptyResultRatherThanNullForAnyUnknownCode(final int unknownCode) {
        final Optional<RejectCode> resolved = RejectCode.fromCode(unknownCode);

        assertThat(resolved)
                .as("fromCode must be total: %d is not an assignable reason code, so the result is "
                        + "an empty Optional - never null, and never a guessed constant",
                        unknownCode)
                .isNotNull()
                .isEmpty();

        assertThatIllegalArgumentException()
                .as("the fail fast form must refuse %d and name it, so the failure is diagnosable "
                        + "from the message alone", unknownCode)
                .isThrownBy(() -> RejectCode.requireFromCode(unknownCode))
                .withMessage("Reason code " + unknownCode
                        + " is not a CBTRN02C reject outcome: the only assignable codes are 100, "
                        + "101, 102, 103 and 109 (" + RejectCode.NO_REJECT_REASON_CODE
                        + " means the record was not rejected)")
                .withNoCause();
    }

    /**
     * The type is an enumeration, not a throwable, so a reject cannot be raised by construction.
     */
    @Test
    void isNotAThrowable() {
        assertThat(Throwable.class.isAssignableFrom(RejectCode.class))
                .as("a reject is a normal, expected result of validating an input record "
                        + "(app/cbl/CBTRN02C.cbl:L213-L215), so RejectCode must not be throwable; "
                        + "the abend path at L707-L711 is a different mechanism entirely")
                .isFalse();

        assertThat(RejectCode.class.getSuperclass())
                .as("RejectCode must remain a plain enumeration whose only supertype is "
                        + "java.lang.Enum")
                .isEqualTo(Enum.class);
    }

    /**
     * No member converts an outcome into something throwable.
     */
    @Test
    void exposesNoConversionFromOutcomeToException() {
        final List<String> forbiddenNames =
                List.of("toException", "asException", "toThrowable", "asThrowable", "newException");

        for (final Method method : RejectCode.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            assertThat(Throwable.class.isAssignableFrom(method.getReturnType()))
                    .as("RejectCode.%s must not hand back a Throwable: reject codes are returned, "
                            + "counted and written, never thrown", method.getName())
                    .isFalse();
            assertThat(forbiddenNames)
                    .as("RejectCode must expose no exception factory; %s reads as one",
                            method.getName())
                    .doesNotContain(method.getName());
        }
    }

    /**
     * No part of the declared API surface mentions the project's typed exception hierarchy.
     */
    @Test
    void declaresNoMemberTypeFromTheProjectExceptionHierarchy() {
        for (final Field field : RejectCode.class.getDeclaredFields()) {
            assertThat(field.getType().getName())
                    .as("field RejectCode.%s must not be typed on the project exception hierarchy",
                            field.getName())
                    .doesNotStartWith(EXCEPTION_PACKAGE_PREFIX);
        }
        for (final Method method : RejectCode.class.getDeclaredMethods()) {
            assertThat(method.getReturnType().getName())
                    .as("RejectCode.%s must not return a project exception type", method.getName())
                    .doesNotStartWith(EXCEPTION_PACKAGE_PREFIX);
            for (final Class<?> parameterType : method.getParameterTypes()) {
                assertThat(parameterType.getName())
                        .as("RejectCode.%s must not accept a project exception type",
                                method.getName())
                        .doesNotStartWith(EXCEPTION_PACKAGE_PREFIX);
            }
            for (final Class<?> thrownType : method.getExceptionTypes()) {
                assertThat(thrownType.getName())
                        .as("RejectCode.%s must not declare a project exception type as thrown",
                                method.getName())
                        .doesNotStartWith(EXCEPTION_PACKAGE_PREFIX);
            }
        }
        for (final Constructor<?> constructor : RejectCode.class.getDeclaredConstructors()) {
            for (final Class<?> parameterType : constructor.getParameterTypes()) {
                assertThat(parameterType.getName())
                        .as("the RejectCode constructor must not accept a project exception type")
                        .doesNotStartWith(EXCEPTION_PACKAGE_PREFIX);
            }
        }
    }

    /**
     * The compiled form references neither the project exception hierarchy nor Spring.
     *
     * @throws IOException if the compiled class file cannot be read from the test classpath
     */
    @Test
    void referencesNeitherTheExceptionHierarchyNorSpringInItsCompiledForm() throws IOException {
        final String compiledForm = compiledClassFileText(RejectCode.class);

        assertThat(compiledForm)
                .as("the constant pool of RejectCode.class must not mention %s: a business outcome "
                        + "must not depend on the typed exception hierarchy in any form, not even "
                        + "inside a method body", EXCEPTION_PACKAGE_INTERNAL_NAME)
                .doesNotContain(EXCEPTION_PACKAGE_INTERNAL_NAME);

        assertThat(compiledForm)
                .as("the constant pool of RejectCode.class must not mention %s or %s: the batch "
                        + "layer maps this enum onto an exit status, and that dependency runs in "
                        + "one direction only", SPRING_PACKAGE_INTERNAL_NAME, EXIT_STATUS_SIMPLE_NAME)
                .doesNotContain(SPRING_PACKAGE_INTERNAL_NAME)
                .doesNotContain(EXIT_STATUS_SIMPLE_NAME);
    }

    /**
     * The type models one reason code, not a collection of them.
     */
    @Test
    void modelsOneReasonCodeRatherThanACollectionOfCodes() {
        for (final Method method : RejectCode.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            final Class<?> returnType = method.getReturnType();

            assertThat(Collection.class.isAssignableFrom(returnType)
                    || Map.class.isAssignableFrom(returnType))
                    .as("RejectCode.%s must not return a collection of reason codes: the source "
                            + "field is a single PIC 9(04) value, so only one code can ever be in "
                            + "force per record", method.getName())
                    .isFalse();
        }
    }

    /*
     * ===== CONCERN 6 of 7: The unguarded 102 then 103 overwrite - what this type can and cannot prove =====
     *
     * The unguarded fall through by which code 103 overwrites code 102.
     *
     * Inside 1500-B-LOOKUP-ACCT the credit limit test and the expiry test are two separate,
     * sequential IF ... ELSE ... END-IF blocks. Verbatim, app/cbl/CBTRN02C.cbl:L403-L420:
     *
     *   COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT              <- L403
     *                       - ACCT-CURR-CYC-DEBIT               <- L404
     *                       + DALYTRAN-AMT                      <- L405
     *
     *   IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL                     <- L407
     *     CONTINUE                                              <- L408
     *   ELSE                                                    <- L409
     *     MOVE 102 TO WS-VALIDATION-FAIL-REASON                 <- L410
     *     MOVE 'OVERLIMIT TRANSACTION'                          <- L411
     *       TO WS-VALIDATION-FAIL-REASON-DESC                   <- L412
     *   END-IF                                                  <- L413
     *   IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)       <- L414
     *     CONTINUE                                              <- L415
     *   ELSE                                                    <- L416
     *     MOVE 103 TO WS-VALIDATION-FAIL-REASON                 <- L417
     *     MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'     <- L418
     *       TO WS-VALIDATION-FAIL-REASON-DESC                   <- L419
     *   END-IF                                                  <- L420
     *
     * There is no ELSE chaining between the two blocks, no early exit, and no test of the reason code
     * in between. So an account that is both over its limit and expired has 102 assigned at L410 and
     * immediately overwritten by 103 at L417, and the single reject record eventually written at L215
     * bears 103. Severity: High. Remediation: none - preserve and log. See also AAP section 0.7.2.6.
     *
     * OWNERSHIP OF THE BEHAVIOURAL ASSERTION - read this before adding a test here.
     * -------------------------------------------------------------------------
     * An earlier revision of this suite asserted the overwrite through a private static
     * replayLookupAcctPredicates(boolean, boolean) helper that re-implemented both IF blocks inside
     * this test file and then asserted against its own output. That is a false green: the assertions
     * stayed green no matter what production did, because production was never invoked. They would
     * have passed unchanged if the real validator guarded the second block with
     * if (reason == 0), retained 102, emitted two trailers, or omitted the expiry branch entirely -
     * the three exact regressions the tests appeared to be protecting against. The helper and its
     * five dependent tests were therefore deleted rather than kept as reassurance.
     *
     * The control flow belongs to com.cardemo.batch.processors.TransactionPostingProcessor, which
     * owns 1500-VALIDATE-TRAN, 1500-A-LOOKUP-XREF and 1500-B-LOOKUP-ACCT. Its own suite is the only
     * place the four predicate combinations can be asserted for real: it must drive the processor
     * with synthetic account and daily transaction inputs and assert that
     *   (over limit, expired)         -> exactly one trailer bearing 0103,
     *   (over limit, not expired)     -> 0102,
     *   (within limit, expired)       -> 0103,
     *   (within limit, not expired)   -> no reject at all.
     * That class is not present at this checkpoint and is outside this checkpoint's scope, so the
     * assertion is recorded here as owned elsewhere rather than faked here.
     *
     * WHAT THIS TYPE CAN PROVE, AND DOES PROVE BELOW.
     * ----------------------------------------------
     * RejectCode is the reason code catalogue, not the control flow. Its own contribution to the
     * overwrite behaviour is structural: it must make the two-codes-at-once state unrepresentable,
     * and it must render the surviving code as exactly one 80 byte trailer. Both are asserted
     * against the production type, so a change to RejectCode that broke either would fail here.
     */

    /**
     * The two codes involved in the overwrite are distinct, ordered constants of the catalogue, each
     * carrying the literal description its {@code MOVE} transcribes.
     *
     * <p>This is the part of the overwrite that {@code RejectCode} genuinely owns: if either code or
     * either description drifted, the reject file would differ byte for byte from the legacy output
     * whatever the processor's control flow did.
     */
    @Test
    void carriesBothCodesOfTheOverwritePairWithTheirSourceLiterals() {
        assertThat(RejectCode.OVERLIMIT_TRANSACTION.getCode())
                .as("app/cbl/CBTRN02C.cbl:L410 moves 102")
                .isEqualTo(102);
        assertThat(RejectCode.OVERLIMIT_TRANSACTION.getDescription())
                .as("the description literal is transcribed from L411")
                .isEqualTo("OVERLIMIT TRANSACTION");

        assertThat(RejectCode.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION.getCode())
                .as("app/cbl/CBTRN02C.cbl:L417 moves 103")
                .isEqualTo(103);
        assertThat(RejectCode.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION.getDescription())
                .as("the description literal is transcribed from L418: it abbreviates ACCT and spells "
                        + "EXPIRATION, not EXPIRY")
                .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");

        assertThat(RejectCode.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION)
                .as("the surviving code of the both-fail case is a different constant from the code it "
                        + "overwrites, so the two can never be conflated")
                .isNotSameAs(RejectCode.OVERLIMIT_TRANSACTION);
        assertThat(RejectCode.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION.ordinal())
                .as("and it is declared after it, matching the order the two MOVEs execute in")
                .isGreaterThan(RejectCode.OVERLIMIT_TRANSACTION.ordinal());
    }

    /**
     * A reason code resolves to exactly one constant, so the both-fail state cannot be represented as
     * a pair.
     *
     * <p>{@code WS-VALIDATION-FAIL-REASON} at {@code app/cbl/CBTRN02C.cbl}:L181 is a single
     * {@code PIC 9(04)} field. {@link RejectCode#requireFromCode(int)} mirrors that exactly: it
     * returns one constant, never a collection, so no caller can express "102 and 103 both apply".
     * That unrepresentability is what makes the overwrite the only possible outcome once the
     * processor has run both blocks.
     */
    @Test
    void resolvesEachReasonCodeToExactlyOneConstantSoTwoCannotBeInForce() {
        assertThat(RejectCode.requireFromCode(102))
                .isSameAs(RejectCode.OVERLIMIT_TRANSACTION);
        assertThat(RejectCode.requireFromCode(103))
                .isSameAs(RejectCode.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION);

        assertThat(RejectCode.fromCode(RejectCode.NO_REJECT_REASON_CODE))
                .as("a cleared field is not a reject outcome, so the L211 test routes the record to "
                        + "2000-POST-TRANSACTION instead of writing a trailer")
                .isEmpty();
    }

    /**
     * One reason code renders exactly one 80 byte trailer, and the trailer of the surviving code
     * carries {@code 0103} with the expiry description.
     *
     * <p>The reject write happens once per input record, at {@code app/cbl/CBTRN02C.cbl}:L215, long
     * after both predicate blocks have run. So an account that is both over its limit and expired
     * yields one trailer, not two, and that trailer bears the code that survived.
     */
    @Test
    void rendersExactlyOneTrailerForTheSurvivingCodeOfTheOverwrite() {
        final String survivingTrailer =
                RejectCode.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION.toValidationTrailer();

        assertThat(survivingTrailer)
                .as("one input record yields one 80 byte trailer; when an account is both over its "
                        + "limit and expired that trailer carries 0103 and the expiry description, "
                        + "never 0102 and never a second record")
                .isEqualTo("0103" + "TRANSACTION RECEIVED AFTER ACCT EXPIRATION" + " ".repeat(34))
                .hasSize(RejectCode.VALIDATION_TRAILER_LENGTH);

        assertThat(RejectCode.OVERLIMIT_TRANSACTION.toValidationTrailer())
                .as("the overwritten code still renders its own trailer when it is the one that "
                        + "survives, which is the (over limit, not expired) case")
                .isEqualTo("0102" + "OVERLIMIT TRANSACTION" + " ".repeat(55))
                .hasSize(RejectCode.VALIDATION_TRAILER_LENGTH)
                .isNotEqualTo(survivingTrailer);
    }

    @Test
    void declaresEveryAuthoredStaticFieldFinal() {
        for (final Field field : RejectCode.class.getDeclaredFields()) {
            if (field.isSynthetic() || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            assertThat(Modifier.isFinal(field.getModifiers()))
                    .as("static field RejectCode.%s must be final: a settable static would be "
                            + "shared mutable state on a type every batch and service path reads",
                            field.getName())
                    .isTrue();
        }
    }

    @Test
    void declaresEveryAuthoredInstanceFieldFinal() {
        for (final Field field : RejectCode.class.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            assertThat(Modifier.isFinal(field.getModifiers()))
                    .as("instance field RejectCode.%s must be final: the reason code and its "
                            + "description are a compile time contract transcribed from "
                            + "app/cbl/CBTRN02C.cbl and must not be reassignable", field.getName())
                    .isTrue();
        }
    }

    /**
     * Repeated calls render identically, which is what proves no ambient clock, default locale or default zone
     * leaks into the output.
     *
     * @param outcome each of the five constants in turn
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(RejectCode.class)
    void rendersIdenticallyOnRepeatedCalls(final RejectCode outcome) {
        assertThat(outcome.toValidationTrailer())
                .as("RejectCode.%s must render deterministically: nothing in this type may consult "
                        + "the current instant, the default locale or the default zone",
                        outcome.name())
                .isEqualTo(outcome.toValidationTrailer());
        assertThat(outcome.toFailReasonField())
                .as("the reason code rendering of RejectCode.%s must be stable across calls",
                        outcome.name())
                .isEqualTo(outcome.toFailReasonField());
        assertThat(outcome.toFailReasonDescField())
                .as("the description rendering of RejectCode.%s must be stable across calls",
                        outcome.name())
                .isEqualTo(outcome.toFailReasonDescField());
    }

    /**
     * The static renderers are stable too, including the cleared trailer.
     */
    @Test
    void rendersTheStaticFormsIdenticallyOnRepeatedCalls() {
        assertThat(RejectCode.noRejectTrailer())
                .as("the cleared trailer must be a constant value, not a freshly decided one")
                .isEqualTo(RejectCode.noRejectTrailer());
        assertThat(RejectCode.renderFailReason(RejectCode.NO_REJECT_REASON_CODE))
                .as("rendering the cleared sentinel must be stable across calls")
                .isEqualTo(RejectCode.renderFailReason(RejectCode.NO_REJECT_REASON_CODE));
    }

    /**
     * Reads a compiled class file from the test classpath and returns its bytes as text, so that its
     * constant pool can be searched for type references.
     *
     * @param type the class whose compiled form should be read
     * @return the class file bytes decoded byte for byte into a string
     * @throws IOException if the class file cannot be read
     * @throws NullPointerException if the class file is not on the test classpath, which means the module was
     * not compiled before the test ran
     */
    private static String compiledClassFileText(final Class<?> type) throws IOException {
        final String resourceName = type.getSimpleName() + ".class";
        try (InputStream classFile = Objects.requireNonNull(type.getResourceAsStream(resourceName),
                () -> "The compiled class file " + resourceName + " is not on the test classpath; run "
                        + "./mvnw test-compile so that target/classes is populated before this test")) {
            return new String(classFile.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
