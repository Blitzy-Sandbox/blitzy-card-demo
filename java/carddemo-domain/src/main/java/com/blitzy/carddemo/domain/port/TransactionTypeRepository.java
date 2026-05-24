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

import com.blitzy.carddemo.domain.record.TranTypeRecord;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Domain port abstraction for the {@code TRANTYPE} VSAM KSDS dataset &mdash;
 * the static transaction-type lookup table that maps a 2-character
 * {@code TRAN-TYPE} primary key to a 50-character human-readable description,
 * with a trailing 8-byte {@code FILLER}, for a total record length of 60
 * bytes per copybook {@code CVTRA03Y}
 * {@code [app/cpy/CVTRA03Y.cpy:L4-L7]}.
 *
 * <h2>COBOL source provenance</h2>
 * Translated from the {@code TRANTYPE-FILE} access pattern in
 * {@code CBTRN03C} (transaction detail report writer)
 * {@code [app/cbl/CBTRN03C.cbl:L39-L43,L72-L75]} and from the IDCAMS
 * {@code DELETE CLUSTER} / {@code DEFINE CLUSTER} / {@code REPRO} seed-load
 * job {@code [app/jcl/TRANTYPE.jcl:L22-L62]}. The COBOL declarations are:
 * <pre>{@code
 *   SELECT TRANTYPE-FILE ASSIGN TO TRANTYPE
 *          ORGANIZATION IS INDEXED
 *          ACCESS MODE  IS RANDOM
 *          RECORD KEY   IS FD-TRAN-TYPE
 *          FILE STATUS  IS TRANTYPE-STATUS.
 *
 *   FD  TRANTYPE-FILE.
 *   01 FD-TRANTYPE-REC.
 *      05 FD-TRAN-TYPE       PIC X(02).
 *      05 FD-TRAN-DATA       PIC X(58).
 * }</pre>
 *
 * <h2>COBOL consumers (per AAP &sect;0.4.1)</h2>
 * <ul>
 *   <li>{@code CBTRN03C} &mdash; paginated transaction detail report writer.
 *       Opens {@code TRANTYPE-FILE} once at startup
 *       {@code [app/cbl/CBTRN03C.cbl:L430-L446]} and issues a random
 *       {@code READ TRANTYPE-FILE INTO TRAN-TYPE-RECORD} for every posted
 *       transaction (paragraph {@code 1500-B-LOOKUP-TRANTYPE}) to resolve
 *       the human-readable type description used in the report detail line
 *       {@code [app/cbl/CBTRN03C.cbl:L494-L502]}. The {@code TRAN-TYPE-DESC}
 *       resolved from this lookup is written to {@code TRAN-REPORT-TYPE-DESC}
 *       in paragraph {@code 1120-WRITE-DETAIL}
 *       {@code [app/cbl/CBTRN03C.cbl:L365-L366]}.</li>
 *   <li>{@code CBACT04C} &mdash; interest calculation engine. Does
 *       <strong>not</strong> open {@code TRANTYPE-FILE} directly
 *       {@code [app/cbl/CBACT04C.cbl:L28-L56]}; instead it consumes
 *       {@code TRANCAT-TYPE-CD} (the 2-byte transaction-type code that is
 *       part of the {@code TRAN-CAT-KEY} composite key) read from
 *       {@code TCATBAL-FILE} and uses it as one of the three components of
 *       the {@code DISCGRP-FILE} composite key when looking up the interest
 *       rate {@code [app/cbl/CBACT04C.cbl:L210-L213]}. The Java translation
 *       of {@code CBACT04C} may use this port to <em>validate</em> a
 *       transaction-type code before computing interest, or to enrich the
 *       synthesised interest-posting transaction with the resolved
 *       description; the COBOL baseline does not perform that enrichment
 *       so doing so in Java would be a behaviour change and is therefore
 *       not implied by this port.</li>
 *   <li>{@code TRANTYPE.jcl} &mdash; IDCAMS {@code DELETE CLUSTER},
 *       {@code DEFINE CLUSTER} ({@code KEYS(2 0) RECORDSIZE(60 60) INDEXED}),
 *       and {@code REPRO INFILE(TRANTYPE) OUTFILE(TTYPVSAM)} initial seed
 *       load {@code [app/jcl/TRANTYPE.jcl:L22-L62]}. The seed-load source
 *       flat file is {@code AWS.M2.CARDDEMO.TRANTYPE.PS}, mirrored under
 *       this repository as {@code app/data/ASCII/trantype.txt}.</li>
 * </ul>
 *
 * <h2>Static lookup-table semantics</h2>
 * {@code TRANTYPE} is a <strong>small, bounded, static reference dataset</strong>
 * &mdash; the reference fixture {@code app/data/ASCII/trantype.txt} contains
 * a single-digit number of entries. No translated COBOL program issues a
 * runtime {@code WRITE} or {@code REWRITE} against this dataset; the only
 * mutation paths are the IDCAMS {@code REPRO} seed load and the
 * {@code DELETE CLUSTER} / re-{@code DEFINE} cycle declared in
 * {@code TRANTYPE.jcl}. {@link #save(TranTypeRecord)} and
 * {@link #delete(String)} therefore exist exclusively to support those
 * JCL-step translations and are not invoked by translated application use
 * cases during normal posting or reporting.
 *
 * <p>Because the dataset is small and read-only at runtime, implementations
 * MAY cache the entire contents in memory on first access (typical pattern:
 * load all records into an immutable {@code Map<String, TranTypeRecord>}
 * keyed by {@code TRAN-TYPE} on first call). Caching is an implementation
 * detail and is NOT part of this port's contract.
 *
 * <h2>Data-driven taxonomy (AAP &sect;0.6.10)</h2>
 * Per AAP &sect;0.6.10, a {@code TransactionType} <em>sealed hierarchy</em>
 * may eventually be derived from the records loaded through this port; that
 * sealed hierarchy would be constructed by an application-class factory
 * consuming {@link #streamSequential()} at startup, NOT by this port itself.
 * This port returns plain {@link TranTypeRecord} values; the sealed type is
 * a separate domain concept not exposed by this interface.
 *
 * <h2>FILLER preservation contract (byte-for-byte fidelity, AAP &sect;0.6.5)</h2>
 * The trailing 8-byte {@code FILLER PIC X(08)}
 * {@code [app/cpy/CVTRA03Y.cpy:L7]} carries no semantic meaning but MUST be
 * preserved verbatim by any implementation of {@link #save(TranTypeRecord)}
 * so that {@code parse(buf).encode()} round-trips with byte identity for
 * any record read from storage and written back. The {@link TranTypeRecord}
 * record itself defensively clones the FILLER on construction and on its
 * accessor; implementations of this port MUST NOT strip, substitute, or
 * zero-fill the FILLER bytes. Similarly, the 50-byte {@code TRAN-TYPE-DESC}
 * field is held verbatim (including any space-padding on the right) so the
 * round-trip identity holds.
 *
 * <h2>Threading and resource ownership</h2>
 * Implementations are not required to be thread-safe for write operations
 * but {@link #findByCode(String)} and {@link #streamSequential()} should
 * support concurrent read use from virtual threads (per AAP &sect;0.6.6).
 * Streams returned by {@link #streamSequential()} hold an open file handle
 * (or underlying cursor) and are {@link AutoCloseable}; callers MUST use
 * try-with-resources to release the handle &mdash; this is the same
 * contract as {@link java.nio.file.Files#lines(java.nio.file.Path)}.
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
 *   <li>No {@code default} methods on this interface &mdash; concrete
 *       implementations supply every operation.</li>
 * </ul>
 *
 * @see TranTypeRecord
 * @see com.blitzy.carddemo.domain.port.TransactionCategoryRepository
 * @see com.blitzy.carddemo.domain.port.TransactionCategoryBalanceRepository
 * @see com.blitzy.carddemo.domain.port.DiscountGroupRepository
 * @since 1.0.0
 */
public interface TransactionTypeRepository {

    /**
     * Looks up the {@link TranTypeRecord} keyed by the 2-character
     * {@code TRAN-TYPE} primary key using random access &mdash; the Java
     * equivalent of the COBOL
     * {@code READ TRANTYPE-FILE INTO TRAN-TYPE-RECORD} statement with
     * {@code RIDFLD(FD-TRAN-TYPE)} populated by
     * {@code MOVE TRAN-TYPE-CD OF TRAN-RECORD TO FD-TRAN-TYPE}
     * {@code [app/cbl/CBTRN03C.cbl:L189-L190]}. Used by {@code CBTRN03C}
     * (paragraph {@code 1500-B-LOOKUP-TRANTYPE}
     * {@code [app/cbl/CBTRN03C.cbl:L494-L502]}) to resolve the
     * 50-character human-readable description for each posted
     * transaction before writing the report detail line. The Java
     * translation of {@code CBACT04C} may additionally use this method
     * to validate that a {@code TRANCAT-TYPE-CD} encountered while
     * scanning {@code TCATBAL-FILE}
     * {@code [app/cbl/CBACT04C.cbl:L194,L212]} corresponds to a known
     * transaction type before computing interest.
     *
     * <h3>Empty Optional semantics</h3>
     * Returns {@link Optional#empty()} when no record matches the supplied
     * code &mdash; this corresponds exactly to the COBOL
     * {@code INVALID KEY} clause on
     * {@code READ TRANTYPE-FILE INTO TRAN-TYPE-RECORD}
     * ({@code FILE STATUS '23'}, record not found) observed at
     * {@code [app/cbl/CBTRN03C.cbl:L495-L501]}. The COBOL baseline
     * treats this as a fatal condition: it issues
     * {@code DISPLAY 'INVALID TRANSACTION TYPE : '} and ABENDs via
     * {@code PERFORM 9999-ABEND-PROGRAM}. Callers translating
     * paragraph {@code 1500-B-LOOKUP-TRANTYPE} faithfully should map
     * an empty {@code Optional} to the same ABEND path; callers
     * building non-fatal lookups (e.g. report enrichment with a
     * {@code "UNKNOWN"} fallback description) may handle the empty
     * case themselves. The port itself does NOT throw on missing
     * keys &mdash; that policy belongs to the caller.
     *
     * <h3>Parameter constraints</h3>
     * <ul>
     *   <li>{@code tranTypeCd}: non-null. The COBOL key
     *       {@code FD-TRAN-TYPE PIC X(02)}
     *       {@code [app/cbl/CBTRN03C.cbl:L74]} is a 2-byte field, so
     *       callers SHOULD provide exactly 2 characters to preserve
     *       byte fidelity with the KSDS key. Implementations MUST
     *       reject {@code tranTypeCd.length() > 2} with an
     *       {@link IllegalArgumentException} &mdash; values longer
     *       than 2 characters cannot be encoded into the 2-byte slot
     *       without corrupting the record layout. Implementations
     *       MAY right-pad shorter values with ASCII spaces to match
     *       the COBOL space-padded comparison semantics of
     *       {@code MOVE} into a {@code PIC X(02)} target.</li>
     * </ul>
     *
     * @param tranTypeCd two-character transaction type code
     *                   ({@code TRAN-TYPE} {@code PIC X(02)})
     * @return {@link Optional#of(Object)} holding the matching
     *         {@link TranTypeRecord} when found, or
     *         {@link Optional#empty()} when no record exists with the
     *         supplied code
     * @throws NullPointerException     if {@code tranTypeCd} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code tranTypeCd.length()}
     *                                  exceeds 2
     */
    Optional<TranTypeRecord> findByCode(String tranTypeCd);

    /**
     * Streams every {@link TranTypeRecord} in the underlying
     * {@code TRANTYPE} dataset in ascending {@code TRAN-TYPE}
     * (lexicographic / byte) order &mdash; the Java equivalent of a
     * sequential {@code OPEN INPUT TRANTYPE-FILE} followed by repeated
     * {@code READ TRANTYPE-FILE NEXT RECORD} calls in a hypothetical
     * COBOL sequential cursor over the KSDS index (no translated COBOL
     * program actually performs a sequential scan of {@code TRANTYPE};
     * this method is provided for application-startup preload,
     * IDCAMS-style seed-load verification, sealed-hierarchy factory
     * construction per AAP &sect;0.6.10, and end-to-end fixture
     * comparison by the golden-record harness per AAP &sect;0.6.11).
     *
     * <h3>Ordering guarantee</h3>
     * Records are emitted in ascending order of the 2-byte
     * {@code TRAN-TYPE} primary key &mdash; matching the lexicographic
     * ordering of the underlying VSAM KSDS index defined by
     * {@code KEYS(2 0)} in {@code TRANTYPE.jcl}
     * {@code [app/jcl/TRANTYPE.jcl:L40]}. Reordering is FORBIDDEN per
     * AAP &sect;0.1.3 (preserve sort orders / batch sequencing): any
     * implementation that returns records in a different order changes
     * observable output and is a defect.
     *
     * <h3>Resource ownership</h3>
     * The returned {@link Stream} is {@link AutoCloseable} and holds an
     * open file handle (or underlying cursor) for the lifetime of the
     * stream. Callers MUST close the stream via try-with-resources to
     * release the handle:
     * <pre>{@code
     *   try (Stream<TranTypeRecord> types = repository.streamSequential()) {
     *       types.forEach(this::process);
     *   }
     * }</pre>
     * Failure to close leaks the underlying file descriptor &mdash; this
     * is the same contract as
     * {@link java.nio.file.Files#lines(java.nio.file.Path)}. In-memory
     * implementations that hold no underlying file handle MAY return a
     * stream whose {@code close()} is a no-op, but callers MUST still
     * write the try-with-resources block so that the contract holds
     * uniformly across implementations.
     *
     * @return an {@link AutoCloseable} {@link Stream} of every
     *         {@link TranTypeRecord} in ascending {@code TRAN-TYPE}
     *         order; never {@code null} (empty stream when the dataset
     *         has no records)
     */
    Stream<TranTypeRecord> streamSequential();

    /**
     * Upserts the supplied {@link TranTypeRecord} into the
     * {@code TRANTYPE} dataset &mdash; insert when no record with the
     * same {@code TRAN-TYPE} primary key exists, replace when it does.
     * This operation exists exclusively to cover the IDCAMS
     * {@code REPRO} seed-load path declared in {@code TRANTYPE.jcl}
     * {@code [app/jcl/TRANTYPE.jcl:L52-L62]}; no translated COBOL
     * program issues a runtime {@code WRITE} or {@code REWRITE}
     * against {@code TRANTYPE} (it is a static reference dataset per
     * AAP &sect;0.6.10).
     *
     * <h3>FILLER preservation (AAP &sect;0.6.5)</h3>
     * The trailing 8-byte {@code FILLER PIC X(08)}
     * {@code [app/cpy/CVTRA03Y.cpy:L7]} MUST be persisted verbatim
     * exactly as carried on the supplied {@link TranTypeRecord}; the
     * implementation MUST NOT strip, normalise, or zero-fill the
     * FILLER bytes. Likewise the 50-byte {@code TRAN-TYPE-DESC PIC X(50)}
     * {@code [app/cpy/CVTRA03Y.cpy:L6]} MUST be persisted with its
     * exact space-padding intact. These invariants are what make the
     * round-trip identity
     * {@code Arrays.equals(buf, TranTypeRecord.parse(buf).encode()) == true}
     * hold &mdash; the formal byte-for-byte fidelity contract asserted
     * by the golden-record harness on every PR per AAP &sect;0.6.11.
     *
     * <h3>Validation</h3>
     * Implementations MUST verify that the {@link TranTypeRecord} is
     * non-null. Field-level validation (key length, description length,
     * FILLER length) is already enforced by the compact canonical
     * constructor of {@link TranTypeRecord}, so a successfully
     * constructed record is always safe to persist; defensive cloning
     * of the FILLER bytes has already been applied at record
     * construction time so implementations do not need to re-clone.
     *
     * @param record the {@link TranTypeRecord} to insert or replace;
     *               must be non-null
     * @throws NullPointerException if {@code record} is {@code null}
     */
    void save(TranTypeRecord record);

    /**
     * Deletes the record keyed by the supplied 2-character
     * {@code TRAN-TYPE} primary key. This operation exists
     * exclusively to cover the IDCAMS {@code DELETE CLUSTER} /
     * re-{@code DEFINE} cycle in {@code TRANTYPE.jcl}
     * {@code [app/jcl/TRANTYPE.jcl:L22-L28]} (which deletes the entire
     * cluster prior to re-loading); individual-record deletes are
     * provided for adapter-level granularity in support of test
     * harnesses and fixture re-builds. No translated COBOL program
     * issues a runtime {@code DELETE} against {@code TRANTYPE} (it is
     * a static reference dataset per AAP &sect;0.6.10).
     *
     * <h3>Not-found behaviour</h3>
     * Implementations MUST throw a
     * {@link java.util.NoSuchElementException} when no record with the
     * supplied code exists &mdash; this mirrors the COBOL
     * {@code INVALID KEY} clause on
     * {@code DELETE TRANTYPE-FILE} ({@code FILE STATUS '23'}, record
     * not found). Callers that want idempotent delete semantics should
     * call {@link #findByCode(String)} first and skip the delete when
     * the optional is empty.
     *
     * <h3>Parameter constraints</h3>
     * Same as {@link #findByCode(String)}: {@code tranTypeCd} must be
     * non-null and at most 2 characters long. Implementations MAY
     * right-pad shorter values with ASCII spaces to match the COBOL
     * space-padded comparison semantics of {@code MOVE} into a
     * {@code PIC X(02)} target.
     *
     * @param tranTypeCd two-character transaction type code
     *                   ({@code TRAN-TYPE} {@code PIC X(02)})
     * @throws NullPointerException             if {@code tranTypeCd}
     *                                          is {@code null}
     * @throws IllegalArgumentException         if {@code tranTypeCd.length()}
     *                                          exceeds 2
     * @throws java.util.NoSuchElementException if no record with the
     *                                          supplied code exists
     *                                          in the dataset
     */
    void delete(String tranTypeCd);
}
