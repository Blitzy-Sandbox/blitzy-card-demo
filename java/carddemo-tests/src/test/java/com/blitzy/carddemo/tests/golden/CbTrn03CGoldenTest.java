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
 * Byte-for-byte golden-record parity test for {@code CBTRN03C}
 * (Paginated Transaction Detail Report Writer).
 *
 * <p><strong>Source COBOL:</strong> {@code app/cbl/CBTRN03C.cbl} &mdash; the
 * {@code PROGRAM-ID CBTRN03C} batch program (650 lines, &quot;Print the
 * transaction detail report&quot;) driven by {@code app/jcl/TRANREPT.jcl}
 * step {@code STEP10R EXEC PGM=CBTRN03C} at
 * {@code app/jcl/TRANREPT.jcl:L59}. The COBOL opens six files declared in
 * the {@code FILE-CONTROL} section
 * ({@code app/cbl/CBTRN03C.cbl:L28-L57}):
 * <ul>
 *   <li>{@code TRANSACT-FILE} (DD {@code TRANFILE}, organisation
 *       {@code SEQUENTIAL}) &mdash; the daily transaction stream</li>
 *   <li>{@code XREF-FILE} (DD {@code CARDXREF}, organisation
 *       {@code INDEXED} access {@code RANDOM} key {@code FD-XREF-CARD-NUM})
 *       &mdash; card cross-reference VSAM KSDS</li>
 *   <li>{@code TRANTYPE-FILE} (DD {@code TRANTYPE}, organisation
 *       {@code INDEXED} access {@code RANDOM} key {@code FD-TRAN-TYPE})
 *       &mdash; transaction-type lookup</li>
 *   <li>{@code TRANCATG-FILE} (DD {@code TRANCATG}, organisation
 *       {@code INDEXED} access {@code RANDOM} key
 *       {@code FD-TRAN-CAT-KEY} = 2-byte type + 4-byte category) &mdash;
 *       transaction-category lookup</li>
 *   <li>{@code REPORT-FILE} (DD {@code TRANREPT}, organisation
 *       {@code SEQUENTIAL}) &mdash; the 133-byte fixed-record
 *       paginated detail report output ({@code FD-REPTFILE-REC PIC X(133)}
 *       at {@code app/cbl/CBTRN03C.cbl:L85})</li>
 *   <li>{@code DATE-PARMS-FILE} (DD {@code DATEPARM}, organisation
 *       {@code SEQUENTIAL}) &mdash; a single record carrying the
 *       inclusive date window per {@code WS-DATEPARM-RECORD} at
 *       {@code app/cbl/CBTRN03C.cbl:L122-L125}:
 *       {@code WS-START-DATE PIC X(10)} +
 *       {@code FILLER PIC X(01)} +
 *       {@code WS-END-DATE PIC X(10)} = <strong>21 bytes total</strong>
 *       (the FD declares an 80-byte {@code FD-DATEPARM-REC} but only
 *       the first 21 bytes are read into {@code WS-DATEPARM-RECORD})</li>
 * </ul>
 *
 * <p>For each in-window transaction (date filter
 * {@code TRAN-PROC-TS(1:10) >= WS-START-DATE AND TRAN-PROC-TS(1:10) <= WS-END-DATE}
 * at {@code app/cbl/CBTRN03C.cbl:L173-L174}, inclusive on both endpoints),
 * the program performs three random lookups (XREF by
 * {@code TRAN-CARD-NUM}, TRANTYPE by {@code TRAN-TYPE-CD}, TRANCATG by
 * composite key), accumulates page / account / grand totals as
 * {@code PIC S9(09)V99}, and emits paginated 133-byte report lines with
 * {@code WS-PAGE-SIZE=20} lines per page
 * ({@code app/cbl/CBTRN03C.cbl:L131-L132}) and a 133-space
 * {@code WS-BLANK-LINE} ({@code app/cbl/CBTRN03C.cbl:L133}).</p>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.transaction.CbTrn03C}. Per
 * AAP &sect;0.4.1 (program-by-program mapping) CBTRN03C is translated
 * into the {@code application/transaction/} subpackage co-located with
 * its sibling translations {@code CbTrn01C}, {@code CbTrn02C},
 * {@code CoTrn00C}, {@code CoTrn01C}, {@code CoTrn02C}.</p>
 *
 * <p><strong>DUPLICATE paragraph names preserved verbatim</strong> per
 * AAP &sect;0.7.1 (Minimal Change Clause &mdash; "translate faithfully,
 * do NOT 'fix' in this refactor"). The COBOL source contains
 * <strong>five</strong> paragraphs whose numeric prefixes are shared,
 * grouped into two prefix families:
 * <ol>
 *   <li><strong>Prefix {@code 1110-}</strong> (two paragraphs):
 *     <ul>
 *       <li>{@code 1110-WRITE-PAGE-TOTALS} at
 *           {@code app/cbl/CBTRN03C.cbl:L293-L304}</li>
 *       <li>{@code 1110-WRITE-GRAND-TOTALS} at
 *           {@code app/cbl/CBTRN03C.cbl:L318-L322}</li>
 *     </ul></li>
 *   <li><strong>Prefix {@code 1120-}</strong> (three paragraphs):
 *     <ul>
 *       <li>{@code 1120-WRITE-ACCOUNT-TOTALS} at
 *           {@code app/cbl/CBTRN03C.cbl:L306-L316}</li>
 *       <li>{@code 1120-WRITE-HEADERS} at
 *           {@code app/cbl/CBTRN03C.cbl:L324-L341}</li>
 *       <li>{@code 1120-WRITE-DETAIL} at
 *           {@code app/cbl/CBTRN03C.cbl:L361-L374}</li>
 *     </ul></li>
 * </ol>
 * Because each paragraph is referenced by its full name in
 * {@code PERFORM} statements (e.g.
 * {@code PERFORM 1110-WRITE-PAGE-TOTALS} vs
 * {@code PERFORM 1110-WRITE-GRAND-TOTALS}), the shared-prefix structure
 * is unambiguous at the COBOL source level. The Java translation
 * disambiguates by giving each paragraph a unique method name while
 * citing the original full COBOL paragraph identifier via Javadoc on
 * {@link com.blitzy.carddemo.application.transaction.CbTrn03C} per AAP
 * &sect;0.7.1. Documented in {@code java/MIGRATION_NOTES.md}.</p>
 *
 * <p><strong>EOF stale-TRAN-AMT suspected bug preserved verbatim</strong>
 * per AAP &sect;0.7.1. At
 * {@code app/cbl/CBTRN03C.cbl:L197-L203} the EOF branch of the main
 * {@code PERFORM UNTIL END-OF-FILE = 'Y'} loop executes:
 * <pre>{@code
 *   ELSE
 *    DISPLAY 'TRAN-AMT ' TRAN-AMT
 *    DISPLAY 'WS-PAGE-TOTAL'  WS-PAGE-TOTAL
 *    ADD TRAN-AMT TO WS-PAGE-TOTAL
 *                    WS-ACCOUNT-TOTAL
 *    PERFORM 1110-WRITE-PAGE-TOTALS
 *    PERFORM 1110-WRITE-GRAND-TOTALS
 *   END-IF
 * }</pre>
 * When {@code 1000-TRANFILE-GET-NEXT} sets {@code END-OF-FILE = 'Y'},
 * the FD buffer {@code TRAN-RECORD} still holds the bytes of the
 * <em>last successfully read</em> record (standard COBOL semantics &mdash;
 * the buffer is not cleared on FILE STATUS = "10"). The
 * {@code ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL} on the EOF branch
 * therefore double-counts the final record into the page and account
 * accumulators before emitting the page totals + grand totals. The
 * resulting grand total at end-of-report is the true sum of all in-window
 * amounts PLUS one extra copy of the final record's
 * {@code TRAN-AMT} &mdash; almost certainly a defect. Per AAP &sect;0.7.1
 * the Java translation
 * ({@link com.blitzy.carddemo.application.transaction.CbTrn03C}) preserves
 * the double-add verbatim, and the captured COBOL
 * {@code tranrept.txt} therefore reflects the incorrect-but-preserved
 * grand total. Documented in {@code java/MIGRATION_NOTES.md}.</p>
 *
 * <p><strong>Input fixtures</strong> (5 files; 4 from
 * {@code app/data/ASCII/} per AAP &sect;0.4.1, 1 synthesised by the
 * test):
 * <ul>
 *   <li>{@code app/data/ASCII/dailytran.txt} (105,300 bytes; 300 records
 *       of 350 bytes each per {@code app/cpy/CVTRA05Y.cpy} 14-field
 *       {@code TRAN-RECORD} layout: {@code TRAN-ID PIC X(16)} +
 *       {@code TRAN-TYPE-CD PIC X(02)} + {@code TRAN-CAT-CD PIC 9(04)} +
 *       {@code TRAN-SOURCE PIC X(10)} + {@code TRAN-DESC PIC X(100)} +
 *       {@code TRAN-AMT PIC S9(09)V99} +
 *       {@code TRAN-MERCHANT-ID PIC 9(09)} +
 *       {@code TRAN-MERCHANT-NAME PIC X(50)} +
 *       {@code TRAN-MERCHANT-CITY PIC X(50)} +
 *       {@code TRAN-MERCHANT-ZIP PIC X(10)} +
 *       {@code TRAN-CARD-NUM PIC X(16)} +
 *       {@code TRAN-ORIG-TS PIC X(26)} +
 *       {@code TRAN-PROC-TS PIC X(26)} +
 *       {@code FILLER PIC X(20)} = 350 bytes) &mdash; the primary
 *       {@link #inputFile()}, wired to DD {@code TRANFILE}</li>
 *   <li>{@code app/data/ASCII/cardxref.txt} (50-byte
 *       {@code CARD-XREF-RECORD} per {@code app/cpy/CVACT03Y.cpy})
 *       &mdash; wired to DD {@code CARDXREF}</li>
 *   <li>{@code app/data/ASCII/trantype.txt} (60-byte
 *       {@code TRAN-TYPE-RECORD} per {@code app/cpy/CVTRA03Y.cpy})
 *       &mdash; wired to DD {@code TRANTYPE}</li>
 *   <li>{@code app/data/ASCII/trancatg.txt} (60-byte
 *       {@code TRAN-CAT-RECORD} per {@code app/cpy/CVTRA04Y.cpy})
 *       &mdash; wired to DD {@code TRANCATG}</li>
 *   <li>{@code dateparm.txt} &mdash; <strong>synthesised inside the
 *       test scenario</strong> rather than referenced from a committed
 *       fixture, because the DATEPARM payload (21 bytes:
 *       {@code YYYY-MM-DD} + {@code SPACE} + {@code YYYY-MM-DD}) varies
 *       per test run to exercise the date-filter boundaries documented
 *       in the {@code @Disabled} activation checklist below. The
 *       synthesised path is NOT included in {@link #auxiliaryInputs()};
 *       the harness {@link GoldenRecordTest#runProgram(Class, Path,
 *       List)} override (deferred per the {@code @Disabled} scaffolding
 *       state) will materialise the 21-byte payload via
 *       {@link java.nio.file.Files#writeString(Path, CharSequence,
 *       java.nio.file.OpenOption...)} into a temp directory before
 *       invoking {@code CbTrn03C}. This matches AAP &sect;0.6.11
 *       ("Initial test scaffolding may use placeholder expected files
 *       marked {@code @Disabled} until COBOL captures are available")
 *       and the agent prompt "Phase 7.3 &mdash; DATEPARM Format (21
 *       bytes)" insight.</li>
 * </ul>
 *
 * <p><strong>Expected outputs</strong> (2 files; the
 * {@link #expectedOutputs()} override returns both for parallel
 * byte-for-byte parity assertion via {@link
 * GoldenRecordTest#byteForByteParity()}):
 * <ul>
 *   <li>{@code tranrept.txt} &mdash; the 133-char-wide paginated detail
 *       report (named per the COBOL JCL DD {@code //TRANREPT DD ...} at
 *       {@code app/jcl/TRANREPT.jcl}; the COBOL {@code SELECT REPORT-FILE
 *       ASSIGN TO TRANREPT} clause binds {@code FD-REPTFILE-REC} to the
 *       TRANREPT DD, so the captured output file takes its name from the
 *       DD rather than from the FD identifier). Each page contains the
 *       {@code REPORT-NAME-HEADER} + {@code WS-BLANK-LINE} +
 *       {@code TRANSACTION-HEADER-1} + {@code TRANSACTION-HEADER-2}
 *       (4 lines emitted by {@code 1120-WRITE-HEADERS}), followed by up
 *       to {@code WS-PAGE-SIZE=20} detail lines, with
 *       {@code REPORT-PAGE-TOTALS} + {@code TRANSACTION-HEADER-2}
 *       emitted by {@code 1110-WRITE-PAGE-TOTALS} on
 *       {@code FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0}
 *       transitions at {@code app/cbl/CBTRN03C.cbl:L282-L285}. Account
 *       breaks (when {@code WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM} at
 *       {@code app/cbl/CBTRN03C.cbl:L181}) emit
 *       {@code REPORT-ACCOUNT-TOTALS} + {@code TRANSACTION-HEADER-2}
 *       via {@code 1120-WRITE-ACCOUNT-TOTALS}. At EOF the program emits
 *       (via the duplicate-prefix paragraphs) the page totals and the
 *       grand totals containing the EOF stale-TRAN-AMT double-add
 *       documented above.</li>
 *   <li>{@code stdout.txt} &mdash; the {@code DISPLAY} stream captured
 *       from the COBOL run: the
 *       {@code 'START OF EXECUTION OF PROGRAM CBTRN03C'} banner
 *       ({@code app/cbl/CBTRN03C.cbl:L160}); the
 *       {@code 'Reporting from ' WS-START-DATE ' to ' WS-END-DATE}
 *       trace ({@code app/cbl/CBTRN03C.cbl:L232-L233}); per-record
 *       {@code DISPLAY TRAN-RECORD} dumps emitting the full 350-byte
 *       record at {@code app/cbl/CBTRN03C.cbl:L180}; the
 *       {@code 'TRAN-AMT '} and {@code 'WS-PAGE-TOTAL'} DISPLAYs from
 *       the EOF branch ({@code app/cbl/CBTRN03C.cbl:L198-L199}); and
 *       the {@code 'END OF EXECUTION OF PROGRAM CBTRN03C'} banner
 *       ({@code app/cbl/CBTRN03C.cbl:L215}).</li>
 * </ul>
 *
 * <p><strong>PAN-masking dichotomy (AAP &sect;0.7.2)</strong>: the COBOL
 * {@code DISPLAY TRAN-RECORD} at {@code app/cbl/CBTRN03C.cbl:L180}
 * emits the full 350-byte record including the 16-byte
 * {@code TRAN-CARD-NUM} PAN. Per AAP &sect;0.7.2 production logs MUST
 * mask all but the last 4 digits; the
 * {@link com.blitzy.carddemo.application.transaction.CbTrn03C}
 * production logger therefore applies masking. However, byte-for-byte
 * parity with the COBOL {@code stdout.txt} requires the test
 * orchestration to bypass masking via a test-only unmasked sink. This
 * dichotomy is intentional and is documented in the
 * {@link com.blitzy.carddemo.application.transaction.CbTrn03C} class
 * Javadoc ("PCI / PAN masking" section) and in
 * {@code java/MIGRATION_NOTES.md}.</p>
 *
 * <p><strong>Constructor wiring</strong>:
 * {@link com.blitzy.carddemo.application.transaction.CbTrn03C} has a
 * six-parameter constructor (per AAP &sect;0.4.1: TransactionRepository,
 * CardXrefRepository, TransactionTypeRepository,
 * TransactionCategoryRepository, DateParamsSource, ReportSink). The
 * base harness
 * {@link GoldenRecordTest#runProgram(Class, java.nio.file.Path,
 * java.util.List)} hook is deferred (throws
 * {@link UnsupportedOperationException} in the current base
 * implementation per AAP &sect;0.6.11); the {@code @Disabled}
 * annotation on {@link #byteForByteParity()} below references this
 * scaffolding state. The eventual override will: (1) instantiate
 * {@code FileTransactionRepository} against {@link #inputFile()},
 * (2) instantiate {@code FileCardXrefRepository},
 * {@code FileTransactionTypeRepository}, and
 * {@code FileTransactionCategoryRepository} against the corresponding
 * {@link #auxiliaryInputs()} paths, (3) synthesise a 21-byte
 * {@code dateparm.txt} into a temp directory and wrap it in a
 * {@code FileDateParamsSource}, (4) wire a test-only unmasked
 * {@code FileReportSink} writing to a temp output for byte capture, and
 * (5) return a {@link java.util.Map} keyed by output name
 * ({@code "tranrept.txt"}, {@code "stdout.txt"}) for the base harness to
 * compare against {@link #expectedOutputs()}.</p>
 *
 * <p>This test is the <strong>NON-NEGOTIABLE PR gate</strong> per AAP
 * &sect;0.6.11. Any deviation in pagination ({@code WS-PAGE-SIZE=20}),
 * line width (133 chars), {@code WS-BLANK-LINE} composition (133 spaces),
 * date filter inclusion semantics (lexical compare on
 * {@code TRAN-PROC-TS(1:10)}, both endpoints inclusive), EOF double-add
 * preservation, or DATEPARM 21-byte payload layout breaks parity and
 * blocks the PR.</p>
 *
 * <p><strong>Scaffolding state</strong>: per AAP &sect;0.6.11 ("Initial
 * test scaffolding may use placeholder expected files marked
 * {@code @Disabled} until COBOL captures are available; the harness
 * skeleton, base class, and per-program test classes are created
 * unconditionally"), the {@link #byteForByteParity()} override below is
 * annotated {@code @Disabled} with a 6-point verification checklist
 * citing the COBOL capture procedure documented in
 * {@code java/MIGRATION_NOTES.md} &sect;1.6. The harness skeleton is
 * unconditionally present so JUnit discovers and reports this
 * per-program test in CI from day one. The {@code @Disabled} annotation
 * will be removed in the same PR that commits non-placeholder content
 * into {@code src/test/resources/golden/cbtrn03c/expected/tranrept.txt}
 * and {@code .../stdout.txt}.</p>
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.transaction.CbTrn03C
 * @since 25
 */
@DisplayName("CBTRN03C \u2014 Paginated Transaction Report Golden-Record Parity (duplicate paragraphs + EOF bug preserved)")
public class CbTrn03CGoldenTest extends GoldenRecordTest {

    /**
     * Identifier of the per-program fixture directory under
     * {@code src/test/resources/golden/}. Lowercase form of the COBOL
     * {@code PROGRAM-ID CBTRN03C} per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention shared by all 28 per-program golden tests.
     */
    private static final String PROGRAM_DIR = "cbtrn03c";

    /**
     * Name of the primary input fixture under {@code app/data/ASCII/}.
     * The {@code dailytran.txt} fixture contains 300 records of 350
     * bytes each (105,300 bytes) per the {@code TRAN-RECORD} layout
     * defined in {@code app/cpy/CVTRA05Y.cpy}. Wired to the COBOL DD
     * {@code TRANFILE} per {@code app/jcl/TRANREPT.jcl:L65-L66}. Read
     * directly from {@code app/} via
     * {@link GoldenRecordTest#resolveAppDataPath(String)} &mdash; NOT
     * copied into this module per AAP &sect;0.4.1 and &sect;0.6.11.
     */
    private static final String DAILYTRAN_TXT = "dailytran.txt";

    /**
     * Name of the card cross-reference auxiliary fixture under
     * {@code app/data/ASCII/}. Wired to the COBOL DD {@code CARDXREF}
     * per {@code app/jcl/TRANREPT.jcl:L67-L68}. Used by the
     * {@code 1500-A-LOOKUP-XREF} paragraph at
     * {@code app/cbl/CBTRN03C.cbl:L484-L492} for random reads keyed by
     * {@code FD-XREF-CARD-NUM PIC X(16)}.
     */
    private static final String CARDXREF_TXT = "cardxref.txt";

    /**
     * Name of the transaction-type auxiliary fixture under
     * {@code app/data/ASCII/}. Wired to the COBOL DD {@code TRANTYPE}
     * per {@code app/jcl/TRANREPT.jcl:L69-L70}. Used by the
     * {@code 1500-B-LOOKUP-TRANTYPE} paragraph at
     * {@code app/cbl/CBTRN03C.cbl:L494-L502} for random reads keyed by
     * {@code FD-TRAN-TYPE PIC X(02)}.
     */
    private static final String TRANTYPE_TXT = "trantype.txt";

    /**
     * Name of the transaction-category auxiliary fixture under
     * {@code app/data/ASCII/}. Wired to the COBOL DD {@code TRANCATG}
     * per {@code app/jcl/TRANREPT.jcl:L71-L72}. Used by the
     * {@code 1500-C-LOOKUP-TRANCATG} paragraph at
     * {@code app/cbl/CBTRN03C.cbl:L504-L512} for random reads keyed by
     * {@code FD-TRAN-CAT-KEY} = {@code FD-TRAN-TYPE-CD PIC X(02)} +
     * {@code FD-TRAN-CAT-CD PIC 9(04)} (6 bytes composite).
     */
    private static final String TRANCATG_TXT = "trancatg.txt";

    /**
     * Name of the captured COBOL paginated report output under
     * {@code src/test/resources/golden/cbtrn03c/expected/}. This file
     * holds the 133-char-wide paginated detail report
     * ({@code FD-REPTFILE-REC PIC X(133)} per
     * {@code app/cbl/CBTRN03C.cbl:L85}), including the
     * incorrect-but-preserved grand total reflecting the EOF
     * stale-TRAN-AMT double-add described in the class Javadoc.
     *
     * <p>The fixture file name {@code tranrept.txt} is derived from
     * the JCL DD {@code //TRANREPT DD ...} at
     * {@code app/jcl/TRANREPT.jcl} (the COBOL
     * {@code SELECT REPORT-FILE ASSIGN TO TRANREPT} clause binds the
     * {@code FD-REPTFILE-REC} FD entry to the DD), per the standard
     * convention in this test suite of naming fixture files after the
     * JCL DD they map to rather than after the COBOL FD identifier.</p>
     */
    private static final String TRANREPT_TXT = "tranrept.txt";

    /**
     * Name of the captured COBOL {@code DISPLAY} stream under
     * {@code src/test/resources/golden/cbtrn03c/expected/}. Captures
     * the START / END banners
     * ({@code app/cbl/CBTRN03C.cbl:L160} and {@code :L215}), the
     * date-window trace ({@code app/cbl/CBTRN03C.cbl:L232-L233}), the
     * per-record {@code DISPLAY TRAN-RECORD} dumps
     * ({@code :L180}), and the EOF-branch {@code TRAN-AMT} /
     * {@code WS-PAGE-TOTAL} diagnostics ({@code :L198-L199}).
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.transaction.CbTrn03C}{@code .class}.
     * Referenced via fully-qualified class literal so this file's
     * import block remains minimal and restricted to the dependency
     * whitelist mandated by the file schema:
     * {@link java.nio.file.Path}, {@link java.util.List},
     * {@link DisplayName}, {@link Disabled}, {@link Test}. The
     * fully-qualified class literal compiles cleanly because
     * {@code carddemo-tests} declares a test-scope transitive
     * dependency on {@code carddemo-application} via {@code carddemo-app}
     * (the composition root) in {@code java/carddemo-tests/pom.xml} per
     * AAP &sect;0.4.1.</p>
     */
    @Override
    protected Class<?> programClass() {
        return com.blitzy.carddemo.application.transaction.CbTrn03C.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to
     * {@code <repo-root>/app/data/ASCII/dailytran.txt} resolved via
     * {@link GoldenRecordTest#resolveAppDataPath(String)}. This is the
     * primary TRANFILE input (300 records of 350 bytes each per
     * {@code app/cpy/CVTRA05Y.cpy} per AAP &sect;0.4.1).</p>
     */
    @Override
    protected Path inputFile() {
        return resolveAppDataPath(DAILYTRAN_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to the captured COBOL
     * paginated report at
     * {@code src/test/resources/golden/cbtrn03c/expected/tranrept.txt},
     * resolved via
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}.
     * This is the primary expected output; the
     * {@link #expectedOutputs()} override below additionally declares
     * {@link #STDOUT_TXT} for the {@code DISPLAY} stream capture.</p>
     */
    @Override
    protected Path expectedOutputFile() {
        return resolveExpectedOutputPath(PROGRAM_DIR, TRANREPT_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns an immutable 3-element {@link List} of auxiliary input
     * fixture paths:
     * <ol>
     *   <li>{@code app/data/ASCII/cardxref.txt} (DD {@code CARDXREF})</li>
     *   <li>{@code app/data/ASCII/trantype.txt} (DD {@code TRANTYPE})</li>
     *   <li>{@code app/data/ASCII/trancatg.txt} (DD {@code TRANCATG})</li>
     * </ol>
     *
     * <p>Note: the {@code DATEPARM} input is NOT included in this list
     * because it is synthesised inside the test orchestration as a
     * 21-byte payload ({@code YYYY-MM-DD} + space + {@code YYYY-MM-DD})
     * per the {@code WS-DATEPARM-RECORD} layout at
     * {@code app/cbl/CBTRN03C.cbl:L122-L125} and the agent prompt
     * "Phase 7.3 &mdash; DATEPARM Format (21 bytes)" insight. The
     * synthesisation site is the
     * {@link GoldenRecordTest#runProgram(Class, java.nio.file.Path,
     * java.util.List)} override that lands in the same PR as the
     * {@code @Disabled} removal.</p>
     *
     * <p>The returned list is {@link List#of(Object, Object, Object)}
     * immutable to preserve deterministic ordering and prevent
     * accidental mutation by the base harness or downstream
     * subclasses.</p>
     */
    @Override
    protected List<Path> auxiliaryInputs() {
        return List.of(
            resolveAppDataPath(CARDXREF_TXT),
            resolveAppDataPath(TRANTYPE_TXT),
            resolveAppDataPath(TRANCATG_TXT)
        );
    }

    /**
     * {@inheritDoc}
     *
     * <p>Declares the two byte-for-byte parity targets for CBTRN03C:
     * <ul>
     *   <li>{@link #TRANREPT_TXT} &mdash; the 133-char-wide paginated
     *       detail report captured from the COBOL {@code TRANREPT} DD
     *       (FD {@code REPORT-FILE} at
     *       {@code app/cbl/CBTRN03C.cbl:L84-L85}). The captured file
     *       includes the EOF stale-TRAN-AMT double-add in its grand
     *       total per AAP &sect;0.7.1 (preserve-as-is).</li>
     *   <li>{@link #STDOUT_TXT} &mdash; the {@code DISPLAY} stream
     *       captured from the COBOL run, including the per-record
     *       {@code DISPLAY TRAN-RECORD} dumps emitting unmasked
     *       {@code TRAN-CARD-NUM} bytes (PAN-masking dichotomy per AAP
     *       &sect;0.7.2 documented in the class Javadoc above).</li>
     * </ul>
     *
     * <p>The base harness
     * {@link GoldenRecordTest#byteForByteParity()} iterates this list
     * in order and asserts byte parity for each entry independently
     * per the AAP &sect;0.6.11 multi-output pattern, identifying any
     * mismatched output by name in the AssertJ failure message.</p>
     *
     * <p>Returned list is {@link List#of(Object, Object)} immutable.</p>
     */
    @Override
    protected List<ExpectedOutput> expectedOutputs() {
        return List.of(
            new ExpectedOutput(TRANREPT_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, TRANREPT_TXT)),
            new ExpectedOutput(STDOUT_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, STDOUT_TXT))
        );
    }

    /**
     * Byte-for-byte parity assertion, currently {@code @Disabled}
     * pending the COBOL CBTRN03C baseline capture per AAP &sect;0.6.11
     * ("Initial test scaffolding may use placeholder expected files
     * marked {@code @Disabled} until COBOL captures are available").
     *
     * <p>The {@code @Disabled} annotation embeds the 6-point activation
     * checklist mandated by the agent prompt "Phase 2: Initial
     * &commat;Disabled Guard" specification. The annotation will be
     * removed in the same PR that commits non-placeholder content under
     * {@code src/test/resources/golden/cbtrn03c/expected/} per the
     * capture procedure documented in {@code java/MIGRATION_NOTES.md}
     * &sect;1.6. The method body delegates to
     * {@link GoldenRecordTest#byteForByteParity()} so the actual
     * byte-by-byte assertion logic remains centralised in the base
     * class (per the AAP &sect;0.6.11 multi-output pattern with
     * structured-record diff hook via
     * {@link GoldenRecordTest#maskedRanges(String)}).</p>
     *
     * <p><strong>Why the {@code @Test} annotation is re-declared on
     * this override</strong>: empirically verified against JUnit
     * Jupiter 5.13.1 (pinned in {@code java/pom.xml}
     * {@code dependencyManagement} per AAP &sect;0.5.1), the JUnit
     * Platform's {@code AnnotationSupport.findAnnotation(method,
     * Test.class)} lookup does NOT walk to the parent class declaration
     * when a subclass <em>overrides</em> a {@code @Test}-annotated
     * method &mdash; the override is treated as a fresh method
     * declaration that must carry its own {@code @Test} annotation for
     * JUnit Jupiter to discover it. Without {@code @Test} here, this
     * test class would be silently dropped from the test suite,
     * defeating the AAP &sect;0.6.11 PR-gate purpose of the harness
     * skeleton. This pattern matches sibling
     * {@link CbAct01CGoldenTest}, {@link CbAct02CGoldenTest},
     * {@link CbAct03CGoldenTest}, {@link CbCus01CGoldenTest},
     * {@link CbStm03BGoldenTest}, and {@link DateValidatorGoldenTest}.</p>
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
        "Awaiting COBOL CBTRN03C baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md \u00a71.6 for the regeneration "
            + "procedure. Verify (all 6 must hold before removing "
            + "@Disabled): "
            + "(1) PAGE_SIZE=20 lines per page, matching "
            + "WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20 at "
            + "app/cbl/CBTRN03C.cbl:L131-L132; "
            + "(2) 133-char line width, matching FD-REPTFILE-REC "
            + "PIC X(133) at app/cbl/CBTRN03C.cbl:L85; "
            + "(3) WS-BLANK-LINE = 133 ASCII spaces (0x20), matching "
            + "WS-BLANK-LINE PIC X(133) VALUE SPACES at "
            + "app/cbl/CBTRN03C.cbl:L133; "
            + "(4) Date filter on TRAN-PROC-TS(1:10) inclusive on both "
            + "endpoints, matching app/cbl/CBTRN03C.cbl:L173-L174 "
            + "(IF TRAN-PROC-TS(1:10) >= WS-START-DATE AND <= WS-END-DATE); "
            + "(5) EOF stale-TRAN-AMT bug preserved verbatim per AAP "
            + "\u00a70.7.1 (the grand total counts the final record's "
            + "TRAN-AMT TWICE due to the ADD on the EOF branch at "
            + "app/cbl/CBTRN03C.cbl:L197-L203 reusing the unchanged "
            + "TRAN-RECORD buffer after END-OF-FILE = 'Y'); "
            + "(6) DATEPARM payload synthesised as 21 bytes "
            + "(10-char YYYY-MM-DD start + 1-char filler space + 10-char "
            + "YYYY-MM-DD end), matching WS-DATEPARM-RECORD at "
            + "app/cbl/CBTRN03C.cbl:L122-L125."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
