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
 * Byte-for-byte golden-record parity test for {@code CBCUS01C}
 * (Sequential CUSTFILE Reader).
 *
 * <p><strong>Source COBOL:</strong> {@code app/cbl/CBCUS01C.cbl} &mdash; the
 * {@code PROGRAM-ID CBCUS01C} batch program that opens the CUSTFILE
 * (KSDS, INDEXED organization, SEQUENTIAL access by {@code FD-CUST-ID
 * PIC 9(09)}), iterates customer records in ascending key order,
 * {@code DISPLAY}s each 500-byte {@code CUSTOMER-RECORD} (from copybook
 * {@code app/cpy/CVCUS01Y.cpy}), and closes the file. The COBOL follows
 * the same simple I/O wrapper template as {@code CBACT01C} / {@code CBACT02C}
 * / {@code CBACT03C}: paragraphs {@code 0000-CUSTFILE-OPEN},
 * {@code 1000-CUSTFILE-GET-NEXT}, {@code 9000-CUSTFILE-CLOSE},
 * {@code Z-ABEND-PROGRAM}, {@code Z-DISPLAY-IO-STATUS}. On any I/O error
 * (FILE STATUS not {@code "00"} on OPEN/CLOSE, or not {@code "00"}/
 * {@code "10"} on READ) the program abends via {@code CALL 'CEE3ABD'} with
 * ABCODE=999.</p>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.customer.CbCus01C}. Per AAP
 * &sect;0.4.1 (program-by-program mapping) CBCUS01C is translated into the
 * {@code application/customer/} subpackage (NOT {@code application/account/}
 * &mdash; verify the import-path alignment against the on-disk sibling
 * folder structure when this test is enabled).</p>
 *
 * <p><strong>Preserved COBOL anomaly</strong> per AAP &sect;0.7.1 (Minimal
 * Change Clause &mdash; translate faithfully, do NOT "fix" in this refactor):
 * the original COBOL displays {@code CUSTOMER-RECORD} TWICE per successful
 * read &mdash; once inside paragraph {@code 1000-CUSTFILE-GET-NEXT} at
 * {@code app/cbl/CBCUS01C.cbl:L96}, and again from the main loop at
 * {@code app/cbl/CBCUS01C.cbl:L78}. The Java translation
 * ({@link com.blitzy.carddemo.application.customer.CbCus01C}) faithfully
 * reproduces this double display so the captured COBOL {@code stdout.txt}
 * and the Java-produced {@code stdout.txt} remain byte-equal. The anomaly
 * is logged in {@code java/MIGRATION_NOTES.md} for follow-up consideration
 * outside the migration scope.</p>
 *
 * <p><strong>Input fixture:</strong> {@code app/data/ASCII/custdata.txt}
 * (50 customer records, 500 bytes each per
 * {@code app/cpy/CVCUS01Y.cpy:§CUSTOMER-RECORD}). The fixture is read
 * directly from {@code app/} via the
 * {@link GoldenRecordTest#resolveAppDataPath(String)} helper &mdash; NOT
 * copied into {@code java/carddemo-tests/} per AAP &sect;0.4.1 and
 * &sect;0.6.11. The base harness byte comparison handles the 500-byte
 * fixed-width record layout transparently; no record-level parsing is
 * required at this layer.</p>
 *
 * <p><strong>Expected output:</strong>
 * {@code src/test/resources/golden/cbcus01c/expected/stdout.txt}
 * captures the COBOL {@code DISPLAY} entries (the
 * {@code "START OF EXECUTION OF PROGRAM CBCUS01C"} banner, 50&times;2 =
 * 100 {@code CUSTOMER-RECORD} display lines per the duplicate-DISPLAY
 * anomaly noted above, and the
 * {@code "END OF EXECUTION OF PROGRAM CBCUS01C"} banner). The exact
 * single- versus double-display count MUST be reverified against
 * {@code app/cbl/CBCUS01C.cbl} when the COBOL capture is committed, since
 * the COBOL source &mdash; not this Javadoc &mdash; is the source of
 * truth.</p>
 *
 * <p><strong>Constructor wiring:</strong>
 * {@link com.blitzy.carddemo.application.customer.CbCus01C} has a single
 * constructor dependency on
 * {@code com.blitzy.carddemo.domain.port.CustomerRepository}, which
 * the base harness {@link GoldenRecordTest#runProgram(Class,
 * java.nio.file.Path, java.util.List)} hook resolves to a
 * {@code FileCustomerRepository} adapter wired against the
 * {@link #inputFile()} fixture. No override of {@code runProgram(...)} is
 * required in this subclass &mdash; the default wiring suffices.</p>
 *
 * <p>This test is the <strong>NON-NEGOTIABLE PR gate</strong> per AAP
 * &sect;0.6.11. Any deviation in the {@code DISPLAY} byte sequence,
 * record ordering (ascending {@code FD-CUST-ID}), or banner text
 * breaks parity and blocks the PR.</p>
 *
 * <p><strong>Scaffolding state</strong>: per AAP &sect;0.6.11 ("Initial
 * test scaffolding may use placeholder expected files marked
 * {@code @Disabled} until COBOL captures are available"), the
 * {@link #byteForByteParity()} override below is annotated
 * {@code @Disabled} with a reason citing the COBOL capture procedure
 * documented in {@code java/MIGRATION_NOTES.md}. The harness skeleton
 * is unconditionally present so JUnit discovers and reports this
 * per-program test in CI from day one. The {@code @Disabled} annotation
 * will be removed in the same PR that commits non-placeholder content
 * under {@code src/test/resources/golden/cbcus01c/expected/}.</p>
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.customer.CbCus01C
 * @since 25
 */
@DisplayName("CBCUS01C \u2014 Sequential CUSTFILE Reader Golden-Record Parity")
public class CbCus01CGoldenTest extends GoldenRecordTest {

    /**
     * Identifier of the per-program fixture directory under
     * {@code src/test/resources/golden/}. Lowercase form of the COBOL
     * {@code PROGRAM-ID CBCUS01C} per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention.
     */
    private static final String PROGRAM_DIR = "cbcus01c";

    /**
     * Name of the input fixture under {@code app/data/ASCII/}: 50 customer
     * records, 500 bytes each per {@code app/cpy/CVCUS01Y.cpy} (record
     * length 500). Read directly via
     * {@link GoldenRecordTest#resolveAppDataPath(String)} &mdash; NOT
     * copied into this module per AAP &sect;0.4.1 and &sect;0.6.11.
     */
    private static final String CUSTDATA_TXT = "custdata.txt";

    /**
     * Name of the captured COBOL stdout trace (banners plus
     * {@code DISPLAY CUSTOMER-RECORD} lines from the
     * {@code 1000-CUSTFILE-GET-NEXT} paragraph and the main
     * {@code PERFORM UNTIL} loop).
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.customer.CbCus01C}{@code .class}.
     * Referenced via fully-qualified class literal so this file's import
     * block remains minimal and restricted to {@link java.nio.file.Path}
     * plus the JUnit Jupiter API annotations ({@link DisplayName},
     * {@link Disabled}, {@link Test}). The fully-qualified class literal
     * compiles cleanly because {@code carddemo-tests} declares a test-scope
     * dependency on {@code carddemo-application} in
     * {@code java/carddemo-tests/pom.xml}.</p>
     */
    @Override
    protected Class<?> programClass() {
        return com.blitzy.carddemo.application.customer.CbCus01C.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to
     * {@code <repo-root>/app/data/ASCII/custdata.txt} resolved via
     * {@link GoldenRecordTest#resolveAppDataPath(String)}. This is the
     * canonical 50-customer fixture (500 bytes &times; 50 records =
     * 25,000 record bytes plus newlines) per AAP &sect;0.4.1.</p>
     */
    @Override
    protected Path inputFile() {
        return resolveAppDataPath(CUSTDATA_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to the captured COBOL
     * stdout trace at
     * {@code src/test/resources/golden/cbcus01c/expected/stdout.txt},
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
     * the COBOL CBCUS01C baseline capture per AAP &sect;0.6.11 ("Initial
     * test scaffolding may use placeholder expected files marked
     * {@code @Disabled} until COBOL captures are available").
     *
     * <p>The {@code @Disabled} annotation will be removed in the same PR
     * that commits non-placeholder content under
     * {@code src/test/resources/golden/cbcus01c/expected/} per the capture
     * procedure documented in {@code java/MIGRATION_NOTES.md}. The method
     * body delegates to {@link GoldenRecordTest#byteForByteParity()} so
     * the actual byte-by-byte assertion logic remains centralised in the
     * base class.</p>
     *
     * <p><strong>Why the {@code @Test} annotation is re-declared on this
     * override</strong>: empirically verified against JUnit Jupiter 5.13.1
     * (pinned in {@code java/pom.xml} dependencyManagement per AAP
     * &sect;0.5.1), the JUnit Platform's annotation lookup does NOT
     * inherit {@code @Test} when a subclass overrides a parent's
     * {@code @Test}-annotated method &mdash; running surefire with
     * {@code -Dtest=CbCus01CGoldenTest} produces "Tests run: 0" when
     * {@code @Test} is omitted from the override, but "Tests run: 1,
     * Skipped: 1" when re-declared. Without {@code @Test} here, this
     * test class would be silently dropped from the test suite,
     * defeating the AAP &sect;0.6.11 PR-gate purpose of the harness
     * skeleton. This pattern matches sibling
     * {@link CbStm03BGoldenTest} and
     * {@link DateValidatorGoldenTest}.</p>
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
        "Awaiting COBOL CBCUS01C baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md for the regeneration procedure. "
            + "The expected output captures the START/END banners plus "
            + "DISPLAY CUSTOMER-RECORD lines. CBCUS01C displays each "
            + "record TWICE per successful read (preserved COBOL anomaly "
            + "at app/cbl/CBCUS01C.cbl:L78 and L96 per AAP \u00a70.7.1) "
            + "so the expected stdout.txt contains 50*2 = 100 record "
            + "display lines, not 50."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
