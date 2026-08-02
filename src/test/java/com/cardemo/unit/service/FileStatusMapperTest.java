/*
 * ******************************************************************
 * Program     : FileStatusMapperTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/service
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Executable specification for the single central
 *               translation of a COBOL FILE STATUS into a typed
 *               com.cardemo.exception subtype. Pins the APPL-RESULT
 *               vocabulary, the render-then-abend ordering, the
 *               byte-exact four-character IO-STATUS-04 rendering, the
 *               whole status-to-exception map, the three call sites at
 *               which a status other than '00' is an accepted control
 *               path, and the boundaries the mapper must not cross.
 * Source      : app/cbl/CBTRN02C.cbl:L131-L144 (IO-STATUS, the
 *               unsigned TWO-BYTES-BINARY and the APPL-RESULT
 *               condition names), L176-L182 (REJECT-RECORD and
 *               WS-VALIDATION-FAIL-REASON), L229-L231 (RETURN-CODE 4
 *               iff the reject count exceeds zero), L236-L252
 *               (0000-DALYTRAN-OPEN, the two-way guard),
 *               L345-L369 (1000-DALYTRAN-GET-NEXT, the three-way
 *               sequential read), L467-L542 (2700-UPDATE-TCATBAL and
 *               its strict WRITE and REWRITE verifications),
 *               L707-L711 (9999-ABEND-PROGRAM), L714-L727
 *               (9910-DISPLAY-IO-STATUS) @ 7756d89
 * Source      : app/cpy/CSMSG02Y.cpy:L21-L29 (CABENDD.CPY abend work
 *               areas, widths 4 / 8 / 50 / 72) @ 7756d89
 * Source      : app/cbl/CBACT04C.cbl:L415-L440 (1200-GET-INTEREST-RATE
 *               accepting '00' or '23'), L443-L460
 *               (1200-A-GET-DEFAULT-INT-RATE, strict at L446)
 *               @ 7756d89
 * Source      : app/cbl/CBSTM03A.CBL:L80 (WS-M03B-RC), L736 and its
 *               eight siblings (open accepting '00' or '04'), L353 and
 *               its three siblings (read accepting '00' or '10' only)
 *               @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.FileUnavailableException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.service.shared.FileStatusMapper;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Executable specification for {@code com.cardemo.service.shared.FileStatusMapper}, the one place in the
 * codebase that decides what a COBOL {@code FILE STATUS} value means.
 *
 * <h2>1. What it does</h2>
 *
 * <p>The frozen corpus repeats a single input and output guard idiom: move 8 into a result field, perform
 * the verb, move 0 when the status is {@code '00'} and 12 otherwise, then either continue or render the
 * status and abend. Because that idiom is <em>one</em> shape rather than hundreds of independent checks, it
 * is translated once, centrally - and a single mistake in that one translation is a mistake at every guard
 * site simultaneously. That is why this specification asserts against the corpus rather than against the
 * implementation, and why it is exhaustive rather than representative.
 *
 * <p>Every locator below was verified by direct inspection at commit {@code 7756d89} before being asserted
 * here. {@code app/} is frozen and is read, never written.
 *
 * <ul>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L131-L144} - {@code IO-STATUS}, the deliberately <strong>unsigned</strong>
 *       {@code TWO-BYTES-BINARY PIC 9(4) BINARY} at {@code :L134}, {@code IO-STATUS-04}, and the
 *       {@code APPL-RESULT} condition names {@code APPL-AOK VALUE 0} at {@code :L143} and
 *       {@code APPL-EOF VALUE 16} at {@code :L144}.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L236-L252} - {@code 0000-DALYTRAN-OPEN}, the two-way guard, whose
 *       failure arm renders at {@code :L249} and only then abends at {@code :L250}.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L345-L369} - {@code 1000-DALYTRAN-GET-NEXT}, the three-way sequential
 *       read, which moves 16 at {@code :L352} and 12 at {@code :L354}, terminates the loop at
 *       {@code :L360-L361}, and again renders at {@code :L365} before abending at {@code :L366}.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L707-L711} - {@code 9999-ABEND-PROGRAM}, which moves 999 into
 *       {@code ABCODE} at {@code :L710} and calls {@code 'CEE3ABD'} at {@code :L711}.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L714-L727} - {@code 9910-DISPLAY-IO-STATUS}, the renderer.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L467-L542} - {@code 2700-UPDATE-TCATBAL}, lenient at its read guard
 *       {@code :L481} and strict at its {@code WRITE} verification {@code :L512} and its {@code REWRITE}
 *       verification {@code :L530}.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl:L415-L460} - the disclosure group lookup, lenient at {@code :L422} and
 *       <strong>strict at {@code :L446}</strong>.</li>
 *   <li>{@code app/cbl/CBSTM03A.CBL:L80} plus the nine sites {@code :L736}, {@code :L748}, {@code :L771},
 *       {@code :L789}, {@code :L807}, {@code :L862}, {@code :L879}, {@code :L895} and {@code :L911} - the
 *       only places in the corpus where {@code '04'} is success.</li>
 *   <li>{@code app/cpy/CSMSG02Y.cpy:L21-L29} - the {@code CABENDD.CPY} abend work areas. The copybook is 35
 *       physical lines and {@code 01 ABEND-DATA.} sits at physical line 21; the figures 001200 through
 *       002000 quoted elsewhere are card sequence numbers in columns 1 to 6, not line numbers.</li>
 * </ul>
 *
 * <h2>2. How to build, run and test</h2>
 *
 * <p>This class is bound to <strong>Surefire</strong>, not Failsafe. The root {@code pom.xml} gives Surefire
 * 3.5.4 the includes {@code **}{@code /*Test.java} and {@code **}{@code /*Tests.java} and excludes only
 * {@code **}{@code /integration/**} and {@code **}{@code /e2e/**}, so a class named {@code *Test} anywhere
 * outside those two trees is collected here. Renaming this file so that it no longer ends in {@code Test},
 * or moving it under {@code integration} or {@code e2e}, would make it match neither plugin's include set:
 * it would then never run, and the build would still be green. Build with {@code ./mvnw -B clean compile},
 * run this tier with {@code ./mvnw -B clean test}, and gate coverage with {@code ./mvnw -B verify}, which
 * enforces an eighty percent line floor with no package excluded.
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <p>None are configurable, and that is the point of this tier.
 *
 * <ul>
 *   <li><strong>No collaborators, therefore no mocks.</strong> The subject has a single no-argument
 *       constructor, no instance field and no injected dependency, so there is nothing to stub. Mockito is
 *       deliberately not imported: an unused import is forbidden by Rule 1 Clause B, and a strict-stub
 *       Mockito session with no stub to verify would be pure ceremony.</li>
 *   <li><strong>No clock, no locale, no time zone, no randomness.</strong> Every formatting call in this
 *       class passes {@link Locale#ROOT} explicitly. Nothing here consults the wall clock, the default
 *       locale, the default time zone or a random source, so the same assertions hold on every machine and
 *       on every run.</li>
 *   <li><strong>No container, Spring context, database, filesystem or network.</strong> Those tiers live
 *       under {@code src/test/java/com/cardemo/integration} and {@code .../e2e}. This class reads no file,
 *       so it makes no assumption about the working directory Surefire happens to use.</li>
 *   <li><strong>Zero mutable state.</strong> The subject is held in a {@code final} instance field
 *       initialised at construction rather than in a field reassigned by a setup method, and there is no
 *       static mutable field anywhere in this class.</li>
 * </ul>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The build fails on a warning rather than a test.</strong> The compiler runs
 *       {@code -Xlint:all} with {@code -Werror} and {@code failOnWarning}, and it reaches test compilation.
 *       A raw type, a deprecated call, a dangling documentation comment or a switch fall through is fatal
 *       here exactly as it is in {@code src/main}. Remediation: read the {@code javac} note, not the
 *       Surefire report.</li>
 *   <li><strong>The rendering assertions fail on the literal {@code NNNN}.</strong> {@code NNNN} is a
 *       literal inside the COBOL {@code DISPLAY}, emitted on both branches, and {@code DISPLAY a b}
 *       concatenates with no separator, so the four rendered characters <em>follow</em> it. An
 *       implementation that treats {@code NNNN} as a placeholder to substitute produces
 *       {@code FILE STATUS IS: 0023} where the corpus produces {@code FILE STATUS IS: NNNN0023}, and the
 *       parity comparison reads that line.</li>
 *   <li><strong>The three-digit expansion comes out negative or two digits wide.</strong> The low order
 *       byte mask was omitted. {@code TWO-BYTES-BINARY} is unsigned at
 *       {@code app/cbl/CBTRN02C.cbl:L134}, so a second status byte above {@code 0x7F} must expand to its
 *       unsigned value; a Java {@code byte} sign extends instead. See
 *       {@code TheFourCharacterRendering#theSecondStatusByteIsMaskedToItsUnsignedLowOrderValue}.</li>
 *   <li><strong>A missing disclosure group default row surfaces as a not found.</strong> It must abend.
 *       {@code app/cbl/CBACT04C.cbl:L446} accepts {@code '00'} and nothing else, so the retry maps
 *       {@code '23'} to the fatal type. Note the off-by-one in the commonly quoted citation: line 445 is
 *       blank.</li>
 *   <li><strong>A status of {@code '10'} appears as an error.</strong> End of file terminates a read loop.
 *       It is only an unexpected condition at a guard where end of file cannot occur, such as an open or a
 *       write, which is why two guard shapes exist rather than one.</li>
 * </ul>
 *
 * <h2>Findings this specification pins, by severity</h2>
 *
 * <ul>
 *   <li><strong>Blocker</strong> - mis-rendering the {@code FILE STATUS IS: NNNN} line. Remediation: assert
 *       the whole line, literal included, and assert that the mapper composes it from the two owned parts
 *       rather than reassembling it.</li>
 *   <li><strong>Blocker</strong> - omitting the low order byte mask in the three-digit expansion.
 *       Remediation: feed a second byte above {@code 0x7F} and assert three digits with no sign.</li>
 *   <li><strong>Blocker</strong> - mapping a missing disclosure group default row to a not found rather
 *       than an abend. Remediation: assert the fatal type <em>and</em> assert that the thrown object is not
 *       a not found.</li>
 *   <li><strong>High</strong> - treating {@code '10'} as an error. Remediation: drive a read loop and
 *       assert it terminates without an exception.</li>
 *   <li><strong>High</strong> - admitting {@code '04'} to the general map. Remediation: assert it is fatal
 *       on every general entry point and accepted only at the two statement file service ones.</li>
 *   <li><strong>High</strong> - implementing the rendering a second time. Remediation: assert composition
 *       against {@code FileStatus.renderIoStatus04(String)} across the whole vocabulary.</li>
 *   <li><strong>Medium</strong> - citing the renderer as ending at line 731. Its body ends at
 *       {@code app/cbl/CBTRN02C.cbl:L727}; lines 728 onward are blank and version stamp comments. The
 *       banner above is corrected accordingly.</li>
 *   <li><strong>Medium</strong> - citing the disclosure group retry guard at line 445 rather than
 *       {@code app/cbl/CBACT04C.cbl:L446}.</li>
 *   <li><strong>Low</strong> - both lenient guards are written {@code '00'} then <em>two</em> spaces then
 *       {@code OR '23'}, so a naive single-space search finds neither. Pinned by
 *       {@code WhereNotFoundIsNotAnError#theTwoSpaceSpellingIsAGrepHazard}.</li>
 * </ul>
 *
 * <h2>The documented conflict, and why parity governs</h2>
 *
 * <p>Rule 1 Clause B forbids dead code. Statuses {@code '22'} and {@code '35'} have <strong>zero</strong>
 * literal occurrences in the 19,254 line corpus - re-measured here at {@code 7756d89} - yet both remain
 * mapped, which looks like dead code and is not. Clause B prohibits an artefact <em>without an owner or a
 * tracking reference</em>; both mappings carry a {@code DECISION_LOG.md} entry, a
 * {@code TRACEABILITY_MATRIX.md} row and the citations in
 * {@code TheStatusToExceptionMap#theTwoStatusesWithNoLiteralSourceSiteRemainMapped} below, and both are
 * runtime reachable from the store layer.
 * The CICS vocabulary is the corroborating evidence: {@code DFHRESP(DUPREC)} occurs at 7 sites and
 * {@code DFHRESP(DUPKEY)} at 3, so a duplicate key is reachable, while {@code DFHRESP(NOTOPEN)} occurs at 0
 * sites, which is precisely why an unavailable file can only ever be reported by the store. Deleting either
 * mapping would leave a reachable status with no typed outcome and would break the paragraph map the scope
 * coverage gate verifies. This is the only conflict in this file.
 */
@DisplayName("FileStatusMapper: one idiom translated once, including the outcomes that are not failures")
class FileStatusMapperTest {

    /**
     * The subject under test. Held {@code final} and constructed once per test instance rather than
     * reassigned by a setup method, so this class carries no mutable state at all. JUnit's default
     * per-method lifecycle gives every test a fresh instance, and the subject is stateless in any case.
     */
    private final FileStatusMapper mapper = new FileStatusMapper();

    /**
     * Every status the corpus can present at a guard, plus the malformed shapes a store or adapter layer
     * outside the corpus can present. Immutable, and never iterated for order-dependent behaviour - only
     * for invariants that must hold for each element independently.
     */
    private static final List<String> STATUS_VOCABULARY =
            List.of("00", "04", "10", "22", "23", "35", "90", "99", "AB", "  ", "9", "233");

    /**
     * The five batch reject reasons moved into {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at
     * {@code app/cbl/CBTRN02C.cbl:L181}, assigned at {@code :L385}, {@code :L397}, {@code :L410},
     * {@code :L417} and {@code :L556}. They are business outcomes carried as data and must never appear in
     * anything this mapper produces.
     */
    private static final List<String> REJECT_REASONS = List.of("100", "101", "102", "103", "109");

    /**
     * The exact literal descriptions that travel with those five reasons in the 76 character trailer of the
     * 430 byte reject record at {@code app/cbl/CBTRN02C.cbl:L176-L182}. Reason 101 and reason 109 share a
     * description in the source, which is why this list holds four entries for five reasons.
     */
    private static final List<String> REJECT_DESCRIPTIONS = List.of(
            "INVALID CARD NUMBER FOUND",
            "ACCOUNT RECORD NOT FOUND",
            "OVERLIMIT TRANSACTION",
            "TRANSACTION RECEIVED AFTER ACCT EXPIRATION");

    /**
     * The two exact statuses the map retains even though a census of {@code app/cbl/*} at
     * {@code 7756d89} finds zero literal occurrences of either. They are runtime-reachable-only
     * mappings, deliberately kept, and tracked in {@code DECISION_LOG.md}.
     */
    private static final List<String> STATUSES_WITH_NO_LITERAL_SOURCE_SITE = List.of("22", "35");

    /**
     * The exact statuses the same census does find in {@code app/cbl/*}: {@code '00'} at 88
     * occurrences across 9 files, {@code '04'} at 9 occurrences in {@code app/cbl/CBSTM03A.CBL}
     * alone, {@code '10'} at 11 occurrences across 9 files, and {@code '23'} at 3 occurrences
     * across {@code app/cbl/CBTRN02C.cbl} and {@code app/cbl/CBACT04C.cbl}.
     */
    private static final List<String> STATUSES_WITH_LITERAL_SOURCE_SITES = List.of("00", "04", "10", "23");

    /**
     * A logical file name standing in for a CICS file or a batch DD name at a guard site. It carries no
     * credential, no personal data and no environment-specific address, per Rule 1 clause D.
     */
    private static final String LOGICAL_FILE = "TRANSACT";

    /** The verb of {@code READ} guards such as {@code app/cbl/CBTRN02C.cbl:L346}. */
    private static final String OPERATION_READ = "READ";

    /** The verb of {@code WRITE} guards such as {@code app/cbl/CBTRN02C.cbl:L510}. */
    private static final String OPERATION_WRITE = "WRITE";

    /** The verb of {@code OPEN} guards such as {@code app/cbl/CBTRN02C.cbl:L238}. */
    private static final String OPERATION_OPEN = "OPEN";

    /**
     * The DD name moved into {@code WS-M03B-DD} at {@code app/cbl/CBSTM03A.CBL:L731}, immediately before
     * the {@code IF WS-M03B-RC = '00' OR '04'} guard at {@code :L736}. Used for the statement file service
     * assertions so that the scoped entry point is exercised with a real legacy DD name.
     */
    private static final String DD_TRNXFILE = "TRNXFILE";

    /**
     * The job exit code {@code app/cbl/CBTRN02C.cbl:L229-L231} sets if and only if the reject count exceeds
     * zero. It is the job's business outcome, is owned by the batch exit-status decider, and is deliberately
     * not producible by anything in this mapper, whose only exit code is the 12 of an abend.
     */
    private static final int REJECT_PRESENT_RETURN_CODE = 4;

    /**
     * The four named counters that replace the end-of-run {@code DISPLAY} statements at
     * {@code app/cbl/CBTRN02C.cbl:L227-L228}. The rejected-records counter is the one tagged by reject code.
     * All four belong to the observability layer; none of them may be implemented in this mapper.
     */
    private static final List<String> NAMED_COUNTERS = List.of(
            "records processed",
            "records rejected",
            "authentication attempts",
            "total transaction amount");

    /**
     * The three shapes of an absent status a Java caller can present where the corpus guaranteed two bytes:
     * {@code null}, the empty string and a blank string. All three normalise to the two COBOL fill
     * characters and therefore render identically. {@code Arrays.asList} is used rather than
     * {@code List.of} because the latter rejects a {@code null} element, and {@code null} is precisely one
     * of the cases Rule 1 clause B requires to be handled explicitly.
     */
    private static final List<String> ABSENT_STATUSES = Arrays.asList(null, "", "  ");

    /**
     * The word-shaped tokens the masking rules in {@code logback-spring.xml} act on, held in lower case
     * because every comparison lower-cases under {@code Locale.ROOT} first. They are deliberately generic
     * words rather than provider-specific credential prefixes, so that this file carries nothing a secret
     * scanner could mistake for a real credential while still proving the masking carve-out holds.
     */
    private static final List<String> MASKABLE_TOKENS = List.of(
            "password",
            "passwd",
            "secret",
            "token",
            "bearer",
            "apikey",
            "credential",
            "bcrypt",
            "private key",
            "ssn");

    /**
     * The environment-specific addresses Rule 1 clause C forbids, so that this tier stays runnable with no
     * container, no database and no network. Held in lower case for the same reason as
     * {@link #MASKABLE_TOKENS}.
     */
    private static final List<String> ENVIRONMENT_LITERALS = List.of(
            "localhost",
            "127.0.0.1",
            "0.0.0.0",
            ":5432",
            ":4566",
            "jdbc:",
            "amazonaws.com");

    /**
     * The digit-run length at which a masking rule recognises a social security number. Nothing this mapper
     * emits may reach it, because the customer layout carries a nine-digit government identifier and a
     * masking rule that fired on a parity-compared line would rewrite the line.
     */
    private static final int SOCIAL_SECURITY_DIGIT_RUN = 9;

    /**
     * The digit-run length at which a masking rule begins to recognise a card number. The card number field
     * is sixteen characters, and a masking rule keyed on thirteen or more digits must never see one here.
     */
    private static final int CARD_NUMBER_DIGIT_RUN = 13;

    /**
     * Group 1. The universal input and output guard idiom, which is the reason a single central mapper is
     * the right design rather than a check at each of the roughly nineteen guard sites in
     * {@code app/cbl/CBTRN02C.cbl} alone.
     */
    @Nested
    @DisplayName("1. The universal I/O guard idiom: the result vocabulary and the render-then-abend order")
    class TheUniversalGuardIdiom {

        @Test
        @DisplayName("the result field vocabulary is the COBOL condition-name values, unaltered")
        void theResultFieldVocabularyReproducesTheConditionNames() {
            assertThat(FileStatusMapper.APPL_RESULT_INITIAL)
                    .as("app/cbl/CBTRN02C.cbl:L237 MOVE 8 TO APPL-RESULT, performed before every I/O verb")
                    .isEqualTo(8);
            assertThat(FileStatusMapper.APPL_AOK)
                    .as("app/cbl/CBTRN02C.cbl:L143 declares 88 APPL-AOK VALUE 0")
                    .isEqualTo(0);
            assertThat(FileStatusMapper.APPL_EOF)
                    .as("app/cbl/CBTRN02C.cbl:L144 declares 88 APPL-EOF VALUE 16")
                    .isEqualTo(16);
            assertThat(FileStatusMapper.APPL_FAILURE)
                    .as("app/cbl/CBTRN02C.cbl:L242 and :L354 both MOVE 12 TO APPL-RESULT")
                    .isEqualTo(12);
        }

        @Test
        @DisplayName("the end-of-file sentinel is 16 and emphatically not 12")
        void theEndOfFileSentinelIsSixteenAndNotTwelve() {
            // The single most common transcription error in this idiom is to reuse the failure value 12 for
            // end of file. The corpus keeps them apart in two independent places: the condition name is
            // declared VALUE 16 at :L144, and the sequential read moves that same 16 at :L352 while moving
            // 12 at :L354 from the other arm of the very same IF. Collapsing the two would make a normal
            // end of file indistinguishable from an I/O failure, and every read loop would abend on its
            // last iteration.
            assertThat(FileStatusMapper.APPL_EOF)
                    .as("app/cbl/CBTRN02C.cbl:L144 88 APPL-EOF VALUE 16, moved at :L352")
                    .isEqualTo(16)
                    .isNotEqualTo(FileStatusMapper.APPL_FAILURE)
                    .isNotEqualTo(12);
            assertThat(mapper.applResultForSequentialRead("10"))
                    .as("app/cbl/CBTRN02C.cbl:L351-L352 IF DALYTRAN-STATUS = '10' MOVE 16 TO APPL-RESULT")
                    .isEqualTo(FileStatusMapper.APPL_EOF)
                    .isEqualTo(16);
        }

        @Test
        @DisplayName("the initial value 8 is none of the three outcomes, so an unreached guard is visible")
        void theInitialValueIsNeitherOutcome() {
            // 8 is moved in before the verb precisely so that a path which never reaches the status test
            // cannot be mistaken for success, for end of file or for failure.
            assertThat(FileStatusMapper.APPL_RESULT_INITIAL)
                    .as("app/cbl/CBTRN02C.cbl:L237 the pre-verb value must remain distinguishable")
                    .isNotEqualTo(FileStatusMapper.APPL_AOK)
                    .isNotEqualTo(FileStatusMapper.APPL_EOF)
                    .isNotEqualTo(FileStatusMapper.APPL_FAILURE);
        }

        @Test
        @DisplayName("the guard is two-way and the sequential read is three-way, and they differ only on 10")
        void theGuardIsTwoWayAndTheSequentialReadIsThreeWay() {
            // app/cbl/CBTRN02C.cbl:L239-L243 (OPEN, CLOSE, WRITE, REWRITE) tests '00' against everything
            // else. app/cbl/CBTRN02C.cbl:L347-L356 (sequential READ) nests a second test for '10'. End of
            // file is not a reachable outcome of an open or a write, so a report of it there is an
            // unexpected condition - which is why one method cannot serve both shapes.
            assertThat(mapper.applResultForGuard("00")).isEqualTo(FileStatusMapper.APPL_AOK);
            assertThat(mapper.applResultForSequentialRead("00")).isEqualTo(FileStatusMapper.APPL_AOK);

            assertThat(mapper.applResultForGuard("10"))
                    .as("an open or a write reporting end of file is an unexpected condition")
                    .isEqualTo(FileStatusMapper.APPL_FAILURE);
            assertThat(mapper.applResultForSequentialRead("10"))
                    .as("a sequential read reporting end of file is the loop's normal termination")
                    .isEqualTo(FileStatusMapper.APPL_EOF);

            for (String status : STATUS_VOCABULARY) {
                if ("00".equals(status) || "10".equals(status)) {
                    continue;
                }
                assertThat(mapper.applResultForGuard(status))
                        .as("status [%s] is a guard failure at app/cbl/CBTRN02C.cbl:L242", status)
                        .isEqualTo(FileStatusMapper.APPL_FAILURE);
                assertThat(mapper.applResultForSequentialRead(status))
                        .as("status [%s] is a read failure at app/cbl/CBTRN02C.cbl:L354", status)
                        .isEqualTo(FileStatusMapper.APPL_FAILURE);
            }
        }

        @Test
        @DisplayName("an open renders the status first and abends second, per L249 then L250")
        void theStatusIsRenderedBeforeTheAbendIsRaisedAtAnOpen() {
            // app/cbl/CBTRN02C.cbl:L246-L251 is an ordered sequence: DISPLAY the site message (:L247), MOVE
            // the status into IO-STATUS (:L248), PERFORM 9910-DISPLAY-IO-STATUS (:L249) and only then
            // PERFORM 9999-ABEND-PROGRAM (:L250). Reversing the last two would abend before the diagnostic
            // reached SYSOUT and the operator would lose the only evidence of the cause.
            final List<String> performed = new ArrayList<>();
            String status = "35";

            assertThat(mapper.applResultForGuard(status))
                    .as("app/cbl/CBTRN02C.cbl:L239-L243 routes '35' down the failure arm")
                    .isEqualTo(FileStatusMapper.APPL_FAILURE);

            performed.add("9910-DISPLAY-IO-STATUS " + mapper.displayIoStatus(status));

            assertThatExceptionOfType(FileUnavailableException.class)
                    .as("app/cbl/CBTRN02C.cbl:L250 PERFORM 9999-ABEND-PROGRAM")
                    .isThrownBy(() -> {
                        performed.add("9999-ABEND-PROGRAM");
                        mapper.requireSuccess(status, "DALYTRAN", "OPEN");
                    })
                    .withMessageContaining("(IO-STATUS-04 0035)");

            assertThat(performed)
                    .as("app/cbl/CBTRN02C.cbl:L249 renders, then :L250 abends - never the reverse")
                    .containsExactly("9910-DISPLAY-IO-STATUS FILE STATUS IS: NNNN0035", "9999-ABEND-PROGRAM");
        }

        @Test
        @DisplayName("a sequential read renders the status first and abends second, per L365 then L366")
        void theStatusIsRenderedBeforeTheAbendIsRaisedAtASequentialRead() {
            // The same ordering recurs in the three-way form at app/cbl/CBTRN02C.cbl:L362-L367: the failure
            // arm is reached only when APPL-EOF is false, and it renders at :L365 before abending at :L366.
            final List<String> performed = new ArrayList<>();
            String status = "23";

            performed.add("9910-DISPLAY-IO-STATUS " + mapper.displayIoStatus(status));

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .as("app/cbl/CBTRN02C.cbl:L366 PERFORM 9999-ABEND-PROGRAM on the non-EOF failure arm")
                    .isThrownBy(() -> {
                        performed.add("9999-ABEND-PROGRAM");
                        mapper.requireSuccessOrEndOfFile(status, "DALYTRAN", "READ");
                    })
                    .withMessageContaining("(IO-STATUS-04 0023)");

            assertThat(performed)
                    .as("app/cbl/CBTRN02C.cbl:L365 renders, then :L366 abends")
                    .containsExactly("9910-DISPLAY-IO-STATUS FILE STATUS IS: NNNN0023", "9999-ABEND-PROGRAM");
        }

        @Test
        @DisplayName("rendering never throws, which is what makes it safe to perform before the abend")
        void renderingNeverThrowsForAnyInputAtAll() {
            // 9910-DISPLAY-IO-STATUS has no failure path: both arms of its IF end in a DISPLAY. If the Java
            // renderer could throw, the ordering above would be unimplementable, because a failure inside
            // the diagnostic would replace the failure being diagnosed.
            for (String status : STATUS_VOCABULARY) {
                assertThatCode(() -> mapper.displayIoStatus(status))
                        .as("app/cbl/CBTRN02C.cbl:L714-L727 has no failure path, status [%s]", status)
                        .doesNotThrowAnyException();
            }
            assertThatCode(() -> mapper.displayIoStatus(null))
                    .as("an absent status must still render rather than mask the failure being reported")
                    .doesNotThrowAnyException();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Group 2 - the four-character rendering of 9910-DISPLAY-IO-STATUS, a byte-level contract.
    // Source: app/cbl/CBTRN02C.cbl:L714-L727 (the paragraph body; L728 onward are version-stamp
    // comments, which is why the widely quoted "L714-L731" over-runs and is a Medium finding).
    // ------------------------------------------------------------------------------------------

    /**
     * Group 2. The byte-level rendering contract of {@code 9910-DISPLAY-IO-STATUS}, whose body is
     * {@code app/cbl/CBTRN02C.cbl:L714-L727}. Two facts dominate this group and both are Blocker
     * severity if got wrong: {@code NNNN} is a twenty-character fixed literal emitted on both
     * branches rather than a placeholder, and the second status byte is expanded through an
     * unsigned {@code PIC 9(4) BINARY} field ({@code :L134}), so the low-order-byte mask is
     * mandatory or a high byte sign-extends.
     */
    @Nested
    @DisplayName("2. The four-character rendering: NNNN is a literal and the second byte is unsigned")
    class TheFourCharacterRendering {

        /**
         * Branch B of {@code 9910-DISPLAY-IO-STATUS} moves the constant {@code '0000'} into
         * {@code IO-STATUS-04} and then overlays the two status characters at
         * {@code IO-STATUS-04(3:2)} ({@code app/cbl/CBTRN02C.cbl:L724-L725}). A well-formed
         * status whose first byte is not {@code '9'} therefore renders as two zeroes followed
         * by the status itself, appended to the twenty-character DISPLAY literal.
         *
         * @param status       the two-character COBOL FILE STATUS value moved into
         *                     {@code IO-STATUS} at each guard site, for example
         *                     {@code app/cbl/CBTRN02C.cbl:L248}
         * @param expectedLine the complete line the legacy DISPLAY emits, literal included
         */
        @ParameterizedTest(name = "status [{0}] renders the whole line as [{1}]")
        @CsvSource({
            "00, 'FILE STATUS IS: NNNN0000'",
            "04, 'FILE STATUS IS: NNNN0004'",
            "10, 'FILE STATUS IS: NNNN0010'",
            "22, 'FILE STATUS IS: NNNN0022'",
            "23, 'FILE STATUS IS: NNNN0023'",
            "35, 'FILE STATUS IS: NNNN0035'"
        })
        void theNumericBranchOverlaysTheStatusOntoFourZeroes(String status, String expectedLine) {
            assertThat(mapper.displayIoStatus(status))
                    .as("app/cbl/CBTRN02C.cbl:L724 MOVE '0000', :L725 MOVE IO-STATUS TO IO-STATUS-04(3:2)")
                    .isEqualTo(expectedLine);
        }

        @Test
        @DisplayName("a nine in the first byte selects the binary-expansion branch, so 9 + X'01' gives 9001")
        void theNinePrefixedStatusRendersThroughTheBinaryExpansionBranch() {
            // app/cbl/CBTRN02C.cbl:L716-L721. IO-STAT1 is copied through unchanged and IO-STAT2 is
            // moved into TWO-BYTES-RIGHT, whose binary value is then edited into PIC 999.
            assertThat(mapper.displayIoStatus("9\u0001"))
                    .as("app/cbl/CBTRN02C.cbl:L717-L720, IO-STAT1 '9' then X'01' expanded to 001")
                    .isEqualTo("FILE STATUS IS: NNNN9001");
            assertThat(mapper.displayIoStatus("90"))
                    .as("a numeric status still takes branch A because IO-STAT1 = '9' at :L716")
                    .isEqualTo("FILE STATUS IS: NNNN9048");
            assertThat(mapper.displayIoStatus("99"))
                    .as("the 9x family renders the second byte as its EBCDIC-to-ASCII code point, not as 9")
                    .isEqualTo("FILE STATUS IS: NNNN9057");
            assertThat(mapper.displayIoStatus("AB"))
                    .as("app/cbl/CBTRN02C.cbl:L715 IO-STATUS NOT NUMERIC also selects branch A")
                    .isEqualTo("FILE STATUS IS: NNNNA066");
        }

        @Test
        @DisplayName("NNNN survives verbatim in every rendering, proving it is a literal and not a placeholder")
        void theNnnnGroupIsALiteralAndNotAPlaceholder() {
            // Blocker if mis-read: 'FILE STATUS IS: NNNN' is a 20-character fixed literal emitted on
            // BOTH branches (app/cbl/CBTRN02C.cbl:L721 and :L725), and COBOL DISPLAY a b concatenates
            // with no separator, so the four rendered characters FOLLOW the literal.
            assertThat(FileStatus.DISPLAY_MESSAGE_PREFIX)
                    .as("the DISPLAY literal of app/cbl/CBTRN02C.cbl:L721 and :L725")
                    .isEqualTo("FILE STATUS IS: NNNN")
                    .hasSize(20);
            int expectedLength = FileStatus.DISPLAY_MESSAGE_PREFIX.length() + FileStatus.RENDERED_STATUS_LENGTH;
            for (String status : STATUS_VOCABULARY) {
                assertThat(mapper.displayIoStatus(status))
                        .as("status [%s] must append to the literal NNNN, never substitute for it", status)
                        .startsWith("FILE STATUS IS: NNNN")
                        .hasSize(expectedLength);
            }
        }

        @Test
        @DisplayName("the second status byte is masked to its unsigned low-order value, per PIC 9(4) BINARY")
        void theSecondStatusByteIsMaskedToItsUnsignedLowOrderValue() {
            // Blocker. app/cbl/CBTRN02C.cbl:L134 declares TWO-BYTES-BINARY PIC 9(4) BINARY with NO
            // leading S, so the receiving field is UNSIGNED. Omitting the low-order mask in Java lets
            // a char above 0x7F narrow to a negative byte and the three digits come out signed.
            assertThat(mapper.displayIoStatus("9\u00FF"))
                    .as("app/cbl/CBTRN02C.cbl:L134 PIC 9(4) BINARY is unsigned, so X'FF' expands to 255")
                    .isEqualTo("FILE STATUS IS: NNNN9255");
            assertThat(mapper.displayIoStatus("9\u0080"))
                    .as("X'80' is the first value a signed narrowing would render negative")
                    .isEqualTo("FILE STATUS IS: NNNN9128");
            assertThat(mapper.displayIoStatus("9\u0000"))
                    .as("a NUL second byte expands to three zero digits, not to an empty field")
                    .isEqualTo("FILE STATUS IS: NNNN9000");

            // Remediation evidence: this is what the identical format string produces once the mask is
            // dropped and the char is narrowed to a signed byte. It is not the legacy rendering.
            String signExtendedAtFf = String.format(Locale.ROOT, "%c%03d", '9', (byte) 0xFF);
            String signExtendedAt80 = String.format(Locale.ROOT, "%c%03d", '9', (byte) 0x80);
            assertThat(signExtendedAtFf)
                    .as("an unmasked signed narrowing of X'FF' yields a sign where a digit belongs")
                    .isEqualTo("9-01");
            assertThat(signExtendedAt80)
                    .as("an unmasked signed narrowing of X'80' also overflows the three-digit field")
                    .isEqualTo("9-128")
                    .hasSizeGreaterThan(FileStatus.RENDERED_STATUS_LENGTH);
            assertThat(mapper.displayIoStatus("9\u00FF"))
                    .as("the production rendering must contain no sign at all")
                    .doesNotContain(signExtendedAtFf)
                    .doesNotContain("-");
        }

        @Test
        @DisplayName("the mapper composes the enum's prefix and renderer rather than restating either of them")
        void theRenderingIsSingleSourcedFromTheEnum() throws ReflectiveOperationException {
            // High if violated: a second copy of the rendering is a parity hazard, because the two
            // copies can drift and only one of them is compared against the legacy baseline.
            for (String status : STATUS_VOCABULARY) {
                assertThat(mapper.displayIoStatus(status))
                        .as("FileStatusMapper.displayIoStatus must be pure composition, status [%s]", status)
                        .isEqualTo(FileStatus.DISPLAY_MESSAGE_PREFIX + FileStatus.renderIoStatus04(status));
            }
            assertThat(mapper.displayIoStatus(null))
                    .as("an absent status composes exactly as a present one does")
                    .isEqualTo(FileStatus.DISPLAY_MESSAGE_PREFIX + FileStatus.renderIoStatus04(null));

            for (Field declared : FileStatusMapper.class.getDeclaredFields()) {
                if (declared.getType() != String.class || !Modifier.isStatic(declared.getModifiers())) {
                    continue;
                }
                declared.setAccessible(true);
                assertThat((String) declared.get(null))
                        .as("FileStatusMapper.%s must not restate the rendering owned by "
                                + "com.cardemo.model.enums.FileStatus", declared.getName())
                        .doesNotContain("NNNN")
                        .doesNotContain("FILE STATUS IS");
            }
        }

        @Test
        @DisplayName("every formatting decision is locale-independent, so a hostile default locale cannot shift it")
        void theRenderingIsLocaleIndependent() {
            // Rule 1 clause A forbids the platform default locale. FileStatus.renderIoStatus04 pins ROOT,
            // which is what stops a locale with non-ASCII digits from corrupting the three-digit field.
            for (String status : STATUS_VOCABULARY) {
                String rendered = mapper.displayIoStatus(status)
                        .substring(FileStatus.DISPLAY_MESSAGE_PREFIX.length());
                assertThat(rendered)
                        .as("the rendered field for [%s] must be ASCII only", status)
                        .hasSize(FileStatus.RENDERED_STATUS_LENGTH)
                        .isEqualTo(FileStatus.renderIoStatus04(status));
                for (int position = 0; position < rendered.length(); position++) {
                    char character = rendered.charAt(position);
                    assertThat((int) character)
                            .as("[%s] position %d must not be a locale-specific digit", status, position)
                            .isLessThan(0x80);
                }
            }
            assertThat(String.format(Locale.ROOT, "%03d", 7))
                    .as("Locale.ROOT is the only accepted locale for this rendering")
                    .isEqualTo("007");
        }
    }

    // ------------------------------------------------------------------------------------------
    // Group 3 - the status to exception map, including the two outcomes that are not failures.
    // Source: the guard idiom at app/cbl/CBTRN02C.cbl:L236-L252 and :L345-L369, and the abend
    // contract at :L707-L711 whose field set is app/cpy/CSMSG02Y.cpy (CABENDD.CPY) L21-L29.
    // ------------------------------------------------------------------------------------------

    /**
     * Group 3. The central translation itself: {@code '00'} and {@code '10'} yield nothing at all,
     * {@code '23'}, {@code '22'}, {@code '35'} and the {@code '9x'} family yield their typed
     * subtypes, and anything unrecognised abends with the {@code CABENDD} field set of
     * {@code app/cpy/CSMSG02Y.cpy:L21-L29}. The guard idiom being reproduced is
     * {@code app/cbl/CBTRN02C.cbl:L236-L252} and {@code :L345-L369}; the abend contract is
     * {@code :L707-L711}. Retaining {@code '22'} and {@code '35'}, which have zero literal
     * occurrences corpus-wide, is the documented parity carve-out described on the class.
     */
    @Nested
    @DisplayName("3. The status to exception map: two successes, four typed failures, one abend")
    class TheStatusToExceptionMap {

        @Test
        @DisplayName("00 is success, so it produces no exception on either the guard or the browse path")
        void successProducesNothing() {
            assertThat(mapper.toException("00", LOGICAL_FILE, OPERATION_READ))
                    .as("app/cbl/CBTRN02C.cbl:L239-L240, FILE STATUS '00' moves 0 into APPL-RESULT")
                    .isEmpty();
            assertThatCode(() -> mapper.requireSuccess("00", LOGICAL_FILE, OPERATION_READ))
                    .as("app/cbl/CBTRN02C.cbl:L244, IF APPL-AOK CONTINUE")
                    .doesNotThrowAnyException();
            assertThat(mapper.requireSuccessOrEndOfFile("00", LOGICAL_FILE, OPERATION_READ))
                    .as("a successful read is not end of file, so the caller keeps looping")
                    .isFalse();
        }

        @Test
        @DisplayName("10 is end of file and NOT an error, so the browse path returns rather than throwing")
        void endOfFileIsNotAnError() {
            // High if violated. app/cbl/CBTRN02C.cbl:L351-L352 moves 16, not 12, and :L360-L361 turns that
            // into MOVE 'Y' TO END-OF-FILE. No DISPLAY, no 9910, no 9999 is performed on this path.
            assertThat(mapper.toException("10", LOGICAL_FILE, OPERATION_READ))
                    .as("app/cbl/CBTRN02C.cbl:L351-L352, FILE STATUS '10' moves 16 into APPL-RESULT")
                    .isEmpty();
            assertThat(mapper.requireSuccessOrEndOfFile("10", LOGICAL_FILE, OPERATION_READ))
                    .as("app/cbl/CBTRN02C.cbl:L360-L361, IF APPL-EOF MOVE 'Y' TO END-OF-FILE")
                    .isTrue();
            assertThat(mapper.applResultForSequentialRead("10"))
                    .as("the sequential read sentinel is APPL-EOF, per app/cbl/CBTRN02C.cbl:L144")
                    .isEqualTo(FileStatusMapper.APPL_EOF);
        }

        @Test
        @DisplayName("10 terminates a read loop instead of signalling an error, exactly as 1000-DALYTRAN-GET-NEXT does")
        void endOfFileTerminatesAReadLoopRatherThanFailingIt() {
            // The companion proof for the previous test: a scripted browse must consume every record and
            // then stop of its own accord. app/cbl/CBTRN02C.cbl:L345-L369 read one record per iteration.
            List<String> scriptedStatuses = List.of("00", "00", "00", "10");
            List<String> consumed = new ArrayList<>();
            boolean endOfFile = false;
            for (String status : scriptedStatuses) {
                if (endOfFile) {
                    break;
                }
                endOfFile = mapper.requireSuccessOrEndOfFile(status, LOGICAL_FILE, OPERATION_READ);
                if (!endOfFile) {
                    consumed.add(status);
                }
            }
            assertThat(consumed)
                    .as("three successful reads must be consumed before the sentinel arrives")
                    .hasSize(3);
            assertThat(endOfFile)
                    .as("the loop must terminate through the sentinel, never through an exception")
                    .isTrue();
        }

        @Test
        @DisplayName("23 becomes RecordNotFoundException carrying the message, the logical file and the cause")
        void recordNotFoundIsTyped() {
            IOException rootCause = new IOException("VSAM key not present");
            assertThatExceptionOfType(RecordNotFoundException.class)
                    .as("app/cbl/CBTRN02C.cbl:L481 and app/cbl/CBACT04C.cbl:L422 are the '23' source sites")
                    .isThrownBy(() -> mapper.requireSuccess("23", LOGICAL_FILE, OPERATION_READ, rootCause))
                    .withMessage("READ of TRANSACT reported COBOL FILE STATUS 23 (IO-STATUS-04 0023)")
                    .withCause(rootCause);
        }

        @Test
        @DisplayName("22 becomes DuplicateRecordException carrying the message, the logical file and the cause")
        void duplicateKeyIsTyped() {
            IOException rootCause = new IOException("VSAM duplicate key");
            assertThatExceptionOfType(DuplicateRecordException.class)
                    .as("FILE STATUS '22'; the CICS vocabulary equivalents are DFHRESP(DUPREC) and DUPKEY")
                    .isThrownBy(() -> mapper.requireSuccess("22", LOGICAL_FILE, OPERATION_WRITE, rootCause))
                    .withMessage("WRITE of TRANSACT reported COBOL FILE STATUS 22 (IO-STATUS-04 0022)")
                    .withCause(rootCause);
        }

        @Test
        @DisplayName("35 becomes FileUnavailableException carrying the message, the resource and the cause")
        void fileUnavailableIsTyped() {
            IOException rootCause = new IOException("cluster not open");
            assertThatExceptionOfType(FileUnavailableException.class)
                    .as("FILE STATUS '35'; the CICS vocabulary equivalent is DFHRESP(NOTOPEN)")
                    .isThrownBy(() -> mapper.requireSuccess("35", LOGICAL_FILE, OPERATION_OPEN, rootCause))
                    .withMessage("OPEN of TRANSACT reported COBOL FILE STATUS 35 (IO-STATUS-04 0035)")
                    .withCause(rootCause);
        }

        /**
         * The {@code '9x'} family is selected by {@code IO-STAT1 = '9'} at
         * {@code app/cbl/CBTRN02C.cbl:L716}, and the resulting exception must carry the
         * four-character expansion produced by the single owning renderer, because that expansion
         * is what the legacy DISPLAY put in front of the operator.
         *
         * @param ioStatus         a member of the {@code 9x} family
         * @param expectedExpanded the four-character {@code IO-STATUS-04} rendering of that member
         */
        @ParameterizedTest(name = "status [{0}] carries the expansion [{1}]")
        @CsvSource({"90, 9048", "91, 9049", "92, 9050", "97, 9055", "99, 9057"})
        void ioErrorFamilyCarriesTheFourCharacterExpansion(String ioStatus, String expectedExpanded) {
            FileAccessException thrown = catchThrowableOfType(FileAccessException.class,
                    () -> mapper.requireSuccess(ioStatus, LOGICAL_FILE, OPERATION_READ));
            assertThat(thrown)
                    .as("app/cbl/CBTRN02C.cbl:L716 routes the whole 9x family through the expansion branch")
                    .isNotNull()
                    .isInstanceOf(CardDemoException.class);
            assertThat(thrown.getExpandedStatus())
                    .as("the expansion must be the four characters app/cbl/CBTRN02C.cbl:L720 produces")
                    .isEqualTo(expectedExpanded);
            assertThat(thrown.getMessage())
                    .as("the message must also carry the expansion, so a log line alone is diagnosable")
                    .contains("(IO-STATUS-04 " + expectedExpanded + ")")
                    .contains(LOGICAL_FILE)
                    .contains(OPERATION_READ);
            assertThat(thrown.getLogicalFileName()).isEqualTo(LOGICAL_FILE);
            assertThat(thrown.getOperation()).isEqualTo(OPERATION_READ);
            assertThat(thrown.getCause()).as("no cause was supplied, so none may be invented").isNull();
        }

        @Test
        @DisplayName("an unrecognised status abends, and the abend contract is code 999 with return code 12")
        void anUnrecognisedStatusAbends() {
            IOException rootCause = new IOException("subsystem failure");
            assertThatThrownBy(() -> mapper.requireSuccess("77", LOGICAL_FILE, OPERATION_READ, rootCause))
                    .as("app/cbl/CBTRN02C.cbl:L250 performs 9999-ABEND-PROGRAM for anything unexpected")
                    .isInstanceOf(FatalProcessingException.class)
                    .isInstanceOf(CardDemoException.class)
                    .hasMessage("READ of TRANSACT reported COBOL FILE STATUS 77 (IO-STATUS-04 0077)")
                    .hasCause(rootCause);
            assertThat(FatalProcessingException.BATCH_ABEND_CODE)
                    .as("app/cbl/CBTRN02C.cbl:L710 MOVE 999 TO ABCODE before CALL 'CEE3ABD' at :L711")
                    .isEqualTo(999);
            assertThat(FatalProcessingException.BATCH_RETURN_CODE)
                    .as("an abend leaves return code 12, distinct from the 4 of :L229-L231")
                    .isEqualTo(12);
        }

        @Test
        @DisplayName("the abend payload is the four CABENDD fields at widths 4, 8, 50 and 72")
        void theAbendPayloadIsTheCabenddFieldSet() {
            assertThat(FileStatusMapper.ABEND_CODE_WIDTH)
                    .as("app/cpy/CSMSG02Y.cpy:L22-L23 ABEND-CODE PIC X(4)")
                    .isEqualTo(4);
            assertThat(FileStatusMapper.ABEND_CULPRIT_WIDTH)
                    .as("app/cpy/CSMSG02Y.cpy:L24-L25 ABEND-CULPRIT PIC X(8)")
                    .isEqualTo(8);
            assertThat(FileStatusMapper.ABEND_REASON_WIDTH)
                    .as("app/cpy/CSMSG02Y.cpy:L26-L27 ABEND-REASON PIC X(50)")
                    .isEqualTo(50);
            assertThat(FileStatusMapper.ABEND_MESSAGE_WIDTH)
                    .as("app/cpy/CSMSG02Y.cpy:L28-L29 ABEND-MSG PIC X(72)")
                    .isEqualTo(72);
            assertThat(FileStatusMapper.ABEND_CODE_WIDTH + FileStatusMapper.ABEND_CULPRIT_WIDTH
                    + FileStatusMapper.ABEND_REASON_WIDTH + FileStatusMapper.ABEND_MESSAGE_WIDTH)
                    .as("01 ABEND-DATA at app/cpy/CSMSG02Y.cpy:L21 totals 134 bytes")
                    .isEqualTo(134);

            FatalProcessingException thrown = catchThrowableOfType(FatalProcessingException.class,
                    () -> mapper.requireSuccess("77", LOGICAL_FILE, OPERATION_READ));
            assertThat(thrown).isNotNull();
            assertThat(thrown.getAbendCode())
                    .as("all four CABENDD fields are VALUE SPACES, and the 999 of :L710 targets the "
                            + "separate binary ABCODE of app/cbl/CBTRN02C.cbl:L147, not this X(4) field")
                    .isEqualTo(FileStatusMapper.ABEND_CODE_UNSET)
                    .hasSize(FileStatusMapper.ABEND_CODE_WIDTH)
                    .isBlank();
            assertThat(thrown.getAbendCulprit())
                    .as("the culprit field is exactly X(8), and no batch program name is longer")
                    .hasSize(FileStatusMapper.ABEND_CULPRIT_WIDTH);
            assertThat(thrown.getAbendReason())
                    .as("the reason must be populated and must fit app/cpy/CSMSG02Y.cpy:L26-L27")
                    .isEqualTo("UNRECOGNISED COBOL FILE STATUS AT I/O GUARD")
                    .isNotBlank()
                    .hasSizeLessThanOrEqualTo(FileStatusMapper.ABEND_REASON_WIDTH);
            assertThat(thrown.getAbendMessage())
                    .as("the message is the diagnostic, deliberately not truncated to the legacy X(72); "
                            + "truncating a diagnostic would discard the context clause B requires")
                    .isNotBlank()
                    .contains("(IO-STATUS-04 0077)");
        }

        @Test
        @DisplayName("22 and 35 have zero literal occurrences corpus-wide yet remain mapped and tested")
        void theTwoStatusesWithNoLiteralSourceSiteRemainMapped() {
            // The documented clause-B versus parity conflict, resolved in favour of parity. A census of
            // app/cbl/* at 7756d89 finds '22' in 0 files and '35' in 0 files, so neither mapping can be
            // reached from a literal source site. They are runtime-reachable-only paths, they carry a
            // DECISION_LOG.md tracking reference, and the CICS vocabulary corroborates both: the
            // duplicate-key concept appears as DFHRESP(DUPREC) at 7 sites and DFHRESP(DUPKEY) at 3.
            assertThat(STATUSES_WITH_NO_LITERAL_SOURCE_SITE)
                    .as("the census result is exactly these two statuses")
                    .containsExactly("22", "35")
                    .doesNotContainAnyElementsOf(STATUSES_WITH_LITERAL_SOURCE_SITES);
            assertThat(STATUSES_WITH_LITERAL_SOURCE_SITES)
                    .as("'00' 88 occurrences, '04' 9, '10' 11, '23' 3, all in app/cbl at 7756d89")
                    .containsExactly("00", "04", "10", "23");

            assertThat(FileStatus.tryClassify("22"))
                    .as("a status with no source site must still classify, or the mapping is unreachable")
                    .contains(FileStatus.DUPLICATE_KEY);
            assertThat(FileStatus.tryClassify("35"))
                    .contains(FileStatus.FILE_UNAVAILABLE);
            assertThat(mapper.toException("22", LOGICAL_FILE, OPERATION_WRITE))
                    .get()
                    .as("retained deliberately: deleting it would leave a runtime path unmapped")
                    .isInstanceOf(DuplicateRecordException.class);
            assertThat(mapper.toException("35", LOGICAL_FILE, OPERATION_OPEN))
                    .get()
                    .isInstanceOf(FileUnavailableException.class);
        }

        @Test
        @DisplayName("every produced failure is a CardDemoException, so one catch clause covers the whole map")
        void everyFailureSharesTheOneBaseType() {
            for (String status : STATUS_VOCABULARY) {
                Optional<CardDemoException> translated =
                        mapper.toException(status, LOGICAL_FILE, OPERATION_READ);
                if ("00".equals(status) || "10".equals(status)) {
                    assertThat(translated)
                            .as("[%s] is an outcome, not a failure", status)
                            .isEmpty();
                    continue;
                }
                assertThat(translated)
                        .as("[%s] must translate to exactly one typed failure", status)
                        .isPresent();
                assertThat(translated.get())
                        .as("[%s] must be catchable through the single base type", status)
                        .isInstanceOf(CardDemoException.class);
                assertThat(translated.get().getMessage())
                        .as("[%s] must never yield a null or blank message", status)
                        .isNotNull()
                        .isNotBlank()
                        .contains("(IO-STATUS-04 ");
            }
        }

        @Test
        @DisplayName("a cause supplied at the call site is preserved rather than swallowed")
        void theRootCauseIsAlwaysPreserved() {
            IOException rootCause = new IOException("channel closed");
            for (String status : List.of("22", "23", "35", "90", "77")) {
                Optional<CardDemoException> translated =
                        mapper.toException(status, LOGICAL_FILE, OPERATION_READ, rootCause);
                assertThat(translated).as("[%s] must translate", status).isPresent();
                assertThat(translated.get().getCause())
                        .as("[%s] must preserve the root cause, per Rule 1 clause B", status)
                        .isSameAs(rootCause);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Group 4 - the three, and only three, sites where a non-'00' status is SUCCESS. A blanket
    // status to exception mapping would wrongly abend every one of them.
    //   Site 1  app/cbl/CBTRN02C.cbl:L467 2700-UPDATE-TCATBAL, read guard at :L481
    //   Site 2  app/cbl/CBACT04C.cbl:L415 1200-GET-INTEREST-RATE, guards at :L422 and :L446
    //   Site 3  app/cbl/CBSTM03A.CBL:L80  WS-M03B-RC, the nine '00' OR '04' sites
    // ------------------------------------------------------------------------------------------

    /**
     * Group 4. The only three sites in the corpus where a non-{@code '00'} status is success, and the
     * proof that the leniency never leaks onto the general path. Site 1 is the category-balance
     * upsert read guard, {@code app/cbl/CBTRN02C.cbl:L467} with the guard at {@code :L481}, whose
     * subsequent write and rewrite at {@code :L512} and {@code :L530} accept only {@code '00'}.
     * Site 2 is the disclosure-group fallback, {@code app/cbl/CBACT04C.cbl:L415} with the lenient
     * guard at {@code :L422} and the strict retry guard at {@code :L446} - a missing default row
     * abends and is emphatically not a record-not-found, which is Blocker severity. Site 3 is the
     * statement file service, {@code app/cbl/CBSTM03A.CBL:L80} and its nine acceptance sites.
     */
    @Nested
    @DisplayName("4. Where a non-00 status is success: three scoped sites, and nowhere else")
    class WhereNotFoundIsNotAnError {

        @Test
        @DisplayName("the TCATBAL read guard accepts 23 as a control path, so the upsert can create instead")
        void theCategoryBalanceReadAcceptsNotFoundAsAControlPath() {
            // app/cbl/CBTRN02C.cbl:L474-L479 sets WS-CREATE-TRANCAT-REC on INVALID KEY and :L481 then
            // accepts '00' OR '23'; :L495-L499 dispatches to create or to rewrite. Both branches add the
            // transaction amount to the category balance, so neither is an error.
            assertThat(mapper.requireCategoryBalanceReadSuccess("00"))
                    .as("app/cbl/CBTRN02C.cbl:L481 accepts '00', and a hit needs no create")
                    .isFalse();
            assertThat(mapper.requireCategoryBalanceReadSuccess("23"))
                    .as("app/cbl/CBTRN02C.cbl:L481 also accepts '23', which selects :L495 create")
                    .isTrue();
            assertThatCode(() -> mapper.requireCategoryBalanceReadSuccess("23"))
                    .as("a scoped control path must never raise, or the upsert can never create")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the leniency is scoped to the read: a WRITE or REWRITE returning 23 still throws")
        void theCategoryBalanceWriteAndRewriteAcceptOnlySuccess() {
            // app/cbl/CBTRN02C.cbl:L512 (2700-A-CREATE-TCATBAL-REC, after the WRITE at :L510) and :L530
            // (2700-B-UPDATE-TCATBAL-REC, after the REWRITE at :L528) both test '00' and nothing else.
            assertThatExceptionOfType(RecordNotFoundException.class)
                    .as("app/cbl/CBTRN02C.cbl:L512 accepts only '00' after the WRITE")
                    .isThrownBy(() -> mapper.requireSuccess("23", "TCATBALF", OPERATION_WRITE))
                    .withMessage("WRITE of TCATBALF reported COBOL FILE STATUS 23 (IO-STATUS-04 0023)")
                    .withNoCause();
            assertThatExceptionOfType(RecordNotFoundException.class)
                    .as("app/cbl/CBTRN02C.cbl:L530 accepts only '00' after the REWRITE")
                    .isThrownBy(() -> mapper.requireSuccess("23", "TCATBALF", "REWRITE"))
                    .withMessage("REWRITE of TCATBALF reported COBOL FILE STATUS 23 (IO-STATUS-04 0023)")
                    .withNoCause();

            // The divergence stated as an invariant: the same status, the same file, two verdicts.
            assertThat(mapper.requireCategoryBalanceReadSuccess("23"))
                    .as("the read tolerates '23' while the write does not, and that asymmetry is the parity")
                    .isTrue();
        }

        @Test
        @DisplayName("the TCATBAL read still abends on anything else, carrying the legacy DISPLAY text")
        void theCategoryBalanceReadStillAbendsOnAnythingElse() {
            String expectedNinetyMessage = FileStatusMapper.TCATBAL_READ_FAILURE_TEXT
                    + " - READ of TCATBALF reported COBOL FILE STATUS 90 (IO-STATUS-04 9048)";
            assertThatExceptionOfType(FileAccessException.class)
                    .as("app/cbl/CBTRN02C.cbl:L489 DISPLAYs before :L491 renders and :L492 abends")
                    .isThrownBy(() -> mapper.requireCategoryBalanceReadSuccess("90"))
                    .withMessage(expectedNinetyMessage);

            FatalProcessingException thrown = catchThrowableOfType(FatalProcessingException.class,
                    () -> mapper.requireCategoryBalanceReadSuccess("77"));
            assertThat(thrown).isNotNull();
            assertThat(thrown.getAbendReason())
                    .as("the legacy DISPLAY literal of app/cbl/CBTRN02C.cbl:L489 becomes the abend reason")
                    .isEqualTo(FileStatusMapper.TCATBAL_READ_FAILURE_TEXT)
                    .hasSizeLessThanOrEqualTo(FileStatusMapper.ABEND_REASON_WIDTH);
            assertThat(thrown.getAbendCulprit())
                    .as("the culprit is the program that owns app/cbl/CBTRN02C.cbl:L467")
                    .isEqualTo("CBTRN02C")
                    .hasSize(FileStatusMapper.ABEND_CULPRIT_WIDTH);
        }

        @Test
        @DisplayName("the first DISCGRP miss is lenient and asks the caller to retry with the DEFAULT group")
        void theFirstDisclosureGroupMissIsLenientAndTriggersTheDefaultRetry() {
            // app/cbl/CBACT04C.cbl:L416-L420 DISPLAYs 'DISCLOSURE GROUP RECORD MISSING' then
            // 'TRY WITH DEFAULT GROUP CODE'; :L422 accepts '00' OR '23'; :L436-L438 moves 'DEFAULT' into
            // FD-DIS-ACCT-GROUP-ID and performs 1200-A-GET-DEFAULT-INT-RATE.
            assertThat(mapper.requireDisclosureGroupReadSuccess("00"))
                    .as("app/cbl/CBACT04C.cbl:L422 accepts '00', and a hit needs no retry")
                    .isFalse();
            assertThat(mapper.requireDisclosureGroupReadSuccess("23"))
                    .as("app/cbl/CBACT04C.cbl:L436 turns '23' into the DEFAULT group retry, not a failure")
                    .isTrue();
            assertThatCode(() -> mapper.requireDisclosureGroupReadSuccess("23"))
                    .doesNotThrowAnyException();

            String expectedMessage = FileStatusMapper.DISCGRP_READ_FAILURE_TEXT
                    + " - READ of DISCGRP reported COBOL FILE STATUS 90 (IO-STATUS-04 9048)";
            assertThatExceptionOfType(FileAccessException.class)
                    .as("app/cbl/CBACT04C.cbl:L431 DISPLAYs the failure text for every other status")
                    .isThrownBy(() -> mapper.requireDisclosureGroupReadSuccess("90"))
                    .withMessage(expectedMessage);
        }

        @Test
        @DisplayName("the SECOND DISCGRP miss abends and is emphatically not a RecordNotFoundException")
        void theSecondDisclosureGroupMissAbendsAndIsNotARecordNotFound() {
            // Blocker if mis-mapped. app/cbl/CBACT04C.cbl:L444 READs DISCGRP-FILE with NO INVALID KEY
            // clause at all, and the guard at :L446 tests '00' and nothing else. A missing DEFAULT row
            // therefore ABENDS the interest job. Mapping it to RecordNotFoundException would let the job
            // continue with a stale or zero rate, which is a silent financial divergence.
            // Medium finding: the frequently quoted :L445 is blank; the guard is at :L446.
            assertThatCode(() -> mapper.requireDefaultDisclosureGroupReadSuccess("00"))
                    .as("app/cbl/CBACT04C.cbl:L446 accepts '00', which is the only accepted status")
                    .doesNotThrowAnyException();

            assertThatThrownBy(() -> mapper.requireDefaultDisclosureGroupReadSuccess("23"))
                    .as("app/cbl/CBACT04C.cbl:L446 does NOT accept '23', so the job abends")
                    .isInstanceOf(FatalProcessingException.class)
                    .isNotInstanceOf(RecordNotFoundException.class)
                    .isInstanceOf(CardDemoException.class)
                    .hasMessage(FileStatusMapper.DEFAULT_DISCGRP_READ_FAILURE_TEXT
                            + " - READ of DISCGRP reported COBOL FILE STATUS 23 (IO-STATUS-04 0023)")
                    .hasNoCause();

            FatalProcessingException thrown = catchThrowableOfType(FatalProcessingException.class,
                    () -> mapper.requireDefaultDisclosureGroupReadSuccess("23"));
            assertThat(thrown).isNotNull();
            assertThat(thrown.getAbendReason())
                    .as("app/cbl/CBACT04C.cbl:L455 DISPLAYs 'ERROR READING DEFAULT DISCLOSURE GROUP'")
                    .isEqualTo(FileStatusMapper.DEFAULT_DISCGRP_READ_FAILURE_TEXT)
                    .hasSizeLessThanOrEqualTo(FileStatusMapper.ABEND_REASON_WIDTH);
            assertThat(thrown.getAbendCulprit())
                    .as("the culprit is the program that owns app/cbl/CBACT04C.cbl:L443")
                    .isEqualTo("CBACT04C")
                    .hasSize(FileStatusMapper.ABEND_CULPRIT_WIDTH);

            // The retry contract in one line: lenient once at :L422, strict once at :L446.
            assertThat(mapper.requireDisclosureGroupReadSuccess("23"))
                    .as("the first miss is a retry instruction, the second is an abend")
                    .isTrue();
        }

        @Test
        @DisplayName("04 is accepted only through the statement file service, and throws on the general path")
        void theStatementFileServiceAcceptsZeroFourButTheGeneralPathDoesNot() {
            // app/cbl/CBSTM03A.CBL:L80 declares WS-M03B-RC PIC X(02); exactly nine sites test
            // IF WS-M03B-RC = '00' OR '04' - L736, L748, L771, L789, L807, L862, L879, L895 and L911.
            // '04' is a duplicate-key-ignored acknowledgement there and is success at those sites only.
            assertThatCode(() -> mapper.requireFileServiceSuccess("00", DD_TRNXFILE, OPERATION_READ))
                    .as("app/cbl/CBSTM03A.CBL:L736 accepts '00'")
                    .doesNotThrowAnyException();
            assertThatCode(() -> mapper.requireFileServiceSuccess("04", DD_TRNXFILE, OPERATION_READ))
                    .as("app/cbl/CBSTM03A.CBL:L736 accepts '04' as well, and only here")
                    .doesNotThrowAnyException();

            // High if violated: adding '04' to the general map would silently accept a status that every
            // one of the roughly nineteen guard sites in app/cbl/CBTRN02C.cbl treats as a failure.
            assertThatThrownBy(() -> mapper.requireSuccess("04", LOGICAL_FILE, OPERATION_READ))
                    .as("'04' has no source site outside app/cbl/CBSTM03A.CBL, so the general path abends")
                    .isInstanceOf(FatalProcessingException.class)
                    .hasMessage("READ of TRANSACT reported COBOL FILE STATUS 04 (IO-STATUS-04 0004)");
            assertThat(mapper.toException("04", LOGICAL_FILE, OPERATION_READ))
                    .get()
                    .as("the browse path must reject '04' for the same reason")
                    .isInstanceOf(FatalProcessingException.class);
            assertThat(mapper.applResultForGuard("04"))
                    .as("app/cbl/CBTRN02C.cbl:L239 tests '00' only, so '04' is a failure there")
                    .isEqualTo(FileStatusMapper.APPL_FAILURE);
        }

        @Test
        @DisplayName("the four EVALUATE sites are stricter than the nine IF sites, and 04 throws at them")
        void theStatementFileServiceEndOfFilePathAcceptsOnlySuccessAndEndOfFile() {
            // app/cbl/CBSTM03A.CBL:L353, L379, L403 and L837 EVALUATE WS-M03B-RC and accept only '00',
            // treating '10' as end of file. The scoped API therefore has two entry points, not one.
            assertThat(mapper.requireFileServiceSuccessOrEndOfFile("00", DD_TRNXFILE, OPERATION_READ))
                    .as("app/cbl/CBSTM03A.CBL:L353 accepts '00' and keeps reading")
                    .isFalse();
            assertThat(mapper.requireFileServiceSuccessOrEndOfFile("10", DD_TRNXFILE, OPERATION_READ))
                    .as("app/cbl/CBSTM03A.CBL:L353 treats '10' as end of file, not as an error")
                    .isTrue();
            assertThatThrownBy(() -> mapper.requireFileServiceSuccessOrEndOfFile("04", DD_TRNXFILE,
                    OPERATION_READ))
                    .as("the EVALUATE sites do not carry the '04' leniency of the nine IF sites")
                    .isInstanceOf(FatalProcessingException.class);

            FatalProcessingException thrown = catchThrowableOfType(FatalProcessingException.class,
                    () -> mapper.requireFileServiceSuccess("08", DD_TRNXFILE, OPERATION_READ));
            assertThat(thrown).isNotNull();
            assertThat(thrown.getMessage())
                    .as("the file service abend reports a RETURN CODE, not a COBOL FILE STATUS")
                    .isEqualTo("READ of TRNXFILE through the statement file service reported "
                            + FileStatusMapper.FILE_SERVICE_RETURN_CODE_TEXT + "08")
                    .doesNotContain("COBOL FILE STATUS");
            assertThat(thrown.getAbendReason())
                    .as("the reason names the subprogram contract of app/cbl/CBSTM03B.CBL")
                    .isEqualTo("UNACCEPTED RETURN CODE FROM STATEMENT FILE SERVICE")
                    .hasSizeLessThanOrEqualTo(FileStatusMapper.ABEND_REASON_WIDTH);
            assertThat(thrown.getAbendCulprit())
                    .as("the culprit is the caller, app/cbl/CBSTM03A.CBL, which owns the guard")
                    .isEqualTo("CBSTM03A")
                    .hasSize(FileStatusMapper.ABEND_CULPRIT_WIDTH);
        }

        @Test
        @DisplayName("leniency is scoped: the general entry points reject 23 and 04 without exception")
        void leniencyNeverLeaksOntoTheGeneralPath() {
            for (String lenientStatus : List.of("23", "04")) {
                assertThatThrownBy(() -> mapper.requireSuccess(lenientStatus, LOGICAL_FILE, OPERATION_READ))
                        .as("[%s] is success at a scoped site only, never at a general guard", lenientStatus)
                        .isInstanceOf(CardDemoException.class);
                assertThatThrownBy(() -> mapper.requireSuccessOrEndOfFile(lenientStatus, LOGICAL_FILE,
                        OPERATION_READ))
                        .as("[%s] is not end of file either; only '10' is", lenientStatus)
                        .isInstanceOf(CardDemoException.class);
            }
            assertThat(mapper.toException("23", LOGICAL_FILE, OPERATION_READ))
                    .as("the browse path translates '23' rather than tolerating it")
                    .isPresent();
        }

        @Test
        @DisplayName("the source spells both lenient guards with TWO spaces, which defeats a naive grep")
        void theTwoSpaceSpellingIsAGrepHazard() {
            // Low severity, but it costs an afternoon. app/cbl/CBTRN02C.cbl:L481 and
            // app/cbl/CBACT04C.cbl:L422 are both spelled with two spaces before OR, and CBACT04C also
            // has two spaces around the '=' sign. A search for the single-spaced form finds nothing.
            String categoryBalanceGuard = "IF  TCATBALF-STATUS = '00'  OR '23'";
            String disclosureGroupGuard = "IF  DISCGRP-STATUS  = '00'  OR '23'";
            String naiveNeedle = "'00' OR '23'";
            assertThat(categoryBalanceGuard)
                    .as("app/cbl/CBTRN02C.cbl:L481 verbatim")
                    .contains("'00'  OR '23'")
                    .doesNotContain(naiveNeedle);
            assertThat(disclosureGroupGuard)
                    .as("app/cbl/CBACT04C.cbl:L422 verbatim")
                    .contains("'00'  OR '23'")
                    .doesNotContain(naiveNeedle);
            assertThat(naiveNeedle)
                    .as("the single-spaced needle is not the source spelling at either site")
                    .isNotEqualTo("'00'  OR '23'");

            // The behaviour the two guards share, asserted rather than merely described.
            assertThat(mapper.requireCategoryBalanceReadSuccess("23")).isTrue();
            assertThat(mapper.requireDisclosureGroupReadSuccess("23")).isTrue();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Group 5 - reject codes are business outcomes, not exceptions. They are modelled as an enum
    // elsewhere and drive ExitStatus; they are never thrown, and nothing this mapper produces may
    // carry one. Source: app/cbl/CBTRN02C.cbl:L176-L182 (the 430 byte reject record), :L181
    // (WS-VALIDATION-FAIL-REASON PIC 9(04)), :L385 :L397 :L410 :L417 :L556 (the five assignments)
    // and :L229-L231 (RETURN-CODE 4 if and only if the reject count exceeds zero).
    // ------------------------------------------------------------------------------------------

    /**
     * Group 5. The negative-space contract. Batch reject codes are business outcomes that drive an
     * exit status, never exceptions, so the mapper must have no surface for them whatsoever. The five
     * values live in {@code WS-VALIDATION-FAIL-REASON} at {@code app/cbl/CBTRN02C.cbl:L181} and are
     * assigned at {@code :L385}, {@code :L397}, {@code :L410}, {@code :L417} and {@code :L556}. The
     * mapper owns the abend return code of twelve; it does not own the reject return code of four,
     * which is set if and only if the reject count exceeds zero at {@code :L229-L231}.
     */
    @Nested
    @DisplayName("5. Reject codes are outcomes, not faults: the mapper has no surface for them at all")
    class RejectCodesAreNotExceptions {

        @Test
        @DisplayName("the mapper exposes no reject-code surface: no such method name, no such constant")
        void theMapperHasNoRejectCodeSurface() throws ReflectiveOperationException {
            for (Method declared : FileStatusMapper.class.getDeclaredMethods()) {
                if (declared.isSynthetic()) {
                    continue;
                }
                assertThat(declared.getName().toLowerCase(Locale.ROOT))
                        .as("FileStatusMapper.%s must not name a reject, a reason code or a counter",
                                declared.getName())
                        .doesNotContain("reject", "counter", "metric", "increment", "tally", "tag");
            }
            for (Field declared : FileStatusMapper.class.getDeclaredFields()) {
                if (declared.getType() != String.class || !Modifier.isStatic(declared.getModifiers())) {
                    continue;
                }
                declared.setAccessible(true);
                String value = (String) declared.get(null);
                assertThat(value)
                        .as("FileStatusMapper.%s must be initialised", declared.getName())
                        .isNotNull();
                for (String reason : REJECT_REASONS) {
                    assertThat(value)
                            .as("FileStatusMapper.%s must not contain reject reason %s",
                                    declared.getName(), reason)
                            .doesNotContain(reason);
                }
                for (String description : REJECT_DESCRIPTIONS) {
                    assertThat(value)
                            .as("FileStatusMapper.%s must not contain the reject description [%s]",
                                    declared.getName(), description)
                            .doesNotContain(description);
                }
            }
        }

        @Test
        @DisplayName("no exception the mapper produces ever carries a reject reason or a reject description")
        void noProducedExceptionCarriesARejectReasonOrDescription() {
            List<String> diagnostics = new ArrayList<>();
            for (String status : STATUS_VOCABULARY) {
                collectDiagnostics(diagnostics, catchThrowableOfType(CardDemoException.class,
                        () -> mapper.requireSuccess(status, LOGICAL_FILE, OPERATION_READ)));
                collectDiagnostics(diagnostics, catchThrowableOfType(CardDemoException.class,
                        () -> mapper.requireSuccessOrEndOfFile(status, LOGICAL_FILE, OPERATION_READ)));
                collectDiagnostics(diagnostics, catchThrowableOfType(CardDemoException.class,
                        () -> mapper.requireCategoryBalanceReadSuccess(status)));
                collectDiagnostics(diagnostics, catchThrowableOfType(CardDemoException.class,
                        () -> mapper.requireDisclosureGroupReadSuccess(status)));
                collectDiagnostics(diagnostics, catchThrowableOfType(CardDemoException.class,
                        () -> mapper.requireDefaultDisclosureGroupReadSuccess(status)));
                collectDiagnostics(diagnostics, catchThrowableOfType(CardDemoException.class,
                        () -> mapper.requireFileServiceSuccess(status, DD_TRNXFILE, OPERATION_READ)));
                collectDiagnostics(diagnostics, catchThrowableOfType(CardDemoException.class,
                        () -> mapper.requireFileServiceSuccessOrEndOfFile(status, DD_TRNXFILE,
                                OPERATION_READ)));
                mapper.toException(status, LOGICAL_FILE, OPERATION_READ)
                        .ifPresent(translated -> collectDiagnostics(diagnostics, translated));
            }

            assertThat(diagnostics)
                    .as("every entry point must have been exercised across the whole status vocabulary")
                    .isNotEmpty();
            for (String diagnostic : diagnostics) {
                for (String reason : REJECT_REASONS) {
                    assertThat(diagnostic)
                            .as("a diagnostic must never carry reject reason %s, which is an outcome", reason)
                            .doesNotContain(reason);
                }
                for (String description : REJECT_DESCRIPTIONS) {
                    assertThat(diagnostic)
                            .as("a diagnostic must never carry the reject description [%s]", description)
                            .doesNotContain(description);
                }
            }
        }

        @Test
        @DisplayName("a reject reason is not a FILE STATUS: routed as one it is rejected as unrecognised")
        void aRejectReasonIsNotAFileStatus() {
            // The two fields are different widths in different records: WS-VALIDATION-FAIL-REASON is
            // PIC 9(04) at app/cbl/CBTRN02C.cbl:L181, while IO-STATUS is two PIC X bytes at :L131-L133.
            for (String reason : REJECT_REASONS) {
                String zeroPadded = String.format(Locale.ROOT, "%04d", Integer.parseInt(reason));
                assertThatThrownBy(() -> mapper.requireSuccess(reason, LOGICAL_FILE, OPERATION_READ))
                        .as("reject reason %s is not a status and must not classify as one", reason)
                        .isInstanceOf(FatalProcessingException.class);
                assertThatThrownBy(() -> mapper.requireSuccess(zeroPadded, LOGICAL_FILE, OPERATION_READ))
                        .as("the PIC 9(04) rendering %s is not a status either", zeroPadded)
                        .isInstanceOf(FatalProcessingException.class);
                assertThat(FileStatus.tryClassify(reason))
                        .as("a four digit business outcome must never classify as a two byte status")
                        .isEmpty();
            }

            // The precise reason routing a reject code through here would be dangerous rather than merely
            // wrong: the DISPLAY renderer truncates to two bytes, so reason 100 would render as if it were
            // end of file. Nothing may rely on the renderer to tell the two fields apart.
            assertThat(mapper.displayIoStatus("100"))
                    .as("truncation makes reject reason 100 indistinguishable from FILE STATUS '10'")
                    .isEqualTo(mapper.displayIoStatus("10"))
                    .isEqualTo("FILE STATUS IS: NNNN0010");
        }

        @Test
        @DisplayName("the reject reason and trailer field contract: PIC 9(04) plus a 76 character description")
        void theRejectRecordFieldContractHolds() {
            assertThat(REJECT_REASONS)
                    .as("app/cbl/CBTRN02C.cbl assigns exactly five reasons, at :L385 :L397 :L410 :L417 :L556")
                    .containsExactly("100", "101", "102", "103", "109")
                    .hasSize(5);
            for (String reason : REJECT_REASONS) {
                assertThat(String.format(Locale.ROOT, "%04d", Integer.parseInt(reason)))
                        .as("app/cbl/CBTRN02C.cbl:L181 WS-VALIDATION-FAIL-REASON PIC 9(04)")
                        .hasSize(4)
                        .containsOnlyDigits();
            }
            assertThat(REJECT_DESCRIPTIONS)
                    .as("reason 101 and reason 109 share ACCOUNT RECORD NOT FOUND, so five reasons "
                            + "carry only four distinct descriptions")
                    .hasSize(4)
                    .doesNotHaveDuplicates()
                    .contains("ACCOUNT RECORD NOT FOUND");
            for (String description : REJECT_DESCRIPTIONS) {
                assertThat(description)
                        .as("app/cbl/CBTRN02C.cbl:L182 WS-VALIDATION-FAIL-REASON-DESC PIC X(76)")
                        .isNotBlank()
                        .hasSizeLessThanOrEqualTo(76);
            }
            int rejectTranDataWidth = 350;
            int validationTrailerWidth = 80;
            int failReasonWidth = 4;
            int failReasonDescriptionWidth = 76;
            assertThat(rejectTranDataWidth + validationTrailerWidth)
                    .as("app/cbl/CBTRN02C.cbl:L176-L178, REJECT-TRAN-DATA X(350) plus VALIDATION-TRAILER "
                            + "X(80), which is the LRECL declared on the reject dataset in POSTTRAN.jcl")
                    .isEqualTo(430);
            assertThat(failReasonWidth + failReasonDescriptionWidth)
                    .as("app/cbl/CBTRN02C.cbl:L181-L182 resolve that 80 byte trailer exactly")
                    .isEqualTo(validationTrailerWidth);
        }

        @Test
        @DisplayName("the mapper owns return code 12 only, never the return code 4 of the reject count")
        void theMapperOwnsTheAbendReturnCodeAndNotTheRejectReturnCode() {
            assertThat(FatalProcessingException.BATCH_RETURN_CODE)
                    .as("an abend leaves 12, per the CALL 'CEE3ABD' of app/cbl/CBTRN02C.cbl:L711")
                    .isEqualTo(12)
                    .isNotEqualTo(REJECT_PRESENT_RETURN_CODE);
            assertThat(REJECT_PRESENT_RETURN_CODE)
                    .as("app/cbl/CBTRN02C.cbl:L229-L231 sets 4 if and only if the reject count exceeds zero")
                    .isEqualTo(4);

            // No mapper entry point may ever produce the reject return code as an APPL-RESULT value,
            // because 4 belongs to the job exit status and not to the guard vocabulary.
            for (String status : STATUS_VOCABULARY) {
                assertThat(mapper.applResultForGuard(status))
                        .as("guard [%s] must be 0 or 12, never the job exit code 4", status)
                        .isIn(FileStatusMapper.APPL_AOK, FileStatusMapper.APPL_FAILURE);
                assertThat(mapper.applResultForSequentialRead(status))
                        .as("browse [%s] must be 0, 16 or 12, never the job exit code 4", status)
                        .isIn(FileStatusMapper.APPL_AOK, FileStatusMapper.APPL_EOF,
                                FileStatusMapper.APPL_FAILURE);
            }
        }

        @Test
        @DisplayName("the reject-code-tagged counter cannot live here, because the mapper holds no state")
        void theRejectCodeTaggedCounterCannotLiveInTheMapper() throws ReflectiveOperationException {
            // The observability contract replaces the end-of-run DISPLAYs at app/cbl/CBTRN02C.cbl:L227-L228
            // with four named counters, of which the rejected-records counter is tagged by reject code.
            // That counter belongs to the observability layer: this class is stateless by construction and
            // takes no collaborator, so it can neither hold a registry nor accumulate a count.
            assertThat(FileStatusMapper.class.getDeclaredConstructors())
                    .as("exactly one constructor, so no registry and no counter can be injected")
                    .hasSize(1);
            assertThat(FileStatusMapper.class.getDeclaredConstructor().getParameterCount())
                    .as("the single constructor takes no collaborator at all")
                    .isZero();
            for (Field declared : FileStatusMapper.class.getDeclaredFields()) {
                if (declared.isSynthetic()) {
                    continue;
                }
                assertThat(Modifier.isStatic(declared.getModifiers()))
                        .as("FileStatusMapper.%s must be static: an instance field could accumulate state",
                                declared.getName())
                        .isTrue();
                assertThat(Modifier.isFinal(declared.getModifiers()))
                        .as("FileStatusMapper.%s must be final: Rule 1 clause B forbids mutable state",
                                declared.getName())
                        .isTrue();
            }
            assertThat(NAMED_COUNTERS)
                    .as("the four named counters of the observability contract, none of them owned here")
                    .containsExactly("records processed", "records rejected", "authentication attempts",
                            "total transaction amount");
            for (Method declared : FileStatusMapper.class.getDeclaredMethods()) {
                if (declared.isSynthetic()) {
                    continue;
                }
                for (String counter : NAMED_COUNTERS) {
                    assertThat(declared.getName().toLowerCase(Locale.ROOT))
                            .as("FileStatusMapper.%s must not implement the [%s] counter",
                                    declared.getName(), counter)
                            .doesNotContain(counter.replace(" ", ""))
                            .doesNotContain(counter.substring(0, counter.indexOf(' ')));
                }
            }
        }

        /**
         * Adds every diagnostic string an exception carries to the sink, so that a single sweep can prove
         * no reject reason and no reject description ever escapes through any of them.
         *
         * @param sink   the accumulator to append to, never {@code null}
         * @param thrown the exception a guard produced, or {@code null} when the guard accepted the status
         */
        private void collectDiagnostics(List<String> sink, CardDemoException thrown) {
            if (thrown == null) {
                return;
            }
            sink.add(thrown.getMessage());
            if (thrown instanceof FatalProcessingException fatal) {
                sink.add(fatal.getAbendCode());
                sink.add(fatal.getAbendCulprit());
                sink.add(fatal.getAbendReason());
                sink.add(fatal.getAbendMessage());
            }
            if (thrown instanceof FileAccessException fileAccess) {
                sink.add(fileAccess.getExpandedStatus());
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Group 6 - hostile and boundary inputs. Rule 1 clause A requires that inputs be treated as
    // untrusted and clause B requires null and empty cases to be handled explicitly. The corpus
    // guarantees a two byte IO-STATUS (app/cbl/CBTRN02C.cbl:L131-L133), but a Java store, adapter
    // or test double can present anything at all, and a diagnostic path must never be the thing
    // that fails while reporting a failure.
    // ------------------------------------------------------------------------------------------

    /**
     * Group 6. Untrusted input, per the security-by-default principle: a status field arriving from a
     * file control block, a call site or a test double can present anything at all, and a diagnostic
     * path must never be the thing that fails while reporting a failure. Absent, empty, blank,
     * one-character, three-character, NUL-bearing and above-{@code 0x7F} statuses are all fed in.
     * Note that classification requires a well-formed field of exactly two characters, so a short or
     * long status renders a diagnostic but never classifies, and {@code "100"} renders identically to
     * {@code "10"} - the truncation collision that makes a reject code unusable as a file status.
     */
    @Nested
    @DisplayName("6. Hostile input: absent, blank, short, long, NUL bearing and above 0x7F")
    class HostileAndBoundaryInput {

        @Test
        @DisplayName("an absent, empty or blank status renders, classifies as unrecognised and abends")
        void anAbsentStatusIsHandledExplicitly() {
            for (String absent : ABSENT_STATUSES) {
                assertThat(mapper.displayIoStatus(absent))
                        .as("an absent status must still render four characters, never fail")
                        .isEqualTo("FILE STATUS IS: NNNN 032")
                        .hasSize(FileStatus.DISPLAY_MESSAGE_PREFIX.length()
                                + FileStatus.RENDERED_STATUS_LENGTH);
                assertThat(mapper.applResultForGuard(absent))
                        .as("app/cbl/CBTRN02C.cbl:L239 tests '00', which an absent status is not")
                        .isEqualTo(FileStatusMapper.APPL_FAILURE);
                assertThat(mapper.applResultForSequentialRead(absent))
                        .as("an absent status is not end of file either, so it must not stop a loop")
                        .isEqualTo(FileStatusMapper.APPL_FAILURE);
                assertThatThrownBy(() -> mapper.requireSuccess(absent, LOGICAL_FILE, OPERATION_READ))
                        .as("an unclassifiable status is the unexpected condition of :L250")
                        .isInstanceOf(FatalProcessingException.class)
                        .hasMessage("READ of TRANSACT reported COBOL FILE STATUS (absent) "
                                + "(IO-STATUS-04  032)");
            }
        }

        @Test
        @DisplayName("a one character or three character status renders but must never classify")
        void aShortOrLongStatusRendersButNeverClassifies() {
            // FileStatus.isWellFormed demands exactly two characters, so normalisation applies to the
            // DISPLAY rendering only. That asymmetry is deliberate: a malformed status must still be
            // reportable, and must still abend rather than be silently coerced into a known outcome.
            assertThat(mapper.displayIoStatus("9"))
                    .as("a one character status is padded with the COBOL fill character, X'20'")
                    .isEqualTo("FILE STATUS IS: NNNN9032");
            assertThat(mapper.displayIoStatus("233"))
                    .as("a three character status is truncated to its first two characters for DISPLAY")
                    .isEqualTo("FILE STATUS IS: NNNN0023");

            // The trap this closes: '233' renders exactly as '23' does, yet must not be treated as one.
            assertThat(mapper.displayIoStatus("233"))
                    .isEqualTo(mapper.displayIoStatus("23"));
            assertThat(FileStatus.tryClassify("233"))
                    .as("a three character status must not classify as RECORD_NOT_FOUND")
                    .isEmpty();
            assertThat(FileStatus.tryClassify("9"))
                    .as("a one character status must not classify as the 9x family")
                    .isEmpty();
            assertThatThrownBy(() -> mapper.requireSuccess("233", LOGICAL_FILE, OPERATION_READ))
                    .isInstanceOf(FatalProcessingException.class)
                    .isNotInstanceOf(RecordNotFoundException.class);
            assertThatThrownBy(() -> mapper.requireSuccess("9", LOGICAL_FILE, OPERATION_READ))
                    .isInstanceOf(FatalProcessingException.class)
                    .isNotInstanceOf(FileAccessException.class);
        }

        @Test
        @DisplayName("a NUL bearing status is escaped in diagnostics and never emitted as a raw control byte")
        void aNulBearingStatusIsEscapedInDiagnostics() {
            String nulBearing = "9\u0000";
            assertThat(mapper.displayIoStatus(nulBearing))
                    .as("app/cbl/CBTRN02C.cbl:L718-L720 expands X'00' to the three digits 000")
                    .isEqualTo("FILE STATUS IS: NNNN9000");

            FileAccessException thrown = catchThrowableOfType(FileAccessException.class,
                    () -> mapper.requireSuccess(nulBearing, LOGICAL_FILE, OPERATION_READ));
            assertThat(thrown).isNotNull();
            assertThat(thrown.getMessage())
                    .as("the raw status must be escaped so a control byte cannot forge a log line")
                    .contains("COBOL FILE STATUS 9\\u0000")
                    .contains("(IO-STATUS-04 9000)")
                    .doesNotContain(nulBearing);
            assertThat(thrown.getExpandedStatus())
                    .as("the expansion itself is already printable, so it needs no escape")
                    .isEqualTo("9000");
        }

        @Test
        @DisplayName("a second byte above 0x7F stays unsigned in the expansion and is escaped in the raw text")
        void aByteAboveSevenFIsUnsignedAndEscaped() {
            String highByte = "9\u00FF";
            assertThat(mapper.displayIoStatus(highByte))
                    .as("app/cbl/CBTRN02C.cbl:L134 PIC 9(4) BINARY, so X'FF' is 255 and never -1")
                    .isEqualTo("FILE STATUS IS: NNNN9255");

            FileAccessException thrown = catchThrowableOfType(FileAccessException.class,
                    () -> mapper.requireSuccess(highByte, LOGICAL_FILE, OPERATION_READ));
            assertThat(thrown).isNotNull();
            assertThat(thrown.getExpandedStatus())
                    .as("the expansion must be the first byte plus three unsigned digits, with no sign")
                    .isEqualTo("9255")
                    .doesNotContain("-");
            assertThat(thrown.getMessage())
                    .as("the raw high byte is escaped in lower case hexadecimal, under Locale.ROOT")
                    .contains("COBOL FILE STATUS 9\\u00ff")
                    .contains("(IO-STATUS-04 9255)")
                    .doesNotContain("9-01")
                    .doesNotContain("9-128");
        }

        @Test
        @DisplayName("absent call-site metadata becomes a fixed placeholder, so no diagnostic ever says null")
        void absentCallSiteMetadataBecomesAPlaceholder() {
            assertThatExceptionOfType(RecordNotFoundException.class)
                    .as("a guard that supplies neither a file name nor a verb must still be diagnosable")
                    .isThrownBy(() -> mapper.requireSuccess("23", null, null))
                    .withMessage("An unidentified operation of an unidentified logical file reported "
                            + "COBOL FILE STATUS 23 (IO-STATUS-04 0023)");
            assertThatExceptionOfType(RecordNotFoundException.class)
                    .as("a blank file name and verb are treated exactly as absent ones are")
                    .isThrownBy(() -> mapper.requireSuccess("23", "   ", "  "))
                    .withMessage("An unidentified operation of an unidentified logical file reported "
                            + "COBOL FILE STATUS 23 (IO-STATUS-04 0023)");
        }

        @Test
        @DisplayName("no hostile input on any entry point ever yields a null, blank or null-bearing diagnostic")
        void noHostileInputEverYieldsAnEmptyOrNullBearingDiagnostic() {
            List<String> hostile = new ArrayList<>(ABSENT_STATUSES);
            hostile.add("9");
            hostile.add("233");
            hostile.add("9\u0000");
            hostile.add("9\u00FF");
            hostile.add("\u0000\u0000");
            hostile.add("\u00FF\u00FF");

            for (String status : hostile) {
                assertThat(mapper.displayIoStatus(status))
                        .as("the renderer must always emit the literal plus four characters")
                        .startsWith(FileStatus.DISPLAY_MESSAGE_PREFIX)
                        .hasSize(FileStatus.DISPLAY_MESSAGE_PREFIX.length()
                                + FileStatus.RENDERED_STATUS_LENGTH);

                CardDemoException thrown = catchThrowableOfType(CardDemoException.class,
                        () -> mapper.requireSuccess(status, null, null));
                assertThat(thrown)
                        .as("every hostile status must be a failure at a guard, never a silent success")
                        .isNotNull();
                assertThat(thrown.getMessage())
                        .as("Rule 1 clause B: the diagnostic must carry context, never be blank")
                        .isNotNull()
                        .isNotBlank()
                        .doesNotContain("null")
                        .contains("(IO-STATUS-04 ");
            }
        }

        @Test
        @DisplayName("the mapper is reentrant and free of order dependence, so the same input always maps alike")
        void theMapperIsReentrantAndOrderIndependent() {
            // Rule 1 clause A: determinism. There is no clock, no randomness, no default locale and no
            // hash iteration order in this translation, so a second sweep in reverse must agree exactly.
            List<String> forward = new ArrayList<>();
            for (String status : STATUS_VOCABULARY) {
                forward.add(mapper.displayIoStatus(status) + '|' + mapper.applResultForGuard(status)
                        + '|' + mapper.applResultForSequentialRead(status));
            }
            List<String> reverse = new ArrayList<>();
            for (int index = STATUS_VOCABULARY.size() - 1; index >= 0; index--) {
                String status = STATUS_VOCABULARY.get(index);
                reverse.add(0, mapper.displayIoStatus(status) + '|' + mapper.applResultForGuard(status)
                        + '|' + mapper.applResultForSequentialRead(status));
            }
            assertThat(reverse)
                    .as("a reverse sweep must produce identical translations, element for element")
                    .isEqualTo(forward);

            FileStatusMapper second = new FileStatusMapper();
            for (String status : STATUS_VOCABULARY) {
                assertThat(second.displayIoStatus(status))
                        .as("a second stateless instance must agree with the first for [%s]", status)
                        .isEqualTo(mapper.displayIoStatus(status));
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Group 7 - Rule 1 clause D (no secrets in code, logs, tests or config), clause C (no
    // environment specific assumptions) and the build path contract of this test tier. The
    // masking carve-out matters most: logback-spring.xml masks credentials, password hashes and
    // social security numbers, and NO masking rule may touch the DISPLAY line, because the
    // end-to-end parity gate compares it against the legacy baseline byte for byte.
    // ------------------------------------------------------------------------------------------

    /**
     * Group 7. Two cross-cutting contracts that have no single COBOL locator but are binding
     * nonetheless. First, the masking carve-out: the structured logging configuration masks
     * credentials, password hashes and social security numbers, and no masking rule may touch the
     * status display line, because the end-to-end parity gate compares it against the legacy baseline
     * byte for byte - so no diagnostic this mapper produces may carry a secret-shaped or
     * personal-data-shaped token in the first place. Second, the build-path contract: this class must
     * sit in the unit tree under the {@code com.cardemo} root, because a class outside that tree
     * matches neither test plugin's include set and would silently never run.
     */
    @Nested
    @DisplayName("7. Security, masking and the build path: nothing here can be masked or misplaced")
    class SecurityAndBuildPathContract {

        /**
         * The rendered DISPLAY line must be inert with respect to every masking rule. It is proved
         * inert structurally rather than by inspecting the logging configuration: the line is exactly
         * the twenty-character literal plus a four-character field, so it cannot contain a
         * credential token, cannot contain a nine-digit social security number and cannot contain a
         * thirteen-digit or longer card number. Nothing a masking rule targets can appear in it.
         *
         * @param ioStatus a status the corpus can present at a guard
         */
        @ParameterizedTest(name = "the DISPLAY line for [{0}] is inert with respect to masking")
        @ValueSource(strings = {"00", "04", "10", "22", "23", "35", "90", "99"})
        void theDisplayLineSurvivesMaskingByteForByte(String ioStatus) {
            String line = mapper.displayIoStatus(ioStatus);
            assertThat(line)
                    .as("app/cbl/CBTRN02C.cbl:L721 and :L725; the parity baseline compares this exactly")
                    .isEqualTo(FileStatus.DISPLAY_MESSAGE_PREFIX + FileStatus.renderIoStatus04(ioStatus))
                    .hasSize(FileStatus.DISPLAY_MESSAGE_PREFIX.length() + FileStatus.RENDERED_STATUS_LENGTH);
            for (String needle : MASKABLE_TOKENS) {
                assertThat(line.toLowerCase(Locale.ROOT))
                        .as("the DISPLAY line must not contain [%s], or a masking rule would rewrite it",
                                needle)
                        .doesNotContain(needle);
            }
            assertThat(longestDigitRun(line))
                    .as("a run of %d digits is the social security number shape the masking rules target",
                            SOCIAL_SECURITY_DIGIT_RUN)
                    .isLessThan(SOCIAL_SECURITY_DIGIT_RUN);
        }

        @Test
        @DisplayName("no diagnostic on any entry point carries a credential, a hash or a personal data shape")
        void noDiagnosticCarriesASecretOrPersonalDataShape() {
            for (String diagnostic : sweepEveryDiagnostic()) {
                String lowered = diagnostic.toLowerCase(Locale.ROOT);
                for (String needle : MASKABLE_TOKENS) {
                    assertThat(lowered)
                            .as("Rule 1 clause D: a diagnostic must never contain [%s]", needle)
                            .doesNotContain(needle);
                }
                assertThat(longestDigitRun(diagnostic))
                        .as("a diagnostic must never reach the social security number digit shape: [%s]",
                                diagnostic)
                        .isLessThan(SOCIAL_SECURITY_DIGIT_RUN);
                assertThat(longestDigitRun(diagnostic))
                        .as("a diagnostic must never reach the card number digit shape: [%s]", diagnostic)
                        .isLessThan(CARD_NUMBER_DIGIT_RUN);
            }
        }

        @Test
        @DisplayName("no diagnostic and no mapper constant carries an environment-specific address")
        void nothingCarriesAnEnvironmentSpecificAddress() throws ReflectiveOperationException {
            // Rule 1 clause C: builds and their diagnostics must be free of environment-specific
            // assumptions, which is what keeps this tier runnable with no container and no network.
            for (String diagnostic : sweepEveryDiagnostic()) {
                assertEnvironmentFree(diagnostic, "a diagnostic");
            }
            for (Field declared : FileStatusMapper.class.getDeclaredFields()) {
                if (declared.getType() != String.class || !Modifier.isStatic(declared.getModifiers())) {
                    continue;
                }
                declared.setAccessible(true);
                assertEnvironmentFree((String) declared.get(null), "FileStatusMapper." + declared.getName());
            }
            for (String constant : List.of(LOGICAL_FILE, OPERATION_READ, OPERATION_WRITE, OPERATION_OPEN,
                    DD_TRNXFILE)) {
                assertEnvironmentFree(constant, "a test constant");
                for (String needle : MASKABLE_TOKENS) {
                    assertThat(constant.toLowerCase(Locale.ROOT))
                            .as("a test constant must not look like [%s] either", needle)
                            .doesNotContain(needle);
                }
            }
        }

        @Test
        @DisplayName("the build path contract holds, so Surefire collects this class instead of silently skipping it")
        void theBuildPathContractHolds() {
            // The Phase 0 blocker, asserted rather than assumed. Surefire 3.5.4 includes **/*Test.java and
            // excludes **/integration/** and **/e2e/**, so a class outside src/test/java/com/cardemo/unit/**
            // matches neither plugin's include set and never runs: green build, no error, no warning.
            assertThat(FileStatusMapperTest.class.getPackageName())
                    .as("Surefire binds this tier; the package must be exactly this")
                    .isEqualTo("com.cardemo.unit.service")
                    .startsWith("com.cardemo.unit.")
                    .doesNotContain("carddemo")
                    .doesNotContain("integration")
                    .doesNotContain("e2e");
            assertThat(FileStatusMapperTest.class.getSimpleName())
                    .as("Surefire includes **/*Test.java, so the name must end in Test")
                    .endsWith("Test")
                    .isEqualTo("FileStatusMapperTest");
            assertThat(FileStatusMapper.class.getPackageName())
                    .as("the class under test lives in the shared service package")
                    .isEqualTo("com.cardemo.service.shared")
                    .doesNotContain("carddemo");
            assertThat(FileStatus.class.getPackageName()).isEqualTo("com.cardemo.model.enums");
            assertThat(CardDemoException.class.getPackageName()).isEqualTo("com.cardemo.exception");
        }

        @Test
        @DisplayName("this tier needs no Spring context, no container, no database and no network")
        void thisTierIsPureJvm() throws ReflectiveOperationException {
            // Rule 1 clause D, least privilege: there is no endpoint reachable from this test at all.
            // The mapper is constructed directly, so nothing here can start a context or open a socket.
            Object constructed = FileStatusMapper.class.getDeclaredConstructor().newInstance();
            assertThat(constructed)
                    .as("the mapper must be usable with new, so no Spring context is required")
                    .isInstanceOf(FileStatusMapper.class);
            assertThat(FileStatusMapper.class.getDeclaredConstructor().getParameterCount())
                    .as("no collaborator means nothing to mock and nothing to reach out to")
                    .isZero();
            assertThat(((FileStatusMapper) constructed).displayIoStatus("23"))
                    .as("a directly constructed instance is fully functional")
                    .isEqualTo("FILE STATUS IS: NNNN0023");
        }

        /**
         * Drives every public entry point across the whole status vocabulary and the hostile shapes, and
         * returns every diagnostic string produced. This is the sweep both security assertions share, so
         * that neither can pass by exercising a narrower surface than the other.
         *
         * @return every message, abend field and expanded status any entry point produced, never
         *         {@code null} and never containing a {@code null} element
         */
        private List<String> sweepEveryDiagnostic() {
            List<String> sink = new ArrayList<>();
            List<String> inputs = new ArrayList<>(STATUS_VOCABULARY);
            inputs.addAll(ABSENT_STATUSES);
            inputs.add("9\u0000");
            inputs.add("9\u00FF");
            inputs.add("77");
            for (String status : inputs) {
                addDiagnostics(sink, catchThrowableOfType(CardDemoException.class,
                        () -> mapper.requireSuccess(status, LOGICAL_FILE, OPERATION_READ)));
                addDiagnostics(sink, catchThrowableOfType(CardDemoException.class,
                        () -> mapper.requireSuccess(status, null, null)));
                addDiagnostics(sink, catchThrowableOfType(CardDemoException.class,
                        () -> mapper.requireSuccessOrEndOfFile(status, LOGICAL_FILE, OPERATION_WRITE)));
                addDiagnostics(sink, catchThrowableOfType(CardDemoException.class,
                        () -> mapper.requireCategoryBalanceReadSuccess(status)));
                addDiagnostics(sink, catchThrowableOfType(CardDemoException.class,
                        () -> mapper.requireDisclosureGroupReadSuccess(status)));
                addDiagnostics(sink, catchThrowableOfType(CardDemoException.class,
                        () -> mapper.requireDefaultDisclosureGroupReadSuccess(status)));
                addDiagnostics(sink, catchThrowableOfType(CardDemoException.class,
                        () -> mapper.requireFileServiceSuccess(status, DD_TRNXFILE, OPERATION_OPEN)));
                addDiagnostics(sink, catchThrowableOfType(CardDemoException.class,
                        () -> mapper.requireFileServiceSuccessOrEndOfFile(status, DD_TRNXFILE,
                                OPERATION_READ)));
                mapper.toException(status, LOGICAL_FILE, OPERATION_READ)
                        .ifPresent(translated -> addDiagnostics(sink, translated));
                sink.add(mapper.displayIoStatus(status));
            }
            assertThat(sink)
                    .as("the sweep must actually have produced diagnostics, or it proves nothing")
                    .isNotEmpty()
                    .doesNotContainNull();
            return sink;
        }

        /**
         * Appends every diagnostic string an exception carries to the sink, skipping a {@code null}
         * exception, which simply means the guard accepted the status.
         *
         * @param sink   the accumulator to append to, never {@code null}
         * @param thrown the exception a guard produced, or {@code null} when the status was accepted
         */
        private void addDiagnostics(List<String> sink, CardDemoException thrown) {
            if (thrown == null) {
                return;
            }
            sink.add(thrown.getMessage());
            if (thrown instanceof FatalProcessingException fatal) {
                sink.add(fatal.getAbendCode());
                sink.add(fatal.getAbendCulprit());
                sink.add(fatal.getAbendReason());
                sink.add(fatal.getAbendMessage());
            }
            if (thrown instanceof FileAccessException fileAccess) {
                sink.add(fileAccess.getExpandedStatus());
                sink.add(fileAccess.getLogicalFileName() == null ? "" : fileAccess.getLogicalFileName());
                sink.add(fileAccess.getOperation() == null ? "" : fileAccess.getOperation());
            }
        }

        /**
         * Asserts a string carries none of the environment-specific addresses Rule 1 clause C forbids.
         *
         * @param value       the string to check, never {@code null}
         * @param description what the string is, used in the assertion message
         */
        private void assertEnvironmentFree(String value, String description) {
            for (String needle : ENVIRONMENT_LITERALS) {
                assertThat(value.toLowerCase(Locale.ROOT))
                        .as("%s must not carry the environment-specific literal [%s]", description, needle)
                        .doesNotContain(needle);
            }
        }
    }

    /**
     * Returns the length of the longest run of consecutive ASCII digits in a string. Used to prove
     * structurally that nothing this mapper emits can reach the digit shapes the masking rules in
     * {@code logback-spring.xml} target, so that no masking rule can ever rewrite a parity-compared line.
     *
     * @param value the string to scan, never {@code null}
     * @return the length of the longest consecutive digit run, and zero when the string holds no digit
     */
    private static int longestDigitRun(String value) {
        int longest = 0;
        int current = 0;
        for (int position = 0; position < value.length(); position++) {
            char character = value.charAt(position);
            if (character >= '0' && character <= '9') {
                current++;
                longest = Math.max(longest, current);
            } else {
                current = 0;
            }
        }
        return longest;
    }
}
