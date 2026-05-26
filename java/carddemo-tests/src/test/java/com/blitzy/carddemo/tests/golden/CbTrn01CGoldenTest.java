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
 * Byte-for-byte golden-record parity test for {@code CBTRN01C}
 * (Daily Transaction Validator).
 *
 * <p><strong>Source COBOL:</strong> {@code app/cbl/CBTRN01C.cbl} &mdash; the
 * {@code PROGRAM-ID CBTRN01C} batch program (492 lines, &quot;Post the
 * records from daily transaction file&quot; per the program header comment,
 * but in fact a <em>read-only validation pre-pass</em>: NO posting, NO
 * updates, NO writes to TRANSACT, ACCTDATA, TCATBAL, or DALYREJS). The
 * COBOL opens six files declared in the {@code FILE-CONTROL} section at
 * {@code app/cbl/CBTRN01C.cbl:L28-L62}:
 * <ul>
 *   <li>{@code DALYTRAN-FILE} (DD {@code DALYTRAN}, organisation
 *       {@code SEQUENTIAL}) &mdash; the primary daily-transaction input
 *       stream, FD layout 350 bytes per {@code app/cpy/CVTRA06Y.cpy}
 *       ({@code DALYTRAN-ID PIC X(16)} + {@code DALYTRAN-TYPE-CD PIC X(02)}
 *       + {@code DALYTRAN-CAT-CD PIC 9(04)} + {@code DALYTRAN-SOURCE
 *       PIC X(10)} + {@code DALYTRAN-DESC PIC X(100)} + {@code DALYTRAN-AMT
 *       PIC S9(09)V99} + {@code DALYTRAN-MERCHANT-ID PIC 9(09)} +
 *       {@code DALYTRAN-MERCHANT-NAME PIC X(50)} +
 *       {@code DALYTRAN-MERCHANT-CITY PIC X(50)} +
 *       {@code DALYTRAN-MERCHANT-ZIP PIC X(10)} + {@code DALYTRAN-CARD-NUM
 *       PIC X(16)} + {@code DALYTRAN-ORIG-TS PIC X(26)} +
 *       {@code DALYTRAN-PROC-TS PIC X(26)} + {@code FILLER PIC X(20)} =
 *       350)</li>
 *   <li>{@code CUSTOMER-FILE} (DD {@code CUSTFILE}, organisation
 *       {@code INDEXED} access {@code RANDOM} key {@code FD-CUST-ID})
 *       &mdash; opened-but-unused per AAP &sect;0.7.1; preserved by the
 *       Java translation</li>
 *   <li>{@code XREF-FILE} (DD {@code XREFFILE}, organisation
 *       {@code INDEXED} access {@code RANDOM} key {@code FD-XREF-CARD-NUM})
 *       &mdash; card cross-reference VSAM KSDS, read by
 *       {@code 2000-LOOKUP-XREF} at {@code app/cbl/CBTRN01C.cbl:L227-L239}
 *       keyed on {@code DALYTRAN-CARD-NUM}</li>
 *   <li>{@code CARD-FILE} (DD {@code CARDFILE}, organisation
 *       {@code INDEXED} access {@code RANDOM} key {@code FD-CARD-NUM})
 *       &mdash; opened-but-unused per AAP &sect;0.7.1; preserved by the
 *       Java translation</li>
 *   <li>{@code ACCOUNT-FILE} (DD {@code ACCTFILE}, organisation
 *       {@code INDEXED} access {@code RANDOM} key {@code FD-ACCT-ID})
 *       &mdash; account master VSAM KSDS, read by
 *       {@code 3000-READ-ACCOUNT} at {@code app/cbl/CBTRN01C.cbl:L241-L250}
 *       keyed on {@code XREF-ACCT-ID} (only when the prior XREF lookup
 *       succeeded, i.e. {@code WS-XREF-READ-STATUS = 0})</li>
 *   <li>{@code TRANSACT-FILE} (DD {@code TRANFILE}, organisation
 *       {@code INDEXED} access {@code RANDOM} key {@code FD-TRANS-ID})
 *       &mdash; opened-but-unused per AAP &sect;0.7.1; preserved by the
 *       Java translation</li>
 * </ul>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.transaction.CbTrn01C}. Per
 * AAP &sect;0.4.1 (program-by-program mapping) CBTRN01C is translated
 * into the {@code application/transaction/} subpackage co-located with
 * its sibling translations {@code CbTrn02C}, {@code CbTrn03C},
 * {@code CoTrn00C}, {@code CoTrn01C}, {@code CoTrn02C} (verified
 * against the on-disk sibling folder structure). The Java translation
 * is instantiated by the harness via a 6-argument constructor
 * {@code CbTrn01C(DailyTransactionRepository, CustomerRepository,
 * CardXrefRepository, CardRepository, AccountRepository,
 * TransactionRepository)} mirroring the COBOL 6-file open pattern.</p>
 *
 * <p><strong>Validation-only (NO posting) preserved verbatim</strong>
 * per AAP &sect;0.7.1 (Minimal Change Clause): unlike its sibling
 * {@link com.blitzy.carddemo.application.transaction.CbTrn02C} (the
 * full posting engine), CBTRN01C reads {@code DALYTRAN-RECORD}s and
 * verifies each one references a known card cross-reference and a
 * known account. On lookup failure the program emits a DISPLAY warning
 * but does NOT reject the record, does NOT write to DALYREJS, does NOT
 * post to TRANSACT, does NOT update ACCOUNT balances, and does NOT
 * mutate the TCATBAL. The return code is <strong>always 0</strong>
 * regardless of how many records fail the lookups &mdash; failures
 * surface as informational DISPLAY warnings only, never as a non-zero
 * exit status. The Java translation faithfully preserves this
 * read-only semantic per AAP &sect;0.7.1.</p>
 *
 * <p><strong>Sequential execution preserved verbatim</strong> per AAP
 * &sect;0.6.6 (&quot;virtual threads are NOT a license to reorder
 * records, change sort orders, or break sequencing&quot;). The COBOL
 * {@code PERFORM UNTIL END-OF-DAILY-TRANS-FILE = 'Y'} loop at
 * {@code app/cbl/CBTRN01C.cbl:L164-L186} processes each daily
 * transaction in the order it appears in the input file; the
 * interleaved DISPLAY emissions (the per-record {@code DISPLAY
 * DALYTRAN-RECORD} dump at {@code :L168} alongside the contextual
 * lookup-status messages from {@code 2000-LOOKUP-XREF} and the outer
 * loop's error message at {@code :L181-L183}) are part of the
 * observable output and would be corrupted by any parallel scheduling.
 * The Java translation
 * ({@link com.blitzy.carddemo.application.transaction.CbTrn01C})
 * therefore executes strictly sequentially &mdash; <em>no virtual
 * threads</em>, no parallel streams, no fan-out per AAP &sect;0.6.6.
 * Byte-for-byte parity of this golden test implicitly verifies the
 * sequential invariant: any non-deterministic reordering would
 * mismatch the captured COBOL stdout.</p>
 *
 * <p><strong>Opened-but-unused boilerplate preserved verbatim</strong>
 * per AAP &sect;0.7.1: the COBOL {@code MAIN-PARA} at
 * {@code app/cbl/CBTRN01C.cbl:L156-L197} executes the full 6-file
 * open / close sequence ({@code 0000-DALYTRAN-OPEN} through
 * {@code 0500-TRANFILE-OPEN}, and {@code 9000-DALYTRAN-CLOSE} through
 * {@code 9500-TRANFILE-CLOSE}) even though the main {@code PERFORM
 * UNTIL} loop body only references three of the six files
 * (DALYTRAN read, XREF random lookup, ACCOUNT random lookup). The
 * remaining three files &mdash; CUSTFILE, CARDFILE, TRANFILE &mdash;
 * are opened with side-effect of file-system access verification
 * (existence + INPUT permission) but their contents are never read
 * by the program. Per the AAP Minimal Change Clause this opened-but-
 * unused boilerplate is NOT &quot;optimised away&quot; in the Java
 * translation; the four auxiliary fixtures
 * ({@code cardxref.txt}, {@code acctdata.txt}, {@code custdata.txt},
 * {@code carddata.txt}) are wired through
 * {@link #auxiliaryInputs()} so the file-based adapter implementations
 * receive paths for the full 6-file inventory even though only XREF
 * and ACCT are actually queried inside the loop. (TRANFILE is
 * intentionally opened but not even wired through this list because
 * the production fixture set does not commit a transact.txt under
 * {@code app/data/ASCII/}; the Java translation handles
 * the TRANFILE open as a no-op marker per AAP &sect;0.7.1.)</p>
 *
 * <p><strong>DALYTRAN-RECORD double-DISPLAY pattern preserved
 * verbatim</strong> per AAP &sect;0.7.1: on XREF lookup failure the
 * COBOL emits TWO logical DISPLAY emissions referencing the offending
 * daily transaction record:
 * <ol>
 *   <li>Emission #1 &mdash; the outer-loop unconditional dump at
 *       {@code app/cbl/CBTRN01C.cbl:L168}:
 *       <pre>{@code
 *           IF  END-OF-DAILY-TRANS-FILE = 'N'
 *               DISPLAY DALYTRAN-RECORD
 *           END-IF
 *       }</pre>
 *       executed on every successful read of the daily-transaction
 *       file (i.e. every iteration where {@code DALYTRAN-STATUS = '00'}
 *       after {@code 1000-DALYTRAN-GET-NEXT}). This emits the full
 *       350-byte {@code DALYTRAN-RECORD} buffer for every record.</li>
 *   <li>Emission #2 &mdash; the XREF-lookup error message at
 *       {@code app/cbl/CBTRN01C.cbl:L180-L184}:
 *       <pre>{@code
 *           ELSE
 *               DISPLAY 'CARD NUMBER ' DALYTRAN-CARD-NUM
 *               ' COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-'
 *               DALYTRAN-ID
 *           END-IF
 *       }</pre>
 *       emitted only when {@code WS-XREF-READ-STATUS} != 0
 *       (i.e. {@code 2000-LOOKUP-XREF} reported {@code INVALID KEY}
 *       at {@code app/cbl/CBTRN01C.cbl:L231-L233} and DISPLAYed
 *       {@code 'INVALID CARD NUMBER FOR XREF'}). This emits the
 *       sub-fields {@code DALYTRAN-CARD-NUM} (16 bytes) and
 *       {@code DALYTRAN-ID} (16 bytes) extracted from the same
 *       DALYTRAN-RECORD buffer that was just dumped at emission #1.</li>
 * </ol>
 * The Java translation faithfully reproduces this double-emission
 * pattern: emission #1 is unconditional per successful record read;
 * emission #2 fires only when the XREF lookup fails. Test fixtures
 * are designed to trigger the double-emission on at least one record
 * (the COBOL capture procedure documented in {@code java/MIGRATION_NOTES.md}
 * &sect;1.6 verifies this by injecting at least one
 * {@code DALYTRAN-CARD-NUM} that has no matching entry in
 * {@code cardxref.txt}).</p>
 *
 * <p><strong>Chained-validation pattern preserved verbatim</strong>
 * per AAP &sect;0.7.1: the ACCOUNT lookup at
 * {@code app/cbl/CBTRN01C.cbl:L173-L184} only fires when the prior
 * XREF lookup succeeded ({@code IF WS-XREF-READ-STATUS = 0}). If the
 * XREF lookup failed, {@code 3000-READ-ACCOUNT} is NEVER invoked for
 * that record &mdash; the program falls through to the outer-loop
 * error message and proceeds to the next daily transaction. The Java
 * translation preserves this short-circuit ordering so the captured
 * COBOL stdout is byte-identical regardless of how many lookup
 * failures occur.</p>
 *
 * <p><strong>Verbatim DISPLAY text strings:</strong> the Java
 * translation emits each of the following strings character-for-
 * character as in the COBOL source (preserving any trailing spaces,
 * sentence punctuation, and capitalisation per AAP &sect;0.7.1):
 * {@code "START OF EXECUTION OF PROGRAM CBTRN01C"} at
 * {@code app/cbl/CBTRN01C.cbl:L156}, {@code "END OF EXECUTION OF
 * PROGRAM CBTRN01C"} at {@code :L195}, {@code "INVALID CARD NUMBER
 * FOR XREF"} at {@code :L232}, {@code "SUCCESSFUL READ OF XREF"} at
 * {@code :L235}, {@code "INVALID ACCOUNT NUMBER FOUND"} at
 * {@code :L246}, {@code "SUCCESSFUL READ OF ACCOUNT FILE"} at
 * {@code :L249}, plus the contextual messages
 * {@code "CARD NUMBER: "}, {@code "ACCOUNT ID : "},
 * {@code "CUSTOMER ID: "}, {@code "ACCOUNT "}+{@code " NOT FOUND"},
 * and {@code "CARD NUMBER "}+{@code " COULD NOT BE VERIFIED. SKIPPING
 * TRANSACTION ID-"} on the respective code paths.</p>
 *
 * <p><strong>Input fixture:</strong> {@code app/data/ASCII/dailytran.txt}
 * (300 daily-transaction records, 350 bytes each per
 * {@code app/cpy/CVTRA06Y.cpy:§DALYTRAN-RECORD} = 105,300 bytes
 * total). Read directly from {@code app/} via
 * {@link GoldenRecordTest#resolveAppDataPath(String)} per AAP
 * &sect;0.4.1 and &sect;0.6.11 (the 9 ASCII fixtures are immutable
 * and NOT copied into this module).</p>
 *
 * <p><strong>Auxiliary fixtures:</strong> 4 supplemental files wired
 * through {@link #auxiliaryInputs()} for the file-based adapter
 * implementations: {@code cardxref.txt} (1,850 bytes / 50 records,
 * the XREF lookup target), {@code acctdata.txt} (15,050 bytes / 50
 * records, the ACCOUNT lookup target), {@code custdata.txt} (25,050
 * bytes / 50 records, the opened-but-unused CUSTFILE target), and
 * {@code carddata.txt} (7,550 bytes / 50 records, the opened-but-
 * unused CARDFILE target). TRANFILE is intentionally NOT included
 * because no {@code transact.txt} fixture is committed under
 * {@code app/data/ASCII/} for this read-only validator (the Java
 * adapter handles the TRANFILE open as a documented no-op marker per
 * AAP &sect;0.7.1).</p>
 *
 * <p><strong>Expected output:</strong>
 * {@code src/test/resources/golden/cbtrn01c/expected/stdout.txt}
 * &mdash; captured COBOL DISPLAY stream containing the START / END
 * banners, per-record DALYTRAN-RECORD dumps (300 lines &times; 350
 * bytes), XREF lookup status DISPLAYs (success messages with
 * {@code CARD NUMBER:}/{@code ACCOUNT ID :}/{@code CUSTOMER ID:}
 * triples on hit, {@code INVALID CARD NUMBER FOR XREF} +
 * {@code CARD NUMBER ... COULD NOT BE VERIFIED. SKIPPING TRANSACTION
 * ID-...} on miss), and ACCOUNT lookup status DISPLAYs
 * ({@code SUCCESSFUL READ OF ACCOUNT FILE} on hit,
 * {@code INVALID ACCOUNT NUMBER FOUND} + {@code ACCOUNT ... NOT FOUND}
 * on miss). The captured file is currently a placeholder per AAP
 * &sect;0.6.11 (&quot;Initial test scaffolding may use placeholder
 * expected files marked {@code @Disabled} until COBOL captures are
 * available&quot;); see {@code java/MIGRATION_NOTES.md} &sect;1.6 for
 * the regeneration procedure.</p>
 *
 * <p><strong>Always-0 return code preserved verbatim</strong> per AAP
 * &sect;0.7.1: the COBOL {@code GOBACK} at
 * {@code app/cbl/CBTRN01C.cbl:L197} is unconditional with no
 * {@code MOVE} to a return-code register; the Java translation
 * therefore reports an effective return code of 0 regardless of any
 * lookup failures encountered. This contrasts with sibling
 * {@link com.blitzy.carddemo.application.transaction.CbTrn02C} (the
 * posting engine) where validation failures actively write to
 * DALYREJS but still GOBACK with 0; the always-0 return-code
 * invariant is shared across the CBTRN01C/CBTRN02C family.</p>
 *
 * <p><strong>Why {@link #byteForByteParity()} is currently
 * {@code @Disabled}:</strong> per AAP &sect;0.6.11 (&quot;Initial test
 * scaffolding may use placeholder expected files marked
 * {@code @Disabled} until COBOL captures are available; the harness
 * skeleton, base class, and per-program test classes are created
 * unconditionally&quot;), this test class is created with full
 * override wiring so JUnit Platform discovers and reports it on
 * every CI run, but the byte-equality assertion is suppressed until
 * a real captured COBOL {@code stdout.txt} replaces the
 * {@code # PLACEHOLDER PENDING COBOL CAPTURE} stub currently committed
 * under {@code src/test/resources/golden/cbtrn01c/expected/}. The
 * {@code @Disabled} annotation will be removed in the same PR that
 * commits the real capture per the procedure in
 * {@code java/MIGRATION_NOTES.md} &sect;1.6.</p>
 *
 * <p>This test is the <strong>NON-NEGOTIABLE PR gate</strong> per
 * AAP &sect;0.6.11 (&quot;These are non-negotiable and run on every
 * PR&quot;). Any change to
 * {@link com.blitzy.carddemo.application.transaction.CbTrn01C} that
 * alters the captured DISPLAY stream by even a single byte will fail
 * this test once the {@code @Disabled} is lifted, blocking the PR
 * until either (a) the Java change is reverted, or (b) the captured
 * COBOL baseline is regenerated to reflect a deliberate behaviour
 * change that the AAP authorises.</p>
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.transaction.CbTrn01C
 * @see com.blitzy.carddemo.application.transaction.CbTrn02C
 * @see com.blitzy.carddemo.application.transaction.CbTrn03C
 * @since 25
 */
@DisplayName("CBTRN01C \u2014 Daily Transaction Validator Golden-Record Parity (double-DISPLAY + chained validation preserved)")
public class CbTrn01CGoldenTest extends GoldenRecordTest {

    /**
     * Identifier of the per-program fixture directory under
     * {@code src/test/resources/golden/}. Lowercase form of the COBOL
     * {@code PROGRAM-ID CBTRN01C} per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention shared by all 28 per-program golden tests.
     */
    private static final String PROGRAM_DIR = "cbtrn01c";

    /**
     * Name of the primary input fixture under {@code app/data/ASCII/}.
     * The {@code dailytran.txt} fixture contains 300 daily-transaction
     * records of 350 bytes each (105,300 bytes total) per the
     * {@code DALYTRAN-RECORD} layout defined in
     * {@code app/cpy/CVTRA06Y.cpy}. Wired to the COBOL DD
     * {@code DALYTRAN} via the {@code SELECT DALYTRAN-FILE ASSIGN TO
     * DALYTRAN} clause at {@code app/cbl/CBTRN01C.cbl:L29}. Read
     * directly from {@code app/} via
     * {@link GoldenRecordTest#resolveAppDataPath(String)} &mdash; NOT
     * copied into this module per AAP &sect;0.4.1 and &sect;0.6.11.
     */
    private static final String DAILYTRAN_TXT = "dailytran.txt";

    /**
     * Name of the card cross-reference auxiliary fixture under
     * {@code app/data/ASCII/}. Wired to the COBOL DD {@code XREFFILE}
     * via the {@code SELECT XREF-FILE ASSIGN TO XREFFILE} clause at
     * {@code app/cbl/CBTRN01C.cbl:L40}. Read by the
     * {@code 2000-LOOKUP-XREF} paragraph at
     * {@code app/cbl/CBTRN01C.cbl:L227-L239} for random reads keyed
     * by {@code FD-XREF-CARD-NUM PIC X(16)} matching
     * {@code DALYTRAN-CARD-NUM}.
     */
    private static final String CARDXREF_TXT = "cardxref.txt";

    /**
     * Name of the account-master auxiliary fixture under
     * {@code app/data/ASCII/}. Wired to the COBOL DD {@code ACCTFILE}
     * via the {@code SELECT ACCOUNT-FILE ASSIGN TO ACCTFILE} clause at
     * {@code app/cbl/CBTRN01C.cbl:L52}. Read by the
     * {@code 3000-READ-ACCOUNT} paragraph at
     * {@code app/cbl/CBTRN01C.cbl:L241-L250} for random reads keyed
     * by {@code FD-ACCT-ID PIC 9(11)} matching {@code XREF-ACCT-ID}
     * from the prior XREF lookup. Only queried when the chained
     * validation precondition {@code WS-XREF-READ-STATUS = 0} holds
     * (XREF lookup succeeded).
     */
    private static final String ACCTDATA_TXT = "acctdata.txt";

    /**
     * Name of the customer-master auxiliary fixture under
     * {@code app/data/ASCII/}. Wired to the COBOL DD {@code CUSTFILE}
     * via the {@code SELECT CUSTOMER-FILE ASSIGN TO CUSTFILE} clause
     * at {@code app/cbl/CBTRN01C.cbl:L34}. Despite the COBOL declaring
     * a {@code 0100-CUSTFILE-OPEN} / {@code 9100-CUSTFILE-CLOSE}
     * paragraph pair and reserving the {@code CUSTFILE-STATUS} +
     * {@code FD-CUSTFILE-REC} working-storage fields, CUSTFILE is
     * <strong>opened-but-unused</strong> in the main {@code PERFORM
     * UNTIL} loop &mdash; the program never executes a {@code READ
     * CUSTOMER-FILE}. Per AAP &sect;0.7.1 (Minimal Change Clause) the
     * Java translation faithfully preserves this dead-code lifecycle:
     * the file is opened (verifying file-system presence and INPUT
     * permission), held open for the duration of the run, and closed
     * during teardown, but no records are read.
     */
    private static final String CUSTDATA_TXT = "custdata.txt";

    /**
     * Name of the card-master auxiliary fixture under
     * {@code app/data/ASCII/}. Wired to the COBOL DD {@code CARDFILE}
     * via the {@code SELECT CARD-FILE ASSIGN TO CARDFILE} clause at
     * {@code app/cbl/CBTRN01C.cbl:L46}. Like CUSTFILE this is
     * <strong>opened-but-unused</strong>: the {@code 0300-CARDFILE-OPEN}
     * and {@code 9300-CARDFILE-CLOSE} paragraphs run, but no
     * {@code READ CARD-FILE} is ever executed in the main loop. Per
     * AAP &sect;0.7.1 the Java translation preserves the open/close
     * lifecycle verbatim.
     */
    private static final String CARDDATA_TXT = "carddata.txt";

    /**
     * Name of the captured COBOL {@code DISPLAY} stream under
     * {@code src/test/resources/golden/cbtrn01c/expected/}. Captures
     * the START banner ({@code app/cbl/CBTRN01C.cbl:L156}), per-
     * record {@code DISPLAY DALYTRAN-RECORD} dumps
     * ({@code :L168}, 300 records &times; 350 bytes each), XREF
     * lookup status DISPLAYs ({@code 'INVALID CARD NUMBER FOR XREF'}
     * at {@code :L232}, {@code 'SUCCESSFUL READ OF XREF'} +
     * {@code 'CARD NUMBER: '} + {@code 'ACCOUNT ID : '} +
     * {@code 'CUSTOMER ID: '} triples at {@code :L235-L238}),
     * outer-loop error messages on XREF miss
     * ({@code 'CARD NUMBER ' &lt;num&gt; ' COULD NOT BE VERIFIED.
     * SKIPPING TRANSACTION ID-' &lt;id&gt;} at {@code :L181-L183}),
     * ACCOUNT lookup status DISPLAYs ({@code 'INVALID ACCOUNT
     * NUMBER FOUND'} at {@code :L246}, {@code 'SUCCESSFUL READ OF
     * ACCOUNT FILE'} at {@code :L249}), outer-loop error messages
     * on ACCOUNT miss ({@code 'ACCOUNT ' &lt;id&gt; ' NOT FOUND'}
     * at {@code :L178}), and the END banner ({@code :L195}).
     * Currently a placeholder pending COBOL capture per AAP
     * &sect;0.6.11.
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.transaction.CbTrn01C}{@code .class}.
     * Referenced via fully-qualified class literal so this file's
     * import block remains minimal and restricted to
     * {@link java.nio.file.Path}, {@link java.util.List}, and the
     * JUnit Jupiter API annotations ({@link DisplayName},
     * {@link Disabled}, {@link Test}). The fully-qualified class
     * literal compiles cleanly because {@code carddemo-tests}
     * declares a test-scope transitive dependency on
     * {@code carddemo-application} via {@code carddemo-app}
     * (the composition root) in {@code java/carddemo-tests/pom.xml}
     * per AAP &sect;0.4.1.</p>
     */
    @Override
    protected Class<?> programClass() {
        return com.blitzy.carddemo.application.transaction.CbTrn01C.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to
     * {@code <repo-root>/app/data/ASCII/dailytran.txt} resolved via
     * {@link GoldenRecordTest#resolveAppDataPath(String)}. This is
     * the primary DALYTRAN input (300 records of 350 bytes each per
     * {@code app/cpy/CVTRA06Y.cpy} = 105,300 bytes total) per AAP
     * &sect;0.4.1.</p>
     */
    @Override
    protected Path inputFile() {
        return resolveAppDataPath(DAILYTRAN_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to the captured COBOL
     * stdout trace at
     * {@code src/test/resources/golden/cbtrn01c/expected/stdout.txt},
     * resolved via
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}.
     * Byte-for-byte equality is asserted by
     * {@link GoldenRecordTest#byteForByteParity()} once the
     * {@code @Disabled} guard on the override below is removed (after
     * the placeholder content is replaced by a real COBOL capture per
     * AAP &sect;0.6.11 / {@code java/MIGRATION_NOTES.md} &sect;1.6).</p>
     */
    @Override
    protected Path expectedOutputFile() {
        return resolveExpectedOutputPath(PROGRAM_DIR, STDOUT_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns an immutable 4-element {@link List} of auxiliary
     * input fixture paths, reflecting the COBOL CBTRN01C 6-file open
     * pattern (DALYTRAN primary + 5 auxiliary, of which TRANFILE has
     * no production fixture committed under {@code app/data/ASCII/}):
     * <ol>
     *   <li>{@code app/data/ASCII/cardxref.txt} (DD {@code XREFFILE},
     *       read by {@code 2000-LOOKUP-XREF})</li>
     *   <li>{@code app/data/ASCII/acctdata.txt} (DD {@code ACCTFILE},
     *       read by {@code 3000-READ-ACCOUNT})</li>
     *   <li>{@code app/data/ASCII/custdata.txt} (DD {@code CUSTFILE},
     *       opened-but-unused per AAP &sect;0.7.1)</li>
     *   <li>{@code app/data/ASCII/carddata.txt} (DD {@code CARDFILE},
     *       opened-but-unused per AAP &sect;0.7.1)</li>
     * </ol>
     *
     * <p>Note: TRANFILE is intentionally NOT included in this list
     * because no {@code transact.txt} fixture is committed under
     * {@code app/data/ASCII/} for this read-only validator. The Java
     * adapter handles the TRANFILE open as a documented no-op marker
     * per AAP &sect;0.7.1 (preserve dead lifecycle verbatim without
     * inventing a synthetic fixture).</p>
     *
     * <p>The returned list is {@link List#of(Object, Object, Object,
     * Object)} immutable to preserve deterministic ordering and
     * prevent accidental mutation by the base harness or downstream
     * subclasses. The list is consumed by the
     * {@link GoldenRecordTest#runProgram(Class, java.nio.file.Path,
     * java.util.List) runProgram(Class, Path, List)} orchestration
     * hook (inherited from the base class) to wire up the file-based
     * adapter implementations against the auxiliary input fixtures
     * before invoking {@code CbTrn01C.run()}.</p>
     */
    @Override
    protected List<Path> auxiliaryInputs() {
        return List.of(
            resolveAppDataPath(CARDXREF_TXT),
            resolveAppDataPath(ACCTDATA_TXT),
            resolveAppDataPath(CUSTDATA_TXT),
            resolveAppDataPath(CARDDATA_TXT)
        );
    }

    /**
     * Byte-for-byte parity assertion, currently {@code @Disabled}
     * pending the COBOL CBTRN01C baseline capture per AAP &sect;0.6.11
     * (&quot;Initial test scaffolding may use placeholder expected
     * files marked {@code @Disabled} until COBOL captures are
     * available&quot;).
     *
     * <p>The {@code @Disabled} annotation will be removed in the same
     * PR that commits non-placeholder content under
     * {@code src/test/resources/golden/cbtrn01c/expected/} per the
     * capture procedure documented in {@code java/MIGRATION_NOTES.md}
     * &sect;1.6. The method body delegates to
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
     * {@link CbCus01CGoldenTest}, {@link CbStm03BGoldenTest},
     * {@link CbTrn03CGoldenTest}, and {@link DateValidatorGoldenTest}.</p>
     *
     * <p>The {@code @Disabled} reason embedded below documents the
     * exact invariants that the future maintainer must verify before
     * removing the annotation:
     * <ol>
     *   <li>The double-DISPLAY pattern on validation failures
     *       (DALYTRAN-RECORD is emitted twice on at least one
     *       record where the XREF lookup fails: once unconditionally
     *       at the outer-loop emission #1 site
     *       {@code app/cbl/CBTRN01C.cbl:L168}, then again via the
     *       sub-field emission #2 at
     *       {@code app/cbl/CBTRN01C.cbl:L181-L183}).</li>
     *   <li>Sequential execution preserved (no parallel
     *       reordering of DALYTRAN records per AAP &sect;0.6.6).</li>
     *   <li>Opened-but-unused CUSTFILE / CARDFILE / TRANFILE
     *       lifecycle preserved per AAP &sect;0.7.1.</li>
     *   <li>Chained validation: ACCOUNT lookup fires only when the
     *       prior XREF lookup succeeded
     *       ({@code WS-XREF-READ-STATUS = 0} at
     *       {@code app/cbl/CBTRN01C.cbl:L173}).</li>
     *   <li>Always-0 return code regardless of lookup failures
     *       (failures produce DISPLAY warnings only, not non-zero
     *       exit status per AAP &sect;0.7.1).</li>
     *   <li>Verbatim DISPLAY text strings (capitalisation,
     *       punctuation, trailing spaces).</li>
     * </ol>
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
        "Awaiting COBOL CBTRN01C baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md \u00a71.6 for the regeneration "
            + "procedure. Verify (all 6 must hold before removing "
            + "@Disabled): "
            + "(1) double-DISPLAY pattern on validation failures: "
            + "DALYTRAN-RECORD is emitted twice on at least one record "
            + "where XREF lookup fails -- once unconditionally at the "
            + "outer-loop site (app/cbl/CBTRN01C.cbl:L168), then again "
            + "via the sub-field emission "
            + "(app/cbl/CBTRN01C.cbl:L181-L183 emits DALYTRAN-CARD-NUM "
            + "and DALYTRAN-ID from the same DALYTRAN-RECORD buffer); "
            + "(2) sequential execution preserved -- no parallel "
            + "reordering of DALYTRAN records per AAP \u00a70.6.6 "
            + "(DISPLAY ordering is observable); "
            + "(3) opened-but-unused CUSTFILE/CARDFILE/TRANFILE "
            + "lifecycle preserved verbatim per AAP \u00a70.7.1 -- "
            + "files are OPENed (existence + INPUT permission "
            + "verified) and CLOSEd but never READ in the main "
            + "PERFORM UNTIL loop; "
            + "(4) chained validation -- 3000-READ-ACCOUNT at "
            + "app/cbl/CBTRN01C.cbl:L241-L250 fires only when the "
            + "prior 2000-LOOKUP-XREF succeeded "
            + "(IF WS-XREF-READ-STATUS = 0 at "
            + "app/cbl/CBTRN01C.cbl:L173); "
            + "(5) always-0 return code regardless of lookup failures "
            + "(GOBACK at app/cbl/CBTRN01C.cbl:L197 is unconditional; "
            + "no MOVE to return-code register; lookup failures "
            + "produce DISPLAY warnings only, never non-zero exit "
            + "status); "
            + "(6) verbatim DISPLAY text strings -- 'START OF "
            + "EXECUTION OF PROGRAM CBTRN01C' (:L156), 'END OF "
            + "EXECUTION OF PROGRAM CBTRN01C' (:L195), 'INVALID CARD "
            + "NUMBER FOR XREF' (:L232), 'SUCCESSFUL READ OF XREF' "
            + "(:L235), 'INVALID ACCOUNT NUMBER FOUND' (:L246), "
            + "'SUCCESSFUL READ OF ACCOUNT FILE' (:L249), plus "
            + "contextual messages 'CARD NUMBER: ', 'ACCOUNT ID : ', "
            + "'CUSTOMER ID: ', 'ACCOUNT ... NOT FOUND', and 'CARD "
            + "NUMBER ... COULD NOT BE VERIFIED. SKIPPING "
            + "TRANSACTION ID-...' preserved character-for-character."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
