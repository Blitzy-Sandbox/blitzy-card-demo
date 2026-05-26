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
 * Byte-for-byte golden-record parity test for {@code COBIL00C}
 * (Bill Payment Online Transaction).
 *
 * <p><strong>Source COBOL:</strong> {@code app/cbl/COBIL00C.cbl} &mdash;
 * the {@code PROGRAM-ID COBIL00C} ({@code app/cbl/COBIL00C.cbl:L24})
 * online-CICS program backing transaction {@code CB00}
 * ({@code WS-TRANID PIC X(04) VALUE 'CB00'} at
 * {@code app/cbl/COBIL00C.cbl:L38}, mapset {@code COBIL00} via
 * {@code COPY COBIL00.} at {@code app/cbl/COBIL00C.cbl:L74}). This is
 * the canonical <strong>MONEY-HANDLING online program</strong> in the
 * CardDemo application &mdash; the operator enters an account
 * identifier, optionally a {@code (Y/N)} confirmation, and on
 * confirmation {@code 'Y'} the program performs an
 * <strong>atomic-from-the-user's-perspective multi-file mutation</strong>:
 * (1)&nbsp;READ-UPDATE on the ACCOUNT-RECORD from the {@code ACCTDAT}
 * VSAM KSDS to load the current balance, (2)&nbsp;READ on the
 * CARD-XREF-RECORD from the {@code CXACAIX} alternate index to recover
 * the card number, (3)&nbsp;{@code STARTBR + READPREV + ENDBR} on the
 * {@code TRANSACT} VSAM KSDS to allocate the next sequential
 * {@code TRAN-ID}, (4)&nbsp;WRITE of a fully-populated CVTRA05Y
 * {@code TRAN-RECORD} (350-byte fixed-width record with
 * {@code TRAN-AMT = ACCT-CURR-BAL}, {@code TRAN-TYPE-CD = '02'},
 * {@code TRAN-DESC = 'BILL PAYMENT - ONLINE'},
 * {@code TRAN-MERCHANT-ID = 999999999},
 * {@code TRAN-MERCHANT-NAME = 'BILL PAYMENT'},
 * {@code TRAN-MERCHANT-CITY = 'N/A'},
 * {@code TRAN-MERCHANT-ZIP = 'N/A'},
 * {@code TRAN-ORIG-TS = TRAN-PROC-TS = current timestamp}), and
 * (5)&nbsp;REWRITE of the ACCOUNT-RECORD after
 * {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}
 * (effectively zeroing the balance via
 * {@link com.blitzy.carddemo.domain.util.Decimals#subtract} at
 * scale&nbsp;2 with banker's rounding per AAP &sect;0.6.1).</p>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.billpay.CoBil00C}. Per AAP
 * &sect;0.4.1 (program-by-program mapping) COBIL00C is translated into
 * the {@code application/billpay/} subpackage (its own subpackage
 * because no other program shares the bill-payment business surface).
 * The Java translation has a 5-argument primary constructor
 * {@code CoBil00C(AccountRepository, CardXrefRepository,
 * TransactionRepository, ProgramRegistry, Clock)} matching the COBOL
 * collaborator surface (one repository port per VSAM file, the
 * dynamic-CALL routing facility for {@code EXEC CICS XCTL} dispatch,
 * and a deterministic time source for the {@code GET-CURRENT-TIMESTAMP}
 * paragraph that replaces {@code EXEC CICS ASKTIME + FORMATTIME}). The
 * base harness
 * {@link GoldenRecordTest#runProgram(Class, java.nio.file.Path,
 * java.util.List)} hook resolves these collaborators against
 * file-backed adapters wired to the three auxiliary fixtures returned
 * by {@link #auxiliaryInputs()}.</p>
 *
 * <h2>Why This Test Is The Decimals Utility Canary</h2>
 *
 * <p><strong>CoBil00C is the simplest path that exercises BOTH
 * {@code BigDecimal} arithmetic AND fixed-width record encoding</strong>
 * across two distinct output files. Any defect in
 * {@link com.blitzy.carddemo.domain.util.Decimals} (rounding mode,
 * scale preservation, sign-nybble handling, truncation vs.
 * banker's-rounding selection at the
 * {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT} site at
 * {@code app/cbl/COBIL00C.cbl:L234}) surfaces here through a byte
 * mismatch in EITHER:</p>
 * <ul>
 *   <li>{@code transact.txt} &mdash; the newly WRITTEN
 *       {@code TRAN-RECORD} carries {@code TRAN-AMT} encoded at
 *       {@code PIC S9(09)V99} (350-byte record per
 *       {@code app/cpy/CVTRA05Y.cpy:&sect;TRAN-RECORD}). The value
 *       comes from {@code MOVE ACCT-CURR-BAL TO TRAN-AMT} at
 *       {@code app/cbl/COBIL00C.cbl:L224} <em>before</em> the
 *       subtraction, so a {@code BigDecimal} that has been normalised
 *       to a shorter scale (e.g. {@code 1.2} instead of {@code 1.20})
 *       would emit a single-byte-shifted encoding here.</li>
 *   <li>{@code acctdata.txt} &mdash; the REWRITTEN
 *       {@code ACCOUNT-RECORD} carries the post-subtraction
 *       {@code ACCT-CURR-BAL} encoded at {@code PIC S9(10)V99}
 *       (300-byte record per
 *       {@code app/cpy/CVACT01Y.cpy:&sect;ACCOUNT-RECORD}). Because
 *       the bill-payment idiom subtracts the FULL balance from
 *       itself, the post-state value is mathematically zero but MUST
 *       be encoded as {@code +0000000000.00} with the COBOL packed-
 *       decimal sign nybble preserved &mdash; per AAP &sect;0.6.1
 *       "a {@code BigDecimal} value of {@code 1.20} must NOT be
 *       normalized to {@code 1.2}" and "every monetary
 *       {@code BigDecimal} operation must declare
 *       {@code setScale(2, RoundingMode.&hellip;)} to preserve
 *       trailing zeros". Any scale loss here means
 *       {@code expected_acctdata.txt != actual_acctdata.txt}.</li>
 * </ul>
 *
 * <h2>The Five Test Scenarios</h2>
 *
 * <p>The {@code input_scenario.txt} fixture under
 * {@code src/test/resources/golden/cobil00c/expected/} encodes the
 * five-submission sequence that exercises the full state space of
 * the CB00 transaction:</p>
 * <ol>
 *   <li><strong>Valid payment (happy path)</strong> &mdash; operator
 *       enters a valid {@code ACTIDINI} (e.g. {@code "00000000010"}
 *       from {@code app/data/ASCII/acctdata.txt}) with
 *       {@code CONFIRMI = 'Y'}; the program performs the full 5-step
 *       multi-file mutation and emits the verbatim
 *       {@code "Payment successful.  Your Transaction ID is N."}
 *       GREEN message (note the DOUBLE space between
 *       {@code "successful."} and {@code "Your"} preserved per
 *       AAP &sect;0.7.1 from
 *       {@code app/cbl/COBIL00C.cbl:L527-L531}).</li>
 *   <li><strong>Insufficient funds / zero balance</strong> &mdash;
 *       operator enters an account whose {@code ACCT-CURR-BAL <=
 *       ZEROS} (per {@code app/cbl/COBIL00C.cbl:L198}); the program
 *       rejects WITHOUT any file mutation and emits the verbatim
 *       {@code "You have nothing to pay..."} RED message. NEITHER
 *       {@code transact.txt} NOR {@code acctdata.txt} is mutated on
 *       this path; the byte-for-byte parity assertion verifies the
 *       no-write invariant.</li>
 *   <li><strong>Invalid account (NOTFND on ACCTDAT)</strong> &mdash;
 *       operator enters a non-existent {@code ACTIDINI} (e.g.
 *       {@code "99999999999"}); the READ-ACCTDAT-FILE paragraph at
 *       {@code app/cbl/COBIL00C.cbl:L343-L372} fails with
 *       {@code DFHRESP(NOTFND)} and the program emits the verbatim
 *       {@code "Account ID NOT found..."} RED message (one of the
 *       three message sites that share this literal per the
 *       fixture-directory README contrast matrix).</li>
 *   <li><strong>PF3 back (XCTL to CDEMO-FROM-PROGRAM)</strong>
 *       &mdash; operator presses {@code DFHPF3}; per
 *       {@code app/cbl/COBIL00C.cbl:L128-L135} the program transfers
 *       to {@code CDEMO-FROM-PROGRAM} (or {@code COMEN01C} as the
 *       fallback) via {@code EXEC CICS XCTL}. The Java translation
 *       returns an {@code Outcome.Xctl} carrying the target program
 *       id; no file mutation occurs.</li>
 *   <li><strong>PF4 clear (INITIALIZE-ALL-FIELDS + redisplay)</strong>
 *       &mdash; operator presses {@code DFHPF4}; the
 *       {@code CLEAR-CURRENT-SCREEN} paragraph at
 *       {@code app/cbl/COBIL00C.cbl:L552-L555} resets all input
 *       fields and redisplays the empty map; no file mutation
 *       occurs.</li>
 * </ol>
 *
 * <h2>Auxiliary Input Fixtures</h2>
 *
 * <p>Three ASCII fixtures from {@code app/data/ASCII/} are wired
 * through {@link #auxiliaryInputs()} to back the multi-file lookup:</p>
 * <ol>
 *   <li>{@code acctdata.txt} (50 account records, 300 bytes each per
 *       {@code app/cpy/CVACT01Y.cpy:&sect;ACCOUNT-RECORD}) backs the
 *       {@link com.blitzy.carddemo.domain.port.AccountRepository}
 *       (READ-UPDATE / REWRITE).</li>
 *   <li>{@code custdata.txt} (customer records per
 *       {@code app/cpy/CVCUS01Y.cpy:&sect;CUSTOMER-RECORD}) supplies
 *       supplemental customer context loaded by the harness's
 *       default file-adapter wiring; while the COBOL
 *       {@code COBIL00C} program itself does NOT issue a
 *       {@code READ CUSTDAT}, the fixture is wired so the harness's
 *       composition root can construct a complete adapter graph and
 *       so future regressions that accidentally introduce a
 *       customer lookup are detectable via the resulting
 *       byte mismatch in {@code stdout.txt}.</li>
 *   <li>{@code cardxref.txt} (50-byte cross-reference records per
 *       {@code app/cpy/CVACT03Y.cpy:&sect;CARD-XREF-RECORD}) backs
 *       the {@link com.blitzy.carddemo.domain.port.CardXrefRepository}
 *       used by paragraph {@code READ-CXACAIX-FILE} at
 *       {@code app/cbl/COBIL00C.cbl:L408-L436} to recover
 *       {@code XREF-CARD-NUM} (the value moved into
 *       {@code TRAN-CARD-NUM} on the WRITE path at
 *       {@code app/cbl/COBIL00C.cbl:L225}).</li>
 * </ol>
 *
 * <p>Per AAP &sect;0.4.1 and &sect;0.6.11 the fixtures are read
 * DIRECTLY from {@code app/} via the
 * {@link GoldenRecordTest#resolveAppDataPath(String)} helper &mdash;
 * NOT copied into {@code java/carddemo-tests/} (the COBOL source tree
 * remains the single source of truth for fixture data).</p>
 *
 * <h2>Expected Outputs (multi-output scenario)</h2>
 *
 * <p>Per AAP &sect;0.6.11 multi-output pattern (overriding
 * {@link #expectedOutputs()} rather than relying on the base class's
 * single-output default), this test declares FOUR byte-for-byte
 * parity targets:</p>
 * <ol>
 *   <li>{@value #TRANSACT_TXT} &mdash; the post-run snapshot of the
 *       {@code TRANSACT} KSDS. On the valid-payment scenario the
 *       file is extended with one 350-byte {@code TRAN-RECORD};
 *       on the other four scenarios the file remains byte-identical
 *       to its pre-test (empty) state. This is the
 *       <strong>primary Decimals utility canary</strong>:
 *       {@code TRAN-AMT} is encoded at {@code PIC S9(09)V99} and any
 *       scale loss or sign-nybble defect surfaces here first.</li>
 *   <li>{@value #ACCTDATA_TXT} &mdash; the post-run snapshot of the
 *       (copied) {@code ACCTDAT} fixture. On the valid-payment
 *       scenario exactly ONE account's {@code ACCT-CURR-BAL} is
 *       REWRITTEN to {@code +0000000000.00} (the result of
 *       {@code curBal - curBal} via
 *       {@link com.blitzy.carddemo.domain.util.Decimals#subtract});
 *       on the other four scenarios the file remains byte-identical
 *       to its pre-test state. This is the
 *       <strong>secondary Decimals utility canary</strong>: the
 *       trailing zero of the {@code .00} fractional part must NOT
 *       collapse to {@code .0} or the {@code PIC S9(10)V99} encoder
 *       emits the wrong number of bytes.</li>
 *   <li>{@value #STDOUT_TXT} &mdash; the PAN-masked SLF4J/DISPLAY
 *       trace per AAP &sect;0.7.2. Every PAN reference is masked to
 *       its last 4 digits before emission (12 leading mask
 *       characters such as {@code "************1234"}). Captures
 *       the verbatim error messages from the COBOL source
 *       (15&nbsp;distinct strings per the
 *       {@code expected/README.md} verbatim message catalog,
 *       including {@code "Acct ID can NOT be empty..."},
 *       {@code "You have nothing to pay..."},
 *       {@code "Account ID NOT found..."},
 *       {@code "Invalid value. Valid values are (Y/N)..."}, the
 *       50-char {@code CCDA-MSG-INVALID-KEY} with its trailing
 *       9&nbsp;spaces preserved per {@code app/cpy/CSMSG01Y.cpy}, and
 *       the GREEN double-space success message
 *       {@code "Payment successful.  Your Transaction ID is N."}).</li>
 *   <li>{@value #BMS_OUTPUT_TXT} &mdash; the serialized
 *       {@code CoBil00Output} screen states (one per submission in
 *       the scenario, five total) with FULL PAN. The BMS screen is
 *       an authorized rendering surface distinct from the logging
 *       surface; PAN masking does NOT apply to this output per AAP
 *       &sect;0.7.2. Captures the per-field rendering of
 *       {@code TRNNAME='CB00'}, {@code PGMNAME='COBIL00C'},
 *       {@code TITLE01}, {@code TITLE02}, {@code CURDATE},
 *       {@code CURTIME}, {@code ACTIDINO}, {@code CURBALO}
 *       (BigDecimal -&gt; {@code PIC +9999999999.99} format per
 *       {@code app/cbl/COBIL00C.cbl:L56}), {@code CONFIRMO}, and
 *       {@code ERRMSGO} per the 1920-byte 24&times;80 BMS layout
 *       defined in {@code app/bms/COBIL00.bms}.</li>
 * </ol>
 *
 * <h2>PAN Masking Verification (AAP &sect;0.7.2)</h2>
 *
 * <p>The captured fixtures verify <strong>two separate code paths</strong>
 * with different PAN visibility:</p>
 * <ul>
 *   <li>{@link #STDOUT_TXT} &mdash; SLF4J / DISPLAY trace. Per AAP
 *       &sect;0.7.2 ("no card PAN logged in full; mask all but last
 *       4 digits in logs and error messages"), every log line
 *       mentioning a card number must show only the last 4 digits
 *       (12&nbsp;leading mask characters such as
 *       {@code "************1234"}). The Java translation routes PAN
 *       through a masking helper before SLF4J emission; the captured
 *       stdout fixture asserts the masked form byte-for-byte.</li>
 *   <li>{@link #BMS_OUTPUT_TXT} &mdash; serialized
 *       {@link com.blitzy.carddemo.application.billpay.CoBil00C}
 *       output screen states (one per submission). The BMS screen
 *       displays the <strong>FULL</strong> PAN because the user is
 *       authorized to view their own card data through the 3270
 *       terminal; the masking rule applies to logs only, not to
 *       authorized screen display. Storage and logging are
 *       different surfaces (per AAP &sect;0.1.1 surfaced implicit
 *       requirement).</li>
 * </ul>
 *
 * <p>This test is the <strong>NON-NEGOTIABLE PR gate</strong> per
 * AAP &sect;0.6.11. Any deviation in the {@code Decimals} arithmetic
 * (rounding direction, scale preservation, sign nybble), the
 * verbatim COBOL messages (15&nbsp;distinct strings including the
 * double-space success line), the multi-file atomic mutation
 * sequence ({@code STARTBR + READPREV + ENDBR + WRITE TRANSACT +
 * REWRITE ACCTDAT}), the PAN-masking behavior in logs, or the
 * insufficient-funds no-write invariant breaks parity and blocks
 * the PR.</p>
 *
 * <p><strong>Scaffolding state</strong>: per AAP &sect;0.6.11
 * ("Initial test scaffolding may use placeholder expected files
 * marked {@code @Disabled} until COBOL captures are available; the
 * harness skeleton, base class, and per-program test classes are
 * created unconditionally"), the {@link #byteForByteParity()}
 * override below is annotated {@code @Disabled} with the 5-point
 * verification reason mandated by the agent prompt and citing AAP
 * &sect;0.6.1 (Decimal Arithmetic Fidelity), AAP &sect;0.6.11 (PR
 * gate), and AAP &sect;0.7.2 (PAN masking). The harness skeleton is
 * unconditionally present so JUnit discovers and reports this
 * per-program test in CI from day one. The {@code @Disabled}
 * annotation will be removed in the same PR that commits
 * non-placeholder content under
 * {@code src/test/resources/golden/cobil00c/expected/}.</p>
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.billpay.CoBil00C
 * @since 25
 */
@DisplayName("COBIL00C \u2014 Bill Payment Golden-Record Parity (money-handling)")
public class CoBil00CGoldenTest extends GoldenRecordTest {

    /**
     * Identifier of the per-program fixture directory under
     * {@code src/test/resources/golden/}. Lowercase form of the COBOL
     * {@code PROGRAM-ID COBIL00C} per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention shared with all 28 sibling golden tests.
     */
    private static final String PROGRAM_DIR = "cobil00c";

    /**
     * Name of the synthesized scenario file under
     * {@code src/test/resources/golden/cobil00c/expected/}. Encodes the
     * five-submission sequence (valid payment, insufficient funds /
     * zero balance, invalid account NOTFND, PF3 back, PF4 clear)
     * consumed by the harness orchestrator to drive the CoBil00C
     * online-CICS state machine. Lives alongside the expected outputs
     * under the per-program {@code cobil00c/} subtree because it is a
     * harness-internal fixture (not part of the immutable
     * {@code app/data/ASCII/} dataset).
     */
    private static final String INPUT_SCENARIO_TXT = "input_scenario.txt";

    /**
     * Name of the captured COBOL {@code TRANSACT} KSDS snapshot under
     * {@code src/test/resources/golden/cobil00c/expected/}. On the
     * valid-payment scenario the file is extended with one 350-byte
     * {@code TRAN-RECORD} (350 bytes per
     * {@code app/cpy/CVTRA05Y.cpy:&sect;TRAN-RECORD}) carrying
     * {@code TRAN-AMT = ACCT-CURR-BAL} at {@code PIC S9(09)V99}; on
     * the other four scenarios the file remains byte-identical to its
     * pre-test (empty) state. This is the
     * <strong>primary Decimals utility canary</strong>: a defect in
     * sign-nybble handling, scale preservation, or COBOL packed-
     * decimal encoding manifests as a byte mismatch here per AAP
     * &sect;0.6.1.
     */
    private static final String TRANSACT_TXT = "transact.txt";

    /**
     * Name of the captured COBOL {@code ACCTDAT} KSDS snapshot under
     * {@code src/test/resources/golden/cobil00c/expected/}. On the
     * valid-payment scenario exactly ONE account's
     * {@code ACCT-CURR-BAL} is REWRITTEN to {@code +0000000000.00}
     * (the result of {@code curBal - curBal} via
     * {@link com.blitzy.carddemo.domain.util.Decimals#subtract} at
     * scale 2); on the other four scenarios the file remains
     * byte-identical to its pre-test state. This is the
     * <strong>secondary Decimals utility canary</strong>: the
     * trailing zero of the {@code .00} fractional part must NOT
     * collapse to {@code .0} or the {@code PIC S9(10)V99} encoder
     * emits the wrong number of bytes per AAP &sect;0.6.1
     * "a BigDecimal value of 1.20 must NOT be normalized to 1.2".
     * Note: this is a DIFFERENT file from
     * {@code app/data/ASCII/acctdata.txt} (which is the immutable
     * source fixture); the harness produces a tempfile copy at
     * runtime, applies the REWRITE, and compares the post-state
     * tempfile against this captured expected output.
     */
    private static final String ACCTDATA_TXT = "acctdata.txt";

    /**
     * Name of the captured COBOL stdout trace under
     * {@code src/test/resources/golden/cobil00c/expected/}. Records
     * the PAN-masked SLF4J/DISPLAY emissions for the five-scenario
     * sequence. Per AAP &sect;0.7.2 every PAN reference in this file
     * shows the last 4 digits only (12 leading mask characters such
     * as {@code "************1234"}). Captures the verbatim error
     * messages from the COBOL source per the
     * {@code expected/README.md} verbatim message catalog
     * (15&nbsp;distinct strings), the DOUBLE-space GREEN success
     * line {@code "Payment successful.  Your Transaction ID is N."},
     * and the {@code DISPLAY 'RESP:' ... 'REAS:' ...} traces on the
     * {@code WHEN OTHER} branches of the four
     * {@code EVALUATE WS-RESP-CD} dispatchers.
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * Name of the captured BMS output trace under
     * {@code src/test/resources/golden/cobil00c/expected/}. Records
     * the serialized {@code CoBil00Output} screen states (one per
     * submission, five total) with FULL PAN. Per AAP &sect;0.7.2
     * these records display FULL PAN because the BMS screen is an
     * authorized rendering surface distinct from the SLF4J logging
     * surface. Captures the 1920-byte 24&times;80 BMS layout per
     * {@code app/bms/COBIL00.bms} (mapset {@code COBIL00}, map
     * {@code COBIL0A}, fields {@code TRNNAME / TITLE01 / CURDATE /
     * PGMNAME / TITLE02 / CURTIME / ACTIDIN / CURBAL / CONFIRM /
     * ERRMSG} plus 8 static literals).
     */
    private static final String BMS_OUTPUT_TXT = "bms_output.txt";

    /**
     * Name of the account master fixture under
     * {@code app/data/ASCII/}. Backs the
     * {@link com.blitzy.carddemo.domain.port.AccountRepository} used
     * by paragraph {@code READ-ACCTDAT-FILE} at
     * {@code app/cbl/COBIL00C.cbl:L343-L372} (READ with UPDATE
     * intent) and paragraph {@code UPDATE-ACCTDAT-FILE} at
     * {@code app/cbl/COBIL00C.cbl:L377-L403} (REWRITE). Read directly
     * via {@link GoldenRecordTest#resolveAppDataPath(String)} &mdash;
     * NOT copied into this module per AAP &sect;0.4.1 and
     * &sect;0.6.11.
     */
    private static final String ACCTDATA_FIXTURE_TXT = "acctdata.txt";

    /**
     * Name of the customer master fixture under
     * {@code app/data/ASCII/}. Supplies supplemental customer context
     * to the harness's composition root so a complete adapter graph
     * can be constructed; the COBOL {@code COBIL00C} program itself
     * does NOT issue a {@code READ CUSTDAT} (the bill-payment
     * use-case operates on ACCT-ID and the derived XREF-CARD-NUM
     * only), so this fixture is byte-identical to its pre-test state
     * after every scenario and that no-touch invariant is verified
     * implicitly by the absence of any {@code custdata.txt} entry in
     * {@link #expectedOutputs()}.
     */
    private static final String CUSTDATA_FIXTURE_TXT = "custdata.txt";

    /**
     * Name of the card cross-reference fixture under
     * {@code app/data/ASCII/}. Backs the
     * {@link com.blitzy.carddemo.domain.port.CardXrefRepository} used
     * by paragraph {@code READ-CXACAIX-FILE} at
     * {@code app/cbl/COBIL00C.cbl:L408-L436} (random read via the
     * {@code CXACAIX} alternate index keyed by
     * {@code XREF-ACCT-ID PIC 9(11)}). Returns the
     * {@code XREF-CARD-NUM} that is subsequently moved into
     * {@code TRAN-CARD-NUM} at
     * {@code app/cbl/COBIL00C.cbl:L225} on the WRITE path. The
     * source of the PAN that AAP &sect;0.7.2 mandates be masked in
     * {@link #STDOUT_TXT} but preserved in full in
     * {@link #BMS_OUTPUT_TXT}.
     */
    private static final String CARDXREF_FIXTURE_TXT = "cardxref.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.billpay.CoBil00C}{@code .class}.
     * Referenced via fully-qualified class literal so this file's
     * import block stays minimal and restricted to
     * {@link java.nio.file.Path}, {@link java.util.List}, and the
     * JUnit Jupiter API annotations ({@link DisplayName},
     * {@link Disabled}, {@link Test}). The fully-qualified class
     * literal compiles cleanly because {@code carddemo-tests}
     * declares a test-scope dependency on {@code carddemo-application}
     * in {@code java/carddemo-tests/pom.xml} (per AAP &sect;0.5.1).</p>
     *
     * <p>The base harness uses the returned {@link Class} reference to
     * instantiate {@link com.blitzy.carddemo.application.billpay.CoBil00C}
     * via its 5-argument primary constructor
     * {@code CoBil00C(AccountRepository, CardXrefRepository,
     * TransactionRepository, ProgramRegistry, Clock)} with file-backed
     * adapter implementations resolved from
     * {@link #auxiliaryInputs()} and a deterministic
     * {@link java.time.Clock} that produces the exact
     * {@code TRAN-ORIG-TS / TRAN-PROC-TS} timestamp captured in the
     * COBOL baseline (per AAP &sect;0.6.6 mandating
     * {@code ScopedValue<Clock>} for test reproducibility).</p>
     */
    @Override
    protected Class<?> programClass() {
        return com.blitzy.carddemo.application.billpay.CoBil00C.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to
     * {@code src/test/resources/golden/cobil00c/expected/input_scenario.txt}
     * via
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}.
     * This synthesized scenario file encodes the five-submission
     * sequence (valid payment, insufficient funds / zero balance,
     * invalid account NOTFND, PF3 back, PF4 clear) for the
     * online-CICS pseudo-conversational test; it lives alongside the
     * expected outputs under the per-program {@code cobil00c/}
     * subtree because it is a harness-internal fixture (not part of
     * the immutable {@code app/data/ASCII/} dataset).</p>
     */
    @Override
    protected Path inputFile() {
        return resolveExpectedOutputPath(PROGRAM_DIR, INPUT_SCENARIO_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to the captured COBOL
     * {@code TRANSACT} snapshot at
     * {@code src/test/resources/golden/cobil00c/expected/transact.txt},
     * resolved via
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}.
     * This is retained for harness backward compatibility (the
     * single-output convention required by
     * {@link GoldenRecordTest#expectedOutputFile()}); the actual
     * byte-for-byte parity assertions iterate the multi-element list
     * returned by {@link #expectedOutputs()} rather than this single
     * path. The {@link #TRANSACT_TXT} output is chosen for this
     * single-output slot because it is the primary
     * {@link com.blitzy.carddemo.domain.util.Decimals} utility canary
     * &mdash; the file most sensitive to
     * {@link java.math.BigDecimal} scale / rounding regressions per
     * AAP &sect;0.6.1.</p>
     */
    @Override
    protected Path expectedOutputFile() {
        return resolveExpectedOutputPath(PROGRAM_DIR, TRANSACT_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns an immutable 3-element {@link List} of auxiliary
     * input fixture paths reflecting the COBIL00C multi-file lookup:</p>
     * <ol>
     *   <li>{@code app/data/ASCII/acctdata.txt} &mdash; account
     *       master (300-byte fixed-width records per
     *       {@code app/cpy/CVACT01Y.cpy}). Random read with UPDATE
     *       intent by primary key {@code ACCT-ID PIC 9(11)} in
     *       paragraph {@code READ-ACCTDAT-FILE} at
     *       {@code app/cbl/COBIL00C.cbl:L343-L372}; subsequent
     *       REWRITE in paragraph {@code UPDATE-ACCTDAT-FILE} at
     *       {@code app/cbl/COBIL00C.cbl:L377-L403} on the
     *       confirm-Y path.</li>
     *   <li>{@code app/data/ASCII/custdata.txt} &mdash; customer
     *       master (500-byte fixed-width records per
     *       {@code app/cpy/CVCUS01Y.cpy}). Supplies supplemental
     *       customer context to the harness's composition root so a
     *       complete adapter graph can be constructed; the COBOL
     *       {@code COBIL00C} program itself does NOT issue a
     *       {@code READ CUSTDAT}, so this fixture is byte-identical
     *       to its pre-test state after every scenario.</li>
     *   <li>{@code app/data/ASCII/cardxref.txt} &mdash; card
     *       cross-reference (50-byte fixed-width records per
     *       {@code app/cpy/CVACT03Y.cpy}). Random read via the
     *       {@code CXACAIX} alternate index keyed by
     *       {@code XREF-ACCT-ID PIC 9(11)} in paragraph
     *       {@code READ-CXACAIX-FILE} at
     *       {@code app/cbl/COBIL00C.cbl:L408-L436}; returns the
     *       {@code XREF-CARD-NUM} that becomes
     *       {@code TRAN-CARD-NUM} on the WRITE path at
     *       {@code app/cbl/COBIL00C.cbl:L225}.</li>
     * </ol>
     *
     * <p>The returned list is
     * {@link List#of(Object, Object, Object)} immutable to preserve
     * deterministic ordering. Ordering matters here because the
     * harness's
     * {@link GoldenRecordTest#runProgram(Class, java.nio.file.Path,
     * java.util.List) runProgram(Class, Path, List)} orchestration
     * hook wires the supplied fixtures to the file-based repository
     * adapters in declaration order; reordering would change which
     * adapter binds which fixture and break the multi-file
     * lookup.</p>
     */
    @Override
    protected List<Path> auxiliaryInputs() {
        return List.of(
            resolveAppDataPath(ACCTDATA_FIXTURE_TXT),
            resolveAppDataPath(CUSTDATA_FIXTURE_TXT),
            resolveAppDataPath(CARDXREF_FIXTURE_TXT)
        );
    }

    /**
     * {@inheritDoc}
     *
     * <p>Declares the four byte-for-byte parity targets for
     * COBIL00C. Overriding this method (rather than relying on the
     * base class's single-output default) is the AAP &sect;0.6.11
     * idiom for multi-output scenarios; the base
     * {@link GoldenRecordTest#byteForByteParity()} iterates this
     * list and asserts byte parity for each entry independently,
     * identifying any mismatched output by name in the AssertJ
     * failure message.</p>
     * <ol>
     *   <li>{@link #TRANSACT_TXT} ({@code transact.txt}) &mdash;
     *       primary Decimals utility canary per AAP &sect;0.6.1;
     *       carries the WRITTEN {@code TRAN-RECORD} with
     *       {@code TRAN-AMT} encoded at {@code PIC S9(09)V99}.</li>
     *   <li>{@link #ACCTDATA_TXT} ({@code acctdata.txt}) &mdash;
     *       secondary Decimals utility canary per AAP &sect;0.6.1;
     *       carries the REWRITTEN {@code ACCOUNT-RECORD} with
     *       {@code ACCT-CURR-BAL} encoded at {@code PIC S9(10)V99}
     *       (trailing zero of {@code .00} fractional part MUST be
     *       preserved).</li>
     *   <li>{@link #STDOUT_TXT} ({@code stdout.txt}) &mdash; the
     *       PAN-masked SLF4J/DISPLAY trace per AAP &sect;0.7.2.
     *       Every PAN reference is masked to its last 4 digits
     *       before emission. Captures the verbatim error messages,
     *       the DOUBLE-space success line, and the
     *       {@code DISPLAY 'RESP:' ... 'REAS:' ...} traces.</li>
     *   <li>{@link #BMS_OUTPUT_TXT} ({@code bms_output.txt}) &mdash;
     *       the serialized {@code CoBil00Output} screen states
     *       (one per submission, five total) with FULL PAN. The BMS
     *       screen is an authorized rendering surface distinct from
     *       the logging surface; PAN masking does NOT apply to this
     *       output per AAP &sect;0.7.2.</li>
     * </ol>
     *
     * <p>Returned list is
     * {@link List#of(Object, Object, Object, Object)} immutable.</p>
     */
    @Override
    protected List<ExpectedOutput> expectedOutputs() {
        return List.of(
            new ExpectedOutput(TRANSACT_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, TRANSACT_TXT)),
            new ExpectedOutput(ACCTDATA_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, ACCTDATA_TXT)),
            new ExpectedOutput(STDOUT_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, STDOUT_TXT)),
            new ExpectedOutput(BMS_OUTPUT_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, BMS_OUTPUT_TXT))
        );
    }

    /**
     * Byte-for-byte parity assertion, currently {@code @Disabled}
     * pending the COBOL COBIL00C baseline capture per AAP
     * &sect;0.6.11 ("Initial test scaffolding may use placeholder
     * expected files marked {@code @Disabled} until COBOL captures
     * are available").
     *
     * <p>The {@code @Disabled} annotation will be removed in the
     * same PR that commits non-placeholder content under
     * {@code src/test/resources/golden/cobil00c/expected/} for all
     * 4 declared outputs per the capture procedure documented in
     * {@code java/MIGRATION_NOTES.md}. The method body delegates to
     * {@link GoldenRecordTest#byteForByteParity()} so the actual
     * byte-by-byte assertion logic remains centralised in the base
     * class (no duplication of the read/compare loop across the
     * 28 per-program subclasses).</p>
     *
     * <p><strong>Why the {@code @Test} annotation is re-declared on
     * this override</strong>: empirically verified against JUnit
     * Jupiter 5.13.1 (pinned in {@code java/pom.xml}
     * {@code dependencyManagement} per AAP &sect;0.5.1), the JUnit
     * Platform's annotation lookup does NOT inherit {@code @Test}
     * when a subclass overrides a parent's {@code @Test}-annotated
     * method &mdash; running surefire with
     * {@code -Dtest=CoBil00CGoldenTest} produces "Tests run: 0" when
     * {@code @Test} is omitted from the override but "Tests run: 1,
     * Skipped: 1" when re-declared. Without {@code @Test} here,
     * this test class would be silently dropped from the test
     * suite, defeating the AAP &sect;0.6.11 PR-gate purpose of the
     * harness skeleton. This pattern matches sibling
     * {@link CoActVwCGoldenTest}, {@link CoActUpCGoldenTest},
     * {@link CoCrdSlCGoldenTest}, {@link CoCrdUpCGoldenTest},
     * {@link CoTrn00CGoldenTest}, {@link CoTrn01CGoldenTest}, and
     * {@link CoTrn02CGoldenTest}.</p>
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
        "Awaiting COBOL COBIL00C baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md for the regeneration procedure. "
            + "Verify (all 5 must hold before removing @Disabled): "
            + "(1) BigDecimal monetary arithmetic truncation matches "
            + "COBOL (MathContext.DECIMAL128 + RoundingMode.HALF_EVEN at "
            + "the COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT site "
            + "at app/cbl/COBIL00C.cbl:L234 per AAP \u00a70.6.1; "
            + "trailing zero of .00 fractional part MUST be preserved -- "
            + "a BigDecimal value of 1.20 MUST NOT be normalized to "
            + "1.2 in either the PIC S9(09)V99 TRAN-AMT encoding in "
            + "transact.txt or the PIC S9(10)V99 ACCT-CURR-BAL encoding "
            + "in acctdata.txt); "
            + "(2) Account balance updates correctly post-payment: on "
            + "the valid-payment scenario the REWRITTEN ACCOUNT-RECORD "
            + "encodes ACCT-CURR-BAL = +0000000000.00 (the COBOL packed-"
            + "decimal sign nybble preserved) reflecting the full-"
            + "balance bill-payment semantics at "
            + "app/cbl/COBIL00C.cbl:L224 (MOVE ACCT-CURR-BAL TO "
            + "TRAN-AMT) and L234 (COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL "
            + "- TRAN-AMT); "
            + "(3) Transaction record written with exact DB2 timestamp "
            + "format: WS-TIMESTAMP is a 26-byte string "
            + "YYYY-MM-DD-HH.MM.SS.000000 produced by GET-CURRENT-"
            + "TIMESTAMP at app/cbl/COBIL00C.cbl:L249-L267; the Java "
            + "translation routes through a deterministic Clock via "
            + "ScopedValue<Clock> per AAP \u00a70.6.6 so the captured "
            + "transact.txt and bms_output.txt artifacts are byte-"
            + "comparable across runs; LocalDateTime via java.time per "
            + "AAP \u00a70.6.4 (NEVER java.util.Date / Calendar / "
            + "SimpleDateFormat); "
            + "(4) PAN masked to last 4 digits in logs (AAP \u00a70.7.2): "
            + "stdout.txt shows 12 leading mask characters such as "
            + "************1234 for every PAN reference but "
            + "bms_output.txt preserves the FULL PAN because the BMS "
            + "screen is an authorized rendering surface distinct from "
            + "the SLF4J logging surface (storage and logging are "
            + "different surfaces per AAP \u00a70.1.1); "
            + "(5) Insufficient-funds path rejects without write: on "
            + "the zero-balance scenario the COBOL guard at "
            + "app/cbl/COBIL00C.cbl:L198 (IF ACCT-CURR-BAL <= ZEROS) "
            + "emits the verbatim 'You have nothing to pay...' RED "
            + "message and BOTH transact.txt AND acctdata.txt remain "
            + "byte-identical to their pre-test (empty / source) state; "
            + "any spurious WRITE TRANSACT or REWRITE ACCTDAT on this "
            + "path produces a byte mismatch in the corresponding "
            + "expected output. "
            + "Additional verbatim-message invariants per the "
            + "expected/README.md catalog (15 distinct strings): "
            + "DOUBLE space between 'successful.' and 'Your' in the "
            + "GREEN success message at app/cbl/COBIL00C.cbl:L527-L531; "
            + "trailing 9 spaces in the 50-char CCDA-MSG-INVALID-KEY "
            + "per app/cpy/CSMSG01Y.cpy:L20-L21; trailing ellipses "
            + "preserved on all 14 RED messages; mixed-case 'can NOT' "
            + "preserved per COBOL source; sort orders preserved "
            + "(ACCT-ID asc; TRAN-ID asc) per VSAM KSDS semantics; "
            + "empty-file fallback first TRAN-ID = "
            + "0000000000000001 per app/cbl/COBIL00C.cbl:L487-L488."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
