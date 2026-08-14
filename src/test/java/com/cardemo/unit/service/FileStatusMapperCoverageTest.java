/*
 * ******************************************************************
 * Program     : FileStatusMapperCoverageTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/service
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Pins the universal COBOL FILE STATUS guard idiom: the
 *               four APPL-RESULT codes, the four-character IO-STATUS-04
 *               rendering displayed by 9910-DISPLAY-IO-STATUS, the
 *               translation of every status into exactly one typed
 *               exception, and the three call sites where a
 *               record-not-found status is an accepted control path
 *               rather than an error - including the one site where the
 *               very same status is fatal.
 * Source      : app/cbl/CBTRN02C.cbl:L142-L144 (APPL-RESULT condition
 *               names), L481-L493 (2700-UPDATE-TCATBAL accepting
 *               '00' OR '23'), L707-L711 (9999-ABEND-PROGRAM),
 *               L714-L727 (9910-DISPLAY-IO-STATUS) @ 7756d89
 * Source      : app/cbl/CBACT04C.cbl:L415-L440 (1200-GET-INTEREST-RATE
 *               accepting '00' OR '23'), L443-L460
 *               (1200-A-GET-DEFAULT-INT-RATE accepting '00' only)
 *               @ 7756d89
 * Source      : app/cbl/CBSTM03A.CBL:L352-L361 (the sequential read
 *               EVALUATE accepting '00' and '10' only), L736-L742 (the
 *               open guard accepting '00' OR '04') @ 7756d89
 * Source      : app/cpy/CSMSG02Y.cpy (CABENDD.CPY abend work areas)
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
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.FileUnavailableException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.service.shared.FileStatusMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link FileStatusMapper}, the single place in the Java target where a COBOL
 * {@code FILE STATUS} value becomes a typed exception.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>The legacy corpus does not check file status in hundreds of individually reasoned ways. It checks it in
 * one way, hundreds of times, and the mapper exists because that repetition is a single idiom. The idiom, as
 * written at {@code app/cbl/CBTRN02C.cbl:L481-L493} and reproduced verbatim at dozens of other sites, is:
 *
 * <pre>
 *     IF  TCATBALF-STATUS = '00'  OR '23'
 *         MOVE 0 TO APPL-RESULT
 *     ELSE
 *         MOVE 12 TO APPL-RESULT
 *     END-IF
 *     IF  APPL-AOK
 *         CONTINUE
 *     ELSE
 *         DISPLAY 'ERROR READING TRANSACTION BALANCE FILE'
 *         MOVE TCATBALF-STATUS TO IO-STATUS
 *         PERFORM 9910-DISPLAY-IO-STATUS
 *         PERFORM 9999-ABEND-PROGRAM
 *     END-IF.
 * </pre>
 *
 * <p>Four things in that fragment are contracts rather than incidental detail, and this test asserts each of
 * them separately so that a regression names itself.
 *
 * <h3>The accepted status set differs per call site, and one difference is decisive</h3>
 *
 * <p>Most guards accept {@code '00'} alone. Three do not, and the three do not agree with one another:
 *
 * <ul>
 *   <li>{@code 2700-UPDATE-TCATBAL} at {@code app/cbl/CBTRN02C.cbl:L481} accepts {@code '00' OR '23'},
 *       because a missing transaction-category-balance row is the signal to create one. A blanket
 *       not-found-means-exception rule would abend the upsert's create path on first use.</li>
 *   <li>{@code 1200-GET-INTEREST-RATE} at {@code app/cbl/CBACT04C.cbl:L422} likewise accepts
 *       {@code '00' OR '23'}, because a missing disclosure group is the signal to retry under the literal
 *       {@code 'DEFAULT'} group identifier.</li>
 *   <li>{@code 1200-A-GET-DEFAULT-INT-RATE} at {@code app/cbl/CBACT04C.cbl:L446} accepts {@code '00'}
 *       <strong>only</strong>. The same {@code '23'} that was a control path one paragraph earlier is fatal
 *       here, because the default group is the fallback and a fallback has no fallback.</li>
 *   </ul>
 *
 * <p>That third bullet is the single most easily lost behaviour in this class, so
 * {@link AcceptedNotFoundControlPaths#theSameNotFoundStatusIsAcceptedTwiceAndFatalOnceOnTheThirdCallSite()}
 * exercises all three methods against the same input in one test rather than trusting three separate ones to
 * be read together.
 *
 * <h3>The guard and the sequential-read loop are different state machines</h3>
 *
 * <p>{@code applResultForGuard} models the two-way {@code MOVE 0 / MOVE 12} above. A keyed read, write,
 * rewrite or close has no end-of-file branch, so end of file is a failure there.
 * {@code applResultForSequentialRead} models the three-way form used by a read loop, where {@code '10'}
 * yields {@code APPL-EOF} and terminates the loop normally. Collapsing the two would either abend every
 * batch job at end of file or make a keyed read silently succeed on a status it never expected, so this test
 * asserts both over the same status vocabulary in one parameterised pass.
 *
 * <h3>The displayed status is a four-character rendering, not the raw status</h3>
 *
 * <p>{@code 9910-DISPLAY-IO-STATUS} at {@code app/cbl/CBTRN02C.cbl:L714-L727} does not display the two-byte
 * status. On the numeric path it displays {@code '0000'} with the status overlaid at positions three and
 * four; on the non-numeric or {@code '9'}-family path it copies the first byte through and expands the
 * second byte's <em>binary value</em> into three digits. Both branches end in
 * {@code DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04}, which means the literal {@code NNNN} placeholder is
 * itself part of the emitted line, immediately followed by the rendering. Gate 1 compares log output against
 * the legacy baseline, so a differently shaped line is a diff even when the underlying status is identical.
 *
 * <h3>Every translated exception carries its evidence</h3>
 *
 * <p>{@code app/cpy/CSMSG02Y.cpy}, whose internal title is {@code CABENDD.CPY}, declares the abend work
 * areas {@code ABEND-CODE PIC X(4)}, {@code ABEND-CULPRIT PIC X(8)}, {@code ABEND-REASON PIC X(50)} and
 * {@code ABEND-MSG PIC X(72)}. The mapper populates the culprit with the program name that owns the guard
 * and the reason with the program's own {@code DISPLAY} literal, so a fatal outcome identifies the COBOL
 * paragraph it came from. This test asserts those payloads rather than merely asserting the exception type.
 *
 * <h2>2. How to run it</h2>
 *
 * <p>Whole class, offline, from the repository root. The only prerequisite is JDK 25 on {@code PATH} with
 * {@code JAVA_HOME} set; Maven comes from the pinned wrapper, which is why the wrapper and never a host
 * {@code mvn} is invoked:
 *
 * <pre>
 *     ./mvnw -B -ntp -o test -Dtest=FileStatusMapperCoverageTest -DfailIfNoSpecifiedTests=false -Djacoco.skip=true
 * </pre>
 *
 * <p>As part of the gated build, which additionally enforces the coverage floor. Note what the two flags
 * mean: {@code -o} makes Maven offline, which causes it to skip {@code dependency-check:check} because that
 * goal declares {@code requiresOnline}, and {@code -Ddependency-check.skip=true} skips it explicitly.
 * Either way <strong>a skipped scan is never evidence that the scan passes</strong>, so the vulnerability
 * gate must be run separately and online:
 *
 * <pre>
 *     ./mvnw -B -ntp -o clean verify -Ddependency-check.skip=true
 * </pre>
 *
 * <p>No container, no database, no LocalStack endpoint and no Spring context are required. The subject is a
 * stateless component with no collaborator, so every test constructs it directly.
 *
 * <h2>3. Configuration and defaults</h2>
 *
 * <p>None. {@link FileStatusMapper} reads no property, no profile and no environment variable, and
 * {@link Statelessness#theMapperDeclaresNoInstanceStateAtAll()} asserts that it holds no instance field, which
 * is what makes the single bean Spring creates safe to share across batch threads and request threads alike.
 *
 * <p>The status vocabulary the tests draw on is fixed by {@link FileStatus} and is not configurable:
 * {@code '00'} success, {@code '04'} accepted secondary, {@code '10'} end of file, {@code '22'} duplicate
 * key, {@code '23'} record not found, {@code '35'} file unavailable and the {@code '9x'} family for physical
 * or logical I/O errors.
 *
 * <h2>4. Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>A guard test now reports {@code APPL_EOF}</strong> - {@code applResultForGuard} has grown an
 *       end-of-file branch the COBOL guard does not have. Restore the two-way form; the three-way form
 *       belongs only to {@code applResultForSequentialRead}.</li>
 *   <li><strong>{@code requireDefaultDisclosureGroupReadSuccess} stops throwing on {@code '23'}</strong> -
 *       someone has generalised the accepted-not-found rule across all three call sites. Compare
 *       {@code app/cbl/CBACT04C.cbl:L422} with {@code :L446}: the two paragraphs deliberately differ, and
 *       accepting {@code '23'} at the second one lets the interest job run with no rate at all.</li>
 *   <li><strong>A displayed line loses the {@code NNNN} literal</strong> - the prefix was mistaken for a
 *       placeholder to substitute. The COBOL {@code DISPLAY} emits the literal and the value, so
 *       {@link FileStatus#DISPLAY_MESSAGE_PREFIX} must remain a literal prefix.</li>
 *   <li><strong>{@code renderIoStatus04} assertions drift by a large margin</strong> - the second byte is
 *       expanded from its <em>character code</em>, not from its digit value. {@code '92'} renders as
 *       {@code 9050} because {@code '2'} is decimal 50; {@code 9002} would mean the binary move at
 *       {@code app/cbl/CBTRN02C.cbl:L719-L720} was replaced by a numeric conversion.</li>
 *   <li><strong>A translated exception loses its culprit or reason</strong> - the mapper is being called with
 *       the generic {@code requireSuccess} where a specialised method is required. The specialised methods
 *       exist precisely so that the abend payload names the originating COBOL program.</li>
 *   </ul>
 */
@DisplayName("FileStatusMapper: the universal COBOL FILE STATUS guard idiom translated once, centrally")
class FileStatusMapperCoverageTest {

    /** The subject under test. Stateless, so one instance per test method costs nothing. */
    private final FileStatusMapper mapper = new FileStatusMapper();

    /**
     * Every status value the mapper is expected to encounter, success and failure alike.
     *
     * <p>Used by the property-style tests that must hold for the whole vocabulary rather than for a chosen
     * example. {@code null} is deliberately excluded here and covered by dedicated tests, because a
     * {@code @ValueSource} cannot carry it.
     */
    private static final String[] STATUS_VOCABULARY = {
        "00", "04", "10", "22", "23", "35", "90", "92", "9A", "ZZ", "", "  ",
    };

    @Nested
    @DisplayName("The APPL-RESULT codes and abend work-area widths are the COBOL literals, unaltered")
    class GuardConstants {

        @Test
        @DisplayName("APPL_RESULT_INITIAL is 8, the value the source moves in before every I/O verb so that "
                + "an unset result can never be mistaken for success")
        void applResultInitialIsEight() {
            assertThat(FileStatusMapper.APPL_RESULT_INITIAL)
                    .as("app/cbl/CBTRN02C.cbl performs MOVE 8 TO APPL-RESULT before each verb; 8 matches "
                            + "neither APPL-AOK (0) nor APPL-EOF (16), so a verb that never ran fails closed")
                    .isEqualTo(8);
        }

        @Test
        @DisplayName("APPL_AOK is 0, APPL_EOF is 16 and APPL_FAILURE is 12, exactly as the 88-level "
                + "condition names at app/cbl/CBTRN02C.cbl:L142-L144 declare them")
        void theThreeOutcomeCodesMatchTheConditionNames() {
            assertThat(FileStatusMapper.APPL_AOK)
                    .as("88 APPL-AOK VALUE 0")
                    .isZero();
            assertThat(FileStatusMapper.APPL_EOF)
                    .as("88 APPL-EOF VALUE 16")
                    .isEqualTo(16);
            assertThat(FileStatusMapper.APPL_FAILURE)
                    .as("the ELSE arm of every guard performs MOVE 12 TO APPL-RESULT")
                    .isEqualTo(12);
        }

        @Test
        @DisplayName("The three outcome codes are mutually distinct, which is what lets one integer carry a "
                + "three-way result without an auxiliary flag")
        void theThreeOutcomeCodesAreDistinct() {
            assertThat(new int[] {
                FileStatusMapper.APPL_AOK, FileStatusMapper.APPL_EOF, FileStatusMapper.APPL_FAILURE,
                FileStatusMapper.APPL_RESULT_INITIAL,
            })
                    .as("0, 16, 12 and the initial 8 must all differ or the guard cannot distinguish "
                            + "success from end of file from failure from never-ran")
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("The four abend work-area widths reproduce app/cpy/CSMSG02Y.cpy field for field and sum "
                + "to the copybook's 134 bytes")
        void theAbendWorkAreaWidthsReproduceTheCopybook() {
            assertThat(FileStatusMapper.ABEND_CODE_WIDTH)
                    .as("app/cpy/CSMSG02Y.cpy declares ABEND-CODE PIC X(4)")
                    .isEqualTo(4);
            assertThat(FileStatusMapper.ABEND_CULPRIT_WIDTH)
                    .as("app/cpy/CSMSG02Y.cpy declares ABEND-CULPRIT PIC X(8), wide enough for an eight "
                            + "character COBOL program name such as CBTRN02C")
                    .isEqualTo(8);
            assertThat(FileStatusMapper.ABEND_REASON_WIDTH)
                    .as("app/cpy/CSMSG02Y.cpy declares ABEND-REASON PIC X(50)")
                    .isEqualTo(50);
            assertThat(FileStatusMapper.ABEND_MESSAGE_WIDTH)
                    .as("app/cpy/CSMSG02Y.cpy declares ABEND-MSG PIC X(72)")
                    .isEqualTo(72);
            assertThat(FileStatusMapper.ABEND_CODE_WIDTH + FileStatusMapper.ABEND_CULPRIT_WIDTH
                    + FileStatusMapper.ABEND_REASON_WIDTH + FileStatusMapper.ABEND_MESSAGE_WIDTH)
                    .as("4 + 8 + 50 + 72 = 134, the populated width of the abend work area; a mismatch means "
                            + "a field width was widened or narrowed away from its PIC clause")
                    .isEqualTo(134);
        }

        @Test
        @DisplayName("The unset abend code and culprit are all-spaces strings of exactly the declared width, "
                + "reproducing an initialised but unpopulated PIC X field rather than a Java null")
        void theUnsetAbendFieldsAreSpaceFilledToTheirDeclaredWidth() {
            assertThat(FileStatusMapper.ABEND_CODE_UNSET)
                    .as("a COBOL PIC X(4) that was never moved into holds four spaces, not a null; the "
                            + "mapper reproduces that so a rendered abend block keeps its column geometry")
                    .isEqualTo("    ")
                    .hasSize(FileStatusMapper.ABEND_CODE_WIDTH)
                    .isBlank();
            assertThat(FileStatusMapper.ABEND_CULPRIT_UNSET)
                    .as("the same reasoning for PIC X(8): eight spaces, blank but not empty and not null")
                    .isEqualTo("        ")
                    .hasSize(FileStatusMapper.ABEND_CULPRIT_WIDTH)
                    .isBlank();
        }

        @Test
        @DisplayName("The three legacy failure texts are the DISPLAY literals of the paragraphs they belong "
                + "to, character for character, because Gate 1 compares emitted text")
        void theLegacyFailureTextsAreTheCobolDisplayLiterals() {
            assertThat(FileStatusMapper.TCATBAL_READ_FAILURE_TEXT)
                    .as("app/cbl/CBTRN02C.cbl:L489 reads "
                            + "DISPLAY 'ERROR READING TRANSACTION BALANCE FILE'")
                    .isEqualTo("ERROR READING TRANSACTION BALANCE FILE");
            assertThat(FileStatusMapper.DISCGRP_READ_FAILURE_TEXT)
                    .as("app/cbl/CBACT04C.cbl:L431 reads DISPLAY 'ERROR READING DISCLOSURE GROUP FILE'")
                    .isEqualTo("ERROR READING DISCLOSURE GROUP FILE");
            assertThat(FileStatusMapper.DEFAULT_DISCGRP_READ_FAILURE_TEXT)
                    .as("app/cbl/CBACT04C.cbl:L455 reads DISPLAY 'ERROR READING DEFAULT DISCLOSURE GROUP'; "
                            + "note it says GROUP and not GROUP FILE, unlike the paragraph above it")
                    .isEqualTo("ERROR READING DEFAULT DISCLOSURE GROUP");
        }

        @Test
        @DisplayName("The two disclosure-group failure texts differ, so a failed default retry can never be "
                + "confused with a failed first read in the log")
        void theTwoDisclosureGroupFailureTextsAreDistinct() {
            assertThat(FileStatusMapper.DEFAULT_DISCGRP_READ_FAILURE_TEXT)
                    .as("app/cbl/CBACT04C.cbl deliberately uses two different literals at :L431 and :L455 so "
                            + "that operations can tell which of the two reads failed")
                    .isNotEqualTo(FileStatusMapper.DISCGRP_READ_FAILURE_TEXT);
        }

        @Test
        @DisplayName("FILE_SERVICE_RETURN_CODE_TEXT keeps its trailing space, because the COBOL DISPLAY "
                + "concatenates the literal directly with the return code")
        void theFileServiceReturnCodeTextKeepsItsTrailingSpace() {
            assertThat(FileStatusMapper.FILE_SERVICE_RETURN_CODE_TEXT)
                    .as("app/cbl/CBSTM03A.CBL:L741 reads DISPLAY 'RETURN CODE: ' WS-M03B-RC; stripping the "
                            + "trailing space would emit RETURN CODE:00 instead of RETURN CODE: 00")
                    .isEqualTo("RETURN CODE: ")
                    .endsWith(" ");
        }
    }

    @Nested
    @DisplayName("displayIoStatus reproduces 9910-DISPLAY-IO-STATUS, literal placeholder included")
    class IoStatusDisplay {

        @ParameterizedTest(name = "status [{0}] displays as the four characters {1}")
        @CsvSource({
            "00,0000",
            "10,0010",
            "22,0022",
            "23,0023",
            "35,0035",
            "90,9048",
            "92,9050",
            "9A,9065",
            "ZZ,Z090",
        })
        @DisplayName("Both branches of the paragraph are exercised: a numeric status is right-aligned into "
                + "'0000' while a non-numeric or 9-family status expands its second byte's character code")
        void bothRenderingBranchesMatchTheParagraph(String ioStatus, String expectedRendering) {
            assertThat(mapper.displayIoStatus(ioStatus))
                    .as("app/cbl/CBTRN02C.cbl:L714-L727 ends both arms in "
                            + "DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04, so the emitted line is the "
                            + "literal prefix immediately followed by the four character rendering")
                    .isEqualTo(FileStatus.DISPLAY_MESSAGE_PREFIX + expectedRendering);
        }

        @Test
        @DisplayName("The 9-family expansion uses the character code of the second byte and not its digit "
                + "value, which is why '92' renders as 9050 rather than 9002")
        void theNineFamilyExpansionUsesTheCharacterCode() {
            assertThat(mapper.displayIoStatus("92"))
                    .as("app/cbl/CBTRN02C.cbl:L719-L720 moves IO-STAT2 into TWO-BYTES-RIGHT, a binary field, "
                            + "so the character '2' contributes its EBCDIC-independent code point 50; a "
                            + "numeric conversion here would emit 9002 and break the baseline comparison")
                    .isEqualTo(FileStatus.DISPLAY_MESSAGE_PREFIX + "9050");
            assertThat(mapper.displayIoStatus("90"))
                    .as("'0' is code point 48, so the same arithmetic gives 9048")
                    .isEqualTo(FileStatus.DISPLAY_MESSAGE_PREFIX + "9048");
        }

        @Test
        @DisplayName("An absent, empty or blank status still renders four characters, because the COBOL field "
                + "is fixed width and a short move leaves spaces behind rather than nothing")
        void absentAndBlankStatusesStillRenderFourCharacters() {
            String expected = FileStatus.DISPLAY_MESSAGE_PREFIX + " 032";
            assertThat(mapper.displayIoStatus(null))
                    .as("a null status behaves as an unmoved PIC XX: first byte space, second byte space "
                            + "whose code point 32 expands to 032")
                    .isEqualTo(expected);
            assertThat(mapper.displayIoStatus(""))
                    .as("an empty status is indistinguishable from an unmoved field in COBOL and must render "
                            + "identically to the null case")
                    .isEqualTo(expected);
            assertThat(mapper.displayIoStatus("  "))
                    .as("two literal spaces are exactly what an unmoved PIC XX contains")
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("A one-character status is padded rather than rejected, so '4' renders as 4032 with the "
                + "missing second byte contributing a space")
        void aOneCharacterStatusIsPaddedNotRejected() {
            assertThat(mapper.displayIoStatus("4"))
                    .as("the source moves into a fixed two-byte field, so a single character occupies byte "
                            + "one and byte two stays a space; 4 followed by the expansion of space is 4032")
                    .isEqualTo(FileStatus.DISPLAY_MESSAGE_PREFIX + "4032");
        }

        @ParameterizedTest(name = "status [{0}] yields a line of prefix length plus exactly four characters")
        @ValueSource(strings = {"00", "04", "10", "22", "23", "35", "90", "92", "9A", "ZZ", "", "  "})
        @DisplayName("Every status in the vocabulary produces a line of the same length, which is the "
                + "property that makes side-by-side log comparison possible at all")
        void everyStatusProducesAFixedLengthLine(String ioStatus) {
            assertThat(mapper.displayIoStatus(ioStatus))
                    .as("IO-STATUS-04 is PIC X(4), so the rendered suffix is always four characters and the "
                            + "emitted line is always the prefix plus four")
                    .hasSize(FileStatus.DISPLAY_MESSAGE_PREFIX.length() + FileStatus.RENDERED_STATUS_LENGTH)
                    .startsWith(FileStatus.DISPLAY_MESSAGE_PREFIX);
        }

        @Test
        @DisplayName("The prefix retains the literal NNNN placeholder, because the COBOL DISPLAY emits the "
                + "literal and the value rather than substituting one into the other")
        void thePrefixRetainsTheLiteralPlaceholder() {
            assertThat(FileStatus.DISPLAY_MESSAGE_PREFIX)
                    .as("app/cbl/CBTRN02C.cbl:L721 is DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04; the NNNN "
                            + "is part of the literal, so the legacy line reads ...NNNN0000 and not ...0000")
                    .isEqualTo("FILE STATUS IS: NNNN");
            assertThat(mapper.displayIoStatus("00"))
                    .as("the composed line therefore contains NNNN followed by the rendering")
                    .isEqualTo("FILE STATUS IS: NNNN0000");
        }

        @ParameterizedTest(name = "displayIoStatus([{0}]) equals the prefix plus renderIoStatus04([{0}])")
        @ValueSource(strings = {"00", "04", "10", "22", "23", "35", "90", "92", "9A", "ZZ", "", "  "})
        @DisplayName("The method composes rather than reimplements: it is exactly the prefix concatenated "
                + "with FileStatus.renderIoStatus04, so the rendering lives in one place only")
        void theMethodComposesTheSharedRenderer(String ioStatus) {
            assertThat(mapper.displayIoStatus(ioStatus))
                    .as("a second private copy of the rendering rules would drift from the enum's; the "
                            + "mapper must delegate so that the four character expansion has one owner")
                    .isEqualTo(FileStatus.DISPLAY_MESSAGE_PREFIX + FileStatus.renderIoStatus04(ioStatus));
        }
    }

    @Nested
    @DisplayName("The guard and the sequential-read loop are deliberately different state machines")
    class GuardVersusSequentialRead {

        @Test
        @DisplayName("applResultForGuard accepts only '00' and returns APPL_FAILURE for everything else, "
                + "including end of file, because a keyed verb has no end-of-file branch")
        void theGuardAcceptsOnlySuccess() {
            assertThat(mapper.applResultForGuard("00"))
                    .as("the IF arm of the idiom performs MOVE 0 TO APPL-RESULT for status '00' alone")
                    .isEqualTo(FileStatusMapper.APPL_AOK);
            assertThat(mapper.applResultForGuard("10"))
                    .as("a keyed READ, WRITE, REWRITE or CLOSE guard has no '10' arm, so end of file falls "
                            + "into the ELSE and becomes APPL-FAILURE; this is not an oversight")
                    .isEqualTo(FileStatusMapper.APPL_FAILURE);
        }

        @ParameterizedTest(name = "the guard maps [{0}] to APPL_FAILURE")
        @ValueSource(strings = {"04", "10", "22", "23", "35", "90", "92", "9A", "ZZ", "", "  "})
        @DisplayName("Every status other than '00' is a guard failure, which is what makes the idiom a "
                + "two-way test that a single integer comparison can express")
        void everyNonSuccessStatusIsAGuardFailure(String ioStatus) {
            assertThat(mapper.applResultForGuard(ioStatus))
                    .as("MOVE 12 TO APPL-RESULT is the unconditional ELSE of the guard")
                    .isEqualTo(FileStatusMapper.APPL_FAILURE);
        }

        @Test
        @DisplayName("A null status is a guard failure rather than an exception, so the caller reaches its "
                + "own abend path exactly as the COBOL ELSE arm does")
        void aNullStatusIsAGuardFailure() {
            assertThat(mapper.applResultForGuard(null))
                    .as("the mapper must not throw while classifying; throwing here would bypass the "
                            + "caller's DISPLAY and 9999-ABEND-PROGRAM sequence")
                    .isEqualTo(FileStatusMapper.APPL_FAILURE);
        }

        @ParameterizedTest(name = "the guard never reports APPL_EOF for [{0}]")
        @ValueSource(strings = {"00", "04", "10", "22", "23", "35", "90", "92", "9A", "ZZ", "", "  "})
        @DisplayName("APPL_EOF is unreachable through applResultForGuard for any input at all, which is the "
                + "structural difference between the two methods stated as a property")
        void theGuardNeverReportsEndOfFile(String ioStatus) {
            assertThat(mapper.applResultForGuard(ioStatus))
                    .as("if the guard could ever return APPL-EOF, a keyed read would inherit a loop "
                            + "termination branch that its COBOL original does not have")
                    .isNotEqualTo(FileStatusMapper.APPL_EOF);
        }

        @ParameterizedTest(name = "sequential read maps [{0}] to {1}")
        @CsvSource({
            "00,0",
            "10,16",
            "04,12",
            "22,12",
            "23,12",
            "35,12",
            "90,12",
            "9A,12",
            "ZZ,12",
        })
        @DisplayName("applResultForSequentialRead is three-way: success, end of file and failure, matching "
                + "the read-loop form the batch programs use to walk a file to completion")
        void theSequentialReadFormIsThreeWay(String ioStatus, int expected) {
            assertThat(mapper.applResultForSequentialRead(ioStatus))
                    .as("the read loop needs '10' to be a normal termination; treating it as a failure "
                            + "would abend every sequential batch step at the last record")
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("The two methods agree on '00' and on every failure status but disagree on '10', and "
                + "that single disagreement is the whole reason both exist")
        void theTwoMethodsDisagreeOnlyOnEndOfFile() {
            assertThat(mapper.applResultForGuard("00"))
                    .as("both forms accept success identically")
                    .isEqualTo(mapper.applResultForSequentialRead("00"));
            assertThat(mapper.applResultForGuard("35"))
                    .as("both forms reject a genuine failure identically")
                    .isEqualTo(mapper.applResultForSequentialRead("35"));
            assertThat(mapper.applResultForGuard("10"))
                    .as("only end of file separates them: APPL-FAILURE for a keyed guard, APPL-EOF for a "
                            + "sequential read loop")
                    .isEqualTo(FileStatusMapper.APPL_FAILURE)
                    .isNotEqualTo(mapper.applResultForSequentialRead("10"));
            assertThat(mapper.applResultForSequentialRead("10"))
                    .as("and the sequential form reports the loop-terminating code")
                    .isEqualTo(FileStatusMapper.APPL_EOF);
        }

        @Test
        @DisplayName("The accepted secondary status '04' is a guard failure in both forms, because only the "
                + "statement file service tolerates it and neither of these methods serves that call site")
        void theAcceptedSecondaryStatusIsNotToleratedByEitherForm() {
            assertThat(mapper.applResultForGuard("04"))
                    .as("app/cbl/CBTRN02C.cbl never tests for '04'; only app/cbl/CBSTM03A.CBL:L736 does, and "
                            + "it does so through the file service methods rather than through this guard")
                    .isEqualTo(FileStatusMapper.APPL_FAILURE);
            assertThat(mapper.applResultForSequentialRead("04"))
                    .as("app/cbl/CBSTM03A.CBL:L352-L361 shows the read loop accepting '00' and '10' only, so "
                            + "'04' is a failure on a read as well")
                    .isEqualTo(FileStatusMapper.APPL_FAILURE);
        }
    }

    @Nested
    @DisplayName("toException translates a status into exactly one typed exception, or into nothing")
    class ToExceptionTranslation {

        @Test
        @DisplayName("Success and end of file translate to no exception at all, so a caller can use the "
                + "Optional form to drive a read loop without catching anything")
        void successAndEndOfFileTranslateToNothing() {
            assertThat(mapper.toException("00", "ACCTDAT", "READ"))
                    .as("status '00' is the success arm of every guard and cannot be an exception")
                    .isEmpty();
            assertThat(mapper.toException("10", "ACCTDAT", "READ"))
                    .as("status '10' terminates a read loop normally; the Optional form uses "
                            + "applResultForSequentialRead, so end of file is absence rather than failure")
                    .isEmpty();
        }

        @Test
        @DisplayName("The four-argument overload behaves identically for the two non-failure statuses, so "
                + "supplying a cause cannot manufacture an exception where there is no failure")
        void theCauseCarryingOverloadStillReturnsNothingForNonFailures() {
            Throwable cause = new IllegalStateException("underlying JDBC failure");
            assertThat(mapper.toException("00", "ACCTDAT", "READ", cause))
                    .as("a cause is context for a failure, not a trigger for one")
                    .isEmpty();
            assertThat(mapper.toException("10", "ACCTDAT", "READ", cause))
                    .as("the same holds at end of file")
                    .isEmpty();
        }

        @Test
        @DisplayName("Status '22' becomes a DuplicateRecordException carrying the logical file name, "
                + "reproducing DFHRESP(DUPREC) and the VSAM duplicate-key condition")
        void duplicateKeyBecomesADuplicateRecordException() {
            DuplicateRecordException failure = (DuplicateRecordException) mapper
                    .toException("22", "TRANSACT", "WRITE").orElseThrow();
            assertThat(failure.getLogicalFile())
                    .as("the file name is carried so that a duplicate on TRANSACT is distinguishable from "
                            + "one on USRSEC without parsing the message")
                    .isEqualTo("TRANSACT");
            assertThat(failure.getCollidingKey())
                    .as("the guard idiom knows the status and the file but not the key, so the key stays "
                            + "null rather than being invented from the message")
                    .isNull();
            assertThat(failure).as("and the message names the operation and the rendered status")
                    .hasMessage("WRITE of TRANSACT reported COBOL FILE STATUS 22 (IO-STATUS-04 0022)");
        }

        @Test
        @DisplayName("Status '23' becomes a RecordNotFoundException whose recordType is the logical file, "
                + "reproducing DFHRESP(NOTFND) and the INVALID KEY condition")
        void recordNotFoundBecomesARecordNotFoundException() {
            RecordNotFoundException failure = (RecordNotFoundException) mapper
                    .toException("23", "ACCTDAT", "READ").orElseThrow();
            assertThat(failure.recordType())
                    .as("the file name identifies what was not found")
                    .contains("ACCTDAT");
            assertThat(failure.recordKey())
                    .as("the guard has no key to report, and RecordNotFoundException converts a null key to "
                            + "an empty Optional rather than to a blank string")
                    .isEmpty();
        }

        @Test
        @DisplayName("Status '35' becomes a FileUnavailableException whose resourceName is the logical file, "
                + "reproducing DFHRESP(NOTOPEN) and the file-not-available condition")
        void fileUnavailableBecomesAFileUnavailableException() {
            FileUnavailableException failure = (FileUnavailableException) mapper
                    .toException("35", "CUSTDAT", "OPEN").orElseThrow();
            assertThat(failure.resourceName())
                    .as("'35' is reported when the dataset itself is unavailable, so the resource name is "
                            + "the only useful payload the guard can supply")
                    .contains("CUSTDAT");
        }

        @ParameterizedTest(name = "the 9-family status [{0}] becomes a FileAccessException rendering as {1}")
        @CsvSource({
            "90,9048",
            "92,9050",
            "9A,9065",
        })
        @DisplayName("Every '9x' status becomes a FileAccessException carrying the expanded four-character "
                + "status, because the family is open-ended and the second byte is diagnostic")
        void theNineFamilyBecomesAFileAccessException(String ioStatus, String expandedStatus) {
            FileAccessException failure = (FileAccessException) mapper
                    .toException(ioStatus, "TRANSACT", "REWRITE").orElseThrow();
            assertThat(failure.getExpandedStatus())
                    .as("the four character expansion is the only form in which the second byte's binary "
                            + "value is legible, which is why the exception carries it rather than the raw "
                            + "two byte status")
                    .isEqualTo(expandedStatus);
            assertThat(failure.getLogicalFileName())
                    .as("the file name is stored exactly as supplied, with no normalisation")
                    .isEqualTo("TRANSACT");
            assertThat(failure.getOperation())
                    .as("the operation is stored exactly as supplied, so REWRITE is not folded into WRITE")
                    .isEqualTo("REWRITE");
        }

        @Test
        @DisplayName("An unclassifiable status becomes a FatalProcessingException, reproducing the WHEN "
                + "OTHER arm that reaches 9999-ABEND-PROGRAM rather than a business error path")
        void anUnclassifiableStatusBecomesFatal() {
            FatalProcessingException failure = (FatalProcessingException) mapper
                    .toException("ZZ", "ACCTDAT", "READ").orElseThrow();
            assertThat(failure.getAbendReason())
                    .as("a status the mapper cannot classify is a programming or environment fault, so the "
                            + "reason says so instead of guessing a business meaning")
                    .isEqualTo("UNRECOGNISED COBOL FILE STATUS AT I/O GUARD");
            assertThat(failure.getAbendCode())
                    .as("the abend code stays unset at this layer: app/cbl/CBTRN02C.cbl:L707-L711 moves 999 "
                            + "into ABCODE inside 9999-ABEND-PROGRAM, not into ABEND-CODE at the guard")
                    .isEqualTo(FileStatusMapper.ABEND_CODE_UNSET);
            assertThat(failure.getAbendCulprit())
                    .as("the generic guard cannot name a culprit program, so the culprit is the unset "
                            + "eight-space field; only the specialised methods know which program failed")
                    .isEqualTo(FileStatusMapper.ABEND_CULPRIT_UNSET);
            assertThat(failure.getAbendMessage())
                    .as("the message still carries the operation, the file and the rendered status")
                    .isEqualTo("READ of ACCTDAT reported COBOL FILE STATUS ZZ (IO-STATUS-04 Z090)");
        }

        @Test
        @DisplayName("The accepted secondary status '04' is fatal at the generic guard, because only the "
                + "statement file service is entitled to accept it")
        void theAcceptedSecondaryStatusIsFatalAtTheGenericGuard() {
            assertThat(mapper.toException("04", "ACCTDAT", "READ"))
                    .as("'04' reaching a general guard means a caller used the wrong method; it must not be "
                            + "silently accepted, or a CBSTM03B specific tolerance would leak everywhere")
                    .get()
                    .isInstanceOf(FatalProcessingException.class);
        }

        @Test
        @DisplayName("An absent, empty or blank status is fatal and reports the (absent) placeholder rather "
                + "than an empty gap in the message")
        void anAbsentStatusIsFatalAndSaysSo() {
            FatalProcessingException failure = (FatalProcessingException) mapper
                    .toException(null, "ACCTDAT", "READ").orElseThrow();
            assertThat(failure)
                    .as("a caller that lost the status has a defect; the placeholder makes the gap visible "
                            + "instead of emitting 'reported COBOL FILE STATUS  ('")
                    .hasMessage("READ of ACCTDAT reported COBOL FILE STATUS (absent) (IO-STATUS-04  032)");
            assertThat(mapper.toException("", "ACCTDAT", "READ")).as("an empty status behaves identically")
                    .get().isInstanceOf(FatalProcessingException.class);
            assertThat(mapper.toException("   ", "ACCTDAT", "READ")).as("as does an all-blank status")
                    .get().isInstanceOf(FatalProcessingException.class);
        }

        @Test
        @DisplayName("An absent file name or operation is replaced by a descriptive placeholder, so the "
                + "message stays a readable sentence instead of collapsing into punctuation")
        void absentFileNamesAndOperationsUsePlaceholders() {
            assertThat(mapper.toException("23", null, null).orElseThrow())
                    .as("both placeholders read as English in position: the operation placeholder is "
                            + "capitalised because it opens the sentence and the file one is not")
                    .hasMessage("An unidentified operation of an unidentified logical file reported COBOL "
                            + "FILE STATUS 23 (IO-STATUS-04 0023)");
            assertThat(mapper.toException("23", "   ", "  ").orElseThrow())
                    .as("blank is treated as absent, because a COBOL field that was never moved into is "
                            + "spaces rather than null")
                    .hasMessage("An unidentified operation of an unidentified logical file reported COBOL "
                            + "FILE STATUS 23 (IO-STATUS-04 0023)");
        }

        @Test
        @DisplayName("The supplied cause is chained onto every translated exception, so the root cause is "
                + "preserved and never swallowed")
        void theSuppliedCauseIsChainedOntoEveryTranslation() {
            Throwable cause = new IllegalStateException("connection reset");
            assertThat(mapper.toException("22", "TRANSACT", "WRITE", cause).orElseThrow())
                    .as("a duplicate key raised by the driver keeps its driver exception")
                    .hasCause(cause);
            assertThat(mapper.toException("23", "ACCTDAT", "READ", cause).orElseThrow())
                    .as("so does a not-found translation")
                    .hasCause(cause);
            assertThat(mapper.toException("35", "CUSTDAT", "OPEN", cause).orElseThrow())
                    .as("so does an unavailable-file translation")
                    .hasCause(cause);
            assertThat(mapper.toException("92", "TRANSACT", "READ", cause).orElseThrow())
                    .as("so does a physical I/O error translation")
                    .hasCause(cause);
            assertThat(mapper.toException("ZZ", "ACCTDAT", "READ", cause).orElseThrow())
                    .as("and so does the fatal fall-through, which is the path most likely to be reached "
                            + "with a driver level cause in hand")
                    .hasCause(cause);
        }

        @Test
        @DisplayName("The three-argument overload leaves the cause unset rather than fabricating one, so an "
                + "absent cause is visible as absent")
        void theThreeArgumentOverloadLeavesTheCauseUnset() {
            assertThat(mapper.toException("23", "ACCTDAT", "READ").orElseThrow())
                    .as("a guard that observed only a status has no cause to report; inventing a wrapper "
                            + "cause would add a stack frame that never happened")
                    .hasNoCause();
        }

        @ParameterizedTest(name = "status [{0}] produces a message quoting both the raw status and its "
                + "rendering")
        @ValueSource(strings = {"22", "23", "35", "90", "9A", "ZZ"})
        @DisplayName("Every failure message quotes the raw status and the IO-STATUS-04 rendering together, "
                + "because the raw value is what the caller saw and the rendering is what the log shows")
        void everyFailureMessageQuotesBothFormsOfTheStatus(String ioStatus) {
            assertThat(mapper.toException(ioStatus, "ACCTDAT", "READ").orElseThrow().getMessage())
                    .as("correlating an exception with a SYSOUT line requires both forms, so the message "
                            + "carries the raw status and the rendering side by side")
                    .contains("COBOL FILE STATUS " + ioStatus)
                    .contains("(IO-STATUS-04 " + FileStatus.renderIoStatus04(ioStatus) + ")");
        }
    }

    @Nested
    @DisplayName("requireSuccess applies guard semantics, which makes it stricter than toException")
    class RequireSuccessGuard {

        @Test
        @DisplayName("Status '00' returns quietly, which is the CONTINUE arm of the idiom")
        void successReturnsQuietly() {
            assertThatCode(() -> mapper.requireSuccess("00", "ACCTDAT", "READ"))
                    .as("IF APPL-AOK CONTINUE: success does nothing at all, and in particular does not log")
                    .doesNotThrowAnyException();
            assertThatCode(() -> mapper.requireSuccess("00", "ACCTDAT", "READ", new IllegalStateException()))
                    .as("the cause-carrying overload behaves identically on success")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("End of file THROWS here even though toException returns empty for it, because "
                + "requireSuccess is the keyed-verb guard and has no end-of-file arm")
        void endOfFileThrowsAlthoughToExceptionReturnsEmpty() {
            assertThat(mapper.toException("10", "ACCTDAT", "READ"))
                    .as("the Optional form uses sequential-read semantics, where '10' is normal")
                    .isEmpty();
            FatalProcessingException failure = catchThrowableOfType(FatalProcessingException.class,
                    () -> mapper.requireSuccess("10", "ACCTDAT", "READ"));
            assertThat(failure)
                    .as("requireSuccess uses applResultForGuard, so '10' is a failure; a caller that wants "
                            + "the loop-terminating behaviour must classify with applResultForSequentialRead "
                            + "instead. Observed rather than assumed: the two deliberately disagree here")
                    .isNotNull();
            assertThat(failure.getAbendReason())
                    .as("end of file is not classifiable as a business error, so it reaches the fatal arm "
                            + "with the unrecognised-status reason")
                    .isEqualTo("UNRECOGNISED COBOL FILE STATUS AT I/O GUARD");
        }

        @ParameterizedTest(name = "requireSuccess([{0}]) throws")
        @ValueSource(strings = {"04", "10", "22", "23", "35", "90", "92", "9A", "ZZ", "", "  "})
        @DisplayName("Every status other than '00' throws, so no failure can pass a guard unnoticed")
        void everyNonSuccessStatusThrows(String ioStatus) {
            assertThat(catchThrowableOfType(RuntimeException.class,
                    () -> mapper.requireSuccess(ioStatus, "ACCTDAT", "READ")))
                    .as("the ELSE arm of the idiom always ends in 9999-ABEND-PROGRAM, so the Java guard "
                            + "always throws rather than returning a status the caller might ignore")
                    .isNotNull();
        }

        @Test
        @DisplayName("The thrown type matches the status classification exactly, so a caller can catch the "
                + "specific condition it knows how to handle")
        void theThrownTypeMatchesTheClassification() {
            assertThat(catchThrowableOfType(DuplicateRecordException.class,
                    () -> mapper.requireSuccess("22", "TRANSACT", "WRITE")))
                    .as("'22' is a duplicate key and must be catchable as one")
                    .isNotNull();
            assertThat(catchThrowableOfType(RecordNotFoundException.class,
                    () -> mapper.requireSuccess("23", "ACCTDAT", "READ")))
                    .as("'23' is a not-found and must be catchable as one")
                    .isNotNull();
            assertThat(catchThrowableOfType(FileUnavailableException.class,
                    () -> mapper.requireSuccess("35", "CUSTDAT", "OPEN")))
                    .as("'35' is an unavailable file and must be catchable as one")
                    .isNotNull();
            assertThat(catchThrowableOfType(FileAccessException.class,
                    () -> mapper.requireSuccess("92", "TRANSACT", "READ")))
                    .as("the '9x' family is a physical or logical I/O error and must be catchable as one")
                    .isNotNull();
        }

        @Test
        @DisplayName("A null status throws fatally rather than passing, because a caller that lost the "
                + "status cannot have verified anything")
        void aNullStatusThrowsFatally() {
            assertThat(catchThrowableOfType(FatalProcessingException.class,
                    () -> mapper.requireSuccess(null, "ACCTDAT", "READ")))
                    .as("failing closed is the only safe reading of an absent status")
                    .isNotNull();
        }

        @Test
        @DisplayName("The cause-carrying overload chains the cause while the three-argument one leaves it "
                + "unset, so the two overloads differ in exactly one observable way")
        void theOverloadsDifferOnlyInTheChainedCause() {
            Throwable cause = new IllegalStateException("driver said no");
            assertThat(catchThrowableOfType(RecordNotFoundException.class,
                    () -> mapper.requireSuccess("23", "ACCTDAT", "READ", cause)))
                    .as("the root cause must survive translation so that a stack trace still reaches the "
                            + "driver frame that failed")
                    .hasCause(cause);
            assertThat(catchThrowableOfType(RecordNotFoundException.class,
                    () -> mapper.requireSuccess("23", "ACCTDAT", "READ")))
                    .as("and the shorter overload must not invent one")
                    .hasNoCause();
        }
    }

    @Nested
    @DisplayName("The sequential-read classification is the read-loop form and reports end of file as a value")
    class SequentialReadClassificationForm {

        @Test
        @DisplayName("Success classifies as APPL_AOK and end of file as APPL_EOF, so a read loop can be "
                + "driven by the returned code with no exception in the normal path")
        void successClassifiesAokAndEndOfFileClassifiesEof() {
            assertThat(mapper.applResultForSequentialRead("00"))
                    .as("APPL_AOK means keep going, mirroring the CONTINUE arm")
                    .isEqualTo(FileStatusMapper.APPL_AOK);
            assertThat(mapper.applResultForSequentialRead("10"))
                    .as("APPL_EOF means the file is exhausted, mirroring MOVE 'Y' TO END-OF-FILE at "
                            + "app/cbl/CBSTM03A.CBL:L356")
                    .isEqualTo(FileStatusMapper.APPL_EOF);
        }

        @ParameterizedTest(name = "applResultForSequentialRead([{0}]) is APPL_FAILURE")
        @ValueSource(strings = {"04", "22", "23", "35", "90", "9A", "ZZ", "", "  "})
        @DisplayName("Anything that is neither success nor end of file classifies as a failure, so the loop "
                + "cannot spin on an unhandled status")
        void anythingElseClassifiesAsFailure(String ioStatus) {
            assertThat(mapper.applResultForSequentialRead(ioStatus))
                    .as("the WHEN OTHER arm at app/cbl/CBSTM03A.CBL:L357-L360 abends, so the classification "
                            + "must be a failure rather than a third tolerated outcome")
                    .isEqualTo(FileStatusMapper.APPL_FAILURE);
        }

        @Test
        @DisplayName("The classification decides only the branch; the thrown type still comes from the map, "
                + "so a reader that abends produces the same taxonomy as a keyed verb")
        void theThrownTypeStillComesFromTheMap() {
            // The four sequential readers classify with this method and then abend with their own DISPLAY
            // literal and culprit. The exception subtype is still the map's, reached through toException.
            assertThat(mapper.toException("23", "ACCTDAT", "READ"))
                    .as("the map answers for '23' regardless of which classifier the caller used")
                    .containsInstanceOf(RecordNotFoundException.class);
            assertThat(mapper.toException("35", "ACCTDAT", "READ"))
                    .as("the same for an unavailable file")
                    .containsInstanceOf(FileUnavailableException.class);
        }

        @Test
        @DisplayName("The two classifications disagree on '10' and agree everywhere else, which is the whole "
                + "reason the corpus needs both")
        void theTwoClassificationsDifferOnlyOnEndOfFile() {
            assertThat(mapper.applResultForSequentialRead("10"))
                    .as("the sequential-read form accepts end of file")
                    .isEqualTo(FileStatusMapper.APPL_EOF);
            assertThat(mapper.applResultForGuard("10"))
                    .as("while the OPEN and WRITE form rejects it; a caller must choose the form that "
                            + "matches its COBOL original rather than treating the two as interchangeable")
                    .isEqualTo(FileStatusMapper.APPL_FAILURE);
            for (String agreed : new String[] {"00", "04", "22", "23", "35", "90", "ZZ"}) {
                assertThat(mapper.applResultForSequentialRead(agreed))
                        .as("the two agree on [%s]", agreed)
                        .isEqualTo(mapper.applResultForGuard(agreed));
            }
        }
    }

    @Nested
    @DisplayName("Three call sites treat record-not-found differently, and the difference is behaviour")
    class AcceptedNotFoundControlPaths {

        @Test
        @DisplayName("The transaction-category-balance read accepts '23' as the create signal, returning "
                + "true rather than throwing, exactly as 2700-UPDATE-TCATBAL does")
        void theCategoryBalanceReadAcceptsNotFoundAsTheCreateSignal() {
            assertThat(mapper.requireCategoryBalanceReadSuccess("00"))
                    .as("false means the row was found, so the caller takes the rewrite branch at "
                            + "app/cbl/CBTRN02C.cbl 2700-B-UPDATE-TCATBAL-REC")
                    .isFalse();
            assertThat(mapper.requireCategoryBalanceReadSuccess("23"))
                    .as("true means the row was absent, so the caller takes the create branch at "
                            + "2700-A-CREATE-TCATBAL-REC; app/cbl/CBTRN02C.cbl:L481 accepts '00' OR '23', "
                            + "so throwing here would abend the upsert on every first posting for a "
                            + "type-and-category pair")
                    .isTrue();
        }

        @Test
        @DisplayName("The disclosure-group read accepts '23' as the default-fallback signal, returning true "
                + "rather than throwing, exactly as 1200-GET-INTEREST-RATE does")
        void theDisclosureGroupReadAcceptsNotFoundAsTheFallbackSignal() {
            assertThat(mapper.requireDisclosureGroupReadSuccess("00"))
                    .as("false means the group-specific rate was found and no retry is needed")
                    .isFalse();
            assertThat(mapper.requireDisclosureGroupReadSuccess("23"))
                    .as("true means the caller must substitute the literal 'DEFAULT' group and retry, per "
                            + "app/cbl/CBACT04C.cbl:L436-L438")
                    .isTrue();
        }

        @Test
        @DisplayName("The default disclosure-group read treats the very same '23' as FATAL, because "
                + "1200-A-GET-DEFAULT-INT-RATE accepts '00' alone and a fallback has no fallback")
        void theDefaultDisclosureGroupReadTreatsNotFoundAsFatal() {
            assertThatCode(() -> mapper.requireDefaultDisclosureGroupReadSuccess("00"))
                    .as("success returns quietly and reports nothing, because there is no second outcome "
                            + "for the caller to branch on")
                    .doesNotThrowAnyException();
            FatalProcessingException failure = catchThrowableOfType(FatalProcessingException.class,
                    () -> mapper.requireDefaultDisclosureGroupReadSuccess("23"));
            assertThat(failure)
                    .as("app/cbl/CBACT04C.cbl:L446 tests DISCGRP-STATUS = '00' only, so '23' falls into the "
                            + "ELSE and reaches 9999-ABEND-PROGRAM; accepting it would let the interest job "
                            + "continue with no rate at all and silently under-accrue interest")
                    .isNotNull();
            assertThat(failure.getAbendReason())
                    .as("the reason is the paragraph's own DISPLAY literal at app/cbl/CBACT04C.cbl:L455")
                    .isEqualTo(FileStatusMapper.DEFAULT_DISCGRP_READ_FAILURE_TEXT);
            assertThat(failure.getAbendCulprit())
                    .as("and the culprit names the interest program, so the abend identifies its origin")
                    .isEqualTo("CBACT04C");
        }

        @Test
        @DisplayName("The same not-found status is accepted twice and fatal once across the three call "
                + "sites, asserted side by side so the asymmetry cannot be read as an inconsistency")
        void theSameNotFoundStatusIsAcceptedTwiceAndFatalOnceOnTheThirdCallSite() {
            assertThat(mapper.requireCategoryBalanceReadSuccess("23"))
                    .as("site one accepts it: app/cbl/CBTRN02C.cbl:L481 reads '00' OR '23'")
                    .isTrue();
            assertThat(mapper.requireDisclosureGroupReadSuccess("23"))
                    .as("site two accepts it: app/cbl/CBACT04C.cbl:L422 reads '00' OR '23'")
                    .isTrue();
            assertThat(catchThrowableOfType(FatalProcessingException.class,
                    () -> mapper.requireDefaultDisclosureGroupReadSuccess("23")))
                    .as("site three does not: app/cbl/CBACT04C.cbl:L446 reads '00' alone. A single "
                            + "not-found-means-accepted rule applied to all three would break the interest "
                            + "job, and a single not-found-means-exception rule would break the posting job")
                    .isNotNull();
        }

        @Test
        @DisplayName("A genuine failure at the category-balance read carries the paragraph's DISPLAY literal "
                + "as the leading clause of the message, matching the legacy SYSOUT ordering")
        void theCategoryBalanceFailureMessageLeadsWithTheLegacyText() {
            FileUnavailableException failure = catchThrowableOfType(FileUnavailableException.class,
                    () -> mapper.requireCategoryBalanceReadSuccess("35"));
            assertThat(failure)
                    .as("app/cbl/CBTRN02C.cbl:L489-L491 displays the failure text first and the status "
                            + "second, so the composed message preserves that order")
                    .hasMessage("ERROR READING TRANSACTION BALANCE FILE - READ of TCATBALF reported COBOL "
                            + "FILE STATUS 35 (IO-STATUS-04 0035)");
            assertThat(failure.resourceName())
                    .as("the logical file is the batch-only TCATBALF dataset, which has no CICS definition "
                            + "in app/csd/CARDDEMO.CSD and is therefore only ever reached from batch")
                    .contains("TCATBALF");
        }

        @Test
        @DisplayName("A genuine failure at the disclosure-group read names DISCGRP and its own literal, so "
                + "the two disclosure-group reads remain distinguishable in the log")
        void theDisclosureGroupFailureMessageNamesItsOwnFile() {
            assertThat(catchThrowableOfType(FileUnavailableException.class,
                    () -> mapper.requireDisclosureGroupReadSuccess("35")))
                    .as("the first read reports the GROUP FILE literal from app/cbl/CBACT04C.cbl:L431")
                    .hasMessage("ERROR READING DISCLOSURE GROUP FILE - READ of DISCGRP reported COBOL FILE "
                            + "STATUS 35 (IO-STATUS-04 0035)");
            assertThat(catchThrowableOfType(FileUnavailableException.class,
                    () -> mapper.requireDefaultDisclosureGroupReadSuccess("35")))
                    .as("while the default retry reports the DEFAULT GROUP literal from :L455, on the same "
                            + "file; only the leading clause distinguishes them")
                    .hasMessage("ERROR READING DEFAULT DISCLOSURE GROUP - READ of DISCGRP reported COBOL "
                            + "FILE STATUS 35 (IO-STATUS-04 0035)");
        }

        @ParameterizedTest(name = "the category-balance read throws for [{0}]")
        @ValueSource(strings = {"04", "10", "22", "35", "90", "9A", "ZZ", "", "  "})
        @DisplayName("Every status other than '00' and '23' throws at the category-balance read, so the "
                + "widened accepted set stays exactly two values wide")
        void theCategoryBalanceReadThrowsForEveryOtherStatus(String ioStatus) {
            assertThat(catchThrowableOfType(RuntimeException.class,
                    () -> mapper.requireCategoryBalanceReadSuccess(ioStatus)))
                    .as("app/cbl/CBTRN02C.cbl:L481 widens the accepted set by exactly one value; anything "
                            + "further would swallow real I/O failures during posting")
                    .isNotNull();
        }

        @ParameterizedTest(name = "the disclosure-group read throws for [{0}]")
        @ValueSource(strings = {"04", "10", "22", "35", "90", "9A", "ZZ", "", "  "})
        @DisplayName("Every status other than '00' and '23' throws at the disclosure-group read, mirroring "
                + "the posting job's guard rather than relaxing it")
        void theDisclosureGroupReadThrowsForEveryOtherStatus(String ioStatus) {
            assertThat(catchThrowableOfType(RuntimeException.class,
                    () -> mapper.requireDisclosureGroupReadSuccess(ioStatus)))
                    .as("app/cbl/CBACT04C.cbl:L422 accepts '00' OR '23' and nothing else")
                    .isNotNull();
        }

        @ParameterizedTest(name = "the default disclosure-group read throws for [{0}]")
        @ValueSource(strings = {"04", "10", "22", "23", "35", "90", "9A", "ZZ", "", "  "})
        @DisplayName("Only '00' passes the default disclosure-group read, and '23' is included in the "
                + "throwing set to make the contrast with the other two methods explicit")
        void onlySuccessPassesTheDefaultDisclosureGroupRead(String ioStatus) {
            assertThat(catchThrowableOfType(RuntimeException.class,
                    () -> mapper.requireDefaultDisclosureGroupReadSuccess(ioStatus)))
                    .as("app/cbl/CBACT04C.cbl:L446 tests for '00' alone, which is why '23' appears in this "
                            + "list and not in the two above it")
                    .isNotNull();
        }

        @Test
        @DisplayName("An unclassifiable status at a specialised read is fatal and names the owning program, "
                + "so an unexpected status is attributable to a COBOL paragraph")
        void anUnclassifiableStatusAtASpecialisedReadNamesTheOwningProgram() {
            FatalProcessingException posting = catchThrowableOfType(FatalProcessingException.class,
                    () -> mapper.requireCategoryBalanceReadSuccess("ZZ"));
            assertThat(posting.getAbendCulprit())
                    .as("the posting program owns 2700-UPDATE-TCATBAL, so it is the culprit")
                    .isEqualTo("CBTRN02C");
            assertThat(posting.getAbendReason())
                    .as("the reason stays the paragraph's own literal rather than the generic "
                            + "unrecognised-status text, because the guard knows which read failed")
                    .isEqualTo(FileStatusMapper.TCATBAL_READ_FAILURE_TEXT);
            FatalProcessingException interest = catchThrowableOfType(FatalProcessingException.class,
                    () -> mapper.requireDisclosureGroupReadSuccess("ZZ"));
            assertThat(interest.getAbendCulprit())
                    .as("the interest program owns 1200-GET-INTEREST-RATE, so it is the culprit there")
                    .isEqualTo("CBACT04C");
            assertThat(interest.getAbendReason())
                    .as("with its own paragraph literal as the reason")
                    .isEqualTo(FileStatusMapper.DISCGRP_READ_FAILURE_TEXT);
        }

        @Test
        @DisplayName("A null status at each specialised read is fatal rather than accepted, so a lost "
                + "status can never be mistaken for the create or fallback signal")
        void aNullStatusAtEachSpecialisedReadIsFatal() {
            assertThat(catchThrowableOfType(FatalProcessingException.class,
                    () -> mapper.requireCategoryBalanceReadSuccess(null)))
                    .as("mistaking a lost status for '23' would insert a spurious category-balance row")
                    .isNotNull();
            assertThat(catchThrowableOfType(FatalProcessingException.class,
                    () -> mapper.requireDisclosureGroupReadSuccess(null)))
                    .as("and would silently apply the default interest rate to an account entitled to its "
                            + "own group rate")
                    .isNotNull();
            assertThat(catchThrowableOfType(FatalProcessingException.class,
                    () -> mapper.requireDefaultDisclosureGroupReadSuccess(null)))
                    .as("while the default read has no accepted second outcome at all")
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("The statement file service tolerates '04' on an open but not on a read")
    class StatementFileServiceReturnCodes {

        @Test
        @DisplayName("Both '00' and '04' are accepted, reproducing the nine IF WS-M03B-RC = '00' OR '04' "
                + "guards in CBSTM03A")
        void bothSuccessAndAcceptedSecondaryPass() {
            assertThatCode(() -> mapper.requireFileServiceSuccess("00", "TRNXFILE", "OPEN"))
                    .as("app/cbl/CBSTM03A.CBL:L736 accepts '00'")
                    .doesNotThrowAnyException();
            assertThatCode(() -> mapper.requireFileServiceSuccess("04", "TRNXFILE", "OPEN"))
                    .as("and the same line accepts '04'; this is the third exception to the general "
                            + "status mapping and it exists only at this call site. Observed rather than "
                            + "assumed: the generic requireSuccess rejects '04'")
                    .doesNotThrowAnyException();
            assertThat(catchThrowableOfType(FatalProcessingException.class,
                    () -> mapper.requireSuccess("04", "TRNXFILE", "OPEN")))
                    .as("proving the tolerance is scoped to the file service rather than global")
                    .isNotNull();
        }

        @ParameterizedTest(name = "the file-service guard abends for [{0}]")
        @ValueSource(strings = {"10", "22", "23", "35", "90", "9A", "ZZ", "", "  "})
        @DisplayName("Every other return code abends, including end of file, because an open that reports "
                + "end of file has not opened anything")
        void everyOtherReturnCodeAbends(String returnCode) {
            assertThat(catchThrowableOfType(FatalProcessingException.class,
                    () -> mapper.requireFileServiceSuccess(returnCode, "TRNXFILE", "OPEN")))
                    .as("the ELSE arm at app/cbl/CBSTM03A.CBL:L739-L742 displays the return code and "
                            + "performs 9999-ABEND-PROGRAM with no business error path at all")
                    .isNotNull();
        }

        @Test
        @DisplayName("The abend names CBSTM03A as the culprit and carries the file-service reason, so a "
                + "statement-run failure is attributable without reading the message")
        void theAbendNamesTheStatementProgram() {
            FatalProcessingException failure = catchThrowableOfType(FatalProcessingException.class,
                    () -> mapper.requireFileServiceSuccess("12", "TRNXFILE", "OPEN"));
            assertThat(failure.getAbendCulprit())
                    .as("the call site lives in the statement program, which calls CBSTM03B through "
                            + "WS-M03B-AREA; the culprit is the caller, not the subprogram")
                    .isEqualTo("CBSTM03A");
            assertThat(failure.getAbendReason())
                    .as("the reason distinguishes an unaccepted subprogram return code from an "
                            + "unrecognised COBOL file status, because they are different faults")
                    .isEqualTo("UNACCEPTED RETURN CODE FROM STATEMENT FILE SERVICE");
            assertThat(failure.getAbendCode())
                    .as("the abend code stays unset here too, for the same reason as the generic guard")
                    .isEqualTo(FileStatusMapper.ABEND_CODE_UNSET);
        }

        @Test
        @DisplayName("The abend message quotes the DISPLAY 'RETURN CODE: ' literal and the code, and does "
                + "NOT use the COBOL FILE STATUS wording, because the subprogram reports its own code")
        void theAbendMessageUsesTheReturnCodeWordingRatherThanTheFileStatusWording() {
            FatalProcessingException failure = catchThrowableOfType(FatalProcessingException.class,
                    () -> mapper.requireFileServiceSuccess("12", "TRNXFILE", "OPEN"));
            assertThat(failure)
                    .as("app/cbl/CBSTM03A.CBL:L740-L741 displays 'ERROR OPENING TRNXFILE' then "
                            + "'RETURN CODE: ' WS-M03B-RC, so the composed message names the operation, the "
                            + "DD name and the return code in that order")
                    .hasMessage("OPEN of TRNXFILE through the statement file service reported "
                            + "RETURN CODE: 12");
            assertThat(failure.getMessage())
                    .as("WS-M03B-RC is a subprogram return code and not a VSAM FILE STATUS; borrowing the "
                            + "file-status wording here would misattribute the failure")
                    .doesNotContain("COBOL FILE STATUS");
        }

        @Test
        @DisplayName("The read-loop form accepts '00' and '10' only, so '04' abends there even though the "
                + "open guard accepts it - the two CBSTM03A idioms genuinely differ")
        void theReadLoopFormRejectsTheAcceptedSecondaryStatus() {
            assertThat(mapper.requireFileServiceSuccessOrEndOfFile("00", "XREFFILE", "READ"))
                    .as("false means keep reading, matching WHEN '00' CONTINUE at "
                            + "app/cbl/CBSTM03A.CBL:L353-L354")
                    .isFalse();
            assertThat(mapper.requireFileServiceSuccessOrEndOfFile("10", "XREFFILE", "READ"))
                    .as("true means end of file, matching WHEN '10' MOVE 'Y' TO END-OF-FILE at :L355-L356")
                    .isTrue();
            assertThat(catchThrowableOfType(FatalProcessingException.class,
                    () -> mapper.requireFileServiceSuccessOrEndOfFile("04", "XREFFILE", "READ")))
                    .as("the EVALUATE at :L352-L361 has no '04' arm, so it falls to WHEN OTHER and abends. "
                            + "Observed rather than assumed: the open guard on the very same shared area "
                            + "accepts '04' at :L736, so the tolerance is per-operation and not per-file")
                    .isNotNull();
        }

        @ParameterizedTest(name = "the file-service read loop abends for [{0}]")
        @ValueSource(strings = {"04", "22", "23", "35", "90", "9A", "ZZ", "", "  "})
        @DisplayName("Everything that is neither '00' nor '10' abends in the read loop, so a partial read "
                + "can never be mistaken for a completed one")
        void theReadLoopAbendsForEverythingElse(String returnCode) {
            assertThat(catchThrowableOfType(FatalProcessingException.class,
                    () -> mapper.requireFileServiceSuccessOrEndOfFile(returnCode, "XREFFILE", "READ")))
                    .as("WHEN OTHER at app/cbl/CBSTM03A.CBL:L357-L360 abends unconditionally")
                    .isNotNull();
        }

        @Test
        @DisplayName("An absent DD name, operation or return code is replaced by a descriptive placeholder, "
                + "so the abend message stays diagnostic instead of collapsing")
        void absentFileServiceArgumentsUsePlaceholders() {
            FatalProcessingException failure = catchThrowableOfType(FatalProcessingException.class,
                    () -> mapper.requireFileServiceSuccess(null, null, null));
            assertThat(failure)
                    .as("all three placeholders read as English in position, and (absent) marks the missing "
                            + "return code rather than leaving a dangling colon")
                    .hasMessage("An unidentified operation of an unidentified logical file through the "
                            + "statement file service reported RETURN CODE: (absent)");
            assertThat(catchThrowableOfType(FatalProcessingException.class,
                    () -> mapper.requireFileServiceSuccessOrEndOfFile("  ", "   ", "  ")))
                    .as("blank arguments are treated as absent, because an unmoved COBOL field is spaces")
                    .hasMessage("An unidentified operation of an unidentified logical file through the "
                            + "statement file service reported RETURN CODE: (absent)");
        }

        @Test
        @DisplayName("The file-service abend chains no cause, because a subprogram return code is an "
                + "observed value rather than a caught exception")
        void theFileServiceAbendChainsNoCause() {
            assertThat(catchThrowableOfType(FatalProcessingException.class,
                    () -> mapper.requireFileServiceSuccess("12", "TRNXFILE", "OPEN")))
                    .as("CALL 'CBSTM03B' returns a code in WS-M03B-RC; there is no Java throwable behind "
                            + "it, so fabricating a cause would invent a stack frame")
                    .hasNoCause();
        }
    }

    @Nested
    @DisplayName("The mapper is stateless, which is what makes one shared bean safe")
    class Statelessness {

        @Test
        @DisplayName("The class declares no instance field at all, so the single bean Spring creates is "
                + "inherently thread safe across batch threads and request threads alike")
        void theMapperDeclaresNoInstanceStateAtAll() {
            Field[] instanceFields = Arrays.stream(FileStatusMapper.class.getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .toArray(Field[]::new);
            assertThat(instanceFields)
                    .as("every method is a pure function of its arguments; a single mutable field would "
                            + "make the shared bean unsafe for the parallel statement and report branches "
                            + "that FlowBuilder.split() runs concurrently")
                    .isEmpty();
        }

        @Test
        @DisplayName("Every public constant the class exposes is static and final, so no caller can mutate "
                + "the guard vocabulary at runtime")
        void everyExposedConstantIsStaticAndFinal() {
            assertThat(Arrays.stream(FileStatusMapper.class.getDeclaredFields())
                    .filter(field -> Modifier.isPublic(field.getModifiers()))
                    .allMatch(field -> Modifier.isStatic(field.getModifiers())
                            && Modifier.isFinal(field.getModifiers())))
                    .as("the APPL-RESULT codes and abend widths are COBOL literals; exposing a mutable one "
                            + "would let a test or a caller redefine what success means")
                    .isTrue();
        }

        @Test
        @DisplayName("Two independently constructed mappers agree on every answer, confirming that no "
                + "instance carries hidden history from earlier calls")
        void twoIndependentInstancesAgreeOnEveryAnswer() {
            FileStatusMapper first = new FileStatusMapper();
            FileStatusMapper second = new FileStatusMapper();
            first.requireCategoryBalanceReadSuccess("23");
            for (String ioStatus : STATUS_VOCABULARY) {
                assertThat(second.applResultForGuard(ioStatus))
                        .as("guard result for [%s] must not depend on which instance answered, nor on what "
                                + "that instance was asked before", ioStatus)
                        .isEqualTo(first.applResultForGuard(ioStatus));
                assertThat(second.displayIoStatus(ioStatus))
                        .as("nor must the rendered display line for [%s]", ioStatus)
                        .isEqualTo(first.displayIoStatus(ioStatus));
            }
        }
    }
}
