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
 * Byte-for-byte golden-record parity test for {@code CBTRN02C}
 * (Full Posting Engine).
 *
 * <p><strong>THIS IS THE MOST CRITICAL PARITY GATE</strong> in the
 * golden-record harness per AAP &sect;0.6.11. CBTRN02C is the central
 * posting engine of the CardDemo system: 25+ paragraphs of business
 * logic, multiple output files (5 distinct outputs), BigDecimal
 * arithmetic, and complex composite-key updates. Any regression in
 * the {@code Decimals} utility, file I/O byte-order, sign-nybble
 * handling for COMP-3, padding direction, or sequencing will FIRST
 * manifest in this test. Treat this test as the canary for the
 * entire COBOL &rarr; Java&nbsp;25 migration.</p>
 *
 * <p><strong>Source COBOL:</strong>
 * {@code app/cbl/CBTRN02C.cbl:L236-L750} &mdash; the 25+ paragraphs
 * that constitute the full daily-transaction posting engine. The
 * paragraphs translated by the Java port and exercised by this
 * golden-record test include:
 * <ul>
 *   <li>{@code 1500-VALIDATE-TRAN} &mdash; orchestrates the chained
 *       XREF and ACCOUNT lookups + over-limit + expiration checks,
 *       sets {@code WS-VALIDATION-FAIL-REASON} to one of 100, 101,
 *       102, 103, or 0 (OK)</li>
 *   <li>{@code 1500-A-LOOKUP-XREF} &mdash; random read of
 *       XREFFILE keyed on {@code DALYTRAN-CARD-NUM}; on INVALID KEY
 *       sets reason 100 ("INVALID CARD NUMBER FOUND")</li>
 *   <li>{@code 1500-B-LOOKUP-ACCT} &mdash; random read of
 *       ACCTFILE keyed on {@code XREF-ACCT-ID} returned by 1500-A;
 *       on INVALID KEY sets reason 101 ("ACCOUNT RECORD NOT FOUND");
 *       on success performs the over-limit check (reason 102) and
 *       the expiration check (reason 103)</li>
 *   <li>{@code 2000-POST-TRANSACTION} &mdash; orchestrates the
 *       posting path when validation passes: invokes
 *       {@code 2700-UPDATE-TCATBAL}, {@code 2800-UPDATE-ACCOUNT-REC},
 *       and {@code 2900-WRITE-TRANSACTION-FILE} in order</li>
 *   <li>{@code 2500-WRITE-REJECT-REC} &mdash; on validation failure
 *       writes a 430-byte record to DALYREJS consisting of the
 *       350-byte source DALYTRAN-RECORD concatenated with the
 *       80-byte trailer (4-char zero-padded reason + 76-char
 *       left-padded description); see Phase 7.1 &amp; 7.2 below</li>
 *   <li>{@code 2700-UPDATE-TCATBAL} &mdash; reads TCATBAL keyed by
 *       the 17-byte composite key (11-digit acctId + 2-char typeCd +
 *       4-digit catCd); if not found delegates to
 *       {@code 2700-A-CREATE-TCATBAL-REC} (WRITE), else delegates
 *       to {@code 2700-B-UPDATE-TCATBAL-REC} (REWRITE)</li>
 *   <li>{@code 2700-A-CREATE-TCATBAL-REC} &mdash; WRITE of a
 *       brand-new TCATBAL record initialised with the current
 *       {@code DALYTRAN-AMT}</li>
 *   <li>{@code 2700-B-UPDATE-TCATBAL-REC} &mdash; REWRITE of the
 *       existing TCATBAL record after adding {@code DALYTRAN-AMT} to
 *       {@code TCATBAL-CUR-BAL}</li>
 *   <li>{@code 2800-UPDATE-ACCOUNT-REC} &mdash; REWRITE of the
 *       ACCOUNT record after applying the amount-sign split: if
 *       {@code DALYTRAN-AMT >= 0} adds to
 *       {@code ACCT-CURR-CYC-CREDIT}, else adds to
 *       {@code ACCT-CURR-CYC-DEBIT}; updates
 *       {@code ACCT-CURR-BAL} unconditionally</li>
 *   <li>{@code 2900-WRITE-TRANSACTION-FILE} &mdash; sequential
 *       WRITE of a TRAN-RECORD entry to the TRANSACT KSDS,
 *       formatting the timestamps in DB2 timestamp format
 *       (see Phase 7.7 below)</li>
 * </ul>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.transaction.CbTrn02C}. Per
 * AAP &sect;0.4.1 (program-by-program mapping), CBTRN02C is
 * translated into the {@code application/transaction/} subpackage
 * co-located with its sibling translations
 * {@code CbTrn01C}, {@code CbTrn03C}, {@code CoTrn00C},
 * {@code CoTrn01C}, {@code CoTrn02C}. The Java translation is
 * instantiated by the harness via the 5-argument constructor
 * {@code CbTrn02C(DailyTransactionRepository, CardXrefRepository,
 * AccountRepository, TransactionRepository,
 * TransactionCategoryBalanceRepository)} mirroring the COBOL 6-file
 * open pattern (DALYTRAN and DALYREJS share the
 * {@code DailyTransactionRepository} port per AAP &sect;0.3 dependency
 * ordering &mdash; the COBOL DALYREJS sink is layered onto
 * {@code DalyTranRecord} via
 * {@code DailyTransactionRepository#appendReject(DalyTranRecord, int, String)}).</p>
 *
 * <p><strong>Five reject codes (preserve exact text per AAP &sect;0.7.1):</strong>
 * <ul>
 *   <li>{@code 100} &mdash; "INVALID CARD NUMBER FOUND"
 *       (set by {@code 1500-A-LOOKUP-XREF} on INVALID KEY)</li>
 *   <li>{@code 101} &mdash; "ACCOUNT RECORD NOT FOUND"
 *       (set by {@code 1500-B-LOOKUP-ACCT} on INVALID KEY)</li>
 *   <li>{@code 102} &mdash; "OVERLIMIT TRANSACTION"
 *       (set when {@code ACCT-CREDIT-LIMIT < WS-TEMP-BAL})</li>
 *   <li>{@code 103} &mdash; "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"
 *       (set when {@code ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS(1:10)};
 *       note the COBOL typo {@code EXPIRAION} preserved per AAP
 *       &sect;0.7.1)</li>
 *   <li>{@code 109} &mdash; "ACCOUNT RECORD NOT FOUND" (intentionally
 *       duplicate text vs 101 per the COBOL source; set by
 *       {@code 2800-UPDATE-ACCOUNT-REC} on INVALID KEY during the
 *       REWRITE path)</li>
 * </ul>
 *
 * <p><strong>430-byte REJECT-RECORD layout</strong> (per the
 * {@code FD-REJS-RECORD} FD at {@code app/cbl/CBTRN02C.cbl:L81-L84}):
 * the 350-byte original {@code DALYTRAN-RECORD} concatenated with an
 * 80-byte {@code VALIDATION-TRAILER}. The trailer comprises a
 * 4-character zero-padded reason code (e.g.&nbsp;{@code "0100"} for
 * reason 100) followed by a 76-character left-padded description with
 * trailing spaces. Total = 350 + 80 = 430 bytes. Byte parity depends
 * on exact padding direction.</p>
 *
 * <p><strong>DB2 timestamp format</strong>
 * ({@code Z-GET-DB2-FORMAT-TIMESTAMP} paragraph): {@code YYYY-MM-DD-HH.MM.SS.HH0000}
 * (26 characters: 10-char date with hyphens + hyphen + 8-char time
 * with dots + dot + 6-char millis where the trailing 4 are literal
 * zeros + 2-char centiseconds derived from
 * {@link java.time.LocalDateTime#getNano()} divided by 10_000_000
 * per AAP &sect;0.6.4). The Java translation in
 * {@link com.blitzy.carddemo.application.transaction.CbTrn02C}
 * formats this exactly so the captured TRANSACT and TRANSACTION
 * records compare byte-for-byte.</p>
 *
 * <p><strong>Display whitespace preserved exactly</strong>
 * (CRITICAL byte-parity invariant from
 * {@code app/cbl/CBTRN02C.cbl:L227-L228}):
 * <ul>
 *   <li>{@code 'TRANSACTIONS PROCESSED :'} &mdash; <strong>1 space</strong>
 *       before colon</li>
 *   <li>{@code 'TRANSACTIONS REJECTED  :'} &mdash; <strong>2 spaces</strong>
 *       before colon</li>
 * </ul>
 * This asymmetric whitespace is preserved verbatim per AAP &sect;0.7.1
 * (Preserve-As-Is). Any single-space normalization in the Java
 * translation will fail byte parity on the {@code stdout.txt}
 * expected output.</p>
 *
 * <p><strong>Sequential execution mandated</strong> &mdash; <em>NO virtual
 * threads</em> per AAP &sect;0.6.6 ("virtual threads are NOT a license to
 * reorder records, change sort orders, or break sequencing"). The COBOL
 * {@code PERFORM UNTIL END-OF-FILE = 'Y'} loop at
 * {@code app/cbl/CBTRN02C.cbl:L202-L219} processes each DALYTRAN
 * record in input order; the per-account sequencing of ACCOUNT
 * REWRITEs and TCATBAL WRITEs/REWRITEs is observable because two
 * transactions against the same {@code (acctId, typeCd, catCd)}
 * key cumulate in the order they appear in DALYTRAN. Parallelising
 * this loop would change the final TCATBAL balances and break byte
 * parity. The Java translation preserves strict sequential execution
 * and this golden-record test verifies it implicitly via byte
 * parity on all 5 outputs.</p>
 *
 * <p><strong>RETURN-CODE 4 on rejects</strong> (per
 * {@code app/cbl/CBTRN02C.cbl:L229-L231}): if
 * {@code WS-REJECT-COUNT > 0} after the main loop, the program sets
 * {@code RETURN-CODE = 4}. The Java translation exposes the value
 * via {@link com.blitzy.carddemo.application.transaction.CbTrn02C#returnCode()};
 * the wrapping main class in {@code carddemo-app/PostTransactionsApp.java}
 * MAY pass this as the process exit status. This test verifies the
 * return code indirectly via the {@code stdout.txt} byte parity.</p>
 *
 * <p><strong>Five expected outputs</strong> (per AAP &sect;0.6.11 the
 * MOST COMPLEX multi-output scenario in the entire harness, vs.
 * {@code CoActUpCGoldenTest} with 4 outputs and
 * {@code CbStm03AGoldenTest} with 3 outputs):
 * <ol>
 *   <li>{@code transact.txt} &mdash; new TRAN-RECORD entries
 *       written to TRANSACT via {@code 2900-WRITE-TRANSACTION-FILE}</li>
 *   <li>{@code acctdata.txt} &mdash; ACCOUNT records REWRITTEN via
 *       {@code 2800-UPDATE-ACCOUNT-REC} after applying the
 *       amount-sign split (credit/debit cycle updates)</li>
 *   <li>{@code tcatbal.txt} &mdash; TCATBAL records WRITTEN
 *       (new) by {@code 2700-A-CREATE-TCATBAL-REC} and REWRITTEN
 *       (updated) by {@code 2700-B-UPDATE-TCATBAL-REC}</li>
 *   <li>{@code dalyrejs.txt} &mdash; DALYREJS records (430 bytes
 *       each: 350 data + 80 trailer) written by
 *       {@code 2500-WRITE-REJECT-REC}</li>
 *   <li>{@code stdout.txt} &mdash; DISPLAY output including the
 *       START/END banners, the asymmetric "TRANSACTIONS PROCESSED :"
 *       (1 space) and "TRANSACTIONS REJECTED  :" (2 spaces) lines,
 *       and any per-paragraph error DISPLAYs that fire on the
 *       fixture data</li>
 * </ol>
 *
 * <p>This test is the <strong>NON-NEGOTIABLE PR gate</strong> per AAP
 * &sect;0.6.11 ("These are non-negotiable and run on every PR"). When
 * enabled, failure blocks the PR.</p>
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.transaction.CbTrn02C
 */
@DisplayName("CBTRN02C \u2014 Full Posting Engine Golden-Record Parity (MOST CRITICAL)")
public class CbTrn02CGoldenTest extends GoldenRecordTest {

    /**
     * Program directory under {@code src/test/resources/golden/}.
     *
     * <p>Lowercase form of the COBOL {@code PROGRAM-ID CBTRN02C}
     * per the {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention shared by all 28 per-program golden tests.</p>
     */
    private static final String PROGRAM_DIR = "cbtrn02c";

    /**
     * Primary input fixture name under {@code app/data/ASCII/}.
     *
     * <p>The {@code dailytran.txt} fixture contains daily-transaction
     * records of 350 bytes each per the {@code DALYTRAN-RECORD} layout
     * defined in {@code app/cpy/CVTRA06Y.cpy}. Wired to the COBOL DD
     * {@code DALYTRAN} via the {@code SELECT DALYTRAN-FILE ASSIGN TO
     * DALYTRAN} clause at {@code app/cbl/CBTRN02C.cbl:L29-L32}. Read
     * directly from {@code app/} via
     * {@link GoldenRecordTest#resolveAppDataPath(String)} &mdash; NOT
     * copied into this module per AAP &sect;0.4.1 and &sect;0.6.11.</p>
     */
    private static final String DAILYTRAN_TXT = "dailytran.txt";

    /**
     * Card cross-reference auxiliary fixture name under
     * {@code app/data/ASCII/}.
     *
     * <p>Wired to the COBOL DD {@code XREFFILE} via the
     * {@code SELECT XREF-FILE ASSIGN TO XREFFILE} clause at
     * {@code app/cbl/CBTRN02C.cbl:L40-L44}. Read by the
     * {@code 1500-A-LOOKUP-XREF} paragraph for random reads keyed by
     * {@code FD-XREF-CARD-NUM PIC X(16)} matching
     * {@code DALYTRAN-CARD-NUM}. On INVALID KEY the parent
     * {@code 1500-VALIDATE-TRAN} sets reject reason 100 ("INVALID
     * CARD NUMBER FOUND").</p>
     */
    private static final String CARDXREF_TXT = "cardxref.txt";

    /**
     * Account-master auxiliary fixture name under
     * {@code app/data/ASCII/}.
     *
     * <p>Wired to the COBOL DD {@code ACCTFILE} via the
     * {@code SELECT ACCOUNT-FILE ASSIGN TO ACCTFILE} clause at
     * {@code app/cbl/CBTRN02C.cbl:L51-L55}. Read by the
     * {@code 1500-B-LOOKUP-ACCT} paragraph for random reads keyed by
     * {@code FD-ACCT-ID PIC 9(11)} matching {@code XREF-ACCT-ID}
     * returned by the prior 1500-A XREF lookup; on INVALID KEY sets
     * reject reason 101 ("ACCOUNT RECORD NOT FOUND"). REWRITTEN by
     * {@code 2800-UPDATE-ACCOUNT-REC} during the posting path after
     * applying the amount-sign split (credit/debit cycle update).</p>
     */
    private static final String ACCTDATA_TXT = "acctdata.txt";

    /**
     * Transaction-category-balance auxiliary fixture name under
     * {@code app/data/ASCII/}.
     *
     * <p>Wired to the COBOL DD {@code TCATBALF} via the
     * {@code SELECT TCATBAL-FILE ASSIGN TO TCATBALF} clause at
     * {@code app/cbl/CBTRN02C.cbl:L57-L61}. Read by the
     * {@code 2700-UPDATE-TCATBAL} paragraph for random reads keyed by
     * the 17-byte composite key {@code FD-TRAN-CAT-KEY}
     * ({@code FD-TRANCAT-ACCT-ID PIC 9(11)} + {@code FD-TRANCAT-TYPE-CD
     * PIC X(02)} + {@code FD-TRANCAT-CD PIC 9(04)} = 17 bytes per the
     * {@code FD-TRAN-CAT-BAL-RECORD} FD at
     * {@code app/cbl/CBTRN02C.cbl:L91-L97}). Either WRITTEN as a new
     * record by {@code 2700-A-CREATE-TCATBAL-REC} or REWRITTEN by
     * {@code 2700-B-UPDATE-TCATBAL-REC} after adding
     * {@code DALYTRAN-AMT} to {@code TCATBAL-CUR-BAL}.</p>
     */
    private static final String TCATBAL_TXT = "tcatbal.txt";

    /**
     * Expected-output name for the TRANSACT writes produced by
     * {@code 2900-WRITE-TRANSACTION-FILE}.
     *
     * <p>Sequential WRITE of TRAN-RECORD entries (350 bytes each per
     * {@code app/cpy/CVTRA05Y.cpy}) to the TRANSACT KSDS. The
     * timestamps in {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS}
     * are formatted in DB2 timestamp format
     * {@code YYYY-MM-DD-HH.MM.SS.HH0000} per Phase 7.7 below.</p>
     */
    private static final String TRANSACT_TXT = "transact.txt";

    /**
     * Expected-output name for the DALYREJS writes produced by
     * {@code 2500-WRITE-REJECT-REC}.
     *
     * <p>Sequential WRITE of 430-byte REJECT-RECORD entries (350-byte
     * original DALYTRAN-RECORD + 80-byte VALIDATION-TRAILER) per the
     * {@code FD-REJS-RECORD} FD at
     * {@code app/cbl/CBTRN02C.cbl:L81-L84}. The 80-byte trailer
     * comprises a 4-character zero-padded reason code (e.g.
     * {@code "0100"}, {@code "0101"}, {@code "0102"}, {@code "0103"},
     * {@code "0109"}) and a 76-character left-padded description with
     * trailing spaces.</p>
     */
    private static final String DALYREJS_TXT = "dalyrejs.txt";

    /**
     * Expected-output name for the captured COBOL {@code DISPLAY}
     * stream.
     *
     * <p>Captures the START banner
     * ({@code 'START OF EXECUTION OF PROGRAM CBTRN02C'} at
     * {@code app/cbl/CBTRN02C.cbl:L194}), any per-paragraph error
     * DISPLAYs that fire on the fixture data (error-opening/-reading/
     * -writing/-rewriting messages from the 0000-0500 OPEN,
     * 9000-9500 CLOSE, 2500/2700/2800/2900 path paragraphs), the
     * end-of-run summary lines with their CRITICAL asymmetric
     * whitespace
     * ({@code 'TRANSACTIONS PROCESSED :'} with <strong>1 space</strong>
     * before colon at {@code :L227}, and
     * {@code 'TRANSACTIONS REJECTED  :'} with <strong>2 spaces</strong>
     * before colon at {@code :L228}), and the END banner
     * ({@code 'END OF EXECUTION OF PROGRAM CBTRN02C'} at
     * {@code :L232}). Currently a placeholder pending COBOL capture
     * per AAP &sect;0.6.11.</p>
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.transaction.CbTrn02C}{@code .class}.
     * Referenced via fully-qualified class literal so this file's
     * import block remains minimal and restricted to
     * {@link java.nio.file.Path}, {@link java.util.List}, and the
     * JUnit Jupiter API annotations ({@link DisplayName},
     * {@link Disabled}, {@link Test}). The fully-qualified class
     * literal compiles cleanly because {@code carddemo-tests}
     * declares a test-scope transitive dependency on
     * {@code carddemo-application} (via {@code carddemo-app}, the
     * composition root) in {@code java/carddemo-tests/pom.xml} per
     * AAP &sect;0.4.1.</p>
     */
    @Override
    protected Class<?> programClass() {
        return com.blitzy.carddemo.application.transaction.CbTrn02C.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to
     * {@code <repo-root>/app/data/ASCII/dailytran.txt} resolved via
     * {@link GoldenRecordTest#resolveAppDataPath(String)}. This is
     * the primary DALYTRAN input (350 bytes per record per
     * {@code app/cpy/CVTRA06Y.cpy}) per AAP &sect;0.4.1.</p>
     */
    @Override
    protected Path inputFile() {
        return resolveAppDataPath(DAILYTRAN_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to the captured COBOL
     * TRANSACT writes at
     * {@code src/test/resources/golden/cbtrn02c/expected/transact.txt},
     * resolved via
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}.
     * This is the <em>primary</em> expected output; the
     * {@link #expectedOutputs()} override below declares the
     * complete 5-output list ({@value #TRANSACT_TXT},
     * {@value #ACCTDATA_TXT}, {@value #TCATBAL_TXT},
     * {@value #DALYREJS_TXT}, {@value #STDOUT_TXT}) for the
     * multi-output byte parity assertion that drives this critical
     * gate.</p>
     */
    @Override
    protected Path expectedOutputFile() {
        return resolveExpectedOutputPath(PROGRAM_DIR, TRANSACT_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns an immutable 3-element {@link List} of auxiliary
     * input fixture paths, reflecting the COBOL CBTRN02C 6-file open
     * pattern (DALYTRAN primary + 5 auxiliary, of which TRANFILE and
     * DALYREJS have no production fixture committed under
     * {@code app/data/ASCII/} because they are output-only sinks):
     * <ol>
     *   <li>{@code app/data/ASCII/cardxref.txt} (DD {@code XREFFILE},
     *       read by {@code 1500-A-LOOKUP-XREF})</li>
     *   <li>{@code app/data/ASCII/acctdata.txt} (DD {@code ACCTFILE},
     *       read by {@code 1500-B-LOOKUP-ACCT}; also REWRITTEN by
     *       {@code 2800-UPDATE-ACCOUNT-REC})</li>
     *   <li>{@code app/data/ASCII/tcatbal.txt} (DD {@code TCATBALF},
     *       read by {@code 2700-UPDATE-TCATBAL}; also WRITTEN by
     *       {@code 2700-A-CREATE-TCATBAL-REC} and REWRITTEN by
     *       {@code 2700-B-UPDATE-TCATBAL-REC})</li>
     * </ol>
     *
     * <p>The returned list is {@link List#of(Object, Object, Object)}
     * immutable to preserve deterministic ordering and prevent
     * accidental mutation by the base harness or downstream
     * subclasses. The list is consumed by the
     * {@link GoldenRecordTest#runProgram(Class, java.nio.file.Path,
     * java.util.List) runProgram(Class, Path, List)} orchestration
     * hook (inherited from the base class) to wire up the file-based
     * adapter implementations against the auxiliary input fixtures
     * before invoking {@code CbTrn02C.run()}.</p>
     */
    @Override
    protected List<Path> auxiliaryInputs() {
        return List.of(
            resolveAppDataPath(CARDXREF_TXT),
            resolveAppDataPath(ACCTDATA_TXT),
            resolveAppDataPath(TCATBAL_TXT)
        );
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns an immutable 5-element {@link List} of
     * {@link ExpectedOutput} declarations covering every output that
     * the COBOL CBTRN02C program produces. This is the
     * <strong>MOST COMPLEX multi-output scenario in the entire
     * golden-record harness</strong> per AAP &sect;0.6.11 (vs.
     * {@code CoActUpCGoldenTest} with 4 outputs and
     * {@code CbStm03AGoldenTest} with 3 outputs), reflecting CBTRN02C's
     * role as the central posting engine:
     * <ol>
     *   <li>{@value #TRANSACT_TXT} &mdash; sequential WRITE of new
     *       TRAN-RECORD entries to the TRANSACT KSDS via
     *       {@code 2900-WRITE-TRANSACTION-FILE}; 350 bytes per
     *       record per {@code app/cpy/CVTRA05Y.cpy} with timestamps
     *       in DB2 format {@code YYYY-MM-DD-HH.MM.SS.HH0000}</li>
     *   <li>{@value #ACCTDATA_TXT} &mdash; REWRITTEN ACCOUNT records
     *       via {@code 2800-UPDATE-ACCOUNT-REC} after applying the
     *       amount-sign split: {@code DALYTRAN-AMT >= 0} adds to
     *       {@code ACCT-CURR-CYC-CREDIT}, {@code DALYTRAN-AMT < 0}
     *       adds to {@code ACCT-CURR-CYC-DEBIT}, with
     *       {@code ACCT-CURR-BAL} updated unconditionally</li>
     *   <li>{@value #TCATBAL_TXT} &mdash; WRITTEN (new) by
     *       {@code 2700-A-CREATE-TCATBAL-REC} or REWRITTEN (updated)
     *       by {@code 2700-B-UPDATE-TCATBAL-REC} after adding
     *       {@code DALYTRAN-AMT} to {@code TCATBAL-CUR-BAL}; 17-byte
     *       composite key per Phase 7.6 below</li>
     *   <li>{@value #DALYREJS_TXT} &mdash; sequential WRITE of
     *       430-byte REJECT-RECORD entries (350-byte source
     *       DALYTRAN-RECORD + 80-byte VALIDATION-TRAILER) via
     *       {@code 2500-WRITE-REJECT-REC}</li>
     *   <li>{@value #STDOUT_TXT} &mdash; captured DISPLAY stream
     *       including the START/END banners and the asymmetric
     *       summary lines (1 space vs 2 spaces before colon per
     *       Phase 7.8 below)</li>
     * </ol>
     *
     * <p>The base harness iterates this list and asserts byte parity
     * for each output independently via
     * {@link GoldenRecordTest#byteForByteParity()}, identifying any
     * mismatched output by name in the AssertJ failure message per
     * the AAP &sect;0.6.11 multi-output pattern.</p>
     */
    @Override
    protected List<ExpectedOutput> expectedOutputs() {
        return List.of(
            new ExpectedOutput(TRANSACT_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, TRANSACT_TXT)),
            new ExpectedOutput(ACCTDATA_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, ACCTDATA_TXT)),
            new ExpectedOutput(TCATBAL_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, TCATBAL_TXT)),
            new ExpectedOutput(DALYREJS_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, DALYREJS_TXT)),
            new ExpectedOutput(STDOUT_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, STDOUT_TXT))
        );
    }

    /**
     * Byte-for-byte parity assertion, currently {@code @Disabled}
     * pending the COBOL CBTRN02C baseline capture per AAP
     * &sect;0.6.11 ("Initial test scaffolding may use placeholder
     * expected files marked {@code @Disabled} until COBOL captures
     * are available").
     *
     * <p>The {@code @Disabled} annotation will be removed in the same
     * PR that commits non-placeholder content under
     * {@code src/test/resources/golden/cbtrn02c/expected/} for all
     * 5 declared outputs per the capture procedure documented in
     * {@code java/MIGRATION_NOTES.md}. The method body delegates to
     * {@link GoldenRecordTest#byteForByteParity()} so the actual
     * byte-by-byte assertion logic remains centralised in the base
     * class (no duplication of the read/compare loop across the 28
     * per-program subclasses).</p>
     *
     * <p><strong>Why the {@code @Test} annotation is re-declared on
     * this override</strong>: empirically verified against JUnit
     * Jupiter 5.13.1 (pinned in {@code java/pom.xml}
     * {@code dependencyManagement} per AAP &sect;0.5.1), the JUnit
     * Platform's {@code AnnotationSupport.findAnnotation(method,
     * Test.class)} lookup does NOT walk to the parent class
     * declaration when a subclass <em>overrides</em> a
     * {@code @Test}-annotated method &mdash; the override is treated
     * as a fresh method declaration that must carry its own
     * {@code @Test} annotation for JUnit Jupiter to discover it.
     * Without {@code @Test} here, this test class would be silently
     * dropped from the test suite, defeating the AAP &sect;0.6.11
     * PR-gate purpose of the harness skeleton. This pattern matches
     * sibling {@link CbAct01CGoldenTest},
     * {@link CbAct02CGoldenTest}, {@link CbAct03CGoldenTest},
     * {@link CbCus01CGoldenTest}, {@link CbStm03AGoldenTest},
     * {@link CbStm03BGoldenTest}, {@link CbTrn01CGoldenTest},
     * {@link CbTrn03CGoldenTest}, and
     * {@link DateValidatorGoldenTest}.</p>
     *
     * <p>The {@code @Disabled} reason embedded below documents the
     * comprehensive 10-point verification checklist that the future
     * maintainer must verify before removing the annotation. This is
     * the MOST CRITICAL parity gate in the entire harness; the
     * checklist deliberately enumerates every invariant whose
     * violation would silently break observable behavior.</p>
     *
     * @throws Exception if the program under test, the
     *                   {@link GoldenRecordTest#runProgram(Class,
     *                   java.nio.file.Path, java.util.List)} hook,
     *                   or any {@link java.nio.file.Files#readAllBytes(
     *                   java.nio.file.Path)} call fails
     */
    @Override
    @Test
    @Disabled(
        "Awaiting COBOL CBTRN02C baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md for the regeneration procedure. "
            + "CRITICAL: this is the MOST IMPORTANT parity gate in the entire "
            + "harness; CBTRN02C is the central posting engine of CardDemo "
            + "and any regression in Decimals utility, file I/O byte-order, "
            + "sign-nybble handling, padding direction, or sequencing will "
            + "manifest here FIRST. Verify (all 10 must hold before removing "
            + "@Disabled): "
            + "(1) Decimals utility truncation matches COBOL "
            + "(MathContext.DECIMAL128 + RoundingMode.DOWN per AAP \u00a70.6.1 "
            + "for the un-ROUNDED ADD statements in 2700-B-UPDATE-TCATBAL-REC "
            + "and 2800-UPDATE-ACCOUNT-REC); "
            + "(2) WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT "
            + "+ DALYTRAN-AMT (the credit-debit-plus-amt formula at the "
            + "1500-B over-limit pre-check); "
            + "(3) Over-limit check ACCT-CREDIT-LIMIT < WS-TEMP-BAL "
            + "(strict less-than, NOT less-than-or-equal -- boundary value "
            + "preserved per AAP \u00a70.7.1; sets reason 102 "
            + "'OVERLIMIT TRANSACTION'); "
            + "(4) Expiration check ACCT-EXPIRAION-DATE < "
            + "DALYTRAN-ORIG-TS(1:10) -- note the COBOL typo EXPIRAION "
            + "(not EXPIRATION) preserved in Java as acctExpiraionDate "
            + "per AAP \u00a70.7.1; sets reason 103 'TRANSACTION RECEIVED "
            + "AFTER ACCT EXPIRATION'; "
            + "(5) Amount-sign split: amt >= 0 adds to "
            + "ACCT-CURR-CYC-CREDIT, amt < 0 adds to ACCT-CURR-CYC-DEBIT "
            + "(BigDecimal.compareTo(BigDecimal.ZERO), NEVER "
            + "BigDecimal.equals(BigDecimal.ZERO) which is scale-sensitive); "
            + "(6) TCATBAL composite key: 11-digit acctId "
            + "(FD-TRANCAT-ACCT-ID PIC 9(11)) + 2-char typeCd "
            + "(FD-TRANCAT-TYPE-CD PIC X(02)) + 4-digit catCd "
            + "(FD-TRANCAT-CD PIC 9(04)) = 17 bytes exactly per "
            + "app/cbl/CBTRN02C.cbl:L91-L97; any byte change in the key "
            + "produces a different TCATBAL record; "
            + "(7) DB2 timestamp YYYY-MM-DD-HH.MM.SS.HH0000 "
            + "(26 characters: 4-digit year + hyphen + 2-digit month + "
            + "hyphen + 2-digit day + hyphen + 2-digit hour + dot + "
            + "2-digit minute + dot + 2-digit second + dot + 2-digit "
            + "centiseconds derived from LocalDateTime.getNano() / "
            + "10_000_000 + literal '0000' trailer per AAP \u00a70.6.4); "
            + "(8) DISPLAY whitespace asymmetry preserved EXACTLY: "
            + "'TRANSACTIONS PROCESSED :' has ONE space before colon at "
            + "app/cbl/CBTRN02C.cbl:L227; 'TRANSACTIONS REJECTED  :' has "
            + "TWO spaces before colon at app/cbl/CBTRN02C.cbl:L228; any "
            + "single-space normalization in the Java translation will "
            + "fail byte parity on stdout.txt; "
            + "(9) RETURN-CODE 4 on rejects: if WS-REJECT-COUNT > 0 after "
            + "the main loop the program sets RETURN-CODE = 4 per "
            + "app/cbl/CBTRN02C.cbl:L229-L231 (vs default 0); verify the "
            + "actual exit code from the test harness; "
            + "(10) Reject codes 100/101/102/103/109 with EXACT text "
            + "descriptions in the 80-byte VALIDATION-TRAILER (4-char "
            + "zero-padded reason + 76-char left-padded description): "
            + "100='INVALID CARD NUMBER FOUND' (1500-A-LOOKUP-XREF "
            + "INVALID KEY), 101='ACCOUNT RECORD NOT FOUND' "
            + "(1500-B-LOOKUP-ACCT INVALID KEY), 102='OVERLIMIT "
            + "TRANSACTION' (over-limit check), 103='TRANSACTION RECEIVED "
            + "AFTER ACCT EXPIRATION' (expiration check), "
            + "109='ACCOUNT RECORD NOT FOUND' (intentional duplicate text "
            + "vs 101 per COBOL source; 2800-UPDATE-ACCOUNT-REC INVALID "
            + "KEY on REWRITE)."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
