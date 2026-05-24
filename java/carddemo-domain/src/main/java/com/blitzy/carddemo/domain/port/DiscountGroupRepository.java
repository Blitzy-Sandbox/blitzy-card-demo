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

import com.blitzy.carddemo.domain.record.DisGroupRecord;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Domain port abstraction for the {@code DISCGRP} VSAM KSDS dataset &mdash;
 * the per-account-group / per-transaction-type / per-category disclosure
 * interest-rate lookup table. Each record is 50 bytes total: a 16-byte
 * composite {@code DIS-GROUP-KEY} ({@code DIS-ACCT-GROUP-ID PIC X(10)} +
 * {@code DIS-TRAN-TYPE-CD PIC X(02)} + {@code DIS-TRAN-CAT-CD PIC 9(04)})
 * followed by a 6-byte {@code DIS-INT-RATE PIC S9(04)V99} zoned-decimal
 * field and a 28-byte trailing {@code FILLER PIC X(28)} per copybook
 * {@code CVTRA02Y} {@code [app/cpy/CVTRA02Y.cpy:L4-L10]}.
 *
 * <h2>COBOL source provenance</h2>
 * Translated from the {@code DISCGRP-FILE} access pattern declared in the
 * {@code CBACT04C} interest-calculation engine. The COBOL declarations are:
 * <pre>{@code
 *   SELECT DISCGRP-FILE ASSIGN TO DISCGRP
 *          ORGANIZATION IS INDEXED
 *          ACCESS MODE  IS RANDOM
 *          RECORD KEY   IS FD-DISCGRP-KEY
 *          FILE STATUS  IS DISCGRP-STATUS.
 *
 *   FD  DISCGRP-FILE.
 *   01  FD-DISCGRP-REC.
 *       05 FD-DISCGRP-KEY.
 *          10 FD-DIS-ACCT-GROUP-ID    PIC X(10).
 *          10 FD-DIS-TRAN-TYPE-CD     PIC X(02).
 *          10 FD-DIS-TRAN-CAT-CD      PIC 9(04).
 *       05 FD-DISCGRP-DATA            PIC X(34).
 * }</pre>
 * {@code [app/cbl/CBACT04C.cbl:L47-L51,L76-L82]}.
 *
 * <h2>COBOL consumers (per AAP &sect;0.4.1)</h2>
 * <ul>
 *   <li><b>{@code CBACT04C}</b> &mdash; monthly interest-calculation engine
 *       (driven by JCL {@code app/jcl/INTCALC.jcl}). The
 *       {@code 1200-GET-INTEREST-RATE} paragraph issues a random
 *       {@code READ DISCGRP-FILE INTO DIS-GROUP-RECORD INVALID KEY ...}
 *       at {@code [app/cbl/CBACT04C.cbl:L416-L420]} after composing the
 *       composite key via three sequential {@code MOVE} statements:
 *       <pre>{@code
 *         MOVE ACCT-GROUP-ID    TO FD-DIS-ACCT-GROUP-ID
 *         MOVE TRANCAT-CD       TO FD-DIS-TRAN-CAT-CD
 *         MOVE TRANCAT-TYPE-CD  TO FD-DIS-TRAN-TYPE-CD
 *       }</pre>
 *       {@code [app/cbl/CBACT04C.cbl:L210-L212]}. The
 *       {@code FILE STATUS = '00' OR '23'} guard at
 *       {@code [app/cbl/CBACT04C.cbl:L422]} treats both NORMAL and
 *       NOT-FOUND as non-fatal &mdash; only OTHER statuses ABEND via
 *       {@code 9999-ABEND-PROGRAM} at
 *       {@code [app/cbl/CBACT04C.cbl:L430-L434]}. Maps to
 *       {@link #findByKey(String, String, int)}.</li>
 *   <li><b>{@code CBACT04C}</b> &mdash; {@code 1200-A-GET-DEFAULT-INT-RATE}
 *       fallback paragraph. When the per-account lookup misses
 *       ({@code FILE STATUS = '23'}), the COBOL flow overwrites the
 *       account-group component of the composite key with the literal
 *       {@code 'DEFAULT'} ({@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID}
 *       at {@code [app/cbl/CBACT04C.cbl:L436-L437]}) and re-issues
 *       {@code READ DISCGRP-FILE INTO DIS-GROUP-RECORD} at
 *       {@code [app/cbl/CBACT04C.cbl:L444]}. The fallback is performed at
 *       the application layer ({@code CbAct04C.getInterestRate}); this
 *       port emits {@link Optional#empty()} for the first miss and the
 *       caller decides whether to retry with {@code "DEFAULT"} or to
 *       ABEND &mdash; see the "DEFAULT fallback is application-layer"
 *       section below. Maps to a second
 *       {@link #findByKey(String, String, int)} call with
 *       {@code accountGroupId = "DEFAULT"} (space-padded to 10 bytes).</li>
 *   <li><b>{@code DISCGRP.jcl}</b> &mdash; IDCAMS {@code DELETE CLUSTER},
 *       {@code DEFINE CLUSTER}, and {@code REPRO} initial seed-load job
 *       ({@code app/jcl/DISCGRP.jcl}). Loads the rate table from the
 *       fixture {@code app/data/ASCII/discgrp.txt} per AAP &sect;0.4.1.
 *       Maps to {@link #delete(String, String, int)} (per-record delete
 *       semantics for fixture re-builds) and {@link #save(DisGroupRecord)}
 *       (REPRO inserts).</li>
 * </ul>
 *
 * <h2>Composite key contract (DIS-GROUP-KEY byte layout)</h2>
 * The 16-byte composite key is exposed across three ordered parameters
 * {@code (accountGroupId, tranTypeCd, tranCatCd)} matching the COBOL
 * declaration order of {@code DIS-GROUP-KEY}
 * {@code [app/cpy/CVTRA02Y.cpy:L5-L8]}:
 * <ul>
 *   <li>{@code DIS-ACCT-GROUP-ID PIC X(10)} at bytes 0..9 &mdash; the
 *       account-group tag, alphanumeric. Typical production values are
 *       {@code "A000000000"}, {@code "ZEROAPR   "}, {@code "DEFAULT   "}
 *       (right-space-padded to 10 bytes).</li>
 *   <li>{@code DIS-TRAN-TYPE-CD PIC X(02)} at bytes 10..11 &mdash; the
 *       2-character transaction-type code (one of the values populated
 *       in the {@code TRANTYPE} dataset; e.g. {@code "01"} = Purchase,
 *       {@code "05"} = Interest).</li>
 *   <li>{@code DIS-TRAN-CAT-CD PIC 9(04)} at bytes 12..15 &mdash; the
 *       4-digit unsigned category code (range {@code 0..9999}; one of
 *       the values populated in the {@code TRANCATG} dataset).</li>
 * </ul>
 * Implementations MUST preserve this byte order when serialising the
 * composite key for VSAM KSDS random reads, IDCAMS-style sequential scans,
 * JDBC-backed adapters, or in-memory tables &mdash; any reordering changes
 * the lexicographic key sort and therefore the observable sequence of
 * {@link #streamSequential()} (FORBIDDEN per AAP &sect;0.1.3 preserve sort
 * orders / batch sequencing).
 *
 * <p>Space-padding convention: shorter caller-supplied
 * {@code accountGroupId} values (e.g. the literal {@code "DEFAULT"} used
 * by the COBOL fallback path, which is 7 characters in the source but
 * stored as {@code "DEFAULT   "} in the 10-byte field) MAY be
 * right-space-padded by the implementation to match the COBOL
 * space-padded comparison semantics of {@code MOVE 'DEFAULT' TO
 * FD-DIS-ACCT-GROUP-ID}. Implementations MUST reject
 * {@code accountGroupId.length() &gt; 10} or
 * {@code tranTypeCd.length() &gt; 2} with an
 * {@link IllegalArgumentException} &mdash; values longer than the field
 * width cannot be encoded into the byte slots without corrupting the
 * record layout, and the {@link DisGroupRecord.DisGroupKey} canonical
 * constructor will reject them.
 *
 * <h2>DEFAULT fallback is application-layer logic (NOT port responsibility)</h2>
 * The COBOL {@code 1200-A-GET-DEFAULT-INT-RATE} fallback to the literal
 * {@code 'DEFAULT'} account-group is implemented in
 * {@code com.blitzy.carddemo.application.account.CbAct04C.getInterestRate}
 * &mdash; NOT in this port. Rationale per AAP &sect;0.6.10 (single-
 * responsibility for ports): a port abstracts data access; business-
 * logic policies such as "retry with DEFAULT on miss" belong to the
 * application use case. The port faithfully returns
 * {@link Optional#empty()} for the original-key miss, and the
 * application class makes a second
 * {@link #findByKey(String, String, int)} call with
 * {@code accountGroupId = "DEFAULT"} when appropriate. Adapter
 * implementations MUST NOT silently retry the lookup with
 * {@code "DEFAULT"} on a miss; doing so would mask the missed key from
 * observability and the application class would have no opportunity to
 * apply the COBOL-faithful logging
 * ({@code DISPLAY 'DISCLOSURE GROUP RECORD MISSING'} and
 * {@code DISPLAY 'TRY WITH DEFAULT GROUP CODE'} at
 * {@code [app/cbl/CBACT04C.cbl:L418-L419]}).
 *
 * <h2>Decimal-rate semantics (DIS-INT-RATE)</h2>
 * The {@code DIS-INT-RATE PIC S9(04)V99} field carried inside each
 * {@link DisGroupRecord} is the monthly periodic interest rate as a
 * percentage with two implicit decimal places (scale 2). The COBOL
 * interest computation in {@code 1300-COMPUTE-INTEREST} is:
 * <pre>{@code
 *   COMPUTE WS-MONTHLY-INT
 *     = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
 * }</pre>
 * {@code [app/cbl/CBACT04C.cbl:L464-L465]}. The COBOL statement carries
 * no {@code ROUNDED} clause, so per AAP &sect;0.6.1 the Java translation
 * applies {@link java.math.RoundingMode#DOWN} (truncation) &mdash; not
 * banker's rounding. The rounding policy is enforced by the
 * application-layer use case, not by this port; this port simply
 * surfaces the {@link DisGroupRecord} whose {@code disIntRate()} is
 * already normalised to scale 2 by the
 * {@link DisGroupRecord} canonical constructor.
 *
 * <h2>FILLER preservation contract (byte-for-byte fidelity, AAP &sect;0.6.5)</h2>
 * The trailing 28-byte {@code FILLER PIC X(28)}
 * {@code [app/cpy/CVTRA02Y.cpy:L10]} carries no semantic meaning but
 * MUST be preserved verbatim by any implementation of
 * {@link #save(DisGroupRecord)} so that
 * {@code Arrays.equals(buf, DisGroupRecord.parse(buf).encode()) == true}
 * round-trips with byte identity for any record read from storage and
 * written back. The {@link DisGroupRecord} canonical constructor
 * defensively clones the FILLER on construction and its
 * {@code filler()} accessor returns a fresh defensive copy;
 * implementations of this port MUST NOT strip, normalise, or zero-fill
 * the FILLER bytes. Likewise the encoded {@code DIS-INT-RATE} zoned-
 * decimal slice MUST be re-emitted byte-for-byte identical to the
 * COBOL representation via the
 * {@code Decimals.encodeZonedDecimal(BigDecimal, int, int)} codec
 * &mdash; this is the byte-for-byte fidelity contract asserted by the
 * golden-record harness on every PR per AAP &sect;0.6.11.
 *
 * <h2>Read-mostly access pattern</h2>
 * The {@code DISCGRP} dataset is small (a single-digit number of rows in
 * the reference fixture {@code app/data/ASCII/discgrp.txt}) and is
 * accessed READ-only at runtime by translated application use cases. No
 * translated COBOL paragraph issues a runtime {@code WRITE} or
 * {@code REWRITE} against {@code DISCGRP} &mdash; the only mutation paths
 * are the IDCAMS {@code REPRO} seed-load and {@code DELETE CLUSTER} /
 * re-{@code DEFINE} cycle declared in {@code DISCGRP.jcl}.
 * {@link #save(DisGroupRecord)} and {@link #delete(String, String, int)}
 * therefore exist exclusively to support those JCL-step translations
 * and are not invoked during normal interest-calculation runs.
 *
 * <p>Because the dataset is small and read-only at runtime,
 * implementations MAY cache the entire contents in memory on first
 * access (typical pattern: load all records into an immutable
 * {@code Map<DisGroupRecord.DisGroupKey, DisGroupRecord>} on first call,
 * keyed by the 16-byte composite key). Caching is an implementation
 * detail and is NOT part of this port's contract.
 *
 * <h2>Threading and resource ownership</h2>
 * Implementations are not required to be thread-safe for write
 * operations ({@link #save(DisGroupRecord)} /
 * {@link #delete(String, String, int)}), which are only invoked from
 * the single-threaded IDCAMS-step translations. The read operations
 * {@link #findByKey(String, String, int)} and
 * {@link #streamSequential()} SHOULD support concurrent invocation
 * from virtual threads (per AAP &sect;0.6.6) so that the
 * {@code CbAct04C} use case can fan-out per-record interest
 * computations without serialising on rate lookups. Streams returned by
 * {@link #streamSequential()} hold an open file handle (or underlying
 * cursor) and are {@link AutoCloseable} (via
 * {@link java.util.stream.BaseStream}); callers MUST use
 * try-with-resources to release the handle &mdash; this is the same
 * contract as {@link java.nio.file.Files#lines(java.nio.file.Path)}.
 *
 * <p>The repository instance itself is also {@link AutoCloseable}
 * (matching the lifecycle convention used by sibling port interfaces
 * in this package &mdash; {@link AccountRepository},
 * {@link CardRepository}, {@link CardXrefRepository},
 * {@link CustomerRepository}, {@link DailyTransactionRepository},
 * {@link TransactionRepository}, and
 * {@link TransactionCategoryBalanceRepository}). The {@link #close()}
 * method releases any backing file channels, DB cursors, or
 * pre-allocated buffers, mirroring the COBOL
 * {@code 9200-DISCGRP-CLOSE} paragraph that issues
 * {@code CLOSE DISCGRP-FILE} at
 * {@code [app/cbl/CBACT04C.cbl:L559-L575]} during program shutdown
 * after the COBOL {@code 1000-MAINLINE} loop terminates
 * ({@code PERFORM 9200-DISCGRP-CLOSE} at
 * {@code [app/cbl/CBACT04C.cbl:L226]}).
 *
 * <h2>Forbidden in implementations (per AAP &sect;0.6 / &sect;0.7.4)</h2>
 * <ul>
 *   <li>No Spring / Hibernate / JPA / Lombok &mdash; constructor
 *       injection and plain factories only.</li>
 *   <li>No {@code java.io.File}, no {@code java.util.Date} /
 *       {@code java.util.Calendar}, no {@code double} / {@code float}
 *       for any record field (per AAP &sect;0.6.1 monetary fidelity).</li>
 *   <li>No {@code ThreadLocal} &mdash; use {@link java.lang.ScopedValue}
 *       for any per-request context propagation (per AAP &sect;0.6.6).</li>
 *   <li>No {@code default} methods on this interface &mdash; concrete
 *       implementations supply every operation.</li>
 * </ul>
 *
 * @see DisGroupRecord
 * @see DisGroupRecord.DisGroupKey
 * @see com.blitzy.carddemo.domain.port.TransactionCategoryBalanceRepository
 * @see com.blitzy.carddemo.domain.port.TransactionTypeRepository
 * @see com.blitzy.carddemo.domain.port.TransactionCategoryRepository
 * @since 1.0.0
 */
public interface DiscountGroupRepository extends AutoCloseable {

    /**
     * Looks up a single {@link DisGroupRecord} by its 16-byte composite
     * {@code DIS-GROUP-KEY} via random-access read &mdash; the Java
     * equivalent of the COBOL random {@code READ DISCGRP-FILE INTO
     * DIS-GROUP-RECORD INVALID KEY ...} issued by paragraph
     * {@code 1200-GET-INTEREST-RATE} at
     * {@code [app/cbl/CBACT04C.cbl:L416-L420]}. The COBOL key is
     * composed via three sequential {@code MOVE} statements at
     * {@code [app/cbl/CBACT04C.cbl:L210-L212]} before the {@code READ}
     * fires.
     *
     * <h3>Empty Optional semantics &mdash; FILE STATUS = '23'</h3>
     * Returns {@link Optional#empty()} when no record matches the
     * supplied composite key. This maps exactly to the COBOL
     * {@code INVALID KEY} clause on {@code READ DISCGRP-FILE}
     * ({@code FILE STATUS = '23'}, record not found). The COBOL
     * baseline at {@code [app/cbl/CBACT04C.cbl:L422,L436-L439]} treats
     * {@code FILE STATUS = '23'} as a non-fatal "not found" by:
     * <ol>
     *   <li>Displaying {@code 'DISCLOSURE GROUP RECORD MISSING'} and
     *       {@code 'TRY WITH DEFAULT GROUP CODE'}
     *       ({@code [app/cbl/CBACT04C.cbl:L418-L419]});</li>
     *   <li>Overwriting the key's {@code FD-DIS-ACCT-GROUP-ID} component
     *       with the literal {@code 'DEFAULT'}
     *       ({@code [app/cbl/CBACT04C.cbl:L437]});</li>
     *   <li>Performing {@code 1200-A-GET-DEFAULT-INT-RATE}, a second
     *       random {@code READ} with the rewritten key
     *       ({@code [app/cbl/CBACT04C.cbl:L444]}).</li>
     * </ol>
     * That fallback policy is translated in the application class
     * ({@code com.blitzy.carddemo.application.account.CbAct04C}), NOT
     * in this port &mdash; callers translating
     * {@code 1200-GET-INTEREST-RATE} faithfully should invoke this
     * method twice (once with the per-account group ID, once with
     * {@code "DEFAULT"}) when the first call returns
     * {@link Optional#empty()}.
     *
     * <h3>Parameter constraints (DIS-GROUP-KEY layout)</h3>
     * <ul>
     *   <li>{@code accountGroupId}: non-null. The COBOL field
     *       {@code FD-DIS-ACCT-GROUP-ID PIC X(10)}
     *       {@code [app/cbl/CBACT04C.cbl:L79]} is a 10-byte
     *       alphanumeric slot, so callers SHOULD provide at most 10
     *       characters; shorter values are right-space-padded by the
     *       implementation to match the COBOL space-padded comparison
     *       semantics of {@code MOVE} into a {@code PIC X(10)} target
     *       (in particular, the COBOL fallback path moves the 7-byte
     *       literal {@code 'DEFAULT'} into the 10-byte field, yielding
     *       a key prefix of {@code "DEFAULT   "}). Implementations
     *       MUST reject {@code accountGroupId.length() > 10} with an
     *       {@link IllegalArgumentException}.</li>
     *   <li>{@code tranTypeCd}: non-null. The COBOL field
     *       {@code FD-DIS-TRAN-TYPE-CD PIC X(02)}
     *       {@code [app/cbl/CBACT04C.cbl:L80]} is a 2-byte alphanumeric
     *       slot. Implementations MUST reject
     *       {@code tranTypeCd.length() > 2} with an
     *       {@link IllegalArgumentException}; shorter values MAY be
     *       right-space-padded.</li>
     *   <li>{@code tranCatCd}: unsigned 4-digit integer in the range
     *       {@code 0..9999} inclusive. The COBOL field
     *       {@code FD-DIS-TRAN-CAT-CD PIC 9(04)}
     *       {@code [app/cbl/CBACT04C.cbl:L81]} cannot hold negative
     *       values or values beyond 4 digits. Implementations MUST
     *       reject {@code tranCatCd &lt; 0 || tranCatCd &gt; 9999}
     *       with an {@link IllegalArgumentException}.</li>
     * </ul>
     *
     * @param accountGroupId the 10-character account-group identifier
     *                       ({@code DIS-ACCT-GROUP-ID PIC X(10)}); may
     *                       be the literal {@code "DEFAULT"} (or
     *                       {@code "DEFAULT   "} pre-padded) for the
     *                       COBOL fallback path
     * @param tranTypeCd     the 2-character transaction-type code
     *                       ({@code DIS-TRAN-TYPE-CD PIC X(02)}) drawn
     *                       from the {@code TRANTYPE} reference dataset
     * @param tranCatCd      the 4-digit unsigned category code
     *                       ({@code DIS-TRAN-CAT-CD PIC 9(04)}) drawn
     *                       from the {@code TRANCATG} reference dataset
     * @return {@link Optional#of(Object)} holding the matching
     *         {@link DisGroupRecord} when found, or
     *         {@link Optional#empty()} when no record exists with the
     *         supplied composite key (mapping to COBOL
     *         {@code FILE STATUS = '23'})
     * @throws NullPointerException     if {@code accountGroupId} or
     *                                  {@code tranTypeCd} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code accountGroupId.length()}
     *                                  exceeds 10,
     *                                  {@code tranTypeCd.length()}
     *                                  exceeds 2, or {@code tranCatCd}
     *                                  is outside the range
     *                                  {@code 0..9999}
     */
    Optional<DisGroupRecord> findByKey(String accountGroupId, String tranTypeCd, int tranCatCd);

    /**
     * Streams every {@link DisGroupRecord} in the underlying
     * {@code DISCGRP} dataset in ascending {@code DIS-GROUP-KEY}
     * (lexicographic / byte) order &mdash; the Java equivalent of a
     * sequential {@code OPEN INPUT DISCGRP-FILE} followed by repeated
     * {@code READ DISCGRP-FILE NEXT RECORD} calls in a hypothetical
     * COBOL sequential cursor over the KSDS index (no translated COBOL
     * program actually performs a sequential scan of {@code DISCGRP};
     * this method is provided for application-startup preload,
     * IDCAMS-style seed-load verification, and end-to-end fixture
     * comparison by the golden-record harness per AAP &sect;0.6.11).
     *
     * <h3>Ordering guarantee</h3>
     * Records are emitted in ascending order of the 16-byte
     * {@code DIS-GROUP-KEY} composite primary key, matching the
     * lexicographic byte ordering of the underlying VSAM KSDS index.
     * Reordering is FORBIDDEN per AAP &sect;0.1.3 (preserve sort orders
     * and batch sequencing): any implementation that returns records in
     * a different order changes observable output and is a defect.
     *
     * <h3>Resource ownership &mdash; AutoCloseable contract</h3>
     * The returned {@link Stream} is {@link AutoCloseable} (via
     * {@link java.util.stream.BaseStream}) and may hold an open file
     * handle (or underlying cursor) for the lifetime of the stream.
     * Callers MUST close the stream via try-with-resources to release
     * the handle:
     * <pre>{@code
     *   try (Stream<DisGroupRecord> groups = repository.streamSequential()) {
     *       groups.forEach(this::process);
     *   }
     * }</pre>
     * Failure to close leaks the underlying file descriptor &mdash;
     * this is the same contract as
     * {@link java.nio.file.Files#lines(java.nio.file.Path)}. In-memory
     * implementations that hold no underlying file handle MAY return a
     * stream whose {@code close()} is a no-op, but callers MUST still
     * write the try-with-resources block so that the contract holds
     * uniformly across implementations.
     *
     * @return an {@link AutoCloseable} {@link Stream} of every
     *         {@link DisGroupRecord} in the {@code DISCGRP} dataset in
     *         ascending {@code DIS-GROUP-KEY} order; never
     *         {@code null} (empty stream when the dataset has no
     *         records)
     */
    Stream<DisGroupRecord> streamSequential();

    /**
     * Upserts the supplied {@link DisGroupRecord} into the
     * {@code DISCGRP} dataset &mdash; insert when no record with the
     * same composite {@code DIS-GROUP-KEY} exists, replace when it
     * does. This operation exists exclusively to cover the IDCAMS
     * {@code REPRO} seed-load path declared in
     * {@code app/jcl/DISCGRP.jcl}; no translated COBOL program issues
     * a runtime {@code WRITE} or {@code REWRITE} against
     * {@code DISCGRP} at runtime (it is a static reference dataset
     * per AAP &sect;0.6.10).
     *
     * <h3>Byte-for-byte fidelity (AAP &sect;0.6.5)</h3>
     * Implementations MUST persist the record byte-for-byte identical
     * to its COBOL representation:
     * <ul>
     *   <li>The 28-byte {@code FILLER PIC X(28)}
     *       {@code [app/cpy/CVTRA02Y.cpy:L10]} MUST be persisted
     *       verbatim exactly as carried on the supplied
     *       {@link DisGroupRecord}; the implementation MUST NOT strip,
     *       normalise, or zero-fill the FILLER bytes.</li>
     *   <li>The 6-byte {@code DIS-INT-RATE PIC S9(04)V99}
     *       {@code [app/cpy/CVTRA02Y.cpy:L9]} MUST be encoded as
     *       signed packed-decimal (zoned-decimal {@code USAGE DISPLAY}
     *       with sign overpunch on the rightmost byte) via the
     *       central {@code Decimals.encodeZonedDecimal(BigDecimal, int,
     *       int)} codec &mdash; not as a plain ASCII number, not as
     *       primitive {@code double}, and not at any scale other than
     *       2. The scale-preservation invariant from AAP &sect;0.1.3
     *       (a {@link java.math.BigDecimal} of {@code 1.20} MUST NOT
     *       normalise to {@code 1.2}) is enforced by the
     *       {@link DisGroupRecord} canonical constructor; the
     *       implementation simply re-encodes whatever value it
     *       receives.</li>
     *   <li>The 16-byte composite key fields
     *       ({@code DIS-ACCT-GROUP-ID PIC X(10)},
     *       {@code DIS-TRAN-TYPE-CD PIC X(02)},
     *       {@code DIS-TRAN-CAT-CD PIC 9(04)}) MUST be space-padded
     *       (alphanumeric) or zero-padded (numeric) per their COBOL
     *       declarations.</li>
     * </ul>
     * Collectively these invariants make the round-trip identity
     * {@code Arrays.equals(buf, DisGroupRecord.parse(buf).encode()) == true}
     * hold &mdash; the formal byte-for-byte fidelity contract asserted
     * by the golden-record harness on every PR per AAP &sect;0.6.11.
     *
     * <h3>Validation</h3>
     * Implementations MUST verify that {@code record} is non-null.
     * Field-level validation (key component lengths, category-code
     * range, FILLER length, rate scale normalisation) is already
     * enforced by the {@link DisGroupRecord} compact canonical
     * constructor and the nested
     * {@link DisGroupRecord.DisGroupKey} canonical constructor, so a
     * successfully constructed record is always safe to persist;
     * defensive cloning of the FILLER bytes has already been applied
     * at record construction time so implementations do not need to
     * re-clone.
     *
     * <h3>Concurrency</h3>
     * Implementations are not required to be thread-safe for this
     * write operation; it is only invoked from the single-threaded
     * IDCAMS-step translation ({@code DefineDiscountGroupApp}).
     * Concurrent invocation behaviour is unspecified.
     *
     * @param record the {@link DisGroupRecord} to insert or replace;
     *               must be non-null. The record carries its own
     *               composite key via {@link DisGroupRecord#disGroupKey()}.
     * @throws NullPointerException if {@code record} is {@code null}
     */
    void save(DisGroupRecord record);

    /**
     * Deletes the {@link DisGroupRecord} keyed by the supplied 16-byte
     * composite {@code DIS-GROUP-KEY}. This operation exists
     * exclusively to support the IDCAMS {@code DELETE CLUSTER} /
     * re-{@code DEFINE} cycle declared in {@code app/jcl/DISCGRP.jcl}
     * (which deletes the entire cluster prior to re-loading);
     * per-record deletes are provided for adapter-level granularity
     * in support of test harnesses and fixture re-builds. No
     * translated COBOL program issues a runtime {@code DELETE}
     * against {@code DISCGRP} at runtime (it is a static reference
     * dataset per AAP &sect;0.6.10).
     *
     * <h3>Not-found behaviour</h3>
     * Implementations MUST throw a
     * {@link java.util.NoSuchElementException} when no record with the
     * supplied composite key exists &mdash; this mirrors the COBOL
     * {@code INVALID KEY} clause on a hypothetical
     * {@code DELETE DISCGRP-FILE} statement
     * ({@code FILE STATUS = '23'}, record not found). Callers that
     * want idempotent delete semantics should call
     * {@link #findByKey(String, String, int)} first and skip the
     * delete when the optional is empty.
     *
     * <h3>Parameter constraints</h3>
     * Same as {@link #findByKey(String, String, int)}:
     * <ul>
     *   <li>{@code accountGroupId}: non-null; at most 10 characters
     *       ({@code DIS-ACCT-GROUP-ID PIC X(10)}). Implementations
     *       MAY right-pad shorter values with ASCII spaces.</li>
     *   <li>{@code tranTypeCd}: non-null; at most 2 characters
     *       ({@code DIS-TRAN-TYPE-CD PIC X(02)}). Implementations
     *       MAY right-pad shorter values with ASCII spaces.</li>
     *   <li>{@code tranCatCd}: unsigned 4-digit integer in the range
     *       {@code 0..9999} inclusive
     *       ({@code DIS-TRAN-CAT-CD PIC 9(04)}).</li>
     * </ul>
     *
     * <h3>Concurrency</h3>
     * Implementations are not required to be thread-safe for this
     * write operation; it is only invoked from the single-threaded
     * IDCAMS-step translation ({@code DefineDiscountGroupApp}).
     * Concurrent invocation behaviour is unspecified.
     *
     * @param accountGroupId the 10-character account-group identifier
     *                       ({@code DIS-ACCT-GROUP-ID PIC X(10)})
     * @param tranTypeCd     the 2-character transaction-type code
     *                       ({@code DIS-TRAN-TYPE-CD PIC X(02)})
     * @param tranCatCd      the 4-digit unsigned category code
     *                       ({@code DIS-TRAN-CAT-CD PIC 9(04)})
     * @throws NullPointerException             if {@code accountGroupId}
     *                                          or {@code tranTypeCd} is
     *                                          {@code null}
     * @throws IllegalArgumentException         if {@code accountGroupId.length()}
     *                                          exceeds 10,
     *                                          {@code tranTypeCd.length()}
     *                                          exceeds 2, or
     *                                          {@code tranCatCd} is
     *                                          outside the range
     *                                          {@code 0..9999}
     * @throws java.util.NoSuchElementException if no record with the
     *                                          supplied composite key
     *                                          exists in the dataset
     */
    void delete(String accountGroupId, String tranTypeCd, int tranCatCd);

    /**
     * Releases any resources held by this repository &mdash; typically
     * the backing VSAM file channel, JDBC cursor, or pre-allocated
     * record buffer.
     *
     * <p>Corresponds to the COBOL {@code 9200-DISCGRP-CLOSE} paragraph
     * which issues {@code CLOSE DISCGRP-FILE} at
     * {@code [app/cbl/CBACT04C.cbl:L559-L575]}, invoked from the
     * {@code 1000-MAINLINE} cleanup section at
     * {@code [app/cbl/CBACT04C.cbl:L226]} after the interest-
     * calculation loop terminates. The CbAct04C batch driver in
     * {@code carddemo-application} composes multiple repository closes
     * via a {@code closeQuietly} helper pattern that swallows
     * close-time exceptions after logging so that one failing close
     * does not prevent the others from running &mdash; matching the
     * COBOL convention of sequencing the 9000-9400 close paragraphs
     * unconditionally even when an earlier paragraph reported a
     * non-zero {@code FILE STATUS}.
     *
     * <h3>Idempotence</h3>
     * Implementations MAY be no-op for in-memory adapters and MUST be
     * idempotent so that callers can defensively close the same
     * repository multiple times without observable side-effects. A
     * second {@link #close()} call after a successful first call MUST
     * be a no-op; it MUST NOT raise an
     * {@link IllegalStateException} or any other exception.
     *
     * <h3>Checked-exception narrowing</h3>
     * This override declares {@code close()} without
     * {@code throws Exception} so that callers do not need
     * checked-exception handling boilerplate. Concrete adapter close
     * failures MUST be wrapped in {@link RuntimeException} subtypes
     * (typically {@link java.io.UncheckedIOException} for file
     * adapters), matching the consistent sibling-port style
     * established by {@link AccountRepository}, {@link CardRepository},
     * {@link CardXrefRepository}, {@link CustomerRepository},
     * {@link DailyTransactionRepository}, {@link TransactionRepository},
     * and {@link TransactionCategoryBalanceRepository}.
     */
    @Override
    void close();
}
