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
 * Byte-for-byte golden-record parity test for {@code COACTVWC}
 * (Account View Online Transaction).
 *
 * <p><strong>Source COBOL:</strong> {@code app/cbl/COACTVWC.cbl} &mdash; the
 * {@code PROGRAM-ID COACTVWC} online-CICS program backing transaction
 * {@code CAVW} ({@code LIT-THISTRANID VALUE 'CAVW'} at
 * {@code app/cbl/COACTVWC.cbl:L145-L146}). The program is
 * <strong>strictly read-only</strong>: it performs three sequential lookups
 * to assemble a complete account-view BMS map (CACTVWA) for the terminal
 * user and never modifies any record. It returns to its caller via
 * {@code EXEC CICS XCTL} to one of three destinations depending on the AID
 * key pressed and on the navigation context carried in the
 * {@code DFHCOMMAREA}.</p>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.account.CoActVwC}. Per AAP
 * &sect;0.4.1 (program-by-program mapping) COACTVWC is translated into the
 * {@code application/account/} subpackage co-located with its sibling
 * translations {@code CoActUpC} (account update with SYNCPOINT ROLLBACK),
 * {@code CbAct01C} / {@code CbAct02C} / {@code CbAct03C} (sequential file
 * readers), and {@code CbAct04C} (interest calculation engine). The Java
 * translation preserves a 4-argument constructor
 * {@code CoActVwC(AccountRepository accountRepository,
 * CustomerRepository customerRepository,
 * CardXrefRepository cardXrefRepository,
 * ProgramRegistry programRegistry)} matching the COBOL collaborator surface
 * (one repository port per VSAM file plus the dynamic-CALL routing facility
 * carried as a collaborator for XCTL dispatch).</p>
 *
 * <h2>3-Step Lookup: CXACAIX &rarr; ACCTDAT &rarr; CUSTDAT</h2>
 *
 * <p>The COBOL paragraph {@code 9000-READ-ACCT} at
 * {@code app/cbl/COACTVWC.cbl:L697-L723} orchestrates a chained read across
 * THREE VSAM datasets (per the
 * {@code LIT-ACCTFILENAME VALUE 'ACCTDAT '},
 * {@code LIT-CUSTFILENAME VALUE 'CUSTDAT '}, and
 * {@code LIT-CARDXREFNAME-ACCT-PATH VALUE 'CXACAIX '} literals at
 * {@code app/cbl/COACTVWC.cbl:L184-L193}):</p>
 * <ol>
 *   <li><strong>{@code CXACAIX}</strong> &mdash; alternate index over the
 *       CARDXREF cluster keyed by {@code WS-CARD-RID-ACCT-ID PIC 9(11)}.
 *       Paragraph {@code 9200-GETCARDXREF-BYACCT} reads the AIX to recover
 *       {@code XREF-CUST-ID} for the supplied account id. On
 *       {@code NOTFND} the program populates
 *       {@link com.blitzy.carddemo.application.account.CoActVwOutput} with
 *       the verbatim message {@code "Did not find this account in account
 *       card xref file"} (preserved per AAP &sect;0.7.1) and returns a
 *       {@link com.blitzy.carddemo.application.account.CoActVwC.Outcome.SendMap}
 *       to re-render the screen.</li>
 *   <li><strong>{@code ACCTDAT}</strong> &mdash; primary VSAM KSDS keyed
 *       by {@code FD-ACCT-ID PIC 9(11)}. Paragraph
 *       {@code 9300-GETACCTDATA-BYACCT} reads the master record to assemble
 *       the balances, credit limits, dates, and the foreign-key reference
 *       {@code ACCT-CUST-ID} that drives step 3. On {@code NOTFND} the
 *       program populates the output with {@code "Did not find this
 *       account in account master file"} (preserved verbatim).</li>
 *   <li><strong>{@code CUSTDAT}</strong> &mdash; CUSTOMER VSAM KSDS keyed
 *       by {@code FD-CUST-ID PIC 9(09)}. Paragraph
 *       {@code 9400-GETCUSTDATA-BYCUST} reads the customer master to
 *       assemble the customer demographic block (name, SSN, address,
 *       phone, FICO, etc.). On {@code NOTFND} the program populates the
 *       output with {@code "Did not find associated customer in master
 *       file"} (preserved verbatim).</li>
 * </ol>
 *
 * <h2>XCTL Targets</h2>
 *
 * <p>The {@link com.blitzy.carddemo.application.account.CoActVwC.Outcome.Xctl}
 * outcome carries one of three target program-ids translating the three
 * {@code EXEC CICS XCTL} paths in {@code COACTVWC.cbl} (lines 326-360):</p>
 * <ul>
 *   <li>{@code COMEN01C} ({@code LIT-MENUPGM} at
 *       {@code app/cbl/COACTVWC.cbl:L167-L168}) &mdash; menu return on
 *       {@code PFK03} (Exit), corresponding to the verbatim
 *       {@code "PF03 pressed.Exiting              "} (no space after the
 *       period, 14 trailing spaces preserved per AAP &sect;0.7.1).</li>
 *   <li>{@code COCRDSLC} ({@code LIT-CARDDTLPGM} at
 *       {@code app/cbl/COACTVWC.cbl:L175-L176}) &mdash; card-detail drill
 *       through when a single card is associated with the viewed
 *       account.</li>
 *   <li>{@code COCRDLIC} ({@code LIT-CCLISTPGM} at
 *       {@code app/cbl/COACTVWC.cbl:L150-L151}) &mdash; card-list drill
 *       through when multiple cards are associated.</li>
 * </ul>
 *
 * <h2>Display Formatters Verified</h2>
 *
 * <p>This test enforces byte-for-byte parity on the COBOL display
 * conversions exercised by paragraph {@code 1200-SETUP-SCREEN-VARS}:</p>
 * <ul>
 *   <li><strong>SSN</strong>: {@code NNN-NN-NNNN} (11 characters total).
 *       Built by the COBOL {@code STRING} construct as
 *       {@code substring(0,3) + "-" + substring(3,5) + "-" + substring(5,9)}.
 *       The Java translation replicates this string assembly bit-for-bit so
 *       the captured {@code bms_output.txt} matches the COBOL screen
 *       exactly.</li>
 *   <li><strong>Phone</strong>: {@code (NNN)NNN-NNNN} (13 characters
 *       total: open-paren + area code + close-paren + 3-digit prefix +
 *       hyphen + 4-digit line number). NO space after the closing
 *       parenthesis is the COBOL convention; preserved verbatim.</li>
 *   <li><strong>Currency</strong>: {@code +###,###,##0.00;-###,###,##0.00}
 *       (15 characters, leading sign, comma thousands separator, exactly
 *       2 fractional digits). Used for {@code ACCT-CURR-BAL},
 *       {@code ACCT-CREDIT-LIMIT}, {@code ACCT-CASH-CREDIT-LIMIT},
 *       {@code ACCT-CURR-CYC-CREDIT}, and {@code ACCT-CURR-CYC-DEBIT}.
 *       The Java translation uses {@link java.text.DecimalFormat} pinned
 *       to {@link java.util.Locale#US} with explicit
 *       {@link java.math.RoundingMode#HALF_EVEN} so the decimal-point
 *       character and grouping separator do not vary with the JVM default
 *       locale (per AAP &sect;0.6.1).</li>
 * </ul>
 *
 * <h2>PAN Masking Verification (AAP &sect;0.7.2)</h2>
 *
 * <p>The captured fixtures verify <strong>two separate code paths</strong>
 * with different PAN visibility:</p>
 * <ul>
 *   <li>{@code stdout.txt} &mdash; SLF4J / DISPLAY trace. Per AAP
 *       &sect;0.7.2 ("no card PAN logged in full; mask all but last 4
 *       digits in logs and error messages"), every log line mentioning a
 *       card number must show only the last 4 digits (12 leading mask
 *       characters such as {@code "************1234"}). The Java
 *       translation routes PAN through a masking helper before SLF4J
 *       emission; the captured stdout fixture asserts the masked form
 *       byte-for-byte.</li>
 *   <li>{@code bms_output.txt} &mdash; serialized
 *       {@link com.blitzy.carddemo.application.account.CoActVwOutput}
 *       screen states (one per submission in the scenario). The BMS
 *       screen displays the <strong>FULL</strong> PAN because the user is
 *       authorized to view their own card data through the 3270 terminal;
 *       the masking rule applies to logs only, not to authorized screen
 *       display. Storage and logging are different surfaces (per AAP
 *       &sect;0.1.1 surfaced implicit requirement).</li>
 * </ul>
 *
 * <h2>Test Scenario</h2>
 *
 * <p>The {@code input_scenario.txt} fixture under
 * {@code src/test/resources/golden/coactvwc/expected/} encodes a sequence
 * of five terminal submissions exercising the full state space of the
 * read-only view:</p>
 * <ol>
 *   <li><strong>Blank entry</strong> (first-time invocation,
 *       {@code EIBCALEN = 0}) &mdash; should render the prompt
 *       {@code "Enter or update id of account to display"} and return
 *       {@link com.blitzy.carddemo.application.account.CoActVwC.Outcome.SendMap}
 *       with an empty input form.</li>
 *   <li><strong>Valid account-id</strong> (e.g. {@code "00000000010"}
 *       from {@code app/data/ASCII/acctdata.txt}) &mdash; triggers the
 *       3-step lookup CXACAIX &rarr; ACCTDAT &rarr; CUSTDAT, populates
 *       all 38 BMS fields (account block + customer block), and returns
 *       {@code Outcome.SendMap} with the info message
 *       {@code "Displaying details of given Account"}.</li>
 *   <li><strong>Invalid account-id</strong> (e.g. {@code "99999999999"}
 *       not present in {@code acctdata.txt}) &mdash; the CXACAIX lookup
 *       fails with {@code NOTFND}; the output carries the verbatim
 *       message {@code "Did not find this account in account card xref
 *       file"}.</li>
 *   <li><strong>PF3 back</strong> &mdash; the user presses {@code PFK03}
 *       (Exit); the program emits the verbatim
 *       {@code "PF03 pressed.Exiting              "} message and returns
 *       {@link com.blitzy.carddemo.application.account.CoActVwC.Outcome.Xctl}
 *       with {@code targetProgram = "COMEN01C"} per
 *       {@code app/cbl/COACTVWC.cbl:L336}.</li>
 *   <li><strong>PF4 clear / non-handled AID</strong> &mdash; per
 *       {@code COACTVWC.cbl:L375} the {@code EVALUATE TRUE} dispatch
 *       falls through to the {@code WHEN OTHER} branch and emits
 *       {@code "UNEXPECTED DATA SCENARIO"}.</li>
 * </ol>
 *
 * <h2>Auxiliary Input Fixtures</h2>
 *
 * <p>Three ASCII fixtures from {@code app/data/ASCII/} are wired through
 * {@link #auxiliaryInputs()} to back the 3-step lookup:</p>
 * <ol>
 *   <li>{@code acctdata.txt} (50 account records, 300 bytes each per
 *       {@code app/cpy/CVACT01Y.cpy:&sect;ACCOUNT-RECORD}) backs the
 *       {@link com.blitzy.carddemo.domain.port.AccountRepository}.</li>
 *   <li>{@code custdata.txt} (customer records per
 *       {@code app/cpy/CVCUS01Y.cpy:&sect;CUSTOMER-RECORD}) backs the
 *       {@link com.blitzy.carddemo.domain.port.CustomerRepository}.</li>
 *   <li>{@code cardxref.txt} (50-byte cross-reference records per
 *       {@code app/cpy/CVACT03Y.cpy:&sect;CARD-XREF-RECORD}) backs the
 *       {@link com.blitzy.carddemo.domain.port.CardXrefRepository}.</li>
 * </ol>
 *
 * <p>Per AAP &sect;0.4.1 and &sect;0.6.11 the fixtures are read DIRECTLY
 * from {@code app/} via the
 * {@link GoldenRecordTest#resolveAppDataPath(String)} helper &mdash; NOT
 * copied into {@code java/carddemo-tests/} (the COBOL source tree remains
 * the single source of truth for fixture data). Because COACTVWC is
 * strictly read-only (no {@code REWRITE}, no {@code WRITE}, no
 * {@code SYNCPOINT}), the post-test state of these three fixtures MUST be
 * byte-identical to their pre-test state; any divergence indicates a
 * regression in the Java translation that the harness's
 * {@link GoldenRecordTest#byteForByteParity()} method will detect.</p>
 *
 * <h2>Expected Outputs (multi-output scenario)</h2>
 *
 * <p>Per AAP &sect;0.6.11 multi-output pattern (overriding
 * {@link #expectedOutputs()} rather than relying on the single-output
 * default), this test declares TWO byte-for-byte parity targets:</p>
 * <ol>
 *   <li>{@code stdout.txt} &mdash; the PAN-masked SLF4J/DISPLAY trace.
 *       Verifies the AAP &sect;0.7.2 logging policy.</li>
 *   <li>{@code bms_output.txt} &mdash; the serialized
 *       {@link com.blitzy.carddemo.application.account.CoActVwOutput}
 *       screen states with FULL PAN. One serialized state per submission
 *       in the scenario (five total), concatenated in submission order.
 *       Sequence ordering is preserved exactly because the user-facing
 *       view depends on the strict submit-then-render protocol of the
 *       CICS pseudo-conversational pattern.</li>
 * </ol>
 *
 * <p>This test is the <strong>NON-NEGOTIABLE PR gate</strong> per AAP
 * &sect;0.6.11. Any deviation in the 3-step lookup ordering, the verbatim
 * COBOL messages, the SSN / phone / currency display masks, the
 * PAN-masking behavior in logs, the XCTL target selection, or the screen
 * sequencing breaks parity and blocks the PR.</p>
 *
 * <p><strong>Scaffolding state</strong>: per AAP &sect;0.6.11 ("Initial
 * test scaffolding may use placeholder expected files marked
 * {@code @Disabled} until COBOL captures are available; the harness
 * skeleton, base class, and per-program test classes are created
 * unconditionally"), the {@link #byteForByteParity()} override below is
 * annotated {@code @Disabled} with a 6-point verification reason citing
 * the COBOL capture procedure documented in
 * {@code java/MIGRATION_NOTES.md}. The harness skeleton is
 * unconditionally present so JUnit discovers and reports this per-program
 * test in CI from day one. The {@code @Disabled} annotation will be
 * removed in the same PR that commits non-placeholder content under
 * {@code src/test/resources/golden/coactvwc/expected/}.</p>
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.account.CoActVwC
 * @since 25
 */
@DisplayName("COACTVWC \u2014 Account View Golden-Record Parity")
public class CoActVwCGoldenTest extends GoldenRecordTest {

    /**
     * Identifier of the per-program fixture directory under
     * {@code src/test/resources/golden/}. Lowercase form of the COBOL
     * {@code PROGRAM-ID COACTVWC} per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention shared with all 28 sibling golden tests.
     */
    private static final String PROGRAM_DIR = "coactvwc";

    /**
     * Name of the synthesized scenario file under
     * {@code src/test/resources/golden/coactvwc/expected/}. Encodes the
     * five-submission sequence (blank entry, valid account-id, invalid
     * account-id, PF3 back, PF4 clear / non-handled AID) consumed by the
     * harness orchestrator to drive the CoActVwC online-CICS state
     * machine.
     */
    private static final String INPUT_SCENARIO_TXT = "input_scenario.txt";

    /**
     * Name of the captured COBOL stdout trace under
     * {@code src/test/resources/golden/coactvwc/expected/}. Records the
     * PAN-masked SLF4J/DISPLAY emissions for the scenario. Per AAP
     * &sect;0.7.2 every PAN reference in this file shows the last 4
     * digits only.
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * Name of the captured BMS output trace under
     * {@code src/test/resources/golden/coactvwc/expected/}. Records the
     * serialized {@code CoActVwOutput} screen states (one per
     * submission, five total). Per AAP &sect;0.7.2 these records display
     * FULL PAN because the BMS screen is an authorized rendering surface
     * distinct from the SLF4J logging surface.
     */
    private static final String BMS_OUTPUT_TXT = "bms_output.txt";

    /**
     * Name of the account master fixture under {@code app/data/ASCII/}.
     * Backs the {@link com.blitzy.carddemo.domain.port.AccountRepository}
     * used by paragraph {@code 9300-GETACCTDATA-BYACCT} (step 2 of the
     * 3-step lookup). Read directly via
     * {@link GoldenRecordTest#resolveAppDataPath(String)} &mdash; NOT
     * copied into this module per AAP &sect;0.4.1 and &sect;0.6.11.
     */
    private static final String ACCTDATA_TXT = "acctdata.txt";

    /**
     * Name of the customer master fixture under {@code app/data/ASCII/}.
     * Backs the {@link com.blitzy.carddemo.domain.port.CustomerRepository}
     * used by paragraph {@code 9400-GETCUSTDATA-BYCUST} (step 3 of the
     * 3-step lookup).
     */
    private static final String CUSTDATA_TXT = "custdata.txt";

    /**
     * Name of the card cross-reference fixture under
     * {@code app/data/ASCII/}. Backs the
     * {@link com.blitzy.carddemo.domain.port.CardXrefRepository} used by
     * paragraph {@code 9200-GETCARDXREF-BYACCT} (step 1 of the 3-step
     * lookup, via the {@code CXACAIX} alternate index).
     */
    private static final String CARDXREF_TXT = "cardxref.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.account.CoActVwC}{@code .class}.
     * Referenced via fully-qualified class literal so this file's import
     * block stays minimal and restricted to {@link java.nio.file.Path},
     * {@link java.util.List}, and the JUnit Jupiter API annotations
     * ({@link DisplayName}, {@link Disabled}, {@link Test}). The
     * fully-qualified class literal compiles cleanly because
     * {@code carddemo-tests} declares a test-scope dependency on
     * {@code carddemo-application} in {@code java/carddemo-tests/pom.xml}
     * (per AAP &sect;0.5.1).</p>
     */
    @Override
    protected Class<?> programClass() {
        return com.blitzy.carddemo.application.account.CoActVwC.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to
     * {@code src/test/resources/golden/coactvwc/expected/input_scenario.txt}
     * via {@link GoldenRecordTest#resolveExpectedOutputPath(String,
     * String)}. This synthesized scenario file encodes the five-submission
     * sequence (blank entry, valid account-id, invalid account-id, PF3
     * back, PF4 clear / non-handled AID) for the online-CICS
     * pseudo-conversational test; it lives alongside the expected outputs
     * under the per-program {@code coactvwc/} subtree because it is a
     * harness-internal fixture (not part of the immutable
     * {@code app/data/ASCII/} dataset).</p>
     */
    @Override
    protected Path inputFile() {
        return resolveExpectedOutputPath(PROGRAM_DIR, INPUT_SCENARIO_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to the captured COBOL stdout
     * trace at
     * {@code src/test/resources/golden/coactvwc/expected/stdout.txt},
     * resolved via {@link GoldenRecordTest#resolveExpectedOutputPath(
     * String, String)}. This is retained for harness backward
     * compatibility (single-output convention); the actual byte-for-byte
     * parity assertions iterate the multi-element list returned by
     * {@link #expectedOutputs()} rather than this single path.</p>
     */
    @Override
    protected Path expectedOutputFile() {
        return resolveExpectedOutputPath(PROGRAM_DIR, STDOUT_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns an immutable 3-element {@link List} of auxiliary input
     * fixture paths reflecting the COACTVWC 3-step lookup
     * CXACAIX &rarr; ACCTDAT &rarr; CUSTDAT:</p>
     * <ol>
     *   <li>{@code app/data/ASCII/acctdata.txt} &mdash; account master
     *       (300-byte fixed-width records per
     *       {@code app/cpy/CVACT01Y.cpy}). Random read by primary key
     *       {@code ACCT-ID PIC 9(11)} in paragraph
     *       {@code 9300-GETACCTDATA-BYACCT}.</li>
     *   <li>{@code app/data/ASCII/custdata.txt} &mdash; customer master
     *       (500-byte fixed-width records per
     *       {@code app/cpy/CVCUS01Y.cpy}). Random read by primary key
     *       {@code CUST-ID PIC 9(09)} in paragraph
     *       {@code 9400-GETCUSTDATA-BYCUST}. The CUST-ID is recovered
     *       from the account record's {@code ACCT-CUST-ID} foreign
     *       key.</li>
     *   <li>{@code app/data/ASCII/cardxref.txt} &mdash; card
     *       cross-reference (50-byte fixed-width records per
     *       {@code app/cpy/CVACT03Y.cpy}). Random read via the
     *       {@code CXACAIX} alternate index keyed by
     *       {@code XREF-ACCT-ID PIC 9(11)} in paragraph
     *       {@code 9200-GETCARDXREF-BYACCT}.</li>
     * </ol>
     *
     * <p>The returned list is {@link List#of(Object, Object, Object)}
     * immutable to preserve deterministic ordering. Ordering matters here
     * because the harness's
     * {@link GoldenRecordTest#runProgram(Class, java.nio.file.Path,
     * java.util.List) runProgram(Class, Path, List)} orchestration hook
     * wires the supplied fixtures to the file-based repository adapters
     * in declaration order; reordering would change which adapter binds
     * which fixture and break the 3-step lookup.</p>
     */
    @Override
    protected List<Path> auxiliaryInputs() {
        return List.of(
            resolveAppDataPath(ACCTDATA_TXT),
            resolveAppDataPath(CUSTDATA_TXT),
            resolveAppDataPath(CARDXREF_TXT)
        );
    }

    /**
     * {@inheritDoc}
     *
     * <p>Declares the two byte-for-byte parity targets for COACTVWC.
     * Overriding this method (rather than relying on the base class's
     * single-output default) is the AAP &sect;0.6.11 idiom for
     * multi-output scenarios; the base
     * {@link GoldenRecordTest#byteForByteParity()} iterates this list and
     * asserts byte parity for each entry independently, identifying any
     * mismatched output by name in the AssertJ failure message.</p>
     * <ol>
     *   <li>{@link #STDOUT_TXT} ({@code stdout.txt}) &mdash; the
     *       PAN-masked SLF4J/DISPLAY trace per AAP &sect;0.7.2. Every
     *       PAN reference is masked to its last 4 digits before
     *       emission.</li>
     *   <li>{@link #BMS_OUTPUT_TXT} ({@code bms_output.txt}) &mdash; the
     *       serialized
     *       {@link com.blitzy.carddemo.application.account.CoActVwOutput}
     *       screen states (one per submission, five total) with FULL
     *       PAN. The BMS screen is an authorized rendering surface
     *       distinct from the logging surface; PAN masking does NOT
     *       apply to this output per AAP &sect;0.7.2.</li>
     * </ol>
     *
     * <p>Returned list is {@link List#of(Object, Object)} immutable.</p>
     */
    @Override
    protected List<ExpectedOutput> expectedOutputs() {
        return List.of(
            new ExpectedOutput(STDOUT_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, STDOUT_TXT)),
            new ExpectedOutput(BMS_OUTPUT_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, BMS_OUTPUT_TXT))
        );
    }

    /**
     * Byte-for-byte parity assertion, currently {@code @Disabled} pending
     * the COBOL COACTVWC baseline capture per AAP &sect;0.6.11 ("Initial
     * test scaffolding may use placeholder expected files marked
     * {@code @Disabled} until COBOL captures are available").
     *
     * <p>The {@code @Disabled} annotation will be removed in the same PR
     * that commits non-placeholder content under
     * {@code src/test/resources/golden/coactvwc/expected/} per the
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
     * {@code -Dtest=CoActVwCGoldenTest} produces "Tests run: 0" when
     * {@code @Test} is omitted from the override but "Tests run: 1,
     * Skipped: 1" when re-declared. Without {@code @Test} here, this
     * test class would be silently dropped from the test suite,
     * defeating the AAP &sect;0.6.11 PR-gate purpose of the harness
     * skeleton. This pattern matches sibling
     * {@link CbAct01CGoldenTest}, {@link CbAct02CGoldenTest},
     * {@link CbAct03CGoldenTest}, {@link CbCus01CGoldenTest},
     * {@link CbStm03AGoldenTest}, {@link CbStm03BGoldenTest},
     * {@link CbTrn01CGoldenTest}, {@link CbTrn02CGoldenTest},
     * {@link CbTrn03CGoldenTest}, and
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
        "Awaiting COBOL COACTVWC baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md for the regeneration procedure. "
            + "Activation checklist (all 6 must hold before removing "
            + "@Disabled): "
            + "(1) 3-step lookup CXACAIX \u2192 ACCTDAT \u2192 CUSTDAT "
            + "verified end-to-end against the captured COBOL output "
            + "(paragraphs 9200-GETCARDXREF-BYACCT, 9300-GETACCTDATA-"
            + "BYACCT, 9400-GETCUSTDATA-BYCUST at "
            + "app/cbl/COACTVWC.cbl:L697-L723 and L774-L870); on each "
            + "NOTFND step the verbatim error message is preserved per "
            + "AAP \u00a70.7.1 (\"Did not find this account in account "
            + "card xref file\" / \"Did not find this account in "
            + "account master file\" / \"Did not find associated "
            + "customer in master file\"). "
            + "(2) SSN displayed in the NNN-NN-NNNN format (11 chars) "
            + "built by paragraph 1200-SETUP-SCREEN-VARS via the COBOL "
            + "STRING construct (substring(0,3)+'-'+substring(3,5)+'-'+"
            + "substring(5,9)); byte-equal in both stdout.txt and "
            + "bms_output.txt. "
            + "(3) Phone displayed in the (NNN)NNN-NNNN format (13 "
            + "chars, NO space after the closing parenthesis per the "
            + "COBOL convention); byte-equal in bms_output.txt. "
            + "(4) Currency fields (ACCT-CURR-BAL, ACCT-CREDIT-LIMIT, "
            + "ACCT-CASH-CREDIT-LIMIT, ACCT-CURR-CYC-CREDIT, "
            + "ACCT-CURR-CYC-DEBIT) displayed in the "
            + "+###,###,##0.00;-###,###,##0.00 format (15 chars, "
            + "leading sign, java.text.DecimalFormat pinned to "
            + "Locale.US with RoundingMode.HALF_EVEN per AAP "
            + "\u00a70.6.1); byte-equal in bms_output.txt. "
            + "(5) PAN masked to the last 4 digits in stdout.txt per "
            + "AAP \u00a70.7.2 (12 leading mask characters such as "
            + "************1234) BUT preserved in full in "
            + "bms_output.txt (the BMS screen is an authorized "
            + "rendering surface distinct from the logging surface). "
            + "(6) XCTL targets COMEN01C (PFK03 exit per "
            + "app/cbl/COACTVWC.cbl:L336), COCRDSLC (card-detail "
            + "drill, LIT-CARDDTLPGM at L175-L176), and COCRDLIC "
            + "(card-list drill, LIT-CCLISTPGM at L150-L151) verified "
            + "via the Outcome.Xctl targetProgram field; PFK03 "
            + "additionally emits the verbatim "
            + "\"PF03 pressed.Exiting              \" message (no "
            + "space after the period, 14 trailing spaces preserved "
            + "per AAP \u00a70.7.1). "
            + "Read-only invariant: COACTVWC never modifies any "
            + "record (no REWRITE, no WRITE, no SYNCPOINT), so the "
            + "three auxiliary fixtures (acctdata.txt, custdata.txt, "
            + "cardxref.txt) MUST be byte-identical to their pre-test "
            + "state after the run; any divergence indicates a "
            + "regression."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
