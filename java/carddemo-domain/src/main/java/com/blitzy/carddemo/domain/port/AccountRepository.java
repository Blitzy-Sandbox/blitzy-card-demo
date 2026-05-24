/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.blitzy.carddemo.domain.port;

import com.blitzy.carddemo.domain.record.AccountRecord;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Domain port for the {@code ACCTDATA} VSAM KSDS dataset (300-byte
 * fixed-width records, 11-digit {@code ACCT-ID} primary key, copybook
 * {@code app/cpy/CVACT01Y.cpy}). This is the most heavily accessed master
 * file in the application &mdash; six COBOL programs consume it via this
 * port. Concrete adapters live in {@code carddemo-adapter-file}
 * (fixed-width file I/O via {@code java.nio.file}) and optionally in
 * {@code carddemo-adapter-db} (JDBC). The composition root in
 * {@code carddemo-app} wires the appropriate implementation at startup
 * per AAP &sect;0.3.6.
 *
 * <h2>COBOL consumers</h2>
 * This port consolidates the {@code ACCTDATA} access modes exercised by
 * the following COBOL programs:
 * <ul>
 *   <li><b>CBACT01C</b> &mdash; batch sequential dump
 *       ({@code app/cbl/CBACT01C.cbl:L1-L80}). The
 *       {@code 0000-ACCTFILE-OPEN} paragraph opens the file with
 *       {@code OPEN INPUT ACCTFILE-FILE}; the
 *       {@code 1000-ACCTFILE-GET-NEXT} paragraph drives the main loop
 *       with {@code READ ACCTFILE-FILE INTO ACCOUNT-RECORD} over a
 *       {@code SELECT ACCTFILE-FILE ASSIGN TO ACCTFILE ORGANIZATION IS
 *       INDEXED ACCESS MODE IS SEQUENTIAL RECORD KEY IS FD-ACCT-ID}
 *       SELECT clause. Maps to {@link #streamSequential()}.</li>
 *   <li><b>CBACT04C</b> &mdash; interest-posting batch engine. The
 *       {@code 1100-GET-ACCT-DATA} paragraph reads the account by key
 *       ({@code READ ACCOUNT-FILE INTO ACCOUNT-RECORD INVALID KEY ...},
 *       {@code app/cbl/CBACT04C.cbl:L373}); the
 *       {@code 1050-UPDATE-ACCOUNT} paragraph posts the computed interest
 *       and rewrites the record ({@code REWRITE FD-ACCTFILE-REC FROM
 *       ACCOUNT-RECORD}, {@code app/cbl/CBACT04C.cbl:L356}). Maps to
 *       {@link #findById(long)} + {@link #save(AccountRecord)}.</li>
 *   <li><b>CBTRN02C</b> &mdash; full transaction-posting engine. The
 *       {@code 1500-B-LOOKUP-ACCT} paragraph reads the account from the
 *       cross-reference key ({@code MOVE XREF-ACCT-ID TO FD-ACCT-ID /
 *       READ ACCOUNT-FILE INTO ACCOUNT-RECORD INVALID KEY ...},
 *       {@code app/cbl/CBTRN02C.cbl:L395}); the
 *       {@code 2800-UPDATE-ACCOUNT-REC} paragraph rewrites the record
 *       after credit / debit cycle balances are updated
 *       ({@code REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD INVALID
 *       KEY ...}, {@code app/cbl/CBTRN02C.cbl:L554-L559}). Maps to
 *       {@link #findById(long)} + {@link #save(AccountRecord)}.</li>
 *   <li><b>COACTVWC</b> &mdash; online account-view transaction. The
 *       {@code 9300-GETACCTDATA-BYACCT} paragraph performs a random read
 *       via {@code EXEC CICS READ DATASET(LIT-ACCTFILENAME)
 *       RIDFLD(WS-CARD-RID-ACCT-ID-X) KEYLENGTH(LENGTH OF
 *       WS-CARD-RID-ACCT-ID-X) INTO(ACCOUNT-RECORD) LENGTH(LENGTH OF
 *       ACCOUNT-RECORD) RESP(WS-RESP-CD)} at
 *       {@code app/cbl/COACTVWC.cbl:L776-L784} and dispatches on
 *       {@code DFHRESP(NORMAL)} / {@code DFHRESP(NOTFND)}. Maps to
 *       {@link #findById(long)}.</li>
 *   <li><b>COACTUPC</b> &mdash; online account-update transaction. The
 *       account is locked with {@code EXEC CICS READ ... UPDATE} earlier
 *       in the flow, then rewritten via {@code EXEC CICS REWRITE
 *       FILE(LIT-ACCTFILENAME) FROM(ACCT-UPDATE-RECORD) LENGTH(LENGTH OF
 *       ACCT-UPDATE-RECORD) RESP(WS-RESP-CD)} at
 *       {@code app/cbl/COACTUPC.cbl:L4065-L4071}. This program contains
 *       the SOLE {@code EXEC CICS SYNCPOINT ROLLBACK} in the entire
 *       COBOL codebase ({@code app/cbl/COACTUPC.cbl:L4099-L4100}),
 *       issued when the subsequent CUSTOMER rewrite fails after the
 *       ACCOUNT rewrite has already succeeded &mdash; rolling both back
 *       atomically. The two-file rollback boundary is reconstructed at
 *       the application layer (try/finally with compensating writes, per
 *       AAP &sect;0.4.1); it is deliberately NOT modeled in this port.
 *       Maps to {@link #findById(long)} + {@link #save(AccountRecord)}.</li>
 *   <li><b>COBIL00C</b> &mdash; online bill-payment transaction. The
 *       {@code READ-ACCTDAT-FILE} paragraph performs a random read with
 *       lock via {@code EXEC CICS READ DATASET(WS-ACCTDAT-FILE)
 *       INTO(ACCOUNT-RECORD) RIDFLD(ACCT-ID) UPDATE RESP(WS-RESP-CD)} at
 *       {@code app/cbl/COBIL00C.cbl:L345-L354}; the
 *       {@code UPDATE-ACCTDAT-FILE} paragraph rewrites the post-payment
 *       balance via {@code EXEC CICS REWRITE DATASET(WS-ACCTDAT-FILE)
 *       FROM(ACCOUNT-RECORD) LENGTH(LENGTH OF ACCOUNT-RECORD)
 *       RESP(WS-RESP-CD)} at {@code app/cbl/COBIL00C.cbl:L379-L385}.
 *       Maps to {@link #findById(long)} + {@link #save(AccountRecord)}.</li>
 *   <li><b>ACCTFILE.jcl</b> &mdash; IDCAMS {@code DELETE} / {@code DEFINE}
 *       / {@code REPRO} cycle that loads the dataset from
 *       {@code app/data/ASCII/acctdata.txt}. Maps to {@link #delete(long)}
 *       (pre-define cleanup, conceptually) and {@link #save(AccountRecord)}
 *       (bulk REPRO load).</li>
 * </ul>
 *
 * <h2>Key semantics ({@code ACCT-ID})</h2>
 * Per copybook {@code app/cpy/CVACT01Y.cpy} ({@code 05 ACCT-ID PIC 9(11)}),
 * {@code ACCT-ID} is declared as an unsigned 11-digit numeric value. The
 * Java translation uses a primitive {@code long} to match this exactly:
 * <ul>
 *   <li>The valid range is {@code 0L} through {@code 99_999_999_999L}
 *       inclusive (the full COBOL {@code PIC 9(11)} domain).</li>
 *   <li>Primitive {@code long} (not boxed {@code Long}) is mandated by
 *       the folder spec for byte-fidelity and zero-allocation call
 *       sites &mdash; this is the per-record hot path in
 *       {@code CBTRN02C} which performs a {@link #findById(long)} and
 *       {@link #save(AccountRecord)} per transaction. See AAP
 *       &sect;0.4.1 (record table: {@code AccountRecord.acctId()}
 *       declared as {@code long}).</li>
 *   <li>Values outside the {@code [0, 99_999_999_999]} range will fail
 *       the canonical-constructor invariant on {@link AccountRecord}
 *       (per AAP &sect;0.6.3, JEP 513 Flexible Constructor Bodies) when
 *       a save is attempted; adapters MAY pre-validate at the
 *       {@link #findById(long)} / {@link #delete(long)} boundaries for
 *       a fail-fast diagnostic.</li>
 * </ul>
 *
 * <h2>Ordering</h2>
 * {@link #streamSequential()} returns records in ascending
 * {@code ACCT-ID} order &mdash; matching the COBOL VSAM KSDS primary-key
 * traversal order produced by {@code READ NEXT} against an
 * {@code ORGANIZATION IS INDEXED ACCESS MODE IS SEQUENTIAL RECORD KEY IS
 * FD-ACCT-ID} cursor in CBACT01C. Implementations MUST preserve this
 * order; any reordering would change the observable output of CBACT01C
 * (the {@code DISPLAY ACCOUNT-RECORD} loop at
 * {@code app/cbl/CBACT01C.cbl:L78}) and the CBACT04C interest-posting
 * sequential scan, which is forbidden by AAP &sect;0.1.3 ("virtual
 * threads are NOT a license to reorder records, change sort orders, or
 * break sequencing") and AAP &sect;0.7.1 ("All file naming conventions,
 * sort orders, and batch sequencing").
 *
 * <h2>Resource lifecycle</h2>
 * The {@link Stream} returned by {@link #streamSequential()} extends
 * {@link AutoCloseable} (via {@link java.util.stream.BaseStream}).
 * Callers MUST close the returned stream &mdash; typically via
 * try-with-resources &mdash; so that the adapter can release the
 * underlying file channel, DB cursor, or pre-allocated record buffer:
 * <pre>{@code
 *   try (Stream<AccountRecord> accounts = repository.streamSequential()) {
 *       accounts.forEach(this::process);
 *   }
 * }</pre>
 * Failure to close may leak file handles in long-running batch jobs.
 *
 * <p>The repository instance itself is also {@link AutoCloseable}
 * (matching the lifecycle convention used by all sibling port
 * interfaces in this package &mdash; CardRepository, CustomerRepository,
 * TransactionRepository, etc.). Implementations MAY be no-op for
 * in-memory adapters and MUST be idempotent so that callers can
 * defensively close the same repository multiple times without
 * observable side-effects. This mirrors the COBOL
 * {@code CLOSE ACCTFILE-FILE} verb in the
 * {@code 9000-ACCTFILE-CLOSE} paragraph of CBACT01C
 * ({@code app/cbl/CBACT01C.cbl:L151-L167}) and the equivalent
 * {@code CLOSE} statements at the end of CBACT04C, CBTRN01C, and
 * CBTRN02C.
 *
 * <h2>Byte fidelity (AAP &sect;0.6.5)</h2>
 * Implementations of {@link #save(AccountRecord)} MUST persist the
 * entire 300-byte fixed-width record image including the 178-byte
 * {@code FILLER} ({@link AccountRecord#filler()}). The FILLER is
 * structural padding and not business data, but byte-for-byte
 * round-trip equality {@code parse(b).encode() == b} is the formal
 * contract with external file consumers per AAP &sect;0.6.5.
 * Truncating, normalising, or synthesising the FILLER bytes is
 * FORBIDDEN.
 *
 * <h2>Decimal arithmetic fidelity (AAP &sect;0.6.1)</h2>
 * The {@link AccountRecord} carries five monetary {@code PIC S9(10)V99}
 * components ({@code ACCT-CURR-BAL}, {@code ACCT-CREDIT-LIMIT},
 * {@code ACCT-CASH-CREDIT-LIMIT}, {@code ACCT-CURR-CYC-CREDIT},
 * {@code ACCT-CURR-CYC-DEBIT}) as {@link java.math.BigDecimal} values
 * at scale 2. The {@link AccountRecord} canonical constructor already
 * normalises these to scale 2 with
 * {@link java.math.RoundingMode#HALF_EVEN} (banker's rounding); callers
 * passing a {@link AccountRecord} to {@link #save(AccountRecord)} can
 * therefore rely on the encoded byte image preserving trailing zeros
 * (i.e., {@code 1.20} is encoded as {@code "00000001940{"} via zoned
 * decimal, never {@code "0000000194{"}). Implementations MUST NOT
 * rescale or truncate monetary fields beyond what
 * {@link AccountRecord} has already enforced.
 *
 * <h2>Transactional boundary note (COACTUPC SYNCPOINT ROLLBACK)</h2>
 * COACTUPC's {@code EXEC CICS SYNCPOINT ROLLBACK}
 * ({@code app/cbl/COACTUPC.cbl:L4099-L4100}) is the sole CICS
 * transactional rollback in the entire COBOL codebase. It is issued
 * when the ACCOUNT rewrite at
 * {@code app/cbl/COACTUPC.cbl:L4065-L4071} has already succeeded but
 * the subsequent CUSTOMER rewrite at
 * {@code app/cbl/COACTUPC.cbl:L4085-L4091} fails &mdash; rolling both
 * back atomically. Per AAP &sect;0.4.1 ("Try/finally with compensating
 * writes; preserve transactional semantics manually"), this two-file
 * rollback is reconstructed at the application layer in
 * {@code CoActUpC.java}, NOT at the port. The port itself remains
 * compensation-free: each individual {@link #save(AccountRecord)} call
 * is atomic only with respect to the single record it writes.
 *
 * <h2>{@code ACCT-EXPIRAION-DATE} typo preservation</h2>
 * The {@link AccountRecord#acctExpiraionDate()} accessor preserves the
 * misspelling ({@code EXPIRAION} missing the 'T') verbatim from the
 * COBOL copybook ({@code 05 ACCT-EXPIRAION-DATE PIC X(10)} at
 * {@code app/cpy/CVACT01Y.cpy:L11}). Per AAP &sect;0.7.1, COBOL
 * obvious-bug behavior is translated faithfully and flagged in
 * {@code MIGRATION_NOTES.md}; do not "fix" the spelling in this
 * refactor.
 *
 * <h2>No framework dependencies</h2>
 * This interface deliberately avoids Spring, Hibernate, JPA, and Lombok
 * imports per AAP &sect;0.1.1 (the user mandate that no framework be
 * introduced that the existing COBOL program does not require).
 * Concrete adapters provide their own dependency-injection wiring via
 * the composition root. The interface also avoids {@code java.io.File}
 * (per AAP &sect;0.1.1: "Translate every COBOL file I/O into
 * {@code java.nio.file} operations; never use {@code java.io.File} in
 * new code") and {@code java.util.Date} / {@code java.util.Calendar}
 * (per AAP &sect;0.1.1: "never use {@code java.util.Date} or
 * {@code java.util.Calendar}").
 *
 * <h2>Thread safety</h2>
 * Per AAP &sect;0.6.6, batch drivers may fan out per-record work onto
 * virtual threads where the COBOL processing is serial but the
 * per-record work is independent. Implementations of this port SHOULD
 * be safe to call concurrently from multiple virtual threads for
 * {@link #findById(long)} (read-only); concurrency on
 * {@link #save(AccountRecord)} and {@link #delete(long)} is
 * implementation-defined and is typically gated by a per-key lock or
 * single-writer constraint in the file adapter.
 *
 * @see AccountRecord
 * @see com.blitzy.carddemo.domain.util.Decimals
 * @since 1.0.0
 */
public interface AccountRepository extends AutoCloseable {

    /**
     * Random read by the 11-digit {@code ACCT-ID} primary key.
     *
     * <p>Corresponds to the COBOL random reads against the
     * {@code ACCTDATA} dataset:
     * <ul>
     *   <li><b>Batch (CBACT04C, CBTRN02C)</b> &mdash;
     *       {@code READ ACCOUNT-FILE INTO ACCOUNT-RECORD INVALID KEY
     *       ...} at {@code app/cbl/CBTRN02C.cbl:L395} (driven from
     *       {@code XREF-ACCT-ID} during the
     *       {@code 1500-B-LOOKUP-ACCT} paragraph) and
     *       {@code app/cbl/CBACT04C.cbl:L373} (driven from
     *       {@code FD-ACCT-ID} during the {@code 1100-GET-ACCT-DATA}
     *       paragraph).</li>
     *   <li><b>Online (COACTVWC)</b> &mdash;
     *       {@code EXEC CICS READ DATASET(LIT-ACCTFILENAME)
     *       RIDFLD(WS-CARD-RID-ACCT-ID-X) INTO(ACCOUNT-RECORD)
     *       LENGTH(LENGTH OF ACCOUNT-RECORD) RESP(WS-RESP-CD)} at
     *       {@code app/cbl/COACTVWC.cbl:L776-L784} in the
     *       {@code 9300-GETACCTDATA-BYACCT} paragraph. Dispatches on
     *       {@code DFHRESP(NORMAL)} vs {@code DFHRESP(NOTFND)}.</li>
     *   <li><b>Online (COACTUPC, COBIL00C)</b> &mdash; the
     *       {@code EXEC CICS READ ... UPDATE} variant that locks the
     *       record for subsequent {@code REWRITE}. Lock acquisition is
     *       an implementation concern; this port exposes only the
     *       value-read contract.</li>
     * </ul>
     *
     * <h3>Return semantics</h3>
     * <ul>
     *   <li>{@link Optional#of(Object) Optional.of(record)} &mdash;
     *       mirrors the COBOL {@code NOT INVALID KEY} branch (e.g.,
     *       {@code app/cbl/CBTRN02C.cbl:L400}) and the
     *       {@code EVALUATE WS-RESP-CD WHEN DFHRESP(NORMAL)} CICS
     *       branch (e.g., {@code app/cbl/COACTVWC.cbl:L787-L788}).</li>
     *   <li>{@link Optional#empty()} &mdash; mirrors the COBOL
     *       {@code INVALID KEY} branch (e.g.,
     *       {@code app/cbl/CBTRN02C.cbl:L396-L399} sets
     *       {@code WS-VALIDATION-FAIL-REASON = 101} and reason
     *       text "ACCOUNT RECORD NOT FOUND") and the CICS
     *       {@code WHEN DFHRESP(NOTFND)} branch (e.g.,
     *       {@code app/cbl/COACTVWC.cbl:L789-L790}).</li>
     *   <li>An unchecked {@link RuntimeException} &mdash; corresponds
     *       to the COBOL {@code WHEN OTHER} branch (e.g.,
     *       {@code app/cbl/COBIL00C.cbl:L365-L371}) and to the
     *       sequential-batch {@code 9999-ABEND-PROGRAM} dispatch when
     *       {@code ACCTFILE-STATUS} is neither {@code '00'} (OK) nor
     *       {@code '10'} (EOF), per
     *       {@code app/cbl/CBACT01C.cbl:L97-L115}. This interface does
     *       not prescribe a specific exception class so that adapters
     *       may surface the original file-status or DFHRESP / DFHRESP2
     *       codes verbatim.</li>
     * </ul>
     *
     * <h3>Parameter contract</h3>
     * {@code acctId} carries the {@code ACCT-ID} key
     * ({@code PIC 9(11)} per {@code app/cpy/CVACT01Y.cpy:L5})
     * &mdash; an unsigned 11-digit numeric value in the range
     * {@code [0, 99_999_999_999]}. Values outside this range describe
     * accounts that cannot exist in the COBOL domain; implementations
     * MAY either return {@link Optional#empty()} (the conservative
     * choice that matches {@code DFHRESP(NOTFND)} semantics) or throw
     * {@link IllegalArgumentException} (the fail-fast choice that
     * mirrors the canonical-constructor invariant on
     * {@link AccountRecord}). Per AAP &sect;0.6.3 (JEP 513 Flexible
     * Constructor Bodies), {@link AccountRecord} enforces
     * {@code acctId &ge; 0L} on construction.
     *
     * @param acctId the 11-digit {@code ACCT-ID} primary key in the
     *               range {@code [0, 99_999_999_999]}.
     * @return an {@link Optional} carrying the matching
     *         {@link AccountRecord}, or {@link Optional#empty()} if no
     *         record exists with that key (matching COBOL
     *         {@code INVALID KEY} / {@code DFHRESP(NOTFND)} semantics).
     * @throws IllegalArgumentException if {@code acctId} is outside the
     *         {@code [0, 99_999_999_999]} {@code PIC 9(11)} domain
     *         (implementation-defined; see Parameter contract above).
     */
    Optional<AccountRecord> findById(long acctId);

    /**
     * Stream every account in ascending {@code ACCT-ID} order.
     *
     * <p>Corresponds to the CBACT01C main loop
     * ({@code PERFORM UNTIL END-OF-FILE = 'Y' ... PERFORM
     * 1000-ACCTFILE-GET-NEXT ... DISPLAY ACCOUNT-RECORD ...} at
     * {@code app/cbl/CBACT01C.cbl:L1-L80}). The COBOL {@code SELECT}
     * declares {@code ORGANIZATION IS INDEXED ACCESS MODE IS
     * SEQUENTIAL RECORD KEY IS FD-ACCT-ID} (lines L29-L33), which
     * traverses the VSAM KSDS in ascending primary-key order &mdash;
     * the Java translation MUST emit the same order.
     *
     * <p>Also used by CBACT04C's interest-posting sequential scan
     * (which iterates every account, computes interest from the
     * transaction-category balances, and rewrites the post-interest
     * balance via {@link #save(AccountRecord)}).
     *
     * <h3>Ordering invariant</h3>
     * Ascending {@code ACCT-ID} order is part of the observable
     * contract. Per AAP &sect;0.1.3, reordering is FORBIDDEN: virtual
     * threads "are NOT a license to reorder records, change sort
     * orders, or break sequencing." Any reordering would change the
     * byte-for-byte output of CBACT01C's {@code DISPLAY
     * ACCOUNT-RECORD} loop, breaking the golden-record harness
     * (AAP &sect;0.6.11).
     *
     * <h3>Resource lifecycle</h3>
     * The returned {@link Stream} backs an underlying file channel or
     * DB cursor. Callers MUST close the stream &mdash; typically via
     * try-with-resources:
     * <pre>{@code
     *   try (Stream<AccountRecord> accounts = repository.streamSequential()) {
     *       accounts.forEach(this::process);
     *   }
     * }</pre>
     * Failure to close may leak file handles in long-running processes
     * such as nightly batch posting jobs.
     *
     * <h3>Empty-dataset semantics</h3>
     * If the dataset is empty, this method returns an empty
     * (closeable) {@link Stream} rather than {@code null}. This
     * matches the COBOL behavior where the
     * {@code PERFORM UNTIL END-OF-FILE = 'Y'} loop simply terminates
     * on the first {@code READ} returning {@code ACCTFILE-STATUS =
     * '10'} (end-of-file), without abending.
     *
     * @return a closeable {@link Stream} of every {@link AccountRecord}
     *         in the dataset, in ascending {@code ACCT-ID} key order;
     *         never {@code null}.
     */
    Stream<AccountRecord> streamSequential();

    /**
     * Upsert an account record &mdash; inserts when no record exists
     * for the supplied {@code ACCT-ID} primary key, REWRITEs otherwise.
     *
     * <p>Corresponds to the COBOL REWRITE/WRITE statements against the
     * {@code ACCTDATA} dataset:
     * <ul>
     *   <li><b>Batch (CBTRN02C)</b> &mdash;
     *       {@code REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD INVALID
     *       KEY ...} at {@code app/cbl/CBTRN02C.cbl:L554-L559} in the
     *       {@code 2800-UPDATE-ACCOUNT-REC} paragraph, called after
     *       the credit / debit cycle balances are updated by the
     *       posting engine.</li>
     *   <li><b>Batch (CBACT04C)</b> &mdash;
     *       {@code REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD} at
     *       {@code app/cbl/CBACT04C.cbl:L356} in the
     *       {@code 1050-UPDATE-ACCOUNT} paragraph, called after the
     *       computed interest is added to {@code ACCT-CURR-BAL} and
     *       the cycle counters are zeroed.</li>
     *   <li><b>Online (COACTUPC)</b> &mdash;
     *       {@code EXEC CICS REWRITE FILE(LIT-ACCTFILENAME)
     *       FROM(ACCT-UPDATE-RECORD) LENGTH(LENGTH OF
     *       ACCT-UPDATE-RECORD) RESP(WS-RESP-CD)} at
     *       {@code app/cbl/COACTUPC.cbl:L4065-L4071}.</li>
     *   <li><b>Online (COBIL00C)</b> &mdash;
     *       {@code EXEC CICS REWRITE DATASET(WS-ACCTDAT-FILE)
     *       FROM(ACCOUNT-RECORD) LENGTH(LENGTH OF ACCOUNT-RECORD)
     *       RESP(WS-RESP-CD)} at
     *       {@code app/cbl/COBIL00C.cbl:L379-L385} in the
     *       {@code UPDATE-ACCTDAT-FILE} paragraph, called after the
     *       bill-payment debit is applied.</li>
     *   <li><b>JCL (ACCTFILE.jcl)</b> &mdash; IDCAMS REPRO bulk load
     *       from {@code app/data/ASCII/acctdata.txt} during dataset
     *       (re-)load. Each record in the input file becomes one
     *       {@code save} call.</li>
     * </ul>
     *
     * <h3>Upsert semantics</h3>
     * If a record with the same {@link AccountRecord#acctId()} already
     * exists, it is REWRITTEN (replaced wholesale, including the
     * 178-byte FILLER). If no such record exists, a new record is
     * inserted (WRITTEN). This mirrors the COBOL convention where
     * {@code REWRITE} is used for the I-O posting flow and
     * {@code WRITE} is used during the IDCAMS REPRO bulk load &mdash;
     * collapsing both into a single {@code save} call simplifies the
     * application layer and matches the conventional Java
     * repository-pattern idiom.
     *
     * <h3>Byte fidelity (AAP &sect;0.6.5)</h3>
     * Implementations MUST persist the entire 300-byte fixed-width
     * record image including the 178-byte {@code FILLER}
     * ({@link AccountRecord#filler()}). The FILLER is structural
     * padding and not business data, but byte-for-byte round-trip
     * equality {@code parse(b).encode() == b} is the formal contract
     * with external file consumers per AAP &sect;0.6.5. Truncating,
     * normalising, or synthesising the FILLER bytes is FORBIDDEN.
     *
     * <h3>Decimal scale (AAP &sect;0.6.1)</h3>
     * Implementations MUST encode the five monetary
     * {@link java.math.BigDecimal} components
     * ({@code ACCT-CURR-BAL}, {@code ACCT-CREDIT-LIMIT},
     * {@code ACCT-CASH-CREDIT-LIMIT}, {@code ACCT-CURR-CYC-CREDIT},
     * {@code ACCT-CURR-CYC-DEBIT}) at scale 2 with zoned-decimal
     * USAGE DISPLAY encoding (sign overpunch on the rightmost byte)
     * to match the byte layout of the {@code app/data/ASCII/acctdata.txt}
     * fixture. The {@link AccountRecord} canonical constructor already
     * normalises the scale; the {@link com.blitzy.carddemo.domain.util.Decimals}
     * utility centralises the codec.
     *
     * <h3>Atomicity</h3>
     * Each {@code save} call is atomic with respect to the single
     * record it writes. Multi-record transactional boundaries (e.g.,
     * the COACTUPC ACCOUNT-plus-CUSTOMER atomic update at
     * {@code app/cbl/COACTUPC.cbl:L4065-L4103}) are reconstructed at
     * the application layer with try/finally and compensating writes
     * per AAP &sect;0.4.1 &mdash; NOT at this port.
     *
     * @param account the account record to upsert; never {@code null}.
     *                The {@link AccountRecord} canonical constructor
     *                has already validated and normalised all fields
     *                (per AAP &sect;0.6.3, JEP 513 Flexible Constructor
     *                Bodies), so implementations may assume all
     *                invariants hold.
     * @throws NullPointerException if {@code account} is {@code null}.
     */
    void save(AccountRecord account);

    /**
     * Delete the account with the supplied {@code ACCT-ID} primary key.
     * Throws if no such record exists.
     *
     * <p>No COBOL program in this codebase issues
     * {@code EXEC CICS DELETE} against {@code ACCTDATA} at runtime
     * &mdash; the only deletion event is the IDCAMS {@code DELETE}
     * / {@code DEFINE} cycle in {@code app/jcl/ACCTFILE.jcl} which
     * drops and recreates the entire dataset. This method is provided
     * for adapter completeness (the folder spec EXPLICITLY enumerates
     * it as one of the four required methods on this port) and to
     * support seed / teardown operations such as fixture reset in the
     * golden-record harness (AAP &sect;0.6.11).
     *
     * <h3>Not-found semantics</h3>
     * If no account exists with the supplied key, implementations MUST
     * throw a {@link RuntimeException} (typically a typed adapter-level
     * exception). This mirrors the {@code DFHRESP(NOTFND)} fall-through
     * on a {@code DELETE} against a missing key &mdash; there is no
     * compensating no-op in the COBOL idiom. Callers that wish to
     * tolerate missing records SHOULD probe with
     * {@link #findById(long)} first.
     *
     * <h3>Parameter contract</h3>
     * {@code acctId} carries the {@code ACCT-ID} key
     * ({@code PIC 9(11)} per {@code app/cpy/CVACT01Y.cpy:L5})
     * &mdash; an unsigned 11-digit numeric value in the range
     * {@code [0, 99_999_999_999]}. Implementations MAY validate this
     * range and throw {@link IllegalArgumentException} for
     * out-of-range values, mirroring the {@link AccountRecord}
     * canonical-constructor invariant.
     *
     * @param acctId the 11-digit {@code ACCT-ID} primary key of the
     *               account to delete; in the range
     *               {@code [0, 99_999_999_999]}.
     * @throws IllegalArgumentException if {@code acctId} is outside the
     *         {@code [0, 99_999_999_999]} {@code PIC 9(11)} domain
     *         (implementation-defined).
     * @throws RuntimeException if no record exists with the supplied
     *         {@code acctId}, or if the adapter encounters an
     *         underlying I/O failure.
     */
    void delete(long acctId);

    /**
     * Release any underlying resources held by this repository &mdash;
     * file channels, DB cursors, pre-allocated buffers, etc.
     *
     * <p>Corresponds to the COBOL {@code CLOSE ACCTFILE-FILE} verb in
     * the {@code 9000-ACCTFILE-CLOSE} paragraph of CBACT01C
     * ({@code app/cbl/CBACT01C.cbl:L151-L167}) and the equivalent
     * {@code CLOSE} statements at the end of CBACT04C, CBTRN01C, and
     * CBTRN02C. The batch driver in {@code carddemo-batch} composes
     * multiple repository closes via the {@code closeQuietly} helper
     * pattern (see e.g. CbAct04C, CbTrn01C, CbTrn02C in
     * {@code carddemo-application}) that swallows close-time exceptions
     * after logging so that one failing close does not prevent the
     * others from running &mdash; matching the COBOL convention of
     * sequencing 9000-9500 close paragraphs unconditionally.
     *
     * <p>Implementations MAY be no-op for in-memory adapters and MUST
     * be idempotent so that callers can defensively close the same
     * repository multiple times without observable side-effects.
     *
     * <p>Declared without {@code throws Exception} to relieve callers
     * of checked-exception boilerplate; concrete adapter exceptions
     * MUST be wrapped in {@link RuntimeException} subtypes per the
     * Folder Rule (no checked exceptions on port interfaces, matching
     * the consistent sibling-port style established by CardRepository,
     * CustomerRepository, TransactionRepository, etc.).
     */
    @Override
    void close();
}
