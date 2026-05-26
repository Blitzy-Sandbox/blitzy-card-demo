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
 * Byte-for-byte golden-record parity test for {@code CBACT01C}
 * (Sequential ACCTFILE Reader, DOUBLE-DISPLAY pattern).
 *
 * <p><strong>Source COBOL:</strong> {@code app/cbl/CBACT01C.cbl} &mdash; the
 * {@code PROGRAM-ID CBACT01C} batch program (194 lines, &quot;Read and
 * print account data file&quot;) that opens the ACCTFILE (KSDS, INDEXED
 * organization, SEQUENTIAL access by {@code FD-ACCT-ID PIC 9(11)}),
 * iterates account records in ascending key order, {@code DISPLAY}s each
 * 300-byte {@code ACCOUNT-RECORD} (from copybook
 * {@code app/cpy/CVACT01Y.cpy}), and closes the file. The COBOL follows
 * the same simple I/O wrapper template as {@code CBACT02C} /
 * {@code CBACT03C} / {@code CBCUS01C}: paragraphs
 * {@code 0000-ACCTFILE-OPEN}, {@code 1000-ACCTFILE-GET-NEXT},
 * {@code 1100-DISPLAY-ACCT-RECORD}, {@code 9000-ACCTFILE-CLOSE},
 * {@code 9999-ABEND-PROGRAM}, {@code 9910-DISPLAY-IO-STATUS}. On any I/O
 * error (FILE STATUS not {@code "00"} on OPEN/CLOSE, or not {@code "00"}/
 * {@code "10"} on READ) the program abends via {@code CALL 'CEE3ABD'}
 * with ABCODE=999.</p>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.account.CbAct01C}. Per AAP
 * &sect;0.4.1 (program-by-program mapping) CBACT01C is translated into
 * the {@code application/account/} subpackage (verified against the
 * on-disk sibling folder structure: {@code CbAct01C.java},
 * {@code CbAct02C.java}, {@code CbAct03C.java}, {@code CbAct04C.java}
 * co-locate under {@code account/} per the AAP transformation table).</p>
 *
 * <p><strong>DOUBLE-DISPLAY anomaly preserved verbatim</strong> per AAP
 * &sect;0.7.1 (Minimal Change Clause &mdash; translate faithfully, do NOT
 * "fix" in this refactor): CBACT01C displays each successfully-read
 * {@code ACCOUNT-RECORD} <strong>TWICE</strong> &mdash; the inner
 * {@code PERFORM 1100-DISPLAY-ACCT-RECORD} inside paragraph
 * {@code 1000-ACCTFILE-GET-NEXT} is <em>active</em> (NOT commented out
 * as in the sibling {@code CBACT02C}). Two emission sites apply per
 * record on a successful read, but with structurally <em>different</em>
 * output formats (in contrast to {@code CBACT03C}, where both emissions
 * are identical full-record dumps):
 * <ol>
 *   <li>Emission #1 &mdash; inside {@code 1000-ACCTFILE-GET-NEXT} at
 *       {@code app/cbl/CBACT01C.cbl:L96}, which performs
 *       {@code 1100-DISPLAY-ACCT-RECORD} at
 *       {@code app/cbl/CBACT01C.cbl:L118-L131}:
 *       <pre>{@code
 *           IF  ACCTFILE-STATUS = '00'
 *               MOVE 0 TO APPL-RESULT
 *               PERFORM 1100-DISPLAY-ACCT-RECORD   <-- emission #1
 *           ELSE
 *       }</pre>
 *       Paragraph {@code 1100-DISPLAY-ACCT-RECORD} emits
 *       <strong>twelve</strong> lines per record: 11 labeled
 *       field-by-field {@code DISPLAY} statements (ACCT-ID,
 *       ACCT-ACTIVE-STATUS, ACCT-CURR-BAL, ACCT-CREDIT-LIMIT,
 *       ACCT-CASH-CREDIT-LIMIT, ACCT-OPEN-DATE, ACCT-EXPIRAION-DATE,
 *       ACCT-REISSUE-DATE, ACCT-CURR-CYC-CREDIT, ACCT-CURR-CYC-DEBIT,
 *       ACCT-GROUP-ID) plus one
 *       {@code "-------------------------------------------------"}
 *       separator line. Note: COBOL {@code 1100-DISPLAY-ACCT-RECORD}
 *       deliberately does <strong>NOT</strong> emit {@code ACCT-ADDR-ZIP}
 *       (a 12th field of the {@code ACCOUNT-RECORD} layout) &mdash; this
 *       omission is preserved verbatim by the Java translation and the
 *       captured COBOL output. Also note: the COBOL label
 *       {@code 'ACCT-EXPIRAION-DATE'} (missing &quot;T&quot; in
 *       &quot;EXPIRATION&quot;) is preserved character-for-character.</li>
 *   <li>Emission #2 &mdash; in the main {@code PERFORM UNTIL} loop at
 *       {@code app/cbl/CBACT01C.cbl:L78}, AFTER {@code GET-NEXT} returns
 *       without setting {@code END-OF-FILE = 'Y'}:
 *       <pre>{@code
 *           IF  END-OF-FILE = 'N'
 *               PERFORM 1000-ACCTFILE-GET-NEXT
 *               IF  END-OF-FILE = 'N'
 *                   DISPLAY ACCOUNT-RECORD         <-- emission #2
 *               END-IF
 *           END-IF
 *       }</pre>
 *       This emits a single {@code DISPLAY ACCOUNT-RECORD} line dumping
 *       the entire 300-byte {@code ACCOUNT-RECORD} buffer
 *       (per {@code app/cpy/CVACT01Y.cpy}).</li>
 * </ol>
 * The Java translation
 * ({@link com.blitzy.carddemo.application.account.CbAct01C}) faithfully
 * reproduces this DOUBLE-DISPLAY pattern so the captured COBOL
 * {@code stdout.txt} contains, per successfully-read record, exactly
 * 13 lines: 12 from {@code 1100-DISPLAY-ACCT-RECORD} (11 labeled
 * fields + 1 separator) plus 1 from the outer-loop
 * {@code DISPLAY ACCOUNT-RECORD}. Same per-record emission count as the
 * sibling {@code CBACT03C} and {@code CBCUS01C} DOUBLE-DISPLAY programs;
 * contrast with {@code CBACT02C} which uses SINGLE-DISPLAY. This anomaly
 * is documented in {@code java/MIGRATION_NOTES.md}.</p>
 *
 * <p><strong>Input fixture:</strong> {@code app/data/ASCII/acctdata.txt}
 * (50 account records, 300 bytes each per
 * {@code app/cpy/CVACT01Y.cpy:§ACCOUNT-RECORD} layout
 * {@code ACCT-ID PIC 9(11)} + {@code ACCT-ACTIVE-STATUS PIC X(01)} +
 * {@code ACCT-CURR-BAL PIC S9(10)V99} +
 * {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} +
 * {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99} +
 * {@code ACCT-OPEN-DATE PIC X(10)} +
 * {@code ACCT-EXPIRAION-DATE PIC X(10)} +
 * {@code ACCT-REISSUE-DATE PIC X(10)} +
 * {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} +
 * {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} +
 * {@code ACCT-ADDR-ZIP PIC X(10)} +
 * {@code ACCT-GROUP-ID PIC X(10)} +
 * {@code FILLER PIC X(178)} = 300 bytes). The fixture is read directly
 * from {@code app/} via the
 * {@link GoldenRecordTest#resolveAppDataPath(String)} helper &mdash; NOT
 * copied into {@code java/carddemo-tests/} per AAP &sect;0.4.1 and
 * &sect;0.6.11. The base harness byte comparison handles the 300-byte
 * fixed-width record layout transparently; no record-level parsing is
 * required at this layer.</p>
 *
 * <p><strong>Expected output:</strong>
 * {@code src/test/resources/golden/cbact01c/expected/stdout.txt} captures
 * the COBOL {@code DISPLAY} entries: the
 * {@code "START OF EXECUTION OF PROGRAM CBACT01C"} banner
 * ({@code app/cbl/CBACT01C.cbl:L71}), 50 &times; 13 =
 * <strong>650</strong> record DISPLAY lines (50 records &times; 13
 * emissions per the DOUBLE-DISPLAY pattern documented above: 11 labeled
 * fields + 1 separator + 1 full-record dump), and the
 * {@code "END OF EXECUTION OF PROGRAM CBACT01C"} banner
 * ({@code app/cbl/CBACT01C.cbl:L85}). Account balances, credit limits,
 * and cash-credit limits appear in the captured COBOL output as
 * COBOL-formatted signed-overpunch numerics (the
 * {@code PIC S9(10)V99} display edit) verbatim because COBOL
 * {@code DISPLAY} performs no transformation beyond the implied
 * edit-mask conversion; the Java translation's SLF4J appender mirrors
 * the COBOL bytes exactly so byte-equality holds. No PAN appears in
 * {@code ACCTFILE} (PAN is in CARDFILE / CARDXREF only), so the AAP
 * &sect;0.7.2 PAN-masking rule does not affect this capture.</p>
 *
 * <p><strong>Constructor wiring:</strong>
 * {@link com.blitzy.carddemo.application.account.CbAct01C} has a single
 * constructor dependency on
 * {@code com.blitzy.carddemo.domain.port.AccountRepository}, which the
 * base harness {@link GoldenRecordTest#runProgram(Class,
 * java.nio.file.Path, java.util.List)} hook resolves to a
 * {@code FileAccountRepository} adapter wired against the
 * {@link #inputFile()} fixture. No override of {@code runProgram(...)}
 * is required in this subclass &mdash; the default wiring suffices.</p>
 *
 * <p>This test is the <strong>NON-NEGOTIABLE PR gate</strong> per AAP
 * &sect;0.6.11. Any deviation in the {@code DISPLAY} byte sequence,
 * record ordering (ascending {@code FD-ACCT-ID}), banner text, or
 * (critically) the DOUBLE-vs-SINGLE display count breaks parity and
 * blocks the PR.</p>
 *
 * <p><strong>Scaffolding state</strong>: per AAP &sect;0.6.11 ("Initial
 * test scaffolding may use placeholder expected files marked
 * {@code @Disabled} until COBOL captures are available; the harness
 * skeleton, base class, and per-program test classes are created
 * unconditionally"), the {@link #byteForByteParity()} override below
 * is annotated {@code @Disabled} with a reason citing the COBOL capture
 * procedure documented in {@code java/MIGRATION_NOTES.md}. The harness
 * skeleton is unconditionally present so JUnit discovers and reports
 * this per-program test in CI from day one. The {@code @Disabled}
 * annotation will be removed in the same PR that commits
 * non-placeholder content under
 * {@code src/test/resources/golden/cbact01c/expected/}.</p>
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.account.CbAct01C
 * @since 25
 */
@DisplayName("CBACT01C \u2014 Sequential ACCTFILE Reader Golden-Record Parity (DOUBLE-DISPLAY)")
public class CbAct01CGoldenTest extends GoldenRecordTest {

    /**
     * Identifier of the per-program fixture directory under
     * {@code src/test/resources/golden/}. Lowercase form of the COBOL
     * {@code PROGRAM-ID CBACT01C} per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention.
     */
    private static final String PROGRAM_DIR = "cbact01c";

    /**
     * Name of the input fixture under {@code app/data/ASCII/}: 50 account
     * records, 300 bytes each per {@code app/cpy/CVACT01Y.cpy} (record
     * length 300: {@code ACCT-ID} 11 + {@code ACCT-ACTIVE-STATUS} 1 +
     * {@code ACCT-CURR-BAL} 12 + {@code ACCT-CREDIT-LIMIT} 12 +
     * {@code ACCT-CASH-CREDIT-LIMIT} 12 + {@code ACCT-OPEN-DATE} 10 +
     * {@code ACCT-EXPIRAION-DATE} 10 + {@code ACCT-REISSUE-DATE} 10 +
     * {@code ACCT-CURR-CYC-CREDIT} 12 + {@code ACCT-CURR-CYC-DEBIT} 12 +
     * {@code ACCT-ADDR-ZIP} 10 + {@code ACCT-GROUP-ID} 10 +
     * {@code FILLER} 178 = 300). Read directly via
     * {@link GoldenRecordTest#resolveAppDataPath(String)} &mdash; NOT
     * copied into this module per AAP &sect;0.4.1 and &sect;0.6.11.
     */
    private static final String ACCTDATA_TXT = "acctdata.txt";

    /**
     * Name of the captured COBOL stdout trace (START/END banners plus
     * 50 &times; 13 = 650 {@code DISPLAY} lines from the DOUBLE-DISPLAY
     * pattern per AAP &sect;0.7.1: each record contributes 11 labeled
     * field {@code DISPLAY}s + 1 separator line from
     * {@code 1100-DISPLAY-ACCT-RECORD} plus 1 full 300-byte
     * {@code DISPLAY ACCOUNT-RECORD} from the outer
     * {@code PERFORM UNTIL} loop). Same emission count pattern as
     * CBACT03C/CBCUS01C and contrasting with CBACT02C's SINGLE-DISPLAY.
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.account.CbAct01C}{@code .class}.
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
        return com.blitzy.carddemo.application.account.CbAct01C.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to
     * {@code <repo-root>/app/data/ASCII/acctdata.txt} resolved via
     * {@link GoldenRecordTest#resolveAppDataPath(String)}. This is the
     * canonical 50-account fixture (300 bytes &times; 50 records =
     * 15,000 record bytes plus newlines, with the 14-field
     * {@code ACCOUNT-RECORD} layout per {@code app/cpy/CVACT01Y.cpy})
     * per AAP &sect;0.4.1.</p>
     */
    @Override
    protected Path inputFile() {
        return resolveAppDataPath(ACCTDATA_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to the captured COBOL stdout
     * trace at {@code src/test/resources/golden/cbact01c/expected/stdout.txt},
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
     * the COBOL CBACT01C baseline capture per AAP &sect;0.6.11 ("Initial
     * test scaffolding may use placeholder expected files marked
     * {@code @Disabled} until COBOL captures are available").
     *
     * <p>The {@code @Disabled} annotation will be removed in the same PR
     * that commits non-placeholder content under
     * {@code src/test/resources/golden/cbact01c/expected/} per the
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
     * {@code -Dtest=CbAct01CGoldenTest} produces "Tests run: 0" when
     * {@code @Test} is omitted from the override, but "Tests run: 1,
     * Skipped: 1" when re-declared. Without {@code @Test} here, this
     * test class would be silently dropped from the test suite,
     * defeating the AAP &sect;0.6.11 PR-gate purpose of the harness
     * skeleton. This pattern matches sibling
     * {@link CbAct02CGoldenTest}, {@link CbAct03CGoldenTest},
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
        "Awaiting COBOL CBACT01C baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md for the regeneration procedure. "
            + "The expected output captures the START/END banners plus "
            + "DISPLAY lines from CBACT01C. CBACT01C uses the "
            + "DOUBLE-DISPLAY pattern per AAP \u00a70.7.1 (inner "
            + "PERFORM 1100-DISPLAY-ACCT-RECORD at paragraph "
            + "1000-ACCTFILE-GET-NEXT is ACTIVE at "
            + "app/cbl/CBACT01C.cbl:L96, combined with the outer-loop "
            + "DISPLAY ACCOUNT-RECORD at app/cbl/CBACT01C.cbl:L78), so "
            + "the expected stdout.txt contains exactly 50 \u00d7 13 = "
            + "650 record display lines (11 labeled field DISPLAYs + 1 "
            + "separator from 1100-DISPLAY-ACCT-RECORD + 1 full "
            + "300-byte DISPLAY ACCOUNT-RECORD per record), same "
            + "emission-count pattern as CBACT03C/CBCUS01C and "
            + "contrasting with CBACT02C's SINGLE-DISPLAY (50 lines). "
            + "Note: 1100-DISPLAY-ACCT-RECORD deliberately omits "
            + "ACCT-ADDR-ZIP (preserved verbatim) and labels the "
            + "expiration field 'ACCT-EXPIRAION-DATE' with the "
            + "original COBOL typo (preserved verbatim)."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
