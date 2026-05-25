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
 * Byte-for-byte golden-record parity test for {@code CBSTM03B}
 * (Callable File-Services Subroutine).
 *
 * <p><strong>Source COBOL:</strong> {@code app/cbl/CBSTM03B.CBL} &mdash; the
 * {@code PROGRAM-ID CBSTM03B} callable subprogram that provides
 * {@code OPEN}/{@code READ}/{@code READ-K}/{@code CLOSE} primitives for
 * four logical datasets: TRNXFILE (transaction-by-card sequential),
 * XREFFILE (card cross-reference sequential), CUSTFILE (customer random by
 * key), and ACCTFILE (account random by key). CBSTM03B is NOT a standalone
 * main program; it is invoked via
 * {@code CALL "CBSTM03B" USING LK-M03B-AREA} by {@link
 * com.blitzy.carddemo.application.statement.CbStm03A} (the statement-generation
 * driver). The COBOL {@code LINKAGE SECTION} {@code LK-M03B-AREA} carries:
 * <ul>
 *   <li>{@code LK-M03B-DD PIC X(08)} &mdash; file id literal ({@code "TRNXFILE"},
 *       {@code "XREFFILE"}, {@code "CUSTFILE"}, {@code "ACCTFILE"})</li>
 *   <li>{@code LK-M03B-OPER PIC X(01)} &mdash; operation code ({@code 'O'}/{@code 'C'}/
 *       {@code 'R'}/{@code 'K'}/{@code 'W'}/{@code 'Z'})</li>
 *   <li>{@code LK-M03B-RC PIC X(02)} &mdash; FILE STATUS return code
 *       ({@code "00"}/{@code "10"}/{@code "23"}/other)</li>
 *   <li>{@code LK-M03B-KEY PIC X(25)} &mdash; random-access key for keyed READ</li>
 *   <li>{@code LK-M03B-KEY-LN PIC S9(4)} &mdash; key length</li>
 *   <li>{@code LK-M03B-FLDT PIC X(1000)} &mdash; 1000-byte payload buffer</li>
 * </ul>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.statement.CbStm03B}. Per AAP &sect;0.4.1
 * (program-by-program mapping checklist) CBSTM03B is translated as a <em>utility
 * class</em> exposing typed public methods ({@code openFile}, {@code closeFile},
 * {@code readNextTransaction}, {@code readNextXref}, {@code readCustomerByKey},
 * {@code readAccountByKey}, {@code dispatch}) that decompose the
 * {@code WS-M03B-AREA} dispatch parameter into idiomatic Java calls. Sealed
 * taxonomies {@code Cbstm03BOperation} and {@code Cbstm03BFileId} model the
 * closed value spaces of {@code LK-M03B-OPER} (88-levels) and {@code LK-M03B-DD}
 * (discrete file identifiers) for compiler-enforced exhaustiveness per AAP
 * &sect;0.1.3 and &sect;0.6.10.
 *
 * <p><strong>Test scenario</strong> (programmatic CALL sequence): because
 * CBSTM03B is a subroutine, the "input fixture" is a synthesised scenario file
 * {@code input_calls.txt} co-located under
 * {@code src/test/resources/golden/cbstm03b/expected/} per the
 * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)} convention
 * (subroutine-style fixtures place scenario inputs alongside their expected
 * outputs because the scenario is a synthesised harness artifact, NOT a captured
 * runtime input). The harness {@link GoldenRecordTest#runProgram(Class,
 * java.nio.file.Path, java.util.List)} hook (to be implemented in a follow-on
 * revision or overridden in this subclass when the COBOL capture lands)
 * interprets each line of {@code input_calls.txt} as a CALL description (file
 * id, operation, optional key) and invokes the corresponding typed method on
 * {@link com.blitzy.carddemo.application.statement.CbStm03B}, accumulating
 * each invocation's FILE STATUS return code and payload bytes into the
 * actual-output buffer for byte-by-byte comparison against the captured
 * COBOL output at {@code stdout.txt}.
 *
 * <p><strong>FILE STATUS preservation</strong> per AAP &sect;0.7.1 (Minimal
 * Change Clause): the Java translation preserves the COBOL FILE STATUS
 * semantics exactly &mdash; {@code "00"} success, {@code "10"} end-of-file,
 * {@code "23"} record-not-found, {@code "35"} file-not-found &mdash; mapped
 * to integer return codes ({@code RC_OK=0}, {@code RC_EOF=16},
 * {@code RC_NOT_FOUND=23}, {@code RC_ERROR=12}) declared on
 * {@link com.blitzy.carddemo.application.statement.CbStm03B}. The test
 * scenario MUST exercise each return-code path so a regression in the
 * COBOL-to-int mapping is detected at PR time.
 *
 * <p><strong>Operation code coverage</strong>: per CBSTM03B.CBL lines
 * 102&ndash;108 there are six declared 88-level operation codes
 * ({@code OPEN}/{@code CLOSE}/{@code READ}/{@code READ-K}/{@code WRITE}/
 * {@code REWRITE}); the four paragraphs (1000-TRNXFILE-PROC, 2000-XREFFILE-PROC,
 * 3000-CUSTFILE-PROC, 4000-ACCTFILE-PROC) only dispatch the first four
 * ({@code OPEN}, {@code CLOSE}, {@code READ}, {@code READ-K}). The COBOL
 * {@code WRITE} and {@code REWRITE} codes are declared-but-unused dead code;
 * per AAP &sect;0.7.1 the Java translation preserves them faithfully (with
 * the dead-code note logged in {@code MIGRATION_NOTES.md}). The activation
 * scenario therefore only needs to drive the four active dispatches across
 * the four files.
 *
 * <p>This test is the <strong>NON-NEGOTIABLE PR gate</strong> per AAP
 * &sect;0.6.11. Any deviation in the FILE STATUS return-code mapping,
 * payload-buffer (1000-byte {@code LK-M03B-FLDT}) layout, or
 * {@code EVALUATE LK-M03B-DD} dispatch outcome breaks parity and blocks
 * the PR.
 *
 * <p><strong>Scaffolding state</strong>: per AAP &sect;0.6.11 ("Initial
 * test scaffolding may use placeholder expected files marked
 * {@code @Disabled} until COBOL captures are available"), the
 * {@link #byteForByteParity()} override below is annotated
 * {@code @Disabled} with a detailed activation checklist citing the
 * COBOL capture procedure documented in {@code java/MIGRATION_NOTES.md}.
 * The harness skeleton is unconditionally present so JUnit discovers and
 * reports this per-program test in CI from day one. The {@code @Disabled}
 * annotation will be removed in the same PR that commits non-placeholder
 * content into {@code src/test/resources/golden/cbstm03b/expected/}.
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.statement.CbStm03B
 * @see com.blitzy.carddemo.application.statement.CbStm03A
 * @since 25
 */
@DisplayName("CBSTM03B \u2014 Callable File-Services Subroutine Golden-Record Parity")
public class CbStm03BGoldenTest extends GoldenRecordTest {

    /**
     * Identifier of the per-program fixture directory under
     * {@code src/test/resources/golden/}. Lowercase form of the COBOL
     * {@code PROGRAM-ID CBSTM03B} per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention.
     */
    private static final String PROGRAM_DIR = "cbstm03b";

    /**
     * Name of the synthesised scenario input file containing one CALL
     * description per line (file id + operation + optional key). Located
     * under {@code src/test/resources/golden/cbstm03b/expected/input_calls.txt}
     * because subroutine-style fixtures co-locate scenario inputs with
     * their expected outputs per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * convention.
     */
    private static final String INPUT_CALLS_TXT = "input_calls.txt";

    /**
     * Name of the captured COBOL stdout trace (one log line per CALL
     * invocation, typically echoing the input dispatch and the resulting
     * FILE STATUS return code plus the 1000-byte {@code LK-M03B-FLDT}
     * payload contents).
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.statement.CbStm03B}{@code .class}.
     * Referenced via fully-qualified class literal so this file's import
     * block remains minimal and restricted to the JUnit Jupiter API
     * annotations ({@link DisplayName}, {@link Disabled}, {@link Test})
     * plus {@link java.nio.file.Path}. The fully-qualified class literal
     * compiles cleanly because {@code carddemo-tests} depends on
     * {@code carddemo-application} via the test classpath (declared in
     * {@code java/carddemo-tests/pom.xml}).
     */
    @Override
    protected Class<?> programClass() {
        return com.blitzy.carddemo.application.statement.CbStm03B.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the path to the synthesised scenario input
     * {@code input_calls.txt} under
     * {@code src/test/resources/golden/cbstm03b/expected/}. The
     * conventional {@code input/} folder is reserved for fixtures that
     * mirror runtime input streams (e.g. {@code dailytran.txt}); for
     * subroutine-style fixtures the scenario CALL list is co-located with
     * the expected outputs because it is a synthesised harness artifact
     * rather than a captured runtime input.
     */
    @Override
    protected Path inputFile() {
        return resolveExpectedOutputPath(PROGRAM_DIR, INPUT_CALLS_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the path to the captured COBOL stdout trace at
     * {@code src/test/resources/golden/cbstm03b/expected/stdout.txt}.
     * This file captures the return codes and any payload bytes from a
     * deterministic CALL sequence; byte-for-byte equality is asserted by
     * {@link GoldenRecordTest#byteForByteParity()}.
     */
    @Override
    protected Path expectedOutputFile() {
        return resolveExpectedOutputPath(PROGRAM_DIR, STDOUT_TXT);
    }

    /**
     * Byte-for-byte parity assertion, currently {@code @Disabled} pending
     * the COBOL CBSTM03B baseline capture per AAP &sect;0.6.11 ("Initial
     * test scaffolding may use placeholder expected files marked
     * {@code @Disabled} until COBOL captures are available").
     *
     * <p>The {@code @Disabled} annotation embeds the activation checklist
     * below. The annotation will be removed in the same PR that commits
     * non-placeholder content under
     * {@code src/test/resources/golden/cbstm03b/expected/} per the capture
     * procedure documented in {@code java/MIGRATION_NOTES.md}. The method
     * body delegates to {@link GoldenRecordTest#byteForByteParity()} so
     * the actual byte-by-byte assertion logic remains centralised in the
     * base class.
     *
     * <p><strong>Why the {@code @Test} annotation is re-declared on this
     * override</strong>: empirically verified against JUnit Jupiter 5.13.1
     * (the version pinned in {@code java/pom.xml} dependencyManagement per
     * AAP &sect;0.5.1), the JUnit Platform's annotation lookup does NOT
     * inherit {@code @Test} when a subclass overrides a parent's
     * {@code @Test}-annotated method &mdash; running surefire with
     * {@code -Dtest=CbStm03BGoldenTest} produces "Tests run: 0" when
     * {@code @Test} is omitted from the override, but "Tests run: 1,
     * Skipped: 1" when re-declared. Without {@code @Test} here, this test
     * class would be silently dropped from the test suite, defeating the
     * AAP &sect;0.6.11 PR-gate purpose of the harness skeleton. This
     * pattern matches the sibling {@code DateValidatorGoldenTest} which
     * documents the same defensive {@code @Test} re-declaration.
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
        "Awaiting COBOL CBSTM03B baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md for the regeneration procedure "
            + "(note: CBSTM03B is a callable subroutine, so the test scenario "
            + "invokes specific methods on com.blitzy.carddemo.application."
            + "statement.CbStm03B rather than running a main program). The "
            + "expected output captures FILE STATUS return codes and 1000-byte "
            + "LK-M03B-FLDT payload contents from a deterministic CALL sequence. "
            + "Activation checklist (all must hold before removing @Disabled): "
            + "(1) input_calls.txt scenario exercises each of the four file "
            + "tags (TRNXFILE, XREFFILE, CUSTFILE, ACCTFILE) and each active "
            + "operation (OPEN, CLOSE, sequential READ for TRNXFILE/XREFFILE, "
            + "keyed READ-K for CUSTFILE/ACCTFILE); "
            + "(2) FILE STATUS codes map exactly: \"00\" -> RC_OK=0, \"10\" -> "
            + "RC_EOF=16, \"23\" -> RC_NOT_FOUND=23, other -> RC_ERROR=12; "
            + "(3) The 1000-byte LK-M03B-FLDT payload byte layout matches "
            + "COBOL for every dispatch (TRNXFILE 350-byte COSTM01 layout, "
            + "XREFFILE 50-byte CVACT03Y layout, CUSTFILE 500-byte CUSTREC "
            + "layout, ACCTFILE 300-byte CVACT01Y layout, padded with spaces "
            + "to 1000 bytes); "
            + "(4) Dispatch on an unrecognised LK-M03B-DD value matches the "
            + "COBOL GO TO 9999-GOBACK fall-through (no RC update); "
            + "(5) The declared-but-unused operation codes WRITE ('W') and "
            + "REWRITE ('Z') are preserved in CbStm03B as documented dead code "
            + "per AAP \u00a70.7.1; "
            + "(6) Random-access keyed READ-K uses LK-M03B-KEY truncated to "
            + "LK-M03B-KEY-LN bytes verbatim, matching the COBOL "
            + "\"MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO FD-CUST-ID\" / "
            + "\"... TO FD-ACCT-ID\" substring semantics."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
