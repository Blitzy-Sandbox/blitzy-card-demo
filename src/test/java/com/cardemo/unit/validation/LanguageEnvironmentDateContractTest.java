/*
 * ******************************************************************
 * Program     : LanguageEnvironmentDateContractTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/validation
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Pins the layer-2 CEEDAYS message contract of the
 *               statically called date validation subprogram, derived
 *               from the raw eight-byte feedback tokens and from the
 *               two caller-side parameter blocks rather than from the
 *               Java source: the token byte layout and its EBCDIC
 *               facility identifier, the misnamed all-zero success
 *               token, the nine decoded severity and message-number
 *               pairs with their fifteen-character result literals,
 *               the eighty-byte result area proven from producer and
 *               caller sides at once, the preserved group-move defect
 *               that destroys two characters of the tested date while
 *               leaving the mask field clean, the dual result-area and
 *               return-code output channels, and the two picture masks
 *               of differing width.
 * Source      : app/cbl/CSUTLDTC.cbl:L1-L157 (the whole subprogram:
 *               L42-L57 producer area, L60-L80 feedback code, L62-L70
 *               the nine tokens, L88 entry, L97-L98 dual output,
 *               L103-L151 A000-MAIN, L122 the preserved defect,
 *               L128-L149 the ten-branch EVALUATE, L152 the exit
 *               paragraph) @ 7756d89
 * Source      : app/cbl/CORPT00C.cbl:L72 (delimited mask), L129-L136
 *               (caller parameter block), L392, L412 (call sites),
 *               L396, L399 (the success and tolerance guards) @ 7756d89
 * Source      : app/cbl/COTRN02C.cbl:L60 (delimited mask), L62-L69
 *               (caller parameter block), L393, L413 (call sites),
 *               L397, L400 (the success and tolerance guards) @ 7756d89
 * Source      : app/cpy/CSUTLDWY.cpy:L4 (the eight-byte compact date),
 *               L58-L59 (undelimited mask), L60-L85 (the third view of
 *               the same eighty bytes) @ 7756d89
 * Source      : app/cpy/CSUTLDPY.cpy:L290-L298 (the undelimited call
 *               path and its severity guard) @ 7756d89
 * Source      : app/cbl/CBACT04C.cbl:L1-L21 (the canonical banner this
 *               header reproduces) @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.DateValidationService.DateValidationResult;
import com.cardemo.service.shared.DateValidationService.FeedbackCode;
import com.cardemo.service.shared.DateValidationService.FeedbackCondition;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The layer-2 CEEDAYS message contract of {@code app/cbl/CSUTLDTC.cbl}, asserted against the frozen
 * COBOL rather than against a plausible reading of the Java that replaced it.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>{@code app/cbl/CSUTLDTC.cbl} is a 157-line statically called subprogram - absent from the CICS
 * resource definitions, so it is called and never transacted - whose only job is to invoke the IBM
 * Language Environment callable service {@code CEEDAYS} and render its feedback into an 80-byte block.
 * Its Java replacement is {@link DateValidationService}.
 *
 * <p>This class asserts the <strong>data contract</strong>: the rendered bytes and the decoded
 * outcomes. It deliberately derives every expectation from one of two places that are not the Java
 * source, so that each assertion is evidence rather than a restatement of the code under test:
 *
 * <ul>
 *   <li><strong>The raw eight-byte feedback tokens</strong> transcribed from
 *       {@code app/cbl/CSUTLDTC.cbl:L62-L70} as hexadecimal literals and decoded here, byte by byte,
 *       into a severity halfword, a message-number halfword, a case-severity control byte and a
 *       three-byte facility identifier. No severity or message number in this file was copied from
 *       {@link FeedbackCondition}; every one was computed from its token.</li>
 *   <li><strong>The two caller-side parameter blocks</strong>, at
 *       {@code app/cbl/CORPT00C.cbl:L129-L136} and {@code app/cbl/COTRN02C.cbl:L62-L69}, which view
 *       the same 80 bytes through a different and coarser field structure. Reconciling the producer's
 *       thirteen fields against the caller's four is what proves the geometry from both ends.</li>
 *   </ul>
 *
 * <p>Scope boundary: the sibling {@code DateValidationServiceTest},
 * {@code DateValidationServiceGuardPathTest} and {@code DateValidationServiceSweepTest} in this same
 * package assert the injected bean's <em>behaviour</em> - which inputs select which outcome. This class
 * asserts the <em>contract</em> those outcomes are expressed in. Both are required and neither
 * substitutes for the other, so nothing here restates an input-to-outcome mapping those files already
 * own.
 *
 * <h3>Blocker: the token named for an invalid date is the success token</h3>
 *
 * <p>{@code app/cbl/CSUTLDTC.cbl:L62} declares
 * {@code 88 FC-INVALID-DATE VALUE X'0000000000000000'}, and {@code :L129-L130} maps that condition to
 * the text {@code Date is valid}. All zeros is the {@code CEEDAYS} all-clear feedback, so the
 * identifier is simply <strong>misnamed in the frozen source</strong>: it means success.
 *
 * <p>This is classified Blocker for correctness because of how it fails. An implementation that trusts
 * the identifier and maps a token named "invalid" to an invalid outcome produces <em>exactly inverted
 * behaviour</em> - every valid date rejected and every invalid date accepted - and it will still
 * compile, still run, and still pass a naively written test that shares the same misreading. Three
 * independent call sites in the frozen corpus corroborate the correct reading, and this class asserts
 * against all three: {@code app/cbl/CORPT00C.cbl:L396} and {@code app/cbl/COTRN02C.cbl:L397} both test
 * {@code IF CSUTLDTC-RESULT-SEV-CD = '0000'} and then {@code CONTINUE}, and
 * {@code app/cpy/CSUTLDPY.cpy:L298} tests {@code IF WS-SEVERITY-N = 0} and then {@code CONTINUE}.
 *
 * <p>Remediation if an assertion here fails: re-read {@code app/cbl/CSUTLDTC.cbl:L62} and
 * {@code :L129-L130} together. Do not rename the concept away, and do not "correct" the mapping. The
 * source name is preserved in the citations on this file's own constants so that the misnomer stays
 * traceable, while those constants are themselves named for what the token <em>means</em> so they
 * cannot mislead a later reader.
 *
 * <h3>High: the result area destroys two characters of the tested date, on purpose</h3>
 *
 * <p>{@code app/cbl/CSUTLDTC.cbl:L107-L108} is a single {@code MOVE} with two receiving fields that
 * places the clean ten-character date into {@code WS-DATE}. After the call,
 * <strong>{@code :L122 MOVE WS-DATE-TO-TEST TO WS-DATE}</strong> overwrites it. The sending field is
 * the whole varying-length group of {@code :L25-L31} - a two-byte binary length followed by the text -
 * and the receiving field is {@code PIC X(10)}, so the group move writes the binary length 10 into
 * bytes one and two and only the first eight characters of the date survive. The last two characters
 * are lost from the rendering.
 *
 * <p>The asymmetry matters as much as the defect. There is <strong>no</strong> post-call move
 * targeting {@code WS-DATE-FMT}, so the {@code Mask used:} field renders cleanly while
 * {@code TstDate:} renders corrupted. Both halves are asserted in one named test, because normalising
 * either one away is the likeliest accidental "improvement".
 *
 * <p>Remediation if an assertion here fails: something tidied up the {@code :L122} group move. Restore
 * it. Parity is the contract and the end-to-end gate compares rendered output against the legacy
 * baseline, so a corrected rendering is a diff.
 *
 * <h3>These assertions are the tracking reference</h3>
 *
 * <p>Rule 1 Clause B forbids dead code and untracked deferred work; the migration mandate requires
 * preserving legacy behaviour one-to-one. Where those collide the AAP resolves for parity, and Clause
 * B is satisfied on its own wording - it prohibits artefacts <em>without owners or tracking
 * reference</em>. For the two preserved defects this file pins, the misnamed success token and the
 * {@code :L122} group move, <strong>these assertions are that tracking reference</strong>, alongside
 * the entries and rows they are owed in the planned {@code DECISION_LOG.md} and
 * {@code TRACEABILITY_MATRIX.md}.
 * <strong>No downstream agent may change the production code to make a test here read more
 * sensibly.</strong> The reading is supposed to be uncomfortable; the source is.
 *
 * <h2>2. How to run, build and test</h2>
 *
 * <pre>{@code
 * ./mvnw -B -ntp -o test -Dtest=LanguageEnvironmentDateContractTest
 * ./mvnw -B -ntp -o test
 * }</pre>
 *
 * <p>Surefire 3.5.4 collects this tier through {@code **}{@code /*Test.java} while excluding
 * {@code **}{@code /integration/**} and {@code **}{@code /e2e/**}, so a class named
 * {@code ...ContractTest} under {@code src/test/java/com/cardemo/unit/validation} is collected by
 * Surefire and not by Failsafe. Relocating or renaming it is a Blocker: a class matching neither
 * plugin's include set is collected by neither, and it then never runs while the build stays green and
 * coverage silently drops - no error and no warning anywhere.
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <ul>
 *   <li><strong>No clock and no fixtures are used by this class.</strong> The contract asserted here is
 *       pure rendering and decoding, so there is nothing to date and nothing to load. The service
 *       nevertheless requires a {@link Clock} by constructor injection for an unrelated future-date
 *       guard, so a fixed instant is supplied. Nothing here reads the system clock, the default locale,
 *       the default time zone or a random source, so no test can pass today and fail tomorrow. The
 *       sibling helpers {@code FixedClockProvider} and {@code FixtureLoader} in
 *       {@code com.cardemo.unit.model} are deliberately not imported: this class needs neither, and an
 *       unused import is forbidden by Rule 1 Clause B. Note that it is <em>not</em> the compiler that
 *       enforces that - {@code javac} 25 publishes no {@code unused} lint key at all, as
 *       {@code javac --help-lint} shows - so it is a review matter.</li>
 *   <li><strong>No mocks.</strong> Real objects throughout. There is no collaborator to stub - the
 *       subject is a pure function of two strings - so introducing a test double would assert the
 *       double rather than the contract.</li>
 *   <li><strong>Charsets are always explicit.</strong> This class is the one place in the tier where
 *       bytes and characters meet, so the platform default is never relied on.
 *       {@code IBM037} decodes the EBCDIC facility identifier and
 *       {@link StandardCharsets#US_ASCII} is named wherever ASCII is meant.</li>
 *   <li><strong>{@link Locale#ROOT} on every case and format operation</strong>, so a Turkish or Thai
 *       default locale cannot change an outcome.</li>
 *   <li><strong>Two masks, two widths.</strong> {@code DateValidationService.MASK_YYYYMMDD} is the
 *       eight-character undelimited mask of the copybook path, declared
 *       {@code PIC X(08)} at {@code app/cpy/CSUTLDWY.cpy:L58-L59}.
 *       {@code DateValidationService.MASK_YYYY_MM_DD} is the ten-character delimited mask of the
 *       program path, declared {@code PIC X(10)} at {@code app/cbl/CORPT00C.cbl:L72} and at
 *       {@code app/cbl/COTRN02C.cbl:L60}. Both are passed through the same {@code PIC X(10)} linkage
 *       item. Conflating them changes what the service is asked to parse.</li>
 *   <li><strong>"Token" here is a diagnostic constant, never a credential.</strong> A {@code CEEDAYS}
 *       feedback token is an eight-byte return code from a date-conversion service. This file holds no
 *       secret, no key, no credential and no personal data: every date in it is synthetic, and
 *       {@code app/data/ASCII/custdata.txt} is deliberately never read because its date-of-birth column
 *       is personally identifying.</li>
 *   </ul>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The build fails with a warning, not a test failure.</strong>
 *       {@code -Xlint:all -Werror} with {@code failOnWarning} reaches test compilation, so one raw type, one
 *       unchecked cast or one documentation comment placed where no declaration follows it - the
 *       {@code dangling-doc-comments} key - ends the build. An unused import does <em>not</em>: {@code javac} 25 has
 *       no {@code unused} lint key, so that prohibition is review-enforced. The banner above is intentionally a plain
 *       block comment and not a documentation comment for exactly that reason.</li>
 *   <li><strong>A severity or message number fails.</strong> Re-decode the hexadecimal token at
 *       {@code app/cbl/CSUTLDTC.cbl:L62-L70}: bytes one and two are the severity, bytes three and four
 *       the message number, both big-endian halfwords. Adjust neither expectation to match the code.</li>
 *   <li><strong>A result literal fails.</strong> The fifteen-character texts are byte-comparable
 *       output, and their trailing spaces are significant. {@code app/cbl/CSUTLDTC.cbl:L126-L127}
 *       carries the source's own width reminder and column ruler for them.</li>
 *   <li><strong>The tested-date field fails.</strong> Something "cleaned up" the {@code :L122} group
 *       move. Restore it; see the High finding above.</li>
 *   <li><strong>The mask field fails.</strong> Something extended the {@code :L122} corruption to
 *       {@code WS-DATE-FMT}. Only {@code WS-DATE} is overwritten. The mask field is clean, and that
 *       asymmetry is the contract.</li>
 *   <li><strong>A gap assertion fails.</strong> The message numbers are not a range. 2510 through
 *       2512, 2514 through 2516 and 2519 are absent from the declared set, and each must fall through
 *       to {@code WHEN OTHER}. Modelling the set as {@code 2507..2521} accepts seven values the source
 *       never declares.</li>
 *   <li><strong>An EBCDIC assertion fails with an unsupported-charset error.</strong> The runtime is
 *       missing {@code IBM037}. A full JDK provides it; a trimmed runtime image may not. The affected
 *       test names the charset in its own assertion first, so the diagnosis is immediate.</li>
 *   <li><strong>Fixture naming, for the suite generally.</strong> The daily transaction fixture is
 *       {@code dailytran.txt}, spelled in full - never {@code dalytran.txt}, which is the mainframe
 *       dataset name and matches no file on disk. This class reads no fixture, so it cannot hit that
 *       trap, but the trap is adjacent.</li>
 *   </ul>
 *
 * <h2>5. Not available</h2>
 *
 * <p>Stated plainly rather than filled with invention, and each with what would be needed to close it:
 *
 * <ul>
 *   <li><strong>The internal algorithm of {@code CEEDAYS}, and its behaviour for any input mapping to
 *       none of the nine declared tokens.</strong> The only behaviour the source defines for an
 *       unrecognised token is the {@code WHEN OTHER} arm at {@code app/cbl/CSUTLDTC.cbl:L147-L148}.
 *       Needed: the Language Environment implementation, which is not in this repository.</li>
 *   <li><strong>Why the message-number set is non-contiguous.</strong> No rationale for the absence of
 *       2510 through 2512, 2514 through 2516 and 2519 exists anywhere in the repository. Needed: the
 *       Language Environment message catalogue.</li>
 *   <li><strong>Why {@code FC-INVALID-DATE} was named for failure when its value is the success
 *       token.</strong> Needed: the original authoring history, which the repository does not carry.</li>
 *   <li><strong>Any documented rationale for the {@code :L122} group move.</strong> It is reproduced as
 *       a defect on the evidence of the code alone.</li>
 *   <li><strong>Any latency or throughput objective for this tier.</strong> None exists anywhere in the
 *       source; the performance gate records a measured baseline and never an invented target.</li>
 *   </ul>
 *
 * <p>One planning-time gap has been <em>closed</em> rather than carried: the
 * {@code app/cbl/COTRN02C.cbl} locators were unverified when this file was specified, and they have
 * since been confirmed by direct read - 783 lines, the mask at {@code :L60}, the parameter block at
 * {@code :L62-L69} field-for-field identical to {@code CORPT00C}'s, and the two call sites at
 * {@code :L393} and {@code :L413}. They are therefore cited here as verified.
 *
 * <h2>6. Source-hygiene observations, Low severity</h2>
 *
 * <ul>
 *   <li>{@code app/cbl/CSUTLDTC.cbl:L1-L18} is an eighteen-line banner variant with no
 *       {@code Program}, {@code Application}, {@code Type} or {@code Function} block; its title line
 *       reads {@code CALL TO CEEDAYS}. This file therefore reproduces the canonical twenty-one-line
 *       form from {@code app/cbl/CBACT04C.cbl:L1-L21} instead of imitating its own source.</li>
 *   <li>{@code :L96} and {@code :L101} are a commented-out {@code DISPLAY WS-MESSAGE} and a
 *       commented-out {@code GOBACK}, evidence the program was debugged interactively. Neither is
 *       ported.</li>
 *   <li>{@code :L116} spells the called program with double quotes, {@code CALL "CEEDAYS"}, while
 *       {@code app/cpy/CSUTLDPY.cpy:L293} uses single quotes, {@code CALL 'CSUTLDTC'}. Both are valid
 *       COBOL.</li>
 *   <li>The version stamp at {@code :L155-L157} reads {@code 23:12:35 CDT} while
 *       {@code app/cpy/CSUTLDPY.cpy:L374} and {@code app/cpy/CSUTLDWY.cpy:L88} both read
 *       {@code 23:15:59 CDT}, corroborating that the program was committed separately from the copybook
 *       pair. Recorded as provenance in prose; no assertion is made on a comment string.</li>
 *   <li>Eight of the ten result literals are written with explicit trailing spaces to exactly fifteen
 *       characters while two are shorter and rely on implicit right-padding. All ten render as fifteen
 *       bytes, so the inconsistency is cosmetic - but it is real, and it is asserted.</li>
 *   </ul>
 *
 * <h2>7. Medium-severity findings recorded here</h2>
 *
 * <ul>
 *   <li><strong>Citation drift, source wins.</strong> The caller parameter block of
 *       {@code app/cbl/CORPT00C.cbl} is {@code L129-L136}, not {@code L129-L137}: line 137 is blank
 *       and line 138 is {@code COPY COCOM01Y}. Verified by direct read; the shorter span is cited
 *       throughout.</li>
 *   <li><strong>Divergence between the producer and the copybook view.</strong>
 *       {@code app/cpy/CSUTLDWY.cpy:L68} spells its picture clause {@code Pic 9(4)} in mixed case where
 *       the producer's {@code app/cbl/CSUTLDTC.cbl:L47} uses {@code PIC}, and
 *       {@code app/cpy/CSUTLDWY.cpy:L76} declares {@code WS-DATE} <em>without</em> the
 *       {@code VALUE SPACES} the producer's {@code :L52} carries. The observable outcome converges only
 *       because {@code app/cpy/CSUTLDPY.cpy:L290} issues
 *       {@code INITIALIZE WS-DATE-VALIDATION-RESULT} before the call. The three views remain
 *       byte-compatible, which is what this class asserts.</li>
 *   </ul>
 */
class LanguageEnvironmentDateContractTest {

    // ------------------------------------------------------------------------------------------------
    // The eight-byte feedback token group, app/cbl/CSUTLDTC.cbl:L61-L79.
    //
    // The nine 88-level condition names of :L62-L70 are declared on FEEDBACK-TOKEN-VALUE at :L61, which
    // is a GROUP and not a scalar. Its subordinate items are what make the tokens decodable:
    //   :L72  04 SEVERITY        PIC S9(4) BINARY   -> bytes 1-2
    //   :L73  04 MSG-NO          PIC S9(4) BINARY   -> bytes 3-4
    //   :L78  03 CASE-SEV-CTL    PIC X              -> byte  5
    //   :L79  03 FACILITY-ID     PIC XXX            -> bytes 6-8
    // Offsets below are zero-based for Java indexing; the comments name the one-based COBOL positions.
    // ------------------------------------------------------------------------------------------------

    /** The declared width of {@code FEEDBACK-TOKEN-VALUE}, {@code app/cbl/CSUTLDTC.cbl:L61}. */
    private static final int TOKEN_GROUP_LENGTH = 8;

    /** Zero-based offset of {@code SEVERITY}, {@code app/cbl/CSUTLDTC.cbl:L72}; COBOL bytes 1-2. */
    private static final int SEVERITY_OFFSET = 0;

    /** Width of the {@code PIC S9(4) BINARY} severity halfword, {@code app/cbl/CSUTLDTC.cbl:L72}. */
    private static final int SEVERITY_WIDTH = 2;

    /** Zero-based offset of {@code MSG-NO}, {@code app/cbl/CSUTLDTC.cbl:L73}; COBOL bytes 3-4. */
    private static final int MESSAGE_NUMBER_OFFSET = 2;

    /** Width of the {@code PIC S9(4) BINARY} message-number halfword, {@code :L73}. */
    private static final int MESSAGE_NUMBER_WIDTH = 2;

    /** Zero-based offset of {@code CASE-SEV-CTL}, {@code app/cbl/CSUTLDTC.cbl:L78}; COBOL byte 5. */
    private static final int CASE_SEVERITY_CONTROL_OFFSET = 4;

    /** Width of the {@code PIC X} case-severity control byte, {@code :L78}. */
    private static final int CASE_SEVERITY_CONTROL_WIDTH = 1;

    /** Zero-based offset of {@code FACILITY-ID}, {@code app/cbl/CSUTLDTC.cbl:L79}; COBOL bytes 6-8. */
    private static final int FACILITY_ID_OFFSET = 5;

    /** Width of the {@code PIC XXX} facility identifier, {@code :L79}. */
    private static final int FACILITY_ID_WIDTH = 3;

    /**
     * The single value byte 5 holds in every one of the eight non-zero tokens of
     * {@code app/cbl/CSUTLDTC.cbl:L63-L70}. It is a Language Environment control byte and is
     * deliberately not claimed to be printable text in any code page.
     */
    private static final int CASE_SEVERITY_CONTROL_BYTE = 0x59;

    /**
     * The EBCDIC code page used to decode {@code FACILITY-ID}. Named explicitly because this class is
     * the one place in the tier where bytes and characters meet, and the platform default would decode
     * the same three bytes as unrelated characters.
     */
    private static final String EBCDIC_CHARSET_NAME = "IBM037";

    /**
     * What bytes 6-8 spell once decoded from EBCDIC: the Language Environment facility identifier. Its
     * constancy across all eight non-zero tokens is the evidence that the byte layout above is right.
     */
    private static final String FACILITY_IDENTIFIER = "CEE";

    // ------------------------------------------------------------------------------------------------
    // The nine declared tokens, transcribed verbatim from app/cbl/CSUTLDTC.cbl:L62-L70.
    //
    // Each constant is named for what its token MEANS rather than for the COBOL condition name, whose
    // citation is carried in the documentation instead. That is a deliberate choice for the first one:
    // the source calls it FC-INVALID-DATE and it means the opposite, so a Java constant echoing that
    // name would propagate the trap into new code. The citation keeps the misnomer traceable.
    // ------------------------------------------------------------------------------------------------

    /**
     * {@code 88 FC-INVALID-DATE VALUE X'0000000000000000'}, {@code app/cbl/CSUTLDTC.cbl:L62}.
     *
     * <p><strong>Named for an invalid date, and it means success.</strong> All zeros is the
     * {@code CEEDAYS} all-clear feedback, and {@code :L129-L130} maps this condition to
     * {@code Date is valid}.
     */
    private static final String TOKEN_SUCCESS = "0000000000000000";

    /** {@code 88 FC-INSUFFICIENT-DATA}, {@code app/cbl/CSUTLDTC.cbl:L63}. */
    private static final String TOKEN_INSUFFICIENT_DATA = "000309CB59C3C5C5";

    /** {@code 88 FC-BAD-DATE-VALUE}, {@code app/cbl/CSUTLDTC.cbl:L64}. */
    private static final String TOKEN_BAD_DATE_VALUE = "000309CC59C3C5C5";

    /** {@code 88 FC-INVALID-ERA}, {@code app/cbl/CSUTLDTC.cbl:L65}. */
    private static final String TOKEN_INVALID_ERA = "000309CD59C3C5C5";

    /** {@code 88 FC-UNSUPP-RANGE}, {@code app/cbl/CSUTLDTC.cbl:L66}. */
    private static final String TOKEN_UNSUPPORTED_RANGE = "000309D159C3C5C5";

    /** {@code 88 FC-INVALID-MONTH}, {@code app/cbl/CSUTLDTC.cbl:L67}. */
    private static final String TOKEN_INVALID_MONTH = "000309D559C3C5C5";

    /** {@code 88 FC-BAD-PIC-STRING}, {@code app/cbl/CSUTLDTC.cbl:L68}. */
    private static final String TOKEN_BAD_PICTURE_STRING = "000309D659C3C5C5";

    /** {@code 88 FC-NON-NUMERIC-DATA}, {@code app/cbl/CSUTLDTC.cbl:L69}. */
    private static final String TOKEN_NON_NUMERIC_DATA = "000309D859C3C5C5";

    /** {@code 88 FC-YEAR-IN-ERA-ZERO}, {@code app/cbl/CSUTLDTC.cbl:L70}. */
    private static final String TOKEN_YEAR_IN_ERA_ZERO = "000309D959C3C5C5";

    /** All nine tokens in declaration order, {@code app/cbl/CSUTLDTC.cbl:L62-L70}. */
    private static final List<String> DECLARED_TOKENS = List.of(
            TOKEN_SUCCESS,
            TOKEN_INSUFFICIENT_DATA,
            TOKEN_BAD_DATE_VALUE,
            TOKEN_INVALID_ERA,
            TOKEN_UNSUPPORTED_RANGE,
            TOKEN_INVALID_MONTH,
            TOKEN_BAD_PICTURE_STRING,
            TOKEN_NON_NUMERIC_DATA,
            TOKEN_YEAR_IN_ERA_ZERO);

    /**
     * The eight tokens of {@code app/cbl/CSUTLDTC.cbl:L63-L70}, being every declared token except the
     * all-zero success token. Only these carry a control byte and a facility identifier.
     */
    private static final List<String> NON_ZERO_TOKENS = List.of(
            TOKEN_INSUFFICIENT_DATA,
            TOKEN_BAD_DATE_VALUE,
            TOKEN_INVALID_ERA,
            TOKEN_UNSUPPORTED_RANGE,
            TOKEN_INVALID_MONTH,
            TOKEN_BAD_PICTURE_STRING,
            TOKEN_NON_NUMERIC_DATA,
            TOKEN_YEAR_IN_ERA_ZERO);

    /** The number of {@code 88}-level conditions declared, {@code app/cbl/CSUTLDTC.cbl:L62-L70}. */
    private static final int DECLARED_CONDITION_COUNT = 9;

    /**
     * Arms of the {@code EVALUATE TRUE} opened at {@code app/cbl/CSUTLDTC.cbl:L128} and closed at
     * {@code :L149}: nine {@code WHEN} arms for the declared conditions plus the {@code WHEN OTHER} of
     * {@code :L147-L148}.
     */
    private static final int EVALUATE_BRANCH_COUNT = 10;

    /**
     * Message numbers that fall inside the declared span 2507 through 2521 yet are <strong>absent</strong>
     * from the nine tokens. The set is not a range, and treating it as one would silently accept seven
     * outcomes the source never declares. No rationale for the gaps exists in the repository.
     */
    private static final List<Integer> ABSENT_MESSAGE_NUMBERS =
            List.of(2510, 2511, 2512, 2514, 2515, 2516, 2519);

    // ------------------------------------------------------------------------------------------------
    // The ten result literals of the EVALUATE arms, app/cbl/CSUTLDTC.cbl:L130-L148.
    //
    // WS-RESULT is PIC X(15) at :L49, and the source records that width itself in two comment lines at
    // :L126-L127 - a reminder and a column ruler. Eight literals are written with explicit trailing
    // spaces to exactly fifteen characters; two are shorter and rely on COBOL's implicit right-padding
    // into the receiving field. All ten render as fifteen bytes. The trailing spaces below are
    // significant and are described in prose so that no formatter can eat the evidence.
    // ------------------------------------------------------------------------------------------------

    /** The declared width of {@code WS-RESULT}, {@code app/cbl/CSUTLDTC.cbl:L49}. */
    private static final int RESULT_LITERAL_WIDTH = 15;

    /**
     * {@code app/cbl/CSUTLDTC.cbl:L130}. Thirteen characters with no trailing spaces written, so two are
     * supplied implicitly by the move into {@code PIC X(15)}. This is the <em>success</em> text.
     */
    private static final String LITERAL_SUCCESS = "Date is valid";

    /**
     * {@code app/cbl/CSUTLDTC.cbl:L132}. Twelve characters with no trailing spaces written, so three are
     * supplied implicitly. The shortest of the ten.
     */
    private static final String LITERAL_INSUFFICIENT_DATA = "Insufficient";

    /** {@code app/cbl/CSUTLDTC.cbl:L134}. Exactly fifteen characters with no padding needed. */
    private static final String LITERAL_BAD_DATE_VALUE = "Datevalue error";

    /** {@code app/cbl/CSUTLDTC.cbl:L136}. Eleven characters plus four explicit trailing spaces. */
    private static final String LITERAL_INVALID_ERA = "Invalid Era    ";

    /** {@code app/cbl/CSUTLDTC.cbl:L138}. Thirteen characters plus two explicit trailing spaces. */
    private static final String LITERAL_UNSUPPORTED_RANGE = "Unsupp. Range  ";

    /** {@code app/cbl/CSUTLDTC.cbl:L140}. Thirteen characters plus two explicit trailing spaces. */
    private static final String LITERAL_INVALID_MONTH = "Invalid month  ";

    /** {@code app/cbl/CSUTLDTC.cbl:L142}. Fourteen characters plus one explicit trailing space. */
    private static final String LITERAL_BAD_PICTURE_STRING = "Bad Pic String ";

    /** {@code app/cbl/CSUTLDTC.cbl:L144}. Exactly fifteen characters with no padding needed. */
    private static final String LITERAL_NON_NUMERIC_DATA = "Nonnumeric data";

    /** {@code app/cbl/CSUTLDTC.cbl:L146}. Fourteen characters plus one explicit trailing space. */
    private static final String LITERAL_YEAR_IN_ERA_ZERO = "YearInEra is 0 ";

    /**
     * {@code app/cbl/CSUTLDTC.cbl:L148}, the {@code WHEN OTHER} arm. Exactly fifteen characters. Note
     * how close it reads to {@link #LITERAL_SUCCESS}: {@code Date is invalid} against
     * {@code Date is valid}, three characters apart.
     */
    private static final String LITERAL_WHEN_OTHER = "Date is invalid";

    /**
     * The eight literals written out to the full fifteen characters in the source, so that the receiving
     * move supplies no padding at all.
     */
    private static final List<String> EXPLICITLY_PADDED_LITERALS = List.of(
            LITERAL_BAD_DATE_VALUE,
            LITERAL_INVALID_ERA,
            LITERAL_UNSUPPORTED_RANGE,
            LITERAL_INVALID_MONTH,
            LITERAL_BAD_PICTURE_STRING,
            LITERAL_NON_NUMERIC_DATA,
            LITERAL_YEAR_IN_ERA_ZERO,
            LITERAL_WHEN_OTHER);

    /**
     * The two literals written shorter than the receiving field, whose trailing spaces exist only
     * because the move into {@code WS-RESULT PIC X(15)} supplies them.
     */
    private static final List<String> IMPLICITLY_PADDED_LITERALS =
            List.of(LITERAL_SUCCESS, LITERAL_INSUFFICIENT_DATA);

    /** Every result literal the source can emit: the nine condition arms plus {@code WHEN OTHER}. */
    private static final List<String> ALL_RESULT_LITERALS = List.of(
            LITERAL_SUCCESS,
            LITERAL_INSUFFICIENT_DATA,
            LITERAL_BAD_DATE_VALUE,
            LITERAL_INVALID_ERA,
            LITERAL_UNSUPPORTED_RANGE,
            LITERAL_INVALID_MONTH,
            LITERAL_BAD_PICTURE_STRING,
            LITERAL_NON_NUMERIC_DATA,
            LITERAL_YEAR_IN_ERA_ZERO,
            LITERAL_WHEN_OTHER);

    // ------------------------------------------------------------------------------------------------
    // The eighty-byte WS-MESSAGE, producer side, app/cbl/CSUTLDTC.cbl:L42-L57.
    //
    // Fifteen 02-level items are declared but only thirteen occupy storage: WS-SEVERITY-N at :L44 and
    // WS-MSG-NO-N at :L47 are REDEFINES of the two items above them and add no bytes. The thirteen
    // widths in declaration order are:
    //   :L43 WS-SEVERITY X(04)                   4
    //   :L45 FILLER      X(11) 'Mesg Code:'     11
    //   :L46 WS-MSG-NO   X(04)                   4
    //   :L48 FILLER      X(01) SPACE             1
    //   :L49 WS-RESULT   X(15)                  15
    //   :L50 FILLER      X(01) SPACE             1
    //   :L51 FILLER      X(09) 'TstDate:'        9
    //   :L52 WS-DATE     X(10) VALUE SPACES     10
    //   :L53 FILLER      X(01) SPACE             1
    //   :L54 FILLER      X(10) 'Mask used:'     10
    //   :L55 WS-DATE-FMT X(10)                  10
    //   :L56 FILLER      X(01) SPACE             1
    //   :L57 FILLER      X(03) SPACES            3
    // ------------------------------------------------------------------------------------------------

    /** The declared width of {@code LS-RESULT}, {@code app/cbl/CSUTLDTC.cbl:L86}. */
    private static final int MESSAGE_AREA_LENGTH = 80;

    /** The thirteen storage widths of {@code WS-MESSAGE}, {@code app/cbl/CSUTLDTC.cbl:L42-L57}. */
    private static final List<Integer> PRODUCER_FIELD_WIDTHS =
            List.of(4, 11, 4, 1, 15, 1, 9, 10, 1, 10, 10, 1, 3);

    /**
     * The same thirteen widths as declared by the third view of these bytes,
     * {@code app/cpy/CSUTLDWY.cpy:L60-L85}, where {@code WS-DATE-VALIDATION-RESULT} restates the
     * producer's layout at level {@code 20} instead of level {@code 02}. Held separately from
     * {@link #PRODUCER_FIELD_WIDTHS} on purpose: asserting that two independently transcribed lists are
     * equal is evidence, whereas comparing one list to itself is not.
     */
    private static final List<Integer> COPYBOOK_VIEW_FIELD_WIDTHS =
            List.of(4, 11, 4, 1, 15, 1, 9, 10, 1, 10, 10, 1, 3);

    /** The number of {@code REDEFINES} items in {@code WS-MESSAGE}: {@code :L44} and {@code :L47}. */
    private static final int PRODUCER_REDEFINES_COUNT = 2;

    /** {@code FILLER VALUE 'Mesg Code:'}, {@code app/cbl/CSUTLDTC.cbl:L45}: ten characters in {@code X(11)}. */
    private static final String MESG_CODE_LITERAL = "Mesg Code:";

    /** The field width the {@code Mesg Code:} literal is moved into, {@code :L45}. */
    private static final int MESG_CODE_FIELD_WIDTH = 11;

    /** {@code FILLER VALUE 'TstDate:'}, {@code app/cbl/CSUTLDTC.cbl:L51}: eight characters in {@code X(09)}. */
    private static final String TST_DATE_LITERAL = "TstDate:";

    /** The field width the {@code TstDate:} literal is moved into, {@code :L51}. */
    private static final int TST_DATE_FIELD_WIDTH = 9;

    /** {@code FILLER VALUE 'Mask used:'}, {@code app/cbl/CSUTLDTC.cbl:L54}: ten characters in {@code X(10)}. */
    private static final String MASK_USED_LITERAL = "Mask used:";

    /** The field width the {@code Mask used:} literal is moved into, {@code :L54}: an exact fit. */
    private static final int MASK_USED_FIELD_WIDTH = 10;

    /** Zero-based start of {@code WS-SEVERITY} in the rendered area, {@code app/cbl/CSUTLDTC.cbl:L43}. */
    private static final int SEVERITY_FIELD_START = 0;

    /** Zero-based start of the {@code Mesg Code:} filler, {@code app/cbl/CSUTLDTC.cbl:L45}. */
    private static final int MESG_CODE_FIELD_START = 4;

    /** Zero-based start of {@code WS-MSG-NO}, {@code app/cbl/CSUTLDTC.cbl:L46}. */
    private static final int MESSAGE_NUMBER_FIELD_START = 15;

    /** Zero-based start of {@code WS-RESULT}, {@code app/cbl/CSUTLDTC.cbl:L49}. */
    private static final int RESULT_FIELD_START = 20;

    /** Zero-based start of the {@code TstDate:} filler, {@code app/cbl/CSUTLDTC.cbl:L51}. */
    private static final int TST_DATE_FIELD_START = 36;

    /** Zero-based start of {@code WS-DATE}, {@code app/cbl/CSUTLDTC.cbl:L52}. */
    private static final int TESTED_DATE_FIELD_START = 45;

    /** Zero-based start of the {@code Mask used:} filler, {@code app/cbl/CSUTLDTC.cbl:L54}. */
    private static final int MASK_USED_FIELD_START = 56;

    /** Zero-based start of {@code WS-DATE-FMT}, {@code app/cbl/CSUTLDTC.cbl:L55}. */
    private static final int MASK_FIELD_START = 66;

    /** The declared width of the four-character severity and message-number display fields. */
    private static final int DISPLAY_HALFWORD_WIDTH = 4;

    // ------------------------------------------------------------------------------------------------
    // The same eighty bytes, caller side.
    //
    // app/cbl/CORPT00C.cbl:L129-L136 and app/cbl/COTRN02C.cbl:L62-L69 declare an identical parameter
    // block. Note the citation: the CORPT00C block ends at L136 and NOT at L137, which is blank; L138 is
    // COPY COCOM01Y. Verified by direct read.
    //   05 CSUTLDTC-DATE                 PIC X(10)     10
    //   05 CSUTLDTC-DATE-FORMAT          PIC X(10)     10
    //   05 CSUTLDTC-RESULT.
    //      10 CSUTLDTC-RESULT-SEV-CD     PIC X(04)      4
    //      10 FILLER                     PIC X(11)     11
    //      10 CSUTLDTC-RESULT-MSG-NUM    PIC X(04)      4
    //      10 CSUTLDTC-RESULT-MSG        PIC X(61)     61
    // ------------------------------------------------------------------------------------------------

    /** The caller's four-field view of the result area, {@code app/cbl/CORPT00C.cbl:L133-L136}. */
    private static final List<Integer> CALLER_RESULT_FIELD_WIDTHS = List.of(4, 11, 4, 61);

    /**
     * The producer's structured tail from COBOL byte 20 onward, {@code app/cbl/CSUTLDTC.cbl:L48-L57},
     * which is exactly what the caller sees through its single opaque {@code PIC X(61)} item.
     */
    private static final List<Integer> PRODUCER_TAIL_WIDTHS = List.of(1, 15, 1, 9, 10, 1, 10, 10, 1, 3);

    /** {@code CSUTLDTC-RESULT-MSG PIC X(61)}, {@code app/cbl/CORPT00C.cbl:L136}. */
    private static final int CALLER_MESSAGE_TAIL_WIDTH = 61;

    /**
     * The leading span in which the two views align field for field: a four-character severity, an
     * eleven-character filler and a four-character message number.
     */
    private static final int ALIGNED_PREFIX_WIDTH = 19;

    /** {@code 01 CSUTLDTC-PARM}, {@code app/cbl/CORPT00C.cbl:L129}: date, mask and the result area. */
    private static final int PARAMETER_BLOCK_LENGTH = 100;

    /**
     * The rendering the callers compare against for success:
     * {@code IF CSUTLDTC-RESULT-SEV-CD = '0000'} at {@code app/cbl/CORPT00C.cbl:L396} and at
     * {@code app/cbl/COTRN02C.cbl:L397}.
     */
    private static final String CALLER_SUCCESS_SEVERITY_CODE = "0000";

    /**
     * The one message number the callers single out and tolerate:
     * {@code IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'} at {@code app/cbl/CORPT00C.cbl:L399} and at
     * {@code app/cbl/COTRN02C.cbl:L400}. It is the unsupported-range outcome.
     */
    private static final String CALLER_TOLERATED_MESSAGE_NUMBER = "2513";

    // ------------------------------------------------------------------------------------------------
    // The varying-length argument groups and the preserved group move.
    //
    // :L25-L31 declares WS-DATE-TO-TEST as a two-byte binary length prefix followed by up to 256 text
    // bytes; :L33-L39 declares WS-DATE-FORMAT identically. That two-byte prefix is the direct cause of
    // the defect at :L122.
    // ------------------------------------------------------------------------------------------------

    /** {@code 02 Vstring-length PIC S9(4) BINARY}, {@code app/cbl/CSUTLDTC.cbl:L26} and {@code :L34}. */
    private static final int VSTRING_LENGTH_PREFIX_WIDTH = 2;

    /** The {@code OCCURS 0 TO 256 TIMES} ceiling on {@code Vstring-char}, {@code :L28-L31}. */
    private static final int VSTRING_TEXT_CAPACITY = 256;

    /**
     * Characters of the date that survive the {@code :L122} group move: ten receiving bytes less the two
     * consumed by the binary length prefix.
     */
    private static final int SURVIVING_DATE_CHARACTERS = 8;

    /**
     * The value {@code :L105-L106} unconditionally moves into {@code Vstring-length}, being
     * {@code LENGTH OF LS-DATE} for the {@code PIC X(10)} item of {@code :L84} - never a trimmed length,
     * and never the caller's actual argument width.
     */
    private static final int VSTRING_LENGTH_VALUE = 10;

    /** The high-order byte the binary length 10 contributes to the corrupted rendering. */
    private static final char LENGTH_PREFIX_HIGH_ORDER_BYTE = '\u0000';

    /** The low-order byte the binary length 10 contributes to the corrupted rendering. */
    private static final char LENGTH_PREFIX_LOW_ORDER_BYTE = '\n';

    // ------------------------------------------------------------------------------------------------
    // The two picture masks and the argument widths that evidence the copybook path's overread.
    // ------------------------------------------------------------------------------------------------

    /** {@code 10 WS-DATE-FORMAT PIC X(08) VALUE 'YYYYMMDD'}, {@code app/cpy/CSUTLDWY.cpy:L58-L59}. */
    private static final int UNDELIMITED_MASK_WIDTH = 8;

    /**
     * {@code 05 WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'}, {@code app/cbl/CORPT00C.cbl:L72} and
     * {@code app/cbl/COTRN02C.cbl:L60}.
     */
    private static final int DELIMITED_MASK_WIDTH = 10;

    /**
     * The width of {@code WS-EDIT-DATE-CCYYMMDD}, {@code app/cpy/CSUTLDWY.cpy:L4}: a group of
     * {@code CCYY} four, {@code MM} two and {@code DD} two. This is the argument
     * {@code app/cpy/CSUTLDPY.cpy:L294} passes into a {@code PIC X(10)} linkage item.
     */
    private static final int COMPACT_DATE_ARGUMENT_WIDTH = 8;

    /** The declared width of {@code LS-DATE}, {@code app/cbl/CSUTLDTC.cbl:L84}. */
    private static final int LINKAGE_DATE_WIDTH = 10;

    // ------------------------------------------------------------------------------------------------
    // Fixtures and helpers.
    // ------------------------------------------------------------------------------------------------

    /**
     * A fixed instant. This class asserts a rendering contract and needs no clock of its own, but
     * {@link DateValidationService} takes one by constructor injection for an unrelated future-date
     * guard. Fixing it keeps every outcome here reproducible; nothing reads the system clock.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2024-06-15T12:00:00Z"), ZoneOffset.UTC);

    /** A ten-character delimited date whose final two characters the {@code :L122} move destroys. */
    private static final String SAMPLE_DELIMITED_DATE = "2022-06-10";

    /** An eight-character undelimited date, the shape the copybook call path supplies. */
    private static final String SAMPLE_COMPACT_DATE = "20220610";

    /**
     * Builds the subject. A fresh instance per call, because the contract asserted here must not depend
     * on any accumulated state.
     *
     * @return a service bound to {@link #FIXED_CLOCK}
     */
    private static DateValidationService service() {
        return new DateValidationService(FIXED_CLOCK);
    }

    /**
     * Decodes one of the hexadecimal token literals into its eight bytes.
     *
     * @param tokenHex a sixteen-digit hexadecimal literal transcribed from
     * {@code app/cbl/CSUTLDTC.cbl:L62-L70}
     * @return the eight bytes of {@code FEEDBACK-TOKEN-VALUE}
     */
    private static byte[] tokenBytes(final String tokenHex) {
        return HexFormat.of().parseHex(tokenHex);
    }

    /**
     * Reads a {@code PIC S9(4) BINARY} halfword out of a token, big-endian as {@code IBM} hardware
     * stores it.
     *
     * @param tokenHex the token to read
     * @param offset the zero-based byte offset of the halfword
     * @return the halfword value
     */
    private static int halfword(final String tokenHex, final int offset) {
        final byte[] token = tokenBytes(tokenHex);
        return ((token[offset] & 0xFF) << 8) | (token[offset + 1] & 0xFF);
    }

    /**
     * Decodes {@code SEVERITY}, {@code app/cbl/CSUTLDTC.cbl:L72}, from a token's first two bytes.
     *
     * @param tokenHex the token to decode
     * @return the severity halfword
     */
    private static int decodedSeverity(final String tokenHex) {
        return halfword(tokenHex, SEVERITY_OFFSET);
    }

    /**
     * Decodes {@code MSG-NO}, {@code app/cbl/CSUTLDTC.cbl:L73}, from a token's third and fourth bytes.
     *
     * @param tokenHex the token to decode
     * @return the message-number halfword
     */
    private static int decodedMessageNumber(final String tokenHex) {
        return halfword(tokenHex, MESSAGE_NUMBER_OFFSET);
    }

    /**
     * Turns a token into the feedback code the service reasons about, going through the decoded
     * halfwords rather than through {@link FeedbackCondition}. This is what makes the assertions in this
     * class independent evidence: the expected values come out of the token bytes.
     *
     * @param tokenHex the token to decode
     * @return a feedback code carrying the decoded severity and message number
     */
    private static FeedbackCode feedbackCodeOf(final String tokenHex) {
        return new FeedbackCode(decodedSeverity(tokenHex), decodedMessageNumber(tokenHex));
    }

    /**
     * Right-pads a value to a COBOL alphanumeric field width, which is what a {@code MOVE} to
     * {@code PIC X(n)} does when the sending item is shorter.
     *
     * @param value the value to pad
     * @param width the receiving field width
     * @return the value padded with trailing spaces to exactly {@code width} characters
     */
    private static String rightPadded(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Extracts one field from a rendered result area by its declared offset and width.
     *
     * @param area the rendered eighty-character area
     * @param start the zero-based field offset
     * @param width the field width
     * @return the field's characters
     */
    private static String field(final String area, final int start, final int width) {
        return area.substring(start, start + width);
    }

    /**
     * Renders a string with every non-printable character escaped, for use in assertion descriptions.
     * Without this the two length-prefix bytes the {@code :L122} move writes would appear as nothing at
     * all in a failure report, which is precisely the evidence a reader needs.
     *
     * @param value the string to render
     * @return the string with characters outside printable ASCII shown as escapes
     */
    private static String visible(final String value) {
        final StringBuilder rendered = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (character < ' ' || character > '~') {
                rendered.append(String.format(Locale.ROOT, "\\u%04X", (int) character));
            } else {
                rendered.append(character);
            }
        }
        return rendered.toString();
    }

    /**
     * The nine declared tokens with the source names, locators and result literals they map to. Severity
     * and message number are deliberately absent: every test using this provider decodes them from the
     * token itself.
     *
     * @return one argument row per declared condition, in the declaration order of
     * {@code app/cbl/CSUTLDTC.cbl:L62-L70}
     */
    static Stream<Arguments> declaredConditionRows() {
        return Stream.of(
                Arguments.of(TOKEN_SUCCESS, "FC-INVALID-DATE", "L62", "L130", LITERAL_SUCCESS),
                Arguments.of(TOKEN_INSUFFICIENT_DATA, "FC-INSUFFICIENT-DATA", "L63", "L132",
                        LITERAL_INSUFFICIENT_DATA),
                Arguments.of(TOKEN_BAD_DATE_VALUE, "FC-BAD-DATE-VALUE", "L64", "L134",
                        LITERAL_BAD_DATE_VALUE),
                Arguments.of(TOKEN_INVALID_ERA, "FC-INVALID-ERA", "L65", "L136", LITERAL_INVALID_ERA),
                Arguments.of(TOKEN_UNSUPPORTED_RANGE, "FC-UNSUPP-RANGE", "L66", "L138",
                        LITERAL_UNSUPPORTED_RANGE),
                Arguments.of(TOKEN_INVALID_MONTH, "FC-INVALID-MONTH", "L67", "L140",
                        LITERAL_INVALID_MONTH),
                Arguments.of(TOKEN_BAD_PICTURE_STRING, "FC-BAD-PIC-STRING", "L68", "L142",
                        LITERAL_BAD_PICTURE_STRING),
                Arguments.of(TOKEN_NON_NUMERIC_DATA, "FC-NON-NUMERIC-DATA", "L69", "L144",
                        LITERAL_NON_NUMERIC_DATA),
                Arguments.of(TOKEN_YEAR_IN_ERA_ZERO, "FC-YEAR-IN-ERA-ZERO", "L70", "L146",
                        LITERAL_YEAR_IN_ERA_ZERO));
    }

    /**
     * The halfword arithmetic of each token, stated in both hexadecimal and decimal so that neither
     * representation can drift from the other unnoticed. The hexadecimal column is what
     * {@code app/cbl/CSUTLDTC.cbl:L62-L70} writes; the decimal column is what
     * {@code app/cbl/CORPT00C.cbl:L399} and {@code app/cbl/COTRN02C.cbl:L400} compare against as text.
     *
     * @return one row per declared condition: token, hexadecimal severity, hexadecimal message number,
     * decimal severity, decimal message number
     */
    static Stream<Arguments> tokenHalfwordArithmetic() {
        return Stream.of(
                Arguments.of(TOKEN_SUCCESS, 0x0000, 0x0000, 0, 0),
                Arguments.of(TOKEN_INSUFFICIENT_DATA, 0x0003, 0x09CB, 3, 2507),
                Arguments.of(TOKEN_BAD_DATE_VALUE, 0x0003, 0x09CC, 3, 2508),
                Arguments.of(TOKEN_INVALID_ERA, 0x0003, 0x09CD, 3, 2509),
                Arguments.of(TOKEN_UNSUPPORTED_RANGE, 0x0003, 0x09D1, 3, 2513),
                Arguments.of(TOKEN_INVALID_MONTH, 0x0003, 0x09D5, 3, 2517),
                Arguments.of(TOKEN_BAD_PICTURE_STRING, 0x0003, 0x09D6, 3, 2518),
                Arguments.of(TOKEN_NON_NUMERIC_DATA, 0x0003, 0x09D8, 3, 2520),
                Arguments.of(TOKEN_YEAR_IN_ERA_ZERO, 0x0003, 0x09D9, 3, 2521));
    }

    /**
     * The eight-byte group the nine condition names are declared on, and the subordinate layout that
     * makes their hexadecimal values decodable at all.
     */
    @Nested
    @DisplayName("FEEDBACK-TOKEN-VALUE is an eight-byte group with a four-part layout")
    class FeedbackTokenGroupGeometry {

        @Test
        @DisplayName("the nine 88-levels sit on a group, not on a scalar, and it is eight bytes wide")
        void theSubordinateWidthsAccountForEveryByteOfTheGroup() {
            final int declaredWidth =
                    SEVERITY_WIDTH + MESSAGE_NUMBER_WIDTH + CASE_SEVERITY_CONTROL_WIDTH + FACILITY_ID_WIDTH;

            assertThat(declaredWidth)
                    .as("app/cbl/CSUTLDTC.cbl:L72-L79 - SEVERITY 2 + MSG-NO 2 + CASE-SEV-CTL 1 "
                            + "+ FACILITY-ID 3 must account for every byte of FEEDBACK-TOKEN-VALUE at :L61")
                    .isEqualTo(TOKEN_GROUP_LENGTH)
                    .isEqualTo(8);

            assertThat(SEVERITY_OFFSET + SEVERITY_WIDTH)
                    .as("app/cbl/CSUTLDTC.cbl:L73 MSG-NO must begin where :L72 SEVERITY ends")
                    .isEqualTo(MESSAGE_NUMBER_OFFSET);
            assertThat(MESSAGE_NUMBER_OFFSET + MESSAGE_NUMBER_WIDTH)
                    .as("app/cbl/CSUTLDTC.cbl:L78 CASE-SEV-CTL must begin where :L73 MSG-NO ends")
                    .isEqualTo(CASE_SEVERITY_CONTROL_OFFSET);
            assertThat(CASE_SEVERITY_CONTROL_OFFSET + CASE_SEVERITY_CONTROL_WIDTH)
                    .as("app/cbl/CSUTLDTC.cbl:L79 FACILITY-ID must begin where :L78 CASE-SEV-CTL ends")
                    .isEqualTo(FACILITY_ID_OFFSET);
            assertThat(FACILITY_ID_OFFSET + FACILITY_ID_WIDTH)
                    .as("app/cbl/CSUTLDTC.cbl:L79 FACILITY-ID must end exactly at the group boundary, "
                            + "leaving no gap and no overlap")
                    .isEqualTo(TOKEN_GROUP_LENGTH);
        }

        @Test
        @DisplayName("every declared token is exactly eight bytes")
        void everyDeclaredTokenOccupiesTheWholeGroupAndNoMore() {
            assertThat(DECLARED_TOKENS)
                    .as("app/cbl/CSUTLDTC.cbl:L62-L70 declares nine condition names")
                    .hasSize(DECLARED_CONDITION_COUNT);

            for (final String token : DECLARED_TOKENS) {
                assertThat(token.length())
                    .as("app/cbl/CSUTLDTC.cbl:L62-L70 - token %s must be sixteen hexadecimal digits, "
                            + "being the eight bytes of FEEDBACK-TOKEN-VALUE at :L61", token)
                        .isEqualTo(TOKEN_GROUP_LENGTH * 2);
                assertThat(tokenBytes(token))
                        .as("app/cbl/CSUTLDTC.cbl:L61 - token %s must decode to exactly %d bytes",
                                token, TOKEN_GROUP_LENGTH)
                        .hasSize(TOKEN_GROUP_LENGTH);
            }
        }

        @Test
        @DisplayName("every non-zero token carries the same case-severity control byte X'59'")
        void everyNonZeroTokenCarriesTheSameCaseSeverityControlByte() {
            assertThat(NON_ZERO_TOKENS)
                    .as("app/cbl/CSUTLDTC.cbl:L63-L70 declares eight non-zero tokens")
                    .hasSize(DECLARED_CONDITION_COUNT - 1);

            for (final String token : NON_ZERO_TOKENS) {
                final byte[] bytes = tokenBytes(token);
                assertThat(bytes[CASE_SEVERITY_CONTROL_OFFSET] & 0xFF)
                        .as("app/cbl/CSUTLDTC.cbl:L78 CASE-SEV-CTL - byte 5 of token %s must be X'59'; "
                                + "its constancy across :L63-L70 is the evidence the layout is right", token)
                        .isEqualTo(CASE_SEVERITY_CONTROL_BYTE);
            }
        }

        @Test
        @DisplayName("every non-zero token carries the EBCDIC facility identifier CEE in bytes six to eight")
        void everyNonZeroTokenCarriesTheLanguageEnvironmentFacilityIdentifier() {
            assertThat(Charset.isSupported(EBCDIC_CHARSET_NAME))
                    .as("this test decodes app/cbl/CSUTLDTC.cbl:L79 FACILITY-ID from EBCDIC, so the "
                            + "runtime must provide the %s code page; a trimmed runtime image may not",
                            EBCDIC_CHARSET_NAME)
                    .isTrue();
            final Charset ebcdic = Charset.forName(EBCDIC_CHARSET_NAME);

            for (final String token : NON_ZERO_TOKENS) {
                final byte[] bytes = tokenBytes(token);
                final byte[] facility = new byte[FACILITY_ID_WIDTH];
                System.arraycopy(bytes, FACILITY_ID_OFFSET, facility, 0, FACILITY_ID_WIDTH);

                assertThat(facility)
                        .as("app/cbl/CSUTLDTC.cbl:L79 FACILITY-ID - bytes 6-8 of token %s must be "
                                + "X'C3C5C5'", token)
                        .containsExactly(0xC3, 0xC5, 0xC5);
                assertThat(new String(facility, ebcdic))
                        .as("app/cbl/CSUTLDTC.cbl:L79 - X'C3C5C5' decoded from %s must spell the "
                                + "Language Environment facility identifier", EBCDIC_CHARSET_NAME)
                        .isEqualTo(FACILITY_IDENTIFIER);
            }
        }

        @Test
        @DisplayName("the facility identifier is EBCDIC and not ASCII, which is why the charset is named")
        void theFacilityIdentifierWouldBeUnreadableUnderAsciiSoTheCharsetIsNotIncidental() {
            final byte[] asciiBytes = FACILITY_IDENTIFIER.getBytes(StandardCharsets.US_ASCII);

            assertThat(asciiBytes)
                    .as("US-ASCII encodes CEE as 0x43 0x45 0x45, which is nothing like the "
                            + "X'C3C5C5' of app/cbl/CSUTLDTC.cbl:L79 - so decoding the token with the "
                            + "platform default charset would silently produce the wrong text")
                    .containsExactly(0x43, 0x45, 0x45)
                    .isNotEqualTo(new byte[] {(byte) 0xC3, (byte) 0xC5, (byte) 0xC5});
        }

        @Test
        @DisplayName("the success token is all zero bytes and so carries neither control byte nor facility id")
        void theSuccessTokenIsAllZeroBytesThroughout() {
            final byte[] bytes = tokenBytes(TOKEN_SUCCESS);

            assertThat(bytes)
                    .as("app/cbl/CSUTLDTC.cbl:L62 - X'0000000000000000' is all zeros in every one of "
                            + "its eight bytes, which is why it is the CEEDAYS all-clear feedback")
                    .containsOnly(0);
            assertThat(bytes[CASE_SEVERITY_CONTROL_OFFSET] & 0xFF)
                    .as("app/cbl/CSUTLDTC.cbl:L62 versus :L78 - the success token carries no "
                            + "case-severity control byte, unlike the eight of :L63-L70")
                    .isNotEqualTo(CASE_SEVERITY_CONTROL_BYTE);
        }

        @Test
        @DisplayName("CASE-2-CONDITION-ID redefines the same four bytes as CASE-1-CONDITION-ID")
        void theSecondCaseRedefinitionOverlaysTheSameFourBytes() {
            for (final String token : DECLARED_TOKENS) {
                final int severity = halfword(token, SEVERITY_OFFSET);
                final int messageNumber = halfword(token, MESSAGE_NUMBER_OFFSET);

                assertThat(halfword(token, SEVERITY_OFFSET))
                        .as("app/cbl/CSUTLDTC.cbl:L74-L76 - CLASS-CODE redefines CASE-1-CONDITION-ID, "
                                + "so it reads the very bytes :L72 SEVERITY reads in token %s", token)
                        .isEqualTo(severity);
                assertThat(halfword(token, MESSAGE_NUMBER_OFFSET))
                        .as("app/cbl/CSUTLDTC.cbl:L77 - CAUSE-CODE reads the bytes :L73 MSG-NO reads "
                                + "in token %s", token)
                        .isEqualTo(messageNumber);
            }

            assertThat(SEVERITY_WIDTH + MESSAGE_NUMBER_WIDTH)
                    .as("app/cbl/CSUTLDTC.cbl:L74-L77 - the redefinition covers four bytes and adds "
                            + "none, so the group stays eight bytes wide")
                    .isEqualTo(CASE_SEVERITY_CONTROL_OFFSET);
        }
    }

    /**
     * The Blocker. {@code FC-INVALID-DATE} is named for failure and means success, and this group exists
     * for no other purpose than to pin that.
     */
    @Nested
    @DisplayName("BLOCKER: the token named FC-INVALID-DATE is the success token")
    class MisnamedSuccessTokenInversionTrap {

        @Test
        @DisplayName("the all-zero token decodes to severity zero and message number zero")
        void theTokenNamedForAnInvalidDateDecodesToTheAllClearHalfwords() {
            assertThat(decodedSeverity(TOKEN_SUCCESS))
                    .as("app/cbl/CSUTLDTC.cbl:L62 FC-INVALID-DATE VALUE X'0000000000000000' - "
                            + "SEVERITY at :L72 decodes to zero")
                    .isZero();
            assertThat(decodedMessageNumber(TOKEN_SUCCESS))
                    .as("app/cbl/CSUTLDTC.cbl:L62 - MSG-NO at :L73 decodes to zero")
                    .isZero();
        }

        @Test
        @DisplayName("severity zero and message number zero render 'Date is valid', not an error")
        void severityZeroAndMessageZeroRenderTheDateIsValidLiteral() {
            final FeedbackCode allZeros = feedbackCodeOf(TOKEN_SUCCESS);

            assertThat(allZeros.condition())
                    .as("app/cbl/CSUTLDTC.cbl:L129 WHEN FC-INVALID-DATE must resolve to a declared "
                            + "condition, not to the WHEN OTHER arm of :L147")
                    .isPresent();
            assertThat(allZeros.resultText())
                    .as("app/cbl/CSUTLDTC.cbl:L130 MOVE 'Date is valid' TO WS-RESULT - the token named "
                            + "FC-INVALID-DATE at :L62 emits the SUCCESS text. Any implementation that "
                            + "trusts the identifier inverts the entire logic and still compiles")
                    .isEqualTo(LITERAL_SUCCESS)
                    .isNotEqualTo(LITERAL_WHEN_OTHER);
        }

        @Test
        @DisplayName("the all-zero token means the date is VALID, end to end through the service")
        void anAllZeroFeedbackTokenMeansTheDateIsValid() {
            final DateValidationResult accepted =
                    service().validate(SAMPLE_DELIMITED_DATE, DateValidationService.MASK_YYYY_MM_DD);

            assertThat(accepted.returnCode())
                    .as("app/cbl/CSUTLDTC.cbl:L98 MOVE WS-SEVERITY-N TO RETURN-CODE - a valid date "
                            + "yields the all-zero token of :L62 and therefore return code zero")
                    .isZero();
            assertThat(accepted.valid())
                    .as("app/cbl/CSUTLDTC.cbl:L62 and :L130 read together - the all-zero token is the "
                            + "ACCEPT verdict despite being named FC-INVALID-DATE")
                    .isTrue();
            assertThat(accepted.resultText())
                    .as("app/cbl/CSUTLDTC.cbl:L130, rendered into WS-RESULT PIC X(15) at :L49")
                    .isEqualTo(rightPadded(LITERAL_SUCCESS, RESULT_LITERAL_WIDTH));
        }

        @Test
        @DisplayName("exactly one of the nine tokens is severity zero, so the inversion cannot be hedged")
        void onlyTheMisnamedTokenDecodesToSeverityZero() {
            final long severityZeroTokens =
                    DECLARED_TOKENS.stream().filter(token -> decodedSeverity(token) == 0).count();

            assertThat(severityZeroTokens)
                    .as("app/cbl/CSUTLDTC.cbl:L62-L70 - only FC-INVALID-DATE at :L62 decodes to "
                            + "severity zero; the other eight are all severity three")
                    .isEqualTo(1);
            assertThat(DECLARED_TOKENS.getFirst())
                    .as("app/cbl/CSUTLDTC.cbl:L62 - the misnamed success token is declared first")
                    .isEqualTo(TOKEN_SUCCESS);
        }

        @Test
        @DisplayName("three independent call sites in the frozen corpus corroborate the correct reading")
        void theCallersThemselvesTreatTheAllZeroOutcomeAsSuccess() {
            final DateValidationResult accepted =
                    service().validate(SAMPLE_DELIMITED_DATE, DateValidationService.MASK_YYYY_MM_DD);

            assertThat(accepted.severityCode())
                    .as("app/cbl/CORPT00C.cbl:L396 and app/cbl/COTRN02C.cbl:L397 both read "
                            + "IF CSUTLDTC-RESULT-SEV-CD = '0000' then CONTINUE. Two programs "
                            + "independently treat the all-zero rendering as SUCCESS")
                    .isEqualTo(CALLER_SUCCESS_SEVERITY_CODE);
            assertThat(Integer.parseInt(accepted.severityCode()))
                    .as("app/cpy/CSUTLDPY.cpy:L298 IF WS-SEVERITY-N = 0 then CONTINUE - a third call "
                            + "site, testing the numeric redefinition rather than the text")
                    .isZero();
        }

        @Test
        @DisplayName("this class names its own constant for what the token means, keeping the misnomer cited")
        void theConstantInThisClassIsNamedForMeaningWhileTheSourceNameStaysTraceable() {
            assertThat(feedbackCodeOf(TOKEN_SUCCESS).condition())
                    .as("app/cbl/CSUTLDTC.cbl:L62 - the source condition name FC-INVALID-DATE is "
                            + "preserved in this file's documentation so the misnomer stays traceable, "
                            + "while the constant itself is named TOKEN_SUCCESS so it cannot mislead")
                    .contains(FeedbackCondition.FC_INVALID_DATE);
            assertThat(FeedbackCondition.FC_INVALID_DATE.resultText())
                    .as("app/cbl/CSUTLDTC.cbl:L130 - the production constant keeps the source spelling, "
                            + "which is why its meaning must be asserted rather than assumed")
                    .isEqualTo(LITERAL_SUCCESS);
        }
    }

    /**
     * The whole decoded table: token to severity, message number and result literal, plus the
     * {@code WHEN OTHER} arm and the non-contiguity of the message-number set.
     */
    @Nested
    @DisplayName("the nine tokens decode to their declared outcomes, and the tenth arm catches the rest")
    class DecodedFeedbackTable {

        @ParameterizedTest(name = "{1} at :{2} renders its literal from :{3}")
        @MethodSource("com.cardemo.unit.validation.LanguageEnvironmentDateContractTest"
                + "#declaredConditionRows")
        @DisplayName("each token decodes to the condition whose literal the source moves for it")
        void eachTokenDecodesToItsDeclaredConditionAndLiteral(final String tokenHex,
                                                             final String sourceConditionName,
                                                             final String tokenLocator,
                                                             final String literalLocator,
                                                             final String expectedLiteral) {
            final FeedbackCode decoded = feedbackCodeOf(tokenHex);

            assertThat(decoded.condition())
                    .as("app/cbl/CSUTLDTC.cbl:%s - 88 %s VALUE X'%s' must resolve to a declared "
                            + "condition and not fall through to the WHEN OTHER arm of :L147",
                            tokenLocator, sourceConditionName, tokenHex)
                    .isPresent();
            assertThat(decoded.condition().orElseThrow().severity())
                    .as("app/cbl/CSUTLDTC.cbl:L72 SEVERITY decoded from bytes 1-2 of %s at :%s",
                            sourceConditionName, tokenLocator)
                    .isEqualTo(decodedSeverity(tokenHex));
            assertThat(decoded.condition().orElseThrow().messageNumber())
                    .as("app/cbl/CSUTLDTC.cbl:L73 MSG-NO decoded from bytes 3-4 of %s at :%s",
                            sourceConditionName, tokenLocator)
                    .isEqualTo(decodedMessageNumber(tokenHex));
            assertThat(decoded.resultText())
                    .as("app/cbl/CSUTLDTC.cbl:%s - the EVALUATE arm for %s moves its literal into "
                            + "WS-RESULT", literalLocator, sourceConditionName)
                    .isEqualTo(expectedLiteral);
        }

        @ParameterizedTest(name = "token {0} = severity {3}, message {4}")
        @MethodSource("com.cardemo.unit.validation.LanguageEnvironmentDateContractTest"
                + "#tokenHalfwordArithmetic")
        @DisplayName("the halfword arithmetic holds in hexadecimal and in decimal alike")
        void theHalfwordArithmeticIsStatedInBothRepresentations(final String tokenHex,
                                                               final int hexadecimalSeverity,
                                                               final int hexadecimalMessageNumber,
                                                               final int decimalSeverity,
                                                               final int decimalMessageNumber) {
            assertThat(decodedSeverity(tokenHex))
                    .as("app/cbl/CSUTLDTC.cbl:L72 - bytes 1-2 of X'%s' as a big-endian halfword",
                            tokenHex)
                    .isEqualTo(hexadecimalSeverity)
                    .isEqualTo(decimalSeverity);
            assertThat(decodedMessageNumber(tokenHex))
                    .as("app/cbl/CSUTLDTC.cbl:L73 - bytes 3-4 of X'%s' as a big-endian halfword; the "
                            + "decimal form is what app/cbl/CORPT00C.cbl:L399 compares as text",
                            tokenHex)
                    .isEqualTo(hexadecimalMessageNumber)
                    .isEqualTo(decimalMessageNumber);
        }

        @Test
        @DisplayName("every declared severity is zero or three and nothing else")
        void everyDeclaredSeverityIsZeroOrThree() {
            for (final String token : DECLARED_TOKENS) {
                assertThat(decodedSeverity(token))
                        .as("app/cbl/CSUTLDTC.cbl:L62-L70 - token %s must decode to the all-clear "
                                + "severity zero or the diagnostic severity three", token)
                        .isIn(0, 3);
            }
        }

        @Test
        @DisplayName("the message numbers are exactly the nine declared values")
        void theMessageNumbersAreExactlyTheNineDeclaredValues() {
            final List<Integer> decoded =
                    DECLARED_TOKENS.stream().map(
                            LanguageEnvironmentDateContractTest::decodedMessageNumber).toList();

            assertThat(decoded)
                    .as("app/cbl/CSUTLDTC.cbl:L62-L70 - the nine message numbers decoded from bytes "
                            + "3-4 of each token, in declaration order")
                    .containsExactly(0, 2507, 2508, 2509, 2513, 2517, 2518, 2520, 2521)
                    .doesNotHaveDuplicates()
                    .hasSize(DECLARED_CONDITION_COUNT);
        }

        @Test
        @DisplayName("the message-number set is NOT contiguous: seven values inside the span are absent")
        void theMessageNumberSetIsNotAContiguousRange() {
            final List<Integer> declared =
                    DECLARED_TOKENS.stream().map(
                            LanguageEnvironmentDateContractTest::decodedMessageNumber).toList();

            assertThat(declared)
                    .as("app/cbl/CSUTLDTC.cbl:L62-L70 - 2510 to 2512, 2514 to 2516 and 2519 lie inside "
                            + "the declared span 2507 to 2521 yet appear in no token. Modelling the set "
                            + "as a range would accept seven outcomes the source never declares")
                    .doesNotContainAnyElementsOf(ABSENT_MESSAGE_NUMBERS);
            assertThat(ABSENT_MESSAGE_NUMBERS)
                    .as("the span 2507 to 2521 holds fifteen values; eight are declared, so seven are "
                            + "absent")
                    .hasSize(7);

            for (final int absent : ABSENT_MESSAGE_NUMBERS) {
                assertThat(new FeedbackCode(3, absent).condition())
                        .as("app/cbl/CSUTLDTC.cbl:L147 WHEN OTHER - severity three with message "
                                + "number %d matches none of the nine tokens of :L62-L70", absent)
                        .isEmpty();
                assertThat(new FeedbackCode(3, absent).resultText())
                        .as("app/cbl/CSUTLDTC.cbl:L148 MOVE 'Date is invalid' TO WS-RESULT - the "
                                + "fallback for message number %d", absent)
                        .isEqualTo(LITERAL_WHEN_OTHER);
            }
        }

        @Test
        @DisplayName("the WHEN OTHER arm renders 'Date is invalid', three characters from the success text")
        void theWhenOtherArmRendersDateIsInvalid() {
            final FeedbackCode unrecognised = new FeedbackCode(3, 2510);

            assertThat(unrecognised.condition())
                    .as("app/cbl/CSUTLDTC.cbl:L147 - a token outside :L62-L70 resolves to no condition")
                    .isEmpty();
            assertThat(unrecognised.resultText())
                    .as("app/cbl/CSUTLDTC.cbl:L148 - the tenth and final arm of the EVALUATE opened "
                            + "at :L128")
                    .isEqualTo(LITERAL_WHEN_OTHER)
                    .isEqualTo(DateValidationService.UNRECOGNISED_RESULT_TEXT);
            assertThat(unrecognised.resultText())
                    .as("app/cbl/CSUTLDTC.cbl:L130 versus :L148 - 'Date is valid' and 'Date is "
                            + "invalid' differ by three characters, which is why the inversion trap of "
                            + ":L62 is so easy to miss on a casual read")
                    .isNotEqualTo(LITERAL_SUCCESS);
        }

        @Test
        @DisplayName("the EVALUATE has ten branches: nine conditions plus WHEN OTHER")
        void theEvaluateConstructHasTenBranches() {
            assertThat(FeedbackCondition.values())
                    .as("app/cbl/CSUTLDTC.cbl:L62-L70 declares nine conditions, and :L129-L146 gives "
                            + "each of them one WHEN arm")
                    .hasSize(DECLARED_CONDITION_COUNT);
            assertThat(DECLARED_CONDITION_COUNT + 1)
                    .as("app/cbl/CSUTLDTC.cbl:L128 EVALUATE TRUE through :L149 END-EVALUATE - nine "
                            + "WHEN arms plus the WHEN OTHER of :L147-L148")
                    .isEqualTo(EVALUATE_BRANCH_COUNT);
            assertThat(ALL_RESULT_LITERALS)
                    .as("app/cbl/CSUTLDTC.cbl:L130-L148 - one distinct literal per branch")
                    .hasSize(EVALUATE_BRANCH_COUNT)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("a severity that no token declares also falls through to WHEN OTHER")
        void anUndeclaredSeverityAlsoFallsThroughToWhenOther() {
            for (final int severity : List.of(1, 2, 4, 8, 12)) {
                assertThat(new FeedbackCode(severity, 2507).condition())
                        .as("app/cbl/CSUTLDTC.cbl:L63 pairs message number 2507 with severity three "
                                + "only, so severity %d with that message number matches no token",
                                severity)
                        .isEmpty();
                assertThat(new FeedbackCode(severity, 2507).resultText())
                        .as("app/cbl/CSUTLDTC.cbl:L148 - the fallback text for severity %d", severity)
                        .isEqualTo(LITERAL_WHEN_OTHER);
            }
        }
    }

    /**
     * The fifteen-character result field, and the padding inconsistency in how the source writes the ten
     * literals that flow into it.
     */
    @Nested
    @DisplayName("WS-RESULT is fifteen characters, and eight of ten literals say so explicitly")
    class ResultLiteralWidths {

        @Test
        @DisplayName("every literal renders as exactly fifteen bytes")
        void everyLiteralRendersAsExactlyFifteenBytes() {
            assertThat(ALL_RESULT_LITERALS)
                    .as("app/cbl/CSUTLDTC.cbl:L130-L148 declares ten literals across the ten branches")
                    .hasSize(EVALUATE_BRANCH_COUNT);

            for (final String literal : ALL_RESULT_LITERALS) {
                assertThat(literal.length())
                        .as("app/cbl/CSUTLDTC.cbl:L49 WS-RESULT PIC X(15) - literal [%s] must fit "
                                + "within the receiving field", literal)
                        .isLessThanOrEqualTo(RESULT_LITERAL_WIDTH);
                assertThat(rightPadded(literal, RESULT_LITERAL_WIDTH))
                        .as("app/cbl/CSUTLDTC.cbl:L49 - literal [%s] renders as fifteen bytes once "
                                + "moved into WS-RESULT", literal)
                        .hasSize(RESULT_LITERAL_WIDTH);
            }
        }

        @Test
        @DisplayName("eight literals carry explicit trailing spaces to the full fifteen characters")
        void eightLiteralsAreWrittenOutToTheFullFieldWidth() {
            assertThat(EXPLICITLY_PADDED_LITERALS)
                    .as("app/cbl/CSUTLDTC.cbl:L134, :L136, :L138, :L140, :L142, :L144, :L146 and :L148 "
                            + "write their literals out to fifteen characters, several of them with "
                            + "trailing spaces inside the quotes")
                    .hasSize(8);

            for (final String literal : EXPLICITLY_PADDED_LITERALS) {
                assertThat(literal)
                        .as("app/cbl/CSUTLDTC.cbl:L49 - literal [%s] is written at the full width, so "
                                + "the move into WS-RESULT supplies no padding at all", literal)
                        .hasSize(RESULT_LITERAL_WIDTH);
                assertThat(rightPadded(literal, RESULT_LITERAL_WIDTH))
                        .as("app/cbl/CSUTLDTC.cbl:L49 - padding literal [%s] must be a no-op", literal)
                        .isEqualTo(literal);
            }
        }

        @Test
        @DisplayName("two literals are shorter and rely on implicit right-padding into PIC X(15)")
        void twoLiteralsRelyOnImplicitPadding() {
            assertThat(IMPLICITLY_PADDED_LITERALS)
                    .as("app/cbl/CSUTLDTC.cbl:L130 and :L132 - only these two literals are written "
                            + "shorter than the receiving field")
                    .hasSize(2)
                    .containsExactly(LITERAL_SUCCESS, LITERAL_INSUFFICIENT_DATA);

            assertThat(LITERAL_SUCCESS.length())
                    .as("app/cbl/CSUTLDTC.cbl:L130 'Date is valid' is thirteen characters, so two "
                            + "trailing spaces are supplied implicitly by the move into :L49")
                    .isEqualTo(13);
            assertThat(LITERAL_INSUFFICIENT_DATA.length())
                    .as("app/cbl/CSUTLDTC.cbl:L132 'Insufficient' is twelve characters, so three "
                            + "trailing spaces are supplied implicitly - the shortest of the ten")
                    .isEqualTo(12);

            for (final String literal : IMPLICITLY_PADDED_LITERALS) {
                assertThat(literal)
                        .as("app/cbl/CSUTLDTC.cbl:L130 and :L132 - neither literal ends in a space in "
                                + "the source, which is exactly why the field width matters")
                        .doesNotEndWith(" ")
                        .hasSizeLessThan(RESULT_LITERAL_WIDTH);
            }
        }

        @Test
        @DisplayName("the explicit and implicit sets together account for all ten literals, disjointly")
        void thePaddingSplitPartitionsTheTenLiteralsExactly() {
            assertThat(EXPLICITLY_PADDED_LITERALS.size() + IMPLICITLY_PADDED_LITERALS.size())
                    .as("app/cbl/CSUTLDTC.cbl:L130-L148 - eight explicitly padded plus two implicitly "
                            + "padded must be all ten branches")
                    .isEqualTo(EVALUATE_BRANCH_COUNT);
            assertThat(EXPLICITLY_PADDED_LITERALS)
                    .as("app/cbl/CSUTLDTC.cbl:L130-L148 - the two sets must not overlap")
                    .doesNotContainAnyElementsOf(IMPLICITLY_PADDED_LITERALS);
            assertThat(ALL_RESULT_LITERALS)
                    .as("app/cbl/CSUTLDTC.cbl:L130-L148 - every literal belongs to exactly one set")
                    .containsAll(EXPLICITLY_PADDED_LITERALS)
                    .containsAll(IMPLICITLY_PADDED_LITERALS);
        }

        @ParameterizedTest(name = "the rendered field for [{0}] is the literal right-padded")
        @MethodSource("com.cardemo.unit.validation.LanguageEnvironmentDateContractTest"
                + "#declaredConditionRows")
        @DisplayName("the rendered field is always the declared literal right-padded with spaces")
        void theRenderedFieldEqualsTheDeclaredLiteralRightPadded(final String tokenHex,
                                                                 final String sourceConditionName,
                                                                 final String tokenLocator,
                                                                 final String literalLocator,
                                                                 final String expectedLiteral) {
            final String rendered = rightPadded(expectedLiteral, RESULT_LITERAL_WIDTH);

            assertThat(rendered)
                    .as("app/cbl/CSUTLDTC.cbl:%s moves the literal for %s, declared at :%s as X'%s', "
                            + "into WS-RESULT PIC X(15) at :L49",
                            literalLocator, sourceConditionName, tokenLocator, tokenHex)
                    .hasSize(RESULT_LITERAL_WIDTH)
                    .startsWith(expectedLiteral);
            assertThat(rendered.strip())
                    .as("app/cbl/CSUTLDTC.cbl:%s - padding adds trailing spaces and never alters the "
                            + "text of %s", literalLocator, sourceConditionName)
                    .isEqualTo(expectedLiteral.strip());
        }
    }

    /**
     * The producer's own view of the eighty bytes: thirteen storage fields, two redefinitions that add
     * none, and three embedded literals whose padding is part of the rendered output.
     */
    @Nested
    @DisplayName("WS-MESSAGE is thirteen storage fields totalling eighty bytes")
    class ProducerMessageAreaGeometry {

        @Test
        @DisplayName("the thirteen declared widths sum to eighty")
        void theThirteenStorageFieldsSumToEightyBytes() {
            assertThat(PRODUCER_FIELD_WIDTHS)
                    .as("app/cbl/CSUTLDTC.cbl:L42-L57 declares fifteen 02-level items, of which "
                            + "thirteen occupy storage")
                    .hasSize(13)
                    .containsExactly(4, 11, 4, 1, 15, 1, 9, 10, 1, 10, 10, 1, 3);

            final int total = PRODUCER_FIELD_WIDTHS.stream().mapToInt(Integer::intValue).sum();

            assertThat(total)
                    .as("app/cbl/CSUTLDTC.cbl:L42-L57 - 4 + 11 + 4 + 1 + 15 + 1 + 9 + 10 + 1 + 10 + 10 "
                            + "+ 1 + 3 must reconcile to the LS-RESULT PIC X(80) of :L86")
                    .isEqualTo(MESSAGE_AREA_LENGTH)
                    .isEqualTo(80);
        }

        @Test
        @DisplayName("the two REDEFINES items add no bytes, which is why fifteen declarations are thirteen fields")
        void theTwoRedefinitionsAddNoBytes() {
            assertThat(PRODUCER_REDEFINES_COUNT)
                    .as("app/cbl/CSUTLDTC.cbl:L44 WS-SEVERITY-N REDEFINES WS-SEVERITY and :L47 "
                            + "WS-MSG-NO-N REDEFINES WS-MSG-NO")
                    .isEqualTo(2);
            assertThat(PRODUCER_FIELD_WIDTHS.size() + PRODUCER_REDEFINES_COUNT)
                    .as("app/cbl/CSUTLDTC.cbl:L42-L57 - thirteen storage fields plus two redefinitions "
                            + "is the fifteen 02-level declarations actually written")
                    .isEqualTo(15);

            final DateValidationResult rejected =
                    service().validate("2022-13-01", DateValidationService.MASK_YYYY_MM_DD);

            assertThat(field(rejected.result(), SEVERITY_FIELD_START, DISPLAY_HALFWORD_WIDTH))
                    .as("app/cbl/CSUTLDTC.cbl:L123 MOVE SEVERITY OF FEEDBACK-CODE TO WS-SEVERITY-N - "
                            + "the numeric redefinition of :L44 is the write target, so the four "
                            + "character display field of :L43 receives a zero-padded rendering")
                    .isEqualTo("0003");
            assertThat(field(rejected.result(), MESSAGE_NUMBER_FIELD_START, DISPLAY_HALFWORD_WIDTH))
                    .as("app/cbl/CSUTLDTC.cbl:L124 MOVE MSG-NO OF FEEDBACK-CODE TO WS-MSG-NO-N - the "
                            + "numeric redefinition of :L47 writing into the :L46 display field")
                    .isEqualTo("2517");
        }

        @Test
        @DisplayName("the three embedded literals render with exactly their declared padding")
        void theThreeEmbeddedLiteralsAreRenderedWithTheirDeclaredPadding() {
            final DateValidationResult result =
                    service().validate(SAMPLE_DELIMITED_DATE, DateValidationService.MASK_YYYY_MM_DD);
            final String area = result.result();

            assertThat(MESG_CODE_LITERAL.length())
                    .as("app/cbl/CSUTLDTC.cbl:L45 FILLER PIC X(11) VALUE 'Mesg Code:' - ten characters "
                            + "in an eleven-byte field, so one trailing space is supplied")
                    .isEqualTo(MESG_CODE_FIELD_WIDTH - 1);
            assertThat(field(area, MESG_CODE_FIELD_START, MESG_CODE_FIELD_WIDTH))
                    .as("app/cbl/CSUTLDTC.cbl:L45 - the rendered filler, one trailing space included")
                    .isEqualTo("Mesg Code: ")
                    .isEqualTo(rightPadded(MESG_CODE_LITERAL, MESG_CODE_FIELD_WIDTH));

            assertThat(TST_DATE_LITERAL.length())
                    .as("app/cbl/CSUTLDTC.cbl:L51 FILLER PIC X(09) VALUE 'TstDate:' - eight characters "
                            + "in a nine-byte field, so one trailing space is supplied")
                    .isEqualTo(TST_DATE_FIELD_WIDTH - 1);
            assertThat(field(area, TST_DATE_FIELD_START, TST_DATE_FIELD_WIDTH))
                    .as("app/cbl/CSUTLDTC.cbl:L51 - the rendered filler, one trailing space included")
                    .isEqualTo("TstDate: ")
                    .isEqualTo(rightPadded(TST_DATE_LITERAL, TST_DATE_FIELD_WIDTH));

            assertThat(MASK_USED_LITERAL.length())
                    .as("app/cbl/CSUTLDTC.cbl:L54 FILLER PIC X(10) VALUE 'Mask used:' - ten characters "
                            + "in a ten-byte field, an exact fit with no padding at all")
                    .isEqualTo(MASK_USED_FIELD_WIDTH);
            assertThat(field(area, MASK_USED_FIELD_START, MASK_USED_FIELD_WIDTH))
                    .as("app/cbl/CSUTLDTC.cbl:L54 - the only one of the three literals that needs no "
                            + "padding, and the only one whose rendering equals its literal")
                    .isEqualTo(MASK_USED_LITERAL)
                    .doesNotEndWith(" ");
        }

        @Test
        @DisplayName("every field of a rendered area sits at its declared offset")
        void everyFieldOfARenderedAreaSitsAtItsDeclaredOffset() {
            final DateValidationResult result =
                    service().validate(SAMPLE_DELIMITED_DATE, DateValidationService.MASK_YYYY_MM_DD);
            final String area = result.result();

            assertThat(area)
                    .as("app/cbl/CSUTLDTC.cbl:L97 MOVE WS-MESSAGE TO LS-RESULT, into the PIC X(80) of "
                            + ":L86. Rendered area with non-printables escaped: [%s]", visible(area))
                    .hasSize(MESSAGE_AREA_LENGTH);

            int offset = 0;
            for (final int width : PRODUCER_FIELD_WIDTHS) {
                assertThat(offset + width)
                        .as("app/cbl/CSUTLDTC.cbl:L42-L57 - the field beginning at offset %d and %d "
                                + "bytes wide must stay inside the eighty-byte area", offset, width)
                        .isLessThanOrEqualTo(MESSAGE_AREA_LENGTH);
                assertThat(field(area, offset, width))
                        .as("app/cbl/CSUTLDTC.cbl:L42-L57 - the field at offset %d must be exactly %d "
                                + "characters", offset, width)
                        .hasSize(width);
                offset += width;
            }
            assertThat(offset)
                    .as("app/cbl/CSUTLDTC.cbl:L42-L57 - walking the thirteen declared widths must land "
                            + "exactly on the area boundary, with no byte unaccounted for")
                    .isEqualTo(MESSAGE_AREA_LENGTH);

            assertThat(field(area, SEVERITY_FIELD_START, DISPLAY_HALFWORD_WIDTH))
                    .as("app/cbl/CSUTLDTC.cbl:L43 WS-SEVERITY PIC X(04) at COBOL bytes 1-4")
                    .isEqualTo(result.severityCode());
            assertThat(field(area, MESSAGE_NUMBER_FIELD_START, DISPLAY_HALFWORD_WIDTH))
                    .as("app/cbl/CSUTLDTC.cbl:L46 WS-MSG-NO PIC X(04) at COBOL bytes 16-19")
                    .isEqualTo(result.messageNumber());
            assertThat(field(area, RESULT_FIELD_START, RESULT_LITERAL_WIDTH))
                    .as("app/cbl/CSUTLDTC.cbl:L49 WS-RESULT PIC X(15) at COBOL bytes 21-35")
                    .isEqualTo(result.resultText());
        }

        @Test
        @DisplayName("the three single-byte fillers and the trailing three-byte filler are all spaces")
        void theFillerBytesAreSpaces() {
            final DateValidationResult result =
                    service().validate(SAMPLE_DELIMITED_DATE, DateValidationService.MASK_YYYY_MM_DD);
            final String area = result.result();

            assertThat(field(area, RESULT_FIELD_START - 1, 1))
                    .as("app/cbl/CSUTLDTC.cbl:L48 FILLER PIC X(01) VALUE SPACE, separating the message "
                            + "number from the result text")
                    .isEqualTo(" ");
            assertThat(field(area, RESULT_FIELD_START + RESULT_LITERAL_WIDTH, 1))
                    .as("app/cbl/CSUTLDTC.cbl:L50 FILLER PIC X(01) VALUE SPACE")
                    .isEqualTo(" ");
            assertThat(field(area, TESTED_DATE_FIELD_START + LINKAGE_DATE_WIDTH, 1))
                    .as("app/cbl/CSUTLDTC.cbl:L53 FILLER PIC X(01) VALUE SPACE")
                    .isEqualTo(" ");
            assertThat(field(area, MASK_FIELD_START + DELIMITED_MASK_WIDTH, 1))
                    .as("app/cbl/CSUTLDTC.cbl:L56 FILLER PIC X(01) VALUE SPACE")
                    .isEqualTo(" ");
            assertThat(area.substring(MESSAGE_AREA_LENGTH - 3))
                    .as("app/cbl/CSUTLDTC.cbl:L57 FILLER PIC X(03) VALUE SPACES, the three bytes that "
                            + "carry the area to exactly eighty")
                    .isEqualTo("   ");
        }

        @Test
        @DisplayName("the area is eighty characters whatever the outcome")
        void theRenderedAreaIsAlwaysEightyCharacters() {
            final DateValidationService service = service();
            final List<String> dates =
                    List.of(SAMPLE_DELIMITED_DATE, "2022-13-01", "2022/06/10", "0000-06-10",
                            "1582-10-14", "          ", "", "2022-02-30");

            for (final String date : dates) {
                final DateValidationResult result =
                        service.validate(date, DateValidationService.MASK_YYYY_MM_DD);

                assertThat(result.result())
                        .as("app/cbl/CSUTLDTC.cbl:L86 LS-RESULT PIC X(80) - the area is a fixed width "
                                + "field, so it is eighty bytes for the accepted and the rejected alike. "
                                + "Date [%s] rendered [%s]", visible(date), visible(result.result()))
                        .hasSize(MESSAGE_AREA_LENGTH);
            }
        }
    }

    /**
     * The same eighty bytes seen from the two calling programs and from the work-area copybook, which is
     * what proves the geometry from both ends rather than from the producer alone.
     */
    @Nested
    @DisplayName("the caller's four-field view reconciles with the producer's thirteen")
    class CallerSideViewReconciliation {

        @Test
        @DisplayName("the caller's four fields also sum to eighty")
        void theCallerViewOfTheResultSumsToEightyBytes() {
            assertThat(CALLER_RESULT_FIELD_WIDTHS)
                    .as("app/cbl/CORPT00C.cbl:L133-L136 and app/cbl/COTRN02C.cbl:L66-L69 - a severity, "
                            + "an opaque filler, a message number and one opaque tail")
                    .hasSize(4)
                    .containsExactly(4, 11, 4, 61);

            final int total = CALLER_RESULT_FIELD_WIDTHS.stream().mapToInt(Integer::intValue).sum();

            assertThat(total)
                    .as("app/cbl/CORPT00C.cbl:L133-L136 - 4 + 11 + 4 + 61 must reconcile to the same "
                            + "eighty bytes the producer builds at app/cbl/CSUTLDTC.cbl:L42-L57")
                    .isEqualTo(MESSAGE_AREA_LENGTH)
                    .isEqualTo(80);
        }

        @Test
        @DisplayName("the caller's opaque PIC X(61) tail is the producer's structured tail from byte twenty")
        void theCallerOpaqueTailIsTheProducerStructuredTailFromByteTwenty() {
            final int tailTotal = PRODUCER_TAIL_WIDTHS.stream().mapToInt(Integer::intValue).sum();

            assertThat(PRODUCER_TAIL_WIDTHS)
                    .as("app/cbl/CSUTLDTC.cbl:L48-L57 - the ten producer fields from COBOL byte 20 "
                            + "onward: a space, the result text, a space, the TstDate: literal, the "
                            + "date, a space, the Mask used: literal, the mask, a space and three "
                            + "trailing spaces")
                    .hasSize(10)
                    .containsExactly(1, 15, 1, 9, 10, 1, 10, 10, 1, 3);
            assertThat(tailTotal)
                    .as("app/cbl/CORPT00C.cbl:L136 CSUTLDTC-RESULT-MSG PIC X(61) - the caller's single "
                            + "opaque item is EXACTLY the producer's ten-field tail: "
                            + "1 + 15 + 1 + 9 + 10 + 1 + 10 + 10 + 1 + 3")
                    .isEqualTo(CALLER_MESSAGE_TAIL_WIDTH)
                    .isEqualTo(61);
            assertThat(MESSAGE_AREA_LENGTH - ALIGNED_PREFIX_WIDTH)
                    .as("app/cbl/CSUTLDTC.cbl:L42-L57 against app/cbl/CORPT00C.cbl:L133-L136 - eighty "
                            + "bytes less the nineteen-byte aligned prefix leaves the sixty-one-byte "
                            + "tail")
                    .isEqualTo(CALLER_MESSAGE_TAIL_WIDTH);

            final DateValidationResult result =
                    service().validate(SAMPLE_DELIMITED_DATE, DateValidationService.MASK_YYYY_MM_DD);

            assertThat(result.messageText())
                    .as("app/cbl/CORPT00C.cbl:L136 - the caller's flattened view of the tail, read out "
                            + "of a real rendering")
                    .hasSize(CALLER_MESSAGE_TAIL_WIDTH)
                    .isEqualTo(result.result().substring(ALIGNED_PREFIX_WIDTH));
        }

        @Test
        @DisplayName("the first nineteen bytes align field for field across both views")
        void theFirstNineteenBytesAlignFieldForFieldAcrossBothViews() {
            assertThat(CALLER_RESULT_FIELD_WIDTHS.subList(0, 3))
                    .as("app/cbl/CORPT00C.cbl:L133-L135 - a four-byte severity, an eleven-byte filler "
                            + "and a four-byte message number")
                    .containsExactly(4, 11, 4)
                    .isEqualTo(PRODUCER_FIELD_WIDTHS.subList(0, 3));

            final int alignedPrefix =
                    CALLER_RESULT_FIELD_WIDTHS.subList(0, 3).stream().mapToInt(Integer::intValue).sum();

            assertThat(alignedPrefix)
                    .as("app/cbl/CSUTLDTC.cbl:L43, :L45 and :L46 against app/cbl/CORPT00C.cbl:L133, "
                            + ":L134 and :L135 - the two views agree field for field over nineteen "
                            + "bytes before the caller's view goes opaque")
                    .isEqualTo(ALIGNED_PREFIX_WIDTH)
                    .isEqualTo(19);

            final DateValidationResult result =
                    service().validate(SAMPLE_DELIMITED_DATE, DateValidationService.MASK_YYYY_MM_DD);

            assertThat(result.severityCode())
                    .as("app/cbl/CORPT00C.cbl:L133 CSUTLDTC-RESULT-SEV-CD PIC X(04) reads the same "
                            + "bytes as app/cbl/CSUTLDTC.cbl:L43 WS-SEVERITY")
                    .isEqualTo(field(result.result(), 0, DISPLAY_HALFWORD_WIDTH));
            assertThat(result.messageNumber())
                    .as("app/cbl/CORPT00C.cbl:L135 CSUTLDTC-RESULT-MSG-NUM PIC X(04) reads the same "
                            + "bytes as app/cbl/CSUTLDTC.cbl:L46 WS-MSG-NO, after the eleven-byte "
                            + "filler both views declare")
                    .isEqualTo(field(result.result(), ALIGNED_PREFIX_WIDTH - DISPLAY_HALFWORD_WIDTH,
                            DISPLAY_HALFWORD_WIDTH));
        }

        @Test
        @DisplayName("the whole parameter block is one hundred bytes")
        void theWholeParameterBlockIsOneHundredBytes() {
            final int block = DELIMITED_MASK_WIDTH + DELIMITED_MASK_WIDTH + MESSAGE_AREA_LENGTH;

            assertThat(block)
                    .as("app/cbl/CORPT00C.cbl:L129-L136 - CSUTLDTC-DATE PIC X(10) plus "
                            + "CSUTLDTC-DATE-FORMAT PIC X(10) plus the eighty-byte CSUTLDTC-RESULT")
                    .isEqualTo(PARAMETER_BLOCK_LENGTH)
                    .isEqualTo(100);
            assertThat(DateValidationService.LS_DATE_LENGTH
                    + DateValidationService.LS_DATE_FORMAT_LENGTH
                    + DateValidationService.LS_RESULT_LENGTH)
                    .as("app/cbl/CSUTLDTC.cbl:L84-L86 - the three LINKAGE items the "
                            + "PROCEDURE DIVISION USING of :L88 receives must total the same hundred "
                            + "bytes the callers hand over")
                    .isEqualTo(PARAMETER_BLOCK_LENGTH);
        }

        @Test
        @DisplayName("both calling programs declare an identical parameter block")
        void theTwoCallersDeclareTheSameParameterBlock() {
            assertThat(CALLER_RESULT_FIELD_WIDTHS)
                    .as("app/cbl/CORPT00C.cbl:L129-L136 - verified by direct read; note the block ends "
                            + "at L136 and NOT at L137, which is blank, and L138 is COPY COCOM01Y. "
                            + "app/cbl/COTRN02C.cbl:L62-L69 declares it field for field identically, "
                            + "also verified by direct read")
                    .containsExactly(4, 11, 4, 61);

            final DateValidationResult result =
                    service().validate("2022-06-10", DateValidationService.MASK_YYYY_MM_DD);

            assertThat(result.severityCode())
                    .as("app/cbl/CORPT00C.cbl:L392 and app/cbl/COTRN02C.cbl:L393 are the two call sites "
                            + "that read this field, and app/cbl/CORPT00C.cbl:L412 and "
                            + "app/cbl/COTRN02C.cbl:L413 the second pair. All four expect the same "
                            + "four-character rendering")
                    .hasSize(DISPLAY_HALFWORD_WIDTH)
                    .isEqualTo(CALLER_SUCCESS_SEVERITY_CODE);
        }

        @Test
        @DisplayName("the message number the callers tolerate renders as the text they compare against")
        void theToleratedMessageNumberRendersAsTheTextTheCallersCompare() {
            final DateValidationResult outOfRange =
                    service().validate("1582-10-14", DateValidationService.MASK_YYYY_MM_DD);

            assertThat(outOfRange.messageNumber())
                    .as("app/cbl/CORPT00C.cbl:L399 and app/cbl/COTRN02C.cbl:L400 both read "
                            + "IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'. The value they single out is "
                            + "the unsupported-range outcome of app/cbl/CSUTLDTC.cbl:L66, whose token "
                            + "X'000309D159C3C5C5' decodes to message number %d",
                            decodedMessageNumber(TOKEN_UNSUPPORTED_RANGE))
                    .isEqualTo(CALLER_TOLERATED_MESSAGE_NUMBER);
            assertThat(Integer.parseInt(outOfRange.messageNumber()))
                    .as("app/cbl/CSUTLDTC.cbl:L66 - the rendered text and the decoded halfword must "
                            + "denote the same number")
                    .isEqualTo(decodedMessageNumber(TOKEN_UNSUPPORTED_RANGE));
            assertThat(outOfRange.severityCode())
                    .as("app/cbl/CORPT00C.cbl:L396-L399 - the tolerance test is only reached because "
                            + "the severity is NOT '0000', so this outcome must fail the success guard")
                    .isNotEqualTo(CALLER_SUCCESS_SEVERITY_CODE);
        }

        @Test
        @DisplayName("the copybook's third view of the same bytes is byte-compatible with the producer")
        void theCopybookThirdViewIsByteCompatibleWithTheProducer() {
            assertThat(COPYBOOK_VIEW_FIELD_WIDTHS)
                    .as("app/cpy/CSUTLDWY.cpy:L60-L85 WS-DATE-VALIDATION-RESULT restates the producer's "
                            + "layout at level 20 instead of level 02, and must be byte-compatible with "
                            + "app/cbl/CSUTLDTC.cbl:L42-L57 field for field")
                    .isEqualTo(PRODUCER_FIELD_WIDTHS)
                    .hasSize(13);

            final int total = COPYBOOK_VIEW_FIELD_WIDTHS.stream().mapToInt(Integer::intValue).sum();

            assertThat(total)
                    .as("app/cpy/CSUTLDWY.cpy:L60-L85 - the third view must total the same eighty bytes, "
                            + "which is what lets app/cpy/CSUTLDPY.cpy:L296 pass it as the third "
                            + "argument to a PIC X(80) linkage item. Two divergences from the producer "
                            + "are cosmetic only: :L68 spells its picture clause Pic 9(4) in mixed case, "
                            + "and :L76 declares WS-DATE without the VALUE SPACES of the producer's "
                            + ":L52 - benign because :L290 issues INITIALIZE first")
                    .isEqualTo(MESSAGE_AREA_LENGTH);
        }

        @Test
        @DisplayName("all three views agree, so the eighty bytes are proven from three independent sides")
        void allThreeViewsAgreeOnTheSameEightyBytes() {
            final int producer = PRODUCER_FIELD_WIDTHS.stream().mapToInt(Integer::intValue).sum();
            final int caller = CALLER_RESULT_FIELD_WIDTHS.stream().mapToInt(Integer::intValue).sum();
            final int copybook = COPYBOOK_VIEW_FIELD_WIDTHS.stream().mapToInt(Integer::intValue).sum();

            assertThat(List.of(producer, caller, copybook))
                    .as("app/cbl/CSUTLDTC.cbl:L42-L57 at level 02, app/cbl/CORPT00C.cbl:L133-L136 at "
                            + "level 10 and app/cpy/CSUTLDWY.cpy:L60-L85 at level 20 are three "
                            + "independently written descriptions of one storage area. Agreement across "
                            + "all three is the evidence; agreement with one of them alone would not be")
                    .containsExactly(MESSAGE_AREA_LENGTH, MESSAGE_AREA_LENGTH, MESSAGE_AREA_LENGTH);
            assertThat(DateValidationService.LS_RESULT_LENGTH)
                    .as("the Java replacement must adopt the same width as all three COBOL views")
                    .isEqualTo(MESSAGE_AREA_LENGTH);
            assertThat(DateValidationService.MESSAGE_TEXT_LENGTH)
                    .as("app/cbl/CORPT00C.cbl:L136 - the Java replacement must also carry the caller's "
                            + "sixty-one-byte tail width, or the caller's view could not be reproduced")
                    .isEqualTo(CALLER_MESSAGE_TAIL_WIDTH);
        }
    }

    /**
     * The preserved legacy defect at {@code app/cbl/CSUTLDTC.cbl:L122}, and the asymmetry that makes it
     * hit one rendered field and not the other. Severity High.
     *
     * <p>These assertions are the tracking reference for the defect. They exist so that the corruption
     * cannot be silently "tidied up": any change to the production rendering breaks this group loudly and
     * on purpose.</p>
     */
    @Nested
    @DisplayName("HIGH: L122 corrupts the rendered TstDate: field and leaves Mask used: clean")
    class GroupMoveCorruptionAndItsAsymmetry {

        @Test
        @DisplayName("the varying-string groups are a two-byte length prefix ahead of up to 256 text bytes")
        void theVaryingStringGroupsCarryATwoByteLengthPrefix() {
            assertThat(VSTRING_LENGTH_PREFIX_WIDTH)
                    .as("app/cbl/CSUTLDTC.cbl:L26 and :L34 - Vstring-length PIC S9(4) BINARY occupies a "
                            + "two-byte halfword, and that prefix is the direct cause of the corruption "
                            + "asserted below")
                    .isEqualTo(2);
            assertThat(VSTRING_TEXT_CAPACITY)
                    .as("app/cbl/CSUTLDTC.cbl:L28-L31 - Vstring-char PIC X OCCURS 0 TO 256 TIMES "
                            + "DEPENDING ON Vstring-length, the CEEDAYS variable-string convention")
                    .isEqualTo(256);
            assertThat(VSTRING_LENGTH_PREFIX_WIDTH + SURVIVING_DATE_CHARACTERS)
                    .as("app/cbl/CSUTLDTC.cbl:L122 - a two-byte prefix plus eight surviving date "
                            + "characters is exactly the ten bytes of WS-DATE PIC X(10) at :L52, which "
                            + "is why the last two date characters have nowhere left to go")
                    .isEqualTo(LINKAGE_DATE_WIDTH)
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("the length prefix is LENGTH OF LS-DATE, unconditionally ten, whatever the argument holds")
        void theLengthPrefixIsAlwaysTenRegardlessOfTheArgument() {
            final DateValidationService service = service();
            final List<String> dates = List.of("2022-06-10", "1999-12-31", "          ", "2022-13-01");

            for (final String date : dates) {
                final String tested = field(service.validate(date, DateValidationService.MASK_YYYY_MM_DD)
                        .result(), TESTED_DATE_FIELD_START, LINKAGE_DATE_WIDTH);

                assertThat(tested.charAt(0))
                        .as("app/cbl/CSUTLDTC.cbl:L105-L106 MOVE LENGTH OF LS-DATE TO VSTRING-LENGTH - "
                                + "the high-order byte of the halfword for date [%s]", visible(date))
                        .isEqualTo(LENGTH_PREFIX_HIGH_ORDER_BYTE);
                assertThat(tested.charAt(1))
                        .as("app/cbl/CSUTLDTC.cbl:L105-L106 - the low-order byte, binary ten, for date "
                                + "[%s]", visible(date))
                        .isEqualTo(LENGTH_PREFIX_LOW_ORDER_BYTE);
                assertThat((tested.charAt(0) << 8) + tested.charAt(1))
                        .as("app/cbl/CSUTLDTC.cbl:L105-L106 - the prefix is LENGTH OF LS-DATE, which is "
                                + "the ten of the PIC X(10) at :L84 and never the caller's real "
                                + "argument width. Date [%s]", visible(date))
                        .isEqualTo(VSTRING_LENGTH_VALUE)
                        .isEqualTo(10);
            }
        }

        @Test
        @DisplayName("HIGH: the rendered TstDate: field carries the prefix and loses two characters")
        void theRenderedTestedDateCarriesTheLengthPrefixAndLosesTwoCharacters() {
            final DateValidationResult result =
                    service().validate(SAMPLE_DELIMITED_DATE, DateValidationService.MASK_YYYY_MM_DD);
            final String tested =
                    field(result.result(), TESTED_DATE_FIELD_START, LINKAGE_DATE_WIDTH);

            assertThat(tested)
                    .as("app/cbl/CSUTLDTC.cbl:L122 MOVE WS-DATE-TO-TEST TO WS-DATE - the source moves "
                            + "the WHOLE varying group of :L25, so the two-byte binary length prefix of "
                            + ":L26 lands in the first two bytes of WS-DATE PIC X(10). Rendered as "
                            + "[%s]", visible(tested))
                    .hasSize(LINKAGE_DATE_WIDTH);

            assertThat(tested.substring(0, VSTRING_LENGTH_PREFIX_WIDTH))
                    .as("app/cbl/CSUTLDTC.cbl:L122 - bytes 1-2 of the rendered field are the binary "
                            + "length ten, NOT date characters")
                    .isEqualTo(String.valueOf(LENGTH_PREFIX_HIGH_ORDER_BYTE)
                            + LENGTH_PREFIX_LOW_ORDER_BYTE);

            assertThat(tested.substring(VSTRING_LENGTH_PREFIX_WIDTH))
                    .as("app/cbl/CSUTLDTC.cbl:L122 - bytes 3-10 are the first eight characters of the "
                            + "date, and only the first eight")
                    .hasSize(SURVIVING_DATE_CHARACTERS)
                    .isEqualTo(SAMPLE_DELIMITED_DATE.substring(0, SURVIVING_DATE_CHARACTERS))
                    .isEqualTo("2022-06-");

            assertThat(tested)
                    .as("app/cbl/CSUTLDTC.cbl:L122 - the two final characters of [%s] are LOST from the "
                            + "rendering, so the clean date never appears in the eighty-byte area. "
                            + "PRESERVED DEFECT: do not repair the production rendering to satisfy a "
                            + "reader's expectation here",
                            SAMPLE_DELIMITED_DATE)
                    .doesNotContain(SAMPLE_DELIMITED_DATE)
                    .doesNotEndWith(SAMPLE_DELIMITED_DATE.substring(SURVIVING_DATE_CHARACTERS));
            assertThat(result.result())
                    .as("app/cbl/CSUTLDTC.cbl:L122 - and the clean date appears nowhere in the whole "
                            + "eighty bytes either. Rendered area [%s]", visible(result.result()))
                    .doesNotContain(SAMPLE_DELIMITED_DATE);
        }

        @Test
        @DisplayName("HIGH: the asymmetry - only WS-DATE is re-corrupted, WS-DATE-FMT renders clean")
        void onlyTheTestedDateIsRecorruptedAndTheMaskRendersClean() {
            final DateValidationResult result =
                    service().validate(SAMPLE_DELIMITED_DATE, DateValidationService.MASK_YYYY_MM_DD);
            final String area = result.result();
            final String tested = field(area, TESTED_DATE_FIELD_START, LINKAGE_DATE_WIDTH);
            final String mask = field(area, MASK_FIELD_START, DELIMITED_MASK_WIDTH);

            assertThat(tested.charAt(0))
                    .as("app/cbl/CSUTLDTC.cbl:L122 - WS-DATE IS the target of a post-CALL group move, "
                            + "so its rendering opens with a binary byte")
                    .isEqualTo(LENGTH_PREFIX_HIGH_ORDER_BYTE);

            assertThat(mask)
                    .as("app/cbl/CSUTLDTC.cbl - there is NO post-CALL move targeting WS-DATE-FMT. The "
                            + "multi-receiver MOVE at :L111-L113 leaves it clean and nothing overwrites "
                            + "it, so the Mask used: field of :L55 renders the mask verbatim. Rendered "
                            + "as [%s]", visible(mask))
                    .isEqualTo(DateValidationService.MASK_YYYY_MM_DD)
                    .doesNotContain(String.valueOf(LENGTH_PREFIX_HIGH_ORDER_BYTE))
                    .doesNotContain(String.valueOf(LENGTH_PREFIX_LOW_ORDER_BYTE));

            assertThat(mask.chars().allMatch(c -> c >= ' '))
                    .as("app/cbl/CSUTLDTC.cbl:L55 - every byte of the mask field is printable, which is "
                            + "precisely what is NOT true of the date field beside it")
                    .isTrue();
            assertThat(tested.chars().allMatch(c -> c >= ' '))
                    .as("app/cbl/CSUTLDTC.cbl:L122 - the date field carries two sub-space bytes, and "
                            + "this is the asymmetry in one assertion. Both halves are the contract")
                    .isFalse();
        }

        @Test
        @DisplayName("the corruption is uniform across outcomes and mask spellings")
        void theCorruptionAppliesToEveryOutcomeAndBothMasks() {
            final DateValidationService service = service();

            final DateValidationResult rejected =
                    service.validate("2022-13-01", DateValidationService.MASK_YYYY_MM_DD);
            assertThat(field(rejected.result(), TESTED_DATE_FIELD_START, LINKAGE_DATE_WIDTH))
                    .as("app/cbl/CSUTLDTC.cbl:L122 sits BEFORE the EVALUATE of :L128, so the corruption "
                            + "is unconditional and applies to rejected dates exactly as to accepted "
                            + "ones")
                    .startsWith(String.valueOf(LENGTH_PREFIX_HIGH_ORDER_BYTE))
                    .endsWith("2022-13-");

            final DateValidationResult compact =
                    service.validate(SAMPLE_COMPACT_DATE, DateValidationService.MASK_YYYYMMDD);
            assertThat(field(compact.result(), TESTED_DATE_FIELD_START, LINKAGE_DATE_WIDTH))
                    .as("app/cbl/CSUTLDTC.cbl:L122 - an eight-character date padded into the PIC X(10) "
                            + "of :L84 loses two trailing SPACES rather than two digits, so the compact "
                            + "path renders all eight of its characters. The prefix is still there")
                    .startsWith(String.valueOf(LENGTH_PREFIX_HIGH_ORDER_BYTE))
                    .endsWith(SAMPLE_COMPACT_DATE);
            assertThat(field(compact.result(), MASK_FIELD_START, DELIMITED_MASK_WIDTH))
                    .as("app/cbl/CSUTLDTC.cbl:L55 - the eight-character mask is right-padded into the "
                            + "ten-byte field and is still clean")
                    .isEqualTo(rightPadded(DateValidationService.MASK_YYYYMMDD, DELIMITED_MASK_WIDTH));
        }
    }

    /**
     * The two output channels of {@code app/cbl/CSUTLDTC.cbl:L97} and {@code :L98}. A port that surfaces
     * only the rendered block loses the process-level signal the callers branch on.
     */
    @Nested
    @DisplayName("the subprogram has two outputs: the eighty-byte block and RETURN-CODE")
    class DualOutputContract {

        @Test
        @DisplayName("the rendered block is returned through the third parameter")
        void theRenderedBlockIsReturnedThroughTheThirdParameter() {
            final DateValidationResult result =
                    service().validate(SAMPLE_DELIMITED_DATE, DateValidationService.MASK_YYYY_MM_DD);

            assertThat(result.result())
                    .as("app/cbl/CSUTLDTC.cbl:L97 MOVE WS-MESSAGE TO LS-RESULT - the first output "
                            + "channel, into the PIC X(80) third LINKAGE item of :L86")
                    .hasSize(MESSAGE_AREA_LENGTH);
            assertThat(result.feedbackCode())
                    .as("app/cbl/CSUTLDTC.cbl:L119 FEEDBACK-CODE is the fourth CEEDAYS argument and the "
                            + "origin of everything rendered")
                    .isNotNull();
        }

        @Test
        @DisplayName("the numeric severity is returned through RETURN-CODE as well")
        void theNumericSeverityIsReturnedThroughReturnCode() {
            final DateValidationService service = service();

            final DateValidationResult accepted =
                    service.validate(SAMPLE_DELIMITED_DATE, DateValidationService.MASK_YYYY_MM_DD);
            assertThat(accepted.returnCode())
                    .as("app/cbl/CSUTLDTC.cbl:L98 MOVE WS-SEVERITY-N TO RETURN-CODE - the second output "
                            + "channel. The success token of :L62 has severity zero, so RETURN-CODE is "
                            + "zero")
                    .isZero();

            final DateValidationResult rejected =
                    service.validate("2022-13-01", DateValidationService.MASK_YYYY_MM_DD);
            assertThat(rejected.returnCode())
                    .as("app/cbl/CSUTLDTC.cbl:L98 - every diagnostic token of :L63-L70 carries severity "
                            + "three, so RETURN-CODE is three")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("both channels always agree, because both derive from the one decoded severity")
        void theTwoChannelsAlwaysAgree() {
            final DateValidationService service = service();
            final List<String> dates =
                    List.of(SAMPLE_DELIMITED_DATE, "2022-13-01", "2022/06/10", "          ",
                            "1582-10-14", "0000-01-01");

            for (final String date : dates) {
                final DateValidationResult result =
                        service.validate(date, DateValidationService.MASK_YYYY_MM_DD);

                assertThat(result.returnCode())
                        .as("app/cbl/CSUTLDTC.cbl:L98 against :L123 - RETURN-CODE and the rendered "
                                + "severity are two views of the ONE value moved out of "
                                + "SEVERITY OF FEEDBACK-CODE, so they can never disagree. Date [%s]",
                                visible(date))
                        .isEqualTo(Integer.parseInt(result.severityCode()));
                assertThat(result.valid())
                        .as("app/cbl/CSUTLDTC.cbl:L98 - a zero RETURN-CODE is the accept verdict, which "
                                + "is how app/cbl/CORPT00C.cbl:L396 and app/cpy/CSUTLDPY.cpy:L298 read "
                                + "it. Date [%s]", visible(date))
                        .isEqualTo(result.returnCode() == 0);
            }
        }

        @Test
        @DisplayName("a rejected date is a severity-three return value and never an exception")
        void aRejectedDateIsAReturnValueAndNeverThrows() {
            final DateValidationService service = service();

            assertThatCode(() -> service.validate("2022-13-01", DateValidationService.MASK_YYYY_MM_DD))
                    .as("app/cbl/CSUTLDTC.cbl:L98 and :L100 - the subprogram sets RETURN-CODE and issues "
                            + "EXIT PROGRAM. Rejection is a RETURN VALUE; there is no abend path here, "
                            + "so the Java replacement must not throw either")
                    .doesNotThrowAnyException();

            final DateValidationResult rejected =
                    service.validate("2022-13-01", DateValidationService.MASK_YYYY_MM_DD);
            assertThat(rejected.valid())
                    .as("app/cbl/CSUTLDTC.cbl:L140 - the outcome is carried in the value, not in a "
                            + "thrown type")
                    .isFalse();
            assertThat(rejected.resultText())
                    .as("app/cbl/CSUTLDTC.cbl:L140 - and the diagnostic text is readable from that same "
                            + "returned value")
                    .isEqualTo(LITERAL_INVALID_MONTH);
        }

        @Test
        @DisplayName("severity and message number both render as four zero-padded characters")
        void severityAndMessageNumberRenderAsFourZeroPaddedCharacters() {
            final DateValidationService service = service();

            final DateValidationResult accepted =
                    service.validate(SAMPLE_DELIMITED_DATE, DateValidationService.MASK_YYYY_MM_DD);
            assertThat(accepted.severityCode())
                    .as("app/cbl/CSUTLDTC.cbl:L123 into the PIC X(04) of :L43 - severity zero renders "
                            + "as four zero characters, never as a single '0' and never as spaces")
                    .isEqualTo("0000")
                    .hasSize(DISPLAY_HALFWORD_WIDTH);
            assertThat(accepted.messageNumber())
                    .as("app/cbl/CSUTLDTC.cbl:L124 into the PIC X(04) of :L46 - message number zero "
                            + "likewise renders as four zero characters. This exact spelling is what "
                            + "app/cbl/CORPT00C.cbl:L396 compares against")
                    .isEqualTo("0000")
                    .hasSize(DISPLAY_HALFWORD_WIDTH);

            final DateValidationResult insufficient =
                    service.validate("          ", DateValidationService.MASK_YYYY_MM_DD);
            assertThat(insufficient.severityCode())
                    .as("app/cbl/CSUTLDTC.cbl:L123 - severity three renders zero-padded to four")
                    .isEqualTo("0003");
            assertThat(insufficient.messageNumber())
                    .as("app/cbl/CSUTLDTC.cbl:L124 - a four-digit message number needs no padding and "
                            + "must not be truncated either. Token X'000309CB59C3C5C5' of :L63 decodes "
                            + "to %d", decodedMessageNumber(TOKEN_INSUFFICIENT_DATA))
                    .isEqualTo("2507");
        }
    }

    /**
     * The two distinct masks and the argument widths that evidence the copybook path's buffer overread.
     * Conflating the masks is a High-severity error: it changes what CEEDAYS is asked to parse.
     */
    @Nested
    @DisplayName("HIGH: two masks of different widths reach the same PIC X(10) linkage item")
    class MaskWidthsAndOverreadEvidence {

        @Test
        @DisplayName("the copybook path's mask is eight characters and the program path's is ten")
        void theTwoMasksHaveDifferentSpellingsAndDifferentWidths() {
            assertThat(DateValidationService.MASK_YYYYMMDD)
                    .as("app/cpy/CSUTLDWY.cpy:L58-L59 declares WS-DATE-FORMAT PIC X(08) VALUE "
                            + "'YYYYMMDD', and app/cpy/CSUTLDPY.cpy:L291 moves that spelling in. This "
                            + "is the copybook path's mask")
                    .isEqualTo("YYYYMMDD")
                    .hasSize(UNDELIMITED_MASK_WIDTH)
                    .doesNotContain("-");

            assertThat(DateValidationService.MASK_YYYY_MM_DD)
                    .as("app/cbl/CORPT00C.cbl:L72 and app/cbl/COTRN02C.cbl:L60, both verified by direct "
                            + "read, declare 'YYYY-MM-DD'. This is the program path's mask")
                    .isEqualTo("YYYY-MM-DD")
                    .hasSize(DELIMITED_MASK_WIDTH)
                    .contains("-");

            assertThat(DateValidationService.MASK_YYYYMMDD)
                    .as("the two masks are DISTINCT constants and must never be conflated - severity "
                            + "High, because CEEDAYS is being asked to parse a different shape")
                    .isNotEqualTo(DateValidationService.MASK_YYYY_MM_DD);
            assertThat(DELIMITED_MASK_WIDTH - UNDELIMITED_MASK_WIDTH)
                    .as("app/cpy/CSUTLDWY.cpy:L58 against app/cbl/CORPT00C.cbl:L72 - the delimited mask "
                            + "is exactly two characters wider, being the two dash separators")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("both masks are accepted by the same PIC X(10) linkage item")
        void bothMasksAreAcceptedThroughTheSameLinkageItem() {
            final DateValidationService service = service();

            assertThat(DateValidationService.LS_DATE_FORMAT_LENGTH)
                    .as("app/cbl/CSUTLDTC.cbl:L85 LS-DATE-FORMAT PIC X(10) - ONE linkage width receives "
                            + "both masks, which is exactly why the two are so easily conflated")
                    .isEqualTo(DELIMITED_MASK_WIDTH);

            assertThat(service.validate(SAMPLE_DELIMITED_DATE, DateValidationService.MASK_YYYY_MM_DD)
                    .valid())
                    .as("app/cbl/CORPT00C.cbl:L72 - a delimited date under the delimited mask is "
                            + "accepted")
                    .isTrue();
            assertThat(service.validate(SAMPLE_COMPACT_DATE, DateValidationService.MASK_YYYYMMDD)
                    .valid())
                    .as("app/cpy/CSUTLDPY.cpy:L291 - a compact date under the compact mask is accepted")
                    .isTrue();

            assertThat(service.validate(SAMPLE_COMPACT_DATE, DateValidationService.MASK_YYYY_MM_DD)
                    .valid())
                    .as("app/cbl/CSUTLDTC.cbl:L116-L120 - a compact date under the DELIMITED mask must "
                            + "be rejected, which is the observable proof the two masks are not "
                            + "interchangeable")
                    .isFalse();
            assertThat(service.validate(SAMPLE_DELIMITED_DATE, DateValidationService.MASK_YYYYMMDD)
                    .valid())
                    .as("app/cbl/CSUTLDTC.cbl:L116-L120 - and a delimited date under the COMPACT mask "
                            + "is rejected too, closing the conflation argument in both directions")
                    .isFalse();
        }

        @Test
        @DisplayName("the overread evidence is the eight-versus-ten argument width, not a simulation")
        void theOverreadEvidenceIsTheArgumentWidthDifference() {
            assertThat(DateValidationService.CCYYMMDD_LENGTH)
                    .as("app/cpy/CSUTLDWY.cpy:L4 WS-EDIT-DATE-CCYYMMDD is CCYY(4) + MM(2) + DD(2), so "
                            + "the argument app/cpy/CSUTLDPY.cpy:L294 hands over is EIGHT bytes wide")
                    .isEqualTo(COMPACT_DATE_ARGUMENT_WIDTH)
                    .isEqualTo(8);
            assertThat(DateValidationService.LS_DATE_LENGTH)
                    .as("app/cbl/CSUTLDTC.cbl:L84 LS-DATE PIC X(10) - but the subprogram declares TEN, "
                            + "and :L105-L106 moves LENGTH OF LS-DATE unconditionally")
                    .isEqualTo(LINKAGE_DATE_WIDTH)
                    .isEqualTo(10);

            assertThat(LINKAGE_DATE_WIDTH - COMPACT_DATE_ARGUMENT_WIDTH)
                    .as("app/cbl/CSUTLDTC.cbl:L105-L106 against app/cpy/CSUTLDPY.cpy:L294 - CEEDAYS is "
                            + "told the argument is ten bytes when the copybook path supplies eight, so "
                            + "it reads TWO bytes past the end. The stray bytes are provably "
                            + "app/cpy/CSUTLDWY.cpy:L37 WS-EDIT-DATE-BINARY for the date argument, and "
                            + ":L61 WS-SEVERITY for the format argument. Severity High, DOCUMENTED and "
                            + "NOT simulated: a JVM cannot reproduce a read past a String, so the "
                            + "argument widths ARE the assertable evidence")
                    .isEqualTo(2);

            assertThat(DateValidationService.MASK_YYYYMMDD.length())
                    .as("app/cpy/CSUTLDPY.cpy:L295 passes WS-DATE-FORMAT PIC X(08) of "
                            + "app/cpy/CSUTLDWY.cpy:L58, so the FORMAT argument is short by the same "
                            + "two bytes as the date argument")
                    .isEqualTo(COMPACT_DATE_ARGUMENT_WIDTH);
            assertThat(DateValidationService.MASK_YYYY_MM_DD.length())
                    .as("app/cbl/CORPT00C.cbl:L129-L136 passes true PIC X(10) fields, so the PROGRAM "
                            + "path does NOT overread. Only the copybook path does")
                    .isEqualTo(LINKAGE_DATE_WIDTH);
        }

        @Test
        @DisplayName("a short or over-long argument is absorbed to the linkage width rather than rejected outright")
        void argumentsAreAbsorbedToTheLinkageWidth() {
            final DateValidationService service = service();

            final DateValidationResult padded =
                    service.validate(SAMPLE_COMPACT_DATE, DateValidationService.MASK_YYYYMMDD);
            assertThat(padded.result())
                    .as("app/cbl/CSUTLDTC.cbl:L84 - an eight-character argument occupies a PIC X(10) "
                            + "field and is space-padded by COBOL, so the eighty-byte area still renders")
                    .hasSize(MESSAGE_AREA_LENGTH);

            final DateValidationResult overLong =
                    service.validate(SAMPLE_DELIMITED_DATE + "XYZ",
                            DateValidationService.MASK_YYYY_MM_DD);
            assertThat(overLong.result())
                    .as("app/cbl/CSUTLDTC.cbl:L84 - a thirteen-character argument is truncated to the "
                            + "PIC X(10) linkage width exactly as a COBOL group move would truncate it, "
                            + "and the area is still eighty bytes")
                    .hasSize(MESSAGE_AREA_LENGTH);
            assertThat(overLong.valid())
                    .as("app/cbl/CSUTLDTC.cbl:L84 - truncation leaves the first ten characters, which "
                            + "are the valid date, so the verdict matches the untruncated argument")
                    .isEqualTo(service.validate(SAMPLE_DELIMITED_DATE,
                            DateValidationService.MASK_YYYY_MM_DD).valid());
        }
    }

    /**
     * Untrusted and boundary input, per Rule 1 clause A ("treat inputs as untrusted") and clause B
     * ("handle null/empty cases explicitly").
     */
    @Nested
    @DisplayName("untrusted and boundary input is handled explicitly")
    class UntrustedAndBoundaryInput {

        @ParameterizedTest(name = "[{index}] a date of \"{0}\" is rejected without throwing")
        @ValueSource(strings = {"", " ", "          ", "abcdefghij", "2022/06/10", "2022-13-01",
            "2022-00-01", "2022-06-00", "2022-06-32", "2022-02-30", "0000-01-01", "----------",
            "9999-99-99", "20220610xx"})
        @DisplayName("hostile and malformed dates are rejected as return values")
        void hostileDatesAreRejectedAsReturnValues(final String date) {
            final DateValidationResult result =
                    service().validate(date, DateValidationService.MASK_YYYY_MM_DD);

            assertThat(result.valid())
                    .as("app/cbl/CSUTLDTC.cbl:L128-L149 - every branch other than :L129-L130 is a "
                            + "rejection. Date [%s] rendered [%s]", visible(date),
                            visible(result.result()))
                    .isFalse();
            assertThat(result.returnCode())
                    .as("app/cbl/CSUTLDTC.cbl:L98 - a rejection carries severity three through "
                            + "RETURN-CODE. Date [%s]", visible(date))
                    .isEqualTo(3);
            assertThat(result.result())
                    .as("app/cbl/CSUTLDTC.cbl:L86 - the area is eighty bytes even for hostile input, "
                            + "with no truncation and no overflow. Date [%s]", visible(date))
                    .hasSize(MESSAGE_AREA_LENGTH);
            assertThat(result.resultText())
                    .as("app/cbl/CSUTLDTC.cbl:L49 WS-RESULT PIC X(15) - the diagnostic text is one of "
                            + "the ten declared literals RIGHT-PADDED to the receiving width, never a "
                            + "fabricated message. The padding is not incidental: two of the ten "
                            + "literals are declared shorter than fifteen characters, so the declared "
                            + "spelling alone would not match the rendering. Date [%s]", visible(date))
                    .hasSize(RESULT_LITERAL_WIDTH)
                    .isIn(ALL_RESULT_LITERALS.stream()
                            .map(literal -> rightPadded(literal, RESULT_LITERAL_WIDTH))
                            .toList());
        }

        @Test
        @DisplayName("a null date and a null mask are absorbed to spaces rather than throwing")
        void nullArgumentsAreAbsorbedToSpaces() {
            final DateValidationService service = service();

            assertThatCode(() -> service.validate(null, DateValidationService.MASK_YYYY_MM_DD))
                    .as("app/cbl/CSUTLDTC.cbl:L88 - a COBOL PIC X(10) linkage item cannot be null, so "
                            + "the Java replacement absorbs null to the space-filled equivalent rather "
                            + "than inventing an exception the source has no path for")
                    .doesNotThrowAnyException();
            assertThatCode(() -> service.validate(SAMPLE_DELIMITED_DATE, null))
                    .as("app/cbl/CSUTLDTC.cbl:L88 - the same reasoning applies to the mask argument")
                    .doesNotThrowAnyException();
            assertThatCode(() -> service.validate(null, null))
                    .as("app/cbl/CSUTLDTC.cbl:L88 - and to both together")
                    .doesNotThrowAnyException();

            final DateValidationResult nullDate =
                    service.validate(null, DateValidationService.MASK_YYYY_MM_DD);
            final DateValidationResult blankDate =
                    service.validate("          ", DateValidationService.MASK_YYYY_MM_DD);

            assertThat(nullDate.result())
                    .as("app/cbl/CSUTLDTC.cbl:L91 MOVE SPACES TO WS-DATE - a null argument must be "
                            + "indistinguishable from the all-spaces argument, because that is the only "
                            + "state a COBOL caller could have produced")
                    .isEqualTo(blankDate.result());
            assertThat(nullDate.messageNumber())
                    .as("app/cbl/CSUTLDTC.cbl:L63 - and both decode to the insufficient-data outcome")
                    .isEqualTo("2507");
        }

        @Test
        @DisplayName("a null clock is rejected at construction with a message naming the argument")
        void aNullClockIsRejectedAtConstruction() {
            assertThatNullPointerException()
                    .as("Rule 1 clause B - the one genuine precondition of this bean is its clock, and "
                            + "the failure must name the argument rather than surfacing a bare NPE")
                    .isThrownBy(() -> new DateValidationService(null))
                    .withMessageContaining("clock")
                    .withNoCause();
        }

        @Test
        @DisplayName("an eighty-byte violation is rejected with a message stating the offending length")
        void anAreaOfTheWrongWidthIsRejectedWithItsLength() {
            final FeedbackCode success = FeedbackCode.of(FeedbackCondition.FC_INVALID_DATE);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("app/cbl/CSUTLDTC.cbl:L86 LS-RESULT PIC X(80) - the width is a contract, so a "
                            + "short area is refused and the diagnostic states the length actually "
                            + "supplied rather than merely that something was wrong")
                    .isThrownBy(() -> new DateValidationResult(success, "too short"))
                    .withMessageContaining("80")
                    .withMessageContaining(String.valueOf("too short".length()))
                    .withNoCause();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("app/cbl/CSUTLDTC.cbl:L86 - an over-long area is refused on the same grounds")
                    .isThrownBy(() -> new DateValidationResult(success,
                            rightPadded("", MESSAGE_AREA_LENGTH + 1)))
                    .withMessageContaining(String.valueOf(MESSAGE_AREA_LENGTH + 1))
                    .withNoCause();

            assertThatNullPointerException()
                    .as("app/cbl/CSUTLDTC.cbl:L97 - neither component of the rendered outcome may be "
                            + "absent, and each failure names its own field")
                    .isThrownBy(() -> new DateValidationResult(success, null))
                    .withMessageContaining("result");
            assertThatNullPointerException()
                    .as("app/cbl/CSUTLDTC.cbl:L119 - the feedback code is likewise mandatory")
                    .isThrownBy(() -> new DateValidationResult(null,
                            rightPadded("", MESSAGE_AREA_LENGTH)))
                    .withMessageContaining("feedbackCode");
            assertThatNullPointerException()
                    .as("app/cbl/CSUTLDTC.cbl:L62-L70 - and a null condition cannot be turned into a "
                            + "feedback code")
                    .isThrownBy(() -> FeedbackCode.of(null))
                    .withMessageContaining("condition");
        }

        @Test
        @DisplayName("an exactly-eighty-byte area is accepted, which fixes the boundary from both sides")
        void anExactlyEightyByteAreaIsAccepted() {
            final FeedbackCode success = FeedbackCode.of(FeedbackCondition.FC_INVALID_DATE);

            assertThatCode(() -> new DateValidationResult(success,
                    rightPadded("", MESSAGE_AREA_LENGTH)))
                    .as("app/cbl/CSUTLDTC.cbl:L86 - exactly eighty is the accepted width, so the "
                            + "boundary is closed on the low side by the seventy-nine case above and on "
                            + "the high side by the eighty-one case")
                    .doesNotThrowAnyException();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("app/cbl/CSUTLDTC.cbl:L86 - seventy-nine bytes is one short and must fail")
                    .isThrownBy(() -> new DateValidationResult(success,
                            rightPadded("", MESSAGE_AREA_LENGTH - 1)))
                    .withMessageContaining(String.valueOf(MESSAGE_AREA_LENGTH - 1));
        }

        @Test
        @DisplayName("the boundary dates of the Lillian range are handled at their exact edges")
        void theLillianRangeBoundariesAreHandledAtTheirEdges() {
            final DateValidationService service = service();

            assertThat(service.validate("1582-10-14", DateValidationService.MASK_YYYY_MM_DD)
                    .messageNumber())
                    .as("app/cbl/CSUTLDTC.cbl:L66 FC-UNSUPP-RANGE - the day before the Lillian epoch is "
                            + "outside the supported range, and its token X'000309D159C3C5C5' decodes to "
                            + "message %d", decodedMessageNumber(TOKEN_UNSUPPORTED_RANGE))
                    .isEqualTo("2513");
            assertThat(service.validate("1582-10-15", DateValidationService.MASK_YYYY_MM_DD).valid())
                    .as("app/cbl/CSUTLDTC.cbl:L62 - the first Lillian day itself is inside the range, so "
                            + "the boundary is exact rather than approximate")
                    .isTrue();
            assertThat(service.validate("9999-12-31", DateValidationService.MASK_YYYY_MM_DD).valid())
                    .as("app/cbl/CSUTLDTC.cbl:L62 - and the last representable day is still accepted")
                    .isTrue();
        }
    }

    /**
     * {@code A000-MAIN-EXIT.} at {@code app/cbl/CSUTLDTC.cbl:L152-L154} is a bare {@code EXIT}: a
     * documentary no-op that exists only as the {@code THRU} target of the {@code PERFORM} at
     * {@code :L93-L94}.
     */
    @Nested
    @DisplayName("A000-MAIN-EXIT is a documentary no-op that changes no state")
    class ExitParagraphIsANoOp {

        @Test
        @DisplayName("repeating a validation yields a byte-identical area, so the exit paragraph changes nothing")
        void repeatingAValidationYieldsAByteIdenticalArea() {
            final DateValidationService service = service();

            final DateValidationResult first =
                    service.validate(SAMPLE_DELIMITED_DATE, DateValidationService.MASK_YYYY_MM_DD);
            final DateValidationResult second =
                    service.validate(SAMPLE_DELIMITED_DATE, DateValidationService.MASK_YYYY_MM_DD);

            assertThat(second.result())
                    .as("app/cbl/CSUTLDTC.cbl:L153 EXIT - a bare COBOL EXIT is a documentary no-op, not "
                            + "a return and not a state change. If it mutated anything, a second "
                            + "identical call could not render byte-identically")
                    .isEqualTo(first.result());
            assertThat(second)
                    .as("app/cbl/CSUTLDTC.cbl:L152-L154 - the whole outcome is reproducible, which is "
                            + "the observable form of 'this paragraph does nothing'")
                    .isEqualTo(first);
        }

        @Test
        @DisplayName("no state leaks between calls, because L90 and L91 reset the area first")
        void noStateLeaksBetweenCalls() {
            final DateValidationService service = service();

            final DateValidationResult beforeContamination =
                    service.validate(SAMPLE_DELIMITED_DATE, DateValidationService.MASK_YYYY_MM_DD);

            assertThat(service.validate("2022-13-01", DateValidationService.MASK_YYYY_MM_DD).valid())
                    .as("app/cbl/CSUTLDTC.cbl:L140 - interpose a rejection carrying different severity, "
                            + "message number and result text")
                    .isFalse();

            assertThat(service.validate(SAMPLE_DELIMITED_DATE, DateValidationService.MASK_YYYY_MM_DD)
                    .result())
                    .as("app/cbl/CSUTLDTC.cbl:L90 INITIALIZE WS-MESSAGE and :L91 MOVE SPACES TO WS-DATE "
                            + "run before every PERFORM of A000-MAIN, so no residue of the interposed "
                            + "rejection can survive into the next rendering")
                    .isEqualTo(beforeContamination.result());
        }

        @Test
        @DisplayName("two independently constructed beans agree, so nothing is held in shared state")
        void twoIndependentBeansAgree() {
            final DateValidationResult fromFirstBean =
                    service().validate(SAMPLE_DELIMITED_DATE, DateValidationService.MASK_YYYY_MM_DD);
            final DateValidationResult fromSecondBean =
                    service().validate(SAMPLE_DELIMITED_DATE, DateValidationService.MASK_YYYY_MM_DD);

            assertThat(fromSecondBean.result())
                    .as("Rule 1 clause B - the rendering is a pure function of its two arguments, with "
                            + "no global mutable state anywhere in the path, so two separately "
                            + "constructed beans must render identically")
                    .isEqualTo(fromFirstBean.result());
        }
    }
}
