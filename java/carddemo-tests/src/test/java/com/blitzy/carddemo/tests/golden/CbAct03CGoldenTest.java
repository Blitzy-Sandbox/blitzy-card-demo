/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright Contributors to the CardDemo Project.
 */
package com.blitzy.carddemo.tests.golden;

import java.nio.file.Path;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Byte-for-byte golden-record parity test for {@code CBACT03C}
 * (Sequential CARDXREF Reader, DOUBLE-DISPLAY pattern).
 *
 * <p><strong>Source COBOL:</strong> {@code app/cbl/CBACT03C.cbl} &mdash; the
 * {@code PROGRAM-ID CBACT03C} batch program that opens the XREFFILE
 * (KSDS, INDEXED organization, SEQUENTIAL access by
 * {@code FD-XREF-CARD-NUM PIC X(16)}), iterates card cross-reference
 * records in ascending key order, {@code DISPLAY}s each 50-byte
 * {@code CARD-XREF-RECORD} (from copybook {@code app/cpy/CVACT03Y.cpy}),
 * and closes the file. The COBOL follows the same simple I/O wrapper
 * template as {@code CBACT01C} / {@code CBACT02C} / {@code CBCUS01C}:
 * paragraphs {@code 0000-XREFFILE-OPEN}, {@code 1000-XREFFILE-GET-NEXT},
 * {@code 9000-XREFFILE-CLOSE}, {@code 9999-ABEND-PROGRAM},
 * {@code 9910-DISPLAY-IO-STATUS}. On any I/O error (FILE STATUS not
 * {@code "00"} on OPEN/CLOSE, or not {@code "00"}/{@code "10"} on READ)
 * the program abends via {@code CALL 'CEE3ABD'} with ABCODE=999.</p>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.account.CbAct03C}. Per AAP
 * &sect;0.4.1 (program-by-program mapping) CBACT03C is translated into
 * the {@code application/account/} subpackage (verified against the
 * on-disk sibling folder structure: {@code CbAct01C.java},
 * {@code CbAct02C.java}, {@code CbAct03C.java}, {@code CbAct04C.java}
 * co-locate under {@code account/} per the AAP transformation table).</p>
 *
 * <p><strong>DOUBLE-DISPLAY anomaly preserved verbatim</strong> per AAP
 * &sect;0.7.1 (Minimal Change Clause &mdash; translate faithfully, do NOT
 * "fix" in this refactor): CBACT03C displays each successfully-read
 * {@code CARD-XREF-RECORD} <strong>TWICE</strong> &mdash; the inner
 * {@code DISPLAY CARD-XREF-RECORD} inside paragraph
 * {@code 1000-XREFFILE-GET-NEXT} is <em>active</em> (NOT commented out
 * as in the sibling {@code CBACT02C}). Two emission sites apply per
 * record on a successful read:
 * <ol>
 *   <li>Emission #1 &mdash; inside {@code 1000-XREFFILE-GET-NEXT} at
 *       {@code app/cbl/CBACT03C.cbl:L96}:
 *       <pre>{@code
 *           IF  XREFFILE-STATUS = '00'
 *               MOVE 0 TO APPL-RESULT
 *               DISPLAY CARD-XREF-RECORD       <-- emission #1
 *           ELSE
 *       }</pre>
 *   </li>
 *   <li>Emission #2 &mdash; in the main {@code PERFORM UNTIL} loop at
 *       {@code app/cbl/CBACT03C.cbl:L78}, AFTER {@code GET-NEXT} returns
 *       without setting {@code END-OF-FILE = 'Y'}:
 *       <pre>{@code
 *           IF  END-OF-FILE = 'N'
 *               PERFORM 1000-XREFFILE-GET-NEXT
 *               IF  END-OF-FILE = 'N'
 *                   DISPLAY CARD-XREF-RECORD   <-- emission #2
 *               END-IF
 *           END-IF
 *       }</pre>
 *   </li>
 * </ol>
 * The Java translation
 * ({@link com.blitzy.carddemo.application.account.CbAct03C}) faithfully
 * reproduces this DOUBLE-DISPLAY pattern so the captured COBOL
 * {@code stdout.txt} contains exactly 50 &times; 2 = <strong>100</strong>
 * record display entries (same pattern as {@code CBACT01C} and
 * {@code CBCUS01C}, contrast with {@code CBACT02C} which uses SINGLE-DISPLAY).
 * This anomaly is documented in {@code java/MIGRATION_NOTES.md}.</p>
 *
 * <p><strong>Input fixture:</strong> {@code app/data/ASCII/cardxref.txt}
 * (50 cross-reference records per
 * {@code app/cpy/CVACT03Y.cpy:§CARD-XREF-RECORD} layout
 * {@code XREF-CARD-NUM PIC X(16)} + {@code XREF-CUST-ID PIC 9(09)} +
 * {@code XREF-ACCT-ID PIC 9(11)} + {@code FILLER PIC X(14)} = 50 bytes
 * per record). The fixture is read directly from {@code app/} via the
 * {@link GoldenRecordTest#resolveAppDataPath(String)} helper &mdash; NOT
 * copied into {@code java/carddemo-tests/} per AAP &sect;0.4.1 and
 * &sect;0.6.11. The base harness byte comparison handles the 50-byte
 * fixed-width record layout transparently; no record-level parsing is
 * required at this layer.</p>
 *
 * <p><strong>Expected output:</strong>
 * {@code src/test/resources/golden/cbact03c/expected/stdout.txt} captures
 * the COBOL {@code DISPLAY} entries: the
 * {@code "START OF EXECUTION OF PROGRAM CBACT03C"} banner
 * ({@code app/cbl/CBACT03C.cbl:L71}), <strong>100</strong>
 * {@code DISPLAY CARD-XREF-RECORD} lines (50 records &times; 2 emission
 * sites per the DOUBLE-DISPLAY pattern documented above), and the
 * {@code "END OF EXECUTION OF PROGRAM CBACT03C"} banner
 * ({@code app/cbl/CBACT03C.cbl:L85}). The PAN (cross-reference card
 * number {@code XREF-CARD-NUM}) appears in the captured COBOL output
 * verbatim because COBOL {@code DISPLAY} has no masking concept; the
 * Java translation's SLF4J appender mirrors the COBOL bytes exactly so
 * byte-equality holds. (The PAN masking AAP rule per AAP &sect;0.7.2
 * applies to operational logs surfaced to humans, not to the
 * golden-record capture which is a controlled test artefact.)</p>
 *
 * <p><strong>Constructor wiring:</strong>
 * {@link com.blitzy.carddemo.application.account.CbAct03C} has a single
 * constructor dependency on
 * {@code com.blitzy.carddemo.domain.port.CardXrefRepository}, which the
 * base harness {@link GoldenRecordTest#runProgram(Class,
 * java.nio.file.Path, java.util.List)} hook resolves to a
 * {@code FileCardXrefRepository} adapter wired against the
 * {@link #inputFile()} fixture. No override of {@code runProgram(...)}
 * is required in this subclass &mdash; the default wiring suffices.</p>
 *
 * <p>This test is the <strong>NON-NEGOTIABLE PR gate</strong> per AAP
 * &sect;0.6.11. Any deviation in the {@code DISPLAY} byte sequence,
 * record ordering (ascending {@code FD-XREF-CARD-NUM}), banner text, or
 * (critically) the DOUBLE-vs-SINGLE display count breaks parity and
 * blocks the PR.</p>
 *
 * <p><strong>Scaffolding state</strong>: per AAP &sect;0.6.11 ("Initial
 * test scaffolding may use placeholder expected files marked
 * {@code @Disabled} until COBOL captures are available"), the
 * {@link #byteForByteParity()} override below is annotated
 * {@code @Disabled} with a reason citing the COBOL capture procedure
 * documented in {@code java/MIGRATION_NOTES.md}. The harness skeleton
 * is unconditionally present so JUnit discovers and reports this
 * per-program test in CI from day one. The {@code @Disabled}
 * annotation will be removed in the same PR that commits
 * non-placeholder content under
 * {@code src/test/resources/golden/cbact03c/expected/}.</p>
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.account.CbAct03C
 * @since 25
 */
@DisplayName("CBACT03C \u2014 Sequential CARDXREF Reader Golden-Record Parity (DOUBLE-DISPLAY)")
public class CbAct03CGoldenTest extends GoldenRecordTest {

    /**
     * Identifier of the per-program fixture directory under
     * {@code src/test/resources/golden/}. Lowercase form of the COBOL
     * {@code PROGRAM-ID CBACT03C} per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention.
     */
    private static final String PROGRAM_DIR = "cbact03c";

    /**
     * Name of the input fixture under {@code app/data/ASCII/}: 50 card
     * cross-reference records, 50 bytes each per
     * {@code app/cpy/CVACT03Y.cpy} (record length 50:
     * {@code XREF-CARD-NUM} 16 + {@code XREF-CUST-ID} 9 +
     * {@code XREF-ACCT-ID} 11 + {@code FILLER} 14). Read directly via
     * {@link GoldenRecordTest#resolveAppDataPath(String)} &mdash; NOT
     * copied into this module per AAP &sect;0.4.1 and &sect;0.6.11.
     */
    private static final String CARDXREF_TXT = "cardxref.txt";

    /**
     * Name of the captured COBOL stdout trace (banners plus 50 &times; 2 =
     * 100 {@code DISPLAY CARD-XREF-RECORD} lines from the DOUBLE-DISPLAY
     * pattern per AAP &sect;0.7.1 &mdash; same as CBACT01C/CBCUS01C,
     * contrast with CBACT02C which displays each record only once).
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.account.CbAct03C}{@code .class}.
     * Referenced via fully-qualified class literal so this file's import
     * block remains minimal and restricted to {@link java.nio.file.Path}
     * plus the JUnit Jupiter API annotations ({@link DisplayName},
     * {@link Disabled}, {@link Test}). The fully-qualified class literal
     * compiles cleanly because {@code carddemo-tests} declares a
     * test-scope dependency on {@code carddemo-application} (transitive
     * via {@code carddemo-app}) in {@code java/carddemo-tests/pom.xml}.</p>
     */
    @Override
    protected Class<?> programClass() {
        return com.blitzy.carddemo.application.account.CbAct03C.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to
     * {@code <repo-root>/app/data/ASCII/cardxref.txt} resolved via
     * {@link GoldenRecordTest#resolveAppDataPath(String)}. This is the
     * canonical 50-record cross-reference fixture
     * ({@code XREF-CARD-NUM} + {@code XREF-CUST-ID} +
     * {@code XREF-ACCT-ID} + {@code FILLER}) per AAP &sect;0.4.1.</p>
     */
    @Override
    protected Path inputFile() {
        return resolveAppDataPath(CARDXREF_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to the captured COBOL stdout
     * trace at {@code src/test/resources/golden/cbact03c/expected/stdout.txt},
     * resolved via
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}.
     * Byte-for-byte equality is asserted by
     * {@link GoldenRecordTest#byteForByteParity()}.</p>
     */
    @Override
    protected Path expectedOutputFile() {
        return resolveExpectedOutputPath(PROGRAM_DIR, STDOUT_TXT);
    }

    /**
     * Byte-for-byte parity assertion, currently {@code @Disabled} pending
     * the COBOL CBACT03C baseline capture per AAP &sect;0.6.11 ("Initial
     * test scaffolding may use placeholder expected files marked
     * {@code @Disabled} until COBOL captures are available").
     *
     * <p>The {@code @Disabled} annotation will be removed in the same PR
     * that commits non-placeholder content under
     * {@code src/test/resources/golden/cbact03c/expected/} per the
     * capture procedure documented in {@code java/MIGRATION_NOTES.md}.
     * The method body delegates to
     * {@link GoldenRecordTest#byteForByteParity()} so the actual
     * byte-by-byte assertion logic remains centralised in the base
     * class.</p>
     *
     * <p><strong>Why the {@code @Test} annotation is re-declared on this
     * override</strong>: empirically verified against JUnit Jupiter
     * 5.13.1 (pinned in {@code java/pom.xml} dependencyManagement per
     * AAP &sect;0.5.1), the JUnit Platform's annotation lookup does NOT
     * inherit {@code @Test} when a subclass overrides a parent's
     * {@code @Test}-annotated method &mdash; running surefire with
     * {@code -Dtest=CbAct03CGoldenTest} produces "Tests run: 0" when
     * {@code @Test} is omitted from the override, but "Tests run: 1,
     * Skipped: 1" when re-declared. Without {@code @Test} here, this
     * test class would be silently dropped from the test suite,
     * defeating the AAP &sect;0.6.11 PR-gate purpose of the harness
     * skeleton. This pattern matches sibling
     * {@link CbAct02CGoldenTest}, {@link CbCus01CGoldenTest},
     * {@link CbStm03BGoldenTest}, and {@link DateValidatorGoldenTest}.</p>
     *
     * @throws Exception if the program under test, the
     *                   {@link GoldenRecordTest#runProgram(Class,
     *                   java.nio.file.Path, java.util.List)} hook, or
     *                   any {@link java.nio.file.Files#readAllBytes(
     *                   java.nio.file.Path)} call fails
     */
    @Override
    @Test
    @Disabled(
        "Awaiting COBOL CBACT03C baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md for the regeneration procedure. "
            + "The expected output captures the START/END banners plus "
            + "DISPLAY CARD-XREF-RECORD lines. CBACT03C uses the "
            + "DOUBLE-DISPLAY pattern per AAP \u00a70.7.1 (inner "
            + "DISPLAY CARD-XREF-RECORD at paragraph "
            + "1000-XREFFILE-GET-NEXT is ACTIVE at "
            + "app/cbl/CBACT03C.cbl:L96, combined with the outer-loop "
            + "DISPLAY at app/cbl/CBACT03C.cbl:L78), so the expected "
            + "stdout.txt contains exactly 100 record display lines "
            + "(50 records \u00d7 2 emissions), same as "
            + "CBACT01C/CBCUS01C and contrasting with CBACT02C's "
            + "SINGLE-DISPLAY (50 lines)."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
