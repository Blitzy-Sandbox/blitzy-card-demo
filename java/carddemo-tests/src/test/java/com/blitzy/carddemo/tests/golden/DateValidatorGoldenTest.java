/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright Contributors to the CardDemo Project.
 */
package com.blitzy.carddemo.tests.golden;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Byte-for-byte golden-record parity test for {@code CSUTLDTC}
 * (Date Validator Subroutine).
 *
 * <p><strong>Source COBOL:</strong> {@code app/cbl/CSUTLDTC.cbl} &mdash; the
 * {@code PROGRAM-ID CSUTLDTC} callable subprogram that wraps the IBM Language
 * Environment service {@code CEEDAYS} to convert/validate a date in a
 * specified format. CSUTLDTC is NOT a standalone main program; it is invoked
 * via {@code CALL "CSUTLDTC" USING LS-DATE, LS-DATE-FORMAT, LS-RESULT} by
 * upstream callers including {@code COACTUPC}, {@code COTRN02C}, and
 * {@code CORPT00C}. The COBOL {@code LINKAGE SECTION} carries:
 * <ul>
 *   <li>{@code LS-DATE PIC X(10)} &mdash; the date string to validate (e.g.
 *       {@code "2024-12-31"})</li>
 *   <li>{@code LS-DATE-FORMAT PIC X(10)} &mdash; the format-mask string
 *       (e.g. {@code "YYYY-MM-DD"})</li>
 *   <li>{@code LS-RESULT PIC X(80)} &mdash; the 80-byte
 *       {@code WS-MESSAGE}-shaped result envelope (severity, message
 *       number, 15-char result text, original date, format mask, padding)
 *       per the {@code WS-MESSAGE} structure at
 *       {@code app/cbl/CSUTLDTC.cbl:L42-L57}</li>
 * </ul>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.util.DateValidator}. Per AAP
 * &sect;0.6.8 this is the <em>only</em> renamed program in the migration
 * (an AAP-sanctioned exception from the standard one-class-per-program
 * naming rule).</p>
 *
 * <p><strong>CEEDAYS &rarr; java.time translation</strong> per AAP
 * &sect;0.4.1: the LE service {@code CEEDAYS} is replaced with
 * {@link java.time.LocalDate#parse(CharSequence, java.time.format.DateTimeFormatter)}
 * configured with
 * {@link java.time.format.ResolverStyle#STRICT}. The strict resolver
 * mandate (AAP &sect;0.6.4) is non-negotiable: {@code LocalDate.parse}
 * defaults to {@link java.time.format.ResolverStyle#SMART SMART} which
 * silently coerces invalid calendar dates (e.g. February 30) into
 * adjacent valid dates &mdash; that behaviour would BREAK byte-for-byte
 * parity with the COBOL {@code FC-BAD-DATE-VALUE} feedback path. The
 * {@code WS-RESULT} 15-character text constants ({@code "Date is valid"},
 * {@code "Datevalue error"}, {@code "Unsupp. Range"}, etc.) are preserved
 * verbatim from {@code app/cbl/CSUTLDTC.cbl:L128-L149} including trailing
 * spaces.</p>
 *
 * <p><strong>LINKAGE SECTION shape preservation</strong> per AAP &sect;0.7.1
 * (Minimal Change Clause): the Java {@code DateValidator.validate(...)}
 * method MUST accept and return record shapes mirroring the COBOL
 * byte-level contract at {@code app/cbl/CSUTLDTC.cbl:L83-L86} so that
 * downstream callers (COACTUPC, COTRN02C, CORPT00C) receive byte-identical
 * results. The working-storage layout from {@code app/cpy/CSUTLDPY.cpy}
 * (procedure template) and {@code app/cpy/CSUTLDWY.cpy} (work area) is
 * translated to {@code com.blitzy.carddemo.domain.validation.DateValidationWork}
 * (consumed by {@code DateValidator}, NOT this test class) so the COBOL
 * field structure (CCYY / MM / DD components, validity flags, century
 * 88-levels {@code THIS-CENTURY=20} / {@code LAST-CENTURY=19}, 80-byte
 * {@code WS-DATE-VALIDATION-RESULT} envelope) is observable in both the
 * Java implementation and the captured COBOL output.</p>
 *
 * <p><strong>Test scenario</strong> (programmatic CALL sequence, per the
 * fixture README at
 * {@code src/test/resources/golden/csutldtc/expected/README.md}):
 * the test reads {@code input_calls.txt} (one CALL per line: date string
 * + format mask), invokes {@link
 * com.blitzy.carddemo.application.util.DateValidator} for each line, and
 * appends the 80-byte {@code LS-RESULT} envelope to {@code call_results.txt}
 * while also producing a structured trace on stdout. Byte-for-byte parity
 * is asserted against the captured COBOL outputs at
 * {@code src/test/resources/golden/csutldtc/expected/stdout.txt} and
 * {@code src/test/resources/golden/csutldtc/expected/call_results.txt}.
 * The minimum required scenario activation set covers (per the fixture
 * README):
 * <ol>
 *   <li>Valid date {@code "2024-12-31"} / {@code "YYYY-MM-DD"} &rarr;
 *       {@code FC-INVALID-DATE} (the OK feedback) &rarr;
 *       {@code "Date is valid  "}</li>
 *   <li>Invalid calendar date {@code "2024-02-30"} / {@code "YYYY-MM-DD"}
 *       &rarr; {@code FC-BAD-DATE-VALUE} &rarr; {@code "Datevalue error"}
 *       (requires {@link java.time.format.ResolverStyle#STRICT})</li>
 *   <li>Format mismatch {@code "2024/12/31"} / {@code "YYYY-MM-DD"}
 *       &rarr; {@code FC-NON-NUMERIC-DATA} or {@code FC-BAD-PIC-STRING}</li>
 *   <li>Leap-year valid {@code "2024-02-29"} &rarr; {@code "Date is valid  "};
 *       leap-year invalid {@code "2023-02-29"} &rarr;
 *       {@code "Datevalue error"}</li>
 *   <li>Year boundaries {@code "0001-01-01"} and {@code "9999-12-31"}
 *       match the CEEDAYS captured behaviour</li>
 *   <li>Blank / insufficient input &rarr; {@code FC-INSUFFICIENT-DATA}
 *       &rarr; {@code "Insufficient   "}</li>
 *   <li>Pre-CEEDAYS-range dates ({@code "1582-12-31"} &mdash; pre-1583
 *       Gregorian cutover) &rarr; {@code FC-UNSUPP-RANGE} &rarr;
 *       {@code "Unsupp. Range  "}</li>
 * </ol>
 *
 * <p>This test is the <strong>NON-NEGOTIABLE PR gate</strong> per AAP
 * &sect;0.6.11. Any deviation in CEEDAYS-equivalent error-code mapping,
 * feedback-token to {@code WS-RESULT} text translation, severity /
 * message-number encoding, or {@code LS-RESULT} 80-byte envelope layout
 * breaks parity and blocks the PR.</p>
 *
 * <p><strong>Scaffolding state</strong>: per AAP &sect;0.6.11 ("Initial
 * test scaffolding may use placeholder expected files marked
 * {@code @Disabled} until COBOL captures are available"), the
 * {@link #byteForByteParity()} override below is annotated
 * {@code @Disabled} with a detailed 9-point verification reason citing the
 * COBOL capture procedure documented in {@code java/MIGRATION_NOTES.md}
 * &sect;1.6. The harness skeleton is unconditionally present so JUnit
 * discovers and reports this per-program test in CI from day one. The
 * {@code @Disabled} annotation will be removed in the same PR that
 * commits non-placeholder content into
 * {@code src/test/resources/golden/csutldtc/expected/stdout.txt},
 * {@code call_results.txt}, and {@code input_calls.txt}.</p>
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.util.DateValidator
 * @see <a href="file:../../../../../../resources/golden/csutldtc/expected/README.md">
 *      csutldtc/expected/README.md (fixture contract)</a>
 * @since 25
 */
@DisplayName("CSUTLDTC \u2014 Date Validator Subroutine Golden-Record Parity (CEEDAYS \u2192 java.time)")
public class DateValidatorGoldenTest extends GoldenRecordTest {

    /**
     * Identifier of the per-program fixture directory under
     * {@code src/test/resources/golden/}. Lowercase form of the COBOL
     * {@code PROGRAM-ID CSUTLDTC} per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention.
     */
    private static final String PROGRAM_DIR = "csutldtc";

    /**
     * Name of the synthesised scenario input file containing one CALL
     * description per line (date string + format mask). Located under
     * {@code src/test/resources/golden/csutldtc/expected/input_calls.txt}
     * because subroutine-style fixtures co-locate scenario inputs with
     * their expected outputs per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * convention.
     */
    private static final String INPUT_CALLS_TXT = "input_calls.txt";

    /**
     * Name of the captured COBOL stdout trace (one log line per CALL
     * invocation, typically echoing the input line and the 80-character
     * {@code LS-RESULT} envelope).
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * Name of the captured COBOL {@code LS-RESULT} byte stream
     * (concatenation of the 80-byte envelopes returned by the CSUTLDTC
     * {@code MOVE WS-MESSAGE TO LS-RESULT} at
     * {@code app/cbl/CSUTLDTC.cbl:L97}, one per CALL).
     */
    private static final String CALL_RESULTS_TXT = "call_results.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.util.DateValidator}{@code .class}.
     * Referenced via fully-qualified name so this file's import block
     * remains restricted to the dependency whitelist
     * ({@link GoldenRecordTest}, {@link ExpectedOutput},
     * {@link java.nio.file.Path}, {@link java.util.List},
     * {@link DisplayName}, {@link Disabled}). The fully-qualified class
     * literal compiles cleanly because {@code carddemo-tests} depends on
     * {@code carddemo-application} via the test classpath.</p>
     */
    @Override
    protected Class<?> programClass() {
        return com.blitzy.carddemo.application.util.DateValidator.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the path to the synthesised scenario input
     * {@code input_calls.txt} under
     * {@code src/test/resources/golden/csutldtc/expected/}. The
     * conventional {@code input/} folder is reserved for fixtures that
     * mirror runtime input streams (e.g. {@code dailytran.txt}); for
     * subroutine-style fixtures the scenario CALL list is co-located
     * with the expected outputs because it is a synthesised harness
     * artifact rather than a captured runtime input.</p>
     */
    @Override
    protected Path inputFile() {
        return resolveExpectedOutputPath(PROGRAM_DIR, INPUT_CALLS_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the path to the captured COBOL stdout trace at
     * {@code src/test/resources/golden/csutldtc/expected/stdout.txt}.
     * This is the <em>primary</em> expected output; the
     * {@link #expectedOutputs()} override below additionally declares
     * {@code call_results.txt} for the 80-byte {@code LS-RESULT}
     * envelope stream.</p>
     */
    @Override
    protected Path expectedOutputFile() {
        return resolveExpectedOutputPath(PROGRAM_DIR, STDOUT_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Declares the two byte-for-byte parity targets for CSUTLDTC:
     * <ul>
     *   <li>{@code stdout.txt} &mdash; the harness-driven log trace
     *       (one line per CALL invocation, echoing the input and the
     *       80-character {@code LS-RESULT}). The COBOL source contains
     *       a commented-out {@code DISPLAY WS-MESSAGE} at
     *       {@code app/cbl/CSUTLDTC.cbl:L96} that does NOT execute;
     *       this {@code stdout.txt} therefore captures the
     *       driver-emitted trace rather than CSUTLDTC's own DISPLAY
     *       stream.</li>
     *   <li>{@code call_results.txt} &mdash; the concatenated 80-byte
     *       {@code LS-RESULT} envelopes returned by each CALL, preserving
     *       the {@code WS-MESSAGE} field layout (severity / msgNo / result
     *       text / date / format mask) verbatim per the
     *       {@code WS-MESSAGE} structure at
     *       {@code app/cbl/CSUTLDTC.cbl:L42-L57}.</li>
     * </ul>
     *
     * <p>The base harness {@link GoldenRecordTest#byteForByteParity()}
     * iterates this list and asserts byte parity for each entry
     * independently per the AAP &sect;0.6.11 multi-output pattern,
     * identifying any mismatched output by name in the AssertJ failure
     * message.</p>
     */
    @Override
    protected List<ExpectedOutput> expectedOutputs() {
        return List.of(
            new ExpectedOutput(STDOUT_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, STDOUT_TXT)),
            new ExpectedOutput(CALL_RESULTS_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, CALL_RESULTS_TXT))
        );
    }

    /**
     * Byte-for-byte parity assertion, currently {@code @Disabled} pending
     * the COBOL CSUTLDTC baseline capture per AAP &sect;0.6.11 ("Initial
     * test scaffolding may use placeholder expected files marked
     * {@code @Disabled} until COBOL captures are available").
     *
     * <p>The {@code @Disabled} annotation embeds the complete 9-point
     * activation checklist below. The annotation will be removed in the
     * same PR that commits non-placeholder content under
     * {@code src/test/resources/golden/csutldtc/expected/} per the
     * capture procedure documented in {@code java/MIGRATION_NOTES.md}
     * &sect;1.6. The method body delegates to
     * {@link GoldenRecordTest#byteForByteParity()} so the actual
     * byte-by-byte assertion logic remains centralised in the base class
     * (per the AAP &sect;0.6.11 multi-output pattern with structured-record
     * diff hook via {@link GoldenRecordTest#maskedRanges(String)}).</p>
     *
     * <p><strong>Why the {@code @Test} annotation is re-declared on this
     * override</strong>: JUnit Platform's
     * {@code AnnotationSupport.findAnnotation(method, Test.class)} does
     * NOT walk to the parent class declaration when a subclass
     * <em>overrides</em> a {@code @Test}-annotated method &mdash; the
     * override is treated as a fresh method declaration that must carry
     * its own {@code @Test} annotation for JUnit Jupiter to discover it.
     * Without {@code @Test} here, this test class would be silently
     * dropped from the test suite, defeating the AAP &sect;0.6.11 PR-gate
     * purpose of the harness skeleton.</p>
     *
     * @throws Exception if the program under test, the
     *                   {@link GoldenRecordTest#runProgram(Class,
     *                   java.nio.file.Path, java.util.List)} hook, or any
     *                   {@link java.nio.file.Files#readAllBytes(
     *                   java.nio.file.Path)} call fails
     */
    @Override
    @Test
    @Disabled(
        "Awaiting COBOL CSUTLDTC baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md \u00a71.6 for the regeneration procedure "
            + "and the documented CEEDAYS \u2192 java.time.LocalDate translation "
            + "per AAP \u00a70.4.1. Activation checklist (all must hold before "
            + "removing @Disabled): "
            + "(1) Valid YYYY-MM-DD parses to java.time.LocalDate; "
            + "(2) Invalid calendar date (e.g., Feb 30) returns the specific "
            + "CEEDAYS error code matching FC-BAD-DATE-VALUE; "
            + "(3) Format mismatch returns CEEDAYS format-error code "
            + "(FC-NON-NUMERIC-DATA or FC-BAD-PIC-STRING); "
            + "(4) Leap-year dates: 2024-02-29 OK; 2023-02-29 rejected; "
            + "(5) Year boundaries (0001-01-01, 9999-12-31) match CEEDAYS "
            + "captured behavior; "
            + "(6) Blank / insufficient input returns FC-INSUFFICIENT-DATA "
            + "(\u2018Insufficient   \u2019); "
            + "(7) Pre-CEEDAYS-range dates (pre-1583 Gregorian) return "
            + "FC-UNSUPP-RANGE (\u2018Unsupp. Range  \u2019); "
            + "(8) LINKAGE SECTION output record shape preserved verbatim "
            + "(CSUTLDPY / CSUTLDWY layout; 80-byte LS-RESULT envelope); "
            + "(9) DateTimeFormatter uses ResolverStyle.STRICT (NOT SMART or "
            + "LENIENT) per AAP \u00a70.6.4."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
