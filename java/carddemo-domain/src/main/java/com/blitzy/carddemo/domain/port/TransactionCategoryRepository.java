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

import com.blitzy.carddemo.domain.record.TranCatRecord;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Domain port abstraction for the {@code TRANCATG} VSAM KSDS dataset &mdash;
 * the static transaction-category lookup table that maps a 6-byte composite
 * key ({@code TRAN-TYPE-CD} {@code PIC X(02)} + {@code TRAN-CAT-CD}
 * {@code PIC 9(04)}) to a 50-byte category description plus a 4-byte
 * trailing {@code FILLER} for a total record length of 60 bytes per
 * copybook {@code CVTRA04Y} {@code [app/cpy/CVTRA04Y.cpy:L4-L9]}.
 *
 * <h2>COBOL source provenance</h2>
 * Translated from the {@code TRANCATG-FILE} access pattern in
 * {@code CBTRN03C} (transaction report writer)
 * {@code [app/cbl/CBTRN03C.cbl:L45-L49,L77-L82]} and from the IDCAMS
 * {@code DEFINE CLUSTER} / {@code REPRO} seed-load JCL
 * {@code [app/jcl/TRANCATG.jcl]}. The COBOL declarations are:
 * <pre>{@code
 *   SELECT TRANCATG-FILE ASSIGN TO TRANCATG
 *          ORGANIZATION IS INDEXED
 *          ACCESS MODE  IS RANDOM
 *          RECORD KEY   IS FD-TRAN-CAT-KEY
 *          FILE STATUS  IS TRANCATG-STATUS.
 *
 *   FD  TRANCATG-FILE.
 *   01 FD-TRAN-CAT-RECORD.
 *       05  FD-TRAN-CAT-KEY.
 *          10  FD-TRAN-TYPE-CD                         PIC X(02).
 *          10  FD-TRAN-CAT-CD                          PIC 9(04).
 *       05  FD-TRAN-CAT-DATA                           PIC X(54).
 * }</pre>
 *
 * <h2>COBOL consumers (per AAP &sect;0.4.1)</h2>
 * <ul>
 *   <li>{@code CBTRN03C} &mdash; paginated transaction detail report
 *       writer. Issues a random {@code READ TRANCATG-FILE} for every
 *       posted transaction to resolve the category description before
 *       writing the detail line
 *       {@code [app/cbl/CBTRN03C.cbl:L504-L512]}.</li>
 *   <li>{@code CBACT04C} &mdash; interest calculation engine. References
 *       {@code TRAN-CAT-CD} when emitting the synthesized interest
 *       posting transaction
 *       {@code [app/cbl/CBACT04C.cbl:L483]}; the report-side and
 *       posting-side translations may join {@code TRANCATG} to resolve
 *       category descriptions.</li>
 *   <li>{@code TRANCATG.jcl} &mdash; IDCAMS {@code DELETE} /
 *       {@code DEFINE CLUSTER} / {@code REPRO} initial seed load
 *       ({@code KEYS(6 0) RECORDSIZE(60 60) INDEXED})
 *       {@code [app/jcl/TRANCATG.jcl]}.</li>
 * </ul>
 *
 * <h2>Composite key contract</h2>
 * The composite key is exposed across two ordered parameters
 * {@code (tranTypeCd, tranCatCd)} in the SAME byte order as the COBOL
 * declaration of {@code FD-TRAN-CAT-KEY} &mdash; type-code at bytes 0..1,
 * category-code at bytes 2..5
 * {@code [app/cpy/CVTRA04Y.cpy:L5-L7]}. Repository implementations MUST
 * preserve this ordering when serialising the composite key for VSAM
 * KSDS random reads, IDCAMS-style sequential scans, or any underlying
 * adapter (JDBC, in-memory, file).
 *
 * <h2>Read-only at runtime &mdash; lookup table semantics</h2>
 * {@code TRANCATG} is a static lookup table seeded once via
 * {@code TRANCATG.jcl} and accessed read-only by COBOL programs at
 * runtime &mdash; no COBOL paragraph issues a runtime {@code WRITE} or
 * {@code REWRITE} against this dataset. The {@link #save(TranCatRecord)}
 * and {@link #delete(String, int)} operations therefore exist exclusively
 * to cover the IDCAMS {@code REPRO} seed-load path and the IDCAMS
 * {@code DELETE} / re-{@code DEFINE} cycle declared in
 * {@code TRANCATG.jcl}; they are not invoked by translated application
 * use cases during normal posting or reporting.
 *
 * <h2>FILLER preservation contract (byte-for-byte fidelity, AAP &sect;0.6.5)</h2>
 * The trailing 4-byte {@code FILLER PIC X(04)}
 * {@code [app/cpy/CVTRA04Y.cpy:L9]} carries no semantic meaning but MUST
 * be preserved verbatim by any implementation of {@link #save(TranCatRecord)}
 * so that {@code parse(record).encode()} round-trips with byte identity
 * for any record read from storage and written back. The
 * {@link TranCatRecord} record itself defensively clones the FILLER on
 * construction and accessor; implementations of this port must not strip
 * or substitute the FILLER bytes.
 *
 * <h2>Threading and resource ownership</h2>
 * Implementations are not required to be thread-safe for write
 * operations but {@link #findByKey(String, int)} and
 * {@link #streamSequential()} should support concurrent read use from
 * virtual threads (per AAP &sect;0.6.6). Streams returned by
 * {@link #streamSequential()} hold an open file handle (or underlying
 * cursor) and are {@link AutoCloseable}; callers MUST use
 * try-with-resources to release the handle.
 *
 * <h2>Forbidden in implementations (per AAP &sect;0.6 / &sect;0.7.4)</h2>
 * <ul>
 *   <li>No Spring / Hibernate / JPA / Lombok &mdash; constructor injection
 *       and plain factories only.</li>
 *   <li>No {@code java.io.File}, no {@code java.util.Date} /
 *       {@code Calendar}, no {@code double} / {@code float} for any
 *       record field.</li>
 *   <li>No {@code ThreadLocal} &mdash; use {@code ScopedValue} for any
 *       per-request context propagation.</li>
 * </ul>
 *
 * @see TranCatRecord
 * @see com.blitzy.carddemo.domain.port.TransactionTypeRepository
 * @see com.blitzy.carddemo.domain.port.TransactionCategoryBalanceRepository
 * @since 1.0.0
 */
public interface TransactionCategoryRepository {

    /**
     * Looks up the {@link TranCatRecord} keyed by the composite
     * {@code (tranTypeCd, tranCatCd)} pair using random access &mdash;
     * the Java equivalent of the COBOL
     * {@code READ TRANCATG-FILE INTO TRAN-CAT-RECORD} statement with
     * the composite key populated via
     * {@code MOVE ... TO FD-TRAN-TYPE-CD OF FD-TRAN-CAT-KEY} and
     * {@code MOVE ... TO FD-TRAN-CAT-CD OF FD-TRAN-CAT-KEY}
     * {@code [app/cbl/CBTRN03C.cbl:L191-L194,L504-L512]}. Used by
     * {@code CBTRN03C} (paragraph {@code 1500-C-LOOKUP-TRANCATG})
     * to resolve the category description for each posted transaction
     * before writing the report detail line, and by {@code CBACT04C}
     * interest computation when the engine needs to validate that the
     * synthesized posting's category code exists in the lookup table.
     *
     * <h4>Empty Optional semantics</h4>
     * Returns {@link Optional#empty()} when no record matches the
     * composite key &mdash; this corresponds exactly to the COBOL
     * {@code INVALID KEY} clause / {@code FILE STATUS '23'} (record
     * not found) condition observed at
     * {@code [app/cbl/CBTRN03C.cbl:L506-L510]}. Callers translating
     * paragraph {@code 1500-C-LOOKUP-TRANCATG} faithfully should map
     * an empty result to the same ABEND path; callers building
     * non-fatal lookups (e.g. report enrichment with a {@code "UNKNOWN"}
     * fallback description) may handle the empty case themselves.
     *
     * <h4>Parameter constraints</h4>
     * <ul>
     *   <li>{@code tranTypeCd}: non-null, exactly 2 characters
     *       (COBOL {@code PIC X(02)}
     *       {@code [app/cpy/CVTRA04Y.cpy:L6]}). Implementations MAY
     *       right-pad with spaces if a shorter value is supplied;
     *       however callers are expected to provide the exact 2-byte
     *       code to preserve byte fidelity with the KSDS key.</li>
     *   <li>{@code tranCatCd}: in the inclusive range
     *       {@code 0..9999} (COBOL {@code PIC 9(04)}
     *       {@code [app/cpy/CVTRA04Y.cpy:L7]}). Implementations MUST
     *       reject values outside this range with an
     *       {@link IllegalArgumentException} &mdash; the field cannot
     *       be encoded into the 4-byte slot without corrupting the
     *       record layout.</li>
     * </ul>
     *
     * @param tranTypeCd two-character transaction type code
     *                   ({@code TRAN-TYPE-CD} {@code PIC X(02)})
     * @param tranCatCd  four-digit transaction category code
     *                   ({@code TRAN-CAT-CD} {@code PIC 9(04)}),
     *                   inclusive range {@code 0..9999}
     * @return {@link Optional#of(Object)} holding the matching
     *         {@link TranCatRecord} when found, or
     *         {@link Optional#empty()} when no record exists with the
     *         supplied composite key
     * @throws NullPointerException     if {@code tranTypeCd} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code tranTypeCd.length()}
     *                                  exceeds 2 or if {@code tranCatCd}
     *                                  is outside {@code 0..9999}
     */
    Optional<TranCatRecord> findByKey(String tranTypeCd, int tranCatCd);

    /**
     * Streams every {@link TranCatRecord} in the underlying
     * {@code TRANCATG} dataset in ascending composite-key order
     * &mdash; the Java equivalent of a sequential
     * {@code OPEN INPUT TRANCATG-FILE} followed by repeated
     * {@code READ TRANCATG-FILE NEXT RECORD} calls in the COBOL
     * baseline (KSDS sequential cursor over the index). Used for
     * report generation, IDCAMS-style seed-load verification, and
     * end-to-end fixture comparison by the golden-record harness
     * (AAP &sect;0.6.11).
     *
     * <h4>Ordering guarantee</h4>
     * Records are emitted in ascending order of the 6-byte composite
     * key &mdash; {@code TRAN-TYPE-CD} first, then {@code TRAN-CAT-CD}
     * &mdash; matching the lexicographic ordering of the underlying
     * VSAM KSDS index defined by {@code KEYS(6 0)} in
     * {@code TRANCATG.jcl} {@code [app/jcl/TRANCATG.jcl]}. Reordering
     * is FORBIDDEN per AAP &sect;0.1.3 (preserve sort orders / batch
     * sequencing).
     *
     * <h4>Resource ownership</h4>
     * The returned {@link Stream} is {@link AutoCloseable} and holds
     * an open file handle (or underlying cursor) for the lifetime of
     * the stream. Callers MUST close the stream via try-with-resources
     * to release the handle:
     * <pre>{@code
     *   try (Stream<TranCatRecord> cats = repository.streamSequential()) {
     *       cats.forEach(this::process);
     *   }
     * }</pre>
     * Failure to close leaks the underlying file descriptor &mdash;
     * this is the same contract as
     * {@link java.nio.file.Files#lines(java.nio.file.Path)}.
     *
     * @return an {@link AutoCloseable} {@link Stream} of every
     *         {@link TranCatRecord} in ascending composite-key
     *         {@code (TRAN-TYPE-CD, TRAN-CAT-CD)} order; never
     *         {@code null} (empty stream when the dataset has no
     *         records)
     */
    Stream<TranCatRecord> streamSequential();

    /**
     * Upserts the supplied {@link TranCatRecord} into the
     * {@code TRANCATG} dataset &mdash; insert when no record with the
     * same composite key exists, replace when it does. This operation
     * exists exclusively to cover the IDCAMS {@code REPRO} seed-load
     * path declared in {@code TRANCATG.jcl}
     * {@code [app/jcl/TRANCATG.jcl]}; no translated COBOL program
     * issues a runtime {@code WRITE} or {@code REWRITE} against
     * {@code TRANCATG} (it is a static lookup table per AAP
     * &sect;0.6.10).
     *
     * <h4>FILLER preservation (AAP &sect;0.6.5)</h4>
     * The trailing 4-byte {@code FILLER PIC X(04)}
     * {@code [app/cpy/CVTRA04Y.cpy:L9]} MUST be persisted verbatim
     * exactly as carried on the supplied {@link TranCatRecord}; the
     * implementation MUST NOT strip, normalise, or zero-fill the
     * FILLER bytes. This invariant is what makes the round-trip
     * identity {@code Arrays.equals(buf, parse(buf).encode()) == true}
     * hold (the formal byte-for-byte fidelity contract asserted by
     * the golden-record harness on every PR).
     *
     * <h4>Validation</h4>
     * Implementations MUST verify that the {@link TranCatRecord} is
     * non-null. Field-level validation (key components in range,
     * description length, FILLER length) is already enforced by the
     * compact canonical constructor of {@link TranCatRecord} and its
     * nested {@code TranCatKey}, so a successfully constructed record
     * is always safe to persist.
     *
     * @param record the {@link TranCatRecord} to insert or replace;
     *               must be non-null
     * @throws NullPointerException if {@code record} is {@code null}
     */
    void save(TranCatRecord record);

    /**
     * Deletes the record keyed by the composite
     * {@code (tranTypeCd, tranCatCd)} pair. This operation exists
     * exclusively to cover the IDCAMS {@code DELETE CLUSTER} /
     * re-{@code DEFINE} cycle in {@code TRANCATG.jcl}
     * {@code [app/jcl/TRANCATG.jcl:STEP05-STEP10]}; no translated
     * COBOL program issues a runtime {@code DELETE} against
     * {@code TRANCATG} (it is a static lookup table per AAP
     * &sect;0.6.10).
     *
     * <h4>Not-found behaviour</h4>
     * Implementations MUST throw a
     * {@link java.util.NoSuchElementException} when no record with
     * the supplied composite key exists &mdash; this mirrors the COBOL
     * {@code INVALID KEY} clause on {@code DELETE TRANCATG-FILE}
     * (FILE STATUS '23', record not found). Callers that want
     * idempotent delete semantics should call
     * {@link #findByKey(String, int)} first and skip the delete when
     * the optional is empty.
     *
     * <h4>Parameter constraints</h4>
     * Same as {@link #findByKey(String, int)}: {@code tranTypeCd}
     * must be non-null and at most 2 characters long; {@code tranCatCd}
     * must be in the inclusive range {@code 0..9999}.
     *
     * @param tranTypeCd two-character transaction type code
     *                   ({@code TRAN-TYPE-CD} {@code PIC X(02)})
     * @param tranCatCd  four-digit transaction category code
     *                   ({@code TRAN-CAT-CD} {@code PIC 9(04)}),
     *                   inclusive range {@code 0..9999}
     * @throws NullPointerException           if {@code tranTypeCd} is
     *                                        {@code null}
     * @throws IllegalArgumentException       if {@code tranTypeCd.length()}
     *                                        exceeds 2 or if
     *                                        {@code tranCatCd} is
     *                                        outside {@code 0..9999}
     * @throws java.util.NoSuchElementException if no record with the
     *                                          supplied composite key
     *                                          exists in the dataset
     */
    void delete(String tranTypeCd, int tranCatCd);
}
