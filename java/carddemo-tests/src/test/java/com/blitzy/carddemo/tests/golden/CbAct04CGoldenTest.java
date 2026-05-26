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
 * Byte-for-byte golden-record parity test for {@code CBACT04C}
 * (Interest Calculation Engine).
 *
 * <p><strong>CRITICAL DECIMAL-ARITHMETIC PROGRAM</strong> per AAP
 * &sect;0.6.1: the COBOL source carries the interest-computation
 * formula {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}
 * at {@code app/cbl/CBACT04C.cbl:L462-L468} <strong>with NO
 * {@code ROUNDED} clause</strong>. Per AAP &sect;0.6.1 ("Banker's rounding
 * for {@code ROUNDED}, truncation otherwise"), the absence of
 * {@code ROUNDED} means the Java translation MUST use
 * {@link java.math.RoundingMode#DOWN RoundingMode.DOWN} (truncation)
 * via the {@code Decimals} utility. The two boundary cases below
 * illustrate why banker's rounding ({@link java.math.RoundingMode#HALF_EVEN
 * HALF_EVEN}) would silently break parity:
 * <ul>
 *   <li>{@code (1000.00 * 0.06) / 1200 = 0.05} exactly &mdash;
 *       both modes agree</li>
 *   <li>{@code (1234.56 * 0.0635) / 1200 = 0.06532880&hellip;} &rarr;
 *       truncates to {@code 0.06}; {@code HALF_EVEN} rounds to
 *       {@code 0.07} &mdash; a 1-cent regression silently propagates to
 *       both the {@code TRAN-AMT} field of every emitted
 *       {@code TRAN-RECORD} and the {@code ACCT-CURR-CYC-CREDIT}
 *       accumulator on the {@code ACCOUNT-RECORD}, breaking byte
 *       parity on TWO of the three expected outputs</li>
 * </ul>
 *
 * <p><strong>Source COBOL:</strong> {@code app/cbl/CBACT04C.cbl} &mdash;
 * the {@code PROGRAM-ID CBACT04C} batch program ("Interest calculator")
 * driven by JCL step {@code app/jcl/INTCALC.jcl:L22}
 * ({@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}). The program
 * walks the TCATBAL VSAM KSDS sequentially, detects account boundaries
 * via the {@code TRANCAT-ACCT-ID} component of the 17-byte composite key
 * ({@code FD-TRANCAT-ACCT-ID PIC 9(11)} + {@code FD-TRANCAT-TYPE-CD
 * PIC X(02)} + {@code FD-TRANCAT-CD PIC 9(04)} per
 * {@code app/cpy/CVTRA01Y.cpy:&sect;TRAN-CAT-BAL-RECORD}), and for each
 * account loads the master record + card cross-reference, looks up the
 * per-account discount-group rate, computes monthly interest, and emits
 * a 350-byte {@code TRAN-RECORD} to the TRANSACT KSDS.</p>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.account.CbAct04C}. Per AAP
 * &sect;0.4.1 (program-by-program mapping), CBACT04C is translated into
 * the {@code application/account/} subpackage co-located with its
 * sibling translations {@code CbAct01C}, {@code CbAct02C},
 * {@code CbAct03C}, {@code CoActVwC}, {@code CoActUpC}. The Java
 * translation is instantiated by the harness via the 5-argument
 * constructor {@code CbAct04C(TransactionCategoryBalanceRepository,
 * AccountRepository, CardXrefRepository, DiscountGroupRepository,
 * TransactionRepository)} mirroring the COBOL 5-file open pattern
 * (TCATBALF primary + XREFFILE, ACCTFILE, DISCGRP, TRANSACT) per
 * {@code app/cbl/CBACT04C.cbl:L28-L65} {@code FILE-CONTROL} section.
 * Per AAP &sect;0.6.6, the program runs within a
 * {@code ScopedValue<BatchRunContext>} scope established by the base
 * harness {@link GoldenRecordTest#runProgram(Class, Path, List)}
 * (NEVER {@code ThreadLocal}).</p>
 *
 * <p><strong>DEFAULT-group fallback</strong> (preserve-as-is per AAP
 * &sect;0.7.1): when the per-account discount-group lookup
 * ({@code 1200-GET-INTEREST-RATE} at {@code app/cbl/CBACT04C.cbl:L415}
 * keyed by the account's {@code ACCT-GROUP-ID}) returns FILE STATUS
 * {@code '23'} (NOT FOUND), the COBOL program retries the lookup with
 * the literal group ID {@code "DEFAULT"} via the
 * {@code 1200-A-GET-DEFAULT-INT-RATE} paragraph at
 * {@code app/cbl/CBACT04C.cbl:L443-L460}. The
 * {@code app/data/ASCII/discgrp.txt} fixture contains 51 lines
 * structured as 3 logical blocks of 17 entries each (account-specific
 * "A..." prefixes, {@code "DEFAULT"} fallbacks, and {@code "ZEROAPR"}
 * zero-interest overrides per AAP &sect;0.7.1 fixture documentation),
 * so the test data exercises both the primary lookup path and the
 * fallback path. Any Java translation that omits the fallback retry
 * (e.g., returning a zero rate on the first NOT FOUND) will silently
 * compute the wrong interest for accounts not in the primary block,
 * breaking byte parity on both {@code transact.txt} and
 * {@code acctdata.txt}.</p>
 *
 * <p><strong>{@code TRAN-RECORD} emission contract</strong> (per
 * {@code 1300-B-WRITE-TX} at {@code app/cbl/CBACT04C.cbl:L473-L490}):
 * for every non-zero interest computation, the program emits a 350-byte
 * {@link com.blitzy.carddemo.domain.record.TranRecord} per
 * {@code app/cpy/CVTRA05Y.cpy:&sect;TRAN-RECORD} with:
 * <ul>
 *   <li>{@code TRAN-ID} &mdash; 16 chars: 8-char {@code PARM-DATE}
 *       (YYYYMMDD form from the JCL {@code PARM='2022071800'}) +
 *       8-char {@code WS-TRANID-SUFFIX} zero-padded counter</li>
 *   <li>{@code TRAN-TYPE-CD} = {@code "01"} (interest)</li>
 *   <li>{@code TRAN-CAT-CD}  = {@code "05"} (interest)</li>
 *   <li>{@code TRAN-SOURCE}  = {@code "System"}</li>
 *   <li>{@code TRAN-DESC}    = {@code "Int. for a/c "} + 11-digit
 *       {@code ACCT-ID}</li>
 *   <li>{@code TRAN-AMT}     = {@code WS-MONTHLY-INT} (truncated;
 *       see decimal-arithmetic note above)</li>
 *   <li>{@code TRAN-CARD-NUM} = {@code XREF-CARD-NUM} of the
 *       current account's first card</li>
 *   <li>{@code TRAN-ORIG-TS} = {@code TRAN-PROC-TS} = current DB2
 *       timestamp in {@code YYYY-MM-DD-HH.MM.SS.NNNNNN} format
 *       (26 chars per AAP &sect;0.6.4)</li>
 * </ul>
 * Byte parity on {@code transact.txt} depends on EXACT timestamp
 * encoding precision; any deviation in the DB2 timestamp format string
 * (e.g., space instead of hyphen between date and time, or different
 * sub-second precision) breaks parity. Test fixtures use a deterministic
 * processing date ({@link java.time.LocalDate#of(int, int, int)
 * LocalDate.of(2022, 7, 18)} matching the JCL
 * {@code PARM='2022071800'}) to ensure reproducibility per AAP
 * &sect;0.6.6.</p>
 *
 * <p><strong>{@code ACCT-CURR-CYC-CREDIT} accumulator</strong> (per
 * {@code 1050-UPDATE-ACCOUNT} at {@code app/cbl/CBACT04C.cbl:L350}):
 * on every account-boundary transition (when {@code TRANCAT-ACCT-ID}
 * changes) and at end-of-file, the program REWRITEs the prior
 * account's {@code ACCOUNT-RECORD} after adding
 * {@code WS-TOTAL-INT} (the per-account accumulator) to
 * {@code ACCT-CURR-BAL} (the {@code COMPUTE} without {@code ROUNDED}
 * at the same line preserves the cumulative truncation chain per AAP
 * &sect;0.6.1). The expected {@code acctdata.txt} output captures the
 * 300-byte ACCOUNT-RECORDs post-interest-posting.</p>
 *
 * <p><strong>Four input fixtures</strong> (all from
 * {@code app/data/ASCII/} per AAP &sect;0.4.1, read directly without
 * copying):
 * <ol>
 *   <li>{@value #TCATBAL_TXT} &mdash; primary TCATBAL records iterated
 *       sequentially by ascending composite key (50 bytes each per
 *       {@code app/cpy/CVTRA01Y.cpy}); wired to COBOL DD
 *       {@code TCATBALF} via {@code SELECT TCATBAL-FILE ASSIGN TO
 *       TCATBALF} at {@code app/cbl/CBACT04C.cbl:L28-L32}</li>
 *   <li>{@value #CARDXREF_TXT} &mdash; card cross-reference for
 *       account-id resolution via alternate index
 *       ({@code XREF-ACCT-ID} alternate record key); wired to COBOL DD
 *       {@code XREFFILE} via {@code SELECT XREF-FILE ASSIGN TO XREFFILE}
 *       at {@code app/cbl/CBACT04C.cbl:L34-L39}</li>
 *   <li>{@value #DISCGRP_TXT} &mdash; discount-group rate lookup
 *       (51 lines in 3-block structure with account-specific
 *       {@code "A..."} prefixes + {@code "DEFAULT"} fallback +
 *       {@code "ZEROAPR"} overrides per AAP &sect;0.7.1); wired to
 *       COBOL DD {@code DISCGRP} via {@code SELECT DISCGRP-FILE ASSIGN
 *       TO DISCGRP} at {@code app/cbl/CBACT04C.cbl:L47-L52}</li>
 *   <li>{@value #ACCTDATA_TXT} &mdash; account-master records updated
 *       in-place ({@code ACCT-CURR-CYC-CREDIT} accumulator REWRITE
 *       per {@code 1050-UPDATE-ACCOUNT}); wired to COBOL DD
 *       {@code ACCTFILE} via {@code SELECT ACCOUNT-FILE ASSIGN TO
 *       ACCTFILE} at {@code app/cbl/CBACT04C.cbl:L41-L45}</li>
 * </ol>
 *
 * <p><strong>Three expected outputs</strong> (per AAP &sect;0.6.11
 * multi-output pattern):
 * <ol>
 *   <li>{@value #TRANSACT_TXT} &mdash; new {@code TRAN-RECORD} entries
 *       written sequentially to TRANSACT by
 *       {@code 1300-B-WRITE-TX}; 350 bytes per record per
 *       {@code app/cpy/CVTRA05Y.cpy:&sect;TRAN-RECORD}</li>
 *   <li>{@value #ACCTDATA_TXT} &mdash; ACCOUNT records REWRITTEN by
 *       {@code 1050-UPDATE-ACCOUNT}; 300 bytes per record per
 *       {@code app/cpy/CVACT01Y.cpy:&sect;ACCOUNT-RECORD}</li>
 *   <li>{@value #STDOUT_TXT} &mdash; captured COBOL {@code DISPLAY}
 *       stream including the {@code "START OF EXECUTION OF PROGRAM
 *       CBACT04C"} banner at {@code app/cbl/CBACT04C.cbl:L184}, the
 *       {@code "END OF EXECUTION OF PROGRAM CBACT04C"} banner at
 *       {@code :L231}, and any per-paragraph error {@code DISPLAY}s
 *       that fire on the fixture data
 *       ({@code "ERROR READING TCATBAL FILE"},
 *       {@code "ERROR READING DEFAULT DISCLOSURE GROUP"},
 *       {@code "ABENDING PROGRAM"}, etc.)</li>
 * </ol>
 *
 * <p>This test is the <strong>NON-NEGOTIABLE PR gate</strong> per AAP
 * &sect;0.6.11 ("These are non-negotiable and run on every PR"). When
 * enabled, failure blocks the PR.</p>
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
 * {@code src/test/resources/golden/cbact04c/expected/}.</p>
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.account.CbAct04C
 * @see com.blitzy.carddemo.domain.util.Decimals
 * @since 25
 */
@DisplayName("CBACT04C \u2014 Interest Calculation Engine Golden-Record Parity (multi-file)")
public class CbAct04CGoldenTest extends GoldenRecordTest {

    /**
     * Identifier of the per-program fixture directory under
     * {@code src/test/resources/golden/}. Lowercase form of the COBOL
     * {@code PROGRAM-ID CBACT04C} per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention shared by all 28 per-program golden tests.
     */
    private static final String PROGRAM_DIR = "cbact04c";

    /**
     * Primary input fixture name under {@code app/data/ASCII/}: TCATBAL
     * records iterated sequentially in ascending composite-key order
     * (17-byte composite key: 11-digit {@code TRANCAT-ACCT-ID} +
     * 2-char {@code TRANCAT-TYPE-CD} + 4-digit {@code TRANCAT-CD} per
     * {@code app/cpy/CVTRA01Y.cpy:&sect;TRAN-CAT-BAL-RECORD}). 50 bytes
     * per record (17-byte key + {@code TRAN-CAT-BAL PIC S9(09)V99}
     * BigDecimal scale 2 + {@code FILLER PIC X(22)}). Wired to COBOL DD
     * {@code TCATBALF} via {@code SELECT TCATBAL-FILE ASSIGN TO TCATBALF}
     * at {@code app/cbl/CBACT04C.cbl:L28-L32}. Read directly from
     * {@code app/} via
     * {@link GoldenRecordTest#resolveAppDataPath(String)} per AAP
     * &sect;0.4.1 and &sect;0.6.11.
     */
    private static final String TCATBAL_TXT = "tcatbal.txt";

    /**
     * Card cross-reference auxiliary fixture name under
     * {@code app/data/ASCII/}.
     *
     * <p>Wired to the COBOL DD {@code XREFFILE} via the
     * {@code SELECT XREF-FILE ASSIGN TO XREFFILE} clause at
     * {@code app/cbl/CBACT04C.cbl:L34-L39}. Read randomly by the
     * {@code 1110-GET-XREF-DATA} paragraph keyed by alternate index
     * {@code FD-XREF-ACCT-ID PIC 9(11)} matching the
     * {@code TRANCAT-ACCT-ID} component of the current TCATBAL record
     * to resolve {@code XREF-CARD-NUM} for the emitted
     * {@code TRAN-RECORD}{@code .TRAN-CARD-NUM} field per AAP
     * &sect;0.4.1.</p>
     */
    private static final String CARDXREF_TXT = "cardxref.txt";

    /**
     * Discount-group rate lookup auxiliary fixture name under
     * {@code app/data/ASCII/}.
     *
     * <p>Wired to the COBOL DD {@code DISCGRP} via the
     * {@code SELECT DISCGRP-FILE ASSIGN TO DISCGRP} clause at
     * {@code app/cbl/CBACT04C.cbl:L47-L52}. Read randomly by the
     * {@code 1200-GET-INTEREST-RATE} paragraph keyed by the 16-byte
     * composite key {@code FD-DISCGRP-KEY}
     * ({@code DIS-ACCT-GROUP-ID PIC X(10)} +
     * {@code DIS-TRAN-TYPE-CD PIC X(02)} +
     * {@code DIS-TRAN-CAT-CD PIC 9(04)} per
     * {@code app/cpy/CVTRA02Y.cpy:&sect;DIS-GROUP-RECORD}). On FILE
     * STATUS {@code '23'} (NOT FOUND), the
     * {@code 1200-A-GET-DEFAULT-INT-RATE} paragraph at
     * {@code app/cbl/CBACT04C.cbl:L443-L460} retries with group ID
     * {@code "DEFAULT"} per AAP &sect;0.7.1 preserve-as-is mandate.
     * The 51-line fixture structure (3 blocks of 17 entries:
     * account-specific {@code "A..."} prefixes, {@code "DEFAULT"}
     * fallback, {@code "ZEROAPR"} zero-interest override) exercises
     * both the primary and fallback paths.</p>
     */
    private static final String DISCGRP_TXT = "discgrp.txt";

    /**
     * Account-master auxiliary fixture name under
     * {@code app/data/ASCII/}.
     *
     * <p>Wired to the COBOL DD {@code ACCTFILE} via the
     * {@code SELECT ACCOUNT-FILE ASSIGN TO ACCTFILE} clause at
     * {@code app/cbl/CBACT04C.cbl:L41-L45}. Read randomly by the
     * {@code 1100-GET-ACCT-DATA} paragraph keyed by {@code FD-ACCT-ID
     * PIC 9(11)} matching the {@code XREF-ACCT-ID} from the prior
     * cross-reference lookup. REWRITTEN by the
     * {@code 1050-UPDATE-ACCOUNT} paragraph at
     * {@code app/cbl/CBACT04C.cbl:L350} after applying the accumulated
     * {@code WS-TOTAL-INT} to {@code ACCT-CURR-BAL} and zeroing
     * {@code ACCT-CURR-CYC-CREDIT} / {@code ACCT-CURR-CYC-DEBIT}
     * counters. The 300-byte layout follows
     * {@code app/cpy/CVACT01Y.cpy:&sect;ACCOUNT-RECORD}.</p>
     */
    private static final String ACCTDATA_TXT = "acctdata.txt";

    /**
     * Expected-output name for the TRANSACT writes produced by
     * {@code 1300-B-WRITE-TX} at {@code app/cbl/CBACT04C.cbl:L473-L490}.
     *
     * <p>Sequential WRITE of {@code TRAN-RECORD} entries (350 bytes
     * each per {@code app/cpy/CVTRA05Y.cpy:&sect;TRAN-RECORD}) to the
     * TRANSACT KSDS. Each emission carries: {@code TRAN-TYPE-CD =
     * "01"} (interest), {@code TRAN-CAT-CD = "05"} (interest),
     * {@code TRAN-SOURCE = "System"}, {@code TRAN-DESC = "Int. for a/c
     * "} + 11-digit {@code ACCT-ID}, {@code TRAN-AMT = WS-MONTHLY-INT}
     * (truncated per AAP &sect;0.6.1), {@code TRAN-CARD-NUM =
     * XREF-CARD-NUM}, and matching {@code TRAN-ORIG-TS} /
     * {@code TRAN-PROC-TS} DB2 timestamps. Byte parity depends on
     * EXACT truncation behavior of the interest formula AND exact DB2
     * timestamp formatting (26 chars
     * {@code YYYY-MM-DD-HH.MM.SS.NNNNNN} per AAP &sect;0.6.4).</p>
     */
    private static final String TRANSACT_TXT = "transact.txt";

    /**
     * Expected-output name for the ACCOUNT REWRITES produced by
     * {@code 1050-UPDATE-ACCOUNT} at {@code app/cbl/CBACT04C.cbl:L350}.
     *
     * <p>On every account-boundary transition (detected via
     * {@code TRANCAT-ACCT-ID} change in the primary TCATBAL stream)
     * and at end-of-file, the prior account's
     * {@code ACCOUNT-RECORD} is REWRITTEN with:
     * <ul>
     *   <li>{@code ACCT-CURR-BAL = ACCT-CURR-BAL + WS-TOTAL-INT}
     *       (the un-ROUNDED accumulator preserves the
     *       per-record truncation chain per AAP &sect;0.6.1)</li>
     *   <li>{@code ACCT-CURR-CYC-CREDIT = 0} (cycle counters
     *       zeroed)</li>
     *   <li>{@code ACCT-CURR-CYC-DEBIT = 0}</li>
     * </ul>
     * 300 bytes per record per
     * {@code app/cpy/CVACT01Y.cpy:&sect;ACCOUNT-RECORD}; byte parity
     * depends on exact BigDecimal scale preservation (PIC S9(10)V99
     * implies scale 2 on the balance fields per AAP &sect;0.1.3
     * "Decimal scale preservation").</p>
     */
    private static final String ACCTDATA_OUT_TXT = "acctdata.txt";

    /**
     * Expected-output name for the captured COBOL {@code DISPLAY}
     * stream.
     *
     * <p>Captures the START banner
     * ({@code 'START OF EXECUTION OF PROGRAM CBACT04C'} at
     * {@code app/cbl/CBACT04C.cbl:L184}), any per-paragraph error
     * {@code DISPLAY}s that fire on the fixture data
     * ({@code 'ERROR READING TCATBAL FILE'},
     * {@code 'ERROR READING DEFAULT DISCLOSURE GROUP'} from
     * {@code 1200-A-GET-DEFAULT-INT-RATE}, etc.), and the END banner
     * ({@code 'END OF EXECUTION OF PROGRAM CBACT04C'} at
     * {@code app/cbl/CBACT04C.cbl:L231}). Currently a placeholder
     * pending COBOL capture per AAP &sect;0.6.11.</p>
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.account.CbAct04C}{@code .class}.
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
        return com.blitzy.carddemo.application.account.CbAct04C.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to
     * {@code <repo-root>/app/data/ASCII/tcatbal.txt} resolved via
     * {@link GoldenRecordTest#resolveAppDataPath(String)}. This is the
     * primary TCATBAL input (50 bytes per record per
     * {@code app/cpy/CVTRA01Y.cpy:&sect;TRAN-CAT-BAL-RECORD}) that
     * the program walks sequentially via
     * {@code 1000-TCATBALF-GET-NEXT} at
     * {@code app/cbl/CBACT04C.cbl:L246} per AAP &sect;0.4.1.</p>
     */
    @Override
    protected Path inputFile() {
        return resolveAppDataPath(TCATBAL_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to the captured COBOL
     * TRANSACT writes at
     * {@code src/test/resources/golden/cbact04c/expected/transact.txt},
     * resolved via
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}.
     * This is the <em>primary</em> expected output; the
     * {@link #expectedOutputs()} override below declares the
     * complete 3-output list ({@value #TRANSACT_TXT},
     * {@value #ACCTDATA_OUT_TXT}, {@value #STDOUT_TXT}) for the
     * multi-output byte parity assertion per AAP &sect;0.6.11.</p>
     */
    @Override
    protected Path expectedOutputFile() {
        return resolveExpectedOutputPath(PROGRAM_DIR, TRANSACT_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns an immutable 3-element {@link List} of auxiliary
     * input fixture paths, reflecting the COBOL CBACT04C 5-file open
     * pattern (TCATBALF primary + 4 auxiliary: XREFFILE, ACCTFILE,
     * DISCGRP, TRANSACT &mdash; of which TRANSACT is an output-only
     * sink and has no input fixture). The 3 auxiliary inputs are
     * declared in deterministic order matching the COBOL FILE-CONTROL
     * declarations at {@code app/cbl/CBACT04C.cbl:L28-L65}:
     * <ol>
     *   <li>{@code app/data/ASCII/cardxref.txt} (DD {@code XREFFILE},
     *       read by {@code 1110-GET-XREF-DATA} via alternate-index
     *       random read on {@code XREF-ACCT-ID})</li>
     *   <li>{@code app/data/ASCII/discgrp.txt} (DD {@code DISCGRP},
     *       read randomly by {@code 1200-GET-INTEREST-RATE} keyed by
     *       the 16-byte composite key {@code FD-DISCGRP-KEY}, with
     *       {@code 1200-A-GET-DEFAULT-INT-RATE} fallback on FILE
     *       STATUS {@code '23'})</li>
     *   <li>{@code app/data/ASCII/acctdata.txt} (DD {@code ACCTFILE},
     *       read randomly by {@code 1100-GET-ACCT-DATA} and
     *       REWRITTEN by {@code 1050-UPDATE-ACCOUNT} per AAP
     *       &sect;0.4.1)</li>
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
     * before invoking {@code CbAct04C.run(LocalDate)} per AAP
     * &sect;0.6.6.</p>
     */
    @Override
    protected List<Path> auxiliaryInputs() {
        return List.of(
            resolveAppDataPath(CARDXREF_TXT),
            resolveAppDataPath(DISCGRP_TXT),
            resolveAppDataPath(ACCTDATA_TXT)
        );
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns an immutable 3-element {@link List} of
     * {@link ExpectedOutput} declarations covering every output that
     * the COBOL CBACT04C program produces per AAP &sect;0.6.11
     * multi-output pattern:
     * <ol>
     *   <li>{@value #TRANSACT_TXT} &mdash; sequential WRITE of new
     *       {@code TRAN-RECORD} entries to the TRANSACT KSDS via
     *       {@code 1300-B-WRITE-TX}; 350 bytes per record per
     *       {@code app/cpy/CVTRA05Y.cpy}. Byte parity here verifies
     *       BOTH the interest-formula truncation (per AAP &sect;0.6.1
     *       {@link java.math.RoundingMode#DOWN RoundingMode.DOWN}) AND
     *       the DB2 timestamp encoding precision (per AAP
     *       &sect;0.6.4)</li>
     *   <li>{@value #ACCTDATA_OUT_TXT} &mdash; REWRITTEN ACCOUNT
     *       records via {@code 1050-UPDATE-ACCOUNT}; 300 bytes per
     *       record per {@code app/cpy/CVACT01Y.cpy}. Byte parity here
     *       verifies the cumulative accumulator chain
     *       ({@code WS-TOTAL-INT}) preserves the per-record
     *       truncation</li>
     *   <li>{@value #STDOUT_TXT} &mdash; captured COBOL
     *       {@code DISPLAY} stream including the START/END banners
     *       and any per-paragraph error messages</li>
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
            new ExpectedOutput(ACCTDATA_OUT_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, ACCTDATA_OUT_TXT)),
            new ExpectedOutput(STDOUT_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, STDOUT_TXT))
        );
    }

    /**
     * Byte-for-byte parity assertion, currently {@code @Disabled}
     * pending the COBOL CBACT04C baseline capture per AAP
     * &sect;0.6.11 ("Initial test scaffolding may use placeholder
     * expected files marked {@code @Disabled} until COBOL captures
     * are available").
     *
     * <p>The {@code @Disabled} annotation will be removed in the same
     * PR that commits non-placeholder content under
     * {@code src/test/resources/golden/cbact04c/expected/} for all
     * 3 declared outputs per the capture procedure documented in
     * {@code java/MIGRATION_NOTES.md}. The method body delegates to
     * {@link GoldenRecordTest#byteForByteParity()} so the actual
     * byte-by-byte assertion logic remains centralised in the base
     * class.</p>
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
     * {@link CbTrn02CGoldenTest}, {@link CbTrn03CGoldenTest}, and
     * {@link DateValidatorGoldenTest}.</p>
     *
     * <p>The {@code @Disabled} reason embedded below documents the
     * 7-point verification checklist that the future maintainer must
     * verify before removing the annotation. CBACT04C is a CRITICAL
     * decimal-arithmetic gate; the checklist deliberately enumerates
     * every invariant whose violation would silently break observable
     * behavior.</p>
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
        "Awaiting COBOL CBACT04C baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md for the regeneration procedure. "
            + "CBACT04C is a CRITICAL decimal-arithmetic gate; the "
            + "Decimals utility's RoundingMode.DOWN (truncation) behavior "
            + "is verified here under realistic interest-calculation "
            + "workloads. Verify (all 7 must hold before removing "
            + "@Disabled): "
            + "(1) Decimals utility truncation matches COBOL "
            + "(MathContext.DECIMAL128 + RoundingMode.DOWN per AAP \u00a70.6.1 "
            + "for the un-ROUNDED COMPUTE statement at "
            + "app/cbl/CBACT04C.cbl:L464-L465: "
            + "'COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200' "
            + "with NO ROUNDED clause; the formula (1234.56 * 0.0635) / 1200 "
            + "must truncate to 0.06, NOT round to 0.07 with HALF_EVEN); "
            + "(2) DEFAULT-group fallback works per AAP \u00a70.7.1 "
            + "(when 1200-GET-INTEREST-RATE returns FILE STATUS '23' "
            + "NOT FOUND, 1200-A-GET-DEFAULT-INT-RATE retries with "
            + "group-id literal 'DEFAULT' at "
            + "app/cbl/CBACT04C.cbl:L443-L460; the discgrp.txt fixture's "
            + "3-block structure exercises both paths); "
            + "(3) ScopedValue<BatchRunContext> scope established by the "
            + "base harness per AAP \u00a70.6.6 (NEVER ThreadLocal); the "
            + "processing date is deterministic LocalDate.of(2022, 7, 18) "
            + "matching the JCL PARM='2022071800' at "
            + "app/jcl/INTCALC.jcl:L22 to ensure reproducibility; "
            + "(4) TRAN-ID format = 16 chars (8-char YYYYMMDD + 8-char "
            + "zero-padded WS-TRANID-SUFFIX counter) per "
            + "app/cbl/CBACT04C.cbl:L476-L480; "
            + "(5) DB2 timestamp format = 26 chars "
            + "YYYY-MM-DD-HH.MM.SS.NNNNNN (hyphen-only date separators, "
            + "dot-only time separators, 6-digit sub-second precision) "
            + "per AAP \u00a70.6.4 for TRAN-ORIG-TS and TRAN-PROC-TS "
            + "fields of the emitted TRAN-RECORDs; "
            + "(6) ACCT-CURR-CYC-CREDIT/DEBIT zeroed and ACCT-CURR-BAL "
            + "incremented by WS-TOTAL-INT on every 1050-UPDATE-ACCOUNT "
            + "REWRITE at app/cbl/CBACT04C.cbl:L350; the cumulative "
            + "accumulator chain preserves per-record truncation (no "
            + "intermediate ROUNDED at any step per AAP \u00a70.6.1); "
            + "(7) Sequential execution preserved (no virtual-thread "
            + "fan-out across the TCATBAL primary loop per AAP \u00a70.6.6: "
            + "'virtual threads are NOT a license to reorder records, "
            + "change sort orders, or break sequencing'); account "
            + "boundary detection by TRANCAT-ACCT-ID changes requires "
            + "strict input ordering."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
