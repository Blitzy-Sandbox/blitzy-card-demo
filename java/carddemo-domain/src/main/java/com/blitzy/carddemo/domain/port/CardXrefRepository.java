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

import com.blitzy.carddemo.domain.record.CardXrefRecord;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Domain port for the {@code CARDXREF} VSAM KSDS dataset (50-byte
 * fixed-width records, copybook {@code app/cpy/CVACT03Y.cpy}) and its
 * alternate index (AIX) on {@code XREF-ACCT-ID}. CARDXREF maps the
 * relationship between cards ({@code XREF-CARD-NUM}, the primary key),
 * customers ({@code XREF-CUST-ID}), and accounts ({@code XREF-ACCT-ID},
 * the AIX key). Concrete adapters live in {@code carddemo-adapter-file}
 * (fixed-width file I/O via {@code java.nio.file}) and optionally in
 * {@code carddemo-adapter-db} (JDBC). The composition root in
 * {@code carddemo-app} wires the appropriate implementation at startup
 * per AAP &sect;0.3.6.
 *
 * <h2>COBOL consumers</h2>
 * This port consolidates the {@code CARDXREF} access modes exercised by
 * the following COBOL programs:
 * <ul>
 *   <li><b>CBACT03C</b> ({@code app/cbl/CBACT03C.cbl:L1-L165}) &mdash;
 *       batch sequential dump driven by
 *       {@code READ XREFFILE-FILE INTO CARD-XREF-RECORD}
 *       ({@code app/cbl/CBACT03C.cbl:L92-L116}) over an
 *       {@code ORGANIZATION IS INDEXED ACCESS MODE IS SEQUENTIAL
 *       RECORD KEY IS FD-XREF-CARD-NUM} SELECT clause
 *       ({@code app/cbl/CBACT03C.cbl:L29-L33}). Maps to
 *       {@link #streamSequential()}.</li>
 *   <li><b>CBTRN02C</b> ({@code app/cbl/CBTRN02C.cbl:L380-L392})
 *       &mdash; random read by card number in the
 *       {@code 1500-A-LOOKUP-XREF} paragraph:
 *       {@code MOVE DALYTRAN-CARD-NUM TO FD-XREF-CARD-NUM} followed by
 *       {@code READ XREF-FILE INTO CARD-XREF-RECORD INVALID KEY ...}.
 *       Maps to {@link #findByCardNumber(String)}.</li>
 *   <li><b>COBIL00C</b> ({@code app/cbl/COBIL00C.cbl:L408-L436}) &mdash;
 *       random read by account id via AIX in the
 *       {@code READ-CXACAIX-FILE} paragraph:
 *       {@code EXEC CICS READ DATASET(WS-CXACAIX-FILE)
 *       INTO(CARD-XREF-RECORD) RIDFLD(XREF-ACCT-ID)
 *       KEYLENGTH(LENGTH OF XREF-ACCT-ID)}. Maps to
 *       {@link #findByAccountId(long)}.</li>
 *   <li><b>COTRN02C</b> &mdash; both access paths in a single program:
 *       <ul>
 *         <li>AIX by account id in {@code READ-CXACAIX-FILE}
 *             ({@code app/cbl/COTRN02C.cbl:L578-L586}): same shape as
 *             COBIL00C above. Maps to
 *             {@link #findByAccountId(long)}.</li>
 *         <li>Primary key by card number in {@code READ-CCXREF-FILE}
 *             ({@code app/cbl/COTRN02C.cbl:L611-L619}):
 *             {@code EXEC CICS READ DATASET(WS-CCXREF-FILE)
 *             INTO(CARD-XREF-RECORD) RIDFLD(XREF-CARD-NUM)
 *             KEYLENGTH(LENGTH OF XREF-CARD-NUM)}. Maps to
 *             {@link #findByCardNumber(String)}.</li>
 *       </ul></li>
 *   <li><b>COACTVWC</b> ({@code app/cbl/COACTVWC.cbl:L723-L735}) &mdash;
 *       random read by account id via AIX in the
 *       {@code 9200-GETCARDXREF-BYACCT} paragraph:
 *       {@code EXEC CICS READ DATASET(LIT-CARDXREFNAME-ACCT-PATH)
 *       RIDFLD(WS-CARD-RID-ACCT-ID-X) INTO(CARD-XREF-RECORD)}. Maps to
 *       {@link #findByAccountId(long)}.</li>
 *   <li><b>XREFFILE.jcl</b> ({@code app/jcl/XREFFILE.jcl}) &mdash;
 *       IDCAMS {@code DELETE} / {@code DEFINE} / {@code REPRO} cycle
 *       that bulk-loads CARDXREF and (re-)builds the AIX. Maps to
 *       {@link #delete(String)} (pre-define cleanup) and
 *       {@link #save(CardXrefRecord)} (bulk load).</li>
 * </ul>
 *
 * <h2>Key semantics</h2>
 * <ul>
 *   <li><b>Primary key {@code XREF-CARD-NUM}</b> &mdash; declared
 *       {@code PIC X(16)} (alphanumeric, NOT numeric) per copybook
 *       {@code app/cpy/CVACT03Y.cpy:L5}. Leading zeros, embedded spaces,
 *       and any other byte values present in the source data MUST be
 *       preserved verbatim. Modelling this key as a Java {@code long}
 *       would silently lose leading zeros and reject any non-digit
 *       byte the source fixture might contain &mdash; both of those
 *       outcomes would violate AAP &sect;0.6.5 (byte-for-byte
 *       fidelity). This port therefore models the key as
 *       {@link String}.</li>
 *   <li><b>Alternate index {@code XREF-ACCT-ID}</b> &mdash; declared
 *       {@code PIC 9(11)} per copybook {@code app/cpy/CVACT03Y.cpy:L7}.
 *       Modelled as primitive {@code long} for direct fidelity to the
 *       11-digit numeric range
 *       ({@code 0..99_999_999_999L = 10^11 - 1}). The
 *       {@link CardXrefRecord} canonical constructor enforces this
 *       upper bound.</li>
 * </ul>
 *
 * <h2>AIX uniqueness</h2>
 * The AIX on {@code XREF-ACCT-ID} is <strong>not</strong> unique in
 * the general case: a single account may have multiple cards (an
 * account-id row points to one or more 16-byte card-num rows). The
 * COBOL {@code EXEC CICS READ} on the AIX path returns the
 * <strong>first</strong> matching record per VSAM AIX iteration order
 * (typically ascending {@code XREF-CARD-NUM} within an account). This
 * port surfaces both modes:
 * <ul>
 *   <li>{@link #findByAccountId(long)} returns the
 *       <strong>first</strong> matching record &mdash; identical to
 *       the COBOL {@code EXEC CICS READ} semantics exercised by
 *       COBIL00C, COTRN02C, and COACTVWC.</li>
 *   <li>{@link #streamByAccountId(long)} returns
 *       <strong>all</strong> matching records &mdash; provided for
 *       application logic that needs to enumerate every card linked
 *       to an account (e.g., account closure, card-block sweeps).
 *       No active COBOL program performs an AIX
 *       {@code STARTBR/READNEXT} loop today, but the access path is
 *       reserved for completeness and to allow downstream callers to
 *       avoid a sequential scan of the entire primary key space.</li>
 * </ul>
 *
 * <h2>Ordering</h2>
 * <ul>
 *   <li>{@link #streamSequential()} returns records in ascending
 *       {@code XREF-CARD-NUM} lexicographic byte order &mdash; matching
 *       the COBOL VSAM KSDS primary-key traversal order exercised by
 *       CBACT03C (SELECT clause: {@code ORGANIZATION IS INDEXED
 *       ACCESS MODE IS SEQUENTIAL RECORD KEY IS FD-XREF-CARD-NUM},
 *       {@code app/cbl/CBACT03C.cbl:L29-L33}). Implementations MUST
 *       preserve this order; any reordering would change the
 *       observable output of CBACT03C, which is forbidden by AAP
 *       &sect;0.7.1 ("All file naming conventions, sort orders, and
 *       batch sequencing").</li>
 *   <li>{@link #streamByAccountId(long)} returns records in AIX
 *       iteration order &mdash; typically ascending
 *       {@code XREF-CARD-NUM} within the account, matching the VSAM
 *       AIX implementation order.</li>
 * </ul>
 *
 * <h2>Byte-fidelity (AAP &sect;0.6.5)</h2>
 * Implementations MUST persist the entire 50-byte fixed-width record
 * image including the trailing 14-byte {@code FILLER}
 * ({@link CardXrefRecord#filler()}). The FILLER is structural padding
 * and not business data, but byte-for-byte round-trip equality
 * ({@code parse(b).encode() == b}) is the formal contract with
 * external file consumers. Truncating, normalising, or synthesising
 * the FILLER bytes is FORBIDDEN.
 *
 * <h2>Resource lifecycle</h2>
 * The {@link Stream} returned by {@link #streamSequential()} and
 * {@link #streamByAccountId(long)} extends {@link AutoCloseable} (via
 * {@link java.util.stream.BaseStream}). Callers MUST close the
 * returned stream &mdash; typically via try-with-resources &mdash; so
 * that the adapter can release the underlying file channel, DB cursor,
 * or pre-allocated record buffer. The repository instance itself is
 * also {@link AutoCloseable} for the same reason; implementations MAY
 * be no-op for in-memory adapters and MUST be idempotent.
 *
 * <h2>PCI / security note (AAP &sect;0.7.2)</h2>
 * The primary key handled by this port is the Primary Account Number
 * (PAN). Per AAP &sect;0.7.2, PAN MUST NOT appear in logs or error
 * messages in plaintext: only the last 4 digits may be visible.
 * Implementations of this port MUST:
 * <ul>
 *   <li>Never log the full 16-character {@code cardNumber} parameter
 *       passed to {@link #findByCardNumber(String)} or
 *       {@link #delete(String)}. Mask all but the last 4 digits when
 *       logging.</li>
 *   <li>Never log the {@link CardXrefRecord#xrefCardNum()} component
 *       of a {@link CardXrefRecord} returned by or accepted into this
 *       port.</li>
 *   <li>Never include the PAN in exception messages that may cross
 *       the application / operator boundary.</li>
 * </ul>
 *
 * <h2>No framework dependencies</h2>
 * This interface deliberately avoids Spring, Hibernate, JPA, and Lombok
 * imports per AAP &sect;0.1.1 (the user mandate that no framework be
 * introduced that the existing COBOL program does not require). It
 * also avoids {@code java.io.File}, {@code java.util.Date}, and
 * {@code java.util.Calendar} per AAP &sect;0.6.4 and &sect;0.6.5.
 * Concrete adapters provide their own dependency-injection wiring via
 * the composition root.
 *
 * <h2>No {@code default} methods</h2>
 * This interface contains no {@code default} method implementations.
 * Every method is abstract so that each adapter (file, JDBC, in-memory
 * test double) provides an explicit implementation. This matches the
 * pure-port design of {@code carddemo-domain} per AAP &sect;0.3.2.
 *
 * @see CardXrefRecord
 * @see com.blitzy.carddemo.domain.record.CardXrefRecord
 * @since 1.0.0
 */
public interface CardXrefRepository extends AutoCloseable {

    /**
     * Random read by primary key ({@code XREF-CARD-NUM}, PIC X(16)).
     *
     * <p>Corresponds to two COBOL access patterns:
     * <ul>
     *   <li>Batch primary-key read in CBTRN02C
     *       {@code 1500-A-LOOKUP-XREF}
     *       ({@code app/cbl/CBTRN02C.cbl:L380-L392}):
     *       <pre>{@code
     *       MOVE DALYTRAN-CARD-NUM TO FD-XREF-CARD-NUM
     *       READ XREF-FILE INTO CARD-XREF-RECORD
     *          INVALID KEY
     *            MOVE 100 TO WS-VALIDATION-FAIL-REASON
     *            MOVE 'INVALID CARD NUMBER FOUND'
     *              TO WS-VALIDATION-FAIL-REASON-DESC
     *          NOT INVALID KEY
     *              CONTINUE
     *       END-READ
     *       }</pre></li>
     *   <li>Online primary-key read in COTRN02C
     *       {@code READ-CCXREF-FILE}
     *       ({@code app/cbl/COTRN02C.cbl:L611-L619}):
     *       <pre>{@code
     *       EXEC CICS READ
     *            DATASET   (WS-CCXREF-FILE)
     *            INTO      (CARD-XREF-RECORD)
     *            LENGTH    (LENGTH OF CARD-XREF-RECORD)
     *            RIDFLD    (XREF-CARD-NUM)
     *            KEYLENGTH (LENGTH OF XREF-CARD-NUM)
     *            RESP      (WS-RESP-CD)
     *            RESP2     (WS-REAS-CD)
     *       END-EXEC
     *       }</pre></li>
     * </ul>
     *
     * <h4>Return semantics</h4>
     * <ul>
     *   <li>{@code Optional.of(record)} &mdash; corresponds to the
     *       {@code NOT INVALID KEY} (batch) or
     *       {@code WHEN DFHRESP(NORMAL)} (CICS) branch.</li>
     *   <li>{@link Optional#empty()} &mdash; corresponds to
     *       {@code INVALID KEY} (batch) or
     *       {@code WHEN DFHRESP(NOTFND)} (CICS). The COBOL caller
     *       sets {@code WS-VALIDATION-FAIL-REASON = 100} and
     *       {@code WS-VALIDATION-FAIL-REASON-DESC = 'INVALID CARD
     *       NUMBER FOUND'} (CBTRN02C) or
     *       {@code WS-MESSAGE = 'Card Number NOT found...'}
     *       (COTRN02C).</li>
     *   <li>A {@link RuntimeException} &mdash; corresponds to the
     *       {@code WHEN OTHER} branch of the COBOL CICS EVALUATE.
     *       This interface does not prescribe a specific exception
     *       class so that adapters may surface the original
     *       file-status or DFHRESP / DFHRESP2 codes verbatim.</li>
     * </ul>
     *
     * <h4>Parameter contract</h4>
     * {@code cardNumber} carries the {@code XREF-CARD-NUM} key
     * (PIC X(16) per {@code app/cpy/CVACT03Y.cpy:L5}) &mdash;
     * alphanumeric, preserving leading zeros and any non-digit bytes
     * present in the source data. The {@link CardXrefRecord}
     * canonical constructor enforces the 16-character upper bound on
     * the value stored inside a record; this method accepts the key
     * parameter as-is so adapters may report a richer diagnostic for
     * malformed keys.
     *
     * <h4>Logging</h4>
     * Implementations MUST NOT log this value in plaintext per AAP
     * &sect;0.7.2; mask all but the last 4 digits.
     *
     * @param cardNumber the {@code XREF-CARD-NUM} primary key; never
     *                   {@code null}.
     * @return an {@link Optional} carrying the matching
     *         {@link CardXrefRecord}, or {@link Optional#empty()} if
     *         no record exists with that key.
     * @throws NullPointerException if {@code cardNumber} is
     *                              {@code null}.
     */
    Optional<CardXrefRecord> findByCardNumber(String cardNumber);

    /**
     * Random read by alternate-index key ({@code XREF-ACCT-ID},
     * PIC 9(11)). Returns the first matching record per AIX iteration
     * order &mdash; identical to the COBOL {@code EXEC CICS READ}
     * semantics on the CXACAIX path.
     *
     * <p>Corresponds to three COBOL access patterns:
     * <ul>
     *   <li>Bill payment account lookup in COBIL00C
     *       {@code READ-CXACAIX-FILE}
     *       ({@code app/cbl/COBIL00C.cbl:L408-L436}):
     *       <pre>{@code
     *       EXEC CICS READ
     *            DATASET   (WS-CXACAIX-FILE)
     *            INTO      (CARD-XREF-RECORD)
     *            LENGTH    (LENGTH OF CARD-XREF-RECORD)
     *            RIDFLD    (XREF-ACCT-ID)
     *            KEYLENGTH (LENGTH OF XREF-ACCT-ID)
     *            RESP      (WS-RESP-CD)
     *            RESP2     (WS-REAS-CD)
     *       END-EXEC
     *       }</pre></li>
     *   <li>Transaction-add account lookup in COTRN02C
     *       {@code READ-CXACAIX-FILE}
     *       ({@code app/cbl/COTRN02C.cbl:L578-L586}): same shape as
     *       COBIL00C.</li>
     *   <li>Account-view cross-reference lookup in COACTVWC
     *       {@code 9200-GETCARDXREF-BYACCT}
     *       ({@code app/cbl/COACTVWC.cbl:L723-L735}):
     *       <pre>{@code
     *       EXEC CICS READ
     *            DATASET   (LIT-CARDXREFNAME-ACCT-PATH)
     *            RIDFLD    (WS-CARD-RID-ACCT-ID-X)
     *            KEYLENGTH (LENGTH OF WS-CARD-RID-ACCT-ID-X)
     *            INTO      (CARD-XREF-RECORD)
     *            LENGTH    (LENGTH OF CARD-XREF-RECORD)
     *            RESP      (WS-RESP-CD)
     *            RESP2     (WS-REAS-CD)
     *       END-EXEC
     *       }</pre>
     *       The COBOL caller then moves
     *       {@code XREF-CUST-ID -&gt; CDEMO-CUST-ID} and
     *       {@code XREF-CARD-NUM -&gt; CDEMO-CARD-NUM}
     *       ({@code app/cbl/COACTVWC.cbl:L739-L740}).</li>
     * </ul>
     *
     * <h4>Return semantics</h4>
     * <ul>
     *   <li>{@code Optional.of(record)} &mdash; corresponds to the
     *       {@code WHEN DFHRESP(NORMAL)} branch.</li>
     *   <li>{@link Optional#empty()} &mdash; corresponds to
     *       {@code WHEN DFHRESP(NOTFND)}. The COBOL caller surfaces
     *       this as {@code WS-MESSAGE = 'Account ID NOT found...'}
     *       (COBIL00C / COTRN02C) or sets
     *       {@code INPUT-ERROR / FLG-ACCTFILTER-NOT-OK} (COACTVWC).</li>
     *   <li>A {@link RuntimeException} &mdash; corresponds to the
     *       {@code WHEN OTHER} branch of the COBOL CICS EVALUATE.</li>
     * </ul>
     *
     * <h4>Multi-card accounts</h4>
     * Because the AIX is <strong>not unique</strong> in the general
     * case (one account may have multiple cards), this method returns
     * only the <strong>first</strong> matching record per AIX
     * iteration order &mdash; precisely matching the COBOL
     * {@code EXEC CICS READ} semantics. Callers that need to
     * enumerate every card linked to an account MUST use
     * {@link #streamByAccountId(long)} instead.
     *
     * <h4>Parameter contract</h4>
     * {@code accountId} carries the {@code XREF-ACCT-ID} key
     * (PIC 9(11) per {@code app/cpy/CVACT03Y.cpy:L7}) &mdash; valid
     * range is {@code 0..99_999_999_999L} inclusive. The
     * {@link CardXrefRecord} canonical constructor enforces this
     * upper bound on the value stored inside a record; this method
     * accepts the key parameter as-is so adapters may report a
     * richer diagnostic for out-of-range keys.
     *
     * @param accountId the {@code XREF-ACCT-ID} alternate-index key.
     * @return an {@link Optional} carrying the first matching
     *         {@link CardXrefRecord}, or {@link Optional#empty()} if
     *         no record exists with that account id.
     */
    Optional<CardXrefRecord> findByAccountId(long accountId);

    /**
     * Stream every cross-reference record for the supplied
     * {@code XREF-ACCT-ID} via the alternate-index path. Provides
     * multi-record access where the COBOL {@code EXEC CICS READ}
     * returns only the first match; supports use cases where multiple
     * cards are linked to a single account (account closure sweeps,
     * card-block updates, statement multi-card consolidation).
     *
     * <p>No active COBOL program issues an AIX
     * {@code STARTBR/READNEXT} loop today; this method is provided
     * for application-layer logic that needs the full set of cards
     * for a given account without resorting to a sequential scan of
     * the entire primary-key space.
     *
     * <h4>Ordering</h4>
     * Records are returned in AIX iteration order &mdash; typically
     * ascending {@code XREF-CARD-NUM} within the account, matching
     * the VSAM AIX implementation order. Implementations MUST
     * preserve this order so that observable downstream behavior is
     * deterministic.
     *
     * <h4>Empty-result semantics</h4>
     * If no record exists with the supplied {@code accountId}, this
     * method returns an empty {@link Stream}, NOT {@code null} and
     * NOT a stream that throws on first read. This matches the
     * common-case adapter behavior where the AIX lookup yields zero
     * rows.
     *
     * <h4>Resource lifecycle</h4>
     * The returned {@link Stream} backs an underlying file channel
     * or DB cursor. Callers MUST close the stream &mdash; typically
     * via try-with-resources:
     * <pre>{@code
     *   try (Stream<CardXrefRecord> cards =
     *           repository.streamByAccountId(acctId)) {
     *       cards.forEach(this::process);
     *   }
     * }</pre>
     * Failure to close may leak file handles in long-running
     * processes.
     *
     * @param accountId the {@code XREF-ACCT-ID} alternate-index key.
     * @return a closeable {@link Stream} of every matching
     *         {@link CardXrefRecord} in AIX iteration order; empty
     *         (never {@code null}) if no record matches.
     */
    Stream<CardXrefRecord> streamByAccountId(long accountId);

    /**
     * Stream every cross-reference record in ascending
     * {@code XREF-CARD-NUM} order (primary key).
     *
     * <p>Corresponds to the CBACT03C main loop
     * ({@code PERFORM UNTIL END-OF-FILE = 'Y' ... READ XREFFILE-FILE
     * INTO CARD-XREF-RECORD ...},
     * {@code app/cbl/CBACT03C.cbl:L74-L81} for the driver and
     * {@code app/cbl/CBACT03C.cbl:L92-L116} for the {@code READ}
     * paragraph). The COBOL {@code SELECT} declares
     * {@code ORGANIZATION IS INDEXED ACCESS MODE IS SEQUENTIAL
     * RECORD KEY IS FD-XREF-CARD-NUM}
     * ({@code app/cbl/CBACT03C.cbl:L29-L33}), which traverses the
     * KSDS in primary-key order &mdash; the Java translation MUST
     * emit the same order so that the {@code DISPLAY CARD-XREF-RECORD}
     * output byte-stream is identical between the COBOL baseline and
     * the Java implementation (AAP &sect;0.6.5).
     *
     * <h4>Resource lifecycle</h4>
     * The returned {@link Stream} backs an underlying file channel
     * or DB cursor. Callers MUST close the stream &mdash; typically
     * via try-with-resources:
     * <pre>{@code
     *   try (Stream<CardXrefRecord> xrefs =
     *           repository.streamSequential()) {
     *       xrefs.forEach(this::process);
     *   }
     * }</pre>
     *
     * @return a closeable {@link Stream} of every
     *         {@link CardXrefRecord} in the dataset, in ascending
     *         {@code XREF-CARD-NUM} key order.
     */
    Stream<CardXrefRecord> streamSequential();

    /**
     * Upsert a cross-reference record &mdash; inserts when no record
     * exists for the supplied {@code XREF-CARD-NUM} primary key,
     * rewrites otherwise. The adapter is responsible for maintaining
     * AIX consistency: when a record is saved, the AIX must be updated
     * accordingly (or rebuilt in adapter implementations that derive
     * the AIX from a flat file scan).
     *
     * <p>No active COBOL program issues {@code WRITE} or
     * {@code REWRITE} against {@code CARDXREF} at runtime; this
     * operation primarily covers the IDCAMS {@code REPRO} bulk load
     * exercised by {@code app/jcl/XREFFILE.jcl}.
     *
     * <h4>Byte fidelity (AAP &sect;0.6.5)</h4>
     * Implementations MUST persist the entire 50-byte fixed-width
     * record image including the trailing 14-byte {@code FILLER}
     * ({@link CardXrefRecord#filler()}). The FILLER is structural
     * padding and not business data, but byte-for-byte round-trip
     * equality ({@code parse(b).encode() == b}) is the formal
     * contract with external file consumers per AAP &sect;0.6.5.
     * Truncating, normalising, or synthesising the FILLER bytes is
     * FORBIDDEN.
     *
     * <h4>AIX maintenance</h4>
     * The AIX on {@code XREF-ACCT-ID} is a derived structure; when a
     * record is saved, the adapter MUST:
     * <ul>
     *   <li>Reflect any change in {@code xrefAcctId} between an
     *       existing record and the new record by updating the AIX
     *       entries (remove old AIX row, add new AIX row).</li>
     *   <li>Preserve all other existing AIX rows for the same
     *       account when a card-num row is updated in place without
     *       changing its account id.</li>
     * </ul>
     * File-based adapters that rebuild the AIX from a full scan on
     * each save satisfy these requirements trivially; in-memory
     * adapters MUST update both the primary index and the AIX in a
     * single logical operation.
     *
     * <h4>Logging</h4>
     * Implementations MUST NOT log
     * {@link CardXrefRecord#xrefCardNum()} or any other field that
     * would expose the full PAN. See the class-level PCI / security
     * note.
     *
     * @param record the cross-reference record to upsert; never
     *               {@code null}.
     * @throws NullPointerException if {@code record} is {@code null}.
     */
    void save(CardXrefRecord record);

    /**
     * Delete the cross-reference record with the supplied
     * {@code XREF-CARD-NUM} primary key. The adapter is responsible
     * for maintaining AIX consistency: the corresponding AIX row
     * MUST also be removed (or the AIX rebuilt) so that a subsequent
     * {@link #findByAccountId(long)} or
     * {@link #streamByAccountId(long)} no longer sees the deleted
     * record.
     *
     * <p>No active COBOL program issues {@code EXEC CICS DELETE}
     * against {@code CARDXREF} at runtime; this operation is
     * provided for adapter completeness and to support the IDCAMS
     * {@code DELETE} / {@code DEFINE} cycle exercised by
     * {@code app/jcl/XREFFILE.jcl} during dataset (re-)load.
     *
     * <h4>Not-found semantics</h4>
     * If no record exists with the supplied key, implementations
     * MUST throw a {@link RuntimeException} (typically a typed
     * adapter-level exception). This mirrors the
     * {@code DFHRESP(NOTFND)} fall-through on a {@code DELETE}
     * against a missing key &mdash; there is no compensating no-op
     * in the COBOL idiom.
     *
     * <h4>Logging</h4>
     * Implementations MUST NOT log the full 16-character key in
     * plaintext. Mask all but the last 4 digits per AAP &sect;0.7.2.
     *
     * @param cardNumber the {@code XREF-CARD-NUM} primary key; never
     *                   {@code null}.
     * @throws NullPointerException if {@code cardNumber} is
     *                              {@code null}.
     * @throws RuntimeException     if no record exists with that key.
     */
    void delete(String cardNumber);

    /**
     * Release any underlying resources held by this repository
     * &mdash; file channels, DB cursors, pre-allocated buffers, etc.
     * Implementations MAY be no-op for in-memory adapters and MUST
     * be idempotent so that callers can defensively close the same
     * repository multiple times without observable side-effects.
     *
     * <p>Declared without {@code throws Exception} to relieve callers
     * of checked-exception boilerplate; concrete adapter exceptions
     * MUST be wrapped in {@link RuntimeException} subtypes. This
     * mirrors the prevailing sibling pattern across the
     * {@code carddemo-domain.port} package
     * ({@code AccountRepository}, {@code CardRepository},
     * {@code CustomerRepository}, etc.) so that all repositories
     * compose uniformly in try-with-resources blocks at the
     * composition root in {@code carddemo-app}.
     */
    @Override
    void close();
}
