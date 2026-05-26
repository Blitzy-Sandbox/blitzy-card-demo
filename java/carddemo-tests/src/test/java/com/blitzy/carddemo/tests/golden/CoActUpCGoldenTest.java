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
 * Byte-for-byte golden-record parity test for {@code COACTUPC}
 * (Account Update Online Transaction with the SOLE {@code SYNCPOINT ROLLBACK}).
 *
 * <p><strong>Source COBOL:</strong> {@code app/cbl/COACTUPC.cbl} &mdash; the
 * {@code PROGRAM-ID COACTUPC} online-CICS program backing transaction
 * {@code CAUP} ({@code LIT-THISTRANID VALUE 'CAUP'} at
 * {@code app/cbl/COACTUPC.cbl} WS-LITERALS, lines 698-734). The program
 * is the <strong>UNIQUE COBOL program in the entire {@code app/cbl/} tree
 * that issues an {@code EXEC CICS SYNCPOINT ROLLBACK}</strong> &mdash; the
 * rollback fires at {@code app/cbl/COACTUPC.cbl:L4100} when the second
 * {@code REWRITE} (CUSTDAT) fails after the first {@code REWRITE}
 * (ACCTDAT) has already succeeded, restoring the pre-image of the account
 * record to maintain cross-record consistency. Per AAP &sect;0.4.1 this is
 * called out as an IMPLEMENTATION DECISION in
 * {@code java/MIGRATION_NOTES.md} because the target hexagonal
 * architecture has no transaction manager &mdash; the Java translation
 * models the semantic via a try/finally compensating-write pattern in
 * the {@code 9600WriteProcessing} method of
 * {@link com.blitzy.carddemo.application.account.CoActUpC}.</p>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.account.CoActUpC}. Per AAP
 * &sect;0.4.1 (program-by-program mapping) COACTUPC is translated into
 * the {@code application/account/} subpackage co-located with its sibling
 * translations {@code CoActVwC} (account view, the read-only sibling),
 * {@code CbAct01C} / {@code CbAct02C} / {@code CbAct03C} (sequential file
 * readers), and {@code CbAct04C} (interest calculation engine). The Java
 * translation has a 5-argument constructor
 * {@code CoActUpC(AccountRepository accountRepository,
 * CustomerRepository customerRepository,
 * CardXrefRepository cardXrefRepository, DateValidator dateValidator,
 * ProgramRegistry programRegistry)} matching the COBOL collaborator
 * surface (one repository port per VSAM file, the CSUTLDTC date-validator
 * subroutine, and the dynamic-CALL routing facility carried as a
 * collaborator for XCTL dispatch).</p>
 *
 * <h2>SYNCPOINT ROLLBACK Semantics &mdash; the Sole In-Tree Occurrence</h2>
 *
 * <p>The COBOL paragraph {@code 9600-WRITE-PROCESSING} at
 * {@code app/cbl/COACTUPC.cbl:L3888-L4105} performs a two-phase
 * cross-record update with the following sequence:</p>
 * <ol>
 *   <li><strong>READ ACCT (UPDATE clause)</strong> &mdash; acquires the
 *       VSAM record lock on ACCTDAT and snapshots the current ACCOUNT
 *       record into working storage as the "pre-image".</li>
 *   <li><strong>READ CUST (UPDATE clause)</strong> &mdash; acquires the
 *       VSAM record lock on CUSTDAT and snapshots the current CUSTOMER
 *       record into working storage as its own pre-image.</li>
 *   <li><strong>Optimistic concurrency check</strong> &mdash; compares
 *       the just-read records against the values cached when the user
 *       first fetched the screen via paragraph
 *       {@code 9700-CHECK-CHANGE-IN-REC}; if any tracked field changed
 *       between fetch and save, the user is rejected with
 *       {@code "Record changed by some one else. Please review"}.</li>
 *   <li><strong>REWRITE ACCT (tentative)</strong> &mdash; commits the
 *       new ACCOUNT image to ACCTDAT.</li>
 *   <li><strong>REWRITE CUST (tentative)</strong> &mdash; commits the
 *       new CUSTOMER image to CUSTDAT.</li>
 *   <li><strong>{@code SYNCPOINT ROLLBACK} on CUST failure</strong>
 *       &mdash; if step 5 fails (any non-NORMAL response code), the
 *       COBOL program issues
 *       {@code EXEC CICS SYNCPOINT ROLLBACK END-EXEC} at
 *       {@code app/cbl/COACTUPC.cbl:L4099-L4101} which causes CICS to
 *       back out the step-4 ACCT REWRITE, restoring the ACCOUNT
 *       record's pre-image on disk. The COBOL working-storage flag
 *       {@code LOCKED-BUT-UPDATE-FAILED} is set true to communicate the
 *       outcome back to the user with the verbatim message
 *       {@code "Unable to Update Account ..."}.</li>
 * </ol>
 *
 * <p><strong>Java translation strategy</strong>: per AAP &sect;0.4.1
 * (CoActUpC class agent_prompt), the {@code 9600WriteProcessing} method
 * in {@link com.blitzy.carddemo.application.account.CoActUpC} reads and
 * caches both pre-images BEFORE issuing any tentative write. On a CUST
 * REWRITE failure the catch block invokes a COMPENSATING WRITE that
 * re-writes the cached ACCOUNT pre-image back to ACCTDAT, achieving the
 * same observable outcome as the COBOL {@code SYNCPOINT ROLLBACK}
 * without a transaction manager. This test fixture verifies the
 * compensating-write path produces a byte-identical
 * {@code acctdata.txt} to the pre-call state (the rollback condition)
 * versus a byte-different {@code acctdata.txt} reflecting the new
 * tentative write (the commit condition).</p>
 *
 * <h2>Preserved COBOL Bugs (AAP &sect;0.7.1 &mdash; "translate
 * faithfully and flag")</h2>
 *
 * <p>Per AAP &sect;0.7.1 ("If a COBOL paragraph contains dead code or
 * obvious bugs, translate it faithfully and flag it in a
 * {@code MIGRATION_NOTES.md}; do not 'fix' it in this refactor"), this
 * test asserts byte-for-byte parity against COBOL output that reflects
 * THREE preserved COBOL defects:</p>
 * <ol>
 *   <li><strong>Typo {@code ACCT-EXPIRAION-DATE}</strong> (missing the
 *       'T' between 'A' and 'I' &mdash; should be
 *       {@code ACCT-EXPIRATION-DATE}). The misspelling appears at six
 *       locations in {@code app/cbl/COACTUPC.cbl}: line 428
 *       ({@code ACCT-UPDATE-EXPIRAION-DATE} in
 *       {@code CUSTOMER-UPDATE-DATA}), lines 690-692
 *       ({@code ACUP-OLD-EXPIRAION-DATE} and its REDEFINES), lines
 *       778-780 ({@code ACUP-NEW-EXPIRAION-DATE} and its REDEFINES),
 *       line 1491 ({@code MOVE ACUP-NEW-EXPIRAION-DATE TO
 *       WS-EDIT-DATE-CCYYMMDD}), line 1693 (optimistic concurrency
 *       comparison), and line 3836 (commented {@code MOVE
 *       ACCT-EXPIRAION-DATE TO ACUP-OLD-EXPIRAION-DATE}). The Java
 *       translation preserves the typo as
 *       {@link com.blitzy.carddemo.application.account.CoActUpC} field
 *       {@code acctExpiraionDate} (NOT {@code acctExpirationDate}) so
 *       byte-emitted DISPLAY traces and field-name diagnostic messages
 *       match the COBOL baseline exactly.</li>
 *   <li><strong>Duplicate 88-level {@code DID-NOT-FIND-ACCT-IN-CARDXREF}</strong>
 *       &mdash; appears at {@code app/cbl/COACTUPC.cbl:L497} and again
 *       at {@code app/cbl/COACTUPC.cbl:L513}, attached to two
 *       different parent {@code 88}-level groups (or with overlapping
 *       VALUE clauses). COBOL behaviour is "last-encountered wins" at
 *       compile time, so the Java translation uses the second
 *       definition's value to emit the corresponding diagnostic
 *       message. The test asserts the captured COBOL output's verbatim
 *       message wording matches the Java translation's emitted string
 *       byte-for-byte.</li>
 *   <li><strong>DOB comparison offset mismatch</strong> &mdash; a
 *       probable COBOL bug in the date-of-birth optimistic-concurrency
 *       comparison where the offset into the cached and current DOB
 *       fields differ by one byte (likely an off-by-one in the
 *       comparison span). Per AAP &sect;0.7.1 ("preserve verbatim; do
 *       not 'fix'") the Java translation reproduces the same
 *       (incorrect) offset mismatch so the optimistic-lock predicate
 *       returns identical true/false outcomes for every input. The
 *       expected outputs captured from the COBOL baseline include
 *       scenarios that exercise the buggy comparison path; the test
 *       passes only when the Java translation reproduces the same
 *       outcome.</li>
 * </ol>
 *
 * <h2>Validation Constants</h2>
 *
 * <p>The Java translation declares the validation constants as
 * {@code public static final int} at the top of
 * {@link com.blitzy.carddemo.application.account.CoActUpC} so the test
 * runs against the same value space the COBOL paragraph
 * {@code 1500-EDIT-CUSTOMER-FICO} enforces (see
 * {@code app/cbl/COACTUPC.cbl:L848} for the COBOL
 * {@code FICO-RANGE-IS-VALID VALUES 300 THRU 850} declaration):</p>
 * <ul>
 *   <li><strong>{@code FICO_MIN = 300}, {@code FICO_MAX = 850}</strong>
 *       &mdash; inclusive bounds; values outside this range are
 *       rejected with the verbatim COBOL message
 *       {@code "FICO Score must be between 300 and 850"} (or
 *       equivalent per the COBOL EDIT-FICO emission).</li>
 *   <li><strong>Invalid SSN Part 1 values</strong>: {@code 0},
 *       {@code 666}, and {@code 900-999} (a closed set of 102 values).
 *       Sourced from {@code app/cbl/COACTUPC.cbl:L121-L123}:
 *       {@code 88 INVALID-SSN-PART1 VALUES 0, 666, 900 THRU 999}.
 *       Inputs matching any of these are rejected by paragraph
 *       {@code 1500-EDIT-CUSTOMER-SSN} with the COBOL emission
 *       verbatim.</li>
 * </ul>
 *
 * <h2>PAN Masking in Logs (AAP &sect;0.7.2)</h2>
 *
 * <p>The captured fixtures verify two separate code paths with
 * different PAN visibility:</p>
 * <ul>
 *   <li>{@code stdout.txt} &mdash; SLF4J / DISPLAY trace. Per AAP
 *       &sect;0.7.2 ("no card PAN logged in full; mask all but last 4
 *       digits in logs and error messages"), every log line mentioning
 *       a card number must show only the last 4 digits (12 leading
 *       mask characters such as {@code "************1234"}). The Java
 *       translation routes PAN through a masking helper before SLF4J
 *       emission; the captured stdout fixture asserts the masked form
 *       byte-for-byte.</li>
 *   <li>{@code bms_output.txt} &mdash; serialized
 *       {@code CoActUpOutput} screen states (one per submission in the
 *       scenario). The BMS screen displays the <strong>FULL</strong>
 *       PAN because the user is authorized to view their own card data
 *       through the 3270 terminal; the masking rule applies to logs
 *       only, not to authorized screen display. Storage and logging
 *       are different surfaces (per AAP &sect;0.1.1 surfaced implicit
 *       requirement).</li>
 * </ul>
 *
 * <h2>Test Scenario &mdash; Multi-Submit Pseudo-Conversation</h2>
 *
 * <p>The {@code input_scenario.txt} fixture under
 * {@code src/test/resources/golden/coactupc/expected/} encodes a
 * sequence of terminal submissions exercising the full state space of
 * the update transaction (including BOTH the COMMIT path and the
 * SYNCPOINT ROLLBACK path):</p>
 * <ol>
 *   <li><strong>Initial fetch</strong> (first-time invocation,
 *       {@code EIBCALEN = 0}) &mdash; user supplies an account-id;
 *       paragraph {@code 1100-RECEIVE-MAP} populates the BMS map,
 *       paragraph {@code 9000-READ-ACCT} performs the 3-step read
 *       (CXACAIX &rarr; ACCTDAT &rarr; CUSTDAT), and the
 *       {@code DetailsNotFetched} &rarr; {@code ShowDetails} state
 *       transition occurs.</li>
 *   <li><strong>Edit with valid data + save (PFK05)</strong> &mdash;
 *       drives the {@code ShowDetails} &rarr; {@code ChangesOk}
 *       &rarr; {@code ChangesOkNotConfirmed} &rarr;
 *       {@code ChangesOkConfirmed} state path; exercises the
 *       <strong>COMMIT path</strong> through
 *       {@code 9600-WRITE-PROCESSING} where both REWRITE operations
 *       succeed. Expected outcome: {@code acctdata.txt} and
 *       {@code custdata.txt} reflect the new image; the SLF4J trace
 *       in {@code stdout.txt} reports successful write with PAN
 *       masked.</li>
 *   <li><strong>Edit with invalid FICO (e.g., 850) and invalid SSN
 *       (e.g., Part1 = 666)</strong> &mdash; drives the
 *       {@code ShowDetails} &rarr; {@code ChangesNotOk} state
 *       transition; paragraphs {@code 1500-EDIT-CUSTOMER-FICO} and
 *       {@code 1500-EDIT-CUSTOMER-SSN} reject the inputs with
 *       verbatim COBOL error messages; NO write occurs; the on-disk
 *       state remains unchanged.</li>
 *   <li><strong>Edit with valid data but CUST REWRITE forced failure
 *       (PFK05)</strong> &mdash; drives the <strong>ROLLBACK
 *       path</strong>: the ACCT REWRITE in {@code 9600} step 4
 *       succeeds tentatively, then the CUST REWRITE in step 5 fails
 *       (the test orchestrator injects a failure via a stub
 *       CustomerRepository that throws on {@code rewrite(...)}). The
 *       COBOL emits {@code EXEC CICS SYNCPOINT ROLLBACK END-EXEC} at
 *       line 4100; the Java translation invokes the compensating
 *       write that restores the ACCT pre-image. Expected outcome:
 *       {@code acctdata.txt} is byte-identical to its pre-call state
 *       (the ACCT pre-image was restored by the compensating write);
 *       {@code stdout.txt} contains the
 *       {@code "Unable to Update Account ..."} verbatim message;
 *       {@code custdata.txt} remains untouched.</li>
 *   <li><strong>PF3 back</strong> (PFK03) &mdash; user exits without
 *       saving; outcome carries XCTL to {@code COMEN01C} per
 *       {@code LIT-MENU-PGM} at
 *       {@code app/cbl/COACTUPC.cbl:L183}.</li>
 *   <li><strong>PF4 clear / non-handled AID</strong> &mdash; per the
 *       {@code EVALUATE TRUE} dispatch the program emits the verbatim
 *       {@code "UNEXPECTED DATA SCENARIO"} or
 *       {@code "Invalid key pressed"} branch.</li>
 *   <li><strong>PF5 save</strong> on a partially-edited form
 *       &mdash; alternate save key; behaviour parity with PFK05.</li>
 *   <li><strong>PF12 cancel</strong> &mdash; per the COBOL dispatch
 *       table; cancellation without write.</li>
 * </ol>
 *
 * <h2>Auxiliary Input Fixtures</h2>
 *
 * <p>Three ASCII fixtures from {@code app/data/ASCII/} are wired
 * through {@link #auxiliaryInputs()} to back the read+update operations
 * across the COACTUPC scenario:</p>
 * <ol>
 *   <li>{@code app/data/ASCII/acctdata.txt} &mdash; account master
 *       (300-byte fixed-width records per
 *       {@code app/cpy/CVACT01Y.cpy:&sect;ACCOUNT-RECORD}). Random
 *       read by primary key {@code ACCT-ID PIC 9(11)} via paragraph
 *       {@code 9300-GETACCTDATA-BYACCT}; updated via {@code REWRITE}
 *       in paragraph {@code 9600-WRITE-PROCESSING} on the commit
 *       path; restored to its pre-image via the Java
 *       compensating-write on the SYNCPOINT ROLLBACK path.</li>
 *   <li>{@code app/data/ASCII/custdata.txt} &mdash; customer master
 *       (500-byte fixed-width records per
 *       {@code app/cpy/CVCUS01Y.cpy:&sect;CUSTOMER-RECORD}). Random
 *       read by primary key {@code CUST-ID PIC 9(09)} via paragraph
 *       {@code 9400-GETCUSTDATA-BYCUST}; updated via {@code REWRITE}
 *       on the commit path; left untouched on the ROLLBACK path
 *       (the CUST REWRITE is the failing operation that triggers
 *       the rollback).</li>
 *   <li>{@code app/data/ASCII/cardxref.txt} &mdash; card
 *       cross-reference (50-byte fixed-width records per
 *       {@code app/cpy/CVACT03Y.cpy:&sect;CARD-XREF-RECORD}). Random
 *       read via the {@code CXACAIX} alternate index keyed by
 *       {@code XREF-ACCT-ID PIC 9(11)} in paragraph
 *       {@code 9200-GETCARDXREF-BYACCT}; read-only in this test
 *       scenario (COACTUPC never modifies the cross-reference).</li>
 * </ol>
 *
 * <p>Per AAP &sect;0.4.1 and &sect;0.6.11 the auxiliary fixtures are
 * read DIRECTLY from {@code app/} via the
 * {@link GoldenRecordTest#resolveAppDataPath(String)} helper &mdash;
 * NOT copied into {@code java/carddemo-tests/} (the COBOL source tree
 * remains the single source of truth for fixture data). The test
 * orchestrator (see {@link GoldenRecordTest#runProgram(Class,
 * java.nio.file.Path, java.util.List)}) reads the pre-call fixtures
 * into memory, supplies them to in-memory repository adapters, runs the
 * scenario, and captures the post-call state into temporary files for
 * byte-for-byte comparison &mdash; the on-disk
 * {@code app/data/ASCII/} files are NEVER mutated.</p>
 *
 * <h2>Expected Outputs (multi-output scenario)</h2>
 *
 * <p>Per AAP &sect;0.6.11 multi-output pattern (overriding
 * {@link #expectedOutputs()} rather than relying on the base class's
 * single-output default), this test declares FOUR byte-for-byte parity
 * targets that together verify BOTH the COMMIT path and the
 * SYNCPOINT ROLLBACK path:</p>
 * <ol>
 *   <li>{@code acctdata.txt} &mdash; final state of the account
 *       master after the full scenario plays out. Verifies the
 *       COMMIT path's REWRITE (the new ACCOUNT image is persisted)
 *       AND the ROLLBACK path's compensating-write (the ACCOUNT
 *       pre-image is restored). For a scenario that includes both
 *       paths, the captured {@code acctdata.txt} reflects whichever
 *       path was final.</li>
 *   <li>{@code custdata.txt} &mdash; final state of the customer
 *       master after the full scenario plays out. Verifies the
 *       COMMIT path's REWRITE on CUSTDAT.</li>
 *   <li>{@code stdout.txt} &mdash; the PAN-masked SLF4J/DISPLAY
 *       trace per AAP &sect;0.7.2. Every PAN reference is masked to
 *       its last 4 digits before emission; includes the verbatim
 *       error messages for invalid FICO/SSN and the
 *       {@code "Unable to Update Account ..."} message emitted on
 *       the SYNCPOINT ROLLBACK path.</li>
 *   <li>{@code bms_output.txt} &mdash; the serialized
 *       {@code CoActUpOutput} screen states with FULL PAN. One
 *       serialized state per submission in the scenario,
 *       concatenated in submission order. Sequence ordering is
 *       preserved exactly because the user-facing view depends on
 *       the strict submit-then-render protocol of the CICS
 *       pseudo-conversational pattern.</li>
 * </ol>
 *
 * <p>This test is the <strong>NON-NEGOTIABLE PR gate</strong> per
 * AAP &sect;0.6.11. Any deviation in the SYNCPOINT ROLLBACK
 * compensating-write semantics, the FICO/SSN validation ranges, the
 * verbatim COBOL error messages, the PAN-masking behaviour in logs,
 * the preserved COBOL bugs (typo, duplicate 88-level, DOB offset
 * mismatch), or the optimistic-concurrency comparison breaks parity
 * and blocks the PR.</p>
 *
 * <p><strong>Scaffolding state</strong>: per AAP &sect;0.6.11
 * ("Initial test scaffolding may use placeholder expected files
 * marked {@code @Disabled} until COBOL captures are available; the
 * harness skeleton, base class, and per-program test classes are
 * created unconditionally"), the {@link #byteForByteParity()}
 * override below is annotated {@code @Disabled} with a 7-point
 * verification reason citing the COBOL capture procedure documented
 * in {@code java/MIGRATION_NOTES.md}. The harness skeleton is
 * unconditionally present so JUnit discovers and reports this
 * per-program test in CI from day one. The {@code @Disabled}
 * annotation will be removed in the same PR that commits
 * non-placeholder content under
 * {@code src/test/resources/golden/coactupc/expected/}.</p>
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.account.CoActUpC
 * @see CoActVwCGoldenTest
 * @since 25
 */
@DisplayName("COACTUPC \u2014 Account Update Golden-Record Parity (sole SYNCPOINT ROLLBACK)")
public class CoActUpCGoldenTest extends GoldenRecordTest {

    /**
     * Identifier of the per-program fixture directory under
     * {@code src/test/resources/golden/}. Lowercase form of the COBOL
     * {@code PROGRAM-ID COACTUPC} per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention shared with all 28 sibling golden tests.
     */
    private static final String PROGRAM_DIR = "coactupc";

    /**
     * Name of the synthesized scenario file under
     * {@code src/test/resources/golden/coactupc/expected/}. Encodes the
     * multi-submission sequence (initial fetch, valid edit + save
     * COMMIT path, invalid FICO/SSN edit, valid edit + CUST REWRITE
     * failure ROLLBACK path, PF3 back, PF4 clear, PF5 save, PF12
     * cancel) consumed by the harness orchestrator to drive the
     * CoActUpC online-CICS state machine through ALL relevant paths
     * including the sole SYNCPOINT ROLLBACK in the source tree.
     */
    private static final String INPUT_SCENARIO_TXT = "input_scenario.txt";

    /**
     * Name of the captured COBOL final account-master state under
     * {@code src/test/resources/golden/coactupc/expected/}. Verifies
     * BOTH the COMMIT path's {@code REWRITE} (new ACCOUNT image is
     * persisted) AND the SYNCPOINT ROLLBACK path's compensating
     * write (the ACCOUNT pre-image is restored after the CUST
     * REWRITE failure at {@code app/cbl/COACTUPC.cbl:L4100}).
     */
    private static final String ACCTDATA_TXT = "acctdata.txt";

    /**
     * Name of the captured COBOL final customer-master state under
     * {@code src/test/resources/golden/coactupc/expected/}. Verifies
     * the COMMIT path's {@code REWRITE} on CUSTDAT; left untouched
     * by the SYNCPOINT ROLLBACK scenario because the CUST REWRITE is
     * itself the failing operation that triggers the rollback (no
     * partial CUST update is ever persisted in the ROLLBACK case).
     */
    private static final String CUSTDATA_TXT = "custdata.txt";

    /**
     * Name of the captured COBOL card-cross-reference fixture under
     * {@code app/data/ASCII/}. Read-only in COACTUPC (the program
     * never modifies CARDXREF); declared here for symmetry with the
     * auxiliary input declaration. The cross-reference is needed
     * because paragraph {@code 9200-GETCARDXREF-BYACCT} performs the
     * first of the three reads via the {@code CXACAIX} alternate
     * index keyed by account-id.
     */
    private static final String CARDXREF_TXT = "cardxref.txt";

    /**
     * Name of the captured COBOL stdout trace under
     * {@code src/test/resources/golden/coactupc/expected/}. Records
     * the PAN-masked SLF4J/DISPLAY emissions for the scenario,
     * including verbatim error messages for invalid FICO/SSN and the
     * {@code "Unable to Update Account ..."} message emitted on the
     * SYNCPOINT ROLLBACK path. Per AAP &sect;0.7.2 every PAN
     * reference in this file shows the last 4 digits only.
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * Name of the captured BMS output trace under
     * {@code src/test/resources/golden/coactupc/expected/}. Records
     * the serialized {@code CoActUpOutput} screen states (one per
     * submission, eight total covering the full scenario). Per AAP
     * &sect;0.7.2 these records display FULL PAN because the BMS
     * screen is an authorized rendering surface distinct from the
     * SLF4J logging surface.
     */
    private static final String BMS_OUTPUT_TXT = "bms_output.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.account.CoActUpC}{@code .class}.
     * Referenced via fully-qualified class literal so this file's
     * import block stays minimal and restricted to
     * {@link java.nio.file.Path}, {@link java.util.List}, and the
     * JUnit Jupiter API annotations ({@link DisplayName},
     * {@link Disabled}, {@link Test}). The fully-qualified class
     * literal compiles cleanly because {@code carddemo-tests}
     * declares a test-scope dependency on {@code carddemo-application}
     * in {@code java/carddemo-tests/pom.xml} (per AAP &sect;0.5.1).</p>
     */
    @Override
    protected Class<?> programClass() {
        return com.blitzy.carddemo.application.account.CoActUpC.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to
     * {@code src/test/resources/golden/coactupc/expected/input_scenario.txt}
     * via {@link GoldenRecordTest#resolveExpectedOutputPath(String,
     * String)}. This synthesized scenario file encodes the
     * multi-submission sequence (eight terminal submits covering BOTH
     * the COMMIT path and the SYNCPOINT ROLLBACK path) for the
     * online-CICS pseudo-conversational test; it lives alongside the
     * expected outputs under the per-program {@code coactupc/}
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
     * account-master final state at
     * {@code src/test/resources/golden/coactupc/expected/acctdata.txt},
     * resolved via {@link GoldenRecordTest#resolveExpectedOutputPath(
     * String, String)}. This is retained for harness backward
     * compatibility (single-output convention); the actual
     * byte-for-byte parity assertions iterate the four-element list
     * returned by {@link #expectedOutputs()} rather than this single
     * path. The choice of {@code acctdata.txt} as the
     * "primary expected output" is deliberate: it is the file whose
     * post-state distinguishes the COMMIT path (new image) from the
     * SYNCPOINT ROLLBACK path (pre-image restored by compensating
     * write), making it the most diagnostic of the four outputs.</p>
     */
    @Override
    protected Path expectedOutputFile() {
        return resolveExpectedOutputPath(PROGRAM_DIR, ACCTDATA_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns an immutable 3-element {@link List} of auxiliary
     * input fixture paths reflecting the COACTUPC read+update access
     * pattern across the ACCTDAT / CUSTDAT / CXACAIX VSAM clusters:</p>
     * <ol>
     *   <li>{@code app/data/ASCII/acctdata.txt} &mdash; account
     *       master (300-byte fixed-width records per
     *       {@code app/cpy/CVACT01Y.cpy}). Read by primary key
     *       {@code ACCT-ID PIC 9(11)} in paragraph
     *       {@code 9300-GETACCTDATA-BYACCT}; rewritten in paragraph
     *       {@code 9600-WRITE-PROCESSING} on the COMMIT path;
     *       restored to its pre-image by the Java compensating-write
     *       on the SYNCPOINT ROLLBACK path.</li>
     *   <li>{@code app/data/ASCII/custdata.txt} &mdash; customer
     *       master (500-byte fixed-width records per
     *       {@code app/cpy/CVCUS01Y.cpy}). Read by primary key
     *       {@code CUST-ID PIC 9(09)} (recovered from the
     *       {@code ACCT-CUST-ID} foreign key on the account record)
     *       in paragraph {@code 9400-GETCUSTDATA-BYCUST}; rewritten
     *       on the COMMIT path; untouched on the ROLLBACK path
     *       (CUST REWRITE is the failing operation that TRIGGERS the
     *       rollback).</li>
     *   <li>{@code app/data/ASCII/cardxref.txt} &mdash; card
     *       cross-reference (50-byte fixed-width records per
     *       {@code app/cpy/CVACT03Y.cpy}). Read via the
     *       {@code CXACAIX} alternate index keyed by
     *       {@code XREF-ACCT-ID PIC 9(11)} in paragraph
     *       {@code 9200-GETCARDXREF-BYACCT}; read-only throughout
     *       the scenario (COACTUPC never updates the
     *       cross-reference).</li>
     * </ol>
     *
     * <p>The returned list is {@link List#of(Object, Object, Object)}
     * immutable to preserve deterministic ordering. Ordering matters
     * because the harness's
     * {@link GoldenRecordTest#runProgram(Class, java.nio.file.Path,
     * java.util.List) runProgram(Class, Path, List)} orchestration
     * hook wires the supplied fixtures to the file-based repository
     * adapters in declaration order; reordering would change which
     * adapter binds which fixture and break the 3-step lookup of
     * paragraph {@code 9000-READ-ACCT}.</p>
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
     * <p>Declares the FOUR byte-for-byte parity targets for COACTUPC.
     * Overriding this method (rather than relying on the base class's
     * single-output default) is the AAP &sect;0.6.11 idiom for
     * multi-output scenarios; the base
     * {@link GoldenRecordTest#byteForByteParity()} iterates this list
     * and asserts byte parity for each entry independently,
     * identifying any mismatched output by name in the AssertJ
     * failure message.</p>
     * <ol>
     *   <li>{@link #ACCTDATA_TXT} ({@code acctdata.txt}) &mdash; the
     *       final state of the account master. Verifies BOTH the
     *       COMMIT path (new image persisted via {@code REWRITE}) AND
     *       the SYNCPOINT ROLLBACK path (pre-image restored via the
     *       Java compensating write). The most diagnostic of the
     *       four outputs because it is the only file that changes
     *       differently depending on which path was final.</li>
     *   <li>{@link #CUSTDATA_TXT} ({@code custdata.txt}) &mdash; the
     *       final state of the customer master. Verifies the
     *       COMMIT path's {@code REWRITE} on CUSTDAT; left untouched
     *       in the SYNCPOINT ROLLBACK scenario.</li>
     *   <li>{@link #STDOUT_TXT} ({@code stdout.txt}) &mdash; the
     *       PAN-masked SLF4J/DISPLAY trace per AAP &sect;0.7.2.
     *       Every PAN reference is masked to its last 4 digits
     *       before emission; includes verbatim COBOL error messages
     *       for invalid FICO/SSN and the
     *       {@code "Unable to Update Account ..."} message emitted
     *       on the SYNCPOINT ROLLBACK path.</li>
     *   <li>{@link #BMS_OUTPUT_TXT} ({@code bms_output.txt}) &mdash;
     *       the serialized {@code CoActUpOutput} screen states with
     *       FULL PAN. One serialized state per submission, eight
     *       total. The BMS screen is an authorized rendering surface
     *       distinct from the logging surface; PAN masking does NOT
     *       apply to this output per AAP &sect;0.7.2.</li>
     * </ol>
     *
     * <p>Returned list is {@link List#of(Object, Object, Object,
     * Object)} immutable.</p>
     */
    @Override
    protected List<ExpectedOutput> expectedOutputs() {
        return List.of(
            new ExpectedOutput(ACCTDATA_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, ACCTDATA_TXT)),
            new ExpectedOutput(CUSTDATA_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, CUSTDATA_TXT)),
            new ExpectedOutput(STDOUT_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, STDOUT_TXT)),
            new ExpectedOutput(BMS_OUTPUT_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, BMS_OUTPUT_TXT))
        );
    }

    /**
     * Byte-for-byte parity assertion, currently {@code @Disabled}
     * pending the COBOL COACTUPC baseline capture per AAP &sect;0.6.11
     * ("Initial test scaffolding may use placeholder expected files
     * marked {@code @Disabled} until COBOL captures are available").
     *
     * <p>The {@code @Disabled} annotation will be removed in the same
     * PR that commits non-placeholder content under
     * {@code src/test/resources/golden/coactupc/expected/} per the
     * capture procedure documented in {@code java/MIGRATION_NOTES.md}
     * (see also the IMPLEMENTATION DECISION section there describing
     * the try/finally compensating-write translation of the sole
     * COBOL {@code SYNCPOINT ROLLBACK} at
     * {@code app/cbl/COACTUPC.cbl:L4100}). The method body delegates
     * to {@link GoldenRecordTest#byteForByteParity()} so the actual
     * byte-by-byte assertion logic remains centralised in the base
     * class.</p>
     *
     * <p><strong>Why the {@code @Test} annotation is re-declared on
     * this override</strong>: empirically verified against JUnit
     * Jupiter 5.13.1 (pinned in {@code java/pom.xml}
     * dependencyManagement per AAP &sect;0.5.1), the JUnit Platform's
     * annotation lookup does NOT inherit {@code @Test} when a
     * subclass overrides a parent's {@code @Test}-annotated method
     * &mdash; running surefire with
     * {@code -Dtest=CoActUpCGoldenTest} produces "Tests run: 0" when
     * {@code @Test} is omitted from the override but "Tests run: 1,
     * Skipped: 1" when re-declared. Without {@code @Test} here, this
     * test class would be silently dropped from the test suite,
     * defeating the AAP &sect;0.6.11 PR-gate purpose of the harness
     * skeleton. This pattern matches sibling {@link CoActVwCGoldenTest},
     * {@link CbAct01CGoldenTest}, {@link CbAct02CGoldenTest},
     * {@link CbAct03CGoldenTest}, {@link CbCus01CGoldenTest},
     * {@link CbStm03AGoldenTest}, {@link CbStm03BGoldenTest},
     * {@link CbTrn01CGoldenTest}, {@link CbTrn02CGoldenTest},
     * {@link CbTrn03CGoldenTest}, and
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
        "Awaiting COBOL COACTUPC baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md for the regeneration "
            + "procedure and the IMPLEMENTATION DECISION documentation "
            + "for the SYNCPOINT ROLLBACK translation. Activation "
            + "checklist (all 7 must hold before removing @Disabled): "
            + "(1) try/finally + compensating writes preserve the "
            + "ACCOUNT pre-image when CUST REWRITE fails &mdash; "
            + "acctdata.txt MUST be byte-identical to the pre-call "
            + "state on the ROLLBACK path (sole SYNCPOINT ROLLBACK at "
            + "app/cbl/COACTUPC.cbl:L4100 in paragraph "
            + "9600-WRITE-PROCESSING); on the COMMIT path "
            + "acctdata.txt and custdata.txt both reflect the new "
            + "image. "
            + "(2) FICO range [300, 850] inclusive per "
            + "app/cbl/COACTUPC.cbl:L848 (88 FICO-RANGE-IS-VALID "
            + "VALUES 300 THRU 850); values outside the range are "
            + "rejected with the verbatim COBOL message in stdout.txt. "
            + "(3) SSN invalid Part1 set {0, 666, 900..999} (102 "
            + "values total) per app/cbl/COACTUPC.cbl:L121-L123 (88 "
            + "INVALID-SSN-PART1 VALUES 0, 666, 900 THRU 999); inputs "
            + "matching any of these are rejected verbatim. "
            + "(4) PAN masked to the last 4 digits in stdout.txt per "
            + "AAP \u00a70.7.2 (12 leading mask characters such as "
            + "************1234) BUT preserved in full in "
            + "bms_output.txt (the BMS screen is an authorized "
            + "rendering surface distinct from the logging surface). "
            + "(5) COBOL typo ACCT-EXPIRAION-DATE (missing 'T' "
            + "between 'A' and 'I'; six occurrences in COACTUPC.cbl "
            + "at L428, L690-L692, L778-L780, L1491, L1693, L3836) "
            + "preserved as Java field acctExpiraionDate (NOT "
            + "acctExpirationDate) per AAP \u00a70.7.1; byte-equal in "
            + "any DISPLAY trace that names the field. "
            + "(6) Duplicate 88-level DID-NOT-FIND-ACCT-IN-CARDXREF "
            + "at app/cbl/COACTUPC.cbl:L497 and L513 preserved with "
            + "COBOL last-encountered-wins semantics per AAP "
            + "\u00a70.7.1; the second definition's value is used by "
            + "the diagnostic message emission. "
            + "(7) DOB comparison offset mismatch (probable COBOL bug "
            + "in the date-of-birth optimistic-concurrency comparison "
            + "in paragraph 9700-CHECK-CHANGE-IN-REC) preserved "
            + "verbatim per AAP \u00a70.7.1; the captured outputs "
            + "include scenarios that exercise the buggy comparison "
            + "path and the Java translation reproduces the same "
            + "outcome bit-for-bit."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
