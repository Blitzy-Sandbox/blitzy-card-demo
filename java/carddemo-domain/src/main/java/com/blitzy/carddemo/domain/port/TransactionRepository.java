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

import com.blitzy.carddemo.domain.record.TranRecord;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Domain port for the {@code TRANSACT} VSAM KSDS dataset (350-byte
 * fixed-width records, 16-byte {@code TRAN-ID} primary key with an
 * alternate index on {@code TRAN-CARD-NUM}, copybook
 * {@code app/cpy/CVTRA05Y.cpy}). Concrete adapters live in
 * {@code carddemo-adapter-file} (fixed-width file I/O via
 * {@code java.nio.file}) and optionally in {@code carddemo-adapter-db}
 * (JDBC). The composition root in {@code carddemo-app} wires the
 * appropriate implementation at startup per AAP &sect;0.3.6.
 *
 * <h2>COBOL consumers</h2>
 * This port consolidates the {@code TRANSACT} access modes exercised
 * by the following COBOL programs:
 * <ul>
 *   <li><b>CBTRN02C</b> &mdash; full transaction-posting batch engine.
 *       The {@code 2900-WRITE-TRANSACTION-FILE} paragraph writes each
 *       newly-posted transaction sequentially to the output dataset
 *       via {@code WRITE FD-TRANFILE-REC FROM TRAN-RECORD} at
 *       {@code app/cbl/CBTRN02C.cbl:L564}. Maps to
 *       {@link #save(TranRecord)}.</li>
 *   <li><b>CBTRN03C</b> &mdash; paginated transaction-detail report
 *       writer. The {@code READ TRANSACT-FILE INTO TRAN-RECORD}
 *       statement at {@code app/cbl/CBTRN03C.cbl:L249} drives the
 *       sequential scan over the entire dataset in ascending
 *       {@code TRAN-ID} key order, producing the report body. Maps to
 *       {@link #streamSequential()}.</li>
 *   <li><b>COTRN00C</b> &mdash; online transaction-list browse.
 *       The {@code STARTBR-TRANSACT-FILE} paragraph issues
 *       {@code EXEC CICS STARTBR DATASET(WS-TRANSACT-FILE)
 *       RIDFLD(TRAN-ID) KEYLENGTH(LENGTH OF TRAN-ID)} at
 *       {@code app/cbl/COTRN00C.cbl:L593}; the
 *       {@code READNEXT-TRANSACT-FILE} paragraph at
 *       {@code app/cbl/COTRN00C.cbl:L626} advances the cursor for
 *       forward pagination; {@code READPREV-TRANSACT-FILE} at
 *       {@code app/cbl/COTRN00C.cbl:L660} reverses for backward
 *       pagination; {@code ENDBR-TRANSACT-FILE} closes the cursor.
 *       Maps to {@link #streamFrom(String)} (forward browse) and
 *       {@link #streamSequential()} (top-of-page).</li>
 *   <li><b>COTRN01C</b> &mdash; online transaction-view (random
 *       read by key). The {@code READ-TRANSACT-FILE} paragraph
 *       issues {@code EXEC CICS READ DATASET(WS-TRANSACT-FILE)
 *       INTO(TRAN-RECORD) LENGTH(LENGTH OF TRAN-RECORD)
 *       RIDFLD(TRAN-ID) KEYLENGTH(LENGTH OF TRAN-ID) UPDATE
 *       RESP(WS-RESP-CD)} at {@code app/cbl/COTRN01C.cbl:L269} and
 *       dispatches on {@code DFHRESP(NORMAL)} (record found,
 *       {@code app/cbl/COTRN01C.cbl:L281-L282}) vs
 *       {@code DFHRESP(NOTFND)} (record absent, sets
 *       "Transaction ID NOT found..." message at
 *       {@code app/cbl/COTRN01C.cbl:L283-L288}). Maps to
 *       {@link #findById(String)}.</li>
 *   <li><b>COTRN02C</b> &mdash; online transaction-add. The
 *       {@code STARTBR-TRANSACT-FILE} paragraph at
 *       {@code app/cbl/COTRN02C.cbl:L644} positions the cursor
 *       (the source comments out the {@code GTEQ} clause &mdash;
 *       see "Next-ID generation" section below); the
 *       {@code READPREV-TRANSACT-FILE} paragraph at
 *       {@code app/cbl/COTRN02C.cbl:L675} retrieves the
 *       lexicographically largest existing {@code TRAN-ID}. When the
 *       file is empty, {@code DFHRESP(ENDFILE)} triggers
 *       {@code MOVE ZEROS TO TRAN-ID} at
 *       {@code app/cbl/COTRN02C.cbl:L689}, yielding a synthetic
 *       all-zeros starting key. The {@code WRITE-TRANSACT-FILE}
 *       paragraph at {@code app/cbl/COTRN02C.cbl:L713-L721} then
 *       inserts the newly-numbered record via
 *       {@code EXEC CICS WRITE DATASET(WS-TRANSACT-FILE)
 *       FROM(TRAN-RECORD) RIDFLD(TRAN-ID) KEYLENGTH(LENGTH OF
 *       TRAN-ID) RESP(WS-RESP-CD)}. Maps to
 *       {@link #findHighestId()} + {@link #save(TranRecord)}.</li>
 *   <li><b>COBIL00C</b> &mdash; online bill-payment. After applying
 *       the payment debit to the account and crediting the
 *       transaction-category balance, the {@code WRITE-TRANSACT-FILE}
 *       paragraph at {@code app/cbl/COBIL00C.cbl:L512-L520} inserts
 *       the bill-payment transaction record via
 *       {@code EXEC CICS WRITE DATASET(WS-TRANSACT-FILE)
 *       FROM(TRAN-RECORD) RIDFLD(TRAN-ID) RESP(WS-RESP-CD)}. Maps
 *       to {@link #save(TranRecord)}.</li>
 *   <li><b>TRANFILE.jcl / TRANIDX.jcl / TRANBKP.jcl</b> &mdash;
 *       IDCAMS {@code DELETE} / {@code DEFINE CLUSTER} /
 *       {@code DEFINE ALTERNATEINDEX} / {@code REPRO} cycle that
 *       loads the dataset and its AIX from the supplied flat input.
 *       Bulk REPRO maps to repeated {@link #save(TranRecord)}
 *       calls.</li>
 * </ul>
 *
 * <h2>Key semantics ({@code TRAN-ID})</h2>
 * Per copybook {@code app/cpy/CVTRA05Y.cpy:L5}
 * ({@code 05 TRAN-ID PIC X(16)}), {@code TRAN-ID} is declared as a
 * 16-byte alphanumeric value. The Java translation uses
 * {@link String} rather than a numeric primitive because the COBOL
 * field is alphanumeric and {@code STARTBR} / {@code READPREV}
 * traversal is lexicographic (byte-wise) rather than numeric. The
 * valid contract is "a 16-character {@link String} matching the
 * underlying COBOL field width." Implementations MAY accept shorter
 * strings and right-pad with spaces to match the COBOL convention,
 * but the canonical form passed across this port is the full
 * 16-character value as encoded by
 * {@link TranRecord#tranId()}.
 *
 * <h2>Next-ID generation idiom (COTRN02C)</h2>
 * COTRN02C generates new transaction IDs by reading the
 * lexicographically largest existing key and incrementing it. The
 * COBOL flow is:
 * <ol>
 *   <li>{@code STARTBR-TRANSACT-FILE} at
 *       {@code app/cbl/COTRN02C.cbl:L644} &mdash; opens a browse
 *       cursor positioned at the value currently in {@code TRAN-ID}.
 *       Because COTRN02C does NOT pre-load {@code TRAN-ID} with
 *       {@code HIGH-VALUES} (the typical idiom for "position at end
 *       of file"), the positioning depends on whatever value
 *       {@code TRAN-ID} held when the {@code STARTBR} was issued
 *       &mdash; see {@code MIGRATION_NOTES.md} for the faithful
 *       translation of this subtle behavior.</li>
 *   <li>{@code READPREV-TRANSACT-FILE} at
 *       {@code app/cbl/COTRN02C.cbl:L675} &mdash; reads backward
 *       from the cursor. On the first successful {@code READPREV},
 *       {@code TRAN-RECORD} is loaded with the previous record's
 *       contents (including its key).</li>
 *   <li>If the file is empty, {@code DFHRESP(ENDFILE)} fires and the
 *       program executes {@code MOVE ZEROS TO TRAN-ID} at
 *       {@code app/cbl/COTRN02C.cbl:L689}, synthesising a 16-byte
 *       all-zero starting key.</li>
 *   <li>{@code ENDBR-TRANSACT-FILE} at
 *       {@code app/cbl/COTRN02C.cbl:L702} &mdash; closes the
 *       cursor.</li>
 *   <li>The application increments the retrieved {@code TRAN-ID} to
 *       produce the next sequential key, then issues
 *       {@code WRITE-TRANSACT-FILE} at
 *       {@code app/cbl/COTRN02C.cbl:L713-L721} to insert the new
 *       record.</li>
 * </ol>
 * The {@link #findHighestId()} method preserves the observable
 * effect of steps 1&ndash;4 by returning the record with the
 * lexicographically largest {@code TRAN-ID}, or {@link Optional#empty()}
 * for an empty dataset (matching the {@code MOVE ZEROS TO TRAN-ID}
 * fallback). Implementations MAY scan the dataset, query a secondary
 * index, or maintain a high-water mark &mdash; the public contract is
 * "return the record with the lexicographically largest
 * {@code TRAN-ID}, or {@link Optional#empty()} when the dataset is
 * empty." The application layer is responsible for the increment
 * logic to produce the next ID.
 *
 * <h2>Alternate-index access ({@code TRAN-CARD-NUM})</h2>
 * The {@code TRANSACT} dataset defines an alternate index on
 * {@code TRAN-CARD-NUM} ({@code 05 TRAN-CARD-NUM PIC X(16)} at
 * {@code app/cpy/CVTRA05Y.cpy:L15}) &mdash; an unsigned 16-character
 * card number. The AIX supports the customer-statement and
 * bill-payment use cases that need to list a single card's
 * transactions without scanning the entire master file. The
 * {@link #streamByCardNumber(String)} method exposes this access
 * path; the file-based adapter implements it via an in-memory
 * secondary index, and the optional JDBC adapter exposes it via a
 * secondary key column. See AAP &sect;0.4.1 "TRANSACT (KSDS + AIX,
 * record 350)" for the AIX requirement.
 *
 * <h2>Ordering</h2>
 * {@link #streamSequential()} returns records in ascending
 * {@code TRAN-ID} order &mdash; matching the COBOL VSAM KSDS
 * primary-key traversal produced by sequential {@code READ
 * TRANSACT-FILE} in CBTRN03C and the {@code STARTBR}/{@code READNEXT}
 * cursor in COTRN00C. {@link #streamFrom(String)} preserves the same
 * ascending order, starting at the first record with
 * {@code TRAN-ID >= startTranId} (the {@code GTEQ} semantic of CICS
 * {@code STARTBR}). {@link #streamByCardNumber(String)} returns
 * records in ascending {@code TRAN-CARD-NUM} order with the
 * secondary tiebreaker of ascending {@code TRAN-ID} &mdash; the
 * natural AIX traversal. Implementations MUST preserve these orders;
 * reordering would change observable output and is FORBIDDEN by AAP
 * &sect;0.1.3 ("virtual threads are NOT a license to reorder
 * records, change sort orders, or break sequencing") and AAP
 * &sect;0.7.1 ("All file naming conventions, sort orders, and batch
 * sequencing").
 *
 * <h2>No delete operation</h2>
 * This interface deliberately omits a {@code delete(String)} method.
 * No COBOL program in the source tree issues
 * {@code EXEC CICS DELETE} or {@code DELETE FILE} against
 * {@code TRANSACT} &mdash; the dataset is append-only at the program
 * level (the IDCAMS {@code DELETE CLUSTER} in TRANFILE.jcl operates
 * at the dataset level, not the record level, and is reconstructed
 * in {@code DefineTransactionFileApp} rather than via this port).
 * Per AAP &sect;0.7.1 "Preserve-As-Is" mandate, adding a
 * record-level delete operation that the COBOL system does not
 * expose would constitute a behavior change beyond migration scope.
 *
 * <h2>Resource lifecycle</h2>
 * The {@link Stream} returned by {@link #streamSequential()},
 * {@link #streamFrom(String)}, and {@link #streamByCardNumber(String)}
 * extends {@link AutoCloseable} (via
 * {@link java.util.stream.BaseStream}). Callers MUST close the
 * returned stream &mdash; typically via try-with-resources &mdash; so
 * that the adapter can release the underlying file channel, DB
 * cursor, or pre-allocated record buffer:
 * <pre>{@code
 *   try (Stream<TranRecord> transactions = repository.streamSequential()) {
 *       transactions.forEach(this::process);
 *   }
 * }</pre>
 * Failure to close may leak file handles in long-running batch jobs
 * such as the nightly transaction posting (CBTRN02C) or detail
 * reporting (CBTRN03C) flows.
 *
 * <p>The repository instance itself is also {@link AutoCloseable}
 * (matching the lifecycle convention used by all sibling
 * big-dataset port interfaces in this package &mdash;
 * {@link AccountRepository}, {@link CardRepository},
 * {@link CustomerRepository}, {@link CardXrefRepository},
 * {@link DailyTransactionRepository}, etc.). Implementations MAY be
 * no-op for in-memory adapters and MUST be idempotent so that
 * callers can defensively close the same repository multiple times
 * without observable side-effects. This mirrors the COBOL
 * {@code CLOSE TRANSACT-FILE} verb at the end of CBTRN02C and
 * CBTRN03C ({@code app/cbl/CBTRN03C.cbl:L516}) and the CICS
 * {@code ENDBR} closures of the COTRN00C/COTRN02C browse cursors.
 *
 * <h2>Byte fidelity (AAP &sect;0.6.5)</h2>
 * Implementations of {@link #save(TranRecord)} MUST persist the
 * entire 350-byte fixed-width record image including the 20-byte
 * trailing {@code FILLER} ({@link TranRecord#filler()}). The FILLER
 * is structural padding and not business data, but byte-for-byte
 * round-trip equality {@code parse(b).encode() == b} is the formal
 * contract with external file consumers per AAP &sect;0.6.5.
 * Truncating, normalising, or synthesising the FILLER bytes is
 * FORBIDDEN.
 *
 * <h2>Decimal arithmetic fidelity (AAP &sect;0.6.1)</h2>
 * The {@link TranRecord} carries one monetary {@code PIC S9(09)V99}
 * field ({@code TRAN-AMT}) as a {@link java.math.BigDecimal} at
 * scale 2. The {@link TranRecord} canonical constructor already
 * normalises this to scale 2 with
 * {@link java.math.RoundingMode#HALF_EVEN} (banker's rounding);
 * callers passing a {@link TranRecord} to {@link #save(TranRecord)}
 * can therefore rely on the encoded byte image preserving trailing
 * zeros. Implementations MUST NOT rescale or truncate the monetary
 * field beyond what {@link TranRecord} has already enforced.
 *
 * <h2>Duplicate-key semantics on {@link #save(TranRecord)}</h2>
 * The COBOL semantics differ subtly between the batch and online
 * call sites:
 * <ul>
 *   <li><b>Batch sequential output (CBTRN02C
 *       {@code WRITE FD-TRANFILE-REC FROM TRAN-RECORD},
 *       {@code app/cbl/CBTRN02C.cbl:L564})</b> &mdash; writes to a
 *       sequentially-organised output dataset (the daily posted
 *       transactions file). Duplicate keys are not detected because
 *       the dataset has no index; the application is responsible for
 *       generating unique {@code TRAN-ID} values upstream.</li>
 *   <li><b>Online insert (COTRN02C
 *       {@code EXEC CICS WRITE ... RIDFLD(TRAN-ID)} at
 *       {@code app/cbl/COTRN02C.cbl:L713-L721}; COBIL00C
 *       {@code EXEC CICS WRITE ... RIDFLD(TRAN-ID)} at
 *       {@code app/cbl/COBIL00C.cbl:L512-L520})</b> &mdash; writes
 *       to the KSDS via the primary key. CICS {@code WRITE} on a
 *       KSDS rejects duplicate keys with {@code DFHRESP(DUPREC)};
 *       the calling program is expected to handle the error.</li>
 * </ul>
 * To preserve both observable behaviors without forking the
 * interface, implementations MAY treat duplicate-key {@code save}
 * as an in-place REWRITE (upsert), as a thrown exception
 * (insert-only), or as silent overwrite (sequential append) &mdash;
 * the composition root selects the right adapter for each consumer.
 * Specifically: the file-based sequential-output adapter used by
 * CBTRN02C silently appends; the file-based KSDS adapter used by
 * COTRN02C/COBIL00C rejects duplicates by throwing; the optional
 * JDBC adapter may be configured for either mode.
 *
 * <h2>No framework dependencies</h2>
 * This interface deliberately avoids Spring, Hibernate, JPA, and
 * Lombok imports per AAP &sect;0.1.1 (the user mandate that no
 * framework be introduced that the existing COBOL program does not
 * require). Concrete adapters provide their own dependency-injection
 * wiring via the composition root. The interface also avoids
 * {@code java.io.File} (per AAP &sect;0.1.1: "Translate every COBOL
 * file I/O into {@code java.nio.file} operations; never use
 * {@code java.io.File} in new code") and {@code java.util.Date} /
 * {@code java.util.Calendar} (per AAP &sect;0.1.1: "never use
 * {@code java.util.Date} or {@code java.util.Calendar}").
 *
 * <h2>Thread safety</h2>
 * Per AAP &sect;0.6.6, batch drivers may fan out per-record work
 * onto virtual threads where the COBOL processing is serial but the
 * per-record work is independent. Implementations of this port
 * SHOULD be safe to call concurrently from multiple virtual threads
 * for {@link #findById(String)} and {@link #findHighestId()}
 * (read-only); concurrency on {@link #save(TranRecord)} is
 * implementation-defined and is typically gated by a per-key lock
 * or single-writer constraint in the file adapter. Stream-returning
 * methods ({@link #streamSequential()},
 * {@link #streamFrom(String)}, {@link #streamByCardNumber(String)})
 * are not required to be thread-safe at the stream level; each
 * caller should obtain its own stream and close it within the same
 * thread or virtual-thread scope.
 *
 * @see TranRecord
 * @see com.blitzy.carddemo.domain.util.Decimals
 * @since 1.0.0
 */
public interface TransactionRepository extends AutoCloseable {

    /**
     * Random read by the 16-character {@code TRAN-ID} primary key.
     *
     * <p>Corresponds to the COBOL random read against the
     * {@code TRANSACT} dataset:
     * <ul>
     *   <li><b>Online (COTRN01C)</b> &mdash;
     *       {@code EXEC CICS READ DATASET(WS-TRANSACT-FILE)
     *       INTO(TRAN-RECORD) LENGTH(LENGTH OF TRAN-RECORD)
     *       RIDFLD(TRAN-ID) KEYLENGTH(LENGTH OF TRAN-ID) UPDATE
     *       RESP(WS-RESP-CD)} at {@code app/cbl/COTRN01C.cbl:L269}
     *       in the {@code READ-TRANSACT-FILE} paragraph. Dispatches
     *       on {@code DFHRESP(NORMAL)} vs {@code DFHRESP(NOTFND)}.
     *       Note that the COBOL form uses {@code UPDATE} to acquire
     *       a record-lock; lock acquisition is an implementation
     *       concern of the adapter, not part of this port
     *       contract.</li>
     * </ul>
     *
     * <h3>Return semantics</h3>
     * <ul>
     *   <li>{@link Optional#of(Object) Optional.of(record)} &mdash;
     *       mirrors the COBOL {@code WHEN DFHRESP(NORMAL)} branch at
     *       {@code app/cbl/COTRN01C.cbl:L281-L282}.</li>
     *   <li>{@link Optional#empty()} &mdash; mirrors the COBOL
     *       {@code WHEN DFHRESP(NOTFND)} branch at
     *       {@code app/cbl/COTRN01C.cbl:L283-L288}, where the
     *       program sets the message
     *       {@code 'Transaction ID NOT found...'} and re-displays
     *       the form.</li>
     *   <li>An unchecked {@link RuntimeException} &mdash; corresponds
     *       to the COBOL {@code WHEN OTHER} branch at
     *       {@code app/cbl/COTRN01C.cbl:L289-L293}, which displays
     *       the {@code RESP} / {@code REAS} codes and surfaces a
     *       generic "Unable to lookup Transaction..." error. This
     *       interface does not prescribe a specific exception class
     *       so that adapters may surface the original
     *       {@code DFHRESP} / {@code DFHRESP2} codes verbatim.</li>
     * </ul>
     *
     * <h3>Parameter contract</h3>
     * {@code tranId} carries the {@code TRAN-ID} key
     * ({@code PIC X(16)} per {@code app/cpy/CVTRA05Y.cpy:L5})
     * &mdash; a 16-character alphanumeric value. Implementations MAY
     * accept shorter strings and right-pad with spaces to the
     * 16-byte field width, or normalise via
     * {@link String#strip()} on the left before key lookup &mdash;
     * the adapter must decide once and apply consistently to both
     * the lookup key and the persisted record. {@code null} input
     * is not permitted and SHOULD raise
     * {@link NullPointerException} on the caller's side.
     *
     * @param tranId the 16-character {@code TRAN-ID} primary key;
     *               must not be {@code null}.
     * @return an {@link Optional} carrying the matching
     *         {@link TranRecord}, or {@link Optional#empty()} if no
     *         record exists with that key (matching COBOL
     *         {@code DFHRESP(NOTFND)} semantics).
     * @throws NullPointerException if {@code tranId} is {@code null}.
     */
    Optional<TranRecord> findById(String tranId);

    /**
     * Return the transaction record with the lexicographically
     * largest {@code TRAN-ID}, or {@link Optional#empty()} if the
     * dataset is empty.
     *
     * <p>Corresponds to the COBOL idiom used by COTRN02C to generate
     * the next sequential transaction ID:
     * <ol>
     *   <li>{@code STARTBR-TRANSACT-FILE} at
     *       {@code app/cbl/COTRN02C.cbl:L644} &mdash; opens a browse
     *       cursor against the {@code TRANSACT} dataset.</li>
     *   <li>{@code READPREV-TRANSACT-FILE} at
     *       {@code app/cbl/COTRN02C.cbl:L675} &mdash; reads backward
     *       from the cursor. On the first successful
     *       {@code READPREV}, {@code TRAN-RECORD} holds the previous
     *       record's contents (including its key).</li>
     *   <li>If the file is empty, {@code DFHRESP(ENDFILE)} triggers
     *       the fallback {@code MOVE ZEROS TO TRAN-ID} at
     *       {@code app/cbl/COTRN02C.cbl:L689}.</li>
     *   <li>{@code ENDBR-TRANSACT-FILE} at
     *       {@code app/cbl/COTRN02C.cbl:L702} closes the
     *       cursor.</li>
     * </ol>
     * The application increments the retrieved {@code TRAN-ID} to
     * produce the next sequential key, then issues
     * {@code WRITE-TRANSACT-FILE} at
     * {@code app/cbl/COTRN02C.cbl:L713-L721} to insert the new
     * record. Preserving this idiom matters for byte-fidelity of
     * generated {@code TRAN-ID} values.
     *
     * <h3>Return semantics</h3>
     * <ul>
     *   <li>{@link Optional#of(Object) Optional.of(record)} &mdash;
     *       the record with the lexicographically largest
     *       {@code TRAN-ID}; mirrors the COBOL state at the end of
     *       the {@code STARTBR}/{@code READPREV} sequence on a
     *       non-empty dataset.</li>
     *   <li>{@link Optional#empty()} &mdash; the dataset is empty;
     *       mirrors the COBOL {@code DFHRESP(ENDFILE)} fallback at
     *       {@code app/cbl/COTRN02C.cbl:L689} ({@code MOVE ZEROS TO
     *       TRAN-ID}). The application layer is responsible for
     *       interpreting empty as "start with all-zeros key" if that
     *       is the desired next-ID seed.</li>
     * </ul>
     *
     * <h3>Implementation contract</h3>
     * Implementations MAY scan the dataset linearly, query a
     * secondary index, or maintain a high-water mark &mdash; the
     * public contract is "return the record with the
     * lexicographically largest {@code TRAN-ID}, or
     * {@link Optional#empty()} when the dataset is empty." The
     * adapter MUST guarantee lexicographic (byte-wise) comparison
     * to match COBOL VSAM KSDS key ordering. Numeric comparison
     * would diverge for keys containing non-digit characters or
     * differing in leading zeros and is FORBIDDEN.
     *
     * @return an {@link Optional} carrying the {@link TranRecord}
     *         with the lexicographically largest {@code TRAN-ID},
     *         or {@link Optional#empty()} when the dataset is empty.
     */
    Optional<TranRecord> findHighestId();

    /**
     * Stream every transaction in ascending {@code TRAN-ID} order.
     *
     * <p>Corresponds to the sequential traversal of the
     * {@code TRANSACT} dataset:
     * <ul>
     *   <li><b>Batch (CBTRN03C)</b> &mdash;
     *       {@code READ TRANSACT-FILE INTO TRAN-RECORD} at
     *       {@code app/cbl/CBTRN03C.cbl:L249} drives the
     *       report-writer main loop. The {@code SELECT TRANSACT-FILE
     *       ASSIGN TO TRANFILE} clause at
     *       {@code app/cbl/CBTRN03C.cbl:L29} declares the sequential
     *       organisation; the {@code OPEN INPUT TRANSACT-FILE} at
     *       {@code app/cbl/CBTRN03C.cbl:L378} opens it for the
     *       paginated report; the {@code CLOSE TRANSACT-FILE} at
     *       {@code app/cbl/CBTRN03C.cbl:L516} closes it.</li>
     *   <li><b>Online (COTRN00C top-of-page)</b> &mdash; the
     *       underlying {@code STARTBR-TRANSACT-FILE} +
     *       {@code READNEXT-TRANSACT-FILE} loop at
     *       {@code app/cbl/COTRN00C.cbl:L593-L626} drives forward
     *       pagination. When the program first enters with no
     *       starting key, the cursor positions at the start of the
     *       dataset and {@link #streamSequential()} matches that
     *       behavior. For pagination from a specific key, use
     *       {@link #streamFrom(String)} instead.</li>
     * </ul>
     *
     * <h3>Ordering invariant</h3>
     * Ascending {@code TRAN-ID} order (lexicographic / byte-wise) is
     * part of the observable contract. Per AAP &sect;0.1.3,
     * reordering is FORBIDDEN: virtual threads "are NOT a license
     * to reorder records, change sort orders, or break sequencing."
     * Any reordering would change the byte-for-byte output of
     * CBTRN03C's paginated report and the COTRN00C browse, breaking
     * the golden-record harness (AAP &sect;0.6.11).
     *
     * <h3>Resource lifecycle</h3>
     * The returned {@link Stream} backs an underlying file channel
     * or DB cursor. Callers MUST close the stream &mdash; typically
     * via try-with-resources:
     * <pre>{@code
     *   try (Stream<TranRecord> transactions = repository.streamSequential()) {
     *       transactions.forEach(this::process);
     *   }
     * }</pre>
     * Failure to close may leak file handles in long-running
     * processes such as the nightly transaction-posting (CBTRN02C)
     * or detail-reporting (CBTRN03C) flows.
     *
     * <h3>Empty-dataset semantics</h3>
     * If the dataset is empty, this method returns an empty
     * (closeable) {@link Stream} rather than {@code null}. This
     * matches the COBOL behavior where the
     * {@code PERFORM UNTIL TRANSACT-EOF} loop simply terminates on
     * the first {@code READ} returning end-of-file, without
     * abending.
     *
     * @return a closeable {@link Stream} of every {@link TranRecord}
     *         in the dataset, in ascending lexicographic
     *         {@code TRAN-ID} key order; never {@code null}.
     */
    Stream<TranRecord> streamSequential();

    /**
     * Stream transactions starting at the first record with
     * {@code TRAN-ID >= startTranId}, in ascending lexicographic
     * key order &mdash; the {@code GTEQ} semantic of CICS
     * {@code STARTBR}.
     *
     * <p>Corresponds to the COBOL paginated-browse idiom against
     * {@code TRANSACT}:
     * <ul>
     *   <li><b>Online (COTRN00C)</b> &mdash;
     *       {@code EXEC CICS STARTBR DATASET(WS-TRANSACT-FILE)
     *       RIDFLD(TRAN-ID) KEYLENGTH(LENGTH OF TRAN-ID)
     *       RESP(WS-RESP-CD)} at
     *       {@code app/cbl/COTRN00C.cbl:L593} positions the cursor.
     *       Note: the source comments out the {@code GTEQ} clause
     *       (line L597), but the Java translation preserves the
     *       conventional {@code GTEQ} semantic that the online
     *       browse logically requires &mdash; the COBOL default
     *       (without {@code GTEQ}) is {@code EQUAL}, which would
     *       fail with {@code DFHRESP(NOTFND)} for any non-existent
     *       starting key; the surrounding code expects forward
     *       positioning and relies on the {@code STARTBR} succeeding
     *       to enter the browse loop. See
     *       {@code MIGRATION_NOTES.md} for the analysis of this
     *       subtle COBOL/CICS quirk.</li>
     *   <li>{@code EXEC CICS READNEXT DATASET(WS-TRANSACT-FILE)
     *       INTO(TRAN-RECORD) RIDFLD(TRAN-ID)} at
     *       {@code app/cbl/COTRN00C.cbl:L626} advances the cursor
     *       forward through the dataset for forward pagination.</li>
     *   <li>The {@code WHEN DFHRESP(ENDFILE)} branch at
     *       {@code app/cbl/COTRN00C.cbl:L639-L645} terminates the
     *       loop with the "You have reached the bottom of the
     *       page..." message; the Java translation models this as
     *       the stream simply running out of elements.</li>
     *   <li>{@code EXEC CICS ENDBR DATASET(WS-TRANSACT-FILE)}
     *       closes the cursor; in Java, closing the returned
     *       stream (via try-with-resources) is the equivalent.</li>
     * </ul>
     *
     * <h3>Parameter contract</h3>
     * <ul>
     *   <li>If {@code startTranId} is {@code null} or
     *       {@link String#isBlank() blank} (all-space or empty
     *       16-character key), this method behaves identically to
     *       {@link #streamSequential()} &mdash; returning the entire
     *       dataset in ascending key order. This matches the COBOL
     *       behavior of {@code STARTBR} positioned at low-values,
     *       which is the conventional "top of file" cursor.</li>
     *   <li>If {@code startTranId} is shorter than 16 characters,
     *       implementations SHOULD right-pad it with spaces to the
     *       16-byte COBOL field width before key comparison.
     *       Comparison is lexicographic (byte-wise), matching
     *       VSAM KSDS key ordering.</li>
     *   <li>If {@code startTranId} is longer than 16 characters,
     *       implementations SHOULD truncate to the first 16
     *       characters to match the COBOL {@code KEYLENGTH(LENGTH
     *       OF TRAN-ID)} clause at
     *       {@code app/cbl/COTRN00C.cbl:L596}.</li>
     * </ul>
     *
     * <h3>Ordering and lifecycle</h3>
     * Same as {@link #streamSequential()}: ascending lexicographic
     * {@code TRAN-ID} order; closeable stream; caller MUST close
     * via try-with-resources to release the underlying file channel
     * or DB cursor.
     *
     * <h3>Empty-result semantics</h3>
     * If no records satisfy {@code TRAN-ID >= startTranId} (i.e.,
     * {@code startTranId} is lexicographically greater than every
     * existing key, or the dataset is empty), this method returns
     * an empty (closeable) {@link Stream}. This is observationally
     * equivalent to the COBOL {@code DFHRESP(NOTFND)} on
     * {@code STARTBR} (when the dataset is empty, or no key
     * satisfies the positioning) at
     * {@code app/cbl/COTRN00C.cbl:L605-L611}, which sets
     * {@code TRANSACT-EOF} and displays the "You are at the top of
     * the page..." message; the application layer can detect the
     * empty stream and surface the analogous message.
     *
     * @param startTranId the 16-character starting key (inclusive);
     *                    {@code null} or blank means "from the
     *                    beginning of the dataset."
     * @return a closeable {@link Stream} of {@link TranRecord}
     *         instances with {@code TRAN-ID >= startTranId}, in
     *         ascending lexicographic key order; never {@code null}.
     */
    Stream<TranRecord> streamFrom(String startTranId);

    /**
     * Stream all transactions for a given card number, accessed via
     * the alternate index on {@code TRAN-CARD-NUM}.
     *
     * <p>Corresponds to the alternate-index access pattern over the
     * {@code TRANSACT} dataset (AAP &sect;0.4.1: "TRANSACT (KSDS +
     * AIX, record 350)"). The AIX is defined on
     * {@code 05 TRAN-CARD-NUM PIC X(16)} at
     * {@code app/cpy/CVTRA05Y.cpy:L15}. This access path is used by
     * customer-statement generation (CBSTM03A pulls per-card
     * transactions to build statement detail lines) and by the
     * online bill-payment program (COBIL00C inspects a customer's
     * recent transactions before applying a payment).
     *
     * <h3>Ordering</h3>
     * Records are returned in ascending {@code TRAN-CARD-NUM} order
     * &mdash; matching the AIX traversal order &mdash; with the
     * secondary tiebreaker of ascending {@code TRAN-ID} for stable
     * ordering of multiple transactions on the same card. Because
     * this method filters by card number, the primary ordering
     * dimension is effectively constant within the returned stream,
     * so the observable order is ascending {@code TRAN-ID} among
     * the matching records. Implementations MUST preserve this
     * order; reordering would change observable output and is
     * FORBIDDEN by AAP &sect;0.1.3 and AAP &sect;0.7.1.
     *
     * <h3>Parameter contract</h3>
     * <ul>
     *   <li>{@code cardNumber} carries the 16-character
     *       {@code TRAN-CARD-NUM} value. Implementations SHOULD
     *       right-pad shorter values with spaces and truncate longer
     *       values to the 16-byte field width before AIX
     *       lookup.</li>
     *   <li>{@code null} input is not permitted and SHOULD raise
     *       {@link NullPointerException} on the caller's side.</li>
     *   <li>If no transactions exist for the supplied card number,
     *       the method returns an empty (closeable) {@link Stream}
     *       &mdash; not {@code null}.</li>
     * </ul>
     *
     * <h3>Resource lifecycle</h3>
     * Same as {@link #streamSequential()}: closeable stream; caller
     * MUST close via try-with-resources:
     * <pre>{@code
     *   try (Stream<TranRecord> cardTxns = repository.streamByCardNumber(card)) {
     *       cardTxns.forEach(this::renderStatementLine);
     *   }
     * }</pre>
     *
     * @param cardNumber the 16-character {@code TRAN-CARD-NUM}
     *                   AIX key; must not be {@code null}.
     * @return a closeable {@link Stream} of {@link TranRecord}
     *         instances matching the supplied card number, in
     *         ascending {@code TRAN-CARD-NUM} order with the
     *         secondary tiebreaker of ascending {@code TRAN-ID};
     *         never {@code null}.
     * @throws NullPointerException if {@code cardNumber} is
     *                              {@code null}.
     */
    Stream<TranRecord> streamByCardNumber(String cardNumber);

    /**
     * Persist a transaction record &mdash; inserts when no record
     * exists for the supplied {@code TRAN-ID}, REWRITEs in-place
     * otherwise (upsert semantics).
     *
     * <p>Corresponds to the COBOL WRITE statements against the
     * {@code TRANSACT} dataset:
     * <ul>
     *   <li><b>Batch sequential output (CBTRN02C)</b> &mdash;
     *       {@code WRITE FD-TRANFILE-REC FROM TRAN-RECORD} at
     *       {@code app/cbl/CBTRN02C.cbl:L564} in the
     *       {@code 2900-WRITE-TRANSACTION-FILE} paragraph, called
     *       once per posted transaction by the full posting engine
     *       to record the newly-posted transaction in the daily
     *       posted-transactions file. The {@code 2900} paragraph
     *       checks {@code TRANFILE-STATUS = '00'} for success and
     *       performs {@code 9999-ABEND-PROGRAM} on any non-success
     *       status (lines L571-L579).</li>
     *   <li><b>Online insert (COTRN02C)</b> &mdash;
     *       {@code EXEC CICS WRITE DATASET(WS-TRANSACT-FILE)
     *       FROM(TRAN-RECORD) LENGTH(LENGTH OF TRAN-RECORD)
     *       RIDFLD(TRAN-ID) KEYLENGTH(LENGTH OF TRAN-ID)
     *       RESP(WS-RESP-CD)} at
     *       {@code app/cbl/COTRN02C.cbl:L713-L721} in the
     *       {@code WRITE-TRANSACT-FILE} paragraph. Issued after the
     *       transaction-add screen has been validated and the
     *       next-ID has been generated via the
     *       {@code STARTBR}/{@code READPREV} idiom (see
     *       {@link #findHighestId()}).</li>
     *   <li><b>Online insert (COBIL00C)</b> &mdash;
     *       {@code EXEC CICS WRITE DATASET(WS-TRANSACT-FILE)
     *       FROM(TRAN-RECORD) LENGTH(LENGTH OF TRAN-RECORD)
     *       RIDFLD(TRAN-ID) KEYLENGTH(LENGTH OF TRAN-ID)
     *       RESP(WS-RESP-CD)} at
     *       {@code app/cbl/COBIL00C.cbl:L512-L520} in the
     *       {@code WRITE-TRANSACT-FILE} paragraph. Issued after the
     *       bill-payment debit has been applied to the account and
     *       the transaction-category balance has been credited, to
     *       record the bill-payment transaction itself.</li>
     *   <li><b>JCL (TRANFILE.jcl)</b> &mdash; IDCAMS REPRO bulk
     *       load from the input flat file during dataset
     *       (re-)load. Each input record becomes one {@code save}
     *       call.</li>
     * </ul>
     *
     * <h3>Upsert semantics</h3>
     * The COBOL semantics differ between the sequential-output and
     * KSDS call sites &mdash; see the class-level
     * "Duplicate-key semantics" section for the full taxonomy. To
     * preserve both observable behaviors without forking the
     * interface:
     * <ul>
     *   <li>If a record with the same {@link TranRecord#tranId()}
     *       already exists in a KSDS-backed adapter, the adapter
     *       MAY either REWRITE in-place (upsert) or throw an
     *       exception (insert-only) &mdash; the composition root
     *       selects the right adapter for each consumer.</li>
     *   <li>If no such record exists, a new record is INSERTED
     *       (WRITTEN).</li>
     *   <li>The sequential-output adapter used by CBTRN02C silently
     *       appends, mirroring the COBOL sequential
     *       {@code WRITE FD-TRANFILE-REC} on a non-indexed output
     *       file.</li>
     * </ul>
     * Collapsing both sequential and indexed WRITE forms into a
     * single {@code save} call simplifies the application layer and
     * matches the conventional Java repository-pattern idiom.
     *
     * <h3>Byte fidelity (AAP &sect;0.6.5)</h3>
     * Implementations MUST persist the entire 350-byte fixed-width
     * record image (including the 20-byte FILLER trailing the
     * {@code TRAN-PROC-TS} field at
     * {@code app/cpy/CVTRA05Y.cpy:L18}). Truncating or normalising
     * the FILLER bytes is FORBIDDEN. The encoded byte image MUST
     * equal {@link TranRecord#encode()} for the supplied
     * {@code transaction}.
     *
     * <h3>Error semantics</h3>
     * Implementations SHOULD raise an unchecked
     * {@link RuntimeException} on any underlying I/O failure,
     * corresponding to the COBOL
     * {@code PERFORM 9999-ABEND-PROGRAM} dispatch in CBTRN02C
     * ({@code app/cbl/CBTRN02C.cbl:L577}) when
     * {@code TRANFILE-STATUS} is non-success, or to the CICS
     * {@code WHEN OTHER} branches in COTRN02C and COBIL00C that
     * display the {@code RESP} / {@code REAS} codes and surface a
     * generic "Unable to Add Transaction..." or "Unable to update
     * TRANSACT..." error. This interface does not prescribe a
     * specific exception class so that adapters may surface the
     * original file-status or DFHRESP / DFHRESP2 codes verbatim.
     *
     * @param transaction the {@link TranRecord} to persist; must
     *                    not be {@code null}.
     * @throws NullPointerException if {@code transaction} is
     *                              {@code null}.
     */
    void save(TranRecord transaction);

    /**
     * Release any underlying resources held by this repository
     * (file channels, DB connections, pre-allocated record
     * buffers).
     *
     * <p>Implementations MUST be idempotent &mdash; calling
     * {@code close()} multiple times on the same instance MUST NOT
     * raise an exception and MUST NOT have observable side-effects
     * beyond the first invocation. In-memory adapters MAY be no-op.
     *
     * <p>Corresponds to the COBOL {@code CLOSE TRANSACT-FILE} verb
     * at the end of CBTRN02C and CBTRN03C
     * ({@code app/cbl/CBTRN03C.cbl:L516}) and to the CICS
     * {@code EXEC CICS ENDBR DATASET(WS-TRANSACT-FILE)} closures of
     * the COTRN00C and COTRN02C browse cursors at
     * {@code app/cbl/COTRN00C.cbl:L702-L706} (analogue) and
     * {@code app/cbl/COTRN02C.cbl:L702-L706}.
     *
     * <p>This method narrows the {@link AutoCloseable#close()}
     * declaration so that implementations are NOT required to
     * declare {@code throws Exception} &mdash; matching the
     * non-checked, idempotent close convention shared by all
     * sibling port interfaces in this package.
     */
    @Override
    void close();
}
