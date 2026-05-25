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
 * Byte-for-byte golden-record parity test for {@code CBACT02C}
 * (Sequential CARDFILE Reader, SINGLE-DISPLAY pattern).
 *
 * <p><strong>Source COBOL:</strong> {@code app/cbl/CBACT02C.cbl} &mdash; the
 * {@code PROGRAM-ID CBACT02C} batch program that opens the CARDFILE
 * (KSDS, INDEXED organization, SEQUENTIAL access by
 * {@code FD-CARD-NUM PIC X(16)}), iterates card records in ascending key
 * order, {@code DISPLAY}s each 150-byte {@code CARD-RECORD} (from copybook
 * {@code app/cpy/CVACT02Y.cpy}), and closes the file. The COBOL follows
 * the same simple I/O wrapper template as {@code CBACT01C} / {@code CBACT03C}
 * / {@code CBCUS01C}: paragraphs {@code 0000-CARDFILE-OPEN},
 * {@code 1000-CARDFILE-GET-NEXT}, {@code 9000-CARDFILE-CLOSE},
 * {@code 9999-ABEND-PROGRAM}, {@code 9910-DISPLAY-IO-STATUS}. On any I/O
 * error (FILE STATUS not {@code "00"} on OPEN/CLOSE, or not {@code "00"}/
 * {@code "10"} on READ) the program abends via {@code CALL 'CEE3ABD'}
 * with ABCODE=999.</p>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.account.CbAct02C}. Per AAP
 * &sect;0.4.1 (program-by-program mapping) CBACT02C is translated into
 * the {@code application/account/} subpackage (verified against the
 * on-disk sibling folder structure: {@code CbAct01C.java},
 * {@code CbAct02C.java}, {@code CbAct03C.java}, {@code CbAct04C.java}
 * co-locate under {@code account/} per the AAP transformation table).</p>
 *
 * <p><strong>SINGLE-DISPLAY pattern preserved verbatim</strong> per AAP
 * &sect;0.7.1 (Minimal Change Clause &mdash; translate faithfully, do NOT
 * "fix" in this refactor): unlike sibling sequential dump programs
 * {@code CBACT01C}, {@code CBACT03C}, and {@code CBCUS01C} (which display
 * each record TWICE per successful read), CBACT02C displays each
 * {@code CARD-RECORD} <strong>exactly once</strong>. The inner
 * {@code DISPLAY CARD-RECORD} statement inside paragraph
 * {@code 1000-CARDFILE-GET-NEXT} is <em>commented out</em> with a
 * leading {@code '*'} at column 7 in the source &mdash;
 * {@code app/cbl/CBACT02C.cbl} line 96 reads:
 * <pre>{@code
 *     IF  CARDFILE-STATUS = '00'
 *         MOVE 0 TO APPL-RESULT
 * *        DISPLAY CARD-RECORD              <-- COMMENTED OUT (line 96)
 *     ELSE
 * }</pre>
 * The only active DISPLAY of the card record is the one at
 * {@code app/cbl/CBACT02C.cbl} line 78 inside the main
 * {@code PERFORM UNTIL} loop. The Java translation
 * ({@link com.blitzy.carddemo.application.account.CbAct02C}) faithfully
 * reproduces this SINGLE-DISPLAY pattern so the captured COBOL
 * {@code stdout.txt} contains exactly 50 {@code CARD-RECORD} display
 * entries (NOT 100 as in {@code CBACT01C}/{@code CBACT03C}/{@code CBCUS01C}).
 * This divergence is also documented in
 * {@code java/MIGRATION_NOTES.md}.</p>
 *
 * <p><strong>Input fixture:</strong> {@code app/data/ASCII/carddata.txt}
 * (50 card records, 150 bytes each per
 * {@code app/cpy/CVACT02Y.cpy:§CARD-RECORD} layout
 * {@code CARD-NUM PIC X(16)} + {@code CARD-ACCT-ID PIC 9(11)} +
 * {@code CARD-CVV-CD PIC 9(03)} + {@code CARD-EMBOSSED-NAME PIC X(50)} +
 * {@code CARD-EXPIRAION-DATE PIC X(10)} + {@code CARD-ACTIVE-STATUS PIC X(01)} +
 * {@code FILLER PIC X(59)} = 150 bytes). The fixture is read directly
 * from {@code app/} via the
 * {@link GoldenRecordTest#resolveAppDataPath(String)} helper &mdash; NOT
 * copied into {@code java/carddemo-tests/} per AAP &sect;0.4.1 and
 * &sect;0.6.11. The base harness byte comparison handles the 150-byte
 * fixed-width record layout transparently; no record-level parsing is
 * required at this layer.</p>
 *
 * <p><strong>Expected output:</strong>
 * {@code src/test/resources/golden/cbact02c/expected/stdout.txt} captures
 * the COBOL {@code DISPLAY} entries: the
 * {@code "START OF EXECUTION OF PROGRAM CBACT02C"} banner
 * ({@code app/cbl/CBACT02C.cbl} line 71), <strong>50</strong> single
 * {@code DISPLAY CARD-RECORD} lines (per the SINGLE-DISPLAY pattern
 * documented above, NOT 100 as in CBACT01C/CBACT03C), and the
 * {@code "END OF EXECUTION OF PROGRAM CBACT02C"} banner
 * ({@code app/cbl/CBACT02C.cbl} line 85). The PAN (card number) appears
 * in the captured COBOL output verbatim because COBOL {@code DISPLAY}
 * has no masking concept; the Java translation's SLF4J appender
 * mirrors the COBOL bytes exactly so byte-equality holds. (The PAN
 * masking AAP rule applies to operational logs surfaced to humans, not
 * to the golden-record capture which is a controlled test artefact.)</p>
 *
 * <p><strong>Constructor wiring:</strong>
 * {@link com.blitzy.carddemo.application.account.CbAct02C} has a single
 * constructor dependency on
 * {@code com.blitzy.carddemo.domain.port.CardRepository}, which the base
 * harness {@link GoldenRecordTest#runProgram(Class,
 * java.nio.file.Path, java.util.List)} hook resolves to a
 * {@code FileCardRepository} adapter wired against the
 * {@link #inputFile()} fixture. No override of {@code runProgram(...)}
 * is required in this subclass &mdash; the default wiring suffices.</p>
 *
 * <p>This test is the <strong>NON-NEGOTIABLE PR gate</strong> per AAP
 * &sect;0.6.11. Any deviation in the {@code DISPLAY} byte sequence,
 * record ordering (ascending {@code FD-CARD-NUM}), banner text, or
 * (critically) the SINGLE-vs-DOUBLE display count breaks parity and
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
 * {@code src/test/resources/golden/cbact02c/expected/}.</p>
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.account.CbAct02C
 * @since 25
 */
@DisplayName("CBACT02C \u2014 Sequential CARDFILE Reader Golden-Record Parity (SINGLE-DISPLAY)")
public class CbAct02CGoldenTest extends GoldenRecordTest {

    /**
     * Identifier of the per-program fixture directory under
     * {@code src/test/resources/golden/}. Lowercase form of the COBOL
     * {@code PROGRAM-ID CBACT02C} per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention.
     */
    private static final String PROGRAM_DIR = "cbact02c";

    /**
     * Name of the input fixture under {@code app/data/ASCII/}: 50 card
     * records, 150 bytes each per {@code app/cpy/CVACT02Y.cpy} (record
     * length 150). Read directly via
     * {@link GoldenRecordTest#resolveAppDataPath(String)} &mdash; NOT
     * copied into this module per AAP &sect;0.4.1 and &sect;0.6.11.
     */
    private static final String CARDDATA_TXT = "carddata.txt";

    /**
     * Name of the captured COBOL stdout trace (banners plus 50 single
     * {@code DISPLAY CARD-RECORD} lines from the main
     * {@code PERFORM UNTIL} loop &mdash; SINGLE-DISPLAY pattern per AAP
     * &sect;0.7.1, contrast with CBACT01C/CBACT03C/CBCUS01C which
     * display each record twice).
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.account.CbAct02C}{@code .class}.
     * Referenced via fully-qualified class literal so this file's import
     * block remains minimal and restricted to {@link java.nio.file.Path}
     * plus the JUnit Jupiter API annotations ({@link DisplayName},
     * {@link Disabled}, {@link Test}). The fully-qualified class literal
     * compiles cleanly because {@code carddemo-tests} declares a
     * test-scope dependency on {@code carddemo-application} in
     * {@code java/carddemo-tests/pom.xml}.</p>
     */
    @Override
    protected Class<?> programClass() {
        return com.blitzy.carddemo.application.account.CbAct02C.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to
     * {@code <repo-root>/app/data/ASCII/carddata.txt} resolved via
     * {@link GoldenRecordTest#resolveAppDataPath(String)}. This is the
     * canonical 50-card fixture (150 bytes &times; 50 records =
     * 7,500 record bytes plus newlines) per AAP &sect;0.4.1.</p>
     */
    @Override
    protected Path inputFile() {
        return resolveAppDataPath(CARDDATA_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to the captured COBOL stdout
     * trace at {@code src/test/resources/golden/cbact02c/expected/stdout.txt},
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
     * the COBOL CBACT02C baseline capture per AAP &sect;0.6.11 ("Initial
     * test scaffolding may use placeholder expected files marked
     * {@code @Disabled} until COBOL captures are available").
     *
     * <p>The {@code @Disabled} annotation will be removed in the same PR
     * that commits non-placeholder content under
     * {@code src/test/resources/golden/cbact02c/expected/} per the
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
     * {@code -Dtest=CbAct02CGoldenTest} produces "Tests run: 0" when
     * {@code @Test} is omitted from the override, but "Tests run: 1,
     * Skipped: 1" when re-declared. Without {@code @Test} here, this
     * test class would be silently dropped from the test suite,
     * defeating the AAP &sect;0.6.11 PR-gate purpose of the harness
     * skeleton. This pattern matches sibling
     * {@link CbCus01CGoldenTest}, {@link CbStm03BGoldenTest}, and
     * {@link DateValidatorGoldenTest}.</p>
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
        "Awaiting COBOL CBACT02C baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md for the regeneration procedure. "
            + "The expected output captures the START/END banners plus "
            + "DISPLAY CARD-RECORD lines. CBACT02C uses the SINGLE-DISPLAY "
            + "pattern per AAP \u00a70.7.1 (inner DISPLAY CARD-RECORD at "
            + "paragraph 1000-CARDFILE-GET-NEXT is commented out at "
            + "app/cbl/CBACT02C.cbl:L96), so the expected stdout.txt "
            + "contains exactly 50 record display lines, NOT 100 as in "
            + "CBACT01C/CBACT03C/CBCUS01C."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
